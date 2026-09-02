package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.ChatMessageDto.UserAttachmentInfo;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.session.SessionResumeDeserializer;

/**
 * Message 业务逻辑：
 * - listBySession：按 sessionId 查全部
 * - getById：单查
 * - createUserMessage：持久化用户消息 + 自增 sessions.messageCount
 * - delete：单删
 * - replaceSessionMessages：全量替换会话消息（partial 压缩写回）
 *
 * Phase 4 stub：不调 LLM；只持久化用户消息并返回
 * assistantMessageId = "msg-stub-pending"，streamTopic 形如
 * "/topic/sessions/{id}/stream"（会话级单 topic，对齐 CC 会话单一事件流）。
 * Phase 5 会接真实 LLM 流式。
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** structured_output 序列化/反序列化（项目惯例：各服务静态 ObjectMapper）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MessageMapper messageMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private ToolCallMapper toolCallMapper;

    /**
     * [token-compact-fix ⑤方案B] 模型表 mapper（models.max_context_tokens 窗口解析）·
     * @Autowired(required=false)：无 Spring 上下文 / mapper 缺失时静默回落 1M（对齐实时路径）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    /** [token-compact-fix ⑤方案B] 提供商 mapper（模型全名感知解析）· @Autowired(required=false)。 */
    @Autowired(required = false)
    private ProviderMapper providerMapper;

    /** [token-compact-fix ⑤方案B] settings 单例行 mapper（settings.mainModelName 模型回落）·
     *  @Autowired(required=false)：无 Spring 上下文时跳过 settings 层，仅会话 override。 */
    @Autowired(required = false)
    private SettingsMapper settingsMapper;

    public List<ChatMessageDto> listBySession(String sessionId) {
        // 校验 session 存在
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        List<MessageRecord> all = messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", sessionId).orderBy("created_at", true));
        List<ChatMessageDto> result = new ArrayList<>(all.size());
        for (MessageRecord m : all) {
            result.add(toDto(m));
        }
        // [token-compact-fix ⑤方案B] 重拉上下文快照补算：对末条 assistant 消息挂
        //   contextTokensUsed/percentLeft/contextWindow（实时 complete 事件推这三字段，重拉丢失；
        //   DB 只存 input/output，cache 未存 → 重算值为近似）。不落库，每次重拉重算。
        applyContextSnapshotToLastAssistant(result, sessionId);
        return result;
    }

    /**
     * [token-compact-fix ⑤方案B] 重拉上下文快照补算 · 用户拍板：不落库，每次重算。
     *
     * <p><b>WHY</b>: 实时 {@code message.complete} 事件推 contextWindow/contextTokensUsed/percentLeft
     * （ChatService:559-581，对齐 CC context.ts current_usage/percentLeft），历史消息重拉
     * （GET /messages）不落库 → 重拉丢失。本方法在重拉结果上对<b>末条 assistant 消息</b>（usage 非空，
     * 对齐 toDto :691 input/output 任一非 null 判据）补算三字段，使前端重拉后上下文余量展示与实时一致。
     *
     * <p><b>重算公式（[B1 方案A] V53 cache 落库后完整 usage · 协议分派对齐实时）</b>:
     * {@code contextTokensUsed} 由 {@link ContextUsageCalculator#computeContextTokensUsed} 按协议分派：
     * Anthropic → input + cacheRead + cacheCreate（Claude API 三字段独立）；OpenAI/DeepSeek → 仅 input
     * （prompt_tokens 已含 cache hit，加 cacheRead 会双计）。与实时 complete 事件 ChatService:572-578
     * 同源单点，消除两处漂移。contextWindow = 模型表 models.max_context_tokens（回落 1M，对齐实时路径
     * ChatService:570-571）；模型不可判定（会话 override + settings.mainModelName 均空）→ 不出快照
     * （对齐实时 usage null 省略语义）。percentLeft = max(0, round((1 - used/window)*100))。
     *
     * @param messages  重拉结果列表（toDto 投影后；原地替换末条 assistant 消息）
     * @param sessionId 会话 ID（DB 键）
     */
    private void applyContextSnapshotToLastAssistant(List<ChatMessageDto> messages, String sessionId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 末条 assistant 消息（usage 非空：input/output 任一非 null，对齐 toDto :691 判据）
        int lastAsstIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant
                    && (m.inputTokens() != null || m.outputTokens() != null)) {
                lastAsstIdx = i;
                break;
            }
        }
        if (lastAsstIdx < 0) {
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文快照跳过: 会话 {} 无 usage 非空的 assistant 消息",
                    sessionId);
            }
            return;
        }
        ChatMessageDto asst = messages.get(lastAsstIdx);
        // 会话模型：会话 override → settings.mainModelName → null（不可判定 → 不出快照）
        String model = resolveSessionModel(sessionId);
        if (model == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文快照跳过: 会话 {} 模型不可判定（会话 override + "
                    + "settings.mainModelName 均空），末条 assistant {} 不出快照", sessionId, asst.id());
            }
            return;
        }
        // [token-compact-fix 修复] contextTokensUsed 按协议分派（与实时 complete 事件同源单点，
        //   ContextUsageCalculator）——Anthropic → input+cacheRead+cacheCreate（三字段独立）；
        //   OpenAI/DeepSeek → 仅 input（prompt_tokens 已含 cache hit，加 cacheRead 会双计，主模型
        //   DeepSeek 必走此路）。判定链 = isAnthropic（同 ChatService.providerTypeForModel：
        //   ModelNameResolver.resolve → provider.type=='anthropic'）。DB 读回 Integer cache →
        //   null 容错转 long（V53 前旧行无 cache）。
        boolean anthropic = ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, model);
        long used = ContextUsageCalculator.computeContextTokensUsed(
            asst.inputTokens() != null ? asst.inputTokens() : 0L,
            asst.inputCacheReadTokens() != null ? asst.inputCacheReadTokens().longValue() : null,
            asst.inputCacheCreationTokens() != null ? asst.inputCacheCreationTokens().longValue() : null,
            anthropic);
        long window = resolveContextWindowForModel(model);
        if (window <= 0) {
            return;
        }
        int percentLeft = Math.max(0, (int) Math.round((1 - (double) used / window) * 100));
        messages.set(lastAsstIdx, asst.withContextSnapshot(used, percentLeft, window));
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] 重拉上下文快照补算: session={} 末条 assistant={} model={} "
                    + "contextTokensUsed={} contextWindow={} percentLeft={} "
                    + "（input={} cacheRead={} cacheCreation={} anthropic={}，协议分派对齐实时）",
                sessionId, asst.id(), model, used, window, percentLeft,
                asst.inputTokens(), asst.inputCacheReadTokens(), asst.inputCacheCreationTokens(), anthropic);
        }
    }

    /**
     * [token-compact-fix ⑤方案B] 解析会话当前模型名 · 对齐 ChatService.resolveModelNameForSession
     * 四层链的会话层 + settings 层（请求体层在重拉路径不存在）：会话 override（sessions.model_name）
     * → settings.mainModelName → null（调用方跳过快照）。
     *
     * @param sessionId 会话 ID
     * @return 模型名（全名/裸名）；不可判定 → null
     */
    private String resolveSessionModel(String sessionId) {
        try {
            SessionRecord session = sessionMapper.selectOneById(sessionId);
            if (session != null && session.getModelName() != null && !session.getModelName().isBlank()) {
                return session.getModelName();
            }
            if (settingsMapper != null) {
                SettingsRecord s = settingsMapper.selectOneById(1);
                if (s != null && s.getMainModelName() != null && !s.getMainModelName().isBlank()) {
                    return s.getMainModelName();
                }
            }
        } catch (Exception e) {
            log.warn("[MessageService] 会话模型解析失败, 上下文快照跳过: sessionId={} err={}", sessionId, e.toString());
        }
        return null;
    }

    /**
     * [token-compact-fix ⑤方案B] 解析模型上下文窗口 · 对齐实时路径 ChatService:570-571
     * （ModelRecord.max_context_tokens，回落 1M）+ 参照 CompactThresholdSystem.getContextWindowForModel
     * 的 DB model 元数据窗口来源（models.max_context_tokens，AgentLoopContextFactory:293-294）。
     *
     * <p>经 {@link ModelNameResolver#resolve}（全名感知，models.name 精确匹配 enabled model）反查
     * ModelRecord 取 {@code max_context_tokens}；未命中/未配置/异常 → 回落 1_048_576（与实时 complete
     * 事件同值 ChatService:571，非 CompactConstants.CONTEXT_1M_WINDOW=1_000_000）。
     *
     * @param model 模型名（全名/裸名；null → 直接回落 1M）
     * @return 模型上下文窗口 token 数（> 0）
     */
    private long resolveContextWindowForModel(String model) {
        if (model == null || modelMapper == null || providerMapper == null) {
            return 1_048_576L;
        }
        try {
            ModelRecord modelRecord = ModelNameResolver.resolve(modelMapper, providerMapper, model);
            if (modelRecord != null && modelRecord.getMaxContextTokens() != null
                    && modelRecord.getMaxContextTokens() > 0) {
                return modelRecord.getMaxContextTokens();
            }
            if (log.isDebugEnabled()) {
                log.debug("[MessageService] 重拉上下文窗口: 模型 {} 无 max_context_tokens 或未命中，回落 1M",
                    model);
            }
        } catch (Exception e) {
            log.warn("[MessageService] 重拉上下文窗口解析失败, 回落 1M: model={} err={}", model, e.toString());
        }
        return 1_048_576L;
    }

    /**
     * 会话恢复专用读取 · 对齐 CC conversationRecovery.ts:167-255
     * {@code deserializeMessagesWithInterruptDetection}（S1 会话恢复补中断语义）。
     *
     * <p><b>WHY</b>：{@link #listBySession} 返回 DB 原始行（created_at ASC），无中断检测 /
     * tool_use 配对过滤 / "Continue" sentinel 注入 —— 中断 turn 恢复后"有问无答"。恢复/续聊
     * 加载历史的通道（ChatController background、PartialCompactService、AwaySummaryController）
     * 消费本方法，对 DB 消息流应用 CC 同款反序列化（未配对 tool_use 剥离 / 孤立 thinking 剥离 /
     * 纯空白 assistant 剥离 / detectTurnInterruption / "Continue" sentinel 注入）。
     *
     * <p><b>不破坏 {@link #listBySession}</b>：前端 GET /messages 原始展示仍走 listBySession
     * （本方法为恢复消费点专用漏斗，DB 权威写入不变）。
     *
     * @param sessionId 会话 ID
     * @return 反序列化（中断语义注入）后的消息列表
     */
    public List<ChatMessageDto> listForResume(String sessionId) {
        List<ChatMessageDto> raw = listBySession(sessionId);
        return SessionResumeDeserializer.deserializeWithInterruptDetection(raw).messages();
    }

    /**
     * 会话恢复专用读取（排除在途用户消息）· fix-loop-resume-history。
     *
     * <p><b>WHY</b>: loop 主路径 resume 时当前用户消息已被 {@code ChatController.createUserMessage}
     * 落 DB，{@link #listForResume} 会把它作为末条 → deserializer 误判 INTERRUPTED_PROMPT 并在其后
     * splice sentinel（破坏本轮回复）。本方法先排除 excludeMessageId 再应用中断语义漏斗，
     * 使 history 末尾即上一轮真实终止状态（对齐 CC conversationRecovery.ts:485-512
     * {@code loadConversationForResume} 全量历史注入）。
     *
     * <p>excludeMessageId == null/blank → 不排除（非流式测试路径回落，对齐
     * LlmAgentLoop :2428-2430 streamUserMessageId==null 回落「转录非空」）。
     *
     * <p><b>不破坏 {@link #listBySession}</b>：DB 权威读取不变（前端 GET /messages 仍走
     * listBySession 原始展示）；本方法为 loop 主路径恢复消费点专用漏斗。
     *
     * @param sessionId       会话 ID（DB 键 "sess-xxx"）
     * @param excludeMessageId 需排除的消息 id（当前 in-flight 用户消息；null=不排除）
     * @return 反序列化（中断语义注入）后的历史消息列表
     */
    public List<ChatMessageDto> listForResumeExcluding(String sessionId, String excludeMessageId) {
        return listForResumeExcluding(listBySession(sessionId), excludeMessageId);
    }

    /**
     * 会话恢复专用读取（排除在途用户消息）· 已取原始转录的内存派生重载。
     *
     * <p><b>WHY（低效非错误修复）</b>：LlmAgentLoop.doRun 注入块与续跑 skill 恢复块每 run 各自
     * {@link #listBySession} 全量读取同一会话消息 = 冗余 DB I/O。注入块改为复用主流程预取一次的
     * 原始转录（LlmAgentLoop.doRun:1917 前一次性读取缓存），经本重载在内存派生排除 + 中断语义
     * 漏斗产物，skill 恢复块直接消费缓存，消除重复查询。
     *
     * <p>语义与 {@link #listForResumeExcluding(String, String)} 完全一致：先排除 excludeMessageId
     * （null/blank = 不排除，回落「转录非空」全量语义），再应用
     * {@code SessionResumeDeserializer.deserializeWithInterruptDetection} 中断语义漏斗。
     *
     * @param raw              已从 DB 读取的原始消息列表（created_at ASC；null/空 → 恒返回空列表）
     * @param excludeMessageId 需排除的消息 id（当前 in-flight 用户消息；null=不排除）
     * @return 反序列化（中断语义注入）后的历史消息列表
     */
    public List<ChatMessageDto> listForResumeExcluding(List<ChatMessageDto> raw, String excludeMessageId) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (excludeMessageId == null || excludeMessageId.isBlank()) {
            return SessionResumeDeserializer.deserializeWithInterruptDetection(raw).messages();
        }
        List<ChatMessageDto> filtered = new ArrayList<>(raw.size());
        for (ChatMessageDto m : raw) {
            if (m != null && excludeMessageId.equals(m.id())) {
                continue;
            }
            filtered.add(m);
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] listForResumeExcluding(raw): raw={} filtered={}（排除在途用户消息 {}）",
                raw.size(), filtered.size(), excludeMessageId);
        }
        return SessionResumeDeserializer.deserializeWithInterruptDetection(filtered).messages();
    }

    public ChatMessageDto getById(String id) {
        MessageRecord m = messageMapper.selectOneById(id);
        if (m == null) throw new NotFoundException("Message " + id + " not found");
        return toDto(m);
    }

    /** [mid-turn 加固 2026-08-25] 消息是否存在（非抛）· 排队 user 补落库前检查已原位落库
     *  （replayAndPersist 单调保序），避免 3 参 now() 重复落库覆盖 created_at 顺序。 */
    public boolean existsById(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return messageMapper.selectOneById(id) != null;
    }

    /**
     * [queue-order-fix 方案A] 排队命令消费时落库 user 消息（指定 id = 队列 uuid）· 对齐 CC
     *   enqueue 内存队列 → 消费时 createUserMessage 落库（落库顺序 = 消费顺序，修复 busy 时
     *   user 消息提前落库插入到未落库 assistant 前的 DB 顺序错位）。
     *
     * <p>busy 时 ChatController 不调 {@link #createUserMessage}（预生成 id 入队），
     * CronIdleExecutor 消费 busy-queued 时经本方法落库（此时前一轮 assistant 已落库 → 顺序正确）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content) {
        return createQueuedUserMessage(sessionId, userMessageId, content, OffsetDateTime.now());
    }

    /**
     * 4 参重载：{@link #createQueuedUserMessage(String, String, String)} + 显式落库时间戳。
     *
     * <p><b>WHY（DB 单键排序并列修复）</b>：replayAndPersist 同一循环内 assistantA / queued-user /
     * assistantB 连续 insert 时，默认 {@code OffsetDateTime.now()} 毫秒精度下 created_at 可能并列 →
     * ORDER BY created_at 单键并列序不确定（ChatService.loadRecentHistory orderBy created_at），
     * queued-user 可能间歇排在 assistantB 之后。调用方（ChatService）传
     * {@code baseTs.plusNanos(seq)} 单调时间戳 → created_at 严格位于 assistantA 与 assistantB 之间。
     *
     * <p>isMeta 恒 false（排队命令为普通用户输入，非系统生成）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt     落库 created_at（供 replayAndPersist 单调序传入；null → OffsetDateTime.now()）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt) {
        return createQueuedUserMessage(sessionId, userMessageId, content, createdAt, false);
    }

    /**
     * 5 参重载：{@link #createQueuedUserMessage(String, String, String, OffsetDateTime)} + 显式 isMeta。
     *
     * <p><b>[cron-task-inject-align C1] isMeta 落库</b>：cron 触发的 user prompt 落库 isMeta=true
     * （CC original: isMeta，useScheduledTasks.ts:76 cron 入队 isMeta 语义 —— UI 隐藏但模型可见），
     * 供 CronIdleExecutor.executeQueuedInput 消费 cron（workload=WORKLOAD_CRON）时落库。busy-queued
     * 排队 prompt 恒 isMeta=false（普通用户输入）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId 预生成 id（队列 QueueItem.uuid；null/空 → generateId 兜底）
     * @param content       排队命令原文（QueueItem.value；null → 空串）
     * @param createdAt     落库 created_at（供 replayAndPersist 单调序传入；null → OffsetDateTime.now()）
     * @param isMeta        CC original: isMeta（messages.ts:3753 / useScheduledTasks.ts:76）—
     *                      true = 系统生成消息（UI 隐藏、模型可见，V51 is_meta 列落库）
     * @return MessageCreatedResponse（queued=false——已消费落库，正常轮）
     */
    public MessageCreatedResponse createQueuedUserMessage(String sessionId, String userMessageId, String content,
                                                          OffsetDateTime createdAt, boolean isMeta) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");
        if (userMessageId == null || userMessageId.isBlank()) {
            userMessageId = generateId("msg");
        }
        MessageRecord m = new MessageRecord();
        m.setId(userMessageId);
        // [userMessageId] 排队用户消息 = 排队命令 uuid（自身 id，CC parentUuid 链根）· V47 列落库
        m.setUserMessageId(userMessageId);
        m.setSessionId(sessionId);
        m.setRole(Role.user.name());
        m.setAuthor(null);
        m.setContent(content != null ? content : "");
        m.setReasoning(null);
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt((createdAt != null ? createdAt : OffsetDateTime.now()).toString());
        m.setCwd(CwdResolution.getCwd(sessionId));
        // 排队命令仅 content 入队（attachments 未入队，登记为限制）；imagePasteIds 恒 null
        m.setImagePasteIds(null);
        // [C1] isMeta 落库 · CC original: isMeta（messages.ts:3753 createUserMessage({..., isMeta:true}) /
        //   useScheduledTasks.ts:76 cron 入队 isMeta 语义）· V51 is_meta 列；cron=true / busy-queued=false
        m.setIsMeta(isMeta);
        messageMapper.insert(m);
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);
        // [streamTopic-session-level] 会话级单 topic（对齐 CC 会话单一事件流）；createQueuedUserMessage
        //   返回值仅由后端消费（原位落库忽略返回值），语义一致。
        String streamTopic = "/topic/sessions/" + sessionId + "/stream";
        return new MessageCreatedResponse(userMessageId, "msg-stub-pending", streamTopic, false);
    }

    public MessageCreatedResponse createUserMessage(String sessionId, SendMessageRequest req) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");

        // 1. 持久化用户消息
        MessageRecord m = new MessageRecord();
        m.setId(generateId("msg"));
        // [userMessageId] user 消息 = 自己的 id（CC parentUuid 链根，sessionStorage.ts:1066-1068
        //   isChainParticipant 后 parentUuid = message.uuid）· V47 列落库
        m.setUserMessageId(m.getId());
        m.setSessionId(sessionId);
        m.setRole(Role.user.name());
        m.setAuthor(null);
        m.setContent(req.content());
        m.setReasoning(null);
        // reasoningDurationMs：user 消息恒 null（后端测推理耗时仅 assistant 消息；对称风格）
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt(OffsetDateTime.now().toString());
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059 transcriptMessage.cwd = getCwd()。
        //   消息产生/落库时戳入当前工作目录，供 /resume 恢复目录上下文（:2522 projectPath=firstMessage.cwd）。
        m.setCwd(CwdResolution.getCwd(sessionId));
        // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523 createUserMessage
        //   签名）· 若 user 消息经 HTTP 发送图片附件（req.attachments() 中 type=image 或
        //   mediaType=image/* 项），取其 contentId（ImageAttachmentStore 数字 id 串）落库，
        //   供前端重拉缩略图 + TokenEstimator 图片 token 估算。无图片 → null。
        m.setImagePasteIds(serializeStringList(imagePasteIdsFromAttachments(req.attachments())));
        // [userAttachments] 附件快照（type+filename 全类型含图片）· user 消息落库时持久化，供前端 F5 重拉显示附件 chip
        m.setUserAttachments(serializeUserAttachments(userAttachmentsFromAttachments(req.attachments())));
        // [C1] 普通用户输入非系统生成 · CC original: isMeta=false（messages.ts:3753 createUserMessage
        //   默认非元消息）· V51 is_meta 列显式落 false
        m.setIsMeta(false);
        messageMapper.insert(m);

        // 2. 自增 sessions.messageCount
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);

        // 3. 会话级 streamTopic（对齐 CC 会话单一事件流）· 前端按事件 userMessageId/assistantMessageId
        //    路由归组，topic 不再编码消息 id；assistantMessageId 占位（跑完后才确定）。
        String assistantId = "msg-stub-pending";
        String streamTopic = "/topic/sessions/" + sessionId + "/stream";

        // [queue-first B6] queued=false 占位：busy 判定在 ChatController.send 前置（若 turn 运行中
        //   再发 → controller 以 queued=true 重建响应并走 enqueueBusyPrompt，不调 processUserMessage）。
        return new MessageCreatedResponse(m.getId(), assistantId, streamTopic, false);
    }

    /**
     * 落一条系统 subtype 消息 · CC original: {@code createScheduledTaskFireMessage}
     * （messages.ts:4385-4392，system + subtype='scheduled_task_fire' + isMeta=false）。
     *
     * <p><b>[cron-fire-visible] WHY</b>: CC onFireTask（useScheduledTasks.ts:110-113）在 cron 触发时
     * {@code setMessages([...prev, createScheduledTaskFireMessage('Running scheduled task (<time>)')])}
     * 落可见系统消息 → 前端 SystemTextMessage.tsx:137 按 subtype 渲染「任务执行中」。Java 侧经本方法
     * 落 DB 消息表（前端 GET /messages 读转录）——转录序 = [scheduled_task_fire, cron user, assistant]。
     *
     * <p><b>role=system 落库合规</b>: messages.role TEXT 含 'system'（V1__init_schema.sql:63）；
     * SessionResumeDeserializer 判末条 turn-relevant 时跳过 system（:353-357/441-442）→ 不破坏
     * 中断检测/resume。subtype 供前端判别渲染（CC SystemTextMessage.tsx:137）；DB toDto 读回 subtype
     * （MessageService:567）。isMeta 已 V51 落库；scheduled_task_fire isMeta=false 仍可见
     * （CC createScheduledTaskFireMessage messages.ts:4385-4392 isMeta=false，与 CC 一致）。
     *
     * @param sessionId 目标会话（short；session 行不存在 → NotFoundException，调用方 try/catch）
     * @param subtype   CC original: subtype（scheduled_task_fire / 其他系统消息）
     * @param content   系统消息正文（前端显示；null → 空串）
     * @return 落库后的消息 id
     */
    public String appendSystemSubtypeMessage(String sessionId, String subtype, String content) {
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " not found");
        MessageRecord m = new MessageRecord();
        m.setId(generateId("msg"));
        m.setSessionId(sessionId);
        m.setRole(Role.system.name());
        m.setAuthor(null);
        m.setContent(content != null ? content : "");
        m.setReasoning(null);
        m.setReasoningDurationMs(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt(OffsetDateTime.now().toString());
        m.setSubtype(subtype);
        // [C1] 系统 subtype 消息 isMeta=false · CC original: isMeta（messages.ts:4385-4392
        //   createScheduledTaskFireMessage isMeta=false，仍可见）· V51 is_meta 列显式落 false
        m.setIsMeta(false);
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059（与 createUserMessage 同款）
        m.setCwd(CwdResolution.getCwd(sessionId));
        messageMapper.insert(m);
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 系统 subtype 消息落库: session={} id={} subtype={} contentLen={}",
                sessionId, m.getId(), subtype, m.getContent().length());
        }
        return m.getId();
    }

    public void delete(String id) {
        MessageRecord m = messageMapper.selectOneById(id);
        if (m == null) throw new NotFoundException("Message " + id + " not found");
        // tool_calls 的 FK ON DELETE CASCADE 会连带清，这里显式删一次更稳
        toolCallMapper.deleteByQuery(QueryWrapper.create().eq("message_id", id));
        messageMapper.deleteById(id);
    }

    /**
     * 对话裁剪（删除 pivot 起全部后续消息）· 对齐 CC {@code rewindConversationTo}
     * （REPL.tsx:3661-3699）{@code setMessages(prev.slice(0, messageIndex))} ——
     * 保留 pivot 之前消息，丢弃 pivot（含）及其后。
     *
     * <p><b>WHY（gap28 前端缺口 §28）</b>: 前端「裁剪到某点」需删除 pivot 之后全部消息且
     * 被删消息<b>不进模型上下文</b>——模型上下文来自 DB transcript（LlmAgentLoop.run 经
     * listBySession 加载），故本方法 DB 删 + 重插保留段即等价 CC 前端 setMessages 裁剪。
     * conversationId 旋转由调用方（ChatController 裁剪端点）负责，对齐 CC REPL.tsx:3673
     * {@code setConversationId(randomUUID())}。
     *
     * <p><b>实现</b>: 复用 {@link #replaceSessionMessages}「删全部 + 重插」路径
     * （toolCallMapper/messageMapper deleteByQuery + 保序重插 created_at=base.plusNanos(i)），
     * 最小新增、无新 SQL；tool_calls 随消息级联清除（replace 路径已显式删 toolCall）。
     *
     * @param sessionId      会话 ID（DB 键，如 "sess-xxx"）
     * @param pivotMessageId 裁剪 pivot 消息 ID（该消息本身及其后全部删除）
     * @return 裁剪后剩余消息列表（空列表 = pivot 是首条 / 全删，前端 setMessages 权威回填）
     * @throws NotFoundException session 不存在 / pivot 消息不存在（对齐 delete :115 语义）
     */
    @Transactional
    public List<ChatMessageDto> trimSessionAfter(String sessionId, String pivotMessageId) {
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        // pivot 定位必须在「当前会话消息列表」内匹配（created_at ASC 序），而非 getById——
        // 避免跨会话消息 id 误匹配（对齐 listBySession :59-71 同源读取）。
        List<ChatMessageDto> all = listBySession(sessionId);
        int pivotIndex = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id() != null && all.get(i).id().equals(pivotMessageId)) {
                pivotIndex = i;
                break;
            }
        }
        if (pivotIndex < 0) {
            throw new NotFoundException("Message " + pivotMessageId + " not found");
        }
        // keep = [0, pivotIndex)（含 pivot 丢弃，对齐 CC slice(0, messageIndex) REPL.tsx:3671）
        List<ChatMessageDto> keep = new ArrayList<>(all.subList(0, pivotIndex));
        if (log.isInfoEnabled()) {
            log.info("[MessageService] trimSessionAfter: session={} pivot={} 裁剪 {}→{} 条消息"
                    + "（含 pivot 及其后丢弃，对齐 CC REPL.tsx:3671 slice(0, messageIndex)）",
                sessionId, pivotMessageId, all.size(), keep.size());
        }
        return replaceSessionMessages(sessionId, keep);
    }

    /**
     * 追加一条出站消息到会话消息库 · W8-04 完成通知链（OPD-TP-07）。
     *
     * <p><b>WHY</b>: teammate 终端转换（completed/failed/killed）产出 task_status attachment
     * （{@code TeammateMessageFoldingChain.teammateTaskStatusAttachment}，author='attachment' +
     * subtype='task_status'），必须落消息表 —— 否则 GET /messages 折叠链（ChatController 出站
     * 组装点）无 in_process_teammate 输入，连续 teammate shutdown 洪泛无法折叠。CC 侧该附件进
     * leader transcript（内存），Java 侧落 DB 消息表等价（前端 GET /messages 读折叠链）。
     *
     * <p>映射复用 {@link #replaceSessionMessages} 的 DTO→Record 规则（author/subtype/content 等
     * 字段），单条插入；sessionId 取自 dto.sessionId()（父会话 = parentSessionId）。
     *
     * @param dto 出站附件消息（如 task_status attachment）
     * @return 落库后的消息（id 已生成）
     */
    public ChatMessageDto appendMessage(ChatMessageDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("appendMessage: dto 不能为 null");
        }
        String id = dto.id() != null ? dto.id() : generateId("msg");
        MessageRecord rec = new MessageRecord();
        rec.setId(id);
        rec.setSessionId(dto.sessionId());
        // [Fix C] messages.role TEXT NOT NULL（V1__init_schema.sql:63，无 DEFAULT）+ JDBC foreign_keys=on
        //   → role-less 消息（CC AttachmentMessage 无 role，attachments.ts:3201-3207；出站 DTO 保持
        //   role=null 与 CC 一致）落库必违反 NOT NULL → INSERT 抛异常（teammate 终端 task_status
        //   attachment 落库失败，20:33 实证）。DB 持久化边界把 null role 适配为 Role.system
        //   （系统生成元数据）；折叠链/前端判据是 author/subtype，不依赖 role。
        rec.setRole(dto.role() != null ? dto.role().name() : Role.system.name());
        rec.setAuthor(dto.author());
        rec.setContent(dto.content() == null ? "" : dto.content());
        rec.setReasoning(dto.reasoning());
        // [reasoningDurationMs] 后端测推理耗时 · V41 列落库（净新增字段，非 CC 对齐）
        rec.setReasoningDurationMs(dto.reasoningDurationMs());
        rec.setFinishReason(dto.finishReason() != null ? dto.finishReason().name() : null);
        rec.setInputTokens(dto.inputTokens());
        rec.setOutputTokens(dto.outputTokens());
        // [token-compact-fix B1 方案A] cache 用量落库 · V53 列；从 AgentUsage 投影
        //   （dto.usage() 真源 → DTO cache 字段回退），使 GET/messages 重算 contextTokensUsed
        //   含 cache（与实时 complete 事件一致，不再少算）。
        rec.setCacheReadInputTokens(cacheReadInputTokensOf(dto));
        rec.setCacheCreationInputTokens(cacheCreationInputTokensOf(dto));
        rec.setCreatedAt(OffsetDateTime.now().toString());
        rec.setToolCallId(dto.toolCallId());
        rec.setSubtype(dto.subtype());
        rec.setStructuredOutput(serializeStructuredOutput(dto.structuredOutput()));
        rec.setAssistantMessageId(dto.assistantMessageId());
        // [userMessageId] 出站附件消息跟随 dto.userMessageId()（CC parentUuid 链根）· V47 列落库；
        //   dto 无归属（null）= 落 NULL（system/teammate 终端附件不在用户轮内，容错）
        rec.setUserMessageId(dto.userMessageId());
        // IMP2-14 · boundary 元数据持久化（V13 列；round-trip 闭环，CC messages.ts:4540-4574/4551-4553）
        rec.setCompactMetadata(serializeMap(dto.compactMetadata()));
        rec.setMicrocompactMetadata(serializeMap(dto.microcompactMetadata()));
        // [snip-persist-field] snip_boundary 元数据持久化（V62 列；round-trip 闭环，CC
        //   snipCompact.ts:99-106 / snipProjection.ts:31 —— removedUuids 落库供前端「已裁剪」标注）
        rec.setSnipMetadata(serializeMap(dto.snipMetadata()));
        rec.setLogicalParentUuid(dto.logicalParentUuid());
        // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523）。
        //   非空 → JSON 数组字符串落库（round-trip 闭环）；null/空 → null（V46 列）。
        rec.setImagePasteIds(serializeStringList(dto.imagePasteIds()));
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059。优先保留消息产生时戳入的 dto.cwd()
        //   （消息产生时工作目录，更精确）；未戳则落库时经 CwdResolution.getCwd 补戳。
        rec.setCwd(dto.cwd() != null ? dto.cwd() : CwdResolution.getCwd(dto.sessionId()));
        // [C1] isMeta round-trip 持久化 · CC original: isMeta（messages.ts:3753 createUserMessage）·
        //   V51 is_meta 列；出站透传 dto.isMeta()（:454），落库同源保证 round-trip 闭环
        rec.setIsMeta(dto.isMeta());
        messageMapper.insert(rec);
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] appendMessage: session={} id={} author={} subtype={} 已落库",
                dto.sessionId(), id, dto.author(), dto.subtype());
        }
        return new ChatMessageDto(
            id, dto.sessionId(), dto.role(), dto.author(), dto.content(), dto.reasoning(),
            dto.toolCalls(), dto.finishReason(), dto.inputTokens(), dto.outputTokens(),
            dto.time(), dto.createdAt(), dto.toolCallId(), dto.assistantMessageId(),
            dto.acceptFeedback(), dto.contentBlocks(), dto.imagePasteIds(),
            dto.structuredOutput(), dto.isMeta(), dto.isError(), dto.sourceToolUseID(),
            dto.subtype()).withCwd(rec.getCwd())
            .withReasoningDurationMs(dto.reasoningDurationMs()) // [reasoningDurationMs] 写后回传保留字段
            .withUserMessageId(dto.userMessageId()); // [userMessageId] 写后回传保留字段（与 rec 落库同源）
    }

    /**
     * 全量替换会话消息 · 对齐 CC REPL.tsx:4964 {@code setMessages(postCompact)}
     * （partial 压缩后消息列表全量替换语义）· CC original: 前端内存替换，Java 侧落 DB。
     *
     * <p><b>WHY（OD-14 D-1 写回）</b>: partial 压缩后新消息列表（boundary + summary +
     * kept + attachments + hooks）须写回消息表，供下次 partial 重复剥离（BoundaryReader
     * 判 boundary）与前端 setMessages 刷新。CC 无 DB 概念，本方法为 Java 持久化载体：
     * <ol>
     *   <li>删该 session 全部消息（FK CASCADE 连带清 tool_calls，显式删更稳）</li>
     *   <li>按数组序重插（保序：created_at 单调递增，listBySession:50 按 created_at ASC）</li>
     *   <li>归一化 sessionId=sessionId（boundary.toChatMessageDto() 的 sessionId=null，
     *       CompactBoundaryMessage.java:286-312）</li>
     *   <li>id 去重（boundary 固定 id 'compact-boundary-compact_boundary'：from 方向
     *       messagesToKeep 保留旧 boundary + 新 boundary → PK 冲突，重插时去重）</li>
     *   <li>kept 消息的 toolCalls 重插 ToolCallRecord（保留原 ID，镜像
     *       ChatService.replayAndPersist:339-357 模式）</li>
     * </ol>
     *
     * <p><b>原子性</b>: 删+重插整体 @Transactional —— 任一重插失败回滚全部，避免半写。
     *
     * @param sessionId   会话 ID
     * @param newMessages 压缩后新消息列表（数组序即最终顺序）
     * @return 归一化后的消息列表（id 去重 + sessionId 落定），供响应回传前端
     */
    @Transactional
    public List<ChatMessageDto> replaceSessionMessages(String sessionId, List<ChatMessageDto> newMessages) {
        // 校验 session 存在
        if (sessionMapper.selectOneById(sessionId) == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        if (log.isInfoEnabled()) {
            log.info("[MessageService] replaceSessionMessages: session={} 全量替换 {} 条消息（partial 压缩写回）",
                sessionId, newMessages == null ? 0 : newMessages.size());
        }
        // 1. 删旧消息（FK CASCADE 连带清 tool_calls；显式删更稳）。
        //    ⚠️ tool_calls 表无 session_id 列（V1 表结构：id/message_id/tool_name/arguments/result/
        //    is_error/created_at）——按 message_id IN (该 session 的 messages.id) 子查询显式删，
        //    不能 eq("session_id")（SQLITE_ERROR no such column: session_id，裁剪消息触发）。
        toolCallMapper.deleteByQuery(QueryWrapper.create()
            .where("message_id IN (SELECT id FROM messages WHERE session_id = ?)", sessionId));
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", sessionId));

        // 2. 重插（保序 + id 去重 + sessionId 归一化）
        List<ChatMessageDto> normalized = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        OffsetDateTime base = OffsetDateTime.now();
        List<ChatMessageDto> sources = newMessages == null ? List.of() : newMessages;
        for (int i = 0; i < sources.size(); i++) {
            ChatMessageDto dto = sources.get(i);
            // id 去重（boundary 固定 id 在 from 方向可能重复 → PK 冲突）
            String id = dto.id();
            if (id == null || !seenIds.add(id)) {
                id = generateId("msg");
            }
            MessageRecord rec = new MessageRecord();
            rec.setId(id);
            rec.setSessionId(sessionId);
            // [Fix C] messages.role TEXT NOT NULL（V1__init_schema.sql:63，无 DEFAULT）+ JDBC foreign_keys=on
            //   → role-less 消息（CC AttachmentMessage 无 role，attachments.ts:3201-3207；出站 DTO 保持
            //   role=null 与 CC 一致）落库必违反 NOT NULL → INSERT 抛异常（teammate 终端 task_status
            //   attachment 落库失败，20:33 实证）。DB 持久化边界把 null role 适配为 Role.system
            //   （系统生成元数据）；折叠链/前端判据是 author/subtype，不依赖 role。
            rec.setRole(dto.role() != null ? dto.role().name() : Role.system.name());
            rec.setAuthor(dto.author());
            rec.setContent(dto.content() == null ? "" : dto.content());
            rec.setReasoning(dto.reasoning());
            // [reasoningDurationMs] 后端测推理耗时 · V41 列落库（净新增字段，非 CC 对齐）
            rec.setReasoningDurationMs(dto.reasoningDurationMs());
            rec.setFinishReason(dto.finishReason() != null ? dto.finishReason().name() : null);
            rec.setInputTokens(dto.inputTokens());
            rec.setOutputTokens(dto.outputTokens());
            // [token-compact-fix B1 方案A] cache 用量落库 · V53 列；partial 压缩写回路径同样
            //   持久化（appendMessage 已持久化，本路径漏掉则 round-trip 破缺——kept 消息经
            //   replaceSessionMessages 重插后 cache 丢失，重算少算）。
            rec.setCacheReadInputTokens(cacheReadInputTokensOf(dto));
            rec.setCacheCreationInputTokens(cacheCreationInputTokensOf(dto));
            // created_at 单调递增（DB 按 created_at ASC 排序 → 重插保序）
            rec.setCreatedAt(base.plusNanos(i).toString());
            rec.setToolCallId(dto.toolCallId());
            rec.setSubtype(dto.subtype());
            rec.setStructuredOutput(serializeStructuredOutput(dto.structuredOutput()));
            // A1: assistantMessageId 持久化 · CC sourceToolAssistantUUID（utils/messages.ts:491）
            rec.setAssistantMessageId(dto.assistantMessageId());
            // [userMessageId] partial 压缩写回路径同样持久化——否则 kept 消息经 replaceSessionMessages
            //   重插后归属丢失（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）· V47 列落库
            rec.setUserMessageId(dto.userMessageId());
            // IMP2-14 · boundary 元数据持久化（V13 列；round-trip 闭环，CC messages.ts:4540-4574/4551-4553）
            rec.setCompactMetadata(serializeMap(dto.compactMetadata()));
            rec.setMicrocompactMetadata(serializeMap(dto.microcompactMetadata()));
            // [snip-persist-field] snip_boundary 元数据持久化（V62 列；round-trip 闭环，CC
            //   snipCompact.ts:99-106 / snipProjection.ts:31 —— removedUuids 落库供前端「已裁剪」标注）
            rec.setSnipMetadata(serializeMap(dto.snipMetadata()));
            rec.setLogicalParentUuid(dto.logicalParentUuid());
            // [V46] imagePasteIds · CC original: imagePasteIds（messages.ts:460-523）。
            //   partial 压缩写回路径同样持久化——否则 kept 消息经 replaceSessionMessages 重插后
            //   图片粘贴序号丢失（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）。
            rec.setImagePasteIds(serializeStringList(dto.imagePasteIds()));
            // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059。优先保留 dto.cwd()（消息产生时戳），
            //   未戳则落库时补戳（partial 压缩写回路径：boundary/summary 等消息可能未戳）。
            rec.setCwd(dto.cwd() != null ? dto.cwd() : CwdResolution.getCwd(sessionId));
            // [C1] isMeta round-trip 持久化 · CC original: isMeta（messages.ts:3753）· V51 is_meta 列；
            //   partial 压缩写回路径补持久化（appendMessage 已持久化，本路径漏掉则 round-trip 破缺）
            rec.setIsMeta(dto.isMeta());
            messageMapper.insert(rec);

            // 3. 重插 tool_calls（保留原 ID，镜像 ChatService.replayAndPersist:339-357）
            if (dto.toolCalls() != null) {
                for (ToolCallDto tc : dto.toolCalls()) {
                    ToolCallRecord tcRec = new ToolCallRecord();
                    tcRec.setId(tc.id() != null ? tc.id() : generateId("tc"));
                    tcRec.setMessageId(id);
                    tcRec.setToolName(tc.name());
                    tcRec.setArguments(tc.arguments());
                    tcRec.setResult(tc.result());
                    tcRec.setIsError(tc.isError());
                    tcRec.setCreatedAt(rec.getCreatedAt());
                    toolCallMapper.insert(tcRec);
                }
            }

            // 归一化 DTO（id 去重后 + sessionId 落定）→ 响应回传
            normalized.add(new ChatMessageDto(
                id, sessionId, dto.role(), dto.author(), dto.content(), dto.reasoning(),
                dto.toolCalls(), dto.finishReason(), dto.inputTokens(), dto.outputTokens(),
                dto.time(), dto.createdAt(), dto.toolCallId(), dto.assistantMessageId(),
                dto.acceptFeedback(), dto.contentBlocks(), dto.imagePasteIds(),
                dto.structuredOutput(), dto.isMeta(), dto.isError(), dto.sourceToolUseID(),
                dto.subtype()).withCwd(rec.getCwd())
                .withReasoningDurationMs(dto.reasoningDurationMs()) // [reasoningDurationMs] 写后回传保留字段
                .withUserMessageId(dto.userMessageId())); // [userMessageId] 写后回传保留字段（与 rec 落库同源）
        }
        if (log.isDebugEnabled()) {
            log.debug("[MessageService] replaceSessionMessages: 完成重插 {} 条（保序 + id 去重 {} 条）",
                normalized.size(), sources.size() - normalized.size());
        }
        return normalized;
    }

    // ============== helpers ==============

    /**
     * [token-compact-fix B1 方案A] 从 DTO 提取 cache_read_input_tokens 供落库 · V53 列。
     *
     * <p><b>真源</b>：优先 {@code dto.usage().cacheReadInputTokens()}（AgentUsage 为 provider
     * 解析的完整 usage，DEC-04 R2-USAGE 数据源闭环真源），回退 DTO 投影字段
     * {@code dto.inputCacheReadTokens()}（LlmAgentLoop withUsageCache 从 usage 投影，
     * 测试/旧构造消息可能仅该字段有值）。两者同源（withUsageCache 即 Math.toIntExact 投影
     * usage.cacheReadInputTokens），双轨冗余仅为防御性容错。null = 无 cache 数据 → 落 NULL
     * （重算回退 0，与实时 usage null 省略语义等价）。
     */
    private static Integer cacheReadInputTokensOf(ChatMessageDto dto) {
        if (dto.usage() != null && dto.usage().cacheReadInputTokens() != null) {
            return Math.toIntExact(dto.usage().cacheReadInputTokens());
        }
        return dto.inputCacheReadTokens();
    }

    /**
     * [token-compact-fix B1 方案A] 从 DTO 提取 cache_creation_input_tokens 供落库 · V53 列。
     * 语义同 {@link #cacheReadInputTokensOf}（优先 AgentUsage 真源 → DTO 投影回退）。
     */
    private static Integer cacheCreationInputTokensOf(ChatMessageDto dto) {
        if (dto.usage() != null && dto.usage().cacheCreationInputTokens() != null) {
            return Math.toIntExact(dto.usage().cacheCreationInputTokens());
        }
        return dto.inputCacheCreationTokens();
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * MessageRecord → 出站 ChatMessageDto（transcript 读回）。
     *
     * <p><b>[P-28] isApiErrorMessage 恒 false</b>：DB schema 未持久化该标志（与
     * isMeta/isError/acceptFeedback/imagePasteIds 同先例），读回恒 false —— CC original
     * messages.ts:453 的运行期消费面（max_tokens 恢复触发 / StopFailure 门控）在 LlmAgentLoop
     * 内存消息流内生效（ER-IMP-11 接线），transcript 重放侧无该语义（P-28 接受，JavaDoc 标注）。
     */
    private ChatMessageDto toDto(MessageRecord m) {
        // 查 tool_calls（若无，返回空 list）
        List<ToolCallRecord> toolEntities = toolCallMapper.selectListByQuery(
            QueryWrapper.create().eq("message_id", m.getId()));
        List<ToolCallDto> toolDtos = new ArrayList<>(toolEntities.size());
        for (ToolCallRecord tc : toolEntities) {
            toolDtos.add(new ToolCallDto(
                tc.getId(),
                tc.getToolName(),
                tc.getArguments(),
                tc.getResult(),
                tc.getIsError()
            ));
        }

        ChatMessageDto dto = new ChatMessageDto(
            m.getId(),
            m.getSessionId(),
            m.getRole() != null ? Role.valueOf(m.getRole()) : null,
            m.getAuthor(),
            m.getContent(),
            m.getReasoning(),
            toolDtos,
            m.getFinishReason() != null ? FinishReason.valueOf(m.getFinishReason()) : null,
            m.getInputTokens(),
            m.getOutputTokens(),
            formatRelativeTimeAgo(parseDateTime(m.getCreatedAt()), OffsetDateTime.now()),
            parseDateTime(m.getCreatedAt()),
            m.getToolCallId(),   // Phase 6·s02 字段
            m.getAssistantMessageId(), // A1 assistantMessageId · CC original: sourceToolAssistantUUID (utils/messages.ts:491)
            null,                // R32-b9 acceptFeedback (DB schema 未持久化)
            java.util.List.of(), // R32-b9 contentBlocks
            parseStringList(m.getImagePasteIds()), // V46 imagePasteIds（V46 列 JSON 数组读回；null 列 → 空列表）
            parseStructuredOutput(m.getStructuredOutput()), // OD-14: V6 读回
            Boolean.TRUE.equals(m.getIsMeta()), // R32-c-1 isMeta（V51 is_meta 列读回；null→false 容错）
            false,               // H13-GAP isError（DB schema 未持久化）
            null,                // P2-22 sourceToolUseID（DB schema 未持久化）
            m.getSubtype(),      // IMP-05/OD-14: V6 读回 subtype（读侧 BoundaryReader 判别）
            false,               // ER-IMP-11 isApiErrorMessage（DB schema 未持久化）
            null, null, null,    // ER-IMP-11 apiError/error/errorDetails（DB schema 未持久化）
            parseMap(m.getCompactMetadata()),       // IMP2-14: V13 读回 boundary compactMetadata
            parseMap(m.getMicrocompactMetadata()),  // IMP2-14: V13 读回 microcompactMetadata
            m.getLogicalParentUuid(),               // IMP2-14: V13 读回 logicalParentUuid
            false,               // IMP2-14 isCompactSummary（DB schema 未持久化）
            false)               // IMP2-14 isVisibleInTranscriptOnly（DB schema 未持久化）
            .withSnipMetadata(parseMap(m.getSnipMetadata())) // [snip-persist-field] V62 读回 snipMetadata（removedUuids 供前端「已裁剪」标注）
            .withCwd(m.getCwd()) // [G13] 回填 cwd 戳（V22 列；旧行 NULL = 未戳容错，对齐 CC 旧 jsonl 无 cwd）
            .withReasoningDurationMs(m.getReasoningDurationMs()) // [reasoningDurationMs] 读侧回填（V41 列；GET /messages 出站唯一点）
            .withUserMessageId(m.getUserMessageId()) // [userMessageId] 读侧回填（V47 列；GET /messages 出站唯一点；旧行 NULL = 无归属容错）
            .withUserAttachments(resolveAttachmentUrls(parseUserAttachments(m.getUserAttachments()), m.getSessionId())); // [userAttachments] 读侧回填（V62 列；GET /messages 出站唯一点；contentId 非空 → url 动态拼 /api/v1/attachments/content/{sessionId}/{contentId}；null 列 → 空列表，恒非 null）
        // [usage 读侧回填] DB 持久化 inputTokens/outputTokens + cache 4 字段 → 重拉投影回填 usage，
        //   使 F5 后消息 token 展示与实时 complete 事件一致（↑input ↓output + cache）。
        //   仅 assistant 消息（tokens 非 null）回填；user/tool 消息不回填（避免 ↑0 ↓0 假值）。
        //   [token-compact-fix B1 方案A] V53 cache 列读回：withUsage 后链式 withUsageCache
        //   回填 inputCacheReadTokens/inputCacheCreationTokens（withUsage 会把 DTO cache 字段
        //   重置为 null，必须先 withUsage 再 withUsageCache，对齐 ChatMessageDto:613-616 顺序约束）。
        if (m.getInputTokens() != null || m.getOutputTokens() != null) {
            dto = dto.withUsage(AgentUsage.fromInputOutput(m.getInputTokens(), m.getOutputTokens()))
                .withUsageCache(m.getCacheReadInputTokens(), m.getCacheCreationInputTokens());
        }
        return dto;
    }

    /** Map 序列化（null → null，写回 DB；复用 structured_output 同款 JSON 通道）。 */
    private static String serializeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[MessageService] 元数据 Map 序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** Map 反序列化（JSON 文本 → Map；null/空/解析失败 → null）。 */
    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 元数据 Map 反序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** structured_output 序列化（null → null，写回 DB）。 */
    private static String serializeStructuredOutput(Map<String, Object> structuredOutput) {
        if (structuredOutput == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(structuredOutput);
        } catch (Exception e) {
            log.warn("[MessageService] structured_output 序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /** structured_output 反序列化（JSON 文本 → Map；null/空/解析失败 → null，fail loud 不吞关键异常）。 */
    private static Map<String, Object> parseStructuredOutput(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] structured_output 反序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * [AM-CC-20260825] 回写 user 消息 image_paste_ids（小图 base64 直传场景）：A4 生成的 user 消息
     * 带正确 imagePasteIds（LlmAgentLoop F1 落盘自增分配 id），但 createUserMessage 落库时拿不到
     * （当时 base64 直传无 contentId）→ 这里按 userMessageId UPDATE 补写 image_paste_ids 列。
     * 消息本体由 createUserMessage 落库，本方法只更新 image_paste_ids（MyBatis-Flex update 仅非 null 字段）。
     *
     * @param userMessageId DB user 消息 id（前端正式气泡 id 与 DB 一致）
     * @param imagePasteIds 图片粘贴序号（A4 生成的 imagePasteIds，可能含自增分配的小图 id）
     */
    public void updateUserImagePasteIds(String userMessageId, List<String> imagePasteIds) {
        if (userMessageId == null || userMessageId.isBlank() || imagePasteIds == null || imagePasteIds.isEmpty()) {
            return;
        }
        MessageRecord rec = new MessageRecord();
        rec.setId(userMessageId);
        rec.setImagePasteIds(serializeStringList(imagePasteIds));
        messageMapper.update(rec);  // id 作条件 + 只更新非 null（image_paste_ids）
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 回写 user 消息 image_paste_ids：id={} ids={}", userMessageId, imagePasteIds);
        }
    }

    /**
     * [附件双模式] 回写 user 消息 user_attachments（path/upload 附件 contentId 回补）：createUserMessage
     * 落库时 path 附件 contentId 未知（resolveAttachments 注册附件表后才拿到）→ 这里按 userMessageId
     * UPDATE 补写含 contentId 的完整附件快照（url 为出站投影，toDto 动态拼，不落库）。
     * 消息本体由 createUserMessage 落库，本方法只更新 user_attachments（MyBatis-Flex update 仅非 null 字段）。
     *
     * <p><b>全量覆盖语义</b>：serializeUserAttachments 对 null/空列表返回 null → update 跳过该列（不清空）；
     * 入参应传该 user 消息的<b>全量</b>附件快照（含既有 contentId 项），非增量。与
     * {@link #updateUserImagePasteIds}（列级追加）不同：user_attachments 是整列 JSON 快照，只能整列覆盖。
     *
     * @param userMessageId   DB user 消息 id（前端正式气泡 id 与 DB 一致）
     * @param userAttachments 附件快照全量列表（contentId 已回补）；null/空 = 不更新
     */
    public void updateUserAttachments(String userMessageId, List<UserAttachmentInfo> userAttachments) {
        if (userMessageId == null || userMessageId.isBlank() || userAttachments == null || userAttachments.isEmpty()) {
            return;
        }
        MessageRecord rec = new MessageRecord();
        rec.setId(userMessageId);
        rec.setUserAttachments(serializeUserAttachments(userAttachments));
        messageMapper.update(rec);  // id 作条件 + 只更新非 null（user_attachments）
        if (log.isInfoEnabled()) {
            log.info("[MessageService] 回写 user 消息 user_attachments：id={} count={}",
                userMessageId, userAttachments.size());
        }
    }

    /**
     * 字符串列表序列化（V46 imagePasteIds JSON 数组通道）· 参照 {@link #serializeMap} 同款 JSON 模式。
     *
     * <p>null/空列表 → null（DB 落 NULL，不存空数组 "[]"）；非空 → JSON 数组文本。
     * 序列化失败 → warn + null（fail loud，降级不落脏数据）。
     *
     * @param values 字符串列表（可 null/空）
     * @return JSON 数组文本；null/空/序列化失败 → null
     */
    private static String serializeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("[MessageService] 字符串列表序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * 字符串列表反序列化（V46 imagePasteIds JSON 数组读回）· 参照 {@link #parseMap} 同款 JSON 模式。
     *
     * <p>null/空列 → 空列表（toDto 消费侧契约：imagePasteIds 恒非 null，旧行 V46 列 NULL 兜底）；
     * 解析失败 → 空列表（fail loud warn，不抛）。
     *
     * @param json JSON 数组文本（可 null/空白）
     * @return 反序列化列表；null/空白/解析失败 → 空列表
     */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 字符串列表反序列化失败（降级空列表）: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 从 HTTP 请求附件提取图片粘贴序号 · CC original: imagePasteIds
     * （messages.ts:460-523 createUserMessage 签名）。
     *
     * <p>判定图片沿用 {@code MediaLimitGuard.isImage} 同款规则（type=image 或 mediaType=image/*）；
     * 取 image 项 contentId（ImageAttachmentStore 数字 id 串）入列 —— CC getNextImagePasteId
     * 记录 imageStore 图片 id 序列。无图片/无 contentId → null。
     *
     * @param attachments 请求体 attachments（可 null/空）
     * @return 图片粘贴序号列表；无图片 → null
     */
    private static List<String> imagePasteIdsFromAttachments(List<AttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (AttachmentRequest att : attachments) {
            if (att == null) {
                continue;
            }
            boolean isImage = (att.type() != null && "image".equalsIgnoreCase(att.type()))
                || (att.mediaType() != null && att.mediaType().startsWith("image/"));
            if (isImage && att.contentId() != null && !att.contentId().isBlank()) {
                ids.add(att.contentId());
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * 附件快照列表序列化（[{type,filename}] JSON 数组）· 参照 {@link #serializeStringList} 同款 JSON 模式。
     *
     * <p>null/空列表 → null（DB 落 NULL）；非空 → JSON 数组文本。序列化失败 → warn + null
     * （fail loud，降级不落脏数据）。
     *
     * @param list 附件快照列表（可 null/空）
     * @return JSON 数组文本；null/空/序列化失败 → null
     */
    private static String serializeUserAttachments(List<UserAttachmentInfo> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("[MessageService] 附件快照序列化失败（降级 null）: {}", e.toString());
            return null;
        }
    }

    /**
     * 附件快照 JSON 反序列化（[{type,filename,mediaType,contentId,url}] → List<UserAttachmentInfo>）· 参照
     * {@link #parseStringList} 同款 JSON 模式。
     *
     * <p>record canonical 5 参缺省 null 容错：历史 2 字段 JSON（{type,filename}）读回 mediaType/contentId/url
     * 均为 null；新字段（mediaType/contentId）自动反序列化（url DB 恒缺，出站由
     * {@link #resolveAttachmentUrls} 动态拼）。
     *
     * <p>null/空列 → 空列表（toDto 消费侧契约：userAttachments 恒非 null，旧行 V62 列 NULL 兜底）；
     * 解析失败 → 空列表（fail loud warn，不抛）。
     *
     * @param json 附件快照 JSON 数组文本（可 null/空白）
     * @return 反序列化列表；null/空白/解析失败 → 空列表
     */
    private static List<UserAttachmentInfo> parseUserAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<UserAttachmentInfo>>() { });
        } catch (Exception e) {
            log.warn("[MessageService] 附件快照反序列化失败（降级空列表）: {}", e.toString());
            return List.of();
        }
    }

    /**
     * [附件双模式] 出站附件 url 动态拼接（user_attachments 持久化 contentId → F5 预览 url）· 对齐前端契约
     * {@code /api/v1/attachments/content/{sessionId}/{contentId}}（AttachmentController GET /attachments/content，
     * 附件表 path 流式字节 + Range 206）。
     *
     * <p><b>出站纯投影</b>：url 由 contentId + sessionId 每次动态重算（DB user_attachments 不落 url，
     * 避免重启后 url 与 contentId 漂移）；contentId 非空 → 拼 url；contentId null/空（path 附件未回补 /
     * 历史旧行 / ≤5MB base64 图无 contentId）→ url null（前端无预览分支，附件 chip 降级纯文本）。
     * 幂等：url 已存在（异常反序列化带 url）以重算为准，不保留 DB 中的陈旧 url。
     *
     * @param list      反序列化后的附件快照（可 null/空）
     * @param sessionId 所属会话 id（消息表 session_id 列，toDto 的 m.getSessionId() 可拿；null → 不拼 url）
     * @return url 已填充的附件快照列表；无变更/无 session → 原列表
     */
    private static List<UserAttachmentInfo> resolveAttachmentUrls(List<UserAttachmentInfo> list, String sessionId) {
        if (list == null || list.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return list;
        }
        List<UserAttachmentInfo> resolved = new ArrayList<>(list.size());
        boolean changed = false;
        for (UserAttachmentInfo info : list) {
            if (info == null) {
                continue;
            }
            String cid = info.contentId();
            String url = (cid != null && !cid.isBlank())
                ? "/api/v1/attachments/content/" + sessionId + "/" + cid
                : null;
            if (url == null && info.url() == null) {
                resolved.add(info); // 无 contentId → 无 url 可拼，保留原 record
                continue;
            }
            resolved.add(new UserAttachmentInfo(info.type(), info.filename(), info.mediaType(), cid, url));
            changed = true;
        }
        return changed ? resolved : list;
    }

    /**
     * 从 HTTP 请求附件提取全部附件（含图片，不 filter）的 type+filename+mediaType+contentId 快照 ·
     * 前端 F5 重拉附件 chip + 预览 url（contentId → toDto 出站拼 url）。
     *
     * <p>与 {@link #imagePasteIdsFromAttachments} 的差异：该方法是图片专用（contentId 入列，供
     * 缩略图重拉 + token 估算），本方法覆盖全类型（file/image/video/audio），null 附件跳过。
     *
     * <p>[附件双模式] contentId 规则：req 附件项已带 contentId（upload / image-cache 引用）→ 直接入快照；
     * path 附件（local-read 本地读盘，path() 非空）contentId 此刻未知 → null（resolveAttachments 注册
     * 附件表后经 {@link #updateUserAttachments} 回补）；≤5MB base64 图无 contentId → null（imagePasteIds
     * 链路不变）。url 恒 null（toDto 出站动态拼，不落库）。无附件 → null。
     *
     * @param attachments 请求体 attachments（可 null/空）
     * @return 附件快照列表（{@link UserAttachmentInfo}）；无附件 → null
     */
    private static List<UserAttachmentInfo> userAttachmentsFromAttachments(List<AttachmentRequest> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        List<UserAttachmentInfo> list = new ArrayList<>();
        for (AttachmentRequest att : attachments) {
            if (att == null) {
                continue;
            }
            String contentId = (att.contentId() != null && !att.contentId().isBlank()) ? att.contentId() : null;
            list.add(new UserAttachmentInfo(att.type(), att.filename(), att.mediaType(), contentId, null));
        }
        return list.isEmpty() ? null : list;
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 人读相对时间 · Phase 5 补齐（原 "刚刚" stub）。对齐 CC {@code formatRelativeTimeAgo}
     * （utils/format.ts:186-198，Intl.RelativeTimeFormat）语义——按消息创建时间距现在的时长
     * 返回中文人读格式（项目 {@code ChatMessageDto.time} 契约「2 分钟」/「刚刚」，非 CC 英文
     * "X minutes ago"）：&lt;60s → 「刚刚」；&lt;1h → 「X 分钟前」；&lt;24h → 「X 小时前」；
     * &lt;30 天 → 「X 天前」；更早 → 具体日期（{@code yyyy-MM-dd}）。createdAt 为 null →
     * 「刚刚」（无时间戳兜底，不抛）。
     *
     * @param createdAt 消息创建时间（可 null）
     * @param now       当前时刻（可注入测试；生产 {@code OffsetDateTime.now()}）
     * @return 人读相对时间串
     */
    private static String formatRelativeTimeAgo(OffsetDateTime createdAt, OffsetDateTime now) {
        if (createdAt == null || now == null) {
            return "刚刚";
        }
        long seconds = Duration.between(createdAt, now).getSeconds();
        if (seconds < 60) {
            return "刚刚";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分钟前";
        }
        if (seconds < 86400) {
            return (seconds / 3600) + " 小时前";
        }
        if (seconds < 86400L * 30) {
            return (seconds / 86400) + " 天前";
        }
        return createdAt.toLocalDate().toString();
    }
}

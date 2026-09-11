package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.fork.GlobalCacheScope;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * partial 压缩 REST 编排服务 · 对齐 CC REPL.tsx:4918-4972 {@code onSummarize} 全流程。
 *
 * <p><b>WHY 存在（OD-14 D-1 后端接线）</b>: CC partial 压缩触发层在前端 REPL 消息选择器
 * （MessageSelector onSummarize），Web 后端提供 REST 载体（D-1 方案 1b）。本服务把 CC
 * REPL.tsx:4918-4972 的语义翻译为服务编排：
 * <ol>
 *   <li>{@code getMessagesAfterCompactBoundary(messages)} 剥离（REPL.tsx:4921）——
 *       先剥离 boundary 前已压缩旧消息再 indexOf（防模型总结被故意删除内容）</li>
 *   <li>按 messageId indexOf 定 pivot（REPL.tsx:4922；-1 → 404，对齐 :4923-4930 warning 语义）</li>
 *   <li>{@code PartialCompactConversation.partialCompactConversation}（REPL.tsx:4943，
 *       compact.ts:772-1106，算法已完整实现仅缺接线）</li>
 *   <li>按 direction 重组（REPL.tsx:4950-4952：from=[keep,summary]，up_to=[summary,keep]）+
 *       boundary/attachments/hookResults 拼接</li>
 *   <li>写回消息列表（append-only 追加压缩结果，不删旧行 —— 对齐 CC transcript append-only）
 *       + 新 conversationId（对齐 REPL.tsx:4971 {@code setConversationId(randomUUID())}）</li>
 *   <li>{@code runPostCompactCleanup}（REPL.tsx:4972，PostCompactCleanup.java:160-163，
 *       非 main-thread）</li>
 * </ol>
 *
 * <p><b>错误翻译</b>:
 * <ul>
 *   <li>{@code nothing_to_summarize}（NOTHING_TO_SUMMARIZE_BEFORE/AFTER）→ 400
 *       ValidationException（CC compact.ts:802-808 抛错）</li>
 *   <li>{@code messageId} 不在 active（剥离后）列表 → 404 NotFoundException
 *       （CC REPL.tsx:4923-4930 warning：已 snipped / pre-compact）</li>
 *   <li>生成失败（NO_SUMMARY / PROMPT_TOO_LONG / api_error 前缀）→ 500 原样
 *       （compact.ts:900-916）</li>
 * </ul>
 *
 * <p><b>并发注记</b>: partial 在 REST 线程运行。PartialCompactConversation 经
 * {@code CacheSafeParamsHolder}（ThreadLocal，save→summarize→finally clear，RES-OPD-SP33
 * 契约）承载 partial fork 缓存共享槽位；ThreadLocal 隔离 REST 线程与主 loop 线程，无静态槽位
 * 冲突。PostCompactionState.markPostCompaction(sessionId) 会话级安全。会话在跑 LLM turn 时
 * 并发 partial 会与内存 AgentState.replaceMessages 分叉（ChatService.inProgress 持有会话）
 * —— 需前端确保非 loading 时调用（对齐 CC MessageSelector 仅非 isLoading 可开），未加 409
 * guard（D-1 最小变更，登记 concerns）。
 */
@Service
public class PartialCompactService {

    private static final Logger log = LoggerFactory.getLogger(PartialCompactService.class);

    private final MessageService messageService;
    private final SessionService sessionService;
    /** L4 摘要生产（生产恒非 null @Bean，ToolRegistrationConfig:523；测试/缺配可 null → fail loud） */
    private final StreamCompactSummary streamCompactSummary;
    /** 会话 AgentState 注册表（sessionId → 主 AgentState；fork 缓存共享原料源） */
    private final SessionAgentStateRegistry sessionAgentStateRegistry;
    /** claudemd 引擎（partial sysPromptCtxProvider 的 userContext.claudeMd 通道；null → 单文件子集） */
    private final com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;
    /** skill 目录（partial defaultAssemble 的 session_guidance 子弹源；null → 空） */
    private final com.nexusai.application.agent.skill.SkillCatalog skillCatalog;

    /**
     * 压缩配置 DB 实时读源 · [V54 token-compact-fix B1-2] @Autowired(required=false)，
     * 供 {@link PartialCompactConversation#setSettingsResolver} 静态槽位接线（PTL 重试上限
     * settings.max_ptl_retries 实时读，null 回落常量 3）。null（直构测试/无 bean）→ 不接线
     * → partial 路径回落常量默认（零行为变化）。
     */
    @Autowired(required = false)
    private CompactSettingsResolver settingsResolver;

    /**
     * [IMP2-03 返工 r2] 任务框架服务（async-agent 附件数据源）· CC appState.tasks local_agent
     * （compact.ts:1571-1574）。字段注入镜像 ToolRegistrationConfig:165-166 模式（不动构造器
     * 签名，避免破坏既有 3 参/6 参直构测试）；null（直构测试）→ populate 跳过 async-agent 附件。
     */
    @Autowired(required = false)
    private TaskFrameworkService taskFrameworkService;

    /**
     * [IMP2-03 返工 r2] plan 文件提供者（plan_file_reference/plan_mode 数据源）· CC getPlan/
     * getPlanFilePath（plans.ts:119-145）。生产无 bean → null → plan 附件降级不注入
     * （concern B N/A）；测试可注入假实现。
     */
    @Autowired(required = false)
    private PlanProvider planProvider;

    /**
     * [IMP2-03 返工 r2] 注入任务框架服务（async-agent 附件数据源 · CC appState.tasks）。
     *
     * @param taskFrameworkService 任务框架（null → async-agent 附件跳过）
     */
    public void setTaskFrameworkService(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
    }

    /**
     * [IMP2-03 返工 r2] 注入 plan 文件提供者（plan_file_reference/plan_mode 数据源 · CC plans.ts）。
     *
     * @param planProvider plan 提供者（null → plan 附件降级不注入）
     */
    public void setPlanProvider(PlanProvider planProvider) {
        this.planProvider = planProvider;
    }

    /**
     * [SQLITE_BUSY_SNAPSHOT 修复] 注入落库段事务管理器（测试 seam · 生产走 {@code @Autowired} 字段注入）。
     *
     * @param transactionManager 事务管理器；null → 落库直调（委托 MessageService 自身 @Transactional）
     */
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * [S3-L4-B] PROMPT_CACHE_BREAK_DETECTION feature 门控 · 对齐 auto 路径
     * ToolRegistrationConfig:733 {@code gatedBy(featureFlags)} 模式（CC claude.ts:1469
     * {@code feature('PROMPT_CACHE_BREAK_DETECTION')}）。required=false：非 Spring 直构测试 /
     * 无 bean → null → gatedBy 内部判 null → feature 关 → notifyCompaction 为 no-op（对齐
     * OPD-SP-14 默认关）；feature 开 → 压缩后复位 cache-read 基线
     * （promptCacheBreakDetection.ts:689-698，防压缩后 cache-read 下降误报）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /**
     * [SQLITE_BUSY_SNAPSHOT 修复] 落库段短事务的事务管理器 · {@code @Autowired(required=false)}
     * 字段注入（镜像本类 taskFrameworkService / planProvider / featureFlags / settingsResolver
     * 既有模式，<b>不动构造器签名</b> —— 3 参 / 6 参直构测试逐位不变）。
     *
     * <p>生产 bean <b>实测为</b> {@code com.mybatisflex.spring.FlexTransactionManager}（MyBatis-Flex
     * starter 提供，3461 实例启动日志 {@code 落库段: 独立短事务（TM=FlexTransactionManager）} 实证）；
     * 直构测试 / 无 bean → null → {@link #persistCompactedMessages} 走「直调委托
     * MessageService/SessionService 各自事务」分支（单测 mock 语义不变）。
     */
    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * [partial-compact-progress 2026-09-11] STOMP 模板 · partial 压缩进度推送 sender。
     *
     * <p>与 manual /compact（{@code ToolRegistrationConfig:440 wsTemplate}）/ auto
     * （{@code LlmAgentLoop.setStreamContext} 注入同一 bean）<b>同一注入模式</b>：
     * {@code @Autowired(required=false)} 字段注入（镜像本类 taskFrameworkService / planProvider /
     * featureFlags / transactionManager 既有模式，<b>不动构造器签名</b> —— 3 参 / 6 参直构测试
     * 逐位不变）。非 STOMP 路径 / 直构测试 → null → 不注册进度推送（压缩照常完成，只是前端无横幅，
     * 与 manual/auto 无 wsTemplate 时同款降级）。
     */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /**
     * [P3-a-2] settings 单例行 mapper · {@code settings.main_model_name} 读取通道
     * （会话无 model_name override 时的模型回落源，见 {@link #resolveSessionModelName(String)}）。
     *
     * <p>与 manual /compact 路径同源：{@code ToolRegistrationConfig.readDbMainModelName}（静态）
     * 读的正是本表 {@code id=1} 行同一列；{@code MessageService.resolveSessionModel} 同链。
     * {@code @Autowired(required=false)} 字段注入（镜像本类 taskFrameworkService / planProvider /
     * settingsResolver / wsTemplate 既有模式，<b>不动构造器签名</b> —— 3 参 / 6 参直构测试逐位不变）；
     * null（直构测试 / 无 bean）→ 模型回落链止于会话记录（返 null → 统一回落非 Anthropic）。
     */
    @Autowired(required = false)
    private com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper;

    /** 测试注入 STOMP 模板（生产走 {@code @Autowired} 字段注入 · 对齐 setPlanProvider 先例）。 */
    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    @Autowired
    public PartialCompactService(MessageService messageService,
                                 SessionService sessionService,
                                 @Autowired(required = false) StreamCompactSummary streamCompactSummary,
                                 @Autowired(required = false) SessionAgentStateRegistry sessionAgentStateRegistry,
                                 @Autowired(required = false) com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine,
                                 @Autowired(required = false) com.nexusai.application.agent.skill.SkillCatalog skillCatalog) {
        this.messageService = messageService;
        this.sessionService = sessionService;
        this.streamCompactSummary = streamCompactSummary;
        this.sessionAgentStateRegistry = sessionAgentStateRegistry;
        this.claudemdEngine = claudemdEngine;
        this.skillCatalog = skillCatalog;
    }

    /** 测试便捷构造（未注册会话 / 无 claudemd/skill 时原料缺 → best-effort 流式 fallback） */
    public PartialCompactService(MessageService messageService,
                                 SessionService sessionService,
                                 StreamCompactSummary streamCompactSummary) {
        this(messageService, sessionService, streamCompactSummary, null, null, null);
    }

    /**
     * partial 压缩编排 · 对齐 CC REPL.tsx:4918-4972 onSummarize。
     *
     * <p><b>[SQLITE_BUSY_SNAPSHOT 修复] 本方法刻意不加 {@code @Transactional}</b>。
     * 原实现把整个编排（读 listForResume → 几十秒 LLM 摘要 → 写回）包在一个事务里：SQLite WAL 下，
     * 事务的第一条 SELECT 就固定了读快照，此后任何其它连接提交写都会让该快照失效 → 事务内<b>后续
     * 任何写</b>必然抛 {@code SQLITE_BUSY_SNAPSHOT}（SQLite 不允许把过期读快照升级为写事务，
     * {@code busy_timeout} 也救不了），于是 partial-compact 在并发写（quartz 触发 / 实时落库 /
     * 其它会话）下<b>稳定 500</b>。事务边界因此收窄为「只有落库那一段」
     * （{@link #persistCompactedMessages} 内 TransactionTemplate 短事务）：LLM 长调用在事务外，
     * 落库事务从开启到提交只跨毫秒级，不再携带过期快照。
     *
     * <p><b>一致性影响</b>：读阶段（步骤 1-6）改为各自自动提交的短读 —— 与 CC 同构
     * （CC 的 partial compact 也没有跨 LLM 调用的 DB 事务，transcript 是 append-only 逐条落盘）。
     * 落库的两处写（{@link MessageService#appendPostCompactMessages} + updateConversationId）
     * 仍在<b>同一个</b>短事务内 → 二者原子性与修复前一致（任一失败整块回滚，不留半写）。
     * {@code PostCompactCleanup} 移到事务提交之后执行（纯内存态复位，本就不需要事务语义）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param request   请求（messageId / direction / feedback）
     * @return 重组后消息列表 + 新 conversationId（前端 setMessages + setConversationId）
     */
    public PartialCompactResponse partialCompact(String sessionId, PartialCompactRequest request) {
        RequestContext.setSession(sessionId);
        CompactConversationContext ctx = null;
        // [partial-compact-progress 2026-09-11] 统一压缩进度通道注册（+ 压缩可中断）。
        //
        // <p><b>WHY（要修的缺陷）</b>：partial 原先在 {@code buildContext} 里把 {@code cc} 的进度
        // sink 覆写成「只写日志」（{@code cc.setOnCompactProgress(event -> log.info(...))}）——
        // {@link PartialCompactConversation} 确实全链 emit 5 事件（PRE_COMPACT / CompactStart /
        // SESSION_START / POST_COMPACT / CompactEnd，:299/:311/:462/:521/:560），但事件只进日志、
        // 不进 STOMP → 前端订阅的 {@code /topic/sessions/{sid}/compact-progress} 恒无消息 →
        // 用户在消息选择器触发 partial 压缩后几十秒内看不到任何进度（不知道在压缩、也不知道进度）。
        //
        // <p><b>照抄的两条既有路径（本处不改语义，只接同一通道）</b>：
        // <ul>
        //   <li>manual {@code /compact}：{@code ToolRegistrationConfig:2307-2336} 注册
        //       （进度={@code CompactProgressState.register} → wsTemplate.convertAndSend /
        //       abort={@code registerAbort}+{@code registerSessionAbort}）+ {@code :2370-2382}
        //       finally 三清理</li>
        //   <li>auto compact：{@code LlmAgentLoop:2207-2224} 注册 + {@code :2227-2232}
        //       finally {@code clear()}</li>
        // </ul>
        //
        // <p><b>线程安全（partial 跑 REST 线程的关键核实）</b>：
        // <ul>
        //   <li>{@code CompactConversationContext.getOnCompactProgress()} 在<b>事件 accept 时</b>
        //       读 {@code CompactProgressState.current()}（ThreadLocal），{@code StreamCompactSummary}
        //       的 abort supplier（{@code () -> CompactProgressState.currentAbort()}）与
        //       {@code SummaryProgress} 推送同样在<b>调用线程</b>读 ThreadLocal。partial 全程
        //       （含 {@code StreamCompactSummary.summarize} / {@code PartialCompactConversation}）
        //       在 REST 线程同步执行、无线程切换 → 注册/读取同线程，ThreadLocal 命中。</li>
        //   <li>abort 是<b>会话级</b>（{@code CompactProgressState.sessionAborts} 为
        //       ConcurrentHashMap，非 ThreadLocal）→ 前端「停止」→ {@code ChatService.cancelSession}
        //       （{@code :1855 abortForSession(sessionId)}）在<b>另一线程</b>也能命中本 REST 线程
        //       注册的 AbortController，与 manual 路径同一条跨线程链路。</li>
        // </ul>
        // 注册点刻意放在 {@code try} <b>之前</b>：保证下方 finally 的三个清理在成功/业务失败/
        // 异常三路必达（register/clear 成对，不为 REST 线程留 ThreadLocal/静态槽残留）。
        AbortController compactAbort = new AbortController();
        registerProgressChannel(sessionId, compactAbort);
        try {
            // ── 1. 加载消息（MessageService.listBySession 校验 session 存在，不存在 → 404）──
            // [S1] partial 压缩 = 续聊加载历史通道 → listForResume（对齐 CC
            //   deserializeMessagesWithInterruptDetection：未配对 tool_use/孤立 thinking/纯空白
            //   assistant 剥离 + 中断 turn "Continue" sentinel 注入）。CC partial compact 消费的
            //   是 deserialize 后的内存消息（loadConversationForResume 已应用中断语义），Java 侧
            //   DB 即 CC 内存列表等价物，压缩输入同样过中断语义漏斗。DB 权威写入不变
            //   （appendPostCompactMessages append-only 写回，见步骤 7；被摘要掉的旧行保留在
            //   boundary 之前 → 读侧剪枝，与 auto/reactive compact 同一语义）。
            List<ChatMessageDto> messages = messageService.listForResume(sessionId);

            // [ALIGN-COMP-1 P1] resume 恢复 invokedSkills / suppress 副作用已迁至通用续跑入口
            // （LlmAgentLoop.run 入口，镜像 CC loadConversationForResume:556-558）。partial 压缩
            // 路径不再重复恢复——续跑入口重建的 invokedSkills 覆盖本路径数据源。

            // ── 2. boundary 剥离（REPL.tsx:4921 getMessagesAfterCompactBoundary）──
            List<ChatMessageDto> compactMessages =
                BoundaryReader.getMessagesAfterCompactBoundary(messages);

            // ── 3. messageId 定 pivot（REPL.tsx:4922 compactMessages.indexOf(message)）──
            int pivot = indexOfMessageId(compactMessages, request.messageId());
            if (pivot == -1) {
                // REPL.tsx:4923-4930：已 snipped / pre-compact → 显式提示而非静默 no-op
                log.warn("[PartialCompact] messageId={} 不在剥离后 active 列表（snipped/pre-compact）→ 404",
                    request.messageId());
                throw new NotFoundException(
                    "Message " + request.messageId() + " is no longer in the active context "
                        + "(snipped or pre-compact). Choose a more recent message.");
            }
            log.info("[PartialCompact] 会话 {}: 剥离后 {} 条消息，pivot(messageId={}) = {}，direction={}",
                sessionId, compactMessages.size(), request.messageId(), pivot, request.direction());

            // ── 4. 构建压缩上下文（mirror ToolRegistrationConfig.buildCompactConversationContext:1389-1413）──
            ctx = buildContext(sessionId, compactAbort);

            // ── 4.5 [ALIGN-COMP-1 CS-2] partial 路径 registry holder 装配（镜像 CompactConversation
            //   holder · AutoCompactor:598-603 同款接线时机）── 使 step 13
            //   populateInvokedSkillsAttachment 能经 sessionId 解析主 AgentState 重注入
            //   invoked_skills 附件（CC compact.ts:950-953 createSkillAttachmentIfNeeded→push）。
            if (sessionAgentStateRegistry != null) {
                PartialCompactConversation.setSessionAgentStateRegistry(sessionAgentStateRegistry);
            }
            // [V54 token-compact-fix B1-2] partial 路径 PTL 重试上限静态槽位接线（同 holder 先例；
            //   settings.max_ptl_retries 有值覆盖常量 3，null 回落；幂等，每调用注入一次）
            PartialCompactConversation.setSettingsResolver(settingsResolver);

            // ── 5. partialCompactConversation（REPL.tsx:4943，compact.ts:772-1106）──
            CompactionResult result;
            try {
                result = PartialCompactConversation.partialCompactConversation(
                    compactMessages, pivot, ctx, request.feedback(), request.toCompactDirection());
            } catch (IllegalArgumentException e) {
                // 错误翻译：nothing_to_summarize → 400（CC compact.ts:802-808）
                if (isNothingToSummarize(e.getMessage())) {
                    log.warn("[PartialCompact] 空 summarize 抛错（400）: {}", e.getMessage());
                    throw new ValidationException(e.getMessage());
                }
                // 其余（NO_SUMMARY / PROMPT_TOO_LONG / api_error 前缀）原样 → 500
                throw e;
            }

            // ── 6. direction-aware 重组（REPL.tsx:4950-4952）──
            List<ChatMessageDto> postCompact =
                CompactionResult.buildPartialPostCompactMessages(result, request.toCompactDirection());
            log.info("[PartialCompact] 重组: direction={} 压缩后消息 {} 条（boundary→ordered→attachments→hooks）",
                request.direction(), postCompact.size());

            // ── 7. 写回：<b>append-only</b> 追加压缩结果 + 新 conversationId（REPL.tsx:4964/4971）──
            // [SM/compact 对齐 CC] 由 replaceSessionMessages（删全表 + 换时间基重插）改为
            //   appendPostCompactMessages（只追加 boundary/summary 新行 + 把 kept 段 created_at 重挂到
            //   boundary 之后，<b>绝不删除旧行</b>）—— 与 auto/reactive compact 同一条落库语义
            //   （ChatService.armRealTimePersist 的 compactPersistListener 亦调本方法）。
            //   WHY（结构同构 + 边界剪枝契约）: partial 的结果集与全量 compact 完全同构
            //   （CompactionResult.buildPartialPostCompactMessages = boundary → ordered(summary/keep)
            //   → attachments → hookResults），且本方法步骤 2 已用
            //   BoundaryReader.getMessagesAfterCompactBoundary 读侧剪枝 —— append-only 正是该读侧契约的
            //   写入侧对偶（CC transcript 恒 append-only，sessionStorage.ts recordTranscript；compact 只追加
            //   boundary+summary，加载侧按最后 boundary 剪枝）。旧 replace 路径换时间基后，与实时落库/
            //   MessageService.nextCreatedAt 单调分配器不同源 → 重插行可能早于会话已有行 → 下轮
            //   boundary 切片把 kept 段整段丢掉（与 auto compact 修复前同款 HIGH bug）。
            //   被摘要掉的旧行保留在 DB（boundary 之前 → 模型面剪枝；轨迹可回溯），与 auto compact 一致。
            // [SQLITE_BUSY_SNAPSHOT 修复] 落库走「事务外 LLM + 短事务 + BUSY_SNAPSHOT 重试」
            //   （见 persistCompactedMessages 的 JavaDoc；本方法已刻意不加 @Transactional）。
            String newConversationId = UUID.randomUUID().toString();
            List<ChatMessageDto> normalized =
                persistCompactedMessages(sessionId, postCompact, newConversationId);

            // ── 8. runPostCompactCleanup（REPL.tsx:4972，非 main-thread，querySource=compact）──
            // [SQLITE_BUSY_SNAPSHOT 修复] 移到落库短事务提交之后 —— 5 项清理全是内存态复位
            // （resetMicrocompactState / clearSystemPromptSections / clearClassifierApprovals …），
            // 不需要事务语义；放在事务外反而保证「清理只在落库成功后发生」。
            PostCompactCleanup.runPostCompactCleanup("compact");

            return new PartialCompactResponse(normalized, newConversationId);
        } finally {
            // [partial-compact-progress 2026-09-11] 统一进度通道清理（与上方 registerProgressChannel
            //   成对；三路必达：成功 / 业务失败(400/404) / 异常）。三处 clear 均幂等，未注册也安全。
            //   顺序对齐 manual ToolRegistrationConfig:2375-2379（clear → clearAbort → removeSessionAbort）。
            CompactProgressState.clear();
            CompactProgressState.clearAbort();
            CompactProgressState.removeSessionAbort(sessionId);
            if (log.isDebugEnabled()) {
                log.debug("[PartialCompact] 压缩进度通道已清理: sessionId={}（ThreadLocal 进度/abort 出栈，"
                    + "会话级 abort 槽位移除）", sessionId);
            }
            // [RES-C3] partial 会话级 sysPromptCtxProvider 生命周期终结（register/unregister 成对，
            // RES-C2 契约：成功/业务失败/异常三路均注销，close 幂等）
            if (ctx != null && ctx.getSysPromptCtxProvider() != null) {
                ctx.getSysPromptCtxProvider().close();
            }
            RequestContext.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [partial-compact-progress 2026-09-11] 统一压缩进度/可中断通道
    // ════════════════════════════════════════════════════════════════════

    /**
     * 注册 partial 压缩的统一进度通道（对齐 manual {@code ToolRegistrationConfig:2307-2336} /
     * auto {@code LlmAgentLoop:2207-2224}，<b>不另起第二套</b>）。
     *
     * <p>三件事，与两条既有路径逐条同构：
     * <ol>
     *   <li><b>进度推送</b>：wsTemplate 可得 → {@code CompactProgressState.register(consumer)}，
     *       consumer 内 {@code convertAndSend(CompactProgressState.topic(sid), toFrontendJson(event))}
     *       → 前端 {@code /topic/sessions/{sid}/compact-progress} 横幅（CompactProgressBar）。
     *       wsTemplate 缺失（非 STOMP 路径/直构测试）→ 不注册，fail loud 记 warn（规则十二），
     *       压缩照常完成。</li>
     *   <li><b>线程级 abort</b>：{@code registerAbort(compactAbort)}（ThreadLocal）→
     *       {@code StreamCompactSummary} 的 abort supplier（{@code ToolRegistrationConfig:1061}）
     *       取到 → 前端取消 → 摘要 provider 硬断流 → 'Compaction canceled.'（对齐 CC
     *       compact.ts:126）。</li>
     *   <li><b>会话级 abort</b>：{@code registerSessionAbort(sid, compactAbort)}（ConcurrentHashMap，
     *       跨线程）→ 前端停止/Esc → {@code ChatService.cancelSession:1855 abortForSession(sid)}
     *       可达本 REST 线程在飞 partial 压缩。</li>
     * </ol>
     *
     * <p><b>成对性</b>：调用方 {@link #partialCompact} 在 finally 内调用 {@code clear()} /
     * {@code clearAbort()} / {@code removeSessionAbort(sessionId)}（三路必达，幂等）。
     *
     * @param sessionId   会话 ID（short 直键 · 与 topic 拼装/会话级 abort 同源）
     * @param compactAbort 本次 partial 压缩的 AbortController（同时设进 ctx.abortController，
     *                     使 CompactHooks 的 hook batch abort 与摘要断流同源）
     */
    private void registerProgressChannel(String sessionId, AbortController compactAbort) {
        // 会话级 + 线程级 abort 恒注册（与 wsTemplate 无关；manual 路径同样无条件注册）
        CompactProgressState.registerAbort(compactAbort);
        CompactProgressState.registerSessionAbort(sessionId, compactAbort);
        if (wsTemplate == null || sessionId == null) {
            // fail loud（规则十二）：非 STOMP 路径跳过的是「推送」，不是「压缩」——不能静默。
            log.warn("[PartialCompact] wsTemplate/sessionId 缺失 → partial 压缩进度不推前端"
                + "（非 STOMP 路径或直构测试；abort 通道仍已注册）: sessionId={} wsTemplate={}",
                sessionId, wsTemplate != null);
            return;
        }
        String topic = CompactProgressState.topic(sessionId);
        CompactProgressState.register(event -> wsTemplate.convertAndSend(
            topic, CompactProgressState.toFrontendJson(event)));
        log.info("[PartialCompact] 压缩进度通道已注册: topic={}（前端进度横幅源；abort=线程级+会话级，"
            + "前端停止可达 REST 线程在飞压缩）", topic);
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部方法
    // ════════════════════════════════════════════════════════════════════

    /** messageId → 剥离后列表下标（REPL.tsx:4922 indexOf；-1 = 不在 active 列表）。 */
    private static int indexOfMessageId(List<ChatMessageDto> messages, String messageId) {
        if (messages == null) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageDto m = messages.get(i);
            if (m != null && messageId.equals(m.id())) {
                return i;
            }
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════════
    // [SQLITE_BUSY_SNAPSHOT 修复] 落库段短事务 + 重试
    // ════════════════════════════════════════════════════════════════════

    /** 落库短事务最大尝试次数（首次 + 4 次重试）；有限次数，耗尽则 fail loud。 */
    static final int MAX_WRITE_ATTEMPTS = 5;

    /**
     * partial 压缩落库段 · 独立<b>短事务</b> + BUSY_SNAPSHOT 重试（[SQLITE_BUSY_SNAPSHOT 修复]）。
     *
     * <p><b>WHY（根因）</b>：SQLite WAL 下事务的第一条 SELECT 固定读快照；该快照被其它连接提交顶掉后，
     * 事务内<b>任何写</b>都抛 {@code SQLITE_BUSY_SNAPSHOT}（SQLite 不允许把过期读快照升级为写事务，
     * {@code busy_timeout} 对本错误不生效）。旧实现把 {@code partialCompact} 整体 {@code @Transactional}
     * 且事务内夹着几十秒的 LLM 摘要 → 并发写（quartz 触发 / 实时落库 / 其它会话）必然让快照过期 →
     * 写回必失败（稳定 500）。本方法把落库收进「开启到提交只跨毫秒级」的短事务，快照不再有机会过期。
     *
     * <p><b>重试（为什么必要且为什么必须换事务）</b>：短事务内仍有毫秒级「先读后写」窗口
     * （{@code appendPostCompactMessages} 先读 knownIds 再写），窗口内恰有并发提交仍可能 BUSY_SNAPSHOT
     * —— 此时同事务内重试无用（快照已死），必须开新事务拿新快照；{@code TransactionTemplate.execute}
     * 每次调用都开新事务，故重试天然正确。重试幂等：上一次尝试整体回滚、DB 无残留，boundary/summary
     * 的 id 来自上游 DTO（稳定），重插不产生重复行（seq/created_at 分配器只产生跳号，不产生回退）。
     *
     * <p><b>原子性</b>：两处写（appendPostCompactMessages + updateConversationId）在<b>同一事务</b>内 ——
     * 与修复前一致（任一失败整块回滚，不留「boundary 落了而 conversationId 没更新」的半写）。
     *
     * @param sessionId         会话 ID
     * @param postCompact       重组后待落库消息（append-only 语义，见 MessageService）
     * @param newConversationId 新 conversationId（REPL.tsx:4971）
     * @return 归一化后的消息列表（供响应体）
     */
    List<ChatMessageDto> persistCompactedMessages(String sessionId,
                                                  List<ChatMessageDto> postCompact,
                                                  String newConversationId) {
        if (transactionManager == null) {
            // 直构测试 / 无 TM bean → 直调：MessageService.appendPostCompactMessages 自带
            // @Transactional 兜底（单测 mock 场景无 DB，语义逐位不变）。
            // 规则十二 fail loud：短事务护栏未启用属「降级」，必须显式记日志，不得静默。
            log.warn("[PartialCompact] 无 PlatformTransactionManager → 落库直调（短事务 + BUSY_SNAPSHOT 重试"
                + "护栏未启用；生产不应出现，出现即注入缺失）");
            List<ChatMessageDto> normalized = messageService.appendPostCompactMessages(sessionId, postCompact);
            sessionService.updateConversationId(sessionId, newConversationId);
            return normalized;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        log.info("[PartialCompact] 落库段: 独立短事务（TM={}）+ BUSY_SNAPSHOT 重试上限 {} 次",
            transactionManager.getClass().getSimpleName(), MAX_WRITE_ATTEMPTS);
        RuntimeException lastBusy = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                List<ChatMessageDto> normalized = template.execute(status -> {
                    List<ChatMessageDto> written =
                        messageService.appendPostCompactMessages(sessionId, postCompact);
                    sessionService.updateConversationId(sessionId, newConversationId);
                    return written;
                });
                if (attempt > 1) {
                    log.warn("[PartialCompact] 落库在第 {} 次尝试成功（前 {} 次 BUSY_SNAPSHOT，已换新事务重试）",
                        attempt, attempt - 1);
                }
                return normalized;
            } catch (RuntimeException e) {
                if (!isSqliteBusy(e)) {
                    throw e;
                }
                lastBusy = e;
                log.warn("[PartialCompact] 落库第 {}/{} 次尝试命中 SQLITE_BUSY_SNAPSHOT（换新事务重试）: {}",
                    attempt, MAX_WRITE_ATTEMPTS, e.getMessage());
            }
        }
        log.error("[PartialCompact] 落库 {} 次尝试均因 SQLITE_BUSY_SNAPSHOT 失败，放弃（fail loud）",
            MAX_WRITE_ATTEMPTS);
        throw lastBusy;
    }

    /**
     * SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT 判别（可重试错误）· 沿 cause 链匹配消息
     * 「SQLITE_BUSY」（同时覆盖 {@code SQLITE_BUSY}(5) 与 {@code SQLITE_BUSY_SNAPSHOT}(517) 两种文案）。
     * <b>不</b>按 {@code org.sqlite.SQLiteException} 类型匹配：生产异常可能被 MyBatis / Spring 事务层
     * 包装，消息仍留在 cause 链上，但最外层类型不一定是它。
     */
    static boolean isSqliteBusy(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("SQLITE_BUSY")) {
                return true;
            }
            Throwable next = cur.getCause();
            cur = (next == cur) ? null : next;
        }
        return false;
    }

    /** nothing_to_summarize 判别（CC compact.ts:802-808 抛错文本）。 */
    private static boolean isNothingToSummarize(String message) {
        return PartialCompactConversation.ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_BEFORE.equals(message)
            || PartialCompactConversation.ERROR_MESSAGE_NOTHING_TO_SUMMARIZE_AFTER.equals(message);
    }

    /**
     * 构建压缩上下文 · mirror ToolRegistrationConfig.buildCompactConversationContext:1389-1413
     * （生产接线模式）。agentId 用 "main"（REST 无 agent 参数；notifyCompaction 经
     * {@link com.nexusai.application.agent.lsp.PromptCacheBreakDetection#gatedBy} 门控接线，
     * feature 关 → no-op；hookRegistry null → agentId 当前流程不可达消费）；summaryProducer
     * 接 StreamCompactSummary（null → fail loud）。
     *
     * <p><b>[partial-compact-progress 2026-09-11] onCompactProgress 不再在此覆写</b>：原实现
     * {@code cc.setOnCompactProgress(event -> log.info(...))} 是「只写日志」的自建第二套 sink
     * （事件到不了前端）。现由 {@link #partialCompact} 经 {@link #registerProgressChannel}
     * 注册 {@code CompactProgressState}（与 manual/auto 同一条统一通道）→
     * {@link CompactConversationContext#getOnCompactProgress()} 在 accept 时优先返回该注册，
     * 无注册（无 STOMP）→ 回落字段默认 no-op。<b>本处刻意不设兜底 sink</b>，避免与统一通道
     * 形成第二条链（对齐 auto buildAutoContext 之外的 LlmAgentLoop/手动路径均不自设 sink 的现状）。
     *
     * <p>[RES-C3] OPD-SP-33 生产 partial 注入会话 AgentState 组装链原料（CC getCacheSharingParams
     * compact.ts:250-287 + REPL.tsx:4938-4942 调用方提供 cacheSafeParams）：会话 AgentState
     * 可得 → 四原料 + gate 全量注入（toolUseContext / sysPromptCtxProvider /
     * defaultSysPromptAssemble / customSystemPrompt / appendSystemPrompt / useGlobalCacheScope），
     * 使 {@link PartialCompactConversation#partialCompactConversation} 的
     * {@code buildCacheSafeParamsForPartial} 返回非 null → fork 缓存共享生效（对齐 R1 manual
     * ToolRegistrationConfig:1302-1310 同款通道）。会话未注册 AgentState → best-effort 缺原料
     * → build 返回 null → save(null) → 走流式 fallback（缓存共享为优化项，不阻断压缩，不抛错）。
     */
    private CompactConversationContext buildContext(String sessionId, AbortController compactAbort) {
        CompactConversationContext cc = new CompactConversationContext();
        // [session-id-short] REST 路径变量 sessionId 已 short 直键，直接入 ctx（不再 parseSessionUuid
        // 归一化——registry 注册键同为 short，populateInvokedSkillsAttachment 可命中；原 UUID.fromString
        // 必抛异常的历史错位根因消除）。
        cc.setSessionId(sessionId);
        cc.setAgentId("main");
        // [P3-a-2] 本会话有效模型 —— 见 {@link #resolveCompactModel(String)} 的 WHY：partial 的
        //   preCompactTokenCount / compactionCallTotalTokens 两处按 ctx.getModel() 分派协议
        //   （PartialCompactConversation:295/468 → CompactConversation.resolveAnthropic），
        //   不设 → 不可判定 → 回落非 Anthropic → anthropic 会话少计（deepseek 恰好正确）。
        cc.setModel(resolveCompactModel(sessionId));
        cc.setQuerySource("compact");
        cc.setReadFileState(new LinkedHashMap<>());
        // [S3-L4-B] PROMPT_CACHE_BREAK_DETECTION 门控接线（对齐 ToolRegistrationConfig:733-734
        // gatedBy(featureFlags) 模式）· feature 关 → 内部 no-op；feature 开 → 压缩后复位
        // cache-read 基线（querySource="compact"，agentId="main"）
        cc.setNotifyCompaction(() -> com.nexusai.application.agent.lsp.PromptCacheBreakDetection
            .gatedBy(featureFlags).notifyCompaction("compact", "main"));
        // [partial-compact-progress 2026-09-11] 原 `cc.setOnCompactProgress(event -> log.info(...))`
        //   （只写日志、前端恒无横幅）已删除：进度 sink 统一由 partialCompact 的
        //   registerProgressChannel 注册进 CompactProgressState，getOnCompactProgress() 优先取该注册。
        //   非 STOMP 路径 → 回落字段默认 no-op（与 auto/manual 同款降级，不再自建第二套 sink）。
        // [可中断 2026-09-04 · CC Esc] partial 压缩的 AbortController 与统一通道同一实例：
        //   CompactHooks 的 hook batch abort（CompactHooks:73/234 读 ctx.getAbortController()）
        //   与 StreamCompactSummary 摘要断流（经 CompactProgressState.currentAbort()）同源 ——
        //   前端停止 → 摘要/hook 一并断（对齐 manual ctx.abortController 语义）。
        cc.setAbortController(compactAbort);
        if (streamCompactSummary != null) {
            cc.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
                try {
                    // [IMP-CM-14 F02] 透传 StreamCompactSummary.summarize 返回的 SummaryResult
                    //   （text + 压缩 API 真实 usage）——旧实现丢弃 usage 改包 new SummaryResult(text, null)
                    //   使 postCompactTokenCount/compactionInputTokens 恒 null/0（f4/f5 根因之一）。
                    return streamCompactSummary.summarize(compactPrompt, messagesToSummarize);
                } catch (Exception e) {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            });
        } else {
            // 生产恒非 null（@Bean required 注入）；缺省 fail loud（规则十二）
            cc.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
                throw new IllegalStateException("StreamCompactSummary 未注入，无法生产 partial 压缩摘要");
            });
        }
        // [RES-C3] 会话 AgentState 组装链原料注入（CC getCacheSharingParams compact.ts:250-287）
        assembleForkCacheSharingMaterials(cc, sessionId);
        // [IMP2-03 返工 r2] partial 路径 async-agent/plan/plan_mode 附件生产（CC
        // partialCompactConversation compact.ts:925-948 生产 file+async+plan+plan_mode+skill；
        // file+3×delta 在 restore() 内（PartialCompactConversation:311），本处补齐
        // async-agent/plan/plan_mode —— 反思修正清单 2：返工前 partial 仅 3×delta 接入，
        // async/plan/plan_mode 三工厂在 partial 路径 0 生产（CC 设计意图 compact.ts:1536-1567
        // 「so the model doesn't spawn a duplicate」/「otherwise it would lose the plan mode
        // instructions」）。数据源：taskFrameworkService/planProvider 字段（Spring 注入或测试
        // setter）；tuc 由 assembleForkCacheSharingMaterials 注入（会话未注册 AgentState →
        // tuc null → plan_mode 不生产，best-effort 降级同 fork 缓存共享）。
        PostCompactAttachmentRestorer.populatePostCompactAttachments(cc, taskFrameworkService, planProvider);
        // [IMP2-03 返工 r4] partial 路径 skill 附件补齐（反思复检 r4 修正清单 1）· CC
        // partialCompactConversation compact.ts:950-953 在 plan_mode 之后生产 skill 附件
        // （createSkillAttachmentIfNeeded(context.agentId)）；修复前 partial 路径
        // populateInvokedSkillsAttachment 0 生产（全仓唯一调用点 CompactConversation:274 全量
        // 路径）→ 模型丢失 invoked skill 内容（INV-15 在 partial 路径未闭环）。镜像
        // CompactConversation:274 同签名调用；registry null / 会话未注册 / 无 invoked skill →
        // 安全跳过不抛错（不中断压缩成功路径）。顺序：additional=[async,plan,plan_mode,skill]
        // → restore() 产出 file→async→plan→plan_mode→skill→3×delta（CC compact.ts:925-975 全序）。
        PostCompactAttachmentRestorer.populateInvokedSkillsAttachment(sessionAgentStateRegistry, cc);
        return cc;
    }

    /**
     * [P3-a-2] partial 压缩上下文的会话有效模型名 · 与 manual {@code /compact} 同源解析。
     *
     * <h2>WHY</h2>
     * {@link PartialCompactConversation} 的 {@code preCompactTokenCount}
     * （{@code PartialCompactConversation:295}）与 {@code compactionCallTotalTokens}（{@code :468}）
     * 都按 {@code ctx.getModel()} 分派协议（{@code CompactConversation.resolveAnthropic} → 唯一权威
     * {@code ContextUsageCalculator.isAnthropic}）：Anthropic = 4 项和；OpenAI/DeepSeek = 仅
     * {@code input + output}（prompt_tokens 已含 cache hit）。{@code buildContext} 原先不
     * {@code setModel} → {@code ctx.getModel()=null} → 不可判定 → 回落非 Anthropic →
     * <b>anthropic 会话少计</b>（deepseek 会话恰好正确）。
     *
     * <h2>解析顺序（两步，与 {@code ToolRegistrationConfig.resolveManualCompactModel} 镜像）</h2>
     * <ol>
     *   <li><b>已注册 live state</b>：{@code state.currentModel()}（{@code LlmAgentLoop.doRun:2461}
     *       入口 + 每轮 {@code resolveTurnEffectiveModel} 覆盖写 = auto 路径 {@code AutoCompactor.model}
     *       同源）→ 缺失时回落 {@code state.currentToolUseContext().effectiveModelName()}
     *       （可能滞后一轮的 per-turn 值）。</li>
     *   <li><b>未注册 / 空缺</b>（空闲会话、REST 线程历史会话）：
     *       {@link #resolveSessionModelName(String)} 走 DB 链
     *       （{@code sessions.model_name} → {@code settings.main_model_name}），
     *       与 {@code ToolRegistrationConfig.rebuildIdleStateFromDb} 的补模型同一链。</li>
     * </ol>
     *
     * <p><b>形态</b>：与 auto 路径同形态（库中原始名，全名/裸名皆可，
     * {@code ContextUsageCalculator.isAnthropic} 经 {@code ModelNameResolver.resolve} 全名感知分派）。
     * 全部不可得 → null → 交 {@code resolveAnthropic} 统一回落非 Anthropic（不在此臆断协议）。
     *
     * @param sessionId 会话 DB 键（short 形态 sess-xxx）
     * @return 本会话有效模型名；不可得 → null
     */
    String resolveCompactModel(String sessionId) {
        AgentState live = sessionAgentStateRegistry == null ? null
            : sessionAgentStateRegistry.get(sessionId);
        if (live != null) {
            String model = live.currentModel();
            if (model == null || model.isBlank()) {
                ToolUseContext tuc = live.currentToolUseContext();
                model = tuc != null ? tuc.effectiveModelName() : null;
            }
            if (model != null && !model.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[P3-a-2] partial 模型解析: session={} model={}（源=live AgentState，"
                            + "与 auto 路径 resolveTurnEffectiveModel 同口径）",
                        sessionId, model);
                }
                return model;
            }
        }
        // 未注册 / live state 模型空缺 → DB 链（会话 override → settings 主模型）
        return resolveSessionModelName(sessionId);
    }

    /**
     * [P3-a-2] 会话有效模型名（DB 链）· {@code sessions.model_name} → {@code settings.main_model_name}。
     *
     * <p>与 manual /compact 路径的空闲重建同链同序（镜像
     * {@code ToolRegistrationConfig.resolveSessionModelName}）：auto 路径模型最终来自
     * {@code ChatService.resolveModelNameForSession}（四层链，会话层 + settings 层即此处；
     * 请求体模型参数层在 partial 这类「历史消息选择器」入口不存在）。既有同层拷贝：
     * {@code MessageService.resolveSessionModel}（重拉上下文快照补算）。
     *
     * @param sessionId 会话 DB 键（short 形态 sess-xxx）
     * @return 模型名；不可得（会话行缺失 / 两处皆空 / mapper 未注入 / 读取异常）→ null
     */
    String resolveSessionModelName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            com.nexusai.model.session.dto.SessionDto session = sessionService == null
                ? null : sessionService.getById(sessionId);
            if (session != null && session.modelName() != null && !session.modelName().isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[P3-a-2] partial 模型解析: session={} model={}（源=sessions.model_name 会话 override）",
                        sessionId, session.modelName());
                }
                return session.modelName();
            }
        } catch (Exception e) {
            // 会话行缺失（NotFoundException）/ 读取异常 → 继续回落 settings（不阻断压缩）
            log.warn("[P3-a-2] partial 会话模型解析失败（回落 settings.main_model_name）: session={} err={}",
                sessionId, e.toString());
        }
        String settingsModel = readDbMainModelName();
        if (log.isDebugEnabled()) {
            log.debug("[P3-a-2] partial 会话无 override → settings.main_model_name={}（session={}）",
                settingsModel, sessionId);
        }
        return settingsModel;
    }

    /**
     * [P3-a-2] 读 DB 主模型名（{@code settings.main_model_name}，单例行 id=1）。
     *
     * <p>与 {@code ToolRegistrationConfig.readDbMainModelName(settingsMapper)} 同一行同一列
     * （manual 路径版本为静态方法，因跨类复用不便而在此按同一语义收敛；两处读点同源同表）。
     * 失败一律 null（mapper 未注入 / 行缺失 / 空值 / 异常）→ 调用方走「不可判定」统一回落。
     *
     * @return main_model_name（非空白）或 null
     */
    private String readDbMainModelName() {
        if (settingsMapper == null) {
            return null;
        }
        try {
            com.nexusai.repository.settings.entity.SettingsRecord s = settingsMapper.selectOneById(1);
            String v = s != null ? s.getMainModelName() : null;
            return v != null && !v.isBlank() ? v : null;
        } catch (Exception e) {
            log.warn("[P3-a-2] partial DB settings.main_model_name 读取失败, 回落 null: {}", e.toString());
            return null;
        }
    }

    /**
     * 组装 partial fork 缓存共享原料 · 对齐 R1 manual ToolRegistrationConfig:1302-1310
     * （CC getCacheSharingParams compact.ts:250-287）：toolUseContext=state.currentToolUseContext()、
     * sysPromptCtxProvider=会话级（sessionStartDate + UserContextProvider(claudemdEngine) +
     * GitStatusProvider）、defaultSysPromptAssemble=default 组装（SystemPromptAssembler 挂会话级
     * systemPromptSectionCache）、customSystemPrompt=state.systemPrompt()、
     * appendSystemPrompt=state.appendSystemPrompt()、useGlobalCacheScope=共享 GlobalCacheScope 单实现
     * （configSupplier 经 streamCompactSummary 同源求值）。
     *
     * <p><b>best-effort（AC2）</b>: 会话未注册 AgentState（无主会话）→ 原料缺 → 调用方
     * buildCacheSafeParamsForPartial 返回 null → 流式 fallback（不阻断压缩，不抛错）；降级路径有日志。
     *
     * @param cc        待组装上下文（四原料 + gate 槽位）
     * @param sessionId 会话 ID（SessionAgentStateRegistry 解析）
     */
    private void assembleForkCacheSharingMaterials(CompactConversationContext cc, String sessionId) {
        // [session-id-short] sessionId 已 short 直键 registry（不再 parseSessionUuid）
        AgentState state = sessionAgentStateRegistry == null ? null
            : sessionAgentStateRegistry.get(sessionId);
        if (state == null) {
            log.warn("[PartialCompact] 会话未注册 AgentState: sessionId={}（LlmAgentLoop 主会话入口才注册，"
                    + "LlmAgentLoop.java:1543）→ fork 缓存共享原料缺 → 走流式 fallback（不阻断压缩）",
                sessionId);
            return;
        }
        ToolUseContext tuc = state.currentToolUseContext();
        cc.setToolUseContext(tuc);
        cc.setSysPromptCtxProvider(buildPartialSystemPromptCtxProvider(state));
        cc.setDefaultSysPromptAssemble(buildPartialDefaultSysPromptAssemble(state, tuc));
        cc.setCustomSystemPrompt(state.systemPrompt());
        cc.setAppendSystemPrompt(state.appendSystemPrompt());
        // firstParty gate（单实现 GlobalCacheScope · 对齐 CC shouldUseGlobalCacheScope betas.ts:227-233）
        Supplier<ProviderConfig> configSupplier =
            streamCompactSummary != null ? streamCompactSummary.configSupplier() : null;
        boolean useGlobalCacheScope = GlobalCacheScope.shouldUseGlobalCacheScope(
            configSupplier == null ? null : configSupplier.get());
        cc.setUseGlobalCacheScope(useGlobalCacheScope);
        log.info("[PartialCompact] 会话 AgentState 组装链原料已注入: sessionId={} tuc={} custom={} append={} "
                + "gate={}（fork 缓存共享待 summarize 前 save）",
            sessionId, tuc != null, state.systemPrompt() != null, state.appendSystemPrompt() != null,
            useGlobalCacheScope);
    }

    /**
     * partial 会话级 system/user 上下文提供者 · 对齐 R1 manual
     * ToolRegistrationConfig.buildManualSystemPromptCtxProvider:1394-1401 + LlmAgentLoop:2184-2188
     * （sessionStartDate + UserContextProvider(claudemdEngine) + GitStatusProvider）。
     * CC original: {@code getUserContext()}/{@code getSystemContext()}（compact.ts:277-281）。
     *
     * <p><b>生命周期（RES-C2 契约）</b>: 构造即向 {@code SystemPromptInjection.CACHE_CLEAR_HOOKS}
     * 注册缓存清理回调；调用方（partialCompact）在 finally 中 {@code close()} 注销，register/unregister
     * 成对（防静态表随 partial 次数有界累积）。
     */
    private SystemPromptContextProvider buildPartialSystemPromptCtxProvider(AgentState state) {
        return new SystemPromptContextProvider(
            state.sessionStartDate(),
            new UserContextProvider(claudemdEngine),
            new GitStatusProvider());
    }

    /**
     * partial default system prompt 惰性组装 · 对齐 R1 manual
     * ToolRegistrationConfig.buildManualDefaultSysPromptAssemble:1421-1446 + LlmAgentLoop
     * buildSystemPromptAssemblyInput:2069-2107 输入组装（enabledTools 从 per-turn TUC 派生、
     * skillCommands 从 SkillCatalog 派生），SystemPromptAssembler 挂会话级 systemPromptSectionCache。
     * CC original: {@code getSystemPrompt(tools, model, dirs, mcpClients)}（compact.ts:261-263）。
     *
     * <p><b>best-effort（已知偏差登记）</b>: REST 线程无 params.modelName()/memoryStorage，
     * model/language/memoryLoader/outputStyleConfig/mcpClients 传 null/空（对齐 Java 主循环 3P 默认）
     * → default 组装产物与主循环在 intro model 名 / memory section 可能差字节 → fork cache 前缀
     * 轻微偏移（缓存未命中但功能正确，不阻断压缩）。custom system prompt 非空时本 Supplier 不被调用
     * （I-13 短路）。
     *
     * @param state 会话状态（systemPromptSectionCache）
     * @param tuc   per-turn ToolUseContext（enabledTools 源，CC compact.ts:285 context）
     * @return default 组装惰性入口（custom 短路时不触发）
     */
    private Supplier<SystemPrompt> buildPartialDefaultSysPromptAssemble(AgentState state, ToolUseContext tuc) {
        final Set<String> enabledTools = (tuc != null && tuc.availableTools() != null)
            ? tuc.availableTools().stream().map(Tool::name).collect(Collectors.toSet())
            : Set.of();
        final List<String> skillCommands;
        if (skillCatalog != null && skillCatalog.getModelInvocableCommands() != null) {
            skillCommands = skillCatalog.getModelInvocableCommands().stream()
                .map(Command::getName)
                .collect(Collectors.toList());
        } else {
            skillCommands = List.of();
        }
        final SystemPromptAssembler assembler = new SystemPromptAssembler(state.systemPromptSectionCache());
        return () -> assembler.assemble(new SystemPromptAssemblyInput(
            enabledTools,
            null,                 // model（REST 线程无 params.modelName() · best-effort）
            List.of(),            // additionalWorkingDirs（Java 主循环单工作目录）
            List.of(),            // mcpClients（Java loop 无 McpClientInfo 通道）
            null,                 // outputStyleConfig（Java 无输出风格配置注入）
            skillCommands,
            null,                 // language（Java 无语言设置通道）
            null,                 // memoryLoader（REST 线程无 memoryStorage 通道 · best-effort）
            false,                // tokenBudgetEnabled（REST 线程无 TOKEN_BUDGET flag 通道 · 对齐 CC prompts.ts:538 关时恒不注册）
            state.sessionId()));  // [cwd-session 2026-08-25 修复] env_info_simple 会话 cwd（显式传 sessionId，绕 MDC）
    }
}

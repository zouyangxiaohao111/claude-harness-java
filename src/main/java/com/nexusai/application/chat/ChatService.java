package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.AgentEvent;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.TaskBudget;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.application.agent.attachment.MediaLimitConstants;
import com.nexusai.application.agent.attachment.MediaLimitGuard;
import com.nexusai.application.agent.attachment.PdfAttachmentStore;
import com.nexusai.application.agent.tool.impl.AskUserQuestionTool;
import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.eventbus.ws.*;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.AttachmentRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.AttachmentRecord;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Chat 业务核心 · 调度 LLM 流、持久化消息、推送 STOMP 事件。
 *
 * <p><b>日志约定（人 + AI 可读）</b>：
 * <ul>
 *   <li>每条日志自动带 {@code [s=sessionId] [r=userMessageId]} 前缀（logback MDC）</li>
 *   <li>每条 STOMP 推送前必打 INFO：{@code STOMP → type=... summary}</li>
 *   <li>user message / assistant message / tool result 都有单独 INFO 行</li>
 *   <li>出错用 ERROR/WARN，便于 AI 按 level 过滤</li>
 * </ul>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** [align-cc] 标题 JSON 解析（json_schema 输出）· 项目惯例各服务静态 ObjectMapper。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final int HISTORY_LIMIT = 20;
    private static final String DEFAULT_MODEL = "mock-fast";
    private static final int SETTINGS_SINGLETON_ID = 1;

    // [附件双模式 · path 附件校验] 白名单扩展名（可读盘直传类型）· 对齐前端大文件 path 通道（>5MB PDF/媒体/图片）
    private static final Set<String> PATH_ATTACHMENT_ALLOWED_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "xls", "xlsx", "mp4", "mov", "mp3", "wav", "webm",
        "jpg", "jpeg", "png", "gif", "webp");
    // [附件双模式] path 附件单文件大小上限 200MB（Files.size 校验；对齐前端 >5MB 阈值 + 留大余量防读盘拖垮）
    private static final long PATH_ATTACHMENT_MAX_BYTES = 200L * 1024 * 1024;
    // [附件双模式] path 附件扩展名 → mediaType（register 推断用；对齐 AttachmentController.EXTENSION_TO_MIME 子集）
    private static final Map<String, String> PATH_EXT_TO_MEDIA_TYPE = createPathExtToMediaType();

    private static Map<String, String> createPathExtToMediaType() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("pdf", "application/pdf");
        m.put("doc", "application/msword");
        m.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        m.put("xls", "application/vnd.ms-excel");
        m.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        m.put("mp4", "video/mp4");
        m.put("mov", "video/quicktime");
        m.put("webm", "video/webm");
        m.put("mp3", "audio/mpeg");
        m.put("wav", "audio/wav");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("png", "image/png");
        m.put("gif", "image/gif");
        m.put("webp", "image/webp");
        return m;
    }

    // Phase 6·s02.6: REPLAY_CHUNK_SIZE / REPLAY_CHUNK_DELAY_MS 弃用 (真流式由 LlmAgentLoop 推 STOMP)

    @Autowired private SessionMapper sessionMapper;
    @Autowired private MessageMapper messageMapper;
    @Autowired private ModelMapper modelMapper;
    @Autowired private ProviderMapper providerMapper;
    @Autowired private SettingsMapper settingsMapper;
    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private ModelConfigResolver modelConfigResolver;
    @Autowired private ProviderService providerService;
    @Autowired private ToolRegistry toolRegistry;
    // [A1 · attachment-multimodal] 图片附件缓存存储（contentId → base64/mediaType 读回）·
    //   对齐 CC imageStore.ts；图片直发 / 多模态工具路由都从这里读缓存。
    @Autowired private ImageAttachmentStore imageAttachmentStore;
    // [U1 · attachment-multimodal] PDF 附件路径存储（contentId → 磁盘路径）· >5MB 大 PDF
    //   multipart 上传落盘（POST /api/v1/attachments/upload）后经本存储按路径通道解析（CC 路径通道）。
    @Autowired private PdfAttachmentStore pdfAttachmentStore;
    // [queue-first B1] turn 运行中再发消息 → 排队（对齐 CC handlePromptSubmit.ts:313，替代 cancel-first）。
    //   busy 时 enqueue prompt（workload="busy-queued"）+ emitChanged，不 cancel 旧 turn。
    @Autowired private NotificationQueue notificationQueue;
    @Autowired private QueueEventPublisher queueEventPublisher;
    // [mid-turn-align] 用户消息落库 · mid-turn 注入的排队 user 消息轮结束补落库
    //   （createQueuedUserMessage，指定 id = 队列 uuid，DB 顺序 = user → assistant... → queued-user）。
    @Autowired(required = false) private com.nexusai.domain.session.MessageService messageService;
    // [attachments-v2 Step2] 媒体（image/video/audio/file）附件路径存储 · 多类型上传落盘
    //   （POST /api/v1/attachments/upload 放宽后非 PDF 走 media-cache）后按路径通道解析。
    @Autowired private MediaAttachmentStore mediaAttachmentStore;
    // [附件双模式 · 统一附件表 contentId] attachments 表（V64）业务 · path/upload 大文件附件统一注册 →
    //   contentId = attachments 自增 id（全局唯一 + 持久化 DB）。resolveAttachments path 分支注册 + 消费侧
    //   （pdf/media/image contentId）附件表校验/解析用。
    @Autowired private AttachmentService attachmentService;
    // [附件双模式] local-read 开关（nexusai.attachments.local-read）· true = 前后端同机（Tauri 本地桌面）
    //   大文件传本地绝对路径 path（附件 path 分支），false = 远程走 upload。AttachmentRequest.path 在
    //   localRead=true 时才读；false 时 path 分支不激活。
    @Value("${nexusai.attachments.local-read:false}")
    private boolean localRead;
    // [R32-b7b-2 P1-1 修复] Spring prototype scope 注入 — 替代旧 new LlmAgentLoop(),
    //   让 setFileConfigStorage / setRuntimeModelOverride / setStartupModelFlag 在生产路径生效.
    @Autowired private org.springframework.beans.factory.ObjectProvider<LlmAgentLoop> loopProvider;
    @Autowired private ToolCallMapper toolCallMapper;
    // STREAM-P1-FIX: 真实注入 token budget / query config / query deps,
    //   否则 ChatService 构造的 LlmAgentLoop 是无门控的 (CC query.ts 生产路径)
    @Autowired(required = false) private TokenBudgetChecker tokenBudgetChecker;
    // [V-TOK 实施] 会话持久化桥（对齐 tokenBudgetChecker 双点注入模式 · required=false）。
    // 计费纯函数 ModelCostCalculator 无需在此注入 —— cost 折算在 LlmAgentLoop（经
    // AgentLoopContext）累计进 state，本类装配 complete 事件直接读 state 会话累计。
    @Autowired(required = false) private com.nexusai.application.agent.cost.CostTracker costTracker;
    @Autowired(required = false) private QueryConfig queryConfig;
    // FIX-R2-1 + FIX-R12-1: 真实注入 memory / recovery 依赖 (s09/s11)
    //   LlmAgentLoop 对应 setter (setMemoryStorage/
    //   setMemoryPrefetcher/
    //   setMaxTokensHandler/setTransientErrorHandler)
    //   真接通, MAX_OUTPUT_TOKENS/429-529 recovery
    //   + memory 索引加载 + side-query 主链激活.
    //   [IMP-02 D-25] prompt-too-long 恢复已内联主循环（CC query.ts:1086-1183），不再注入独立 handler。
    //   [GR-3] auto 自动压缩器经 LlmAgentLoop @Autowired autoCompactor 注入，此处无需手动接线。
    @Autowired(required = false) private com.nexusai.application.agent.memory.MemoryStorage memoryStorage;
    @Autowired(required = false) private com.nexusai.application.agent.memory.MemoryPrefetcher memoryPrefetcher;
    @Autowired(required = false) private com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;
    @Autowired(required = false) private com.nexusai.application.agent.recovery.MaxTokensHandler maxTokensHandler;
    @Autowired(required = false) private com.nexusai.application.agent.recovery.TransientErrorHandler transientErrorHandler;
    // Phase 4: 注入 ScheduleService — closeSession 路径接通 ScheduleService.cleanupBySession
    //   SESSION-scope 调度任务在 session 关闭时同步清理 (对齐 CC cronScheduler.ts:329 removeSessionCronTasks)
    @Autowired(required = false) private ScheduleService scheduleService;
    // [IMP2-10 · MISS-2 · OD-13] taskBudget 配置源：nexusai.agent.task-budget.total（tokens），
    //   0 = 未配置 → 回落 RunRequest.DEFAULT_TASK_BUDGET_TOTAL（OD-13 来源链: 请求参数→配置→默认值；
    //   ChatService 请求体 SendMessageRequest 无 taskBudget 字段，仅配置/默认值两级）。
    @Value("${nexusai.agent.task-budget.total:0}")
    private int taskBudgetTotalConfigured = 0;

    // [WF-4] resume 恢复 worktree cwd：复用 ToolRegistrationConfig 的 sessionProjectRootResolver
    //   （sessionId → session.mainProjectId → project.path）解析会话 projectRoot，与
    //   EnterWorktreeTool.persistWorktreeState 写 transcript 用同一 workspaceDir。
    @Autowired(required = false) private java.util.function.Function<String, String> sessionProjectRootResolver;
    // [S4-FIX] 会话 AgentState 注册表 —— cancelSession 接通 loop abort 通道：
    //   cancelSession 先经本注册表取在飞主 AgentState 调 state.cancel()，使 LlmAgentLoop.run() 内
    //   state.cancelled() 轮询（LlmAgentLoop:4660/4682）500ms 内退出；否则仅 task.cancel 在
    //   loop.run() 返回后 :299 检查，已删会话 in-flight turn 仍继续跑并推 STOMP 至已删 topic
    //   （探查 S4 目标击穿）。best-effort，required=false。
    @Autowired(required = false) private SessionAgentStateRegistry sessionAgentStateRegistry;
    // [P5-①/②] SkillRegistry · userInvocable=false 拒绝 + immediate local-jsx busy 优先判定用
    //   （对齐 CC getCommand / findCommand，SkillRegistry.java:1204-1215 三维匹配）。
    @Autowired(required = false) private SkillRegistry skillRegistry;
    // [P5-②] UserInputDispatcher · immediate local-jsx 命令立即执行（busy 优先/空闲直派）·
    //   生产接线点：dispatch() 此前无生产调用方，P5 起由 ChatService/ChatController 调用。
    @Autowired(required = false) private UserInputDispatcher userInputDispatcher;
    // [P1 · slash-align] SlashCommandInterceptor · '/' 开头输入唯一 slash 编排入口
    //   （镜像 CC processSlashCommand 全流程；内部注入 SkillRegistry + UserInputDispatcher）。
    //   required=false 容错直构测试（null → slash 拦截跳过，普通 prompt 不变）。
    @Autowired(required = false) private SlashCommandInterceptor slashInterceptor;

    /** sessionId → 当前 in-progress 任务。 */
    private final ConcurrentHashMap<String, ChatTask> inProgress = new ConcurrentHashMap<>();

    /**
     * [IMP2-10 · MISS-2 · OD-13] 主会话入口 taskBudget 解析。
     *
     * <p>来源链按 OD-13 裁决：请求参数 → 配置 → 默认值。本入口无请求参数通道
     * （SendMessageRequest 无 taskBudget 字段），故为「配置 {@code nexusai.agent.task-budget.total}
     * → 默认值 {@link RunRequest#DEFAULT_TASK_BUDGET_TOTAL}」两级；恒非 null
     * （CC query.ts:197 输入契约仅 {@code {total}}，remaining 由 loop 局部维护）。
     */
    private TaskBudget taskBudgetOf() {
        TaskBudget budget = RunRequest.resolveTaskBudget(null, taskBudgetTotalConfigured);
        if (log.isDebugEnabled()) {
            log.debug("[IMP2-10 taskBudget] 主会话入口注入: source=配置/默认值 total={}", budget.total());
        }
        return budget;
    }

    // ─────────────────────────── 入口: 处理用户消息 ───────────────────────────

    /**
     * 处理一条用户消息（异步）：HTTP 立刻返 202，LLM 流在后台跑。
     * 入口设 MDC（sessionId + reqId），出口 finally 清。
     */
    /**
     * [queue-first B1] turn 运行中再发消息 → 排队（对齐 CC handlePromptSubmit.ts:313，替代 cancel-first）。
     *
     * <p>enqueue {@code mode=prompt, workload="busy-queued", priority=NEXT, uuid=userMessageId}，
     * 不 cancel 旧 turn——对齐 CC busy prompt 默认 {@code priority='next'}：优先在下一个工具边界被
     * 当前轮 mid-turn drain 注入消费（同轮回答）；仅当前轮不再调工具时才留到 turn 结束由
     * CronIdleExecutor 起新轮消费（B3）。
     *
     * @param sessionId     目标会话（short）
     * @param userMessageId DB user 消息 id（前端正式气泡 id 与 DB 一致）
     * @param req           原请求体（取 content；null → 空串）
     */
    public void enqueueBusyPrompt(String sessionId, String userMessageId, SendMessageRequest req) {
        // [prompt-align UP-02] busy-queued 入队 value 对齐 CC：handlePromptSubmit.ts:337
        //   busy 分支 enqueue {value: finalInput.trim(), ...} —— 首尾 trim 再入队。
        //   粘贴展开（expandPastedTextRefs，history.ts:81-108，[Pasted text #N] 占位替换）为 CC 终端特性，
        //   web 前端无该占位输入，不落地（文档化差异）：图片粘贴已走 attachments/RunRequest.attachments
        //   等价通道（对齐 CC pastedContents→buildImageContentBlocks，attachments.ts:1062-1071）。
        //   空 content（全空白→""）仍入队落库（:264 既有注释语义不变；CC input.trim()==='' 早返在
        //   handlePromptSubmit:188，Java 该守卫在 controller 层不重复加）。
        String content = req != null && req.content() != null ? req.content().trim() : "";
        if (log.isInfoEnabled()) {
            log.info("QUEUE busy: session={} turn 运行中 → enqueue prompt（priority=next，不 cancel 旧 turn，"
                    + "优先在下一工具边界被当前轮 mid-turn drain 注入同轮回答；仅当前轮不再调工具时"
                    + "留到 turn 结束由 CronIdleExecutor 起新轮兜底）",
                sessionId);
        }
        // [mid-turn-align] priority=NEXT（对齐 CC 用户输入默认 'next'，messageQueueManager.ts:128-135 +
        //   query.ts:1560 getCommandsByMaxPriority(sleepRan ? 'later' : 'next')）：NEXT 使 busy-queued
        //   能进 drainForQuery 快照（sleepRan=false 绝大多数工具边界 threshold=NEXT），在下一工具边界
        //   被当前轮 mid-turn 注入同轮回答；仅当前轮不再调工具时才留到 turn 结束由 CronIdleExecutor
        //   起新轮兜底消费（mainThreadConsumable 不看 priority，兜底不受影响）。
        // [OD-D5] busy-queued 携图：从 req.attachments() 提取 image 类（≤5MB base64 直传项）→
        //   QueueItem.attachments 携带。不预写 pendingPromptImages（防共享桶错配 + 端后 doRun
        //   双注册；reflector MAJOR-2/5）——drain 消费点逐项 registerRunPromptImages 消费即清。
        //   contentId/path 大图/PDF/media 本期 busy mid-turn 不支撑（端后兜底 doRun 空闲链）。
        //   空图 → 现状纯文本零变化。
        java.util.List<AttachmentRequest> busyImages = busyQueuedImageAttachments(req);
        notificationQueue.enqueue(new NotificationQueue.QueueItem(
            content, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, userMessageId, false, "busy-queued", false, null, sessionId,
            null /* boundProject */, null /* scheduleId */, busyImages));
        if (log.isInfoEnabled()) {
            log.info("QUEUE busy 携附件: session={} uuid={} images={}（OD-D5 busy 图片通道：≤5MB base64 直传项随队列入队，"
                    + "drain 消费点逐项注册；大图/PDF/media 端后兜底）",
                sessionId, userMessageId, busyImages == null ? 0 : busyImages.size());
        }
        queueEventPublisher.emitChanged(sessionId);
    }

    /**
     * [OD-D5] 提取 busy-queued 可携带图片附件（≤5MB base64 image 直传项）。
     *
     * <p>对齐 CC busy enqueue 携 {@code pastedContents}（handlePromptSubmit.ts:340）——CC 只把
     * 「图片粘贴内容」随命令携带，非图片附件（PDF/media/path 大图）不在 busy 内联注入范围。
     * Java 侧过滤条件：
     * <ul>
     *   <li>{@code type=image} 或 {@code mediaType=image/*}（同 {@code LlmAgentLoop.isImageAttachment}）</li>
     *   <li>{@code base64} 非空白（直传内容；contentId/path 通道需空闲 resolveAttachments 补全，
     *       busy mid-turn 不支撑）</li>
     *   <li>{@code base64.length() ≤ 5MB}（Anthropic image block base64 硬限制，apiLimits.ts:19；
     *       超限大图本期走端后兜底 doRun 空闲链，不随 busy 队列内联注入）</li>
     * </ul>
     * 空附件 / 无命中 → 空列表（纯文本行为零变化）。
     *
     * @param req 原请求体（可 null）
     * @return 可携带图片附件列表（恒非 null；无 → emptyList）
     */
    private java.util.List<AttachmentRequest> busyQueuedImageAttachments(SendMessageRequest req) {
        if (req == null || req.attachments() == null || req.attachments().isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<AttachmentRequest> images = new java.util.ArrayList<>();
        for (AttachmentRequest att : req.attachments()) {
            if (att == null) {
                continue;
            }
            boolean imageType = (att.type() != null && "image".equalsIgnoreCase(att.type()))
                || (att.mediaType() != null && att.mediaType().startsWith("image/"));
            if (!imageType) {
                continue;
            }
            String b64 = att.base64();
            if (b64 == null || b64.isBlank()) {
                continue;   // contentId/path 通道 busy mid-turn 不支撑（红线 §五.7）
            }
            if (b64.length() > MediaLimitConstants.API_IMAGE_MAX_BASE64_SIZE) {
                if (log.isDebugEnabled()) {
                    log.debug("QUEUE busy 跳过超限图（>5MB base64 不内联注入，端后 doRun 兜底）: filename={} base64Len={}",
                        att.filename(), b64.length());
                }
                continue;
            }
            images.add(att);
        }
        return images;
    }

    // ─────────────────────────── P5 · 变量 + 扩展对齐 ───────────────────────────

    /**
     * [P5-①/②] 从用户输入解析 slash 命令名（首个空白分隔）· 等价
     * handlePromptSubmit.ts:230-238 / UserInputDispatcher.dispatch:63-65。
     *
     * @param content 用户原始输入（可为 null）
     * @return 命令名（无前导 '/'）；输入非 "/" 开头 / 命令名空白 → null
     */
    private String parseCommandName(String content) {
        if (content == null || !content.trim().startsWith("/")) {
            return null;
        }
        String rest = content.trim().substring(1);
        int space = rest.indexOf(' ');
        String name = space == -1 ? rest : rest.substring(0, space);
        return name.isBlank() ? null : name;
    }

    /**
     * [P5-①] userInvocable=false 拒绝消息 · 对齐 CC processSlashCommand.tsx:526-548。
     *
     * <p>判定链：输入 trim 后以 "/" 开头 → parseCommandName → {@code skillRegistry.findCommand}
     * （三维匹配 name/userFacingName/aliases）→ {@code userInvocable === false} 命中 → 拒绝路径：
     * <ul>
     *   <li>原 user 消息由调用方已落库（空闲：controller createUserMessage；排队：CronIdleExecutor
     *       createQueuedUserMessage）——本方法只处理<b>第二条</b> user 可见消息</li>
     *   <li>持久化 + 推送第二条 user 可见消息，内容精确复刻 CC processSlashCommand.tsx:543：
     *       {@code This skill can only be invoked by Claude, not directly by users. Ask Claude to use the
     *       "{commandName}" skill for you.}</li>
     *   <li>推 status=idle（不启动 LlmAgentLoop，CC shouldQuery:false 等价）</li>
     * </ul>
     * 未命中 / userInvocable!=false / skillRegistry 未注入 → 返回 false（调用方回落正常 LLM 路径）。
     *
     * <p><b>位置约束（风险 §1）</b>：只能放在用户输入入口（processUserMessage / CronIdleExecutor 消费），
     * 绝不放 SkillToolImpl —— SkillTool 是模型经工具主动调用，CC SkillTool 不查 userInvocable。
     *
     * @param sessionId    目标会话（short）
     * @param content      用户原始输入（可为 null）
     * @param userMessageId 该轮 flow userMessageId（第二条拒绝消息归属同一 flow）
     * @param wsTemplate   STOMP 模板（null → 仅落库不推送，复用 sendAndLog 语义）
     * @return true = 已拒绝（调用方应 return 终结，不起 LlmAgentLoop）；false = 正常路径
     */
    public boolean rejectNonUserInvocable(String sessionId, String content, String userMessageId,
                                          SimpMessagingTemplate wsTemplate) {
        if (skillRegistry == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        String commandName = parseCommandName(content);
        if (commandName == null) {
            return false;
        }
        Command cmd;
        try {
            cmd = skillRegistry.findCommand(commandName);
        } catch (Exception e) {
            log.warn("rejectNonUserInvocable: findCommand 抛异常，回落正常路径: session={} command=/{} err={}",
                sessionId, commandName, e.getMessage());
            return false;
        }
        if (cmd == null || !Boolean.FALSE.equals(cmd.getUserInvocable())) {
            return false;
        }
        // ── 拒绝路径（对齐 CC processSlashCommand.tsx:526-548 userInvocable===false 分支）──
        String rejectionText = "This skill can only be invoked by Claude, not directly by users. "
            + "Ask Claude to use the \"" + commandName + "\" skill for you.";
        if (log.isInfoEnabled()) {
            log.info("SKILL REJECT: userInvocable=false 拒绝用户直接调用 command=/{} session={} "
                    + "（CC processSlashCommand.tsx:526-548，shouldQuery=false 不启动 LlmAgentLoop）",
                commandName, sessionId);
        }
        // 持久化第二条 user 可见消息（CC createUserMessage 同样落 transcript；刷新不丢）
        String rejectionId = generateId("msg");
        try {
            if (messageService != null) {
                MessageCreatedResponse resp = messageService.createQueuedUserMessage(sessionId, rejectionId, rejectionText);
                rejectionId = resp != null && resp.userMessageId() != null ? resp.userMessageId() : rejectionId;
            }
        } catch (Exception e) {
            log.warn("rejectNonUserInvocable: 拒绝消息落库失败（仅推送）: session={} err={}",
                sessionId, e.getMessage());
        }
        sendAndLog(wsTemplate, streamTopic(sessionId),
            new MessageUserEvent(sessionId, userMessageId, rejectionId, rejectionText, false),
            "message.user id=" + abbreviate(rejectionId, 16) + " (userInvocable=false 拒绝)");
        sendAndLog(wsTemplate, streamTopic(sessionId),
            SessionStatusEvent.of(sessionId, userMessageId, "idle"),
            "status=idle (userInvocable=false 拒绝)");
        return true;
    }

    /**
     * [P5-②] immediate local-jsx 命令判定 · 对齐 CC handlePromptSubmit.ts:239-252
     * （{@code cmd.immediate && isCommandEnabled(cmd) && (name|aliases|userFacingName) 命中}）。
     *
     * <p>仅 {@code type=='local-jsx' && immediate==true && isCommandEnabled()} → true；其余
     * （含未命中 / prompt 型 / 非 immediate）→ false（保持现状进 LLM/排队）。
     *
     * @param content 用户原始输入（可为 null）
     * @return true = immediate local-jsx 命令（应 dispatch 不走模型/队列）
     */
    public boolean isImmediateLocalJsxCommand(String content) {
        if (skillRegistry == null) {
            return false;
        }
        String commandName = parseCommandName(content);
        if (commandName == null) {
            return false;
        }
        Command cmd;
        try {
            cmd = skillRegistry.findCommand(commandName);
        } catch (Exception e) {
            log.warn("isImmediateLocalJsxCommand: findCommand 抛异常，按非 immediate 处理: command=/{} err={}",
                commandName, e.getMessage());
            return false;
        }
        return cmd != null
            && Boolean.TRUE.equals(cmd.getImmediate())
            && cmd.isCommandEnabled()
            && "local-jsx".equals(cmd.getType());
    }

    /**
     * [P5-②] immediate local-jsx 命令立即执行 · 对齐 CC handlePromptSubmit.ts:239-252
     * （queryGuard.isActive 优先语义 → 直接 load+call，不 enqueue）。
     *
     * <p>用户输入入口（ChatController.send busy 分支 / ChatService.processUserMessage busy 兜底 +
     * 空闲路径）接线 UserInputDispatcher.dispatch —— 补上 dispatch() 生产零调用方缺口。
     * <b>未注册命名 handler 的 immediate 命令 → log.warn + 返回 false</b>（调用方回落原 busy
     * 排队 / 正常 LLM，fail loud 不静默吞）。
     *
     * @param sessionId      目标会话（short）
     * @param userMessageId  该轮 flow userMessageId（busy 路径推送 message.user 用；null → 生成）
     * @param content        用户原始输入（可为 null）
     * @param persistAndPush true = busy 路径（controller 未落库未推送 → 本方法落库 + 推 message.user，
     *                       前端可见命令执行；CC busy immediate 仅通知不落 transcript，web 端显式推送补齐）;
     *                       false = 空闲路径（controller 已 createUserMessage 落库 → 仅 dispatch，不重复推送）
     * @param wsTemplate     STOMP 模板（persistAndPush=true 且非 null 时推送）
     * @return true = 已 dispatch；false = 无命名 handler / dispatcher 未注入（调用方回落）
     */
    public boolean dispatchImmediateLocalJsx(String sessionId, String userMessageId, String content,
                                             boolean persistAndPush, SimpMessagingTemplate wsTemplate) {
        if (userInputDispatcher == null) {
            log.warn("IMMEDIATE local-jsx 回落: UserInputDispatcher 未注入 session={} content={}",
                sessionId, abbreviate(content, 80));
            return false;
        }
        String commandName = parseCommandName(content);
        if (commandName == null) {
            return false;
        }
        if (!userInputDispatcher.hasSlashCommandHandler(commandName)) {
            log.warn("IMMEDIATE local-jsx 命令 '/{}' 未注册命名 handler，回落原路径（fail loud 不静默吞）: session={}",
                commandName, sessionId);
            return false;
        }
        try {
            userInputDispatcher.dispatch(content);
            if (log.isInfoEnabled()) {
                log.info("IMMEDIATE local-jsx 命令立即执行: session={} command=/{} "
                        + "（CC handlePromptSubmit.ts:239-252 busy/空闲优先，不经模型/队列）",
                    sessionId, commandName);
            }
        } catch (Exception e) {
            log.warn("IMMEDIATE local-jsx 命令执行抛异常: session={} command=/{} err={}",
                sessionId, commandName, e.toString());
            // 执行异常已 fail loud 记日志；仍视为已 dispatch（避免双路径双执行）
            return true;
        }
        if (persistAndPush) {
            String msgId = userMessageId != null ? userMessageId : generateId("msg");
            try {
                if (messageService != null) {
                    MessageCreatedResponse resp = messageService.createQueuedUserMessage(sessionId, msgId, content);
                    msgId = resp != null && resp.userMessageId() != null ? resp.userMessageId() : msgId;
                }
            } catch (Exception e) {
                log.warn("IMMEDIATE local-jsx busy 命令落库失败（仅推送）: session={} err={}",
                    sessionId, e.getMessage());
            }
            sendAndLog(wsTemplate, streamTopic(sessionId),
                new MessageUserEvent(sessionId, msgId, msgId, content, false),
                "message.user id=" + abbreviate(msgId, 16) + " (immediate local-jsx busy 优先)");
        }
        return true;
    }

    /**
     * [mid-turn-align · 实时落库 2026-09-03] 幂等兜底落库 mid-turn 注入的排队 user 消息。
     *
     * <p><b>实时化后定位变化</b>：queued-user 正常由实时落库 appendListener（persistAppendedMessage
     * user 分支）在 append 时点原位落库（4 参单调 ts）；本方法仅服务 appendListener 未执行/漏落的两条
     * 兜底路径（对齐原 replayAndPersist 时代失败路径语义，消息不永久丢失）：
     * <ul>
     *   <li><b>run 正常返回收口</b>（processUserMessage / 无 listener 场景）：已实时落库的
     *       existsById 判重跳过；漏落/未武装的此处补落</li>
     *   <li><b>cancel 分支</b>（run 已成功返回但 task.cancel 置位）：本轮已 mid-turn 注入的
     *       queued-user 不得丢失（否则 DB 无记录、前端气泡悬空）</li>
     * </ul>
     *
     * <p>逐个 try/catch + warn：单条落库失败不打断 message.complete 收口（非致命）。
     *
     * @param state     本轮 AgentState（成功/cancel 分支 state 非 null）
     * @param sessionId 目标会话（short）
     */
    private void persistInjectedQueuedMessages(AgentState state, String sessionId) {
        if (state == null || messageService == null) {
            return;
        }
        List<AgentState.InjectedQueuedMessage> injected = state.injectedQueuedMessages();
        if (injected == null || injected.isEmpty()) {
            return;
        }
        // [reflect-warning] 单调时间戳兜底：同批连续 insert 不在同一毫秒并列（对齐原 replayAndPersist
        //   baseTs.plusNanos(seq) 保序范式；实时 append 已用同款单调 ts）。
        OffsetDateTime baseTs = OffsetDateTime.now();
        long tsSeq = 0;
        for (AgentState.InjectedQueuedMessage inj : injected) {
            try {
                // [mid-turn 加固 2026-08-25] 跳过已原位落库的（实时 appendListener 已落 4 参单调 ts）——
                //   否则重复落库会 insert 冲突（无害）或覆盖 created_at 顺序。仅实时漏落时本处补落。
                if (messageService.existsById(inj.uuid())) {
                    if (log.isDebugEnabled()) {
                        log.debug("ChatService: 排队 user 消息已原位落库，补落库跳过 id={}", inj.uuid());
                    }
                    continue;
                }
                // 空 content 也落库（busy 入队 content 可为空串；空 user 消息仍应在 DB 有记录）。
                // 4 参重载注入单调时间戳：DB created_at 严格递增，顺序可期（对齐原 replayAndPersist :688）。
                // [P0-1 OD-1/OD-3] 6 参重载透传 queuedOrigin（registry 仅 busy-queued → 'busy-queued' 落 V67 列）
                messageService.createQueuedUserMessage(sessionId, inj.uuid(), inj.content(),
                    baseTs.plusNanos(tsSeq++), false, inj.queuedOrigin());
                if (log.isInfoEnabled()) {
                    log.info("ChatService: mid-turn 注入排队 user 消息补落库 session={} id={} chars={}",
                        sessionId, inj.uuid(), inj.content() == null ? 0 : inj.content().length());
                }
            } catch (Exception e) {
                log.warn("ChatService: mid-turn 注入排队 user 消息落库失败（不打断收口）: session={} id={}: {}",
                    sessionId, inj.uuid(), e.getMessage());
            }
        }
    }

    /**
     * [实时落库 2026-09-03] 已注入命令绝不 requeue（对齐 CC messageQueueManager.ts：无 requeue API，
     * 消费即移除；query 崩溃已消费命令不自动回队——transcript/DB 实时留痕不重试）。
     *
     * <p>原 reenqueueInjectedQueuedMessages（error 分支逃生门）已按用户拍板删除：error 分支 run() 抛异常
     * 时，已注入命令若已 append 已由实时落库落 DB（+ 前端气泡留痕）；未注入仍在队列由 CronIdleExecutor
     * 自然消费。遗留引用已清（规则十二：显式失败不静默跳过）。
     */
    @Async("chatExecutor")
    public void processUserMessage(String sessionId,
                                   String userMessageId,
                                   SendMessageRequest req,
                                   SimpMessagingTemplate wsTemplate) {
        RequestContext.set(sessionId, userMessageId);
        try {
            // [V-TOK 实施] 本轮耗时锚点（duration_ms 装配用 · Java 无 API 累计计时，用 turn 墙钟近似）
            long turnStartMs = System.currentTimeMillis();
            log.info("USER → content={}", abbreviate(req == null ? "" : req.content(), 120));

            // [queue-first B1 防御] turn 运行中再发 → 排队（controller 已前置判定，此处兜底其他入口）
            if (LlmAgentLoop.isSessionRunning(sessionId)) {
                // [P5-②] immediate local-jsx 命令 busy 优先：不排队、立即 dispatch（CC
                //   handlePromptSubmit.ts:239-252 queryGuard.isActive 优先语义）。未注册命名 handler
                //   的 immediate 命令 → dispatchImmediateLocalJsx 返回 false（内部 log.warn fail loud）
                //   → 回落原 busy 排队（CC dequeue 后重走 handlePromptSubmit 语义）。
                if (isImmediateLocalJsxCommand(req != null ? req.content() : null)) {
                    dispatchImmediateLocalJsx(sessionId, userMessageId, req != null ? req.content() : null,
                        true, wsTemplate);
                    return;
                }
                enqueueBusyPrompt(sessionId, userMessageId, req);
                return;
            }

            // [P5-①] userInvocable=false 拒绝消息 · 对齐 CC processSlashCommand.tsx:526-548：
            //   输入以 "/" 开头 → findCommand → userInvocable===false → 推第二条 user 可见消息
            //   （"只能由 Claude 调用"）+ status idle，不启动 LlmAgentLoop（CC shouldQuery:false）。
            //   命中即 return（拒绝路径终结）；未命中/userInvocable!=false → 回落正常 LLM 路径。
            if (rejectNonUserInvocable(sessionId, req != null ? req.content() : null, userMessageId, wsTemplate)) {
                return;
            }

            // [P5-②] 空闲 immediate local-jsx 命令 → 直接 dispatch（对齐 CC local-jsx 不走模型）；
            //   controller 已落库 user 消息（persistAndPush=false 不重复推送），仅执行 handler + idle。
            //   非 immediate slash 命令保持现状（进 LLM）。
            if (isImmediateLocalJsxCommand(req != null ? req.content() : null)) {
                dispatchImmediateLocalJsx(sessionId, userMessageId, req != null ? req.content() : null,
                    false, wsTemplate);
                sendAndLog(wsTemplate, streamTopic(sessionId),
                    SessionStatusEvent.of(sessionId, userMessageId, "idle"),
                    "status=idle (immediate local-jsx 已执行，不走模型)");
                return;
            }

            SessionRecord session = sessionMapper.selectOneById(sessionId);
            if (session == null) {
                log.error("Session not found");
                return;
            }

            // [P1 · slash-align] '/' 开头输入 → CC processSlashCommand 边界拦截
            //   （对齐 CC processSlashCommand.tsx:309-921 主流程）。插入点：busy 检查 + session 校验之后、
            //   provider 解析 / loop.run 之前。分派结果：
            //   - shouldQuery=true（prompt 型）→ isMeta 技能内容落库 + 技能级 model 覆盖，回落正常
            //     loop.run 流式（用户气泡已由 controller createUserMessage 落库 + 前端乐观插入）。
            //   - shouldQuery=false（unknown / local / local-jsx / userInvocable=false）→ 非查询型终态收口
            //     （不跑 loop.run）：推结果消息 + status=idle + inProgress.remove，STOMP 链路不漏。
            //   userInvocable=false / immediate local-jsx 已由上方 P5 分支先行拦截（rejectNonUserInvocable /
            //   dispatchImmediateLocalJsx），本拦截器对这两类再命中为防御性兜底（不双处理）。
            String rawContent = req != null ? req.content() : null;
            SlashCommandInterceptor.SlashResolution slash = null;
            if (slashInterceptor != null && rawContent != null && rawContent.startsWith("/")) {
                slash = slashInterceptor.intercept(sessionId, userMessageId, rawContent,
                    streamTopic(sessionId), wsTemplate);
            }
            // [P1 · slash-align] 技能级 model 覆盖 · 对齐 CC processSlashCommand.tsx:917（model: command.model）
            //   在 resolveModelNameForSession 之后应用（modelName 此时已解析）；fallbackModel 在覆盖
            //   之后 resolveEffectiveFallbackModel 执行 → 按覆盖后 model 重算（[Fix-P1] 修正旧注释
            //   「不重算」——代码实为重算，后期待实现.md §68.6 同步修正）。
            String slashModelOverride = (slash != null && slash.handled() && slash.shouldQuery()
                && slash.command() != null && slash.command().getModel() != null
                && !slash.command().getModel().isBlank())
                ? slash.command().getModel() : null;
            if (slash != null && slash.handled() && slash.shouldQuery()
                    && slash.metaMessageContent() != null && !slash.metaMessageContent().isEmpty()) {
                // [P1 · slash-align] prompt 型技能内容 isMeta 落库（UI 隐藏、模型可见、DB 持久化 · 对齐 CC
                //   processSlashCommand.tsx:915-918 createUserMessage({content: skillContent, isMeta:true})）。
                //   resume 按 id 排除当前 user，metaId 为独立 id 会被载入历史 → 模型上下文 =
                //   [历史..., user(isMeta 技能内容), user(/command args)]。CC 对应 [metadata, user(isMeta
                //   技能内容)]（无原始 /command args、无 metadata XML 标签，web 以原始 /command args 气泡
                //   等价，见 后期待实现.md §68.7）。[Fix-P1] SessionResumeDeserializer.spliceNoResponseRequested
                //   已增「末条 user isMeta → 不注入」→ 不产生幽灵 'No response requested.' sentinel（修复
                //   反思报告上下文序列不实）。best-effort：落库失败不阻断主链（对齐 cron isMeta 先例）。
                String slashMetaId = "msg-slash-meta-" + UUID.randomUUID().toString().substring(0, 8);
                try {
                    if (messageService != null) {
                        messageService.createQueuedUserMessage(sessionId, slashMetaId,
                            slash.metaMessageContent(), OffsetDateTime.now(), true);
                        log.info("[slash] prompt 型技能内容 isMeta 落库: session={} id={} chars={}"
                            + "（对齐 CC :915-918）", sessionId, slashMetaId, slash.metaMessageContent().length());
                    }
                } catch (Exception e) {
                    log.warn("[slash] isMeta 技能内容落库失败（best-effort 不阻断主链）: session={} id={}: {}",
                        sessionId, slashMetaId, e.getMessage());
                }
            }
            if (slash != null && slash.handled() && !slash.shouldQuery()) {
                // [P1 · slash-align] 非查询型终态收口（不跑 loop.run）· 对齐 CC shouldQuery=false 语义
                //   （Unknown skill / local-command-stdout / userInvocable-false / fork 占位）。
                //   显式推 结果 MessageUserEvent（新 id msg-slash-xxx）+ status=idle + inProgress.remove，
                //   确保 status 终态不漏、inProgress 不残留（防 cancelSession 幽灵任务）。
                String slashResultId = slash.resultMessageId() != null ? slash.resultMessageId()
                    : "msg-slash-" + UUID.randomUUID().toString().substring(0, 8);
                if (slash.resultText() != null) {
                    try {
                        if (messageService != null) {
                            messageService.createQueuedUserMessage(sessionId, slashResultId,
                                slash.resultText(), OffsetDateTime.now(), false);
                        }
                    } catch (Exception e) {
                        log.warn("[slash] 非查询型结果落库失败（best-effort 仅推送）: session={} id={}: {}",
                            sessionId, slashResultId, e.getMessage());
                    }
                    publishUserMessageEvent(sessionId, slashResultId, slash.resultText(), false,
                        streamTopic(sessionId), wsTemplate);
                }
                sendAndLog(wsTemplate, streamTopic(sessionId),
                    SessionStatusEvent.of(sessionId, userMessageId, "idle"),
                    "status=idle (slash non-querying)");
                inProgress.remove(sessionId);
                log.info("[slash] 非查询型命令终态收口: cmd={} shouldQuery=false",
                    slash.command() != null ? slash.command().getName() : "?");
                return;
            }

            // [IMP-G] G26③ AskUserQuestion previewFormat 会话建立接线：读 CLAUDE_CODE_QUESTION_PREVIEW_FORMAT
            // env，仅当配置值合法（'markdown'|'html'）才 set（CC main.tsx:835-843 合法值分支；Java Web
            // 后端非 CC CLI 客户端，不套用 CLI 默认 markdown 分支）。幂等：静态值与会话无关，多会话
            // 建立时重复覆盖同值。
            AskUserQuestionTool.applyQuestionPreviewFormatFromConfig();

            // [WF-4] resume 恢复：从 transcript 读回 worktree-state → WorktreeCwdTracker
            //   （对齐 CC sessionRestore.ts:332-366 restoreWorktreeForResume）
            restoreWorktreeForResume(sessionId);

            String modelName = resolveModelNameForSession(session, req != null ? req.modelName() : null);
            // [P1 · slash-align] 技能级 model 覆盖（prompt 型命令 command.model，CC :917）
            if (slashModelOverride != null) {
                log.info("[slash] 技能级 model 覆盖: {} → {}（CC processSlashCommand.tsx:917）",
                    modelName, slashModelOverride);
                modelName = slashModelOverride;
            }
            log.info("MODEL → 已解析 model 来源: request/session/settings/default, present={}",
                modelName != null && !modelName.isBlank());
            // [F4] 降级模型：请求体 fallbackModel 优先，空则回落 settings.fallbackModelName（前端设置页可配）
            String fallbackModel = resolveEffectiveFallbackModel(modelName, req != null ? req.fallbackModel() : null);

            // [streamTopic-session-level] 会话级单 topic：消息归属走事件字段，topic 不再编码消息 id
            String streamTopic = streamTopic(sessionId);

            // 1) session.status=thinking
            sendAndLog(wsTemplate, streamTopic,
                SessionStatusEvent.of(sessionId, userMessageId, "thinking"),
                "status=thinking");

            // 2) cancel task 注册（[queue-first B1] 删 previous.cancel —— 对齐 CC 不再打断旧 turn；
            //   inProgress.put 保留，供 cancelSession 定位在飞 turn；用户主动 /cancel 仍走 task.cancel）
            ChatTask task = new ChatTask(sessionId, userMessageId,
                "msg-pending-" + UUID.randomUUID().toString().substring(0, 8));
            inProgress.put(sessionId, task);

            // 3) 解析 provider
            ProviderConfig config;
            String providerType;
            try {
                config = buildConfigForModel(modelName);
                providerType = providerTypeForModel(modelName);
            } catch (Exception e) {
                log.warn("Provider resolve failed → fallback mock: {}", e.toString());
                config = ProviderConfig.empty();
                providerType = "openai_compatible";
            }
            LlmProvider provider = llmProviderFactory.getProvider(config, providerType);
            log.info("PROVIDER → type={} baseUrl={}", provider.type(),
                config == null ? "(mock)" : abbreviate(config.baseUrl(), 60));

            // 4) 调 LlmAgentLoop
            AgentState state = null;
            // [mid-turn-align] loop 实例在 try 前声明：error 分支（run() 抛异常时 state 赋值未完成、恒 null）
            //   经 loop.injectedQueuedMessages() 逃生门重新 enqueue 回队列（见 error 分支）。
            LlmAgentLoop loop = null;
            try {
                // Phase 6·s02.6: 注入 wsTemplate + session + userMessageId,
                //   让 LlmAgentLoop 在 OpenAiSdkProvider 解析 chunk 时**立即推 STOMP** (真流式).
                loop = loopProvider.getObject();
                loop.setStreamContext(wsTemplate, sessionId, userMessageId);
                // STREAM-P1-FIX: 真实注入 token budget / query config
                //   不再是 setXxx 死代码 - 实际进 LlmAgentLoop.run() 的 loop() 内每轮 check
                if (tokenBudgetChecker != null) loop.setTokenBudgetChecker(tokenBudgetChecker);
                if (queryConfig != null) loop.setQueryConfig(queryConfig);
                // FIX-R2-1 + FIX-R12-1: 真实注入 memory / recovery 依赖
                if (memoryStorage != null) loop.setMemoryStorage(memoryStorage);
                if (memoryPrefetcher != null) loop.setMemoryPrefetcher(memoryPrefetcher);
                if (claudemdEngine != null) loop.setClaudemdEngine(claudemdEngine);
                if (maxTokensHandler != null) loop.setMaxTokensHandler(maxTokensHandler);
                if (transientErrorHandler != null) loop.setTransientErrorHandler(transientErrorHandler);
                log.info("AGENT stream wired: budget={} config={} mem={} memPref={} maxTok={} transient={}",
                    tokenBudgetChecker != null, queryConfig != null,
                    memoryStorage != null, memoryPrefetcher != null,
                    maxTokensHandler != null, transientErrorHandler != null);
                String userPrompt = req != null ? req.content() : null;
                if (userPrompt == null || userPrompt.isBlank()) {
                    userPrompt = lastUserContent(loadRecentHistory(sessionId, HISTORY_LIMIT));
                }
                log.info("AGENT start: prompt={}chars tools={}",
                    userPrompt == null ? 0 : userPrompt.length(),
                    toolRegistry == null ? 0 : toolRegistry.all().size());
                // [attachments-v2 Step2] 单次请求附件上限 50（对齐前端契约；防超大请求体）
                if (req != null && req.attachments() != null
                        && req.attachments().size() > MAX_ATTACHMENTS_PER_REQUEST) {
                    throw new ValidationException("一次最多发送 " + MAX_ATTACHMENTS_PER_REQUEST + " 个附件");
                }
                // [A1 · attachment-multimodal] 消费请求附件：contentId → ImageAttachmentStore
                //   读缓存补全 base64/mediaType（直传 base64 原样保留），组装可消费附件列表透传 LlmAgentLoop。
                List<AttachmentRequest> attachments = resolveAttachments(sessionId,
                    req != null ? req.attachments() : null);
                // [A5 · 限额闸门] 媒体限额校验：5MB base64 硬校验（超限压缩，失败拒绝）+ 100 项/请求裁剪（保最新）
                //   对齐 CC apiLimits.ts:19/94 + imageValidation.ts:90-102 + claude.ts:956 stripExcessMediaItems。
                //   校验入口：ChatService 消费附件处（A1 resolveAttachments 补全 base64 后、透传 LlmAgentLoop 前）。
                attachments = MediaLimitGuard.guard(attachments);
                // [附件双模式 · 统一附件表 contentId] 回写 user_attachments：resolveAttachments 已把 path/upload
                //   大文件附件注册附件表并分配 contentId（createUserMessage 落库时 path 附件 contentId 未知 →
                //   当时快照 contentId=null）→ 此处把<b>已解析附件快照（含新 contentId）</b>全量覆盖回写
                //   user_attachments（对齐 updateUserImagePasteIds 回写范式：消息本体已由 controller createUserMessage
                //   落库，本回写仅 UPDATE user_attachments 列）。≤5MB base64 图无 contentId 保持 null（imagePasteIds
                //   链路不变）；url 为出站投影不落库。messageService 为 @Autowired(required=false) 必须判 null。
                if (messageService != null && !attachments.isEmpty()) {
                    try {
                        messageService.updateUserAttachments(userMessageId, userAttachmentSnapshotOf(attachments));
                    } catch (Exception e) {
                        log.warn("[attachments] 回写 user 消息 user_attachments 失败: userMessageId={} err={}",
                            userMessageId, e.toString());
                    }
                }
                // [ER-IMP-02 · R-TOK] 主线程 agentId 传 null 对齐 CC 主线程语义：
                // CC query.ts:1311 checkTokenBudget(budgetTracker!, toolUseContext.agentId, ...)
                // 主线程 toolUseContext.agentId=undefined（query.ts:342 if (!toolUseContext.agentId)
                // 主线程专属 gate）→ 走续跑逻辑；恒传 sessionUuid 会使 checkTokenBudget 首行
                // if (agentId || ...) 命中 → 主线程首迭代 StopDecision → MAX_OUTPUT_TOKENS break。
                // 主会话（agentId==null）注册 gate（LlmAgentLoop:1556 sessionAgentStateRegistry）、
                // tool_use_summary（:3655-3661 gate agentId==null）、skill dedup agentKey
                // （:2482 主线程 agentKey=""）随之激活 —— 均为 CC 对齐休眠路径。
                // [session-id-short] sessionId 已 short 直传 RunRequest.session（不再 parseSessionUuid）
                // [V44] 有效初始权限模式 = per-call ?? 会话 override（session.permission_mode 列）：
                //   per-call（HTTP 请求体 SendMessageRequest.permissionMode）恒胜会话 override；两者共享
                //   CLI 槽（resolver 链第 2 优先级，恒胜 settings 槽）——实现「会话初始化传 permissionModeCli」。
                String perCallPermissionMode = req != null ? req.permissionMode() : null;
                String effectivePermissionMode =
                    resolveEffectivePermissionMode(session, perCallPermissionMode);
                if (log.isInfoEnabled()) {
                    log.info("PERMISSION MODE → 有效初始权限模式: per-call={} session.override={} → effective={}"
                            + "（per-call 恒胜会话 override；null 回落全局 settings/permissions.defaultMode）",
                        perCallPermissionMode, session.getPermissionMode(), effectivePermissionMode);
                }
                // [实时落库 2026-09-03] run 前武装实时落库 SPI：装配了落库能力（messageService != null）才
                //   武装（非 Spring 单测不武装 → loop mock 不消费 enabler，零行为变化）。doRun 历史注入完成、
                //   prePersistedMessageIds 已登记后回调 → armRealTimePersist setAppendListener，其后每条
                //   新 append（=消息完整产出）即实时落 DB（对齐 CC recordTranscript）。传该轮 userMessageId
                //   作 DB user_message_id 归属根（对齐原 replayAndPersist lastUserMessageId 初值语义）。
                if (messageService != null) {
                    loop.setPostHistoryPersistEnabler(state2 ->
                        armRealTimePersist(state2, sessionId, streamTopic, wsTemplate, userMessageId));
                }
                state = loop.run(RunRequest.session(
                    userPrompt, sessionId, null, config, modelName, null,
                    req != null ? req.appendSystemPrompt() : null,   // [RES-SP31] 接线：HTTP 请求体追加指令
                    fallbackModel,                                     // [DEC-RV-02 · FIX-16] per-call 降级模型（请求体 → settings.fallbackModelName → RunRequest.fallbackModel → QueryParams → RetryOptions）
                    effectivePermissionMode,                           // [V44] 有效初始权限模式（per-call ?? 会话 override → RunRequest.permissionModeCli → InitialPermissionModeResolver.Input CLI 槽）
                    req != null && Boolean.TRUE.equals(req.dangerouslySkipPermissions()), // [RV-11 · REV-FIX-2] dangerouslySkip（HTTP 请求体 → RunRequest.dangerouslySkipPermissions）
                    taskBudgetOf(),                                  // [IMP2-10 · MISS-2] taskBudget 生产注入（OD-13: 配置→默认值）
                    req != null ? req.jsonSchema() : null,           // [IMP-HR-08 · OPD-WF6-01-06-?-3] 主循环 structured output jsonSchema 透传（HTTP 请求体 → RunRequest.jsonSchema → LlmAgentLoop 注册 enforcement）
                    attachments));                                   // [A1 · attachment-multimodal] 附件列表透传（image content block / 多模态工具路由）
            } catch (Exception e) {
                log.error("AGENT failed: model={}", modelName, e);
                // [实时落库 2026-09-03] error 分支删除 reenqueueInjectedQueuedMessages（对齐 CC
                //   messageQueueManager.ts：无 requeue API，消费即移除；query 崩溃已消费命令不自动回队）。
                //   已注入命令若已 append 已实时落库留痕（DB + 前端气泡）；未注入仍在队列由 CronIdleExecutor
                //   自然消费——规则十二显式失败：不再静默重放。
                String errorCode = (e instanceof InterruptedException) ? "cancelled" : "llm_error";
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                // [reflect-blocker] 终态事件同源（error 分支）：run() 抛异常 → state 恒 null（赋值未完成），
                //   无法经 state 取末条 assistant id；sessionAgentStateRegistry 持当前轮在飞 state
                //   （LlmAgentLoop:2013-2017 run() 流式开始前注册），currentAssistantMessageId() =
                //   prepareAssistantMessageId() 结果（LlmAgentLoop:4209），正是流式 chunk 事件同源 id →
                //   优先取在飞 id，回落 task 占位（罕见：run() 在注册前即抛的 setup 失败仍幽灵气泡，可接受）。
                String realErrorAssistantId = task.assistantMessageId;
                if (sessionAgentStateRegistry != null) {
                    AgentState inFlight = sessionAgentStateRegistry.get(sessionId);
                    String inFlightId = inFlight != null ? inFlight.currentAssistantMessageId() : null;
                    if (inFlightId != null) {
                        realErrorAssistantId = inFlightId;
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("ChatService: error 分支终态事件 assistantId 同源解析 real={} placeholder={} source={}",
                        realErrorAssistantId, task.assistantMessageId,
                        realErrorAssistantId.equals(task.assistantMessageId) ? "task-placeholder" : "registry-inflight");
                }
                sendAndLog(wsTemplate, streamTopic,
                    MessageErrorEvent.of(sessionId, effectiveEventUserMessageId(state, userMessageId), realErrorAssistantId,
                        errorCode, errorMsg),
                    "error code=" + errorCode + " msg=" + abbreviate(errorMsg, 200));
                sendAndLog(wsTemplate, streamTopic,
                    SessionStatusEvent.of(sessionId, effectiveEventUserMessageId(state, userMessageId), "idle"),
                    "status=idle (after error)");
                inProgress.remove(sessionId, task);
                return;
            }

            // [reflect-blocker] 终态事件同源改造：流式 chunk/tool_call 携带真实 turnAssistantId 建气泡
            //   （LlmAgentLoop:4741 chunk / :4841 tool_call），message.complete/cancelled/replay_error
            //   必须同源——否则前端 assistantGroups.get(占位)=undefined 静默 no-op（气泡永不 locked、
            //   streaming 光标不停）、message.error 走 assistantGroupFor(占位) 建幽灵气泡。
            //   ChatTask.assistantMessageId 是 final 占位（构造 'msg-pending-xxx' 后不可改，ChatTask:1568
            //   final 字段）→ 不能用 task 变异，改用局部变量：末条 assistant 真实 id（=turnAssistantId，
            //   lastAssistantMessage 末向前扫描，:869）优先，无 assistant（取消过早未产出）回落占位。
            //   与 replayAndPersist final 落库同源契约（LlmAgentLoop:6055-6058 三处同源）。
            ChatMessageDto lastAsst = lastAssistantMessage(state);
            String realAssistantId = (lastAsst != null && lastAsst.id() != null)
                ? lastAsst.id() : task.assistantMessageId;
            if (log.isDebugEnabled()) {
                log.debug("ChatService: 终态事件 assistantId 同源解析 real={} placeholder={} source={}",
                    realAssistantId, task.assistantMessageId,
                    (lastAsst != null && lastAsst.id() != null) ? "lastAssistantMessage" : "task-placeholder");
            }

            // [实时落库 2026-09-03] run() 返回后收口：解除 appendListener（防泄漏/下轮误触发）+ queued-user
            //   幂等兜底（listener 单条漏落或 mock loop 未武装时，existsById 判重补落）。原 replayAndPersist
            //   批量已删——消息已实时落库，此处不再遍历 state.messages()。
            if (state != null) {
                state.clearAppendListener();
                persistInjectedQueuedMessages(state, sessionId);
            }

            // 5) cancel 检查
            if (task.cancel.get()) {
                log.info("AGENT cancelled by user");
                // [mid-turn-align] cancel 分支 queued-user 已由上方收口 persistInjectedQueuedMessages
                //   幂等补落（run 返回后即执行，先于本 cancel 检查），此处不再重复调用。
                sendAndLog(wsTemplate, streamTopic,
                    MessageCancelledEvent.of(sessionId, effectiveEventUserMessageId(state, userMessageId), realAssistantId),
                    "cancelled");
                sendAndLog(wsTemplate, streamTopic,
                    SessionStatusEvent.of(sessionId, effectiveEventUserMessageId(state, userMessageId), "idle"),
                    "status=idle (cancelled)");
                inProgress.remove(sessionId, task);
                return;
            }

            log.info("AGENT done: turns={} exit={} totalChars={}",
                state.turnCount(), state.exitReason(),
                state.messages().stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum());

            // 6) 消息已实时落库（appendListener → persistAppendedMessage，run 全程逐条落），无 run 末批量。
            //   原 replayAndPersist 已删：本处仅收口（clearAppendListener + persistInjectedQueuedMessages
            //   幂等兜底）已在上方执行。queued-user 原位顺序 = append 即落（对齐 CC messages.ts:3782 消费时落库）。

            // 7) message.complete · [V-TOK 实施] 照抄 CC result 事件结构（真实 usage/cost/上下文，
            //    替代 mock 42）——usage = 末条 assistant 的 provider usage；total_cost_usd/modelUsage =
            //    state 会话累计（LlmAgentLoop 每轮累加）；上下文三字段常驻每轮推（对齐 CC StatusLine）。
            //   [cron-complete] 装配提取到 publishCompleteEvent（cron 触发链路复用，单点防漂移）
            String finalContent = state.lastAssistant() == null ? "" : state.lastAssistant();
            publishCompleteEvent(sessionId, userMessageId, state, streamTopic, wsTemplate,
                turnStartMs, realAssistantId);

            // 7.1) [V-TOK 实施] 会话累计持久化 save（写 sessions 表 total_cost_yuan + model_usage_json，
            //     跨 turn 权威；restore 在 LlmAgentLoop 会话启动时做 —— save/restore 分属两处各一）。
            if (costTracker != null) {
                costTracker.saveCurrentSessionCosts(sessionId, state);
            }

            // 8) status=idle
            sendAndLog(wsTemplate, streamTopic,
                SessionStatusEvent.of(sessionId, effectiveEventUserMessageId(state, userMessageId), "idle"),
                "status=idle");

            inProgress.remove(sessionId, task);

            // 9) 标题生成（首条）
            maybeGenerateTitle(session, userMessageId, finalContent, wsTemplate);

            log.info("DONE: turns={} exit={}", state.turnCount(), state.exitReason());
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 本轮 complete 事件推送（会话级单 topic）· [cron-complete 修复] public 化供 cron 触发链路
     * （CronIdleExecutor.runOneAgentLoop）复用 —— 对齐正常 turn 收口（前端 finalize cron 块，
     * 根治 cron 块无 complete 残留 streams 被后续用户 turn 混收口倒挂）。
     *
     * <p>装配逻辑原为 {@code processUserMessage} 内联（照抄 CC result 事件结构，V-TOK 实施），
     * 提取单点防两处漂移。字段契约见 {@link MessageCompleteEvent}。
     *
     * @param realAssistantId 末条 assistant 真实 id（=turnAssistantId，前端块 id 同源）；null 回落
     *                        {@code lastAssistantMessage(state).id()}（cron 场景无 task 占位）
     */
    public void publishCompleteEvent(String sessionId, String userMessageId,
                                     com.nexusai.application.agent.AgentState state,
                                     String streamTopic,
                                     org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate,
                                     long turnStartMs, String realAssistantId) {
        String finalContent = state.lastAssistant() == null ? "" : state.lastAssistant();
        // [bugfix] 前端收口消息思考丢失：reasoning 由 null 改为末条 assistant 的真实 reasoning
        //   （对齐 replayAndPersist finalReasoning 捕获语义，:432 finalReasoning = m.reasoning()）
        String finalReasoning = lastAssistantReasoning(state);
        // [usage-push] complete.usage 口径从末条单条改为本轮累计 totalUsage（对齐 CC result.usage =
        //   query 级累计 totalUsage，QueryEngine.ts:790-816/:861）——run 级累计由 LlmAgentLoop 3 处
        //   withUsage append 后 publishMessageUsage → state.accumulateRunUsage 累加。无任何 assistant
        //   消息带 usage 时 runUsage() = 全零哨兵（AgentState 每 run 新建自动清零），仍发 usage 对象
        //   （与 MessageUsageDto.from 恒非 null + @JsonInclude(NON_NULL) 形状一致）。
        AgentUsage completeUsage = state.runUsage();
        // 块 3 投影：真实 input/output tokens（本轮累计；runUsage() 恒非 null → 恒发）
        Integer completeInputTokens = (int) completeUsage.inputTokens();
        Integer completeOutputTokens = (int) completeUsage.outputTokens();
        // 上下文快照（对齐 CC context.ts:118-144）：单点化收口到 ContextUsageCalculator.snapshot
        //   —— window = 模型 max_context_tokens（回落 1M）+ used 协议分派 + percentLeft clamp 0。
        //   [usage-push] 与 message.usage 事件（LlmAgentLoop.publishMessageUsage）共用同一快照单点，
        //   防 ChatService/MessageService 式公式漂移重演（见 ContextUsageCalculator 类 javadoc）。
        String completeModel = state.currentModel();
        ContextUsageCalculator.Snapshot completeSnapshot =
            ContextUsageCalculator.snapshot(modelMapper, providerMapper, completeModel, completeUsage);
        long completeContextWindow = completeSnapshot.contextWindow();
        long completeContextUsed = completeSnapshot.contextTokensUsed();
        Integer completePercentLeft = completeSnapshot.percentLeft();
        long completeDurationMs = System.currentTimeMillis() - turnStartMs;
        // [B7-R9] decode_ms 装配：从末条 assistant 消息读 LlmAgentLoop firstTokenMs 打点结果
        Long completeDecodeMs = lastAssistantDecodeMs(state);
        // [cron-complete] realAssistantId null 回落末条 assistant 真实 id（cron 场景无 task 占位）
        String resolvedAssistantId = realAssistantId;
        if (resolvedAssistantId == null) {
            ChatMessageDto lastAsst = lastAssistantMessage(state);
            if (lastAsst != null && lastAsst.id() != null) resolvedAssistantId = lastAsst.id();
        }
        sendAndLog(wsTemplate, streamTopic,
            // [merge] 保留 effectiveEventUserMessageId（排队 flow userMessageId 双通道一致）+ V-TOK 真实 usage 装配
            new MessageCompleteEvent(sessionId, effectiveEventUserMessageId(state, userMessageId), resolvedAssistantId,
                finalContent, finalReasoning, state.finishReason(),
                completeInputTokens, completeOutputTokens,
                lastAssistantReasoningDurationMs(state),
                MessageUsageDto.from(completeUsage, completeDecodeMs),
                state.sessionCostYuan(),
                state.sessionModelUsage(),
                completeDurationMs,
                state.turnCount(),
                completeContextWindow, completeContextUsed, completePercentLeft),
            "complete finishReason=" + state.finishReason()
                + " content=" + abbreviate(finalContent, 80)
                + " reasoningLen=" + (finalReasoning == null ? 0 : finalReasoning.length()));
    }

    /**
     * 后台落库 user 消息推送 · [cron-complete 修复] cron 触发链路（CronIdleExecutor.executeQueuedInput）
     * 落库 user 消息后调用。
     *
     * <p>对齐前端 {@code PushedUserMessageEvent} 契约（nexusai types.ts:822-827）：isMeta=true 前端
     * appendMetaUser 占位不显示，但建立 flow 锚点。WHY（cron 消息顺序倒挂修复）：cron user prompt 只
     * 落库不推前端 → 前端 messages 缺锚点 → 该轮 assistant 流式块按 flowKey=userMessageId 找不到
     * 锚点插入末尾 → 顺序倒挂；推 message.user 建立锚点后，complete 收口时 cron 块插入正确位置。
     *
     * @param userMessageId cron user 消息 id（=落库后真实 id，uuid=null 时 createQueuedUserMessage 兜底生成）
     */
    public void publishUserMessageEvent(String sessionId, String userMessageId, String content,
                                        boolean isMeta, String streamTopic,
                                        org.springframework.messaging.simp.SimpMessagingTemplate wsTemplate) {
        sendAndLog(wsTemplate, streamTopic,
            new MessageUserEvent(sessionId, userMessageId, userMessageId, content, isMeta),
            "message.user id=" + abbreviate(userMessageId, 16)
                + " isMeta=" + isMeta
                + " content=" + abbreviate(content, 80));
    }

    /**
     * [usermessageid 双通道 2026-08-25] 终态事件（complete/cancel/error）userMessageId 用消息链推导：
     * {@code state.lastUserMessageId() ?? turn userMessageId}，与 chunk 每轮推导同源（对齐 CC
     * parentUuid 链）——排队注入后 = 排队 uuid，否则 = 本轮 user。state 为 null（run 抛异常的 error
     * 分支）回落 turn userMessageId。调用点跨 try/catch 及 try 外分支，故用方法而非 try 内局部变量。
     */
    private String effectiveEventUserMessageId(com.nexusai.application.agent.AgentState state, String userMessageId) {
        if (state != null) {
            String last = state.lastUserMessageId();
            if (last != null) {
                return last;
            }
        }
        return userMessageId;
    }

    // ─────────────────────────── STOMP 推送统一入口 ───────────────────────────

    /**
     * 统一 STOMP 推送 + 日志。summary 是给人 + AI 看的简短描述。
     *
     * <p><b>wsTemplate null 守卫</b>（2026-08-25）：非 Spring 单测或 headless 未注入 wsTemplate 时跳过
     * 推送（实时落库仅写 DB），不 NPE。生产主链路 wsTemplate 恒非 null，行为不变。
     */
    private void sendAndLog(SimpMessagingTemplate ws, String topic,
                            StreamEvent evt, String summary) {
        if (ws == null) {
            if (log.isDebugEnabled()) {
                log.debug("wsTemplate 未注入，跳过 STOMP 推送（仅落库）: type={} {}", evt.getType(), summary);
            }
            return;
        }
        ws.convertAndSend(topic, evt);
        log.info("STOMP → type={} {}", evt.getType(), summary);
    }

    // ─────────────────────────── runStream 入口（CC query.ts AsyncGenerator 语义） ───────────────────────────
    /**
     * 流式入口（演示用）· 对齐 CC query.ts:219 {@code async function* query(params)}.
     *
     * <p>与 {@link #processUserMessage} 主流程平行：{@code processUserMessage} 用
     * {@link LlmAgentLoop#run(RunRequest)} 拿终态 AgentState（实时落库由 appendListener 完成）；
     * 本方法用 {@link LlmAgentLoop#runStream(RunRequest)} 拿事件流
     * ({@link AgentEvent.TurnStarted} / {@link AgentEvent.TurnCompleted} /
     * {@link AgentEvent.Terminal}), 模拟 CC AsyncGenerator 消费.
     *
     * <p>调用方（未来）/ 测试可走 {@link LlmAgentLoop#runStream(RunRequest)} 路径观察
     * 4 个生命周期事件, 现有生产路径仍走 {@code loop.run()}.
     *
     * @param loop 已注入的 LlmAgentLoop 实例
     * @param params RunRequest
     * @return 事件列表（按时间顺序, 最后一条必为 AgentEvent.Terminal）
     */
    List<AgentEvent> runStream(LlmAgentLoop loop, RunRequest params) {
        if (loop == null || params == null) {
            log.warn("runStream 入参为空: loop={} params={}", loop, params);
            return Collections.emptyList();
        }
        log.info("runStream 调用: sessionId={}", params.sessionId());
        try {
            // 核心：把 loop.run() 改用 loop.runStream() —— 对齐 CC AsyncGenerator
            return loop.runStream(params).collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.error("runStream 失败: {}", e.toString(), e);
            return Collections.emptyList();
        }
    }

    // ─────────────────────────── 实时落库（append 即落 · 对齐 CC recordTranscript）───────────────────────────

    /**
     * [实时落库 2026-09-03] 单轮实时落库上下文（per-run）。
     *
     * <p>持会话级串行锁 + 单调时间戳 + 消息链归属（lastUserMessageId）。listener 每 append 一条消息即同步
     * 落库，故时间戳须 {@code baseTs.plusNanos(seq)} 单调保序（工具并行 append → synchronized 串行落库），
     * 对齐原 replayAndPersist 循环内保序范式（同 MessageService.replaceSessionMessages base.plusNanos(i)）。
     * lastUserMessageId 初始 = 本轮发起 user 消息 id（调用方经 5 参 armRealTimePersist 传入，对齐原
     * replayAndPersist lastUserMessageId = turn userMessageId）；遇 mid-turn 注入排队 user 时推进。
     */
    private static final class PersistCtx {
        final String sessionId;
        final Object lock = new Object();
        final java.time.OffsetDateTime baseTs;
        final java.util.concurrent.atomic.AtomicLong tsSeq;
        final java.util.concurrent.atomic.AtomicReference<String> lastUserMessageId;

        PersistCtx(String sessionId, String initialUserMessageId) {
            this.sessionId = sessionId;
            this.baseTs = java.time.OffsetDateTime.now();
            this.tsSeq = new java.util.concurrent.atomic.AtomicLong(0);
            this.lastUserMessageId = new java.util.concurrent.atomic.AtomicReference<>(initialUserMessageId);
        }
    }

    /**
     * [实时落库 2026-09-03] 武装 AgentState 逐条 append 监听器（对齐 CC recordTranscript 每条产出即写）。
     *
     * <p>生产调用方（ChatService.processUserMessage / CronIdleExecutor）在 {@code loop.run()} 前经
     * {@code LlmAgentLoop.setPostHistoryPersistEnabler(...)} 注入本方法；doRun 历史注入完成、
     * prePersistedMessageIds 已登记后回调 → 此处 setAppendListener，其后每条新 append（=消息完整产出，
     * DTO 全字段齐：usage/finishReason 已在 append 点定）即实时落 DB。
     *
     * <p><b>listener 体内异常处理</b>：单条落库失败仅 log.warn 不抛出（不打断主循环 / 不丢前端流）；
     * queued-user 若因注册时序漏落，由 processUserMessage 收口 persistInjectedQueuedMessages
     * （existsById 判重）幂等补漏。
     *
     * @param state                本轮 AgentState（doRun 内历史注入后、后续消息 append 前）
     * @param sessionId            目标会话（short）
     * @param streamTopic          会话级 STOMP topic
     * @param wsTemplate           STOMP 模板（null → sendAndLog 跳过推送仅落库，非 Spring 单测）
     * @param initialUserMessageId 本轮发起 user 消息 id（= turn userMessageId，DB user_message_id 归属根，
     *                             对齐原 replayAndPersist 初值）。null → 回落 state.lastUserMessageId()/sessionId
     */
    public void armRealTimePersist(AgentState state, String sessionId, String streamTopic,
                                   SimpMessagingTemplate wsTemplate, String initialUserMessageId) {
        if (state == null) {
            return;
        }
        String init = initialUserMessageId != null
            ? initialUserMessageId
            : (state.lastUserMessageId() != null ? state.lastUserMessageId() : sessionId);
        PersistCtx ctx = new PersistCtx(sessionId, init);
        state.setAppendListener(m -> {
            try {
                persistAppendedMessage(ctx, state, m, streamTopic, wsTemplate);
            } catch (Exception e) {
                log.warn("实时落库失败: session={} id={} role={}: {}", sessionId,
                    m != null ? m.id() : "?", m != null ? m.role() : "?", e.getMessage());
            }
        });
        if (log.isInfoEnabled()) {
            log.info("[实时落库] appendListener 已武装: session={}（对齐 CC recordTranscript 逐条实时写）", sessionId);
        }
    }

    /**
     * [实时落库 2026-09-03] 4 参便捷版：init 由 {@code state.lastUserMessageId()}（历史末条 user）推导，
     * 无则回落 sessionId。供无显式 userMessageId 的场景兜底；生产调用方走 5 参重载（传该轮 userMessageId）。
     */
    public void armRealTimePersist(AgentState state, String sessionId, String streamTopic,
                                   SimpMessagingTemplate wsTemplate) {
        armRealTimePersist(state, sessionId, streamTopic, wsTemplate, null);
    }

    /**
     * [实时落库 2026-09-03] appendListener 单条实时落库（核心）· 由原 replayAndPersist 循环逐条提取。
     *
     * <p>每 append 一条消息即同步落 DB（对齐 CC recordTranscript append-only 实时写，替代原 run 末
     * replayAndPersist 批量遍历 {@code state.messages()}）。分支语义与 replayAndPersist 现状逐字一致：
     * <ul>
     *   <li><b>snip_boundary</b>：判重 selectOneById → insert system 行（subtype/snipMetadata）+ removedUuids
     *       非空推 MessageBoundaryEvent（压缩机制零改动，仅触发时机提前到 append 即落）</li>
     *   <li><b>user</b>：imagePasteIds 回写 lastUserMessageId 行；mid-turn 注入 queued-user 原位落库
     *       （4 参单调 ts + 推进 lastUserMessageId）；普通 user 不重复 insert（controller 已落）</li>
     *   <li><b>assistant</b>：toolCalls 非空 → insert finishReason=tool_calls + 逐条 ToolCallRecord
     *       （不落 usage 列，对齐原循环）；纯文本 → insert finishReason = subtype==max_tokens ? max_tokens
     *       : state.finishReason()（usage/cache 投影），每条 append 即落（取代仅末条）</li>
     *   <li><b>tool</b>：insert tool 行 + toolCallMapper.update result/isError + STOMP 去重回放</li>
     * </ul>
     * synchronized(ctx.lock)：StreamingToolExecutor 真流式工具并行 append → 串行落库 + 单调 ts。
     */
    private void persistAppendedMessage(PersistCtx ctx, AgentState state, ChatMessageDto m,
            String streamTopic, SimpMessagingTemplate wsTemplate) {
        synchronized (ctx.lock) {
            String sessionId = ctx.sessionId;
            java.time.OffsetDateTime ts = ctx.baseTs.plusNanos(ctx.tsSeq.incrementAndGet());

            // [fix-loop-resume-history] 注入历史消息跳过（防重复落库 · 双通道铁律：DB 权威读取不破坏）。
            //   prePersistedMessageIds 在 doRun 历史注入时先登记后 append（LlmAgentLoop:2294），listener
            //   据此在 append 时点即识别历史 id 跳过（合成 sentinel/Continue 同登记，绝不可落库）。
            java.util.Set<String> prePersisted = state.prePersistedMessageIds();
            if (prePersisted != null && m.id() != null && prePersisted.contains(m.id())) {
                return;
            }

            // ── snip_boundary：role=system + subtype=snip_boundary · 逐字搬原 replayAndPersist snip 分支 ──
            if (m.role() == Role.system
                    && com.nexusai.application.agent.compact.SnipCompactor.SUBTYPE_SNIP_BOUNDARY.equals(m.subtype())) {
                String boundaryId = m.id() != null ? m.id() : "msg-" + UUID.randomUUID().toString().substring(0, 8);
                try {
                    if (messageMapper.selectOneById(boundaryId) != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("ChatService: snip_boundary 已落库，跳过重复 insert（防主键冲突）: id={}", boundaryId);
                        }
                        return;
                    }
                    MessageRecord rec = new MessageRecord();
                    rec.setId(boundaryId);
                    rec.setSessionId(sessionId);
                    rec.setRole(Role.system.name());
                    rec.setAuthor(m.author() != null ? m.author() : "system");
                    rec.setContent(m.content());
                    rec.setSubtype(m.subtype());
                    if (m.snipMetadata() != null) {
                        rec.setSnipMetadata(JSON.writeValueAsString(m.snipMetadata()));
                    }
                    rec.setCreatedAt(ts.toString());
                    messageMapper.insert(rec);
                    java.util.List<String> removedUuids = m.snipMetadata() != null
                            && m.snipMetadata().get("removedUuids") instanceof java.util.List<?> ru
                            ? ru.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                            : java.util.List.of();
                    if (!removedUuids.isEmpty()) {
                        sendAndLog(wsTemplate, streamTopic,
                            new com.nexusai.eventbus.ws.MessageBoundaryEvent(
                                sessionId, ctx.lastUserMessageId.get(), removedUuids, m.content()),
                            "message.boundary removedUuids=" + removedUuids.size());
                    }
                    if (log.isInfoEnabled()) {
                        log.info("ChatService: snip_boundary 实时落库+推送: session={} boundary={} removedUuids={} 条",
                            sessionId, rec.getId(), removedUuids.size());
                    }
                } catch (Exception e) {
                    log.warn("ChatService: snip_boundary 实时落库失败（best-effort）: session={} id={}: {}",
                        sessionId, m.id(), e.getMessage());
                }
                return;
            }

            if (m.role() == Role.user) {
                // [AM-CC-20260825] A4 图片 user 消息：imagePasteIds 回写 lastUserMessageId 对应 user 行
                //   （createUserMessage 已落库，本体不重复 insert）。
                // [OD-D13 AM 回写守卫（reflector MAJOR-3）] 仅 m.id()==ctx.lastUserMessageId.get() 或
                //   injectedQueuedById(m.id())==null 才 updateUserImagePasteIds —— 防 busy 带图消息 append 时
                //   lastUserMessageId 仍指向原 turn user 行 → busy 图 imagePasteIds 脏写上一 user 行
                //   （图归属错乱）。busy 带图行 imagePasteIds 由下方 createQueuedUserMessage 8 参 overload
                //   直接落自身行（非 AM 回写）。
                if (messageService != null && m.imagePasteIds() != null && !m.imagePasteIds().isEmpty()
                        && m.id() != null) {
                    boolean isLastUserRow = m.id().equals(ctx.lastUserMessageId.get());
                    boolean notInjected = injectedQueuedById(state, m.id()) == null;
                    if (isLastUserRow || notInjected) {
                        try {
                            messageService.updateUserImagePasteIds(ctx.lastUserMessageId.get(), m.imagePasteIds());
                        } catch (Exception e) {
                            log.warn("[ChatService] 实时落库回写 user 消息 image_paste_ids 失败: id={} err={}",
                                m.id(), e.getMessage());
                        }
                    }
                }
                // [mid-turn-align] 仅 mid-turn 注入的 queued-user 原位落库（4 参单调 ts）+ 推进归属。
                if (messageService != null && m.id() != null) {
                    AgentState.InjectedQueuedMessage inj = injectedQueuedById(state, m.id());
                    if (inj != null) {
                        try {
                            // [P0-1 OD-1/OD-3] 6 参重载透传 queuedOrigin（registry 仅 busy-queued →
                            //   'busy-queued' 落 V67 queued_origin 列；resume toDto 读回 → 发送层重包壳）
                            // [OD-D13] 8 参 overload 接 imagePasteIds + userAttachments：busy 带图消息
                            //   m.imagePasteIds()（buildUserMessageWithImages 恒收集）→ 落 V46 自身行
                            //   （busy 图行由 overload 写入，非 AM 回写 —— 守卫上方已拦截脏写上一行）。
                            //   content 仍 inj.content()（QueueItem.value 原文，壳不落库）；userAttachments
                            //   本期 busy 图 null（base64 直传无附件表 contentId）。
                            messageService.createQueuedUserMessage(sessionId, m.id(), inj.content(), ts,
                                false, inj.queuedOrigin(), m.imagePasteIds(), m.userAttachments());
                            ctx.lastUserMessageId.set(m.id());
                            if (log.isInfoEnabled()) {
                                log.info("ChatService: mid-turn 注入排队 user 消息实时原位落库 session={} id={}"
                                        + " images={}",
                                    sessionId, m.id(),
                                    m.imagePasteIds() == null ? 0 : m.imagePasteIds().size());
                            }
                        } catch (Exception e) {
                            log.warn("ChatService: mid-turn 注入排队 user 消息实时原位落库失败: session={} id={}: {}",
                                sessionId, m.id(), e.getMessage());
                        }
                    }
                }
                return;
            }

            if (m.role() == Role.assistant) {
                String id = m.id();
                if (log.isInfoEnabled()) {
                    log.info("ASSISTANT msg: id={} contentLen={} reasoningLen={} toolCalls={} subtype={}",
                        abbreviate(id, 16),
                        m.content() == null ? 0 : m.content().length(),
                        m.reasoning() == null ? 0 : m.reasoning().length(),
                        m.toolCalls() == null ? 0 : m.toolCalls().size(),
                        m.subtype());
                }
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    // 工具轮 assistant：insert finishReason=tool_calls（不落 usage 列，对齐原循环）+ 逐条 ToolCallRecord。
                    MessageRecord rec = newAssistantMessage(sessionId, id, ctx.lastUserMessageId.get());
                    rec.setContent(m.content() == null ? "" : m.content());
                    rec.setReasoning(m.reasoning());
                    rec.setReasoningDurationMs(m.reasoningDurationMs());
                    rec.setFinishReason("tool_calls");
                    rec.setCreatedAt(ts.toString());
                    messageMapper.insert(rec);
                    appendReasoningDurationToTranscript(sessionId, id, m.reasoningDurationMs());
                    for (ToolCallDto tc : m.toolCalls()) {
                        Map<String, Object> argsMap = parseArgs(tc.arguments());
                        // [工具调用实时推] 去重：本 turn 已实时推送过同 toolCallId 的 tool_call → 跳过；
                        //   ToolCallRecord insert 无条件（对齐原 replayAndPersist 双守卫语义）。
                        //   ⚠️ 实时化时序：assistant append（本 listener）先于 executor.add（runTools），
                        //   若本处推送必须同步登记 realtimeToolCallsPushed——否则 executor 稍后 add 时
                        //   set.add 返回 true 再推一次 → 前端重复工具卡片（原批量 replay 在 run 末无此
                        //   时序，Executor 已完成不会双推；实时化后顺序反转必须登记防双）。
                        if (state.realtimeToolCallsPushed() == null
                                || !state.realtimeToolCallsPushed().contains(tc.id())) {
                            if (state.realtimeToolCallsPushed() != null) {
                                state.realtimeToolCallsPushed().add(tc.id());
                            }
                            sendAndLog(wsTemplate, streamTopic,
                                new MessageToolCallEvent(sessionId, ctx.lastUserMessageId.get(), id,
                                    tc.id(), tc.name(), argsMap),
                                "tool_call name=" + tc.name()
                                    + " id=" + abbreviate(tc.id(), 24)
                                    + " args=" + abbreviate(tc.arguments(), 120));
                        }
                        ToolCallRecord tcRec = new ToolCallRecord();
                        tcRec.setId(tc.id());
                        tcRec.setMessageId(id);
                        tcRec.setToolName(tc.name());
                        tcRec.setArguments(tc.arguments());
                        tcRec.setResult(null);
                        tcRec.setIsError(false);
                        tcRec.setCreatedAt(OffsetDateTime.now().toString());
                        toolCallMapper.insert(tcRec);
                    }
                } else {
                    // 纯文本 assistant（含截断 subtype=max_tokens / error 文案行）：每条 append 即落
                    //   （取代原 replayAndPersist final 仅落末条 · 对齐 CC transcript append-only）。
                    //   usage/cache 投影对照原 final 块（tool_calls 分支不落 usage，纯文本等价 final）。
                    String fr = "max_tokens".equals(m.subtype())
                        ? "max_tokens" : (state.finishReason() == null ? "stop" : state.finishReason());
                    MessageRecord rec = newAssistantMessage(sessionId, id, ctx.lastUserMessageId.get());
                    rec.setContent(m.content() == null ? "" : m.content());
                    rec.setReasoning(m.reasoning());
                    rec.setReasoningDurationMs(m.reasoningDurationMs());
                    rec.setFinishReason(fr);
                    AgentUsage usage = m.usage();
                    rec.setInputTokens(usage != null ? (int) usage.inputTokens() : null);
                    rec.setOutputTokens(usage != null ? (int) usage.outputTokens() : null);
                    rec.setCacheReadInputTokens(usage != null && usage.cacheReadInputTokens() != null
                        ? Math.toIntExact(usage.cacheReadInputTokens()) : null);
                    rec.setCacheCreationInputTokens(usage != null && usage.cacheCreationInputTokens() != null
                        ? Math.toIntExact(usage.cacheCreationInputTokens()) : null);
                    rec.setCreatedAt(ts.toString());
                    messageMapper.insert(rec);
                    appendReasoningDurationToTranscript(sessionId, id, m.reasoningDurationMs());
                    if (log.isInfoEnabled()) {
                        log.info("ChatService: 纯文本 assistant 实时落库: session={} id={} finishReason={} len={}",
                            sessionId, id, fr, m.content() == null ? 0 : m.content().length());
                    }
                }
                return;
            }

            if (m.role() == Role.tool) {
                String content = m.content() == null ? "" : m.content();
                boolean isError = content.startsWith("Error")
                    || content.toLowerCase().contains("dangerous")
                    || content.toLowerCase().contains("not found")
                    || content.toLowerCase().contains("no such tool");
                // [工具调用实时推] 去重 + STOMP 推送。父 id = m.assistantMessageId()（= turnAssistantId）；
                //   null 时跳过推送（实时上下文无原 replayAndPersist finalAssistantId 随机占位——DB insert
                //   无条件保留，前端刷新经 GET /messages 重建卡片；fail loud 已 debug 记日志）。
                //   ⚠️ 登记 realtimeToolResultsPushed（同 tool_call 分支防双推理据）：tool_result 消息
                //   append 通常晚于 executor pushToolResultRealtime（已登记 → 此处跳过），headless/未推
                //   场景本处推送须登记，防同 turn 内后续重复。
                if (m.assistantMessageId() != null
                        && (state.realtimeToolResultsPushed() == null
                            || !state.realtimeToolResultsPushed().contains(m.toolCallId()))) {
                    if (state.realtimeToolResultsPushed() != null) {
                        state.realtimeToolResultsPushed().add(m.toolCallId());
                    }
                    sendAndLog(wsTemplate, streamTopic,
                        new MessageToolResultEvent(sessionId, ctx.lastUserMessageId.get(),
                            m.assistantMessageId(),
                            m.toolCallId(), truncate(content, 5000), isError),
                        "tool_result id=" + abbreviate(m.toolCallId(), 24)
                            + " len=" + content.length() + " isError=" + isError);
                }
                MessageRecord rec = newToolMessage(sessionId, m.toolCallId(), content, ctx.lastUserMessageId.get());
                rec.setCreatedAt(ts.toString());
                messageMapper.insert(rec);
                ToolCallRecord tcUpdate = toolCallMapper.selectOneById(m.toolCallId());
                if (tcUpdate != null) {
                    tcUpdate.setResult(truncate(content, 5000));
                    tcUpdate.setIsError(isError);
                    toolCallMapper.update(tcUpdate);
                }
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("[实时落库] 未处理消息角色: role={} id={}", m.role(), m.id());
            }
        }
    }

    /**
     * 按 id 在 {@code state.injectedQueuedMessages()} 中查找注入的排队 user 消息（原始 value 载体）。
     *
     * <p>实时落库（persistAppendedMessage user 分支）在 append 时点按 m.id() 反查 injected 原始 content
     * —— 落库用原始 value（非 wrapCommandText 包裹文本），与 m.content() 不同。
     */
    private static AgentState.InjectedQueuedMessage injectedQueuedById(AgentState state, String id) {
        if (state == null || id == null) {
            return null;
        }
        List<AgentState.InjectedQueuedMessage> injected = state.injectedQueuedMessages();
        if (injected == null) {
            return null;
        }
        for (AgentState.InjectedQueuedMessage inj : injected) {
            if (inj != null && id.equals(inj.uuid())) {
                return inj;
            }
        }
        return null;
    }

    /**
     * 末条 assistant 消息的 reasoning（message.complete 事件收口用）。
     *
     * <p>与 {@link AgentState#lastAssistant()} 同向（末向前扫描首个 assistant）取 reasoning，
     * 语义对齐 {@code replayAndPersist} 的 finalReasoning 捕获（:432 {@code finalReasoning = m.reasoning()}）。
     * 此前 MessageCompleteEvent 构造 reasoning 恒传 null → 前端收口消息思考丢失；本方法补上真实值。
     */
    private String lastAssistantReasoning(AgentState state) {
        List<ChatMessageDto> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m.reasoning();
            }
        }
        return null;
    }

    /**
     * 末条 assistant 消息的推理耗时（message.complete 事件收口用）。
     *
     * <p>与 {@link #lastAssistantReasoning} 同向（末向前扫描首个 assistant）取推理耗时，语义对齐
     * {@code replayAndPersist} 的 finalReasoningDurationMs 捕获（finalReasoningDurationMs = m.reasoningDurationMs()）。
     */
    private Long lastAssistantReasoningDurationMs(AgentState state) {
        List<ChatMessageDto> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m.reasoningDurationMs();
            }
        }
        return null;
    }

    /**
     * 末条 assistant 消息的输出解码耗时（message.complete 事件 usage.decode_ms 收口用）。
     *
     * <p>与 {@link #lastAssistantReasoningDurationMs} 同向（末向前扫描首个 assistant）取
     * {@code decodeMs}，值源 = LlmAgentLoop firstTokenMs 打点（首 SSE content chunk → 流结束
     * 跨度，经 withDecodeMs 挂载）。null = 无计时（NON_NULL 省略，前端 null=无数据）。
     */
    private Long lastAssistantDecodeMs(AgentState state) {
        List<ChatMessageDto> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m.decodeMs();
            }
        }
        return null;
    }

    /**
     * 末条 assistant 消息（与 {@link AgentState#lastAssistant()} / {@link #lastAssistantReasoning} 同向：
     * 末向前扫描首个非 null assistant）。
     *
     * <p><b>WHY（同源改造）</b>：replayAndPersist final 落库块需取末条 assistant 的真实
     * {@code ChatMessageDto.id}（=turnAssistantId，配合 LlmAgentLoop 纯文本分支 4-参 toMessage 补传），
     * 使 DB 落库 id 与流式 {@code chunk.assistantMessageId} 同源（前端「块 id 匹配 DB」目标域），
     * 而非随机 {@code finalAssistantId = "msg-"+UUID8}。
     *
     * @param state 回放持久化的 AgentState
     * @return 末条非 null assistant ChatMessageDto；无 assistant → null
     */
    private ChatMessageDto lastAssistantMessage(AgentState state) {
        List<ChatMessageDto> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m;
            }
        }
        return null;
    }

    /**
     * [reasoningDurationMs] transcript 双轨写点 · 把 assistant 推理耗时追加到扁平 transcript 文件
     * （SessionStorage.appendReasoningDuration → {@code {type:'reasoning-duration'}} entry）。
     *
     * <p>best-effort：durationMs==null（无 reasoning）/ sessionId==null / messageId==null → 直接
     * return（不记录）；写失败仅 log.warn 中文日志，不阻断 DB 落库（以 DB 为权威，transcript 仅
     * 审计/未来用途）。workspaceDir 传原始项目根（CwdResolution.getOriginalCwdLayer(sessionId)），
     * 防 getTranscriptPath 双重包裹（SessionStorage.appendReasoningDuration JavaDoc 已标注）。
     *
     * @param sessionId           会话 ID（DB 键 "sess-xxx"）
     * @param messageId           产生该推理的 assistant 消息 id
     * @param reasoningDurationMs 推理耗时 ms；null → no-op
     */
    private void appendReasoningDurationToTranscript(String sessionId, String messageId, Long reasoningDurationMs) {
        if (reasoningDurationMs == null || sessionId == null || messageId == null) {
            return;
        }
        try {
            Path workspaceDir = Path.of(CwdResolution.getOriginalCwdLayer(sessionId));
            SessionStorage.appendReasoningDuration(workspaceDir, sessionId, messageId, reasoningDurationMs);
        } catch (Exception e) {
            log.warn("[ChatService] appendReasoningDurationToTranscript 失败（不阻断主流程）: sessionId={} messageId={} err={}",
                sessionId, messageId, e.getMessage());
        }
    }

    // Phase 6·s02.6: streamTextChunks / streamReasoningChunks 弃用 (真流式由 LlmAgentLoop 推 STOMP).
    // 保留 stub 避免 LlmAgentLoopTest 引用未定义方法, 后续清理.

    // ─────────────────────────── cancel ───────────────────────────

    public boolean cancelSession(String sessionId, SimpMessagingTemplate wsTemplate) {
        // [S4-FIX] 先接通 loop abort 通道：经 SessionAgentStateRegistry 取在飞主 AgentState
        //   调 state.abortStream()（R28 attach 的 runAbortController.abort('user-cancel')），
        //   使 provider 流消费循环 chunk 边界检查 aborted → CancellationException → done.countDown()
        //   → loop 立即退出（对齐 CC abort('user-cancel') 硬中断，替代原 500ms 协式轮询）。
        //   best-effort：registry 未注入 / 无在飞 state（no-op）不阻塞下方 task.cancel 主路径。
        if (sessionAgentStateRegistry != null && sessionId != null && !sessionId.isBlank()) {
            // [session-id-short] sessionId 已 short 直键 registry（不再 parseSessionUuid）
            AgentState inFlight = sessionAgentStateRegistry.get(sessionId);
            if (inFlight != null) {
                inFlight.abortStream("user-cancel");
                if (log.isInfoEnabled()) {
                    log.info("[S4-FIX] cancelSession: 已 abortStream 在飞 AgentState session={}（abort('user-cancel') → provider 硬断流 → loop 立即退出）",
                        sessionId);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[S4-FIX] cancelSession: 无在飞 AgentState（registry miss）session={}", sessionId);
            }
        }
        // [可中断 2026-09-04 · CC Esc] abort 会话在飞压缩（manual HTTP /compact）：前端停止键/Esc →
        //   cancelSession 一并打断压缩（摘要 provider 硬断流 → 'Compaction canceled.'，对齐 CC
        //   compact.ts:126）。无在飞压缩 → false no-op（不阻塞 task.cancel 主路径）。
        boolean compactAborted =
            com.nexusai.application.agent.compact.CompactProgressState.abortForSession(sessionId);
        if (compactAborted && log.isInfoEnabled()) {
            log.info("[cancelSession] 已 abort 在飞压缩 session={}（user-cancel → 摘要断流）", sessionId);
        }
        ChatTask task = inProgress.get(sessionId);
        if (task != null) {
            task.cancel.set(true);
        } else if (log.isDebugEnabled()) {
            log.debug("cancelSession: 无在飞 ChatTask（@Async 启动竞态 / run 已结束）——仍推 cancelled 让前端清理 activeStreams");
        }
        // [reflect-blocker + cancel-必达] 终态事件同源（cancelSession 无 state 引用）：会话级 topic 下
        //   取消归属在飞 assistant 消息——取 registry 在飞 state 当前 turn 真实 id（currentAssistantMessageId() =
        //   prepareAssistantMessageId() 结果，与流式 chunk 事件同源），回落 task 占位（无在飞 state 时）。
        //   <b>task==null（@Async 启动竞态 / run 已结束）也发 cancelled + idle</b>：cancel 是用户命令，
        //   必须回确认——否则前端 activeStreams 等 cancelled 事件永久残留（停止键卡死）。幂等：前端
        //   handleSessionDone 按 topic 校验 + 已删则 no-op，重复 cancelled 无害。
        String realCancelAssistantId = task != null ? task.assistantMessageId : null;
        String cancelUserMessageId = task != null ? task.userMessageId : null;
        if (sessionAgentStateRegistry != null && sessionId != null && !sessionId.isBlank()) {
            AgentState inFlight = sessionAgentStateRegistry.get(sessionId);
            String inFlightId = inFlight != null ? inFlight.currentAssistantMessageId() : null;
            if (inFlightId != null) {
                realCancelAssistantId = inFlightId;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("ChatService: cancelSession 终态事件 assistantId 同源解析 real={} placeholder={} source={}",
                realCancelAssistantId, task != null ? task.assistantMessageId : null,
                (realCancelAssistantId != null && task != null && realCancelAssistantId.equals(task.assistantMessageId))
                    ? "task-placeholder" : "registry-inflight");
        }
        // [streamTopic-session-level] 会话级单 topic（事件仍携带 userMessageId 供前端归组）
        String streamTopic = streamTopic(sessionId);
        sendAndLog(wsTemplate, streamTopic,
            MessageCancelledEvent.of(sessionId, cancelUserMessageId, realCancelAssistantId),
            "cancelled (manual)");
        sendAndLog(wsTemplate, streamTopic,
            SessionStatusEvent.of(sessionId, cancelUserMessageId, "idle"),
            "status=idle (after cancel)");
        log.info("CANCELLED: assistantId={}", realCancelAssistantId);
        // 语义：有实际 ChatTask 被取消 → true；无（@Async 启动竞态 / run 已结束）→ false（事件仍已发，前端可清理）
        return task != null;
    }

    // ─────────────────────────── session 关闭 ───────────────────────────

    /**
     * Phase 4: 关闭 session · 对齐 CC cronScheduler.ts:329 removeSessionCronTasks.
     *
     * <p>调用时机: session 删除/关闭时 (由 {@link com.nexusai.domain.session.SessionService#delete}
     * 在删 DB 前主动调). 同步清理该 session 注册的所有 SESSION-scope schedule 任务 —
     * 内存 sessionJobs 索引 + Quartz job + DB 行. 不影响 DURABLE-scope 任务.
     *
     * <p><b>WHY best-effort</b>: schedule 清理失败 (Quartz 暂时不可用 / 文件锁争用)
     * 不应阻塞 session 删除本身 — 主流程继续, 残留任务下次 fire 时由
     * {@link ScheduleService#deleteAfterFire} 兜底.
     *
     * @param sessionId 要关闭的 session id (null/blank → no-op)
     */
    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("closeSession: sessionId 为空, 跳过 ScheduleService 清理");
            return;
        }
        if (scheduleService == null) {
            log.warn("closeSession: ScheduleService 未注入, 跳过清理 session={}", sessionId);
            return;
        }
        try {
            int deleted = scheduleService.cleanupBySession(sessionId);
            log.info("CLOSE session={} ScheduleService.cleanupBySession 删除任务数={}", sessionId, deleted);
        } catch (Exception e) {
            // best-effort: 清理失败不阻塞 session 删除主流程
            log.error("closeSession: ScheduleService.cleanupBySession 失败 session={}: {}",
                sessionId, e.toString());
        }
    }

    // ─────────────────────────── Provider 路由 (Phase A5) ───────────────────────────

    ProviderConfig buildConfigForModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is blank");
        }
        ModelRecord model = findEnabledModelByName(modelName);
        if (model == null) throw new IllegalStateException("No enabled model for: " + modelName);
        ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
        if (provider == null) throw new IllegalStateException(
            "Provider " + model.getProviderId() + " not found");
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new IllegalStateException("Provider " + provider.getId() + " disabled");
        }
        String rawKey = providerService.getDecryptedApiKey(provider.getId());
        if (rawKey == null || rawKey.isBlank()) {
            log.warn("Provider {} no apiKey → mock fallback", provider.getId());
            return ProviderConfig.empty();
        }
        return new ProviderConfig(provider.getBaseUrl(), rawKey);
    }

    String providerTypeForModel(String modelName) {
        ModelRecord model = findEnabledModelByName(modelName);
        if (model == null) return "openai_compatible";
        ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
        if (provider == null || provider.getType() == null) return "openai_compatible";
        return provider.getType();
    }

    private ModelRecord findEnabledModelByName(String modelName) {
        // W1-2: 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）
        return ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
    }

    // ─────────────────────────── Model 解析（读时唯一主链） ───────────────────────────

    /**
     * [V44] 有效初始权限模式 = per-call ?? 会话 override。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：Web 多会话无 appState 单例，权限模式需要三态链
     * 「会话 override ?? 全局 default」——会话级覆盖落在 {@code sessions.permission_mode} 列
     * （V44），per-call（HTTP 请求体 {@code SendMessageRequest.permissionMode}）恒胜会话 override。
     * 两者共享 CLI 槽（{@code RunRequest.permissionModeCli} → {@code InitialPermissionModeResolver}
     * 链第 2 优先级，恒胜 settings 槽），故此处把「per-call 胜出」后的值直接喂给
     * {@code RunRequest.permissionModeCli}，即实现「会话初始化传 permissionModeCli」。
     *
     * <p><b>回落</b>：两者均 null → 返回 null → resolver 回落 settings 槽（DB 全局
     * {@code settings.permission_mode} → 磁盘 settings.json defaultMode → default）。
     *
     * @param session 会话记录（可能为 null，null 时跳过会话层 override）
     * @param perCallPermissionMode HTTP 请求体携带的 permissionMode（可能为 null）
     * @return 生效的 CC 权限模式串；无 per-call 且无会话 override → null（回落全局）
     */
    static String resolveEffectivePermissionMode(SessionRecord session, String perCallPermissionMode) {
        if (perCallPermissionMode != null) {
            return perCallPermissionMode;
        }
        return session != null ? session.getPermissionMode() : null;
    }

    /**
     * 跨文档唯一读时解析符号：四层链解析本次会话实际使用的 model 名（恒不 null）。
     *
     * <p>优先级：1) 请求体 modelName → 2) 会话 override → 3) settings mainModelName → 4) DEFAULT_MODEL。
     * 对齐 CC 读时 model 解析（doc-b §2.3 / doc-c §3.2 权威版），禁另起 resolveRuntimeModel。
     *
     * @param session 会话记录（可能为 null，null 时跳过会话层）
     * @param reqModelName 请求体携带的 model 名（可能为 null/blank）
     * @return 解析出的 model 名（恒非 null 非 blank）
     */
    String resolveModelNameForSession(SessionRecord session, String reqModelName) {
        // 1) 请求体 modelName 优先
        if (reqModelName != null && !reqModelName.isBlank()) {
            return reqModelName;
        }
        // 2) 会话 override
        if (session != null && session.getModelName() != null && !session.getModelName().isBlank()) {
            return session.getModelName();
        }
        // 3) settings mainModelName → model 名
        String fromSettings = resolveSettingsModelName();
        if (fromSettings != null) return fromSettings;
        // 4) 默认
        return DEFAULT_MODEL;
    }

    /**
     * [P-26] fallback 模型 ≠ 主模型 同步校验 · 对齐 CC main.tsx:1336-1340
     * （read 自验：{@code if (fallbackModel && options.model && fallbackModel === options.model)}
     * → stderr {@code 'Error: Fallback model cannot be the same as the main model. Please specify
     * a different model for --fallback-model.'} + exit(1)）。
     *
     * <p>CC 在 CLI 启动期校验；Java 按调用传入（SendMessageRequest.fallbackModel），等效校验点
     * 落 HTTP 请求体层（决策 P-26：两入口同步 400）。主模型取 {@link #resolveModelNameForSession}
     * 四层链解析的生效值（req.modelName → session override → settings → DEFAULT_MODEL），
     * 与 CC {@code options.model}（解析后的主模型）语义一致。
     *
     * <p>仅在双值均非空且相等时拒绝（CC {@code fallbackModel && options.model &&} 短路语义）；
     * 单侧 null / 空白放行。异常类型 {@link ValidationException}（GlobalExceptionHandler:50-54
     * → 400；plan 原拟 IllegalArgumentException，但项目 400 映射惯例为 ValidationException，
     * 偏差见 concerns）。
     *
     * @param sessionId 会话 ID（解析 session override 用；session 不存在 → 跳过会话层）
     * @param req       请求体（取 modelName + fallbackModel；null → 无校验）
     * @throws ValidationException 当 fallbackModel 与生效主模型非空且相等
     */
    public void validateFallbackModelDistinct(String sessionId, SendMessageRequest req) {
        String fallbackModel = req != null ? req.fallbackModel() : null;
        if (fallbackModel == null || fallbackModel.isBlank()) {
            return;
        }
        SessionRecord session = sessionMapper.selectOneById(sessionId);
        String mainModel = resolveModelNameForSession(session, req != null ? req.modelName() : null);
        if (mainModel != null && !mainModel.isBlank() && mainModel.equals(fallbackModel)) {
            if (log.isDebugEnabled()) {
                log.debug("ChatService: fallback 模型与主模型相同被拒 (session={}, model={}) · CC main.tsx:1336-1340",
                    sessionId, mainModel);
            }
            throw new ValidationException(
                "Fallback model cannot be the same as the main model. Please specify a different model for --fallback-model.");
        }
    }

    /**
     * 读取 settings 单例的 mainModelName 并解析成 model 名（异常吞并 → 回落 null）。
     *
     * <p>继承原 SessionService.resolveDefaultModelName（已删除，语义迁移至此）的 try/catch + log.warn 吞并防御：
     * settingsMapper.selectOneById 异常不向上传播出 processUserMessage 的 async 线程（否则无 catch 静默失败），
     * 而是 catch 后返回 null → 主链回落 DEFAULT_MODEL。
     * [R-1 P0 修复] settings 存全名/裸名（V28 RENAME main_model_id→main_model_name），走
     * {@link ModelNameResolver#resolve} 全名反查（providerName/modelName 联合查，无 / 回退按 name 查，
     * 对齐 CronIdleExecutor:546-548）；不再按 models.id selectOneById 直查——settings 存的是名字而非 id，
     * id 直查恒 miss → 主循环恒回落 DEFAULT_MODEL(mock-fast)。
     *
     * @return settings mainModelName 对应的 model 名；settings 不存在 / mainModelName 空 / 解析失败时返回 null
     */
    private String resolveSettingsModelName() {
        try {
            SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            if (s == null || s.getMainModelName() == null || s.getMainModelName().isBlank()) {
                return null;
            }
            // [R-1 P0 修复] settings 存全名/裸名 → 全名反查而非 id 直查（对齐 CronIdleExecutor.resolveMainModelName:546-548）
            ModelRecord m = ModelNameResolver.resolve(modelMapper, providerMapper, s.getMainModelName());
            String name = (m != null && Boolean.TRUE.equals(m.getEnabled())) ? m.getName() : null;
            // [W2-2] 中档（主模型）解析命中数据流日志 · CC getMainLoopModel settings 层（model.ts:92-98）
            if (log.isDebugEnabled()) {
                log.debug("[ChatService] 中档主模型 settings.mainModelName 全名反查: raw={} → name={}",
                    s.getMainModelName(), name);
            }
            return name;
        } catch (Exception e) {
            log.warn("resolveSettingsModelName failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 降级模型解析：请求体 fallbackModel 优先，空则回落 settings.fallbackModelName（前端设置页可配）。
     * 解析结果与主模型相同则忽略（返回 null，不降级——避免 RetryOptions fallback==main 的退化语义）。
     *
     * @param mainModel  生效主模型名（恒非 null）
     * @param reqFallback 请求体 fallbackModel（可 null/blank）
     * @return 生效 fallback 模型名；无 / 与主模型相同 → null
     */
    private String resolveEffectiveFallbackModel(String mainModel, String reqFallback) {
        String fb = (reqFallback != null && !reqFallback.isBlank())
            ? reqFallback
            : resolveFallbackModelName();
        if (fb == null || fb.isBlank() || fb.equals(mainModel)) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[F4] 降级模型生效: fallback={} main={}", fb, mainModel);
        }
        return fb;
    }

    /**
     * 读取 settings 单例的 fallbackModelName（前端设置页配置的全局降级模型）。
     * 异常吞并 → 回落 null（不降级）；null/blank → null。
     */
    private String resolveFallbackModelName() {
        try {
            SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            if (s == null || s.getFallbackModelName() == null || s.getFallbackModelName().isBlank()) {
                return null;
            }
            return s.getFallbackModelName();
        } catch (Exception e) {
            log.warn("resolveFallbackModelName failed: {}", e.toString());
            return null;
        }
    }

    // ─────────────────────────── 标题生成 ───────────────────────────

    /**
     * [title-cc-align] 标题生成（对齐 CC initReplBridge.ts:349-378 onUserMessage 的 count1+count3
     *   触发语义 + REPL.tsx:2661-2699 haikuTitleAttemptedRef 一次性）。
     *
     * <p>触发条件（userCount = title-worthy 用户消息计数，role=user 且 is_meta 非 1 且 content 非空；
     *   count1 输入 = 首条 title-worthy 用户消息原文，count3 输入 = 完整会话文本尾部 1000 字符）：
     * <ul>
     *   <li><b>count1</b>：userCount>=1 && titleExplicit==0 && isDefaultTitle（title 仍默认 = count1
     *       未成功 / 未显式命名）——对齐 CC {@code count===1 && !hasTitle}（initReplBridge.ts:355-358）</li>
     *   <li><b>count3</b>：userCount>=3 && titleExplicit==0（未显式命名（非 1）且未刷新（非 2）才刷新）
     *       ——对齐 CC {@code userMessageCount === 3} → extractConversationText 全会话尾部
     *       （sessionTitle.ts:33-54 MAX_CONVERSATION_TEXT=1000，tail-slice 最近上下文优先）</li>
     * </ul>
     * 两者<b>并列判断</b>（非 else）：userCount==1 只走 count1；==2 走 count1；>=3 若 title 仍默认
     * 先补 count1 再 count3（聚合兜底，防批量跳变越过 3 不触发，反射器 MAJOR-3 定死）。显式 /rename
     * （titleExplicit==1，SessionService.update 置位）永不自动覆盖。
     *
     * <p>覆盖规则：count1 成功置 titleExplicit=0（仍可被 count3 进化，对齐 CC count1 生成 → count3
     *   覆盖自动 title）；count3 成功置 titleExplicit=2（已自动刷新，不再重复刷新，对齐 CC count>=3
     *   后回调停 done）。生成失败落"新会话"占位（titleExplicit 不变）→ 后续收口按条件重试。
     *
     * <p>public 化：CronIdleExecutor 收口补 title 生成（busy-queued / cron / task-notification 轮末
     *   同样触发，对齐 CC 所有路径汇聚 onQuery 都会检查 title）。
     *
     * @param session               会话记录（调用方已从 DB 载入）
     * @param userMessageId         该轮 user 消息 id（SessionTitleEvent 归属；cron 场景 cmd.uuid() 可 null）
     * @param assistantFinalContent 该轮 assistant 最终内容（保留参数，暂未用于输入）
     * @param wsTemplate            STOMP 出站模板（status topic 推送）
     */
    public void maybeGenerateTitle(SessionRecord session, String userMessageId,
                                   String assistantFinalContent, SimpMessagingTemplate wsTemplate) {
        try {
            String currentTitle = session.getTitle();
            // [联调修复] 对齐 CC initReplBridge.ts:299-336「非显式命名可被自动生成覆盖」：前端创建会话
            //   传占位标题（'新会话' 等）不等同于用户显式 /rename → 一律视为默认，允许摘要生成替换。
            //   CC 侧显式命名（/rename）有内存标记；Java 端以 title_explicit 列（V66）三态承载。
            boolean looksLikeDefault = isDefaultTitle(currentTitle, session.getModelName());
            Integer titleExplicit = session.getTitleExplicit();
            // [FIX-1 对抗核验 MAJOR] 自动生成资格 = 未显式命名（非 1）且未自动刷新（非 2）——
            //   旧 explicitBlocked 只拦 1 不拦 2：count3 成功置 2 后下一收口又满足条件 → 每轮重复刷新。
            //   对齐 plan §3.4.2 + CC initReplBridge.ts:377「return userMessageCount >= 3 后停 done」。
            boolean autoEligible = titleExplicit == null || titleExplicit == 0;
            // [title-cc-align] title-worthy 用户消息计数替代 messageCount（后者混入 system 计数，长任务
            //   首轮工具/system 消息多 → 快速超 2 永不生成；对齐 CC onUserMessage userMessageCount
            //   initReplBridge.ts:349-352）
            int userCount = countTitleWorthyUserMessages(session.getId());
            boolean doCount1 = userCount >= 1 && autoEligible && looksLikeDefault;
            boolean doCount3 = userCount >= 3 && autoEligible;
            if (!doCount1 && !doCount3) return;

            String fastModelName = modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001");
            if (fastModelName == null) {
                // [G-9] 弱档(小快)模型统一委托 ModelConfigResolver.resolveFastModelName（②weak→③haiku45，
                //   不回退主模型）；未配置 → 跳过生成，log.debug 披露（不静默，对齐 CC getSmallFastModel
                //   model.ts:36-38 缺失语义）
                if (log.isDebugEnabled()) {
                    log.debug("[ChatService] 标题生成跳过：fastModelName 未配置（弱档小快模型缺失，"
                            + "对齐 CC getSmallFastModel model.ts:36-38）session={} userCount={}",
                        session.getId(), userCount);
                }
                return;
            }
            // count1（title 仍默认）→ 首条 title-worthy 用户消息输入；成功置 titleExplicit=0（仍可被 count3 进化）
            if (doCount1) {
                String userContent = extractFirstUserContent(session.getId());
                String input = userContent != null ? userContent : "(无内容)";
                boolean ok = applyGeneratedTitle(session, generateTitleText(input, fastModelName),
                    0, userMessageId, wsTemplate);
                if (log.isInfoEnabled()) {
                    log.info("[ChatService] 标题 count1 生成{} session={} userCount={} title={}",
                        ok ? "成功" : "失败（保持原标题与 titleExplicit 不变，可重试）",
                        session.getId(), userCount, session.getTitle());
                }
            }
            // count3 → 完整会话文本尾部 1000 字符输入；成功置 titleExplicit=2（已自动刷新，不再重复刷新）
            if (doCount3) {
                String convText = extractConversationTextTail(session.getId());
                String input = (convText != null && !convText.isBlank()) ? convText : "(无内容)";
                boolean ok = applyGeneratedTitle(session, generateTitleText(input, fastModelName),
                    2, userMessageId, wsTemplate);
                if (log.isInfoEnabled()) {
                    log.info("[ChatService] 标题 count3 刷新{} session={} userCount={} title={}",
                        ok ? "成功" : "失败（保持原标题与 titleExplicit 不变，可重试）",
                        session.getId(), userCount, session.getTitle());
                }
            }
        } catch (Exception e) {
            log.error("maybeGenerateTitle failed", e);
        }
    }

    /**
     * [title-cc-align] 生成标题文本 · 对齐 CC sessionTitle.ts:79-129 generateSessionTitle
     *   （SESSION_TITLE_PROMPT 3-7 words sentence case + JSON {title} 结构化输出；异常 → "新会话" 占位，
     *   never rejects 语义）。提取自原 maybeGenerateTitle 生成段，count1/count3 共用。
     *
     * @return 非 null 非 blank 的标题（生成失败回落"新会话"占位）
     */
    private String generateTitleText(String input, String fastModelName) {
        ProviderConfig config;
        String providerType;
        try {
            config = buildConfigForModel(fastModelName);
            providerType = providerTypeForModel(fastModelName);
        } catch (Exception e) {
            config = ProviderConfig.empty();
            providerType = "openai_compatible";
        }
        LlmProvider titleProvider = llmProviderFactory.getProvider(config, providerType);
        String prompt = "用 5-10 个字总结: " + input;
        String newTitle;
        try {
            // [align-cc 2026-08-25] 对齐 CC sessionTitle.ts SESSION_TITLE_PROMPT（3-7 words + sentence case
            //   + 好/坏例子 + JSON {title} 结构化输出）——旧 prompt「用 5-10 个字总结」无约束，LLM 自由发挥
            //   输出对话文本（实测 title="一切是否已然终结？"/"请提供项目描述..."）。
            String titlePrompt = "Generate a concise, sentence-case title (3-7 words) that captures the main topic "
                + "or goal of this coding session. The title should be clear enough that the user recognizes the "
                + "session in a list. Use sentence case: capitalize only the first word and proper nouns.\n\n"
                + "Return JSON with a single \"title\" field.\n\n"
                + "Good examples:\n{\"title\": \"Fix login button on mobile\"}\n{\"title\": \"Add OAuth authentication\"}\n"
                + "{\"title\": \"Debug failing CI tests\"}\n{\"title\": \"Refactor API client error handling\"}\n\n"
                + "Bad (too vague): {\"title\": \"Code changes\"}\n"
                + "Bad (too long): {\"title\": \"Investigate and fix the issue where the login button does not respond on mobile devices\"}\n"
                + "Bad (wrong case): {\"title\": \"Fix Login Button On Mobile\"}";
            // [language 2026-09-04] 标题语言跟随 settings.language(auto → LanguageResolver 按时区解析成
            //   语言显示名)。已配置 → 追加"用 {语言} 输出标题"(解决中文会话出英文标题)。null(未配置)→ 原样。
            String titleLanguage = resolveTitleLanguage();
            if (titleLanguage != null) {
                titlePrompt = titlePrompt + "\nOutput the title in " + titleLanguage + ".";
            }
            var titleSchema = JSON.createObjectNode();
            titleSchema.put("type", "object");
            titleSchema.putObject("properties").putObject("title").put("type", "string");
            titleSchema.putArray("required").add("title");
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                List.of(), null, LlmProvider.ChatRequestOptions.OutputFormat.jsonSchema(titleSchema),
                null, null, "auto_title", null, null, null, null, null, null, null, null, null);
            String titleJson = titleProvider.chatWithOptions(config, fastModelName, titlePrompt, prompt, options);
            newTitle = (titleJson != null && !titleJson.isBlank())
                ? JSON.readTree(titleJson).path("title").asText(null) : null;
        } catch (Exception ex) {
            log.warn("Title failed: {}", ex.getMessage());
            newTitle = "新会话";
        }
        if (newTitle != null) {
            newTitle = newTitle.trim().replaceAll("^[\"']|[\"']$", "");
            if (newTitle.length() > 30) newTitle = newTitle.substring(0, 30) + "...";
            if (newTitle.isBlank()) newTitle = "新会话";
        } else newTitle = "新会话";
        return newTitle;
    }

    /**
     * [language 2026-09-04] 读 settings.language(auto → LanguageResolver 按时区解析)得标题语言显示名。
     *   读失败 / 未配置 → null(标题 prompt 不加语言要求,沿用英文 prompt)。
     */
    private String resolveTitleLanguage() {
        try {
            com.nexusai.repository.settings.entity.SettingsRecord s =
                settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            if (s == null) {
                return null;
            }
            return com.nexusai.application.agent.prompt.LanguageResolver.resolve(s.getLanguage());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[ChatService] resolveTitleLanguage 读 settings.language 失败(标题仍按原 prompt 生成): {}",
                    e.getMessage());
            }
            return null;
        }
    }

    /**
     * [title-cc-align] 应用生成的标题并推 status topic · titleExplicit 按生成轮次置位
     * （count1→0 可进化 / count3→2 已刷新停止）。
     *
     * <p>[FIX-2 对抗核验 MINOR] 成功判定：generateTitleText 失败回落"新会话"占位
     * （isDefaultTitle 判 true）→ 不落库不置位，保持原 title 与 titleExplicit 不变 ——
     *   否则 count3 失败置 titleExplicit=2 会锁死重试（plan §3.4.6「失败 titleExplicit 不变」），
     *   且可能用占位标题覆写已有自动标题。
     *
     * @return true=生成成功已落库；false=生成失败（回落占位，保持原状态可重试）
     */
    private boolean applyGeneratedTitle(SessionRecord session, String newTitle, int titleExplicit,
                                        String userMessageId, SimpMessagingTemplate wsTemplate) {
        // 成功判定：newTitle 非空且非默认占位（count1/count3 生成失败统一回落"新会话"→ false）
        boolean generated = newTitle != null && !isDefaultTitle(newTitle, session.getModelName());
        if (!generated) {
            if (log.isDebugEnabled()) {
                log.debug("[ChatService] 标题生成失败（回落默认占位），保持原 title 与 titleExplicit 不变 session={}",
                    session.getId());
            }
            return false;
        }
        session.setTitle(newTitle);
        session.setTitleExplicit(titleExplicit);
        session.setUpdatedAt(OffsetDateTime.now().toString());
        sessionMapper.update(session);
        // [AM-CC-20260825] title 推会话级 status topic（前端常驻订阅），而非 stream topic——
        //   stream topic 在 message.complete 后前端可能退订 → title 事件丢失（新建会话列表标题不更新，
        //   2026-08-25 联调实测）。status topic 前端持续订阅，实时收到标题。
        sendAndLog(wsTemplate, "/topic/sessions/" + session.getId() + "/status",
            com.nexusai.eventbus.ws.SessionTitleEvent.of(session.getId(), userMessageId, newTitle),
            "title=" + newTitle);
        return true;
    }

    /**
     * [title-cc-align] title-worthy 用户消息计数 · 对齐 CC onUserMessage userMessageCount
     *   （initReplBridge.ts:349-352）：role=user 且 (is_meta IS NULL OR is_meta != 1) 且 content 非空。
     *   is_meta NULL 显式包含（V51 存量旧行）；替代 messageCount（混入 system 计数导致长任务永不生成）。
     *   原始 SQL where（与 MessageService:718 同款模式，MyBatis-Flex where(String, Object...) 生产已证）。
     */
    private int countTitleWorthyUserMessages(String sessionId) {
        long count = messageMapper.selectCountByQuery(
            QueryWrapper.create().where(
                "session_id = ? AND role = ? AND (is_meta IS NULL OR is_meta != 1) "
                    + "AND content IS NOT NULL AND content != ''",
                sessionId, Role.user.name()));
        return (int) count;
    }

    /**
     * [title-cc-align] 完整会话文本尾部 1000 字符 · 对齐 CC sessionTitle.ts:33-54 extractConversationText
     *   （MAX_CONVERSATION_TEXT=1000；遍历 user/assistant 跳过 isMeta / 空 content，tail-slice
     *   最近上下文优先；无 origin 列 → isMeta + 空 content 近似 human origin 判定）。
     */
    private String extractConversationTextTail(String sessionId) {
        List<MessageRecord> rows = messageMapper.selectListByQuery(
            QueryWrapper.create().where(
                "session_id = ? AND role IN ('user', 'assistant')", sessionId)
                .orderBy("created_at", true));
        if (rows == null || rows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (MessageRecord m : rows) {
            if (Boolean.TRUE.equals(m.getIsMeta())) continue;
            String c = m.getContent();
            if (c == null || c.isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c);
        }
        String text = sb.toString();
        return text.length() > 1000 ? text.substring(text.length() - 1000) : text;
    }

    /**
     * 标题是否为「默认/未显式命名」（允许自动生成覆盖）。对齐 CC initReplBridge.ts:299-336：
     * 仅用户显式 /rename 命名的标题才阻止自动生成。判定 = null/blank ∨ 等于 modelName ∨ 占位标题
     * （A 方案：后端兜底识别前端占位；B 方案：前端创建不再传占位 title，双保险）。
     * [title-cc-align MAJOR-4] package-private → <b>public static</b>：SessionService
     * （com.nexusai.domain.session，跨包）create 时经本方法判定 titleExplicit 初值。
     */
    public static boolean isDefaultTitle(String title, String modelName) {
        return title == null || title.isBlank()
            || title.equals(modelName)
            || isDefaultPlaceholderTitle(title);
    }

    /**
     * 判断 title 是否为「创建时占位默认标题」（前端未显式命名）。对齐 CC initReplBridge.ts:299-336：
     * 仅用户显式 /rename / /remote-control 命名的标题才阻止自动生成；普通占位标题应被摘要生成覆盖。
     * Java 端无 /rename 内存标记（currentSessionTitle），故用占位集合近似。
     */
    private static boolean isDefaultPlaceholderTitle(String title) {
        if (title == null) return false;
        return switch (title.trim()) {
            case "新会话", "新对话", "新聊天", "新工作区",
                 "Untitled", "New Chat", "New Conversation", "New conversation" -> true;
            default -> false;
        };
    }

    /**
     * 首条 title-worthy 用户消息原文（count1 输入）· 对齐 CC initReplBridge.ts onUserMessage
     *   count===1 时 deriveTitle(text) 取触发那一条消息原文（:365-368）。
     *
     * <p>[FIX-3 对抗核验 MINOR] orderBy 由 created_at DESC（最近）改 ASC（最早）：count1 语义是
     *   「首条」title-worthy 用户消息——CC 逐条必经 count==1，取的是触发 count 的那条（即最早）。
     *   收口聚合（userCount 跳变：immediate local-jsx / 被拒 userInvocable / busy-queued 一次落多行）
     *   时旧逻辑取「最近」一条，与新会话首条主题偏差大。
     *
     * <p>[title-cc-align 反射器 MAJOR-2 定死] 保留 (is_meta IS NULL OR is_meta != 1) + content 非空 过滤：
     *   否则收口时首条 role=user 可能是 cron/task 元消息（isMeta=true 非空 content，
     *   CronIdleExecutor.java:466-473），count1 输入取错。改后恒为首条 title-worthy 用户消息。
     */
    private String extractFirstUserContent(String sessionId) {
        List<MessageRecord> rows = messageMapper.selectListByQuery(
            QueryWrapper.create().where(
                "session_id = ? AND role = ? AND (is_meta IS NULL OR is_meta != 1) "
                    + "AND content IS NOT NULL AND content != ''",
                sessionId, Role.user.name())
                .orderBy("created_at", true).limit(1));
        return (rows != null && !rows.isEmpty()) ? rows.get(0).getContent() : null;
    }

    // ─────────────────────────── helpers ───────────────────────────

    /**
     * [streamTopic-session-level] 会话级单一流式 topic · 对齐 CC 会话单一事件流（ccrClient.ts:100-118
     * byMessage/scopeToMessage 仅用 msg_ 串 + scope(session_id,parent_tool_use_id) 归属，无 topic 维度）。
     *
     * <p>原 per-message topic {@code /topic/sessions/{sid}/messages/{uuid}/stream}（Web 自建翻译层）
     * 删除：消息归属已由事件 {@code userMessageId}/{@code assistantMessageId} 字段承载
     * （MessageChunkEvent.java:16,24-26 等全部消息级事件），topic 不再编码消息 id——前端订阅一次
     * 即收全部消息流事件（chunk/tool_call/tool_result/complete/error/cancelled），按事件携带的消息 id 路由。
     *
     * @param sessionId 目标会话（short）
     * @return 会话级流式 topic {@code /topic/sessions/{sid}/stream}
     */
    private static String streamTopic(String sessionId) {
        return "/topic/sessions/" + sessionId + "/stream";
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() > max ? s.substring(0, max) + "…(" + s.length() + ")" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\n... (truncated)" : s;
    }

    private MessageRecord newAssistantMessage(String sessionId, String id, String userMessageId) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(Role.assistant.name());
        m.setAuthor(null);
        m.setContent("");
        m.setReasoning(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt(OffsetDateTime.now().toString());
        // [userMessageId] assistant 消息 = 发起该轮的用户消息 id（CC parentUuid 链根，
        //   sessionStorage.ts:1066-1068；replayAndPersist 传 turn 的 userMessageId）· V47 列落库
        m.setUserMessageId(userMessageId);
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059 transcriptMessage.cwd = getCwd()。
        //   消息落库时戳入当前工作目录，供 /resume 恢复目录上下文（:2522 projectPath=firstMessage.cwd）。
        //   本路径直写 messageMapper.insert 绕过 MessageService，须在此独立戳入。
        m.setCwd(CwdResolution.getCwd(sessionId));
        return m;
    }

    private MessageRecord newToolMessage(String sessionId, String toolCallId, String content,
                                         String userMessageId) {
        MessageRecord m = new MessageRecord();
        m.setId(generateId("msg"));
        m.setSessionId(sessionId);
        m.setRole(Role.tool.name());
        m.setAuthor("tool");
        m.setContent(content);
        m.setReasoning(null);
        m.setFinishReason(null);
        m.setInputTokens(null);
        m.setOutputTokens(null);
        m.setCreatedAt(OffsetDateTime.now().toString());
        m.setToolCallId(toolCallId);
        // [userMessageId] tool/tool_result 跟随所属 assistant 的 user_message_id（turn 内同源 =
        //   replayAndPersist 的 userMessageId；CC effectiveParentUuid = sourceToolAssistantUUID，
        //   sessionStorage.ts:1028-1037）· V47 列落库
        m.setUserMessageId(userMessageId);
        // [G13] 消息 cwd 戳 · 对齐 CC sessionStorage.ts:1059（同 newAssistantMessage）。
        m.setCwd(CwdResolution.getCwd(sessionId));
        return m;
    }

    private List<ChatMessageDto> loadRecentHistory(String sessionId, int limit) {
        List<MessageRecord> desc = messageMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", sessionId)
                .orderBy("created_at", false).limit(limit));
        List<MessageRecord> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        List<ChatMessageDto> result = new ArrayList<>(asc.size());
        for (MessageRecord m : asc) {
            if (m.getRole() == null) continue;
            try {
                Role r = Role.valueOf(m.getRole());
                if (r == Role.tool) continue;
                result.add(new ChatMessageDto(
                    m.getId(), m.getSessionId(), r, m.getAuthor(),
                    m.getContent() != null ? m.getContent() : "",
                    m.getReasoning(), null, null,
                    m.getInputTokens(), m.getOutputTokens(),
                    null, null, null, null,
                    null,                          // R32-b9 acceptFeedback
                    java.util.List.of(),           // R32-b9 contentBlocks
                    java.util.List.of()));         // R32-b9 imagePasteIds
            } catch (IllegalArgumentException ignored) {
                // skip unknown roles
            }
        }
        return result;
    }

    private String lastUserContent(List<ChatMessageDto> history) {
        if (history == null) return "";
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDto m = history.get(i);
            if (m != null && m.role() == Role.user && m.content() != null) return m.content();
        }
        return "";
    }

    // ─────────────────────────── 附件消费（A1 · attachment-multimodal） ───────────────────────────

    /** [attachments-v2 Step2] 单次请求附件数量上限 · 对齐前端契约（防超大请求体）。 */
    private static final int MAX_ATTACHMENTS_PER_REQUEST = 50;

    /**
     * 解析请求附件 → 可消费附件列表（透传 {@link RunRequest#attachments()}）。
     *
     * <p>对齐 CC {@code pastedContents}（utils/config.ts:54-62 {@code PastedContent}）消费语义 +
     * [附件双模式 · 统一附件表 contentId]（用户拍板 2026-09-02：附件表 {@code attachments} 成为
     * PDF/媒体/大图统一 contentId 注册中心，contentId = attachments 自增 id）。分支顺序：
     * <ul>
     *   <li><b>1. {@code base64}</b> 非空白 → 原样保留（CC {@code PastedContent.content} 直发，
     *       ≤5MB 图片 base64 通道，imageStore/附件表不经手）</li>
     *   <li><b>1.5. {@code path}</b> 非空白（local-read=true）→ 校验白名单/存在/≤200MB/防穿越 → 注册附件表
     *       （source='path'）→ 得新 contentId → 输出 {type, contentId, filename, mediaType, base64=null, path}</li>
     *   <li><b>2. {@code type=pdf} + contentId</b>（无 base64）→ contentId 首选附件表（{@link AttachmentService#getContent}
     *       校验 pdf 记录存在，附件表 path 读盘）；附件表无记录且旧 {@link PdfAttachmentStore} 有 → store 兜底
     *       （历史存量不迁移）。base64 保持 null（大文件不内存直发）</li>
     *   <li><b>2.5. {@code type=video/audio/file} + contentId</b>（无 base64）→ contentId 首选附件表校验，
     *       附件表无记录且 {@link MediaAttachmentStore} 有 → store 兜底（同上）</li>
     *   <li><b>3. {@code contentId}</b>（无 base64，非 pdf/media）→ 首选附件表校验（image/* 记录 → 大图路径通道，
     *       base64=null）；附件表未命中 → 回退 {@link ImageAttachmentStore#getBase64}（≤5MB image-cache 兼容）</li>
     *   <li><b>4.</b> 既无 base64 又无 contentId/path → 无法消费；附件表+store 均未命中 / contentId 非数字 /
     *       path 校验失败 → 无法消费，均 warn 并跳过（fail loud，不静默丢弃）</li>
     * </ul>
     *
     * <p>视频/音频：按方案定稿本期走惰性外挂工具（P1 登记），此处仅解析保序透传
     * （type=video/audio 的条目若带 base64/contentId 同样补全，LlmAgentLoop 侧按 type 分流）。
     *
     * @param sessionId 会话 id（附件表按会话注册；ImageAttachmentStore 按会话分桶）
     * @param raw       请求体 {@code attachments}（null/空 → 空列表）
     * @return 已补全附件列表（未解析项已剔除）；无附件返回空列表
     */
    private List<AttachmentRequest> resolveAttachments(String sessionId, List<AttachmentRequest> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AttachmentRequest> resolved = new ArrayList<>(raw.size());
        for (AttachmentRequest att : raw) {
            if (att == null) {
                continue;
            }
            // 1) 直传 base64（剥离 dataUrl 前缀后保留）· [AM-CC-20260825] 前端粘贴/选择图片传
            //    dataUrl（data:image/png;base64,xxx）——若含前缀，F1 storeWithId base64 decode 失败
            //    → image-cache 空 → multimodal_attachment cache miss（2026-08-25 联调实测）。
            //    对齐 CC pastedContents.content（纯 base64，config.ts:57）。统一在此剥离，下游 F1/A4/A3
            //    全部拿到纯 base64。
            if (att.base64() != null && !att.base64().isBlank()) {
                // [skill-attach-register] base64 直传 PDF（≤5MB，前端契约 base64 无 contentId）→ 落盘 +
                //   注册附件表（统一 contentId → user_attachments 落 contentId → F5 出站 url 可拼预览）。
                //   注册成功 → 转 contentId 路径通道（PdfAttachmentProcessor resolvePathChannel 附件表读盘，
                //   不再 writeBase64Channel 临时落盘）；store/register 失败 → warn 降级保留 base64 直传
                //   （附件不丢，模型仍可读，仅 F5 url 缺失）。
                if (isPdfAttachment(att) && (att.contentId() == null || att.contentId().isBlank())) {
                    AttachmentRequest pdfRegistered = registerBase64Pdf(sessionId, att);
                    if (pdfRegistered != null) {
                        resolved.add(pdfRegistered);
                        if (log.isDebugEnabled()) {
                            log.debug("[A1 attachments] base64 直传 PDF 注册附件表 → contentId 路径通道: "
                                    + "contentId={} filename={}（≤5MB base64 → 附件表统一 contentId，F5 url 可预览）",
                                pdfRegistered.contentId(), pdfRegistered.filename());
                        }
                        continue;
                    }
                    // 注册失败 → 降级走下方原 base64 直传（保留 contentId=null 语义）
                }
                resolved.add(new AttachmentRequest(
                    att.type(), att.contentId(), att.filename(), att.mediaType(),
                    stripDataUrlPrefix(att.base64()), att.path()));
                if (log.isDebugEnabled()) {
                    log.debug("[A1 attachments] 附件直传 base64（dataUrl 前缀已剥离）: type={} filename={} mediaType={}",
                        att.type(), att.filename(), att.mediaType());
                }
                continue;
            }
            // 1.5) [附件双模式 · 统一附件表 contentId · 用户拍板 2026-09-02] path 附件（本地直读，省 upload）：
            //   local-read=true（前后端同机 Tauri 桌面）时前端把 >5MB 大文件以本地绝对路径 path 随消息直传。
            //   校验白名单扩展名 + Files.exists + size≤200MB + 防穿越（normalize 禁 ../ 符号链接）通过 →
            //   attachmentService.register(source='path') 注册附件表（contentId=attachments 自增 id，零拷贝不复制
            //   进 store）→ resolved 输出 AttachmentRequest(type, contentId, filename, mediaType, base64=null, path)。
            //   校验失败 → warn 跳过（fail loud，不静默丢弃）。
            //   [path] 三通道互斥：path 附件不带 base64/contentId（前端契约）；path 分支在 base64 之后优先于
            //   contentId 分支——path 附件的本地绝对路径是唯一真源，contentId 由本分支注册附件表产生。
            if (att.path() != null && !att.path().isBlank()) {
                if (!localRead) {
                    log.warn("[attachments path] local-read=false 收到 path 附件，跳过（远程应走 upload）: path={} type={} filename={}",
                        att.path(), att.type(), att.filename());
                    continue;
                }
                AttachmentRequest pathResolved = resolveAttachmentPath(sessionId, att);
                if (pathResolved != null) {
                    resolved.add(pathResolved);
                }
                continue;
            }
            // 2) type=pdf + contentId（无 base64）→ 统一附件表 contentId 解析（附件双模式 · 用户拍板 2026-09-02）：
            //    >5MB 大 PDF 经 POST /api/v1/attachments/upload multipart 落盘（store pdf-cache）+ 注册 attachments
            //    表 → contentId=附件表自增 id。resolveAttachments 经 attachmentService.getContent 校验附件表记录存在
            //    （附件表 path 读盘，base64=null 路径通道）。历史存量（旧 store contentId，附件表无记录）→ 回退
            //    PdfAttachmentStore.get 校验（store 兜底保留原行为，存量不迁移）；store 也无 → skip fail loud。
            if (isPdfAttachment(att) && (att.base64() == null || att.base64().isBlank())
                    && att.contentId() != null && !att.contentId().isBlank()) {
                long pdfId;
                try {
                    pdfId = Long.parseLong(att.contentId().trim());
                } catch (NumberFormatException e) {
                    log.warn("[U1 attachments] PDF contentId 非数字，跳过附件: contentId={} type={} filename={}",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                // [附件双模式] 附件表命中且 mediaType 为 pdf → 附件表记录；附件表无记录 / mediaType 冲突（id 空间撞号，
                //   如图片-cache 旧 id 撞附件表某视频行）→ store 兜底（历史存量兼容，不迁移）
                AttachmentRecord pdfRec = resolveContentIdInTable(sessionId, pdfId, "application/pdf");
                if (pdfRec == null && pdfAttachmentStore != null) {
                    PdfAttachmentStore.StoredPdf legacyPdf = pdfAttachmentStore.get(sessionId, pdfId);
                    if (legacyPdf != null) {
                        // store 兜底命中：保留 contentId 引用（下游 U2 按 PdfAttachmentStore.getPath 读磁盘）
                        String pdfMediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                            ? att.mediaType() : PdfSupport.PDF_MEDIA_TYPE;
                        resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                            pdfMediaType, null, null));
                        if (log.isDebugEnabled()) {
                            log.debug("[U1 attachments] PDF 附件 store 兜底路径通道解析: contentId={} type={} mediaType={} path={}",
                                att.contentId(), att.type(), pdfMediaType, legacyPdf.path());
                        }
                        continue;
                    }
                }
                if (pdfRec == null) {
                    log.warn("[U1 attachments] PDF 附件附件表+store 均未命中，跳过附件: contentId={} type={} filename={}（type=pdf 路径通道）",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                String pdfMediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                    ? att.mediaType()
                    : (pdfRec.getMediaType() != null ? pdfRec.getMediaType() : PdfSupport.PDF_MEDIA_TYPE);
                // base64=null：路径通道，仅保留 contentId 引用（下游 U3 按附件表 getPath 读磁盘）
                resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                    pdfMediaType, null, null));
                if (log.isDebugEnabled()) {
                    log.debug("[U1 attachments] PDF 附件路径通道解析(附件表): contentId={} type={} mediaType={} path={}",
                        att.contentId(), att.type(), pdfMediaType, pdfRec.getPath());
                }
                continue;
            }
            // 2.5) [附件双模式] type=video/audio/file + contentId（无 base64）→ 统一附件表 contentId 解析：
            //   >5MB 媒体 upload 落盘（media-cache）+ 注册 attachments 表 → contentId=附件表自增 id。
            //   attachmentService.getContent 校验附件表记录存在（附件表 path 读盘，base64=null 路径通道）。
            //   历史存量（旧 store contentId，附件表无记录）→ 回退 MediaAttachmentStore.get 校验（store 兜底，
            //   不迁移保原行为）；store 也无 → skip fail loud。
            if (isMediaAttachment(att) && (att.base64() == null || att.base64().isBlank())
                    && att.contentId() != null && !att.contentId().isBlank()) {
                long mediaId;
                try {
                    mediaId = Long.parseLong(att.contentId().trim());
                } catch (NumberFormatException e) {
                    log.warn("[attachments-v2] 媒体 contentId 非数字，跳过附件: contentId={} type={} filename={}",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                AttachmentRecord mediaRec = resolveContentIdInTable(sessionId, mediaId, null);
                // 撞号防御：附件表记录 mediaType 明显非媒体（image/* 或 application/pdf → 旧 media-cache id 撞附件表
                //   其它类型行）→ 按未命中处理，回退 store 校验（store 是旧 id 的真源）。
                if (mediaRec != null && !isMediaRecordType(mediaRec)) {
                    if (log.isDebugEnabled()) {
                        log.debug("[attachments-v2] 附件表记录 mediaType 非媒体，撞号按未命中处理: contentId={} mediaType={}",
                            mediaId, mediaRec.getMediaType());
                    }
                    mediaRec = null;
                }
                if (mediaRec == null && mediaAttachmentStore != null) {
                    MediaAttachmentStore.StoredMedia legacyMedia = mediaAttachmentStore.get(sessionId, mediaId);
                    if (legacyMedia != null) {
                        String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                            ? att.mediaType() : legacyMedia.mediaType();
                        // base64=null：路径通道，仅保留 contentId 引用（下游按 MediaAttachmentStore.getPath 读磁盘）
                        resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                            mediaType, null, null));
                        if (log.isDebugEnabled()) {
                            log.debug("[attachments-v2] 媒体附件 store 兜底路径通道确认: contentId={} type={} mediaType={} path={}",
                                att.contentId(), att.type(), mediaType, legacyMedia.path());
                        }
                        continue;
                    }
                }
                if (mediaRec == null) {
                    log.warn("[attachments-v2] 媒体附件附件表+store 均未命中，跳过附件: contentId={} type={} filename={}（media-cache 路径通道）",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                    ? att.mediaType()
                    : (mediaRec.getMediaType() != null ? mediaRec.getMediaType() : "application/octet-stream");
                // base64=null：路径通道，仅保留 contentId 引用（下游按附件表 getPath 读磁盘）
                resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                    mediaType, null, null));
                if (log.isDebugEnabled()) {
                    log.debug("[attachments-v2] 媒体附件路径通道确认(附件表): contentId={} type={} mediaType={} path={}",
                        att.contentId(), att.type(), mediaType, mediaRec.getPath());
                }
                continue;
            }
            // 3) contentId → 统一附件表 contentId 解析 + image-cache 兼容回退（附件双模式 · 用户拍板 2026-09-02）
            //   [图片分流] upload/path 大图（>5MB）已注册 attachments 表 → contentId=附件表自增 id → 附件表命中 →
            //   base64=null 透传 contentId（>5MB 大图不内存直发，下游经附件表 path 做路径说明/读盘）。≤5MB 直传图
            //   F1 分配 image-cache（非附件表）→ 附件表未命中 → 回退 imageAttachmentStore.getBase64 读 base64。
            //   （附件表与 image-cache 为独立 id 空间，先查附件表不命中即视为 image-cache id，无歧义。）
            if (att.contentId() != null && !att.contentId().isBlank()) {
                long id;
                try {
                    id = Long.parseLong(att.contentId().trim());
                } catch (NumberFormatException e) {
                    log.warn("[A1 attachments] contentId 非数字，跳过附件: contentId={} type={} filename={}",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                AttachmentRecord imgRec = resolveContentIdInTable(sessionId, id, "image/");
                if (imgRec != null) {
                    // 附件表命中（upload/path 大图）→ base64=null 透传 contentId（>5MB 不直发，下游附件表 path 读盘）
                    String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                        ? att.mediaType()
                        : (imgRec.getMediaType() != null ? imgRec.getMediaType() : "image/png");
                    resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                        mediaType, null, null));
                    if (log.isDebugEnabled()) {
                        log.debug("[A1 attachments] 图片附件表解析(大图路径通道): contentId={} type={} mediaType={} path={}",
                            att.contentId(), att.type(), mediaType, imgRec.getPath());
                    }
                    continue;
                }
                if (imageAttachmentStore == null) {
                    log.warn("[A1 attachments] ImageAttachmentStore 未注入且附件表未命中，跳过附件: contentId={} type={} filename={}",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                ImageAttachmentStore.Base64Content content = imageAttachmentStore.getBase64(sessionId, id);
                if (content == null) {
                    log.warn("[A1 attachments] 图片缓存未命中（附件表+image-cache 均无）: contentId={} type={} filename={}",
                        att.contentId(), att.type(), att.filename());
                    continue;
                }
                String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                    ? att.mediaType() : content.mediaType();
                resolved.add(new AttachmentRequest(att.type(), att.contentId(), att.filename(),
                    mediaType, content.base64(), null));
                if (log.isDebugEnabled()) {
                    log.debug("[A1 attachments] 图片 image-cache 缓存读回: contentId={} type={} mediaType={}",
                        att.contentId(), att.type(), mediaType);
                }
                continue;
            }
            // 4) 无 base64 且无 contentId → 无法消费
            log.warn("[A1 attachments] 附件无 base64 且无 contentId，跳过: type={} filename={}",
                att.type(), att.filename());
        }
        log.info("[A1 attachments] 附件解析完成: session={} 请求={} 可消费={}",
            sessionId, raw.size(), resolved.size());
        return resolved;
    }

    /** [AM-CC-20260825] 剥离 dataUrl 前缀（data:image/png;base64, → 纯 base64）· 前端粘贴/选择
     *  图片传 dataUrl；CC pastedContents.content 为纯 base64（config.ts:57）。无前缀 → 原样。 */
    private static String stripDataUrlPrefix(String base64) {
        if (base64 == null || !base64.startsWith("data:")) {
            return base64;
        }
        int comma = base64.indexOf(',');
        return comma >= 0 ? base64.substring(comma + 1) : base64;
    }

    /** 是否为 PDF 附件（type=pdf 或 mediaType=application/pdf）。 */
    private static boolean isPdfAttachment(AttachmentRequest att) {
        String type = att.type();
        if (type != null && "pdf".equalsIgnoreCase(type)) {
            return true;
        }
        String mediaType = att.mediaType();
        return mediaType != null && PdfSupport.PDF_MEDIA_TYPE.equalsIgnoreCase(mediaType);
    }

    /**
     * [skill-attach-register] base64 直传 PDF（≤5MB，前端契约 ≤5MB 附件一律 base64 无 contentId）落盘 +
     * 注册附件表（统一 contentId 注册中心）· 复刻 AttachmentController.upload pdf 分支范式
     * （pdfAttachmentStore.store → attachmentService.register → contentId=附件表自增 id）。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：base64 直传 PDF 此前 resolveAttachments 仅透传 base64（无附件表
     * 注册）→ user_attachments.contentId=null → 出站 {@code resolveAttachmentUrls} 拼不出
     * {@code /attachments/content/{sessionId}/{contentId}} → F5 重拉前端无法按内容端点预览（乐观 base64
     * 仅前端内存，重拉丢失）。注册后 resolveAttachments 输出走 contentId 路径通道 → PdfAttachmentProcessor
     * resolvePathChannel 经附件表读盘分页（不再 writeBase64Channel 临时落盘 attach-uuid.pdf），语义与
     * &gt;5MB upload/path 附件统一。≤5MB 图保持 base64 直传（image-cache/imagePasteIds 独立通道，不改）。
     *
     * @param sessionId 会话 id（附件表 session_id + store 落盘目录归属）
     * @param att       原附件（type=pdf + base64 非空 + contentId 空）
     * @return 已注册附件（type=pdf + contentId + base64=null + path=store 落盘路径）；store/register 失败 →
     *         null（调用方降级保留 base64 直传）
     */
    private AttachmentRequest registerBase64Pdf(String sessionId, AttachmentRequest att) {
        if (pdfAttachmentStore == null || attachmentService == null) {
            log.warn("[attachments] base64 PDF 注册附件表依赖未注入（pdfAttachmentStore/attachmentService）→ 降级 base64 直传: filename={}",
                att.filename());
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(stripDataUrlPrefix(att.base64()));
            if (bytes.length == 0) {
                log.warn("[attachments] base64 PDF 解码为空 → 降级 base64 直传: filename={}", att.filename());
                return null;
            }
            String filename = (att.filename() != null && !att.filename().isBlank())
                ? att.filename() : "attachment.pdf";
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                PdfAttachmentStore.StoredPdf stored = pdfAttachmentStore.store(sessionId, in, bytes.length, filename);
                if (stored == null) {
                    log.warn("[attachments] base64 PDF 落盘失败（store null）→ 降级 base64 直传: filename={} size={}B",
                        filename, bytes.length);
                    return null;
                }
                String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
                    ? att.mediaType() : PdfSupport.PDF_MEDIA_TYPE;
                long contentId = attachmentService.register(sessionId, stored.path(), mediaType,
                    stored.filename(), stored.size(), "upload");
                if (log.isInfoEnabled()) {
                    log.info("[attachments] base64 直传 PDF 注册附件表成功: contentId={} filename={} size={}B "
                            + "path={}（≤5MB base64 → 附件表统一 contentId，F5 预览 url 可恢复）",
                        contentId, stored.filename(), stored.size(), stored.path());
                }
                return new AttachmentRequest("pdf", String.valueOf(contentId), stored.filename(),
                    mediaType, null, stored.path());
            }
        } catch (Exception e) {
            log.warn("[attachments] base64 PDF 注册附件表异常 → 降级 base64 直传: filename={} 原因={}",
                att.filename(), e.toString());
            return null;
        }
    }

    /** [attachments-v2 Step2] 是否为媒体附件（type=video/audio/file）。 */
    private static boolean isMediaAttachment(AttachmentRequest att) {
        String type = att.type();
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase();
        return lower.equals("video") || lower.equals("audio") || lower.equals("file");
    }

    /** [附件双模式] 附件表记录 mediaType 是否为媒体（video/* | audio/*；octet-stream 容忍——前端 file 类型未精确标注）。 */
    private static boolean isMediaRecordType(AttachmentRecord rec) {
        String mt = rec.getMediaType();
        if (mt == null) {
            return true; // 未标注 → 容忍存在性（未知即按媒体消费，下游按实际 mediaType 分流）
        }
        String lower = mt.toLowerCase();
        return lower.startsWith("video/") || lower.startsWith("audio/")
            || "application/octet-stream".equals(lower);
    }

    /**
     * [附件双模式] 已解析附件 → user_attachments 快照（type/filename/mediaType/contentId + url=null 出站投影）。
     *
     * <p>与 {@code MessageService.userAttachmentsFromAttachments} 同构，但入参为 resolveAttachments
     * <b>解析后</b>列表（path/upload 附件 contentId 已注册附件表 → 含新 contentId）；≤5MB base64 图
     * contentId=null 保持（imagePasteIds 链路不变，不并入附件表快照 url）。url 恒 null（MessageService.toDto
     * 按 contentId + sessionId 动态拼）。null/空 → 空列表（updateUserAttachments 判空跳过，不清空已落库快照）。
     *
     * @param resolved resolveAttachments 输出（已补全/已注册 contentId 的附件）
     * @return user_attachments 快照列表（可空）
     */
    private static List<ChatMessageDto.UserAttachmentInfo> userAttachmentSnapshotOf(
            List<AttachmentRequest> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        List<ChatMessageDto.UserAttachmentInfo> list = new ArrayList<>(resolved.size());
        for (AttachmentRequest att : resolved) {
            if (att == null) {
                continue;
            }
            String contentId = (att.contentId() != null && !att.contentId().isBlank())
                ? att.contentId() : null;
            list.add(new ChatMessageDto.UserAttachmentInfo(
                att.type(), att.filename(), att.mediaType(), contentId, null));
        }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [附件双模式 · 统一附件表 contentId · 用户拍板 2026-09-02] 辅助解析
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [附件双模式] path 附件校验 + 附件表注册 → 可消费 AttachmentRequest（base64=null 路径通道）。
     *
     * <p><b>校验链</b>（local-read 本地直读外部磁盘路径 = 把宿主机文件路径暴露给后端，必须严格校验）：
     * <ol>
     *   <li>扩展名白名单 {@link #PATH_ATTACHMENT_ALLOWED_EXTENSIONS}</li>
     *   <li>{@code Files.exists}（文件真实存在，非空目录）</li>
     *   <li>{@code Files.size ≤ 200MB}（{@link #PATH_ATTACHMENT_MAX_BYTES}）</li>
     *   <li>防穿越：{@code normalize} 后禁 {@code ..} 路径段 / 符号链接（防指向任意系统文件）</li>
     * </ol>
     * 任一失败 → warn 并返回 null（fail loud，调用方跳过该附件）。
     *
     * <p><b>注册</b>：{@code attachmentService.register(sessionId, path, mediaType, filename, size, "path")}
     * → contentId = attachments 自增 id（零拷贝：不复制进 store，附件表 path 记外部绝对路径）。
     *
     * @param sessionId 会话 id
     * @param att       path 附件（path 非空已由调用方保证）
     * @return 已注册附件的 AttachmentRequest（type, contentId, filename, mediaType, base64=null, path）；校验/注册失败 → null
     */
    private AttachmentRequest resolveAttachmentPath(String sessionId, AttachmentRequest att) {
        if (attachmentService == null) {
            log.warn("[attachments path] AttachmentService 未注入，跳过 path 附件: path={} type={} filename={}",
                att.path(), att.type(), att.filename());
            return null;
        }
        String rawPath = att.path();
        Path p = normalizeAndValidatePath(rawPath);
        if (p == null) {
            return null; // 校验失败已 warn（fail loud）
        }
        // 扩展名白名单
        String filename = (att.filename() != null && !att.filename().isBlank())
            ? att.filename() : p.getFileName().toString();
        String ext = extensionOf(filename);
        if (!PATH_ATTACHMENT_ALLOWED_EXTENSIONS.contains(ext)) {
            log.warn("[attachments path] path 附件扩展名不在白名单，跳过: path={} filename={} ext={}",
                rawPath, filename, ext);
            return null;
        }
        long size;
        try {
            if (!Files.exists(p) || Files.isDirectory(p)) {
                log.warn("[attachments path] path 附件不存在或为目录，跳过: path={}", rawPath);
                return null;
            }
            size = Files.size(p);
        } catch (IOException e) {
            log.warn("[attachments path] path 附件读元数据失败，跳过: path={} 原因={}", rawPath, e.toString());
            return null;
        }
        if (size > PATH_ATTACHMENT_MAX_BYTES) {
            log.warn("[attachments path] path 附件超 200MB 上限，跳过: path={} size={}B", rawPath, size);
            return null;
        }
        // mediaType 推断：优先请求体 mediaType，否则扩展名映射，最后 octet-stream
        String mediaType = (att.mediaType() != null && !att.mediaType().isBlank())
            ? att.mediaType() : PATH_EXT_TO_MEDIA_TYPE.getOrDefault(ext, "application/octet-stream");
        try {
            long contentId = attachmentService.register(sessionId, p.toString(), mediaType, filename, size, "path");
            AttachmentRequest resolved = new AttachmentRequest(att.type(), String.valueOf(contentId),
                filename, mediaType, null, p.toString());
            if (log.isDebugEnabled()) {
                log.debug("[attachments path] path 附件注册附件表: contentId={} type={} filename={} mediaType={} size={}B path={}",
                    contentId, att.type(), filename, mediaType, size, p);
            }
            return resolved;
        } catch (Exception e) {
            log.warn("[attachments path] path 附件注册附件表失败，跳过: path={} 原因={}", rawPath, e.toString());
            return null;
        }
    }

    /**
     * [附件双模式] path 绝对路径安全校验 + normalize。
     *
     * <p><b>防穿越</b>：外部绝对路径可能含 {@code ..}（越权指向白名单外文件）或为符号链接（指向任意系统
     * 文件）——normalize 后逐段检查含 {@code ..} 即拒绝；{@code Files.isSymbolicLink} 拒绝符号链接本身。
     * Windows 绝对路径 normalize 后不残留 {@code ..}（无法上卷），再显式逐段校验兜底（Unix 相对场景）。
     *
     * @param raw 原始 path（调用方保证非空）
     * @return normalize 后绝对 Path；非法 → null（内部 warn，fail loud）
     */
    private Path normalizeAndValidatePath(String raw) {
        Path p;
        try {
            p = Path.of(raw).toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("[attachments path] path 附件解析失败，跳过: path={} 原因={}", raw, e.toString());
            return null;
        }
        for (Path segment : p) {
            if ("..".equals(segment.toString())) {
                log.warn("[attachments path] path 附件含 .. 穿越段，跳过: path={} normalized={}", raw, p);
                return null;
            }
        }
        try {
            if (Files.isSymbolicLink(p)) {
                log.warn("[attachments path] path 附件为符号链接，跳过: path={}", raw);
                return null;
            }
        } catch (Exception e) {
            log.warn("[attachments path] path 附件符号链接探测失败，跳过: path={} 原因={}", raw, e.toString());
            return null;
        }
        return p;
    }

    /** 取文件名扩展名（小写，无点）· filename="a.PDF" → "pdf"；无扩展名 → "". */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    /**
     * [附件双模式] contentId → 附件表记录校验（统一解析入口 · 附件表 contentId 全局唯一）。
     *
     * <p>附件表命中且（mediaTypePrefix 为空 或 记录 mediaType 以该前缀开头）→ 返回记录；
     * 附件表未注入 / 无记录 / mediaType 前缀不符（旧 id 撞号异物，不应按目标类型消费）→ null。
     * <p><b>mediaTypePrefix 语义</b>：pdf 分支传 {@code "application/pdf"} 防撞 image-cache 旧 id；
     * media 分支传 null（video/audio 多前缀，仅存在性校验）；image 分支传 {@code "image/"}。
     *
     * @param sessionId     会话 id（预留，附件表按会话查询可后续收紧；当前 getContent 全库唯一 id）
     * @param contentId     附件表自增 id
     * @param mediaTypePrefix mediaType 前缀约束（可 null = 仅存在性校验）
     * @return 附件表记录；未命中/类型不符 → null
     */
    private AttachmentRecord resolveContentIdInTable(String sessionId, long contentId, String mediaTypePrefix) {
        if (attachmentService == null) {
            if (log.isDebugEnabled()) {
                log.debug("[attachments] AttachmentService 未注入，跳过附件表解析: contentId={}", contentId);
            }
            return null;
        }
        AttachmentRecord rec = attachmentService.getContent(contentId);
        if (rec == null) {
            return null;
        }
        if (mediaTypePrefix != null && !mediaTypePrefix.isBlank()) {
            String mt = rec.getMediaType();
            if (mt == null || !mt.toLowerCase().startsWith(mediaTypePrefix.toLowerCase())) {
                if (log.isDebugEnabled()) {
                    log.debug("[attachments] 附件表记录 mediaType 与消费类型不符，按未命中处理: contentId={} mediaType={} expectPrefix={}",
                        contentId, mt, mediaTypePrefix);
                }
                return null;
            }
        }
        return rec;
    }

    private static Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Collections.emptyMap();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(argsJson, new com.fasterxml.jackson.core.type.TypeReference<
                    Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.singletonMap("_raw", argsJson);
        }
    }

    /** In-progress 流任务。 */
    private static final class ChatTask {
        final String sessionId;
        final String userMessageId;
        final String assistantMessageId;
        final AtomicBoolean cancel = new AtomicBoolean(false);

        ChatTask(String sessionId, String userMessageId, String assistantMessageId) {
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
            this.assistantMessageId = assistantMessageId;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PR 4 helpers
    // ════════════════════════════════════════════════════════════════════════

    // ─────────────────────────── worktree resume 恢复 ───────────────────────────

    /**
     * resume（会话续聊）时从 transcript 读回 worktree-state → WorktreeCwdTracker，
     * 对齐 CC sessionRestore.ts:332-366 {@code restoreWorktreeForResume}。
     *
     * <p>CC 行为（逐行）：
     * <ol>
     *   <li>{@code getCurrentWorktreeSession()} 有 fresh 会话 → {@code saveWorktreeState(fresh)}
     *       优先，跳过 transcript 恢复（:336-339，残留 3）</li>
     *   <li>无 worktreeSession（transcript 无 worktree-state / 已退出）→ 不恢复（:340）</li>
     *   <li>{@code process.chdir(worktreePath)} 作为 TOCTOU 安全存在性校验，目录消失 →
     *       {@code saveWorktreeState(null)} 清态（:343-350）</li>
     *   <li>目录存在 → {@code setCwd(worktreePath)} + {@code setOriginalCwd(getCwd())}（:352-353），
     *       此时 {@code getCwd()=worktreePath}（chdir 后），故 <b>originalCwd = worktreePath</b>
     *       （残留 1）；随后 {@code restoreWorktreeSession(worktreeSession)} 恢复完整会话对象
     *       （:359，残留 2）</li>
     * </ol>
     *
     * <p>Java 化差异：CC {@code process.chdir} 改进程 cwd；Java 后端改
     * {@link WorktreeCwdTracker}（sessionKey=稳定 UUID 串）的 cwd/originalCwd/sessionWorktree 三态，
     * 工具执行时经 {@code getCwd/getOriginalCwd/getWorktreeSession} 读取。"进入前目录"仍保留在
     * transcript worktreeSession.originalCwd（Enter 时前端传入，Exit 回退读回），resume 不再读它
     * 覆盖 originalCwd（对齐 CC :352-353 精确语义）。
     */
    private void restoreWorktreeForResume(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // [session-id-short] transcript/WorktreeCwdTracker 键 = sessionId short 直键
        //   （EnterWorktreeTool 用 ctx.sessionId()，二者同源同值）
        String sessionKey = sessionId;
        // [RESIDUAL-FIX 残留 3] fresh 守卫：已有完整 worktree 会话对象 → 跳过 transcript 恢复
        //   （对齐 CC sessionRestore.ts:336-339 getCurrentWorktreeSession() 有值 → saveWorktreeState
        //   (fresh) 优先，不读 transcript 覆盖当前状态）。
        WorktreeCwdTracker.WorktreeSession fresh = WorktreeCwdTracker.getWorktreeSession(sessionKey);
        if (fresh != null) {
            log.info("[ChatService] resume 跳过 transcript 恢复：已有 fresh worktree 会话 "
                    + "session={} worktreePath={}", sessionId, fresh.worktreePath());
            return;
        }
        // workspaceDir 与会话 projectRoot 一致：EnterWorktreeTool.persistWorktreeState 写 transcript
        //   用 AutoMemPaths.currentSessionProjectRoot()（即 run() 入口 sessionProjectRootResolver 冻结结果）；
        //   resolver 不可解析（会话未绑定项目）→ 回落 config home（与 loop 默认 workspaceDir 同源）。
        String projectRoot = (sessionProjectRootResolver != null)
                ? sessionProjectRootResolver.apply(sessionId)
                : null;
        if (projectRoot == null || projectRoot.isBlank()) {
            projectRoot = AutoMemPaths.currentSessionProjectRoot();
        }
        Path workspaceDir = Path.of(projectRoot);
        JsonNode worktreeSession = SessionStorage.readWorktreeState(workspaceDir, sessionKey);
        if (worktreeSession == null) {
            // 无 worktree 状态（从未进入 / 已退出）→ 不恢复（CC :340）
            return;
        }
        JsonNode worktreePathNode = worktreeSession.get("worktreePath");
        if (worktreePathNode == null || !worktreePathNode.isTextual()) {
            return;
        }
        String worktreePathStr = worktreePathNode.asText();
        if (worktreePathStr.isBlank()) {
            return;
        }
        Path worktreePath = Path.of(worktreePathStr);
        if (!Files.isDirectory(worktreePath)) {
            // 目录已消失 → 清 tracker（CC :343-350 process.chdir 抛 ENOENT → saveWorktreeState(null)）
            WorktreeCwdTracker.clearCwd(sessionKey);
            WorktreeCwdTracker.clearOriginalCwd(sessionKey);
            WorktreeCwdTracker.clearWorktreeSession(sessionKey);
            log.warn("[ChatService] resume 恢复 worktree 目录已消失，清 tracker: session={} worktreePath={}",
                    sessionId, worktreePathStr);
            return;
        }
        // [RESIDUAL-FIX 残留 1] 对齐 CC :352-353 setCwd(worktreePath) + setOriginalCwd(getCwd())，
        //   此时 getCwd() = worktreePath（chdir 后），故 originalCwd = worktreePath（而非 transcript
        //   里的"进入前目录"——后者仍保留在 transcript 供 Exit 回退读回）。
        WorktreeCwdTracker.setCwd(sessionKey, worktreePath);
        WorktreeCwdTracker.setOriginalCwd(sessionKey, worktreePathStr);
        // [RESIDUAL-FIX 残留 2] 对齐 CC :359 restoreWorktreeSession(worktreeSession) 恢复完整会话
        //   对象（worktreePath/worktreeBranch/worktreeName/hookBased/sessionId）。
        WorktreeCwdTracker.setWorktreeSession(sessionKey, buildWorktreeSession(worktreeSession,
                sessionKey, worktreePathStr));
        log.info("[ChatService] resume 恢复 worktree cwd: session={} worktreePath={} originalCwd={}",
                sessionId, worktreePathStr, worktreePathStr);
    }

    /**
     * [RESIDUAL-FIX 残留 2] 从 transcript worktreeSession JSON 解析完整 worktree 会话对象 ·
     * 对齐 CC worktree.ts:140-154 {@code WorktreeSession}（worktreeBranch/worktreeName 等
     * optional 字段缺失时为 null / 默认值）。
     */
    private WorktreeCwdTracker.WorktreeSession buildWorktreeSession(JsonNode worktreeSession,
                                                                    String sessionKey,
                                                                    String worktreePathStr) {
        String worktreeBranch = (worktreeSession.get("worktreeBranch") != null
                && worktreeSession.get("worktreeBranch").isTextual())
                ? worktreeSession.get("worktreeBranch").asText() : null;
        String worktreeName = (worktreeSession.get("worktreeName") != null
                && worktreeSession.get("worktreeName").isTextual())
                ? worktreeSession.get("worktreeName").asText() : null;
        boolean hookBased = worktreeSession.path("hookBased").asBoolean(false);
        return new WorktreeCwdTracker.WorktreeSession(worktreePathStr, worktreeBranch, worktreeName,
                hookBased, sessionKey);
    }
}

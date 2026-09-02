package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.SessionKeys;
import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.PowerShellTool;
import com.nexusai.application.agent.bash.DestructiveCommandWarning;
import com.nexusai.application.agent.bash.SedEditParser;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.domain.session.MessageService;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import com.nexusai.eventbus.ws.PermissionExplanationEvent;
import com.nexusai.model.session.dto.ChatMessageDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket STOMP 实现的 {@link PermissionPrompter} · PR 4 唯一实现。
 *
 * <h2>工作原理</h2>
 * <p>当 {@link com.nexusai.application.agent.LlmAgentLoop} 检测到 {@link PermissionResult.Ask} 时调用本类的
 * {@link #prompt}，步骤：
 * <ol>
 *   <li>分配 {@code requestId}（通常 = {@code ToolUseBlock.id}），创建对应
 *       {@link CompletableFuture}{@code <PermissionResult>} 存入 {@link #pending}，
 *       并创建原子 claim 守卫（{@link #raceClaims}）—— 对齐 CC createResolveOnce.claim()</li>
 *   <li>通过 {@link SimpMessagingTemplate} 把 {@link MessagePermissionRequestEvent} 推到
 *       {@code /topic/sessions/{sessionId}/permission-requests}（队列 push）</li>
 *   <li>[canUseTool v3] 启动后台竞速 racer（hook / classifier / bridge / channel 四路），
 *       与本地用户响应（{@link #onResponse}）竞速 —— 首个 claim 获胜，其余忽略（对齐
 *       CC interactiveHandler.ts:57-531 + PermissionContext.ts:75-94 createResolveOnce）</li>
 *   <li>阻塞等 future 完成 —— 一旦任一 racer claim，立即返回，不再固定等待用户 30s</li>
 *   <li>超时（默认 30s）→ 返回 {@link PermissionResult.Deny(timeout)} —— 仅作为纯用户决策
 *       floor（CC 本地弹窗是兜底，interactiveHandler.ts:315）；自动化决策无需等待</li>
 *   <li>中断 → 返回 {@link PermissionResult.Deny(interrupt)}</li>
 * </ol>
 *
 * <h2>线程模型</h2>
 * <p>{@link #prompt} 在 {@link com.nexusai.application.agent.LlmAgentLoop} 的工作线程
 * （{@code chatExecutor} 线程池）上阻塞 —— 这意味着会占用一个 worker 线程直到首个 racer 决策。
 * 这是有意的设计（Java 同步工具执行循环需要"等决策后再继续"），但相比 v2 的"固定等用户 30s"，
 * 现在 hook / bridge / channel 的自动化决策会立刻 resolve，不再占用完整 30s。
 * 后台 racer 在 {@link #RACERS} daemon 线程池上运行，不占用 chatExecutor worker。
 *
 * <h2>三路竞速（canUseTool v3）</h2>
 * <ul>
 *   <li><b>local（用户 STOMP 响应）</b> — {@link #onResponse}，前端 allow/deny</li>
 *   <li><b>hook</b> — {@link #startHookRace} 后台执行 PermissionRequest hooks
 *       （CC interactiveHandler.ts:411-431 {@code ctx.runHooks}），
 *       deny / blockingError / stop → 立即拒绝</li>
 *   <li><b>bridge / channel</b> — 注入式 {@link BridgePermissionCallbacks} /
 *       {@link ChannelPermissionCallbacks}（CC :244-298 / :316-407）。[canUseTool v4]
 *       生产 @Component 实现 {@link StompBridgePermissionCallbacks} /
 *       {@link StompChannelPermissionCallbacks} 已接线（STOMP 出站 + inbound resolve），
 *       竞速真正参与生产；远程表面是否在线由部署决定（不在线则用户/本地决策兜底）</li>
 * </ul>
 *
 * <p>classifier 竞速（CC interactiveHandler.ts:433-530 executeAsyncClassifierCheck）随
 * CC 外部构建 BASH_CLASSIFIER 恒禁用（bashClassifier.ts stub）而停用：Java 无启发式
 * BashClassifier → pendingClassifierCheck 生产恒 null，无竞速载体。
 *
 * <h2>并发安全</h2>
 * <ul>
 *   <li>{@link #pending} 用 {@link ConcurrentHashMap} —— 多线程可并发 put/remove</li>
 *   <li>{@link #onResponse} 用 {@code remove()} 原子取走 future，配合 {@link #raceClaims}
 *       的 {@code claim()} 原子 check-and-mark，避免重复 complete（幂等）</li>
 *   <li>Future 完成是无副作用的（一次 {@code complete(...)} 后再次 complete 被忽略）</li>
 * </ul>
 *
 * @see PermissionPrompter
 * @see MessagePermissionRequestEvent
 * @see com.nexusai.apis.permission.PermissionController
 */
@Component
public class WebSocketPermissionPrompter implements PermissionPrompter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPermissionPrompter.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_FACTORY =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Phase 1 默认超时 30 秒 —— 仅作为纯用户决策 floor（自动化竞速决策立即返回）。
     * 可通过 {@link #WebSocketPermissionPrompter(SimpMessagingTemplate, long)} 注入。
     */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * 后台竞速线程池 · daemon，不阻止 JVM 退出，供 hook / bridge / channel racer。
     */
    private static final java.util.concurrent.ExecutorService RACERS =
        java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "permission-racer");
            t.setDaemon(true);
            return t;
        });

    private final SimpMessagingTemplate ws;
    private final long timeoutMs;

    /**
     * §14: Hook 注册中心（PermissionRequest / PermissionDenied hooks）。
     * null → 无 hook（向后兼容）。
     */
    @Autowired(required = false)
    private HookRegistry hookRegistry;

    /**
     * [canUseTool v3 + v4] bridge 竞速回调（CCR/claude.ai 远程弹窗）· 生产 @Component
     * {@link StompBridgePermissionCallbacks} 已接线（v4 修复 v3 缺口①：无实现→注入 null）。
     */
    @Autowired(required = false)
    private BridgePermissionCallbacks bridgeCallbacks;

    /**
     * [canUseTool v3 + v4] channel 竞速回调（Telegram/iMessage/Discord）· 生产 @Component
     * {@link StompChannelPermissionCallbacks} 已接线（v4 修复 v3 缺口①）。
     */
    @Autowired(required = false)
    private ChannelPermissionCallbacks channelCallbacks;

    /**
     * [impl-I-3 T6] channel relay 门控数据源 · CC original: {@code isChannelPermissionRelayEnabled()}
     * （channelPermissions.ts:36-38，GrowthBook 'tengu_harbor_permissions' 默认 false）。
     * Java 配置 {@code nexusai.mcp.channels-permission-relay-enabled}（默认 false）→ 跳过整个
     * channel relay 竞速（CC「One gate, full disable」useManageMCPConnections.ts:188）。null
     * （测试直构 / 未注入）→ 视为开启（保留既有 STOMP channel 竞速测试语义）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.mcp.config.McpProperties mcpProperties;

    /**
     * [impl-I-3 T6] 合格 channel server 数据源 · CC original: {@code getAppState().mcp.clients}
     * （interactiveHandler.ts:323）。Java 数据源 = {@link McpToolPool}（activeServers +
     * getServerCapabilities）。null（未接线 / 测试）→ 跳过 filter（向后兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.mcp.McpToolPool channelRelayServerSource;

    /**
     * [impl-I-3 T6] relay 白名单判定 · CC original: {@code isInAllowlist(name) =>
     * findChannelEntry(name, allowedChannels) !== undefined}（interactiveHandler.ts:323-325）。
     * Java 无 session --channels 会话态 → 用 DB ledger（{@link ChannelAllowlistService#isAllowlisted}）
     * 近似，默认 fail-closed（无服务 → 不通过）。
     */
    @Autowired(required = false)
    private com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService channelAllowlistService;

    /** [impl-I-3 T6] relay 白名单判定函数（name → in allowlist）· 测试注入覆盖默认 DB 判定。 */
    private java.util.function.Function<String, Boolean> relayAllowlistChecker;

    /**
     * [OPD-WF8-02-07] ChannelPermission.isEnabled 门控数据源 · 可配置、默认关闭（对齐 CC
     * 默认 false，非硬编码 {@code () -> true}）。镜像 CC {@code isChannelPermissionRelayEnabled()}
     * （channelPermissions.ts:36-37，GrowthBook 'tengu_harbor_permissions' 缺省 false）。
     */
    @Autowired(required = false)
    private ChannelPermissionFeature channelPermissionFeature;

    /** [impl-I-3 T6] filterPermissionRelayClients 执行器（CC channelPermissions.ts:177-194 4 判定）。 */
    private final com.nexusai.application.agent.security.ChannelPermission channelPermission =
        new com.nexusai.application.agent.security.ChannelPermission(this::channelPermissionRelayEnabled);

    /**
     * [OPD-WF8-02-07] ChannelPermission.isEnabled 门控读取 · 配置 {@code nexusai.feature.channel-permission}
     * （默认 false，对齐 CC channelPermissions.ts:36-38 默认 false）。feature 未注入（测试直构）→
     * 默认关闭（对齐 CC 默认 false，不再保留 {@code () -> true} 语义）。
     */
    private boolean channelPermissionRelayEnabled() {
        return channelPermissionFeature != null && channelPermissionFeature.isEnabled();
    }

    /**
     * 待响应的 future 映射：{@code requestId → CompletableFuture<PermissionResult>}。
     *
     * <p>WHY {@link ConcurrentHashMap}：
     * <ul>
     *   <li>同一 session 的多 tool_call 并发权限检查</li>
     *   <li>前端响应回调 {@link #onResponse} 来自 STOMP inbound 线程</li>
     *   <li>{@link #prompt} 调用方来自 {@code chatExecutor} 线程池</li>
     * </ul>
     * 三类线程并发读写，必须线程安全。
     */
    private final Map<String, CompletableFuture<PermissionResult>> pending =
        new ConcurrentHashMap<>();

    /**
     * [canUseTool v3] 每请求的原子 claim 守卫 · 对齐 CC createResolveOnce.claim()
     * （PermissionContext.ts:75-94）。value = AtomicBoolean（false=未 claim，true=已 claim）。
     *
     * <p>WHY 独立 map 不塞进 future：{@code pending} 的 future 是通用
     * {@code CompletableFuture<PermissionResult>}（兼容既有注入测试），claim 守卫是
     * 竞速模型专用簿记，独立 map 让 {@link #onResponse} / abort listener / 后台 racer
     * 都能原子 check-and-mark。
     */
    private final Map<String, AtomicBoolean> raceClaims = new ConcurrentHashMap<>();

    /**
     * 暂存每个 prompt 调用方的 input —— {@link #onResponse} 完成 future 时
     * 构造 {@link PermissionResult.Allow} 需要 updatedInput（非 null）。
     */
    private final Map<String, JsonNode> promptInputs = new ConcurrentHashMap<>();

    /**
     * [Session S16] 暂存每个 prompt 调用方的 {@link ToolUseContext} —— onResponse 收到
     * updatedPermissions 时需要 permissionContext（apply 基座）+ setAppState（appState 同步）；
     * 对齐 CC interactiveHandler.ts 闭包捕获 ctx（:57-70）。
     */
    private final Map<String, ToolUseContext> promptContexts = new ConcurrentHashMap<>();

    /**
     * [RV-07] prompt requestId → bridge 随机 requestId 映射 · 本地 racer 胜出（onResponse）
     * 时向 bridge 远程表面回传 dismiss（对齐 CC interactiveHandler.ts:140-144/162-168/186-192
     * 本地 onAbort/onAllow/onReject 的 sendResponse + cancelRequest）。
     */
    private final Map<String, String> bridgeRequestIds = new ConcurrentHashMap<>();

    /**
     * [Session S16] 权限更新 Applier（CC {@code applyPermissionUpdates}，
     * PermissionUpdate.ts:196-206）。{@code null} = 未注入（旧测试构造路径）→
     * updatedPermissions 应用跳过（仅日志）。
     */
    @Autowired(required = false)
    private PermissionUpdateApplier permissionUpdateApplier;

    /**
     * [Session S16] 权限更新 Persister（CC {@code persistPermissionUpdates}，
     * PermissionUpdate.ts:349-353，合并写回由 S01 交付）。{@code null} = 未注入 →
     * updatedPermissions 持久化跳过（仅日志）。
     */
    @Autowired(required = false)
    private PermissionUpdatePersister permissionUpdatePersister;

    /**
     * [WF-11 · OPD-WF8-01-T3] serializeDecisionReason classifier 门控 · CC original:
     * {@code feature('BASH_CLASSIFIER') || feature('TRANSCRIPT_CLASSIFIER')}
     * （structuredIO.ts:69-74）。{@code null}（未接线 / 测试直构）→ 门控关闭（classifier 序列化为 undefined）。
     */
    @Autowired(required = false)
    private BashClassifierFeature bashClassifierFeature;

    /**
     * [WF-11 · OPD-WF8-01-T3] TRANSCRIPT_CLASSIFIER 特性开关 · CC original:
     * {@code feature('TRANSCRIPT_CLASSIFIER')}。默认 true（M3.4，application.yml 同源）。
     * 仅 Spring 注入生效；测试直构 {@code new WebSocketPermissionPrompter(...)} 时字段保持 Java
     * 默认 false → 门控关闭。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.classifier.transcript.enabled:true}")
    private boolean transcriptClassifierEnabled;

    /**
     * [IMP-H R1] {@code tengu_destructive_command_warning} feature 门控 · CC original:
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false) ?
     * getDestructiveCommandWarning(command) : null}（BashPermissionRequest.tsx:274）。
     * 默认 false（外部构建默认关 → destructiveWarning 恒 null，对齐 CC 外部构建语义）。
     * {@code null}（未接线 / 测试直构）→ {@link FeatureFlags#ALL_DISABLED}（门控关闭）。
     * 仅 Spring 注入生效；测试直构 {@code new WebSocketPermissionPrompter(...)} 时字段保持
     * ALL_DISABLED → 破坏性命令分支恒 null。
     */
    @Autowired(required = false)
    private FeatureFlags featureFlags = FeatureFlags.ALL_DISABLED;

    /**
     * [WF-11 · OPD-WF8-01-T5] SDK 事件队列 · 对齐 CC {@code enqueueSdkEvent}（sdkEventQueue.ts:77-87）。
     * {@code null}（未接线 / 测试直构）→ 会话态 running 通知跳过（不阻断权限流程）。
     */
    @Autowired(required = false)
    private SdkEventQueue sdkEventQueue;

    /**
     * [WF3-04 explainer] 权限解释器 · 对齐 CC permissionExplainer.ts（经
     * PermissionExplanation.tsx Ctrl+E 惰性触发）。null = 未注入（测试直构 / 解释器关闭）
     * → 解释能力跳过。
     *
     * <p>CC 为 UI 惰性触发（用户按 Ctrl+E 才调 generatePermissionExplanation）；
     * Java 无 UI 模块，本字段建立接线点，惰性 STOMP 请求通道由前端契约确认后接入
     * （见探查 progress WF3-04-plan.md §5 concern）。{@link #explainPermissionRequest}
     * 为惰性入口的 Java 等价（对齐 CC createExplanationPromise）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.explainer.PermissionExplainer permissionExplainer;

    /**
     * [REV-FIX-5 缝隙3] 会话消息源 · {@link #explainAndSend} 取对话历史传给 explainer
     * （CC {@code generatePermissionExplanation} 的 {@code messages?} 参数，
     * permissionExplainer.ts:39/163）。null（未注入 / 测试直构）→ messages 传空列表
     * （CC messages? 可选语义一致）。
     */
    @Autowired(required = false)
    private MessageService messageService;

    /**
     * Spring 注入构造器（生产用）。
     *
     * @param ws STOMP 推送模板（{@code @Autowired(required=false)} 容错 —— 无 WebSocket 场景也能注入）
     */
    @Autowired
    public WebSocketPermissionPrompter(SimpMessagingTemplate ws) {
        this(ws, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 测试用构造器 —— 允许注入自定义超时。
     *
     * <p><b>超时已移除</b>（对齐 CC 无限等待 · 2026-08-24）：{@code timeoutMs} 不再用于阻塞等待
     * （原 {@code future.get(timeoutMs, ...)} 改为 {@code future.get()}），保留构造参数仅为兼容测试
     * 注入。CC 权限请求无超时（PermissionContext Promise 挂起直至用户 allow/deny 或 abort，
     * bashPermissions.js 无 timeout；原 30s 超时会与前端"离开很久仍在等待"行为矛盾，已移除）。
     *
     * @param ws        STOMP 推送模板
     * @param timeoutMs 保留兼容参数（不再用于等待；> 0 校验不变）
     */
    public WebSocketPermissionPrompter(SimpMessagingTemplate ws, long timeoutMs) {
        if (ws == null) {
            throw new IllegalArgumentException("SimpMessagingTemplate is null");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0, got " + timeoutMs);
        }
        this.ws = ws;
        this.timeoutMs = timeoutMs;
    }

    /**
     * [WF3-04 explainer] 惰性权限解释入口 · 对齐 CC PermissionExplanation.tsx Ctrl+E
     * 惰性触发 {@code generatePermissionExplanation}（permissionExplainer.ts:147-250）。
     *
     * <p>解释器未注入 / 门控关闭 / 生成失败 → null（CC 无降级语义）。[REV-FIX-5 缝隙3]
     * 惰性 STOMP 通道已接入：{@link #explainAndSend} 由
     * {@link com.nexusai.apis.permission.PermissionController#handlePermissionExplain}
     * 调起，前端点弹窗"解释"→ {@code /app/sessions/{sessionId}/permission-explain} → 本方法
     * 生产可达，结果经 {@code /topic/sessions/{sessionId}/permission-explanations} 推送
     * {@link PermissionExplanationEvent}（四字段或 unavailable）。
     *
     * @param sessionId       会话 UUID（解析会话主循环模型源；可 null = 无会话态 → explainer 返回 null）
     * @param toolName        工具名
     * @param input           工具输入
     * @param toolDescription 工具描述（可 null）
     * @param messages        对话历史（可 null/空）
     * @param signal          取消信号（可 null）
     * @return 权限解释；不可用/失败 → null
     */
    public com.nexusai.application.agent.permission.explainer.PermissionExplanation explainPermissionRequest(
            String sessionId,
            String toolName,
            JsonNode input,
            String toolDescription,
            java.util.List<com.nexusai.model.session.dto.ChatMessageDto> messages,
            AbortController signal) {
        if (permissionExplainer == null || !permissionExplainer.isPermissionExplainerEnabled()) {
            return null;
        }
        try {
            return permissionExplainer.generatePermissionExplanation(
                sessionId, toolName, input, toolDescription, messages, signal);
        } catch (Exception e) {
            log.warn("权限解释生成失败（返回 null，对齐 CC 无降级）", e);
            return null;
        }
    }

    /**
     * [REV-FIX-5 缝隙3] STOMP 生产入口 · 前端弹窗"解释"→ {@code /app/sessions/{sessionId}/permission-explain}
     * → 本方法 → {@code /topic/sessions/{sessionId}/permission-explanations} 推送解释事件。
     *
     * <p>对齐 CC {@code createExplanationPromise}（PermissionExplanation.tsx:77-85）：
     * <ul>
     *   <li><b>惰性</b>：仅在用户请求时生成（不预取 token）；</li>
     *   <li><b>异步</b>：生成在 {@link #RACERS} daemon 线程执行（CC promise 不阻塞 UI
     *       主线程；Java 不阻塞 STOMP inbound 线程 —— explainer 是阻塞 LLM sideQuery
     *       等价调用）；</li>
     *   <li><b>signal 恒不 abort</b>：传 {@code null}（CC {@code new AbortController().signal}，
     *       PermissionExplanation.tsx:83 "Won't abort"）；</li>
     *   <li><b>null 语义</b>：explainer 未注入 / 门控关闭 / 生成失败 → 推送
     *       {@link PermissionExplanationEvent#unavailable}（CC「Explanation unavailable」:161-166）。</li>
     * </ul>
     *
     * <p>messages 来源 = {@link MessageService#listBySession}（explainer 内部
     * {@code extractConversationContext} 取最近 3 条 assistant）；messageService 未注入
     * → 空列表（CC messages? 可选语义）。推送失败仅日志不抛（对齐
     * {@link #onResponse} 容错 —— 不向前端回 error frame）。
     *
     * @param sessionId       会话 ID（STOMP topic 路由 + 消息源）
     * @param requestId       关联 {@code MessagePermissionRequestEvent.requestId}（透传回事件）
     * @param toolName        待解释工具名（CC {@code toolName}，permissionExplainer.ts:36）
     * @param input           工具输入（CC {@code toolInput}，permissionExplainer.ts:37）
     * @param toolDescription 工具描述（可 null，CC {@code toolDescription?}，permissionExplainer.ts:38）
     */
    public void explainAndSend(String sessionId, String requestId, String toolName,
                               JsonNode input, String toolDescription) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("PERMISSION explain: sessionId is blank, ignoring requestId={}", requestId);
            return;
        }
        if (requestId == null || requestId.isBlank()) {
            log.warn("PERMISSION explain: requestId is blank, ignoring sessionId={}", sessionId);
            return;
        }
        if (toolName == null || toolName.isBlank()) {
            log.warn("PERMISSION explain: toolName is blank, ignoring sessionId={} requestId={}",
                sessionId, requestId);
            return;
        }
        // 取会话消息（explainer 上下文）· messageService 未注入/异常 → 空列表（CC messages? 可选）
        final List<ChatMessageDto> messages = fetchSessionMessages(sessionId);
        final String topic = explanationTopicFor(sessionId);
        // [F3C-MODEL] explainer 经 SessionAgentStateRegistry 读会话主循环模型（AgentState.currentModel =
        //   CC options.mainLoopModel）；[session-id-short] sessionId 已 short 直键（原 short→parseSessionUuid(null)
        //   恒失效使 explainer 模型源恒 null，本次根治）
        // 生成在 RACERS daemon 线程执行（CC promise 异步语义，不阻塞 STOMP inbound）
        RACERS.execute(() -> {
            try {
                // signal = null（CC new AbortController().signal，恒不 abort）
                com.nexusai.application.agent.permission.explainer.PermissionExplanation explanation =
                    explainPermissionRequest(sessionId, toolName, input, toolDescription, messages, null);
                PermissionExplanationEvent event = PermissionExplanationEvent.of(sessionId, requestId, explanation);
                if (event.isAvailable()) {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION explain → STOMP topic={} requestId={} tool={} riskLevel={}",
                            topic, requestId, toolName, event.getRiskLevel());
                    }
                } else {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION explain → unavailable（对齐 CC Explanation unavailable）"
                            + " topic={} requestId={} tool={}", topic, requestId, toolName);
                    }
                }
                ws.convertAndSend(topic, event);
            } catch (Throwable th) {
                // 推送失败仅日志 —— 不向 STOMP inbound 回 error frame（对齐 onResponse 容错）
                log.error("PERMISSION explain push failed: sessionId={} requestId={} tool={} err={}",
                    sessionId, requestId, toolName, th.toString());
            }
        });
    }

    /**
     * 构造权限解释 STOMP topic 路径（{@code /topic/sessions/{sessionId}/permission-explanations}）。
     *
     * <p>WHY 暴露为静态：测试可断言"实际推送的解释 topic 路径格式正确"（镜像
     * {@link #topicFor}）。
     */
    public static String explanationTopicFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is blank");
        }
        // [session-id-short] sessionId 已 short 恒等直拼（originalKey 反解已删）
        return "/topic/sessions/" + sessionId + "/permission-explanations";
    }

    /**
     * [REV-FIX-5 缝隙3] 会话消息源 · explainer 的 {@code messages?} 参数
     * （CC generatePermissionExplanation messages，permissionExplainer.ts:39/163）。
     *
     * <p>messageService 未注入（测试直构 / 无 bean）或拉取异常（会话不存在 / DB 故障）
     * → 返回空列表，不阻断解释（CC messages? 可选语义，无降级文案）。
     *
     * @param sessionId 会话 ID
     * @return 会话消息列表（explainer 内部 extractConversationContext 自动取最近 3 条 assistant）
     */
    private List<ChatMessageDto> fetchSessionMessages(String sessionId) {
        if (messageService == null) {
            return List.of();
        }
        try {
            return messageService.listBySession(sessionId);
        } catch (Exception e) {
            log.warn("PERMISSION explain: 会话消息拉取失败（降级空列表）sessionId={} err={}",
                sessionId, e.toString());
            return List.of();
        }
    }

    /**
     * [canUseTool v3] 测试注入 · 装配后台 bridge / channel 竞速 racer（classifier 竞速
     * 随 O18 删除）。生产由 @Autowired 注入（Spring 上下文）。
     */
    void wireRacersForTesting(BridgePermissionCallbacks bridge, ChannelPermissionCallbacks channel) {
        this.bridgeCallbacks = bridge;
        this.channelCallbacks = channel;
    }

    /**
     * [canUseTool v3] 测试注入 hookRegistry（后台 hook 竞速 racer）。
     */
    void setHookRegistryForTesting(HookRegistry registry) {
        this.hookRegistry = registry;
    }

    /**
     * [IMP-H R1] 测试注入 FeatureFlags（destructiveCommandWarning 门控数据源）·
     * 生产由 @Autowired 注入；测试直构 {@code new WebSocketPermissionPrompter(...)} 时字段保持
     * {@link FeatureFlags#ALL_DISABLED} → 破坏性命令分支恒 null（对齐 CC 外部构建默认关）。
     */
    void setFeatureFlagsForTesting(FeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    /**
     * [REV-FIX-5 缝隙3] 测试注入 permissionExplainer（解释器；生产由 @Autowired 注入）。
     * 镜像 {@link #setHookRegistryForTesting}。
     */
    void setPermissionExplainerForTesting(
            com.nexusai.application.agent.permission.explainer.PermissionExplainer explainer) {
        this.permissionExplainer = explainer;
    }

    /**
     * [REV-FIX-5 缝隙3] 测试注入 messageService（会话消息源；生产由 @Autowired 注入）。
     * null → {@link #explainAndSend} 传空消息列表（CC messages? 可选语义）。
     */
    void setMessageServiceForTesting(MessageService service) {
        this.messageService = service;
    }

    /**
     * [Session S16] 测试注入 · 装配权限更新 apply + persist 管线
     * （生产由 @Autowired 注入）。
     */
    void wireUpdatePipelineForTesting(PermissionUpdateApplier applier,
                                      PermissionUpdatePersister persister) {
        this.permissionUpdateApplier = applier;
        this.permissionUpdatePersister = persister;
    }

    /**
     * [impl-I-3 T6] 注入 channel relay 门控数据源（McpProperties → channelsPermissionRelayEnabled）。
     * null → 视为开启（保留既有 STOMP channel 竞速测试语义）。
     */
    public void setMcpProperties(com.nexusai.application.agent.mcp.config.McpProperties mcpProperties) {
        this.mcpProperties = mcpProperties;
    }

    /**
     * [impl-I-3 T6] 注入合格 channel server 数据源（McpToolPool）· null → 跳过 filter（向后兼容）。
     */
    public void setChannelRelayServerSource(com.nexusai.application.agent.mcp.McpToolPool pool) {
        this.channelRelayServerSource = pool;
    }

    /**
     * [impl-I-3 T6] 注入 relay 白名单判定函数（name → in allowlist）· 覆盖默认 DB ledger 判定。
     */
    public void setChannelRelayAllowlistChecker(java.util.function.Function<String, Boolean> checker) {
        this.relayAllowlistChecker = checker;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PermissionPrompter 实现
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public PermissionResult prompt(Tool tool,
                                   JsonNode input,
                                   PermissionDecisionReason reason,
                                   ToolUseContext ctx,
                                   String requestId) {
        return prompt(tool, input, reason, ctx, requestId, PermissionPromptDetails.none());
    }

    /**
     * [canUseTool v2 + v3] 带展示细节的弹窗询问 · 对齐 CC useCanUseTool.tsx:56-60
     * {@code await tool.description(input, ...)} + interactiveHandler.ts:250-253
     * （description / suggestions / blockedPath 进 STOMP 事件）。
     *
     * <p>[canUseTool v3] 竞速模型：queue push（STOMP）后启动 hook / classifier /
     * bridge / channel 后台 racer，与用户响应竞速 —— 首个 claim 获胜，自动化决策
     * 不再固定阻塞 30s。
     */
    @Override
    public PermissionResult prompt(Tool tool,
                                   JsonNode input,
                                   PermissionDecisionReason reason,
                                   ToolUseContext ctx,
                                   String requestId,
                                   PermissionPromptDetails details) {
        if (tool == null) {
            throw new IllegalArgumentException("tool is null");
        }
        if (input == null) {
            throw new IllegalArgumentException("input is null");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("ctx is null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is blank");
        }
        // [Session WF3-02 A4] ⊕-6 双通道澄清（documentation-only）：
        //   本分支 isNonInteractiveSession 是 <b>session 级</b> 兜底 —— MCP/SDK/print 等
        //   无 STOMP 终端的会话整体跳过弹窗（CC isNonInteractiveSession 语义）。
        //   而 A4 的 shouldAvoidPermissionPrompts 是 <b>subagent 级</b>（async 后台 agent，
        //   ToolPermissionGate.applyHeadlessDecision 消费）：先跑 PermissionRequest hooks，
        //   无 hook 决策才 auto-deny asyncAgent。两者不冲突 —— A4 在 gate 层先于本
        //   interactive 分支触发（headless ask 决策已在 gate 内转 deny/allow，不会到达
        //   prompter），故不会"双重拒绝"。本分支保留是为非 subagent 的非交互会话兜底。
        if (ctx.isNonInteractiveSession()) {
            log.info("权限提示静默拒绝：非交互会话跳过 STOMP requestId={}", requestId);
            return new PermissionResult.Deny(
                "Permission prompt unavailable in non-interactive session",
                new PermissionDecisionReason.Other("non_interactive_session"),
                null);
        }
        log.info("PERMISSION prompt: tool={} requestId={} reason={}",
            tool.name(), requestId, reason == null ? "(null)" : reason.getClass().getSimpleName());

        // ── [canUseTool v3] 竞速模型 · 对齐 CC interactiveHandler.ts:57-232 + createResolveOnce ──
        // 1) future + 原子 claim 守卫注册
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean(false);
        CompletableFuture<PermissionResult> existing = pending.put(requestId, future);
        raceClaims.put(requestId, claimed);
        // 同步暂存 input（onResponse 需要它构造 Allow.updatedInput）
        promptInputs.put(requestId, input);
        // [Session S16] 同步暂存 ctx（onResponse 需要 permissionContext 做 updatedPermissions
        //   apply + appState 同步；对齐 CC interactiveHandler 闭包捕获 ctx）
        promptContexts.put(requestId, ctx);
        if (existing != null) {
            log.warn("PERMISSION prompt: requestId={} already has a pending future, overwriting",
                requestId);
            existing.complete(new PermissionResult.Deny(
                "Replaced by newer request with same id",
                new PermissionDecisionReason.Other("request_id_collision"),
                null));
        }

        // 1.5) abort 监听 — 取消信号立即完成 future（对齐 CC interactiveHandler.ts:137-153
        //      onAbort → logCancelled + cancelAndAbort; 避免中止后仍阻塞满超时）。
        //      claim 守卫: 竞速已结束（用户/hook/classifier 已决策）则忽略 abort。
        if (ctx.abortController() != null && ctx.abortController() != AbortController.NOOP) {
            ctx.abortController().onCancel(ac -> {
                // 幂等: future.complete 二次调用被忽略; pending.remove 重复执行无害
                pending.remove(requestId);
                raceClaims.remove(requestId);
                if (claimed.compareAndSet(false, true)) {
                    future.complete(new PermissionResult.Deny(
                        PermissionRejectMessages.buildRejectMessage(
                            ctx.agentType() != null, null),
                        new PermissionDecisionReason.Other("user_abort"),
                        requestId));
                }
            });
        }

        // 2) STOMP 推送到前端（队列 push）· [canUseTool v2] 携带 description/suggestions/blockedPath
        //    （对齐 CC useCanUseTool.tsx:56-60 + interactiveHandler.ts:250-253）
        //    [G21] 携带 warning（权限弹窗渲染文本）· sed 编辑 / 破坏性命令警告随 STOMP 推送（非 API）
        try {
            // [session-id-short] ctx.sessionId() 已 short，恒等直传（不再 originalKey 反解）
            String topic = topicFor(ctx.sessionId());
            String eventSessionId = ctx.sessionId();
            String description = details != null && details.description() != null
                ? details.description()
                : describeFallback(tool, input);
            List<PermissionUpdate> suggestions = details != null ? details.suggestions() : List.of();
            String blockedPath = details != null ? details.blockedPath() : null;
            String warning = renderPermissionWarning(tool, input);
            MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
                eventSessionId,
                requestId,
                resolveToolUseId(ctx, requestId),
                tool.name(),
                input,
                reason == null ? null : reason,
                description,
                suggestions,
                blockedPath,
                warning,
                isClassifierFeatureEnabled()
            );
            ws.convertAndSend(topic, event);
            // [2026-08-26 AskUserQuestion 诊断] 记录推送的 toolInput 是否含 questions——
            //   前端 AskUserForm 靠 toolInput.questions 渲染选择题（缺失→通用授权弹窗）
            if (log.isInfoEnabled()) {
                log.info("PERMISSION STOMP → topic={} requestId={} tool={} hasDescription={} hasWarning={}"
                        + " toolInputHasQuestions={} toolInputAbbrev={}",
                    topic, requestId, tool.name(), description != null, warning != null,
                    input != null && input.has("questions"),
                    input != null ? (input.size() <= 100 ? input.toString() : input.toString().substring(0, 100) + "...") : "null");
            }
        } catch (Exception e) {
            // STOMP 推送失败 —— 立即 deny（不能让 loop 永久阻塞）
            pending.remove(requestId);
            raceClaims.remove(requestId);
            log.error("PERMISSION STOMP push failed: requestId={}", requestId, e);
            return new PermissionResult.Deny(
                "Permission prompt delivery failed: " + e.getMessage(),
                new PermissionDecisionReason.Other("stomp_push_failed"),
                null);
        }

        // 3) [canUseTool v3] 后台 racer 竞速（对齐 CC interactiveHandler.ts）
        //    - hook: CC :411-431（仅 runHookRace，非 awaitAutomatedChecks）
        //    - bridge: CC :244-298 · channel: CC :316-407（注入式，null 跳过）
        //      （classifier 竞速已随 O18 删除——CC 外部构建 BASH_CLASSIFIER 恒禁用，
        //       pendingClassifierCheck 生产恒 null，无载体）
        //    [IMP-10 OPD-WF8-CB-01] 竞速 racer 参与/失败追踪 —— 全部 racer 异常 + 超时兜底
        //    必须 deny（对齐 CC structuredIO.ts:639-649 异常→deny），不静默放行。
        RacerTracker racers = new RacerTracker();
        startHookRace(tool, input, ctx, requestId, future, claimed, details, racers);
        startBridgeRace(tool, input, ctx, requestId, future, claimed, details, racers);
        startChannelRace(tool, input, ctx, requestId, future, claimed, details, racers);

        // 4) 阻塞等待首个 racer claim 的决策 · 无限等待对齐 CC（CC 权限请求无超时——PermissionContext
        //    Promise 挂起直至用户 allow/deny 或 abort；racer 失败/onResponse/abort 均 complete future，
        //    不会挂起。原 30s 超时 DEFAULT_TIMEOUT_MS=30_000 已移除：用户离开多久都保持弹窗等待选择，
        //    与前端行为一致，超时不再自动 DENY）。
        try {
            PermissionResult result = future.get();
            log.info("PERMISSION response: requestId={} decision={}",
                requestId, result.getClass().getSimpleName());
            return result;
        } catch (InterruptedException e) {
            // 保留 interrupt 标志 —— 上层调用方可能依赖 interrupt 状态做取消
            Thread.currentThread().interrupt();
            log.warn("PERMISSION interrupted: requestId={}", requestId);
            return new PermissionResult.Deny(
                "Permission prompt interrupted",
                new PermissionDecisionReason.Other("interrupt"),
                null);
        } catch (ExecutionException e) {
            // future 内部异常（不应该发生 —— racer/onResponse 永远 complete 合法值）
            log.error("PERMISSION future execution error: requestId={}", requestId, e);
            return new PermissionResult.Deny(
                "Permission prompt internal error: " + e.getMessage(),
                new PermissionDecisionReason.Other("future_error"),
                null);
        } finally {
            // 清理 —— 即使 onResponse 已 remove，这里 remove 也是 idempotent
            pending.remove(requestId);
            raceClaims.remove(requestId);
            promptInputs.remove(requestId);
            promptContexts.remove(requestId);
            bridgeRequestIds.remove(requestId);
            // [OPD-WF8-01-T5 / WF-11] 会话态 running 通知补接 · 对齐 CC structuredIO.ts:650-657：
            //   finally 中「无其他 pending 权限请求」时 notifySessionStateChanged('running')。
            //   Java 经 SdkEventQueue.enqueueSdkEvent 发射 SessionStateChangedEvent('running')，
            //   由 LlmAgentLoop turn 顶部 drain 出站到 /topic/tasks（对齐 CC sessionState.ts:128
            //   enqueueSdkEvent {subtype:'session_state_changed', state}）。
            //   sdkEventQueue 未注入（测试直构）→ 跳过，不阻断权限流程。
            if (pending.isEmpty() && sdkEventQueue != null) {
                sdkEventQueue.enqueueSdkEvent(new SdkEventQueue.SessionStateChangedEvent("running"));
                if (log.isDebugEnabled()) {
                    log.debug("PERMISSION 无 pending 权限请求 → 会话态 running 通知出站 requestId={}（对齐 CC structuredIO.ts:654）",
                        requestId);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [canUseTool v3] 后台竞速 racer（对齐 CC interactiveHandler.ts）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 后台 PermissionRequest hooks racer · 对齐 CC interactiveHandler.ts:411-431
     * {@code ctx.runHooks}（awaitAutomatedChecksBeforeDialog=false 时 hooks 由 interactive
     * 分支后台执行并采纳决策）。
     *
     * <p>[Session S07] Java 决策提取（真实信号，非假实现）：
     * <ol>
     *   <li><b>permissionRequestResult 优先</b>（CC runHooks 唯一决策通道,
     *       PermissionContext.ts:231-259）— Allow → 采纳 updatedInput 改写（X2 修复,
     *       改写后输入进 Allow.updatedInput 供工具以新输入执行）;Deny → hook message
     *       拒绝（fail-closed）;</li>
     *   <li>blockingError / preventContinuation（hook 阻塞或 stop）→ 立即 Deny
     *       （既有 fail-closed 通道, 不改变 blocking vs pending 边界）;</li>
     *   <li>permissionBehavior allow/deny → 对应决策（无 permissionRequestResult 时兜底,
     *       如 programmatic hook 仅设 behavior）。</li>
     * </ol>
     * hook 事件携带 permission_suggestions + permission_mode + tool_use_id
     * （对齐 CC executePermissionRequestHooks hooks.ts:4157-4192 载荷）。
     */
    private void startHookRace(Tool tool, JsonNode input, ToolUseContext ctx,
                               String requestId, CompletableFuture<PermissionResult> future,
                               AtomicBoolean claimed, PermissionPromptDetails details,
                               RacerTracker racers) {
        // runHookRace gate 在 start 内收敛（原 prompt() 调用点条件迁移至此）——runHookRace=false
        //   （非 hooks 场景）时 hook racer 不启动、不计入 RacerTracker。对齐 CC
        //   interactiveHandler.ts:411-431（仅 !awaitAutomatedChecksBeforeDialog 才后台 runHooks）。
        if (hookRegistry == null || details == null || !details.runHookRace()) {
            return;
        }
        List<Map<String, Object>> suggestions = toPermissionSuggestionMaps(
            details != null ? details.suggestions() : List.of());
        racers.recordStarted();
        RACERS.execute(() -> {
            try {
                HookEvent requestEvent = HookEvent.permissionRequest(
                    tool.name(), input, suggestions,
                    ctx != null ? ToolPermissionGate.modeToCcString(ctx.permissionMode()) : null,
                    requestId,
                    ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
                    ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null
                );
                GenericHook.HookResult result = hookRegistry.executeEvent(requestEvent);
                HookRaceOutcome outcome = toHookRaceDecision(result, input, requestId);
                PermissionResult decision = outcome != null ? outcome.decision() : null;
                if (decision != null && claimed.compareAndSet(false, true)) {
                    // [Session S16] 对齐 CC PermissionContext.ts:233-239 handleHookAllow:
                    //   hook allow 携带 updatedPermissions → persistPermissions（apply + persist）
                    //   后再放行（CC :324-335）。空列表 = hook 未批准规则变更。
                    if (!outcome.updatedPermissions().isEmpty()) {
                        applyAndPersistUpdates(outcome.updatedPermissions(), ctx, requestId);
                    }
                    // [hooks_v3 WF3-X6] deny.interrupt → 会话级 abort — 对齐 CC
                    //   PermissionContext.ts:245-250（先 abort 后 buildDeny/complete）。
                    if (outcome.interrupt()) {
                        ToolPermissionGate.abortIfPossible(ctx);
                    }
                    future.complete(decision);
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION hook race resolved: requestId={} decision={}",
                            requestId, decision.getClass().getSimpleName());
                    }
                }
            } catch (AbortException ae) {
                // [IMP-10 核心修复] AbortException 必须先于通用 Throwable 识别并立即完成 future
                //   为 abort deny（对齐 CC AbortError → 中止 agent，OPD-WF3-DC-v4-07），
                //   不吞 → 不转 timeout deny（旧 catch (Throwable) 会把用户中止意图静默丢弃，
                //   future 永不完成 → 30s 超时兜底 deny(reason=timeout)）。
                completeRacerAbort(requestId, future, claimed, ctx, ae);
            } catch (Throwable th) {
                // [OPD-WF8-CB-01] 单个 racer 异常 → 保留 drop（不因单崩溃误拒），登记失败；
                //   [2026-08-24 对齐 CC 无超时] 原超时兜底已移除 → 全部 racer 失败立即 complete deny
                //   （否则 future 永不 complete → 无限挂起）。对齐 CC structuredIO.ts:639-649 异常→deny。
                log.warn("HOOK PermissionRequest race failed: requestId={} err={}",
                    requestId, th.toString());
                racers.recordFailure();
                if (racers.allStartedFailed()) {
                    completeRacerAllFailed(requestId, future, claimed, ctx);
                }
            }
        });
    }

    /**
     * hook 竞速决策提取 · null = hook 未表态（用户决定）。
     *
     * <p>[Session S07] 决策来源按 CC runHooks 优先级：
     * permissionRequestResult（顶层回填, 对齐 CC hooks.ts:2882-2886）→
     * blockingError / preventContinuation（Java fail-closed 通道）→ permissionBehavior.
     *
     * <p>[Session S16] 返回 {@link HookRaceOutcome} —— Allow 决策同时携带 hook 批准的
     * updatedPermissions（CC handleHookAllow 第 2 参，PermissionContext.ts:319-336），
     * 由调用方在 claim 后 apply + persist。
     */
    private static HookRaceOutcome toHookRaceDecision(GenericHook.HookResult result,
                                                      JsonNode input, String requestId) {
        if (result == null) {
            return null;
        }
        PermissionRequestResult prr = result.permissionRequestResult();
        if (prr instanceof PermissionRequestResult.Allow allow) {
            // CC PermissionContext.ts:233-239 handleHookAllow:
            //   finalInput = decision.updatedInput ?? updatedInput ?? input — Java 采纳
            //   hook 的 updatedInput 改写 (X2), 无改写时以原输入执行.
            JsonNode rewritten = allow.updatedInput() != null
                ? JSON_FACTORY.valueToTree(allow.updatedInput()) : input;
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION hook allow 竞速胜出: requestId={} 输入改写={} (X2 采纳 updatedInput)",
                    requestId, allow.updatedInput() != null);
            }
            List<PermissionUpdate> updates = toPermissionUpdateList(allow.updatedPermissions());
            return new HookRaceOutcome(
                new PermissionResult.Allow(rewritten,
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "allow"),
                    requestId, false, null, List.of()),
                updates);
        }
        if (prr instanceof PermissionRequestResult.Deny deny) {
            // CC PermissionContext.ts:240-258 buildDeny:
            //   message || 'Permission denied by hook' — hook 的真实消息进 deny message.
            // [hooks_v3 WF3-X6] deny.interrupt → interrupt 标志透传给 startHookRace, 在 claim
            //   后 abortIfPossible(ctx) (CC :245-250 会话级 abort; 原注释误托给 gate fail-closed
            //   deny, X-WF7-06 不变量 B 实证缺口).
            String message = deny.message() != null && !deny.message().isBlank()
                ? deny.message() : "Permission denied by hook";
            return new HookRaceOutcome(
                new PermissionResult.Deny(message,
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                    requestId),
                List.of(),
                Boolean.TRUE.equals(deny.interrupt()));
        }
        if (result.blockingError() != null) {
            String msg = result.blockingError().blockingError();
            return new HookRaceOutcome(
                new PermissionResult.Deny(
                    msg != null && !msg.isBlank() ? msg : "Permission request hook blocked",
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                    requestId),
                List.of());
        }
        if (result.preventContinuation()) {
            String reason = result.stopReason();
            return new HookRaceOutcome(
                new PermissionResult.Deny(
                    reason != null && !reason.isBlank() ? reason : "Permission request hook stopped",
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                    requestId),
                List.of());
        }
        if (result.permissionBehavior() == PermissionBehavior.ALLOW) {
            return new HookRaceOutcome(
                new PermissionResult.Allow(input,
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "allow"),
                    requestId, false, null, List.of()),
                List.of());
        }
        if (result.permissionBehavior() == PermissionBehavior.DENY) {
            return new HookRaceOutcome(
                new PermissionResult.Deny("Permission request hook denied",
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                    requestId),
                List.of());
        }
        return null;
    }

    /**
     * hook 竞速决策 + 携带的权限更新 + interrupt 标志 · [Session S16]。
     *
     * @param decision           最终权限决策（Allow/Deny；null 由外层判空）
     * @param updatedPermissions hook allow 批准的权限更新（可为空列表）
     * @param interrupt          [hooks_v3 WF3-X6] hook deny 的 interrupt 标志 — CC
     *                           PermissionContext.ts:245-250 deny.interrupt →
     *                           abortController.abort() 会话级中断。由 startHookRace 在
     *                           claim 后执行 abort（本类持 ctx）。
     */
    private record HookRaceOutcome(PermissionResult decision,
                                   List<PermissionUpdate> updatedPermissions,
                                   boolean interrupt) {
        public HookRaceOutcome {
            updatedPermissions = updatedPermissions == null ? List.of() : List.copyOf(updatedPermissions);
        }

        /** 2 参兼容构造器 · interrupt 默认 false（allow / blockingError / preventContinuation 分支）. */
        private HookRaceOutcome(PermissionResult decision, List<PermissionUpdate> updatedPermissions) {
            this(decision, updatedPermissions, false);
        }
    }


    /**
     * [Session S07] PermissionUpdate 建议 → HookEvent.permissionSuggestions Map 列表 ·
     * CC original: {@code permission_suggestions} (coreSchemas.ts:431).
     */
    private static List<Map<String, Object>> toPermissionSuggestionMaps(List<PermissionUpdate> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(suggestions.size());
        for (PermissionUpdate suggestion : suggestions) {
            out.add(JSON_FACTORY.convertValue(suggestion, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session S16] updatedPermissions apply + persist 管线（CC persistPermissions 等价）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [Session S16] 批准流 apply + persist · 对齐 CC {@code PermissionContext.persistPermissions}
     * （PermissionContext.ts:139-147）。
     *
     * <p>CC 语义（handleUserAllow / handleHookAllow / bridge onResponse 三处共用）：
     * <ol>
     *   <li>{@code persistPermissionUpdates(updates)} —— 可持久化 destination 写盘
     *       （S01 合并写回）—— 下一轮 {@code PermissionContextBuilder} 从磁盘加载 → "Allow
     *       forever" 真实生效（验收 2）；</li>
     *   <li>{@code setToolPermissionContext(applyPermissionUpdates(appState.ctx, updates))}
     *       —— Applier 应用到当前上下文 + 同步 appState 的 toolPermissionContext
     *       （{@code AgentLoopContext.mergeAppStateCommandRules} 同键读取，AppState 后续轮
     *       派生保持最新）。</li>
     * </ol>
     *
     * <p>本方法为幂等增强：applier / persister 未注入（旧测试构造路径）→ 仅日志不抛。
     *
     * @param updates   批准的权限更新（可为空列表 → no-op）
     * @param ctx       当前工具调用上下文（permissionContext 承载规则集；可为 null）
     * @param requestId 关联请求 ID（仅日志）
     */
    private void applyAndPersistUpdates(List<PermissionUpdate> updates, ToolUseContext ctx, String requestId) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        ToolPermissionContext current = ctx != null ? ctx.permissionContext() : null;
        if (current == null) {
            if (log.isWarnEnabled()) {
                log.warn("PERMISSION updatedPermissions: 无 permissionContext 可应用（ctx 未携带），"
                    + "跳过 apply+persist requestId={} updates={}", requestId, updates.size());
            }
            return;
        }
        // 1) apply —— CC applyPermissionUpdates（PermissionUpdate.ts:196-206）
        //    [DEL-WF1-03] SESSION destination 更新不再同步 SessionSource（已删）；
        //    "Allow this session" 跨轮持久待后续 appState 承载任务（见探查/progress/wf12.md）。
        ToolPermissionContext applied = current;
        if (permissionUpdateApplier != null) {
            // [session-id-short] ctx.sessionId() 已 String（short）
            String sessionId = ctx != null ? ctx.sessionId() : null;
            applied = permissionUpdateApplier.applyAll(updates, current);
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION updatedPermissions: apply 完成 requestId={} updates={} sessionId={}",
                    requestId, updates.size(), sessionId);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION updatedPermissions: applier 未注入，跳过 apply requestId={}",
                    requestId);
            }
        }
        // 2) persist —— CC persistPermissionUpdates（PermissionUpdate.ts:349-353；
        //    supportsPersistence 拦截 CLI_ARG/SESSION 非可持久化 destination）
        if (permissionUpdatePersister != null) {
            permissionUpdatePersister.persistAll(updates);
            if (log.isInfoEnabled()) {
                log.info("PERMISSION updatedPermissions: persist 完成 requestId={} updates={}",
                    requestId, updates.size());
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION updatedPermissions: persister 未注入，跳过 persist requestId={}",
                    requestId);
            }
        }
        // 3) appState 同步 —— CC setToolPermissionContext（PermissionContext.ts:143-145）
        if (ctx != null && ctx.setAppState() != null) {
            final ToolPermissionContext finalCtx = applied;
            ctx.setAppState().accept(prev -> {
                Map<String, Object> next = new java.util.LinkedHashMap<>(prev);
                next.put("toolPermissionContext", finalCtx);
                return next;
            });
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION updatedPermissions: appState.toolPermissionContext 已同步 requestId={}",
                    requestId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session S16] PermissionUpdate JSON 转换（CC PermissionUpdateSchema 判别联合）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [Session S16] 原始 JSON 数组 → {@link PermissionUpdate} 列表 ·
     * 对齐 CC {@code PermissionUpdateSchema}（PermissionUpdateSchema.ts，discriminated union
     * 按 {@code type} 判别）。
     *
     * <p>宽松双形状：
     * <ul>
     *   <li><b>CC 形状</b>：{@code {type: 'addRules'|'removeRules'|'replaceRules'|'setMode'|
     *       'addDirectories'|'removeDirectories', ...}}，destination/behavior/mode 为 camelCase
     *       字面量（OPD-PERM-03 依现状同时接受）；</li>
     *   <li><b>Java 直连形状</b>：无 type 字段时按字段形状推断（rules+behavior→addRules /
     *       mode+destination→setMode / directories+destination→addDirectories /
     *       paths+destination→removeDirectories）。removeRules 与 setMode/directories 均要求
     *       destination 必填（CC schema 必填，缺字段拒收返回 null——未上线可破约）。</li>
     * </ul>
     *
     * @param nodes 原始 JSON 节点列表（可为 null）
     * @return 解析后的权限更新列表；无法解析的节点跳过（不抛，best-effort）
     */
    public static List<PermissionUpdate> parseUpdatedPermissions(List<JsonNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<PermissionUpdate> out = new java.util.ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            PermissionUpdate parsed = parsePermissionUpdate(node);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    /**
     * [Session S16] hook 原始对象列表（HookOutputParser.objListOrNull 产物，元素为 Map）
     * → {@link PermissionUpdate} 列表。元素经 JSON 转换后走 {@link #parsePermissionUpdate}。
     */
    static List<PermissionUpdate> toPermissionUpdateList(List<Object> rawObjects) {
        if (rawObjects == null || rawObjects.isEmpty()) {
            return List.of();
        }
        List<PermissionUpdate> out = new java.util.ArrayList<>(rawObjects.size());
        for (Object raw : rawObjects) {
            if (raw == null) {
                continue;
            }
            PermissionUpdate parsed = parsePermissionUpdate(JSON_FACTORY.valueToTree(raw));
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    /**
     * [Session S16] 单节点解析 · 判别联合 + 字段形状推断（见 {@link #parseUpdatedPermissions}）。
     */
    static PermissionUpdate parsePermissionUpdate(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String type = node.path("type").asText(null);
        if (type != null && !type.isBlank()) {
            return switch (type) {
                case "addRules" -> parseAddOrReplace(node, true);
                case "replaceRules" -> parseAddOrReplace(node, false);
                case "removeRules" -> parseRemoveRules(node);
                case "setMode" -> parseSetMode(node);
                case "addDirectories" -> parseDirectories(node, true);
                case "removeDirectories" -> parseDirectories(node, false);
                default -> null;
            };
        }
        // Java 直连/旧形状推断（无 type 判别字段）
        if (node.has("mode")) {
            return parseSetMode(node);
        }
        if (node.has("directories")) {
            return parseDirectories(node, true);
        }
        if (node.has("paths")) {
            return parseDirectories(node, false);
        }
        if (node.has("rules")) {
            if (node.has("behavior")) {
                JsonNode rulesNode = node.get("rules");
                return parseAddOrReplace(node, rulesNode == null || rulesNode.size() > 0);
            }
            return parseRemoveRules(node);
        }
        return null;
    }

    /** addRules / replaceRules 解析（type 判别或字段形状推断共用）。 */
    private static PermissionUpdate parseAddOrReplace(JsonNode node, boolean add) {
        PermissionUpdate.Destination destination = parseDestination(node);
        com.nexusai.application.agent.permission.PermissionBehavior behavior = parseBehavior(node);
        if (destination == null || behavior == null) {
            return null;
        }
        List<PermissionRule> rules = parseRules(node, destination, behavior);
        if (add) {
            return rules.isEmpty() ? null : new PermissionUpdate.AddRules(destination, rules, behavior);
        }
        return new PermissionUpdate.ReplaceRules(destination, rules, behavior);
    }

    /** removeRules 解析 · CC removeRules 含 behavior 字段（PermissionUpdateSchema.ts:59）。 */
    private static PermissionUpdate parseRemoveRules(JsonNode node) {
        PermissionUpdate.Destination destination = parseDestination(node);
        if (destination == null) {
            return null;
        }
        com.nexusai.application.agent.permission.PermissionBehavior behavior = parseBehavior(node);
        if (behavior == null) {
            // CC schema behavior 必填（PermissionUpdateSchema.ts:59）——缺 behavior 拒收（未上线可破约）
            return null;
        }
        List<PermissionRule> rules = parseRules(node, destination, behavior);
        return rules.isEmpty() ? null : new PermissionUpdate.RemoveRules(destination, rules, behavior);
    }

    /** setMode 解析 · CC mode 字面量（'default'|'acceptEdits'|...）或 Java 枚举名。 */
    private static PermissionUpdate parseSetMode(JsonNode node) {
        PermissionUpdate.Destination destination = parseDestination(node);
        if (destination == null) {
            // CC schema destination 必填（PermissionUpdateSchema.ts:65）——缺 destination 拒收
            return null;
        }
        String modeText = node.path("mode").asText(null);
        PermissionMode mode = parsePermissionMode(modeText);
        return mode == null ? null : new PermissionUpdate.SetMode(destination, mode);
    }

    /** addDirectories / removeDirectories 解析 · CC 字段名 {@code directories}（Java 直连形状接受 paths）。 */
    private static PermissionUpdate parseDirectories(JsonNode node, boolean add) {
        PermissionUpdate.Destination destination = parseDestination(node);
        if (destination == null) {
            // CC schema destination 必填（PermissionUpdateSchema.ts:70/75）——缺 destination 拒收
            return null;
        }
        JsonNode dirsNode = node.has("directories") ? node.get("directories") : node.get("paths");
        if (dirsNode == null || !dirsNode.isArray() || dirsNode.isEmpty()) {
            return null;
        }
        List<String> paths = new java.util.ArrayList<>(dirsNode.size());
        for (JsonNode dir : dirsNode) {
            if (dir != null && dir.isTextual() && !dir.asText().isBlank()) {
                paths.add(dir.asText());
            }
        }
        if (paths.isEmpty()) {
            return null;
        }
        return add
            ? new PermissionUpdate.AddDirectories(destination, paths)
            : new PermissionUpdate.RemoveDirectories(destination, paths);
    }

    /** rules 数组解析 · 每项 {@code {toolName, ruleContent?}}（CC ruleValue 形状）。 */
    private static List<PermissionRule> parseRules(JsonNode node,
                                                   PermissionUpdate.Destination destination,
                                                   com.nexusai.application.agent.permission.PermissionBehavior behavior) {
        JsonNode rulesNode = node.get("rules");
        if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()) {
            return List.of();
        }
        // removeRules 的规则无 behavior（CC removeRules 无 behavior 字段）——
        // Java PermissionRule 强制非空，匹配按 toolName+ruleContent 值进行（ruleBehavior 不参与），
        com.nexusai.application.agent.permission.PermissionBehavior effectiveBehavior =
            behavior != null ? behavior : com.nexusai.application.agent.permission.PermissionBehavior.ALLOW;
        List<PermissionRule> rules = new java.util.ArrayList<>(rulesNode.size());
        for (JsonNode ruleNode : rulesNode) {
            if (ruleNode == null || !ruleNode.isObject()) {
                continue;
            }
            String toolName = ruleNode.path("toolName").asText(null);
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            String ruleContent = ruleNode.path("ruleContent").asText(null);
            if (ruleContent != null && ruleContent.isBlank()) {
                ruleContent = null;
            }
            // [OPD-PERM-08] 桶 key 即规则归属：source 一律取 destination 对应 source
            //   （CC PermissionUpdate 规则无 source 字段）
            PermissionRuleSource source = PermissionUpdateApplier.mapDestination(destination);
            rules.add(new PermissionRule(source, effectiveBehavior, new PermissionRuleValue(toolName, ruleContent)));
        }
        return List.copyOf(rules);
    }

    /** destination 解析 · CC camelCase（'userSettings' 等）与 Java 枚举名（'USER_SETTINGS'）均接受。 */
    private static PermissionUpdate.Destination parseDestination(JsonNode node) {
        String text = node.path("destination").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        for (PermissionUpdate.Destination d : PermissionUpdate.Destination.values()) {
            if (d.name().equalsIgnoreCase(text) || ccDestination(d).equalsIgnoreCase(text)) {
                return d;
            }
        }
        return null;
    }

    /** behavior 解析 · 枚举字面量名（大小写不敏感，等价 CC 字面量 'allow'/'deny'/'ask'）。 */
    private static com.nexusai.application.agent.permission.PermissionBehavior parseBehavior(JsonNode node) {
        String text = node.path("behavior").asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        // 注意：本类另导入 hook 包 PermissionBehavior（hook 竞速决策用），此处全限定
        //   指向 permission 包枚举（PermissionRule.ruleBehavior 类型）
        for (com.nexusai.application.agent.permission.PermissionBehavior b :
                com.nexusai.application.agent.permission.PermissionBehavior.values()) {
            if (b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }

    /** mode 解析 · CC 字面量与 Java 枚举名均接受（映射表对齐 modeToCcString）。 */
    private static PermissionMode parsePermissionMode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (PermissionMode m : PermissionMode.values()) {
            if (m.name().equalsIgnoreCase(text) || ccMode(m).equalsIgnoreCase(text)) {
                return m;
            }
        }
        return null;
    }

    /** CC destination 字面量 · 对齐 PermissionUpdateDestination（types/permissions.ts:147-153）。 */
    private static String ccDestination(PermissionUpdate.Destination d) {
        return switch (d) {
            case USER_SETTINGS -> "userSettings";
            case PROJECT_SETTINGS -> "projectSettings";
            case LOCAL_SETTINGS -> "localSettings";
            case CLI_ARG -> "cliArg";
            case SESSION -> "session";
        };
    }

    /** CC mode 字面量 · 对齐 PermissionMode（types/permissions.ts:16-38）。 */
    private static String ccMode(PermissionMode m) {
        return switch (m) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case BYPASS_PERMISSIONS -> "bypassPermissions";
            case DONT_ASK -> "dontAsk";
            case PLAN -> "plan";
            case AUTO -> "auto";
            case BUBBLE -> "bubble";
        };
    }

    /**
     * bridge 竞速 racer · 对齐 CC interactiveHandler.ts:244-298（CCR/claude.ai 远程弹窗）。
     * 注入为 null → 不参与竞速。
     */
    private void startBridgeRace(Tool tool, JsonNode input, ToolUseContext ctx,
                                 String requestId, CompletableFuture<PermissionResult> future,
                                 AtomicBoolean claimed, PermissionPromptDetails details,
                                 RacerTracker racers) {
        if (bridgeCallbacks == null) {
            return;
        }
        String bridgeRequestId = UUID.randomUUID().toString();
        bridgeRequestIds.put(requestId, bridgeRequestId);
        racers.recordStarted();
        try {
            // [canUseTool v4] 传 sessionId — CC 回调是 session 级闭包，Java @Component 单例需显式
            //   路由出站请求到正确 session 的 STOMP topic（对齐 CC bridgePermissionCallbacks.ts
            //   sendRequest 参数 + interactiveHandler.ts:245-253）。
            bridgeCallbacks.sendRequest(
                ctx.sessionId(),
                bridgeRequestId, tool.name(), input, requestId,
                details != null ? details.description() : null,
                details != null ? details.suggestions() : List.of(),
                details != null ? details.blockedPath() : null);
        } catch (AbortException ae) {
            // [IMP-10 核心修复] AbortException 不吞 → 立即中止 deny（对齐 CC AbortError）
            completeRacerAbort(requestId, future, claimed, ctx, ae);
            return;
        } catch (Throwable th) {
            // [OPD-WF8-CB-01] 单个 racer 异常 → 保留 drop（不因单崩溃误拒），登记失败；
            //   [2026-08-24 对齐 CC 无超时] 全部 racer 失败立即 complete deny（原超时兜底已移除）。
            log.warn("PERMISSION bridge sendRequest failed (graceful degradation): requestId={} err={}",
                requestId, th.toString());
            racers.recordFailure();
            if (racers.allStartedFailed()) {
                completeRacerAllFailed(requestId, future, claimed, ctx);
            }
            return;
        }
        Runnable unsubscribe = bridgeCallbacks.onResponse(bridgeRequestId, response -> {
            if (claimed.compareAndSet(false, true)) {
                if ("allow".equals(response.behavior())) {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION bridge race ALLOW: requestId={}", requestId);
                    }
                    // [Session S16] 对齐 CC interactiveHandler.ts:266-269 —
                    //   response.updatedPermissions?.length → ctx.persistPermissions（apply + persist）
                    //   后再 buildAllow（CC :280 response.updatedInput ?? displayInput）
                    if (response.updatedPermissions() != null && !response.updatedPermissions().isEmpty()) {
                        applyAndPersistUpdates(response.updatedPermissions(), ctx, requestId);
                    }
                    JsonNode bridgeInput = response.updatedInput() != null
                        ? response.updatedInput() : input;
                    future.complete(new PermissionResult.Allow(bridgeInput,
                        new PermissionDecisionReason.Other("bridge_allowed"),
                        requestId, false, null, List.of()));
                } else {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION bridge race DENY: requestId={}", requestId);
                    }
                    future.complete(new PermissionResult.Deny(
                        response.message() != null ? response.message() : "Denied via bridge",
                        new PermissionDecisionReason.Other("bridge_denied"),
                        requestId));
                }
            }
        });
        if (ctx.abortController() != null && ctx.abortController() != AbortController.NOOP) {
            ctx.abortController().onCancel(ac -> {
                bridgeCallbacks.cancelRequest(bridgeRequestId);
                unsubscribe.run();
            });
        }
    }

    /**
     * channel 竞速 racer · 对齐 CC interactiveHandler.ts:316-407（Telegram/iMessage/Discord）。
     * 注入为 null → 不参与竞速。
     *
     * <p>[impl-I-3 T6] 前置两闸：
     * <ol>
     *   <li><b>isChannelPermissionRelayEnabled</b>（channelPermissions.ts:36-38）— 配置 false →
     *       跳过整个 channel relay 竞速（CC useManageMCPConnections.ts:188「One gate, full disable」）</li>
     *   <li><b>filterPermissionRelayClients</b>（interactiveHandler.ts:323-326）— 无合格 channel server
     *       （connected + allowlist + 双 capability，channelPermissions.ts:177-194）→ 不参与竞速
     *       （「a relay-only channel never becomes a permission surface by accident」）</li>
     * </ol>
     */
    private void startChannelRace(Tool tool, JsonNode input, ToolUseContext ctx,
                                  String requestId, CompletableFuture<PermissionResult> future,
                                  AtomicBoolean claimed, PermissionPromptDetails details,
                                  RacerTracker racers) {
        if (channelCallbacks == null) {
            return;
        }
        // [impl-I-3 T6] 闸 1: isChannelPermissionRelayEnabled（默认 false → 跳过整个 relay 竞速）
        boolean relayEnabled = mcpProperties == null || mcpProperties.channelsPermissionRelayEnabled();
        if (!relayEnabled) {
            if (log.isInfoEnabled()) {
                log.info("CHANNEL 竞速跳过: isChannelPermissionRelayEnabled=false requestId={}", requestId);
            }
            return;
        }
        // [impl-I-3 T6] 闸 2: 无合格 channel server → 不参与竞速（本地/前端兜底）
        if (channelRelayServerSource != null && !hasQualifiedChannelRelayServer()) {
            if (log.isInfoEnabled()) {
                log.info("CHANNEL 竞速跳过: 无合格 channel server（filterPermissionRelayClients 为空）requestId={}",
                    requestId);
            }
            return;
        }
        String channelRequestId = channelCallbacks.shortRequestId(requestId);
        racers.recordStarted();
        try {
            // [canUseTool v4] 传 sessionId — 对齐 CC interactiveHandler.ts:334-354
            //   ChannelPermissionRequestParams（request_id/tool_name/description/input_preview）。
            channelCallbacks.sendRequest(
                ctx.sessionId(),
                channelRequestId, tool.name(),
                details != null ? details.description() : null, input);
        } catch (AbortException ae) {
            // [IMP-10 核心修复] AbortException 不吞 → 立即中止 deny（对齐 CC AbortError）
            completeRacerAbort(requestId, future, claimed, ctx, ae);
            return;
        } catch (Throwable th) {
            // [OPD-WF8-CB-01] 单个 racer 异常 → 保留 drop（不因单崩溃误拒），登记失败；
            //   [2026-08-24 对齐 CC 无超时] 全部 racer 失败立即 complete deny（原超时兜底已移除）。
            log.warn("PERMISSION channel sendRequest failed (graceful degradation): requestId={} err={}",
                requestId, th.toString());
            racers.recordFailure();
            if (racers.allStartedFailed()) {
                completeRacerAllFailed(requestId, future, claimed, ctx);
            }
            return;
        }
        Runnable unsubscribe = channelCallbacks.onResponse(channelRequestId, response -> {
            if (claimed.compareAndSet(false, true)) {
                if ("allow".equals(response.behavior())) {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION channel race ALLOW: requestId={}", requestId);
                    }
                    future.complete(new PermissionResult.Allow(input,
                        new PermissionDecisionReason.Other("channel_allowed"),
                        requestId, false, null, List.of()));
                } else {
                    if (log.isInfoEnabled()) {
                        log.info("PERMISSION channel race DENY: requestId={}", requestId);
                    }
                    future.complete(new PermissionResult.Deny(
                        "Denied via channel " + response.fromServer(),
                        new PermissionDecisionReason.Other("channel_denied"),
                        requestId));
                }
            }
        });
        if (ctx.abortController() != null && ctx.abortController() != AbortController.NOOP) {
            ctx.abortController().onCancel(ac -> unsubscribe.run());
        }
    }

    /**
     * [impl-I-3 T6] 是否存在合格 channel server · 对齐 CC filterPermissionRelayClients
     * （channelPermissions.ts:177-194 4 判定 ALL required：connected + allowlist + 双 capability）。
     *
     * <p>数据源 = {@link McpToolPool}（activeServers + getServerCapabilities，R2-1 扩展后
     * experimental 可用）；allowlist = 注入的 relayAllowlistChecker（默认 DB ledger，fail-closed）。
     */
    private boolean hasQualifiedChannelRelayServer() {
        java.util.Set<String> servers = channelRelayServerSource.activeServers();
        java.util.List<RelayClientView> views = new java.util.ArrayList<>();
        for (String name : servers) {
            var caps = channelRelayServerSource.getServerCapabilities(name).orElse(null);
            if (caps == null) {
                continue;
            }
            // filterPermissionRelayClients 的 capabilitiesFn 期望「完整 capabilities map」→
            // 包装 {experimental: ...}（CC client.capabilities 结构，channelPermissions.ts:191 读 experimental）
            java.util.Map<String, Object> capsMap = new java.util.LinkedHashMap<>();
            capsMap.put("experimental", caps.experimental());
            views.add(new RelayClientView("connected", name, capsMap));
        }
        java.util.function.Function<String, Boolean> checker = relayAllowlistChecker;
        if (checker == null) {
            checker = name -> channelAllowlistService != null && channelAllowlistService.isAllowlisted(name);
        }
        java.util.List<RelayClientView> qualified = channelPermission.filterPermissionRelayClients(
            views, RelayClientView::name, RelayClientView::type, RelayClientView::capabilities, checker);
        return !qualified.isEmpty();
    }

    /** [impl-I-3 T6] relay 判定用 MCP client 视图（对齐 CC filterPermissionRelayClients 泛型约束 T）。 */
    private record RelayClientView(String type, String name, java.util.Map<String, Object> capabilities) {}

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-10 OPD-WF8-CB-01] 竞速 racer 参与/失败追踪 · 对齐 CC structuredIO.ts:639-649
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 竞速 racer 参与/失败追踪 · [IMP-10 OPD-WF8-CB-01 拍板组合方案]。
     *
     * <p>语义（CC original：structuredIO.ts:639-649 竞速异常 catch → deny 决策）：
     * <ul>
     *   <li><b>单 racer 异常 → 保留 Java drop</b>（不因单崩溃误拒）：{@link #recordFailure()}
     *       只登记失败，不立即拒绝；</li>
     *   <li><b>全部 racer 异常 + 超时兜底 → deny</b>（不静默放行）：prompt 超时兜底时
     *       {@link #allStartedFailed()} 判定 {@code started>0 && failed>=started} → 返回
     *       {@code deny(reason=permission_request_failed)}，对齐 CC 异常→deny。</li>
     * </ul>
     *
     * <p>{@code started} 由 prompt 线程同步登记（各 racer 通过 gate 实际启动时），
     * {@code failed} 由 racer 线程异步登记 —— 超时兜底时刻 {@code started} 已完整（所有
     * start 调用在 {@code future.get} 之前完成），无登记顺序竞态。
     */
    private static final class RacerTracker {
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        /** 竞速 racer 实际启动（通过 gate）登记 · prompt 线程同步调用。 */
        void recordStarted() {
            started.incrementAndGet();
        }

        /** 竞速 racer 异常失败登记 · racer 线程异步调用。 */
        void recordFailure() {
            failed.incrementAndGet();
        }

        /** 全部已启动 racer 均已异常失败（started>0 时）· 供超时兜底 deny 判据。 */
        boolean allStartedFailed() {
            int s = started.get();
            return s > 0 && failed.get() >= s;
        }
    }

    /**
     * 竞速 racer 抛 {@link AbortException} → 立即完成 future 为中止 deny · [IMP-10 核心修复]。
     *
     * <p>对齐 CC AbortError（errors.ts:12-17）→ 中止 agent（OPD-WF3-DC-v4-07 abort 主题）：
     * 用户中止意图不可吞（HookRegistry 13 处 AbortException 透传点），也不可静默转成
     * timeout deny。本方法以与 {@code ctx.abortController().onCancel} 一致的
     * {@code user_abort} 决策完成 future（claim 守卫保证幂等），prompt 立即返回，不阻塞
     * 至超时。
     *
     * @param requestId 请求 ID（决策 toolUseID）
     * @param future    竞速 future（claim 后 complete）
     * @param claimed   原子 claim 守卫（首个 claim 获胜）
     * @param ctx       工具调用上下文（agentType 决定拒绝消息模板）
     * @param ae        中止异常（仅日志）
     */
    private static void completeRacerAbort(String requestId,
                                           CompletableFuture<PermissionResult> future,
                                           AtomicBoolean claimed, ToolUseContext ctx,
                                           AbortException ae) {
        if (!claimed.compareAndSet(false, true)) {
            // 竞速已由其他 racer / abort listener 胜出 → 忽略（幂等）
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("PERMISSION racer AbortException → 中止 deny（对齐 CC AbortError，"
                + "不吞 → 不转 timeout deny）: requestId={} err={}", requestId, ae.getMessage());
        }
        future.complete(new PermissionResult.Deny(
            PermissionRejectMessages.buildRejectMessage(ctx.agentType() != null, null),
            new PermissionDecisionReason.Other("user_abort"),
            requestId));
    }

    /**
     * 全部竞速 racer 异常失败 → 立即完成 future 为 deny(permission_request_failed) ·
     * [2026-08-24 对齐 CC 无超时]。
     *
     * <p>原实现依赖 {@code future.get(timeoutMs)} 超时兜底触发「全部 racer 失败 → deny」；移除
     * 超时（对齐 CC 无限等待）后，若 racer 异常仅 {@code recordFailure()} 不 complete future，
     * 且用户一直不响应 → {@code future.get()} 无限挂起。故各 racer catch Throwable 中
     * {@code recordFailure()} 后检查 {@code RacerTracker#allStartedFailed()}，全部失败立即
     * complete deny（对齐 CC structuredIO.ts:639-649 异常→deny，不静默放行）。claim 守卫幂等。
     *
     * @param requestId 请求 ID
     * @param future    竞速 future（claim 后 complete）
     * @param claimed   原子 claim 守卫
     * @param ctx       工具调用上下文（拒绝消息模板）
     */
    private static void completeRacerAllFailed(String requestId,
                                               CompletableFuture<PermissionResult> future,
                                               AtomicBoolean claimed, ToolUseContext ctx) {
        if (!claimed.compareAndSet(false, true)) {
            // 竞速已由其他 racer / 用户 / abort 胜出 → 忽略（幂等）
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("PERMISSION 全部竞速 racer 异常失败 → 立即 deny（对齐 CC structuredIO.ts:639-649 "
                + "异常→deny，原超时兜底已移除）: requestId={}", requestId);
        }
        future.complete(new PermissionResult.Deny(
            "Tool permission request failed: all permission checks failed",
            new PermissionDecisionReason.Other("permission_request_failed"),
            requestId));
    }

    // ════════════════════════════════════════════════════════════════════════
    // STOMP 响应回调（由 PermissionController 调）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 前端响应回调 · 完成对应 future。
     *
     * <p>由 {@link com.nexusai.apis.permission.PermissionController} 在收到
     * {@link com.nexusai.ws.events.MessagePermissionResponseEvent} 时调用。
     *
     * <p>幂等行为：
     * <ul>
     *   <li>找不到 {@code requestId} 对应的 future → log warn + 忽略（可能是超时已返回）</li>
     *   <li>future 已 complete → 第二次 complete 被忽略（CompletableFuture 自带幂等）</li>
     *   <li>[canUseTool v3] claim 已占用（hook/classifier/bridge/channel 竞速已胜出）→
     *       log info + 忽略（对齐 CC interactiveHandler.ts 各 racer 的 {@code if (!claim()) return}）</li>
     * </ul>
     *
     * @param requestId 关联的 {@code MessagePermissionRequestEvent.requestId}
     * @param decision  用户决策（{@code "allow"} / {@code "deny"}，大小写不敏感）
     */
    public void onResponse(String requestId, String decision) {
        // 向后兼容 2 参版本:无 feedback/contentBlocks/updatedPermissions 的旧前端
        onResponse(requestId, decision, null, null, null);
    }

    /**
     * [R32-b9] STOMP 响应回调 · 完成对应 future (含 acceptFeedback + contentBlocks)。
     *
     * <p>[Session S16] 兼容重载 · 无 updatedPermissions（旧前端/旧测试语义）→ null。
     *
     * @param requestId      关联 {@code MessagePermissionRequestEvent.requestId}
     * @param decision       用户决策(allow / deny,大小写不敏感)
     * @param acceptFeedback [R32-b9] 用户允许时的反馈文本(可空)
     * @param contentBlocks  [R32-b9] 用户允许时的内容块 (可空;含 image 等)
     */
    public void onResponse(String requestId, String decision,
                           String acceptFeedback, List<JsonNode> contentBlocks) {
        onResponse(requestId, decision, null, acceptFeedback, contentBlocks);
    }

    /**
     * [Session S16] STOMP 响应回调 · 完成对应 future（含 updatedPermissions）。
     *
     * <p><b>4 参语义对齐 CC onAllow</b>（interactiveHandler.ts:154-167）：
     * {@code onAllow(updatedInput, permissionUpdates, feedback, contentBlocks)} ——
     * 用户批准建议（"Always allow"）时把权限更新列表随响应回传，本方法在完成 future 前
     * 先 apply + persist（对齐 CC handleUserAllow → persistPermissions，
     * PermissionContext.ts:291-318/139-147），"Allow forever" 下一轮真实生效。
     *
     * @param requestId          关联 {@code MessagePermissionRequestEvent.requestId}
     * @param decision           用户决策(allow / deny,大小写不敏感)
     * @param updatedPermissions [Session S16] 用户批准的权限更新（原始 JSON 数组，
     *                           CC 判别联合形状；可为 null/空 = 未批准规则变更）
     * @param acceptFeedback     [R32-b9] 用户允许时的反馈文本(可空)
     * @param contentBlocks      [R32-b9] 用户允许时的内容块 (可空;含 image 等)
     */
    public void onResponse(String requestId, String decision,
                           List<JsonNode> updatedPermissions,
                           String acceptFeedback, List<JsonNode> contentBlocks) {
        // [FIX-E askuser-answers] 5 参版本委托 7 参（answers/annotations 传 null = 非 AskUserQuestion 工具）
        onResponse(requestId, decision, updatedPermissions, acceptFeedback, contentBlocks, null, null);
    }

    /**
     * [FIX-E askuser-answers] STOMP 响应回调 · 完成对应 future（含 answers + annotations）。
     *
     * <p>在 {@link #onResponse(String, String, List, String, List)} 基础上追加 AskUserQuestion
     * 答案收集通道的 {@code answers}/{@code annotations}，并在 Allow 分支合并进
     * {@link PermissionResult.Allow#updatedInput}（对齐 CC {@code AskUserQuestionPermissionRequest.tsx:398-407}
     * {@code submitAnswers → onAllow(updatedInput, ...)}：{@code updatedInput = {...input, answers,
     * ...(annotations && {annotations})}}）。CC 合并点在提交 UI（前端组件），Java 无前端组件 →
     * 合并点迁移到后端 onResponse（架构差异，非行为偏离）。
     *
     * @param requestId          关联 {@code MessagePermissionRequestEvent.requestId}
     * @param decision           用户决策(allow / deny,大小写不敏感)
     * @param updatedPermissions [Session S16] 用户批准的权限更新（原始 JSON 数组；可为 null）
     * @param acceptFeedback     [R32-b9] 用户允许时的反馈文本(可空)
     * @param contentBlocks      [R32-b9] 用户允许时的内容块 (可空;含 image 等)
     * @param answers            [FIX-E] AskUserQuestion 答案（{questionText: optionLabel}；可为 null）
     * @param annotations        [FIX-E] 每问注解（preview/notes；可为 null）
     */
    public void onResponse(String requestId, String decision,
                           List<JsonNode> updatedPermissions,
                           String acceptFeedback, List<JsonNode> contentBlocks,
                           JsonNode answers, JsonNode annotations) {
        if (requestId == null || requestId.isBlank()) {
            log.warn("PERMISSION onResponse: requestId is blank, ignoring");
            return;
        }
        CompletableFuture<PermissionResult> future = pending.remove(requestId);
        AtomicBoolean claimed = raceClaims.remove(requestId);
        // [FIX-E askuser-answers] 用 remove 返回值捕获 originalInput —— 修复已存在 bug：
        //   原实现 promptInputs.remove(requestId) 先于 promptInputs.get(requestId) 读取
        //   （get 恒 null，Allow.updatedInput 现恒为空 ObjectNode，questions 丢失）。改为
        //   remove 一次拿到原值，供 Allow 合并 answers/annotations 与 deny hook 用。
        JsonNode originalInput = promptInputs.remove(requestId);
        ToolUseContext promptCtx = promptContexts.remove(requestId);
        if (future == null) {
            // 可能场景：超时已返回 / 请求已被新请求覆盖 / 重复响应
            log.warn("PERMISSION onResponse: no pending request for requestId={} (likely timeout/duplicate)",
                requestId);
            return;
        }
        // [canUseTool v3] 竞速 claim 守卫 — hook/classifier/bridge/channel 已胜出则忽略用户响应
        //   （对齐 CC interactiveHandler.ts:160 onAllow {@code if (!claim()) return}）。
        //   无 claim 记录（外部注入的 future，测试兼容）→ 直接 complete。
        if (claimed != null && !claimed.compareAndSet(false, true)) {
            log.info("PERMISSION onResponse: race already resolved for requestId={}, ignored",
                requestId);
            return;
        }
        boolean isAllow = "allow".equalsIgnoreCase(decision);
        // [RV-07] 本地用户 racer 胜出 → 通知 bridge 远程表面 dismiss（对齐 CC interactiveHandler.ts:162-168
        //   onAllow / :186-192 onReject 的 sendResponse + cancelRequest）
        String bridgeRequestId = bridgeRequestIds.remove(requestId);
        if (bridgeCallbacks != null && bridgeRequestId != null) {
            try {
                bridgeCallbacks.sendResponse(bridgeRequestId,
                    new BridgePermissionCallbacks.BridgeResponse(
                        isAllow ? "allow" : "deny", null, null, null));
            } catch (Throwable th) {
                log.warn("PERMISSION bridge sendResponse failed (graceful): requestId={} err={}",
                    requestId, th.toString());
            }
        }
        PermissionResult result;
        if (isAllow) {
            // [Session S16] 用户批准建议 → apply + persist（对齐 CC handleUserAllow
            //   PermissionContext.ts:291-318：persistPermissions(permissionUpdates) 先行，
            //   再 buildAllow）
            List<PermissionUpdate> updates = parseUpdatedPermissions(updatedPermissions);
            if (!updates.isEmpty()) {
                applyAndPersistUpdates(updates, promptCtx, requestId);
            }
            // [FIX-E askuser-answers] Allow.updatedInput = originalInput 浅拷贝 + answers/annotations
            //   （合并语义对齐 CC AskUserQuestionPermissionRequest.tsx:398-407 submitAnswers）。
            //   originalInput 为 null 时以空对象兜底（Allow.updatedInput 恒非 null）。
            JsonNode updatedInput = buildUpdatedInput(originalInput, answers, annotations);
            // [R32-b9] 透传 STOMP feedback/blocks 到 Allow 决策(CC addToolResult allow 路径)
            String fb = (acceptFeedback != null && !acceptFeedback.isBlank()) ? acceptFeedback : null;
            List<JsonNode> blocks = (contentBlocks != null && !contentBlocks.isEmpty())
                ? List.copyOf(contentBlocks) : List.of();
            result = new PermissionResult.Allow(
                updatedInput,
                new PermissionDecisionReason.Other("User allowed via WebSocket"),
                null, false,
                fb,           // R32-b9 · acceptFeedback(原硬编码 null → 现透传)
                blocks);      // R32-b9 · contentBlocks(原硬编码 null → 现透传)
            if (log.isDebugEnabled() && (fb != null || !blocks.isEmpty() || answers != null || annotations != null)) {
                log.debug("PERMISSION onResponse: allow feedback={} blocks={} hasAnswers={} hasAnnotations={} inputFields={} requestId={}",
                    fb != null, blocks.size(), answers != null, annotations != null,
                    updatedInput.size(), requestId);
            }
        } else {
            // decision 是 null / 未知 / "deny" 都视为 deny
            result = new PermissionResult.Deny(
                "User denied via WebSocket",
                new PermissionDecisionReason.Other("user_denied"),
                null);

            // §14: PermissionDenied hooks — 权限被拒绝（对齐 CC hooks.ts）
            if (hookRegistry != null) {
                try {
                    HookEvent deniedEvent = HookEvent.permissionDenied(
                        null, // toolName not available here
                        originalInput,
                        "User denied via WebSocket",
                        null, null
                    );
                    hookRegistry.executeEvent(deniedEvent);
                } catch (Exception e) {
                    log.warn("HOOK PermissionDenied failed: {}", e.getMessage());
                }
            }
        }
        boolean completed = future.complete(result);
        if (!completed) {
            // 不应该发生 —— remove() 已保证 future 是唯一的
            log.warn("PERMISSION onResponse: future already completed for requestId={}",
                requestId);
        }
    }

    /**
     * [FIX-E askuser-answers] 合并 answers/annotations 进 originalInput 构建 Allow.updatedInput。
     *
     * <p>对齐 CC {@code AskUserQuestionPermissionRequest.tsx:398-407} submitAnswers：
     * {@code updatedInput = {...toolUseConfirm.input, answers: answersToSubmit,
     * ...(Object.keys(annotations).length > 0 && {annotations})}} —— answers 覆盖写入，
     * annotations 仅在非空时并入。originalInput 非 ObjectNode（异常输入）→ 空 ObjectNode 兜底。
     *
     * @param originalInput prompt 调用方暂存的原输入（可为 null）
     * @param answers       AskUserQuestion 答案（{questionText: optionLabel}；可为 null）
     * @param annotations   每问注解（preview/notes；可为 null）
     * @return 合并后的 ObjectNode（originalInput 浅拷贝 + answers/annotations）
     */
    private JsonNode buildUpdatedInput(JsonNode originalInput, JsonNode answers, JsonNode annotations) {
        ObjectNode merged = (originalInput != null && originalInput.isObject())
            ? ((ObjectNode) originalInput).deepCopy()
            : JSON_FACTORY.createObjectNode();
        if (answers != null && answers.isObject() && !answers.isEmpty()) {
            merged.set("answers", answers);
        }
        if (annotations != null && annotations.isObject() && !annotations.isEmpty()) {
            merged.set("annotations", annotations);
        }
        return merged;
    }
    // 诊断 / 测试辅助
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 当前 pending 请求数量（诊断 + 测试用）。
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * [WF-11 · OD-WF1-01 G 族] 解析事件 {@code tool_use_id} · CC original:
     * {@code tool_use_id}（ToolUseBlock.id / PermissionPromptToolResultSchema.ts:15-127）。
     *
     * <p>优先取 {@link ToolUseContext#toolUseId()}（生产按工具调用设置）；null/blank 时
     * 回落 {@code requestId}（兼容既有调用路径 —— Java requestId 通常即 ToolUseBlock.id）。
     */
    private static String resolveToolUseId(ToolUseContext ctx, String requestId) {
        String ctxToolUseId = ctx != null ? ctx.toolUseId() : null;
        if (ctxToolUseId != null && !ctxToolUseId.isBlank()) {
            return ctxToolUseId;
        }
        return requestId;
    }

    /**
     * [WF-11 · OPD-WF8-01-T3] serializeDecisionReason classifier 门控 · CC original:
     * {@code feature('BASH_CLASSIFIER') || feature('TRANSCRIPT_CLASSIFIER')}
     * （structuredIO.ts:69-74）。
     */
    private boolean isClassifierFeatureEnabled() {
        boolean bash = bashClassifierFeature != null && bashClassifierFeature.isEnabled();
        return bash || transcriptClassifierEnabled;
    }

    /**
     * [canUseTool v2] 弹窗描述兜底 · 对齐 CC useCanUseTool.tsx:56-60
     * {@code await tool.description(input, {...})}；失败/无实现时退化为 tool.name()，
     * 保证弹窗描述字段恒有值（CC description 用于队列展示 + 拒绝记录）。
     */
    private static String describeFallback(Tool tool, JsonNode input) {
        try {
            String desc = input != null ? tool.description(input) : tool.description();
            return desc != null && !desc.isBlank() ? desc : tool.name();
        } catch (Throwable th) {
            return tool.name();
        }
    }

    /**
     * [G21] Bash/Sed 权限弹窗渲染文本 · CC original: {@code destructiveWarning}
     * （BashPermissionRequest.tsx:274 {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false) ?
     * getDestructiveCommandWarning(command) : null}）+ sed 编辑渲染
     * （BashPermissionRequest.tsx:89 {@code parseSedEditCommand}）。
     *
     * <p>对齐 CC BashPermissionRequest.tsx:88-103 渲染分支：
     * <ol>
     *   <li><b>sed -i 编辑优先</b> — CC :89 {@code parseSedEditCommand(command)} 非 null 时
     *       sedInfo 分支渲染 {@code SedEditPermissionRequest}（文件编辑风格），Java 端等价输出
     *       sed 编辑渲染文本；</li>
     *   <li><b>否则破坏性命令</b> — CC :274 {@code getDestructiveCommandWarning(command)} 返回
     *       人类可读警告；</li>
     *   <li><b>非 Bash / 无 command / 两者均无匹配</b> → {@code null}
     *       （@JsonInclude NON_NULL 省略字段，前端向后兼容）。</li>
     * </ol>
     *
     * <p>[IMP-H R1] 破坏性命令分支带 feature 门控（对齐 CC BashPermissionRequest.tsx:274
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false)}）：
     * 门控关闭（默认 false，外部构建语义）→ destructiveWarning 恒 null；sed 编辑分支
     * （CC :89 sedInfo）<b>不</b>门控（CC sedInfo 分支无 feature 门控）。feature 数据源 =
     * {@link FeatureFlags#destructiveCommandWarning()}（{@link #featureFlags}）。
     *
     * <p>[B11] PowerShell 破坏性命令渲染分支（CC PowerShellPermissionRequest.tsx:60 对齐）：
     * 仅破坏性命令警告（PS 特有模式集 {@link PowerShellTool#getDestructiveCommandWarning(String)}），
     * 与 Bash 共用同一 {@code nexusai.feature.destructive-command-warning} 门控；无 sed 编辑分支。
     *
     * <p>G21 拍板：渲染结果随 {@link MessagePermissionRequestEvent#getWarning()} 通过
     * STOMP/WebSocket 推送给前端弹窗（非 API）。
     *
     * @param tool  权限请求的工具（仅 {@link ToolNameConstants#BASH_TOOL_NAME} /
     *              {@link ToolNameConstants#POWER_SHELL_TOOL_NAME} 参与渲染）
     * @param input 工具输入（Bash/PowerShell inputSchema.command 为待检测命令；非 Bash/PowerShell
     *              或缺 command → null）
     * @return 弹窗渲染文本；非 Bash/PowerShell/Sed 场景 → null
     */
    private String renderPermissionWarning(Tool tool, JsonNode input) {
        if (tool == null || input == null || !input.isObject()) {
            return null;
        }
        String toolName = tool.name();
        // [B11] PowerShell 权限弹窗破坏性命令警告 · 对齐 CC PowerShellPermissionRequest.tsx:60 ——
        //   getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false) ?
        //   getDestructiveCommandWarning(command) : null（PowerShellTool/destructiveCommandWarning.ts）。
        //   PS 无 sed 编辑分支（CC PowerShellPermissionRequest.tsx 无 parseSedEditCommand），仅破坏性命令渲染。
        if (ToolNameConstants.POWER_SHELL_TOOL_NAME.equals(toolName)) {
            return renderPowerShellDestructiveWarning(input);
        }
        // 非 Bash / PowerShell → 无渲染（对齐 @JsonInclude NON_NULL 省略字段，前端向后兼容）
        if (!ToolNameConstants.BASH_TOOL_NAME.equals(toolName)) {
            return null;
        }
        String command = input.path("command").asText(null);
        if (command == null || command.isBlank()) {
            return null;
        }
        // CC BashPermissionRequest.tsx:89 sedInfo 优先 → sed 编辑渲染（文件编辑风格）。
        // [IMP-H R1] sed 分支不门控（CC sedInfo 分支 BashPermissionRequest.tsx:89 无 feature 门控）。
        SedEditParser.SedEditInfo sedInfo = SedEditParser.parseSedEditCommand(command);
        if (sedInfo != null) {
            return "sed 编辑 " + sedInfo.filePath()
                + ": s/" + sedInfo.pattern() + "/" + sedInfo.replacement() + "/" + sedInfo.flags();
        }
        // CC BashPermissionRequest.tsx:274 destructiveWarning — feature 门控（默认 false → null）
        if (featureFlags == null || !featureFlags.destructiveCommandWarning()) {
            return null;
        }
        return DestructiveCommandWarning.getDestructiveCommandWarning(command);
    }

    /**
     * [B11] PowerShell 权限弹窗破坏性命令警告渲染 · CC original: {@code destructiveWarning}
     * （PowerShellPermissionRequest.tsx:60 {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false) ?
     * getDestructiveCommandWarning(command) : null}，command 来自 PowerShellTool.inputSchema）。
     *
     * <p>与 Bash 版（{@code renderPermissionWarning} Bash 分支）共用同一 feature 门控
     * {@code nexusai.feature.destructive-command-warning}（默认 false → 外部构建语义
     * destructiveWarning 恒 null，对齐 CC 外部构建默认关）；门控开启才调用
     * {@link PowerShellTool#getDestructiveCommandWarning(String)}（PS 特有模式集，
     * tools/PowerShellTool/destructiveCommandWarning.ts，与 Bash 模式集不同）。
     *
     * @param input 工具输入（PowerShell inputSchema.command 为待检测命令；缺 command → null）
     * @return 弹窗渲染文本；门控关 / 缺 command / 无命中 → null
     */
    private String renderPowerShellDestructiveWarning(JsonNode input) {
        if (featureFlags == null || !featureFlags.destructiveCommandWarning()) {
            return null;
        }
        String command = input.path("command").asText(null);
        if (command == null || command.isBlank()) {
            return null;
        }
        return PowerShellTool.getDestructiveCommandWarning(command);
    }

    /**
     * 构造 STOMP topic 路径（{@code /topic/sessions/{sessionId}/permission-requests}）。
     *
     * <p>WHY 暴露为静态：测试可断言"实际推送的 topic 路径格式正确"。
     */
    public static String topicFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is blank");
        }
        // [session-id-short] sessionId 已 short 恒等直拼（originalKey 反解已删，前端订阅 short 命中）
        return "/topic/sessions/" + sessionId + "/permission-requests";
    }
}

package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.PermissionRejectMessages;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.toolsearch.SchemaNotSentHint;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PromptRequester;
import com.nexusai.application.agent.permission.hook.PromptRequesterFactory;
import com.nexusai.application.agent.telemetry.FileExtensionExtractor;
import com.nexusai.application.agent.telemetry.McpServerToolSanitizer;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.mcp.McpServerScope;
import com.nexusai.application.agent.mcp.McpAuthError;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 1:1 复刻 CC StreamingToolExecutor · Phase 6·s02.5 真流式并行。
 *
 * <p><b>[R32-#15] 架构对齐源修正</b>: Java 端实际对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/StreamingToolExecutor.ts}(完整 530 行),
 * 而非 {@code query.ts:561}(旧文档误引用)。批次 6 修订 JavaDoc 与所有内部行号引用。
 * 同时 {@link ToolCallPartitioner} 对齐 {@code toolOrchestration.ts:88-125 partitionToolCalls},
 * 两者职责独立 (Partitioner 是 batch 分配, StreamingToolExecutor 是流式执行)。
 *
 * <h2>核心设计</h2>
 * <ul>
 *   <li><b>add 立即 execute</b>: 不再"全部 add 完才 executeAll", 而是 add 一个立即 processQueue,
 *       条件满足立即后台 execute</li>
 *   <li><b>canExecuteTool 守门</b>:
 *     <ul>
 *       <li>当前无 executing → 立即可执行</li>
 *       <li>本工具 safe + 所有 executing 也 safe → 可并行</li>
 *       <li>本工具 unsafe → 等所有 executing 完成 (CC line 129-135)</li>
 *     </ul>
 *   </li>
 *   <li><b>getRemainingResults</b>: stream done 后, await 所有 in-flight, 按 add 顺序返回</li>
 *   <li><b>顺序缓冲</b>: 返回值按 add 顺序 (LinkedHashMap by id), 不按完成顺序</li>
 *   <li><b>[Phase 2 PR 1]</b> {@code permissionGate} 插入点:
 *     {@link #executeAsync} 调 {@code tool.execute} 之前,
 *     调 {@link ToolPermissionGate#check} 做 3 态决策 (allow/deny/ask).
 *     {@code gate == null} → 退化为 allow (向后兼容).</li>
 *   <li><b>[R32 批次 2-4]</b> hook 串联 + abort 决策 + sibling abort 接入 (见方法注释).</li>
 *   <li><b>[R32-b15 C7]</b> discard 完整: queued 不启动 + in-flight 生成 synthetic fallback error
 *       + getRemainingResults discarded 短路返回 (CC toolExecution.ts:413-415/454-456).</li>
 *   <li><b>[R32-b15 C8+C10]</b> pendingProgress 队列 + progressAvailableResolve 唤醒器 +
 *       MessageUpdate 流式 yield + YIELDED 状态 + 非阻塞 drain (CC toolExecution.ts:407-440).</li>
 *   <li><b>[R32-b15 C11]</b> updateInterruptibleState: 仅所有 executing 工具 interruptBehavior="cancel"
 *       时设 true (CC StreamingToolExecutor.ts:254-260).</li>
 *   <li><b>[R32-b15 C15]</b> classifyToolError: 异常分类注入 ToolResult.errorCategory
 *       + formatError 用户可读 + telemetry-safe (CC toolExecution.ts:150-170/615-680/1631-1694).</li>
 * </ul>
 */
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    // [R32-b12 D-5 P1] Telemetry · 对齐 CC toolExecution.ts:1134-1395 (success) +
    //   1639-1689 (PostToolUseFailure 双发).
    //
    // [R32-b12 Fix-v3 P1-1] 修复 telemetry 字段 null 短路问题:
    //   之前 @Autowired(required=false) 让 Spring 自动注入 (单测场景有效),
    //   但 LlmAgentLoop 手动 `new StreamingToolExecutor(...)` 时 (1613/3508)
    //   Spring 不感知该实例 → telemetry 字段始终 null → 所有 8 个埋点 (success / result /
    //   failure / decision / cancelled / error) 实际被 `if (telemetry == null) return`
    //   短路, 完全不工作. 这是 P1-1 阻塞缺陷: 所有 streaming 路径 telemetry 全失效.
    //
    //   修复: 移除 @Autowired(required=false) 字段注入, 改用 setTelemetry setter,
    //   LlmAgentLoop 在 manual new 后显式调 setTelemetry(this.telemetry).
    //   保留字段 (非 final) 允许 setter 注入; @Autowired 注解移除避免误导 (无 setter
    //   也不会被自动注入). null-safe: 注入前所有 telemetry 调用保持原短路行为,
    //   注入后 telemetry 立即生效.
    private Telemetry telemetry;

    // [R32-b12 D-4] 工具决策归因 map · 由 LlmAgentLoop.applyPermissionFilter 完成后注入.
    // 用于 logOTelEvent('tool_result') 注入 decision_source / decision_type 字段.
    // volatile + Map.copyOf 注入: 简单场景下不会变; 若 LlmAgentLoop 注入后再 mutate 也不会读脏数据
    // (Map.copyOf 在 setter 中执行, 注入后内部引用固定).
    private volatile java.util.Map<String, ToolDecisionInfo> toolDecisions = java.util.Map.of();

    /**
     * [R32-b15 C11] AgentState 引用 · 由 LlmAgentLoop 在 manual new 后显式注入,
     * 供 {@link #executeAsync} 入口/出口同步 {@code setHasInterruptibleToolInProgress}.
     *
     * <p>WHY (CLAUDE.md 规则 7 · 显式选择): 不扩展 {@link ToolUseContext} 16 字段 record
     * (会影响所有 8 个旧构造重载). 用单独 setter 解耦 — 与 setTelemetry / setToolDecisions
     * 模式一致. null-safe: 未注入时 interruptible state 同步 noop (向后兼容单测).
     */
    private volatile com.nexusai.application.agent.AgentState agentStateRef = null;

    /**
     * [工具调用实时推] 实时推送通道 · 由 {@link AgentLoopContext#buildStreamingExecutor}
     * 在 manual new 后经 {@link #setToolStreamPublisher} 注入. 承载 SimpMessagingTemplate
     * + stream topic + 会话定位三元组.
     *
     * <p>WHY (CLAUDE.md 规则 7 · 显式选择): 不扩展 6 参 canonical 构造器 (501-503, 波及
     * ~10 个测试文件), 用独立 setter 注入 — 与 setTelemetry / setAgentState 先例一致.
     * null-safe: 未注入 (或 wsTemplate/streamTopic 为 null) 时推送 helper 早返 no-op
     * (向后兼容 cron / 非流式 / 单测路径, R6).
     */
    private volatile ToolStreamPublisher toolStream;

    /**
     * [工具调用实时推] 推送通道载体 · immutable record, volatile 字段持引用保证并发可见.
     */
    private static record ToolStreamPublisher(
            SimpMessagingTemplate wsTemplate,
            String streamTopic,
            String sessionId,
            String userMessageId) {}

    /**
     * [R32-b15 Stage 2 C5] Safe context modifier deferred queue ·
     * 对齐 CC {@code toolOrchestration.ts:30-62} runToolsConcurrently
     * {@code queuedContextModifiers} Map (按 toolUseId key) 在批次结束后按调用
     * 原序提交.
     *
     * <p><b>WHY (CLAUDE.md 规则 1 · 先思后码)</b>: Java 端当前立即 applyExtendedToolResult
     * (line 782 注释明确记录与 CC 偏差), safe 并发完成顺序由线程调度决定, 多 modifier
     * 立即执行会污染上下文顺序. 本 Stage 2 引入 deferred 模式: 当
     * {@link #deferContextModifier} 为 {@code true} (默认开启, 顶层 runTools 用)
     * 时, 工具完成不立即 apply, 而把 (toolUseId, Consumer) 入队; 批次结束后
     * {@link #applyDeferredContextModifiers(ToolUseContext)} 按 add 顺序真实 apply.
     *
     * <p><b>单线程约束</b>: LinkedHashMap + 同步块 (CC 端批次提交 single-threaded);
     * 入队在 executeAsync (多线程) 阶段串行化到 map; drain 在
     * {@code LlmAgentLoop.runTools} 单线程路径执行, 无并发问题.
     */
    // [A1 → P0-2] contextModifier 签名 Function<ToolUseContext,ToolUseContext> (CC Tool.ts:330).
    //   [P0-2] 由 SkillTool inline 三件套 (SkillTool.ts:775-839) 真实填充 (非恒 null), 此 Map
    //   现承载真实 modifier; 批次结束后 applyDeferredContextModifiers 按 add 顺序真实 apply.
    //   LinkedHashMap 保序: add 顺序 == 工具原序, 不受 completion 时序影响.
    private final java.util.LinkedHashMap<String, java.util.function.Function<com.nexusai.application.agent.tool.ToolUseContext, com.nexusai.application.agent.tool.ToolUseContext>> deferredContextModifiers
        = new java.util.LinkedHashMap<>();

    /**
     * [R32-b15 Stage 2 C5] 是否启用 deferred context modifier 模式.
     * 默认 {@code false} (与现有立即 apply 行为一致, 向后兼容单测).
     * {@link LlmAgentLoop} 顶层 {@code runTools} 入口在 manual new 后 set true.
     *
     * <p>作用域: 全部 tool batch. unsafe 工具在 CC 端是立即 apply 单项 (单执行者);
     * Java 端简化为全部统一按 add 顺序提交 (与 CC safe 行为一致),
     * 因为 unsafe 单项 modifier 顺序天然等于 add 顺序 (串行执行).
     */
    private volatile boolean deferContextModifier = false;

    // [R32-b12 D-5] 工具执行开始时间 (per-call) · 用于计算 durationMs.
    // TrackedTool 在构造时设置, 成功路径读取.
    private final java.util.Map<String, Long> toolStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    // [R32-b12 Fix-v3 P1-2] 移除 preToolHookStartTimes map: hook 计时改为 hook 入口 startNs +
    //   hook 出口立即计算 durationMs 写入 t.preToolHookDurationMs 字段, 不再延迟到 emit 计算.
    //   旧实现: map.put() 在 hook 入口, map.remove() 在 finally, map.remove() 时计算
    //   (now - startNs) 包含后续 permission gate + tool.execute + emit 间隔时间, 不准确.

    private final ToolRegistry registry;
    private final ExecutorService executor;
    /**
     * s05-P1-2: 工具执行上下文（可为 null）· 对齐 CC call(input, context)。
     * 非 null 时以 {@code tool.execute(call, ctx)} 派发，工具可读 agentId/sessionId 做会话分桶。
     */
    private final ToolUseContext ctx;

    /** [Session J 方案 A] SubagentTool 特化执行参数 · 与 ToolUseContext 顶层字段解耦. */
    private volatile AgentOptions subagentAgentOptions = AgentOptions.defaultOptions();
    private volatile ForkSubagentMessages.Message subagentAssistantMessage;

    // [A1] 输入清洗器 · 由 LlmAgentLoop buildStreamingExecutor 注入,executeAsync 入口
    //   字段剥离阶段使用 (§3.4 defense-in-depth). null-safe: null 时跳过 (向后兼容单测).
    private volatile InputSanitizer inputSanitizer;

    // [A1] 工具输入验证器 · 由 LlmAgentLoop buildStreamingExecutor 注入,executeAsync 入口
    //   schema 验证 + validateInput 语义验证阶段使用 (对齐 CC toolExecution.ts:615-733).
    //   null-safe: null 时跳过 (向后兼容单测).
    private volatile ToolInputValidator inputValidator;

    /**
     * [R32-c-1] PermissionDenied retry hook 触发开关 · 对齐 CC
     * {@code Open-ClaudeCode/src/utils/hooks.ts:3529-3562 executePermissionDeniedHooks} +
     * {@code services/tools/toolExecution.ts:1075-1101} retry hook 触发条件.
     *
     * <p>WHY: CC retry hook 触发条件是
     * {@code feature('TRANSCRIPT_CLASSIFIER') && decisionReason.classifier === 'auto-mode'}
     * (toolExecution.ts:1076-1078). Java 端用此 boolean 作为该 feature 的镜像: 只有
     * {@link #setTranscriptClassifierEnabled(boolean)} 显式启用时, executeAsync 才会在
     * auto-mode Deny 路径触发 PermissionDenied hook chain.
     *
     * <p>字段默认 false 仅为非 Spring 直构（测试）兜底, 不代表生产默认. 生产默认由
     * LlmAgentLoop {@code @Value("${nexusai.classifier.transcript.enabled:true}")}
     * （application.yml:117 enabled: true, M3.4 用户确认决策）经
     * AgentLoopContext.buildStreamingExecutor 显式注入, 与 CC TRANSCRIPT_CLASSIFIER
     * feature flag（bun:bundle 编译期默认）契约对齐为开启.
     */
    private volatile boolean transcriptClassifierEnabled = false;

    /**
     * [R32-c-1] PermissionDenied hook 执行器 · 默认 null (向后兼容, 无依赖时跳过整条 retry 链);
     * 由 LlmAgentLoop.buildStreamingExecutor 注入, 与 hookRegistry 配对.
     */
    private volatile PermissionDeniedHookExecutor permissionDeniedHookExecutor;

    /**
     * [R32-c-1] 重试元消息工厂 · 默认 null (向后兼容, 不注入 isMeta 消息); 由 LlmAgentLoop
     * buildStreamingExecutor 注入, retry hook 触发后通过 {@link #extendedResultHandler}
     * 推送到 AgentState.messages (复用 {@link ToolResult} newMessages 桥, 不破坏 message 流).
     */
    private volatile RetryMessageFactory retryMessageFactory;

    /**
     * s07-P1-3 wiring: {@code ToolResult<T>} 折入的应用回调 · 对齐 CC SkillTool.ts:735-860 call() 返回协议.
     *
     * <p>A1 退役 ExtendedToolResult 后, CC 的 newMessages + contextModifier + structuredOutput
     * 已折入 {@link ToolResult} 本身 (Tool.ts:323/330/331-335). 工具返回带这些载荷的
     * {@code ToolResult<?>} 时, 本回调 (由 LlmAgentLoop 提供, 现签名
     * {@code Consumer<AgentToolResult<?>>}) 走 {@link ToolResultApplier#apply} 应用:
     * 追加 newMessages + 暂存 structuredOutput + [IT-6] 产出 structured_output attachment
     * (对齐 CC toolExecution.ts:1272-1279; 不进 LLM).
     *
     * <p>null → 普通 ToolResult 行为 (不应用 newMessages/contextModifier).
     */
    private final java.util.function.BiConsumer<AgentToolResult<?>, String> extendedResultHandler;
    /**
     * [Phase 2 PR 1] 工具执行权限门 · 对齐 CC useCanUseTool.tsx:27-191 三态分支.
     *
     * <p>非 null 时, 在调 {@code tool.execute} 之前先调
     * {@link ToolPermissionGate#check} 做 allow/deny/ask 决策:
     * <ul>
     *   <li>{@link ToolPermissionGate.Decision#ALLOW} → 继续执行</li>
     *   <li>{@link ToolPermissionGate.Decision#DENY} → 注入 ToolResult.error, 不调 tool.execute</li>
     *   <li>{@link ToolPermissionGate.Decision#ASK} → 由 gate.check 内部同步阻塞 prompter
     *       转 ALLOW/DENY, 不会真正返回 ASK</li>
     * </ul>
     *
     * <p>null → 退化为 allow (Phase 2 PR 1 之前的向后兼容行为, 单测可绕开门控).
     */
    private final ToolPermissionGate permissionGate;
    /**
     * [hooks_v3 H-PERM-02 · 1-7] Hook 权限决策解析器 · 实例注入.
     *
     * <p>对齐 CC {@code resolveHookPermissionDecision} (toolHooks.ts:332-433): 原静态单例
     * {@code HookPermissionResolver.SHARED} + {@code setSharedSandboxManager} 已删除,
     * 由本实例承载 resolver (sandbox 经 {@link #setSandboxManager} → bean setter 注入).
     * 默认 {@code new HookPermissionResolver()} (手动构造 / 单测 / 未接线时) — 无状态纯逻辑
     * 类单实例复用安全; 生产经 {@link #setPermissionResolver} 注入 {@link Component} bean
     * (inputValidator 等 bean setter 注入生效), sandbox 仍经 {@link #setSandboxManager}
     * 转接到该 bean 实例 setter.
     */
    private HookPermissionResolver permissionResolver = new HookPermissionResolver();
    /**
     * [P0-3 强化] Hook 注册中心 · 对齐 CC toolHooks.ts PreToolUse/PostToolUse hook 串联点.
     *
     * <p>非 null 时, 在 {@code tool.execute} 前调
     * {@link HookRegistry#executePreToolUse} 得到 16 字段
     * {@link AggregatedHookResult} 聚合,按 CC 7 类 case 分支:
     * <ul>
     *   <li>{@link AggregatedHookResult#preventContinuation()} → 立即阻断</li>
     *   <li>{@link AggregatedHookResult#permissionBehavior()} (Deny) → ToolResult.error</li>
     *   <li>{@link AggregatedHookResult#updatedInput()} != null → 替换 input (CC 全替换语义)</li>
     *   <li>{@link AggregatedHookResult#additionalContexts()} → 推附加上下文消息</li>
     *   <li>{@link AggregatedHookResult#stopReason()} + preventContinuation → 短路 + 发 stop 消息</li>
     * </ul>
     *
     * <p>在 {@code tool.execute} 后调 {@link HookRegistry#executePostToolUse} 做 MCP-specific 分流:
     * <ul>
     *   <li>MCP 工具 + {@link GenericHook.HookResult#updatedMCPToolOutput()} != null → 替换 toolOutput</li>
     *   <li>non-MCP + {@link GenericHook.HookResult#blockingError()} != null → 作为 feedback 追加到 result</li>
     *   <li>non-MCP + {@link GenericHook.HookResult#additionalContext()} != null → 追加 metadata</li>
     * </ul>
     *
     * <p>null → 退化为不串联 hook (向后兼容批次 1 之前的行为).
     */
    private final HookRegistry hookRegistry;
    /**
     * [IMP-RS-01 DEL-01e 补回] prompt 回调工厂 (未绑定) · 对齐 CC {@code toolUseContext.requestPrompt}
     * (REPL.tsx:2520, {@code feature('HOOK_PROMPTS') ? requestPrompt : undefined}).
     *
     * <p>null (默认) = prompt 通道关闭, 对齐 CC 发布产物 (package/cli.js v2.1.88) 编译期
     * {@code feature('HOOK_PROMPTS')=false} 时行为; 非 null = PreToolUse 链按
     * {@code factory.bind("PreToolUse:<toolName>", toolUseSummary)} 绑定后透传给
     * {@link HookRegistry#executePreToolUse(String, JsonNode, ToolUseContext, String, String, PromptRequester)}
     * (对齐 CC toolHooks.ts:474 透传 toolUseContext.requestPrompt). UI 消费端 (等价 CC
     * setPromptQueue) 经 {@link #setPromptRequesterFactory} 注入。
     */
    private volatile PromptRequesterFactory promptRequesterFactory;
    /**
     * [R32-#29] 兄弟级 AbortController · 对齐 CC StreamingToolExecutor.ts:44-47 siblingAbortController.
     *
     * <p>构造时从 {@code ctx.abortController()} 创建 child. 当某 Bash 工具 errored 时,
     * 调用 {@code siblingAbortController.abort("sibling_error")} 同步杀掉所有兄弟工具
     * (per-tool child controller 通过该 abort 传播级联取消).
     *
     * <p>{@code ctx == null} 时退化为独立 AbortController(无 parent 传播).
     */
    private final AbortController siblingAbortController;
    /** 按 add 顺序 (ConcurrentHashMap by id, 插入时 order 由 map iteration 保证). */
    private final Map<String, TrackedTool> tools = new LinkedHashMap<>();
    private final AtomicInteger erroredCount = new AtomicInteger(0);
    private volatile boolean discarded = false;
    /**
     * [R32-b15 C10] 已 drain 的工具 ID 集合 · 对齐 CC StreamingToolExecutor.ts:407-440
     * getCompletedResults() 非阻塞返回已 COMPLETED 工具, 已返回的不再重复. Set 用于 O(1) 去重.
     */
    private final Set<String> drainedIds = ConcurrentHashMap.newKeySet();
    /**
     * [R32-b15 C8] 待消费 progress 事件队列 · 对齐 CC StreamingToolExecutor.ts:407 pendingProgress.
     * LinkedBlockingQueue 保证多线程 enqueueProgress / peekPendingProgress 安全.
     */
    private final java.util.concurrent.LinkedBlockingQueue<Tool.ToolProgress> pendingProgress =
        new java.util.concurrent.LinkedBlockingQueue<>();
    /**
     * [R32-b8 #3 P0-1 校正] 内部 in-progress set · 对齐 CC StreamingToolExecutor.ts:267/525
     * 维护 in-flight tool_use_id 的可变状态. fire/clear 先 mutate 内部 set,
     * 再调 Function.apply(current) 接收 immutable snapshot (返回给调用方).
     * 使用 ConcurrentHashMap.newKeySet() 保证多线程 fire/clear 安全.
     */
    private final Set<String> inProgressToolUseIDs = ConcurrentHashMap.newKeySet();
    /**
     * [R32-#28/#29] 任一 Bash 工具 error 时设为 true · 对齐 CC hasErrored.
     * 触发 siblingAbortController.abort("sibling_error").
     */
    private volatile boolean hasErrored = false;
    /**
     * [R32-#29] Bash 错误时记录出错的工具描述 · 对齐 CC erroredToolDescription.
     * 用于 createSyntheticErrorMessage(sibling_error) 文案 "parallel tool call ${desc} errored".
     */
    private volatile String erroredToolDescription = "";

    /**
     * [A2 强化] hookRegistry=null warn 频率控制 · 对齐 CC backward-compat 行为.
     *
     * <p>WHY: R32 Phase 2 PR 1 之前大量单测 / Spring-less 场景构造 executor 不传 hookRegistry,
     * 期望 hook 链完全跳过而非 NPE. 首次检测到 hookRegistry==null 时 warn 一次, 后续静默
     * (避免 per-tool-call spam). volatile + compareAndSet 保证并发 executeAsync 路径下只触发一次.
     *
     * <p>对齐 R32-b12 Fix-v3 P1-1 telemetry 注入策略: 同样用一次性 flag 防止日志爆炸.
     */
    private final java.util.concurrent.atomic.AtomicBoolean nullHookRegistryWarned =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * [R32-b15 C8] 共享 progressAvailableResolve · 对齐 CC StreamingToolExecutor.ts:407
     * progressAvailableResolve (Promise resolve function). getCompletedResults() 阻塞等待
     * 进度或结果可用时被此唤醒器唤醒. null 表示无人等待 → progress 直接入队不唤醒.
     *
     * <p>采用 {@link java.util.concurrent.atomic.AtomicReference} 持有 resolve 回调,
     * 一次性的 CompletableFuture-style 唤醒: resume / consume 后立即置 null,
     * 下次等待时由消费者重新注入. 这是与 CC 端每次 await 后重建 Promise resolve 等价的
     * Java 简化版.
     */
    private final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<List<Tool.ToolProgress>>>
        progressAvailableResolve = new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * [R32-b10 B4] 并发执行 safe 工具的上限 · 对齐 CC {@code getMaxToolUseConcurrency()}
     * (Open-ClaudeCode/src/services/tools/toolOrchestration.ts:8-12).
     *
     * <p><b>D-1 偏差 (CLAUDE.md 规则 12 · Fail loud)</b>: 任务 brief 描述 env 名为
     * {@code MAX_TOOL_USE_CONCURRENCY}, CC 真源 (toolOrchestration.ts:10) 为
     * {@code CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY}. 按 CLAUDE.md 规则 11 (匹配既有规范)
     * 选择 CC 真源. 在 commit message + progress 文件显式记录此偏差.
     *
     * <p><b>Java static 字段初始化时机 (CLAUDE.md 规则 12 · Fail loud)</b>:
     * env 修改需重启 JVM 才能生效 (与 CC 函数式调用每次读 env 不同). 这是有意设计 — 静态
     * 字段类加载时一次性初始化, 与 brief 描述的 static 字段语义一致. 详见 L-1 限制.
     *
     * <p><b>testability 偏差</b> (CLAUDE.md 规则 12 · Fail loud): 字段声明为非 final
     * (volatile 而非 final). 原因: Java 17+ 完全阻止反射修改 {@code static final}
     * 字段 (即使非编译期常量), 但边界测试需要注入不同 MAX 值验证守门逻辑. 删除
     * {@code final} 不破坏封装 (字段仍为 private, 外部代码无法修改). 这是与 brief
     * 描述 "static final" 的有意偏差, 已在 commit message 显式记录.
     *
     * <p>默认值 10 (CC 真源 + brief 一致).
     */
    private static volatile int MAX_TOOL_USE_CONCURRENCY =
        parseMaxConcurrency(System.getenv("CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY"));

    /**
     * [R32-b10 B4] env 字符串解析 · 对齐 CC {@code parseInt(env || '', 10) || 10}
     * (toolOrchestration.ts:8-12).
     *
     * <p>CC 行为: parseInt 失败 (NaN) / 结果为 0 → fallback 10. Java 端用
     * {@link Integer#parseInt(String)} + {@code v != 0} 显式校验 + try/catch 实现。
     * 负数必须保留：CC 的 {@code parseInt('-3', 10) || 10} 中 -3 为 truthy，返回 -3。
     *
     * <p>package-private 而非 private, 便于 R32B10_MaxToolUseConcurrencyTest 直接覆盖
     * 7 个 env 解析边界用例 (无需反射).
     *
     * @param envValue env 字符串 (可为 null)
     * @return 解析后的并发上限 (默认 10)
     */
    static int parseMaxConcurrency(String envValue) {
        if (envValue == null) {
            return 10;
        }
        try {
            int v = Integer.parseInt(envValue.trim());
            // 与 CC 真源的 || 短路一致：仅 0 fallback，负数解析成功后保留。
            return v != 0 ? v : 10;
        } catch (NumberFormatException e) {
            log.warn("CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY 解析失败, 使用默认值 10: envValue='{}' err={}",
                envValue, e.toString());
            return 10;
        }
    }

    public StreamingToolExecutor(ToolRegistry registry) {
        this(registry, defaultExecutor(), null, null, null, null);
    }

    public StreamingToolExecutor(ToolRegistry registry, ExecutorService executor) {
        this(registry, executor, null, null, null, null);
    }

    /** s05-P1-2: 带 ToolUseContext 构造（对齐 CC 每次 tool call 携带 context）。 */
    public StreamingToolExecutor(ToolRegistry registry, ToolUseContext ctx) {
        this(registry, defaultExecutor(), ctx, null, null, null);
    }

    /**
     * s07-P1-3 wiring: 带 ToolUseContext + ExtendedToolResult handler 构造.
     *
     * <p>LlmAgentLoop 提供 extendedResultHandler 处理 ExtendedToolResult (SkillTool 返回值),
     * 执行 dispatch 应用: 追加 newMessages + 调 contextModifier.
     */
    public StreamingToolExecutor(ToolRegistry registry, ToolUseContext ctx,
                                 java.util.function.BiConsumer<AgentToolResult<?>, String> extendedResultHandler) {
        this(registry, defaultExecutor(), ctx, extendedResultHandler, null, null);
    }

    public StreamingToolExecutor(ToolRegistry registry, ExecutorService executor, ToolUseContext ctx) {
        this(registry, executor, ctx, null, null, null);
    }

    public StreamingToolExecutor(ToolRegistry registry, ExecutorService executor, ToolUseContext ctx,
                                 java.util.function.BiConsumer<AgentToolResult<?>, String> extendedResultHandler) {
        this(registry, executor, ctx, extendedResultHandler, null, null);
    }

    /**
     * [A1 撤外层] 5 参便捷构造器 · 含 gate + hookRegistry · 用 {@link #defaultExecutor()}.
     *
     * <p>WHY (CLAUDE.md 规则 7 · 显式暴露冲突): A1 撤外层后, AgentLoopContext.buildStreamingExecutor
     * 需要传入 gate + hookRegistry 同时不暴露 ExecutorService. 本构造器用 defaultExecutor()
     * + 把 gate/hook 一起传入, 是 A1 撤外层必需的最小新增, 也是生产唯一入口
     * (AgentLoopContext:1322).
     *
     * <p>[P-AL-08 精简] 旧 {@code (registry, executor, ctx, handler, gate)} 5 参构造
     * (Phase 2 PR 1) 全工程零 caller, 已删除 —— 生产与测试统一走本构造 / 6 参终极构造,
     * 不再保留同参位数的双轨 5 参形态 (对齐 CC 单构造语义, StreamingToolExecutor.ts:53-62).
     *
     * @param registry     工具注册表
     * @param ctx          工具调用上下文
     * @param extendedHandler ExtendedToolResult 应用回调
     * @param gate         权限门 (可为 null → 退化为 allow)
     * @param hookRegistry hook 注册中心 (可为 null → 不串联 hook)
     */
    public StreamingToolExecutor(ToolRegistry registry, ToolUseContext ctx,
                                 java.util.function.BiConsumer<AgentToolResult<?>, String> extendedHandler,
                                 ToolPermissionGate gate, HookRegistry hookRegistry) {
        this(registry, defaultExecutor(), ctx, extendedHandler, gate, hookRegistry);
    }

    /**
     * [R32-#17] 6 参终极构造器（含 ToolPermissionGate + HookRegistry）· 对齐 CC 完整 hook 串联.
     *
     * <p>新增 {@code hookRegistry} 参数,与 CC {@code toolHooks.ts} 串联模式对齐.
     * 非 null 时, {@link #executeAsync(TrackedTool)} 入口调
     * {@link HookRegistry#executePreToolUse} 决定是否阻断 / 修改 input,
     * 出口调 {@link HookRegistry#executePostToolUse} 做 MCP-specific 分流.
     *
     * <p>{@code hookRegistry} 可为 null —— null 时退化为不串联 hook (向后兼容批次 1).
     * 本构造是 canonical 全参构造, 其余便捷构造 (1/2/3/4/5 参) 全部委托本构造.
     *
     * @param registry              工具注册表
     * @param executor              后台线程池
     * @param ctx                   工具调用上下文
     * @param extendedResultHandler ExtendedToolResult dispatch handler（可为 null）
     * @param gate                  工具执行权限门（可为 null）
     * @param hookRegistry          hook 注册中心（可为 null → 不串联 hook）
     * @since R32
     */
    public StreamingToolExecutor(ToolRegistry registry, ExecutorService executor, ToolUseContext ctx,
                                 java.util.function.BiConsumer<AgentToolResult<?>, String> extendedResultHandler,
                                 ToolPermissionGate gate, HookRegistry hookRegistry) {
        if (registry == null) throw new IllegalArgumentException("registry is null");
        if (executor == null) throw new IllegalArgumentException("executor is null");
        this.registry = registry;
        this.executor = executor;
        this.ctx = ctx;
        this.extendedResultHandler = extendedResultHandler;
        this.permissionGate = gate;
        this.hookRegistry = hookRegistry;
        // [R32-#29] siblingAbortController 是 ctx.abortController() 的 child.
        // ctx == null 时退化为独立 AbortController(无 parent 传播).
        this.siblingAbortController = ctx != null
            ? ctx.abortController().createChild()
            : new AbortController();
        // [B GAP-HOOK-02] 装配 PreToolUse hook 错误 attachment sink ·
        //   对齐 CC utils/hooks.ts:2698-2730: hook 异常 → hook_error_during_execution attachment
        //   (LLM/transcript 可见), 工具继续执行. HookRegistry 内部 catch 单个 hook 异常后经
        //   本 sink 回调, 惰性读 agentStateRef (setAgentState 后生效), 与 inject*HookAttachments
        //   同一 attachment 通道. hookRegistry 为 null 时不装配.
        if (hookRegistry != null) {
            hookRegistry.setPreToolUseHookErrorSink((hookName, toolUseId, th) -> {
                com.nexusai.application.agent.AgentState st = this.agentStateRef;
                if (st == null) return;
                try {
                    st.appendAttachment(
                        com.nexusai.application.agent.attachment.AttachmentMessageDto
                            .hookErrorDuringExecution(
                                hookName != null ? hookName : "PreToolUse",
                                toolUseId != null ? toolUseId : "",
                                "PreToolUse",
                                th != null && th.getMessage() != null
                                    ? th.getMessage() : String.valueOf(th)));
                } catch (Throwable attTh) {
                    log.warn("TOOL hook error attachment 注入失败: hook={} err={}",
                        hookName, attTh.toString());
                }
            });
        }
    }

    /**
     * Add a tool to the queue. <b>Will start executing immediately if conditions allow</b>
     * (对齐 CC StreamingToolExecutor.addTool — query.ts:76).
     *
     * <p>线程安全: 可从多个线程 (LLM stream 回调) 并发调用。
     */
    public void add(ToolUseBlock call) {
        add(call, null, null);
    }

    /** Add a tool with its per-call progress callback. */
    public void add(ToolUseBlock call, Consumer<Tool.ToolProgress> onProgress) {
        add(call, null, onProgress);
    }

    /**
     * [R32-b15 Stage 2 C5] Add a tool with parent assistant lineage + per-call progress.
     *
     * <p>对齐 CC {@code toolOrchestration.ts:130-139,152-172} 父 assistant 查找;
     * parent 为 {@code null} 时与 {@link #add(ToolUseBlock, Consumer)} 等价 (向后兼容
     * 单测场景). parent 不为 null 时, 写入 {@link TrackedTool#parent},
     * 工具结果 / ExtendedToolResult / telemetry 归因时引用.
     */
    public void add(ToolUseBlock call, ToolParent parent, Consumer<Tool.ToolProgress> onProgress) {
        if (call == null) throw new IllegalArgumentException("call is null");
        // [R32-b15 C7] discard 短路: 已 discarded 时 queued 不启动, 立即生成 synthetic fallback error.
        //   对齐 CC toolExecution.ts:454-456: "If discarded, queued tools do not start".
        //   CC 生成 synthetic "<tool_use_error>Error: Streaming fallback...</tool_use_error>" 注入 LLM.
        //   Java 端同样用 synthetic_fallback 错误 content, 并设 status=COMPLETED 让 getRemainingResults
        //   短路返回 (无需等待).
        if (discarded) {
            TrackedTool dt = new TrackedTool();
            dt.call = call;
            dt.parent = parent;
            dt.onProgress = onProgress;
            dt.tool = registry.get(call.name()).orElse(null);
            dt.isConcurrencySafe = true;
            dt.status = Status.COMPLETED;
            dt.result = createSyntheticErrorMessage(call.id(), "streaming_fallback");
            // [IMP-C2 返工] synthetic error 结果必须同步标记 isError（getResultErrorFlags 配对推导依赖）
            dt.isError = true;
            // [工具调用实时推] discarded 短路分支: tool_call + tool_result 均同步就绪 →
            //   实时推送 (tool_call 即时性 + tool_result 前端取消卡片, 对齐探针 P4).
            //   Q4 关键边界: 本分支双集合分别登记, 回放 per-toolCallId 去重正确补推缺口.
            pushToolCallRealtime(dt);
            pushToolResultRealtime(dt);
            tools.put(call.id(), dt);
            log.info("TOOL add: discarded → synthetic fallback queued for id={}",
                abbreviate(call.id(), 24));
            return;
        }
        TrackedTool t = new TrackedTool();
        t.call = call;
        t.parent = parent;
        t.onProgress = onProgress;
        // [GAP-R1] 调度线程（runner，已被 runWithTeammateContext 包）捕获 teammate 上下文，
        // 传播到工具执行线程（对齐 CC AsyncLocalStorage 跨异步传播；Java ThreadLocal 不跨线程）。
        t.capturedTeammateContext = TeammateContext.getTeammateContext();
        Tool tool = registry.get(call.name()).orElse(null);
        // [IMP-C4 R1 rework] isEnabled 守卫：已注册但 disabled 的工具与未知工具同路径报
        //   "No such tool available"，不得真实执行（对齐 CC tools.ts:325 getTools isEnabled
        //   过滤 → 执行器在过滤后 pool findToolByName 查不到 → StreamingToolExecutor.ts:91）。
        //   Java 注册表保留全部已注册工具（LLM schema 已按 isEnabled 过滤），此处执行器
        //   主路径补守卫（ToolRegistry.dispatch 守卫仅覆盖 fork 路径）。
        if (tool != null && tool.isEnabled()) {
            t.tool = tool;
            try {
                t.isConcurrencySafe = tool.isConcurrencySafe(call.input());
            } catch (Exception e) {
                log.warn("isConcurrencySafe threw for {}: {}", call.name(), e.toString());
                t.isConcurrencySafe = false;
            }
            // [R32-b12 D-5] 记录工具执行开始时间 · 对齐 CC toolExecution.ts:863 preToolHookStart /
            //   line 1223 startTime.
            toolStartTimes.put(call.id(), System.currentTimeMillis());
            tools.put(call.id(), t);
            // [工具调用实时推] 正常分支: 模型 tool_use 到达即实时推 tool_call (早于执行,
            //   前端卡片即时出现); 覆盖 queued 全路径 (executeAsync 只跑通过队列闸的工具).
            pushToolCallRealtime(t);
            // [R32-b15 Stage 2 C4] deferred 模式: add 时即占位, 保持 LinkedHashMap 顺序
            // 为 add 顺序 (而非完成顺序). executeAsync 完成时把真实 modifier 替换占位.
            if (deferContextModifier) {
                synchronized (deferredContextModifiers) {
                    deferredContextModifiers.put(call.id(), null);
                }
            }
            // [R32-b8 #3 P0-2 校正] 不在 add() 触发 in-progress 标记 ·
            //   对齐 CC StreamingToolExecutor.ts:267 executeTool 入口触发
            //   (而非 addTool 触发). 实施点: 移到 executeAsync() 入口 (Fix 2).
            //   此处保持 QUEUED 状态不触发, 让 in-progress 仅追踪真正 EXECUTING 的工具.
            processQueue();
        } else {
            // 没找到工具 / 工具已禁用: 立即 mark 为 completed, error result
            // [IMP-C4 R1 rework] isEnabled=false 与 unknown 同路径（CC tools.ts:325 可见集过滤
            //   → 执行器 findToolByName 查不到 → StreamingToolExecutor.ts:91）；disabled 补 warn 日志.
            if (tool != null) {
                log.warn("TOOL add: 工具 '{}' (id={}) 已注册但 disabled → 拒绝执行 (No such tool available)",
                    call.name(), abbreviate(call.id(), 24));
            }
            t.isConcurrencySafe = true;
            t.status = Status.COMPLETED;
            // [R32-b15 Stage 2 C5] 未知工具错误保留 lineage parent (CLAUDE.md 规则 12 · Fail loud)
            //   让 UI/transcript 仍能按 DTO assistantMessageId 字段回挂父 envelope.
            t.result = ToolResult.error(call.id(),
                "No such tool available: " + call.name());
            t.isError = true;
            tools.put(call.id(), t);
            // [工具调用实时推] unknown/disabled 分支: result 已同步就绪 →
            //   tool_call + tool_result 实时推送 (前端即时收 error 卡片).
            pushToolCallRealtime(t);
            pushToolResultRealtime(t);
            // [R32-b8 #3] 同步: 错误工具立即 mark completed (不入 in-progress 等待 UI)
            processQueue();
        }
    }

    /**
     * [R32-b12 D-4] 注入工具决策归因 map · 由 LlmAgentLoop.applyPermissionFilter 完成后调用.
     *
     * <p>用于 logOTelEvent('tool_result') 注入 decision_source / decision_type 字段.
     * 调用时机会: streamingExec 已构造 + tools 已 add (可能已 execute),
     * 但 tool_result 事件在 executeAsync promise 完成时 emit (异步, 通常晚于 setter).
     *
     * <p>WHY volatile + Map.copyOf: setter 注入后内部引用固定, 执行器并发读可见.
     *
     * @param decisions 决策归因 map (key=toolUseId, value=ToolDecisionInfo); null → empty Map
     */
    public void setToolDecisions(java.util.Map<String, ToolDecisionInfo> decisions) {
        this.toolDecisions = decisions == null
            ? java.util.Map.of()
            : java.util.Map.copyOf(decisions);
        if (log.isDebugEnabled()) {
            log.debug("TOOL toolDecisions injected: size={}", this.toolDecisions.size());
        }
    }

    /**
     * [Session J 方案 A] 注入 SubagentTool 的 CC 对齐执行参数.
     * 普通 Tool 仍走 Tool 三参接口, 不修改 Tool / ToolRegistry 契约.
     */
    public void setSubagentExecutionContext(
            AgentOptions agentOptions,
            ForkSubagentMessages.Message assistantMessage) {
        this.subagentAgentOptions = agentOptions != null
            ? agentOptions
            : AgentOptions.defaultOptions();
        this.subagentAssistantMessage = assistantMessage;
        if (log.isDebugEnabled()) {
            log.debug("TOOL Subagent 执行上下文已注入: querySource={} assistantMessage={}",
                this.subagentAgentOptions.querySource(),
                assistantMessage != null ? "已透传" : "null");
        }
    }

    /**
     * [R32-b12 Fix-v3 P1-1] 注入 Telemetry bean · 由 LlmAgentLoop 在 manual new 之后调用.
     *
     * <p>WHY (CLAUDE.md 规则 7 + 12 · Fail loud): 修复 P1-1 阻塞缺陷. 之前 telemetry
     * 字段是 {@code @Autowired(required=false)}, 但 LlmAgentLoop 用 manual
     * {@code new StreamingToolExecutor(...)} (行 1613/3508) 绕过 Spring 注入,
     * 导致 telemetry 字段始终为 null, 所有 8 个埋点被 `if (telemetry == null) return`
     * 短路. 现在改为 setter 注入, LlmAgentLoop 在 manual new 后显式调此方法.
     *
     * <p>调用时机: 紧跟 {@code new StreamingToolExecutor(...)} 之后, 在 tools.add()
     * 之前 (保证 setToolDecisions / executeAsync 路径都能读到 telemetry).
     *
     * <p>null-safe: 传入 null 时保持 telemetry 字段为 null (向后兼容, 短路上层埋点).
     *
     * @param telemetry Spring 注入的 Telemetry bean (可为 null)
     */
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
        if (log.isDebugEnabled()) {
            log.debug("TOOL telemetry injected: telemetryPresent={}", telemetry != null);
        }
    }

    /** [R32-b12 Fix-v3 P1-1] 暴露 telemetry 字段 (供单元测试验证注入成功). */
    public Telemetry getTelemetry() {
        return this.telemetry;
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] 注入 prompt 回调工厂 · 由 UI 消费端 (等价 CC setPromptQueue,
     * REPL.tsx:2383-2391) / LlmAgentLoop 注入. null-safe: 传入 null → 通道关闭 (对齐 CC
     * feature('HOOK_PROMPTS')=false 时 requestPrompt: undefined)。
     *
     * @param promptRequesterFactory CC original: requestPrompt (hooks.ts:1972); 未绑定工厂
     */
    public void setPromptRequesterFactory(PromptRequesterFactory promptRequesterFactory) {
        this.promptRequesterFactory = promptRequesterFactory;
        if (log.isDebugEnabled()) {
            log.debug("TOOL promptRequesterFactory injected: present={}", promptRequesterFactory != null);
        }
    }

    /**
     * [H8 v2 补全 H8-GAP-1 + D P1-2] 注入沙箱管理器 → 透传给 {@link HookPermissionResolver}.
     *
     * <p>WHY: HookPermissionResolver.checkRuleBasedPermissions 的 Bash sandbox auto-allow
     * (CC permissions.ts:1186-1205) 需要 SandboxManager; [hooks_v3 H-PERM-02 · 1-7] 起
     * 权限决策经本类 {@link #permissionResolver} 实例 (bean setter 注入), sandbox 注入
     * 改走 bean 实例 setter — 对齐 CC resolveHookPermissionDecision 无状态纯函数
     * (toolHooks.ts:332-433), 无静态单例无双轨. null-safe: 传 null 时 sandbox 语义关闭
     * = 登记前行为.
     *
     * @param sandboxManager Bash 沙箱管理器 (可为 null)
     */
    public void setSandboxManager(com.nexusai.application.agent.permission.sandbox.SandboxManager sandboxManager) {
        permissionResolver.setSandboxManager(sandboxManager);
        if (log.isDebugEnabled()) {
            log.debug("TOOL sandboxManager injected → permissionResolver bean: present={}", sandboxManager != null);
        }
    }

    /**
     * [hooks_v3 H-PERM-02 · 1-7] 注入 Hook 权限决策解析器 bean · 由
     * buildStreamingExecutor (AgentLoopContext) 接线.
     *
     * <p>对齐 CC resolveHookPermissionDecision 无状态纯函数 (toolHooks.ts:332-433);
     * 生产注入 {@link Component} bean (inputValidator / sandbox bean setter 注入生效).
     * null-safe: 传 null 时保持默认 new 实例 (手动构造 / 单测场景).
     *
     * @param permissionResolver Hook 权限决策解析器 (可为 null → 保持默认)
     */
    public void setPermissionResolver(HookPermissionResolver permissionResolver) {
        if (permissionResolver != null) {
            this.permissionResolver = permissionResolver;
        }
    }

    /**
     * [R32-b15 C11] 注入 AgentState · 用于 interruptible state 同步.
     *
     * <p>调用时机: 紧跟 {@code new StreamingToolExecutor(...)} 之后, 在 tools.add() 之前.
     * 与 setTelemetry / setToolDecisions 模式一致.
     *
     * <p>null-safe: 传 null 时保持字段 null (向后兼容单测场景).
     *
     * @param state AgentState 实例 (可为 null)
     */
    public void setAgentState(com.nexusai.application.agent.AgentState state) {
        this.agentStateRef = state;
        if (log.isDebugEnabled()) {
            log.debug("TOOL agentState injected: statePresent={}", state != null);
        }
    }

    /** [R32-b15 C11] 暴露 AgentState 字段 (供单元测试验证注入成功). */
    public com.nexusai.application.agent.AgentState getAgentState() {
        return this.agentStateRef;
    }

    /**
     * [工具调用实时推] 注入实时推送通道 · 由 {@link AgentLoopContext#buildStreamingExecutor}
     * 在 manual new 后调用 (wsTemplate/streamTopic 非 null 才注入).
     *
     * <p>null-safe (与 setTelemetry / setAgentState 同款模式): 直接赋值不抛 NPE;
     * wsTemplate/streamTopic 为 null 时 record 内字段为 null, 推送 helper 早返 no-op.
     * sessionId / userMessageId 可为 null (后台任务 topic, userMessageId 被
     * {@code @JsonInclude(NON_NULL)} 省略, R6).
     *
     * @param wsTemplate           STOMP 消息模板 (可为 null → 关闭实时推)
     * @param streamTopic          stream topic (可为 null → 关闭实时推)
     * @param streamSessionId      会话 id (后台任务场景为 task 关联会话, 可为 null)
     * @param streamUserMessageId  user message id (后台任务场景为 null)
     */
    public void setToolStreamPublisher(SimpMessagingTemplate wsTemplate,
                                       String streamTopic,
                                       String streamSessionId,
                                       String streamUserMessageId) {
        this.toolStream = new ToolStreamPublisher(
            wsTemplate, streamTopic, streamSessionId, streamUserMessageId);
        if (log.isDebugEnabled()) {
            log.debug("TOOL toolStreamPublisher injected: wsTemplatePresent={} topicPresent={} sessionId={} userMessageId={}",
                wsTemplate != null, streamTopic != null, streamSessionId, streamUserMessageId);
        }
    }

    /**
     * [A1 撤外层] 注入 InputSanitizer · 由 LlmAgentLoop buildStreamingExecutor 在
     * manual new 后调用,供 {@link #executeAsync} 入口字段剥离使用.
     *
     * <p>对齐 CC toolExecution.ts:761-773 (defense-in-depth). null 时跳过该阶段
     * (向后兼容单测场景). 这是为了把原 {@code LlmAgentLoop.applyPermissionFilter}
     * 的字段剥离迁移到内层 — 单职责由 {@link StreamingToolExecutor} 承担,无需外层
     * 预先干预.
     */
    public void setInputSanitizer(InputSanitizer sanitizer) {
        this.inputSanitizer = sanitizer;
        if (log.isDebugEnabled()) {
            log.debug("TOOL inputSanitizer injected: present={}", sanitizer != null);
        }
    }

    /** [A1] 暴露 InputSanitizer (供单元测试验证注入). */
    public InputSanitizer getInputSanitizer() {
        return this.inputSanitizer;
    }

    /**
     * [A1 撤外层] 注入 ToolInputValidator · 由 LlmAgentLoop buildStreamingExecutor
     * 调用,供 {@link #executeAsync} 入口 schema + validateInput 阶段使用.
     *
     * <p>对齐 CC toolExecution.ts:615-733 (Zod schema + validateInput). null 时
     * 跳过该阶段 (向后兼容单测).
     */
    public void setInputValidator(ToolInputValidator validator) {
        this.inputValidator = validator;
        if (log.isDebugEnabled()) {
            log.debug("TOOL inputValidator injected: present={}", validator != null);
        }
    }

    /**
     * [R32-c-1] 注入 transcript classifier 启用标志 · 调用时机: buildStreamingExecutor 内
     * 紧跟 hookRegistry / gate 注入后, add() 之前.
     *
     * @param enabled true 启用 retry hook 触发条件 (CC feature('TRANSCRIPT_CLASSIFIER') 镜像)
     */
    public void setTranscriptClassifierEnabled(boolean enabled) {
        this.transcriptClassifierEnabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("TOOL transcriptClassifierEnabled set: {}", enabled);
        }
    }

    /** [R32-c-1] 暴露 transcript classifier 启用标志 (测试用). */
    public boolean isTranscriptClassifierEnabled() {
        return this.transcriptClassifierEnabled;
    }

    /**
     * [R32-c-1] 注入 PermissionDenied hook 执行器 · 与 hookRegistry 配对使用.
     *
     * <p>WHY 独立 setter: HookRegistry 已含 PermissionDenied event filter, 但
     * 执行器封装了早返 + Stream 聚合 + AggregatedHookResult 映射, 是更高级的
     * 抽象. 单测可直接 mock 执行器, 验证 executeAsync 主循环触发点.
     *
     * @param executor 执行器 (可为 null → 退化为无 retry hook, 向后兼容)
     */
    public void setPermissionDeniedHookExecutor(PermissionDeniedHookExecutor executor) {
        this.permissionDeniedHookExecutor = executor;
        if (log.isDebugEnabled()) {
            log.debug("TOOL permissionDeniedHookExecutor injected: present={}", executor != null);
        }
    }

    /** [R32-c-1] 暴露 retry hook executor (测试用). */
    public PermissionDeniedHookExecutor getPermissionDeniedHookExecutor() {
        return this.permissionDeniedHookExecutor;
    }

    /**
     * [R32-c-1] 注入重试元消息工厂 · 触发后通过 extendedResultHandler 推送 isMeta user message.
     *
     * @param factory 工厂 (可为 null → 退化为不注入 isMeta 消息, 向后兼容)
     */
    public void setRetryMessageFactory(RetryMessageFactory factory) {
        this.retryMessageFactory = factory;
        if (log.isDebugEnabled()) {
            log.debug("TOOL retryMessageFactory injected: present={}", factory != null);
        }
    }

    /** [A1] 暴露 ToolInputValidator (供单元测试验证注入). */
    public ToolInputValidator getInputValidator() {
        return this.inputValidator;
    }

    /**
     * [R32-b15 Stage 2 C4] 开启 deferred context modifier 模式 ·
     * 由 LlmAgentLoop.runTools 顶层入口在 manual new 后调用.
     *
     * <p>设为 true 时, {@link #executeAsync} 收集 (tool_use_id, contextModifier)
     * 到 {@link #deferredContextModifiers} 队列, 不立即 apply; 批次结束后调用
     * {@link #applyDeferredContextModifiers(ToolUseContext)} 单线程按 add 顺序
     * 提交 (与 CC toolOrchestration.ts:30-62 queuedContextModifiers 等价).
     */
    public void setDeferContextModifier(boolean value) {
        this.deferContextModifier = value;
        if (log.isDebugEnabled()) {
            log.debug("TOOL deferContextModifier set: {}", value);
        }
    }

    /**
     * [P0-2] 应用 deferred context modifiers (单线程, 按 add 顺序) · 真实 apply ·
     * 对齐 CC {@code toolOrchestration.ts:53-61} 批次结束后按 blocks 原序 apply.
     *
     * <p><b>CC 真源</b> (toolOrchestration.ts:54-63, Read 实证):
     * <pre>
     * for (const block of blocks) {
     *   const modifiers = queuedContextModifiers[block.id]
     *   if (!modifiers) continue
     *   for (const modifier of modifiers) {
     *     currentContext = modifier(currentContext)
     *   }
     * }
     * </pre>
     * {@code currentContext} 续传后续 tool call/turn —— modifier 副作用 (CC 改 getAppState /
     * options.mainLoopModel) 落在会话上下文.
     *
     * <p><b>Java 实现</b>: 遍历 {@link #deferredContextModifiers} (LinkedHashMap, add 顺序即
     * 工具原序), 对 per-turn TUC 依次调 {@code modifier.apply(currentContext)}, 返回 TUC 作为
     * 后续 modifier 基座. SkillTool contextModifier 的 {@code setAppState} 副作用落入会话
     * {@code appStateRef} (跨 turn 保持, LlmAgentLoop.java:490), 由后续轮次 toolExecContext /
     * getModelForCall 消费.
     *
     * <p><b>调用方</b>: {@code AgentLoopContext.runTools} 顶层入口 (4 处调用点), 在
     * {@code getRemainingResults()} 返回后调用. 旧实现 (A1 遗留 no-op drain) 已删除 ——
     * 现在真实按序 apply, 不再仅是清占位.
     *
     * @param perTurnTuc 本 turn 的 tool-use context (modifier 应用基座)
     */
    public void applyDeferredContextModifiers(
            com.nexusai.application.agent.tool.ToolUseContext perTurnTuc) {
        if (perTurnTuc == null) {
            return;
        }
        synchronized (deferredContextModifiers) {
            if (deferredContextModifiers.isEmpty()) {
                return;
            }
            com.nexusai.application.agent.tool.ToolUseContext currentContext = perTurnTuc;
            int applied = 0;
            for (java.util.Map.Entry<String,
                    java.util.function.Function<com.nexusai.application.agent.tool.ToolUseContext,
                            com.nexusai.application.agent.tool.ToolUseContext>> e
                    : deferredContextModifiers.entrySet()) {
                var modifier = e.getValue();
                if (modifier == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("TOOL applyDeferredContextModifiers: skip null modifier for id={}",
                            abbreviate(e.getKey(), 24));
                    }
                    continue;
                }
                try {
                    com.nexusai.application.agent.tool.ToolUseContext next = modifier.apply(currentContext);
                    if (next != null) {
                        currentContext = next;
                    }
                    applied++;
                    if (log.isDebugEnabled()) {
                        log.debug("TOOL applyDeferredContextModifiers: applied modifier for id={} (按 add 顺序)",
                            abbreviate(e.getKey(), 24));
                    }
                } catch (Throwable th) {
                    // modifier 抛异常不阻断后续 modifier apply (best-effort, 对齐 CC 无 catch 但
                    // Java 端 fail-loud 原则下以日志暴露, 不静默吞)
                    log.warn("TOOL applyDeferredContextModifiers: modifier threw for id={}: {}",
                        abbreviate(e.getKey(), 24), th.toString());
                }
            }
            deferredContextModifiers.clear();
            if (log.isDebugEnabled()) {
                log.debug("TOOL applyDeferredContextModifiers: applied {} modifiers, queue drained", applied);
            }
        }
    }

    /**
     * [R32-b15 C11] 工具启动时同步 interruptible state · 对齐 CC StreamingToolExecutor.ts:254-260.
     *
     * <p>算法:
     * <ol>
     *   <li>若新工具 interruptBehavior != "cancel" → setHasInterruptibleToolInProgress(false)</li>
     *   <li>若新工具是 cancel → 检查所有 EXECUTING 工具是否均为 cancel, 是则 true, 否则 false</li>
     * </ol>
     *
     * <p>null-safe: agentStateRef == null 时跳过 (单测场景, 向后兼容).
     */
    private void updateInterruptibleStateOnStart(TrackedTool t) {
        if (agentStateRef == null) return;
        boolean newToolCancel = t.tool != null && "cancel".equals(t.tool.interruptBehavior());
        if (!newToolCancel) {
            agentStateRef.setHasInterruptibleToolInProgress(false);
            return;
        }
        // 新工具是 cancel, 检查所有 EXECUTING 是否都是 cancel
        boolean allCancel = true;
        for (TrackedTool other : tools.values()) {
            if (other.status == Status.EXECUTING
                && (other.tool == null || !"cancel".equals(other.tool.interruptBehavior()))) {
                allCancel = false;
                break;
            }
        }
        agentStateRef.setHasInterruptibleToolInProgress(allCancel);
    }

    /**
     * [R32-b15 C11] 工具结束时同步 interruptible state · 对齐 CC StreamingToolExecutor.ts:254-260
     * end-of-tool 处理. 当前工具 COMPLETED → 重新计算剩余 EXECUTING 是否均为 cancel.
     */
    private void updateInterruptibleStateOnEnd(TrackedTool t) {
        if (agentStateRef == null) return;
        boolean allCancel = true;
        boolean hasExecuting = false;
        for (TrackedTool other : tools.values()) {
            if (other == t) continue;
            if (other.status == Status.EXECUTING) {
                hasExecuting = true;
                if (other.tool == null || !"cancel".equals(other.tool.interruptBehavior())) {
                    allCancel = false;
                    break;
                }
            }
        }
        // 无剩余 EXECUTING → interruptible state = false (无可中断工具)
        agentStateRef.setHasInterruptibleToolInProgress(hasExecuting && allCancel);
    }

    /**
     * [B GAP-EXEC-07] 注册 per-tool abort 冒泡 listener · 对齐 CC StreamingToolExecutor.ts:304-318.
     *
     * <p>CC 真源 (grep 自验):
     * <pre>
     * toolAbortController.signal.addEventListener('abort', () => {
     *   if (reason !== 'sibling_error' && !ctx.abortController.signal.aborted && !discarded) {
     *     ctx.abortController.abort(reason)
     *   }
     * }, { once: true })
     * </pre>
     *
     * <p>Java 端用 {@link AbortController#onCancel} (简化版 addEventListener); 不保证
     * once 语义, 但 listener 内守门 (父已取消 / discarded / sibling_error) 保证幂等.
     *
     * <p>package-private 供同包单测直接驱动 (无需反射 TrackedTool 私有嵌套类).
     *
     * @param toolAbortController 本工具的 child controller (executeAsync 内创建)
     */
    void registerAbortBubbleUp(AbortController toolAbortController) {
        if (toolAbortController == null) return;
        toolAbortController.onCancel(tc -> {
            String tcReason = tc.reason();
            if ("sibling_error".equals(tcReason)) {
                return; // sibling 级联不冒泡 — CC: siblingAbortController 是设计内联的兄弟取消
            }
            if (ctx == null || ctx.abortController() == null || ctx.abortController().isCancelled()) {
                return; // 父已取消不重复 abort
            }
            if (discarded) {
                return; // discarded 不冒泡
            }
            ctx.abortController().abort(tcReason);
            if (log.isDebugEnabled()) {
                log.debug("TOOL abort bubble-up: childReason={} → parent.abort({})",
                    tcReason, tcReason);
            }
        });
    }

    /**
     * [B GAP-EXEC-08] 生成工具描述 · 对齐 CC StreamingToolExecutor.ts:243-252 getToolDescription.
     *
     * <p>CC 真源 (grep 自验): 取 input 的 {@code command} / {@code file_path} / {@code pattern}
     * 首个非空字段, 截断 40 字符 (超长加 {@code \u2026}), 返回 {@code "${name}(${truncated})"};
     * 无匹配字段时返回 {@code tool.block.name}. 用于 sibling synthetic 错误文案
     * "Cancelled: parallel tool call ${desc} errored".
     *
     * @param t 当前 TrackedTool
     * @return 工具描述字符串
     */
    private static String getToolDescription(TrackedTool t) {
        if (t == null || t.call == null) return "";
        JsonNode input = t.call.input();
        String summary = "";
        if (input != null && input.isObject()) {
            for (String key : new String[]{"command", "file_path", "pattern"}) {
                JsonNode v = input.get(key);
                if (v != null && v.isTextual() && !v.asText().isEmpty()) {
                    summary = v.asText();
                    break;
                }
            }
        }
        if (!summary.isEmpty()) {
            String truncated = summary.length() > 40
                ? summary.substring(0, 40) + "\u2026"
                : summary;
            return t.call.name() + "(" + truncated + ")";
        }
        return t.call.name();
    }

    /** 处理队列, 启动条件满足的工具 (CC line 140-151). */
    private void processQueue() {
        for (TrackedTool t : tools.values()) {
            if (t.status != Status.QUEUED) continue;
            if (canExecuteTool(t)) {
                executeAsync(t);
            } else {
                // [B GAP-EXEC-01 (queue)] CC StreamingToolExecutor.ts:146-149 守序 break ·
                //   WHY: 不安全的工具 (isConcurrencySafe=false) 必须独占执行, 它守门失败时
                //   其后的所有工具都不能越过它提前启动 (CC: "since we need to maintain order
                //   for non-concurrent tools, stop here"). 无 break 时队列 [safe(A), unsafe(B),
                //   safe(C)] 会让 C 在 B 之前启动, 破坏 unsafe 顺序语义.
                if (!t.isConcurrencySafe) {
                    break;
                }
            }
            // 守门失败时保持 QUEUED 状态, 等当前 EXECUTING 完成时 processQueue 唤醒
        }
    }

    /** 守门: 当前无 executing, 或本工具 safe + 所有 executing 也 safe (CC line 129-135). */
    private boolean canExecuteTool(TrackedTool t) {
        if (t.isConcurrencySafe) {
            for (TrackedTool other : tools.values()) {
                if (other.status == Status.EXECUTING && !other.isConcurrencySafe) {
                    return false;
                }
            }
            // [R32-b10 B4] safe 路径: 检查当前 in-flight safe 工具数是否达到 maxConcurrency 上限
            // 对齐 CC runToolsConcurrently.all(generators, getMaxToolUseConcurrency()) 限流
            // (Open-ClaudeCode/src/services/tools/toolOrchestration.ts:175). 复用
            // tools.values() 遍历, 不引入新字段. 已知限制: 线程池 defaultExecutor 默认
            // 8 线程 < MAX_TOOL_USE_CONCURRENCY 默认 10, 第 9-10 个 tool 阻塞等待线程
            // (D-2, b10 不解决, brief 范围外).
            int safeInFlight = 0;
            for (TrackedTool other : tools.values()) {
                if (other.status == Status.EXECUTING && other.isConcurrencySafe) {
                    safeInFlight++;
                    if (safeInFlight >= MAX_TOOL_USE_CONCURRENCY) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            for (TrackedTool other : tools.values()) {
                if (other.status == Status.EXECUTING) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 启动工具后台执行 (CC executeTool line 265-405). */
    private void executeAsync(TrackedTool t) {
        // [IMP-C D2-A/F3] 调度线程（会话线程）捕获 projectRoot → 任务体线程注入 →
        //   finally restore 外层原值（对齐 LlmAgentLoop.run() :1637/:1645 capture/restore 语义；
        //   restore 而非 remove —— 线程池复用防泄漏，null 捕获值不 set 保持回落）。
        final String scheduledProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        // [reqId MDC 传播] 调度线程（会话线程）捕获 MDC context map → 任务体线程回放
        //   （对齐 LlmAgentLoop STREAM_EXECUTOR mdcCtx 先例 + withSessionProjectRoot 同款成对模式）。
        //   WHY: 工具执行在 fixed-8 池线程（CompletableFuture.runAsync(..., executor)），ThreadLocal 不跨
        //   线程 → 池线程 RequestContext.requestId()=null → isTodoV2Enabled()=false → 子代理（SubagentTool）
        //   回落 V1 TodoWrite、父 V2/子 V1 工具集分叉（决策 #65）。MDC 回放后池线程同帧 requestId 可见
        //   → 工具（含 sync 子代理）判交互正确，且 async 子代理线程捕获父 MDC 时非 null。
        final java.util.Map<String, String> mdcCtx = org.slf4j.MDC.getCopyOfContextMap();
        t.status = Status.EXECUTING;
        // [R32-b8 #3 P0-2 校正] 在 executeAsync() 入口 (执行真正开始时) 触发 in-progress 标记 ·
        //   对齐 CC StreamingToolExecutor.ts:267 executeTool 入口 + toolOrchestration.ts:127/160
        //   (parallel start + sequential start). add() 时不触发 (Fix 2), 避免 QUEUED 状态
        //   工具被错误地标记为 in-progress.
        // [P0-1 校正] 用 Function<Set<String>, Set<String>> 严格对齐 CC Tool.ts:227.
        fireInProgress(t.call.id());
        // [R32-b15 C11] 入口同步 interruptible state · 对齐 CC StreamingToolExecutor.ts:254-260
        //   updateInterruptibleState: 任意工具进入 EXECUTING 时, 若其 interruptBehavior!="cancel"
        //   → setHasInterruptibleToolInProgress(false); 若全为 cancel → setHasInterruptibleToolInProgress(true).
        //   入口先设 false (默认保守), 然后单独处理"全 cancel"路径.
        updateInterruptibleStateOnStart(t);
        t.promise = CompletableFuture.runAsync(withSessionProjectRoot(scheduledProjectRoot, mdcCtx, () -> {
            long t0 = System.currentTimeMillis();
            log.info("TOOL exec: name={} id={} input={}",
                t.call.name(), abbreviate(t.call.id(), 24), abbreviate(t.call.input().toString(), 200));

            // ── [R32-#29 + B GAP-EXEC-07] per-tool abort controller: siblingAbortController 的 child.
            // 当 Bash 错误触发 siblingAbortController.abort("sibling_error") 时,
            // 所有 per-tool child 会通过 AbortController.createChild() 的 listener
            // 级联取消(对齐 CC StreamingToolExecutor.ts:294-303 executeTool).
            // Java 端 Tool.execute 没有独立 abortSignal 参数 — child controller
            // 用于本类内的 abort 短路检查,tool 内部仍用 ctx.abortController().
            AbortController toolAbortController = siblingAbortController.createChild();
            t.toolAbortController = toolAbortController;
            // [B GAP-EXEC-07] abort bubble-up listener · 对齐 CC StreamingToolExecutor.ts:304-318.
            //   WHY: 子工具被取消 (非 sibling_error, e.g. permission.cancelAndAbort 拒绝)
            //   时必须把取消信号冒泡到父 ctx.abortController(), 让 query loop 的
            //   post-tool abort 检查结束整个 turn (CC #21056 regression: 不冒泡则
            //   ExitPlanMode "clear context + auto" 只发 REJECT_MESSAGE 给模型而不中止).
            //   守门: sibling_error 不冒泡 (siblingAbortController 取消是 CC 设计内联的
            //   兄弟级联, 不结束 turn) + 父已取消不重复 abort + discarded 不冒泡.
            registerAbortBubbleUp(toolAbortController);

            try {
                // ── [R32-#16] executeAsync 入口 abort 检查 · 对齐 CC collectResults
                // 入口检查 (StreamingToolExecutor.ts:275-291) + getAbortReason 三态决策.
                // [B GAP-EXEC-01/04] per-tool 判定 (CC ts:210 getAbortReason(tool)) —
                //   interrupt 时按当前工具的 interruptBehavior() 区分 cancel/block.
                String abortReason = getAbortReason(t);
                if (abortReason != null) {
                    log.info("TOOL skipped: name={} id={} abortReason={}",
                        t.call.name(), abbreviate(t.call.id(), 24), abortReason);
                    // ── [G P1-6 + Session I P3-2] abort 短路注入 tengu_tool_use_cancelled ──
                    //   对齐 CC Open-ClaudeCode/src/services/tools/toolExecution.ts:415-453
                    //   (AbortController.signal.aborted 先 logEvent('tengu_tool_use_cancelled')
                    //   再 yield CANCEL_MESSAGE) — 事件必须先于合成错误产出.
                    //   此处真实调用 emitCancelledTelemetry (本类唯一发射点之一, 见下方 helper):
                    //   含 toolName 脱敏 + isMcp + queryChainId/queryDepth + recordEvent +
                    //   logOTelEvent. 分支 2 (agent-level cancel) 与本节互斥 (本节先 return),
                    //   单工具调用恰好 1 次发射, 不双发.
                    emitCancelledTelemetry(t);
                    t.result = createSyntheticErrorMessage(t.call.id(), abortReason);
                    // [IMP-C2 返工] synthetic error 结果必须同步标记 isError（getResultErrorFlags 配对推导依赖）
                    t.isError = true;
                    t.status = Status.COMPLETED;
                    // [R32-b8 #3 P1-1] abort 路径触发 in-progress 清除 ·
                    //   对齐 CC toolOrchestration.ts:183 markToolUseAsComplete
                    //   (即使 abort 也会通过 markToolUseAsComplete 清理 in-progress set).
                    clearInProgress(t.call.id());
                    // [工具调用实时推] abort 早退出口: 实时推 isError=true 的 tool_result
                    //   (前端收 cancelled 卡片关闭, 对齐探针 P4 要求).
                    pushToolResultRealtime(t);
                    processQueue();
                    return;
                }
                // ── [G29① S-2] 删 cancelled() 独立分支 · 对齐 CC toolExecution.ts:245 + permissions.ts:1163
                //   getAbortReason 已覆盖 ctx.abortController() 路径 (per-tool/per-session);
                //   旧 agent-level state.cancelled() 独立分支 (用户在 UI 取消整个 agent, Java 独有
                //   "agent_cancelled" reason → CANCEL_MESSAGE) 已删除 —— CC getAbortReason 无此态,
                //   对齐 CC 应走 abortController → user_interrupted → REJECT_MESSAGE (deletion-manifest S-2).
                // ── [A1 撤外层] (b) 字段剥离 defense-in-depth · 对齐 CC toolExecution.ts:761-773
                //   剥离 _simulatedSedEdit/_internal/__ 前缀字段,即使 LLM 误注入也不传到 tool.execute.
                //   inputSanitizer == null 时跳过 (向后兼容单测). 不修改原 call.input(),
                //   仅在 tool.execute 之前用 strippedInput (per-task §3.4 语义, §3.5 backfill 删除).
                JsonNode strippedInput = t.call.input();
                if (inputSanitizer != null && strippedInput != null && strippedInput.isObject()) {
                    strippedInput = inputSanitizer.stripInternalFields(t.call.name(), strippedInput);
                    if (log.isDebugEnabled() && strippedInput != t.call.input()) {
                        log.debug("TOOL 字段剥离: callId={} tool={} stripped some internal fields",
                            abbreviate(t.call.id(), 24), t.call.name());
                    }
                }
                // ── [FIX-A backfill-observable] §3.5 backfill 生产接线 · 对齐 CC toolExecution.ts:781-793 ──
                //   backfillObservableInput 在浅克隆上回填 file_path 绝对化 (~/相对 → 绝对),
                //   PreToolUse hook / canUseTool 看 backfilled 版, tool.execute 仍用原始
                //   t.call.input() (CC callInput 不突变契约). 防 ~/相对路径绕过 hook allowlist.
                //   inputSanitizer == null 时 backfilledInput 退化为 strippedInput (向后兼容单测).
                JsonNode backfilledInput = strippedInput;
                if (inputSanitizer != null && t.tool != null
                        && strippedInput != null && strippedInput.isObject()) {
                    backfilledInput = inputSanitizer.backfill(t.tool, strippedInput);
                    if (log.isDebugEnabled()) {
                        log.debug("TOOL backfill: callId={} tool={} file_path 绝对化",
                            abbreviate(t.call.id(), 24), t.call.name());
                    }
                }
                // ── [A1 撤外层] (d) schema 校验 · 对齐 CC toolExecution.ts:615-680
                //   用 Tool.inputSchema (Zod schema 等价) 校验; 失败 → 注入 tengu_tool_use_error
                //   + tengu_deferred_tool_schema_not_sent (仅 MCP deferred) + SchemaNotSentHint 拼接到 error.
                if (inputValidator != null && t.tool != null) {
                    ToolErrorFormatter.SafeParseResult parsed =
                        inputValidator.safeParseSchema(t.tool, strippedInput);
                    if (!parsed.ok()) {
                        // [IT-4 OD-TDV1-6] CC toolExecution.ts:617 formatZodValidationError
                        //   三句式 (toolErrors.ts:66-132) — safeParseSchema 多 issue 全量传递
                        String errorContent = ToolErrorFormatter.formatZodValidationError(
                            t.tool.name(), parsed.issues());
                        // [A1] SchemaNotSentHint · 对齐 CC toolExecution.ts:619-630
                        // [Session H P2-1] 完整 4 道乐观门 (feature gate → ToolSearch 可用 →
                        //   deferred tool → discovered set), 入参含 strippedInput 供
                        //   shouldDefer(JsonNode) 判定 (CC isDeferredTool 静态属性等价).
                        String schemaHint = SchemaNotSentHint.build(t.tool, ctx, strippedInput);
                        if (schemaHint != null) {
                            emitDeferredSchemaTelemetry(t);
                            errorContent += schemaHint;  // CC :629 errorContent += schemaHint
                        }
                        // CC :632-634 logForDebugging(`${tool.name} tool input error:
                        //   ${errorContent.slice(0, 200)}`)
                        if (log.isDebugEnabled()) {
                            log.debug("TOOL schema 校验: callId={} tool={} error={}",
                                abbreviate(t.call.id(), 24), t.call.name(),
                                errorContent.length() > 200
                                    ? errorContent.substring(0, 200) : errorContent);
                        }
                        // [P-AL-05 D-2] schema 路径重载: error='InputValidationError' + errorDetails
                        //   (CC toolExecution.ts:635-662), 内部注入 ToolDecisionInfo("config","reject")
                        emitToolUseErrorTelemetry(t, errorContent);
                        // [IT-4 OD-TDV1-6] CC toolExecution.ts:670 tool_result content 逐字:
                        //   `<tool_use_error>InputValidationError: ${errorContent}</tool_use_error>`
                        //   is_error:true（旧 errorBuilder 死代码 + joinIssuesForLlm 折叠已删）
                        t.result = ToolResult.error(t.call.id(),
                            ToolErrorFormatter.inputValidationErrorBlock(errorContent), "validation");
                        t.isError = true;
                        erroredCount.incrementAndGet();
                        t.status = Status.COMPLETED;
                        clearInProgress(t.call.id());
                        // [工具调用实时推] schema 校验失败出口: 实时推 isError=true 的 tool_result
                        //   (前端收 error 卡片).
                        pushToolResultRealtime(t);
                        processQueue();
                        return;
                    }
                }
                // ── [A1 撤外层] (e) validateInput 语义校验 · 对齐 CC toolExecution.ts:683-733
                //   用 Tool.validateInput (含上下文感知路径/命令安全检查) 做语义验证;
                //   失败 → 注入 tengu_tool_use_error, decision="reject".
                if (inputValidator != null && t.tool != null) {
                    Tool.ValidationResult semanticResult = inputValidator.validateSemantics(
                        t.tool, strippedInput, ctx);
                    if (!semanticResult.ok()) {
                        log.info("TOOL semantic 校验: callId={} ok=false msg={}",
                            abbreviate(t.call.id(), 24), semanticResult.message());
                        // [P-AL-05 D-2] semantic 路径重载: error=message + errorCode
                        //   (CC toolExecution.ts:691-698), 内部注入 ToolDecisionInfo("config","reject")
                        emitToolUseErrorTelemetry(t, semanticResult.errorCode(), semanticResult.message());
                        String joined = ToolErrorFormatter.joinIssuesForLlm(
                            ToolErrorFormatter.formatValidationError(semanticResult));
                        t.result = ToolResult.error(t.call.id(),
                            "Input validation failed: " + joined, "validation");
                        t.isError = true;
                        erroredCount.incrementAndGet();
                        t.status = Status.COMPLETED;
                        clearInProgress(t.call.id());
                        // [工具调用实时推] semantic 校验失败出口: 实时推 isError=true 的 tool_result
                        //   (前端收 error 卡片).
                        pushToolResultRealtime(t);
                        processQueue();
                        return;
                    }
                }
                // ── [U6-A1] 投机分类器启动 (CC toolExecution.ts:739-751) ──
                //   在 semantic 校验后 / PreToolUse hook 前无条件调 start, 让 bash allow
                //   classifier 与 pre-tool hooks / deny-ask classifiers / 权限弹窗并行执行.
                //   首闸 isClassifierPermissionsEnabled() 默认 false → start 恒 return false
                //   (不填充 speculativeChecks), 接线保留可随时开启.
                if (t.tool != null
                        && com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME.equals(t.tool.name())
                        && strippedInput != null && strippedInput.isObject()
                        && strippedInput.has("command") && !strippedInput.get("command").isNull()) {
                    String bashCommand = strippedInput.get("command").asText();
                    // CC toolExecution.ts:746-750 四参: (command, appState.toolPermissionContext,
                    //   abortController.signal, isNonInteractiveSession). Java 等价:
                    //   ctx.permissionContext() / ctx.abortController() / ctx.isNonInteractiveSession().
                    boolean started = SpeculativeClassifier.startSpeculativeClassifierCheck(
                        bashCommand,
                        ctx != null ? ctx.permissionContext() : null,
                        ctx != null ? ctx.abortController() : null,
                        ctx != null && ctx.isNonInteractiveSession());
                    if (log.isDebugEnabled()) {
                        log.debug("TOOL 投机分类器启动: callId={} tool={} started={}",
                            abbreviate(t.call.id(), 24), t.tool.name(), started);
                    }
                }
                // ── [R32-#17] PreToolUse hook 串联点 (入口) ──
                // [R32-b12 Fix-v3 P1-2] PreToolUse hook 计时精确化 · 修复 preToolHookDurationMs 偏差:
                //   之前 preToolHookStartTimes 记录在 hook 入口 (line 503), 但 stop 没有显式记录,
                //   emitSuccessTelemetry 内部用 (now - start) 包含: hook 耗时 + permission gate
                //   耗时 + tool.execute 耗时 + emit 调用间隔. 这是错误的"hook-only" 时长.
                //   现在改为: hook 入口 start, hook 出口 stop, (stop - start) 仅含 hook 时长.
                //   工具执行时长另用 toolDurationMs 字段 (与 CC 端 `durationMs` 区分).
                //   对齐 CC toolExecution.ts:800 runPreToolUseHooks start/end + 863 startTime.
                long preToolHookStartNs = System.nanoTime();
                long preToolHookDurationMs = 0L;
                // [P0-3] 对齐 CC toolExecution.ts:800 runPreToolUseHooks + toolHooks.ts:435 7 类 case.
                //   16 字段 AggregatedHookResult 聚合 (P0-3 全量对齐 CC AggregatedHookResult).
                //   在 permission gate 前, 拿到 permissionBehavior (Deny → 直接阻断)
                //   + updatedInput (替换 sanitizedInput, CC 全替换语义) + preventContinuation 标志.
                //   本串入点消费 CC 7 类 case 中的 6 类 (case 7 "stop" 由 preventContinuation+stopReason 翻译).
                AggregatedHookResult preOutcome = AggregatedHookResult.proceed();
                if (hookRegistry != null && ctx != null && t.tool != null) {
                    try {
                        // [IMP-HOOKS-S6 E9·CCJ-T6-22 / FIX-A backfill-observable] PreToolUse
                        //   hook 入参 = processedInput (strip+backfill 后) — 对齐 CC
                        //   toolExecution.ts:761-793 + runPreToolUseHooks (toolHooks.ts:466-476):
                        //   backfill 在浅克隆上绝对化 file_path, hook 看 backfilled 版
                        //   (防 ~/ 相对路径绕过 hook allowlist), tool.execute 仍用原始输入
                        //   (CC callInput 不突变契约).
                        // [IMP-HOOKS-S6 ⊕1] 6 参签名 (删 userModified/parentMessage/requestId, S9 DEL-02f;
                        //   [IMP-RS-01 DEL-01e 补回] prompt 回调通道恢复为第 6 参):
                        //   对齐 CC executePreToolHooks 9 参 (hooks.ts:3394-3405) hook 入参面 —
                        //   permissionMode/signal/timeoutMs 由 ctx 内部可取; prompt 回调通道
                        //   等价 toolHooks.ts:474 透传 toolUseContext.requestPrompt (REPL.tsx:2520);
                        //   toolUseSummary 保留 (toolHooks.ts:474-475).
                        // [IMP-RS-01 DEL-01e 补回] 绑定未绑定工厂 (sourceName=hookName 等价 CC
                        //   `${hookEvent}:${matchQuery}` = "PreToolUse:<toolName>", hooks.ts:1987,
                        //   toolInputSummary 等价 toolHooks.ts:475) → 透传绑定版 requester.
                        String promptSourceName = "PreToolUse:" + t.call.name();
                        String promptToolSummary = t.tool.getToolUseSummary(jsonNodeToMap(backfilledInput));
                        PromptRequester promptRequester = promptRequesterFactory != null
                            ? promptRequesterFactory.bind(promptSourceName, promptToolSummary)
                            : null;
                        preOutcome = hookRegistry.executePreToolUse(
                            t.call.name(), backfilledInput, ctx, t.call.id(),
                            promptToolSummary, promptRequester);
                        // [IMPL-03 D6-2 / OD-06] continue:false 不再无条件阻断 (旧实现
                        //   case 4 立即填 ToolResult.error). 对齐 CC toolExecution.ts:1025-1027:
                        //   shouldPreventContinuation 只在 permissionDecision.behavior !== 'allow'
                        //   (deny 路径) 补"Execution stopped by PreToolUse hook"文案; allow 路径
                        //   工具照跑, 成功后在下方注入 hook_stopped_continuation attachment
                        //   (toolExecution.ts:1571-1582). 阻断与否由最终权限决策
                        //   (finalDecision instanceof Deny) 决定, 见 [R32-#19] 段.
                        // [A2 强化] 数据流日志: PreToolUse hook 成功执行
                        // [IMP-HOOKS-S6 ⊕2] preHookNames 名单打印已删除 (CC 无对应),
                        //   decision 归因保留.
                        String preDecision = preOutcome.permissionBehavior() != null
                            ? preOutcome.permissionBehavior().getClass().getSimpleName()
                            : "Proceed";
                        log.info("PreToolUse hook executed, hookName={}, toolName={}, decision={}",
                            "PreToolUse:" + t.call.name(), t.call.name(), preDecision);
                    } catch (com.nexusai.application.agent.permission.hook.AbortException ae) {
                        // [P1-2] PreToolUse hook 抛 AbortException → 立即停止工具 + record abort
                        //   对齐 CC utils/hooks.ts:2045-2051 executeHooks AbortError rethrow 语义
                        //   HookRegistry 已 unwrap + re-throw (R31-D2.6), 此处接住后立即阻断工具.
                        log.warn("TOOL PreToolUse hook AbortException: name={} id={} reason={}",
                            t.call.name(), abbreviate(t.call.id(), 24), ae.getMessage());
                        t.result = ToolResult.error(t.call.id(),
                            "PreToolUse hook aborted: " + ae.getMessage(), "abort");
                        t.isError = true;
                        erroredCount.incrementAndGet();
                    } catch (Throwable th) {
                        // [B GAP-HOOK-02 修正] 通用 Throwable → 非阻断 + error attachment ·
                        //   对齐 CC 实际源码 hooks.ts:2698-2730 (Pattern #9 实证, 不信 B.md 描述):
                        //   CC executeHooks 对 command/prompt/http/agent hook 异常 yield
                        //   {@code hook_non_blocking_error} attachment + outcome non_blocking_error,
                        //   <b>工具继续执行</b> (不是 stop / erroredCount++ / result error).
                        //   Java 端 HookRegistry 内部已 catch 单个 hook 异常 (warn + telemetry +
                        //   continue, 与 CC 同语义); 本 catch 接住的是逃出 HookRegistry 的
                        //   聚合机制异常 — 保持非阻断, 补 hook_error_during_execution attachment
                        //   (CC createAttachmentMessage type hook_error_during_execution),
                        //   让 LLM/transcript 可见 hook 执行失败, 但不中止工具.
                        log.warn("TOOL PreToolUse hook threw: name={} id={} err={}",
                            t.call.name(), abbreviate(t.call.id(), 24), th.toString());
                        if (agentStateRef != null) {
                            try {
                                agentStateRef.appendAttachment(
                                    com.nexusai.application.agent.attachment.AttachmentMessageDto
                                        .hookErrorDuringExecution(
                                            "PreToolUse:" + t.call.name(), t.call.id(), "PreToolUse",
                                            th.getMessage() != null ? th.getMessage() : th.toString()));
                            } catch (Throwable attTh) {
                                log.warn("TOOL hook error attachment 注入失败: {}",
                                    attTh.toString());
                            }
                        }
                    } finally {
                        // [R32-b12 Fix-v3 P1-2] hook 出口 stop · 仅含 hook 耗时.
                        preToolHookDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - preToolHookStartNs);
                    }
                } else if (hookRegistry == null) {
                    // [A2 强化] 数据流日志: hookRegistry=null 跳过所有 hook 阶段 (向后兼容)
                    // WHY: R32 Phase 2 PR 1 之前大量单测场景构造 null hookRegistry, 期望 hook 链
                    //      完全跳过而非 NPE. 首次遇到时 warn 一次, 让单测维护者立即知道.
                    //      频率控制: volatile boolean nullWarned 防止 per-tool-call spam.
                    warnHookRegistryNullOnce();
                }
                // 把精确 hook-only durationMs 暂存到 t.preToolHookDurationMs 供 emitSuccessTelemetry 使用
                t.preToolHookDurationMs = preToolHookDurationMs;
                // [Session H5] PreToolUse attachment 注入 (CC runPreToolUseHooks yield hook_cancelled + hook_additional_context;
                //   PreToolUse 不 yield hook_blocking_error/hook_stopped_continuation - blockingError 走 deny permission 通道)
                injectPreToolUseHookAttachments(t, preOutcome, ctx);
                // ── [IMP-HOOKS-S6 E7·CCJ-T6-21] hook 执行期间 abort → 工具不执行 ──
                // 对齐 CC toolHooks.ts:582-603 (per-result abort 检查 → hook_cancelled +
                // stop yield + return) + toolExecution.ts:848-860 (stop case →
                // createToolResultStopMessage + toolUseResult `Error: ${stopReason}` →
                // return, 工具不执行). hook_cancelled attachment 已由
                // injectPreToolUseHookAttachments (:2749) 注入 (CC yield hook_cancelled
                // 先于 stop). Java 预检在整批 hook 完成后 (CC 在逐结果内) — 方向安全
                // (abort=用户停止意图), 行为面略宽于 CC (有 hook 注册但无匹配时 abort 也
                // 短路). [IMP-HOOKS-S9 回归修正] 门控补 hookRegistry != null — CC 的
                // abort-stop 检查在 runPreToolUseHooks 逐结果循环内 (toolHooks.ts:582-603),
                // 无 hook 注册时该检查不存在, 工具是否执行由 getAbortReason per-tool
                // 决策 (ts:210-231: interrupt + interruptBehavior=block → 继续执行)。
                // 旧实现无门控 → hookRegistry==null 场景误短路, 破坏 per-tool 语义
                // (StreamingToolExecutorDispatchTest 回归, 断点基线绿).
                if (hookRegistry != null
                    && t.result == null
                    && ctx != null && ctx.abortController() != null
                    && ctx.abortController().isCancelled()) {
                    // [IMP-C4 REQ-G3-2-3 + G33①] PreToolUse stop 分支 content =
                    //   withMemoryCorrectionHint(CANCEL_MESSAGE)（对齐 CC toolExecution.ts:443-448
                    //   createToolResultStopMessage + content.content = withMemoryCorrectionHint(CANCEL_MESSAGE)；
                    //   utils/messages.ts:210-211）。旧实现 `Error: ${stopReason}` 在
                    //   stopReason null 时产出 "Error: undefined"（TR-A3 △-4 行为可观测差异）。
                    String stopReason = preOutcome.stopReason();
                    t.result = ToolResult.error(t.call.id(),
                        com.nexusai.application.agent.permission.PermissionRejectMessages.withMemoryCorrectionHint(
                            com.nexusai.application.agent.permission.PermissionRejectMessages.CANCEL_MESSAGE), "abort");
                    t.isError = true;
                    // [IMP-HOOKS-S6 E7] stoppedByHookExecuted 标志: post 链 (:1712-1843)
                    //   对 abort-stop 结果跳过 failure analytics + executePostToolUseFailure
                    //   (CC stop case return 不触发失败链).
                    t.stoppedByHookExecuted = true;
                    if (log.isInfoEnabled()) {
                        log.info("TOOL PreToolUse hook abort → 工具不执行: name={} id={} stopReason={}",
                            t.call.name(), abbreviate(t.call.id(), 24), stopReason);
                    }
                }

                if (t.tool != null && t.result == null) {
                    // [Session H8] S9 遗留 "只写不读" 死字段已修复: AHR.message() 由
                    //   injectPreToolUseHookAttachments 结算 (hook_user_message → 普通 user
                    //   ChatMessageDto(isMeta=false) → dispatch 并入 t.result.newMessages →
                    //   与 tool_result 同批送达, 对齐 CC toolHooks.ts:478-480 message →
                    //   toolExecution.ts:815 resultingMessages). 不再以死字段伪装, B-R5 遗憾关闭.

                    // ── [R32-#18 + A2-P0-2 修复] processedInput 跨阶段收敛:
                    //   hookUpdatedInput 是<b>整体替换</b> (CC 全替换语义) ·
                    //   对齐 CC toolExecution.ts:837 `processedInput = result.updatedInput`
                    //   + toolExecution.ts:1131 `if (permissionDecision.updatedInput !== undefined)
                    //   { processedInput = permissionDecision.updatedInput }`.
                    //   原 Java 实现 (mergeToInput 浅合并) 偏离 CC: hook 只回部分字段时 Java
                    //   保留 LLM 原字段 (混淆), hook 想删字段 (传 null) Java 删不掉.
                    //   修复后: hook 返回的 Map 整体替换 LLM 原 input, 不在 Map 内的字段全部消失.
                    //   构造新 ToolUseBlock 用于 tool.execute 透传.
                    ToolUseBlock effectiveCall = t.call;
                    Map<String, Object> hookInput = preOutcome.updatedInput();
                    if (hookInput != null && !hookInput.isEmpty()) {
                        JsonNode effectiveInput = mapToJsonNode(hookInput);
                        effectiveCall = new ToolUseBlock(t.call.id(), t.call.name(), effectiveInput);
                        if (log.isDebugEnabled()) {
                            log.debug("TOOL PreToolUse hookUpdatedInput replaced (CC 全替换): name={} id={}",
                                t.call.name(), abbreviate(t.call.id(), 24));
                        }
                    }

                    // ── [Session H8 + D P1-2] resolveHookPermissionDecision 最终权限决策 ──
                    // 对齐 CC toolExecution.ts:921-929 调用点 + toolHooks.ts:332-433 定义:
                    //   · hook allow + 无 deny/ask 规则命中 → 直接放行 (跳过权限弹窗) —
                    //     H8 前 hook allow 会落入 gate 全 10 层管线 → 第 3 层兜底 Ask → 仍弹窗,
                    //     违反 CC "hook allow bypasses the permission prompt" 语义.
                    //   · requireCanUseTool / requiresUserInteraction 守卫 → 仍走 canUseTool
                    //   · hook allow + deny 规则 → deny 覆盖 (bypass-immune)
                    //   · hook allow + ask 规则 / hook ask / 无决策 → canUseTool (gate),
                    //     其中 hook ask 透传 forceDecision (弹窗展示 hook 的 ask 消息).
                    // [D P1-2 + hooks_v3 H-PERM-02 · 1-7] 解析入口 = permissionResolver 实例
                    //   (CC toolExecution.ts:921-929 调用点镜像, 7 参); 原
                    //   LlmAgentLoop.resolveHookPermissionDecision 静态入口已改实例注入,
                    //   本类直接经 {@link #permissionResolver} bean 委托
                    //   HookPermissionResolver (CC toolHooks.ts:332-433 定义镜像).
                    // [H8] blockingError → hook deny · 对齐 CC toolHooks.ts:481-498
                    //   (getPreToolHookBlockingMessage 格式化后作为 deny message 注入 LLM).
                    PermissionResult hookPermission = preOutcome.permissionBehavior();
                    if (hookPermission == null && preOutcome.blockingError() != null) {
                        String denialMessage = HookRegistry.getPreToolHookBlockingMessage(
                            "PreToolUse:" + t.call.name(), preOutcome.blockingError());
                        hookPermission = new PermissionResult.Deny(
                            denialMessage,
                            new PermissionDecisionReason.Hook(
                                "PreToolUse:" + t.call.name(), null, denialMessage),
                            t.call.id());
                    }
                    // gate == null → 跳过 resolver (向后兼容: 仅 hook Deny 阻断);
                    // ctx == null 或 ctx.permissionContext() == null → skip (无规则集).
                    // [FIX-A-R2] permission 门输入选型：hook 未改 input → 用 backfilledInput
                    //   （file_path 已绝对化），使相对/~ 路径命中权限内容规则（对齐 CC
                    //   toolExecution.ts:921-936 resolveHookPermissionDecision 第 3 参
                    //   processedInput 是 backfilled 绝对版）；hook 已整体替换 input →
                    //   用 effectiveCall.input()（hook 全替换优先于 backfill）。
                    JsonNode permissionInput = (hookInput != null && !hookInput.isEmpty())
                        ? effectiveCall.input()
                        : backfilledInput;
                    if (log.isDebugEnabled()) {
                        log.debug("TOOL permission 门输入: callId={} tool={} useBackfill={}",
                            abbreviate(t.call.id(), 24), t.call.name(),
                            (hookInput == null || hookInput.isEmpty()));
                    }
                    PermissionResult finalDecision = hookPermission;
                    HookPermissionResolver.ResolvedPermission resolved = null;
                    if (permissionGate != null && ctx != null && ctx.permissionContext() != null) {
                        try {
                            HookPermissionResolver.ResolvedPermission r =
                                permissionResolver.resolve(
                                    hookPermission, preOutcome.updatedInput(),
                                    t.tool, permissionInput, ctx, t.call.id(),
                                    (tool, input, cctx, toolUseId, forceDecision) -> {
                                        // Java 端 canUseTool 等价 = gate.check 6 参 (forceDecision
                                        // 透传, 对齐 CC useCanUseTool.tsx:37 forceDecision 短路管线).
                                        ToolPermissionGate.DecisionResult gr = permissionGate.check(
                                            tool,
                                            new ToolUseBlock(toolUseId, tool.name(), input),
                                            input, cctx, cctx.permissionContext(), forceDecision);
                                        // [A1 撤外层] (h) 决策 telemetry 归因 · 对齐 CC toolExecution.ts:948-977
                                        log.info("TOOL permission gate check: callId={} tool={} decision={}",
                                            abbreviate(toolUseId, 24), tool.name(), gr.decision());
                                        injectDecisionInfo(t, gr);
                                        return gr;
                                    });
                            resolved = r;
                            finalDecision = resolved.decision();
                            log.info("TOOL hookPermissionResolver: callId={} tool={} hookDecision={} final={}",
                                abbreviate(t.call.id(), 24), t.call.name(),
                                hookPermission != null ? hookPermission.getClass().getSimpleName() : "Proceed",
                                finalDecision != null ? finalDecision.getClass().getSimpleName() : "null");
                        } catch (com.nexusai.application.agent.permission.hook.AbortException ae) {
                            // [R5 / OPD-WF3-DC-v4-07] gate.check 的 AbortException 必须透传（对齐
                            // CC toolExecution.ts catch AbortError → isInterrupt，toolExecution.ts:1694；
                            // 以及 CC permissions.ts:826-828/:1024-1026 抛 AbortError 中止 agent）。
                            // 旧 catch(Throwable) 把 AbortException 转 ToolResult.error 后继续往下走
                            // → 工具照常执行，用户中止意图被吞。重抛 → 落到外层 catch(Throwable)
                            // (:2152)，该处 isAbort=true + 工具不执行 + PostToolUseFailure hook
                            // isInterrupt=true（CC toolExecution.ts:1691-1707 语义）。
                            throw ae;
                        } catch (Throwable th) {
                            // gate 抛异常 → fail loud (CLAUDE.md 规则十二) 但不挂掉 tool batch
                            log.error("TOOL permission gate threw: name={} id={} err={}",
                                t.call.name(), abbreviate(t.call.id(), 24), th.toString());
                            t.result = ToolResult.error(t.call.id(),
                                "permission gate failed: " + th.getMessage());
                            t.isError = true;
                        }
                    }

                    // ── [IMP-HOOKS-S6 E9·CCJ-T6-20] resolved.input() 应用 ──
                    // 对齐 CC toolExecution.ts:930-931 `processedInput = resolved.input` —
                    // resolver 返回的生效 input (hook allow/ask updatedInput 或原 input)
                    // 覆盖 effectiveCall, 供 tool.execute + Post/Failure hook 链使用.
                    // [Java 契约判别] Allow.updatedInput 为 record 强制非空字段 (CC 端
                    //   optional), "hook 是否提供了 updatedInput" 以 AHR.updatedInput()
                    //   为唯一判别 (HookPermissionResolver H8 契约注释) — AHR.updatedInput()
                    //   == null 时视为 CC "无 updatedInput" (原 input 保留), 不应用
                    //   resolved.input (此时其值 = allow.updatedInput() 桩值, 非 hook 意图).
                    if (resolved != null && resolved.input() != null
                        && preOutcome.updatedInput() != null) {
                        effectiveCall = new ToolUseBlock(t.call.id(), t.call.name(), resolved.input());
                    }
                    // [IMP-HOOKS-S6 E9·CCJ-T6-13] executedInput 落盘 (pre 链 resolver 后):
                    //   PostToolUse/PostToolUseFailure hook 入参 = 最终生效 input
                    //   (CC toolExecution.ts:1488/:1705 processedInput).
                    t.executedInput = effectiveCall.input();

                    // ── [IMP-HOOKS-S6 CCJ-T6-18] hook_permission_decision attachment ──
                    // 对齐 CC toolExecution.ts:979-993: decisionReason.type==='hook' &&
                    // hookName==='PermissionRequest' && behavior!=='ask' →
                    // push hook_permission_decision attachment (decision=allow/deny).
                    // LLM API 上下文排除已由 AgentLoopContext 既有逻辑覆盖
                    // (:2078-2079/:2406-2408 hook_permission_decision → null).
                    PermissionDecisionReason finalReason = null;
                    if (finalDecision instanceof PermissionResult.Allow a) {
                        finalReason = a.reason();
                    } else if (finalDecision instanceof PermissionResult.Deny d) {
                        finalReason = d.reason();
                    } else if (finalDecision instanceof PermissionResult.Ask a) {
                        finalReason = a.reason();
                    } else if (finalDecision instanceof PermissionResult.Passthrough p) {
                        finalReason = p.reason();
                    }
                    if (finalDecision != null
                        && !(finalDecision instanceof PermissionResult.Ask)
                        && finalReason instanceof PermissionDecisionReason.Hook hookReason
                        && "PermissionRequest".equals(hookReason.hookName())
                        && agentStateRef != null) {
                        String decisionText = finalDecision instanceof PermissionResult.Allow ? "allow" : "deny";
                        agentStateRef.appendAttachment(
                            AttachmentMessageDto.hookPermissionDecision(decisionText, t.call.id()));
                        if (log.isDebugEnabled()) {
                            log.debug("TOOL hook_permission_decision attachment: name={} id={} decision={}",
                                t.call.name(), abbreviate(t.call.id(), 24), decisionText);
                        }
                    }

                    // ── [R32-#19] 最终 Deny → 阻断 ──
                    // 来源: hook deny (bypass-immune 最高优先级) / rule deny 覆盖 hook allow /
                    //       gate deny (ask 规则弹窗被拒 / classifier auto-mode deny).
                    if (t.result == null && finalDecision instanceof PermissionResult.Deny deny) {
                        if (deny == hookPermission) {
                            // hook 自身 deny (含 blockingError 翻译) → 原文直出 (无 Java 独有
                            //   前缀, 对齐 CC toolExecution.ts:1033 tool_result content =
                            //   errorMessage 原文直出). CC 兜底 (toolExecution.ts:1025-1027):
                            //   preventContinuation && 无阻断消息 → "Execution stopped by
                            //   PreToolUse hook[: stopReason]" (Java Deny 恒非空消息, 兜底
                            //   不可达 — denyBlockingMessage 保持 CC 防御语义, 独立单测覆盖).
                            String errorMessage = denyBlockingMessage(deny.message(),
                                preOutcome.preventContinuation(), preOutcome.stopReason());
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL hook deny 阻断: callId={} tool={} message={}",
                                    abbreviate(t.call.id(), 24), t.call.name(), errorMessage);
                            }
                            t.result = ToolResult.error(t.call.id(), errorMessage, "permission");
                            t.isError = true;
                        } else {
                            // rule deny / gate deny → toToolResult 提取 denyResult.message()
                            // [R32-b15 C15] permission denied 错误分类
                            t.result = ToolPermissionGate.toToolResult(
                                ToolPermissionGate.Decision.DENY,
                                deny,
                                t.call.id());
                            t.isError = true;
                            t.errorCategory = "permission";
                        }
                        erroredCount.incrementAndGet();
                        // ── [R32-c-1] retry hook 触发 (CC toolExecution.ts:1075-1101) ──
                        // 触发条件: transcript classifier 启用 + 决策来源 = auto-mode Classifier.
                        // maybeFirePermissionDeniedRetry 内部自守卫 (reason 非 Classifier
                        // auto-mode → 早返 no-op), hook deny / rule deny 天然不触发, 对齐
                        // CC 默认行为 (hook deny 不走 classifier retry 路径).
                        maybeFirePermissionDeniedRetry(t, effectiveCall, deny);
                    } else if (t.result == null) {
                        // [FIX-E askuser-answers] gate Allow 的 updatedInput（含合并后的
                        //   answers/annotations）应用到 effectiveCall —— 对齐 CC
                        //   toolExecution.ts:1130-1132 `if (permissionDecision.updatedInput
                        //   !== undefined) { processedInput = permissionDecision.updatedInput }`。
                        //   修复缺口：此前 gate 弹窗用户 Allow+answers 后，合并的 input 在
                        //   tool.execute 前被丢弃（AskUserQuestionTool 收不到 answers 恒 fail-loud）。
                        // [FIX-A-R2] 守卫：gate 可能把 backfilled 回显（绝对 file_path）作为
                        //   Allow.updatedInput 返回——若其值与 permissionInput 相等，说明只是
                        //   backfill 回显（非真实 ask answers/annotations），跳过重建 effectiveCall，
                        //   保持 effectiveCall 仍为 t.call 原始输入（transcript/VCR 哈希稳定，对齐
                        //   CC toolExecution.ts:1182-1205 callInput 收敛）。仅当 updatedInput 与
                        //   permissionInput 值不同（真实 ask answers/annotations）才应用。
                        if (finalDecision instanceof PermissionResult.Allow allow
                                && allow.updatedInput() != null
                                && !allow.updatedInput().equals(permissionInput)) {
                            effectiveCall = new ToolUseBlock(t.call.id(), t.call.name(), allow.updatedInput());
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL gate Allow.updatedInput 应用（含 answers/annotations）: name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        } else if (log.isDebugEnabled()
                                && finalDecision instanceof PermissionResult.Allow allow
                                && allow.updatedInput() != null) {
                            // 数据流日志：backfill 回显跳过（值相等 → 不覆盖 effectiveCall）
                            log.debug("TOOL gate Allow.updatedInput 为 backfill 回显跳过（防绝对路径泄漏到 tool.execute）: name={} id={}",
                                t.call.name(), abbreviate(t.call.id(), 24));
                        }
                        // [Session H8] AHR.message() 已由 injectPreToolUseHookAttachments
                        //   结算 (hook_user_message → 普通 user ChatMessageDto(isMeta=false) →
                        //   dispatch 并入 t.result.newMessages → 与 tool_result 同批送达,
                        //   对齐 CC toolHooks.ts:478-480 resultingMessages);
                        //   修复 S9 遗留 "只写不读" 死字段问题 (B-R5).
                        // ALLOW (或 gate==null) → 真实执行
                        // ── [WF3-03 U-9 G1] 执行层 updatedInput 断链修复 ──
                        // 对齐 CC toolExecution.ts:1130-1131:
                        //   if (permissionDecision.updatedInput !== undefined)
                        //   { processedInput = permissionDecision.updatedInput }.
                        // 之前 effectiveCall 仅由 preOutcome.updatedInput() (AHR 层, CC
                        //   toolExecution.ts:837) 构建; finalDecision (resolved.decision)
                        //   携带的 Allow.updatedInput (用户弹窗改写 / hook ask→用户改 input)
                        //   未到达 execute (G1 断链点). 修复: finalDecision 为 gate 替换的
                        //   Allow (finalDecision != hookPermission) 时, 用 allow.updatedInput()
                        //   覆盖 effectiveCall.
                        // WHY 加 finalDecision != hookPermission 守卫: hook 自身的
                        //   permissionBehavior Allow 的 updatedInput 是 Java 强制非空占位
                        //   (HookRegistry.toPermissionResult :1289-1290 无改写时 = 原 input),
                        //   该改写已由 AHR.updatedInput() 全替换 (line 1400-1408) 生效; 若
                        //   盲目覆盖会把占位空对象写回 execute (R32-#18 回归). 只有 resolver
                        //   (gate 决策) 替换出的 Allow 才是权威改写 (CC decision.updatedInput).
                        if (finalDecision instanceof PermissionResult.Allow allow
                                && finalDecision != hookPermission) {
                            effectiveCall = new ToolUseBlock(t.call.id(), t.call.name(),
                                allow.updatedInput());
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL 执行层应用 permissionDecision.updatedInput (U-9 G1): name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        }
                        // R31: 传入每次调用的进度回调；未 override 三参 execute 的工具回退二参方法。
                        if (log.isDebugEnabled() && t.onProgress != null) {
                            log.debug("工具执行携带进度回调: name={} id={}",
                                t.call.name(), abbreviate(t.call.id(), 24));
                        }
                        // s07-P1-3: Tool.execute 返回 AgentToolResult (sealed interface).
                        // R32-#18 修复: 用 effectiveCall(含 hookUpdatedInput) 替代原 t.call
                        // 让 hook 修改的 input 真正到达 tool.execute (而非仅 permission gate).
                        // [R32-b12 Fix-v3 P1-2] tool.execute 计时: 单独记录 tool 真实耗时
                        //   (与 preToolHookDurationMs 区分), 便于 telemetry 区分报告.
                        // [R32-b15 C8] onProgress 包装: 同时入队 pendingProgress + 调用原 callback.
                        //   对齐 CC StreamingToolExecutor.ts:407 pendingProgress push + 给原 onProgress.
                        // [IMP-HOOKS-S6 E9·CCJ-T6-20] permissionDecision.updatedInput 覆盖 ·
                        //   对齐 CC toolExecution.ts:1128-1132 `if (permissionDecision.updatedInput
                        //   !== undefined) { processedInput = permissionDecision.updatedInput }`
                        //   (hook allow 携带的 updatedInput 最后覆盖生效 input, 供 call +
                        //   Post/Failure hook 链使用).
                        if (finalDecision instanceof PermissionResult.Allow allow
                            && allow.updatedInput() != null
                            // [Java 契约判别] 同 resolved.input 门: AHR.updatedInput() 为
                            //   "hook 是否提供了 updatedInput" 唯一判别 (H8 契约), null →
                            //   不覆盖 (Allow.updatedInput 非空是 record 强制, 非 hook 意图)
                            && preOutcome.updatedInput() != null) {
                            effectiveCall = new ToolUseBlock(t.call.id(), t.call.name(), allow.updatedInput());
                            t.executedInput = allow.updatedInput();
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL permissionDecision.updatedInput 覆盖: name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        }
                        long toolExecStartNs = System.nanoTime();
                        // [B GAP-EXEC-08 修正] 标记工具真实执行 (permission DENY 等未执行
                        //   路径保持 false, 不触发 success-path Bash sibling abort)
                        t.toolExecuted = true;
                        try {
                            Consumer<Tool.ToolProgress> originalCallback = t.onProgress;
                            Consumer<Tool.ToolProgress> wrappedCallback = progress -> {
                                // 1. 入队 pendingProgress
                                enqueueProgress(progress);
                                // 2. 调原 onProgress (LlmAgentLoop 注入)
                                if (originalCallback != null) {
                                    try {
                                        originalCallback.accept(progress);
                                    } catch (Throwable th) {
                                        log.warn("TOOL onProgress 回调失败: name={} id={} err={}",
                                            t.call.name(), abbreviate(t.call.id(), 24), th.toString());
                                    }
                                }
                            };
                            if (t.tool instanceof SubagentTool subagentTool) {
                                ForkSubagentMessages.Message assistantMessage = subagentAssistantMessage;
                                // 降级构造: subagentAssistantMessage 为 null 时, 仅以当前 tool_use 组装
                                // 最小 AssistantMessage. 主循环路径已由 LlmAgentLoop 注入完整父 assistant
                                // 消息 (fork 缓存共享前缀前提, buildForkedMessages 克隆全量 content blocks),
                                // 本 fallback 仅非主循环路径触发 — 此时无缓存前缀一致性诉求, 行为等价
                                // CC forkSubagent.ts:127-139 无 tool_use 边界逻辑 (透明披露, 非行为级偏离).
                                if (assistantMessage == null) {
                                    String assistantId = t.parent != null
                                        ? t.parent.assistantMessageId()
                                        : effectiveCall.id();
                                    assistantMessage = new ForkSubagentMessages.AssistantMessage(
                                        assistantId,
                                        List.of(new ForkSubagentMessages.BetaToolUseBlock(
                                            effectiveCall.id(), effectiveCall.name(), effectiveCall.input())));
                                }
                                // [GAP-R1] 工具执行线程恢复调度侧捕获的 teammate 上下文
                                // （对齐 CC AsyncLocalStorage 跨异步传播；null=主会话不包装）。
                                // 注: effectiveCall/assistantMessage 为可重赋值局部 → 先取 final 副本供 lambda 捕获。
                                ToolUseBlock effectiveCallForExecute = effectiveCall;
                                ForkSubagentMessages.Message assistantMessageForExecute = assistantMessage;
                                t.result = t.capturedTeammateContext != null
                                    ? TeammateContext.runWithTeammateContext(t.capturedTeammateContext,
                                        () -> subagentTool.execute(
                                            effectiveCallForExecute, ctx, wrappedCallback,
                                            subagentAgentOptions, assistantMessageForExecute))
                                    : subagentTool.execute(
                                        effectiveCallForExecute, ctx, wrappedCallback,
                                        subagentAgentOptions, assistantMessageForExecute);
                                if (log.isDebugEnabled()) {
                                    log.debug("TOOL Subagent 五参特化分发: name={} id={} querySource={}",
                                        effectiveCall.name(), abbreviate(effectiveCall.id(), 24),
                                        subagentAgentOptions.querySource());
                                }
                            } else {
                                ToolUseBlock effectiveCallForExecute = effectiveCall;
                                t.result = t.capturedTeammateContext != null
                                    ? TeammateContext.runWithTeammateContext(t.capturedTeammateContext,
                                        () -> t.tool.execute(effectiveCallForExecute, ctx, wrappedCallback))
                                    : t.tool.execute(effectiveCallForExecute, ctx, wrappedCallback);
                            }
                        } finally {
                            t.toolDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - toolExecStartNs);
                        }
                        // [IMP-C2 返工] 正常返回路径推导 isError：ToolResult 4 字段契约删除
                        // isError 后，执行器在知悉结果处（工具正常返回 ToolResult.error）经
                        // isToolErrorData(data) 推导错误标志（CC is_error 由执行路径推导）。
                        // 供 sibling abort（:1898）、failure analytics、getResultErrorFlags
                        // 配对推导使用；Bash 工具经 interpretExitCodeResult 显式标志已覆盖，
                        // 此处兜底所有直接 execute 返回 error ToolResult 的工具。
                        if (t.result instanceof ToolResult<?> ret) {
                            t.isError = com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(ret.data());
                            if (t.isError && log.isDebugEnabled()) {
                                log.debug("TOOL 正常返回路径推导 isError=true: name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        }
                        // [IMPL-03 D6-2 / OD-06] continue:false + allow → 工具照跑;
                        //   成功后注入 hook_stopped_continuation attachment (CC
                        //   toolExecution.ts:1571-1582 createAttachmentMessage
                        //   hook_stopped_continuation, message = stopReason || 缺省
                        //   "Execution stopped by hook"). 工具失败路径不注入 (CC 错误跳
                        //   catch, 该段不可达).
                        if (preOutcome.preventContinuation()
                            && t.result != null && !t.isError
                            && agentStateRef != null) {
                            agentStateRef.appendAttachment(
                                com.nexusai.application.agent.attachment.AttachmentMessageDto
                                    .hookStoppedContinuation(
                                        "PreToolUse:" + t.call.name(), t.call.id(), "PreToolUse",
                                        preOutcome.stopReason() != null
                                            ? preOutcome.stopReason() : "Execution stopped by hook"));
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL continue:false + allow → 工具已执行, 注入 hook_stopped_continuation: name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        }
                    }
                }
                // s07-P1-3 深度重构: ToolResult<T> 折入后的 dispatch (CC SkillTool.ts:735-860 call() 返回协议).
                // A1 退役 ExtendedToolResult 后, newMessages/contextModifier/structuredOutput 已
                // 折入 ToolResult<T> 本身 (Tool.ts:323/330/331-335), 不再用 instanceof 分流.
                // t.result 类型是 AgentToolResult<?> (sealed, 唯一实现 ToolResult), 非 null 即需 dispatch.
                // ── [hook message 普通消息通道] PreToolUse hook plain message 合并进 newMessages ──
                // 对齐 CC resultingMessages.push(result.message) (toolExecution.ts:815) → 与
                // tool_result 同批 (ToolResultApplier.apply → state.messages().addAll)、一次性
                // (query.ts:1395 filter type==='user' → :1716 messages 同批). 复用 retry isMeta
                // 同一约定通道 (StreamingToolExecutor.java maybeFirePermissionDeniedRetry 先例).
                // 兜底: t.result 为 null (未执行/测试场景) 时直接 appendMessage 保底送达.
                if (t.pendingHookUserMessages != null && !t.pendingHookUserMessages.isEmpty()) {
                    if (t.result instanceof ToolResult<?> tr) {
                        java.util.List<ChatMessageDto> merged = new java.util.ArrayList<>(t.pendingHookUserMessages);
                        if (tr.newMessages() != null) {
                            merged.addAll(tr.newMessages());
                        }
                        t.result = new ToolResult<>(tr.data(), merged, tr.contextModifier(), tr.mcpMeta());
                        if (log.isDebugEnabled()) {
                            log.debug("HOOK PreToolUse message {} 条并入 tool_result.newMessages (普通消息通道): name={} id={}",
                                t.pendingHookUserMessages.size(), t.call.name(), abbreviate(t.call.id(), 24));
                        }
                        t.pendingHookUserMessages.clear();
                    } else if (agentStateRef != null) {
                        for (ChatMessageDto hookMsg : t.pendingHookUserMessages) {
                            agentStateRef.appendMessage(hookMsg);
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("HOOK PreToolUse message {} 条直接 appendMessage 保底 (t.result 为 null): name={} id={}",
                                t.pendingHookUserMessages.size(), t.call.name(), abbreviate(t.call.id(), 24));
                        }
                        t.pendingHookUserMessages.clear();
                    }
                }
                if (extendedResultHandler != null && t.result != null) {
                    if (deferContextModifier) {
                        // [R32-b15 Stage 2 C4 + C5] 延迟 apply: 把 contextModifier 替换
                        // add() 时占位的 null. LinkedHashMap 顺序保持为 add 顺序,
                        // 不受 completion 时序影响. 对齐 CC toolOrchestration.ts:30-62
                        //   queuedContextModifiers (按 tool_use_id 原序遍历).
                        java.util.function.Function<com.nexusai.application.agent.tool.ToolUseContext, com.nexusai.application.agent.tool.ToolUseContext> ctxMod =
                            ((ToolResult<?>) t.result).contextModifier();
                        if (ctxMod != null) {
                            synchronized (deferredContextModifiers) {
                                deferredContextModifiers.put(t.call.id(), ctxMod);
                            }
                            if (log.isDebugEnabled()) {
                                log.debug("TOOL deferred contextModifier queued: name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        } else if (log.isDebugEnabled()) {
                            // tool 未声明 modifier → 不覆盖占位, apply 时跳过 (保持 add 顺序位置)
                            log.debug("TOOL deferred queue: null modifier for id={}, will skip on apply",
                                abbreviate(t.call.id(), 24));
                        }
                        // 立即处理 newMessages / structuredOutput (不依赖顺序的载荷)
                        try {
                            extendedResultHandler.accept(t.result, t.call.id());
                        } catch (Throwable th) {
                            log.warn("ToolResult pre-handler threw for tool name={}: {}",
                                t.call.name(), th.toString());
                        }
                    } else {
                        try {
                            // [R32-#23 设计澄清] 立即 apply (向后兼容 s07-P1-3 wiring).
                            // CC StreamingToolExecutor.ts:388-395 注释说 "we currently don't
                            // support context modifiers for concurrent tools" 但 Java 端
                            // s07-P1-3 wiring 测试期望 safe/concurrent 工具也立即 apply.
                            // 为保持向后兼容, Java 端选择立即 apply (与 s07 行为一致).
                            //
                            // 风险: 立即 apply 可能破坏 prompt cache (LLM 看到的 tool list
                            // 顺序突变). 已知限制, 已由 Stage 2 引入 defer 模式解决.
                            extendedResultHandler.accept(t.result, t.call.id());
                            if (log.isDebugEnabled()) {
                                log.debug("ToolResult applied for tool name={} id={}",
                                    t.call.name(), abbreviate(t.call.id(), 24));
                            }
                        } catch (Throwable th) {
                            log.warn("ToolResult handler threw for tool name={}: {}",
                                t.call.name(), th.toString());
                        }
                    }
                }

                // ── [R32-#20] PostToolUse hook + MCP-specific 分流 (出口) ──
                // 对齐 CC toolExecution.ts:1483 runPostToolUseHooks + toolHooks.ts:39-191.
                // 对齐 CC toolExecution.ts:1494-1530 MCP 分流:
                //   - 'updatedMCPToolOutput' in hookResult → 替换 t.result.content
                //   - non-MCP + blockingError → 作为 feedback 追加到 t.result.content
                //   - non-MCP + additionalContext → 追加 metadata
                //
                // [IMP-HR-06 TC-01] PostToolUseFailure 触发面收窄 (对齐 CC toolExecution.ts:1483/1700/1103):
                //   runPostToolUseFailureHooks 仅在 catch (tool.call 抛异常) 块 (:1700) 触发 —
                //   success 路径 (工具真实执行返回, 无论 isError) 一律走 runPostToolUseHooks 成功链
                //   (CC :1483 对非异常结果无条件执行, 含工具正常 is_error 返回如 Bash 命令失败);
                //   权限 DENY 在 :1103 早返 return, 不跑任何 post/failure 链 → Java 以
                //   {@code t.toolExecuted} 排除 DENY/未执行结果 (不误入 post 链).
                //   注: 下方 failure analytics (tengu_tool_use_error) 为 telemetry 独立层,
                //   不属于本 hook 触发面 (CC catch 内 analytics 语义另行核验, 见 IMP-HR-06 记录 §8).
                //
                // [R32-b12 Fix-v3 P1-3] failure analytics 不依赖 hookRegistry:
                //   之前 `if (hookRegistry != null)` 包裹整个 PostToolUse hook + failure analytics
                //   分支, 导致 hookRegistry=null 时即使是 error ToolResult 也不发失败事件, 失败
                //   analytics 数据丢失. 现在拆分为两层:
                //   1. PostToolUse hook 串联 (MCP-specific 分流 + feedback): 仅 hookRegistry != null 时执行
                //   2. Failure analytics (tengu_tool_use_error + logOTelEvent('tool_result')):
                //      仅依赖 ToolResult.isError(), hookRegistry 是辅助 (有则带 hook 数据, 无则跳过)
                //   这保证所有失败路径的 telemetry 事件都被发出.
                if (t.result != null) {
                    ToolResult<?> baseResult = (ToolResult<?>) t.result;
                    // ── [B GAP-EXEC-08] Bash isError success-path sibling abort · ──
                    //   对齐 CC StreamingToolExecutor.ts:347-364 executeTool 循环内 isErrorResult 检查.
                    //   WHY (V2 §3.4 措辞纠正, Pattern #2 grep 实证): CC 的 isErrorResult 检查
                    //   不区分异常/正常 return — 工具<b>正常返回</b> is_error=true 的 result 同样
                    //   触发 {@code hasErrored=true + siblingAbortController.abort('sibling_error')}.
                    //   旧 Java 仅在 catch(Throwable) 路径 (下方 :1620-1640) 级联, success 路径
                    //   t.isError 只发 telemetry 不级联 → Bash 返回错误 result 时
                    //   兄弟工具不取消, 与 CC 语义相反.
                    //   [B 修正 2] 限定 {@code t.toolExecuted} (工具真实执行): permission gate
                    //   DENY / hook Deny / schema 校验失败 等<b>未执行</b>路径也产生 is_error
                    //   result (CC toolExecution.ts:1034), 但 CC 中并行工具各自的 DENY 结果
                    //   已完成不受 sibling abort 影响; Java 串行调度下若不排除会短路后续排队
                    //   工具 → DENY retry hook 丢失 (RetryHookE2ETest 回归).
                    //   CC 同时设置 erroredToolDescription = getToolDescription(tool) (ts:361)
                    //   供 sibling synthetic 文案 "Cancelled: parallel tool call ${desc} errored".
                    if (t.isError
                        && com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME.equals(t.call.name())
                        && t.toolExecuted
                        && !hasErrored) {
                        hasErrored = true;
                        erroredToolDescription = getToolDescription(t);
                        siblingAbortController.abort("sibling_error");
                        log.info("TOOL Bash isError (success path) → siblingAbortController.abort('sibling_error')");
                    }
                    // [IMP-HOOKS-S6 E7] abort-stop 结果跳过 failure analytics ·
                    //   CC stop case (toolExecution.ts:848-860) return 不触发失败链.
                    if (t.isError && !t.stoppedByHookExecuted) {
                        try {
                            // [R32-b12 Fix-v3 P1-3] 失败 analytics 双发:
                            //   1. tengu_tool_use_error (Statsig/1P)
                            //   2. logOTelEvent('tool_result') (success=false, OTel)
                            //   不再依赖 hookRegistry != null. 即使 hookRegistry=null,
                            //   普通工具返回 error result 也发失败事件.
                            emitPostToolUseFailureAnalytics(t, baseResult, t0);
                            // [A2 强化] 数据流日志: 失败事件已发出
                            // WHY: failure analytics 是 PostToolUse 失败路径的关键串联点
                            //      (CC line 1589-1737 5 类 fallback 语义), 数据流日志让运维能
                            //      直接 grep 到失败事件的 errorCategory, 排查 Statsig 1P + OTel 双发.
                            log.info("TOOL failure event emitted, errorCategory={}, toolName={}, toolUseId={}",
                                t.errorCategory != null ? t.errorCategory : "unknown",
                                t.call.name(), abbreviate(t.call.id(), 24));
                        } catch (Throwable th) {
                            log.warn("TOOL failure analytics threw: name={} id={} err={}",
                                t.call.name(), abbreviate(t.call.id(), 24), th.toString());
                        }
                    }
                    // ── PostToolUse hook 串联 (MCP-specific 分流 + feedback) ──
                    // 仅在 hookRegistry != null 时执行 (向后兼容, 单测场景 hookRegistry=null 时跳过).
                    // [IMP-HOOKS-S6 E7] abort-stop 结果跳过 post 链 (CC stop case return).
                    // [IMP-HR-06 TC-01] PostToolUseFailure 触发面收窄 (对齐 CC toolExecution.ts:1483/1700):
                    //   1. success 路径 (工具真实执行返回, 无论 isError) 一律走 executePostToolUse 成功链 —
                    //      CC runPostToolUseHooks (:1483) 对非异常结果无条件执行, 含工具正常 is_error 返回.
                    //   2. t.toolExecuted 守卫排除 DENY/未执行结果 (CC deny 早返 :1103 不跑 post 链).
                    //   3. executePostToolUseFailure 仅在下方 catch(Throwable) 块 (:2148) 触发 (CC catch :1700).
                    if (hookRegistry != null && ctx != null && t.tool != null
                        && !t.stoppedByHookExecuted && t.toolExecuted) {
                        try {
                            // 成功路径: executePostToolUse + MCP-specific 分流
                            // [IMP-HOOKS-S6 E9·CCJ-T6-13] PostToolUse 入参 = 生效 input (executedInput)
                            // [IMP-HR-06 TC-01] 不再按 baseResult.isError() 分流到 executePostToolUseFailure —
                            //   非异常结果无论 isError 均走 PostToolUse 成功链 (CC toolExecution.ts:1483).
                            GenericHook.HookResult postOutcome = hookRegistry.executePostToolUse(
                                t.call.name(),
                                t.executedInput != null ? t.executedInput : t.call.input(),
                                baseResult, ctx);
                            // [A2 强化] 数据流日志: PostToolUse hook 成功执行
                            // WHY: PostToolUse 在 tool.execute 之后调 (CC line 1483 runPostToolUseHooks),
                            //      是 MCP-specific 分流 (updatedMCPToolOutput) + non-MCP feedback
                            //      (blockingError + additionalContext) 的统一入口. 数据流日志让运维
                            //      直接 grep 到 PostToolUse 是否跑了 / 是否替换了 tool output.
                            // [IMP-HOOKS-S6 ⊕2] postHookNames 名单打印已删除 (CC 无对应),
                            //   decision 归因保留.
                            String postDecision = postOutcome != null
                                && postOutcome.updatedMCPToolOutput() != null ? "MCP_OUTPUT_REPLACED"
                                : (postOutcome != null && postOutcome.blockingError() != null ? "FEEDBACK_APPENDED"
                                : (postOutcome != null && postOutcome.preventContinuation() ? "STOPPED"
                                : "PROCEED"));
                            log.info("PostToolUse hook executed, hookName={}, toolName={}, decision={}",
                                "PostToolUse:" + t.call.name(), t.call.name(), postDecision);
                            if (postOutcome != null) {
                                boolean isMcp = t.tool.isMcp();
                                if (!isMcp) {
                                    // [DEL-STE-01] non-MCP blockingError 不再拼进 content + 翻转 isError —
                                    //   对齐 CC toolHooks.ts:105-115: 仅产 hook_blocking_error attachment
                                    //   (由下方 injectPostToolUseHookAttachments 统一注入), tool result 不变.
                                    // [IMP-HOOKS-S6 CCJ-T6-15] non-MCP additionalContext 不再拼入
                                    //   ToolResult content — 旧双交付 (content 拼接 + attachment)
                                    //   删除, 仅保留 attachment 通道. 对齐 CC toolHooks.ts:133-143
                                    //   / :269-280: additionalContexts → 仅 hook_additional_context
                                    //   attachment, tool result 不变 (CC toolExecution.ts:1514-1515
                                    //   resultingMessages.push 只推 attachment 消息).
                                }
                                // [DEL-STE-02] MCP 双跑 content 拼接已删除 — 对齐 CC toolExecution.ts:1498-1499:
                                //   MCP 分支仅 hookResults.push (hook 结果走 message/attachment 通道),
                                //   不改 tool output. blockingError/additionalContext 由下方
                                //   injectPostToolUseHookAttachments 统一注入 attachment.
                                // [DEL-STE-03] preventContinuation content 拼接 + isError 翻转已删除 —
                                //   对齐 CC toolHooks.ts:118-130: 仅产 hook_stopped_continuation attachment
                                //   (由下方 injectPostToolUseHookAttachments 统一注入) + return, tool result 不变.
                                // [Session H5] PostToolUse attachment 注入 (CC runPostToolUseHooks yield 5 类:
                                //   hook_cancelled/hook_blocking_error/hook_stopped_continuation/hook_additional_context/hook_error_during_execution)
                                // [IMP-ST-02 TC-04] 送达序对齐 CC push tail: CC 对每个 PostToolUse result 先 yield
                                //   附件 (message→blocking→stopped→additional, toolHooks.ts:95-151), updatedMCPToolOutput
                                //   最后 yield (:146-151) + 消费端 addToolResult 之后 flush (toolExecution.ts:1540-1542
                                //   /1585-1587) → 附件先注入 state.attachments() 通道, MCP toolOutput 替换后置, 与 CC
                                //   代码序一致 (旧实现替换在前、注入在后, X-PROBE EV-XP-W3-026 代码序相反).
                                injectPostToolUseHookAttachments(t, postOutcome, ctx, "PostToolUse");
                                // MCP-specific 分流: updatedMCPToolOutput → 替换 toolOutput (CC 最后 yield)
                                if (isMcp && postOutcome.updatedMCPToolOutput() != null) {
                                    Object updated = postOutcome.updatedMCPToolOutput();
                                    String updatedContent = updated instanceof String
                                        ? (String) updated : String.valueOf(updated);
                                    // [IMP-ST-01] 对齐 CC toolExecution.ts:1400-1401/1467/1541: updatedMCPToolOutput
                                    // 仅替换 data, 原 result 附属字段 (contextModifier/mcpMeta) 保留进新结果 —
                                    // CC addToolResult 复用 result.contextModifier + result.mcpMeta 于最终
                                    // user message (probe WF3-02 §7 △-2 / OPD-TC-02). newMessages 不保留: 已在
                                    // extendedResultHandler (ToolResultApplier.apply) 阶段消费, 避免二次追加.
                                    // [IMP-C2 合并] master ToolResult 4 字段契约无 toolUseId/isError/errorCategory/
                                    //   structuredOutput 字段 (structuredOutput 走 AgentState 通道) —
                                    //   4 字段构造保留 contextModifier/mcpMeta 对齐 [IMP-ST-01] 语义.
                                    t.result = new ToolResult<>(updatedContent, null,
                                        baseResult.contextModifier(), baseResult.mcpMeta());
                                    log.info("TOOL PostToolUse MCP output replaced: name={} id={}",
                                        t.call.name(), abbreviate(t.call.id(), 24));
                                }
                            }
                        } catch (Throwable th) {
                            // hook 抛异常 → best-effort, 不挂掉 tool batch
                            log.warn("TOOL PostToolUse hook threw: name={} id={} err={}",
                                t.call.name(), abbreviate(t.call.id(), 24), th.toString());
                            // 注: 真正的 hook 抛错埋点 (tengu_post_tool_hook_error /
                            //   tengu_post_tool_failure_hook_error) 在 HookRegistry.executePostToolUse +
                            //   executeEvent 内部 catch. 此 catch 仅捕获 executePostToolUse / executeEvent
                            //   自身异常 (理论几乎不可达, 因为 HookRegistry 内部已 swallow hook 抛错).
                            //   不重复发埋点, 避免 double-fire.
                        }
                    }
                }

                t.status = Status.COMPLETED;
                // [工具调用实时推] 成功漏斗出口: status 已置 COMPLETED 后实时推 tool_result
                //   (T5 断言发送时刻 status 已 COMPLETED). hook-stop 分支 (1476-1488 设置
                //   t.result 后不 return) 落入本出口被覆盖, 推送 isError=true 的 error 结果.
                pushToolResultRealtime(t);
                long totalElapsed = System.currentTimeMillis() - t0;
                log.info("TOOL result: name={} id={} outputLen={} isError={} elapsed={}ms",
                    t.call.name(), abbreviate(t.call.id(), 24),
                    t.result == null ? 0 : (t.result.data() instanceof String str ? str.length()
                        : (t.result.data() != null ? String.valueOf(t.result.data()).length() : 0)),
                    t.isError,
                    totalElapsed);
                // [R32-b12 D-5 P1] tengu_tool_use_success + logOTelEvent('tool_result') 埋点 ·
                //   对齐 CC Open-ClaudeCode/src/services/tools/toolExecution.ts:1134-1395.
                //   触发条件: baseResult !isError() (即成功路径).
                //   字段: durationMs / preToolHookDurationMs / toolResultSizeBytes / fileExtension +
                //         decision_source / decision_type / mcp_server_scope / tool_name / success.
                emitSuccessTelemetry(t, t0);
                // [H14] CC UserToolSuccessMessage.tsx:47-50 — 工具结果渲染时 getClassifierApproval
                //   读取分类器放行规则 (bash matchedRule) → deleteClassifierApproval 清理 (一次性读取).
                //   Java 端无 UI 渲染层, 在结果埋点出口同步读取 + 删除, 让 approval store 不泄漏.
                releaseClassifierApproval(t.call.id());
                // [R32-b8 #3] 工具执行完成, 从 in-progress set 移除 · 对齐 CC
                //   StreamingToolExecutor.ts:525 markToolUseAsComplete +
                //   setInProgressToolUseIDs(prev => { next.delete(toolUseID); return next })
                clearInProgress(t.call.id());
                // [R32-b15 C11] 工具结束同步 interruptible state · 对齐 CC end-of-tool 处理
                updateInterruptibleStateOnEnd(t);
            } catch (Throwable th) {
                t.status = Status.COMPLETED;
                // [R32-#24] 错误分类: AbortException vs 其他 (isAbort 保留供 telemetry 跳过 +
                //   PostToolUseFailure hook isInterrupt 显式入参, CC toolExecution.ts:1694)
                boolean isAbort = th instanceof com.nexusai.application.agent.permission.hook.AbortException;
                // [R32-b15 C15] 用 ToolErrorFormatter.classifyToolError 注入错误分类 ·
                //   [P-25] 删 abort 特判双分支 (旧 "abort" 硬编码), 统一 errorCategory=classifyToolError(th)
                //   (CC toolExecution.ts:150-171 细粒度类名), errorCategory 注入 ToolResult.errorCategory
                //   供 telemetry 区分 (接线保留, 决策 P-25).
                String errorCategory = ToolErrorFormatter.classifyToolError(th);
                t.result = ToolResult.error(t.call.id(),
                    ToolErrorFormatter.formatError(th), errorCategory);
                // [IMP-C2] catch 路径同步 TrackedTool isError/errorCategory
                //   （getResultErrorFlags 配对推导 + PostToolUseFailure hook 入参依赖）
                t.isError = true;
                t.errorCategory = errorCategory;
                erroredCount.incrementAndGet();
                // [工具调用实时推] catch(Throwable) 出口: isError=true + result 已就绪后实时推
                //   tool_result (ToolErrorFormatter.formatError 内容).
                pushToolResultRealtime(t);
                log.error("TOOL threw unhandled (category={}): name={} id={}",
                    errorCategory, t.call.name(), abbreviate(t.call.id(), 24), th);
                // [Session H P2-5] McpAuthError → appState mcp.clients needs-auth 降级 ·
                //   对齐 CC toolExecution.ts:1599-1629 (catch 内先处理 McpAuthError →
                //   setAppState 三条件: 按 name 查不到 → prev; type!=='connected' → prev;
                //   否则替换为 {name, type:'needs-auth', config: existing.config}).
                //   分支不阻断既有 ToolResult.error 流程 (错误分类/telemetry 已在上方完成).
                if (th instanceof McpAuthError mcpAuthErr) {
                    degradeMcpClientToNeedsAuth(ctx, mcpAuthErr);
                }
                // [R32-b12 D-5 P1] PostToolUseFailure analytics 双发 ·
                //   对齐 CC toolExecution.ts:1639-1689 (tengu_tool_use_error +
                //   logOTelEvent('tool_result') success=false).
                //   触发条件: t.isError (PostToolUse hook 失败路径已在上面 emit
                //   HookEvent.toolPostFailure, 这里补全 analytics 双发).
                //   AbortException 不触发 (CC: instanceof AbortError 不触发).
                if (!isAbort) {
                    // [Session I P3-3] 透传 errorCategory 给 emitFailureTelemetry, telemetry
                    //   真实反映错误分类 (Pattern #11: null 也 emit, 不早返).
                    emitFailureTelemetry(t, th, System.currentTimeMillis() - t0, errorCategory);
                }
                // [A2 P0] 失败路径 PostToolUseFailure hook 串联 · 对齐 CC toolExecution.ts:1700-1713.
                //   CC catch 块<b>无条件</b>调 runPostToolUseFailureHooks (含 AbortError → isInterrupt=true
                //   也触发, toolExecution.ts:1694/1707); Java 此前仅 t.isError 分支 (line 1618)
                //   调 executePostToolUseFailure, catch(Throwable) 路径缺失 → tool.execute 抛异常时
                //   onPostToolUseFailure hooks 收不到事件, 与 CC 失败链语义不符.
                //   守卫与成功路径一致 (hookRegistry/ctx/t.tool 非 null, 向后兼容 null fallback);
                //   t.result 已在上方构造 (error ToolResult), 直接作为 hook 入参.
                //   abort 路径 (isAbort) 同样触发 — CC isInterrupt=true 无差别进入失败链.
                if (hookRegistry != null && ctx != null && t.tool != null) {
                    try {
                        // [IMP-HOOKS-S6 E9·CCJ-T6-13] 失败链入参 = 生效 input (executedInput)
                        // [P-25] isInterrupt 显式传 isAbort (CC isInterrupt = error instanceof AbortError,
                        //   toolExecution.ts:1694; 旧 HookRegistry 内部 "abort" 字符串匹配已删)
                        GenericHook.HookResult failureOutcome = hookRegistry.executePostToolUseFailure(
                            t.call.name(),
                            t.executedInput != null ? t.executedInput : t.call.input(),
                            (ToolResult<?>) t.result, ctx, false, isAbort,
                            t.call.id(), t.isError);
                        // [H5] failure 路径 attachment 注入 (CC runPostToolUseFailureHooks yield 4 类 attachment)
                        injectPostToolUseHookAttachments(t, failureOutcome, ctx, "PostToolUseFailure");
                        // [A2 强化] 数据流日志: catch 路径失败事件已发出 (与 line 1601 成功分支呼应)
                        // WHY: catch(Throwable) 是 tool.execute 抛异常的兜底出口, 运维需要能
                        //      grep 到该路径的 errorCategory, 确认 failure hook 链 + analytics 双发.
                        log.info("TOOL failure event emitted, errorCategory={}, toolName={}, toolUseId={}",
                            errorCategory, t.call.name(), abbreviate(t.call.id(), 24));
                    } catch (Throwable hookTh) {
                        // best-effort: hook 链异常不掩盖工具失败本身 (fail loud 但已记日志)
                        log.warn("TOOL PostToolUseFailure hook threw (catch path): name={} id={} err={}",
                            t.call.name(), abbreviate(t.call.id(), 24), hookTh.toString());
                    }
                }
                // [R32-#29] Bash-only sibling abort 触发 · 对齐 CC
                // StreamingToolExecutor.ts:353-362 (isErrorResult + BASH_TOOL_NAME).
                // Bash 错误时设 hasErrored + 触发 siblingAbortController.abort("sibling_error").
                // Bash 有 implicit dependency chain (e.g. mkdir fails → 后续无意义);
                // Read/WebFetch 等独立工具不触发.
                if (t.tool != null
                    && com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME.equals(t.call.name())) {
                    if (!hasErrored) {
                        hasErrored = true;
                        // [B GAP-EXEC-08] 对齐 CC getToolDescription (ts:243-252):
                        //   "${name}(${command|file_path|pattern 截断 40 字符})", 供 sibling synthetic 文案.
                        erroredToolDescription = getToolDescription(t);
                        siblingAbortController.abort("sibling_error");
                        log.info("TOOL Bash errored → siblingAbortController.abort('sibling_error')");
                    }
                }
                // [R32-b8 #3] 错误路径同步清理 in-progress · CC markToolUseAsComplete 不区分 success/error
                clearInProgress(t.call.id());
                // [R32-b15 C11] 错误完成同步 interruptible state · 对齐 CC end-of-tool 处理
                updateInterruptibleStateOnEnd(t);
            } finally {
                // [R32-b12 Fix-v3 P1-2] 不再需要 preToolHookStartTimes.remove(): hook 时长已精确记录
                //   到 t.preToolHookDurationMs 字段 (hook 出口写入), 不依赖延迟到 emit 的 map.remove.
                processQueue();
                // [R32-b15 C8] 唤醒 getCompletedResults / nextEventAvailable 等待者 · 工具完成时
                //   检查是否有 pendingProgress 等待者, 有则用空列表唤醒 (通知有新结果可用).
                java.util.function.Consumer<List<Tool.ToolProgress>> wait =
                    progressAvailableResolve.getAndSet(null);
                if (wait != null) {
                    try {
                        wait.accept(peekPendingProgress());
                    } catch (Throwable th) {
                        log.warn("TOOL finally progressAvailableResolve 唤醒失败: {}", th.toString());
                    }
                }
            }
        }), executor);
    }

    /**
     * [IMP-C D2-A/F3 + reqId MDC 传播] 跨线程 projectRoot + MDC 传播载体 —— 调度线程捕获值注入任务体线程。
     *
     * <p>WHY projectRoot: 工具执行在 fixed-8 池线程（{@code runAsync(..., executor)}），ThreadLocal 不跨线程；
     *   不传播则工具体内 {@link AutoMemPaths#currentSessionProjectRoot()} 读回落值
     *   （CLAUDE_PROJECT_DIR env ?? config home），而非会话绑定 P（M-04：工具/HOOK/定时器
     *   线程 7+ 消费者受影响）。模式对齐 {@link com.nexusai.application.agent.LlmAgentLoop#run()}
     *   capture/restore（:1637/:1645）：调度线程（会话线程）捕获一次，任务体开头 set，
     *   finally restore 外层原值（restore 而非 remove —— 线程池复用防泄漏，null 捕获值
     *   不 set，保持回落语义）。
     *
     * <p>WHY MDC: 与 projectRoot 同因（池线程 ThreadLocal 不跨线程）。不传播则池线程
     *   {@link com.nexusai.common.RequestContext#requestId()}=null → {@link com.nexusai.application.agent.tasks.TaskSystemConfig#isTodoV2Enabled()}
     *   =false → 子代理回落 V1 TodoWrite、父 V2/子 V1 工具集分叉（决策 #65）。对齐
     *   LlmAgentLoop STREAM_EXECUTOR mdcCtx 先例（:4697/:4723-4724/:4743）：调度线程捕获
     *   MDC context map，任务体开头 {@code setContextMap}，finally restore 外层原值
     *   （restore 而非 clear —— 线程池复用防泄漏；null 捕获值不 set）。
     */
    private static Runnable withSessionProjectRoot(String scheduledProjectRoot,
            java.util.Map<String, String> mdcCtx, Runnable task) {
        return () -> {
            String prevProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
            java.util.Map<String, String> prevMdc = org.slf4j.MDC.getCopyOfContextMap();
            try {
                if (scheduledProjectRoot != null && !scheduledProjectRoot.isBlank()) {
                    AutoMemPaths.setCurrentProjectRoot(scheduledProjectRoot);
                }
                if (mdcCtx != null) {
                    org.slf4j.MDC.setContextMap(mdcCtx);
                }
                task.run();
            } finally {
                AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot);
                if (prevMdc != null) {
                    org.slf4j.MDC.setContextMap(prevMdc);
                } else {
                    org.slf4j.MDC.clear();
                }
            }
        };
    }

    /**
     * [P0-3] Map → JsonNode 整体转换 · 用于 AHR.updatedInput 全替换 ·
     * 对齐 CC {@code toolExecution.ts:837} {@code processedInput = result.updatedInput}.
     *
     * <p>WHY 整体转换 (vs 原浅合并): {@link AggregatedHookResult#updatedInput()} 是 Map,
     * Tool.execute 接收 JsonNode. 此方法把 hook 返回的 Map <b>整体</b>作为新 JsonNode
     * 返回 (CC 全替换语义), hook 未声明的字段全部丢失, hook 声明为 null 的字段被 null 化.
     *
     * <p>嵌套 JsonNode 保持原样 (消费者按 JsonNode 处理); 标量值由 ObjectMapper 转 JsonNode.
     *
     * <p><b>[P2-2 审计判定] putPOJO 分支可达性</b>: 当前 hook 唯一调用方是
     * {@code preOutcome.updatedInput()} (AHR.updatedInput, CC 唯一通道), 该字段来源于
     * {@link com.nexusai.application.agent.permission.hook.HookRegistry.JsonNodeToMap}
     * (HookRegistry.java:445-456), 后者只产出 {@code Map<String, JsonNode>}.
     * 因此 else 分支 (putPOJO) 在实际生产路径上<b>不可达</b>.
     *
     * <p>保留 else 分支作为防御性兜底: 若未来 hook 接入返回标量值 / 数组的 Map (非 JsonNode),
     * putPOJO 由 ObjectMapper 默认配置处理, 对 {@code Date} / {@code BigDecimal} / 自定义 POJO
     * 的序列化行为依赖全局 ObjectMapper 设置. 当前 null-safe (ObjectMapper 不抛 NPE).
     *
     * <p><b>实现约束</b>: hook 实现应遵循约定返回 {@code Map<String, JsonNode>}.
     * 若需返回标量值, 自行封装为 JsonNode (e.g. {@code JsonNodeFactory.instance.textNode("foo")}).
     *
     * @param hookInput hook 返回的更新 input (Map&lt;String, Object&gt;) — 约定是
     *                  {@code Map<String, JsonNode>}, 标量值会被 putPOJO 兜底处理
     * @return JsonNode (整体替换视图, 等价于 hook 声明的 input 视图)
     */
    private static JsonNode mapToJsonNode(Map<String, Object> hookInput) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        for (Map.Entry<String, Object> entry : hookInput.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                // CC: hook 显式声明 null → 该字段被 null 化 (允许 hook 删字段)
                result.putNull(key);
            } else if (value instanceof JsonNode jsonNode) {
                result.set(key, jsonNode);
            } else {
                // 防御性兜底分支 (P2-2 审计判定: 当前生产路径不可达, 仅防御)
                result.putPOJO(key, value);
            }
        }
        return result;
    }

    /**
     * [Session H8] JsonNode → Map 整体转换 · {@link Tool#getToolUseSummary} 入参转换.
     *
     * <p>WHY: CC runPreToolUseHooks 传 {@code tool.getToolUseSummary?.(processedInput)}
     * (toolHooks.ts:475), processedInput 是 Record (Java: Map). t.call.input() 是 JsonNode,
     * 需转为 Map 供摘要函数读取. 与 {@link #mapToJsonNode} 互为逆操作, 只处理 object 节点
     * (字段值原样保留 JsonNode, 摘要函数按 JsonNode 读取 — 与 HookRegistry.JsonNodeToMap
     * 产出一致).
     *
     * @param node 工具输入 JsonNode (object 类型)
     * @return Map 视图; 非 object 节点返回 null (摘要函数可安全处理)
     */
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        var iter = node.fields();
        while (iter.hasNext()) {
            var e = iter.next();
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    /**
     * [A2 强化] hookRegistry=null 时 warn 一次 (向后兼容可观察性) ·
     * 对齐 CC 行为: 无 hook 时 hook chain 全部 skip, 但日志层应有可观察证据.
     *
     * <p>并发安全: {@link #nullHookRegistryWarned} 用 {@link java.util.concurrent.atomic.AtomicBoolean#compareAndSet(boolean, boolean)}
     * 保证多个并发 executeAsync 路径下只触发一次 warn.
     */
    private void warnHookRegistryNullOnce() {
        if (nullHookRegistryWarned.compareAndSet(false, true)) {
            log.warn("hookRegistry is null, 跳过所有 hook 阶段 (向后兼容)");
        }
    }

    /**
     * [R32-c-1] PermissionDenied retry hook 触发点 · 对齐 CC
     * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1075-1101}.
     *
     * <p>触发条件: transcript classifier 启用 + Deny 决策来源是
     * {@code PermissionDecisionReason.Classifier(classifier="auto-mode")}
     * （对齐 CC toolExecution.ts:1078 {@code decisionReason.classifier === 'auto-mode'}）.
     * 不满足任一条件时静默早返, 不浪费 hook executor 启动成本.
     *
     * <p>若 hook 任意 result 的 {@link AggregatedHookResult#retry()} 为 true, 复用现有
     * {@link #extendedResultHandler} 通道(下游 LlmAgentLoop.runTools 接的是
     * {@code ExtendedToolResultApplier.apply}) 推送一条
     * {@link com.nexusai.model.session.dto.ChatMessageDto} (role=user, isMeta=true)
     * 到 AgentState.messages — 与 CC 的 {@code resultingMessages.push({ message: createUserMessage({isMeta:true})})}
     * 行为对齐.
     *
     * <p>WHY 不在 StreamingToolExecutor 直接 append: 现有 TrackedTool 生命周期只承载
     * tool_result DTO, 用户消息注入走 LlmAgentLoop.runTools 的 dispatch 路径才完整.
     * 通过 ExtendedToolResult.newMessages() 是已有约定通道, 复用避免 message 重复.
     */
    private void maybeFirePermissionDeniedRetry(TrackedTool t,
                                              ToolUseBlock effectiveCall,
                                              com.nexusai.application.agent.permission.PermissionResult denyResult) {
        if (!transcriptClassifierEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: transcriptClassifierEnabled=false, toolName={}",
                    t.call.name());
            }
            return;
        }
        if (permissionDeniedHookExecutor == null) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: permissionDeniedHookExecutor=null, toolName={}",
                    t.call.name());
            }
            return;
        }
        if (denyResult == null) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: Deny 决策为 null, toolName={}", t.call.name());
            }
            return;
        }
        if (!(denyResult instanceof com.nexusai.application.agent.permission.PermissionResult.Deny deny)) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: Deny 决策不是 PermissionResult.Deny, toolName={}", t.call.name());
            }
            return;
        }
        PermissionDecisionReason denyReason = deny.reason();
        if (!(denyReason instanceof PermissionDecisionReason.Classifier classifier)) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: Deny 原因不是 Classifier 记录, toolName={}", t.call.name());
            }
            return;
        }
        if (!"auto-mode".equals(classifier.classifier())) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 早返: Classifier.classifier={} (期望 auto-mode), toolName={}",
                    classifier.classifier(), t.call.name());
            }
            return;
        }
        // [R32-c-1] 满足全部触发条件 → 同步执行 retry hook chain.
        String classifierReason = classifier.reason();
        String reason = classifierReason != null ? classifierReason : "Permission denied";
        java.util.stream.Stream<AggregatedHookResult> hookResults;
        try {
            hookResults = permissionDeniedHookExecutor.executePermissionDeniedHooks(
                // [reflector-C F2 返工] 对齐 CC processedInput (toolExecution.ts:834-838
                //   hookUpdatedInput 全替换 + :931-932 决策后 input): 传 effectiveCall.input()
                //   (含 PreToolUse hook 全替换结果), 与权限决策 (:1446) 使用同一 input —
                //   不再传原始 t.call.input() (hook 载荷与决策输入不一致的旧行为).
                effectiveCall.name(),
                effectiveCall.id(),
                effectiveCall.input(),
                reason,
                ctx,
                ctx != null && ctx.permissionMode() != null
                    ? ToolPermissionGate.modeToCcString(ctx.permissionMode()) : null,
                // [P-AL-04 REQ-C-C1] 第 7 参 signal 桥接 · 对齐 CC toolExecution.ts:1088
                //   {@code toolUseContext.abortController.signal} — Java 等价 = ctx.abortController()
                //   (AbortController.isCancelled() 即 signal.aborted)。旧实现传 null 导致取消语义
                //   丢失：用户取消后 retry hook 仍完整执行 + 注入 isMeta（open-decisions REQ-C-C1
                //   登记偏差）；现透传取消信号, PermissionDeniedHookExecutor 早返跳过
                //   （对齐 CC executeHooks {@code if (signal?.aborted) return}, hooks.ts:2015-2017）。
                //   ctx == null → null（等价 CC signal undefined, 不检查）。
                ctx != null ? ctx.abortController() : null);
        } catch (Throwable th) {
            log.warn("RETRY hook 调用失败 (best-effort): toolName={} err={}", t.call.name(), th.toString());
            return;
        }
        boolean retry = false;
        java.util.List<AggregatedHookResult> materialized = java.util.Collections.emptyList();
        try {
            materialized = hookResults.toList();
            for (AggregatedHookResult r : materialized) {
                if (r != null && Boolean.TRUE.equals(r.retry())) {
                    retry = true;
                    break;
                }
            }
        } catch (Throwable th) {
            log.warn("RETRY hook 流消费失败 (best-effort): toolName={} err={}",
                t.call.name(), th.toString());
        }
        if (!retry) {
            if (log.isDebugEnabled()) {
                log.debug("RETRY hook 未返回 retry=true (count={}), 不注入 isMeta 消息: toolName={}",
                    materialized.size(), t.call.name());
            }
            return;
        }
        // [R32-c-1] 注入 isMeta user message via 现有 extendedResultHandler 通道.
        if (retryMessageFactory == null || extendedResultHandler == null) {
            log.warn("RETRY hook 触发但 factory/handler 未注入, 跳过 isMeta 注入: toolName={}",
                t.call.name());
            return;
        }
        com.nexusai.model.session.dto.ChatMessageDto retryMsg = retryMessageFactory.createRetryMessage(
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null);
        // [R32-c-1] 现有 extendedResultHandler 协议 = Consumer<AgentToolResult<?>>.
        // A1 退役 ExtendedToolResult 后, newMessages 已折入 ToolResult<T> (Tool.ts:323):
        //   用 ToolResult.errorWithNewMessages 把 isMeta retry message 挂到 newMessages,
        //   LlmAgentLoop.runTools 接的 ToolResultApplier.apply 走 messages.addAll 桥, 复用既有 message 流.
        ToolResult<?> retryContainer = ToolResult.errorWithNewMessages(
            "Permission denied by classifier (auto-mode): " + reason,
            java.util.List.of(retryMsg));
        try {
            extendedResultHandler.accept(retryContainer, t.call.id());
            log.info("RETRY hook 触发 retry=true → 注入 isMeta user message: toolName={} id={}",
                t.call.name(), abbreviate(t.call.id(), 24));
        } catch (Throwable th) {
            log.warn("RETRY isMeta 消息注入失败: toolName={} err={}", t.call.name(), th.toString());
        }
    }

    /**
     * 等所有 in-flight 工具完成, 按 add 顺序返回结果 (CC getRemainingResults:453).
     *
     * <p>循环模式 (CC line 458-485): processQueue → 启动能启动的 → 等 in-flight 完成 → 重复.
     * 直到所有工具都 finished (COMPLETED).
     *
     * <p>[R32-b15 C7] discarded 短路: discard 后已无新工具启动, 所有 in-flight 工具的
     * abort 路径会立即生成 synthetic fallback (CC toolExecution.ts:413-415); 但这里为
     * 防止后台 promise 异常 (e.g. shutdown) 阻塞调用方, discarded 时再次扫描并把
     * 任何 status==QUEUED/EXECUTING 且 result==null 的工具填上 synthetic.
     *
     * <p><b>[DEC-2 / OPD-TOOL-EX-01] List 终端契约构建于惰性 {@link #getRemainingResultsStream()}
     * 之上 (增量实现核心)</b>: 本方法不再用阻塞 {@code allOf(...).join()} 一次性收集,
     * 而是委托惰性 stream 逐条 drain 后 {@code collect} 成 List —— 慢工具不再拖快工具,
     * 快工具结果在慢工具完成前即可产出. 公开契约 (返回 {@code List<ToolResult>}) 保持不变.
     */
    public List<ToolResult> getRemainingResults() {
        // [DEC-2 / OPD-TOOL-EX-01] 委托惰性 getRemainingResultsStream 收集 (增量实现核心).
        //   删除旧阻塞 allOf(...).join() 循环 (慢工具拖快工具); discarded 边界兜底
        //   (QUEUED/EXECUTING result==null → synthetic 'streaming_fallback') 由
        //   drainNextBatch → drainDiscardedRemaining 负责, 与本方法旧实现逐字等价.
        List<ToolResult> results =
            getRemainingResultsStream().collect(java.util.stream.Collectors.toList());
        if (log.isDebugEnabled()) {
            log.debug("TOOL getRemainingResults 委托惰性 stream 完成: results={} discarded={}",
                results.size(), discarded);
        }
        return results;
    }

    /**
     * [IMP-C2] 每组 toolUseId → 是否错误 · 组 2-1 拍板后 ToolResult 删除 isError 字段
     * （对齐 CC ToolResult 4 字段契约），执行器在错误路径（permission deny / validation fail /
     * 异常 catch）置 {@link TrackedTool#isError}。本方法按 add 顺序收集当前已完结工具的错误标志，
     * 供 AgentLoopContext 配对结果-调用时推导 isError（mapper 参数透传，CC is_error 错误路径直构）。
     *
     * @return toolUseId → isError（LinkedHashMap add 序；仅含已完结工具）
     */
    public java.util.Map<String, Boolean> getResultErrorFlags() {
        java.util.Map<String, Boolean> flags = new java.util.LinkedHashMap<>();
        for (TrackedTool t : tools.values()) {
            if (t.status == Status.COMPLETED && t.call != null) {
                flags.put(t.call.id(), t.isError);
            }
        }
        return flags;
    }

    /**
     * [Session F ALI-1] 惰性版 getRemainingResults · 对齐 CC
     * {@code Open-ClaudeCode/src/services/tools/StreamingToolExecutor.ts:453-490}
     * getRemainingResults() AsyncGenerator 逐条 yield 语义.
     *
     * <p><b>WHY (ALI-1)</b>: CC 的 getRemainingResults 是 AsyncGenerator, 每个工具完成即
     * yield (query.ts:1380-1408 for-await 逐条消费 → UI 流式可见); Java 阻塞版
     * {@link #getRemainingResults()} 一次性收集全部, 慢工具拖住快工具结果. 本方法提供
     * 真惰性入口: {@link java.util.Spliterator#tryAdvance} 逐条拉取, 每条结果在所属工具
     * 完成时立即返回, 未完成时阻塞等待下一个完成事件 (CC generator 循环语义).
     *
     * <p><b>语义对齐</b>:
     * <ul>
     *   <li>顺序 = add 顺序 ({@link #getCompletedResults()} 按 LinkedHashMap 迭代)</li>
     *   <li>discarded 短路: 不等 in-flight, 直接 yield synthetic streaming_fallback
     *       (CC ts:454-456 + R32-b15 C7)</li>
     *   <li>与 {@link #getRemainingResults()} 互斥消费: 同一 executor 实例上二选一
     *       (两者都经 drainedIds 防重, 混用会丢结果 — 与 CC 双消费模式一致:
     *       query.ts:851 getCompletedResults 流中消费 vs :1380 getRemainingResults)</li>
     * </ul>
     *
     * @return 惰性 Stream, 每条结果在工具完成时 yield; 全部完成后终止
     */
    public java.util.stream.Stream<ToolResult> getRemainingResultsStream() {
        return java.util.stream.StreamSupport.stream(new java.util.Spliterator<ToolResult>() {
            /** 当前批 (drainNextBatch 一次性拉取的已完成结果), 逐条消费. */
            private final java.util.List<ToolResult> batch = new java.util.ArrayList<>();
            private int batchIdx = 0;

            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super ToolResult> action) {
                while (batchIdx >= batch.size()) {
                    // 当前批耗尽 → 取下一批 (阻塞直到有完成结果或全部完成)
                    batch.clear();
                    batchIdx = 0;
                    java.util.List<ToolResult> next = drainNextBatch();
                    if (next.isEmpty()) {
                        return false; // 全部完成, 流终止
                    }
                    batch.addAll(next);
                }
                action.accept(batch.get(batchIdx++));
                return true;
            }

            @Override
            public java.util.Spliterator<ToolResult> trySplit() {
                return null; // 顺序流, 不并行拆分 (tools map 非线程安全 for 并发迭代)
            }

            @Override
            public long estimateSize() {
                return Long.MAX_VALUE; // 完成数未知
            }

            @Override
            public int characteristics() {
                return java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL;
            }
        }, false);
    }

    /**
     * [Session F ALI-1] 拉取下一批已完成结果 · 对齐 CC getRemainingResults 循环体
     * (StreamingToolExecutor.ts:458-485): {@code while hasUnfinishedTools: processQueue →
     * yield completed → 无产出时等 in-flight 完成}.
     *
     * @return 下一批已完成结果; 空列表 = 全部完成 (流终止)
     */
    private java.util.List<ToolResult> drainNextBatch() {
        if (!discarded) {
            while (hasUnfinishedTools()) {
                processQueue();
                java.util.List<ToolResult> ready = getCompletedResults();
                if (!ready.isEmpty()) {
                    return ready;
                }
                // 无完成结果 → 等**任一** in-flight 完成 (CC StreamingToolExecutor.ts:482-483:
                //   Promise.race(executingPromises, progressPromise) — 任一完成即唤醒重查,
                //   快工具结果不会被慢工具拖住). 阻塞版 getRemainingResults 用 allOf (等全部)
                //   是终端收集语义; 惰性版必须 race, 否则一批返回破坏逐条 yield.
                java.util.List<CompletableFuture<Void>> all = new java.util.ArrayList<>();
                for (TrackedTool t : tools.values()) {
                    if (t.status == Status.EXECUTING && t.promise != null) {
                        all.add(t.promise);
                    }
                }
                if (!all.isEmpty()) {
                    try {
                        CompletableFuture.anyOf(all.toArray(new CompletableFuture[0])).join();
                    } catch (Throwable th) {
                        // discarded + abort path → 抛 CompletionException (cause=AbortException)
                        // 此为预期, 不上报. 详见 executeAsync catch 分支 (同阻塞版).
                        if (!discarded) {
                            log.warn("TOOL getRemainingResultsStream join 异常: {}", th.toString());
                        }
                    }
                }
            }
            processQueue(); // 最后再扫一次 (有 unsafe 完成时释放), 同阻塞版
        }
        // [DEC-2 / OPD-TOOL-EX-01] discarded 最终 drain 等价兜底: 阻塞版 getRemainingResults
        //   在 discarded 时对 status==QUEUED/EXECUTING 且 result==null 的工具补 synthetic
        //   'streaming_fallback' (createSyntheticErrorMessage). 委托后 getCompletedResults()
        //   只返回 COMPLETED 工具, 会漏掉 discard 时仍 QUEUED/EXECUTING 的工具, 故必须
        //   走 drainDiscardedRemaining 补齐 (按 add 序, 跳过已 drained, 不经过
        //   isConcurrencySafe break, 与阻塞版逐字等价).
        if (discarded) {
            return drainDiscardedRemaining();
        }
        return getCompletedResults();
    }

    /**
     * [DEC-2 / OPD-TOOL-EX-01] discarded 时最终 drain · 与旧阻塞版 getRemainingResults
     * 收集循环逐字等价 (CC StreamingToolExecutor.ts:454-456 discarded 后 generator 不再
     * yield, 消费者得到 synthetic 'streaming_fallback').
     *
     * <p><b>WHY</b>: 阻塞版在 discarded 时直接遍历 tools 收集: result==null → synthetic
     * 'streaming_fallback' (createSyntheticErrorMessage), 否则取 t.result 本身. 而惰性
     * {@link #getCompletedResults()} 只 yield status==COMPLETED 的工具, 会漏掉 discard
     * 时仍 QUEUED/EXECUTING (abort 尚未结算) 的工具. 本方法在 add 序下一次补齐, 保证
     * {@link #getRemainingResults()} 委托后与旧阻塞版结果全量等价 (含 discarded 边界).
     *
     * @return 剩余工具结果 (add 序, 已 drain 的跳过)
     */
    private java.util.List<ToolResult> drainDiscardedRemaining() {
        java.util.List<ToolResult> drained = new java.util.ArrayList<>();
        for (TrackedTool t : tools.values()) {
            if (drainedIds.contains(t.call.id())) {
                continue; // 已 yield 的工具不重复
            }
            if (t.result == null) {
                // 阻塞版 discarded 分支: result==null → synthetic streaming_fallback
                // [IMP-C2 返工] 合成错误回写 t.result + 同步标记 isError/COMPLETED
                //   （getResultErrorFlags 配对推导依赖 status==COMPLETED + isError）
                ToolResult synthetic = createSyntheticErrorMessage(t.call.id(), "streaming_fallback");
                t.result = synthetic;
                t.isError = true;
                t.status = Status.COMPLETED;
                drained.add(synthetic);
            } else if (t.result instanceof ToolResult<?> tr) {
                drained.add(tr);
            } else {
                // 未知类型 → 防御性 fallback (sealed 已收窄, 理论不可达)
                drained.add(ToolResult.error(t.call.id(), "unknown result type"));
            }
            drainedIds.add(t.call.id());
        }
        if (log.isDebugEnabled()) {
            log.debug("TOOL drainDiscardedRemaining: 补齐 discarded 残留结果={}",
                drained.size());
        }
        return drained;
    }

    /**
     * [R32-b15 C8+C10] 非阻塞 drain · 对齐 CC StreamingToolExecutor.ts:407-440
     * getCompletedResults() — 立即返回当前已完成的 (COMPLETED 状态) 工具结果,
     * 不等待 in-flight. 重复调用直到返回空列表表示所有工具已 drain.
     *
     * <p>设计意图 (CC toolExecution.ts:407): 允许 LlmAgentLoop 流式消费:
     *   <ol>
     *     <li>stream 期间 stream-emit 已完成的工具结果 (UI 立即看到)</li>
     *     <li>yield progress 事件 (进度回调)</li>
     *     <li>无 in-flight 时退出流 (loop end)</li>
     *   </ol>
     *
     * <p>Java 端实现:
     *   <ul>
     *     <li>每次调用返回当前 status==COMPLETED 的工具结果 (按 add 顺序)</li>
     *     <li>内部维护 drainedIds set, 已返回的不再重复返回</li>
     *     <li>仅返回 status==COMPLETED 的; status==QUEUED/EXECUTING 的等下次</li>
     *   </ul>
     *
     * <p>返回类型是 List&lt;{@link ToolResult}&gt; 简化 (CC 返回 MessageUpdate union 含
     * progress + result + assistant message; Java 端本期只 result, progress 走
     * per-call {@code onProgress} 回调).
     *
     * @return 当前已完成但未 drain 的工具结果列表 (按 add 顺序); 空列表表示本次无新增
     */
    public List<ToolResult> getCompletedResults() {
        List<ToolResult> drained = new ArrayList<>();
        for (TrackedTool t : tools.values()) {
            if (t.status == Status.COMPLETED && !drainedIds.contains(t.call.id())) {
                if (t.result == null) {
                    drained.add(ToolResult.error(t.call.id(),
                        discarded ? "streaming_fallback" : "no result produced"));
                } else if (t.result instanceof ToolResult<?> tr) {
                    // A1: ToolResult 在 dispatch 时已被 extendedResultHandler 处理, 返回其本身 (无 base 解包)
                    drained.add(tr);
                } else {
                    // 未知类型 → 防御性 fallback (sealed 已收窄, 理论不可达)
                    drained.add(ToolResult.error(t.call.id(), "unknown result type"));
                }
                drainedIds.add(t.call.id());
            } else if (t.status == Status.EXECUTING && !t.isConcurrencySafe) {
                // [Session F ALI-1] CC 保序 break (StreamingToolExecutor.ts:436-438):
                //   非并发安全工具执行中 → 其后的工具结果不越过它 yield (保持 add 序,
                //   避免 UI 看到后序结果先于前序 unsafe 工具). Java 串行队列下此状态
                //   结构不可达 (unsafe executing ⟹ 后续全 QUEUED, canExecuteTool 守门),
                //   为防御性对齐 (abort/discard 边角), 与 CC 逐字一致.
                break;
            }
        }
        return drained;
    }

    /**
     * [R32-b15 C8] 等待下一个 progress 或结果事件 · 对齐 CC StreamingToolExecutor.ts:407
     * progressAvailableResolve: getCompletedResults() 阻塞等待时被入队 progress / 完成结果
     * 唤醒. 本 Java 端简化为 {@link java.util.concurrent.CompletableFuture} 风格 ——
     * 返回的 future 在下一个 progress 入队或新工具完成时立即完成.
     *
     * <p>消费者 (LlmAgentLoop 等) 用法:
     * <pre>
     *   CompletableFuture&lt;Void&gt; waiter = streamingExec.nextEventAvailable();
     *   List&lt;ToolResult&gt; newResults = streamingExec.getCompletedResults();
     *   if (!newResults.isEmpty()) { stream-emit; }
     *   waiter.get(timeoutMs, MILLISECONDS);  // 等待下一批
     * </pre>
     *
     * @return CompletableFuture, 在下一个 progress 事件或新工具 COMPLETED 时完成;
     *         若 discarded 或全部完成, future 立即完成 (返回 null)
     */
    public CompletableFuture<Void> nextEventAvailable() {
        if (discarded || !hasUnfinishedTools()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        java.util.function.Consumer<List<Tool.ToolProgress>> cb = progressList -> {
            if (!f.isDone()) f.complete(null);
        };
        // 仅当当前没有等待者时才注入 (一消费者一等待)
        if (progressAvailableResolve.compareAndSet(null, cb)) {
            // 若在 CAS 与此刻之间已经有新 progress 入队 (唤醒), 则立即完成
            if (!pendingProgress.isEmpty()) {
                if (progressAvailableResolve.compareAndSet(cb, null)) {
                    f.complete(null);
                }
            }
        } else {
            // 已有等待者 → 直接复用其 future, 此处简化: 立即完成 (避免多消费者复杂)
            f.complete(null);
        }
        return f;
    }

    /**
     * [R32-b15 C8] 把 progress 事件入队 · 由 executeAsync 内部 onProgress wrapper 调用.
     * 对齐 CC StreamingToolExecutor.ts:407 pendingProgress.push(progressMessage).
     *
     * <p>同步入队 (LinkedBlockingQueue 保证线程安全); 唤醒当前等待者.
     */
    void enqueueProgress(Tool.ToolProgress progress) {
        if (progress == null) return;
        pendingProgress.add(progress);
        java.util.function.Consumer<List<Tool.ToolProgress>> wait =
            progressAvailableResolve.getAndSet(null);
        if (wait != null) {
            try {
                // pendingProgress 是 LinkedBlockingQueue; Consumer 期望 List → 用 snapshot 转换
                wait.accept(new java.util.ArrayList<>(pendingProgress));
            } catch (Throwable th) {
                log.warn("TOOL progressAvailableResolve 唤醒失败: {}", th.toString());
            }
        }
    }

    /**
     * [R32-b15 C8] 取出当前所有 pending progress 事件 · 对齐 CC pendingProgress.shift().
     * 不清空, 仅 snapshot, 消费者负责清空.
     */
    public List<Tool.ToolProgress> peekPendingProgress() {
        return new java.util.ArrayList<>(pendingProgress);
    }

    /**
     * [R32-b15 C8] 清空 pending progress 队列 · 由消费者在 peekPendingProgress 后调用.
     */
    public void clearPendingProgress() {
        pendingProgress.clear();
    }

    private boolean hasUnfinishedTools() {
        for (TrackedTool t : tools.values()) {
            if (t.status == Status.QUEUED || t.status == Status.EXECUTING) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>[IMP-C4 DC-TR-A3-1]</b> 兼容老 API {@code executeAll()} / {@code pendingCount()} 已删除 ——
     * CC StreamingToolExecutor.ts 无这些方法（EV-A3-079）；全仓 0 调用方（grep 复验）；pendingCount
     * 返回 size 语义可疑。主链消费 {@link #getRemainingResults()} / {@link #getCompletedResults()}，
     * 无需兼容壳。{@link #size()} 保留 —— AgentLoopContext:1520/1652 真实消费（runTools 日志 + fork
     * 守卫），非死 API（06-deletion-manifest DC-TR-A3-1 仅登记 executeAll/pendingCount）。
     */

    public void discard() {
        discarded = true;
        // [R32-b8 #3 P0-1 校正] discard 时清空 in-progress set · 对齐 CC REPL.tsx:430
        //   setInProgressToolUseIDs?.(prev => (prev.size > 0 ? new Set() : prev)).
        //   P0-1 校正: 用 Function<Set<String>, Set<String>> 严格对齐 CC Tool.ts:227,
        //   先 mutate 内部 set (clear), 再调 Function.apply(empty) 返回 immutable snapshot.
        //   null-safe: ctx == null 或 Function == null 时跳过 (noop fallback).
        if (ctx != null && ctx.inProgressToolUseIDs() != null) {
            try {
                inProgressToolUseIDs.clear();
                Set<String> snapshot = ctx.inProgressToolUseIDs().apply(java.util.Set.of());
                if (log.isDebugEnabled()) {
                    log.debug("TOOL discard: in-progress set 已清空, snapshotSize={}",
                        snapshot != null ? snapshot.size() : -1);
                }
            } catch (Throwable th) {
                // Function 抛异常 → best-effort, 不挂掉 discard 流程
                log.warn("TOOL discard inProgressToolUseIDs.apply 失败: {}", th.toString());
            }
        }
        // [R32-b15 C7] discard 中止所有兄弟/工具 controller · 对齐 CC toolExecution.ts:64-71
        //   + 210-230: discard() 触发 siblingAbortController.abort("streaming_fallback"),
        //   级联杀掉所有 per-tool child + 所有 EXECUTING 工具的后台 promise.
        //   在 catch (Throwable) 路径上, 各工具 promise 会看到 isCancelled=true → 进入
        //   getAbortReason() → 返回 "streaming_fallback" → createSyntheticErrorMessage 生成 synthetic.
        try {
            siblingAbortController.abort("streaming_fallback");
            log.info("TOOL discard: siblingAbortController.abort('streaming_fallback')");
        } catch (Throwable th) {
            log.warn("TOOL discard siblingAbortController.abort 失败: {}", th.toString());
        }
        // [R32-b15 C7] 唤醒 getCompletedResults 等待者 · discard 后即使无 progress / result 也
        //   让消费者能从 await 中返回 (CC StreamingToolExecutor.ts:454-456: discard 后
        //   result generator 应停止 yield). 这里通过清空 progressAvailableResolve 让
        //   下次 await 立即返回空 (而非永久阻塞).
        java.util.function.Consumer<List<Tool.ToolProgress>> wait = progressAvailableResolve.getAndSet(null);
        if (wait != null) {
            try {
                wait.accept(java.util.List.of());
            } catch (Throwable th) {
                log.warn("TOOL discard progressAvailableResolve.accept 失败: {}", th.toString());
            }
        }
    }

    /**
     * [R32-b8 #3 P0-1 校正] 把 toolUseId 加入 in-progress 追踪 · 对齐 CC
     * {@code StreamingToolExecutor.ts:267} executeTool 入口触发
     * + {@code toolOrchestration.ts:127/160} (sequential/concurrent start).
     *
     * <p>P0-1 校正: 用 {@link Function}{@code <Set<String>, Set<String>>} 严格对齐
     * CC {@code Tool.ts:227 setInProgressToolUseIDs} 函数式 updater 模式.
     * Function 接收 prev Set (内部 in-progress 状态), 返回 immutable snapshot
     * ({@code Collections.unmodifiableSet(new HashSet<>(prev))}).
     * 调用方 (LlmAgentLoop) 实现此 Function 来维护自己的 in-progress state.
     *
     * <p>本类维护一个内部 mutable set (this.inProgressToolUseIDs), 每次 fire/clear
     * 先 mutate 内部状态, 再调用 Function.apply() 接收 immutable snapshot.
     * 调用方可选择读取 snapshot 用于 UI / 审计.
     *
     * <p>null-safe: {@code ctx == null} 或 {@code inProgressToolUseIDs == null}
     * 时跳过 (default noop Function {@code s -> Set.of()}).
     */
    private void fireInProgress(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) return;
        if (ctx == null) return;
        Function<Set<String>, Set<String>> hook = ctx.inProgressToolUseIDs();
        if (hook == null) return;
        try {
            // 1. mutate 内部状态 (add ID)
            inProgressToolUseIDs.add(toolUseId);
            // 2. 调 Function.apply(currentState) 接收 immutable snapshot
            //    (对齐 CC setInProgressToolUseIDs(prev => new Set(prev).add(id)))
            Set<String> snapshot = hook.apply(Collections.unmodifiableSet(
                new HashSet<>(inProgressToolUseIDs)));
            // 3. snapshot 由调用方使用 (此处仅记录日志, 防止 IDE 报 unused)
            if (log.isDebugEnabled()) {
                log.debug("TOOL in-progress add: id={} snapshotSize={}",
                    abbreviate(toolUseId, 24),
                    snapshot != null ? snapshot.size() : -1);
            }
        } catch (Throwable th) {
            // Function 抛异常 → best-effort, 不挂掉 add 流程
            log.warn("TOOL inProgressToolUseIDs.apply (add) 失败: id={} err={}",
                abbreviate(toolUseId, 24), th.toString());
        }
    }

    /**
     * [R32-b8 #3 P0-1 校正] 把 toolUseId 从 in-progress 追踪中移除 · 对齐 CC
     * {@code StreamingToolExecutor.ts:525} markToolUseAsComplete
     * + {@code toolOrchestration.ts:148/173/183} (sequential/concurrent end).
     *
     * <p>P0-1 校正: 用 {@link Function}{@code <Set<String>, Set<String>>} 严格对齐
     * CC {@code Tool.ts:227 setInProgressToolUseIDs} 函数式 updater 模式.
     * Function 接收 prev Set, 返回 immutable snapshot.
     * 行为对齐 CC {@code setInProgressToolUseIDs(prev => { next.delete(toolUseID); return next })}.
     *
     * <p>null-safe: {@code ctx == null} 或 {@code inProgressToolUseIDs == null}
     * 时跳过 (default noop Function).
     */
    private void clearInProgress(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) return;
        if (ctx == null) return;
        Function<Set<String>, Set<String>> hook = ctx.inProgressToolUseIDs();
        if (hook == null) return;
        try {
            // 1. mutate 内部状态 (remove ID)
            inProgressToolUseIDs.remove(toolUseId);
            // 2. 调 Function.apply(currentState) 接收 immutable snapshot
            //    (对齐 CC setInProgressToolUseIDs(prev => { next.delete(id); return next }))
            Set<String> snapshot = hook.apply(Collections.unmodifiableSet(
                new HashSet<>(inProgressToolUseIDs)));
            if (log.isDebugEnabled()) {
                log.debug("TOOL in-progress remove: id={} snapshotSize={}",
                    abbreviate(toolUseId, 24),
                    snapshot != null ? snapshot.size() : -1);
            }
        } catch (Throwable th) {
            // Function 抛异常 → best-effort, 不挂掉 execute 流程
            log.warn("TOOL inProgressToolUseIDs.apply (remove) 失败: id={} err={}",
                abbreviate(toolUseId, 24), th.toString());
        }
    }
    public int erroredCount() { return erroredCount.get(); }
    /** 当前已排队/执行中的工具数 · AgentLoopContext:1520/1652 消费（runTools 日志 + fork 守卫）。 */
    public int size() { return tools.size(); }

    private static ExecutorService defaultExecutor() {
        return Executors.newFixedThreadPool(
            Math.min(8, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "tool-exec-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + s.length() + ")";
    }

    /**
     * [R32-b2] ToolResult → JsonNode 转换辅助方法 · 用于 PostToolUseFailure event 构造.
     *
     * <p>WHY: HookEvent.toolPostFailure 接收 JsonNode 类型 result 字段,
     * 但 streaming 路径持有 ToolResult 类型. 此方法把 ToolResult 序列化为
     * {"toolUseId": ..., "content": ..., "isError": true} JSON 节点.
     *
     * @param result ToolResult (failure path)
     * @return JsonNode 表示(null → null)
     */
    private static JsonNode resultToJsonNode(ToolResult result, String toolUseId, boolean isError) {
        if (result == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("toolUseId", toolUseId);
            node.put("content", result.data() instanceof String dc ? dc : String.valueOf(result.data()));
            node.put("isError", isError);
            return node;
        } catch (Exception e) {
            log.warn("TOOL resultToJsonNode failed: id={} err={}", toolUseId, e.toString());
            return null;
        }
    }

    private enum Status {
        QUEUED, EXECUTING, COMPLETED,
        /**
         * [R32-b15 C10] 已 yield 给消费者 (getCompletedResults 返回) · 对齐 CC
         * StreamingToolExecutor.ts:407-440 YIELDED 状态. Java 端用 drainedIds 简化: 已
         * 返回的工具不会重复 yield, status 仍为 COMPLETED (YIELDED 与 COMPLETED 不冲突).
         * 此枚举保留为扩展位, 当前逻辑不主动设置.
         */
        YIELDED
    }

    /**
     * 阻断消息解析 · 对齐 CC toolExecution.ts:1023-1027 deny 路径错误文案:
     * <pre>
     *   let errorMessage = permissionDecision.message
     *   // Only use generic "Execution stopped" message if we don't have a detailed hook message
     *   if (shouldPreventContinuation && !errorMessage) {
     *     errorMessage = `Execution stopped by PreToolUse hook${stopReason ? `: ${stopReason}` : ''}`
     *   }
     * </pre>
     *
     * <p>Java 端 {@link PermissionResult.Deny} record compact constructor 强制
     * message 非空 (PermissionResult.java:155-161), 故兜底分支在生产路径不可达
     * (completion-report R13 登记: "Java Deny 强制非空 message → CC :1025-1027
     * 兜底不可达"). 本 helper 保持 CC 防御语义并支持独立单测 (EX-B R13).
     *
     * @param denyMessage        阻断消息 (CC {@code permissionDecision.message};
     *                           hook deny 恒非空, 兜底仅防御性可达)
     * @param preventContinuation hook 是否要求停止后续轮次
     *                            ({@link AggregatedHookResult#preventContinuation()},
     *                            CC {@code shouldPreventContinuation})
     * @param stopReason          停止原因 ({@link AggregatedHookResult#stopReason()},
     *                            CC {@code stopReason}; 可 null)
     * @return tool_result content (原文直出; 空消息 + preventContinuation → CC 兜底文案)
     */
    static String denyBlockingMessage(String denyMessage, boolean preventContinuation, String stopReason) {
        if (preventContinuation && (denyMessage == null || denyMessage.isBlank())) {
            boolean hasStopReason = stopReason != null && !stopReason.isBlank();
            return "Execution stopped by PreToolUse hook" + (hasStopReason ? ": " + stopReason : "");
        }
        return denyMessage;
    }

    /**
     * [Session H5 + H8 + hook-message 普通消息通道] PreToolUse hook 结算 · 对齐 CC
     * toolHooks.ts:478-603 runPreToolUseHooks yield.
     *
     * <p><b>message 改普通消息通道</b>: CC toolHooks.ts:478-480 {@code result.message} →
     * {@code yield {type:'message', message:{message: result.message}}} → toolExecution.ts:815
     * {@code resultingMessages.push} → 普通 user 消息 (与 tool_result 同批、一次性). Java 端
     * {@code hook_user_message} 类型即 CC 普通 hook message 的载体, 不再 appendAttachment
     * 常驻, 而是结算为 {@link ChatMessageDto}(role=user, isMeta=false) 暂存到
     * {@link TrackedTool#pendingHookUserMessages}, 由 dispatch 合并进 t.result.newMessages.
     *
     * <p>仍走 attachment 的: hook_additional_context (additionalContexts) + hook_cancelled (abort)
     * + 真实 attachment 消息 (hook_success 等, OD-14 透传通道) — CC 本就是 attachment, 保持.
     * PreToolUse 不 yield hook_blocking_error/hook_stopped_continuation
     * (blockingError 走 deny permission 通道, 非 attachment).
     */
    private void injectPreToolUseHookAttachments(TrackedTool t, AggregatedHookResult preOutcome, ToolUseContext ctx) {
        if (agentStateRef == null || preOutcome == null) return;
        String hookName = "PreToolUse:" + t.call.name();
        String toolUseID = t.call.id();
        // CC toolHooks.ts:478-480 hook message → resultingMessages → 普通 user 消息 ([H8] 修复
        //   S9 遗留 B-R5 "只写不读" 死字段: AHR.message() 之前 0 消费端, 现在经普通消息通道结算)
        // [IMPL-07 D3-3/OD-14] AHR.message 统一 AttachmentMessageDto 通道; 多 hook 第 2..N 个
        //   message 全保留 (CC 逐结果 yield → resultingMessages 全 push). hook_user_message 类型
        //   = CC 普通 hook message → 结算为 user ChatMessageDto(isMeta=false); 其他真实
        //   attachment 类型 (hook_success 等) 保持 appendAttachment.
        if (preOutcome.message() != null) {
            for (AttachmentMessageDto msg : preOutcome.message()) {
                if (msg != null && "hook_user_message".equals(msg.type())) {
                    String content = msg.content();
                    if (content != null && !content.isBlank()) {
                        t.pendingHookUserMessages.add(plainHookUserMessage(content));
                    }
                } else if (msg != null) {
                    agentStateRef.appendAttachment(msg);
                }
            }
        }
        // CC toolHooks.ts:582-603 hook_cancelled (abortController.signal.aborted)
        if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
            agentStateRef.appendAttachment(AttachmentMessageDto.hookCancelled(hookName, toolUseID, "PreToolUse"));
        }
        // CC toolHooks.ts:566-578 hook_additional_context
        if (preOutcome.additionalContexts() != null && !preOutcome.additionalContexts().isEmpty()) {
            agentStateRef.appendAttachment(AttachmentMessageDto.hookAdditionalContext(
                hookName, toolUseID, "PreToolUse", preOutcome.additionalContexts()));
        }
        // [DEL-WF1-TY-02 v4 实施] hook_system_message · 对齐 CC hooks.ts:2769-2780
        //   (executeHooks 逐结果 yield systemMessage → hook_system_message attachment; N 条 → N 附件).
        //   AHR.systemMessages 聚合字段已删除; systemMessage 在 HookResult→AHR 转换边界经
        //   AggregatedHookResult.foldSystemMessages 就地折叠为 hook_system_message AttachmentMessageDto
        //   并入 AHR.message 通道, 由上方 message 循环逐条 appendAttachment (非 hook_user_message
        //   → appendAttachment 分支), 旧 first-non-null 只注入第 1 条的问题由逐条折叠消除.
    }

    /**
     * hook plain message → 普通 user ChatMessageDto (isMeta=false) · 对齐 CC
     * {@code createUserMessage({content: result.message})} (toolHooks.ts:478-480).
     *
     * <p>author 用 "system" (与 AgentLoopContext.metaUserMessage 一致, hook 消息非真实用户输入);
     * isMeta=false 使消息进入对话历史 (普通消息通道), 而非 system 提示 — CC hook message
     * 是普通 user 消息 (query.ts:1395 filter type==='user').
     *
     * @param content hook 返回的用户可见消息原文 (CC original: result.message)
     */
    private static ChatMessageDto plainHookUserMessage(String content) {
        return new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), null, com.nexusai.model.session.dto.Role.user, "system",
            content, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of(), null, false);
    }

    /**
     * [Session H5] PostToolUse/PostToolUseFailure hook attachment 注入 · 对齐 CC toolHooks.ts:79-185
     * (runPostToolUseHooks 5 类) + toolHooks.ts:234-313 (runPostToolUseFailureHooks 4 类, 无 hook_stopped_continuation).
     *
     * @param hookEvent "PostToolUse" 或 "PostToolUseFailure" (区分是否注入 hook_stopped_continuation)
     */
    private void injectPostToolUseHookAttachments(TrackedTool t, GenericHook.HookResult outcome,
                                                  ToolUseContext ctx, String hookEvent) {
        if (agentStateRef == null || outcome == null) return;
        String hookName = hookEvent + ":" + t.call.name();
        String toolUseID = t.call.id();
        // CC toolHooks.ts:68-88 / :224-243 hook_cancelled (abort)
        if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
            agentStateRef.appendAttachment(AttachmentMessageDto.hookCancelled(hookName, toolUseID, hookEvent));
        }
        // [H3 v3 修复] Gap 1 (H3-GAP-1 残留): outcome.message() 消费 · 对齐 CC
        //   toolHooks.ts:89-108 runPostToolUseHooks `if (result.message ...) yield { message }`.
        //   processHookJSONOutput (hooks.ts:710-736) 生成的 hook_success/hook_blocking_error
        //   attachment 必须注入 LLM 上下文 (AgentState.attachments → messagesForLlm).
        // [IMP-DA-01 TY-01] 逐条注入全部附件 (含 hook_blocking_error) · allBlockingErrors 字段删除后,
        //   每 blocking result 的 hook_blocking_error 附件已在 HookRegistry 折叠层并入 messages
        //   (processHookJSONOutput 生成 / exit-2 / programmatic 无 message 时合成), 故此处不再跳过
        //   hook_blocking_error (#31301 双显示规避由"折叠层单载体"取代; CC 逐 blocking 产 N 附件
        //   toolHooks.ts:105-115 / :257-267).
        // [H-WF5a-02 折叠链项1] outcome.message 工具链路径承载 List<Object> (executePostToolUse/
        //   executePostToolUseFailure 逐 result 收集, 5-W3-4 message 全保留); 通用事件路径
        //   (executeEvent 折叠) 仍单值 AttachmentMessageDto 亦兼容.
        if (outcome.message() instanceof java.util.List<?> msgList) {
            for (Object m : msgList) {
                if (m instanceof AttachmentMessageDto att) {
                    agentStateRef.appendAttachment(att);
                }
            }
        } else if (outcome.message() instanceof AttachmentMessageDto msg) {
            agentStateRef.appendAttachment(msg);
        }
        // [IMP-HOOKS-S6 CCJ-T6-19 + H-WF5a-02 折叠链项3] hook_system_message · 对齐 CC hooks.ts:2769-2780
        //   (PostToolUse/PostToolUseFailure 链同注入, N 条 → N 附件; LLM API 上下文排除已由
        //   AgentLoopContext 既有逻辑覆盖).
        if (outcome.systemMessages() != null) {
            for (String sysMsg : outcome.systemMessages()) {
                if (sysMsg != null && !sysMsg.isBlank()) {
                    agentStateRef.appendAttachment(AttachmentMessageDto.hookSystemMessage(
                        hookName, toolUseID, hookEvent, sysMsg));
                }
            }
        }
        // CC toolHooks.ts:118-130 hook_stopped_continuation (仅 PostToolUse, PostToolUseFailure 无此分支)
        if (outcome.preventContinuation() && !"PostToolUseFailure".equals(hookEvent)) {
            agentStateRef.appendAttachment(AttachmentMessageDto.hookStoppedContinuation(
                hookName, toolUseID, hookEvent, outcome.stopReason()));
        }
        // CC toolHooks.ts:133-143 / :270-280 hook_additional_context
        // [H-WF5a-02 折叠链项2] HookResult.additionalContexts 已是 List<String> → 逐条注入 N 附件
        //   (CC 逐 result 单元素数组 → N hook_additional_context 附件, 旧仅首条).
        if (outcome.additionalContexts() != null) {
            for (String additionalCtx : outcome.additionalContexts()) {
                if (additionalCtx != null && !additionalCtx.isBlank()) {
                    agentStateRef.appendAttachment(AttachmentMessageDto.hookAdditionalContext(
                        hookName, toolUseID, hookEvent, List.of(additionalCtx)));
                }
            }
        }
    }

    private static final class TrackedTool {
        ToolUseBlock call;
        Consumer<Tool.ToolProgress> onProgress;
        Tool tool;
        boolean isConcurrencySafe;
        Status status = Status.QUEUED;
        CompletableFuture<Void> promise;
        AgentToolResult result;
        /**
         * [GAP-R1] 调度侧捕获的 in-process teammate 上下文 · 对齐 CC AsyncLocalStorage
         * 跨异步延续自动传播（inProcessRunner.ts:1160 runWithTeammateContext 包 runAgent）。
         *
         * <p>WHY: {@link TeammateContext} 是 ThreadLocal-backed，而工具体在
         * {@link #executeAsync} 的 {@code CompletableFuture.runAsync(..., executor)} 回调
         * （池线程）执行 —— ThreadLocal 不跨线程，需在 <b>add() 调度线程</b>（runner 线程，
         * 已被 SpawnInProcess 包 runWithTeammateContext）捕获，再在工具执行线程恢复，
         * SubagentTool.isTeammate/isInProcessTeammate 守卫才在 teammate 场景命中。
         *
         * <p>null = 主会话/普通 subagent 路径，不包装 → 行为零变化（不破坏主会话）。
         */
        TeammateContext capturedTeammateContext;
        /**
         * [R32-b15 Stage 2 C5] 父 assistant message lineage 句柄 · 由
         * {@link #add(ToolUseBlock, ToolParent, Consumer)} 注入, executeAsync 完成后
         * 由 LlmAgentLoop 构造 tool_result DTO 时读取 (写入
         * {@code ChatMessageDto.assistantMessageId}).
         *
         * <p>可为 null (单测场景 + 兼容老 add() 重载). null 时 LlmAgentLoop 顶层
         * 入口通过 {@code AgentState.findAssistantIdByToolUseId} 反查补救.
         */
        ToolParent parent;
        /**
         * [R32-#29] per-tool child abort controller · 对齐 CC TrackedTool 不持有
         * controller (CC 是 collectResults 内的局部变量), 但 Java 端 TrackedTool
         * 持有以支持 getRemainingResults 阶段再次检查 abort.
         */
        AbortController toolAbortController;
        /**
         * [R32-b12 Fix-v3 P1-2] PreToolUse hook 时长 (毫秒, hook-only) · 对齐 CC
         * toolExecution.ts:863 preToolHookDurationMs (CC 端仅含 hook 执行时间).
         *
         * <p>默认值 0L: hookRegistry==null 或 t.tool==null 时无 PreToolUse hook
         * 执行, 该字段保持 0L, 不会被错误地包含后续 permission gate / tool.execute 时间.
         *
         * <p>WHY PerTrackedTool 字段 (而非 preToolHookStartTimes map): hook 入口
         * 记录 startNs, 出口计算 durationMs 后立即写回字段, 不再依赖 map.remove()
         * 延迟到 emitSuccessTelemetry 才计算 (那会包含后续时间).
         */
        long preToolHookDurationMs = 0L;
        /**
         * [R32-b12 Fix-v3 P1-2] 工具执行时长 (毫秒, tool.execute 真实耗时, 不含 hook).
         *
         * <p>对齐 CC toolExecution.ts:863 durationMs (CC 端是 startTime 到 tool 完成).
         * Java 端拆分为: preToolHookDurationMs (hook only) + toolDurationMs (tool only) =
         *   总耗时. 供 telemetry emit 区分报告.
         *
         * <p>设置时机: t.tool.execute(...) 调用前后, 仅在真正执行 (ALLOW 路径) 时设置.
         */
        long toolDurationMs = 0L;
        /**
         * [B GAP-EXEC-08 修正] 工具是否真实执行过 (tool.execute 被调用) ·
         *   WHY: success-path Bash isError sibling abort 只应对<b>工具真实执行</b>产生的
         *   error result 级联 (对齐 CC isErrorResult 语义). permission gate DENY /
         *   PreToolUse Deny / schema 校验失败 等<b>未执行</b>路径也产生 error result
         *   (is_error=true, CC toolExecution.ts:1034), 但 CC 中并行工具各自的 DENY 结果
         *   不受 sibling abort 影响; Java 串行调度下若不排除, 前一个 DENY 会短路后续
         *   排队工具 → retry hook 丢失 (RetryHookE2ETest 回归). 标志在 tool.execute
         *   调用前置 true, catch(Throwable) 真实执行异常路径同样为 true.
         */
        volatile boolean toolExecuted = false;
        /**
         * [IMP-C2] 结果是否错误 · 组 2-1 拍板后 ToolResult 不再携带 isError（对齐 CC
         * ToolResult 4 字段契约）。执行器在错误路径（permission deny / validation fail /
         * 异常 catch）构造 error result 时同步置 true，供失败 analytics / sibling abort /
         * PostToolUseFailure 分支判定（isError 由 mapper 参数推导，CC is_error 错误路径直构）。
         */
        volatile boolean isError = false;
        /**
         * [IMP-C2] 错误分类（abort/validation/permission/execution...）· OTel 通道改道：
         * 不再存于 ToolResult（errorCategory 字段删除），改由执行器在错误路径
         * {@code ToolErrorFormatter.classifyToolError} 计算并透传 OTel 发射点。
         */
        volatile String errorCategory = null;
        /**
         * [IMP-HOOKS-S6 E9·CCJ-T6-13] 生效工具输入 (pre 链 resolver 后落盘) ·
         *   PostToolUse/PostToolUseFailure hook 入参 = 最终生效 input (strip → hookUpdatedInput
         *   → resolved.input → permissionDecision.updatedInput 逐级收敛后的值), 对齐 CC
         *   toolExecution.ts:1488/:1705 {@code processedInput}. 恒非 null (effectiveCall.input()
         *   兜底), 与 {@link #toolExecuted} 无关 (deny/abort 路径同样落盘).
         */
        JsonNode executedInput;
        /**
         * [IMP-HOOKS-S6 E7·CCJ-T6-21] hook 执行期间 abort → stop 结果标志 ·
         *   对齐 CC toolExecution.ts:848-860 stop case: 工具不执行 + 直接 return,
         *   <b>不触发失败链</b> (无 PostToolUseFailure hooks, 无 failure analytics).
         *   post 链 (:1712-1843) 对该标志跳过 emitPostToolUseFailureAnalytics 与
         *   executePostToolUseFailure (包括 t.isError 分支).
         */
        volatile boolean stoppedByHookExecuted = false;
        /**
         * [hook message 普通消息通道] PreToolUse hook plain message (hook_user_message 类型) 结算
         * 为普通 user 消息 (isMeta=false) 的暂存区 · 对齐 CC toolHooks.ts:478-480
         * {@code result.message → yield {type:'message', message:{message}}} →
         * toolExecution.ts:815 {@code resultingMessages.push(result.message)} → 与 tool_result
         * 同批 (query.ts:1395 filter type==='user' → :1716 messages 同批)、一次性 (非常驻 attachment).
         *
         * <p>WHY 不直接 appendAttachment: CC 中 hook message 是普通 user 消息, 非 attachment
         * (Java 旧实现把它包成 hook_user_message attachment 常驻 state.attachments 每轮重渲染).
         * 本字段由 {@code injectPreToolUseHookAttachments} 收集, dispatch 时合并进
         * {@code t.result.newMessages} → ToolResultApplier.apply → state.messages().addAll,
         * 与 tool_result 同一批送达模型, 不落入 AgentState.attachments.
         */
        java.util.List<ChatMessageDto> pendingHookUserMessages = new ArrayList<>();
        // [S9] 原 hookAttachedMessage / hookAdditionalContexts 字段已删除 —
        //   只写不读死代码 (交付通道从未接线, B-R5/J.md 已记遗憾).
    }

    /**
     * [R32-#26 + B GAP-EXEC-01/04] getAbortReason 三态决策 · 对齐 CC StreamingToolExecutor.ts:210-231.
     *
     * <p>优先级:
     * <ol>
     *   <li>{@link #discarded} → "streaming_fallback"</li>
     *   <li>{@link #hasErrored} → "sibling_error"</li>
     *   <li>父 controller aborted + reason="interrupt" + 本工具 interruptBehavior()="cancel" → "user_interrupted"</li>
     *   <li>父 controller aborted (非 interrupt) → "user_interrupted"</li>
     *   <li>否则 → null (继续执行)</li>
     * </ol>
     *
     * <p>[B 修正] CC 真源 (Pattern #2/#9 grep 实证):
     * <ul>
     *   <li>CC {@code getAbortReason(tool: TrackedTool)} 是 <b>per-tool</b> 参数 (StreamingToolExecutor.ts:210);
     *       interrupt 判定用 {@code getToolInterruptBehavior(tool)} (ts:233-241) 查<b>该工具</b>定义
     *       (findToolByName + definition.interruptBehavior()), 而非"第一个 EXECUTING 工具".</li>
     *   <li>旧 Java 实现 {@link #interruptBehaviorCancel()} 取首 EXECUTING 工具, 多工具并发
     *       (Bash=cancel, FileRead=block) 时无法 per-tool 区分 — 已删除, 由 per-tool 判定替代.</li>
     * </ul>
     *
     * @param t 当前工具 (TrackedTool); null 时按 CC getToolInterruptBehavior 默认 "block" 处理
     * @return 三态 reason 字符串, 或 null
     */
    String getAbortReason(TrackedTool t) {
        if (discarded) return "streaming_fallback";
        if (hasErrored) return "sibling_error";
        if (ctx == null) return null;
        AbortController parent = ctx.abortController();
        if (parent == null || !parent.isCancelled()) return null;
        String reason = parent.reason();
        if ("interrupt".equals(reason)) {
            // interrupt + 本工具 interruptBehavior="cancel" → user_interrupted; 否则 null
            // (对齐 CC getToolInterruptBehavior: 未找到定义或调用抛错 → 默认 "block").
            if (t != null && t.tool != null && "cancel".equals(t.tool.interruptBehavior())) {
                return "user_interrupted";
            }
            return null;
        }
        // 非 interrupt reason (e.g. permission_denied) → user_interrupted
        return "user_interrupted";
    }

    /**
     * [R32-#27 + B GAP-EXEC-03 + ER-IMP-14] createSyntheticErrorMessage 四态文案 ·
     * 对齐 CC StreamingToolExecutor.ts:153-205.
     *
     * <p>CC 真源 (grep 自验, 不信注释):
     * <ul>
     *   <li>CC 是 <b>3 参</b> {@code createSyntheticErrorMessage(toolUseId, reason, assistantMessage)}
     *       (ts:153-157), 返回带 {@code sourceToolAssistantUUID: assistantMessage.uuid} 的 Message
     *       (ts:171,186,203) — 合成错误结果自带父 assistant 关联, 消费点是 insertMessageChain
     *       父链 override (sessionStorage.ts:1033-1036).</li>
     *   <li>Java 端父链归因改走<b>消息层</b> (ER-IMP-14): {@code AgentState.assistantIdByToolUseId}
     *       (bind: LlmAgentLoop:3230 / AgentLoopContext:1451 预绑全 turn 内 toolCall) →
     *       {@code AgentLoopContext:1598-1617} 解析 parentAssistantId → {@code toolResultMessage}
     *       → {@code ChatMessageDto.assistantMessageId} (= CC sourceToolAssistantUUID 等价位,
     *       tool 消息=含对应 tool_use 的 assistant uuid). executor 合成错误仅返回
     *       {@link ToolResult#error}, 不再携带父关联 (CC 3 参的 assistantMessage 参数在
     *       Java 消息层由 map 解析, executor 参数冗余).</li>
     *   <li>{@code user_interrupted} 文案是 {@code REJECT_MESSAGE} (utils/messages.ts:212-213):
     *       "The user doesn't want to proceed with this tool use. The tool use was rejected
     *       (eg. if it was a file edit, the new_string was NOT written to the file). STOP what
     *       you are doing and wait for the user to tell you how to proceed."</li>
     * </ul>
     *
     * <p>[G29① S-2] 对齐 CC: agent_cancelled 态已删除 (CC getAbortReason 无此态), 若仍被
     * 引用则产出 REJECT_MESSAGE (不再用 CANCEL_MESSAGE)。四态文案 (对齐 CC StreamingToolExecutor.ts:153-205):
     * <ul>
     *   <li>{@code "user_interrupted"} → REJECT_MESSAGE (utils/messages.ts:212-213)</li>
     *   <li>{@code "agent_cancelled"} → REJECT_MESSAGE (对齐 CC whole-agent cancel; 原 CANCEL_MESSAGE 已删)</li>
     *   <li>{@code "streaming_fallback"} → "<tool_use_error>Error: Streaming fallback - tool execution discarded</tool_use_error>"</li>
     *   <li>{@code "sibling_error"} → "<tool_use_error>Cancelled: parallel tool call ${desc} errored</tool_use_error>"</li>
     * </ul>
     *
     * @param toolUseId 工具调用 ID (CC toolUseId)
     * @param reason    原因 (sibling_error / user_interrupted / streaming_fallback; agent_cancelled 已删除, 仅防御保留)
     */
    ToolResult createSyntheticErrorMessage(String toolUseId, String reason) {
        String content;
        switch (reason) {
            case "user_interrupted":
                // CC StreamingToolExecutor.ts:165 content: withMemoryCorrectionHint(REJECT_MESSAGE)
                //   (grep 自验, 不信注释) · utils/messages.ts:212-213 REJECT_MESSAGE 原文
                content = PermissionRejectMessages.withMemoryCorrectionHint(PermissionRejectMessages.REJECT_MESSAGE);
                break;
            case "agent_cancelled":
                // [G29① S-2] 对齐 CC 走 REJECT_MESSAGE (deletion-manifest S-2) ·
                //   CC createSyntheticErrorMessage 无 agent_cancelled 态 (StreamingToolExecutor.ts:153
                //   仅 sibling_error/user_interrupted/streaming_fallback); whole-agent cancel 走
                //   abortController → user_interrupted → REJECT_MESSAGE. 本 case 为防御性保留
                //   (原 CANCEL_MESSAGE 已删), 若仍被引用则产出 withMemoryCorrectionHint(REJECT_MESSAGE) 对齐 CC.
                content = PermissionRejectMessages.withMemoryCorrectionHint(PermissionRejectMessages.REJECT_MESSAGE);
                break;
            case "streaming_fallback":
                content = "<tool_use_error>Error: Streaming fallback - tool execution discarded</tool_use_error>";
                break;
            case "sibling_error":
                String desc = erroredToolDescription;
                content = desc != null && !desc.isEmpty()
                    ? "<tool_use_error>Cancelled: parallel tool call " + desc + " errored</tool_use_error>"
                    : "<tool_use_error>Cancelled: parallel tool call errored</tool_use_error>";
                break;
            default:
                content = "[R32 synthetic unknown abort reason] " + reason;
                break;
        }
        return ToolResult.error(toolUseId, content);
    }

    // ─────────────────── [A1 撤外层] 决策 telemetry helpers ───────────────────

    /**
     * [B GAP-EXEC-08 (TrackedTool)] 取工具父 assistant message UUID ·
     * 对齐 CC TrackedTool.assistantMessage.uuid (StreamingToolExecutor.ts:21,114-121).
     *
     * <p>Java 端父句柄是 {@link ToolParent#assistantMessageId()} (LlmAgentLoop 预分配,
     * 经 {@link #add(ToolUseBlock, ToolParent, Consumer)} 注入); 单测/老 add() 重载
     * parent==null 时返回 null (合成错误不携带父关联, 与 2 参重载等价).
     *
     * @param t 当前 TrackedTool
     * @return 父 assistant message UUID, 或 null
     */
    private static String sourceAssistantId(TrackedTool t) {
        return t != null && t.parent != null ? t.parent.assistantMessageId() : null;
    }

    // ─────────────────── [工具调用实时推] push helpers ───────────────────

    /**
     * [工具调用实时推] tool_call 实时推送 · add() 三分支入口调用 (CC 工具卡片即时性).
     *
     * <p>WHY (CLAUDE.md 规则 9): 工具调用到达 executor 即实时推 tool_call 事件到 streamTopic,
     * 前端工具卡片即时出现; 回放 (ChatService.replayAndPersist) 经 AgentState 去重集合
     * 跳过已推 STOMP, 防前端重复卡片.
     *
     * <p><b>幂等去重</b>: {@link AgentState#realtimeToolCallsPushed()} 登记 id, Set.add 返回
     * false = 本 turn 已推过同 toolCallId → 跳过 (覆盖 streaming-fallback 重建二次 add 同 id
     * 场景, T4). agentStateRef 未注入 (单测/cron) 时跳过去重, 直接推送.
     *
     * @param t 已 add 的 TrackedTool (call 必非 null)
     */
    private void pushToolCallRealtime(TrackedTool t) {
        ToolStreamPublisher ts = this.toolStream;
        if (ts == null || ts.wsTemplate() == null || ts.streamTopic() == null || t == null || t.call == null) {
            return; // no-op: 通道未注入 (向后兼容)
        }
        // 幂等去重: 本 turn 已推过同 toolCallId → 跳过 (Set.add 返回 false).
        if (agentStateRef != null && !agentStateRef.realtimeToolCallsPushed().add(t.call.id())) {
            if (log.isDebugEnabled()) {
                log.debug("TOOL realtime tool_call dedup skip: id={}", abbreviate(t.call.id(), 24));
            }
            return;
        }
        String assistantId = sourceAssistantId(t);
        if (assistantId == null && agentStateRef != null) {
            assistantId = agentStateRef.currentAssistantMessageId();
        }
        if (assistantId == null) {
            assistantId = "";
        }
        Map<String, Object> args = convertInputToMap(t.call.input());
        try {
            ts.wsTemplate().convertAndSend(ts.streamTopic(),
                new MessageToolCallEvent(ts.sessionId(), ts.userMessageId(),
                    assistantId, t.call.id(), t.call.name(), args));
            if (log.isDebugEnabled()) {
                log.debug("TOOL realtime tool_call pushed: id={} name={} assistantId={}",
                    abbreviate(t.call.id(), 24), t.call.name(), abbreviate(assistantId, 16));
            }
        } catch (Throwable th) {
            // 推送失败不阻断工具执行 (best-effort, fail loud 已记日志)
            log.warn("TOOL realtime tool_call 推送失败: id={} err={}",
                abbreviate(t.call.id(), 24), th.toString());
        }
    }

    /**
     * [工具调用实时推] tool_result 实时推送 · executeAsync 5 个 COMPLETED 出口调用.
     *
     * <p><b>幂等去重</b>: {@link AgentState#realtimeToolResultsPushed()} 登记 toolCallId,
     * Set.add 返回 false = 本 turn 已推过 → 跳过 (tool_result 多工具并发完成写同一集合,
     * ConcurrentHashMap.newKeySet 保证安全, R5).
     *
     * <p>isError 取精确 {@code t.isError} (优于回放启发式 ChatService:490-493, R3 已知差异).
     *
     * @param t 已完成的 TrackedTool (call 必非 null)
     */
    private void pushToolResultRealtime(TrackedTool t) {
        ToolStreamPublisher ts = this.toolStream;
        if (ts == null || ts.wsTemplate() == null || ts.streamTopic() == null || t == null || t.call == null) {
            return; // no-op: 通道未注入 (向后兼容)
        }
        // 幂等去重: 本 turn 已推过同 toolCallId → 跳过.
        if (agentStateRef != null && !agentStateRef.realtimeToolResultsPushed().add(t.call.id())) {
            if (log.isDebugEnabled()) {
                log.debug("TOOL realtime tool_result dedup skip: id={}", abbreviate(t.call.id(), 24));
            }
            return;
        }
        String assistantId = sourceAssistantId(t);
        if (assistantId == null && agentStateRef != null) {
            assistantId = agentStateRef.currentAssistantMessageId();
        }
        if (assistantId == null) {
            assistantId = "";
        }
        String result = truncateResult(t.result == null ? null : t.result.data());
        try {
            ts.wsTemplate().convertAndSend(ts.streamTopic(),
                new MessageToolResultEvent(ts.sessionId(), ts.userMessageId(),
                    assistantId, t.call.id(), result, t.isError));
            if (log.isDebugEnabled()) {
                log.debug("TOOL realtime tool_result pushed: id={} isError={} len={}",
                    abbreviate(t.call.id(), 24), t.isError, result.length());
            }
        } catch (Throwable th) {
            // 推送失败不阻断工具执行 (best-effort, fail loud 已记日志)
            log.warn("TOOL realtime tool_result 推送失败: id={} err={}",
                abbreviate(t.call.id(), 24), th.toString());
        }
    }

    /**
     * [工具调用实时推] JsonNode 入参 → Map (供 MessageToolCallEvent.arguments) ·
     * executor 已惯用 ObjectMapper (2326/3025 先例). null / 异常 → 空 Map (best-effort).
     */
    private static Map<String, Object> convertInputToMap(JsonNode input) {
        if (input == null) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                input, new TypeReference<Map<String, Object>>() {});
        } catch (Throwable th) {
            return Map.of();
        }
    }

    /**
     * [工具调用实时推] tool_result 内容截断 · 对齐 ChatService.truncate@5000 语义.
     * null → ""; 超 5000 → 截断加 "\n... (truncated)" (前端预览限幅, R7).
     */
    private static String truncateResult(Object data) {
        if (data == null) {
            return "";
        }
        String s = data instanceof String str ? str : String.valueOf(data);
        if (s.length() > 5000) {
            return s.substring(0, 5000) + "\n... (truncated)";
        }
        return s;
    }

    /**
     * [A1 撤外层] (a) agent-level cancel telemetry · 对齐 CC
     * toolExecution.ts:415-453 tengu_tool_use_cancelled 触发条件
     * (AbortController.signal.aborted).
     *
     * <p>[G step 5d 增强] 补 {@code isMcp} 字段对齐 CC 真源 line 419
     * ({@code isMcp: tool.isMcp ?? false}).
     *
     * <p>[Session I P3-2 增强] 补 {@code queryChainId} / {@code queryDepth} 字段对齐 CC 真源
     * line 422-424 ({@code queryChainId: toolUseContext.queryTracking?.chainId,
     * queryDepth: toolUseContext.queryTracking?.depth}). 来源 {@link ToolUseContext#queryTracking()}
     * — LlmAgentLoop 每轮经 AgentLoopContext.toolExecContext stamp (A2 已有基础设施).
     * ctx 可为 null (agent-level cancel 分支不保证 ctx 非空) → null-guard, 不注入即等价 CC
     * {@code queryTracking} undefined 的缺省语义. mcpServerType / mcpServerBaseUrl / requestId
     * 不注入 (Java 无等价字段, 按硬约束 #8 不捏造等价物, 与 emitSuccessTelemetry 既有模式一致).
     *
     * <p>WHY 内层 emit: 原 LlmAgentLoop.applyPermissionFilter 在外层 emit;
     * 撤外层后改在 StreamingToolExecutor.executeAsync 入口 emit, 与 CC 串联顺序一致.
     * 同时把 ToolDecisionInfo(source="user_reject", decision="reject") 注入
     * toolDecisions map, 供 emitSuccessTelemetry / emitPostToolUseFailureAnalytics
     * 读 decision_source / decision_type.
     *
     * @param t 当前 TrackedTool
     */
    private void emitCancelledTelemetry(TrackedTool t) {
        if (telemetry == null || t == null || t.call == null) return;
        String toolNameRaw = t.tool != null ? t.tool.name() : t.call.name();
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        // [G step 5d] 取 tool.isMcp 字段(与 CC toolExecution.ts:419 isMcp: tool.isMcp ?? false 对齐)
        // tool==null 时(如 forceReset queue 路径)走 false fallback, 与 CC ?? false 语义等价
        boolean isMcp = false;
        if (t.tool != null) {
            try {
                isMcp = t.tool.isMcp();
            } catch (Throwable th) {
                log.debug("emitCancelledTelemetry: tool.isMcp() 抛错, 走 false fallback: {}", th.toString());
                isMcp = false;
            }
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("toolName", toolName);
        attrs.put("toolUseID", McpServerToolSanitizer.sanitize(t.call.id()));
        attrs.put("isMcp", isMcp);  // [G] 对齐 CC toolExecution.ts:419
        // [Session I P3-2] queryChainId/queryDepth 注入 · 对齐 CC toolExecution.ts:422-424
        //   queryTracking 由 AgentLoopContext.toolExecContext 每轮 stamp (chainId+depth);
        //   ctx == null (agent-level cancel 场景) 或未 stamp → 不注入 (等价 CC undefined).
        if (this.ctx != null && this.ctx.queryTracking() != null) {
            Object chainId = this.ctx.queryTracking().get("chainId");
            Object depth = this.ctx.queryTracking().get("depth");
            if (chainId != null) {
                attrs.put("queryChainId", chainId);
            }
            if (depth != null) {
                attrs.put("queryDepth", depth);
            }
        }
        try {
            telemetry.recordEvent("tengu_tool_use_cancelled", attrs);
            telemetry.logOTelEvent("tengu_tool_use_cancelled", attrs);
            if (log.isDebugEnabled()) {
                // [G P1-6 + I P3-2] 数据流日志 (硬约束 6: 中文 + isDebugEnabled 包裹):
                // attrs dump 含 queryChainId/queryDepth, 供取消事件归因
                log.debug("TOOL cancelled telemetry 已发出: id={} tool={} isMcp={} queryChainId={} queryDepth={}",
                    abbreviate(t.call.id(), 24), toolName, isMcp,
                    this.ctx != null && this.ctx.queryTracking() != null
                        ? this.ctx.queryTracking().get("chainId") : null,
                    this.ctx != null && this.ctx.queryTracking() != null
                        ? this.ctx.queryTracking().get("depth") : null);
            }
        } catch (Throwable th) {
            log.warn("TOOL cancelled telemetry 失败: id={} err={}",
                abbreviate(t.call.id(), 24), th.toString());
        }
        // 注入 decision (cancelled fallback 路径) · source="user_reject"
        java.util.Map<String, ToolDecisionInfo> decisions =
            new java.util.HashMap<>(this.toolDecisions);
        decisions.put(t.call.id(), new ToolDecisionInfo("user_reject", "reject"));
        this.toolDecisions = java.util.Map.copyOf(decisions);
    }

    /**
     * [A1 撤外层] (d) tool use error telemetry · schema 校验失败路径 · 对齐 CC
     * toolExecution.ts:635-662 (tengu_tool_use_error · InputValidationError 载荷).
     *
     * <pre>{@code
     * logEvent('tengu_tool_use_error', {
     *   error: 'InputValidationError',                          // :637 字面量常量
     *   errorDetails: errorContent.slice(0, 2000),              // :638-641 截断 2000
     *   messageID: messageId,                                   // :642-643
     *   toolName: sanitizeToolNameForAnalytics(tool.name),      // :644
     *   isMcp: tool.isMcp ?? false,                             // :645
     *   queryChainId, queryDepth,                               // :647-649
     *   ...(mcpServerType / mcpServerBaseUrl / requestId 可选),  // :650-661
     * })
     * }</pre>
     *
     * <ul>
     *   <li>{@code error} = 字面量 {@code InputValidationError}（CC :637 常量，非错误详情）</li>
     *   <li>{@code errorDetails} = schema 校验失败消息，截断 2000 字符（CC :638-641
     *       {@code errorContent.slice(0, 2000)}）</li>
     *   <li>{@code messageID} = {@code sourceAssistantId(t)}（Java 端 CC {@code message.id}
     *       镜像 = ToolParent.assistantMessageId，LlmAgentLoop turn 预分配）；
     *       parent==null（单测/老 add 重载）时不注入，不捏造归因</li>
     *   <li>{@code toolName} = {@link McpServerToolSanitizer}（Java 端
     *       sanitizeToolNameForAnalytics 等价物，metadata.ts:70-77）</li>
     *   <li>{@code isMcp} = {@code tool.isMcp()} 缺省 false fallback（CC :645 ?? false；
     *       tool==null 如 forceReset queue 路径同样走 false）</li>
     *   <li>queryChainId/queryDepth 同 emitCancelledTelemetry 模式（CC :647-649；
     *       ctx==null 或未 stamp 不注入 ≡ CC undefined 缺省）</li>
     *   <li><b>无 toolUseID</b>：CC :635-662 载荷不含该字段（旧 Java 实现多传，
     *       P-AL-05 D-2 已删）</li>
     *   <li>mcpServerType / mcpServerBaseUrl / requestId / mcpToolDetailsForAnalytics：
     *       不注入（Java 无等价字段，硬约束 #8 不捏造，与 emitCancelledTelemetry :2950
     *       既有模式一致）</li>
     * </ul>
     *
     * <p>附带副作用（与旧 3 参 helper 等价）：注入 {@code ToolDecisionInfo("config",
     * "reject")} 到 toolDecisions map（旧调用点均传 source="config"，此处硬编码）。
     *
     * @param t            当前 TrackedTool
     * @param errorDetails schema 校验失败消息（Zod issue 折叠单行）
     */
    private void emitToolUseErrorTelemetry(TrackedTool t, String errorDetails) {
        if (telemetry == null || t == null || t.call == null) return;
        String toolNameRaw = t.tool != null ? t.tool.name() : t.call.name();
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        // [P-AL-05 D-2] 取 tool.isMcp 字段（CC :645 tool.isMcp ?? false 缺省语义）
        //   tool==null 时（forceReset queue 路径）走 false fallback，等价 CC ?? false
        boolean isMcp = false;
        if (t.tool != null) {
            try {
                isMcp = t.tool.isMcp();
            } catch (Throwable th) {
                log.debug("emitToolUseErrorTelemetry: tool.isMcp() 抛错, 走 false fallback: {}", th.toString());
                isMcp = false;
            }
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("error", "InputValidationError");  // CC toolExecution.ts:637 字面量常量
        if (errorDetails != null) {
            attrs.put("errorDetails",
                errorDetails.length() > 2000 ? errorDetails.substring(0, 2000) : errorDetails);
        }
        String messageId = sourceAssistantId(t);
        if (messageId != null) {
            attrs.put("messageID", messageId);  // CC toolExecution.ts:642-643 messageId
        }
        attrs.put("toolName", toolName);
        attrs.put("isMcp", isMcp);  // CC toolExecution.ts:645
        putQueryTrackingAttrs(attrs);
        emitUseErrorEvent(t, attrs, "schema");
        injectConfigRejectDecision(t);
    }

    /**
     * [A1 撤外层] (e) tool use error telemetry · validateInput 语义校验失败路径 · 对齐 CC
     * toolExecution.ts:691-698 (tengu_tool_use_error · semantic fail 载荷).
     *
     * <pre>{@code
     * logEvent('tengu_tool_use_error', {
     *   messageID: messageId,                                   // :692-693
     *   toolName: sanitizeToolNameForAnalytics(tool.name),      // :694
     *   error: isValidCall.message,                             // :695-696
     *   errorCode: isValidCall.errorCode,                       // :697
     *   isMcp: tool.isMcp ?? false,                             // :698
     *   queryChainId, queryDepth,                               // :700-702
     *   ...(mcpServerType / mcpServerBaseUrl / requestId 可选),
     * })
     * }</pre>
     *
     * <p>字段说明同 schema 重载（messageID 缺省不注入 / isMcp false fallback /
     * queryChainId+queryDepth 可选注入 / <b>无 toolUseID</b>——CC :691-698 载荷不含
     * 该字段，旧 Java 实现多传已删）。
     *
     * @param t        当前 TrackedTool
     * @param errorCode 语义校验错误码（CC isValidCall.errorCode，如 PATH_ESCAPE）
     * @param errorMsg  语义校验错误消息（CC isValidCall.message）
     */
    private void emitToolUseErrorTelemetry(TrackedTool t, String errorCode, String errorMsg) {
        if (telemetry == null || t == null || t.call == null) return;
        String toolNameRaw = t.tool != null ? t.tool.name() : t.call.name();
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        // [P-AL-05 D-2] isMcp 缺省 false fallback（CC :698 tool.isMcp ?? false）
        boolean isMcp = false;
        if (t.tool != null) {
            try {
                isMcp = t.tool.isMcp();
            } catch (Throwable th) {
                log.debug("emitToolUseErrorTelemetry: tool.isMcp() 抛错, 走 false fallback: {}", th.toString());
                isMcp = false;
            }
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        String messageId = sourceAssistantId(t);
        if (messageId != null) {
            attrs.put("messageID", messageId);  // CC toolExecution.ts:692-693 messageId
        }
        attrs.put("toolName", toolName);
        if (errorMsg != null) {
            attrs.put("error", errorMsg);  // CC :695-696 isValidCall.message
        }
        if (errorCode != null) {
            attrs.put("errorCode", errorCode);  // CC :697 isValidCall.errorCode
        }
        attrs.put("isMcp", isMcp);  // CC :698
        putQueryTrackingAttrs(attrs);
        emitUseErrorEvent(t, attrs, "semantic");
        injectConfigRejectDecision(t);
    }

    /**
     * tengu_tool_use_error 事件双发（recordEvent + logOTelEvent）+ 数据流日志
     * （硬约束 6: 中文 WHY + if(log.isDebugEnabled()) 包裹）。
     *
     * @param t     当前 TrackedTool（仅取 call.id 做日志归因）
     * @param attrs 事件 attrs（各调用方按 CC 对应载荷组装）
     * @param path  "schema" | "semantic"（日志归因）
     */
    private void emitUseErrorEvent(TrackedTool t, java.util.Map<String, Object> attrs, String path) {
        try {
            telemetry.recordEvent("tengu_tool_use_error", attrs);
            telemetry.logOTelEvent("tengu_tool_use_error", attrs);
            if (log.isDebugEnabled()) {
                // 数据流日志: tengu_tool_use_error 已发出 (CC toolExecution.ts:635-662/691-698 载荷对齐)
                log.debug("TOOL use_error telemetry 已发出: path={} id={} tool={} isMcp={} attrs={}",
                    path, abbreviate(t.call.id(), 24), attrs.get("toolName"), attrs.get("isMcp"), attrs.keySet());
            }
        } catch (Throwable th) {
            log.warn("TOOL use_error telemetry 失败: id={} err={}",
                abbreviate(t.call.id(), 24), th.toString());
        }
    }

    /**
     * queryChainId/queryDepth 可选注入 · 对齐 CC toolExecution.ts:647-649 / :700-702
     * （queryTracking 由 AgentLoopContext.toolExecContext 每轮 stamp chainId+depth；
     * ctx==null 或未 stamp → 不注入 ≡ CC undefined 缺省语义）。
     *
     * @param attrs 目标事件 attrs
     */
    private void putQueryTrackingAttrs(java.util.Map<String, Object> attrs) {
        if (this.ctx != null && this.ctx.queryTracking() != null) {
            Object chainId = this.ctx.queryTracking().get("chainId");
            Object depth = this.ctx.queryTracking().get("depth");
            if (chainId != null) {
                attrs.put("queryChainId", chainId);
            }
            if (depth != null) {
                attrs.put("queryDepth", depth);
            }
        }
    }

    /**
     * validation/config fail fallback 路径决策归因注入（旧 3 参 helper 等价——
     * 两调用点原均传 source="config"，此处硬编码；供 emitSuccessTelemetry /
     * emitPostToolUseFailureAnalytics 读 decision_source / decision_type）。
     *
     * @param t 当前 TrackedTool
     */
    private void injectConfigRejectDecision(TrackedTool t) {
        java.util.Map<String, ToolDecisionInfo> decisions =
            new java.util.HashMap<>(this.toolDecisions);
        decisions.put(t.call.id(), new ToolDecisionInfo("config", "reject"));
        this.toolDecisions = java.util.Map.copyOf(decisions);
    }

    /**
     * [A1 撤外层] (d) SchemaNotSentHint telemetry · 对齐 CC
     * toolExecution.ts:625-628 tengu_deferred_tool_schema_not_sent 触发条件
     * (MCP deferred tool schema 未发到 LLM).
     *
     * <p>[P-CC-03 拍板] attrs 对齐 CC :625-628 精确两字段:
     * <pre>{@code
     * logEvent('tengu_deferred_tool_schema_not_sent', {
     *   toolName: sanitizeToolNameForAnalytics(tool.name),
     *   isMcp: tool.isMcp ?? false,
     * })
     * }</pre>
     * <ul>
     *   <li>{@code toolName}: 经 {@link McpServerToolSanitizer} 脱敏 (Java 端
     *       sanitizeToolNameForAnalytics 等价物, 与 emitCancelledTelemetry 同模式)</li>
     *   <li>{@code isMcp}: {@code tool.isMcp()} —— CC {@code tool.isMcp ?? false}
     *       缺省分支等价 (Tool.ts:436 isMcp 为可选布尔; Java
     *       {@link com.nexusai.application.agent.tool.Tool#isMcp()} default 有实现,
     *       tool==null 或抛错时 false fallback)</li>
     *   <li><b>无 toolUseID</b>: CC 事件载荷不含该字段 (旧 Java 实现多传, 已删)</li>
     * </ul>
     *
     * @param t 当前 TrackedTool
     */
    private void emitDeferredSchemaTelemetry(TrackedTool t) {
        if (telemetry == null || t == null || t.call == null) return;
        String toolNameRaw = t.tool != null ? t.tool.name() : t.call.name();
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        // [P-CC-03] 取 tool.isMcp 字段 (与 CC toolExecution.ts:627 isMcp: tool.isMcp ?? false 对齐)
        //   tool==null 时 (如 forceReset queue 路径) 走 false fallback, 与 CC ?? false 语义等价
        boolean isMcp = false;
        if (t.tool != null) {
            try {
                isMcp = t.tool.isMcp();
            } catch (Throwable th) {
                log.debug("emitDeferredSchemaTelemetry: tool.isMcp() 抛错, 走 false fallback: {}", th.toString());
                isMcp = false;
            }
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("toolName", toolName);
        attrs.put("isMcp", isMcp);  // [P-CC-03] 对齐 CC toolExecution.ts:627; 删 toolUseID (CC 无此字段)
        try {
            telemetry.recordEvent("tengu_deferred_tool_schema_not_sent", attrs);
            if (log.isDebugEnabled()) {
                // 数据流日志: deferred schema 事件已发出 (CC toolExecution.ts:625-628 载荷仅 toolName+isMcp)
                log.debug("TOOL deferred_schema telemetry 已发出: id={} tool={} isMcp={}",
                    abbreviate(t.call.id(), 24), toolName, isMcp);
            }
        } catch (Throwable th) {
            log.warn("TOOL deferred_schema telemetry 失败: id={} err={}",
                abbreviate(t.call.id(), 24), th.toString());
        }
    }

    /**
     * [A1 撤外层] (h) 决策归因注入 · 对齐 CC toolExecution.ts:948-977 (allow) +
     * 1001-1022 (deny) + 1105-1126 (tool_decision OTel event).
     *
     * <p>把 Allow/Deny 决策归因 (source + decision) 注入 toolDecisions map,
     * 供 emitSuccessTelemetry / emitPostToolUseFailureAnalytics 读 decision_source
     * / decision_type 字段. 对于 DENY,从 {@link PermissionResult.Deny#reason()}
     * 推 OTel source (复用 PermissionDecisionReason.decisionReasonToOTelSource);
     * ALLOW 时 result=null → source="other".
     *
     * @param t          当前 TrackedTool
     * @param gateResult 权限门决策结果 (含 Decision + decision reason)
     */
    private void injectDecisionInfo(TrackedTool t, ToolPermissionGate.DecisionResult gateResult) {
        if (t == null || t.call == null || gateResult == null) return;
        ToolPermissionGate.Decision decision = gateResult.decision();
        if (decision == ToolPermissionGate.Decision.ASK) {
            // ASK 不会真正返回给 executeAsync 调用方 (gate 内部同步 prompter 转 ALLOW/DENY).
            // 但保险起见: ASK 时不注入 (CC line 953-955 仅 behavior!='ask' 触发 tool_decision).
            return;
        }
        String otelSource;
        String otelDecision;
        // [Session H9] gate 已嵌入 logPermissionDecision 归因 ({source, decision, timestamp} ·
        //   permissionLogging.ts:220-228) → 直接采用, 不再二次推导
        ToolDecisionInfo embedded = gateResult.decisionInfo();
        boolean alreadyLogged = embedded != null;
        if (alreadyLogged) {
            otelSource = embedded.source();
            otelDecision = embedded.decision();
        } else if (decision == ToolPermissionGate.Decision.ALLOW) {
            // ALLOW result 通常为 null; source 默认 "other"
            otelSource = "other";
            otelDecision = "accept";
        } else {
            // DENY: 从 PermissionResult.Deny.reason() 推 OTel source
            if (gateResult.result() instanceof PermissionResult.Deny denyResult
                && denyResult.reason() != null) {
                otelSource = com.nexusai.application.agent.permission.PermissionDecisionReason
                    .decisionReasonToOTelSource(denyResult.reason(),
                        com.nexusai.application.agent.permission.PermissionBehavior.DENY);
            } else {
                otelSource = "other";
            }
            otelDecision = "reject";
        }
        java.util.Map<String, ToolDecisionInfo> decisions =
            new java.util.HashMap<>(this.toolDecisions);
        decisions.put(t.call.id(), new ToolDecisionInfo(otelSource, otelDecision));
        this.toolDecisions = java.util.Map.copyOf(decisions);
        log.info("TOOL decision telemetry: callId={} tool={} decision={} source={}",
            abbreviate(t.call.id(), 24), t.call.name(), otelDecision, otelSource);
        // [A1] tool_decision OTel event · 对齐 CC toolExecution.ts:948-977
        //   [Session H9] CC :958 guard — 交互路径已由 logPermissionDecision 记录
        //   (toolDecisions map 已含 toolUseID) → 不重复发 tool_decision + code-edit counter
        if (telemetry != null) {
            try {
                String toolNameRaw = t.tool != null ? t.tool.name() : t.call.name();
                if (!alreadyLogged) {
                    telemetry.logOTelEvent("tool_decision",
                        Map.of("decision", otelDecision, "source", otelSource,
                            "tool_name", toolNameRaw));
                    // CC toolExecution.ts:970-976 headless 路径的 code-edit counter (H9 补全)
                    if (com.nexusai.application.agent.permission.PermissionDecisionLogger
                            .isCodeEditingTool(toolNameRaw)) {
                        telemetry.incrementCodeEditCounter(
                            toolNameRaw, otelDecision, otelSource, otelDecision);
                    }
                }
                // Statsig 1P 双发 (对齐 CC line 1105-1126)
                String eventName = "allow".equals(otelDecision)
                    ? "tengu_tool_use_can_use_tool_allowed"
                    : "tengu_tool_use_can_use_tool_rejected";
                java.util.Map<String, Object> allowDenyAttrs = new java.util.HashMap<>();
                allowDenyAttrs.put("toolName", McpServerToolSanitizer.sanitize(toolNameRaw));
                allowDenyAttrs.put("toolUseID", McpServerToolSanitizer.sanitize(t.call.id()));
                allowDenyAttrs.put("behavior", otelDecision);
                telemetry.recordEvent(eventName, allowDenyAttrs);
            } catch (Throwable th) {
                log.warn("TOOL tool_decision telemetry 失败: id={} err={}",
                    abbreviate(t.call.id(), 24), th.toString());
            }
        }
    }

    /**
     * [H14][IMP-WF3-TC-01] 释放 toolUseID 的分类器审批记录 · 对齐 CC UserToolSuccessMessage.tsx:47-50.
     *
     * <p>CC 前端在工具结果渲染时 getClassifierApproval (bash matchedRule 显示) +
     * deleteClassifierApproval (一次性读取, 防 map 泄漏). Java 端无 UI 渲染层,
     * 在工具成功结果出口同步读取: getClassifierApproval 命中时先按 toolUseId 暂存
     * {@code AgentState.classifierMatchedRules} (供 AgentLoopContext 构建工具结果 payload
     * ChatMessageDto.matchedRule 时取走, 前端展示"已自动批准（规则X）"), 记录数据流日志
     * (证明 approval 被消费), 再 deleteClassifierApproval 清理 store (服务端保留清理,
     * DC-WF3-TC-01 混合方案).
     *
     * @param toolUseID 工具调用 ID (与 setClassifierApproval 的 key 一致)
     */
    private void releaseClassifierApproval(String toolUseID) {
        try {
            String matchedRule = com.nexusai.application.agent.permission.ClassifierApprovals
                .getClassifierApproval(toolUseID, null);
            if (matchedRule != null) {
                // [IMP-WF3-TC-01] 暂存到 AgentState → AgentLoopContext 渲染点取走注入 ChatMessageDto.matchedRule
                com.nexusai.application.agent.AgentState st = this.agentStateRef;
                if (st != null) {
                    st.recordClassifierMatchedRule(toolUseID, matchedRule);
                }
                if (log.isInfoEnabled()) {
                    log.info("TOOL classifier approval consumed: toolUseId={} matchedRule={}",
                        abbreviate(toolUseID, 24), matchedRule);
                }
            }
            com.nexusai.application.agent.permission.ClassifierApprovals.deleteClassifierApproval(toolUseID);
        } catch (Throwable th) {
            log.warn("TOOL classifier approval release 失败: toolUseId={} err={}",
                abbreviate(toolUseID, 24), th.toString());
        }
    }

    // ─────────────────── [R32-b12 D-5] OTel/Statsig 2 埋点 helpers ───────────────────

    /**
     * [R32-b12 D-5] 成功路径埋点 · 对齐 CC toolExecution.ts:1134-1395
     * (tengu_tool_use_success + logOTelEvent('tool_result')).
     *
     * @param t   当前 TrackedTool
     * @param t0  executeAsync 入口时间 (ms epoch) — durationMs = now - t0
     */
    private void emitSuccessTelemetry(TrackedTool t, long t0) {
        if (telemetry == null || t.tool == null) return;
        ToolResult result = (t.result instanceof ToolResult tr) ? tr : null;
        if (result == null || t.isError) return;
        long durationMs = System.currentTimeMillis() - t0;
        // [R32-b12 Fix-v3 P1-2] preToolHookDurationMs 仅含 hook 耗时 · 工具执行时长另用 toolDurationMs.
        //   之前用 preToolHookStartTimes map + map.remove() 在 emit 时计算, 但 startNs 记录在 hook
        //   入口, 计算时 (now - startNs) 包含 hook + permission gate + tool.execute + emit 间隔.
        //   现在 t.preToolHookDurationMs 在 hook 出口已记录, t.toolDurationMs 在 tool.execute
        //   出口已记录. 两个值精确分离, analytics 数据真实可靠.
        long preToolHookDurationMs = t.preToolHookDurationMs;
        long toolDurationMsOnly = t.toolDurationMs;
        String resultContent = result.data() instanceof String dc ? dc : String.valueOf(result.data());
        long toolResultSizeBytes = telemetry.toolResultSizeBytes(resultContent);
        String fileExtension = resolveFileExtension(t.call.name(), t.call.input());
        String toolNameRaw = t.tool.name() != null ? t.tool.name() : t.call.name();
        // [R32-b12 Fix-v3 P1-4] MCP server tool 脱敏 · 对齐 CC sanitizeMcpServerTool.ts.
        //   raw mcp__server__tool 可能含 IP / 路径 / 凭据, telemetry emit 前脱敏.
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        ToolDecisionInfo decisionInfo = toolDecisions.get(t.call.id());
        String mcpServerScope = resolveMcpServerScope(toolNameRaw, t.tool);

        // Statsig/1P: tengu_tool_use_success
        Map<String, Object> successAttrs = new java.util.HashMap<>();
        successAttrs.put("toolName", toolName);
        successAttrs.put("toolUseID", McpServerToolSanitizer.sanitize(t.call.id()));
        successAttrs.put("durationMs", durationMs);
        successAttrs.put("preToolHookDurationMs", preToolHookDurationMs);
        successAttrs.put("toolDurationMs", toolDurationMsOnly);
        successAttrs.put("toolResultSizeBytes", toolResultSizeBytes);
        // [Session I P3-3] errorCategory 字段 · R32-b15 文档化 Java 偏离: CC 真源为
        //   toolExecution.ts:1643 classifyToolError() → error attr; Java 侧 8 桶分类
        //   注入 ToolResult.errorCategory (偏离声明见 ToolResult.java:46).
        //   success 路径 result.isError() == false (line 2181 早返), 故 errorCategory 恒为 null;
        //   但仍按 Pattern #11 emit null, telemetry 真实反映状态, 不早返.
        successAttrs.put("errorCategory", t.errorCategory);
        if (fileExtension != null) successAttrs.put("fileExtension", fileExtension);
        if (decisionInfo != null) successAttrs.put("decisionSource", decisionInfo.source());
        telemetry.recordEvent("tengu_tool_use_success", successAttrs);

        // OTel: tool_result event
        Map<String, Object> oTelAttrs = new java.util.HashMap<>();
        oTelAttrs.put("tool_name", toolName);
        oTelAttrs.put("success", "true");
        oTelAttrs.put("duration_ms", String.valueOf(durationMs));
        oTelAttrs.put("pre_tool_hook_duration_ms", String.valueOf(preToolHookDurationMs));
        oTelAttrs.put("tool_duration_ms", String.valueOf(toolDurationMsOnly));
        oTelAttrs.put("tool_result_size_bytes", String.valueOf(toolResultSizeBytes));
        if (decisionInfo != null) {
            oTelAttrs.put("decision_source", decisionInfo.source());
            oTelAttrs.put("decision_type", decisionInfo.decision());
        }
        if (mcpServerScope != null) oTelAttrs.put("mcp_server_scope", mcpServerScope);
        // extractToolInputForTelemetry: 当 OTEL_LOG_TOOL_DETAILS=true
        String toolInputJson = telemetry.extractToolInputForTelemetry(t.call.input());
        if (toolInputJson != null) oTelAttrs.put("tool_input", toolInputJson);
        telemetry.logOTelEvent("tool_result", oTelAttrs);
    }

    /**
     * [R32-b12 D-5] PostToolUse hook 失败路径埋点 · 对齐 CC toolExecution.ts:1639-1689.
     *
     * @param t      当前 TrackedTool (工具返回 error result)
     * @param result 失败 ToolResult
     * @param t0     executeAsync 入口时间 (ms epoch)
     */
    private void emitPostToolUseFailureAnalytics(TrackedTool t, ToolResult result, long t0) {
        if (telemetry == null || t.tool == null) return;
        long durationMs = System.currentTimeMillis() - t0;
        String toolNameRaw = t.tool.name() != null ? t.tool.name() : t.call.name();
        // [R32-b12 Fix-v3 P1-4] MCP server tool 脱敏
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        ToolDecisionInfo decisionInfo = toolDecisions.get(t.call.id());
        String mcpServerScope = resolveMcpServerScope(toolNameRaw, t.tool);
        String dc = result.data() instanceof String s ? s : String.valueOf(result.data());
        String errorMsg = (dc != null && !dc.isEmpty()) ? dc : "tool error";

        // Statsig/1P: tengu_tool_use_error
        Map<String, Object> errAttrs = new java.util.HashMap<>();
        errAttrs.put("toolName", toolName);
        errAttrs.put("toolUseID", McpServerToolSanitizer.sanitize(t.call.id()));
        errAttrs.put("error", errorMsg);
        // [Session I P3-3] errorCategory 注入失败事件 · 从 ToolResult.errorCategory() 取
        //   (PostToolUseFailure 路径, ToolResult 已构造好). Pattern #11: 即使 null 也 emit.
        errAttrs.put("errorCategory", t.errorCategory);
        if (decisionInfo != null) errAttrs.put("decisionSource", decisionInfo.source());
        telemetry.recordEvent("tengu_tool_use_error", errAttrs);

        // OTel: tool_result event (success=false)
        Map<String, Object> oTelAttrs = new java.util.HashMap<>();
        oTelAttrs.put("tool_name", toolName);
        oTelAttrs.put("use_id", McpServerToolSanitizer.sanitize(t.call.id()));
        oTelAttrs.put("success", "false");
        oTelAttrs.put("duration_ms", String.valueOf(durationMs));
        oTelAttrs.put("error", errorMsg);
        // [Session I P3-3] errorCategory 注入 OTel event · Pattern #11 即使 null 也 emit.
        oTelAttrs.put("error_category", t.errorCategory);
        if (decisionInfo != null) {
            oTelAttrs.put("decision_source", decisionInfo.source());
            oTelAttrs.put("decision_type", decisionInfo.decision());
        }
        if (mcpServerScope != null) oTelAttrs.put("mcp_server_scope", mcpServerScope);
        String toolInputJson = telemetry.extractToolInputForTelemetry(t.call.input());
        if (toolInputJson != null) oTelAttrs.put("tool_input", toolInputJson);
        telemetry.logOTelEvent("tool_result", oTelAttrs);
    }

    /**
     * [Session H P2-5] McpAuthError → appState mcp.clients needs-auth 降级 ·
     * 对齐 CC toolExecution.ts:1601-1629.
     *
     * <p>CC 三条件:
     * <ol>
     *   <li>{@code prevState.mcp.clients.findIndex(c => c.name === serverName) === -1}
     *       → 返回 prevState (no-op)</li>
     *   <li>找到但 {@code type !== 'connected'} → 返回 prevState (不覆盖其他状态)</li>
     *   <li>否则按 CC toolExecution.ts:1616-1620 <b>显式重建</b>
     *       {@code {name, type:'needs-auth', config: existingClient.config}} —
     *       CC 是重建对象（新字面量）而非"保留全部字段再覆盖"，client 携带的其余
     *       字段（如 status/custom）必须被丢弃（reflector-H R-2 处置）</li>
     * </ol>
     *
     * <p>Java 端 appState 是 {@code Map<String,Object>} 函数式更新 (LlmAgentLoop.setAppState
     * 同款语义). AppState 无 'mcp' key 时按 CC 结构惰性创建空 clients 列表 → 三条件
     * 后仍返回 prev (与 CC 空 clients 行为一致).
     *
     * @param ctx  工具调用上下文 (setAppState 消费者; null/未注入 → no-op)
     * @param err  McpAuthError (携带 serverName)
     */
    private static void degradeMcpClientToNeedsAuth(ToolUseContext ctx, McpAuthError err) {
        if (ctx == null || ctx.setAppState() == null) {
            return;
        }
        String serverName = err.serverName();
        ctx.setAppState().accept(prev -> {
            Map<String, Object> prevMap = prev == null ? Map.of() : prev;
            // 惰性读取 / 创建 CC 结构: appState.mcp.clients (List<Map>)
            Object mcpObj = prevMap.get("mcp");
            List<Map<String, Object>> clients = new ArrayList<>();
            if (mcpObj instanceof Map<?, ?> mcpMap) {
                Object clientsObj = mcpMap.get("clients");
                if (clientsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> clientMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) clientMap);
                            clients.add(copy);
                        }
                    }
                }
            }
            // 条件 1: 按 name 查不到 → 返回 prev (CC toolExecution.ts:1607-1609)
            int existingIndex = -1;
            Map<String, Object> existing = null;
            for (int i = 0; i < clients.size(); i++) {
                Map<String, Object> client = clients.get(i);
                if (serverName != null && serverName.equals(client.get("name"))) {
                    existingIndex = i;
                    existing = client;
                    break;
                }
            }
            if (existingIndex == -1) {
                return prev;
            }
            // 条件 2: type !== 'connected' → 返回 prev (CC toolExecution.ts:1611-1614)
            if (existing == null || !"connected".equals(existing.get("type"))) {
                return prev;
            }
            // 条件 3: 按 CC toolExecution.ts:1616-1620 显式重建 {name, type:'needs-auth',
            // config: existing.config} 三字段 — CC 是重建对象而非"保留全部字段再覆盖",
            // client 携带的其余字段 (如 status/custom) 必须被丢弃 (reflector-H R-2).
            Map<String, Object> updated = new LinkedHashMap<>();
            updated.put("name", serverName);
            updated.put("type", "needs-auth");
            updated.put("config", existing.get("config"));
            clients.set(existingIndex, updated);
            log.info("MCP 服务器 {} 需要重新授权, 已降级 needs-auth (按 CC 重建 name/type/config 三字段)",
                serverName);
            Map<String, Object> newMcp = new LinkedHashMap<>();
            if (mcpObj instanceof Map<?, ?> mcpMap) {
                mcpMap.forEach((k, v) -> newMcp.put(String.valueOf(k), v));
            }
            newMcp.put("clients", clients);
            Map<String, Object> next = new LinkedHashMap<>(prevMap);
            next.put("mcp", newMcp);
            return next;
        });
    }

    /**
     * [R32-b12 D-5] executor throw 失败路径埋点 (catch (Throwable th) 分支).
     *
     * @param t             当前 TrackedTool
     * @param th            抛出的异常
     * @param durationMs    工具执行耗时
     * @param errorCategory [Session I P3-3] CC ToolResult.errorCategory() — 错误分类 (abort/validation/
     *                      permission/execution/io 等, null 表示未分类). Pattern #11: 即使 null 也
     *                      emit, telemetry 真实反映 null 状态, 不早返.
     */
    private void emitFailureTelemetry(TrackedTool t, Throwable th, long durationMs, String errorCategory) {
        if (telemetry == null || t.tool == null) return;
        String toolNameRaw = t.tool.name() != null ? t.tool.name() : t.call.name();
        // [R32-b12 Fix-v3 P1-4] MCP server tool 脱敏
        String toolName = McpServerToolSanitizer.sanitize(toolNameRaw);
        ToolDecisionInfo decisionInfo = toolDecisions.get(t.call.id());
        String errorMsg = th != null && th.getMessage() != null ? th.getMessage() : "executor error";

        Map<String, Object> errAttrs = new java.util.HashMap<>();
        errAttrs.put("toolName", toolName);
        errAttrs.put("toolUseID", McpServerToolSanitizer.sanitize(t.call.id()));
        errAttrs.put("error", errorMsg);
        // [Session I P3-3] errorCategory 注入失败事件 · Pattern #11 即使 null 也 emit.
        errAttrs.put("errorCategory", errorCategory);
        telemetry.recordEvent("tengu_tool_use_error", errAttrs);

        Map<String, Object> oTelAttrs = new java.util.HashMap<>();
        oTelAttrs.put("tool_name", toolName);
        oTelAttrs.put("use_id", McpServerToolSanitizer.sanitize(t.call.id()));
        oTelAttrs.put("success", "false");
        oTelAttrs.put("duration_ms", String.valueOf(durationMs));
        oTelAttrs.put("error", errorMsg);
        // [Session I P3-3] errorCategory 注入 OTel event · Pattern #11 即使 null 也 emit.
        oTelAttrs.put("error_category", errorCategory);
        if (decisionInfo != null) {
            oTelAttrs.put("decision_source", decisionInfo.source());
            oTelAttrs.put("decision_type", decisionInfo.decision());
        }
        telemetry.logOTelEvent("tool_result", oTelAttrs);
    }

    /**
     * 解析 fileExtension · 优先用 file_path input 字段, 否则尝试 bash command (CC metadata.ts:323-360).
     */
    private String resolveFileExtension(String toolName, JsonNode input) {
        if (input == null) return null;
        // 优先 file_path (Edit/Write/Read 等工具)
        JsonNode filePathNode = input.get("file_path");
        if (filePathNode != null && filePathNode.isTextual()) {
            return FileExtensionExtractor.getFileExtensionForAnalytics(filePathNode.textValue());
        }
        // bash command (Bash 工具)
        if ("Bash".equals(toolName)) {
            JsonNode commandNode = input.get("command");
            if (commandNode != null && commandNode.isTextual()) {
                JsonNode simFilePathNode = input.get("_simulatedFilePath");
                String simFilePath = simFilePathNode != null && simFilePathNode.isTextual()
                    ? simFilePathNode.textValue() : null;
                return FileExtensionExtractor.getFileExtensionsFromBashCommand(
                    commandNode.textValue(), simFilePath);
            }
        }
        return null;
    }

    /**
     * 解析 MCP server scope · 对齐 CC mcp/utils.ts:413-436.
     *
     * <p>[IMP-E1 DC-2] McpServerInfo 收敛 2 字段后 scope 不再经 mcpClients map 承载，
     * 签名收敛为 (toolName, tool)（claude_ai fallback 判定；配置派生 scope 登记为受控限制）。
     */
    private String resolveMcpServerScope(String toolName, Tool tool) {
        return McpServerScope.getMcpServerScopeFromToolName(toolName, tool);
    }
}

package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.AgentState.ExitReason;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.ModelCaller;
import com.nexusai.application.agent.loop.ModelRequest;
import com.nexusai.application.agent.loop.ModelResponse;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.hook.GenericHook.HookOutcome;
import com.nexusai.application.agent.permission.hook.GenericHook.HookResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.impl.SyntheticOutputTool;
import com.nexusai.application.agent.skill.ArgumentSubstitution;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Exec Agent Hook · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/execAgentHook.ts} (340 行).
 *
 * <p>WHY: CC agent hook 启动一个独立子 agent 多轮调 LLM (带工具) 验证某条件 (如 "验证测试已运行且通过"),
 * 强制通过 {@code SyntheticOutputTool} 返回 {@code {ok, reason}}, 按 outcome 4 态返回 HookResult.
 *
 * <p><b>H7-arch Phase 3: 接入 queryLoop（单一循环源）</b>。对齐 CC {@code execAgentHook.ts:167}
 * {@code for await (const message of query({...querySource:'hook_agent'}))} -- CC agent hook 复用主
 * {@code query()}，不自建循环。Java 等价：fresh carrier（隔离可变状态 + 隔离 toolRegistry +
 * nonInteractiveSession=true）走 {@link LlmAgentLoop#queryLoop}，自动获得主循环已对齐的压缩/预算/
 * stopHook/fallback 能力。改造前是 stream 手写降级版（{@code LlmProvider.stream} 直接调，不执行工具，
 * 不接入主循环），已删。
 *
 * <h2>CC 真源行为映射 (主 agent grep 实证 execAgentHook.ts, 不抄探查报告行号)</h2>
 * <ul>
 *   <li>$ARGUMENTS 替换 (:60 addArgumentsToPrompt) -> {@link #substituteArguments}</li>
 *   <li>timeout 默认 60s (:75) -> {@link #resolveTimeoutMs}</li>
 *   <li>createStructuredOutputTool (:89) -> {@link #createHookStructuredOutputTool}</li>
 *   <li>父工具过滤 + StructuredOutput (:93-105) -> {@link #buildEffectiveRegistry}</li>
 *   <li>systemPrompt 含 transcriptPath (:107-116) -> {@link #buildSystemPrompt}</li>
 *   <li>{@code hook.model ?? getSmallFastModel()} (:118) -> {@link #resolveModel}
 *       （[R10] 双空时回落 getSmallFastModel env 链，不产出空串进 QueryParams）</li>
 *   <li>MAX_AGENT_TURNS=50 (:119) -> {@code state.maxTurns(MAX_AGENT_TURNS)} +
 *       <b>[R9] ExecAgentHook 层 assistant 消息计数熔断</b>（经 deps.callModel 包装
 *       onAssistantMessage：第 50 条消息到达 → hookAbort.abort("max_agent_turns")，其 tool call
 *       不执行；CC :197-207 语义，见 {@link #exec} 步骤 9 注释）</li>
 *   <li>asAgentId('hook-agent-${UUID}') (:122) -> {@link #generateHookAgentId()}（派生 UUID 供类型化字段）</li>
 *   <li>isNonInteractiveSession:true (:133) -> base TUC {@code withNonInteractiveSession(true)}</li>
 *   <li>abort 隔离 (:76-85) -> hookAbortController + 超时 + parent signal -> {@code state.cancel()}</li>
 *   <li>registerStructuredOutputEnforcement (:157-160) -> {@link StructuredOutputEnforcementHook}
 *       （[H13] 补 5s 重入超时，对齐 hookHelpers.ts:81）</li>
 *   <li>for-await query() (:167-227) -> {@link LlmAgentLoop#queryLoop}(HOOK_AGENT)
 *       （deps 为 ExecAgentHook 内联 LoopDeps：context() 委托 shared ctx + callModel 包装计数熔断）</li>
 *   <li>structured_output 检测 (:212-226) -> {@link #extractStructuredOutput}（扫 tool 消息 structuredOutput
 *       attachment 字段，替代 tool_call 反向扫，[H13]）</li>
 *   <li>analytics (:242/257/287/319) -> {@code tengu_agent_stop_hook_max_turns/_error/_success}（[H13] 补）</li>
 *   <li>outcome 4 态 (:248/275/293/325) -> {@link #resolveOutcome}（[H13] 补 message attachment + analytics）</li>
 *   <li>清理 session hook (:233) -> finally unregister enforcement hook</li>
 * </ul>
 *
 * <h2>Java 适配（对齐 H2 降级风格 + [H13] 补齐 + H13-v2 对抗核验登记）</h2>
 * <ul>
 *   <li><b>transcriptPath</b>: CC :108 systemPrompt 含 transcriptPath + :146-149 Read(transcriptPath)
 *       session rule。Java ExecAgentHook 接收 transcriptPath 入参注入 systemPrompt（null 时省略该行）。
 *       Read(transcriptPath) session rule 降级已登记 J.md H13-GAP-1（Java 无父 permission context 继承，
 *       靠 carrier 共享父工具集 Read；DONT_ASK+空规则会拒绝 hook 工具，需父规则继承后才能对齐）。</li>
 *   <li><b>thinkingConfig:disabled</b> (:134) + <b>mode:'dontAsk'</b> (:146):
 *       [CCJ-EXEC-08] thinkingConfig 已接线 —— LlmAgentLoop :3337 ModelRequest 构造按
 *       querySource==HOOK_AGENT 注入 {@code params.thinkingConfig()}（默认 disabled）→ ModelCaller
 *       → provider stream 新重载（OpenAiSdkProvider 发射 thinking:{type:'disabled'}；
 *       Anthropic 省略参数=disabled 等价）。mode:'dontAsk' 经 buildHookPermissionContext（父规则继承）。</li>
 *   <li><b>attachment message</b>: [H13] 已补 CC success → hook_success attachment（CC :296-302）、
 *       non_blocking_error → hook_non_blocking_error attachment（CC :328-336），不再恒 null。</li>
 *   <li><b>analytics</b>: [H13] 已补 tengu_agent_stop_hook_max_turns / _error(type1,2) / _success，经
 *       {@link com.nexusai.application.agent.telemetry.Telemetry#recordEvent}（CC logEvent 等价）。</li>
 *   <li><b>outcome 映射 exitReason</b>: Java loop 在 provider 报错时设 exitReason=STREAM_ERROR 并返回
 *       （不抛异常，与 CC query() 抛错不同）。故 resolveOutcome 按 exitReason 区分：error 退出 ->
 *       non_blocking_error + error type2 analytics（CC :316-338）；MAX_TURNS -> cancelled +
 *       max_turns analytics（CC :238-252）；ABORTED -> cancelled 无 analytics（CC :308-313）；其余 ->
 *       cancelled + error type1 analytics（CC :254-267）。</li>
 *   <li><b>budgetTracker</b>: P3-③ 后 budgetTracker 已持久化于 AgentState（loop 入口优先复用
 *       state.budgetTracker()），不再 fresh carrier init。</li>
 * </ul>
 *
 * <p><b>接线状态</b>: HookRegistry 已接线 — {@code executeConfiguredAgent} (配置 AgentHook 分发)
 * 注入本类并调用 {@code exec} (对齐 CC execAgentHook.ts:36-339)。
 *
 * @see AgentHook
 * @see GenericHook.HookResult
 * @see StructuredOutputEnforcementHook
 * @see com.nexusai.application.agent.LlmAgentLoop#queryLoop
 * @since Session H7 (P2) · Phase 3 接入 queryLoop
 */
@Component
public class ExecAgentHook {

    private static final Logger log = LoggerFactory.getLogger(ExecAgentHook.class);

    /** CC SyntheticOutputTool.ts:20 {@code SYNTHETIC_OUTPUT_TOOL_NAME = 'StructuredOutput'}. */
    public static final String SYNTHETIC_OUTPUT_TOOL_NAME = ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME;

    /** CC execAgentHook.ts:119 {@code MAX_AGENT_TURNS = 50}. */
    public static final int MAX_AGENT_TURNS = 50;

    /** CC execAgentHook.ts:75 默认 60s ({@code hook.timeout ? hook.timeout*1000 : 60000}). */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** CC execAgentHook.ts:107-116 systemPrompt 验证任务描述（无强制调用句 — [CCJ-EXEC-16] 已删除）. */
    private static final String SYSTEM_PROMPT_HEAD = """
        You are verifying a stop condition in Claude Code. Your task is to verify that the agent completed the given plan.""";
    private static final String SYSTEM_PROMPT_TAIL = """

        Use the available tools to inspect the codebase and verify the condition.
        Use as few steps as possible - be efficient and direct.

        When done, return your result using the %s tool with:
        - ok: true if the condition is met
        - ok: false with reason if the condition is not met""".formatted(SYNTHETIC_OUTPUT_TOOL_NAME);
    /** CC execAgentHook.ts:108 transcript 路径提示行. */
    private static final String TRANSCRIPT_LINE_TEMPLATE =
        " The conversation transcript is available at: %s%nYou can read this file to analyze the conversation history if needed.";

    /** 超时调度器（daemon 线程，对齐 CC createCombinedAbortSignal 的 timeout 分量）. */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newScheduledThreadPool(
        2, r -> { Thread t = new Thread(r, "exec-agent-hook-timeout"); t.setDaemon(true); return t; });

    private final ObjectMapper objectMapper;
    private final com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory;
    private final ToolRegistry parentToolRegistry;
    private final HookRegistry hookRegistry;
    private final ProviderConfig providerConfig;
    private final String defaultFastModel;
    /** [H13] analytics 通道（可为 null）· 对齐 CC logEvent (execAgentHook.ts:242/257/287/319)。 */
    private final Telemetry telemetry;
    /**
     * [?-EX-06] ProviderService 通道 · 按 modelName 解析 enabled + model 匹配 provider →
     * {@link ProviderConfig(baseUrl, apiKey)}（参照 HookRegistry.resolvePromptProvider 模式）。
     * 可 null = 未接线（手动 new / 测试）→ 维持 {@link #providerConfig} 注入兜底。
     */
    private final ProviderService providerService;
    /**
     * [?-EX-06] provider 工厂 · 解析结果按 provider type 路由校验（对照
     * HookRegistry.resolvePromptProvider 的 factory.getProvider(config, providerType) 模式）。
     * 可 null = 未接线 → 维持 {@link #providerConfig} 注入兜底。
     */
    private final LlmProviderFactory llmProviderFactory;

    /**
     * [?-EX-05 E4 闭环] Spring 构造器注解 · 对齐 HookRegistry.defaultFastModel 同款
     * {@code @Value("${nexusai.hook.fastModel:}")} 模式。
     *
     * <p><b>[IMPL-06] @Autowired(required=false)</b>: 生产上下文无 {@link ProviderConfig} bean
     * （配置域缺口，登记 09 §?-EX-06）→ 缺省注入 null（本类空值路径已存在：telemetry null →
     * 不发射 analytics；providerConfig null → loop 无法发起 LLM → non_blocking_error，与
     * ExecPromptHook「无可用 provider 显式跳过」同属生产偏差面，见 09）。测试直接 new 不受影响。
     *
     * <p><b>[?-EX-06 实施]</b>: 新增 {@link ProviderService} + {@link LlmProviderFactory}
     * （均 @Autowired(required=false)，参照 HookRegistry.resolvePromptProvider 模式）——
     * 生产不再依赖 ProviderConfig bean：hook 执行时按 modelName 解析真实 provider 配置
     * （enabled + model 匹配 → 解密 apiKey → ProviderConfig），解析不到才回落
     * {@code providerConfig} 注入兜底（生产 null → mock → non_blocking_error，维持修复前行为）。
     *
     * @param objectMapper       JSON 处理
     * @param contextFactory     AgentLoopContext 共享工厂（P3-③ 替代 fresh carrier；hook agent 经 shared() 构造隔离 ctx）
     * @param parentToolRegistry 父工具集（过滤 ALL_AGENT_DISALLOWED_TOOLS + 加 SyntheticOutputTool）
     * @param hookRegistry       hook 注册中心（注册/注销 StructuredOutputEnforcementHook；可为 null）
     * @param providerConfig     provider 运行时配置兜底（生产缺省 null；?-EX-06 后仅解析失败时回落）
     * @param defaultFastModel   默认 fast model 名（对齐 CC :118 getSmallFastModel）· Spring 经
     *                           {@code nexusai.hook.fastModel} 属性解析（未配置 → 空串，同 HookRegistry:365；
     *                           空串时 [R10] 回落 getSmallFastModel env 链，不再产出空串进 QueryParams）
     * @param telemetry          analytics 通道（可为 null = 不发射 tengu_agent_stop_hook_* 事件）
     * @param providerService    [?-EX-06] 真实 provider 解析通道（可为 null = 未接线 → 回落 providerConfig）
     * @param llmProviderFactory [?-EX-06] provider 工厂（解析结果 type 路由校验；可为 null = 未接线）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public ExecAgentHook(ObjectMapper objectMapper,
                         com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory,
                         ToolRegistry parentToolRegistry,
                         HookRegistry hookRegistry,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         ProviderConfig providerConfig,
                         @org.springframework.beans.factory.annotation.Value("${nexusai.hook.fastModel:}")
                         String defaultFastModel,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         Telemetry telemetry,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         ProviderService providerService,
                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                         LlmProviderFactory llmProviderFactory) {
        this.objectMapper = objectMapper;
        this.contextFactory = contextFactory;
        this.parentToolRegistry = parentToolRegistry;
        this.hookRegistry = hookRegistry;
        this.providerConfig = providerConfig;
        this.defaultFastModel = defaultFastModel;
        this.telemetry = telemetry;
        this.providerService = providerService;
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * 执行 agent hook · 多轮 LLM 子循环（复用 queryLoop）· 对齐 CC execAgentHook.ts:36-339.
     *
     * <p>流程: 替换 $ARGUMENTS -> 构 effectiveRegistry(父工具过滤 + SyntheticOutput) -> fresh carrier
     * (nonInteractiveSession=true) -> AgentState(maxTurns=50 + user msg) -> abort 隔离(timeout+parent) ->
     * 注册 enforcement hook -> queryLoop(HOOK_AGENT) -> 提取 StructuredOutput {ok,reason} -> outcome 4 态.
     *
     * @param hook          agent hook 配置 (prompt/timeout/model)
     * @param hookName      hook 名 (日志用)
     * @param hookEvent     hook 事件载体 (CC hookEvent, 用于 attachment.hookEvent)
     * @param jsonInput     hook 输入 JSON (替换 $ARGUMENTS)
     * @param transcriptPath 父会话 transcript 路径 (注入 systemPrompt；null 时省略，对齐 CC :54-56)
     * @param parentAbort   父循环 abort 信号 (null = 无父级取消)
     * @param sessionId     父会话 ID（short；hook agent 复用父 sessionId，agentId 用独立 hookAgentId 隔离）
     * @param agentName     [H13] 父 agent 名 (来自 CC hookInput.agent_type, 写入 tengu_agent_stop_hook_* analytics)
     * @param parentPermCtx [对抗核验 H13-GAP-1 v3] 父 permission context (可 null)。非 null 时
     *                      hook agent 继承父 alwaysAllowRules + 追加 Read(transcriptPath) session 规则
     *                      + mode=DONT_ASK（对齐 CC execAgentHook.ts:141-153 getAppState override）；
     *                      null = 旧行为（无父规则继承, mode 保持默认）
     * @return HookResult (outcome 4 态之一)
     */
    public HookResult exec(AgentHook hook, String hookName, HookEvent hookEvent,
                           String jsonInput, String transcriptPath,
                           AbortController parentAbort, String sessionId, String agentName,
                           ToolPermissionContext parentPermCtx) {
        // CC :122 asAgentId('hook-agent-${randomUUID()}') · 唯一 agentId 命名空间隔离子循环状态.
        // [H13] 旧实现直接用 UUID.randomUUID()（generateHookAgentId 死方法）; 现经 generateHookAgentId()
        // 生成 'hook-agent-<uuid>' 前缀, 派生 UUID 供类型化字段（ToolUseContext/AgentState/EnforcementHook）。
        String hookAgentId = generateHookAgentId();
        UUID hookAgentUuid = UUID.fromString(hookAgentId.substring("hook-agent-".length()));
        // CC :51 effectiveToolUseID = toolUseID || `hook-${randomUUID()}`
        String effectiveToolUseID = "hook-" + UUID.randomUUID();
        long hookStartTime = System.currentTimeMillis();
        long timeoutMs = resolveTimeoutMs(hook.timeout());
        String modelName = resolveModel(hook.model(), defaultFastModel);
        String enforcementHookName = "structuredOutputEnforcement-" + hookAgentId;
        ScheduledFuture<?> timeoutFuture = null;
        // [E8/CCJ-EXEC-06] 父 abort 监听器引用 · CC execAgentHook.ts:229-230/:305-306
        //   removeEventListener + cleanupCombinedSignal（正常 + catch 两路径）——
        //   finally 移除防累积（旧实现注册后永不移除，长会话泄漏）。
        Consumer<AbortController> parentAbortListener = null;

        try {
            // 1. 替换 $ARGUMENTS · CC :60 addArgumentsToPrompt(hook.prompt, jsonInput)
            String processedPrompt = substituteArguments(hook.prompt(), jsonInput);
            if (log.isDebugEnabled()) {
                log.debug("ExecAgentHook: hook={} agentId={} 处理后 prompt: {}", hookName, hookAgentId, processedPrompt);
            }

            // 2. effectiveRegistry: 父工具过滤 + SyntheticOutputTool · CC :89-105
            ToolRegistry effectiveRegistry = buildEffectiveRegistry();
            if (log.isDebugEnabled()) {
                log.debug("ExecAgentHook: hook={} effectiveRegistry 工具数={}", hookName, effectiveRegistry.size());
            }

            // 3. [P3-③] base TUC + 隔离 ctx · CC :125-154 agentToolUseContext
            //    base TUC = ToolUseContext.of(hookAgentId, sessionId) + availableTools=effectiveTools
            //    + isNonInteractiveSession=true（CC :133 让 SyntheticOutputTool 接受调用）。
            //    工具隔离走 TUC（对齐 CC toolUseContext.options.tools），不再 fresh carrier 换 toolRegistry。
            if (contextFactory == null) {
                throw new IllegalStateException("contextFactory not injected");
            }
            List<Tool> effectiveTools = effectiveRegistry.all();
            // 7a. hook 独立 abort controller · CC :75-85 createCombinedAbortSignal(parent + timeout)。
            //     提前声明以便注入 base TUC（H13-GAP-4 v3: 超时硬中断把 hookAbort 透传 provider stream）。
            AbortController hookAbort = new AbortController();
            // [对抗核验 H13-GAP-1 v3] hook TUC = 父规则 ∪ Read(transcriptPath) + mode=DONT_ASK ·
            //   对齐 CC execAgentHook.ts:141-153 getAppState() override。
            //   parentPermCtx 非 null → 继承父 alwaysAllowRules + 追加 SESSION Read 规则 + DONT_ASK
            //   （R26 hook 层把未命中规则的 ask 工具 deny, 对齐 CC dontAsk→deny 语义）；
            //   null → 旧行为（无父规则, mode=DEFAULT, 不触发 dontAsk deny —— 避免空规则拒绝全部工具）。
            ToolPermissionContext hookPermCtx = buildHookPermissionContext(parentPermCtx, transcriptPath);
            PermissionMode hookMode = hookPermCtx != null ? PermissionMode.DONT_ASK : PermissionMode.DEFAULT;
            ToolUseContext baseTuc = ToolUseContext.of(hookAgentUuid, sessionId, hookMode,
                effectiveTools, "", hookAbort, List.of(), hookPermCtx, hookMode)
                .withNonInteractiveSession(true);

            // 4. systemPrompt 含 transcriptPath · CC :107-116
            String systemPrompt = buildSystemPrompt(transcriptPath);

            // 5. AgentState + maxTurns=50 + user message · CC :119, :67-68
            AgentState state = new AgentState(systemPrompt, sessionId, hookAgentUuid);
            state.maxTurns(MAX_AGENT_TURNS);
            state.appendMessage(userMessage(processedPrompt));

            // 7. abort 隔离 · CC :75-85 createCombinedAbortSignal(parent + timeout)
            //     hookAbort 已在步骤 3 提前声明并注入 base TUC（供 loop 透传 provider 硬中断）。
            timeoutFuture = scheduleTimeout(hookAbort, timeoutMs);
            if (parentAbort != null) {
                // [E8/CCJ-EXEC-06] 引用捕获供 finally removeOnCancel（CC removeEventListener）
                parentAbortListener = ac -> {
                    if (!hookAbort.isCancelled()) {
                        hookAbort.abort("parent_cancelled");
                    }
                };
                parentAbort.onCancel(parentAbortListener);
            }
            // hookAbort -> state.cancel() · loop L1846 state.cancelled() 检查后 break（exitReason=ABORTED）
            hookAbort.onCancel(ac -> {
                if (!state.cancelled()) {
                    state.cancel();
                    log.info("ExecAgentHook: hook={} abort 触发 state.cancel", hookName);
                }
            });

            // 8. 注册 StructuredOutputEnforcementHook · CC :157-160 registerStructuredOutputEnforcement
            //    按 hookAgentId 自过滤（隔离父循环），finally 注销（CC :233 clearSessionHooks）
            if (hookRegistry != null) {
                try {
                    hookRegistry.register(enforcementHookName,
                        new StructuredOutputEnforcementHook(hookAgentUuid, state),
                        HookEventType.STOP);
                } catch (Exception e) {
                    log.warn("ExecAgentHook: hook={} 注册 enforcement hook 失败: {}", hookName, e.getMessage());
                }
            }

            log.info("ExecAgentHook: hook={} queryLoop start agentId={} model={} timeoutMs={} tools={}",
                hookName, hookAgentId, modelName, timeoutMs, effectiveRegistry.size());

            // 9. queryLoop · CC :167-227 for-await query({querySource:'hook_agent'})
            // [H7-arch Phase 5-2 P3-③] 接口层：HookLoopDeps 持 AgentLoopContext（factory.shared() 隔离 ctx），
            //   工具隔离 + nonInteractiveSession 经 base TUC（availableTools=effectiveTools + isNonInteractiveSession=true）。
            // [R9] 50 turn 熔断语义（CC execAgentHook.ts:197-207）: CC 按 assistant 消息数计 turnCount，
            //   第 50 条消息到达即 {@code hookAbortController.abort() + break}（该消息的 tool call 不再执行），
            //   模型调用数保持 50。Java 端在 ExecAgentHook 层包装计数（不碰 LlmAgentLoop 主循环，
            //   其他 QuerySource 语义零变化）：经本 deps 的 callModel 包装 onAssistantMessage —— 第 50 条
            //   消息到达 → hookAbort.abort("max_agent_turns") → state.cancel() → loop 走 aborted_streaming
            //   路径（LlmAgentLoop:3345）生成 synthetic error 退出（exitReason=ABORTED），第 50 轮 tool
            //   call 不执行。流式工具执行架构下（真 provider 在流中已 add 工具）已启动的工具可能完成，
            //   与 timeout/parent abort 既有竞态一致（CC 顺序执行工具，Java 流式并行 —— 既有架构差异）。
            // [IMP-D F4/M-07] hook agent workspaceDir 注入会话 projectRoot（修 M-07 user.dir 兜底链）。
            //   HOOK_EXECUTOR 线程经 IMP-C withSessionProjectRoot 回放后读 holder 即会话值。
            AgentLoopContext sharedCtx = contextFactory.shared(AutoMemPaths.currentSessionProjectRoot());
            AtomicInteger assistantMessageCounter = new AtomicInteger(0);
            AtomicBoolean maxTurnsBreakerFired = new AtomicBoolean(false);
            LoopDeps deps = new LoopDeps() {
                @Override public AgentLoopContext context() { return sharedCtx; }
                @Override public ModelResponse callModel(ModelRequest request) {
                    Consumer<AssistantMessage> original = request.onAssistantMessage();
                    ModelRequest countingRequest = new ModelRequest(
                        request.config(), request.modelName(), request.blocks(),
                        request.querySource(), request.messages(), request.tools(),
                        request.maxOutputTokensOverride(), request.taskBudget(), request.effortValue(),
                        // [CCJ-EXEC-08] thinkingConfig 透传（hook agent 请求携带 disabled →
                        //   ModelCaller → provider stream 新重载；主循环 null 零变化）
                        request.thinkingConfig(),
                        request.onChunk(),
                        msg -> {
                            // 先透传 loop 回调（capturedMsg/needsFollowUp 正常建立），再计数熔断
                            original.accept(msg);
                            if (assistantMessageCounter.incrementAndGet() >= MAX_AGENT_TURNS
                                    && !hookAbort.isCancelled()) {
                                maxTurnsBreakerFired.set(true);
                                log.info("ExecAgentHook: hook={} 第 {} 条 assistant 消息到达, 触发 MAX_AGENT_TURNS 熔断 abort (CC execAgentHook.ts:197-207)",
                                    hookName, MAX_AGENT_TURNS);
                                hookAbort.abort("max_agent_turns");
                            }
                        },
                        request.onToolCallComplete(), request.onReasoningChunk(),
                        request.onStreamingFallback(), request.onError(), request.onComplete(),
                        request.abortController());
                    return ModelCaller.call(context(), countingRequest);
                }
            };
            // [?-EX-06] 真实 provider 配置（按 modelName 解析；解析不到回落注入兜底）
            ProviderConfig effectiveConfig = resolveProviderConfig(modelName);
            List<String> consumedCommandUuids = new ArrayList<>();
            com.nexusai.application.agent.loop.QueryParams queryParams =
                com.nexusai.application.agent.loop.QueryParams.forLoop(
                    state.messages(), systemPrompt, baseTuc,
                    QuerySource.HOOK_AGENT, modelName, MAX_AGENT_TURNS, null, null, null, null,
                    deps, effectiveConfig);
            LoopResult result = LlmAgentLoop.queryLoop(queryParams, state, consumedCommandUuids);

            // 10. 提取 StructuredOutput {ok,reason} · CC :212-225 attachment.structured_output
            AgentState finalState = result.finalState();
            JsonNode structuredOutput = extractStructuredOutput(finalState);
            if (log.isInfoEnabled()) {
                log.info("ExecAgentHook: hook={} queryLoop done turns={} exitReason={} structured={} 耗时={}ms",
                    hookName, result.totalTurns(),
                    finalState != null ? finalState.exitReason() : null,
                    structuredOutput != null, System.currentTimeMillis() - hookStartTime);
            }

            // 11. outcome 4 态 · CC :236-338
            return resolveOutcome(structuredOutput, finalState, hook, hookName,
                agentName, hookStartTime, result.totalTurns(), maxTurnsBreakerFired.get(),
                effectiveToolUseID, hookEvent);

        } catch (Throwable t) {
            // 外层 catch · CC :316-338 outcome='non_blocking_error'
            // [H13] 补 tengu_agent_stop_hook_error (errorType=2) analytics + hook_non_blocking_error attachment
            String errorMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (log.isErrorEnabled()) {
                log.error("ExecAgentHook: hook={} 执行异常, outcome=non_blocking_error: {}", hookName, errorMsg, t);
            }
            emitAnalytics("tengu_agent_stop_hook_error", hookStartTime, agentName,
                Map.of("errorType", 2));
            return nonBlockingError(hookName, effectiveToolUseID, hookEvent,
                "Error executing agent hook: " + errorMsg);
        } finally {
            // CC :229-233 清理 · 取消超时 + 注销 enforcement hook + [E8] 移除父 abort 监听器
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            if (parentAbortListener != null) {
                parentAbort.removeOnCancel(parentAbortListener);
            }
            if (hookRegistry != null) {
                try {
                    hookRegistry.unregister(enforcementHookName);
                } catch (Exception e) {
                    log.warn("ExecAgentHook: hook={} 注销 enforcement hook 失败: {}", hookName, e.getMessage());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // effectiveRegistry 构建 · CC :89-105
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建 hook agent 隔离工具集 · 对齐 CC execAgentHook.ts:93-105.
     *
     * <p>CC: {@code toolUseContext.options.tools} 过滤掉已有 StructuredOutput（避免不同 schema 冲突）+
     * {@code ALL_AGENT_DISALLOWED_TOOLS}（防递归 spawn subagent / plan mode / 询问用户）+ structuredOutputTool。
     *
     * <p>Java: parentToolRegistry.all() 过滤上述两项 + 注册 hook-schema SyntheticOutputTool +
     * {@code setSkipSpecialToolsFilter(true)}（让 StructuredOutput 暴露给 LLM）。
     */
    private ToolRegistry buildEffectiveRegistry() {
        ToolRegistry effectiveRegistry = new ToolRegistry();
        for (Tool t : parentToolRegistry.all()) {
            if (t == null) continue;
            String name = t.name();
            // CC :93-95 过滤已有 StructuredOutput（避免不同 schema 冲突）
            if (SYNTHETIC_OUTPUT_TOOL_NAME.equals(name)) continue;
            // CC :101-103 过滤 ALL_AGENT_DISALLOWED_TOOLS
            if (ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS.contains(name)) continue;
            effectiveRegistry.register(t);
        }
        // CC :89 createStructuredOutputTool() · hook 专用 {ok,reason} schema
        effectiveRegistry.register(createHookStructuredOutputTool());
        // 让 StructuredOutput（SPECIAL_TOOLS 成员）暴露给 LLM schema
        effectiveRegistry.setSkipSpecialToolsFilter(true);
        return effectiveRegistry;
    }

    private Tool createHookStructuredOutputTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode okProp = props.putObject("ok");
        okProp.put("type", "boolean");
        okProp.put("description", "Whether the condition was met");
        ObjectNode reasonProp = props.putObject("reason");
        reasonProp.put("type", "string");
        reasonProp.put("description", "Reason, if the condition was not met");
        schema.putArray("required").add("ok");  // CC :57 required: ['ok']
        // [IT-5] 广告 additionalProperties=false 保留 · 逐字对齐 CC hookHelpers.ts:58
        // 手写 inputJSONSchema 的 additionalProperties:false（仅广告层）。
        // 运行时放行未知键由 SyntheticOutputTool 类级 unknownKeysPolicy()=PASSTHROUGH
        // 承担（CC hookResponseSchema = z.object hookHelpers.ts:17，strip 不报错；
        // strip 与 passthrough 对 validator 语义等价，均不拒绝）。
        schema.put("additionalProperties", false);  // CC :58
        // [CCJ-EXEC-16] 强制调用指令载体 = 工具 prompt（CC hookHelpers.ts:60-62 逐字文本）——
        //   ToolRegistry.toOpenAiToolsArray 序列化 description = prompt() ?? description()（api.ts:171），
        //   systemPrompt 不再附加调用句（execAgentHook.ts:107-116 无该句）。
        return new SyntheticOutputTool(schema,
            "Use this tool to return your verification result. "
                + "You MUST call this tool exactly once at the end of your response.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // systemPrompt 构建 · CC :107-116
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建 systemPrompt · 对齐 CC execAgentHook.ts:107-116.
     *
     * <p>含 transcriptPath 时注入 "The conversation transcript is available at: ${transcriptPath}"
     * （CC :108）；null 时省略（降级 H7-1）。
     */
    private static String buildSystemPrompt(String transcriptPath) {
        if (transcriptPath == null || transcriptPath.isBlank()) {
            return SYSTEM_PROMPT_HEAD + SYSTEM_PROMPT_TAIL;
        }
        return SYSTEM_PROMPT_HEAD + String.format(TRANSCRIPT_LINE_TEMPLATE, transcriptPath) + SYSTEM_PROMPT_TAIL;
    }

    /**
     * [对抗核验 H13-GAP-1 v3] 构造 hook agent 专属 permission context · 对齐 CC
     * execAgentHook.ts:141-153 {@code getAppState()} override:
     * <pre>
     *   toolPermissionContext: {
     *     ...appState.toolPermissionContext,          // 继承父全部规则集
     *     mode: 'dontAsk',
     *     alwaysAllowRules: {
     *       ...parent.alwaysAllowRules,
     *       session: [...existingSessionRules, `Read(/${transcriptPath})`],
     *     },
     *   }
     * </pre>
     *
     * <p>WHY (J.md H13-GAP-1 登记): 旧 Java 实现 {@code ToolUseContext.of(hookAgentUuid, sessionId)}
     * 空规则集 + 无父继承 → DONT_ASK 会拒绝 hook agent 全部工具（Bash/Grep/Read 均需 ask）。
     * 继承父 alwaysAllowRules 保底 + 追加 Read(transcriptPath) SESSION 规则后, hook agent 能读
     * transcript 验证条件, 其余工具按父 allow 规则放行、未命中规则的在 DONT_ASK 下被 deny。
     *
     * @param parentPermCtx  父 permission context（非 null 才继承；null → 返回 null = 旧行为）
     * @param transcriptPath transcript 路径（非空才追加 Read 规则）
     * @return hook permCtx（mode=DONT_ASK + 父规则 ∪ Read）；parentPermCtx==null → null
     */
    static ToolPermissionContext buildHookPermissionContext(
            ToolPermissionContext parentPermCtx, String transcriptPath) {
        if (parentPermCtx == null) {
            return null;
        }
        Map<PermissionRuleSource, Set<PermissionRule>> allowRules =
            new EnumMap<>(PermissionRuleSource.class);
        allowRules.putAll(parentPermCtx.alwaysAllowRules());
        if (transcriptPath != null && !transcriptPath.isBlank()) {
            // 追加 Read(/transcriptPath) 到 SESSION 桶 · 父桶可能是不可变 Set.of → 必须拷贝为可变集合再 add。
            Set<PermissionRule> sessionSet = new LinkedHashSet<>(
                allowRules.getOrDefault(PermissionRuleSource.SESSION, Set.of()));
            sessionSet.add(new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
                PermissionRuleValue.withContent("Read", "/" + transcriptPath)));
            allowRules.put(PermissionRuleSource.SESSION, sessionSet);
        }
        return new ToolPermissionContext(
            PermissionMode.DONT_ASK,
            allowRules,
            parentPermCtx.alwaysDenyRules(),
            parentPermCtx.alwaysAskRules(),
            parentPermCtx.additionalWorkingDirectories(),
            parentPermCtx.isBypassPermissionsModeAvailable(),
            parentPermCtx.isAutoModeAvailable(),
            parentPermCtx.strippedDangerousRules(),
            parentPermCtx.shouldAvoidPermissionPrompts(),
            parentPermCtx.awaitAutomatedChecksBeforeDialog(),
            parentPermCtx.prePlanMode()
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // StructuredOutput 提取 · CC :212-225
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从 finalState.messages() 提取 StructuredOutput attachment 的 {ok,reason} · 对齐 CC
     * execAgentHook.ts:212-226 {@code message.type === 'attachment' && message.attachment.type === 'structured_output'}.
     *
     * <p><b>[H13] 检测路径修正（替代 tool_call 反向扫）</b>: CC 检测的是 SyntheticOutputTool 返回的
     * structured_output attachment（Java 等价: Role.tool 消息的 {@link ChatMessageDto#structuredOutput()} 字段,
     * 由 ToolResultApplier.recordStructuredOutput → AgentLoopContext.takeStructuredOutput 注入）。
     * 旧实现反向扫 assistant.tool_calls 解析 arguments —— agent 可能多次调用 StructuredOutput, 取到"最后一次"
     * schema 非法调用时 hook 验证条件永远判失败（探查 §C 2.3-4）。
     *
     * <p>schema 校验失败 → 跳过继续扫（CC :216 {@code if (parsed.success)} 不成立则 continue loop），
     * 不是直接 non_blocking_error。返回第一个校验通过的 attachment data。
     *
     * @return StructuredOutput 的 {ok,reason} JsonNode；null = 无合法 structured output
     */
    private JsonNode extractStructuredOutput(AgentState finalState) {
        if (finalState == null || finalState.messages() == null) return null;
        List<ChatMessageDto> messages = finalState.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = messages.get(i);
            if (msg == null || msg.role() != Role.tool) continue;
            Map<String, Object> structured = msg.structuredOutput();
            if (structured == null || structured.isEmpty()) continue;
            JsonNode node = objectMapper.valueToTree(structured);
            // CC :216 hookResponseSchema().safeParse —— 校验失败跳过（continue loop 的 Java 等价）
            String schemaError = validateHookResponseSchema(node);
            if (schemaError == null) {
                return node;
            }
            if (log.isDebugEnabled()) {
                log.debug("ExecAgentHook: structured_output attachment schema 校验失败, 跳过继续: {}", schemaError);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // outcome 解析 · CC :236-338
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 按 StructuredOutput + exitReason 解析 outcome 4 态 · 对齐 CC execAgentHook.ts:236-338.
     *
     * <p><b>[H13] 语义补齐</b>:
     * <ul>
     *   <li>structuredOutput 非空（附件检测已通过 schema）: 按 ok 分流 success/blocking（CC :271-303）</li>
     *   <li>structuredOutput 空 + error 退出(STREAM_ERROR/STREAM_TIMEOUT/...): non_blocking_error +
     *       tengu_agent_stop_hook_error(errorType=2) analytics（CC :316-338）</li>
     *   <li>structuredOutput 空 + MAX_TURNS 或 (ABORTED + maxTurnsBreakerFired): cancelled +
     *       tengu_agent_stop_hook_max_turns analytics（CC :238-252）—— [R9] 熔断走 abort 路径
     *       （exitReason=ABORTED，loop aborted_streaming 退出），仍按 CC hitMaxTurns 发 max_turns analytics</li>
     *   <li>structuredOutput 空 + ABORTED（非熔断，如父 abort/timeout）: cancelled 无 analytics（CC :308-313 内层 catch）</li>
     *   <li>structuredOutput 空 + 其他: cancelled + tengu_agent_stop_hook_error(errorType=1) analytics（CC :254-267）</li>
     * </ul>
     */
    private HookResult resolveOutcome(JsonNode structuredOutput, AgentState finalState,
                                      AgentHook hook, String hookName,
                                      String agentName, long hookStartTime, int turnCount,
                                      boolean maxTurnsBreakerFired,
                                      String effectiveToolUseID, HookEvent hookEvent) {
        if (structuredOutput != null) {
            boolean ok = structuredOutput.path("ok").asBoolean();
            JsonNode reasonNode = structuredOutput.path("reason");
            String reason = (reasonNode.isMissingNode() || reasonNode.isNull()) ? null : reasonNode.asText(null);

            if (!ok) {
                // CC :271-283 outcome='blocking' + blockingError（字段面：无 preventContinuation、
                //   无 stopReason — [CCJ-EXEC-14] 旧实现多带两键，stopHooks 分发语义偏移）
                // [H13] 文本对齐 CC :279 `${structuredOutputResult.reason}` 模板字面量 —— reason=undefined 时拼
                // "undefined"（修探查 §C 2.3-9 "Agent hook condition was not met: Agent hook condition was not met" 错位）。
                String reasonText = reason != null ? reason : "undefined";
                String blockingText = "Agent hook condition was not met: " + reasonText;
                if (log.isInfoEnabled()) {
                    log.info("ExecAgentHook: hook={} 条件未满足, outcome=blocking, reason={}", hookName, reasonText);
                }
                return blocking(blockingText, hook.prompt());
            }

            // CC :285-303 outcome='success' + tengu_agent_stop_hook_success analytics + hook_success attachment
            emitAnalytics("tengu_agent_stop_hook_success", hookStartTime, agentName,
                Map.of("turnCount", turnCount));
            if (log.isInfoEnabled()) {
                log.info("ExecAgentHook: hook={} 条件满足, outcome=success", hookName);
            }
            return success(hookName, effectiveToolUseID, hookEvent);
        }

        // structuredOutput == null
        ExitReason er = finalState != null ? finalState.exitReason() : null;
        if (er != null && isErrorExit(er)) {
            // CC :316-338 outcome='non_blocking_error' + errorType=2 analytics
            emitAnalytics("tengu_agent_stop_hook_error", hookStartTime, agentName,
                Map.of("errorType", 2));
            if (log.isWarnEnabled()) {
                log.warn("ExecAgentHook: hook={} 无 structured output 且 error 退出 ({}), outcome=non_blocking_error",
                    hookName, er);
            }
            return nonBlockingError(hookName, effectiveToolUseID, hookEvent,
                "Error executing agent hook: " + er);
        }
        if (er == ExitReason.MAX_TURNS || (er == ExitReason.ABORTED && maxTurnsBreakerFired)) {
            // CC :238-252 hitMaxTurns -> cancelled + tengu_agent_stop_hook_max_turns analytics
            // [R9] 熔断路径: loop 以 ABORTED 退出（aborted_streaming），maxTurnsBreakerFired 区分
            //   「熔断 abort」与「父 abort/timeout」——前者按 CC hitMaxTurns 发 max_turns analytics。
            emitAnalytics("tengu_agent_stop_hook_max_turns", hookStartTime, agentName,
                Map.of("turnCount", turnCount));
            if (log.isInfoEnabled()) {
                log.info("ExecAgentHook: hook={} 达到 MAX_AGENT_TURNS={} (exitReason={}), outcome=cancelled",
                    hookName, MAX_AGENT_TURNS, er);
            }
            return cancelled();
        }
        // CC :254-267 其他无 structured output -> cancelled + errorType=1 analytics
        // (ABORTED 例外: CC :308-313 内层 catch combinedSignal.aborted 直接 cancelled, 无 analytics)
        if (er != ExitReason.ABORTED) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("turnCount", turnCount);
            attrs.put("errorType", 1);
            emitAnalytics("tengu_agent_stop_hook_error", hookStartTime, agentName, attrs);
        }
        if (log.isInfoEnabled()) {
            log.info("ExecAgentHook: hook={} 无 structured output (exitReason={}), outcome=cancelled",
                hookName, er);
        }
        return cancelled();
    }

    /**
     * [H13] CC logEvent 等价 · 对齐 execAgentHook.ts:242/257/287/319 发射 tengu_agent_stop_hook_* analytics.
     * telemetry 为 null 时不发射（幂等）。
     */
    private void emitAnalytics(String eventName, long hookStartTime, String agentName,
                               Map<String, Object> extra) {
        if (telemetry == null) {
            return;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("durationMs", System.currentTimeMillis() - hookStartTime);
        attrs.put("agentName", agentName);
        if (extra != null) {
            attrs.putAll(extra);
        }
        telemetry.recordEvent(eventName, attrs);
    }

    /**
     * loop error 退出原因集合 · 这些 exitReason 映射 CC :316-338 non_blocking_error。
     *
     * <p>[ER-IMP-01] MAX_RETRIES / FALLBACK 分支删除（DC-12）：两枚举值已随
     * {@code AgentState.ExitReason} 删除——withRetry 重试耗尽统一映射 MODEL_ERROR
     * （CC query.ts:996），fallback 切换属 withRetry 域动作、最终仍以 MODEL_ERROR /
     * STREAM_ERROR 等 reason 退出。
     */
    private static boolean isErrorExit(ExitReason er) {
        return er == ExitReason.STREAM_ERROR
            || er == ExitReason.STREAM_TIMEOUT
            || er == ExitReason.IMAGE_ERROR
            || er == ExitReason.PROMPT_TOO_LONG;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 超时调度 · CC :75-85
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 调度超时 abort · 对齐 CC :75-82 createCombinedAbortSignal({timeoutMs})。
     *
     * <p><b>[IMPL-06] 硬中断已闭环（H13-GAP-4 v3）</b>: 旧实现标注"软超时"（stream await
     * done.await 300s 不响应 state.cancel）。现 hookAbort 已注入 base TUC →
     * per-turn TUC.abortController → ModelCaller 透传 provider stream：abort 时 provider
     * 以 CancellationException 终止底层请求（LlmAgentLoop :2869-2875 + LlmProvider.stream
     * onCancel），loop 的 done.await 立即唤醒 → turn 中断（对齐 CC createCombinedAbortSignal
     * 硬中断）。timeout 与父 abort 同路径（均 abort hookAbort → state.cancel + provider 硬中断）。
     */
    private static ScheduledFuture<?> scheduleTimeout(AbortController hookAbort, long timeoutMs) {
        return TIMEOUT_SCHEDULER.schedule(() -> {
            if (!hookAbort.isCancelled()) {
                hookAbort.abort("timeout");
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }


    // ════════════════════════════════════════════════════════════════════════
    // HookResult 工厂 (outcome 4 态) · 对齐 CC execAgentHook.ts 返回
    // ════════════════════════════════════════════════════════════════════════

    /** success · CC :293-303 outcome='success' + message=hook_success attachment（CC :296-302）. */
    private HookResult success(String hookName, String effectiveToolUseID, HookEvent hookEvent) {
        return new HookResult(false, null, null, null,
            AttachmentMessageDto.hookSuccess(hookName, effectiveToolUseID, hookEvent.type().name()),
            null, null, null, null, HookOutcome.SUCCESS, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    /**
     * blocking + blockingError · CC :271-283
     * {@code { outcome:'blocking', blockingError: { blockingError: "Agent hook condition was not met: ${reason}", command: hook.prompt } }}.
     *
     * <p><b>[CCJ-EXEC-14]</b> 字段面逐字对齐 CC：<b>无 preventContinuation、无 stopReason</b>
     * （CC agent blocking 返回对象不含这两键，Java 置 false/null）——分发层 stopHooks 语义
     * 与 CC 一致：agent blocking 只进 blockingError 通道（stopHooks.ts:330-331 以 blockingErrors
     * 重入），不触发 preventContinuation 的 "禁止继续" 路径（stopHooks.ts:269-293）。
     *
     * @param blockingText 阻塞错误完整文本（含 reason，reason=undefined 时文本含 "undefined"）
     * @param hookPrompt   blockingError.command = hook.prompt
     */
    private static HookResult blocking(String blockingText, String hookPrompt) {
        return new HookResult(false,
            new HookBlockingError(blockingText, hookPrompt),
            null, null, null, null, null,
            null, null, HookOutcome.BLOCKING, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    /** non_blocking_error · CC :316-338 outcome='non_blocking_error' + message=hook_non_blocking_error attachment（CC :328-336）. */
    private HookResult nonBlockingError(String hookName, String effectiveToolUseID, HookEvent hookEvent, String stderr) {
        // [对抗核验 H13-GAP] CC :333-335 stdout='' + exitCode=1 显式传递（旧实现丢 stdout/exitCode）
        return new HookResult(false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError(hookName, effectiveToolUseID, hookEvent.type().name(), stderr, "", 1),
            null, null, null, null, HookOutcome.NON_BLOCKING_ERROR, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    /** cancelled · CC :248-252 / :264-267 / :308-313 outcome='cancelled'. */
    private static HookResult cancelled() {
        return new HookResult(false, null, null, null, null, null, null,
            null, null, HookOutcome.CANCELLED, null, null, null, null,
            null, null, null, null);   // +4 awaiting (2026-08-12 △-01)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 解析 helper
    // ════════════════════════════════════════════════════════════════════════

    /** CC :75 {@code hook.timeout ? hook.timeout * 1000 : 60000} (秒 -> 毫秒, 默认 60s). */
    private static long resolveTimeoutMs(Integer timeoutSeconds) {
        return timeoutSeconds != null && timeoutSeconds > 0
            ? timeoutSeconds * 1000L
            : DEFAULT_TIMEOUT_MS;
    }

    /**
     * CC :118 {@code hook.model ?? getSmallFastModel()} (hook 未指定 model -> 默认 fast model).
     *
     * <p><b>[R10] env 链回落</b>: {@code nexusai.hook.fastModel} 未配置（空串）时不再产出空串进
     * QueryParams —— 回落 getSmallFastModel env 链（CC model.ts:36-38
     * {@code ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()}，实现同
     * {@link SkillImprovementHook#getSmallFastModel()}，同包共享单一实现）。
     */
    private String resolveModel(String hookModel, String defaultFastModel) {
        if (hookModel != null && !hookModel.isBlank()) {
            return hookModel;
        }
        if (defaultFastModel != null && !defaultFastModel.isBlank()) {
            return defaultFastModel;
        }
        return SkillImprovementHook.getSmallFastModel();
    }

    /**
     * [?-EX-06] 按 modelName 解析真实 provider 配置 · 参照 HookRegistry.resolvePromptProvider
     * （HookRegistry.java:2426-2457，只读参照）模式：
     * <ol>
     *   <li>enabled provider 内找 name 匹配且 enabled 的 model（CC model 配置归属 provider）</li>
     *   <li>provider enabled + 解密 apiKey 非空 → {@code ProviderConfig(baseUrl, apiKey)}</li>
     *   <li>工厂 2-arg 路由校验（config → 真实 provider，非 mock）+ provider type 日志</li>
     * </ol>
     *
     * <p><b>解析不到</b>（未接线 / 无匹配 / 无 apiKey / 异常）→ warn + 回落注入兜底
     * {@link #providerConfig}（生产恒 null → QueryParams.config=null → loop 走 MockLlmProvider →
     * non_blocking_error，维持 ?-EX-06 修复前行为；不构造 {@code ProviderConfig.empty()} 假可用）。
     *
     * <p><b>type 路由说明</b>: loop 侧 provider 仍经 {@code ctx.llmProviderFactory().getProvider(config)}
     * 1-arg 重路由（与主循环一致，LlmAgentLoop:2800 / ModelCaller:63）；anthropic 类型 provider 的
     * type 路由是 loop 级既有行为（主循环同），本层交付物 = config 真实（baseUrl + 解密 apiKey）。
     *
     * @param modelName 非空模型名（resolveModel 产物，hook.model 或 fast model）
     * @return 可用 ProviderConfig 或注入兜底（可 null / 不可用）
     */
    private ProviderConfig resolveProviderConfig(String modelName) {
        if (providerService == null || llmProviderFactory == null) {
            if (log.isDebugEnabled()) {
                log.debug("ExecAgentHook: providerService/llmProviderFactory 未接线, 回落注入兜底 providerConfig={}",
                    providerConfig != null && providerConfig.isUsable());
            }
            return providerConfig;
        }
        try {
            for (ProviderDto p : providerService.listAll()) {
                if (!p.enabled() || p.models() == null || p.models().isEmpty()) {
                    continue;
                }
                boolean modelFound = p.models().stream()
                    .anyMatch(m -> m.enabled() && modelName.equals(m.name()));
                if (!modelFound) {
                    continue;
                }
                String rawKey = providerService.getDecryptedApiKey(p.id());
                if (rawKey == null || rawKey.isBlank()) {
                    log.warn("ExecAgentHook: provider '{}' 无 apiKey, 跳过 (model={})", p.id(), modelName);
                    continue;
                }
                String providerType = p.type() != null ? p.type().name() : "openai_compatible";
                ProviderConfig cfg = new ProviderConfig(p.baseUrl(), rawKey);
                // 工厂 2-arg 路由校验（对齐 HookRegistry.resolvePromptProvider 的
                //   factory.getProvider(config, providerType) 模式）+ provider type 日志
                LlmProvider routed = llmProviderFactory.getProvider(cfg, providerType);
                if (log.isInfoEnabled()) {
                    log.info("ExecAgentHook: 真实 provider 解析成功 model={} providerType={} routed={} baseUrl={}",
                        modelName, providerType, routed.type(), cfg.baseUrl());
                }
                return cfg;
            }
        } catch (Exception e) {
            log.warn("ExecAgentHook: provider 解析失败, 回落注入兜底: {}", e.toString());
            return providerConfig;
        }
        log.warn("ExecAgentHook: 模型 '{}' 无匹配的 enabled provider/apiKey, 回落注入兜底 (生产 null → mock → non_blocking_error)",
            modelName);
        return providerConfig;
    }

    /**
     * 生成 hook agent 唯一 ID · 对齐 CC :122 {@code asAgentId('hook-agent-${randomUUID()}')}.
     * 给每个 hook agent 独立命名空间, 隔离子循环状态不污染主循环.
     */
    static String generateHookAgentId() {
        return "hook-agent-" + UUID.randomUUID();
    }

    /** user message · CC :67-68 createUserMessage({content: processedPrompt}). */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user",
            content, null, null, null, null, null, null, null,
            null, null, null, List.of(), List.of());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 参数替换 (对齐 CC hookHelpers.ts addArgumentsToPrompt + argumentSubstitution.ts)
    // ════════════════════════════════════════════════════════════════════════
    // NOTE: 与 ExecPromptHook.substituteArguments 重复, 对齐 CC 公共 hookHelpers.ts 语义.
    //   未来 J 阶段抽 HookHelpers 公共类消除重复 (规则三: 本 session 不碰 ExecPromptHook).


    /**
     * 替换 {@code $ARGUMENTS} 占位符 · 统一委托公共 {@link ArgumentSubstitution#substituteArguments}
     * （#PLAN-P04-2 双实现漂移闭环 · 消除本类私有重复实现，与 ExecPromptHook 共用单一入口）。
     *
     * <p>CC 真源：hookHelpers.ts:34 {@code addArgumentsToPrompt(prompt, jsonInput) →
     * substituteArguments(prompt, jsonInput)}（argumentSubstitution.ts:94-145），默认
     * {@code appendIfNoPlaceholder=true}、无命名参数。Java 端单一公共实现
     * {@code ArgumentSubstitution.substituteArguments(content, args, true, null)} 承载
     * 全部 5 替换（$name/$ARGUMENTS[N]/$N/$ARGUMENTS/append）。
     */
    private static String substituteArguments(String content, String args) {
        return ArgumentSubstitution.substituteArguments(content, args, true, null);
    }

    /**
     * 校验 hook 响应 schema · 对齐 CC hookHelpers.ts:16-24 hookResponseSchema (Zod).
     * {@code {ok: boolean, reason?: string}}.
     *
     * @return null 表示校验通过; 非 null 为错误描述
     */
    private static String validateHookResponseSchema(JsonNode json) {
        if (json == null) {
            return "structured output is null";
        }
        JsonNode okNode = json.path("ok");
        if (okNode.isMissingNode()) {
            return "ok field is required";
        }
        if (!okNode.isBoolean()) {
            return "ok must be boolean, got " + okNode.getNodeType();
        }
        JsonNode reasonNode = json.path("reason");
        // [CCJ-EXEC-12] reason:null 判非法 · 对齐 zod z.string().optional()（hookHelpers.ts:16-24）
        //   —— optional 不接受 null → safeParse 失败 → 该 attachment 跳过继续 loop（CC :216
        //   if(parsed.success) 语义），旧实现 isNull() 放行（→ success/blocking 分流），语义偏移。
        if (!reasonNode.isMissingNode() && !reasonNode.isTextual()) {
            return "reason must be string, got " + reasonNode.getNodeType();
        }
        return null;
    }
}

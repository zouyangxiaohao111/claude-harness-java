package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.permission.classifier.YoloPromptBuilder;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookMatcher;
import com.nexusai.infra.util.PluginOnlyPolicy;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.AgentSummaryHandle;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.SummarySummarizer;
import com.nexusai.application.agent.subagent.SummarySummarizerImpl;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.tasks.AgentProgressTracker;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreePaths;
import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.ForkWorktreePaths;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.worktree.WorktreeService;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.subagent.FrontmatterHooks;
import com.nexusai.application.agent.subagent.MessageFilters;
import com.nexusai.application.agent.subagent.SkillPreloader;
import com.nexusai.application.agent.subagent.createSubagentContext;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.mcp.McpElicitationStateMachine;
import com.nexusai.application.agent.skill.SkillContentLoader;
import com.nexusai.model.command.Command;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.tool.AgentToolUtils;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 子 Agent 执行器 · 对齐 CC runAgent.ts:248-860 22 步流程
 *
 * <p>架构变更（s06 fix）：不再复用 LlmAgentLoop.run() 作为子 Agent 主循环。
 * SubagentExecutor 拥有自己的内联查询循环（inline query loop），完全对齐 CC 的
 * runAgent → query 调用链，实现消息隔离、工具隔离、权限隔离、transcript 持久化。
 *
 * <h2>CC 22 步逐项对齐</h2>
 * <ol>
 *   <li>agentId —— createAgentId() / override</li>
 *   <li>transcriptSubdir —— setAgentTranscriptSubdir</li>
 *   <li>contextMessages + initialMessages 装配</li>
 *   <li>readFileState 克隆</li>
 *   <li>userContext + systemContext 解析</li>
 *   <li>toolPermissionContext 覆盖（agentPermissionMode / shouldAvoidPermissionPrompts / allowedTools）</li>
 *   <li>useExactTools 决策</li>
 *   <li>resolveAgentTools —— 按 Agent definition 过滤工具</li>
 *   <li>additionalWorkingDirectories</li>
 *   <li>agentSystemPrompt —— override / getAgentSystemPrompt</li>
 *   <li>agentAbortController</li>
 *   <li>executeSubagentStartHooks → 收集 additionalContexts</li>
 *   <li>推 additionalContexts 到 initialMessages</li>
 *   <li>registerFrontmatterHooks</li>
 *   <li>SkillPreloader.preload → 推入 initialMessages</li>
 *   <li>AgentMcpServers.initialize</li>
 *   <li>allTools uniqBy name（合并 resolvedTools + agentMcpTools）</li>
 *   <li>agentOptions 构造</li>
 *   <li>createSubagentContext.create</li>
 *   <li>recordSidechainTranscript + writeAgentMetadata</li>
 *   <li>query 主循环（内联，不委托 LlmAgentLoop.run）</li>
 *   <li>finally: clearAgentTranscriptSubdir + mcpCleanup + clearSessionHooks</li>
 * </ol>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li><b>不委托 LlmAgentLoop.run()</b>：子 Agent 需要完全隔离的 messages[] / tools /
 *       permissionContext，LlmAgentLoop.run() 会创建共享的 AgentState 导致上下文泄露</li>
 *   <li><b>内联查询循环</b>：直接在 SubagentExecutor 内实现与 LlmAgentLoop.loop() 语义相同
 *       但上下文隔离的循环（独立的 messages list、独立的工具注册表）</li>
 *   <li><b>构造器兼容</b>：保持与 SubagentTool.java 的兼容性，接收 LlmAgentLoop 参数但不用
 *       作子 Agent 主循环（仅用于辅助操作如 tool 执行委托）</li>
 * </ul>
 *
 * @see com.nexusai.application.agent.tool.impl.SubagentTool
 * @see com.nexusai.application.agent.LlmAgentLoop
 */
public class SubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubagentExecutor.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long STREAM_TIMEOUT_SECONDS = 300;

    /**
     * [IMP-SUB-25 D-3] handoff 复核提示词 · CC original:
     * "Sub-agent has finished and is handing back control to the main agent. Review the sub-agent's work
     * based on the block rules and let the main agent know if any file is dangerous (the main agent will see the reason)."
     * (Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts:416-418, classifyHandoffIfNeeded 内 user text block)。
     */
    private static final String HANDOFF_REVIEW_PROMPT =
        "Sub-agent has finished and is handing back control to the main agent. Review the sub-agent's work "
            + "based on the block rules and let the main agent know if any file is dangerous "
            + "(the main agent will see the reason).";

    /**
     * [IMP-SUB-25 D-3] 分类器不可用验证提示 · CC original (agentToolUtils.ts:469)：
     * {@code "Note: The safety classifier was unavailable when reviewing this sub-agent's work.
     * Please carefully verify the sub-agent's actions and output before acting on them."}
     */
    private static final String HANDOFF_UNAVAILABLE_NOTE =
        "Note: The safety classifier was unavailable when reviewing this sub-agent's work. "
            + "Please carefully verify the sub-agent's actions and output before acting on them.";

    /**
     * 子 Agent 执行结果 · 对齐 CC AgentTool.tsx:1253-1260 sync 结构化返回
     * (status: 'completed', content[], totalToolUseCount, totalDurationMs, agentId).
     *
     * <p>s06 P1-2 修补: 父 Agent 可获取子 Agent 的 token 数、工具调用次数、耗时等结构化元数据.
     * 之前 audit 偏差: execute() 返回纯 String, 父 Agent 无法访问结构化信息.
     *
     * <p>[S4 P1 差异项 2] 补 {@code totalTokens} + {@code usage}: CC agentToolUtils.ts:237-256
     * agentToolResultSchema 含 {@code totalTokens} + {@code usage{7 子字段}}; finalizeAgentTool (:319/355)
     * 从末尾 assistant message 提取. Java 端 ChatMessageDto 仅 inputTokens/outputTokens (S4-2b 数据源
     * 缺口, 嵌套字段未解析 → usage 兜底 EMPTY).
     *
     * @param summaryText      最终结论文本 (CC content[] 文本)
     * @param totalToolUseCount 工具调用计数 (CC totalToolUseCount)
     * @param totalDurationMs   总耗时 ms (CC totalDurationMs)
     * @param agentId           子 Agent UUID
     * @param status            completed / aborted (CC status)
     * @param totalTokens       总 token 数 (CC totalTokens, agentToolUtils.ts:237/319)
     * @param usage             usage 对象 (CC usage, agentToolUtils.ts:238-256)
     */
    public record SubagentResult(
            String summaryText,
            int totalToolUseCount,
            long totalDurationMs,
            String agentId,
            String status,
            long totalTokens,
            AgentUsage usage
    ) {
        public static SubagentResult completed(String summary, int toolUseCount, long durationMs, String agentId) {
            return completed(summary, toolUseCount, durationMs, agentId, 0L, AgentUsage.EMPTY);
        }

        public static SubagentResult completed(String summary, int toolUseCount, long durationMs,
                                               String agentId, long totalTokens, AgentUsage usage) {
            return new SubagentResult(summary, toolUseCount, durationMs, agentId,
                "completed", totalTokens, Objects.requireNonNull(usage, "SubagentResult.usage 必填（对齐 CC usage 非空）"));
        }

        public static SubagentResult aborted(String summary, int toolUseCount, long durationMs, String agentId) {
            return aborted(summary, toolUseCount, durationMs, agentId, 0L, AgentUsage.EMPTY);
        }

        public static SubagentResult aborted(String summary, int toolUseCount, long durationMs,
                                             String agentId, long totalTokens, AgentUsage usage) {
            return new SubagentResult(summary, toolUseCount, durationMs, agentId,
                "aborted", totalTokens, Objects.requireNonNull(usage, "SubagentResult.usage 必填（对齐 CC usage 非空）"));
        }
    }

    /**
     * fork 缓存共享参数 · 对齐 CC runAgent.ts:249-269 runAgentParams 的 fork path 专属字段
     * + resumeAgent.ts:166-195 resume 专属字段.
     *
     * <p>WHY record 封装 (决策 S3-1 方案 A): CC TS 解构扁平 21 入参 → Java 无解构,
     * record 是 idiomatic 等价 (SubagentResult / AgentRunOptions / SubagentRuntime 同类
     * record 风格, CLAUDE.md 规则 11). 8 参扁平签名可读性差且易错位.
     *
     * <p>forkContextMessages 放本 record 而非 SubagentContextOverrides (决策 S3-2):
     * CC forkedAgent.ts:67 CacheSafeParams.forkContextMessages 是 fork 专属独立类型,
     * SubagentContextOverrides (:260-304) 无此字段; 放通用 overrides 会污染非 fork 路径.
     *
     * <p>resume 专属字段 (resumedMessages / resumedWorktreePath): 对齐 CC resumeAgent.ts:166-195
     * runAgentParams — {@code promptMessages = [...resumedMessages, createUserMessage({content: prompt})]},
     * {@code forkContextMessages: undefined} (transcript 已含父上下文切片, 重供会重复 tool_use ID),
     * {@code worktreePath: resumedWorktreePath}. resume 触发判定: {@code resumedMessages != null} →
     * Step 10 直接装配 resumed 消息链 + 用户 prompt (绕过 systemContext/userContext 与 fork 前缀装配).
     *
     * <p>{@code @JsonIgnore} local-only 约束 (对齐 BudgetTracker 教训): fork 消息前缀是
     * LLM API 构造中间产物, 非 outbound DTO, 禁止序列化到外部通道.
     *
     * @param assistantMessage     CC original: assistantMessage (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:512
     *                             buildForkedMessages 入参) — 父 agent 最后一条 assistant message
     *                             (含 tool_use), fork 前缀克隆源; null 时 Step 10 降级纯 user 消息
     * @param forkContextMessages  CC original: forkContextMessages (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:630
     *                             {@code isForkPath ? toolUseContext.messages : undefined}) — 父对话历史,
     *                             Step 10 经 filterIncompleteToolCalls 剔除残缺 tool_use 后前置
     * @param forkParentSystemPrompt CC original: forkParentSystemPrompt (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:493-497
     *                             renderedSystemPrompt 优先 / :504 fallback recompute) — 父 rendered
     *                             system prompt 字节, Step 9 直接透传 (runAgent.ts:508-509 override.systemPrompt)
     * @param parentThinkingConfig CC original: thinkingConfig (Open-ClaudeCode/src/tools/AgentTool/runAgent.ts:682-683
     *                             {@code useExactTools ? toolUseContext.options.thinkingConfig : ...}) — 父
     *                             thinkingConfig 继承源. 决策 S3-6 B: 经 ForkPathParams 直带 (绕开
     *                             LlmAgentLoop.buildSubagentAgentOptions 硬编码 null)
     * @param resumedMessages      resume 专属: 三层过滤后的 transcript 消息链 · CC original:
     *                             resumeAgent.ts:166-171 {@code promptMessages = [...resumedMessages, ...]};
     *                             null = 非 resume 模式 (标准 fork/普通执行行为不变)
     * @param resumedWorktreePath  resume 专属: 原 worktree 路径 (stat 校验通过) · CC original:
     *                             resumeAgent.ts:82-97 {@code worktreePath: resumedWorktreePath} —
     *                             Step 18 复用原 worktree (而非新建), effectiveCwd 落到该目录
     * @param agentIdOverride      resume 专属: 原 sub-agent UUID · CC original: resumeAgent.ts:240
     *                             {@code override: {..., agentId: asAgentId(agentBackgroundTask.agentId)}}
     *                             (runAgent.ts:347 {@code override?.agentId ? override.agentId : createAgentId()})
     *                             — Step 5 createSubagentContext 优先用此键 (而非 generate 新 UUID),
     *                             使 transcript/metadata 续写原键, 二次 resume 读到 pre-resume transcript;
     *                             null = 非 resume 路径 (标准 fork/普通执行) 保持 generate 新 UUID
     * @param contentReplacementState resume 专属: 重建的 ContentReplacementState · CC original:
     *                             resumeAgent.ts:194 {@code contentReplacementState: resumedReplacementState}
     *                             (reconstructForSubagentResume toolResultStorage.ts:1001-1012) — Step 20
     *                             runSubagentQueryLoop 注入 resumed 子 agent query loop session state,
     *                             query.ts:372-389 applyToolResultBudget 消费同一实例 (per-message budget);
     *                             null = 父 live state 不可得 (web 端点) → loop 保持默认 create
     *                             (CC :1006 {@code if (!parentState) return undefined} feature off)
     */
    public record ForkPathParams(
            @JsonIgnore ForkSubagentMessages.Message assistantMessage,
            @JsonIgnore List<?> forkContextMessages,
            @JsonIgnore String forkParentSystemPrompt,
            @JsonIgnore Object parentThinkingConfig,
            @JsonIgnore List<AgentMessage> resumedMessages,
            @JsonIgnore String resumedWorktreePath,
            @JsonIgnore UUID agentIdOverride,
            @JsonIgnore ContentReplacementState contentReplacementState
    ) {
        public ForkPathParams {
            if (forkParentSystemPrompt == null) forkParentSystemPrompt = "";
        }

        /**
         * 兼容 4 参构造（非 resume 调用方：SubagentTool / 测试），resume 字段默认 null。
         */
        public ForkPathParams(ForkSubagentMessages.Message assistantMessage, List<?> forkContextMessages,
                              String forkParentSystemPrompt, Object parentThinkingConfig) {
            this(assistantMessage, forkContextMessages, forkParentSystemPrompt, parentThinkingConfig,
                null, null, null, null);
        }
    }

    /**
     * 子 Agent 工具注册表（无 task 工具，防止递归）
     */
    private final ToolRegistry subagentToolRegistry;

    /**
     * Hook 注册表
     */
    private final HookRegistry hookRegistry;

    /**
     * [A1 撤外层] 工具执行权限门 · 注入后透传给 fresh carrier（Phase 2 queryLoop）,
     * 子 Agent 路径 StreamingToolExecutor 5 参构造器激活 gate check.
     *
     * <p>WHY 这里用 setter (而不是 ctor 参数): 现有 SubagentExecutor 5 个 setter
     * (worktreeService / backgroundTaskRunner / mcpTransportFactory /
     * skillContentLoader) 都是 setter 注入, 保持现有风格 (CLAUDE.md 规则 11).
     */
    private volatile ToolPermissionGate permissionGate;

    /**
     * [A1 撤外层] 工具输入消毒器 · （Phase 2: fresh carrier @Autowired 自带）.
     * null-safe: 未注入时 StreamingToolExecutor 跳过字段剥离 (向后兼容).
     */
    private volatile InputSanitizer inputSanitizer;

    /**
     * [A1 撤外层] 工具输入验证器 · （Phase 2: fresh carrier @Autowired 自带）.
     * null-safe: 未注入时 StreamingToolExecutor 跳过 schema + validateInput 校验.
     */
    private volatile ToolInputValidator inputValidator;

    /**
     * [ALI-3] Telemetry bean · 透传 → StreamingToolExecutor.setTelemetry.
     * 子 Agent 路径 telemetry 短路修复 (主路径 buildStreamingExecutor 已注入).
     */
    private volatile Telemetry telemetry;

    /**
     * [ALI-3] transcript classifier 开关 · 透传 → setTranscriptClassifierEnabled.
     * 默认 true (对齐 LlmAgentLoop @Value 默认), 关闭时 PermissionDenied retry hook 早返.
     */
    private volatile boolean transcriptClassifierEnabled = true;

    /**
     * [A5-2] 模型/provider mapper · 求和 provider 分派（isAnthropic）原料 · 子 Agent 预算
     * totalTokens 按 effectiveModel 判 anthropic（deepseek input 已含 cache hit → 4 项和双计
     * over-count）。由装配方（SubagentTool / ToolRegistrationConfig）经 {@link #setModelMapper}/
     * {@link #setProviderMapper} 注入；null = 未接线 → 回落 anthropic 语义（既有 4 项和，无行为变化）。
     */
    private volatile ModelMapper modelMapper;
    private volatile ProviderMapper providerMapper;

    /**
     * 注入模型 mapper（A5-2 · null 注入 = 复位回落 anthropic 语义）。
     *
     * @param modelMapper 模型 mapper（可 null）
     */
    public void setModelMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    /**
     * 注入提供商 mapper（A5-2 · null 注入 = 复位回落 anthropic 语义）。
     *
     * @param providerMapper 提供商 mapper（可 null）
     */
    public void setProviderMapper(ProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    /**
     * [IMP-SUB-25 D-3] handoff 安全分类器 · 对齐 CC agentToolUtils.ts:389-481
     * {@code classifyHandoffIfNeeded} 内的 {@code classifyYoloAction}（yoloClassifier.ts:1012）。
     *
     * <p>子 Agent 结束、交还控制权给父 Agent 前，用 auto-mode 分类器复核子 Agent 终态
     * transcript，阻断时给父 Agent 追加安全警告（CC 三调用点 agentToolUtils.ts:608 /
     * AgentTool.tsx:963/:1238）。由装配方经 {@link #setYoloClassifier} 注入；
     * null = 未接线 → handoff 分类跳过（对齐 PermissionPipeline:394 分类器不可用跳过约定）。
     */
    private volatile YoloClassifier yoloClassifier;

    /**
     * [ALI-3] deferred context modifier 模式 · 主路径统一开启, 子 Agent 路径补齐.
     */
    private volatile boolean deferContextModifier = true;

    /**
     * 父 Agent 的 LlmAgentLoop —— 不再用于子 Agent 主循环。
     * 保留此字段仅为保持 SubagentTool 构造器兼容性。
     */
    @SuppressWarnings("unused")
    private final LlmAgentLoop parentLoop;

    /**
     * [H7-arch Phase 5-2 P3-③] AgentLoopContext 共享工厂 · runSubagentQueryLoop 经 {@code shared()}
     * 构造隔离 ctx（工具隔离走 base TUC 的 availableTools=effectiveTools，不再换 carrier toolRegistry）。
     * null = 未注入 → fail loud IllegalStateException（SubagentTool 必须 setContextFactory）。
     */
    private com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory;

    /**
     * LLM provider factory
     */
    private final LlmProviderFactory llmProviderFactory;

    /**
     * Provider config（与父 Agent 共享）
     */
    private final ProviderConfig providerConfig;

    /**
     * 备用模型名（子 Agent 默认）
     */
    private final String fallbackModelName;

    /**
     * 备用系统提示（子 Agent 使用，可被 AgentDefinition.getSystemPrompt() 覆盖）
     */
    private final String fallbackSystemPrompt;

    /**
     * [Phase A 任务 4] 用解析后的 effectiveCwd 覆盖给定 ToolUseContext 中的 effectiveCwd 字段.
     *
     * <p>WHY static: 单测可纯函数验证 (避免起 execute() 需要 LLM), 实际 execute() 内部
     * 在 Step 18 计算 worktreePath 后调用此方法派生 subagentCtx (IMP-SUB-19 #23:
     * create() 直接返回 ToolUseContext), 让 runSubagentQueryLoop 通过 subagentCtx 读到一致的
     * effectiveCwd.
     *
     * <p>防御性: source == null → null; effectiveCwd == null → 透传 (compact ctor 兜底 user.dir).
     *
     * @param source       原始 ToolUseContext (来自 createSubagentContext.create)
     * @param effectiveCwd 解析后的有效工作目录 (来自 Step 18 worktreePath, 已是绝对路径)
     * @return 新 ToolUseContext, 14 字段与 source 一致, effectiveCwd 替换为给定值
     */
    public static ToolUseContext withEffectiveCwd(ToolUseContext source, Path effectiveCwd) {
        if (source == null) {
            return null;
        }
        Path resolved = effectiveCwd != null ? effectiveCwd.toAbsolutePath() : null;
        // [Session J 方案 A] 完整 47 字段透传 — querySource/assistantMessage 已撤回顶层 record;
        //   中间 28 字段 (Stage 3.2 C2 4 + Stage 3.3 UI 11 + Stage 3.4 session 13) 传 null → compact ctor 兜底.
        //   47 字段 fileReadingLimits 走 canonical ctor 透传 source (对齐 CC forkedAgent.ts:456), 见末段.
        return new ToolUseContext(
            source.agentId(),
            source.sessionId(),
            source.mode(),
            source.additionalWorkingDirectories(),
            source.availableTools(),
            source.taskListId(),
            source.abortController(),
            source.messages(),
            source.permissionContext(),
            source.permissionMode(),
            source.mcpClients(),
            source.isNonInteractiveSession(),
            source.renderedSystemPrompt(),
            resolved,
            source.inProgressToolUseIDs(),
            source.toolDecisions(),
            // [R32-b15 Stage 3.1 C13] 透传 onCompactProgress 到子 Agent 上下文
            // (子 Agent 触发压缩时仍走同一回调, 保持 session 级事件流一致)
            source.onCompactProgress(),
            // Stage 3.2 C2 4 字段 — 子 Agent 独立 (source=null)
            null, null, null, null,
            // Stage 3.3 UI 10 字段 — 子 Agent 独立 (source=null; prompt 回调通道 已删 S9)
            null, null, null, null, null, null, null, null, null, null,
            // Stage 3.4 session 13 字段 — 子 Agent 独立 (source=null, 简化透传避免错乱)
            false,
            null, null, null, null,
            null,
            false, false,
            null, null, null,
            null, null,
            // [L+ R1] readFileState 透传 — 子 Agent 复用父 Agent 的 dedup cache,
            //   避免 fork 后重复读已读文件. 对齐 CC runAgent.ts:705 readFileState 父→子透传语义.
            //   compact ctor 内部会 .clone() 出新 Map (兜底逻辑), 避免子 Agent 写 dedup 污染父 cache.
            source.readFileState(),
            // [MCP-I-9 Q-30] mcpServerConnections 透传 — 子 base TUC 继承父已建 MCP 连接,
            //   对齐 CC runAgent.ts:685 agentOptions.mcpClients = mergedMcpClients.
            //   (Step 15 计算 mergedClients 后经 withMcpServerConnections 写入; 此处仅透传 source)
            source.mcpServerConnections(),
            // [R2-D3 修复] fileReadingLimits 透传 — 子代理继承父 Read 上限 override,
            //   对齐 CC forkedAgent.ts:456 fileReadingLimits: parentContext.fileReadingLimits.
            //   原 46 参兼容构造器缺该字段 → compact ctor 兜底 null, 丢失父 override (R2 遗留).
            source.fileReadingLimits(),
            // [openai-lazy] effectiveModelName 透传 — 子代理共享父 turn 模型名（ToolSearch 分流渲染用）
            source.effectiveModelName()
        );
    }

    /**
     * 父 Agent 的 ToolUseContext（可选）· 用于 createSubagentContext.create() 时的
     * readFileState clone / setAppState 隔离 / additionalWorkingDirectories 继承。
     * null = standalone 模式（无父 Agent 上下文可继承）。
     */
    private final ToolUseContext parentToolUseContext;

    /**
     * s18 P1-5/6: Worktree 服务 — 用于 sub-agent 隔离 (isolation=worktree).
     * 可选注入, 未注入时回退到旧 user.dir stub 行为.
     */
    private WorktreeService worktreeService;

    /**
     * [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION feature 门 · 对齐 CC runAgent.ts:824-826
     * {@code if (feature('PROMPT_CACHE_BREAK_DETECTION')) cleanupAgentTracking(agentId)}。
     * 可选注入（setFeatureFlags），未注入 / flag 关 → cleanupAgentTracking no-op
     * （OPD-SP-14 默认关，零行为变化）。
     */
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /**
     * CC original: effectiveIsolation (AgentTool.tsx:431 isolation ?? selectedAgent.isolation)
     * — tool call input.isolation 的透传值. null = 未显式指定 → Step 18 回退
     * agentDefinition.isolation(). 由 {@link #setEffectiveIsolation(String)} 注入.
     */
    private volatile String effectiveIsolationOverride;

    /**
     * Phase 3: BackgroundTaskRunner — owner-scoped 批 kill (CC runAgent.ts:851).
     * 可选注入, 未注入时 killShellTasksForAgent noop (向后兼容).
     */
    private BackgroundTaskRunner backgroundTaskRunner;

    /**
     * OPD-TP-21: task-scoped AbortController · 对齐 CC runAgent.ts:524-525 override?.abortController
     * （AgentTool.tsx:735 传 taskState.abortController → killAsyncAgent abort 直接中断 worker）。
     * 由 SubagentTool 异步 worker 路径经 {@link #setTaskAbortController} 注入（runner
     * registerAsyncAgent 创建的同实例）；null = 未注入（标准 sync/fork/standalone 路径行为不变，
     * 回退 isAsync ? new AbortController() : 父引用）。
     */
    private AbortController taskAbortController;

    /**
     * [RF-1] 父 assistant message 的 requestId · 对齐 CC AgentTool.tsx:723/:778
     * {@code invokingRequestId: assistantMessage?.requestId}。由 SubagentTool 从父 assistant message
     * 提取经 {@link #setInvokingRequestId} 注入（sync / async worker / 降级 sync 三构造点）；resume
     * 路径无父 request_id → null。透传到 {@link #buildSubagentAgentContext} 的子
     * AgentContext.invokingRequestId（analytics sparse-edge 归因 spawn/resume 边界）。
     * null = 未透传（非 fork / 流式 provider 未捕获 request_id）。
     */
    private volatile String invokingRequestId;

    /**
     * P2.3: MCP transport factory · 用于 sub-agent MCP server 真接入 (transport.start +
     * initialize + tools/list). [S5 P0 差异 2] 必须注入 — 2 参 stub 分支已删, agent 声明
     * frontmatter MCP 而 factory 未注入时 fail loud (对齐 CC 总是真实 tools/list).
     */
    private McpTransportFactory mcpTransportFactory;

    /**
     * [S5 P1 差异 3][R31-03 返工] 周期摘要服务 · 对齐 CC agentToolUtils.ts:543-553 startAgentSummarization.
     * 可选注入 (未注入 → 不启动 summary). 触发门三 flag (coordinator / fork / SDK) 见
     * {@link #maybeStartSummary}.
     */
    private volatile AgentSummaryService summaryService;

    /** [S5 P1 差异 3] coordinator mode gate · CC resumeAgent.ts:250-253 enableSummarization 之一. */
    private volatile CoordinatorMode coordinatorMode;

    /**
     * [prompt-align UP-01] 提示词对齐门控实时读源 · 读 settings.coordinator_mode_enabled（DB 单行）。
     * 可选注入（未注入 → 回落 {@link CoordinatorMode#isCoordinatorMode()}，默认关 = 未配置零行为变化）。
     * 用于子代理 drain pending 消息的 coordinator 包裹门（对齐 CC getAgentPendingMessageAttachments
     * attachments.ts:1085-1102 → {type:'queued_command', origin:{kind:'coordinator'}, isMeta:true}）。
     */
    private volatile com.nexusai.application.agent.prompt.PromptAlignSettingsResolver promptAlignSettingsResolver;

    /**
     * [R31-03 返工] SDK agentProgressSummaries 门 · 对齐 CC bootstrap/state.ts:1077-1079
     * {@code getSdkAgentProgressSummariesEnabled()}（读 STATE.sdkAgentProgressSummariesEnabled，
     * 默认 false · state.ts:303；print.ts:2904-2909 SDK 消费者请求 agentProgressSummaries 时置 true）。
     *
     * <p>enableSummarization 三 flag 之一（async AgentTool.tsx:750 / sync :852 / backgrounded :934 /
     * resume resumeAgent.ts:250-253）。默认 false 对齐 CC 默认；由 SubagentTool 经
     * {@link #setSdkAgentProgressSummariesEnabled} 注入（@Value 配置 nexusai.agent.progress-summaries-enabled）。
     */
    private volatile boolean sdkAgentProgressSummariesEnabled;

    /**
     * [R31-03 返工] 注入 SDK agentProgressSummaries 门 · 对齐 CC bootstrap/state.ts:1077-1079
     * getSdkAgentProgressSummariesEnabled()。
     */
    public void setSdkAgentProgressSummariesEnabled(boolean sdkAgentProgressSummariesEnabled) {
        this.sdkAgentProgressSummariesEnabled = sdkAgentProgressSummariesEnabled;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] R31-03: sdkAgentProgressSummariesEnabled 注入={} "
                + "(CC bootstrap/state.ts:1077 getSdkAgentProgressSummariesEnabled)",
                sdkAgentProgressSummariesEnabled);
        }
    }

    /**
     * [R31-03 返工] 摘要门控 spawn 路径类型 · 对齐 CC 四个 summary 生产点三套门（规则三禁止
     * 统一为单一三 flag 或）:
     * <ul>
     *   <li>{@link SummarySpawnPath#ASYNC} — CC AgentTool.tsx:750 {@code isCoordinator ||
     *       isForkSubagentEnabled() || getSdkAgentProgressSummariesEnabled()}（三 flag 或）</li>
     *   <li>{@link SummarySpawnPath#SYNC} — CC AgentTool.tsx:852 {@code summaryTaskId &&
     *       getSdkAgentProgressSummariesEnabled()}（SDK 门 + 前台任务登记守卫）</li>
     *   <li>{@link SummarySpawnPath#BACKGROUNDED} — CC AgentTool.tsx:934 {@code
     *       getSdkAgentProgressSummariesEnabled()}（仅 SDK 门）</li>
     *   <li>{@link SummarySpawnPath#RESUME} — CC resumeAgent.ts:250-253 三 flag 或</li>
     * </ul>
     * 由 {@code SubagentTool.applySummaryWiring} 按 4 构造点注入（async worker → ASYNC /
     * executeSync+降级 sync → SYNC / resume → RESUME）。默认 ASYNC 对齐 fork skill
     * （executeForkedSkill）+ teammate（AutonomousAgentLoop）路径三 flag 或。
     */
    private volatile SummarySpawnPath summarySpawnPath = SummarySpawnPath.ASYNC;

    /**
     * [R31-03 返工] sync 路径前台任务登记守卫 · 对齐 CC AgentTool.tsx:818-833 + :843
     * {@code summaryTaskId = foregroundTaskId}（仅 {@code !isBackgroundTasksDisabled} 时
     * registerAgentForeground 成功才非 undefined）。Java 端 sync 路径无 registerAgentForeground
     * 等价物 → 恒 null → sync 摘要门 {@code summaryTaskId != null && sdk} 恒 false
     * （等价 CC 后台任务禁用态）。保留 setter 供未来前台登记接线后注入真实 taskId。
     */
    private volatile String summaryTaskId;

    /**
     * [R31-03 返工] 注入摘要门控 spawn 路径 · 对齐 CC 四生产点三套门（详见
     * {@link SummarySpawnPath}）。由 {@code SubagentTool.applySummaryWiring} 调用。
     */
    public void setSummarySpawnPath(SummarySpawnPath summarySpawnPath) {
        this.summarySpawnPath = summarySpawnPath;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] R31-03: 摘要门控 spawn 路径注入={} "
                + "(CC AgentTool.tsx:750/:852/:934 + resumeAgent.ts:250-253)", summarySpawnPath);
        }
    }

    /**
     * [R31-03 返工] 注入 sync 路径前台任务登记 ID · 对齐 CC AgentTool.tsx:843
     * {@code summaryTaskId = foregroundTaskId}。null = 无前台登记（Java 当前架构恒 null）。
     */
    public void setSummaryTaskId(String summaryTaskId) {
        this.summaryTaskId = summaryTaskId;
    }

    /**
     * [RF-2 ①] SDK 事件队列 · 对齐 CC utils/sdkEventQueue.ts（进程级单例）。
     * 由 SubagentTool 经 {@code applySummaryWiring} 注入；周期摘要回调经此发射
     * {@code task_progress} SDK 事件（CC updateAgentSummary → emitTaskProgress）。
     * 未注入（测试直构）→ 摘要仅记录不发射 SDK（等价 CC 测试无 bean 场景）。
     */
    private volatile SdkEventQueue sdkEventQueue;

    /** [RF-2 ①] 注入 SDK 事件队列。 */
    public void setSdkEventQueue(SdkEventQueue sdkEventQueue) {
        this.sdkEventQueue = sdkEventQueue;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] RF-2: sdkEventQueue 注入={} (CC utils/sdkEventQueue.ts)",
                sdkEventQueue != null);
        }
    }

    /**
     * [RF-2 ①] 关联父 tool_use block id · 对齐 CC {@code toolUseContext.toolUseId}。
     * 由 SubagentTool 注入（executeSync 首参 toolUseId）；周期摘要发射 task_progress 时透传。
     * null = 无父 tool_use（fork skill / teammate 等路径）。
     */
    private volatile String summaryToolUseId;

    /** [RF-2 ①] 注入关联 tool_use id。 */
    public void setSummaryToolUseId(String summaryToolUseId) {
        this.summaryToolUseId = summaryToolUseId;
    }

    /**
     * [RF-2 ②] 前台任务登记描述 · 对齐 CC registerAgentForeground 的 {@code description}
     * （AgentTool.tsx:821）。由 SubagentTool 注入；executeStreaming 内前台登记
     * （{@code summarySpawnPath == SYNC}）时作为 BackgroundTask.description。
     */
    private volatile String summaryDescription;

    /** [RF-2 ②] 注入前台任务登记描述。 */
    public void setSummaryDescription(String summaryDescription) {
        this.summaryDescription = summaryDescription;
    }

    /**
     * [S5 P0 差异 2] plugin-only policy settings supplier · 对齐 CC runAgent.ts:118
     * {@code isRestrictedToPluginOnly('mcp')}. 默认空 map (不锁), 由装配方注入真实 policy.
     */
    private volatile Supplier<Map<String, Object>> pluginOnlySettingsSupplier = Map::of;

    /**
     * [MCP-I-9 Q-32] MCP server 按名解析器 · 对齐 CC runAgent.ts:140-151 getMcpConfigByName
     * + services/mcp/config.ts:1033。string-ref spec（仅 name）→ 查 DB（Q-09=C DB 唯一运行时源）。
     * 由装配方注入（ToolRegistrationConfig 接 McpServerService）；null → string-ref 直接跳过。
     */
    private volatile java.util.function.Function<String, java.util.Optional<AgentMcpServers.McpServerSpec>>
        mcpServerNameResolver;

    /**
     * [S5 P0 差异 1] MCP tool 调用超时 ms · 对齐 CC getMcpToolTimeoutMs() (client.ts:224).
     * 从 nexusai.mcp.tool-timeout-ms 读取 (默认 60000)（McpTimeoutConfig 已删 MCP-I-9 T6，@Value 承载）.
     */
    private volatile long mcpToolTimeoutMs = 60_000L;

    /**
     * [S05] MCP URL elicitation 状态机 · 与生产轨共享同一实例（elicitation UI 队列不分裂）。
     * null（默认/未接线）→ agent 轨工具不接 -32042 elicitation（测试直连；
     * 生产接线登记 09：McpServerService.elicitationMachine() 依赖 McpToolPool 可见性提升，
     * 待 owner 拍板）。对齐 CC runAgent.ts:1880 context.handleElicitation。
     */
    private volatile McpElicitationStateMachine mcpElicitationMachine;

    /**
     * [IMP-G4 组11-1] Subagent hard_metrics 遥测 · 对齐 CC {@code logEvent}（AgentTool.tsx /
     * agentToolUtils.ts 的 tengu_agent_* 事件）。由装配方（SubagentTool 4 构造站点 /
     * ToolRegistrationConfig.subagentExecutor @Bean）注入；未注入（null）→ 事件跳过不发射
     * （不破坏既有调用）。
     */
    private volatile com.nexusai.application.agent.api.AnalyticsTracker analyticsTracker;

    /**
     * [IMP-G4 组11-1] 会话级 name→agentId 注册表（C7）· 对齐 CC appState.agentNameRegistry
     * （AgentTool.tsx:703-712 写点 / SendMessageTool.ts:804 读点）。由装配方注入；未注入 → no-op
     * （不破坏既有调用）。
     */
    private volatile com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry;

    /**
     * [IMP-G4 组11-1] 会话级 system/user 上下文提供者（F5）· 对齐 CC memoized getSystemContext()
     * （runAgent.ts:380-383）。由装配方注入；未注入 → 惰性构造会话级 provider（gitStatus 通道），
     * 保证子 agent systemContext 非空。
     */
    private volatile com.nexusai.application.agent.prompt.SystemPromptContextProvider systemPromptContextProvider;

    /**
     * [IMP-G4 组11-1] 本 executor 是否承载 async 执行（完成/终止遥测 is_async 字段）· 对齐 CC
     * finalizeAgentTool metadata.isAsync（agentToolUtils.ts:342）。由 SubagentTool async worker /
     * executeResumeAsync 注入 true；sync / 降级 sync 默认 false。
     */
    private volatile boolean asyncExecution = false;

    /**
     * [agentId 统一返工] 外部注入的统一 agentId（packed UUID）· 对齐 CC AgentTool.tsx:580
     * {@code earlyAgentId} / forkedAgent.ts:448 {@code overrides?.agentId ?? createAgentId()}：
     * CC 子代理 agentId 只 createAgentId() 一次，taskId===agentId（LocalAgentTask.tsx:197-262）。
     * Java async 路径由 SubagentTool.executeAsync 生成 agentId（=taskId）后经
     * {@link #setAgentIdOverride(UUID)} 注入，SubagentExecutor Step 5 createSubagentContext
     * 优先使用该键（而非 create() 内部重新 createAgentId()）→ subagentCtx.agentId === taskId →
     * agentIdHex（transcript 键）=== unpackAgentId(taskId)，根治"执行记录找不到"。
     * resume 续写优先：Step 5 三元 {@code resumeAgentIdOverride != null ? resumeAgentIdOverride : this.agentIdOverride}
     * 保证 resume 原键（forkParams.agentIdOverride）胜出。null = 非统一路径（标准 sync/fork/测试）
     * 保持 create() 内部 generate 新 UUID 语义不变。
     */
    private volatile UUID agentIdOverride = null;

    /** [IMP-G4 组11-1] 子 agent assistant 消息计数（完成遥测 assistant_message_count）· 在
     * runSubagentQueryLoop appendListener 逐 assistant 消息累加（对齐 CC finalizeAgentTool
     * agentMessages.length）。单 executor 实例单 query loop，字段语义安全。 */
    private int assistantMessageCount = 0;

    /**
     * P2.2: Skill content loader · 用于 fork mode 加载技能内容注入 system prompt.
     * 默认 new SkillContentLoader()（SkillToolImpl 已有相同 fallback 模式）.
     */
    private SkillContentLoader skillContentLoader = new SkillContentLoader();

    /**
     * P1-11: 共享 SkillPreloader (@Component) · 消费共享 SkillRegistry bean 预加载 skills.
     *
     * <p>CC 真源：runAgent.ts:580 subagent preload 用模块级共享 memoized
     * {@code getSkillToolCommands}。Java 侧 SkillPreloader 为 @Component，经
     * {@link #setSkillPreloader} 注入；未注入且 agent 声明了 skills 时 Step 14 fail loud
     * （抛 IllegalStateException，无影子路径），避免静默降级到不预加载。
     */
    private SkillPreloader skillPreloader;

    /**
     * 构造器 · 保持 SubagentTool 兼容性（无 parent ToolUseContext）。
     */
    public SubagentExecutor(
            ToolRegistry subagentToolRegistry,
            HookRegistry hookRegistry,
            LlmAgentLoop parentLoop,
            LlmProviderFactory llmProviderFactory,
            ProviderConfig providerConfig,
            String fallbackModelName,
            String fallbackSystemPrompt) {
        this(subagentToolRegistry, hookRegistry, parentLoop,
             llmProviderFactory, providerConfig,
             fallbackModelName, fallbackSystemPrompt,
             null);  // standalone mode — no parent TUC
    }

    /**
     * 完整构造器（含 parent ToolUseContext）· 对齐 CC runAgent.ts:700 toolUseContext 透传
     *
     * @param subagentToolRegistry 子 Agent 工具注册表
     * @param hookRegistry         Hook 注册表
     * @param parentLoop           父 Agent 循环（不用于子 Agent 主循环）
     * @param llmProviderFactory   LLM 工厂
     * @param providerConfig       Provider 配置
     * @param fallbackModelName    备用模型名
     * @param fallbackSystemPrompt 备用系统提示
     * @param parentToolUseContext 父 Agent 的 ToolUseContext（可为 null，null=standalone）
     */
    public SubagentExecutor(
            ToolRegistry subagentToolRegistry,
            HookRegistry hookRegistry,
            LlmAgentLoop parentLoop,
            LlmProviderFactory llmProviderFactory,
            ProviderConfig providerConfig,
            String fallbackModelName,
            String fallbackSystemPrompt,
            ToolUseContext parentToolUseContext) {
        this.subagentToolRegistry = subagentToolRegistry;
        this.hookRegistry = hookRegistry;
        this.parentLoop = parentLoop;
        this.llmProviderFactory = llmProviderFactory;
        this.providerConfig = providerConfig;
        this.fallbackModelName = fallbackModelName;
        this.fallbackSystemPrompt = fallbackSystemPrompt;
        this.parentToolUseContext = parentToolUseContext;
        this.worktreeService = null; // 默认未注入 — 由 setWorktreeService 注入
    }

    /**
     * s18 P1-5/6: 注入 WorktreeService — 由 Spring 容器 (SubagentExecutorConfig) 注入,
     * 测试时可手动调本方法. 注入后 sub-agent 在 isolation=worktree 模式下真正获得隔离目录.
     */
    public void setWorktreeService(WorktreeService worktreeService) {
        this.worktreeService = worktreeService;
    }

    /**
     * [IMP-SP2-08] 注入 FeatureFlags · 由装配方（SubagentTool 4 构造站点 / ToolRegistrationConfig
     * subagentExecutor @Bean）注入；测试可手动调用。未注入（null）→
     * {@link #cleanupAgentTracking(String)} feature 门短路 → no-op（默认关行为保持）。
     *
     * <p>setter 注入风格对齐 {@link #setWorktreeService(WorktreeService)}：构造器不变，
     * 可选能力经 setter 接入。
     */
    public void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    /**
     * CC original: effectiveIsolation (Open-ClaudeCode/src/tools/AgentTool/AgentTool.tsx:431
     * {@code effectiveIsolation = isolation ?? selectedAgent.isolation}).
     *
     * <p>由 {@code SubagentTool.doExecute} 从 tool call input.isolation 透传
     * (fork path 下 fork AgentDefinition 自身 isolation 恒为空, 只有 input 显式
     * {@code isolation="worktree"} 才触发 Step 18 真实 worktree 创建 — CC AgentTool.tsx:590-593).
     * null = 未透传 → Step 18 回退 {@code agentDefinition.isolation()}.
     *
     * @param effectiveIsolation input.isolation 透传值 (可为 null, null 表示未显式指定)
     */
    public void setEffectiveIsolation(String effectiveIsolation) {
        this.effectiveIsolationOverride = effectiveIsolation;
    }

    /**
     * Phase 3: 注入 BackgroundTaskRunner · 用于 finally cleanup 中 owner-scoped 批 kill.
     */
    public void setBackgroundTaskRunner(BackgroundTaskRunner backgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner;
    }

    /**
     * [FORK-02] 把保留/复用的隔离 worktree 登记到 BackgroundTask（供终态通知透传
     * {@code <worktree>} 段）· 对齐 CC getWorktreeResult（AgentTool.tsx:644-685 保留才返回；
     * resumeAgent.ts:254 resume 恒返回 {@code {worktreePath}}）。
     *
     * <p>仅在 async 统一路径（{@link #setAgentIdOverride} 非 null，taskId = agentIdOverride）生效；
     * sync/standalone/测试直构无 task → no-op（零行为变化）。Step 21.0 判定保留后调用，早于
     * AsyncAgentFinalizer 终态通知（同 worker 线程，无跨线程竞态）。
     */
    private void registerTaskWorktreeForNotification(String worktreePath, String worktreeBranch) {
        if (backgroundTaskRunner == null || agentIdOverride == null || worktreePath == null
                || worktreePath.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] FORK-02: worktree 未登记 "
                    + "(backgroundTaskRunner={} agentIdOverride={} worktreePath={})",
                    backgroundTaskRunner != null, agentIdOverride != null, worktreePath);
            }
            return;
        }
        backgroundTaskRunner.registerTaskWorktree(agentIdOverride.toString(), worktreePath, worktreeBranch);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] FORK-02: 已登记 task={} worktree={} branch={}",
                agentIdOverride, worktreePath, worktreeBranch);
        }
    }

    /**
     * OPD-TP-21: 注入 task-scoped AbortController（异步 worker 路径）。
     * 对齐 CC runAgent.ts:524-525 override.abortController 优先于 isAsync/new()/父引用。
     * 由 SubagentTool 从 BackgroundTaskRunner.taskAbortController(taskId) 取得同实例后注入，
     * 使 killAsyncAgent 的 abort() 能经本控制器直达 worker 查询循环（state.cancel → "aborted"）。
     *
     * @param taskAbortController runner 为该任务创建的 AbortController（null = 不注入）
     */
    public void setTaskAbortController(AbortController taskAbortController) {
        this.taskAbortController = taskAbortController;
    }

    /**
     * [RF-1] 注入父 assistant message 的 requestId · 对齐 CC AgentTool.tsx:723/:778
     * {@code invokingRequestId: assistantMessage?.requestId}。
     *
     * <p>由 SubagentTool 在 3 个 executor 构造点从父 assistant message 提取注入（sync /
     * async worker / 降级 sync）；resume 路径不注入（无父 request_id）。null = 未透传。
     *
     * @param invokingRequestId 父 assistant message 的 API request_id（CC assistantMessage?.requestId）
     */
    public void setInvokingRequestId(String invokingRequestId) {
        this.invokingRequestId = invokingRequestId;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [RF-1] invokingRequestId 注入={} (CC AgentTool.tsx:723/:778)",
                invokingRequestId);
        }
    }

    /**
     * [IMP-M-P2-2] agent-memory 目录解析器（生成期注入 · OPD-M-38）· 对齐 CC agentMemory.ts.
     *
     * <p>由 Spring 容器（ToolRegistrationConfig.subagentExecutor）注入；测试可手动调本方法。
     * 为 null 时 buildAgentSystemPrompt 跳过 agent-memory 注入（不破坏既有调用）。
     */
    private com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory;

    public void setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory) {
        this.agentMemoryDirectory = agentMemoryDirectory;
    }

    /**
     * [IMP-G4 组11-1] 注入 Subagent hard_metrics 遥测（跨域 AnalyticsTracker）· 对齐 CC logEvent
     * tengu_agent_* 事件（AgentTool.tsx/agentToolUtils.ts）。null → 事件不发射（不破坏既有调用）。
     */
    public void setAnalyticsTracker(com.nexusai.application.agent.api.AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [IMP-G4] analyticsTracker 注入={} (CC tengu_agent_* logEvent)",
                analyticsTracker != null);
        }
    }

    /**
     * [IMP-G4 组11-1] 注入会话级 name→agentId 注册表（C7）· 对齐 CC appState.agentNameRegistry
     * （AgentTool.tsx:703-712 / SendMessageTool.ts:804）。null → 路由/待办 no-op。
     */
    public void setAgentNameRegistry(com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry) {
        this.agentNameRegistry = agentNameRegistry;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [IMP-G4] agentNameRegistry 注入={} (CC agentNameRegistry)",
                agentNameRegistry != null);
        }
    }

    /**
     * [IMP-G4 组11-1] 注入会话级 system/user 上下文提供者（F5）· 对齐 CC memoized getSystemContext()
     * （runAgent.ts:380-383）。null → Step 8 惰性构造会话级 provider。
     */
    public void setSystemPromptContextProvider(
            com.nexusai.application.agent.prompt.SystemPromptContextProvider systemPromptContextProvider) {
        this.systemPromptContextProvider = systemPromptContextProvider;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [IMP-G4] systemPromptContextProvider 注入={} "
                + "(CC getSystemContext memoized, runAgent.ts:380-383)",
                systemPromptContextProvider != null);
        }
    }

    /**
     * [IMP-G4 组11-1] 标记本 executor 承载 async 执行（完成遥测 is_async）· 对齐 CC
     * finalizeAgentTool metadata.isAsync（agentToolUtils.ts:342）。SubagentTool async worker /
     * resume 注入 true；sync / 降级 sync 默认 false。
     */
    public void setAsyncExecution(boolean asyncExecution) {
        this.asyncExecution = asyncExecution;
    }

    /**
     * [agentId 统一返工] 注入统一 agentId（packed UUID）· 对齐 CC AgentTool.tsx:580
     * {@code earlyAgentId} / forkedAgent.ts:448 {@code overrides?.agentId ?? createAgentId()}。
     *
     * <p>调用方：SubagentTool.executeAsync async worker 路径（exec.execute 前）注入
     * {@code agentId}（= BackgroundTask.taskId 对应的 packed UUID）→ Step 5 createSubagentContext
     * 优先用此键 → subagentCtx.agentId === taskId → agentIdHex（transcript 键）=== unpackAgentId(taskId)，
     * 前端传 taskId 即可命中 transcript（根治"执行记录找不到"）。resume 续写优先：
     * forkParams.agentIdOverride（resumeForkParams 第 7 参）在 Step 5 三元中仍胜出。
     * null = 非统一路径保持 create() 内部 generate 新 UUID 语义。
     */
    public void setAgentIdOverride(UUID agentIdOverride) {
        this.agentIdOverride = agentIdOverride;
    }

    /**
     * [FIX-AM REQ-M-19] 自定义 agent 解析器（生产接线 · CC AgentTool.tsx:286
     * {@code toolUseContext.options.agentDefinitions.activeAgents.find(a => a.agentType === ...)}）。
     *
     * <p>由 {@link SubagentTool} 3 个 executor 构造点注入 {@code agentRegistry::findAgent}——
     * 使自定义 memory agent（.claude/agents/*.md）真实可达 {@link #resolveAgentDefinition}，
     * 不再因仅查 {@link BuiltInAgents} 抛 AgentNotFoundException。未注入时保持旧行为
     * （仅内置），不破坏既有调用（bean 路径/测试）。
     */
    private java.util.function.Function<String, AgentDefinition> agentDefinitionResolver;

    public void setAgentDefinitionResolver(java.util.function.Function<String, AgentDefinition> agentDefinitionResolver) {
        this.agentDefinitionResolver = agentDefinitionResolver;
    }

    /**
     * [ODF-C3] 子 Agent 附加 agents · 对齐 CC print.ts:4381-4383
     * (SDK {@code request.agents} → {@code parseAgentsFromJson(request.agents,'flagSettings')}
     * → 并入子 Agent agents 列表)。
     *
     * <p>由 SubagentTool 装配点注入（fork/subagent 请求 DTO 携带的 agents map）。
     * {@link #resolveAgentDefinition} 优先命中本 map（merge 进子 Agent registry），
     * 未命中时回退 agentDefinitionResolver / BuiltInAgents（并入而非替换）。
     *
     * @param additionalAgentDefinitions agentType → AgentDefinition（flagSettings/plugin 等来源）
     */
    public void setAdditionalAgentDefinitions(Map<String, AgentDefinition> additionalAgentDefinitions) {
        if (additionalAgentDefinitions != null) {
            this.additionalAgentDefinitions = additionalAgentDefinitions;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] ODF-C3: 附加 agents map 注入 size={} "
                    + "(对齐 CC print.ts:4381-4383 request.agents)",
                    additionalAgentDefinitions.size());
            }
        }
    }

    /** [ODF-C3] 附加 agents map · 子 Agent registry merge 输入（对齐 CC print.ts request.agents） */
    private volatile Map<String, AgentDefinition> additionalAgentDefinitions = Map.of();

    /**
     * [ODF-C3 返工#3] 子 loop 消费 AgentOptions.agentDefinitions · 对齐 CC runAgent.ts:700-714
     * {@code context.options.agentDefinitions} + print.ts:4381-4383（SDK request.agents 并入子 Agent）。
     *
     * <p>子 ctx.options() 携带的 agents map（经 buildForkAgentOptions / 装配注入塞进
     * AgentOptions.agentDefinitions）在此并入本 executor 的 {@code additionalAgentDefinitions}
     * （子 registry 最局部解析源，resolveAgentDefinition 优先命中）—— 验收 #2 的
     * "fork/subagent DTO 携带 agents map 并经子 loop merge，子 Agent 可列出 flagSettings agent" 闭环。
     *
     * <p>并入而非替换：options 携带的 map 与已注入 additionalAgentDefinitions 并存，
     * options 同名 type 覆盖已注入（后并优先，对齐 CC agentMap.set 覆盖语义）。
     *
     * @param options fork/子 Agent 的 AgentOptions（含 agentDefinitions()；null/空 → no-op）
     */
    void mergeOptionsAgentDefinitions(AgentOptions options) {
        if (options == null) {
            return;
        }
        Map<String, AgentDefinition> carried = options.agentDefinitions();
        if (carried == null || carried.isEmpty()) {
            return;
        }
        Map<String, AgentDefinition> merged = new java.util.HashMap<>(additionalAgentDefinitions);
        merged.putAll(carried);
        this.additionalAgentDefinitions = merged;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [ODF-C3 返工#3] 子 loop 消费 options().agentDefinitions(): "
                + "并入 {} 个 agent 到子 registry (CC runAgent.ts:700-714)",
                carried.size());
        }
    }

    /**
     * P2.3: 注入 McpTransportFactory · 注入后 {@link #initializeAgentMcp(AgentDefinition)}
     * 走 3 参重载（真接入 tools/list + tools/call）. 未注入时回退到 2 参 stub.
     *
     * @param factory Spring 自动装配的 {@link McpTransportFactory} bean, 或测试 stub
     */
    public void setMcpTransportFactory(McpTransportFactory factory) {
        this.mcpTransportFactory = factory;
    }

    /**
     * [S5 P1 差异 3] 注入周期摘要服务 · 对齐 CC agentToolUtils.ts:543-553.
     * 注入后 + CoordinatorMode.isCoordinatorMode()=true → 子 agent loop 启动前 start summary.
     */
    public void setSummaryService(AgentSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** [S5 P1 差异 3] 注入 CoordinatorMode · enableSummarization 判定 (CC resumeAgent.ts:250-253). */
    public void setCoordinatorMode(CoordinatorMode coordinatorMode) {
        this.coordinatorMode = coordinatorMode;
    }

    /**
     * [prompt-align UP-01] 注入提示词对齐门控读源 · 读 settings.coordinator_mode_enabled（DB 实时）。
     * null → 回落 coordinatorMode.isCoordinatorMode()（feature+env 双真，默认关）。
     * 装配方 ToolRegistrationConfig.subagentExecutor 注入（batch0 resolver @Bean）。
     */
    public void setPromptAlignSettingsResolver(
            com.nexusai.application.agent.prompt.PromptAlignSettingsResolver promptAlignSettingsResolver) {
        this.promptAlignSettingsResolver = promptAlignSettingsResolver;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [UP-01] promptAlignSettingsResolver 注入={} "
                + "(coordinator 包裹门 DB 实时读源, CC attachments.ts:1085-1102)",
                promptAlignSettingsResolver != null);
        }
    }

    /**
     * [S5 P0 差异 2] 注入 plugin-only policy settings supplier · 对齐 CC runAgent.ts:118.
     * 默认 Map::of (不锁), 装配方按需注入真实 policy.
     */
    public void setPluginOnlySettingsSupplier(Supplier<Map<String, Object>> pluginOnlySettingsSupplier) {
        if (pluginOnlySettingsSupplier != null) {
            this.pluginOnlySettingsSupplier = pluginOnlySettingsSupplier;
        }
    }

    /**
     * [MCP-I-9 Q-32] 注入 MCP server 按名解析器 · 对齐 CC runAgent.ts:140-151 getMcpConfigByName.
     * string-ref spec（frontmatter 仅 name）→ 查 DB（Q-09=C 唯一运行时源）。未注入 → string-ref 跳过。
     *
     * @param resolver name → Optional&lt;McpServerSpec&gt;（命中 → DB config 构 spec；未命中 → empty）
     */
    public void setMcpServerNameResolver(
            java.util.function.Function<String, java.util.Optional<AgentMcpServers.McpServerSpec>> resolver) {
        this.mcpServerNameResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] Q-32 MCP server 按名解析器已注入");
        }
    }

    /** [S5 P0 差异 1] MCP tool 超时 ms · 对齐 CC getMcpToolTimeoutMs() (client.ts:224), 默认 60000. */
    @org.springframework.beans.factory.annotation.Value("${nexusai.mcp.tool-timeout-ms:60000}")
    public void setMcpToolTimeoutMs(long mcpToolTimeoutMs) {
        this.mcpToolTimeoutMs = mcpToolTimeoutMs;
    }

    /**
     * [S05] 注入 MCP elicitation 状态机（null-safe）· 对齐 CC runAgent.ts:1880
     * {@code handleElicitation} 通道。null = 不接 elicitation（测试默认）。
     */
    public void setMcpElicitationMachine(McpElicitationStateMachine machine) {
        this.mcpElicitationMachine = machine;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] 注入 McpElicitationStateMachine（{}）",
                machine != null);
        }
    }

    /**
     * P2.2: 注入 SkillContentLoader · 主要用于测试覆写 (默认 new SkillContentLoader()).
     */
    public void setSkillContentLoader(SkillContentLoader loader) {
        if (loader != null) {
            this.skillContentLoader = loader;
        }
    }

    /**
     * P1-11: 注入共享 SkillPreloader (@Component) · 对齐 CC runAgent.ts:580 共享 memoized getSkillToolCommands.
     *
     * <p>由 SubagentTool 3 个构造站点（executeSync / async Thread / 降级同步）与
     * ToolRegistrationConfig.subagentExecutor() @Bean 注入。未注入时 agent 声明 skills →
     * Step 14 fail loud（抛 IllegalStateException）。
     */
    public void setSkillPreloader(SkillPreloader preloader) {
        this.skillPreloader = preloader;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] P1-11: 注入共享 SkillPreloader");
        }
    }

    /**
     * [A1 撤外层] 注入 ToolPermissionGate · 透传给 fresh carrier（Phase 2 queryLoop）,
     * 让子 Agent 路径 StreamingToolExecutor 激活权限门 check (CC useCanUseTool.tsx:27-191 对齐).
     * null-safe: 未注入时 fresh carrier 收到 null → StreamingToolExecutor 退化为 allow.
     */
    public void setPermissionGate(ToolPermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    /**
     * [H7-arch Phase 5-2 P3-③] 注入 AgentLoopContextFactory · runSubagentQueryLoop 走 queryLoop 用。
     * 对齐 CC runAgent 复用 query()：隔离 ctx + base TUC availableTools=effectiveTools。
     */
    public void setContextFactory(com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    /**
     * [A1 撤外层] 注入 InputSanitizer · 透传给 fresh carrier（Phase 2 queryLoop）.
     * null-safe: 未注入时 StreamingToolExecutor 跳过字段剥离.
     */
    public void setInputSanitizer(InputSanitizer inputSanitizer) {
        this.inputSanitizer = inputSanitizer;
    }

    /**
     * [A1 撤外层] 注入 ToolInputValidator · 透传给 fresh carrier（Phase 2 queryLoop）.
     * null-safe: 未注入时 StreamingToolExecutor 跳过 schema + validateInput 校验.
     */
    public void setInputValidator(ToolInputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    /**
     * [ALI-3] 注入 Telemetry · 透传给 AgentTurnExecutor.MultiTurnRequest →
     * executeAgentTurn → StreamingToolExecutor.setTelemetry. 子 Agent 路径
     * telemetry 短路修复 (之前只注入 inputSanitizer/inputValidator 2 个).
     */
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * [ALI-3] transcript classifier 开关 · 透传 → StreamingToolExecutor.
     * 关闭状态下 PermissionDenied retry hook 早返 (LlmAgentLoop:4020-4031 三件套).
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.classifier.transcript.enabled:true}")
    public void setTranscriptClassifierEnabled(boolean transcriptClassifierEnabled) {
        this.transcriptClassifierEnabled = transcriptClassifierEnabled;
    }

    /**
     * [IMP-SUB-25 D-3] 注入 handoff 安全分类器 · 装配方（SubagentTool 构造点 /
     * ToolRegistrationConfig bean）注入；测试可手动调用。null = 未接线 → handoff
     * 分类跳过（对齐 PermissionPipeline:394 分类器不可用跳过约定）。
     *
     * @param yoloClassifier handoff 分类器（可为 null）
     */
    public void setYoloClassifier(YoloClassifier yoloClassifier) {
        this.yoloClassifier = yoloClassifier;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类器注入={} (CC agentToolUtils.ts:389)",
                yoloClassifier != null ? yoloClassifier.getClass().getSimpleName() : null);
        }
    }

    /**
     * [ALI-3] deferred context modifier 模式 · 主路径统一开启 (CC
     * toolOrchestration.ts:30-62 queuedContextModifiers 按 add 顺序提交).
     * 子 Agent 路径之前按完成顺序提交 → prompt cache 风险.
     */
    public void setDeferContextModifier(boolean deferContextModifier) {
        this.deferContextModifier = deferContextModifier;
    }

    // [S3-4 决策 B] setSubagentExecutionContext 已删除:
    //   subagentAgentOptions / subagentAssistantMessage 是 S1/S2 落地的 WRITE-ONLY 死线
    //   (grep 自验: 两字段仅 setter 写入, 全文件无读取点). 父 thinkingConfig 改走
    //   ForkPathParams.parentThinkingConfig (S3-6 决策 B: SubagentTool.buildForkParams 从
    //   agentOptions.thinkingConfig() 直带, 绕开 LlmAgentLoop.buildSubagentAgentOptions 硬编码 null);
    //   assistantMessage 由 ForkPathParams.assistantMessage 承载. SubagentTool 3 个调用点已同步移除.

    private static boolean isForkAgentType(String agentType) {
        return "fork".equalsIgnoreCase(agentType);
    }

    /**
     * 执行子 Agent · 22 步流程（对齐 CC runAgent.ts:248-860）
     *
     * @param prompt        用户提示
     * @param subagentType  子 Agent 类型名
     * @param modelOverride 模型覆盖（null = 使用 AgentDefinition 的 model 或 fallback）
     * @param forkParams    fork 缓存共享参数（非 fork path 传 null）· 对齐 CC runAgent.ts:249-269
     *                      runAgentParams 的 fork path 专属字段 (assistantMessage / forkContextMessages /
     *                      forkParentSystemPrompt / parentThinkingConfig). null = 非 fork path
     *                      (向后兼容 executeForkedSkill / SubagentTool 非 fork 调用点).
     * @return 最终结论文本
     */
    public SubagentResult execute(String prompt, String subagentType, String modelOverride,
                                  ForkPathParams forkParams) {
        // 决策 2: 非流式保留 — 阻塞委托 executeStreaming, sink=null (不逐消息回调).
        //   现有阻塞调用方 (executeForkedSkill / SubagentTool) 契约不变.
        return executeStreaming(prompt, subagentType, modelOverride, forkParams, null);
    }

    /**
     * 子 Agent 流式执行 · 对齐 CC {@code runAgent.ts:248} {@code AsyncGenerator<Message, void>}
     * (决策 2 主目标).
     *
     * <p>逐消息回调 {@code messageSink} · 对齐 CC {@code runAgent.ts:748-806} for-await yield 分发:
     * message_start stream_event 丢弃 / attachment 直接 accept / recordable 消息 accept.
     * [S4-1] 消息经 {@link AgentState#setAppendListener} mid-flight 单点武装 — {@code appendMessage}
     * 是唯一消息 append 通道, 回调在 append 后同步触发 (实时转录 + 逐消息 emit + lastRecordedUuid
     * 维护), 首消息早于终态, 与 CC for-await 逐消息 yield 语义一致 (LlmAgentLoop 零改动).
     *
     * @param prompt        用户提示
     * @param subagentType  子 Agent 类型名
     * @param modelOverride 模型覆盖 (null = 使用 AgentDefinition 的 model 或 fallback)
     * @param forkParams    fork 缓存共享参数 (非 fork path 传 null)
     * @param messageSink   逐消息回调 (null = 非流式, {@link #execute} 委托路径)
     * @return 终态 SubagentResult (含 usage/totalTokens)
     */
    public SubagentResult executeStreaming(String prompt, String subagentType, String modelOverride,
                                           ForkPathParams forkParams, Consumer<SubagentMessage> messageSink) {
        // [P0-1][P1-18] 内部 9 参重载: effortOverride=null + parentTucOverride=null + forkAllowedTools=null
        //   + abortControllerOverride=null + querySourceOverride=null + worktreePathOverride=null (标准路径行为不变)
        return executeStreaming(prompt, subagentType, modelOverride, forkParams, messageSink,
            null, null, null, null, null, null);
    }

    /**
     * W8-02 REWORK (GAP-6): 流式执行 + 显式 work abortController 覆盖 · 对齐 CC inProcessRunner.ts:1197
     * {@code override: { abortController: currentWorkAbortController }} —— teammate 每轮 Escape 只停本轮
     * 不杀队友（lifecycle abortController 仍 kill 整个 teammate）。
     *
     * @param abortControllerOverride 本轮 work abort 覆盖（teammate currentWorkAbortController 经桥传入）;
     *                                null = 标准决策（isAsync → 独立 new / 否则父引用，CC runAgent.ts:520-528）
     */
    public SubagentResult executeStreaming(String prompt, String subagentType, String modelOverride,
                                           ForkPathParams forkParams, Consumer<SubagentMessage> messageSink,
                                           AbortController abortControllerOverride) {
        return executeStreaming(prompt, subagentType, modelOverride, forkParams, messageSink,
            null, null, null, abortControllerOverride, null, null);
    }

    /**
     * [Fix-D4] 流式执行 + querySource 覆盖 · CC original: claudeCodeBackend.ts:304
     * {@code querySource: toolUseContext.options.querySource ?? 'workflow'}。
     *
     * <p>workflow 后端（ClaudeCodeBackendAdapter）委托 runAgent 时传 {@code 'workflow'} 语义
     * （CC :304 默认值）：workflow 子代理 querySource 恒 'workflow'，persist gate
     * （query.ts:376-378）不命中（非 agent:/repl_main_thread 前缀）→ content replacement
     * 不持久化。null = 标准派生（{@link #resolveQuerySource}：fork → FORK / 非 fork →
     * agentDefinition.querySourceForAgent()）。
     *
     * @param querySourceOverride workflow 语义 querySource（'workflow'）; null = 标准派生
     */
    public SubagentResult executeStreaming(String prompt, String subagentType, String modelOverride,
                                           ForkPathParams forkParams, Consumer<SubagentMessage> messageSink,
                                           AbortController abortControllerOverride, String querySourceOverride) {
        return executeStreaming(prompt, subagentType, modelOverride, forkParams, messageSink,
            null, null, null, abortControllerOverride, querySourceOverride, null);
    }

    /**
     * [Fix-D1] 流式执行 + 预创建隔离 worktree 路径透传 · CC original: {@code override.worktreePath}
     * (claudeCodeBackend.ts:311) + {@code runWithCwdOverride}（:235-240，utils/cwd.ts）。
     *
     * <p><b>WHY（规则 3 · 严格对齐 CC）</b>：workflow adapter（ClaudeCodeBackendAdapter）经
     * {@code AgentWorktreeManager} 预创建 {@code wf_<sha256>} 隔离 worktree（fail-closed，建树失败 →
     * dead{worktree-failed}，CC claudeCodeBackend.ts:227-233），本参数把 worktree 路径透传至
     * Step 18 作为子 agent effectiveCwd。工作目录铁律：该路径走 Step 18 {@code withEffectiveCwd} 派生
     * 子 ToolUseContext，是子 Agent 工具链看到有效 cwd 的唯一入口（对齐 CC runWithCwdOverride 等价面）。
     * <b>不触发内部 {@code createAgentWorktree}</b>（否则会按 fork slug 再建一个且 fail-open，
     * 与 wf_&lt;sha256&gt; fail-closed 语义互斥）。null = 标准路径（按 effectiveIsolation 内部决策）。
     *
     * @param abortControllerOverride 本轮 work abort 覆盖（teammate currentWorkAbortController 经桥传入）;
     *                                null = 标准决策（isAsync → 独立 new / 否则父引用，CC runAgent.ts:520-528）
     * @param querySourceOverride     workflow 语义 querySource（'workflow'）; null = 标准派生
     * @param worktreePathOverride    workflow adapter 预创建隔离 worktree 绝对路径（Fix-D1）；null = 标准路径
     */
    public SubagentResult executeStreaming(String prompt, String subagentType, String modelOverride,
                                           ForkPathParams forkParams, Consumer<SubagentMessage> messageSink,
                                           AbortController abortControllerOverride, String querySourceOverride,
                                           String worktreePathOverride) {
        return executeStreaming(prompt, subagentType, modelOverride, forkParams, messageSink,
            null, null, null, abortControllerOverride, querySourceOverride, worktreePathOverride);
    }

    /**
     * [P0-1] 流式执行内部重载 · 追加 3 个 SkillTool fork 专属覆写.
     *
     * <p>与 5 参公开方法同 22 步流程, 差异:
     * <ul>
     *   <li>{@code effortOverride}: fork skill effort 合并 · 对齐 CC SkillTool.ts:208-212
     *       {@code agentDefinition = command.effort !== undefined ? {...baseAgent, effort: command.effort} : baseAgent}</li>
     *   <li>{@code parentTucOverride}: per-call 父 ToolUseContext · 对齐 CC SkillTool.ts:226-229
     *       runAgent toolUseContext: {@code {...context, getAppState: modifiedGetAppState}}.
     *       覆盖构造器级 {@link #parentToolUseContext} (bean 单例无法 per-call 注入), 供 fork 子代理
     *       继承父 agentId/sessionId/availableTools.</li>
     *   <li>{@code forkAllowedTools}: fork skill allowedTools · [P1-18] fork 工具授权核心落点, 经
     *       {@link #createForkGetAppStateWithAllowedTools} 包装 fork 子代理 getAppState, 使
     *       AgentLoopContext.mergeAppStateCommandRules 逐轮把技能工具并入子代理 permCtx
     *       (对齐 CC SkillTool.ts:227 modifiedGetAppState + forkedAgent.ts:147-171).
     *       null/空 = 不包装 (标准路径行为不变).</li>
     * </ul>
     */
    private SubagentResult executeStreaming(String prompt, String subagentType, String modelOverride,
                                            ForkPathParams forkParams, Consumer<SubagentMessage> messageSink,
                                            String effortOverride, ToolUseContext parentTucOverride,
                                            List<String> forkAllowedTools, AbortController abortControllerOverride,
                                            String querySourceOverride, String worktreePathOverride) {
        // s06 P1-2 修补: 真实 metrics — durationMs 通过本地变量注入 SubagentResult
        long startMs = System.currentTimeMillis();
        // ── Step 1: 解析 AgentDefinition ──
        String effectiveType = subagentType != null ? subagentType : BuiltInAgents.GENERAL_PURPOSE;
        AgentDefinition agentDefinition = resolveAgentDefinition(effectiveType);
        // [P0-1 + C-31] effort 合并 · 对齐 CC SkillTool.ts:208-212: skill.effort 非空 →
        //   agentDefinition 携带 effort. [C-31] 消费点已打通: runSubagentQueryLoop 子 AgentState
        //   注入 agentDefinition.effort → LlmAgentLoop ModelRequest 构造读 state.effortValue →
        //   ModelCaller → AnthropicSdkProvider buildMessageParams (output_config.effort + beta header,
        //   claude.ts:1458)。merge 从数据形态变为真实 LLM 应用。
        if (effortOverride != null && !effortOverride.isBlank()) {
            AgentDefinition merged = withEffort(agentDefinition, effortOverride);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [P0-1] effort 合并: type='{}' effort='{}' (CC SkillTool.ts:208-212)",
                        effectiveType, effortOverride);
            }
            agentDefinition = merged;
        }
        if (agentDefinition == null) {
            // s31 R-P1-7: 抛 AgentNotFoundException 替代静默 fallback general-purpose.
            //   对齐 CC AgentTool.tsx:345-353 (区分 not found vs denied).
            //   之前审计偏差: 静默 fallback → 模型以为指定了 X 实则跑通用, 不一致.
            //   现在: 显式抛 AgentNotFoundException, 外层 SubagentTool.executeSync 捕获
            //   后转 ToolResult.error 返回父 Agent, 模型可调整策略.
            String availableList = BuiltInAgents.getBuiltInAgents().stream()
                .map(AgentDefinition::agentType)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
            log.warn("[SubagentExecutor] Agent type '{}' 未找到 — 抛出 AgentNotFoundException, "
                    + "可用: {}", effectiveType, availableList);
            throw new AgentNotFoundException("Agent type '" + effectiveType + "' 未找到。"
                    + "可用 Agent types: " + availableList);
        }

        String effectiveModel = modelOverride != null
                ? modelOverride
                : agentDefinition.model().orElse(fallbackModelName);

        // ── Step 2: toolPermissionContext ──
        boolean isAsync = agentDefinition.background().orElse(false);
        PermissionMode permissionMode = resolvePermissionMode(agentDefinition);
        // [B-2] shouldAvoidPermissionPrompts · 对齐 CC runAgent.ts:440-451 降级公式:
        //   shouldAvoidPrompts = canShowPermissionPrompts!==undefined ? !canShowPermissionPrompts
        //                       : (agentPermissionMode==='bubble' ? false : isAsync)
        //   Java AgentDefinition 无 canShowPermissionPrompts (完整透传留 P3, H9-GAP-6 已文档化)
        //   → 降级: bubble (fork) 子 agent 恒可冒泡弹窗 → false; 异步非 bubble → true (自动拒绝).
        //   修复: 旧实现 = isAsync (漏 bubble 例外) 且结果只传参不落地 (runSubagentQueryLoop 死参数).
        boolean shouldAvoidPermissionPrompts = resolveShouldAvoidPermissionPrompts(permissionMode, isAsync);

        // ── Step 3: useExactTools 决策 ──
        // 对齐 CC AgentTool.tsx:631-632: useExactTools=true 仅 fork path (CC AgentTool.tsx:323
        // isForkPath = (effectiveType==null); SubagentExecutor 收到 subagentType="fork" + forkParams 非 null).
        // useExactTools 继承父 tools/thinkingConfig/isNonInteractiveSession (runAgent.ts:500/668-669/682-683).
        boolean isForkPath = isForkAgentType(effectiveType) && forkParams != null;
        boolean useExactTools = isForkPath;

        // ── Step 4: resolveAgentTools ──
        List<Tool> resolvedTools = resolveAgentTools(agentDefinition);

        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] Step 4: 已解析 {} 个工具: {}",
                    resolvedTools.size(),
                    resolvedTools.stream().map(Tool::name).collect(Collectors.joining(", ")));
        }

        // ── Step 5: createSubagentContext (agentId + ToolUseContext + runtime) ──
        // 对齐 CC runAgent.ts:700-714 + forkedAgent.ts:345-462
        // parentToolUseContext 非 null 时：readFileState clone / additionalWorkingDirectories 继承 / mode 继承
        // [Session B B 方案] parent + overrides 模式 · 与 CC forkedAgent.ts:345-462 object literal spread 严格同构.
        //   parentToolUseContext 为 null → create() 内部走 standalone 路径 (独立 sessionId + abort).
        //   isAsync=true 时显式传 shareSetAppState=false (CC forkedAgent.ts:410-411 async 不共享).
        ToolUseContext subagentCtx;
        // [P0-1] per-call 父 TUC 优先 (SkillTool fork 透传), 否则回退构造器级 (SubagentTool 路径)
        ToolUseContext effectiveParentTuc = parentTucOverride != null ? parentTucOverride : parentToolUseContext;
        // [RES-R2] resume 二次续跑 agentId override · 对齐 CC runAgent.ts:347 override?.agentId ?
        //   override.agentId : createAgentId() + resumeAgent.ts:240 override.agentId=原 agentId。
        //   仅 resume 路径 (forkParams.agentIdOverride 非 null) 使用原键续写 transcript/metadata;
        //   null = 非 resume (标准 fork/普通执行) 保持 create() 内部 generate 新 UUID 语义。
        UUID resumeAgentIdOverride = forkParams != null ? forkParams.agentIdOverride() : null;
        if (effectiveParentTuc != null) {
            // [P1-18] fork 工具授权: 把 skill allowedTools 包装进子代理 getAppState ·
            //   对齐 CC forkedAgent.ts:206-209 modifiedGetAppState = createGetAppStateWithAllowedTools(
            //   context.getAppState, allowedTools) + SkillTool.ts:227 runAgent toolUseContext.getAppState.
            //   createSubagentContext.create effectiveOverrides 透传 overrides.getAppState()
            //   (createSubagentContext.java:365-377 14 字段 ctor), 子代理 TUC.getAppState() =
            //   包装函数; AgentLoopContext.mergeAppStateCommandRules 逐轮读 baseTuc.getAppState()
            //   .apply(null) 快照的 alwaysAllowRules[COMMAND] 并入 permCtx → 技能工具不被权限层阻断.
            Function<Map<String, Object>, Map<String, Object>> forkWrappedGetAppState =
                (forkAllowedTools != null && !forkAllowedTools.isEmpty())
                    ? createForkGetAppStateWithAllowedTools(effectiveParentTuc.getAppState(), forkAllowedTools)
                    : null;
            if (log.isDebugEnabled() && forkWrappedGetAppState != null) {
                log.debug("[SubagentExecutor] [P1-18] fork 工具授权包装 getAppState: allowedTools={} "
                        + "(CC forkedAgent.ts:147-171)", forkAllowedTools);
            }
            // [B 返工 R-1] abortController: 对齐 CC runAgent.ts:520-528 决策层 —
            //   agentAbortController = override?.abortController
            //     ? override.abortController
            //     : isAsync ? new AbortController() : toolUseContext.abortController
            //   - OPD-TP-21: taskAbortController 注入时优先（= CC override.abortController，来自
            //     AgentTool.tsx:735 taskState.abortController → killAsyncAgent abort 直达 worker）
            //   - async: 独立 unlinked 控制器（子 Agent 独立运行, 父 abort 不级联, CC :526-527）
            //   - sync: 共享父控制器引用（父 abort 立即同步到子, CC :527-528）
            // 不再传 null 由 create() 按 hasParent 决策（旧实现无 async 分支 → async 子 agent 误共享父引用）
            // [WF-8 + OPD-TP-21] abort 覆盖决策（CC runAgent.ts:520-528 override?.abortController 胜出）：
            //   taskAbortController（OPD-TP-21 后台任务 kill 通道，优先级最高）
            //   → abortControllerOverride（GAP-6 teammate 每轮 work abort，Escape 只停本轮不杀队友）
            //   → 默认（isAsync → 独立 new / 否则父引用）
            AbortController abortOverride = taskAbortController != null
                ? taskAbortController
                : abortControllerOverride != null
                    ? abortControllerOverride
                    : (isAsync ? new AbortController() : effectiveParentTuc.abortController());
            ToolUseContext.SubagentContextOverrides overrides =
                new ToolUseContext.SubagentContextOverrides(
                    // agentId: [RES-R2] resume 续写原键 (CC resumeAgent.ts:240) 优先;
                    //   [agentId 统一返工] 非 resume → 外部注入统一 agentId
                    //   (CC forkedAgent.ts:448 overrides?.agentId ?? createAgentId() — 身份合一 taskId===agentId);
                    //   两者皆 null → create() 内部生成新 UUID (标准 sync/fork 语义不变)
                    resumeAgentIdOverride != null ? resumeAgentIdOverride : this.agentIdOverride,
                    effectiveType,                 // agentType: 子 Agent 类型 (CC :266, 449)
                    null,                          // messages: null → 从父继承 (CC :268, 446)
                    abortOverride,                 // abortController: sync=父引用 / async=独立 new (CC runAgent.ts:524-528)
                    null,                          // shareAbortController: null (abortController 已显式, CC :350-351 override 胜出)
                    null,                          // readFileState: null → 从父 clone (CC :270, 379-381)
                    null,                          // permissionContext: null → 从父继承
                    !isAsync,                      // shareSetAppState: async=false (CC :281, 410-411)
                    true,                          // shareSetResponseLength: true (CC :287, 426)
                    null,                          // criticalSystemReminder_EXPERIMENTAL: null → 从父继承
                    null,                          // contentReplacementState: null → create() 内部新建
                    null,                          // options: null → 继承父 availableTools (S7)
                    forkWrappedGetAppState,        // getAppState: [P1-18] fork 工具授权包装 (CC forkedAgent.ts:147-171)
                    null                           // requireCanUseTool: null → with() 兜底父 (S7)
                );
            if (isForkPath) {
                // 对齐 CC runAgent.ts:694 ...(useExactTools && { querySource }) + :682-683 thinkingConfig.
                // fork 子 agent 的 context.options 必须带 querySource='agent:builtin:fork'
                //   (抗 autocompact 递归守卫, 关闭 Pattern #11 bypass) + 继承父 thinkingConfig
                //   (cache key 一致) + useExactTools=true (CC AgentTool.tsx:631-632).
                // [ODF-C3] fork 子 ctx.options.agentDefinitions 携带附加 agents map
                //   (SDK request.agents 对齐 print.ts:4381-4383) — mergeOptionsAgentDefinitions 输入
                // [IMP-SUB-19 #23] 3 参 create(parent, overrides, options) 已删（options 原塞进
                //   已删除的 SubagentContext 包装 record）。fork AgentOptions 改由调用方本地持有，
                //   即时并入子 registry（对齐 CC createSubagentContext 直接返回 ToolUseContext +
                //   runAgent.ts:700-714 context.options.agentDefinitions）。
                AgentOptions forkAgentOptions = buildForkAgentOptions(forkParams, additionalAgentDefinitions);
                mergeOptionsAgentDefinitions(forkAgentOptions);
                subagentCtx = createSubagentContext.create(effectiveParentTuc, overrides);
            } else {
                subagentCtx = createSubagentContext.create(effectiveParentTuc, overrides);
            }
        } else {
            // standalone 路径 (无父 TUC) · 旧 6 参 ctor 已移除 (合并后 create 仅 2/3 参重载) —
            //   agentType 经 14 参 overrides 承载 (CC forkedAgent.ts:449 agentType 仅 override),
            //   create(parent=null, overrides) 内部走独立 sessionId + 独立 abort 的 standalone 路径.
            // OPD-TP-21: 注入 taskAbortController（如异步 worker 无父 TUC 场景），
            //   kill 通道仍可直达；null（既有路径）→ create() 内部独立 abort 语义不变。
            ToolUseContext.SubagentContextOverrides standaloneOverrides =
                new ToolUseContext.SubagentContextOverrides(
                    // [agentId 统一返工] resume 原键优先 (CC resumeAgent.ts:240), 否则外部注入统一 agentId
                    //   (CC forkedAgent.ts:448 overrides?.agentId ?? createAgentId() — taskId===agentId),
                    //   两者皆 null → create() 内部生成新 UUID (标准 sync/fork 语义不变)
                    resumeAgentIdOverride != null ? resumeAgentIdOverride : this.agentIdOverride,
                    effectiveType, null,
                    taskAbortController != null ? taskAbortController : abortControllerOverride,
                    null, null, null, null,
                    null, null, null, null, null, null);
            subagentCtx = createSubagentContext.create(null, standaloneOverrides);
        }

        ToolUseContext agentTuc = subagentCtx;
        UUID agentId = agentTuc.agentId();
        // [R3-WF-F IMP-SUB-12 返工] a+16hex 生产接线：ToolUseContext.agentId 为 packAgentId 可逆编码
        //   （S-12 桥），此处还原 CC 语义的 a+16hex（AgentTool.tsx:580 earlyAgentId / forkedAgent.ts:448
        //   createAgentId）。transcript/metadata/resume/analytics 键一律用 agentIdHex 输出。
        String agentIdHex = agentId != null ? AgentContext.unpackAgentId(agentId) : null;
        // [session-id-short] agentTuc.sessionId() 已 String（short）
        String sessionId = agentTuc.sessionId();

        log.info("[SubagentExecutor] Step 1-5: agentId={} (a+16hex={}) type={} model={} async={}",
                agentId, agentIdHex, agentDefinition.agentType(), effectiveModel, isAsync);

        // ── Step 6: transcriptSubdir ──
        // 对齐 CC runAgent.ts:351-353 setAgentTranscriptSubdir
        Path sessionDir = resolveSessionDir(sessionId);

        // ── Step 7: additionalWorkingDirectories ──
        // 对齐 CC runAgent.ts:504-506 Array.from(appState.toolPermissionContext
        //   .additionalWorkingDirectories.keys()) → 逐调用下传 getAgentSystemPrompt
        //   → enhanceSystemPromptWithEnvDetails → computeEnvInfo(modelId, dirs)。
        //   [R32-04] 旧实现此处取出 additionalWorkingDirs 却从未下传（BuiltInAgents 恒 List.of()），
        //   现转换为 keySet 列表下传 buildAgentSystemPrompt。
        Map<String, ToolUseContext.AdditionalWorkingDirectory> additionalWorkingDirs =
                agentTuc.additionalWorkingDirectories();
        List<String> additionalWorkingDirList = additionalWorkingDirs == null
                ? List.of()
                : new ArrayList<>(additionalWorkingDirs.keySet());

        // ── Step 8: resolve userContext + systemContext ──
        // 对齐 CC runAgent.ts:380-410。旧自建 env 块（## Environment / git HEAD 原始 ref 读取）
        // 已删除（DEL-SP-16，CC 子代理 systemContext 来自 memoized getSystemContext()，非自建 env 块）。
        // [IMP-G4 F5] systemContext 接 SystemPromptContextProvider.getSystemContext()（CC runAgent.ts:380-383
        //   memoized）：resolveSystemContextText() 惰性构造会话级 provider（未注入时）→ gitStatus/cacheBreaker
        //   map → "key: value" 行渲染（对齐 SystemPromptContextProvider.appendSystemContext :304-322 渲染格式）。
        String userContext = userContextFor(agentDefinition);
        String systemContext = resolveSystemContextText();

        // ── Step 9: agentSystemPrompt ──
        // 对齐 CC runAgent.ts:508-509: agentSystemPrompt = override?.systemPrompt ? override.systemPrompt : ...
        //   fork path 直接用父 rendered bytes (forkParentSystemPrompt) — prompt cache 共享前提,
        //   跳过 buildAgentSystemPrompt 重新构造; 非 fork path 保持 buildAgentSystemPrompt.
        //   惰性求值: CC 三元短路右侧不求值, Java 方法实参先求值 — 故包 Supplier 延迟构造,
        //   fork path 不白算 buildAgentSystemPrompt (C-7 残留清理, 对齐 CC runAgent.ts:508-509 短路语义).
        //   lambda 捕获需 effectively final: agentDefinition 在 effort 合并处 (:726) 被重新赋值,
        //   故取 final 引用供惰性构造使用.
        final AgentDefinition promptAgent = agentDefinition;
        String agentSystemPrompt = resolveForkAgentSystemPrompt(
            isForkPath, forkParams,
            () -> buildAgentSystemPrompt(isForkPath, promptAgent, resolvedTools, effectiveModel,
                additionalWorkingDirList, userContext));
        log.info("[SubagentExecutor] Step 9: systemPrompt 长度={}", agentSystemPrompt.length());

        // ── Step 10: contextMessages + initialMessages 装配 ──
        // querySource: fork path 用常量 FORK_QUERY_SOURCE ('agent:builtin:fork' 无连字符, 对齐 CC
        //   AgentTool.tsx:332 + promptCategory.ts:23); 非 fork path 委托 AgentDefinition.querySourceForAgent()
        //   （对齐 CC promptCategory.ts:16-28 getQuerySourceForAgent: builtin → 'agent:builtin:<type>' /
        //   'agent:default', custom/plugin → 恒常量 'agent:custom'）。
        //   Pattern #11: 旧公式 'agent:'+source+':'+agentType 拼出 'agent:built-in:fork'/'agent:userSettings:<type>'
        //   与 CC 值面不符 (D13/D9 修复), 递归守卫 SubagentTool 检查 'agent:builtin:fork' (无连字符).
        //   【IMP-SUB-15 返工 R1b 死路径标注】resolveQuerySource 组合值当前只有**组合点值面**意义：
        //   AgentRunOptions.querySource (:3287) 全文件 0 读取点 (grep "agentOptions.querySource" = 空),
        //   运行时 QueryParams.querySource (:3642) 由 isForkPath 独立派生 (FORK/SUBAGENT 枚举),
        //   与本组合值无关。故非 fork 运行时 querySource 仍为聚合值 'agent:subagent'
        //   (QuerySource.java:98-101 文档化), 精确 agentType 值域复活归 IMP2-05。
        //   [Fix-D4] querySourceOverride 非空（workflow 后端委托，CC claudeCodeBackend.ts:304
        //   'workflow'）→ 优先使用 override，替代标准派生（resolveQuerySource）。override 同时
        //   经 AgentRunOptions.querySource → withQuerySourceValue 透传到 loop 发射侧。
        String querySource = querySourceOverride != null
            ? querySourceOverride
            : resolveQuerySource(isForkPath, agentDefinition);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] Step 10: querySource={} isForkPath={} agentType={} "
                    + "querySourceOverride={} (CC promptCategory.ts:16-28 + claudeCodeBackend.ts:304)",
                querySource, isForkPath, agentDefinition.agentType(), querySourceOverride);
        }
        List<Map<String, Object>> initialMessages = new ArrayList<>();

        if (forkParams != null && forkParams.resumedMessages() != null) {
            // resume 模式 · 对齐 CC resumeAgent.ts:166-171 promptMessages + runAgent.ts:370-373:
            //   initialMessages = [...resumedMessages, createUserMessage({content: prompt})],
            //   forkContextMessages=undefined — transcript 已含父上下文切片 (含 fork 父前缀),
            //   重供会重复 tool_use ID (resumeAgent.ts:187-189 注释).
            //   三层过滤 (whitespace/orphaned-thinking/unresolved) 由 ResumeService 提前完成,
            //   此处直接透传, 不再二次 filterIncompleteToolCalls (CC resume 不跑该过滤).
            List<AgentMessage> resumed = forkParams.resumedMessages();
            initialMessages.addAll(resumedMessagesToMaps(resumed));
            // [...resumedMessages, createUserMessage({content: prompt})] — user prompt 追加在 resumed 链尾
            Map<String, Object> userPromptMsg = new LinkedHashMap<>();
            userPromptMsg.put("role", "user");
            userPromptMsg.put("content", prompt);
            initialMessages.add(userPromptMsg);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 10: resume 模式装配 initialMessages, resumed.size={}, 尾接 user prompt (CC resumeAgent.ts:166-171)",
                        resumed == null ? 0 : resumed.size());
            }
        } else if (isForkPath) {
            // fork 缓存共享前缀 · 对齐 CC runAgent.ts:370-373:
            //   initialMessages = [...filterIncompleteToolCalls(forkContextMessages), ...promptMessages]
            //   promptMessages = buildForkedMessages(prompt, assistantMessage) (CC AgentTool.tsx:512).
            //   fork 子 agent 的 systemContext/userContext 不再独立注入 — 父的 systemContext 已含在
            //   forkParentSystemPrompt (Step 9) 字节内 (CC fork path 不 build 独立系统消息).
            initialMessages.addAll(assembleForkInitialMessages(prompt, forkParams));
        } else {
            // 非 fork path · 现有纯 user 消息装配 (systemContext + userContext + prompt)
            // 添加 systemContext 作为 system 消息（若存在）
            if (systemContext != null && !systemContext.isBlank()) {
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemContext);
                initialMessages.add(sysMsg);
            }

            // 添加 userContext 作为 user 消息（若存在）
            if (userContext != null && !userContext.isBlank()) {
                Map<String, Object> uctxMsg = new LinkedHashMap<>();
                uctxMsg.put("role", "user");
                uctxMsg.put("content", userContext);
                initialMessages.add(uctxMsg);
            }

        // 添加用户 prompt 作为 user 消息 — fork path 已在 :799 if(isForkPath) 分支经
        //   assembleForkInitialMessages (buildForkedMessages, CC AgentTool.tsx:512) 装配,
        //   此处仅非 fork 路径的普通 "role=user, content=prompt" 装配 (合并去重 2026-08-04).
        Map<String, Object> userPromptMsg = new LinkedHashMap<>();
        userPromptMsg.put("role", "user");
        userPromptMsg.put("content", prompt);
        initialMessages.add(userPromptMsg);
        }


        // ── Step 11: executeSubagentStartHooks → 收集 additionalContexts ──
        List<String> additionalContexts = executeSubagentStartHooks(
                agentIdHex, sessionId.toString(), agentDefinition.agentType());

        // ── Step 12: 推 additionalContexts 到 initialMessages ──
        if (!additionalContexts.isEmpty()) {
            Map<String, Object> contextMessage = createHookContextMessage(additionalContexts);
            initialMessages.add(contextMessage);
            log.info("[SubagentExecutor] Step 11-12: 已推入 {} 个 additionalContext 片段", additionalContexts.size());
        }

        // ── Step 13: registerFrontmatterHooks ──
        int registeredHookCount = 0;
        if (agentDefinition.hooks().isPresent() && !agentDefinition.hooks().get().isEmpty()) {
            try {
                // [IMP-HOOKS-S8 H8 CCJ-HOOKS-T8-02] 注册前 plugin-only 门控 · 对齐 CC
                //   runAgent.ts:564-566: hooksAllowedForThisAgent =
                //   !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(agentDefinition.source)。
                //   policy (strictPluginOnlyCustomization) 锁 hooks 面且 agent 来源非 admin-trusted
                //   (userSettings/projectSettings/flagSettings) → 不注册；built-in/plugin/policySettings
                //   来源始终可注册 (ADMIN_TRUSTED_SOURCES)。与 SkillToolImpl:1158-1162 skill 侧
                //   门控同一语义（CC processSlashCommand.tsx:874-875）。
                // [Session S1 P1-4] FrontmatterHooks.fromMap 现返回 Map<HookEventType, List<HookMatcher>>
                // (27 事件全支持, 对齐 CC hooks.ts:211-213 HooksSchema partialRecord); 旧 HooksSettings
                // 5 具名字段 record 已删除, 22 种事件 hooks 不再静默丢弃.
                // [IMPL-10] DEL-L03-04: frontmatter hooks 改注册到 SessionHookStore（对齐 CC
                //   registerFrontmatterHooks.ts:56 addSessionHook, sessionId=agentId），不再走
                //   GenericHook 旧会话作用域机制；清理由 finally 的 clearSessionHooks(agentId) 承担。
                registeredHookCount = registerAgentFrontmatterHooks(
                        hookRegistry,
                        agentIdHex,
                        agentDefinition.agentType(),
                        agentDefinition.hooks(),
                        agentDefinition.source(),
                        pluginOnlySettingsSupplier);
                if (registeredHookCount > 0) {
                    log.info("[SubagentExecutor] Step 13: 已注册 {} 个 frontmatter hooks agent={} source={}",
                            registeredHookCount, agentId, agentDefinition.source());
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 13: frontmatter hook 注册失败: {} - {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // ── Step 14: SkillPreloader.preload → 推入 initialMessages ──
        // P1-11: 消费共享 SkillPreloader bean（内部共享 SkillRegistry @Bean），
        //   对齐 CC runAgent.ts:580 getSkillToolCommands 共享 memoized 源；不再 per-call new SkillRegistry。
        List<String> skillsToPreload = agentDefinition.skills().orElse(List.of());
        if (!skillsToPreload.isEmpty()) {
            // fail loud: skillPreloader 未注入时不允许静默跳过预加载（无影子路径，规则十二）
            if (skillPreloader == null) {
                log.error("[SubagentExecutor] Step 14: skillPreloader 未注入 (agent={}), 无法预加载 skills={}",
                        agentId, skillsToPreload);
                throw new IllegalStateException(
                        "SkillPreloader not injected into SubagentExecutor; cannot preload skills: " + skillsToPreload);
            }
            try {
                // [P5-③] preload 携带会话 sessionId + fork 子代理 ToolUseContext（agentTuc）·
                //   对齐 CC runAgent.ts:617-627 skill.getPromptForCommand('', toolUseContext)：
                //   ${CLAUDE_SESSION_ID} = agentTuc.sessionId()（父会话 short 继承），shell 注入
                //   权限预检用 agentTuc（含 fork 工具授权 getAppState 包装）。
                String preloadSessionId = agentTuc != null ? agentTuc.sessionId() : null;
                SkillPreloader.PreloadResult preloadResult =
                    skillPreloader.preload(skillsToPreload, preloadSessionId, agentTuc);
                if (!preloadResult.initialMessages().isEmpty()) {
                    initialMessages.addAll(preloadResult.initialMessages());
                    log.info("[SubagentExecutor] Step 14: 已预加载 {} 个 skills agent={}",
                            preloadResult.initialMessages().size(), agentId);
                }
                if (!preloadResult.missingSkills().isEmpty()) {
                    log.warn("[SubagentExecutor] Step 14: 缺失 skills: {}",
                            String.join(", ", preloadResult.missingSkills()));
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 14: skill 预加载失败: {} - {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // ── Step 15: AgentMcpServers.initialize ──
        // [MCP-I-9 Q-30] 传父连接：当前执行上下文（effectiveParentTuc）携带的
        //   mcpServerConnections（父 executor Step 15 写入的 mergedClients；主链 base TUC = 空）。
        //   对齐 CC runAgent.ts:653-656 initializeAgentMcpServers(agentDefinition,
        //   toolUseContext.options.mcpClients)。
        List<AgentMcpServers.McpServerConnection> inheritedParentClients =
            effectiveParentTuc != null
                ? (effectiveParentTuc.mcpServerConnections() != null
                    ? effectiveParentTuc.mcpServerConnections() : List.of())
                : List.of();
        AgentMcpServers.InitResult mcpInitResult = initializeAgentMcp(agentDefinition, inheritedParentClients);
        Runnable mcpCleanup = mcpInitResult.cleanup();
        List<Tool> agentMcpTools = mcpInitResult.tools();

        // ── Step 16: allTools uniqBy name ──
        List<Tool> allTools = mergeToolsUniqByName(resolvedTools, agentMcpTools);
        log.info("[SubagentExecutor] Step 15-16: {} 已解析 + {} mcp = {} 总工具数 (按 name 去重)",
                resolvedTools.size(), agentMcpTools.size(), allTools.size());

        // ── Step 17: agentOptions 构造 ──
        // thinkingConfig: 对齐 CC runAgent.ts:682-684 — fork path (useExactTools) 继承父 thinkingConfig
        //   (prompt cache key 一致), 非 fork path {type:'disabled'} (控制输出 token 成本). 旧硬编码
        //   isForkAgentType ? Map.of("type","enabled") : Map.of() 是偏差: fork 传 enabled 与父不同 →
        //   cache key 不一致 → prompt cache 失效 (S3-7).
        Map<String, Object> thinkingConfig = resolveForkThinkingConfig(isForkPath, forkParams);
        // DEL-SP-10：buildAgentOptions 第 8 实参原错传 agentSystemPrompt 给 appendSystemPrompt 参数
        //   （CC runAgent.ts:673 真语义是继承父 appendSystemPrompt，字段名与承载值双错）——删除该传递；
        //   agentSystemPrompt 仍由 Step 9 :864 resolve，并在 Step 20 runSubagentQueryLoop 使用，不受影响。
        AgentRunOptions agentOptions = buildAgentOptions(allTools, effectiveModel, isAsync, useExactTools,
                agentDefinition, querySource, mcpInitResult.clients(),
                thinkingConfig);

        // ── Step 18: writeAgentMetadata ──
        // s18 P1-5/6: effectiveIsolation == "worktree" 且 worktreeService 已注入时,
        //   创建真实 worktree 目录 (替代原 user.dir stub).
        //   agentSlug 用 agentId (UUID) 保证唯一性, 工作结束后 finally 块清理.
        //   对齐 CC AgentTool.tsx:431 effectiveIsolation = isolation ?? selectedAgent.isolation:
        //   fork AgentDefinition 自身 isolation 恒为空, 只有 SubagentTool 从 tool call
        //   input.isolation 透传 (setEffectiveIsolation) 的 "worktree" 才触发创建.
        String effectiveIsolation = effectiveIsolationOverride != null
            ? effectiveIsolationOverride
            : agentDefinition.isolation().orElse("");
        String worktreePath = System.getProperty("user.dir");
        String agentWorktreeSlug = null;
        // [FORK-02] 保留 worktree 登记（对齐 CC getWorktreeResult — cleanupWorktreeIfNeeded
        //   AgentTool.tsx:644-685 仅在保留时返回 {worktreePath, worktreeBranch}）· Step 21.0
        //   判定 keep/remove 后经 registerTaskWorktree 写到 BackgroundTask，终态通知带 worktree 段。
        WorktreeCreateResult createdAgentWorktree = null;   // isolation=worktree 实际创建（供 keep 判定）
        String resumedWorktreePathForNotification = null;   // resume 复用路径（CC resumeAgent.ts:254 恒表面）
        if (worktreePathOverride != null && !worktreePathOverride.isBlank()) {
            // [Fix-D1] workflow adapter 预创建隔离 worktree（CC claudeCodeBackend.ts:311
            // override.worktreePath + :235-240 runWithCwdOverride 等价面）。直接作为 effectiveCwd，
            // 不触发内部 createAgentWorktree（否则会按 fork slug 再建一个且 fail-open，与
            // wf_<sha256> fail-closed 语义互斥——Step 18 下方 withEffectiveCwd 是子 Agent 工具链
            // 看到有效 cwd 的唯一入口，对齐 CC runWithCwdOverride）。
            worktreePath = worktreePathOverride;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 18: 使用预创建隔离 worktree={}（Fix-D1 CC override.worktreePath）",
                        worktreePath);
            }
        } else if (forkParams != null && forkParams.resumedWorktreePath() != null
                && !forkParams.resumedWorktreePath().isBlank()) {
            // resume 模式 · 对齐 CC resumeAgent.ts:82-97/192: 复用原 worktree (stat 校验通过),
            //   不再新建隔离 worktree — effectiveCwd 落到原 worktree, metadata 重新持久化
            //   (CC "Re-persist so metadata survives runAgent's writeAgentMetadata overwrite").
            //   utimes bump 由 ResumeService 完成 (对齐 resumeAgent.ts:93-97 stale-worktree 清理保护).
            worktreePath = forkParams.resumedWorktreePath();
            // [FORK-02] resume 复用路径恒登记（CC resumeAgent.ts:254 getWorktreeResult
            //   {@code resumedWorktreePath ? { worktreePath: resumedWorktreePath } : {}}，
            //   无 keep/remove 判定）。
            resumedWorktreePathForNotification = worktreePath;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 18: resume 复用原 worktree={} (不新建隔离 worktree, CC resumeAgent.ts:192)",
                        worktreePath);
            }
        } else if ("worktree".equalsIgnoreCase(effectiveIsolation)) {
            if (worktreeService != null) {
                // CC AgentTool.tsx:591: `agent-${earlyAgentId.slice(0, 8)}`
                //   [R-A11] 对齐 CC: slug 后缀 = a+16hex agentId (agentIdHex = unpackAgentId 还原的
                //   CC earlyAgentId 等价, :1387) 前 8 位 = 'a'+7 hex → 首字符恒 'a' (CC 同源)。
                //   旧实现 (packed UUID 前 8 位, 随机 hex 首字符 ≠ CC 'a') 已删除。
                agentWorktreeSlug = ForkWorktreePaths.buildWorktreeSlug(agentIdHex);
                try {
                    WorktreeCreateResult wtResult = worktreeService.createAgentWorktree(
                            Paths.get(worktreePath), agentWorktreeSlug);
                    worktreePath = wtResult.worktreePath().toString();
                    // [FORK-02] 捕获创建结果 → Step 21.0 keep 判定后登记 worktree 到 task
                    createdAgentWorktree = wtResult;
                    log.info("[SubagentExecutor] Step 18: 已创建 agent worktree 于 {} (slug={}, branch={})",
                            worktreePath, agentWorktreeSlug, wtResult.worktreeBranch());
                } catch (Exception e) {
                    log.warn("[SubagentExecutor] Step 18: createAgentWorktree 失败, 回退 user.dir: {}",
                            e.getMessage());
                    agentWorktreeSlug = null;
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 18: isolation=worktree 但 worktreeService 未注入, 回退 user.dir stub");
                }
            }
        }
        String description = agentDefinition.whenToUse();
        // [Phase A 任务 4] 透传 effectiveCwd: 用 Step 18 计算的 worktreePath 作为子 ToolUseContext 的 effectiveCwd.
        //   [IMP-SUB-19 #23] create() 现直接返回 ToolUseContext（无包装 record 可重建）——
        //   直接以 withEffectiveCwd + withMcpServerConnections 派生新 TUC 即可；
        //   abort / runtime 状态仍由 TUC 承载（abortController/readFileState 同一实例）。
        //   runSubagentQueryLoop 通过 subagentCtx 读取, 故本次派生是子 Agent 工具链看到有效 cwd 的唯一入口.
        //   [MCP-I-9 Q-30] 派生时把 mergedClients（父+agent）写入子 base TUC → 嵌套第 2 层
        //   经 parentTUC.mcpServerConnections() 继承（连接对象传递）。对齐 CC runAgent.ts:685
        //   agentOptions.mcpClients = mergedMcpClients。
        subagentCtx = withEffectiveCwd(subagentCtx, Path.of(worktreePath))
            .withMcpServerConnections(mcpInitResult.clients());
        log.info("[SubagentExecutor] [Phase A 任务 4] effectiveCwd={} 透传到子 ToolUseContext (agent={})"
                + " · mcpServerConnections={}（Q-30 连接继承）",
                worktreePath, agentDefinition.agentType(),
                mcpInitResult.clients() != null ? mcpInitResult.clients().size() : 0);

        // [IMP-D F4/M-08] worktree 隔离子代理：agent-memory project/local scope 根改绑
        // effectiveCwd（worktree 路径 · CC agentMemory.ts:43/59 getCwd 语义）；非 worktree
        // （回退 user.dir）保持 projectRoot 绑定（T5 C3 约束：user.dir 不是 projectRoot 替身）。
        // 注：Step 9 的 prompt 注入早于 Step 18 worktree 创建（CC 在 spawn 层创建 worktree、
        //   Java 在 executeStreaming 内创建 —— 既有结构差异），本覆盖作用于 worktree 创建后
        //   的一切 agent-memory 根判定（CC runWithCwdOverride 等价面）。
        if (agentMemoryDirectory != null && worktreePath != null
                && !worktreePath.equals(System.getProperty("user.dir"))) {
            agentMemoryDirectory = agentMemoryDirectory.withEffectiveCwd(worktreePath);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-D F4/M-08] agent-memory 根改绑 effectiveCwd={} "
                        + "(worktree 隔离)", worktreePath);
            }
        }
        try {
            // [#25] AgentMetadata.model 字段已删（对齐 CC sessionStorage.ts:264-272 无 model）——
            //   resume 模型改现算（ResumeService 读 AgentState.currentModel()，CC resumeAgent.ts:131），
            //   不再持久化 spawn 时有效模型。
            AgentTranscript.writeMetadata(sessionDir, sessionId.toString(), agentIdHex,
                    new AgentTranscript.AgentMetadata(
                            agentDefinition.agentType(),
                            worktreePath,
                            description));
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 18: 已写入 agent 元数据 agent={} (a+16hex={}, worktree={})", agentId, agentIdHex, worktreePath);
            }
        } catch (Exception e) {
            log.warn("[SubagentExecutor] Step 18: writeMetadata 失败: {}", e.getMessage());
        }

        // ── Step 19: record initialMessages ──
        recordSidechainTranscript(sessionDir, sessionId.toString(), agentIdHex, initialMessages);

        // ── Step 19.5a: fork path worktree 提示注入 ──
        // 对齐 CC AgentTool.tsx:598-602: fork path + worktree 实际创建成功 → 追加
        //   buildWorktreeNotice(parentCwd, worktreeCwd) user 消息 (追加在 fork directive
        //   之后, 作为子 agent 看到的最新指引: 翻译路径 + 重读可能过期的文件).
        //   worktreePath != user.dir 即创建成功 (createAgentWorktree 失败时已回退 user.dir, 不注入).
        if (isForkAgentType(effectiveType)
                && !worktreePath.equals(System.getProperty("user.dir"))) {
            String parentCwd = System.getProperty("user.dir");
            String notice = ForkWorktreePaths.buildWorktreeNotice(parentCwd, worktreePath);
            Map<String, Object> noticeMsg = new LinkedHashMap<>();
            noticeMsg.put("role", "user");
            noticeMsg.put("content", notice);
            initialMessages.add(noticeMsg);
            log.info("[SubagentExecutor] fork path: worktree 隔离提示已注入, parentCwd={}, worktreeCwd={}",
                    parentCwd, worktreePath);
        }

        // ── Step 19.5: 给 initialMessages 每条 Map 补 "uuid" 键 (供 lastRecordedUuid parent chain) ──
        // 对齐 CC runAgent.ts:745 lastRecordedUuid = initialMessages.at(-1)?.uuid ?? null —
        //   Java 端 initialMessages 是 List<Map<String,Object>> 无 uuid 键 (S4-5), 需先装配.
        //   Pattern #9 修正: 旧代码 :767-769 用 UUID.randomUUID() 伪造 lastRecordedUuid (注释声称
        //   对齐 CC :745 实为撒谎), 随机 uuid 不对应任何已录制消息, transcript parent chain 断裂.
        assignInitialMessageUuids(initialMessages);
        String lastRecordedUuid = lastInitialMessageUuid(initialMessages);

        // ── Step 19.7: [RF-2 ②] sync 路径前台任务登记 (registerAgentForeground 等价) ──
        // 对齐 CC AgentTool.tsx:818-833 + :843：仅 sync 路径 (!isBackgroundTasksDisabled 时)
        // registerAgentForeground 成功才得 summaryTaskId = foregroundTaskId（agentId 合一）。
        // Java 端 agentId 在 Step 5 生成，故此处（Step 19.7）登记，随后 maybeStartSummary
        // 用 summaryTaskId 守卫 sync 门（summaryTaskId && sdk）。async/resume/backgrounded 不登记。
        // [RF-2 返工] 提取为 registerSyncForeground seam：生产链路测试可直达（Pattern #14），锁定
        //   「SubagentTool 注入 backgroundTaskRunner → registerAgentForeground → summaryTaskId 写回」
        //   全链，而非仅静态断言 maybeStartSummary 门（RF-2 反思 P0-② 假接线根因）。
        // Phase 4 (cron-notify): 透传创建会话 sessionId（sessionId = agentTuc.sessionId()，父继承
        // 会话，step 5 提取）——前台任务若被后台化，完成通知注入创建会话回合。
        BackgroundTask registeredForeground = registerSyncForeground(agentId, summaryDescription, prompt, effectiveType,
            sessionId != null ? sessionId.toString() : null);

        // ── Step 19.8: 接通周期摘要 (S5 P1 差异 3 · CC agentToolUtils.ts:543-553) ──
        // 在 agent loop 启动前 start; finally (Step 21) stop (CC :595/:639 success+catch 两路径).
        // [R31-03 返工] 透传 spawn 路径 + summaryTaskId → maybeStartSummary 分路径门
        //   (ASYNC/RESUME 三 flag 或 / SYNC summaryTaskId&&sdk / BACKGROUNDED 仅 sdk)。
        // [RF-2 ①] progressTracker = AgentProgress 通道（CC ProgressTracker），摘要回调经其发射
        //   task_progress SDK 事件。
        AgentProgressTracker progressTracker = new AgentProgressTracker(
            agentIdHex, summaryToolUseId, startMs);
        AgentSummaryHandle summaryHandle = maybeStartSummary(
            summarySpawnPath, summaryTaskId,
            summaryService, coordinatorMode, sdkAgentProgressSummariesEnabled,
            agentIdHex, sessionDir, sessionId.toString(),
            llmProviderFactory, providerConfig, effectiveModel,
            progressTracker, sdkEventQueue);
        // ── Step 20: query 主循环（内联，不委托 LlmAgentLoop.run）──
        // [IMP-D F4/M-05] spawn 作用域注入会话 projectRoot（修 M-05/M-06 · 模板
        //   AgentContext.runWithAgentContext :154-166 同款 capture/set/restore；
        //   subagent-reverify #10 已裁决接线模式）。sync 路径 = 工具线程（IMP-C 已传播）
        //   同值成对；async/resume 路径 = SubagentTool asyncWorker 线程体已注入父值，此处
        //   capture/set/restore 同值成对；多嵌套子代理（子再 spawn）restore 外层原值不串台。
        SubagentResult loopResult = null;
        final String prevSubagentProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        AutoMemPaths.setCurrentProjectRoot(prevSubagentProjectRoot);
        try {
            // [R2-CTX] subagent spawn 包裹 runWithAgentContext（analytics 归因 · CC AgentTool.tsx:733/:785/:911）。
            //   每次 spawn 新建 SubagentContext 并包住 runSubagentQueryLoop，使 query loop 内事件
            //   （getSubagentLogName → subagent_name / getAgentContext → parent_agent_id）可归因到该子 agent。
            //   异步边界：executeStreaming 同步运行（sync 在工具线程 / async 在 asyncWorker 线程），
            //   runWithAgentContext 的 ThreadLocal set/remove 与 query loop 同线程（S-15 跨线程不串台）。
            String invocationKind = (forkParams != null && forkParams.resumedMessages() != null)
                ? "resume" : "spawn";
            // lambda 捕获需 effectively final：subagentCtx（Step 18 rebuild）/ agentDefinition（Step 1
            //   effort merge）均被重赋值，取 final 引用供 runWithAgentContext 包裹体使用。
            final ToolUseContext ctxForLoop = subagentCtx;
            final AgentDefinition defForLoop = agentDefinition;
            AgentContext.SubagentContext agentContext = buildSubagentAgentContext(
                agentId, defForLoop.agentType(),
                defForLoop instanceof AgentDefinition.BuiltInAgentDefinition,
                this.invokingRequestId,
                invocationKind);
            log.info("[SubagentExecutor] [R2-CTX] subagent 执行进入 AgentContext 作用域: agentId={} (a+16hex={}) "
                    + "subagentName={} invocationKind={} (analytics 归因)",
                agentId, agentIdHex, defForLoop.agentType(), invocationKind);
            loopResult = AgentContext.runWithAgentContext(agentContext, () -> {
                SubagentResult innerResult = runSubagentQueryLoop(
                    ctxForLoop, defForLoop, initialMessages,
                    agentSystemPrompt, agentOptions, allTools,
                    effectiveModel, effectiveType, isForkPath, isAsync, permissionMode, shouldAvoidPermissionPrompts,
                    sessionDir, lastRecordedUuid, messageSink,
                    // [RES-R6] resume 专属: 重建的 ContentReplacementState（CC resumeAgent.ts:194）
                    //   经 ForkPathParams 直带, null → loop 默认 create（非 resume / 父 live state 不可得）
                    forkParams != null ? forkParams.contentReplacementState() : null,
                    // [D-6] progressTracker 逐 assistant message 累积接入（CC updateProgressFromMessage）
                    progressTracker);
                return innerResult;
            });
        } finally {
            // ── Step 21: finally cleanup (9 项) ──
            // 对齐 CC runAgent.ts:816-859: mcpCleanup → clearSessionHooks → clearAgentTranscriptSubdir →
            //   cleanupAgentTracking → readFileState.clear → initialMessages release →
            //   unregisterPerfettoAgent → todos cleanup → killShellTasksForAgent

            // [S5 P1 差异 3] 停止周期摘要 · 对齐 CC agentToolUtils.ts:595/:639 stopSummarization
            //   (success + catch 两路径) — agent loop 结束必须 stop, 否则定时器泄漏.
            if (summaryHandle != null) {
                try {
                    summaryHandle.stop();
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] [S5 P1] summary 已停止 agent={}", agentId);
                    }
                } catch (Exception e) {
                    log.warn("[SubagentExecutor] [S5 P1] summary 停止失败: {}", e.getMessage());
                }
            }

            // [RF-2 ②] 注销前台任务（未后台化直接完成）· 对齐 CC AgentTool.tsx:1162-1184。
            //   [IMP-G] G26②：补齐 CC 同处「非 wasBackgrounded → enqueueSdkEvent (subtype=task_notification)
            //   SDK bookend」（AgentTool.tsx:1167-1183）——foreground agent 未后台化完成时直接发射终态
            //   SDK 事件供前端消费（走 drainSdkEvents，不触发 print.ts XML task_notification 解析）。
            //   后台化任务由 async 生命周期收尾，不在此注销/发射（CC :1191 wasBackgrounded 分支）。
            if (registeredForeground != null && !registeredForeground.isBackgrounded()) {
                try {
                    backgroundTaskRunner.unregisterAgentForeground(registeredForeground.id());
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] RF-2: 前台任务已注销 taskId={} agent={}",
                            registeredForeground.id(), agentId);
                    }
                } catch (Exception e) {
                    log.warn("[SubagentExecutor] RF-2: 前台任务注销失败: {}", e.getMessage());
                }

                // [IMP-G] G26② sync task_notification SDK bookend · 对齐 CC AgentTool.tsx:1167-1183：
                //   status = syncAgentError ? 'failed' : wasAborted ? 'stopped' : 'completed'；
                //   output_file=''（CC :1175）；summary=description（CC :1176）；
                //   usage={total_tokens, tool_uses, duration_ms}（CC :1177-1181）。
                //   Java 等价：loopResult==null（runSubagentQueryLoop 抛异常）→ 'failed'；
                //   loopResult.status()=='aborted' → 'stopped'；否则 'completed'。
                if (sdkEventQueue != null) {
                    String bookendStatus;
                    if (loopResult == null) {
                        bookendStatus = "failed";
                    } else if ("aborted".equals(loopResult.status())) {
                        bookendStatus = "stopped";
                    } else {
                        bookendStatus = "completed";
                    }
                    int totalTokensInt = loopResult != null ? (int) loopResult.totalTokens() : 0;
                    int toolUsesInt = loopResult != null ? loopResult.totalToolUseCount() : 0;
                    long durationMs = System.currentTimeMillis() - startMs;
                    sdkEventQueue.emitTaskTerminatedSdk(registeredForeground.id(), bookendStatus,
                        new com.nexusai.application.agent.tasks.SdkEventQueue.TaskTerminatedOpts(
                            summaryToolUseId, summaryDescription, "",
                            new com.nexusai.application.agent.tasks.SdkEventQueue.TaskUsage(
                                totalTokensInt, toolUsesInt, durationMs)));
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] G26② sync task_notification bookend: taskId={} status={} "
                                + "summaryLen={} usage=({},{},{}ms)（CC AgentTool.tsx:1167-1183）",
                            registeredForeground.id(), bookendStatus,
                            summaryDescription == null ? 0 : summaryDescription.length(),
                            totalTokensInt, toolUsesInt, durationMs);
                    }
                }
            }

            // s31 R-P0-2: 清理 sub-agent worktree (如果创建过). 对齐 CC AgentTool.tsx:644-685
            //   cleanupWorktreeIfNeeded — changed → keep; unchanged → remove. 之前审计偏差:
            //   finally 无条件 removeAgentWorktree, 用户产物丢失. 现在: 先 countChanges()
            //   探测变更, 有变更 → keepWorktree (保留 worktree + branch 供 review),
            //   无变更 → removeAgentWorktree (discardChanges=true, 安全删除空 worktree).
            if (agentWorktreeSlug != null && worktreeService != null) {
                try {
                    Path gitRoot = Paths.get(System.getProperty("user.dir"));
                    WorktreeService.WorktreeChanges changes =
                        worktreeService.countChanges(gitRoot, agentWorktreeSlug);
                    if (changes.hasAny()) {
                        worktreeService.keepWorktree(gitRoot, agentWorktreeSlug);
                        log.info("[SubagentExecutor] Step 21.0: 已保留 agent worktree slug={} "
                                + "(modifiedFiles={}, unpushedCommits={}) — 用户产物保留供 review",
                                agentWorktreeSlug, changes.modifiedFileCount(),
                                changes.unpushedCommitCount());
                        // [FORK-02] 保留 → 登记 worktree 到 task（终态通知带 <worktree> 段）
                        if (createdAgentWorktree != null) {
                            registerTaskWorktreeForNotification(
                                createdAgentWorktree.worktreePath().toString(),
                                createdAgentWorktree.worktreeBranch());
                        }
                    } else {
                        worktreeService.removeAgentWorktree(gitRoot, agentWorktreeSlug);
                        log.info("[SubagentExecutor] Step 21.0: 已移除 agent worktree slug={} "
                                + "(no changes)", agentWorktreeSlug);
                    }
                } catch (Exception e) {
                    log.warn("[SubagentExecutor] Step 21.0: worktree 清理失败 slug={}: {}",
                            agentWorktreeSlug, e.getMessage());
                    // 探测失败 → worktree 保留（未 remove），模型侧路径照常表面（对齐 CC 保留语义）
                    if (createdAgentWorktree != null) {
                        registerTaskWorktreeForNotification(
                            createdAgentWorktree.worktreePath().toString(),
                            createdAgentWorktree.worktreeBranch());
                    }
                }
            } else if (resumedWorktreePathForNotification != null) {
                // [FORK-02] resume 复用路径：无本地 keep/remove 判定，恒登记
                //   （CC resumeAgent.ts:254 getWorktreeResult {@code resumedWorktreePath ? {worktreePath} : {}}）
                registerTaskWorktreeForNotification(resumedWorktreePathForNotification, null);
            }

            try {
                mcpCleanup.run();
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 21.1: MCP 清理完成 agent={}", agentId);
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 21.1: MCP 清理失败: {}", e.getMessage());
            }

            // [IMPL-10] DEL-L03-03: SUBAGENT_STOP finally 二次发射已删除 — 单发收敛到
            //   loop 内 stop 段（对齐 CC executeStopHooks hooks.ts:3653，无 finally 二次发射）。
            // [IMPL-10] DEL-L03-04: frontmatter hooks 已迁 SessionHookStore，Step 21.2
            //   unregister（GenericHook 注销）不再需要；清理由 clearSessionHooks(sessionId/agentId) 承担。
            cleanupSessionHooks(sessionId, agentId);

            // 清除 agent transcript 子目录
            try {
                AgentTranscript.clearTranscript(sessionDir, sessionId, agentIdHex);
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 21.3: 已清理 transcript agent={} (a+16hex={})", agentId, agentIdHex);
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 21.3: transcript 清理失败: {}", e.getMessage());
            }

            // 清理 agent tracking（对齐 CC cleanupAgentTracking）
            // [P-CC-02] FileStateCache API: invalidateAll() → clear() (CC fileStateCache.ts:58-60 命名).
            try {
                subagentCtx.readFileState().clear();
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 21.4: 已失效 readFileState agent={} (a+16hex={})", agentId, agentIdHex);
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 21.4: fileState 清理失败: {}", e.getMessage());
            }

            // 释放 initialMessages 数组引用
            initialMessages.clear();

            // [IMP-SP2-08] cleanupAgentTracking 复活：feature('PROMPT_CACHE_BREAK_DETECTION') 开启时
            //   清理 PREVIOUS 中该 agent 的 cache-break tracking（对齐 CC runAgent.ts:824-826，
            //   finally 块内正常/abort/error 三路均执行；feature 默认关 → no-op，默认关零行为变化）。
            // [R3-WF-F REWORK-1] 键回传 packed UUID（agentId.toString()）——写入侧
            //   PromptCacheBreakDetection.getTrackingKey :152 用 agentId 原值作 key；
            //   a+16hex（agentIdHex）传入时 PREVIOUS.remove(a+16hex) 恒 miss → 静默泄漏。
            cleanupAgentTracking(agentId != null ? agentId.toString() : null);

            // 对齐 CC runAgent.ts:843-849 todos cleanup
            // [R3-WF-F REWORK-1] 键回传 packed UUID 字符串——TodoWriteTool:832 todoKey =
            //   ctx.agentId().toString()（packed UUID 字符串），cleanupAgentTodos 必须同键命中
            //   appState.todos 桶；a+16hex 传入 containsKey 恒 false → todo 桶泄漏。
            cleanupAgentTodos(agentId != null ? agentId.toString() : null);

            // 对齐 CC runAgent.ts:851 killShellTasksForAgent
            // [R3-WF-F REWORK-1] 键回传 packed UUID 字符串——BackgroundTaskRunner.killShellTasksForAgent
            //   以 UUID 匹配 task.agentId()（packed UUID）；safeParseUuid(agentIdHex) 抛
            //   IllegalArgumentException → null → 返回 0 no-op → 后台 shell 任务永不终止。
            killShellTasksForAgent(agentId != null ? agentId.toString() : null);

            // 对齐 CC runAgent.ts:852-861 killMonitorMcpTasksForAgent（feature('MONITOR_TOOL') 门控）
            // [R3-WF-F REWORK-1] 同上：packed UUID 字符串对齐 BackgroundTaskRunner UUID 匹配。
            killMonitorMcpTasksForAgent(agentId != null ? agentId.toString() : null);

            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 21: 完整清理已完成 agent={} (a+16hex={})", agentId, agentIdHex);
            }

            // [IMP-D F4/M-05] 恢复 spawn 作用域外层 projectRoot（成对 restore · 线程池复用防串台，
            //   对齐 HookRegistry.withSessionProjectRoot 同款模式；null → 移除回落生效）。
            AutoMemPaths.restoreCurrentProjectRoot(prevSubagentProjectRoot);
        }

        // ── Step 22: extract conclusion ──
        String conclusion = loopResult != null ? loopResult.summaryText() : null;
        boolean wasAborted = loopResult != null && "aborted".equals(loopResult.status());
        if (conclusion == null || conclusion.isBlank()) {
            conclusion = "Subagent completed without final answer.";
        }
        log.info("[SubagentExecutor] Step 22: 子 Agent 结束, status={} conclusionLength={}",
                wasAborted ? "aborted" : "completed", conclusion.length());

        // s06 P1-2 修补: 返回结构化 SubagentResult (对齐 CC AgentTool.tsx:1253-1260)
        // [Phase A 任务 3] 真实 totalToolUseCount 透传 · 直接复用 loopResult.totalToolUseCount(),
        //   该值已在 runSubagentQueryLoop 末尾从 LoopResult 取出.
        //   loopResult == null 时 (runSubagentQueryLoop 抛异常) 退回到 0 — 与异常路径
        //   保持一致 (异常时无法获得真实 metrics).
        long durationMs = System.currentTimeMillis() - startMs;
        // [R3-WF-F IMP-SUB-12 返工] SubagentResult.agentId 输出 a+16hex（对齐 CC AgentTool.tsx:1253-1260
        //   agentId = earlyAgentId = createAgentId() 格式）。
        String agentIdStr = agentIdHex != null ? agentIdHex : (agentId != null ? agentId.toString() : "");
        int toolUseCount = loopResult != null ? loopResult.totalToolUseCount() : 0;
        // [S4 P1 差异项 2] usage/totalTokens 从 runSubagentQueryLoop 返回透传 (对齐 CC
        //   agentToolUtils.ts:319/355 finalizeAgentTool). [DEC-04] SubagentResult.usage 必填非空
        //   (工厂 requireNonNull), loopResult.usage() 恒非 null → 直接透传; loopResult==null
        //   (异常路径) → EMPTY 零初始化哨兵 (对齐 CC EMPTY_USAGE).
        long totalTokens = loopResult != null ? loopResult.totalTokens() : 0L;
        AgentUsage usage = loopResult != null ? loopResult.usage() : AgentUsage.EMPTY;
        // [冲突裁决·并集] HEAD=IMP-G4 F17 hard_metrics（tengu_agent_tool_completed/terminated +
        //   tengu_cache_eviction_hint，CC finalizeAgentTool agentToolUtils.ts:322-357）；subagent_v3=仅注释
        //   （其 base 无 hard_metrics）。hard_metrics 依赖 method 参数 invokingRequestId 非 ThreadLocal 边，
        //   Step 22 发射无需 AgentContext 作用域，均对齐 CC，故保留 hard_metrics 块。
        //   [R-3] 原子代理边界 tengu_api_success/error 已删除（对齐 CC：logging.ts 仅 3 个 per-LLM-call
        //   事件 query/error/success，:196/:304/:463，无子代理边界事件）；per-LLM-call 事件由
        //   AnthropicSdkProvider 承担（A-13）。
        // [IMP-G4 F17] 完成/终止 hard_metrics · 对齐 CC finalizeAgentTool
        //   （agentToolUtils.ts:322-346 tengu_agent_tool_completed + :349-357 tengu_cache_eviction_hint）
        //   与 sync 终止事件（AgentTool.tsx:1132/1209 tengu_agent_tool_terminated）。
        //   metadata 对齐 CC：agentType/resolvedAgentModel/isBuiltInAgent/isAsync/startTime。
        //   abort → terminated（reason=user_cancel_sync 对齐 AgentTool.tsx:1136）；正常 → completed
        //   + cache_eviction_hint（scope=subagent_end，CC :349-357；last_request_id 需父 requestId，
        //   Java invokingRequestId 为父 request_id，语义等价注入）。
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("agent_type", agentDefinition.agentType());
        metrics.put("model", effectiveModel);
        metrics.put("prompt_char_count", prompt != null ? prompt.length() : 0);
        metrics.put("response_char_count", conclusion != null ? conclusion.length() : 0);
        metrics.put("assistant_message_count", assistantMessageCount);
        metrics.put("total_tool_uses", toolUseCount);
        metrics.put("duration_ms", durationMs);
        metrics.put("total_tokens", totalTokens);
        metrics.put("is_built_in_agent",
            com.nexusai.application.agent.subagent.BuiltInAgents.isBuiltIn(agentDefinition));
        metrics.put("is_async", asyncExecution);
        if (wasAborted) {
            metrics.put("reason", "user_cancel_sync");
            emitAgentMetrics(
                com.nexusai.application.agent.api.AnalyticsTracker.EventName.AGENT_TOOL_TERMINATED,
                metrics);
        } else {
            emitAgentMetrics(
                com.nexusai.application.agent.api.AnalyticsTracker.EventName.AGENT_TOOL_COMPLETED,
                metrics);
            // [IMP-G4 F17] tengu_cache_eviction_hint（CC agentToolUtils.ts:349-357）：
            //   scope='subagent_end' + last_request_id（Java 无逐消息 requestId 暴露，
            //   用父 invokingRequestId 注入近似 — CC lastAssistantMessage.requestId 与
            //   assistantMessage?.requestId 同源）
            Map<String, Object> eviction = new LinkedHashMap<>();
            eviction.put("scope", "subagent_end");
            eviction.put("last_request_id", invokingRequestId != null ? invokingRequestId : "");
            emitAgentMetrics(
                com.nexusai.application.agent.api.AnalyticsTracker.EventName.CACHE_EVICTION_HINT,
                eviction);
        }

        // [R-3] 原子代理边界 terminal API 事件已删除（对齐 CC：logging.ts 仅 3 个 per-LLM-call 事件
        //   query/error/success，:196/:304/:463，无子代理边界事件）；此处仅保留 Step 22 结果组装。
        return wasAborted
                ? SubagentResult.aborted(conclusion, toolUseCount, durationMs, agentIdStr, totalTokens, usage)
                : SubagentResult.completed(conclusion, toolUseCount, durationMs, agentIdStr, totalTokens, usage);
    }

    /**
     * [Session H5] 清理 sub-agent 的临时 session hooks · 对齐 CC runAgent.ts:822
     * {@code clearSessionHooks} (sessionHooks.ts:437-447).
     *
     * <p>WHY (H5 v2 对抗核验未登记缺口): {@code execute} 的 finally 块 Step 21.2b 调用本方法 —
     * sub-agent 会话结束必须释放注册过的运行时临时 hook (addSessionHook/addFunctionHook 注册的),
     * 否则泄漏到后续会话复用 (注册了永不清理). 抽成 package-private 方法是为了让 finally 接线
     * 可被集成测试直接验证 (SessionHookStoreTest 只有 store 自身单测, 无 SubagentExecutor 接线兜底).
     *
     * <p>[IMPL-10] DEL-L03-04: frontmatter hooks 迁 SessionHookStore 后以 agentId 为 key
     * 注册（对齐 CC registerFrontmatterHooks sessionId=agentId），故两个 key 都要清：
     * sessionId（skill/运行时临时 hooks）+ agentId（frontmatter hooks）。
     *
     * @param sessionId sub-agent 会话 ID (HookRegistry.clearSessionHooks 的 key)
     * @param agentId   sub-agent agent ID (frontmatter hooks 的 key)
     */
    void cleanupSessionHooks(String sessionId, UUID agentId) {
        if (hookRegistry == null) {
            return;
        }
        if (sessionId != null) {
            try {
                // [session-id-short] sessionId 已 short，直传 clearSessionHooks
                hookRegistry.clearSessionHooks(sessionId);
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 21.2b: 已清理 session hooks session={}", sessionId);
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 21.2b: clearSessionHooks(sessionId) 失败: {}", e.getMessage());
            }
        }
        if (agentId != null) {
            try {
                // [R3-WF-F IMP-SUB-12 返工] frontmatter hooks 以 a+16hex 键注册（Step 13 agentIdHex），
                //   cleanup 同键清理（S-12 桥还原）。
                hookRegistry.clearSessionHooks(AgentContext.unpackAgentId(agentId));
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] Step 21.2b: 已清理 frontmatter session hooks agent={} (a+16hex={})",
                        agentId, AgentContext.unpackAgentId(agentId));
                }
            } catch (Exception e) {
                log.warn("[SubagentExecutor] Step 21.2b: clearSessionHooks(agentId) 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * [P1-6-CLEANUP-1] 清理子 agent 的 invokedSkills · 对齐 CC {@code clearInvokedSkillsForAgent}
     * (bootstrap/state.ts:1557-1563)。
     *
     * <p>权威清理接线点：{@link #runSubagentQueryLoop} finally 调用本方法，覆盖 CC 4 个调用方
     * （fork/通用/后台/失败子 agent 全部终结于 runSubagentQueryLoop 的隔离 state）：
     * <ul>
     *   <li>CC SkillTool.ts:287 — fork 子 agent finally {@code clearInvokedSkillsForAgent(agentId)}</li>
     *   <li>CC AgentTool.tsx:1032 — 后台化 agent finally {@code clearInvokedSkillsForAgent(syncAgentId)}</li>
     *   <li>CC AgentTool.tsx:1187 — 子 agent 完成路径
     *       （注释 'Clean up scoped skills so they don't accumulate in the global map'）</li>
     *   <li>CC agentToolUtils.ts:683 — killed/failed 路径 finally
     *       {@code clearInvokedSkillsForAgent(agentIdForCleanup)}</li>
     * </ul>
     *
     * <p>隔离 state（{@code runSubagentQueryLoop} 构造）即子 agent 写入侧落点 —— 子 agent 完成
     * 或失败后释放其 skill 全文（每条含完整 skill content，杜绝累积泄漏 / stale skill 注入）。
     * package-private 供同包测试直测（对齐 {@link #cleanupSessionHooks} 既有可测约定）。
     *
     * @param state   子 agent 隔离 AgentState（{@code runSubagentQueryLoop} 内构造）
     * @param agentId 子 agent UUID
     */
    void cleanSubagentInvokedSkills(AgentState state, UUID agentId) {
        if (state == null || agentId == null) {
            return;
        }
        int removed = state.getInvokedSkillsForAgent(agentId).size();
        state.clearInvokedSkillsForAgent(agentId);
        if (log.isDebugEnabled()) {
            log.debug("[P1-6-CLEANUP-1] 子 agent 完成/失败，清理 invokedSkills agent={} removed={}",
                    agentId, removed);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // P2.2: SkillTool fork mode 入口 · 对齐 CC SkillTool.ts:622-632 executeForkedSkill
    // ════════════════════════════════════════════════════════════════════════

    /**
     * executeForkedSkill · 对齐 CC SkillTool.ts:122-289 executeForkedSkill()
     * for-await runAgent + onProgress (子任务 a: skill_progress 上报).
     *
     * <p>复用 {@link #execute(String, String, String)} 22 步主流程（隔离 sub-agent
     * 上下文 + 独立 transcript + 独立 hook）, 但 prompt 由 caller 构造（CC: 注入技能
     * 内容到 system prompt 后整个调用 delegation）. agent 类型来自
     * {@link Command#getAgent()}（缺省 "general-purpose"）.
     *
     * <p>[P1-18] 新增 fork 工具授权: 读取 {@code skill.getAllowedTools()} 经
     * {@code executeStreaming} 内部 8 参重载透传, 使 fork 子代理 getAppState 被
     * {@link #createForkGetAppStateWithAllowedTools} 包装 (对齐 CC forkedAgent.ts:203-209
     * {@code allowedTools = parseToolListFromCLI(command.allowedTools ?? [])} +
     * {@code modifiedGetAppState = createGetAppStateWithAllowedTools(...)}).
     * 旧 4 参兼容壳已删除 (对齐 CC 单一 executeForkedSkill 签名, grep 自验零外部调用方).
     *
     * @param prompt      技能内容 (CC original: skillContent, forkedAgent.ts:224)
     * @param skill       技能命令 (读取 agent 类型 / 模型覆盖 / allowedTools / effort)
     * @param args        用户传入 args（仅日志记录）
     * @param parentTuc   父 Agent ToolUseContext（透传, 不再丢弃）· CC original: toolUseContext
     *                    (SkillTool.ts:226-229 runAgent toolUseContext: {...context, ...})
     * @param messageSink 逐消息回调 (null = 非流式) · 对齐 CC runAgent.ts:748-806 for-await yield,
     *                    供 caller (SkillToolImpl) 检测 tool 内容并上报 skill_progress (SkillTool.ts:240-261)
     * @return SubagentResult（与 {@link #execute} 22 步流程同结构）
     */
    public SubagentResult executeForkedSkill(String prompt, Command skill, String args,
                                            ToolUseContext parentTuc,
                                            Consumer<SubagentMessage> messageSink) {
        String subagentType = skill.getAgent() != null && !skill.getAgent().isBlank()
                ? skill.getAgent()
                : BuiltInAgents.GENERAL_PURPOSE;
        String modelOverride = skill.getModel();
        // [P1-18] fork 工具授权: skill frontmatter allowedTools → executeStreaming 透传
        //   (对齐 CC forkedAgent.ts:203 allowedTools = parseToolListFromCLI(command.allowedTools ?? [])).
        List<String> forkAllowedTools = skill.getAllowedTools() != null
                ? skill.getAllowedTools() : List.of();
        log.info("[SubagentExecutor] executeForkedSkill: skill='{}' subagentType='{}' model='{}' argsLen={} "
                + "effort={} allowedTools={}",
                skill.getName(), subagentType, modelOverride,
                args != null ? args.length() : 0, skill.getEffort(), forkAllowedTools);
        // 对齐 CC SkillTool.ts:208-212: skill.effort 非空 → 经 executeStreaming 内部重载合并进 agentDefinition.
        //   forkParams=null → 非 fork path (SkillTool 走普通 subagent, 不触发 fork 缓存共享前缀)
        return executeStreaming(prompt, subagentType, modelOverride, null, messageSink,
                skill.getEffort(), parentTuc, forkAllowedTools, null, null, null);
    }

    /**
     * [P1-18] 创建携带 allowedTools 的 fork getAppState 包装 · CC original:
     * {@code createGetAppStateWithAllowedTools(baseGetAppState, allowedTools)} (forkedAgent.ts:147-171).
     *
     * <p><b>CC 真源 (Read 实证)</b>:
     * <ol>
     *   <li>{@code if (allowedTools.length === 0) return baseGetAppState} (:151 no-op 守卫)</li>
     *   <li>{@code return () => { const appState = baseGetAppState(); return {...appState,
     *       toolPermissionContext: {...appState.toolPermissionContext, alwaysAllowRules:
     *       {...appState.toolPermissionContext.alwaysAllowRules, command: [...new Set([
     *       ...(appState.toolPermissionContext.alwaysAllowRules.command || []), ...allowedTools])]}}}}}
     *       (:153-166) — 把 allowedTools 去重合入 appState.toolPermissionContext.alwaysAllowRules.command</li>
     * </ol>
     *
     * <p><b>Java 表示</b>: getAppState 是 {@code Function<Map,Map>} (取 prev 快照返回 next 快照),
     * base 快照 = {@code baseGetAppState.apply(prev)}; 合并语义复用
     * {@link SkillToolImpl#mergeAllowedToolsIntoAppState} (package-private 单源, 避免双实现漂移),
     * 把 allowedTools 以 whole-tool ALLOW rule 并入快照 toolPermissionContext.alwaysAllowRules[COMMAND]
     * (去重). 消费方 {@code AgentLoopContext.mergeAppStateCommandRules} (:1140-1160) 逐轮读
     * {@code baseTuc.getAppState().apply(null)} 快照的 COMMAND 桶并入 fork 子代理 per-turn permCtx
     * → 技能声明工具在 fork 子代理内不再被权限层阻断.
     *
     * @param baseGetAppState 基础 getAppState (父 TUC.getAppState 桥接会话 appStateRef)
     * @param allowedTools    skill frontmatter allowedTools (CC original: parseToolListFromCLI 输出,
     *                        forkedAgent.ts:203); null/空 → no-op 原样返回 base
     * @return 包装后 getAppState; allowedTools 空时返回原函数 (no-op 守卫, 对齐 :151)
     */
    static Function<Map<String, Object>, Map<String, Object>> createForkGetAppStateWithAllowedTools(
            Function<Map<String, Object>, Map<String, Object>> baseGetAppState,
            List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            // CC forkedAgent.ts:151 no-op 守卫: allowedTools 空 → 原样返回 baseGetAppState
            return baseGetAppState;
        }
        return prev -> {
            Map<String, Object> baseSnapshot = baseGetAppState != null ? baseGetAppState.apply(prev) : null;
            // 复用 SkillToolImpl.mergeAllowedToolsIntoAppState 单源合并语义 (CC :160-166 command 桶去重)
            return SkillToolImpl.mergeAllowedToolsIntoAppState(baseSnapshot, allowedTools);
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 2 helper: resolve AgentDefinition
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // Step 2 helper: resolve AgentDefinition
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析 AgentDefinition · CC original: {@code activeAgents.find} (AgentTool.tsx:286)。
     *
     * <p>W-2a 可见性提升：由 package-private 扩为 public，供
     * {@link com.nexusai.application.agent.workflow.agent.ClaudeCodeBackendAdapter#resolveAgentDefinition}
     * （跨包，workflow.agent 域）复用 CC claudeCodeBackend.ts:47-56 的 activeAgents.find 语义。
     * 仅放宽访问修饰符，签名/行为不变（LOW risk）。
     */
    public AgentDefinition resolveAgentDefinition(String agentType) {
        // [ODF-C3] 附加 agents map 优先命中（对齐 CC print.ts:4381-4383 request.agents 并入
        //   子 Agent agents 列表 — SDK 传入 flagSettings agent 在子 loop 最局部可达）。
        if (additionalAgentDefinitions != null && agentType != null) {
            AgentDefinition extra = additionalAgentDefinitions.get(agentType);
            if (extra != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] resolveAgentDefinition 命中附加 agent: type={} source={}",
                        agentType, extra.source());
                }
                return extra;
            }
        }
        // [FIX-AM REQ-M-19] 生产接线：自定义 agent 解析器优先（SubagentTool 注入
        //   agentRegistry::findAgent，对齐 CC AgentTool.tsx:286 activeAgents.find）。
        //   自定义 memory agent（.claude/agents/*.md 带 memory 字段）由此可达，
        //   不再因仅查 BuiltInAgents 返回 null → AgentNotFoundException。
        if (agentDefinitionResolver != null && agentType != null) {
            AgentDefinition custom = agentDefinitionResolver.apply(agentType);
            if (custom != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] resolveAgentDefinition 命中自定义 agent: type={} source={}",
                        agentType, custom.source());
                }
                return custom;
            }
        }
        AgentDefinition builtIn = BuiltInAgents.get(agentType);
        if (builtIn != null) return builtIn;
        return null;
    }

    /**
     * [P0-1] effort 合并 · 对齐 CC SkillTool.ts:208-212 ({@code {...baseAgent, effort: command.effort}}).
     *
     * <p>CC 对象展开 ({@code ...baseAgent}) 等价 Java 端 Builder 全字段复制 + effort 覆盖.
     * {@link #resolveAgentDefinition} 经 [FIX-AM] agentDefinitionResolver 可产出自定义 agent;
     * 本方法仅 BuiltIn 分支做 Builder 全字段复制 (自定义 agent 已含自身 effort, 原样返回 fail-safe).
     *
     * <p>[C-31] effort 消费点已打通: runSubagentQueryLoop 子 AgentState 注入
     * {@code agentDefinition.effort} → LlmAgentLoop ModelRequest 构造读 {@code state.effortValue()}
     * （:2762 等价 query.ts:694）→ ModelCaller → AnthropicSdkProvider buildMessageParams
     * （output_config.effort + effort-2025-11-24 beta header，claude.ts:1458 + 437-463）。
     * 本方法合并的数据形态因此真实作用于 fork LLM 请求（SkillTool.ts:208-212 语义闭环）。
     *
     * @param base   基础 AgentDefinition (BuiltIn)
     * @param effort skill frontmatter effort 值 (Command.getEffort, Command.java:172)
     * @return 携带 effort 的新 AgentDefinition; 非 BuiltIn 类型原样返回
     */
    private static AgentDefinition withEffort(AgentDefinition base, String effort) {
        if (base instanceof AgentDefinition.BuiltInAgentDefinition b) {
            return AgentDefinition.BuiltInAgentDefinition.builder(
                    b.agentType(), b.whenToUse(), (modelId, dirs) -> b.getSystemPrompt(modelId, dirs))
                .tools(b.tools().orElse(null))
                .disallowedTools(b.disallowedTools().orElse(null))
                .skills(b.skills().orElse(null))
                .mcpServers(b.mcpServers().orElse(null))
                .hooks(b.hooks().orElse(null))
                .color(b.color().orElse(null))
                .model(b.model().orElse(null))
                .effort(effort)
                .permissionMode(b.permissionMode().orElse(null))
                .maxTurns(b.maxTurns().orElse(null))
                .criticalSystemReminder_EXPERIMENTAL(b.criticalSystemReminder_EXPERIMENTAL().orElse(null))
                .requiredMcpServers(b.requiredMcpServers().orElse(null))
                .background(b.background().orElse(null))
                .initialPrompt(b.initialPrompt().orElse(null))
                .memory(b.memory().orElse(null))
                .isolation(b.isolation().orElse(null))
                .omitClaudeMd(b.omitClaudeMd().orElse(null))
                .build();
        }
        return base;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 5 helper: resolvePermissionMode
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [H9 v3 Gap①] 计算子 agent 最终生效的 permissionMode · 对齐 CC runAgent.ts:419-432.
     *
     * <p><b>WHY (H9 v3 对抗核验缺口①)</b>: CC 真源 — fork 子 agent 的
     * {@code agentPermissionMode = agentDefinition.permissionMode} (ForkSubagent 固定 "bubble")，
     * 覆盖子 {@code toolPermissionContext.mode}，但父 mode 为 {@code bypassPermissions /
     * acceptEdits / auto} 时父优先级更高不覆盖 (runAgent.ts:424-431)。本方法封装该覆盖决策，
     * 供 {@link #runSubagentQueryLoop} 应用到子 base TUC，让 BUBBLE 真正落到生产 TUC
     * （此前 resolvePermissionMode 结果只传参不落地）。
     *
     * @param agentDefinition 子 agent 定义 (读 permissionMode 是否显式定义)
     * @param resolvedMode    {@link #resolvePermissionMode(AgentDefinition)} 的解析结果
     *                        (agent 显式定义时的值; 未定义时为 DEFAULT)
     * @param parentMode      父 agent 的 permissionMode (子 TUC 当前继承值)
     * @return 最终生效的 permissionMode (agent 未定义 → 继承父; 父高优先级 → 父; 否则 → resolved)
     */
    static PermissionMode resolveEffectiveForkMode(AgentDefinition agentDefinition,
            PermissionMode resolvedMode, PermissionMode parentMode) {
        if (agentDefinition == null || agentDefinition.permissionMode().isEmpty()) {
            // agent 未定义 → 继承父 (子 TUC 已从父继承, 无需覆盖)
            return parentMode;
        }
        if (parentMode == PermissionMode.BYPASS_PERMISSIONS
                || parentMode == PermissionMode.ACCEPT_EDITS
                || parentMode == PermissionMode.AUTO) {
            // 父优先级更高 (CC runAgent.ts:424-431): bypassPermissions/acceptEdits/auto 不覆盖
            return parentMode;
        }
        return resolvedMode;
    }

    /**
     * [B-2] 计算子 agent 的 shouldAvoidPermissionPrompts · 对齐 CC runAgent.ts:440-451.
     *
     * <p><b>WHY (B-2 死参数收尾)</b>: 旧实现 {@code shouldAvoidPermissionPrompts = isAsync}
     * 漏掉 CC 的 bubble 例外, 且结果只传参不落地 (runSubagentQueryLoop 函数体未使用) —
     * 死参数. CC 真源 ({@code runAgent.ts:440-451}):
     * <pre>
     *   shouldAvoidPrompts = canShowPermissionPrompts !== undefined
     *     ? !canShowPermissionPrompts
     *     : (agentPermissionMode === 'bubble' ? false : isAsync)
     * </pre>
     * Java {@link AgentDefinition} 无 {@code canShowPermissionPrompts} 字段 (完整透传
     * 留 P3, H9-GAP-6 已文档化) → 本方法实现降级公式:
     * <ul>
     *   <li>agent 定义 mode == BUBBLE (fork 子 agent, CC forkSubagent.ts:67) → false
     *       (bubble 冒泡到父终端恒可弹窗, CC :443-445)</li>
     *   <li>其余 → isAsync (同步子 agent 可弹窗; 异步后台 agent 无 UI → 自动拒绝)</li>
     * </ul>
     *
     * @param agentPermissionMode 子 agent 定义解析出的 permissionMode (CC agentPermissionMode)
     * @param isAsync             是否异步后台 agent (CC isAsync)
     * @return 是否自动拒绝权限弹窗
     */
    static boolean resolveShouldAvoidPermissionPrompts(PermissionMode agentPermissionMode, boolean isAsync) {
        return agentPermissionMode != PermissionMode.BUBBLE && isAsync;
    }

    PermissionMode resolvePermissionMode(AgentDefinition agentDefinition) {
        String mode = agentDefinition.permissionMode().orElse(null);
        if (mode == null) return PermissionMode.DEFAULT;
        try {
            return PermissionMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PermissionMode.DEFAULT;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // S3 fork 缓存共享 helpers (对齐 CC runAgent.ts:508-509/682-684/694/866-904 + AgentTool.tsx:512/630/632)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Step 9 决策: agentSystemPrompt 来源 · 对齐 CC runAgent.ts:508-509
     * ({@code agentSystemPrompt = override?.systemPrompt ? override.systemPrompt : ...}).
     *
     * <p>fork path + forkParentSystemPrompt 非空 → 直接用父 rendered bytes (prompt cache 共享前提,
     * 跳过 buildAgentSystemPrompt 重新构造); 否则返回 {@code computedAgentSystemPrompt.get()}
     * (非 fork path / fork 但父提示缺失). 第三参为 Supplier 而非 String — CC 三元短路右侧
     * 不求值, 惰性化对齐 (C-7 残留清理), fork path 不执行 buildAgentSystemPrompt.
     *
     * <p>[RES-SP31-1 返工] append 为 fork-only：CC 真源复验确认 append 内容仅经
     * buildEffectiveSystemPrompt（systemPrompt.ts:115-122）达 fork 路径（resumeAgent.ts:135-141 在
     * isResumedFork 内）；非 fork resume override:undefined（resumeAgent.ts:183-185）→
     * runAgent.ts:508-518 getAgentSystemPrompt（:906-924 = [agentPrompt] + env）不含 append。故此处
     * 不追加 append：非 fork resume 返回 computed 原样；fork resume 的 forkParentSystemPrompt
     * （ResumeService rendered 补 append / 重建路径已含 append）原样直用，无重复追加。
     *
     * @param isForkPath              fork 路径标志
     * @param forkParams              fork 缓存共享参数 (forkParentSystemPrompt + resume 字段来源)
     * @param computedAgentSystemPrompt 非 fork path 的 buildAgentSystemPrompt 输出
     * @return 最终 agentSystemPrompt
     */
    static String resolveForkAgentSystemPrompt(boolean isForkPath, ForkPathParams forkParams,
            java.util.function.Supplier<String> computedAgentSystemPrompt) {
        if (isForkPath && forkParams != null
                && forkParams.forkParentSystemPrompt() != null
                && !forkParams.forkParentSystemPrompt().isBlank()) {
            String parentPrompt = forkParams.forkParentSystemPrompt();
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] fork path: agentSystemPrompt 采用父 rendered bytes "
                        + "长度={}, 跳过 buildAgentSystemPrompt (prompt cache 共享前提, CC runAgent.ts:508-509)",
                    parentPrompt.length());
            }
            return parentPrompt;
        }
        String computed = computedAgentSystemPrompt.get();
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] 非 fork path: agentSystemPrompt 由 buildAgentSystemPrompt "
                    + "构造长度={}", computed.length());
        }
        return computed;
    }

    /**
     * Step 10 fork 前缀装配 · 对齐 CC runAgent.ts:370-373 + AgentTool.tsx:512/630.
     *
     * <p>WHY 缓存共享 (决策 1 目标 1+3): {@code initialMessages =
     * [...filterIncompleteToolCalls(forkContextMessages), ...buildForkedMessages(prompt, assistantMessage)]}.
     * forkContextMessages 是父对话历史, 经 {@link #filterIncompleteToolCalls} 剔除含未配对 tool_use
     * 的 assistant 消息 (避免 API 拒绝残缺序列); promptMessages 由 {@link ForkSubagentMessages#buildForkedMessages}
     * 构造 [克隆 assistant (tool_use) + tool_result 占位 + directive] — 激活 dead code.
     *
     * <p>边界: assistantMessage null / 非 {@link ForkSubagentMessages.AssistantMessage} → 降级纯
     * user 消息 (不 NPE, 对齐 CC forkSubagent.ts:127-139 无 tool_use 边界逻辑).
     *
     * @param prompt      用户指令 (buildForkedMessages directive)
     * @param forkParams  fork 缓存共享参数 (forkContextMessages + assistantMessage 来源)
     * @return initialMessages 前缀 Map 列表 (contextMessages + promptMessages)
     */
    static List<Map<String, Object>> assembleForkInitialMessages(String prompt, ForkPathParams forkParams) {
        List<Map<String, Object>> maps = new ArrayList<>();
        // 1. contextMessages = filterIncompleteToolCalls(forkContextMessages) 转 Map (CC runAgent.ts:370-371)
        List<?> rawContext = forkParams != null ? forkParams.forkContextMessages() : List.of();
        List<ChatMessageDto> contextMessages = filterIncompleteToolCalls(rawContext);
        for (ChatMessageDto cm : contextMessages) {
            maps.add(chatMessageToMap(cm));
        }
        // 2. promptMessages = buildForkedMessages(prompt, assistantMessage) 转 Map (CC AgentTool.tsx:512)
        ForkSubagentMessages.Message assistantMessage = forkParams != null ? forkParams.assistantMessage() : null;
        if (assistantMessage instanceof ForkSubagentMessages.AssistantMessage forkAssistant) {
            List<ForkSubagentMessages.Message> forkedMessages =
                ForkSubagentMessages.buildForkedMessages(prompt, forkAssistant);
            maps.addAll(toInitialMessageMaps(forkedMessages));
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] fork 缓存共享: buildForkedMessages 调用, "
                        + "forkContextMessages.size={} (过滤后 {}), forkedMessages.size={}",
                    rawContext.size(), contextMessages.size(), forkedMessages.size());
            }
        } else {
            // 边界: assistantMessage 缺失 → 降级纯 user 消息 (不 NPE)
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("role", "user");
            fallback.put("content", prompt);
            maps.add(fallback);
            if (log.isWarnEnabled()) {
                log.warn("[SubagentExecutor] fork path 但 assistantMessage 缺失, 降级为纯 user 消息 (不 NPE)");
            }
        }
        return maps;
    }

    /**
     * 剔除含未配对 tool_use 的 assistant 消息 · 对齐 CC runAgent.ts:866-904 filterIncompleteToolCalls.
     *
     * <p>WHY: fork 子 agent 的 initialMessages 前缀来自父对话历史 (forkContextMessages), 若其中
     * assistant 消息含 tool_use 但无对应 tool_result (压缩/中断残留), API 会拒绝残缺 tool_use 序列.
     * CC 收集有结果的 tool_use_id (tool_result 的 tool_use_id), 剔除含未配对 tool_use 的 assistant
     * 消息 (runAgent.ts:875-904).
     *
     * <p><b>@SharedLogic</b>: 算法委托 {@link MessageFilters#filterIncompleteToolCallsImpl} 单源,
     * 与 {@code MessageFilters.filterIncompleteToolCalls(List<AgentMessage>)} 共用同一实现, 两路语义
     * 等价对齐 CC runAgent.ts:866-904. 本方法仅做 ChatMessageDto accessor 适配 + 既有 fail-loud
     * 契约 (非 ChatMessageDto 元素剔除, rawContext 实际全为 ChatMessageDto 故此分支 dead).
     *
     * <p>类型映射: CC 端 tool_result 在 user 消息 content 内 (Anthropic 风格 BetaBlock[]);
     * Java 端 ChatMessageDto role=tool 消息的 toolCallId 即对应 tool_result 的 tool_use_id.
     * 泛型 accessor 方案不转类型, 字段零丢失 (reasoning/isError 等保留).
     *
     * @param messages 父对话历史 (List&lt;?&gt; 实为 List&lt;ChatMessageDto&gt;); null/空 → 空列表
     * @return 过滤后的消息列表 (非 assistant 消息 + toolCalls 全配对的 assistant 消息)
     */
    static List<ChatMessageDto> filterIncompleteToolCalls(List<?> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        // 保留既有 fail-loud 契约: 非 ChatMessageDto 元素剔除 (rawContext 实际全为 ChatMessageDto,
        // 此分支 dead, 但保留契约以防回归). 仅 ChatMessageDto 元素进入单源算法.
        List<ChatMessageDto> dtos = new ArrayList<>(messages.size());
        for (Object m : messages) {
            if (m instanceof ChatMessageDto dto) {
                dtos.add(dto);
            }
        }
        List<ChatMessageDto> filtered = MessageFilters.filterIncompleteToolCallsImpl(
            dtos,
            d -> d.role() == null ? null : d.role().name().toLowerCase(),
            ChatMessageDto::toolCallId,
            d -> d.toolCalls() == null ? null : d.toolCalls().stream().map(ToolCallDto::id).toList());
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] filterIncompleteToolCalls 委托 MessageFilters 单源, rawContext.size={}, 过滤后 size={}",
                messages.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * ForkSubagentMessages.Message → initialMessages Map · 对齐 CC forkSubagent.ts:107-169.
     *
     * <p>Anthropic SDK BetaBlock[] → Java 消息 Map: assistant 消息的 tool_use 块 → toolCalls 键
     * (供 convertToChatMessageDto 还原 → provider 序列化 tool_use); user 消息的 tool_result 块 →
     * 独立 tool-role Map (toolCallId 键, 供 provider 序列化 tool_result), text 块 → user-role Map.
     *
     * @param messages buildForkedMessages 输出
     * @return initialMessages 格式 Map 列表
     */
    static List<Map<String, Object>> toInitialMessageMaps(List<ForkSubagentMessages.Message> messages) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (messages == null) {
            return maps;
        }
        for (ForkSubagentMessages.Message m : messages) {
            if (m instanceof ForkSubagentMessages.AssistantMessage am) {
                Map<String, Object> assistantMap = new LinkedHashMap<>();
                assistantMap.put("role", "assistant");
                StringBuilder text = new StringBuilder();
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ForkSubagentMessages.ContentBlock block : am.content()) {
                    if (block instanceof ForkSubagentMessages.BetaTextBlock tb) {
                        if (tb.text() != null && !tb.text().isBlank()) {
                            text.append(tb.text()).append("\n");
                        }
                    } else if (block instanceof ForkSubagentMessages.BetaToolUseBlock tu) {
                        Map<String, Object> tc = new LinkedHashMap<>();
                        tc.put("id", tu.id());
                        tc.put("name", tu.name());
                        tc.put("arguments", tu.input() != null ? tu.input().toString() : "{}");
                        toolCalls.add(tc);
                    }
                }
                if (text.length() > 0) {
                    assistantMap.put("content", text.toString().trim());
                }
                if (!toolCalls.isEmpty()) {
                    assistantMap.put("toolCalls", toolCalls);
                }
                maps.add(assistantMap);
            } else if (m instanceof ForkSubagentMessages.UserMessage um) {
                for (ForkSubagentMessages.ContentBlock block : um.content()) {
                    if (block instanceof ForkSubagentMessages.BetaToolResultBlock tr) {
                        Map<String, Object> toolMap = new LinkedHashMap<>();
                        toolMap.put("role", "tool");
                        toolMap.put("toolCallId", tr.toolUseId());
                        toolMap.put("content", ForkSubagentMessages.FORK_PLACEHOLDER_RESULT);
                        maps.add(toolMap);
                    } else if (block instanceof ForkSubagentMessages.BetaTextBlock tb) {
                        Map<String, Object> userMap = new LinkedHashMap<>();
                        userMap.put("role", "user");
                        userMap.put("content", tb.text() != null ? tb.text() : "");
                        maps.add(userMap);
                    }
                }
            }
        }
        return maps;
    }

    /**
     * resume 消息链 → initialMessages Map · 对齐 CC resumeAgent.ts:166-171 promptMessages 透传
     * (transcript 消息直用, 不二次过滤).
     *
     * <p>AgentMessage → initialMessages 格式 Map (供 {@link #convertToChatMessageDto} 还原):
     * <ul>
     *   <li>user/assistant/system → {role, content}</li>
     *   <li>assistant 含 toolCalls → 追加 toolCalls 键 (id/name/arguments) — provider 序列化 tool_use 块</li>
     *   <li>tool → {role=tool, toolCallId, content, isError=isApiError} — provider 序列化 tool_result</li>
     * </ul>
     * transcript 内部字段 (agentId/isSidechain/uuid/parentUuid) 剥离 — 对齐 CC resume 只取
     * messages 的 role/content/tool_use/tool_result 语义, uuid 链由 Step 19.5 重建.
     *
     * @param messages 三层过滤后的 AgentMessage 列表 (resumeAgent.ts:70-74)
     * @return initialMessages 格式 Map 列表
     */
    static List<Map<String, Object>> resumedMessagesToMaps(List<AgentMessage> messages) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (messages == null) {
            return maps;
        }
        for (AgentMessage m : messages) {
            if (m == null) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            String role = m.role() != null ? m.role() : "user";
            map.put("role", role);
            String content = m.content() != null ? m.content() : "";
            map.put("content", content);
            if ("assistant".equals(role) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (AgentMessage.ToolCallInfo tc : m.toolCalls()) {
                    Map<String, Object> tcm = new LinkedHashMap<>();
                    tcm.put("id", tc.id());
                    tcm.put("name", tc.name());
                    tcm.put("arguments", tc.arguments() != null ? tc.arguments() : "{}");
                    toolCalls.add(tcm);
                }
                map.put("toolCalls", toolCalls);
            }
            if ("tool".equals(role)) {
                map.put("toolCallId", m.toolCallId());
                if (m.isApiError()) {
                    map.put("isError", true);
                }
            }
            maps.add(map);
        }
        return maps;
    }

    /**
     * Step 17 决策: thinkingConfig · 对齐 CC runAgent.ts:682-684.
     *
     * <p>fork path (useExactTools) → 继承父 thinkingConfig (ForkPathParams.parentThinkingConfig,
     * 决策 S3-6 B: 经 ForkPathParams 直带, 绕开 LlmAgentLoop.buildSubagentAgentOptions 硬编码 null);
     * 非 fork path → {@code {type:'disabled'}} (CC runAgent.ts:684, 控制输出 token 成本). 旧偏差
     * {@code isForkAgentType ? Map.of("type","enabled") : Map.of()} 是 S3-7: fork 传 enabled 与父
     * 不同 → cache key 不一致 → prompt cache 失效.
     *
     * @param isForkPath fork 路径标志
     * @param forkParams fork 缓存共享参数 (parentThinkingConfig 来源)
     * @return 最终 thinkingConfig (fork 继承父可能 null → AgentRunOptions compact ctor 兜底 Map.of())
     */
    static Map<String, Object> resolveForkThinkingConfig(boolean isForkPath, ForkPathParams forkParams) {
        if (isForkPath && forkParams != null && forkParams.parentThinkingConfig() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inherited = (Map<String, Object>) forkParams.parentThinkingConfig();
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] fork path: thinkingConfig 继承父={} "
                        + "(cache key 一致, 对齐 CC runAgent.ts:682-683)",
                    inherited);
            }
            return inherited;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] 非 fork path: thinkingConfig={type:'disabled'} "
                    + "(控制输出 token 成本, 对齐 CC runAgent.ts:684)");
        }
        return Map.of("type", "disabled");
    }

    /**
     * R1-THINK: 派生 thinkingConfig Map → {@link ThinkingConfig} record 转换 · 对齐 CC runAgent.ts:682-684
     * + thinking.ts:10-13 union 三态.
     *
     * <p><b>WHY</b>: {@code resolveForkThinkingConfig} 输出的 Map（落到 {@code AgentRunOptions.thinkingConfig}）
     * 此前在 {@code runSubagentQueryLoop} 零读取 —— {@code QueryParams.forLoop} 恒 null→disabled，fork child
     * 运行时未继承父 thinking → 子请求 thinking 与父不一致 → cache key 偏移 → prompt cache 失效
     * （DISC-SUB-03 EV-FK-014 / Q2）。本方法把派生 map 转回 {@code ThinkingConfig}
     * （{@code QueryParams.withThinkingConfig} 注入型），使 runSubagentQueryLoop 把继承的父 thinking
     * 真正注入 query loop（CC runAgent.ts:682-683 语义）。
     *
     * <p>Map 键约定（对齐 {@code resolveForkThinkingConfig} / {@code SubagentExecutorForkPathTest}）:
     * {@code type} 三态（disabled/adaptive/enabled）；{@code budget_tokens}（snake_case，仅 enabled 需要，
     * 与父 AgentOptions.thinkingConfig 及 API 线格式一致）。enabled 缺 budget_tokens 为畸形输入 →
     * 显式降级 disabled（规则十二 显式失败，不静默构造 enabled(0)）。
     *
     * @param thinkingMap 派生 thinkingConfig Map（{@code resolveForkThinkingConfig} 输出；可为 null）
     * @return ThinkingConfig（type 三态映射；null/空/畸形 → disabled）
     */
    static ThinkingConfig toThinkingConfig(Map<String, Object> thinkingMap) {
        if (thinkingMap == null || thinkingMap.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] toThinkingConfig: 空/无 thinkingConfig map → disabled "
                        + "(CC thinking.ts:13)");
            }
            return ThinkingConfig.disabled();
        }
        Object typeObj = thinkingMap.get("type");
        String type = typeObj instanceof String s ? s : null;
        ThinkingConfig result;
        if ("enabled".equals(type)) {
            Object budgetObj = thinkingMap.get("budget_tokens");
            if (budgetObj instanceof Number n) {
                result = ThinkingConfig.enabled(n.intValue());
            } else {
                log.warn("[SubagentExecutor] toThinkingConfig: enabled 缺 budget_tokens（畸形输入），"
                        + "显式降级 disabled: {}", thinkingMap);
                result = ThinkingConfig.disabled();
            }
        } else if ("adaptive".equals(type)) {
            result = ThinkingConfig.adaptive();
        } else {
            result = ThinkingConfig.disabled();
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] toThinkingConfig: 派生 map {} → ThinkingConfig(type={}, budgetTokens={})",
                thinkingMap, result.type(), result.budgetTokens());
        }
        return result;
    }

    /**
     * [R2-CTX] 构造 subagent spawn 的 AgentContext（analytics 归因）· 对齐 CC AgentTool.tsx:719-727/:772-780
     * {@code asyncAgentContext / syncAgentContext} object literal（每次 spawn 新建 context）。
     *
     * <p><b>WHY</b>: CC 每次 spawn 都把整个 agent 执行包进 {@code runWithAgentContext(context, ...)}
     * （AgentTool.tsx:733 async / :785 sync / :911 background 三处），使 query loop 内的事件
     * （{@code getSubagentLogName()} → {@code subagent_name} 属性、{@code getAgentContext()} →
     * parent_agent_id）能正确归因到该子 agent。Java 现状（S-13 / DISC-SUB-03 EV-FK-015）：SubagentTool
     * → SubagentExecutor 无包裹，{@link AgentContext#getSubagentLogName()} 在 query loop 内恒 null
     * → {@code SessionFileAccessHooks.subagentProps()} 空 map → 事件缺 {@code subagent_name}。
     * 本方法把 CC object literal 的字段映射为 {@link AgentContext.SubagentContext}，executeStreaming
     * Step 20 用 {@link AgentContext#runWithAgentContext} 包裹 {@code runSubagentQueryLoop}。
     *
     * <p>字段映射（CC AgentTool.tsx:719-727）:
     * <ul>
     *   <li>{@code agentId} — 子 agent UUID（Step 5 createSubagentContext 生成，CC createAgentId()）</li>
     *   <li>{@code parentSessionId} — null（CC :720 getParentSessionId()；main REPL subagent 为 undefined；
     *       Java 生产无 TeammateAgentContext 接线 → 恒 undefined 等价 null）</li>
     *   <li>{@code subagentName} — agent 类型名（CC :722 selectedAgent.agentType，如 "Explore"）</li>
     *   <li>{@code isBuiltIn} — 是否内置 agent（CC :723 isBuiltInAgent(selectedAgent)；
     *       Java 用 {@code instanceof AgentDefinition.BuiltInAgentDefinition} 等价）</li>
     *   <li>{@code invokingRequestId} — 父 assistant message 的 requestId（CC :726
     *       {@code assistantMessage?.requestId}；[RF-1] 经 ForkSubagentMessages.AssistantMessage.requestId
     *       透传，非 fork / 流式 provider 未捕获 request_id 时为 null）</li>
     *   <li>{@code invocationKind} — "spawn"|"resume"（CC :725；resume 由 forkParams.resumedMessages
     *       判定）</li>
     * </ul>
     *
     * <p><b>static seam（Pattern #14 RED-GREEN 双证）</b>: executeStreaming 全流程依赖 LLM 循环重依赖，
     * 单测无法起全流程；本方法为 package-private static，让 {@code SubagentAgentContextWiringTest} 直测
     * 字段映射语义 = 验证生产逻辑。
     *
     * @param agentId          子 agent UUID（Step 5 生成）
     * @param subagentName     agent 类型名（CC selectedAgent.agentType）
     * @param isBuiltIn        是否内置 agent（CC isBuiltInAgent）
     * @param invokingRequestId 父 assistant message 的 requestId（CC :726 assistantMessage?.requestId；
     *                          nullable，非 fork / 流式 provider 未捕获时为 null）
     * @param invocationKind   "spawn" | "resume"（CC AgentTool.tsx:725）
     * @return 不可变 SubagentContext（每次 spawn 新建，invocationEmitted=AtomicBoolean(false)）
     */
    static AgentContext.SubagentContext buildSubagentAgentContext(
            UUID agentId, String subagentName, boolean isBuiltIn, String invokingRequestId, String invocationKind) {
        // [R3-WF-F IMP-SUB-12 返工] SubagentContext.agentId 输出 a+16hex（对齐 CC agentContext.ts:34
        //   SubagentContext.agentId = createAgentId() 产物；Java 经 S-12 pack/unpack 桥还原）。
        String agentIdHex = agentId != null ? AgentContext.unpackAgentId(agentId) : agentId != null ? agentId.toString() : null;
        AgentContext.SubagentContext ctx = new AgentContext.SubagentContext(
            agentIdHex,           // agentId: CC :34 (a+16hex)
            null,                 // parentSessionId: main REPL subagent → undefined (CC :720)
            subagentName,         // subagentName: selectedAgent.agentType (CC :722)
            isBuiltIn,            // isBuiltIn: isBuiltInAgent(selectedAgent) (CC :723)
            invokingRequestId,    // invokingRequestId: assistantMessage?.requestId (CC :726)
            invocationKind        // invocationKind: 'spawn' | 'resume' (CC :725)
        );
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [R2-CTX] 构造 subagent AgentContext: agentId={} (a+16hex={}) subagentName={} "
                    + "isBuiltIn={} invokingRequestId={} invocationKind={} (CC AgentTool.tsx:719-727)",
                agentId, agentIdHex, subagentName, isBuiltIn, invokingRequestId, invocationKind);
        }
        return ctx;
    }

    /**
     * fork 子 Agent 专属 AgentOptions · 对齐 CC runAgent.ts:682-684 + :694 + AgentTool.tsx:631-632.
     *
     * <p>context.options 必须带 querySource='agent:builtin:fork' (抗 autocompact 递归守卫, 关闭
     * Pattern #11 bypass) + 继承父 thinkingConfig (cache key 一致) + useExactTools=true
     * (CC AgentTool.tsx:631-632).
     *
     * @param forkParams fork 缓存共享参数 (parentThinkingConfig 来源)
     * @return fork 专属 AgentOptions (Step 5 本地持有，经 mergeOptionsAgentDefinitions 并入子 registry；
     *         IMP-SUB-19 #23: 原"传入 3 参 create"通道已随包装 record 删除)
     */
    static AgentOptions buildForkAgentOptions(ForkPathParams forkParams) {
        return buildForkAgentOptions(forkParams, Map.of());
    }

    /**
     * [ODF-C3] fork 子 Agent 专属 AgentOptions + 附加 agents map · 对齐 CC runAgent.ts:682-684
     * + :694 + AgentTool.tsx:631-632 + print.ts:4381-4383 (SDK request.agents 并入子 ctx)。
     *
     * <p>context.options.agentDefinitions 必须携带 SDK/flag agents map，createSubagentContext
     * 才能 merge 进子 loop（验收 #2 子 Agent prompt 可列出 flagSettings agent）。
     * querySource='agent:builtin:fork'（抗 autocompact 递归守卫）+ useExactTools=true。
     *
     * @param forkParams                  fork 缓存共享参数 (parentThinkingConfig 来源)
     * @param additionalAgentDefinitions  附加 agents map（flagSettings/plugin 来源；null → 空）
     * @return fork 专属 AgentOptions (Step 5 本地持有，经 mergeOptionsAgentDefinitions 并入子 registry；
     *         IMP-SUB-19 #23: 原"传入 3 参 create"通道已随包装 record 删除)
     */
    static AgentOptions buildForkAgentOptions(ForkPathParams forkParams,
                                              Map<String, AgentDefinition> additionalAgentDefinitions) {
        Object parentThinkingConfig = forkParams != null ? forkParams.parentThinkingConfig() : null;
        Map<String, AgentDefinition> agentDefs = additionalAgentDefinitions != null
            ? additionalAgentDefinitions : Map.of();
        AgentOptions options = new AgentOptions(
            Map.of(),                            // tools: 子 agent 工具在 loop 内独立解析
            true,                                // useExactTools: fork path 恒 true (CC AgentTool.tsx:631-632)
            parentThinkingConfig,                // thinkingConfig: 继承父 (CC runAgent.ts:682-683)
            Map.of(), Map.of(), agentDefs,       // mcpClients / mcpResources / agentDefinitions (ODF-C3)
            ForkSubagent.FORK_QUERY_SOURCE,      // querySource: 'agent:builtin:fork' (CC runAgent.ts:694)
            false);                              // canReadOutputFile
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] fork 子 ctx.options: querySource={} useExactTools=true "
                    + "thinkingConfig={} (抗 autocompact 递归守卫, 对齐 CC runAgent.ts:688-694)",
                ForkSubagent.FORK_QUERY_SOURCE, parentThinkingConfig);
        }
        return options;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 7 helper: resolveAgentTools
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析子 Agent 可用工具 · 对齐 CC {@code resolveAgentTools} (agentToolUtils.ts:122-225).
     *
     * <p>[S4 P1 差异项 5] 内部必调 {@code filterToolsForAgent} (CC :142): 剔除 Agent/TaskOutput/
     * ExitPlanMode 等递归/主线程抽象工具 + CUSTOM_AGENT_DISALLOWED + async 白名单. 旧实现
     * {@code subagentToolRegistry.all()} 全量返回 (:1028/:1032) 不调 filter = 双路径漂移风险
     * (过滤靠 SubagentTool.createSubagentToolRegistry 外层做).
     *
     * <p>S4-6 决策: registry 由 createSubagentToolRegistry 预过滤 (async 白名单需 run_in_background
     * 真值, executor 无从得知), 本处再调 filterToolsForAgent 是对 registry 池的兜底过滤 — 该函数
     * 纯 membership 判定, 双过滤幂等, 不改变结果. plan-mode agent 的 ExitPlanMode 放行 (CC :88-93)
     * 需 registry 未删该工具才生效 — [OPD-SP-32] 已修正: createSubagentToolRegistry 现传真实
     * permissionMode, plan-mode agent 的 registry 保留 ExitPlanMode, 本层兜底过滤同源同参幂等,
     * S4-6 concerns 闭环.
     *
     * @param agentDefinition 子 Agent 定义 (tools/disallowedTools/source/permissionMode 来源)
     * @return 过滤 + 解析后的工具列表
     */
    List<Tool> resolveAgentTools(AgentDefinition agentDefinition) {
        boolean isBuiltIn = "built-in".equals(agentDefinition.source());
        boolean isAsync = agentDefinition.background().orElse(false);
        PermissionMode permissionMode = resolvePermissionMode(agentDefinition);
        // CC agentToolUtils.ts:140-147: filteredAvailableTools = filterToolsForAgent({tools, isBuiltIn, isAsync, permissionMode})
        //   + :150-160 disallowedTools 精确剔除 (filterToolsForAgent 第 5 参已含).
        List<Tool> filtered = AgentToolUtils.filterToolsForAgent(
            subagentToolRegistry.all(), isBuiltIn, isAsync, permissionMode,
            agentDefinition.disallowedTools());

        // CC :162-173 hasWildcard → return allowedAvailableTools (全量放行)
        // [S4-1 C-9] wildcard 仅 undefined / ['*'] (agentToolUtils.ts:163-165):
        //   Optional.empty() (= CC undefined) → wildcard; 显式空列表 (= CC []) → 非 wildcard,
        //   走 by-name 循环返回空工具集. 旧实现把空列表也当 wildcard (undefined 与空列表
        //   双语义混淆) 已删 — 对齐 CC :162-173 两分支显式处理.
        boolean hasWildcard = agentDefinition.tools().isEmpty() || agentDefinition.usesAllTools();
        List<String> toolNames = agentDefinition.tools().orElse(List.of());
        if (hasWildcard) {
            return filtered;
        }
        // CC :175-216: by-name 解析 (availableToolMap 查表 + valid/invalid 拆分)
        List<Tool> resolved = new ArrayList<>();
        for (String name : toolNames) {
            filtered.stream().filter(t -> name.equals(t.name())).findFirst().ifPresent(resolved::add);
        }
        return resolved;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 8 helper: buildAgentSystemPrompt
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建 agent system prompt · 对齐 CC getAgentSystemPrompt (runAgent.ts:508-518)
     *
     * <p>CC 的 getAgentSystemPrompt() 会注入 toolUseContext、model、dirs、tools 等信息。
     */
    private String buildAgentSystemPrompt(boolean isForkPath, AgentDefinition agentDefinition,
                                          List<Tool> resolvedTools, String effectiveModel,
                                          List<String> additionalWorkingDirectories, String userContext) {
        // [R2-ENVINFO] 模型经 getSystemPrompt(modelId, dirs) 逐调用显式传参 · 对齐 CC enhanceSystemPromptWithEnvDetails
        //   入参 resolvedAgentModel（runAgent.ts:340）。CC 无进程级/线程级静态模型槽——旧实现
        //   SubagentEnvInfo.setDefaultModelId(ThreadLocal) 静态槽已删，effectiveModel 直接作为显式参数
        //   穿过 getSystemPrompt(modelId, dirs) → BuiltInAgents.buildSystemPrompt → computeEnvInfo(modelId, dirs)：
        //   built-in 子代理 env 块恒含 modelDescription 行（marketing 名 → "named {m}. The exact model ID
        //   is {id}."，否则 "You are powered by the model {id}."）；null/blank → 抑制模型描述行
        //   （对齐 CC undercover 分支 prompts.ts:621-623，不编造模型名）。
        // [R32-04] additionalWorkingDirectories 经本方法显式下传（CC runAgent.ts:504-518 同链路），
        //   env 块渲染 Additional working directories 行（prompts.ts:631-633）。
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] buildAgentSystemPrompt 模型+附加目录显式传参: agentType={} model={} additionalWorkingDirectories={}",
                agentDefinition.agentType(), effectiveModel, additionalWorkingDirectories);
        }
        String agentPrompt = agentDefinition.getSystemPrompt(effectiveModel, additionalWorkingDirectories);
        if (agentPrompt == null || agentPrompt.isBlank()) {
            agentPrompt = fallbackSystemPrompt != null ? fallbackSystemPrompt : "";
        }

        // 增强：注入工具列表信息（对齐 CC getAgentSystemPrompt 增强逻辑）
        StringBuilder enhanced = new StringBuilder(agentPrompt);

        // [IMP-M-P2-2] 生成期 agent-memory 注入（OPD-M-38）· 对齐 CC loadAgentsDir.ts:481-488/726-732
        //   getSystemPrompt = () => { if (isAutoMemoryEnabled() && memory) return systemPrompt + '\n\n' + loadAgentMemoryPrompt(...) }
        //   Java 端 CustomAgentDefinition.getSystemPrompt 是静态内容（无 CC 闭包），故注入点在
        //   消费端 buildAgentSystemPrompt；[OPD-CM5-F-25] isAutoMemoryEnabled 门控在此调用方
        //   （对齐 CC 调用方门控，loadAgentMemoryPrompt 已去内部门控）。
        String memoryPrompt = agentMemoryPrompt(agentDefinition);
        if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
            enhanced.append("\n\n").append(memoryPrompt);
        }

        // [IMP-F2-3] 子代理记忆遥测 · 对齐 CC AgentTool.tsx:522-531（非 fork else 分支）：
        //   if (selectedAgent.memory) logEvent('tengu_agent_memory_loaded', { scope, source: 'subagent' })
        //   门控 = memory 字段存在（shouldEmitAgentMemoryLoaded：CC 不 gate isAutoMemoryEnabled，
        //   事件语义="agent 定义带记忆"，与 memoryPrompt 是否实际注入无关——CC 门控 selectedAgent.memory
        //   truthy，AgentTool.tsx:523）；fork path 不发射（CC :482-513 fork 分支无本事件，含 fork 但
        //   父 prompt 缺失回退 buildAgentSystemPrompt 的场景）。
        //   发射 = 真实 Telemetry 通道 emitAgentMemoryLoaded（recordEvent + logOTelEvent），
        //   替代 AnalyticsTracker stub 计数（DC-V5-09 删除）；属性 = scope + source（CC 恒不发射
        //   agent_type：条件 "external" === 'ant' 恒 false，AgentTool.tsx:525-527）。
        if (shouldEmitAgentMemoryLoaded(isForkPath, agentDefinition)) {
            emitAgentMemoryLoaded(agentDefinition);
        }

        // 注入可用工具摘要
        if (!resolvedTools.isEmpty()) {
            enhanced.append("\n\n## Available Tools\n");
            for (Tool t : resolvedTools) {
                enhanced.append("- **").append(t.name()).append("**: ").append(t.description()).append("\n");
            }
        }

        // 注入 user context (CLAUDE.md 等)
        if (userContext != null && !userContext.isBlank()) {
            enhanced.append("\n\n## User Context\n").append(userContext);
        }

        return enhanced.toString();
    }

    /**
     * [IMP-M-P2-2] agent-memory prompt（生成期注入 · OPD-M-38）· 对齐 CC
     * {@code loadAgentsDir.ts:481-488/726-732} 的 getSystemPrompt 闭包语义。
     *
     * <p>[OPD-CM5-F-25] isAutoMemoryEnabled 门控在<b>本调用方</b>（CC {@code if (isAutoMemoryEnabled()
     * && memory)}）：auto-memory 禁用或 agentDefinition.memory() 无值（user/project/local 三 scope
     * 之一）时返回 null（不追加）。agentMemoryDirectory 未注入 → null（不破坏既有调用路径）。
     *
     * @param agentDefinition 待生成 system prompt 的 agent 定义
     * @return memory prompt 文本或 null
     */
    private String agentMemoryPrompt(AgentDefinition agentDefinition) {
        if (agentMemoryDirectory == null) {
            return null;
        }
        // [OPD-CM5-F-25] 门控移调用方：CC loadAgentsDir.ts:481-488/726-732
        //   if (isAutoMemoryEnabled() && memory) —— 禁用时跳过注入（loadAgentMemoryPrompt 已去内部门控）
        if (!com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            return null;
        }
        java.util.Optional<String> memory = agentDefinition.memory();
        if (memory.isEmpty() || memory.get().isBlank()) {
            return null;
        }
        com.nexusai.application.agent.agent.AgentMemoryDirectory.AgentMemoryScope scope =
            com.nexusai.application.agent.agent.AgentMemoryDirectory.fromName(memory.get());
        if (scope == null) {
            return null;
        }
        String prompt = agentMemoryDirectory.loadAgentMemoryPrompt(agentDefinition.agentType(), scope);
        if (prompt == null || prompt.isEmpty()) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] buildAgentSystemPrompt 生成期注入 agent-memory prompt: agentType={} scope={}",
                agentDefinition.agentType(), scope);
        }
        return prompt;
    }

    /**
     * [IMP-CM-18] 子代理记忆遥测门控 · 对齐 CC AgentTool.tsx:524-530：
     *   {@code if (selectedAgent.memory) logEvent('tengu_agent_memory_loaded', { scope, source: 'subagent' })}。
     *
     * <p>仅非 fork 子代理路径发射（CC 事件位于 AgentTool 的 else 非 fork 分支，
     *   AgentTool.tsx:482-513 fork 分支无本事件）；scope 判定 = agentDefinition.memory()
     *   有非空值（CC {@code selectedAgent.memory} truthy，'user'/'project'/'local' 三 scope 之一，
     *   loadAgentsDir.ts:92）。仅判定 memory 字段存在——CC 不门控 isAutoMemoryEnabled
     *   （事件语义 = "agent 定义带记忆" 而非 "记忆内容实际注入"）。
     *
     * @param isForkPath       fork 路径标志（CC AgentTool.tsx:482 isForkPath）
     * @param agentDefinition  子代理定义（null → false）
     * @return true 表示应发射 tengu_agent_memory_loaded（source='subagent'）
     */
    static boolean shouldEmitAgentMemoryLoaded(boolean isForkPath, AgentDefinition agentDefinition) {
        if (isForkPath) {
            return false;
        }
        if (agentDefinition == null) {
            return false;
        }
        java.util.Optional<String> memory = agentDefinition.memory();
        return memory.isPresent() && !memory.get().isBlank();
    }

    /**
     * [IMP-CM-18] 子代理记忆遥测发射 · 对齐 CC AgentTool.tsx:524-530
     *   {@code logEvent('tengu_agent_memory_loaded', { scope: selectedAgent.memory, source: 'subagent' })}。
     *
     * <p>双发射 recordEvent + logOTelEvent（IMP-CM-17 遥测通道）；telemetry=null → 静默跳过
     *   （测试/未接线零行为变化，同 MemoryPromptBuilder.emitMemdirLoaded :630-637 惯例）。
     *   CC original {@code agent_type} 属性恒不发射：其条件 {@code "external" === 'ant'}
     *   （AgentTool.tsx:525-527）恒 false → 运行时事件属性仅 {@code scope} + {@code source}。
     *
     * @param agentDefinition 子代理定义（memory 无值时 no-op）
     */
    void emitAgentMemoryLoaded(AgentDefinition agentDefinition) {
        if (agentDefinition == null) {
            return;
        }
        java.util.Optional<String> memory = agentDefinition.memory();
        if (memory.isEmpty() || memory.get().isBlank()) {
            return;
        }
        Telemetry t = this.telemetry;
        if (t == null) {
            return;
        }
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        // CC original: scope = selectedAgent.memory (AgentTool.tsx:527) · 'user'/'project'/'local'
        attrs.put("scope", memory.get());
        // CC original: source = 'subagent' (AgentTool.tsx:529)
        attrs.put("source", "subagent");
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] 子代理记忆遥测: tengu_agent_memory_loaded scope={} source=subagent agentType={}",
                memory.get(), agentDefinition.agentType());
        }
        t.recordEvent("tengu_agent_memory_loaded", attrs);
        t.logOTelEvent("tengu_agent_memory_loaded", attrs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 10 helper: executeSubagentStartHooks
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 收集单条 additionalContext · 对齐 CC hooks.ts:2783-2789
     * ({@code if (result.additionalContext) yield { additionalContexts: [result.additionalContext] }}).
     *
     * <p>WHY static seam (Pattern #14 RED-GREEN 双证): executeSubagentStartHooks 是 private, 单测
     * 无法起 execute() 全流程; 抽成 package-private static 让测试验证 additionalContext 提取语义
     * (直接 push 原文, 非 "[Hook: ...]" 包装 — 旧代码 stopReason 误用 Pattern #9 已删). Java
     * GenericHook.HookResult.additionalContext 是单值 String (H3 已对齐 CC utils/hooks.ts:347),
     * 故单个 HookResult 至多贡献 1 条 context; 多 hook 聚合缺口见 concerns S4-2b (executeEvent
     * 聚合为单 HookResult). 入参取 String 而非 HookResult: 构造 HookResult 需 package-private
     * 构造器 (hook 包), 抽 String 让测试零依赖.
     *
     * @param additionalContext hook 聚合结果的 additionalContext (可为 null)
     * @return additionalContext 列表 (无 → 空列表, 不包装 "[Hook: ...]")
     */
    static List<String> collectAdditionalContext(String additionalContext) {
        List<String> contexts = new ArrayList<>();
        if (additionalContext != null && !additionalContext.isBlank()) {
            contexts.add(additionalContext);
        }
        return contexts;
    }

    /**
     * [IMP-HOOKS-S8 H8 CCJ-HOOKS-T8-02] Step 13 seam · 门控 + 注册 agent frontmatter hooks.
     *
     * <p>对齐 CC runAgent.ts:564-575:
     * <pre>
     *   const hooksAllowedForThisAgent =
     *     !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(agentDefinition.source)
     *   if (agentDefinition.hooks && hooksAllowedForThisAgent) registerFrontmatterHooks(..., true)
     * </pre>
     * policy (strictPluginOnlyCustomization) 锁 hooks 面且 agent source 非 admin-trusted
     * (userSettings/projectSettings/flagSettings) → 拒绝注册 (debug 日志跳过, 权限面不绕过);
     * built-in/plugin/policySettings 来源 (ADMIN_TRUSTED_SOURCES) 始终放行。与
     * SkillToolImpl:1158-1162 skill 侧门控 (CC processSlashCommand.tsx:874-875) 同一语义。
     *
     * <p>WHY static seam (Pattern #14, collectAdditionalContext 先例): Step 13 位于 9 参
     * executeStreaming 22 步流程内部, 单测无法起全流程; 抽 package-private static 让测试
     * 直接验证「policy 锁 hooks 面 → userSettings agent 0 注册 / plugin agent 注册」门控语义。
     *
     * @param hookRegistry                session hook 注册目标 (null → 0)
     * @param agentId                     agent UUID 字符串 (session hook key, CC addSessionHook sessionId=agentId)
     * @param agentType                   agent 类型名 (日志)
     * @param rawHooks                    agent frontmatter hooks raw Map (CC agentDefinition.hooks)
     * @param source                      agent 来源 (CC agentDefinition.source: built-in/userSettings/
     *                                    projectSettings/policySettings/flagSettings/plugin)
     * @param pluginOnlySettingsSupplier  strictPluginOnlyCustomization 策略设置 (缺省 Map::of = 不锁)
     * @return 实际注册的 hook 命令数 (门控拒绝 → 0)
     */
    static int registerAgentFrontmatterHooks(
            HookRegistry hookRegistry,
            String agentId,
            String agentType,
            Optional<Map<String, Object>> rawHooks,
            String source,
            Supplier<Map<String, Object>> pluginOnlySettingsSupplier) {
        if (hookRegistry == null || rawHooks == null || rawHooks.isEmpty() || rawHooks.get().isEmpty()) {
            return 0;
        }
        boolean hooksAllowedForThisAgent =
                !PluginOnlyPolicy.isRestrictedToPluginOnly(PluginOnlyPolicy.SURFACE_HOOKS,
                        pluginOnlySettingsSupplier)
                || PluginOnlyPolicy.isSourceAdminTrusted(source);
        if (!hooksAllowedForThisAgent) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] Step 13: 权限门控跳过 agent frontmatter hooks 注册 "
                                + "(source={}, strictPluginOnlyCustomization 锁 hooks 面) "
                                + "(CC runAgent.ts:564-566)",
                        source);
            }
            return 0;
        }
        Map<HookEventType, List<HookMatcher>> hooks = FrontmatterHooks.fromMap(rawHooks.get());
        int registered = FrontmatterHooks.register(hookRegistry, agentId, agentType, hooks);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] Step 13: 已注册 {} 个 frontmatter hooks agent={} source={}",
                    registered, agentId, source);
        }
        return registered;
    }

    /**
     * 执行 SubagentStart hooks 并收集 additionalContexts
     *
     * <p>对齐 CC runAgent.ts:531-555: 遍历注册的 SubagentStart hooks，
     * 收集 hook 返回的 additionalContext 文本。
     */
    private List<String> executeSubagentStartHooks(String agentId, String sessionId, String agentType) {
        List<String> contexts = new ArrayList<>();
        if (hookRegistry == null) return contexts;

        try {
            HookEvent startEvent = HookEvent.subagentStart(agentId, agentType, sessionId);
            // [H-WF4-03 · 5-W4-6] SubagentStart additionalContexts 逐结果收集 · CC runAgent.ts:531-543
            //   for-await 逐 hookResult 取 additionalContexts 数组（hooks.ts:2783-2789 每条 yield
            //   {additionalContexts:[result.additionalContext]}）。executeEventAll 返回全部非 null 结果
            //   （多 hook 各贡献 1 条全收；旧 executeEvent 折叠单结果丢 2+ 条）。
            List<GenericHook.HookResult> results = hookRegistry.executeEventAll(startEvent);
            for (GenericHook.HookResult result : results) {
                if (result == null) {
                    continue;
                }
                // 从 hook 结果中提取 additionalContexts
                // CC runAgent.ts:531-543: for await (hookResult of executeSubagentStartHooks) 取
                //   hookResult.additionalContexts 数组 (hooks.ts:2783-2789 yield {additionalContexts:[result.additionalContext]}).
                // Pattern #9 修正: 旧代码 :1103-1104 误读 stopReason 单字段当 additionalContext 并包装
                //   "[Hook: ...]" — CC 读的是 raw additionalContext (hooks.ts:347) 且直接 push 原文. 语义双重错位.
                if (result.preventContinuation()) {
                    log.warn("[SubagentExecutor] SubagentStart hook 阻断 continuation agent={}({})",
                            agentId, agentType);
                }
                // [H-WF5a-02 折叠链项2] HookResult.additionalContexts 已是 List<String> →
                //   直接遍历全保留 (CC 逐结果 yield additionalContexts 数组). collectAdditionalContext
                //   String seam 保留供测试, 生产改走 List 通道.
                if (result.additionalContexts() != null) {
                    for (String ac : result.additionalContexts()) {
                        if (ac != null && !ac.isBlank()) {
                            contexts.add(ac);
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] SubagentStart hooks 已执行 agent={} ({}): 收集 {} 个 context",
                        agentId, agentType, contexts.size());
            }
        } catch (Exception e) {
            log.warn("[SubagentExecutor] SubagentStart hook 失败: {}", e.getMessage());
        }
        return contexts;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 11 helper: createHookContextMessage
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Object> createHookContextMessage(List<String> additionalContexts) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("isMeta", true);
        List<Map<String, Object>> content = new ArrayList<>();
        // [S4-1 C-8] 对齐 CC messages.ts:4117-4128 hook_additional_context 序列化:
        //   createUserMessage({ content: wrapInSystemReminder(`${attachment.hookName} hook
        //   additional context: ${attachment.content.join('\n')}`), isMeta: true })
        //   wrapInSystemReminder (messages.ts:3097-3099) = `<system-reminder>\n${content}\n</system-reminder>`;
        //   hookName 常量 'SubagentStart' (createAttachmentMessage, runAgent.ts:546-555).
        //   Pattern #9 修正: 旧头部 "[SubagentStart hook context]" 是自创格式非 CC 字节, 已整行替换.
        String text = "<system-reminder>\nSubagentStart hook additional context: "
                + String.join("\n", additionalContexts)
                + "\n</system-reminder>";
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);

        message.put("content", content);
        return message;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 14 helper: initializeAgentMcp
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 Agent MCP servers · 对齐 CC runAgent.ts:92-218 initializeAgentMcpServers.
     *
     * <p>[MCP-I-9 Q-30] 新增 {@code parentClients} 参数：子代理继承父 MCP 连接 ·
     * CC runAgent.ts:653-656 {@code initializeAgentMcpServers(agentDefinition,
     * toolUseContext.options.mcpClients)}。无 frontmatter MCP → 直接返 parentClients
     * （CC :104-110）；有 → merged = 父 + agent（CC :213-217）。父连接来源：
     * 父 executor Step 15 写入子 base TUC 的 mcpServerConnections（嵌套第 2 层
     * 收到第 1 层 mergedClients，连接对象传递）。
     *
     * @param agentDefinition Agent 定义
     * @param parentClients   父 Agent 继承的 MCP 连接（CC original: toolUseContext.options.mcpClients）
     * @return 初始化结果（含真实 tools[]，非 stub）
     */
    AgentMcpServers.InitResult initializeAgentMcp(
            AgentDefinition agentDefinition,
            List<AgentMcpServers.McpServerConnection> parentClients) {
        List<AgentMcpServers.McpServerConnection> effectiveParentClients =
            parentClients != null ? parentClients : List.of();
        if (agentDefinition.mcpServers().isEmpty() || agentDefinition.mcpServers().get().isEmpty()) {
            // CC runAgent.ts:104-110 无 frontmatter MCP → 直接返 parentClients（共享继承）
            return new AgentMcpServers.InitResult(effectiveParentClients, List.of(), () -> {});
        }

        List<AgentMcpServers.McpServerSpec> specs = new ArrayList<>();
        for (Map<String, Object> mcpMap : agentDefinition.mcpServers().get()) {
            // ── [MCP-I-9 返工 R3] CC 判别 (runAgent.ts:140-170 / loadAgentsDir.ts:66) ──
            // CC mcpServers item = union(string, z.record([name]: config))：
            //   * keyed inline（CC 真源 schema） = {[serverName]: config} —— 单 key 且 value 为 config Map
            //   * string-ref = {"name": ref}（loadAgentsDir 把 string 项包成 {"name": ref}）
            //   * flat inline（Java 既有测试形态）= {name, command, type, args, env} 顶层配置
            // 首轮判别器 command.isBlank() && !has type && !has command 对 keyed 形式顶层
            // 无 command/type → 误判 string-ref（name=「unnamed」查 DB miss → 静默丢弃），
            // 违反 CC runAgent.ts:152-170（inline 取 entries[0] 键为 name）。
            if (mcpMap.size() == 1 && mcpMap.values().iterator().next() instanceof Map) {
                Map.Entry<String, Object> entry = mcpMap.entrySet().iterator().next();
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) entry.getValue();
                specs.add(AgentMcpServers.fromConfig(entry.getKey(), cfg));
                log.info("[SubagentExecutor] keyed inline MCP server '{}' (type={}, 对齐 CC runAgent.ts:163-169)",
                        entry.getKey(), cfg.getOrDefault("type", "stdio"));
                continue;
            }
            // keyed inline 多 key → CC runAgent.ts:155-162 entries.length !== 1 → warn + continue（跳过）
            if (!mcpMap.isEmpty() && mcpMap.values().stream().allMatch(v -> v instanceof Map)) {
                log.warn("[SubagentExecutor] 无效 keyed MCP spec（应恰含一个 serverName→config 键，"
                        + "对齐 CC runAgent.ts:155-162 entries.length!==1）: {} → 跳过", mcpMap.keySet());
                continue;
            }
            // string-ref：仅 {"name": ref}（value 为 String）· [MCP-I-9 Q-32] 对齐 CC runAgent.ts:140-151
            if (mcpMap.size() == 1 && mcpMap.containsKey("name")
                    && mcpMap.get("name") instanceof String) {
                String refName = (String) mcpMap.get("name");
                if (mcpServerNameResolver != null) {
                    java.util.Optional<AgentMcpServers.McpServerSpec> resolved =
                        mcpServerNameResolver.apply(refName);
                    if (resolved.isPresent()) {
                        specs.add(resolved.get());
                        log.info("[SubagentExecutor] Q-32 按名解析 MCP server '{}' 命中 DB（string-ref）",
                                refName);
                    } else {
                        // CC runAgent.ts:145-151 找不到 → warn + continue（跳过，不抛）
                        log.warn("[SubagentExecutor] Q-32 MCP server 未找到: '{}'（string-ref，跳过；"
                                + "对齐 CC runAgent.ts:145-151）", refName);
                    }
                } else {
                    // 双轨缺口兜底：resolver 未注入 → warn + 跳过（不再 fall-through 空 command）
                    log.warn("[SubagentExecutor] string-ref '{}' 但按名解析器未注入 → 跳过（需接线 "
                            + "setMcpServerNameResolver，对齐 CC runAgent.ts:140-151）", refName);
                }
                continue;
            }
            // flat inline（Java 既有测试形态）：顶层 name/command/type/args/env
            String name = Objects.toString(mcpMap.get("name"), "unnamed");
            String command = Objects.toString(mcpMap.get("command"), "");
            @SuppressWarnings("unchecked")
            List<String> args = mcpMap.get("args") instanceof List
                    ? (List<String>) mcpMap.get("args") : List.of();
            @SuppressWarnings("unchecked")
            Map<String, String> env = mcpMap.get("env") instanceof Map
                    ? (Map<String, String>) mcpMap.get("env") : Map.of();
            // [MCP-I-9 Q-32] inline spec 保持 stdio 缺省（McpServerSpec 4 参 ctor 兜底 type=stdio）
            specs.add(new AgentMcpServers.McpServerSpec(name, command, args, env));
        }

        if (mcpTransportFactory == null) {
            // [S5 P0 差异 2] 2 参 stub 分支已删 (AgentMcpServers 的 @Deprecated 2 参重载 +
            //   connectToServerStdio 已删除). 有 frontmatter MCP 必须注入 factory — 对齐 CC 总是
            //   真实 tools/list (CC runAgent.ts 无 'stub without transportFactory' 路径).
            throw new IllegalStateException(
                "initializeAgentMcp: McpTransportFactory required for frontmatter MCP servers "
                    + "(2 参 stub 已删, 强制注入 factory)");
        }
        try {
            // [S5 P0 差异 2] 传 agentDefinition.source (CC runAgent.ts:117 isSourceAdminTrusted)
            //   + pluginOnlySettingsSupplier (CC :118 isRestrictedToPluginOnly('mcp'))
            //   + mcpToolTimeoutMs (CC client.ts:224 getMcpToolTimeoutMs).
            log.info("[SubagentExecutor] initializeAgentMcp: 走 7 参重载（factory 已注入, {} specs, "
                    + "source={}, timeoutMs={}, parentClients={}, elicitationMachine={}）", specs.size(),
                    agentDefinition.source(), mcpToolTimeoutMs, effectiveParentClients.size(),
                    mcpElicitationMachine != null);
            return AgentMcpServers.initialize(
                    Optional.of(specs), effectiveParentClients, mcpTransportFactory,
                    agentDefinition.source(), pluginOnlySettingsSupplier, mcpToolTimeoutMs,
                    mcpElicitationMachine);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] AgentMcpServers.initialize 失败: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage());
            // [MCP-I-9 Q-30] 失败也保留 parentClients（父连接共享不受子 agent MCP 失败影响）
            return new AgentMcpServers.InitResult(effectiveParentClients, List.of(), () -> {});
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 15 helper: maybeStartSummary (S5 P1 差异 3 · CC agentToolUtils.ts:543-553)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 摘要门控 spawn 路径枚举 · 对齐 CC 四个 summary 生产点三套门语义（规则三禁止统一为单一三 flag 或）。
     *
     * <p>CC 真源（Read 实证）:
     * <ul>
     *   <li>{@code ASYNC} — async-from-start: {@code isCoordinator || isForkSubagentEnabled() ||
     *       getSdkAgentProgressSummariesEnabled()}（AgentTool.tsx:750，三 flag 或）</li>
     *   <li>{@code SYNC} — sync: {@code summaryTaskId && getSdkAgentProgressSummariesEnabled()}
     *       （AgentTool.tsx:852，SDK 门 + 前台任务登记守卫）</li>
     *   <li>{@code BACKGROUNDED} — backgrounded: {@code getSdkAgentProgressSummariesEnabled()}
     *       （AgentTool.tsx:934，仅 SDK 门）</li>
     *   <li>{@code RESUME} — resume: {@code isCoordinatorMode() || isForkSubagentEnabled() ||
     *       getSdkAgentProgressSummariesEnabled()}（resumeAgent.ts:250-253，三 flag 或）</li>
     * </ul>
     */
    public enum SummarySpawnPath {
        /** CC AgentTool.tsx:750 async-from-start · 三 flag 或. */
        ASYNC,
        /** CC AgentTool.tsx:852 sync · summaryTaskId && sdk（前台任务登记守卫）. */
        SYNC,
        /** CC AgentTool.tsx:934 backgrounded · 仅 sdk 门. */
        BACKGROUNDED,
        /** CC resumeAgent.ts:250-253 resume · 三 flag 或. */
        RESUME
    }

    /**
     * [RF-2 返工] sync 路径前台任务登记 seam（Step 19.7 提取，供生产链路级测试直达）。
     *
     * <p>对齐 CC AgentTool.tsx:818-833 + :843：仅 sync 路径（{@code !isBackgroundTasksDisabled} 时）
     * {@code registerAgentForeground} 成功才得 {@code summaryTaskId = foregroundTaskId}（taskId===agentId
     * 合一，LocalAgentTask.tsx:132/406）。返回的 {@code BackgroundTask} 由 Step 21 finally 注销
     * （CC AgentTool.tsx:1162-1184 unregisterAgentForeground 等价）。
     *
     * <p>此前该逻辑内联于 executeStreaming，测试只能静态断言 {@link #maybeStartSummary} 门，无法覆盖
     * 「SubagentTool 注入 backgroundTaskRunner → 此处登记 → summaryTaskId 写回」生产接线（RF-2 反思
     * P0-② 假接线：setBackgroundTaskRunner 全仓 0 调用 → 守卫恒 false）。提取后生产链路测试可直达本
     * seam，锁定全链而非仅静态门。
     *
     * @param agentId          子代理 UUID（CC 合一 taskId）
     * @param description      任务描述（CC registerAgentForeground description，AgentTool.tsx:821）
     * @param prompt           agent prompt（CC LocalAgentTask.tsx:119）
     * @param agentType        Agent 类型名（general-purpose 等）
     * @param createSessionId  创建会话 sessionId（Phase 4 cron-notify：由 executeStreaming 的
     *                         {@code agentTuc.sessionId()}（父继承会话）透传；tool-exec 池线程无 MDC）
     * @return 已登记的前台 BackgroundTask；spawnPath 非 SYNC 或 runner 未注入 → null
     */
    BackgroundTask registerSyncForeground(UUID agentId, String description, String prompt, String agentType,
                                          String createSessionId) {
        if (summarySpawnPath != SummarySpawnPath.SYNC || backgroundTaskRunner == null) {
            return null;
        }
        BackgroundTask registered = backgroundTaskRunner.registerAgentForeground(
            agentId, description, prompt, agentType, createSessionId);
        this.summaryTaskId = registered.id();
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] RF-2: sync 路径前台任务已登记 summaryTaskId={} agent={}",
                this.summaryTaskId, agentId);
        }
        return registered;
    }

    /**
     * 子 agent loop 启动前接通周期摘要 · 对齐 CC agentToolUtils.ts:543-553
     * ({@code onCacheSafeParams → startAgentSummarization(taskId, asAgentId(taskId), params, rootSetAppState)}).
     *
     * <p><b>[R31-03 返工] CC 触发门按 spawn 路径拆分（三套门，规则三禁止统一为单一三 flag 或）</b>:
     * <ul>
     *   <li>{@link SummarySpawnPath#ASYNC} — {@code isCoordinator || isForkSubagentEnabled() ||
     *       getSdkAgentProgressSummariesEnabled()}（三 flag 或 · AgentTool.tsx:750）</li>
     *   <li>{@link SummarySpawnPath#RESUME} — 同三 flag 或（resumeAgent.ts:250-253）</li>
     *   <li>{@link SummarySpawnPath#SYNC} — {@code summaryTaskId != null && sdk}（SDK 门 + 前台
     *       任务登记守卫 · AgentTool.tsx:852/:818-833/:843）</li>
     *   <li>{@link SummarySpawnPath#BACKGROUNDED} — {@code sdk}（仅 SDK 门 · AgentTool.tsx:934）</li>
     * </ul>
     *
     * <p>修复前（S5-9 TODO → R31-03 初返工）：统一三 flag 合取，缺 sync {@code summaryTaskId &&}
     * 守卫（reverify-t3r1 §8 #5 判 △）。本次按 CC 四生产点三套门逐路径对齐。
     *
     * <p>static seam (Pattern #14): executeStreaming Step 19/20 之间调用本方法; 单测直接验证
     * 各 spawn 路径门 → start 被调 (activeAgents 含 agentId), 门关 → null.
     *
     * @param spawnPath                    spawn 路径类型（ASYNC/SYNC/BACKGROUNDED/RESUME），决定门语义
     * @param summaryTaskId                sync 路径前台任务登记 ID（CC AgentTool.tsx:843
     *                                     foregroundTaskId）；Java 无前台登记恒 null → sync 门恒 false
     * @return AgentSummaryHandle; summaryService/coordinatorMode 未注入或对应路径门关 → null
     */
    public static AgentSummaryHandle maybeStartSummary(
            SummarySpawnPath spawnPath,
            String summaryTaskId,
            AgentSummaryService summaryService,
            CoordinatorMode coordinatorMode,
            boolean sdkAgentProgressSummariesEnabled,
            String agentId,
            Path sessionDir,
            String sessionId,
            LlmProviderFactory llmProviderFactory,
            ProviderConfig providerConfig,
            String modelName,
            AgentProgressTracker progressTracker,
            SdkEventQueue sdkEventQueue) {
        if (summaryService == null || coordinatorMode == null) {
            return null;
        }
        boolean coordinator = coordinatorMode.isCoordinatorMode();
        boolean fork = ForkSubagent.isForkSubagentEnabled();
        boolean sdk = sdkAgentProgressSummariesEnabled;
        // CC 三套分路径门 (AgentTool.tsx:750/:852/:934 + resumeAgent.ts:250-253):
        //   ASYNC/RESUME → coordinator || fork || sdk
        //   SYNC         → summaryTaskId != null && sdk
        //   BACKGROUNDED → sdk
        boolean enableSummarization = switch (spawnPath) {
            case ASYNC, RESUME -> coordinator || fork || sdk;
            case SYNC -> summaryTaskId != null && sdk;
            case BACKGROUNDED -> sdk;
        };
        if (!enableSummarization) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] R31-03: 摘要门未命中, 跳过 summary 启动 spawnPath={} agent={} "
                    + "(coordinator={} fork={} sdk={} summaryTaskId={})",
                    spawnPath, agentId, coordinator, fork, sdk, summaryTaskId);
            }
            return null;
        }
        // CC agentSummary.ts:46 startAgentSummarization + :68 readTranscript 用同一 sessionDir/sessionId
        SummarySummarizer summarizer = new SummarySummarizerImpl(
            sessionDir, sessionId, llmProviderFactory, providerConfig, modelName);
        // [RF-2 ①] updateCallback 由 no-op → AgentProgress 通道 · 对齐 CC updateAgentSummary
        //   （LocalAgentTask.tsx:359-407）：写入 progress.summary + 按 sdk 门发射 task_progress。
        AgentSummaryHandle handle = summaryService.start(
            agentId, agentId, summarizer, summary -> {
                if (progressTracker != null) {
                    progressTracker.applySummary(summary, sdk, sdkEventQueue);
                }
            });
        log.info("[SubagentExecutor] [S5 P1] summary 已接通: spawnPath={} agent={} session={} "
            + "(coordinator={} fork={} sdk={} summaryTaskId={})",
            spawnPath, agentId, sessionId, coordinator, fork, sdk, summaryTaskId);
        return handle;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 15 helper: mergeToolsUniqByName
    // ════════════════════════════════════════════════════════════════════════

    List<Tool> mergeToolsUniqByName(List<Tool> resolved, List<Tool> mcpTools) {
        if (mcpTools.isEmpty()) return new ArrayList<>(resolved);
        Map<String, Tool> merged = new LinkedHashMap<>();
        // resolved tools 优先级更高（先放）
        for (Tool t : resolved) merged.put(t.name(), t);
        // MCP tools 补充（不覆盖）
        for (Tool t : mcpTools) merged.putIfAbsent(t.name(), t);
        return new ArrayList<>(merged.values());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 16 helper: buildAgentOptions
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Agent 运行选项 · 对齐 CC runAgent.ts:667-695 agentOptions 11 字段
     * （DEL-SP-10 已删 appendSystemPrompt 错标字段，见 buildAgentOptions Javadoc）
     *
     * <p>替代原有的 {@code Map<String, Object>}，提供类型安全的 agent 运行配置。
     */
    record AgentRunOptions(
            List<Tool> tools,
            boolean useExactTools,
            boolean isNonInteractiveSession,
            String mainLoopModel,
            Map<String, Object> thinkingConfig,
            // [IMP2-05 运行时接线] querySource 字段复活：runSubagentQueryLoop 构建 QueryParams 时
            //   经 .withQuerySourceValue(agentOptions.querySource()) 消费（:3979 接线），值源自
            //   resolveQuerySource(isForkPath, agentDefinition)（:1445 组合点，对齐 CC promptCategory.ts:16-28
            //   getQuerySourceForAgent → AgentTool.tsx:609 + runAgent.ts:694 fork 子继承父）：fork →
            //   'agent:builtin:fork'；非 fork 内置 → 'agent:builtin:<type>' / 'agent:default'；自定义/插件 →
            //   'agent:custom'。枚举 QuerySource（FORK/SUBAGENT）仍独立承担守卫类别（autocompact 递归守卫 /
            //   persist gate / 529 / main-thread 判定），本字段承担 agentType 级精确值。
            String querySource,
            // [MCP-I-9 Q-30] int mcpClientCount → List<McpServerConnection> mcpClients
            //   · 对齐 CC runAgent.ts:685 agentOptions.mcpClients = mergedMcpClients
            //   (连接对象传递，非 int 计数；0 读取点契约替换，符合「未上线可破约」).
            List<AgentMcpServers.McpServerConnection> mcpClients,
            Map<String, Object> mcpResources,
            Map<String, AgentDefinition> agentDefinitions,
            boolean verbose,
            // [OD-11 对齐 CC 无默认] null = 无限轮 (CC query.ts:190 不设默认值)
            Integer maxTurns) {
        AgentRunOptions {
            if (tools == null) tools = List.of();
            if (thinkingConfig == null) thinkingConfig = Map.of();
            if (mcpClients == null) mcpClients = List.of();
            if (mcpResources == null) mcpResources = Map.of();
            if (agentDefinitions == null) agentDefinitions = Map.of();
        }
    }

    /**
     * 派生子代理 querySource 组合值 · 对齐 CC promptCategory.ts:16-28 + runAgent.ts:694。
     *
     * <p><b>IMP-SUB-15 返工 R2（规则九）</b>：原 :1361-1363 三元内联于 execute() Step 10，
     * 无法被测试锁定——4 条 helper 测试打在 {@code AgentDefinition.querySourceForAgent()} 上，
     * 即使把非 fork 分支改回旧公式或删除整段组合仍全绿，对生产接线零保护。抽取为
     * package-private 静态方法后接线本体可回归（非 fork 值面 + fork 常量直接锁在此方法）。
     *
     * <p><b>死路径披露（返工 R1b）</b>：返回值非 fork 分支当前**无运行时消费点**——
     * {@code AgentRunOptions.querySource}（本文件 :3287）0 读取点；运行时 querySource 由
     * QueryParams（:3642）经 {@code isForkPath} 独立派生（FORK/SUBAGENT 枚举 →
     * {@code 'agent:builtin:fork'}/{@code 'agent:subagent'}）。非 fork 精确值域
     * （{@code agent:builtin:&lt;type&gt;}/{@code agent:custom}）复活归 IMP2-05。
     *
     * @param isForkPath      是否 fork 子 agent 路径（true → 恒 FORK_QUERY_SOURCE）
     * @param agentDefinition 目标 AgentDefinition（非 fork 路径用于派生 CC 值面）
     * @return fork → {@code ForkSubagent.FORK_QUERY_SOURCE}（{@code 'agent:builtin:fork'}）；
     *         非 fork → {@code agentDefinition.querySourceForAgent()}
     */
    static String resolveQuerySource(boolean isForkPath, AgentDefinition agentDefinition) {
        return isForkPath
            ? ForkSubagent.FORK_QUERY_SOURCE
            : agentDefinition.querySourceForAgent();
    }

    /**
     * 构建 agentOptions · 对齐 CC runAgent.ts:667-695
     *
     * <p>CC 12 字段：tools, useExactTools, isNonInteractiveSession, mainLoopModel,
     * thinkingConfig, querySource, mcpClients, mcpResources, agentDefinitions,
     * appendSystemPrompt, verbose, maxTurns。
     * Java 记录 11 字段：appendSystemPrompt 为错标死字段（DEL-SP-10）已删 —— 调用点 :995
     * 实传的是 agentSystemPrompt，而 CC runAgent.ts:673 真语义是继承父 appendSystemPrompt，
     * 字段名与承载值双错；无任何读取点（grep agentOptions.appendSystemPrompt() 全仓库 0 命中）。
     */
    private AgentRunOptions buildAgentOptions(
            List<Tool> allTools, String effectiveModel, boolean isAsync,
            boolean useExactTools, AgentDefinition agentDefinition,
            String querySource, List<AgentMcpServers.McpServerConnection> mcpClients,
            Map<String, Object> inheritedThinkingConfig) {
        // [OD-11 对齐 CC 无默认] null = 无限轮 (CC query.ts:190 不设默认值)
        Integer maxTurns = agentDefinition.maxTurns().orElse(null);
        // [ODF-C3] 非 fork 子 Agent 同样携带附加 agents map（SDK request.agents 对齐 print.ts:4381-4383）
        Map<String, AgentDefinition> agentDefs = additionalAgentDefinitions != null
            ? additionalAgentDefinitions : Map.of();
        // [MCP-I-9 Q-30] 直传 mergedClients（父+agent）· CC runAgent.ts:685
        //   agentOptions.mcpClients = mergedMcpClients（旧 int mcpClientCount > 0 ? : 0 已删）
        return new AgentRunOptions(
                allTools, useExactTools, isAsync, effectiveModel,
                inheritedThinkingConfig, querySource,
                mcpClients,
                Map.of(), agentDefs,
                false, maxTurns);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 5 helper: createSubagentToolUseContext → delegated to createSubagentContext.create()
    // 对齐 CC runAgent.ts:700-714 + forkedAgent.ts:345-462
    // 实际调用在 execute() Step 5，不再使用独立 helper 方法
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // Step 18 helper: resolveSessionDir + record transcripts
    // ════════════════════════════════════════════════════════════════════════

    // [R-A11] buildWorktreeSlug 已迁移至 ForkWorktreePaths.buildWorktreeSlug(String earlyAgentId)
    //   (CC AgentTool.tsx:591 对齐)。旧 UUID 实现基于 packed UUID 前 8 位随机 hex 首字符,
    //   与 CC earlyAgentId.slice(0,8) ('a' 前缀) 语义不同 (R3-WF-F concerns-4), 已删除。

    /**
     * 会话目录根 · [R1] 旧 {java.io.tmpdir}/nexusai-sessions 平铺根 → config-home 项目 slug 目录
     * （{@link SessionStorage#sessionProjectDir}，对齐 CC getProjectDir(getOriginalCwd())）。
     * 供 subagent sidechain transcript（AgentTranscript.getTranscriptPath/recordSidechainTranscript）
     * 使用 —— 与 SessionStorage.getAgentTranscriptPath 同根，双根分裂消除（AgentTranscript 双根统一）。
     *
     * @param sessionId 主会话 ID（null → 回落 user.dir 兜底层）
     */
    private Path resolveSessionDir(String sessionId) {
        return com.nexusai.application.agent.tool.SessionStorage.sessionProjectDir(sessionId);
    }

    private void recordSidechainTranscript(Path sessionDir, String sessionId,
                                           String agentId, List<Map<String, Object>> messages) {
        try {
            AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, agentId, messages);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] recordSidechainTranscript 失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 20: subagent query loop · [H7-arch Phase 2] 改调 queryLoop（单一循环源）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [RES-R6] resume 重建的 ContentReplacementState 注入 resumed 子 agent query loop session state。
     *
     * <p>对齐 CC resumeAgent.ts:194 {@code runAgentParams.contentReplacementState = resumedReplacementState}
     * → query.ts:372-389 {@code applyToolResultBudget(messages, toolUseContext.contentReplacementState, ...)}
     * 消费同一实例（per-message tool result budget 决策保真，prompt cache 前缀稳定）。
     *
     * <p>{@code state == null}（父 live state 不可得 / 非 resume）→ no-op，loop 保持默认 create
     * （CC toolResultStorage.ts:1006 {@code if (!parentState) return undefined} feature off 同语义）。
     *
     * @param deps  子 agent query loop deps（{@link SubagentLoopDeps}，持隔离 {@code AgentLoopContext}）
     * @param state resume 重建的 ContentReplacementState（可为 null）
     */
    static void injectContentReplacementState(
            com.nexusai.application.agent.loop.SubagentLoopDeps deps, ContentReplacementState state) {
        if (deps == null || state == null) {
            return;
        }
        deps.context().sessionState().setContentReplacementState(state);
        log.info("[RES-R6] resume 重建的 ContentReplacementState 已注入 resumed 子 agent query loop: "
                + "seenIds={} replacements={} (CC resumeAgent.ts:194 → query.ts:372-389)",
            state.seenIds().size(), state.replacements().size());
    }

    /**
     * 子 Agent 查询循环 · [H7-arch Phase 2] 改调 {@link LlmAgentLoop#queryLoop}（单一循环源）。
     *
     * <p>对齐 CC {@code runAgent.ts:748}：{@code for await (const message of query({...}))} -- subagent
     * 复用主 {@code query()}，LLM 循环全部委托。Java 等价：[H7-arch Phase 5-2 P3-③] 经
     * {@code contextFactory.shared()} 构造隔离 ctx + base TUC availableTools=effectiveTools
     * （工具隔离走 ToolUseContext，不再 fresh carrier），自动获得主循环已对齐的 5 步压缩管线 +
     * budgetTracker + stopHook + taskBudget 能力。
     *
     * <p>三障碍解决：
     * <ul>
     *   <li>abort：{@code abortControllerRef.onCancel(state::cancel)} -> loop {@code state.cancelled()}</li>
     *   <li>工具隔离：base TUC {@code withAvailableTools(effectiveTools)}（对齐 CC runAgent 工具隔离）</li>
     *   <li>state 构造：{@code new AgentState(systemPrompt, sessionId, agentId)} + appendMessage</li>
     * </ul>
     */
    private SubagentResult runSubagentQueryLoop(
            ToolUseContext subagentCtx,
            AgentDefinition agentDefinition,
            List<Map<String, Object>> initialMessages,
            String agentSystemPrompt,
            AgentRunOptions agentOptions,
            List<Tool> allTools,
            String effectiveModel,
            String effectiveType,
            boolean isForkPath,
            boolean isAsync,
            PermissionMode permissionMode,
            boolean shouldAvoidPermissionPrompts,
            Path sessionDir,
            String lastRecordedUuid,
            Consumer<SubagentMessage> messageSink,
            ContentReplacementState contentReplacementState,
            AgentProgressTracker progressTracker) {

        UUID agentId = subagentCtx.agentId();
        // [R3-WF-F IMP-SUB-12 返工] transcript/SubagentResult 键还原 a+16hex（S-12 桥），
        //   对齐 CC AgentTool.tsx:580 earlyAgentId = createAgentId() 格式。
        String agentIdHex = agentId != null ? AgentContext.unpackAgentId(agentId) : null;
        // [session-id-short] subagentCtx.sessionId() 已 String（short）
        String sessionId = subagentCtx.sessionId();

        // [OD-11 对齐 CC 无默认] null = 无限轮
        Integer maxTurns = agentOptions.maxTurns();

        // [ODF-C3 返工#3] 子 loop 消费 AgentOptions.agentDefinitions（SDK request.agents 语义）
        // 对齐 CC runAgent.ts:700-714 context.options.agentDefinitions + print.ts:4381-4383。
        // [IMP-SUB-19 #23] mergeOptionsAgentDefinitions 已前移至 Step 5（fork 路径构建
        // forkAgentOptions 后即时并入 additionalAgentDefinitions），此处不再经 subagentCtx 二次 merge。

        // [H7-arch Phase 5-2 P3-③] 工具隔离改 base TUC availableTools=effectiveTools
        //（对齐 CC runAgent toolUseContext.options.tools），不再换 carrier toolRegistry。
        if (contextFactory == null) {
            log.error("[SubagentExecutor] contextFactory 未注入, 无法走 queryLoop (P3-③ 要求 SubagentTool 注入 factory)");
            throw new IllegalStateException("contextFactory not injected - SubagentTool must setContextFactory(...)");
        }

        // 构造 subagent AgentState（隔离 messages/turnCount/maxTurns/systemPrompt）
        AgentState state = new AgentState(agentSystemPrompt, sessionId, agentId);
        // [C-31] fork effort 注入 · 对齐 CC SkillTool.ts:208-212（fork effort 合并进 agentDefinition）
        //   → query.ts:694（appState.effortValue 逐轮注入）。子 AgentState.effortValue 经
        //   LlmAgentLoop ModelRequest 构造（:2762 等价）自动携带到 fork LLM 请求 → AnthropicSdkProvider
        //   buildMessageParams（output_config.effort）。数据形态合并（withEffort）由此变为真实 LLM 应用。
        if (agentDefinition.effort().isPresent()) {
            state.setEffortValue(agentDefinition.effort().get());
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [C-31] 子 AgentState 注入 effortValue={} (CC SkillTool.ts:208-212)",
                        agentDefinition.effort().get());
            }
        }
        try {
            state.maxTurns(maxTurns);
            for (Map<String, Object> raw : initialMessages) {
                ChatMessageDto msg = convertToChatMessageDto(raw, sessionId);
                if (msg != null) state.appendMessage(msg);
            }
            // [IMP-G4 C7] 按名路由待办消息消费 · 对齐 CC queuePendingMessage（SendMessageTool.ts:810-813
            //   → LocalAgentTask 每轮消费 pendingMessages）。本 agent 存活期间 SendMessage 按名投递的
            //   待办消息在此 drain 为 user 消息前置到对话（"Message queued for delivery ... at its next
            //   tool round" 语义）。agentNameRegistry 未注入 → no-op（不破坏既有调用）。
            if (agentNameRegistry != null) {
                List<String> pending = agentNameRegistry.drain(agentId.toString());
                if (!pending.isEmpty()) {
                    // [UP-01] coordinator 包裹门控 · 对齐 CC getAgentPendingMessageAttachments
                    //   （attachments.ts:1085-1102 → drained.map → {type:'queued_command',
                    //   origin:{kind:'coordinator'}, isMeta:true}）+ wrapCommandText case 'coordinator'
                    //   （messages.ts:5502-5505，逐字）+ wrapMessagesInSystemReminder（messages.ts:3784）。
                    //   门控：settings.coordinator_mode_enabled（DB 实时读源）非 null → 用 DB 值；
                    //   null → CoordinatorMode.isCoordinatorMode() 回落（feature+env 双真，默认关
                    //   = 未配置零行为变化）。CC 本无条件包 coordinator（concern 1 文档化偏差，owner 可拍板）。
                    boolean coordGate = (promptAlignSettingsResolver != null
                            && promptAlignSettingsResolver.coordinatorModeEnabled() != null)
                        ? promptAlignSettingsResolver.coordinatorModeEnabled()
                        : (coordinatorMode != null && coordinatorMode.isCoordinatorMode());
                    int coordinatorWrapped = 0;
                    for (String pm : pending) {
                        if (coordGate) {
                            // CC wrapCommandText case 'coordinator'（messages.ts:5502-5505）逐字 +
                            //   :3784 system-reminder 包裹（与 UP-03 queued_command 消费端模式一致）；
                            //   isMeta=true → 前端隐藏、模型可见（对齐 CC queued_command isMeta:true）。
                            String wrapped = "<system-reminder>\n"
                                + "The coordinator sent a message while you were working:\n" + pm
                                + "\n\nAddress this before completing your current task.\n</system-reminder>";
                            state.appendMessage(LlmAgentLoop.toMessage(Role.user, wrapped, null, null, true));
                            coordinatorWrapped++;
                        } else {
                            Map<String, Object> pendingMsg = new LinkedHashMap<>();
                            pendingMsg.put("role", "user");
                            pendingMsg.put("content", pm);
                            ChatMessageDto dto = convertToChatMessageDto(pendingMsg, sessionId);
                            if (dto != null) state.appendMessage(dto);
                        }
                    }
                    log.info("[SubagentExecutor] [IMP-G4 C7] 消费按名路由待办消息 {} 条 → 前置 user 消息 "
                            + "(agentId={}, coordGate={}, coordinatorWrapped={}, CC queuePendingMessage)",
                        pending.size(), agentId, coordGate, coordinatorWrapped);
                }
            }
            int initialMsgCount = state.messages().size();

            // [S4-1 流式化] 初始消息加载完成后武装 appendListener (mid-flight 单点方案):
            //   对齐 CC runAgent.ts:748-806 for-await 逐消息 yield — appendMessage 是唯一消息
            //   append 通道 (AgentState.java:698 单点, LlmAgentLoop 14 处 append + AgentLoopContext
            //   :1499/:1575/:1582 + StreamingToolExecutor 回写全部汇聚), 回调 = 实时转录
            //   (CC :794-800 recordSidechainTranscript) + 流式 emit (CC :804 yield message) +
            //   lastRecordedUuid 逐消息维护 (CC :801-802, progress 不更新).
            //   旧后置批量循环已删除 (P0-2 根因: 首消息不早于终态, 与 CC 实时语义相悖).
            //   回调体线程安全约束: StreamingToolExecutor 异步 append 跨线程触发, record 写文件 +
            //   sink.accept 均不持锁.
            java.util.concurrent.atomic.AtomicReference<String> currentParentUuid =
                new java.util.concurrent.atomic.AtomicReference<>(lastRecordedUuid);
            // [IMP-G4 E5] assistant 消息响应长度累计（setResponseLength 通道）· 对齐 CC
            //   AgentTool.tsx:1094-1102 getAssistantMessageContentLength → setResponseLength。
            //   Java setResponseLength 是 Consumer<String>（接收累计长度，StreamCompactSummary :651-652
            //   累加语义），此处按 CC 逐 assistant 消息累加 contentLength，total 经 accept 透传
            //   （fork 共享父 setResponseLength，子 agent 运行期间 spinner 反映子 agent 响应长度）。
            java.util.concurrent.atomic.AtomicLong subagentResponseLength =
                new java.util.concurrent.atomic.AtomicLong(0L);
            state.setAppendListener(msg -> {
                // [IMP-G4 E5] assistant 消息 token 计数接线 · 对齐 CC AgentTool.tsx:1094-1102：
                //   getAssistantMessageContentLength（tokens.ts:183-207 文本/thinking/tool_use input 求和）
                //   → setResponseLength(len => len + contentLength)。Java ChatMessageDto content=文本、
                //   toolCalls=tool_use 列表，取两者长度和近似 CC contentLength。
                if (msg.role() == Role.assistant) {
                    int contentLength = msg.content() != null ? msg.content().length() : 0;
                    if (msg.toolCalls() != null) {
                        for (var tc : msg.toolCalls()) {
                            if (tc.arguments() != null) {
                                contentLength += tc.arguments().length();
                            }
                        }
                    }
                    if (contentLength > 0) {
                        long total = subagentResponseLength.addAndGet(contentLength);
                        // [冲突裁决·并集修复] IMP-SUB-19 #23 已删 SubagentContext 包装（create() 直接返回
                        //   ToolUseContext），subagentCtx 即 ToolUseContext —— 原 master IMP-G4 E5 的
                        //   subagentCtx.toolUseContext() 解包装调用改为直调（CC AgentTool.tsx:1094-1102
                        //   setResponseLength 语义不变）。
                        if (subagentCtx.setResponseLength() != null) {
                            subagentCtx.setResponseLength().accept(String.valueOf(total));
                        }
                    }
                    // [IMP-G4 F17] assistant 消息计数（完成遥测 assistant_message_count · 对齐 CC
                    //   finalizeAgentTool agentMessages.length）
                    assistantMessageCount++;
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] [IMP-G4 E5] assistant 消息响应长度累计: "
                                + "contentLength={} total={} count={} (CC AgentTool.tsx:1094-1102)",
                            contentLength, subagentResponseLength.get(), assistantMessageCount);
                    }
                }
                // [D-6] 逐 assistant message 进度累积 · 对齐 CC agentToolUtils.ts:571
                //   updateProgressFromMessage(tracker, message, ...) → getProgressUpdate(tracker)
                //   (LocalAgentTask.tsx:68-104)。CC 在 for-await 逐消息 yield 处调用；Java 等价位
                //   = appendListener（appendMessage 单点通道）。仅 assistant 消息累积，user/tool
                //   跳过（CC updateProgressFromMessage 首行 message.type !== 'assistant' return）。
                //   usage==null（OpenAI non-streaming 未解析，AgentUsage 已登记缺口）跳过——
                //   CC assistant message 恒有 usage，Java 数据缺口不伪造、不重置 latestInputTokens。
                if (progressTracker != null && msg.role() == Role.assistant && msg.usage() != null) {
                    int toolUseInMessage = msg.toolCalls() != null ? msg.toolCalls().size() : 0;
                    long beforeToolUse = progressTracker.toolUseCount();
                    long beforeToken = progressTracker.tokenCount();
                    progressTracker.accumulateFromMessage(msg.usage(), toolUseInMessage);
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] [D-6] progress 累积: agent={} assistant 消息 "
                                + "toolUse+{} → toolUse={} token={} (前 toolUse={} token={})",
                            agentId, toolUseInMessage,
                            progressTracker.toolUseCount(), progressTracker.tokenCount(),
                            beforeToolUse, beforeToken);
                    }
                }
                // 实时转录 + parent chain · 对齐 CC runAgent.ts:794-800
                //   recordSidechainTranscript([message], agentId, lastRecordedUuid)
                recordMessageTranscript(sessionDir, sessionId.toString(), agentIdHex,
                        msg, currentParentUuid.get());
                // [P1-18] 2 参 toSubagentMessage: 注入 fork agentId + toolContent 判定,
                //   供 SkillToolImpl.buildForkProgressSink 回填 SkillProgressData.agentId
                //   (CC SkillTool.ts:256) 与 tool 块过滤 (CC SkillTool.ts:246-248).
                SubagentMessage out = toSubagentMessage(msg, agentIdHex);
                if (messageSink != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentExecutor] [S4-1 mid-flight] sink 接收 type={} parentUuid={} "
                                + "内容长度={} toolContent={} agentId={}",
                                out.getClass().getSimpleName(),
                                currentParentUuid.get() != null ? currentParentUuid.get() : "null",
                                msg.content() != null ? msg.content().length() : 0,
                                out.toolContent(), out.agentId());
                    }
                    messageSink.accept(out);
                }
                // CC runAgent.ts:801-802: progress 不更新 lastRecordedUuid, 其余消息更新。
                // ProgressMessage 现经 withOnToolProgress 独立通道产出（本方法下方 [REW-PROGRESS R32-03]
                //   .withOnToolProgress → toProgressMessage → messageSink），不落 appendListener
                //   （progress 非 AgentState 消息，独立通道是设计而非"不产出"）。本分支保留对齐
                //   CC runAgent.ts:801 progress 不更新 lastRecordedUuid；DEC-25 降级
                //   （messageSink==null async/非流式不产出）仍成立。
                if (!(out instanceof SubagentMessage.ProgressMessage) && msg.id() != null) {
                    currentParentUuid.set(msg.id());
                }
            });
    
            log.info("[SubagentExecutor] [H7-arch Phase 2] queryLoop 启动: agentId={} maxTurns={} initialMsgs={} tools={} model={}",
                    agentId, maxTurns, initialMsgCount, allTools.size(), effectiveModel);
    
            // [Phase A 任务 2] 启动前 abort 早停守卫 · 避免无意义的 LLM 调用.
            if (subagentCtx.abortController() != null
                    && subagentCtx.abortController().isCancelled()) {
                log.info("[SubagentExecutor] Query loop 启动前已 abort, 跳过 LLM 调用: agentId={}", agentId);
                // [S4] usage/totalTokens 兜底 (无 LLM 调用 → 0/EMPTY, 对齐 CC 无 assistant message 路径)
                return SubagentResult.aborted(
                        extractConclusionFromMessages(state.messages()), 0, 0L, agentIdHex,
                        0L, AgentUsage.EMPTY);
            }
    
            // [H7-arch Phase 2] abort 关联 · abortControllerRef.onCancel -> state.cancel()
            // 对齐 CC query 内部检查 signal.aborted；Java loop L1798 state.cancelled() 由 abortController 驱动.
            // sync 模式 abortControllerRef = parentAbort（共享父引用），父 abort 立即触发 state.cancel().
            AbortController abortRef = subagentCtx.abortController();
            if (abortRef != null) {
                abortRef.onCancel(ac -> {
                    if (!state.cancelled()) {
                        state.cancel();
                        log.info("[SubagentExecutor] [H7-arch Phase 2] abortController 触发 state.cancel: agentId={}", agentId);
                    }
                });
            }
    
            com.nexusai.application.agent.loop.LoopResult result;
            // [IMP-SUB-25 返工 R1 门2] effectiveForkMode 提升到内层 try 外作用域：
            //   handoff 门 2（下方 :3716 classifyHandoffIfNeeded 调用点）需读子 agent 实际生效 mode
            //   （CC toolPermissionContext.mode 语义，runAgent.ts:415-432），而该值在内层 try 内派生
            //   （:3588 resolveEffectiveForkMode），try 结束即出作用域。初始值 = permissionMode（方法参数，
            //   仅在内层 try 异常提前返回时兜底；成功路径必被 :3588 覆盖后才走到 handoff 调用点）。
            PermissionMode effectiveForkMode = permissionMode;
            try {
                // [H7-arch Phase 5-2 P3-③] 接口层：SubagentLoopDeps 持 AgentLoopContext（factory.shared() 隔离 ctx）。
                // base TUC = subagentCtx（IMP-SUB-19 #23: create() 直接返回 ToolUseContext；
                //   fork 继承父回调 + queryTracking 新链）+ 修正 availableTools=effectiveTools
                // （对齐 CC runAgent 工具隔离）。
                com.nexusai.application.agent.loop.SubagentLoopDeps deps =
                    new com.nexusai.application.agent.loop.SubagentLoopDeps(
                        // [IMP-D F4/M-07] 子代理 LoopSessionState.workspaceDir 注入会话 projectRoot
                        //   （修 M-07 user.dir 兜底链：STOP hook transcript_path → P/<session>/subagents/...）。
                        //   本线程已由 spawn 作用域注入（Step 20），读 holder 即会话值。
                        contextFactory.shared(AutoMemPaths.currentSessionProjectRoot()));
                // [RES-R6] resume 重建的 ContentReplacementState 注入 query loop session state
                // （对齐 CC resumeAgent.ts:194 runAgentParams.contentReplacementState → query.ts:372-389
                //   applyToolResultBudget 消费同一实例）。null（父 live state 不可得 / 非 resume）→
                //   loop 保持默认 create（CC :1006 reconstructForSubagentResume 返 undefined feature off）。
                injectContentReplacementState(deps, contentReplacementState);
                java.util.List<String> consumedCommandUuids = new ArrayList<>();
                ToolUseContext baseTuc = subagentCtx.withAvailableTools(allTools);
                // [H9 v3 Gap①] 应用 agent 的 permissionMode 到子 base TUC (对齐 CC runAgent.ts:415-432)
                //   — 之前 resolvePermissionMode(agentDefinition) 的结果只传给 runSubagentQueryLoop
                //   但从未落到 base TUC, 导致 fork 子 agent 的 BUBBLE mode 生产不可达.
                //   CC 真源: agentPermissionMode 定义时覆盖 toolPermissionContext.mode (runAgent.ts:419-432),
                //   父 mode 为 bypassPermissions/acceptEdits/auto 时父优先级更高不覆盖 (:424-431).
                //   fork 的 ForkSubagentAgentDefinition permissionMode="bubble" → BUBBLE → toolExecContext
                //   据此派生 awaitAutomatedChecksBeforeDialog=true → gate coordinator 分支生产可达.
                PermissionMode parentMode = subagentCtx.permissionMode();
                effectiveForkMode = resolveEffectiveForkMode(agentDefinition, permissionMode, parentMode);
                // [B-2] shouldAvoidPermissionPrompts 落地 · 对齐 CC runAgent.ts:440-451:
                //   CC 经 agentGetAppState wrapper 每次读 state 时把 shouldAvoidPrompts 写入
                //   toolPermissionContext; Java 无函数式 getAppState wrapper → 等价位 = 在子
                //   base TUC permCtx 落 flag, 再由 AgentLoopContext.toolExecContext →
                //   PermissionContextBuilder 每轮重建时保真 (builder 4 参重载).
                //   覆盖条件: base TUC 已有 permCtx (父继承) 且 flag 不同 → 重建;
                //   standalone 无 permCtx 且应避免弹窗 → 新建最小 permCtx 作 flag 载体
                //   (否则 per-turn 重建拿不到 flag, 恒 false).
                com.nexusai.application.agent.permission.ToolPermissionContext basePermCtx = baseTuc.permissionContext();
                boolean baseAvoidFlag = basePermCtx != null
                    && basePermCtx.shouldAvoidPermissionPrompts();
                if (effectiveForkMode != parentMode || baseAvoidFlag != shouldAvoidPermissionPrompts) {
                    com.nexusai.application.agent.permission.ToolPermissionContext targetPermCtx;
                    if (basePermCtx != null) {
                        // 父继承的 permCtx 原样保留, 仅替换 shouldAvoidPermissionPrompts (CC spread 语义)
                        targetPermCtx = new com.nexusai.application.agent.permission.ToolPermissionContext(
                            basePermCtx.mode(), basePermCtx.alwaysAllowRules(), basePermCtx.alwaysDenyRules(),
                            basePermCtx.alwaysAskRules(), basePermCtx.additionalWorkingDirectories(),
                            basePermCtx.isBypassPermissionsModeAvailable(), basePermCtx.isAutoModeAvailable(),
                            basePermCtx.strippedDangerousRules(), shouldAvoidPermissionPrompts,
                            basePermCtx.awaitAutomatedChecksBeforeDialog(), basePermCtx.prePlanMode());
                    } else {
                        // standalone 无父 permCtx: 新建最小 permCtx 携带 flag (规则集空 — 与
                        // standalone TUC 现状一致, 仅补 flag 载体; per-turn 重建以 builder 结果为准)
                        targetPermCtx = new com.nexusai.application.agent.permission.ToolPermissionContext(
                            effectiveForkMode, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                            java.util.Map.of(), true, true, java.util.Map.of(),
                            shouldAvoidPermissionPrompts, false, null);
                    }
                    baseTuc = baseTuc.withPermissionContext(targetPermCtx, effectiveForkMode);
                    log.info("[SubagentExecutor] [H9 v3 Gap① + B-2] 子 base TUC permissionMode={}, "
                            + "shouldAvoidPermissionPrompts={} (agent={})",
                        effectiveForkMode, shouldAvoidPermissionPrompts, agentDefinition.agentType());
                }
                // [H7-arch Phase 5-2 B1] 调用方收敛：构造 loop.QueryParams（deps 从 params 读）。
                // [Q-3 fork querySource 递归守卫闭环] 对齐 CC runAgent.ts:694
                //   ...(useExactTools && { querySource }) + AgentTool.tsx:332 主检查读
                //   context.options.querySource === 'agent:builtin:fork'. fork 子 agent 的
                //   QueryParams.querySource 必须为 FORK, 经 buildSubagentAgentOptions(FORK) 映射回
                //   'agent:builtin:fork' 字符串注入 AgentOptions.querySource, 抗 autocompact 递归守卫
                //   (autocompact 只重写 messages 不重写 context.options). 非 fork path 保持 SUBAGENT.
                // [IMP2-05 运行时接线 · 分工] 枚举 forkQuerySource 承担守卫类别（autocompact 递归守卫 /
                //   persist gate / 529 / main-thread 判定消费 canonical），agentType 级精确值由
                //   agentOptions.querySource() 承担 —— 该值源自 resolveQuerySource(isForkPath,
                //   agentDefinition)（Step 10 :1445 组合点）：fork → 'agent:builtin:fork'
                //   （ForkSubagent.FORK_QUERY_SOURCE，对齐 CC runAgent.ts:694 fork 子继承父 querySource）；
                //   非 fork → agentDefinition.querySourceForAgent()（对齐 CC promptCategory.ts:16-28
                //   getQuerySourceForAgent → AgentTool.tsx:609 toolUseContext.options.querySource ??
                //   getQuerySourceForAgent(...)：内置 → 'agent:builtin:<type>' / 'agent:default'，
                //   自定义/插件 → 恒常量 'agent:custom'）。经 QueryParams.withQuerySourceValue 透传到
                //   loop 发射侧（LlmAgentLoop :3830 effectiveValue 优先取用），复活
                //   AgentRunOptions.querySource 死路径（:3522，原 0 读取点）。
                // [Fix-D4] workflow 后端委托（querySourceOverride='workflow'）→ agentOptions.querySource()
                //   已含该值（Step 10 组合点），守卫类别经 fromString 归一 WORKFLOW 枚举（canonical
                //   'workflow'）。persist gate（query.ts:376-378）按 canonical 前缀判定：'workflow'
                //   非 agent:/repl_main_thread → 不持久化（对齐 CC claudeCodeBackend.ts:304 'workflow'
                //   语义）。无法归一（标准子代理精确值 'agent:builtin:<type>'）→ 回退 isForkPath 派生
                //   （FORK/SUBAGENT）。fork 精确值 'agent:builtin:fork' 经 fromString 归一 FORK（守卫不变性）。
                QuerySource overrideCategory = QuerySource.fromString(agentOptions.querySource());
                QuerySource forkQuerySource = overrideCategory != null
                    ? overrideCategory
                    : (isForkPath ? QuerySource.FORK : QuerySource.SUBAGENT);
                String exactQuerySource = agentOptions.querySource();
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] [IMP2-05] querySource 派生: isForkPath={} 守卫类别={} "
                            + "精确值={} agentType={} (CC promptCategory.ts:16-28 + AgentTool.tsx:609 + runAgent.ts:694 + claudeCodeBackend.ts:304)",
                            isForkPath, forkQuerySource, exactQuerySource, agentDefinition.agentType());
                }
                com.nexusai.application.agent.loop.QueryParams queryParams =
                    com.nexusai.application.agent.loop.QueryParams.forLoop(
                        state.messages(), agentSystemPrompt, baseTuc,
                        forkQuerySource, effectiveModel, maxTurns, null, null, null, null,
                        deps, providerConfig)
                        // [IMP2-05 运行时接线] 注入 agentType 级精确 querySource（CC querySource 值域
                        //   唯一来源：promptCategory.ts:16-28 getQuerySourceForAgent → AgentTool.tsx:609
                        //   toolUseContext.options.querySource ?? getQuerySourceForAgent(...)，fork 子
                        //   继承父 runAgent.ts:694）。null 防护：resolveQuerySource 两分支均返回非 null
                        //   （常量 / querySourceForAgent 默认方法），withQuerySourceValue(null) 时发射侧
                        //   回退 category.canonical()（'agent:subagent' 聚合占位，向后兼容）。
                        .withQuerySourceValue(exactQuerySource)
                        // [R1-THINK] 注入派生 thinkingConfig（fork 继承父 / 非 fork disabled）——
                        //   forLoop 恒 null→disabled 是硬默认；此处用 withThinkingConfig 把 Step 17
                        //   派生结果（resolveForkThinkingConfig → AgentRunOptions.thinkingConfig）真正
                        //   注入 query loop，使 fork child 运行时继承父 thinking（CC runAgent.ts:682-684，
                        //   关闭 DISC-SUB-03 EV-FK-014 应用层惰性缺口）.
                        .withThinkingConfig(toThinkingConfig(agentOptions.thinkingConfig()))
                        // [REW-PROGRESS R32-03] 工具进度 → ProgressMessage 产出接线 · CC original:
                        //   toolExecution.ts:550 createProgressMessage（tool.call progress 回调 →
                        //   query.ts:1380-1387 yield update.message）→ runAgent.ts:792-805 yield progress
                        //   （isRecordableMessage('progress') 命中；progress 不更新 lastRecordedUuid :801-802）。
                        //   QueryParams.onToolProgress → AgentLoopContext/LlmAgentLoop exec.add(call,parent,
                        //   onProgress) → StreamingToolExecutor wrappedCallback（:1520-1540）转发到本消费者。
                        //   DEC-25 降级保留: messageSink==null（async worker / 非流式 execute）→ 不产出
                        //   （对齐 CC S4-7 async 无实时进度；仅流式 sink 消费端可见 progress）。
                        .withOnToolProgress(progress -> {
                            if (messageSink != null) {
                                SubagentMessage pm = toProgressMessage(progress);
                                if (log.isDebugEnabled()) {
                                    String desc = pm instanceof SubagentMessage.ProgressMessage pp
                                        ? pp.description() : "";
                                    log.debug("[SubagentExecutor] [REW-PROGRESS] sink 产出 ProgressMessage: "
                                            + "toolUseId={} desc={} (CC runAgent.ts:792-805 yield progress)",
                                            progress.toolUseId(),
                                            desc != null && desc.length() > 80 ? desc.substring(0, 80) + "..." : desc);
                                }
                                messageSink.accept(pm);
                            }
                        });
                log.info("[SubagentExecutor] [R1-THINK] thinkingConfig 注入 QueryParams: type={} budgetTokens={} "
                        + "(fork 继承父 / 非 fork disabled · CC runAgent.ts:682-684)",
                        queryParams.thinkingConfig().type(), queryParams.thinkingConfig().budgetTokens());
                log.info("[SubagentExecutor] [IMP2-05] 子 agent querySource 精确值注入: 守卫类别={} 精确值={} "
                        + "有效值={} agentType={} (CC promptCategory.ts:16-28 + AgentTool.tsx:609 + runAgent.ts:694)",
                        forkQuerySource, exactQuerySource,
                        QuerySource.effectiveValue(queryParams.querySource(), queryParams.querySourceValue()),
                        agentDefinition.agentType());
                result = LlmAgentLoop.queryLoop(queryParams, state, consumedCommandUuids);
            } catch (Exception e) {
                log.error("[SubagentExecutor] [H7-arch Phase 2] queryLoop 抛出: {}", e.toString());
                String fallbackText = extractConclusionFromMessages(state.messages());
                // [S4] 异常路径 usage 兜底: 从已累积的 state.messages() 提取 (可能含已产出的 assistant 消息)
                AgentUsage fallbackUsage = extractUsageFromMessages(state.messages());
                // [A5-2] 子 Agent 预算求和分派：按 effectiveModel 判 anthropic（deepseek input 已含
                //   cache hit，4 项和双计 over-count）。mapper 不可得 → 回落 anthropic 语义。
                boolean fallbackAnthropic = (modelMapper != null && providerMapper != null)
                    ? ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, effectiveModel)
                    : true;
                long fallbackTokens = extractTotalTokens(state.messages(), fallbackAnthropic);
                // [R-A2] A-2 异常 catch 路径 totalToolUseCount 真实计数 · 对齐 CC agentToolUtils.ts:262-274
                //   countToolUses —— queryLoop 异常时 state.messages() 可能已累积部分 assistant 消息
                //   （已产出的 tool_use 块），原两分支硬编码 0 会让父 Agent 在异常场景系统性低估子
                //   Agent 工具用量（tool budget / task-notification usage 段）。与正常完成路径 :4033
                //   同口径：从 initialMsgCount 起计数（父 context / fork 前缀 tool_use 不计入子 Agent）。
                int fallbackToolUseCount = countToolUses(state.messages(), initialMsgCount);
                if (log.isInfoEnabled()) {
                    log.info("[SubagentExecutor] [R-A2] queryLoop 异常路径 totalToolUseCount 真实计数: "
                            + "agentId={} toolUseCount={} aborted={} (CC countToolUses agentToolUtils.ts:262-274)",
                            agentId, fallbackToolUseCount,
                            subagentCtx.abortController().isCancelled());
                }
                // [IMP-SUB-25 返工 R6] sync 错误恢复分类 · 对齐 CC AgentTool.tsx:1223-1252：
                //   syncAgentError 但已产出 assistant 消息（部分输出可能含危险动作）→ CC 仍运行
                //   classifyHandoffIfNeeded 并前置警告（:1235-1252）。Java 本 catch = 同场景的
                //   sync 错误恢复（返回 completed），非 abort 时镜像该语义。async 异常路径保持
                //   不分类（对齐 CC async catch agentToolUtils.ts:638- killAsyncAgent 无分类），
                //   故仅 !isAsync 分支走错误恢复分类。
                if (!subagentCtx.abortController().isCancelled() && !isAsync) {
                    fallbackText = applySyncErrorRecoveryClassification(
                            state.messages(), effectiveForkMode, effectiveType,
                            fallbackToolUseCount,
                            subagentCtx, fallbackText);
                }
                return subagentCtx.abortController().isCancelled()
                        ? SubagentResult.aborted(fallbackText, fallbackToolUseCount, 0L, agentIdHex, fallbackTokens, fallbackUsage)
                        : SubagentResult.completed(fallbackText, fallbackToolUseCount, 0L, agentIdHex, fallbackTokens, fallbackUsage);
            }
    
            // [S4-1 流式化] 后置批量 transcript 录制 + 逐消息 emit 循环已删除 —
            //   实时性由 appendListener mid-flight 回调承担 (武装于初始消息加载后,
            //   见上方 setAppendListener), 本循环若保留会产生双 emit/双转录 (P0-2 根因).
            //   usage/totalTokens 仍从终态消息提取 (对齐 CC finalizeAgentTool agentToolUtils.ts:319/355).
            AgentState finalState = result.finalState();
    
            boolean aborted = result.aborted() || (finalState != null && finalState.cancelled());
            List<ChatMessageDto> summarySource = finalState != null ? finalState.messages() : state.messages();
            String summary = extractConclusionFromMessages(summarySource);
            // [IMP-SUB-03] D3 totalToolUseCount 恒 0 修复 · 对齐 CC agentToolUtils.ts:262-274
            //   countToolUses(agentMessages) —— 从消息历史 tool_use 计数填充。原恒 0 的
            //   LoopResult.totalToolUseCount 死字段（queryLoop 硬编码 0，全仓无消费方）已在
            //   IMP-SUB-03 返工时从 LoopResult record 删除，真实计数只走本处 countToolUses。
            //   CC finalizeAgentTool 在 finalize 站点（agentToolUtils.ts:320）计数而非 query 循环，
            //   Java 等价位 = 本 result 构造点。CC agentMessages = query 循环产出消息
            //   （AgentTool.tsx:786 空数组 + :1065 push，不含 initialMessages/fork 前缀）；
            //   Java finalState.messages() 含 initialMsgCount 前缀（父 context 的 tool_use 不应计入
            //   子 agent 自身计数），故从 initialMsgCount 起计数。
            int totalToolUseCount = countToolUses(summarySource, initialMsgCount);
            int totalTurns = result.totalTurns();
            // [S4 P1 差异项 2] usage/totalTokens 从末尾 assistant 消息提取 (对齐 CC agentToolUtils.ts:319/355)
            AgentUsage usage = extractUsageFromMessages(summarySource);
            // [A5-2] 子 Agent 预算求和分派：按 effectiveModel 判 anthropic（deepseek input 已含
            //   cache hit，4 项和双计 over-count）。mapper 不可得 → 回落 anthropic 语义。
            boolean anthropic = (modelMapper != null && providerMapper != null)
                ? ContextUsageCalculator.isAnthropic(modelMapper, providerMapper, effectiveModel)
                : true;
            long totalTokens = extractTotalTokens(summarySource, anthropic);
    
            log.info("[SubagentExecutor] [H7-arch Phase 2] queryLoop 完成: agentId={} turns={} toolUseCount={} msgs={} aborted={} totalTokens={}",
                    agentId, totalTurns, totalToolUseCount,
                    finalState != null ? finalState.messages().size() : initialMsgCount, aborted, totalTokens);

            // [IMP-SUB-25 D-3] handoff 安全分类 · 对齐 CC AgentTool.tsx:1238 / agentToolUtils.ts:608
            //   （completed 终态复核；abort 路径不分类）。
            //   [返工 R6 注释更正] "失败路径 CC 一律不分类" 的断言与 CC 真源不符：sync 错误恢复
            //   已有 assistant 消息时 CC 仍运行 classifyHandoffIfNeeded（AgentTool.tsx:1223-1252，
            //   :1226-1228 无 assistant 消息才重抛、:1235-1252 有则 finalize + 分类 + 前置）。
            //   该 sync 错误恢复分支由下方 catch 路径 applySyncErrorRecoveryClassification 承接；
            //   此处为正常完成终态（sync/async 均分类）分支。
            if (!aborted) {
                // [IMP-SUB-25 返工 R1 门2] 传 effectiveForkMode（子 agent 实际生效 mode，:3588）而非
                //   permissionMode（agent 声明/DEFAULT）：对齐 CC toolPermissionContext.mode（runAgent.ts:415-432
                //   父 auto 不覆盖 + TRANSCRIPT_CLASSIFIER 开启时父 auto 恒保留）——父会话 auto-mode + 子 agent
                //   无声明 mode（内置 general-purpose 常见）→ effectiveForkMode=AUTO → handoff 分类器运行，
                //   与 CC 同场景行为一致（旧实现 permissionMode=DEFAULT → 门 2 跳过 → 分类器永不运行）。
                String handoffWarning = classifyHandoffIfNeeded(
                        summarySource, effectiveForkMode, effectiveType, totalToolUseCount,
                        subagentCtx);
                if (handoffWarning != null) {
                    if (log.isInfoEnabled()) {
                        log.info("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 警告前置到最终消息: agentId={} "
                            + "warnLen={} (CC AgentTool.tsx:1246-1251)", agentId, handoffWarning.length());
                    }
                    // [IMP-SUB-25 返工 R4] 静态 seam（Pattern #14）抽取前置拼接：同包单测验证
                    //   "安全警告必须随危险输出浮出"（规则九 WHY）。
                    summary = prependHandoffWarning(handoffWarning, summary);
                }
            }

            return aborted
                    ? SubagentResult.aborted(summary, totalToolUseCount, 0L, agentIdHex, totalTokens, usage)
                    : SubagentResult.completed(summary, totalToolUseCount, 0L, agentIdHex, totalTokens, usage);
        } finally {
            // [S4-1 流式化] finally 解除 appendListener (防泄漏: 本 state 为子 agent 隔离实例,
            //   不解除则异常/早退路径残留回调引用; 对齐 CC runAgent.ts:816-859 finally 清理).
            state.clearAppendListener();
            // [P1-6-CLEANUP-1] 子 agent 完成/失败路径释放 invokedSkills (对齐 CC 4 调用方)
            cleanSubagentInvokedSkills(state, agentId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Query loop helpers
    // ════════════════════════════════════════════════════════════════════════

    // [Phase 2 PR 1 删除] executeToolCallsWithPermissions 已删除, 由 StreamingToolExecutor +
    // ToolPermissionGate 替代 (CC useCanUseTool.tsx:27-191 真实权限门 + query.ts:1062 插入点).
    // 历史: 原 4 层决策 (BYPASS_PERMISSIONS / shouldAvoidPermissionPrompts / ACCEPT_EDITS / DEFAULT)
    // 仅是 10 层 PermissionPipeline 的子集, 缺 ask rule / deny rule / safetyCheck / classifier 等真实规则.

    // [Phase 2 PR 1 删除] canUseTool 4 层决策方法已删除, 由 PermissionPipeline 10 层 +
    // ToolPermissionGate 三态分支 (ALLOW/DENY/ASK) 替代. 对齐 CC hasPermissionsToUseToolInner
    // permissions.ts:1158-1319 真实决策树.

    /**
     * [IMP-SUB-25 返工 R4] handoff 安全警告前置到最终消息 · 对齐 CC AgentTool.tsx:1246-1251
     * （sync {@code agentResult.content} 前置语义：警告块在子 Agent 结论之前）。
     *
     * <p>static seam（Pattern #14）：同包单测直接验证「危险输出时安全警告必须随结论文本浮出」
     * （规则九 WHY）——若业务逻辑变更而测试仍绿，即该行为被破坏。
     *
     * @param warning handoff 分类警告字符串（null → 原样返回 summary）
     * @param summary 子 Agent 最终结论文本
     * @return {@code warning + "\n\n" + summary}；warning 为 null → summary 原样
     */
    static String prependHandoffWarning(String warning, String summary) {
        return warning != null ? warning + "\n\n" + summary : summary;
    }

    /**
     * 从消息历史中提取最终结论。
     * 从后往前找最后一条 assistant 消息的文本。
     */
    private String extractConclusionFromMessages(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = messages.get(i);
            if (msg.role() == Role.assistant && msg.content() != null && !msg.content().isBlank()) {
                return msg.content();
            }
        }
        return "Subagent completed without final answer.";
    }

    /**
     * [IMP-SUB-25 D-3] handoff 安全分类 · 对齐 CC {@code classifyHandoffIfNeeded}
     * (Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts:389-481)。
     *
     * <p>子 Agent 结束、交还控制权给父 Agent 前，用 auto-mode 分类器复核子 Agent 的
     * 工作终态（transcript + 复核提示词）。阻断时返回安全警告字符串，由调用方前置到
     * 最终消息（CC 三调用点：agentToolUtils.ts:608 / AgentTool.tsx:963 / :1238）。
     *
     * <p>门控（逐条对齐 CC）：
     * <ol>
     *   <li>{@code feature('TRANSCRIPT_CLASSIFIER')}（:404）→ Java {@link #transcriptClassifierEnabled}</li>
     *   <li>{@code toolPermissionContext.mode !== 'auto'}（:405）→ Java {@link PermissionMode#AUTO}</li>
     *   <li>{@code buildTranscriptForClassifier(agentMessages, tools)} 为空（:407-408）→ 跳过</li>
     *   <li>分类器不可用（null / !isAvailable）→ 跳过（对齐 PermissionPipeline:394 分类器不可用跳过约定）</li>
     * </ol>
     *
     * <p>决策（:426-459）→ {@code unavailable | blocked | allowed}，数据流日志输出（CC
     * {@code tengu_auto_mode_decision}）；命中 shouldBlock 时返回 CC 原文警告（:461-477）。
     *
     * <p><b>[返工 R3 接口缺口已收口]</b> CC handoff action 是 user 文本块（yoloClassifier.ts:418-421
     * {@code "User: {text}\n"}），本方法经 {@link YoloClassifier#classifyTextAction} 建模该
     * user-text action（YoloClassifierImpl 以 {@code "User: " + userText + "\n"} 作 actionCompact，
     * 非空 → 走真实 2-stage LLM 分类，不再短路 ALLOW）。{@code subagentType} 仅作决策日志
     * agentType 字段消费，不再投影作 toolName。
     *
     * @param agentMessages    子 Agent 终态消息（finalState.messages()）
     * @param effectiveMode    子 Agent 实际生效权限模式（CC toolPermissionContext.mode 语义，
     *                         调用点传 {@code effectiveForkMode} 而非 agent 声明 mode）
     * @param subagentType     子 Agent 类型（CC subagentType，:440 agentType，仅决策日志用）
     * @param totalToolUseCount 子 Agent 总工具调用数（CC totalToolUseCount，:442 toolUseCount）
     * @param ctx              子 Agent ToolUseContext（供分类器 abort 判定，CC abortSignal）
     * @return 安全警告字符串；无需警告返回 null
     */
    // [IMP-SUB-25 返工 R4] private → 包可见（Pattern #14 测试 seam）：同包单测直接调用聚焦验证
    //   安全警告必须随危险输出浮出（规则九 WHY）；不改行为。
    String classifyHandoffIfNeeded(
            List<ChatMessageDto> agentMessages,
            PermissionMode effectiveMode,
            String subagentType,
            int totalToolUseCount,
            ToolUseContext ctx) {
        // 门 1: feature('TRANSCRIPT_CLASSIFIER') (agentToolUtils.ts:404)
        if (!transcriptClassifierEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类跳过：TRANSCRIPT_CLASSIFIER 关闭");
            }
            return null;
        }
        // 门 2: toolPermissionContext.mode === 'auto' (agentToolUtils.ts:405)
        // [返工 R1] 判定用子 agent 实际生效 mode（effectiveMode = effectiveForkMode），
        //   对齐 CC toolPermissionContext.mode（父 auto 不覆盖，runAgent.ts:424-431）。
        if (effectiveMode != PermissionMode.AUTO) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类跳过：effectiveMode={} != auto",
                    effectiveMode);
            }
            return null;
        }
        // 门 3: buildTranscriptForClassifier 为空 → 跳过 (agentToolUtils.ts:407-408)
        String agentTranscript = buildTranscriptForClassifier(agentMessages);
        if (agentTranscript == null || agentTranscript.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类跳过：classifier transcript 为空");
            }
            return null;
        }
        // 门 4: 分类器不可用 → 跳过 (对齐 PermissionPipeline:394 分类器不可用跳过约定)
        if (yoloClassifier == null || !yoloClassifier.isAvailable()) {
            if (log.isWarnEnabled()) {
                log.warn("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类器不可用 → 跳过 handoff 复核 "
                    + "(subagentType={})", subagentType);
            }
            return null;
        }
        // 复核提示词 · CC agentToolUtils.ts:416-418（user text action）·
        // [返工 R3] 经 classifyTextAction 建模 user-text action（CC yoloClassifier.ts:418-421
        //   "User: {text}\n"），actionCompact 非空 → 真实走 2-stage LLM 分类（不再因投影
        //   subagentType 失配短路 ALLOW）。
        YoloClassifierResult classifierResult;
        try {
            classifierResult = yoloClassifier.classifyTextAction(
                HANDOFF_REVIEW_PROMPT, agentMessages, ctx).get();
        } catch (Exception e) {
            // CC classifyYoloAction 全路径不抛（错误收敛为 unavailable 结果）；Java 兜底跳过。
            if (log.isWarnEnabled()) {
                log.warn("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类调用失败 → 跳过 (subagentType={}): {}",
                    subagentType, e.toString());
            }
            return null;
        }
        // 决策 · CC agentToolUtils.ts:426-430 handoffDecision
        // [S06] YoloClassifierResult 对齐 CC 布尔 shouldBlock（⊕-02）—— 不再经 PermissionBehavior 三态
        boolean unavailable = Boolean.TRUE.equals(classifierResult.unavailable());
        boolean shouldBlock = classifierResult.shouldBlock();
        String handoffDecision = unavailable ? "unavailable" : shouldBlock ? "blocked" : "allowed";
        // 数据流日志 · CC :431-459 logEvent('tengu_auto_mode_decision', {...}) 的 Java 消费面
        if (log.isInfoEnabled()) {
            log.info("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类决策: decision={} unavailable={} "
                    + "subagentType={} toolUseCount={} agentMsgId={} classifierModel={} "
                    + "stage={} stage1RequestId={} stage2RequestId={} stage1MsgId={} stage2MsgId={} "
                    + "(CC tengu_auto_mode_decision agentToolUtils.ts:431)",
                handoffDecision, unavailable, subagentType, totalToolUseCount,
                getLastAssistantMessageId(agentMessages),
                classifierResult.model(), classifierResult.stage(),
                classifierResult.stage1RequestId(), classifierResult.stage2RequestId(),
                classifierResult.stage1MsgId(), classifierResult.stage2MsgId());
        }
        if (shouldBlock) {
            if (unavailable) {
                // CC :461-470 — 分类器不可用但命中 shouldBlock → 放行但附验证提示
                if (log.isWarnEnabled()) {
                    log.warn("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类器不可用，放行子 Agent 输出并附验证警告");
                }
                return HANDOFF_UNAVAILABLE_NOTE;
            }
            // CC :472-476 — 分类器标记子 Agent 输出 → SECURITY WARNING（reason 透传）
            if (log.isWarnEnabled()) {
                log.warn("[SubagentExecutor] [IMP-SUB-25 D-3] handoff 分类器标记子 Agent 输出: {}",
                    classifierResult.reason());
            }
            return "SECURITY WARNING: This sub-agent performed actions that may violate security policy. Reason: "
                + classifierResult.reason()
                + ". Review the sub-agent's actions carefully before acting on its output.";
        }
        return null;
    }

    /**
     * [IMP-SUB-25 返工 R6] sync 错误恢复 handoff 分类 · 对齐 CC AgentTool.tsx:1223-1252
     * 错误恢复语义（{@code syncAgentError && hasAssistantMessages} → 仍运行
     * {@code classifyHandoffIfNeeded} 并前置警告）。
     *
     * <p>门（CC :1225-1229）：子 Agent 消息含 assistant 消息（部分输出）才复核——有部分输出
     * 才可能含危险动作；无输出（CC :1226-1228 重抛、Java 错误恢复返回 completed）无产物可复核
     * → 不分类。镜像 CC 语义后调用 {@link #classifyHandoffIfNeeded}，命中警告则前置到结论文本
     * （CC :1246-1251 content 前置语义）。
     *
     * <p>包可见 seam（Pattern #14，同包单测经 mock YoloClassifier 验证 sync 错误恢复两分支
     * 有/无 assistant 消息）；本方法需经 {@link #classifyHandoffIfNeeded} 读
     * {@code yoloClassifier}/{@code transcriptClassifierEnabled} 字段，故为实例方法而非 static。
     *
     * @param messages       异常时已累积的子 Agent 消息（state.messages()，含 initialMsgCount 前缀）
     * @param effectiveMode  子 Agent 生效权限模式（effectiveForkMode：成功派生则真实解析值，
     *                       :3593 之前异常时为 permissionMode 兜底）
     * @param subagentType   子 Agent 类型（CC subagentType，决策日志 agentType 用）
     * @param totalToolUseCount 子 Agent 工具调用计数（异常路径经 countToolUses 重数）
     * @param ctx            子 Agent ToolUseContext（分类器 abort 判定，CC abortSignal）
     * @param fallbackText   异常路径结论文本（extractConclusionFromMessages 结果）
     * @return 命中警告时前置后的结论文本；无警告 / 无 assistant 消息 → 原样返回 fallbackText
     */
    String applySyncErrorRecoveryClassification(
            List<ChatMessageDto> messages,
            PermissionMode effectiveMode,
            String subagentType,
            int totalToolUseCount,
            ToolUseContext ctx,
            String fallbackText) {
        // CC AgentTool.tsx:1225-1229 hasAssistantMessages 门 —— 无 assistant 消息（无部分输出）→
        //   CC 重抛错误（:1228）；Java 错误恢复（返回 completed）无产物可复核 → 不分类。
        boolean hasAssistantMessages = messages.stream().anyMatch(m -> m.role() == Role.assistant);
        if (!hasAssistantMessages) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-SUB-25 R6] sync 错误恢复无 assistant 消息 → "
                        + "不分类（CC AgentTool.tsx:1226-1228 无部分输出不复核）");
            }
            return fallbackText;
        }
        String handoffWarning = classifyHandoffIfNeeded(
                messages, effectiveMode, subagentType, totalToolUseCount, ctx);
        if (handoffWarning != null) {
            if (log.isInfoEnabled()) {
                log.info("[SubagentExecutor] [IMP-SUB-25 R6] sync 错误恢复 handoff 警告前置到 "
                        + "结论文本: warnLen={} (CC AgentTool.tsx:1246-1251)", handoffWarning.length());
            }
            return prependHandoffWarning(handoffWarning, fallbackText);
        }
        return fallbackText;
    }

    /**
     * [IMP-SUB-25 D-3] 构建分类器转录 · 对齐 CC {@code buildTranscriptForClassifier}
     * (yoloClassifier.ts:434-442)：逐消息 {@code toCompact} 拼接。
     *
     * @param messages 子 Agent 终态消息
     * @return 拼接后的紧凑转录；空 → ""（调用方跳过）
     */
    private String buildTranscriptForClassifier(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        YoloPromptBuilder promptBuilder = new YoloPromptBuilder();
        Map<String, Tool> projectionLookup = buildClassifierProjectionLookup(
            subagentToolRegistry != null ? subagentToolRegistry.all() : List.of());
        StringBuilder sb = new StringBuilder();
        for (YoloPromptBuilder.CompactMessage entry : promptBuilder.buildTranscriptEntries(messages, projectionLookup)) {
            if (entry != null && entry.content() != null) {
                sb.append(entry.content());
            }
        }
        return sb.toString();
    }

    /**
     * [IMP-SUB-25 D-3] 构建分类器投影 lookup · 对齐 CC {@code buildToolLookup}
     * (yoloClassifier.ts:364-374)：name + 每个 alias → Tool。
     *
     * @param tools 子 Agent 工具集
     * @return name/alias → Tool 查表（不可变视图，可为空）
     */
    private static Map<String, Tool> buildClassifierProjectionLookup(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return Map.of();
        }
        Map<String, Tool> lookup = new HashMap<>();
        for (Tool tool : tools) {
            lookup.put(tool.name(), tool);
            for (String alias : tool.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    lookup.put(alias, tool);
                }
            }
        }
        return lookup;
    }

    /**
     * [IMP-SUB-25 D-3] 取末尾 assistant 消息 ID · 对齐 CC {@code getLastAssistantMessage}
     * (agentToolUtils.ts:447 {@code getLastAssistantMessage(agentMessages)?.message.id})。
     *
     * @param messages 子 Agent 终态消息
     * @return 末尾 assistant 消息的 id；无 → null
     */
    private static String getLastAssistantMessageId(List<ChatMessageDto> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageDto msg = messages.get(i);
                if (msg.role() == Role.assistant) {
                    return msg.id();
                }
            }
        }
        return null;
    }

    /**
     * 从消息历史提取 usage · 对齐 CC {@code lastAssistantMessage.message.usage} 直接透传
     * (agentToolUtils.ts:355) — 取 <b>末尾 assistant 消息</b> 的 usage (无 text 要求, 对齐
     * finalizeAgentTool {@code getLastAssistantMessage}).
     *
     * <p>[DEC-04 数据源闭环] 优先读 {@code ChatMessageDto.usage()} (provider 解析的完整 AgentUsage,
     * LlmAgentLoop withUsage 填充) — 与 CC message.usage 同构. 回退链 (消息未携带 usage 对象时):
     * ① DB 持久化/旧构造消息仅 inputTokens/outputTokens → fromInputOutput 投影; ② 无 assistant →
     * AgentUsage.EMPTY 零初始化哨兵 (对齐 CC emptyUsage.ts:8). 不再有"assistant 有 tokens 但读成
     * null"的半对齐 (DEC-04 症状: 恒 0/EMPTY).
     *
     * @param messages 子 Agent 完整消息历史 (finalState.messages())
     * @return usage record (末尾 assistant 消息的完整 usage; 无 assistant → EMPTY)
     */
    static AgentUsage extractUsageFromMessages(List<ChatMessageDto> messages) {
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageDto msg = messages.get(i);
                if (msg.role() == Role.assistant) {
                    AgentUsage usage = msg.usage();
                    if (usage != null) {
                        return usage;
                    }
                    // 旧/DB 消息仅 2 字段 → 投影回退
                    return AgentUsage.fromInputOutput(msg.inputTokens(), msg.outputTokens());
                }
            }
        }
        return AgentUsage.EMPTY;
    }

    /**
     * 计算总 token 数 · 对齐 CC {@code getTokenCountFromUsage} (tokens.ts:46-53):
     * {@code input + (cache_creation ?? 0) + (cache_read ?? 0) + output} · finalizeAgentTool
     * (agentToolUtils.ts:319) 同公式.
     *
     * <p><b>A5-2</b>: 1 参 = anthropic 语义（4 项和，CC 原生）——保持既有测试/seam 兼容；
     * 子 Agent 预算分派请用 {@link #extractTotalTokens(List, boolean)} 传 isAnthropic
     * （由调用点按 effectiveModel 判，deepseek input 已含 cache hit → 4 项和双计 over-count）。
     *
     * @param messages 子 Agent 完整消息历史
     * @return 末尾 assistant 消息的 4 token 字段之和 (无 assistant → 0)
     */
    static long extractTotalTokens(List<ChatMessageDto> messages) {
        return extractTotalTokens(messages, true);
    }

    /**
     * 计算总 token 数 · 协议分派重载（A5-2 · deepseek 双计修复）。
     *
     * @param messages  子 Agent 完整消息历史
     * @param anthropic 协议判定：true=4 项和；false=仅 input+output（子 Agent 预算口径）
     * @return 末尾 assistant 消息的 token 之和 (无 assistant → 0)
     */
    static long extractTotalTokens(List<ChatMessageDto> messages, boolean anthropic) {
        AgentUsage usage = extractUsageFromMessages(messages);
        return usage.totalTokens(anthropic);
    }

    /**
     * 统计消息历史中 assistant 消息的 tool_use 块总数 · 对齐 CC
     * {@code countToolUses} (Open-ClaudeCode/src/tools/AgentTool/agentToolUtils.ts:262-274)。
     *
     * <p>CC 逐条遍历消息：assistant 消息 content 中 type==='tool_use' 的块计数。Java 端
     * ChatMessageDto assistant 消息的 tool_use 块等价表示 = {@code toolCalls} 字段
     * （LlmAgentLoop append assistant 时写入，见 LlmAgentLoop.java:3871；SubagentExecutor
     * progressTracker 累积同口径 :3442）。CC 调用点 finalizeAgentTool 传 {@code agentMessages}
     * （仅 query 循环产出消息，不含 initialMessages/fork 前缀），故本方法带
     * {@code startInclusive} 起始下标（initialMsgCount），跳过初始/fork 前缀消息 —— 父
     * context 的 tool_use 不应计入子 agent 自身工具调用计数。
     *
     * @param messages       子 Agent 消息历史（finalState.messages()）
     * @param startInclusive 起始下标（initialMsgCount = 初始/fork 前缀消息数，0 = 全量计数）
     * @return tool_use 块总数
     */
    static int countToolUses(List<ChatMessageDto> messages, int startInclusive) {
        int count = 0;
        if (messages != null) {
            for (int i = startInclusive; i < messages.size(); i++) {
                ChatMessageDto m = messages.get(i);
                if (m.role() == Role.assistant && m.toolCalls() != null) {
                    count += m.toolCalls().size();
                }
            }
        }
        return count;
    }

    /**
     * 取 initialMessages 末尾消息的 uuid · 对齐 CC {@code runAgent.ts:745}
     * {@code lastRecordedUuid = initialMessages.at(-1)?.uuid ?? null}.
     *
     * <p>WHY static seam (Pattern #14): 验证 lastRecordedUuid parent chain 可复现 (非 random).
     * 空列表 → null (对齐 CC {@code ?? null}).
     *
     * @param initialMessages Step 10-14 装配的初始消息 Map 列表
     * @return 末尾消息 "uuid" 键值; null/空列表 → null
     */
    static String lastInitialMessageUuid(List<Map<String, Object>> initialMessages) {
        if (initialMessages == null || initialMessages.isEmpty()) {
            return null;
        }
        Object uuid = initialMessages.get(initialMessages.size() - 1).get("uuid");
        return uuid != null ? String.valueOf(uuid) : null;
    }

    /**
     * 给 initialMessages 每条 Map 补 "uuid" 键 · 对齐 CC Message.uuid 语义.
     *
     * <p>CC 每条 message 自带 uuid (runAgent.ts:745 {@code initialMessages.at(-1)?.uuid}); Java 端
     * initialMessages 是 {@code List<Map<String,Object>>} 无 uuid 键 (S4-5 决策 a: 给 map 加键,
     * 非类型化为 Message — 最小改动, 不触 $4.4 偏移 1 大重构). 幂等: 已有 uuid 键不覆盖.
     *
     * @param initialMessages Step 10-14 装配的初始消息 Map 列表 (就地修改)
     */
    static void assignInitialMessageUuids(List<Map<String, Object>> initialMessages) {
        if (initialMessages == null) {
            return;
        }
        for (Map<String, Object> m : initialMessages) {
            if (m != null && m.get("uuid") == null) {
                m.put("uuid", UUID.randomUUID().toString());
            }
        }
    }

    /**
     * ChatMessageDto → SubagentMessage · 对齐 CC runAgent.ts:792-805 recordable 消息 yield.
     *
     * <p>assistant → AssistantMessage (含 usage, CC agentToolUtils.ts:355); user/tool →
     * UserMessage; 其它 → SystemMessage. ProgressMessage 不落 AgentState (progress 是独立
     * 进度通道, 非消息历史) — 由 {@link #toProgressMessage} 经 {@code QueryParams.withOnToolProgress}
     * 单独产出 (R32-03, 见 runSubagentQueryLoop 接线); stream_event 由 CC 丢弃 (非 yield 类型),
     * 无对应 Java 子类型.
     *
     * @param msg 子 Agent 消息
     * @return 对应 SubagentMessage 子类型
     */
    static SubagentMessage toSubagentMessage(ChatMessageDto msg) {
        // [P1-18] 无 agentId 上下文 (测试/独立调用) → 委托 2 参版, agentId=null (SkillProgressData.agentId 兜底)
        return toSubagentMessage(msg, null);
    }

    /**
     * [P1-18] ChatMessageDto → SubagentMessage · 追加 agentId + toolContent 判定.
     *
     * <p><b>toolContent 判定 (对齐 CC SkillTool.ts:246-248)</b>: CC 检查消息 content 含
     * tool_use/tool_result 块. Java ChatMessageDto 中 assistant 消息的 tool_use 落在
     * {@code toolCalls()} (List&lt;ToolCallDto&gt;), role=tool 的 tool_result 落在 {@code toolCallId()};
     * 任一非空 → toolContent=true (等价 CC "content 含 tool 块"). 该标志供
     * {@link SkillToolImpl#buildForkProgressSink} 精确过滤 skill_progress 上报.
     *
     * <p><b>agentId 载体 (对齐 CC SkillTool.ts:256)</b>: fork agentId 由本类内部创建
     * (createSubagentContext), 消息发射点 (runSubagentQueryLoop) 已持有 → 注入每条
     * assistant/user 消息, 供 SkillToolImpl 回填 SkillProgressData.agentId (不再硬编码 null).
     *
     * @param msg     源消息
     * @param agentId 产出该消息的 fork 子 agent id (非 fork / 测试路径传 null)
     * @return SubagentMessage (assistant/user 消息携带 toolContent + agentId)
     */
    static SubagentMessage toSubagentMessage(ChatMessageDto msg, String agentId) {
        if (msg.role() == Role.assistant) {
            boolean toolContent = msg.toolCalls() != null && !msg.toolCalls().isEmpty();
            // [DEC-04] 优先透传完整 usage (msg.usage()), 旧 2 字段消息投影回退 (fromInputOutput)
            AgentUsage usage = msg.usage() != null
                ? msg.usage()
                : AgentUsage.fromInputOutput(msg.inputTokens(), msg.outputTokens());
            return new SubagentMessage.AssistantMessage(
                msg.content() != null ? msg.content() : "",
                usage,
                toolContent, agentId);
        }
        if (msg.role() == Role.user || msg.role() == Role.tool) {
            boolean toolContent = msg.role() == Role.tool && msg.toolCallId() != null;
            return new SubagentMessage.UserMessage(
                msg.content() != null ? msg.content() : "",
                toolContent, agentId);
        }
        return new SubagentMessage.SystemMessage(
            msg.content() != null ? msg.content() : "", null);
    }

    /**
     * Tool.ToolProgress → SubagentMessage.ProgressMessage · 对齐 CC
     * {@code createProgressMessage} (utils/messages.ts:603-618) + {@code runAgent.ts:792-805}
     * yield progress 的 Java 生产接线点（R32-03 闭环）。
     *
     * <p>CC 真源（已 grep 自验）: 工具执行时 {@code tool.call(..., progress => onToolProgress({
     * toolUseID: progress.toolUseID, data: progress.data }))} (toolExecution.ts:1216-1219) →
     * {@code createProgressMessage} 组装 {@code {type:'progress', data, toolUseID, parentToolUseID}}
     * (messages.ts:603-618) → query.ts:1380-1387 {@code yield update.message} → runAgent.ts:792-805
     * {@code isRecordableMessage('progress')} → recordSidechainTranscript（progress 不更新
     * lastRecordedUuid, runAgent.ts:801-802）→ yield 给上层。Java 端 {@code ProgressMessage}
     * 仅 description 一字段，description = 进度数据的可读渲染（String.valueOf(data)，忠实 CC
     * data 原样载荷，不猜测结构化字段）。
     *
     * <p>调用方: {@code runSubagentQueryLoop} 经 {@code QueryParams.withOnToolProgress} 接线——
     * 子 agent 工具（McpServerTool:538 / SkillToolImpl fork:1535）报告进度时构造 ProgressMessage
     * 发射 messageSink（CC AgentTool.tsx:1084-1092 对 bash/powershell progress 转父 onProgress，
     * 其它 progress 记录于 agentMessages；Java 端统一经 ProgressMessage 载体给 sink 消费端裁量）。
     *
     * @param progress 工具进度事件（CC toolExecution.ts:1216 progress 回调入参）
     * @return ProgressMessage（description = 进度数据可读形式；data null → 空串）
     */
    static SubagentMessage toProgressMessage(Tool.ToolProgress progress) {
        return new SubagentMessage.ProgressMessage(
            progress != null && progress.data() != null ? String.valueOf(progress.data()) : "");
    }

    /**
     * 记录单条消息 transcript（per-message 实时录制）· 对齐 CC runAgent.ts per-message
     * transcript 录制模式 (runAgent.ts:792-805 逐消息 recordSidechainTranscript)。
     *
     * <p>[S4-1 差异项 4 收尾] parentUuid 显式注入 · CC original: {@code recordSidechainTranscript(
     * [message], agentId, lastRecordedUuid)} (runAgent.ts:794-800, sessionStorage.ts:1451-1462
     * {@code startingParentUuid}) — Java 端单条录制时 AgentTranscript.enrichTranscriptMessage 的
     * prevUuid=null 无法自链, 必须由调用方携带 lastRecordedUuid 作为本条消息的 parentUuid,
     * 否则 transcript parent chain 断裂 (resume 时消息链重建错乱)。
     *
     * @param sessionDir  transcript 根目录
     * @param sessionId   会话 id
     * @param agentId     子 agent id
     * @param msg         待录制的消息 (id 经 chatMessageToMap 写入 map 的 "uuid" 键)
     * @param parentUuid  lastRecordedUuid (上一已录制消息的 uuid; null = 链首)
     */
    private void recordMessageTranscript(Path sessionDir, String sessionId, String agentId,
                                         ChatMessageDto msg, String parentUuid) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> map = chatMessageToMap(msg);
        if (parentUuid != null) {
            map.put("parentUuid", parentUuid);
        }
        msgs.add(map);
        try {
            AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, agentId, msgs);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] recordMessageTranscript 失败: {}", e.getMessage());
        }
    }

    /**
     * 记录单条 tool result transcript（per-message 录制）
     */
    private void recordToolResultTranscript(Path sessionDir, String sessionId, String agentId,
                                            ToolResult tr, String toolUseId, boolean isError) {
        Map<String, Object> trMap = new LinkedHashMap<>();
        trMap.put("role", "tool");
        trMap.put("toolCallId", toolUseId);
        trMap.put("content", tr.data() instanceof String s ? s : String.valueOf(tr.data()));
        trMap.put("isError", isError);
        try {
            AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, agentId, List.of(trMap));
        } catch (Exception e) {
            log.warn("[SubagentExecutor] recordToolResultTranscript 失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Finally cleanup helpers（对齐 CC runAgent.ts:816-859）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [IMP-SP2-08] 清理 agent tracking · 对齐 CC runAgent.ts:824-826
     * {@code if (feature('PROMPT_CACHE_BREAK_DETECTION')) cleanupAgentTracking(agentId)}
     * + promptCacheBreakDetection.ts:700-702 {@code previousStateBySource.delete(agentId)}。
     *
     * <p><b>feature 门控在调用点</b>（对齐 CC runAgent.ts:824）：{@code featureFlags != null &&
     * promptCacheBreakDetection()} 开启时才经 gatedBy 实例删除 PREVIOUS 对应 key；默认关
     * （OPD-SP-14，未注入/flag 关）→ no-op，零行为变化。cleanup 本身无 enabled 检查
     * （与 CC promptCacheBreakDetection.ts:700-702 一致 —— delete 不经 feature 检查）。
     *
     * <p>finally 调用点：{@link #executeStreaming} Step 21（:1490-1493 一带），正常/abort/error
     * 三路均执行（CC runAgent.ts:816 finally 块）。
     *
     * <p>package-private：同包测试直测接线（对齐 {@link #cleanupSessionHooks(UUID, UUID)} 可测约定）。
     *
     * @param agentId 子 agent UUID 字符串（null → no-op，独立操作不抛异常）
     */
    void cleanupAgentTracking(String agentId) {
        if (featureFlags != null && featureFlags.promptCacheBreakDetection()) {
            com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags)
                .cleanupAgentTracking(agentId);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] cleanupAgentTracking: 已清理 agent={} 的 cache-break tracking（CC runAgent.ts:824-826）", agentId);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] cleanupAgentTracking: noop（PROMPT_CACHE_BREAK_DETECTION 未启用）agent={}", agentId);
        }
    }
    /**
     * [P2-3 废弃] 注销 Perfetto agent · 对齐 CC runAgent.ts:840 unregisterPerfettoAgent.
     *
     * <p>Perfetto tracing 未集成, 保留方法签名 + @Deprecated 注释, finally 调用点已删除.
     * Phase 3 集成 Perfetto tracing 后真正接入并复活调用。
     */
    @Deprecated
    private void unregisterPerfettoAgent(String agentId) {
        // Perfetto tracing not integrated; noop.
        // CC: unregisterPerfettoAgent(agentId)
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] unregisterPerfettoAgent: noop agent={}", agentId);
        }
    }

    /**
     * 清理 agent 的 todos · 对齐 CC runAgent.ts:843-849 rootSetAppState todos cleanup
     *
     * <p>[S05] 存储介质迁移（OD-TDV1-1）：todo 桶存于会话级 appState（子 Agent 经继承父 TUC 的
     * setAppState 通道写入，createSubagentContext parent+overrides 路径），清理改经
     * {@link #parentToolUseContext} 的 setAppState 通道移除该 agentId 桶。
     * CC: rootSetAppState(prev => { const { [agentId]: _, ...todos } = prev.todos; return { ...prev, todos } })
     *
     * <p>无父会话 ToolUseContext（standalone 模式）时 noop（无会话 appState 可清，
     * 与迁移前 todoWriteTool 未注入 noop 行为等价）。
     */
    private void cleanupAgentTodos(String agentId) {
        if (parentToolUseContext == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] cleanupAgentTodos: 无父会话 ToolUseContext（standalone），noop agent={}", agentId);
            }
            return;
        }
        try {
            cleanupAgentTodosFromAppState(parentToolUseContext, agentId);
            log.info("[SubagentExecutor] cleanupAgentTodos: agent {} 的 todo 桶已从会话 appState 清理", agentId);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] cleanupAgentTodos 失败 (agent={}): {}", agentId, e.getMessage());
        }
    }

    /**
     * [S05] 从父会话 appState 移除 agent 的 todo 桶 · 对齐 CC runAgent.ts:843-849。
     *
     * <p>static seam（Pattern #14 RED-GREEN 双证先例，参照 executeSubagentStartHooks :1578 抽法）：
     * 供单测直接验证清理语义（桶移除/其他桶保留/无桶 no-op），不经 22 步 execute。
     *
     * <p>CC 语义：{@code rootSetAppState(prev => { const { [agentId]: _, ...todos } = prev.todos;
     * return { ...prev, todos } })} —— 键不存在时结果与 prev 同值（no-op 等价）。
     *
     * @param parentTuc 父会话 ToolUseContext（其 setAppState 通道接线到会话 appStateRef，
     *                  LlmAgentLoop.java:3542 区域）
     * @param agentId   子 Agent ID 字符串（桶键）
     */
    static void cleanupAgentTodosFromAppState(ToolUseContext parentTuc, String agentId) {
        if (parentTuc == null || agentId == null) {
            return;
        }
        parentTuc.setAppState().accept(prev -> {
            Object todosObj = prev.get("todos");
            if (!(todosObj instanceof Map<?, ?> todosMap)) {
                return prev; // 无 todos 桶 → no-op
            }
            if (!todosMap.containsKey(agentId)) {
                return prev; // 桶不存在 → no-op（对齐 CC 解构后同值）
            }
            Map<String, Object> next = new java.util.HashMap<>(prev);
            Map<String, Object> nextTodos = new java.util.HashMap<>();
            todosMap.forEach((k, v) -> {
                if (!agentId.equals(k)) {
                    nextTodos.put(String.valueOf(k), v);
                }
            });
            next.put("todos", nextTodos);
            return next;
        });
    }

    /**
     * 杀死 agent 的后台 shell 任务 · 对齐 CC runAgent.ts:851 killShellTasksForAgent
     *
     * <p>Phase 3: 真实化 — 委托 {@link BackgroundTaskRunner#killShellTasksForAgent}.
     * CC: killShellTasksForAgent(agentId, toolUseContext.getAppState, rootSetAppState)
     *
     * <p>未注入 BackgroundTaskRunner 时 noop (向后兼容测试环境).
     */
    private void killShellTasksForAgent(String agentId) {
        if (backgroundTaskRunner == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] killShellTasksForAgent: runner 未注入, noop agent={}", agentId);
            }
            return;
        }
        try {
            UUID agentUuid = safeParseUuid(agentId);
            int killed = backgroundTaskRunner.killShellTasksForAgent(agentUuid);
            log.info("[SubagentExecutor] killShellTasksForAgent: agent {} 终止 {} 个后台 shell 任务",
                agentId, killed);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] killShellTasksForAgent 失败 (agent={}): {}", agentId, e.getMessage());
        }
    }

    /**
     * 杀死 agent 的 monitor_mcp 后台任务 · 对齐 CC runAgent.ts:852-861
     * killMonitorMcpTasksForAgent（feature('MONITOR_TOOL') → MonitorMcpTask module；
     * runAgent 无条件 killShellTasksForAgent + MONITOR_TOOL 门控 killMonitorMcpTasksForAgent）。
     *
     * <p>OPD-TS-25：委托 {@link BackgroundTaskRunner#killMonitorMcpTasksForAgent}。
     * CC: killMonitorMcpTasksForAgent(agentId, toolUseContext.getAppState, rootSetAppState)
     *
     * <p>未注入 BackgroundTaskRunner 时 noop (向后兼容测试环境).
     */
    private void killMonitorMcpTasksForAgent(String agentId) {
        if (backgroundTaskRunner == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] killMonitorMcpTasksForAgent: runner 未注入, noop agent={}", agentId);
            }
            return;
        }
        try {
            UUID agentUuid = safeParseUuid(agentId);
            int killed = backgroundTaskRunner.killMonitorMcpTasksForAgent(agentUuid);
            log.info("[SubagentExecutor] killMonitorMcpTasksForAgent: agent {} 终止 {} 个 monitor 任务",
                agentId, killed);
        } catch (Exception e) {
            log.warn("[SubagentExecutor] killMonitorMcpTasksForAgent 失败 (agent={}): {}", agentId, e.getMessage());
        }
    }

    /** 安全解析 UUID (CC agentId 已是 UUID, 但 call site 用 String 传, 兼容非法输入). */
    private UUID safeParseUuid(String agentId) {
        if (agentId == null) return null;
        try {
            return UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] safeParseUuid: agentId={} 不是合法 UUID, 返回 null", agentId);
            }
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // userContext / systemContext 解析（对齐 CC runAgent.ts:380-410）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析 userContext · 对齐 CC resolvedUserContext
     *
     * <p>读取项目根目录的 CLAUDE.md 内容。
     *
     * <p>[IMP-D F4/M-12] 根 = 会话 projectRoot（修 M-12：原 {@code Paths.get("")} = JVM user.dir，
     * 会话绑 P≠user.dir 时子代理收不到 P/CLAUDE.md）。spawn 入口（IMP-D Step 20）/ 工具线程
     * （IMP-C 传播）注入后，本线程读 holder 即会话值；无会话上下文 → 回落 CLAUDE_PROJECT_DIR
     * env ?? config home（ODF-A1「绝不读 user.dir」约束）。
     */
    private String resolveUserContext() {
        try {
            Path projectRoot = Path.of(AutoMemPaths.currentSessionProjectRoot());
            Path claudeMd = projectRoot.resolve("CLAUDE.md");
            if (Files.isRegularFile(claudeMd)) {
                String content = Files.readString(claudeMd);
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] 已加载 userContext 自 {} ({} 字符)",
                            claudeMd, content.length());
                }
                return content;
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] 无法加载 userContext: {}", e.getMessage());
            }
        }
        return "";
    }

    /**
     * 解析 Agent 的 userContext，并遵守 omitClaudeMd 契约。
     *
     * <p>WHY: master 的 {@code SubagentExecutorOmitContextTest} 通过该 helper 验证契约；
     * rebase 后保留单一生产调用点，避免测试契约与 Step 8 内联逻辑再次漂移。
     */
    String userContextFor(AgentDefinition agentDefinition) {
        boolean omitClaudeMd = agentDefinition != null
                && agentDefinition.omitClaudeMd().orElse(false);
        return omitClaudeMd ? "" : resolveUserContext();
    }

    // ── systemContext 解析（DEL-SP-16 + IMP-G4 F5）──
    // 旧双 overload 解析（## Environment 块：Working Directory/OS/Java + git HEAD 原始 ref 读取）
    // 已整段删除 —— CC 子代理 systemContext 来自 memoized getSystemContext()
    // （context.ts:116-150，经 runAgent.ts:381-395），非自建 env 块，语义完全漂移。
    // [IMP-G4 F5] Step 8 调用点改接 SystemPromptContextProvider.getSystemContext()：
    //   注入 provider 用其 getSystemContext()（gitStatus/cacheBreaker），未注入时惰性构造
    //   会话级 provider（gitStatus 通道），保证子 agent systemContext 非空（CC runAgent.ts:380-383）。

    /**
     * [IMP-G4 F5] 解析子 agent systemContext 文本 · 对齐 CC runAgent.ts:380-383
     * {@code systemContext = getSystemContext()}（memoized）+ 渲染为 system 消息 content。
     *
     * <p>渲染格式对齐 {@link com.nexusai.application.agent.prompt.SystemPromptContextProvider#appendSystemContext}
     * （:304-322）：{@code "key: value"} 行，多键换行拼接，空串过滤。未注入 provider 时惰性构造
     * 会话级实例（per-executor 缓存 = per-spawn memoize 近似；CC 会话级 memoize 因 Java 无
     * 会话字段化承载而近似到 executor 实例边界）。
     *
     * @return systemContext 文本；无 gitStatus/cacheBreaker → 空串（CC 空 systemContext 等价，
     *         Step 10 非 fork path 仅当非空才注入 system 消息）
     */
    String resolveSystemContextText() {
        try {
            com.nexusai.application.agent.prompt.SystemPromptContextProvider provider =
                systemPromptContextProvider();
            if (provider == null) {
                return "";
            }
            Map<String, String> ctx = provider.getSystemContext();
            if (ctx == null || ctx.isEmpty()) {
                return "";
            }
            StringBuilder joined = new StringBuilder();
            int idx = 0;
            for (Map.Entry<String, String> e : ctx.entrySet()) {
                if (idx++ > 0) {
                    joined.append('\n');
                }
                joined.append(e.getKey()).append(": ").append(e.getValue());
            }
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-G4 F5] systemContext 渲染: keys={} 长度={} "
                    + "(CC runAgent.ts:380-383 getSystemContext)",
                    ctx.keySet(), joined.length());
            }
            return joined.toString();
        } catch (Exception e) {
            // fail-loud: 不静默吞异常 — systemContext 解析失败登记日志并回退空串（CC 无异常路径，
            // Java provider 可能因 git 命令环境异常抛错，回退不阻断子 agent 启动）
            log.warn("[SubagentExecutor] [IMP-G4 F5] systemContext 解析异常, 回退空串: {}", e.toString());
            return "";
        }
    }

    /**
     * [IMP-G4 F5] 会话级 SystemPromptContextProvider 惰性构造 · 未注入时新建（per-executor 缓存）。
     *
     * <p>构造参数对齐 LlmAgentLoop:2641-2644（sessionStartDate + UserContextProvider(claudemdEngine)
     * + GitStatusProvider）：getSystemContext() 仅消费 GitStatusProvider（gitStatus/cacheBreaker），
     * UserContextProvider(claudemdEngine=null) 为占位（回退单文件子集），sessionStartDate 用
     * 当前日期（getSystemContext 不消费 sessionStartDate）。
     *
     * @return 注入的 provider 或惰性构造的会话级 provider（null 仅在构造失败时）
     */
    private com.nexusai.application.agent.prompt.SystemPromptContextProvider systemPromptContextProvider() {
        com.nexusai.application.agent.prompt.SystemPromptContextProvider p = systemPromptContextProvider;
        if (p == null) {
            p = new com.nexusai.application.agent.prompt.SystemPromptContextProvider(
                java.time.LocalDate.now().toString(),
                new com.nexusai.application.agent.prompt.UserContextProvider(
                    (com.nexusai.application.agent.context.ClaudemdEngine) null),
                new com.nexusai.application.agent.prompt.GitStatusProvider());
            systemPromptContextProvider = p;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentExecutor] [IMP-G4 F5] 惰性构造 SystemPromptContextProvider "
                    + "(未注入, per-executor 缓存 · CC runAgent.ts:380-383)");
            }
        }
        return p;
    }

    /**
     * [IMP-G4 组11-1] Subagent hard_metrics 事件发射 · 对齐 CC {@code logEvent}
     * （AgentTool.tsx/agentToolUtils.ts）。未注入 analyticsTracker → no-op（不破坏既有调用）。
     *
     * <p>[IMP-T REWORK] 由 {@code track(EventName, Map)} 迁移为 {@code logEvent(CC事件名, Map)}
     * —— 原 track 忽略 metadata，hard_metrics 值域全部丢失；现按 CC 事件名发射并把 String 值经
     * {@link com.nexusai.application.agent.api.AnalyticsTracker#verified(String)} 包装
     * （CC AnalyticsMetadata_I_VERIFIED_THIS_IS_NOT_CODE_OR_FILEPATHS 标记等价；本批次各事件
     * String 值均为非 code/filepath 的枚举串：agent_type/model/reason/scope/last_request_id/source）。
     *
     * @param eventName 事件枚举（tengu_agent_* 映射见 {@link com.nexusai.application.agent.api.AnalyticsTracker.EventName}）
     * @param properties 事件属性 map（CC snake_case 键，经 CC logEvent properties 参数）
     */
    void emitAgentMetrics(com.nexusai.application.agent.api.AnalyticsTracker.EventName eventName,
                          Map<String, Object> properties) {
        if (analyticsTracker == null) {
            return;
        }
        String ccEventName = switch (eventName) {
            case AGENT_TOOL_SELECTED -> "tengu_agent_tool_selected";     // AgentTool.tsx:419-428
            case AGENT_TOOL_COMPLETED -> "tengu_agent_tool_completed";   // agentToolUtils.ts:322-346
            case AGENT_TOOL_TERMINATED -> "tengu_agent_tool_terminated"; // AgentTool.tsx:997/1132/1209
            // [IMP-F2-3] AGENT_MEMORY_LOADED 已改走真实 Telemetry 通道（emitAgentMemoryLoaded，
            //   SubagentExecutor.buildAgentSystemPrompt），不再经 AnalyticsTracker stub（DC-V5-09 删除）。
            case CACHE_EVICTION_HINT -> "tengu_cache_eviction_hint";     // agentToolUtils.ts:349-357
            default -> null;
        };
        if (ccEventName == null) {
            log.warn("[SubagentExecutor] emitAgentMetrics 未知事件枚举={}（跳过：无 CC 事件名映射）",
                eventName);
            return;
        }
        Map<String, Object> verifiedProps = new LinkedHashMap<>(properties.size());
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Object v = e.getValue();
            verifiedProps.put(e.getKey(),
                v instanceof String s
                    ? com.nexusai.application.agent.api.AnalyticsTracker.verified(s)
                    : v);
        }
        analyticsTracker.logEvent(ccEventName, verifiedProps);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] [IMP-G4] 发射 hard_metrics: event={} ccEvent={} props={}",
                eventName, ccEventName, properties);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Message conversion utilities
    // ════════════════════════════════════════════════════════════════════════

    // ────────────────────────────────────────────────────────────────────────────
    // Fork 消息转换 (buildForkedMessages 产物 → initialMessages Map 格式)
    // 对齐 CC AgentTool.tsx:512 fork path: promptMessages = buildForkedMessages(...)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * 把 {@link ForkSubagentMessages#buildForkedMessages} 的返回消息列表转换为
     * {@code initialMessages} 的 Map 格式 (role + content)。
     *
     * <p><b>WHY 不复用 {@link #convertToChatMessageDto}</b>: 现有转换只提取 content 数组的
     * text 块, fork assistant 消息的 tool_use blocks 会被丢弃 — 破坏 CC "保留全部 content
     * blocks" 契约 (forkSubagent.ts:113-120 克隆保留 thinking/text/tool_use)。
     * 本转换保留 blocks 数组原样 (tool_use / tool_result / text 结构), 与 CC
     * {@code createUserMessage({content: [...]})} 序列化形态一致。
     *
     * @param forkMessages buildForkedMessages 返回值 — 2 条 [cloned assistant, user]
     *                     或边界 1 条 [user] (无 tool_use 时, CC forkSubagent.ts:127-139)
     * @return 与 initialMessages 同构的 List&lt;Map&gt; (role=assistant|user, content=blocks 数组)
     */
    private List<Map<String, Object>> forkMessagesToInitialMessages(
            List<ForkSubagentMessages.Message> forkMessages) {
        List<Map<String, Object>> result = new ArrayList<>(forkMessages.size());
        for (ForkSubagentMessages.Message message : forkMessages) {
            if (message instanceof ForkSubagentMessages.AssistantMessage assistant) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                List<Map<String, Object>> blocks = forkContentBlocksToMaps(assistant.content());
                msg.put("content", blocks);
                result.add(msg);
                if (log.isDebugEnabled()) {
                    long toolUseCount = blocks.stream()
                        .filter(b -> "tool_use".equals(b.get("type")))
                        .count();
                    log.debug("[SubagentExecutor] fork path: assistant 消息转换保留全部 {} blocks, "
                            + "tool_use={} (对齐 CC '保留全部 content blocks' 契约)",
                        blocks.size(), toolUseCount);
                }
            } else if (message instanceof ForkSubagentMessages.UserMessage user) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "user");
                List<Map<String, Object>> blocks = forkContentBlocksToMaps(user.content());
                msg.put("content", blocks);
                result.add(msg);
                if (log.isDebugEnabled()) {
                    long toolResultCount = blocks.stream()
                        .filter(b -> "tool_result".equals(b.get("type")))
                        .count();
                    long placeholderConsistent = blocks.stream()
                        .filter(b -> "tool_result".equals(b.get("type")))
                        .filter(b -> {
                            Object content = b.get("content");
                            if (!(content instanceof List<?> inner)) return false;
                            return inner.stream().anyMatch(item ->
                                item instanceof Map<?, ?> block
                                    && "text".equals(block.get("type"))
                                    && ForkSubagentMessages.FORK_PLACEHOLDER_RESULT.equals(block.get("text")));
                        })
                        .count();
                    // directive 文本 = user 消息最后一个 text block (buildChildMessage 产物,
                    //   尾部 = FORK_DIRECTIVE_PREFIX + directive, CC forkSubagent.ts:158-166/197)
                    String directiveTail = blocks.stream()
                        .filter(b -> "text".equals(b.get("type")))
                        .reduce((first, second) -> second)
                        .map(b -> String.valueOf(b.get("text")))
                        .orElse("");
                    if (directiveTail.length() > 100) {
                        directiveTail = directiveTail.substring(directiveTail.length() - 100);
                    }
                    log.debug("[SubagentExecutor] fork path: user 消息转换 blocks={}, tool_result={}, "
                            + "placeholder 一致={}/{} (FORK_PLACEHOLDER_RESULT='{}' 缓存共享约束), "
                            + "directive 尾部='{}'",
                        blocks.size(), toolResultCount, placeholderConsistent, toolResultCount,
                        ForkSubagentMessages.FORK_PLACEHOLDER_RESULT, directiveTail);
                }
            }
        }
        return result;
    }

    /**
     * fork 消息的 content blocks → initialMessages 的 blocks Map 数组。
     * 对齐 CC forkSubagent.ts:113-120 + 142-166 的 block 结构:
     * tool_use(id/name/input) / tool_result(tool_use_id/content) / text(text) 全部原样保留。
     */
    private List<Map<String, Object>> forkContentBlocksToMaps(
            List<ForkSubagentMessages.ContentBlock> contentBlocks) {
        List<Map<String, Object>> blocks = new ArrayList<>(contentBlocks.size());
        for (ForkSubagentMessages.ContentBlock block : contentBlocks) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (block instanceof ForkSubagentMessages.BetaToolUseBlock toolUse) {
                map.put("type", "tool_use");
                map.put("id", toolUse.id());
                map.put("name", toolUse.name());
                map.put("input", toolUse.input());
            } else if (block instanceof ForkSubagentMessages.BetaToolResultBlock toolResult) {
                map.put("type", "tool_result");
                map.put("tool_use_id", toolResult.toolUseId());
                List<Map<String, Object>> innerBlocks = new ArrayList<>(toolResult.content().size());
                for (ForkSubagentMessages.BetaTextBlock textBlock : toolResult.content()) {
                    Map<String, Object> inner = new LinkedHashMap<>();
                    inner.put("type", "text");
                    inner.put("text", textBlock.text());
                    innerBlocks.add(inner);
                }
                map.put("content", innerBlocks);
            } else if (block instanceof ForkSubagentMessages.BetaTextBlock text) {
                map.put("type", "text");
                map.put("text", text.text());
            }
            blocks.add(map);
        }
        return blocks;
    }

    /**
     * 统计 initialMessages (fork 前缀) 中的 tool_use block 总数 — 供关键分支日志使用。
     */
    private static int countToolUseBlocks(List<Map<String, Object>> forkInitialMessages) {
        int count = 0;
        for (Map<String, Object> msg : forkInitialMessages) {
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> b && "tool_use".equals(b.get("type"))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private ChatMessageDto convertToChatMessageDto(Map<String, Object> raw, String sessionId) {
        String roleStr = Objects.toString(raw.get("role"), "user");
        Role role;
        try {
            role = Role.valueOf(roleStr.toLowerCase());
        } catch (IllegalArgumentException e) {
            role = Role.user;
        }
        String content = Objects.toString(raw.get("content"), "");

        // 处理 content 为数组的情况（如 hook context message）
        if (raw.get("content") instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) raw.get("content")) {
                if (item instanceof Map) {
                    Map<String, Object> block = (Map<String, Object>) item;
                    if ("text".equals(block.get("type")) && block.get("text") != null) {
                        sb.append(block.get("text")).append("\n");
                    }
                }
            }
            content = sb.toString().trim();
        }

        // [S3] fork 前缀 tool 消息: toolCallId 透传 (对齐 CC tool_result.tool_use_id) → AnthropicSdkProvider
        //   序列化为 {type:'tool_result', tool_use_id}. role=tool 但无 toolCallId → 无法配对, 丢弃
        //   (fail loud, 避免残缺 tool_result 进 provider).
        String toolCallId = raw.get("toolCallId") != null ? Objects.toString(raw.get("toolCallId"), null) : null;
        if (role == Role.tool) {
            if (toolCallId == null || toolCallId.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentExecutor] convertToChatMessageDto: role=tool 无 toolCallId, 丢弃该消息");
                }
                return null;
            }
            boolean isError = raw.get("isError") instanceof Boolean b && b;
            return new ChatMessageDto(
                    UUID.randomUUID().toString(), sessionId, Role.tool, null,
                    content, null, null, null, null, null, null, OffsetDateTime.now(),
                    toolCallId, null, null, List.of(), List.of(), null, false, isError);
        }

        // [S3] fork 前缀 assistant 消息: toolCalls 透传 (对齐 CC assistant tool_use 块) →
        //   AnthropicSdkProvider 序列化为 tool_use block. 空 toolCalls → 普通 assistant 消息.
        List<ToolCallDto> toolCalls = null;
        if (raw.get("toolCalls") instanceof List) {
            toolCalls = new ArrayList<>();
            for (Object tco : (List<Object>) raw.get("toolCalls")) {
                if (tco instanceof Map) {
                    Map<String, Object> tcm = (Map<String, Object>) tco;
                    toolCalls.add(new ToolCallDto(
                            Objects.toString(tcm.get("id"), null),
                            Objects.toString(tcm.get("name"), null),
                            Objects.toString(tcm.get("arguments"), "{}"),
                            null, null));
                }
            }
            if (toolCalls.isEmpty()) toolCalls = null;
        }
        if (role == Role.assistant && toolCalls != null && !toolCalls.isEmpty()) {
            return new ChatMessageDto(
                    UUID.randomUUID().toString(), sessionId.toString(), Role.assistant, null,
                    content, null, toolCalls, FinishReason.tool_calls,
                    null, null, null, OffsetDateTime.now(), null, null,
                    null, List.of(), List.of(), null, false, false);
        }

        return buildChatMessage(sessionId.toString(), role, content, null);
    }

    private ChatMessageDto buildChatMessage(String sessionId, Role role, String content, String reasoning) {
        return new ChatMessageDto(
                UUID.randomUUID().toString(),
                sessionId,
                role,
                null,       // author
                content,
                reasoning,
                null,       // toolCalls
                null,       // finishReason
                null,       // inputTokens
                null,       // outputTokens
                null,       // time
                OffsetDateTime.now(),
                null,       // toolCallId
                null,        // assistantMessageId
                null,       // R32-b9 acceptFeedback
                java.util.List.of(),  // R32-b9 contentBlocks
                java.util.List.of()   // R32-b9 imagePasteIds
        );
    }

    private ChatMessageDto buildAssistantWithToolCalls(
            String sessionId, String content, String reasoning, List<ToolUseBlock> toolCalls) {
        List<ToolCallDto> toolCallDtos = new ArrayList<>();
        for (ToolUseBlock block : toolCalls) {
            String argsJson;
            try {
                argsJson = JSON.writeValueAsString(block.input());
            } catch (Exception e) {
                argsJson = "{}";
            }
            toolCallDtos.add(new ToolCallDto(block.id(), block.name(), argsJson, null, null));
        }
        return new ChatMessageDto(
                UUID.randomUUID().toString(),
                sessionId.toString(),
                Role.assistant,
                null,       // author
                content,
                reasoning,
                toolCallDtos.isEmpty() ? null : toolCallDtos,
                toolCallDtos.isEmpty() ? null : FinishReason.tool_calls,
                null, null, null, OffsetDateTime.now(), null, null,
                null,                          // R32-b9 acceptFeedback
                java.util.List.of(),           // R32-b9 contentBlocks
                java.util.List.of()            // R32-b9 imagePasteIds
        );
    }

    /**
     * [对抗核验 H13-GAP v3] 构建 tool-role 消息 · isError 从 ToolResult.isError 透传。
     *
     * <p>WHY (对抗核验登记 H13-GAP-7 遗留): ChatMessageDto.isError（CC tool_result.is_error,
     * messages.ts:4754）已由 LlmAgentLoop.toolResultMessage 透传（主路径经 queryLoop 覆盖）；
     * 但 SubagentExecutor 自有同名方法（本方法）旧实现走 17 参兼容构造器 → isError 恒 false。
     * 子 Agent 专用 tool-role 消息若不经 queryLoop 主路径（独立构造场景），错误标志会丢失,
     * StructuredOutputEnforcementHook.hasSuccessfulToolCall 会误判失败工具为成功。
     * package-private 供同包测试验证（对齐 cleanupSessionHooks 既有约定）。
     *
     * <p><b>WF2-R4（返工）</b>: 载荷改走 per-tool {@link Tool#mapToToolResultBlockParam}
     * 构造 tool_result 块（对齐 CC {@code toolExecution.ts:1292}
     * {@code tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)} —— 主/子循环共享 mapper）。
     * 旧实现直走 {@link ToolResult#renderToolResultPayloadText} 旁路，ReadFileTool 等 per-tool
     * override 在 mapper 序列化层拼行号前缀（CC {@code FileReadTool.ts:692-715} text case
     * {@code freshness + formatFileLines→addLineNumbers + reminder}），旁路直拼会丢行号（raw）。
     * tool 为 null / mapper 返回 null / 块 content 非字符串时回退默认渲染器
     * （与 {@link LlmAgentLoop#toolResultMessage} 同一回退契约）。
     *
     * @param sessionId 会话 ID
     * @param result    工具执行结果（data 为 raw content + structuredOutput 呈现元数据）
     * @param tool      产生该结果的 Tool 实例（CC original: toolExecution.ts:1292 的 tool；
     *                  synthetic error / 未知工具路径传 null → 回退默认渲染器）
     */
    ChatMessageDto toolResultMessage(String sessionId, ToolResult result, Tool tool) {
        // [IMP-C2] ToolResult 4 字段契约：toolUseId/isError 由 mapper 参数推导（组 2-1 拍板）。
        //   本方法无生产调用方（仅定义），toolUseId/isError 传 null/false（调用方接入时透传）。
        return toolResultMessage(sessionId, result, tool, null, false);
    }

    ChatMessageDto toolResultMessage(String sessionId, ToolResult result, Tool tool,
                                     String toolUseId, boolean isError) {
        // [WF2-R4] per-tool mapper 构造 tool_result 块（对齐 CC toolExecution.ts:1292）
        ToolResultBlockParam block = (tool != null)
            ? tool.mapToToolResultBlockParam(result, toolUseId, isError) : null;
        String payload = (block != null && block.content() instanceof String s)
            ? s
            : ToolResult.renderToolResultPayloadText(result);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentExecutor] toolResultMessage 载荷来源: toolUseId={} tool={} mapper={} payloadLen={}（CC toolExecution.ts:1292 主/子共享 mapper）",
                toolUseId,
                tool != null ? tool.name() : "null",
                block != null ? "命中" : "回退默认渲染器",
                payload.length());
        }
        return new ChatMessageDto(
                UUID.randomUUID().toString(),
                sessionId.toString(),
                Role.tool,
                null,
                payload,
                null,
                null,
                null,
                null, null,
                null, OffsetDateTime.now(),
                toolUseId, null,
                null,                          // R32-b9 acceptFeedback
                java.util.List.of(),           // R32-b9 contentBlocks
                java.util.List.of(),           // R32-b9 imagePasteIds
                null,                          // structuredOutput
                false,                         // isMeta
                isError                        // isError · CC tool_result.is_error (messages.ts:4754)
        );
    }

    private static Map<String, Object> chatMessageToMap(ChatMessageDto msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        // [S4-1 差异项 4] 保留消息创建时 uuid · CC 每条 message 自带 uuid (runAgent.ts:745);
        //   transcript 录制时 AgentTranscript.enrichTranscriptMessage 对无 uuid 的消息随机生成
        //   → parent chain 断裂. msg.id() 即该消息创建时 uuid (toMessage/convertToChatMessageDto
        //   均 UUID.randomUUID), 显式写入 map 供录制保留. fork 父对话消息经
        //   convertToChatMessageDto 重建后 id 为新建随机 uuid — 父前缀 uuid 保留由
        //   assignInitialMessageUuids 幂等键承担 (已有 uuid 不覆盖).
        if (msg.id() != null) {
            map.put("uuid", msg.id());
        }
        map.put("role", msg.role().name().toLowerCase());
        map.put("content", msg.content());
        if (msg.reasoning() != null) map.put("reasoning", msg.reasoning());
        if (msg.toolCalls() != null) {
            List<Map<String, Object>> tcs = new ArrayList<>();
            for (ToolCallDto tc : msg.toolCalls()) {
                Map<String, Object> tcMap = new LinkedHashMap<>();
                tcMap.put("id", tc.id());
                tcMap.put("name", tc.name());
                tcMap.put("arguments", tc.arguments());
                tcs.add(tcMap);
            }
            map.put("toolCalls", tcs);
        }
        // [S3] fork 前缀 tool 消息: 透传 toolCallId + isError (对齐 CC tool_result.tool_use_id /
        //   messages.ts:4754 is_error), 供 convertToChatMessageDto 还原 → provider 序列化.
        //   WHY: 不透传则 fork child 看到的父 tool_result 缺 tool_use_id, AnthropicSdkProvider 直接跳过
        //   (子 agent 上下文残缺) 且 StructuredOutputEnforcementHook 误判失败工具.
        if (msg.role() == Role.tool) {
            if (msg.toolCallId() != null) {
                map.put("toolCallId", msg.toolCallId());
            }
            map.put("isError", msg.isError());
        }
        return map;
    }
}

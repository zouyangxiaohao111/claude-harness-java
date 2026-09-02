package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactEnvProperties;
import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.ToolUseSummaryGenerator;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * [H7-arch Phase 5-2 P3 测试同步] 最小 AgentLoopContext 构造 helper。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9): loop 单测驱动 queryLoop 需要 AgentLoopContext，但手工
 * {@code new AgentLoopContext(..., null)} 35+ 参数冗长且 18 个轻方法 static 化后 Mockito 无法再
 * stub runtime 上的 {@code computeBudgetFromGates / estimateMessagesTokens / getModelForCall}
 * 等——必须经 ctx 注入 bean（TokenBudgetBeans / deps.resolveModel）驱动真实静态逻辑。本 helper
 * 收敛两处重复：
 * <ol>
 *   <li>{@link #agentLoopContext(Behaviors, LlmProviderFactory, QueryConfig, ToolUseSummaryGenerator, AgentLoopContext.TokenBudgetBeans)}
 *       — 构造最小 ctx（toolRegistry/hookRegistry 等基础设施为 null，D5 新组件按需注入，
 *       sessionState 由 compact ctor 兜底新建）。</li>
 *   <li>{@link #tokenBudgetBeans(int, int)} — 构造 TokenBudgetBeans mock（TokenEstimator 固定
 *       tokenUsage / ModelMapper+ProviderMapper 固定 contextWindow），供 blocking-limit 等
 *       依赖精确预算数值的测试复用。</li>
 * </ol>
 */
public final class TestContexts {

    private TestContexts() {}

    /**
     * 最小 AgentLoopContext · sessionState 经 compact ctor 兜底新建（null → 等价 per-run 全新状态）。
     *
     * <p>P3-⑤ 后重方法已 static 化（不再走 Behaviors 委托 mock runtime）：executor 构建由
     * per-turn TUC 的 {@code availableTools()} 驱动（空工具 → buildStreamingExecutor 返回 null）。
     * 需要真实 executor 的测试（StreamingFallbackTombstoneTest / ModelFallbackTest）需在
     * QueryParams.toolUseContext 注入含 dummy tool 的 base TUC。
     *
     * @param toolRegistry      ToolRegistry（位置 1，原 34 组件；buildPromptContext/applyToolResultBudget 等读）
     * @param factory           LlmProviderFactory（位置 11，原 34 组件）
     * @param queryConfig       QueryConfig（位置 10；null = gates 走默认值）
     * @param generator         ToolUseSummaryGenerator（位置 25；null = 未注入）
     * @param tokenBudgetBeans  TokenBudgetBeans（D5 新增；null = estimate 走 chars/4 兜底、budget 走 200K 兜底）
     */
    public static AgentLoopContext agentLoopContext(
            ToolRegistry toolRegistry,
            LlmProviderFactory factory,
            QueryConfig queryConfig,
            ToolUseSummaryGenerator generator,
            AgentLoopContext.TokenBudgetBeans tokenBudgetBeans) {
        // [IMP2-06] 显式转型消除重载歧义：7 参重载现有两个候选
        // （(FeatureFlags, EventBridge) 新增 vs (ToolExecutionBeans, HookRegistry) 既有），
        // null,null 必须锁定既有 7 参（ToolExecutionBeans 版）。
        return agentLoopContext(toolRegistry, factory, queryConfig, generator, tokenBudgetBeans,
            (AgentLoopContext.ToolExecutionBeans) null,
            (com.nexusai.application.agent.permission.hook.HookRegistry) null);
    }

    /**
     * [S3] 6 参重载：额外注入 FeatureFlags（historySnip 等 feature-gated 门控测试用 · OD-01 S3-B1/B4）。
     * 位置与 5/7 参重载同构（featureFlags 在 AgentLoopContext 位置 20），仅 flags 与默认值不同。
     *
     * @param flags  feature flags（null → ALL_DISABLED，对齐生产默认全关）
     */
    public static AgentLoopContext agentLoopContext(
            ToolRegistry toolRegistry,
            LlmProviderFactory factory,
            QueryConfig queryConfig,
            ToolUseSummaryGenerator generator,
            AgentLoopContext.TokenBudgetBeans tokenBudgetBeans,
            FeatureFlags flags) {
        return new AgentLoopContext(
            toolRegistry, null, null, null, null, null, null, null, null,
            queryConfig, factory, null, null, null, null, null, null, null, null,
            flags != null ? flags : FeatureFlags.ALL_DISABLED, null, null, null, null, null,
            null, tokenBudgetBeans, null, null, null, null, null);
    }

    /**
     * [IMP2-06] 8 参重载：额外注入 EventBridge（snip boundary yield 事件通道测试用 ·
     * CC query.ts:406-408 yield → publishEvent → Spring 监听方 + runStream 流）。
     * 位置与 6 参重载同构（eventBridge 在 AgentLoopContext 位置 29），仅 bridge 与默认值不同。
     *
     * @param flags  feature flags（null → ALL_DISABLED）
     * @param bridge 事件桥（publisher 非 null 时 publishEvent 双通道生效；
     *               null → 事件被 publishEvent 早退丢弃，等价无监听方场景）
     */
    public static AgentLoopContext agentLoopContext(
            ToolRegistry toolRegistry,
            LlmProviderFactory factory,
            QueryConfig queryConfig,
            ToolUseSummaryGenerator generator,
            AgentLoopContext.TokenBudgetBeans tokenBudgetBeans,
            FeatureFlags flags,
            AgentLoopContext.EventBridge bridge) {
        return new AgentLoopContext(
            toolRegistry, null, null, null, null, null, null, null, null,   // 1-9
            queryConfig, factory, null, null, null, null, null, null, null, null,  // 10-19
            flags != null ? flags : FeatureFlags.ALL_DISABLED, null, null, null, null, null,  // 20-25
            null, tokenBudgetBeans, bridge, null, null, null, null);       // 26-32
    }

    /**
     * 7 参重载：额外注入 ToolExecutionBeans + hookRegistry（PermissionGateInjectionTest 反射驱动真实
     * buildStreamingExecutor 验证 gate/hook 注入用；其余调用方走 5 参）。
     */
    public static AgentLoopContext agentLoopContext(
            ToolRegistry toolRegistry,
            LlmProviderFactory factory,
            QueryConfig queryConfig,
            ToolUseSummaryGenerator generator,
            AgentLoopContext.TokenBudgetBeans tokenBudgetBeans,
            AgentLoopContext.ToolExecutionBeans toolExecutionBeans,
            com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry) {
        return new AgentLoopContext(
            toolRegistry, hookRegistry, null, null, null, null,
            null, null, null, queryConfig, factory, null, null, null, null,
            null, null, null, null,
            FeatureFlags.ALL_DISABLED, null, null, null, null, generator,
            // D5 新增 4 组件 + promptSuggestion + sessionState（null → compact ctor 兜底）
            toolExecutionBeans, tokenBudgetBeans, null, null, null, null, null);
    }

    /**
     * [H7-arch Phase 5-2 P3-⑤] 最小 dummy Tool · 供需要真实 buildStreamingExecutor 的测试
     * （StreamingFallbackTombstoneTest / ModelFallbackTest）在 base TUC 注入非空 availableTools。
     *
     * @param name 工具名（LLM 可调 / dispatch 可命中）
     */
    public static com.nexusai.application.agent.tool.Tool dummyTool(String name) {
        com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        return new com.nexusai.application.agent.tool.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "dummy " + name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return json.createObjectNode();
            }
            @Override public com.nexusai.application.agent.tool.AgentToolResult execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(
                    call.id(), "dummy: " + name);
            }
            @Override public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };
    }

    /**
     * 固定预算数值的 TokenBudgetBeans mock：
     * <ul>
     *   <li>{@code tokenEstimator.estimateMessageTokens(any)} → {@code tokenUsage}</li>
     *   <li>{@code modelMapper.selectOneByQuery / selectListByQuery(any)} → ModelRecord(providerId=p1,
     *       maxContextTokens=contextWindow) —— W2-1 模型级窗口（models.max_context_tokens）</li>
     *   <li>{@code providerMapper.selectOneById(p1) / selectOneByQuery(any)} → ProviderRecord（仅全名解析用）</li>
     * </ul>
     * 使 static {@code AgentLoopContext.estimateMessagesTokens / computeBudgetFromGates} 返回精确值
     * （对齐原测试经 Behaviors mock runtime 注入 MOCK_TOKEN_USAGE / MOCK_CONTEXT_WINDOW 的意图）。
     */
    public static AgentLoopContext.TokenBudgetBeans tokenBudgetBeans(int contextWindow, int tokenUsage) {
        TokenEstimator te = Mockito.mock(TokenEstimator.class);
        // IMP-17 后 blocking 预检测量走 tokenCountWithEstimation（CC query.ts:637 usage-walk），
        // mock 固定返回 tokenUsage 驱动「估算超窗 → 拦截」意图。
        when(te.tokenCountWithEstimation(anyList())).thenReturn(tokenUsage);
        ModelMapper modelMapper = Mockito.mock(ModelMapper.class);
        ModelRecord model = new ModelRecord();
        model.setProviderId("p1");
        // W2-1: 窗口源从 provider 级迁到模型级（运行时改读 models.max_context_tokens）
        model.setMaxContextTokens(contextWindow);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
        // W1-2 ModelNameResolver 兼容路径（裸名查询）走 selectListByQuery —— 必须 stub 否则 resolve 回落默认
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));
        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        when(providerMapper.selectOneById("p1")).thenReturn(provider);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        return new AgentLoopContext.TokenBudgetBeans(te, modelMapper, providerMapper);
    }
}

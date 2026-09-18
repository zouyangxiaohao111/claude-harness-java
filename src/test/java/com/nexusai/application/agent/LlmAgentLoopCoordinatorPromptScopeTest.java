package com.nexusai.application.agent;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 提示词作用域] coordinator 系统提示<b>只许进主线程</b>，不得泄漏到子代理 / hook agent。
 *
 * <p><b>CC 真源（实读）</b>：coordinator 分支位于
 * {@code buildEffectiveSystemPrompt}（{@code claude-code-best/src/utils/systemPrompt.ts:62-75}），
 * 该函数<b>只被主线程路径调用</b>；子代理走的是
 * {@code runAgent.ts:517-527 → getAgentSystemPrompt(agentDefinition, ...)}（{@code :892-916}），
 * <b>不经过</b> {@code buildEffectiveSystemPrompt} ⇒ coordinator 提示对子代理结构性不可达。
 *
 * <p><b>本类守护什么</b>：nexusai 把三条路径（主线程 {@code LlmAgentLoop.doRun} / 所有子代理
 * {@code SubagentExecutor.runSubagentQueryLoop} / stop-hook 校验 agent {@code ExecAgentHook}）
 * 都收敛到同一个 {@code LlmAgentLoop.collectRunMaterial}。若 builder 的 coordinator 分支只看
 * 「coordinator 门」，子代理就会被 coordinator 提示<b>整体替换</b>自己的 agent 提示与 agent-memory
 * （coordinator 分支在 custom 之前早退）—— worker 会误以为自己是协调者。
 *
 * <p><b>判别信号</b>：{@code QuerySource}（顶层循环 = {@code USER} / {@code REPL_MAIN_THREAD}；
 * 子代理 = {@code SUBAGENT}/{@code FORK}/{@code WORKFLOW}；hook agent = {@code HOOK_AGENT}）——
 * 与 {@code LlmAgentLoop.sessionVisibleToolsBase} 的 coordinator 工具池裁剪同判据（单点
 * {@code isTopLevelLoopSource}）。<b>⛔ 不是</b> {@code ToolUseContext.agentId()}：主会话后台化
 * （{@code MainSessionBackgroundService:406}）是「主线程且 agentId 非 null」，
 * 用 agentId 判会误关后台化主会话的 coordinator 提示（本类有专门用例钉死该变体）。
 * 夹具仍按生产姿态设置 agentId（{@code LlmAgentLoop:10023} 主线程 TUC 取
 * {@code state.agentId()}），以便上述变体用例可判别。
 *
 * <p><b>反向对照</b>：主线程用例断言 coordinator 提示<b>必须</b>出现 —— 防「一刀切关掉分支」式假绿。
 */
@DisplayName("[coordinator 作用域] coordinator 提示只进主线程：子代理 / hook agent 必须拿到自己的 agent 提示")
class LlmAgentLoopCoordinatorPromptScopeTest {

    // 夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（否则 CwdResolution fail-loud 抛）。
    @BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    @TempDir
    Path configHome;

    @BeforeEach
    void setUp() {
        // 隔离 nexusai 自有根（防污染真实 ~/.nexusai）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        // coordinator 门：feature + env 双真（生产 coordinator 部署姿态）
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1"));
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        LlmAgentLoop.setCoordinatorMode(null);
    }

    // ── 夹具 ──

    /** 组装出的系统提示全文（{@code collectRunMaterial} 回灌的 systemPrompt 段数组）。 */
    private String assembledSystemPrompt(AgentState state, ToolUseContext tuc, QuerySource querySource) {
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), List.of(), tuc, querySource, "test-model",
            null, null, null, null, null, depsOf(ctx), ProviderConfig.empty());
        return String.join("\n", LlmAgentLoop.collectRunMaterial(ctx, params, state).systemPrompt());
    }

    private static LoopDeps depsOf(AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
            @Override public String uuid() { return "coordinator-scope-test"; }
        };
    }

    // ════════════════════════════════════════════════════════════════
    // 主线程：coordinator 提示必须出现（正向对照）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主线程（agentId null）→ coordinator 提示注入（CC systemPrompt.ts:62-75 主线程路径生效）")
    void mainThread_getsCoordinatorPrompt() {
        AgentState state = new AgentState("MAIN-CUSTOM-PROMPT", "sess-main", null);
        ToolUseContext mainTuc = ToolUseContext.of(null, "sess-main", PermissionMode.DEFAULT, List.of());

        String prompt = assembledSystemPrompt(state, mainTuc, QuerySource.REPL_MAIN_THREAD);

        assertThat(prompt)
            .as("主线程必须拿到 coordinator 提示（防「关掉整条分支」式假绿）")
            .contains(CoordinatorMode.getCoordinatorSystemPrompt());
    }

    @Test
    @DisplayName("主会话后台化（agentId=taskId 非 null，但 querySource 仍 REPL_MAIN_THREAD）→ 仍必须注入 coordinator 提示")
    void backgroundedMainSession_withNonNullAgentId_stillGetsCoordinatorPrompt() {
        // WHY: 「agentId 非 null」≠「子代理」—— MainSessionBackgroundService:406 以
        //   RunRequest.session(userPrompt, sessionUuid, agentUuid /* =taskId */, ...) 后台化跑**主会话**
        //   （querySource 仍 REPL_MAIN_THREAD：RunRequest.session 工厂 :323）。CC 侧的等价路径仍走
        //   QueryEngine → buildEffectiveSystemPrompt（CC 的 coordinator 门只看 feature/env +
        //   mainThreadAgentDefinition，**不含** agentId）⇒ 后台化主会话照常注入 coordinator 提示。
        //   变异点：判据改用 ToolUseContext.agentId()（非 null 即当子代理）→ 本用例红。
        UUID taskAgentId = UUID.randomUUID();
        AgentState state = new AgentState("BG-MAIN-CUSTOM-PROMPT", "sess-bg-main", taskAgentId);
        ToolUseContext bgTuc = ToolUseContext.of(taskAgentId, "sess-bg-main", PermissionMode.DEFAULT, List.of());

        String prompt = assembledSystemPrompt(state, bgTuc, QuerySource.REPL_MAIN_THREAD);

        assertThat(prompt)
            .as("后台化主会话的顶层判据是 querySource，不是 agentId ⇒ coordinator 提示必须仍在")
            .contains(CoordinatorMode.getCoordinatorSystemPrompt());
    }

    // ════════════════════════════════════════════════════════════════
    // 子代理：coordinator 提示必须缺席（缺陷主场景）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("子代理（agentId 非 null，SubagentExecutor 路径）→ 不得拿到 coordinator 提示，自己的 agent 提示必须保留")
    void subagent_doesNotGetCoordinatorPrompt() {
        UUID subAgentId = UUID.randomUUID();
        AgentState state = new AgentState("WORKER-AGENT-PROMPT", "sess-sub", subAgentId);
        ToolUseContext subTuc = ToolUseContext.of(subAgentId, "sess-sub", PermissionMode.DEFAULT, List.of());

        String prompt = assembledSystemPrompt(state, subTuc, QuerySource.SUBAGENT);

        assertThat(prompt)
            .as("子代理不得被 coordinator 提示整体替换（CC runAgent.ts:517-527 走 getAgentSystemPrompt，不铺 coordinator）")
            .doesNotContain(CoordinatorMode.getCoordinatorSystemPrompt());
        assertThat(prompt)
            .as("子代理自己的 agent 提示必须保留（coordinator 分支早退会把 custom 一起吃掉）")
            .contains("WORKER-AGENT-PROMPT");
    }

    @Test
    @DisplayName("fork 子代理（QuerySource.FORK，agentId 非 null）→ 同子代理语义，不得拿到 coordinator 提示")
    void forkSubagent_doesNotGetCoordinatorPrompt() {
        UUID forkAgentId = UUID.randomUUID();
        AgentState state = new AgentState("FORK-AGENT-PROMPT", "sess-fork", forkAgentId);
        ToolUseContext forkTuc = ToolUseContext.of(forkAgentId, "sess-fork", PermissionMode.DEFAULT, List.of());

        String prompt = assembledSystemPrompt(state, forkTuc, QuerySource.FORK);

        assertThat(prompt)
            .as("fork 子代理同样走 runAgent 提示装配路径，不得被 coordinator 替换")
            .doesNotContain(CoordinatorMode.getCoordinatorSystemPrompt());
        assertThat(prompt).contains("FORK-AGENT-PROMPT");
    }

    @Test
    @DisplayName("hook agent（ExecAgentHook 路径，agentId 非 null）→ 不得拿到 coordinator 提示（CC 用硬编码校验提示）")
    void hookAgent_doesNotGetCoordinatorPrompt() {
        UUID hookAgentId = UUID.randomUUID();
        AgentState state = new AgentState("HOOK-AGENT-PROMPT", "sess-hook", hookAgentId);
        ToolUseContext hookTuc = ToolUseContext.of(hookAgentId, "sess-hook", PermissionMode.DEFAULT, List.of());

        String prompt = assembledSystemPrompt(state, hookTuc, QuerySource.HOOK_AGENT);

        assertThat(prompt)
            .as("hook 校验 agent 不得拿到 coordinator 提示（CC ExecAgentHook 用硬编码校验提示）")
            .doesNotContain(CoordinatorMode.getCoordinatorSystemPrompt());
        assertThat(prompt).contains("HOOK-AGENT-PROMPT");
    }
}

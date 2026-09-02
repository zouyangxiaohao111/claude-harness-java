package com.nexusai.application.agent.workflow.agent;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ClaudeCodeBackendAdapter schema 模式运行时接线测试 · 对齐 CC {@code claudeCodeBackend.ts:272-287
 * （schema prompt 注入）+ :371-397（extractStructuredOutput + dead 分类）}。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：W-2b 对抗验证 FAIL 的根因是
 * {@code StructuredOutputExtractorTest} 直调 {@code classifySchemaMode} 绕过 adapter，
 * 无法暴露「schema 模式运行时接线缺失」——adapter 不注入 schema prompt、不调用
 * classifySchemaMode，导致 schema 模式 agent 恒被引擎边界误判为 dead{invalid-structured-output}。
 * 本测试必须过 adapter.{@code run} 全链路（Mockito 替 {@code SubagentExecutor}）：
 * <ol>
 *   <li><b>prompt 注入</b>（A 域）— schema 存在时 agent 必须收到 schema JSON + CRITICAL RULES，
 *       否则 agent 无从产出 JSON（CC :272-287）。</li>
 *   <li><b>ok 分类</b>（B 域）— 产出匹配 JSON → ok{output=紧凑 JSON}，且 usage/toolCount/tokenCount/
 *       model 用真实值覆盖（CC :390-397 finalize 值）。</li>
 *   <li><b>dead 分类</b>（C 域）— 未产出 JSON → no-structured-output（带预览）；
 *       产出但 shape 不匹配 → invalid-structured-output（CC :373-388）。</li>
 *   <li><b>非 schema 透传</b>（D 域）— schema 为 null 不注入 prompt，output=summaryText 原样。</li>
 * </ol>
 */
@DisplayName("[W-2b] ClaudeCodeBackendAdapter schema 模式运行时接线")
class ClaudeCodeBackendAdapterTest {

    private final SubagentExecutor executor = mock(SubagentExecutor.class);
    private final ClaudeCodeBackendAdapter adapter = new ClaudeCodeBackendAdapter(executor);

    /** 临时目录（E 域 isolation 测试：mock WorktreeService 返回的假 worktree 路径落点）。 */
    @TempDir
    Path tmp;

    /** 构造最小 adapterCtx（host=null bundle、新 AbortController、register/unregister 空实现）。 */
    private AgentAdapterContext ctx() {
        return new AgentAdapterContext(
                HostHandle.create(null),
                new AbortController(),
                "run-1",
                1,
                null,
                (id, ac) -> {
                },
                id -> {
                });
    }

    private static SubagentExecutor.SubagentResult completed(String summary, int toolCount, long totalTokens,
                                                             AgentUsage usage) {
        return SubagentExecutor.SubagentResult.completed(summary, toolCount, 1000L, "agent-1", totalTokens, usage);
    }

    private static AgentUsage usage(long input, long output) {
        return new AgentUsage(input, output, 0L, 0L, null, "standard", null);
    }

    // ─────────────────────────── A 域：schema prompt 注入 ───────────────────────────

    @Test
    @DisplayName("A.1 schema 存在 → executeStreaming 收到的 prompt 含 schema JSON + CRITICAL RULES")
    void schemaMode_injectsSchemaPrompt() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of("type", "string")));
        AgentRunParams params = new AgentRunParams(
                "do the task", schema, null, null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("{\"name\": \"w2b\"}", 2, 150L, usage(100L, 50L)));

        AgentRunResult runResult = adapter.run(params, ctx()).join();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).executeStreaming(promptCaptor.capture(), any(), any(), isNull(), any(), any(), eq("workflow"), any());
        String prompt = promptCaptor.getValue();

        // CC claudeCodeBackend.ts:272-287 逐字：schema JSON 围栏 + CRITICAL RULES 4 条
        assertThat(prompt).startsWith("do the task");
        assertThat(prompt).contains("After completing the task, emit your final answer as a single JSON object matching this JSON Schema:");
        assertThat(prompt).contains("```json");
        assertThat(prompt).contains("\"type\" : \"object\""); // Jackson 2 空格缩进 schema JSON
        assertThat(prompt).contains("CRITICAL RULES:");
        assertThat(prompt).contains("- The JSON object must be the LAST text block in your response. Do not write any prose after it.");
        assertThat(prompt).contains("- Emit the JSON as plain text (markdown code fences optional).");
        assertThat(prompt).contains("- Do NOT call any \"StructuredOutput\" or \"SyntheticOutput\" tool — it is not available in this environment.");
        assertThat(prompt).contains("- Your turn must end with the JSON object. Anything after it (prose, tool calls) will be ignored or cause your answer to be discarded.");
        // prompt 注入不改变返回结果仍为 ok
        assertThat(runResult).isInstanceOf(AgentRunResultOk.class);
    }

    @Test
    @DisplayName("A.2 schema 为 null → prompt 原样透传（不注入）")
    void nonSchemaMode_promptUnchanged() {
        AgentRunParams params = new AgentRunParams(
                "do the task", null, null, null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("plain text result", 1, 100L, usage(80L, 20L)));

        adapter.run(params, ctx()).join();

        verify(executor).executeStreaming(eq("do the task"), any(), any(), isNull(), any(), any(), eq("workflow"), any());
    }

    // ─────────────────────────── B 域：ok 分类 + 真实 metrics 覆盖 ───────────────────────────

    @Test
    @DisplayName("B.1 schema 模式产出匹配 JSON → ok，output=紧凑 JSON，usage/token/model 真实覆盖")
    void schemaMode_validJson_returnsOkWithRealMetrics() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of("type", "string")));
        AgentRunParams params = new AgentRunParams(
                "do the task", schema, "my-model", null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("```json\n{\"name\": \"w2b\"}\n```", 2, 150L, usage(100L, 50L)));

        AgentRunResult runResult = adapter.run(params, ctx()).join();

        assertThat(runResult).isInstanceOf(AgentRunResultOk.class);
        AgentRunResultOk ok = (AgentRunResultOk) runResult;
        // classifySchemaMode 提取围栏内对象 → 紧凑 JSON（CC :390-397 output: structured as object 的 Java String 契约）
        assertThat(ok.output()).isEqualTo("{\"name\":\"w2b\"}");
        // 真实 metrics 覆盖 classifySchemaMode 的占位 0/null（CC :391-396 finalize 值）
        assertThat(ok.outputTokens()).isEqualTo(50);
        assertThat(ok.model()).isEqualTo("my-model");
        assertThat(ok.toolCount()).isEqualTo(2);
        assertThat(ok.tokenCount()).isEqualTo(150);
    }

    // ─────────────────────────── C 域：dead 分类 ───────────────────────────

    @Test
    @DisplayName("C.1 schema 模式未产出 JSON → dead{no-structured-output}，detail=预览")
    void schemaMode_noJson_returnsDeadNoStructuredOutput() {
        AgentRunParams params = new AgentRunParams(
                "do the task", Map.of("type", "object"), null, null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("agent 忘了输出 JSON", 0, 10L, usage(10L, 0L)));

        AgentRunResult runResult = adapter.run(params, ctx()).join();

        assertThat(runResult).isInstanceOf(AgentRunResultDead.class);
        AgentRunResultDead dead = (AgentRunResultDead) runResult;
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.NO_STRUCTURED_OUTPUT);
        assertThat(dead.detail()).isEqualTo("agent 忘了输出 JSON");
    }

    @Test
    @DisplayName("C.2 schema 模式产出 JSON 但 shape 不匹配 → dead{invalid-structured-output}")
    void schemaMode_shapeMismatch_returnsDeadInvalidStructuredOutput() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of("id", Map.of("type", "integer")));
        AgentRunParams params = new AgentRunParams(
                "do the task", schema, null, null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("{\"name\": \"missing-id\"}", 1, 20L, usage(10L, 5L)));

        AgentRunResult runResult = adapter.run(params, ctx()).join();

        assertThat(runResult).isInstanceOf(AgentRunResultDead.class);
        AgentRunResultDead dead = (AgentRunResultDead) runResult;
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.INVALID_STRUCTURED_OUTPUT);
        assertThat(dead.detail()).contains("required property 'id'");
    }

    // ─────────────────────────── D 域：非 schema 模式透传 ───────────────────────────

    @Test
    @DisplayName("D.1 非 schema 模式 → ok{output=summaryText 原样}（不做 JSON 提取/分类）")
    void nonSchemaMode_returnsPlainTextOk() {
        AgentRunParams params = new AgentRunParams(
                "do the task", null, "m", null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("plain text result", 1, 100L, usage(80L, 20L)));

        AgentRunResult runResult = adapter.run(params, ctx()).join();

        assertThat(runResult).isInstanceOf(AgentRunResultOk.class);
        AgentRunResultOk ok = (AgentRunResultOk) runResult;
        assertThat(ok.output()).isEqualTo("plain text result");
        assertThat(ok.outputTokens()).isEqualTo(20);
        assertThat(ok.model()).isEqualTo("m");
        assertThat(ok.toolCount()).isEqualTo(1);
        assertThat(ok.tokenCount()).isEqualTo(100);
    }

    // ─────────────────────────── E 域：[Fix-D4] querySource:'workflow' 委托语义 ───────────────────────────

    @Test
    @DisplayName("[Fix-D4] E.1 每次委托 runAgent 必传 querySource='workflow'（CC claudeCodeBackend.ts:304 默认）")
    void delegates_withQuerySourceWorkflow() {
        // WHY（规则九）: CC claudeCodeBackend.ts:304 runAgent 调用恒带
        //   querySource: toolUseContext.options.querySource ?? 'workflow'。workflow 子代理
        //   querySource 必须为 'workflow' 而非 SubagentExecutor 标准派生的 SUBAGENT（agent:subagent）——
        //   否则 persist gate（query.ts:376-378）误命中 agent: 前缀 → workflow 子代理 content
        //   replacement 错误落 sidechain（P1 Report D-4）。本断言锁「adapter 委托链恒传 'workflow'」，
        //   防未来回归标准派生。
        AgentRunParams params = new AgentRunParams(
                "do the task", null, null, null, null, null, null, null, null);
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any()))
                .thenReturn(completed("plain text result", 1, 100L, usage(80L, 20L)));

        adapter.run(params, ctx()).join();

        verify(executor).executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), any());
        // querySource 覆盖不可被 null 覆盖（防「null=标准派生」误用回归 → 恢复 agent:subagent）
        verify(executor, org.mockito.Mockito.never())
                .executeStreaming(any(), any(), any(), isNull(), any(), any(), isNull(), any());
    }

    // ─────────────────────────── F 域：[Fix-D1] isolation:'worktree' 接线 ───────────────────────────

    /**
     * 用 mock WorktreeService 构造隔离 adapter（D-1 接线：AgentWorktreeManager 预创建
     * wf_<sha256> worktree，fail-closed 建树失败 → dead{worktree-failed}，CC claudeCodeBackend.ts:219-234）。
     */
    private ClaudeCodeBackendAdapter isolatedAdapter(WorktreeService mockWorktreeService) {
        return new ClaudeCodeBackendAdapter(executor, new AgentWorktreeManager(mockWorktreeService));
    }

    @Test
    @DisplayName("F.1 isolation=worktree → AgentWorktreeManager 建树（wf_<sha256> slug）+ worktree 路径透传 executeStreaming 第 8 参（CC claudeCodeBackend.ts:219-234/:311）")
    void isolationWorktree_createsWorktreeAndPassesPath() {
        // WHY（规则九）: CC claudeCodeBackend.ts:220-234 isolation:'worktree' 时创建独立 git worktree，
        //   worktree 路径经 :311 override.worktreePath + runWithCwdOverride（:235-240）使并发 agent 各写各的
        //   cwd；不传路径 = 共享 cwd 并发写互踩（P1 Report D-1 未接线根因）。断言：建树被调 +
        //   slug 命中清理正则 + 路径到达 executeStreaming。
        WorktreeService mockWorktreeService = mock(WorktreeService.class);
        ClaudeCodeBackendAdapter isolated = isolatedAdapter(mockWorktreeService);

        AgentRunParams params = new AgentRunParams(
                "do task", null, null, null, null, "worktree", null, null, null);
        String worktreePath = tmp.resolve("wf-agent-worktree").toString();
        when(mockWorktreeService.createAgentWorktree(any(), any()))
                .thenReturn(new WorktreeCreateResult.Created(Path.of(worktreePath), "wf_branch", tmp));
        when(executor.executeStreaming(any(), any(), any(), isNull(), any(), any(), eq("workflow"), eq(worktreePath)))
                .thenReturn(completed("isolated result", 1, 100L, usage(80L, 20L)));

        AgentRunResult runResult = isolated.run(params, ctx()).join();

        // 建树被调 + slug 形如 cleanupStaleAgentWorktrees 清理正则 ^wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+$
        ArgumentCaptor<String> slugCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockWorktreeService).createAgentWorktree(any(), slugCaptor.capture());
        assertThat(slugCaptor.getValue()).matches("^wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+$")
                .as("workflow isolation slug 必须命中清理正则（否则 30 天清理漏扫泄漏 worktree）");
        // worktree 路径透传 executeStreaming 第 8 参（CC :311 override.worktreePath → Step 18 effectiveCwd）
        assertThat(runResult).isInstanceOf(AgentRunResultOk.class);
        assertThat(((AgentRunResultOk) runResult).output()).isEqualTo("isolated result");
    }

    @Test
    @DisplayName("F.2 isolation=worktree 建树失败 → dead{worktree-failed}，绝不回落共享 cwd（fail-closed，CC claudeCodeBackend.ts:227-233）")
    void isolationWorktree_failClosedDeadOnCreationFailure() {
        // WHY（规则九）: CC claudeCodeBackend.ts:227-228 注释「do not silently fall back to a shared cwd
        //   (otherwise concurrent writes race on data)」。建树失败必须降级 dead{worktree-failed}，
        //   绝不回落共享 cwd 委托 runAgent（P1 Report D-1 静默 fail-open 根因）。
        WorktreeService mockWorktreeService = mock(WorktreeService.class);
        ClaudeCodeBackendAdapter isolated = isolatedAdapter(mockWorktreeService);

        AgentRunParams params = new AgentRunParams(
                "do task", null, null, null, null, "worktree", null, null, null);
        when(mockWorktreeService.createAgentWorktree(any(), any()))
                .thenThrow(new WorktreeService.WorktreeException("git worktree add failed (exit=128)"));

        AgentRunResult runResult = isolated.run(params, ctx()).join();

        assertThat(runResult).isInstanceOf(AgentRunResultDead.class);
        AgentRunResultDead dead = (AgentRunResultDead) runResult;
        assertThat(dead.reason()).isEqualTo(AgentRunResult.DeadReason.WORKTREE_FAILED);
        assertThat(dead.detail()).contains("git worktree add failed");
        // fail-closed：绝不回落共享 cwd → 不委托 runAgent（8 参 executeStreaming 恒不被调）
        verify(executor, never())
                .executeStreaming(any(), any(), any(), isNull(), any(), any(), any(), any());
    }
}

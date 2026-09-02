package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [exitworktree-contract] ExitWorktreeTool 8 字段 structuredOutput 契约 · 对齐 CC
 * ExitWorktreeTool.ts:47-58 outputSchema + :274-283 keep data + :310-320 remove data
 * + :322-328 mapToolResultToToolResultBlockParam。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC 的 ExitWorktreeTool 输出不是一段 JSON 文本，而是
 * 8 字段结构化 data（action/originalCwd/worktreePath/worktreeBranch/tmuxSessionName/
 * discardedFiles/discardedCommits/message），且 {@code mapToolResultToToolResultBlockParam}
 * 仅抽取 message 作 tool_result content——结构化字段走 data 通道不进 LLM（避免 token 浪费 +
 * 保持 CC 契约形状）。旧 Java 实现把 3 字段（含错误的 slug 键）塞成 JSON 字符串当 data，
 * 既偏离 CC 输出契约、又丢失结构化通道。本测试锁定三条契约：
 * <ol>
 *   <li>keep 六键、无 discarded 键、tmuxSessionName 恒 null（对齐 CC keep data）</li>
 *   <li>remove 七键、含 discardedFiles/discardedCommits、无 tmuxSessionName（对齐 CC remove data）</li>
 *   <li>mapToToolResultBlockParam content == message 文本、toolUseId 匹配（8 字段不进 LLM）</li>
 * </ol>
 * 若实现退回 JSON 字符串输出、或把 8 字段塞进 content、或 keep/remove 键集合错位，本测试即红。
 *
 * @see ExitWorktreeTool
 */
class ExitWorktreeToolStructuredOutputTest {

    /** 固定 sessionId：仅作 WorktreeCwdTracker sessionKey 键（IMP-F1 会话作用域守卫）。 */
    private static final String SESSION_ID =
            "sess-12345678";

    @AfterEach
    void tearDown() {
        // WorktreeCwdTracker 为进程级静态单例，测试间必须清理 sessionWorktree，避免串味。
        WorktreeCwdTracker.clearWorktreeSession(SESSION_ID.toString());
        WorktreeCwdTracker.clearCwd(SESSION_ID.toString());
        WorktreeCwdTracker.clearOriginalCwd(SESSION_ID.toString());
    }

    private static ToolUseBlock call(String id, String action) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("action", action);
        return new ToolUseBlock(id, ExitWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), SESSION_ID);
    }

    /**
     * 注册当前会话 worktree 会话对象 — IMP-F1 会话作用域守卫：ExitWorktreeTool 只操作
     * {@code WorktreeCwdTracker.getWorktreeSession(sessionKey)} 返回的会话 worktree（对齐 CC
     * {@code getCurrentWorktreeSession()}）。直调 execute 前必须先登记，否则守卫返回 no-op。
     */
    private static void registerSession(String slug) {
        WorktreeCwdTracker.setWorktreeSession(SESSION_ID.toString(),
            new WorktreeCwdTracker.WorktreeSession(
                "/fake/worktrees/" + slug, "worktree-" + slug, slug, false, SESSION_ID.toString()));
    }

    private static Map<String, Object> structuredOutput(AgentToolResult<?> result) {
        assertThat(result).isInstanceOf(ToolResult.class);
        return ToolResult.presentationMeta((ToolResult<?>) result);
    }

    @Test
    @DisplayName("keep → 六键（含 tmuxSessionName=null），不含 discardedFiles/discardedCommits")
    void keep_hasSixKeys_withoutDiscarded() {
        // WHY: CC ExitWorktreeTool.ts:274-283 keep data 含 action/originalCwd/worktreePath/
        //   worktreeBranch/tmuxSessionName/message，无 discarded 字段（keep 不丢弃任何东西）。
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        registerSession("feature-x");

        AgentToolResult<?> result = tool.execute(call("keep-call", "keep"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("keep 应成功").isFalse();
        Map<String, Object> so = structuredOutput(result);
        assertThat(so).containsKeys("action", "originalCwd", "worktreePath",
                "worktreeBranch", "tmuxSessionName", "message");
        assertThat(so.get("action")).isEqualTo("keep");
        assertThat(so.get("tmuxSessionName"))
            .as("tmuxSessionName 恒 null（RETAIN-gap：Java 无 tmux 会话能力）")
            .isNull();
        assertThat(so.get("message")).isInstanceOf(String.class);
        assertThat(so).doesNotContainKeys("discardedFiles", "discardedCommits");
    }

    @Test
    @DisplayName("remove → 含 discardedFiles=2/discardedCommits=3，不含 tmuxSessionName")
    void remove_hasDiscardedCounts_withoutTmuxSessionName() {
        // WHY: CC ExitWorktreeTool.ts:310-320 remove data 含 discardedFiles(=changedFiles)/
        //   discardedCommits(=commits) 且无 tmuxSessionName（remove 会 kill tmux 会话）。
        WorktreeService service = mock(WorktreeService.class);
        when(service.countChanges(any(), any()))
            .thenReturn(new WorktreeService.WorktreeChanges(2, 3));
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        registerSession("feature-x");

        AgentToolResult<?> result = tool.execute(call("remove-call", "remove"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("remove 应成功（mock 不真正删）").isFalse();
        Map<String, Object> so = structuredOutput(result);
        assertThat(so.get("action")).isEqualTo("remove");
        assertThat(so.get("discardedFiles")).isEqualTo(2);
        assertThat(so.get("discardedCommits")).isEqualTo(3);
        assertThat(so).containsKeys("originalCwd", "worktreePath", "worktreeBranch", "message");
        assertThat(so).doesNotContainKey("tmuxSessionName");
    }

    @Test
    @DisplayName("mapToToolResultBlockParam → content == message 文本、toolUseId 匹配、isError=false")
    void mapToToolResultBlockParam_usesOnlyMessageAsContent() {
        // WHY: CC ExitWorktreeTool.ts:322-328 mapToolResultToToolResultBlockParam({message}, id)
        //   仅抽取 message 作 content，8 字段走 data 结构化通道不进 LLM。若把整份结构化 Map
        //   序列化进 content（token 浪费 + 破坏 Anthropic tool_result 契约），本测试即红。
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        registerSession("feature-x");

        AgentToolResult<?> result = tool.execute(call("keep-call-2", "keep"), ctx());
        String message = (String) structuredOutput(result).get("message");

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "keep-call-2", false);

        assertThat(block).as("success 路径必须返回非 null tool_result 块").isNotNull();
        assertThat(block.toolUseId()).isEqualTo("keep-call-2");
        assertThat(block.isError()).isFalse();
        assertThat(block.content())
            .as("content 仅 message 文本（8 字段走 structuredOutput，不进 LLM）")
            .isInstanceOf(String.class)
            .isEqualTo(message);
    }

    @Test
    @DisplayName("mapToToolResultBlockParam 错误路径 → 返回 null（fail-loud 回退默认渲染器）")
    void mapToToolResultBlockParam_error_returnsNull() {
        // WHY: 对齐 AskUserQuestionTool/RemoteTriggerTool 惯例——isError 结果不应被本 override
        //   消费（否则 error message 会因 structuredOutput 无 message 键而被吞掉）。
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);

        AgentToolResult<?> error = ToolResult.error("error-call", "missing required input: action");

        assertThat(tool.mapToToolResultBlockParam(error, "error-call", true)).isNull();
    }

    @Test
    @DisplayName("无会话 worktree → 会话守卫 no-op（对齐 CC ExitWorktreeTool.ts:174-188 errorCode 1）")
    void execute_withoutSession_returnsNoOpGuard() {
        // WHY: IMP-F1 会话作用域守卫——未由本会话 EnterWorktree 创建的 worktree（跨会话/手工）
        //   不可被 ExitWorktree 操作，否则可能误删。直调 execute 未登记会话 → 守卫 no-op。
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);

        AgentToolResult<?> result = tool.execute(call("noop-call", "remove"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("无会话必须被守卫拦截").isTrue();
        assertThat((String) result.data())
            .as("no-op 文案对齐 CC ExitWorktreeTool.ts:185-187")
            .contains("no active EnterWorktree session");
        // 守卫不得触达 WorktreeService（防误删）——直调下 consumeHookBasedWorktree 未被消费
    }
}

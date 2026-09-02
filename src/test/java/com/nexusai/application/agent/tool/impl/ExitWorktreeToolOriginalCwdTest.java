package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
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
 * gap1-originalCwd: ExitWorktreeTool 退出时回显用户真实目录（前端传入），缺失回退 user.dir。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC ExitWorktreeTool.ts:47-58 outputSchema 要求
 * {@code originalCwd: z.string()} 必填，且 call() 退出前从 currentWorktreeSession 解构
 * originalCwd 回显（:274-283 keep data / :310-320 remove data）。CC 的 originalCwd =
 * {@code getCwd()} 进程 cwd；Web 架构下服务端 user.dir ≠ 浏览器用户目录，故 Java 端由
 * 前端经 POST /api/v1/sessions body 传入 originalCwd → EnterWorktreeTool 捕获存入
 * WorktreeCwdTracker → ExitWorktreeTool 读取回显，缺失回退 user.dir。旧实现恒回显
 * user.dir（gitRoot），前端永远拿不到真实目录——本测试锁定「传入 → 回显该值；未传 → 回退
 * user.dir」这一契约，若退回恒 user.dir 或把回退值写错，测试即红。
 *
 * @see ExitWorktreeTool
 * @see WorktreeCwdTracker
 */
class ExitWorktreeToolOriginalCwdTest {

    /** 固定 sessionId：msb=0 形状（对齐 parseSessionUuid("sess-...") 产物），仅作 sessionKey 键。 */
    private static final String SESSION_ID =
            "sess-12345678";
    private static final String FAKE_ORIGINAL_CWD = "/fake/orig";

    @AfterEach
    void tearDown() {
        // WorktreeCwdTracker 为进程级静态单例，测试间必须清理，避免 originalCwd / sessionWorktree
        //   串味污染后续断言。
        WorktreeCwdTracker.clearOriginalCwd(SESSION_ID.toString());
        WorktreeCwdTracker.clearWorktreeSession(SESSION_ID.toString());
        WorktreeCwdTracker.clearCwd(SESSION_ID.toString());
    }

    /**
     * 注册当前会话 worktree 会话对象 — IMP-F1 会话作用域守卫：ExitWorktreeTool 只操作
     * {@code WorktreeCwdTracker.getWorktreeSession(sessionKey)} 返回的会话 worktree（对齐 CC
     * {@code getCurrentWorktreeSession()}）。直调 execute 前必须先登记，否则守卫返回 no-op。
     */
    private static void registerSession() {
        WorktreeCwdTracker.setWorktreeSession(SESSION_ID.toString(),
            new WorktreeCwdTracker.WorktreeSession(
                "/fake/worktrees/feature-x", "worktree-feature-x", "feature-x", false,
                SESSION_ID.toString()));
    }

    private static ToolUseBlock call(String id, String action) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("action", action);
        return new ToolUseBlock(id, ExitWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), SESSION_ID);
    }

    private static Map<String, Object> structuredOutput(AgentToolResult<?> result) {
        assertThat(result).isInstanceOf(ToolResult.class);
        return ToolResult.presentationMeta((ToolResult<?>) result);
    }

    @Test
    @DisplayName("keep → 回显 WorktreeCwdTracker 里 Enter 捕获的 originalCwd（前端传入用户真实目录）")
    void keep_echoesTrackedOriginalCwd() {
        // WHY: Enter 捕获前端传入 originalCwd → Exit keep 应回显该值，而非服务端 user.dir。
        WorktreeCwdTracker.setOriginalCwd(SESSION_ID.toString(), FAKE_ORIGINAL_CWD);
        registerSession();

        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);

        AgentToolResult<?> result = tool.execute(call("keep-orig", "keep"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("keep 应成功").isFalse();
        Map<String, Object> so = structuredOutput(result);
        assertThat(so.get("originalCwd")).isEqualTo(FAKE_ORIGINAL_CWD);
        assertThat((String) so.get("message"))
            .as("keep message 应包含回显的 originalCwd")
            .contains(FAKE_ORIGINAL_CWD);
    }

    @Test
    @DisplayName("remove → 回显 WorktreeCwdTracker 里 Enter 捕获的 originalCwd")
    void remove_echoesTrackedOriginalCwd() {
        // WHY: remove 分支 data 同样含 originalCwd（CC ExitWorktreeTool.ts:310-320），
        //   且 buildRemoveMessage 文本含 "Session is now back in {originalCwd}"。
        WorktreeCwdTracker.setOriginalCwd(SESSION_ID.toString(), FAKE_ORIGINAL_CWD);
        registerSession();

        WorktreeService service = mock(WorktreeService.class);
        when(service.countChanges(any(), any()))
            .thenReturn(new WorktreeService.WorktreeChanges(2, 3));
        ExitWorktreeTool tool = new ExitWorktreeTool(service);

        AgentToolResult<?> result = tool.execute(call("remove-orig", "remove"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("remove 应成功（mock 不真正删）").isFalse();
        Map<String, Object> so = structuredOutput(result);
        assertThat(so.get("originalCwd")).isEqualTo(FAKE_ORIGINAL_CWD);
        assertThat((String) so.get("message"))
            .as("remove message 应包含回显的 originalCwd")
            .contains(FAKE_ORIGINAL_CWD);
    }

    @Test
    @DisplayName("未捕获 originalCwd → keep 回退 user.dir（System.getProperty）")
    void keep_fallsBackToUserDirWhenNotTracked() {
        // WHY: 前端未传 originalCwd（或 Enter 未捕获）时，Exit 必须回退 user.dir 而非空值/null——
        //   对齐 CC originalCwd 必填语义 + Java 现有 gitRoot=user.dir 兜底。
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        registerSession();

        AgentToolResult<?> result = tool.execute(call("keep-fallback", "keep"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(structuredOutput(result).get("originalCwd"))
            .isEqualTo(System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("未捕获 originalCwd → remove 回退 user.dir（System.getProperty）")
    void remove_fallsBackToUserDirWhenNotTracked() {
        // WHY: 同 keep 回退语义，remove 分支也必须回退 user.dir（而非 null/空串）。
        WorktreeService service = mock(WorktreeService.class);
        when(service.countChanges(any(), any()))
            .thenReturn(new WorktreeService.WorktreeChanges(0, 0));
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        registerSession();

        AgentToolResult<?> result = tool.execute(call("remove-fallback", "remove"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(structuredOutput(result).get("originalCwd"))
            .isEqualTo(System.getProperty("user.dir"));
    }
}

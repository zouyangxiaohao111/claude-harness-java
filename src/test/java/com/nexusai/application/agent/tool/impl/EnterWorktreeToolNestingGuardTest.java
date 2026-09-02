package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [OD-2A-6] worktree 嵌套守卫 · 对齐 CC EnterWorktreeTool.ts:79-81
 * {@code if (getCurrentWorktreeSession()) throw new Error('Already in a worktree session')}。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>：CC 真源在 call() 入口用 {@code getCurrentWorktreeSession()}
 * 检查会话是否已在 worktree 中，非空即抛错 —— <b>CC 没有 worktree 嵌套</b>，因此也不存在
 * pre-worktree originalCwd 栈（OD-2A-6 的"栈"在 CC 真源中无对应物）。Java 端
 * {@code SessionCwdHolder.originalCwd} / {@code WorktreeCwdTracker} 均为<b>单槽</b>
 * （对齐 CC 单 {@code STATE.originalCwd} / 单 {@code currentWorktreeSession}）；若无此守卫，
 * worktree 内再 Enter 会<b>覆盖</b>单槽 → 退出后丢外层 worktree 路径（回落 boundProject 主仓根
 * 而非外层 worktree），破坏「会话维度单 worktree」契约。本守卫显式拒绝嵌套，使嵌套不可达，
 * 单层退出恢复（ExitWorktreeTool 清槽回落 boundProject）即 CC 语义的完整实现。
 *
 * <p><b>验收</b>：
 * <ul>
 *   <li>会话已在 worktree（WorktreeCwdTracker.getWorktreeSession 非空）→ Enter 返回 error，
 *       且不触碰 WorktreeService（嵌套被拒绝，不得创建/恢复 worktree）。</li>
 *   <li>会话不在 worktree → Enter 正常走 WorktreeService（守卫放行，不误伤单层进入）。</li>
 * </ul>
 */
@DisplayName("[OD-2A-6] EnterWorktreeTool worktree 嵌套守卫（对齐 CC EnterWorktreeTool.ts:79-81）")
class EnterWorktreeToolNestingGuardTest {

    private static final String SESSION =
            "sess-12345678";
    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        WorktreeCwdTracker.clearWorktreeSession(SESSION.toString());
        WorktreeCwdTracker.clearCwd(SESSION.toString());
        WorktreeCwdTracker.clearOriginalCwd(SESSION.toString());
    }

    private static ToolUseBlock enterCall(String name) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("name", name);
        return new ToolUseBlock("enter-" + name, EnterWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("会话已在 worktree 中 → Enter 拒绝（error），且不触碰 WorktreeService（嵌套不可达）")
    void alreadyInWorktree_rejectsNestedEnter() {
        // WHY: CC EnterWorktreeTool.ts:79-81 在 call() 入口 getCurrentWorktreeSession() 非空即抛
        //   "Already in a worktree session" —— 会话维度单 worktree，嵌套在 CC 中不存在。
        //   若 Java 无此守卫，worktree 内再 Enter 会覆盖 originalCwd/WorktreeCwdTracker 单槽，
        //   退出后丢外层 worktree 路径（回落 boundProject 而非外层 worktree）。本测试锁定：
        //   已登记 worktree 会话对象（对齐 CC currentWorktreeSession 非空）→ Enter 必须 error。
        WorktreeCwdTracker.setWorktreeSession(SESSION.toString(),
            new WorktreeCwdTracker.WorktreeSession(
                "/fake/worktrees/outer", "worktree-outer", "outer", false,
                SESSION.toString()));

        WorktreeService service = mock(WorktreeService.class);
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(new HookRegistry()); // 无 hook → git 路径（若守卫失效会触达 service）

        AgentToolResult<?> result = tool.execute(enterCall("inner"), ctx());

        // 守卫返回 ToolResult.error（对齐 CC throw）。isToolErrorData 前缀表不含本消息，直接断言
        // data 内容 + 非空（守卫触达证明错误路径被选中）。
        String data = String.valueOf(result.data());
        assertThat(data)
            .as("会话已在 worktree 中，嵌套 Enter 必须返回错误信息（对齐 CC 'Already in a worktree session'）")
            .contains("Already in a worktree session")
            .isNotEmpty();
        // 关键：守卫在触达 WorktreeService 前短路——不得创建/恢复/校验任何 worktree
        verify(service, never()).createWorktree(any(), any());
        verify(service, never()).registerHookBasedWorktree(any(), any());
    }

    @Test
    @DisplayName("会话不在 worktree 中 → Enter 正常走 WorktreeService（守卫放行，不误伤单层进入）")
    void notInWorktree_guardPassesThrough() {
        // WHY: 守卫必须精确匹配「当前会话已登记 worktree 会话对象」（对齐 CC getCurrentWorktreeSession），
        //   不误伤单层进入——否则首次 Enter 也被拒，worktree 功能全断。mock WorktreeService
        //   createWorktree 正常返回，断言 execute 触达 service 且成功。
        WorktreeService service = mock(WorktreeService.class);
        Path wt = Path.of("/repo/.nexusai/worktrees/feature-x");
        when(service.createWorktree(any(), eq("feature-x")))
            .thenReturn(new WorktreeCreateResult.Created(wt, "feature-x", Path.of("/repo")));
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(new HookRegistry()); // 无 hook → git 路径

        AgentToolResult<?> result = tool.execute(enterCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("会话不在 worktree 中，Enter 应放行成功")
            .isFalse();
        verify(service).createWorktree(any(), eq("feature-x"));
    }
}

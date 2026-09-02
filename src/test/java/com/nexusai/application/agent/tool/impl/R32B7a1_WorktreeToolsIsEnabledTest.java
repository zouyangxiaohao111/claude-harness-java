package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [WF-B4] · EnterWorktreeTool / ExitWorktreeTool.isEnabled() 恒启用验证.
 *
 * <p><b>WHY (意图验证)</b>: CC 侧 Worktree 模式无条件开启 —
 * {@code utils/worktreeModeEnabled.ts:11} {@code return true}（恒真），且
 * EnterWorktreeTool.ts / ExitWorktreeTool.ts 均无 {@code isEnabled} override，
 * 继承 {@code tool.ts:758} 基类默认 {@code isEnabled: () => true}。
 * Java 端曾自造"env 变量 / sysprop 默认 false 的 opt-in"门控（偏离 CC），
 * WF-B4 已删除。本测试锁定"恒 true，不依赖任何环境配置"这一对齐行为。
 *
 * @see EnterWorktreeTool#isEnabled()
 * @see ExitWorktreeTool#isEnabled()
 */
class R32B7a1_WorktreeToolsIsEnabledTest {

    private final WorktreeService worktreeService = mock(WorktreeService.class);

    @Test
    @DisplayName("EnterWorktreeTool.isEnabled() 恒 true（对齐 CC worktreeModeEnabled.ts:11 return true）")
    void enterToolAlwaysEnabled() {
        // WHY: CC 无条件启用 worktree 工具；Java 恒 true 即与 CC 行为一致。
        EnterWorktreeTool tool = new EnterWorktreeTool(worktreeService);
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("ExitWorktreeTool.isEnabled() 恒 true（对齐 CC tool.ts:758 基类默认 true）")
    void exitToolAlwaysEnabled() {
        // WHY: Exit 与 Enter 同无条件启用，无独立开关（CC 两工具均无 isEnabled override）。
        ExitWorktreeTool tool = new ExitWorktreeTool(worktreeService);
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isEnabled() 不依赖任何 env/sysprop 门控（恒 true，非 opt-in）")
    void noEnvGating() {
        // WHY: 曾存在"env 变量 / sysprop 默认 false 的 opt-in"门控（偏离 CC），
        // WF-B4 删除。此测试锁定"无论环境如何配置，工具始终暴露"。
        // 已无需反射改 env — 直接断言恒 true 即证明门控已移除。
        EnterWorktreeTool enterTool = new EnterWorktreeTool(worktreeService);
        ExitWorktreeTool exitTool = new ExitWorktreeTool(worktreeService);
        assertThat(enterTool.isEnabled()).isTrue();
        assertThat(exitTool.isEnabled()).isTrue();
    }
}

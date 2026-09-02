package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [Q-3] · ExitWorktreeTool.isDestructive() 删除方向破坏性语义验证.
 *
 * <p><b>WHY (意图验证)</b>: CC ExitWorktreeTool.ts:168-170 的 isDestructive 是纯 UI
 * 标签契约成员（仅标注 [destructive] 标签，不参与权限决策）——
 * {@code isDestructive(input) { return input.action === 'remove' }}。
 * action='remove' 会不可逆删除 worktree + branch，必须在 UI 暴露破坏性标记；
 * action='keep' 仅保留现场，非破坏性。Java 端此前未 override，继承 Tool.java:338-340
 * 恒 false，偏离 CC（keep/remove 都被标成"安全"）。本测试锁定"仅删除方向才破坏"
 * 这一安全语义——若未来误把 keep 判破坏、或把 remove 判非破坏，本测试即红。
 *
 * @see ExitWorktreeTool#isDestructive(JsonNode)
 */
class ExitWorktreeToolDestructiveTest {

    private final WorktreeService worktreeService = mock(WorktreeService.class);

    private JsonNode input(String action) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("action", action);
        return node;
    }

    @Test
    @DisplayName("action='remove' → true（删除 worktree + branch 为不可逆破坏性操作）")
    void removeIsDestructive() {
        ExitWorktreeTool tool = new ExitWorktreeTool(worktreeService);
        assertThat(tool.isDestructive(input("remove"))).isTrue();
    }

    @Test
    @DisplayName("action='keep' → false（保留现场非破坏性）")
    void keepIsNotDestructive() {
        ExitWorktreeTool tool = new ExitWorktreeTool(worktreeService);
        assertThat(tool.isDestructive(input("keep"))).isFalse();
    }

    @Test
    @DisplayName("null input → false（fail-closed 对齐 Tool.java:338-340 默认 false）")
    void nullInputIsNotDestructive() {
        ExitWorktreeTool tool = new ExitWorktreeTool(worktreeService);
        assertThat(tool.isDestructive(null)).isFalse();
    }

    @Test
    @DisplayName("action 缺失 → false（非 'remove' 一律非破坏性）")
    void missingActionIsNotDestructive() {
        ExitWorktreeTool tool = new ExitWorktreeTool(worktreeService);
        assertThat(tool.isDestructive(JsonNodeFactory.instance.objectNode())).isFalse();
    }
}

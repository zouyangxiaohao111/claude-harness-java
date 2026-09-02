package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.ForkWorktreePaths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session S7 · worktree slug 格式 RED→GREEN 验证 · 对齐 CC AgentTool.tsx:591.
 *
 * <p><b>WHY (CLAUDE.md 规则九 · Pattern #12 命名反直觉)</b>: CC AgentTool.tsx:591
 * {@code `agent-${earlyAgentId.slice(0, 8)}`} 固定 "agent-" 前缀 + earlyAgentId 前 8 位;
 * earlyAgentId=createAgentId()='a'+16hex (uuid.ts:24-27) → slug 首字符恒 'a' (R-A11)。
 * 旧 Java 基于 packed UUID 前 8 位 (随机 hex 首字符) 与 CC 'a' 前缀语义不同, 已迁移至
 * {@link ForkWorktreePaths#buildWorktreeSlug(String)}。
 */
@DisplayName("Session S7 · worktree slug 格式 agent-{earlyAgentId前8位}")
class WorktreeSlugFormatTest {

    @Test
    @DisplayName("slug = agent-{a+16hex 前8位}, 首字符恒 'a'（R-A11 · CC AgentTool.tsx:591）")
    void worktreeSlug_isAgentPrefixPlusFirst8OfA16Hex() {
        // WHY: CC AgentTool.tsx:580 earlyAgentId=createAgentId()='a'+16hex, :591
        //   `agent-${earlyAgentId.slice(0, 8)}` → slug = "agent-aXXXXXXX" (首字符恒 'a')。
        //   旧实现基于 packed UUID 前 8 位 (随机 hex 首字符) 与 CC 语义不同 (R3-WF-F concerns-4)。
        String slug = ForkWorktreePaths.buildWorktreeSlug("a1234567890abcdef");

        assertThat(slug)
            .as("slug 必须匹配 agent-a[0-9a-f]{7} (CC AgentTool.tsx:591, 首字符恒 'a')")
            .matches("agent-a[0-9a-f]{7}");
        assertThat(slug)
            .as("slug 必须以固定 'agent-' 前缀开头 (非 agentType 前缀)")
            .startsWith("agent-");
        // 8 位 = 'a'+16hex 前 8 字符: "a1234567"
        assertThat(slug)
            .as("slug 必须精确等于 agent-a1234567 (CC earlyAgentId.slice(0,8) 确定性)")
            .isEqualTo("agent-a1234567");
    }

    @Test
    @DisplayName("生产 createAgentId() 产物 → slug 恒 'a' 首字符（R-A11 生产接线断言）")
    void worktreeSlug_fromCreateAgentId_alwaysStartsWithAgentA() {
        // WHY: 生产 fork 路径 slug 输入是 a+16hex (AgentContext.unpackAgentId 还原) —
        //   无论随机值如何, 首字符恒 'a', slug 恒 "agent-a..." (CC earlyAgentId 同源)。
        String slug = ForkWorktreePaths.buildWorktreeSlug(AgentContext.createAgentId());

        assertThat(slug)
            .as("createAgentId 产物前 8 位 = 'a'+7 hex → slug 恒 agent-a... (CC 首字符语义)")
            .matches("agent-a[0-9a-f]{7}");
    }

    @Test
    @DisplayName("带 label 的 a+16hex 按 CC slice(0,8) 取前 8 字符（含 label，行为一致）")
    void worktreeSlug_labeledId_slicesFirst8CharsLikeCC() {
        // WHY: CC 对 earlyAgentId 一律 slice(0,8), 不区分 label; Java fork 生产不产生 label id
        //   (createSubagentContext.java:214 无 label createAgentId()), 但方法行为必须与 CC 一致。
        assertThat(ForkWorktreePaths.buildWorktreeSlug("acompact-a3f2c1b4d5e6f7a8"))
            .as("label id 前 8 字符 = 'acompact' → slug agent-acompact (CC slice(0,8) 忠实)")
            .isEqualTo("agent-acompact");
    }
}

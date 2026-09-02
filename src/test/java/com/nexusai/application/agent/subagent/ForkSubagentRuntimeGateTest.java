package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-SP23 · {@link ForkSubagent#isForkSubagentEnabled()} 对齐 CC forkSubagent.ts:32-39。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: OPD-SP-23 用户拍板"补上，对齐 CC
 * isForkSubagentEnabled 双分支"。CC 真源（forkSubagent.ts:32-39）:
 * <pre>{@code
 * if (feature('FORK_SUBAGENT')) {
 *   if (isCoordinatorMode()) return false
 *   if (getIsNonInteractiveSession()) return false
 *   return true
 * }
 * return false
 * }</pre>
 * Java 以运行时门槽三参（默认 {true,false,false}，由 SubagentTool register/setter 同步）承载
 * feature/coordinator/interactive 全局态。测试钉死：feature 关 / coordinator / 非交互 任一命中
 * → false；三者全放行 → true。默认槽位 {true,false,false} → true（对齐生产 SubagentTool 无配置默认，
 * 即 session_guidance 默认注入 :318 fork 变体，与 CC 发行版 FORK_SUBAGENT 启用一致）。
 */
@DisplayName("RES-SP23 · ForkSubagent.isForkSubagentEnabled 对齐 CC forkSubagent.ts:32-39")
class ForkSubagentRuntimeGateTest {

    @AfterEach
    void restoreDefaultGate() {
        // 还原默认门槽，避免跨测试污染（测试间顺序无关）
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    @Test
    @DisplayName("默认运行时门槽 {true,false,false} → true（对齐生产 SubagentTool 无配置默认）")
    void defaultGate_forkEnabled() {
        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("默认 {true,false,false} → feature 开且非 coordinator 且交互式 → true（forkSubagent.ts:36）")
            .isTrue();
    }

    @Test
    @DisplayName("feature('FORK_SUBAGENT')=false → false（forkSubagent.ts:33 早返）")
    void featureOff_disables() {
        ForkSubagent.syncRuntimeGate(false, false, false);

        assertThat(ForkSubagent.isForkSubagentEnabled()).isFalse();
    }

    @Test
    @DisplayName("coordinator 模式 → false（forkSubagent.ts:34 isCoordinatorMode）")
    void coordinatorMode_disables() {
        ForkSubagent.syncRuntimeGate(true, true, false);

        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("coordinatorMode=true 即便 feature 开也必须关闭（与 coordinator 互斥）")
            .isFalse();
    }

    @Test
    @DisplayName("非交互会话 → false（forkSubagent.ts:35 getIsNonInteractiveSession）")
    void nonInteractive_disables() {
        ForkSubagent.syncRuntimeGate(true, false, true);

        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("nonInteractive=true 即便 feature 开也必须关闭")
            .isFalse();
    }

    @Test
    @DisplayName("三者全放行（feature 开 / 非 coordinator / 交互式）→ true（forkSubagent.ts:36）")
    void allClear_enables() {
        ForkSubagent.syncRuntimeGate(true, false, false);

        assertThat(ForkSubagent.isForkSubagentEnabled()).isTrue();
    }
}

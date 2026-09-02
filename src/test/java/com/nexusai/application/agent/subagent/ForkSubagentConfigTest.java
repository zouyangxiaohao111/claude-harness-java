package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-SP23-1 · ForkSubagentConfig（nexusai.fork.* 配置类）驱动 ForkSubagent 运行时门槽。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: OPD-SP23-1 用户拍板"对齐 CC（经配置类驱动门，
 * 非 bean 接线）"。CC 真源（forkSubagent.ts:32-39）：{@code feature('FORK_SUBAGENT')} 是进程级
 * 全局值，与 Spring bean 生命周期无关。生产 SubagentTool 为 {@code new} 构造 → 旧 @Value setter
 * 注入不触发 → 门停默认 {true,false,false}，无法反映 yml 配置。本测试钉死：配置类注册后
 * {@code new SubagentTool()}（无 Spring 上下文）必须读取 {@link ForkSubagentConfig#current()}
 * 同步 {@link ForkSubagent#syncRuntimeGate} → {@link ForkSubagent#isForkSubagentEnabled()} 反映
 * 真实配置（featureOn=false → 门关）。
 *
 * <p><b>RED 基线</b>: 实现前 {@code new SubagentTool()} 不读配置类 → 运行时槽位恒默认
 * {true,false,false} → featureOn=false 配置下 isForkSubagentEnabled() 仍 true → 断言 isFalse 失败。
 */
@DisplayName("RES-SP23-1 · ForkSubagentConfig 驱动 ForkSubagent 运行时门槽（非 bean 接线）")
class ForkSubagentConfigTest {

    @AfterEach
    void restoreDefaults() {
        // 还原静态状态，避免跨测试污染（配置类静态 current + ForkSubagent 运行时槽位）
        ForkSubagentConfig.register(ForkSubagentConfig.DEFAULTS);
        ForkSubagent.syncRuntimeGate(true, false, false);
    }

    @Test
    @DisplayName("配置 featureOn=false → new SubagentTool() 后 isForkSubagentEnabled()=false（CC forkSubagent.ts:33 早返）")
    void configFeatureOnFalse_drivesNewSubagentToolRuntimeGate() {
        // GIVEN: 配置类注册 featureOn=false（模拟 yml nexusai.fork.feature-on: false）
        ForkSubagentConfig.register(new ForkSubagentConfig(false, false, false));

        // WHEN: 生产构造路径 new SubagentTool()（无 Spring 上下文，@Value 不触发）
        new SubagentTool();

        // THEN: 运行时门槽反映真实配置 → 门关（对齐 CC feature('FORK_SUBAGENT') 全局语义）
        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("featureOn=false 配置下 new SubagentTool() 必须把门槽同步为关（forkSubagent.ts:33）")
            .isFalse();
    }

    @Test
    @DisplayName("默认配置（yml 未配置 nexusai.fork.*）→ {true,false,false}，new SubagentTool() 后门开")
    void defaultConfig_matchesHardcodedBaseline() {
        // GIVEN: 默认配置（ForkSubagentConfig.DEFAULTS）
        ForkSubagentConfig cfg = ForkSubagentConfig.current();

        // THEN: 字段默认值与旧硬编码基线一致（CC 发行版 FORK_SUBAGENT 启用）
        assertThat(cfg.isFeatureOn()).as("默认 feature-on = true（对齐硬编码基线）").isTrue();
        assertThat(cfg.isCoordinatorMode()).as("默认 coordinator-mode = false").isFalse();
        assertThat(cfg.isNonInteractive()).as("默认 non-interactive = false").isFalse();

        // WHEN: 默认配置下 new SubagentTool()
        new SubagentTool();

        // THEN: 门开（默认 {true,false,false} → isForkSubagentEnabled()=true）
        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("默认配置下 new SubagentTool() 后门必须开（对齐硬编码基线）")
            .isTrue();
    }

    @Test
    @DisplayName("configCoordinatorModeTrue_drivesNewSubagentToolRuntimeGate: coordinator=true 配置下门关")
    void configCoordinatorModeTrue_drivesNewSubagentToolRuntimeGate() {
        // GIVEN: 配置类注册 coordinatorMode=true（模拟 CC isCoordinatorMode()）
        ForkSubagentConfig.register(new ForkSubagentConfig(true, true, false));

        // WHEN: 生产构造路径 new SubagentTool()
        new SubagentTool();

        // THEN: 门关（forkSubagent.ts:34 isCoordinatorMode → false）
        assertThat(ForkSubagent.isForkSubagentEnabled())
            .as("coordinatorMode=true 配置下 new SubagentTool() 后门必须关")
            .isFalse();
    }
}

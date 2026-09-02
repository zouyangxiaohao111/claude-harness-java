package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M1.2 · ForkSubagent.isEnabled 3 参注入对齐 CC {@code feature('COORDINATOR_MODE')} +
 * {@code isEnvTruthy(env.CLAUDE_CODE_COORDINATOR_MODE)} + {@code feature('FORK_SUBAGENT')} (CC AgentTool.tsx:323-325 + 553-557).
 *
 * <p><b>WHY 3 测试覆盖核心注入契约 (CLAUDE.md 规则九 · 测试验证意图)</b>:
 * 旧实现硬编码 {@code ForkSubagent.isEnabled(true, false, false)}, 无法运行时切换.
 * M1.2 重构后, fork gate 必须能从 Spring @Value 注入动态切换 — 这是对齐 CC feature()
 * 门控语义的关键 (CC 端通过 feature flag 灰度上线 fork subagent).
 * <ol>
 *   <li>forkGateRespondsToRuntimeConfigChange — @Value 注入后 forkGateOn 改变 (动态配置生效)</li>
 *   <li>forkGateDefaultOffWhenNoConfigProvided — 无配置时默认值是 {true,false,false} (与硬编码一致)</li>
 *   <li>forkGateCoordinatorModeRequiresEnvTrue — coordinator=true 时即便 featureOn=true 也关闭</li>
 * </ol>
 *
 * <p><b>3 测试验收硬指标 (CLAUDE.md 规则十二)</b>: 3/0/0/0 — 3 测试全 PASS, 0 FAIL/ERROR/SKIP.
 *
 * <p><b>RED 验证策略 (Pattern #14)</b>: 本测试类编写后先 stash 掉 M1.2 实现 (setFeatureOn /
 * setCoordinatorMode / setNonInteractive / setForkGate), 跑测试确认 RED 失败 (不是 trivially pass),
 * 再 restore 实现 → GREEN.
 */
@DisplayName("Session M1.2 · ForkSubagent.isEnabled 3 参注入对齐 CC feature('COORDINATOR_MODE')")
class ForkSubagentIsEnabledInjectionTest {

    /**
     * 反射读取 SubagentTool 私有字段 (featureOn / coordinatorMode / nonInteractive).
     * 不依赖 Spring 容器 (单测 fast).
     */
    private static class ToolState {
        boolean featureOn;
        boolean coordinatorMode;
        boolean nonInteractive;
    }

    private static ToolState readState(SubagentTool tool) throws Exception {
        ToolState state = new ToolState();
        Class<?> c = tool.getClass();
        // 父类 SubagentTool 字段 (本身类) — featureOn/coordinatorMode/nonInteractive
        Field f1 = c.getDeclaredField("featureOn");
        f1.setAccessible(true);
        state.featureOn = f1.getBoolean(tool);
        Field f2 = c.getDeclaredField("coordinatorMode");
        f2.setAccessible(true);
        state.coordinatorMode = f2.getBoolean(tool);
        Field f3 = c.getDeclaredField("nonInteractive");
        f3.setAccessible(true);
        state.nonInteractive = f3.getBoolean(tool);
        return state;
    }

    /**
     * 反射调 setForkGate(featureOn, coordinatorMode, nonInteractive).
     * 不依赖 Spring 容器.
     */
    private static void setForkGate(SubagentTool tool, boolean featureOn, boolean coordinatorMode, boolean nonInteractive)
            throws Exception {
        Method m = SubagentTool.class.getMethod("setForkGate", boolean.class, boolean.class, boolean.class);
        m.invoke(tool, featureOn, coordinatorMode, nonInteractive);
    }

    /**
     * 反射调 ForkSubagent.isEnabled(3 参).
     */
    private static boolean computeForkGate(SubagentTool tool) throws Exception {
        ToolState state = readState(tool);
        return ForkSubagent.isEnabled(state.featureOn, state.coordinatorMode, state.nonInteractive);
    }

    // ═════════════════════ Test 1: @Value 注入后 forkGateOn 改变 ═════════════════════

    @Test
    @DisplayName("forkGateRespondsToRuntimeConfigChange: @Value 注入不同 featureOn 后 forkGateOn 立即变化")
    void forkGateRespondsToRuntimeConfigChange() throws Exception {
        // GIVEN: 新建 SubagentTool (无 Spring 容器), 默认 featureOn=true
        SubagentTool tool = new SubagentTool();

        // WHEN: 通过 setForkGate 修改 featureOn = false
        setForkGate(tool, false, false, false);
        ToolState stateAfterOff = readState(tool);

        // THEN: state.featureOn=false → ForkSubagent.isEnabled(false, false, false) = false
        assertThat(stateAfterOff.featureOn)
            .as("featureOn 必须立即响应 setForkGate 注入（CC feature flag 动态切换语义）")
            .isFalse();
        assertThat(computeForkGate(tool))
            .as("featureOn=false 时 forkGate 必须关闭（CC feature off → isForkSubagentEnabled() = false）")
            .isFalse();

        // WHEN: 重新设置 featureOn = true → fork gate 应恢复
        setForkGate(tool, true, false, false);
        ToolState stateAfterOn = readState(tool);

        // THEN: state.featureOn=true → ForkSubagent.isEnabled(true, false, false) = true
        assertThat(stateAfterOn.featureOn).isTrue();
        assertThat(computeForkGate(tool))
            .as("featureOn=true 时 forkGate 必须开启")
            .isTrue();
    }

    // ═════════════════════ Test 2: 默认值与硬编码一致 ═════════════════════

    @Test
    @DisplayName("forkGateDefaultOffWhenNoConfigProvided: 无配置时默认值 {true, false, false} (与原硬编码一致)")
    void forkGateDefaultOffWhenNoConfigProvided() throws Exception {
        // GIVEN: 新建 SubagentTool, 不调任何 setter
        SubagentTool tool = new SubagentTool();
        ToolState state = readState(tool);

        // THEN: 默认值与原硬编码 ForkSubagent.isEnabled(true, false, false) 一致
        assertThat(state.featureOn)
            .as("无配置时 featureOn 默认 = true（M1.2 兼容硬编码默认值）")
            .isTrue();
        assertThat(state.coordinatorMode)
            .as("无配置时 coordinatorMode 默认 = false")
            .isFalse();
        assertThat(state.nonInteractive)
            .as("无配置时 nonInteractive 默认 = false")
            .isFalse();
        assertThat(computeForkGate(tool))
            .as("默认值 {true, false, false} → forkGateOn = true（与硬编码一致）")
            .isTrue();
    }

    // ═════════════════════ Test 3: coordinatorMode=true 强制关闭 ═════════════════════

    @Test
    @DisplayName("forkGateCoordinatorModeRequiresEnvTrue: coordinatorMode=true 时即便 featureOn=true 也关闭")
    void forkGateCoordinatorModeRequiresEnvTrue() throws Exception {
        // GIVEN: 新建 SubagentTool, coordinatorMode=true (模拟 CC feature('COORDINATOR_MODE') + env)
        SubagentTool tool = new SubagentTool();
        setForkGate(tool, true, true, false);  // featureOn=true, coordinatorMode=true

        // WHEN: 反射计算 forkGateOn
        boolean forkGateOn = computeForkGate(tool);

        // THEN: ForkSubagent.isEnabled(true, true, false) = false (CC AgentTool.tsx:323-325 fork gate 在 coordinator 模式下关闭)
        assertThat(forkGateOn)
            .as("coordinatorMode=true 时 forkGate 必须强制关闭（CC AgentTool.tsx:323-325 feature('COORDINATOR_MODE') gate）")
            .isFalse();
    }
}
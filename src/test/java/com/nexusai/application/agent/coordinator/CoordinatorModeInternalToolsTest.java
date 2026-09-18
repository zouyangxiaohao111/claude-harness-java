package com.nexusai.application.agent.coordinator;

import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 缺件补齐] INTERNAL_WORKER_TOOLS 字符串正确性 + 常量单一权威 · 对齐 CC
 * coordinator/coordinatorMode.ts:29-34.
 *
 * <p><b>WHY (意图验证，不是行为验证)</b>：{@link CoordinatorMode#INTERNAL_WORKER_TOOLS} 的用途是
 * 从 worker 工具清单里<b>做减法</b> —— {@code getCoordinatorUserContext} 遍历
 * {@code AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS}，凡命中该集合的项被剔除，剩下的 join 成
 * {@code workerToolsContext} 发给模型（coordinatorMode.ts:88-95 同构）。
 *
 * <p>减法集合是「按字符串相等」匹配的：集合里写错一个字面量，<b>不会抛异常、不会告警</b> ——
 * 只是那一项静默漏删，于是模型看到的 worker 工具清单里多出一项本不该出现的内部工具
 * （如 StructuredOutput）。这正是本文件存在的理由：把「集合成员的字符串 == 真源常量」钉死，
 * 一旦有人再写死字面量、或常量改名而集合没跟着改，测试立刻变红。
 *
 * <p><b>历史缺陷</b>：本测试建立前，第 4 项写成 {@code "SyntheticOutput"}（CC 早期旧名），
 * 而真名是 {@code "StructuredOutput"}（{@link ToolNameConstants#SYNTHETIC_OUTPUT_TOOL_NAME}，
 * 对齐 CC SyntheticOutputTool.ts:20 {@code SYNTHETIC_OUTPUT_TOOL_NAME = 'StructuredOutput'}；
 * CC coordinatorMode.ts:33 用的正是该常量而非字面量）⇒ 减法漏删一项。
 *
 * <p><b>变异点</b>：把 {@code INTERNAL_WORKER_TOOLS} 任一项改回字面量错值（例如第 4 项
 * 改回 {@code "SyntheticOutput"}）→ {@link #internalWorkerToolsMatchToolNameConstants()} 变红。
 */
class CoordinatorModeInternalToolsTest {

    // ─────────── 1. 第 4 项必须是 StructuredOutput（不是 SyntheticOutput） ───────────

    @Test
    @DisplayName("INTERNAL_WORKER_TOOLS 含 'StructuredOutput' · 对齐 CC SYNTHETIC_OUTPUT_TOOL_NAME")
    void internalWorkerToolsContainsStructuredOutput() {
        assertThat(CoordinatorMode.INTERNAL_WORKER_TOOLS)
            .as("worker 工具减法集合必须含真名 StructuredOutput，否则该项漏删、模型可见工具清单多一项")
            .contains(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)
            .contains("StructuredOutput");
    }

    @Test
    @DisplayName("INTERNAL_WORKER_TOOLS 不含旧名 'SyntheticOutput'（CC 早期旧名，非真名）")
    void internalWorkerToolsDoesNotContainLegacySyntheticOutput() {
        assertThat(CoordinatorMode.INTERNAL_WORKER_TOOLS)
            .as("'SyntheticOutput' 是 CC 早期旧名，工具注册表里不存在该名 —— 留在集合里就是一句永不命中的空断言")
            .doesNotContain("SyntheticOutput");
    }

    // ─────────── 2. 每一项都等于 ToolNameConstants 对应常量（防再写死字面量） ───────────

    @Test
    @DisplayName("INTERNAL_WORKER_TOOLS 每项 == ToolNameConstants 对应常量（单一权威）")
    void internalWorkerToolsMatchToolNameConstants() {
        assertThat(CoordinatorMode.INTERNAL_WORKER_TOOLS)
            .as("集合成员必须引用 ToolNameConstants 常量；任一项写死字面量/常量改名不同步 → 减法静默漏删")
            .containsExactlyInAnyOrder(
                ToolNameConstants.TEAM_CREATE_TOOL_NAME,
                ToolNameConstants.TEAM_DELETE_TOOL_NAME,
                ToolNameConstants.SEND_MESSAGE_TOOL_NAME,
                ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
    }

    @Test
    @DisplayName("集合规模 = 4（CC coordinatorMode.ts:29-34 四项）")
    void internalWorkerToolsSizeIsFour() {
        assertThat(CoordinatorMode.INTERNAL_WORKER_TOOLS).hasSize(4);
    }

    // ─────────── 3. 减法语义：四个真名都必须被 isInternalWorkerTool 判为内部 ───────────

    @Test
    @DisplayName("isInternalWorkerTool 对四个真名均 true · 对普通 worker 工具 false")
    void isInternalWorkerToolMatchesRealNames() {
        assertThat(CoordinatorMode.isInternalWorkerTool(ToolNameConstants.TEAM_CREATE_TOOL_NAME)).isTrue();
        assertThat(CoordinatorMode.isInternalWorkerTool(ToolNameConstants.TEAM_DELETE_TOOL_NAME)).isTrue();
        assertThat(CoordinatorMode.isInternalWorkerTool(ToolNameConstants.SEND_MESSAGE_TOOL_NAME)).isTrue();
        assertThat(CoordinatorMode.isInternalWorkerTool(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)).isTrue();

        assertThat(CoordinatorMode.isInternalWorkerTool("SyntheticOutput"))
            .as("旧名不命中，恰是泄漏发生时的表现")
            .isFalse();
        assertThat(CoordinatorMode.isInternalWorkerTool("Bash")).isFalse();
        assertThat(CoordinatorMode.isInternalWorkerTool(null)).isFalse();
    }
}

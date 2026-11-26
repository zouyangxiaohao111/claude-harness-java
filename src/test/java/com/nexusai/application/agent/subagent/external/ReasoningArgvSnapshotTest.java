package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推理强度 → argv 的**逐字节快照**回归。
 *
 * <p><b>WHY</b>：2026-11-26 把 switch 换成策略表，策略替换最容易「看着一样、实际差一个引号/一个空格」。
 * 尤其 codex 的 {@code -c model_reasoning_effort="high"} —— 引号是参数值的一部分，
 * 少了它 CLI 不报错、只是静默忽略档位。这里把五家（含不支持的 claude）一次锁死。
 */
@DisplayName("[external-agent] 推理强度 argv 快照（策略表替换防漂移）")
class ReasoningArgvSnapshotTest {

    @Test
    @DisplayName("五种 style 的 argv 逐字节快照")
    void allStylesSnapshot() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(ExternalRuntimeCatalog.find("claude"), "high"))
                .as("claude 不支持推理强度")
                .isEmpty();

        assertThat(ExternalRuntimeCatalog.reasoningArgs(ExternalRuntimeCatalog.find("codex"), "high"))
                .as("codex 的引号必须保留，否则静默无效")
                .containsExactly("-c", "model_reasoning_effort=\"high\"");

        assertThat(ExternalRuntimeCatalog.reasoningArgs(ExternalRuntimeCatalog.find("pi"), "high"))
                .containsExactly("--thinking", "high");

        assertThat(ExternalRuntimeCatalog.reasoningArgs(ExternalRuntimeCatalog.find("opencode"), "high"))
                .containsExactly("--variant", "high");

        assertThat(ExternalRuntimeCatalog.reasoningArgs(ExternalRuntimeCatalog.find("agy"), "high"))
                .containsExactly("--effort", "high");
    }
}
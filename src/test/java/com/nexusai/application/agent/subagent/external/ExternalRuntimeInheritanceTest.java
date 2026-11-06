package com.nexusai.application.agent.subagent.external;

import com.nexusai.application.agent.subagent.AgentDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行时预设解析回归（「只写 runtime 名字就能跑」）。
 *
 * <p>WHY（真正的用户痛点）：没有继承时，用户想「还是用 claude，但换个 exe / 加个代理」，
 * 必须把整条 argv 抄一遍 —— 抄错一个参数 CLI 就静默挂住或返回空。
 * 有了继承只需写差异。这里锁死继承的三条语义：
 * <ol>
 *   <li>只写 {@code runtime} → argv 完全来自预设</li>
 *   <li>{@code bin} 覆盖预设的 bin，但 args 仍继承</li>
 *   <li>{@code argsPrefix} 插在最前，{@code env} 原样带出</li>
 * </ol>
 */
@DisplayName("[external-agent] 运行时预设解析（只写 runtime 即可）")
class ExternalRuntimeInheritanceTest {

    private static AgentDefinition.ExternalAgentDefinition.Builder minimal(String type, String runtime) {
        return AgentDefinition.ExternalAgentDefinition.builder(type, "desc", "userSettings", "")
                .runtime(runtime);
    }

    @Test
    @DisplayName("只写 runtime ⇒ argv 用预设（不必抄 args）")
    void runtimeOnlyInheritsArgs() {
        var def = minimal("my-claude", "claude").build();

        var spec = ExternalRuntimeSpec.of(def, null, null);

        assertThat(spec.argv())
                .containsExactly("-p", "{prompt}", "--output-format", "stream-json", "--verbose");
        assertThat(spec.streamFormat()).isEqualTo("claude-stream-json");
    }

    @Test
    @DisplayName("只写 runtime（不写 args）⇒ 合法，argv 来自扫描到的预设")
    void runtimeOnlyIsValid() {
        // 用户 2026-11-04 简化口径：「选择 claude / opencode 等就行，不用配置 bin —— 扫描就有」
        var def = minimal("only-name", "claude").build();
        assertThat(def.args()).isEmpty();

        var spec = ExternalRuntimeSpec.of(def, null, null);
        assertThat(spec.argv())
                .containsExactly("-p", "{prompt}", "--output-format", "stream-json", "--verbose");
        assertThat(spec.streamFormat()).isEqualTo("claude-stream-json");
    }

    @Test
    @DisplayName("写了 args 但不含 {prompt} ⇒ 报错（并提示「只想用预设就删掉 args」）")
    void explicitArgsWithoutPlaceholderRejected() {
        assertThatThrownBy(() -> minimal("bad", "claude").args(List.of("-p", "--output-format", "text")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{prompt}");
    }

    @Test
    @DisplayName("argsPrefix 插在内置 argv 之前（前缀开关必须在位置参数前）")
    void argsPrefixComesFirst() {
        var def = minimal("proxy-claude", "claude")
                .argsPrefix(List.of("--profile", "demo"))
                .build();

        var spec = ExternalRuntimeSpec.of(def, null, null);

        assertThat(spec.argv()).startsWith("--profile", "demo");
        assertThat(spec.argv()).contains("-p", "{prompt}");
    }

    @Test
    @DisplayName("显式 args 覆盖预设 args，但 streamFormat 仍继承")
    void explicitArgsOverridePresetButFormatInherits() {
        var def = minimal("custom-claude", "claude")
                .args(List.of("--my-flag", "{prompt}"))
                .build();

        var spec = ExternalRuntimeSpec.of(def, null, null);

        assertThat(spec.argv()).containsExactly("--my-flag", "{prompt}");
        assertThat(spec.streamFormat()).as("未显式写 streamFormat ⇒ 沿用预设").isEqualTo("claude-stream-json");
    }

    @Test
    @DisplayName("未配置连接信息 ⇒ 不注入任何环境变量（CLI 用自己的默认）")
    void unconfiguredInjectsNothing() {
        ExternalRuntimeEnvStore.replace(Map.of());
        var def = minimal("proxy-claude", "claude").build();

        var spec = ExternalRuntimeSpec.of(def, null, null);

        // 关键：空 map 而不是「注入空字符串」—— 空串会被 CLI 当成显式清空
        assertThat(spec.env()).isEmpty();
    }

    @Test
    @DisplayName("配了 baseUrl + key ⇒ 用该 CLI 认的变量名注入（不与预设名混用）")
    void configuredOverlayUsesPresetEnvNames() {
        ExternalRuntimeEnvStore.replace(Map.of("claude",
                new ExternalRuntimeEnvStore.Overlay(null, "https://proxy.example/v1", "sk-test")));
        try {
            var def = minimal("proxy-claude", "claude").build();
            var spec = ExternalRuntimeSpec.of(def, null, null);

            assertThat(spec.env()).containsEntry("ANTHROPIC_BASE_URL", "https://proxy.example/v1");
            // __default__（旧单 key 列）映射到 apiKeyEnvs 的第一个，现与 OpenDesign 一致是 ANTHROPIC_API_KEY
            assertThat(spec.env()).containsEntry("ANTHROPIC_API_KEY", "sk-test");
            // 没填 configDir ⇒ 不注入该变量（三项独立）
            assertThat(spec.env()).doesNotContainKey("CLAUDE_CONFIG_DIR");
        } finally {
            ExternalRuntimeEnvStore.replace(Map.of());
        }
    }

    @Test
    @DisplayName("日志摘要必须脱敏（只留 key，不留值）")
    void envLogIsRedacted() {
        ExternalRuntimeEnvStore.replace(Map.of("claude",
                new ExternalRuntimeEnvStore.Overlay(null, null, "sk-super-secret")));
        try {
            var def = minimal("k", "claude").build();
            var spec = ExternalRuntimeSpec.of(def, null, null);

            assertThat(spec.envKeysForLog()).containsEntry("ANTHROPIC_API_KEY", "***");
            assertThat(spec.envKeysForLog().values()).doesNotContain("sk-super-secret");
        } finally {
            ExternalRuntimeEnvStore.replace(Map.of());
        }
    }

    @Test
    @DisplayName("手写 args 时完全不走预设（全量自定义）")
    void fullyCustomArgsIgnoredPreset() {
        var def = minimal("legacy", "claude")
                .args(List.of("-p", "{prompt}", "--output-format", "text"))
                .streamFormat("plain")
                .build();

        var spec = ExternalRuntimeSpec.of(def, null, null);

        assertThat(spec.argv()).containsExactly("-p", "{prompt}", "--output-format", "text");
        assertThat(spec.streamFormat()).isEqualTo("plain");
        assertThat(spec.env()).isEmpty();
    }

    @Test
    @DisplayName("{model} / {cwd} 占位被替换，{prompt} 保留给 spawn 时替换")
    void placeholders() {
        var def = minimal("m", "claude")
                .args(List.of("-p", "{prompt}", "--model", "{model}", "--dir", "{cwd}"))
                .build();

        var spec = ExternalRuntimeSpec.of(def, "deepseek-flash", java.nio.file.Path.of("D:/tmp"));

        assertThat(spec.argv()).contains("deepseek-flash");
        assertThat(spec.argv()).contains("D:\\tmp".replace('\\', '\\'));
        // {prompt} 不能在 build 阶段就被替换掉
        assertThat(spec.argv()).contains("{prompt}");
        assertThat(spec.buildArgv("hello")).contains("hello");
    }

    @Test
    @DisplayName("未知运行时且无自定义 argv ⇒ 返回人话原因（不抛，交给调用方回落）")
    void unknownRuntimeReportsReason() {
        var def = minimal("x", "no-such-cli").build();

        var r = ExternalRuntimeSpec.tryResolve(def, null, null);

        assertThat(r.isAvailable()).isFalse();
        assertThat(r.unavailableReason()).contains("no-such-cli").contains("可选");
    }

    @Test
    @DisplayName("本机没装该 CLI ⇒ 原因里给出「怎么装」的可操作提示")
    void missingBinaryReportsInstallHint() {
        // 用一个没有预设的 runtime + 显式 argv，确保不会回退到任何已装 CLI
        var def = minimal("y", "my-custom-cli")
                .bin("definitely-not-installed-cli-xyz")
                .args(List.of("-p", "{prompt}"))
                .build();

        var r = ExternalRuntimeSpec.tryResolve(def, null, null);

        assertThat(r.isAvailable()).isFalse();
        assertThat(r.unavailableReason()).contains("未找到");
    }

    @Test
    @DisplayName("显式 bin 找不到时回退到预设的可执行文件（有 runtime 时不至于全废）")
    void explicitBinFallsBackToPreset() {
        var def = minimal("z", "claude")
                .bin("definitely-not-installed-cli-xyz")
                .build();

        var r = ExternalRuntimeSpec.tryResolve(def, null, null);

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.spec().bin()).contains("claude");
    }
}

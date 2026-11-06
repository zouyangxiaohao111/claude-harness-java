package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型 / 推理强度落到 argv 的回归。
 *
 * <p>WHY 必须锁死（2026-11-04 真机踩坑）：三家 CLI 的写法完全不同，且**参数写错不报错、只是静默无效** ——
 * 用户会以为「设了 high 却没效果」，比直接报错难查得多：
 * <ul>
 *   <li>codex：{@code -c model_reasoning_effort="high"}（是 {@code -c} 配置项，不是 {@code --effort}）</li>
 *   <li>pi：{@code --thinking high}</li>
 *   <li>opencode：{@code --variant high}（按模型给）</li>
 *   <li>claude：**没有**这个开关 —— 必须不追加任何参数，而不是塞一个它忽略的值</li>
 * </ul>
 *
 * <p>另一个坑：codex 的档位是**按模型**给的（真机 {@code debug models} 实测：
 * {@code gpt-6-luna} 到 {@code max}、{@code gpt-6.1-sol} 到 {@code ultra}），
 * 所以这里**不能**用全局表做校验 —— 否则 max/ultra 会被静默丢掉。
 */
@DisplayName("[external-agent] 模型/推理强度 → argv")
class ExternalRuntimeReasoningTest {

    private static final ExternalRuntimeCatalog.RuntimePreset CODEX =
            ExternalRuntimeCatalog.find("codex");
    private static final ExternalRuntimeCatalog.RuntimePreset CLAUDE =
            ExternalRuntimeCatalog.find("claude");
    private static final ExternalRuntimeCatalog.RuntimePreset PI =
            ExternalRuntimeCatalog.find("pi");
    private static final ExternalRuntimeCatalog.RuntimePreset OPENCODE =
            ExternalRuntimeCatalog.find("opencode");

    @Test
    @DisplayName("codex 用 -c model_reasoning_effort=…（不是 --effort）")
    void codexUsesConfigOverride() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(CODEX, "high"))
                .containsExactly("-c", "model_reasoning_effort=\"high\"");
    }

    @Test
    @DisplayName("pi 用 --thinking")
    void piUsesThinkingFlag() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(PI, "medium"))
                .containsExactly("--thinking", "medium");
    }

    @Test
    @DisplayName("opencode 用 --variant")
    void opencodeUsesVariant() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(OPENCODE, "high"))
                .containsExactly("--variant", "high");
    }

    @Test
    @DisplayName("claude 没有推理开关 ⇒ 任何档位都不追加参数（不能塞一个它忽略的值）")
    void claudeNeverAddsReasoning() {
        assertThat(CLAUDE.supportsReasoning()).isFalse();
        for (String any : List.of("low", "high", "xhigh", "max", "ultra")) {
            assertThat(ExternalRuntimeCatalog.reasoningArgs(CLAUDE, any))
                    .as("claude 不支持推理强度，不该为 '%s' 追加参数", any)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("模型级档位（max/ultra）必须放行 —— 全局表里没有也要拼进去")
    void modelSpecificLevelsAreNotDropped() {
        // 真机 gpt-6-luna 支持到 max、gpt-6.1-sol 支持到 ultra，但预设全局表只是兜底展示
        assertThat(ExternalRuntimeCatalog.reasoningArgs(CODEX, "max"))
                .containsExactly("-c", "model_reasoning_effort=\"max\"");
        assertThat(ExternalRuntimeCatalog.reasoningArgs(CODEX, "ultra"))
                .containsExactly("-c", "model_reasoning_effort=\"ultra\"");
    }

    @Test
    @DisplayName("default / 空 ⇒ 不追加（沿用 CLI 自己的配置）")
    void defaultAddsNothing() {
        for (var p : List.of(CODEX, PI, OPENCODE)) {
            assertThat(ExternalRuntimeCatalog.reasoningArgs(p, "default")).isEmpty();
            assertThat(ExternalRuntimeCatalog.reasoningArgs(p, "")).isEmpty();
            assertThat(ExternalRuntimeCatalog.reasoningArgs(p, null)).isEmpty();
        }
    }

    @Test
    @DisplayName("含引号/空格等会打穿 argv 的值一律丢弃（它是要拼进 -c \"k=v\" 的）")
    void unsafeValuesAreDropped() {
        for (String bad : List.of("high; rm -rf /", "high\") -c x=\"y", "high value", "high`id`")) {
            assertThat(ExternalRuntimeCatalog.reasoningArgs(CODEX, bad))
                    .as("非法值 '%s' 必须丢弃", bad)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("模型标志各家不同：opencode 是 -m，其余是 --model")
    void modelFlagDiffersPerCli() {
        assertThat(ExternalRuntimeCatalog.modelArgs(OPENCODE, "anthropic/claude-sonnet-4-5"))
                .containsExactly("-m", "anthropic/claude-sonnet-4-5");
        assertThat(ExternalRuntimeCatalog.modelArgs(CODEX, "gpt-6.1-sol"))
                .containsExactly("--model", "gpt-6.1-sol");
        assertThat(ExternalRuntimeCatalog.modelArgs(CLAUDE, "sonnet"))
                .containsExactly("--model", "sonnet");
        assertThat(ExternalRuntimeCatalog.modelArgs(PI, "openai/gpt-5"))
                .containsExactly("--model", "openai/gpt-5");
    }

    @Test
    @DisplayName("没选模型 ⇒ 不追加 --model（让 CLI 用自己的默认）")
    void emptyModelAddsNothing() {
        for (var p : List.of(CODEX, CLAUDE, PI, OPENCODE)) {
            assertThat(ExternalRuntimeCatalog.modelArgs(p, "")).isEmpty();
            assertThat(ExternalRuntimeCatalog.modelArgs(p, "default")).isEmpty();
            assertThat(ExternalRuntimeCatalog.modelArgs(p, null)).isEmpty();
        }
    }

    @Test
    @DisplayName("能力位可信：codex/pi/opencode 支持推理，claude 不支持")
    void capabilityFlagsAreHonest() {
        assertThat(CODEX.supportsReasoning()).isTrue();
        assertThat(PI.supportsReasoning()).isTrue();
        assertThat(OPENCODE.reasoningStyle())
                .isEqualTo(ExternalRuntimeCatalog.ReasoningStyle.OPENCODE_VARIANT);
        assertThat(CLAUDE.reasoningStyle())
                .isEqualTo(ExternalRuntimeCatalog.ReasoningStyle.NONE);
    }

    @Test
    @DisplayName("探测用的 argv 必须走 argv 传 prompt（不能依赖 stdin —— 非 TTY 下会永久阻塞）")
    void probeArgvCarriesPromptSoStdinCanBeClosed() {
        // 2026-11-04 真机踩到：opencode run 在 shell 里 8s 答完，但经 ProcessBuilder
        // 不关闭 stdin 时 70s 零输出、不退出。探测层因此必须立刻 close stdin，
        // 前提是**任务确实在 argv 里**。这里锁住这个前提。
        for (var p : List.of(CODEX, CLAUDE, PI, OPENCODE)) {
            assertThat(p.args())
                    .as("%s 的预设 argv 必须含 {prompt} 占位", p.runtime())
                    .anyMatch(a -> a.contains(ExternalRuntimeSpec.PROMPT_PLACEHOLDER));
        }
    }

    @Test
    @DisplayName("key 变量名对齐 OpenDesign 的白名单（写错 = 页面填了 key 但不生效）")
    void apiKeyEnvNames() {
        // 来源：OpenDesign daemon 的 AGENT_CLI_ENV_KEYS（chunk-5PWCTNQA.mjs）。
        assertThat(CODEX.apiKeyEnvs())
                .containsExactly("CODEX_API_KEY", "OPENAI_API_KEY");
        assertThat(CLAUDE.apiKeyEnvs())
                .containsExactly("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN");
        // ⚠️ pi / opencode **不给 key 输入框**（与 OpenDesign 一致，用户 2026-11-04 拍板）：
        // 这两家的变量名随 provider 变（pi 文档列了 20+ 家），写死任意一个都会造成
        // 「页面填了但不生效」；且 base URL 只存在于各自配置文件、不是环境变量。
        // ⇒ 必须是空列表，前端据此渲染「请用 CLI 自己的 /login」提示。
        assertThat(OPENCODE.apiKeyEnvs()).isEmpty();
        assertThat(PI.apiKeyEnvs()).isEmpty();
    }
}

package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.nexusai.application.agent.permission.classifier.YoloClassifierImpl.modelSupportsAutoMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * [AM-CC-20260824] YoloClassifierImpl.modelSupportsAutoMode 彻底对齐 CC 当前版测试 ·
 * CC betas.ts:160-162 modelSupportsAutoMode。
 *
 * <p>WHY（意图验证）：AutoModeGate "model" 判定用 {@code classifier.isAvailable()}
 * （getUnavailableReason），旧实现是 CC 旧版五层链（provider 仅 firstParty / allowModels /
 * ant denylist / external allowlist）；CC 当前版已简化为
 * {@code modelSupportsAutoMode(_model) { return feature('TRANSCRIPT_CLASSIFIER') ? true : false }}
 * —— 只查 feature 门，{@code _model} 参数忽略。本测试锁定该语义，保证未来回归不会复活
 * provider/model 判定（deepseek 等 openai_compatible 提供方随 feature 门自然放行）。
 *
 * <p>测试调用包级静态纯函数 {@code modelSupportsAutoMode}（参数显式，不依赖环境变量）。
 */
@DisplayName("[AM-CC] modelSupportsAutoMode 彻底对齐 CC 当前版（betas.ts:160-162 只查 feature 门）")
class YoloClassifierModelSupportsAutoModeTest {

    @Test
    @DisplayName("feature('TRANSCRIPT_CLASSIFIER') 关闭 → 恒 false（任何 model/provider/userType）")
    void featureOff_alwaysFalse() {
        // CC :161-162：feature 关 → return false（不看 _model）
        assertThat(modelSupportsAutoMode("claude-sonnet-4-6-20250805", true, "anthropic",
                Set.of(), false, null))
            .as("feature 关闭 → 恒 false，即使 provider/model 都支持")
            .isFalse();
        // 参数无关：openai_compatible / 任意 model / ant 亦然
        assertThat(modelSupportsAutoMode("deepseek/deepseek-v4-flash", true, "openai_compatible",
                Set.of("deepseek/deepseek-v4-flash"), false, "ant"))
            .as("feature 关闭 → 参数无关恒 false")
            .isFalse();
    }

    @Test
    @DisplayName("feature('TRANSCRIPT_CLASSIFIER') 开启 → 恒 true（不看 provider，deepseek 自然放行）")
    void featureOn_alwaysTrue() {
        // CC 当前版：feature 开 → return true（_model 忽略；旧五层链「仅 anthropic」已删，
        //   2026-08-24 用户拍板彻底对齐 CC，deepseek 无需「偏离 CC」标记即随 feature 门放行）
        assertThat(modelSupportsAutoMode("deepseek/deepseek-v4-flash", true, "openai_compatible",
                Set.of(), true, null))
            .as("openai_compatible provider（deepseek）→ feature 门放行（对齐 CC 当前版）")
            .isTrue();
        assertThat(modelSupportsAutoMode("claude-sonnet-4-6-20250805", true, "anthropic",
                Set.of(), true, null))
            .as("anthropic provider → 依然 true")
            .isTrue();
        assertThat(modelSupportsAutoMode("claude-3-5-sonnet-20241022", true, "openai_sdk",
                Set.of(), true, "ant"))
            .as("ant + 任意 provider → 只查 feature 门 → true")
            .isTrue();
    }

    @Test
    @DisplayName("参数完全忽略：provider 未接线 / null model / allowModels / userType 不影响结果（对齐 CC _model 忽略）")
    void paramsIgnored_onlyFeatureGateMatters() {
        // CC :160 modelSupportsAutoMode(_model)——_model 参数连读都没读；Java 6 参签名保留兼容，
        //   除 feature 门外全部忽略。
        assertThat(modelSupportsAutoMode(null, false, null, Set.of(), true, null))
            .as("providerFactory 未接线 + null model → feature 门决定 → true")
            .isTrue();
        assertThat(modelSupportsAutoMode(null, false, null, Set.of(), false, null))
            .as("providerFactory 未接线 + null model → feature 关闭 → false")
            .isFalse();
    }
}

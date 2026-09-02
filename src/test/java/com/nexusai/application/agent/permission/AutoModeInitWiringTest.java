package com.nexusai.application.agent.permission;

import com.nexusai.infra.util.AutoModeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [WF-8 · OPD-AM-01 + OD-WF1-CFG-04] auto 初始接线语义契约测试 · 对齐 CC
 * {@code main.tsx:1409}（setAutoModeFlagCli）+ {@code permissionSetup.ts:807}（setAutoModeActive）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：LlmAgentLoop.doRun 在启动/初始化路径对
 * {@code initialPermissionModeFromCLI} 的等价接线 —— feature 门 + 解析结果驱动两处静态副作用：
 * CLI flag（opt-in 意图）与 auto 激活态（plan+active 入口门据此放行）。本测试锁定该接线的<b>不变量</b>
 * （用真实 {@link InitialPermissionModeResolver} + 真实 {@link AutoModeState}）：
 * <ul>
 *   <li>feature 开 + 初始解析为 auto → 会话携带 auto 意图（flag 置位 + 激活）——CC
 *       {@code hasAutoModeOptInAnySource:1363} 据此判 opt-in、{@code isAutoModeEntry} plan+active 据此放行；</li>
 *   <li>feature 关 → 无任何 auto 副作用（CC main.tsx:1399 整块跳过 / :807 feature 门）；</li>
 *   <li>feature 开 + circuit broken + CLI auto → 解析折叠 default（不激活），但 CLI 意图 flag 仍置位
 *       （CC :731-738 折叠 mode、main.tsx:1409 保留意图供 verifyAutoModeGateAccess 通知）。</li>
 * </ul>
 */
@DisplayName("[WF-8] auto 初始接线：CLI flag + setAutoModeActive 语义契约")
class AutoModeInitWiringTest {

    @BeforeEach
    @AfterEach
    void reset() {
        AutoModeState.resetForTesting();
    }

    /** LlmAgentLoop.doRun 等价接线逻辑（feature 门 + 解析结果驱动静态副作用，逐行镜像）。 */
    private void wireInitialAuto(InitialPermissionModeResolver.Input input,
                                 InitialPermissionModeResolver.Config config) {
        InitialPermissionModeResolver.Result result =
            InitialPermissionModeResolver.resolve(input, config);
        boolean classifierOn = config.transcriptClassifierFeature().getAsBoolean();
        if (classifierOn) {
            // CC main.tsx:1409 —— autoModeFlagCli = "用户本会话 intend auto" 信号。
            boolean autoModeIntent = "auto".equals(input.permissionModeCli())
                || result.mode() == PermissionMode.AUTO
                || (input.permissionModeCli() == null
                    && "auto".equals(input.settingsDefaultMode()));
            if (autoModeIntent) {
                AutoModeState.setAutoModeFlagCli(true);
            }
            // CC permissionSetup.ts:807 —— 尾部对 auto 结果置激活态。
            if (result.mode() == PermissionMode.AUTO) {
                AutoModeState.setAutoModeActive(true);
            }
        }
    }

    @Test
    @DisplayName("feature 开 + CLI --permission-mode auto → CLI flag + 激活态（CC main.tsx:1409 + :807）")
    void cliAutoWithFeatureOnCarriesIntent() {
        var input = new InitialPermissionModeResolver.Input("auto", false, null, false);
        var config = new InitialPermissionModeResolver.Config(
            null, () -> true, () -> AutoModeState.isAutoModeCircuitBroken(), false);

        wireInitialAuto(input, config);

        assertThat(AutoModeState.getAutoModeFlagCli())
            .as("main.tsx:1409 —— permissionModeCli==='auto' → 置位 opt-in 意图")
            .isTrue();
        assertThat(AutoModeState.isAutoModeActive())
            .as("permissionSetup.ts:807 —— 初始解析 AUTO → 置激活态")
            .isTrue();
    }

    @Test
    @DisplayName("feature 关 + CLI auto → 无任何 auto 副作用（CC main.tsx:1399 整块跳过 / :807 feature 门）")
    void featureOffNoAutoSideEffects() {
        var input = new InitialPermissionModeResolver.Input("auto", false, null, false);
        var config = InitialPermissionModeResolver.Config.defaults(); // classifier 门关

        wireInitialAuto(input, config);

        assertThat(AutoModeState.getAutoModeFlagCli()).isFalse();
        assertThat(AutoModeState.isAutoModeActive()).isFalse();
    }

    @Test
    @DisplayName("feature 开 + circuit broken + CLI auto → 折叠 default（不激活），但 CLI 意图保留（CC :731-738）")
    void circuitBrokenCollapsesModeButKeepsIntent() {
        AutoModeState.setAutoModeCircuitBroken(true);
        var input = new InitialPermissionModeResolver.Input("auto", false, null, false);
        var config = new InitialPermissionModeResolver.Config(
            null, () -> true, () -> AutoModeState.isAutoModeCircuitBroken(), false);

        wireInitialAuto(input, config);

        assertThat(AutoModeState.getAutoModeFlagCli())
            .as("main.tsx:1409 —— permissionModeCli==='auto' 恒置意图（断路器只折叠 mode 不吞意图）")
            .isTrue();
        assertThat(AutoModeState.isAutoModeActive())
            .as("permissionSetup.ts:807 —— result.mode 折叠 default → 不激活")
            .isFalse();
    }

    @Test
    @DisplayName("feature 开 + settings.defaultMode=auto（无 CLI）→ 置位意图 + 激活（CC :763-768 + :807）")
    void settingsDefaultAutoCarriesIntent() {
        var input = new InitialPermissionModeResolver.Input(null, false, "auto", false);
        var config = new InitialPermissionModeResolver.Config(
            null, () -> true, () -> AutoModeState.isAutoModeCircuitBroken(), false);

        wireInitialAuto(input, config);

        assertThat(AutoModeState.getAutoModeFlagCli())
            .as("main.tsx:1409 —— 无 CLI 且 settings.defaultMode==auto → 置位意图")
            .isTrue();
        assertThat(AutoModeState.isAutoModeActive())
            .as("permissionSetup.ts:807 —— 初始解析 AUTO → 置激活态")
            .isTrue();
    }
}

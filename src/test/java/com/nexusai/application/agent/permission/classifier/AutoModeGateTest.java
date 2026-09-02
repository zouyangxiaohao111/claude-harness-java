package com.nexusai.application.agent.permission.classifier;

import com.nexusai.infra.util.AutoModeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AutoModeGate 三态对齐测试 · CC permissionSetup.ts:1311/1315-1320/1328-1333 + settings.ts:896-911.
 *
 * <p><b>WHY（意图验证）</b>: OPD-77 对齐 —— getEnabledState() 补 "opt-in" 第三态
 * （CC 'opt-in' = 仅显式 opt-in 可用，:1308），hasOptIn() 对齐 hasAutoModeOptInAnySource
 * （CLI flag || 可信 userSettings skipAutoPermissionPrompt，显式排除项目目录配置防 RCE）。
 *
 * <p>可控输入注入：包级 {@code setOptInSupplier} 完全覆盖默认源判定（不碰静态
 * AutoModeState / 用户真实文件）；默认源路径经 {@code setUserSettingsPath} 指到 @TempDir。
 * 静态 CLI flag 用 {@link AutoModeState#resetForTesting()} 前后清理，避免测试间污染。
 */
@DisplayName("AutoModeGate 三态（enabled/disabled/opt-in）")
class AutoModeGateTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetStaticState() {
        AutoModeState.resetForTesting();
    }

    // ── getEnabledState 三态 ──────────────────────────────────────────────

    @Test
    @DisplayName("配置启用 → enabled（无 opt-in 也成立）")
    void configuredEnabledReturnsEnabled() {
        AutoModeGate gate = new AutoModeGate(true);
        gate.setOptInSupplier(() -> false);

        assertThat(gate.getEnabledState()).isEqualTo("enabled");
        assertThat(gate.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("配置禁用 + 无 opt-in → disabled（默认态）")
    void disabledWithoutOptInReturnsDisabled() {
        AutoModeGate gate = new AutoModeGate(false);
        gate.setOptInSupplier(() -> false);

        assertThat(gate.getEnabledState()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("配置禁用 + 显式 opt-in → opt-in（第三态）")
    void disabledWithOptInReturnsOptIn() {
        AutoModeGate gate = new AutoModeGate(false);
        gate.setOptInSupplier(() -> true);

        assertThat(gate.getEnabledState()).isEqualTo("opt-in");
    }

    @Test
    @DisplayName("配置启用时 opt-in 信号不改变 enabled（配置优先）")
    void enabledWinsOverOptInSignal() {
        AutoModeGate gate = new AutoModeGate(true);
        gate.setOptInSupplier(() -> true);

        assertThat(gate.getEnabledState()).isEqualTo("enabled");
    }

    // ── hasOptIn · 注入源（测试可控输入）─────────────────────────────────

    @Test
    @DisplayName("注入 supplier=false 覆盖 CLI flag=true 的默认源")
    void injectedSupplierOverridesDefaultSources() {
        AutoModeGate gate = new AutoModeGate(false);
        AutoModeState.setAutoModeFlagCli(true);   // 默认源为 true
        gate.setOptInSupplier(() -> false);       // 注入覆盖 → false

        assertThat(gate.hasOptIn()).isFalse();
        assertThat(gate.getEnabledState()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("注入 supplier=true → hasOptIn true + 状态 opt-in")
    void injectedSupplierTrueEnablesOptIn() {
        AutoModeGate gate = new AutoModeGate(false);
        gate.setOptInSupplier(() -> true);

        assertThat(gate.hasOptIn()).isTrue();
    }

    // ── hasOptIn · 默认源 = CLI flag（AutoModeState 静态）─────────────────

    @Test
    @DisplayName("CLI flag 置位 → hasOptIn true（对齐 CC getAutoModeFlagCli 分支）")
    void cliFlagAloneYieldsOptIn() {
        AutoModeGate gate = new AutoModeGate(false);
        AutoModeState.setAutoModeFlagCli(true);

        assertThat(gate.hasOptIn()).isTrue();
        assertThat(gate.getEnabledState()).isEqualTo("opt-in");
    }

    @Test
    @DisplayName("CLI flag 未置位 + userSettings 缺失 → hasOptIn false")
    void noSourcesYieldsNoOptIn() {
        AutoModeGate gate = new AutoModeGate(false);
        gate.setUserSettingsPath(tempDir.resolve("no-such-settings.json"));

        assertThat(gate.hasOptIn()).isFalse();
        assertThat(gate.getEnabledState()).isEqualTo("disabled");
    }

    // ── hasOptIn · 默认源 = 可信 userSettings 文件（skipAutoPermissionPrompt）──

    @Test
    @DisplayName("userSettings skipAutoPermissionPrompt=true → hasOptIn true + 状态 opt-in")
    void userSettingsOptInFileYieldsOptIn() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "{\"skipAutoPermissionPrompt\": true}");
        AutoModeGate gate = new AutoModeGate(false);
        gate.setUserSettingsPath(settings);

        assertThat(gate.hasOptIn()).isTrue();
        assertThat(gate.getEnabledState()).isEqualTo("opt-in");
    }

    @Test
    @DisplayName("userSettings skipAutoPermissionPrompt=false → hasOptIn false")
    void userSettingsOptInFalseYieldsNoOptIn() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "{\"skipAutoPermissionPrompt\": false}");
        AutoModeGate gate = new AutoModeGate(false);
        gate.setUserSettingsPath(settings);

        assertThat(gate.hasOptIn()).isFalse();
    }

    @Test
    @DisplayName("userSettings 缺键 / 损坏 JSON / 非 boolean → hasOptIn false（lenient）")
    void malformedUserSettingsYieldsNoOptIn() throws Exception {
        Path noKey = tempDir.resolve("no-key.json");
        Files.writeString(noKey, "{\"permissions\": {}}");
        AutoModeGate gate = new AutoModeGate(false);
        gate.setUserSettingsPath(noKey);
        assertThat(gate.hasOptIn()).isFalse();

        Path corrupt = tempDir.resolve("corrupt.json");
        Files.writeString(corrupt, "{not json");
        gate.setUserSettingsPath(corrupt);
        assertThat(gate.hasOptIn()).isFalse();

        Path nonBoolean = tempDir.resolve("non-boolean.json");
        Files.writeString(nonBoolean, "{\"skipAutoPermissionPrompt\": \"yes\"}");
        gate.setUserSettingsPath(nonBoolean);
        assertThat(gate.hasOptIn()).isFalse();
    }

    // ── 防 RCE：默认读取路径 = 用户目录级，非项目目录 ──────────────────────

    @Test
    @DisplayName("默认 userSettings 路径位于 user.home 下（不读项目目录配置）")
    void defaultSettingsPathIsUserLevel() {
        // 防 RCE：默认只读用户级文件 <user.home>/.nexusai/settings.json；
        // 项目目录下配置（nexusai.home 默认 cwd 的 settings.json / settings.local.json）不参与读取
        assertThat(AutoModeGate.DEFAULT_USER_SETTINGS_PATH.toString())
            .startsWith(Paths.get(System.getProperty("user.home")).toString());
        assertThat(AutoModeGate.DEFAULT_USER_SETTINGS_PATH.getFileName().toString())
            .isEqualTo("settings.json");
        assertThat(AutoModeGate.DEFAULT_USER_SETTINGS_PATH.toString())
            .doesNotContain("settings.local.json");
    }

    // ── 电路断路器（OPD-AM-01 · GAP-AM-01 落地）──────────────────────────

    @Test
    @DisplayName("断路器状态置位 → isEnabled false + 原因 circuit-breaker（CC isAutoModeGateEnabled:1284）")
    void circuitBrokenStateDisablesGate() {
        AutoModeState.setAutoModeCircuitBroken(true);
        AutoModeGate gate = new AutoModeGate(true);
        gate.setOptInSupplier(() -> false);

        assertThat(gate.isEnabled())
            .as("CC :1284 —— circuit broken → 恒 false，即使配置 enabled=true")
            .isFalse();
        assertThat(gate.getUnavailableReason(null))
            .as("CC getAutoModeUnavailableReason:1296-1298 —— 断路器原因联合值")
            .isEqualTo("circuit-breaker");
        assertThat(gate.getEnabledState())
            .as("getEnabledState 读原始配置三态，不受断路器影响（CC getAutoModeEnabledState:1328-1333）")
            .isEqualTo("enabled");
    }

    @Test
    @DisplayName("@PostConstruct 写入：circuit-breaker 配置开启 → AutoModeState 熔断（CC permissionSetup.ts:1099）")
    void applyCircuitBreakerWritesState() {
        AutoModeGate gate = new AutoModeGate(true);
        gate.setCircuitBreakerEnabled(true);

        gate.applyCircuitBreaker();

        assertThat(AutoModeState.isAutoModeCircuitBroken())
            .as("CC :1099 setAutoModeCircuitBroken(enabledState==='disabled' || disabledBySettings)")
            .isTrue();
        assertThat(gate.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("circuit-breaker 配置关闭 → @PostConstruct 不熔断（默认不激活）")
    void applyCircuitBreakerOffLeavesStateUnbroken() {
        AutoModeGate gate = new AutoModeGate(true);
        gate.setCircuitBreakerEnabled(false);

        gate.applyCircuitBreaker();

        assertThat(AutoModeState.isAutoModeCircuitBroken()).isFalse();
        assertThat(gate.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("不可用原因值域对齐 CC（settings → circuit-breaker → model，null=可用）")
    void unavailableReasonUnionValues() {
        AutoModeGate off = new AutoModeGate(false);
        assertThat(off.getUnavailableReason(null))
            .as("CC :1295 settings 禁用 → 'settings'")
            .isEqualTo("settings");

        AutoModeGate on = new AutoModeGate(true);
        assertThat(on.getUnavailableReason(null))
            .as("CC :1299 model 不支持（classifier null）→ 'model'")
            .isEqualTo("model");
        assertThat(on.getUnavailableReason(null)).isNotEqualTo("circuit-breaker");
    }

    // ── [P2 OPD-WF1-CFG-v4-04] getAutoModeEnabledStateIfCached 三态（未取到 undefined）──

    @Test
    @DisplayName("[P2] 冷启动（未取到）→ getAutoModeEnabledStateIfCached null + 不熔断（CC undefined 延迟）")
    void autoModeEnabledStateIfCached_undefinedOnColdStart() {
        // WHY: CC getAutoModeEnabledStateIfCached（permissionSetup.ts:1335-1352）无缓存返回
        //   undefined；同步断路器检查（initialPermissionModeFromCLI :717-720）不能把「未取到」
        //   与「已取到且 disabled」混为一谈 —— 前者延迟到 verifyAutoModeGateAccess（不阻断）。
        assertThat(AutoModeState.getAutoModeEnabledStateIfCached())
            .as("冷启动未取到 → undefined（Java null 表达），非 'disabled'")
            .isNull();
        assertThat(AutoModeState.isAutoModeCircuitBroken())
            .as("未取到不阻断（CC undefined → 不 === 'disabled'）")
            .isFalse();
    }

    @Test
    @DisplayName("[P2] 熔断置位 → getAutoModeEnabledStateIfCached 'disabled' + isAutoModeCircuitBroken true")
    void autoModeEnabledStateIfCached_disabledWhenBroken() {
        AutoModeState.setAutoModeCircuitBroken(true);

        assertThat(AutoModeState.getAutoModeEnabledStateIfCached())
            .as("已取到且 disabled → 'disabled'（CC === 'disabled' 立即阻断）")
            .isEqualTo("disabled");
        assertThat(AutoModeState.isAutoModeCircuitBroken()).isTrue();
    }

    @Test
    @DisplayName("[P2] @PostConstruct 门检完成且非熔断 → 解析为 'enabled'（未取到→已取到）")
    void autoModeEnabledStateIfCached_enabledAfterGateResolved() {
        AutoModeGate gate = new AutoModeGate(true);
        gate.setCircuitBreakerEnabled(false);

        gate.applyCircuitBreaker();

        assertThat(AutoModeState.getAutoModeEnabledStateIfCached())
            .as("门检完成且非 disabled → 'enabled'（CC getAutoModeEnabledStateIfCached 已取到）")
            .isEqualTo("enabled");
        assertThat(AutoModeState.isAutoModeCircuitBroken()).isFalse();
        assertThat(gate.isEnabled()).isTrue();
    }
}

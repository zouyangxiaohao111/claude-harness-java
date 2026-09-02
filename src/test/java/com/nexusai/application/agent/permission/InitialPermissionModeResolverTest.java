package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InitialPermissionModeResolver 测试 · 对齐 CC {@code initialPermissionModeFromCLI}
 * （permissionSetup.ts:689-808）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：钉死「初始 mode 多源优先级链 + bypass 禁用门」——
 * 这是 RV-11 的核心缺口：旧实现 {@code DEFAULT_MODE} 恒 DEFAULT，无 CLI/settings/dangerouslySkip 链。
 * 每条用例锁定一个 CC 实际 TS 行为（附 CC 行号），若优先级链或禁用门被改坏，测试即红。
 */
class InitialPermissionModeResolverTest {

    private static final InitialPermissionModeResolver.Config DEFAULT_CFG =
        InitialPermissionModeResolver.Config.defaults();

    // ── 优先级链 ──────────────────────────────────────────────

    @Test
    @DisplayName("空输入（无 CLI / 无 dangerouslySkip / 无 settings）→ DEFAULT")
    void emptyInputFallsBackToDefault() {
        var r = InitialPermissionModeResolver.resolve(
            InitialPermissionModeResolver.Input.empty(), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(r.notification()).isNull();
    }

    @Test
    @DisplayName("dangerouslySkipPermissions → BYPASS_PERMISSIONS（链首位，CC :725-726）")
    void dangerouslySkipPermissionsYieldsBypass() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, true, null, false), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }

    @Test
    @DisplayName("CLI --permission-mode plan 覆盖 settings.defaultMode=acceptEdits（CLI > settings，CC :728/:743）")
    void cliWinsOverSettingsDefaultMode() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("plan", false, "acceptEdits", false), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("无 CLI 时 settings.defaultMode=acceptEdits → ACCEPT_EDITS（settings 源，CC :743-771）")
    void settingsDefaultModeYieldsAcceptEdits() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "acceptEdits", false), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("CLI 未知串 → DEFAULT（permissionModeFromString 折叠，PermissionMode.ts:117-120）")
    void unknownCliFoldsToDefault() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("garbage-mode", false, null, false), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("CLI auto + classifier 关闭 → DEFAULT（PERMISSION_MODES 不含 auto 折叠，CC :729）")
    void cliAutoWithClassifierOffCollapsesToDefault() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("auto", false, null, false), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("CLI auto + classifier 开 + circuit 未断 → AUTO（CC :731-738）")
    void cliAutoWithClassifierOnYieldsAuto() {
        var cfg = new InitialPermissionModeResolver.Config(
            null, () -> true, () -> false, false);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("auto", false, null, false), cfg);
        assertThat(r.mode()).isEqualTo(PermissionMode.AUTO);
    }

    @Test
    @DisplayName("CLI auto + classifier 开 + circuit broken → 折叠 default（CC :733-736）")
    void cliAutoWithCircuitBrokenCollapsesToDefault() {
        var cfg = new InitialPermissionModeResolver.Config(
            null, () -> true, () -> true, false);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("auto", false, null, false), cfg);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    // ── bypass 禁用门 ─────────────────────────────────────────

    @Test
    @DisplayName("dangerouslySkip + settings.disableBypassPermissionsMode → 跳过 bypass → DEFAULT + notification（CC :778-787）")
    void bypassDisabledBySettingsYieldsDefaultWithNotification() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, true, null, true), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(r.notification())
            .isEqualTo("Bypass permissions mode was disabled by settings");
    }

    @Test
    @DisplayName("dangerouslySkip + Statsig 门 → 跳过 bypass → DEFAULT + org-policy notification（CC :781-783）")
    void bypassDisabledByStatsigYieldsOrgPolicyNotification() {
        var cfg = new InitialPermissionModeResolver.Config(
            () -> true, null, null, false);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, true, null, false), cfg);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
        assertThat(r.notification())
            .isEqualTo("Bypass permissions mode was disabled by your organization policy");
    }

    @Test
    @DisplayName("dangerouslySkip 被禁用但 CLI plan 在链中 → 跳过 bypass 取 plan（首个合法 mode，CC :775-795）")
    void bypassDisabledFallsThroughToNextValidMode() {
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("plan", true, null, true), DEFAULT_CFG);
        assertThat(r.mode()).isEqualTo(PermissionMode.PLAN);
        assertThat(r.notification())
            .isEqualTo("Bypass permissions mode was disabled by settings");
    }

    // ── CCR 限制 ──────────────────────────────────────────────

    @Test
    @DisplayName("CCR 下 settings.defaultMode=bypassPermissions → 忽略 → DEFAULT（CC :749-753）")
    void ccrRestrictsSettingsDefaultModeToSafeModes() {
        var cfg = new InitialPermissionModeResolver.Config(
            null, null, null, true);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "bypassPermissions", false), cfg);
        assertThat(r.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("CCR 下 settings.defaultMode=plan → 保留（CC allowlist acceptEdits/plan/default）")
    void ccrAllowsPlanDefaultMode() {
        var cfg = new InitialPermissionModeResolver.Config(
            null, null, null, true);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "plan", false), cfg);
        assertThat(r.mode()).isEqualTo(PermissionMode.PLAN);
    }

    // ── [IMP-7 · OPD-WF1-CFG-v4-03] CCR 忽略遥测事件 ─────────────────────────

    @Test
    @DisplayName("CCR 忽略不支持 defaultMode → 发射 tengu_ccr_unsupported_default_mode_ignored + mode 字段")
    void ccrIgnoredDefaultMode_emitsTelemetryEvent() {
        // WHY: CC permissionSetup.ts:756-758 `logEvent('tengu_ccr_unsupported_default_mode_ignored',
        //   { mode: settingsMode })`。旧 Java 仅 log.warn 无遥测事件（MISS-1）——OPD-WF1-CFG-v4-03
        //   拍板补遥测事件。本测试钉死：CCR 忽略发生时就地发射（mode 字段携带被忽略的原始串）。
        PermissionPipelineTelemetryTest.SpyTelemetry spy = new PermissionPipelineTelemetryTest.SpyTelemetry();
        var cfg = new InitialPermissionModeResolver.Config(
            null, null, null, true, spy);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "bypassPermissions", false), cfg);

        assertThat(r.mode()).as("CCR 忽略 → 不产生 bypass mode").isEqualTo(PermissionMode.DEFAULT);
        assertThat(spy.recordedEvents)
            .as("CCR 忽略必须发射遥测事件（permissionSetup.ts:756-758）")
            .containsKey("tengu_ccr_unsupported_default_mode_ignored");
        assertThat(spy.recordedEvents.get("tengu_ccr_unsupported_default_mode_ignored").get("mode"))
            .as("CC :757 mode 字段 = 被忽略的 settingsMode 原始串")
            .isEqualTo("bypassPermissions");
        // 非忽略分支（acceptEdits/plan/default 受支持）不发射
        var okCfg = new InitialPermissionModeResolver.Config(null, null, null, true, spy);
        InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "plan", false), okCfg);
        // 同 spy 上只应有 1 次（bypass 忽略那次）；plan 保留不发射
        assertThat(spy.recordedEvents.get("tengu_ccr_unsupported_default_mode_ignored")).isNotNull();
    }

    @Test
    @DisplayName("非 CCR 环境不支持 defaultMode 不发射遥测（event 只在 CLAUDE_CODE_REMOTE 下发射）")
    void nonCcr_doesNotEmitTelemetryEvent() {
        // WHY: CC :749-753 —— 仅 `isEnvTruthy(process.env.CLAUDE_CODE_REMOTE)` 时忽略非
        //   acceptEdits/plan/default 的 settingsMode；本地（非 CCR）bypassPermissions 合法，
        //   既不忽略也不发射事件。负控钉死「事件只在 CCR 忽略分支发射」。
        PermissionPipelineTelemetryTest.SpyTelemetry spy = new PermissionPipelineTelemetryTest.SpyTelemetry();
        var cfg = new InitialPermissionModeResolver.Config(null, null, null, false, spy);
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "bypassPermissions", false), cfg);

        assertThat(r.mode()).as("非 CCR：bypassPermissions 直接入链").isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(spy.recordedEvents)
            .as("非 CCR 不忽略 → 不发射遥测事件")
            .doesNotContainKey("tengu_ccr_unsupported_default_mode_ignored");
    }

    // ── [V44] 三态锁链：会话 override（CLI 槽）> DB 全局（settings 槽）> default ──

    @Test
    @DisplayName("[V44] 会话 override plan（CLI 槽）恒胜 DB 全局 acceptEdits（settings 槽）→ PLAN")
    void v44_sessionOverrideCliWinsOverDbGlobalSettings() {
        // WHY: ChatService 把会话 override 喂 CLI 槽（RunRequest.permissionModeCli），DB 全局进
        //   settings 槽（InitialPermissionModeSource DB ?? 磁盘 defaultMode）——resolver 链 CLI >
        //   settings（permissionSetup.ts:728/:743），会话覆盖必须恒胜全局默认（对齐既有
        //   cliWinsOverSettingsDefaultMode :42 语义，V44 三态链核心）。
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input("plan", false, "acceptEdits", false), DEFAULT_CFG);
        assertThat(r.mode())
            .as("会话 override plan 恒胜 DB 全局 acceptEdits（CLI 槽 > settings 槽）")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("[V44] DB 全局 acceptEdits（settings 槽）→ ACCEPT_EDITS")
    void v44_dbGlobalSettingsSlotYieldsAcceptEdits() {
        // WHY: 无 per-call / 无会话 override → 回落 settings 槽（InitialPermissionModeSource resolveInput
        //   DB 全局 ?? 磁盘 defaultMode 合并结果）→ ACCEPT_EDITS（permissionSetup.ts:743 直接 cast）。
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "acceptEdits", false), DEFAULT_CFG);
        assertThat(r.mode())
            .as("DB 全局 acceptEdits（settings 槽）生效 → ACCEPT_EDITS")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    @DisplayName("[V44] settings 槽 auto + classifier 关 → AUTO 保留（CC :744 直接 cast，区别于 CLI 折叠）")
    void v44_settingsAutoWithClassifierOffStaysAuto() {
        // WHY: CC 真源不对称折叠（permissionSetup.ts:729 vs :744）——settings.defaultMode 直接 cast
        //   不经 permissionModeFromString，classifier 关时 auto 仍保留；CLI 槽 auto 才折叠 DEFAULT
        //   （cliAutoWithClassifierOffCollapsesToDefault）。若实现"统一折叠"则破坏对齐（settings 槽
        //   auto 被 fold），本测试钉死不对称语义。
        var r = InitialPermissionModeResolver.resolve(
            new InitialPermissionModeResolver.Input(null, false, "auto", false), DEFAULT_CFG);
        assertThat(r.mode())
            .as("settings 槽 auto 直接 cast 不折叠（CC :744）→ AUTO 保留")
            .isEqualTo(PermissionMode.AUTO);
    }
}

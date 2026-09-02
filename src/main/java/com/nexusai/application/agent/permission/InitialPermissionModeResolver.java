package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 初始权限模式解析器 · 对齐 CC {@code initialPermissionModeFromCLI}
 * （Open-ClaudeCode/src/utils/permissions/permissionSetup.ts:689-808）。
 *
 * <h2>职责</h2>
 * <p>把「多源初始模式」收敛成单一 {@link PermissionMode}，按 CC 实际优先级链
 * （{@code permissionSetup.ts:722-773}，无独立 env 源）：
 * <ol>
 *   <li>{@code dangerouslySkipPermissions} → {@code bypassPermissions}（:725-726）</li>
 *   <li>{@code permissionModeCli}（CLI {@code --permission-mode}）→ {@code permissionModeFromString}（:729-740）</li>
 *   <li>{@code settings.permissions.defaultMode} → settingsMode（:743-771）；
 *       env {@code CLAUDE_CODE_REMOTE} 不是独立 mode 源，仅限制 settings.defaultMode
 *       只允许 acceptEdits/plan/default（:749-753）</li>
 * </ol>
 * <p>收敛（:775-799）：遍历 orderedModes，若 {@code bypassPermissions} 被禁用门关闭则
 * continue + 置 notification；取首个合法 mode；无合法 mode → fallback {@code default}。
 *
 * <h2>CC 真源关键行为（不信注释，Read TS 实测）</h2>
 * <ul>
 *   <li><b>禁用门</b>（:709-711）：{@code disableBypassPermissionsMode = growthBookDisable
 *       || settingsDisableBypassPermissionsMode}，Statsig 门优先级高于 settings。</li>
 *   <li><b>CLI auto 与 settings auto 语义不对称</b>：CLI 走 {@code permissionModeFromString}
 *       （:729）——classifier 关闭时 {@code 'auto'} 会被 {@code PERMISSION_MODES} 折叠为
 *       {@code 'default'}；settings.defaultMode 是直接 cast（:744，不经 permissionModeFromString）
 *       ——classifier 关闭时 {@code 'auto'} 仍保留为 {@code 'auto'}。Java 端在
 *       {@link PermissionMode#fromString} 恒映射 {@code 'auto' → AUTO}，本类 CLI 分支显式补
 *       classifier-off 折叠（等价 CC 双重判定），settings 分支不折叠（等价 CC 直接 cast）。</li>
 *   <li><b>CCR 限制</b>（:749-753）：仅当 settingsMode 不在 acceptEdits/plan/default 时忽略，
 *       与 classifier 门无关。</li>
 *   <li><b>auto circuit breaker</b>（:717-720 + :731-738 + :763-768）：仅当 classifier 开且
 *       {@code getAutoModeEnabledStateIfCached()==='disabled'} 时折叠 auto → default。</li>
 *   <li><b>setAutoModeActive(true)</b>（:807）：CC 在函数尾部对 auto 结果置激活态。
 *       Java 端本类保持<b>纯函数</b>（无 AutoModeState 全局副作用），该副作用由调用方
 *       （WF-8 落地于 LlmAgentLoop.doRun 启动/初始化路径，OD-WF1-CFG-04）依据
 *       {@link Result#mode()} 应用 —— 见 concerns。</li>
 * </ul>
 *
 * <h2>注入约定（对齐既有 TransitionConfig 惯例）</h2>
 * <p>Java 端无 Statsig/GrowthBook 客户端，CC 的 feature 门 / Statsig 门 / circuit breaker
 * 均建模为注入式 {@link BooleanSupplier}（{@link Config}），null → 关闭。确定性对齐仅 settings 源。
 *
 * <p><b>纯函数 / 可重入</b>：同 Input + 同 Config → 同 Result（无状态，便于测试与并发）。
 */
public final class InitialPermissionModeResolver {

    private static final Logger log = LoggerFactory.getLogger(InitialPermissionModeResolver.class);

    /** 初始模式多源输入 · 对齐 CC initialPermissionModeFromCLI 入参 + settings 派生字段。 */
    public record Input(
            /** CLI {@code --permission-mode} 字符串（CC permissionModeCli，可为 null）。 */
            String permissionModeCli,
            /** CLI {@code --dangerously-skip-permissions}（CC dangerouslySkipPermissions）。 */
            boolean dangerouslySkipPermissions,
            /** settings.permissions.defaultMode（CC settings.permissions?.defaultMode，可为 null）。 */
            String settingsDefaultMode,
            /** settings.permissions.disableBypassPermissionsMode === 'disable'（CC :706）。 */
            boolean settingsDisableBypassPermissionsMode
    ) {
        /** 空输入（无 CLI / 无 dangerouslySkip / 无 settings）→ 解析结果为 DEFAULT。 */
        public static Input empty() {
            return new Input(null, false, null, false);
        }
    }

    /** 依赖注入配置 · 对齐 CC 模块级 feature / Statsig / circuit breaker 判定。 */
    public record Config(
            /** CC {@code checkStatsigFeatureGate_CACHED('tengu_disable_bypass_permissions_mode')}（:703，GrowthBook 门）。 */
            BooleanSupplier statsigDisableBypassPermissionsMode,
            /** CC {@code feature('TRANSCRIPT_CLASSIFIER')}（:717/:731/:763）。 */
            BooleanSupplier transcriptClassifierFeature,
            /** CC {@code getAutoModeEnabledStateIfCached()==='disabled'}（:717-720，auto circuit breaker）。 */
            BooleanSupplier autoModeCircuitBrokenSync,
            /** CC {@code isEnvTruthy(process.env.CLAUDE_CODE_REMOTE)}（:749）。 */
            boolean claudeCodeRemote,
            /** 遥测 bean（[IMP-7 OPD-WF1-CFG-v4-03] CCR 忽略事件发射）；null → 不发射。 */
            Telemetry telemetry
    ) {
        public Config {
            statsigDisableBypassPermissionsMode = statsigDisableBypassPermissionsMode != null
                    ? statsigDisableBypassPermissionsMode : () -> false;
            transcriptClassifierFeature = transcriptClassifierFeature != null
                    ? transcriptClassifierFeature : () -> false;
            autoModeCircuitBrokenSync = autoModeCircuitBrokenSync != null
                    ? autoModeCircuitBrokenSync : () -> false;
        }

        /** 4 参便捷构造器（无 telemetry）· 既有调用方/测试沿用（null → 不发射遥测）。 */
        public Config(BooleanSupplier statsigDisableBypassPermissionsMode,
                      BooleanSupplier transcriptClassifierFeature,
                      BooleanSupplier autoModeCircuitBrokenSync,
                      boolean claudeCodeRemote) {
            this(statsigDisableBypassPermissionsMode, transcriptClassifierFeature,
                autoModeCircuitBrokenSync, claudeCodeRemote, null);
        }

        /** 默认配置（所有门关闭 / 非 CCR / 无 telemetry）→ 仅 settings 源生效，auto 分支休眠。 */
        public static Config defaults() {
            return new Config(null, null, null, false, null);
        }
    }

    /** 解析结果 · 对齐 CC 返回 {@code { mode, notification? }}（:796）。 */
    public record Result(PermissionMode mode, String notification) {
        public Result {
            if (mode == null) {
                mode = PermissionMode.DEFAULT;
            }
        }
    }

    private InitialPermissionModeResolver() {
    }

    /**
     * 解析初始权限模式（对齐 CC initialPermissionModeFromCLI，:689-808）。
     *
     * @param input  多源输入（CLI / dangerouslySkip / settings）
     * @param config 依赖注入（Statsig 门 / classifier 门 / auto circuit breaker / CCR）
     * @return 收敛后的初始模式 + 可选通知（bypass 被禁用时的用户通知）
     */
    public static Result resolve(Input input, Config config) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(config, "config");

        boolean classifierOn = config.transcriptClassifierFeature().getAsBoolean();
        // CC :717-720 —— circuit breaker 仅在 classifier 开启时参与判定
        boolean autoBroken = classifierOn && config.autoModeCircuitBrokenSync().getAsBoolean();
        boolean growthBookDisable = config.statsigDisableBypassPermissionsMode().getAsBoolean();
        // CC :709-711 —— Statsig 门优先于 settings
        boolean disableBypass = growthBookDisable || input.settingsDisableBypassPermissionsMode();

        List<PermissionMode> orderedModes = new ArrayList<>(3);
        String notification = null;

        // 1. CC :725-726 —— dangerouslySkipPermissions → bypassPermissions
        if (input.dangerouslySkipPermissions()) {
            orderedModes.add(PermissionMode.BYPASS_PERMISSIONS);
        }

        // 2. CC :728-741 —— CLI --permission-mode
        String cli = input.permissionModeCli();
        if (cli != null && !cli.isBlank()) {
            PermissionMode parsed = PermissionMode.fromString(cli.trim());
            if (classifierOn && parsed == PermissionMode.AUTO) {
                // CC :731-738 —— classifier 开 + auto：circuit broken → fallback default
                if (autoBroken) {
                    log.warn("InitialPermissionModeResolver: auto 模式电路断路器激活（cached），"
                            + "CLI --permission-mode auto 回退 default");
                } else {
                    orderedModes.add(PermissionMode.AUTO);
                }
            } else if (!classifierOn && parsed == PermissionMode.AUTO) {
                // CC :729 + PermissionMode.ts:117-120 —— classifier 关时 permissionModeFromString('auto')
                //   折叠为 'default'（PERMISSION_MODES 运行时集合不含 auto）
                orderedModes.add(PermissionMode.DEFAULT);
            } else {
                // CC :740 —— 其余合法 parsedMode 直接入链
                orderedModes.add(parsed);
            }
        }

        // 3. CC :743-771 —— settings.permissions.defaultMode（直接 cast，不折叠 auto）
        String settingsDefaultMode = input.settingsDefaultMode();
        if (settingsDefaultMode != null && !settingsDefaultMode.isBlank()) {
            PermissionMode settingsMode = PermissionMode.fromString(settingsDefaultMode.trim());
            if (config.claudeCodeRemote()
                    && settingsMode != PermissionMode.ACCEPT_EDITS
                    && settingsMode != PermissionMode.PLAN
                    && settingsMode != PermissionMode.DEFAULT) {
                // CC :749-753 —— CCR 仅允许 acceptEdits/plan/default，忽略其余（含 bypassPermissions）
                log.warn("InitialPermissionModeResolver: settings defaultMode \"{}\" 在 CLAUDE_CODE_REMOTE "
                        + "不受支持（仅 acceptEdits/plan/default），忽略", settingsDefaultMode);
                // [IMP-7 · OPD-WF1-CFG-v4-03] 补 CCR 遥测事件 · 对齐 CC permissionSetup.ts:756-758
                //   `logEvent('tengu_ccr_unsupported_default_mode_ignored', { mode: settingsMode })`
                emitCcrUnsupportedDefaultModeIgnored(config, settingsDefaultMode);
            } else if (classifierOn && settingsMode == PermissionMode.AUTO) {
                // CC :763-768 —— classifier 开 + settings auto：同样受 circuit breaker 门控
                if (autoBroken) {
                    log.warn("InitialPermissionModeResolver: auto 模式电路断路器激活（cached），"
                            + "settings defaultMode auto 回退 default");
                } else {
                    orderedModes.add(PermissionMode.AUTO);
                }
            } else {
                // CC :771 —— 其余 settingsMode 直接入链（classifier 关时 auto 也在此直接入链）
                orderedModes.add(settingsMode);
            }
        }

        // 4. CC :775-799 —— 收敛：取首个合法 mode，bypass 被禁用门关闭则跳过
        Result result = null;
        for (PermissionMode mode : orderedModes) {
            if (mode == PermissionMode.BYPASS_PERMISSIONS && disableBypass) {
                if (growthBookDisable) {
                    // CC :781-783
                    notification = "Bypass permissions mode was disabled by your organization policy";
                    log.warn("InitialPermissionModeResolver: bypassPermissions 被组织策略（Statsig 门）禁用");
                } else {
                    // CC :785-787
                    notification = "Bypass permissions mode was disabled by settings";
                    log.warn("InitialPermissionModeResolver: bypassPermissions 被 settings 禁用");
                }
                continue;
            }
            result = new Result(mode, notification);
            break;
        }

        // CC :797-799 —— 无合法 mode → fallback default
        if (result == null) {
            result = new Result(PermissionMode.DEFAULT, notification);
        }

        if (log.isDebugEnabled()) {
            log.debug("InitialPermissionModeResolver.resolve: cli={} dangerouslySkip={} defaultMode={} "
                    + "disableBypass={} classifierOn={} → mode={} notification={}",
                cli, input.dangerouslySkipPermissions(), settingsDefaultMode,
                input.settingsDisableBypassPermissionsMode(), classifierOn,
                result.mode(), result.notification());
        }

        // CC :807 setAutoModeActive(true) 副作用不在此建模（纯函数），由调用方
        // （WF-8 落地于 LlmAgentLoop.doRun 启动/初始化路径，OD-WF1-CFG-04）应用 —— 见类 JavaDoc
        return result;
    }

    /**
     * 发射 {@code tengu_ccr_unsupported_default_mode_ignored} 遥测事件 · 对齐 CC
     * permissionSetup.ts:756-758（{@code logEvent('tengu_ccr_unsupported_default_mode_ignored',
     * { mode: settingsMode })}）。
     *
     * <p>[IMP-7 · OPD-WF1-CFG-v4-03 拍板：补遥测事件] 旧实现仅 log.warn 无遥测（MISS-1）。
     * Java 以 Telemetry bean 双发（recordEvent = CC logEvent 等价 + logOTelEvent = OTel 扩展通道，
     * 与 PermissionPipeline.emitAutoModeDecision 约定一致）。null telemetry → 不发射（测试/无 bean）。
     *
     * @param config 依赖注入配置（含 telemetry）
     * @param settingsDefaultMode 被忽略的 settings defaultMode 原始串（CC settingsMode）
     */
    private static void emitCcrUnsupportedDefaultModeIgnored(Config config, String settingsDefaultMode) {
        Telemetry telemetry = config.telemetry();
        if (telemetry == null) {
            return;
        }
        try {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("mode", settingsDefaultMode);
            telemetry.recordEvent("tengu_ccr_unsupported_default_mode_ignored", attrs);
            telemetry.logOTelEvent("tengu_ccr_unsupported_default_mode_ignored", attrs);
            if (log.isDebugEnabled()) {
                log.debug("InitialPermissionModeResolver: CCR 遥测事件 tengu_ccr_unsupported_default_mode_ignored "
                        + "已发射 (mode={})", settingsDefaultMode);
            }
        } catch (Throwable th) {
            log.warn("InitialPermissionModeResolver: CCR 遥测发射失败 (mode={}): {}",
                settingsDefaultMode, th.toString());
        }
    }
}

package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.infra.util.AutoModeState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BooleanSupplier;

/**
 * Auto mode 开关 + 配置检查 · 对齐 CC permissionSetup.ts:1283-1362
 *
 * <p>控制 auto mode 的启用状态，检查 classifier 是否可用，
 * 以及提供 opt-in 等配置信息。
 *
 * <h2>配置属性</h2>
 * <ul>
 *   <li>{@code nexusai.auto-mode.enabled} — 是否启用 auto mode（默认 false）</li>
 * </ul>
 *
 * @see YoloClassifier
 */
@Component
public class AutoModeGate {
    private static final Logger log = LoggerFactory.getLogger(AutoModeGate.class);

    /**
     * 可信 userSettings 源文件 · 对齐 CC {@code ~/.claude/settings.json}（settings.ts:898）→
     * nexusai 自有根（决策 D1：{@code ~/.{appName}/settings.json}，appName=spring.application.name
     * 默认 nexusai）。
     * <p>仅读用户目录级文件（{@link NexusaiPaths#getAppConfigHomeDir()}）；项目目录下配置
     * （nexusai.home 默认 cwd）对应 CC projectSettings —— 显式排除，防恶意项目借 opt-in 绕过权限弹窗
     * （对齐 CC settings.ts:893-894 的 RCE 防护注释）。
     * <p>注：static final 在类加载期冻结（测试断言锚点，见 AutoModeGateTest#defaultSettingsPathIsUserLevel）；
     * 生产运行时经 {@link #defaultSettingsPath()} 动态解析，规避类加载早于 NexusaiAppNameInitializer
     * 的时序问题。
     */
    static final Path DEFAULT_USER_SETTINGS_PATH = defaultSettingsPath();

    /**
     * 动态解析默认 userSettings 源路径（{@code ~/.{appName}/settings.json}）· 决策 D1。
     *
     * @return 用户级 settings.json 路径
     */
    private static Path defaultSettingsPath() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "settings.json");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;

    /**
     * 电路断路器配置（org 级熔断）· 对齐 CC {@code tengu_auto_mode_config.enabled==='disabled'}
     * （permissionSetup.ts:1091-1101 {@code verifyAutoModeGateAccess} 写入
     * {@code setAutoModeCircuitBroken(enabledState === 'disabled' || disabledBySettings)}）。
     *
     * <p>Java 无 GrowthBook，此配置为 CC「enabled==='disabled'」的 web 等价源；
     * {@code @PostConstruct} 启动时写入 {@link AutoModeState#setAutoModeCircuitBroken}，
     * {@link #isEnabled()} / {@link #getUnavailableReason(YoloClassifier)} 读取该状态
     * （CC {@code isAutoModeGateEnabled:1284} / {@code getAutoModeUnavailableReason:1296} 断路器阻断）。
     */
    @Value("${nexusai.auto-mode.circuit-breaker:false}")
    private volatile boolean circuitBreakerEnabled;

    /** 注入式 opt-in 信号源（测试可控输入；非 null 时完全覆盖默认源判定）。 */
    private volatile BooleanSupplier optInSupplier;

    /** 可信 userSettings 文件路径（测试可覆盖；null → 运行时动态解析 {@link #defaultSettingsPath()}，
     * 规避 static final 类加载冻结默认 appName 的时序问题——生产 appName 由
     * {@code NexusaiAppNameInitializer} @PostConstruct 写入）。 */
    private volatile Path userSettingsPath;

    public AutoModeGate(@Value("${nexusai.auto-mode.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 启动时把电路断路器配置写入 {@link AutoModeState} · 对齐 CC permissionSetup.ts:1099
     * {@code autoModeStateModule?.setAutoModeCircuitBroken(enabledState === 'disabled' || disabledBySettings)}。
     *
     * <p>写入后 {@link AutoModeState#isAutoModeCircuitBroken()} 成为生产断路器状态（读方
     * {@link #isEnabled()} / {@link #getUnavailableReason(YoloClassifier)} /
     * {@code InitialPermissionModeResolver.Config.autoModeCircuitBrokenSync}）。测试手动
     * {@code new AutoModeGate(...)} 不走 Spring 生命周期 → 配置字段 false → 不熔断。
     */
    @PostConstruct
    void applyCircuitBreaker() {
        if (circuitBreakerEnabled) {
            AutoModeState.setAutoModeCircuitBroken(true);
            log.warn("[AutoModeGate] 电路断路器激活（nexusai.auto-mode.circuit-breaker=true，"
                    + "对齐 CC tengu_auto_mode_config.enabled==='disabled'）：auto 模式被熔断，禁止进入/重入");
        } else {
            // [P2 · OPD-WF1-CFG-v4-04] 已取到且非 disabled → 状态解析为 ENABLED
            //   （CC getAutoModeEnabledStateIfCached 从 undefined 解析为非 disabled）；
            //   @PostConstruct 即"门检完成"时刻，unresolved(UNDEFINED) 仅存在于冷启动窗口。
            AutoModeState.setAutoModeCircuitBroken(false);
        }
    }

    /**
     * 包级注入 opt-in 信号源 · 对齐 CC hasAutoModeOptInAnySource 的注入面（测试可控输入）。
     *
     * @param supplier 判定信号（null 不受理；不调用时走默认源 = CLI flag || userSettings 文件）
     */
    void setOptInSupplier(BooleanSupplier supplier) {
        this.optInSupplier = supplier;
    }

    /**
     * 包级覆盖电路断路器配置（测试可控输入；生产经 {@code @Value} 注入，@PostConstruct 写入
     * {@link AutoModeState}）。对齐 {@link #setOptInSupplier} 既有测试注入惯例。
     *
     * @param broken 电路断路器开关（true → {@link #applyCircuitBreaker()} 熔断 auto 模式）
     */
    void setCircuitBreakerEnabled(boolean broken) {
        this.circuitBreakerEnabled = broken;
    }

    /**
     * 包级覆盖可信 userSettings 文件路径（测试用；生产走 {@link #defaultSettingsPath()} 动态自有根）。
     *
     * @param path 用户级 settings.json 路径
     */
    void setUserSettingsPath(Path path) {
        this.userSettingsPath = path;
    }
    /**
     * auto mode 是否开启 · 对齐 CC {@code isAutoModeGateEnabled}（permissionSetup.ts:1283-1288）。
     *
     * <p>CC 真源：{@code if (circuitBroken) return false; if (disabledBySettings) return false;
     * if (!modelSupports) return false; return true}。Java 映射：
     * <ul>
     *   <li><b>circuit broken</b> → {@link AutoModeState#isAutoModeCircuitBroken()}（:1284，启动由
     *       {@code nexusai.auto-mode.circuit-breaker} 配置写入）——OPD-AM-01 落地：enabled==='disabled'
     *       熔断后 auto 进入/重入被阻断；</li>
     *   <li><b>settings 禁用</b> → {@code nexusai.auto-mode.enabled} 配置（:1285，org 级开关等价）；</li>
     *   <li><b>model 不支持</b> → 分类器可用性（:1286，见 {@link #getUnavailableReason(YoloClassifier)}）。</li>
     * </ul>
     *
     * @return true 表示 auto 模式可用
     */
    public boolean isEnabled() {
        // [AM-CC-20260824] 对齐 CC isAutoModeGateEnabled（permissionSetup.ts:1271）恒 true——
        //   Java 旧实现加了 nexusai.auto-mode.enabled settings 门（默认 false）→ auto 链路
        //   （PermissionPipeline.isAutoModeEntry）永不进 → classifier 永不触发（2026-08-24 联调实测）。
        //   settings 禁用语义仍由 getUnavailableReason() 返回 'settings' 消费（展示用，不阻断权限链）。
        //   电路断路器阻断保留（CC permissionSetup.ts:1284 真实存在）。
        if (AutoModeState.isAutoModeCircuitBroken()) {
            return false;
        }
        return true;
    }

    /**
     * auto mode 不可用原因 · 对齐 CC {@code getAutoModeUnavailableReason}（permissionSetup.ts:1294-1301）。
     *
     * <p>返回 CC 联合值 {@code 'settings' | 'circuit-breaker' | 'model'}（Java 端此前返回自然语言
     * String，契约漂移 DRIFT-AM-05；DEL-AM-04 接线时统一为 CC 值域），null 表示可用。
     * 检查顺序对齐 CC：settings → circuit-breaker → model。
     *
     * @param classifier yolo 分类器实例（可为 null；null → model 不可用）
     * @return 不可用原因联合值；null 表示可用
     */
    public String getUnavailableReason(YoloClassifier classifier) {
        // CC :1295 —— settings 禁用（nexusai.auto-mode.enabled=false，org 级开关等价）
        if (!enabled) {
            return "settings";
        }
        // CC :1296-1298 —— 电路断路器
        if (AutoModeState.isAutoModeCircuitBroken()) {
            return "circuit-breaker";
        }
        // CC :1299 —— model 不支持（分类器不可用）
        if (classifier == null || !classifier.isAvailable()) {
            return "model";
        }
        return null;
    }

    /**
     * auto mode 启用状态（§8.14 getAutoModeEnabledState）。
     *
     * <p>返回 "enabled" / "disabled" / "opt-in"。
     *
     * @return 状态字符串
     */
    public String getEnabledState() {
        // 对齐 CC getAutoModeEnabledState（permissionSetup.ts:1328-1333）+ 三态消费语义：
        //   - 静态配置开启 → "enabled"
        //   - 静态配置关闭但用户显式 opt-in（CLI flag / userSettings skipAutoPermissionPrompt）
        //     → "opt-in"（CC :1308 注释：仅显式 opt-in 可用）
        //   - 其余 → "disabled"（CC AUTO_MODE_ENABLED_DEFAULT，:1313）
        String state;
        if (enabled) {
            state = "enabled";
        } else if (hasOptIn()) {
            state = "opt-in";
        } else {
            state = "disabled";
        }
        if (!"disabled".equals(state) && log.isDebugEnabled()) {
            log.debug("[AutoModeGate] getEnabledState 返回非默认态: state={}, nexusai.auto-mode.enabled={}, cliFlag={}",
                state, enabled, AutoModeState.getAutoModeFlagCli());
        }
        return state;
    }

    /**
     * 是否有显式 opt-in（§8.16 hasAutoModeOptInAnySource，permissionSetup.ts:1362-1365）。
     *
     * <p>信号源：注入式 supplier（测试可控输入）优先；缺省走
     * CLI flag（{@link AutoModeState#getAutoModeFlagCli()}）|| 可信 userSettings
     * 文件（{@link NexusaiPaths#getAppConfigHomeDir()} 下 {@code settings.json} 的
     * {@code skipAutoPermissionPrompt}）。
     * 项目目录下配置（nexusai.home 默认 cwd）对应 CC projectSettings，显式排除 ——
     * 恶意项目不能借 opt-in 自动绕过权限弹窗（对齐 CC settings.ts:893-894 RCE 防护）。
     *
     * @return true 表示已显式 opt-in
     */
    public boolean hasOptIn() {
        BooleanSupplier injected = optInSupplier;
        if (injected != null) {
            boolean v = injected.getAsBoolean();
            if (log.isDebugEnabled()) {
                log.debug("[AutoModeGate] hasOptIn 注入源判定: {}", v);
            }
            return v;
        }
        boolean cliFlag = AutoModeState.getAutoModeFlagCli();
        boolean userSettingsOptIn = readUserSettingsOptIn();
        boolean result = cliFlag || userSettingsOptIn;
        if (log.isDebugEnabled()) {
            log.debug("[AutoModeGate] hasOptIn 判定: result={}, cliFlag={}, userSettingsOptIn={}（仅可信源, 排除项目目录配置）",
                result, cliFlag, userSettingsOptIn);
        }
        return result;
    }

    /**
     * 读可信 userSettings 源（默认 {@link #defaultSettingsPath()} 动态自有根，可经
     * {@link #setUserSettingsPath} 测试覆盖）顶层键 {@code skipAutoPermissionPrompt} ·
     * 对齐 CC hasAutoModeOptIn（settings.ts:896-911，userSettings 源分支）。
     *
     * @return true 表示用户级 settings 显式 opt-in
     */
    private boolean readUserSettingsOptIn() {
        Path path = userSettingsPath != null ? userSettingsPath : defaultSettingsPath();
        try {
            if (!Files.exists(path)) {
                return false;
            }
            JsonNode root = MAPPER.readTree(path.toFile());
            JsonNode value = root.get("skipAutoPermissionPrompt");
            return value != null && value.isBoolean() && value.asBoolean();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoModeGate] 读取 userSettings skipAutoPermissionPrompt 失败: {} — 视为未 opt-in: {}",
                    path, e.getMessage());
            }
            return false;
        }
    }
}

package com.nexusai.application.agent.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * fast-mode 运行时状态 · 对齐 CC fastMode.ts:183-317（FastModeRuntimeState 区段）。
 *
 * <p>CC 分两块：
 * <ul>
 *   <li><b>cooldown 运行时状态</b>（:183-237）— {@code runtimeState: 'active' |
 *       {status:'cooldown', resetAt, reason}}。rate limit / overloaded 后进入 cooldown，
 *       到期自动回 active（withRetry.ts:297-304 触发后，wasFastModeActive 下一轮变 false → 标准速度）。</li>
 *   <li><b>org 级禁用 + overage rejection</b>（:239-317）— {@code orgStatus} 与
 *       {@code handleFastModeRejectedByAPI/handleFastModeOverageRejection}。CC 持久化到
 *       global config + settings；Java 未上线 → <b>in-memory 标志 + slf4j 日志</b>（不持久化 config）。</li>
 * </ul>
 *
 * <p><b>Java 近似标注</b>：CC 的 wasFastModeActive = {@code isFastModeEnabled() ?
 * retryContext.fastMode && !isFastModeCooldown() : false}（withRetry.ts:196-198）。Java 无 per-call
 * fastMode 选项，retryContext.fastMode 恒等于全局 gate；handleFastModeRejectedByAPI 对
 * retryContext.fastMode=false 的副作用（CC:312）以本类 {@link #isOrgDisabled()} 表达——
 * LlmAgentLoop 的 wasFastModeActive = {@code isFastModeEnabled() && FastModeRuntimeState.isFastModeActive()}。
 *
 * <p><b>V-PF-3 overage 语义拆分</b>：CC withRetry.ts:280 对 overage 拒绝<b>无条件</b>
 * retryContext.fastMode=false（含 out-of-credits）。本类 {@link #handleFastModeOverageRejection}
 * 仅对<b>非</b> out-of-credits 设 orgDisabled（fastMode.ts:305-311）；out-of-credits 的「本
 * episode 切标准速度」以 LlmAgentLoop 的 {@code fastModeTemporarilyDisabled} episode 局部标志表达
 * （CC retryContext 每 withRetry 重建，Java 每 genuine next_turn 复位），不落 org 级全局禁用。
 *
 * <p><b>F3 恒关（用户拍板 2026-08-22）</b>：非 Anthropic 无 fast-mode 服务端（无 fast-mode-2026-02-01
 * 服务端能力）→ {@link #isFastModeEnabled()} <b>恒返回 false</b>，不再读任何 env；原
 * {@code CLAUDE_CODE_DISABLE_FAST_MODE}（CC fastMode.ts:39）/ Java {@code NEXUSAI_DISABLE_FAST_MODE}
 * env 路已删除。cooldown / org 级禁用状态机保留为 CC 镜像（fastMode.ts:183-317），恒关下全部经
 * isFastModeEnabled 守卫惰性不可达（LlmAgentLoop wasFastModeActive / Path 3 fast-mode 分支恒 false）。
 *
 * <p>线程安全：静态 volatile 状态（CC module-level let + createSignal 同为单例语义）。
 * 测试可用 {@link #reset()} 复位。
 */
public final class FastModeRuntimeState {

    private static final Logger log = LoggerFactory.getLogger(FastModeRuntimeState.class);

    /** F3 恒关启动说明 · 类加载时打一次（LlmAgentLoop 启动引用本类即触发） */
    static {
        log.info("FastModeRuntimeState: fast mode 恒关（用户拍板：非 Anthropic 无 fast-mode 服务端），"
            + "原 CLAUDE_CODE_DISABLE_FAST_MODE/NEXUSAI_DISABLE_FAST_MODE env 路已删除");
    }

    /**
     * 冷却原因 · CC original: {@code type CooldownReason = 'rate_limit' | 'overloaded'} (fastMode.ts:191)。
     */
    public enum CooldownReason {
        /** 429 rate limit 触发 */
        RATE_LIMIT("rate_limit"),
        /** 529 overloaded 触发 */
        OVERLOADED("overloaded");

        private final String ccValue;

        CooldownReason(String ccValue) {
            this.ccValue = ccValue;
        }

        /** CC 字符串值（日志/事件面）· fastMode.ts:191 */
        public String ccValue() {
            return ccValue;
        }
    }

    /**
     * cooldown 状态 · CC original: {@code FastModeRuntimeState =
     * {status:'active'} | {status:'cooldown', resetAt, reason}} (fastMode.ts:183-185)。
     */
    private record CooldownState(String status, long resetAt, CooldownReason reason) {
        static CooldownState active() {
            return new CooldownState("active", 0, null);
        }

        static CooldownState cooldown(long resetAt, CooldownReason reason) {
            return new CooldownState("cooldown", resetAt, reason);
        }
    }

    /** CC runtimeState (fastMode.ts:187) — 初始 active */
    private static volatile CooldownState runtimeState = CooldownState.active();

    /**
     * CC orgStatus（fastMode.ts:351-356）Java in-memory 近似：fast mode 是否被 API 拒绝/overage 禁用。
     *
     * <p>CC {@code handleFastModeRejectedByAPI} 设 {@code orgStatus={disabled,reason:'preference'}}
     * + 持久化 config；overage 非 out-of-credits 同理。Java 未上线 → in-memory 标志。
     */
    private static volatile boolean orgDisabled = false;

    /** CC hasLoggedCooldownExpiry (fastMode.ts:188) — 冷却到期日志只打一次 */
    private static volatile boolean hasLoggedCooldownExpiry = false;

    /**
     * env 读取器（public 供跨包测试注入）· 与 ErrorClassifier.ENV_READER 同风格。
     *
     * <p><b>F3 恒关（2026-08-22）</b>：fast-mode 门控不再读任何 env（isFastModeEnabled 恒 false），
     * 本字段保留仅为既有测试注入缝兼容（测试按 NEXUSAI_DISABLE_FAST_MODE 设门控分支现已无效果），
     * 无生产消费方。
     */
    public static volatile java.util.function.Function<String, String> ENV_READER = System::getenv;

    private FastModeRuntimeState() {
        // 工具类不可实例化
    }

    /**
     * fast mode 全局启用判定 · <b>恒关</b>（用户拍板 F3：非 Anthropic 无 fast-mode 服务端）。
     *
     * <p><b>恒关（2026-08-22）</b>：恒返回 false，不读任何 env。原 CC 路
     * {@code isFastModeEnabled = !isEnvTruthy(CLAUDE_CODE_DISABLE_FAST_MODE)}（fastMode.ts:38-40）
     * 与 Java {@code NEXUSAI_DISABLE_FAST_MODE} env 路（ApiErrors.ENV_NEXUSAI_DISABLE_FAST_MODE）已删除——
     * 无服务端则 fast mode 永远不可用，保留 env 反会误导运维以为可开启。下游 LlmAgentLoop 的
     * wasFastModeActive / Path 3 fast-mode fallback 恒走 false 分支。
     */
    public static boolean isFastModeEnabled() {
        return false;
    }

    /**
     * 当前运行时状态 · CC fastMode.ts:199-212 {@code getFastModeRuntimeState}。
     *
     * <p>cooldown 且 {@code Date.now() >= resetAt} → 回 active；期间若 fast mode 仍启用且未打过
     * 到期日志，打一次「cooldown 已过期，重新启用 fast mode」（CC:204-208 hasLoggedCooldownExpiry）。
     */
    static CooldownState getRuntimeState() {
        CooldownState s = runtimeState;
        if ("cooldown".equals(s.status()) && System.currentTimeMillis() >= s.resetAt()) {
            if (isFastModeEnabled() && !hasLoggedCooldownExpiry) {
                log.warn("FastModeRuntimeState: fast mode cooldown 已过期，重新启用 fast mode · CC fastMode.ts:204-208");
                hasLoggedCooldownExpiry = true;
            }
            runtimeState = CooldownState.active();
            return runtimeState;
        }
        return s;
    }

    /**
     * 是否处于冷却 · CC fastMode.ts:315-317 {@code isFastModeCooldown}。
     *
     * @return true=cooldown 状态（含未到期的 resetAt）
     */
    public static boolean isFastModeCooldown() {
        return "cooldown".equals(getRuntimeState().status());
    }

    /**
     * 是否已被 API 拒绝/overage 永久禁用（Java 近似 orgStatus disabled + retryContext.fastMode=false）。
     *
     * <p>CC 中 handleFastModeRejectedByAPI/handleFastModeOverageRejection 设 orgStatus disabled 并
     * 在 withRetry 内把 retryContext.fastMode=false（:280/:312）；Java 无 per-call fastMode，
     * 以此标志表达对 wasFastModeActive 的副作用。
     *
     * @return true=fast mode 已被 API 拒绝禁用
     */
    public static boolean isOrgDisabled() {
        return orgDisabled;
    }

    /**
     * fast mode 是否有效（供 wasFastModeActive）· 等价 CC {@code retryContext.fastMode && !isFastModeCooldown()}
     * （withRetry.ts:197，Java 以 !isOrgDisabled() 表达 retryContext.fastMode=false 副作用）。
     *
     * @return true=fast mode 生效（非冷却且未被 API 拒绝）
     */
    public static boolean isFastModeActive() {
        return !isFastModeCooldown() && !orgDisabled;
    }

    /**
     * 触发冷却 · CC fastMode.ts:214-233 {@code triggerFastModeCooldown}。
     *
     * <p>fast mode 未启用 → no-op（CC:218-220）；否则置 cooldown 状态 + 重置到期日志标志
     * （CC:222）+ 日志 + 事件（Java 事件面 N/A，日志承载）。
     *
     * @param resetTimestamp 冷却到期时间戳（epoch ms）
     * @param reason         冷却原因（rate_limit / overloaded）
     */
    public static void triggerFastModeCooldown(long resetTimestamp, CooldownReason reason) {
        if (!isFastModeEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("FastModeRuntimeState: fast mode 未启用，忽略冷却触发 · CC fastMode.ts:218-220");
            }
            return;
        }
        runtimeState = CooldownState.cooldown(resetTimestamp, reason);
        hasLoggedCooldownExpiry = false;
        long durationMs = resetTimestamp - System.currentTimeMillis();
        log.warn("FastModeRuntimeState: fast mode cooldown 触发 ({})，时长 {}s · CC fastMode.ts:214-233",
            reason.ccValue(), Math.round(durationMs / 1000.0));
        log.warn("FastModeRuntimeState: tengu_fast_mode_fallback_triggered 等价 {{cooldown_duration_ms={}, cooldown_reason={}}} · CC fastMode.ts:227-231",
            durationMs, reason.ccValue());
    }

    /**
     * 清除冷却 · CC fastMode.ts:235-237 {@code clearFastModeCooldown}。
     */
    public static void clearFastModeCooldown() {
        runtimeState = CooldownState.active();
        if (log.isDebugEnabled()) {
            log.debug("FastModeRuntimeState: cooldown 已手动清除（clearFastModeCooldown）· CC fastMode.ts:235-237");
        }
    }

    /**
     * API 拒绝 fast mode 处理 · CC fastMode.ts:244-255 {@code handleFastModeRejectedByAPI}。
     *
     * <p>CC 设置 orgStatus disabled（reason=preference）+ 持久化 global config
     * （penguinModeOrgEnabled=false）+ userSettings.fastMode=undefined。Java in-memory：
     * 置 {@link #orgDisabled} + 日志（不持久化 config）。
     */
    public static void handleFastModeRejectedByAPI() {
        if (orgDisabled) {
            // CC:245-247 orgStatus.status === 'disabled' → return（幂等）
            return;
        }
        orgDisabled = true;
        log.error("FastModeRuntimeState: API 拒绝 fast mode 请求（400 'Fast mode is not enabled'），永久禁用 fast mode"
            + " · CC fastMode.ts:244-255（Java in-memory，未上线不持久化 config）");
    }

    /**
     * overage 拒绝处理 · CC fastMode.ts:295-313 {@code handleFastModeOverageRejection}。
     *
     * <p>CC 计算 reason 专属消息 + 日志；非 out-of-credits 原因 → 永久禁用 fast mode
     * （updateSettingsForSource + saveGlobalConfig）。Java in-memory：消息映射 + 日志 +
     * 非 out-of-credits 置 {@link #orgDisabled}。
     *
     * @param reason overage 禁用原因（CC header anthropic-ratelimit-unified-overage-disabled-reason 值）
     */
    public static void handleFastModeOverageRejection(String reason) {
        String message = getOverageDisabledMessage(reason);
        log.error("FastModeRuntimeState: fast mode overage 拒绝: {} — {} · CC fastMode.ts:295-313",
            reason != null ? reason : "unknown", message);
        log.warn("FastModeRuntimeState: tengu_fast_mode_overage_rejected 等价 {{overage_disabled_reason={}}} · CC fastMode.ts:300-303",
            reason != null ? reason : "unknown");
        if (!isOutOfCreditsReason(reason)) {
            orgDisabled = true;
            log.warn("FastModeRuntimeState: overage 拒绝非 out-of-credits，永久禁用 fast mode"
                + " · CC fastMode.ts:305-311（Java in-memory，未上线不持久化 config）");
        }
    }

    /**
     * overage 禁用原因 → 用户可读消息 · CC fastMode.ts:263-284 {@code getOverageDisabledMessage}。
     *
     * @param reason header 原始值（null → 默认分支）
     * @return 中文解释（CC 原英文意图）
     */
    public static String getOverageDisabledMessage(String reason) {
        if (reason == null) {
            return "Fast mode disabled · extra usage not available";
        }
        return switch (reason) {
            case "out_of_credits" -> "Fast mode disabled · extra usage credits exhausted";
            case "org_level_disabled", "org_service_level_disabled" ->
                "Fast mode disabled · extra usage disabled by your organization";
            case "org_level_disabled_until" ->
                "Fast mode disabled · extra usage spending cap reached";
            case "member_level_disabled" ->
                "Fast mode disabled · extra usage disabled for your account";
            case "seat_tier_level_disabled", "seat_tier_zero_credit_limit", "member_zero_credit_limit" ->
                "Fast mode disabled · extra usage not available for your plan";
            case "overage_not_provisioned", "no_limits_configured" ->
                "Fast mode requires extra usage billing · /extra-usage to enable";
            default -> "Fast mode disabled · extra usage not available";
        };
    }

    /**
     * out-of-credits 原因判定 · CC fastMode.ts:286-288 {@code isOutOfCreditsReason}。
     *
     * <p>out-of-credits 时 CC 不永久禁用 fast mode（只是当下没额度）——overage 拒绝也保留 fast mode。
     */
    static boolean isOutOfCreditsReason(String reason) {
        return "org_level_disabled_until".equals(reason) || "out_of_credits".equals(reason);
    }

    /**
     * 测试复位 · 清空 cooldown + org 禁用标志（CC module-level 状态无等价；测试专用，public 供跨包测试）。
     */
    public static void reset() {
        runtimeState = CooldownState.active();
        hasLoggedCooldownExpiry = false;
        orgDisabled = false;
    }
}

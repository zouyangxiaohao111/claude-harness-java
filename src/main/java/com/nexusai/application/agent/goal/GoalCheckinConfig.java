package com.nexusai.application.agent.goal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 目标 check-in / 重试的常量与配置解析 · 对齐 CC claude.exe v2.1.318。
 *
 * <p><b>逐字对齐 CC 的常量</b>（claude.exe v2.1.318 字节码实证）：
 * <pre>
 * Lp = 30      // CLAUDE_CODE_GOAL_CHECKIN_MINUTES 默认 30 分钟
 * Ur = 60000   // 最小 check-in 间隔（1 分钟）
 * Up = 2       // 指数退避倍数（base * 2^min(count, 2)）
 * Np = 3       // idle check-in 上限（超过只重排 tick，不再注入提示）
 * Bp = 120     // 提示里任务描述截断宽度
 * Di = [60000, 300000, 900000]   // 评估器 error 重试三档（1/5/15 分钟）
 * yg = 0.2     // 重试间隔的随机抖动比例（0~20%）
 * Fi = 3       // 重试次数上限（超限转 gave_up 提示）
 * </pre>
 *
 * <p><b>与 CC 的差异（结构性，非行为）</b>：CC 从 env {@code CLAUDE_CODE_GOAL_CHECKIN_MINUTES}
 * 读；本仓是 Web 应用，用户没有 shell env —— 改成「设置 → 高级」里的 DB 列
 * {@code settings.goal_checkin_minutes}（V258），默认 30，{@code 0} = 关闭。
 * 这正是用户 2026-11-04 拍板的形态。
 */
public final class GoalCheckinConfig {

    private static final Logger log = LoggerFactory.getLogger(GoalCheckinConfig.class);

    /** CC {@code Lp}：check-in 默认间隔（分钟）。 */
    public static final int DEFAULT_CHECKIN_MINUTES = 30;

    /** CC {@code Ur}：最小 check-in 间隔（毫秒）—— 防止 30 分钟配置被写成 0 之外的小值后热循环。 */
    public static final long MIN_CHECKIN_INTERVAL_MS = 60_000L;

    /** CC {@code Up}：指数退避的最大指数（{@code 2^min(count, 2)} ⇒ 1x / 2x / 4x 封顶）。 */
    public static final int MAX_BACKOFF_EXPONENT = 2;

    /** CC {@code Np}：idle check-in 上限 —— 超过后只重排 tick，不再注入提示（避免无限打扰）。 */
    public static final int MAX_IDLE_CHECKINS = 3;

    /** CC {@code Bp}：注入提示里任务描述的截断宽度。 */
    public static final int TASK_DESCRIPTION_MAX_CHARS = 120;

    /** CC {@code Di}：评估器 error 的重试间隔三档（毫秒）。 */
    public static final long[] RETRY_INTERVALS_MS = {60_000L, 300_000L, 900_000L};

    /** CC {@code yg}：重试间隔随机抖动比例（0~20%，防多会话同时重试）。 */
    public static final double RETRY_JITTER_RATIO = 0.2;

    /** CC {@code Fi}：自动重试次数上限。 */
    public static final int MAX_AUTO_RETRIES = RETRY_INTERVALS_MS.length;

    /**
     * 不可恢复错误 → 直接清目标（CC {@code ru()} 的 {@code fg} 表）。
     *
     * <p>CC 原文：{@code fg = {auth, disk_full, verification_required, billing,
     * context_limit, model_unavailable}} —— 这些错误重试多少次都不会好，
     * 继续重试只会白烧调用次数。
     */
    public static final java.util.Set<String> UNRECOVERABLE_CAUSES = java.util.Set.of(
        "auth", "disk_full", "verification_required", "billing", "context_limit", "model_unavailable");

    private GoalCheckinConfig() {
    }

    /**
     * 「分钟」→「毫秒」并套最小间隔下限。
     *
     * @param minutes 配置值（null 或 ≤0 = 关闭 check-in）
     * @return 生效间隔（毫秒）；0 = 关闭
     */
    public static long resolveCheckinIntervalMs(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return 0L;
        }
        long ms = minutes * 60_000L;
        if (ms < MIN_CHECKIN_INTERVAL_MS) {
            log.warn("[goal] goal_checkin_minutes={} 折算 {}ms 低于下限 {}ms，按最小值生效",
                minutes, ms, MIN_CHECKIN_INTERVAL_MS);
            return MIN_CHECKIN_INTERVAL_MS;
        }
        return ms;
    }

    /**
     * 指数退避间隔 · CC {@code ja(e,n) = e * 2**Math.min(n, Up)}。
     *
     * @param baseMs check-in 基础间隔（毫秒）
     * @param count  已注入次数
     * @return 本次应等待的间隔（毫秒）
     */
    public static long backoffMs(long baseMs, int count) {
        return baseMs * (1L << Math.min(Math.max(count, 0), MAX_BACKOFF_EXPONENT));
    }

    /**
     * 第 n 次评估器 error 的重试间隔（含抖动）· CC {@code Math.round(Di[x] * (1 + Math.random()*yg))}。
     *
     * @param retryIndex 已重试次数（0 = 第一次）
     * @return 间隔（毫秒）；越界 → 第一档
     */
    public static long retryIntervalMs(int retryIndex) {
        long base = (retryIndex >= 0 && retryIndex < RETRY_INTERVALS_MS.length)
            ? RETRY_INTERVALS_MS[retryIndex]
            : RETRY_INTERVALS_MS[0];
        return Math.round(base * (1 + Math.random() * RETRY_JITTER_RATIO));
    }
}

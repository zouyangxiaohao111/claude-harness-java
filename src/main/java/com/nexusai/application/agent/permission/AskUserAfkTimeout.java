package com.nexusai.application.agent.permission;

import java.util.function.Function;

/**
 * AskUserQuestion 的 AFK（用户离开键盘）超时档位 · 对齐 CC claude.exe 2.1.328 的
 * {@code settings.askUserQuestionTimeout}（定义在 {@code 314820551}）。
 *
 * <p><b>CC 原文字面</b>：
 * <ul>
 *   <li>值域 {@code 60s / 5m / 10m / never}（schema 定义处 {@code 314820551}）；</li>
 *   <li>未设置或 {@code never} ⇒ 终端不自动继续（默认关闭）；</li>
 *   <li>有效时长 = {@code CLAUDE_AFK_TIMEOUT_MS ?? 卡片 prop ?? 60000}（{@code 351393303} 的
 *       {@code VOe}）；倒计时阈值 = {@code CLAUDE_AFK_COUNTDOWN_MS ?? 20000}，再
 *       {@code min(..., 时长)}（同处）。</li>
 * </ul>
 *
 * <p><b>本仓一处有意差异（⛔ 不要改成 CC 行为）</b>：CC 允许「只设 {@code CLAUDE_AFK_TIMEOUT_MS}
 * 环境变量」就开启 AFK（{@code afkTimeoutMs !== null || env !== undefined}）。本仓要求设置列
 * 非 {@code never} 才可能开启 —— 用户在产品上选择「从不」是个明确决定，环境变量只能覆盖时长，
 * 不能把「从不」变成开启。理由：本仓设置项就是该能力的唯一产品开关，env 只作运维覆盖。
 *
 * <p><b>职责边界</b>：本类只做「设置值 + 环境变量 → 生效时长/阈值」的纯计算，不持有状态、
 * 不设 deadline。真正的倒计时在前端权限卡里跑（超时由卡片以 allow 提交已作答项），后端只负责
 * 把本次生效的 {@code afkTimeoutMs} / 阈值随权限请求下发，并把超时结果回填到工具输入。
 */
public enum AskUserAfkTimeout {

    /** 不自动继续（默认；CC 的 {@code never} 与「未设置」同义）。 */
    NEVER("never", null),
    /** 1 分钟。 */
    S60("60s", 60_000L),
    /** 5 分钟。 */
    M5("5m", 300_000L),
    /** 10 分钟。 */
    M10("10m", 600_000L);

    /** 覆盖本次时长的环境变量 · CC original: {@code CLAUDE_AFK_TIMEOUT_MS}（{@code 351393303}）。 */
    public static final String ENV_TIMEOUT_MS = "CLAUDE_AFK_TIMEOUT_MS";

    /** 覆盖倒计时显示阈值（毫秒）的环境变量 · CC original: {@code CLAUDE_AFK_COUNTDOWN_MS}。 */
    public static final String ENV_COUNTDOWN_MS = "CLAUDE_AFK_COUNTDOWN_MS";

    /** 倒计时阈值默认值 · CC original: {@code eYo=20000}（{@code 351393281}）。 */
    public static final long DEFAULT_COUNTDOWN_MS = 20_000L;

    private final String wireValue;
    private final Long millis;

    AskUserAfkTimeout(String wireValue, Long millis) {
        this.wireValue = wireValue;
        this.millis = millis;
    }

    /** 落库/传输用的字面值（{@code never} / {@code 60s} / {@code 5m} / {@code 10m}）。 */
    public String wireValue() {
        return wireValue;
    }

    /** 是否启用（{@code never} ⇒ false）。 */
    public boolean enabled() {
        return millis != null;
    }

    /** 本档位的毫秒数；{@code never} ⇒ 0（调用方应先用 {@link #enabled()} 判断）。 */
    public long millis() {
        return millis == null ? 0L : millis;
    }

    /**
     * 解析设置值 · 对齐 CC schema：只接受 {@code 60s/5m/10m/never}。
     *
     * <p>{@code null} / 空白 / 大小写外的其它值一律 {@link #NEVER}（默认关闭，不抛异常：
     * 这是读侧容错 —— 脏数据不该让权限弹窗崩掉，写侧由
     * {@code SettingsService.update} 做 fail-loud 校验）。
     */
    public static AskUserAfkTimeout parse(String raw) {
        if (raw == null) {
            return NEVER;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return NEVER;
        }
        for (AskUserAfkTimeout t : values()) {
            if (t.wireValue.equalsIgnoreCase(v)) {
                return t;
            }
        }
        return NEVER;
    }

    /**
     * 计算本次生效的超时时长（毫秒）。
     *
     * <p>优先级：设置 = {@code never}/null（含脏值）⇒ {@code null}（关闭，env 不参与）；
     * 否则 {@code CLAUDE_AFK_TIMEOUT_MS}（正数才认）⇒ 该值，否则本档位毫秒数。
     *
     * @param setting 设置列原值（可 null）
     * @param env     环境变量读取器（名 → 值；测试注入 Map::get，生产传 {@code System::getenv}）
     * @return 生效毫秒数；{@code null} = 不启用
     */
    public static Long resolveTimeoutMs(String setting, Function<String, String> env) {
        AskUserAfkTimeout parsed = parse(setting);
        if (!parsed.enabled()) {
            return null;
        }
        Long fromEnv = positiveLong(env != null ? env.apply(ENV_TIMEOUT_MS) : null);
        return fromEnv != null ? fromEnv : parsed.millis();
    }

    /**
     * 计算倒计时显示阈值（毫秒）：剩余时间 ≤ 该阈值时才在卡片上显示剩余秒数
     * （CC {@code showCountdown = remaining*1000 <= threshold}）。
     *
     * <p>{@code CLAUDE_AFK_COUNTDOWN_MS}（正数才认）⇒ 该值，否则 {@link #DEFAULT_COUNTDOWN_MS}；
     * 最后与总时长取 {@code min}（对齐 CC {@code Math.min(L, C)}）。
     *
     * @param timeoutMs 本次生效总时长（毫秒，必须 &gt; 0）
     * @param env       环境变量读取器（名 → 值；可 null）
     * @return 阈值毫秒数（&gt; 0）
     */
    public static long resolveCountdownMs(long timeoutMs, Function<String, String> env) {
        Long fromEnv = positiveLong(env != null ? env.apply(ENV_COUNTDOWN_MS) : null);
        long threshold = fromEnv != null ? fromEnv : DEFAULT_COUNTDOWN_MS;
        return Math.min(threshold, timeoutMs);
    }

    /** 读取一个正数毫秒环境变量；非法/非正/空 ⇒ null。 */
    private static Long positiveLong(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(v);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            // 非法值不抛：权限弹窗不能因为运维填错 env 就崩掉（与 parse 的读侧容错同口径）
            return null;
        }
    }
}

package com.nexusai.kb.repo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 知识库域时间格式化 · 唯一出口。
 *
 * <p><b>为什么必须有这个类</b>（实测缺陷，非推测）：
 *
 * <p>sqlite-jdbc 3.46.0.0 把 {@link LocalDateTime} 直接写进 TEXT 列时，用的是
 * {@link LocalDateTime#toString()}，而它的输出格式<b>随值变化</b>：
 * <pre>
 *   LocalDateTime.of(2026,11,3,9,5,0)   → "2026-11-03T09:05"        (长度 16)
 *   LocalDateTime.of(2026,11,3,9,5,30)  → "2026-11-03T09:05:30"     (长度 19)
 *   ...(纳秒 != 0)                      → "2026-11-03T09:05:00.123" (长度 23)
 * </pre>
 *
 * <p>而 NexusAI 现有 19 张表的时间列由 SQLite {@code datetime('now')} 写入，形态是
 * {@code "2026-11-03 12:26:09"}（<b>空格</b>分隔、固定 19 字符）。
 *
 * <p>两种形态混存时，{@code ORDER BY create_time} 会给出<b>错误的时间序</b>
 * —— 因为空格 {@code 0x20} 小于字母 {@code T} {@code 0x54}，字典序不等于时间序：
 * <pre>
 *   实测 ORDER BY 结果： "2026-11-03 12:26:09" | "2026-11-03T09:05" | "2026-11-03T23:59"
 *                        ↑ 12:26 排到了 09:05 前面 ⇒ 错的
 * </pre>
 *
 * <p><b>解法</b>：所有写入路径统一走 {@link #format(LocalDateTime)}，输出与 SQLite
 * {@code datetime('now')} <b>逐字节同款</b>的 {@code yyyy-MM-dd HH:mm:ss}。
 * 实测格式化后 {@code ORDER BY} 与 {@code >} 比较均正确。
 *
 * <p>⚠️ 精度取舍：SQLite {@code datetime('now')} 本身就是秒精度，本格式化同样只到秒。
 * 需要毫秒的场景（如任务排队序号）请用独立列（INTEGER 时间戳或自增序号），
 * 不要指望本列。
 */
public final class KbTimeFormat {

    /** 与 SQLite {@code datetime('now')} 逐字节同款的格式。 */
    public static final DateTimeFormatter SQLITE_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private KbTimeFormat() {
        // 静态工具类
    }

    /**
     * 格式化为 SQLite 同款时间串。
     *
     * @param value 时间值；null 原样返回 null（DB 列有默认值，写入 null 会走默认）
     * @return {@code yyyy-MM-dd HH:mm:ss} 形态的串
     */
    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(SQLITE_DATETIME);
    }

    /**
     * 解析 SQLite 同款时间串。
     *
     * <p>同时兼容历史数据里的 {@code T} 分隔形态
     * （早期若有以 {@code LocalDateTime.toString()} 写入的行）。
     *
     * @param value 时间串；null/空白返回 null
     * @return 解析结果
     * @throws java.time.format.DateTimeParseException 两种形态都不匹配时抛出（fail loud）
     */
    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        // 先试标准形态；失败再试 ISO 形态（含 T 分隔、可带毫秒）
        try {
            return LocalDateTime.parse(trimmed, SQLITE_DATETIME);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDateTime.parse(trimmed);
        }
    }

    /**
     * 当前时间（已格式化）· 写入用。
     *
     * @return {@code yyyy-MM-dd HH:mm:ss} 形态的当前时间
     */
    public static String now() {
        return format(LocalDateTime.now());
    }
}

package com.nexusai.domain.im_message;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * {@code im_message.sent_at} 的<b>唯一</b>时间归一化入口（V315）。
 *
 * <h2>🔴 为什么必须有这个类（这是持久化层留下的坑）</h2>
 * {@code sent_at} 是 TEXT 列，区间过滤与排序全靠<b>字典序</b>比较
 * （见 {@code ImMessageMapper.selectByChat} 的 {@code sent_at >= ?} 与
 * {@code orderBy("sent_at")}）。
 * ⇒ 只要写入方用了不同的<b>时区偏移</b>（{@code +08:00} / {@code +00:00} / {@code Z}），
 * 字典序就<b>不等于</b>时间序 ⇒ 区间过滤静默漏数据、排序静默错乱，<b>且不报任何错</b>。
 * <p>具体炸法：{@code "2026-11-18T02:00:00Z"}（= 10:00 +08:00）与
 * {@code "2026-11-18T09:00:00+08:00"} 比较时，{@code 'Z'(0x5A) > '+'(0x2B)}
 * ⇒ 字典序说「Z 那条更晚」，实际它更早 ⇒ 区间查询把该出现的消息漏掉。
 *
 * <h2>规范化规则（唯一口径）</h2>
 * <ul>
 *   <li><b>偏移统一</b>：{@link #CANONICAL_OFFSET}（{@code +08:00}）—— 与本仓其它时间列
 *       （{@code im_chat.last_message_at} 等）同口径。</li>
 *   <li><b>形态定宽</b>：{@code yyyy-MM-dd'T'HH:mm:ss.SSS+08:00}（定长 29 字符）⇒
 *       同一偏移下字典序 ≡ 时间序。⛔ 不用 {@code ISO_OFFSET_DATE_TIME}：它在秒为 0 时
 *       会省略秒（{@code 10:00+08:00}），形态就会变宽窄不一。</li>
 *   <li><b>不猜</b>：解析不出来返回 {@code null}，由调用方决定丢弃还是报错 ——
 *       宁可少一条消息，也不要写一条「时间不明」的行去污染排序。</li>
 * </ul>
 *
 * <h2>⚠️ 所有写入 {@code im_message.sent_at} 的地方都必须走这里</h2>
 * 包括：① 本包的拉取层（{@code domain.im_message.fetch}）② 未来的「发送时补写 out」
 * ③ 未来的「扫描 Job 落库」。<b>任何一个写入方绕过本类直接写 ISO 串，
 * 都会把整张表的排序语义打坏。</b>
 */
public final class ImMessageTime {

    /**
     * 统一偏移：{@code +08:00}。
     *
     * <p>为什么是固定偏移而不是 {@code ZoneId.systemDefault()}：系统时区会随机器/夏令时变化，
     * 同一台机器在不同时刻写出的两行可能偏移不同 ⇒ 字典序又坏了。固定偏移是唯一能长期成立的写法。
     */
    public static final ZoneOffset CANONICAL_OFFSET = ZoneOffset.ofHours(8);

    /**
     * 规范形态：{@code yyyy-MM-dd'T'HH:mm:ss.SSS+08:00}（定宽 ⇒ 字典序 == 时间序）。
     *
     * <p>带毫秒是为了不丢飞书 {@code create_time}（毫秒）的亚秒精度 —— 同一秒内的多条消息
     * 靠它定序（否则顺序随机，每页可能不一样）。
     */
    private static final DateTimeFormatter CANONICAL =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(CANONICAL_OFFSET);

    /**
     * 企微 {@code send_time} 形态：{@code YYYY-MM-DD HH:MM:SS}（平台只给到秒，且不带偏移）。
     *
     * <p>按 {@link #CANONICAL_OFFSET} 解释 —— 企微是国内平台的本地时间，不做时区猜测。
     */
    private static final DateTimeFormatter WECOM_LOCAL =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(CANONICAL_OFFSET);

    /**
     * epoch「秒 vs 毫秒」的分界：{@code 1e11}。
     *
     * <p>依据：当前 epoch 秒约 {@code 1.8e9}，epoch 毫秒约 {@code 1.8e12}；
     * 而 {@code 1e11} 秒 = 公元 5138 年 ⇒ 不可能是秒。
     * ⇒ 纯数字输入里，{@code >= 1e11} 一律当毫秒。
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;

    private ImMessageTime() {
    }

    /**
     * 任意时间表示 → 规范形态。
     *
     * <p>支持：ISO-8601 带偏移（{@code 2026-11-18T10:00:00+08:00} / {@code ...Z}）、
     * {@code Instant} 形态（{@code 2026-11-18T02:00:00Z}）、企微本地形态
     * （{@code 2026-11-18 10:00:00}）、纯数字（epoch 秒）。
     *
     * @return 规范形态；解析不出来 ⇒ {@code null}（<b>不猜</b>）
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // ① ISO 带偏移（含 Z）
        try {
            return format(OffsetDateTime.parse(s));
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        // ② Instant 形态（Z 结尾）
        try {
            return format(Instant.parse(s));
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        // ③ 企微本地形态（无偏移）
        try {
            return format(LocalDateTime.parse(s, WECOM_LOCAL));
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        // ④ 纯数字（epoch）：秒还是毫秒？按量级判（1e11 秒 = 公元 5138 年，不可能）
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                long v = Long.parseLong(s);
                return v >= EPOCH_MILLIS_THRESHOLD
                    ? canonicalFromEpochMillis(v)
                    : canonicalFromEpochSeconds(v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** epoch 秒 → 规范形态。 */
    public static String canonicalFromEpochSeconds(long epochSeconds) {
        return format(Instant.ofEpochSecond(epochSeconds));
    }

    /** epoch 毫秒 → 规范形态（飞书 {@code create_time} 是毫秒）。 */
    public static String canonicalFromEpochMillis(long epochMillis) {
        return format(Instant.ofEpochMilli(epochMillis));
    }

    /** 当前时刻（规范形态）。 */
    public static String canonicalNow() {
        return format(Instant.now());
    }

    /**
     * 解析成 {@link Instant}（用于按<b>真实时间</b>而不是字符串排序 / 过滤）。
     *
     * @return 解析失败 ⇒ {@code null}
     */
    public static Instant toInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        try {
            return LocalDateTime.parse(s, WECOM_LOCAL).toInstant(CANONICAL_OFFSET);
        } catch (DateTimeParseException ignored) {
            // 继续试下一种
        }
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                long v = Long.parseLong(s);
                return v >= EPOCH_MILLIS_THRESHOLD
                    ? Instant.ofEpochMilli(v)
                    : Instant.ofEpochSecond(v);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把任意形态收敛到规范形态；已是规范形态则原样返回。
     *
     * <p>用于「读侧兜底」：库里若混进了非规范形态（某个写入方绕过本类），
     * 读出来时先修一次，排序才不会错。
     */
    public static String canonicalOrSelf(String raw) {
        String c = canonical(raw);
        return c != null ? c : raw;
    }

    private static String format(Instant instant) {
        return CANONICAL.format(instant.atOffset(CANONICAL_OFFSET));
    }

    private static String format(OffsetDateTime odt) {
        return CANONICAL.format(odt);
    }

    private static String format(LocalDateTime ldt) {
        return CANONICAL.format(ldt.atOffset(CANONICAL_OFFSET));
    }
}

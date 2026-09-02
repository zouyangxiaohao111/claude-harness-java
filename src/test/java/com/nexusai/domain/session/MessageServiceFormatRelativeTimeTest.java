package com.nexusai.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MessageService#formatRelativeTimeAgo} 意图测试 · Phase 5 time 字段真实时间补齐。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：原 {@code ChatMessageDto.time} 恒 "刚刚"（MessageService.java:462
 * Phase 4 stub），前端无法展示消息真实时间。补齐后按创建时间距 now 返回中文人读格式——若实现退化
 * （如恒返回"刚刚"/日期格式错/边界错），此测试必红。对齐 CC {@code formatRelativeTimeAgo}
 * （utils/format.ts:186-198 Intl.RelativeTimeFormat 语义），输出为项目中文契约（「2 分钟」/「刚刚」）。
 */
class MessageServiceFormatRelativeTimeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-24T10:00:00+08:00");

    /** 反射调用 private static formatRelativeTimeAgo（对齐 ChatServiceReplayPersistReasoningTest 反射先例）。 */
    private static String format(OffsetDateTime createdAt, OffsetDateTime now) throws Exception {
        Method m = MessageService.class.getDeclaredMethod(
            "formatRelativeTimeAgo", OffsetDateTime.class, OffsetDateTime.class);
        m.setAccessible(true);
        return (String) m.invoke(null, createdAt, now);
    }

    @Test
    @DisplayName("createdAt 为 null → 刚刚（无时间戳兜底，不抛）")
    void nullCreatedAt_returnsJiuGang() throws Exception {
        assertEquals("刚刚", format(null, NOW));
    }

    @Test
    @DisplayName("< 60s → 刚刚（对齐 CC Intl.RelativeTimeFormat 秒级「刚刚」）")
    void underOneMinute_returnsJiuGang() throws Exception {
        assertEquals("刚刚", format(NOW.minusSeconds(30), NOW));
    }

    @Test
    @DisplayName("5 分钟 → 5 分钟前")
    void fiveMinutes_returnsMinAgo() throws Exception {
        assertEquals("5 分钟前", format(NOW.minusMinutes(5), NOW));
    }

    @Test
    @DisplayName("3 小时 → 3 小时前（分钟边界 < 3600s 取小时）")
    void threeHours_returnsHourAgo() throws Exception {
        assertEquals("3 小时前", format(NOW.minusHours(3), NOW));
    }

    @Test
    @DisplayName("2 天 → 2 天前（小时边界 < 86400s 取天）")
    void twoDays_returnsDayAgo() throws Exception {
        assertEquals("2 天前", format(NOW.minusDays(2), NOW));
    }

    @Test
    @DisplayName("40 天（≥30 天）→ 具体日期 yyyy-MM-dd")
    void fortyDays_returnsDate() throws Exception {
        assertEquals("2026-07-15", format(NOW.minusDays(40), NOW));
    }
}

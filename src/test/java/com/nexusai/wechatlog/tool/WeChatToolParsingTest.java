package com.nexusai.wechatlog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具层纯函数测试（不依赖 Spring、不依赖数据库）。
 *
 * <h2>为什么这些要测</h2>
 * 工具的输入来自模型的 JSON，字段名/类型都可能不按预期来。这类解析错误会让工具
 * 「静默返回空结果」，比抛异常更难发现。
 */
class WeChatToolParsingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- 时间解析 ----

    @Test
    @DisplayName("since 支持 Unix 秒")
    void parsesUnixSeconds() {
        assertEquals(1700000000L, WeChatSearchMessagesTool.parseTime("1700000000"));
    }

    @Test
    @DisplayName("since 支持 ISO 日期（按本地时区当日零点）")
    void parsesIsoDate() {
        long t = WeChatSearchMessagesTool.parseTime("2026-11-01");
        assertTrue(t > 0, "应解析成功");
        var dt = java.time.Instant.ofEpochSecond(t)
                .atZone(java.time.ZoneId.systemDefault());
        assertEquals(2026, dt.getYear());
        assertEquals(11, dt.getMonthValue());
        assertEquals(1, dt.getDayOfMonth());
        assertEquals(0, dt.getHour(), "应是当日零点");
    }

    @Test
    @DisplayName("since 支持 ISO 日期时间与空格分隔")
    void parsesDateTime() {
        long a = WeChatSearchMessagesTool.parseTime("2026-11-01T12:00:00");
        long b = WeChatSearchMessagesTool.parseTime("2026-11-01 12:00:00");
        assertTrue(a > 0);
        assertEquals(a, b, "T 与空格两种写法应等价");
    }

    @Test
    @DisplayName("since 为空或无法解析时返回 0（表示不限），不抛异常")
    void handlesBadTimeInput() {
        assertEquals(0L, WeChatSearchMessagesTool.parseTime(null));
        assertEquals(0L, WeChatSearchMessagesTool.parseTime(""));
        assertEquals(0L, WeChatSearchMessagesTool.parseTime("不是时间"));
        assertEquals(0L, WeChatSearchMessagesTool.parseTime("2026-13-99"));
    }

    // ---- 参数解析 ----

    @Test
    @DisplayName("字段缺失/为 null 时文本参数返回 null")
    void textHandlesMissingAndNull() throws Exception {
        JsonNode noField = JSON.readTree("{}");
        assertNull(WeChatSearchMessagesTool.text(noField, "keyword"));

        JsonNode nullField = JSON.readTree("{\"keyword\": null}");
        assertNull(WeChatSearchMessagesTool.text(nullField, "keyword"));

        JsonNode blank = JSON.readTree("{\"keyword\": \"   \"}");
        assertNull(WeChatSearchMessagesTool.text(blank, "keyword"), "纯空白应视为未提供");
    }

    @Test
    @DisplayName("整数参数缺失时用默认值，超范围时被 clamp")
    void intParamDefaultsAndClamps() throws Exception {
        JsonNode empty = JSON.readTree("{}");
        assertEquals(20, WeChatSearchMessagesTool.intVal(empty, "limit", 20));

        JsonNode big = JSON.readTree("{\"limit\": 99999}");
        assertEquals(99999, WeChatSearchMessagesTool.intVal(big, "limit", 20));
        assertEquals(100, WeChatSearchMessagesTool.clamp(
                WeChatSearchMessagesTool.intVal(big, "limit", 20), 1, 100),
                "上限应被夹到 100");

        JsonNode zero = JSON.readTree("{\"limit\": 0}");
        assertEquals(1, WeChatSearchMessagesTool.clamp(0, 1, 100), "下限应被夹到 1");
    }

    @Test
    @DisplayName("时间格式化对非法值返回 ? 而不是崩溃")
    void formatsTimeSafely() {
        assertEquals("?", WeChatSearchMessagesTool.formatTime(null));
        assertEquals("?", WeChatSearchMessagesTool.formatTime(0));
        assertEquals("?", WeChatSearchMessagesTool.formatTime(-1));
        String ok = WeChatSearchMessagesTool.formatTime(1700000000);
        assertTrue(ok.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), ok);
    }

    // ---- 按会话读消息：时间窗解析 ----

    @Test
    @DisplayName("until 只给日期时补到当天 23:59:59")
    void untilDateOnlyMeansEndOfDay() {
        long t = WeChatReadConversationTool.parseUntil("2026-11-01");
        var dt = java.time.Instant.ofEpochSecond(t)
                .atZone(java.time.ZoneId.systemDefault());
        assertEquals(23, dt.getHour());
        assertEquals(59, dt.getMinute());
        assertEquals(59, dt.getSecond());
    }

    @Test
    @DisplayName("时间窗：默认今天 / 只给 since=自动 custom / month=近 30 个自然日")
    void resolvesRanges() {
        long now = 1_800_000_000L;
        long todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond();

        // 什么都不给 ⇒ today（今天的 00:00 ~ now）
        var today = WeChatReadConversationTool.resolveWindow(null, null, null, now);
        assertEquals(todayStart, today.since());
        assertEquals(now, today.until());

        // 只给 since、没给 range ⇒ 自动 custom
        var custom = WeChatReadConversationTool.resolveWindow(null, "2026-10-14 17:30:00", null, now);
        assertTrue(custom.since() > 0);
        assertEquals(now, custom.until());

        // range=month ⇒ todayStart - 29 天 ~ now
        var month = WeChatReadConversationTool.resolveWindow("month", null, null, now);
        assertEquals(todayStart - 29L * 86400, month.since());

        // custom 但两边都没给 ⇒ null（调用方报错）
        assertNull(WeChatReadConversationTool.resolveWindow("custom", null, null, now));
    }
}

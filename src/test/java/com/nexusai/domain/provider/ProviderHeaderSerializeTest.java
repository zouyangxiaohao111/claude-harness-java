package com.nexusai.domain.provider;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * [任务 3] {@code ProviderService.serializeHeaders} / {@code deserializeHeaders} ——
 * {@code providers.extra_headers} 这一列的 JSON 往返。
 *
 * <p><b>WHY（规则九·验证意图）</b>：这条路此前是**手写朴素 JSON**（序列化只转义 {@code "} 不转义 {@code \}，
 * 反序列化按 {@code ,} split、按第一个 {@code :} 切），因此 {@code Accept: application/json, text/event-stream}
 * 这类含逗号的值读回来会被**切碎**并凭空多出一个垃圾键。本类把「值不被破坏」钉死为可执行契约——
 * 这一列马上要承载 opencode 的 {@code x-opencode-session} 等真实 header，破坏是静默的。
 *
 * <p><b>取证方式</b>：直接调用 package-private 的静态方法（为此两个方法已从 {@code private static}
 * 提为 {@code static}，见 {@code ProviderService} 内的注释）。
 *
 * <p><b>改造前的红灯基线（把可见性提为 package-private、实现不动，实测 {@code Tests run: 11, Failures: 4}）</b>：
 * 只有 3 条毒字符用例 + 1 条 warn 用例会红 ——
 * {@code valueWithCommaIsNotSplit}、{@code valueWithQuoteAndBackslashSurvives}、
 * {@code valueWithBracesSurvives}、{@code invalidJsonWarnsWithRawSnippet}。
 * {@code valueWithColonIsNotSplit} / {@code chineseValueRoundTrips} / {@code multiKeyRoundTrips}
 * **改造前就是绿的**（旧实现按第一个 {@code :} 切、且只在 {@code ,} 上出错），它们是**回归哨兵**，
 * 守「别把值改坏」而非「修好了旧缺陷」。
 */
class ProviderHeaderSerializeTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. 毒字符往返 —— **本段不是整体红灯基线**，逐条如实标注（改造前实测，见类 JavaDoc）：
    //    · 改造前红灯（3 条）：valueWithCommaIsNotSplit、valueWithQuoteAndBackslashSurvives、
    //      valueWithBracesSurvives
    //    · 回归哨兵、改造前本来就绿（3 条）：valueWithColonIsNotSplit、chineseValueRoundTrips、
    //      multiKeyRoundTrips —— 它们守的是「别把值改坏」，不是「修好了旧缺陷」，
    //      对旧实现不具备鉴别力（旧实现按第一个 ':' 切、且只在逗号上出错）。
    // ════════════════════════════════════════════════════════════════════

    /** 改造前红灯 ── 旧实现按 ',' split，此输入被切成 2 条并凭空多出垃圾键。 */
    @Test
    @DisplayName("值含逗号不被切碎（Accept: application/json, text/event-stream）")
    void valueWithCommaIsNotSplit() {
        Map<String, String> in = Map.of("Accept", "application/json, text/event-stream");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    /** 回归哨兵（**改造前本来就绿**）：旧实现按**第一个** ':' 切，无逗号的值本就切不错 → 对旧实现无鉴别力。 */
    @Test
    @DisplayName("值含冒号不被切碎（https://gw.example.com:8443/v1）· 回归哨兵")
    void valueWithColonIsNotSplit() {
        Map<String, String> in = Map.of("X-Url", "https://gw.example.com:8443/v1");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    /** 改造前红灯 ── 旧实现不转义 '\'，产出的 `"a\"b\c"` 里 `\c` 是非法 JSON 转义（且值本身也变了）。 */
    @Test
    @DisplayName("值含引号与反斜杠不被破坏（a\"b\\c）")
    void valueWithQuoteAndBackslashSurvives() {
        Map<String, String> in = Map.of("X-Q", "a\"b\\c");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    /** 改造前红灯 ── 旧实现把 `\"` 原样留在值里（连同外层引号一起被剥掉一半），值被破坏。 */
    @Test
    @DisplayName("值含花括号（{\"k\":\"v\"}）")
    void valueWithBracesSurvives() {
        Map<String, String> in = Map.of("X-J", "{\"k\":\"v\"}");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    /** 回归哨兵（**改造前本来就绿**）：无逗号无冒号，旧实现两侧都不出错 → 对旧实现无鉴别力。 */
    @Test
    @DisplayName("中文值往返 · 回归哨兵")
    void chineseValueRoundTrips() {
        Map<String, String> in = Map.of("X-Cn", "会话标识");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    /** 回归哨兵（**改造前本来就绿**）：两键值均无毒字符，旧实现的 split/切分恰好正确 → 对旧实现无鉴别力。 */
    @Test
    @DisplayName("多键保持往返（含 ${session_id} 占位符原样透传，展开不归本层管）· 回归哨兵")
    void multiKeyRoundTrips() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("x-opencode-session", "${session_id}");
        in.put("x-opencode-client", "nexusai");
        assertEquals(in, ProviderService.deserializeHeaders(ProviderService.serializeHeaders(in)));
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 对外契约：不得「顺手改进」
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("null 与空 Map 序列化为 null（与改造前一致）")
    void nullAndEmptyMapSerializeToNull() {
        assertNull(ProviderService.serializeHeaders(null));
        assertNull(ProviderService.serializeHeaders(Map.of()));
    }

    // ════════════════════════════════════════════════════════════════════
    // 2.1 值为 null / key 为 null 的条目：跳过 + warn（**不静默入库**）
    //     契约边界：规范把 null 处理放在注入侧（DynamicHeaderExpander.expandAll 跳过 + warn）
    //     与任务 4 写侧校验（isValidHeaderValue(null)=false → 400）。但本层是「任务 3 → 任务 4」
    //     之间的窗口，且 Jackson 会把 null value 静默写成 {"X-A":null} 入库；
    //     改造前手写实现在此处直接 NPE（响亮失败），改造后不得退化为静默。
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("值为 null 的条目：跳过该条 + warn（不得写成 {\"X-A\":null} 静默入库）")
    void nullValueEntryIsSkippedWithWarn() {
        // Map.of 不允许 null，故用 HashMap
        Map<String, String> in = new HashMap<>();
        in.put("X-Good", "1");
        in.put("X-Bad", null);

        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        String json;
        try {
            json = ProviderService.serializeHeaders(in);
            assertTrue(
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("X-Bad") && m.contains("值为 null")),
                "跳过 null 值条目必须留 warn 且带 header 名，实际日志=" + appender.list);
        } finally {
            detachCapture(appender);
        }

        assertNotNull(json, "仍有合法条目，不得整体丢弃");
        assertFalse(json.contains("X-Bad"), "null 值条目必须被跳过，实际 json=" + json);
        assertEquals(Map.of("X-Good", "1"), ProviderService.deserializeHeaders(json));
    }

    @Test
    @DisplayName("key 为 null 的条目：跳过该条 + warn（不得写出非法 JSON）")
    void nullKeyEntryIsSkippedWithWarn() {
        Map<String, String> in = new HashMap<>();
        in.put("X-Good", "1");
        in.put(null, "v");

        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        String json;
        try {
            json = ProviderService.serializeHeaders(in);
            assertTrue(
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("有一个 header 名为 null 的条目")),
                "跳过 null key 条目必须留 warn，实际日志=" + appender.list);
        } finally {
            detachCapture(appender);
        }

        assertEquals(Map.of("X-Good", "1"), ProviderService.deserializeHeaders(json));
    }

    @Test
    @DisplayName("全部条目都是 null 值 → 与「空 Map 存 null」契约一致，仍留 warn")
    void allEntriesNullSerializeToNull() {
        Map<String, String> in = new HashMap<>();
        in.put("X-Bad", null);

        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            assertNull(ProviderService.serializeHeaders(in),
                "全部条目被跳过 ⇒ 等价于空 Map ⇒ 按既有契约存 null（不是 \"{}\"）");
            assertTrue(
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("X-Bad")),
                "整体丢弃也必须留 warn，实际日志=" + appender.list);
        } finally {
            detachCapture(appender);
        }
    }

    @Test
    @DisplayName("坏 JSON 反序列化返回空 Map 且不抛；null / 空白串返回 null（与改造前一致）")
    void invalidJsonDeserializesToEmptyWithoutThrowing() {
        assertEquals(Map.of(), ProviderService.deserializeHeaders("{不是合法JSON"));
        assertNull(ProviderService.deserializeHeaders(null));
        assertNull(ProviderService.deserializeHeaders("   "));
    }

    @Test
    @DisplayName("坏 JSON 反序列化必须 warn 且带上原始片段（否则用户只看到 header 莫名消失）")
    void invalidJsonWarnsWithRawSnippet() {
        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            assertEquals(Map.of(), ProviderService.deserializeHeaders("{不是合法JSON"));
            assertTrue(
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("extra_headers 反序列化失败") && m.contains("不是合法JSON")),
                "反序列化失败必须留 warn 且含原始 json 片段，实际日志=" + appender.list);
        } finally {
            detachCapture(appender);
        }
    }

    /**
     * 合法 JSON 不得触发 warn（守卫不得噪声化）。
     *
     * <p><b>为什么要带正向对照</b>：本用例的主体是**纯否定断言**（{@code noneMatch}），若不先证明
     * 「捕获链路是活的」，它单独运行时**恒绿** —— 「代码没 warn」与「appender 根本没接上 / 日志级别被
     * 调到 WARN 以上」不可区分。故选**自检式**（先合成一条 warn 断言必被捕获 → 清空 → 再跑被测逻辑），
     * 而不是与 {@code invalidJsonWarnsWithRawSnippet} 合并：合并会让「合法路径不噪声化」这条契约
     * 依附于「坏 JSON 契约必须也成功」，一条挂掉会连带掩盖另一条；自检式让两个用例彼此独立、
     * 各自单点可判。自检用的 logger 与 {@code ProviderService} 内的 {@code log} 是**同一个**
     * logback Logger 实例（同 {@code LoggerFactory.getLogger(ProviderService.class)}）。
     *
     * <p><b>前提（已独立复核）</b>：{@code backend/src/test/resources/logback-test.xml} 把
     * {@code com.nexusai} 设为 DEBUG，全仓无对该 logger 调 {@code setLevel} → warn 不会被级别过滤。
     */
    @Test
    @DisplayName("正常 JSON 反序列化不产生任何 warn 日志（守卫不得噪声化）")
    void validJsonDoesNotWarn() {
        ListAppender<ILoggingEvent> appender = attachWarnCapture();
        try {
            // ── 正向对照：证明该 appender 确实挂在被测类的 logger 上、且 warn 级别未被过滤 ──
            capturedLogger().warn("[自检] 捕获链路活性探针");
            assertFalse(appender.list.isEmpty(),
                "捕获链路没生效：appender 未挂上 ProviderService 的 logger，或 warn 级别被过滤 → 本用例的否定断言无意义");
            appender.list.clear();

            // ── 被测逻辑：合法 JSON 不得产生任何 warn ──
            assertEquals(Map.of("X-A", "1"), ProviderService.deserializeHeaders("{\"X-A\":\"1\"}"));
            assertTrue(
                appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(m -> m.contains("extra_headers 反序列化失败")),
                "合法 JSON 不得触发 warn，实际日志=" + appender.list);
        } finally {
            detachCapture(appender);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 序列化产物形状（Jackson 必须是「真 JSON」，不是朴素拼接）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 用**独立** ObjectMapper（不是被测类里那个 {@code HEADERS_JSON}）读回序列化产物，证明产物是合法 JSON。
     *
     * <p><b>为什么输入选「引号 + 反斜杠」而不用逗号值</b>：逗号值经朴素拼接产出的**仍是合法 JSON**
     * （字符串内的逗号本就合法），对本用例**零鉴别力**；而 {@code a"b\c} 经朴素拼接产出
     * {@code {"X-Q":"a\"b\c"}} —— {@code \c} 是非法转义，解析必抛。实测：把 {@code serializeHeaders}
     * 换回手写拼接，本用例变红。
     */
    @Test
    @DisplayName("序列化产物可被独立解析器读回（毒字符输入：引号+反斜杠 → 朴素拼接会产出非法 JSON）")
    void serializedFormIsValidJson() {
        Map<String, String> in = Map.of("X-Q", "a\"b\\c");
        String json = ProviderService.serializeHeaders(in);
        assertNotNull(json);

        Map<String, String> parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            fail("序列化产物不是合法 JSON（说明退回了朴素拼接）：json=[" + json + "] · " + e);
            return;
        }
        assertEquals(in, parsed);
    }

    // ════════════════════════════════════════════════════════════════════
    // 日志捕获工具
    // ════════════════════════════════════════════════════════════════════


    private static ListAppender<ILoggingEvent> attachWarnCapture() {
        ch.qos.logback.classic.Logger logger = capturedLogger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachCapture(ListAppender<ILoggingEvent> appender) {
        capturedLogger().detachAppender(appender);
    }

    /** 与 {@code ProviderService} 内部 {@code log} 同一个 logback Logger 实例（自检探针也走它）。 */
    private static ch.qos.logback.classic.Logger capturedLogger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderService.class);
    }
}

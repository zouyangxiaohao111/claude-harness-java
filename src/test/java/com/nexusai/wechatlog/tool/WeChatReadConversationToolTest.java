package com.nexusai.wechatlog.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.wechatlog.service.WeChatMessageIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WeChatReadConversationTool} 行为测试 · 不依赖 Spring / 数据库。
 *
 * <p>用子类化的 {@link RecordingIndexer} 记录入参并返回固定行，替代 Mockito：
 * 被测行为是「工具如何把入参翻译成时间窗/分页 + 如何渲染」，不是 SQL 本身。
 */
class WeChatReadConversationToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 索引器替身：记录最后一次查询入参，按预置值返回。 */
    static class RecordingIndexer extends WeChatMessageIndexer {
        long total;
        List<Map<String, Object>> rows = List.of();

        String conversationId;
        long since;
        long until;
        int offset;
        int limit;
        boolean ascending;

        RecordingIndexer() {
            super(null);
        }

        @Override
        public long countMessages(String accountWxid, String conversationId,
                                  long sinceUnix, long untilUnix) {
            capture(conversationId, sinceUnix, untilUnix);
            return total;
        }

        @Override
        public List<Map<String, Object>> listMessages(String accountWxid, String conversationId,
                                                      long sinceUnix, long untilUnix,
                                                      int offset, int limit, boolean ascending) {
            capture(conversationId, sinceUnix, untilUnix);
            this.offset = offset;
            this.limit = limit;
            this.ascending = ascending;
            return rows;
        }

        private void capture(String conversationId, long sinceUnix, long untilUnix) {
            this.conversationId = conversationId;
            this.since = sinceUnix;
            this.until = untilUnix;
        }
    }

    private RecordingIndexer indexer;
    private WeChatReadConversationTool tool;

    @BeforeEach
    void setUp() {
        indexer = new RecordingIndexer();
        tool = new WeChatReadConversationTool();
        tool.setIndexer(indexer);
    }

    private ToolUseBlock call(String json) throws Exception {
        return new ToolUseBlock("tu-1", "wechat_read_conversation", JSON.readTree(json));
    }

    private static String text(Object result) {
        return String.valueOf(((com.nexusai.application.agent.tool.ToolResult<?>) result).data());
    }

    private static Map<String, Object> row(long time, String sender, String content, int self) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msg_time", time);
        m.put("sender", sender);
        m.put("search_text", content);
        m.put("content_preview", content);
        m.put("is_self", self);
        return m;
    }

    @Test
    @DisplayName("缺 conversation_id → error，文案提示先用 wechat_list_conversations")
    void missingConversationIdIsError() throws Exception {
        String out = text(tool.execute(call("{}")));
        assertTrue(out.contains("必填"), out);
        assertTrue(out.contains("wechat_list_conversations"), out);
    }

    @Test
    @DisplayName("时间窗内 0 条 → success + 可操作提示（不抛异常）")
    void emptyWindowIsSuccessWithHint() throws Exception {
        indexer.total = 0;
        String out = text(tool.execute(call("{\"conversation_id\":\"g@chatroom\"}")));
        assertTrue(out.contains("没有消息"), out);
        assertTrue(out.contains("同步微信聊天记录"), out);
    }

    @Test
    @DisplayName("total=2 → 首行给总数/翻页信息，两行按查询顺序输出，本人标记")
    void rendersRowsWithPagingInfo() throws Exception {
        indexer.total = 2;
        indexer.rows = List.of(
                row(1000L, "alice", "第一句", 0),
                row(2000L, "wxid_self", "第二句", 1));

        String out = text(tool.execute(call("{\"conversation_id\":\"g@chatroom\"}")));

        assertTrue(out.contains("共 2 条"), out);
        assertTrue(out.contains("has_more=false"), out);
        assertTrue(out.contains("next_offset=-1"), out);
        assertTrue(out.indexOf("第一句") < out.indexOf("第二句"), "应按查询顺序输出");
        assertTrue(out.contains("wxid_self(本人): 第二句"), out);
    }

    @Test
    @DisplayName("total=5，offset=2，返回 2 条 → has_more=true，next_offset=4")
    void pagingComputesNextOffset() throws Exception {
        indexer.total = 5;
        indexer.rows = List.of(
                row(3000L, "a", "三", 0),
                row(4000L, "b", "四", 0));

        String out = text(tool.execute(call(
                "{\"conversation_id\":\"g@chatroom\",\"offset\":2}")));

        assertTrue(out.contains("has_more=true"), out);
        assertTrue(out.contains("next_offset=4"), out);
        assertEquals(2, indexer.offset);
    }

    @Test
    @DisplayName("since > until → 自动交换并在首行标注")
    void swappedWindowIsAnnotated() throws Exception {
        indexer.total = 1;
        indexer.rows = List.of(row(5000L, "a", "内容", 0));

        String out = text(tool.execute(call(
                "{\"conversation_id\":\"g@chatroom\","
                        + "\"since\":\"2026-11-02\",\"until\":\"2026-11-01\"}")));

        assertTrue(out.contains("已交换 since/until"), out);
        assertTrue(indexer.since < indexer.until, "交换后 since 应早于 until");
    }

    @Test
    @DisplayName("limit=9999 → 实参被夹到上限 300")
    void limitIsClamped() throws Exception {
        indexer.total = 0;
        tool.execute(call("{\"conversation_id\":\"g@chatroom\",\"limit\":9999}"));
        assertEquals(WeChatReadConversationTool.MAX_LIMIT, indexer.limit);
    }

    @Test
    @DisplayName("只给 conversation_id → 默认查今天 00:00 至现在")
    void defaultRangeIsToday() throws Exception {
        indexer.total = 0;
        long before = System.currentTimeMillis() / 1000;
        tool.execute(call("{\"conversation_id\":\"g@chatroom\"}"));
        long after = System.currentTimeMillis() / 1000;

        long todayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        assertEquals(todayStart, indexer.since);
        assertTrue(indexer.until >= before && indexer.until <= after,
                "until 应是当前时间");
    }

    @Test
    @DisplayName("range=month → since = 今天 00:00 − 29 天")
    void monthRangeStarts29DaysBack() throws Exception {
        indexer.total = 0;
        tool.execute(call("{\"conversation_id\":\"g@chatroom\",\"range\":\"month\"}"));

        long todayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        assertEquals(todayStart - 29L * 86400, indexer.since);
    }

    @Test
    @DisplayName("range=custom 且两端都没给 → error，文案提示补时间")
    void customWithoutBothEndsIsError() throws Exception {
        String out = text(tool.execute(call(
                "{\"conversation_id\":\"g@chatroom\",\"range\":\"custom\"}")));
        assertTrue(out.contains("没有可用的时间范围"), out);
    }

    @Test
    @DisplayName("text 为空时回落 content_preview")
    void fallsBackToPreviewWhenSearchTextBlank() throws Exception {
        indexer.total = 1;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("msg_time", 6000L);
        m.put("sender", "a");
        m.put("search_text", "");
        m.put("content_preview", "预览内容");
        m.put("is_self", 0);
        indexer.rows = List.of(m);

        String out = text(tool.execute(call("{\"conversation_id\":\"g@chatroom\"}")));
        assertTrue(out.contains("预览内容"), out);
    }

    @Test
    @DisplayName("order=desc → 传给索引器的 ascending=false")
    void descOrderPassesThrough() throws Exception {
        indexer.total = 0;
        tool.execute(call("{\"conversation_id\":\"g@chatroom\",\"order\":\"desc\"}"));
        assertFalse(indexer.ascending);
    }

    @Test
    @DisplayName("只给 until（日期）→ since 补到该日 00:00，until 补到该日 23:59:59")
    void onlyUntilFillsStartOfThatDay() throws Exception {
        indexer.total = 0;
        tool.execute(call("{\"conversation_id\":\"g@chatroom\",\"until\":\"2026-11-01\"}"));

        long startOfDay = LocalDate.of(2026, 11, 1)
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        assertEquals(startOfDay, indexer.since);
        assertEquals(startOfDay + 86399, indexer.until);
    }
}

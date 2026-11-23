package com.nexusai.wechatlog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.wechatlog.service.WeChatMessageIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 工具：按会话 + 时间段列出本机微信消息（不按关键词过滤）。
 *
 * <h2>与 wechat_search_messages 的分工</h2>
 * 搜索是「找命中关键词的片段」；本工具是「把某段时间里这个会话说的话按顺序全部读一遍」。
 * 后者才能看到不含关键词、但构成上下文的句子。
 *
 * <h2>时间范围</h2>
 * 默认只列今天（本地 00:00 起）；{@code range=month} 列近 30 个自然日；
 * {@code range=custom} 用 {@code since}/{@code until}（只给一边时另一边自动补齐）。
 *
 * <h2>数据来源</h2>
 * 只读索引表 {@code wechat_message}（{@code search_text} 列，≤500 字符）。
 * 不读微信原始库、不提权；索引滞后时由用户执行一次「同步微信聊天记录」。
 */
@Component
public class WeChatReadConversationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WeChatReadConversationTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 300;

    @Autowired
    private WeChatMessageIndexer indexer;

    @Value("${nexusai.wechatlog.default-account:}")
    private String defaultAccount;

    /** [测试注入] 索引服务。 */
    public void setIndexer(WeChatMessageIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public String name() {
        return "wechat_read_conversation";
    }

    @Override
    public String description() {
        return "按会话 + 时间段列出本机微信聊天记录（不按关键词过滤），按时间升序返回，"
                + "支持 limit/offset 翻页。用于「把某段时间里这个群/这个人说的话全部看一遍」。"
                + "conversation_id 先用 wechat_list_conversations 获取；"
                + "数据来自本机已同步索引，只覆盖已同步的记录。";
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        props.set("conversation_id", JSON.objectNode().put("type", "string")
                .put("description", "会话标识（wxid 或 xxx@chatroom）；"
                        + "先用 wechat_list_conversations 获取，禁止编造"));
        props.set("range", JSON.objectNode().put("type", "string")
                .put("description", "时间范围：today（默认，今天 00:00 起）/ "
                        + "month（含今天在内的最近 30 个自然日）/ custom（用 since/until）"));
        props.set("since", JSON.objectNode().put("type", "string")
                .put("description", "仅 custom 生效：起始时间（含端点）。"
                        + "ISO-8601 / yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / Unix 秒"));
        props.set("until", JSON.objectNode().put("type", "string")
                .put("description", "仅 custom 生效：结束时间（含端点）；"
                        + "只给日期时按当天 23:59:59"));
        props.set("limit", JSON.objectNode().put("type", "integer")
                .put("description", "返回条数（默认 " + DEFAULT_LIMIT
                        + "，上限 " + MAX_LIMIT + "）"));
        props.set("offset", JSON.objectNode().put("type", "integer")
                .put("description", "翻页偏移（默认 0）"));
        props.set("order", JSON.objectNode().put("type", "string")
                .put("description", "asc（默认，按对话顺序）/ desc"));

        root.putArray("required").add("conversation_id");
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        String toolUseId = call.id();
        try {
            JsonNode in = call.input();
            String conversationId = WeChatSearchMessagesTool.text(in, "conversation_id");
            if (conversationId == null) {
                return ToolResult.error(toolUseId,
                        "conversation_id 必填：先用 wechat_list_conversations 拿到会话 id，不要凭空编。");
            }

            long now = System.currentTimeMillis() / 1000;
            String range = WeChatSearchMessagesTool.text(in, "range");
            String sinceRaw = WeChatSearchMessagesTool.text(in, "since");
            String untilRaw = WeChatSearchMessagesTool.text(in, "until");
            Window w = resolveWindow(range, sinceRaw, untilRaw, now);
            if (w == null) {
                return ToolResult.error(toolUseId,
                        "没有可用的时间范围：请用 range=today/month，"
                                + "或给 since/until（如 since=2026-10-14 17:30:00）。");
            }
            long since = w.since();
            long until = w.until();
            boolean swapped = w.swapped();

            int limit = WeChatSearchMessagesTool.clamp(
                    WeChatSearchMessagesTool.intVal(in, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
            int offset = Math.max(0, WeChatSearchMessagesTool.intVal(in, "offset", 0));
            boolean ascending = !"desc".equalsIgnoreCase(
                    WeChatSearchMessagesTool.text(in, "order"));
            String account = (defaultAccount == null || defaultAccount.isBlank())
                    ? null : defaultAccount;

            long total = indexer.countMessages(account, conversationId, since, until);
            List<Map<String, Object>> rows = indexer.listMessages(
                    account, conversationId, since, until, offset, limit, ascending);

            if (total == 0) {
                return ToolResult.success(toolUseId,
                        "会话 " + conversationId + " 在 "
                                + WeChatSearchMessagesTool.formatTime(since) + " ~ "
                                + WeChatSearchMessagesTool.formatTime(until)
                                + " 内没有消息。可放宽时间范围；"
                                + "若确认该时段有消息，请在设置里执行一次「同步微信聊天记录」。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(total).append(" 条；本次返回 ").append(rows.size())
                    .append(" 条（offset=").append(offset)
                    .append("，limit=").append(limit).append("）");
            if (swapped) {
                sb.append("（已交换 since/until）");
            }
            sb.append('\n');
            int next = offset + rows.size();
            boolean hasMore = next < total;
            sb.append("has_more=").append(hasMore)
                    .append("，next_offset=").append(hasMore ? next : -1).append('\n');

            for (Map<String, Object> r : rows) {
                Object text = r.get("search_text");
                if (text == null || String.valueOf(text).isBlank()) {
                    text = r.get("content_preview");
                }
                sb.append("- [")
                        .append(WeChatSearchMessagesTool.formatTime(r.get("msg_time")))
                        .append("] ")
                        .append(r.get("sender") == null ? "?" : r.get("sender"));
                Object self = r.get("is_self");
                if (self instanceof Number n && n.intValue() == 1) {
                    sb.append("(本人)");
                }
                sb.append(": ").append(text == null ? "" : text).append('\n');
            }
            return ToolResult.success(toolUseId, sb.toString().trim());
        } catch (Exception e) {
            log.warn("列出会话消息失败", e);
            return ToolResult.error(toolUseId, "列出会话消息失败: " + e.getMessage());
        }
    }

    /** 解析后的时间窗。 */
    record Window(long since, long until, boolean swapped) {
    }

    /**
     * 解析时间窗：range = today（默认）/ month / custom。
     *
     * <p>显式传 since/until 且未传 range ⇒ 按 custom；显式 range=today/month 时忽略两端。</p>
     *
     * @return null = custom 但 since/until 都没给（调用方报错）
     */
    static Window resolveWindow(String range, String sinceRaw, String untilRaw, long now) {
        String r = (range == null || range.isBlank())
                ? ((sinceRaw != null || untilRaw != null) ? "custom" : "today")
                : range.trim().toLowerCase();
        long todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond();
        return switch (r) {
            case "month" -> new Window(todayStart - 29L * 86400, now, false);
            case "custom" -> {
                long since = WeChatSearchMessagesTool.parseTime(sinceRaw);
                long until = parseUntil(untilRaw);
                if (since <= 0 && until <= 0) {
                    yield null;
                }
                if (until <= 0) {
                    until = now;
                }
                if (since <= 0) {
                    // 只给了 until：从该时刻所在自然日的 00:00 起
                    since = java.time.Instant.ofEpochSecond(until)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .atStartOfDay(java.time.ZoneId.systemDefault())
                            .toEpochSecond();
                }
                boolean swapped = since > until;
                yield swapped ? new Window(until, since, true) : new Window(since, until, false);
            }
            case "today" -> new Window(todayStart, now, false);
            default -> new Window(todayStart, now, false); // 未知值按 today 容错
        };
    }

    /**
     * until 解析：只给日期（10 位）时补到当天 23:59:59，使「到某天为止」含该日。
     */
    static long parseUntil(String s) {
        long t = WeChatSearchMessagesTool.parseTime(s);
        if (t > 0 && s != null && s.trim().length() == 10) {
            return t + 86399;
        }
        return t;
    }
}

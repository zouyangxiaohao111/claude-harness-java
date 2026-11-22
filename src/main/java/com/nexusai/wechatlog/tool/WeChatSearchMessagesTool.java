package com.nexusai.wechatlog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.wechatlog.service.WeChatLogService;
import com.nexusai.wechatlog.service.WeChatMessageIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * AI 工具：在本机微信聊天记录里按关键词/会话/时间检索。
 *
 * <h2>数据来源</h2>
 * 只读 NexusAI 自己的索引表 {@code wechat_message}（摘要），不回读微信库。
 * 索引由定时任务 {@code WeChatLogSyncJob} 维护。
 *
 * <h2>⚠️ 为什么工具名用下划线</h2>
 * 与知识库那组工具（{@code search_knowledge}）保持一致：这是<b>本仓新增能力</b>，
 * 不对齐 CC 的 PascalCase 命名，用源侧风格便于与上游对照。
 *
 * <h2>⚠️ 隐私边界（重要）</h2>
 * 工具只返回<b>摘要</b>（{@code content_preview}，≤120 字符），不返回完整正文。
 * 模型要看上下文时，再用 {@code wechat_read_conversation} 按会话+时间窗读一小段。
 * 这样避免一次把整段私聊灌进模型上下文。
 */
@Component
public class WeChatSearchMessagesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WeChatSearchMessagesTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    @Autowired
    private WeChatMessageIndexer indexer;

    @Value("${nexusai.wechatlog.default-account:}")
    private String defaultAccount;

    @Override
    public String name() {
        return "wechat_search_messages";
    }

    @Override
    public String description() {
        return "在本机微信聊天记录索引中按关键词检索消息摘要。"
                + "返回会话、发送者、时间、消息类型与一小段内容预览。"
                + "适用于「某人说过什么」「查某件事的聊天记录」这类问题。"
                + "注意：只覆盖本机已同步的聊天记录；返回的是摘要不是全文。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode keyword = JSON.objectNode();
        keyword.put("type", "string");
        keyword.put("description", "关键词（对消息文本做包含匹配）；留空表示不限关键词");
        props.set("keyword", keyword);

        ObjectNode conversation = JSON.objectNode();
        conversation.put("type", "string");
        conversation.put("description", "限定会话标识（wxid 或 xxx@chatroom）；留空表示全部会话");
        props.set("conversation_id", conversation);

        ObjectNode since = JSON.objectNode();
        since.put("type", "string");
        since.put("description", "起始时间，ISO-8601（如 2026-11-01）或 Unix 秒；留空表示不限");
        props.set("since", since);

        ObjectNode limit = JSON.objectNode();
        limit.put("type", "integer");
        limit.put("description", "返回条数（默认 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "）");
        props.set("limit", limit);

        return root;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentToolResult<?> execute(ToolUseBlock call) {
        String toolUseId = call.id();
        try {
            JsonNode in = call.input();
            String keyword = text(in, "keyword");
            String conversationId = text(in, "conversation_id");
            long sinceUnix = parseTime(text(in, "since"));
            int limit = clamp(intVal(in, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
            String account = resolveAccount();

            List<Map<String, Object>> rows =
                    indexer.search(account, keyword, conversationId, sinceUnix, limit);

            if (rows.isEmpty()) {
                return ToolResult.success(toolUseId,
                        "没有找到匹配的微信聊天记录。"
                                + "可能原因：该账号尚未同步（需在设置里手动同步一次）、"
                                + "关键词不匹配、或时间范围外。");
            }
            return ToolResult.success(toolUseId, render(rows));
        } catch (Exception e) {
            log.warn("检索微信消息失败", e);
            return ToolResult.error(toolUseId, "检索微信消息失败: " + e.getMessage());
        }
    }

    private String resolveAccount() {
        return (defaultAccount == null || defaultAccount.isBlank()) ? null : defaultAccount;
    }

    private String render(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(rows.size()).append(" 条微信消息：\n");
        for (Map<String, Object> r : rows) {
            Object conv = r.get("conversation_id");
            Object sender = r.get("sender");
            Object time = r.get("msg_time");
            Object type = r.get("msg_type");
            Object preview = r.get("content_preview");
            Object self = r.get("is_self");

            sb.append("- [").append(formatTime(time)).append("] ");
            sb.append("会话=").append(conv);
            sb.append(" 发送者=").append(sender == null ? "?" : sender);
            if (self != null && ((Number) self).intValue() == 1) {
                sb.append("(本人)");
            }
            int t = type == null ? 0 : ((Number) type).intValue();
            sb.append(" 类型=").append(WeChatMessageIndexer.typeName(t));
            sb.append(" | ").append(preview == null ? "" : preview).append('\n');
        }
        return sb.toString().trim();
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    static String text(JsonNode in, String field) {
        if (in == null || !in.hasNonNull(field)) {
            return null;
        }
        String v = in.get(field).asText();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    static int intVal(JsonNode in, String field, int def) {
        if (in == null || !in.hasNonNull(field)) {
            return def;
        }
        try {
            return in.get(field).asInt(def);
        } catch (Exception e) {
            return def;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 解析时间：支持 Unix 秒 / ISO-8601 日期 / 日期时间。
     *
     * @return Unix 秒；无法解析或为空时返回 0（表示不限）
     */
    static long parseTime(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        String t = s.trim();
        // 纯数字 → Unix 秒
        if (t.matches("\\d+")) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        // ISO-8601 日期或日期时间（按本地时区）
        try {
            if (t.length() == 10) {
                return java.time.LocalDate.parse(t)
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toEpochSecond();
            }
            return java.time.OffsetDateTime.parse(t).toEpochSecond();
        } catch (Exception ignored) {
            // 继续尝试 LocalDateTime
        }
        try {
            return java.time.LocalDateTime.parse(t.replace(' ', 'T'))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static String formatTime(Object epochSecond) {
        if (!(epochSecond instanceof Number n) || n.longValue() <= 0) {
            return "?";
        }
        return java.time.Instant.ofEpochSecond(n.longValue())
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

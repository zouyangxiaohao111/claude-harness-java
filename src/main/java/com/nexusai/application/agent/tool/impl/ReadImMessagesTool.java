package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_message.ImMessageQueryService;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_message.ImMessageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ReadImMessages} 工具 · 让 agent <b>读到某个会话的历史消息</b>。
 *
 * <h2>为什么需要这个工具</h2>
 * agent 能「发消息」（{@code SendImAsUser}）却<b>读不到对方说了什么</b> ⇒ 它无法回答
 * 「张三刚才在群里说了什么」「这个需求谁提的」。
 * <p>本工具把三平台差异极大的读能力（飞书能翻到底 / 企微只有 7 天 / 微信在本机库）
 * 收敛成一个接口：<b>传 {@code imch-xxx}，拿回一串消息</b>。
 *
 * <h2>数据来源是「本地 ∪ 官方」</h2>
 * 见 {@link ImMessageQueryService}：本地表（{@code im_message}）是缓存 + 补缺，
 * 官方接口是增量来源。工具层不关心细节，只负责把结果讲清楚。
 *
 * <h2>⚠️ 返回值必须带 platform 与 origin</h2>
 * <ul>
 *   <li>{@code platform} —— 「这是飞书还是企微还是微信」；三平台的消息语义不同
 *       （企微读不到 bot 自己发的消息 ⇒ agent 不能据此断言「对方没回」）。</li>
 *   <li>{@code origin} —— 「这条来自本地还是官方」；官方拉不到时 agent 才知道
 *       「这段历史可能不全」。</li>
 * </ul>
 *
 * <h2>🔴 返回 JSON <b>字符串</b>，不是 Map</h2>
 * 落库链路（{@code LlmAgentLoop.toolResultMessage} / {@code applyToolResultBudget}）只对
 * {@code String} data 生成 content，Map 会落成<b>空 content</b>（本仓已踩过两次）。
 */
@Component
public class ReadImMessagesTool implements Tool {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(ReadImMessagesTool.class);

    public static final String NAME = "ReadImMessages";

    public static final String DESCRIPTION =
        "读取某个 IM 会话的历史消息（本地记录 + 平台补拉的并集）。"
            + "chatId 用 ListImChats / SearchImContacts 返回的 imch-xxx。"
            + "返回里带 platform（哪个渠道）与 origin（本地还是官方），"
            + "平台读不到的历史会在 notes 里说明。";

    public static final String PROMPT =
        "Read the message history of one IM chat.\n\n"
            + "`chatId` is the `imch-xxx` id from `ListImChats` (or `SearchImContacts`) — "
            + "never invent one.\n\n"
            + "`since` / `until` are ISO-8601 timestamps; omit `since` to get roughly the last "
            + "7 days. `senderId` narrows the result to one person's messages.\n\n"
            + "Each message carries `platform` (feishu / wecom / wechat), `direction` "
            + "(`in` = somebody else, `out` = sent by us) and `origin` (`official` = from the "
            + "platform, `local` = written by us when sending). Read `notes` before concluding "
            + "that somebody did not reply — some platforms cannot show our own outgoing "
            + "messages at all.";

    /** 默认条数。 */
    public static final int DEFAULT_LIMIT = 50;
    /** 硬上限（防模型一次要 10 万条把上下文撑爆）。 */
    public static final int MAX_LIMIT = 200;
    /** 不传 {@code since} 时的默认窗口（天）。 */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    @Autowired(required = false)
    private ImMessageQueryService queryService;

    /** [测试注入] 消息查询服务。 */
    public void setQueryService(ImMessageQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    /**
     * 只读工具。
     *
     * <p>⚠️ {@code refresh=true} 会把平台消息<b>写进本地缓存表</b>（{@code im_message}），
     * 但它<b>不对外发消息</b>、也不改任何用户可见的状态 ⇒ 仍按只读对待。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 并发安全（只读库 + 只读平台；写缓存走幂等 upsert）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("chatId")
            .put("type", "string")
            .put("description", "The chat to read, as `imch-xxx` from ListImChats or "
                + "SearchImContacts. Required — never invent a chat id.");

        props.putObject("since")
            .put("type", "string")
            .put("description", "Optional. ISO-8601 start of the window, e.g. "
                + "2026-11-10T00:00:00+08:00. Omit to read roughly the last 7 days.");

        props.putObject("until")
            .put("type", "string")
            .put("description", "Optional. ISO-8601 end of the window (inclusive). "
                + "Omit for \"now\".");

        props.putObject("senderId")
            .put("type", "string")
            .put("description", "Optional. Only return messages sent by this person "
                + "(the platform user id, e.g. `ou_...` on Feishu).");

        props.putObject("limit")
            .put("type", "integer")
            .put("description", "Optional. How many messages to return. Default "
                + DEFAULT_LIMIT + ", maximum " + MAX_LIMIT + ".");

        props.putObject("refresh")
            .put("type", "boolean")
            .put("description", "Optional. Pull new messages from the platform before "
                + "answering (default true). Set false to read the local cache only — "
                + "faster, and works when the platform is unreachable.");

        schema.putArray("required").add("chatId");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (queryService == null) {
            return ToolResult.error(call.id(), "Error: IM 消息服务未初始化");
        }
        JsonNode input = call.input();
        String chatId = text(input, "chatId");
        if (chatId == null || chatId.isBlank()) {
            return ToolResult.error(call.id(),
                "Error: chatId 必填（用 ListImChats 返回的 imch-xxx，不要凭空编）");
        }
        String since = text(input, "since");
        String until = text(input, "until");
        String senderId = text(input, "senderId");
        int limit = limitOf(input);
        boolean refresh = input == null || input.path("refresh").isMissingNode()
            || input.path("refresh").isNull() || input.path("refresh").asBoolean(true);

        // 不传 since = 最近 7 天
        if (since == null || since.isBlank()) {
            since = ImMessageTime.canonicalFromEpochSeconds(
                Instant.now().minus(DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS).getEpochSecond());
        }

        ImMessageQueryService.ImMessageReadResult result;
        try {
            result = queryService.readWithNotes(chatId, since, until, senderId, limit, refresh);
        } catch (ValidationException e) {
            return ToolResult.error(call.id(), "Error: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[ReadImMessages] 读取失败 chat={} err={}", chatId, e.toString());
            return ToolResult.error(call.id(),
                "Error: 读取 IM 消息失败: " + e.getClass().getSimpleName());
        }

        List<ImMessageEntry> messages = result.messages() == null ? List.of() : result.messages();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chatId", result.chatId());
        data.put("platform", result.platform());
        data.put("chatName", result.chatName());
        data.put("count", messages.size());

        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (ImMessageEntry m : messages) {
            out.add(describe(m));
        }
        data.put("messages", out);

        List<String> notes = result.notes();
        if (notes != null && !notes.isEmpty()) {
            data.put("notes", notes);
        }
        if (messages.isEmpty()) {
            data.put("hint", "这个会话在给定区间里没有消息。"
                + "试试放宽 since，或确认聊天 id 是否正确（用 ListImChats 复核）。");
        }

        // 🔴 必须序列化成 String 再返回：落库链路只对 String data 生成 content（Map 会落成空）
        try {
            return ToolResult.success(call.id(), JSON.writeValueAsString(data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[ReadImMessages] 序列化失败: {}", e.toString());
            return ToolResult.success(call.id(),
                "{\"chatId\":\"" + result.chatId() + "\",\"messages\":[],\"count\":0}");
        }
    }

    /**
     * 一条消息 → 给模型看的紧凑描述。
     *
     * <p>⛔ <b>不暴露 {@code mediaJson}</b>：平台引用（image_key / file_key / base64 片段）
     * 对模型没用还很占 context ⇒ 媒体消息只给占位文字（{@code [图片]} / {@code [文件: a.pdf]}）。
     */
    private static Map<String, Object> describe(ImMessageEntry m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sentAt", m.sentAt());
        map.put("sender", displaySender(m));
        map.put("senderId", m.senderId());
        map.put("text", textOf(m));
        map.put("msgType", m.msgType());
        map.put("direction", m.direction());
        map.put("origin", m.origin());
        map.put("messageId", m.messageId());
        return map;
    }

    private static String displaySender(ImMessageEntry m) {
        if (m.senderName() != null && !m.senderName().isBlank()) {
            return m.senderName();
        }
        if (m.senderId() != null && !m.senderId().isBlank()) {
            return m.senderId();
        }
        return "未知发送人";
    }

    /** 正文：文本消息给原文；媒体消息给占位（{@code [图片]} / {@code [文件: a.pdf]}）。 */
    static String textOf(ImMessageEntry m) {
        String type = m.msgType() == null ? "" : m.msgType();
        if ("text".equals(type) || type.isEmpty()) {
            return m.content() == null ? "" : m.content();
        }
        String fileName = fileNameOf(m.mediaJson());
        return switch (type) {
            case "image" -> "[图片]";
            case "voice" -> "[语音]";
            case "video" -> "[视频]";
            case "file" -> fileName == null ? "[文件]" : "[文件: " + fileName + "]";
            case "mixed" -> fileName == null ? "[混合消息]" : "[混合消息: " + fileName + "]";
            default -> (m.content() == null || m.content().isBlank())
                ? "[" + type + "]" : m.content();
        };
    }

    /** 从 {@code media_json} 里取文件名（数组元素结构同 {@code InboundMessage.Media}）。 */
    private static String fileNameOf(String mediaJson) {
        if (mediaJson == null || mediaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = JSON.readTree(mediaJson);
            if (arr == null || !arr.isArray()) {
                return null;
            }
            for (JsonNode item : arr) {
                JsonNode name = item.path("fileName");
                if (name.isTextual() && !name.asText().isBlank()) {
                    return name.asText();
                }
            }
        } catch (Exception e) {
            // 媒体引用解析不出来不影响正文展示
            return null;
        }
        return null;
    }

    private static int limitOf(JsonNode input) {
        if (input == null) {
            return DEFAULT_LIMIT;
        }
        JsonNode n = input.path("limit");
        if (n.isMissingNode() || n.isNull() || !n.isNumber()) {
            return DEFAULT_LIMIT;
        }
        int v = n.asInt(DEFAULT_LIMIT);
        if (v <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(v, MAX_LIMIT);
    }

    private static String text(JsonNode input, String field) {
        if (input == null) {
            return null;
        }
        JsonNode n = input.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isTextual()) {
            return null;
        }
        String v = n.asText().trim();
        return v.isEmpty() ? null : v;
    }
}

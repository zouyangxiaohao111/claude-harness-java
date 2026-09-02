package com.nexusai.application.agent.remote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SDK message adapter · 对齐 CC remote/sdkMessageAdapter.ts.
 *
 * <p>CC source: remote/sdkMessageAdapter.ts (302 LOC).
 * Converts CCR SDK-format messages to REPL internal Message types.
 * - assistant → AssistantMessage (uuid, content, timestamp, error)
 * - user → ignored (CCR mode) or user-typed message (historical)
 * - stream_event → StreamEvent
 * - result → ignored unless error
 * - system (init/status/compact_boundary) → SystemMessage
 * - tool_progress → SystemMessage with elapsed_time
 * - auth_status/rate_limit_event → ignored
 * - tool_use_summary → converted（[W9-02 OPD-TS-31] 入站联动，解析 summary + preceding_tool_use_ids）
 */
public final class SDKMessageAdapter {

    private static final Logger log = LoggerFactory.getLogger(SDKMessageAdapter.class);

    public interface ConvertedMessage {
        String type();
    }

    public record ConvertedMessageWrapper(String type, Object message, Object event) implements ConvertedMessage {
        public String type() { return type; }
    }

    /**
     * SDK 消息扁平承载 record · 对齐 CC coreSchemas.ts SDKMessageSchema union。
     *
     * <p>[W9-02 U2] tool_use_summary 三字段按 CC 扁平契约承载于顶层：
     * {@code summary / preceding_tool_use_ids / session_id}（coreSchemas.ts:1769-1778，
     * 与出站 LlmAgentLoop.emitToolUseSummarySdkMessage 同 shape），不再塞入 message 信封。
     */
    public record SDKMessage(String type, Map<String, Object> message, Map<String, Object> compact_metadata,
                              String tool_use_id, String tool_name, Double elapsed_time_seconds,
                              String status, String subtype, List<String> errors, Object tool_use_result,
                              String uuid, String timestamp,
                              String summary, List<String> preceding_tool_use_ids, String session_id) {
        /** Builder for testability. All fields except type are nullable. */
        public static SDKMessage of(String type) {
            return new SDKMessage(type, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        public SDKMessage withType(String t) { return new SDKMessage(t, message, compact_metadata, tool_use_id, tool_name, elapsed_time_seconds, status, subtype, errors, tool_use_result, uuid, timestamp, summary, preceding_tool_use_ids, session_id); }

        /**
         * [W9-02 U2] 扁平 tool_use_summary 构造器 · 对齐 CC SDKToolUseSummaryMessageSchema
         * （coreSchemas.ts:1769-1778）：{type, summary, preceding_tool_use_ids, uuid, session_id}。
         * summary/preceding_tool_use_ids/session_id 置于顶层，message=null。
         */
        public static SDKMessage toolUseSummary(String summary, List<String> precedingToolUseIds,
                                                String uuid, String timestamp, String sessionId) {
            return new SDKMessage("tool_use_summary", null, null, null, null, null, null, null, null, null,
                uuid, timestamp, summary, precedingToolUseIds, sessionId);
        }
    }

    public record ConvertOptions(boolean convertToolResults, boolean convertUserTextMessages) {}

    public ConvertedMessage convertSDKMessage(SDKMessage msg, ConvertOptions opts) {
        Objects.requireNonNull(msg);
        if (opts == null) opts = new ConvertOptions(false, false);

        switch (msg.type()) {
            case "assistant":
                return new ConvertedMessageWrapper("message", Map.of(
                    "type", "assistant",
                    "message", msg.message(),
                    "uuid", msg.uuid(),
                    "requestId", "",
                    "timestamp", msg.timestamp() != null ? msg.timestamp() : java.time.Instant.now().toString(),
                    "error", msg.errors() != null && !msg.errors().isEmpty() ? msg.errors().get(0) : null
                ), null);

            case "user": {
                Object content = msg.message() != null ? msg.message().get("content") : null;
                boolean isToolResult = content instanceof List
                    && ((List<?>) content).stream().anyMatch(b ->
                        b instanceof Map && "tool_result".equals(((Map<?, ?>) b).get("type")));
                if (opts.convertToolResults() && isToolResult) {
                    Map<String, Object> um = new LinkedHashMap<>();
                    um.put("content", content);
                    um.put("toolUseResult", msg.tool_use_result());
                    um.put("uuid", msg.uuid());
                    um.put("timestamp", msg.timestamp());
                    return new ConvertedMessageWrapper("message", um, null);
                }
                if (opts.convertUserTextMessages() && !isToolResult
                    && (content instanceof String || content instanceof List)) {
                    Map<String, Object> um = new LinkedHashMap<>();
                    um.put("content", content);
                    um.put("toolUseResult", msg.tool_use_result());
                    um.put("uuid", msg.uuid());
                    um.put("timestamp", msg.timestamp());
                    return new ConvertedMessageWrapper("message", um, null);
                }
                return ignored();
            }

            case "stream_event":
                return new ConvertedMessageWrapper("stream_event", null,
                    new LinkedHashMap<>(Map.of("event", msg.message())));

            case "result":
                if (!"success".equals(msg.subtype())) {
                    String err = msg.errors() != null && !msg.errors().isEmpty()
                        ? msg.errors().get(0) : "Unknown error";
                    return new ConvertedMessageWrapper("message", Map.of(
                        "type", "system", "subtype", "informational",
                        "content", err, "level", "warning",
                        "uuid", msg.uuid(), "timestamp", msg.timestamp()
                    ), null);
                }
                return ignored();

            case "system":
                if ("init".equals(msg.subtype())) {
                    String content = "Remote session initialized (model: " +
                        (msg.message() != null ? msg.message().getOrDefault("model", "?") : "?") + ")";
                    return new ConvertedMessageWrapper("message", Map.of(
                        "type", "system", "subtype", "informational",
                        "content", content, "level", "info",
                        "uuid", msg.uuid(), "timestamp", msg.timestamp()
                    ), null);
                }
                if ("status".equals(msg.subtype()) && msg.status() != null) {
                    String content = "compacting".equals(msg.status())
                        ? "Compacting conversation…"
                        : "Status: " + msg.status();
                    return new ConvertedMessageWrapper("message", Map.of(
                        "type", "system", "subtype", "informational",
                        "content", content, "level", "info",
                        "uuid", msg.uuid(), "timestamp", msg.timestamp()
                    ), null);
                }
                if ("compact_boundary".equals(msg.subtype())) {
                    // 对齐 CC fromSDKMessages（utils/messages/mappers.ts:53-67）：
                    // subtype='compact_boundary' + content 常量 + compact_metadata → compactMetadata 透传
                    Map<String, Object> system = new LinkedHashMap<>();
                    system.put("type", "system");
                    system.put("subtype", "compact_boundary");
                    system.put("content", "Conversation compacted");
                    system.put("level", "info");
                    system.put("uuid", msg.uuid());
                    system.put("timestamp", msg.timestamp());
                    if (msg.compact_metadata() != null && !msg.compact_metadata().isEmpty()) {
                        // CC original: compact_metadata (SDK 契约) → 内部 camelCase compactMetadata (messages.ts:4540)
                        system.put("compactMetadata", msg.compact_metadata());
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("SDK system 消息转换: subtype=compact_boundary content=Conversation compacted compactMetadata={}",
                            msg.compact_metadata());
                    }
                    return new ConvertedMessageWrapper("message", system, null);
                }
                return ignored();

            case "tool_progress":
                String content = "Tool " + msg.tool_name() + " running for " +
                    msg.elapsed_time_seconds() + "s…";
                return new ConvertedMessageWrapper("message", Map.of(
                    "type", "system", "subtype", "informational",
                    "content", content, "level", "info",
                    "uuid", msg.uuid(), "timestamp", msg.timestamp(),
                    "toolUseID", msg.tool_use_id()
                ), null);

            case "tool_use_summary":
                // [W9-02 OPD-TS-31] 入站联动 · 不再刻意丢弃（CC remote/sdkMessageAdapter.ts:258-260
                // 仍是 ignored，但用户拍板 OPD-TS-31 超越 CC：解析 summary + preceding_tool_use_ids
                // 注入上下文，供 transcript/UI 可观测）。
                // SDK 契约 snake_case（coreSchemas.ts:1769-1778）：{type:'tool_use_summary',
                //   summary, preceding_tool_use_ids, uuid, session_id}。
                return convertToolUseSummaryMessage(msg);

            case "auth_status":
            case "rate_limit_event":
                return ignored();

            default:
                return ignored();
        }
    }

    /**
     * [W9-02 OPD-TS-31] 入站 tool_use_summary 转换 · 对齐 SDK 扁平 snake_case 契约
     * {@code {type:'tool_use_summary', summary, preceding_tool_use_ids, uuid, session_id}}
     * （coreSchemas.ts:1769-1778）。
     *
     * <p>[W9-02 U2 返工] summary/preceding_tool_use_ids/session_id 从 record 顶层组件读取
     * （CC 契约扁平、出站 LlmAgentLoop.emitToolUseSummarySdkMessage 同 shape），
     * 不再读嵌套 message 信封（旧实现读 msg.message().get(...) 在真实 wire 上 message=null
     * → 静默 no-op，reflector E2-1）。
     *
     * <p>语义：summary 文本 + preceding_tool_use_ids（snake_case → camelCase）承载到
     * ConvertedMessage（type='message'，内部 map type='tool_use_summary'），供入站消息
     * 装配处转为 {@code AttachmentMessageDto(type='tool_use_summary')} 注入 AgentState
     * （对齐出站附件通道 HaikuToolUseSummaryGenerator）。缺失字段优雅降级（null/空列表，不抛 NPE）。
     *
     * @param msg 原始 SDK 消息（扁平顶层承载 summary / preceding_tool_use_ids / session_id）
     * @return type='message' 的 ConvertedMessage；任何解析失败仅日志 + 返回 message（不阻断会话）
     */
    private ConvertedMessage convertToolUseSummaryMessage(SDKMessage msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tool_use_summary");
        String summary = msg.summary();
        List<String> precedingToolUseIds = msg.preceding_tool_use_ids() != null
            ? msg.preceding_tool_use_ids()
            : new java.util.ArrayList<>();
        m.put("summary", summary);
        m.put("precedingToolUseIds", precedingToolUseIds);
        m.put("uuid", msg.uuid());
        m.put("timestamp", msg.timestamp() != null ? msg.timestamp() : java.time.Instant.now().toString());
        if (log.isInfoEnabled()) {
            log.info("SDK tool_use_summary 入站转换: chars={} precedingToolUseIds={} · OPD-TS-31",
                summary != null ? summary.length() : 0, precedingToolUseIds.size());
        }
        return new ConvertedMessageWrapper("message", m, null);
    }

    private static ConvertedMessage ignored() {
        return new ConvertedMessageWrapper("ignored", null, null);
    }

    public boolean isSessionEndMessage(SDKMessage msg) {
        return "result".equals(msg.type());
    }

    public boolean isSuccessResult(SDKMessage msg) {
        return "result".equals(msg.type()) && "success".equals(msg.subtype());
    }

    public String getResultText(SDKMessage msg) {
        if (isSuccessResult(msg) && msg.message() != null) {
            Object result = msg.message().get("result");
            return result != null ? result.toString() : null;
        }
        return null;
    }
}

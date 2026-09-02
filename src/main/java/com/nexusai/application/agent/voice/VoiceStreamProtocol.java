package com.nexusai.application.agent.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Voice Stream STT WebSocket 协议编解码 · 对齐 CC voiceStreamSTT.ts:29-94.
 *
 * <p>L1 语义: 控制消息 + 二进制音频 + 转录事件 三类 JSON. 对齐 CC 类型:
 * <ul>
 *   <li>control: {@code {"type":"KeepAlive"}} / {@code {"type":"CloseStream"}}</li>
 *   <li>events: {@code {"type":"TranscriptText","data":"..."}} / {@code {"type":"TranscriptEndpoint"}}</li>
 *   <li>errors: {@code {"type":"TranscriptError","error_code":"...","description":"..."}}</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1 API Contract</b>: 所有消息必含 {@code type} 字段</li>
 *   <li><b>A2 Golden Trace</b>: open → send audio → CloseStream → 收 TranscriptEndpoint → close</li>
 *   <li><b>A3 State Machine</b>: CREATED→OPEN→FINALIZING→CLOSED 严格单向</li>
 *   <li><b>A4 Tool Sequence</b>: open → keepalive → CloseStream → transcript → close</li>
 *   <li><b>A5 Business Result</b>: transcribe 返回拼接后的 final text</li>
 * </ul>
 */
public final class VoiceStreamProtocol {

    /** control message types. */
    public static final String TYPE_KEEPALIVE = "KeepAlive";
    public static final String TYPE_CLOSE_STREAM = "CloseStream";

    /** event message types (server → client). */
    public static final String TYPE_TRANSCRIPT_TEXT = "TranscriptText";
    public static final String TYPE_TRANSCRIPT_ENDPOINT = "TranscriptEndpoint";
    public static final String TYPE_TRANSCRIPT_ERROR = "TranscriptError";
    public static final String TYPE_ERROR = "error";

    /** finalize 触发原因 (CC voiceStreamSTT.ts:60-65 FinalizeSource). */
    public enum FinalizeSource {
        POST_CLOSESTREAM_ENDPOINT,  // 收到 CloseStream 后收到 TranscriptEndpoint
        NO_DATA_TIMEOUT,            // CloseStream 后无数据 1.5s
        SAFETY_TIMEOUT,             // 安全超时 5s
        WS_CLOSE,                   // WebSocket 主动关闭
        WS_ALREADY_CLOSED           // finalize 时已关
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VoiceStreamProtocol() {}

    /** 编码 KeepAlive 控制帧. */
    public static String encodeKeepAlive() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", TYPE_KEEPALIVE);
        return toJson(node);
    }

    /** 编码 CloseStream 控制帧. */
    public static String encodeCloseStream() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", TYPE_CLOSE_STREAM);
        return toJson(node);
    }

    /** 解码服务端消息. 返回解析后的结构化事件, null 表示非预期格式. */
    public static VoiceStreamEvent decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText("");
            return switch (type) {
                case TYPE_TRANSCRIPT_TEXT ->
                    new VoiceStreamEvent.TranscriptText(node.path("data").asText(""));
                case TYPE_TRANSCRIPT_ENDPOINT ->
                    VoiceStreamEvent.TranscriptEndpoint.INSTANCE;
                case TYPE_TRANSCRIPT_ERROR ->
                    new VoiceStreamEvent.TranscriptError(
                        node.path("error_code").asText(null),
                        node.path("description").asText(null));
                case TYPE_ERROR ->
                    new VoiceStreamEvent.Error(node.path("message").asText("unknown"));
                default -> null;
            };
        } catch (Exception e) {
            return new VoiceStreamEvent.Error("parse failed: " + e.getMessage());
        }
    }

    private static String toJson(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON encode failed", e);
        }
    }

    /** 服务端事件 ADT · 对齐 CC VoiceStreamMessage union. */
    public sealed interface VoiceStreamEvent {
        record TranscriptText(String text) implements VoiceStreamEvent {}
        record TranscriptEndpoint() implements VoiceStreamEvent {
            public static final TranscriptEndpoint INSTANCE = new TranscriptEndpoint();
        }
        record TranscriptError(String errorCode, String description) implements VoiceStreamEvent {}
        record Error(String message) implements VoiceStreamEvent {}
    }
}
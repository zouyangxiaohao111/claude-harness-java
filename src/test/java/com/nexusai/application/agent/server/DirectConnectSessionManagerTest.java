package com.nexusai.application.agent.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [W9-02 OPD-TS-31] DirectConnectSessionManager 入站 streamlined_tool_use_summary 联动测试。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则九 · 测试验证意图):
 * <ol>
 *   <li><b>streamlined 摘要不再被 skip</b> — 用户拍板 OPD-TS-31 超越 CC（CC
 *       server/directConnectManager.ts:108 仍跳过 streamlined_tool_use_summary）：direct-connect
 *       通道回传的流式工具摘要必须路由到 {@code callbacks.onMessage()} 正常消费，否则该链路静默丢失。</li>
 *   <li><b>其余 control 消息 keep 语义</b> — control_response / keep_alive / streamlined_text /
 *       system.post_turn_summary 仍跳过（CC 行为不变），防过度放行破坏既有控制面。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: 把 streamlined_tool_use_summary 加回 skip 列表 → 测试 1 红。
 */
class DirectConnectSessionManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造 manager：wsFactory 不参与 handleIncoming；MessageParser 用 Jackson 解析单行 JSON。 */
    private DirectConnectSessionManager manager(List<Map<String, Object>> received) {
        DirectConnectSessionManager.DirectConnectCallbacks callbacks =
            new DirectConnectSessionManager.DirectConnectCallbacks(
                received::add,                       // onMessage
                (req, requestId) -> {},              // onPermissionRequest
                () -> {},                            // onConnected
                () -> {},                            // onDisconnected
                t -> {});                            // onError
        return new DirectConnectSessionManager(
            new DirectConnectSessionManager.DirectConnectConfig("srv", "sess", "ws://x", "tok"),
            callbacks,
            (url, headers, listener) -> null,        // wsFactory 未使用
            s -> {
                try {
                    return mapper.readValue(s, Map.class);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            Object::toString,
            () -> UUID.randomUUID().toString(),
            DirectConnectSessionManager.stdoutLogger());
    }

    @Test
    @DisplayName("streamlined_tool_use_summary 不再 skip：路由到 onMessage 正常消费（OPD-TS-31）")
    void streamlinedToolUseSummary_routesToOnMessage() {
        List<Map<String, Object>> received = new ArrayList<>();
        DirectConnectSessionManager mgr = manager(received);

        mgr.handleIncoming("{\"type\":\"streamlined_tool_use_summary\","
            + "\"tool_summary\":\"Read 2 files, wrote 1 file\","
            + "\"session_id\":\"s1\",\"uuid\":\"u1\"}");

        assertThat(received).hasSize(1);
        assertThat(received.get(0).get("type")).isEqualTo("streamlined_tool_use_summary");
        assertThat(received.get(0).get("tool_summary")).isEqualTo("Read 2 files, wrote 1 file");
    }

    @Test
    @DisplayName("回归：control_response / keep_alive / streamlined_text / post_turn_summary 仍跳过")
    void controlMessages_stillSkipped() {
        List<Map<String, Object>> received = new ArrayList<>();
        DirectConnectSessionManager mgr = manager(received);

        mgr.handleIncoming("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\"}}\n"
            + "{\"type\":\"keep_alive\"}\n"
            + "{\"type\":\"streamlined_text\",\"text\":\"hello\"}\n"
            + "{\"type\":\"system\",\"subtype\":\"post_turn_summary\",\"content\":\"done\"}\n"
            + "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[]}}");

        // 前 4 条 control/skip 类全部被过滤；仅最后一条 assistant 正常路由
        assertThat(received).hasSize(1);
        assertThat(received.get(0).get("type")).isEqualTo("assistant");
    }
}

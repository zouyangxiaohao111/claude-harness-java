package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tasks.MonitorTaskRunner;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorTool 契约测试 · 对齐 claude.exe v2.1.325 的 MonitorTool。
 *
 * <p>三条硬契约：
 * command 与 ws 二选一；description 必填；command 模式的权限委托 Bash 链。
 */
@DisplayName("[monitor-cc] MonitorTool")
class MonitorToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MonitorTaskRunner runner;
    private MonitorTool tool;

    @BeforeEach
    void setUp() {
        runner = mock(MonitorTaskRunner.class);
        tool = new MonitorTool(FeatureFlags.ALL_DISABLED, null, runner);
    }

    private static JsonNode input(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static ToolUseBlock call(String json) throws Exception {
        return new ToolUseBlock("call-1", "Monitor", input(json));
    }

    @Test
    @DisplayName("schema：command/ws/description/timeout_ms/allowed_domains 齐全")
    void schema_hasCcFields() {
        JsonNode schema = tool.inputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("command")).isTrue();
        assertThat(props.has("ws")).isTrue();
        assertThat(props.has("description")).isTrue();
        assertThat(props.has("timeout_ms")).isTrue();
        assertThat(props.has("allowed_domains")).isTrue();
        assertThat(props.path("allowed_domains").path("items").path("type").asText())
            .isEqualTo("string");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required").toString()).contains("description");
    }

    @Test
    @DisplayName("command 与 ws 同时给 ⇒ 拒绝")
    void bothCommandAndWs_rejected() throws Exception {
        var r = tool.execute(call("{\"command\":\"echo 1\",\"ws\":\"ws://localhost:1\",\"description\":\"d\"}"));
        assertThat(String.valueOf(r.data())).contains("Exactly one of command or ws");
    }

    @Test
    @DisplayName("command 与 ws 都不给 ⇒ 拒绝")
    void neitherCommandNorWs_rejected() throws Exception {
        var r = tool.execute(call("{\"description\":\"d\"}"));
        assertThat(String.valueOf(r.data())).contains("Exactly one of command or ws");
    }

    @Test
    @DisplayName("description 必填")
    void descriptionRequired() throws Exception {
        var r = tool.execute(call("{\"command\":\"echo 1\"}"));
        assertThat(String.valueOf(r.data())).contains("description is required");
    }

    @Test
    @DisplayName("command ⇒ 走 startCommand，返回 taskId")
    void command_startsCommandMode() throws Exception {
        when(runner.startCommand(anyString(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn("b1234567");

        var r = tool.execute(call("{\"command\":\"tail -f app.log\",\"description\":\"看日志\"}"));

        assertThat(String.valueOf(r.data()))
            .contains("Monitor started (task b1234567, persistent — runs until TaskStop or session end)");
        assertThat(String.valueOf(r.data())).contains("Keep working — do not poll or sleep.");
        verify(runner).startCommand(eq("tail -f app.log"), eq("看日志"), eq(0L), eq("call-1"), eq(null), eq(null));
    }

    @Test
    @DisplayName("ws ⇒ 走 startWebSocket，带 allowed_domains")
    void ws_startsWsMode() throws Exception {
        when(runner.startWebSocket(anyString(), any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn("b7654321");

        var r = tool.execute(call(
            "{\"ws\":\"wss://example.com/feed\",\"description\":\"看连接\","
                + "\"allowed_domains\":[\"example.com\"]}"));

        assertThat(String.valueOf(r.data())).contains("b7654321");
        verify(runner).startWebSocket(eq("wss://example.com/feed"),
            eq(java.util.List.of("example.com")), eq("看连接"), eq(0L), eq("call-1"), eq(null), eq(null));
    }

    @Test
    @DisplayName("timeout_ms 负数 ⇒ 拒绝")
    void negativeTimeout_rejected() throws Exception {
        var r = tool.execute(call("{\"command\":\"echo 1\",\"description\":\"d\",\"timeout_ms\":-1}"));
        assertThat(String.valueOf(r.data())).contains("timeout_ms must be >= 0");
    }

    @Test
    @DisplayName("门控：MONITOR_TOOL 关 ⇒ isEnabled=false")
    void isEnabled_followsFeatureFlag() {
        assertThat(tool.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("门控：配置默认开启（forceEnabled=true）⇒ isEnabled=true")
    void isEnabled_forcedByConfig() {
        MonitorTool forced = new MonitorTool(FeatureFlags.ALL_DISABLED, null, runner, true);
        assertThat(forced.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("权限：command 模式委托 Bash 权限链")
    void permissions_commandDelegatesToBash() throws Exception {
        BashTool bash = mock(BashTool.class);
        var allow = new com.nexusai.application.agent.permission.PermissionResult.Allow(
            input("{\"command\":\"rm -rf /\"}"),
            new com.nexusai.application.agent.permission.PermissionDecisionReason.Other("bash"),
            null, false, null, java.util.List.of());
        when(bash.checkPermissions(any(), any())).thenReturn(allow);
        tool.setBashTool(bash);

        var r = tool.checkPermissions(input("{\"command\":\"rm -rf /\",\"description\":\"d\"}"), null);

        assertThat(r).isSameAs(allow);
        verify(bash).checkPermissions(any(), any());
    }

    @Test
    @DisplayName("权限：ws 模式不触发 Bash 链")
    void permissions_wsDoesNotUseBash() throws Exception {
        BashTool bash = mock(BashTool.class);
        tool.setBashTool(bash);

        var r = tool.checkPermissions(input("{\"ws\":\"wss://example.com\",\"description\":\"d\"}"), null);

        assertThat(r).isInstanceOf(com.nexusai.application.agent.permission.PermissionResult.Allow.class);
        verify(bash, org.mockito.Mockito.never()).checkPermissions(any(), any());
    }
}

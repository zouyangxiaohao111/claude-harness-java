package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.config.McpProperties;
import com.nexusai.application.agent.security.ChannelPermission;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [impl-I-3 T6] channel permission relay 过滤契约测试 · 对齐 CC channelPermissions.ts:177-194
 * （filterPermissionRelayClients 4 判定）+ isChannelPermissionRelayEnabled（:36-38）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 通道权限中继不能把任意 server 当权限表面 —— 必须
 * connected + allowlist + 双 capability（channelPermissions.ts L174-176 注释「a relay-only
 * channel never becomes a permission surface by accident」）。startChannelRace 无合格 channel
 * server / relay 门控 false 时不得触发 STOMP channel sendRequest（本地/前端兜底）。
 */
class ChannelRelayFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    private static final class DescribedTool implements Tool {
        private final String name;
        DescribedTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public String description(JsonNode input) { return name; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) { return ToolResult.success(call.id(), "stub"); }
    }

    private static ToolUseContext newCtx() {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    private static PermissionPromptDetails details() {
        return new PermissionPromptDetails("desc", List.of(), null, null, false);
    }

    /** 双 capability 的 MCP channel server 视图（R2-1 experimental 扩展）。 */
    private static JsonRpcMcpClient.Capabilities dualCapabilities() {
        return new JsonRpcMcpClient.Capabilities(true, true, false, false,
            false, false, false,
            Map.of("claude/channel", Map.of(), "claude/channel/permission", Map.of()));
    }

    private static McpToolPool mockPool(Set<String> servers,
                                        Map<String, JsonRpcMcpClient.Capabilities> caps) {
        McpToolPool pool = mock(McpToolPool.class);
        when(pool.activeServers()).thenReturn(servers);
        caps.forEach((name, c) -> when(pool.getServerCapabilities(name)).thenReturn(Optional.of(c)));
        return pool;
    }

    // ─────────────────── ③ filterPermissionRelayClients 4 判定逐条裁剪 ───────────────────

    @Test
    @DisplayName("③ filterPermissionRelayClients 4 判定：缺一即滤除（connected/allowlist/双 capability）")
    void filterPermissionRelayClients_trimsEachCondition() {
        ChannelPermission perm = new ChannelPermission(() -> true);
        record Client(String type, String name, Map<String, Object> capabilities) {}

        Map<String, Object> exp = Map.of("claude/channel", Map.of(), "claude/channel/permission", Map.of());
        Map<String, Object> capsMap = Map.of("experimental", exp);

        List<Client> clients = List.of(
            new Client("disconnected", "svr-ok", capsMap),          // type≠connected → 滤除
            new Client("connected", "svr-not-allowlisted", capsMap), // 不在 allowlist → 滤除
            new Client("connected", "svr-ok", Map.of("experimental", Map.of("claude/channel", Map.of()))), // 缺 permission → 滤除
            new Client("connected", "svr-ok", Map.of("experimental", Map.of("claude/channel/permission", Map.of()))), // 缺 channel → 滤除
            new Client("connected", "svr-ok", capsMap)               // 全过 → 保留
        );

        List<Client> qualified = perm.filterPermissionRelayClients(clients,
            Client::name, Client::type, Client::capabilities, name -> "svr-ok".equals(name));
        assertThat(qualified).as("4 判定 ALL required，仅全过者保留")
            .extracting(Client::name)
            .containsExactly("svr-ok");
    }

    // ─────────────────── ① relay 门控 false → 跳过整个 channel relay ───────────────────

    @Test
    @DisplayName("① isChannelPermissionRelayEnabled=false → 不触发 channel sendRequest（竞速跳过）")
    void relayGateOff_skipsChannelRace() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 1_000);
        prompter.wireRacersForTesting(null, new com.nexusai.application.agent.permission.StompChannelPermissionCallbacks(channelWs));
        // relay 门控 false（McpProperties.channelsPermissionRelayEnabled=false）
        prompter.setMcpProperties(new McpProperties(false, true, false, null, null, null, null, null, null, null, null));

        Thread t = new Thread(() -> prompter.prompt(
            new DescribedTool("Bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-t6-off", details()));
        t.start();
        t.join(300);

        // relay 门控 false → 跳过整个 channel relay 竞速（CC useManageMCPConnections.ts:188「One gate, full disable」）
        verify(channelWs, never()).convertAndSend(anyString(), any(Object.class));
    }

    // ─────────────────── ① 无合格 server（capability 缺失 / 不在 allowlist）→ 跳过 ───────────────────

    @Test
    @DisplayName("① 无合格 channel server（双 capability 缺失）→ 不触发 channel sendRequest")
    void noQualifiedServer_capabilityMissing_skips() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 1_000);
        prompter.wireRacersForTesting(null, new StompChannelPermissionCallbacks(channelWs));
        // relay 门控开，但 server 只声明 claude/channel（缺 claude/channel/permission）→ 不合格
        prompter.setMcpProperties(new McpProperties(false, true, true, null, null, null, null, null, null, null, null));
        prompter.setChannelRelayServerSource(mockPool(Set.of("plugin:slack:1"), Map.of(
            "plugin:slack:1", new JsonRpcMcpClient.Capabilities(true, true, false, false,
                false, false, false, Map.of("claude/channel", Map.of())))));
        prompter.setChannelRelayAllowlistChecker(name -> true);

        Thread t = new Thread(() -> prompter.prompt(
            new DescribedTool("Bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-t6-nocap", details()));
        t.start();
        t.join(300);

        // 双 capability 缺一 → filterPermissionRelayClients 空 → 不触发 channel sendRequest
        verify(channelWs, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("① 有 capability 但不在 allowlist → 不触发 channel sendRequest（allowlist 4 判定之一）")
    void noQualifiedServer_notInAllowlist_skips() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 1_000);
        prompter.wireRacersForTesting(null, new StompChannelPermissionCallbacks(channelWs));
        prompter.setMcpProperties(new McpProperties(false, true, true, null, null, null, null, null, null, null, null));
        prompter.setChannelRelayServerSource(mockPool(Set.of("plugin:slack:1"), Map.of("plugin:slack:1", dualCapabilities())));
        prompter.setChannelRelayAllowlistChecker(name -> false); // 不在白名单

        Thread t = new Thread(() -> prompter.prompt(
            new DescribedTool("Bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-t6-noal", details()));
        t.start();
        t.join(300);

        // 不在 allowlist → filterPermissionRelayClients 空 → 不触发 channel sendRequest
        verify(channelWs, never()).convertAndSend(anyString(), any(Object.class));
    }

    // ─────────────────── ② 有合格 server → sendRequest 触发 ───────────────────

    @Test
    @DisplayName("② 有合格 channel server（双 capability + allowlist）→ channel sendRequest 触发")
    void qualifiedServer_triggersChannelRace() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 1_000);
        prompter.wireRacersForTesting(null, new StompChannelPermissionCallbacks(channelWs));
        prompter.setMcpProperties(new McpProperties(false, true, true, null, null, null, null, null, null, null, null));
        prompter.setChannelRelayServerSource(mockPool(Set.of("plugin:slack:1"), Map.of("plugin:slack:1", dualCapabilities())));
        prompter.setChannelRelayAllowlistChecker(name -> true);

        Thread t = new Thread(() -> prompter.prompt(
            new DescribedTool("Bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-t6-ok", details()));
        t.start();

        // channel relay sendRequest → STOMP 推送 channel topic
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(channelWs, timeout(3000)).convertAndSend(anyString(), captor.capture());
        assertThat(captor.getValue())
            .as("合格 channel server → 出站 channel permission_request（CC interactiveHandler.ts:334-354）")
            .isInstanceOf(ChannelPermissionRequestEvent.class);
        t.join(2_000);
    }
}

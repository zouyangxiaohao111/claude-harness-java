package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionModeService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P1] 渠道权限中继的**统一资格入口**。
 *
 * <p><b>WHY</b>：IM 与 MCP 是两条并列来源，判定口径不同（IM=会话绑定，MCP=白名单+双能力）。
 * 旧的并列 {@code if} 短路会退化成「两条必须同时满足」或「IM 也要过 MCP 白名单」——
 * 真实条件下只绑 IM 就永远推不出去。
 *
 * <p>本文件锁死：
 * <ol>
 *   <li>IM 来源：只凭会话绑定即合格（MCP 池里<b>没有</b>合格 server、白名单 false 也不影响）；</li>
 *   <li>MCP 来源：凭白名单 + 双 capability 合格（IM 空绑定也不影响）；</li>
 *   <li>两条来源走**同一个函数**、返回**同构结构**（来源类型 + 能力声明 + 合格 + 原因码）；</li>
 *   <li>两者都不满足 ⇒ 不合格，且 startChannelRace 不推送。</li>
 * </ol>
 *
 * <p>⚠️ 变异判据：入口退回「先查 MCP、不合格直接 return，再看 IM」的并列短路 ⇒ 用例 1 变红。
 */
@DisplayName("[P1] 统一渠道资格入口")
class ChannelRelayEligibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "a0000000000bb001";
    private static final String SESSION = "sess-p1-eligibility";

    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static final class StubChannelCallbacks implements ChannelPermissionCallbacks {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final Map<String, Consumer<ChannelResponse>> pending = new ConcurrentHashMap<>();
        @Override public String shortRequestId(String toolUseId) { return "abcde"; }
        @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
            pending.put(requestId.toLowerCase(), handler);
            return () -> pending.remove(requestId.toLowerCase());
        }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                          String description, JsonNode displayInput) {
            sent.add(toolName);
        }
        @Override public boolean resolve(String requestId, String behavior, String fromServer) {
            Consumer<ChannelResponse> cb = pending.remove(requestId.toLowerCase());
            if (cb == null) return false;
            cb.accept(new ChannelResponse(behavior, fromServer));
            return true;
        }
    }

    private static ToolUseContext ctx() {
        com.nexusai.common.SessionProjectRoot.setForSession(SESSION,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null),
            PermissionMode.DEFAULT);
    }

    private static ImChatEntry chat(String id) {
        return new ImChatEntry(id, "i1", "feishu", "p1", "p1", "p2p", 2,
            null, null, SESSION, "manual", null, null, null, null);
    }

    /** 组装 prompter：settings / 会话绑定 / IM 绑定 / MCP 池 / 白名单 全部可控。 */
    private static WebSocketPermissionPrompter prompter(
            boolean settingsBothGates, boolean sessionBound,
            ChannelPermissionImNotifier im, McpToolPool pool, boolean allowlisted) {
        WebSocketPermissionPrompter p = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 100);
        SettingsService settings = mock(SettingsService.class);
        when(settings.readDbChannelsEnabled()).thenReturn(settingsBothGates);
        when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(settingsBothGates);
        ReflectionTestUtils.setField(p, "settingsService", settings);
        SessionModeService sessionMode = mock(SessionModeService.class);
        when(sessionMode.hasAnyChannel(anyString())).thenReturn(sessionBound);
        ReflectionTestUtils.setField(p, "sessionModeService", sessionMode);
        if (im != null) ReflectionTestUtils.setField(p, "imNotifier", im);
        if (pool != null) ReflectionTestUtils.setField(p, "channelRelayServerSource", pool);
        ReflectionTestUtils.setField(p, "relayAllowlistChecker", (java.util.function.Function<String, Boolean>) n -> allowlisted);
        return p;
    }

    /** MCP 池：一个 server，能力由 experimental 决定。 */
    private static McpToolPool poolWith(boolean qualified, boolean allowlistedIgnored) {
        McpToolPool pool = mock(McpToolPool.class);
        if (!qualified) {
            when(pool.activeServers()).thenReturn(Set.of());
            return pool;
        }
        when(pool.activeServers()).thenReturn(Set.of("market-channel"));
        JsonRpcMcpClient.Capabilities caps = new JsonRpcMcpClient.Capabilities(
            true, true, false, false, false, false, false,
            Map.of("claude/channel", Map.of(), "claude/channel/permission", Map.of()));
        when(pool.getServerCapabilities("market-channel")).thenReturn(Optional.of(caps));
        return pool;
    }

    private static ChannelPermissionImNotifier imWith(boolean bound) {
        ChannelPermissionImNotifier im = mock(ChannelPermissionImNotifier.class);
        when(im.boundChats(anyString())).thenReturn(bound ? List.of(chat("chat-1")) : List.of());
        return im;
    }

    @Test
    @DisplayName("IM 来源：只凭会话绑定即合格（MCP 池无合格 server + 白名单 false 也不影响）")
    void imSourceQualifiesWithoutMcpWhitelist() {
        WebSocketPermissionPrompter p = prompter(true, true, imWith(true), poolWith(false, false), false);

        ChannelRelayEligibility r = p.evaluateChannelRelayEligibility(ctx());

        assertThat(r.qualified()).isTrue();
        assertThat(r.source()).isEqualTo(ChannelRelayEligibility.Source.IM);
        assertThat(r.capabilities().permissionRelay()).isTrue();
        assertThat(r.reason()).isEqualTo(ChannelRelayEligibility.Reason.QUALIFIED_IM_BOUND);
        assertThat(r.detail()).contains("chats=1");
    }

    @Test
    @DisplayName("MCP 来源：凭白名单 + 双 capability 合格（IM 空绑定也不影响）")
    void mcpSourceQualifiesThroughWhitelist() {
        WebSocketPermissionPrompter p = prompter(true, true, imWith(false), poolWith(true, true), true);

        ChannelRelayEligibility r = p.evaluateChannelRelayEligibility(ctx());

        assertThat(r.qualified()).isTrue();
        assertThat(r.source()).isEqualTo(ChannelRelayEligibility.Source.MCP);
        assertThat(r.capabilities().permissionRelay()).isTrue();
        assertThat(r.reason()).isEqualTo(ChannelRelayEligibility.Reason.QUALIFIED_MCP_SERVER);
    }

    @Test
    @DisplayName("两种来源走同一函数、返回同构（同一类型 + 同一组字段）")
    void bothSourcesReturnIsomorphicShape() {
        ChannelRelayEligibility im = prompter(true, true, imWith(true), poolWith(false, false), false)
            .evaluateChannelRelayEligibility(ctx());
        ChannelRelayEligibility mcp = prompter(true, true, imWith(false), poolWith(true, true), true)
            .evaluateChannelRelayEligibility(ctx());

        assertThat(im.getClass()).isEqualTo(mcp.getClass());
        // 同构 = 三种来源都填同样的四个语义字段（来源/能力/合格/原因），只是取值不同
        assertThat(List.of(im.source(), mcp.source()))
            .containsExactly(ChannelRelayEligibility.Source.IM, ChannelRelayEligibility.Source.MCP);
        assertThat(im.capabilities()).isNotNull();
        assertThat(mcp.capabilities()).isNotNull();
        assertThat(im.reason()).isNotNull();
        assertThat(mcp.reason()).isNotNull();
    }

    @Test
    @DisplayName("两者都不满足 ⇒ 不合格 + 原因码 NOT_NO_SOURCE（来源 NONE / 能力 false）")
    void bothUnsatisfiedIsRejected() {
        WebSocketPermissionPrompter p = prompter(true, true, imWith(false), poolWith(false, false), false);

        ChannelRelayEligibility r = p.evaluateChannelRelayEligibility(ctx());

        assertThat(r.qualified()).isFalse();
        assertThat(r.source()).isEqualTo(ChannelRelayEligibility.Source.NONE);
        assertThat(r.capabilities().permissionRelay()).isFalse();
        assertThat(r.reason()).isEqualTo(ChannelRelayEligibility.Reason.NOT_NO_SOURCE);
    }

    @Test
    @DisplayName("settings 两门未全开 / 会话未绑定渠道 ⇒ 各自原因码")
    void gateFailuresHaveDistinctReasons() {
        assertThat(prompter(false, true, imWith(true), poolWith(true, true), true)
            .evaluateChannelRelayEligibility(ctx()).reason())
            .isEqualTo(ChannelRelayEligibility.Reason.NOT_SETTINGS_GATE);
        assertThat(prompter(true, false, imWith(true), poolWith(true, true), true)
            .evaluateChannelRelayEligibility(ctx()).reason())
            .isEqualTo(ChannelRelayEligibility.Reason.NOT_SESSION_UNBOUND);
    }

    @Test
    @DisplayName("两者都不满足 ⇒ startChannelRace 不推送（桌面路径照旧等待）")
    void unsatisfiedSourceDoesNotPush() throws Exception {
        StubChannelCallbacks cb = new StubChannelCallbacks();
        WebSocketPermissionPrompter p = prompter(true, true, imWith(false), poolWith(false, false), false);
        ReflectionTestUtils.setField(p, "channelCallbacks", cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-p1-nopush";
        Thread t = new Thread(() -> out.set(p.prompt(new StubTool(), JSON.createObjectNode(),
            new PermissionDecisionReason.Other("test"), ctx(), requestId,
            new PermissionPromptDetails("运行命令", List.of(), null))));
        t.setDaemon(true);
        t.start();

        Thread.sleep(600);
        assertThat(cb.sent).as("两条来源都不满足时不得出站").isEmpty();
        assertThat(out.get()).isNull();

        p.onResponse(requestId, "allow");
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
    }
}

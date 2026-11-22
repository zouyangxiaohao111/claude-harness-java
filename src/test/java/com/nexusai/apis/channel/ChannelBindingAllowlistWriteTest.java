package com.nexusai.apis.channel;

import com.nexusai.application.agent.mcp.ChannelAllowlist;
import com.nexusai.application.agent.mcp.ChannelSessionAllowlist;
import com.nexusai.domain.mcp_channel_binding.ChannelBindingService;
import com.nexusai.model.mcp_channel_binding.ChannelBindingEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P3] 会话级 allowlist 的**真实生产写入**：渠道绑定/解绑 ⇒ ChannelSessionAllowlist
 * 按会话写入/清理，且 A/B 两会话互不串权。
 *
 * <p><b>WHY</b>：setForSession 此前只有测试调用（生产零写入点）⇒ 会话白名单恒空。本测试锁：
 * <ol>
 *   <li>绑定渠道 ⇒ 该会话白名单出现对应 server entry（dev=true = 绑定即显式授权）；</li>
 *   <li>解绑（含最后一个）⇒ 白名单重算/清空；</li>
 *   <li>force 抢占 ⇒ 旧会话被重算（不会残留别人的渠道）、新会话拿到；</li>
 *   <li>冲突未 force ⇒ 两边白名单都不变（拒绝也必须是原子无副作用）。</li>
 * </ol>
 *
 * <p>⚠️ 变异判据：删掉 controller 的 syncSessionAllowlist(...) 调用 ⇒ 用例全红。
 */
@DisplayName("[P3] 会话级 allowlist 生产写入")
class ChannelBindingAllowlistWriteTest {

    private final Map<String, ChannelBindingEntry> byChannel = new ConcurrentHashMap<>();
    private ChannelSessionAllowlist allowlist;
    private ChannelBindingController controller;

    @BeforeEach
    void setUp() {
        ChannelBindingService svc = mock(ChannelBindingService.class);
        when(svc.listBySession(anyString())).thenAnswer(inv -> byChannel.values().stream()
            .filter(e -> e.sessionId().equals(inv.getArgument(0)))
            .toList());
        when(svc.findByChannelId(anyString())).thenAnswer(inv ->
            Optional.ofNullable(byChannel.get(inv.getArgument(0))));
        when(svc.bind(anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(inv -> {
            String channelId = inv.getArgument(0);
            String serverName = inv.getArgument(1);
            String sessionId = inv.getArgument(2);
            boolean force = inv.getArgument(3);
            ChannelBindingEntry existing = byChannel.get(channelId);
            if (existing != null && !existing.sessionId().equals(sessionId) && !force) {
                return new ChannelBindingService.BindResult(null, existing);
            }
            ChannelBindingEntry e = new ChannelBindingEntry(
                "chb-" + channelId, channelId, serverName, sessionId, "2026-11-22T00:00:00Z");
            byChannel.put(channelId, e);
            return new ChannelBindingService.BindResult(e, null);
        });
        when(svc.unbind(anyString(), anyString())).thenAnswer(inv -> {
            String channelId = inv.getArgument(0);
            String sessionId = inv.getArgument(1);
            ChannelBindingEntry e = byChannel.get(channelId);
            if (e == null) return false;
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(e.sessionId())) return false;
            byChannel.remove(channelId);
            return true;
        });

        allowlist = new ChannelSessionAllowlist();
        controller = new ChannelBindingController();
        ReflectionTestUtils.setField(controller, "channelBindingService", svc);
        ReflectionTestUtils.setField(controller, "channelSessionAllowlist", allowlist);
    }

    private ResponseEntity<Map<String, Object>> bind(String channelId, String serverName,
                                                     String sessionId, boolean force) {
        Map<String, Object> body = Map.of(
            "channelId", channelId, "serverName", serverName, "sessionId", sessionId, "force", force);
        return controller.bind(body);
    }

    @Test
    @DisplayName("绑定渠道 ⇒ 该会话白名单写入（server-kind + dev=true），其它会话不受影响")
    void bindWritesSessionAllowlistAndIsolatesSessions() {
        bind("ch-1", "srv-a", "sess-A", false);
        bind("ch-2", "srv-b", "sess-A", false);
        bind("ch-3", "srv-c", "sess-B", false);

        assertThat(allowlist.getForSession("sess-A"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactlyInAnyOrder("srv-a", "srv-b");
        assertThat(allowlist.getForSession("sess-A"))
            .allMatch(e -> "server".equals(e.kind()) && Boolean.TRUE.equals(e.dev()));
        assertThat(allowlist.getForSession("sess-B"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("srv-c");
        // 未绑定任何渠道的会话仍 fail-closed 空表
        assertThat(allowlist.getForSession("sess-C")).isEmpty();
    }

    @Test
    @DisplayName("解绑 ⇒ 该会话白名单重算；解绑最后一个 ⇒ 清空（fail-closed）")
    void unbindRecomputesAndClears() {
        bind("ch-1", "srv-a", "sess-A", false);
        bind("ch-2", "srv-b", "sess-A", false);
        bind("ch-3", "srv-c", "sess-B", false);

        controller.unbind("ch-1", Map.of("sessionId", "sess-A"));
        assertThat(allowlist.getForSession("sess-A"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("srv-b");
        assertThat(allowlist.getForSession("sess-B"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("srv-c");

        controller.unbind("ch-2", Map.of("sessionId", "sess-A"));
        assertThat(allowlist.getForSession("sess-A"))
            .as("最后一个渠道解绑 ⇒ 会话白名单必须清空（不留陈旧授权）")
            .isEmpty();
    }

    @Test
    @DisplayName("force 抢占 ⇒ 旧会话白名单被重算（不残留），新会话拿到")
    void forceStealRecomputesPreviousOwner() {
        bind("ch-1", "srv-a", "sess-A", false);
        assertThat(allowlist.getForSession("sess-A")).hasSize(1);

        ResponseEntity<Map<String, Object>> resp = bind("ch-1", "srv-a", "sess-B", true);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(allowlist.getForSession("sess-A"))
            .as("抢占后旧会话不能残留已经不属于它的渠道")
            .isEmpty();
        assertThat(allowlist.getForSession("sess-B"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("srv-a");
    }

    @Test
    @DisplayName("冲突且未 force ⇒ 409，双方白名单都不变（拒绝无副作用）")
    void conflictWithoutForceLeavesAllowlistsUntouched() {
        bind("ch-1", "srv-a", "sess-A", false);

        ResponseEntity<Map<String, Object>> resp = bind("ch-1", "srv-a", "sess-B", false);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(allowlist.getForSession("sess-A"))
            .extracting(ChannelAllowlist.ChannelEntry::name)
            .containsExactly("srv-a");
        assertThat(allowlist.getForSession("sess-B"))
            .as("冲突拒绝不得给请求方写入白名单")
            .isEmpty();
    }
}

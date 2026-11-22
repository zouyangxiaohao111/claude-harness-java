package com.nexusai.apis.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.ChannelAdapterAuth;
import com.nexusai.application.agent.permission.ChannelPermissionCallbacks;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import com.nexusai.eventbus.ws.ChannelPermissionResponseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P0.1] STOMP 渠道回执的身份校验（堵伪造 fromServer）。
 *
 * <p>攻击形态：任何能往 {@code /app/sessions/{sid}/permission-channel-response} 发消息的客户端，
 * 带上目标 requestId + {@code behavior:"allow"} + 随便写的 {@code fromServer}，就能替用户批准。
 * 防线 = 适配器共享口令（未配置口令时全拒）。本测试直接打控制器入口，断言
 * <b>{@code resolve} 根本没被调用</b>（pending 保留），而不是只看日志。
 */
@DisplayName("[P0.1] 渠道回执身份校验 · 伪造 allow 必须被丢弃")
class ChannelResponseIdentityTest {

    /** 记录 resolve 调用的最小回调。 */
    private static final class RecordingCallbacks implements ChannelPermissionCallbacks {
        final AtomicInteger resolveCalls = new AtomicInteger();
        @Override public String shortRequestId(String toolUseId) { return "abcde"; }
        @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
            return () -> { };
        }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                         String description, JsonNode displayInput) { }
        @Override public boolean resolve(String requestId, String behavior, String fromServer) {
            resolveCalls.incrementAndGet();
            return true;
        }
    }

    private static PermissionController controller(ChannelAdapterAuth auth, RecordingCallbacks cbs) {
        PermissionController c = new PermissionController();
        WebSocketPermissionPrompter prompter = mock(WebSocketPermissionPrompter.class);
        when(prompter.admitChannelResponse(anyString(), anyString()))
            .thenReturn(WebSocketPermissionPrompter.ResponseAdmission.ACCEPT);
        ReflectionTestUtils.setField(c, "prompter", prompter);
        ReflectionTestUtils.setField(c, "channelCallbacks", cbs);
        ReflectionTestUtils.setField(c, "channelAdapterAuth", auth);
        return c;
    }

    @Test
    @DisplayName("无口令 / 错口令 / 未配置口令 ⇒ resolve 不被调用（伪造被丢弃）")
    void forgedOrMissingTokenIsDropped() {
        RecordingCallbacks cbs = new RecordingCallbacks();
        PermissionController withSecret = controller(new ChannelAdapterAuth("s3cret"), cbs);

        withSecret.handleChannelPermissionResponse("sess-1",
            new ChannelPermissionResponseEvent("req-1", "allow", "plugin:telegram:tg", null));
        withSecret.handleChannelPermissionResponse("sess-1",
            new ChannelPermissionResponseEvent("req-2", "allow", "plugin:telegram:tg", "wrong"));
        assertThat(cbs.resolveCalls.get()).as("伪造/缺失口令不得触发 resolve").isZero();

        // 未配置口令的部署：即使带了一个自洽的 token 也拒绝（fail-closed）
        PermissionController unconfigured = controller(new ChannelAdapterAuth(""), new RecordingCallbacks());
        RecordingCallbacks cbs2 = new RecordingCallbacks();
        ReflectionTestUtils.setField(unconfigured, "channelCallbacks", cbs2);
        unconfigured.handleChannelPermissionResponse("sess-1",
            new ChannelPermissionResponseEvent("req-3", "allow", "plugin:telegram:tg", "anything"));
        assertThat(cbs2.resolveCalls.get()).as("未配置口令 ⇒ 一律拒绝").isZero();
    }

    @Test
    @DisplayName("正确口令 ⇒ 正常 resolve（不能把合法回执一起堵死）")
    void validTokenResolves() {
        RecordingCallbacks cbs = new RecordingCallbacks();
        PermissionController c = controller(new ChannelAdapterAuth("s3cret"), cbs);

        c.handleChannelPermissionResponse("sess-1",
            new ChannelPermissionResponseEvent("req-ok", "allow", "plugin:telegram:tg", "s3cret"));

        assertThat(cbs.resolveCalls.get()).isEqualTo(1);
    }
}

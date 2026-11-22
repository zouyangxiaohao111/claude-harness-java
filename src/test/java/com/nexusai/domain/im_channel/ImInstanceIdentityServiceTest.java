package com.nexusai.domain.im_channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 机器人身份探测（接入页 / 渠道选择器）。
 *
 * <p>WHY：聊天列表里只有 {@code oc_…} / 群 id，用户看不出是哪个机器人。平台其实能直接问出来
 * （飞书 bot/v3/info；企微 identity whoami），这条链路不能靠"发一条消息去猜"。
 */
@DisplayName("机器人身份探测")
class ImInstanceIdentityServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ImInstanceEntry inst(String platform) {
        return new ImInstanceEntry("imc-1", platform, "测试渠道", "connected", null, null,
                null, "t", "t");
    }

    @Test
    @DisplayName("企微 ⇒ Bot ID + whoami 授权人（默认会话锚点）")
    void wecomResolvesAuthorizer() throws Exception {
        ImChannelService channelService = mock(ImChannelService.class);
        WecomCliTokenService tokenService = mock(WecomCliTokenService.class);
        WecomCliClient client = mock(WecomCliClient.class);
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("wecom")));
        when(channelService.credentialOf("imc-1"))
                .thenReturn(Optional.of("{\"botId\":\"bot-123456789\",\"botSecret\":\"s\"}"));
        when(tokenService.tokenFor("imc-1", "bot-123456789", "s")).thenReturn("tok");
        when(client.call(eq("tok"), eq("/cli/identity/whoami"), any()))
                .thenReturn(new WecomCliClient.Result(0, "ok",
                        JSON.readTree("{\"authorizer_id\":\"zhangsan\"}"), "{}"));

        ImInstanceIdentityService svc = new ImInstanceIdentityService();
        ReflectionTestUtils.setField(svc, "channelService", channelService);
        ReflectionTestUtils.setField(svc, "wecomTokenService", tokenService);
        ReflectionTestUtils.setField(svc, "wecomCliClient", client);

        var r = svc.resolve("imc-1");

        assertThat(r.ok()).isTrue();
        assertThat(r.platform()).isEqualTo("wecom");
        assertThat(r.botId()).isEqualTo("bot-123456789");
        assertThat(r.authorizerId()).isEqualTo("zhangsan");
    }

    @Test
    @DisplayName("企微 whoami 未返回授权人 ⇒ 仍是凭证有效（不误判为失败）")
    void wecomWhoamiWithoutAuthorizerStillOk() throws Exception {
        ImChannelService channelService = mock(ImChannelService.class);
        WecomCliTokenService tokenService = mock(WecomCliTokenService.class);
        WecomCliClient client = mock(WecomCliClient.class);
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("wecom")));
        when(channelService.credentialOf("imc-1"))
                .thenReturn(Optional.of("{\"botId\":\"bot-1\",\"botSecret\":\"s\"}"));
        when(tokenService.tokenFor("imc-1", "bot-1", "s")).thenReturn("tok");
        when(client.call(any(), any(), any()))
                .thenReturn(new WecomCliClient.Result(-1, "unknown path", null, "{}"));

        ImInstanceIdentityService svc = new ImInstanceIdentityService();
        ReflectionTestUtils.setField(svc, "channelService", channelService);
        ReflectionTestUtils.setField(svc, "wecomTokenService", tokenService);
        ReflectionTestUtils.setField(svc, "wecomCliClient", client);

        var r = svc.resolve("imc-1");

        assertThat(r.ok()).as("换票成功 = 凭证有效；whoami 失败只是身份读不到").isTrue();
        assertThat(r.authorizerId()).isNull();
        assertThat(r.message()).contains("授权人未取到");
    }

    @Test
    @DisplayName("飞书 ⇒ 机器人名 + 可见群数（复用凭证探测）")
    void feishuUsesProbe() {
        ImChannelService channelService = mock(ImChannelService.class);
        ImCredentialProbeService probe = mock(ImCredentialProbeService.class);
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("feishu")));
        when(channelService.credentialOf("imc-1"))
                .thenReturn(Optional.of("{\"appId\":\"cli_x\",\"appSecret\":\"sec\"}"));
        when(probe.probeFeishu("cli_x", "sec"))
                .thenReturn(new ImCredentialProbeService.Result(true, "0", "凭证有效", "NexusAI", "3"));

        ImInstanceIdentityService svc = new ImInstanceIdentityService();
        ReflectionTestUtils.setField(svc, "channelService", channelService);
        ReflectionTestUtils.setField(svc, "feishuProbe", probe);

        var r = svc.resolve("imc-1");

        assertThat(r.ok()).isTrue();
        assertThat(r.botName()).isEqualTo("NexusAI");
        assertThat(r.chatVisible()).isEqualTo("3");
    }

    @Test
    @DisplayName("缓存：10 分钟内第二次调用不再打平台")
    void secondCallUsesCache() throws Exception {
        ImChannelService channelService = mock(ImChannelService.class);
        WecomCliTokenService tokenService = mock(WecomCliTokenService.class);
        WecomCliClient client = mock(WecomCliClient.class);
        when(channelService.findById("imc-1")).thenReturn(Optional.of(inst("wecom")));
        when(channelService.credentialOf("imc-1"))
                .thenReturn(Optional.of("{\"botId\":\"bot-1\",\"botSecret\":\"s\"}"));
        when(tokenService.tokenFor("imc-1", "bot-1", "s")).thenReturn("tok");
        when(client.call(any(), any(), any()))
                .thenReturn(new WecomCliClient.Result(0, "ok",
                        JSON.readTree("{\"userid\":\"lisi\"}"), "{}"));

        ImInstanceIdentityService svc = new ImInstanceIdentityService();
        ReflectionTestUtils.setField(svc, "channelService", channelService);
        ReflectionTestUtils.setField(svc, "wecomTokenService", tokenService);
        ReflectionTestUtils.setField(svc, "wecomCliClient", client);

        svc.resolve("imc-1");
        svc.resolve("imc-1");

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).call(any(), any(), any());
    }
}

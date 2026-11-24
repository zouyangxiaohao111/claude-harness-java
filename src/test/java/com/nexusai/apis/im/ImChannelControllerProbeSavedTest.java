package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImCredentialProbeService;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [21001] 已保存实例的凭证自检（{@code POST /im/instances/{id}/probe}）。
 *
 * <p><b>WHY（规则九）</b>：一键创建成功 ≠ 飞书肯换票。豆包账号创建的自建应用会回
 * {@code 21001 Access denied due to platform policy}；创建后立刻把这个原因返回给前端，
 * 用户就不会点「同步」（0 个会话）和「授权」（飞书页显示「无需授权任何权限」）后误判。
 * 同时锁死响应**不含凭证/密钥**（该端点读的是已保存凭证）。
 */
@DisplayName("[21001] 实例凭证自检")
class ImChannelControllerProbeSavedTest {

    private ImChannelController controller;
    private ImChannelService channelService;
    private ImCredentialProbeService probe;

    @BeforeEach
    void setUp() {
        controller = new ImChannelController();
        channelService = mock(ImChannelService.class);
        probe = mock(ImCredentialProbeService.class);
        ReflectionTestUtils.setField(controller, "channelService", channelService);
        ReflectionTestUtils.setField(controller, "probeService", probe);
    }

    private static ImInstanceEntry instance(String platform) {
        return new ImInstanceEntry("imc-1", platform, "飞书", "connected", null, null, null, "t", "t");
    }

    private void givenCredential(String json) {
        when(channelService.credentialOf("imc-1")).thenReturn(Optional.of(json));
    }

    @Test
    @DisplayName("21001 → ok=false + code + 原始 msg（不把平台策略说成授权问题）")
    void platformPolicyDenied() {
        when(channelService.findById("imc-1")).thenReturn(Optional.of(instance("feishu")));
        givenCredential("{\"appId\":\"cli_x\",\"appSecret\":\"secret-value\"}");
        when(probe.probeFeishu("cli_x", "secret-value")).thenReturn(
            new ImCredentialProbeService.Result(false, "21001",
                "飞书换取 tenant_access_token 失败：code=21001 msg=Access denied due to platform policy.",
                null, null));

        Map<String, Object> out = controller.probeSaved("imc-1");

        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("code")).isEqualTo("21001");
        assertThat(String.valueOf(out.get("message"))).contains("21001");
        assertThat(out.toString()).as("响应不得含密钥").doesNotContain("secret-value");
    }

    @Test
    @DisplayName("换票成功 → ok=true + 机器人名（加分信息，不藏起来）")
    void probeOk() {
        when(channelService.findById("imc-1")).thenReturn(Optional.of(instance("feishu")));
        givenCredential("{\"appId\":\"cli_x\",\"appSecret\":\"s\"}");
        when(probe.probeFeishu(anyString(), anyString())).thenReturn(
            new ImCredentialProbeService.Result(true, "0", "凭证有效 · 机器人：测试bot", "测试bot", "3"));

        Map<String, Object> out = controller.probeSaved("imc-1");

        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("botName")).isEqualTo("测试bot");
        assertThat(out.get("chatVisible")).isEqualTo("3");
    }

    @Test
    @DisplayName("实例不存在 → NOT_FOUND，不触达换票")
    void notFound() {
        when(channelService.findById("imc-1")).thenReturn(Optional.empty());

        Map<String, Object> out = controller.probeSaved("imc-1");

        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("code")).isEqualTo("NOT_FOUND");
        verify(probe, never()).probeFeishu(any(), any());
    }

    @Test
    @DisplayName("非飞书实例 → UNSUPPORTED")
    void unsupportedPlatform() {
        when(channelService.findById("imc-1")).thenReturn(Optional.of(instance("wechat")));

        Map<String, Object> out = controller.probeSaved("imc-1");

        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("code")).isEqualTo("UNSUPPORTED");
        verify(probe, never()).probeFeishu(any(), any());
    }

    @Test
    @DisplayName("实例无凭证 → INVALID_CREDENTIAL，不触达换票")
    void missingCredential() {
        when(channelService.findById("imc-1")).thenReturn(Optional.of(instance("feishu")));
        when(channelService.credentialOf("imc-1")).thenReturn(Optional.empty());

        Map<String, Object> out = controller.probeSaved("imc-1");

        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("code")).isEqualTo("INVALID_CREDENTIAL");
        verify(probe, never()).probeFeishu(any(), any());
    }
}

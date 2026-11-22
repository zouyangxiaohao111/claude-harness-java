package com.nexusai.apis.settings;

import com.nexusai.domain.settings.SettingsService;
import com.nexusai.infra.llm.ProxyProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [保密 2026-11-22 B6] settings.http_proxy（LLM 出站代理）的口令段脱敏。
 *
 * <p>三态语义必须原样保住（见 SettingsService.readDbHttpProxy javadoc）：
 * null = 未配置（回落环境变量/直连）、"" = 显式直连、非空 = 该代理。
 * 脱敏只动 user:pass@ 的口令段；「试一下」用服务端真值（否则拿掩码去连必失败）。
 */
class SettingsHttpProxyRedactionTest {

    private static final String STORED = "http://user:pw-123456@127.0.0.1:7897";
    private static final String MASKED = "http://user:****@127.0.0.1:7897";

    private SettingsService settingsService;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        controller = new SettingsController();
        ReflectionTestUtils.setField(controller, "settingsService", settingsService);
        when(settingsService.readDbHttpProxy()).thenReturn(STORED);
    }

    @Test
    @DisplayName("GET：口令段掩码，主机端口可读")
    void get_masksPassword() {
        assertThat(controller.getHttpProxy().proxy()).isEqualTo(MASKED);
    }

    @Test
    @DisplayName("PUT：整串掩码回传 → 落库仍是真口令")
    void put_maskedEcho_keepsPassword() {
        controller.updateHttpProxy(new SettingsController.HttpProxyRequest(MASKED));
        verify(settingsService).writeDbHttpProxy(STORED);
    }

    @Test
    @DisplayName("PUT：只改主机端口、口令留掩码 → 换回真口令后落库")
    void put_maskedPasswordNewHost() {
        controller.updateHttpProxy(
            new SettingsController.HttpProxyRequest("http://user:****@10.0.0.2:8080"));
        verify(settingsService).writeDbHttpProxy("http://user:pw-123456@10.0.0.2:8080");
    }

    @Test
    @DisplayName("PUT：null / 空串 三态原样透传（不被掩码逻辑改写）")
    void put_tristatePreserved() {
        controller.updateHttpProxy(new SettingsController.HttpProxyRequest(null));
        verify(settingsService).writeDbHttpProxy(null);
        controller.updateHttpProxy(new SettingsController.HttpProxyRequest(""));
        verify(settingsService).writeDbHttpProxy("");
    }

    @Test
    @DisplayName("POST test：掩码 → 用服务端真口令去连通性测试")
    void test_usesServerSidePassword() {
        try (MockedStatic<ProxyProbe> probe = mockStatic(ProxyProbe.class)) {
            probe.when(() -> ProxyProbe.test(any()))
                .thenReturn(new ProxyProbe.TestResult(true, 5, null));

            controller.testHttpProxy(new SettingsController.HttpProxyRequest(
                "http://user:****@10.0.0.2:8080"));

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            probe.verify(() -> ProxyProbe.test(captor.capture()));
            assertThat(captor.getValue()).isEqualTo("http://user:pw-123456@10.0.0.2:8080");
        }
    }
}

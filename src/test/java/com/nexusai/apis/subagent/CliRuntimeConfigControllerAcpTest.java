package com.nexusai.apis.subagent;

import com.nexusai.application.agent.subagent.external.ExternalRuntimeEnvStore;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec;
import com.nexusai.domain.subagent.CliRuntimeConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * runtime-configs 三端点对 ACP 的语义：version/models/test 不再 400，未知 runtime 仍 400。
 *
 * <p>test 走真探测（spawn dsh → initialize → session/new）。为了让「探测成功」可复现，
 * 测试期给 ACP 配置注入一个 dummy key（dsh 启动期只检查 key 存在）；结束后清空静态快照。
 */
@DisplayName("[acp] CliRuntimeConfigController ACP 三端点")
class CliRuntimeConfigControllerAcpTest {

    private CliRuntimeConfigController controller;

    @BeforeEach
    void setUp() {
        ExternalRuntimeEnvStore.replace(Map.of("acp", new ExternalRuntimeEnvStore.Overlay(
                null, null, Map.of("DEEPSEEK_API_KEY", "sk-dummy-for-boot"),
                null, null, null)));
        controller = new CliRuntimeConfigController(mock(CliRuntimeConfigService.class));
    }

    @AfterEach
    void tearDown() {
        ExternalRuntimeEnvStore.replace(Map.of());
    }

    @Test
    @DisplayName("version(acp)：200，带 runtime / acpProtocolVersion，不伪造预设字段")
    void acpVersion() {
        Map<String, Object> resp = controller.version("acp");

        assertThat(resp.get("runtime")).isEqualTo("acp");
        assertThat(resp.get("acpProtocolVersion")).isEqualTo(1);
        assertThat(resp).containsKey("available");
        if (Boolean.TRUE.equals(resp.get("available"))) {
            assertThat((String) resp.get("resolvedPath")).isNotBlank();
        }
        assertThat(resp).doesNotContainKey("supportsModelList");
    }

    @Test
    @DisplayName("models(acp)：200 + supported=false + 空列表")
    void acpModels() {
        Map<String, Object> resp = controller.models("acp");

        assertThat(resp.get("runtime")).isEqualTo("acp");
        assertThat(resp.get("supported")).isEqualTo(false);
        assertThat((List<?>) resp.get("models")).isEmpty();
    }

    @Test
    @DisplayName("test(acp)：真探测 initialize + session/new ⇒ ok + acp_session")
    void acpTestRealProbe() {
        Assumptions.assumeTrue(ExternalRuntimeSpec.resolveExecutable("dsh") != null,
                "本机未安装 dsh，跳过真探测");

        Map<String, Object> resp = controller.test("acp");

        assertThat(resp.get("runtime")).isEqualTo("acp");
        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("probeLevel")).isEqualTo("acp_session");
        assertThat((Long) resp.get("elapsedMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("未知 runtime：version/models/test 仍 400")
    void unknownRuntimeStill400() {
        assertThatThrownBy(() -> controller.version("nope-runtime"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.models("nope-runtime"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.test("nope-runtime"))
                .isInstanceOf(ResponseStatusException.class);
    }
}

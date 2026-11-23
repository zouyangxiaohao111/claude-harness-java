package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真机 E2E · 用本机已装的 {@code dsh --profile acp}（@deepseek-ai/dsh）跑
 * initialize + session/new，有真 key 时再跑一次 prompt。
 *
 * <p>默认<b>跳过</b>（{@code NEXUSAI_ACP_E2E=1} 才跑），避免 CI / 无 dsh 的机器上变红。
 * 证据口径：初始化与会话创建不消耗模型调用，可用 dummy key 完成；prompt 需要真 key，
 * 没有就如实跳过（不伪造成功）。
 */
@DisplayName("[acp] 真机 dsh E2E（默认跳过）")
class AcpRealDshSmokeTest {

    @Test
    void dshInitializeAndNewSession() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("NEXUSAI_ACP_E2E")),
                "NEXUSAI_ACP_E2E!=1，跳过真机 E2E");
        String bin = ExternalRuntimeSpec.resolveExecutable("dsh");
        Assumptions.assumeTrue(bin != null, "本机未安装 dsh，跳过");

        Map<String, String> env = new HashMap<>();
        boolean hasRealKey = System.getenv("DEEPSEEK_API_KEY") != null
                && !System.getenv("DEEPSEEK_API_KEY").isBlank();
        if (!hasRealKey) {
            // dsh 启动期只检查 key 是否存在；initialize / session/new 不调用模型
            env.put("DEEPSEEK_API_KEY", "sk-dummy-for-boot");
        }

        AcpProcess.Started started = null;
        AcpTransport transport = null;
        try {
            started = AcpProcess.start(List.of(bin, "--profile", "acp"),
                    Path.of(System.getProperty("java.io.tmpdir")), env);
            transport = AcpTransport.forProcess(started);
            AcpClient client = new AcpClient(transport);
            transport.start();

            JsonNode init = client.initialize(60_000);
            assertThat(init.path("protocolVersion").asInt()).isGreaterThanOrEqualTo(1);

            String sessionId = client.newSession(Path.of(System.getProperty("java.io.tmpdir")), 30_000);
            assertThat(sessionId).isNotBlank();

            if (hasRealKey) {
                JsonNode prompt = client.prompt(
                        "Reply with the single word ACP_OK and stop.", 180_000);
                assertThat(prompt.path("stopReason").asText()).isNotBlank();
            }
        } finally {
            // 同 AcpAgentRunner：先收进程，再关传输（避免 stdout close 与 readLine 互等）
            if (started != null) {
                AcpProcess.quiesce(started.process(), 6_000);
            }
            if (transport != null) {
                transport.close();
            }
        }
    }
}

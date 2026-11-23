package com.nexusai.application.agent.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真探测 · 用独立 JVM 跑 {@link AcpFakeServerMain}，断言 ok / probeLevel 的真实语义。
 */
@DisplayName("[acp] AcpRuntimeProbe 真探测")
class AcpRuntimeProbeTest {

    @Test
    @DisplayName("正常适配器 ⇒ ok=true + acp_session")
    void probeSucceedsAgainstFakeServer() {
        Path cwd = Path.of(System.getProperty("java.io.tmpdir"));
        AcpRuntimeSpec spec = new AcpRuntimeSpec(
                "acp", javaBin(),
                List.of("-cp", childClasspath(), AcpFakeServerMain.class.getName()),
                Map.of(), null, cwd, 30_000L, AcpPermissionBridge.POLICY_ASK);

        AcpRuntimeProbe.Result result = AcpRuntimeProbe.probe(spec, 20_000L);

        assertThat(result.ok()).isTrue();
        assertThat(result.probeLevel()).isEqualTo("acp_session");
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("可执行文件不存在 ⇒ ok=false + failed（不抛）")
    void probeFailsGracefully() {
        AcpRuntimeSpec spec = new AcpRuntimeSpec(
                "acp", "C:/definitely/not/here/xyz-not-exist",
                List.of(), Map.of(), null,
                Path.of(System.getProperty("java.io.tmpdir")), 5_000L,
                AcpPermissionBridge.POLICY_ASK);

        AcpRuntimeProbe.Result result = AcpRuntimeProbe.probe(spec, 2_000L);

        assertThat(result.ok()).isFalse();
        assertThat(result.probeLevel()).isEqualTo("failed");
        assertThat(result.message()).isNotBlank();
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin",
                AcpProcess.isWindows() ? "java.exe" : "java").toString();
    }

    /** 子进程 cwd 在 temp ⇒ classpath 相对项必须转绝对。 */
    private static String childClasspath() {
        String cp = System.getProperty("surefire.test.class.path");
        if (cp == null || cp.isBlank()) {
            cp = System.getProperty("java.class.path");
        }
        StringBuilder out = new StringBuilder();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(File.pathSeparator);
            }
            out.append(Path.of(entry).toAbsolutePath());
        }
        return out.toString();
    }
}

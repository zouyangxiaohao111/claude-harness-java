package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 危险命令硬阻断 · 清单照 Octop {@code acp/permissions.py:11-18}。
 */
@DisplayName("[acp] AcpDangerousCommandPolicy 硬阻断")
class AcpDangerousCommandPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CWD = Path.of(System.getProperty("java.io.tmpdir"));

    private static JsonNode toolCall(String kind, Map<String, Object> rawInput, List<Map<String, String>> locations) {
        Map<String, Object> call = new java.util.LinkedHashMap<>();
        call.put("toolCallId", "t-1");
        call.put("title", "cmd");
        call.put("kind", kind);
        if (rawInput != null) {
            call.put("rawInput", rawInput);
        }
        if (locations != null) {
            call.put("locations", locations);
        }
        return JSON.valueToTree(call);
    }

    @Test
    @DisplayName("四个危险片段都被阻断")
    void blockedPatterns() {
        for (String command : List.of("rm -rf /", "sudo rm -rf /opt", "mkfs.ext4 /dev/sda", "dd if=/dev/zero of=x")) {
            AcpDangerousCommandPolicy.Decision decision =
                    AcpDangerousCommandPolicy.check(toolCall("execute", Map.of("command", command), null), CWD);
            assertThat(decision.blocked()).as("command=%s", command).isTrue();
        }
    }

    @Test
    @DisplayName("普通命令 + 目录内路径 ⇒ 放行")
    void allowsSafeCommand() {
        AcpDangerousCommandPolicy.Decision decision =
                AcpDangerousCommandPolicy.check(toolCall("execute", Map.of("command", "mvn test"), null), CWD);
        assertThat(decision.blocked()).isFalse();

        Path inside = CWD.resolve("src/app.txt");
        AcpDangerousCommandPolicy.Decision read =
                AcpDangerousCommandPolicy.check(toolCall("read", Map.of("path", inside.toString()), null), CWD);
        assertThat(read.blocked()).isFalse();
    }

    @Test
    @DisplayName("越界路径 ⇒ 阻断（含 ../ 逃逸）")
    void blocksPathEscape() {
        Path outside = CWD.resolve("..").resolve("escape.txt");
        AcpDangerousCommandPolicy.Decision decision = AcpDangerousCommandPolicy.check(
                toolCall("edit", Map.of("path", outside.toString()), null), CWD);
        assertThat(decision.blocked()).isTrue();

        AcpDangerousCommandPolicy.Decision viaLocations = AcpDangerousCommandPolicy.check(
                toolCall("read", null, List.of(Map.of("path", CWD.resolve("..").resolve("x").toString()))), CWD);
        assertThat(viaLocations.blocked()).isTrue();
    }

    @Test
    @DisplayName("无 rawInput ⇒ 放行；args 数组拼成命令参与匹配")
    void argsFallback() {
        assertThat(AcpDangerousCommandPolicy.check(toolCall("other", null, null), CWD).blocked()).isFalse();

        AcpDangerousCommandPolicy.Decision decision = AcpDangerousCommandPolicy.check(
                toolCall("execute", Map.of("args", List.of("rm", "-rf", "/")), null), CWD);
        assertThat(decision.blocked()).isTrue();
    }
}

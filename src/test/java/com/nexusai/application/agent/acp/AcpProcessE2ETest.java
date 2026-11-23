package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真子进程 E2E · spawn 一个独立 JVM 跑 {@link AcpFakeServerMain}，经真 stdio 走完
 * initialize → session/new → session/prompt，收齐 session/update 并拿到 end_turn。
 *
 * <p>与 {@link AcpClientProtocolTest} 的分工：那边用内存管道测协议细节；这边验证
 * 「真进程 + 真进程树收尾 + 真 stdio」这一段，且不需要模型密钥。
 */
@DisplayName("[acp] 真子进程 E2E（fake stdio server）")
class AcpProcessE2ETest {

    @Test
    void fullPromptRoundOverRealProcess() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                AcpProcess.isWindows() ? "java.exe" : "java").toString();
        Path cwd = Path.of(System.getProperty("java.io.tmpdir"));

        AcpProcess.Started started = AcpProcess.start(
                List.of(javaBin, "-cp", classpath(), AcpFakeServerMain.class.getName()),
                cwd, Map.of());
        AcpTransport transport = AcpTransport.forProcess(started);
        transport.start();
        AcpClient client = new AcpClient(transport);
        List<String> updates = new CopyOnWriteArrayList<>();
        client.setUpdateListener(update -> updates.add(update.path("sessionUpdate").asText("")));
        try {
            JsonNode init = client.initialize(30_000);
            assertThat(init.path("protocolVersion").asInt()).isEqualTo(1);

            String sessionId = client.newSession(cwd, 15_000);
            assertThat(sessionId).isEqualTo("fake-session-1");

            JsonNode result = client.prompt("say hello", 30_000);
            // prompt result = 本轮已结算；但 session/update 在独立通知线程处理，可能稍晚 ⇒ 有界等待
            boolean allSeen = awaitAll(updates, 3_000,
                    "agent_message_chunk", "tool_call", "tool_call_update");
            // 原始证据行：放进日志，便于回报「这一轮到底收到了什么」
            System.out.println("[AcpProcessE2ETest] prompt stopReason="
                    + result.path("stopReason").asText() + " updates=" + updates
                    + " allSeen=" + allSeen);
            assertThat(result.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(allSeen)
                    .as("等待 3000ms 未收齐 3 条 update；实际=%s", updates)
                    .isTrue();
            assertThat(updates).contains("agent_message_chunk", "tool_call", "tool_call_update");
        } finally {
            // 先收进程再关传输（与 AcpAgentRunner 同序，避免 stdout close/readLine 互等）
            AcpProcess.quiesce(started.process(), 3_000);
            transport.close();
        }
    }

    /** 有界等待若干 update 都到齐（result 与通知无先后保证 · 10ms 间隔）。 */
    private static boolean awaitAll(List<String> updates, long timeoutMs, String... expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (updates.containsAll(List.of(expected))) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return updates.containsAll(List.of(expected));
            }
        }
        return updates.containsAll(List.of(expected));
    }

    private static String classpath() {
        String cp = System.getProperty("surefire.test.class.path");
        if (cp == null || cp.isBlank()) {
            cp = System.getProperty("java.class.path");
        }
        // ⚠️ 子进程 cwd 是 temp 目录：classpath 里的相对项必须转绝对，否则子进程找不到主类
        StringBuilder out = new StringBuilder();
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(java.io.File.pathSeparator);
            }
            out.append(Path.of(entry).toAbsolutePath());
        }
        return out.toString();
    }
}

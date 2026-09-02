package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * [S02 X-8] cleanup 升级序列 · 对齐 CC client.ts:1426-1562（SIGINT→SIGTERM→SIGKILL 轮询
 * 升级 + 总 failsafe 600ms；Java 两级 destroy()≈SIGTERM → destroyForcibly()≈SIGKILL，
 * 受控偏差登记见 concerns）。
 *
 * <p><b>WHY（规则九）</b>：旧实现裸 process.destroy()——不保证优雅关闭（Docker/常驻进程
 * 忽略终止信号），且无等待/升级（X-8 脏代码）。Windows 无法观测 SIGINT 特定步骤，本测试
 * 锁定可观察契约：
 * <ol>
 *   <li>常驻子进程（忽略 stdin 的 java 子进程）close() 后限时内进程终止（升级序列完成）</li>
 *   <li>close() 后 state CLOSED + pending 全部失败（防悬挂）</li>
 *   <li>reader 线程（stdout/stderr）随进程退出自然终止</li>
 *   <li>close 幂等（二次调用 no-op）</li>
 * </ol>
 */
@DisplayName("[S02 X-8] stdio cleanup 升级序列")
class StdioMcpTransportCleanupTest {

    private StdioMcpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    private static McpTransport.TransportConfig sleepConfig(String serverName) {
        Path javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java");
        return new McpTransport.TransportConfig(
            javaBin.toString(),
            List.of("-cp", System.getProperty("java.class.path"),
                StdioTestChildMain.class.getName(), "sleep"),
            Map.of(), null, serverName, "stdio");
    }

    @Test
    @DisplayName("常驻子进程 close() → 限时内进程终止（升级序列）+ state CLOSED + pending 失败 + reader 退出")
    void close_terminatesResidentChild_withinLimit() throws Exception {
        transport = new StdioMcpTransport();
        transport.start(sleepConfig("sleep-srv"));
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);

        // 制造 pending（子进程忽略 stdin → 永不响应）→ close 必须拒绝（防悬挂）
        CompletableFuture<JsonNode> pendingFut = transport.sendRequest("tools/call", Map.of("name", "t"));

        long start = System.currentTimeMillis();
        transport.close();
        long elapsed = System.currentTimeMillis() - start;

        // 升级序列总时长受控（destroy 100ms + destroyForcibly 400ms + 轮询 ≈600ms；宽松上界 5s）
        assertThat(elapsed).as("cleanup 升级序列必须限时完成（总 failsafe ≈600ms 语义）").isLessThan(5_000);
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CLOSED);

        Throwable thrown = catchThrowable(() -> pendingFut.get(2, TimeUnit.SECONDS));
        assertThat(thrown).as("close 必须拒绝 pending（防悬挂）").isNotNull();
        assertThat(thrown).hasMessageContaining("transport closed");

        // reader 线程随进程退出自然终止（stdout/stderr EOF）
        awaitThreadsGone("mcp-stdio-reader", "mcp-stdio-stderr-reader");

        // close 幂等（二次调用 no-op，不抛）
        transport.close();
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CLOSED);
    }

    /** 等待具名 daemon 线程退出（≤5s）。 */
    private static void awaitThreadsGone(String... names) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            boolean anyAlive = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                for (String name : names) {
                    if (t.getName().equals(name) && t.isAlive()) {
                        anyAlive = true;
                    }
                }
            }
            if (!anyAlive) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("reader 线程未在限时内退出: " + String.join(", ", names));
    }
}

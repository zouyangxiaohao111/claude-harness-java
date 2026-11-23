package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 父轮取消 → ACP 子进程收尾 · 真子进程验证：
 * ① 发出 {@code session/cancel}（假 server 写标记文件为证）；
 * ② 在飞的 {@code session/prompt} 被解除；
 * ③ 进程树退出；④ listener 释放（不累积）。
 */
@DisplayName("[acp] 父轮取消中断 ACP")
class AcpAbortBridgeTest {

    @Test
    @DisplayName("父 abort ⇒ 发 session/cancel + prompt 解除 + 子进程退出")
    void parentAbortCancelsPromptAndExitsProcess() throws Exception {
        Path cwd = Path.of(System.getProperty("java.io.tmpdir"));
        Path marker = Files.createTempFile("acp-cancel-marker-", ".flag");
        Files.deleteIfExists(marker);

        AcpProcess.Started started = AcpProcess.start(
                List.of(javaBin(), "-cp", childClasspath(), AcpFakeServerMain.class.getName(),
                        marker.toString(), "hold"),
                cwd, Map.of());
        AcpTransport transport = AcpTransport.forProcess(started);
        transport.start();
        AcpClient client = new AcpClient(transport);
        AbortController parent = new AbortController();
        AcpAbortBridge bridge = new AcpAbortBridge(parent);
        CountDownLatch promptDone = new CountDownLatch(1);
        try {
            client.initialize(20_000);
            client.newSession(cwd, 10_000);

            Thread promptThread = new Thread(() -> {
                try {
                    client.prompt("hold me", 30_000);
                } catch (Throwable ignore) {
                    // 被取消导致连接断开，属预期
                } finally {
                    promptDone.countDown();
                }
            }, "acp-prompt-holder");
            promptThread.setDaemon(true);
            promptThread.start();

            // 等 session/prompt 帧发出并挂起（本地管道，500ms 足够）
            Thread.sleep(500);

            bridge.bind();
            bridge.attach(client, started);
            parent.abort();

            assertThat(promptDone.await(8, TimeUnit.SECONDS))
                    .as("父 abort 后挂起的 session/prompt 应被解除")
                    .isTrue();
            assertThat(awaitFile(marker, 4_000))
                    .as("假 server 应收到 ACP session/cancel（标记文件未出现）")
                    .isTrue();
            assertThat(started.process().waitFor(8, TimeUnit.SECONDS))
                    .as("父 abort 后子进程应退出")
                    .isTrue();
            assertThat(bridge.isCancelled()).isTrue();
        } finally {
            bridge.unbind();
            AcpProcess.killTree(started.process());
            transport.close();
            Files.deleteIfExists(marker);
        }
    }

    @Test
    @DisplayName("父已取消 ⇒ bind 立即生效；attach(null) 不 NPE；unbind 释放监听")
    void alreadyCancelledParentAbortsImmediately() {
        AbortController parent = new AbortController();
        parent.abort();
        AcpAbortBridge bridge = new AcpAbortBridge(parent);

        bridge.bind();
        assertThat(bridge.isCancelled()).isTrue();

        bridge.attach(null, null);   // 无子进程时不抛
        bridge.unbind();
        assertThat(parent.listenerCount()).isZero();
    }

    private static boolean awaitFile(Path file, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) {
                return true;
            }
            Thread.sleep(20);
        }
        return Files.isRegularFile(file);
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

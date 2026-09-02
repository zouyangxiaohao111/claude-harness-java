package com.nexusai.application.agent.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.impl.LspTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LspManager → ProcessLspClient 真实子进程 E2E · 对齐 CC LSPServerManager.ts:215-236 (ensureServerStarted)
 * + :270-368 (openFile/changeFile/saveFile)。
 *
 * <p>WHY 覆盖意图（CLAUDE.md 规则九）：
 * <ul>
 *   <li><b>真实子进程</b>：配置 {@code python mock-lsp-server.py}，ensureServerStarted 惰性启动真实
 *       ProcessLspClient（ProcessBuilder + initialize 握手），非 InMemoryLspClient stub。</li>
 *   <li><b>真实帧</b>：mock server 线程安全记录收到的 didOpen/didChange/didSave，经
 *       {@code test/getNotifications} 返回，断言真实帧已写入真实子进程 stdin（非 log-only stub）。</li>
 *   <li><b>isLspConnected / LspTool.isEnabled 恢复可连</b>：configured 后 isLspConnected==true
 *       （对齐 CC manager.ts:100-110，NOT_STARTED 也计 connected），LspTool.isEnabled()==true。</li>
 * </ul>
 *
 * <p>RED 前提（改码前）：当前代码 InMemoryLspClient stub（sendRequest 恒 null）、无 openFile 兜底、
 * isLspConnected 恒 false → 本测试编译即失败（LspManager 无 openFile/sendRequest/isFileOpen 方法）。
 */
class LspManagerProcessE2ETest {

    /** 解析 mock-lsp-server.py 绝对路径（classpath 资源 → target/test-classes/mock-lsp-server.py）。 */
    private static String mockServerPath() throws Exception {
        URL url = LspManagerProcessE2ETest.class.getClassLoader().getResource("mock-lsp-server.py");
        assertThat(url)
            .as("mock-lsp-server.py 必须在测试 classpath 上（src/test/resources）")
            .isNotNull();
        return Paths.get(url.toURI()).toAbsolutePath().toString();
    }

    /** 配置真实子进程：command=python（本机 3.14.4 已确认），args=[mock 脚本]，extensions=['ts']。 */
    private static LspManager configuredManager() throws Exception {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(
                List.of("ts"), "python", List.of(mockServerPath()), Map.of(),
                Map.of("ts", "typescript"), null)));
        return mgr;
    }

    @Test
    @DisplayName("E2E：真实子进程收到 didOpen → didChange → didSave（真实帧写入真实 stdin）")
    void realSubprocess_receivesDidOpenDidChangeDidSave() throws Exception {
        LspManager mgr = configuredManager();
        try {
            mgr.openFile("/a.ts", "content1");   // ensureServerStarted → 真实子进程 start+initialize → didOpen
            mgr.changeFile("/a.ts", "content2"); // 已 opened → didChange
            mgr.saveFile("/a.ts");               // didSave

            JsonNode notifications = mgr.sendRequest("/a.ts", "test/getNotifications", Map.of(), JsonNode.class);

            assertThat(notifications)
                .as("真实子进程必须返回通知记录（test/getNotifications）")
                .isNotNull();
            assertThat(notifications.isArray()).isTrue();
            assertThat(notifications.size())
                .as("真实子进程必须收到 3 帧：didOpen + didChange + didSave")
                .isEqualTo(3);

            // didOpen：uri + languageId=typescript + version:1 + text=content1
            JsonNode didOpen = notifications.get(0);
            assertThat(didOpen.get("method").asText()).isEqualTo("textDocument/didOpen");
            JsonNode didOpenDoc = didOpen.get("params").get("textDocument");
            assertThat(didOpenDoc.get("uri").asText()).contains("a.ts");
            assertThat(didOpenDoc.get("languageId").asText()).isEqualTo("typescript");
            assertThat(didOpenDoc.get("version").asInt()).isEqualTo(1);
            assertThat(didOpenDoc.get("text").asText()).isEqualTo("content1");

            // didChange：contentChanges[0].text=content2
            JsonNode didChange = notifications.get(1);
            assertThat(didChange.get("method").asText()).isEqualTo("textDocument/didChange");
            assertThat(didChange.get("params").get("contentChanges").get(0).get("text").asText())
                .isEqualTo("content2");

            // didSave
            JsonNode didSave = notifications.get(2);
            assertThat(didSave.get("method").asText()).isEqualTo("textDocument/didSave");
        } finally {
            mgr.shutdown();
        }
    }

    @Test
    @DisplayName("isLspConnected == true 且 LspTool.isEnabled == true（恢复可连，对齐 CC manager.ts:100-110）")
    void isLspConnected_true_and_lspToolEnabled() throws Exception {
        LspManager mgr = configuredManager();
        try {
            assertThat(mgr.isLspConnected())
                .as("configured 后（initialized + 非空 + NOT_STARTED 非 FAILED）必须可连")
                .isTrue();
            assertThat(new LspTool(mgr).isEnabled())
                .as("LSPTool.isEnabled 委托 isLspConnected，恢复可连后必须启用")
                .isTrue();
        } finally {
            mgr.shutdown();
        }
    }
}

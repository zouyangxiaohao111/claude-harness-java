package com.nexusai.application.agent.lsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LspManager.changeFile / saveFile / isLspConnected 单测 · 对齐 CC LSPServerManager.ts:270-368 + manager.ts:100-110。
 *
 * <p>WHY 覆盖意图（CLAUDE.md 规则九）：
 * <ul>
 *   <li><b>didOpen 兜底</b>：changeFile 前文件未 opened → openFile 兜底发 didOpen
 *       （CC LSPServerManager.ts:321-324，LSP 要求 didOpen 先于 didChange）。</li>
 *   <li><b>didChange 通知</b>：已 opened 后 changeFile 发 {@code textDocument/didChange}，
 *       参数含 {@code textDocument.uri} + {@code version:1}（CC :327 恒 1）+ {@code contentChanges}。</li>
 *   <li><b>didSave 通知</b>：saveFile 发 {@code textDocument/didSave}，参数含 {@code textDocument.uri}。</li>
 *   <li><b>非 READY 短路</b>：server 非 READY 时 saveFile 不发送通知（CC state!=='running' → return）。</li>
 *   <li><b>isLspConnected 语义</b>：initialized + 非空 + 任一非 FAILED → true（NOT_STARTED 也计 connected，
 *       对齐 CC manager.ts:100-110，不再要求 READY）。</li>
 * </ul>
 */
class LspManagerChangeFileSaveFileTest {

    /** 捕获 sendNotification 的 mock LspClient。 */
    private static final class CapturingLspClient implements LspClient {
        final List<Map.Entry<String, Object>> notifications = new ArrayList<>();

        @Override public Map<String, Object> capabilities() { return Map.of(); }
        @Override public boolean isInitialized() { return true; }
        @Override public void start(String command, String[] args, Map<String, String> env, String cwd) {}
        @Override public LspInitializeResult initialize(String rootUri) { return null; }
        @Override public <T> T sendRequest(String method, Object params, Class<T> resultType) { return null; }
        @Override public void sendNotification(String method, Object params) {
            notifications.add(Map.entry(method, params));
        }
        @Override public void stop() {}
    }

    private static LspManager readyManager(CapturingLspClient client) {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(List.of("ts"), "cmd", List.of(), Map.of())));
        mgr.getServerForFile("/a.ts").orElseThrow().setState(LspManager.State.READY);
        mgr.registerClient("ts", client);
        return mgr;
    }

    @Test
    @DisplayName("changeFile 未 opened → didOpen 兜底；已 opened → didChange；saveFile → didSave（CC didOpen 前置）")
    void changeFile_openFileFallback_thenDidChange_andSave() {
        CapturingLspClient client = new CapturingLspClient();
        LspManager mgr = readyManager(client);

        // 首次 changeFile：文件未 opened → openFile 兜底 → didOpen（不是 didChange）
        mgr.changeFile("/a.ts", "v1");
        // 二次 changeFile：已 opened → didChange
        mgr.changeFile("/a.ts", "v2");
        mgr.saveFile("/a.ts");

        assertThat(client.notifications).hasSize(3);
        assertThat(client.notifications.get(0).getKey()).isEqualTo("textDocument/didOpen");
        assertThat(client.notifications.get(1).getKey()).isEqualTo("textDocument/didChange");
        assertThat(client.notifications.get(2).getKey()).isEqualTo("textDocument/didSave");

        // didOpen 参数：textDocument.uri + languageId(derived plaintext) + version:1 + text
        Map<?, ?> didOpenParams = (Map<?, ?>) client.notifications.get(0).getValue();
        Map<?, ?> didOpenDoc = (Map<?, ?>) didOpenParams.get("textDocument");
        assertThat((String) didOpenDoc.get("uri")).contains("a.ts");
        assertThat(didOpenDoc.get("version")).isEqualTo(1);
        assertThat((String) didOpenDoc.get("text")).isEqualTo("v1");
        assertThat((String) didOpenDoc.get("languageId")).isEqualTo("plaintext");

        // didChange 参数：textDocument.uri + version:1（CC 恒 1）+ contentChanges[0].text=v2
        Map<?, ?> didChangeParams = (Map<?, ?>) client.notifications.get(1).getValue();
        Map<?, ?> didChangeDoc = (Map<?, ?>) didChangeParams.get("textDocument");
        assertThat((String) didChangeDoc.get("uri")).contains("a.ts");
        assertThat(didChangeDoc.get("version")).isEqualTo(1);
        List<?> contentChanges = (List<?>) didChangeParams.get("contentChanges");
        assertThat(contentChanges).hasSize(1);
        assertThat((String) ((Map<?, ?>) contentChanges.get(0)).get("text")).isEqualTo("v2");

        // didSave 参数：textDocument.uri
        Map<?, ?> didSaveParams = (Map<?, ?>) client.notifications.get(2).getValue();
        Map<?, ?> didSaveDoc = (Map<?, ?>) didSaveParams.get("textDocument");
        assertThat((String) didSaveDoc.get("uri")).contains("a.ts");
    }

    @Test
    @DisplayName("server 非 READY：saveFile 不发送通知（CC state!=='running' → return）")
    void saveFile_nonReadyServer_noNotification() {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(List.of("ts"), "cmd", List.of(), Map.of())));
        // 不 setState(READY)：默认 NOT_STARTED
        CapturingLspClient client = new CapturingLspClient();
        mgr.registerClient("ts", client);

        mgr.saveFile("/a.ts");

        assertThat(client.notifications).isEmpty();
    }

    @Test
    @DisplayName("无 server（无扩展名映射）：零副作用、不抛")
    void changeFile_noServer_zeroSideEffect() {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of());
        // 无 ts 扩展名映射 → getServerForFile empty → return
        mgr.changeFile("/a.ts", "content");
        mgr.saveFile("/a.ts");
        // 不抛即通过
    }

    @Test
    @DisplayName("isLspConnected：未 initialized → false")
    void isLspConnected_notInitialized_false() {
        assertThat(new LspManager().isLspConnected()).isFalse();
    }

    @Test
    @DisplayName("isLspConnected：0 server → false")
    void isLspConnected_emptyServers_false() {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of());
        assertThat(mgr.isLspConnected()).isFalse();
    }

    @Test
    @DisplayName("isLspConnected：initialized + 非空 + NOT_STARTED（从未启动）→ true（对齐 CC stopped 计 connected）")
    void isLspConnected_initializedNonFailed_true() {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(List.of("ts"), "cmd", List.of(), Map.of())));
        // NOT_STARTED：从未启动，CC 'stopped' 也计 connected（manager.ts:107 state!=='error'）
        assertThat(mgr.isLspConnected()).isTrue();
    }

    @Test
    @DisplayName("isLspConnected：全 FAILED → false（CC manager.ts:100-110 任一 state==='error' 才 false）")
    void isLspConnected_allFailed_false() {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(List.of("ts"), "cmd", List.of(), Map.of())));
        mgr.getServerForFile("/a.ts").orElseThrow().setState(LspManager.State.FAILED);
        assertThat(mgr.isLspConnected()).isFalse();
    }
}

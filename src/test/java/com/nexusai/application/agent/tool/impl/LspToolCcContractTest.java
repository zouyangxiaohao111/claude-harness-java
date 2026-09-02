package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.lsp.LspClient;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D3 · LspTool 对齐 CC LSPTool.ts 的契约测试。
 *
 * <p>WHY 覆盖意图（CLAUDE.md 规则九）：
 * <ul>
 *   <li><b>HIGH R-3 两步 call hierarchy</b>：incomingCalls/outgoingCalls 必须先
 *       prepareCallHierarchy 拿 CallHierarchyItem，再以 {@code {item}} 请求实际 calls；
 *       旧实现直接 map 到 callHierarchy/* + 错误参数 → LSP server 无法解析 → 功能不可用。</li>
 *   <li><b>didOpen + 10MB 上限（Q-6 归属裁决）</b>：LSPTool.execute 在发请求前
 *       didOpen（文件未 open 时读取 + openFile），超 10MB 拒绝（CC LSPTool.ts:259-278）。</li>
 *   <li><b>languageId 删除（⊕-10）</b>：buildParams 不再附加 languageId="plaintext"（CC 不发）。</li>
 *   <li><b>错误语义</b>：未连接 → "Error:" 前缀被 isToolErrorData 识别为错误。</li>
 * </ul>
 */
@DisplayName("IMP-D3 · LspTool CC 契约（两步 call hierarchy {item} + didOpen/10MB + languageId 删）")
class LspToolCcContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 记录 sendRequest/sendNotification 的 mock LspClient · 对齐 LspManagerChangeFileSaveFileTest 模式。 */
    private static final class RecordingLspClient implements LspClient {
        final List<Map.Entry<String, Object>> requests = new ArrayList<>();
        final List<Map.Entry<String, Object>> notifications = new ArrayList<>();
        final Map<String, JsonNode> responses = new LinkedHashMap<>();
        JsonNode defaultResponse = JSON.createObjectNode();

        @Override public Map<String, Object> capabilities() { return Map.of(); }
        @Override public boolean isInitialized() { return true; }
        @Override public void start(String command, String[] args, Map<String, String> env, String cwd) {}
        @Override public LspInitializeResult initialize(String rootUri) { return null; }
        @Override public <T> T sendRequest(String method, Object params, Class<T> resultType) {
            requests.add(Map.entry(method, params));
            JsonNode r = responses.containsKey(method) ? responses.get(method) : defaultResponse;
            return (T) r;
        }
        @Override public void sendNotification(String method, Object params) {
            notifications.add(Map.entry(method, params));
        }
        @Override public void stop() {}
    }

    private static LspTool wiredTool(RecordingLspClient client) {
        LspManager mgr = new LspManager();
        mgr.initialize(Map.of(
            "ts", new LspManager.LspServerConfig(List.of("ts"), "cmd", List.of(), Map.of())));
        mgr.getServerForFile("/a.ts").orElseThrow().setState(LspManager.State.READY);
        mgr.registerClient("ts", client);
        return new LspTool(mgr);
    }

    private static ObjectNode input(String filePath, String operation) {
        ObjectNode node = JSON.createObjectNode();
        node.put("operation", operation);
        node.put("filePath", filePath);
        node.put("line", 3);
        node.put("character", 5);
        return node;
    }

    @Test
    @DisplayName("incomingCalls 两步：先 prepareCallHierarchy 取 item，再以 {item} 请求 callHierarchy/incomingCalls（HIGH R-3）")
    void execute_incomingCalls_twoStepWithItem(@TempDir Path ws) throws Exception {
        Path file = ws.resolve("a.ts");
        Files.writeString(file, "export function foo() {}");
        RecordingLspClient client = new RecordingLspClient();
        // prepareCallHierarchy 返回 CallHierarchyItem 数组
        ArrayNode item = JSON.createArrayNode().add(JSON.createObjectNode()
            .put("name", "foo").put("kind", 3).put("uri", "file://" + file.toString().replace('\\', '/')));
        client.responses.put("textDocument/prepareCallHierarchy", item);
        LspTool tool = wiredTool(client);

        var result = tool.execute(new ToolUseBlock("call-1", "LSP",
            input(file.toString(), "incomingCalls")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(0).getKey())
            .as("第一步必须 prepareCallHierarchy（CC LSPTool.ts:299-306）")
            .isEqualTo("textDocument/prepareCallHierarchy");
        assertThat(client.requests.get(1).getKey())
            .as("第二步必须 callHierarchy/incomingCalls（CC :319-326）")
            .isEqualTo("callHierarchy/incomingCalls");
        Map<?, ?> callParams = (Map<?, ?>) client.requests.get(1).getValue();
        assertThat(callParams.containsKey("item"))
            .as("call hierarchy 参数必须含 {item}（CC :324-325 {item: callItems[0]}）")
            .isTrue();
        Object itemSent = callParams.get("item");
        assertThat(itemSent).isNotNull();
    }

    @Test
    @DisplayName("prepare 无 item → 错误返回（CC :307-316 无 call hierarchy item）")
    void execute_callHierarchy_noPrepareItem_errors(@TempDir Path ws) throws Exception {
        Path file = ws.resolve("a.ts");
        Files.writeString(file, "export function foo() {}");
        RecordingLspClient client = new RecordingLspClient();
        client.responses.put("textDocument/prepareCallHierarchy", JSON.createArrayNode()); // 空数组
        LspTool tool = wiredTool(client);

        var result = tool.execute(new ToolUseBlock("call-1", "LSP",
            input(file.toString(), "outgoingCalls")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data())).contains("No call hierarchy item found");
    }

    @Test
    @DisplayName("didOpen：文件未 open → execute 先发 textDocument/didOpen 再发请求（CC :261-278）")
    void execute_didOpen_opensFileBeforeRequest(@TempDir Path ws) throws Exception {
        Path file = ws.resolve("a.ts");
        Files.writeString(file, "export function foo() {}");
        RecordingLspClient client = new RecordingLspClient();
        LspTool tool = wiredTool(client);

        var result = tool.execute(new ToolUseBlock("call-1", "LSP",
            input(file.toString(), "hover")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        boolean didOpen = client.notifications.stream()
            .anyMatch(e -> "textDocument/didOpen".equals(e.getKey()));
        assertThat(didOpen)
            .as("文件未 open 时必须先 didOpen（CC LSPTool.ts:261-274；Q-6 归属裁决：本工具落地）")
            .isTrue();
        assertThat(client.requests.stream().map(Map.Entry::getKey))
            .contains("textDocument/hover");
    }

    @Test
    @DisplayName("10MB 上限：文件超限 → 拒绝且不 didOpen（CC LSPTool.ts:265-271）")
    void execute_didOpen_fileTooLarge_rejected(@TempDir Path ws) throws Exception {
        Path file = ws.resolve("big.ts");
        byte[] big = new byte[10_000_001]; // 略超 10MB
        Files.write(file, big);
        RecordingLspClient client = new RecordingLspClient();
        LspTool tool = wiredTool(client);

        var result = tool.execute(new ToolUseBlock("call-1", "LSP",
            input(file.toString(), "hover")));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data())).contains("File too large for LSP analysis");
        assertThat(client.notifications).as("超限不得 didOpen（CC :265-271 提前返回）")
            .isEmpty();
    }

    @Test
    @DisplayName("buildParams 无 languageId 硬编码（⊕-10：CC 不发 languageId）")
    void execute_params_noLanguageId(@TempDir Path ws) throws Exception {
        Path file = ws.resolve("a.ts");
        Files.writeString(file, "export function foo() {}");
        RecordingLspClient client = new RecordingLspClient();
        LspTool tool = wiredTool(client);

        tool.execute(new ToolUseBlock("call-1", "LSP", input(file.toString(), "hover")));

        Map.Entry<String, Object> hoverReq = client.requests.stream()
            .filter(e -> "textDocument/hover".equals(e.getKey()))
            .findFirst().orElseThrow();
        Map<?, ?> params = (Map<?, ?>) hoverReq.getValue();
        assertThat(params.containsKey("languageId"))
            .as("顶层不得含 languageId（CC LSPTool.ts getMethodAndParams 无 languageId）")
            .isFalse();
        Map<?, ?> textDoc = (Map<?, ?>) params.get("textDocument");
        assertThat(textDoc.containsKey("languageId"))
            .as("textDocument 内不得含 languageId（⊕-10 已删）")
            .isFalse();
    }

    @Test
    @DisplayName("未连接 → 'Error:' 前缀被 isToolErrorData 识别为错误（LspTool A3 状态机）")
    void execute_notConnected_errorDataRecognized() {
        LspTool tool = new LspTool(new LspManager()); // 未 initialize → isEnabled false
        ObjectNode in = JSON.createObjectNode();
        in.put("operation", "hover");
        in.put("filePath", "/a.ts");
        in.put("line", 1);
        in.put("character", 1);

        var result = tool.execute(new ToolUseBlock("call-1", "LSP", in));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("LSP 未连接必须被识别为错误数据（is_error=true 语义）")
            .isTrue();
        assertThat(String.valueOf(result.data())).contains("LSP not connected");
    }
}

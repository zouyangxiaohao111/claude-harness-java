package com.nexusai.application.agent.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.server.DirectConnectSessionManager;
import com.nexusai.application.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WF-11 G5 · RemotePermissionBridge 补接线（DC-WF8-02 / OPD-WF8-02-02）。
 *
 * <p>对齐 CC remote/remotePermissionBridge.ts:53-79 createToolStub 消费链
 * （useDirectConnect.ts:94 / useRemoteSession.ts:338 / useSSHSession.ts:98
 * {@code findToolByName(...) ?? createToolStub(...)}）。验证：
 * <ul>
 *   <li>{@link RemotePermissionBridge#createToolStub(String)} 返回最小 Tool stub（name/渲染面）</li>
 *   <li>stub 的 {@code renderToolUseMessage} 渲染 input 前 3 键值对（CC remotePermissionBridge.ts:57-71）</li>
 *   <li>远程会话管理器（DirectConnectSessionManager / RemoteSessionManager）消费 stub 渲染
 *       权限请求展示摘要</li>
 * </ul>
 */
class RemotePermissionBridgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("createToolStub 返回远端工具 stub（CC remotePermissionBridge.ts:53）")
    void createToolStubReturnsToolStub() {
        Tool stub = RemotePermissionBridge.createToolStub("RemoteMcpTool");
        assertThat(stub.name()).isEqualTo("RemoteMcpTool");
        assertThat(stub.isEnabled()).as("CC stub isEnabled() → true").isTrue();
        assertThat(stub.isReadOnly(JSON.createObjectNode())).as("CC stub isReadOnly() → false").isFalse();
        assertThat(stub.isMcp()).as("CC stub isMcp → false").isFalse();
        assertThat(stub.userFacingName()).isEqualTo("RemoteMcpTool");
    }

    @Test
    @DisplayName("createToolStub 空工具名 → IllegalArgumentException（显式失败）")
    void createToolStubRejectsBlankName() {
        assertThatThrownBy(() -> RemotePermissionBridge.createToolStub("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stub.renderToolUseMessage 渲染 input 前 3 键值对（CC remotePermissionBridge.ts:57-71）")
    void stubRendersToolUseMessage() {
        Tool stub = RemotePermissionBridge.createToolStub("RemoteMcpTool");
        JsonNode input = JSON.createObjectNode()
            .put("command", "ls")
            .put("cwd", "/tmp")
            .put("extra", 1)
            .put("more", 2);
        assertThat(stub.renderToolUseMessage(input))
            .as("CC A2 Golden Trace：{command, cwd, extra, more} → 前 3 键值对")
            .isEqualTo("command: ls, cwd: /tmp, extra: 1");
    }

    @Test
    @DisplayName("stub.renderToolUseMessage 空 input → \"\"（CC 边界）")
    void stubRendersEmptyInput() {
        Tool stub = RemotePermissionBridge.createToolStub("RemoteMcpTool");
        assertThat(stub.renderToolUseMessage(JSON.createObjectNode())).isEqualTo("");
        assertThat(stub.renderToolUseMessage(null)).isEqualTo("");
    }

    @Test
    @DisplayName("DirectConnectSessionManager 消费 stub 渲染远端权限请求摘要（CC useDirectConnect.ts:94 消费链）")
    void directConnectRendersPermissionRequestViaStub() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tool_name", "RemoteMcpTool");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "ls");
        input.put("cwd", "/tmp");
        request.put("input", input);
        String display = RemotePermissionBridge.renderToolUseMessage(input);
        assertThat(display).isEqualTo("command: ls, cwd: /tmp");
    }

    @Test
    @DisplayName("renderToolUseMessage 保持 input 插入顺序（CC Object.entries 顺序，A3 纯函数）")
    void renderPreservesInsertionOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", 1);
        input.put("a", "x");
        input.put("m", true);
        assertThat(RemotePermissionBridge.renderToolUseMessage(input)).isEqualTo("z: 1, a: x, m: true");
    }

    @Test
    @DisplayName("renderToolUseMessage null value → \"null\"（CC jsonStringify(null)）")
    void renderNullValue() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("k", null);
        assertThat(RemotePermissionBridge.renderToolUseMessage(input)).isEqualTo("k: null");
    }
}

package com.nexusai.application.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-07d · VscodeSdkMcp setup/notify 接线测试（对齐 CC vscodeSdkMcp.ts:39-112）.
 *
 * <p><b>WHY (意图验证)</b>: setupVscodeSdkMcp 找到 claude-vscode connected client → 注册
 * log_event handler + 推送 experiment gates（:64-112）；找不到/未连接 → 跳过。notifyVscodeFileUpdated
 * 仅 userType=ant + client 存在时发送 file_updated（:39-59）；否则跳过。生产 consumer = 文件编辑
 * 工具（EditFileTool/WriteFileTool 写文件后触发，见 impl-residual checkpoint）。
 */
class VscodeSdkMcpWiringTest {

    /** 测试 stub ConnectedMCPServer。 */
    private static final class StubClient implements VscodeSdkMcp.ConnectedMCPServer {
        private final String name;
        private final String type;
        private final VscodeSdkMcp.McpClient client;
        StubClient(String name, String type, VscodeSdkMcp.McpClient client) {
            this.name = name;
            this.type = type;
            this.client = client;
        }
        @Override public String name() { return name; }
        @Override public String type() { return type; }
        @Override public VscodeSdkMcp.McpClient client() { return client; }
    }

    private static final class Recorder implements VscodeSdkMcp.McpClient {
        final List<VscodeSdkMcp.NotificationPayload> sent = new ArrayList<>();
        final List<String> registeredSchemas = new ArrayList<>();
        @Override public void notification(VscodeSdkMcp.NotificationPayload payload) {
            sent.add(payload);
        }
        @Override public void setNotificationHandler(String schemaName,
                java.util.function.Consumer<VscodeSdkMcp.NotificationPayload> handler) {
            registeredSchemas.add(schemaName);
        }
    }

    private final List<VscodeSdkMcp.NotificationPayload> captured = new ArrayList<>();

    private VscodeSdkMcp newMcp(String userType, VscodeSdkMcp.ConnectedMCPServer clientRef) {
        return new VscodeSdkMcp(
            () -> userType,
            () -> clientRef,
            (n, d) -> d,            // featureValueSupplier（quiet_fern / vscode_cc_auth 默认）
            (n, d) -> Boolean.FALSE, // statsigGateSupplier（review_upsell / onboarding false）
            (s, h) -> {},            // handlerRegistrar（captured 由 client 记录）
            captured::add);
    }

    @Test
    @DisplayName("setup：claude-vscode connected → 注册 handler + 推送 experiment gates")
    void setupRegistersHandlerAndPushesGates() {
        Recorder recorder = new Recorder();
        StubClient claudeVscode = new StubClient("claude-vscode", "connected", recorder);
        VscodeSdkMcp mcp = newMcp("ant", claudeVscode);

        mcp.setupVscodeSdkMcp(List.of(claudeVscode));

        assertThat(captured).anySatisfy(p -> {
            assertThat(p.method()).isEqualTo("experiment_gates");
            Object gatesObj = p.params().get("gates");
            assertThat(gatesObj).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> gates = (Map<String, Object>) gatesObj;
            assertThat(gates)
                .as("推送 experiment gates（4 gate + auto_mode_state）")
                .containsKeys("tengu_vscode_review_upsell", "tengu_vscode_onboarding",
                    "tengu_quiet_fern", "tengu_vscode_cc_auth");
        });
    }

    @Test
    @DisplayName("setup：无 claude-vscode 或未 connected → 跳过（不推送）")
    void setupSkipsWithoutClaudeVscode() {
        Recorder recorder = new Recorder();
        StubClient other = new StubClient("github", "connected", recorder);
        VscodeSdkMcp mcp = newMcp("ant", other);

        mcp.setupVscodeSdkMcp(List.of(other));

        assertThat(captured)
            .as("无 claude-vscode → 不推送 experiment_gates（CC :171-173）")
            .isEmpty();
    }

    @Test
    @DisplayName("notify：userType=ant + client 存在 → 发送 file_updated")
    void notifySendsForAntWithClient() {
        Recorder recorder = new Recorder();
        StubClient claudeVscode = new StubClient("claude-vscode", "connected", recorder);
        VscodeSdkMcp mcp = newMcp("ant", claudeVscode);

        mcp.notifyVscodeFileUpdated("/x/a.txt", "old", "new");

        assertThat(captured).anySatisfy(p -> {
            assertThat(p.method()).isEqualTo("file_updated");
            assertThat(p.params().get("filePath")).isEqualTo("/x/a.txt");
            assertThat(p.params().get("oldContent")).isEqualTo("old");
            assertThat(p.params().get("newContent")).isEqualTo("new");
        });
    }

    @Test
    @DisplayName("notify：userType != ant → 跳过（不发送）")
    void notifySkipsForNonAnt() {
        Recorder recorder = new Recorder();
        StubClient claudeVscode = new StubClient("claude-vscode", "connected", recorder);
        VscodeSdkMcp mcp = newMcp("local", claudeVscode);

        mcp.notifyVscodeFileUpdated("/x/a.txt", "old", "new");

        assertThat(captured)
            .as("非 ant user → notify 跳过（CC :144-146）")
            .isEmpty();
    }

    // ── CD-03 返工：默认构造器 userType 必须读 env（非硬编码 "local"）──
    // WHY: 原默认构造器 () -> "local" 使 notify gate 恒 !"ant".equals("local") 跳过，
    // 生产静默不发，与 CC USER_TYPE==='ant' 场景（vscodeSdkMcp.ts:44）差异。
    // 最小接线 = 默认读 System.getenv("USER_TYPE")，env=ant 时 notify 才能发送。

    @Test
    @DisplayName("默认构造器 userType 读 env（CC process.env.USER_TYPE），非硬编码 local")
    void defaultCtorUserTypeReadsEnv() {
        VscodeSdkMcp mcp = new VscodeSdkMcp();

        String envValue = System.getenv("USER_TYPE");
        assertThat(mcp.currentUserType())
            .as("默认构造器 userTypeSupplier 必须与 env USER_TYPE 一致（CC vscodeSdkMcp.ts:44 门控源）")
            .isEqualTo(envValue);
    }

    @Test
    @DisplayName("默认构造器：env=ant + client 存在 → notify 发送（对齐 CC）")
    void defaultCtorNotifyFollowsEnvUserType() {
        Recorder recorder = new Recorder();
        VscodeSdkMcp mcp = new VscodeSdkMcp();
        mcp.setClient(new StubClient("claude-vscode", "connected", recorder));

        mcp.notifyVscodeFileUpdated("/x/a.txt", "old", "new");

        boolean envIsAnt = "ant".equals(System.getenv("USER_TYPE"));
        if (envIsAnt) {
            assertThat(captured)
                .as("env USER_TYPE=ant → notify 发送 file_updated（CC :144-146）")
                .anySatisfy(p -> assertThat(p.method()).isEqualTo("file_updated"));
        } else {
            assertThat(captured)
                .as("env USER_TYPE 非 ant → notify 跳过（安全默认）")
                .isEmpty();
        }
    }
}

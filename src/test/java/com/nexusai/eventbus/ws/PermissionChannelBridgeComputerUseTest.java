package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.computeruse.ComputerUseApprovalPayload;
import com.nexusai.application.agent.computeruse.ComputerUseRequestAccessTool;
import com.nexusai.application.agent.permission.StompBridgePermissionCallbacks;
import com.nexusai.application.agent.permission.StompChannelPermissionCallbacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [Phase 1d] bridge / channel 权限请求事件的 <b>computerUse 面板数据</b>单测（纯单测）。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：Phase 1b 只给主通道
 * （{@code /permission-requests}）加了 {@code computerUse} ⇒ bridge（CCR / claude.ai 远程弹窗）
 * 与 channel（Telegram / iMessage / Discord）两条通道收到 CU 审批请求时<b>拿不到面板数据</b>
 * ⇒ 前端命中 fail-loud 错误面板（安全但体验差）。
 * <p>⇒ 本测试锁三件事：
 * <ol>
 *   <li><b>两个事件类都有可空 {@code computerUse} 字段</b>，形状同
 *       {@link ComputerUseApprovalPayload}；</li>
 *   <li><b>既有构造器 / 既有工厂逐字不受影响</b>（computerUse 缺省 = null ⇒
 *       {@code @JsonInclude(NON_NULL)} 省略字段 ⇒ 前端向后兼容）；</li>
 *   <li><b>发布方真的填了</b>（bridge / channel 两条通道都能拿到面板数据）。</li>
 * </ol>
 */
class PermissionChannelBridgeComputerUseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-cu-panel";

    /** CU 审批面板载荷样例（形状 = ComputerUseApprovalPayload）。 */
    private static ComputerUseApprovalPayload samplePayload() {
        return new ComputerUseApprovalPayload(
                List.of(new ComputerUseApprovalPayload.App(
                        "chrome", "Chrome", "read", "只能查看", false, false)),
                "要操作浏览器",
                List.of("clipboardRead"),
                java.util.Map.of("clipboardRead", "允许读取剪贴板"));
    }

    /** CU 工具入参样例（含 Phase 1a 就地注入的 resolvedApps）。 */
    private static ObjectNode cuToolInput() {
        ObjectNode input = JSON.createObjectNode();
        input.putArray("apps").add("Chrome");
        input.put("reason", "要操作浏览器");
        input.put("clipboardRead", true);
        ArrayNode resolved = input.putArray(ComputerUseRequestAccessTool.RESOLVED_APPS_KEY);
        ObjectNode app = resolved.addObject();
        app.put("requestedName", "Chrome");
        app.put("appId", "chrome");
        app.put("displayName", "Chrome");
        app.put("alreadyGranted", false);
        app.put("policyDenied", false);
        app.put("tier", "read");
        return input;
    }

    // ─────────────────────────── 事件类 · 字段与兼容性 ───────────────────────────

    @Test
    @DisplayName("Bridge 事件：新构造器带 computerUse ⇒ getter 返回它")
    void bridgeEventCarriesComputerUse() {
        BridgePermissionRequestEvent event = new BridgePermissionRequestEvent(
                SESSION, "req-1", ComputerUseRequestAccessTool.TOOL_NAME, cuToolInput(),
                "tool-1", "描述", List.of(), null, samplePayload(), 123L);

        assertThat(event.getComputerUse()).isNotNull();
        assertThat(event.getComputerUse().apps()).hasSize(1);
        assertThat(event.getComputerUse().apps().get(0).appId()).isEqualTo("chrome");
        assertThat(event.getComputerUse().requestedFlags()).containsExactly("clipboardRead");
        assertThat(event.getComputerUse().reason()).isEqualTo("要操作浏览器");
    }

    @Test
    @DisplayName("Bridge 事件：既有构造器逐字保留 ⇒ computerUse 为 null 且 JSON 省略该字段")
    void bridgeEventLegacyConstructorStillWorks() throws Exception {
        BridgePermissionRequestEvent event = new BridgePermissionRequestEvent(
                SESSION, "req-1", "Bash", JSON.createObjectNode().put("command", "ls"),
                "tool-1", "描述", List.of(), null, 123L);

        assertThat(event.getComputerUse()).isNull();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(event));
        assertThat(json.has("computerUse")).as("非 CU 请求 ⇒ 字段必须被省略").isFalse();
    }

    @Test
    @DisplayName("Bridge 事件：既有 of(...) 工厂逐字保留 ⇒ computerUse 为 null")
    void bridgeEventLegacyFactoryStillWorks() {
        BridgePermissionRequestEvent event = BridgePermissionRequestEvent.of(
                SESSION, "req-1", "Bash", JSON.createObjectNode(), "tool-1", "描述", List.of(), null);

        assertThat(event.getComputerUse()).isNull();
    }

    @Test
    @DisplayName("Channel 事件：新构造器带 computerUse ⇒ getter 返回它")
    void channelEventCarriesComputerUse() {
        ChannelPermissionRequestEvent event = new ChannelPermissionRequestEvent(
                SESSION, "req-2", ComputerUseRequestAccessTool.TOOL_NAME, "描述", "{}", samplePayload(), 456L);

        assertThat(event.getComputerUse()).isNotNull();
        assertThat(event.getComputerUse().apps().get(0).displayName()).isEqualTo("Chrome");
    }

    @Test
    @DisplayName("Channel 事件：既有构造器逐字保留 ⇒ computerUse 为 null 且 JSON 省略该字段")
    void channelEventLegacyConstructorStillWorks() throws Exception {
        ChannelPermissionRequestEvent event = new ChannelPermissionRequestEvent(
                SESSION, "req-2", "Bash", "描述", "{}", 456L);

        assertThat(event.getComputerUse()).isNull();
        JsonNode json = JSON.readTree(JSON.writeValueAsString(event));
        assertThat(json.has("computerUse")).as("非 CU 请求 ⇒ 字段必须被省略").isFalse();
    }

    @Test
    @DisplayName("Channel 事件：既有 of(...) 工厂逐字保留 ⇒ computerUse 为 null")
    void channelEventLegacyFactoryStillWorks() {
        ChannelPermissionRequestEvent event = ChannelPermissionRequestEvent.of(
                SESSION, "req-2", "Bash", "描述", "{}");

        assertThat(event.getComputerUse()).isNull();
    }

    @Test
    @DisplayName("两个事件的 computerUse 形状一致（前端一套解析器吃两条通道）")
    void bothChannelsShareTheSamePayloadShape() throws Exception {
        BridgePermissionRequestEvent bridge = new BridgePermissionRequestEvent(
                SESSION, "req-1", ComputerUseRequestAccessTool.TOOL_NAME, cuToolInput(),
                "tool-1", "描述", List.of(), null, samplePayload(), 1L);
        ChannelPermissionRequestEvent channel = new ChannelPermissionRequestEvent(
                SESSION, "req-2", ComputerUseRequestAccessTool.TOOL_NAME, "描述", "{}", samplePayload(), 2L);

        JsonNode bridgeJson = JSON.readTree(JSON.writeValueAsString(bridge)).get("computerUse");
        JsonNode channelJson = JSON.readTree(JSON.writeValueAsString(channel)).get("computerUse");

        assertThat(bridgeJson).isNotNull();
        assertThat(channelJson).isNotNull();
        java.util.List<String> bridgeFields = new java.util.ArrayList<>();
        bridgeJson.fieldNames().forEachRemaining(bridgeFields::add);
        java.util.List<String> channelFields = new java.util.ArrayList<>();
        channelJson.fieldNames().forEachRemaining(channelFields::add);
        assertThat(channelFields).containsExactlyInAnyOrderElementsOf(bridgeFields);
        assertThat(channelJson.get("apps").get(0).get("appId").asText())
                .isEqualTo(bridgeJson.get("apps").get(0).get("appId").asText());
    }

    // ─────────────────────────── 发布方 · 真的填了 ───────────────────────────

    @Test
    @DisplayName("bridge 发布方：从 displayInput 提取并填充 computerUse（CU 工具）")
    void bridgePublisherFillsComputerUse() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks callbacks = new StompBridgePermissionCallbacks(ws);

        callbacks.sendRequest(SESSION, "req-1", ComputerUseRequestAccessTool.TOOL_NAME,
                cuToolInput(), "tool-1", "描述", List.of(), null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        BridgePermissionRequestEvent event = (BridgePermissionRequestEvent) captor.getValue();
        assertThat(event.getComputerUse()).isNotNull();
        assertThat(event.getComputerUse().apps().get(0).appId()).isEqualTo("chrome");
        assertThat(event.getComputerUse().requestedFlags()).containsExactly("clipboardRead");
    }

    @Test
    @DisplayName("bridge 发布方：非 CU 工具 ⇒ computerUse 为 null（不污染普通请求）")
    void bridgePublisherLeavesNonCuRequestsAlone() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks callbacks = new StompBridgePermissionCallbacks(ws);

        callbacks.sendRequest(SESSION, "req-1", "Bash",
                JSON.createObjectNode().put("command", "ls"), "tool-1", "描述", List.of(), null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        BridgePermissionRequestEvent event = (BridgePermissionRequestEvent) captor.getValue();
        assertThat(event.getComputerUse()).isNull();
    }

    @Test
    @DisplayName("channel 发布方：直传 computerUse（事件里只有 inputPreview ⇒ 只能由发布方给）")
    void channelPublisherFillsComputerUse() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompChannelPermissionCallbacks callbacks = new StompChannelPermissionCallbacks(ws);

        callbacks.sendRequest(SESSION, "req-2", ComputerUseRequestAccessTool.TOOL_NAME,
                "描述", cuToolInput());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        ChannelPermissionRequestEvent event = (ChannelPermissionRequestEvent) captor.getValue();
        assertThat(event.getComputerUse()).isNotNull();
        assertThat(event.getComputerUse().apps().get(0).displayName()).isEqualTo("Chrome");
    }

    @Test
    @DisplayName("channel 发布方：非 CU 工具 ⇒ computerUse 为 null")
    void channelPublisherLeavesNonCuRequestsAlone() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StompChannelPermissionCallbacks callbacks = new StompChannelPermissionCallbacks(ws);

        callbacks.sendRequest(SESSION, "req-2", "Bash", "描述", JSON.createObjectNode());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        ChannelPermissionRequestEvent event = (ChannelPermissionRequestEvent) captor.getValue();
        assertThat(event.getComputerUse()).isNull();
    }
}

package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.computeruse.ComputerUseRequestAccessTool;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [刷新重放 · 三通道] 会话状态重放覆盖 <b>message / bridge / channel</b> 三条权限通道。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：权限请求本体是<b>一次性 STOMP 推送</b> —— 请求到达时
 * 用户不在该会话的订阅集合里（非当前会话 / <b>刷新页面后重连</b>），事件被静默丢弃 ⇒ 用户看不到、
 * 也没法批准/拒绝。修法 = 服务端保留「仍在等待用户」的请求快照，切会话 / 重连时经
 * {@code GET /api/v1/sessions/{id}/state} 重放。
 *
 * <p>⛔ <b>此前只重放 message 通道</b> ⇒ bridge（CCR / claude.ai 式远程弹窗）/ channel
 * （Telegram / iMessage / Discord 中继）两条通道的待审批请求在用户刷新后<b>消失</b>。
 *
 * <p>本测试锁四件事：
 * <ol>
 *   <li><b>bridge 通道</b>的待审批请求在重放返回体里<b>能被取回</b>（含 {@code computerUse}
 *       —— 漏了它，弹窗会退回 fail-loud 错误面板 / 普通 allow-deny，授权范围消失）；</li>
 *   <li><b>channel 通道</b>同（且带 channel 字段名 {@code inputPreview}）；</li>
 *   <li><b>message 通道行为不变</b>（回归断言：字段逐一对应，不因引通道快照而丢字段）；</li>
 *   <li><b>非 CU 请求不污染</b>（{@code computerUse} 恒 null ⇒ 前端走普通弹窗分支）。</li>
 * </ol>
 *
 * <p>⭐ 另锁一条语义：快照的 {@code requestId} 必须是 <b>owner requestId（tool_use id）</b> ——
 * 前端补拉路径按 message 通道归一化，用户的允许/拒绝经 {@code /permission-response} 回服务端，
 * 命中的是 prompter 按 owner requestId 登记的 pending future ⇒ 若快照改用通道级 id
 * （bridge UUID / channel 短 id），响应<b>查不到</b> ⇒ 弹窗点了没反应、工具永久挂起。
 */
class PendingPermissionSnapshotReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    // ─────────────────────────── 工具桩 ───────────────────────────

    /** 工具桩（只实现名称；权限弹窗路径不执行工具）。 */
    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public String description(JsonNode input) { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static ToolUseContext newCtx() {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    private static PermissionPromptDetails details() {
        return new PermissionPromptDetails("desc", List.of(), null, null, false);
    }

    /** CU 工具入参（含 Phase 1a 就地注入的 resolvedApps ⇒ CU 面板数据可算）。 */
    private static ObjectNode cuToolInput() {
        ObjectNode input = JSON.createObjectNode();
        input.put("reason", "要操作浏览器");
        input.put("clipboardRead", true);
        ObjectNode app = input.putArray(ComputerUseRequestAccessTool.RESOLVED_APPS_KEY).addObject();
        app.put("requestedName", "Chrome");
        app.put("appId", "chrome");
        app.put("displayName", "Chrome");
        app.put("alreadyGranted", false);
        app.put("policyDenied", false);
        app.put("tier", "read");
        return input;
    }

    /** 非 CU 工具入参。 */
    private static ObjectNode plainToolInput() {
        return JSON.createObjectNode().put("path", "/a");
    }

    // ─────────────────────────── 回调包装（消除注册竞态） ───────────────────────────

    /**
     * bridge 回调包装 · {@code onResponse} 注册完成即放行。
     *
     * <p>WHY：竞速 resolver 的注册在 {@code sendRequest} 之后 ⇒ 「出站推送已发生」不等于
     * 「resolver 已注册」⇒ 提前 resolve 会 delete-before-call 永久 miss。放行信号打在
     * {@code onResponse} 上 ⇒ 后续 resolve 必然命中。
     */
    private static final class LatchedBridge implements BridgePermissionCallbacks {
        private final StompBridgePermissionCallbacks base;
        private final CountDownLatch registered = new CountDownLatch(1);
        LatchedBridge(SimpMessagingTemplate ws) { this.base = new StompBridgePermissionCallbacks(ws); }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                JsonNode displayInput, String toolUseId, String description,
                List<PermissionUpdate> suggestions, String blockedPath) {
            base.sendRequest(sessionId, requestId, toolName, displayInput, toolUseId,
                description, suggestions, blockedPath);
        }
        @Override public Runnable onResponse(String requestId, Consumer<BridgeResponse> handler) {
            Runnable unsub = base.onResponse(requestId, handler);
            registered.countDown();
            return unsub;
        }
        @Override public void cancelRequest(String requestId) { base.cancelRequest(requestId); }
        @Override public void sendResponse(String requestId, BridgeResponse response) {
            base.sendResponse(requestId, response);
        }
        @Override public boolean resolve(String requestId, BridgeResponse response) {
            return base.resolve(requestId, response);
        }
    }

    /** channel 回调包装 · 同 {@link LatchedBridge}（注册完成即放行）。 */
    private static final class LatchedChannel implements ChannelPermissionCallbacks {
        private final StompChannelPermissionCallbacks base;
        private final CountDownLatch registered = new CountDownLatch(1);
        LatchedChannel(SimpMessagingTemplate ws) { this.base = new StompChannelPermissionCallbacks(ws); }
        @Override public String shortRequestId(String toolUseId) { return base.shortRequestId(toolUseId); }
        @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
            Runnable unsub = base.onResponse(requestId, handler);
            registered.countDown();
            return unsub;
        }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                String description, JsonNode displayInput) {
            base.sendRequest(sessionId, requestId, toolName, description, displayInput);
        }
        @Override public boolean resolve(String requestId, String behavior, String fromServer) {
            return base.resolve(requestId, behavior, fromServer);
        }
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 等到重放返回体里出现至少 {@code min} 条快照（推送/登记是异步的）。 */
    private static List<PendingPermissionSnapshot> awaitPending(WebSocketPermissionPrompter prompter,
                                                                String sessionId, int min) {
        long deadline = System.currentTimeMillis() + 5_000;
        List<PendingPermissionSnapshot> list = List.of();
        while (System.currentTimeMillis() < deadline) {
            list = prompter.listPendingSnapshots(sessionId);
            if (list.size() >= min) return list;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return list;
    }

    // ─────────────────────────── ① bridge 通道 ───────────────────────────

    @Test
    @DisplayName("① bridge 通道的待审批请求在刷新重放返回体里能被取回（含 computerUse）")
    void bridgeChannel_pendingRequest_isReplayedWithComputerUse() throws Exception {
        // WHY: 刷新后 bridge 通道的请求此前完全不可见 ⇒ 用户既看不到、也没法批准/拒绝。
        //   断言：重放返回体里能按 channel="bridge" 取回该请求，且带 computerUse（漏了它
        //   ⇒ 前端弹窗退回 fail-loud 错误面板，授权范围消失）。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        LatchedBridge bridge = new LatchedBridge(bridgeWs);
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, null);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool(ComputerUseRequestAccessTool.TOOL_NAME), cuToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-cu-1", details())));
        t.start();

        // 快照登记在 onResponse 注册之前 ⇒ latch 放行即代表快照已可重放（确定性）
        assertThat(bridge.registered.await(5, TimeUnit.SECONDS))
            .as("bridge 竞速必须启动（否则本用例无意义）")
            .isTrue();

        List<PendingPermissionSnapshot> pending = prompter.listPendingSnapshots(SESSION_ID);
        PendingPermissionSnapshot bridgeItem = pending.stream()
            .filter(s -> PendingPermissionSnapshot.CHANNEL_BRIDGE.equals(s.channel()))
            .findFirst()
            .orElse(null);

        assertThat(bridgeItem)
            .as("bridge 通道的待审批请求必须出现在刷新重放返回体里（此前只重放 message 通道）")
            .isNotNull();
        assertThat(bridgeItem.requestId())
            .as("快照 requestId 必须是 owner requestId（前端响应据此命中 pending future）")
            .isEqualTo("req-cu-1");
        assertThat(bridgeItem.channelRequestId())
            .as("bridge 通道级 requestId（随机 UUID）必须保留（响应路由到通道端点时用）")
            .isNotBlank();
        assertThat(bridgeItem.displayInput())
            .as("bridge 通道字段名 displayInput 必须带（前端 bridge 归一化读它）")
            .isNotNull();
        assertThat(bridgeItem.toolInput())
            .as("归一化入参 toolInput 必须带（前端任一 kind 的归一化都取得到）")
            .isNotNull();
        assertThat(bridgeItem.computerUse())
            .as("CU 请求 ⇒ 补拉路径必须带 computerUse（漏了 ⇒ 弹窗退回 fail-loud 错误面板）")
            .isNotNull();
        assertThat(bridgeItem.computerUse().apps()).hasSize(1);
        assertThat(bridgeItem.computerUse().apps().get(0).appId()).isEqualTo("chrome");
        assertThat(bridgeItem.computerUse().requestedFlags()).containsExactly("clipboardRead");

        // 收尾：远程表面允许 → 竞速 claim → prompt 线程返回
        assertThat(bridge.resolve(bridgeItem.channelRequestId(),
            new BridgePermissionCallbacks.BridgeResponse("allow", null, null))).isTrue();
        t.join(5_000);
        assertThat(holder.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    // ─────────────────────────── ② channel 通道 ───────────────────────────

    @Test
    @DisplayName("② channel 通道的待审批请求在刷新重放返回体里能被取回（含 computerUse）")
    void channelChannel_pendingRequest_isReplayedWithComputerUse() throws Exception {
        // WHY: 同 ① —— channel（Telegram / iMessage / Discord 中继）通道的待审批请求此前
        //   在用户刷新后消失。断言：重放返回体里能按 channel="channel" 取回，且带
        //   inputPreview（channel 字段名）与 computerUse。
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        LatchedChannel channel = new LatchedChannel(channelWs);
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(null, channel);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool(ComputerUseRequestAccessTool.TOOL_NAME), cuToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-cu-2", details())));
        t.start();

        assertThat(channel.registered.await(5, TimeUnit.SECONDS))
            .as("channel 竞速必须启动（否则本用例无意义）")
            .isTrue();

        List<PendingPermissionSnapshot> pending = prompter.listPendingSnapshots(SESSION_ID);
        PendingPermissionSnapshot channelItem = pending.stream()
            .filter(s -> PendingPermissionSnapshot.CHANNEL_CHANNEL.equals(s.channel()))
            .findFirst()
            .orElse(null);

        assertThat(channelItem)
            .as("channel 通道的待审批请求必须出现在刷新重放返回体里（此前只重放 message 通道）")
            .isNotNull();
        assertThat(channelItem.requestId())
            .as("快照 requestId 必须是 owner requestId（前端响应据此命中 pending future）")
            .isEqualTo("req-cu-2");
        assertThat(channelItem.channelRequestId())
            .as("channel 通道级 requestId（5 字母短 id）必须保留")
            .isNotBlank();
        assertThat(channelItem.inputPreview())
            .as("channel 通道字段名 inputPreview 必须带（与出站事件同形）")
            .isNotNull();
        assertThat(channelItem.toolInput())
            .as("归一化入参 toolInput 必须带（channel 通道只推截断预览；快照带全量 ⇒ 弹窗可渲染）")
            .isNotNull();
        assertThat(channelItem.computerUse())
            .as("CU 请求 ⇒ 补拉路径必须带 computerUse")
            .isNotNull();
        assertThat(channelItem.computerUse().apps()).hasSize(1);
        assertThat(channelItem.computerUse().requestedFlags()).containsExactly("clipboardRead");

        // 收尾：通道表面回复 allow → 竞速 claim
        assertThat(channel.resolve(channelItem.channelRequestId(), "allow", "plugin:telegram:tg")).isTrue();
        t.join(5_000);
        assertThat(holder.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    // ─────────────────────────── ③ message 通道回归 ───────────────────────────

    @Test
    @DisplayName("③ message 通道行为不变（回归断言：字段逐一对应，不因引通道快照而丢字段）")
    void messageChannel_replayShapeUnchanged() throws Exception {
        // WHY: 本批把重放返回体的元素类型从 MessagePermissionRequestEvent 换成统一形状
        //   ⇒ 必须证明 message 通道的字段一个不少（前端按 requestId/toolName/toolInput/
        //   computerUse/suggestions/reason 渲染弹窗；缺一个就退化）。
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool("Read"), plainToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-msg-1", details())));
        t.start();

        List<PendingPermissionSnapshot> pending = awaitPending(prompter, SESSION_ID, 1);
        assertThat(pending).hasSize(1);
        PendingPermissionSnapshot m = pending.get(0);

        assertThat(m.channel()).isEqualTo(PendingPermissionSnapshot.CHANNEL_MESSAGE);
        assertThat(m.type()).isEqualTo(PendingPermissionSnapshot.TYPE_PERMISSION_REQUEST);
        assertThat(m.sessionId()).isEqualTo(SESSION_ID);
        assertThat(m.requestId()).isEqualTo("req-msg-1");
        assertThat(m.toolUseId()).isEqualTo("req-msg-1");
        assertThat(m.toolName()).isEqualTo("Read");
        assertThat(m.toolInput().path("path").asText()).isEqualTo("/a");
        assertThat(m.description()).isEqualTo("desc");
        assertThat(m.suggestions()).isEmpty();
        assertThat(m.timestampMs()).isPositive();
        assertThat(m.channelRequestId()).as("message 通道无通道级 requestId").isNull();

        // 用户允许 → 快照清除（不残留幽灵弹窗）
        prompter.onResponse("req-msg-1", "allow");
        t.join(5_000);
        assertThat(holder.get()).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompter.listPendingSnapshots(SESSION_ID))
            .as("用户已响应 ⇒ 重放里不得再挂着该请求（幽灵弹窗）")
            .isEmpty();
    }

    // ─────────────────────────── ④ 非 CU 请求不污染 ───────────────────────────

    @Test
    @DisplayName("④ 非 CU 请求 ⇒ 三条通道的快照都不带 computerUse（不污染 CU 面板分支）")
    void nonCuRequest_neverCarriesComputerUse() throws Exception {
        // WHY: computerUse 非 null ⇒ 前端走「桌面控制审批面板」分支；非 CU 请求若被误填
        //   ⇒ 普通权限弹窗被替换成桌面控制面板（用户看到无关的应用清单）。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        LatchedBridge bridge = new LatchedBridge(bridgeWs);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        LatchedChannel channel = new LatchedChannel(channelWs);
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, channel);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool("Read"), plainToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-plain-1", details())));
        t.start();

        assertThat(bridge.registered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(channel.registered.await(5, TimeUnit.SECONDS)).isTrue();

        List<PendingPermissionSnapshot> pending = prompter.listPendingSnapshots(SESSION_ID);
        assertThat(pending)
            .as("三条通道的快照都应在重放返回体里")
            .hasSize(3);
        for (PendingPermissionSnapshot s : pending) {
            assertThat(s.computerUse())
                .as("非 CU 请求（tool=%s · channel=%s）不得带 computerUse", s.toolName(), s.channel())
                .isNull();
        }

        bridge.resolve(pending.stream()
            .filter(s -> PendingPermissionSnapshot.CHANNEL_BRIDGE.equals(s.channel()))
            .findFirst().orElseThrow().channelRequestId(),
            new BridgePermissionCallbacks.BridgeResponse("deny", null, null));
        t.join(5_000);
    }

    // ─────────────────────────── ⑤ 清理：响应后不留残留 ───────────────────────────

    @Test
    @DisplayName("⑤ 用户响应后 ⇒ 三条通道的快照一并清除（重放里不留幽灵请求）")
    void afterUserResponse_allChannelSnapshotsCleared() throws Exception {
        // WHY: 快照若不随响应清除 ⇒ 用户已批准，刷新页面却又弹一次（且点了没反应）。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        LatchedBridge bridge = new LatchedBridge(bridgeWs);
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, null);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool("Read"), plainToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-clear-1", details())));
        t.start();

        assertThat(bridge.registered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(prompter.listPendingSnapshots(SESSION_ID))
            .as("响应前：message + bridge 两条快照")
            .hasSize(2);

        // 前端补拉路径按 message 通道归一化 ⇒ 响应经 /permission-response 回服务端
        prompter.onResponse("req-clear-1", "allow");
        t.join(5_000);
        assertThat(holder.get()).isInstanceOf(PermissionResult.Allow.class);
        assertThat(prompter.listPendingSnapshots(SESSION_ID))
            .as("用户已响应 ⇒ 三条通道的快照都必须清空")
            .isEmpty();
    }

    // ─────────────────────────── ⑥ 重放返回体的 JSON 形状 ───────────────────────────

    @Test
    @DisplayName("⑥ 重放返回体的 JSON 形状与前端 PermissionEventPayload 对齐（三通道各一份）")
    void replayBody_jsonShape_matchesFrontendContract() throws Exception {
        // WHY: 前端三条通道共用一个归一化函数（front/src/hooks/useChatSocket.ts 的
        //   toPermissionItem），读的是 PermissionEventPayload 的字段名
        //   （toolInput / displayInput / inputPreview / computerUse …）⇒ 快照的线上形状
        //   必须逐字段落在前端契约里，否则「编译全绿但线上弹窗空白」。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        LatchedBridge bridge = new LatchedBridge(bridgeWs);
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        LatchedChannel channel = new LatchedChannel(channelWs);
        WebSocketPermissionPrompter prompter =
            new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, channel);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new StubTool(ComputerUseRequestAccessTool.TOOL_NAME), cuToolInput(),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-shape-1", details())));
        t.start();

        assertThat(bridge.registered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(channel.registered.await(5, TimeUnit.SECONDS)).isTrue();

        List<PendingPermissionSnapshot> pending = prompter.listPendingSnapshots(SESSION_ID);
        for (PendingPermissionSnapshot s : pending) {
            JsonNode json = JSON.readTree(JSON.writeValueAsString(s));
            System.out.println("[REPLAY-JSON][" + s.channel() + "] " + json.toString());
            // 前端 PermissionEventPayload 的公共字段（除 computerUse 外均可空）
            assertThat(json.has("requestId")).isTrue();
            assertThat(json.has("toolName")).isTrue();
            assertThat(json.has("toolInput")).as("归一化入参必须在线上形状里").isTrue();
            assertThat(json.has("computerUse")).as("CU 请求 ⇒ computerUse 必须在线上形状里").isTrue();
            assertThat(json.get("computerUse").isNull()).isFalse();
            assertThat(json.has("timestampMs")).isTrue();
        }

        // bridge 通道线上形状 ⇒ 同时带 displayInput（bridge 字段名）
        JsonNode bridgeJson = JSON.readTree(JSON.writeValueAsString(pending.stream()
            .filter(s -> PendingPermissionSnapshot.CHANNEL_BRIDGE.equals(s.channel()))
            .findFirst().orElseThrow()));
        assertThat(bridgeJson.has("displayInput")).as("bridge 字段名 displayInput 必须在").isTrue();
        // channel 通道线上形状 ⇒ 同时带 inputPreview（channel 字段名）
        JsonNode channelJson = JSON.readTree(JSON.writeValueAsString(pending.stream()
            .filter(s -> PendingPermissionSnapshot.CHANNEL_CHANNEL.equals(s.channel()))
            .findFirst().orElseThrow()));
        assertThat(channelJson.has("inputPreview")).as("channel 字段名 inputPreview 必须在").isTrue();

        bridge.resolve(bridgeJson.get("channelRequestId").asText(),
            new BridgePermissionCallbacks.BridgeResponse("deny", null, null));
        t.join(5_000);
    }
}

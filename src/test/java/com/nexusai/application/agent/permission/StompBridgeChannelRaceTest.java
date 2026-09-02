package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.BridgePermissionRequestEvent;
import com.nexusai.eventbus.ws.ChannelPermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * [canUseTool v4] bridge/channel 竞速生产参与测试 · 对齐 CC
 * interactiveHandler.ts:244-298（bridge 竞速）+ :316-407（channel 中继）+
 * bridgePermissionCallbacks.ts + services/mcp/channelPermissions.ts。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: v3 对抗复验判 canUseTool PARTIAL — 残留缺口 ①：
 * {@link BridgePermissionCallbacks} / {@link ChannelPermissionCallbacks} 是接口，生产无
 * {@code @Component} 实现 → {@code @Autowired(required=false)} 注入 null →
 * {@code startBridgeRace} / {@code startChannelRace} 直接 return，CCR 远程弹窗 / 通道中继
 * 竞速在生产永不参与。用户明确要求"限制就开放"，不能以"无实现"为借口。
 *
 * <p>本测试验证生产实现（STOMP/WebSocket 通道，对齐 Java 既有 STOMP 弹窗链路）：
 * <ol>
 *   <li><b>bridge 竞速参与</b> — {@code sendRequest} STOMP 出站推送 → 远程表面响应 →
 *       STOMP inbound {@code resolve} → claim + 竞速 Allow（不阻塞用户）。</li>
 *   <li><b>channel 竞速参与</b> — {@code shortRequestId} + STOMP 出站 → inbound
 *       {@code resolve(behavior, fromServer)} → 竞速 Allow。</li>
 *   <li><b>resolve 幂等</b> — 未知 requestId 返回 false（对齐 channelPermissions.ts:231）。</li>
 *   <li><b>shortRequestId</b> — FNV-1a 5 字母（a-z 去 'l'）+ 确定性（channelPermissions.ts:140-152）。</li>
 * </ol>
 *
 * @see StompBridgePermissionCallbacks
 * @see StompChannelPermissionCallbacks
 * @see WebSocketPermissionPrompter
 * @since canUseTool v4 修复
 */
class StompBridgeChannelRaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";
    /** CC ID_ALPHABET = a-z minus 'l'（channelPermissions.ts:78）。 */
    private static final String ID_ALPHABET = "abcdefghijkmnopqrstuvwxyz";

    /** 可配置 input 感知描述的工具桩（对齐 CC Tool.ts:386-393 description(input, options)）。 */
    private static final class DescribedTool implements Tool {
        private final String name;
        private final String desc;
        DescribedTool(String name, String desc) { this.name = name; this.desc = desc; }
        @Override public String name() { return name; }
        @Override public String description() { return desc; }
        @Override public String description(JsonNode input) {
            return desc + ":" + input.path("path").asText("?");
        }
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

    // ─────────────────── 缺口① : bridge 竞速生产参与 ───────────────────

    @Test
    @DisplayName("bridge 竞速生产参与: STOMP 出站推送 + inbound resolve → Allow 不阻塞用户")
    void bridgeRace_resolvesViaStompInbound_withoutUserWait() throws Exception {
        // WHY: v3 对抗复验 gap① — bridge/channel 生产无 @Component 实现 → startBridgeRace 直接
        //      return，CCR 远程弹窗竞速永不参与。用户明确要求"限制就开放"。生产实现必须：
        //      sendRequest STOMP 推送（远程表面收到）→ 远程响应 → STOMP inbound resolve →
        //      claim + 竞速 Allow。若实现缺失/未接线，prompt 不会因 bridge resolve 而返回（RED）。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks bridge = new StompBridgePermissionCallbacks(bridgeWs);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        prompter.wireRacersForTesting(bridge, null);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/a"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-br-1",
            details())));
        t.start();

        // 捕获出站 bridge 请求事件 → 取 bridgeRequestId（对齐 CC :245-253 sendRequest）
        // timeout(3000) — 竞速 racer 在 prompt 线程跑，test 线程必须等 sendRequest 已推送
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(bridgeWs, timeout(3000)).convertAndSend(anyString(), captor.capture());
        BridgePermissionRequestEvent req = (BridgePermissionRequestEvent) captor.getValue();
        assertThat(req.getToolName()).isEqualTo("Read");
        assertThat(req.getSessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(req.getToolUseId()).isEqualTo("req-br-1");

        // 模拟远程表面响应（STOMP inbound resolve · 对齐 CC bridgeApi sendResponse）
        boolean resolved = bridge.resolve(req.getRequestId(),
            new BridgePermissionCallbacks.BridgeResponse("allow", null, null));
        assertThat(resolved)
            .as("bridge resolve 必须命中 pending resolver（对齐 channelPermissions.ts:231）")
            .isTrue();
        t.join(3_000);

        assertThat(holder.get())
            .as("bridge 远程 allow → 竞速 claim + Allow（CC interactiveHandler.ts:280 buildAllow）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow ->
                assertThat(allow.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                        o -> assertThat(o.reason()).isEqualTo("bridge_allowed")));
    }

    @Test
    @DisplayName("bridge resolve 未知 requestId → false（对齐 channelPermissions.ts:231 delete-before-call）")
    void bridgeResolve_unknownRequestId_returnsFalse() {
        // WHY: resolve 必须 delete-before-call + 未知 id 返回 false — 若恒 true，重复事件 /
        //      伪造 id 会误触发（CC channelPermissions.ts:228-238 幂等契约）。
        StompBridgePermissionCallbacks bridge =
            new StompBridgePermissionCallbacks(mock(SimpMessagingTemplate.class));
        assertThat(bridge.resolve("no-such-id",
                new BridgePermissionCallbacks.BridgeResponse("allow", null, null)))
            .as("未知 bridge requestId → resolve 必须返回 false")
            .isFalse();
    }

    // ─────────────────── 缺口① : channel 竞速生产参与 ───────────────────

    @Test
    @DisplayName("channel 竞速生产参与: shortRequestId + STOMP 出站 + inbound resolve → Allow")
    void channelRace_resolvesViaStompInbound_withoutUserWait() throws Exception {
        // WHY: v3 对抗复验 gap① 同 bridge — channelCallbacks 生产无 @Component 实现 →
        //   startChannelRace 直接 return。生产实现必须：sendRequest STOMP 推送 → 通道表面
        //      回复（"yes tbxkq"）→ 服务器 structured event → inbound resolve(behavior, fromServer)
        //      → claim + 竞速 Allow（CC interactiveHandler.ts:376-396）。
        SimpMessagingTemplate channelWs = mock(SimpMessagingTemplate.class);
        // 注册完成信号: prompt 后台线程在 sendRequest 之后才调 onResponse 注册 pending —
        //   verify(convertAndSend) 通过不代表注册完成, resolve 提前会 miss (delete-before-call
        //   语义, 首次 miss 永久丢失) → 包装 onResponse 等注册 latch, 消除竞态
        CountDownLatch channelRegistered = new CountDownLatch(1);
        StompChannelPermissionCallbacks baseChannel = new StompChannelPermissionCallbacks(channelWs);
        ChannelPermissionCallbacks channel = new ChannelPermissionCallbacks() {
            @Override
            public String shortRequestId(String toolUseId) {
                return baseChannel.shortRequestId(toolUseId);
            }
            @Override
            public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
                Runnable unsub = baseChannel.onResponse(requestId, handler);
                channelRegistered.countDown();
                return unsub;
            }
            @Override
            public void sendRequest(String sessionId, String requestId, String toolName,
                                    String description, JsonNode displayInput) {
                baseChannel.sendRequest(sessionId, requestId, toolName, description, displayInput);
            }
            @Override
            public boolean resolve(String requestId, String behavior, String fromServer) {
                return baseChannel.resolve(requestId, behavior, fromServer);
            }
        };
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        prompter.wireRacersForTesting(null, channel);

        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new DescribedTool("Bash", "bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-ch-1",
            details())));
        t.start();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(channelWs, timeout(3000)).convertAndSend(anyString(), captor.capture());
        ChannelPermissionRequestEvent req = (ChannelPermissionRequestEvent) captor.getValue();
        assertThat(req.getToolName()).isEqualTo("Bash");
        assertThat(req.getSessionId()).isEqualTo(SESSION_ID.toString());
        // 出站 payload 的 request_id 必须 = shortRequestId(toolUseID)（CC interactiveHandler.ts:321）
        assertThat(req.getRequestId())
            .as("channel request_id 必须是 shortRequestId(toolUseID) 的产物")
            .isEqualTo(channel.shortRequestId("req-ch-1"));
        // 等 prompt 后台线程完成 onResponse 注册（verify(convertAndSend) 只证出站推送完成,
        //   不证 pending 注册就绪 — 注册在 sendRequest 之后, resolve 提前会 delete-before-call miss)
        assertThat(channelRegistered.await(3, TimeUnit.SECONDS))
            .as("channel onResponse 注册必须先于 resolve（防竞态）")
            .isTrue();

        // 模拟通道 server 解析用户 "yes tbxkq" 后发 structured event（inbound resolve）
        boolean resolved = channel.resolve(req.getRequestId(), "allow", "plugin:telegram:tg");
        assertThat(resolved).isTrue();
        t.join(3_000);

        assertThat(holder.get())
            .as("channel 远程 allow → 竞速 claim + Allow（CC interactiveHandler.ts:376-384）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow ->
                assertThat(allow.reason())
                    .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                        o -> assertThat(o.reason()).isEqualTo("channel_allowed")));
    }

    @Test
    @DisplayName("channel resolve 未知 requestId → false + resolve 后重入返回 false")
    void channelResolve_unknownOrAlreadyResolved_returnsFalse() {
        // WHY: resolve delete-before-call（CC channelPermissions.ts:232-236）— 第二次 resolve
        //      同一 id 必须 false（重复事件/网络 dup 被忽略）。
        StompChannelPermissionCallbacks channel =
            new StompChannelPermissionCallbacks(mock(SimpMessagingTemplate.class));
        assertThat(channel.resolve("no-such-id", "allow", "plugin:telegram:tg"))
            .as("未知 channel requestId → resolve 必须返回 false")
            .isFalse();
        channel.onResponse("tbxkq", r -> { });
        assertThat(channel.resolve("tbxkq", "allow", "plugin:telegram:tg"))
            .as("首个 resolve 命中 → true")
            .isTrue();
        assertThat(channel.resolve("tbxkq", "deny", "plugin:telegram:tg"))
            .as("resolve 后重入 → false（delete-before-call 幂等）")
            .isFalse();
    }

    // ─────────────────── shortRequestId 正确性 ───────────────────

    @Test
    @DisplayName("shortRequestId: FNV-1a 5 字母 (a-z 去 'l') + 确定性")
    void shortRequestId_isDeterministicFiveLettersFromAlphabet() {
        // WHY: CC channelPermissions.ts:140-152 — 5 letters from 25-char alphabet（a-z minus 'l'，
        //      looks like 1/I）。letters-only so phone users don't switch keyboard modes。
        //      确定性 + 字母表约束是通道 server 解析回复（PERMISSION_REPLY_RE）的前置契约。
        StompChannelPermissionCallbacks channel =
            new StompChannelPermissionCallbacks(mock(SimpMessagingTemplate.class));
        String id1 = channel.shortRequestId("toolu_abcd1234");
        String id2 = channel.shortRequestId("toolu_abcd1234");
        assertThat(id1).isEqualTo(id2).hasSize(5);
        for (int i = 0; i < id1.length(); i++) {
            assertThat(ID_ALPHABET)
                .as("channel ID 每字符必须来自 a-z 去 'l' 的 25 字母表（CC :78）")
                .contains(String.valueOf(id1.charAt(i)));
        }
        // 不同 toolUseID → 不同 ID（hash 而非 slice）
        assertThat(channel.shortRequestId("toolu_efgh5678"))
            .as("不同 toolUseID 产生不同短 ID")
            .isNotEqualTo(id1);
    }

    // ─────────────────── bridge updatedInput 语义（CC :280） ───────────────────

    @Test
    @DisplayName("bridge allow 携带 updatedInput → Allow.updatedInput 用 bridge 的（CC :280 ?? displayInput）")
    void bridgeAllow_usesUpdatedInputFromResponse() throws Exception {
        // WHY: CC interactiveHandler.ts:280 — bridge allow 用 response.updatedInput ?? displayInput。
        //      若 Java 固定用原始 input（忽略 bridge updatedInput），plan-edit 等专用渲染器的
        //      远程修改会丢失 → 决策与 CC 语义不符。
        SimpMessagingTemplate bridgeWs = mock(SimpMessagingTemplate.class);
        StompBridgePermissionCallbacks bridge = new StompBridgePermissionCallbacks(bridgeWs);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(mock(SimpMessagingTemplate.class), 5_000);
        prompter.wireRacersForTesting(bridge, null);

        JsonNode updated = JSON.createObjectNode().put("path", "/remote-modified");
        AtomicReference<PermissionResult> holder = new AtomicReference<>();
        Thread t = new Thread(() -> holder.set(prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/a"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-br-2",
            details())));
        t.start();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(bridgeWs, timeout(3000)).convertAndSend(anyString(), captor.capture());
        BridgePermissionRequestEvent req = (BridgePermissionRequestEvent) captor.getValue();
        bridge.resolve(req.getRequestId(),
            new BridgePermissionCallbacks.BridgeResponse("allow", null, updated));
        t.join(3_000);

        assertThat(holder.get())
            .as("bridge allow 必须用 response.updatedInput ?? displayInput（CC :280）")
            .isInstanceOfSatisfying(PermissionResult.Allow.class, allow ->
                assertThat(allow.updatedInput().path("path").asText()).isEqualTo("/remote-modified"));
    }
}

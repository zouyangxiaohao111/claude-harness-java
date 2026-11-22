package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.domain.session.SessionModeService;

/**
 * Channel 权限竞速的三条边界（[F2][F3][F4]）。
 *
 * <p>三条都是「竞速通道抢了本该由桌面负责的事」：
 * <ol>
 *   <li><b>[F2]</b> AskUserQuestion 不应进 channel 中继 —— 选择题要渲染卡片，渠道侧只有
 *       "yes/no 短 id" 一种回执；推过去等于让用户对着一道看不到的题回 yes；</li>
 *   <li><b>[F3]</b> channel 出站失败不该判 Deny —— 桌面弹窗还开着，人可能正要批准；</li>
 *   <li><b>[F4]</b> 桌面先答后，channel 侧必须同步收敛（清掉待回执），且 deny 也要通知前端；
 *       否则稍后一条 IM "yes" 会命中一个已完成请求（用户以为批了，实际没有）。</li>
 * </ol>
 */
@DisplayName("[F2/F3/F4] channel 竞速边界")
class ChannelRaceGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_ID = "a0000000000f2001";
    private static final String SESSION = "sess-f2f3f4-r01";
    private static final String SHORT_ID = "abcde";

    /** 可控 channel 回调（真实现依赖 STOMP，本测试只关心竞速行为）。 */
    private static final class StubCallbacks implements ChannelPermissionCallbacks {
        final Map<String, Consumer<ChannelResponse>> pending = new ConcurrentHashMap<>();
        final List<String> sentTools = new CopyOnWriteArrayList<>();
        volatile boolean failSend = false;

        @Override public String shortRequestId(String toolUseId) { return SHORT_ID; }
        @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
            pending.put(requestId.toLowerCase(), handler);
            return () -> pending.remove(requestId.toLowerCase());
        }
        @Override public void sendRequest(String sessionId, String requestId, String toolName,
                                         String description, JsonNode displayInput) {
            if (failSend) {
                throw new RuntimeException("stub channel push failed");
            }
            sentTools.add(toolName);
        }
        @Override public boolean resolve(String requestId, String behavior, String fromServer) {
            Consumer<ChannelResponse> resolver = pending.remove(requestId.toLowerCase());
            if (resolver == null) {
                return false;
            }
            resolver.accept(new ChannelResponse(behavior, fromServer));
            return true;
        }
    }

    private static final class StubTool implements Tool {
        private final String name;
        private final boolean requiresInteraction;
        StubTool(String name, boolean requiresInteraction) {
            this.name = name;
            this.requiresInteraction = requiresInteraction;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public boolean requiresUserInteraction() { return requiresInteraction; }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ToolUseContext ctx() {
        com.nexusai.common.SessionProjectRoot.setForSession(SESSION,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
        return ToolUseContext.of(AGENT_ID, SESSION, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null),
            PermissionMode.DEFAULT);
    }

    /** 组装 prompter：channel 回调 + 两道设置门 + 会话绑定判据（默认：两门开、已绑定）。 */
    private static WebSocketPermissionPrompter prompter(SimpMessagingTemplate ws, StubCallbacks cb)
            throws Exception {
        return prompter(ws, cb, true, true, true);
    }

    private static WebSocketPermissionPrompter prompter(SimpMessagingTemplate ws, StubCallbacks cb,
                                                      boolean channelsGate, boolean relayGate,
                                                      boolean sessionBound) throws Exception {
        WebSocketPermissionPrompter p = new WebSocketPermissionPrompter(ws, 100);
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("channelCallbacks");
        f.setAccessible(true);
        f.set(p, cb);

        SettingsService settings = mock(SettingsService.class);
        when(settings.readDbChannelsEnabled()).thenReturn(channelsGate);
        when(settings.readDbChannelsPermissionRelayEnabled()).thenReturn(relayGate);
        Field sf = WebSocketPermissionPrompter.class.getDeclaredField("settingsService");
        sf.setAccessible(true);
        sf.set(p, settings);

        SessionModeService sessionMode = mock(SessionModeService.class);
        when(sessionMode.hasAnyChannel(anyString())).thenReturn(sessionBound);
        Field mf = WebSocketPermissionPrompter.class.getDeclaredField("sessionModeService");
        mf.setAccessible(true);
        mf.set(p, sessionMode);
        return p;
    }

    /** 在窗口期内等到 channel 被调用（true）或确认没被调用（false）。 */
    private static boolean racedWithin(StubCallbacks cb, long windowMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            if (!cb.sentTools.isEmpty()) {
                return true;
            }
            Thread.sleep(10);
        }
        return !cb.sentTools.isEmpty();
    }

    private static void awaitPending(WebSocketPermissionPrompter prompter, String requestId)
            throws Exception {
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        f.setAccessible(true);
        Map<String, ?> pending = (Map<String, ?>) f.get(prompter);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (pending.containsKey(requestId)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("pending 未登记: " + requestId);
    }


    /** 等 channel 侧收到 sendRequest（pending 登记早于竞速启动，不能立即断言）。 */
    private static void awaitSent(StubCallbacks cb, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cb.sentTools.size() >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("channel sendRequest 未在 " + timeoutMs + "ms 内发生: " + cb.sentTools);
    }

    private static Thread promptAsync(WebSocketPermissionPrompter prompter, Tool tool,
                                      AtomicReference<PermissionResult> out, String requestId) {
        return promptAsync(prompter, tool, out, requestId,
            new PermissionPromptDetails("desc", List.of(), null));
    }

    private static Thread promptAsync(WebSocketPermissionPrompter prompter, Tool tool,
                                      AtomicReference<PermissionResult> out, String requestId,
                                      PermissionPromptDetails details) {
        Thread t = new Thread(() -> {
            try {
                out.set(prompter.prompt(tool, JSON.createObjectNode(),
                    new PermissionDecisionReason.Other("test"), ctx(), requestId, details));
            } catch (Throwable th) {
                out.set(new PermissionResult.Deny("prompt threw: " + th, 
                    new PermissionDecisionReason.Other("prompt_threw"), requestId));
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ═════════════════════════ [F2] Ask 不进中继 ═════════════════════════

    @Test
    @DisplayName("[F2] 需要用户交互的工具（Ask 类）不产生 channel 卡")
    void askLikeToolIsNotRacedOverChannel() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-f2-ask";
        promptAsync(prompter, new StubTool("AskUserQuestion", true), out, requestId);
        // 留出竞速启动窗口（正常工具在这段时间内必发；Ask 必须仍为空）
        Thread.sleep(800);

        assertThat(cb.sentTools)
            .as("Ask 类工具不得进入 channel 中继（选择题无法用 yes/no 回执表达）")
            .isEmpty();

        prompter.onResponse(requestId, "allow");
        Thread.sleep(50);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("[F2] 普通工具仍走 channel 竞速（不能误关整条通道）")
    void normalToolStillRacesOverChannel() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-f2-bash";
        promptAsync(prompter, new StubTool("Bash", false), out, requestId);
        awaitSent(cb, 1, 5_000);

        assertThat(cb.sentTools).hasSize(1);
        assertThat(cb.sentTools.get(0)).isEqualTo("Bash");

        prompter.onResponse(requestId, "allow");
        Thread.sleep(50);
    }

    // ═════════════════════════ [F3] 发送失败不判 Deny ═════════════════════════

    @Test
    @DisplayName("[F3] channel 出站失败 ⇒ 桌面 future 仍挂起（不 Deny），桌面回答后正常 Allow")
    void channelSendFailureDoesNotDenyDesktopFuture() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        cb.failSend = true;
        WebSocketPermissionPrompter prompter = prompter(ws, cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-f3-send-fail";
        Thread t = promptAsync(prompter, new StubTool("Bash", false), out, requestId);

        // 给失败分支留时间：旧实现会在此瞬间 complete deny（prompt 直接返回）
        Thread.sleep(400);
        assertThat(t.isAlive())
            .as("channel 发送失败 = 该路未参与竞速；桌面仍在等，不能被 deny")
            .isTrue();
        assertThat(out.get()).isNull();

        prompter.onResponse(requestId, "allow");
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
    }

    // ═════════════════════════ [F4] 两侧收敛 ═════════════════════════

    @Test
    @DisplayName("[F4] channel DENY 也要通知前端关闭弹窗（与 ALLOW 一致）")
    void channelDenyNotifiesResolved() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-f4-deny";
        Thread t = promptAsync(prompter, new StubTool("Bash", false), out, requestId);
        awaitPending(prompter, requestId);

        assertThat(cb.resolve(SHORT_ID, "deny", "plugin:telegram:tg")).isTrue();
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Deny.class);

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(ws, Mockito.atLeastOnce())
            .convertAndSend(Mockito.eq("/topic/sessions/" + SESSION + "/permission-resolved"),
                sent.capture());
        assertThat(sent.getAllValues())
            .as("deny 路径也必须推 permission-resolved（否则桌面弹窗挂在已结束的请求上）")
            .anyMatch(v -> v instanceof Map<?, ?> m && "channel".equals(m.get("source")));
    }

    @Test
    @DisplayName("[F4] 桌面先答 ⇒ channel 待回执被清掉，迟到的 IM 回执不再命中")
    void desktopAnswerClearsChannelPending() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "req-f4-desktop-first";
        Thread t = promptAsync(prompter, new StubTool("Bash", false), out, requestId);
        awaitSent(cb, 1, 5_000);
        assertThat(cb.pending).as("前置：channel 待回执已登记").containsKey(SHORT_ID);

        prompter.onResponse(requestId, "allow");
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);

        assertThat(cb.pending)
            .as("桌面已裁决 ⇒ channel 待回执必须清掉（否则 IM 回执会命中一个已完成请求）")
            .doesNotContainKey(SHORT_ID);
        assertThat(cb.resolve(SHORT_ID, "allow", "plugin:telegram:tg"))
            .as("迟到回执必须返回 false（显示为已过期），不能报成功")
            .isFalse();
    }

    // ═════════════════ [S1/S2] 两门 × 会话绑定矩阵 ═════════════════

    @Test
    @DisplayName("[S1/S2] 两个设置门 × 会话绑定：只有全开且本会话已绑渠道才真的推送（与无人值守无关）")
    void relayMatrix() throws Exception {
        boolean[] bools = {true, false};
        for (boolean channelsGate : bools) {
            for (boolean relayGate : bools) {
                for (boolean bound : bools) {
                    SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
                    StubCallbacks cb = new StubCallbacks();
                    WebSocketPermissionPrompter prompter =
                        prompter(ws, cb, channelsGate, relayGate, bound);

                    AtomicReference<PermissionResult> out = new AtomicReference<>();
                    String requestId = "m-" + channelsGate + "-" + relayGate + "-" + bound;
                    promptAsync(prompter, new StubTool("Bash", false), out, requestId);

                    boolean raced = racedWithin(cb, 500);
                    boolean expected = channelsGate && relayGate && bound;
                    assertThat(raced)
                        .as("channelsGate=%s relayGate=%s sessionBound=%s ⇒ 推送=%s",
                            channelsGate, relayGate, bound, expected)
                        .isEqualTo(expected);
                    if (expected) {
                        assertThat(cb.sentTools).hasSize(1);
                    }

                    // 释放挂起的 prompt（未推送的格子 / 推送后仍等回执的格子）
                    prompter.onResponse(requestId, "allow");
                }
            }
        }
    }

    @Test
    @DisplayName("[S2] Ask 即使在两门全开且已绑渠道时也不推送（requiresUserInteraction 排除）")
    void askNeverRelayedEvenWhenAllGatesOpen() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb, true, true, true);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "m-ask-all-open";
        promptAsync(prompter, new StubTool("AskUserQuestion", true), out, requestId);

        assertThat(racedWithin(cb, 500)).as("Ask 不得进中继").isFalse();
        prompter.onResponse(requestId, "allow");
    }

    // ═════════════════ [S3] 竞速失败不收敛（按 CC） ═════════════════

    @Test
    @DisplayName("[S3] hook racer 抛异常 ⇒ 桌面 future 仍挂起（不判 Deny），用户答后正常 Allow")
    void hookRacerFailureDoesNotDeny() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        StubCallbacks cb = new StubCallbacks();
        WebSocketPermissionPrompter prompter = prompter(ws, cb, true, true, true);

        com.nexusai.application.agent.permission.hook.HookRegistry hooks =
            mock(com.nexusai.application.agent.permission.hook.HookRegistry.class);
        when(hooks.executeEvent(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("hook boom"));
        Field hf = WebSocketPermissionPrompter.class.getDeclaredField("hookRegistry");
        hf.setAccessible(true);
        hf.set(prompter, hooks);

        AtomicReference<PermissionResult> out = new AtomicReference<>();
        String requestId = "s3-hook-fail";
        Thread t = promptAsync(prompter, new StubTool("Bash", false), out, requestId,
            new PermissionPromptDetails("desc", List.of(), null, null, true));
        awaitPending(prompter, requestId);
        Thread.sleep(400);

        assertThat(t.isAlive())
            .as("CC 的 hook racer 是裸 await（无 catch→deny）：失败只让那一路退出，请求仍等用户")
            .isTrue();
        assertThat(out.get()).isNull();

        prompter.onResponse(requestId, "allow");
        t.join(3_000);
        assertThat(out.get()).isInstanceOf(PermissionResult.Allow.class);
    }
}

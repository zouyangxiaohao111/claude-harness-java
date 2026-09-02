package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [canUseTool v3] 交互竞速模型测试 · 对齐 CC interactiveHandler.ts:57-531 +
 * PermissionContext.ts:75-94（createResolveOnce.claim 原子守卫）+ useCanUseTool.tsx:56-60
 * （description）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: v2 对抗复验判 canUseTool PARTIAL，残留缺口：
 * <ol>
 *   <li><b>交互竞速模型</b> — Java 同步阻塞 future.get(30s) 时，hook 自动化决策
 *       不会提前 resolve。CC 是 queue + 多路竞速（hook/bridge/channel；classifier 竞速
 *       已随 O18 删除——CC 外部构建恒禁用）+ resolveOnce 原子守卫：首个 racer claim 即
 *       获胜，其余忽略，自动化决策无需等用户满 30s。</li>
 *   <li><b>description</b> — tool.description(input) 产物必须进 STOMP 弹窗事件（生产链路
 *       gate → prompter → STOMP）。</li>
 * </ol>
 *
 * @see WebSocketPermissionPrompter
 * @see ToolPermissionGate
 * @since canUseTool v3 修复
 */
class InteractiveRaceModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

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

    private static PermissionResult.PendingClassifierCheck check(String command) {
        return new PermissionResult.PendingClassifierCheck(command, "/cwd", List.of());
    }

    private static PermissionPromptDetails details(boolean runHookRace, String command) {
        return new PermissionPromptDetails(
            "desc", List.of(), null,
            command != null ? check(command) : null, runHookRace);
    }

    // ─────────────────── 缺口 ① : 竞速模型 — hook 拒绝不阻塞 ───────────────────

    @Test
    @DisplayName("PermissionRequest hook 返回 blockingError → 竞速立即 Deny, 不阻塞 30s")
    void hookRace_denyResolvesWithoutUserWait() throws Exception {
        // WHY: CC interactiveHandler.ts:411-431 后台 ctx.runHooks 竞速 — hook 拒绝（blockingError/
        //      preventContinuation）应直接 resolve，无需等用户。若 Java 忽略 hook 决策只等用户
        //      → PermissionRequest hook 的 deny 形同虚设（v2 同步阻塞缺口）。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.executeEvent(any(com.nexusai.application.agent.permission.hook.HookEvent.class)))
            .thenReturn(GenericHook.HookResult.stop("denied", "hook blocked this tool"));
        prompter.setHookRegistryForTesting(registry);

        long before = System.currentTimeMillis();
        PermissionResult result = prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/etc/passwd"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-hook-1",
            details(true, null));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result)
            .as("hook 拒绝 → 竞速立即 Deny（CC :423-430 hook 决策 claim + resolve）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.message()).contains("hook blocked this tool"));
        assertThat(elapsed).isLessThan(3_000L);
    }

    // ─────────────────── [Session S07] hook 竞速: updatedInput 采纳 + deny 消息 (X2) ───────────────────

    @Test
    @DisplayName("S07 hook allow + updatedInput → 竞速采纳 hook 输入改写 (X2, CC PermissionContext.ts:233-239)")
    void hookRace_allowAdoptsUpdatedInput() throws Exception {
        // WHY (X2): CC runHooks (PermissionContext.ts:231-239) allow →
        //   finalInput = decision.updatedInput ?? updatedInput ?? input, 工具以改写后输入执行.
        //   旧 Java toHookRaceDecision 用原始 input 构造 Allow → hook 的输入改写 (headless
        //   wrapper 场景) 静默丢失. 必须断言 Allow.updatedInput 是 hook 改写产物.
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.executeEvent(any(com.nexusai.application.agent.permission.hook.HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed()
                .withPermissionRequestResult(
                    new com.nexusai.application.agent.permission.hook.PermissionRequestResult.Allow(
                        Map.of("command", "git status --short"), List.of())));
        prompter.setHookRegistryForTesting(registry);

        PermissionResult result = prompter.prompt(
            new DescribedTool("Bash", "bash"), JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-hook-allow-1",
            details(true, null));

        assertThat(result)
            .as("S07: hook allow 的 updatedInput 改写必须被采纳 (X2)")
            .isInstanceOfSatisfying(PermissionResult.Allow.class,
                allow -> assertThat(allow.updatedInput().path("command").asText())
                    .isEqualTo("git status --short"));
    }

    @Test
    @DisplayName("S07 hook deny + message → 竞速 Deny 携带 hook 消息 (fail-closed, CC PermissionContext.ts:240-258)")
    void hookRace_denyCarriesHookMessage() throws Exception {
        // WHY: CC buildDeny(message || 'Permission denied by hook') — hook 拒绝原因必须
        //      透传 (旧 Java 路径硬编码 "Permission request hook denied", 丢真实原因).
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.executeEvent(any(com.nexusai.application.agent.permission.hook.HookEvent.class)))
            .thenReturn(GenericHook.HookResult.proceed()
                .withPermissionRequestResult(
                    new com.nexusai.application.agent.permission.hook.PermissionRequestResult.Deny(
                        "policy blocks", false)));
        prompter.setHookRegistryForTesting(registry);

        PermissionResult result = prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/etc/passwd"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-hook-deny-1",
            details(true, null));

        assertThat(result)
            .as("S07: hook deny 消息必须透传为 deny message")
            .isInstanceOfSatisfying(PermissionResult.Deny.class,
                deny -> assertThat(deny.message()).isEqualTo("policy blocks"));
    }

    // ─────────────────── 缺口 ② : tool.description 进 STOMP 弹窗（生产链路） ───────────────────

    @Test
    @DisplayName("生产链路 gate → prompter: STOMP 事件携带 tool.description(input) 产物")
    void productionPath_stompEventCarriesToolDescription() throws Exception {
        // WHY: CC useCanUseTool.tsx:56-60 在 ask/deny 分流前 await tool.description(input, ...)，
        //      description 进交互队列与拒绝记录。v2 缺口 ② = description 不进弹窗。生产链路
        //      gate.promptDetailsOf → describe(tool,input) → prompter → STOMP 事件必须携带该产物。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 200);
        InteractiveHandler interactive = new InteractiveHandler(prompter);
        PermissionPipeline pipeline = new PermissionPipeline() {
            @Override
            public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                          ToolUseContext ctx, ToolPermissionContext permCtx) {
                return new PermissionResult.Ask("ask", new PermissionDecisionReason.Other("test"),
                    List.of(), null, null, null, false, null, List.of());
            }
        };
        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline, prompter, null, null, null,
            null, null, interactive,
            new PermissionDecisionLogger(null), null, null, null);
        ToolPermissionContext permCtx = new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);

        ToolUseBlock call = new ToolUseBlock("req-desc-1", "Read", JSON.createObjectNode().put("path", "/a"));
        // 在独立线程调用（prompt 阻塞 200ms 超时降级，但事件在阻塞前已推送）
        Thread t = new Thread(() -> gate.check(
            new DescribedTool("Read", "read-the-file"), call,
            JSON.createObjectNode().put("path", "/a"), newCtx(), permCtx));
        t.start();
        t.join(3_000);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        MessagePermissionRequestEvent event = (MessagePermissionRequestEvent) captor.getValue();
        assertThat(event.getDescription())
            .as("CC useCanUseTool.tsx:56-60 tool.description(input) 产物必须进弹窗（v2 缺口 ②）")
            .isEqualTo("read-the-file:/a");
    }

}

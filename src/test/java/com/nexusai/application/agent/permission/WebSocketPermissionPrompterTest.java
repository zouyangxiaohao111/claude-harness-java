package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [permissions_v4 IMP-10] WebSocketPermissionPrompter 竞速失败降级语义测试 · 对齐 CC
 * {@code structuredIO.ts:639-649}（异常→deny）+ OPD-WF8-CB-01 用户拍板组合方案。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：v4 探查判 C29 语义偏移（EV-WF8-CB-032）——
 * <ul>
 *   <li><b>CC</b>：竞速（hook + SDK prompt race）任一异常 → catch → deny 决策
 *       （"Tool permission request failed: ..."）。</li>
 *   <li><b>Java（旧）</b>：各 racer 异常 → log warn + drop（静默），由其余 racer / 超时兜底，
 *       超时兜底 deny 的 reason 恒为 {@code timeout}，掩盖"全部权限通道失败"的真实原因。</li>
 *   <li><b>拍板组合方案</b>：单个 racer 异常保留 Java drop（不因单崩溃误拒）；
 *       <b>全部 racer 异常 + 超时兜底必须 deny 而非静默放行</b>（对齐 CC）。</li>
 * </ul>
 *
 * <p>另含 <b>核心修复</b>：racer 抛 {@link AbortException}（hook 检测到用户中止意图）不得被
 * {@code catch (Throwable)} 吞掉转 timeout deny —— 必须立即以 abort deny 完成 future
 * （对齐 CC AbortError → 中止 agent，OPD-WF3-DC-v4-07 abort 主题）。
 */
class WebSocketPermissionPrompterTest {

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

    private static PermissionPromptDetails details(boolean runHookRace) {
        return new PermissionPromptDetails("desc", List.of(), null, null, runHookRace);
    }

    // ─────────────────── OPD-WF8-CB-01 · 全部 racer 异常 + 超时兜底 → deny ───────────────────

    @Test
    @DisplayName("全部 racer 异常 + 超时兜底 → deny(reason=permission_request_failed)（对齐 CC structuredIO.ts:639-649，不静默放行）")
    void allRacersFailed_timeout_deniesWithPermissionRequestFailed() {
        // WHY: 拍板组合方案 —— 单个 racer 异常保留 Java drop（不因单崩溃误拒），但全部 racer 异常 +
        //   超时兜底必须 deny 而非静默放行。旧 Java 各 racer 异常 log warn + drop，超时兜底返回
        //   deny(reason=timeout)，掩盖真实原因（bridge/channel 权限通道全部故障）。若 bridge+channel
        //   双双 sendRequest 抛异常 → 超时兜底必须以 permission_request_failed 拒绝（对齐 CC 异常→deny）。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 1_500);

        BridgePermissionCallbacks failingBridge = new BridgePermissionCallbacks() {
            @Override public void sendRequest(String sessionId, String requestId, String toolName,
                    JsonNode displayInput, String toolUseId, String description,
                    List<PermissionUpdate> suggestions, String blockedPath) {
                throw new IllegalStateException("bridge down");
            }
            @Override public Runnable onResponse(String requestId, Consumer<BridgeResponse> handler) {
                return () -> { };
            }
            @Override public void cancelRequest(String requestId) { }
            @Override public void sendResponse(String requestId, BridgeResponse response) { }
            @Override public boolean resolve(String requestId, BridgeResponse response) { return false; }
        };
        ChannelPermissionCallbacks failingChannel = new ChannelPermissionCallbacks() {
            @Override public String shortRequestId(String toolUseId) { return "abcde"; }
            @Override public Runnable onResponse(String requestId, Consumer<ChannelResponse> handler) {
                return () -> { };
            }
            @Override public void sendRequest(String sessionId, String requestId, String toolName,
                    String description, JsonNode displayInput) {
                throw new IllegalStateException("channel down");
            }
            @Override public boolean resolve(String requestId, String behavior, String fromServer) { return false; }
        };
        prompter.wireRacersForTesting(failingBridge, failingChannel);

        long before = System.currentTimeMillis();
        PermissionResult result = prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/a"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-all-fail-1",
            details(false));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result)
            .as("全部 racer 异常 + 超时兜底必须 deny（OPD-WF8-CB-01，对齐 CC structuredIO.ts:639-649）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason())
                    .as("全部权限通道失败的 deny 必须携带 permission_request_failed reason（非 timeout）")
                    .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                        o -> assertThat(o.reason()).isEqualTo("permission_request_failed")));
        // 竞速 drop 语义保留：单 racer 异常不立即拒绝（不因单崩溃误拒）—— 此处 bridge+channel 双双失败，
        // 仍走超时兜底（非 30s 固定等待，1.5s 测试超时即达）
        assertThat(elapsed).isLessThan(3_000L);
    }

    // ─────────────────── 核心修复 · AbortException 不得吞 → 立即中止 deny ───────────────────

    @Test
    @DisplayName("核心修复: hook racer 抛 AbortException → 立即 deny(reason=user_abort)，不吞 → 不转 timeout deny")
    void hookRacerAbort_abortsImmediately() {
        // WHY: 旧实现 startHookRace catch (Throwable) 吞 AbortException → racer drop → future 永不完成
        //   → 30s 超时兜底 deny(reason=timeout)，用户中止意图（CC AbortError）被静默转成 timeout deny。
        //   修复：AbortException 必须先于通用 Throwable 识别并立即完成 future 为 abort deny（对齐
        //   ctx.abortController.onCancel 的 user_abort 决策），中止 agent 而非等超时。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5_000);
        HookRegistry registry = mock(HookRegistry.class);
        when(registry.executeEvent(any(HookEvent.class)))
            .thenThrow(new AbortException("user pressed Ctrl-C"));
        prompter.setHookRegistryForTesting(registry);

        long before = System.currentTimeMillis();
        PermissionResult result = prompter.prompt(
            new DescribedTool("Read", "read"), JSON.createObjectNode().put("path", "/a"),
            new PermissionDecisionReason.Other("test"), newCtx(), "req-abort-1",
            details(true));
        long elapsed = System.currentTimeMillis() - before;

        assertThat(result)
            .as("AbortException 必须立即中止 deny（对齐 CC AbortError → 中止 agent）")
            .isInstanceOfSatisfying(PermissionResult.Deny.class, deny ->
                assertThat(deny.reason())
                    .as("AbortException 不得转 timeout deny，reason 必须 user_abort")
                    .isInstanceOfSatisfying(PermissionDecisionReason.Other.class,
                        o -> assertThat(o.reason()).isEqualTo("user_abort")));
        assertThat(elapsed)
            .as("AbortException 必须立即 resolve（不吞 → 不阻塞至 5s timeout）")
            .isLessThan(3_000L);
    }
}

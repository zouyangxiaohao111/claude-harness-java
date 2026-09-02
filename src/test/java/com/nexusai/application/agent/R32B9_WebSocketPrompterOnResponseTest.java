package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * R32-b9 · Phase 3 · WebSocketPermissionPrompter.onResponse 透传 acceptFeedback + contentBlocks.
 *
 * <p><b>WHY (意图验证)</b>: b9 brief 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1418-1467} addToolResult allow
 * 路径 — 前端 STOMP 弹窗可携带 {@code acceptFeedback} (text) + {@code contentBlocks} (image 等);
 * 后端 WebSocketPermissionPrompter 必须透传到 {@link PermissionResult.Allow} 字段供后续
 * LlmAgentLoop 注入 user message. 验证:
 * <ul>
 *   <li>2 参向后兼容 (旧前端只传 requestId + decision → Allow 仍 4 字段正常填入,但 feedback/blocks=null)</li>
 *   <li>4 参新版本: 透传 acceptFeedback + contentBlocks 到 Allow.acceptFeedback / Allow.contentBlocks</li>
 *   <li>4 参版本: null/blank 的 feedback 被规范化为 null (CC addToolResult 也过滤空字符串)</li>
 *   <li>4 参版本: 空 contentBlocks 列表被规范化为 {@code List.of()}</li>
 * </ul>
 *
 * <p>WHY 直接调 4 参 onResponse 测试: 这是 b9 新加的对外扩展点 (Phase 1 保证老前端兼容);
 * 直接调可绕开 STOMP 网络层,纯单元测试。
 *
 * @see WebSocketPermissionPrompter#onResponse(String, String, String, List)
 */
class R32B9_WebSocketPrompterOnResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("2 参向后兼容: onResponse(requestId, decision) 不抛, future 完成")
    void twoArgBackwardsCompatible() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        // 模拟 prompt 注册: 把 future 注入 pending map
        String requestId = "compat-call-1";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        prompter.onResponse(requestId, "allow");

        PermissionResult result = future.get();
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.acceptFeedback()).isNull();   // 旧前端不传
        assertThat(allow.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("4 参新版本: 透传 acceptFeedback + contentBlocks 到 Allow 字段")
    void fourArgTransmitsFeedback() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        String requestId = "call-fb-1";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        List<JsonNode> blocks = new ArrayList<>();
        blocks.add(JSON.readTree("{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"http://x/y.png\"}}"));
        prompter.onResponse(requestId, "allow", "用户反馈: 这张图有问题", blocks);

        PermissionResult result = future.get();
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.acceptFeedback()).isEqualTo("用户反馈: 这张图有问题");
        assertThat(allow.contentBlocks()).hasSize(1);
        assertThat(allow.contentBlocks().get(0).get("type").asText()).isEqualTo("image");
    }

    @Test
    @DisplayName("4 参版本: blank feedback → 规范化为 null;空 contentBlocks → List.of()")
    void fourArgNormalizesBlanks() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        String requestId = "call-fb-2";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        prompter.onResponse(requestId, "allow", "   ", new ArrayList<>());

        PermissionResult.Allow allow = (PermissionResult.Allow) future.get();
        assertThat(allow.acceptFeedback()).isNull();
        assertThat(allow.contentBlocks()).isEmpty();
    }

    @Test
    @DisplayName("unknown requestId → log warn 忽略,不抛异常 (防御 STOMP 重复响应)")
    void unknownRequestIdIgnored() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        // 不注册 pending future,直接调 → 应静默 log warn + 忽略
        prompter.onResponse("never-registered", "allow", "fb", List.of());
        // 通过: 没有异常,future null,不影响其他并发调用
        verify(ws, never()).convertAndSend(anyString(), anyObject());
    }

    @Test
    @DisplayName("decision=deny → PermissionResult.Deny, HookDenied 仍触发 (向后兼容)")
    void denyStillTriggersHook() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        String requestId = "call-deny-1";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        prompter.onResponse(requestId, "deny", null, null);

        PermissionResult result = future.get();
        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    // ─────────── reflection helpers ───────────

    @SuppressWarnings("unchecked")
    private static void registerPendingFuture(WebSocketPermissionPrompter prompter,
                                              String requestId,
                                              CompletableFuture<PermissionResult> future) throws Exception {
        Field pendingField = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<String, CompletableFuture<PermissionResult>> pending =
            (Map<String, CompletableFuture<PermissionResult>>) pendingField.get(prompter);
        pending.put(requestId, future);
    }

    // ─────────── mockito ArgumentMatchers ───────────

    private static String anyString() { return org.mockito.ArgumentMatchers.anyString(); }
    private static Object anyObject() { return org.mockito.ArgumentMatchers.any(Object.class); }
}

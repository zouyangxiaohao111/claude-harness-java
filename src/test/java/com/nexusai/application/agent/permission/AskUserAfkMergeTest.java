package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import com.nexusai.model.settings.dto.SettingsDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AFK 的「接线」验证：设置值 → 请求事件 → 响应回填 → Allow.updatedInput → 工具结果。
 *
 * <p>WHY 单独一条（不复用纯函数单测）：纯函数单测只能证明计算对，不能证明真的接上了 ——
 * 本仓出现过「语义有、接线无」的假绿。本测试走真公开入口：
 * {@code prompt(...)} 推事件 → {@code onResponse(...)} 回填 → 断言
 * <ol>
 *   <li>事件里带 {@code afkTimeoutMs}/{@code afkCountdownMs}（前端倒计时靠它）；</li>
 *   <li>{@code Allow.updatedInput} 带 {@code afkTimeoutMs}（工具据此出「没人回答」文案）；</li>
 *   <li>设置=never 时两者都不出现（默认行为与改造前一致）。</li>
 * </ol>
 */
@DisplayName("AFK 接线：设置 → 请求事件 → 响应回填 → 工具输入")
class AskUserAfkMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_ID = "agent-afk";
    private static final String SESSION = "sess-afk";
    private static final String TOPIC = "/topic/sessions/" + SESSION + "/permission-requests";

    private static final class StubTool implements Tool {
        @Override public String name() { return "AskUserQuestion"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    private static ObjectNode askInput() {
        ObjectNode input = JSON.createObjectNode();
        ObjectNode q = input.putArray("questions").addObject();
        q.put("question", "Which library should we use?");
        q.put("header", "Library");
        ObjectNode o = q.putArray("options").addObject();
        o.put("label", "date-fns");
        o.put("description", "Modern date library");
        return input;
    }

    private static ToolUseContext ctx() {
        // 会话 cwd 解析依赖冻结表（本仓既有夹具约定，缺失会抛 UnresolvedProjectRootException）
        com.nexusai.common.SessionProjectRoot.setForSession(SESSION, System.getProperty("user.dir"));
        return ToolUseContext.of(AGENT_ID, SESSION, PermissionMode.DEFAULT,
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            new ToolPermissionContext(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
                false, false, Map.of(), false, false, null),
            PermissionMode.DEFAULT);
    }

    /** 反射注入 settingsService（prompter 用 @Autowired(required=false) 字段注入）。 */
    private static void injectSettings(WebSocketPermissionPrompter prompter, String afkSetting)
            throws Exception {
        SettingsDto dto = JSON.readValue("{\"askUserAfkTimeout\":\"" + afkSetting + "\"}", SettingsDto.class);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.get()).thenReturn(dto);
        Field f = WebSocketPermissionPrompter.class.getDeclaredField("settingsService");
        f.setAccessible(true);
        f.set(prompter, settingsService);
    }

    /**
     * 抓取推给权限 topic 的请求事件（同一次 prompt 可能还推解释事件 ⇒ 按类型过滤）。
     *
     * <p>⚠️ pending 登记发生在 STOMP 推送**之前** ⇒ 轮询等推送，避免与 push 线程竞态。
     */
    private static MessagePermissionRequestEvent captureRequestEvent(SimpMessagingTemplate ws)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            try {
                Mockito.verify(ws, Mockito.atLeastOnce()).convertAndSend(eq(TOPIC), sent.capture());
            } catch (AssertionError notYet) {
                Thread.sleep(10);
                continue;
            }
            MessagePermissionRequestEvent event = sent.getAllValues().stream()
                .filter(v -> v instanceof MessagePermissionRequestEvent)
                .map(v -> (MessagePermissionRequestEvent) v)
                .findFirst()
                .orElse(null);
            if (event != null) {
                return event;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("未捕获到 permission.request 事件");
    }

    private static void awaitPending(WebSocketPermissionPrompter prompter, String requestId,
                                     AtomicReference<Throwable> boom) throws Exception {
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
        throw new AssertionError("pending 未在 5s 内登记: " + requestId
            + "（prompt 异常 = " + boom.get() + "）");
    }

    @Test
    @DisplayName("设置=5m ⇒ 事件带 300000/20000；超时回填后 Allow.updatedInput 带 afkTimeoutMs")
    void enabledSettingFlowsIntoEventAndAllow() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        injectSettings(prompter, "5m");

        String requestId = "req-afk-1";
        AtomicReference<PermissionResult> resolved = new AtomicReference<>();
        AtomicReference<Throwable> boom = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                resolved.set(prompter.prompt(new StubTool(), askInput(),
                    new PermissionDecisionReason.Other("test"), ctx(), requestId,
                    new PermissionPromptDetails("desc", List.of(), null)));
            } catch (Throwable th) {
                boom.set(th);
            }
        });
        t.start();
        awaitPending(prompter, requestId, boom);

        MessagePermissionRequestEvent event = captureRequestEvent(ws);
        assertThat(event.getAfkTimeoutMs()).as("事件下发总时长").isEqualTo(300_000);
        assertThat(event.getAfkCountdownMs()).as("事件下发倒计时阈值（默认 20s）").isEqualTo(20_000);

        ObjectNode answers = JSON.createObjectNode();
        answers.put("Which library should we use?", "date-fns");
        prompter.onResponse(requestId, "allow", null, null, null, answers, null, null, 300_000, true);
        t.join(5_000);

        Object result = resolved.get();
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        JsonNode updatedInput = ((PermissionResult.Allow) result).updatedInput();
        assertThat(updatedInput.path("afkTimeoutMs").asInt()).isEqualTo(300_000);
        assertThat(updatedInput.path("answers").path("Which library should we use?").asText())
            .isEqualTo("date-fns");
        assertThat(updatedInput.has("isByCountdown"))
            .as("isByCountdown 是结果顶层标记，不进 tool_result/updatedInput")
            .isFalse();
    }

    @Test
    @DisplayName("设置=never ⇒ 事件不带计时，人类作答回填后 Allow.updatedInput 无 afkTimeoutMs（默认行为不变）")
    void neverSettingKeepsLegacyBehavior() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 100);
        injectSettings(prompter, "never");

        String requestId = "req-afk-2";
        AtomicReference<PermissionResult> resolved = new AtomicReference<>();
        AtomicReference<Throwable> boom = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                resolved.set(prompter.prompt(new StubTool(), askInput(),
                    new PermissionDecisionReason.Other("test"), ctx(), requestId,
                    new PermissionPromptDetails("desc", List.of(), null)));
            } catch (Throwable th) {
                boom.set(th);
            }
        });
        t.start();
        awaitPending(prompter, requestId, boom);

        MessagePermissionRequestEvent event = captureRequestEvent(ws);
        assertThat(event.getAfkTimeoutMs()).as("never ⇒ 前端不计时").isNull();
        assertThat(event.getAfkCountdownMs()).isNull();

        ObjectNode answers = JSON.createObjectNode();
        answers.put("Which library should we use?", "date-fns");
        prompter.onResponse(requestId, "allow", null, null, null, answers, null, null, null, null);
        t.join(5_000);

        JsonNode updatedInput = ((PermissionResult.Allow) resolved.get()).updatedInput();
        assertThat(updatedInput.has("afkTimeoutMs"))
            .as("人类作答路径不得凭空出现 AFK 标记")
            .isFalse();
        assertThat(updatedInput.path("answers").size()).isEqualTo(1);
    }
}

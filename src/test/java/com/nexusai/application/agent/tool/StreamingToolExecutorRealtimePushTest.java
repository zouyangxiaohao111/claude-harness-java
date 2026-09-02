package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import com.nexusai.eventbus.ws.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [工具调用实时推] StreamingToolExecutor 实时推埋点 · 净新增。
 *
 * <p>覆盖 (设计规格 T1/T2/T3/T4/T5/T6/T7/T8/T11):
 * <ol>
 *   <li>T1 tool_call 实时推: add 入口 → mock SimpMessagingTemplate.convertAndSend 捕获
 *       MessageToolCallEvent, 断言 type/sessionId/userMessageId/assistantMessageId(=parent)/
 *       toolCallId/toolName/arguments</li>
 *   <li>T2 注入 null → 零 convertAndSend (向后兼容 cron/非流式/单测)</li>
 *   <li>T3 unknown/disabled 与 discarded 分支都推 tool_call (+ tool_result 错误内容)</li>
 *   <li>T4 实时-实时幂等去重: 同 toolCallId 二次 add → 仅推一次</li>
 *   <li>T5 成功漏斗出口 tool_result isError=false</li>
 *   <li>T6 catch(Throwable) 出口 tool_result isError=true</li>
 *   <li>T7 abort / schema / semantic 三条早退出口各自推 isError=true</li>
 *   <li>T8 去重登记: AgentState.realtimeToolCallsPushed()/realtimeToolResultsPushed() 含 id</li>
 *   <li>T11 嵌套入参保真 + result 超 5000 截断加 "... (truncated)"</li>
 * </ol>
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 实时推意图是「工具调用即时可见 + 回放去重防重复
 * 卡片」——若实现回退 (删埋点 / 断幂等 / 断截断), 对应断言必须变红.
 */
@DisplayName("[工具调用实时推] StreamingToolExecutor 实时推埋点")
class StreamingToolExecutorRealtimePushTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "/topic/stream";

    // ════════════════════════════════════════════════════════════════════
    // T1 + T8: 正常分支 tool_call 实时推 + 去重登记
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1/T8 正常分支 add → tool_call 实时推 (parent assistantMessageId) + 双集合去重登记")
    void realtimeToolCallPushedOnAddWithParent() throws Exception {
        // GIVEN: 已注入推送通道 + agentState 的 executor, 注册工具
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "hello world"));
        AgentState state = new AgentState("sys");
        state.prepareAssistantMessageId();
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        // WHEN: add 带 parent (生产路径 turnAssistantId 逐工具注入 ToolParent)
        exec.add(call("tc1", "quick"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // THEN: tool_call 恰好推一次, 字段逐项对齐
        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolCallEvent evt = toolCall(captor, "tc1");
        assertThat(evt).as("tool_call 必须被实时推送").isNotNull();
        assertThat(evt.getType()).isEqualTo("message.tool_call");
        assertThat(evt.getSessionId()).isEqualTo("sess-1");
        assertThat(evt.getUserMessageId()).isEqualTo("msg-u1");
        assertThat(evt.getAssistantMessageId())
            .as("assistantMessageId 必须取 parent.assistantMessageId (turnAssistantId)")
            .isEqualTo("turn-1");
        assertThat(evt.getToolCallId()).isEqualTo("tc1");
        assertThat(evt.getToolName()).isEqualTo("quick");
        assertThat(evt.getArguments()).isNotNull();

        // THEN (T8): 双集合均已登记 id
        assertThat(state.realtimeToolCallsPushed()).as("tool_call id 已登记").contains("tc1");
        assertThat(state.realtimeToolResultsPushed()).as("tool_result id 已登记").contains("tc1");
    }

    // ════════════════════════════════════════════════════════════════════
    // T2: 注入 null → no-op
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T2 未注入推送通道 → 工具执行全路径零 convertAndSend (向后兼容)")
    void noInjection_noConvertAndSend() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "ok"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        // NOTE: 不调 setToolStreamPublisher → toolStream == null

        exec.add(call("tc1", "quick"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        assertThat(state.realtimeToolCallsPushed())
            .as("未注入通道不登记 (保持空集合, 回放全推向后兼容)")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // T3: unknown/disabled 与 discarded 分支全覆盖
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T3a unknown 工具 (add 第三分支) → tool_call + tool_result(isError=true) 实时推")
    void unknownTool_pushesToolCallAndErrorResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry(); // 不含 "ghost"
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tg1", "ghost"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        assertThat(toolCall(captor, "tg1")).as("unknown 工具也必须实时推 tool_call").isNotNull();
        MessageToolResultEvent res = toolResult(captor, "tg1");
        assertThat(res).as("unknown 工具 tool_result 已同步就绪, 实时推").isNotNull();
        assertThat(res.getIsError()).isTrue();
        assertThat(res.getResult()).contains("No such tool available");
    }

    @Test
    @DisplayName("T3b discarded 分支 → tool_call + tool_result(synthetic error) 实时推")
    void discardedBranch_pushesToolCallAndErrorResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "ok"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.discard();
        exec.add(call("td1", "quick"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        assertThat(toolCall(captor, "td1")).as("discarded 工具必须实时推 tool_call").isNotNull();
        MessageToolResultEvent res = toolResult(captor, "td1");
        assertThat(res).as("discarded 工具 tool_result 同步就绪, 实时推 (前端取消卡片)").isNotNull();
        assertThat(res.getIsError()).isTrue();
        assertThat(res.getResult()).contains("Streaming fallback");
    }

    // ════════════════════════════════════════════════════════════════════
    // T4: 实时-实时幂等去重
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T4 同 toolCallId 二次 add (streaming-fallback 重建) → convertAndSend 仅一次")
    void sameToolCallIdTwice_pushesOnce() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "ok"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tc1", "quick"), ToolParent.of("turn-1"), null);
        exec.add(call("tc1", "quick"), ToolParent.of("turn-1"), null); // 重建二次 add 同 id
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        assertThat(toolCalls(captor, "tc1"))
            .as("Set.add 幂等: 二次 add 同 id 必须跳过, 仅推一次 tool_call")
            .hasSize(1);
        assertThat(toolResults(captor, "tc1"))
            .as("tool_result 同样仅推一次")
            .hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // T5: 成功漏斗出口
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T5 工具正常完成 → tool_result(isError=false, result=内容) 实时推")
    void successExit_pushesToolResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "hello world"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tc1", "quick"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "tc1");
        assertThat(res).isNotNull();
        assertThat(res.getIsError()).isFalse();
        assertThat(res.getResult()).isEqualTo("hello world");
        assertThat(res.getAssistantMessageId()).isEqualTo("turn-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // T6: catch(Throwable) 出口
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T6 tool.execute 抛异常 → tool_result(isError=true) 实时推")
    void exceptionExit_pushesErrorResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(throwingTool("boomTool", "boom failure"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("te1", "boomTool"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "te1");
        assertThat(res).isNotNull();
        assertThat(res.getIsError())
            .as("catch 路径 isError 取精确 t.isError=true")
            .isTrue();
        assertThat(res.getResult())
            .as("result = ToolErrorFormatter.formatError 内容 (含异常 message)")
            .contains("boom");
    }

    // ════════════════════════════════════════════════════════════════════
    // T7: abort / schema / semantic 早退出口
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T7a abort 早退 → tool_result(isError=true) 实时推 (前端收 cancelled 卡片)")
    void abortExit_pushesCancelledResult() throws Exception {
        AbortController parent = new AbortController();
        parent.abort("interrupt");
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "cancelTool"; }
            @Override public String description() { return "cancel tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "cancel"; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new AssertionError("cancel 工具不得真实执行");
            }
        });
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool, context(parent));
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tab1", "cancelTool"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "tab1");
        assertThat(res).as("abort 早退出口必须实时推 tool_result").isNotNull();
        assertThat(res.getIsError()).isTrue();
    }

    @Test
    @DisplayName("T7b schema 校验失败 → tool_result(isError=true) 实时推")
    void schemaFailExit_pushesErrorResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(schemaRequiredTool("schemaTool"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setInputValidator(new ToolInputValidator()); // 真实 schema 校验
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        // input 缺 required 字段 "path" → schema 校验必失败
        exec.add(call("tsc1", "schemaTool", JSON.createObjectNode()), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "tsc1");
        assertThat(res).as("schema 失败出口必须实时推 tool_result").isNotNull();
        assertThat(res.getIsError()).isTrue();
        assertThat(res.getResult()).contains("InputValidationError");
    }

    @Test
    @DisplayName("T7c semantic 校验失败 → tool_result(isError=true) 实时推")
    void semanticFailExit_pushesErrorResult() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(semanticFailTool("semTool", "path escapes workspace"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setInputValidator(new ToolInputValidator()); // 真实语义校验 (委托 tool.validateInput)
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tsem1", "semTool"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "tsem1");
        assertThat(res).as("semantic 失败出口必须实时推 tool_result").isNotNull();
        assertThat(res.getIsError()).isTrue();
        assertThat(res.getResult()).contains("path escapes workspace");
    }

    // ════════════════════════════════════════════════════════════════════
    // T11: 嵌套入参保真 + result 截断
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T11a arguments 含嵌套对象/数组 → convertValue 正确保真")
    void nestedArguments_preserved() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("quick", "ok"));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        JsonNode input = JSON.valueToTree(Map.of(
            "nested", Map.of("a", 1),
            "arr", List.of(1, 2)));
        exec.add(call("tn1", "quick", input), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolCallEvent evt = toolCall(captor, "tn1");
        assertThat(evt).isNotNull();
        assertThat(evt.getArguments()).containsKeys("nested", "arr");
        assertThat(evt.getArguments().get("nested")).isEqualTo(Map.of("a", 1));
        assertThat(evt.getArguments().get("arr")).isEqualTo(List.of(1, 2));
    }

    @Test
    @DisplayName("T11b result 超过 5000 → 截断加 '\\n... (truncated)'")
    void longResult_truncated() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ToolRegistry registry = new ToolRegistry();
        registry.register(quickTool("big", "x".repeat(6000)));
        AgentState state = new AgentState("sys");
        StreamingToolExecutor exec = newExecutor(registry, pool);
        exec.setAgentState(state);
        exec.setToolStreamPublisher(ws, TOPIC, "sess-1", "msg-u1");

        exec.add(call("tb1", "big"), ToolParent.of("turn-1"), null);
        exec.getRemainingResults();
        pool.shutdown();

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws, atLeastOnce()).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolResultEvent res = toolResult(captor, "tb1");
        assertThat(res).isNotNull();
        assertThat(res.getResult()).hasSize(5000 + "\n... (truncated)".length());
        assertThat(res.getResult()).endsWith("... (truncated)");
    }

    // ════════════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════════════

    private static StreamingToolExecutor newExecutor(ToolRegistry registry, ExecutorService pool) {
        return new StreamingToolExecutor(registry, pool, context());
    }

    private static StreamingToolExecutor newExecutor(ToolRegistry registry, ExecutorService pool, ToolUseContext ctx) {
        return new StreamingToolExecutor(registry, pool, ctx);
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseBlock call(String id, String name, JsonNode input) {
        return new ToolUseBlock(id, name, input);
    }

    private static ToolUseContext context() {
        return context(new AbortController());
    }

    private static ToolUseContext context(AbortController abortController) {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> java.util.Collections.unmodifiableSet(java.util.Set.of()));
    }

    /** 快速成功工具 (concurrency-safe, 默认 isEnabled=true). */
    private static Tool quickTool(String name, String result) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "quick " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), result);
            }
        };
    }

    /** execute 抛异常的失败工具. */
    private static Tool throwingTool(String name, String errMsg) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "throw " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new RuntimeException(errMsg);
            }
        };
    }

    /** inputSchema required="path" → 缺该字段时真实 ToolInputValidator schema 校验失败. */
    private static Tool schemaRequiredTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "schema " + name; }
            @Override public JsonNode inputSchema() {
                ObjectNode schema = JSON.createObjectNode();
                schema.putArray("required").add("path");
                schema.putObject("properties").putObject("path").put("type", "string");
                return schema;
            }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }

    /** validateInput 返回 fail → 真实 ToolInputValidator 语义校验失败. */
    private static Tool semanticFailTool(String name, String errMsg) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "sem " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public Tool.ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
                return Tool.ValidationResult.fail("PATH_ESCAPE", errMsg);
            }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
    }

    private static MessageToolCallEvent toolCall(ArgumentCaptor<StreamEvent> captor, String toolCallId) {
        return toolCalls(captor, toolCallId).stream().findFirst().orElse(null);
    }

    private static List<MessageToolCallEvent> toolCalls(ArgumentCaptor<StreamEvent> captor, String toolCallId) {
        return captor.getAllValues().stream()
            .filter(MessageToolCallEvent.class::isInstance)
            .map(MessageToolCallEvent.class::cast)
            .filter(e -> toolCallId.equals(e.getToolCallId()))
            .toList();
    }

    private static MessageToolResultEvent toolResult(ArgumentCaptor<StreamEvent> captor, String toolCallId) {
        return toolResults(captor, toolCallId).stream().findFirst().orElse(null);
    }

    private static List<MessageToolResultEvent> toolResults(ArgumentCaptor<StreamEvent> captor, String toolCallId) {
        return captor.getAllValues().stream()
            .filter(MessageToolResultEvent.class::isInstance)
            .map(MessageToolResultEvent.class::cast)
            .filter(e -> toolCallId.equals(e.getToolCallId()))
            .toList();
    }
}

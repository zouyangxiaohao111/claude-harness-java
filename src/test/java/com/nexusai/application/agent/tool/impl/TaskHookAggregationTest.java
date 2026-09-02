package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.nexusai.application.agent.tool.ToolUseContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [hook-aggregate P1] TaskCreate / TaskUpdate hook 阻塞错误全量聚合定向测试.
 *
 * <p>WHY 本测试验证意图（对齐 CC 逐 result.blockingError 收集，非仅行为）：CC
 * executeTaskCreatedHooks / executeTaskCompletedHooks 消费方 {@code for await (const result of generator)}
 * 逐 result.blockingError 收集全部（TaskCreateTool.ts:104-108 / TaskUpdateTool.ts:247-253），
 * 每条经 getTaskCreatedHookMessage / getTaskCompletedHookMessage 加前缀（hooks.ts:1914-1929），
 * 最后 join('\n')。Java 端 {@code HookRegistry.executeEvent} 折叠为首个 blockingError
 * （:1331-1332 + resolveEventResult），工具层只能见到 1 个 —— 本项新增
 * {@code executeEventAll} 暴露全部结果，工具逐条聚合。若未来工具又退回只取首个或丢前缀，
 * 本测试即失败（测试验证意图而非仅行为）。
 *
 * <p>语义边界（CC 真源，不信注释）：
 * <ul>
 *   <li><b>TaskCreate</b>: 非空 → deleteTask 回滚 + throw join('\n')（TaskCreateTool.ts:110-113）
 *       —— Java 用 ToolResult.error 通道承载 throw（isError=true）。</li>
 *   <li><b>TaskUpdate</b>: 非空 → 返回结构化 {data:{success:false,error:join('\n')}} 良性失败
 *       （TaskUpdateTool.ts:255-264）—— Java 双通道 success:false（isError=false），
 *       structuredOutput.error 承载 join('\n')。</li>
 * </ul>
 */
class TaskHookAggregationTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskCreate/Update call() 内逐次 getTaskListId()：mock getTask/deleteTask("tl-1",...) 要命中必须返回 "tl-1"
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.taskListId");
    }

    // ════════════════════════════════════════════════════════════════════════
    // TaskCreate · 全量聚合 + 前缀 + join + deleteTask 回滚
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TaskCreate 两个 hook 各返回 blockingError → 全量聚合前缀 join('\\n') + deleteTask 回滚（CC:104-113）")
    void taskCreate_collectsAllBlockingErrorsAndRollsBack() {
        // WHY: 旧 Java 只取 blockingErrors.get(0) 且用 stopReason()（语义不同的字段），
        // CC 逐 result.blockingError 收集全部并 join('\n') 后 deleteTask 回滚再 throw。
        // 两个 hook 都必须被聚合，缺一个即为差异回归。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-5");
        when(taskService.deleteTask(eq("tl-1"), eq("t-5"))).thenReturn(true);

        HookRegistry registry = new HookRegistry();
        registry.register("hookA", event ->
                event.type() == HookEventType.TASK_CREATED
                    ? GenericHook.HookResult.stop("reasonA", "A")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_CREATED);
        registry.register("hookB", event ->
                event.type() == HookEventType.TASK_CREATED
                    ? GenericHook.HookResult.stop("reasonB", "B")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_CREATED);

        TaskCreateTool tool = new TaskCreateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskCreate",
            json.createObjectNode().put("subject", "Write docs"));

        ToolResult<?> result = tool.execute(call);

        // CC: throw new Error(blockingErrors.join('\n')) → Java error 通道（isError=true）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(result.data()).isEqualTo(
            "TaskCreated hook feedback:\nA\nTaskCreated hook feedback:\nB");
        // CC: await deleteTask(getTaskListId(), taskId) 回滚
        verify(taskService).deleteTask("tl-1", "t-5");
    }

    @Test
    @DisplayName("TaskCreate 单 hook 阻塞 → 单条前缀文本 + 回滚（不重复 join 换行）")
    void taskCreate_singleBlockingErrorStillPrefixed() {
        // WHY: 聚合逻辑对单 hook 也必须加前缀（CC 每条都经 getTaskCreatedHookMessage），
        // join('\n') 对单元素不产生多余换行。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-7");
        when(taskService.deleteTask(eq("tl-1"), eq("t-7"))).thenReturn(true);

        HookRegistry registry = new HookRegistry();
        registry.register("only", event ->
                event.type() == HookEventType.TASK_CREATED
                    ? GenericHook.HookResult.stop("reasonOnly", "solo error")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_CREATED);

        TaskCreateTool tool = new TaskCreateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-2", "TaskCreate",
            json.createObjectNode().put("subject", "One hook"));

        ToolResult<?> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(result.data()).isEqualTo("TaskCreated hook feedback:\nsolo error");
        verify(taskService).deleteTask("tl-1", "t-7");
    }

    // ════════════════════════════════════════════════════════════════════════
    // TaskUpdate · 全量聚合 + 前缀 + 结构化 success:false（非 error）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TaskUpdate completed 两 hook 阻塞 → 全量聚合前缀 join('\\n') + success:false 非 error（CC:247-264）")
    void taskUpdate_collectsAllBlockingErrors_returnsStructuredSuccessFalse() {
        // WHY: CC 对 blockingErrors 非空返回结构化 {data:{success:false,error:join('\n')}} 良性失败
        // （非 throw 非 error）。旧 Java 用 ToolResult.error(get(0)) 只取首个且升级为失败。
        TaskService taskService = mock(TaskService.class);
        Task inProgress = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(inProgress));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(inProgress));

        HookRegistry registry = new HookRegistry();
        registry.register("hookA", event ->
                event.type() == HookEventType.TASK_COMPLETED
                    ? GenericHook.HookResult.stop("reasonA", "A")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_COMPLETED);
        registry.register("hookB", event ->
                event.type() == HookEventType.TASK_COMPLETED
                    ? GenericHook.HookResult.stop("reasonB", "B")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_COMPLETED);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "completed"));

        ToolResult<String> result = tool.execute(call);

        // CC: 良性失败非 error —— isError=false（区别于 TaskCreate 的 throw/error）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).isEqualTo(
            "TaskCompleted hook feedback:\nA\nTaskCompleted hook feedback:\nB");
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(false);
        assertThat(so.get("taskId")).isEqualTo("t-1");
        assertThat(so.get("updatedFields")).isEqualTo(List.of());
        assertThat(so.get("error")).isEqualTo(
            "TaskCompleted hook feedback:\nA\nTaskCompleted hook feedback:\nB");
        // 阻塞时不应推进 status（CC 提前 return，未走 updateTask）
        verify(taskService, never()).updateTask(any(), any(), Mockito.anyMap());
    }

    @Test
    @DisplayName("TaskUpdate completed 单 hook 阻塞 → 单条前缀文本 + success:false")
    void taskUpdate_singleBlockingErrorStillPrefixed() {
        TaskService taskService = mock(TaskService.class);
        Task inProgress = new Task("t-3", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-3")).thenReturn(Optional.of(inProgress));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(inProgress));

        HookRegistry registry = new HookRegistry();
        registry.register("only", event ->
                event.type() == HookEventType.TASK_COMPLETED
                    ? GenericHook.HookResult.stop("reasonOnly", "solo error")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_COMPLETED);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-2", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-3").put("status", "completed"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).isEqualTo("TaskCompleted hook feedback:\nsolo error");
        assertThat (ToolResult.presentationMeta(result).get("success")).isEqualTo(false);
        assertThat (ToolResult.presentationMeta(result).get("error")).isEqualTo("TaskCompleted hook feedback:\nsolo error");
        verify(taskService, never()).updateTask(any(), any(), Mockito.anyMap());
    }

    /** [IMP-C2] successWithStructuredOutput 折入 data(Map) 后，模型侧渲染文本在 "summary" 键。 */
    private static String summary(ToolResult<?> result) {
        Object data = result.data();
        if (data instanceof Map<?, ?> m && m.containsKey("summary")) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(data);
    }

    @Test
    @DisplayName("无阻塞 hook（proceed）→ 工具正常推进，不返回阻塞错误")
    void noBlockingError_toolProceeds() {
        // WHY: 聚合语义不得把 proceed()（blockingError=null）误判为阻塞 ——
        // CC 仅 result.blockingError 存在才收集（TaskCreateTool.ts:105 / TaskUpdateTool.ts:248）。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-9");
        HookRegistry registry = new HookRegistry();
        registry.register("pass", event -> GenericHook.HookResult.proceed(),
            HookEventType.TASK_CREATED);

        TaskCreateTool tool = new TaskCreateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-3", "TaskCreate",
            json.createObjectNode().put("subject", "No block"));

        ToolResult<?> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.data()).isInstanceOf(TaskCreateTool.TaskCreateOutput.class);
        verify(taskService, never()).deleteTask(any(), any());
    }
    @Test
    @DisplayName("TaskCreate hook 事件 session_id 非 null 且等于 ctx.sessionId()（CC getSessionId() 恒有, hooks.ts:315/3756-3764）")
    void taskCreate_hookEventSessionIdEqualsCtxSessionId() {
        // WHY: OD-TC-2 工具侧最小补传——CC createBaseHookInput 的 session_id 恒有（hooks.ts:315
        // resolvedSessionId = sessionId ?? getSessionId()），Java 此前传 null 导致 session-scoped
        // programmatic hook 无法命中 TaskCreated（HookRegistry:1529 sessionScope 过滤 + CommandHookExecutor:1298
        // 非 null 才写 session_id）。2 参 execute 传 ctx 时事件必须携带 ctx.sessionId()。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-11");

        HookRegistry registry = new HookRegistry();
        AtomicReference<String> capturedSessionId = new AtomicReference<>();
        registry.register("capture", event -> {
            if (event.type() == HookEventType.TASK_CREATED) {
                capturedSessionId.set(event.sessionId());
            }
            return GenericHook.HookResult.proceed();
        }, HookEventType.TASK_CREATED);

        TaskCreateTool tool = new TaskCreateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-4", "TaskCreate",
            json.createObjectNode().put("subject", "Session capture"));
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), sessionUuid);

        ToolResult<?> result = tool.execute(call, ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // CC: session_id = getSessionId() 恒有 → Java 事件 sessionId 必须非 null 且等于调用方 ctx.sessionId()
        assertThat(capturedSessionId.get()).isNotNull();
        assertThat(capturedSessionId.get()).isEqualTo(sessionUuid.toString());
    }

    @Test
    @DisplayName("TaskUpdate completed hook 事件 session_id 非 null 且等于 ctx.sessionId()（CC getSessionId() 恒有）")
    void taskUpdate_hookEventSessionIdEqualsCtxSessionId() {
        // WHY: OD-TU-4 工具侧补传（与 TaskCreate 同构；CC executeTaskCompletedHooks 9 参中
        // toolUseContext 携带的 session 上下文，hookInput.session_id 恒为主会话）。
        TaskService taskService = mock(TaskService.class);
        Task inProgress = new Task("t-13", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-13")).thenReturn(Optional.of(inProgress));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(inProgress));

        HookRegistry registry = new HookRegistry();
        AtomicReference<String> capturedSessionId = new AtomicReference<>();
        registry.register("capture", event -> {
            if (event.type() == HookEventType.TASK_COMPLETED) {
                capturedSessionId.set(event.sessionId());
            }
            return GenericHook.HookResult.proceed();
        }, HookEventType.TASK_COMPLETED);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-3", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-13").put("status", "completed"));
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), sessionUuid);

        ToolResult<String> result = tool.execute(call, ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // CC: session_id 恒有（hooks.ts:315）→ Java 事件 sessionId 必须非 null 且等于调用方 ctx.sessionId()
        assertThat(capturedSessionId.get()).isNotNull();
        assertThat(capturedSessionId.get()).isEqualTo(sessionUuid.toString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPD-WF4-LC-01 · TaskCreateTool abortController data 收敛为 null
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TaskCreate ctx 携带已取消 AbortController → hook 事件 data 不含 abort_signal_cancelled/reason（OPD-WF4-LC-01 收敛为 null）")
    void taskCreate_abortControllerDataConvergesToNull() {
        // WHY（测试验证意图而非仅行为）：CC TaskCreatedHookInputSchema（coreSchemas.ts:601-612）
        // 无 abort_signal_cancelled/abort_signal_reason 载荷——CC executeTaskCreatedHooks 的
        // signal 仅作 executeHooks 控制参数（hooks.ts:3766-3772），不进 hookInput。Java 端此前把
        // ctx.abortController() 状态写入 hook event data（HookEventData.TaskCreated 第 6/7 字段）
        // 属 Java 独有扩展（OPD-WF4-LC-01 拍板收敛为 null）。即使 ctx 携带已取消且带 reason 的
        // AbortController，taskCreated hook 事件 data 也不得出现 abort 键——否则 hook 命令会收到
        // CC 不存在的载荷，契约漂移。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-15");

        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("capture", event -> {
            if (event.type() == HookEventType.TASK_CREATED) {
                captured.set(event);
            }
            return GenericHook.HookResult.proceed();
        }, HookEventType.TASK_CREATED);

        TaskCreateTool tool = new TaskCreateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-5", "TaskCreate",
            json.createObjectNode().put("subject", "Abort converge"));
        AbortController abort = new AbortController();
        abort.abort("user_cancelled"); // 已取消 + reason（旧实现会把 cancelled=true/reason 写入 data map）
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "tl-1", abort);

        ToolResult<?> result = tool.execute(call, ctx);

        // [IMP-C2] ToolResult 4 字段契约删 isError → 以 isToolErrorData(data) 推导（master 同款模式）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        HookEvent evt = captured.get();
        assertThat(evt).isNotNull();
        // CC TaskCreatedHookInput 无 abort 载荷 → data 不得含 abort_signal_cancelled / abort_signal_reason
        assertThat(evt.data()).doesNotContainKey("abort_signal_cancelled");
        assertThat(evt.data()).doesNotContainKey("abort_signal_reason");
    }
}

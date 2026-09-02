package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * maptoolresult-layer · TaskUpdateTool 双通道结构化输出定向测试（CC TaskUpdateTool.ts 对齐）。
 *
 * <p>WHY 本测试验证意图（CC 双通道契约）：TaskUpdate 未找到 / deleted / hook 阻塞 / 成功
 * 全部返回 {@code successWithStructuredOutput} 双通道——
 * <ul>
 *   <li><b>data</b>（LLM 可见 tool_result content）= {@link TaskUpdateTool#mapToolResultToToolResultBlockParam}
 *       渲染文本（对齐 CC TaskUpdateTool.ts:364-405）；</li>
 *   <li><b>structuredOutput</b>（给消费方解析的结构化 JSON，spread 字段）= success/taskId/
 *       updatedFields/error?/statusChange{from,to}?/verificationNudgeNeeded?（对齐 CC:69-83）。</li>
 * </ul>
 * 未找到与 hook 阻塞是<b>结构化 success:false 成功</b>（非 ToolResult.error，对齐 CC:147-156 / 255-264），
 * deleted 返回 statusChange{from,to:'deleted'}（对齐 CC:214-227）。
 */
class TaskUpdateToolStructuredOutputTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskUpdateTool call() 内逐次 getTaskListId()：mock getTask/deleteTask("tl-1",...) 要命中必须返回 "tl-1"
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("未找到 → isError=false + data='Task not found' + structuredOutput{success:false,error:'Task not found'}（CC:147-156）")
    void notFound_returnsStructuredSuccessFalse() {
        // WHY: CC 未找到是 { success: false } 良性结构非 error（TaskUpdateTool.ts:147-156），
        // Java 旧代码用 ToolResult.error 把良性条件升级为失败。
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "missing")).thenReturn(Optional.empty());

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "missing"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 良性条件非 error
        // data 通道 = mapper 渲染文本（CC:380 content = error || `Task #${taskId} not found`）
        assertThat(summary(result)).isEqualTo("Task not found");
        // structuredOutput 通道 = spread 结构化字段（CC:69-83 outputSchema）
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(false);
        assertThat(so.get("taskId")).isEqualTo("missing");
        assertThat(so.get("updatedFields")).isEqualTo(List.of());
        assertThat(so.get("error")).isEqualTo("Task not found");
        assertThat(so.containsKey("statusChange")).isFalse();
        assertThat(so.containsKey("verificationNudgeNeeded")).isFalse();
    }

    @Test
    @DisplayName("空 taskId → 良性路径 success:false + error='Task not found' 非 error（CC:38 z.string() 无 min + :147-156）")
    void emptyTaskId_returnsBenignTaskNotFound() {
        // WHY: CC inputSchema taskId 为 z.string() 无 min（TaskUpdateTool.ts:38），空串合法 →
        // getTask 空 id 不存在 → { success:false, taskId:'', updatedFields:[], error:'Task not found' }
        // 良性失败（TaskUpdateTool.ts:147-156，非 error tool_result）。
        // Java 旧实现 :380-382 工具层拦截空串（D-TU-1 已删）把良性条件升级为 error，
        // 改变可观察行为（CC 语义：空 taskId 走 Step 2 getTask → 'Task not found'）。
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "")).thenReturn(Optional.empty());

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", ""));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 良性条件非 error
        assertThat(summary(result)).isEqualTo("Task not found");
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(false);
        assertThat(so.get("taskId")).isEqualTo("");
        assertThat(so.get("updatedFields")).isEqualTo(List.of());
        assertThat(so.get("error")).isEqualTo("Task not found");
        assertThat(so.containsKey("statusChange")).isFalse();
    }

    @Test
    @DisplayName("toAutoClassifierInput: 直返 join 无 NAME fallback（CC:114-119；D-TU-2 已删）")
    void toAutoClassifierInput_returnsJoinWithoutNameFallback() {
        // WHY: CC TaskUpdateTool.ts:114-119 parts.join(' ') 无 fallback——缺键时 join 结果含空位，
        // 不回退工具名（旧实现 null input → NAME、空 parts → NAME 均删，D-TU-2）。
        TaskUpdateTool tool = new TaskUpdateTool(mock(TaskService.class), null);

        // null input（CC 不可能路径，zod 校验后必为对象）：null 安全直返空串，不回退 NAME
        assertThat(tool.toAutoClassifierInput(null)).isEmpty();
        // 缺全部键 → join 空列表 = 空串（CC 空数组 join(' ') 语义），非 NAME
        assertThat(tool.toAutoClassifierInput(json.createObjectNode())).isEmpty();
        // 正常路径：taskId + status + subject 空格拼接（CC:115-118）
        JsonNode input = json.createObjectNode()
            .put("taskId", "t-1")
            .put("status", "in_progress")
            .put("subject", "Fix bug");
        assertThat(tool.toAutoClassifierInput(input)).isEqualTo("t-1 in_progress Fix bug");
        // 缺 status/subject → 仅 taskId（CC [input.taskId].join(' ')）
        assertThat(tool.toAutoClassifierInput(json.createObjectNode().put("taskId", "t-1")))
            .isEqualTo("t-1");
    }

    @Test
    @DisplayName("deleted 成功 → isError=false + data='Updated task #t-1 deleted' + structuredOutput{success:true,updatedFields:['deleted'],statusChange:{from,to:'deleted'}}（CC:214-227）")
    void deleted_returnsStructuredStatusChange() {
        // WHY: CC TaskUpdateTool.ts:214-227 deleted 返回结构化 {success, updatedFields:['deleted'], statusChange}。
        // Java 旧代码用 String resultMsg 拍平丢结构化契约（探查 △-6）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        when(taskService.deleteTask("tl-1", "t-1")).thenReturn(true);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "deleted"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // data 通道：CC:384 'Updated task #id ' + updatedFields.join(', ') = "Updated task #t-1 deleted"
        // （对齐后 LLM 可见文本从旧 'Task #t-1 deleted successfully' 变化，plan risks 已标注）
        assertThat(summary(result)).isEqualTo("Updated task #t-1 deleted");
        // structuredOutput 通道
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(true);
        assertThat(so.get("updatedFields")).isEqualTo(List.of("deleted"));
        assertThat(so.containsKey("error")).isFalse();
        Map<?, ?> sc = (Map<?, ?>) so.get("statusChange");
        assertThat(sc.get("from")).isEqualTo("in_progress");
        assertThat(sc.get("to")).isEqualTo("deleted");
    }

    @Test
    @DisplayName("deleted 失败 → isError=false + data='Failed to delete task'（CC:224-226 删除失败非 error）")
    void deletedFailure_returnsFailedToDeleteTask() {
        // WHY: CC TaskUpdateTool.ts:224-226 deleted=false → { success:false, error:'Failed to delete task' }。
        // 删除失败是良性条件非 error；error 文案无 taskId 后缀（精确对齐 CC）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        when(taskService.deleteTask("tl-1", "t-1")).thenReturn(false);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "deleted"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).isEqualTo("Failed to delete task");
        assertThat (ToolResult.presentationMeta(result).get("success")).isEqualTo(false);
        assertThat (ToolResult.presentationMeta(result).get("error")).isEqualTo("Failed to delete task");
        assertThat (ToolResult.presentationMeta(result).get("updatedFields")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("hook 阻塞 → isError=false + structuredOutput{success:false,error}（CC:255-264）")
    void hookBlocked_returnsStructuredSuccessFalse() {
        // WHY: CC hook 阻塞返回 { data: { success: false, error: blockingErrors.join('\n') } } 非 error。
        // Java 旧代码用 ToolResult.error(get(0)) 只取第一个且升级为失败。
        // [hook-aggregate] 聚合语义改为 CC 的 result.blockingError 存在性判断（TaskUpdateTool.ts:248），
        // 且错误经 getTaskCompletedHookMessage 加 'TaskCompleted hook feedback:\n' 前缀（hooks.ts:1928）。
        // 故 hook 必须带 blockingError 才会触发阻塞分支 —— stop("reason") 只设 stopReason 不设
        // blockingError，不再触发阻塞（CC 语义）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(task));

        HookRegistry registry = new HookRegistry();
        registry.register("recorder", event ->
                event.type() == HookEventType.TASK_COMPLETED
                    ? GenericHook.HookResult.stop("hook blocked reason", "hook blocked reason")
                    : GenericHook.HookResult.proceed(),
            HookEventType.TASK_COMPLETED);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, registry);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "completed"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 结构化 success:false 非 error
        assertThat(summary(result)).isEqualTo("TaskCompleted hook feedback:\nhook blocked reason");
        assertThat (ToolResult.presentationMeta(result).get("success")).isEqualTo(false);
        assertThat (ToolResult.presentationMeta(result).get("error"))
            .isEqualTo("TaskCompleted hook feedback:\nhook blocked reason");
        assertThat (ToolResult.presentationMeta(result).get("updatedFields")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("成功路径 → data='Updated task #t-1 subject, status'（空格分隔无括号，CC:384）+ structuredOutput{statusChange}（CC:351-362）")
    void success_returnsUpdatedFieldsWithSpaceSeparator() {
        // WHY: CC TaskUpdateTool.ts:351-362 成功返回 { success:true, updatedFields, statusChange, verificationNudgeNeeded }；
        // mapper :384 'Updated task #id ' + join(', ') 空格分隔无括号（旧实现括号格式是错误）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        Task updated = new Task("t-1", "new subject", "desc", null, null,
            Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(updated));

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode()
                .put("taskId", "t-1")
                .put("subject", "new subject")
                .put("status", "completed"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(summary(result)).isEqualTo("Updated task #t-1 subject, status");
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(true);
        assertThat(so.get("updatedFields")).isEqualTo(List.of("subject", "status"));
        assertThat(so.containsKey("error")).isFalse();
        Map<?, ?> sc = (Map<?, ?>) so.get("statusChange");
        assertThat(sc.get("from")).isEqualTo("in_progress");
        assertThat(sc.get("to")).isEqualTo("completed");
        assertThat(so.get("verificationNudgeNeeded")).isEqualTo(false);
    }

    // ════════════════════════════════════════════════════════════════════════
    // mapper 直接单测（mapToolResultToToolResultBlockParam 五分支，包级 static）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("inputSchema 精确九字段，无 removeBlocks/removeBlockedBy（CC:33-66 z.strictObject）")
    void inputSchema_hasExactlyNineCcFields() {
        // WHY: CC TaskUpdateTool.ts:33-66 inputSchema 是 z.strictObject —— 严格拒绝未知键。
        // Java schema 不得广告 CC 不存在的 removeBlocks/removeBlockedBy 字段（s12 误加已删，
        // 清理项 inputschema-removeblocks），否则 LLM 会在 schema 下发送 CC 会拒绝的键，
        // 造成 CC 行为对齐之外的契约漂移。本测试锁定九字段集合，防止再次引入偏离字段。
        TaskUpdateTool tool = new TaskUpdateTool(mock(TaskService.class), null);

        JsonNode schema = tool.inputSchema();
        JsonNode properties = schema.get("properties");

        Set<String> fields = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
            "taskId", "subject", "description", "activeForm", "status",
            "addBlocks", "addBlockedBy", "owner", "metadata");
        assertThat(fields).doesNotContain("removeBlocks", "removeBlockedBy");

        // required 仅 taskId、additionalProperties:false（对齐 CC z.strictObject 拒绝未知键）
        assertThat(schema.get("required"))
            .isEqualTo(json.createArrayNode().add("taskId"));
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("mapper: success=false 且 error 空 → 回退 'Task #id not found'（CC:373-381）")
    void mapper_notFoundFallbackWhenErrorNull() {
        // WHY: CC:380 content = error || `Task #${taskId} not found` —— error 空时回退占位文案。
        TaskUpdateTool.TaskUpdateOutput out =
            new TaskUpdateTool.TaskUpdateOutput(false, "t-1", List.of(), null, null, null);
        assertThat(TaskUpdateTool.mapToolResultToToolResultBlockParam(out))
            .isEqualTo("Task #t-1 not found");
    }

    @Test
    @DisplayName("mapper: 成功 → 'Updated task #id ' + updatedFields 空格分隔（CC:384）")
    void mapper_successRendersUpdatedFields() {
        // WHY: CC:384 空格分隔无括号（Java 旧实现 'Updated task #X (subject, status)' 括号格式错误）。
        TaskUpdateTool.TaskUpdateOutput out =
            new TaskUpdateTool.TaskUpdateOutput(true, "t-1", List.of("subject", "status"),
                null, null, false);
        assertThat(TaskUpdateTool.mapToolResultToToolResultBlockParam(out))
            .isEqualTo("Updated task #t-1 subject, status");
    }

    @Test
    @DisplayName("mapper: 已 completed 仅改 subject（statusChange=null）不触发 completed 提醒")
    void mapper_noReminderWhenStatusChangeNull() {
        // WHY: 提醒下沉 mapper 并由结构化 statusChange 驱动（concerns#6）。statusChange 仅真实
        // 迁移才非 null（CC updates.status 仅在 status !== existingTask.status 时 set）——
        // 旧实现 newStatus==COMPLETED 无条件触发，'已 completed 仅改 subject' 会误提醒。
        TaskUpdateTool.TaskUpdateOutput out =
            new TaskUpdateTool.TaskUpdateOutput(true, "t-1", List.of("subject"),
                null, null, false);
        assertThat(TaskUpdateTool.mapToolResultToToolResultBlockParam(out))
            .isEqualTo("Updated task #t-1 subject");
    }

    @Test
    @DisplayName("mapper: completed 提醒（statusChange.to=completed + agent swarms + teammate agentId，CC:388-394）")
    void mapper_appendsCompletedReminder() {
        // WHY: CC TaskUpdateTool.ts:388-394 statusChange.to==='completed' && getAgentId() && isAgentSwarmsEnabled()
        // → 追加提醒。[IMP-G3] OD-G2-1 拍板：getAgentId()（teammate.ts:88-92）改由 Java Teammate.getAgentId()
        // 对等表达（in-process TeammateContext ThreadLocal > dynamicTeamContext），不再用 nexusai.agent.name
        // sysprop 代理——测试以 dynamicTeamContext 模拟 running-as-teammate。
        System.setProperty("nexusai.experimental.agent-teams", "true");
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
            "teammateA@t", "teammateA", "t", null, false, null));
        try {
            TaskUpdateTool.StatusChange sc =
                new TaskUpdateTool.StatusChange("in_progress", "completed");
            TaskUpdateTool.TaskUpdateOutput out =
                new TaskUpdateTool.TaskUpdateOutput(true, "t-1", List.of("status"), null, sc, false);
            assertThat(TaskUpdateTool.mapToolResultToToolResultBlockParam(out))
                .isEqualTo("Updated task #t-1 status"
                    + "\n\nTask completed. Call TaskList now to find your next available task or see if your work unblocked others.");
        } finally {
            TaskSystemConfig.clearForTest();
            Teammate.clearDynamicTeamContext();
        }
    }

    @Test
    @DisplayName("mapper: 主线程无 teammate context（即使 sysprop agent-name 已设）不追加 completed 提醒（U-19，CC:389 getAgentId()）")
    void mapper_noTeammateContext_noReminder() {
        // WHY: CC TaskUpdateTool.ts:389 getAgentId()（teammate.ts:88-92）仅 running-as-teammate 返回
        // agentId，主线程 undefined → 不提醒。U-19 弃用 nexusai.agent.name sysprop 代理后，主线程
        // 即使设置了 agent-name 也不再误提醒（旧实现 getAgentName()!=null 会误触发）。
        System.setProperty("nexusai.experimental.agent-teams", "true");
        System.setProperty("nexusai.agent.name", "teammateA");
        try {
            TaskUpdateTool.StatusChange sc =
                new TaskUpdateTool.StatusChange("in_progress", "completed");
            TaskUpdateTool.TaskUpdateOutput out =
                new TaskUpdateTool.TaskUpdateOutput(true, "t-1", List.of("status"), null, sc, false);
            assertThat(TaskUpdateTool.mapToolResultToToolResultBlockParam(out))
                .isEqualTo("Updated task #t-1 status");
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    @Test
    @DisplayName("mapper: nudge NOTE（verificationNudgeNeeded=true，引用 AgentToolConstants.VERIFICATION_AGENT_TYPE，CC:396-398）")
    void mapper_appendsNudgeNote() {
        // WHY: CC TaskUpdateTool.ts:396-398 verificationNudgeNeeded → NOTE 提示。
        // Java 引用 AgentToolConstants.VERIFICATION_AGENT_TYPE 常量而非硬编码 'verification'
        // （CC AgentTool/constants.ts:4）。
        TaskUpdateTool.StatusChange sc =
            new TaskUpdateTool.StatusChange("in_progress", "completed");
        TaskUpdateTool.TaskUpdateOutput out =
            new TaskUpdateTool.TaskUpdateOutput(true, "t-1", List.of("status"), null, sc, true);
        String text = TaskUpdateTool.mapToolResultToToolResultBlockParam(out);
        assertThat(text).startsWith("Updated task #t-1 status");
        assertThat(text).contains(
            "\n\nNOTE: You just closed out 3+ tasks and none of them was a verification step.");
        assertThat(text).contains("subagent_type=\"verification\"");
    }

    // ════════════════════════════════════════════════════════════════════════
    // verificationNudge 门 3 定向测试（execute 层，CC TaskUpdateTool.ts:333-349）
    // ════════════════════════════════════════════════════════════════════════
    // CC 外门四条件（:335-338）：feature('VERIFICATION_AGENT') + tengu_hive_evidence
    // + !context.agentId + updates.status === 'completed'；内层三条件（:341/:344/:345）：
    // allDone + allTasks.length >= 3 + !/verif/i。三用例各自锁定一个外门：
    // ① true 必须外门+内门全真；② false 唯一锁定迁移门（:338）；③ false 唯一锁定主线程门（:337）。

    @Test
    @DisplayName("nudge: 主线程（ctx=null）+ IN_PROGRESS→completed 真实迁移 + 内层全满足 → verificationNudgeNeeded=true（CC:333-349）")
    void nudge_mainThreadMigrationToCompleted_triggers() {
        // WHY: CC TaskUpdateTool.ts:333-349 外门四条件（feature VERIFICATION_AGENT :335 +
        // tengu_hive_evidence :336 + !context.agentId :337 + updates.status==='completed' :338）
        // 全过且内层（allDone :341 + length>=3 :344 + 无 verif 步骤 :345）全满足才置
        // verificationNudgeNeeded=true。Java isMainThread（TaskUpdateTool.java:855-858）对
        // ctx==null（单参 execute）视为主线程，对齐 CC !context.agentId。本测试证明
        // 主线程+真实迁移到 completed 是触发前提，防只有 feature 开关却无迁移时误报。
        System.setProperty("nexusai.feature.verification_agent", "true");
        System.setProperty("nexusai.feature.tengu_hive_evidence", "true");
        try {
            TaskService taskService = mock(TaskService.class);
            Task task = new Task("t-1", "subject", "desc", null, null,
                Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
            Task updated = new Task("t-1", "subject", "desc", null, null,
                Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of());
            // 内层全满足：3 个全 completed 且 subject 无 verif 关键字（CC:341/344/345）
            when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
            when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(updated));
            when(taskService.listTasks("tl-1")).thenReturn(List.of(
                new Task("t-1", "Build the thing", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-2", "Write tests", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-3", "Update docs", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of())));

            TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
            ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
                json.createObjectNode().put("taskId", "t-1").put("status", "completed"));

            // 单参 execute → ctx=null → 主线程（isMainThread: TaskUpdateTool.java:855-858）
            ToolResult<String> result = tool.execute(call);

            Map<String, Object> so = ToolResult.presentationMeta(result);
            assertThat(so.get("verificationNudgeNeeded")).isEqualTo(true);
            // data 通道 NOTE 渲染（CC:396-398，subagent_type 引用 AgentToolConstants.VERIFICATION_AGENT_TYPE）
            assertThat(summary(result)).contains("NOTE: You just closed out 3+ tasks");
            assertThat(summary(result)).contains("subagent_type=\"verification\"");
            // 顺带：真实迁移到 completed → statusChange 存在 + updatedFields 含 status（nudge 前提）
            assertThat(so.get("updatedFields")).isEqualTo(List.of("status"));
            assertThat(so.containsKey("statusChange")).isTrue();
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    @Test
    @DisplayName("nudge: 已 completed 仅改 subject（无 status 迁移）→ verificationNudgeNeeded=false（CC:338 门短路）")
    void nudge_alreadyCompletedSubjectOnly_doesNotTrigger() {
        // WHY: CC TaskUpdateTool.ts:230/:267 — updates.status 仅在真实迁移（status !==
        // existingTask.status）块内 set；已 completed 仅改 subject → updates.status 未定义 →
        // :338 门短路。nudge 只在「状态真迁移到 completed」时提示，防 LLM 补一句 subject
        // 被误判为收尾闭环。listTasks 故意 mock 成内层可全过 —— 唯一锁定迁移门
        // （TaskUpdateTool.java:728-729）是短路源，而非内层 allDone/>=3/!verif 在兜底。
        System.setProperty("nexusai.feature.verification_agent", "true");
        System.setProperty("nexusai.feature.tengu_hive_evidence", "true");
        try {
            TaskService taskService = mock(TaskService.class);
            Task task = new Task("t-1", "subject", "desc", null, null,
                Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of());
            when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
            when(taskService.listTasks("tl-1")).thenReturn(List.of(
                new Task("t-1", "Build the thing", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-2", "Write tests", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-3", "Update docs", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of())));

            TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
            ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
                json.createObjectNode().put("taskId", "t-1").put("subject", "new subject"));

            ToolResult<String> result = tool.execute(call);

            Map<String, Object> so = ToolResult.presentationMeta(result);
            assertThat(so.get("verificationNudgeNeeded")).isEqualTo(false);
            assertThat(so.get("updatedFields")).isEqualTo(List.of("subject"));
            assertThat(so.containsKey("statusChange")).isFalse(); // 无迁移 → 无 statusChange
            assertThat(summary(result)).doesNotContain("NOTE: You just closed out");
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    @Test
    @DisplayName("nudge: 子代理 ctx（agentId≠sessionId）即使真实迁移 + 内层全满足 → verificationNudgeNeeded=false（CC:337 门短路）")
    void nudge_subagentCtx_doesNotTrigger() {
        // WHY: CC TaskUpdateTool.ts:337 !context.agentId — 子代理/subagent 完成任务不该触发主线程
        // 闭环 nudge。Java isMainThread（TaskUpdateTool.java:855-858）：agentId==sessionId 才主线程；
        // 子代理 ctx（createSubagentContext 随机新 agentId）agentId≠sessionId → nudgeGateMainThread=false。
        // 内层条件故意全满足以证明短路源是主线程门而非内层；verify never() 双证 listTasks 未走到。
        System.setProperty("nexusai.feature.verification_agent", "true");
        System.setProperty("nexusai.feature.tengu_hive_evidence", "true");
        try {
            TaskService taskService = mock(TaskService.class);
            Task task = new Task("t-1", "subject", "desc", null, null,
                Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
            Task updated = new Task("t-1", "subject", "desc", null, null,
                Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of());
            when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
            when(taskService.updateTask(any(), any(), Mockito.anyMap())).thenReturn(Optional.of(updated));
            // 内层故意全满足（若主线程门被绕过，内层会放行 → 测试变红）
            when(taskService.listTasks("tl-1")).thenReturn(List.of(
                new Task("t-1", "Build the thing", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-2", "Write tests", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of()),
                new Task("t-3", "Update docs", "d", null, null,
                    Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of())));

            TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
            ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
                json.createObjectNode().put("taskId", "t-1").put("status", "completed"));
            // 子代理 ctx：agentId≠sessionId（对齐 createSubagentContext 随机新 UUID，ToolUseContext.java:283-290）
            ToolUseContext subCtx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

            ToolResult<String> result = tool.execute(call, subCtx);

            Map<String, Object> so = ToolResult.presentationMeta(result);
            assertThat(so.get("verificationNudgeNeeded")).isEqualTo(false);
            assertThat(summary(result)).doesNotContain("NOTE: You just closed out");
            // 主线程门短路 → listTasks 从未被调用（若被调用说明主线程门被绕过）
            verify(taskService, never()).listTasks("tl-1");
        } finally {
            TaskSystemConfig.clearForTest();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // addBlocks/addBlockedBy filter 计数定向测试（CC TaskUpdateTool.ts:301-324，S14）
    // ════════════════════════════════════════════════════════════════════════
    // CC: newBlocks = addBlocks.filter(id => !existingTask.blocks.includes(id)) —— 按 filter
    // 结果计数，blockTask 返回值（false = 任务不存在，tasks.ts:467-469）不影响 push 'blocks'。
    // Java 旧实现按 blockTask()==true 计入（△ B11/B12），引用不存在任务时 updatedFields
    // 缺 'blocks'/'blockedBy'，与 CC 可观察行为偏离（CC 报更新成功）。

    @Test
    @DisplayName("addBlocks: 引用不存在任务 → blockTask 返回 false 仍计入 updatedFields['blocks'] + success:true（CC:301-311）")
    void addBlocks_nonexistentTask_stillCountsBlocks() {
        // WHY: CC TaskUpdateTool.ts:301-311 newBlocks 按 filter（排除已存在）计数，循环内
        // await blockTask(...) 不检查返回值；newBlocks 非空 → push 'blocks'。blockTask 返回
        // false（目标任务不存在，tasks.ts:467-469）时 CC 仍报更新成功 —— Java 旧实现
        // blockTask()==true 才计入 newBlocks，场景差异（E3 验收标准 1）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        // blockTask 返回 false = 目标任务不存在（CC tasks.ts:467-469 / Java TaskService.java:843）
        when(taskService.blockTask("tl-1", "t-1", "missing-99")).thenReturn(false);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1")
                .set("addBlocks", json.createArrayNode().add("missing-99")));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(true);
        // CC: filter 只排除已存在 block，引用不存在任务仍进 newBlocks → push 'blocks'
        assertThat(so.get("updatedFields")).isEqualTo(List.of("blocks"));
        assertThat(summary(result)).isEqualTo("Updated task #t-1 blocks");
        verify(taskService).blockTask("tl-1", "t-1", "missing-99");
    }

    @Test
    @DisplayName("addBlockedBy: 引用不存在任务 → blockTask 返回 false 仍计入 updatedFields['blockedBy'] + success:true（CC:314-324）")
    void addBlockedBy_nonexistentTask_stillCountsBlockedBy() {
        // WHY: CC TaskUpdateTool.ts:314-324 反向 blockTask(taskListId, blockerId, taskId)，
        // 计数同 addBlocks（filter 结果，不依赖返回值）。blockTask 返回 false（源任务=bloker
        // 不存在，tasks.ts:467-469）时 CC 仍 push 'blockedBy'（E3 验收标准 2）。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));
        // 方向反转：blocker 不存在 → false（CC:319 blockTask(taskListId, blockerId, taskId)）
        when(taskService.blockTask("tl-1", "missing-99", "t-1")).thenReturn(false);

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1")
                .set("addBlockedBy", json.createArrayNode().add("missing-99")));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(true);
        assertThat(so.get("updatedFields")).isEqualTo(List.of("blockedBy"));
        assertThat(summary(result)).isEqualTo("Updated task #t-1 blockedBy");
        verify(taskService).blockTask("tl-1", "missing-99", "t-1");
    }

    @Test
    @DisplayName("addBlocks: 已存在的 block 被 filter 排除 → 不 push 'blocks' 且不重复 blockTask（CC:302-304）")
    void addBlocks_alreadyExistingBlock_filteredOut() {
        // WHY: CC:302-304 newBlocks = addBlocks.filter(id => !existingTask.blocks.includes(id))
        // —— 已存在引用被排除；newBlocks 空 → 不 push 'blocks'。Java filter 语义已同构，
        // 本测试锁定过滤边界，防止计数口径修正时破坏 filter。
        TaskService taskService = mock(TaskService.class);
        Task task = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of("t-2"), List.of(), Map.of());
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(task));

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1")
                .set("addBlocks", json.createArrayNode().add("t-2")));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        Map<String, Object> so = ToolResult.presentationMeta(result);
        assertThat(so.get("success")).isEqualTo(true);
        assertThat(so.get("updatedFields")).isEqualTo(List.of());
        // 已存在引用被 filter 排除 → blockTask 不被调用（CC:305-307 只遍历 newBlocks）
        verify(taskService, never()).blockTask(any(), any(), any());
    }

    /** [IMP-C2] successWithStructuredOutput 折入 data(Map) 后，模型侧渲染文本在 "summary" 键。 */
    private static String summary(ToolResult<?> result) {
        Object data = result.data();
        if (data instanceof Map<?, ?> m && m.containsKey("summary")) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(data);
    }
}

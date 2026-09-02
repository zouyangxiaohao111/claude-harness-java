package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * TaskUpdate 工具 · 对齐 CC TaskUpdateTool.ts（PROMPT 全量；mapToolResult 分层/nudge 门/mailbox 见方法备注）
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = 'TaskUpdate'</td><td>TaskUpdateTool.ts:89</td><td>{@link #name()}</td></tr>
 *   <tr><td>inputSchema 9 字段（含 status='deleted'）</td><td>TaskUpdateTool.ts:33-66</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema 6 字段</td><td>TaskUpdateTool.ts:69-83</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>userFacingName() → 'TaskUpdate'</td><td>TaskUpdateTool.ts:104-105</td><td>{@link #userFacingName()}</td></tr>
 *   <tr><td>shouldDefer = true</td><td>TaskUpdateTool.ts:107</td><td>{@link #shouldDefer(JsonNode)}</td></tr>
 *   <tr><td>isEnabled() = isTodoV2Enabled()</td><td>TaskUpdateTool.ts:108-109</td><td>{@link #isEnabled()}</td></tr>
 *   <tr><td>isConcurrencySafe() → true</td><td>TaskUpdateTool.ts:111-112</td><td>{@link #isConcurrencySafe(JsonNode)}</td></tr>
 *   <tr><td>toAutoClassifierInput</td><td>TaskUpdateTool.ts:114-118</td><td>{@link #toAutoClassifierInput(JsonNode)}</td></tr>
 *   <tr><td>renderToolUseMessage → null</td><td>TaskUpdateTool.ts:120-121</td><td>{@link #renderToolUseMessage(JsonNode)}</td></tr>
 *   <tr><td>auto-expand task list</td><td>TaskUpdateTool.ts:140-143</td><td>{@link #execute(ToolUseBlock, ToolUseContext)} 已实现 setAppState（session AppState 写入；UI 投递待 Stage 3.3）</td></tr>
 *   <tr><td>status='deleted' 处理</td><td>TaskUpdateTool.ts:214-227</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>auto-set owner (in_progress + swarms)</td><td>TaskUpdateTool.ts:188-198</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>metadata merge (null 删除键)</td><td>TaskUpdateTool.ts:200-209</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>status=completed → TaskCompleted hooks</td><td>TaskUpdateTool.ts:232-265</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>owner change → mailbox notify</td><td>TaskUpdateTool.ts:277-298</td><td>{@link #execute(ToolUseBlock)} Step 7（S12 已接线：{@link com.nexusai.application.agent.team.TeammateMailbox#writeToMailbox}，teammateMailbox.ts:134-192；判据 owner 非空 && swarms，OD-TU-2b）</td></tr>
 *   <tr><td>addBlocks/addBlockedBy 处理</td><td>TaskUpdateTool.ts:301-324</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>verification nudge</td><td>TaskUpdateTool.ts:333-361</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>task completion reminder</td><td>TaskUpdateTool.ts:388-395</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 * </table>
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li>mailbox notify：已实现（S12，execute Step 7，消息体 TaskUpdateTool.ts:280-287 + teammateMailbox.ts:953-960，inbox 路径 {configHome}/teams/{taskListId}/inboxes/{owner}.json）；跨进程消费（CC attachments.ts:3532）依赖 CC CLI 读同一文件</li>
 *   <li>auto-expand task list：已实现（session AppState 写入，对齐 CC TaskUpdateTool.ts:140-143）；UI 投递（EventPublisher→前端 React）待 Stage 3.3</li>
 * </ul>
 *
 * @see Task
 * @see TaskService
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskUpdateTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskUpdateTool.class);
    private static final String NAME = "TaskUpdate";

    /** 对齐 CC TaskUpdateTool prompt.ts:1 DESCRIPTION = 'Update a task in the task list' */
    private static final String DESCRIPTION = "Update a task in the task list";

    /**
     * PROMPT · 对齐 CC TaskUpdateTool prompt.ts:3-77 全量，逐字。
     *
     * <p>CC 真源（grep 实证 prompt.ts:3-77）：intro + ## When to Use This Tool 三段
     * （Mark tasks as resolved / Delete tasks / Update task details）+ ## Fields You Can
     * Update 8 字段 + ## Status Workflow + ## Staleness + ## Examples 5 个 JSON 代码块。
     * CC 中 \`pending\` 等转义反引号在 Java 文本块写为直接 `` ` ``。
     */
    private static final String PROMPT = """
        Use this tool to update a task in the task list.

        ## When to Use This Tool

        **Mark tasks as resolved:**
        - When you have completed the work described in a task
        - When a task is no longer needed or has been superseded
        - IMPORTANT: Always mark your assigned tasks as resolved when you finish them
        - After resolving, call TaskList to find your next task

        - ONLY mark a task as completed when you have FULLY accomplished it
        - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
        - When blocked, create a new task describing what needs to be resolved
        - Never mark a task as completed if:
          - Tests are failing
          - Implementation is partial
          - You encountered unresolved errors
          - You couldn't find necessary files or dependencies

        **Delete tasks:**
        - When a task is no longer relevant or was created in error
        - Setting status to `deleted` permanently removes the task

        **Update task details:**
        - When requirements change or become clearer
        - When establishing dependencies between tasks

        ## Fields You Can Update

        - **status**: The task status (see Status Workflow below)
        - **subject**: Change the task title (imperative form, e.g., "Run tests")
        - **description**: Change the task description
        - **activeForm**: Present continuous form shown in spinner when in_progress (e.g., "Running tests")
        - **owner**: Change the task owner (agent name)
        - **metadata**: Merge metadata keys into the task (set a key to null to delete it)
        - **addBlocks**: Mark tasks that cannot start until this one completes
        - **addBlockedBy**: Mark tasks that must complete before this one can start

        ## Status Workflow

        Status progresses: `pending` → `in_progress` → `completed`

        Use `deleted` to permanently remove a task.

        ## Staleness

        Make sure to read a task's latest state using `TaskGet` before updating it.

        ## Examples

        Mark task as in progress when starting work:
        ```json
        {"taskId": "1", "status": "in_progress"}
        ```

        Mark task as completed after finishing work:
        ```json
        {"taskId": "1", "status": "completed"}
        ```

        Delete a task:
        ```json
        {"taskId": "1", "status": "deleted"}
        ```

        Claim a task by setting owner:
        ```json
        {"taskId": "1", "owner": "my-name"}
        ```

        Set up task dependencies:
        ```json
        {"taskId": "2", "addBlockedBy": ["1"]}
        ```
        """;

    private final TaskService taskPersistence;
    private final HookRegistry hookRegistry;

    /** s12-3.2: Spring 构造器注入（按类型解析，无 taskListId）· 对齐 CC TaskUpdateTool call() 内逐次 getTaskListId() */
    public TaskUpdateTool(TaskService taskPersistence, HookRegistry hookRegistry) {
        this.taskPersistence = taskPersistence;
        this.hookRegistry = hookRegistry;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tool 接口实现
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    /**
     * 工具 prompt · 对齐 CC TaskUpdateTool.ts:95-97 prompt() → PROMPT（CC prompt.ts:3-77 全量）
     */
    @Override
    public String prompt() {
        if (log.isDebugEnabled()) {
            log.debug("TaskUpdate.prompt() 返回 {} 字符 PROMPT（CC prompt.ts:3-77 多段：When to Use/Fields/Status Workflow/Staleness/Examples）",
                PROMPT.length());
        }
        return PROMPT;
    }

    /**
     * 用户可见名 · 对齐 CC TaskUpdateTool.ts:104-105 userFacingName() → 'TaskUpdate'
     */
    @Override
    public String userFacingName() {
        // 对齐 CC TaskUpdateTool.ts:105: return 'TaskUpdate'
        return "TaskUpdate";
    }

    /**
     * 搜索提示 · 对齐 CC TaskUpdateTool.ts:90 {@code searchHint: 'update a task'}。
     *
     * <p>CC 真源（grep 实证 TaskUpdateTool.ts:90）：buildTool 对象字面量字段
     * {@code searchHint: 'update a task'}。供 ToolSearch 关键词匹配（ToolSearchTool.ts 消费方
     * 待 OPD-23 接线）。与同批 TaskCreate/Get/List/TodoWrite 保持一致，TaskUpdateTool 补齐
     * 此前缺失的 override（Q-1）。
     */
    @Override
    public String searchHint() {
        // 对齐 CC TaskUpdateTool.ts:90: searchHint: 'update a task'
        return "update a task";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输入 Schema · 对齐 CC TaskUpdateTool.ts:33-66 inputSchema
     *
     * <p>CC inputSchema 9 字段（TaskUpdateTool.ts:37-65）：
     * <pre>
     * z.strictObject({
     *   taskId, subject, description, activeForm,
     *   status (含 'deleted'), addBlocks, addBlockedBy, owner, metadata
     * })
     * </pre>
     *
     * <p>status 为工具输入层扩展枚举 TaskUpdateStatusSchema
     * （CC TaskUpdateTool.ts:35 {@code TaskStatusSchema().or(z.literal('deleted'))}）——
     * <b>'deleted' 是删除 action 而非存储态</b>：execute() 在 {@code status === 'deleted'}
     * 分支（CC TaskUpdateTool.ts:214-227）走 {@code deleteTask()} 物理 unlink + 提前返回，
     * 从不写入 'deleted' 存储值；存储态严格 3 值（CC tasks.ts:71-73 TaskStatusSchema）。
     * 与存储枚举 TaskStatus（已删 DELETED）分层一致，本项不动。
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("taskId", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "The ID of the task to update"));
        properties.set("subject", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "New subject for the task"));
        properties.set("description", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "New description for the task"));
        properties.set("activeForm", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "Present continuous form shown in spinner when in_progress (e.g., 'Running tests')"));

        // 对齐 CC TaskUpdateTool.ts:35 TaskUpdateStatusSchema = TaskStatusSchema().or(z.literal('deleted'))
        // —— 仅工具输入层接受 'deleted'（删除 action，execute :484 前置拦截 deleteTask 物理 unlink），
        // 非存储态；存储态严格 3 值（CC tasks.ts:71-73 TaskStatusSchema）。与存储枚举 TaskStatus（已删 DELETED）分层一致。
        ObjectNode statusProp = JSON.createObjectNode();
        statusProp.put("type", "string");
        statusProp.set("enum", JSON.createArrayNode()
            .add("pending").add("in_progress").add("completed").add("deleted"));
        statusProp.put("description", "New status for the task (including 'deleted' to permanently remove; 'deleted' is a delete action, never a stored status)");
        properties.set("status", statusProp);

        // 对齐 CC TaskUpdateTool.ts:50-53 addBlocks
        ObjectNode addBlocksProp = JSON.createObjectNode();
        addBlocksProp.put("type", "array");
        addBlocksProp.set("items", JSON.createObjectNode().put("type", "string"));
        addBlocksProp.put("description", "Task IDs that this task blocks");
        properties.set("addBlocks", addBlocksProp);

        // 对齐 CC TaskUpdateTool.ts:54-57 addBlockedBy
        ObjectNode addBlockedByProp = JSON.createObjectNode();
        addBlockedByProp.put("type", "array");
        addBlockedByProp.set("items", JSON.createObjectNode().put("type", "string"));
        addBlockedByProp.put("description", "Task IDs that block this task");
        properties.set("addBlockedBy", addBlockedByProp);

        // 对齐 CC TaskUpdateTool.ts:58 owner
        properties.set("owner", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "New owner for the task"));

        // 对齐 CC TaskUpdateTool.ts:59-64 metadata
        properties.set("metadata", JSON.createObjectNode()
            .put("type", "object")
            .put("description", "Metadata keys to merge into the task. Set a key to null to delete it."));

        schema.set("properties", properties);
        schema.set("required", JSON.createArrayNode().add("taskId"));
        schema.put("additionalProperties", false);

        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TaskUpdateTool.ts:69-83 outputSchema
     *
     * <p>CC outputSchema 6 字段：
     * <pre>
     * z.object({
     *   success, taskId, updatedFields, error, statusChange, verificationNudgeNeeded
     * })
     * </pre>
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("success", JSON.createObjectNode().put("type", "boolean"));
        properties.set("taskId", JSON.createObjectNode().put("type", "string"));
        properties.set("updatedFields", JSON.createObjectNode()
            .put("type", "array")
            .set("items", JSON.createObjectNode().put("type", "string")));
        properties.set("error", JSON.createObjectNode().put("type", "string"));
        ObjectNode statusChange = JSON.createObjectNode();
        statusChange.put("type", "object");
        ObjectNode scProps = JSON.createObjectNode();
        scProps.set("from", JSON.createObjectNode().put("type", "string"));
        scProps.set("to", JSON.createObjectNode().put("type", "string"));
        statusChange.set("properties", scProps);
        properties.set("statusChange", statusChange);
        properties.set("verificationNudgeNeeded", JSON.createObjectNode().put("type", "boolean"));

        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限 / 分类
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("task_update_allow"),
            null, false, null, List.of());
    }

    /**
     * 自动分类器输入 · 对齐 CC TaskUpdateTool.ts:114-119 toAutoClassifierInput()
     *
     * <p>CC 源码：{@code const parts = [input.taskId]; if (input.status) parts.push(input.status);
     * if (input.subject) parts.push(input.subject); return parts.join(' ')} — taskId + status + subject
     * 空格拼接，<b>无 fallback</b>（D-TU-2 已删）：缺键时 join 结果含空位，不回退工具名 NAME。
     * Java 侧 null 安全返回空串（对齐 CC 空数组 join(' ') 结果）；CC 输入经 zod 校验恒非 null。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC TaskUpdateTool.ts:115-118；null 仅防御（CC 不可能路径）
        if (input == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (input.has("taskId")) parts.add(input.get("taskId").asText());
        if (input.has("status")) parts.add(input.get("status").asText());
        if (input.has("subject")) parts.add(input.get("subject").asText());
        String joined = String.join(" ", parts);
        if (log.isDebugEnabled()) {
            log.debug("TaskUpdate 分类器输入拼接: 输入键 taskId/status/subject 命中 → '{}'", joined);
        }
        return joined;
    }

    @Override
    public String renderToolUseMessage(JsonNode input) {
        // 对齐 CC TaskUpdateTool.ts:121: return null
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 核心执行逻辑 · 对齐 CC TaskUpdateTool.ts:123-406
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [H6-FIX] 单参执行 · 委托 2 参版本（无 ctx → 场景如直接调用/测试，permissionMode/abortController 为 null）。
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行 TaskUpdate · 对齐 CC TaskUpdateTool.ts:123-406（PROMPT/DESCRIPTION 全量移植；
     * mapToolResult 分层/nudge 门/mailbox 等执行层差异见上方类注释「已知限制」与各 Step 备注）。
     *
     * <p>[H6-FIX] 由单参 {@link #execute(ToolUseBlock)} 委托升级到 {@code execute(call, ctx)}：
     * StreamingToolExecutor:1414 以 3 参 {@code tool.execute(call, ctx, onProgress)} 派发 →
     * 本 2 参重载可拿到真实 {@link ToolUseContext}（permissionMode / abortController），
     * 对齐 CC executeTaskCompletedHooks 9 参（utils/hooks.ts:3789-3799）——实际 9 形参
     * 含 timeoutMs（修正前误写 8 参）；不再传死 null
     * （CHANGELOG 0.2.29 H6-6）。
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();

        // Step 1: 解析 taskId（对齐 CC TaskUpdateTool.ts:125）
        // D-TU-1 已删：CC inputSchema taskId 为 z.string() 无 min（TaskUpdateTool.ts:38），空串合法 →
        // 不在此拦截，空/空白 taskId 走 Step 2 getTask 良性路径 → {success:false, error:'Task not found'}
        // 非 error（TaskUpdateTool.ts:146-156）。
        String taskId = input.has("taskId") ? input.get("taskId").asText() : "";

        // Step 2: 检查任务是否存在（对齐 CC TaskUpdateTool.ts:146-156）
        // CC: const taskListId = getTaskListId(); const existingTask = await getTask(taskListId, taskId)
        // 逐次动态解析列表 ID（对齐 CC TaskUpdateTool.ts:137 getTaskListId()）
        String listId = TaskService.getTaskListId();
        if (log.isDebugEnabled()) {
            log.debug("TaskUpdate 解析列表 ID {}，处理任务 {}", listId, taskId);
        }

        // Auto-expand task list（对齐 CC TaskUpdateTool.ts:139-143）
        // CC 真源（grep 实证，不信注释）：
        //   context.setAppState(prev => {
        //     if (prev.expandedView === 'tasks') return prev          // :141
        //     return { ...prev, expandedView: 'tasks' as const }      // :142
        //   })
        //
        // 位置语义：在 taskId 解析 + getTaskListId 之后、getTask 存在性检查之前（对齐 CC :139-143）——
        // 与 TaskCreate（创建成功后才展开）不同，更新一进来就展开。
        // [R32-b15 Stage 3.2 C2] Java 已有 session AppState 基础设施（LlmAgentLoop.appStateRef +
        // setAppState/getAppStateSnapshot，经 ToolUseContext.getAppState/setAppState 桥接字段注入，
        // LlmAgentLoop.java:3442-3445）——此处落地真实调用，不再留 TODO。
        // guard 对齐 CC :141（prev.expandedView==='tasks' 则 no-op 返回原引用），
        // 置位对齐 CC :142（否则拷贝 prev + 置 'tasks'）。
        // 残留 UI 投递缺口：expandedView 写入 JVM 内 session appStateRef（LlmAgentLoop.java:452），
        // 尚无 EventPublisher/STOMP 通道推到前端 React（LlmAgentLoop.java:528-529 明示 Stage 3.3 对接）；
        // 不伪造投递基础设施，也不因投递缺口删除 CC 扩展点。
        if (ctx != null) {
            ctx.setAppState().accept(prev -> {
                if ("tasks".equals(prev.get("expandedView"))) {
                    // 对齐 CC TaskUpdateTool.ts:141 return prev（expandedView 已是 'tasks'，no-op）
                    return prev;
                }
                // 对齐 CC TaskUpdateTool.ts:142 return { ...prev, expandedView: 'tasks' }
                // CC original: expandedView（AppStateStore.ts:95 'none' | 'tasks' | 'teammates'；默认 'none' :476）
                Map<String, Object> next = new LinkedHashMap<>(prev);
                next.put("expandedView", "tasks");
                return next;
            });
            if (log.isDebugEnabled()) {
                log.debug("[TaskUpdate] 展开任务视图 expandedView→tasks, session={}", ctx.sessionId());
            }
        }

        Optional<Task> taskOpt = taskPersistence.getTask(listId, taskId);
        if (taskOpt.isEmpty()) {
            // 对齐 CC TaskUpdateTool.ts:147-156: task not found → { data: { success: false, taskId, updatedFields: [], error: 'Task not found' } }
            // 非 error（良性条件，CC mapper 注释明示 "Task not found" 不触发 sibling tool 取消），
            // 结构化 success:false 经 successWithOutput 双通道：data=渲染文本给 LLM，
            // structuredOutput=结构化 JSON 给消费方。'Task not found' 文案精确对齐 CC（无 taskId 后缀）。
            log.info("TaskUpdate 未找到任务: taskId={} success=false updatedFields=[] error='Task not found'", taskId);
            return successWithOutput(call.id(),
                new TaskUpdateOutput(false, taskId, List.of(), "Task not found", null, null));
        }
        Task existingTask = taskOpt.get();
        // 对齐 CC：mailbox 通知消息体使用 getTask 时点的 subject/description
        // （TaskUpdateTool.ts:282-283 existingTask.subject/description），
        // 后续 updateTask 落盘的新值不影响通知内容。
        String originalSubject = existingTask.subject();
        String originalDescription = existingTask.description();

        // Step 3: 收集更新字段（对齐 CC TaskUpdateTool.ts:158-159 updatedFields）
        List<String> updatedFields = new ArrayList<>();
        Map<String, Object> partialUpdates = new HashMap<>();

        // 3a: 更新基本字段（对齐 CC TaskUpdateTool.ts:161-183）
        if (input.has("subject")) {
            String subject = input.get("subject").asText();
            if (!subject.equals(existingTask.subject())) {
                partialUpdates.put("subject", subject);
                updatedFields.add("subject");
            }
        }
        if (input.has("description")) {
            String desc = input.get("description").asText();
            if (!desc.equals(existingTask.description())) {
                partialUpdates.put("description", desc);
                updatedFields.add("description");
            }
        }
        if (input.has("activeForm")) {
            String af = input.get("activeForm").asText();
            if (!af.equals(existingTask.activeForm())) {
                partialUpdates.put("activeForm", af);
                updatedFields.add("activeForm");
            }
        }
        if (input.has("owner")) {
            String owner = input.get("owner").asText();
            if (!owner.equals(existingTask.owner())) {
                partialUpdates.put("owner", owner);
                updatedFields.add("owner");
            }
        }

        // 3b: Auto-set owner（对齐 CC TaskUpdateTool.ts:188-198）
        // CC: if (isAgentSwarmsEnabled() && status === 'in_progress'
        //     && owner === undefined && !existingTask.owner)
        String statusStr = input.has("status") ? input.get("status").asText() : null;
        if (TaskSystemConfig.isAgentSwarmsEnabled()
            && "in_progress".equals(statusStr)
            && !input.has("owner")
            && existingTask.owner() == null) {
            String agentName = TaskSystemConfig.getAgentName();
            if (agentName != null && !agentName.isBlank()) {
                partialUpdates.put("owner", agentName);
                updatedFields.add("owner");
            }
        }

        // 3c: Metadata merge（对齐 CC TaskUpdateTool.ts:200-210）
        // CC: null value = delete key, non-null = set key
        if (input.has("metadata") && input.get("metadata").isObject()) {
            Map<String, Object> metadataUpdates = new HashMap<>();
            JsonNode metadataNode = input.get("metadata");
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().isNull()) {
                    metadataUpdates.put(entry.getKey(), null); // null = 删除键
                } else {
                    metadataUpdates.put(entry.getKey(), jsonNodeToObject(entry.getValue()));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("TaskUpdate 解析 metadata 更新: {} 个键（嵌套对象/数组以 Map/List 递归保留，"
                        + "对齐 CC TaskUpdateTool.ts:60 z.unknown 原样存储）；null 值键为删除语义，随后 mergeMetadata 合并",
                    metadataUpdates.size());
            }
            // 对齐 CC TaskUpdateTool.ts:209: merged metadata 全量替换
            Task mergedTask = existingTask.mergeMetadata(metadataUpdates);
            partialUpdates.put("metadata", mergedTask.metadata());
            updatedFields.add("metadata");
            // 更新 existingTask 引用用于后续判断
            existingTask = mergedTask;
        }

        // Step 4: 处理 status='deleted'（对齐 CC TaskUpdateTool.ts:214-227）
        // CC: if (status === 'deleted') { deleteTask(); return early }
        if ("deleted".equals(statusStr)) {
            boolean deleted = taskPersistence.deleteTask(listId, taskId);
            // 对齐 CC TaskUpdateTool.ts:216-226: → { success, updatedFields: ['deleted'],
            //   error?, statusChange: { from: existingTask.status, to: 'deleted' } }
            // 删除失败也是良性条件（CC 返回非 error）。渲染文本归 mapper mapToolResultToToolResultBlockParam。
            log.info("TaskUpdate 删除任务: taskId={} success={} updatedFields={} error={}",
                taskId, deleted,
                deleted ? List.of("deleted") : List.of(),
                deleted ? null : "Failed to delete task");
            return successWithOutput(call.id(), new TaskUpdateOutput(
                deleted, taskId,
                deleted ? List.of("deleted") : List.of(),
                deleted ? null : "Failed to delete task",
                deleted ? new StatusChange(existingTask.status().toValue(), "deleted") : null,
                null));
        }

        // Step 5: 处理 status 变更（对齐 CC TaskUpdateTool.ts:228-269）
        Task.TaskStatus oldStatus = existingTask.status();
        Task.TaskStatus newStatus = oldStatus;

        if (statusStr != null && !"deleted".equals(statusStr)) {
            newStatus = Task.TaskStatus.fromString(statusStr);
            if (newStatus == null) {
                // Task.fromString 严格化后非法值返回 null（对齐 CC safeParse→null，tasks.ts:333-339；
                // 原实现抛 IllegalArgumentException 由 catch 捕获）。null 一律按「非法 status」返回
                // error，与 CC TaskUpdateStatusSchema.parse 失败返回 error 语义一致（TaskUpdateTool.ts:33-49）。
                return ToolResult.error(call.id(), "Invalid status: " + statusStr);
            }

            if (newStatus != oldStatus) {
                // 5a: status → completed → TaskCompleted hooks（对齐 CC TaskUpdateTool.ts:232-265）
                if (newStatus == Task.TaskStatus.COMPLETED) {
                    List<String> blockingErrors = new ArrayList<>();
                    if (hookRegistry != null) {
                        try {
                            // CC: executeTaskCompletedHooks(taskId, subject, description,
                            //     getAgentName(), getTeamName(), permissionMode, abortSignal,
                            //     timeoutMs, toolUseContext)
                            //     (utils/hooks.ts:3789-3799 9 参；timeoutMs Java 不传→默认 TOOL_HOOK_EXECUTION_TIMEOUT_MS)
                            //
                            // [H6] 9 参补齐: HookEvent.taskCompleted 新增 permissionMode + abortController
                            // 重载。
                            // [H6-FIX] TaskUpdateTool 现 override execute(call, ctx) —— 从 ToolUseContext
                            // 拿真实 permissionMode / abortController（对齐 CC 9 参），不再是死 null
                            // （CHANGELOG 0.2.29 H6-6）。toolUseContext 本身不可序列化进 hook 事件，
                            // 仍传 null（HookEvent 无该字段）。
                            String agentName = TaskSystemConfig.getAgentName();
                            String teamName = TaskSystemConfig.getTeamName();
                            String permissionMode = ctx != null && ctx.permissionMode() != null
                                ? ctx.permissionMode().name() : null;
                            AbortController abortController = ctx != null ? ctx.abortController() : null;
                            // [S15] session_id 补传（OD-TU-4 工具侧落地）：与 TaskCreated 同构，
                            // CC executeTaskCompletedHooks 的 hookInput.session_id 恒有
                            // （hooks.ts:315/3800-3808）；Java 取 ctx.sessionId()（主会话 UUID，
                            // 子 Agent 上下文继承主会话，ToolUseContext.with :1314）；
                            // ctx == null（单参 execute 测试路径）维持 null 回退。
                            String sessionId = ctx != null ? ctx.sessionId() : null;
                            HookEvent event = HookEvent.taskCompleted(
                                taskId, existingTask.subject(), existingTask.description(),
                                agentName, teamName, sessionId, null,
                                permissionMode, abortController);
                            if (log.isDebugEnabled()) {
                                log.debug("TaskUpdate 触发 TaskCompleted hook 事件构造: taskId={} sessionId={}"
                                    + "（CC session_id 恒有, hooks.ts:315）", taskId, sessionId);
                            }
                            log.info("STOP_HOOK TaskUpdate status→completed: taskId={} 触发 TaskCompleted hook"
                                + " (permissionMode={}, abortController={})",
                                taskId, permissionMode, abortController != null
                                    ? ("cancelled=" + abortController.isCancelled()) : "null");
                            // 对齐 CC TaskUpdateTool.ts:247-253: for await (const result of generator) {
                            //   if (result.blockingError) blockingErrors.push(getTaskCompletedHookMessage(result.blockingError))
                            // 收集全部 hook 的 blockingError（executeEventAll 暴露全量结果，非折叠首个），
                            // 每条经 getTaskCompletedHookMessage 加 'TaskCompleted hook feedback:\n' 前缀（hooks.ts:1928）。
                            for (GenericHook.HookResult hookResult : hookRegistry.executeEventAll(event)) {
                                if (hookResult != null && hookResult.blockingError() != null) {
                                    blockingErrors.add(getTaskCompletedHookMessage(hookResult.blockingError()));
                                }
                            }
                            if (log.isDebugEnabled()) {
                                log.debug("TaskCompleted hook 聚合完成: taskId={} 阻塞错误数={}",
                                    taskId, blockingErrors.size());
                            }
                        } catch (Exception e) {
                            log.warn("TaskCompleted hook execution failed: {}", e.getMessage());
                        }
                    }

                    if (!blockingErrors.isEmpty()) {
                        // 对齐 CC TaskUpdateTool.ts:255-264: hook 阻塞 → { data: { success: false, taskId, updatedFields: [], error: blockingErrors.join('\n') } }
                        // 非 error（结构化 success:false）；CC 返回 join('\n')，Java 双通道 data=渲染文本、
                        // structuredOutput=结构化 spread（TaskUpdateToolStructuredOutputTest 定向断言）。
                        log.info("TaskUpdate TaskCompleted hook 阻塞: taskId={} success=false updatedFields=[] error={}",
                            taskId, String.join("\n", blockingErrors));
                        return successWithOutput(call.id(), new TaskUpdateOutput(
                            false, taskId, List.of(), String.join("\n", blockingErrors), null, null));
                    }
                }

                partialUpdates.put("status", newStatus);
                updatedFields.add("status");
            }
        }

        // Step 6: 应用部分更新（对齐 CC TaskUpdateTool.ts:272-274）
        // CC: if (Object.keys(updates).length > 0) await updateTask(taskListId, taskId, updates)
        if (!partialUpdates.isEmpty()) {
            Optional<Task> updated = taskPersistence.updateTask(listId, taskId, partialUpdates);
            if (updated.isPresent()) {
                existingTask = updated.get();
            }
        }

        // Step 7: Owner change → mailbox notify（对齐 CC TaskUpdateTool.ts:277-298，S12 已接线）
        // CC 真源（grep 自验，不信注释）：
        //   if (updates.owner && isAgentSwarmsEnabled()) {                  // :277 falsy 判据
        //     const senderName = getAgentName() || 'team-lead'              // :278；'team-lead' = TEAM_LEAD_NAME (swarm/constants.ts:1)
        //     const senderColor = getTeammateColor()                        // :279
        //     const assignmentMessage = JSON.stringify({                    // :280-287 TaskAssignmentMessage (teammateMailbox.ts:953-960)
        //       type: 'task_assignment', taskId,
        //       subject: existingTask.subject, description: existingTask.description,
        //       assignedBy: senderName, timestamp: new Date().toISOString() })
        //     await writeToMailbox(updates.owner,                           // :288-297
        //       { from: senderName, text: assignmentMessage,
        //         timestamp: new Date().toISOString(), color: senderColor },
        //       taskListId)   // 第三参 taskListId 作 teamName（按任务列表分箱）
        //   }
        // Java 判据 = partialUpdates.containsKey("owner") && 值非空 && isAgentSwarmsEnabled()
        // （OD-TU-2b：CC falsy 判据，空串 owner 不触发）。
        // 消息体 subject/description 用 originalSubject/originalDescription（getTask 时点值，
        // 对齐 CC :282-283 existingTask.subject/description）。
        // 基础设施 = TeammateMailbox（com.nexusai.application.agent.team，S12 新建，
        // 对齐 CC teammateMailbox.ts:134-192 writeToMailbox 全量：ensureInboxDir →
        // '[]' flag wx → {inbox}.lock 锁内重读 → push read:false → 2 空格缩进写回，
        // 出错 logError 不抛 :184-187）；消费侧互操作：CC attachments.ts:3532 经
        // readUnreadMessages(:3590) 读同一 inbox 文件（跨进程 CC CLI 可读 Java 写出的通知）。
        Object ownerValue = partialUpdates.get("owner");
        if (ownerValue instanceof String newOwner
            && !newOwner.isEmpty()
            && TaskSystemConfig.isAgentSwarmsEnabled()) {
            String senderName = TaskSystemConfig.getAgentName();
            if (senderName == null || senderName.isEmpty()) {
                // 对齐 CC :278 getAgentName() || 'team-lead'（swarm/constants.ts:1 TEAM_LEAD_NAME）
                // JS falsy 语义：仅空串 '' 回退；全空白串 ' ' 为 truthy 不回退（与下方 owner 判据同口径）
                senderName = "team-lead";
            }
            String senderColor = TaskSystemConfig.getTeammateColor();
            // CC :280-287：消息体 timestamp 与信封 timestamp 为两次 new Date().toISOString()
            String assignmentText = TeammateMailbox.taskAssignmentJson(
                taskId, originalSubject, originalDescription, senderName, TeammateMailbox.isoNow());
            String envelopeTimestamp = TeammateMailbox.isoNow();
            if (log.isDebugEnabled()) {
                log.debug("TaskUpdate owner 变更 → 写 teammate inbox: recipient={} from={} team={} path={}",
                    newOwner, senderName, listId, TeammateMailbox.getInboxPath(newOwner, listId));
            }
            TeammateMailbox.writeToMailbox(newOwner,
                TeammateMailbox.TeammateMessage.of(senderName, assignmentText, envelopeTimestamp, senderColor),
                listId);
        }

        // Step 8: addBlocks 处理（对齐 CC TaskUpdateTool.ts:301-312）
        // CC: for each new block → await blockTask(taskListId, taskId, blockId)
        //
        // 注意：CC TaskService.blockTask(taskListId, fromId, toId) 可直接调用。
        // Java TaskService.blockTask 已实现——直接使用。
        if (input.has("addBlocks") && input.get("addBlocks").isArray()) {
            JsonNode addBlocksNode = input.get("addBlocks");
            List<String> newBlocks = new ArrayList<>();
            for (JsonNode blockIdNode : addBlocksNode) {
                String blockId = blockIdNode.asText();
                // 对齐 CC: filter out already-existing blocks
                if (!existingTask.blocks().contains(blockId)) {
                    // CC: blockTask sets A blocks B (A.from blocks B.to)
                    // S14（TU-B11）：计数按 filter 结果（CC TaskUpdateTool.ts:301-311），blockTask
                    // 返回值（false=目标任务不存在，tasks.ts:467-469）仅作失败通知日志，不决定
                    // newBlocks 计入——引用不存在任务时 CC 仍 push 'blocks'（E3 已锁定）。
                    boolean blocked = taskPersistence.blockTask(listId, taskId, blockId);
                    newBlocks.add(blockId);
                    if (log.isDebugEnabled()) {
                        log.debug("TaskUpdate addBlocks 处理: 当前任务={} 目标={} blockTask 返回={}"
                                + "（false=目标不存在，仍按 CC filter 结果计入 'blocks'）",
                            taskId, blockId, blocked);
                    }
                }
            }
            if (!newBlocks.isEmpty()) {
                updatedFields.add("blocks");
            }
        }

        // Step 9: addBlockedBy 处理（对齐 CC TaskUpdateTool.ts:314-324）
        // CC: for each new blockedBy → await blockTask(taskListId, blockerId, taskId)
        // 方向反转：blocker blocks this task
        if (input.has("addBlockedBy") && input.get("addBlockedBy").isArray()) {
            JsonNode addBlockedByNode = input.get("addBlockedBy");
            List<String> newBlockedBy = new ArrayList<>();
            for (JsonNode blockerIdNode : addBlockedByNode) {
                String blockerId = blockerIdNode.asText();
                // 对齐 CC: filter out already-existing blockedBy
                if (!existingTask.blockedBy().contains(blockerId)) {
                    // CC: blockTask(taskListId, blockerId, taskId)
                    // S14（TU-B12）：计数按 filter 结果（CC TaskUpdateTool.ts:314-324），blockTask
                    // 返回值（false=来源任务不存在，tasks.ts:467-469）仅作失败通知日志，不决定
                    // newBlockedBy 计入——引用不存在任务时 CC 仍 push 'blockedBy'（E3 已锁定）。
                    boolean blocked = taskPersistence.blockTask(listId, blockerId, taskId);
                    newBlockedBy.add(blockerId);
                    if (log.isDebugEnabled()) {
                        log.debug("TaskUpdate addBlockedBy 处理: 当前任务={} 反向阻塞来源={} blockTask 返回={}"
                                + "（false=来源不存在，仍按 CC filter 结果计入 'blockedBy'）",
                            taskId, blockerId, blocked);
                    }
                }
            }
            if (!newBlockedBy.isEmpty()) {
                updatedFields.add("blockedBy");
            }
        }

        // Step 10: Verification nudge（对齐 CC TaskUpdateTool.ts:333-361）
        // CC 完整 7 条件（OD-TU-5b：外门 4 + 内层 3；旧注释误计为更少，已修正）：
        // feature('VERIFICATION_AGENT') + tengu_hive_evidence + !context.agentId
        // + updates.status === 'completed' + allDone + allTasks.length >= 3 + !verif
        //
        // 外门 4 条件（CC TaskUpdateTool.ts:335-338，逐一对应）：
        //   feature('VERIFICATION_AGENT')                     → TaskSystemConfig.isVerificationAgentEnabled()
        //   tengu_hive_evidence                                → TaskSystemConfig.isTenguHiveEvidenceEnabled()
        //   !context.agentId                                   → isMainThread(ctx)
        //      CC original: !context.agentId (TaskUpdateTool.ts:337)
        //   updates.status === 'completed'                     → updatedFields.contains("status") && newStatus == COMPLETED
        //      CC original: updates.status === 'completed' (TaskUpdateTool.ts:338)
        //      CC updates.status 仅在 status !== existingTask.status 块内 set（TaskUpdateTool.ts:230/267），
        //      即真实迁移；Java updatedFields 同理，仅 :512 newStatus!=oldStatus 块内 :571 push "status"。
        //      「已 completed 任务仅改 subject」→ updatedFields 无 status → 门短路，不再误触发 nudge。
        boolean verificationNudgeNeeded = false;
        boolean nudgeGateFeature = TaskSystemConfig.isVerificationAgentEnabled()
            && TaskSystemConfig.isTenguHiveEvidenceEnabled();
        boolean nudgeGateMainThread = isMainThread(ctx);
        boolean nudgeGateMigration = updatedFields.contains("status")
            && newStatus == Task.TaskStatus.COMPLETED;
        if (nudgeGateFeature) {
            if (!nudgeGateMainThread) {
                // 对齐 CC TaskUpdateTool.ts:337 !context.agentId：子 Agent/subagent 完成任务的 TaskUpdate 不触发 nudge
                if (log.isDebugEnabled()) {
                    log.debug("TaskUpdate verification nudge skipped: not main thread (agentId={})",
                        ctx != null ? ctx.agentId() : null);
                }
            } else if (!nudgeGateMigration) {
                // 对齐 CC TaskUpdateTool.ts:338 updates.status === 'completed'：仅真实迁移到 completed 才进入内层
                if (log.isDebugEnabled()) {
                    log.debug("TaskUpdate verification nudge skipped: status not actually changed to completed (updatedFields={})",
                        updatedFields);
                }
            }
        }
        // 内层 3 条件（CC TaskUpdateTool.ts:342-345）：allDone + allTasks.length >= 3 + !verif
        if (nudgeGateFeature && nudgeGateMainThread && nudgeGateMigration) {
            List<Task> allTasks = taskPersistence.listTasks(listId);
            boolean allDone = allTasks.stream().allMatch(t -> t.isCompleted());
            if (allDone && allTasks.size() >= 3
                && allTasks.stream().noneMatch(t ->
                    t.subject() != null && t.subject().toLowerCase().contains("verif"))) {
                verificationNudgeNeeded = true;
            }
        }

        // Step 11: 返回结构化输出（对齐 CC TaskUpdateTool.ts:351-362 call() → {data:{success, taskId, updatedFields, statusChange, verificationNudgeNeeded}}）
        // 渲染文本（'Updated task #id ...' + completed 提醒 + nudge NOTE）归 mapper mapToolResultToToolResultBlockParam
        // （对齐 CC TaskUpdateTool.ts:364-405）。successWithOutput 双通道：data=渲染文本给 LLM，
        // structuredOutput=结构化 JSON 给消费方（对齐 SubagentTool:1110-1118 spread 字段风格）。
        // statusChange 仅真实迁移才非 null（partialUpdates 含 status；CC updates.status 同样仅在
        // status !== existingTask.status 时 set，TaskUpdateTool.ts:230/267）——顺带修复'已 completed
        // 仅改 subject 误触发 completed 提醒'问题（旧实现用 newStatus==COMPLETED 无条件触发）。
        boolean statusChanged = partialUpdates.containsKey("status");
        log.info("TaskUpdate 更新成功: taskId={} success=true updatedFields={} statusChanged={} verificationNudgeNeeded={}",
            taskId, updatedFields, statusChanged, verificationNudgeNeeded);
        return successWithOutput(call.id(), new TaskUpdateOutput(
            true, taskId, updatedFields, null,
            statusChanged ? new StatusChange(oldStatus.toValue(), newStatus.toValue()) : null,
            verificationNudgeNeeded));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 将 JsonNode 值递归转为 Java Object（用于 metadata 存储）
     *
     * <p>对齐 CC TaskUpdateTool.ts:60 metadata: z.record(z.string(), z.unknown()) 原样存储
     * （CC original: z.unknown, TaskUpdateTool.ts:60）：嵌套对象/数组递归转换为
     * LinkedHashMap/List，不再 asText() 降级为空串（旧代码缺陷）——
     * tasks.ts:300 jsonStringify 逐字保留嵌套结构，此处等价。
     *
     * <p>数字精度：沿用原 int/long/double 分支 + canConvertToInt/canConvertToLong 守卫
     * （CC JS Number 是 IEEE double，不引入 BigDecimal 映射）。
     */
    private static Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) {
            // CC z.unknown 数字为 IEEE double；Java 侧优先 int，容不下转 long（保精度）
            if (node.canConvertToInt()) return node.asInt();
            if (node.canConvertToLong()) return node.asLong();
            return node.asDouble();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(jsonNodeToObject(element));
            }
            return list;
        }
        if (node.isObject()) {
            // 递归保留嵌套对象键序（对齐 CC JSON.stringify 插入序，tasks.ts:300）
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), jsonNodeToObject(entry.getValue()));
            }
            return map;
        }
        if (node.isBinary()) {
            // JSON 输入不可达（JSON 无二进制类型）；binaryValue 抛 IOException，包 try/catch 保签名
            try {
                return node.binaryValue();
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("metadata 二进制节点读取失败，降级为 null: {}", e.getMessage());
                }
                return null;
            }
        }
        // exotic 节点（POJONode/EmbeddedObject）：JSON 输入不可达，仅此一处兜底
        return node.asText();
    }

    /**
     * 格式化 TaskCompleted hook 阻塞错误 · 对齐 CC {@code getTaskCompletedHookMessage}
     * （Open-ClaudeCode/src/utils/hooks.ts:1925-1929）：
     * <pre>
     *   return `TaskCompleted hook feedback:\n${blockingError.blockingError}`
     * </pre>
     *
     * <p>WHY: CC 消费方（TaskUpdateTool.ts:249-251）把每个 result.blockingError 经本函数加前缀
     * 后收集，再 join('\n') 填入 error。Java 端逐条等价。
     *
     * @param blockingError CC original: {@code blockingError} (hooks.ts:1927);
     *                      结构化阻塞错误 record（exit 2 stderr 文本在 blockingError()）
     * @return 前缀 + 阻塞错误文本，注入 LLM 作为反馈
     */
    private static String getTaskCompletedHookMessage(HookBlockingError blockingError) {
        return "TaskCompleted hook feedback:\n" + blockingError.blockingError();
    }

    /**
     * 是否主线程 · 对齐 CC TaskUpdateTool.ts:337 {@code !context.agentId}
     *
     * <p>与 TodoWriteTool.isMainThread（TodoWriteTool.java:837-839）同一约定：
     * [session-id-short] 主线程判定 = agentId==null（对齐 CC {@code agentId === undefined}）：
     * <ul>
     *   <li>主线程：agentId=null（ChatService 主会话传 null，RunRequest null=主线程）→
     *       effectiveAgentId 兜底已删，ctx.agentId() 保持 null → isMainThread true</li>
     *   <li>子 Agent：agentId 为独立 packed a+16hex UUID（createSubagentContext 显式传非 null）
     *       → isMainThread false</li>
     *   <li>ctx == null（老调用点/测试）：视为主线程（与 CC 无 agent 上下文一致）</li>
     * </ul>
     *
     * @param ctx 运行时上下文（可为 null）
     * @return true 如果是主线程
     */
    private boolean isMainThread(ToolUseContext ctx) {
        // 对齐 CC TaskUpdateTool.ts:337: !context.agentId
        // [session-id-short] 主线程判定 agentId==null（原 agentId.equals(sessionId)
        // UUID/String 恒 false 死分支，会把主线程误判为子 Agent）
        return ctx == null || ctx.agentId() == null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结构化输出 record · 对齐 CC TaskUpdateTool.ts outputSchema + 双通道 mapper
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输出 record · 对齐 CC TaskUpdateTool.ts:69-83 outputSchema（六字段）.
     *
     * <p>CC outputSchema（grep 实证 TaskUpdateTool.ts:69-83）：
     * <pre>
     * z.object({
     *   success: z.boolean(),
     *   taskId: z.string(),
     *   updatedFields: z.array(z.string()),
     *   error: z.string().optional(),
     *   statusChange: z.object({ from: z.string(), to: z.string() }).optional(),
     *   verificationNudgeNeeded: z.boolean().optional(),
     * })
     * </pre>
     *
     * <p>{@code toString()} 委托 {@link #mapToolResultToToolResultBlockParam(TaskUpdateOutput)} 作
     * LLM 可见文本桥（concerns#1），{@code data} 承载结构化六字段供 SDK 消费方解析。
     *
     * @param success                 CC original: success (TaskUpdateTool.ts:71) — 更新是否成功
     * @param taskId                  CC original: taskId (TaskUpdateTool.ts:72) — 目标任务 ID
     * @param updatedFields           CC original: updatedFields (TaskUpdateTool.ts:73) — 实际变更的字段名列表
     * @param error                   CC original: error (TaskUpdateTool.ts:74) — 失败原因（未找到 / hook 阻塞等良性条件）
     * @param statusChange            CC original: statusChange (TaskUpdateTool.ts:75-80) — 状态迁移 {from, to}；仅真实迁移非 null
     * @param verificationNudgeNeeded CC original: verificationNudgeNeeded (TaskUpdateTool.ts:81) — 结构性验证提示；可空
     */
    public record TaskUpdateOutput(boolean success, String taskId, List<String> updatedFields,
                                   String error, StatusChange statusChange, Boolean verificationNudgeNeeded) {
        @Override
        public String toString() {
            // 委托独立 mapper：结构化输出渲染文本与 execute() data 通道共用同一入口（CC:364-405）
            return mapToolResultToToolResultBlockParam(this);
        }
    }

    /**
     * 状态迁移 record · 对齐 CC TaskUpdateTool.ts:75-80 (statusChange: {from, to}).
     *
     * @param from CC original: from (TaskUpdateTool.ts:77) — 迁移前状态
     * @param to   CC original: to (TaskUpdateTool.ts:78) — 迁移后状态
     */
    public record StatusChange(String from, String to) {
    }

    /**
     * 渲染工具结果文本 · 对齐 CC TaskUpdateTool.ts:364-405 mapToolResultToToolResultBlockParam.
     *
     * <p>独立 mapToolResult 分层：{@link #execute(ToolUseBlock, ToolUseContext)} 只构建结构化
     * {@link TaskUpdateOutput}，渲染文本全部在此产出（对齐 CC:364-405），execute() 经
     * {@link #successWithOutput(String, TaskUpdateOutput)} 双通道返回。
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * mapToolResultToToolResultBlockParam(content, toolUseID) {
     *   const { success, taskId, updatedFields, error, statusChange, verificationNudgeNeeded } = content
     *   if (!success) return { ..., content: error || `Task #${taskId} not found` }  // 非 error（良性）
     *   let resultContent = `Updated task #${taskId} ${updatedFields.join(', ')}`
     *   if (statusChange?.to === 'completed' && getAgentId() && isAgentSwarmsEnabled()) {
     *     resultContent += '\n\nTask completed. Call TaskList now to find your next available task or see if your work unblocked others.'
     *   }
     *   if (verificationNudgeNeeded) {
     *     resultContent += '\n\nNOTE: You just closed out 3+ tasks ... (subagent_type="verification") ...'
     *   }
     *   return { ..., content: resultContent }
     * }
     * </pre>
     *
     * @param content 结构化输出（TaskUpdateOutput，data 承载 success/taskId/updatedFields/statusChange 等）
     * @return CC mapper content 文本（LLM 可见 tool_result content）
     */
    static String mapToolResultToToolResultBlockParam(TaskUpdateOutput content) {
        if (!content.success()) {
            // 对齐 CC TaskUpdateTool.ts:373-381: !success → content = error || `Task #${taskId} not found`
            // 非 error（良性条件，如任务列表已清理），避免触发 sibling tool 取消（CC 注释明示）。
            return content.error() != null && !content.error().isBlank()
                ? content.error()
                : "Task #" + content.taskId() + " not found";
        }
        if (log.isDebugEnabled()) {
            log.debug("TaskUpdate mapper 渲染 tool_result 文本: taskId={} success={} updatedFields={}",
                content.taskId(), content.success(), content.updatedFields());
        }
        // 对齐 CC TaskUpdateTool.ts:384: 'Updated task #id updatedFields'（空格分隔，无括号）
        String resultContent = "Updated task #" + content.taskId() + " "
            + String.join(", ", content.updatedFields());

        // 对齐 CC TaskUpdateTool.ts:387-394: statusChange?.to === 'completed' && getAgentId()
        // && isAgentSwarmsEnabled() → 提醒 teammate 调用 TaskList。statusChange 仅真实迁移非 null：
        // CC updates.status 仅在 status !== existingTask.status 块内 set（TaskUpdateTool.ts:230/:267-268），
        // statusChange 仅在 updates.status 存在时产出（TaskUpdateTool.ts:356-359）→ statusChange 存在
        // ⟺ 真实迁移；'已 completed 仅改 subject/owner' 场景（无 status 输入或 status==现有值）
        // statusChange 为 null，不触发提醒（修复旧实现 newStatus==COMPLETED 无条件触发的误报）。
        // [IMP-G3] OD-G2-1 拍板：getAgentId()（teammate.ts:88-92，仅 running-as-teammate 返回
        // agentId，主线程 undefined）改由 Java Teammate.getAgentId() 对等表达（in-process TeammateContext
        // ThreadLocal > dynamicTeamContext，与 CC 同优先级）；不再用 nexusai.agent.name sysprop 代理。
        boolean reminderRealMigrationToCompleted = content.statusChange() != null
            && "completed".equals(content.statusChange().to());
        if (reminderRealMigrationToCompleted
            && TaskSystemConfig.isAgentSwarmsEnabled()
            && Teammate.getAgentId() != null) {
            if (log.isDebugEnabled()) {
                log.debug("TaskUpdate completed 提醒已追加: taskId={} statusChange={}->{} "
                        + "swarmsEnabled=true agentId={}（对齐 CC TaskUpdateTool.ts:392-394）",
                    content.taskId(), content.statusChange().from(), content.statusChange().to(),
                    Teammate.getAgentId());
            }
            resultContent +=
                "\n\nTask completed. Call TaskList now to find your next available task or see if your work unblocked others.";
        } else if (log.isDebugEnabled() && reminderRealMigrationToCompleted) {
            // 已真实迁移到 completed，但 CC 门（getAgentId / isAgentSwarmsEnabled）未全过：
            // 记录拦截原因（swarms 未启用或缺 agentId 时不提醒）
            log.debug("TaskUpdate completed 提醒被拦截: taskId={} statusChange={}->{} "
                    + "swarmsEnabled={} agentId={}（CC:389 getAgentId()/390 isAgentSwarmsEnabled() 门未全过）",
                content.taskId(), content.statusChange().from(), content.statusChange().to(),
                TaskSystemConfig.isAgentSwarmsEnabled(), Teammate.getAgentId());
        }

        // 对齐 CC TaskUpdateTool.ts:396-398: verificationNudgeNeeded → NOTE 提示
        // （AgentToolConstants.VERIFICATION_AGENT_TYPE = 'verification'，CC AgentTool/constants.ts:4）。
        if (Boolean.TRUE.equals(content.verificationNudgeNeeded())) {
            resultContent +=
                "\n\nNOTE: You just closed out 3+ tasks and none of them was a verification step. Before writing your final summary, spawn the verification agent (subagent_type=\""
                    + AgentToolConstants.VERIFICATION_AGENT_TYPE
                    + "\"). You cannot self-assign PARTIAL by listing caveats in your summary — only the verifier issues a verdict.";
        }
        return resultContent;
    }

    /**
     * 结构化输出 Map · 对齐 CC TaskUpdateTool.ts:69-83 outputSchema（spread 字段，SubagentTool:1110-1118 风格）。
     *
     * <p>供 {@link #successWithOutput(String, TaskUpdateOutput)} 经
     * {@link ToolResult#successWithStructuredOutput} 填入 structuredOutput 通道
     * （IT-6 后走 AttachmentMessageDto structured_output attachment，对齐 CC
     * toolExecution.ts:1272-1279；provider 不再序列化为 text block 发模型）。
     * error / statusChange / verificationNudgeNeeded 可空，与 CC optional 对齐（:74/75/81）。
     *
     * @param output 结构化输出（TaskUpdateOutput）
     * @return spread 字段 Map（success/taskId/updatedFields/error?/statusChange{from,to}?/verificationNudgeNeeded?）
     */
    private static Map<String, Object> toStructuredOutput(TaskUpdateOutput output) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", output.success());
        map.put("taskId", output.taskId());
        map.put("updatedFields", output.updatedFields());
        if (output.error() != null) {
            map.put("error", output.error());
        }
        if (output.statusChange() != null) {
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("from", output.statusChange().from());
            sc.put("to", output.statusChange().to());
            map.put("statusChange", sc);
        }
        if (output.verificationNudgeNeeded() != null) {
            map.put("verificationNudgeNeeded", output.verificationNudgeNeeded());
        }
        return map;
    }

    /**
     * 双通道成功返回 · data=渲染文本（给 LLM），structuredOutput=结构化 JSON（给消费方）。
     *
     * <p>对齐 CC：execute() 只构建结构化 TaskUpdateOutput，渲染文本归
     * {@link #mapToolResultToToolResultBlockParam(TaskUpdateOutput)}（CC:364-405）；
     * Java 经 {@link ToolResult#successWithStructuredOutput} 同时产出 data + structuredOutput
     * （TodoWriteTool.java:594-596 同款先例；concerns#4 A1 遗憾：额外 JSON text block 亦发给模型）。
     *
     * @param toolUseId 工具调用 ID
     * @param output    结构化输出（TaskUpdateOutput）
     * @return ToolResult：data=渲染文本（非空），structuredOutput=结构化 Map
     */
    private static ToolResult<java.util.Map<String, Object>> successWithOutput(String toolUseId, TaskUpdateOutput output) {
        return ToolResult.successWithStructuredOutput(toolUseId,
            mapToolResultToToolResultBlockParam(output), toStructuredOutput(output));
    }
}

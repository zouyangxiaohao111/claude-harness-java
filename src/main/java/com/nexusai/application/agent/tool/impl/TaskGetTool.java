package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TaskGet 工具 · 对齐 CC TaskGetTool.ts（PROMPT 全量；结构化 data 输出见方法备注）
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = 'TaskGet'</td><td>TaskGetTool.ts:39</td><td>{@link #name()}</td></tr>
 *   <tr><td>searchHint = 'retrieve a task by ID'</td><td>TaskGetTool.ts:40</td><td>{@link #searchHint()}</td></tr>
 *   <tr><td>inputSchema (taskId)</td><td>TaskGetTool.ts:13-17</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema (task 结构化)</td><td>TaskGetTool.ts:20-33</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>userFacingName() → 'TaskGet'</td><td>TaskGetTool.ts:54-55</td><td>{@link #userFacingName()}</td></tr>
 *   <tr><td>shouldDefer = true</td><td>TaskGetTool.ts:57</td><td>{@link #shouldDefer(JsonNode)}</td></tr>
 *   <tr><td>isEnabled() = isTodoV2Enabled()</td><td>TaskGetTool.ts:58-59</td><td>{@link #isEnabled()}</td></tr>
 *   <tr><td>isConcurrencySafe() → true</td><td>TaskGetTool.ts:61-62</td><td>{@link #isConcurrencySafe(JsonNode)}</td></tr>
 *   <tr><td>isReadOnly() → true</td><td>TaskGetTool.ts:64-65</td><td>{@link #isReadOnly(JsonNode)}</td></tr>
 *   <tr><td>toAutoClassifierInput → input.taskId</td><td>TaskGetTool.ts:67-68</td><td>{@link #toAutoClassifierInput(JsonNode)}</td></tr>
 *   <tr><td>renderToolUseMessage → null</td><td>TaskGetTool.ts:70-71</td><td>{@link #renderToolUseMessage(JsonNode)}</td></tr>
 *   <tr><td>call() 返回结构化 task 或 null</td><td>TaskGetTool.ts:73-97</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>mapToolResultToToolResultBlockParam</td><td>TaskGetTool.ts:99-127</td><td>{@link #renderToolResultText(TaskGetOutput)}</td></tr>
 * </table>
 *
 * @see Task
 * @see TaskService
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskGetTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskGetTool.class);
    private static final String NAME = "TaskGet";

    /** 对齐 CC TaskGetTool prompt.ts:1 DESCRIPTION = 'Get a task by ID from the task list' */
    private static final String DESCRIPTION = "Get a task by ID from the task list";

    /**
     * PROMPT · 对齐 CC TaskGetTool prompt.ts:3-24 全量，逐字。
     *
     * <p>CC 真源（grep 实证 prompt.ts:3-24）：intro + ## When to Use This Tool 3 条
     * （:7-9）+ ## Output 5 字段（:14-18）+ ## Tips 2 条（:22-23）。
     * 含反引号（`` `pending` `` 等）与 **加粗** markdown——文本块直接内联。
     */
    private static final String PROMPT = """
        Use this tool to retrieve a task by its ID from the task list.

        ## When to Use This Tool

        - When you need the full description and context before starting work on a task
        - To understand task dependencies (what it blocks, what blocks it)
        - After being assigned a task, to get complete requirements

        ## Output

        Returns full task details:
        - **subject**: Task title
        - **description**: Detailed requirements and context
        - **status**: 'pending', 'in_progress', or 'completed'
        - **blocks**: Tasks waiting on this one to complete
        - **blockedBy**: Tasks that must complete before this one can start

        ## Tips

        - After fetching a task, verify its blockedBy list is empty before beginning work.
        - Use TaskList to see all tasks in summary form.
        """;

    private final TaskService taskPersistence;

    /** s12-3.2: Spring 构造器注入（按类型解析，无 taskListId）· 对齐 CC TaskGetTool call() 内逐次 getTaskListId() */
    public TaskGetTool(TaskService taskPersistence) {
        this.taskPersistence = taskPersistence;
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
     * 搜索提示 · 对齐 CC TaskGetTool.ts:40 searchHint = 'retrieve a task by ID'。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）；供 ToolSearch 关键词匹配
     * （ToolSearchTool.java:484/:500/:518 消费，命中 +4 高于描述 +2）。
     */
    @Override
    public String searchHint() {
        return "retrieve a task by ID";
    }

    /**
     * 工具 prompt · 对齐 CC TaskGetTool.ts:45-47 prompt() → PROMPT（CC prompt.ts:3-24 全量）
     */
    @Override
    public String prompt() {
        if (log.isDebugEnabled()) {
            log.debug("TaskGet.prompt() 返回 {} 字符 PROMPT（CC prompt.ts:3-24 多段：When to Use/Output/Tips）",
                PROMPT.length());
        }
        return PROMPT;
    }

    /**
     * 用户可见名 · 对齐 CC TaskGetTool.ts:54-55 userFacingName() → 'TaskGet'
     */
    @Override
    public String userFacingName() {
        // 对齐 CC TaskGetTool.ts:55: return 'TaskGet'
        return "TaskGet";
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        // 对齐 CC TaskGetTool.ts:65: return true
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输入 Schema · 对齐 CC TaskGetTool.ts:13-17 inputSchema
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("taskId", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "The ID of the task to retrieve"));

        schema.set("properties", properties);
        schema.set("required", JSON.createArrayNode().add("taskId"));
        schema.put("additionalProperties", false);

        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TaskGetTool.ts:20-33 outputSchema
     *
     * <p>CC outputSchema（grep 实证，不信注释）：
     * <pre>
     * z.object({
     *   task: z.object({ id, subject, description, status, blocks, blockedBy }).nullable()
     * })
     * </pre>
     *
     * <p>zod v4 toJSONSchema（默认 io='output'，CC zodToJsonSchema.ts:20）序列化语义：
     * <ul>
     *   <li>顶层 {@code task} 为 z.object 成员 → 默认 required，序列化为根 required 数组
     *       {@code ["task"]}（CC TaskGetTool.ts:21）</li>
     *   <li>task 对象 6 字段均无 .optional()（CC TaskGetTool.ts:22-30）→ 全部进入
     *       task 对象内部 required 数组</li>
     *   <li>{@code .nullable()}（CC TaskGetTool.ts:31）→ 序列化为
     *       {@code anyOf: [taskObj, {type:"null"}]}（zod 实际实现用 anyOf，issue #5100）</li>
     *   <li>普通 z.object 在 output 模式输出 {@code additionalProperties:false}（根与 task 对象内部）</li>
     * </ul>
     *
     * <p>status 用严格 TaskStatusSchema()（CC TaskGetTool.ts:27，即 tasks.ts:71-73
     * z.enum(['pending','in_progress','completed'])）——3 值，无 'deleted'
     * （grep 实证 CC TaskGetTool.ts 无 deleted）。grep 实证 'deleted' → 0 命中。
     */
    @Override
    public JsonNode outputSchema() {
        if (log.isDebugEnabled()) {
            log.debug("TaskGet.outputSchema() 构建任务输出契约：task 顶层 required + 内部 6 字段 required + nullable anyOf[taskObj,{type:null}]");
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        ObjectNode taskObj = JSON.createObjectNode();
        taskObj.put("type", "object");
        ObjectNode taskProps = JSON.createObjectNode();
        taskProps.set("id", JSON.createObjectNode().put("type", "string"));
        taskProps.set("subject", JSON.createObjectNode().put("type", "string"));
        taskProps.set("description", JSON.createObjectNode().put("type", "string"));
        taskProps.set("status", JSON.createObjectNode()
            .put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        taskProps.set("blocks", JSON.createObjectNode()
            .put("type", "array").set("items", JSON.createObjectNode().put("type", "string")));
        taskProps.set("blockedBy", JSON.createObjectNode()
            .put("type", "array").set("items", JSON.createObjectNode().put("type", "string")));
        taskObj.set("properties", taskProps);
        // CC TaskGetTool.ts:22-30 6 字段均无 .optional() → task 对象内部全部 required
        taskObj.set("required", JSON.createArrayNode()
            .add("id").add("subject").add("description")
            .add("status").add("blocks").add("blockedBy"));
        // zod v4 output 模式普通 z.object 输出 additionalProperties:false
        taskObj.put("additionalProperties", false);
        // CC TaskGetTool.ts:31 .nullable() → anyOf: [taskObj, {type:"null"}]
        ArrayNode taskNullable = JSON.createArrayNode();
        taskNullable.add(taskObj);
        taskNullable.add(JSON.createObjectNode().put("type", "null"));
        ObjectNode taskAnyOf = JSON.createObjectNode();
        taskAnyOf.set("anyOf", taskNullable);
        properties.set("task", taskAnyOf);

        schema.set("properties", properties);
        // CC TaskGetTool.ts:21 顶层 task 为 z.object 默认 required → 根 required 数组
        schema.set("required", JSON.createArrayNode().add("task"));
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
            new PermissionDecisionReason.Other("task_get_allow"),
            null, false, null, List.of());
    }

    /**
     * 自动分类器输入 · 对齐 CC TaskGetTool.ts:67-68 toAutoClassifierInput()
     *
     * <p>CC 源码：{@code return input.taskId}——无 fallback（D-1 已删除）。
     * 缺 taskId 返回 null（等价 CC undefined，消费侧 {@code ?? input} 回退；
     * 实际不可达：inputSchema taskId 必填，TaskGetTool.ts:13-17）。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC TaskGetTool.ts:68: return input.taskId（无 fallback，不回退 NAME）
        if (input == null || !input.has("taskId")) {
            return null;
        }
        return input.get("taskId").asText();
    }

    /**
     * 工具使用消息渲染 · 对齐 CC TaskGetTool.ts:71: return null
     */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 核心执行逻辑 · 对齐 CC TaskGetTool.ts:73-127
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 执行 TaskGet · 对齐 CC TaskGetTool.ts:73-127 call() + mapToolResultToToolResultBlockParam()
     *
     * <p>核心流程：
     * <ol>
     *   <li>解析 taskId</li>
     *   <li>查找任务</li>
     *   <li>返回结构化任务数据（含 blocks/blockedBy 引用）</li>
     * </ol>
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        JsonNode input = call.input();

        // Step 1: 解析 taskId（对齐 CC TaskGetTool.ts:73）
        String taskId = input.has("taskId") ? input.get("taskId").asText() : "";
        // 无 blank-taskId 拦截（TG-15/D-1 家族已删除）：CC inputSchema taskId 为 z.string()
        // 无 min（TaskGetTool.ts:15），空串合法，直接走查找路径（TaskGetTool.ts:73-76）；
        // 未找到 → { data: { task: null } } 结构化成功（TaskGetTool.ts:78-83）。
        if (log.isDebugEnabled() && taskId.isEmpty()) {
            log.debug("TaskGet 收到空 taskId，走查找路径（对齐 CC TaskGetTool.ts:73-97："
                    + "getTask 未找到 → data.task=null 结构化成功）");
        }

        // Step 2: 查找任务（对齐 CC TaskGetTool.ts:74-76）
        // CC: const taskListId = getTaskListId(); const task = await getTask(taskListId, taskId)
        // 逐次动态解析列表 ID（对齐 CC TaskGetTool.ts:74 getTaskListId()）
        String listId = TaskService.getTaskListId();
        if (log.isDebugEnabled()) {
            log.debug("TaskGet 解析列表 ID {}，查找任务 {}", listId, taskId);
        }
        Optional<Task> taskOpt = taskPersistence.getTask(listId, taskId);
        if (taskOpt.isEmpty()) {
            // 对齐 CC TaskGetTool.ts:78-83: task not found → return { data: { task: null } }
            // 结构化成功（非 error），'Task not found' 文本归 mapper renderToolResultText。
            if (log.isDebugEnabled()) {
                log.debug("TaskGet 未找到任务: taskId={} 返回结构化 TaskGetOutput(task=null)", taskId);
            }
            return ToolResult.success(call.id(), new TaskGetOutput(null));
        }

        // Step 3: 构建结构化输出（对齐 CC TaskGetTool.ts:86-97）
        // CC: return { data: { task: { id, subject, description, status, blocks, blockedBy } } }
        Task task = taskOpt.get();
        TaskData data = new TaskData(task.id(), task.subject(), task.description(),
            task.status().toValue(), task.blocks(), task.blockedBy());
        // 渲染多行文本（Task #/Status/Description/Blocked by/Blocks）归 mapper
        // renderToolResultText（对齐 CC TaskGetTool.ts:99-127 mapToolResultToToolResultBlockParam）。
        if (log.isDebugEnabled()) {
            log.debug("TaskGet 找到任务: taskId={} 返回结构化 TaskGetOutput(TaskData)", taskId);
        }
        return ToolResult.success(call.id(), new TaskGetOutput(data));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册方法
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // 结构化输出 record · 对齐 CC TaskGetTool.ts outputSchema + 双通道 mapper
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输出 record · 对齐 CC TaskGetTool.ts:20-33 outputSchema (task: {...}.nullable()).
     *
     * <p>CC outputSchema（grep 实证 TaskGetTool.ts:20-33，task 顶层 .nullable()）：
     * <pre>
     * z.object({
     *   task: z.object({ id, subject, description, status, blocks, blockedBy }).nullable(),
     * })
     * </pre>
     *
     * <p>{@code toString()} 委托 {@link #renderToolResultText(TaskGetOutput)} 作
     * LLM 可见文本桥（concerns#1），{@code data} 承载结构化 task（或 null）供 SDK 消费方解析。
     *
     * <p>{@code TaskGetOutput} 不加 {@code @JsonInclude(NON_NULL)}：CC 未找到时
     * 返回显式 null 保留键（TaskGetTool.ts:78-83 {@code data: { task: null }}，
     * JSON.stringify 保留 null，仅省略 undefined），Java {@code null} 对应此语义 →
     * task 键始终写出（未找到时 {@code "task":null}）。内部 {@link TaskData} 保留
     * NON_NULL（description/blocks/blockedBy 的 null ≈ CC undefined 省略）。
     *
     * @param task CC original: task (TaskGetTool.ts:22-31) — 任务详情；null = 未找到
     *             （结构化成功，非 error；对齐 CC TaskGetTool.ts:78-83）
     */
    public record TaskGetOutput(TaskData task) {
        public TaskGetOutput {
            if (log.isDebugEnabled() && task == null) {
                log.debug("TaskGetOutput task 为 null（未找到）: 显式 task 键保留（对齐 CC TaskGetTool.ts:78-83 data:{task:null}）");
            }
        }
        @Override
        public String toString() {
            return renderToolResultText(this);
        }
    }
    /**
     * 任务详情 record · 对齐 CC TaskGetTool.ts:22-31 (task: {id, subject, description, status, blocks, blockedBy}).
     *
     * <p>{@code @JsonInclude(NON_NULL)}（Task.java:54 先例）：null 字段序列化省略
     * （对齐 CC jsonStringify 省略 undefined）；CC task 对象无 owner 字段
     * （TaskGetTool.ts:22-31），Java record 亦无 owner。
     *
     * @param id          CC original: id (TaskGetTool.ts:24) — 任务 ID
     * @param subject     CC original: subject (TaskGetTool.ts:25) — 任务标题
     * @param description CC original: description (TaskGetTool.ts:26) — 任务描述
     * @param status      CC original: status (TaskGetTool.ts:27) — TaskStatusSchema 字符串值
     * @param blocks      CC original: blocks (TaskGetTool.ts:28) — 此任务阻塞的任务 ID 列表
     * @param blockedBy   CC original: blockedBy (TaskGetTool.ts:29) — 阻塞此任务的任务 ID 列表
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskData(String id, String subject, String description, String status,
                           List<String> blocks, List<String> blockedBy) {
        public TaskData {
            if (log.isDebugEnabled() && (description == null || blocks == null || blockedBy == null)) {
                log.debug("TaskData 存在 null 字段: NON_NULL 序列化将省略对应键（对齐 CC 省略 undefined；生产路径 description/blocks/blockedBy 均非 null）");
            }
        }
    }

    /**
     * 渲染工具结果文本 · 对齐 CC TaskGetTool.ts:99-127 mapToolResultToToolResultBlockParam.
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * mapToolResultToToolResultBlockParam(content, toolUseID) {
     *   const { task } = content
     *   if (!task) return { ..., content: 'Task not found' }
     *   const lines = [
     *     `Task #${task.id}: ${task.subject}`,
     *     `Status: ${task.status}`,
     *     `Description: ${task.description}`,
     *   ]
     *   if (task.blockedBy.length > 0) lines.push(`Blocked by: ${...#id...}`)
     *   if (task.blocks.length > 0)   lines.push(`Blocks: ${...#id...}`)
     *   return { ..., content: lines.join('\n') }
     * }
     * </pre>
     *
     * @param data 结构化输出（TaskGetOutput，data 承载 task 或 null）
     * @return CC mapper content 文本（LLM 可见 tool_result content）
     */
    static String renderToolResultText(TaskGetOutput data) {
        TaskData task = data.task();
        if (task == null) {
            // 对齐 CC TaskGetTool.ts:101-107: !task → content: 'Task not found'
            return "Task not found";
        }
        if (log.isDebugEnabled()) {
            log.debug("TaskGet mapper 渲染 tool_result 文本: taskId={} status={}",
                task.id(), task.status());
        }
        // 对齐 CC TaskGetTool.ts:109-113: 基础行 Task #id / Status / Description
        List<String> lines = new ArrayList<>();
        lines.add("Task #" + task.id() + ": " + task.subject());
        lines.add("Status: " + task.status());
        lines.add("Description: " + task.description());
        // 对齐 CC TaskGetTool.ts:115-120: blockedBy / blocks 可选行
        if (!task.blockedBy().isEmpty()) {
            lines.add("Blocked by: " + task.blockedBy().stream()
                .map(id -> "#" + id)
                .collect(Collectors.joining(", ")));
        }
        if (!task.blocks().isEmpty()) {
            lines.add("Blocks: " + task.blocks().stream()
                .map(id -> "#" + id)
                .collect(Collectors.joining(", ")));
        }
        return String.join("\n", lines);
    }
}

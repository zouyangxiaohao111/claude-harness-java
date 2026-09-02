package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TaskList 工具 · 对齐 CC TaskListTool.ts（PROMPT 全量 + agent-swarms 分支；结构化输出见方法备注）
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = 'TaskList'</td><td>TaskListTool.ts:34</td><td>{@link #name()}</td></tr>
 *   <tr><td>searchHint = 'list all tasks'</td><td>TaskListTool.ts:35</td><td>{@link #searchHint()}</td></tr>
 *   <tr><td>inputSchema (空对象)</td><td>TaskListTool.ts:13</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema (tasks: Task[])</td><td>TaskListTool.ts:16-27</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>userFacingName() → 'TaskList'</td><td>TaskListTool.ts:49-50</td><td>{@link #userFacingName()}</td></tr>
 *   <tr><td>shouldDefer = true</td><td>TaskListTool.ts:52</td><td>{@link #shouldDefer(JsonNode)}</td></tr>
 *   <tr><td>isEnabled() = isTodoV2Enabled()</td><td>TaskListTool.ts:53-54</td><td>{@link #isEnabled()}</td></tr>
 *   <tr><td>isConcurrencySafe() → true</td><td>TaskListTool.ts:56-57</td><td>{@link #isConcurrencySafe(JsonNode)}</td></tr>
 *   <tr><td>isReadOnly() → true</td><td>TaskListTool.ts:59-60</td><td>{@link #isReadOnly(JsonNode)}</td></tr>
 *   <tr><td>renderToolUseMessage → null</td><td>TaskListTool.ts:62-63</td><td>{@link #renderToolUseMessage(JsonNode)}</td></tr>
 *   <tr><td>call() 过滤 _internal + resolved blockedBy</td><td>TaskListTool.ts:65-89</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>mapToolResultToToolResultBlockParam</td><td>TaskListTool.ts:91-115</td><td>{@link #renderToolResultText(TaskListOutput)}</td></tr>
 * </table>
 *
 * @see Task
 * @see TaskService
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskListTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskListTool.class);
    private static final String NAME = "TaskList";

    /** 对齐 CC TaskListTool prompt.ts:3 DESCRIPTION = 'List all tasks in the task list'（无尾句号） */
    private static final String DESCRIPTION = "List all tasks in the task list";

    /**
     * 主模板 · 对齐 CC TaskListTool prompt.ts getPrompt() 主模板 (:28-47)，逐字。
     *
     * <p>${teammateUseCase} 与 ${idDescription} 为占位 token，在 {@link #getPrompt()} 内经
     * {@code TaskSystemConfig.isAgentSwarmsEnabled()} 三分支 replace 解析
     * （对齐 CC prompt.ts:5-49）。teammateWorkflow 因 CC:48 结尾紧跟反引号（无尾换行），
     * 在 {@link #getPrompt()} 内以 {@code + teammateWorkflow} 追加而非文本块 token，
     * 避免文本块收尾 `"""` 注入多余换行。
     */
    private static final String LIST_MAIN_TEMPLATE = """
        Use this tool to list all tasks in the task list.

        ## When to Use This Tool

        - To see what tasks are available to work on (status: 'pending', no owner, not blocked)
        - To check overall progress on the project
        - To find tasks that are blocked and need dependencies resolved
        ${teammateUseCase}- After completing a task, to check for newly unblocked work or claim the next available task
        - **Prefer working on tasks in ID order** (lowest ID first) when multiple tasks are available, as earlier tasks often set up context for later ones

        ## Output

        Returns a summary of each task:
        ${idDescription}
        - **subject**: Brief description of the task
        - **status**: 'pending', 'in_progress', or 'completed'
        - **owner**: Agent ID if assigned, empty if available
        - **blockedBy**: List of open task IDs that must be resolved first (tasks with blockedBy cannot be claimed until dependencies resolve)

        Use TaskGet with a specific task ID to view full details including description and comments.
        """;

    private final TaskService taskPersistence;

    /** s12-3.2: Spring 构造器注入（按类型解析，无 taskListId）· 对齐 CC TaskListTool call() 内逐次 getTaskListId() */
    public TaskListTool(TaskService taskPersistence) {
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
     * 搜索提示 · 对齐 CC TaskListTool.ts:35 searchHint = 'list all tasks'。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）；供 ToolSearch 关键词匹配
     * （ToolSearchTool.java:484/:500/:518 消费，命中 +4 高于描述 +2）。
     */
    @Override
    public String searchHint() {
        return "list all tasks";
    }

    /**
     * 工具 prompt · 对齐 CC TaskListTool.ts:40-42 prompt() → getPrompt()
     *
     * <p>每次调用按 {@code TaskSystemConfig.isAgentSwarmsEnabled()} 实时三分支拼装
     * （对齐 CC prompt.ts:5-49），不缓存——与 CC 动态行为一致。
     */
    @Override
    public String prompt() {
        String prompt = getPrompt();
        if (log.isDebugEnabled()) {
            log.debug("TaskList.prompt() 返回 {} 字符 PROMPT（swarms={}）",
                prompt.length(), TaskSystemConfig.isAgentSwarmsEnabled());
        }
        return prompt;
    }

    /**
     * 动态拼装 PROMPT · 镜像 CC TaskListTool prompt.ts:5-49 getPrompt()
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * export function getPrompt(): string {
     *   const teammateUseCase = isAgentSwarmsEnabled()
     *     ? `- Before assigning tasks to teammates, to see what's available\n` : ''
     *   const idDescription = isAgentSwarmsEnabled()
     *     ? '- **id**: Task identifier (use with TaskGet, TaskUpdate)'
     *     : '- **id**: Task identifier (use with TaskGet, TaskUpdate)'   // 两分支文本相同
     *   const teammateWorkflow = isAgentSwarmsEnabled()
     *     ? `\n## Teammate Workflow\n\nWhen working as a teammate:\n1. ... 5. ...\n` : ''
     *   return `...${teammateUseCase}...${idDescription}...${teammateWorkflow}`
     * }
     * </pre>
     *
     * <p>实现：主模板保留 CC 原 token ${teammateUseCase}/${idDescription}（便于 diff 对源），
     * 用 {@code .replace()} 替换为分支结果；teammateWorkflow 经 {@code +} 追加
     * （对齐 CC:48 无尾换行）。
     */
    private String getPrompt() {
        boolean swarms = TaskSystemConfig.isAgentSwarmsEnabled();
        String teammateUseCase = swarms ? """
            - Before assigning tasks to teammates, to see what's available
            """ : "";
        String idDescription = "- **id**: Task identifier (use with TaskGet, TaskUpdate)";
        String teammateWorkflow = swarms ? """

            ## Teammate Workflow

            When working as a teammate:
            1. After completing your current task, call TaskList to find available work
            2. Look for tasks with status 'pending', no owner, and empty blockedBy
            3. **Prefer tasks in ID order** (lowest ID first) when multiple tasks are available, as earlier tasks often set up context for later ones
            4. Claim an available task using TaskUpdate (set `owner` to your name), or wait for leader assignment
            5. If blocked, focus on unblocking tasks or notify the team lead
            """ : "";
        return LIST_MAIN_TEMPLATE
            .replace("${teammateUseCase}", teammateUseCase)
            .replace("${idDescription}", idDescription)
            + teammateWorkflow;
    }

    /**
     * 用户可见名 · 对齐 CC TaskListTool.ts:49-50 userFacingName() → 'TaskList'
     */
    @Override
    public String userFacingName() {
        // 对齐 CC TaskListTool.ts:50: return 'TaskList'
        return "TaskList";
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        // 对齐 CC TaskListTool.ts:60: return true
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输入 Schema · 对齐 CC TaskListTool.ts:13 inputSchema（空对象）
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.put("properties", JSON.createObjectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TaskListTool.ts:16-27 outputSchema
     *
     * <p>CC outputSchema（grep 实证，不信注释）：
     * <pre>
     * z.object({
     *   tasks: z.array(z.object({
     *     id, subject, status,
     *     owner: z.string().optional(),
     *     blockedBy: z.array(z.string()),
     *   }))
     * })
     * </pre>
     *
     * <p>zod v4 toJSONSchema（默认 io='output'，CC zodToJsonSchema.ts:20）序列化语义：
     * <ul>
     *   <li>顶层 {@code tasks} 为 z.object 成员 → 默认 required，序列化为根 required 数组
     *       {@code ["tasks"]}（CC TaskListTool.ts:17）</li>
     *   <li>item 内 owner 为 {@code z.string().optional()}（CC TaskListTool.ts:23）→
     *       不在 item 内部 required 数组；其余 id/subject/status/blockedBy 无 .optional()
     *       （CC TaskListTool.ts:20-22,24）→ 全部 required</li>
     *   <li>普通 z.object 在 output 模式输出 {@code additionalProperties:false}（根与 item 内部）</li>
     * </ul>
     *
     * <p>status 用严格 TaskStatusSchema()（CC TaskListTool.ts:22，即 tasks.ts:71-73
     * z.enum(['pending','in_progress','completed'])）——3 值，无 'deleted'
     * （grep 实证 CC TaskListTool.ts 无 deleted）。grep 实证 'deleted' → 0 命中。
     */
    @Override
    public JsonNode outputSchema() {
        if (log.isDebugEnabled()) {
            log.debug("TaskList.outputSchema() 构建任务列表输出契约：tasks 顶层 required + item 内部 required[id,subject,status,blockedBy]（owner 可选）");
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        ObjectNode tasksArray = JSON.createObjectNode();
        tasksArray.put("type", "array");
        ObjectNode taskItem = JSON.createObjectNode();
        taskItem.put("type", "object");
        ObjectNode taskProps = JSON.createObjectNode();
        taskProps.set("id", JSON.createObjectNode().put("type", "string"));
        taskProps.set("subject", JSON.createObjectNode().put("type", "string"));
        taskProps.set("status", JSON.createObjectNode()
            .put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        taskProps.set("owner", JSON.createObjectNode().put("type", "string"));
        taskProps.set("blockedBy", JSON.createObjectNode()
            .put("type", "array").set("items", JSON.createObjectNode().put("type", "string")));
        taskItem.set("properties", taskProps);
        // CC TaskListTool.ts:20-22,24 无 .optional() → item 内部 required；
        // owner（CC TaskListTool.ts:23 .optional()）不在 required 数组
        taskItem.set("required", JSON.createArrayNode()
            .add("id").add("subject").add("status").add("blockedBy"));
        // zod v4 output 模式普通 z.object 输出 additionalProperties:false
        taskItem.put("additionalProperties", false);
        tasksArray.set("items", taskItem);
        properties.set("tasks", tasksArray);

        schema.set("properties", properties);
        // CC TaskListTool.ts:17 顶层 tasks 为 z.object 默认 required → 根 required 数组
        schema.set("required", JSON.createArrayNode().add("tasks"));
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
            new PermissionDecisionReason.Other("task_list_allow"),
            null, false, null, List.of());
    }

    // [B 组对齐 2026-08-04] 删除 toAutoClassifierInput override (返回 NAME 的旧实现) —
    //   CC TaskListTool 无 override (Tool.ts:767 默认 ''), 落回接口默认空串 = 跳过分类器
    //   (CC :754 注释 "security-relevant tools must override"). 原 D-2 声称删除但残留 (对抗核验缺口).

    /**
     * 工具使用消息渲染 · 对齐 CC TaskListTool.ts:63: return null
     */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 核心执行逻辑 · 对齐 CC TaskListTool.ts:65-115
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 执行 TaskList · 对齐 CC TaskListTool.ts:65-115 call() + mapToolResultToToolResultBlockParam()
     *
     * <p>核心流程（CC TaskListTool.ts:65-115）：
     * <ol>
     *   <li>获取任务列表</li>
     *   <li>过滤 _internal 元数据任务（对齐 CC: filter t.meta?._internal）</li>
     *   <li>构建已解决任务 ID 集合，过滤 blockedBy 引用</li>
     *   <li>格式化输出（每行：#ID [status] subject (owner) [blocked by #X]）</li>
     * </ol>
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        // Step 1: 获取任务列表（对齐 CC TaskListTool.ts:66-70）
        // CC: const taskListId = getTaskListId(); const allTasks = (await listTasks(taskListId)).filter(t => !t.metadata?._internal)
        // 逐次动态解析列表 ID（对齐 CC TaskListTool.ts:66 getTaskListId()）
        String listId = TaskService.getTaskListId();
        if (log.isDebugEnabled()) {
            log.debug("TaskList 解析列表 ID {}，列出任务", listId);
        }
        List<Task> allTasks = taskPersistence.listTasks(listId).stream()
            .filter(t -> {
                // 对齐 CC TaskListTool.ts:69: t => !t.metadata?._internal（JS 真值过滤：truthy 值即过滤）
                Map<String, Object> meta = t.metadata();
                if (meta != null && meta.containsKey("_internal")) {
                    Object internal = meta.get("_internal");
                    boolean internalTruthy = isJsTruthy(internal);
                    if (log.isDebugEnabled()) {
                        log.debug("TaskList 过滤 _internal 任务: taskId={}, _internal={}, truthy={}, 保留={}",
                            t.id(), internal, internalTruthy, !internalTruthy);
                    }
                    return !internalTruthy;
                }
                return true;
            })
            .collect(Collectors.toList());

        // Step 2: 构建已解决任务 ID 集合（对齐 CC TaskListTool.ts:73-75）
        // CC: resolvedTaskIds = allTasks.filter(t => t.status === 'completed').map(t => t.id)
        Set<String> resolvedTaskIds = allTasks.stream()
            .filter(t -> t.isCompleted())
            .map(Task::id)
            .collect(Collectors.toSet());

        // Step 3: 构建结构化输出（对齐 CC TaskListTool.ts:77-89 call() → { data: { tasks } }）
        // 空列表返回 { tasks: [] }（'No tasks found' 文本归 mapper renderToolResultText）。
        if (allTasks.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("TaskList 空列表: 返回结构化 TaskListOutput(tasks=[])");
            }
            return ToolResult.success(call.id(), new TaskListOutput(List.of()));
        }

        // 对齐 CC TaskListTool.ts:77-83: 逐任务映射 TaskSummary；blockedBy 在 data 层
        // 过滤 resolved (completed) 任务（CC: task.blockedBy.filter(id => !resolvedTaskIds.has(id))）。
        // 渲染行格式（#id [status] subject(owner)[blocked by #a,#b]）归 mapper renderToolResultText
        // （对齐 CC TaskListTool.ts:91-115 mapToolResultToToolResultBlockParam）。
        List<TaskSummary> tasks = allTasks.stream()
            .map(task -> {
                List<String> activeBlockedBy = task.blockedBy().stream()
                    .filter(id -> !resolvedTaskIds.contains(id))
                    .collect(Collectors.toList());
                return new TaskSummary(task.id(), task.subject(), task.status().toValue(),
                    task.owner(), activeBlockedBy);
            })
            .collect(Collectors.toList());

        if (log.isDebugEnabled()) {
            log.debug("TaskList 执行完成: 返回结构化 TaskListOutput 任务数={}", tasks.size());
        }
        return ToolResult.success(call.id(), new TaskListOutput(tasks));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册方法
    // ════════════════════════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════════════════════════
    // 结构化输出 record · 对齐 CC TaskListTool.ts outputSchema + 双通道 mapper
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输出 record · 对齐 CC TaskListTool.ts:16-27 outputSchema (tasks: Task[]).
     *
     * <p>CC outputSchema（grep 实证 TaskListTool.ts:16-27）：
     * <pre>
     * z.object({
     *   tasks: z.array(z.object({
     *     id: z.string(),
     *     subject: z.string(),
     *     status: TaskStatusSchema(),
     *     owner: z.string().optional(),
     *     blockedBy: z.array(z.string()),
     *   })),
     * })
     * </pre>
     * <p>{@code toString()} 委托 {@link #renderToolResultText(TaskListOutput)} 作
     * LLM 可见文本桥（concerns#1），{@code data} 承载结构化 tasks 数组供 SDK 消费方解析。
     *
     * <p>{@code @JsonInclude(NON_NULL)}（Task.java:54 先例）：null 字段序列化省略
     * （对齐 CC jsonStringify 省略 undefined）。tasks 恒非 null（空列表 []），
     * 注解实际生效面在元素 record {@link TaskSummary} 的 owner（见下）。
     *
     * @param tasks CC original: tasks (TaskListTool.ts:18-26) — 任务摘要数组（_internal 已过滤，blockedBy 已过滤 resolved）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskListOutput(List<TaskSummary> tasks) {
        @Override
        public String toString() {
            return renderToolResultText(this);
        }
    }

    /**
     * 任务摘要 record · 对齐 CC TaskListTool.ts:18-26 (tasks 数组元素).
     *
     * <p>{@code @JsonInclude(NON_NULL)}（Task.java:54 先例）：owner 为 null（未分配）
     * 时序列化省略 owner 键——对齐 CC {@code owner: z.string().optional()}
     * （TaskListTool.ts:23）+ {@code owner: task.owner} 为 undefined 时 jsonStringify
     * 省略键（TaskListTool.ts:81）。Java null ≈ CC undefined。
     *
     * @param id        CC original: id (TaskListTool.ts:20) — 任务 ID
     * @param subject   CC original: subject (TaskListTool.ts:21) — 任务标题
     * @param status    CC original: status (TaskListTool.ts:22) — TaskStatusSchema 字符串值
     * @param owner     CC original: owner (TaskListTool.ts:23) — 所有者 agent；可空
     * @param blockedBy CC original: blockedBy (TaskListTool.ts:24) — 阻塞此任务的任务 ID 列表（已过滤 resolved）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskSummary(String id, String subject, String status, String owner,
                              List<String> blockedBy) {
        public TaskSummary {
            if (log.isDebugEnabled() && owner == null) {
                log.debug("TaskSummary owner 为 null: NON_NULL 序列化将省略 owner 键（对齐 CC TaskListTool.ts:23/:81 省略 undefined）");
            }
        }
    }

    /**
     * 渲染工具结果文本 · 对齐 CC TaskListTool.ts:91-115 mapToolResultToToolResultBlockParam.
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * mapToolResultToToolResultBlockParam(content, toolUseID) {
     *   const { tasks } = content
     *   if (tasks.length === 0) return { ..., content: 'No tasks found' }
     *   const lines = tasks.map(task => {
     *     const owner = task.owner ? ` (${task.owner})` : ''
     *     const blocked = task.blockedBy.length > 0
     *       ? ` [blocked by ${task.blockedBy.map(id => `#${id}`).join(', ')}]` : ''
     *     return `#${task.id} [${task.status}] ${task.subject}${owner}${blocked}`
     *   })
     *   return { ..., content: lines.join('\n') }
     * }
     * </pre>
     *
     * @param data 结构化输出（TaskListOutput，data 承载 tasks 数组）
     * @return CC mapper content 文本（LLM 可见 tool_result content）
     */
    static String renderToolResultText(TaskListOutput data) {
        List<TaskSummary> tasks = data.tasks();
        if (tasks.isEmpty()) {
            // 对齐 CC TaskListTool.ts:93-99: tasks.length === 0 → content: 'No tasks found'
            return "No tasks found";
        }
        if (log.isDebugEnabled()) {
            log.debug("TaskList mapper 渲染 tool_result 文本: 任务数={}", tasks.size());
        }
        // 对齐 CC TaskListTool.ts:102: task.owner ? ` (${task.owner})` : ''（JS 真值：空串/undefined 不加括号）
        List<String> lines = new ArrayList<>();
        for (TaskSummary task : tasks) {
            String owner = isJsTruthy(task.owner()) ? " (" + task.owner() + ")" : "";
            String blocked = task.blockedBy().isEmpty()
                ? ""
                : " [blocked by " + task.blockedBy().stream()
                    .map(id -> "#" + id)
                    .collect(Collectors.joining(", ")) + "]";
            lines.add("#" + task.id() + " [" + task.status() + "] " + task.subject() + owner + blocked);
        }
        return String.join("\n", lines);
    }

    /**
     * JS 真值判定 · 对齐 CC TaskListTool.ts:69 `!t.metadata?._internal` 与 :102 `task.owner ? ` (${task.owner})` : ''`
     *
     * <p>JS falsy 集 = null/undefined/0/-0/""/false/NaN；其余均 truthy。CC 对 _internal（
     * tasks.ts:86 metadata: z.record(z.string(), z.unknown()) 任意 JSON 值）与 owner（
     * tasks.ts:82 z.string().optional()，string|undefined）均直接做 truthiness，不做类型收窄。
     *
     * <p>注意：不复用 {@code SettingMigrations.toBoolean} —— 那是 CC {@code Boolean(str)} env
     * 强转语义（"false"/"0" 视为 falsy，SettingMigrations.java:65），对 metadata 任意值不成立。
     * 此处按 JS truthiness：非空串（含 "false"/"0"/"1"）一律 truthy。
     *
     * @param value CC original: _internal / owner（任意 JSON 值）
     * @return JS truthiness：true=truthy，false=falsy
     */
    private static boolean isJsTruthy(Object value) {
        if (value == null) return false;                        // null/undefined → falsy
        if (value instanceof Boolean b) return b;                // false → falsy，true → truthy
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return !Double.isNaN(d) && d != 0; // 0/-0/0.0/NaN → falsy，其余 truthy（对齐 JS falsy 集）
        }
        if (value instanceof String s) return !s.isEmpty();      // 空串 → falsy；非空（含 "false"/"0"）→ truthy
        return true;                                             // List/Map/其他对象 → truthy
    }
}

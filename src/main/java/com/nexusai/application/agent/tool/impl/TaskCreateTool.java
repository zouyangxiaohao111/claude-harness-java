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
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * TaskCreate 工具 · 对齐 CC TaskCreateTool.ts（PROMPT 全量 + agent-swarms 分支；结构化输出/setAppState 见方法备注）
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = 'TaskCreate'</td><td>TaskCreateTool.ts:49</td><td>{@link #name()}</td></tr>
 *   <tr><td>inputSchema (subject, description, activeForm, metadata)</td><td>TaskCreateTool.ts:18-33</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema (task: {id, subject})</td><td>TaskCreateTool.ts:36-43</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>userFacingName() → 'TaskCreate'</td><td>TaskCreateTool.ts:64-65</td><td>{@link #userFacingName()}</td></tr>
 *   <tr><td>shouldDefer = true</td><td>TaskCreateTool.ts:67</td><td>{@link #shouldDefer(JsonNode)}</td></tr>
 *   <tr><td>isEnabled() = isTodoV2Enabled()</td><td>TaskCreateTool.ts:68-69</td><td>{@link #isEnabled()}</td></tr>
 *   <tr><td>isConcurrencySafe() → true</td><td>TaskCreateTool.ts:71-72</td><td>{@link #isConcurrencySafe(JsonNode)}</td></tr>
 *   <tr><td>toAutoClassifierInput → input.subject</td><td>TaskCreateTool.ts:74-75</td><td>{@link #toAutoClassifierInput(JsonNode)}</td></tr>
 *   <tr><td>renderToolUseMessage → null</td><td>TaskCreateTool.ts:77-78</td><td>{@link #renderToolUseMessage(JsonNode)}</td></tr>
 *   <tr><td>call() 核心逻辑</td><td>TaskCreateTool.ts:80-129</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>hook 9 参数 + blocking error 回滚</td><td>TaskCreateTool.ts:92-113</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>auto-expand task list</td><td>TaskCreateTool.ts:116-119</td><td>{@link #execute(ToolUseBlock, ToolUseContext)} 已实现 setAppState（session AppState 写入；UI 投递待 Stage 3.3）</td></tr>
 *   <tr><td>mapToolResultToToolResultBlockParam</td><td>TaskCreateTool.ts:130-137</td><td>{@link #renderToolResultText(TaskCreateOutput)}</td></tr>
 * </table>
 *
 * @see Task
 * @see TaskService
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskCreateTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskCreateTool.class);
    private static final String NAME = "TaskCreate";

    /** 对齐 CC TaskCreateTool prompt.ts:3 DESCRIPTION = 'Create a new task in the task list' */
    private static final String DESCRIPTION = "Create a new task in the task list";

    /**
     * 主模板 · 对齐 CC TaskCreateTool prompt.ts getPrompt() 主模板 (:16-55)，逐字。
     *
     * <p>${teammateContext} 与 ${teammateTips} 为占位 token，在 {@link #getPrompt()} 内经
     * {@code TaskSystemConfig.isAgentSwarmsEnabled()} 三分支 replace 解析
     * （对齐 CC prompt.ts:5-56）：
     * <ul>
     *   <li>teammateContext：CC prompt.ts:6-8 —— swarms 时追加 ' and potentially assigned to teammates'</li>
     *   <li>teammateTips：CC prompt.ts:10-14 —— swarms 时插入 teammate 专属 Tips 两行（尾换行）</li>
     * </ul>
     */
    private static final String CREATE_MAIN_TEMPLATE = """
        Use this tool to create a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
        It also helps the user understand the progress of the task and overall progress of their requests.

        ## When to Use This Tool

        Use this tool proactively in these scenarios:

        - Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
        - Non-trivial and complex tasks - Tasks that require careful planning or multiple operations${teammateContext}
        - Plan mode - When using plan mode, create a task list to track the work
        - User explicitly requests todo list - When the user directly asks you to use the todo list
        - User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
        - After receiving new instructions - Immediately capture user requirements as tasks
        - When you start working on a task - Mark it as in_progress BEFORE beginning work
        - After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation

        ## When NOT to Use This Tool

        Skip using this tool when:
        - There is only a single, straightforward task
        - The task is trivial and tracking it provides no organizational benefit
        - The task can be completed in less than 3 trivial steps
        - The task is purely conversational or informational

        NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.

        ## Task Fields

        - **subject**: A brief, actionable title in imperative form (e.g., "Fix authentication bug in login flow")
        - **description**: What needs to be done
        - **activeForm** (optional): Present continuous form shown in the spinner when the task is in_progress (e.g., "Fixing authentication bug"). If omitted, the spinner shows the subject instead.

        All tasks are created with status `pending`.

        ## Tips

        - Create tasks with clear, specific subjects that describe the outcome
        - After creating tasks, use TaskUpdate to set up dependencies (blocks/blockedBy) if needed
        ${teammateTips}- Check TaskList first to avoid creating duplicate tasks
        """;

    private final TaskService taskPersistence;
    private final HookRegistry hookRegistry;

    /** s12-3.2: Spring 构造器注入（按类型解析，无 taskListId）· 对齐 CC TaskCreateTool call() 内逐次 getTaskListId() */
    public TaskCreateTool(TaskService taskPersistence, HookRegistry hookRegistry) {
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
     * 搜索提示 · 对齐 CC TaskCreateTool.ts:50 searchHint = 'create a task in the task list'
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * searchHint: 'create a task in the task list',
     * </pre>
     * 供 ToolSearch 关键词评分（CC ToolSearchTool.ts:282-285 命中 +4 分）。值逐字对齐
     * CC 工具真源，满足 Tool.ts:378 约束（3-10 词、无尾句号）。
     */
    @Override
    public String searchHint() {
        // 对齐 CC TaskCreateTool.ts:50: 'create a task in the task list'
        return "create a task in the task list";
    }

    /**
     * 工具 prompt · 对齐 CC TaskCreateTool.ts:55-57 prompt() → getPrompt()
     *
     * <p>每次调用按 {@code TaskSystemConfig.isAgentSwarmsEnabled()} 实时三分支拼装
     * （对齐 CC prompt.ts:5-56），不缓存——与 CC 动态行为一致。
     */
    @Override
    public String prompt() {
        String prompt = getPrompt();
        if (log.isDebugEnabled()) {
            log.debug("TaskCreate.prompt() 返回 {} 字符 PROMPT（swarms={}）",
                prompt.length(), TaskSystemConfig.isAgentSwarmsEnabled());
        }
        return prompt;
    }

    /**
     * 动态拼装 PROMPT · 镜像 CC TaskCreateTool prompt.ts:5-56 getPrompt()
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * export function getPrompt(): string {
     *   const teammateContext = isAgentSwarmsEnabled()
     *     ? ' and potentially assigned to teammates' : ''
     *   const teammateTips = isAgentSwarmsEnabled()
     *     ? `- Include enough detail in the description for another agent to understand and complete the task
     * - New tasks are created with status 'pending' and no owner - use TaskUpdate with the \`owner\` parameter to assign them
     * `
     *     : ''
     *   return `...${teammateContext}...${teammateTips}...`
     * }
     * </pre>
     *
     * <p>实现：主模板保留 CC 原 token ${teammateContext}/${teammateTips}（便于 diff 对源），
     * 用 {@code .replace()} 替换为分支结果（逐字对齐，避免 String.format 转义风险）。
     */
    private String getPrompt() {
        boolean swarms = TaskSystemConfig.isAgentSwarmsEnabled();
        String teammateContext = swarms
            ? " and potentially assigned to teammates"
            : "";
        String teammateTips = swarms ? """
            - Include enough detail in the description for another agent to understand and complete the task
            - New tasks are created with status 'pending' and no owner - use TaskUpdate with the `owner` parameter to assign them
            """ : "";
        return CREATE_MAIN_TEMPLATE
            .replace("${teammateContext}", teammateContext)
            .replace("${teammateTips}", teammateTips);
    }

    /**
     * 用户可见名 · 对齐 CC TaskCreateTool.ts:64-65 userFacingName() → 'TaskCreate'
     */
    @Override
    public String userFacingName() {
        // 对齐 CC TaskCreateTool.ts:65: return 'TaskCreate'
        return "TaskCreate";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输入 Schema · 对齐 CC TaskCreateTool.ts:18-33 inputSchema
     *
     * <p>CC inputSchema（TaskCreateTool.ts:18-33）：
     * <pre>
     * z.strictObject({
     *   subject: z.string(),
     *   description: z.string(),
     *   activeForm: z.string().optional(),
     *   metadata: z.record(z.string(), z.unknown()).optional(),
     * })
     * </pre>
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("subject", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "A brief title for the task"));
        properties.set("description", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "What needs to be done"));
        properties.set("activeForm", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "Present continuous form shown in spinner when in_progress (e.g., 'Running tests')"));
        properties.set("metadata", JSON.createObjectNode()
            .put("type", "object")
            .put("description", "Arbitrary metadata to attach to the task"));

        schema.set("properties", properties);
        // 对齐 CC TaskCreateTool.ts:22-23 — subject + description 必填
        schema.set("required", JSON.createArrayNode().add("subject").add("description"));
        schema.put("additionalProperties", false);

        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TaskCreateTool.ts:36-43 outputSchema
     *
     * <p>CC outputSchema（TaskCreateTool.ts:36-43）：
     * <pre>
     * z.object({
     *   task: z.object({ id: z.string(), subject: z.string() }),
     * })
     * </pre>
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        ObjectNode taskObj = JSON.createObjectNode();
        taskObj.put("type", "object");
        ObjectNode taskProps = JSON.createObjectNode();
        taskProps.set("id", JSON.createObjectNode().put("type", "string"));
        taskProps.set("subject", JSON.createObjectNode().put("type", "string"));
        taskObj.set("properties", taskProps);
        properties.set("task", taskObj);

        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限 / 分类
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 权限检查 · 对齐 CC checkPermissions() → { behavior: 'allow', updatedInput: input }
     *
     * <p>CC TaskCreateTool 没有显式定义 checkPermissions，使用 CC Tool.ts 默认行为。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        // 对齐 CC 默认 behavior: 'allow' — 任务创建不需要权限检查
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("task_create_allow"),
            null, false, null, List.of());
    }

    /**
     * 自动分类器输入 · 对齐 CC TaskCreateTool.ts:74-75 toAutoClassifierInput()
     *
     * <p>CC 源码：{@code return input.subject}——无 fallback（D-TC-2 已删除）。
     * 缺 subject 返回 null（等价 CC undefined，消费侧 {@code ?? input} 回退；
     * 实际不可达：inputSchema subject 必填，TaskCreateTool.ts:18-33）。
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC TaskCreateTool.ts:75: return input.subject（无 fallback，不回退 NAME）
        if (input == null || !input.has("subject")) {
            return null;
        }
        return input.get("subject").asText();
    }

    /**
     * 工具使用消息渲染 · 对齐 CC TaskCreateTool.ts:77-78 renderToolUseMessage() → null
     */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        // 对齐 CC TaskCreateTool.ts:78: return null
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 核心执行逻辑 · 对齐 CC TaskCreateTool.ts:80-137
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [hook-9args] 单参执行 · 委托 2 参版本（无 ctx → 场景如直接调用/测试，permissionMode 为 null；
     * abortController 按 OPD-WF4-LC-01 恒 null）。
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行 TaskCreate · 对齐 CC TaskCreateTool.ts:80-137 call() + mapToolResultToToolResultBlockParam()
     *
     * <p>核心流程（CC TaskCreateTool.ts:80-137）：
     * <ol>
     *   <li>解析输入参数（subject, description, activeForm, metadata）</li>
     *   <li>调用 createTask() 创建任务文件</li>
     *   <li>执行 TaskCreated hooks（收集 blocking errors）</li>
     *   <li>如果有 blocking error → 删除任务 + 返回错误</li>
     *   <li>Auto-expand task list（CC setAppState）</li>
     *   <li>构建返回消息</li>
     * </ol>
     *
     * <p>[hook-9args] 由单参 {@link #execute(ToolUseBlock)} 委托升级到 {@code execute(call, ctx)}：
     * ToolRegistry:658 以 2 参 {@code tool.execute(call, ctx)} 派发 → 本 2 参重载可拿到真实
     * {@link ToolUseContext}（permissionMode），对齐 CC executeTaskCreatedHooks 9 参
     * （utils/hooks.ts:3745-3755）；abortController 按 OPD-WF4-LC-01 收敛为 null
     * （CC TaskCreateTool.ts:93-103 实传 9 参，第 7 参 signal 不写 hookInput）。
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();

        // Step 1: 解析输入（对齐 CC TaskCreateTool.ts:80）
        String subject = input.has("subject") ? input.get("subject").asText() : "";
        String description = input.has("description") ? input.get("description").asText() : "";
        // OPD-TS-12：缺省 activeForm 不物化为 subject（CC TaskCreateTool.ts:22 optional，
        // 未提供传 undefined → jsonStringify 省略键）——null 透传由 @JsonInclude(NON_NULL) 省略键。
        String activeForm = input.has("activeForm") ? input.get("activeForm").asText() : null;
        // 无 blank-subject 拦截（D-TC-1 已删除）：CC inputSchema subject 为 z.string() 无 min
        // （TaskCreateTool.ts:20），空串合法，照常建任务（tasks.ts:78/284-308）；null/undefined
        // 由 zod 校验层拒绝，Java 侧由 Task 构造器 null 拒绝兜底（OD-TC-4）。
        if (log.isDebugEnabled()) {
            log.debug("TaskCreate 解析输入完成: subject='{}' description='{}' activeForm='{}'"
                    + "（空 subject 走 CC 良性路径建任务）",
                subject, description, activeForm);
        }

        // 解析 metadata（对齐 CC TaskCreateTool.ts:29-31 metadata 可选；嵌套结构递归保留，对齐 CC z.unknown 原样存储）
        // LinkedHashMap 保顶层键序 = CC JSON.stringify 按插入序写键（tasks.ts:300 落盘）
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (input.has("metadata") && input.get("metadata").isObject()) {
            JsonNode metadataNode = input.get("metadata");
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                metadata.put(entry.getKey(), jsonNodeToObject(entry.getValue()));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("TaskCreate 解析 metadata 完成: {} 个键, 嵌套对象/数组以 Map/List 递归保留（对齐 CC z.unknown）",
                metadata.size());
        }

        // Step 2: 创建任务（对齐 CC TaskCreateTool.ts:81-90 createTask）
        // CC: const taskId = await createTask(getTaskListId(), { subject, description, ... })
        Task task = new Task(null, subject, description, activeForm, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), metadata);

        String taskId;
        try {
            // 逐次动态解析列表 ID（对齐 CC TaskCreateTool.ts:81 createTask(getTaskListId(), ...)）
            String listId = TaskService.getTaskListId();
            if (log.isDebugEnabled()) {
                log.debug("TaskCreate 解析列表 ID {}，创建任务", listId);
            }
            taskId = taskPersistence.createTask(listId, task);
        } catch (Exception e) {
            log.error("Failed to create task: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "Failed to create task: " + e.getMessage());
        }

        // Step 3: 执行 TaskCreated hooks（对齐 CC TaskCreateTool.ts:92-109）
        // CC: const generator = executeTaskCreatedHooks(taskId, subject, description,
        //       getAgentName(), getTeamName(), undefined, context?.abortController?.signal,
        //       undefined, context)   ← 共 9 参（hooks.ts:3745-3755）
        //
        // [hook-9args] 9 参透传（CC TaskCreateTool.ts:93-103 实传 9 参）：
        //   taskId / subject / description / getAgentName() / getTeamName() /
        //   permissionMode（第 6 参 = undefined → null）/
        //   signal（第 7 参 = context?.abortController?.signal → 收敛为 null，见下方注释）/
        //   timeoutMs（第 8 参 = undefined → 默认 TOOL_HOOK_EXECUTION_TIMEOUT_MS=10 分钟，
        //     Java 端由 HookRegistry 每 hook 超时承载，HookRegistry.java:393）/
        //   toolUseContext（第 9 参 = context → 不可序列化进 hook 事件，Java 传 null，H6 先例）
        // Java 端从真实 ToolUseContext（2 参 execute 的 ctx）取 permissionMode；
        // abortController 按 OPD-WF4-LC-01 收敛为 null（CC TaskCreatedHookInput 无 abort 载荷）。
        List<String> blockingErrors = new ArrayList<>();
        if (hookRegistry != null) {
            try {
                // CC: executeTaskCreatedHooks(taskId, subject, description, getAgentName(), getTeamName(),
                //     permissionMode, signal, timeoutMs, toolUseContext)
                // Java: 传 agentName/teamName 从 TaskSystemConfig 获取；permissionMode 从 ctx 获取；
                //   signal 收敛为 null（OPD-WF4-LC-01，见下方注释）
                String agentName = TaskSystemConfig.getAgentName();
                String teamName = TaskSystemConfig.getTeamName();
                String permissionMode = ctx != null && ctx.permissionMode() != null
                    ? ctx.permissionMode().name() : null;
                // [OPD-WF4-LC-01] abortController data 收敛为 null：CC TaskCreatedHookInputSchema
                // （coreSchemas.ts:601-612）无 abort_signal_cancelled/reason 载荷——CC
                // executeTaskCreatedHooks 的 signal 仅作 executeHooks 控制参数（hooks.ts:3766-3772），
                // 不进 hookInput。Java 端此前把 ctx.abortController() 状态写入 hook event data
                // （HookEventData.TaskCreated 第 6/7 字段）属 Java 独有扩展、无 gating 语义
                // （abort 门控由 HookRegistry.executeEvent* 入口 parentAbort 检查 / 调用方承载，
                // 不依赖本 data 字段）。OPD-WF4-LC-01 拍板收敛为 null：TaskCreateTool 生产不再写
                // abort 状态进 hook 事件载荷（第 7 参 signal 恒传 null）。参考先例：LlmAgentLoop
                // 决策 2-2（teammate taskCompleted 传 null，LlmAgentLoop.java:5656-5665）。
                // [S15] session_id 补传（OD-TC-2 工具侧落地）：CC createBaseHookInput 的
                // session_id 恒有（hooks.ts:315 resolvedSessionId = sessionId ?? getSessionId()，
                // TaskCreated hookInput 即含之，hooks.ts:3756-3764）。Java 取 ctx.sessionId()
                // （主会话 UUID；子 Agent 上下文继承主会话，ToolUseContext.with :1314）；
                // ctx == null（单参 execute 测试路径）维持 null 回退——buildJsonInput :1298
                // 非 null 才写 session_id 键，null 即 CC 无 session 上下文语义。
                String sessionId = ctx != null ? ctx.sessionId() : null;
                HookEvent event = HookEvent.taskCreated(
                    taskId, subject, description, agentName, teamName, sessionId, null,
                    permissionMode, null);
                if (log.isDebugEnabled()) {
                    log.debug("TaskCreate 触发 TaskCreated hook 事件构造: taskId={} sessionId={}"
                        + "（CC session_id 恒有, hooks.ts:315）", taskId, sessionId);
                }
                log.info("STOP_HOOK TaskCreate 触发 TaskCreated hook: taskId={} (permissionMode={})",
                    taskId, permissionMode);
                // 对齐 CC TaskCreateTool.ts:104-108: for await (const result of generator) {
                //   if (result.blockingError) blockingErrors.push(getTaskCreatedHookMessage(result.blockingError))
                // 收集全部 hook 的 blockingError（executeEventAll 暴露全量结果，非折叠首个），
                // 每条经 getTaskCreatedHookMessage 加 'TaskCreated hook feedback:\n' 前缀（hooks.ts:1917）。
                for (GenericHook.HookResult hookResult : hookRegistry.executeEventAll(event)) {
                    if (hookResult != null && hookResult.blockingError() != null) {
                        blockingErrors.add(getTaskCreatedHookMessage(hookResult.blockingError()));
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("TaskCreate hook 聚合完成: taskId={} 阻塞错误数={}", taskId, blockingErrors.size());
                }
            } catch (Exception e) {
                // CC 中 hook 异常不阻止任务创建，仅记录日志
                log.warn("TaskCreated hook execution failed: {}", e.getMessage());
            }
        }

        // Step 4: 如果有 blocking error → 删除任务 + 返回错误（对齐 CC TaskCreateTool.ts:110-113）
        if (!blockingErrors.isEmpty()) {
            // CC: await deleteTask(getTaskListId(), taskId); throw new Error(blockingErrors.join('\n'))
            // Java 用 ToolResult.error 通道承载 CC throw（isError=true）。
            // 逐次动态解析列表 ID（对齐 CC TaskCreateTool.ts:111 deleteTask(getTaskListId(), taskId)）
            taskPersistence.deleteTask(TaskService.getTaskListId(), taskId);
            return ToolResult.error(call.id(), String.join("\n", blockingErrors));
        }

        // Step 5: Auto-expand task list（对齐 CC TaskCreateTool.ts:116-119）
        // CC 真源（grep 实证，不信注释）：
        //   context.setAppState(prev => {
        //     if (prev.expandedView === 'tasks') return prev          // :117
        //     return { ...prev, expandedView: 'tasks' as const }      // :118
        //   })
        //
        // [R32-b15 Stage 3.2 C2] Java 已有 session AppState 基础设施（LlmAgentLoop.appStateRef +
        // setAppState/getAppStateSnapshot，经 ToolUseContext.getAppState/setAppState 桥接字段注入，
        // LlmAgentLoop.java:3442-3445）——此处落地真实调用，不再留 TODO。
        // 位置语义：在 blocking-error 回滚之后（:110-113）才展开——仅任务创建成功后触发。
        // guard 对齐 CC :117（prev.expandedView==='tasks' 则 no-op 返回原引用），
        // 置位对齐 CC :118（否则拷贝 prev + 置 'tasks'）。
        // 残留 UI 投递缺口：expandedView 写入 JVM 内 session appStateRef（LlmAgentLoop.java:452），
        // 尚无 EventPublisher/STOMP 通道推到前端 React（LlmAgentLoop.java:528-529 明示 Stage 3.3 对接）；
        // 不伪造投递基础设施，也不因投递缺口删除 CC 扩展点。
        if (ctx != null) {
            ctx.setAppState().accept(prev -> {
                if ("tasks".equals(prev.get("expandedView"))) {
                    // 对齐 CC TaskCreateTool.ts:117 return prev（expandedView 已是 'tasks'，no-op）
                    return prev;
                }
                // 对齐 CC TaskCreateTool.ts:118 return { ...prev, expandedView: 'tasks' }
                // CC original: expandedView（AppStateStore.ts:95 'none' | 'tasks' | 'teammates'；默认 'none' :476）
                Map<String, Object> next = new LinkedHashMap<>(prev);
                next.put("expandedView", "tasks");
                return next;
            });
            if (log.isDebugEnabled()) {
                log.debug("[TaskCreate] 任务创建成功，展开任务视图 expandedView→tasks, session={}",
                    ctx.sessionId());
            }
        }

        // Step 6: 返回结构化输出（对齐 CC TaskCreateTool.ts:121-128 call() → { data: { task: { id, subject } } }）
        // 渲染文本 'Task #id created successfully: subject' 归 mapper renderToolResultText
        // （对齐 CC TaskCreateTool.ts:130-137 mapToolResultToToolResultBlockParam）。
        if (log.isDebugEnabled()) {
            log.debug("TaskCreate 执行完成: taskId={} subject={} 返回结构化 TaskCreateOutput(TaskRef)",
                taskId, subject);
        }
        return ToolResult.success(call.id(), new TaskCreateOutput(new TaskRef(taskId, subject)));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 将 JsonNode 值递归转为 Java Object（用于 metadata 存储）
     *
     * <p>对齐 CC TaskCreateTool.ts:29 metadata: z.record(z.string(), z.unknown()) 原样存储
     * （CC original: z.unknown, TaskCreateTool.ts:29）：嵌套对象/数组递归转换为
     * LinkedHashMap/List，不再 asText() 降级为空串（旧代码 P3 缺陷）——
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
     * 格式化 TaskCreated hook 阻塞错误 · 对齐 CC {@code getTaskCreatedHookMessage}
     * （Open-ClaudeCode/src/utils/hooks.ts:1914-1918）：
     * <pre>
     *   return `TaskCreated hook feedback:\n${blockingError.blockingError}`
     * </pre>
     *
     * <p>WHY: CC 消费方（TaskCreateTool.ts:106）把每个 result.blockingError 经本函数加前缀
     * 后收集，再 join('\n') 抛出。Java 端逐条等价。
     *
     * @param blockingError CC original: {@code blockingError} (hooks.ts:1915);
     *                      结构化阻塞错误 record（exit 2 stderr 文本在 blockingError()）
     * @return 前缀 + 阻塞错误文本，注入 LLM 作为反馈
     */
    private static String getTaskCreatedHookMessage(HookBlockingError blockingError) {
        return "TaskCreated hook feedback:\n" + blockingError.blockingError();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 结构化输出 record · 对齐 CC TaskCreateTool.ts outputSchema + 双通道 mapper
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输出 record · 对齐 CC TaskCreateTool.ts:36-43 outputSchema (task: {id, subject}).
     *
     * <p>CC outputSchema（grep 实证 TaskCreateTool.ts:36-43）：
     * <pre>
     * z.object({
     *   task: z.object({ id: z.string(), subject: z.string() }),
     * })
     * </pre>
     *
     * <p>{@code toString()} 委托 {@link #renderToolResultText(TaskCreateOutput)} 作
     * LLM 可见文本桥（concerns#1）：production 路径经
     * {@code String.valueOf(result.data())} 生成 tool_result content，保持 CC mapper
     * 渲染文本，同时 {@code data} 承载结构化 task 供 SDK 消费方解析。
     *
     * @param task CC original: task (TaskCreateTool.ts:38-41) — 新创建任务的结构化摘要
     */
    public record TaskCreateOutput(TaskRef task) {
        @Override
        public String toString() {
            return renderToolResultText(this);
        }
    }

    /**
     * 任务摘要 record · 对齐 CC TaskCreateTool.ts:38-41 (task: {id, subject}).
     *
     * @param id      CC original: id (TaskCreateTool.ts:39) — 新任务 ID
     * @param subject CC original: subject (TaskCreateTool.ts:40) — 任务标题
     */
    public record TaskRef(String id, String subject) {
    }

    /**
     * 渲染工具结果文本 · 对齐 CC TaskCreateTool.ts:130-137 mapToolResultToToolResultBlockParam.
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * mapToolResultToToolResultBlockParam(content, toolUseID) {
     *   return {
     *     tool_use_id: toolUseID,
     *     type: 'tool_result',
     *     content: `Task #${task.id} created successfully: ${task.subject}`,
     *   }
     * }
     * </pre>
     *
     * <p>CC 双通道：{@code data} 供 SDK 消费方解析结构化输出，mapper 的 content 才是
     * 发往模型的 tool_result 文本（toolExecution.ts:1292
     * {@code mappedToolResultBlock = tool.mapToolResultToToolResultBlockParam(result.data, toolUseID)}）。
     *
     * @param data 结构化输出（TaskCreateOutput，data 承载 task{id,subject}）
     * @return CC mapper content 文本（LLM 可见 tool_result content）
     */
    static String renderToolResultText(TaskCreateOutput data) {
        if (log.isDebugEnabled()) {
            log.debug("TaskCreate mapper 渲染 tool_result 文本: taskId={} subject={}",
                data.task().id(), data.task().subject());
        }
        return "Task #" + data.task().id() + " created successfully: " + data.task().subject();
    }
}

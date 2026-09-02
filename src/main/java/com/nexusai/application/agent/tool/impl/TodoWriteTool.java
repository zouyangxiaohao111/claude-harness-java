package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.eventbus.ws.TodoUpdateEvent;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

/**
 * TodoWrite 工具 · 对齐 CC TodoWriteTool.ts（PROMPT 全量；存储介质已迁移：S05 起经
 * context.setAppState 写会话级 appState.todos[todoKey]，对齐 CC :88-94，见 {@link #execute(ToolUseBlock, ToolUseContext)}）
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = 'TodoWrite'</td><td>TodoWriteTool.ts:32</td><td>{@link #name()}</td></tr>
 *   <tr><td>searchHint = 'manage the session task checklist'</td><td>TodoWriteTool.ts:33</td><td>{@link #searchHint()}（Q-1 恢复）</td></tr>
 *   <tr><td>maxResultSizeChars = 100_000</td><td>TodoWriteTool.ts:34</td><td>{@link #maxResultSizeChars()}</td></tr>
 *   <tr><td>inputSchema (todos[])</td><td>TodoWriteTool.ts:13-17</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema (oldTodos, newTodos, verificationNudgeNeeded)</td><td>TodoWriteTool.ts:20-27</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>userFacingName() → ''</td><td>TodoWriteTool.ts:48-49</td><td>{@link #userFacingName()}</td></tr>
 *   <tr><td>shouldDefer = true</td><td>TodoWriteTool.ts:51</td><td>{@link #shouldDefer(JsonNode)}</td></tr>
 *   <tr><td>isEnabled() = !isTodoV2Enabled()</td><td>TodoWriteTool.ts:53</td><td>{@link #isEnabled()}</td></tr>
 *   <tr><td>toAutoClassifierInput</td><td>TodoWriteTool.ts:55-56</td><td>{@link #toAutoClassifierInput(JsonNode)}</td></tr>
 *   <tr><td>checkPermissions → allow</td><td>TodoWriteTool.ts:58-60</td><td>{@link #checkPermissions(JsonNode, ToolUseContext)}</td></tr>
 *   <tr><td>renderToolUseMessage → null</td><td>TodoWriteTool.ts:62-63</td><td>{@link #renderToolUseMessage(JsonNode)}</td></tr>
 *   <tr><td>call() 核心逻辑</td><td>TodoWriteTool.ts:65-102</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>mapToolResultToToolResultBlockParam</td><td>TodoWriteTool.ts:104-114</td><td>{@link #execute(ToolUseBlock)} 内联</td></tr>
 *   <tr><td>verification nudge 6 条件</td><td>TodoWriteTool.ts:77-86</td><td>{@link #execute(ToolUseBlock)} 内 condition</td></tr>
 * </table>
 *
 * <h2>Verification Nudge</h2>
 * <p>对齐 CC TodoWriteTool.ts:76-86 — 当主线程 agent 关闭 3+ 个任务且没有
 * verification 步骤时，追加提醒让 LLM 生成 verification agent。
 *
 * <p>CC 完整 6 条件（TodoWriteTool.ts:77-86）：
 * <ol>
 *   <li>{@code feature('VERIFICATION_AGENT')} — 功能开关</li>
 *   <li>{@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_hive_evidence', false)} — feature flag</li>
 *   <li>{@code !context.agentId} — 仅主线程触发</li>
 *   <li>{@code allDone} — 所有任务完成</li>
 *   <li>{@code todos.length >= 3} — 至少 3 个任务</li>
 *   <li>{@code !todos.some(t => /verif/i.test(t.content))} — 无 verification 步骤</li>
 * </ol>
 *
 * @see Tool
 */
public class TodoWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);
    private static final String NAME = "TodoWrite";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * STOMP 模板 · [todo-rest-stream] 会话无关基建依赖（不违反 DC-1：无 sessionId 字段 /
     * 无构造器注入，todoKey 仍由运行时 context 解析）。由
     * {@link com.nexusai.application.agent.config.ToolRegistrationConfig#todoTaskTools()} 经
     * setter 注入（复用 handleCompactCommand token_warning 推送用 @Autowired(required=false) bean）。
     * 未注入（测试/孤立运行）→ {@link #pushTodoUpdate} warn+skip（no-op，不中断 execute）。
     */
    private SimpMessagingTemplate wsTemplate;

    /**
     * 会话级 AgentState 解析器 · [todo-rest-stream] sessionId → 主会话 AgentState
     * （SessionAgentStateRegistry::get 接线）。由 ToolRegistrationConfig.todoTaskTools() 注入
     * （照抄 {@code SkillToolImpl.setSessionStateResolver} 模式）。未接线 → execute Step5.5
     * debug skip（AgentState.todos 不写，REST 读空，推流不受影响）。
     */
    private java.util.function.Function<Object, AgentState> sessionStateResolver;

    /**
     * 会话级 SessionMapper · [R3 持久升级] sessions.todos JSON 列（V43）读写通道——跨 send/重启
     * 会话 todo 真源。由 ToolRegistrationConfig.todoTaskTools V1 分支经 {@link #setSessionMapper}
     * 注入（照抄 EffortCommand.writeSessionEffort 模板，不 setUpdatedAt）。未注入（测试/孤立
     * 运行）→ Step 5.6 warn+skip（AC-5 隔离：DB 持久化是旁路副作用，不中断 execute）。
     */
    private SessionMapper sessionMapper;

    /** [todo-rest-stream] 注入 STOMP 模板（ToolRegistrationConfig.todoTaskTools V1 分支接线）。 */
    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    /** [todo-rest-stream] 注入会话 AgentState 解析器（ToolRegistrationConfig.todoTaskTools V1 分支接线）。 */
    public void setSessionStateResolver(java.util.function.Function<Object, AgentState> sessionStateResolver) {
        this.sessionStateResolver = sessionStateResolver;
    }

    /** [R3 持久升级] 注入 SessionMapper（ToolRegistrationConfig.todoTaskTools V1 分支接线；null 注入 → Step 5.6 skip）。 */
    public void setSessionMapper(SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 常量 · 对齐 CC constants.ts + prompt.ts
    // ════════════════════════════════════════════════════════════════════════

    /** 对齐 CC prompt.ts DESCRIPTION */
    private static final String DESCRIPTION =
        "Update the todo list for the current session. To be used proactively and often to track progress and pending tasks. "
      + "Make sure that at least one task is in_progress at all times. Always provide both content (imperative) and activeForm (present continuous) for each task.";

    /**
     * PROMPT 内容 · 对齐 CC prompt.ts PROMPT 全量（含 8 个 example + ${FILE_EDIT_TOOL_NAME} 插值）
     *
     * <p>CC prompt.ts 包含完整的使用指导：何时用/何时不用 TodoWrite、8 个示例块
     * （## Examples of When to Use the Todo List @ CC:27-91 / ## Examples of When NOT
     * to Use the Todo List @ CC:93-142）、任务状态管理、任务分解策略等。
     * 全部逐字对齐（CC prompt.ts:3-181）；:121 的 ${FILE_EDIT_TOOL_NAME} 在
     * {@link #prompt()} 内经 {@link ToolNameConstants#FILE_EDIT_TOOL_NAME} 解析为 'Edit'
     * （对齐 CC FileEditTool/constants.ts:3）。
     */
    private static final String PROMPT = """
        Use this tool to create and manage a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
        It also helps the user understand the progress of the task and overall progress of their requests.

        ## When to Use This Tool
        Use this tool proactively in these scenarios:

        1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
        2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
        3. User explicitly requests todo list - When the user directly asks you to use the todo list
        4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
        5. After receiving new instructions - Immediately capture user requirements as todos
        6. When you start working on a task - Mark it as in_progress BEFORE beginning work. Ideally you should only have one todo as in_progress at a time
        7. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation

        ## When NOT to Use This Tool

        Skip using this tool when:
        1. There is only a single, straightforward task
        2. The task is trivial and tracking it provides no organizational benefit
        3. The task can be completed in less than 3 trivial steps
        4. The task is purely conversational or informational

        NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.

        ## Examples of When to Use the Todo List

        <example>
        User: I want to add a dark mode toggle to the application settings. Make sure you run the tests and build when you're done!
        Assistant: *Creates todo list with the following items:*
        1. Creating dark mode toggle component in Settings page
        2. Adding dark mode state management (context/store)
        3. Implementing CSS-in-JS styles for dark theme
        4. Updating existing components to support theme switching
        5. Running tests and build process, addressing any failures or errors that occur
        *Begins working on the first task*

        <reasoning>
        The assistant used the todo list because:
        1. Adding dark mode is a multi-step feature requiring UI, state management, and styling changes
        2. The user explicitly requested tests and build be run afterward
        3. The assistant inferred that tests and build need to pass by adding "Ensure tests and build succeed" as the final task
        </reasoning>
        </example>

        <example>
        User: Help me rename the function getCwd to getCurrentWorkingDirectory across my project
        Assistant: *Uses grep or search tools to locate all instances of getCwd in the codebase*
        I've found 15 instances of 'getCwd' across 8 different files.
        *Creates todo list with specific items for each file that needs updating*

        <reasoning>
        The assistant used the todo list because:
        1. First, the assistant searched to understand the scope of the task
        2. Upon finding multiple occurrences across different files, it determined this was a complex task with multiple steps
        3. The todo list helps ensure every instance is tracked and updated systematically
        4. This approach prevents missing any occurrences and maintains code consistency
        </reasoning>
        </example>


        <example>
        User: I need to implement these features for my e-commerce site: user registration, product catalog, shopping cart, and checkout flow.
        Assistant: *Creates a todo list breaking down each feature into specific tasks based on the project architecture*
        Let's start with user registration. This will involve creating database models, API endpoints, and frontend forms that integrate with your existing authentication system.

        <reasoning>
        The assistant used the todo list because:
        1. The user provided multiple complex features to implement in a comma separated list
        2. The todo list helps organize these large features into manageable tasks
        3. This approach allows for tracking progress across the entire implementation
        </reasoning>
        </example>

        <example>
        User: Can you help optimize my React application? It's rendering slowly and has performance issues.
        Assistant: *Reviews component structure, render patterns, state management, and data fetching*
        After analyzing your codebase, I've identified several performance issues.
        *Creates todo list with items like: 1) Implementing memoization for expensive calculations in ProductList, 2) Adding virtualization for long lists in Dashboard, 3) Optimizing image loading in Gallery component, 4) Fixing state update loops in ShoppingCart, 5) Reviewing bundle size and implementing code splitting*
        Let's start by implementing memoization for the expensive calculations in your ProductList component.

        <reasoning>
        The assistant used the todo list because:
        1. First, the assistant examined the codebase to identify specific performance issues
        2. Based on this analysis, it identified multiple optimization opportunities
        3. Performance optimization is a non-trivial task requiring multiple steps
        4. The todo list helps methodically track improvements across different components
        5. This systematic approach ensures all performance bottlenecks are addressed
        </reasoning>
        </example>

        ## Examples of When NOT to Use the Todo List

        <example>
        User: How do I print 'Hello World' in Python?
        Assistant: In Python, you can print "Hello World" with this simple code:

        python
        print("Hello World")

        This will output the text "Hello World" to the console when executed.

        <reasoning>
        The assistant did not use the todo list because this is a single, trivial task that can be completed in one step. There's no need to track multiple tasks or steps for such a straightforward request.
        </reasoning>
        </example>

        <example>
        User: What does the git status command do?
        Assistant: The git status command shows the current state of your working directory and staging area. It displays which changes have been staged, which haven't, and which files aren't being tracked by Git. This is a fundamental command for understanding the current state of your repository before making commits.

        <reasoning>
        The assistant did not use the todo list because this is an informational request with no actual coding task to complete. The user is simply asking for an explanation, not for the assistant to perform multiple steps or tasks.
        </reasoning>
        </example>

        <example>
        User: Can you add a comment to the calculateTotal function to explain what it does?
        Assistant: Sure, let me add a comment to the calculateTotal function to explain what it does.
        * Uses the ${FILE_EDIT_TOOL_NAME} tool to add a comment to the calculateTotal function *

        <reasoning>
        The assistant did not use the todo list because this is a single, straightforward task confined to one location in the code. Adding a comment doesn't require tracking multiple steps or systematic organization.
        </reasoning>
        </example>

        <example>
        User: Run npm install for me and tell me what happens.
        Assistant: I'll run the npm install command for you.

        *Executes: npm install*

        The command completed successfully. Here's the output:
        [Output of npm install command]

        All dependencies have been installed according to your package.json file.

        <reasoning>
        The assistant did not use the todo list because this is a single command execution with immediate results. There are no multiple steps to track or organize, making the todo list unnecessary for this straightforward task.
        </reasoning>
        </example>

        ## Task States and Management

        1. **Task States**: Use these states to track progress:
           - pending: Task not yet started
           - in_progress: Currently working on (limit to ONE task at a time)
           - completed: Task finished successfully

           **IMPORTANT**: Task descriptions must have two forms:
           - content: The imperative form describing what needs to be done (e.g., "Run tests", "Build the project")
           - activeForm: The present continuous form shown during execution (e.g., "Running tests", "Building the project")

        2. **Task Management**:
           - Update task status in real-time as you work
           - Mark tasks complete IMMEDIATELY after finishing (don't batch completions)
           - Exactly ONE task must be in_progress at any time (not less, not more)
           - Complete current tasks before starting new ones
           - Remove tasks that are no longer relevant from the list entirely

        3. **Task Completion Requirements**:
           - ONLY mark a task as completed when you have FULLY accomplished it
           - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
           - When blocked, create a new task describing what needs to be resolved
           - Never mark a task as completed if:
             - Tests are failing
             - Implementation is partial
             - You encountered unresolved errors
             - You couldn't find necessary files or dependencies

        4. **Task Breakdown**:
           - Create specific, actionable items
           - Break complex tasks into smaller, manageable steps
           - Use clear, descriptive task names
           - Always provide both forms:
             - content: "Fix authentication bug"
             - activeForm: "Fixing authentication bug"

        When in doubt, use this tool. Being proactive with task management demonstrates attentiveness and ensures you complete all requirements successfully.
        """;

    // ════════════════════════════════════════════════════════════════════════
    // TodoStatus 枚举 · 对齐 CC utils/todo/types.ts:4-6
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Todo 状态枚举 · 对齐 CC utils/todo/types.ts:4-6
     *
     * <p>CC 三态：pending / in_progress / completed。
     * s05-P2-5: 移除宽容 fromString（null→PENDING / "inprogress" 别名 / 未知值
     * fallback）—— 解析改在 {@link #parseTodos} 内严格校验，对齐 CC
     * {@code z.enum(['pending','in_progress','completed'])} 抛 ZodError 拒绝。
     */
    public enum TodoStatus {
        PENDING, IN_PROGRESS, COMPLETED;

        public String toValue() {
            return name().toLowerCase();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TodoItem 数据对象 · 对齐 CC utils/todo/types.ts:8-15
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Todo 项 · 对齐 CC utils/todo/types.ts:8-14 TodoItemSchema
     *
     * <p>CC 定义（types.ts:8-14）：
     * <pre>
     * z.object({
     *   content: z.string().min(1, 'Content cannot be empty'),
     *   status: TodoStatusSchema(),   // pending / in_progress / completed
     *   activeForm: z.string().min(1, 'Active form cannot be empty'),
     * })
     * </pre>
     *
     * @param content    祈使形式描述（必填）
     * @param status     状态（默认 PENDING）
     * @param activeForm 现在进行时形式（默认 = content）
     */
    public record TodoItem(
        String content,
        TodoStatus status,
        String activeForm
    ) {
        public TodoItem {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content cannot be empty");
            }
            if (status == null) status = TodoStatus.PENDING;
            if (activeForm == null || activeForm.isBlank()) {
                activeForm = content;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 构造器
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 无参构造器（DC-1 对齐 CC：无 sessionId 字段 / 无构造器注入）。
     *
     * <p>CC 真源（TodoWriteTool.ts:65）{@code call({todos}, context)} —— todoKey 完全来自
     * 运行时 context（{@code context.agentId ?? getSessionId()}，:67），工具对象本身无会话状态。
     * 旧的 {@code sessionId} 字段 + 构造器注入（默认 {@code "default"} 回退）已删除：Java 同 CC
     * 为无状态工具，会话分桶全部由 {@link #resolveTodoKey(ToolUseContext)} 在调用时解析。
     *
     * <p>装配入口：{@code ToolRegistrationConfig:217 new TodoWriteTool()}（无参，保留）。
     */
    public TodoWriteTool() {
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tool 接口实现
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        // 对齐 CC constants.ts:1: TODO_WRITE_TOOL_NAME = 'TodoWrite'
        return NAME;
    }

    /**
     * 搜索提示 · 对齐 CC TodoWriteTool.ts:33 {@code searchHint: 'manage the session task checklist'}
     *
     * <p>CC 源码（TodoWriteTool.ts:31-33）：
     * <pre>
     * export const TodoWriteTool = buildTool({
     *   name: TODO_WRITE_TOOL_NAME,
     *   searchHint: 'manage the session task checklist',
     *   maxResultSizeChars: 100_000,
     *   strict: true,
     * </pre>
     *
     * <p>CC 语义（Tool.ts:372-378）：{@code searchHint} 供 ToolSearch 关键词匹配的能力短语；
     * Java 侧 Tool 接口已提升为契约成员（Tool.java:641-648，默认 null）。TodoWrite 值必须逐字
     * 对齐 CC 真源（Q-1：此前被误删，本轮恢复）。
     */
    @Override
    public String searchHint() {
        // 对齐 CC TodoWriteTool.ts:33: searchHint: 'manage the session task checklist'
        return "manage the session task checklist";
    }

    /**
     * 工具描述 · 对齐 CC prompt.ts DESCRIPTION
     *
     * <p>CC description() 是 async，Java 是 sync（JVM 限制）。
     * 内容相同——返回 DESCRIPTION 常量。
     */
    @Override
    public String description() {
        return DESCRIPTION;
    }

    /**
     * 用户可见名 · 对齐 CC TodoWriteTool.ts:48-50 userFacingName() → ''
     *
     * <p>CC 返回空字符串——此工具没有面向用户的简短名称。
     */
    @Override
    public String userFacingName() {
        // 对齐 CC TodoWriteTool.ts:48-50: return ''
        return "";
    }

    /**
     * 工具 prompt · 对齐 CC TodoWriteTool.ts:39-41 prompt() → PROMPT
     *
     * <p>CC prompt() 是 async，Java 是 sync。内容为完整 PROMPT（含 8 个 example，
     * CC prompt.ts:27-142）；:121 的 ${FILE_EDIT_TOOL_NAME} 经
     * {@link ToolNameConstants#FILE_EDIT_TOOL_NAME} 解析为 'Edit'
     * （对齐 CC FileEditTool/constants.ts:3）。
     */
    @Override
    public String prompt() {
        String rendered = PROMPT.replace("${FILE_EDIT_TOOL_NAME}", ToolNameConstants.FILE_EDIT_TOOL_NAME);
        if (log.isDebugEnabled()) {
            log.debug("TodoWriteTool.prompt() 返回 {} 字符 PROMPT（含 8 example，FILE_EDIT_TOOL_NAME 已解析为 '{}'）",
                rendered.length(), ToolNameConstants.FILE_EDIT_TOOL_NAME);
        }
        return rendered;
    }

    /**
     * 结果落盘阈值 · 对齐 CC TodoWriteTool.ts:34 maxResultSizeChars = 100_000
     */
    @Override
    public long maxResultSizeChars() {
        // 对齐 CC TodoWriteTool.ts:34: maxResultSizeChars: 100_000
        return 100_000L;
    }

    /**
     * 是否启用 · 对齐 CC TodoWriteTool.ts:52-54 isEnabled() → !isTodoV2Enabled()
     *
     * <p>V1 vs V2 互斥：TodoWrite (V1) 仅在 Task V2 未启用时生效。
     */
    @Override
    public boolean isEnabled() {
        // 对齐 CC TodoWriteTool.ts:53: return !isTodoV2Enabled()
        return !TaskSystemConfig.isTodoV2Enabled();
    }

    /**
     * 是否延迟执行 · 对齐 CC TodoWriteTool.ts:51 shouldDefer = true
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        // 对齐 CC TodoWriteTool.ts:51: shouldDefer: true
        return true;
    }

    /**
     * 是否严格模式 · 对齐 CC TodoWriteTool.ts:35 {@code strict: true}
     *
     * <p>CC 源码（TodoWriteTool.ts:31-35）：
     * <pre>
     * export const TodoWriteTool = buildTool({
     *   name: TODO_WRITE_TOOL_NAME,
     *   searchHint: 'manage the session task checklist',
     *   maxResultSizeChars: 100_000,
     *   strict: true,
     * </pre>
     *
     * <p>strict() 对齐 CC {@code ToolDef.strict} SDK 严格模式标志（BashTool.tsx:425
     * 同模式），与 {@link Tool#unknownKeysPolicy()}（zod unknownKeys 三态）语义不同，
     * 不得复用（Tool.java:570-572 已标注）。
     *
     * <p>消费方：{@code ToolRegistry.toOpenAiToolsArray}（ToolRegistry.java:481）
     * {@code flag && tool.strict()} → {@code fn.strict=true}（CC api.ts:185-192），
     * 随后经 AnthropicSdkProvider/OpenAiSdkProvider 透传 SDK {@code .strict(true)}。
     * TodoWrite inputSchema 本身即 {@code z.strictObject}（TodoWriteTool.ts:14 拒绝未知键），
     * 与 {@code strict: true} 一致——模型不可注入额外字段。
     */
    @Override
    public boolean strict() {
        // 对齐 CC TodoWriteTool.ts:35: strict: true
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 输入 Schema · 对齐 CC TodoWriteTool.ts:13-17 inputSchema
     *
     * <p>CC inputSchema（TodoWriteTool.ts:13-17）：
     * <pre>
     * z.strictObject({
     *   todos: TodoListSchema().describe('The updated todo list'),
     * })
     * </pre>
     */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        ObjectNode todosProp = JSON.createObjectNode();
        todosProp.put("type", "array");
        todosProp.put("description", "The updated todo list");

        ObjectNode items = JSON.createObjectNode();
        items.put("type", "object");

        ObjectNode itemProperties = JSON.createObjectNode();
        // 对齐 CC utils/todo/types.ts:8-14：仅 content, status, activeForm（无 subject）
        itemProperties.set("content", JSON.createObjectNode()
            .put("type", "string")
            // 对齐 CC types.ts:10 z.string().min(1, 'Content cannot be empty') —— zod v4 toJSONSchema 广告输出 minLength:1
            .put("minLength", 1)
            .put("description", "Imperative form (e.g., 'Run tests')"));
        itemProperties.set("status", JSON.createObjectNode()
            .put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        itemProperties.set("activeForm", JSON.createObjectNode()
            .put("type", "string")
            // 对齐 CC types.ts:12 z.string().min(1, 'Active form cannot be empty') —— zod v4 toJSONSchema 广告输出 minLength:1
            .put("minLength", 1)
            .put("description", "Present continuous form (e.g., 'Running tests')"));
        items.set("properties", itemProperties);
        items.set("required", JSON.createArrayNode().add("content").add("status").add("activeForm"));
        // 对齐 CC types.ts:8 z.object —— zod v4 toJSONSchema 广告输出 additionalProperties=false
        // （与运行时 parseTodos 的 strip 语义一致化：广告层拒绝未知键）
        items.put("additionalProperties", false);

        todosProp.set("items", items);
        properties.set("todos", todosProp);
        schema.set("properties", properties);
        schema.set("required", JSON.createArrayNode().add("todos"));
        schema.put("additionalProperties", false);

        if (log.isDebugEnabled()) {
            log.debug("TodoWrite.inputSchema() 构建输入契约：item 级 minLength=1（content/activeForm，CC types.ts:10/:12）+ item additionalProperties=false（CC types.ts:8 z.object 广告），顶层 additionalProperties=false（CC :14 z.strictObject）");
        }

        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TodoWriteTool.ts:20-27 outputSchema + zodToJsonSchema 广告契约
     *
     * <p>CC outputSchema（TodoWriteTool.ts:20-27）：
     * <pre>
     * z.object({
     *   oldTodos: TodoListSchema(),
     *   newTodos: TodoListSchema(),
     *   verificationNudgeNeeded: z.boolean().optional(),
     * })
     * </pre>
     *
     * <p>严格度对齐（outputschema-strict-v1，todo-write 用户拍板翻转）：本方法 outputSchema
     * {@code additionalProperties=false}——对齐 CC <b>广告 schema</b>：CC TodoWriteTool.ts:20-26
     * 用 {@code z.object}，广告给模型时经 {@code zodToJsonSchema.ts:20 toJSONSchema()} 序列化
     * （zod v4 io='output' 默认），普通 {@code z.object} 输出 {@code additionalProperties=false}。
     * 即消费方（LLM/MCP）看到的 outputSchema 契约是严格的、拒绝未知键。
     *
     * <p>对比 {@link #inputSchema()} 同样 {@code additionalProperties=false}——对齐 CC
     * TodoWriteTool.ts:14 {@code z.strictObject}（拒绝未知键）。即 input/output 广告契约均严格；
     * CC 运行时 {@code z.object} 的 strip 语义只影响执行内部分析，不影响对外广告的 JSON Schema。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();

        // oldTodos: array of TodoItem（对齐 CC TodoWriteTool.ts:22）
        properties.set("oldTodos", createTodoListSchemaProperty("The todo list before the update"));

        // newTodos: array of TodoItem（对齐 CC TodoWriteTool.ts:23）
        properties.set("newTodos", createTodoListSchemaProperty("The todo list after the update"));

        // verificationNudgeNeeded: boolean, optional（对齐 CC TodoWriteTool.ts:24）
        properties.set("verificationNudgeNeeded", JSON.createObjectNode()
            .put("type", "boolean"));

        schema.set("properties", properties);
        // 对齐 CC TodoWriteTool.ts:20-27 —— z.object({oldTodos, newTodos, verificationNudgeNeeded: optional()})
        // zod v4 toJSONSchema 广告输出 required=[oldTodos,newTodos]（optional 字段不进 required）
        schema.set("required", JSON.createArrayNode().add("oldTodos").add("newTodos"));

        // outputschema-strict-v1: outputSchema 严格（additionalProperties=false）——对齐 CC 广告
        // schema：TodoWriteTool.ts:20-26 z.object + zodToJsonSchema.ts:20 toJSONSchema() 序列化
        // 普通 z.object 输出 additionalProperties=false。消费方按此契约拒绝未知键。
        // 对比 inputSchema() 同样 additionalProperties=false（对齐 CC :14 z.strictObject 拒绝未知键）。
        schema.put("additionalProperties", false);

        if (log.isDebugEnabled()) {
            log.debug("TodoWrite.outputSchema() 构建输出契约：additionalProperties=false（对齐 CC 广告 schema：TodoWriteTool.ts:20-26 z.object + zodToJsonSchema.ts:20 输出 additionalProperties=false），inputSchema 同为 strict（z.strictObject :14）");
        }

        return schema;
    }

    /**
     * outputSchema 的 TodoList 数组属性 · 对齐 CC {@code TodoListSchema()}（utils/todo/types.ts:17）
     * {@code z.array(TodoItemSchema())} 经 zod v4 {@code toJSONSchema()}（zodToJsonSchema.ts:20）的广告输出。
     *
     * <p>U-2（item 粒度补齐）：CC outputSchema（TodoWriteTool.ts:20-27）的 oldTodos/newTodos 与
     * inputSchema 复用同一 {@code TodoItemSchema}（types.ts:8-14），广告 JSON Schema 的 item 级
     * 结构必须与 {@link #inputSchema()} 的 item 一致：
     * <ul>
     *   <li>{@code content} / {@code activeForm} → {@code minLength:1}（types.ts:10/:12
     *       {@code z.string().min(1, ...)}）</li>
     *   <li>item 级 {@code required: [content, status, activeForm]}（z.object 全字段必填）</li>
     *   <li>item 级 {@code additionalProperties:false}（types.ts:8 z.object 广告输出）</li>
     * </ul>
     *
     * @param description 数组属性描述（CC describe() 文案）
     * @return TodoList 数组属性（含 item 级约束）
     */
    private JsonNode createTodoListSchemaProperty(String description) {
        ObjectNode prop = JSON.createObjectNode();
        prop.put("type", "array");

        ObjectNode items = JSON.createObjectNode();
        items.put("type", "object");
        ObjectNode itemProps = JSON.createObjectNode();
        // 对齐 CC types.ts:10 z.string().min(1, 'Content cannot be empty') —— zod v4 toJSONSchema 广告输出 minLength:1
        itemProps.set("content", JSON.createObjectNode()
            .put("type", "string")
            .put("minLength", 1));
        itemProps.set("status", JSON.createObjectNode()
            .put("type", "string")
            .set("enum", JSON.createArrayNode().add("pending").add("in_progress").add("completed")));
        // 对齐 CC types.ts:12 z.string().min(1, 'Active form cannot be empty') —— zod v4 toJSONSchema 广告输出 minLength:1
        itemProps.set("activeForm", JSON.createObjectNode()
            .put("type", "string")
            .put("minLength", 1));
        items.set("properties", itemProps);
        // 对齐 CC types.ts:8 z.object —— zod v4 toJSONSchema 广告输出 required=[content,status,activeForm] + additionalProperties=false
        // （与 inputSchema() 的 item 级结构一致：outputSchema 复用同一 TodoItemSchema）
        items.set("required", JSON.createArrayNode().add("content").add("status").add("activeForm"));
        items.put("additionalProperties", false);
        prop.set("items", items);
        prop.put("description", description);

        return prop;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限 / 分类
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 权限检查 · 对齐 CC TodoWriteTool.ts:58-60 checkPermissions() → allow
     *
     * <p>CC 源码（TodoWriteTool.ts:58-60）：
     * <pre>
     * async checkPermissions(input) {
     *   // No permission checks required for todo operations
     *   return { behavior: 'allow', updatedInput: input }
     * }
     * </pre>
     *
     * <p>Todo 操作不需要权限检查——直接放行。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        // 对齐 CC TodoWriteTool.ts:59-60: return { behavior: 'allow', updatedInput: input }
        // 对齐 CC TodoWriteTool.ts:59: return { behavior: 'allow', updatedInput: input }
        return new PermissionResult.Allow(
            input,   // 对齐 CC updatedInput: input（不修改 input）
            new PermissionDecisionReason.Other("todo_allow"),
            null,    // toolUseID（可为 null）
            false,   // userModified
            null,    // acceptFeedback
            List.of()); // contentBlocks
    }

    /**
     * 自动分类器输入 · 对齐 CC TodoWriteTool.ts:55-56 toAutoClassifierInput()
     *
     * <p>CC 源码（TodoWriteTool.ts:55-56）：
     * <pre>return `${input.todos.length} items`</pre>
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC TodoWriteTool.ts:56: return `${input.todos.length} items`
        int count = 0;
        if (input != null && input.has("todos") && input.get("todos").isArray()) {
            count = input.get("todos").size();
        }
        return count + " items";
    }

    /**
     * 工具使用消息渲染 · 对齐 CC TodoWriteTool.ts:62-63 renderToolUseMessage() → null
     *
     * <p>CC 返回 null——TodoWrite 不产生用户可见的工具使用消息。
     */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        // 对齐 CC TodoWriteTool.ts:63: return null
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 核心执行逻辑 · 对齐 CC TodoWriteTool.ts:65-102 call()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 执行 TodoWrite（无 context 单参重载）· 委托给 {@link #execute(ToolUseBlock, ToolUseContext)}
     *
     * <p>DC-1（对齐 CC）：CC 的 {@code call({todos}, context)} 恒有 context（TodoWriteTool.ts:65），
     * Java 无 context 单参入口同样<b>显式失败</b>——委托 {@code execute(call, null)} 后由
     * {@link #resolveTodoKey(ToolUseContext)} 抛 {@link IllegalStateException}，不再回退
     * 旧构造器 sessionId（"default" 桶）静默执行。生产路径 StreamingToolExecutor 以 3 参
     * dispatch（StreamingToolExecutor.java:1771-1772 {@code tool.execute(call, ctx, onProgress)}），
     * ctx 恒非 null；本单参入口仅因 Tool 接口契约要求而保留。
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行 TodoWrite · 对齐 CC TodoWriteTool.ts:65-114 call(input, context) + mapToolResultToToolResultBlockParam()
     *
     * <p>s05-P1-2（含 P2-7 一并修复）：使用 s02-P1 引入的 execute(call, ctx) 重载，
     * 从 ToolUseContext 读运行时 agentId 做 per-call 分桶 —— 对齐 CC
     * {@code const todoKey = context.agentId ?? getSessionId()}（TodoWriteTool.ts:67）。
     *
     * <p>核心流程（CC TodoWriteTool.ts:65-114）：
     * <ol>
     *   <li>获取 todoKey（agentId ?? sessionId）</li>
     *   <li>解析旧 todos + 新 todos</li>
     *   <li>检查 allDone → 全完成则清空列表</li>
     *   <li>Verification nudge 6 条件检查</li>
     *   <li>存储经 context.setAppState 写会话级 appState.todos[todoKey]（对齐 CC :88-94）</li>
     *   <li>构建返回消息（base + nudge）</li>
     * </ol>
     *
     * @param call 工具调用请求
     * @param ctx  运行时上下文（DC-1：必填；null 由 {@link #resolveTodoKey} 抛
     *             {@link IllegalStateException} 显式失败，对齐 CC call 恒有 context）
     * @return 执行结果
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();

        // Step 0: 解析 todoKey（对齐 CC TodoWriteTool.ts:67）—— ctx==null 在此抛异常（DC-1）
        String todoKey = resolveTodoKey(ctx);
        if (log.isDebugEnabled()) {
            log.debug("TodoWrite execute: todoKey={}", todoKey);
        }

        // Step 1: 解析新 todo 列表（对齐 CC TodoWriteTool.ts:65 — Zod 验证失败拒绝整个调用）
        List<TodoItem> newTodos;
        try {
            newTodos = parseTodos(input);
        } catch (IllegalArgumentException e) {
            log.warn("TodoWrite input rejected: todoKey={} reason={}", todoKey, e.getMessage());
            return ToolResult.error(call.id(), "InputValidationError: " + e.getMessage());
        }

        // Step 2: 获取旧 todo 列表（对齐 CC TodoWriteTool.ts:68）
        // CC: const oldTodos = appState.todos[todoKey] ?? []
        // 数据流：会话 appState（LlmAgentLoop.appStateRef）→ ctx.getAppState 快照 → oldTodos
        // DC-1: resolveTodoKey 已保证 ctx 非 null（否则抛异常），无 ctx==null 空列表回退分支。
        List<TodoItem> oldTodos = readTodosFromAppState(
            ctx.getAppState().apply(java.util.Collections.emptyMap()), todoKey);

        // Step 3: 判断所有任务是否完成（对齐 CC TodoWriteTool.ts:69）
        // CC: const allDone = todos.every(_ => _.status === 'completed')
        boolean allDone = newTodos.stream().allMatch(t -> t.status() == TodoStatus.COMPLETED);

        // Step 4: 全完成则清空列表（对齐 CC TodoWriteTool.ts:70）
        // CC: const newTodos = allDone ? [] : todos
        List<TodoItem> storedTodos = allDone ? List.of() : newTodos;

        // Step 5: 存储经 context.setAppState 写会话级 appState.todos[todoKey]
        // 对齐 CC TodoWriteTool.ts:88-94：
        //   context.setAppState(prev => ({
        //     ...prev,
        //     todos: { ...prev.todos, [todoKey]: newTodos },
        //   }))
        // 数据流：storedTodos → ctx.setAppState（会话级，LlmAgentLoop.appStateRef）→ appState.todos[todoKey]
        // 幂等覆盖；allDone 时写入空数组（键保留，CC :70/:92 语义）
        // DC-1: resolveTodoKey 已保证 ctx 非 null，存储无条件执行（对齐 CC call 恒有 context）。
        ctx.setAppState().accept(prev -> {
            Map<String, Object> next = new java.util.HashMap<>(prev);
            Map<String, Object> nextTodos = new java.util.HashMap<>();
            Object prevTodos = prev.get("todos");
            if (prevTodos instanceof Map<?, ?> m) {
                m.forEach((k, v) -> nextTodos.put(String.valueOf(k), v));
            }
            nextTodos.put(todoKey, storedTodos);
            next.put("todos", nextTodos);
            return next;
        });
        if (log.isDebugEnabled()) {
            log.debug("TodoWrite 存储: appState.todos[{}] 写入 {} 项{}", todoKey, storedTodos.size(),
                allDone ? "（allDone 清空）" : "");
        }

        // Step 5.5: 同步会话级 AgentState.todos 桶（REST 读侧载体，与 appStateRef 双写不漂移）
        // 与 appStateRef 解耦：appStateRef（LlmAgentLoop）供 loop 自身 todo reminder 读；
        // AgentState.todos 经 SessionAgentStateRegistry 供 TodoStatusController REST / 前端读。
        // 同一存储点双写（本块 + 上方 setAppState），无并发漂移；resolver 未接线/会话不可达 → debug skip。
        if (ctx != null && ctx.sessionId() != null && sessionStateResolver != null) {
            AgentState sessionState = sessionStateResolver.apply(ctx.sessionId());
            if (sessionState != null) {
                sessionState.setTodos(todoKey, storedTodos);
                if (log.isDebugEnabled()) {
                    log.debug("TodoWrite AgentState 同步: todos[{}] 写入 {} 项{}", todoKey, storedTodos.size(),
                        allDone ? "（allDone 清空）" : "");
                }
            }
        }

        // Step 5.6: sessions.todos DB 持久化（[R3 持久升级] 跨 send/重启会话 todo 真源，V43 列）
        // DB-first 读侧（TodoStatusController REST / SessionDto.todos / doRun 回读注入）都以本列承载。
        // 全 map 写入：appState.todos 现含 todoKey→storedTodos（Step 5 刚写），读 ctx.getAppState
        // 快照全 map → todosMapToJson 规范化（status 小写）→ sessionMapper.update。全桶空 map →
        // 存 null（对齐 disabled_tools 空集合→null 惯例）；null 必须 update(s,false) 显式写 NULL
        // （MyBatis-Flex update(entity) 默认忽略 null 字段，镜像 EffortCommand:353 /
        // SessionService.setTeamContext:352）。AC-5 隔离：sessionMapper 未注入 / 会话不存在 /
        // 查询或更新异常 → warn+skip 不中断 execute（镜像 pushTodoUpdate try/catch 不外抛模式）。
        if (ctx != null && ctx.sessionId() != null && sessionMapper != null) {
            try {
                SessionRecord sessionRecord = sessionMapper.selectOneById(ctx.sessionId());
                if (sessionRecord == null) {
                    log.warn("TodoWrite DB 持久化跳过: 会话 {} 不存在（sessions.todos 列未写）", ctx.sessionId());
                } else {
                    Map<String, Object> appStateSnapshot =
                        ctx.getAppState().apply(java.util.Collections.emptyMap());
                    Map<String, List<TodoItem>> fullMap = new HashMap<>();
                    Object todosObj = appStateSnapshot.get("todos");
                    if (todosObj instanceof Map<?, ?> todosMap) {
                        todosMap.forEach((k, v) -> {
                            if (v instanceof List<?> list) {
                                List<TodoItem> items = new ArrayList<>();
                                for (Object o : list) {
                                    if (o instanceof TodoItem item) {
                                        items.add(item);
                                    }
                                }
                                fullMap.put(String.valueOf(k), items);
                            }
                        });
                    }
                    String json = fullMap.isEmpty() ? null : todosMapToJson(fullMap);
                    sessionRecord.setTodos(json);
                    if (json == null) {
                        sessionMapper.update(sessionRecord, false);   // 显式写 NULL（全桶空清空语义）
                    } else {
                        sessionMapper.update(sessionRecord);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("TodoWrite DB 持久化: session={} 列 todos 写入 {} 桶{}",
                            ctx.sessionId(), fullMap.size(),
                            fullMap.isEmpty() ? "（全桶空 → null）" : "");
                    }
                }
            } catch (Exception e) {
                log.warn("TodoWrite DB 持久化失败（不中断 execute）: todoKey={} err={}", todoKey, e.getMessage());
            }
        }

        // Step 5.7: STOMP 推流 TodoUpdateEvent（session 级 topic /topic/sessions/{sessionId}/todos）
        // AC-5 隔离：wsTemplate 未注入 / 会话不可达 → warn+skip / 绝不外抛（防 execute 外层 catch
        // 把成功 TodoWrite 翻成 ToolResult.error）。推送内容用 storedTodos（含 allDone 空列表，
        // 与 appState 实际持久值 / REST 读侧一致）。
        pushTodoUpdate(ctx, todoKey, storedTodos);

        // Step 6: Verification nudge 6 条件检查（对齐 CC TodoWriteTool.ts:77-86）
        boolean verificationNudgeNeeded = checkVerificationNudge(allDone, newTodos, ctx);

        // Step 7: 构建返回消息（对齐 CC TodoWriteTool.ts:104-114 mapToolResultToToolResultBlockParam）
        String baseMessage = "Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress. Please proceed with the current tasks if applicable";
        // 对齐 CC TodoWriteTool.ts:106-108 nudge 消息；subagent_type 引用 AgentToolConstants.VERIFICATION_AGENT_TYPE
        // （='verification'，CC AgentTool/constants.ts:4）——A21 常量化，文案字节不变
        String nudgeMessage = verificationNudgeNeeded
            ? "\n\nNOTE: You just closed out 3+ tasks and none of them was a verification step. Before writing your final summary, spawn the verification agent (subagent_type=\""
                + AgentToolConstants.VERIFICATION_AGENT_TYPE
                + "\"). You cannot self-assign PARTIAL by listing caveats in your summary — only the verifier issues a verdict."
            : "";
        if (log.isDebugEnabled() && verificationNudgeNeeded) {
            log.debug("TodoWrite nudge 文案构建：subagent_type={}（AgentToolConstants.VERIFICATION_AGENT_TYPE，CC AgentTool/constants.ts:4）", AgentToolConstants.VERIFICATION_AGENT_TYPE);
        }

        // Step 8: s05-P2-9 — 构建 outputSchema 对应的 structured output
        // 对齐 CC TodoWriteTool.ts:96-102: return {data: {oldTodos, newTodos: todos, verificationNudgeNeeded}}
        // 注意 CC 的 newTodos 是输入 todos（非 allDone 清空后的列表）
        ObjectNode structured = JSON.createObjectNode();
        structured.set("oldTodos", todoListToArray(oldTodos));
        structured.set("newTodos", todoListToArray(newTodos));
        structured.put("verificationNudgeNeeded", verificationNudgeNeeded);

        log.info("TodoWrite stored: todoKey={} old={} new={} allDone={} nudge={}",
            todoKey, oldTodos.size(), newTodos.size(), allDone, verificationNudgeNeeded);

        // [A1·退役 metadata] 旧 success(id, data, Map metadata) 改走 successWithStructuredOutput
        // (metadata 的 "structured_output" 即 CC 结构化输出语义, 折入 ToolResult.structuredOutput, 对齐 CC data:T).
        return ToolResult.successWithStructuredOutput(call.id(), baseMessage + nudgeMessage,
            Map.of("structured_output", structured.toString()));
    }

    /**
     * s05-P2-9: TodoItem 列表 → outputSchema TodoList JSON 数组
     *
     * <p>[todo-rest-stream] 可见性 private → public static：TodoUpdateEvent（推流）与
     * TodoStatusController（REST）共用同一序列化器，status 经 {@code TodoStatus.toValue()}
     * 小写（pending/in_progress/completed），防止 TodoStatus 无 @JsonValue 时 Jackson 直出大写。
     */
    public static ArrayNode todoListToArray(List<TodoItem> todos) {
        ArrayNode arr = JSON.createArrayNode();
        for (TodoItem t : todos) {
            ObjectNode n = JSON.createObjectNode();
            n.put("content", t.content());
            n.put("status", t.status().toValue());
            n.put("activeForm", t.activeForm());
            arr.add(n);
        }
        return arr;
    }

    /**
     * [R3 持久升级] todos 全 map → sessions.todos 列 JSON 串（Jackson 规范形）·
     * 对齐 CC appState.todos {todoKey: TodoItem[]}（TodoWriteTool.ts:65-94）。
     *
     * <p>每桶经 {@link #todoListToArray} 序列化 → status 小写（pending/in_progress/completed，
     * CC types.ts:4-6 值域），防 TodoStatus 无 @JsonValue 时 Jackson 直出大写（JSON 序列化阻抗风险）。
     * 空 map / null → null（对齐 disabled_tools 空集合→null 惯例）。
     *
     * @param todosMap todoKey → TodoItem 列表（null 视为空 map）
     * @return JSON 串（null = 全桶空）；如 {@code {"sess-xxx":[{"content":...,"status":"in_progress",...}]}}
     */
    public static String todosMapToJson(Map<String, List<TodoItem>> todosMap) {
        if (todosMap == null || todosMap.isEmpty()) {
            return null;
        }
        ObjectNode root = JSON.createObjectNode();
        for (Map.Entry<String, List<TodoItem>> e : todosMap.entrySet()) {
            root.set(e.getKey(), todoListToArray(e.getValue()));
        }
        return root.toString();
    }

    /**
     * [R3 持久升级] sessions.todos 列 JSON 串 → todos 全 map（Jackson 解析，fail-soft）·
     * 对齐 CC appState.todos {todoKey: TodoItem[]} 形态。
     *
     * <p>null / 空白 / 解析失败 / 非对象 → 空 map（fail-soft，不抛）；逐项解析，status 严格三值
     * 小写 → 枚举 switch（对齐 CC types.ts:4-6 z.enum 拒绝），非法条目跳过；空数组桶保留键
     * （对齐 CC allDone 清空语义 TodoWriteTool.ts:70/:92 —— 键在值为空数组）。
     *
     * <p>三通道契约：DB 写（Step5.6）/ doRun 回读注入（LlmAgentLoop）/ Controller REST 读，
     * 共用同一规范形——往返坏 = reminder/DTO/DB 全链漂移。
     *
     * @param json sessions.todos 列 JSON 串（可 null/空白）
     * @return todoKey → TodoItem 列表；非法/空 → 空 map
     */
    public static Map<String, List<TodoItem>> todosJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode objectNode)) {
                return java.util.Collections.emptyMap();
            }
            Map<String, List<TodoItem>> result = new HashMap<>();
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.put(field.getKey(), todosArrayToItems(field.getValue()));
            }
            return result;
        } catch (Exception e) {
            log.warn("TodoWrite todosJsonToMap 解析失败（fail-soft 空 map）: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * [R3 持久升级] JsonNode 数组 → TodoItem 列表（todosJsonToMap 内部逐项解析）。
     *
     * <p>非数组 → 空列表；逐项 status 严格三值小写 → 枚举 switch，非法条目跳过（对齐 CC
     * types.ts:4-6 z.enum 拒绝 + fail-soft 隔离坏条目不坏整桶）；content 空白 → 跳过；
     * activeForm 空白 → 回落 content（复用 TodoItem 构造器默认，CC types.ts:12 语义）。
     */
    private static List<TodoItem> todosArrayToItems(JsonNode node) {
        List<TodoItem> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isObject()) {
                continue;
            }
            String content = item.has("content") ? item.get("content").asText() : null;
            if (content == null || content.isBlank()) {
                continue;   // 非法条目：content 空白跳过
            }
            String statusStr = item.has("status") ? item.get("status").asText() : null;
            TodoStatus status = switch (statusStr == null ? "" : statusStr) {
                case "pending" -> TodoStatus.PENDING;
                case "in_progress" -> TodoStatus.IN_PROGRESS;
                case "completed" -> TodoStatus.COMPLETED;
                default -> null;
            };
            if (status == null) {
                continue;   // 非法条目：status 非三值跳过
            }
            String activeForm = item.has("activeForm") ? item.get("activeForm").asText() : null;
            result.add(new TodoItem(content, status, activeForm));
        }
        return result;
    }

    /**
     * STOMP 推流 TodoUpdateEvent · [todo-rest-stream] session 级 topic
     * {@code /topic/sessions/{sessionId}/todos}。
     *
     * <p><b>AC-5 隔离（硬性）</b>：{@code ctx == null}（execute(call, null) 路径）→ warn+skip
     * （不触碰 ctx，防 NPE）；{@code wsTemplate} 未注入 / {@code convertAndSend} 抛异常 → 内部
     * catch → warn，<b>绝不向外抛</b>——否则会被 execute 外层 catch 捕获，把成功 TodoWrite 翻成
     * {@code ToolResult.error}（WebSearchTool.publishResults :658-681 同型）。推流为旁路副作用，
     * 不中断 TodoWrite 执行。
     *
     * <p><b>载荷与读侧一致</b>：推送内容用 {@code storedTodos}（含 allDone 空列表，对齐 CC
     * TodoWriteTool.ts:70/:92 清空语义）——与 appState 实际持久值 / REST 读侧一致。
     *
     * @param ctx         工具上下文（{@code ctx.sessionId()} 为 short sess-xxx，ToolUseContext:57-58）
     * @param todoKey     todoKey（agentId ?? sessionId 解析结果）
     * @param storedTodos 存储后的 todo 列表（allDone 时为空列表）
     */
    private void pushTodoUpdate(ToolUseContext ctx, String todoKey, List<TodoItem> storedTodos) {
        if (ctx == null) {
            log.warn("TodoWrite 推流跳过: ctx 为 null（无 session 可路由 topic）");
            return;
        }
        try {
            String sessionId = ctx.sessionId();
            String topic = "/topic/sessions/" + sessionId + "/todos";
            if (wsTemplate == null) {
                log.warn("TodoWrite 推流跳过: wsTemplate 未注入 sessionId={}", sessionId);
                return;
            }
            TodoUpdateEvent evt = new TodoUpdateEvent(
                sessionId, todoKey, todoListToArray(storedTodos), System.currentTimeMillis());
            wsTemplate.convertAndSend(topic, evt);
            log.info("TodoWrite 推流: topic={} todoKey={} items={}", topic, todoKey, storedTodos.size());
        } catch (Exception e) {
            log.warn("TodoWrite 推流失败（不中断 execute）: todoKey={} err={}", todoKey, e.getMessage());
        }
    }

    /**
     * 解析 todoKey · 对齐 CC TodoWriteTool.ts:67 {@code context.agentId ?? getSessionId()}
     *
     * <p><b>CC 真源（Open-ClaudeCode/src/Tool.ts:245）</b>：
     * <pre>
     * agentId?: AgentId // Only set for subagents; use getSessionId() for session ID.
     *                  // Hooks use this to distinguish subagent calls.
     * </pre>
     * CC 的 agentId 是 optional 字段，仅子 Agent 设置、主线程为 undefined → 主线程回退全局会话
     * UUID（TodoWriteTool.ts:67 {@code context.agentId ?? getSessionId()}；
     * bootstrap/state.ts:431-433 {@code getSessionId() → STATE.sessionId}），子 Agent 用其 agentId 分桶。
     *
     * <p><b>Java 等价映射</b>（NexusAI {@link ToolUseContext#agentId()} 恒非 null，由构造保证）：
     * <ul>
     *   <li>CC 主线程 agentId=undefined → todoKey=getSessionId()
     *       ⇔ Java 主线程 agentId=null（ChatService.java:207-208 主会话传 null，
     *       RunRequest.java:13 null=主线程）→ LlmAgentLoop.buildBaseToolUseContext
     *       effectiveAgentId=sessionId 兜底（G1 修复，LlmAgentLoop.java:4940-4948）
     *       → ctx.agentId()==sessionId → todoKey=sessionId（对齐 CC getSessionId()）</li>
     *   <li>CC 子 Agent agentId 有值 → todoKey=agentId
     *       ⇔ Java 子 Agent agentId=随机 UUID（createSubagentContext.java:283-290
     *       {@code UUID agentId = (overrides...agentId()!=null) ? overrides.agentId() : UUID.randomUUID()}
     *       ：:284-286 生成子 Agent 随机 UUID；:287-290 sessionId 父继承或独立生成
     *       ⇒ 子 Agent agentId≠sessionId）→ todoKey=agentId 字符串</li>
     * </ul>
     *
     * <p><b>DC-1（对齐 CC call 恒有 context）</b>：ctx==null 不再回退构造器 sessionId（"default"
     * 桶已删除），改为抛 {@link IllegalStateException} 显式失败——CC 真源
     * {@code call({todos}, context)} 恒有 context（TodoWriteTool.ts:65），todoKey 唯一来源是
     * 运行时 context（{@code context.agentId ?? getSessionId()}，:67），不存在无 context 的分桶场景。
     * 生产经 StreamingToolExecutor 3 参 dispatch（StreamingToolExecutor.java:1771-1772）ctx 恒非
     * null；无 ctx 仅测试/REPL 路径，按显式失败处理（规则十二 Fail loud）。
     *
     * @param ctx 运行时上下文（必填，null 抛 IllegalStateException）
     * @return todoKey（子 agent=agentId 串；主线程=short sessionId）
     */
    private String resolveTodoKey(ToolUseContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException(
                "TodoWriteTool.execute 必须携带 ToolUseContext：CC call({todos}, context) 恒有 context"
                + "（TodoWriteTool.ts:65），todoKey=context.agentId ?? getSessionId()（:67），无无-context 分桶路径");
        }
        // [session-id-short] 主线程（agentId==null）用 short sessionId 作 todo 桶键，
        // 与读侧 AgentLoopContext.readTodosFromAppState 主线程回退键收敛（防 EV-TDV3-TV1-033 复发）
        return ctx.agentId() != null ? ctx.agentId().toString() : ctx.sessionId();
    }

    /**
     * Verification Nudge 完整 6 条件检查 · 对齐 CC TodoWriteTool.ts:77-86
     *
     * <p>CC 源码（TodoWriteTool.ts:77-86）：
     * <pre>
     * if (
     *   feature('VERIFICATION_AGENT') &&                                    // 条件 1
     *   getFeatureValue_CACHED_MAY_BE_STALE('tengu_hive_evidence', false) && // 条件 2
     *   !context.agentId &&                                                  // 条件 3
     *   allDone &&                                                           // 条件 4
     *   todos.length >= 3 &&                                                 // 条件 5
     *   !todos.some(t => /verif/i.test(t.content))                          // 条件 6
     * )
     * </pre>
     *
     * @param allDone  所有任务是否已完成
     * @param newTodos 新的 todo 列表
     * @param ctx      运行时上下文（isMainThread 判定用，可为 null）
     * @return true 如果需要 verification nudge
     */
    private boolean checkVerificationNudge(boolean allDone, List<TodoItem> newTodos, ToolUseContext ctx) {
        // 条件 1: VERIFICATION_AGENT 功能开关（CC: feature('VERIFICATION_AGENT')）
        if (!TaskSystemConfig.isVerificationAgentEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("TodoWrite verification nudge skipped: VERIFICATION_AGENT feature disabled");
            }
            return false;
        }

        // 条件 2: tengu_hive_evidence feature flag（CC: getFeatureValue_CACHED_MAY_BE_STALE）
        if (!TaskSystemConfig.isTenguHiveEvidenceEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("TodoWrite verification nudge skipped: tengu_hive_evidence feature disabled");
            }
            return false;
        }

        // 条件 3: 仅主线程（CC: !context.agentId）— s05-P2-8: 基于运行时 ctx 判定
        // DC-1: ctx 恒非 null（resolveTodoKey 已保证）
        if (!isMainThread(ctx)) {
            if (log.isDebugEnabled()) {
                log.debug("TodoWrite verification nudge skipped: not main thread (agentId={})",
                    ctx.agentId());
            }
            return false;
        }

        // 条件 4: 所有任务完成（CC: allDone）—— 调用方已计算
        if (!allDone) {
            return false;
        }

        // 条件 5: 至少 3 个任务（CC: todos.length >= 3）
        if (newTodos.size() < 3) {
            return false;
        }

        // 条件 6: 无 verification 步骤（CC: !todos.some(t => /verif/i.test(t.content))）
        boolean hasVerification = newTodos.stream()
            .anyMatch(t -> t.content() != null
                && t.content().toLowerCase().contains("verif"));
        if (hasVerification) {
            return false;
        }

        return true;
    }

    /**
     * 是否主线程 · 对齐 CC TodoWriteTool.ts:80 {@code !context.agentId}
     *
     * <p>s05-P2-8: 弃用字符串命名约定启发式（{@code sessionId.contains("agent-")}），
     * 改为运行时 ToolUseContext 判定。
     *
     * <p><b>CC 真源</b>（Tool.ts:245 {@code agentId?: AgentId // Only set for subagents; use getSessionId() for session ID}）：
     * CC 判主线程即 {@code !context.agentId}（agentId===undefined）。Java 端 agentId 恒非 null，
     * 运行时约定等价：<b>{@code CC agentId===undefined 判主线程 ⇔ Java agentId.equals(sessionId) 判主线程}</b>。
     * <ul>
     *   <li>主线程：agentId=null（ChatService 主会话传 null，RunRequest null=主线程）→
     *       [session-id-short] effectiveAgentId 兜底已删，ctx.agentId() 保持 null → isMainThread true</li>
     *   <li>子 Agent：agentId 为独立 packed a+16hex UUID（createSubagentContext 显式传非 null）
     *       → ctx.agentId()!=null → isMainThread false</li>
     * </ul>
     *
     * <p>DC-1：调用链 {@code execute → checkVerificationNudge → isMainThread} 已由
     * {@link #resolveTodoKey(ToolUseContext)} 保证 ctx 非 null（null 抛 IllegalStateException），
     * 故旧 {@code ctx == null} 视为主线程的回退分支已删除（对齐 CC call 恒有 context）。
     *
     * <p><b>映射（session-id-short 重写）</b>：主线程判定 = agentId==null（对齐 CC
     * {@code !context.agentId}）；原 {@code ctx.agentId().equals(ctx.sessionId())} 在
     * agentId=UUID / sessionId=String 下恒 false → 会把主线程误判为子 Agent（todo nudge
     * 抑制、token budget 错域）——死分支已删。
     *
     * @param ctx 运行时上下文
     * @return true 如果是主线程
     */
    private boolean isMainThread(ToolUseContext ctx) {
        // 对齐 CC: !context.agentId —— DC-1: ctx 恒非 null（execute 内 resolveTodoKey 保证）
        return ctx.agentId() == null;
    }


    // ════════════════════════════════════════════════════════════════════════
    // 解析工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 解析 todo 列表 · 对齐 CC utils/todo/types.ts:8-14 TodoItemSchema
     *
     * <p>未知字段（如 subject）静默剔除 — 对齐 CC z.object() 默认 strip 行为。
     * s05-P2-4: activeForm 必填（对齐 CC types.ts:12 {@code z.string().min(1)}）——
     * 缺失/空白时抛 {@link IllegalArgumentException} 拒绝整个调用，不再静默默认 content。
     *
     * @param input JSON 输入（含 todos 数组）
     * @return 解析后的 TodoItem 列表
     * @throws IllegalArgumentException item 级验证失败（对齐 CC ZodError 拒绝整个调用）
     */
    List<TodoItem> parseTodos(JsonNode input) {
        List<TodoItem> todos = new ArrayList<>();
        JsonNode todosNode = input.get("todos");
        if (todosNode == null || !todosNode.isArray()) {
            return todos;
        }
        for (int i = 0; i < todosNode.size(); i++) {
            JsonNode item = todosNode.get(i);

            // s05-P2-6: content 必填非空白（CC types.ts:10: z.string().min(1, 'Content cannot be empty')）
            // 空 content 项拒绝整个调用而非静默丢弃（规则十二: 显式失败）
            String content = item.has("content") ? item.get("content").asText() : "";
            if (content.isBlank()) {
                throw new IllegalArgumentException(
                    "todos[" + i + "].content: Content cannot be empty");
            }

            // s05-P2-5: status 必填且严格三值（CC types.ts:4-6: z.enum([...]) 抛 ZodError 拒绝）
            String statusStr = item.has("status") ? item.get("status").asText() : null;
            TodoStatus status = switch (statusStr == null ? "" : statusStr) {
                case "pending" -> TodoStatus.PENDING;
                case "in_progress" -> TodoStatus.IN_PROGRESS;
                case "completed" -> TodoStatus.COMPLETED;
                default -> throw new IllegalArgumentException(
                    "todos[" + i + "].status: Invalid enum value. Expected "
                    + "'pending' | 'in_progress' | 'completed', received '" + statusStr + "'");
            };

            // s05-P2-4: activeForm 必填（CC types.ts:12: z.string().min(1, 'Active form cannot be empty')）
            String activeForm = item.has("activeForm") ? item.get("activeForm").asText() : null;
            if (activeForm == null || activeForm.isBlank()) {
                throw new IllegalArgumentException(
                    "todos[" + i + "].activeForm: Active form cannot be empty");
            }

            todos.add(new TodoItem(content, status, activeForm));
        }
        return todos;
    }

    /**
     * 从会话 appState 快照读取 todo 桶 · 对齐 CC TodoWriteTool.ts:68 {@code appState.todos[todoKey] ?? []}
     *
     * <p>S05 存储介质迁移（OD-TDV1-1）：todo 存储从实例级 CHM 迁移到会话级 appState
     * （LlmAgentLoop.appStateRef），本方法是读写两侧共用的读适配：
     * <ul>
     *   <li>写前读 oldTodos（execute，对齐 CC TodoWriteTool.ts:68）</li>
     *   <li>reminder 注入读侧（LlmAgentLoop / AgentLoopContext，对齐 CC attachments.ts:3304-3306
     *       {@code const todos = appState.todos[todoKey] ?? []}）</li>
     * </ul>
     *
     * @param snapshot 会话 appState 快照（LlmAgentLoop.getAppStateSnapshot 语义，不可变副本）
     * @param todoKey  todoKey（agentId ?? sessionId 解析结果；null → 空列表）
     * @return 桶内 todo 列表；无桶/非 List/键缺失 → 空列表（CC {@code ?? []} 缺省语义）
     */
    public static List<TodoItem> readTodosFromAppState(Map<String, Object> snapshot, String todoKey) {
        if (snapshot == null || todoKey == null) {
            return List.of();
        }
        Object todosObj = snapshot.get("todos");
        if (!(todosObj instanceof Map<?, ?> todosMap)) {
            return List.of();
        }
        Object bucket = todosMap.get(todoKey);
        if (!(bucket instanceof List<?> list)) {
            return List.of();
        }
        List<TodoItem> result = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof TodoItem item) {
                result.add(item);
            }
        }
        return result;
    }

}

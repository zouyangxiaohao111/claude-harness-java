package com.nexusai.apis.task;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步任务统一管理 REST 意图测试（#138）· 前端任务 tab「异步任务清单」数据源
 * （docs/team-perm-timeout-frontend-prompt.md §6）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>——三个契约是前端对接的前提：
 * <ol>
 *   <li><b>全部类型</b>：清单必须合并 runner 本地 tasks ∪ TaskFrameworkService.store ——
 *       monitor_mcp / in_process_teammate / dream 仅存统一 store（MonitorMcpTaskRunner/
 *       InProcessTeammateTaskRegistry registerTask → store），裸 {@code listTasks()} 会漏类型
 *       （计划决策 D1）。测试用 store-only monitor 任务验证合并。</li>
 *   <li><b>会话级隔离</b>：sessionId 非空 → 只该会话任务；null-session 任务（main-thread spawn）
 *       不属于任何会话被排除；空/缺省 → 全部。全部停止同样会话级（用户拍板）。</li>
 *   <li><b>kill 契约</b>：404 无该任务；409 非 running（fail loud，GlobalExceptionHandler →
 *       RFC 7807）；成功时任务真实被杀到 KILLED。</li>
 * </ol>
 */
@DisplayName("[#138] TaskController 异步任务统一管理 REST")
class TaskControllerTest {

    private TaskFrameworkService framework;
    private BackgroundTaskRunner runner;
    private TaskService service;
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 直构（BackgroundTaskRunnerTest:62-63 同款）——registerAsyncAgent 只注册不跑进程；
        // store-only 任务经 framework.registerTask 直接入统一 store（验证「全部类型」合并）
        framework = new TaskFrameworkService(null);
        runner = new BackgroundTaskRunner(new NotificationQueue(), framework);
        // V2 任务清单走 TaskService 文件存储：@TempDir 隔离 configHome，避免写真实 ~/.claude
        service = new TaskService(tempDir);
        TaskController controller = new TaskController(runner, framework, service);
        // setControllerAdvice：GlobalExceptionHandler 将 NotFoundException → 404 / ConflictException → 409
        //（无 advice 则异常传播为 ServletException，WorkflowControllerTest:42-45 同款模式）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        // V2 端点写 RequestContext MDC（MemoryController/TeamController 同款）——测试线程复用，
        // 清理防 MDC sessionId 泄漏到其他用例（ThreadLocal 不自动清）
        RequestContext.clear();
    }

    /** 注册一个 running 的 local_agent 后台任务（sess 会话）· taskId = agentId.toString()（CC 合一）。 */
    private BackgroundTask registerAgent(String sessionId) {
        return runner.registerAsyncAgent(UUID.randomUUID(), "测试子代理 " + sessionId,
            "prompt " + sessionId, "general-purpose", null, sessionId);
    }

    /** 注册一个仅存统一 store 的 monitor_mcp 后台任务（不经 runner 本地 map，验证合并）。 */
    private void registerMonitorTask(String sessionId) {
        BackgroundTask monitor = new BackgroundTask(
            "m-monitor-" + sessionId, TaskType.MONITOR_MCP, BackgroundTaskStatus.RUNNING,
            "监控输出变化 " + sessionId, null,
            System.currentTimeMillis(), null, null,
            "", 0L, false,
            null, true, sessionId,
            null, null, null, null, null, null);
        framework.registerTask(monitor);
    }

    @Test
    @DisplayName("GET /api/v1/tasks?sessionId=sess-1 → 只含该会话任务（含 store-only monitor 合并）")
    void listTasks_filtersBySessionAndMergesStore() throws Exception {
        // WHY: 会话级清单——null-session 任务被排除；monitor 仅存 store，须与本地合并
        BackgroundTask s1 = registerAgent("sess-1");
        BackgroundTask s2 = registerAgent("sess-2");
        registerMonitorTask("sess-1");

        mockMvc.perform(get("/api/v1/tasks").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].id", hasItem(s1.id())))
            .andExpect(jsonPath("$[*].id", hasItem("m-monitor-sess-1")))
            .andExpect(jsonPath("$[*].id", not(hasItem(s2.id()))));
    }

    @Test
    @DisplayName("GET /api/v1/tasks（无 sessionId）→ 全部类型全量（含 store-only monitor）")
    void listTasks_noSessionReturnsAllTypes() throws Exception {
        // WHY: 前端「查看更多」弹窗不限制数量——必须返回全部类型；store-only monitor 在列
        registerAgent("sess-1");
        registerAgent("sess-2");
        registerMonitorTask("sess-1");

        mockMvc.perform(get("/api/v1/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[*].id", hasItem("m-monitor-sess-1")));
    }

    @Test
    @DisplayName("GET /api/v1/tasks?sessionId= 空串 → 全部（非空才算会话过滤）")
    void listTasks_blankSessionMeansAll() throws Exception {
        // WHY（D4）：前端恒传 activeSessionId ?? ''，空串 = 显式「全部」，不清空也不排除任何会话
        registerAgent("sess-1");
        registerAgent("sess-2");

        mockMvc.perform(get("/api/v1/tasks").param("sessionId", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{id}/kill → 200 {success:true} 且任务真实被 kill 到 KILLED")
    void kill_stopsRunningAgentTask() throws Exception {
        // WHY: kill 端点必须真实杀到（stopTask → killAsyncAgent），不是只回包——断言 runner.getTask 终态
        BackgroundTask task = registerAgent("sess-1");

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/kill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(runner.getTask(task.id()).orElseThrow().status())
            .as("kill 后任务必须为 KILLED")
            .isEqualTo(BackgroundTaskStatus.KILLED);
    }

    @Test
    @DisplayName("POST /api/v1/tasks/nope/kill → 404（无该任务）")
    void kill_missingTaskReturnsNotFound() throws Exception {
        // WHY: 前端 ApiError.userMessage() 展示 404；无该任务不能静默成功
        mockMvc.perform(post("/api/v1/tasks/nope/kill"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{id}/kill 对已终止任务 → 409（非 running，fail loud）")
    void kill_nonRunningReturnsConflict() throws Exception {
        // WHY: 重复 kill / 终态任务不能静默成功——stopTask NOT_RUNNING → 409（fail loud）
        BackgroundTask task = registerAgent("sess-1");
        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/kill"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/kill"))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /api/v1/tasks/stop-all?sessionId=sess-1 → 只停该会话 running（stopped:1），他会话不动")
    void stopAll_isSessionScoped() throws Exception {
        // WHY: 用户拍板「全部停止当前会话级」——只停匹配会话，sess-2 任务必须仍 RUNNING
        BackgroundTask s1 = registerAgent("sess-1");
        BackgroundTask s2 = registerAgent("sess-2");

        mockMvc.perform(post("/api/v1/tasks/stop-all").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.stopped").value(1));

        assertThat(runner.getTask(s1.id()).orElseThrow().status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(runner.getTask(s2.id()).orElseThrow().status())
            .as("非目标会话任务不得被停")
            .isEqualTo(BackgroundTaskStatus.RUNNING);
    }

    @Test
    @DisplayName("POST /api/v1/tasks/stop-all?sessionId= 空串 → 全部会话 running 全停（stopped:2）")
    void stopAll_blankSessionStopsAll() throws Exception {
        // WHY（D4）：空 sessionId = 显式「全部」——两会话 running 任务全停
        BackgroundTask s1 = registerAgent("sess-1");
        BackgroundTask s2 = registerAgent("sess-2");

        mockMvc.perform(post("/api/v1/tasks/stop-all").param("sessionId", ""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.stopped").value(2));

        assertThat(runner.getTask(s1.id()).orElseThrow().status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(runner.getTask(s2.id()).orElseThrow().status()).isEqualTo(BackgroundTaskStatus.KILLED);
    }

    @Test
    @DisplayName("清单 DTO 字段对齐前端契约（type/status CC 小写 + agentId UUID→串 + isBackgrounded 透出）")
    void listDto_fieldShapeMatchesFrontendContract() throws Exception {
        // WHY: 前端 BackgroundTaskDto 按 CC 值域消费（local_agent/running…）；agentId 串化；
        // AsyncTasksPanel.tsx:59 按 isBackgrounded !== false 过滤——DTO 必须透出该字段
        //（registerAsyncAgent 后台=true / registerAgentForeground 前台=false）
        UUID bgAgentId = UUID.randomUUID();
        BackgroundTask bg = runner.registerAsyncAgent(bgAgentId, "后台契约子代理", "prompt",
            "general-purpose", null, "sess-1");
        UUID fgAgentId = UUID.randomUUID();
        BackgroundTask fg = runner.registerAgentForeground(fgAgentId, "前台契约子代理", "prompt",
            "general-purpose", "sess-1");

        mockMvc.perform(get("/api/v1/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + bg.id() + "')].type").value(hasItem("local_agent")))
            .andExpect(jsonPath("$[?(@.id=='" + bg.id() + "')].status").value(hasItem("running")))
            .andExpect(jsonPath("$[?(@.id=='" + bg.id() + "')].agentId").value(hasItem(bgAgentId.toString())))
            .andExpect(jsonPath("$[?(@.id=='" + bg.id() + "')].isBackgrounded").value(hasItem(true)))
            .andExpect(jsonPath("$[?(@.id=='" + fg.id() + "')].isBackgrounded").value(hasItem(false)));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{id}/background → 200 {success:true} 且前台 agent 翻转 isBackgrounded=true")
    void backgroundTask_foregroundAgent_flipsToBackgrounded() throws Exception {
        // WHY: 对齐 CC Ctrl+B task:background——前台子代理就地转后台（backgroundAgentTask 翻转
        // isBackgrounded）；翻转后该任务才满足前端 AsyncTasksPanel 的 isBackgrounded !== false
        // 过滤，出现在异步面板。断言后端真实翻转（非只回包）+ GET 清单透出。
        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAgentForeground(agentId, "前台子代理", "prompt",
            "general-purpose", "sess-1");
        assertThat(task.isBackgrounded()).as("前置：registerAgentForeground 是前台任务").isFalse();

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/background"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(runner.getTask(task.id()).orElseThrow().isBackgrounded())
            .as("background 后任务必须 isBackgrounded=true（前端异步面板可见）")
            .isTrue();

        // GET 清单 DTO 透出 isBackgrounded=true（前端按此过滤展示真后台任务）
        mockMvc.perform(get("/api/v1/tasks").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + task.id() + "')].isBackgrounded").value(hasItem(true)));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{id}/background 对已后台化任务 → 400（fail loud）")
    void backgroundTask_alreadyBackgrounded_400() throws Exception {
        // WHY: 幂等契约——backgroundAgentTask 仅前台任务可后台化（已后台化 → false）；
        // 重复 background 不能静默成功，400 让前端 ApiError.userMessage() 展示
        BackgroundTask task = registerAgent("sess-1"); // registerAsyncAgent → isBackgrounded=true

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/background"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/tasks/nope/background → 400（无该任务）")
    void backgroundTask_notFound_400() throws Exception {
        // WHY: fail loud——无该任务不能静默成功；400（区别于 kill 的 404：background 失败契约
        // 统一 400 ValidationException，见 TaskController 类 Javadoc）
        mockMvc.perform(post("/api/v1/tasks/nope/background"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/tasks/{id}/background 对 monitor_mcp → 400（类型不支持）")
    void backgroundTask_unsupportedType_400() throws Exception {
        // WHY: CC Ctrl+B 只支持 local_bash（backgroundExistingForegroundTask）/local_agent
        // （backgroundAgentTask）；monitor_mcp 无前台转后台路径 → 400（fail loud）
        registerMonitorTask("sess-1");

        mockMvc.perform(post("/api/v1/tasks/m-monitor-sess-1/background"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/tasks/list?sessionId=sess-1 → V2 任务清单（TaskService.listTasks 文件存储，会话互斥）")
    void listV2Tasks_returnsSessionTaskList() throws Exception {
        // WHY: 用户拍板前端任务清单统一走 TaskService.listTasks——V2（TaskCreate）文件存储
        // {configHome}/tasks/{taskListId}；一个会话 V1/V2 互斥只会有一个。sessionId 写 MDC →
        // TaskService.getTaskListId() 解析该会话列表，他会话任务不得混入。
        String createdId = service.createTask("sess-1", Task.create("前端清单任务", "来自 TaskCreate"));
        service.createTask("sess-2", Task.create("他会话任务", "不应出现在 sess-1 清单"));

        mockMvc.perform(get("/api/v1/tasks/list").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.v2Tasks.length()").value(1))
            .andExpect(jsonPath("$.v2Tasks[0].id").value(createdId))
            .andExpect(jsonPath("$.v2Tasks[0].subject").value("前端清单任务"))
            .andExpect(jsonPath("$.v2Tasks[0].description").value("来自 TaskCreate"))
            .andExpect(jsonPath("$.v2Tasks[0].status").value("pending"))
            .andExpect(jsonPath("$.v1Todos.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/tasks/list 与异步后台任务清单不同源（只含 V2 TaskCreate 任务）")
    void listV2Tasks_isSeparateFromBackgroundTaskList() throws Exception {
        // WHY: 用户拍板「任务清单」统一走 TaskService.listTasks（V2），与异步后台任务清单
        // （GET /api/v1/tasks → BackgroundTask）不同源——V2 端点不得混入后台任务，反之亦然。
        BackgroundTask bg = registerAgent("sess-1");
        service.createTask("sess-1", Task.create("V2 任务", "TaskCreate 创建"));

        mockMvc.perform(get("/api/v1/tasks/list").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.v2Tasks.length()").value(1))
            .andExpect(jsonPath("$.v2Tasks[0].subject").value("V2 任务"))
            .andExpect(jsonPath("$.v2Tasks[*].id", not(hasItem(bg.id()))))
            .andExpect(jsonPath("$.v1Todos.length()").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/tasks/background-all → 前台 agent 后台化（已后台化 + monitor 跳过）")
    void backgroundAll_backgroundsForegroundAgentsOnly() throws Exception {
        // WHY: 对齐 CC Ctrl+B backgroundAll（LocalShellTask.tsx:390-410）——只后台化前台任务；
        //   已后台化（registerAsyncAgent isBackgrounded=true）与不支持类型（monitor）跳过。
        //   backgrounded 计数只算真正翻转的。
        UUID fgAgentId = UUID.randomUUID();
        BackgroundTask fg = runner.registerAgentForeground(fgAgentId, "前台子代理", "prompt",
            "general-purpose", "sess-1");
        BackgroundTask bg = registerAgent("sess-1");   // registerAsyncAgent → isBackgrounded=true
        registerMonitorTask("sess-1");                 // monitor_mcp 无转后台路径

        mockMvc.perform(post("/api/v1/tasks/background-all").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.backgrounded").value(1));

        assertThat(runner.getTask(fg.id()).orElseThrow().isBackgrounded())
            .as("background-all 后前台 agent 必须 isBackgrounded=true").isTrue();
        assertThat(runner.getTask(bg.id()).orElseThrow().isBackgrounded())
            .as("已后台化任务保持 true（不重复处理）").isTrue();
    }

    @Test
    @DisplayName("POST /api/v1/tasks/background-all?sessionId=sess-1 → 只后台化该会话前台任务")
    void backgroundAll_sessionFiltered() throws Exception {
        // WHY: 会话级批量（前端 Ctrl+B 只后台化当前会话前台任务，对齐 stop-all 会话过滤语义）；
        //   他会话前台任务不动。
        UUID a1 = UUID.randomUUID();
        runner.registerAgentForeground(a1, "sess1前台", "prompt", "general-purpose", "sess-1");
        UUID a2 = UUID.randomUUID();
        runner.registerAgentForeground(a2, "sess2前台", "prompt", "general-purpose", "sess-2");

        mockMvc.perform(post("/api/v1/tasks/background-all").param("sessionId", "sess-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backgrounded").value(1));

        assertThat(runner.getTask(a1.toString()).orElseThrow().isBackgrounded()).isTrue();
        assertThat(runner.getTask(a2.toString()).orElseThrow().isBackgrounded())
            .as("他会话前台任务不受影响").isFalse();
    }
}

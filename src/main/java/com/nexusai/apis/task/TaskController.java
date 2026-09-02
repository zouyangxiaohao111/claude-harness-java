package com.nexusai.apis.task;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner.StopTaskResult;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.task.dto.TaskDto;
import com.nexusai.model.task.dto.TaskItemDto;
import com.nexusai.model.task.dto.TaskListSnapshotDto;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 异步任务统一管理 REST · 用户拍板 2026-08-24「任务 tab 内嵌异步任务清单（默认 5 个）+ 查看更多
 * 弹窗全量；就近停止双入口；全部停止当前会话级；全部类型」。
 *
 * <h2>路由</h2>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/v1/tasks?sessionId=</td><td>异步任务清单（全部类型合并）；
 *       sessionId 非空 → 只留该会话任务；空/缺省 → 全部</td></tr>
 *   <tr><td>GET</td><td>/api/v1/tasks/list?sessionId=</td><td><b>V2 任务清单</b>（TaskCreate 任务，
 *       文件存储 {configHome}/tasks/{taskListId}）；sessionId 写 MDC → getTaskListId 解析列表 ID；
 *       与左侧异步后台任务清单不同源</td></tr>
 *   <tr><td>POST</td><td>/api/v1/tasks/{taskId}/kill</td><td>精准停止单个（按 type 分发）；
 *       404 无该任务 / 409 非 running（fail loud）</td></tr>
 *   <tr><td>POST</td><td>/api/v1/tasks/{taskId}/background</td><td>前台任务转后台（按 type 分发：
 *       local_bash → backgroundExistingForegroundTask 就地翻转 + StallWatchdog + 完成 watcher /
 *       local_agent → backgroundAgentTask 翻转 isBackgrounded）；失败统一 400（无该任务 /
 *       已后台化 / 类型不支持或进程已结束，fail loud）</td></tr>
 *   <tr><td>POST</td><td>/api/v1/tasks/background-all?sessionId=</td><td>前台任务全部转后台（对齐
 *       CC Ctrl+B backgroundAll：bash + agent 同时）；sessionId 非空 → 只该会话；空/缺省 → 全部</td></tr>
 *   <tr><td>POST</td><td>/api/v1/tasks/stop-all?sessionId=</td><td>会话级全部停止；
 *       sessionId 非空 → 只停该会话 running；空/缺省 → 全部</td></tr>
 * </table>
 *
 * <p><b>全部类型</b>：清单/stop-all 一律走 {@link BackgroundTaskRunner#listAllTasks()}
 * （本地 tasks ∪ TaskFrameworkService.store 合并去重）——monitor_mcp / in_process_teammate /
 * dream 仅存统一 store，裸 {@code listTasks()} 会漏类型（计划决策 D1）。单任务 kill 走
 * {@link BackgroundTaskRunner#stopTask}（stopTask 内部已按 type 分发 7 种 + 回退注册表，
 * bash→cancel / agent→killAsyncAgent / workflow→killWorkflowTask / monitor→stop /
 * teammate→registry.kill / dream/remote 回退，错误码 NOT_FOUND / NOT_RUNNING /
 * UNSUPPORTED_TYPE，对齐 CC stopTask.ts:38-100）。
 *
 * <p><b>会话隔离</b>：null-session 任务（main-thread spawn 无 MDC / 测试直构）不属于任何会话，
 * 会话级过滤时被排除（正确隔离）；空 sessionId = 显式「全部」（前端恒传 activeSessionId ?? ''）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final BackgroundTaskRunner backgroundTaskRunner;
    private final TaskFrameworkService taskFrameworkService;
    private final TaskService taskService;
    /** [task-v2-merge] V1 todo 读取：sessions.todos 列（DB-first，同 TodoStatusController）· 无 bean → 空桶。 */
    @Autowired(required = false)
    private SessionMapper sessionMapper;

    /** Spring 构造器注入（单构造器，无需 required=false）· 对齐 WorkflowController 注入风格。 */
    @Autowired
    public TaskController(BackgroundTaskRunner backgroundTaskRunner,
                          TaskFrameworkService taskFrameworkService,
                          TaskService taskService) {
        this.backgroundTaskRunner = backgroundTaskRunner;
        this.taskFrameworkService = taskFrameworkService;
        this.taskService = taskService;
    }

    /**
     * 异步任务清单 · GET /api/v1/tasks?sessionId=
     *
     * <p>sessionId 非空 → 只留 {@code sessionId.equals(task.sessionId())}（null-session 任务被排除）；
     * sessionId 空/缺省 → 全部类型全量（前端「查看更多」弹窗）。
     *
     * @param sessionId 会话 id（可选）
     * @return TaskDto 列表（type/status 均 CC 小写值域）
     */
    @GetMapping
    public List<TaskDto> listTasks(@RequestParam(required = false) String sessionId) {
        List<BackgroundTask> all = backgroundTaskRunner.listAllTasks();
        List<TaskDto> result = new ArrayList<>();
        for (BackgroundTask task : all) {
            if (sessionId != null && !sessionId.isBlank()) {
                // 会话级清单：null-session 任务不属于任何会话，被排除 = 正确会话隔离
                if (task.sessionId() == null || !sessionId.equals(task.sessionId())) {
                    continue;
                }
            }
            result.add(TaskDto.from(task));
        }
        if (log.isDebugEnabled()) {
            log.debug("[TaskController] GET /api/v1/tasks sessionId={} → {} 个任务（全量 {} 个）",
                sessionId, result.size(), all.size());
        }
        return result;
    }

    /**
     * V2 任务清单 · GET /api/v1/tasks/list?sessionId=
     *
     * <p>用户拍板（2026-08-24）：TaskService.listTasks 共用——一个会话 V1（TodoWrite）/V2
     * （TaskCreate）互斥，只会有一个；前端任务清单统一走本端点（V2 文件存储
     * {@code {configHome}/tasks/{taskListId}}）。与 {@link #listTasks}（异步后台任务清单）
     * <b>不同源</b>——本端点返回 TaskCreate 创建的任务（TaskService.listTasks → List&lt;Task&gt;），
     * 映射为 {@link TaskItemDto}（CC TaskSchema tasks.ts:76-88 全量投影）。
     *
     * <p>会话机制：解析 sessionId（query ?sessionId= → MDC 兜底），非 null 先
     * {@code RequestContext.setSession(sessionId)} 写 MDC（MemoryController:121-139 /
     * TeamController:91-98 同款）→ {@link TaskService#getTaskListId()} 按 CC 优先级链
     * （tasks.ts:199-210）解析 taskListId：env CLAUDE_CODE_TASK_LIST_ID / in-process teammate
     * teamName / team.name / 会话级 leaderTeamName / 当前会话 MDC。任务读取无锁（CC listTasks
     * 无锁 readdir，tasks.ts:443-456）。
     *
     * @param sessionId query {@code ?sessionId=}（可选；任务清单会话锚定）
     * @return TaskItemDto 列表（status 已 CC 小写值域）
     */
    @GetMapping("/list")
    public TaskListSnapshotDto listTasksMerged(@RequestParam(value = "sessionId", required = false) String sessionId) {
        // 会话解析（MemoryController:136-140 / TeamController:91-98 同款）：query ?sessionId= → MDC 兜底
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : RequestContext.sessionId();
        if (sid != null) {
            RequestContext.setSession(sid);
        }
        String taskListId = TaskService.getTaskListId();
        // [task-v2-merge] V1 V2 都查合并（用户拍板 2026-08-25：会话 V1（TodoWrite）/V2（TaskCreate）
        //   互斥只会有一个；端点两者都查返回，前端按非空方显示）：
        //   V2 = TaskService 文件（{configHome}/tasks/{taskListId}），V1 = sessions.todos 列（DB-first）。
        List<TaskItemDto> v2Tasks = new ArrayList<>();
        for (Task task : taskService.listTasks(taskListId)) {
            v2Tasks.add(TaskItemDto.from(task));
        }
        List<TodoWriteTool.TodoItem> v1Todos = new ArrayList<>();
        Map<String, List<TodoWriteTool.TodoItem>> v1Buckets = readV1Todos(sid);
        if (sid != null) {
            v1Todos.addAll(v1Buckets.getOrDefault(sid, List.of()));
        }
        long updatedAt = System.currentTimeMillis();
        // [access-log 去噪] 与同 controller GET /api/v1/tasks 一致：前端 2s 轮询端点降 debug（联调 INFO 不刷屏）
        if (log.isDebugEnabled()) {
            log.debug("[TaskController] GET /api/v1/tasks/list sessionId={} taskListId={} → v2Tasks={} v1Todos={}（V1/V2 互斥合并）",
                sid, taskListId, v2Tasks.size(), v1Todos.size());
        }
        return new TaskListSnapshotDto(taskListId, v2Tasks, v1Todos, updatedAt);
    }

    /** [task-v2-merge] V1 todo 桶读取 · sessions.todos 列 DB-first（同 TodoStatusController.readBuckets 语义）·
     *  SessionMapper 未注入 / 会话不存在 / 解析失败 → 空桶（fail-soft，不 500）。 */
    private Map<String, List<TodoWriteTool.TodoItem>> readV1Todos(String sessionId) {
        if (sessionMapper == null || sessionId == null || sessionId.isBlank()) {
            return Map.of();
        }
        try {
            SessionRecord s = sessionMapper.selectOneById(sessionId);
            if (s != null) {
                Map<String, List<TodoWriteTool.TodoItem>> parsed = TodoWriteTool.todosJsonToMap(s.getTodos());
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.warn("TaskController V1 todos 读取降级（空桶）: sessionId={} err={}", sessionId, e.getMessage());
        }
        return Map.of();
    }

    /**
     * 精准停止单个任务 · POST /api/v1/tasks/{taskId}/kill
     *
     * <p>委托 {@link BackgroundTaskRunner#stopTask} 全类型分发。失败契约（fail loud）：
     * NOT_FOUND → 404 NotFoundException；NOT_RUNNING / UNSUPPORTED_TYPE → 409 ConflictException
     * （GlobalExceptionHandler → RFC 7807）。
     *
     * @param taskId 目标任务 id（bash=TaskIdGenerator 前缀 b；agent=agentId；workflow=runId 前缀 w 等）
     * @return {@code {success: true}}
     */
    @PostMapping("/{taskId}/kill")
    public Map<String, Object> killTask(@PathVariable String taskId) {
        StopTaskResult r = backgroundTaskRunner.stopTask(taskId);
        if (r.ok()) {
            log.info("[TaskController] POST /api/v1/tasks/{}/kill → 停止成功 type={}", taskId, r.taskType());
            return Map.of("success", true);
        }
        switch (r.errorCode()) {
            case NOT_FOUND -> {
                log.warn("[TaskController] POST /api/v1/tasks/{}/kill → 任务不存在（404）", taskId);
                throw new NotFoundException("Task not found: " + taskId);
            }
            case NOT_RUNNING -> {
                log.warn("[TaskController] POST /api/v1/tasks/{}/kill → 任务非运行态（409）", taskId);
                throw new ConflictException("Task not running: " + taskId);
            }
            case UNSUPPORTED_TYPE -> {
                log.warn("[TaskController] POST /api/v1/tasks/{}/kill → 类型不支持停止（409）", taskId);
                throw new ConflictException("Task type unsupported for stop: " + taskId);
            }
        }
        throw new ConflictException("Task stop failed: " + taskId);
    }

    /**
     * 前台任务转后台 · POST /api/v1/tasks/{taskId}/background
     *
     * <p>对齐 CC {@code task:background}（LocalShellTask.tsx:420-474 backgroundExistingForegroundTask /
     * LocalAgentTask.tsx:620-652 backgroundAgentTask），按 {@code task.type()} 分发：
     * local_bash → {@link BackgroundTaskRunner#backgroundExistingForegroundTask}（就地翻转 +
     * StallWatchdog + 完成 watcher）；local_agent → {@link BackgroundTaskRunner#backgroundAgentTask}
     * （翻转 isBackgrounded，子代理本就异步后台跑）。不重复 registerTask（无重复 task_started）。
     *
     * <p>失败契约（fail loud）：任务不存在 / 已后台化 / 类型不支持 / 进程已结束 → 400
     * ValidationException（GlobalExceptionHandler → RFC 7807，对齐 stopTask fail loud 风格）。
     *
     * @param taskId 目标任务 id（bash=TaskIdGenerator 前缀 b；agent=agentId 等）
     * @return {@code {success: true}}
     */
    @PostMapping("/{taskId}/background")
    public Map<String, Object> backgroundTask(@PathVariable String taskId) {
        // 查任务定位类型（listAllTasks = 本地 tasks ∪ store 合并，同 listTasks/stopAll 数据源）
        BackgroundTask task = backgroundTaskRunner.listAllTasks().stream()
            .filter(t -> taskId.equals(t.id()))
            .findFirst().orElse(null);
        if (task == null) {
            log.warn("[TaskController] POST /api/v1/tasks/{}/background → 任务不存在（400）", taskId);
            throw new ValidationException("Task not found: " + taskId);
        }
        if (task.isBackgrounded()) {
            log.warn("[TaskController] POST /api/v1/tasks/{}/background → 任务已后台化（400）", taskId);
            throw new ValidationException("Task already backgrounded: " + taskId);
        }
        boolean ok;
        switch (task.type()) {
            case LOCAL_BASH -> ok = backgroundTaskRunner.backgroundExistingForegroundTask(taskId);
            case LOCAL_AGENT -> ok = backgroundTaskRunner.backgroundAgentTask(taskId);
            default -> {
                log.warn("[TaskController] POST /api/v1/tasks/{}/background → 类型不支持（400） type={}",
                    taskId, task.type());
                throw new ValidationException("Task type unsupported for background: "
                    + task.type().getTypeString() + " (" + taskId + ")");
            }
        }
        if (!ok) {
            // bash：进程已结束/无 runner（前置已排除不存在/已后台化/类型）
            log.warn("[TaskController] POST /api/v1/tasks/{}/background → 后台化失败（400）", taskId);
            throw new ValidationException("Task cannot be backgrounded: " + taskId);
        }
        log.info("[TaskController] POST /api/v1/tasks/{}/background → 后台化成功 type={}", taskId, task.type());
        return Map.of("success", true);
    }

    /**
     * 会话级全部停止 · POST /api/v1/tasks/stop-all?sessionId=
     *
     * <p>遍历 {@link BackgroundTaskRunner#listAllTasks()}：sessionId 非空 → 仅
     * {@code sessionId.equals(task.sessionId())}（null-session 任务被排除）；sessionId 空/缺省 →
     * 全部。仅 status==RUNNING 才逐个 {@code stopTask}；失败 log.warn 不中断。用户拍板：全部停止
     * 当前会话级，由前端传入 activeSessionId 界定。
     *
     * @param sessionId 会话 id（可选）
     * @return {@code {success: true, stopped: N}}
     */
    @PostMapping("/stop-all")
    public Map<String, Object> stopAll(@RequestParam(required = false) String sessionId) {
        List<BackgroundTask> all = backgroundTaskRunner.listAllTasks();
        int stopped = 0;
        for (BackgroundTask task : all) {
            if (sessionId != null && !sessionId.isBlank()) {
                if (task.sessionId() == null || !sessionId.equals(task.sessionId())) {
                    continue;
                }
            }
            if (task.status() != BackgroundTaskStatus.RUNNING) {
                continue;
            }
            StopTaskResult r = backgroundTaskRunner.stopTask(task.id());
            if (r.ok()) {
                stopped++;
            } else {
                log.warn("[TaskController] stop-all: task {} 停止失败 errorCode={}", task.id(), r.errorCode());
            }
        }
        log.info("[TaskController] POST /api/v1/tasks/stop-all sessionId={} → 已停 {} 个（扫描 {} 个）",
            sessionId, stopped, all.size());
        return Map.of("success", true, "stopped", stopped);
    }

    /**
     * 前台任务全部转后台 · POST /api/v1/tasks/background-all?sessionId=
     *
     * <p>对齐 CC Ctrl+B {@code task:background} → backgroundAll（LocalShellTask.tsx:390-410）：
     * <b>同时后台化前台 bash（:394-400 backgroundTask）与前台 agent（:402-409
     * backgroundAgentTask）</b>。Java 端 {@code BackgroundTaskRunner.backgroundAll()} 仅代理
     * local_agent（RF-2 不完整，缺 bash 分支），本端点按 stop-all 模式遍历 listAllTasks 自行
     * 分发补齐 bash——对齐 CC 完整语义。
     *
     * <p>遍历 {@link BackgroundTaskRunner#listAllTasks()}：sessionId 非空 → 仅
     * {@code sessionId.equals(task.sessionId())}（null-session 任务被排除）；空/缺省 → 全部。
     * 仅 {@code !isBackgrounded}（前台）任务参与：local_bash →
     * {@link BackgroundTaskRunner#backgroundExistingForegroundTask}（就地翻转 + StallWatchdog +
     * 完成 watcher）、local_agent → {@link BackgroundTaskRunner#backgroundAgentTask}（翻转
     * isBackgrounded）；其他类型（monitor/teammate/workflow/dream）无转后台路径 → 跳过（批量
     * 语义不报错）。失败 log.warn 不中断（对齐 stop-all）。
     *
     * @param sessionId 会话 id（可选）
     * @return {@code {success: true, backgrounded: N}}
     */
    @PostMapping("/background-all")
    public Map<String, Object> backgroundAll(@RequestParam(required = false) String sessionId) {
        List<BackgroundTask> all = backgroundTaskRunner.listAllTasks();
        int backgrounded = 0;
        for (BackgroundTask task : all) {
            if (sessionId != null && !sessionId.isBlank()) {
                if (task.sessionId() == null || !sessionId.equals(task.sessionId())) {
                    continue;
                }
            }
            if (task.isBackgrounded()) {
                continue;
            }
            boolean ok;
            switch (task.type()) {
                case LOCAL_BASH -> ok = backgroundTaskRunner.backgroundExistingForegroundTask(task.id());
                case LOCAL_AGENT -> ok = backgroundTaskRunner.backgroundAgentTask(task.id());
                default -> {
                    continue;   // monitor/teammate/workflow/dream 无转后台路径 → 跳过
                }
            }
            if (ok) {
                backgrounded++;
            } else {
                log.warn("[TaskController] background-all: task {} 后台化失败（进程已结束）", task.id());
            }
        }
        log.info("[TaskController] POST /api/v1/tasks/background-all sessionId={} → 已后台化 {} 个（扫描 {} 个）",
            sessionId, backgrounded, all.size());
        return Map.of("success", true, "backgrounded", backgrounded);
    }
}

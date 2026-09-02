package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 任务服务层 · 对齐 CC tasks.ts 业务编排
 *
 * <p>s12.5-1.1 (L3 拆分): 业务方法保持不变，IO 委托给 {@link TaskFileStorage}，
 * ID 管理委托给 {@link HighWatermark}。
 *
 * <h2>文件结构（对齐 CC tasks.ts:221-227 getTasksDir）</h2>
 * <pre>
 * {configHome}/
 * └── tasks/
 *     └── {taskListId}/
 *         ├── .highwatermark    # 最大任务 ID（CC tasks.ts:92）
 *         ├── .lock             # 锁文件（CC tasks.ts:504-523）
 *         ├── 1.json            # 任务 1
 *         ├── 2.json            # 任务 2
 *         └── ...
 * </pre>
 * <p>configHome = CLAUDE_CONFIG_DIR 环境变量，否则 ~/.claude（对齐 CC envUtils.ts:7-14
 * getClaudeConfigHomeDir；Java 侧可经 nexusai.task.config-dir sysprop 覆盖），
 * 任务存储根 = {configHome}/tasks/{taskListId}，无 .claude 中间层。
 *
 * <h2>并发安全（按操作分锁，对齐 CC tasks.ts 真实锁粒度）</h2>
 * <p>不再统一列表目录级锁（脏代码），改为 CC 的分操作锁粒度：
 * <ul>
 *   <li><b>列表级锁</b>（tasksDir/.lock）：{@link #createTask(String, Task)}
 *       （CC tasks.ts:293）、{@link #resetTaskList}（CC tasks.ts:154）与
 *       {@link #claimTask(String, String, String, boolean)} checkAgentBusy=true
 *       变体（CC tasks.ts:558 + :618-628 lockfile.lock(lockPath)）。</li>
 *   <li><b>文件级锁</b>（{taskId}.json.lock）：仅 {@link #updateTask(String, String, Map)}
 *       （CC tasks.ts:386）与 {@link #claimTask(String, String, String, boolean)}
 *       checkAgentBusy=false 变体（CC tasks.ts:566）。</li>
 *   <li><b>无锁</b>：{@link #getTask}（CC tasks.ts:314-316 raw read）、
 *       {@link #listTasks}（CC tasks.ts:443-456 readdir）、{@link #deleteTask}
 *       （CC tasks.ts:393-441，级联走 updateTask 文件级锁）、{@link #blockTask}
 *       （CC tasks.ts:458-486，两次 updateTask 文件级锁）。</li>
 * </ul>
 * <p>同 JVM 并发 tryLock 抛 {@link java.nio.channels.OverlappingFileLockException}，
 * {@link TaskLock} 已统一捕获并退避重试（TaskLock.withRetryLoop）。
 *
 * @see TaskFileStorage
 * @see HighWatermark
 * @see TaskLock
 * @see Task
 * @see TaskSystemConfig
 */
@Component // s12-3.2: Spring bean 用于 Task 工具构造器注入
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    // ════════════════════════════════════════════════════════════════════════
    // Signal 实现 · 对齐 CC tasks.ts:18 createSignal() + tasks.ts:53 subscribe
    // CC 使用 Bun 的 Signal，Java 用 CopyOnWriteArrayList 替代
    // ════════════════════════════════════════════════════════════════════════

    /** 任务更新监听器列表 · 对齐 CC tasks.ts:18 tasksUpdated signal */
    private static final List<Runnable> tasksUpdatedListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册任务更新监听器 · 对齐 CC tasks.ts:53 onTasksUpdated = tasksUpdated.subscribe
     */
    public static Runnable addListener(Runnable listener) {
        tasksUpdatedListeners.add(listener);
        return () -> tasksUpdatedListeners.remove(listener);
    }

    /**
     * 通知所有监听器任务已更新 · 对齐 CC tasks.ts:61-67 notifyTasksUpdated()
     */
    public static void notifyTasksUpdated() {
        for (Runnable listener : tasksUpdatedListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("任务更新监听器执行失败: {}", e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Leader team name · 对齐 CC tasks.ts:25 leaderTeamName + set/clear
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [team-cc-align fixPlan2] 会话级 leader team name · 对齐 CC tasks.ts:25 进程级 {@code let
     * leaderTeamName}（CC 单会话）→ Java 多会话按 sessionId 分桶（multi-session-vs-cc-single-session
     * 铁律；键 = RequestContext MDC 会话 short，对齐 session-id-short 统一 sess-xxx）。
     * 原 ThreadLocal 跨线程丢失 → leader 建 team（线程 A set）后 TaskCreate（线程 B）读不到 →
     * getTaskListId 兜底成 sessionId/UUID，任务落错目录。
     */
    private static final Map<String, String> leaderTeamNames = new ConcurrentHashMap<>();

    /**
     * 设置当前会话的 leader team name · 对齐 CC tasks.ts:31-37 setLeaderTeamName()
     * 无当前会话上下文 → warn 跳过（Java 需会话键防跨会话污染，CC 进程级无需）。
     */
    public static void setLeaderTeamName(String teamName) {
        if (teamName == null || teamName.isBlank()) return;
        String sessionId = RequestContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[TaskService] setLeaderTeamName: 无当前会话上下文, 跳过 team={}（Java 需会话键，对齐 multi-session）", teamName);
            return;
        }
        String old = leaderTeamNames.get(sessionId);
        if (teamName.equals(old)) return;
        leaderTeamNames.put(sessionId, teamName);
        notifyTasksUpdated();
    }

    /**
     * 清除当前会话的 leader team name · 对齐 CC tasks.ts:43-47 clearLeaderTeamName()
     */
    public static void clearLeaderTeamName() {
        String sessionId = RequestContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) return;
        if (leaderTeamNames.remove(sessionId) != null) {
            notifyTasksUpdated();
        }
    }

    /** 当前会话的 leader team name · getTaskListId 优先级 4 读取（per-session，防跨会话污染）。 */
    private static String leaderTeamNameForCurrentSession() {
        String sessionId = RequestContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return leaderTeamNames.get(sessionId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // TaskListId 解析 · 对齐 CC tasks.ts:199-210 getTaskListId()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前任务列表 ID · 对齐 CC tasks.ts:199-210 getTaskListId()
     *
     * <p>CC 真源（grep 实证 tasks.ts:199-210，不信注释）：
     * <pre>
     * export function getTaskListId(): string {
     *   if (process.env.CLAUDE_CODE_TASK_LIST_ID) {
     *     return process.env.CLAUDE_CODE_TASK_LIST_ID            // 优先级 1
     *   }
     *   const teammateCtx = getTeammateContext()
     *   if (teammateCtx) {
     *     return teammateCtx.teamName                            // 优先级 2
     *   }
     *   return getTeamName() || leaderTeamName || getSessionId() // 优先级 3
     * }
     * </pre>
     *
     * <p>Java 映射（接口用 Spring 属性，行为对齐 CC 优先级）：
     * <ol>
     *   <li>env <b>CLAUDE_CODE_TASK_LIST_ID</b>（CC tasks.ts:200 优先级 1）→ sysprop
     *       <b>nexusai.taskListId</b>（Spring 接口承载 CC env 语义，见
     *       {@link #resolveTaskListIdFromEnvOrProperty()}）</li>
     *   <li><b>in-process teammate teamName</b>（CC tasks.ts:205-208 优先级 2 +
     *       teammateContext.ts:47-49 getTeammateContext()，经
     *       {@link TeammateContext#getTeammateContext()} 取 ThreadLocal；本分支已<b>live</b>——
     *       {@link SpawnInProcess}（SpawnInProcess.java:285）将 runner 线程全程包在
     *       {@code runWithTeammateContext(teammateContext, () -> loop.runTeammateLoop(prompt))}
     *       中（对齐 CC inProcessRunner.ts:1160），teammate 的 {@code getTaskListId()} 直接返回
     *       {@code teammateCtx.teamName}，与 leader 共享同一任务列表，见下方「何时生效」落地说明）</li>
     *   <li><b>nexusai.team.name</b>（CC 优先级 3 的 getTeamName() Java 近似，对齐
     *       teammate.ts:111-119 实际行为；修复原遗留错误 teamName key——该 key 全仓库
     *       无任何写入点，grep 实证属脏 key）</li>
     *   <li>ThreadLocal {@link #leaderTeamNameHolder}（对齐 CC tasks.ts:25/209 leaderTeamName）</li>
     *   <li>sysprop <b>nexusai.sessionId</b>（CC tasks.ts:209 getSessionId() 的部署注入会话 ID）</li>
     *   <li>RequestContext MDC <b>sessionId</b>（CC tasks.ts:209 getSessionId() 的当前会话，
     *       ChatService:154 请求入口注入）</li>
     *   <li>进程级稳定会话 UUID（对齐 CC STATE.sessionId = randomUUID()，state.ts:331；
     *       无任何会话上下文时的兜底）</li>
     * </ol>
     *
     * <p><b>优先级 2（in-process teammate）何时生效</b>：
     * <ul>
     *   <li>CC 侧（tasks.ts:203-208）：in-process teammate 运行在 leader 同进程，
     *       AsyncLocalStorage（teammateContext.ts:41-49）存 leader 上下文，故
     *       {@code getTaskListId()} 直接返回 {@code teammateCtx.teamName}，让 teammate
     *      与 leader 共享同一任务列表。</li>
     *   <li>Java 侧：{@link TeammateContext}（ThreadLocal-backed，commit f5903dca）已接线——
     *       {@link SpawnInProcess}（SpawnInProcess.java:285）把 runner 线程包在
     *       {@code TeammateContext.runWithTeammateContext(teammateContext, () -> loop.runTeammateLoop(prompt))}
     *       中（对齐 CC inProcessRunner.ts:1160），runner 线程全程持 teammate 上下文；
     *       工具执行线程的上下文由 StreamingToolExecutor.executeAsync 捕获传播
     *       （ThreadLocal 不跨线程，Java 手动桥接 AsyncLocalStorage 自动传播语义）。
     *       因此 teammate 的 {@code getTaskListId()} 优先级 2 <b>live</b>，与 leader 共享任务列表。</li>
     * </ul>
     *
     * <p>最终回退 <b>会话 ID（进程级稳定 UUID）</b>：CC getTaskListId() 本身从不返回
     * 'tasklist'（最后回退 getSessionId()，会话 UUID，state.ts:331 randomUUID），
     * 'tasklist' 是 CC 真实常量 {@code DEFAULT_TASKS_MODE_TASK_LIST_ID}（tasks.ts:862，
     * watcher/main.tsx UI 用，getTaskListId 永不返回它）。Java 静态方法无 state/ToolUseContext
     * 直接访问，以 {@link RequestContext#sessionId()}（MDC 当前会话）+ 进程级稳定 UUID 兜底
     * （U-3 对齐 CC：弃硬编码 'tasklist'）。
     *
     * <p>WHY 单一解析点：消除工具构造器 @Value 注入的第二个 taskListId key，同一应用内所有
     * 任务读写（工具 + 提醒 + hook + watcher）经本方法解析同一列表 ID（对齐 CC 工具
     * call() 内逐次 getTaskListId()）。
     *
     * @return 任务列表 ID
     */
    public static String getTaskListId() {
        // 优先级 1（CC tasks.ts:200-204）：env CLAUDE_CODE_TASK_LIST_ID → sysprop nexusai.taskListId
        String explicit = resolveTaskListIdFromEnvOrProperty();
        if (explicit != null) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级1（env CLAUDE_CODE_TASK_LIST_ID / sysprop nexusai.taskListId）解析列表 ID {}", explicit);
            }
            return explicit;
        }

        // 优先级 2（CC tasks.ts:205-208 + teammateContext.ts:47-49）：in-process teammate teamName
        // 接线说明：SpawnInProcess.java:285 已把 runner 线程包在
        //   runWithTeammateContext(teammateContext, () -> loop.runTeammateLoop(prompt)) 中
        //   （对齐 CC inProcessRunner.ts:1160），teammate 的 getTaskListId() 优先级 2 live，
        //   与 leader 共享任务列表（详见方法 Javadoc「何时生效」）。
        TeammateContext teammateCtx = TeammateContext.getTeammateContext();
        if (teammateCtx != null && teammateCtx.getData().teamName() != null
                && !teammateCtx.getData().teamName().isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级2（in-process teammate teamName）解析列表 ID {}", teammateCtx.getData().teamName());
            }
            return teammateCtx.getData().teamName();
        }

        // 优先级 3（CC teammate.ts:111-119 getTeamName() Java 近似）：sysprop nexusai.team.name
        String teamName = TaskSystemConfig.getTeamName();
        if (teamName != null && !teamName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级3（teamName nexusai.team.name）解析列表 ID {}", teamName);
            }
            return teamName;
        }

        // 优先级 4（CC tasks.ts:25/209 leaderTeamName）：会话级 leader team name
        //   [team-cc-align fixPlan2] 原 ThreadLocal 跨线程丢失 → 按当前会话 short 分桶读取。
        String leaderName = leaderTeamNameForCurrentSession();
        if (leaderName != null && !leaderName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级4（leaderTeamName 会话级）解析列表 ID {}", leaderName);
            }
            return leaderName;
        }

        // 优先级 5（CC tasks.ts:209 getSessionId()）：sysprop nexusai.sessionId（部署注入会话 ID）
        String sessionId = System.getProperty("nexusai.sessionId");
        if (sessionId != null && !sessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级5（sessionId nexusai.sessionId）解析列表 ID {}", sessionId);
            }
            return sessionId;
        }

        // 优先级 6（CC tasks.ts:209 getSessionId() 的当前会话）：RequestContext MDC 会话 ID。
        // CC getSessionId() 返回 STATE.sessionId（当前请求会话 UUID，state.ts:431-432）；Java 静态方法
        // 无法访问 state/ToolUseContext，以 RequestContext MDC 会话 ID（ChatService:154 入口注入）为
        // getSessionId() 等价物——请求流中 getTaskListId() 调用均处于会话线程，MDC 已注入。
        String requestSessionId = RequestContext.sessionId();
        if (requestSessionId != null && !requestSessionId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("getTaskListId: 优先级6（当前会话 RequestContext MDC）解析列表 ID {}", requestSessionId);
            }
            return requestSessionId;
        }

        // 最终回退：进程级稳定会话 UUID（对齐 CC STATE.sessionId = randomUUID()，state.ts:331）。
        // CC getSessionId() 永不返回 null（STATE.sessionId 恒为 UUID）；Java 静态方法在无任何会话
        // 上下文（非请求线程/测试）时以进程级稳定 UUID 兜底——保证非 null（getTasksDir 依赖
        // sanitizePathComponent(taskListId) 非 null，DC-4 后 null 会 NPE），且永不返回 'tasklist'
        // （CC DEFAULT_TASKS_MODE_TASK_LIST_ID tasks.ts:862 仅供 watcher/main.tsx UI 使用，
        // getTaskListId 本身永不返回它，对齐 CC tasks.ts:199-210）。
        if (log.isDebugEnabled()) {
            log.debug("getTaskListId: 无任何配置与会话上下文，回退进程级会话 UUID {}", PROCESS_SESSION_ID);
        }
        return PROCESS_SESSION_ID;
    }

    /** 进程级稳定会话 UUID · 对齐 CC STATE.sessionId = randomUUID()（state.ts:331），懒初始化进程内稳定 */
    private static final String PROCESS_SESSION_ID = java.util.UUID.randomUUID().toString();

    /**
     * 解析显式 task list ID · 对齐 CC tasks.ts:200-204 getTaskListId() 优先级 1
     *
     * <p>env <b>CLAUDE_CODE_TASK_LIST_ID</b> 优先（CC 真源 tasks.ts:200），sysprop
     * <b>nexusai.taskListId</b> 为 Spring 接口承载（Java 部署经 -Dnexusai.taskListId 注入）。
     * 单一 key 来源：全仓库仅本方法读取 nexusai.taskListId（grep 复验点）。
     *
     * @return 显式列表 ID；两者均未配置返回 null
     */
    private static String resolveTaskListIdFromEnvOrProperty() {
        String env = System.getenv("CLAUDE_CODE_TASK_LIST_ID");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getProperty("nexusai.taskListId");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 实例字段 + 构造器
    // ════════════════════════════════════════════════════════════════════════

    private final TaskFileStorage fileStorage;
    private final HighWatermark highWatermark;

    /** Spring no-arg 构造器（默认 config home）· s12-3.2 */
    public TaskService() {
        this(TaskSystemConfig.getClaudeConfigHomeDir());
    }

    /** 测试构造器（显式 config home）· config-home 注入缝 */
    public TaskService(Path configHomeDir) {
        TaskFileStorage fs = new TaskFileStorage(configHomeDir);
        this.fileStorage = fs;
        this.highWatermark = new HighWatermark(fs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 路径工具方法（委托给 fileStorage）
    // ════════════════════════════════════════════════════════════════════════

    public Path getTasksDir(String taskListId) {
        return fileStorage.getTasksDir(taskListId);
    }

    public Path getTaskPath(String taskListId, String taskId) {
        return fileStorage.getTaskPath(taskListId, taskId);
    }

    public Path getHighWaterMarkPath(String taskListId) {
        return fileStorage.getHighWaterMarkPath(taskListId);
    }

    public void ensureTasksDir(String taskListId) throws IOException {
        fileStorage.ensureTasksDir(taskListId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // High water mark · 委托给 HighWatermark
    // ════════════════════════════════════════════════════════════════════════

    public long readHighWaterMark(String taskListId) {
        return highWatermark.readHighWaterMark(taskListId);
    }

    public void writeHighWaterMark(String taskListId, long value) throws IOException {
        highWatermark.writeHighWaterMark(taskListId, value);
    }

    public long findHighestTaskIdFromFiles(String taskListId) {
        return highWatermark.findHighestTaskIdFromFiles(taskListId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CRUD 操作 · 对齐 CC tasks.ts:284-456
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 创建任务（带锁）· 对齐 CC tasks.ts:283-308 createTask()
     *
     * <p>对齐 CC：createTask 只算 {@code id = max(findHighestTaskIdFromFiles, readHighWaterMark) + 1}
     * 后写任务文件，<b>不写 HWM</b>（tasks.ts:283-308 实测无 writeHighWaterMark，grep 实证）。
     * HWM 仅由 {@link #deleteTask}（tasks.ts:405）与 {@link #resetTaskList}（tasks.ts:161）
     * 提升——任务文件本身是最高 ID 的权威，HWM 仅防止删文件后 ID 复用。
     */
    public String createTask(String taskListId, Task task) {
        Path tasksDir = fileStorage.getTasksDir(taskListId);
        String result = TaskLock.withLockAndReturn(tasksDir, () -> {
            try {
                fileStorage.ensureTasksDir(taskListId);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create tasks directory: " + e.getMessage(), e);
            }

            long highWaterMark = highWatermark.readHighWaterMark(taskListId);
            long highestFromFile = highWatermark.findHighestTaskIdFromFiles(taskListId);
            long newId = Math.max(highWaterMark, highestFromFile) + 1;

            Task withId = new Task(String.valueOf(newId), task.subject(), task.description(),
                task.activeForm(), task.owner(), task.status(),
                task.blocks(), task.blockedBy(), task.metadata());
            fileStorage.writeTaskFile(taskListId, String.valueOf(newId), withId);

            // 数据流日志（对齐 CC createTask 写文件后 notifyTasksUpdated + return id，tasks.ts:299-308）
            log.info("createTask: 已在列表 {} 创建任务 #{} (subject={})", taskListId, newId, withId.subject());

            return String.valueOf(newId);
        });

        notifyTasksUpdated();
        return result;
    }

    /**
     * 读取任务（无锁）· 对齐 CC tasks.ts:310-349 getTask()
     *
     * <p>CC getTask 是 raw readFile（tasks.ts:314-316），不加任何锁。
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已删除。
     *
     * <p>ant 旧状态迁移（对齐 CC tasks.ts:317-331）：读取原文后，当 USER_TYPE==='ant'
     * 时将遗留状态 open→pending / resolved→completed /
     * planning|implementing|reviewing|verifying→in_progress（内存迁移，不落盘），
     * 随后才做 TaskSchema 等价校验（tasks.ts:333-339）。非 ant 用户不迁移，遗留状态
     * 读失败 → Optional.empty（等价 CC zod 校验失败 → null）。
     *
     * <p>实现落在 storage 统一读入口 {@link TaskFileStorage#readTaskFileMigrated(String, String, boolean)}
     * （getTask 与 listTaskFiles 共用，FIX-G4 消除读路径分叉），本方法仅委托并按 CC
     * tasks.ts:320 判断 USER_TYPE 是否为 'ant'。
     */
    public Optional<Task> getTask(String taskListId, String taskId) {
        if (log.isDebugEnabled()) {
            log.debug("getTask: 无锁读取任务 {} in list {}", taskId, taskListId);
        }
        return fileStorage.readTaskFileMigrated(taskListId, taskId, isAntUser());
    }

    /**
     * 环境变量读取器 · 测试注入缝（对齐 ErrorClassifier.java:61 ENV_READER 惯例）
     *
     * <p>System.getenv 在 JVM 内只读不可 mutate，FIX-G4b 使 updateTask/blockTask/claimTask
     * 等变更路径统一走 {@link #isAntUser()} 后，测试需经此缝注入 USER_TYPE 以验证
     * ant 遗留状态迁移。生产默认 {@code System::getenv}，行为零变化。
     */
    static volatile Function<String, String> ENV_READER = System::getenv;

    /**
     * 是否为 ant 用户 · 对齐 CC utils/tasks.ts:320 process.env.USER_TYPE === 'ant'
     *
     * <p>CC 真源（grep 实证 utils/tasks.ts:320）：{@code if (process.env.USER_TYPE === 'ant')}，
     * 严格相等（大小写敏感）。先例：MockRateLimits.java:59、AntModels.java:64-66。
     * 不使用 equalsIgnoreCase（QueryConfigAutoConfiguration.java:34 的宽松惯例），
     * 以 CC 严格相等为准（规则七显式选择，见 concerns）。
     *
     * @return USER_TYPE 严格等于 "ant"
     */
    private static boolean isAntUser() {
        return "ant".equals(ENV_READER.apply("USER_TYPE"));
    }

    /**
     * 读取任务并执行 ant 遗留状态迁移（统一读入口，委托 storage）· 对齐 CC tasks.ts:310-339 getTask()
     *
     * <p>FIX-G4 结构对齐：迁移读实现已下沉到
     * {@link TaskFileStorage#readTaskFileMigrated(String, String, boolean)} 单一实现
     * （getTask 与 listTaskFiles 共用，消除读路径分叉，对齐 CC listTasks 逐 id 调 getTask
     * tasks.ts:454）。本方法仅保留包内委托，作为测试注入缝（antUser 显式注入，避免依赖
     * 真实 System.getenv("USER_TYPE")，对齐 codebase StuckSkillRegistrar.java:24 /
     * RememberSkillRegistrar.java:18 惯例）。
     *
     * @param taskListId 任务列表 ID
     * @param taskId     任务 ID
     * @param antUser    USER_TYPE==='ant' 时执行遗留状态迁移
     * @return 校验通过的 Task；不存在或迁移后仍校验失败 → Optional.empty（对齐 CC return null）
     */
    Optional<Task> readTaskFileMigrated(String taskListId, String taskId, boolean antUser) {
        return fileStorage.readTaskFileMigrated(taskListId, taskId, antUser);
    }

    /**
     * 部分更新任务（文件级锁）· 对齐 CC tasks.ts:370-391 updateTask()
     *
     * <p>镜像 CC updateTask 结构：前置无锁存在性检查（tasks.ts:377-382，先 getTask
     * 不存在直接 return null，不加锁）→ 文件级锁内重读 + 逐字段合并 + 写
     * （tasks.ts:386 lockfile.lock(taskPath) + updateTaskUnsafe）。
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已换文件级锁。
     */
    public Optional<Task> updateTask(String taskListId, String taskId, Map<String, Object> updates) {
        Path taskPath = fileStorage.getTaskPath(taskListId, taskId);
        // 前置无锁存在性检查（镜像 CC tasks.ts:377-382）
        if (fileStorage.readTaskFileMigrated(taskListId, taskId, isAntUser()).isEmpty()) {
            return Optional.empty();
        }
        if (log.isDebugEnabled()) {
            log.debug("updateTask(部分): 前置无锁存在性检查通过，文件级锁 {} 更新任务 {} in list {}", taskPath, taskId, taskListId);
        }
        Optional<Task> result = TaskLock.withFileLockAndReturn(taskPath, () -> {
            // 锁内重读（镜像 CC updateTaskUnsafe 的 getTask）
            Optional<Task> existingOpt = fileStorage.readTaskFileMigrated(taskListId, taskId, isAntUser());
            if (existingOpt.isEmpty()) {
                return Optional.<Task>empty();
            }
            Task task = existingOpt.get();

            if (updates.containsKey("subject")) {
                task = task.withSubject((String) updates.get("subject"));
            }
            if (updates.containsKey("description")) {
                task = task.withDescription((String) updates.get("description"));
            }
            if (updates.containsKey("activeForm")) {
                task = task.withActiveForm((String) updates.get("activeForm"));
            }
            if (updates.containsKey("status")) {
                Object statusVal = updates.get("status");
                Task.TaskStatus newStatus;
                if (statusVal instanceof Task.TaskStatus) {
                    newStatus = (Task.TaskStatus) statusVal;
                } else {
                    newStatus = Task.TaskStatus.fromString(String.valueOf(statusVal));
                    // Task.fromString 严格化后解析失败返回 null（对齐 CC safeParse→null，tasks.ts:333-339）；
                    // 保持既有「非法状态拒绝」行为：null 视为解析失败抛 IllegalArgumentException（旧实现直接抛）。
                    if (newStatus == null) {
                        throw new IllegalArgumentException(
                            "Unknown task status: " + statusVal + ". Valid values: pending, in_progress, completed");
                    }
                }
                task = task.withStatus(newStatus);
            }
            if (updates.containsKey("owner")) {
                task = task.withOwner((String) updates.get("owner"));
            }
            if (updates.containsKey("blocks")) {
                @SuppressWarnings("unchecked")
                List<String> blocks = (List<String>) updates.get("blocks");
                task = task.withBlocksList(blocks);
            }
            if (updates.containsKey("blockedBy")) {
                @SuppressWarnings("unchecked")
                List<String> blockedBy = (List<String>) updates.get("blockedBy");
                task = task.withBlockedByList(blockedBy);
            }
            if (updates.containsKey("metadata")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) updates.get("metadata");
                task = task.withMetadata(metadata);
            }

            fileStorage.writeTaskFile(taskListId, taskId, task);
            return Optional.of(task);
        });

        if (result.isPresent()) {
            notifyTasksUpdated();
        }
        return result;
    }

    /**
     * 删除任务（无列表锁）· 对齐 CC tasks.ts:393-441 deleteTask()
     *
     * <p>镜像 CC deleteTask 结构（非原子）：
     * <ol>
     *   <li>数字 taskId 才提升 HWM（tasks.ts:401-407 parseInt + 非 NaN 才提升）</li>
     *   <li>unlink 任务文件，ENOENT → return false（tasks.ts:410-418）</li>
     *   <li>listTasks 级联逐任务调 {@link #updateTask(String, String, Map)} 清理
     *       blocks/blockedBy，每任务各自文件级锁（tasks.ts:420-434），非列表锁</li>
     *   <li>整体 try/catch → return false（tasks.ts:438-440）</li>
     * </ol>
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已删除。
     */
    public boolean deleteTask(String taskListId, String taskId) {
        try {
            // 更新 high water mark 防止 ID 复用（数字 taskId 才提升，镜像 CC tasks.ts:401-407）
            try {
                long numericId = Long.parseLong(taskId);
                long currentMark = highWatermark.readHighWaterMark(taskListId);
                if (numericId > currentMark) {
                    try {
                        highWatermark.writeHighWaterMark(taskListId, numericId);
                    } catch (IOException e) {
                        log.warn("删除任务时更新 high water mark 失败：{}", e.getMessage());
                    }
                }
            } catch (NumberFormatException e) {
                // taskId 不是数字，跳过 high water mark
            }

            // 删除任务文件（ENOENT → return false，镜像 CC tasks.ts:410-418）
            boolean deleted = fileStorage.deleteTaskFile(taskListId, taskId);
            if (!deleted) return false;

            // 清理其他任务的 blocks/blockedBy 引用（走 updateTask 文件级锁，镜像 CC tasks.ts:420-434）
            List<Task> allTasks = listTasks(taskListId);
            if (log.isDebugEnabled()) {
                log.debug("deleteTask: 无列表锁，级联清理 {} 个任务的 blocks/blockedBy 引用（每任务文件级锁）", allTasks.size());
            }
            for (Task t : allTasks) {
                List<String> newBlocks = new ArrayList<>(t.blocks());
                List<String> newBlockedBy = new ArrayList<>(t.blockedBy());
                // 独立剔除 blocks 与 blockedBy 中的 taskId（对齐 CC tasks.ts:423-427 两条独立 filter）：
                // 不能用 || 短路——若被删任务同时出现在某任务的 blocks 与 blockedBy（互斥环），
                // newBlocks.remove 返回 true 会使 newBlockedBy.remove 被跳过，blockedBy 残留悬空引用。
                boolean changed = newBlocks.remove(taskId);
                changed |= newBlockedBy.remove(taskId);
                if (changed) {
                    if (log.isDebugEnabled()) {
                        log.debug("deleteTask: 级联清理任务 {} 对已删任务 {} 的引用，blocks={} blockedBy={}",
                            t.id(), taskId, newBlocks, newBlockedBy);
                    }
                    updateTask(taskListId, t.id(),
                        Map.of("blocks", newBlocks, "blockedBy", newBlockedBy));
                }
            }

            notifyTasksUpdated();
            return true;
        } catch (Exception e) {
            log.warn("删除任务 {} 失败：{}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 列出所有任务（无锁）· 对齐 CC tasks.ts:443-456 listTasks()
     *
     * <p>CC listTasks 无锁 readdir 过滤 endsWith('.json') + 逐个 getTask
     * （getTask 本身也无锁）。非法（schema 校验失败）文件经 getTask 返回 null，
     * 被 {@code results.filter(t => t !== null)} 剔除（tasks.ts:454-456）。
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已删除。
     *
     * <p>FIX-G4 结构对齐：经 {@link TaskFileStorage#listTaskFiles(String, boolean)} 传入
     * {@code isAntUser()}，ant 用户读取列表同样复用 getTask 的遗留状态迁移（对齐 CC
     * listTasks 经 getTask，tasks.ts:454 + getTask 内 ant 迁移 tasks.ts:317-331）；
     * 非 ant 用户 {@code antUser=false} 行为与迁移前一致。
     */
    public List<Task> listTasks(String taskListId) {
        if (log.isDebugEnabled()) {
            log.debug("listTasks: 无锁 readdir list {}", taskListId);
        }
        return fileStorage.listTaskFiles(taskListId, isAntUser());
    }

    /**
     * 释放某 teammate 的全部未完成任务并生成通知消息 · 对齐 CC tasks.ts:818-860 unassignTeammateTasks()
     *
     * <p>CC 真源（grep 自验，非注释）：
     * <ol>
     *   <li>{@code listTasks(teamName)}（tasks.ts:824）</li>
     *   <li>过滤 {@code status !== 'completed' && (owner === teammateId || owner === teammateName)}
     *       （tasks.ts:825-829，<b>teammateId 与 teammateName 双键匹配</b>）</li>
     *   <li>逐任务 {@code updateTask(teamName, task.id, { owner: undefined, status: 'pending' })}
     *       （tasks.ts:832-834，走 updateTask = 触发 notifyTasksUpdated）</li>
     *   <li>通知文案 {@code actionVerb = reason === 'terminated' ? 'was terminated' : 'has shut down'}；
     *       有释放任务时追加 {@code ' N task(s) were unassigned: #id "subject", ... . Use TaskList to check availability and TaskUpdate with owner to reassign them to idle teammates.'}
     *       （tasks.ts:843-851）</li>
     *   <li>返回 {@code { unassignedTasks: [{id, subject}], notificationMessage }}（tasks.ts:853-859）</li>
     * </ol>
     *
     * <p>CC 调用方（接线语义）：print.ts:2572（shutdown_approved → 'shutdown'）；
     * TeamsDialog.tsx:573（teammate 移除 → 'terminated'）。
     *
     * @param teamName     团队/任务列表名
     * @param teammateId   teammate 的 agent ID（CC original: teammateId, tasks.ts:819）
     * @param teammateName teammate 的显示名（CC original: teammateName, tasks.ts:820）
     * @param reason       退出原因（CC original: reason, tasks.ts:822；'terminated' | 'shutdown'）
     * @return 被释放任务快照 + 通知消息
     */
    public UnassignTasksResult unassignTeammateTasks(String teamName, String teammateId,
                                                     String teammateName, String reason) {
        // 1. 列出全部任务（tasks.ts:824 listTasks）
        List<Task> tasks = listTasks(teamName);

        // 2. 过滤未完成 + 双键 owner 匹配（tasks.ts:825-829）
        List<Task> unresolved = new ArrayList<>();
        for (Task t : tasks) {
            boolean notCompleted = t.status() != Task.TaskStatus.COMPLETED;
            boolean ownedByTeammate = t.owner() != null
                && (t.owner().equals(teammateId) || t.owner().equals(teammateName));
            if (notCompleted && ownedByTeammate) {
                unresolved.add(t);
            }
        }

        // 3. 逐任务释放：owner 置空 + status 置 pending（tasks.ts:832-834 走 updateTask → notify）
        //    注意：Map.of 不接受 null 值，owner 置空须用 HashMap（CC owner: undefined）
        for (Task task : unresolved) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("owner", null);
            updates.put("status", Task.TaskStatus.PENDING);
            updateTask(teamName, task.id(), updates);
        }

        // 数据流日志（对齐 CC tasks.ts:836-840 logForDebugging 未分配数量）
        if (log.isDebugEnabled()) {
            log.debug("unassignTeammateTasks: 从 teammate {} 释放 {} 个任务 in list {}",
                teammateName, unresolved.size(), teamName);
        }

        // 4. 组装通知消息（tasks.ts:842-851）
        String actionVerb = "terminated".equals(reason) ? "was terminated" : "has shut down";
        String notificationMessage = teammateName + " " + actionVerb + ".";
        if (!unresolved.isEmpty()) {
            List<String> taskList = new ArrayList<>();
            for (Task t : unresolved) {
                taskList.add("#" + t.id() + " \"" + t.subject() + "\"");
            }
            notificationMessage += " " + unresolved.size() + " task(s) were unassigned: "
                + String.join(", ", taskList)
                + ". Use TaskList to check availability and TaskUpdate with owner to reassign them to idle teammates.";
        }
        log.info("unassignTeammateTasks: teammate {} {}，释放 {} 个任务，通知消息: {}",
            teammateName, actionVerb, unresolved.size(), notificationMessage);

        // 5. 组装返回结果（tasks.ts:853-859）
        List<UnassignTasksResult.UnassignedTask> unassigned = new ArrayList<>();
        for (Task t : unresolved) {
            unassigned.add(new UnassignTasksResult.UnassignedTask(t.id(), t.subject()));
        }
        return new UnassignTasksResult(unassigned, notificationMessage);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Team 成员与 agent 状态 · 对齐 CC tasks.ts:697-798（U-6 对称补齐）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 读取 team 成员列表 · 对齐 CC tasks.ts:724-753 readTeamMembers()
     *
     * <p>CC 真源（grep 自验，非注释）：
     * <pre>
     * const teamFilePath = join(getTeamsDir(), sanitizeName(teamName), 'config.json')  // tasks.ts:727-728
     * const content = await readFile(teamFilePath, 'utf-8')                             // tasks.ts:730
     * const teamFile = jsonParse(content) as { leadAgentId: string; members: TeamMember[] }  // tasks.ts:731-733
     * return { leadAgentId: teamFile.leadAgentId,                                       // tasks.ts:735
     *          members: teamFile.members.map(m => ({ agentId, name, agentType })) }      // tasks.ts:736-741
     * // ENOENT → null（tasks.ts:745-747）；其他错误 → logForDebugging + null（tasks.ts:748-751）
     * </pre>
     *
     * <p>team 名路径用 {@link TeamHelpers#sanitizeName}（对齐 CC tasks.ts:728 sanitizeName：
     * {@code name.replace(/[^a-zA-Z0-9]/g, '-').toLowerCase()}），复用 TeamHelpers 既有实现
     * （对齐 CC teamHelpers.ts:100-102，非 tasks.ts 私有 sanitizeName 的重复）。成员文件
     * {@code {configHome}/teams/{sanitized}/config.json} 读取在 {@link TaskFileStorage#readTeamConfig}。
     *
     * <p>CC 侧 readTeamMembers 为模块私有函数（0 导出），Java 对称补齐为 task-store API 备用
     * （U-6，用户拍板），语义以 CC tasks.ts:724-753 为准。
     *
     * @param teamName 团队名（CC original: teamName, tasks.ts:724）
     * @return team 成员（leadAgentId + members）；team 文件不存在/解析失败 → Optional.empty（对齐 CC return null）
     */
    public Optional<TeamMembers> readTeamMembers(String teamName) {
        String safeTeamName = TeamHelpers.sanitizeName(teamName);
        Optional<JsonNode> configOpt = fileStorage.readTeamConfig(safeTeamName);
        if (configOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("readTeamMembers: team {} 配置文件不存在或读取失败", teamName);
            }
            return Optional.empty();
        }
        JsonNode root = configOpt.get();

        String leadAgentId = root.path("leadAgentId").asText(null);

        List<TeamMember> members = new ArrayList<>();
        JsonNode membersNode = root.path("members");
        if (membersNode != null && membersNode.isArray()) {
            for (JsonNode m : membersNode) {
                members.add(new TeamMember(
                    m.path("agentId").asText(null),
                    m.path("name").asText(null),
                    m.path("agentType").isMissingNode() ? null : m.path("agentType").asText(null)));
            }
        }

        log.info("readTeamMembers: team {} 读取 {} 个成员（leadAgentId={}）", teamName, members.size(), leadAgentId);
        return Optional.of(new TeamMembers(leadAgentId, members));
    }

    /**
     * 获取 team 内所有 agent 的忙闲状态 · 对齐 CC tasks.ts:763-798 getAgentStatuses()
     *
     * <p>CC 真源（grep 自验，非注释）：
     * <pre>
     * const teamData = await readTeamMembers(teamName)   // tasks.ts:766
     * if (!teamData) return null                          // tasks.ts:767-769
     * const taskListId = sanitizeName(teamName)           // tasks.ts:771
     * const allTasks = await listTasks(taskListId)        // tasks.ts:772
     * // 未完成任务按 owner 分组（tasks.ts:775-782）
     * // 每成员：tasksByName = map.get(member.name)；tasksById = map.get(member.agentId)
     * // currentTasks = uniq([...tasksByName, ...tasksById])；status = 空?'idle':'busy'（tasks.ts:785-797）
     * </pre>
     *
     * <p>agent 视为 "busy" 当且仅当持有至少一个未完成任务（status != 'completed' && owner 匹配）。
     * owner 双键匹配（name 新格式 + agentId 旧格式，对齐 tasks.ts:787-789），currentTasks 去重
     * （uniq 语义，对齐 tasks.ts:789）。
     *
     * <p>CC 侧 getAgentStatuses 为导出 API 但自身 0 调用（死代码，U-6 对称补齐备用），
     * 语义以 CC tasks.ts:763-798 为准。
     *
     * @param teamName 团队名（CC original: teamName, tasks.ts:763；同时作为 taskListId 来源，tasks.ts:771）
     * @return 各 agent 状态列表；team 文件不存在 → Optional.empty（对齐 CC return null）
     */
    public Optional<List<AgentStatus>> getAgentStatuses(String teamName) {
        Optional<TeamMembers> teamDataOpt = readTeamMembers(teamName);
        if (teamDataOpt.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("getAgentStatuses: team {} 不存在，返回空", teamName);
            }
            return Optional.empty();
        }
        TeamMembers teamData = teamDataOpt.get();

        String taskListId = TeamHelpers.sanitizeName(teamName);
        List<Task> allTasks = listTasks(taskListId);

        // 未完成任务按 owner 分组（对齐 CC tasks.ts:775-782）
        Map<String, List<String>> unresolvedTasksByOwner = new HashMap<>();
        for (Task task : allTasks) {
            if (task.status() != Task.TaskStatus.COMPLETED && task.owner() != null) {
                unresolvedTasksByOwner.computeIfAbsent(task.owner(), k -> new ArrayList<>()).add(task.id());
            }
        }

        // 每成员：name + agentId 双键取并集（去重），空 → idle / 非空 → busy（对齐 CC tasks.ts:785-797）
        List<AgentStatus> statuses = new ArrayList<>();
        for (TeamMember member : teamData.members()) {
            List<String> tasksByName = unresolvedTasksByOwner.getOrDefault(member.name(), List.of());
            List<String> tasksById = unresolvedTasksByOwner.getOrDefault(member.agentId(), List.of());
            List<String> currentTasks = uniq(tasksByName, tasksById);
            String status = currentTasks.isEmpty() ? "idle" : "busy";
            statuses.add(new AgentStatus(member.agentId(), member.name(), member.agentType(),
                status, currentTasks));
        }

        log.info("getAgentStatuses: team {} 汇总 {} 个 agent 状态", teamName, statuses.size());
        return Optional.of(statuses);
    }

    /** 有序去重并集 · 对齐 CC array.ts:11-13 {@code uniq(xs) = [...new Set(xs)]}（tasks.ts:789 依赖） */
    private static List<String> uniq(List<String> first, List<String> second) {
        LinkedHashSet<String> set = new LinkedHashSet<>(first);
        set.addAll(second);
        return List.copyOf(set);
    }

    /**
     * 重置任务列表（带锁）· 对齐 CC tasks.ts:147-188 resetTaskList()
     */
    public void resetTaskList(String taskListId) {
        Path tasksDir = fileStorage.getTasksDir(taskListId);
        if (log.isDebugEnabled()) {
            log.debug("resetTaskList: 列表级锁 {} 重置任务列表 {}（跳过点文件，对齐 CC tasks.ts:147-188）", tasksDir, taskListId);
        }
        TaskLock.withLock(tasksDir, () -> {
            Path dir = fileStorage.getTasksDir(taskListId);

            long currentHighest = highWatermark.findHighestTaskIdFromFiles(taskListId);
            if (currentHighest > 0) {
                long existingMark = highWatermark.readHighWaterMark(taskListId);
                if (currentHighest > existingMark) {
                    try {
                        highWatermark.writeHighWaterMark(taskListId, currentHighest);
                    } catch (IOException e) {
                        log.warn("重置任务列表时更新 high water mark 失败：{}", e.getMessage());
                    }
                }
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    // 跳过点文件（对齐 CC tasks.ts:173：file.endsWith('.json') && !file.startsWith('.')）
                    if (name.startsWith(".")) {
                        continue;
                    }
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("删除任务文件 {} 失败：{}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                // 目录不存在
            }
        });
        notifyTasksUpdated();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 阻塞图操作 · 对齐 CC tasks.ts:458-486 blockTask()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 设置阻塞关系（无列表锁）· 对齐 CC tasks.ts:458-486 blockTask()
     *
     * <p>镜像 CC blockTask（非原子双写）：
     * <ol>
     *   <li>两次无锁 getTask，任一缺失 → return false（tasks.ts:463-469）</li>
     *   <li>源任务加 blocks（tasks.ts:472-476）、目标任务加 blockedBy（tasks.ts:478-482），
     *       各自走 {@link #updateTask(String, String, Map)} 文件级锁</li>
     *   <li>返回 true</li>
     * </ol>
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已删除。
     */
    public boolean blockTask(String taskListId, String fromTaskId, String toTaskId) {
        // 两次无锁读取（镜像 CC tasks.ts:463-466）
        Optional<Task> fromOpt = fileStorage.readTaskFileMigrated(taskListId, fromTaskId, isAntUser());
        Optional<Task> toOpt = fileStorage.readTaskFileMigrated(taskListId, toTaskId, isAntUser());

        if (fromOpt.isEmpty() || toOpt.isEmpty()) {
            return false;
        }

        Task fromTask = fromOpt.get();
        Task toTask = toOpt.get();

        if (!fromTask.blocks().contains(toTaskId)) {
            List<String> newBlocks = new ArrayList<>(fromTask.blocks());
            newBlocks.add(toTaskId);
            updateTask(taskListId, fromTaskId, Map.of("blocks", newBlocks));
        }

        if (!toTask.blockedBy().contains(fromTaskId)) {
            List<String> newBlockedBy = new ArrayList<>(toTask.blockedBy());
            newBlockedBy.add(fromTaskId);
            updateTask(taskListId, toTaskId, Map.of("blockedBy", newBlockedBy));
        }

        if (log.isDebugEnabled()) {
            log.debug("blockTask: 无列表锁，{} 阻塞 {} in list {}（两次 updateTask 文件级锁）", fromTaskId, toTaskId, taskListId);
        }
        return true;
    }


    // ════════════════════════════════════════════════════════════════════════
    // 任务认领 · 对齐 CC tasks.ts:541-612 claimTask()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 尝试认领任务（checkAgentBusy=false，文件级锁）· 对齐 CC tasks.ts:541-612 claimTask()
     *
     * <p>默认文件级锁变体（对齐 CC tasks.ts:562-611）：
     * <ol>
     *   <li>前置无锁存在性检查（tasks.ts:549-554，taskBeforeLock 不存在 →
     *       task_not_found，不加锁）</li>
     *   <li>文件级锁内（tasks.ts:566 lockfile.lock(taskPath)）重读任务 + 已认领 /
     *       已完成 / blocked 检查 + writeTaskFile（updateTaskUnsafe 语义，tasks.ts:596-599）</li>
     * </ol>
     * 原列表级 {@code TaskLock.withLockAndReturn} 包装为脏代码，已换文件级锁。
     *
     * <p>s12.5-4.1: 引入 per-call resolvedCache（Map&lt;String, Task&gt;），
     * blockedBy 依赖解析时先查缓存，未命中再读存储。方法返回后缓存自动 GC。
     *
     * @see #claimTask(String, String, String, boolean)
     */
    public ClaimTaskResult claimTask(String taskListId, String taskId, String claimantAgentId) {
        return claimTask(taskListId, taskId, claimantAgentId, false);
    }

    /**
     * 尝试认领任务 · 对齐 CC tasks.ts:541-612 claimTask() + :558/:618-692 checkAgentBusy 变体
     *
     * <p>两个锁变体（对齐 CC 真实锁粒度，grep 实证）：
     * <ul>
     *   <li><b>checkAgentBusy=false</b>（默认，CC tasks.ts:562-611）：<b>文件级锁</b>
     *       （tasks.ts:566 lockfile.lock(taskPath)）认领单任务。</li>
     *   <li><b>checkAgentBusy=true</b>（CC tasks.ts:558 分支 + claimTaskWithBusyCheck
     *       tasks.ts:618-692）：<b>列表级锁</b>（tasks.ts:623-628 ensureTaskListLockFile +
     *       lockfile.lock(lockPath)）内原子做「agent 是否已忙」检查 + 认领，防 TOCTOU 竞态。
     *       agent 已持有其他未完成任务 → {@link ClaimTaskResult.AgentBusy}（tasks.ts:661-674）。</li>
     * </ul>
     * 两种变体均保留：前置无锁存在性检查（tasks.ts:549-554）、已认领 / 已完成 / blocked
     * 检查、认领写 owner（updateTaskUnsafe 语义，tasks.ts:596-599）。
     *
     * @param checkAgentBusy true 时走列表级锁原子检查（对齐 CC ClaimTaskOptions.checkAgentBusy，tasks.ts:525-532）
     */
    public ClaimTaskResult claimTask(String taskListId, String taskId, String claimantAgentId, boolean checkAgentBusy) {
        Path taskPath = fileStorage.getTaskPath(taskListId, taskId);
        // Step 0: 前置无锁存在性检查（镜像 CC tasks.ts:549-554）
        if (fileStorage.readTaskFileMigrated(taskListId, taskId, isAntUser()).isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("claimTask: 任务 {} 在列表 {} 中不存在", taskId, taskListId);
            }
            return ClaimTaskResult.taskNotFound();
        }
        if (checkAgentBusy) {
            // checkAgentBusy=true → 列表级锁变体（对齐 CC tasks.ts:558 + :618-628 lockfile.lock(lockPath)）
            if (log.isDebugEnabled()) {
                log.debug("claimTask(checkAgentBusy=true): 列表级锁 {} 原子检查 agent busy + 认领任务 {} by agent {}", taskPath.getParent(), taskId, claimantAgentId);
            }
            return claimTaskWithBusyCheck(taskListId, taskId, claimantAgentId);
        }
        if (log.isDebugEnabled()) {
            log.debug("claimTask: 前置无锁存在性检查通过，文件级锁 {} 认领任务 {} by agent {}", taskPath, taskId, claimantAgentId);
        }
        // s12.5-4.1: per-call resolvedCache — 本次 claim 调用内任务读取缓存
        Map<String, Task> resolvedCache = new HashMap<>();
        try {
            return TaskLock.withFileLockAndReturn(taskPath, () -> {
            // Step 1: 检查任务是否存在（缓存优先，锁内重读）
            Task task = getOrReadTask(taskListId, taskId, resolvedCache);
            if (task == null) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask: 任务 {} 在列表 {} 中不存在", taskId, taskListId);
                }
                return ClaimTaskResult.taskNotFound();
            }

            // Step 2: 检查是否已被其他 agent 认领
            if (task.owner() != null && !task.owner().equals(claimantAgentId)) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask: 任务 {} 已被 {} 认领", taskId, task.owner());
                }
                return ClaimTaskResult.alreadyClaimed(task.owner(), task);
            }

            // Step 3: 检查是否已完成
            if (task.status() == Task.TaskStatus.COMPLETED) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask: 任务 {} 已完成，不可重复认领", taskId);
                }
                return ClaimTaskResult.alreadyResolved(task);
            }

            // Step 4: 检查 blockedBy（s12.5-4.1: 通过 resolvedCache 按需逐条读取依赖任务）
            List<String> blockedByTasks = new ArrayList<>();
            for (String depId : task.blockedBy()) {
                Task depTask = getOrReadTask(taskListId, depId, resolvedCache);
                // 依赖任务不存在（已被删除）→ 不阻塞
                if (depTask != null && depTask.status() != Task.TaskStatus.COMPLETED) {
                    blockedByTasks.add(depId);
                }
            }
            if (!blockedByTasks.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask: 任务 {} 被阻塞，依赖：{}", taskId, blockedByTasks);
                }
                return ClaimTaskResult.blocked(task, blockedByTasks);
            }

            // Step 5: 认领成功（updateTaskUnsafe 语义，镜像 CC tasks.ts:596-599）
            Task updated = new Task(task.id(), task.subject(), task.description(),
                task.activeForm(), claimantAgentId, task.status(),
                task.blocks(), task.blockedBy(), task.metadata());
            fileStorage.writeTaskFile(taskListId, taskId, updated);
            // 写盘后必通知（对齐 CC tasks.ts:366 updateTaskUnsafe 写盘后 notifyTasksUpdated）——
            // 认领成功必然触发 tasksUpdated signal，供 UI/消费者刷新任务列表
            notifyTasksUpdated();

            log.info("claimTask: 任务 {}（\"{}\"）已由 {} 认领", taskId, task.subject(), claimantAgentId);
            return ClaimTaskResult.success(updated);
        });
        } catch (Exception e) {
            // 对齐 CC tasks.ts:601-606：catch(error) → logForDebugging + logError(error) + task_not_found。
            // 覆盖锁获取失败（TaskLock.withRetryLoop 30 次重试耗尽抛 LockAcquisitionException，
            // 对齐 proper-lockfile retries 耗尽后 throw，tasks.ts:566）与锁内读/写 owner 全程异常
            // （writeTaskFile IO 失败抛 RuntimeException）——claimTask 永不向上抛异常。
            if (log.isDebugEnabled()) {
                log.debug("claimTask: 认领任务 {} 失败（锁获取或读写异常），详情：{}", taskId, e.toString());
            }
            log.error("claimTask: 认领任务 {} 失败（锁获取或读写异常），按 CC task_not_found 返回：{}", taskId, e.getMessage());
            return ClaimTaskResult.taskNotFound();
        }
    }

    /**
     * checkAgentBusy=true 的列表级锁认领 · 对齐 CC tasks.ts:618-692 claimTaskWithBusyCheck()
     *
     * <p>列表级锁（tasksDir/.lock，对齐 CC tasks.ts:623-628 lockfile.lock(lockPath)）内原子：
     * 全量 listTasks（tasks.ts:631）→ 找目标任务 → 已认领 / 已完成 / blocked 检查
     * （tasks.ts:640-658）→ agent busy 检查（tasks.ts:661-674）→ 认领写 owner
     * （tasks.ts:677-679 updateTask）。返回 agent_busy 时携带 busyWithTasks。
     *
     * <p>认领写走 fileStorage.writeTaskFile 直接写（锁内已持有列表锁，且已在内存读到目标任务，
     * 无需再取文件级锁——对齐 CC updateTaskUnsafe「调用方已持锁」语义，tasks.ts:352-368）。
     */
    private ClaimTaskResult claimTaskWithBusyCheck(String taskListId, String taskId, String claimantAgentId) {
        Path tasksDir = fileStorage.getTasksDir(taskListId);
        try {
            return TaskLock.withLockAndReturn(tasksDir, () -> {
            // 锁内全量读取（镜像 CC tasks.ts:631 allTasks = await listTasks(taskListId)；
            // ant 用户复用 getTask 迁移语义，对齐 CC listTasks 经 getTask）
            List<Task> allTasks = fileStorage.listTaskFiles(taskListId, isAntUser());
            Task task = allTasks.stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
            if (task == null) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask(checkAgentBusy): 任务 {} 在列表 {} 中不存在", taskId, taskListId);
                }
                return ClaimTaskResult.taskNotFound();
            }

            // 已被其他 agent 认领（镜像 CC tasks.ts:640-643）
            if (task.owner() != null && !task.owner().equals(claimantAgentId)) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask(checkAgentBusy): 任务 {} 已被 {} 认领", taskId, task.owner());
                }
                return ClaimTaskResult.alreadyClaimed(task.owner(), task);
            }

            // 已完成（镜像 CC tasks.ts:645-647）
            if (task.status() == Task.TaskStatus.COMPLETED) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask(checkAgentBusy): 任务 {} 已完成，不可重复认领", taskId);
                }
                return ClaimTaskResult.alreadyResolved(task);
            }

            // 未完成 blockedBy（镜像 CC tasks.ts:650-658；依赖任务缺失视为不阻塞）
            List<String> blockedByTasks = new ArrayList<>();
            for (String depId : task.blockedBy()) {
                Task depTask = allTasks.stream().filter(t -> t.id().equals(depId)).findFirst().orElse(null);
                if (depTask != null && depTask.status() != Task.TaskStatus.COMPLETED) {
                    blockedByTasks.add(depId);
                }
            }
            if (!blockedByTasks.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask(checkAgentBusy): 任务 {} 被阻塞，依赖：{}", taskId, blockedByTasks);
                }
                return ClaimTaskResult.blocked(task, blockedByTasks);
            }

            // agent busy 检查（镜像 CC tasks.ts:661-674：agent 已持有其他未完成任务 → agent_busy）
            List<String> agentOpenTasks = allTasks.stream()
                .filter(t -> t.status() != Task.TaskStatus.COMPLETED
                    && claimantAgentId.equals(t.owner())
                    && !t.id().equals(taskId))
                .map(Task::id)
                .toList();
            if (!agentOpenTasks.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("claimTask(checkAgentBusy): 任务 {} 认领失败，agent {} 已忙于任务 {}", taskId, claimantAgentId, agentOpenTasks);
                }
                return ClaimTaskResult.agentBusy(task, agentOpenTasks);
            }

            // 认领成功（锁内写 owner，镜像 CC tasks.ts:677-679 updateTask；updateTaskUnsafe 语义）
            Task updated = new Task(task.id(), task.subject(), task.description(),
                task.activeForm(), claimantAgentId, task.status(),
                task.blocks(), task.blockedBy(), task.metadata());
            fileStorage.writeTaskFile(taskListId, taskId, updated);
            // 写盘后必通知（对齐 CC tasks.ts:366 updateTask → updateTaskUnsafe 写盘后 notifyTasksUpdated）——
            // busy 变体认领成功同样必然触发 tasksUpdated signal
            notifyTasksUpdated();

            log.info("claimTask(checkAgentBusy): 任务 {}（\"{}\"）已由 {} 认领", taskId, task.subject(), claimantAgentId);
            return ClaimTaskResult.success(updated);
        });
        } catch (Exception e) {
            // 对齐 CC tasks.ts:681-686：catch(error) → logForDebugging + logError(error) + task_not_found。
            // 覆盖列表级锁获取失败（TaskLock.withRetryLoop 30 次重试耗尽抛 LockAcquisitionException，
            // 对齐 proper-lockfile retries 耗尽后 throw，tasks.ts:628）与锁内 listTasks/写 owner 全程异常
            // （writeTaskFile IO 失败抛 RuntimeException）——claimTaskWithBusyCheck 永不向上抛异常。
            if (log.isDebugEnabled()) {
                log.debug("claimTask(checkAgentBusy): 认领任务 {} 失败（锁获取或读写异常），详情：{}", taskId, e.toString());
            }
            log.error("claimTask(checkAgentBusy): 认领任务 {} 失败（锁获取或读写异常），按 CC task_not_found 返回：{}", taskId, e.getMessage());
            return ClaimTaskResult.taskNotFound();
        }
    }

    /**
     * 从缓存或存储读取任务 · s12.5-4.1
     *
     * <p>先查 resolvedCache，未命中则读存储并回填缓存。
     * 返回 null 表示任务不存在。
     */
    private Task getOrReadTask(String taskListId, String taskId, Map<String, Task> cache) {
        return cache.computeIfAbsent(taskId, id ->
            fileStorage.readTaskFileMigrated(taskListId, id, isAntUser()).orElse(null));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 清理路径组件 · 对齐 CC tasks.ts:217-219 sanitizePathComponent()
     */
    public static String sanitizePathComponent(String input) {
        return TaskFileStorage.sanitizePathComponent(input);
    }
}

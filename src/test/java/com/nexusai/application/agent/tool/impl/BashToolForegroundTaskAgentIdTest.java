package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.bash.ShellResolver;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.LocalBashTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.StallWatchdog;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskIdGenerator;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator-align] 前台 bash 登记必须携带调用方 {@code ctx.agentId}（T2 断点回归守卫）。
 *
 * <p><b>WHY（规则九 · 验意图而非行为）</b>：前台登记那一刻是该 task 归属 agentId 的<b>唯一写入点</b>——
 * CC {@code registerForeground} 的 taskState 从 input 解构 agentId 装入
 * （LocalShellTask.tsx:266 → :283），此后转后台完成/失败读回 {@code prevTask.agentId} 入队
 * （:430/:470）、stall advisory 透传（:89-94）、{@code killShellTasksForAgent} 按
 * {@code task.agentId === agentId} 收尸（killShellTasks.ts:62）。写死 null ⇒ 该 task 与主会话任务
 * 无法区分 ⇒ 完成回执 agentId=null ⇒ {@code NotificationQueue.drainForQuery} 主线程规则
 * （只捞 agentId==null，NotificationQueue.java:775 ← query.ts:1574）把它截给主会话 ⇒
 * <b>主 agent 收到子代理内部后台命令的回执</b>（上报现象），子代理自己永远收不到。
 *
 * <p>纯 JUnit（⛔ 无 {@code @SpringBootTest} —— 会迁移用户真库），断言用真实对象不 mock 无关物。
 */
@DisplayName("[coordinator-align] 前台 bash 登记携带调用方 agentId")
class BashToolForegroundTaskAgentIdTest {

    private static final String SESSION = "sess-fg-agentid";

    /** 子代理 ctx（agentId 非 null —— CC 只有子代理 context 才带 agentId，Tool.ts:245） */
    private static ToolUseContext subagentCtx(UUID agentId) {
        return new ToolUseContext(agentId, SESSION, PermissionMode.DEFAULT, Map.of(), List.of());
    }

    /** 主线程 ctx（agentId=null —— 对齐 CC {@code !context.agentId}，ToolUseContext.java:424-425） */
    private static ToolUseContext mainThreadCtx() {
        return new ToolUseContext(null, SESSION, PermissionMode.DEFAULT, Map.of(), List.of());
    }

    private static BackgroundTask build(UUID agentId) {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        return BashTool.buildForegroundTask(
            taskId, "sleep 1", "tu-" + taskId,
            "/tmp/" + taskId + ".output", SESSION, agentId == null ? mainThreadCtx() : subagentCtx(agentId));
    }

    @Test
    @DisplayName("调用方 ctx.agentId 非 null ⇒ 前台登记出的 BackgroundTask 携带该 agentId")
    void foregroundTask_carriesCallingAgentId() {
        UUID agentId = UUID.randomUUID();

        BackgroundTask fg = build(agentId);

        assertThat(fg.agentId())
            .as("前台登记是归属唯一写入点：为 null 则完成回执必被主线程规则（只捞 agentId==null）截给主会话")
            .isEqualTo(agentId);
        assertThat(fg.isBackgrounded())
            .as("前台任务未转后台（对齐 CC LocalShellTask.tsx:281 isBackgrounded:false）")
            .isFalse();
        assertThat(fg.sessionId())
            .as("创建会话透传（通知归会话 · withSessionId）")
            .isEqualTo(SESSION);
    }

    @Test
    @DisplayName("主线程 ctx（agentId=null）⇒ 归属保持 null，不编造")
    void foregroundTask_mainThread_nullNotInvented() {
        BackgroundTask fg = build(null);

        assertThat(fg.agentId())
            .as("主线程无归属可传 —— 编造 UUID 会让主会话自己的回执没人消费")
            .isNull();
    }

    @Test
    @DisplayName("ctx=null（1 参 dispatch 兼容路径）⇒ 不抛、归属保持 null")
    void foregroundTask_nullCtx_keepsNull() {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);

        BackgroundTask fg = BashTool.buildForegroundTask(
            taskId, "echo hi", "tu-" + taskId,
            "/tmp/" + taskId + ".output", SESSION, null);

        assertThat(fg.agentId()).isNull();
        assertThat(fg.id()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("归属透到终态通知：registerForeground → cancel(markKilled) 入队的 agentId = 调用方 agentId")
    void attribution_reachesEnqueuedTerminalNotification() {
        UUID agentId = UUID.randomUUID();
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(
            queue, new TaskFrameworkService(null));

        BackgroundTask fg = build(agentId);
        runner.registerForeground(fg, new LocalBashTaskRunner());

        // 前台任务不在 runningTasks（不经 executor.submit）→ cancel 走 tasks RUNNING 分支 → markKilled
        assertThat(runner.cancel(fg.id())).isTrue();

        List<NotificationQueue.QueueItem> enqueued =
            queue.dequeueAllMatching(item -> true);
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).agentId())
            .as("终止通知必须回归属代理，否则主线程规则把它截给主会话")
            .isEqualTo(agentId.toString());
        assertThat(enqueued.get(0).mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 调用点守卫（F2）：只测方法体守不住调用点 —— 改回内联写死 null 时方法体测试仍全绿
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("调用点守卫：execute 的 G5-2 登记必须经唯一构造点并传 ctx（防改回内联写死 null 静默回归）")
    void callSite_mustRouteThroughBuildForegroundTaskWithCtx() {
        String src = readBashToolSource();

        int reg = src.indexOf("backgroundTaskRunner.registerForeground(");
        assertThat(reg).as("BashTool 必须存在前台登记调用点").isGreaterThan(0);
        assertThat(src.indexOf("backgroundTaskRunner.registerForeground(", reg + 1))
            .as("前台登记调用点应唯一").isEqualTo(-1);

        // 登记点之前的窗口 = fgTask 构造 + adoptForegroundProcess —— 正是被守卫的调用点区域
        String window = src.substring(Math.max(0, reg - 900), reg);
        assertThat(window)
            .as("G5-2 登记必须经唯一构造点：改回内联 new BackgroundTask(...，归属写入点即分裂，"
                + "本守卫红 = 提示走回 buildForegroundTask(...)")
            .contains("buildForegroundTask(");
        assertThat(window)
            .as("登记点前不得出现内联 new BackgroundTask(...（前台构造唯一入口）")
            .doesNotContain("new BackgroundTask(");

        int call = window.indexOf("buildForegroundTask(");
        String args = argsOf(window, call);
        assertThat(args)
            .as("第 6 实参必须是 ctx（归属唯一来源）")
            .contains("ctx");
        assertThat(args)
            .as("实参不得写死字面 null —— 写 null 即上报现象回归（回执被主会话截走）")
            .doesNotContain("null");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 三个生产入队点（F3）：前台→后台的 完成 / 失败 / stall —— 真 NotificationQueue 出队断言
    // ══════════════════════════════════════════════════════════════════════

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("生产点①：转后台完成入队（completeForegroundBackgroundedTask 完成分支）agentId 归属正确")
    void backgroundedCompletionEnqueue_carriesTaskAgentId() {
        Assumptions.assumeTrue(shellAvailable(),
            "需要可用 bash/zsh（Windows 走 Git Bash；ShellResolver.resolveShell 找不到则跳过）");

        UUID agentId = UUID.randomUUID();
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(
            queue, new TaskFrameworkService(null));

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        String outputFile = tempDir.resolve(taskId + ".output").toString();
        LocalBashTaskRunner bashRunner = new LocalBashTaskRunner();
        // 前台命令在独立线程运行（等价 BashTool G5 线程），随后转后台由 watcher 等它自然结束
        Thread t = new Thread(() -> {
            try {
                bashRunner.execute("sleep 2", outputFile);
            } catch (Exception ignored) {
                // 测试收尾不要求命令成功
            }
        }, "fg-bash-complete");
        t.setDaemon(true);
        t.start();
        awaitUntil(bashRunner::isProcessAlive, "前台 bash 进程启动");

        BackgroundTask fg = BashTool.buildForegroundTask(taskId, "sleep 2", "tu-" + taskId,
            outputFile, SESSION, subagentCtx(agentId));
        runner.registerForeground(fg, bashRunner);
        assertThat(runner.backgroundExistingForegroundTask(taskId))
            .as("进程存活 → 就地转后台（对齐 CC :421 shellCommand.background）")
            .isTrue();

        awaitUntil(() -> queue.size() > 0, "转后台完成通知入队（sleep 2 自然结束）");

        List<NotificationQueue.QueueItem> enqueued = queue.dequeueAllMatching(item -> true);
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).agentId())
            .as("完成回执必须回归属代理，否则 drainForQuery 主线程规则截给主会话")
            .isEqualTo(agentId.toString());
        assertThat(enqueued.get(0).mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
        t.interrupt();
    }

    @Test
    @DisplayName("生产点②：转后台失败入队（completeForegroundBackgroundedTask catch 分支）agentId 归属正确")
    void backgroundedFailureEnqueue_carriesTaskAgentId() {
        UUID agentId = UUID.randomUUID();
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(
            queue, new TaskFrameworkService(null));

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        // 故障注入点 = runner 自身（读结果那一步）：真 runner 的 getLastResult 只会返回 null，
        // 而 null 走的是「无结果 → FAILED」正常分支（:1149-1153），**进不了** catch（:1178-1199）。
        // 要覆盖 :1194 那条入队，必须让它在 try 内抛 RuntimeException 且 catch 内不再抛 ——
        // 故只覆写读结果方法；队列 / frameworkService / BackgroundTaskRunner 全用真对象。
        LocalBashTaskRunner exploding = new LocalBashTaskRunner() {
            @Override
            public boolean isProcessAlive() {
                return true; // 过 backgroundExistingForegroundTask 的存活守卫（:994）
            }

            @Override
            public void awaitCompletion() {
                // 立即返回（不阻塞 watcher）
            }

            @Override
            public BashResult getLastResult() {
                throw new IllegalStateException("[test] 注入读结果失败 → 落 completeForegroundBackgroundedTask catch");
            }
        };

        BackgroundTask fg = BashTool.buildForegroundTask(taskId, "sleep 0", "tu-" + taskId,
            tempDir.resolve(taskId + ".output").toString(), SESSION, subagentCtx(agentId));
        runner.registerForeground(fg, exploding);
        assertThat(runner.backgroundExistingForegroundTask(taskId)).isTrue();

        awaitUntil(() -> queue.size() > 0, "转后台失败通知入队");

        List<NotificationQueue.QueueItem> enqueued = queue.dequeueAllMatching(item -> true);
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).agentId())
            .as("失败回执同样必须回归属代理")
            .isEqualTo(agentId.toString());
        assertThat(enqueued.get(0).mode()).isEqualTo(NotificationQueue.MODE_TASK_NOTIFICATION);
    }

    @Test
    @DisplayName("生产点③：前台转后台 stall 入队（newStallWatchdog 回调）agentId 归属正确")
    void stallEnqueue_carriesTaskAgentId() throws Exception {
        UUID agentId = UUID.randomUUID();
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(
            queue, new TaskFrameworkService(null));

        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        BackgroundTask task = BashTool.buildForegroundTask(taskId, "sleep 1", "tu-" + taskId,
            tempDir.resolve(taskId + ".output").toString(), SESSION, subagentCtx(agentId));

        // 为什么走反射：stall 回调闭包在 private newStallWatchdog(BackgroundTask) 的 lambda 里
        // （BackgroundTaskRunner:1102-1109），阈值/间隔是 CC 常量 45s/5s（StallWatchdog.java:43-46）
        // ⇒ 等真实 tick 要 45s+，不可测。取回**生产 lambda 本体**直接 run，执行的就是那段入队代码
        // （非测试自造等价物），变异 :1107 的 agentId 公式本测试必须变红。
        Method factory = BackgroundTaskRunner.class.getDeclaredMethod("newStallWatchdog", BackgroundTask.class);
        factory.setAccessible(true);
        StallWatchdog watchdog = (StallWatchdog) factory.invoke(runner, task);
        Field action = StallWatchdog.class.getDeclaredField("onStallAction");
        action.setAccessible(true);
        try {
            Runnable onStall = (Runnable) action.get(watchdog);
            assertThat(onStall).as("newStallWatchdog 必须挂 stall 回调").isNotNull();
            onStall.run();
        } finally {
            watchdog.stop(); // 关掉构造期建的调度器，防线程泄漏
        }

        List<NotificationQueue.QueueItem> enqueued = queue.dequeueAllMatching(item -> true);
        assertThat(enqueued).hasSize(1);
        assertThat(enqueued.get(0).agentId())
            .as("stall advisory 必须让归属代理看到，否则子代理以为命令还活着")
            .isEqualTo(agentId.toString());
        assertThat(enqueued.get(0).priority())
            .as("stall 走 NEXT（CC LocalShellTask.tsx:89-94 priority:'next'）")
            .isEqualTo(NotificationQueue.Priority.NEXT);
    }

    // ──────────────────────────────────────────────────────────────────────

    private static boolean shellAvailable() {
        try {
            ShellResolver.resolveShell();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static void awaitUntil(BooleanSupplier cond, String desc) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + desc, e);
            }
        }
        throw new AssertionError("等待超时: " + desc);
    }

    /** 读 BashTool 源文件（测试工作目录 = backend basedir；兜底再试仓库相对路径）。 */
    private static String readBashToolSource() {
        List<Path> candidates = List.of(
            Path.of("src/main/java/com/nexusai/application/agent/tool/impl/BashTool.java"),
            Path.of("backend/src/main/java/com/nexusai/application/agent/tool/impl/BashTool.java"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("读取 BashTool.java 失败: " + p.toAbsolutePath(), e);
                }
            }
        }
        throw new AssertionError("找不到 BashTool.java 源文件，已试: " + candidates
            + "，user.dir=" + System.getProperty("user.dir"));
    }

    /** 截取 src 中 openIdx 处 `(` 起的完整实参串（括号配平）。 */
    private static String argsOf(String src, int openIdx) {
        int open = src.indexOf('(', openIdx);
        assertThat(open).as("调用点后必须有左括号").isGreaterThan(-1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("实参括号不配平");
    }
}

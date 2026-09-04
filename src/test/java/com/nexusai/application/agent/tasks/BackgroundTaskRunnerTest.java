package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R-A7 / 方案B · outputFile 路径对齐 CC 五层 getTaskOutputDir（推翻 A-7 简化根 nexusai-sessions）。
 *
 * <p><b>CC 真源（Read 自验）</b>：
 * <ul>
 *   <li>{@code getTaskOutputPath(taskId) = join(getTaskOutputDir(), `${taskId}.output`)}
 *       （{@code Open-ClaudeCode/src/utils/task/diskOutput.ts:72-74}）</li>
 *   <li>{@code getTaskOutputDir() = join(getProjectTempDir(), getSessionId(), 'tasks')}
 *       （diskOutput.ts:50-55）</li>
 *   <li>{@code getProjectTempDir() = join(getClaudeTempDir(), sanitizePath(getOriginalCwd())) + sep}
 *       （{@code src/utils/permissions/filesystem.ts:376-378}）</li>
 *   <li>{@code getClaudeTempDir() = join(tmpdir, getClaudeTempDirName()) + sep}
 *       （filesystem.ts:331-347）；{@code getClaudeTempDirName()}：Windows→{@code claude}，
 *       Unix→{@code claude-{uid}}（filesystem.ts:307-315）—— Java 单出口
 *       {@link NexusaiPaths#getAppTempDirName()}，per-user 层品牌名动态 = {appName}（自有，无前导点），
 *       行为仍镜像 CC 结构（win 无 uid / Unix {appName}-{uid数字}）。</li>
 *   <li>{@code sanitizePath}：非字母数字→'-'（sessionStoragePortable.ts:311-319）</li>
 *   <li>Task 创建：{@code Task.ts:121 outputFile: getTaskOutputPath(id)}（LocalAgentTask.tsx:488/553
 *       createTaskStateBase 消费）</li>
 * </ul>
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>——五层为何重要：
 * <ul>
 *   <li><b>per-user 层</b>（{@code {appName}-{uid}}）：Unix 多用户共享同一 {@code /tmp}，不加 uid 层会造成
 *       权限冲突与跨用户串读（filesystem.ts:311-313）；Windows tmpdir 已 per-user 故 CC 不加 uid
 *       （filesystem.ts:305/308-310）——Java 走 {@link NexusaiPaths#getAppTempDirName()} 平台分支
 *       （per-user 层品牌名已动态 appName，行为仍镜像 CC 结构）。</li>
 *   <li><b>per-project 层</b>（sanitizePath(originalCwd)）：不同项目 originalCwd → 不同输出目录
 *       （filesystem.ts:376-378），同一用户多个项目互不串扰。</li>
 *   <li><b>per-session 层</b>：sessionId 纳入路径防并发会话 clobber（diskOutput.ts:38-41）。</li>
 *   <li><b>前端按 CC 契约读 outputFile 查进度</b>：CC 路径为五层
 *       {@code <projectTempDir>/<sessionId>/tasks/<id>.output}（diskOutput.ts:72-74）；
 *       Java 旧 A-7 简化根 {@code {tmpdir}/nexusai-sessions} 与 CC 真源不符（缺 per-user/per-project 层），
 *       已删除（无兼容层/双轨）。</li>
 * </ul>
 *
 * <p>本类锁定：{@code taskOutputPath} 产出 CC 五层格式（含 per-user {appName}[-{uid}] + originalCwd sanitize +
 * sessionId + tasks + .output）、registerAsyncAgent / registerAgentForeground 的 outputFile 走
 * {@code taskOutputPath}、appendToOutputFile 写前建父目录、per-project 隔离、sanitizePath 分隔符替换。
 */
@DisplayName("[R-A7/方案B] BackgroundTaskRunner outputFile 路径对齐 CC 五层 getTaskOutputDir")
class BackgroundTaskRunnerTest {

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), framework);

    @AfterEach
    void tearDown() {
        // MDC ThreadLocal + sysprop + SessionCwdHolder（originalCwd 登记）均须清理，
        // 避免跨测试线程复用泄漏（RequestContext 铁律 + originalCwd 槽防串扰）
        RequestContext.clear();
        System.clearProperty("nexusai.sessionId");
        SessionCwdHolder.reset();
    }

    /** 期望 CC 五层路径（与生产 taskOutputDir 同源镜像，锁定五层形态）。 */
    private static String expectedFiveLayerPath(String sessionId, String taskId) {
        String sanitizedCwd = AutoMemPaths.sanitizePath(CwdResolution.getOriginalCwdLayer(sessionId));
        return Paths.get(NexusaiPaths.getAppTempDir(), sanitizedCwd,
            sessionId, "tasks", taskId + ".output").toString();
    }

    @Test
    @DisplayName("taskOutputPath 产出 CC 五层 {tmpRoot}/{appName}[-{uid}]/{sanitizedCwd}/{sessionId}/tasks/{taskId}.output")
    void taskOutputPath_producesCcFiveLayerFormat() {
        // WHY: CC getTaskOutputPath = join(getTaskOutputDir(), `${taskId}.output`)，
        //   getTaskOutputDir = join(getProjectTempDir(), getSessionId(), 'tasks')（diskOutput.ts:50-55/72-74），
        //   getProjectTempDir = join(getClaudeTempDir(), sanitizePath(getOriginalCwd()))（filesystem.ts:376-378）。
        //   sessionId 纳入路径防止并发会话 clobber（diskOutput.ts:38-41）。旧平铺 /tmp/agent-*.out 已删除。
        String sessionId = "sess-r-b";
        RequestContext.setSession(sessionId);
        String taskId = "a12345678";

        String path = BackgroundTaskRunner.taskOutputPath(taskId);

        assertThat(path).as("必须为 CC 五层格式（per-user + per-project + per-session + tasks + .output）")
            .isEqualTo(expectedFiveLayerPath(sessionId, taskId));
        assertThat(path).contains("tasks", taskId + ".output");
        // 旧平铺格式不得再产出
        assertThat(path).doesNotStartWith("/tmp/agent-").doesNotContain("agent-" + taskId + ".out");
    }

    @Test
    @DisplayName("taskOutputDir 产出 CC 五层唯一根（{appName}[-{uid}] + sanitizedCwd + sessionId + tasks，旧 nexusai-sessions 已删）")
    void taskOutputDir_producesCcFiveLayerRoot() {
        // WHY（方案B 意图，规则九）：CC 唯一 diskOutput 机制 getTaskOutputDir = join(getProjectTempDir(),
        //   getSessionId(), 'tasks')（diskOutput.ts:50-55）；getProjectTempDir = join(getClaudeTempDir(),
        //   sanitizePath(getOriginalCwd()))（filesystem.ts:376-378）。所有后台任务类型共用唯一根。
        //   Java taskOutputDir() 即唯一根锚点：MonitorMcpTaskRunner.defaultOutputFile 与
        //   RemoteTaskConfiguration.taskOutputDirSupplier 都收敛到它（旧 monitor flat 根 {tmpdir}/nexusai-tasks
        //   + remote 项目目录根已删）。本测试逐段锁死五层形态（temp + per-user + per-project + per-session + tasks），
        //   回归即变红。
        String sessionId = "sess-dir-b";
        RequestContext.setSession(sessionId);
        try {
            String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
            String dir = BackgroundTaskRunner.taskOutputDir();
            Path p = Path.of(dir);
            // 五层逐段：.../tasks(⑤) ← sessionId(④) ← sanitizedCwd(③) ← {appName}[-{uid}](②) ← tmpRoot(①)
            assertThat(p.getName(p.getNameCount() - 1).toString()).isEqualTo("tasks");
            assertThat(p.getName(p.getNameCount() - 2).toString()).isEqualTo(sessionId);
            assertThat(p.getName(p.getNameCount() - 4).toString())
                .as("per-user 层 = NexusaiPaths.getAppTempDirName()（Windows={appName} / Unix={appName}-{uid}，CC filesystem.ts:307-315 结构）")
                .isEqualTo(NexusaiPaths.getAppTempDirName());
            assertThat(dir).startsWith(Paths.get(tmpDir).toString());
            // 旧 A-7 简化根不得再产出（无兼容层/双轨，单轨五层）
            assertThat(dir).doesNotContain("nexusai-sessions");
            // 不含 taskId 文件名（目录 vs 文件语义分离，对齐 CC getTaskOutputDir 返回目录）
            assertThat(dir).doesNotEndWith(".output");
            // 与 taskOutputPath 的关系：taskOutputPath = taskOutputDir + <taskId>.output
            assertThat(BackgroundTaskRunner.taskOutputPath("tid-b"))
                .isEqualTo(Paths.get(BackgroundTaskRunner.taskOutputDir(), "tid-b.output").toString());
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("taskOutputPath sessionId 回退链：RequestContext → nexusai.sessionId sysprop → unknown")
    void taskOutputPath_sessionIdFallbackChain() {
        // WHY: CC getSessionId（bootstrap/state.ts:431-433）进程级 sessionId 恒非 null；Java 无单例会话，
        //   以 MDC（RequestContext）为主源，回退 sysprop（对齐 tasks.ts:209），再回退 "unknown"（fail-closed）。
        String taskId = "a12345678";

        // 主源：MDC 优先
        RequestContext.setSession("mdc-sess");
        assertThat(BackgroundTaskRunner.taskOutputPath(taskId))
            .isEqualTo(expectedFiveLayerPath("mdc-sess", taskId));

        // 回退 1：sysprop
        RequestContext.clear();
        System.setProperty("nexusai.sessionId", "sysprop-sess");
        assertThat(BackgroundTaskRunner.taskOutputPath(taskId))
            .isEqualTo(expectedFiveLayerPath("sysprop-sess", taskId));

        // 回退 2：unknown（fail-closed 占位）
        RequestContext.clear();
        System.clearProperty("nexusai.sessionId");
        assertThat(BackgroundTaskRunner.taskOutputPath(taskId))
            .isEqualTo(expectedFiveLayerPath("unknown", taskId));
    }

    @Test
    @DisplayName("per-user 层：NexusaiPaths.getAppTempDirName()（Windows={appName} / Unix={appName}-{uid}，CC filesystem.ts:307-315 结构）")
    void taskOutputDir_perUserLayerAppTempDirName() {
        // WHY（规则九）：CC getClaudeTempDirName（filesystem.ts:307-315）——Unix 多用户共享 /tmp 需
        //   {appName}-{uid} 防权限冲突与跨用户串读（:311-313）；Windows tmpdir 已 per-user（C:\Users\{user}
        //   \AppData\Local\Temp），CC 用 'claude' 即可（:305/308-310）。Java 等价 = NexusaiPaths
        //   getAppTempDirName() 平台分支（per-user 层品牌名动态 appName；uid 数字经 UnixSystem，不硬编码）。
        //   测试独立按 os.name 计算期望层（win 无 uid 段），断言 taskOutputDir 的 ② per-user 段一致。
        String sessionId = "sess-uid-b";
        RequestContext.setSession(sessionId);
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String expectedLayer = os.contains("windows")
                ? NexusaiPaths.getAppName()
                : NexusaiPaths.getAppTempDirName(); // = {appName}-{uid}（uid 经现取，不硬编码）
            String dir = BackgroundTaskRunner.taskOutputDir();
            String perUserSegment = Path.of(dir).getName(Path.of(dir).getNameCount() - 4).toString();
            assertThat(perUserSegment).as("② per-user 段必须与 NexusaiPaths 平台分支一致").isEqualTo(expectedLayer);
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("per-project 隔离：不同项目 originalCwd → 不同输出目录（CC filesystem.ts:376-378）")
    void taskOutputDir_perProjectIsolation() {
        // WHY（规则九）：CC getProjectTempDir = join(getClaudeTempDir(), sanitizePath(getOriginalCwd()))
        //   （filesystem.ts:376-378）——不同项目 originalCwd 落不同目录，同一用户多项目互不串扰。
        //   原 A-7 简化根 nexusai-sessions 无 per-project 层（同 sessionId 跨项目会碰撞）——这是要推翻的。
        String sessionA = "sess-proj-a";
        String sessionB = "sess-proj-b";
        SessionCwdHolder.setOriginalCwd(sessionA, "C:\\dev\\projectA");
        SessionCwdHolder.setOriginalCwd(sessionB, "C:\\dev\\projectB");
        try {
            RequestContext.setSession(sessionA);
            String dirA = BackgroundTaskRunner.taskOutputDir();
            RequestContext.setSession(sessionB);
            String dirB = BackgroundTaskRunner.taskOutputDir();
            assertThat(dirA).as("不同项目 originalCwd 必须产出不同输出目录").isNotEqualTo(dirB);
            assertThat(Path.of(dirA).getName(Path.of(dirA).getNameCount() - 3).toString())
                .as("③ per-project 段 = sanitizePath(originalCwd)").isEqualTo("C--dev-projectA");
            assertThat(Path.of(dirB).getName(Path.of(dirB).getNameCount() - 3).toString())
                .isEqualTo("C--dev-projectB");
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("sanitizePath 路径分隔符替换：originalCwd 的 \\ / : 等非字母数字 → '-'（CC sessionStoragePortable.ts:311-319）")
    void taskOutputDir_sanitizePathReplacesSeparators() {
        // WHY（规则九）：CC sanitizePath（sessionStoragePortable.ts:311-319）替换所有非字母数字字符
        //   （含 Windows 反斜杠/冒号）为 '-'，保证跨平台目录名安全（Windows 冒号保留字符会破坏路径）。
        //   Java 复验 AutoMemPaths.sanitizePath 镜像：C:\Users\dev\my project → C--Users-dev-my-project。
        String sessionId = "sess-sanitize-b";
        SessionCwdHolder.setOriginalCwd(sessionId, "C:\\Users\\dev\\my project");
        RequestContext.setSession(sessionId);
        try {
            String dir = BackgroundTaskRunner.taskOutputDir();
            String projectSegment = Path.of(dir).getName(Path.of(dir).getNameCount() - 3).toString();
            assertThat(projectSegment)
                .as("③ per-project 段不得含路径分隔符/冒号残留，替换为 '-'")
                .isEqualTo("C--Users-dev-my-project");
            assertThat(projectSegment).doesNotContain("\\").doesNotContain(":").doesNotContain("/");
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("registerAsyncAgent outputFile = taskOutputPath(agentId)（CC LocalAgentTask.tsx:488 → Task.ts:121）")
    void registerAsyncAgent_outputFileUsesHierarchicalPath() {
        // WHY: CC registerAsyncAgent createTaskStateBase → Task.ts:121 outputFile: getTaskOutputPath(id)，
        //   taskId===agentId 合一。旧 Java 硬编码 /tmp/agent-{taskId}.out 已删除（A-7 拍板）。
        String sessionId = "sess-async";
        RequestContext.setSession(sessionId);
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();

        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "异步任务", "prompt", "general-purpose", null, null);

        assertThat(task.outputFile())
            .as("async agent 任务 outputFile 必须为 CC 五层格式")
            .isEqualTo(BackgroundTaskRunner.taskOutputPath(taskId));
        assertThat(task.outputFile()).isEqualTo(expectedFiveLayerPath(sessionId, taskId));
        assertThat(task.outputFile()).doesNotContain("/tmp/agent-");
    }

    @Test
    @DisplayName("registerAgentForeground outputFile = taskOutputPath(agentId)（CC LocalAgentTask.tsx:553 → Task.ts:121）")
    void registerAgentForeground_outputFileUsesHierarchicalPath() {
        // WHY: CC registerAgentForeground createTaskStateBase → Task.ts:121 outputFile: getTaskOutputPath(id)，
        //   taskId===agentId 合一。旧 Java 硬编码 /tmp/agent-{taskId}.out 已删除（A-7 拍板）。
        String sessionId = "sess-fg";
        RequestContext.setSession(sessionId);
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();

        BackgroundTask task = runner.registerAgentForeground(
            agentId, "前台任务", "prompt", "general-purpose", null);

        assertThat(task.outputFile())
            .as("前台 agent 任务 outputFile 必须为 CC 五层格式")
            .isEqualTo(BackgroundTaskRunner.taskOutputPath(taskId));
        assertThat(task.outputFile()).isEqualTo(expectedFiveLayerPath(sessionId, taskId));
        assertThat(task.outputFile()).doesNotContain("/tmp/agent-");
    }

    @Test
    @DisplayName("completeAsyncAgent 写入 summary 到五层 outputFile（父目录自动创建，CC ensureOutputDir）")
    void completeAsyncAgent_writesSummaryToHierarchicalOutputFile() throws Exception {
        // WHY: CC ensureOutputDir mkdir recursive（diskOutput.ts:65-67）——五层格式父目录
        //   .../{sessionId}/tasks 不再天然存在，Java appendToOutputFile 必须写前建父目录。
        //   否则 completeAsyncAgent 写 summary 时 ENOENT（旧平铺 /tmp 父目录恒在无需此步）。
        String sessionId = "sess-write-" + UUID.randomUUID().toString().substring(0, 8);
        RequestContext.setSession(sessionId);
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "写输出", "prompt", "general-purpose", null, null);

        runner.completeAsyncAgent(taskId,
            AsyncAgentResult.success("总结文本", 3, 120L, taskId, 42L, AgentUsage.EMPTY));

        Path outputPath = Path.of(task.outputFile());
        assertThat(outputPath).as("summary 必须已写入分层 outputFile").exists();
        String content = Files.readString(outputPath);
        assertThat(content).contains("总结文本");
        // 父目录五层结构存在（.../{sessionId}/tasks）
        assertThat(outputPath.getParent()).isNotNull();
        assertThat(outputPath.getParent().getFileName().toString()).isEqualTo("tasks");
        // 清理临时产物
        Files.deleteIfExists(outputPath);
        Files.deleteIfExists(outputPath.getParent());
    }

    // ────────────────────────────── W-4b LOCAL_WORKFLOW 生命周期 ──────────────────────────────

    @Test
    @DisplayName("W-4b registerWorkflowTask 注册 LOCAL_WORKFLOW 任务（对齐 LocalWorkflowTask.tsx:53-83）")
    void registerWorkflowTask_registersLocalWorkflowTask() {
        BackgroundTask task = runner.registerWorkflowTask("w-test-1", "spec 工作流", "spec", null, null, null);

        assertThat(task.type()).isEqualTo(TaskType.LOCAL_WORKFLOW);
        assertThat(task.status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        assertThat(task.id()).isEqualTo("w-test-1");
        assertThat(task.isBackgrounded()).isTrue();
        // framework store 可查（对齐 CC framework.ts:104-116 task_started）
        assertThat(framework.getTask("w-test-1")).isPresent();
        // outputFile 五层 + <taskId>.output（Task.ts:121 getTaskOutputPath）
        assertThat(task.outputFile()).endsWith("w-test-1.output");
    }

    @Test
    @DisplayName("W-4b completeWorkflowTask 推进终态（对齐 LocalWorkflowTask.tsx:85-96 无条件覆盖）")
    void completeWorkflowTask_advancesToCompleted() {
        runner.registerWorkflowTask("w-test-2", "spec", "spec", null, null, null);
        runner.completeWorkflowTask("w-test-2");

        BackgroundTask completed = runner.getTask("w-test-2").orElseThrow();
        assertThat(completed.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(completed.notified()).isTrue();
        assertThat(completed.endTime()).isNotNull();
    }

    @Test
    @DisplayName("W-4b failWorkflowTask 推进失败态（对齐 LocalWorkflowTask.tsx:98-111）")
    void failWorkflowTask_advancesToFailed() {
        runner.registerWorkflowTask("w-test-3", "spec", "spec", null, null, null);
        runner.failWorkflowTask("w-test-3");

        BackgroundTask failed = runner.getTask("w-test-3").orElseThrow();
        assertThat(failed.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(failed.notified()).isTrue();
    }

    @Test
    @DisplayName("W-4b killWorkflowTask only-if-running 守卫 + abort 控制器（对齐 LocalWorkflowTask.tsx:117-132）")
    void killWorkflowTask_guardsRunningAndAbortsController() {
        AbortController abort = new AbortController();
        runner.registerWorkflowTask("w-test-4", "spec", "spec", abort, null, null);

        assertThat(runner.killWorkflowTask("w-test-4")).isTrue();
        BackgroundTask killed = runner.getTask("w-test-4").orElseThrow();
        assertThat(killed.status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(abort.isCancelled()).isTrue();

        // 幂等：非 running 再 kill → false（LocalWorkflowTask.tsx:122 only-if-running 守卫）
        assertThat(runner.killWorkflowTask("w-test-4")).isFalse();
        // 未知 taskId → false
        assertThat(runner.killWorkflowTask("w-nonexistent")).isFalse();
    }

    @Test
    @DisplayName("W-4b stopTask LOCAL_WORKFLOW 分发 kill（对齐 stopTask.ts:57-63 getTaskByType→LocalWorkflowTask.kill）")
    void stopTask_dispatchesLocalWorkflowToKill() {
        runner.registerWorkflowTask("w-test-5", "spec", "spec", null, null, null);

        BackgroundTaskRunner.StopTaskResult result = runner.stopTask("w-test-5");

        assertThat(result.errorCode()).isNull();
        BackgroundTask stopped = runner.getTask("w-test-5").orElseThrow();
        assertThat(stopped.status()).isEqualTo(BackgroundTaskStatus.KILLED);
    }

    // ════════════════════════════════════════════════════════════════
    // T1: size-watchdog kill 结果 → 通知 summary 并入 kill 消息（sizeWatchdogKillNote）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1: sizeWatchdogKillNote — size-kill 结果 → kill 消息；正常/其他失败 → null（不误并文本）")
    void sizeWatchdogKillNote_detectsKilledResult_andNullForNormal() {
        // WHY（规则九 · T1）: LocalBashTaskRunner 杀进程后 BashResult 携带 exitCode=137 + stderr 前缀
        //   kill 消息（对齐 CC prependStderr）。BackgroundTaskRunner.spawn 消费时把该消息并入完成通知
        //   summary（模型可见）。判定 = exitCode==137 && stderr 前缀命中；正常完成 / 其他失败 → null
        //   （不改变既有通知文本）。RED: 漏判 size-kill → 通知只见 "failed with exit code 137"，
        //   模型无法区分磁盘打满被杀（防护失效静默）；误并 → 正常通知被污染。
        LocalBashTaskRunner.BashResult killed = new LocalBashTaskRunner.BashResult(
            LocalBashTaskRunner.KILLED_FOR_SIZE_EXIT_CODE, "",
            LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE + " extra stderr");
        assertThat(BackgroundTaskRunner.sizeWatchdogKillNote(killed))
            .as("size-kill 结果 → 返回 kill 消息（并入 summary）")
            .isEqualTo(LocalBashTaskRunner.KILLED_FOR_SIZE_MESSAGE);

        LocalBashTaskRunner.BashResult normal = new LocalBashTaskRunner.BashResult(0, "out", "");
        assertThat(BackgroundTaskRunner.sizeWatchdogKillNote(normal))
            .as("正常完成 → null（不改变通知文本）").isNull();

        LocalBashTaskRunner.BashResult otherFail = new LocalBashTaskRunner.BashResult(1, "", "boom");
        assertThat(BackgroundTaskRunner.sizeWatchdogKillNote(otherFail))
            .as("非 size-kill 失败（exitCode=1）→ null（不误并消息）").isNull();

        // 同 137 但 stderr 前缀不命中（进程自身 SIGKILL，非 watchdog）→ null（判别收敛到 size-kill）
        LocalBashTaskRunner.BashResult otherSigkill = new LocalBashTaskRunner.BashResult(137, "", "killed by oom");
        assertThat(BackgroundTaskRunner.sizeWatchdogKillNote(otherSigkill))
            .as("exitCode=137 但前缀不命中 → null（不误判为 size-kill）").isNull();
    }
}

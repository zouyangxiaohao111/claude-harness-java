package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ── [S2 · F-09/F-20 2026-09-14] 夹具 DB 姿态显式声明 ──
    //   本夹具不接 DB 回源 ⇒ 未绑定 sessionId 属「确无会话」（还原本批前的 cwd 域行为）。
    //   ⛔ 不声明则 SessionProjectRoot.lookup 走「未接线 = 无法判定」⇒ CwdResolution fail-loud 抛。
    //   见 SessionProjectRootTestSupport 的类 javadoc。

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabaseForSessionProjectRoot() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    /** [批 3b-D7] workflow 任务的显式创建会话（旧实现由下游读 ambient 会话槽）。 */
    private static final String WF_SESSION = "sess-wf-3b-fixture";

    private final TaskFrameworkService framework = new TaskFrameworkService(null);
    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), framework);

    @AfterEach
    void tearDown() {
        // sysprop + SessionCwdHolder（originalCwd 登记）须清理，避免跨测试线程复用泄漏。
        // [批 3c] 原此处还清「裸 MDC 会话槽」——该槽已整类删除，语句删除。
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
        // [批 3c] 原此处 setSession 造 ambient 会话当「同源」装置；API 已显式传入 sessionId ⇒ 装置删除。
        String sessionId = "sess-r-b";
        String taskId = "a12345678";

        String path = BackgroundTaskRunner.taskOutputPath(sessionId, taskId);

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
        //   Java taskOutputDir(sessionId) 即唯一根锚点：MonitorMcpTaskRunner.defaultOutputFile 与
        //   RemoteTaskConfiguration.taskOutputDirSupplier 都收敛到它（旧 monitor flat 根 {tmpdir}/nexusai-tasks
        //   + remote 项目目录根已删）。本测试逐段锁死五层形态（temp + per-user + per-project + per-session + tasks），
        //   回归即变红。
        // [批 3c] 原此处 setSession + try/finally(clear) 造/清 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-dir-b";
        String tmpDir = System.getProperty("java.io.tmpdir", "/tmp");
        String dir = BackgroundTaskRunner.taskOutputDir(sessionId);
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
        assertThat(BackgroundTaskRunner.taskOutputPath(sessionId, "tid-b"))
            .isEqualTo(Paths.get(BackgroundTaskRunner.taskOutputDir(sessionId), "tid-b.output").toString());
    }

    @Test
    @DisplayName("[批 3b-D7] taskOutputPath 会话态只认显式入参；缺值 fail-loud（MDC/sysprop/unknown 三源已删）")
    void taskOutputPath_explicitSessionOnly_failsLoudWhenAbsent() {
        // WHY（规则十二 · 显式失败）：旧实现三源兜底 —— MDC（派生线程恒 null/残留别会话）→ sysprop
        //   （全局单值，多会话撞车）→ "unknown"（伪造会话 id）。CC 的等价物是 STATE 进程单例
        //   （bootstrap/state.ts:431-433），Java 多会话**没有**对应物 ⇒ 只能显式传参，缺值必须暴露。
        String taskId = "a12345678";

        assertThat(BackgroundTaskRunner.taskOutputPath("explicit-sess", taskId))
            .as("显式会话生效（五层镜像期望）")
            .isEqualTo(expectedFiveLayerPath("explicit-sess", taskId));

        // 反向对照：线程上有 sysprop 也不再被采用（旧实现会生效）
        // [批 3c] 原此处还写「别的会话」的裸 MDC 值当第一主源诱饵 —— 该槽已整类删除，装置删除。
        System.setProperty("nexusai.sessionId", "sysprop-should-not-win");
        try {
            assertThat(BackgroundTaskRunner.taskOutputPath("explicit-sess", taskId))
                .as("显式入参必须胜出；旧实现（sysprop 兜底）会得到 sysprop-should-not-win 的路径")
                .isEqualTo(expectedFiveLayerPath("explicit-sess", taskId));
            assertThat(BackgroundTaskRunner.taskOutputDir("explicit-sess"))
                .as("taskOutputDir 同理只认显式会话")
                .isEqualTo(Paths.get(expectedFiveLayerPath("explicit-sess", taskId)).getParent().toString());

            assertThatThrownBy(() -> BackgroundTaskRunner.taskOutputPath(null, taskId))
                .as("null 会话 → 抛（旧实现回落 sysprop/unknown）")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
            assertThatThrownBy(() -> BackgroundTaskRunner.taskOutputDir("   "))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            System.clearProperty("nexusai.sessionId");
        }
    }

    /**
     * [cwd3 步骤 2 · S2.5] {@code taskOutputDir} <b>保持 fail-loud，⛔ 不加哨兵</b>。
     *
     * <p><b>WHY（规则九 · 意图）</b>：任务产物目录在<b>注册期</b>就已写进任务记录。若会话在任务运行
     * 期间被删，收尾时按「无会话」回落 {@code user.dir} 会把产物写到<b>与注册期不同的目录</b>
     * （注册期是原 boundProject，收尾变成后端启动目录）⇒ 产物丢失/串目录。⇒ 该路径**有意**让
     * 「DB 明确答无此会话」继续抛，靠调用方修复（删任务 / 补会话），而非静默换目录。
     *
     * <p>RED（反向实验）：把 {@code taskOutputDir} 里的
     * {@code CwdResolution.getOriginalCwdLayer(sessionId)} 换成
     * {@code getOriginalCwdLayerForNonSession()}（或给 unknown 分支加哨兵/回落）⇒ 本用例红。
     */
    @Test
    @DisplayName("[cwd3 S2.5] taskOutputDir：DB 明确答无此会话 ⇒ 仍 fail-loud（⛔ 不加哨兵/不回落 user.dir）")
    void taskOutputDir_unknownSessionStaysFailLoud() {
        com.nexusai.common.SessionProjectRoot.setDbResolver(
            sid -> com.nexusai.common.SessionProjectRoot.Lookup.unknown());
        try {
            assertThatThrownBy(() -> BackgroundTaskRunner.taskOutputDir("sess-deleted-cwd3"))
                .as("会话已删 ⇒ 收尾时不得改写到别的目录（有意 fail-loud，防产物落到与注册期不同的目录）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-deleted-cwd3");
        } finally {
            SessionProjectRootTestSupport.declareNoDatabase();
        }
    }

    @Test
    @DisplayName("per-user 层：NexusaiPaths.getAppTempDirName()（Windows={appName} / Unix={appName}-{uid}，CC filesystem.ts:307-315 结构）")
    void taskOutputDir_perUserLayerAppTempDirName() {
        // WHY（规则九）：CC getClaudeTempDirName（filesystem.ts:307-315）——Unix 多用户共享 /tmp 需
        //   {appName}-{uid} 防权限冲突与跨用户串读（:311-313）；Windows tmpdir 已 per-user（C:\Users\{user}
        //   \AppData\Local\Temp），CC 用 'claude' 即可（:305/308-310）。Java 等价 = NexusaiPaths
        //   getAppTempDirName() 平台分支（per-user 层品牌名动态 appName；uid 数字经 UnixSystem，不硬编码）。
        //   测试独立按 os.name 计算期望层（win 无 uid 段），断言 taskOutputDir 的 ② per-user 段一致。
        // [批 3c] 原此处 setSession + try/finally(clear) 造/清 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-uid-b";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String expectedLayer = os.contains("windows")
            ? NexusaiPaths.getAppName()
            : NexusaiPaths.getAppTempDirName(); // = {appName}-{uid}（uid 经现取，不硬编码）
        String dir = BackgroundTaskRunner.taskOutputDir(sessionId);
        String perUserSegment = Path.of(dir).getName(Path.of(dir).getNameCount() - 4).toString();
        assertThat(perUserSegment).as("② per-user 段必须与 NexusaiPaths 平台分支一致").isEqualTo(expectedLayer);
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
        // [批 3c] 原此处两次 setSession 切换 ambient 会话；API 已按会话显式入参 ⇒ 装置删除。
        String dirA = BackgroundTaskRunner.taskOutputDir(sessionA);
        String dirB = BackgroundTaskRunner.taskOutputDir(sessionB);
        assertThat(dirA).as("不同项目 originalCwd 必须产出不同输出目录").isNotEqualTo(dirB);
        assertThat(Path.of(dirA).getName(Path.of(dirA).getNameCount() - 3).toString())
            .as("③ per-project 段 = sanitizePath(originalCwd)").isEqualTo("C--dev-projectA");
        assertThat(Path.of(dirB).getName(Path.of(dirB).getNameCount() - 3).toString())
            .isEqualTo("C--dev-projectB");
    }

    @Test
    @DisplayName("sanitizePath 路径分隔符替换：originalCwd 的 \\ / : 等非字母数字 → '-'（CC sessionStoragePortable.ts:311-319）")
    void taskOutputDir_sanitizePathReplacesSeparators() {
        // WHY（规则九）：CC sanitizePath（sessionStoragePortable.ts:311-319）替换所有非字母数字字符
        //   （含 Windows 反斜杠/冒号）为 '-'，保证跨平台目录名安全（Windows 冒号保留字符会破坏路径）。
        //   Java 复验 AutoMemPaths.sanitizePath 镜像：C:\Users\dev\my project → C--Users-dev-my-project。
        // [批 3c] 原此处 setSession + try/finally(clear) 造/清 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-sanitize-b";
        SessionCwdHolder.setOriginalCwd(sessionId, "C:\\Users\\dev\\my project");
        String dir = BackgroundTaskRunner.taskOutputDir(sessionId);
        String projectSegment = Path.of(dir).getName(Path.of(dir).getNameCount() - 3).toString();
        assertThat(projectSegment)
            .as("③ per-project 段不得含路径分隔符/冒号残留，替换为 '-'")
            .isEqualTo("C--Users-dev-my-project");
        assertThat(projectSegment).doesNotContain("\\").doesNotContain(":").doesNotContain("/");
    }

    @Test
    @DisplayName("registerAsyncAgent outputFile = taskOutputPath(sessionId, agentId)（CC LocalAgentTask.tsx:488 → Task.ts:121）")
    void registerAsyncAgent_outputFileUsesHierarchicalPath() {
        // WHY: CC registerAsyncAgent createTaskStateBase → Task.ts:121 outputFile: getTaskOutputPath(id)，
        //   taskId===agentId 合一。旧 Java 硬编码 /tmp/agent-{taskId}.out 已删除（A-7 拍板）。
        // [批 3c] 原此处 setSession 造 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-async";
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();

        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "异步任务", "prompt", "general-purpose", null, sessionId);

        assertThat(task.outputFile())
            .as("async agent 任务 outputFile 必须为 CC 五层格式")
            .isEqualTo(BackgroundTaskRunner.taskOutputPath(sessionId, taskId));
        assertThat(task.outputFile()).isEqualTo(expectedFiveLayerPath(sessionId, taskId));
        assertThat(task.outputFile()).doesNotContain("/tmp/agent-");
    }

    @Test
    @DisplayName("registerAgentForeground outputFile = taskOutputPath(sessionId, agentId)（CC LocalAgentTask.tsx:553 → Task.ts:121）")
    void registerAgentForeground_outputFileUsesHierarchicalPath() {
        // WHY: CC registerAgentForeground createTaskStateBase → Task.ts:121 outputFile: getTaskOutputPath(id)，
        //   taskId===agentId 合一。旧 Java 硬编码 /tmp/agent-{taskId}.out 已删除（A-7 拍板）。
        // [批 3c] 原此处 setSession 造 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-fg";
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();

        BackgroundTask task = runner.registerAgentForeground(
            agentId, "前台任务", "prompt", "general-purpose", sessionId);

        assertThat(task.outputFile())
            .as("前台 agent 任务 outputFile 必须为 CC 五层格式")
            .isEqualTo(BackgroundTaskRunner.taskOutputPath(sessionId, taskId));
        assertThat(task.outputFile()).isEqualTo(expectedFiveLayerPath(sessionId, taskId));
        assertThat(task.outputFile()).doesNotContain("/tmp/agent-");
    }

    @Test
    @DisplayName("completeAsyncAgent 写入 summary 到五层 outputFile（父目录自动创建，CC ensureOutputDir）")
    void completeAsyncAgent_writesSummaryToHierarchicalOutputFile() throws Exception {
        // WHY: CC ensureOutputDir mkdir recursive（diskOutput.ts:65-67）——五层格式父目录
        //   .../{sessionId}/tasks 不再天然存在，Java appendToOutputFile 必须写前建父目录。
        //   否则 completeAsyncAgent 写 summary 时 ENOENT（旧平铺 /tmp 父目录恒在无需此步）。
        // [批 3c] 原此处 setSession 造 ambient 会话；API 已显式传 sessionId ⇒ 装置删除。
        String sessionId = "sess-write-" + UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "写输出", "prompt", "general-purpose", null, sessionId);

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
        BackgroundTask task = runner.registerWorkflowTask("w-test-1", "spec 工作流", "spec", null, null, WF_SESSION);

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
        runner.registerWorkflowTask("w-test-2", "spec", "spec", null, null, WF_SESSION);
        runner.completeWorkflowTask("w-test-2");

        BackgroundTask completed = runner.getTask("w-test-2").orElseThrow();
        assertThat(completed.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(completed.notified()).isTrue();
        assertThat(completed.endTime()).isNotNull();
    }

    @Test
    @DisplayName("W-4b failWorkflowTask 推进失败态（对齐 LocalWorkflowTask.tsx:98-111）")
    void failWorkflowTask_advancesToFailed() {
        runner.registerWorkflowTask("w-test-3", "spec", "spec", null, null, WF_SESSION);
        runner.failWorkflowTask("w-test-3");

        BackgroundTask failed = runner.getTask("w-test-3").orElseThrow();
        assertThat(failed.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(failed.notified()).isTrue();
    }

    @Test
    @DisplayName("W-4b killWorkflowTask only-if-running 守卫 + abort 控制器（对齐 LocalWorkflowTask.tsx:117-132）")
    void killWorkflowTask_guardsRunningAndAbortsController() {
        AbortController abort = new AbortController();
        runner.registerWorkflowTask("w-test-4", "spec", "spec", abort, null, WF_SESSION);

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
        runner.registerWorkflowTask("w-test-5", "spec", "spec", null, null, WF_SESSION);

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

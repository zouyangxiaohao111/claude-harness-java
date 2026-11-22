package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 · teammate 终态逐出（对齐 CC evictAfter=now+30000）+ loop 表泄漏修复 + 生命周期固定顺序。
 *
 * <p>WHY：
 * <ul>
 *   <li>{@code InProcessTeammateTaskRegistry.evictTerminalTask} 改前<b>生产 0 调用</b> ⇒ loop 表
 *       只增不减（会话删除也只 abort 不删条目）；</li>
 *   <li>退役必须发生在 task 记录消失<b>之后</b>，否则 SendMessage 在「task 还在、名字已退役」的
 *       窗口里会误走 subagent resume 分支；</li>
 *   <li>顺序固定：task evict → registry remove → retire → release quota。</li>
 * </ul>
 */
@DisplayName("B1 · teammate 终态逐出（30s 窗口 / loop 泄漏 / 固定顺序）")
class InProcessTeammateTaskRegistryEvictionTest {

    @TempDir
    Path tempDir;

    private TaskFrameworkService framework;
    private AgentNameRegistry names;
    private SpawnInProcess spawner;
    private final List<String> spawnedTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        TaskSystemConfig.clearForTest();
        Files.createDirectories(tempDir.resolve("session-1"));
        // 夹具无 DB 回源：显式声明「本环境确无会话」（CwdResolution 走进程 user.dir）
        SessionProjectRootTestSupport.declareNoDatabase();
        framework = new TaskFrameworkService(new SdkEventQueue());
        names = new AgentNameRegistry();
        names.setSessionDirResolver(sessionId -> tempDir);
        spawner = new SpawnInProcess(framework);
        spawner.setAgentNameRegistry(names);
        spawner.setSessionDirResolver(sessionId -> tempDir);
    }

    @AfterEach
    void tearDown() {
        spawner.registry().cleanupSession("session-1");
        for (String taskId : spawnedTaskIds) {
            spawner.registry().kill(taskId);
        }
        SessionProjectRootTestSupport.clearNoDatabase();
        System.clearProperty("nexusai.task.config-dir");
        TaskSystemConfig.clearForTest();
    }

    private String spawn(String name, String team) {
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig(name, team, "do work", null, false, null, "general-purpose", tempDir.toString()),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-1"));
        assertThat(out.success()).as("前提：spawn 成功").isTrue();
        spawnedTaskIds.add(out.taskId());
        return out.taskId();
    }

    private static void awaitTrue(String what, long timeoutMs, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(cond.getAsBoolean()).as(what).isTrue();
    }

    @Test
    @DisplayName("终态窗口内不逐出、窗口后逐出并退役（task 记录消失 ⇒ 活跃名字清空）")
    void completed_evictsAfterWindow_andRetires() throws Exception {
        spawner.registry().setEvictAfterMs(250);
        String taskId = spawn("alice", "team-a");

        assertThat(spawner.registry().complete(taskId)).as("running → completed 必须推进").isTrue();

        // 窗口内：task 记录与 loop 都还在（SendMessage 仍应看到 agent-stopped 带 status）
        Thread.sleep(60);
        assertThat(framework.getTask(taskId)).as("窗口内 task 记录必须保留").isPresent();
        assertThat(spawner.registry().get(taskId)).as("窗口内 loop 条目必须保留").isPresent();
        assertThat(names.retiredEntryById("alice@team-a")).as("窗口内不得提前退役").isNull();

        // 以「退役已写入」为完成判据（退役是管线最后一步之一；单等 loop 删除会踩到
        //   task evict → registry remove → retire 之间的正常时间窗）
        awaitTrue("窗口后必须完成逐出管线（退役已写入）", 3_000,
            () -> names.retiredEntryById("alice@team-a") != null);
        assertThat(spawner.registry().get(taskId)).as("窗口后 loop 条目必须被逐出").isEmpty();
        assertThat(framework.getTask(taskId)).as("窗口后 task 记录必须被 evict").isEmpty();
        assertThat(names.retiredEntryById("alice@team-a"))
            .as("任务记录消失后才退役（否则误走 subagent resume）").isNotNull();
        assertThat(names.retiredEntryById("alice@team-a").terminalStatus()).isEqualTo("completed");
        assertThat(names.retiredEntryById("alice@team-a").transcriptPath())
            .as("退役条目必须带 transcriptPath").isNotNull();
        assertThat(names.resolveActiveEntry("session-1", "alice")).as("活跃表必须已清").isNull();
        assertThat(names.resolveEntry("session-1", "alice")).as("解析顺序：活跃 → 退役").isNotNull();
        assertThat(names.resolveEntry("session-1", "alice").agentId()).isEqualTo("alice@team-a");
    }

    @Test
    @DisplayName("killed 走同一条逐出管线：退役 status/stopReason = killed")
    void killed_usesSamePipeline() throws Exception {
        spawner.registry().setEvictAfterMs(0);
        String taskId = spawn("bob", "team-a");

        assertThat(spawner.registry().kill(taskId)).as("running → killed 必须推进").isTrue();

        awaitTrue("killed 后必须完成逐出管线（退役已写入）", 3_000,
            () -> names.retiredEntryById("bob@team-a") != null);
        assertThat(spawner.registry().get(taskId)).isEmpty();
        assertThat(framework.getTask(taskId)).isEmpty();
        assertThat(names.retiredEntryById("bob@team-a")).isNotNull();
        assertThat(names.retiredEntryById("bob@team-a").terminalStatus()).isEqualTo("killed");
        assertThat(names.retiredEntryById("bob@team-a").stopReason()).isEqualTo("killed");
    }

    @Test
    @DisplayName("生命周期固定顺序：task evict → registry remove → retire → release quota")
    void lifecycleOrder_isFixed() throws Exception {
        spawner.registry().setEvictAfterMs(0);
        String taskId = spawn("carol", "team-a");

        List<String> stages = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(4);
        spawner.registry().setLifecycleObserver((stage, id, agentId) -> {
            stages.add(stage.name());
            done.countDown();
        });
        // 配额释放探针：执行时必须已逐出 task/loop、且已退役（把顺序钉在释放这一刻）
        List<String> releaseSnapshot = new CopyOnWriteArrayList<>();
        spawner.registry().registerQuotaRelease(taskId, () -> {
            releaseSnapshot.add("taskGone=" + framework.getTask(taskId).isEmpty());
            releaseSnapshot.add("registryGone=" + spawner.registry().get(taskId).isEmpty());
            releaseSnapshot.add("retired=" + (names.retiredEntryById("carol@team-a") != null));
        });

        assertThat(spawner.registry().complete(taskId)).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).as("四个阶段都必须执行").isTrue();

        assertThat(stages).as("顺序必须固定").containsExactly(
            InProcessTeammateTaskRegistry.LifecycleStage.TASK_EVICTED.name(),
            InProcessTeammateTaskRegistry.LifecycleStage.REGISTRY_REMOVED.name(),
            InProcessTeammateTaskRegistry.LifecycleStage.RETIRED.name(),
            InProcessTeammateTaskRegistry.LifecycleStage.QUOTA_RELEASED.name());
        assertThat(releaseSnapshot).as("释放配额时 task/loop 已删、退役已写")
            .containsExactly("taskGone=true", "registryGone=true", "retired=true");
    }

    @Test
    @DisplayName("loop 表逐出后释放：多轮 spawn/终态后 registry.size 回落 0（修只增不减）")
    void loopMapIsReleased_noLeak() throws Exception {
        spawner.registry().setEvictAfterMs(0);
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            taskIds.add(spawn("worker" + i, "team-a"));
        }
        assertThat(spawner.registry().size()).as("20 个 teammate 均在表内").isEqualTo(20);

        for (String taskId : taskIds) {
            spawner.registry().complete(taskId);
        }

        awaitTrue("全部终态后必须完成逐出管线（20 条进退役表）", 5_000,
            () -> names.retiredSize() == 20);
        assertThat(spawner.registry().size()).as("loop 表必须清空（旧实现只增不减）").isZero();
        assertThat(names.retiredSize()).as("20 条全部进退役表").isEqualTo(20);
    }

    @Test
    @DisplayName("逐出窗口常量 = 30_000ms（CC ML=30000）")
    void defaultEvictWindowIs30s() {
        assertThat(InProcessTeammateTaskRegistry.EVICT_AFTER_MS).isEqualTo(30_000L);
    }
}

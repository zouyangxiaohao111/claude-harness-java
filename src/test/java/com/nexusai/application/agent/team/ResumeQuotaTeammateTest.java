package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.ResumeQuota;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 · teammate spawn/resume 的会话级并发槽（CC {@code roomForResume} / {@code $xt()} / {@code Tr()}
 * / 启动门 {@code subagent_concurrency_cap}）。
 *
 * <p>锁住两条：
 * <ul>
 *   <li>c：live 数达到上限 ⇒ spawn <b>直接拒</b>（原文 {@code Concurrent subagent limit reached...}），
 *       ⛔ 不排队、不起线程、不注册名字、不留任务；</li>
 *   <li>d：终态逐出释放且<b>只释放一次</b>（重复逐出不会把计数减成负数）。</li>
 * </ul>
 */
@DisplayName("B3 · teammate 并发槽（spawn 门 / 逐出释放一次）")
class ResumeQuotaTeammateTest {

    @TempDir
    Path tempDir;

    private TaskFrameworkService framework;
    private AgentNameRegistry names;
    private SpawnInProcess spawner;
    private ResumeQuota quota;
    private final List<String> spawnedTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        TaskSystemConfig.clearForTest();
        Files.createDirectories(tempDir.resolve("session-1"));
        SessionProjectRootTestSupport.declareNoDatabase();
        framework = new TaskFrameworkService(new SdkEventQueue());
        names = new AgentNameRegistry();
        names.setSessionDirResolver(sessionId -> tempDir);
        quota = new ResumeQuota(1);
        spawner = new SpawnInProcess(framework);
        spawner.setAgentNameRegistry(names);
        spawner.setSessionDirResolver(sessionId -> tempDir);
        spawner.setResumeQuota(quota);
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

    private SpawnInProcess.InProcessSpawnOutput spawn(String name) {
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig(name, "team-q", "do work", null, false, null,
                "general-purpose", tempDir.toString()),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-q"));
        if (out.success()) {
            spawnedTaskIds.add(out.taskId());
        }
        return out;
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
    @DisplayName("上限解析 · env > 远端配置（>20 且 <=1000 才生效，取 min(cfg,64)）> 缺省 20")
    void resolveMax_matchesCcClamp() {
        assertThat(ResumeQuota.resolveMax(null, null)).isEqualTo(20);
        assertThat(ResumeQuota.resolveMax("8", null)).as("env 优先").isEqualTo(8);
        assertThat(ResumeQuota.resolveMax("3", 100)).as("env 覆盖远端配置").isEqualTo(3);
        assertThat(ResumeQuota.resolveMax(null, 20)).as("不大于 20 ⇒ 忽略").isEqualTo(20);
        assertThat(ResumeQuota.resolveMax(null, 50)).isEqualTo(50);
        assertThat(ResumeQuota.resolveMax(null, 100)).as("min(cfg,64)").isEqualTo(64);
        assertThat(ResumeQuota.resolveMax(null, 1001)).as("超过 1000 ⇒ 忽略").isEqualTo(20);
    }

    @Test
    @DisplayName("c · 槽满（live=1/上限=1）⇒ spawn 拒绝、不排队、零副作用")
    void spawnBlockedAtLiveLimit() {
        SpawnInProcess.InProcessSpawnOutput first = spawn("alice");
        assertThat(first.success()).isTrue();
        assertThat(quota.inUse("session-1")).as("首个 spawn 必须占一个槽").isEqualTo(1);

        SpawnInProcess.InProcessSpawnOutput second = spawn("bob");

        assertThat(second.success()).as("槽满必须拒绝（不排队）").isFalse();
        assertThat(second.error())
            .as("CC subagent_concurrency_cap 原文（偏移 334098803）")
            .startsWith("Concurrent subagent limit reached. You can run 1 subagents at once. "
                + "Do not retry.");
        assertThat(quota.inUse("session-1")).as("拒绝不得额外占槽").isEqualTo(1);
        assertThat(framework.listAll()).as("拒绝不得残留 BackgroundTask").hasSize(1);
        assertThat(spawner.registry().size()).as("拒绝不得残留 loop/线程").isEqualTo(1);
        assertThat(names.resolveActiveEntry("session-1", "bob"))
            .as("拒绝不得注册名字").isNull();
    }

    @Test
    @DisplayName("d · 终态逐出释放且只释放一次（重复逐出不会把计数减成负数）")
    void releaseExactlyOnceAtEviction() throws Exception {
        spawner.registry().setEvictAfterMs(0);
        SpawnInProcess.InProcessSpawnOutput out = spawn("carol");
        assertThat(out.success()).isTrue();
        assertThat(quota.inUse("session-1")).isEqualTo(1);

        assertThat(spawner.registry().complete(out.taskId())).isTrue();
        awaitTrue("completed 经逐出管线后槽必须归还", 3_000,
            () -> quota.inUse("session-1") == 0);

        // 重复逐出（直调）不得二次释放
        spawner.registry().evictTerminalTask(out.taskId());
        assertThat(quota.inUse("session-1"))
            .as("重复逐出必须幂等（计数不得为负）").isZero();

        // killed 走同一条管线
        SpawnInProcess.InProcessSpawnOutput killed = spawn("dave");
        assertThat(killed.success()).isTrue();
        assertThat(quota.inUse("session-1")).isEqualTo(1);
        assertThat(spawner.registry().kill(killed.taskId())).isTrue();
        awaitTrue("killed 经逐出管线后槽必须归还", 3_000,
            () -> quota.inUse("session-1") == 0);
        spawner.registry().evictTerminalTask(killed.taskId());
        assertThat(quota.inUse("session-1")).isZero();
    }
}

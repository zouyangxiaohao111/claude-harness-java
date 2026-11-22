package com.nexusai.application.agent.team;

import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.AgentTranscript;
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
 * B1 · teammate meta sidecar 写入 + 缺失/不可读两档（CC dSa / cSa）。
 *
 * <p>WHY：{@code AgentTranscript.writeMetaExtras} 改前全仓 0 调用 ⇒ {@code taskKind} 永不落盘
 * ⇒ 逐出后 SendMessage 恒判 subagent（走 transcript resume 而不是 in-process 重拉）。
 * 本用例锁住：spawn 写三键；meta 缺失 = MISSING 档（按「不存在」），坏 JSON = UNREADABLE 档；
 * 两档都<b>不崩</b>，且名字解析仍走 registry（roster-first，不靠 meta 兜底）。
 */
@DisplayName("B1 · teammate meta sidecar（写入 / 缺失 / 不可读）")
class TeammateMetaSidecarTest {

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

    private SpawnInProcess.InProcessSpawnOutput spawn(String name, String team) {
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig(name, team, "do work", null, false, null,
                "general-purpose", tempDir.toString()),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-1"));
        spawnedTaskIds.add(out.taskId());
        return out;
    }

    private Path metaPath(String name, String team) {
        return tempDir.resolve("session-1").resolve("subagents")
            .resolve("agent-" + name + "@" + team + ".meta.json");
    }

    @Test
    @DisplayName("spawn 写 MetaExtras：taskKind=in_process_teammate + name + teamName")
    void spawn_writesTaskKindNameTeam() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("dave", "team-b");
        assertThat(out.success()).isTrue();

        AgentTranscript.MetaExtras extras = AgentTranscript.readMetaExtras(tempDir, "session-1",
            "dave@team-b");
        assertThat(extras.taskKind()).isEqualTo("in_process_teammate");
        assertThat(extras.teammate()).as("CC 仅认 taskKind 判定 teammate").isTrue();
        assertThat(extras.name()).isEqualTo("dave");
        assertThat(extras.teamName()).isEqualTo("team-b");
        assertThat(extras.agentType()).isEqualTo("general-purpose");
    }

    @Test
    @DisplayName("旧数据无 meta：metadataState=MISSING（dSa 档）、readMetaExtras 不崩、名字仍可解析")
    void metaMissing_isMissingTier_noCrash() throws Exception {
        SpawnInProcess.InProcessSpawnOutput out = spawn("erin", "team-b");
        assertThat(out.success()).isTrue();
        Path meta = metaPath("erin", "team-b");
        assertThat(Files.deleteIfExists(meta)).as("前提：meta 已写入").isTrue();

        assertThat(AgentTranscript.metadataState(tempDir, "session-1", "erin@team-b"))
            .as("无 meta ⇒ MISSING 档（CC dSa：never ran here / settings lost）")
            .isEqualTo(AgentTranscript.MetadataState.MISSING);
        AgentTranscript.MetaExtras extras = AgentTranscript.readMetaExtras(tempDir, "session-1",
            "erin@team-b");
        assertThat(extras.teammate()).as("无 meta 不得崩、不得伪造 teammate").isFalse();
        assertThat(extras.name()).isNull();

        // roster-first：名字解析不依赖 meta（CC Qt 先查 roster）
        assertThat(names.resolveActiveEntry("session-1", "erin")).isNotNull();
        assertThat(names.resolveEntry("session-1", "erin").agentId()).isEqualTo("erin@team-b");
    }

    @Test
    @DisplayName("坏 JSON：metadataState=UNREADABLE（cSa 档），readMetaExtras 不崩")
    void metaUnreadable_isUnreadableTier_noCrash() throws Exception {
        SpawnInProcess.InProcessSpawnOutput out = spawn("frank", "team-b");
        assertThat(out.success()).isTrue();
        Files.writeString(metaPath("frank", "team-b"), "{not-json");

        assertThat(AgentTranscript.metadataState(tempDir, "session-1", "frank@team-b"))
            .as("坏 JSON ⇒ UNREADABLE 档（CC cSa：could not be read just now）")
            .isEqualTo(AgentTranscript.MetadataState.UNREADABLE);
        AgentTranscript.MetaExtras extras = AgentTranscript.readMetaExtras(tempDir, "session-1",
            "frank@team-b");
        assertThat(extras.teammate()).isFalse();
        assertThat(extras.taskKind()).isNull();
    }

    @Test
    @DisplayName("会话目录不存在 ⇒ 不写 meta、不凭空建目录、spawn 照常成功")
    void spawn_unknownSessionDir_skipsMetaWithoutCreatingDirs() {
        // 解析器指向一个没有 <sessionId> 子目录的根 ⇒ meta 安全阀必须跳过（不在用户目录外建文件）
        Path missingRoot = tempDir.resolve("missing-root");
        spawner.setSessionDirResolver(sessionId -> missingRoot);
        SpawnInProcess.InProcessSpawnOutput out = spawn("grace", "team-b");
        assertThat(out.success()).as("meta 写不了不得阻断 spawn").isTrue();
        assertThat(missingRoot.resolve("session-1")).as("不得凭空创建会话目录").doesNotExist();
        assertThat(AgentTranscript.metadataState(missingRoot, "session-1", "grace@team-b"))
            .isEqualTo(AgentTranscript.MetadataState.MISSING);
    }
}

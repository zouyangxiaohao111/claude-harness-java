package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 · teammate 身份注册（SpawnInProcess → AgentNameRegistry，owner = Lead 会话）。
 *
 * <p>WHY：改前 {@code SpawnInProcess} 从不 register ⇒ teammate 名在注册表里不存在、
 * 终态也不进退役表 ⇒ SendMessage 的「已停止/已逐出」分支对 teammate 恒不可达
 * （"名字不可寻址"）。本用例锁住：
 * <ul>
 *   <li>spawn 后 {@code resolveEntry(lead, name)} 命中且 agentId = name@team；</li>
 *   <li>owner 维度 = Lead 会话（别的会话解析不到）；</li>
 *   <li>同名（活子代理 / 活 teammate）⇒ 按 CC 自动改名 name-2…，先注册者保号；</li>
 *   <li>保留名 / agent id 形 / session id 形 ⇒ spawn 失败且无残留任务（CC 双重拒绝）。</li>
 * </ul>
 */
@DisplayName("B1 · teammate 身份注册（owner=Lead 会话）")
class TeammateLifecycleRegistrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        // 真实会话目录形态：<sessionDir>/<sessionId>（meta 写入的安全阀要求它存在）
        Files.createDirectories(tempDir.resolve("session-1"));
        // 夹具无 DB 回源：显式声明「本环境确无会话」（CwdResolution 走进程 user.dir）
        SessionProjectRootTestSupport.declareNoDatabase();
        framework = new TaskFrameworkService(new SdkEventQueue());
        names = new AgentNameRegistry();
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
        if (out.taskId() != null && out.success()) {
            spawnedTaskIds.add(out.taskId());
        }
        return out;
    }

    @Test
    @DisplayName("spawn 后按 Lead 会话注册：resolveEntry(lead, name)=name@team，别的会话解析不到")
    void spawn_registersOwnerLeadName() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("alice", "team-a");

        assertThat(out.success()).as("spawn 必须成功").isTrue();
        AgentNameRegistry.AgentNameEntry active = names.resolveActiveEntry("session-1", "alice");
        assertThat(active).as("spawn 必须注册进活跃表（改前从不 register）").isNotNull();
        assertThat(active.agentId()).as("agentId = name@team（spawnInProcess.ts:112 formatAgentId）")
            .isEqualTo("alice@team-a");
        assertThat(names.resolveEntry("session-1", "alice")).as("解析顺序：活跃表命中").isNotNull();
        assertThat(names.resolveEntry("session-1", "alice").agentId()).isEqualTo("alice@team-a");
        assertThat(names.resolveEntry("another-session", "alice"))
            .as("owner 维度 = Lead 会话（跨会话不得串台）").isNull();
    }

    @Test
    @DisplayName("同名活子代理在册 ⇒ spawn 成功并自动改名 alice-2（CC 不拒，@357747471）")
    void spawn_conflictWithLiveSubagent_usesAutoName() {
        String subagentId = "11111111-1111-1111-1111-111111111111";
        assertThat(names.register("session-1", "alice", subagentId).registered()).isTrue();

        SpawnInProcess.InProcessSpawnOutput out = spawn("alice", "team-a");

        // CC 原文：[spawnInProcessTeammate] name "X" already routes to live ...;
        //   registry entry uses "X" instead ⇒ spawn 不失败，注册表改用自动名。
        assertThat(out.success()).as("同名活代理在册不得阻断 spawn（CC 自动改名）").isTrue();
        assertThat(out.agentId()).as("自动名参与 agentId = 真名@team").isEqualTo("alice-2@team-a");
        assertThat(framework.listAll()).as("spawn 成功 ⇒ 留下 1 个 BackgroundTask").hasSize(1);
        assertThat(spawner.registry().size()).as("spawn 成功 ⇒ 留下 1 个 loop 条目").isEqualTo(1);
        assertThat(names.resolveActiveEntry("session-1", "alice").agentId())
            .as("⛔ 先注册者保号：原活子代理映射不得被覆盖").isEqualTo(subagentId);
        assertThat(names.resolveActiveEntry("session-1", "alice-2").agentId())
            .as("后者以 name-2 注册").isEqualTo("alice-2@team-a");
    }

    @Test
    @DisplayName("同名 teammate 仍在运行 ⇒ 第二次 spawn 成功并改名 bob-2（取第一个可用后缀）")
    void spawn_sameNameLiveTeammate_usesAutoName() {
        SpawnInProcess.InProcessSpawnOutput first = spawn("bob", "team-a");
        assertThat(first.success()).isTrue();

        SpawnInProcess.InProcessSpawnOutput second = spawn("bob", "team-a");

        assertThat(second.success()).as("CC 撞名不失败，改用自动名").isTrue();
        assertThat(second.agentId()).as("第一个可用后缀 = -2").isEqualTo("bob-2@team-a");
        assertThat(names.resolveActiveEntry("session-1", "bob").agentId())
            .as("先注册者（bob）保号").isEqualTo("bob@team-a");
        assertThat(framework.listAll()).as("两个 teammate 各有任务").hasSize(2);
    }

    @Test
    @DisplayName("保留名（team-lead / user）不可占用 ⇒ spawn 失败且无任务")
    void spawn_reservedName_rejected() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("team-lead", "team-a");

        assertThat(out.success()).as("保留名不得占用").isFalse();
        assertThat(out.error()).contains("cannot be registered");
        assertThat(framework.listAll()).isEmpty();
        assertThat(spawner.registry().size()).isZero();

        // CC 真实保留名含 user / system（任意拼写），且 ⛔ 不得靠自动改名绕过。
        SpawnInProcess.InProcessSpawnOutput user = spawn("User", "team-a");
        assertThat(user.success()).as("user（任意拼写）是保留名").isFalse();
        assertThat(user.error()).contains("cannot be registered");
        assertThat(framework.listAll()).isEmpty();
    }

    @Test
    @DisplayName("spawn 后 meta sidecar 落在 <sessionDir>/<session>/subagents/agent-<name@team>.meta.json")
    void spawn_writesMetaSidecarToSessionDir() throws Exception {
        SpawnInProcess.InProcessSpawnOutput out = spawn("carol", "team-a");
        assertThat(out.success()).isTrue();

        Path meta = tempDir.resolve("session-1").resolve("subagents")
            .resolve("agent-carol@team-a.meta.json");
        assertThat(meta).as("meta sidecar 必须落盘（否则重拉走 subagent 分支）").isRegularFile();
        JsonNode node = JSON.readTree(Files.readString(meta));
        assertThat(node.path("taskKind").asText()).isEqualTo("in_process_teammate");
        assertThat(node.path("name").asText()).isEqualTo("carol");
        assertThat(node.path("teamName").asText()).isEqualTo("team-a");
        assertThat(node.path("agentType").asText()).isEqualTo("general-purpose");
    }
}

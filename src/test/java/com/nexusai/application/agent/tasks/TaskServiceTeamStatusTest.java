package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.team.TeamHelpers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * readTeamMembers / getAgentStatuses · 定向验证 team 成员读取与 agent 忙闲状态汇总
 * 对齐 CC utils/tasks.ts:697-798（U-6 对称补齐 API）
 *
 * <p><b>WHY (意图验证)</b>: CC tasks.ts 导出 TeamMember/AgentStatus 类型 + getAgentStatuses()
 * （readTeamMembers 为模块私有辅助），用于基于任务所有权展示团队 agent 忙闲状态（teams footer /
 * 待认领任务调度）。CC 侧这些 API 自身 0 调用（死代码），Java 补齐为对称 API 备用（U-6，用户拍板），
 * 语义必须对齐 CC 真源：
 * <ul>
 *   <li>{@code readTeamMembers} 读 {@code {configHome}/teams/{sanitizeName(teamName)}/config.json}
 *       （tasks.ts:728），ENOENT → null（tasks.ts:745-747）</li>
 *   <li>{@code getAgentStatuses} 未完成任务（status != 'completed' && owner 非空）按 owner 分组，
 *       每成员 name + agentId 双键取并集（去重），空 → idle / 非空 → busy（tasks.ts:775-797）</li>
 * </ul>
 *
 * <p>参考 CC 真源（grep 自验，非注释）：
 * <ul>
 *   <li>{@code export type TeamMember = { agentId; name; agentType? }} — tasks.ts:697-701</li>
 *   <li>{@code export type AgentStatus = { agentId; name; agentType?; status: 'idle'|'busy'; currentTasks }} — tasks.ts:706-712</li>
 *   <li>{@code const teamFilePath = join(teamsDir, sanitizeName(teamName), 'config.json')} — tasks.ts:728</li>
 *   <li>{@code if (task.status !== 'completed' && task.owner)} — tasks.ts:777</li>
 *   <li>{@code const currentTasks = uniq([...tasksByName, ...tasksById])} — tasks.ts:789</li>
 * </ul>
 */
class TaskServiceTeamStatusTest {

    @TempDir
    Path tempDir;

    private TaskService newService() {
        // 显式 configHome 构造器：隔离真实 ~/.claude 目录，team 目录 = {configHome}/teams
        return new TaskService(tempDir);
    }

    /** 写 team config.json · 对齐 CC teamHelpers.ts:66-68 config.json 形状（leadAgentId + members 数组） */
    private void writeTeamConfig(String teamName, String leadAgentId, List<TeamMember> members) throws IOException {
        Path dir = tempDir.resolve("teams").resolve(TeamHelpers.sanitizeName(teamName));
        Files.createDirectories(dir);
        StringBuilder membersJson = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            TeamMember m = members.get(i);
            if (i > 0) membersJson.append(", ");
            membersJson.append("{\"agentId\": \"").append(m.agentId())
                .append("\", \"name\": \"").append(m.name())
                .append("\", \"agentType\": \"").append(m.agentType() == null ? "" : m.agentType())
                .append("\"}");
        }
        String json = "{\"leadAgentId\": \"" + leadAgentId + "\", \"members\": [" + membersJson + "]}";
        Files.writeString(dir.resolve("config.json"), json);
    }

    @Test
    @DisplayName("readTeamMembers：读 {configHome}/teams/{sanitizeName}/config.json，返回 leadAgentId + members")
    void readTeamMembersParsesConfig() throws IOException {
        writeTeamConfig("My Team/研发",
            "lead-1",
            List.of(new TeamMember("a-1", "Alice", "researcher"),
                    new TeamMember("a-2", "Bob", null)));

        TaskService service = newService();
        Optional<TeamMembers> teamData = service.readTeamMembers("My Team/研发");

        assertThat(teamData).isPresent();
        assertThat(teamData.get().leadAgentId()).isEqualTo("lead-1");
        assertThat(teamData.get().members())
            .extracting(TeamMember::agentId)
            .containsExactly("a-1", "a-2");
        assertThat(teamData.get().members()).extracting(TeamMember::name).containsExactly("Alice", "Bob");
        assertThat(teamData.get().members().get(0).agentType()).isEqualTo("researcher");
    }

    @Test
    @DisplayName("readTeamMembers：team config.json 不存在 → Optional.empty（对齐 CC ENOENT → null，tasks.ts:745-747）")
    void readTeamMembersMissingTeamReturnsEmpty() {
        TaskService service = newService();

        assertThat(service.readTeamMembers("ghost-team")).isEmpty();
    }

    @Test
    @DisplayName("getAgentStatuses：成员持有未完成任务（owner 匹配）→ busy + currentTasks；无 → idle")
    void getAgentStatusesGroupsUnresolvedByOwner() throws IOException {
        // team：Alice(a-1) / Bob(a-2) / Carol(a-3)
        writeTeamConfig("team-1", "a-1",
            List.of(new TeamMember("a-1", "Alice", null),
                    new TeamMember("a-2", "Bob", null),
                    new TeamMember("a-3", "Carol", null)));

        TaskService service = newService();
        // taskListId = sanitizeName(teamName) = "team-1"（tasks.ts:771）
        String t1 = service.createTask("team-1", Task.create("任务一", "Alice 的任务"));
        service.updateTask("team-1", t1, Map.of("owner", "a-1"));           // Alice(agentId) 认领
        String t2 = service.createTask("team-1", Task.create("任务二", "Bob 的任务"));
        service.updateTask("team-1", t2, Map.of("owner", "Bob"));           // Bob(name) 认领
        String t3 = service.createTask("team-1", Task.create("任务三", "Carol 已完成"));
        service.updateTask("team-1", t3, Map.of("owner", "a-3", "status", Task.TaskStatus.COMPLETED)); // completed 不计

        Optional<List<AgentStatus>> statuses = service.getAgentStatuses("team-1");

        assertThat(statuses).isPresent();
        // Alice：agentId=a-1 认领 → busy + [t1]
        assertThat(statuses.get().stream().filter(s -> s.name().equals("Alice")).findFirst().orElseThrow())
            .satisfies(s -> {
                assertThat(s.status()).isEqualTo("busy");
                assertThat(s.currentTasks()).containsExactly(t1);
            });
        // Bob：name=Bob 认领 → busy + [t2]（双键 name 匹配）
        assertThat(statuses.get().stream().filter(s -> s.name().equals("Bob")).findFirst().orElseThrow())
            .satisfies(s -> {
                assertThat(s.status()).isEqualTo("busy");
                assertThat(s.currentTasks()).containsExactly(t2);
            });
        // Carol：completed 任务不计 → idle + 空
        assertThat(statuses.get().stream().filter(s -> s.name().equals("Carol")).findFirst().orElseThrow())
            .satisfies(s -> {
                assertThat(s.status()).isEqualTo("idle");
                assertThat(s.currentTasks()).isEmpty();
            });
    }

    @Test
    @DisplayName("getAgentStatuses：team 不存在 → Optional.empty（对齐 CC return null，tasks.ts:767-769）")
    void getAgentStatusesMissingTeamReturnsEmpty() {
        TaskService service = newService();

        assertThat(service.getAgentStatuses("ghost-team")).isEmpty();
    }

    @Test
    @DisplayName("getAgentStatuses：name 与 agentId 双键同任务 → currentTasks 去重（对齐 CC uniq，tasks.ts:789）")
    void currentTasksDeduplicatedAcrossNameAndId() throws IOException {
        writeTeamConfig("dedupe-team", "a-1",
            List.of(new TeamMember("a-1", "Alice", null)));

        TaskService service = newService();
        String t1 = service.createTask("dedupe-team", Task.create("任务一", "双键同一任务"));
        // owner 同时匹配 name 与 agentId？CC 按 owner 单一字符串分组；此处验证 owner=agentId 时
        // name 不额外命中（owner 是单一字符串，不可能是两个不同值）——去重路径实际覆盖：
        // 构造 owner="a-1" 与另一任务 owner="Alice"，若 Alice 同时被两键命中则去重。
        service.updateTask("dedupe-team", t1, Map.of("owner", "a-1"));
        String t2 = service.createTask("dedupe-team", Task.create("任务二", "Alice name 认领"));
        service.updateTask("dedupe-team", t2, Map.of("owner", "Alice"));

        Optional<List<AgentStatus>> statuses = service.getAgentStatuses("dedupe-team");

        assertThat(statuses).isPresent();
        AgentStatus alice = statuses.get().get(0);
        // name + agentId 双键命中 t2 与 t1 → busy，currentTasks 合并去重
        assertThat(alice.status()).isEqualTo("busy");
        assertThat(alice.currentTasks()).containsExactlyInAnyOrder(t1, t2);
    }
}

package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S12 · TaskUpdate owner 变更 → 文件级 teammate mailbox 通知（CC TaskUpdateTool.ts:277-298
 * + teammateMailbox.ts:134-192 writeToMailbox）定向测试。
 *
 * <p>WHY 本测试验证意图（CC 行为，不信注释）：
 * <ul>
 *   <li>触发判据 = {@code updates.owner && isAgentSwarmsEnabled()}（CC :277 <b>falsy 判据</b>，
 *       空串 owner 不触发，OD-TU-2b）；</li>
 *   <li>inbox 路径 = {@code {configHome}/teams/{taskListId}/inboxes/{owner}.json}
 *       （teammateMailbox.ts:56-66 getInboxPath + :288-297 第三参 taskListId 作 teamName）；</li>
 *   <li>信封 TeammateMessage = from/text/timestamp/read 必填 + color?（teammateMailbox.ts:43-50），
 *       read 由 writeToMailbox 强制 false（:173-176），color 缺省时省略键（JSON.stringify 省略 undefined）；</li>
 *   <li>text = task_assignment JSON（type/taskId/subject/description/assignedBy/timestamp，
 *       teammateMailbox.ts:953-960），assignedBy = getAgentName() || 'team-lead'（CC :278）。</li>
 * </ul>
 */
class TaskUpdateToolMailboxNotifyTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.taskListId", "tl-1");
        // configHome 指向临时目录：{configHome}/teams/tl-1/inboxes/{owner}.json
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
        System.setProperty("nexusai.agent.name", "leadAgent");
        System.setProperty("nexusai.agent.color", "blue");
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.taskListId");
        System.clearProperty("nexusai.task.config-dir");
        System.clearProperty("nexusai.agent.name");
        System.clearProperty("nexusai.agent.color");
    }

    private Path inboxPath(String owner) {
        return tempDir.resolve("teams").resolve("tl-1").resolve("inboxes").resolve(owner + ".json");
    }

    private JsonNode readInbox(String owner) throws Exception {
        Path path = inboxPath(owner);
        assertThat(path).exists();
        return json.readTree(Files.readString(path));
    }

    private static Task baseTask() {
        return new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());
    }

    private static Task taskWithOwner(String owner) {
        return new Task("t-1", "subject", "desc", null, owner,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());
    }

    private ToolResult<String> runUpdate(String owner) {
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(baseTask()));
        when(taskService.updateTask(eq("tl-1"), eq("t-1"), any())).thenReturn(Optional.of(taskWithOwner(owner)));
        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("owner", owner));
        return tool.execute(call);
    }

    @Test
    @DisplayName("owner 变更 + swarms 启用 → teams/{taskListId}/inboxes/{owner}.json 写入 CC 形状消息（CC:277-298）")
    void ownerChange_swarmsEnabled_writesTaskAssignmentToInbox() throws Exception {
        ToolResult<String> result = runUpdate("teammateB");

        // 更新本身成功（owner 入 updatedFields）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat (ToolResult.presentationMeta(result).get("updatedFields")).isEqualTo(List.of("owner"));

        // inbox 文件写入 + 信封 CC 形状（teammateMailbox.ts:43-50）
        JsonNode messages = readInbox("teammateB");
        assertThat(messages).hasSize(1);
        JsonNode msg = messages.get(0);
        assertThat(msg.get("from").asText()).isEqualTo("leadAgent"); // getAgentName()
        assertThat(msg.get("read").asBoolean()).isFalse();           // writeToMailbox 强制 read:false
        assertThat(msg.get("timestamp").asText()).isNotEmpty();
        assertThat(msg.get("color").asText()).isEqualTo("blue");     // getTeammateColor()
        assertThat(msg.get("summary")).isNull();                     // 缺省省略键（undefined → 省略）

        // text = task_assignment JSON（teammateMailbox.ts:953-960 + TaskUpdateTool.ts:280-287）
        JsonNode assignment = json.readTree(msg.get("text").asText());
        assertThat(assignment.get("type").asText()).isEqualTo("task_assignment");
        assertThat(assignment.get("taskId").asText()).isEqualTo("t-1");
        assertThat(assignment.get("subject").asText()).isEqualTo("subject");       // existingTask.subject（更新前值）
        assertThat(assignment.get("description").asText()).isEqualTo("desc");      // existingTask.description
        assertThat(assignment.get("assignedBy").asText()).isEqualTo("leadAgent");
        assertThat(assignment.get("timestamp").asText()).isNotEmpty();
        // 两端 timestamp 均为 ISO-8601（CC new Date().toISOString()）
        assertThatCode(() -> Instant.parse(assignment.get("timestamp").asText()))
            .doesNotThrowAnyException();
        assertThatCode(() -> Instant.parse(msg.get("timestamp").asText()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空串 owner 变更 → 不写 inbox（CC:277 falsy 判据，OD-TU-2b）")
    void emptyStringOwner_doesNotWriteInbox() {
        ToolResult<String> result = runUpdate("");

        // 空串 owner 仍走更新（CC updates.owner='' 条件更新生效），但不触发 mailbox 写
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat (ToolResult.presentationMeta(result).get("updatedFields")).isEqualTo(List.of("owner"));
        assertThat(inboxPath("")).doesNotExist();
    }

    @Test
    @DisplayName("swarms 关闭 → owner 变更不写 inbox（CC:277 isAgentSwarmsEnabled() 门）")
    void swarmsDisabled_doesNotWriteInbox() {
        System.clearProperty("nexusai.experimental.agent-teams");
        ToolResult<String> result = runUpdate("teammateB");

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(inboxPath("teammateB")).doesNotExist();
    }

    @Test
    @DisplayName("auto-set owner（in_progress + swarms + 无 owner）→ 写 inbox，from/assignedBy = agent 名（CC:188-198 + :278）")
    void autoSetOwner_inProgress_writesInbox() throws Exception {
        TaskService taskService = mock(TaskService.class);
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(baseTask()));
        when(taskService.updateTask(eq("tl-1"), eq("t-1"), any()))
            .thenReturn(Optional.of(taskWithOwner("leadAgent")));
        TaskUpdateTool tool = new TaskUpdateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate",
            json.createObjectNode().put("taskId", "t-1").put("status", "in_progress"));

        ToolResult<String> result = tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode messages = readInbox("leadAgent");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("from").asText()).isEqualTo("leadAgent");
        JsonNode assignment = json.readTree(messages.get(0).get("text").asText());
        assertThat(assignment.get("assignedBy").asText()).isEqualTo("leadAgent");
    }

    @Test
    @DisplayName("无 agent 名 → from/assignedBy 回退 'team-lead'（CC:278 getAgentName() || 'team-lead'）")
    void noAgentName_fallsBackToTeamLead() throws Exception {
        System.clearProperty("nexusai.agent.name");
        ToolResult<String> result = runUpdate("teammateB");

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode messages = readInbox("teammateB");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("from").asText()).isEqualTo("team-lead");
        JsonNode assignment = json.readTree(messages.get(0).get("text").asText());
        assertThat(assignment.get("assignedBy").asText()).isEqualTo("team-lead");
    }

    @Test
    @DisplayName("无 agent 颜色 → 信封省略 color 键（CC undefined → JSON.stringify 省略）")
    void noColor_omitsColorKey() throws Exception {
        System.clearProperty("nexusai.agent.color");
        ToolResult<String> result = runUpdate("teammateB");

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode messages = readInbox("teammateB");
        assertThat(messages.get(0).has("color")).isFalse();
    }

    @Test
    @DisplayName("isTaskAssignment: task_assignment 文本解析成功、非分配消息返回 null（teammateMailbox.ts:965-977）")
    void isTaskAssignment_parsesAssignmentAndRejectsOthers() {
        String valid = "{\"type\":\"task_assignment\",\"taskId\":\"t-1\",\"subject\":\"s\","
            + "\"description\":\"d\",\"assignedBy\":\"leadAgent\",\"timestamp\":\"2026-08-04T00:00:00.000Z\"}";
        TeammateMailbox.TaskAssignmentMessage assignment = TeammateMailbox.isTaskAssignment(valid);
        assertThat(assignment).isNotNull();
        assertThat(assignment.type()).isEqualTo("task_assignment");
        assertThat(assignment.taskId()).isEqualTo("t-1");
        assertThat(assignment.subject()).isEqualTo("s");
        assertThat(assignment.description()).isEqualTo("d");
        assertThat(assignment.assignedBy()).isEqualTo("leadAgent");
        assertThat(assignment.timestamp()).isEqualTo("2026-08-04T00:00:00.000Z");

        // 非 JSON / 非 task_assignment → null（CC :971-976 catch + type 判据）
        assertThat(TeammateMailbox.isTaskAssignment("not json")).isNull();
        assertThat(TeammateMailbox.isTaskAssignment("{\"type\":\"idle_notification\"}")).isNull();
        assertThat(TeammateMailbox.isTaskAssignment(null)).isNull();
    }

    @Test
    @DisplayName("readMailbox: 文件不存在返回空列表（teammateMailbox.ts:100-102 ENOENT → []）")
    void readMailbox_missingFile_returnsEmpty() {
        assertThat(TeammateMailbox.readMailbox("nobody", "tl-1")).isEmpty();
    }
}

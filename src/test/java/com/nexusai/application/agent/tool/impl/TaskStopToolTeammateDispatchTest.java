package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-G3] TaskStopTool in_process_teammate 分发（映射 CC killInProcessTeammate，统一 stopTask 路径）。
 *
 * <p><b>WHY（规则九）</b>：CC TaskStopTool.ts 无独立 in-process registry 概念，纯委托
 * {@code stopTask(id, ...)}（TaskStopTool.ts:107-130）；in_process_teammate kill 经统一
 * {@code stopTask → getTaskByType('in_process_teammate').kill → killInProcessTeammate}
 * （stopTask.ts:57-65 + spawnInProcess.ts:227-328）。Java 原实现把分发放在 TaskStopTool 内的
 * SpawnInProcess.registry() 分支（⊕-2），与 CC 结构偏移——IMP-G3 把分发迁至
 * {@link BackgroundTaskRunner#stopTask(String)} 的 stopInProcessTeammateTask 回退
 * （teammate 任务注册在统一 store：InProcessTeammateTaskRegistry.registerTask → TaskFrameworkService），
 * TaskStopTool 只做纯委托。本测试验证迁址后 teammate 仍可被 TaskStop 停止且 kill 到达状态机。
 */
@DisplayName("[IMP-G3] TaskStopTool in_process_teammate 分发（经 BackgroundTaskRunner.stopTask 统一路径）")
class TaskStopToolTeammateDispatchTest {

    @TempDir
    Path tempDir;

    private TaskFrameworkService taskFrameworkService;
    private SpawnInProcess spawner;
    private TaskStopTool stopTool;
    private BackgroundTaskRunner runner;
    private com.nexusai.application.agent.subagent.StopMarkers stopMarkers;
    private final List<String> spawnedTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        java.nio.file.Files.createDirectories(tempDir.resolve("session-1"));
        taskFrameworkService = new TaskFrameworkService(new SdkEventQueue());
        spawner = new SpawnInProcess(taskFrameworkService);
        spawner.setSessionDirResolver(id -> tempDir);
        // 真实 BackgroundTaskRunner：teammate 任务注册在统一 store（frameworkService），
        // stopTask 经 stopInProcessTeammateTask 回退分发到 registry.kill（对齐 CC stopTask 统一路径）。
        runner = new BackgroundTaskRunner(
            new NotificationQueue(), taskFrameworkService, new SdkEventQueue());
        runner.setSpawnInProcess(spawner);
        stopMarkers = new com.nexusai.application.agent.subagent.StopMarkers();
        runner.setStopMarkers(stopMarkers);
        runner.setSessionDirResolver(id -> tempDir);
        stopTool = new TaskStopTool();
        ReflectionTestUtils.setField(stopTool, "backgroundTaskRunner", runner);
        ReflectionTestUtils.setField(stopTool, "stopMarkers", stopMarkers);
    }

    @AfterEach
    void tearDown() {
        for (String taskId : spawnedTaskIds) {
            spawner.registry().kill(taskId);
        }
        System.clearProperty("nexusai.task.config-dir");
        TaskSystemConfig.clearForTest();
    }

    private SpawnInProcess.InProcessSpawnOutput spawn(String name, String teamName, String prompt) {
        SpawnInProcess.InProcessSpawnOutput out = spawner.spawnInProcessTeammate(
            new SpawnInProcess.InProcessSpawnConfig(name, teamName, prompt, null, false, null),
            new SpawnInProcess.SpawnContext("session-1", "tool-use-1"));
        spawnedTaskIds.add(out.taskId());
        return out;
    }

    @Test
    @DisplayName("in_process_teammate taskId → BackgroundTaskRunner.stopTask 统一路径分发 registry.kill")
    void stop_inProcessTeammate_dispatchesToRegistryKill() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("worker", "t", "work");

        ToolResult<?> result = stopTool.execute(new ToolUseBlock(
            "call-1", "TaskStop",
            JsonNodeFactory.instance.objectNode()
                .put("task_id", out.taskId())));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("running teammate 应停止成功").isFalse();
        assertThat(result.data()).as("JSON 输出 task_type 必须为 in_process_teammate")
            .isInstanceOf(String.class)
            .satisfies(s -> {
                String json = (String) s;
                assertThat(json).contains("\"task_id\":\"" + out.taskId() + "\"");
                assertThat(json).contains("\"task_type\":\"in_process_teammate\"");
            });

        // kill 必须真实到达 AutonomousAgentLoop 状态机（abort + KILLED）
        Optional<AutonomousAgentLoop> loopOpt = spawner.registry().get(out.taskId());
        assertThat(loopOpt).as("spawn 后 loop 必须注册").isPresent();
        assertThat(loopOpt.get().isAborted()).as("stopTask 分发必须 abort 生命周期控制器（killInProcessTeammate）")
            .isTrue();
    }

    @Test
    @DisplayName("已 terminal teammate → 错误 'Task X is not running'（CC TaskStopTool.ts:82-88 validateInput errorCode 3）")
    void stop_alreadyStoppedTeammate_noOp() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("worker2", "t", "work");
        spawner.registry().kill(out.taskId());

        // kill 后任务仍留 store 3s（STOPPED_DISPLAY_MS），status=killed ≠ running → NOT_RUNNING。
        ToolResult<?> result = stopTool.execute(new ToolUseBlock(
            "call-2", "TaskStop",
            JsonNodeFactory.instance.objectNode()
                .put("task_id", out.taskId())));
        // CC TaskStopTool.ts:82-88 validateInput: status!=='running' → result:false errorCode 3
        // （Java 经 stopTask NOT_RUNNING → ToolResult.error，CC-aligned；旧 registry 分支 no-op 成功为 Java-only 偏离）
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("非 running 应报错（CC validateInput errorCode 3）").isTrue();
        assertThat(String.valueOf(result.data()))
            .as("已终止任务提示 is not running（CC TaskStopTool.ts:86 message）")
            .contains("is not running");
    }

    @Test
    @DisplayName("非 teammate taskId（store 无此任务）→ NOT_FOUND 错误（CC validateInput errorCode 1）")
    void stop_nonTeammate_returnsNotFound() {
        ToolResult<?> result = stopTool.execute(new ToolUseBlock(
            "call-3", "TaskStop",
            JsonNodeFactory.instance.objectNode()
                .put("task_id", "nonexistent-task")));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("非 teammate 任务 → not_found 错误").isTrue();
        assertThat(String.valueOf(result.data())).contains("No task found with ID");
    }

    @Test
    @DisplayName("b-1 · validateInput 能看见统一 store 里的 teammate 任务（不再误报 No task found）")
    void validateInput_findsStoreTask() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("worker3", "t", "work");

        com.nexusai.application.agent.tool.Tool.ValidationResult validation =
            stopTool.validateInput(JsonNodeFactory.instance.objectNode().put("task_id", out.taskId()),
                null);

        assertThat(validation.ok()).as("running teammate 必须通过 validateInput（CC appState.tasks 同一张表）")
            .isTrue();
        assertThat(validation.errorCode()).isNull();
        assertThat(runner.findTask(out.taskId())).as("统一读口必须命中 store 任务").isPresent();
    }

    @Test
    @DisplayName("b-2 · TaskStop 对 teammate 双写停止标记且幂等（内存 count=1、伴生文件存在、二次停不自增）")
    void stop_teammate_writesDualStopMarker_idempotent() {
        SpawnInProcess.InProcessSpawnOutput out = spawn("worker4", "t", "work");
        String agentId = "worker4@t";

        ToolResult<?> first = stopTool.execute(new ToolUseBlock(
            "call-4", "TaskStop",
            JsonNodeFactory.instance.objectNode().put("task_id", out.taskId())));
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).as("首次停止必须成功").isFalse();

        assertThat(stopMarkers.isStopped(agentId))
            .as("内存 stoppedByUser（CC Fat，偏移 334311557）必须写").isTrue();
        assertThat(stopMarkers.stopCount(agentId)).as("首次停止 userStopCount=1").isEqualTo(1);
        Path sidecar = tempDir.resolve("session-1").resolve("subagents")
            .resolve("agent-" + agentId + ".stopped-by-user");
        assertThat(sidecar).as("磁盘伴生文件 <agentId>.stopped-by-user 必须存在（偏移 325012255）")
            .isRegularFile();

        ToolResult<?> second = stopTool.execute(new ToolUseBlock(
            "call-5", "TaskStop",
            JsonNodeFactory.instance.objectNode().put("task_id", out.taskId())));
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).as("二次停返回 not running").isTrue();
        assertThat(stopMarkers.stopCount(agentId))
            .as("已停不自增（CC Fat: if(stopped) return）").isEqualTo(1);
    }
}

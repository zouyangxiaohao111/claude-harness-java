package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TaskStop 工具 · 对齐 CC TaskStopTool.ts（完整实现，非 stub）。
 *
 * <p>[IMP-G] G25②：删除「Task system not available」stub 回退分支（生产恒注入
 * {@link BackgroundTaskRunner}）；G25③：description/prompt/userFacingName 逐字对齐 CC +
 * errorCode 校验上移 {@link #validateInput} 层（缺失 → 1 / 未找到 → 1 / 非运行 → 3）。
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskStopTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskStopTool.class);
    private static final String NAME = "TaskStop";

    /** 后台任务取消 · 生产恒注入（G25② 删除 stub 回退） */
    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    @Override
    public String name() {
        return NAME;
    }

    /** 搜索提示 · 对齐 CC TaskStopTool.ts:41 searchHint。 */
    @Override
    public String searchHint() {
        return "kill a running background task";
    }

    @Override
    public String description() {
        // 对齐 CC TaskStopTool.ts:92-94 description()
        return "Stop a running background task by ID";
    }

    @Override
    public String prompt() {
        // 对齐 CC TaskStopTool.ts:95-97 prompt() → DESCRIPTION（prompt.ts:3-8）
        return " - Stops a running background task by its ID\n"
                + " - Takes a task_id parameter identifying the task to stop\n"
                + " - Returns a success or failure status\n"
                + " - Use this tool when you need to terminate a long-running task";
    }

    @Override
    public String userFacingName() {
        // 对齐 CC TaskStopTool.ts:46 userFacingName: () => (process.env.USER_TYPE === 'ant' ? '' : 'Stop Task')
        // 外部 build（非 ant）→ 'Stop Task'
        return "Stop Task";
    }

    /**
     * 工具别名 · 对齐 CC TaskStopTool.ts:44 {@code aliases: ['KillShell']}。
     *
     * <p>WHY（规则九，EV-G2-034）：KillShell 是废弃旧名，历史 transcript / SDK 以 KillShell 名
     * 反查 --resume 重放；Java 未 override aliases → 查表断链。ToolRegistry findToolByName
     * 按 name + alias 双路径（Tool.java:756 toolMatchesName）。
     */
    @Override
    public List<String> aliases() {
        return List.of("KillShell");
    }

    /**
     * 恒启用 · 对齐 CC TaskStopTool.ts（无 isEnabled override → buildTool 默认 true）。
     *
     * <p>CC TaskStopTool 恒启用，不受 isTodoV2Enabled 门控；Java 覆写 AbstractTaskTool 基类
     * （基类按 isTodoV2Enabled()）→ 恒启用，否则 V1 模式（默认）下 TaskStop 被误禁（△-12）。
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        // 对齐 CC TaskStopTool.ts:45 maxResultSizeChars: 100_000（旧实现 10_000 截断长输出，△-10）
        return 100_000L;
    }

    /**
     * 是否延迟执行 · 对齐 CC TaskStopTool.ts:53 shouldDefer: true（常量，与 input 无关）。
     * <p>反向修正：旧实现 return false 偏离 CC（AbstractTaskTool 基类本就 true，
     * 显式 override 保持文档化对齐）。
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        // 对齐 CC TaskStopTool.ts:54-56 isConcurrencySafe() → true（旧实现 false 调度并发误判，△-11）
        return true;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        // s13 P2 终极: 同时支持 task_id 和 shell_id (CC TaskStopTool.ts:17 KillShell alias)
        properties.set("task_id", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "The ID of the task to stop"));
        properties.set("shell_id", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "CC KillShell alias: shell_id is accepted as task_id"));

        schema.set("properties", properties);
        // 不强制 required, 因 shell_id 可作为 task_id 别名
        schema.put("additionalProperties", false);

        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        // 对齐 CC TaskStopTool.ts:22-34 outputSchema：{message, task_id, task_type, command?}
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("message", JSON.createObjectNode().put("type", "string"));
        properties.set("task_id", JSON.createObjectNode().put("type", "string"));
        properties.set("task_type", JSON.createObjectNode().put("type", "string"));
        properties.set("command", JSON.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        schema.put("additionalProperties", false);

        return schema;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("task_stop_allow"),
            null, false, null, List.of());
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC TaskStopTool.ts:57-59 toAutoClassifierInput: input.task_id ?? input.shell_id ?? ''
        if (input != null) {
            if (input.has("task_id") && !input.get("task_id").isNull()) {
                return input.get("task_id").asText();
            }
            if (input.has("shell_id") && !input.get("shell_id").isNull()) {
                return input.get("shell_id").asText();
            }
        }
        return "";
    }

    /**
     * 输入校验 · 对齐 CC TaskStopTool.ts:60-91 validateInput（G25③ errorCode 上移 validateInput 层）。
     *
     * <p>CC 顺序：id = task_id ?? shell_id（:62）；缺失 → errorCode 1（:63-69）；task 不存在 →
     * errorCode 1（:74-80）；task.status !== 'running' → errorCode 3（:82-88）。Java 经
     * {@link BackgroundTaskRunner#getTask} 查 runner 本地 store（CC appState.tasks 等价；
     * 前台已注销任务已从 store 移除 → 视为 not_found，与 CC unregisterAgentForeground 语义一致）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String taskId = input != null ? input.path("task_id").asText("") : "";
        if (taskId.isBlank()) {
            taskId = input != null ? input.path("shell_id").asText("") : "";
        }
        if (taskId.isBlank()) {
            // CC :63-69 Missing required parameter: task_id（errorCode 1）
            return ValidationResult.fail("1", "Missing required parameter: task_id");
        }
        if (backgroundTaskRunner != null) {
            java.util.Optional<com.nexusai.application.agent.tasks.BackgroundTask> task =
                    backgroundTaskRunner.getTask(taskId);
            if (task.isEmpty()) {
                // CC :74-80 No task found with ID: ${id}（errorCode 1）
                return ValidationResult.fail("1", "No task found with ID: " + taskId);
            }
            if (task.get().status() != com.nexusai.application.agent.tasks.BackgroundTaskStatus.RUNNING) {
                // CC :82-88 Task ${id} is not running (status: ${task.status})（errorCode 3）
                return ValidationResult.fail("3",
                        "Task " + taskId + " is not running (status: " + task.get().status().getStatusString() + ")");
            }
        }
        return ValidationResult.pass();
    }

    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input != null && input.has("task_id")) {
            return "TaskStop: #" + input.get("task_id").asText();
        }
        return null;
    }

    /**
     * 执行 TaskStop · 按 task.type() 分发 · 对齐 CC stopTask.ts:38-100 + TaskStopTool.ts:116-129.
     *
     * <p>OPD-TS-23（R1 孤儿进程修复）：委托 {@link BackgroundTaskRunner#stopTask(String)} 先查
     * task.type() 再分发——bash → cancel()（killProcess 杀子进程 + KILLED + 抑制通知 +
     * emitTerminatedSdk 'stopped'）；local_agent → killAsyncAgent（only-if-running 原子守卫）。
     * 旧实现无条件先 killAsyncAgent（:158），bash 任务标 KILLED 但子进程未杀 → 孤儿进程（R1）。
     *
     * <p>OPD-TS-25：monitor_mcp → {@link BackgroundTaskRunner#stopTask(String)} 回退分发
     * stopMonitorMcpTask（getTaskByType('monitor_mcp') → MonitorMcpTask.kill 等价，CC tasks.ts:37-39 +
     * stopTask.ts:57-65；MonitorMcpTaskRunner.stop() → 流式循环退出流转 killed + notified +
     * SDK 'stopped'）。monitor 任务注册于统一 store（registerTask 落 TaskFrameworkService）而非
     * BackgroundTaskRunner 本地地图 → stopTask 经 store 查 + MonitorMcpTaskRunner 分发。
     *
     * <p>返回 JSON 对齐 CC TaskStopTool.ts:122-129：{message, task_id, task_type, command}，
     * task_type 用实际类型串（CC task.type，非硬编码 "bash"）；command = bash command /
     * agent description（CC stopTask.ts:97）。错误映射 CC validateInput errorCode：
     * 缺参/not_found → errorCode 1；not_running → errorCode 3；unsupported_type → StopTaskError。
     */
    @Override
    public ToolResult execute(ToolUseBlock call) {
        // CC TaskStopTool.ts:112-115: 支持 task_id 或 shell_id (KillShell 兼容)
        String taskId = call.input().path("task_id").asText("");
        if (taskId.isBlank()) {
            taskId = call.input().path("shell_id").asText("");
        }
        if (taskId.isBlank()) {
            // CC TaskStopTool.ts:63-68 validateInput errorCode 1
            return ToolResult.error(call.id(), "Missing required parameter: task_id");
        }
        // [IMP-G3] 纯委托 stopTask（对齐 CC TaskStopTool.ts:107-130 call → stopTask 单点分发）。
        // in_process_teammate 分发已迁至 BackgroundTaskRunner.stopTask（stopInProcessTeammateTask，
        // 对齐 CC stopTask.ts:57-65 getTaskByType('in_process_teammate').kill →
        // spawnInProcess.ts:227-328 killInProcessTeammate）——本工具不再保留独立 registry 分支
        // （TR-G2-⊕-2 映射，删除 Java-only SpawnInProcess.registry 特殊路径）。
        // [IMP-G] G25②：删除「Task system not available」stub 回退（生产恒注入，直接调 stopTask）。
        // [HEAD/WF-4] 其余任务类型按 type 分发（bash/local_agent/dream/remote/monitor）→ stopTask()
        BackgroundTaskRunner.StopTaskResult result = backgroundTaskRunner.stopTask(taskId);
        if (!result.ok()) {
            if (result.errorCode() == BackgroundTaskRunner.StopTaskErrorCode.NOT_FOUND) {
                // CC TaskStopTool.ts:74-79 validateInput errorCode 1
                return ToolResult.error(call.id(), "No task found with ID: " + taskId);
            }
            if (result.errorCode() == BackgroundTaskRunner.StopTaskErrorCode.NOT_RUNNING) {
                // CC TaskStopTool.ts:82-87 validateInput errorCode 3
                return ToolResult.error(call.id(), "Task " + taskId + " is not running");
            }
            // CC stopTask.ts:57-63 StopTaskError('unsupported_type')
            return ToolResult.error(call.id(), "Unsupported task type: " + result.taskType());
        }

        // CC TaskStopTool.ts:122-129 输出 {message, task_id, task_type, command}
        String command = result.command() != null ? result.command() : "";
        ObjectNode out = JSON.createObjectNode();
        out.put("message", "Successfully stopped task: " + taskId + " (" + command + ")");
        out.put("task_id", result.taskId());
        out.put("task_type", result.taskType());
        out.put("command", command);
        log.info("TaskStopTool: stopped task {} type={}", taskId, result.taskType());
        return ToolResult.success(call.id(), out.toString());
    }
}

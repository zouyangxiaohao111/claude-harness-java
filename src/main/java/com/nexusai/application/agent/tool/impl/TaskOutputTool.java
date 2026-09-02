package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TaskOutput 工具 · 对齐 CC TaskOutputTool.tsx（完整实现，非 stub）。
 *
 * <p>[IMP-G] G25②：删除「Task system not available」stub 回退分支（生产恒注入
 * {@link BackgroundTaskRunner}）；G25③：description/prompt/userFacingName 逐字对齐 CC +
 * errorCode 校验上移 {@link #validateInput} 层（缺失 task_id → 1 / 未找到 → 2）。
 */
@Component // s12-3.2: Spring bean 自动注册到 ToolRegistry
public class TaskOutputTool extends AbstractTaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskOutputTool.class);
    private static final String NAME = "TaskOutput";

    /** 后台任务输出读取 · 生产恒注入（G25② 删除 stub 回退） */
    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    @Override
    public String name() {
        return NAME;
    }

    /** 搜索提示 · 对齐 CC TaskOutputTool.tsx:146 searchHint。 */
    @Override
    public String searchHint() {
        return "read output/logs from a background task";
    }

    @Override
    public String description() {
        // 对齐 CC TaskOutputTool.tsx:157-159 description()
        return "[Deprecated] — prefer Read on the task output file path";
    }

    @Override
    public String prompt() {
        // 对齐 CC TaskOutputTool.tsx:172-182 prompt()（逐字）
        return "DEPRECATED: Prefer using the Read tool on the task's output file path instead. "
                + "Background tasks return their output file path in the tool result, and you receive "
                + "a <task-notification> with the same path when the task completes — Read that file directly.\n"
                + "\n"
                + "- Retrieves output from a running or completed task (background shell, agent, or remote session)\n"
                + "- Takes a task_id parameter identifying the task\n"
                + "- Returns the task output along with status information\n"
                + "- Use block=true (default) to wait for task completion\n"
                + "- Use block=false for non-blocking check of current status\n"
                + "- Task IDs can be found using the /tasks command\n"
                + "- Works with all task types: background shells, async agents, and remote sessions";
    }

    @Override
    public String userFacingName() {
        // 对齐 CC TaskOutputTool.tsx:151-153 userFacingName() → 'Task Output'
        return "Task Output";
    }

    /**
     * 工具别名 · 对齐 CC TaskOutputTool.tsx:150 {@code aliases: ['AgentOutputTool', 'BashOutputTool']}。
     *
     * <p>WHY（规则九，EV-G2-021）：历史 transcript / SDK 以 AgentOutputTool / BashOutputTool
     * 名反查 --resume 重放，Java 未 override aliases → 查表断链。ToolRegistry findToolByName
     * 按 name + alias 双路径（Tool.java:756 toolMatchesName）。
     */
    @Override
    public List<String> aliases() {
        return List.of("AgentOutputTool", "BashOutputTool");
    }

    /**
     * 恒启用 · 对齐 CC TaskOutputTool.tsx:163-165 {@code isEnabled() { return "external" !== 'ant' }}。
     *
     * <p>CC 生产态（external）恒 true，不受 isTodoV2Enabled 门控；Java 覆写 AbstractTaskTool
     * 基类（基类按 isTodoV2Enabled()）→ 恒启用，否则 V1 模式（默认）下 TaskOutput 被误禁（△-2）。
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 并发安全 · 对齐 CC TaskOutputTool.tsx:160-162 {@code isConcurrencySafe(_input) { return this.isReadOnly?.(_input) ?? false }}。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return isReadOnly(input);
    }

    /** 是否延迟执行 · 对齐 CC TaskOutputTool.tsx:148 shouldDefer: true（与 AbstractTaskTool 基类同值，显式对齐）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * 输入校验 · 对齐 CC TaskOutputTool.tsx:183-207 validateInput（G25③ errorCode 上移 validateInput 层）。
     *
     * <p>CC 顺序：task_id 缺失 → errorCode 1 'Task ID is required'（:188-194）；task 不存在 →
     * errorCode 2 'No task found with ID: ${task_id}'（:196-203）。Java 经
     * {@link BackgroundTaskRunner#getTask} 查 runner 本地 store（CC appState.tasks 等价）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        String taskId = input != null ? input.path("task_id").asText("") : "";
        if (taskId.isBlank()) {
            // CC :188-194 Task ID is required（errorCode 1）
            return ValidationResult.fail("1", "Task ID is required");
        }
        if (backgroundTaskRunner != null
                && backgroundTaskRunner.getTask(taskId).isEmpty()) {
            // CC :196-203 No task found with ID: ${task_id}（errorCode 2）
            return ValidationResult.fail("2", "No task found with ID: " + taskId);
        }
        return ValidationResult.pass();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("task_id", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "The ID of the task to retrieve output for"));
        // s13 P1-2b 修复: 对齐 CC TaskOutputTool.tsx:30-34（仅 task_id/block/timeout 三字段；
        // TR-G2-⊕-1 synchronous/wait 已删）
        properties.set("block", JSON.createObjectNode()
            .put("type", "boolean")
            .put("description", "Whether to wait for task completion")
            .put("default", true));
        properties.set("timeout", JSON.createObjectNode()
            .put("type", "number")
            .put("description", "Max wait time in ms (default 30000, max 600000)")
            .put("default", 30000));

        schema.set("properties", properties);
        schema.set("required", JSON.createArrayNode().add("task_id"));
        schema.put("additionalProperties", false);

        return schema;
    }

    /**
     * 输出 Schema · 对齐 CC TaskOutputTool.tsx:51-54 嵌套契约
     * {@code TaskOutputToolOutput = { retrieval_status, task }}。
     *
     * <p>TR-G2-⊕-3：旧 flat 7 字段 outputSchema（output/task_id/task_type/status/exit_code/error/
     * retrieval_status）重构为 CC 嵌套 shape——顶层 {retrieval_status, task}，task 为 TaskOutput
     * （task_id/task_type/status/description/output + 可选 exit_code/error/prompt/result）。
     * 消费方按 CC 契约解析 data.task.*（EV-G2-025 输出契约漂移 HIGH 修复）。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();
        properties.set("retrieval_status", JSON.createObjectNode()
            .put("type", "string")
            .put("description", "success | timeout | not_ready (CC TaskOutputTool.tsx:52)")
            .set("enum", JSON.createArrayNode()
                .add("success")
                .add("timeout")
                .add("not_ready")));

        // task: TaskOutput（CC TaskOutputTool.tsx:39-50；task 为 null 时仅 retrieval_status=timeout）
        ObjectNode task = JSON.createObjectNode();
        task.put("type", "object");
        ObjectNode taskProps = JSON.createObjectNode();
        taskProps.set("task_id", JSON.createObjectNode().put("type", "string"));
        taskProps.set("task_type", JSON.createObjectNode().put("type", "string"));
        taskProps.set("status", JSON.createObjectNode().put("type", "string"));
        taskProps.set("description", JSON.createObjectNode().put("type", "string"));
        taskProps.set("output", JSON.createObjectNode().put("type", "string"));
        // CC TaskOutput 类型字段名 camelCase：exitCode/error/prompt/result（TaskOutputTool.tsx:39-50）
        taskProps.set("exitCode", JSON.createObjectNode().put("type", "number"));
        taskProps.set("error", JSON.createObjectNode().put("type", "string"));
        taskProps.set("prompt", JSON.createObjectNode().put("type", "string"));
        taskProps.set("result", JSON.createObjectNode().put("type", "string"));
        task.set("properties", taskProps);
        properties.set("task", task);

        schema.set("properties", properties);
        schema.put("additionalProperties", false);

        return schema;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("task_output_allow"),
            null, false, null, List.of());
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        if (input != null && input.has("task_id")) {
            return input.get("task_id").asText();
        }
        return NAME;
    }

    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input != null && input.has("task_id")) {
            return "TaskOutput: #" + input.get("task_id").asText();
        }
        return null;
    }

    /** s13-P1-2: 默认 block=true, 对齐 CC TaskOutputTool.tsx:118-143 默认 block */
    public static final boolean DEFAULT_BLOCK = true;
    /** s13-P1-2: 默认 timeout 30s, 对齐 CC TaskOutputTool.tsx:127 */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    /** Phase 3: poll 间隔 100ms, 移到 BackgroundTaskRunner.getOutput (CC TaskOutputTool.tsx:120) */
    public static final long POLL_INTERVAL_MS = 100L;

    /**
     * s13-P1-2 终极实现: 执行 TaskOutput · 对齐 CC TaskOutputTool.tsx:208-282.
     *
     * <p>支持 block + timeout 参数:
     * <ul>
     *   <li>block=true: 异步轮询 (每 100ms 检查任务状态) 直到终态或 timeout</li>
     *   <li>block=false: 立即返回当前输出 (s12 行为)</li>
     *   <li>timeout: 最大等待时间, 默认 30s</li>
     * </ul>
     *
     * <p>输出对齐 CC 嵌套契约：{@link TaskOutputToolOutput} = {retrieval_status, task}，
     * data（LLM 可见 tool_result content）= {@link #mapToolResultToToolResultBlockParam} XML 渲染
     * （CC TaskOutputTool.tsx:283-308），structuredOutput = 嵌套 JSON（SDK/前端按 CC 契约解析
     * data.task.*，修复输出契约漂移 HIGH EV-G2-025）。
     */
    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        String taskId = call.input().path("task_id").asText("");
        if (taskId.isBlank()) {
            // 对齐 CC TaskOutputTool.tsx:188-193 validateInput errorCode 1
            return ToolResult.error(call.id(), "Task ID is required");
        }
        boolean block = call.input().path("block").asBoolean(DEFAULT_BLOCK);
        long timeoutMs = call.input().has("timeout")
            ? call.input().path("timeout").asLong(DEFAULT_TIMEOUT_MS)
            : DEFAULT_TIMEOUT_MS;

        // [IMP-G] G25②：删除「Task system not available」stub 回退（生产恒注入，直接调 getOutput）。
        // Phase 3: 委托 BackgroundTaskRunner.getOutput (CC TaskOutputTool.tsx:118-143)
        BackgroundTaskRunner.TaskOutput output = backgroundTaskRunner.getOutput(taskId, block, timeoutMs);
        if (!output.found()) {
            // 对齐 CC TaskOutputTool.tsx:215-217 call 抛 No task found with ID
            // （validateInput errorCode 2: No task found with ID: ${task_id}）
            log.info("TaskOutputTool: task {} 不存在 (not_found)", taskId);
            return ToolResult.error(call.id(), "No task found with ID: " + taskId);
        }

        // 构造 CC 嵌套输出（CC TaskOutputTool.tsx:51-54）：
        // retrieval_status: success（终态非 timeout）| timeout | not_ready（非阻塞查 running/pending）
        String retrievalStatus;
        if (output.timedOut()) {
            retrievalStatus = "timeout";
        } else if (output.status().isTerminal()) {
            retrievalStatus = "success";
        } else {
            retrievalStatus = "not_ready";
        }
        TaskOutputPayload payload = new TaskOutputPayload(
            output.taskId(), output.taskType(), output.description(),
            output.status().getStatusString(), output.content(), output.outputFile(),
            output.exitCode(), output.error(), output.prompt(), output.result());
        TaskOutputToolOutput structured = new TaskOutputToolOutput(retrievalStatus, payload);

        String rendered = mapToolResultToToolResultBlockParam(structured);
        log.info("TaskOutputTool: task {} retrieval_status={} status={}",
            taskId, retrievalStatus, output.status().getStatusString());
        return ToolResult.successWithStructuredOutput(call.id(), rendered, toStructuredOutput(structured));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 嵌套输出契约 · 对齐 CC TaskOutputTool.tsx:39-54
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC TaskOutputTool.tsx:51-54 TaskOutputToolOutput：{@code { retrieval_status, task }}。
     *
     * @param retrievalStatus CC original: retrieval_status ('success'|'timeout'|'not_ready')
     * @param task            CC original: task（TaskOutput 或 null——timeout 且无任务时 null）
     */
    public record TaskOutputToolOutput(String retrievalStatus, TaskOutputPayload task) {
    }

    /**
     * CC TaskOutputTool.tsx:39-50 TaskOutput：{@code { task_id, task_type, status, description,
     * output, exitCode?, error?, prompt?, result? }}。
     *
     * <p>[IMP-G] G25① 补字段跟踪：Java BackgroundTask 模型现跟踪 exitCode/error/prompt/result
     * （local_bash exit code / local_agent error+prompt+clean result，CC getTaskOutputData
     * TaskOutputTool.tsx:60-115 分类型 shaping）。可选字段 null → 省略（与 CC optional 语义
     * 一致——JSON.stringify 省略 undefined）。
     *
     * @param taskId      CC original: task_id
     * @param taskType    CC original: task_type（'local_bash'/'local_agent'/'in_process_teammate'...）
     * @param description CC original: description
     * @param status      CC original: status（'pending'/'running'/'completed'/'failed'/'killed'）
     * @param output      CC original: output（输出内容）
     * @param outputFile  Java 扩展：输出文件绝对路径（CC formatTaskOutput 截断头引用；非 CC 字段）
     * @param exitCode    CC original: exitCode（local_bash result.code，null 省略）
     * @param error       CC original: error（local_agent error，null 省略）
     * @param prompt      CC original: prompt（local_agent prompt / remote_agent command，null 省略）
     * @param result      CC original: result（local_agent clean final answer，null 省略）
     */
    public record TaskOutputPayload(String taskId, String taskType, String description,
                                    String status, String output, String outputFile,
                                    Integer exitCode, String error, String prompt, String result) {
    }

    /**
     * 渲染工具结果文本 · 对齐 CC TaskOutputTool.tsx:283-308 mapToolResultToToolResultBlockParam.
     *
     * <p>CC 真源（grep 实证，不信注释）：
     * <pre>
     * parts = ['<retrieval_status>' + data.retrieval_status + '</retrieval_status>']
     * if (data.task) {
     *   parts.push('<task_id>' + task.task_id + '</task_id>')
     *   parts.push('<task_type>' + task.task_type + '</task_type>')
     *   parts.push('<status>' + task.status + '</status>')
     *   if (task.exitCode != null) parts.push('<exit_code>' + task.exitCode + '</exit_code>')
     *   if (task.output?.trim()) parts.push('<output>\n' + formatTaskOutput(...).content.trimEnd() + '\n</output>')
     *   if (task.error) parts.push('<error>' + task.error + '</error>')
     * }
     * return parts.join('\n\n')
     * </pre>
     *
     * @param output 嵌套输出（retrieval_status + task）
     * @return CC mapper content 文本（LLM 可见 tool_result content）
     */
    static String mapToolResultToToolResultBlockParam(TaskOutputToolOutput output) {
        List<String> parts = new ArrayList<>();
        parts.add("<retrieval_status>" + output.retrievalStatus() + "</retrieval_status>");
        if (output.task() != null) {
            TaskOutputPayload task = output.task();
            parts.add("<task_id>" + task.taskId() + "</task_id>");
            parts.add("<task_type>" + task.taskType() + "</task_type>");
            parts.add("<status>" + task.status() + "</status>");
            // exit_code：CC 仅 exitCode != null 时输出（TaskOutputTool.tsx:290-292）
            if (task.exitCode() != null) {
                parts.add("<exit_code>" + task.exitCode() + "</exit_code>");
            }
            if (task.output() != null && !task.output().isBlank()) {
                String formatted = formatTaskOutput(task.output(), task.taskId(), task.outputFile());
                parts.add("<output>\n" + formatted.stripTrailing() + "\n</output>");
            }
            // error：CC 仅 task.error 存在时输出（TaskOutputTool.tsx:299-301）
            if (task.error() != null && !task.error().isBlank()) {
                parts.add("<error>" + task.error() + "</error>");
            }
        }
        return String.join("\n\n", parts);
    }

    /**
     * 结构化输出 Map · 对齐 CC TaskOutputTool.tsx:51-54（嵌套 {retrieval_status, task}，spread 字段）。
     *
     * @param output 嵌套输出
     * @return Map：{retrieval_status, task:{task_id, task_type, status, description, output}}
     */
    private static Map<String, Object> toStructuredOutput(TaskOutputToolOutput output) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("retrieval_status", output.retrievalStatus());
        if (output.task() != null) {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("task_id", output.task().taskId());
            task.put("task_type", output.task().taskType());
            task.put("status", output.task().status());
            task.put("description", output.task().description());
            task.put("output", output.task().output());
            // [IMP-G] G25① 对齐 CC TaskOutput 可选字段（camelCase；null 省略）
            if (output.task().exitCode() != null) {
                task.put("exitCode", output.task().exitCode());
            }
            if (output.task().error() != null) {
                task.put("error", output.task().error());
            }
            if (output.task().prompt() != null) {
                task.put("prompt", output.task().prompt());
            }
            if (output.task().result() != null) {
                task.put("result", output.task().result());
            }
            map.put("task", task);
        } else {
            map.put("task", null);
        }
        return map;
    }

    /**
     * 截断任务输出 · 对齐 CC utils/task/outputFormatting.ts formatTaskOutput。
     *
     * <p>CC 默认 {@code TASK_MAX_OUTPUT_LENGTH}=32_000（上限 160_000），超长截断时
     * 头注 {@code [Truncated. Full output: ${filePath}]} + 保留末尾 N 字符。Java 无
     * TASK_MAX_OUTPUT_LENGTH env → 固定默认 32_000；filePath 用 BackgroundTask.outputFile
     * （真实输出路径）。
     *
     * @param output     原始输出
     * @param taskId     任务 id
     * @param outputFile 输出文件绝对路径（可为 null）
     * @return 截断后内容（未超长则原样）
     */
    static String formatTaskOutput(String output, String taskId, String outputFile) {
        int maxLen = DEFAULT_MAX_OUTPUT_LENGTH;
        if (output.length() <= maxLen) {
            return output;
        }
        String filePath = outputFile != null
            ? outputFile : com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(taskId);
        String header = "[Truncated. Full output: " + filePath + "]\n\n";
        int available = maxLen - header.length();
        int start = Math.max(0, output.length() - available);
        return header + output.substring(start);
    }

    /** CC outputFormatting.ts:8 TASK_MAX_OUTPUT_DEFAULT = 32_000。 */
    static final int DEFAULT_MAX_OUTPUT_LENGTH = 32_000;

    /**
     * s13-P1-2b: 异步轮询已迁移到 {@link BackgroundTaskRunner#getOutput} (Phase 3).
     * TaskOutputTool.execute() 统一委托 runner.getOutput(taskId, blocking, timeoutMs),
     * 不再内联轮询逻辑.
     */
}

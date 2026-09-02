package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.MonitorMcpTaskRunner;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MonitorTool 真实现 · 对齐 CC {@code tools.ts:39-40, 237} 接线 glue + LocalShellTask.tsx
 * kind='monitor' 语义。
 *
 * <p><b>WHY（OPD-10 注册桩 → 生产接线）</b>: CC 中 {@code MonitorTool = feature('MONITOR_TOOL')
 * ? require(...).MonitorTool : null}（tools.ts:39-40），flag 关时工具为 {@code null}、不进入
 * getAllBaseTools 数组（tools.ts:237）。Java 端原为 fail-loud stub（execute 直接报"未实现"）。
 * 本类接入 {@link MonitorMcpTaskRunner}：execute 注册 MONITOR_MCP 任务 + 独立线程跑流式监控，
 * 使 MonitorTool 从 0 生产调用方 → 1。
 *
 * <p><b>门控语义</b>: {@link #isEnabled()} = {@code featureFlags.monitorTool()}
 * （{@code MONITOR_TOOL} flag · CC tools.ts:39），默认全关 → isEnabled()==false 不暴露。
 *
 * <p><b>DEC-1 显式冲突标注（规则七）</b>: CC MonitorTool 本体 TS 模块被 DCE 剔除，由 glue+语义
 * 推得输入为 {@code command}（任意 shell 脚本，流式重定向到 outputFile）；Java
 * {@link MonitorMcpTaskRunner#monitor} 固定轮询 McpServerService（running/error/stopped + 池大小），
 * 无任意 shell 执行。裁决：选 <b>Java 语义</b>（监控对象固定为 MCP server 状态），接口名 / 摘要
 * （{@code Monitor "desc" stream ended}，LocalShellTask.tsx:136）/ 生命周期语义逐字对齐 CC；
 * 不伪造 {@code command} 死参数（inputSchema 只暴露 description，DEC-2）。
 *
 * <p>CC 门控原名/行号: {@code MONITOR_TOOL} (Open-ClaudeCode/src/tools.ts:39-40)。
 * CC 注册点: Open-ClaudeCode/src/tools.ts:237。
 * CC monitor 摘要: Open-ClaudeCode/src/tasks/LocalShellTask/LocalShellTask.tsx:129-144。
 */
public class MonitorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MonitorTool.class);

    /** CC 工具名 · {@code tools.ts:39-40} MonitorTool（无源码，以类名/工具名为准）。 */
    public static final String NAME = ToolNameConstants.MONITOR_TOOL_NAME;

    private final FeatureFlags featureFlags;

    /** MONITOR_MCP 流式监控执行器（可为 null —— 测试直构无 bean 时 execute fail-loud）。 */
    private final MonitorMcpTaskRunner monitorMcpTaskRunner;

    public MonitorTool() {
        this(FeatureFlags.ALL_DISABLED, null);
    }

    /** 注册桩兼容构造器（保留既有 feature-gated 注册路径）。 */
    public MonitorTool(FeatureFlags featureFlags) {
        this(featureFlags, null);
    }

    /**
     * 生产构造器 · 对齐 CC MonitorTool feature('MONITOR_TOOL') 条件构建。
     *
     * @param featureFlags        MONITOR_TOOL 门控（{@code FeatureFlags.ALL_DISABLED} 时工具不暴露）
     * @param monitorMcpTaskRunner MCP 状态监控执行器（{@code @Component}；null 时 execute fail-loud）
     */
    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
        this.monitorMcpTaskRunner = monitorMcpTaskRunner;
    }

    @Override
    public String name() {
        if (log.isDebugEnabled()) {
            log.debug("MonitorTool.name(): 返回 CC 工具名 MONITOR_TOOL_NAME='Monitor'（对齐 tools.ts:39-40）");
        }
        return NAME;
    }

    @Override
    public String description() {
        return "Start a streaming monitor of MCP server status (pool size, running/error/stopped counts). " +
               "Each 2s observation is appended to an output file; read it with TaskOutput. " +
               "The stream ends when stopped (TaskStop) or the monitor exits.";
    }

    @Override
    public JsonNode inputSchema() {
        // DEC-2 · 对齐 CC MonitorTool 的 description 承载摘要（LocalShellTask.tsx:129 `Monitor "desc" ...`）。
        // Java monitor 固定监控 MCP server 状态 → 无 command 死参数。
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode desc = JsonNodeFactory.instance.objectNode();
        desc.put("type", "string");
        desc.put("description", "监控描述，用于状态摘要（CC: Monitor \"desc\" ...）。可省，缺省用 task id 占位。");
        properties.set("description", desc);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        boolean enabled = this.featureFlags.monitorTool();
        if (log.isDebugEnabled()) {
            log.debug("MonitorTool.isEnabled() = {}（MONITOR_TOOL 门控，CC tools.ts:39）", enabled);
        }
        return enabled;
    }

    /**
     * 权限表态 · 对齐 CC MonitorTool.tsx:90-93 {@code checkPermissions = bashToolHasPermission(...)}
     * 的结构（H-17「MonitorTool 无 checkPermissions → 权限绕过」修复）。
     *
     * <p><b>DEC-1 适配</b>: CC 对任意 shell {@code command} 复用 Bash 权限链（bashPermissions.ts）；
     * Java MonitorTool 按 DEC-1 拍板固定监控 MCP server 状态（{@link MonitorMcpTaskRunner} 轮询，
     * 无 command 输入）→ 无 bash 内容可检。返回显式 {@link PermissionResult.Allow}（decisionReason
     * 标注 MCP 只读轮询语义），补上该 override 使权限面显式、可审计（非基类「default allow」盲区）；
     * whole-tool deny/ask 规则仍由管线 1a/1b 层在 1c 之前先行裁决，不受本 override 影响。
     *
     * @return Allow（decisionReason=Other「Monitor MCP 状态轮询（DEC-1）只读」）
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("MonitorTool.checkPermissions: MCP 状态轮询（DEC-1 无 shell command），"
                    + "自动放行（对齐 CC MonitorTool.tsx:90-93 结构，Java 无 command 输入）");
        }
        return new PermissionResult.Allow(
                input,
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Other(
                        "Monitor MCP 状态轮询（DEC-1 无 shell command）只读监控自动放行"),
                null,
                false,
                null,
                java.util.List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 生产接线 execute · DEC-3：
     * <ol>
     *   <li>读 input.description（缺省 → 空串，monitor() 用 taskId 占位）</li>
     *   <li>{@code registerTask(description, call.id(), ctx.agentId())} — ctx.agentId() 为 subagent
     *       时 → {@code killMonitorMcpTasksForAgent} 可按 agent 归属命中（CC runAgent.ts:852-861）；
     *       main-thread ctx null / 不归属 → 不被 subagent 结束批量 kill</li>
     *   <li>独立线程跑 {@code monitor(taskId, description, null)}（monitor 是阻塞轮询循环，
     *       Thread.sleep，必须异步，design §1.1）</li>
     *   <li>返回 BashTool.executeBackground 同款模型契约：taskId + TaskOutput 读 outputFile</li>
     *   <li>错误处理：runner 未装配 → ToolResult.error（fail-loud）；{@link MonitorMcpTaskRunner#isMonitorAvailable()}
     *       为 false（McpServerService 无 bean）→ execute 在启动线程前同步返回 error（monitor 抛错在子线程，
     *       execute 无法捕获——前置校验保证不"假启动"）</li>
     * </ol>
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (monitorMcpTaskRunner == null) {
            log.warn("[MonitorTool] execute: MonitorMcpTaskRunner 未装配（MONITOR_TOOL 开但无 runner bean）call.id={}", call.id());
            return ToolResult.error(call.id(),
                "Monitor 工具不可用：MonitorMcpTaskRunner 未装配（MONITOR_TOOL 需 task 系统运行时）");
        }
        if (!monitorMcpTaskRunner.isMonitorAvailable()) {
            log.error("[MonitorTool] execute: McpServerService 未装配，无法监控 MCP 状态 call.id={}", call.id());
            return ToolResult.error(call.id(),
                "Monitor 工具不可用：McpServerService 未装配，无法轮询 MCP 服务器状态");
        }

        String description = call.input().path("description").asText("");
        // Phase 4 (cron-notify): 透传创建会话 sessionId（ctx.sessionId()）→ monitor 终态通知
        // 注入创建会话回合（对齐 CC monitor 终态通知注入当前循环，LocalShellTask.tsx:166-171）。
        String monitorSessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
        String taskId = monitorMcpTaskRunner.registerTask(
            description, call.id(), ctx != null ? ctx.agentId() : null, monitorSessionId);
        String outputFile = monitorMcpTaskRunner.outputFileFor(taskId);

        // monitor() 是阻塞轮询循环（2s/次），必须在独立线程运行；终态由 monitor() 内部
        // transitionTerminal（updateTaskState + SDK + notificationQueue priority=NEXT）闭环。
        Thread t = new Thread(() -> {
            try {
                monitorMcpTaskRunner.monitor(taskId, description, null);
            } catch (Throwable e) {
                // fail-loud：monitor() 已先 transitionTerminal(failed)（LocalShellTask.tsx:139
                // "script failed"），此处补日志防子线程静默死亡（不吞错误）。
                log.error("[MonitorTool] monitor 线程异常终止 taskId={}: {}", taskId, e.getMessage(), e);
            }
        }, "monitor-mcp-" + taskId);
        t.setDaemon(true);
        t.start();

        log.info("[MonitorTool] Monitor started: taskId={}, desc={}, outputFile={}（流式监控已接线，"
            + "TaskOutput 可读 outputFile，TaskStop 可终止）", taskId, description, outputFile);
        return ToolResult.success(call.id(),
            "Monitor started: " + taskId
            + "\nUse TaskOutput to read the stream from: " + outputFile);
    }
}

package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.Tool;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.mcp.dto.McpStatus;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * MCP 状态流式监控执行器 — 对齐 CC LocalShellTask.tsx kind='monitor' streaming-only 语义.
 *
 * <p>CC 真源 (Open-ClaudeCode/src/tasks/LocalShellTask/LocalShellTask.tsx)：
 * <ul>
 *   <li>:47 startStallWatchdog 对 monitor 是 noop（`if (kind === 'monitor') return () => {};`）—
 *       监控无"等待交互输入"告警，因为 monitor 是 streaming-only</li>
 *   <li>:129-144 monitor 摘要是流式语义：脚本退出 = stream ended 而非 condition met，
 *       `Monitor "${description}" stream ended` / `script failed` / `stopped`</li>
 * </ul>
 *
 * <p>任务生命周期（对齐 CC framework.ts:77-117 registerTask + updateTaskState +
 * sdkEventQueue.ts:114-134 emitTaskTerminatedSdk）：
 * <ul>
 *   <li>{@link #registerTask} — 生成 m 前缀 taskId（Task.ts:82 {@code monitor_mcp:'m'}）+ outputFile
 *       （对齐 CC getTaskOutputPath，diskOutput.ts:72-73 {@code <tmp>/tasks/<taskId>.output}），
 *       落 {@link TaskFrameworkService#registerTask}（status=running → SDK task_started）</li>
 *   <li>{@link #monitor} 退出时终态流转：completed（stream ended）/ failed（script failed）/
 *       killed（stopped，{@link #stop} 触发）→ updateTaskState（endTime + notified:true）
 *       + SDK task_notification（emitTaskTerminatedSdk）+ 通知队列 XML 入队
 *       （enqueuePendingNotification，monitor priority='next'，LocalShellTask.tsx:166-171）</li>
 * </ul>
 *
 * <p>本类职责：持续轮询 McpServerService.getCurrentTools / listAll，每次观测追加写
 * outputFile（对齐 CC 流式重定向，LocalBashTaskRunner.appendOutputLine 同款 append 模式）。
 * 流退出（stop() 中断 / 线程中断）= stream ended / stopped，非 condition met。
 */
@Component
public class MonitorMcpTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitorMcpTaskRunner.class);

    /** 状态轮询间隔 (ms) — 持续观察 MCP 状态变化 */
    private static final long POLL_INTERVAL_MS = 2_000L;

    @Autowired(required = false)
    private McpServerService mcpServerService;

    /** 统一任务存储 + SDK task_started 发射（可为 null —— 测试直构无 bean 时跳过）· OPD-TS-22 */
    @Autowired(required = false)
    private TaskFrameworkService taskFrameworkService;

    /** SDK 事件队列（可为 null —— 测试直构无 bean 时不发 task_terminated）· 对齐 CC emitTaskTerminatedSdk */
    @Autowired(required = false)
    private SdkEventQueue sdkEventQueue;

    /** 通知队列（可为 null —— 测试直构无 bean 时不入队 XML 通知）· 对齐 CC enqueuePendingNotification
     *  (LocalShellTask.tsx:166-171) — monitor 终态通知 priority='next'（feature('MONITOR_TOOL') 时注入模型） */
    @Autowired(required = false)
    private NotificationQueue notificationQueue;

    /** 是否仍在监控 — stop() 置 false 使轮询循环退出 */
    private volatile boolean running;

    /** 是否已请求停止（kill 语义）— stop() 置 true，monitor 退出时据此流转 killed（CC :142 stopped） */
    private volatile boolean stopped;

    /** 当前监控线程 — stop() 中断其 sleep 立即退出 */
    private volatile Thread monitorThread;

    /** 最近一次 registerTask 注册的任务 — monitor 退出时据此终态流转（无 framework bean 时亦可流转） */
    private volatile BackgroundTask activeTask;

    /**
     * 注册 MONITOR_MCP 任务 — 对齐 CC createTaskStateBase + registerTask（framework.ts:77-117）。
     *
     * <p>语义（CC 真源）：
     * <ol>
     *   <li>{@code generateTaskId('monitor_mcp')} — 前缀 'm' + 8 base36（Task.ts:82/98-106，
     *       TaskIdGenerator.generate 等价）</li>
     *   <li>outputFile 对齐 CC getTaskOutputPath（diskOutput.ts:72-73）</li>
     *   <li>{@code {status:'running', notified:false, startTime:Date.now()}} →
     *       registerTask（framework.ts:78-95 含 SDK task_started）</li>
     * </ol>
     *
     * @param description 监控描述（用于 CC 摘要 `Monitor "${description}" ...`；monitor 入参应一致）
     * @param toolUseId   关联 tool_use block id（可空）
     * @return 新任务 id（'m' 前缀）
     */
    public String registerTask(String description, String toolUseId) {
        return registerTask(description, toolUseId, null);
    }

    /**
     * 注册 MONITOR_MCP 任务（带 agent 归属）· OPD-TS-25：subagent 结束
     * {@code killMonitorMcpTasksForAgent}（CC runAgent.ts:852-861）按 agentId 批量终止其 monitor
     * 任务——monitor 任务须承载归属才能被 owner-scoped kill 命中（BackgroundTask.agentId）。
     *
     * @param description 监控描述（用于 CC 摘要 `Monitor "${description}" ...`）
     * @param toolUseId   关联 tool_use block id（可空）
     * @param agentId     拥有此 monitor 任务的 sub-agent UUID（主线程/main-session 触发为 null，
     *                    不归属任何 agent，不被 killMonitorMcpTasksForAgent 终止）
     * @return 新任务 id（'m' 前缀）
     */
    public String registerTask(String description, String toolUseId, @Nullable UUID agentId) {
        // Phase 4 (cron-notify): 无显式 sessionId 的既有入口 → 委托 4 参（sessionId=null 回落全局）。
        return registerTask(description, toolUseId, agentId, null);
    }

    /**
     * 注册 MONITOR_MCP 任务（带 agent 归属 + 创建会话）· Phase 4 (cron-notify)：
     * 通知带创建会话 sessionId → drain 3a 注入创建会话回合（MonitorTool.execute 经
     * {@code ctx.sessionId()} 透传创建会话）。
     *
     * @param description 监控描述（用于 CC 摘要 `Monitor "${description}" ...`）
     * @param toolUseId   关联 tool_use block id（可空）
     * @param agentId     拥有此 monitor 任务的 sub-agent UUID（主线程/main-session 触发为 null，
     *                    不归属任何 agent，不被 killMonitorMcpTasksForAgent 终止）
     * @param sessionId   创建此 monitor 任务的会话 sessionId（null → 回落全局）
     * @return 新任务 id（'m' 前缀）
     */
    public String registerTask(String description, String toolUseId, @Nullable UUID agentId,
                               @Nullable String sessionId) {
        String taskId = TaskIdGenerator.generate(TaskType.MONITOR_MCP);
        long now = System.currentTimeMillis();
        String outputFile = defaultOutputFile(taskId);
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.MONITOR_MCP, BackgroundTaskStatus.RUNNING,
            description, toolUseId, now, null, null,
            outputFile, 0L, false, agentId, true, sessionId,
            null, null, null, null, null, null);
        activeTask = task;
        if (taskFrameworkService != null) {
            // 对齐 CC framework.ts:78-95 registerTask → SDK task_started
            taskFrameworkService.registerTask(task);
        }
        log.info("[MonitorMcpTaskRunner] registerTask: taskId={}, type=monitor_mcp, status=running, outputFile={}, sessionId={}",
            taskId, outputFile, sessionId);
        return taskId;
    }

    /**
     * 流式监控 MCP 状态 — 对齐 CC kind='monitor' streaming-only（LocalShellTask.tsx:129-144）.
     *
     * <p>持续轮询 McpServerService.getCurrentTools/listAll，每次观测以一行追加写入 outputFile。
     * 循环直至 {@link #stop()}（或线程中断）——此时流结束，按 CC 语义流转终态并返回对应摘要：
     * <ul>
     *   <li>stop() 请求（kill）→ killed → `Monitor "${description}" stopped`（LocalShellTask.tsx:142）</li>
     *   <li>循环自然退出（未 stop）→ completed → `Monitor "${description}" stream ended`
     *       （LocalShellTask.tsx:136，脚本退出 = stream ended 非 condition met）</li>
     *   <li>轮询抛错 → failed → `Monitor "${description}" script failed`（LocalShellTask.tsx:139）
     *       + fail loud：log.error + 抛 RuntimeException（不静默"ok"掩盖错误）</li>
     * </ul>
     * 终态流转（{@link #transitionTerminal}）：updateTaskState（endTime + notified:true 可 evict）
     * + SDK task_notification。
     *
     * @param taskId     任务 id（须先经 {@link #registerTask} 注册；未注册时跳过生命周期流转）
     * @param description 监控描述（用于 CC 摘要；null 时用 taskId 占位）
     * @param outputFile 输出文件路径（逐次观测 append 写入；null/blank 时回退注册任务的 outputFile）
     * @return 终态 CC 摘要（stream ended / script failed / stopped）
     */
    public String monitor(String taskId, String description, String outputFile) {
        log.info("[MonitorMcpTaskRunner] start streaming: taskId={}", taskId);

        if (mcpServerService == null) {
            String msg = "MonitorMcpTaskRunner: McpServerService not available (no bean)";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        BackgroundTask task = resolveTask(taskId);
        if (task == null) {
            log.warn("[MonitorMcpTaskRunner] taskId={} 未注册（无 registerTask / 不在统一 store），跳过生命周期流转", taskId);
        }
        String desc = description != null ? description : taskId;
        String effectiveOutputFile = (task != null && (outputFile == null || outputFile.isBlank()))
            ? task.outputFile() : outputFile;

        running = true;
        stopped = false;
        monitorThread = Thread.currentThread();

        BackgroundTaskStatus terminal;
        String summary;
        try {
            while (running) {
                String observation = snapshot(taskId);
                appendLine(effectiveOutputFile, observation);
                Thread.sleep(POLL_INTERVAL_MS);
            }
            // 循环自然退出：stop() 已请求 → killed；否则流自然结束 → completed
            if (stopped) {
                summary = String.format("Monitor \"%s\" stopped", desc);
                terminal = BackgroundTaskStatus.KILLED;
            } else {
                summary = String.format("Monitor \"%s\" stream ended", desc);
                terminal = BackgroundTaskStatus.COMPLETED;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[MonitorMcpTaskRunner] stream ended (interrupted): taskId={}", taskId);
            // 线程中断 = 流被强制终止（stop() 中断 sleep 或外部中断）→ killed 语义（LocalShellTask.tsx:142）
            summary = String.format("Monitor \"%s\" stopped", desc);
            terminal = BackgroundTaskStatus.KILLED;
        } catch (Throwable t) {
            log.error("[MonitorMcpTaskRunner] script failed: taskId={}: {}", taskId, t.getMessage(), t);
            summary = String.format("Monitor \"%s\" script failed", desc);
            terminal = BackgroundTaskStatus.FAILED;
            if (task != null) {
                transitionTerminal(task, terminal, summary);
            }
            throw new RuntimeException("MonitorMcpTaskRunner failed: " + t.getMessage(), t);
        } finally {
            running = false;
            monitorThread = null;
        }
        if (task != null) {
            transitionTerminal(task, terminal, summary);
        }
        // CC LocalShellTask.tsx:136/139/142 — 脚本退出语义摘要
        return summary;
    }

    /**
     * 停止流式监控 — 对齐 CC kill → stream stopped（LocalShellTask.tsx:142）.
     *
     * <p>置 stopped=true + running=false + 中断 monitor 线程（正在 sleep 的轮询立即退出）。
     * monitor 据此流转 killed。
     */
    public void stop() {
        stopped = true;
        running = false;
        Thread t = monitorThread;
        if (t != null) {
            t.interrupt();
        }
        log.info("[MonitorMcpTaskRunner] stop requested (kill 语义): 线程中断, 流将按 stopped 结束");
    }

    /** 当前是否仍在监控 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 任务输出文件路径（供 MonitorTool 返回契约 "Use TaskOutput to read the stream from: ..."）。
     *
     * <p>与 {@link #registerTask} 内部 {@code defaultOutputFile(taskId)} 同一计算（确定性
     * 唯一根 {@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks/<taskId>.output}，批次Y Q3
     * 收敛 CC 唯一 diskOutput 机制，diskOutput.ts:50-55/72-74），避免调用方复制路径逻辑产生漂移。
     * 未注册的 taskId 亦可计算（路径与注册后一致，registerTask 不改变路径）。
     *
     * @param taskId 任务 id（'m' 前缀）
     * @return 该任务输出文件绝对路径
     */
    public String outputFileFor(String taskId) {
        return defaultOutputFile(taskId);
    }

    /**
     * 监控能力是否可用 — MCP 状态轮询依赖 {@link McpServerService} bean。
     *
     * <p><b>WHY（DEC-3 fail-loud 前置校验）</b>：{@link #monitor} 是无 mcpServerService 时
     * 抛 {@link IllegalStateException}（:154-158），但 monitor 在 MonitorTool.execute 内经
     * 独立线程启动——异常发生在子线程，execute 无法同步捕获。此方法让 execute 在启动线程前
     * 同步校验，null → 直接 ToolResult.error（不吞错误、不"假启动"）。
     */
    public boolean isMonitorAvailable() {
        return mcpServerService != null;
    }

    /**
     * 终态流转 — 对齐 CC updateTaskState + emitTaskTerminatedSdk。
     *
     * <p>{@code withStatus + withEndTime + withNotified}（CC: status + endTime + notified:true，
     * LocalShellTask.tsx:107-117 enqueueShellNotification 置 notified 防双发）→ updateTaskState
     * 使统一 store 终态可 evict（framework.ts:124-147）；SDK 事件 status 映射
     * completed/failed/stopped（sdkEventQueue.ts:114-134）；通知队列 XML 入队
     * （monitor priority='next'，LocalShellTask.tsx:166-171）。
     */
    private void transitionTerminal(BackgroundTask task, BackgroundTaskStatus status, String summary) {
        long now = System.currentTimeMillis();
        BackgroundTask terminal = task.withStatus(status).withEndTime(now).withNotified();
        if (taskFrameworkService != null) {
            taskFrameworkService.updateTaskState(task.id(), terminal);
        }
        if (sdkEventQueue != null) {
            String sdkStatus = switch (status) {
                case COMPLETED -> "completed";
                case FAILED -> "failed";
                case KILLED -> "stopped";
                default -> null;
            };
            if (sdkStatus != null) {
                sdkEventQueue.emitTaskTerminatedSdk(task.id(), sdkStatus,
                    new SdkEventQueue.TaskTerminatedOpts(task.toolUseId(), summary, task.outputFile(), null));
            }
        }
        // CC LocalShellTask.tsx:166-171 — enqueuePendingNotification（monitor 分支）：
        //   mode='task-notification'，priority = feature('MONITOR_TOOL') ? 'next' : 'later' → monitor 恒 next
        //   （优先于普通 bash 通知注入模型）；agentId = 归属 subagent（主线程 null）。
        if (notificationQueue != null) {
            String xml = buildMonitorNotification(terminal, summary);
            // Phase 4 (cron-notify): 通知带创建会话 sessionId（registerTask 4 参透传）→ drain 3a
            // 注入创建会话回合；agentId 仍按原 owner-scoped 语义（subagent 自消费）。
            notificationQueue.enqueuePendingNotification(
                new NotificationQueue.QueueItem(xml, NotificationQueue.MODE_TASK_NOTIFICATION,
                    NotificationQueue.Priority.NEXT,
                    task.agentId() != null ? task.agentId().toString() : null,
                    null, false, null, false, null, task.sessionId()));
        }
        log.info("[MonitorMcpTaskRunner] 终态流转: taskId={}, status={}, summary='{}'",
            task.id(), status.getStatusString(), summary);
    }

    /**
     * 构建 monitor 终态通知 XML — 对齐 CC enqueueShellNotification monitor 分支
     * (LocalShellTask.tsx:160-165) 5 TAG：task-id / [tool-use-id] / output-file / status / summary。
     *
     * <p>摘要格式（CC :136/139/142，monitor streaming-only 无 exit code 概念 → 省略 "(exit N)" 段）：
     * <pre>
     * completed → Monitor "desc" stream ended
     * failed    → Monitor "desc" script failed
     * killed    → Monitor "desc" stopped
     * </pre>
     */
    private static String buildMonitorNotification(BackgroundTask task, String summary) {
        StringBuilder xml = new StringBuilder(256);
        xml.append("<task-notification>\n");
        appendTag(xml, "task-id", task.id());
        if (task.toolUseId() != null) {
            appendTag(xml, "tool-use-id", task.toolUseId());
        }
        appendTag(xml, "output-file", task.outputFile());
        appendTag(xml, "status", task.status().getStatusString());
        appendTag(xml, "summary", summary);
        xml.append("</task-notification>");
        return xml.toString();
    }

    private static void appendTag(StringBuilder sb, String tag, String value) {
        sb.append("  <").append(tag).append(">")
          .append(escapeXml(value))
          .append("</").append(tag).append(">\n");
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    /** 解析当前任务：优先 registerTask 的 activeTask，其次统一 store（getTask），无则 null。 */
    private BackgroundTask resolveTask(String taskId) {
        BackgroundTask active = activeTask;
        if (active != null && active.id().equals(taskId)) {
            return active;
        }
        if (taskFrameworkService != null) {
            return taskFrameworkService.getTask(taskId).orElse(null);
        }
        return null;
    }

    /**
     * 默认输出文件路径 — 对齐 CC getTaskOutputPath（diskOutput.ts:72-73
     * {@code join(getTaskOutputDir(), \`${taskId}.output\`)}）。
     *
     * <p><b>批次Y Q3 收敛唯一根</b>：与 {@link BackgroundTaskRunner#taskOutputPath} 同源
     * （{@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks/<taskId>.output}）——CC 所有后台任务
     * （Bash/PS/monitor/agent/remote_agent）共用唯一 diskOutput 根（diskOutput.ts:50-55），
     * monitor_mcp 旧独立 flat 根 {@code {tmpdir}/nexusai-tasks}（无 per-session 层）为 Java 自创
     * 偏离，已收敛。sessionId 来源 {@link BackgroundTaskRunner#resolveSessionId()} 同源
     * （RequestContext.sessionId() 主源 → nexusai.sessionId sysprop → unknown）。
     * 父目录由 {@link #appendLine} 写前 createDirectories（对齐 CC ensureOutputDir，
     * diskOutput.ts:65-67 mkdir recursive）。
     */
    private static String defaultOutputFile(String taskId) {
        return BackgroundTaskRunner.taskOutputPath(taskId);
    }

    /**
     * 单次观测快照：getCurrentTools（活跃工具池） + listAll（各 server 状态计数）。
     *
     * @return 一行观测文本（写入 outputFile 的原始内容）
     */
    private String snapshot(String taskId) {
        List<Tool> tools = mcpServerService.getCurrentTools();
        List<com.nexusai.model.mcp.dto.McpServerDto> servers = mcpServerService.listAll();

        int runningCount = 0, errorCount = 0, stoppedCount = 0;
        for (com.nexusai.model.mcp.dto.McpServerDto s : servers) {
            McpStatus status = s.status();
            if (status == null) {
                if (log.isDebugEnabled()) {
                    log.debug("MonitorMcpTaskRunner: null status for server={}", s.name());
                }
                continue;
            }
            switch (status) {
                case running -> runningCount++;
                case error -> errorCount++;
                case stopped -> stoppedCount++;
            }
            if (status == McpStatus.error) {
                log.warn("[MonitorMcpTaskRunner] MCP server in error state: id={} name={} lastError={}",
                    s.id(), s.name(), s.lastError());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("MonitorMcpTaskRunner: taskId={} pool={} servers={} running={} error={} stopped={}",
                taskId, tools.size(), servers.size(), runningCount, errorCount, stoppedCount);
        }
        return "pool=" + tools.size() + ",servers=" + servers.size()
            + ",running=" + runningCount + ",error=" + errorCount + ",stopped=" + stoppedCount;
    }

    /**
     * 追加一行观测到 outputFile — 对齐 CC 流式重定向（LocalBashTaskRunner.appendOutputLine 同款
     * CREATE+APPEND 模式）。文件不存在时创建；写失败仅 log.warn（不阻断监控主循环）。
     */
    private void appendLine(String outputFile, String line) {
        if (outputFile == null || outputFile.isBlank()) {
            return;
        }
        try {
            Path filePath = Path.of(outputFile);
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("MonitorMcpTaskRunner: append output file failed: path={} 错误={}",
                outputFile, e.getMessage());
        }
    }
}

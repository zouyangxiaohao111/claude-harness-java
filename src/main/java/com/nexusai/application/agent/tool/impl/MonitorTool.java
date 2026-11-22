package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tasks.MonitorMcpTaskRunner;
import com.nexusai.application.agent.tasks.MonitorTaskRunner;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Monitor 工具 · 对齐 claude.exe v2.1.325 的 MonitorTool（off≈164,355,xxx）。
 *
 * <p><b>CC 真契约</b>（本次用 REA 从二进制挖出，取代此前的推断实现）：
 * <ul>
 *   <li>{@code command} 与 {@code ws} <b>二选一</b>（原文 {@code exactly one of command or ws}）；</li>
 *   <li>{@code command}：<i>Shell command or script. Each stdout line is an event; exit ends the watch.</i></li>
 *   <li>{@code description}：给人看的监控描述（任务面板与通知摘要）；</li>
 *   <li>{@code timeout_ms}：到点自动结束；{@code allowed_domains}：命令模式域名白名单。</li>
 * </ul>
 *
 * <p>工具说明原文：<i>When you are about to check a log, process or job again and again to
 * catch each new event, use it and not repeated Bash calls or sleeps.</i>
 *
 * <p>门控：{@code MONITOR_TOOL} feature flag（默认关，对齐 CC flag-off null）。
 */
public class MonitorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MonitorTool.class);

    public static final String NAME = ToolNameConstants.MONITOR_TOOL_NAME;

    private final FeatureFlags featureFlags;
    /** 历史 MCP 状态监控执行器（保留构造兼容；command/ws 模式不用它）。 */
    private final MonitorMcpTaskRunner monitorMcpTaskRunner;
    /** CC 契约的命令/连接执行器。 */
    private final MonitorTaskRunner monitorTaskRunner;
    /** 配置强制开启（nexusai.feature.monitor-tool=true）· 与 CC 的实验 flag 并存。 */
    private final boolean forceEnabled;
    /** 权限判定复用 Bash 链（setter 注入，避免改注册方法签名）。 */
    private BashTool bashTool;

    public MonitorTool() {
        this(FeatureFlags.ALL_DISABLED, null, null, false);
    }

    public MonitorTool(FeatureFlags featureFlags) {
        this(featureFlags, null, null, false);
    }

    /** 旧构造器（MCP 模式）兼容。 */
    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner) {
        this(featureFlags, monitorMcpTaskRunner, null, false);
    }

    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner,
                       MonitorTaskRunner monitorTaskRunner) {
        this(featureFlags, monitorMcpTaskRunner, monitorTaskRunner, false);
    }

    public MonitorTool(FeatureFlags featureFlags, MonitorMcpTaskRunner monitorMcpTaskRunner,
                       MonitorTaskRunner monitorTaskRunner, boolean forceEnabled) {
        this.featureFlags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
        this.monitorMcpTaskRunner = monitorMcpTaskRunner;
        this.monitorTaskRunner = monitorTaskRunner;
        this.forceEnabled = forceEnabled;
    }

    @Autowired(required = false)
    public void setBashTool(BashTool bashTool) {
        this.bashTool = bashTool;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 工具描述 · 对齐 claude.exe v2.1.328 Monitor（description/prompt = {@code bri()} + ws 段），
     * 保留「按通知次数选工具」的决策树。
     *
     * <p><b>逐字对齐的部分</b>：开头段、{@code Pick by how many notifications you need} 三档分流、
     * 示例（含 2.1.328 新增的 CI 检查示例）、脚本质量/覆盖率/输出量约束。
     *
     * <p><b>按本仓事实裁剪的部分</b>（⛔ 不照抄不存在的机制）：
     * <ul>
     *   <li>本仓 {@code MonitorTaskRunner} 的 {@code redirectErrorStream(true)} ⇒ stderr 合并进事件流，
     *       不是 CC 的「stderr 只落文件」；</li>
     *   <li>本仓每行立即入队（无 CC 的 200ms 批处理），前 {@code 200} 条推送、超出只落文件；</li>
     *   <li>本仓 {@code timeout_ms} 缺省/0 = 不超时（CC 默认 300000ms、上限 1800000ms、另有
     *       {@code persistent} 字段）⇒ 结果文案统一按 CC 的 persistent 分支输出；</li>
     *   <li>本仓 {@code ws} 仍是 URL 字符串（CC 2.1.328 为 {@code {url, protocols}}），
     *       ws 段文案按本仓能力写。</li>
     * </ul>
     */
    @Override
    public String description() {
        return """
            When you are about to check a log, process or job again and again to catch each new event, use it and not repeated Bash calls or sleeps.

            Start a background monitor that streams events from a long-running script. Each stdout line is an event — you keep working and notifications arrive in the chat. Events arrive on their own schedule and are not replies from the user, even if one lands while you're waiting for the user to answer a question.

            Pick by how many notifications you need:
            - **One** ("tell me when the server is ready / the build finishes") → run the command in the **foreground with Bash**, exiting when the condition is true, e.g. `until grep -q "Ready in" dev.log; do sleep 0.5; done`.
            - **One per occurrence, while you work on this turn** ("show each ERROR line in the dev server's log while you try the change") → Monitor with an unbounded command (`tail -f`, `inotifywait -m`, `while true`).
            - **One per occurrence, until a known end, while you work on this turn** ("show each test file's result as the suite you just started runs, stop when it ends") → Monitor with a command that emits lines and then exits.

            Your script's stdout is the event stream. Each line becomes a notification. Exit ends the watch.

              # Each matching log line is an event
              tail -f /var/log/app.log | grep --line-buffered "ERROR"

              # Each file change is an event
              inotifywait -m --format '%e %f' /watched/dir

              # Poll GitHub for new PR comments and emit one line per new comment
              last=$(date -u +%Y-%m-%dT%H:%M:%SZ)
              while true; do
                now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
                gh api "repos/owner/repo/issues/123/comments?since=$last" --jq '.[] | "\\(.user.login): \\(.body)"'
                last=$now; sleep 30
              done

              # Node script that emits events as they arrive (e.g. WebSocket listener)
              node watch-for-events.js

              # Per-occurrence with a natural end: emit each CI check as it lands, exit when the run completes
              prev=""
              while true; do
                s=$(gh pr checks 123 --json name,bucket)
                cur=$(jq -r '.[] | select(.bucket!="pending") | "\\(.name): \\(.bucket)"' <<<"$s" | sort)
                comm -13 <(echo "$prev") <(echo "$cur")
                prev=$cur
                jq -e 'all(.bucket!="pending")' <<<"$s" >/dev/null && break
                sleep 30
              done

            **Don't use an unbounded command for a single notification.** `tail -f`, `inotifywait -m`, and `while true` never exit on their own, so the monitor stays armed until timeout even after the event has fired. For "tell me when X is ready," use a foreground Bash `until` loop instead. Note that `tail -f log | grep -m 1 ...` does *not* fix this: if the log goes quiet after the match, `tail` never receives SIGPIPE and the pipeline hangs anyway.

            **Script quality:**
            - Every pipe stage must flush per line or matches sit in its buffer unseen: `grep` needs `--line-buffered`, `awk` needs `fflush()`. `head` cannot flush at all — `| head -N` delivers nothing until N matches accumulate, then ends the stream.
            - In poll loops, handle transient failures (`curl ... || true`) — one failed request shouldn't kill the monitor.
            - Poll intervals: 30s+ for remote APIs (rate limits), 0.5-1s for local checks.
            - Write a specific `description` — it appears in every notification ("errors in deploy.log" not "watching logs").
            - stdout and stderr are merged into the event stream; filter both with the same pattern if a failure may be written to stderr.

            **Coverage — silence is not success.** When watching a job or process for an outcome, your filter must match every terminal state, not just the happy path. A monitor that greps only for the success marker stays silent through a crashloop, a hung process, or an unexpected exit — and silence looks identical to "still running." Before arming, ask: *if this process crashed right now, would my filter emit anything?* If not, widen it.

              # Wrong — silent on crash, hang, or any non-success exit
              tail -f run.log | grep --line-buffered "elapsed_steps="

              # Right — one alternation covering progress + the failure signatures you'd act on
              tail -f run.log | grep -E --line-buffered "elapsed_steps=|Traceback|Error|FAILED|assert|Killed|OOM"

            For poll loops checking job state, emit on every terminal status (`succeeded|failed|cancelled|timeout`), not just success. If you cannot confidently enumerate the failure signatures, broaden the grep alternation rather than narrow it — some extra noise is better than missing a crashloop.

            **Output volume**: Every stdout line is a conversation message, so the filter should be selective — but selective means "the lines you'd act on," not "only good news." Never pipe raw logs; filter to exactly the success and failure signals you care about. The first 200 events of a monitor are pushed to the conversation; beyond that, lines are only written to the task's output file (readable with Read).

            **ws source** — open a WebSocket and stream each incoming message as an event. No shell, no polling: the server pushes, you get notified.

              Monitor({
                ws: 'wss://events.example.com/stream',
                description: 'deploy events',
              })

            Each text or binary message becomes one notification. Socket close ends the watch; errors are surfaced before close. Same output-volume limit as a command monitor.

            The script runs in the same shell environment as Bash. Exit ends the watch (exit code is reported). Set `timeout_ms` to stop it automatically; omit it (or 0) for no timeout. Use TaskStop to cancel early.""";
    }

    /**
     * 搜索提示 · 对齐 CC v2.1.325 MonitorTool 的 {@code searchHint}
     * （off≈327,891,640：{@code watch, monitor, or keep an eye on a process/log/command or WebSocket
     * — stream each stdout line as a live notification}）。
     */
    @Override
    public String searchHint() {
        return "watch, monitor, or keep an eye on a process/log/command or WebSocket — "
            + "stream each stdout line as a live notification";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string").put("description",
            "Shell command or script. Each stdout line is an event; exit ends the watch.");
        props.putObject("ws").put("type", "string").put("description",
            "WebSocket URL (ws:// or wss://). Each message is an event.");
        props.putObject("description").put("type", "string").put("description",
            "Short human-readable description of what you are monitoring (shown in notifications).");
        props.putObject("timeout_ms").put("type", "integer").put("description",
            "Kill the monitor after this deadline (milliseconds). Omit or 0 = persistent — runs until TaskStop or session end.");
        ObjectNode domains = props.putObject("allowed_domains");
        domains.put("type", "array");
        domains.putObject("items").put("type", "string");
        domains.put("description", "allowed_domains applies only to a command monitor");
        schema.putArray("required").add("description");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        return forceEnabled || featureFlags.monitorTool();
    }

    /**
     * 权限 · command 模式复用 Bash 权限链（对齐 CC bashToolHasPermission 结构）；
     * ws 模式由 execute 做协议与域名校验。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        String command = input != null ? input.path("command").asText("") : "";
        if (!command.isBlank() && bashTool != null) {
            return bashTool.checkPermissions(input, ctx);
        }
        return new PermissionResult.Allow(
            input,
            new PermissionDecisionReason.Other("monitor_allow"),
            null, false, null, List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (monitorTaskRunner == null) {
            return ToolResult.error(call.id(),
                "Monitor 工具不可用：MonitorTaskRunner 未装配（MONITOR_TOOL 需 task 系统运行时）");
        }
        JsonNode input = call.input();
        String command = input.path("command").asText("");
        String ws = input.path("ws").asText("");
        boolean hasCommand = !command.isBlank();
        boolean hasWs = !ws.isBlank();
        if (hasCommand == hasWs) {
            return ToolResult.error(call.id(), "Exactly one of command or ws is required");
        }
        String description = input.path("description").asText("");
        if (description.isBlank()) {
            return ToolResult.error(call.id(), "description is required");
        }
        long timeoutMs = input.path("timeout_ms").asLong(0L);
        if (timeoutMs < 0) {
            return ToolResult.error(call.id(), "timeout_ms must be >= 0");
        }
        List<String> domains = new ArrayList<>();
        JsonNode domainsNode = input.path("allowed_domains");
        if (domainsNode.isArray()) {
            for (JsonNode d : domainsNode) {
                if (d.isTextual()) {
                    domains.add(d.asText());
                }
            }
        }
        String sessionId = ctx != null ? ctx.sessionId() : null;
        String agentId = ctx != null ? ctx.agentId() : null;
        try {
            String taskId = hasCommand
                ? monitorTaskRunner.startCommand(command, description, timeoutMs, call.id(), agentId, sessionId)
                : monitorTaskRunner.startWebSocket(ws, domains, description, timeoutMs, call.id(), agentId, sessionId);
            log.info("[MonitorTool] started taskId={} mode={} desc={}",
                taskId, hasCommand ? "command" : "ws", description);
            // 工具结果 · 对齐 CC 2.1.328 MonitorTool.mapToolResultToToolResultBlockParam
            // （timeout<=0 = persistent → 「persistent — runs until TaskStop or session end」）。
            String timeoutLabel = timeoutMs > 0
                ? "timeout " + timeoutMs + "ms"
                : "persistent — runs until TaskStop or session end";
            return ToolResult.success(call.id(),
                "Monitor started (task " + taskId + ", " + timeoutLabel + "). "
                    + "You will be notified on each event. Keep working — do not poll or sleep. "
                    + "Events may arrive while you are waiting for the user — an event is not their reply.");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), e.getMessage());
        } catch (Exception e) {
            log.error("[MonitorTool] start failed: {}", e.getMessage(), e);
            return ToolResult.error(call.id(), "Monitor failed to start: " + e.getMessage());
        }
    }
}

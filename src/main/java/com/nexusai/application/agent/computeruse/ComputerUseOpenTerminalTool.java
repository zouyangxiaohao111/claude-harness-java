package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code mcp__computer-use__open_terminal} · 打开终端并启动 AI agent CLI（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:535-575}）</h2>
 * <pre>
 * name: 'open_terminal',
 * description: 'Open a new terminal window and launch an AI agent CLI. ' +
 *   'This is a workflow tool that automates: open terminal → type startup command → press Enter → wait → verify. ' +
 *   "Supported agents: claude (runs 'claude'), codex (runs 'codex'), gemini (runs 'gemini'), " +
 *   'or any custom command. After launching, the tool binds to the new terminal window ' +
 *   'and takes a screenshot to verify the agent started successfully. ' +
 *   "Use this when the user says: 'open Claude Code', 'start a Codex terminal', 'launch Gemini', etc.",
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     agent: { type: 'string', enum: [claude,codex,gemini,custom], description: '...' },
 *     command: { type: 'string', description: "Custom command to run in the terminal. Only used when agent='custom'. Example: 'python app.py'" },
 *     terminal: { type: 'string', enum: [wt,powershell,cmd], description: "Which terminal to open. Default: 'wt' (Windows Terminal). 'powershell' for PowerShell window, 'cmd' for Command Prompt." },
 *     working_directory: { type: 'string', description: 'Working directory for the terminal. If omitted, uses current directory.' },
 *   },
 *   required: ['agent'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code toolCalls.ts:3162-3226} + {@code executorCrossPlatform.ts:578-652}）</h2>
 * <ol>
 *   <li>校验 {@code agent}（合法值 4 个）；{@code custom} 必须带 {@code command}；</li>
 *   <li>启动终端（命令在终端启动参数里 ⇒ 终端起来时命令已在跑）；</li>
 *   <li><b>轮询等待新窗口出现</b>（CCB {@code executorCrossPlatform.ts:616-638}：最多 5 秒），
 *       并把新窗口<b>绑定</b>；</li>
 *   <li>返回「已启动 + 窗口信息 + 提示截图验证」。</li>
 * </ol>
 */
public final class ComputerUseOpenTerminalTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseOpenTerminalTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__open_terminal";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:536-542}。 */
    private static final String DESCRIPTION =
            "Open a new terminal window and launch an AI agent CLI. "
                    + "This is a workflow tool that automates: open terminal → type startup command → press Enter → wait → verify. "
                    + "Supported agents: claude (runs 'claude'), codex (runs 'codex'), gemini (runs 'gemini'), "
                    + "or any custom command. After launching, the tool binds to the new terminal window "
                    + "and takes a screenshot to verify the agent started successfully. "
                    + "Use this when the user says: 'open Claude Code', 'start a Codex terminal', 'launch Gemini', etc.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:543-574}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "agent": {
                  "type": "string",
                  "enum": ["claude", "codex", "gemini", "custom"],
                  "description": "Which agent to launch. claude: runs 'claude' command. codex: runs 'codex' command. gemini: runs 'gemini' command. custom: runs the command specified in 'command' parameter."
                },
                "command": {
                  "type": "string",
                  "description": "Custom command to run in the terminal. Only used when agent='custom'. Example: 'python app.py'"
                },
                "terminal": {
                  "type": "string",
                  "enum": ["wt", "powershell", "cmd"],
                  "description": "Which terminal to open. Default: 'wt' (Windows Terminal). 'powershell' for PowerShell window, 'cmd' for Command Prompt."
                },
                "working_directory": {
                  "type": "string",
                  "description": "Working directory for the terminal. If omitted, uses current directory."
                }
              },
              "required": ["agent"]
            }
            """;

    /** 合法 agent · CCB {@code toolCalls.ts:3175}。 */
    private static final List<String> VALID_AGENTS = List.of("claude", "codex", "gemini", "custom");

    /** 轮询等待新窗口的次数 · CCB {@code executorCrossPlatform.ts:619}（25 × 200ms = 5 秒）。 */
    private static final int POLL_ATTEMPTS = 25;

    /** 轮询间隔（毫秒）· CCB {@code executorCrossPlatform.ts:620}。 */
    private static final long POLL_INTERVAL_MS = 200L;

    private final ComputerUseTerminalLauncher launcher;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseSleeper sleeper;

    /**
     * @param launcher     终端启动器
     * @param windowOps    窗口操作原语（用于找新窗口）
     * @param bindingStore 绑定窗口存储
     * @param sleeper      休眠器（可注入假时钟）
     */
    public ComputerUseOpenTerminalTool(ComputerUseTerminalLauncher launcher,
                                        ComputerUseWindowOps windowOps,
                                        ComputerUseWindowBindingStore bindingStore,
                                        ComputerUseSleeper sleeper) {
        this.launcher = launcher;
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] open_terminal inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 会启动应用 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use open_terminal is gated by the "
                        + "session allowlist granted via request_access"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode agentNode = input.path("agent");
        if (!agentNode.isTextual()) {
            // CCB toolCalls.ts:3172-3173 —— requireString 的错误文案
            return ToolResult.error(call.id(), "\"agent\" must be a string.");
        }
        String agent = agentNode.asText();
        if (!VALID_AGENTS.contains(agent)) {
            // CCB toolCalls.ts:3176-3179
            return ToolResult.error(call.id(),
                    "Invalid agent \"" + agent + "\". Valid: " + String.join(", ", VALID_AGENTS));
        }
        JsonNode commandNode = input.path("command");
        String command = commandNode.isTextual() ? commandNode.asText() : null;
        if ("custom".equals(agent) && command == null) {
            // CCB toolCalls.ts:3182-3184
            return ToolResult.error(call.id(), "agent='custom' requires 'command' parameter.");
        }
        JsonNode terminalNode = input.path("terminal");
        String terminal = terminalNode.isTextual() ? terminalNode.asText() : "wt";
        JsonNode cwdNode = input.path("working_directory");
        String workingDirectory = cwdNode.isTextual() ? cwdNode.asText() : null;

        String sessionId = ctx != null ? ctx.sessionId() : null;
        try {
            // 启动前的窗口快照（用于识别「新」窗口）· CCB executorCrossPlatform.ts:601
            Set<Long> before = new HashSet<>();
            for (ComputerUseWindowInfo w : windowOps.listVisibleWindows()) {
                before.add(w.hwnd());
            }

            launcher.launch(agent, command, terminal, workingDirectory);

            // 轮询等待新窗口 · CCB executorCrossPlatform.ts:616-638
            ComputerUseWindowInfo found = null;
            for (int i = 0; i < POLL_ATTEMPTS && found == null; i++) {
                sleeper.sleep(POLL_INTERVAL_MS);
                for (ComputerUseWindowInfo w : windowOps.listVisibleWindows()) {
                    if (!before.contains(w.hwnd()) && isTerminalWindow(w, agent, command)) {
                        found = w;
                        break;
                    }
                }
            }

            if (found == null) {
                return ToolResult.error(call.id(),
                        "Terminal window not detected within 5 seconds. The terminal may still be starting — "
                                + "use bind_window(action='list') to find and bind it manually.");
            }

            if (bindingStore != null) {
                bindingStore.bind(sessionId, new ComputerUseWindowBinding(found, null, null));
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 终端已启动并绑定: hwnd={} title=\"{}\" agent={}",
                        TOOL_NAME, found.hwndText(), found.title(), agent);
            }
            // CCB toolCalls.ts:3220-3225
            return ToolResult.success(call.id(),
                    "Terminal opened and " + agentDisplayName(agent, command) + " launched.\n"
                            + "Window: hwnd=" + found.hwndText() + " \"" + found.title() + "\"\n"
                            + "Command: '" + ("custom".equals(agent) ? command : agent) + "' + Enter\n"
                            + "Status: bound to this terminal. Take a screenshot to verify the agent started.");
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: agent={} err={}", TOOL_NAME, agent, e.getMessage(), e);
            return ToolResult.error(call.id(), "终端启动失败: " + e.getMessage());
        }
    }

    /** agent 的展示名 · CCB {@code toolCalls.ts:3213-3218} 的 {@code agentNames}。 */
    private static String agentDisplayName(String agent, String command) {
        return switch (agent) {
            case "claude" -> "Claude Code";
            case "codex" -> "Codex";
            case "gemini" -> "Gemini";
            case "custom" -> command == null ? "custom" : command;
            default -> agent;
        };
    }

    /**
     * 新窗口是否像终端窗口 · CCB {@code executorCrossPlatform.ts:621-632}
     * （标题含 terminal / powershell / cmd / 命令名）。
     */
    private static boolean isTerminalWindow(ComputerUseWindowInfo window, String agent, String command) {
        String title = window.title().toLowerCase(java.util.Locale.ROOT);
        if (title.contains("terminal") || title.contains("powershell") || title.contains("cmd")) {
            return true;
        }
        String needle = "custom".equals(agent) ? command : agent;
        return needle != null && !needle.isBlank() && title.contains(needle.toLowerCase(java.util.Locale.ROOT));
    }
}

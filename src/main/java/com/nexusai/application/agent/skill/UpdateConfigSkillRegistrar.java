package com.nexusai.application.agent.skill;

import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /update-config skill 注册器 · 对齐 CC skills/bundled/updateConfig.ts.
 *
 * <p>L1 语义: 注册 /update-config skill — 修改 settings.json (用户/项目/本地).
 *            - args 以 "[hooks-only]" 开头 → 返回 HOOKS_DOCS + HOOK_VERIFICATION_FLOW (≤ user request)
 *            - 否则 → UPDATE_CONFIG_PROMPT (已内联 SETTINGS_EXAMPLES_DOCS + HOOKS_DOCS +
 *                    HOOK_VERIFICATION_FLOW + Example Workflows，逐字对齐 CC :307-443)
 *                    + 动态 JSON Schema (通过 schemaSupplier 注入).
 *            args 非空 → 末尾追加 "## User Request" 段.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register() → BundledSkillDefinition; name="update-config";
 *       allowedTools=[Read]; userInvocable=true; 无 whenToUse/argumentHint（CC updateConfig.ts:446-451）.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — args="[hooks-only]..." → HOOKS_DOCS;
 *       args="" → 完整 UPDATE_CONFIG_PROMPT; args="set DEBUG=true" →
 *       UPDATE_CONFIG_PROMPT + ## User Request.</li>
 *   <li><b>A3</b>: 状态 — HOOKS_ONLY / FULL; args 检测 (prefix + 内容).</li>
 *   <li><b>A4</b>: args=null → FULL; args="[hooks-only]" 无后续 → HOOKS_ONLY 空 task;
 *       schemaSupplier=null → schema 段省略 (不抛).</li>
 *   <li><b>A5</b>: 真实场景 — 用户说 "add prettier hook after Write" →
 *       skill 输出 UPDATE_CONFIG_PROMPT + Hooks 文档 + 验证流程, 引导用户编辑 settings.json.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC async getPromptForCommand → 同步 Function (上层 wrap async);
 *                    CC zod SettingsSchema → 注入式 Supplier&lt;String&gt; (测试用 stub);
 *                    TS template literal → Java text block; TS `${...}` 模板插值 → Java 常量拼接
 *                    （UPDATE_CONFIG_PROMPT = core + SETTINGS_EXAMPLES_DOCS + HOOKS_DOCS +
 *                    HOOK_VERIFICATION_FLOW + closing，逐字对齐 CC :307-443 插值产物）.
 */
public final class UpdateConfigSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(UpdateConfigSkillRegistrar.class);

    public static final String SKILL_NAME = "update-config";
    public static final String HOOKS_ONLY_PREFIX = "[hooks-only]";

    /** CC updateConfig.ts:448-449 description — 完整描述（含 permissions/env vars 具体示例与 Examples 段）. */
    public static final String DESCRIPTION =
        "Use this skill to configure the NexusAI harness via settings.json. "
            + "Automated behaviors (\"from now on when X\", \"each time X\", \"whenever X\", "
            + "\"before/after X\") require hooks configured in settings.json - the harness "
            + "executes these, not Claude, so memory/preferences cannot fulfill them. "
            + "Also use for: permissions (\"allow X\", \"add permission\", \"move permission to\"), "
            + "env vars (\"set X=Y\"), hook troubleshooting, or any changes to "
            + "settings.json/settings.local.json files. Examples: \"allow npm commands\", "
            + "\"add bq permission to global settings\", \"move permission to user settings\", "
            + "\"set DEBUG=true\", \"when claude stops show X\". For simple settings like theme/model, "
            + "use Config tool.";

    /**
     * 真实 Settings JSON Schema · 对齐 CC updateConfig.ts:10-13 {@code generateSettingsSchema()}
     * = {@code toJSONSchema(SettingsSchema(), { io: 'input' })}（utils/settings/types.ts:255-1073）。
     *
     * <p><b>P2-11 / DEL-05 动态校验</b>：CC 在运行时从 Zod {@code SettingsSchema} 动态生成；Java 旧实现
     * 为<b>手写静态 JSON 字符串</b>（字段增删需人工同步 → 漂移，EV-WF3-BD-181 △）。现由
     * {@link SettingsSchemaGenerator#generate()} 从声明式字段定义程序化生成（镜像 CC SettingsSchema），
     * 字段增删只改生成器定义一处、schema 自动再生成。顶层契约与 CC 一致：
     * <ul>
     *   <li>{@code type:"object"} + {@code additionalProperties:true}（CC {@code .passthrough()}，types.ts:1072）</li>
     *   <li>全部字段 {@code .optional()} → 不进 {@code required}（CC 无必填字段）</li>
     *   <li>枚举 → {@code type:"string" + enum}；{@code z.record} → {@code additionalProperties}；
     *       {@code z.coerce.string} env → 字符串值</li>
     * </ul>
     *
     * <p>feature-gated 字段（xaaIdp / disableDeepLinkRegistration / classifierPermissionsEnabled /
     * minSleepDurationMs / maxSleepDurationMs / voiceEnabled / assistant / assistantName / defaultView /
     * effortLevel.max）随 CC 编译期 feature()/env 动态增减，Java Web 无对应 feature，生成器仅含
     * 确定性核心字段（Java Web 生产 bundle 与 CC 生产 bundle 的 feature 关闭态一致）。
     *
     * <p><b>已知 △ + 漂移风险（非全量对齐）</b>：CC 运行时从 Zod 动态生成 schema（每次
     * {@code generateSettingsSchema()} 调用 {@code toJSONSchema} 都与真实类型同步），Java 从静态
     * 字段定义生成——CC 后续增删字段/改枚举不会自动同步，需人工改 {@link SettingsSchemaGenerator}
     * 定义。已登记的显式漂移点：
     * <ul>
     *   <li>{@code $schema}：CC {@code z.literal(CLAUDE_CODE_SETTINGS_SCHEMA_URL)}（types.ts:258-259，
     *       const 值 {@code https://json.schemastore.org/claude-code-settings.json}）→ Java 以
     *       {@code type:"string" + const} 等价表达（zod v4 {@code z.literal} 的 toJSONSchema 产物）。</li>
     *   <li>feature-gated 字段省略（见上）。</li>
     * </ul>
     */
    public static final String REAL_SETTINGS_SCHEMA = SettingsSchemaGenerator.generate();

    private final Supplier<String> settingsSchemaSupplier;

    public UpdateConfigSkillRegistrar(Supplier<String> settingsSchemaSupplier) {
        this.settingsSchemaSupplier = settingsSchemaSupplier == null ? () -> REAL_SETTINGS_SCHEMA : settingsSchemaSupplier;
    }

    public UpdateConfigSkillRegistrar() {
        this(() -> REAL_SETTINGS_SCHEMA);
    }

    /** 主入口 — 注册 bundled skill · 统一产出 BundledSkillDefinition（P1-4）. */
    public BundledSkillDefinition register() {
        return new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            null,   // whenToUse（CC updateConfig.ts:446-451 register 无 whenToUse — Java 自增已移除）
            null,   // argumentHint（CC updateConfig.ts:446-451 register 无 argumentHint — Java 自增已移除）
            List.of("Read"),   // allowedTools (CC updateConfig.ts:450, 修 E9)
            null,   // model
            false,  // disableModelInvocation=false (CC undefined → default false)
            true,   // userInvocable (CC updateConfig.ts:451)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> buildPrompt(args)
        );
    }

    /** CC updateConfig.ts getPromptForCommand — 主链. */
    private List<PromptBlock> buildPrompt(String args) {
        String userArgs = args == null ? "" : args;
        if (userArgs.startsWith(HOOKS_ONLY_PREFIX)) {
            String req = userArgs.substring(HOOKS_ONLY_PREFIX.length()).trim();
            StringBuilder sb = new StringBuilder(HOOKS_DOCS).append("\n\n").append(HOOK_VERIFICATION_FLOW);
            if (!req.isEmpty()) {
                sb.append("\n\n## Task\n\n").append(req);
            }
            if (log.isDebugEnabled()) {
                log.debug("[UpdateConfigSkillRegistrar] buildPrompt [hooks-only] req='{}'（CC updateConfig.ts:453-460）", req);
            }
            // [T3/#21] prompt 文本 .nexusai → 动态 appName（决策 D1/D6）
            return List.of(PromptBlock.text(sb.toString().replace(".nexusai", "." + NexusaiPaths.getAppName())));
        }
        // Full prompt — UPDATE_CONFIG_PROMPT 已内联 SETTINGS/HOOKS/VERIFICATION/Example Workflows（CC :378-382 模板插值）
        StringBuilder sb = new StringBuilder(UPDATE_CONFIG_PROMPT);
        sb.append("\n\n## Full Settings JSON Schema\n\n```json\n")
          .append(settingsSchemaSupplier.get()).append("\n```");
        if (!userArgs.isEmpty()) {
            sb.append("\n\n## User Request\n\n").append(userArgs);
        }
        if (log.isDebugEnabled()) {
            log.debug("[UpdateConfigSkillRegistrar] buildPrompt full userArgs='{}' schemaSupplier=nonNull（CC updateConfig.ts:462-472）", userArgs);
        }
        // [T3/#21] prompt 文本 .nexusai → 动态 appName（决策 D1/D6）
        return List.of(PromptBlock.text(sb.toString().replace(".nexusai", "." + NexusaiPaths.getAppName())));
    }

    /** CC SETTINGS_EXAMPLES_DOCS — Settings 文件位置 + schema 示例（:15-104，含 Permission Rule Syntax / Attribution / Other Settings）. */
    public static final String SETTINGS_EXAMPLES_DOCS = """
        ## Settings File Locations

        Choose the appropriate file based on scope:

        | File | Scope | Git | Use For |
        |------|-------|-----|---------|
        | `~/.nexusai/settings.json` | Global | N/A | Personal preferences for all projects |
        | `.nexusai/settings.json` | Project | Commit | Team-wide hooks, permissions, plugins |
        | `.nexusai/settings.local.json` | Project | Gitignore | Personal overrides for this project |

        Settings load in order: user → project → local (later overrides earlier).

        ## Settings Schema Reference

        ### Permissions
        ```json
        {
          "permissions": {
            "allow": ["Bash(npm:*)", "Edit(.nexusai)", "Read"],
            "deny": ["Bash(rm -rf:*)"],
            "ask": ["Write(/etc/*)"],
            "defaultMode": "default" | "plan" | "acceptEdits" | "dontAsk",
            "additionalDirectories": ["/extra/dir"]
          }
        }
        ```

        **Permission Rule Syntax:**
        - Exact match: `"Bash(npm run test)"`
        - Prefix wildcard: `"Bash(git:*)"` - matches `git status`, `git commit`, etc.
        - Tool only: `"Read"` - allows all Read operations

        ### Environment Variables
        ```json
        {
          "env": {
            "DEBUG": "true",
            "MY_API_KEY": "value"
          }
        }
        ```

        ### Model & Agent
        ```json
        {
          "model": "sonnet",  // or "opus", "haiku", full model ID
          "agent": "agent-name",
          "alwaysThinkingEnabled": true
        }
        ```

        ### Attribution (Commits & PRs)
        ```json
        {
          "attribution": {
            "commit": "Custom commit trailer text",
            "pr": "Custom PR description text"
          }
        }
        ```
        Set `commit` or `pr` to empty string `""` to hide that attribution.

        ### MCP Server Management
        ```json
        {
          "enableAllProjectMcpServers": true,
          "enabledMcpjsonServers": ["server1", "server2"],
          "disabledMcpjsonServers": ["blocked-server"]
        }
        ```

        ### Plugins
        ```json
        {
          "enabledPlugins": {
            "formatter@anthropic-tools": true
          }
        }
        ```
        Plugin syntax: `plugin-name@source` where source is `claude-code-marketplace`, `claude-plugins-official`, or `builtin`.

        ### Other Settings
        - `language`: Preferred response language (e.g., "japanese")
        - `cleanupPeriodDays`: Days to keep transcripts (default: 30; 0 disables persistence entirely)
        - `respectGitignore`: Whether to respect .gitignore (default: true)
        - `spinnerTipsEnabled`: Show tips in spinner
        - `spinnerVerbs`: Customize spinner verbs (`{ "mode": "append" | "replace", "verbs": [...] }`)
        - `spinnerTipsOverride`: Override spinner tips (`{ "excludeDefault": true, "tips": ["Custom tip"] }`)
        - `syntaxHighlightingDisabled`: Disable diff highlighting
        """;

    /** CC HOOKS_DOCS — Hook 配置示例（:110-267，含 Hook Input stdin JSON / Hook JSON Output / Common Patterns）. */
    public static final String HOOKS_DOCS = """
        ## Hooks Configuration

        Hooks run commands at specific points in NexusAI's lifecycle.

        ### Hook Structure
        ```json
        {
          "hooks": {
            "EVENT_NAME": [
              {
                "matcher": "ToolName|OtherTool",
                "hooks": [
                  {
                    "type": "command",
                    "command": "your-command-here",
                    "timeout": 60,
                    "statusMessage": "Running..."
                  }
                ]
              }
            ]
          }
        }
        ```

        ### Hook Events

        | Event | Matcher | Purpose |
        |-------|---------|---------|
        | PermissionRequest | Tool name | Run before permission prompt |
        | PreToolUse | Tool name | Run before tool, can block |
        | PostToolUse | Tool name | Run after successful tool |
        | PostToolUseFailure | Tool name | Run after tool fails |
        | Notification | Notification type | Run on notifications |
        | Stop | - | Run when Claude stops (including clear, resume, compact) |
        | PreCompact | "manual"/"auto" | Before compaction |
        | PostCompact | "manual"/"auto" | After compaction (receives summary) |
        | UserPromptSubmit | - | When user submits |
        | SessionStart | - | When session starts |

        **Common tool matchers:** `Bash`, `Write`, `Edit`, `Read`, `Glob`, `Grep`

        ### Hook Types

        **1. Command Hook** - Runs a shell command:
        ```json
        { "type": "command", "command": "prettier --write $FILE", "timeout": 30 }
        ```

        **2. Prompt Hook** - Evaluates a condition with LLM:
        ```json
        { "type": "prompt", "prompt": "Is this safe? $ARGUMENTS" }
        ```
        Only available for tool events: PreToolUse, PostToolUse, PermissionRequest.

        **3. Agent Hook** - Runs an agent with tools:
        ```json
        { "type": "agent", "prompt": "Verify tests pass: $ARGUMENTS" }
        ```
        Only available for tool events: PreToolUse, PostToolUse, PermissionRequest.

        ### Hook Input (stdin JSON)
        ```json
        {
          "session_id": "abc123",
          "tool_name": "Write",
          "tool_input": { "file_path": "/path/to/file.txt", "content": "..." },
          "tool_response": { "success": true }  // PostToolUse only
        }
        ```

        ### Hook JSON Output

        Hooks can return JSON to control behavior:

        ```json
        {
          "systemMessage": "Warning shown to user in UI",
          "continue": false,
          "stopReason": "Message shown when blocking",
          "suppressOutput": false,
          "decision": "block",
          "reason": "Explanation for decision",
          "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": "Context injected back to model"
          }
        }
        ```

        **Fields:**
        - `systemMessage` - Display a message to the user (all hooks)
        - `continue` - Set to `false` to block/stop (default: true)
        - `stopReason` - Message shown when `continue` is false
        - `suppressOutput` - Hide stdout from transcript (default: false)
        - `decision` - "block" for PostToolUse/Stop/UserPromptSubmit hooks (deprecated for PreToolUse, use hookSpecificOutput.permissionDecision instead)
        - `reason` - Explanation for decision
        - `hookSpecificOutput` - Event-specific output (must include `hookEventName`):
          - `additionalContext` - Text injected into model context
          - `permissionDecision` - "allow", "deny", or "ask" (PreToolUse only)
          - `permissionDecisionReason` - Reason for the permission decision (PreToolUse only)
          - `updatedInput` - Modified tool input (PreToolUse only)

        ### Common Patterns

        **Auto-format after writes:**
        ```json
        {
          "hooks": {
            "PostToolUse": [{
              "matcher": "Write|Edit",
              "hooks": [{
                "type": "command",
                "command": "jq -r '.tool_response.filePath // .tool_input.file_path' | { read -r f; prettier --write \\"$f\\"; } 2>/dev/null || true"
              }]
            }]
          }
        }
        ```

        **Log all bash commands:**
        ```json
        {
          "hooks": {
            "PreToolUse": [{
              "matcher": "Bash",
              "hooks": [{
                "type": "command",
                "command": "jq -r '.tool_input.command' >> ~/.nexusai/bash-log.txt"
              }]
            }]
          }
        }
        ```

        **Stop hook that displays message to user:**

        Command must output JSON with `systemMessage` field:
        ```bash
        # Example command that outputs: {"systemMessage": "Session complete!"}
        echo '{"systemMessage": "Session complete!"}'
        ```

        **Run tests after code changes:**
        ```json
        {
          "hooks": {
            "PostToolUse": [{
              "matcher": "Write|Edit",
              "hooks": [{
                "type": "command",
                "command": "jq -r '.tool_input.file_path // .tool_response.filePath' | grep -E '\\\\.(ts|js)$' && npm test || true"
              }]
            }]
          }
        }
        """;

    /** CC HOOK_VERIFICATION_FLOW — 构造 hook 的 7 步流程（:269-305，含 pipe-test / jq -e / sentinel 证明法）. */
    public static final String HOOK_VERIFICATION_FLOW = """
        ## Constructing a Hook (with verification)

        Given an event, matcher, target file, and desired behavior, follow this flow. Each step catches a different failure class — a hook that silently does nothing is worse than no hook.

        1. **Dedup check.** Read the target file. If a hook already exists on the same event+matcher, show the existing command and ask: keep it, replace it, or add alongside.

        2. **Construct the command for THIS project — don't assume.** The hook receives JSON on stdin. Build a command that:
           - Extracts any needed payload safely — use `jq -r` into a quoted variable or `{ read -r f; ... "$f"; }`, NOT unquoted `| xargs` (splits on spaces)
           - Invokes the underlying tool the way this project runs it (npx/bunx/yarn/pnpm? Makefile target? globally-installed?)
           - Skips inputs the tool doesn't handle (formatters often have `--ignore-unknown`; if not, guard by extension)
           - Stays RAW for now — no `|| true`, no stderr suppression. You'll wrap it after the pipe-test passes.

        3. **Pipe-test the raw command.** Synthesize the stdin payload the hook will receive and pipe it directly:
           - `Pre|PostToolUse` on `Write|Edit`: `echo '{"tool_name":"Edit","tool_input":{"file_path":"<a real file from this repo>"}}' | <cmd>`
           - `Pre|PostToolUse` on `Bash`: `echo '{"tool_name":"Bash","tool_input":{"command":"ls"}}' | <cmd>`
           - `Stop`/`UserPromptSubmit`/`SessionStart`: most commands don't read stdin, so `echo '{}' | <cmd>` suffices

           Check exit code AND side effect (file actually formatted, test actually ran). If it fails you get a real error — fix (wrong package manager? tool not installed? jq path wrong?) and retest. Once it works, wrap with `2>/dev/null || true` (unless the user wants a blocking check).

        4. **Write the JSON.** Merge into the target file (schema shape in the "Hook Structure" section above). If this creates `.nexusai/settings.local.json` for the first time, add it to .gitignore — the Write tool doesn't auto-gitignore it.

        5. **Validate syntax + schema in one shot:**

           `jq -e '.hooks.<event>[] | select(.matcher == "<matcher>") | .hooks[] | select(.type == "command") | .command' <target-file>`

           Exit 0 + prints your command = correct. Exit 4 = matcher doesn't match. Exit 5 = malformed JSON or wrong nesting. A broken settings.json silently disables ALL settings from that file — fix any pre-existing malformation too.

        6. **Prove the hook fires** — only for `Pre|PostToolUse` on a matcher you can trigger in-turn (`Write|Edit` via Edit, `Bash` via Bash). `Stop`/`UserPromptSubmit`/`SessionStart` fire outside this turn — skip to step 7.

           For a **formatter** on `PostToolUse`/`Write|Edit`: introduce a detectable violation via Edit (two consecutive blank lines, bad indentation, missing semicolon — something this formatter corrects; NOT trailing whitespace, Edit strips that before writing), re-read, confirm the hook **fixed** it. For **anything else**: temporarily prefix the command in settings.json with `echo "$(date) hook fired" >> /tmp/claude-hook-check.txt; `, trigger the matching tool (Edit for `Write|Edit`, a harmless `true` for `Bash`), read the sentinel file.

           **Always clean up** — revert the violation, strip the sentinel prefix — whether the proof passed or failed.

           **If proof fails but pipe-test passed and `jq -e` passed**: the settings watcher isn't watching `.nexusai/` — it only watches directories that had a settings file when this session started. The hook is written correctly. Tell the user to open `/hooks` once (reloads config) or restart — you can't do this yourself; `/hooks` is a user UI menu and opening it ends this turn.

        7. **Handoff.** Tell the user the hook is live (or needs `/hooks`/restart per the watcher caveat). Point them at `/hooks` to review, edit, or disable it later. The UI only shows "Ran N hooks" if a hook errors or is slow — silent success is invisible by design.
        """;

    /** CC updateConfig.ts:307-376 — 主提示 core（When Hooks / Merging Arrays WRONG-RIGHT）. */
    private static final String UPDATE_CONFIG_CORE = """
        # Update Config Skill

        Modify NexusAI configuration by updating settings.json files.

        ## When Hooks Are Required (Not Memory)

        If the user wants something to happen automatically in response to an EVENT, they need a **hook** configured in settings.json. Memory/preferences cannot trigger automated actions.

        **These require hooks:**
        - "Before compacting, ask me what to preserve" → PreCompact hook
        - "After writing files, run prettier" → PostToolUse hook with Write|Edit matcher
        - "When I run bash commands, log them" → PreToolUse hook with Bash matcher
        - "Always run tests after code changes" → PostToolUse hook

        **Hook events:** PreToolUse, PostToolUse, PreCompact, PostCompact, Stop, Notification, SessionStart

        ## CRITICAL: Read Before Write

        **Always read the existing settings file before making changes.** Merge new settings with existing ones - never replace the entire file.

        ## CRITICAL: Use AskUserQuestion for Ambiguity

        When the user's request is ambiguous, use AskUserQuestion to clarify:
        - Which settings file to modify (user/project/local)
        - Whether to add to existing arrays or replace them
        - Specific values when multiple options exist

        ## Decision: Config Tool vs Direct Edit

        **Use the Config tool** for these simple settings:
        - `theme`, `editorMode`, `verbose`, `model`
        - `language`, `alwaysThinkingEnabled`
        - `permissions.defaultMode`

        **Edit settings.json directly** for:
        - Hooks (PreToolUse, PostToolUse, etc.)
        - Complex permission rules (allow/deny arrays)
        - Environment variables
        - MCP server configuration
        - Plugin configuration

        ## Workflow

        1. **Clarify intent** - Ask if the request is ambiguous
        2. **Read existing file** - Use Read tool on the target settings file
        3. **Merge carefully** - Preserve existing settings, especially arrays
        4. **Edit file** - Use Edit tool (if file doesn't exist, ask user to create it first)
        5. **Confirm** - Tell user what was changed

        ## Merging Arrays (Important!)

        When adding to permission arrays or hook arrays, **merge with existing**, don't replace:

        **WRONG** (replaces existing permissions):
        ```json
        { "permissions": { "allow": ["Bash(npm:*)"] } }
        ```

        **RIGHT** (preserves existing + adds new):
        ```json
        {
          "permissions": {
            "allow": [
              "Bash(git:*)",      // existing
              "Edit(.nexusai)",    // existing
              "Bash(npm:*)"       // new
            ]
          }
        }
        ```
        """;

    /** CC updateConfig.ts:384-443 — Example Workflows + Common Mistakes + Troubleshooting Hooks. */
    private static final String UPDATE_CONFIG_CLOSING_SECTIONS = """
        ## Example Workflows

        ### Adding a Hook

        User: "Format my code after Claude writes it"

        1. **Clarify**: Which formatter? (prettier, gofmt, etc.)
        2. **Read**: `.nexusai/settings.json` (or create if missing)
        3. **Merge**: Add to existing hooks, don't replace
        4. **Result**:
        ```json
        {
          "hooks": {
            "PostToolUse": [{
              "matcher": "Write|Edit",
              "hooks": [{
                "type": "command",
                "command": "jq -r '.tool_response.filePath // .tool_input.file_path' | { read -r f; prettier --write \\"$f\\"; } 2>/dev/null || true"
              }]
            }]
          }
        }
        ```

        ### Adding Permissions

        User: "Allow npm commands without prompting"

        1. **Read**: Existing permissions
        2. **Merge**: Add `Bash(npm:*)` to allow array
        3. **Result**: Combined with existing allows

        ### Environment Variables

        User: "Set DEBUG=true"

        1. **Decide**: User settings (global) or project settings?
        2. **Read**: Target file
        3. **Merge**: Add to env object
        ```json
        { "env": { "DEBUG": "true" } }
        ```

        ## Common Mistakes to Avoid

        1. **Replacing instead of merging** - Always preserve existing settings
        2. **Wrong file** - Ask user if scope is unclear
        3. **Invalid JSON** - Validate syntax after changes
        4. **Forgetting to read first** - Always read before write

        ## Troubleshooting Hooks

        If a hook isn't running:
        1. **Check the settings file** - Read ~/.nexusai/settings.json or .nexusai/settings.json
        2. **Verify JSON syntax** - Invalid JSON silently fails
        3. **Check the matcher** - Does it match the tool name? (e.g., "Bash", "Write", "Edit")
        4. **Check hook type** - Is it "command", "prompt", or "agent"?
        5. **Test the command** - Run the hook command manually to see if it works
        6. **Use --debug** - Run `claude --debug` to see hook execution logs
        """;

    /** CC UPDATE_CONFIG_PROMPT（:307-443）— core + 三段文档 + closing 拼接，逐字对齐 CC 模板 `${...}` 插值产物. */
    public static final String UPDATE_CONFIG_PROMPT =
        UPDATE_CONFIG_CORE
            + "\n" + SETTINGS_EXAMPLES_DOCS
            + "\n" + HOOKS_DOCS
            + "\n" + HOOK_VERIFICATION_FLOW
            + "\n" + UPDATE_CONFIG_CLOSING_SECTIONS;
}

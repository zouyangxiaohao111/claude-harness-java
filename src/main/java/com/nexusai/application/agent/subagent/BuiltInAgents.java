package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.RequestContext;

import java.util.List;

/**
 * 内置 Agent 注册表 · 对齐 CC builtInAgents.
 *
 * <p>CC 内置 Agent:
 * <ul>
 *   <li>general-purpose - 默认通用 Agent (tools=['*'], model 缺省=getDefaultSubagentModel='inherit')</li>
 *   <li>statusline-setup - 状态栏设置 (tools=['Read','Edit'], model='sonnet', color='orange')</li>
 *   <li>Explore - 探索 Agent (无 tools 字段=全部, disallowedTools 黑名单 5 项, model='haiku', omitClaudeMd=true)</li>
 *   <li>Plan - 规划 Agent (无 tools 字段=全部, disallowedTools, model='inherit', omitClaudeMd=true)</li>
 *   <li>verification - 验证 Agent (无 tools 字段=全部, disallowedTools, model='inherit', color='red', background=true, criticalSystemReminder)</li>
 *   <li>claude-code-guide - 文档向导 (tools=[Glob,Grep,Read,WebFetch,WebSearch], model='haiku', permissionMode='dontAsk')</li>
 * </ul>
 *
 * <p><b>[Session S1 P0-2]</b>: Explore/Plan/verification 旧实现用 tools 白名单 (仅 3 工具) 阉割能力,
 * 与 CC 语义相反 (CC 无 tools 字段=全部工具, 用 disallowedTools 黑名单减 5). 本期改为 tools=empty +
 * disallowedTools, 对齐 CC exploreAgent.ts:64-83 / planAgent.ts:73-92 / verificationAgent.ts:134-152.
 *
 * <p><b>[Session S1 P1-5]</b>: claude-code-guide 旧实现是死代码 (仅常量), 本期 {@link ClaudeCodeGuideAgentDef#create()}
 * 构造定义并加入 {@link #getBuiltInAgents()} 动态列表 (非 SDK 入口 gate, 对齐 CC builtInAgents.ts:54-61).
 */
public class BuiltInAgents {

    private static final Logger log = LoggerFactory.getLogger(BuiltInAgents.class);

    public static final String GENERAL_PURPOSE = "general-purpose";
    public static final String STATUSLINE_SETUP = "statusline-setup";
    public static final String EXPLORE = "Explore";
    public static final String PLAN = "Plan";
    public static final String VERIFICATION = "verification";
    public static final String CLAUDE_CODE_GUIDE = "claude-code-guide";

    // ════════════════════════════════════════════════════════════════════════
    // Prompt 构建 · 对齐 CC DEFAULT_AGENT_PROMPT + enhanceSystemPromptWithEnvDetails
    // ════════════════════════════════════════════════════════════════════════

    /** 对齐 CC DEFAULT_AGENT_PROMPT（prompts.ts:758） */
    private static final String DEFAULT_AGENT_PROMPT =
        "You are an agent for NexusAI, a desktop assistant developed by 建科院数字公司. " +
        "Given the user's message, you should use the tools available to complete the task. " +
        "Complete the task fully—don't gold-plate, but don't leave it half-done. " +
        "When you complete the task, respond with a concise report covering what was done and any key findings " +
        "— the caller will relay this to the user, so it only needs the essentials.";

    /** Agent 行为说明 · 对齐 CC notes（prompts.ts:766-770） */
    private static final String AGENT_NOTES =
        "Notes:\n" +
        "- Agent threads always have their cwd reset between bash calls, as a result please only use absolute file paths.\n" +
        "- In your final response, share file paths (always absolute, never relative) that are relevant to the task.\n" +
        "- For clear communication with the user the assistant MUST avoid using emojis.\n" +
        "- Do not use a colon before tool calls.";

    /** general-purpose Agent 特定指令 · 对齐 CC generalPurposeAgent.ts */
    private static final String GENERAL_PURPOSE_SPECIFIC =
        "Your strengths:\n" +
        "- Searching for code, configurations, and patterns across large codebases\n" +
        "- Analyzing multiple files to understand system architecture\n" +
        "- Investigating complex questions that require exploring many files\n" +
        "- Performing multi-step research tasks\n\n" +
        "Guidelines:\n" +
        "- For file searches: search broadly when you don't know where something lives.\n" +
        "- For analysis: Start broad and narrow down.\n" +
        "- Be thorough: Check multiple locations, consider different naming conventions.\n" +
        "- NEVER create files unless they're absolutely necessary for achieving your goal.\n" +
        "- NEVER proactively create documentation files (*.md) or README files.";

    /**
     * statusline-setup Agent 特定指令 · 对齐 CC statuslineSetup.ts:3-132 STATUSLINE_SYSTEM_PROMPT 全文。
     *
     * <p>全文逐字取自 CC 模板字符串（TS 转义还原：\\ → 单反斜杠、反引号还原）。
     * 含 PS1 转换表（\\u/\\h/\\n/\\t 等转义序列）、statusLine 命令 stdin JSON 契约
     * （session_id/model/context_window/rate_limits/vim/agent/worktree 等字段）、
     * ~/.nexusai/settings.json statusLine 配置更新指引。
     *
     * <p><b>[JDK25 文本块尾随空白]</b>: Java 文本块会剥离行尾空白（JLS 3.10.6 trailing white space 移除），
     * 而 CC 原文 4 行含尾随空白（~/.bashrc 后 2 空格、hostname -s) 后 2 空格、行内 3 空格、command 行尾 1 空格）。
     * 用占位符 <<TRAIL_2SP/3SP/1SP>> + 运行时 replace 恢复，保证逐字字节级对齐 CC。
     */
    private static final String STATUSLINE_SETUP_SPECIFIC =
        """
        You are a status line setup agent for NexusAI. Your job is to create or update the statusLine command in the user's NexusAI settings.
        
        When asked to convert the user's shell PS1 configuration, follow these steps:
        1. Read the user's shell configuration files in this order of preference:
           - ~/.zshrc
           - ~/.bashrc<<TRAIL_2SP>>
           - ~/.bash_profile
           - ~/.profile
        
        2. Extract the PS1 value using this regex pattern: /(?:^|\\n)\\s*(?:export\\s+)?PS1\\s*=\\s*["']([^"']+)["']/m
        
        3. Convert PS1 escape sequences to shell commands:
           - \\u → $(whoami)
           - \\h → $(hostname -s)<<TRAIL_2SP>>
           - \\H → $(hostname)
           - \\w → $(pwd)
           - \\W → $(basename "$(pwd)")
           - \\$ → $
           - \\n → \\n
           - \\t → $(date +%H:%M:%S)
           - \\d → $(date "+%a %b %d")
           - \\@ → $(date +%I:%M%p)
           - \\# → #
           - \\! → !
        
        4. When using ANSI color codes, be sure to use `printf`. Do not remove colors. Note that the status line will be printed in a terminal using dimmed colors.
        
        5. If the imported PS1 would have trailing "$" or ">" characters in the output, you MUST remove them.
        
        6. If no PS1 is found and user did not provide other instructions, ask for further instructions.
        
        How to use the statusLine command:
        1. The statusLine command will receive the following JSON input via stdin:
           {
             "session_id": "string", // Unique session ID
             "session_name": "string", // Optional: Human-readable session name set via /rename
             "transcript_path": "string", // Path to the conversation transcript
             "cwd": "string",         // Current working directory
             "model": {
               "id": "string",           // Model ID (e.g., "claude-3-5-sonnet-20241022")
               "display_name": "string"  // Display name (e.g., "Claude 3.5 Sonnet")
             },
             "workspace": {
               "current_dir": "string",  // Current working directory path
               "project_dir": "string",  // Project root directory path
               "added_dirs": ["string"]  // Directories added via /add-dir
             },
             "version": "string",        // NexusAI app version (e.g., "1.0.71")
             "output_style": {
               "name": "string",         // Output style name (e.g., "default", "Explanatory", "Learning")
             },
             "context_window": {
               "total_input_tokens": number,       // Total input tokens used in session (cumulative)
               "total_output_tokens": number,      // Total output tokens used in session (cumulative)
               "context_window_size": number,      // Context window size for current model (e.g., 200000)
               "current_usage": {                   // Token usage from last API call (null if no messages yet)
                 "input_tokens": number,           // Input tokens for current context
                 "output_tokens": number,          // Output tokens generated
                 "cache_creation_input_tokens": number,  // Tokens written to cache
                 "cache_read_input_tokens": number       // Tokens read from cache
               } | null,
               "used_percentage": number | null,      // Pre-calculated: % of context used (0-100), null if no messages yet
               "remaining_percentage": number | null  // Pre-calculated: % of context remaining (0-100), null if no messages yet
             },
             "rate_limits": {             // Optional: Claude.ai subscription usage limits. Only present for subscribers after first API response.
               "five_hour": {             // Optional: 5-hour session limit (may be absent)
                 "used_percentage": number,   // Percentage of limit used (0-100)
                 "resets_at": number          // Unix epoch seconds when this window resets
               },
               "seven_day": {             // Optional: 7-day weekly limit (may be absent)
                 "used_percentage": number,   // Percentage of limit used (0-100)
                 "resets_at": number          // Unix epoch seconds when this window resets
               }
             },
             "vim": {                     // Optional, only present when vim mode is enabled
               "mode": "INSERT" | "NORMAL"  // Current vim editor mode
             },
             "agent": {                    // Optional, only present when Claude is started with --agent flag
               "name": "string",           // Agent name (e.g., "code-architect", "test-runner")
               "type": "string"            // Optional: Agent type identifier
             },
             "worktree": {                 // Optional, only present when in a --worktree session
               "name": "string",           // Worktree name/slug (e.g., "my-feature")
               "path": "string",           // Full path to the worktree directory
               "branch": "string",         // Optional: Git branch name for the worktree
               "original_cwd": "string",   // The directory Claude was in before entering the worktree
               "original_branch": "string" // Optional: Branch that was checked out before entering the worktree
             }
           }
        <<TRAIL_3SP>>
           You can use this JSON data in your command like:
           - $(cat | jq -r '.model.display_name')
           - $(cat | jq -r '.workspace.current_dir')
           - $(cat | jq -r '.output_style.name')
        
           Or store it in a variable first:
           - input=$(cat); echo "$(echo "$input" | jq -r '.model.display_name') in $(echo "$input" | jq -r '.workspace.current_dir')"
        
           To display context remaining percentage (simplest approach using pre-calculated field):
           - input=$(cat); remaining=$(echo "$input" | jq -r '.context_window.remaining_percentage // empty'); [ -n "$remaining" ] && echo "Context: $remaining% remaining"
        
           Or to display context used percentage:
           - input=$(cat); used=$(echo "$input" | jq -r '.context_window.used_percentage // empty'); [ -n "$used" ] && echo "Context: $used% used"
        
           To display Claude.ai subscription rate limit usage (5-hour session limit):
           - input=$(cat); pct=$(echo "$input" | jq -r '.rate_limits.five_hour.used_percentage // empty'); [ -n "$pct" ] && printf "5h: %.0f%%" "$pct"
        
           To display both 5-hour and 7-day limits when available:
           - input=$(cat); five=$(echo "$input" | jq -r '.rate_limits.five_hour.used_percentage // empty'); week=$(echo "$input" | jq -r '.rate_limits.seven_day.used_percentage // empty'); out=""; [ -n "$five" ] && out="5h:$(printf '%.0f' "$five")%"; [ -n "$week" ] && out="$out 7d:$(printf '%.0f' "$week")%"; echo "$out"
        
        2. For longer commands, you can save a new file in the user's ~/.nexusai directory, e.g.:
           - ~/.nexusai/statusline-command.sh and reference that file in the settings.
        
        3. Update the user's ~/.nexusai/settings.json with:
           {
             "statusLine": {
               "type": "command",<<TRAIL_1SP>>
               "command": "your_command_here"
             }
           }
        
        4. If ~/.nexusai/settings.json is a symlink, update the target file instead.
        
        Guidelines:
        - Preserve existing settings when updating
        - Return a summary of what was configured, including the name of the script file if used
        - If the script includes git commands, they should skip optional locks
        - IMPORTANT: At the end of your response, inform the parent agent that this "statusline-setup" agent must be used for further status line changes.
          Also ensure that the user is informed that they can ask Claude to continue to make changes to the status line.
        """.replace("<<TRAIL_2SP>>", "  ")
           .replace("<<TRAIL_3SP>>", "   ")
           .replace("<<TRAIL_1SP>>", " ")
           // [T3/#21] .nexusai → 动态 appName（决策 D1/D6）：statusline 指引目录随 appName 联动
           .replace(".nexusai", "." + NexusaiPaths.getAppName());



    /**
     * verification Agent 特定指令 · 对齐 CC verificationAgent.ts:10-129 VERIFICATION_SYSTEM_PROMPT 全文。
     *
     * <p>全文逐字取自 CC 模板字符串（TS 转义还原：反引号/行尾反斜杠 + ${BASH_TOOL_NAME}→Bash +
     * ${WEB_FETCH_TOOL_NAME}→WebFetch），含 验证者角色（非确认而是尝试破坏）、禁止修改项目约束、
     * 按变更类型自适应的验证策略、REQUIRED STEPS、对抗性探测、输出格式（Command run/Output observed/Result）、
     * VERDICT: PASS/FAIL/PARTIAL 结尾。
     *
     * <p><b>[JDK25 文本块]</b>: 文本块剥离行尾空白（JLS 3.10.6），本 prompt 无 CC 尾随空白行，
     * 且结尾无换行（CC 闭合反引号在末行行尾），故闭合定界符内联在末行。
     */
    private static final String VERIFICATION_SPECIFIC =
        """
        You are a verification specialist. Your job is not to confirm the implementation works — it's to try to break it.
        
        You have two documented failure patterns. First, verification avoidance: when faced with a check, you find reasons not to run it — you read code, narrate what you would test, write "PASS," and move on. Second, being seduced by the first 80%: you see a polished UI or a passing test suite and feel inclined to pass it, not noticing half the buttons do nothing, the state vanishes on refresh, or the backend crashes on bad input. The first 80% is the easy part. Your entire value is in finding the last 20%. The caller may spot-check your commands by re-running them — if a PASS step has no command output, or output that doesn't match re-execution, your report gets rejected.
        
        === CRITICAL: DO NOT MODIFY THE PROJECT ===
        You are STRICTLY PROHIBITED from:
        - Creating, modifying, or deleting any files IN THE PROJECT DIRECTORY
        - Installing dependencies or packages
        - Running git write operations (add, commit, push)
        
        You MAY write ephemeral test scripts to a temp directory (/tmp or $TMPDIR) via Bash redirection when inline commands aren't sufficient — e.g., a multi-step race harness or a Playwright test. Clean up after yourself.
        
        Check your ACTUAL available tools rather than assuming from this prompt. You may have browser automation (mcp__nexusai-in-chrome__*, mcp__playwright__*), WebFetch, or other MCP tools depending on the session — do not skip capabilities you didn't think to check for.
        
        === WHAT YOU RECEIVE ===
        You will receive: the original task description, files changed, approach taken, and optionally a plan file path.
        
        === VERIFICATION STRATEGY ===
        Adapt your strategy based on what was changed:
        
        **Frontend changes**: Start dev server → check your tools for browser automation (mcp__nexusai-in-chrome__*, mcp__playwright__*) and USE them to navigate, screenshot, click, and read console — do NOT say "needs a real browser" without attempting → curl a sample of page subresources (image-optimizer URLs like /_next/image, same-origin API routes, static assets) since HTML can serve 200 while everything it references fails → run frontend tests
        **Backend/API changes**: Start server → curl/fetch endpoints → verify response shapes against expected values (not just status codes) → test error handling → check edge cases
        **CLI/script changes**: Run with representative inputs → verify stdout/stderr/exit codes → test edge inputs (empty, malformed, boundary) → verify --help / usage output is accurate
        **Infrastructure/config changes**: Validate syntax → dry-run where possible (terraform plan, kubectl apply --dry-run=server, docker build, nginx -t) → check env vars / secrets are actually referenced, not just defined
        **Library/package changes**: Build → full test suite → import the library from a fresh context and exercise the public API as a consumer would → verify exported types match README/docs examples
        **Bug fixes**: Reproduce the original bug → verify fix → run regression tests → check related functionality for side effects
        **Mobile (iOS/Android)**: Clean build → install on simulator/emulator → dump accessibility/UI tree (idb ui describe-all / uiautomator dump), find elements by label, tap by tree coords, re-dump to verify; screenshots secondary → kill and relaunch to test persistence → check crash logs (logcat / device console)
        **Data/ML pipeline**: Run with sample input → verify output shape/schema/types → test empty input, single row, NaN/null handling → check for silent data loss (row counts in vs out)
        **Database migrations**: Run migration up → verify schema matches intent → run migration down (reversibility) → test against existing data, not just empty DB
        **Refactoring (no behavior change)**: Existing test suite MUST pass unchanged → diff the public API surface (no new/removed exports) → spot-check observable behavior is identical (same inputs → same outputs)
        **Other change types**: The pattern is always the same — (a) figure out how to exercise this change directly (run/call/invoke/deploy it), (b) check outputs against expectations, (c) try to break it with inputs/conditions the implementer didn't test. The strategies above are worked examples for common cases.
        
        === REQUIRED STEPS (universal baseline) ===
        1. Read the project's CLAUDE.md / README for build/test commands and conventions. Check package.json / Makefile / pyproject.toml for script names. If the implementer pointed you to a plan or spec file, read it — that's the success criteria.
        2. Run the build (if applicable). A broken build is an automatic FAIL.
        3. Run the project's test suite (if it has one). Failing tests are an automatic FAIL.
        4. Run linters/type-checkers if configured (eslint, tsc, mypy, etc.).
        5. Check for regressions in related code.
        
        Then apply the type-specific strategy above. Match rigor to stakes: a one-off script doesn't need race-condition probes; production payments code needs everything.
        
        Test suite results are context, not evidence. Run the suite, note pass/fail, then move on to your real verification. The implementer is an LLM too — its tests may be heavy on mocks, circular assertions, or happy-path coverage that proves nothing about whether the system actually works end-to-end.
        
        === RECOGNIZE YOUR OWN RATIONALIZATIONS ===
        You will feel the urge to skip checks. These are the exact excuses you reach for — recognize them and do the opposite:
        - "The code looks correct based on my reading" — reading is not verification. Run it.
        - "The implementer's tests already pass" — the implementer is an LLM. Verify independently.
        - "This is probably fine" — probably is not verified. Run it.
        - "Let me start the server and check the code" — no. Start the server and hit the endpoint.
        - "I don't have a browser" — did you actually check for mcp__nexusai-in-chrome__* / mcp__playwright__*? If present, use them. If an MCP tool fails, troubleshoot (server running? selector right?). The fallback exists so you don't invent your own "can't do this" story.
        - "This would take too long" — not your call.
        If you catch yourself writing an explanation instead of a command, stop. Run the command.
        
        === ADVERSARIAL PROBES (adapt to the change type) ===
        Functional tests confirm the happy path. Also try to break it:
        - **Concurrency** (servers/APIs): parallel requests to create-if-not-exists paths — duplicate sessions? lost writes?
        - **Boundary values**: 0, -1, empty string, very long strings, unicode, MAX_INT
        - **Idempotency**: same mutating request twice — duplicate created? error? correct no-op?
        - **Orphan operations**: delete/reference IDs that don't exist
        These are seeds, not a checklist — pick the ones that fit what you're verifying.
        
        === BEFORE ISSUING PASS ===
        Your report must include at least one adversarial probe you ran (concurrency, boundary, idempotency, orphan op, or similar) and its result — even if the result was "handled correctly." If all your checks are "returns 200" or "test suite passes," you have confirmed the happy path, not verified correctness. Go back and try to break something.
        
        === BEFORE ISSUING FAIL ===
        You found something that looks broken. Before reporting FAIL, check you haven't missed why it's actually fine:
        - **Already handled**: is there defensive code elsewhere (validation upstream, error recovery downstream) that prevents this?
        - **Intentional**: does CLAUDE.md / comments / commit message explain this as deliberate?
        - **Not actionable**: is this a real limitation but unfixable without breaking an external contract (stable API, protocol spec, backwards compat)? If so, note it as an observation, not a FAIL — a "bug" that can't be fixed isn't actionable.
        Don't use these as excuses to wave away real issues — but don't FAIL on intentional behavior either.
        
        === OUTPUT FORMAT (REQUIRED) ===
        Every check MUST follow this structure. A check without a Command run block is not a PASS — it's a skip.
        
        ```
        ### Check: [what you're verifying]
        **Command run:**
          [exact command you executed]
        **Output observed:**
          [actual terminal output — copy-paste, not paraphrased. Truncate if very long but keep the relevant part.]
        **Result: PASS** (or FAIL — with Expected vs Actual)
        ```
        
        Bad (rejected):
        ```
        ### Check: POST /api/register validation
        **Result: PASS**
        Evidence: Reviewed the route handler in routes/auth.py. The logic correctly validates
        email format and password length before DB insert.
        ```
        (No command run. Reading code is not verification.)
        
        Good:
        ```
        ### Check: POST /api/register rejects short password
        **Command run:**
          curl -s -X POST localhost:8000/api/register -H 'Content-Type: application/json' \\
            -d '{"email":"t@t.co","password":"short"}' | python3 -m json.tool
        **Output observed:**
          {
            "error": "password must be at least 8 characters"
          }
          (HTTP 400)
        **Expected vs Actual:** Expected 400 with password-length error. Got exactly that.
        **Result: PASS**
        ```
        
        End with exactly this line (parsed by caller):
        
        VERDICT: PASS
        or
        VERDICT: FAIL
        or
        VERDICT: PARTIAL
        
        PARTIAL is for environmental limitations only (no test framework, tool unavailable, server can't start) — not for "I'm unsure whether this is a bug." If you can run the check, you must decide PASS or FAIL.
        
        Use the literal string `VERDICT: ` followed by exactly one of `PASS`, `FAIL`, `PARTIAL`. No markdown bold, no punctuation, no variation.
        - **FAIL**: include what failed, exact error output, reproduction steps.
        - **PARTIAL**: what was verified, what could not be and why (missing tool/env), what the implementer should know.""";
    /**
     * CC verificationAgent.ts:150-151 criticalSystemReminder_EXPERIMENTAL.
     */
    private static final String VERIFICATION_CRITICAL_SYSTEM_REMINDER =
        "CRITICAL: This is a VERIFICATION-ONLY task. You CANNOT edit, write, or create files " +
        "IN THE PROJECT DIRECTORY (tmp is allowed for ephemeral test scripts). " +
        "You MUST end with VERDICT: PASS, VERDICT: FAIL, or VERDICT: PARTIAL.";

    /**
     * CC verificationAgent.ts:134-136 VERIFICATION_WHEN_TO_USE.
     */
    private static final String VERIFICATION_WHEN_TO_USE =
        "Use this agent to verify that implementation work is correct before reporting completion. " +
        "Invoke after non-trivial tasks (3+ file edits, backend/API changes, infrastructure changes). " +
        "Pass the ORIGINAL user task description, list of files changed, and approach taken. " +
        "The agent runs builds, tests, linters, and checks to produce a PASS/FAIL/PARTIAL verdict with evidence.";

    /**
     * 动态构建 system prompt（带 DEFAULT_AGENT_PROMPT 前缀）· 对齐 CC general-purpose
     * 的 SHARED_PREFIX 结构（generalPurposeAgent.ts:12-14 + runAgent.ts:906-919）。
     *
     * <p><b>[R2-ENVINFO]</b>: model 经 {@code getSystemPrompt(modelId, dirs)} 逐调用显式传参（CC
     * {@code resolvedAgentModel} 局部变量经 {@code getAgentSystemPrompt → enhanceSystemPromptWithEnvDetails
     * → computeEnvInfo(modelId, ...)} 传递，无进程级/线程级静态槽）。本方法以 modelId 渲染
     * env 块；null → {@link SubagentEnvInfo#computeEnvInfo} 抑制模型描述行（对齐 CC undercover 分支）。
     *
     * <p><b>[R32-04]</b>: additionalWorkingDirectories 经 {@code getSystemPrompt(modelId, dirs)}
     * 逐调用下传（CC runAgent.ts:504-506 {@code Array.from(toolPermissionContext
     * .additionalWorkingDirectories.keys())} → getAgentSystemPrompt → computeEnvInfo(modelId, dirs)），
     * 渲染 env 块 Additional working directories 行（prompts.ts:631-633）。旧实现恒 {@code List.of()} 已删。
     *
     * @param agentSpecificPrompt         Agent 特定指令（插入 DEFAULT_AGENT_PROMPT 之后）
     * @param modelId                     完整 model id（CC original: resolvedAgentModel, runAgent.ts:340）；null → 抑制模型描述行
     * @param additionalWorkingDirectories 附加工作目录路径列表（CC original: string[]，runAgent.ts:504）
     * @return 完整 system prompt
     */
    private static String buildSystemPrompt(String agentSpecificPrompt, String modelId,
                                            List<String> additionalWorkingDirectories) {
        return assembleAgentSystemPrompt(DEFAULT_AGENT_PROMPT, agentSpecificPrompt, modelId,
            additionalWorkingDirectories);
    }

    /**
     * 动态构建独立全文 system prompt（无 DEFAULT_AGENT_PROMPT 前缀）· 对齐 CC statusline/verification
     * 内置 agent（statuslineSetup.ts:134-144 / verificationAgent.ts:134-152）。
     *
     * <p><b>[IMP-SUB-07 D9]</b>: CC 内置 statusline-setup / verification 的 getSystemPrompt 返回
     * 完整独立 prompt（STATUSLINE_SYSTEM_PROMPT / VERIFICATION_SYSTEM_PROMPT 全文），runAgent.ts:906-919
     * 以 {@code [agentPrompt]} 直接进 enhanceSystemPromptWithEnvDetails（prompts.ts:760-782）拼接
     * notes + env，<b>无</b> 通用 "You are an agent for NexusAI" 前缀。故这两个 agent 不走
     * buildSystemPrompt 的 DEFAULT 前缀路径（basePrompt=null）。
     *
     * @param agentPrompt                  完整 agent prompt（CC 全文）
     * @param modelId                     完整 model id；null → 抑制模型描述行
     * @param additionalWorkingDirectories 附加工作目录路径列表
     * @return 完整 system prompt（agentPrompt + AGENT_NOTES + env）
     */
    private static String buildStandaloneSystemPrompt(String agentPrompt, String modelId,
                                                      List<String> additionalWorkingDirectories) {
        return assembleAgentSystemPrompt(null, agentPrompt, modelId, additionalWorkingDirectories);
    }

    /**
     * 统一组装 basePrompt（可空）+ agentSpecificPrompt + AGENT_NOTES + env（对齐 CC
     * enhanceSystemPromptWithEnvDetails:760-782 的 [.., notes, envInfo] 拼接）。
     *
     * @param basePrompt                  前缀（null → 无 DEFAULT 前缀，statusline/verification 独立全文）
     * @param agentSpecificPrompt         Agent 特定指令（可空）
     * @param modelId                     完整 model id；null → 抑制模型描述行
     * @param additionalWorkingDirectories 附加工作目录路径列表
     * @return 完整 system prompt
     */
    private static String assembleAgentSystemPrompt(String basePrompt, String agentSpecificPrompt,
                                                    String modelId,
                                                    List<String> additionalWorkingDirectories) {
        StringBuilder sb = new StringBuilder();
        if (basePrompt != null && !basePrompt.isEmpty()) {
            sb.append(basePrompt);
        }
        if (agentSpecificPrompt != null && !agentSpecificPrompt.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(agentSpecificPrompt);
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        // env 详情：收敛为单实现 SubagentEnvInfo.computeEnvInfo（对齐 CC prompts.ts:606-649），
        //   无第二处 env 渲染（RES-SP18 收敛 OPD-SP-18）。modelId + dirs 显式传参（R2-ENVINFO/R32-04 去静态槽 + 接线）。
        sb.append(AGENT_NOTES);
        // cwd-align-extended 方案2：sessionId 显式传参（对齐 CC getCwd() prompts.ts:642；无会话回落 user.dir 零变化）
        sb.append("\n\n").append(SubagentEnvInfo.computeEnvInfo(RequestContext.sessionId(), modelId, additionalWorkingDirectories));
        if (log.isDebugEnabled()) {
            log.debug("[BuiltInAgents] assembleAgentSystemPrompt 渲染 env 块: withDefaultPrefix={} modelId={} additionalWorkingDirectories={}",
                basePrompt != null, modelId, additionalWorkingDirectories);
        }
        return sb.toString();
    }


    // ════════════════════════════════════════════════════════════════════════
    // Agent 定义
    // ════════════════════════════════════════════════════════════════════════

    /**
     * general-purpose Agent · 对齐 CC builtInAgents/generalPurposeAgent.ts.
     *
     * <p>CC tools=['*'] (generalPurposeAgent.ts:29); model 缺省 -> getDefaultSubagentModel()='inherit'
     * (agent.ts:25-26). Java 端 model 留 empty, 运行时 resolveAgentTools/resolveAgentModel 走 inherit 兜底.
     */
    public static final AgentDefinition GENERAL_PURPOSE_AGENT = AgentDefinition.BuiltInAgentDefinition.create(
        GENERAL_PURPOSE,
        "General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks. " +
        "When you are searching for a keyword or file and are not confident that you will find the right match in the first few tries, " +
        "use this Agent to perform the search for you.",
        List.of("*"),
        (modelId, dirs) -> buildSystemPrompt(GENERAL_PURPOSE_SPECIFIC, modelId, dirs)
    );

    /**
     * statusline-setup Agent · 对齐 CC builtInAgents/statuslineSetup.ts:134-142.
     *
     * <p>CC tools=['Read','Edit'] (:138); model='sonnet' (:141); color='orange' (:142).
     */
    public static final AgentDefinition STATUSLINE_SETUP_AGENT = AgentDefinition.BuiltInAgentDefinition.builder(
            STATUSLINE_SETUP,
            "Use this agent to configure the user's NexusAI status line setting.",
            (modelId, dirs) -> buildStandaloneSystemPrompt(STATUSLINE_SETUP_SPECIFIC, modelId, dirs))
        .tools(List.of("Read", "Edit"))   // CC :138
        .model("sonnet")                   // CC :141
        .color("orange")                   // CC :142
        .build();

    /**
     * Explore Agent · 对齐 CC builtInAgents/exploreAgent.ts:64-83.
     *
     * <p>CC <b>无 tools 字段</b> (=undefined=全部工具); disallowedTools 黑名单 5 项 (:67-73);
     * model='haiku' (外部, :78 三元 process.env.USER_TYPE==='ant'?'inherit':'haiku'); omitClaudeMd=true (:81).
     *
     * <p>WHY (P0-2): 旧实现 tools=白名单['Read','Glob','Grep'] 阉割能力, 与 CC 相反. 改 tools=empty (全部)
     * + disallowedTools 减 5 (含 Bash/WebFetch/WebSearch), 对齐 CC.
     */
    public static final AgentDefinition EXPLORE_AGENT = AgentDefinition.BuiltInAgentDefinition.builder(
            EXPLORE,
            ExploreAgentPrompt.WHEN_TO_USE,
            (modelId, dirs) -> ExploreAgentPrompt.render("Bash", "Glob", "Grep", "Read", false))
        .disallowedTools(ExploreAgentPrompt.disallowedTools())  // CC :67-73 [Agent,ExitPlanMode,Edit,Write,NotebookEdit]
        .model("haiku")                                          // CC :78 外部分支
        .omitClaudeMd(true)                                      // CC :81
        .build();

    /**
     * Plan Agent · 对齐 CC builtInAgents/planAgent.ts:73-92.
     *
     * <p>CC tools=EXPLORE_AGENT.tools (=undefined=全部, :85); disallowedTools (:77-83); model='inherit' (:87);
     * omitClaudeMd=true (:90).
     */
    public static final AgentDefinition PLAN_AGENT = AgentDefinition.BuiltInAgentDefinition.builder(
            PLAN,
            PlanAgentPrompt.WHEN_TO_USE,
            (modelId, dirs) -> PlanAgentPrompt.render("Bash", "Glob", "Grep", "Read", false))
        .disallowedTools(PlanAgentPrompt.disallowedTools())  // CC :77-83
        .model("inherit")                                     // CC :87
        .omitClaudeMd(true)                                   // CC :90
        .build();

    /**
     * verification Agent · 对齐 CC builtInAgents/verificationAgent.ts:134-152.
     *
     * <p>CC 无 tools 字段 (=全部); disallowedTools (:139-145); color='red' (:137); background=true (:138);
     * model='inherit' (:148); criticalSystemReminder_EXPERIMENTAL (:150-151).
     */
    public static final AgentDefinition VERIFICATION_AGENT = AgentDefinition.BuiltInAgentDefinition.builder(
            VERIFICATION,
            VERIFICATION_WHEN_TO_USE,
            (modelId, dirs) -> buildStandaloneSystemPrompt(VERIFICATION_SPECIFIC, modelId, dirs))
        .disallowedTools(ExploreAgentPrompt.disallowedTools())  // CC :139-145 (同 Explore 5 项)
        .model("inherit")                                        // CC :148
        .color("red")                                            // CC :137
        .background(true)                                        // CC :138
        .criticalSystemReminder_EXPERIMENTAL(VERIFICATION_CRITICAL_SYSTEM_REMINDER)  // CC :150-151
        .build();

    /**
     * claude-code-guide Agent · 对齐 CC builtInAgents/claudeCodeGuideAgent.ts:98-120.
     *
     * <p>由 {@link ClaudeCodeGuideAgentDef#create()} 构造 (P1-5 激活, 旧实现死代码).
     */
    public static final AgentDefinition CLAUDE_CODE_GUIDE_AGENT = ClaudeCodeGuideAgentDef.create();

    // ════════════════════════════════════════════════════════════════════════
    // Feature gate (对齐 CC builtInAgents.ts:13-20 / :55-58 / :64-69)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [Session S2] Explore/Plan agent gate · 对齐 CC {@code areExplorePlanAgentsEnabled()}
     * (builtInAgents.ts:13-20): {@code feature('BUILTIN_EXPLORE_PLAN_AGENTS') &&
     * getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_stoat', true)} — 3P default true.
     * Java 无 GrowthBook SDK → @Value boolean 默认 true（对齐 CC 3P default）。
     */
    private static volatile boolean explorePlanEnabled = true;

    /**
     * [Session S2] verification agent gate · 对齐 CC builtInAgents.ts:64-69:
     * {@code feature('VERIFICATION_AGENT') && getFeatureValue_CACHED_MAY_BE_STALE('tengu_hive_evidence', false)}
     * — GrowthBook default false。Java @Value boolean 默认 false。
     */
    private static volatile boolean verificationEnabled = false;

    /**
     * [Session S2] entrypoint 类型 · 对齐 CC {@code process.env.CLAUDE_CODE_ENTRYPOINT}
     * (builtInAgents.ts:55-58)。CC 非 'sdk-ts'/'sdk-py'/'sdk-cli' 入口才加入 claude-code-guide。
     * Java 默认 ""（非 SDK 入口）→ 默认加入 guide agent。
     */
    private static volatile String entrypoint = "";

    /**
     * [Session S2] Spring @Value 注入容器（static utility 无 bean, 用嵌套 @Configuration 转接）·
     * 对齐 CC feature()/GrowthBook gate。测试可绕过容器直接调 static setter。
     */
    @org.springframework.context.annotation.Configuration
    public static class GateConfig {
        @org.springframework.beans.factory.annotation.Value("${nexusai.agent.explore-plan-enabled:true}")
        public void setExplorePlanEnabled(boolean v) {
            BuiltInAgents.explorePlanEnabled = v;
        }

        @org.springframework.beans.factory.annotation.Value("${nexusai.agent.verification-enabled:false}")
        public void setVerificationEnabled(boolean v) {
            BuiltInAgents.verificationEnabled = v;
        }

        @org.springframework.beans.factory.annotation.Value("${nexusai.agent.entrypoint:}")
        public void setEntrypoint(String v) {
            BuiltInAgents.entrypoint = v == null ? "" : v;
        }
    }

    public static boolean areExplorePlanAgentsEnabled() {
        return explorePlanEnabled;
    }

    /**
     * 是否为非 SDK 入口 · 对齐 CC builtInAgents.ts:55-58
     * {@code CLAUDE_CODE_ENTRYPOINT !== 'sdk-ts' && !== 'sdk-py' && !== 'sdk-cli'}。
     */
    private static boolean isNonSdkEntrypoint() {
        return !"sdk-ts".equals(entrypoint) && !"sdk-py".equals(entrypoint) && !"sdk-cli".equals(entrypoint);
    }

    /**
     * 动态获取内置 Agent 列表 · 对齐 CC {@code getBuiltInAgents()} (builtInAgents.ts:22-72)。
     *
     * <p>CC 真源（Pattern #9 已实读）:
     * <ol>
     *   <li>SDK disable env + noninteractive → []（:25-30；Java 无 SDK 入口，跳过）</li>
     *   <li>coordinator mode → coordinator agents（:35-43；Java 无 coordinator 模块，跳过）</li>
     *   <li>base = [GENERAL_PURPOSE, STATUSLINE_SETUP]（:45-48）</li>
     *   <li>areExplorePlanAgentsEnabled() → + [EXPLORE, PLAN]（:50-52）</li>
     *   <li>非 SDK 入口 → + CLAUDE_CODE_GUIDE（:54-61）</li>
     *   <li>feature('VERIFICATION_AGENT') && GB → + VERIFICATION（:64-69）</li>
     * </ol>
     *
     * <p><b>不含 FORK</b>（CC builtInAgents.ts:22-72 全文无 FORK；FORK 在 AgentTool.tsx:335 直接引用，
     * 不经 getBuiltInAgents）。Java 端 FORK 生产路径由 {@link #get(String)} fork 特殊分支兜底
     * （SubagentExecutor.execute 以类型字符串 "fork" 重解析，S2-5 偏差见 concerns）。
     */
    public static List<AgentDefinition> getBuiltInAgents() {
        List<AgentDefinition> agents = new java.util.ArrayList<>();
        agents.add(GENERAL_PURPOSE_AGENT);
        agents.add(STATUSLINE_SETUP_AGENT);
        if (areExplorePlanAgentsEnabled()) {
            agents.add(EXPLORE_AGENT);
            agents.add(PLAN_AGENT);
        }
        if (isNonSdkEntrypoint()) {
            agents.add(CLAUDE_CODE_GUIDE_AGENT);
        }
        if (verificationEnabled) {
            agents.add(VERIFICATION_AGENT);
        }
        return agents;
    }

    /**
     * 获取内置 Agent 定义（对齐 CC builtInAgents[agentType]）。
     *
     * <p><b>[Session S2] fork 特殊路径</b>: 删除静态 ALL Map 后，get() 委托 getBuiltInAgents()
     * 流式查找。但 SubagentExecutor.execute 对 fork path 以类型字符串 "fork" 重解析
     * （SubagentTool.executeSync → executor.execute(prompt, "fork", ...) → resolveAgentDefinition
     * → BuiltInAgents.get("fork")），故 fork 不在 getBuiltInAgents() 列表中仍必须可命中 —
     * 等价于 CC AgentTool.tsx:335 对 FORK_AGENT 的直接引用（不经 builtInAgents 列表）。
     */
    public static AgentDefinition get(String agentType) {
        if (agentType == null) return null;
        // CC AgentTool.tsx:335 FORK_AGENT 直接引用 — fork 不经 getBuiltInAgents() 列表
        if (ForkSubagent.FORK_SUBAGENT_TYPE.equals(agentType)) {
            return ForkSubagentAgentDefinition.create();
        }
        return getBuiltInAgents().stream()
            .filter(a -> agentType.equals(a.agentType()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 检查是否为内置 Agent（对齐 CC isBuiltInAgent）
     */
    public static boolean isBuiltIn(AgentDefinition def) {
        return def instanceof AgentDefinition.BuiltInAgentDefinition;
    }
}

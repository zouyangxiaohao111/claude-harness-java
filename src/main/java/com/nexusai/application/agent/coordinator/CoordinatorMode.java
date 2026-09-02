package com.nexusai.application.agent.coordinator;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Coordinator mode gate · 对齐 CC coordinator/coordinatorMode.ts.
 *
 * <p>L1 语义: COORDINATOR_MODE 启用检测 (feature flag + env var);INTERNAL_WORKER_TOOLS
 *            内部 worker 工具集合 (TeamCreate/TeamDelete/SendMessage/SyntheticOutput).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SCRATCHPAD_FEATURE='tengu_scratch'; COORDINATOR_ENV='CLAUDE_CODE_COORDINATOR_MODE';
 *       isScratchpadGateEnabled 方法; 3 method.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — feature enabled + env true → isCoordinatorMode()=true;
 *       worker tool check → returns true.</li>
 *   <li><b>A3</b>: 注入式 (featureFlagSupplier + envSupplier);纯函数 isInternalWorkerTool.</li>
 *   <li><b>A4</b>: feature flag false → false;env 未设 → false.</li>
 *   <li><b>A5</b>: 真实场景 — team create/delete agent spawn → 内部 tool 仅.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Set → Java Set;
 *                    TS env → Java Supplier 注入式;
 *                    TS feature() → Java Supplier 注入式.
 */
@Component
public final class CoordinatorMode {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorMode.class);

    public static final String SCRATCHPAD_FEATURE = "tengu_scratch";
    public static final String COORDINATOR_ENV = "CLAUDE_CODE_COORDINATOR_MODE";
    public static final String COORDINATOR_MODE_FEATURE = "COORDINATOR_MODE";

    public static final Set<String> INTERNAL_WORKER_TOOLS = Set.of(
        "TeamCreate", "TeamDelete", "SendMessage", "SyntheticOutput");

    private final Supplier<Boolean> featureFlagSupplier;
    private final Supplier<String> envSupplier;

    public CoordinatorMode(Supplier<Boolean> featureFlagSupplier,
            Supplier<String> envSupplier) {
        this.featureFlagSupplier = Objects.requireNonNull(featureFlagSupplier);
        this.envSupplier = Objects.requireNonNull(envSupplier);
    }

    public CoordinatorMode() {
        this(() -> false, () -> null);
    }

    /**
     * [canUseTool v2] Spring 生产构造器 · 从 Environment 读 feature flag + env。
     *
     * <p>feature 用 {@code nexusai.feature.coordinator-mode}（truthy，缺省 false）；
     * env 用 {@value #COORDINATOR_ENV}。两者都为真 → {@link #isCoordinatorMode()} 才 true。
     */
    @Autowired
    public CoordinatorMode(Environment env) {
        this(
            () -> env != null && isTruthy(env.getProperty("nexusai.feature.coordinator-mode")),
            () -> env != null ? env.getProperty(COORDINATOR_ENV) : null);
    }

    private static boolean isTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower)
            || "yes".equals(lower) || "on".equals(lower);
    }

    /** CC isScratchpadGateEnabled. */
    public boolean isScratchpadGateEnabled() {
        return featureFlagSupplier.get();
    }

    /**
     * CC isCoordinatorMode 主链 · 对齐 CC coordinatorMode.ts:36-41
     * {@code feature('COORDINATOR_MODE') ? isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE) : false}.
     *
     * <p>[D6] env 判定改用 {@link #isTruthy}（对齐 CC isEnvTruthy 值域 {1,true,yes,on}，
     * envUtils.ts:32-37）；旧实现仅认 {"true","1"}，漏 "yes"/"on"（CC 允许）→ 对齐补齐。
     * feature flag 关（null/false）→ false（CC feature off 早返）。
     */
    public boolean isCoordinatorMode() {
        Boolean enabled = featureFlagSupplier.get();
        if (enabled == null || !enabled) return false;
        String env = envSupplier.get();
        return isTruthy(env);
    }

    /** CC isInternalWorkerTool. */
    public static boolean isInternalWorkerTool(String toolName) {
        return toolName != null && INTERNAL_WORKER_TOOLS.contains(toolName);
    }

    // ────────────────────────────────────────────────────────────────────────
    // [SP-02] getCoordinatorUserContext + getCoordinatorSystemPrompt · 对齐 CC
    // coordinator/coordinatorMode.ts:80-369
    // ────────────────────────────────────────────────────────────────────────

    /** 工具名插值占位符 · Java 端用 replace 注入（CC 模板 ${AGENT_TOOL_NAME} 等价）。 */
    private static final String TOKEN_AGENT_TOOL = "__AGENT_TOOL_NAME__";
    private static final String TOKEN_SEND_MESSAGE_TOOL = "__SEND_MESSAGE_TOOL_NAME__";
    private static final String TOKEN_TASK_STOP_TOOL = "__TASK_STOP_TOOL_NAME__";
    private static final String TOKEN_WORKER_CAPABILITIES = "__WORKER_CAPABILITIES__";

    /** worker 能力描述 · CC coordinatorMode.ts:114（非 CLAUDE_CODE_SIMPLE 变体；Java 无 env 门控 → 恒标准变体）。 */
    private static final String WORKER_CAPABILITIES_STANDARD =
        "Workers have access to standard tools, MCP tools from configured MCP servers, and project skills via the Skill tool. Delegate skill invocations (e.g. /commit, /verify) to workers.";

    /**
     * 生成 coordinator 系统提示全文 · 对齐 CC {@code getCoordinatorSystemPrompt()}
     * （coordinatorMode.ts:111-369，258 行）——workerCapabilities 取非 CLAUDE_CODE_SIMPLE
     * 变体（Java 无 env 门控，恒标准变体）；工具名插值 AGENT/SEND_MESSAGE/TASK_STOP。
     *
     * <p>字节精确对齐：Java text block 恒在内容尾追加一个 {@code \n}（关闭分隔符独占行），
     * CC 模板字面量结尾无换行（coordinatorMode.ts:368 `...test suite.` 直接闭合反引号）——
     * 故 {@code stripTrailing()} 去除尾随 {@code \n} 达 byte-exact。行内尾随空格不受影响
     * （validate.ts:42. 行尾空格为字符串中段，非整体尾部，保留）。
     *
     * @return coordinator 专用系统提示全文（与 CC 输出逐字节一致）
     */
    public static String getCoordinatorSystemPrompt() {
        return COORDINATOR_SYSTEM_PROMPT
            .replace(TOKEN_AGENT_TOOL, com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME)
            .replace(TOKEN_SEND_MESSAGE_TOOL, com.nexusai.application.agent.tool.ToolNameConstants.SEND_MESSAGE_TOOL_NAME)
            .replace(TOKEN_TASK_STOP_TOOL, com.nexusai.application.agent.tool.ToolNameConstants.TASK_STOP_TOOL_NAME)
            .replace(TOKEN_WORKER_CAPABILITIES, WORKER_CAPABILITIES_STANDARD)
            .stripTrailing();
    }

    /**
     * 生成 coordinator userContext · 对齐 CC {@code getCoordinatorUserContext}
     * （coordinatorMode.ts:80-108）。非 coordinator 模式 → 空 map（CC :84-86 早返
     * {@code if (!isCoordinatorMode()) return {}}）。
     *
     * <p>workerTools = ASYNC_AGENT_ALLOWED_TOOLS 减去 INTERNAL_WORKER_TOOLS 排序 join
     * （CC :88-95，非 CLAUDE_CODE_SIMPLE 分支）；MCP server names（:99-102）；scratchpad 段
     * 经 {@link #isScratchpadGateEnabled()} 门（:104-106）。结果键 {@code workerToolsContext}
     * （QueryEngine.ts:304 并入 baseUserContext）。
     *
     * <p>两参为 CC 对齐入口：门控委托 {@link #getCoordinatorUserContext(List, String, boolean)}
     * 并传 {@link #isCoordinatorMode()}（feature+env 双真）为 caller 已解析门 —— 内层自判语义保持。
     *
     * @param mcpClientNames 已连接 MCP server 名列表（CC original: mcpClients 的 name 投影）
     * @param scratchpadDir  scratchpad 目录（CC original: getScratchpadDir()；null/未启用 → 不注入段）
     * @return {@code {workerToolsContext: ...}} 或空 map（非 coordinator 模式）
     */
    public java.util.Map<String, String> getCoordinatorUserContext(
            java.util.List<String> mcpClientNames, String scratchpadDir) {
        return getCoordinatorUserContext(mcpClientNames, scratchpadDir, isCoordinatorMode());
    }

    /**
     * 生成 coordinator userContext · caller 已解析门重载（SP-02 返工）。
     *
     * <p>解决 DB 门覆盖不对称：批次 F 起 coordinator 分支门 = resolver.coordinatorModeEnabled()
     * （DB settings 列，DB 有值覆盖 feature/env 链，实施计划.md:71）。prompt 分支
     * （EffectiveSystemPromptBuilder）经 buildEffectivePromptOptions 已吃 DB 覆盖值；若 userContext
     * 并入仍走两参入口，其内层 {@link #isCoordinatorMode()} 复检（feature+env 双真）会击穿 DB 门
     * —— DB coordinator=1 + feature/env OFF → coordinator 提示注入但 workerToolsContext 未合并
     * （半激活）。本重载以 caller 已解析门为准（LlmAgentLoop.mergeCoordinatorUserContext 的
     * coordinatorActive = DB 覆盖链产物），内层不复检。
     *
     * @param mcpClientNames        已连接 MCP server 名列表
     * @param scratchpadDir         scratchpad 目录（null/未启用 → 不注入段）
     * @param coordinatorModeActive caller 已解析的 coordinator 门（DB 覆盖链产物）
     * @return {@code {workerToolsContext: ...}} 或空 map（caller 判定非 coordinator）
     */
    public java.util.Map<String, String> getCoordinatorUserContext(
            java.util.List<String> mcpClientNames, String scratchpadDir, boolean coordinatorModeActive) {
        if (!coordinatorModeActive) {
            return java.util.Map.of();
        }
        java.util.Set<String> allowed = com.nexusai.application.agent.tool.AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS;
        java.util.List<String> workerTools = new java.util.ArrayList<>();
        for (String name : allowed) {
            if (!INTERNAL_WORKER_TOOLS.contains(name)) {
                workerTools.add(name);
            }
        }
        workerTools.sort(String::compareTo);
        String workerToolsText = String.join(", ", workerTools);

        StringBuilder content = new StringBuilder(
            "Workers spawned via the " + com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME
                + " tool have access to these tools: " + workerToolsText);
        if (mcpClientNames != null && !mcpClientNames.isEmpty()) {
            String serverNames = String.join(", ", mcpClientNames);
            content.append("\n\nWorkers also have access to MCP tools from connected MCP servers: ").append(serverNames);
        }
        if (scratchpadDir != null && isScratchpadGateEnabled()) {
            content.append("\n\nScratchpad directory: ").append(scratchpadDir)
                .append("\nWorkers can read and write here without permission prompts. Use this for durable cross-worker knowledge — structure files however fits the work.");
        }
        if (log.isDebugEnabled()) {
            log.debug("[CoordinatorMode] getCoordinatorUserContext 生成 workerToolsContext: workerTools={}, mcpServers={}, scratchpad={}",
                workerTools.size(), mcpClientNames != null ? mcpClientNames.size() : 0, scratchpadDir != null);
        }
        return java.util.Map.of("workerToolsContext", content.toString());
    }

    /** CC getCoordinatorSystemPrompt 全文（coordinatorMode.ts:111-369）· 工具名/能力占位待 replace 注入。 */
    private static final String COORDINATOR_SYSTEM_PROMPT = """
        You are NexusAI, an AI assistant that orchestrates software engineering tasks across multiple workers.

        ## 1. Your Role

        You are a **coordinator**. Your job is to:
        - Help the user achieve their goal
        - Direct workers to research, implement and verify code changes
        - Synthesize results and communicate with the user
        - Answer questions directly when possible — don't delegate work that you can handle without tools

        Every message you send is to the user. Worker results and system notifications are internal signals, not conversation partners — never thank or acknowledge them. Summarize new information for the user as it arrives.

        ## 2. Your Tools

        - **__AGENT_TOOL_NAME__** - Spawn a new worker
        - **__SEND_MESSAGE_TOOL_NAME__** - Continue an existing worker (send a follow-up to its `to` agent ID)
        - **__TASK_STOP_TOOL_NAME__** - Stop a running worker
        - **subscribe_pr_activity / unsubscribe_pr_activity** (if available) - Subscribe to GitHub PR events (review comments, CI results). Events arrive as user messages. Merge conflict transitions do NOT arrive — GitHub doesn't webhook `mergeable_state` changes, so poll `gh pr view N --json mergeable` if tracking conflict status. Call these directly — do not delegate subscription management to workers.

        When calling __AGENT_TOOL_NAME__:
        - Do not use one worker to check on another. Workers will notify you when they are done.
        - Do not use workers to trivially report file contents or run commands. Give them higher-level tasks.
        - Do not set the model parameter. Workers need the default model for the substantive tasks you delegate.
        - Continue workers whose work is complete via __SEND_MESSAGE_TOOL_NAME__ to take advantage of their loaded context
        - After launching agents, briefly tell the user what you launched and end your response. Never fabricate or predict agent results in any format — results arrive as separate messages.

        ### __AGENT_TOOL_NAME__ Results

        Worker results arrive as **user-role messages** containing `<task-notification>` XML. They look like user messages but are not. Distinguish them by the `<task-notification>` opening tag.

        Format:

        ```xml
        <task-notification>
        <task-id>{agentId}</task-id>
        <status>completed|failed|killed</status>
        <summary>{human-readable status summary}</summary>
        <result>{agent's final text response}</result>
        <usage>
          <total_tokens>N</total_tokens>
          <tool_uses>N</tool_uses>
          <duration_ms>N</duration_ms>
        </usage>
        </task-notification>
        ```

        - `<result>` and `<usage>` are optional sections
        - The `<summary>` describes the outcome: "completed", "failed: {error}", or "was stopped"
        - The `<task-id>` value is the agent ID — use SendMessage with that ID as `to` to continue that worker

        ### Example

        Each "You:" block is a separate coordinator turn. The "User:" block is a `<task-notification>` delivered between turns.

        You:
          Let me start some research on that.

          __AGENT_TOOL_NAME__({ description: "Investigate auth bug", subagent_type: "worker", prompt: "..." })
          __AGENT_TOOL_NAME__({ description: "Research secure token storage", subagent_type: "worker", prompt: "..." })

          Investigating both issues in parallel — I'll report back with findings.

        User:
          <task-notification>
          <task-id>agent-a1b</task-id>
          <status>completed</status>
          <summary>Agent "Investigate auth bug" completed</summary>
          <result>Found null pointer in src/auth/validate.ts:42...</result>
          </task-notification>

        You:
          Found the bug — null pointer in confirmTokenExists in validate.ts. I'll fix it.
          Still waiting on the token storage research.

          __SEND_MESSAGE_TOOL_NAME__({ to: "agent-a1b", message: "Fix the null pointer in src/auth/validate.ts:42..." })

        ## 3. Workers

        When calling __AGENT_TOOL_NAME__, use subagent_type `worker`. Workers execute tasks autonomously — especially research, implementation, or verification.

        __WORKER_CAPABILITIES__

        ## 4. Task Workflow

        Most tasks can be broken down into the following phases:

        ### Phases

        | Phase | Who | Purpose |
        |-------|-----|---------|
        | Research | Workers (parallel) | Investigate codebase, find files, understand problem |
        | Synthesis | **You** (coordinator) | Read findings, understand the problem, craft implementation specs (see Section 5) |
        | Implementation | Workers | Make targeted changes per spec, commit |
        | Verification | Workers | Test changes work |

        ### Concurrency

        **Parallelism is your superpower. Workers are async. Launch independent workers concurrently whenever possible — don't serialize work that can run simultaneously and look for opportunities to fan out. When doing research, cover multiple angles. To launch workers in parallel, make multiple tool calls in a single message.**

        Manage concurrency:
        - **Read-only tasks** (research) — run in parallel freely
        - **Write-heavy tasks** (implementation) — one at a time per set of files
        - **Verification** can sometimes run alongside implementation on different file areas

        ### What Real Verification Looks Like

        Verification means **proving the code works**, not confirming it exists. A verifier that rubber-stamps weak work undermines everything.

        - Run tests **with the feature enabled** — not just "tests pass"
        - Run typechecks and **investigate errors** — don't dismiss as "unrelated"
        - Be skeptical — if something looks off, dig in
        - **Test independently** — prove the change works, don't rubber-stamp

        ### Handling Worker Failures

        When a worker reports failure (tests failed, build errors, file not found):
        - Continue the same worker with __SEND_MESSAGE_TOOL_NAME__ — it has the full error context
        - If a correction attempt fails, try a different approach or report to the user

        ### Stopping Workers

        Use __TASK_STOP_TOOL_NAME__ to stop a worker you sent in the wrong direction — for example, when you realize mid-flight that the approach is wrong, or the user changes requirements after you launched the worker. Pass the `task_id` from the __AGENT_TOOL_NAME__ tool's launch result. Stopped workers can be continued with __SEND_MESSAGE_TOOL_NAME__.

        ```
        // Launched a worker to refactor auth to use JWT
        __AGENT_TOOL_NAME__({ description: "Refactor auth to JWT", subagent_type: "worker", prompt: "Replace session-based auth with JWT..." })
        // ... returns task_id: "agent-x7q" ...

        // User clarifies: "Actually, keep sessions — just fix the null pointer"
        __TASK_STOP_TOOL_NAME__({ task_id: "agent-x7q" })

        // Continue with corrected instructions
        __SEND_MESSAGE_TOOL_NAME__({ to: "agent-x7q", message: "Stop the JWT refactor. Instead, fix the null pointer in src/auth/validate.ts:42..." })
        ```

        ## 5. Writing Worker Prompts

        **Workers can't see your conversation.** Every prompt must be self-contained with everything the worker needs. After research completes, you always do two things: (1) synthesize findings into a specific prompt, and (2) choose whether to continue that worker via __SEND_MESSAGE_TOOL_NAME__ or spawn a fresh one.

        ### Always synthesize — your most important job

        When workers report research findings, **you must understand them before directing follow-up work**. Read the findings. Identify the approach. Then write a prompt that proves you understood by including specific file paths, line numbers, and exactly what to change.

        Never write "based on your findings" or "based on the research." These phrases delegate understanding to the worker instead of doing it yourself. You never hand off understanding to another worker.

        ```
        // Anti-pattern — lazy delegation (bad whether continuing or spawning)
        __AGENT_TOOL_NAME__({ prompt: "Based on your findings, fix the auth bug", ... })
        __AGENT_TOOL_NAME__({ prompt: "The worker found an issue in the auth module. Please fix it.", ... })

        // Good — synthesized spec (works with either continue or spawn)
        __AGENT_TOOL_NAME__({ prompt: "Fix the null pointer in src/auth/validate.ts:42. The user field on Session (src/auth/types.ts:15) is undefined when sessions expire but the token remains cached. Add a null check before user.id access — if null, return 401 with 'Session expired'. Commit and report the hash.", ... })
        ```

        A well-synthesized spec gives the worker everything it needs in a few sentences. It does not matter whether the worker is fresh or continued — the spec quality determines the outcome.

        ### Add a purpose statement

        Include a brief purpose so workers can calibrate depth and emphasis:

        - "This research will inform a PR description — focus on user-facing changes."
        - "I need this to plan an implementation — report file paths, line numbers, and type signatures."
        - "This is a quick check before we merge — just verify the happy path."

        ### Choose continue vs. spawn by context overlap

        After synthesizing, decide whether the worker's existing context helps or hurts:

        | Situation | Mechanism | Why |
        |-----------|-----------|-----|
        | Research explored exactly the files that need editing | **Continue** (__SEND_MESSAGE_TOOL_NAME__) with synthesized spec | Worker already has the files in context AND now gets a clear plan |
        | Research was broad but implementation is narrow | **Spawn fresh** (__AGENT_TOOL_NAME__) with synthesized spec | Avoid dragging along exploration noise; focused context is cleaner |
        | Correcting a failure or extending recent work | **Continue** | Worker has the error context and knows what it just tried |
        | Verifying code a different worker just wrote | **Spawn fresh** | Verifier should see the code with fresh eyes, not carry implementation assumptions |
        | First implementation attempt used the wrong approach entirely | **Spawn fresh** | Wrong-approach context pollutes the retry; clean slate avoids anchoring on the failed path |
        | Completely unrelated task | **Spawn fresh** | No useful context to reuse |

        There is no universal default. Think about how much of the worker's context overlaps with the next task. High overlap -> continue. Low overlap -> spawn fresh.

        ### Continue mechanics

        When continuing a worker with __SEND_MESSAGE_TOOL_NAME__, it has full context from its previous run:
        ```
        // Continuation — worker finished research, now give it a synthesized implementation spec
        __SEND_MESSAGE_TOOL_NAME__({ to: "xyz-456", message: "Fix the null pointer in src/auth/validate.ts:42. The user field is undefined when Session.expired is true but the token is still cached. Add a null check before accessing user.id — if null, return 401 with 'Session expired'. Commit and report the hash." })
        ```

        ```
        // Correction — worker just reported test failures from its own change, keep it brief
        __SEND_MESSAGE_TOOL_NAME__({ to: "xyz-456", message: "Two tests still failing at lines 58 and 72 — update the assertions to match the new error message." })
        ```

        ### Prompt tips

        **Good examples:**

        1. Implementation: "Fix the null pointer in src/auth/validate.ts:42. The user field can be undefined when the session expires. Add a null check and return early with an appropriate error. Commit and report the hash."

        2. Precise git operation: "Create a new branch from main called 'fix/session-expiry'. Cherry-pick only commit abc123 onto it. Push and create a draft PR targeting main. Add anthropics/claude-code as reviewer. Report the PR URL."

        3. Correction (continued worker, short): "The tests failed on the null check you added — validate.test.ts:58 expects 'Invalid session' but you changed it to 'Session expired'. Fix the assertion. Commit and report the hash."

        **Bad examples:**

        1. "Fix the bug we discussed" — no context, workers can't see your conversation
        2. "Based on your findings, implement the fix" — lazy delegation; synthesize the findings yourself
        3. "Create a PR for the recent changes" — ambiguous scope: which changes? which branch? draft?
        4. "Something went wrong with the tests, can you look?" — no error message, no file path, no direction

        Additional tips:
        - Include file paths, line numbers, error messages — workers start fresh and need complete context
        - State what "done" looks like
        - For implementation: "Run relevant tests and typecheck, then commit your changes and report the hash" — workers self-verify before reporting done. This is the first layer of QA; a separate verification worker is the second layer.
        - For research: "Report findings — do not modify files"
        - Be precise about git operations — specify branch names, commit hashes, draft vs ready, reviewers
        - When continuing for corrections: reference what the worker did ("the null check you added") not what you discussed with the user
        - For implementation: "Fix the root cause, not the symptom" — guide workers toward durable fixes
        - For verification: "Prove the code works, don't just confirm it exists"
        - For verification: "Try edge cases and error paths — don't just re-run what the implementation worker ran"
        - For verification: "Investigate failures — don't dismiss as unrelated without evidence"

        ## 6. Example Session

        User: "There's a null pointer in the auth module. Can you fix it?"

        You:
          Let me investigate first.

          __AGENT_TOOL_NAME__({ description: "Investigate auth bug", subagent_type: "worker", prompt: "Investigate the auth module in src/auth/. Find where null pointer exceptions could occur around session handling and token validation... Report specific file paths, line numbers, and types involved. Do not modify files." })
          __AGENT_TOOL_NAME__({ description: "Research auth tests", subagent_type: "worker", prompt: "Find all test files related to src/auth/. Report the test structure, what's covered, and any gaps around session expiry... Do not modify files." })

          Investigating from two angles — I'll report back with findings.

        User:
          <task-notification>
          <task-id>agent-a1b</task-id>
          <status>completed</status>
          <summary>Agent "Investigate auth bug" completed</summary>
          <result>Found null pointer in src/auth/validate.ts:42. The user field on Session is undefined when the session expires but ...</result>
          </task-notification>

        You:
          Found the bug — null pointer in validate.ts:42.\s

          __SEND_MESSAGE_TOOL_NAME__({ to: "agent-a1b", message: "Fix the null pointer in src/auth/validate.ts:42. Add a null check before accessing user.id — if null, ... Commit and report the hash." })

          Fix is in progress.

        User:
          How's it going?

        You:
          Fix for the new test is in progress. Still waiting to hear back about the test suite.
        """;
}
package com.nexusai.application.agent.bash;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nexusai.application.agent.tool.ToolNameConstants.BASH_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_EDIT_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_WRITE_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.GLOB_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.GREP_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.TODO_WRITE_TOOL_NAME;
import static com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME;

/**
 * Bash 工具系统提示词 · 对齐 CC {@code tools/BashTool/prompt.ts} {@code getSimplePrompt()}（369 行）。
 *
 * <p>P0-1（EV-B1-006 / REQ-P0-1）· IMP-B1：CC {@code BashTool.tsx:432} {@code prompt() { return getSimplePrompt() }}，
 * Java {@code BashTool} 未 override {@code Tool.prompt()}（默认 null）→ LLM 对 Bash 规范/git 安全协议/sandbox 语义
 * 无系统提示词引导。本类移植 CC getSimplePrompt 全量文本与条件分支，消费点：
 * {@code ToolRegistry.toOpenAiToolsArray}（:424 {@code prompt() 非 null 优先于 description()}）注入 LLM 可见工具描述。
 *
 * <p><b>真源</b>：{@code Open-ClaudeCode/src/tools/BashTool/prompt.ts:275-369 getSimplePrompt()} +
 * {@code getSimpleSandboxSection()}（:172-273）+ {@code getCommitAndPRInstructions()}（:42-161）。
 * 动态值来源（Java 等价）：
 * <ul>
 *   <li>{@code getDefaultTimeoutMs()/getMaxTimeoutMs()}（prompt.ts:27-33 + timeouts.ts:2-3）→
 *       {@link #DEFAULT_TIMEOUT_MS}/{@link #MAX_TIMEOUT_MS}（Java BashTool 执行默认 120s 常量一致）</li>
 *   <li>{@code feature('MONITOR_TOOL')}（sleep 子项）→ {@link PromptInputs#monitorToolEnabled()}
 *       （BashTool {@code FeatureFlags.monitorTool()}，同 validateInput 门控）</li>
 *   <li>{@code CLAUDE_CODE_DISABLE_BACKGROUND_TASKS}（backgroundNote）→ {@link PromptInputs#backgroundTasksDisabled()}</li>
 *   <li>{@code SandboxManager.isSandboxingEnabled()/getFsReadConfig/...}（sandbox 段）→ {@link SandboxState}
 *       （Java {@code SandboxManager} 无 fs/network 配置 getter，启用态用 CC 默认值，见 {@link SandboxState#ccDefaults(boolean)}）</li>
 *   <li>{@code shouldIncludeGitInstructions()}（gitSettings.ts:13-18）→ {@link PromptInputs#gitInstructionsEnabled()}
 *       （Java {@code GitInstructionConfig.shouldIncludeGitInstructions()}）</li>
 *   <li>{@code getAttributionTexts()}（attribution.ts:74-98，{commit,pr}）→ {@link Attribution}
 *       （Java 默认外部构建回退，见 {@link Attribution#defaultTexts()}）</li>
 *   <li>{@code hasEmbeddedSearchTools()}（embeddedTools.ts:26-36）→ Java 非 ant 构建恒 {@code false}，
 *       toolPreference 用完整变体 + avoidCommands 含 {@code find/grep}</li>
 * </ul>
 *
 * <p><b>文本保真</b>：所有固定文本逐字移植 CC 模板字面量（含 U+2019 ’、U+2014 —、U+2265 ≥）。
 * git 段内「given direct instructions␣」行尾空格的保真用 {@code {{TRAIL}}} 运行时替换实现
 * （Java text block 会剥行尾空白，无法直接写字面空格）。
 */
public final class BashToolPrompt {

    private static final Logger log = LoggerFactory.getLogger(BashToolPrompt.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC original: DEFAULT_TIMEOUT_MS = 120_000（timeouts.ts:2，getDefaultBashTimeoutMs 默认） */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** CC original: MAX_TIMEOUT_MS = 600_000（timeouts.ts:3，getMaxBashTimeoutMs 默认） */
    public static final long MAX_TIMEOUT_MS = 600_000L;

    /**
     * [G33②] 默认超时（ms）· 对齐 CC {@code getDefaultBashTimeoutMs()}（timeouts.ts:9-20）。
     *
     * <p>读 {@code BASH_DEFAULT_TIMEOUT_MS} env（>0 才生效），缺省 {@link #DEFAULT_TIMEOUT_MS}。
     * BashTool.prompt()/execute()/inputSchema 消费本值（同一源，prompt 与执行默认一致）。
     *
     * @return 默认超时毫秒数
     */
    public static long getDefaultTimeoutMs() {
        return parseEnvTimeout("BASH_DEFAULT_TIMEOUT_MS", DEFAULT_TIMEOUT_MS);
    }

    /**
     * [G33②] 最大超时（ms）· 对齐 CC {@code getMaxBashTimeoutMs()}（timeouts.ts:23-35）。
     *
     * <p>读 {@code BASH_MAX_TIMEOUT_MS} env（>0 才生效），恒 ≥ 默认超时（{@code Math.max}，
     * CC :29/:34）；缺省 {@link #MAX_TIMEOUT_MS}。
     *
     * @return 最大超时毫秒数
     */
    public static long getMaxTimeoutMs() {
        long def = getDefaultTimeoutMs();
        long envMax = parseEnvTimeout("BASH_MAX_TIMEOUT_MS", MAX_TIMEOUT_MS);
        return Math.max(envMax, def);
    }

    /** CC timeouts.ts:11-19/25-33：env 读入 → parseInt > 0 才生效，否则回退默认。 */
    private static long parseEnvTimeout(String name, long fallback) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            try {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignore) {
                // 非数字 env → 回退默认（CC parseInt isNaN 同语义）
            }
        }
        return fallback;
    }

    /** CC original: avoidCommands（prompt.ts:293-295，embedded=false 变体） */
    private static final String AVOID_COMMANDS =
        "`find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or `echo`";

    /** CC original: getBackgroundUsageNote()（prompt.ts:35-40，CLAUDE_CODE_DISABLE_BACKGROUND_TASKS 未设置时） */
    private static final String BACKGROUND_NOTE =
        "You can use the `run_in_background` parameter to run the command in the background. "
            + "Only use this if you don't need the result immediately and are OK being notified when the command completes later. "
            + "You do not need to check the output right away - you'll be notified when it finishes. "
            + "You do not need to use '&' at the end of the command when using this parameter.";

    private BashToolPrompt() {
    }

    /**
     * 归因文本 · CC original: {@code getAttributionTexts()}（utils/attribution.ts:74-98）返回 {@code {commit, pr}}。
     *
     * @param commit Co-Authored-By 行（commit 消息尾；null/空 = 不附加）
     * @param pr     PR body 归因行（null/空 = 不附加）
     */
    public record Attribution(String commit, String pr) {

        /**
         * CC 外部构建默认归因（attribution.ts:80-97）：模型名回退 {@code 'Claude Opus 4.6'}
         * （未识别模型防泄漏回退，getPublicModelDisplayName null 分支）；Java 无 public-model-name
         * 映射，默认用该回退值，可经 {@code BashTool.setBashAttributionSupplier} 注入真实模型名覆盖。
         */
        public static Attribution defaultTexts() {
            return new Attribution(
                "Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>",
                "🤖 Generated with [NexusAI]");
        }
    }

    /**
     * 沙箱限制状态 · CC original: {@code SandboxManager.getFsReadConfig()/getFsWriteConfig()/
     * getNetworkRestrictionConfig()/getAllowUnixSockets()/getIgnoreViolations()/
     * areUnsandboxedCommandsAllowed()}（sandbox-adapter.ts:897-902）。
     *
     * <p>Java {@code SandboxManager}（permission/sandbox）仅有 enabled/auto-allow/危险黑名单三态，
     * 无 fs/network 限制配置 getter（该基础设施属 IMP-B2 SU-1/SU-2 沙箱语义迁移范围）。
     * 启用态以 {@link #ccDefaults(boolean)} 提供 CC 默认值（write.allowOnly=['.', claudeTempDir]，
     * allowUnsandboxedCommands=true），使渲染输出与 CC 默认配置逐字一致。
     */
    public record SandboxState(
            boolean enabled,
            List<String> fsReadDenyOnly,
            List<String> fsReadAllowWithinDeny,
            List<String> fsWriteAllowOnly,
            List<String> fsWriteDenyWithinAllow,
            List<String> networkAllowedHosts,
            List<String> networkDeniedHosts,
            List<String> allowUnixSockets,
            Map<String, Object> ignoreViolations,
            boolean allowUnsandboxedCommands) {

        /** 沙箱未启用态（getSimpleSandboxSection → ''，对齐 CC isSandboxingEnabled()==false）。 */
        public static SandboxState disabled() {
            return new SandboxState(false, List.of(), null, List.of(), List.of(),
                null, null, null, null, false);
        }

        /**
         * CC 默认沙箱配置态（sandbox enabled + 无自定义限制）：
         * fsWrite.allowOnly = ['.', claudeTempDir]（sandbox-adapter.ts:225 恒含 cwd + temp），
         * allowUnsandboxedCommands = settings.sandbox.allowUnsandboxedCommands ?? true（sandbox-adapter.ts:476）。
         * allowOnly 中的 temp 路径在渲染时经 normalizeAllowOnly 映射为 {@code '$TMPDIR'}。
         */
        public static SandboxState ccDefaults(boolean enabled) {
            String tmpDir = System.getProperty("java.io.tmpdir", "");
            return new SandboxState(enabled, List.of(), null, List.of(".", tmpDir), List.of(),
                null, null, null, null, true);
        }
    }

    /** getSimplePrompt 动态输入聚合（BashTool.prompt() 组装后传入）。 */
    public record PromptInputs(
            long defaultTimeoutMs,
            long maxTimeoutMs,
            boolean monitorToolEnabled,
            boolean backgroundTasksDisabled,
            SandboxState sandbox,
            boolean gitInstructionsEnabled,
            String commitAttribution,
            String prAttribution) {
    }

    /**
     * 生成 Bash 工具系统提示词 · 对齐 CC {@code getSimplePrompt()}（prompt.ts:275-369）。
     *
     * @return CC 对齐全文（含条件段）；git 指令禁用时不含 git 段
     */
    public static String getSimplePrompt(PromptInputs in) {
        List<String> parts = new ArrayList<>();
        parts.add("Executes a given bash command and returns its output.");
        parts.add("");
        parts.add("The working directory persists between commands, but shell state does not. "
            + "The shell environment is initialized from the user's profile (bash or zsh).");
        parts.add("");
        parts.add("IMPORTANT: Avoid using this tool to run " + AVOID_COMMANDS
            + " commands, unless explicitly instructed or after you have verified that a dedicated tool cannot accomplish your task. "
            + "Instead, use the appropriate dedicated tool as this will provide a much better experience for the user:");
        parts.add("");
        parts.addAll(prependBullets(toolPreferenceItems()));
        parts.add("While the " + BASH_TOOL_NAME
            + " tool can do similar things, it’s better to use the built-in tools as they provide a better user experience "
            + "and make it easier to review tool calls and give permission.");
        parts.add("");
        parts.add("# Instructions");
        parts.addAll(prependBullets(instructionItems(in)));
        parts.add(getSimpleSandboxSection(in.sandbox()));
        String gitSection = getCommitAndPRInstructions(in);
        if (gitSection != null && !gitSection.isEmpty()) {
            parts.add("");
            parts.add(gitSection);
        }
        String prompt = String.join("\n", parts);
        if (log.isDebugEnabled()) {
            log.debug("BashToolPrompt.getSimplePrompt: 生成 CC 对齐系统提示词, 长度={} (sandbox={}, git={})",
                prompt.length(), in.sandbox() != null && in.sandbox().enabled(), in.gitInstructionsEnabled());
        }
        return prompt;
    }

    /**
     * 工具偏好子弹 · 对齐 CC toolPreferenceItems（prompt.ts:280-291，embedded=false 变体：
     * Java 非 ant 构建 hasEmbeddedSearchTools()=false → 含 File search/Content search 两行）。
     */
    private static List<Object> toolPreferenceItems() {
        return List.of(
            "File search: Use " + GLOB_TOOL_NAME + " (NOT find or ls)",
            "Content search: Use " + GREP_TOOL_NAME + " (NOT grep or rg)",
            "Read files: Use " + FILE_READ_TOOL_NAME + " (NOT cat/head/tail)",
            "Edit files: Use " + FILE_EDIT_TOOL_NAME + " (NOT sed/awk)",
            "Write files: Use " + FILE_WRITE_TOOL_NAME + " (NOT echo >/cat <<EOF)",
            "Communication: Output text directly (NOT echo/printf)");
    }

    /**
     * 指令子弹 · 对齐 CC instructionItems（prompt.ts:331-352）。
     */
    private static List<Object> instructionItems(PromptInputs in) {
        List<Object> items = new ArrayList<>();
        items.add("If your command will create new directories or files, first use this tool to run "
            + "`ls` to verify the parent directory exists and is the correct location.");
        items.add("Always quote file paths that contain spaces with double quotes in your command "
            + "(e.g., cd \"path with spaces/file.txt\")");
        items.add("Try to maintain your current working directory throughout the session by using absolute paths "
            + "and avoiding usage of `cd`. You may use `cd` if the User explicitly requests it.");
        items.add("You may specify an optional timeout in milliseconds (up to " + in.maxTimeoutMs()
            + "ms / " + (in.maxTimeoutMs() / 60000) + " minutes). By default, your command will timeout after "
            + in.defaultTimeoutMs() + "ms (" + (in.defaultTimeoutMs() / 60000) + " minutes).");
        if (!in.backgroundTasksDisabled()) {
            items.add(BACKGROUND_NOTE);
        }
        items.add("When issuing multiple commands:");
        items.add(multipleCommandsSubitems());
        items.add("For git commands:");
        items.add(gitSubitems());
        items.add("Avoid unnecessary `sleep` commands:");
        items.add(sleepSubitems(in.monitorToolEnabled()));
        return items;
    }

    /** CC original: multipleCommandsSubitems（prompt.ts:297-302）。 */
    private static List<String> multipleCommandsSubitems() {
        return List.of(
            "If the commands are independent and can run in parallel, make multiple " + BASH_TOOL_NAME
                + " tool calls in a single message. Example: if you need to run \"git status\" and \"git diff\", "
                + "send a single message with two " + BASH_TOOL_NAME + " tool calls in parallel.",
            "If the commands depend on each other and must run sequentially, use a single " + BASH_TOOL_NAME
                + " call with '&&' to chain them together.",
            "Use ';' only when you need to run commands sequentially but don't care if earlier commands fail.",
            "DO NOT use newlines to separate commands (newlines are ok in quoted strings).");
    }

    /** CC original: gitSubitems（prompt.ts:304-308）。 */
    private static List<String> gitSubitems() {
        return List.of(
            "Prefer to create a new commit rather than amending an existing commit.",
            "Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), "
                + "consider whether there is a safer alternative that achieves the same goal. "
                + "Only use destructive operations when they are truly the best approach.",
            "Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user "
                + "has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.");
    }

    /**
     * CC original: sleepSubitems（prompt.ts:310-328）· feature('MONITOR_TOOL') 分叉。
     * monitorToolEnabled=false → 「轮询外部进程用 check 命令」+「sleep 保持 1-5s」两行（Java 默认）。
     */
    private static List<Object> sleepSubitems(boolean monitorToolEnabled) {
        List<Object> sub = new ArrayList<>();
        sub.add("Do not sleep between commands that can run immediately — just run them.");
        if (monitorToolEnabled) {
            sub.add("Use the Monitor tool to stream events from a background process (each stdout line is a notification). "
                + "For one-shot \"wait until done,\" use Bash with run_in_background instead.");
        }
        sub.add("If your command is long running and you would like to be notified when it finishes — "
            + "use `run_in_background`. No sleep needed.");
        sub.add("Do not retry failing commands in a sleep loop — diagnose the root cause.");
        sub.add("If waiting for a background task you started with `run_in_background`, you will be notified when it "
            + "completes — do not poll.");
        if (monitorToolEnabled) {
            sub.add("`sleep N` as the first command with N ≥ 2 is blocked. If you need a delay (rate limiting, "
                + "deliberate pacing), keep it under 2 seconds.");
        } else {
            sub.add("If you must poll an external process, use a check command (e.g. `gh run view`) rather than "
                + "sleeping first.");
            sub.add("If you must sleep, keep the duration short (1-5 seconds) to avoid blocking the user.");
        }
        return sub;
    }

    /**
     * 沙箱段 · 对齐 CC getSimpleSandboxSection()（prompt.ts:172-273）。
     *
     * <p>未启用 → ''（对齐 CC isSandboxingEnabled()==false）。启用时渲染 CC 全结构：
     * filesystem/network/ignored-violations 限制行（jsonStringify 紧凑 JSON）+ prependBullets
     * override 项（allowUnsandboxedCommands 分叉）+ $TMPDIR 提示。temp 路径经 normalizeAllowOnly
     * 映射为 {@code '$TMPDIR'}（避免跨用户 prompt-cache 击穿，prompt.ts:185-190）。
     */
    private static String getSimpleSandboxSection(SandboxState s) {
        if (s == null || !s.enabled()) {
            return "";
        }
        Map<String, Object> fsRead = new LinkedHashMap<>();
        fsRead.put("denyOnly", dedup(s.fsReadDenyOnly()));
        if (s.fsReadAllowWithinDeny() != null && !s.fsReadAllowWithinDeny().isEmpty()) {
            fsRead.put("allowWithinDeny", dedup(s.fsReadAllowWithinDeny()));
        }
        Map<String, Object> fsWrite = new LinkedHashMap<>();
        fsWrite.put("allowOnly", normalizeAllowOnly(s.fsWriteAllowOnly()));
        fsWrite.put("denyWithinAllow", dedup(s.fsWriteDenyWithinAllow()));
        Map<String, Object> filesystemConfig = new LinkedHashMap<>();
        filesystemConfig.put("read", fsRead);
        filesystemConfig.put("write", fsWrite);

        Map<String, Object> networkConfig = new LinkedHashMap<>();
        if (s.networkAllowedHosts() != null && !s.networkAllowedHosts().isEmpty()) {
            networkConfig.put("allowedHosts", dedup(s.networkAllowedHosts()));
        }
        if (s.networkDeniedHosts() != null && !s.networkDeniedHosts().isEmpty()) {
            networkConfig.put("deniedHosts", dedup(s.networkDeniedHosts()));
        }
        if (s.allowUnixSockets() != null && !s.allowUnixSockets().isEmpty()) {
            networkConfig.put("allowUnixSockets", dedup(s.allowUnixSockets()));
        }

        List<String> restrictionsLines = new ArrayList<>();
        if (!filesystemConfig.isEmpty()) {
            restrictionsLines.add("Filesystem: " + json(filesystemConfig));
        }
        if (!networkConfig.isEmpty()) {
            restrictionsLines.add("Network: " + json(networkConfig));
        }
        if (s.ignoreViolations() != null && !s.ignoreViolations().isEmpty()) {
            restrictionsLines.add("Ignored violations: " + json(s.ignoreViolations()));
        }

        List<Object> sandboxOverrideItems;
        if (s.allowUnsandboxedCommands()) {
            sandboxOverrideItems = List.of(
                "You should always default to running commands within the sandbox. "
                    + "Do NOT attempt to set `dangerouslyDisableSandbox: true` unless:",
                List.of(
                    "The user *explicitly* asks you to bypass sandbox",
                    "A specific command just failed and you see evidence of sandbox restrictions causing the failure. "
                        + "Note that commands can fail for many reasons unrelated to the sandbox (missing files, "
                        + "wrong arguments, network issues, etc.)."),
                "Evidence of sandbox-caused failures includes:",
                List.of(
                    "\"Operation not permitted\" errors for file/network operations",
                    "Access denied to specific paths outside allowed directories",
                    "Network connection failures to non-whitelisted hosts",
                    "Unix socket connection errors"),
                "When you see evidence of sandbox-caused failure:",
                List.of(
                    "Immediately retry with `dangerouslyDisableSandbox: true` (don't ask, just do it)",
                    "Briefly explain what sandbox restriction likely caused the failure. Be sure to mention that the "
                        + "user can use the `/sandbox` command to manage restrictions.",
                    "This will prompt the user for permission"),
                "Treat each command you execute with `dangerouslyDisableSandbox: true` individually. Even if you have "
                    + "recently run a command with this setting, you should default to running future commands within "
                    + "the sandbox.",
                "Do not suggest adding sensitive paths like ~/.bashrc, ~/.zshrc, ~/.ssh/*, or credential files to the "
                    + "sandbox allowlist.");
        } else {
            sandboxOverrideItems = List.of(
                "All commands MUST run in sandbox mode - the `dangerouslyDisableSandbox` parameter is disabled by policy.",
                "Commands cannot run outside the sandbox under any circumstances.",
                "If a command fails due to sandbox restrictions, work with the user to adjust sandbox settings instead.");
        }

        List<Object> items = new ArrayList<>(sandboxOverrideItems);
        items.add("For temporary files, always use the `$TMPDIR` environment variable. TMPDIR is automatically set "
            + "to the correct sandbox-writable directory in sandbox mode. Do NOT use `/tmp` directly - use `$TMPDIR` "
            + "instead.");

        List<String> section = new ArrayList<>();
        section.add("");
        section.add("## Command sandbox");
        section.add("By default, your command will be run in a sandbox. This sandbox controls which directories and "
            + "network hosts commands may access or modify without an explicit override.");
        section.add("");
        section.add("The sandbox has the following restrictions:");
        section.add(String.join("\n", restrictionsLines));
        section.add("");
        section.addAll(prependBullets(items));
        return String.join("\n", section);
    }

    /**
     * git 指令段 · 对齐 CC getCommitAndPRInstructions()（prompt.ts:42-161）外部用户全量内联分支
     * （Java 非 ant、非 undercover → 不含 undercoverSection，含 attribution 插值）。
     *
     * <p>文本保真：模板字面量（含 git 安全协议/步骤/HEREDOC 示例/PR 流程）逐字移植；attribution
     * 三处插值（commit 尾段、HEREDOC body、PR body）按 CC 条件（null/空 → '.' 或不附加）。
     * 行尾空格经 {@code {{TRAIL}}} 运行时替换保留（text block 剥行尾空白）。
     */
    private static String getCommitAndPRInstructions(PromptInputs in) {
        if (!in.gitInstructionsEnabled()) {
            return "";
        }
        String commitAttribution = in.commitAttribution();
        String prAttribution = in.prAttribution();
        String commitEnding = commitAttribution != null && !commitAttribution.isEmpty()
            ? " ending with:\n   " + commitAttribution
            : ".";
        String commitBody = commitAttribution != null && !commitAttribution.isEmpty()
            ? "\n\n   " + commitAttribution
            : "";
        String prBody = prAttribution != null && !prAttribution.isEmpty()
            ? "\n\n" + prAttribution
            : "";
        String section = GIT_SECTION_TEMPLATE
            .replace("{{TRAIL}}", " ")
            .replace("{{BASH}}", BASH_TOOL_NAME)
            .replace("{{TODO}}", TODO_WRITE_TOOL_NAME)
            .replace("{{AGENT}}", AGENT_TOOL_NAME)
            .replace("{{COMMIT_ENDING}}", commitEnding)
            .replace("{{COMMIT_BODY}}", commitBody)
            .replace("{{PR_BODY}}", prBody);
        if (log.isDebugEnabled()) {
            log.debug("BashToolPrompt.getCommitAndPRInstructions: 渲染 git 指令段, 长度={} (commitAttr={}, prAttr={})",
                section.length(), commitAttribution != null, prAttribution != null);
        }
        return section;
    }

    /** CC original: prependBullets（constants/prompts.ts:167-173）：string → " - x"，array 子项 → "  - x"。 */
    private static List<String> prependBullets(List<Object> items) {
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof List<?> sublist) {
                for (Object sub : sublist) {
                    out.add("  - " + sub);
                }
            } else {
                out.add(" - " + item);
            }
        }
        return out;
    }

    /** CC original: dedup（prompt.ts:167-170）— 空数组原样返回，非空去重保序。 */
    private static List<String> dedup(List<String> arr) {
        if (arr == null || arr.isEmpty()) {
            return arr == null ? List.of() : arr;
        }
        return new ArrayList<>(new LinkedHashSet<>(arr));
    }

    /**
     * CC original: normalizeAllowOnly（prompt.ts:189-190）— 去重 + 把 claudeTempDir 映射为
     * {@code '$TMPDIR'}（Java 用 {@code java.io.tmpdir} 等价 CC getClaudeTempDir()）。
     */
    private static List<String> normalizeAllowOnly(List<String> paths) {
        Set<String> deduped = new LinkedHashSet<>(paths == null ? List.of() : paths);
        String tmpDir = System.getProperty("java.io.tmpdir", "");
        List<String> out = new ArrayList<>(deduped.size());
        for (String p : deduped) {
            out.add(p.equals(tmpDir) ? "$TMPDIR" : p);
        }
        return out;
    }

    /** CC original: jsonStringify（slowOperations.ts:170-194）— JSON.stringify 紧凑序列化，保插入序。 */
    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(toJsonNode(value));
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("BashToolPrompt.json 序列化失败（回退 toString）: {}", e.toString());
            }
            return String.valueOf(value);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode toJsonNode(Object value) {
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = JSON.createObjectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                node.set(String.valueOf(e.getKey()), toJsonNode(e.getValue()));
            }
            return node;
        }
        if (value instanceof List<?> list) {
            ArrayNode node = JSON.createArrayNode();
            for (Object o : list) {
                node.add(toJsonNode(o));
            }
            return node;
        }
        return JSON.valueToTree(value);
    }

    /**
     * git 指令段模板（含 {@code {{...}}} 运行时替换标记）· 逐字移植 CC getCommitAndPRInstructions
     * 外部分支模板字面量（prompt.ts:81-160）。
     *
     * <p>固定文本：git 安全协议 + 4 步 commit 流程 + HEREDOC 示例 + PR 创建流程 + 其他常用操作。
     * {@code {{TRAIL}}} 保留「given direct instructions 」行尾空格（text block 会剥，运行时补）。
     */
    private static final String GIT_SECTION_TEMPLATE = """
            # Committing changes with git

            Only create commits when requested by the user. If unclear, ask first. When the user asks you to create a new git commit, follow these steps carefully:

            You can call multiple tools in a single response. When multiple independent pieces of information are requested and all commands are likely to succeed, run multiple tool calls in parallel for optimal performance. The numbered steps below indicate which commands should be batched in parallel.

            Git Safety Protocol:
            - NEVER update the git config
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., restore ., clean -f, branch -D) unless the user explicitly requests these actions. Taking unauthorized destructive actions is unhelpful and can result in lost work, so it's best to ONLY run these commands when given direct instructions{{TRAIL}}
            - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
            - NEVER run force push to main/master, warn the user if they request it
            - CRITICAL: Always create NEW commits rather than amending, unless the user explicitly requests a git amend. When a pre-commit hook fails, the commit did NOT happen — so --amend would modify the PREVIOUS commit, which may result in destroying work or losing previous changes. Instead, after hook failure, fix the issue, re-stage, and create a NEW commit
            - When staging files, prefer adding specific files by name rather than using "git add -A" or "git add .", which can accidentally include sensitive files (.env, credentials) or large binaries
            - NEVER commit changes unless the user explicitly asks you to. It is VERY IMPORTANT to only commit when explicitly asked, otherwise the user will feel that you are being too proactive

            1. Run the following bash commands in parallel, each using the {{BASH}} tool:
              - Run a git status command to see all untracked files. IMPORTANT: Never use the -uall flag as it can cause memory issues on large repos.
              - Run a git diff command to see both staged and unstaged changes that will be committed.
              - Run a git log command to see recent commit messages, so that you can follow this repository's commit message style.
            2. Analyze all staged changes (both previously staged and newly added) and draft a commit message:
              - Summarize the nature of the changes (eg. new feature, enhancement to an existing feature, bug fix, refactoring, test, docs, etc.). Ensure the message accurately reflects the changes and their purpose (i.e. "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.).
              - Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files
              - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"
              - Ensure it accurately reflects the changes and their purpose
            3. Run the following commands in parallel:
               - Add relevant untracked files to the staging area.
               - Create the commit with a message{{COMMIT_ENDING}}
               - Run git status after the commit completes to verify success.
               Note: git status depends on the commit completing, so run it sequentially after the commit.
            4. If the commit fails due to pre-commit hook: fix the issue and create a NEW commit

            Important notes:
            - NEVER run additional commands to read or explore code, besides git bash commands
            - NEVER use the {{TODO}} or {{AGENT}} tools
            - DO NOT push to the remote repository unless the user explicitly asks you to do so
            - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
            - IMPORTANT: Do not use --no-edit with git rebase commands, as the --no-edit flag is not a valid option for git rebase.
            - If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
            - In order to ensure good formatting, ALWAYS pass the commit message via a HEREDOC, a la this example:
            <example>
            git commit -m "$(cat <<'EOF'
               Commit message here.{{COMMIT_BODY}}
               EOF
               )"
            </example>

            # Creating pull requests
            Use the gh command via the Bash tool for ALL GitHub-related tasks including working with issues, pull requests, checks, and releases. If given a Github URL use the gh command to get the information needed.

            IMPORTANT: When the user asks you to create a pull request, follow these steps carefully:

            1. Run the following bash commands in parallel using the {{BASH}} tool, in order to understand the current state of the branch since it diverged from the main branch:
               - Run a git status command to see all untracked files (never use -uall flag)
               - Run a git diff command to see both staged and unstaged changes that will be committed
               - Check if the current branch tracks a remote branch and is up to date with the remote, so you know if you need to push to the remote
               - Run a git log command and `git diff [base-branch]...HEAD` to understand the full commit history for the current branch (from the time it diverged from the base branch)
            2. Analyze all changes that will be included in the pull request, making sure to look at all relevant commits (NOT just the latest commit, but ALL commits that will be included in the pull request!!!), and draft a pull request title and summary:
               - Keep the PR title short (under 70 characters)
               - Use the description/body for details, not the title
            3. Run the following commands in parallel:
               - Create new branch if needed
               - Push to remote with -u flag if needed
               - Create PR using gh pr create with the format below. Use a HEREDOC to pass the body to ensure correct formatting.
            <example>
            gh pr create --title "the pr title" --body "$(cat <<'EOF'
            ## Summary
            <1-3 bullet points>

            ## Test plan
            [Bulleted markdown checklist of TODOs for testing the pull request...]{{PR_BODY}}
            EOF
            )"
            </example>

            Important:
            - DO NOT use the {{TODO}} or {{AGENT}} tools
            - Return the PR URL when you're done, so the user can see it

            # Other common operations
            - View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments
            """;
}

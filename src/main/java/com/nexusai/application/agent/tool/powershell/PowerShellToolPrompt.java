package com.nexusai.application.agent.tool.powershell;

import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_EDIT_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.FILE_WRITE_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.GLOB_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.GREP_TOOL_NAME;
import static com.nexusai.application.agent.tool.ToolNameConstants.POWER_SHELL_TOOL_NAME;

/**
 * PowerShell 工具系统提示词 · 对齐 CC {@code tools/PowerShellTool/prompt.ts} {@code getPrompt()}（全量 145 行）。
 *
 * <p><b>WHY（G10）</b>：CC {@code PowerShellTool.tsx:282-284} {@code async prompt() { return getPrompt() }}，
 * Java PowerShellTool 未 override {@link com.nexusai.application.agent.tool.Tool#prompt()}（默认回退
 * description()）→ LLM 缺失 PowerShell 使用规范 / 版本特定语法引导 / Start-Sleep 滥用抑制，模型产出
 * PS 5.1/UTF-16/Start-Sleep 滥用概率上升（决策拍板 G10 评级 HIGH）。本类移植 CC getPrompt() 全量文本，
 * 消费点 {@code ToolRegistry.toOpenAiToolsArray}（prompt() 非 null 优先于 description()）注入 LLM 可见工具描述。
 *
 * <p><b>真源</b>：{@code Open-ClaudeCode/src/tools/PowerShellTool/prompt.ts:73-145 getPrompt()} +
 * {@code getBackgroundUsageNote()}（:26-31）+ {@code getSleepGuidance()}（:33-44）+
 * {@code getEditionSection()}（:51-71）。动态值来源（Java 等价）：
 * <ul>
 *   <li>{@code getDefaultTimeoutMs()/getMaxTimeoutMs()}（prompt.ts:18-24 → timeouts.ts:2-3）→
 *       {@link #DEFAULT_TIMEOUT_MS}/{@link #MAX_TIMEOUT_MS}（Java PowerShellTool 执行默认 120s 常量一致）</li>
 *   <li>{@code getMaxOutputLength()}（prompt.ts:125 → outputLimits.ts:5，默认 30000）→
 *       {@link #MAX_OUTPUT_LENGTH}（Java BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT 一致）</li>
 *   <li>{@code CLAUDE_CODE_DISABLE_BACKGROUND_TASKS}（backgroundNote/sleepGuidance 为 null 分叉）→
 *       {@link PromptInputs#backgroundTasksDisabled()}（Java PowerShellTool 与 BashTool 同款开关）</li>
 *   <li>{@code getPowerShellEdition()}（prompt.ts:76 → powershellDetection.ts:87-100，pwsh basename
 *       派生 core/desktop）→ {@link PowerShellEdition}（Java {@code PowerShellTool.resolvePwshPath()}
 *       派生，见 {@link PowerShellEdition#fromPwshPath}）</li>
 * </ul>
 *
 * <p><b>文本保真</b>：所有固定文本逐字移植 CC 模板字面量（含 U+2019 ’、U+2014 —、反引号、行内
 * {@code <example>} 块）。工具名经 {@link PromptInputs} 注入（Java ToolNameConstants 值：
 * Glob/Grep/Read/Edit/Write/PowerShell，与 CC prompt.ts 各 *_TOOL_NAME 常量一致）。
 */
public final class PowerShellToolPrompt {

    /** CC original: getDefaultTimeoutMs() = getDefaultBashTimeoutMs() = 120_000（timeouts.ts:2，Java 120s 常量一致）。 */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** CC original: getMaxTimeoutMs() = getMaxBashTimeoutMs() = 600_000（timeouts.ts:3，Java 600s 常量一致）。 */
    public static final long MAX_TIMEOUT_MS = 600_000L;

    /** CC original: getMaxOutputLength() 默认 30000（outputLimits.ts:5；Java BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT 一致）。 */
    public static final long MAX_OUTPUT_LENGTH = 30_000L;

    private PowerShellToolPrompt() {
    }

    /** PowerShell 版本 · 对齐 CC {@code PowerShellEdition}（powershellDetection.ts:3-9）。 */
    public enum PowerShellEdition {
        /** Windows PowerShell 5.1（powershell.exe）· CC 'desktop'。 */
        DESKTOP,
        /** PowerShell 7+（pwsh）· CC 'core'。 */
        CORE,
        /** 未探测（pwsh 缺失/首 prompt 构建前）· CC null → 保守 5.1-safe 引导。 */
        UNKNOWN;

        /**
         * 从 pwsh 路径派生版本 · 对齐 CC {@code getPowerShellEdition()}（powershellDetection.ts:87-100）：
         * basename（剥 .exe）== 'pwsh' → core，否则（powershell/powershell.exe）→ desktop；
         * null（无 pwsh）→ unknown。
         *
         * @param pwshPath 解析后的 pwsh 可执行路径（null = 未安装）
         */
        public static PowerShellEdition fromPwshPath(String pwshPath) {
            if (pwshPath == null || pwshPath.isBlank()) {
                return UNKNOWN;
            }
            String base = pwshPath;
            int cut = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (cut >= 0) {
                base = base.substring(cut + 1);
            }
            String lower = base.toLowerCase();
            if (lower.endsWith(".exe")) {
                lower = lower.substring(0, lower.length() - 4);
            }
            return "pwsh".equals(lower) ? CORE : DESKTOP;
        }
    }

    /** 提示词输入 · 对齐 CC getPrompt() 消费的动态值。 */
    public record PromptInputs(
            boolean backgroundTasksDisabled,
            PowerShellEdition edition,
            long defaultTimeoutMs,
            long maxTimeoutMs,
            long maxOutputLength) {

        public static PromptInputs defaults() {
            return new PromptInputs(false, PowerShellEdition.UNKNOWN,
                DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS, MAX_OUTPUT_LENGTH);
        }
    }

    /**
     * CC getBackgroundUsageNote()（prompt.ts:26-31）· background tasks 禁用时为 null。
     */
    private static String getBackgroundUsageNote(boolean backgroundTasksDisabled) {
        if (backgroundTasksDisabled) {
            return null;
        }
        return "  - You can use the `run_in_background` parameter to run the command in the background. "
            + "Only use this if you don't need the result immediately and are OK being notified when the command completes later. "
            + "You do not need to check the output right away - you'll be notified when it finishes.";
    }

    /**
     * CC getSleepGuidance()（prompt.ts:33-44）· background tasks 禁用时为 null。
     */
    private static String getSleepGuidance(boolean backgroundTasksDisabled) {
        if (backgroundTasksDisabled) {
            return null;
        }
        return "  - Avoid unnecessary `Start-Sleep` commands:\n"
            + "    - Do not sleep between commands that can run immediately — just run them.\n"
            + "    - If your command is long running and you would like to be notified when it finishes — simply run your command using `run_in_background`. There is no need to sleep in this case.\n"
            + "    - Do not retry failing commands in a sleep loop — diagnose the root cause or consider an alternative approach.\n"
            + "    - If waiting for a background task you started with `run_in_background`, you will be notified when it completes — do not poll.\n"
            + "    - If you must poll an external process, use a check command rather than sleeping first.\n"
            + "    - If you must sleep, keep the duration short (1-5 seconds) to avoid blocking the user.";
    }

    /**
     * CC getEditionSection()（prompt.ts:51-71）· 版本特定语法引导。
     */
    private static String getEditionSection(PowerShellEdition edition) {
        if (edition == PowerShellEdition.DESKTOP) {
            return "PowerShell edition: Windows PowerShell 5.1 (powershell.exe)\n"
                + "   - Pipeline chain operators `&&` and `||` are NOT available — they cause a parser error. To run B only if A succeeds: `A; if ($?) { B }`. To chain unconditionally: `A; B`.\n"
                + "   - Ternary (`?:`), null-coalescing (`??`), and null-conditional (`?.`) operators are NOT available. Use `if/else` and explicit `$null -eq` checks instead.\n"
                + "   - Avoid `2>&1` on native executables. In 5.1, redirecting a native command's stderr inside PowerShell wraps each line in an ErrorRecord (NativeCommandError) and sets `$?` to `$false` even when the exe returned exit code 0. stderr is already captured for you — don't redirect it.\n"
                + "   - Default file encoding is UTF-16 LE (with BOM). When writing files other tools will read, pass `-Encoding utf8` to `Out-File`/`Set-Content`.\n"
                + "   - `ConvertFrom-Json` returns a PSCustomObject, not a hashtable. `-AsHashtable` is not available.";
        }
        if (edition == PowerShellEdition.CORE) {
            return "PowerShell edition: PowerShell 7+ (pwsh)\n"
                + "   - Pipeline chain operators `&&` and `||` ARE available and work like bash. Prefer `cmd1 && cmd2` over `cmd1; cmd2` when cmd2 should only run if cmd1 succeeds.\n"
                + "   - Ternary (`$cond ? $a : $b`), null-coalescing (`??`), and null-conditional (`?.`) operators are available.\n"
                + "   - Default file encoding is UTF-8 without BOM.";
        }
        // 探测未决（首 prompt 构建前）/ PS 未安装 → 保守 5.1-safe 引导（CC :67-70）
        return "PowerShell edition: unknown — assume Windows PowerShell 5.1 for compatibility\n"
            + "   - Do NOT use `&&`, `||`, ternary `?:`, null-coalescing `??`, or null-conditional `?.`. These are PowerShell 7+ only and parser-error on 5.1.\n"
            + "   - To chain commands conditionally: `A; if ($?) { B }`. Unconditionally: `A; B`.";
    }

    /**
     * CC getPrompt()（prompt.ts:73-145）· 全量提示词。逐字对齐 CC 模板（含背景注/sleep 引导条件插入）。
     *
     * <p>工具名经参数注入（Java ToolNameConstants 值：{@code GLOB_TOOL_NAME}/{@code GREP_TOOL_NAME}/
     * {@code FILE_READ_TOOL_NAME}/{@code FILE_EDIT_TOOL_NAME}/{@code FILE_WRITE_TOOL_NAME}/
     * {@code POWER_SHELL_TOOL_NAME}，与 CC prompt.ts:11-16 各 *_TOOL_NAME 常量一致）。
     */
    public static String getPrompt(PromptInputs in) {
        String backgroundNote = getBackgroundUsageNote(in.backgroundTasksDisabled());
        String sleepGuidance = getSleepGuidance(in.backgroundTasksDisabled());
        String editionSection = getEditionSection(in.edition());

        StringBuilder sb = new StringBuilder();
        sb.append("Executes a given PowerShell command with optional timeout. Working directory persists between commands; shell state (variables, functions) does not.\n\n");
        sb.append("IMPORTANT: This tool is for terminal operations via PowerShell: git, npm, docker, and PS cmdlets. DO NOT use it for file operations (reading, writing, editing, searching, finding files) - use the specialized tools for this instead.\n\n");
        sb.append(editionSection).append("\n\n");
        sb.append("Before executing the command, please follow these steps:\n\n");
        sb.append("1. Directory Verification:\n");
        sb.append("   - If the command will create new directories or files, first use `Get-ChildItem` (or `ls`) to verify the parent directory exists and is the correct location\n\n");
        sb.append("2. Command Execution:\n");
        sb.append("   - Always quote file paths that contain spaces with double quotes\n");
        sb.append("   - Capture the output of the command.\n\n");
        sb.append("PowerShell Syntax Notes:\n");
        sb.append("   - Variables use $ prefix: $myVar = \"value\"\n");
        sb.append("   - Escape character is backtick (`), not backslash\n");
        sb.append("   - Use Verb-Noun cmdlet naming: Get-ChildItem, Set-Location, New-Item, Remove-Item\n");
        sb.append("   - Common aliases: ls (Get-ChildItem), cd (Set-Location), cat (Get-Content), rm (Remove-Item)\n");
        sb.append("   - Pipe operator | works similarly to bash but passes objects, not text\n");
        sb.append("   - Use Select-Object, Where-Object, ForEach-Object for filtering and transformation\n");
        sb.append("   - String interpolation: \"Hello $name\" or \"Hello $($obj.Property)\"\n");
        sb.append("   - Registry access uses PSDrive prefixes: `HKLM:\\SOFTWARE\\...`, `HKCU:\\...` — NOT raw `HKEY_LOCAL_MACHINE\\...`\n");
        sb.append("   - Environment variables: read with `$env:NAME`, set with `$env:NAME = \"value\"` (NOT `Set-Variable` or bash `export`)\n");
        sb.append("   - Call native exe with spaces in path via call operator: `& \"C:\\Program Files\\App\\app.exe\" arg1 arg2`\n\n");
        sb.append("Interactive and blocking commands (will hang — this tool runs with -NonInteractive):\n");
        sb.append("   - NEVER use `Read-Host`, `Get-Credential`, `Out-GridView`, `$Host.UI.PromptForChoice`, or `pause`\n");
        sb.append("   - Destructive cmdlets (`Remove-Item`, `Stop-Process`, `Clear-Content`, etc.) may prompt for confirmation. Add `-Confirm:$false` when you intend the action to proceed. Use `-Force` for read-only/hidden items.\n");
        sb.append("   - Never use `git rebase -i`, `git add -i`, or other commands that open an interactive editor\n\n");
        sb.append("Passing multiline strings (commit messages, file content) to native executables:\n");
        sb.append("   - Use a single-quoted here-string so PowerShell does not expand `$` or backticks inside. The closing `'@` MUST be at column 0 (no leading whitespace) on its own line — indenting it is a parse error:\n");
        sb.append("<example>\n");
        sb.append("git commit -m @'\n");
        sb.append("Commit message here.\n");
        sb.append("Second line with $literal dollar signs.\n");
        sb.append("'@\n");
        sb.append("</example>\n");
        sb.append("   - Use `@'...'@` (single-quoted, literal) not `@\"...\"@` (double-quoted, interpolated) unless you need variable expansion\n");
        sb.append("   - For arguments containing `-`, `@`, or other characters PowerShell parses as operators, use the stop-parsing token: `git log --% --format=%H`\n\n");
        sb.append("Usage notes:\n");
        sb.append("  - The command argument is required.\n");
        sb.append("  - You can specify an optional timeout in milliseconds (up to ").append(in.maxTimeoutMs())
            .append("ms / ").append(in.maxTimeoutMs() / 60000).append(" minutes). If not specified, commands will timeout after ")
            .append(in.defaultTimeoutMs()).append("ms (").append(in.defaultTimeoutMs() / 60000).append(" minutes).\n");
        sb.append("  - It is very helpful if you write a clear, concise description of what this command does.\n");
        sb.append("  - If the output exceeds ").append(in.maxOutputLength())
            .append(" characters, output will be truncated before being returned to you.\n");
        if (backgroundNote != null) {
            sb.append(backgroundNote).append('\n');
        }
        sb.append("  - Avoid using PowerShell to run commands that have dedicated tools, unless explicitly instructed:\n");
        sb.append("    - File search: Use ").append(GLOB_TOOL_NAME).append(" (NOT Get-ChildItem -Recurse)\n");
        sb.append("    - Content search: Use ").append(GREP_TOOL_NAME).append(" (NOT Select-String)\n");
        sb.append("    - Read files: Use ").append(FILE_READ_TOOL_NAME).append(" (NOT Get-Content)\n");
        sb.append("    - Edit files: Use ").append(FILE_EDIT_TOOL_NAME).append('\n');
        sb.append("    - Write files: Use ").append(FILE_WRITE_TOOL_NAME).append(" (NOT Set-Content/Out-File)\n");
        sb.append("    - Communication: Output text directly (NOT Write-Output/Write-Host)\n");
        sb.append("  - When issuing multiple commands:\n");
        sb.append("    - If the commands are independent and can run in parallel, make multiple ").append(POWER_SHELL_TOOL_NAME).append(" tool calls in a single message.\n");
        sb.append("    - If the commands depend on each other and must run sequentially, chain them in a single ").append(POWER_SHELL_TOOL_NAME).append(" call (see edition-specific chaining syntax above).\n");
        sb.append("    - Use `;` only when you need to run commands sequentially but don't care if earlier commands fail.\n");
        sb.append("    - DO NOT use newlines to separate commands (newlines are ok in quoted strings and here-strings)\n");
        sb.append("  - Do NOT prefix commands with `cd` or `Set-Location` -- the working directory is already set to the correct project directory automatically.\n");
        if (sleepGuidance != null) {
            sb.append(sleepGuidance).append('\n');
        }
        sb.append("  - For git commands:\n");
        sb.append("    - Prefer to create a new commit rather than amending an existing commit.\n");
        sb.append("    - Before running destructive operations (e.g., git reset --hard, git push --force, git checkout --), consider whether there is a safer alternative that achieves the same goal. Only use destructive operations when they are truly the best approach.\n");
        sb.append("    - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false) unless the user has explicitly asked for it. If a hook fails, investigate and fix the underlying issue.");
        return sb.toString();
    }
}

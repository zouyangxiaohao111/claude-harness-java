package com.nexusai.application.agent.memory;

/**
 * ExtractMemoriesOpener · 对齐 CC services/extractMemories/prompts.ts:29-44 opener().
 *
 * <p>L1 语义: 后台 memory 提取 agent prompt 的共享开头段落。
 * 嵌入 5 个 tool name + 双轮策略(read-all-then-edit-all) + manifest 注入(若有现有 memories)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #render(int, String, String, String, String, String, String)} (newMessageCount, existing, fileRead, fileEdit, fileWrite, glob, grep) → String</li>
 *   <li><b>A2 Golden Trace</b>: existingMemories 非空 → '## Existing memory files' 段;空 → 无段;always 含 5 tool name + 双轮策略</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: existingMemories null/empty → omit 段;tool names null 时 String.format %s 抛 NPE (caller 保证)</li>
 *   <li><b>A5 业务场景</b>: extraction agent prompt begins with this opener + manifest</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal + dynamic imports → Java text block +
 * tool names 注入 (caller 控制);TS array.join('\n') → Java 字符串拼接。
 */
public final class ExtractMemoriesOpener {

    private ExtractMemoriesOpener() {}

    // ════════════════════════════════════════════════════════════════
    // CC tool name 常量 · prompts.ts:19-24
    // ════════════════════════════════════════════════════════════════

    /** CC FILE_READ_TOOL_NAME (prompts.ts:19) */
    public static final String FILE_READ_TOOL_NAME = "Read";
    /** CC FILE_EDIT_TOOL_NAME (prompts.ts:20) */
    public static final String FILE_EDIT_TOOL_NAME = "Edit";
    /** CC FILE_WRITE_TOOL_NAME (prompts.ts:21) */
    public static final String FILE_WRITE_TOOL_NAME = "Write";
    /** CC GLOB_TOOL_NAME (prompts.ts:22) */
    public static final String GLOB_TOOL_NAME = "Glob";
    /** CC GREP_TOOL_NAME (prompts.ts:23) */
    public static final String GREP_TOOL_NAME = "Grep";
    /** CC BASH_TOOL_NAME (prompts.ts:24) */
    public static final String BASH_TOOL_NAME = "Bash";

    /**
     * Render the shared opener section for both extract-prompt variants.
     *
     * @param newMessageCount number of recent messages to analyze
     * @param existingMemories manifest text from formatMemoryManifest; empty string if none
     * @param fileReadToolName CC FILE_READ_TOOL_NAME (e.g. "Read")
     * @param fileEditToolName CC FILE_EDIT_TOOL_NAME (e.g. "Edit")
     * @param fileWriteToolName CC FILE_WRITE_TOOL_NAME (e.g. "Write")
     * @param globToolName CC GLOB_TOOL_NAME (e.g. "Glob")
     * @param grepToolName CC GREP_TOOL_NAME (e.g. "Grep")
     * @param bashToolName CC BASH_TOOL_NAME (e.g. "Bash")
     */
    public static String render(
        int newMessageCount,
        String existingMemories,
        String fileReadToolName,
        String fileEditToolName,
        String fileWriteToolName,
        String globToolName,
        String grepToolName,
        String bashToolName) {

        String manifest = (existingMemories == null || existingMemories.isEmpty())
            ? ""
            : "\n\n## Existing memory files\n\n" + existingMemories +
              "\n\nCheck this list before writing — update an existing file rather than creating a duplicate.";
        return """
            You are now acting as the memory extraction subagent. Analyze the most recent ~%d messages above and use them to update your persistent memory systems.

            Available tools: %s, %s, %s, read-only %s (ls/find/cat/stat/wc/head/tail and similar), and %s/%s for paths inside the memory directory only. %s rm is not permitted. All other tools — MCP, Agent, write-capable %s, etc — will be denied.

            You have a limited turn budget. %s requires a prior %s of the same file, so the efficient strategy is: turn 1 — issue all %s calls in parallel for every file you might update; turn 2 — issue all %s/%s calls in parallel. Do not interleave reads and writes across multiple turns.

            You MUST only use content from the last ~%d messages to update your persistent memories. Do not waste any turns attempting to investigate or verify that content further — no grepping source files, no reading code to confirm a pattern exists, no git commands.%s
            """.formatted(
                newMessageCount,
                fileReadToolName, grepToolName, globToolName, bashToolName,
                fileEditToolName, fileWriteToolName, bashToolName, bashToolName,
                fileEditToolName, fileReadToolName, fileReadToolName,
                fileWriteToolName, fileEditToolName,
                newMessageCount,
                manifest);
    }

    /**
     * Build the extraction prompt for auto-only memory (no team memory).
     * Four-type taxonomy, no scope guidance (single directory) · CC original:
     * {@code buildExtractAutoOnlyPrompt} (prompts.ts:50-94)。
     *
     * @param newMessageCount 最近可见消息数（游标计数）
     * @param existingMemories manifest 文本（formatMemoryManifest 输出；空 = 无）
     * @param skipIndex       是否跳过 MEMORY.md 两步索引（CC skipIndex，tengu_moth_copse）
     * @return 完整提取 prompt
     */
    public static String buildExtractAutoOnlyPrompt(
            int newMessageCount, String existingMemories, boolean skipIndex) {
        String howToSave;
        if (skipIndex) {
            // CC prompts.ts:56-66（skipIndex=true：单步写文件，不更新 MEMORY.md 索引）
            howToSave = "## How to save memories\n"
                + "\n"
                + "Write each memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:\n"
                + "\n"
                // DC-7 去重：单一声明在 MemoryPromptBuilder（CC memoryTypes.ts:261-271）。
                // MPB sectionNoTrailing 去尾空元素 → join 无尾 "\n"，此处补至 2 换行（对齐文本块+显式 "\n"）。
                + String.join("\n", MemoryPromptBuilder.MEMORY_FRONTMATTER_EXAMPLE) + "\n\n"
                + "- Organize memory semantically by topic, not chronologically\n"
                + "- Update or remove memories that turn out to be wrong or outdated\n"
                // [rev2 EX-03②] 末行无尾 "\n"（CC array-join('\n') 无尾换行）
                + "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.";
        } else {
            // CC prompts.ts:68-82（skipIndex=false：两步保存 = 写文件 + MEMORY.md 加索引行）
            howToSave = "## How to save memories\n"
                + "\n"
                + "Saving a memory is a two-step process:\n"
                + "\n"
                + "**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:\n"
                + "\n"
                // DC-7 去重：单一声明在 MemoryPromptBuilder（CC memoryTypes.ts:261-271）。
                // MPB sectionNoTrailing 去尾空元素 → join 无尾 "\n"，此处补至 2 换行（对齐文本块+显式 "\n"）。
                + String.join("\n", MemoryPromptBuilder.MEMORY_FRONTMATTER_EXAMPLE) + "\n\n"
                + "**Step 2** — add a pointer to that file in `MEMORY.md`. `MEMORY.md` is an index, not a memory — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. It has no frontmatter. Never write memory content directly into `MEMORY.md`.\n"
                + "\n"
                + "- `MEMORY.md` is always loaded into your system prompt — lines after 200 will be truncated, so keep the index concise\n"
                + "- Organize memory semantically by topic, not chronologically\n"
                + "- Update or remove memories that turn out to be wrong or outdated\n"
                // [rev2 EX-03②] 末行无尾 "\n"（CC array-join('\n') 无尾换行）
                + "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.";
        }
        String opener = render(newMessageCount, existingMemories,
            FILE_READ_TOOL_NAME, FILE_EDIT_TOOL_NAME, FILE_WRITE_TOOL_NAME,
            GLOB_TOOL_NAME, GREP_TOOL_NAME, BASH_TOOL_NAME);
        // [rev2 EX-03①] opener 尾 3→2 换行：Java render() text block 尾换行 + 原 2 个显式 "\n"
        //   = 3 换行 vs CC [opener,'',"If the user…"].join('\n') = 2 换行 —— 去掉 1 个显式 "\n"。
        //   opener 本身（text block 尾换行）与 CC join 的段落间隔恰好互补，字节对齐。
        return opener
            + "\n"
            + "If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.\n"
            + "\n"
            // DC-7 去重：单一声明在 MemoryPromptBuilder（CC memoryTypes.ts:113-178/:183-195）。
            // TYPES 经 MPB section() 保留尾 ''（CC :177）→ join 尾 "\n"，+ "\n" = 2 换行；
            // WHAT 经 sectionNoTrailing 去尾空元素 → join 无尾 "\n"，补至 "\n\n"。
            + String.join("\n", MemoryPromptBuilder.TYPES_SECTION_INDIVIDUAL) + "\n"
            + String.join("\n", MemoryPromptBuilder.WHAT_NOT_TO_SAVE_SECTION) + "\n\n"
            + howToSave;
    }

    /**
     * Build the extraction prompt for combined auto + team memory. · CC original:
     * {@code buildExtractCombinedPrompt} (prompts.ts:101-154)。
     *
     * <p><b>TEAMMEM 门控</b>（prompts.ts:106-112）：CC 在 feature('TEAMMEM') 关闭时
     * 直接回退 buildExtractAutoOnlyPrompt。Java 端 team-memory 全链归 IMP-M-P1-4，
     * 当前 TEAMMEM 关闭 → 本方法恒委托 auto-only 变体（对齐 CC 关闭时行为，非简化）。
     */
    public static String buildExtractCombinedPrompt(
            int newMessageCount, String existingMemories, boolean skipIndex) {
        return buildExtractAutoOnlyPrompt(newMessageCount, existingMemories, skipIndex);
    }
}

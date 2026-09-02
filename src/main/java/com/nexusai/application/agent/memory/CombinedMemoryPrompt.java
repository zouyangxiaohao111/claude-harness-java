package com.nexusai.application.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * CombinedMemoryPrompt · 对齐 CC {@code memdir/teamMemPrompts.ts:22-100} buildCombinedMemoryPrompt().
 *
 * <p>当 auto memory + team memory 同时启用时合并两者的 LLM 提示构建器（CC loadMemoryPrompt
 * TEAMMEM 分支 memdir.ts:448-472）。旧 Java 版含反 CC 目录存在性指导文本
 * （DEL-M-01 已废除），且结构自造「## Memory scope」扁平两 bullet；本版逐字对齐 CC：
 * 四类 taxonomy（COMBINED 含 {@code <scope>} 标签）、两步保存、TRUSTING_RECALL、
 * drift caveat、searching-past-context。
 *
 * <p>纯函数静态类：目录由调用方（{@link MemoryPromptBuilder#loadMemoryPrompt()}）解析传入，
 * searching-past 段由调用方按 tengu_coral_fern 门控拼装后注入。
 */
public final class CombinedMemoryPrompt {

    private CombinedMemoryPrompt() {}

    /**
     * 构建合并 prompt（auto + team）· CC original: buildCombinedMemoryPrompt（teamMemPrompts.ts:22-100）。
     *
     * @param autoDir                    private memory 目录
     * @param teamDir                    shared team memory 目录（CC getTeamMemPath = join(autoMemPath, 'team')）
     * @param extraGuidelines            CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES 注入段（可为 null）
     * @param skipIndex                  CC tengu_moth_copse flag：true 时省略两步保存
     * @param searchingPastContextSection 调用方按 tengu_coral_fern 门控拼装的 searching-past 段文本（可为空串）
     * @return 合并 prompt 文本
     */
    public static String buildCombinedMemoryPrompt(
        String autoDir, String teamDir,
        List<String> extraGuidelines,
        boolean skipIndex,
        String searchingPastContextSection) {

        String safeDirName1 = autoDir == null ? "" : autoDir;
        String safeDirName2 = teamDir == null ? "" : teamDir;
        List<String> extras = (extraGuidelines == null || extraGuidelines.isEmpty())
            ? List.of()
            : extraGuidelines;

        List<String> howToSave = new ArrayList<>();
        if (skipIndex) {
            howToSave.add("## How to save memories");
            howToSave.add("");
            howToSave.add("Write each memory to its own file in the chosen directory (private or team, per the type's scope guidance) using this frontmatter format:");
            howToSave.add("");
            howToSave.addAll(MemoryPromptBuilder.MEMORY_FRONTMATTER_EXAMPLE);
            // [G-105] skipIndex 分支 FRONTMATTER 与 bullets 间补空行（DRIFT-17）·
            //   CC teamMemPrompts.ts:30-41 `...MEMORY_FRONTMATTER_EXAMPLE, '', '- Keep the name…'`
            //   —— 旧实现缺该 '' 空行（输出差一个换行，NOT_ALIGNED）。
            howToSave.add("");
            howToSave.addAll(commonSaveBullets());
        } else {
            howToSave.add("## How to save memories");
            howToSave.add("");
            howToSave.add("Saving a memory is a two-step process:");
            howToSave.add("");
            howToSave.add("**Step 1** — write the memory to its own file in the chosen directory (private or team, per the type's scope guidance) using this frontmatter format:");
            howToSave.add("");
            howToSave.addAll(MemoryPromptBuilder.MEMORY_FRONTMATTER_EXAMPLE);
            howToSave.add("");
            howToSave.add("**Step 2** — add a pointer to that file in the same directory's `" + MemoryPromptBuilder.ENTRYPOINT_NAME + "`. Each directory (private and team) has its own `" + MemoryPromptBuilder.ENTRYPOINT_NAME + "` index — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. They have no frontmatter. Never write memory content directly into a `" + MemoryPromptBuilder.ENTRYPOINT_NAME + "`.");
            howToSave.add("");
            howToSave.add("- Both `" + MemoryPromptBuilder.ENTRYPOINT_NAME + "` indexes are loaded into your conversation context — lines after " + MemoryPromptBuilder.MAX_ENTRYPOINT_LINES + " will be truncated, so keep them concise");
            howToSave.addAll(commonSaveBullets());
        }

        return buildBody(safeDirName1, safeDirName2, extras, howToSave, searchingPastContextSection);
    }

    private static List<String> commonSaveBullets() {
        return List.of(
            "- Keep the name, description, and type fields in memory files up-to-date with the content",
            "- Organize memory semantically by topic, not chronologically",
            "- Update or remove memories that turn out to be wrong or outdated",
            "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one.");
    }

    /**
     * CC original: buildCombinedMemoryPrompt 主体文本（teamMemPrompts.ts:60-99）。
     * DIRS_EXIST_GUIDANCE 用 CC 文本「Both directories already exist — write to them directly
     * with the Write tool (do not run mkdir or check for their existence).」（DEL-M-01 反 CC 文本已删除）。
     */
    private static String buildBody(String autoDir, String teamDir, List<String> extras,
                                    List<String> howToSave, String searchingPast) {
        List<String> lines = new ArrayList<>();
        lines.add("# Memory");
        lines.add("");
        lines.add("You have a persistent, file-based memory system with two directories: a private directory at `" + autoDir
            + "` and a shared team directory at `" + teamDir + "`. " + MemoryPromptBuilder.DIRS_EXIST_GUIDANCE);
        lines.add("");
        lines.add("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.");
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.");
        lines.add("");
        lines.add("## Memory scope");
        lines.add("");
        lines.add("There are two scope levels:");
        lines.add("");
        lines.add("- private: memories that are private between you and the current user. They persist across conversations with only this specific user and are stored at the root `" + autoDir + "`.");
        lines.add("- team: memories that are shared with and contributed by all of the users who work within this project directory. Team memories are synced at the beginning of every session and they are stored at `" + teamDir + "`.");
        lines.add("");
        lines.addAll(MemoryPromptBuilder.TYPES_SECTION_COMBINED);
        lines.addAll(MemoryPromptBuilder.WHAT_NOT_TO_SAVE_SECTION);
        lines.add("- You MUST avoid saving sensitive data within shared team memories. For example, never save API keys or user credentials.");
        lines.add("");
        lines.addAll(howToSave);
        lines.add("");
        lines.add("## When to access memories");
        lines.add("- When memories (personal or team) seem relevant, or the user references prior work with them or others in their organization.");
        lines.add("- You MUST access memory when the user explicitly asks you to check, recall, or remember.");
        lines.add("- If the user says to *ignore* or *not use* memory: proceed as if MEMORY.md were empty. Do not apply remembered facts, cite, compare against, or mention memory content.");
        lines.add(MemoryPromptBuilder.MEMORY_DRIFT_CAVEAT);
        lines.add("");
        lines.addAll(MemoryPromptBuilder.TRUSTING_RECALL_SECTION);
        lines.add("");
        lines.add("## Memory and other forms of persistence");
        lines.add("Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.");
        lines.add("- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.");
        lines.add("- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.");
        lines.addAll(extras);
        lines.add("");
        if (searchingPast != null && !searchingPast.isBlank()) {
            lines.addAll(List.of(searchingPast.split("\n", -1)));
        }
        return String.join("\n", lines);
    }
}

package com.nexusai.application.agent.memory;

/**
 * ConsolidationPrompt · 对齐 CC services/autoDream/consolidationPrompt.ts.
 *
 * <p>L1 语义: Auto-dream 内存巩固 LLM prompt 模板构建器。包含 4 阶段:
 * <ol>
 *   <li>Orient — 列出 memory dir,读 entrypoint,skim 现有 topic</li>
 *   <li>Gather recent signal — daily logs/transcript 关键词 grep</li>
 *   <li>Consolidate — 写入/更新 topic 文件,合并去重,删除矛盾</li>
 *   <li>Prune and index — 更新 entrypoint (≤ MAX_ENTRYPOINT_LINES, ≤25KB) — 索引非 dump</li>
 * </ol>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #buildConsolidationPrompt(String, String, String)} (memoryRoot, transcriptDir, extra) → String prompt</li>
 *   <li><b>A2 Golden Trace</b>: 含 4 阶段 markdown + memoryRoot / transcriptDir / ENTRYPOINT_NAME / DIR_EXISTS_GUIDANCE / MAX_ENTRYPOINT_LINES;extra 非空附加 "## Additional context"</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output (除 String.format 内容)</li>
 *   <li><b>A4 边界</b>: extra 空字符串 → 不附加 Additional context;null → treat as empty</li>
 *   <li><b>A5 业务场景</b>: nightly dream 调用 → LLM 通过 4 阶段 prompt 巩固 memory dir</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal with embedded imports →
 * Java text block + 占位符替换;附 conditional section 通过 String.format;
 * DIR_EXISTS_GUIDANCE / ENTRYPOINT_NAME / MAX_ENTRYPOINT_LINES 是 CC 的
 * memdir 模块导出常量,在 Java 占位为最终值 (测试断言字符串包含)。
 */
public final class ConsolidationPrompt {

    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    private static final String MAX_ENTRYPOINT_LINES = "200";
    /**
     * CC memdir.ts:116-117 DIR_EXISTS_GUIDANCE —— 目录已存在，直接用 Write 工具写入
     * （不要 mkdir / 检查存在性）。IMP-M-P2-1 修正反 CC 文本（旧文本引导浪费 turn 探测）。
     */
    private static final String DIR_EXISTS_GUIDANCE =
        "This directory already exists — write to it directly with the Write tool (do not run mkdir or check for its existence).";

    private ConsolidationPrompt() {
        // 工具类
    }

    /**
     * Build the dream consolidation prompt.
     *
     * @param memoryRoot    absolute path to memory directory
     * @param transcriptDir absolute path to JSONL transcript directory
     * @param extra         optional additional context for the LLM (null/empty → skip Additional context section)
     * @return rendered prompt text
     */
    public static String buildConsolidationPrompt(String memoryRoot, String transcriptDir, String extra) {
        // [G-77] 尾字节对齐（CC consolidationPrompt.ts:64）：收尾反引号紧跟 `${extra}` 插值，
        //   模板**无尾换行**。text block 闭合定界符与末行内容同行（`say so.%s"""`）→ Java text
        //   block 不再附加闭合定界符前的尾随行终止符（EV-038 语言级实证 + 2026-08-14 javac 复证：
        //   独占一行形式 → "hello\n"，同行形式 → "hello!"）——extra 空/非空两态均与 CC 字节一致。
        String suffix = (extra == null || extra.isEmpty())
            ? ""
            : "\n\n## Additional context\n\n" + extra;
        return """
            # Dream: Memory Consolidation

            You are performing a dream — a reflective pass over your memory files. Synthesize what you've learned recently into durable, well-organized memories so that future sessions can orient quickly.

            Memory directory: `%s`
            %s

            Session transcripts: `%s` (large JSONL files — grep narrowly, don't read whole files)

            ---

            ## Phase 1 — Orient

            - `ls` the memory directory to see what already exists
            - Read `%s` to understand the current index
            - Skim existing topic files so you improve them rather than creating duplicates
            - If `logs/` or `sessions/` subdirectories exist (assistant-mode layout), review recent entries there

            ## Phase 2 — Gather recent signal

            Look for new information worth persisting. Sources in rough priority order:

            1. **Daily logs** (`logs/YYYY/MM/YYYY-MM-DD.md`) if present — these are the append-only stream
            2. **Existing memories that drifted** — facts that contradict something you see in the codebase now
            3. **Transcript search** — if you need specific context (e.g., "what was the error message from yesterday's build failure?"), grep the JSONL transcripts for narrow terms:
               `grep -rn "<narrow term>" %s/ --include="*.jsonl" | tail -50`

            Don't exhaustively read transcripts. Look only for things you already suspect matter.

            ## Phase 3 — Consolidate

            For each thing worth remembering, write or update a memory file at the top level of the memory directory. Use the memory file format and type conventions from your system prompt's auto-memory section — it's the source of truth for what to save, how to structure it, and what NOT to save.

            Focus on:
            - Merging new signal into existing topic files rather than creating near-duplicates
            - Converting relative dates ("yesterday", "last week") to absolute dates so they remain interpretable after time passes
            - Deleting contradicted facts — if today's investigation disproves an old memory, fix it at the source

            ## Phase 4 — Prune and index

            Update `%s` so it stays under %s lines AND under ~25KB. It's an **index**, not a dump — each entry should be one line under ~150 characters: `- [Title](file.md) — one-line hook`. Never write memory content directly into it.

            - Remove pointers to memories that are now stale, wrong, or superseded
            - Demote verbose entries: if an index line is over ~200 chars, it's carrying content that belongs in the topic file — shorten the line, move the detail
            - Add pointers to newly important memories
            - Resolve contradictions — if two files disagree, fix the wrong one

            ---

            Return a brief summary of what you consolidated, updated, or pruned. If nothing changed (memories are already tight), say so.%s"""
            .formatted(memoryRoot, DIR_EXISTS_GUIDANCE, transcriptDir,
                ENTRYPOINT_NAME, transcriptDir, ENTRYPOINT_NAME, MAX_ENTRYPOINT_LINES, suffix);
    }
}

package com.nexusai.application.agent.session;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session memory prompts service · 对齐 CC services/SessionMemory/prompts.ts.
 *
 * <p><b>P1-3 接线（DEL-M-12）</b>: 旧版 6 方法（loadSessionMemoryTemplate /
 * buildSessionMemoryUpdatePrompt / analyzeSectionSizes / generateSectionReminders /
 * truncateSessionMemoryForCompact / substituteVariables）0 调用方；本类按 CC prompts.ts 重新接线：
 * <ul>
 *   <li>{@link #loadSessionMemoryTemplate()} — 决策 D1/D3 双源读
 *       {@code session-memory/config/template.md}：先 {@code ~/.{appName}/} 自有根，再回落
 *       {@code ~/.claude/}（CC 只读兼容）；均无 → DEFAULT_SESSION_MEMORY_TEMPLATE（CC :86-104）</li>
 *   <li>{@link #loadSessionMemoryPrompt()} — 决策 D1/D3 双源读
 *       {@code session-memory/config/prompt.md}：先 {@code ~/.{appName}/} 自有根，再回落
 *       {@code ~/.claude/}；均无 → getDefaultUpdatePrompt（CC :111-129）</li>
 *   <li>{@link #buildSessionMemoryUpdatePrompt(currentNotes, notesPath)} — 变量替换 + section 提醒
 *       （CC :226-247）</li>
 *   <li>{@link #truncateSessionMemoryForCompact(content)} — per-section 截断（CC :256-296）</li>
 * </ul>
 *
 * <p>CC source: services/SessionMemory/prompts.ts (324 LOC).
 */
public final class SessionMemoryPrompts {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryPrompts.class);
    public static final int MAX_SECTION_LENGTH = 2000;
    public static final int MAX_TOTAL_SESSION_MEMORY_TOKENS = 12000;

    // [SM-13] 前导 \n（DRIFT-20）· CC prompts.ts:11 模板字面量以 `\n` 开头（反引号后直接换行）
    //   ——新建 memory 文件首行前必须有空行；isSessionMemoryEmpty 因 trim 不受影响。
    public static final String DEFAULT_SESSION_MEMORY_TEMPLATE =
        "\n# Session Title\n" +
        "_A short and distinctive 5-10 word descriptive title for the session. Super info dense, no filler_\n\n" +
        "# Current State\n_What is actively being worked on right now? Pending tasks not yet completed. Immediate next steps._\n\n" +
        "# Task specification\n_What did the user ask to build? Any design decisions or other explanatory context_\n\n" +
        "# Files and Functions\n_What are the important files? In short, what do they contain and why are they relevant?_\n\n" +
        "# Workflow\n_What bash commands are usually run and in what order? How to interpret their output if not obvious?_\n\n" +
        "# Errors & Corrections\n_Errors encountered and how they were fixed. What did the user correct? What approaches failed and should not be tried again?_\n\n" +
        "# Codebase and System Documentation\n_What are the important system components? How do they work/fit together?_\n\n" +
        "# Learnings\n_What has worked well? What has not? What to avoid? Do not duplicate items from other sections_\n\n" +
        "# Key results\n_If the user asked a specific output such as an answer to a question, a table, or other document, repeat the exact result here_\n\n" +
        "# Worklog\n_Step by step, what was attempted, done? Very terse summary for each step_\n";

    /**
     * 加载自定义 session memory 模板 · 对齐 CC {@code loadSessionMemoryTemplate}
     * （prompts.ts:86-104）。决策 D1/D3 双源读：先
     * {@code ~/.{appName}/session-memory/config/template.md}（NexusaiPaths 自有根），再回落
     * {@code ~/.claude/session-memory/config/template.md}（CC 只读兼容）；两处均无
     * → DEFAULT_SESSION_MEMORY_TEMPLATE。
     *
     * @return 模板内容
     */
    public String loadSessionMemoryTemplate() {
        Path templatePath = resolveSessionMemoryConfigFile("template.md");
        if (templatePath == null) {
            return DEFAULT_SESSION_MEMORY_TEMPLATE;
        }
        try {
            return Files.readString(templatePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[SessionMemoryPrompts] 读取模板失败，回落默认模板: {} - {}", templatePath, e.toString());
            return DEFAULT_SESSION_MEMORY_TEMPLATE;
        }
    }

    /**
     * 加载自定义 session memory prompt · 对齐 CC {@code loadSessionMemoryPrompt}
     * （prompts.ts:111-129）。决策 D1/D3 双源读：先
     * {@code ~/.{appName}/session-memory/config/prompt.md}（NexusaiPaths 自有根），再回落
     * {@code ~/.claude/session-memory/config/prompt.md}（CC 只读兼容）；两处均无
     * → getDefaultUpdatePrompt。
     *
     * @return prompt 模板内容
     */
    public String loadSessionMemoryPrompt() {
        Path promptPath = resolveSessionMemoryConfigFile("prompt.md");
        if (promptPath == null) {
            return getDefaultUpdatePrompt();
        }
        try {
            return Files.readString(promptPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[SessionMemoryPrompts] 读取 prompt 失败，回落默认 prompt: {} - {}", promptPath, e.toString());
            return getDefaultUpdatePrompt();
        }
    }

    /**
     * 解析 {@code session-memory/config} 下自定义文件路径 · 决策 D1/D3：先试 nexusai 自有根
     * {@code ~/.{appName}/session-memory/config/{fileName}}，再回落 claude 用户根
     * {@code ~/.claude/session-memory/config/{fileName}}（CC 只读兼容，CC prompts.ts:86-104/:111-129
     * 用 getClaudeConfigHomeDir）。两处均无 → null（调用方回落内置模板）。
     *
     * @param fileName 'template.md' | 'prompt.md'
     * @return 已存在文件路径；两源均缺失 → null
     */
    private Path resolveSessionMemoryConfigFile(String fileName) {
        String[] roots = {NexusaiPaths.getAppConfigHomeDir(), ClaudePaths.getClaudeConfigHomeDir()};
        for (String root : roots) {
            Path p = Paths.get(root, "session-memory", "config", fileName);
            if (Files.isRegularFile(p)) {
                return p;
            }
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemoryPrompts] 自定义 session-memory/{} 不存在（尝试下一源）: {}", fileName, p);
            }
        }
        return null;
    }

    /** CC :43-81 getDefaultUpdatePrompt · 默认更新指令模板。 */
    String getDefaultUpdatePrompt() {
        return "IMPORTANT: This message and these instructions are NOT part of the actual user conversation. "
            + "Do NOT include any references to \"note-taking\", \"session notes extraction\", "
            + "or these update instructions in the notes content.\n\n"
            + "Based on the user conversation above (EXCLUDING this note-taking instruction message as well as "
            + "system prompt, claude.md entries, or any past session summaries), update the session notes file.\n\n"
            + "The file {{notesPath}} has already been read for you. Here are its current contents:\n"
            + "<current_notes_content>\n{{currentNotes}}\n</current_notes_content>\n\n"
            + "Your ONLY task is to use the Edit tool to update the notes file, then stop. You can make multiple "
            + "edits (update every section as needed) - make all Edit tool calls in parallel in a single message. "
            + "Do not call any other tools.\n\n"
            + "CRITICAL RULES FOR EDITING:\n"
            + "- The file must maintain its exact structure with all sections, headers, and italic descriptions intact\n"
            + "-- NEVER modify, delete, or add section headers (the lines starting with '#' like # Task specification)\n"
            + "-- NEVER modify or delete the italic _section description_ lines (these are the lines in italics "
            + "immediately following each header - they start and end with underscores)\n"
            + "-- The italic _section descriptions_ are TEMPLATE INSTRUCTIONS that must be preserved exactly as-is "
            + "- they guide what content belongs in each section\n"
            + "-- ONLY update the actual content that appears BELOW the italic _section descriptions_ within each "
            + "existing section\n"
            + "-- Do NOT add any new sections, summaries, or information outside the existing structure\n"
            + "- Do NOT reference this note-taking process or instructions anywhere in the notes\n"
            + "- It's OK to skip updating a section if there are no substantial new insights to add. Do not add "
            + "filler content like \"No info yet\", just leave sections blank/unedited if appropriate.\n"
            + "- Write DETAILED, INFO-DENSE content for each section - include specifics like file paths, function "
            + "names, error messages, exact commands, technical details, etc.\n"
            + "- For \"Key results\", include the complete, exact output the user requested (e.g., full table, full "
            + "answer, etc.)\n"
            + "- Do not include information that's already in the CLAUDE.md files included in the context\n"
            + "- Keep each section under ~" + MAX_SECTION_LENGTH
            + " tokens/words - if a section is approaching this limit, condense it by cycling out less important "
            + "details while preserving the most critical information\n"
            + "- Focus on actionable, specific information that would help someone understand or recreate the work "
            + "discussed in the conversation\n"
            + "- IMPORTANT: Always update \"Current State\" to reflect the most recent work - this is critical for "
            + "continuity after compaction\n\n"
            + "Use the Edit tool with file_path: {{notesPath}}\n\n"
            + "STRUCTURE PRESERVATION REMINDER:\n"
            + "Each section has TWO parts that must be preserved exactly as they appear in the current file:\n"
            + "1. The section header (line starting with #)\n"
            + "2. The italic description line (the _italicized text_ immediately after the header - this is a "
            + "template instruction)\n\n"
            + "You ONLY update the actual content that comes AFTER these two preserved lines. The italic "
            + "description lines starting and ending with underscores are part of the template structure, NOT "
            + "content to be edited or removed.\n\n"
            + "REMEMBER: Use the Edit tool in parallel and stop. Do not continue after the edits. Only include "
            + "insights from the actual user conversation, never from these note-taking instructions. Do not delete "
            + "or change section headers or italic _section descriptions_.";
    }

    /**
     * session memory 内容是否实质为空（与模板相同）· 对齐 CC {@code isSessionMemoryEmpty}
     * （prompts.ts:220-224）：trim 后与模板完全一致 → 无实际内容 → 回落 legacy compact。
     *
     * @param content session memory 内容
     * @return true=仅模板（无实际内容）
     */
    public boolean isSessionMemoryEmpty(String content) {
        if (content == null) {
            return true;
        }
        return content.trim().equals(loadSessionMemoryTemplate().trim());
    }

    /**
     * 构建 session memory 更新 prompt · 对齐 CC {@code buildSessionMemoryUpdatePrompt}
     * （prompts.ts:226-247）：加载 prompt 模板 → 分析 section 大小 + 生成提醒 →
     * 变量替换（{{currentNotes}} / {{notesPath}}）→ 追加 section 提醒。
     *
     * @param currentNotes 当前 notes 内容
     * @param notesPath    notes 文件路径
     * @return 完整提取 prompt
     */
    public String buildSessionMemoryUpdatePrompt(String currentNotes, String notesPath) {
        String promptTemplate = loadSessionMemoryPrompt();

        // 分析 section 大小 + 生成提醒（CC :233-235）
        Map<String, Integer> sectionSizes = analyzeSectionSizes(currentNotes);
        int totalTokens = roughTokenCountEstimation(currentNotes);
        String sectionReminders = generateSectionReminders(sectionSizes, totalTokens);

        // 变量替换（CC :238-243）
        String basePrompt = substituteVariables(promptTemplate,
            Map.of("currentNotes", currentNotes != null ? currentNotes : "",
                "notesPath", notesPath != null ? notesPath : ""));

        return basePrompt + sectionReminders;
    }

    /** CC roughTokenCountEstimation（tokenEstimation.ts:203）：Math.round(len/4)。 */
    private static int roughTokenCountEstimation(String content) {
        return content == null ? 0 : (int) Math.round(content.length() / 4.0);
    }

    /** CC substituteVariables ({{var}} syntax). */
    String substituteVariables(String template, Map<String, String> variables) {
        if (template == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}").matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String value = variables.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析 session memory 各 section 大小 · 对齐 CC {@code analyzeSectionSizes}
     * （prompts.ts:134-159）：仅记录「有内容行」的 section，token 估算为
     * {@code roughTokenCountEstimation(sectionContent)}（trim 后 join 再 Math.round(len/4)）。
     *
     * @param content session memory 内容
     * @return section 头（含 "# "）→ token 数（插入序）
     */
    Map<String, Integer> analyzeSectionSizes(String content) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (content == null) {
            return result;
        }
        // split("\n", -1)：保留尾部空串（对齐 JS split('\n')，CC :138）——尾部换行后的空 section
        // 在 CC 中 currentContent=[...''] 长度>0 仍被记录（0 token），默认 split 会丢弃
        String[] lines = content.split("\n", -1);
        String currentSection = null;
        List<String> currentContent = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("# ")) {
                if (currentSection != null && !currentContent.isEmpty()) {
                    String sectionContent = String.join("\n", currentContent).trim();
                    result.put(currentSection, roughTokenCountEstimation(sectionContent));
                }
                currentSection = line;
                currentContent = new ArrayList<>();
            } else {
                currentContent.add(line);
            }
        }
        if (currentSection != null && !currentContent.isEmpty()) {
            String sectionContent = String.join("\n", currentContent).trim();
            result.put(currentSection, roughTokenCountEstimation(sectionContent));
        }
        return result;
    }

    /**
     * 生成 section 超限/超预算提醒 · 对齐 CC {@code generateSectionReminders}
     * （prompts.ts:164-196）：
     * <ul>
     *   <li>oversizedSections 按 token <b>降序</b>，每项 {@code - "section" is ~N tokens (limit: 2000)}</li>
     *   <li>overBudget（>12000）→ 完整 CRITICAL 指令（含 Prioritize keeping
     *       "Current State" and "Errors &amp; Corrections"）</li>
     *   <li>超限项列表前缀：overBudget 时 "Oversized sections to condense"，否则
     *       "IMPORTANT: ... MUST be condensed"</li>
     * </ul>
     *
     * @param sectionSizes section 头 → token 数
     * @param totalTokens  全文 token 估算
     * @return 提醒文本；无超限且未超预算 → ""
     */
    String generateSectionReminders(Map<String, Integer> sectionSizes, int totalTokens) {
        boolean overBudget = totalTokens > MAX_TOTAL_SESSION_MEMORY_TOKENS;
        List<String> oversizedSections = sectionSizes.entrySet().stream()
            .filter(e -> e.getValue() > MAX_SECTION_LENGTH)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> "- \"" + e.getKey() + "\" is ~" + e.getValue()
                + " tokens (limit: " + MAX_SECTION_LENGTH + ")")
            .collect(Collectors.toList());

        if (oversizedSections.isEmpty() && !overBudget) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        if (overBudget) {
            parts.add("\n\nCRITICAL: The session memory file is currently ~" + totalTokens
                + " tokens, which exceeds the maximum of " + MAX_TOTAL_SESSION_MEMORY_TOKENS
                + " tokens. You MUST condense the file to fit within this budget. Aggressively "
                + "shorten oversized sections by removing less important details, merging related "
                + "items, and summarizing older entries. Prioritize keeping \"Current State\" and "
                + "\"Errors & Corrections\" accurate and detailed.");
        }
        if (!oversizedSections.isEmpty()) {
            parts.add("\n\n" + (overBudget
                ? "Oversized sections to condense"
                : "IMPORTANT: The following sections exceed the per-section limit and MUST be condensed")
                + ":\n" + String.join("\n", oversizedSections));
        }
        return String.join("", parts);
    }

    /**
     * 截断 session memory 中超限 section · 对齐 CC {@code truncateSessionMemoryForCompact}
     * （prompts.ts:256-296）+ {@code flushSessionSection}（:298-324）。
     *
     * <p>超限 section <b>不字符截断</b>：在超限点前的<b>整行边界</b>保留（charCount 累加
     * 每行 {@code len+1}），末尾追加截断标记 {@code \n[... section truncated for length ...]}。
     * 空串行按 CC push 语义保留原位置：首 header 前恰单空行时产物保留前导 {@code \n}
     * （flush 返回 {@code ['']} → {@code outputLines.push(...)} 推入空串元素，:274/:303-305）。
     *
     * @param content session memory 内容
     * @return 截断结果（content + 是否发生截断）
     */
    public TruncationResult truncateSessionMemoryForCompact(String content) {
        if (content == null) {
            return new TruncationResult(List.of(), false);
        }
        // split("\n", -1)：保留尾部空串（对齐 JS split('\n')，CC :260）
        String[] lines = content.split("\n", -1);
        int maxCharsPerSection = MAX_SECTION_LENGTH * 4;
        List<String> outputChunks = new ArrayList<>();
        List<String> currentSectionLines = new ArrayList<>();
        String currentSectionHeader = "";
        boolean wasTruncated = false;

        for (String line : lines) {
            if (line.startsWith("# ")) {
                TruncationResult r =
                    flushSection(currentSectionHeader, currentSectionLines, maxCharsPerSection);
                // CC prompts.ts:274 outputLines.push(...result.lines)——空串元素也 push，
                // 前导单空行（flush 返回 ['']）因此保留原位置，join 产出前导 \n
                outputChunks.addAll(r.lines());
                wasTruncated = wasTruncated || r.wasTruncated();
                currentSectionHeader = line;
                currentSectionLines = new ArrayList<>();
            } else {
                currentSectionLines.add(line);
            }
        }
        TruncationResult r =
            flushSection(currentSectionHeader, currentSectionLines, maxCharsPerSection);
        outputChunks.addAll(r.lines());
        wasTruncated = wasTruncated || r.wasTruncated();

        // CC prompts.ts:293 outputLines.join('\n')——空串元素保留原位置（前导 "" 产出前导 \n）
        return new TruncationResult(outputChunks, wasTruncated);
    }

    /**
     * 冲刷单个 section · 对齐 CC {@code flushSessionSection}（prompts.ts:298-324）：
     * 无 header → 原样返回 sectionLines（不 join、不截断）；未超限 → [header, ...lines]；
     * 超限 → 整行边界截断 + 截断标记。
     *
     * @param sectionHeader     当前 section 头（含 "# "；空串 = 首个 header 之前的内容）
     * @param sectionLines      section 内容行
     * @param maxCharsPerSection 每 section 最大字符数（MAX_SECTION_LENGTH * 4）
     * @return 冲刷结果（行列表 + 是否截断；行列表语义对齐 CC {@code flushSessionSection}）
     */
    private TruncationResult flushSection(
            String sectionHeader, List<String> sectionLines, int maxCharsPerSection) {
        if (sectionHeader == null || sectionHeader.isEmpty()) {
            // CC :303-305 无 header → 原样返回 sectionLines（不 join、不截断，空串元素保留）
            return new TruncationResult(sectionLines, false);
        }
        String sectionContent = String.join("\n", sectionLines);
        if (sectionContent.length() <= maxCharsPerSection) {
            List<String> lines = new ArrayList<>(sectionLines.size() + 1);
            lines.add(sectionHeader);
            lines.addAll(sectionLines);
            return new TruncationResult(lines, false);
        }
        // 整行边界截断（prompts.ts:313-322）：charCount 累加每行 len+1（'\n' 计入）
        int charCount = 0;
        List<String> keptLines = new ArrayList<>();
        keptLines.add(sectionHeader);
        for (String line : sectionLines) {
            if (charCount + line.length() + 1 > maxCharsPerSection) {
                break;
            }
            keptLines.add(line);
            charCount += line.length() + 1;
        }
        keptLines.add("\n[... section truncated for length ...]");
        return new TruncationResult(keptLines, true);
    }

    /**
     * 冲刷结果：行列表 + 是否截断。{@link #content()} 返回 CC {@code outputLines.join('\n')}
     * （prompts.ts:293）语义的最终文本——调用方（SessionMemoryService:1357-1370）按
     * {@code content()}/{@code wasTruncated()} 消费，与旧契约一致。
     */
    public record TruncationResult(List<String> lines, boolean wasTruncated) {
        public String content() {
            return String.join("\n", lines);
        }
    }
}

package com.nexusai.application.agent.docs;

import com.nexusai.application.agent.skill.NexusaiPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MagicDocsPrompts · 对齐 CC services/MagicDocs/prompts.ts.
 *
 * <p>L1 语义: Magic Docs 更新 prompt 模板加载 + 变量替换 + 可选自定义段拼接。
 * <ul>
 *   <li>{@link #DEFAULT_UPDATE_PROMPT_TEMPLATE} — 内置 60+ 行 magic doc 更新规则 (BE TERSE/high-signal;preserve header;cleanup outdated;etc.)</li>
 *   <li>{@link #loadMagicDocsPrompt(String, FileLoader)} — 决策 D1/D3 双源读：先
 *       {@code ~/.{appName}/magic-docs/prompt.md}（NexusaiPaths 自有根），再回落
 *       {@code ~/.claude/magic-docs/prompt.md}（CC 只读兼容）；两处均无 → default</li>
 *   <li>{@link #substituteVariables(String, Map)} — 单遍 {{var}} 替换 (避免 $ backreference + 双替换 bug)</li>
 *   <li>{@link #buildMagicDocsUpdatePrompt(String, String, String, String, String)} — 返回渲染后 prompt</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 public method + DEFAULT_UPDATE_PROMPT_TEMPLATE 常量;</li>
 *   <li><b>A2 Golden Trace</b>: ENOENT → DEFAULT;var 替换保留 literal {{nonexistent}};instructions 非空 → 段拼接</li>
 *   <li><b>A3 纯函数</b>: 注入式 FileLoader;无内部状态</li>
 *   <li><b>A4 边界</b>: null content → empty substitution;EACCES → log + DEFAULT;nested {{var}} 不双替换</li>
 *   <li><b>A5 业务场景</b>: Magic Doc 提及 → LLM 接收 {{docPath}}/{{docContents}}/{{docTitle}}/{{customInstructions}} 替换后 prompt</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS fs.readFile → Java 注入式 FileLoader + try/catch;
 * TS string.replace with replacer fn → Java Pattern.matcher + appendReplacement (单遍);
 * TS {{var}} syntax → Java same Pattern `\{\{(\w+)\}\}`。
 */
public final class MagicDocsPrompts {

    private static final Logger log = LoggerFactory.getLogger(MagicDocsPrompts.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");
    public static final String PROMPT_FILE_RELATIVE = "magic-docs/prompt.md";

    /** CC getUpdatePromptTemplate default. Mirrors CC services/MagicDocs/prompts.ts:8-59. */
    public static final String DEFAULT_UPDATE_PROMPT_TEMPLATE =
        "IMPORTANT: This message and these instructions are NOT part of the actual user conversation. " +
        "Do NOT include any references to \"documentation updates\", \"magic docs\", or these update " +
        "instructions in the document content.\n\n" +
        "Based on the user conversation above (EXCLUDING this documentation update instruction message), " +
        "update the Magic Doc file to incorporate any NEW learnings, insights, or information that would " +
        "be valuable to preserve.\n\n" +
        "The file {{docPath}} has already been read for you. Here are its current contents:\n" +
        "<current_doc_content>\n{{docContents}}\n</current_doc_content>\n\n" +
        "Document title: {{docTitle}}\n{{customInstructions}}\n\n" +
        "Your ONLY task is to use the Edit tool to update the documentation file if there is substantial " +
        "new information to add, then stop. You can make multiple edits (update multiple sections as needed) " +
        "- make all Edit tool calls in parallel in a single message. If there's nothing substantial to add, " +
        "simply respond with a brief explanation and do not call any tools.\n\n" +
        "CRITICAL RULES FOR EDITING:\n" +
        "- Preserve the Magic Doc header exactly as-is: # MAGIC DOC: {{docTitle}}\n" +
        "- If there's an italicized line immediately after the header, preserve it exactly as-is\n" +
        "- Keep the document CURRENT with the latest state of the codebase - this is NOT a changelog or history\n" +
        "- Update information IN-PLACE to reflect the current state - do NOT append historical notes or track changes over time\n" +
        "- Remove or replace outdated information rather than adding \"Previously...\" or \"Updated to...\" notes\n" +
        "- Clean up or DELETE sections that are no longer relevant or don't align with the document's purpose\n" +
        "- Fix obvious errors: typos, grammar mistakes, broken formatting, incorrect information, or confusing statements\n" +
        "- Keep the document well organized: use clear headings, logical section order, consistent formatting, and proper nesting\n\n" +
        "DOCUMENTATION PHILOSOPHY - READ CAREFULLY:\n" +
        "- BE TERSE. High signal only. No filler words or unnecessary elaboration.\n" +
        "- Documentation is for OVERVIEWS, ARCHITECTURE, and ENTRY POINTS - not detailed code walkthroughs\n" +
        "- Do NOT duplicate information that's already obvious from reading the source code\n" +
        "- Do NOT document every function, parameter, or line number reference\n" +
        "- Focus on: WHY things exist, HOW components connect, WHERE to start reading, WHAT patterns are used\n" +
        "- Skip: detailed implementation steps, exhaustive API docs, play-by-play narratives\n\n" +
        "What TO document:\n" +
        "- High-level architecture and system design\n" +
        "- Non-obvious patterns, conventions, or gotchas\n" +
        "- Key entry points and where to start reading code\n" +
        "- Important design decisions and their rationale\n" +
        "- Critical dependencies or integration points\n" +
        "- References to related files, docs, or code (like a wiki) - help readers navigate to relevant context\n\n" +
        "What NOT to document:\n" +
        "- Anything obvious from reading the code itself\n" +
        "- Exhaustive lists of files, functions, or parameters\n" +
        "- Step-by-step implementation details\n" +
        "- Low-level code mechanics\n" +
        "- Information already in CLAUDE.md or other project docs\n\n" +
        "Use the Edit tool with file_path: {{docPath}}\n\n" +
        "REMEMBER: Only update if there is substantial new information. The Magic Doc header " +
        "(# MAGIC DOC: {{docTitle}}) must remain unchanged.";

    public interface FileLoader {
        /** @return file content as UTF-8 string; throw IOException on missing/error. */
        String readUtf8(String path) throws IOException;
    }

    public static String defaultFileLoader(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new RuntimeException("ENOENT: " + path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String loadMagicDocsPrompt(String claudeConfigHomeDir, FileLoader loader) {
        // 决策 D1/D3：先试 nexusai 自有根 ~/.{appName}/magic-docs/prompt.md（NexusaiPaths），
        // 再回落 ~/.claude/magic-docs/prompt.md（CC 只读兼容，claudeConfigHomeDir 参数）；两处均
        // 缺失/异常才回落 DEFAULT。CC 无预设文件（仅自定义覆盖，从不写）→ 读取链纯只读。
        // CC original: prompts.ts:66-76 loadMagicDocsPrompt ENOENT → 内置模板。
        for (String home : new String[] {NexusaiPaths.getAppConfigHomeDir(), claudeConfigHomeDir}) {
            String promptPath = home + "/" + PROMPT_FILE_RELATIVE;
            try {
                return loader.readUtf8(promptPath);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("MagicDocsPrompt 加载失败（尝试下一源）: {} - {}", promptPath, e.toString());
                }
            }
        }
        // CC: silently fall back to default on any error
        return DEFAULT_UPDATE_PROMPT_TEMPLATE;
    }

    public static String substituteVariables(String template, Map<String, String> variables) {
        if (template == null) return null;
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = variables != null && variables.containsKey(key) ? variables.get(key) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String buildMagicDocsUpdatePrompt(
        String claudeConfigHomeDir, FileLoader loader,
        String docContents, String docPath, String docTitle, String instructions) {
        String promptTemplate = loadMagicDocsPrompt(claudeConfigHomeDir, loader);
        String customInstructions = (instructions == null || instructions.isEmpty())
            ? ""
            : "\n\nDOCUMENT-SPECIFIC UPDATE INSTRUCTIONS:\nThe document author has provided specific instructions " +
              "for how this file should be updated. Pay extra attention to these instructions and follow them carefully:\n\n\""
              + instructions + "\"\n\nThese instructions take priority over the general rules below. Make sure " +
              "your updates align with these specific guidelines.";
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("docContents", docContents == null ? "" : docContents);
        variables.put("docPath", docPath == null ? "" : docPath);
        variables.put("docTitle", docTitle == null ? "" : docTitle);
        variables.put("customInstructions", customInstructions);
        return substituteVariables(promptTemplate, variables);
    }
}

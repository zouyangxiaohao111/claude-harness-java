package com.nexusai.application.agent.docs;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Magic Doc 检测器 · 对齐 CC services/MagicDocs/magicDocs.ts:31-81.
 *
 * <p>L1 语义: 自动维护的 markdown 文档. 文件首行含 {@code # MAGIC DOC: [title]} 时
 * 被识别为 magic doc; 紧随其后的斜体行 (e.g. {@code _keep this up to date_}) 作为
 * 更新指令 (instructions). 后台周期 fork subagent 用对话内容更新文档.
 *
 * <p>L2 契约:
 * <ul>
 *   <li>{@link #detect} 返回 title (必有) + 可选 instructions</li>
 *   <li>非 magic doc → empty</li>
 *   <li>header 必须在文件第一行 (CC {@code ^# MAGIC DOC:...$ /im} flag m, multiline)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): {@code Pattern.CASE_INSENSITIVE | MULTILINE} 取代 CC 的 /im flag.
 */
@Component
public class MagicDocDetector {

    /** header 模式: {@code ^# MAGIC DOC: <title>$}. */
    private static final Pattern MAGIC_DOC_HEADER = Pattern.compile(
        "^#\\s*MAGIC\\s+DOC:\\s*(.+)$",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    /** 紧随 header 的斜体行 (instructions). */
    private static final Pattern ITALICS = Pattern.compile(
        "^[_*](.+?)[_*]\\s*$",
        Pattern.MULTILINE
    );

    /** 检测结果. */
    public record Detection(String title, String instructions) {
        public boolean hasInstructions() {
            return instructions != null && !instructions.isBlank();
        }
    }

    /**
     * 检测文件内容是否为 magic doc. 对齐 CC magicDocs.ts:52-81 detectMagicDocHeader.
     */
    public Optional<Detection> detect(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        Matcher headerMatch = MAGIC_DOC_HEADER.matcher(content);
        if (!headerMatch.find()) {
            return Optional.empty();
        }
        String title = headerMatch.group(1).trim();

        // 在 header 后查找斜体行 (允许一个空行间隔)
        int headerEnd = headerMatch.end();
        String afterHeader = content.substring(headerEnd);
        Matcher nextLineMatch = Pattern.compile(
            "^\\s*\\n(?:\\s*\\n)?(.+?)(?:\\n|$)",
            Pattern.MULTILINE
        ).matcher(afterHeader);

        if (nextLineMatch.find()) {
            String nextLine = nextLineMatch.group(1);
            Matcher italicsMatch = ITALICS.matcher(nextLine);
            if (italicsMatch.find()) {
                return Optional.of(new Detection(title, italicsMatch.group(1).trim()));
            }
        }
        return Optional.of(new Detection(title, null));
    }
}
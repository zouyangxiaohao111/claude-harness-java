package com.nexusai.application.agent.team;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DirectMemberMessageParser · 对齐 CC utils/directMemberMessage.ts:6-20 parseDirectMemberMessage.
 *
 * <p>L1 语义: 解析 {@code @agent-name message-text} 语法用于直接 team member 消息,
 * 返回 {@code (recipientName, message)} 或 null (格式不匹配)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #parse(String)} (input) → ParsedDirectMessage record | null;constant regex</li>
 *   <li><b>A2 Golden Trace</b>: "@researcher fix tests" → recipientName=researcher, message="fix tests";不匹配 / 缺 message → null</li>
 *   <li><b>A3 纯函数</b>: Pattern.compile 一次,Matcher 复用</li>
 *   <li><b>A4 边界</b>: null input → null;空白 message → null (trim 后空)</li>
 *   <li><b>A5 业务场景</b>: "@coder-agent continue with task X" → CC UI 解析后 bypass LLM 直接 deliver 到 coder-agent</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS regex literal {@code /^@([\w-]+)\s+(.+)$/s} →
 * Java Pattern {@code ^@([\\w-]+)\\s+(.+)$} + DOTALL flag;
 * TS string.match destructuring → Java Matcher.group。
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
public final class DirectMemberMessageParser {

    private static final Pattern AT_MEMBER = Pattern.compile(
        "^@([\\w-]+)\\s+(.+)$", Pattern.DOTALL);

    public record ParsedDirectMessage(String recipientName, String message) {}

    private DirectMemberMessageParser() {}

    /**
     * Parse {@code @agent message}.
     *
     * @param input raw user input
     * @return ParsedDirectMessage(recipientName, message) or null if format invalid
     */
    public static ParsedDirectMessage parse(String input) {
        if (input == null) return null;
        Matcher m = AT_MEMBER.matcher(input);
        if (!m.matches()) return null;
        String recipient = m.group(1);
        String message = m.group(2);
        if (recipient == null || recipient.isEmpty()) return null;
        if (message == null) return null;
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return null;
        return new ParsedDirectMessage(recipient, trimmed);
    }
}

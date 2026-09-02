package com.nexusai.application.agent.permission.classifier;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session M3.3 + [S12 R2] · PromptLengths.userPromptsLength 分桶测试 · 对齐 CC
 * Open-ClaudeCode/src/utils/permissions/yoloClassifier.ts:1039-1066.
 *
 * <p><b>WHY (意图验证)</b>: CC 真源
 * <pre>
 *   switch (entry.role) {
 *     case 'user': userPromptsLength += serialized.length; break;
 *     case 'assistant': toolCallsLength += serialized.length; break;
 *   }
 * </pre>
 *
 * <p>[S12 R2] 分桶输入改为<b>转录序列化文本</b>（{@link YoloPromptBuilder#buildTranscriptEntries}）：
 * user → "User: {text}\n"；assistant 纯文本消息（无 toolCalls）被排除（防注入）。
 * 本测试走真实 builder + 与 classifySync 相同的分桶派生逻辑（避免镜像流式过滤漂移）。
 */
class PromptLengthsUserPromptsTest {

    private final YoloPromptBuilder builder = new YoloPromptBuilder();

    // ─────────── 1. 按 role==USER 累加（序列化长度） ───────────

    @Test
    @DisplayName("M3.3-1/S12: userPromptsLength 按转录序列化长度累加 · 对齐 CC yoloClassifier.ts:1045-1047 case 'user'")
    void userPromptsLength_aggregatesByRoleUser() {
        List<ChatMessageDto> transcript = List.of(
            // CC line 418-422: toCompactBlock + role === 'user' → "User: {text}\n"
            userMessage("hello world"),                 // "User: hello world\n" = 18 chars
            userMessage(""),                            // "User: \n" = 7 chars
            userMessage("another prompt")               // "User: another prompt\n" = 21 chars
        );

        long userPromptsLength = userBucket(builder.buildTranscriptEntries(transcript, Map.of()));

        assertThat(userPromptsLength)
            .as("userPromptsLength 必须累加全部 user 条目的序列化长度 (CC yoloClassifier.ts:1047)")
            .isEqualTo(18L + 7L + 21L);

        // PromptLengths 必须接受派生值(无负数, 边界保护)
        PromptLengths lengths = new PromptLengths(0L, 0L, userPromptsLength);
        assertThat(lengths.userPromptsLength())
            .as("PromptLengths.userPromptsLength must equal derived 46L")
            .isEqualTo(46L);
    }

    // ─────────── 2. 排除 system + assistant 文本 ───────────

    @Test
    @DisplayName("M3.3-2/S12: userPromptsLength 排除 system + assistant（assistant 文本防注入，CC :341-357）")
    void userPromptsLength_excludesSystemAndAssistant() {
        List<ChatMessageDto> transcript = List.of(
            systemMessage("system-only-content-XXX"),   // 23 chars (NOT counted)
            userMessage("user-only-YYY"),               // "User: user-only-YYY\n" = 20 chars (counted)
            assistantMessage("assistant-only-ZZZ")      // 无 toolCalls → 转录排除 (NOT counted)
        );

        List<YoloPromptBuilder.CompactMessage> entries = builder.buildTranscriptEntries(transcript, Map.of());
        long userPromptsLength = userBucket(entries);

        // 仅 20 chars (user)；assistant 文本条目必须不存在于转录
        assertThat(userPromptsLength)
            .as("userPromptsLength must EXCLUDE system (23 chars) and assistant text")
            .isEqualTo(20L);
        assertThat(entries)
            .as("assistant 纯文本消息（无 toolCalls）必须被转录排除（防注入，CC :341-357）")
            .noneMatch(e -> "assistant".equals(e.role()));
    }

    // ─────────── helpers ───────────

    /** 与 classifySync 相同的 user 桶派生逻辑（Σ role=user 序列化长度）。 */
    private static long userBucket(List<YoloPromptBuilder.CompactMessage> entries) {
        return entries.stream()
            .filter(e -> "user".equals(e.role()))
            .mapToLong(e -> e.content() != null ? e.content().length() : 0L)
            .sum();
    }

    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(null, null, Role.user, null, content, null, null, null, null,
            null, null, null, null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto systemMessage(String content) {
        return new ChatMessageDto(null, null, Role.system, null, content, null, null, null, null,
            null, null, null, null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto assistantMessage(String content) {
        return new ChatMessageDto(null, null, Role.assistant, null, content, null, null, null, null,
            null, null, null, null, null, null, List.of(), List.of());
    }
}

package com.nexusai.application.agent.permission.classifier;

/**
 * Prompt 长度度量 · 对齐 CC yoloClassifier.ts:719-723 + :1062-1066
 * (CC 原名 {@code promptLengths}, 类型 { systemPrompt: number, toolCalls: number, userPrompts: number }).
 *
 * <p>CC 真源字段对照:
 * <ul>
 *   <li>{@code systemPromptLength} ← CC {@code systemPrompt: systemPrompt.length}
 *       (yoloClassifier.ts:1063)</li>
 *   <li>{@code toolCallsLength} ← CC {@code toolCalls: toolCallsLength}
 *       (yoloClassifier.ts:1064, 变量定义于 line ~750-758 toolCallsLength 计算)</li>
 *   <li>{@code userPromptsLength} ← CC {@code userPrompts: promptLengths.userPrompts}
 *       (yoloClassifier.ts:1065 + :1039/1047 按 entry.role 分别累加 transcript
 *       拼接字符数,区别于 tool calls — 是 "transcript 用户消息撑爆 prompt"
 *       vs "tool schema 撑爆 prompt" 的判别来源)</li>
 * </ul>
 *
 * <p>WHY 独立 record 而非 nested class: 与 CC 真源形态 1:1 对齐 (Object literal
 * 类型独立于宿主 record); Pattern #4 'sealed stuffing 防控' — 新字段
 * 仅需扩本 record, 不污染 YoloClassifierResult.
 *
 * @param systemPromptLength 系统 prompt 字符串长度 (CC: systemPrompt.length)
 * @param toolCallsLength    tool calls 内容长度 (CC: toolCallsLength)
 * @param userPromptsLength  transcript 拼接字符数 (CC: promptLengths.userPrompts)
 *
 * @see YoloClassifierResult#promptLengths()
 */
public record PromptLengths(
        long systemPromptLength,
        long toolCallsLength,
        long userPromptsLength
) {
    /**
     * 紧凑构造器: 不变量保护 · 三个 length 都不可负.
     *
     * <p>WHY 允许 0: 理论上 prompt 完全为空时 (异常分支) 也应能构造,
     * 不允许负数是因为 CC 真源三者都是 string.length() 调用结果.
     */
    public PromptLengths {
        if (systemPromptLength < 0) {
            throw new IllegalArgumentException(
                "systemPromptLength is negative: " + systemPromptLength);
        }
        if (toolCallsLength < 0) {
            throw new IllegalArgumentException(
                "toolCallsLength is negative: " + toolCallsLength);
        }
        if (userPromptsLength < 0) {
            throw new IllegalArgumentException(
                "userPromptsLength is negative: " + userPromptsLength);
        }
    }
}

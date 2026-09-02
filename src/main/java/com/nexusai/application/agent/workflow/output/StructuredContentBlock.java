package com.nexusai.application.agent.workflow.output;

/**
 * 单个内容块 · CC original: {@code {type: string; text?: string}} (structuredOutput.ts:83)。
 *
 * <p>{@link StructuredOutputExtractor} 的输入契约：adapter（W-2a ClaudeCodeBackendAdapter）把
 * agent 终态消息 content 数组映射为本类型（text 块取 {@code text}，其余块 {@code type} 非 "text"
 * 被提取器跳过）。CC 侧直接操作 {@code finalized.content}（claudeCodeBackend.ts:372），此处以
 * 最小 record 解耦，避免提取器依赖尚未落地的消息 content 模型。</p>
 *
 * @param type CC original: {@code block.type} — 块类型，"text" 才参与提取
 * @param text CC original: {@code block.text?} — text 块的文本内容
 */
public record StructuredContentBlock(String type, String text) {

    /** 便捷工厂：构造 text 块 · CC original: {@code {type: 'text', text}} */
    public static StructuredContentBlock text(String text) {
        return new StructuredContentBlock("text", text);
    }
}

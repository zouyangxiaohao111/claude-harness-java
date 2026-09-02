package com.nexusai.application.agent.prompt;

/**
 * 单个系统提示块 · 对齐 CC {@code SystemPromptBlock}
 * （CC original: {@code SystemPromptBlock = { text: string, cacheScope: CacheScope | null }} (utils/api.ts:81-84)）。
 *
 * <p>发送边界（IMP-SP-06）将 SystemPrompt 数组按 boundary 拆分为若干 block，
 * 每个 block 携带独立缓存作用域。
 *
 * @param text       提示文本 · CC original: text (utils/api.ts:82)
 * @param cacheScope 缓存作用域 · CC original: cacheScope: CacheScope|null (utils/api.ts:83)；
 *                   {@link CacheScope#NULL} 表示该 block 不参与缓存
 */
public record SystemPromptBlock(
    String text,
    CacheScope cacheScope
) {
}

package com.nexusai.application.agent.prompt;

import java.util.concurrent.CompletableFuture;

/**
 * 单个系统提示 section · 对齐 CC {@code SystemPromptSection}
 * （CC original: {@code SystemPromptSection = { name: string, compute: ComputeFn, cacheBreak: boolean }}
 * (constants/systemPromptSections.ts:10-14)）。
 *
 * <p>compute 是延迟求值回调（async），由 resolve 阶段并行调用；
 * cacheBreak 是布尔而非三态枚举（DEL-SP-20 将旧缓存作用域三态收敛于此）。
 *
 * @param name       唯一标识 · CC original: name (constants/systemPromptSections.ts:11)
 * @param compute    延迟求值回调（async）· CC original: compute (constants/systemPromptSections.ts:12)
 * @param cacheBreak 该 section 变化时是否破坏 prompt 缓存 · CC original: cacheBreak (constants/systemPromptSections.ts:13)
 */
public record SystemPromptSection(
    String name,
    ComputeFn compute,
    boolean cacheBreak
) {

    /**
     * 延迟求值回调 · 对齐 CC {@code ComputeFn}
     * （CC original: {@code () => string | null | Promise<string | null>} (constants/systemPromptSections.ts:8)）。
     *
     * <p>CC 为 async 函数，Java 以 {@link CompletableFuture} 忠实 async 语义
     * （为 IMP-SP-02 CompletableFuture.allOf 并行 resolve 预留契约）。
     * null 结果用 {@link CompletableFuture#completedFuture(Object)} 传入 null 表示。
     */
    @FunctionalInterface
    public interface ComputeFn {
        CompletableFuture<String> compute();
    }
}

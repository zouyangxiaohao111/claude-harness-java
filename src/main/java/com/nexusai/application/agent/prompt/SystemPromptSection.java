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
 * <p><b>{@code cacheBreak=true} 的两条 Java 侧守卫</b>（CC 靠调用点自觉 + TS 必填 reason，
 * 见 {@code SystemPromptSections.dangerousUncachedSystemPromptSection}）：
 * <ol>
 *   <li>语义是<b>只写不读</b>（每轮重算并覆盖缓存），⛔ 不是「不缓存」——见
 *       {@link SystemPromptSectionRegistry#resolveAll}（短路条件 {@code !cacheBreak && cache.has(name)}，
 *       CC systemPromptSections.ts:50-54）；</li>
 *   <li>段名必须已在 {@link SystemPromptCacheBreakWhitelist} 登记，否则<b>构造期 fail loud</b>
 *       （防「顺手加易失段」打掉其后整段前缀缓存）。⚠️ 该白名单<b>当前为空表</b>
 *       （对齐 CC 2.1.278「{@code cacheBreak:true} 计数 0」）⇒ Java 侧当前<b>没有任何</b>
 *       {@code cacheBreak=true} 段，本守卫对一切段名 fail loud。</li>
 * </ol>
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
     * 紧凑构造器 · 唯一不变量：{@code cacheBreak=true} ⇒ 段名必须在重算白名单内。
     *
     * <p>放在 record 层（而非只在工厂层）是为了让「直接 new」的调用方也受守卫约束
     * （record 构造器是 public，工厂不是唯一入口）。
     *
     * @throws IllegalArgumentException {@code cacheBreak=true} 但段名未登记
     */
    public SystemPromptSection {
        if (cacheBreak && !SystemPromptCacheBreakWhitelist.isAllowed(name)) {
            throw new IllegalArgumentException(
                "cacheBreak=true 的段名不在重算白名单内（对齐 CC 2.1.278「全份提示 cacheBreak=true 计数 0」的不变量）: "
                    + "name=" + name + ", 已登记=" + SystemPromptCacheBreakWhitelist.allowedNames()
                    + "；新增易失段必须先登记理由（SystemPromptCacheBreakWhitelist）");
        }
    }

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

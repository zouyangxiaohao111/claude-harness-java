package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptSectionRegistry resolve 三态 + 并行 + 跨会话隔离测试。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: CC resolveSystemPromptSections 的语义是
 * per-section 短路（systemPromptSections.ts:50-55）+ 并行 + null 缓存，三态
 * 直接决定缓存命中率与 token 成本：
 * <ul>
 *   <li>I-1 命中不重算：cacheBreak=false 且 name 已入 Map → 短路返回缓存值，compute 不调
 *       （命中先于 compute，短路唯一前提 systemPromptSections.ts:50）；</li>
 *   <li>I-2 cacheBreak=true 恒重算：DANGEROUS_uncachedSystemPromptSection 每轮都调 compute
 *       （systemPromptSections.ts:24/:37），值变化才打破 prompt 缓存；</li>
 *   <li>并行 + 输入序：CompletableFuture.allOf 并行执行，结果数组按注册序（等价 CC
 *       Promise.all systemPromptSections.ts:43-57）——多条阻塞 compute 总耗时≈单条；</li>
 *   <li>跨会话隔离：cache 参数注入，两个 AgentState 的缓存互不串扰。</li>
 * </ul>
 */
class RegistryResolveTest {

    @Test
    @DisplayName("I-1：同 name 二次 resolve compute 计数=1（命中先于 compute，systemPromptSections.ts:50-51）")
    void resolve_hitPrecedesCompute() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        int[] computeCount = {0};
        SystemPromptSection section = SystemPromptSections.systemPromptSection("identity", () -> {
            computeCount[0]++;
            return CompletableFuture.completedFuture("identity-content");
        });
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        registry.register(section);

        List<String> first = registry.resolveAll(cache);
        List<String> second = registry.resolveAll(cache);

        assertThat(first).containsExactly("identity-content");
        assertThat(second).containsExactly("identity-content");
        assertThat(computeCount[0])
            .as("首次 miss compute 1 次；二次命中短路 compute 不再调 → 总计数 1")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("I-2：cacheBreak=true 两次 resolve 均调 compute（DANGEROUS_uncached，systemPromptSections.ts:24/:37）")
    void resolve_cacheBreakTrue_recomputesEveryTurn() {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        int[] computeCount = {0};
        SystemPromptSection volatileSection =
            SystemPromptSections.dangerousUncachedSystemPromptSection("mcp-state", () -> {
                computeCount[0]++;
                return CompletableFuture.completedFuture("mcp-value");
            }, "MCP 状态每轮变化需破缓存");
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        registry.register(volatileSection);

        List<String> first = registry.resolveAll(cache);
        List<String> second = registry.resolveAll(cache);

        assertThat(first).containsExactly("mcp-value");
        assertThat(second).containsExactly("mcp-value");
        assertThat(computeCount[0])
            .as("cacheBreak=true 短路条件恒不成立 → 两轮都调 compute，计数 2")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("并行 + 顺序保持：多 section 阻塞 compute 全部并发启动，结果按注册序（等价 Promise.all）")
    void resolve_parallelAndOrderPreserved() throws InterruptedException {
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        SystemPromptSection a = section("identity", "identity-value", allStarted, release);
        SystemPromptSection b = section("tools", "tools-value", allStarted, release);
        SystemPromptSection c = section("memory", "memory-value", allStarted, release);
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        registry.register(a);
        registry.register(b);
        registry.register(c);

        // resolveAll 在后台线程运行（3 条 compute 各自 await release）；主线程先断言
        // 3 条是否全部启动 —— 若串行实现，第一条 compute 阻塞时 allStarted 只到 1，断言超时失败
        CompletableFuture<List<String>> resolveFuture =
            CompletableFuture.supplyAsync(() -> registry.resolveAll(cache));

        assertThat(allStarted.await(2, TimeUnit.SECONDS))
            .as("3 条阻塞 compute 在 release 前全部启动 → 并行执行（非串行）")
            .isTrue();
        release.countDown();

        List<String> results = resolveFuture.join();

        assertThat(results)
            .as("结果数组按注册序（Promise.all 输入序）")
            .containsExactly("identity-value", "tools-value", "memory-value");
    }

    @Test
    @DisplayName("跨会话隔离：两个 cache 实例 resolve 互不串扰（会话级 STATE 非全局单例）")
    void resolve_crossSessionIsolation() {
        SystemPromptSectionCache sessionA = new SystemPromptSectionCache();
        SystemPromptSectionCache sessionB = new SystemPromptSectionCache();
        int[] computeCount = {0};
        SystemPromptSection section = SystemPromptSections.systemPromptSection("identity", () -> {
            computeCount[0]++;
            return CompletableFuture.completedFuture("identity-content");
        });
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        registry.register(section);

        List<String> resultA1 = registry.resolveAll(sessionA);
        List<String> resultB = registry.resolveAll(sessionB);
        List<String> resultA2 = registry.resolveAll(sessionA);

        assertThat(resultA1).containsExactly("identity-content");
        assertThat(resultB).containsExactly("identity-content");
        assertThat(resultA2).containsExactly("identity-content");
        assertThat(computeCount[0])
            .as("会话 A 命中不重算(计数1) + 会话 B miss 重算(计数2)，互不串扰")
            .isEqualTo(2);
    }

    private static SystemPromptSection section(
        String name, String value, CountDownLatch allStarted, CountDownLatch release
    ) {
        return SystemPromptSections.systemPromptSection(name, () -> {
            allStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("compute 被中断", e);
            }
            return CompletableFuture.completedFuture(value);
        });
    }
}

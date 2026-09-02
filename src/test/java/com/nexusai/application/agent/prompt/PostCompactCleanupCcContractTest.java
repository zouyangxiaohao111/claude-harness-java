package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.PostCompactCleanup;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.loop.ContextCollapse;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP2-01 · PostCompactCleanup 主线程门契约测试 · 对齐 CC postCompactCleanup.ts:31-77。
 *
 * <p><b>WHY (S-12 / EV2-040)</b>: 生产 LlmAgentLoop 压缩路径传 {@code querySource().name()}
 * 大写枚举名（REPL_MAIN_THREAD / SDK），而 {@code isMainThreadCompact} 只认小写字面量
 * {@code repl_main_thread} / {@code sdk} → 生产主线程/SDK 门恒 false，3 项 main-thread
 * 操作（resetContextCollapse + getUserContext.cache.clear + resetGetMemoryFilesCache）恒跳过。
 * 本测试钉死「生产值域（大写）→ 3 项 main-thread 操作执行」，修复后转 GREEN。
 *
 * <p>3 项 main-thread 操作（CC postCompactCleanup.ts:42-61）：
 * <ol>
 *   <li>{@code feature('CONTEXT_COLLAPSE') && isMainThreadCompact → resetContextCollapse()}（:42-49）</li>
 *   <li>{@code isMainThreadCompact → getUserContext.cache.clear?.()}（:59，Java:
 *       {@link SystemPromptInjection#clearAllProviderCaches()}）</li>
 *   <li>{@code isMainThreadCompact → resetGetMemoryFilesCache('compact')}（:60，Java:
 *       {@link ClaudemdEngine#resetGetMemoryFilesCache}）</li>
 * </ol>
 *
 * <p>位于 prompt 包：{@link SystemPromptInjection#registerCacheClearHook} 为 package-private
 * （同包可注册观察钩子，与 {@code CacheInvalidationTest} 同机制）。
 */
class PostCompactCleanupCcContractTest {

    private static final AtomicInteger COLLAPSE_RESETS = new AtomicInteger();
    private static final AtomicInteger MEMFILES_RESETS = new AtomicInteger();

    /** 本测试注册的 cache-clear 观察钩子（@AfterEach 逐个注销，防静态表累积）。 */
    private static final List<Runnable> REGISTERED_HOOKS = new CopyOnWriteArrayList<>();

    /** CONTEXT_COLLAPSE 开启 + resetContextCollapse 计数 spy（经 STATIC_COLLAPSE 注入）。 */
    private static final ContextCollapse ENABLED_COLLAPSE = new ContextCollapse(FeatureFlags.ALL_DISABLED) {
        @Override
        public boolean isContextCollapseEnabled() {
            return true;
        }

        @Override
        public void resetContextCollapse() {
            COLLAPSE_RESETS.incrementAndGet();
        }
    };

    /** resetGetMemoryFilesCache 计数 spy（经 STATIC_CLAUDE_MD 注入）。 */
    private static final ClaudemdEngine CLAUDEMD_SPY = new ClaudemdEngine(
        AutoMemPaths.defaultInstance(),
        new MemoryFileDetection(AutoMemPaths.defaultInstance(), () -> true, () -> true)) {
        @Override
        public void resetGetMemoryFilesCache(String reason) {
            MEMFILES_RESETS.incrementAndGet();
        }
    };

    @AfterEach
    void resetStaticState() {
        COLLAPSE_RESETS.set(0);
        MEMFILES_RESETS.set(0);
        for (Runnable hook : REGISTERED_HOOKS) {
            SystemPromptInjection.unregisterUserCacheClearHook(hook);
        }
        REGISTERED_HOOKS.clear();
        // 复位静态宿主，避免跨用例/跨测试类污染
        new PostCompactCleanup(null, null, null);
    }

    /** 注入全部 spy 协作器（main-thread 操作可观察）。 */
    private static void wireSpies() {
        new PostCompactCleanup(ENABLED_COLLAPSE, new SessionAgentStateRegistry(), CLAUDEMD_SPY);
    }

    /** 注册 cache-clear 观察钩子并跟踪（@AfterEach 注销）。 */
    private static Runnable registerClearHook(AtomicInteger counter) {
        Runnable hook = counter::incrementAndGet;
        REGISTERED_HOOKS.add(hook);
        SystemPromptInjection.registerUserCacheClearHook(hook);
        return hook;
    }

    // ════════════════════════════════════════════════════════════════════
    // 门判定 · isMainThreadCompact（CC postCompactCleanup.ts:36-39）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门判定: 生产大写 REPL_MAIN_THREAD/SDK/USER → true（canonical 归一，S-12）")
    void isMainThreadCompact_productionUppercase_true() {
        assertThat(PostCompactCleanup.isMainThreadCompact("REPL_MAIN_THREAD"))
            .as("生产主循环 name() 大写必须判定为主线程").isTrue();
        assertThat(PostCompactCleanup.isMainThreadCompact("SDK"))
            .as("生产 SDK name() 大写必须判定为主线程").isTrue();
        assertThat(PostCompactCleanup.isMainThreadCompact("USER"))
            .as("生产 USER（主线程用户会话）大写必须判定为主线程").isTrue();
    }

    @Test
    @DisplayName("门判定: 小写既有值域/null → true；非主线程 → false（语义保持）")
    void isMainThreadCompact_existingValues_unchanged() {
        assertThat(PostCompactCleanup.isMainThreadCompact(null))
            .as("undefined 等价 null → main-thread（CC :37）").isTrue();
        assertThat(PostCompactCleanup.isMainThreadCompact("repl_main_thread:outputStyle:custom"))
            .as("outputStyle 变体前缀命中（CC :38 startsWith）").isTrue();
        assertThat(PostCompactCleanup.isMainThreadCompact("sdk")).as("小写 sdk 保持").isTrue();

        assertThat(PostCompactCleanup.isMainThreadCompact("compact"))
            .as("compact 非主线程（CC :39 仅 sdk/repl_main_thread）").isFalse();
        assertThat(PostCompactCleanup.isMainThreadCompact("COMPACT"))
            .as("大写 COMPACT 归一 'compact' 非主线程").isFalse();
        assertThat(PostCompactCleanup.isMainThreadCompact("SESSION_MEMORY"))
            .as("大写 SESSION_MEMORY 归一 'session_memory' 非主线程").isFalse();
        assertThat(PostCompactCleanup.isMainThreadCompact("agent:builtin:fork"))
            .as("subagent 非主线程").isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 生产值域 → 3 项 main-thread 操作执行（S-12 主线程门生效链）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("生产值域: runPostCompactCleanup(REPL_MAIN_THREAD) → 3 项 main-thread 操作全部执行")
    void productionUppercase_mainThreadOperationsExecute() {
        wireSpies();
        AtomicInteger cacheClears = new AtomicInteger();
        registerClearHook(cacheClears);

        PostCompactCleanup.runPostCompactCleanup("REPL_MAIN_THREAD");

        assertThat(COLLAPSE_RESETS.get())
            .as("操作 1: resetContextCollapse 必须执行（CC :42-49）").isEqualTo(1);
        assertThat(cacheClears.get())
            .as("操作 2: getUserContext.cache.clear 等价 clearAllProviderCaches 必须执行（CC :59）").isEqualTo(1);
        assertThat(MEMFILES_RESETS.get())
            .as("操作 3: resetGetMemoryFilesCache('compact') 必须执行（CC :60）").isEqualTo(1);
    }

    @Test
    @DisplayName("生产值域: runPostCompactCleanup(SDK) → 3 项 main-thread 操作全部执行")
    void productionUppercaseSdk_mainThreadOperationsExecute() {
        wireSpies();
        AtomicInteger cacheClears = new AtomicInteger();
        registerClearHook(cacheClears);

        PostCompactCleanup.runPostCompactCleanup("SDK");

        assertThat(COLLAPSE_RESETS.get()).isEqualTo(1);
        assertThat(cacheClears.get()).isEqualTo(1);
        assertThat(MEMFILES_RESETS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非主线程: runPostCompactCleanup(COMPACT) → 3 项 main-thread 操作全部跳过（gate 反向）")
    void nonMainThread_operationsSkipped() {
        wireSpies();
        AtomicInteger cacheClears = new AtomicInteger();
        registerClearHook(cacheClears);

        PostCompactCleanup.runPostCompactCleanup("COMPACT");

        assertThat(COLLAPSE_RESETS.get()).as("非主线程 resetContextCollapse 必须跳过").isZero();
        assertThat(cacheClears.get()).as("非主线程 cache.clear 必须跳过").isZero();
        assertThat(MEMFILES_RESETS.get()).as("非主线程 resetGetMemoryFilesCache 必须跳过").isZero();
    }
}

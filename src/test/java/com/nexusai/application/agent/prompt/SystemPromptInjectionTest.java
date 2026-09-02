package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RES-R5-4 · {@link SystemPromptInjection} 注销通道（unregisterCacheClearHook）意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：{@code CACHE_CLEAR_HOOKS} 静态表只有 register 无 remove，
 * 每服务实例（SystemPromptContextProvider 构造）+1 且永不释放 → 有界累积泄漏。本次为它补
 * remove/注销通道（Java 内部卫生，非 CC 对齐项）：
 * <ol>
 *   <li><b>unregister 幂等移除</b>：注册 N 个 → 注销 M 个 → 表大小 N-M；注销不在表中的回调 → no-op。</li>
 *   <li><b>注销后不再通知</b>：{@code setSystemPromptInjection} / {@code clearAllProviderCaches}
 *       只通知<b>存活</b> provider，已注销的不再被触发（不变量：setter/clearAll 仍须通知全部存活者）。</li>
 *   <li><b>provider 生命周期注销</b>：{@link SystemPromptContextProvider#close()} 注销自身回调，
 *       表不再累积。</li>
 * </ol>
 *
 * <p><b>隔离</b>：{@code CACHE_CLEAR_HOOKS} 为进程级静态表（对齐 CC 模块级 let），逐用例
 * {@link #cleanup()} 注销本用例注册的回调，避免污染其他用例/测试类（CacheInvalidationTest /
 * ContextAnalyzeServiceTest 依赖该表）。
 */
class SystemPromptInjectionTest {

    /** 本用例注册的回调（@AfterEach 统一注销，防止静态表污染）。 */
    private final List<Runnable> registered = new ArrayList<>();

    private Runnable reg() {
        Runnable hook = () -> { };
        registered.add(hook);
        SystemPromptInjection.registerCacheClearHook(hook);
        return hook;
    }

    @AfterEach
    void cleanup() {
        for (Runnable hook : registered) {
            SystemPromptInjection.unregisterCacheClearHook(hook);
        }
        registered.clear();
        SystemPromptInjection.setSystemPromptInjection(null);
    }

    /** 反射读静态表当前大小 · 断言表有界性（不依赖包内私有常量）。 */
    private static int tableSize() throws Exception {
        Field field = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> table = (List<Runnable>) field.get(null);
        return table.size();
    }

    // ── 注销通道幂等移除（RES-R5-4 核心）──

    @Test
    @DisplayName("注册 N 个 → 注销 M 个 → 表大小 N-M（remove 通道补齐前 RED）")
    void unregister_reducesTableSize() throws Exception {
        int before = tableSize();
        Runnable h1 = reg();
        Runnable h2 = reg();
        Runnable h3 = reg();
        assertThat(tableSize() - before).as("注册 3 个后 +3").isEqualTo(3);

        SystemPromptInjection.unregisterCacheClearHook(h1);
        SystemPromptInjection.unregisterCacheClearHook(h2);
        assertThat(tableSize() - before).as("注销 2 个后剩 1").isEqualTo(1);
    }

    @Test
    @DisplayName("注销不在表中的回调 → no-op（幂等，表大小不变）")
    void unregister_absentHook_noOp() throws Exception {
        int before = tableSize();
        SystemPromptInjection.unregisterCacheClearHook(() -> { });
        assertThat(tableSize()).as("未注册回调注销 no-op").isEqualTo(before);
    }

    @Test
    @DisplayName("注销 null → no-op（对齐 register 的 null 守卫）")
    void unregister_nullHook_noOp() throws Exception {
        int before = tableSize();
        SystemPromptInjection.unregisterCacheClearHook(null);
        assertThat(tableSize()).as("null 注销 no-op").isEqualTo(before);
    }

    @Test
    @DisplayName("全部注销 → 表回到基线（不变量：register/unregister 成对，不永久累积）")
    void unregister_all_returnsToBaseline() throws Exception {
        int before = tableSize();
        Runnable h1 = reg();
        Runnable h2 = reg();
        SystemPromptInjection.unregisterCacheClearHook(h1);
        SystemPromptInjection.unregisterCacheClearHook(h2);
        assertThat(tableSize()).as("注册 2 注销 2 → 表大小回到基线").isEqualTo(before);
    }

    // ── 注销后不再通知已注销 provider ──

    @Test
    @DisplayName("注销后 setSystemPromptInjection 只通知存活 provider，不通知已注销者（context.ts:29-34 不变量）")
    void unregisteredHook_notifiedBySetter_absent() throws Exception {
        AtomicInteger survivor = new AtomicInteger();
        AtomicInteger dead = new AtomicInteger();
        Runnable s = () -> survivor.incrementAndGet();
        Runnable d = () -> dead.incrementAndGet();
        registered.add(s);
        registered.add(d);
        SystemPromptInjection.registerCacheClearHook(s);
        SystemPromptInjection.registerCacheClearHook(d);
        SystemPromptInjection.unregisterCacheClearHook(d);

        SystemPromptInjection.setSystemPromptInjection("inject-x");

        assertThat(survivor.get()).as("存活 provider 被 setter 通知").isEqualTo(1);
        assertThat(dead.get()).as("已注销 provider 不再被通知").isZero();
    }

    @Test
    @DisplayName("注销后 clearAllProviderCaches 只通知存活 provider（clearAll 不变量）")
    void unregisteredHook_clearAll_onlySurvivorFires() throws Exception {
        int before = tableSize();
        AtomicInteger survivor = new AtomicInteger();
        AtomicInteger dead = new AtomicInteger();
        Runnable s = () -> survivor.incrementAndGet();
        Runnable d = () -> dead.incrementAndGet();
        registered.add(s);
        registered.add(d);
        SystemPromptInjection.registerCacheClearHook(s);
        SystemPromptInjection.registerCacheClearHook(d);
        SystemPromptInjection.unregisterCacheClearHook(d);

        int hooks = SystemPromptInjection.clearAllProviderCaches();

        assertThat(survivor.get()).as("存活 provider 被 clearAll 通知").isEqualTo(1);
        assertThat(dead.get()).as("已注销 provider 不被 clearAll 通知").isZero();
        assertThat(hooks - before).as("clearAll 返回为存活表大小（含未注销 survivor，相对基线 +1）").isEqualTo(1);
    }

    // ── provider 生命周期注销（close 入口）──

    @Test
    @DisplayName("SystemPromptContextProvider.close() 注销自身回调 → 表不再累积")
    void providerClose_unregistersItsHook() throws Exception {
        int before = tableSize();
        SystemPromptContextProvider provider = new SystemPromptContextProvider(
            "2026-08-07", new UserContextProvider(), new GitStatusProvider());
        assertThat(tableSize() - before).as("构造注册 1 个回调").isEqualTo(1);

        provider.close();
        assertThat(tableSize()).as("close 注销自身回调 → 表回到基线").isEqualTo(before);
    }

    @Test
    @DisplayName("provider.close() 后 setter 双清不再触发该 provider 缓存清理（close 语义不变量）")
    void providerClosed_notifiedBySetter_absent() throws Exception {
        int before = tableSize();
        SystemPromptContextProvider provider = new SystemPromptContextProvider(
            "2026-08-07", new UserContextProvider(), new GitStatusProvider());
        provider.close();

        // close 后 setter 不再通知 → provider 缓存不被双清（无异常即证明未触发；表大小不增长）
        SystemPromptInjection.setSystemPromptInjection("inject-y");
        assertThat(tableSize()).as("close 后 setter 不再向表追加/通知（表大小回到基线）").isEqualTo(before);
    }

    @Test
    @DisplayName("setter 双清仍通知存活 provider（不变量：注册回调仍生效，未破坏 context.ts:29-34）")
    void setter_notifiesRegisteredSurvivors() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        Runnable hook = () -> fired.incrementAndGet();
        registered.add(hook);
        SystemPromptInjection.registerCacheClearHook(hook);

        SystemPromptInjection.setSystemPromptInjection("inject-z");

        assertThat(fired.get()).as("存活注册回调仍被 setter 通知").isEqualTo(1);
    }
}

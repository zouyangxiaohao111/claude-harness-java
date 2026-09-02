package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemPromptContextProvider 意图测试 · 对齐 CC {@code getSystemContext}/{@code getUserContext}/
 * {@code fetchSystemPromptParts}/{@code appendSystemContext}（context.ts:116-189、
 * queryContext.ts:44-74、api.ts:437-447）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：gitStatus 门控（I-14，CCR/禁 git）决定 systemContext
 * 是否携带 git 块；cacheBreaker 门控（BREAK_CACHE_COMMAND + 注入值）决定是否产出
 * CACHE_BREAKER；setter 双清缓存（context.ts:29-34）保证注入变更后上下文立即刷新；
 * custom 短路（I-13）决定三路并行的 default/systemContext 是否跳过。测试钉死这些契约，
 * 防止门控被误放宽或短路被误绕过。
 *
 * <p>隔离：{@code SystemPromptInjection} 为进程级静态值 + 已注册缓存清理回调
 * （对齐 CC 模块级 let + memoize.cache.clear），{@link #resetInjection()} 逐测试复位。
 */
class SystemPromptContextProviderTest {

    @TempDir
    Path tmp;

    private FakeEnv env;
    private GitStatusProvider fakeGit;
    private UserContextProvider fakeUser;

    @BeforeEach
    void setUp() {
        resetInjection();
        env = new FakeEnv();
        fakeGit = new GitStatusProvider(tmp) {
            @Override
            public String getGitStatus() {
                return "GIT-BLOCK";
            }
        };
        fakeUser = new UserContextProvider(tmp) {
            @Override
            public String claudeMd() {
                return "项目指令";
            }

            @Override
            public String currentDate(String sessionStartDate) {
                return "Today's date is " + sessionStartDate + ".";
            }
        };
    }

    private static void resetInjection() {
        SystemPromptInjection.setSystemPromptInjection(null);
    }

    private SystemPromptContextProvider provider(String sessionStartDate) {
        return new SystemPromptContextProvider(sessionStartDate, fakeUser, fakeGit, env);
    }

    // ── gitStatus 门控（I-14）──

    @Test
    @DisplayName("正常（无 CCR/禁 git env）→ systemContext 含 gitStatus")
    void normal_systemContextHasGitStatus() {
        assertThat(provider("2026-08-05").getSystemContext())
            .as("无门控 → gitStatus 注入")
            .containsEntry("gitStatus", "GIT-BLOCK");
    }

    @Test
    @DisplayName("CLAUDE_CODE_REMOTE truthy → systemContext 无 gitStatus（I-14，context.ts:124-128）")
    void ccRemote_systemContextOmitsGitStatus() {
        env.put("CLAUDE_CODE_REMOTE", "1");

        assertThat(provider("2026-08-05").getSystemContext())
            .as("CCR → gitStatus 恒 null，但 context map 仍存在")
            .doesNotContainKey("gitStatus");
    }

    @Test
    @DisplayName("CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS truthy → systemContext 无 gitStatus（I-14，gitSettings.ts）")
    void gitInstructionsDisabled_systemContextOmitsGitStatus() {
        env.put("CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS", "true");

        assertThat(provider("2026-08-05").getSystemContext())
            .as("禁 git 指令 → gitStatus 恒 null")
            .doesNotContainKey("gitStatus");
    }

    // ── cacheBreaker 门控（context.ts:131-147）──

    @Test
    @DisplayName("BREAK_CACHE_COMMAND 门关（默认）→ 即便有注入值也无 cacheBreaker（feature 默认 off）")
    void cacheBreaker_gateOff_noCacheBreaker() {
        SystemPromptInjection.setSystemPromptInjection("inject-x");

        assertThat(provider("2026-08-05").getSystemContext())
            .as("门关 → 注入值被忽略")
            .doesNotContainKey("cacheBreaker");
    }

    @Test
    @DisplayName("BREAK_CACHE_COMMAND 门开 + 注入值非空 → cacheBreaker=[CACHE_BREAKER: <injection>]（context.ts:143-147）")
    void cacheBreaker_gateOnAndInjection_present() {
        env.put("BREAK_CACHE_COMMAND", "1");
        SystemPromptInjection.setSystemPromptInjection("inject-x");

        assertThat(provider("2026-08-05").getSystemContext())
            .as("门开 + 注入 → 产出 cacheBreaker 块")
            .containsEntry("cacheBreaker", "[CACHE_BREAKER: inject-x]");
    }

    @Test
    @DisplayName("BREAK_CACHE_COMMAND 门开但注入值为 null → 无 cacheBreaker（context.ts:143 需 injection 真值）")
    void cacheBreaker_gateOnNoInjection_absent() {
        env.put("BREAK_CACHE_COMMAND", "1");

        assertThat(provider("2026-08-05").getSystemContext())
            .as("门开但无注入 → 不产出")
            .doesNotContainKey("cacheBreaker");
    }

    // ── setter 双清缓存（context.ts:29-34）──

    @Test
    @DisplayName("setSystemPromptInjection 变更 → 双清 getSystemContext/getUserContext 缓存，下次调用重算（context.ts:32-33）")
    void injectionSetter_clearsBothCaches() {
        AtomicInteger claudeMdCalls = new AtomicInteger();
        fakeUser = new UserContextProvider(tmp) {
            @Override
            public String claudeMd() {
                return "claude-" + claudeMdCalls.incrementAndGet();
            }

            @Override
            public String currentDate(String sessionStartDate) {
                return "Today's date is " + sessionStartDate + ".";
            }
        };
        SystemPromptContextProvider p = provider("2026-08-05");

        p.getUserContext();
        assertThat(claudeMdCalls.get()).as("首次 getUserContext 计算一次").isEqualTo(1);
        // memoize：未变更前第二次调用不再重算
        p.getUserContext();
        assertThat(claudeMdCalls.get()).as("memoize 命中，不重算").isEqualTo(1);

        SystemPromptInjection.setSystemPromptInjection("inject-x");

        p.getUserContext();
        assertThat(claudeMdCalls.get()).as("setter 双清 → 下次调用重算").isEqualTo(2);
    }

    @Test
    @DisplayName("setter 清缓存后 getSystemContext 重新求值 gitStatus（双清覆盖 system 通道）")
    void injectionSetter_recomputesSystemContext() {
        env.put("BREAK_CACHE_COMMAND", "1");
        AtomicInteger gitCalls = new AtomicInteger();
        fakeGit = new GitStatusProvider(tmp) {
            @Override
            public String getGitStatus() {
                gitCalls.incrementAndGet();
                return "GIT-" + gitCalls.get();
            }
        };
        SystemPromptContextProvider p = provider("2026-08-05");

        p.getSystemContext();
        assertThat(gitCalls.get()).as("首次求值").isEqualTo(1);
        SystemPromptInjection.setSystemPromptInjection("inject-y");
        Map<String, String> ctx = p.getSystemContext();
        assertThat(gitCalls.get()).as("双清后重算 gitStatus").isEqualTo(2);
        assertThat(ctx).as("重算后 cacheBreaker 注入").containsEntry("cacheBreaker", "[CACHE_BREAKER: inject-y]");
    }

    // ── getUserContext：claudeMd + currentDate 会话冻结（I-10）──

    @Test
    @DisplayName("getUserContext：claudeMd + currentDate（用会话冻结日期，跨午夜不陈旧）")
    void getUserContext_claudeMdAndFrozenDate() {
        SystemPromptContextProvider p = provider("2026-08-04"); // 冻结于 8-04，真实日期已 8-05

        assertThat(p.getUserContext())
            .containsEntry("claudeMd", "项目指令")
            .containsEntry("currentDate", "Today's date is 2026-08-04.");
    }

    // ── SP-07 △-5：并发 memoize 单飞去重（CC lodash memoize promise 共享）──

    @Test
    @DisplayName("getUserContext 并发双调 → claudeMd 只计算一次（SP-07 △-5：CC memoize promise 共享单飞）")
    void getUserContext_concurrentCalls_singleFlight() throws Exception {
        AtomicInteger claudeMdCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fakeUser = new UserContextProvider(tmp) {
            @Override
            public String claudeMd() {
                claudeMdCalls.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "claude-1";
            }

            @Override
            public String currentDate(String sessionStartDate) {
                return "Today's date is " + sessionStartDate + ".";
            }
        };
        SystemPromptContextProvider p = provider("2026-08-05");
        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    p.getUserContext();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threads.add(t);
        }
        startGate.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS))
            .as("至少一线程进入 claudeMd 计算体").isTrue();
        // 现行 volatile 双检非原子：A 阻塞期间 B 必进计算体 → claudeMdCalls=2（RED）
        Thread.sleep(300);
        release.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }
        assertThat(claudeMdCalls.get()).as("并发双调共享同一次计算（CC promise 共享）").isEqualTo(1);
    }

    @Test
    @DisplayName("getSystemContext 并发双调 → getGitStatus 只计算一次（SP-07 △-5 单飞）")
    void getSystemContext_concurrentCalls_singleFlight() throws Exception {
        AtomicInteger gitCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fakeGit = new GitStatusProvider(tmp) {
            @Override
            public String getGitStatus() {
                gitCalls.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "GIT-BLOCK";
            }
        };
        SystemPromptContextProvider p = provider("2026-08-05");
        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    p.getSystemContext();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threads.add(t);
        }
        startGate.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS))
            .as("至少一线程进入 getGitStatus 计算体").isTrue();
        Thread.sleep(300);
        release.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }
        assertThat(gitCalls.get()).as("并发双调共享同一次计算（CC promise 共享）").isEqualTo(1);
    }

    // ── fetchSystemPromptParts 三路并行 + I-13 短路（queryContext.ts:61-73）──

    private static final class CountingSupplier implements Supplier<SystemPrompt> {
        private final AtomicInteger calls = new AtomicInteger();
        private final SystemPrompt value;

        CountingSupplier() {
            this.value = SystemPrompt.from(List.of("DEFAULT-1", "DEFAULT-2"));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public SystemPrompt get() {
            calls.incrementAndGet();
            return value;
        }
    }

    @Test
    @DisplayName("custom 定义（非 null）→ defaultSystemPrompt=[] 且 systemContext={}（I-13 短路，queryContext.ts:62-63/:71）")
    void fetchSystemPromptParts_customShortCircuits() {
        SystemPromptContextProvider p = provider("2026-08-05");
        CountingSupplier assemble = new CountingSupplier();

        SystemPromptParts parts = p.fetchSystemPromptParts("CUSTOM", assemble);

        assertThat(parts.defaultSystemPrompt()).as("I-13：custom 定义 → default 短路为 []").isEmpty();
        assertThat(parts.systemContext()).as("I-13：custom 定义 → systemContext 短路为 {}").isEmpty();
        assertThat(parts.userContext()).as("userContext 恒计算（含 currentDate）").containsKey("currentDate");
        assertThat(assemble.calls()).as("I-13：default 组装不被调用").isZero();
    }

    @Test
    @DisplayName("custom 未定义（null）→ default 组装 + systemContext 正常三路")
    void fetchSystemPromptParts_noCustom_fullPaths() {
        SystemPromptContextProvider p = provider("2026-08-05");
        CountingSupplier assemble = new CountingSupplier();

        SystemPromptParts parts = p.fetchSystemPromptParts(null, assemble);

        assertThat(parts.defaultSystemPrompt()).as("无 custom → default 组装结果").containsExactly("DEFAULT-1", "DEFAULT-2");
        assertThat(parts.systemContext()).as("无 custom → systemContext 含 gitStatus").containsEntry("gitStatus", "GIT-BLOCK");
        assertThat(parts.userContext()).containsKey("currentDate");
        assertThat(assemble.calls()).isEqualTo(1);
    }

    // ── appendSystemContext（api.ts:437-447）──

    @Test
    @DisplayName("appendSystemContext：原元素 + context 单块（key: value 换行 join），空 context 块被过滤（api.ts:441-446）")
    void appendSystemContext_mergesContextBlock() {
        SystemPromptContextProvider p = provider("2026-08-05");

        // LinkedHashMap 保证 key 序（Map.of 迭代序未定义 → 断言不确定）
        java.util.Map<String, String> ctx = new java.util.LinkedHashMap<>();
        ctx.put("gitStatus", "GIT-BLOCK");
        ctx.put("cacheBreaker", "CB");
        List<String> merged = p.appendSystemContext(
            SystemPrompt.from(List.of("SYS-1", "SYS-2")), ctx);

        assertThat(merged).as("原元素序保持 + context 拼接为单元素").containsExactly(
            "SYS-1", "SYS-2", "gitStatus: GIT-BLOCK\ncacheBreaker: CB");
    }

    @Test
    @DisplayName("appendSystemContext 空 context → 只返回原元素（join('') 空串被 filter(Boolean) 移除）")
    void appendSystemContext_emptyContext_noExtraElement() {
        SystemPromptContextProvider p = provider("2026-08-05");

        List<String> merged = p.appendSystemContext(
            SystemPrompt.from(List.of("SYS-1")), Map.of());

        assertThat(merged).as("空 context → 不追加空块").containsExactly("SYS-1");
    }

    /** 假环境变量查询 · 测试隔离，不碰真实进程环境。 */
    private static final class FakeEnv implements SystemPromptContextProvider.Environment {
        private final Map<String, String> env = new HashMap<>();

        void put(String key, String value) {
            env.put(key, value);
        }

        @Override
        public String get(String key) {
            return env.get(key);
        }
    }
}

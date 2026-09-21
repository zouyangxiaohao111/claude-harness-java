package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>步骤 2</b> · 会话级 prompt 缓存 store 意图测试（跨 run 存活 / 跨会话隔离 / 会话终结回收）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：本步的对齐点不是「缓存对象存在」，而是<b>缓存的可见范围</b>
 * —— CC 的 {@code STATE} 是进程级（bootstrap/state.ts:429，一进程一会话），本仓一 JVM 多会话
 * ⇒ 必须映射为<b>会话级（按 sessionId 分区）</b>。因此三条不变量必须钉死，任一条反了都会
 * 让前缀缓存命中塌或让会话串味：
 * <ol>
 *   <li><b>跨 run 存活</b>：同一 sessionId 的相邻 run（= 相邻两次 {@code new AgentState}）
 *       命中<b>同一份</b>分段缓存与<b>同一份</b> provider ⇒ 段值/userContext 不会每 run 重算；
 *       （旧实现每 run new 缓存 ⇒ 跨 run 命中恒 0 = DeepSeek 前缀缓存塌的机制）</li>
 *   <li><b>跨会话隔离</b>：不同 sessionId ⇒ 不同 store（⛔ 不得退化成全局单例）；</li>
 *   <li><b>无会话不共享键</b>：sessionId 为空 ⇒ 未注册的一次性 store（等价改造前的每实例缓存），
 *       ⛔ 不得让所有无会话调用方共用一个桶。</li>
 * </ol>
 *
 * <p>隔离：store 注册表是进程级静态表，逐用例 {@link SessionPromptCacheRegistry#resetForTest()}
 * 清空（同时注销本用例建的 provider 回调），避免污染其他用例/测试类。
 */
class SessionPromptCacheStoreTest {

    @AfterEach
    void tearDown() {
        SessionPromptCacheRegistry.resetForTest();
    }

    /** 反射读 SystemPromptInjection.CACHE_CLEAR_HOOKS 当前大小（provider 回调是否已注销的观察点）。 */
    private static int hookTableSize() throws Exception {
        Field field = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> table = (List<Runnable>) field.get(null);
        return table.size();
    }

    private static String newSessionId() {
        return "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** 假 provider：不触发外部 I/O（git 子进程 / 文件读）的会话级 context provider。 */
    private static SystemPromptContextProvider fakeProvider() {
        return new SystemPromptContextProvider("2026-08-12",
            new UserContextProvider(Path.of("")) {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }
            },
            new GitStatusProvider(Path.of("")) {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            },
            env -> null);
    }

    // ── 不变量 1 · 跨 run 存活 ──

    @Test
    @DisplayName("跨 run 存活：同 sessionId 的两份 AgentState 共用同一 store ⇒ 分段缓存与冻结值跨 run 不重算")
    void sameSession_secondRun_seesSameStoreAndCachedSections() {
        String sessionId = newSessionId();

        AgentState run1 = new AgentState("sys", sessionId, null);
        // run 1：分段 resolve 写入缓存（模拟 registry 写侧）
        run1.systemPromptSectionCache().set("env_info_simple", "env-v1");
        AtomicInteger providerCalls = new AtomicInteger();
        SystemPromptContextProvider provider1 = run1.promptCacheStore().contextProvider(() -> {
            providerCalls.incrementAndGet();
            return fakeProvider();
        });

        // run 2：生产形态 = 同一 sessionId 新建 AgentState（LlmAgentLoop.run 每 run new）
        AgentState run2 = new AgentState("sys", sessionId, null);

        assertThat(run2.promptCacheStore())
            .as("同会话相邻 run 必须命中同一 store（跨 run 不销毁）")
            .isSameAs(run1.promptCacheStore());
        assertThat(run2.systemPromptSectionCache().has("env_info_simple"))
            .as("run 1 写入的分段值对 run 2 可见（否则每 run 重算 ⇒ 段值漂移）")
            .isTrue();
        assertThat(run2.systemPromptSectionCache().get("env_info_simple"))
            .isEqualTo("env-v1");

        SystemPromptContextProvider provider2 = run2.promptCacheStore().contextProvider(() -> {
            providerCalls.incrementAndGet();
            return fakeProvider();
        });
        assertThat(provider2)
            .as("run 2 必须复用 run 1 建的 provider（其 memoize 才跨 run 冻结 userContext/systemContext）")
            .isSameAs(provider1);
        assertThat(providerCalls.get())
            .as("第二个工厂必须未被调用（首个 run 的构造参数胜）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("会话冻结日期：store 只取首建那一次的 sessionStartDate（跨午夜保留旧日期，CC common.ts:24）")
    void sessionStartDate_frozenAtFirstUse() {
        String sessionId = newSessionId();

        SessionPromptCacheStore first = SessionPromptCacheRegistry.forSession(sessionId, "2026-08-12");
        SessionPromptCacheStore second = SessionPromptCacheRegistry.forSession(sessionId, "2026-08-13");

        assertThat(second).isSameAs(first);
        assertThat(second.sessionStartDate())
            .as("后续 run 传入的新日期不得覆盖（否则跨午夜头部字节变化 ⇒ 打掉整段前缀缓存）")
            .isEqualTo("2026-08-12");
    }

    // ── 不变量 2 · 跨会话隔离 ──

    @Test
    @DisplayName("跨会话隔离：不同 sessionId 的 store 互不串扰（⛔ 不是全局单例）")
    void differentSessions_isolated() {
        String a = newSessionId();
        String b = newSessionId();

        AgentState stateA = new AgentState("sys", a, null);
        AgentState stateB = new AgentState("sys", b, null);
        stateA.systemPromptSectionCache().set("env_info_simple", "env-A");

        assertThat(stateA.promptCacheStore()).isNotSameAs(stateB.promptCacheStore());
        assertThat(stateB.systemPromptSectionCache().has("env_info_simple"))
            .as("会话 A 的分段缓存不得串到会话 B")
            .isFalse();
        assertThat(SessionPromptCacheRegistry.size())
            .as("两个会话各占一个 store")
            .isEqualTo(2);
    }

    // ── 不变量 3 · 无会话不共享键 ──

    @Test
    @DisplayName("无会话（sessionId 为空）：不注册、不共享键 —— 每实例一份（等价改造前每实例缓存）")
    void noSession_notShared() {
        int before = SessionPromptCacheRegistry.size();

        AgentState state1 = new AgentState("sys");
        AgentState state2 = new AgentState("sys");

        assertThat(state1.promptCacheStore()).isNotSameAs(state2.promptCacheStore());
        assertThat(state1.promptCacheStore().sessionId()).isNull();
        assertThat(SessionPromptCacheRegistry.size())
            .as("无会话调用方不得占用注册表条目（⛔ 否则所有无会话调用方共用一个桶）")
            .isEqualTo(before);
    }

    // ── 会话终结回收 ──

    @Test
    @DisplayName("会话终结 evict：注册表移除 + provider 回调注销 + 分段缓存清空（CC 进程退出等价物）")
    void evict_removesStoreAndClosesProvider() throws Exception {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.systemPromptSectionCache().set("env_info_simple", "env-v1");
        SessionPromptCacheStore store = state.promptCacheStore();
        store.contextProvider(SessionPromptCacheStoreTest::fakeProvider);

        int withProvider = hookTableSize();
        assertThat(SessionPromptCacheRegistry.size()).isEqualTo(1);

        SessionPromptCacheRegistry.evict(sessionId);

        assertThat(SessionPromptCacheRegistry.size())
            .as("evict 后该会话条目已移除（防 per-session 无界增长）")
            .isZero();
        assertThat(hookTableSize())
            .as("evict → provider close 注销缓存清理回调（register/unregister 成对）")
            .isEqualTo(withProvider - 1);
        assertThat(store.sectionCache().size())
            .as("evict → 分段缓存清空（store 即将被回收，不留悬挂引用）")
            .isZero();

        // 再次取同会话：重新懒建（等价 CC 重新开始一次会话）
        assertThat(SessionPromptCacheRegistry.forSession(sessionId, "2026-08-12"))
            .isNotSameAs(store);
    }

    @Test
    @DisplayName("evict 幂等/安全：null、空白、未知会话一律 no-op（不阻塞会话删除主流程）")
    void evict_isNullSafe() {
        SessionPromptCacheRegistry.evict(null);
        SessionPromptCacheRegistry.evict("");
        SessionPromptCacheRegistry.evict("   ");
        SessionPromptCacheRegistry.evict(newSessionId());
        assertThat(SessionPromptCacheRegistry.size()).isZero();
    }
}

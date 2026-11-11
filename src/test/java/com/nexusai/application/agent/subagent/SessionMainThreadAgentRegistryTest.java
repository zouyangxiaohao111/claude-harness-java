package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SessionMainThreadAgentRegistry} 单元测试 · 对齐 CC 2.1.322 的
 * {@code this.mainThreadAgent} 会话级冻结语义。
 *
 * <p>WHY 这些行为重要：CC 每轮的 system prompt 与工具池都取自会话对象里冻结的那份
 * {@code mainThreadAgent}（offset≈329932127 / 328523098），watcher 只刷
 * AppState.agentDefinitions 不回写它 ⇒ 磁盘 md 改动当前会话不生效。本仓若每次
 * 现查 registry，就与 CC 行为不一致（改了 md 立刻"热更"）。
 *
 * <p>判据全部用「lookup 调用计数 + assertSame 同一实例」表达，⛔ 不用 assertNotNull 之类恒真断言。
 */
class SessionMainThreadAgentRegistryTest {

    private final AtomicInteger lookupCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        SessionMainThreadAgentRegistry.clearAll();
        lookupCalls.set(0);
    }

    @AfterEach
    void tearDown() {
        SessionMainThreadAgentRegistry.clearAll();
    }

    private static AgentDefinition agent(String type) {
        return AgentDefinition.CustomAgentDefinition.builder(type, "when:" + type, "userSettings", "body:" + type)
                .build();
    }

    /** 计数型 lookup：每次调用都计数，返回新实例（模拟「每轮现查 registry」）。 */
    private Supplier<AgentDefinition> countingLookup(String type) {
        return () -> {
            lookupCalls.incrementAndGet();
            return agent(type);
        };
    }

    @Test
    @DisplayName("同 (sessionId, agentType) 二次 resolve：lookup 只调 1 次，且返回同一实例（assertSame）")
    void sameKeyFreezesSnapshot() {
        AgentDefinition first = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        AgentDefinition second = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));

        assertThat(lookupCalls.get()).as("lookup 只应在首次解析时调用一次（此后命中快照）").isEqualTo(1);
        assertThat(second).as("缓存命中必须返回快照里的同一对象（不是 equals 相等的新对象）").isSameAs(first);
    }

    @Test
    @DisplayName("同 sessionId 换 agentType：lookup 再次调用，返回新对象（用户显式切 agent 立刻生效）")
    void agentTypeChangeRefreshesSnapshot() {
        AgentDefinition alpha = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        AgentDefinition beta = SessionMainThreadAgentRegistry.resolve("s1", "beta", countingLookup("beta"));

        assertThat(lookupCalls.get()).as("agentType 变更后必须重新 lookup").isEqualTo(2);
        assertThat(beta).as("换 agent 后返回的是新定义，不是旧快照").isNotSameAs(alpha);
        assertThat(beta.agentType()).isEqualTo("beta");

        // 再切回 alpha：又是一次 lookup（旧快照已被 beta 覆盖，不做多值缓存）
        AgentDefinition back = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        assertThat(lookupCalls.get()).as("切回旧 agentType 也要重新 lookup（每会话仅一个快照槽）").isEqualTo(3);
        assertThat(back.agentType()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("lookup 返回 null：不缓存（再次 resolve 仍然调 lookup）")
    void nullLookupResultIsNotCached() {
        AgentDefinition miss1 = SessionMainThreadAgentRegistry.resolve("s1", "ghost", () -> {
            lookupCalls.incrementAndGet();
            return null;
        });
        AgentDefinition miss2 = SessionMainThreadAgentRegistry.resolve("s1", "ghost", () -> {
            lookupCalls.incrementAndGet();
            return null;
        });

        assertThat(miss1).as("未命中返回 null").isNull();
        assertThat(miss2).isNull();
        assertThat(lookupCalls.get()).as("null 结果不得缓存（否则一个瞬时未命中会被永久冻结）").isEqualTo(2);
    }

    @Test
    @DisplayName("sessionId 为 null（或空白）：每次都调 lookup，不共享缓存")
    void nullSessionIdNeverCaches() {
        AgentDefinition a = SessionMainThreadAgentRegistry.resolve(null, "alpha", countingLookup("alpha"));
        AgentDefinition b = SessionMainThreadAgentRegistry.resolve(null, "alpha", countingLookup("alpha"));
        AgentDefinition c = SessionMainThreadAgentRegistry.resolve("  ", "alpha", countingLookup("alpha"));

        assertThat(lookupCalls.get()).as("无会话键 ⇒ 每次都现查（防跨会话串味）").isEqualTo(3);
        assertThat(a).isNotNull();
        assertThat(b).as("无缓存 ⇒ 两次是不同的新实例").isNotSameAs(a);
        assertThat(c).isNotNull();
    }

    @Test
    @DisplayName("agentType 为空白：每次都调 lookup，不共享缓存")
    void blankAgentTypeNeverCaches() {
        SessionMainThreadAgentRegistry.resolve("s1", "", countingLookup("alpha"));
        SessionMainThreadAgentRegistry.resolve("s1", "  ", countingLookup("alpha"));

        assertThat(lookupCalls.get()).as("空白 agentType ⇒ 不建缓存条目").isEqualTo(2);
        // 且不应在 sessionId 上留下任何快照：随后用合法 agentType 仍要真查一次
        SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        assertThat(lookupCalls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("evict(sessionId) 后再 resolve：lookup 被再次调用")
    void evictForcesRelookup() {
        AgentDefinition first = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        assertThat(lookupCalls.get()).isEqualTo(1);

        SessionMainThreadAgentRegistry.evict("s1");
        AgentDefinition afterEvict = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));

        assertThat(lookupCalls.get()).as("evict 后必须重新 lookup").isEqualTo(2);
        assertThat(afterEvict).as("重新 lookup 得到的是新实例").isNotSameAs(first);
    }

    @Test
    @DisplayName("不同 sessionId 互不共享（同一 agentType 各自冻结各自的实例）")
    void snapshotsArePerSession() {
        AgentDefinition s1 = SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        AgentDefinition s2 = SessionMainThreadAgentRegistry.resolve("s2", "alpha", countingLookup("alpha"));

        assertThat(lookupCalls.get()).as("不同会话各查一次").isEqualTo(2);
        assertThat(s2).as("不同会话拿到各自独立快照").isNotSameAs(s1);
    }

    @Test
    @DisplayName("clearAll 清空全部快照（测试隔离用）")
    void clearAllDropsEverything() {
        SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        SessionMainThreadAgentRegistry.resolve("s2", "beta", countingLookup("beta"));
        assertThat(lookupCalls.get()).isEqualTo(2);

        SessionMainThreadAgentRegistry.clearAll();
        SessionMainThreadAgentRegistry.resolve("s1", "alpha", countingLookup("alpha"));
        SessionMainThreadAgentRegistry.resolve("s2", "beta", countingLookup("beta"));
        assertThat(lookupCalls.get()).isEqualTo(4);
    }
}

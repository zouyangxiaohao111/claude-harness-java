package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionGitStatusRegistry 意图测试 · 对齐 CC context.ts:97 原话「会话开始一次快照、会话内不更新」。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：同一会话跨 run 必须复用同一 {@link GitStatusProvider}
 * （git status 只算一次 → system 尾字节稳定 → 保护 deepseek 单前缀缓存）；不同会话隔离
 * （互不串 gitStatus，Spring 多会话服务下进程级缓存会串）；evict 后重建新实例（防无界增长，
 * 且 /clear / 会话删除后重新快照，等价 CC reset 后新 turn）。
 */
class SessionGitStatusRegistryTest {

    @Test
    @DisplayName("同 sessionId 两次 getForSession → 同一实例（CC context.ts:97 会话内只算一次）")
    void sameSession_returnsSameInstance() {
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();
        GitStatusProvider first = registry.getForSession("sess-a");
        GitStatusProvider second = registry.getForSession("sess-a");

        assertThat(second).as("同 sessionId 跨 run 复用同一实例").isSameAs(first);
        assertThat(registry.size()).as("单会话只占一个槽").isEqualTo(1);
    }

    @Test
    @DisplayName("不同 sessionId → 不同实例（互不串 gitStatus）")
    void differentSession_returnsDifferentInstance() {
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();

        assertThat(registry.getForSession("sess-a"))
            .as("不同会话隔离，不共享 GitStatusProvider")
            .isNotSameAs(registry.getForSession("sess-b"));
        assertThat(registry.size()).as("两会话各占一槽").isEqualTo(2);
    }

    @Test
    @DisplayName("evict 后再次 getForSession → 新实例（会话结束释放，防无界增长）")
    void evict_thenGetForSession_returnsNewInstance() {
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();
        GitStatusProvider before = registry.getForSession("sess-a");

        registry.evict("sess-a");
        assertThat(registry.size()).as("evict 后槽位释放").isZero();

        GitStatusProvider after = registry.getForSession("sess-a");
        assertThat(after).as("evict 后重新快照（新实例）").isNotSameAs(before);
    }

    @Test
    @DisplayName("null/blank sessionId → null（守卫，不注册）；evict(null) no-op 不抛")
    void nullOrBlankSession_returnsNull() {
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();

        assertThat(registry.getForSession(null)).as("null sessionId → null（回落每 run new）").isNull();
        assertThat(registry.getForSession("  ")).as("blank sessionId → null").isNull();

        registry.evict(null); // no-op 不抛
        assertThat(registry.size()).as("守卫不产生槽位").isZero();
    }
}

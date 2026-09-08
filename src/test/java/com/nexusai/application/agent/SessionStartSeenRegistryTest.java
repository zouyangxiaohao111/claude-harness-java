package com.nexusai.application.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C 级 2026-09-07 后端重启副作用补回] 进程级 sessionStartSeen 冷/热判据单元测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：§14 SessionStart 是否执行由本注册表决定 —— 冷（putIfAbsent 成功）
 * 才跑 hook 副作用；同进程已跑过（热）跳过；后端重启 = JVM 内存清空（reset 模拟）。/clear 移除 key → 下 run
 * 恢复冷。每项锁死，供 LlmAgentLoop/CommandController 接线用例复用语义。
 */
@DisplayName("[C 级] SessionStartSeenRegistry 冷/热判据")
class SessionStartSeenRegistryTest {

    @BeforeEach
    @AfterEach
    void clean() {
        SessionStartSeenRegistry.reset();
    }

    @Test
    @DisplayName("首跑=cold（putIfAbsent 成功）；同 key 再跑=hot")
    void firstRun_cold_secondRun_hot() {
        assertThat(SessionStartSeenRegistry.markSeen("sess-key1"))
            .as("本进程首跑该会话 → cold").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("sess-key1"))
            .as("同 key 二次 run（同进程已跑过）→ hot").isFalse();
    }

    @Test
    @DisplayName("不同会话 key 互不影响；null/空白 key 恒 cold（无去重键）")
    void differentKeys_independent_nullKeyAlwaysCold() {
        assertThat(SessionStartSeenRegistry.markSeen("sess-a")).isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("sess-b"))
            .as("另一会话首跑仍 cold").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("sess-a")).isFalse();
        assertThat(SessionStartSeenRegistry.markSeen(null))
            .as("null key 无去重键 → 恒 cold（对齐无会话 run 路径）").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("   ")).isTrue();
    }

    @Test
    @DisplayName("remove（/clear 接线）→ 下 run 恢复 cold；reset（重启模拟）→ 全表冷")
    void remove_resetsSingleKey_resetClearsAll() {
        SessionStartSeenRegistry.markSeen("sess-clear");
        assertThat(SessionStartSeenRegistry.markSeen("sess-clear")).isFalse();
        // = CommandController /clear remove(RequestContext.sessionId)
        SessionStartSeenRegistry.remove("sess-clear");
        assertThat(SessionStartSeenRegistry.markSeen("sess-clear"))
            .as("/clear 移除该会话 key → 下 run 恢复 cold（副作用重跑）").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("sess-clear")).isFalse();

        // reset（@VisibleForTesting / 后端重启模拟）→ 全表清空
        SessionStartSeenRegistry.markSeen("sess-r1");
        SessionStartSeenRegistry.reset();
        assertThat(SessionStartSeenRegistry.markSeen("sess-r1"))
            .as("reset 后（模拟后端重启）→ 再 run 判 cold").isTrue();
        assertThat(SessionStartSeenRegistry.markSeen("sess-clear"))
            .as("reset 清空全表（含 remove 过的 key）").isTrue();
    }
}

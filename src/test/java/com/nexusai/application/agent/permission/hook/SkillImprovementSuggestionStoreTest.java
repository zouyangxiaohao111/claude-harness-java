package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore.PendingSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-13] SkillImprovementSuggestionStore 测试 · 对齐 CC {@code AppState.skillImprovement.suggestion}
 * (skillImprovement.ts:160-165 / useSkillImprovementSurvey.ts:14-17).
 *
 * <p>WHY (规则九 · 测试验证意图): 本 store 是 apply 半环的<b>桥梁</b> — 检测器 (writeSuggestion)
 * 写入, REST 决策端点 (SkillImprovementController) 读取/决策. 关键意图:
 * <ul>
 *   <li><b>session 作用域隔离</b>: 不同 session 的 suggestion 互不可见 — 若泄露, 一个会话的
 *       改进建议会应用到另一个会话的 skill 文件</li>
 *   <li><b>remove 一次性消费</b>: 决策后必须清空 — 否则同一 suggestion 会被重复 apply (静默改文件)</li>
 * </ul>
 */
@DisplayName("[P1-13] SkillImprovementSuggestionStore session 作用域 suggestion 存取")
class SkillImprovementSuggestionStoreTest {

    /** WHY: put→peek→remove 一次消费闭环, 决定后 store 必须清空 (CC :89-95 suggestion:null). */
    @Test
    @DisplayName("put/peek/remove 闭环: remove 后 store 清空")
    void putPeekRemove_roundTripAndClear() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        PendingSuggestion suggestion = new PendingSuggestion("my-skill",
                List.of(new SkillUpdate("s", "c", "r")));

        store.put(sessionId, suggestion);
        assertThat(store.peek(sessionId)).isSameAs(suggestion);
        assertThat(store.size()).isEqualTo(1);

        assertThat(store.remove(sessionId)).isSameAs(suggestion);
        assertThat(store.peek(sessionId)).isNull();
        assertThat(store.size()).isZero();
        // 二次 remove → null (幂等)
        assertThat(store.remove(sessionId)).isNull();
    }

    /** WHY: session 作用域隔离 — 会话 A 的 suggestion 不能被会话 B 决策端点读取. */
    @Test
    @DisplayName("session 作用域: 不同 session 互不可见")
    void sessionScoped_isolation() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionA = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionB = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        store.put(sessionA, new PendingSuggestion("skill-a",
                List.of(new SkillUpdate("s", "c", "r"))));

        assertThat(store.peek(sessionA)).isNotNull();
        assertThat(store.peek(sessionB)).isNull();
        assertThat(store.remove(sessionB)).isNull();
        assertThat(store.size()).isEqualTo(1);
    }

    /** WHY: null sessionId / null suggestion 静默忽略 — REST 层残缺请求不得污染 store. */
    @Test
    @DisplayName("null 入参静默忽略")
    void nullInputs_areIgnored() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        store.put(null, new PendingSuggestion("s", List.of(new SkillUpdate("s", "c", "r"))));
        store.put("sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        assertThat(store.size()).isZero();
        assertThat(store.peek(null)).isNull();
        assertThat(store.remove(null)).isNull();
    }

    /** WHY: 同 session 重复写入覆盖 (每轮检测器刷新 suggestion) — 与 CC setAppState 覆盖语义一致. */
    @Test
    @DisplayName("同 session 重复写入覆盖")
    void sameSession_putOverwrites() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        store.put(sessionId, new PendingSuggestion("old", List.of(new SkillUpdate("s", "c", "r"))));
        PendingSuggestion fresh = new PendingSuggestion("new", List.of(new SkillUpdate("s2", "c2", "r2")));
        store.put(sessionId, fresh);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.peek(sessionId)).isSameAs(fresh);
    }

    // ════════════════════════════════════════════════════════════════════
    // [DEL-SH-01] 会话清理钩子 · CC original: AppState.skillImprovement.suggestion
    //   随会话销毁 (useSkillImprovementSurvey.ts:26 响应式读 / :89-95 决策后清空); CC 无 TTL.
    //   Java 旧 30 分钟 TTL 近似机制已删除, 改为 removeBySession 显式会话结束清理.
    // ════════════════════════════════════════════════════════════════════

    /** WHY: 会话消亡时必须清走该 session 条目 — 对齐 CC AppState 随会话销毁 (无 TTL, 无残留). */
    @Test
    @DisplayName("removeBySession: 只清理目标 session, 其他 session 保留")
    void removeBySession_clearsOnlyTargetSession() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionA = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionB = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        store.put(sessionA, new PendingSuggestion("skill-a",
                List.of(new SkillUpdate("s", "c", "r"))));
        store.put(sessionB, new PendingSuggestion("skill-b",
                List.of(new SkillUpdate("s", "c", "r"))));

        store.removeBySession(sessionA);

        assertThat(store.peek(sessionA)).isNull();
        assertThat(store.peek(sessionB)).isNotNull();
        assertThat(store.size()).isEqualTo(1);
    }

    /** WHY: null / 未知 session 静默忽略 — 会话清理不得误删其他会话条目, 也不得抛异常. */
    @Test
    @DisplayName("removeBySession: null / 未知 session 静默忽略")
    void removeBySession_nullOrUnknown_ignored() {
        SkillImprovementSuggestionStore store = new SkillImprovementSuggestionStore();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        store.put(sessionId, new PendingSuggestion("skill",
                List.of(new SkillUpdate("s", "c", "r"))));

        store.removeBySession(null);
        store.removeBySession("sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.peek(sessionId)).isNotNull();
    }
}

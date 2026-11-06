package com.nexusai.domain.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [goal V253] SessionGoal JSON 往返 + fail-soft 降级测试。
 *
 * <p>WHY: {@code sessions.goal} 列是「当前快照」的唯一真源（恢复时读它而不是反推
 * transcript —— 压缩会剪掉旧的 goal attachment，反推会丢目标）。
 * ⇒ 往返必须无损、坏数据必须降级为「无目标」而不是抛异常。
 */
@DisplayName("[goal V253] SessionGoal JSON 往返与降级")
class SessionGoalJsonTest {

    // ════════════════════════════════════════════════════════════════════
    // 往返
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("往返：6 字段全部无损")
    void roundTrip_allFields() {
        SessionGoal g = new SessionGoal(
            "e2e/login.spec.ts 全部通过，且不修改测试断言",
            SessionGoal.Phase.ACTIVE, 2, 1762300000000L, 145320L,
            "尚未满足 — transcript 里只有单测通过");

        String json = SessionGoal.toJson(g);
        SessionGoal back = SessionGoal.fromJson(json);

        assertThat(back).isNotNull();
        assertThat(back.condition()).isEqualTo(g.condition());
        assertThat(back.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
        assertThat(back.iterations()).isEqualTo(2);
        assertThat(back.setAt()).isEqualTo(1762300000000L);
        assertThat(back.tokensAtStart()).isEqualTo(145320L);
        assertThat(back.lastReason()).isEqualTo(g.lastReason());
    }

    @Test
    @DisplayName("往返：lastReason 为 null 时不写该键（对齐 CC undefined 省略）")
    void roundTrip_nullReason_omitsKey() {
        SessionGoal g = new SessionGoal("cond", SessionGoal.Phase.ACTIVE, 0, 1L, 2L, null);
        String json = SessionGoal.toJson(g);
        assertThat(json).doesNotContain("lastReason");

        SessionGoal back = SessionGoal.fromJson(json);
        assertThat(back).isNotNull();
        assertThat(back.lastReason()).isNull();
    }

    @Test
    @DisplayName("往返：retryAt/retryCount 必须落盘（曾漏写 ⇒ 自动重试链路从未生效）")
    void roundTrip_retrySchedule() {
        SessionGoal g = new SessionGoal(
            "e2e 全绿", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null,
            null, null, null, 1762300060000L, 2);

        String json = SessionGoal.toJson(g);
        assertThat(json).contains("\"retryAt\"");
        assertThat(json).contains("\"retryCount\"");

        SessionGoal back = SessionGoal.fromJson(json);
        assertThat(back).isNotNull();
        assertThat(back.retryAt()).isEqualTo(1762300060000L);
        assertThat(back.retryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("往返：consumeRetry 后写回，retryCount 仍在（上限不被复位）")
    void roundTrip_afterConsumeRetry_countSurvives() {
        SessionGoal consumed = new SessionGoal(
            "c", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null,
            null, null, null, 999L, 3).consumeRetry();

        SessionGoal back = SessionGoal.fromJson(SessionGoal.toJson(consumed));
        assertThat(back).isNotNull();
        assertThat(back.retryAt()).isNull();
        assertThat(back.retryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("往返：无重试排程时不写这两个键")
    void roundTrip_noRetry_omitsKeys() {
        String json = SessionGoal.toJson(
            new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 1L, 0L, null));
        assertThat(json).doesNotContain("retryAt");
        assertThat(json).doesNotContain("retryCount");
    }

    @Test
    @DisplayName("三态 phase 均可往返")
    void roundTrip_allPhases() {
        for (SessionGoal.Phase p : SessionGoal.Phase.values()) {
            SessionGoal back = SessionGoal.fromJson(
                SessionGoal.toJson(new SessionGoal("c", p, 1, 1L, 1L, "r")));
            assertThat(back).as("phase=%s 往返", p).isNotNull();
            assertThat(back.phase()).isEqualTo(p);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // fail-soft 降级（对齐 SessionService.getDisabledTools / TodoWriteTool.todosJsonToMap）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("降级：null / 空白 / 非对象 / 坏 JSON → null（= 无目标），不抛")
    void fromJson_invalid_returnsNull() {
        assertThat(SessionGoal.fromJson(null)).isNull();
        assertThat(SessionGoal.fromJson("")).isNull();
        assertThat(SessionGoal.fromJson("   ")).isNull();
        assertThat(SessionGoal.fromJson("not json at all")).isNull();
        assertThat(SessionGoal.fromJson("[1,2,3]")).isNull();
        assertThat(SessionGoal.fromJson("42")).isNull();
    }

    @Test
    @DisplayName("降级：缺 condition → null")
    void fromJson_missingCondition_returnsNull() {
        assertThat(SessionGoal.fromJson("{\"phase\":\"active\",\"iterations\":1}")).isNull();
        assertThat(SessionGoal.fromJson("{\"condition\":\"\",\"phase\":\"active\"}")).isNull();
        assertThat(SessionGoal.fromJson("{\"condition\":\"   \"}")).isNull();
    }

    @Test
    @DisplayName("降级：缺 phase 视为 ACTIVE；缺计数取默认值")
    void fromJson_missingFields_defaults() {
        SessionGoal g = SessionGoal.fromJson("{\"condition\":\"c\"}");
        assertThat(g).isNotNull();
        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
        assertThat(g.iterations()).isZero();
        assertThat(g.setAt()).isZero();
        assertThat(g.tokensAtStart()).isZero();
        assertThat(g.lastReason()).isNull();
    }

    @Test
    @DisplayName("降级：未知 phase → ACTIVE（fail-soft，不抛）")
    void fromJson_unknownPhase_fallsBackToActive() {
        SessionGoal g = SessionGoal.fromJson("{\"condition\":\"c\",\"phase\":\"definitely-not-a-phase\"}");
        assertThat(g).isNotNull();
        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
    }

    @Test
    @DisplayName("四态 phase 均可往返（paused 是 B4 新增的合法态，不再是「未知」）")
    void fromJson_allFourPhasesRoundTrip() {
        for (SessionGoal.Phase p : SessionGoal.Phase.values()) {
            SessionGoal g = SessionGoal.fromJson(
                SessionGoal.toJson(new SessionGoal("c", p, 1, 1L, 1L, "r")));
            assertThat(g).isNotNull();
            assertThat(g.phase()).isEqualTo(p);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 构造与不变式
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("构造：空 condition 拒绝（非法目标不该落库）")
    void constructor_blankCondition_throws() {
        assertThatThrownBy(() -> new SessionGoal(null, SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionGoal("  ", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("构造：负 iterations 归零；null phase 归 ACTIVE")
    void constructor_normalizes() {
        SessionGoal g = new SessionGoal("c", null, -5, 0L, 0L, null);
        assertThat(g.iterations()).isZero();
        assertThat(g.phase()).isEqualTo(SessionGoal.Phase.ACTIVE);
    }

    @Test
    @DisplayName("上限常量 = 4000（对齐 CC bor=4000）")
    void maxConditionLength_is4000() {
        assertThat(SessionGoal.MAX_CONDITION_LENGTH).isEqualTo(4000);
    }

    @Test
    @DisplayName("toJson(null) → null（列存 null = 无目标）")
    void toJson_null_returnsNull() {
        assertThat(SessionGoal.toJson(null)).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // wither
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("wither：withReason / withPhase / withIterations 只改目标字段")
    void withers_changeOnlyTargetField() {
        SessionGoal g = new SessionGoal("c", SessionGoal.Phase.ACTIVE, 1, 10L, 20L, "old");
        assertThat(g.withReason("new").lastReason()).isEqualTo("new");
        assertThat(g.withReason("new").condition()).isEqualTo("c");
        assertThat(g.withPhase(SessionGoal.Phase.IMPOSSIBLE).phase())
            .isEqualTo(SessionGoal.Phase.IMPOSSIBLE);
        assertThat(g.withIterations(5).iterations()).isEqualTo(5);
        assertThat(g.withIterations(5).setAt()).isEqualTo(10L);
    }

    @Test
    @DisplayName("isActive：仅 ACTIVE 为真")
    void isActive_onlyForActive() {
        assertThat(new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null).isActive()).isTrue();
        assertThat(new SessionGoal("c", SessionGoal.Phase.ACHIEVED, 0, 0L, 0L, null).isActive()).isFalse();
        assertThat(new SessionGoal("c", SessionGoal.Phase.IMPOSSIBLE, 0, 0L, 0L, null).isActive()).isFalse();
    }

    @Test
    @DisplayName("isLive：ACTIVE / PAUSED 为真（两者都需要 Stop hook 继续参与）")
    void isLive_activeOrPaused() {
        assertThat(new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null).isLive()).isTrue();
        assertThat(new SessionGoal("c", SessionGoal.Phase.PAUSED, 0, 0L, 0L, null).isLive()).isTrue();
        assertThat(new SessionGoal("c", SessionGoal.Phase.ACHIEVED, 0, 0L, 0L, null).isLive()).isFalse();
        assertThat(new SessionGoal("c", SessionGoal.Phase.IMPOSSIBLE, 0, 0L, 0L, null).isLive()).isFalse();
    }

    /**
     * ⚠️ <b>回归锁定：consumeRetry 绝不能清零 retryCount</b>。
     *
     * <p>WHY：清零会让 {@code scheduleEvaluatorRetry} 每次都读到 attempts=0 ⇒
     * 永远到不了上限 3 ⇒ <b>无限重试</b>（永久性失败 + 守卫复位 = 重试风暴）。
     * 这个 bug 我在实施时真的写出过，所以锁死它。
     */
    @Test
    @DisplayName("consumeRetry：清 retryAt、保留 retryCount（防无限重试）")
    void consumeRetry_keepsRetryCount() {
        SessionGoal g = new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null)
            .withRetry(123L, 2);
        SessionGoal after = g.consumeRetry();
        assertThat(after.retryAt()).as("消费后不应再有待重试时刻").isNull();
        assertThat(after.retryCount()).as("计数必须保留，否则重试上限永远到不了").isEqualTo(2);
    }

    @Test
    @DisplayName("clearRetryFully：两者都清（只在评估器真的出结论时用）")
    void clearRetryFully_clearsBoth() {
        SessionGoal g = new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null)
            .withRetry(123L, 2);
        SessionGoal after = g.clearRetryFully();
        assertThat(after.retryAt()).isNull();
        assertThat(after.retryCount()).isNull();
    }

    @Test
    @DisplayName("deferral 与 retry 互不影响（两套独立语义）")
    void deferralAndRetry_areIndependent() {
        SessionGoal g = new SessionGoal("c", SessionGoal.Phase.ACTIVE, 0, 0L, 0L, null)
            .withDeferral(100L, 1, 100L)
            .withRetry(200L, 2);
        // 清延迟不该动重试
        SessionGoal clearedDeferral = g.clearDeferral();
        assertThat(clearedDeferral.deferredSince()).isNull();
        assertThat(clearedDeferral.retryAt()).isEqualTo(200L);
        assertThat(clearedDeferral.retryCount()).isEqualTo(2);
        // 消费重试不该动延迟
        SessionGoal consumedRetry = g.consumeRetry();
        assertThat(consumedRetry.deferredSince()).isEqualTo(100L);
        assertThat(consumedRetry.checkinCount()).isEqualTo(1);
    }
}

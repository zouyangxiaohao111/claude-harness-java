package com.nexusai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionKeys <b>@Deprecated 兼容层</b> 测试 · 锁定旧数据读取不变量（[session-id-short] 阶段 1）。
 *
 * <p>WHY（规则九）：sessionId 统一 short 后，SessionKeys.canonicalUuid/originalKey 仅作存量读取
 * 兼容层（存量 DB cron 任务派生 UUID 串反解 / 存量 transcript 文件名派生 UUID）。本测试锁定
 * @Deprecated 兼容语义不变量 —— 防止阶段 2 删除前改写造成旧数据读取漂移。
 */
class SessionKeysTest {

    @Test
    @DisplayName("正向: sess-xxx(8位hex) → 00000000-0000-0000-0000-xxxxxxxx0000 派生 UUID")
    void canonicalUuid_derivesStableUuidFromSessionKey() {
        UUID uuid = SessionKeys.canonicalUuid("sess-a1b2c3d4");
        assertThat(uuid.toString()).isEqualTo("00000000-0000-0000-0000-a1b2c3d40000");
    }

    @Test
    @DisplayName("正向: 合规 UUID 原样返回（幂等）")
    void canonicalUuid_returnsValidUuidUnchanged() {
        UUID given = UUID.randomUUID();
        assertThat(SessionKeys.canonicalUuid(given.toString())).isEqualTo(given);
    }

    @Test
    @DisplayName("正向: null/空白 → 零 UUID 占位（绝不抛）")
    void canonicalUuid_nullAndBlankReturnZeroUuid() {
        assertThat(SessionKeys.canonicalUuid(null)).isEqualTo(new UUID(0L, 0L));
        assertThat(SessionKeys.canonicalUuid("  ")).isEqualTo(new UUID(0L, 0L));
    }

    @Test
    @DisplayName("正向: 任意串 → 稳定 hash UUID（同一输入多次调用同一输出）")
    void canonicalUuid_arbitraryStringIsStable() {
        UUID a = SessionKeys.canonicalUuid("weird-value-中文");
        UUID b = SessionKeys.canonicalUuid("weird-value-中文");
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(new UUID(0L, 0L));
    }

    @Test
    @DisplayName("逆向: 派生 UUID → 原始键 sess-xxx（canonicalUuid 的逆）")
    void originalKey_reversesDerivedUuid() {
        String key = "sess-deadbeef";
        UUID uuid = SessionKeys.canonicalUuid(key);
        assertThat(SessionKeys.originalKey(uuid)).isEqualTo(key);
        assertThat(SessionKeys.originalKey(uuid.toString())).isEqualTo(key);
    }

    @Test
    @DisplayName("逆向: 不可逆（随机 UUID / hash 兜底 UUID / null）→ null 诚实降级")
    void originalKey_returnsNullWhenNotReversible() {
        assertThat(SessionKeys.originalKey(UUID.randomUUID())).isNull();
        // hash 兜底 UUID（非 sess- 派生形态）不可逆
        assertThat(SessionKeys.originalKey(SessionKeys.canonicalUuid("weird-value"))).isNull();
        assertThat(SessionKeys.originalKey((UUID) null)).isNull();
        assertThat(SessionKeys.originalKey((String) null)).isNull();
        assertThat(SessionKeys.originalKey("  ")).isNull();
    }

    @Test
    @DisplayName("逆向(String): 已是 sess-xxx 原样返回；派生 UUID 反解；不可逆 null")
    void originalKey_stringConvenience() {
        assertThat(SessionKeys.originalKey("sess-11223344")).isEqualTo("sess-11223344");
        assertThat(SessionKeys.originalKey(SessionKeys.canonicalUuid("sess-11223344").toString()))
            .isEqualTo("sess-11223344");
        assertThat(SessionKeys.originalKey(UUID.randomUUID().toString())).isNull();
    }

    @Test
    @DisplayName("兼容层一致性: canonicalUuid('sess-xxx') 稳定派生 00000000-...（存量读取不变量，@Deprecated）")
    void canonicalUuid_legacyCompatibilityInvariant() {
        // [session-id-short] ChatService.parseSessionUuid 已删（全仓直键化）；SessionKeys.canonicalUuid
        // 保留为仅存量读取兼容层，锁定派生形态不变量。
        String key = "sess-abcdef01";
        assertThat(SessionKeys.canonicalUuid(key).toString())
            .isEqualTo("00000000-0000-0000-0000-abcdef010000");
    }

    // ════════════════════════════════════════════════════════════════════
    // [批 6] 「确无会话」哨兵 —— 形态上必须「明确不是会话键」+ 承重消费方零抛
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[批 6] NO_SESSION: 无 \"sess-\" 前缀、非 UUID、稳定；isNoSession 精确匹配")
    void noSessionSentinel_hasNoSessionKeyShape() {
        assertThat(SessionKeys.NO_SESSION)
            .as("哨兵必须明确不是会话键：⛔ 不带 sess- 前缀")
            .doesNotStartWith("sess-");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> UUID.fromString(SessionKeys.NO_SESSION)))
            .as("哨兵不得是合法 UUID 形态")
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(SessionKeys.isNoSession(SessionKeys.NO_SESSION)).isTrue();
        assertThat(SessionKeys.isNoSession("sess-a1b2c3d4")).isFalse();
        assertThat(SessionKeys.isNoSession(null)).isFalse();
        assertThat(SessionKeys.isNoSession("")).isFalse();
    }

    /**
     * ⭐ [批 6] 承重消费方复核（用户裁定 #1 明确要求改后**再核一次**）。
     *
     * <p>本用例只覆盖<b>两个</b>消费方 —— {@link SessionKeys} 自身两个方法。它们是「哨兵换形态后
     * 本类内部不抛且行为可预期」的真守卫（直调本尊，见下方两条断言）。
     *
     * <p>⛔ <b>〔假守卫族修复 · F-11〕</b>原用例还有「消费者 3 = {@code AutoDreamConsolidator}
     * 的包含式形态校验」，但那一段是<b>把该类的判据在测试里就地重写后用同一表达式断言自己</b>
     * （测试内自建正则 + 自建 {@code startsWith}，对被测对象零引用、零调用）⇒ 改动生产守卫
     * （{@code AutoDreamConsolidator.java:699}）时它恒绿，<b>声称守护实际守不住</b>。该段已删除。
     * 「哨兵被 transcript 扫描排除」的真守卫改挂在<b>同包</b>测试
     * {@code AutoDreamConsolidatorTest#sessionGate_excludesNoSessionSentinel}
     * （直调包内 {@code scanSessionTranscripts}，含正向对照）。
     */
    @Test
    @DisplayName("[批 6] 哨兵经承重消费方：canonicalUuid 走 hash 兜底不抛 / originalKey 诚实降级 null")
    void noSessionSentinel_survivesLoadBearingConsumers() {
        // 消费者 1: canonicalUuid —— 旧实现在「8 位」与「sess- 前缀」上的特判都不命中 ⇒ hash 兜底
        UUID hashUuid = SessionKeys.canonicalUuid(SessionKeys.NO_SESSION);
        assertThat(hashUuid).isNotNull();
        // 兜底必须稳定（同输入同输出），否则权限 requestId 关联会漂
        assertThat(SessionKeys.canonicalUuid(SessionKeys.NO_SESSION)).isEqualTo(hashUuid);
        // 且必须**不是** sess- 派生形态（否则会被当合法会话 UUID）
        assertThat(hashUuid.toString()).doesNotStartWith("00000000-0000-0000-0000-");

        // 消费者 2: originalKey(String) —— 非 sess- ⇒ 走 canonicalUuid 反解 ⇒ 不可逆 ⇒ null（诚实降级）
        assertThat(SessionKeys.originalKey(SessionKeys.NO_SESSION)).isNull();
        assertThat(SessionKeys.originalKey(hashUuid)).isNull();

        // ⛔ 原「消费者 3: AutoDreamConsolidator 的包含式守卫」整块已删（F-11 假守卫族）——
        //   该块在本类内自建正则 + 自建 startsWith 后断言自己，对生产守卫零引用零调用 ⇒ 零鉴别力。
        //   真守卫见 AutoDreamConsolidatorTest#sessionGate_excludesNoSessionSentinel（同包直调）。
    }
}

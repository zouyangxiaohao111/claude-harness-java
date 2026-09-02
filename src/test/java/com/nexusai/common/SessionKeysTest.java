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
}

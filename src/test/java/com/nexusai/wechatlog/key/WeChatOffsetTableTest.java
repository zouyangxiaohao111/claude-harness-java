package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本 → 偏移链的解析单测。
 *
 * <p>核心约定：已验证版本才算 verified；未验证版本允许 best-effort，
 * 但必须带 verified=false，让上层在失败时能报「版本未适配」而不是「没有候选」。
 */
class WeChatOffsetTableTest {

    private static WeChatOffsetTable table() {
        return new WeChatOffsetTable(java.util.Map.of(
                "4.1.11", WeChatOffsetTable.builtin411()));
    }

    @Test
    @DisplayName("4.1.11 精确/前缀命中都算已验证")
    void verifiedVersionMatches() {
        Optional<WeChatOffsetTable.Resolution> exact = table().resolve("4.1.11");
        assertTrue(exact.isPresent());
        assertTrue(exact.get().verified());
        assertEquals("4.1.11", exact.get().matchedKey());

        Optional<WeChatOffsetTable.Resolution> prefix = table().resolve("4.1.11.15");
        assertTrue(prefix.isPresent());
        assertTrue(prefix.get().verified());
    }

    @Test
    @DisplayName("未验证版本（4.2.0.53）走 best-effort，且 marked verified=false")
    void unknownVersionUsesBestEffortUnverified() {
        Optional<WeChatOffsetTable.Resolution> r = table().resolve("4.2.0.53");
        assertTrue(r.isPresent());
        assertFalse(r.get().verified(), "4.2.0.53 未验证，不能被当成已验证");
        assertEquals("4.1.11", r.get().matchedKey());
        assertEquals(0x10, r.get().chain().nodeBack());
    }

    @Test
    @DisplayName("同时有 4.1 与 4.1.11 时取最长前缀")
    void longestPrefixWins() {
        WeChatOffsetChain c = WeChatOffsetTable.builtin411();
        WeChatOffsetTable t = new WeChatOffsetTable(java.util.Map.of(
                "4.1", c, "4.1.11", c));
        var r = t.resolve("4.1.11.5");
        assertTrue(r.isPresent());
        assertEquals("4.1.11", r.get().matchedKey());
    }

    @Test
    @DisplayName("比所有已知版本都老 ⇒ 没有可用链")
    void olderThanAllKnownIsEmpty() {
        assertTrue(table().resolve("3.9.0").isEmpty());
    }

    @Test
    @DisplayName("properties 能追加一条已验证版本")
    void propertiesAddVerifiedVersion() {
        Properties p = new Properties();
        p.setProperty("4.2.0.53.verified", "true");
        p.setProperty("4.2.0.53.nodeBack", "0x20");
        p.setProperty("4.2.0.53.nodeNeedleOffset", "0x10");
        p.setProperty("4.2.0.53.nodeLengthOffset", "0x18");
        p.setProperty("4.2.0.53.nodeConfigOffset", "0x28");
        p.setProperty("4.2.0.53.configBlobOffset", "0x88");
        p.setProperty("4.2.0.53.dataPtrOffset", "0x8");
        p.setProperty("4.2.0.53.dataLenOffset", "0x10");
        p.setProperty("4.2.0.53.xorMask", "00112233");

        var parsed = WeChatOffsetTable.parseProperties(p);
        assertTrue(parsed.containsKey("4.2.0.53"));
        WeChatOffsetChain c = parsed.get("4.2.0.53");
        assertTrue(c.verified());
        assertEquals(0x20, c.nodeBack());
        assertArrayEquals(new byte[]{0x00, 0x11, 0x22, 0x33}, c.xorMask());
    }

    @Test
    @DisplayName("properties 条目缺字段时跳过，不产生半份链")
    void incompletePropertiesEntryIsSkipped() {
        Properties p = new Properties();
        p.setProperty("4.3.0.nodeBack", "16");
        assertTrue(WeChatOffsetTable.parseProperties(p).isEmpty());
    }

    @Test
    @DisplayName("版本比较按数字段，不做字符串比较")
    void versionCompareIsNumeric() {
        assertTrue(WeChatOffsetTable.compareVersion(
                WeChatOffsetTable.parseVersion("4.1.11"),
                WeChatOffsetTable.parseVersion("4.2.0")) < 0);
        assertEquals(0, WeChatOffsetTable.compareVersion(
                WeChatOffsetTable.parseVersion("4.2.0.53"),
                WeChatOffsetTable.parseVersion("4.2.0.53")));
    }
}

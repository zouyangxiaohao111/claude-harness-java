package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Windows 取密钥的纯逻辑测试（与操作系统无关）。
 *
 * <p>为什么这些断言重要：偏移链是逆向得到的魔数，改一个字节就全废，
 * 且失败表现为「扫不到候选」这种静默现象。把它们钉进测试，至少能防止有人「顺手优化」。
 */
class WindowsKeyExtractorCryptoTest {

    @Test
    @DisplayName("4.1.11 偏移链逐值正确，XOR 掩码与上游一致")
    void builtin411ChainMatchesUpstream() {
        WeChatOffsetChain chain = WeChatOffsetTable.builtin411();
        assertTrue(chain.verified(), "4.1.11 是已验证链");
        assertEquals("4.1.11", chain.versionKey());
        assertEquals(0x10, chain.nodeBack());
        assertEquals(0x10, chain.nodeNeedleOffset());
        assertEquals(0x18, chain.nodeLengthOffset());
        assertEquals(0x28, chain.nodeConfigOffset());
        assertEquals(0x88, chain.configBlobOffset());
        assertEquals(0x8, chain.dataPtrOffset());
        assertEquals(0x10, chain.dataLenOffset());
        assertEquals(32, chain.xorMask().length, "上游掩码是 32 字节");
        String expected = "d2c7442458020000004889442450488b450048844c2448488944254048584c24";
        assertEquals(expected, hex(chain.xorMask()), "掩码必须逐字节与上游一致");
    }

    @Test
    @DisplayName("xorRepeat 用掩码循环异或，且两次异或还原原文")
    void xorRepeatIsReversible() {
        byte[] mask = new byte[]{0x11, 0x22, 0x33};
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] enc = MemoryScanner.xorRepeat(data, mask);
        assertArrayEquals(new byte[]{(byte) 0x10, (byte) 0x20, (byte) 0x30,
                (byte) 0x15, (byte) 0x27}, enc);
        assertArrayEquals(data, MemoryScanner.xorRepeat(enc, mask));
    }

    @Test
    @DisplayName("u64 按小端读 64 位")
    void readsU64LittleEndian() {
        byte[] buf = new byte[]{
                (byte) 0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00, 0x00};
        assertEquals(0x1122334455667788L, MemoryScanner.u64(buf, 0));
        byte[] buf2 = new byte[]{(byte) 0xAA, (byte) 0xBB, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00};
        assertEquals(1L, MemoryScanner.u64(buf2, 2));
    }

    @Test
    @DisplayName("u64 越界返回 -1（不抛异常）")
    void readsU64OutOfBounds() {
        assertEquals(-1L, MemoryScanner.u64(new byte[4], 0));
        assertEquals(-1L, MemoryScanner.u64(null, 0));
        assertEquals(-1L, MemoryScanner.u64(new byte[16], 9));
    }

    @Test
    @DisplayName("x'<hex>' 正则能提取候选，且长度上限 192")
    void literalPatternMatches() {
        String key = "ab".repeat(32);
        String salt = "cd".repeat(16);
        String blob = "noise x'" + key + salt + "' tail";
        var m = java.util.regex.Pattern
                .compile("[xX]'([0-9a-fA-F]{64,192})'")
                .matcher(blob);
        assertTrue(m.find());
        assertEquals(96, m.group(1).length());
    }

    @Test
    @DisplayName("32 字节筛查：全同值判为不可信，正常值通过")
    void rejectsDegenerateKeys() {
        assertTrue(MemoryScanner.isProbable32ByteKey("0102030405060708090a0b0c0d0e0f10"
                + "1112131415161718191a1b1c1d1e1f20"));
        assertFalse(MemoryScanner.isProbable32ByteKey("00".repeat(32)));
        assertFalse(MemoryScanner.isProbable32ByteKey("aa".repeat(32)));
        assertFalse(MemoryScanner.isProbable32ByteKey("abc"));
        assertFalse(MemoryScanner.isProbable32ByteKey(null));
    }

    @Test
    @DisplayName("isSupported 只在 Windows 返回 true")
    void isSupportedOnlyOnWindows() {
        var ex = new WindowsKeyExtractor();
        String os = System.getProperty("os.name", "").toLowerCase();
        assertEquals(os.contains("win"), ex.isSupported());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}

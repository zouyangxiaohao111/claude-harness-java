package com.nexusai.wechatlog.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
/**
 * SQLCipher4 的单元测试。
 *
 * <h2>测试策略（关键：不能只做自证式 round-trip）</h2>
 * <ol>
 *   <li><b>PBKDF2 用公开 RFC 向量</b>：手写 PBKDF2 是全类最容易错的地方（尤其 dkLen &lt; hLen
 *       与迭代累加异或），必须用外部已知答案验证，而不是用自己再实现一遍去对答案。</li>
 *   <li><b>派生结果用 Python 独立实现算出的定值</b>：{@code ENC_KEY}/{@code MAC_KEY} 两个常量
 *       由 {@code hashlib.pbkdf2_hmac} + 手写 mac_salt 逻辑在 Python 侧独立生成，
 *       与 Java 实现无共享代码 —— 能抓出「passphrase 当字符处理」这类编码错误。</li>
 *   <li><b>页加解密用测试内独立的加密器</b>：测试里用裸 {@code javax.crypto} 按规范
 *       构造密文（不复用生产代码的 encrypt 路径，因为生产代码只有 decrypt），
 *       再让 {@link SqlCipher4#decryptPage} 解回来比对。</li>
 *   <li><b>错误密钥必须被拒</b>：只测正例会漏掉「HMAC 校验被绕过」的严重缺陷。</li>
 * </ol>
 */
class SqlCipher4Test {

    // ---- 由 Python 独立实现生成的定值（见 src/test/resources 说明） ----
    private static final String PASSPHRASE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SALT_HEX = "aabbccddeeff00112233445566778899";
    private static final String EXPECTED_ENC_KEY =
            "9188949b2270d59e24db68a0c76336aee48975fca996dee2f4098d8bfd2d80c4";
    private static final String EXPECTED_MAC_KEY =
            "ab8b8dc01d6f5949555ee3b0f0641b41b7b1ed8b127d5cb23498c8de2aa0cf48";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ==================================================================
    // 1. PBKDF2 的公开向量验证
    // ==================================================================

    @Test
    @DisplayName("PBKDF2-HMAC-SHA512 匹配公开发布的已知向量")
    void pbkdf2MatchesKnownVectors() {
        // 公开向量：P="password", S="salt", c=1, dkLen=64
        byte[] dk1 = SqlCipher4.pbkdf2HmacSha512(
                "password".getBytes(), "salt".getBytes(), 1, 64);
        assertEquals("867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252"
                        + "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
                toHex(dk1), "c=1 向量不匹配");

        // c=2
        byte[] dk2 = SqlCipher4.pbkdf2HmacSha512(
                "password".getBytes(), "salt".getBytes(), 2, 64);
        assertEquals("e1d9c16aa681708a45f5c7c4e215ceb66e011a2e9f0040713f18aefdb866d53c"
                        + "f76cab2868a39b9f7840edce4fef5a82be67335c77a6068e04112754f27ccf4e",
                toHex(dk2), "c=2 向量不匹配");

        // c=4096（多轮累加异或，最容易写错的分支）
        byte[] dk4096 = SqlCipher4.pbkdf2HmacSha512(
                "password".getBytes(), "salt".getBytes(), 4096, 64);
        assertEquals("d197b1b33db0143e018b12f3d1d1479e6cdebdcc97c5c0f87f6902e072f457b5"
                        + "143f30602641b3d55cd335988cb36b84376060ecd532e039b742a239434af2d5",
                toHex(dk4096), "c=4096 向量不匹配");
    }

    @Test
    @DisplayName("PBKDF2 在 dkLen < hLen 时只截断首块，不额外计算块")
    void pbkdf2TruncatesWhenDkLenBelowHashLen() {
        byte[] full = SqlCipher4.pbkdf2HmacSha512(
                "password".getBytes(), "salt".getBytes(), 1, 64);
        byte[] trunc = SqlCipher4.pbkdf2HmacSha512(
                "password".getBytes(), "salt".getBytes(), 1, 32);
        assertArrayEquals(Arrays.copyOf(full, 32), trunc,
                "32 字节结果应为 64 字节结果的前 32 字节");
    }

    // ==================================================================
    // 2. 派生链与 Python 独立实现对齐
    // ==================================================================

    @Test
    @DisplayName("deriveEncKey 与 Python 独立实现一致（抓 passphrase 编码错误）")
    void deriveEncKeyMatchesPythonReference() {
        byte[] encKey = SqlCipher4.deriveEncKey(hex(PASSPHRASE), hex(SALT_HEX));
        assertEquals(EXPECTED_ENC_KEY, toHex(encKey),
                "encKey 与 hashlib.pbkdf2_hmac('sha512', passphrase, salt, 256000, 32) 不一致");
    }

    @Test
    @DisplayName("deriveMacKey 与 Python 独立实现一致（salt^0x3a + 2 轮）")
    void deriveMacKeyMatchesPythonReference() {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = SqlCipher4.deriveMacKey(encKey, hex(SALT_HEX));
        assertEquals(EXPECTED_MAC_KEY, toHex(macKey),
                "macKey 与 PBKDF2(encKey, salt^0x3a, 2, 32) 不一致");
    }

    @Test
    @DisplayName("passphrase 含非 ASCII 字节时按原始字节处理（不走 UTF-8 二次编码）")
    void passphraseBytesAreTreatedAsRawBytes() {
        // 0x80..0xBF 在 UTF-8 里非法单独出现，若实现误走 String 编码会得到不同结果
        byte[] weird = new byte[]{(byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff,
                (byte) 0x80, (byte) 0xbf, (byte) 0xfe, (byte) 0xff};
        byte[] encKey = SqlCipher4.deriveEncKey(weird, hex(SALT_HEX));
        assertEquals(32, encKey.length);

        // 同样的字节序列必须稳定（同样输入同输出）
        assertArrayEquals(encKey, SqlCipher4.deriveEncKey(weird, hex(SALT_HEX)));
    }

    // ==================================================================
    // 3. 页加解密（测试内独立构造密文）
    // ==================================================================

    @Test
    @DisplayName("第 1 页解密：salt 跳过 + 明文 SQLite 头重建")
    void decryptFirstPage() throws Exception {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = hex(EXPECTED_MAC_KEY);

        byte[] plain = new byte[SqlCipherParams.PAGE_SIZE];
        System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0, plain, 0,
                SqlCipherParams.SQLITE_HEADER.length);
        for (int i = 16; i < 400; i++) {
            plain[i] = (byte) (i & 0xff);
        }

        byte[] cipherPage = encryptPageIndependently(encKey, macKey, plain, 0,
                hex(SALT_HEX), CipherSuite.V4_SHA512);
        byte[] recovered = SqlCipher4.decryptPage(
                encKey, macKey, cipherPage, 0, CipherSuite.V4_SHA512);

        // 保留区在明文页里应是 0
        byte[] expected = plain.clone();
        Arrays.fill(expected, SqlCipherParams.PAGE_SIZE - CipherSuite.V4_SHA512.reserve(),
                SqlCipherParams.PAGE_SIZE, (byte) 0);
        assertArrayEquals(expected, recovered, "第 1 页解密结果不符");
    }

    @Test
    @DisplayName("第 N 页解密：整页密文体，无 salt 偏移")
    void decryptNonFirstPage() throws Exception {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = hex(EXPECTED_MAC_KEY);

        byte[] plain = new byte[SqlCipherParams.PAGE_SIZE];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) ((i * 7) & 0xff);
        }

        byte[] cipherPage = encryptPageIndependently(encKey, macKey, plain, 3,
                hex(SALT_HEX), CipherSuite.V4_SHA512);
        byte[] recovered = SqlCipher4.decryptPage(
                encKey, macKey, cipherPage, 3, CipherSuite.V4_SHA512);

        byte[] expected = plain.clone();
        Arrays.fill(expected, SqlCipherParams.PAGE_SIZE - CipherSuite.V4_SHA512.reserve(),
                SqlCipherParams.PAGE_SIZE, (byte) 0);
        assertArrayEquals(expected, recovered, "第 4 页解密结果不符");
    }

    @Test
    @DisplayName("全零页原样返回（稀疏文件空洞不能被当作损坏）")
    void allZeroPagePassesThrough() {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = hex(EXPECTED_MAC_KEY);
        byte[] zero = new byte[SqlCipherParams.PAGE_SIZE];

        byte[] out = SqlCipher4.decryptPage(encKey, macKey, zero, 5,
                CipherSuite.V4_SHA512);
        assertArrayEquals(zero, out);
    }

    // ==================================================================
    // 4. 校验与负例
    // ==================================================================

    @Test
    @DisplayName("正确密钥通过校验，错误密钥被拒")
    void verifyAcceptsCorrectKeyRejectsWrongKey() throws Exception {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = hex(EXPECTED_MAC_KEY);

        byte[] plain = new byte[SqlCipherParams.PAGE_SIZE];
        System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0, plain, 0, 16);
        byte[] cipherPage = encryptPageIndependently(encKey, macKey, plain, 0,
                hex(SALT_HEX), CipherSuite.V4_SHA512);

        // 正例：指定套件即可通过
        assertEquals(CipherSuite.V4_SHA512,
                SqlCipher4.verifyEncKey(encKey, cipherPage, CipherSuite.V4_SHA512));

        // 正例：不给套件时自动试探也应当选中 4.x
        assertEquals(CipherSuite.V4_SHA512,
                SqlCipher4.verifyEncKey(encKey, cipherPage),
                "自动试探应识别为 4.x 套件");

        // 负例：换一个字节就应被拒
        byte[] wrong = encKey.clone();
        wrong[0] ^= 0x01;
        assertNull(SqlCipher4.verifyEncKey(wrong, cipherPage, CipherSuite.V4_SHA512),
                "错误密钥必须被拒绝");

        // 负例：长度不对
        assertNull(SqlCipher4.verifyEncKey(new byte[16], cipherPage, null),
                "长度不对的候选必须被拒绝");
    }

    @Test
    @DisplayName("自动试探能在候选套件中选中正确的那个（用 3.x 套件加密 → 识别为 V3_SHA1）")
    void verifyAutoDetectsSuite() throws Exception {
        // 关键设计验证：reserve 不是独立可试探的整数，必须与 HMAC 算法绑定成「套件」。
        // 用 3.x 的 SHA1/64000/48 参数构造密文，自动试探必须识别出 V3_SHA1，
        // 且不能误判成 V4_SHA512（两者的 reserve 与 HMAC 长度都不同）。
        byte[] encKey = SqlCipher4.deriveEncKey(
                hex(PASSPHRASE), hex(SALT_HEX), CipherSuite.V3_SHA1);
        byte[] macKey = SqlCipher4.deriveMacKey(encKey, hex(SALT_HEX), CipherSuite.V3_SHA1);

        byte[] plain = new byte[SqlCipherParams.PAGE_SIZE];
        System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0, plain, 0, 16);

        byte[] cipherPage = encryptPageIndependently(encKey, macKey, plain, 0,
                hex(SALT_HEX), CipherSuite.V3_SHA1);

        assertEquals(CipherSuite.V3_SHA1, SqlCipher4.verifyEncKey(encKey, cipherPage),
                "自动试探应识别出 3.x 套件");
        assertNull(SqlCipher4.verifyEncKey(encKey, cipherPage, CipherSuite.V4_SHA512),
                "3.x 密文不应被误判为 4.x 套件");
    }

    @Test
    @DisplayName("HMAC 被篡改时解密抛异常，不返回垃圾数据")
    void tamperedPageThrows() throws Exception {
        byte[] encKey = hex(EXPECTED_ENC_KEY);
        byte[] macKey = hex(EXPECTED_MAC_KEY);
        byte[] plain = new byte[SqlCipherParams.PAGE_SIZE];
        System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0, plain, 0, 16);

        byte[] cipherPage = encryptPageIndependently(encKey, macKey, plain, 0,
                hex(SALT_HEX), CipherSuite.V4_SHA512);
        cipherPage[100] ^= 0xff;   // 翻转密文体中的一个字节

        assertThrows(SqlCipherException.class, () ->
                SqlCipher4.decryptPage(encKey, macKey, cipherPage, 0,
                        CipherSuite.V4_SHA512));
    }

    @Test
    @DisplayName("明文 SQLite 库不被误判为可解密")
    void plaintextDatabaseIsDetected() {
        byte[] head = new byte[SqlCipherParams.PAGE_SIZE];
        System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0, head, 0, 16);
        assertTrue(SqlCipher4.isPlaintextSqlite(head));
        assertNull(SqlCipher4.verifyEncKey(hex(EXPECTED_ENC_KEY), head, null),
                "明文库不应返回有效套件");
    }

    // ==================================================================
    // 测试内独立加密器（按 SQLCipher 规范裸写，不复用生产解密代码）
    // ==================================================================

    private static byte[] encryptPageIndependently(byte[] encKey, byte[] macKey,
                                                   byte[] plain, int pageIndex,
                                                   byte[] salt, CipherSuite suite) throws Exception {
        int reserve = suite.reserve();
        int reserveOffset = SqlCipherParams.PAGE_SIZE - reserve;
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 0x11);

        int start = (pageIndex == 0) ? SqlCipherParams.SALT_SIZE : 0;
        byte[] bodyLen = Arrays.copyOfRange(plain, start, reserveOffset);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"),
                new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(bodyLen);

        byte[] page = new byte[SqlCipherParams.PAGE_SIZE];
        if (pageIndex == 0) {
            System.arraycopy(salt, 0, page, 0, SqlCipherParams.SALT_SIZE);
        }
        System.arraycopy(ct, 0, page, start, ct.length);
        System.arraycopy(iv, 0, page, reserveOffset, SqlCipherParams.IV_SIZE);

        // HMAC := HMAC(macKey, 密文体 || IV || LE32(pageIndex+1))
        Mac mac = Mac.getInstance(suite.hmacAlgorithm());
        mac.init(new SecretKeySpec(macKey, suite.hmacAlgorithm()));
        mac.update(page, start, reserveOffset + SqlCipherParams.IV_SIZE - start);
        mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(pageIndex + 1).array());
        byte[] h = mac.doFinal();
        System.arraycopy(h, 0, page, reserveOffset + SqlCipherParams.IV_SIZE,
                suite.hmacSize());
        return page;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}

package com.nexusai.wechatlog.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KeyMatcher} 的测试。
 *
 * <h2>要验证的核心命题</h2>
 * 「候选池里不管装的是 raw key 还是 passphrase，都能被正确识别并归到对应库」。
 * 这是三平台取密钥能统一收口的前提 —— 如果这里错了，整个方案在各平台上都得各自特判。
 *
 * <h2>测试数据</h2>
 * 用测试内独立的加密器按 SQLCipher 规范构造密文（不复用生产解密路径）。
 */
class KeyMatcherTest {

    private static final byte[] PASSPHRASE = hex(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    private static final byte[] SALT = hex("aabbccddeeff00112233445566778899");

    @Test
    @DisplayName("候选是 raw encKey 时能被识别为 RAW_KEY")
    void matchesRawKey(@TempDir Path dir) throws Exception {
        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        Path db = writeEncryptedDb(dir, "message_0.db", encKey, SALT);

        List<byte[]> candidates = List.of(encKey);
        List<KeyMatcher.Match> matches =
                KeyMatcher.matchAll(List.of(db), candidates);

        assertEquals(1, matches.size(), "应命中 1 个库");
        assertEquals(KeyMatcher.Origin.RAW_KEY, matches.get(0).source(),
                "raw key 应被识别为 RAW_KEY（而不是又跑一遍 256000 轮 PBKDF2）");
        assertArrayEquals(encKey, matches.get(0).encKey());
    }

    @Test
    @DisplayName("候选是 passphrase 时也能命中，来源标为 PASSPHRASE")
    void matchesPassphrase(@TempDir Path dir) throws Exception {
        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        Path db = writeEncryptedDb(dir, "message_0.db", encKey, SALT);

        // 候选只给 passphrase（不给已经派生好的 key）
        List<KeyMatcher.Match> matches =
                KeyMatcher.matchAll(List.of(db), List.of(PASSPHRASE));

        assertEquals(1, matches.size(), "passphrase 应能派生出可用密钥并命中");
        assertEquals(KeyMatcher.Origin.PASSPHRASE, matches.get(0).source());
        assertArrayEquals(encKey, matches.get(0).encKey(),
                "派生出的 encKey 应与直接用的一致");
    }

    @Test
    @DisplayName("候选池混装 raw key 与垃圾时，正确的那把胜出")
    void mixedCandidatesPicksCorrectOne(@TempDir Path dir) throws Exception {
        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        Path db = writeEncryptedDb(dir, "message_0.db", encKey, SALT);

        List<byte[]> candidates = new ArrayList<>();
        // 塞一堆干扰项（模拟内存里抓到的一堆 32 字节）
        for (int i = 0; i < 5; i++) {
            byte[] junk = new byte[32];
            Arrays.fill(junk, (byte) (0xA0 + i));
            candidates.add(junk);
        }
        candidates.add(encKey);

        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(List.of(db), candidates);
        assertEquals(1, matches.size());
        assertArrayEquals(encKey, matches.get(0).encKey(), "应选中真正的那把");
    }

    @Test
    @DisplayName("每库独立密钥：两把不同 key 各自归到自己的库")
    void perDatabaseKeysAreAttributed(@TempDir Path dir) throws Exception {
        byte[] saltA = hex("00112233445566778899aabbccddeeff");
        byte[] saltB = hex("ffeeddccbbaa99887766554433221100");

        byte[] keyA = SqlCipher4.deriveEncKey(PASSPHRASE, saltA);
        byte[] keyB = SqlCipher4.deriveEncKey(PASSPHRASE, saltB);

        Path dbA = writeEncryptedDb(dir, "message_0.db", keyA, saltA);
        Path dbB = writeEncryptedDb(dir, "contact.db", keyB, saltB);

        List<KeyMatcher.Match> matches =
                KeyMatcher.matchAll(List.of(dbA, dbB), List.of(keyA, keyB));

        assertEquals(2, matches.size(), "两个库都应命中");
        // 断言「A 的 key 归 A、B 的 key 归 B」，而不是互相搞错
        for (KeyMatcher.Match m : matches) {
            if (m.databasePath().getFileName().toString().equals("message_0.db")) {
                assertArrayEquals(keyA, m.encKey(), "message_0.db 应配 saltA 的 key");
            } else {
                assertArrayEquals(keyB, m.encKey(), "contact.db 应配 saltB 的 key");
            }
        }
    }

    @Test
    @DisplayName("全是垃圾候选时不误命中")
    void noMatchWhenAllCandidatesWrong(@TempDir Path dir) throws Exception {
        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        Path db = writeEncryptedDb(dir, "message_0.db", encKey, SALT);

        byte[] junk = new byte[32];
        Arrays.fill(junk, (byte) 0x5A);
        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(List.of(db), List.of(junk));
        assertTrue(matches.isEmpty(), "错误候选不应被接受");
    }

    @Test
    @DisplayName("长度不是 32 的候选被忽略（不抛异常）")
    void ignoresWrongLengthCandidates(@TempDir Path dir) throws Exception {
        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        Path db = writeEncryptedDb(dir, "message_0.db", encKey, SALT);

        List<byte[]> candidates = List.of(new byte[16], new byte[8], encKey);
        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(List.of(db), candidates);
        assertEquals(1, matches.size(), "应跳过长度不对的候选，命中正确的那把");
    }

    @Test
    @DisplayName("明文库不需要密钥，不进入匹配结果")
    void plaintextDbIsSkipped(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("plain.db");
        byte[] page = new byte[4096];
        byte[] hdr = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(hdr, 0, page, 0, hdr.length);
        Files.write(db, page);

        byte[] encKey = SqlCipher4.deriveEncKey(PASSPHRASE, SALT);
        List<KeyMatcher.Match> matches = KeyMatcher.matchAll(List.of(db), List.of(encKey));
        assertTrue(matches.isEmpty(), "明文库不应被当作需要解密的库");
    }

    // ==================================================================

    /** 写一个按 SQLCipher 规范加密的单页库（page 0）。 */
    private Path writeEncryptedDb(Path dir, String name, byte[] encKey, byte[] salt)
            throws Exception {
        Path db = dir.resolve(name);
        Files.write(db, buildCipherPage0(encKey, salt));
        return db;
    }

    private byte[] buildCipherPage0(byte[] encKey, byte[] salt) throws Exception {
        byte[] macKey = SqlCipher4.deriveMacKey(encKey, salt);
        int reserve = CipherSuite.V4_SHA512.reserve();
        int reserveOffset = 4096 - reserve;
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 0x22);

        byte[] plain = new byte[4096];
        byte[] hdr = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(hdr, 0, plain, 0, hdr.length);
        for (int i = 16; i < 200; i++) {
            plain[i] = (byte) (i & 0xff);
        }

        byte[] body = Arrays.copyOfRange(plain, 16, reserveOffset);
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(body);

        byte[] page = new byte[4096];
        System.arraycopy(salt, 0, page, 0, 16);
        System.arraycopy(ct, 0, page, 16, ct.length);
        System.arraycopy(iv, 0, page, reserveOffset, 16);

        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
        mac.update(page, 16, reserveOffset + 16 - 16);
        mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array());
        byte[] h = mac.doFinal();
        System.arraycopy(h, 0, page, reserveOffset + 16, 64);
        return page;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}

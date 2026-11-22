package com.nexusai.wechatlog.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * SQLCipher 页级加解密（微信 WCDB 使用）。
 *
 * <h2>派生链</h2>
 * <pre>
 * encKey = PBKDF2-HMAC-{suite}(passphrase, salt,       suite.iterations, 32)
 * macKey = PBKDF2-HMAC-{suite}(encKey,     salt ^ 0x3a, 2,               32)
 * </pre>
 *
 * <h2>⚠️ 两种「密钥」必须区分（读两个上游实现时最大的坑）</h2>
 * <ul>
 *   <li><b>passphrase</b>：微信 4.1.10+ 内存里只留这个，需再跑 {@link #deriveEncKey}
 *       才是真正的 AES key。断 {@code CCKeyDerivationPBKDF} 拿到的是它。</li>
 *   <li><b>raw encKey</b>：已派生的 32 字节 AES key，直接可用。断
 *       {@code CCCryptorCreate} 拿到的是它（第 4 参就是成品 key）。</li>
 * </ul>
 * 因此本类提供两条入口；调用方不必先判断拿到哪种 —— {@link KeyMatcher} 把两种都当候选，
 * 用 HMAC 校验反推真相。
 *
 * <h2>⚠️ 为什么不直接用 {@code PBEKeySpec}</h2>
 * JDK 的 {@code PBKDF2KeyImpl} 对 {@code char[]} 密码做「含非 ASCII 时转 UTF-8」的自适应
 * 编码，而 SQLCipher 要的是<b>原始字节</b>。用 {@code PBEKeySpec} 会在密钥含非 ASCII 字节时
 * 静默算出错误结果。所以这里手写 PBKDF2（RFC 8018），字节语义完全可控。
 *
 * <h2>页格式（以 4.x 的 reserve=80 为例）</h2>
 * <pre>
 * page 0:  [ salt(16) ][ 密文体(4000) ][ IV(16) ][ HMAC(64) ]
 * page N:  [        密文体(4016)         ][ IV(16) ][ HMAC(64) ]
 *
 * HMAC := HMAC-SHA512(macKey, 密文体 || IV || LE32(页号+1))
 * 解密 := AES-256-CBC(密文体, IV)
 * </pre>
 */
public final class SqlCipher4 {

    private SqlCipher4() {
    }

    // ==================================================================
    // 密钥派生
    // ==================================================================

    /** 用默认套件（4.x）派生。 */
    public static byte[] deriveEncKey(byte[] passphrase, byte[] salt) {
        return deriveEncKey(passphrase, salt, CipherSuite.defaultSuite());
    }

    /**
     * 从 passphrase 派生单个数据库的 AES 主密钥。
     *
     * @param passphrase 32 字节 passphrase（微信 4.1.10+ 内存里只留的那种）
     * @param salt       该数据库文件头 16 字节
     * @param suite      密码套件
     * @return 32 字节 encKey
     */
    public static byte[] deriveEncKey(byte[] passphrase, byte[] salt, CipherSuite suite) {
        requireNonNullNonEmpty(passphrase, "passphrase");
        requireSalt(salt);
        return pbkdf2(passphrase, salt, suite.pbkdf2Iterations(),
                SqlCipherParams.KEY_SIZE, suite.hmacAlgorithm());
    }

    /** 用默认套件（4.x）派生 MAC key。 */
    public static byte[] deriveMacKey(byte[] encKey, byte[] salt) {
        return deriveMacKey(encKey, salt, CipherSuite.defaultSuite());
    }

    /**
     * 从 encKey 派生 MAC key：{@code PBKDF2(encKey, salt^0x3a, 2, 32)}。
     *
     * <p>注意 password 是 <b>encKey</b> 而非原始 passphrase，且只有 2 轮。
     */
    public static byte[] deriveMacKey(byte[] encKey, byte[] salt, CipherSuite suite) {
        if (encKey == null || encKey.length != SqlCipherParams.KEY_SIZE) {
            throw new IllegalArgumentException("encKey 必须是 32 字节");
        }
        requireSalt(salt);
        return pbkdf2(encKey, xor(salt, SqlCipherParams.MAC_SALT_XOR),
                SqlCipherParams.MAC_KEY_ITERATIONS, SqlCipherParams.KEY_SIZE, suite.hmacAlgorithm());
    }

    /**
     * PBKDF2（RFC 8018 §5.2）。
     *
     * <pre>
     * U1 = HMAC-(alg)(P, S || INT_BE32(1))
     * Ui = HMAC-(alg)(P, U(i-1))
     * T1 = U1 xor U2 xor ... xor Uc
     * </pre>
     */
    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int dkLen, String hmacAlg) {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations 必须 >= 1");
        }
        try {
            Mac mac = Mac.getInstance(hmacAlg);
            mac.init(new SecretKeySpec(password, hmacAlg));

            byte[] out = new byte[dkLen];
            int offset = 0;
            int blockIndex = 1;

            while (offset < dkLen) {
                mac.update(salt);
                mac.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(blockIndex).array());
                byte[] u = mac.doFinal();
                byte[] t = u.clone();

                for (int i = 1; i < iterations; i++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < t.length; k++) {
                        t[k] ^= u[k];
                    }
                }

                int take = Math.min(t.length, dkLen - offset);
                System.arraycopy(t, 0, out, offset, take);
                offset += take;
                blockIndex++;

                Arrays.fill(u, (byte) 0);
                Arrays.fill(t, (byte) 0);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2-" + hmacAlg + " 派生失败", e);
        }
    }

    /** 便捷重载：PBKDF2-HMAC-SHA512（保留给测试用）。 */
    static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int dkLen) {
        return pbkdf2(password, salt, iterations, dkLen, "HmacSHA512");
    }

    // ==================================================================
    // 校验
    // ==================================================================

    /**
     * 用候选密钥校验第 1 页 HMAC，判断它是否真的是这个数据库的密钥。
     *
     * <p><b>整个方案的核心</b>：让「拿到的是 passphrase 还是 raw key」这个与版本强相关的
     * 问题变成不需要关心的事 —— 候选池里每个 32 字节都试一次，能通过 HMAC 的就是对的。
     *
     * @param encKey 候选 AES 主密钥（已派生形态）
     * @param page0  数据库前 4096 字节
     * @param suite  指定套件；传 {@code null} 表示按 {@link CipherSuite#candidates()} 依次试探
     * @return 校验通过时返回实际生效的套件，否则 {@code null}
     */
    public static CipherSuite verifyEncKey(byte[] encKey, byte[] page0, CipherSuite suite) {
        if (page0 == null || page0.length < SqlCipherParams.PAGE_SIZE) {
            return null;
        }
        if (encKey == null || encKey.length != SqlCipherParams.KEY_SIZE) {
            return null;
        }
        // 已是明文库，不存在「密钥对不对」
        if (isPlaintextSqlite(page0)) {
            return null;
        }

        byte[] salt = Arrays.copyOfRange(page0, 0, SqlCipherParams.SALT_SIZE);
        if (suite != null) {
            return verifyWithSuite(encKey, page0, salt, suite) ? suite : null;
        }
        for (CipherSuite s : CipherSuite.candidates()) {
            if (verifyWithSuite(encKey, page0, salt, s)) {
                return s;
            }
        }
        return null;
    }

    /** 便捷重载：自动按候选套件试探。 */
    public static CipherSuite verifyEncKey(byte[] encKey, byte[] page0) {
        return verifyEncKey(encKey, page0, null);
    }

    private static boolean verifyWithSuite(byte[] encKey, byte[] page0, byte[] salt,
                                           CipherSuite suite) {
        int hmacOffset = suite.hmacOffset();
        if (hmacOffset < 0
                || hmacOffset + suite.hmacSize() > SqlCipherParams.PAGE_SIZE) {
            return false;
        }
        byte[] macKey = deriveMacKey(encKey, salt, suite);
        byte[] expected = computePageHmac(macKey, page0, 0, hmacOffset, suite);
        byte[] actual = Arrays.copyOfRange(page0, hmacOffset, hmacOffset + suite.hmacSize());
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 计算某页 HMAC。
     *
     * @param macKey    32 字节 MAC key
     * @param page      页字节（长度 &gt;= 4096）
     * @param pageIndex 0-based 页号
     * @param dataEnd   HMAC 数据结束偏移（不含）
     * @param suite     密码套件
     */
    static byte[] computePageHmac(byte[] macKey, byte[] page, int pageIndex,
                                  int dataEnd, CipherSuite suite) {
        try {
            Mac mac = Mac.getInstance(suite.hmacAlgorithm());
            mac.init(new SecretKeySpec(macKey, suite.hmacAlgorithm()));
            // page 0 跳过前 16 字节 salt（salt 是明文）
            int start = (pageIndex == 0) ? SqlCipherParams.SALT_SIZE : 0;
            mac.update(page, start, dataEnd - start);
            // 页号小端 32 位，1-based
            mac.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(pageIndex + 1).array());
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    // ==================================================================
    // 解密
    // ==================================================================

    /**
     * 解密单页。
     *
     * @param encKey    32 字节 AES 主密钥
     * @param macKey    32 字节 MAC key
     * @param page      源页（至少 4096 字节）
     * @param pageIndex 0-based 页号
     * @param suite     密码套件（决定 reserve / HMAC 算法）
     * @return 明文页（4096 字节，保留区补 0）
     * @throws SqlCipherException HMAC 校验失败（密钥不对或数据损坏）
     */
    public static byte[] decryptPage(byte[] encKey, byte[] macKey,
                                     byte[] page, int pageIndex, CipherSuite suite) {
        if (page.length < SqlCipherParams.PAGE_SIZE) {
            throw new SqlCipherException("页长度不足: " + page.length);
        }

        // 全零页（稀疏文件空洞）SQLCipher 不加密，原样返回
        if (SqlCipherParams.isAllZero(page, 0, SqlCipherParams.PAGE_SIZE)) {
            return Arrays.copyOf(page, SqlCipherParams.PAGE_SIZE);
        }

        int reserve = suite.reserve();
        int start = (pageIndex == 0) ? SqlCipherParams.SALT_SIZE : 0;
        int reserveOffset = SqlCipherParams.PAGE_SIZE - reserve;
        int hmacOffset = reserveOffset + SqlCipherParams.IV_SIZE;

        if (reserveOffset < 0
                || hmacOffset + suite.hmacSize() > SqlCipherParams.PAGE_SIZE) {
            throw new SqlCipherException("套件参数非法: " + suite);
        }

        // 1. HMAC 校验
        byte[] expected = computePageHmac(macKey, page, pageIndex, hmacOffset, suite);
        byte[] actual = Arrays.copyOfRange(page, hmacOffset, hmacOffset + suite.hmacSize());
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new SqlCipherException(
                    "第 " + (pageIndex + 1) + " 页 HMAC 校验失败（密钥不匹配或数据损坏）");
        }

        // 2. AES-256-CBC
        byte[] ciphertext = Arrays.copyOfRange(page, start, reserveOffset);
        if (ciphertext.length % 16 != 0) {
            throw new SqlCipherException("密文体长度不是 16 的倍数: " + ciphertext.length);
        }
        byte[] iv = Arrays.copyOfRange(page, reserveOffset,
                reserveOffset + SqlCipherParams.IV_SIZE);
        byte[] body;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
            body = cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new SqlCipherException("AES-CBC 解密失败", e);
        }

        // 3. 组装明文页；保留区补 0，不残留密文
        byte[] out = new byte[SqlCipherParams.PAGE_SIZE];
        if (pageIndex == 0) {
            System.arraycopy(SqlCipherParams.SQLITE_HEADER, 0,
                    out, 0, SqlCipherParams.SQLITE_HEADER.length);
            System.arraycopy(body, 0, out, SqlCipherParams.SQLITE_HEADER.length, body.length);
        } else {
            System.arraycopy(body, 0, out, 0, body.length);
        }
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 判断给定文件头是否已是明文 SQLite。 */
    public static boolean isPlaintextSqlite(byte[] head) {
        if (head == null || head.length < 15) {
            return false;
        }
        for (int i = 0; i < 15; i++) {
            if (head[i] != SqlCipherParams.SQLITE_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    static byte[] xor(byte[] data, byte x) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ x);
        }
        return out;
    }

    private static void requireNonNullNonEmpty(byte[] v, String name) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    private static void requireSalt(byte[] salt) {
        if (salt == null || salt.length != SqlCipherParams.SALT_SIZE) {
            throw new IllegalArgumentException("salt 必须是 16 字节");
        }
    }
}

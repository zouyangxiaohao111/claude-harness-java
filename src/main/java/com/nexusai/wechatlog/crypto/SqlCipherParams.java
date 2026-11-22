package com.nexusai.wechatlog.crypto;

/**
 * 微信 WCDB / SQLCipher4 的加密参数常量。
 *
 * <h2>参数来源（两处独立实现互证一致）</h2>
 * <ul>
 *   <li>chatlog（Go，{@code internal/wechat/decrypt/darwin/v4.go}）：
 *       {@code V4PageSize=4096, V4IterCount=256000, HmacSHA512Size=64}</li>
 *   <li>wcdb-key-tool（Python，{@code wcdb_key_tool.py}）：
 *       {@code PAGE_SZ=4096, HMAC_SZ=64, RESERVE_SZ=80}</li>
 * </ul>
 *
 * <h2>reserve 的来历（不是硬编码的魔法值）</h2>
 * <pre>
 * reserve = IV(16) + HMAC(64) = 80，恰好是 AES 块大小 16 的整数倍，无需补齐。
 * 即每页结构： [ 密文体 (4096-80=4016) ][ IV (16) ][ HMAC (64) ]
 * </pre>
 *
 * <h2>⚠️ 与 chatlog 的一处刻意分歧</h2>
 * chatlog 解密后把页尾的 IV+HMAC 原样拼回明文页（{@code DecryptPage} 末尾
 * {@code append(encrypted, pageBuf[pageSize-reserve:]...)}）。但页尾那段是
 * SQLCipher 的加密元数据，属于 SQLite 的「保留区」，明文库里应当是 0。
 * 本实现按 rmqg/wechat-mac-export 的做法写零，理由：保留区残留密文会让
 * SQLite 的完整性校验在极端情况下读到垃圾。两者对正文读取无差别。
 */
public final class SqlCipherParams {

    private SqlCipherParams() {
    }

    /** 页大小（字节）。 */
    public static final int PAGE_SIZE = 4096;

    /** 主密钥长度（AES-256）。 */
    public static final int KEY_SIZE = 32;

    /** 每个数据库文件头 16 字节是 salt（明文）。 */
    public static final int SALT_SIZE = 16;

    /** AES-CBC 的 IV 长度。 */
    public static final int IV_SIZE = 16;

    /** HMAC-SHA512 输出长度。 */
    public static final int HMAC_SIZE = 64;

    /** 每页保留区大小 = IV + HMAC。 */
    public static final int RESERVE_SIZE = IV_SIZE + HMAC_SIZE;

    /** SQLCipher 4 的 PBKDF2 迭代次数（SHA512）。 */
    public static final int PBKDF2_ITERATIONS = 256_000;

    /** 派生 MAC key 时的固定 2 轮（SQLCipher 规范）。 */
    public static final int MAC_KEY_ITERATIONS = 2;

    /** MAC salt = salt XOR 0x3a（SQLCipher 规范）。 */
    public static final byte MAC_SALT_XOR = 0x3a;

    /** 明文 SQLite 文件头。 */
    public static final byte[] SQLITE_HEADER = "SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    /**
     * 加密页中 HMAC 覆盖范围的右边界（不含）。
     *
     * <p>= PAGE_SIZE - RESERVE_SIZE + IV_SIZE = 4032。
     * 即 HMAC 覆盖「密文体 + IV」，不含 HMAC 自身。
     */
    public static final int HMAC_DATA_END = PAGE_SIZE - RESERVE_SIZE + IV_SIZE;

    /** 页尾 HMAC 的起始偏移（= 4032）。 */
    public static final int HMAC_OFFSET = PAGE_SIZE - RESERVE_SIZE + IV_SIZE;

    /**
     * 判定一个 4096 字节页是否为全零。
     *
     * <p>SQLCipher 对稀疏文件的空洞页不加密，原样保留全零；解密时必须原样写出，
     * 否则 SQLite 会把空洞当成损坏页。
     */
    public static boolean isAllZero(byte[] page, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (page[i] != 0) {
                return false;
            }
        }
        return true;
    }
}

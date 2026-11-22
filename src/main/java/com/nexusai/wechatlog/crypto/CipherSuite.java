package com.nexusai.wechatlog.crypto;

/**
 * SQLCipher 的一组完整密码参数。
 *
 * <h2>为什么不能单独「试探 reserve」</h2>
 * 我最初的实现把 reserve 当成一个独立可试探的整数（候选 {@code 80/48/16/64/32}），
 * 单测立刻暴露了这是错的：
 * <pre>
 * reserve = IV(16) + hmacSize，再向上补齐到 16 的倍数
 * 若强行用 reserve=48 配 hmacSize=64，则 hmac 偏移 = 4096-48+16 = 4064，
 * 结束位置 4064+64 = 4128 > 4096 → 越界。
 * </pre>
 * 真正决定页布局的是 <b>HMAC 算法</b>，reserve 只是它的派生结果：
 * <table>
 *   <tr><th>版本</th><th>KDF</th><th>HMAC</th><th>hmacSize</th><th>reserve</th></tr>
 *   <tr><td>微信 3.x</td><td>PBKDF2-SHA1 64000</td><td>SHA1</td><td>20</td><td>48 (16+20→补齐)</td></tr>
 *   <tr><td>微信 4.x</td><td>PBKDF2-SHA512 256000</td><td>SHA512</td><td>64</td><td>80 (16+64)</td></tr>
 * </table>
 * 所以候选池应该是「套件」，而不是单独的数字。这也让 {@code verify} 的返回值得以
 * 携带「用哪个套件验证通过的」，而不是一个含义含糊的 reserve。
 */
public enum CipherSuite {

    /**
     * 微信 4.x（WCDB over SQLCipher 4）。
     *
     * <p>参数双源互证：chatlog {@code darwin/v4.go} 与 wcdb-key-tool 的 {@code RESERVE_SZ=80}
     * 完全一致。
     */
    V4_SHA512(
            "HmacSHA512",
            "PBKDF2WithHmacSHA512",
            64,
            80,
            256_000,
            "微信 4.x（SQLCipher4 / SHA512 / 256000 轮 / reserve 80）"),

    /**
     * 微信 3.x（老版本）。
     *
     * <p>chatlog {@code windows/v3.go}：{@code V3IterCount=64000, HmacSHA1Size=20,
     * reserve=(16+20)补齐到 48}。
     *
     * <p>⚠️ 3.x 的数据库在另一套目录（{@code Message/msg_0.db}），且目前主流客户端都是 4.x。
     * 保留此套件是为了让候选池在多账号/旧备份目录下也能工作，不代表已真机验证。
     */
    V3_SHA1(
            "HmacSHA1",
            "PBKDF2WithHmacSHA1",
            20,
            48,
            64_000,
            "微信 3.x（SQLCipher / SHA1 / 64000 轮 / reserve 48）");

    private final String hmacAlgorithm;
    private final String pbkdf2Algorithm;
    private final int hmacSize;
    private final int reserve;
    private final int pbkdf2Iterations;
    private final String description;

    CipherSuite(String hmacAlgorithm, String pbkdf2Algorithm,
                int hmacSize, int reserve, int pbkdf2Iterations, String description) {
        this.hmacAlgorithm = hmacAlgorithm;
        this.pbkdf2Algorithm = pbkdf2Algorithm;
        this.hmacSize = hmacSize;
        this.reserve = reserve;
        this.pbkdf2Iterations = pbkdf2Iterations;
        this.description = description;
    }

    public String hmacAlgorithm() {
        return hmacAlgorithm;
    }

    public String pbkdf2Algorithm() {
        return pbkdf2Algorithm;
    }

    public int hmacSize() {
        return hmacSize;
    }

    public int reserve() {
        return reserve;
    }

    public int pbkdf2Iterations() {
        return pbkdf2Iterations;
    }

    public String description() {
        return description;
    }

    /** 页尾 HMAC 的起始偏移（不含 salt 处理）。 */
    public int hmacOffset() {
        return SqlCipherParams.PAGE_SIZE - reserve + SqlCipherParams.IV_SIZE;
    }

    /** HMAC 覆盖的数据结束偏移（含 IV，不含 HMAC）。 */
    public int hmacDataEnd() {
        return hmacOffset();
    }

    /** 默认套件（当前主流：微信 4.x）。 */
    public static CipherSuite defaultSuite() {
        return V4_SHA512;
    }

    /** 校验顺序：先试主流版本，再退到老版本。 */
    public static CipherSuite[] candidates() {
        return new CipherSuite[]{V4_SHA512, V3_SHA1};
    }
}

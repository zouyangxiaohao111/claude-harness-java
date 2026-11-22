package com.nexusai.wechatlog.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一个「来源未知的 32 字节」变成「某个数据库确实可用的密钥」。
 *
 * <h2>为什么要这一层</h2>
 * 三个平台的取钥机制拿到的产物并不统一，而且与微信版本相关：
 * <table>
 *   <tr><th>抓取点</th><th>拿到的是</th><th>用法</th></tr>
 *   <tr><td>macOS {@code CCCryptorCreate}</td><td>raw encKey</td><td>直接解密</td></tr>
 *   <tr><td>macOS {@code CCKeyDerivationPBKDF}</td><td>passphrase</td><td>需 PBKDF2 256000 轮</td></tr>
 *   <tr><td>Windows {@code Config.Cipher} 扫描</td><td>两者都可能</td><td>未知</td></tr>
 *   <tr><td>Linux gdb 断点</td><td>passphrase</td><td>需 PBKDF2</td></tr>
 * </table>
 * 与其让每个平台自己判断「我拿到的是什么」，不如统一交给本类：
 * <b>候选池里的每个 32 字节，都分别按「当成 raw key」和「当成 passphrase」各试一次，
 * 用 HMAC 校验第 1 页来裁定真假。</b>
 *
 * <p>这样版本差异被吸收在密码学层面，平台实现只需老实把抓到的字节丢进来。
 *
 * <h2>性能（实测数字与优化）</h2>
 * PBKDF2-256000 单次在本机约 <b>670ms</b>。若候选 C 个、库 N 个，
 * 最坏成本 = C×N×套件数 次 256000 轮派生。实测 C=2, N=31, 套件 2 = 124 次 ≈ <b>83 秒</b>
 * —— 用户会以为「卡死了」。
 *
 * <p>因此本类做了三件事：
 * <ol>
 *   <li><b>raw key 优先</b>：它只需 2 轮 PBKDF2（微秒级），几乎零成本，先全试一遍；</li>
 *   <li><b>passphrase 阶段只试 4.x 套件</b>：3.x（SHA1/64000）只在 raw 与 4.x 全败后才降级尝试；
 *       实测微信 4.1.x 全是 4.x 参数，没必要每次都跑一遍 3.x；</li>
 *   <li><b>进度回调</b>：让调用方能显示「已试 x/y」，避免长时间无输出被误判为卡死。</li>
 * </ol>
 */
public final class KeyMatcher {

    private static final Logger log = LoggerFactory.getLogger(KeyMatcher.class);

    private KeyMatcher() {
    }

    /**
     * 进度回调（可选）。用于在长时间派生时给用户可见反馈。
     *
     * <p>实现方应<b>行缓冲</b>输出（如 System.err.println），否则终端看不到。
     */
    public interface ProgressListener {
        void onProgress(String stage, int done, int total, String detail);
    }

    /**
     * 一个数据库的密钥归属结果。
     *
     * @param databasePath 数据库绝对路径
     * @param encKey       可直接使用的 32 字节 AES 主密钥
     * @param suite        生效的密码套件
     * @param source       它是怎么来的（便于排障与给用户解释）
     */
    public record Match(Path databasePath, byte[] encKey, CipherSuite suite, Origin source) {
        @Override
        public String toString() {
            return "Match{" + databasePath.getFileName()
                    + ", suite=" + suite
                    + ", origin=" + source
                    + ", key=" + briefHex(encKey) + "}";
        }
    }

    /** 候选密钥的来源，用于排障。 */
    public enum Origin {
        /** 候选本身就是可用的 encKey。 */
        RAW_KEY,
        /** 候选是 passphrase，经 PBKDF2 派生后可用。 */
        PASSPHRASE
    }

    /**
     * 匹配单个数据库。
     *
     * <p>顺序：先当 raw key 试（成本极低），再当 passphrase 派生（成本高）。
     *
     * @param dbPath    数据库路径
     * @param page0     该库前 4096 字节
     * @param candidates 候选字节（长度非 32 的会被忽略）
     * @return 命中则返回 Match，否则 null
     */
    public static Match matchSingle(Path dbPath, byte[] page0, List<byte[]> candidates) {
        return matchSingle(dbPath, page0, candidates, null);
    }

    /**
     * 匹配单个库（带进度回调）。
     */
    public static Match matchSingle(Path dbPath, byte[] page0, List<byte[]> candidates,
                                    ProgressListener progress) {
        if (page0 == null || page0.length < SqlCipherParams.PAGE_SIZE) {
            return null;
        }
        if (SqlCipher4.isPlaintextSqlite(page0)) {
            // 明文库：不需要密钥
            return null;
        }

        byte[] salt = new byte[SqlCipherParams.SALT_SIZE];
        System.arraycopy(page0, 0, salt, 0, SqlCipherParams.SALT_SIZE);

        // 阶段 1：把候选当 raw key 试（只 2 轮 PBKDF2，微秒级）
        for (byte[] candidate : candidates) {
            if (candidate == null || candidate.length != SqlCipherParams.KEY_SIZE) {
                continue;
            }
            CipherSuite suite = SqlCipher4.verifyEncKey(candidate, page0, null);
            if (suite != null) {
                return new Match(dbPath, candidate, suite, Origin.RAW_KEY);
            }
        }

        // 阶段 2：把候选当 passphrase 派生后试。
        // 先只试主流套件（4.x），全败后再降级试 3.x —— 微信 4.1.x 都是 4.x 参数，
        // 每次多跑一遍 3.x 只会让用户多等一倍。
        for (CipherSuite suite : new CipherSuite[]{
                CipherSuite.V4_SHA512, CipherSuite.V3_SHA1}) {
            for (byte[] candidate : candidates) {
                if (candidate == null || candidate.length != SqlCipherParams.KEY_SIZE) {
                    continue;
                }
                if (progress != null) {
                    progress.onProgress("派生", 0, 0,
                            "PBKDF2 " + suite + "（约 0.7s/次）");
                }
                byte[] encKey = SqlCipher4.deriveEncKey(candidate, salt, suite);
                if (SqlCipher4.verifyEncKey(encKey, page0, suite) != null) {
                    return new Match(dbPath, encKey, suite, Origin.PASSPHRASE);
                }
            }
        }

        return null;
    }

    /**
     * 批量匹配：对每个库都用全部候选试一遍。
     *
     * @param databases  库路径列表
     * @param candidates 候选字节集合（内部会去重）
     * @return 按输入顺序排列的命中的 Match；未命中的库不出现在结果里
     */
    public static List<Match> matchAll(List<Path> databases, List<byte[]> candidates) {
        return matchAll(databases, candidates, null);
    }

    /**
     * 批量匹配（带进度回调）。
     *
     * <p><b>关键优化</b>：先对所有库做一轮「raw key」批量试探（便宜），
     * 只有剩下的库才进入昂贵的 passphrase 派生阶段。这样绝大多数情况下
     * （微信 4.1.x 的 {@code CCCryptorCreate} 断点抓到的就是 raw key）
     * 完全不会触发 256000 轮派生。
     */
    public static List<Match> matchAll(List<Path> databases, List<byte[]> candidates,
                                       ProgressListener progress) {
        List<byte[]> deduped = dedupe(candidates);
        List<Match> out = new ArrayList<>();

        // 先读出所有库的第 1 页（一次 IO，后面复用）
        Map<Path, byte[]> page0Map = new LinkedHashMap<>();
        for (Path db : databases) {
            byte[] p0 = readPage0(db);
            if (p0 != null) {
                page0Map.put(db, p0);
            }
        }

        // ---- 阶段 1：raw key 批量试探（几乎免费）----
        List<Path> unresolved = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Path, byte[]> e : page0Map.entrySet()) {
            i++;
            if (progress != null) {
                progress.onProgress("raw校验", i, page0Map.size(),
                        e.getKey().getFileName().toString());
            }
            Match m = matchAsRawKey(e.getKey(), e.getValue(), deduped);
            if (m != null) {
                out.add(m);
            } else {
                unresolved.add(e.getKey());
            }
        }
        log.debug("raw key 阶段: 命中 {} / 共 {}", out.size(), page0Map.size());

        if (unresolved.isEmpty()) {
            return out;
        }

        // ---- 阶段 2：对未命中的库做 passphrase 派生（昂贵，有进度）----
        log.info("{} 个库需要 passphrase 派生（每个约 0.7s×候选数），请耐心等待…",
                unresolved.size());
        int j = 0;
        for (Path db : unresolved) {
            j++;
            if (progress != null) {
                progress.onProgress("派生", j, unresolved.size(), db.getFileName().toString());
            }
            Match m = matchAsPassphrase(db, page0Map.get(db), deduped, progress);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** 只做 raw key 试探（零成本）。 */
    private static Match matchAsRawKey(Path dbPath, byte[] page0, List<byte[]> candidates) {
        if (SqlCipher4.isPlaintextSqlite(page0)) {
            return null;
        }
        for (byte[] candidate : candidates) {
            CipherSuite suite = SqlCipher4.verifyEncKey(candidate, page0, null);
            if (suite != null) {
                return new Match(dbPath, candidate, suite, Origin.RAW_KEY);
            }
        }
        return null;
    }

    /** 只做 passphrase 派生试探（先 4.x 再 3.x）。 */
    private static Match matchAsPassphrase(Path dbPath, byte[] page0, List<byte[]> candidates,
                                           ProgressListener progress) {
        if (page0 == null || page0.length < SqlCipherParams.PAGE_SIZE) {
            return null;
        }
        if (SqlCipher4.isPlaintextSqlite(page0)) {
            return null;
        }
        byte[] salt = new byte[SqlCipherParams.SALT_SIZE];
        System.arraycopy(page0, 0, salt, 0, SqlCipherParams.SALT_SIZE);

        for (CipherSuite suite : new CipherSuite[]{
                CipherSuite.V4_SHA512, CipherSuite.V3_SHA1}) {
            for (byte[] candidate : candidates) {
                byte[] encKey = SqlCipher4.deriveEncKey(candidate, salt, suite);
                if (SqlCipher4.verifyEncKey(encKey, page0, suite) != null) {
                    return new Match(dbPath, encKey, suite, Origin.PASSPHRASE);
                }
            }
        }
        return null;
    }

    /** 读库的第 1 页（4096 字节）；读不到返回 null。 */
    private static byte[] readPage0(Path db) {
        try {
            if (!Files.isRegularFile(db) || Files.size(db) < SqlCipherParams.PAGE_SIZE) {
                return null;
            }
            byte[] page0 = new byte[SqlCipherParams.PAGE_SIZE];
            try (var in = Files.newInputStream(db)) {
                if (in.readNBytes(page0, 0, SqlCipherParams.PAGE_SIZE)
                        < SqlCipherParams.PAGE_SIZE) {
                    return null;
                }
            }
            return page0;
        } catch (Exception e) {
            // 单个库读失败不能中断整批（可能正在被微信写入）
            return null;
        }
    }

    /** 按十六进制字符串去重，保持顺序。 */
    private static List<byte[]> dedupe(List<byte[]> candidates) {
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> out = new ArrayList<>();
        for (byte[] c : candidates) {
            if (c == null || c.length != SqlCipherParams.KEY_SIZE) {
                continue;
            }
            String hex = toHex(c);
            if (seen.add(hex)) {
                out.add(c);
            }
        }
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** 日志里只显示前 4 字节，避免把可用密钥整条写进日志。 */
    private static String briefHex(byte[] b) {
        if (b == null) {
            return "null";
        }
        String full = toHex(b);
        return full.substring(0, 8) + "…";
    }
}

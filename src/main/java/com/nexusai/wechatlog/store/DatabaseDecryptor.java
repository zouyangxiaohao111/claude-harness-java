package com.nexusai.wechatlog.store;

import com.nexusai.wechatlog.crypto.CipherSuite;
import com.nexusai.wechatlog.crypto.KeyMatcher;
import com.nexusai.wechatlog.crypto.SqlCipher4;
import com.nexusai.wechatlog.crypto.SqlCipherParams;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一个加密的微信数据库解密成明文 SQLite 文件。
 *
 * <h2>流程</h2>
 * <pre>
 * 1. 读主库全部页 → 逐页解密 → 得到页数组
 * 2. 若存在 -wal，把 WAL 里已 commit 的帧解密后覆盖进页数组
 * 3. 拼成连续字节 → 写临时文件 → 原子改名
 * 4. 用标准 sqlite3 JDBC 打开它查询
 * </pre>
 *
 * <h2>⚠️ 为什么要读成「页数组」而不是流式写</h2>
 * WAL 的帧是<b>按页号随机覆盖</b>的（本机实测 pgno 范围 1..22453，顺序跳跃）。
 * 流式顺序写无法处理，必须先有可随机寻址的页集合。内存代价：92MB 库约 92MB 堆，
 * 对单次同步可接受；若将来要处理超大库，改为「临时文件 + RandomAccessFile」即可，
 * 接口不变。
 *
 * <h2>⚠️ 不做的事（明确边界）</h2>
 * <ul>
 *   <li>不修改原始数据库 —— 全程只读；产物写到单独的 workDir。</li>
 *   <li>不做增量：每次都全量解。增量留到上层用「文件 mtime + WAL 长度」判断
 *       是否需要重解，避免把复杂度堆在这一层。</li>
 * </ul>
 */
public final class DatabaseDecryptor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDecryptor.class);

    private DatabaseDecryptor() {
    }

    /** 单库解密结果。 */
    public record DecryptResult(Path source, Path output, int pages, int walFrames,
                                CipherSuite suite, KeyMatcher.Origin origin) {
        @Override
        public String toString() {
            return "DecryptResult{" + source.getFileName() + " → " + output.getFileName()
                    + ", pages=" + pages + ", walFrames=" + walFrames
                    + ", suite=" + suite + ", origin=" + origin + "}";
        }
    }

    /**
     * 解密单个库到 {@code outputPath}。
     *
     * @param dbFile     加密的源库
     * @param outputPath 明文输出路径（父目录会自动创建）
     * @param encKey     32 字节 AES 主密钥
     * @param suite      密码套件
     * @return 结果统计
     * @throws IOException 读写失败
     */
    public static DecryptResult decrypt(Path dbFile, Path outputPath,
                                        byte[] encKey, CipherSuite suite) throws IOException {
        byte[] macKey = SqlCipher4.deriveMacKey(encKey, readSalt(dbFile), suite);

        // 1. 主库解密
        List<byte[]> pages = decryptAllPages(dbFile, encKey, macKey, suite);

        // 2. 合并 WAL（存在才做）
        int walFrames = 0;
        Path wal = WeChatDataLocator.walFileFor(dbFile);
        if (Files.isRegularFile(wal)) {
            try {
                byte[] walBytes = Files.readAllBytes(wal);
                WalMerger.Merged merged = WalMerger.merge(pages, walBytes, encKey, macKey, suite);
                pages = merged.pages();
                walFrames = merged.result().appliedFrames();
            } catch (Exception e) {
                // WAL 合并失败不应该让整个库报废：主库本身是完整可用的
                log.warn("WAL 合并失败，仅使用主库内容: {}", dbFile, e);
            }
        }

        // 3. 落盘（临时文件 + 原子改名，避免下游读到半成品）
        Files.createDirectories(outputPath.getParent());
        Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        Files.write(tmp, WalMerger.toBytes(pages));
        try {
            Files.move(tmp, outputPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持 ATOMIC_MOVE（如跨分区），退化为普通 move
            Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("解密完成: {} → {} ({} 页, WAL {} 帧)",
                dbFile.getFileName(), outputPath, pages.size(), walFrames);
        return new DecryptResult(dbFile, outputPath, pages.size(), walFrames, suite, null);
    }

    /**
     * 把一个账号下的所有加密库解密到 {@code workDir}，保留相对目录结构。
     *
     * @param accountDir 账号目录（如 {@code .../xwechat_files/wxid_xxx_hash}）
     * @param workDir    产物根目录
     * @param matches    {@link KeyMatcher} 给出的「库 → 密钥」归属
     * @return 成功解密的库列表
     */
    public static List<DecryptResult> decryptAccount(Path accountDir, Path workDir,
                                                     List<KeyMatcher.Match> matches) {
        List<DecryptResult> out = new ArrayList<>();
        for (KeyMatcher.Match m : matches) {
            try {
                Path rel = accountDir.relativize(m.databasePath());
                Path output = workDir.resolve(rel);
                DecryptResult r = decrypt(m.databasePath(), output, m.encKey(), m.suite());
                out.add(new DecryptResult(r.source(), r.output(), r.pages(), r.walFrames(),
                        r.suite(), m.source()));
            } catch (Exception e) {
                log.warn("解密失败，跳过: {}", m.databasePath(), e);
            }
        }
        return out;
    }

    // ==================================================================

    /** 读主库全部页并逐页解密。 */
    static List<byte[]> decryptAllPages(Path dbFile, byte[] encKey, byte[] macKey,
                                        CipherSuite suite) throws IOException {
        long size = Files.size(dbFile);
        long totalPages = size / SqlCipherParams.PAGE_SIZE;
        if (totalPages == 0) {
            return new ArrayList<>();
        }
        List<byte[]> pages = new ArrayList<>((int) Math.min(totalPages, 100_000));

        try (InputStream in = Files.newInputStream(dbFile)) {
            byte[] buf = new byte[SqlCipherParams.PAGE_SIZE];
            for (int i = 0; i < totalPages; i++) {
                int n = in.readNBytes(buf, 0, SqlCipherParams.PAGE_SIZE);
                if (n < SqlCipherParams.PAGE_SIZE) {
                    break;   // 尾部不足一页，SQLite 本来也会忽略
                }
                pages.add(SqlCipher4.decryptPage(encKey, macKey, buf, i, suite));
            }
        }
        return pages;
    }

    /** 读文件头 16 字节 salt。 */
    static byte[] readSalt(Path dbFile) throws IOException {
        byte[] salt = new byte[SqlCipherParams.SALT_SIZE];
        try (InputStream in = Files.newInputStream(dbFile)) {
            if (in.readNBytes(salt, 0, SqlCipherParams.SALT_SIZE) < SqlCipherParams.SALT_SIZE) {
                throw new IOException("文件不足 16 字节，无法读取 salt: " + dbFile);
            }
        }
        return salt;
    }
}

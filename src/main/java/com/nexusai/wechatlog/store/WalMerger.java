package com.nexusai.wechatlog.store;

import com.nexusai.wechatlog.crypto.CipherSuite;
import com.nexusai.wechatlog.crypto.SqlCipher4;
import com.nexusai.wechatlog.crypto.SqlCipherParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 SQLCipher 的 WAL（Write-Ahead Log）合并进已解密的主库。
 *
 * <h2>⚠️ 为什么必须做（两个上游实现都漏了）</h2>
 * 本机实测 {@code message_0.db-wal} 有 4MB 且存在。SQLite 的 WAL 模式把最近事务先写进
 * WAL 文件，之后才（可能）checkpoint 回主库。也就是说：
 * <ul>
 *   <li>{@code chatlog} 只解 {@code *.db}，不碰 {@code -wal}；</li>
 *   <li>{@code rmqg/wechat-mac-export} 同样只处理主库。</li>
 * </ul>
 * 两者的产物都会丢掉<b>最近一段时间的消息</b>。要做到「定时同步拿到最新消息」，
 * 这一层是必需的，不是可选的优化。
 *
 * <h2>WAL 格式（SQLite 官方定义）</h2>
 * <pre>
 * 整个 WAL 文件 = 32 字节头 + N 个「帧」
 *
 * 头 (32B):
 *   [0..4)   magic: 0x377f0682 或 0x377f0683（低位是校验和大端/小端标志）
 *   [4..8)   格式版本（3007000）
 *   [8..12)  page size
 *   [12..16) checkpoint 序号
 *   [16..20) salt-1
 *   [20..24) salt-2
 *   [24..32) 校验和
 *
 * 帧 (pageSize + 24):
 *   [0..4)   页号（本帧对应主库的第几页，1-based）
 *   [4..8)   提交后数据库大小（非 0 表示这是一次 commit 的最后一帧）
 *   [8..16)  salt-1, salt-2（必须与头一致，否则视为 WAL 被重置）
 *   [16..24) 校验和
 *   [24..)   页数据（这里才是加密的页内容）
 * </pre>
 *
 * <h2>⚠️ 关于「salt」的两个不同概念（我起初混淆过）</h2>
 * <ul>
 *   <li><b>加密 salt</b>：主库文件头前 16 字节，参与 PBKDF2 派生。每个库固定不变。</li>
 *   <li><b>WAL salt</b>：WAL 头 {@code [16..24)}，SQLite 用来检测「WAL 是否被重置」。
 *       每次 checkpoint 后重置时会自增。</li>
 * </ul>
 * 两者<b>不是同一个值</b>。本机实测：主库 salt {@code 1d96e1ea752fe644...}，
 * WAL 头 salt {@code 8de29e11 / 09412615}。所以「WAL salt 应等于主库 salt」是错误假设。
 * 正确的比较是：<b>帧头 salt 必须等于 WAL 头 salt</b>，否则该帧属于上一代 WAL。
 *
 * <h2>本实现的范围（明确声明，避免误以为很完整）</h2>
 * <ul>
 *   <li>✅ 只应用<b>已 commit</b> 的帧（遇到 commit 帧时把累积的帧落地）；
 *        未 commit 的尾部帧丢弃 —— 这是 SQLite 的正确语义，不是简化。</li>
 *   <li>✅ 帧头 salt 与 WAL 头不一致时视为「上一代残留」并停止读取。本机实测
 *        微信退出后留下的 WAL 就是这种状态：1018 个帧全是旧代，无一匹配头部
 *        ⇒ 结果正确地返回「WAL 中没有已提交的事务」，此时最新数据已在主库里。</li>
 *   <li>❌ <b>不校验 WAL 帧的校验和</b>。原因：校验和按 SQLite 语义算在<b>明文</b>页上，
 *        要校验就得先解密；而每帧的 SQLCipher HMAC 校验已经能发现密钥错误与数据损坏。
 *        功能上不缺失，只是少一道冗余检查。</li>
 * </ul>
 */
public final class WalMerger {

    private static final Logger log = LoggerFactory.getLogger(WalMerger.class);

    private static final int WAL_HEADER_SIZE = 32;
    private static final int FRAME_HEADER_SIZE = 24;
    private static final int WAL_MAGIC_LE = 0x377f0682;
    private static final int WAL_MAGIC_BE = 0x377f0683;

    private WalMerger() {
    }

    /** 合并结果。 */
    public record Result(int appliedFrames, int skippedFrames, String note) {
        public boolean changed() {
            return appliedFrames > 0;
        }
    }

    /**
     * 把 WAL 合并到主库的页数组上。
     *
     * @param mainPages  主库的全部页（每页 4096 字节，已按 WAL 顺序排列）
     * @param wal        整个 WAL 文件的字节
     * @param encKey     该库的 AES 主密钥
     * @param macKey     该库的 MAC key
     * @param suite      密码套件
     * @return 合并后的页数组（可能是新的更大数组）+ 统计
     */
    public static Merged merge(List<byte[]> mainPages, byte[] wal,
                               byte[] encKey, byte[] macKey, CipherSuite suite) {
        if (wal == null || wal.length < WAL_HEADER_SIZE) {
            return new Merged(mainPages, new Result(0, 0, "WAL 不存在或过短"));
        }

        ByteBuffer head = ByteBuffer.wrap(wal, 0, WAL_HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        int magic = head.getInt(0);
        if (magic != WAL_MAGIC_LE && magic != WAL_MAGIC_BE) {
            return new Merged(mainPages, new Result(0, 0, "WAL 魔数不匹配（不是 WAL 文件）"));
        }
        int pageSize = head.getInt(8);
        if (pageSize != SqlCipherParams.PAGE_SIZE) {
            return new Merged(mainPages, new Result(0, 0,
                    "WAL 页大小 " + pageSize + " 与预期 4096 不符"));
        }

        // 注意：这里<b>不</b>拿主库 salt 去比 WAL salt —— 两者是不同概念（见类注释）。
        // WAL salt 只用于「帧是否属于本代 WAL」的判定，与加密派生无关。

        int walSalt1 = head.getInt(16);
        int walSalt2 = head.getInt(20);

        int frameSize = pageSize + FRAME_HEADER_SIZE;
        int totalFrames = (wal.length - WAL_HEADER_SIZE) / frameSize;

        // 累积「已 commit 的帧」；遇到 commit 帧时才真正落地
        List<PendingFrame> pending = new ArrayList<>();
        List<PendingFrame> committed = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < totalFrames; i++) {
            int off = WAL_HEADER_SIZE + i * frameSize;

            // ⚠️ 不要写 ByteBuffer.wrap(wal, off, LEN).getInt(0)：
            // wrap(array, offset, length) 的 position 被设为 offset，但 getInt(0)
            // 的索引是**相对整个数组的绝对索引 0**，不是相对 off。
            // 必须显式用「绝对索引 off + k」，或先 slice()。
            // 我在这里踩过一次：所有帧的 salt 都读成 0，导致「每帧都不匹配」→ 全部跳过。
            long pgno = Integer.toUnsignedLong(readInt(wal, off));
            int dbSizeAfterCommit = readInt(wal, off + 4);
            int fSalt1 = readInt(wal, off + 8);
            int fSalt2 = readInt(wal, off + 12);

            if (fSalt1 != walSalt1 || fSalt2 != walSalt2) {
                // WAL 被重置（换了一轮），之前的帧作废
                log.debug("WAL 第 {} 帧 salt 不匹配，判定 WAL 已重置，停止读取", i);
                break;
            }
            if (pgno == 0 || pgno > Integer.MAX_VALUE) {
                skipped++;
                continue;
            }

            byte[] rawFramePage = new byte[pageSize];
            System.arraycopy(wal, off + FRAME_HEADER_SIZE, rawFramePage, 0, pageSize);
            pending.add(new PendingFrame((int) pgno, rawFramePage, dbSizeAfterCommit));

            if (dbSizeAfterCommit != 0) {
                // 这是一次事务的最后一帧 → 整批生效
                committed.addAll(pending);
                pending.clear();
            }
        }
        if (!pending.isEmpty()) {
            // 尾部未 commit 的帧按 SQLite 语义丢弃
            log.debug("丢弃 {} 个未 commit 的 WAL 帧", pending.size());
            skipped += pending.size();
        }

        if (committed.isEmpty()) {
            return new Merged(mainPages, new Result(0, skipped, "WAL 中没有已提交的事务"));
        }

        // 应用已提交的帧
        int maxPgno = mainPages.size();
        for (PendingFrame f : committed) {
            maxPgno = Math.max(maxPgno, f.pgno);
        }
        List<byte[]> out = new ArrayList<>(mainPages);
        while (out.size() < maxPgno) {
            out.add(new byte[pageSize]);
        }

        int applied = 0;
        // 以最后一帧的 dbSizeAfterCommit 为准（SQLite 语义：截断/扩展到最后一次 commit 的大小）
        int finalDbPages = 0;
        for (PendingFrame f : committed) {
            if (f.dbSizeAfterCommit != 0) {
                finalDbPages = f.dbSizeAfterCommit;
            }
        }

        Set<Integer> touched = new HashSet<>();
        for (PendingFrame f : committed) {
            int idx = f.pgno - 1;
            if (idx < 0) {
                skipped++;
                continue;
            }
            while (out.size() <= idx) {
                out.add(new byte[pageSize]);
            }
            try {
                byte[] plain = SqlCipher4.decryptPage(
                        encKey, macKey, f.page, idx, suite);
                out.set(idx, plain);
                touched.add(f.pgno);
                applied++;
            } catch (Exception e) {
                // 单帧解不出来不应该让整个库报废（可能是 WAL 尾部被截断）
                log.debug("WAL 第 {} 页解密失败，跳过: {}", f.pgno, e.getMessage());
                skipped++;
            }
        }

        // 按最后一次 commit 的数据库页数截断
        if (finalDbPages > 0 && finalDbPages < out.size()) {
            out = new ArrayList<>(out.subList(0, finalDbPages));
        }

        log.info("WAL 合并完成: 应用 {} 帧，跳过 {}，覆盖 {} 个页，最终 {} 页",
                applied, skipped, touched.size(), out.size());
        return new Merged(out, new Result(applied, skipped,
                "应用 " + applied + " 帧 / 覆盖 " + touched.size() + " 页"));
    }

    /** 合并后的页集合。 */
    public record Merged(List<byte[]> pages, Result result) {
    }

    private record PendingFrame(int pgno, byte[] page, int dbSizeAfterCommit) {
    }

    /**
     * 从字节数组的绝对偏移处读一个大端 32 位整数。
     *
     * <p>存在的原因见{@link #merge} 里的注释：{@code ByteBuffer.wrap(a, off, len)}
     * 的 {@code getInt(0)} 取的是数组索引 0，不是 off —— 用它解析 WAL 帧会全部读错。
     */
    private static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    /**
     * 把页数组拼回连续字节（用于写文件或喂给 sqlite3）。
     */
    public static byte[] toBytes(List<byte[]> pages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pages.size() * SqlCipherParams.PAGE_SIZE);
        for (byte[] p : pages) {
            out.write(p, 0, SqlCipherParams.PAGE_SIZE);
        }
        return out.toByteArray();
    }
}

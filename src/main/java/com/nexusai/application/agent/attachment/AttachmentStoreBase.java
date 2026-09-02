package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 附件缓存存储抽象基类 · [attachments-v2 重构] 模板方法下沉三 store（Image/Pdf/Media）公共基础设施。
 *
 * <p><b>WHY（2026-08-25 attachments-v2）</b>：{@link ImageAttachmentStore}（对齐 CC imageStore.ts）
 * 与 {@link PdfAttachmentStore}（对齐 CC 路径通道）高度同构——目录/路径/自增 id/FIFO 逐出
 * （200 上限）/写盘（FileChannel force 刷盘）/会话清理/会话解析全部重复。抽取为模板基类，
 * 子类经三个钩子（{@link #cacheDirName} / {@link #extensionOf} / {@link #newSessionMap}）差异化；
 * <b>公共 API 签名一律不变</b>（调用方零改动，回归可控）。
 *
 * <p><b>行为契约（对齐 CC imageStore.ts，逐条保持）</b>：
 * <ul>
 *   <li>目录 {@code {configHome}/{cacheDirName}/{sessionId}/{id}.{ext}}</li>
 *   <li>内存索引会话分桶，200 上限按会话 FIFO 逐出（仅删内存索引，不动磁盘文件，
 *       CC imageStore.ts:115-124）</li>
 *   <li>id 每会话独立自增从 1（CC {@code PastedContent.id} 顺序数字 id，config.ts:55）</li>
 *   <li>写盘 {@code FileChannel.force} 刷盘（data-sync 语义，防「上传成功但字节未落盘」静默丢数据）</li>
 *   <li>失败返回 null 不抛异常（CC storeImage catch → null，imageStore.ts:75-78）</li>
 *   <li>会话解析 显式 sessionId → MDC（{@link RequestContext#sessionId()}）→ 'unknown' 兜底</li>
 * </ul>
 *
 * <p>非 Spring 组件（抽象基类不注册）；子类 {@code @Component} 继承。锁为实例级
 * （每 store 实例独立），等价原各 store 自身锁。
 *
 * @param <T> 存储记录类型（StoredImage / StoredPdf / StoredMedia）
 */
public abstract class AttachmentStoreBase<T> {

    protected static final Logger log = LoggerFactory.getLogger(AttachmentStoreBase.class);

    /** 每会话内存索引上限 · 对齐 CC imageStore.ts:10 {@code MAX_STORED_IMAGE_PATHS=200}。 */
    protected static final int MAX_STORED_PATHS = 200;

    /** 无会话兜底桶名（cron/后台无 MDC 时，等价 CC STATE.sessionId 恒存在）。 */
    protected static final String UNKNOWN_SESSION = "unknown";

    /** 锁：保护 sessions 与 nextIds 的复合操作（evict + put、取桶 + 增删）。 */
    protected final Object lock = new Object();

    /** 会话 → 内存索引（id → 存储记录）。每会话一桶，桶为插入序 LinkedHashMap（FIFO）。 */
    private final Map<String, Map<Long, T>> sessions = new HashMap<>();

    /** 会话 → 下一个自增 id（CC PastedContent.id 顺序数字 id，config.ts:55）。 */
    private final Map<String, AtomicLong> nextIds = new HashMap<>();

    // ════════════════════════════════════════════════════════════════════
    // 子类差异点（模板方法钩子）
    // ════════════════════════════════════════════════════════════════════

    /** 缓存根目录名 · 子类指定（'image-cache' / 'pdf-cache' / 'media-cache'）。 */
    protected abstract String cacheDirName();

    /** 由 mediaType 推扩展名 · 子类指定（PDF 恒 'pdf'；image/media 取 {@code mediaType.split('/')[1]}）。 */
    protected abstract String extensionOf(String mediaType);

    /** 新建会话桶 · 子类指定（默认 FIFO 插入序 LinkedHashMap；需 LRU 改 accessOrder=true，见各子类 javadoc）。 */
    protected abstract Map<Long, T> newSessionMap();

    // ════════════════════════════════════════════════════════════════════
    // 公共基础设施（子类 store/get 方法内部调用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 存储目录 · {@code {configHome}/{cacheDirName}/{sessionId}}。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     */
    protected Path storeDir(String sessionId) {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), cacheDirName(), resolveSessionId(sessionId));
    }

    /**
     * 确定性文件路径 · {@code {storeDir}/{id}.{ext}}。不依赖内存索引，即使逐出 / 重启仍可还原
     * （CC 路径通道语义：文件在盘，路径可重建）。
     *
     * @param sessionId 会话 id
     * @param id        附件 id（contentId）
     * @param mediaType MIME 类型（经 {@link #extensionOf} 推扩展名）
     */
    protected String buildPath(String sessionId, long id, String mediaType) {
        return storeDir(sessionId).resolve(id + "." + extensionOf(mediaType)).toString();
    }

    /** 取会话自增 id 并递增 · CC PastedContent.id「Sequential numeric ID」（config.ts:55），每会话从 1 递增。 */
    protected long nextId(String sessionId) {
        synchronized (lock) {
            return nextIds.computeIfAbsent(sessionId, k -> new AtomicLong(1)).getAndIncrement();
        }
    }

    /** 读内存索引记录 · 需内存命中；未命中返回 null（被逐出 / 重启后未命中，同 CC 路径缓存语义）。 */
    protected T get(String sessionId, long id) {
        String sid = resolveSessionId(sessionId);
        synchronized (lock) {
            Map<Long, T> bucket = sessions.get(sid);
            return bucket == null ? null : bucket.get(id);
        }
    }

    /**
     * 登记内存索引 · 逐出 + 登记必须同一临界区（否则并发 put 可突破 200 上限：
     * evict 检查后、put 前被他人插入）。
     */
    protected void put(String sessionId, long id, T record) {
        String sid = resolveSessionId(sessionId);
        synchronized (lock) {
            evictIfAtCapLocked(sid);
            sessions.computeIfAbsent(sid, k -> newSessionMap()).put(id, record);
        }
    }

    /**
     * 逐出最早插入的条目至上限内（调用方须持有 {@link #lock}）· 对齐 CC imageStore.ts:115-124
     * evictOldestIfAtCap（FIFO，删最早插入键）。仅删内存索引，不删磁盘文件。
     */
    protected void evictIfAtCapLocked(String sessionId) {
        Map<Long, T> bucket = sessions.get(sessionId);
        if (bucket == null) {
            return;
        }
        while (bucket.size() >= MAX_STORED_PATHS) {
            Long oldest = bucket.keySet().iterator().next();
            bucket.remove(oldest);
            if (log.isDebugEnabled()) {
                log.debug("附件索引达到上限逐出最早条目：session={} id={}（FIFO，仅内存索引，磁盘文件保留）",
                        sessionId, oldest);
            }
        }
    }

    /**
     * 流式写盘 + force 刷盘 · 对齐 ImageAttachmentStore writeBase64 的 FileChannel 语义
     * （{@code FileChannel.force} 防「上传成功但字节未落盘」）。写完后核对实际字节数与声明一致，
     * 不一致 warn（fail loud，不静默）。
     *
     * @param path 目标文件路径
     * @param in   输入流（调用方负责关闭；本方法内读取）
     * @param size 声明大小（字节，上传校验已算，供写盘后核对）
     */
    protected void writeStream(String path, InputStream in, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(Path.of(path),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] chunk = new byte[8192];
            long written = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                ByteBuffer buf = ByteBuffer.wrap(chunk, 0, n);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                written += n;
            }
            ch.force(true);
            if (written != size) {
                log.warn("附件写入字节数与声明不一致：声明={} 实际={} path={}", size, written, path);
            }
        }
    }

    /** 清空单会话内存桶（不动磁盘）· 供子类「清内存不动盘」API 与 {@link #cleanupSession} 复用。 */
    protected void clearSessionBucket(String sessionId) {
        String sid = resolveSessionId(sessionId);
        synchronized (lock) {
            sessions.remove(sid);
            nextIds.remove(sid);
        }
    }

    /** 清理单会话缓存（会话结束/删除时调用）· 清内存桶 + 删除 {@code {cacheDir}/{sessionId}/} 目录。 */
    protected void cleanupSession(String sessionId) {
        clearSessionBucket(sessionId);
        Path dir = storeDir(sessionId);
        try {
            if (Files.exists(dir)) {
                deleteRecursively(dir);
                if (log.isDebugEnabled()) {
                    log.debug("已清理会话附件缓存目录：{}", dir);
                }
            }
        } catch (Exception e) {
            log.warn("清理会话附件缓存目录失败：{} 原因={}", dir, e.getMessage());
        }
    }

    /** 清空全部会话内存索引（不动磁盘）· 等价 CC /clear caches（caches.ts:87）。 */
    protected void clearAll() {
        synchronized (lock) {
            sessions.clear();
            nextIds.clear();
        }
        if (log.isDebugEnabled()) {
            log.debug("已清空全部会话附件内存索引");
        }
    }

    /**
     * 会话解析：显式参数 → MDC（{@link RequestContext#sessionId()}）→ 'unknown' 兜底。
     * CC 恒有会话（STATE.sessionId），Java 后端 cron/后台线程可能无 MDC，归入 'unknown' 桶。
     */
    protected String resolveSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        String fromMdc = RequestContext.sessionId();
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return UNKNOWN_SESSION;
    }

    /** 递归删除文件/目录 · 等价 CC rm(recursive, force)（imageStore.ts:149）。不跟随符号链接。 */
    protected void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : sorted) {
                Files.deleteIfExists(p);
            }
        }
    }
}

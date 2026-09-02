package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.RequestContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 图片附件缓存存储 · 对齐 CC {@code utils/imageStore.ts}（imageStore.ts:1-167 全量）。
 *
 * <p>CC 行为：{@code image-cache/<sessionId>/<id>.<ext>} 落盘 + 进程级内存 Map（id→path）+
 * 200 上限逐出 + 会话孤儿目录清理。[attachments-v2 重构] 本类继承 {@link AttachmentStoreBase}
 * （公共基础设施下沉基类），保留全部公共 API 签名（调用方零改动）与 A4 专属逻辑。
 *
 * <h2>⚠️ 200 上限语义（FIFO，非 LRU）</h2>
 * <p>CC 实际源码 {@code evictOldestIfAtCap}（imageStore.ts:115-124）遍历 JS Map（迭代序=插入序）
 * 删<b>最早插入</b>的键，且 {@code getStoredImagePath}（imageStore.ts:104-106）只读不重排 —— 即
 * <b>FIFO（先入先出）</b>，非 LRU。基类 {@code newSessionMap} 用插入序 LinkedHashMap（accessOrder=false）
 * + FIFO 逐出，严格复刻 CC 语义。若要改 LRU 仅需把 {@link #newSessionMap} 的 accessOrder 改 true
 * 并保留访问命中重排。
 *
 * <h2>会话隔离（CC 单会话进程 → Java 多会话后端）</h2>
 * <p>基类按 sessionId 分桶（{@code Map<sessionId, Map<id, StoredImage>>}），200 上限按会话独立计数
 * —— 等价 CC「每进程=每会话」语义。会话清理同时清内存桶与磁盘目录。
 *
 * <h2>落盘细节</h2>
 * <ul>
 *   <li>目录：{@code {configHome}/image-cache/{sessionId}/}，configHome = {@link NexusaiPaths#getAppConfigHomeDir()}（决策 D1 自有根）</li>
 *   <li>文件名：{@code <id>.<ext>}，ext = {@code mediaType.split('/')[1] || 'png'}（imageStore.ts:33-36）</li>
 *   <li>写入：base64 解码后写盘，{@code FileChannel.force(false)} 等价 CC {@code fh.datasync()}（imageStore.ts:67）</li>
 *   <li>失败：对齐 CC storeImage 返回 {@code null}（imageStore.ts:75-78），不抛异常</li>
 * </ul>
 *
 * <p><b>Java 侧新增读取 API</b>（CC 只有路径缓存，无读取）：
 * {@link #getBase64} 读文件返回 base64 + mediaType，供图片直接发送（Anthropic {@code image}
 * content block）与多模态工具读内容使用。
 */
@Component
public final class ImageAttachmentStore extends AttachmentStoreBase<ImageAttachmentStore.StoredImage> {

    /** 图片缓存根目录名 · CC original: {@code IMAGE_STORE_DIR = 'image-cache'}（imageStore.ts:9） */
    public static final String IMAGE_STORE_DIR = "image-cache";

    /** 每会话图片缓存上限 · CC original: {@code MAX_STORED_IMAGE_PATHS = 200}（imageStore.ts:10） */
    public static final int MAX_STORED_IMAGE_PATHS = 200;

    /**
     * 待注入本次 prompt 的图片（sessionId → 有序 PastedImage 列表）· A4 图片注入契约。
     *
     * <p>对齐 CC：用户粘贴图片 → AppState.pastedContents → prompt submit 时
     * {@code buildImageContentBlocks(pastedContents)} 注入主 user 消息 content 数组
     * （attachments.ts:1062-1071）。Java 端发送侧（A1）落盘缓存后把本 prompt 的图片
     * 经 {@link #registerPendingPromptImages} 登记，主循环（LlmAgentLoop A4）在首个 user 消息
     * 构造时 {@link #drainPendingPromptImages} 消费并清除 —— 只注入一次，跨 turn 不残留。
     * 与 CC 单进程 AppState 语义等价：Java 多会话后端按 sessionId 分桶。
     */
    private final Map<String, List<PastedImage>> pendingPromptImages = new java.util.HashMap<>();

    /**
     * 单条图片内容 · 对齐 CC {@code PastedContent}（config.ts:54-64）中 type='image' 的字段子集：
     * {@code id}（顺序数字 id）、{@code content}（base64 字符串）、{@code mediaType?}（e.g. 'image/png'）。
     *
     * @param id        顺序数字 id · CC original: {@code PastedContent.id}（config.ts:55）
     * @param base64    base64 编码的图片内容 · CC original: {@code PastedContent.content}（config.ts:57）
     * @param mediaType MIME 类型，null → 落盘时按 'image/png' 兜底 · CC original: {@code PastedContent.mediaType?}（config.ts:58）
     */
    public record PastedImage(long id, String base64, String mediaType) {}

    /**
     * 落盘结果 · 对齐 CC {@code storeImage} 返回值（imageStore.ts:73-74 返回 path，Java 附带 id/mediaType 超集）。
     *
     * @param id        图片 id
     * @param path      磁盘绝对路径 {@code {configHome}/image-cache/{sessionId}/{id}.{ext}}
     * @param mediaType MIME 类型（落盘时实际使用的值，null 已被兜底为 image/png）
     */
    public record StoredImage(long id, String path, String mediaType) {}

    /**
     * 读取结果 · Java 新增读取 API（CC 无对应，CC 仅缓存路径）。
     *
     * @param mediaType MIME 类型（用于拼 data URL / Anthropic image block media_type）
     * @param base64    文件内容的 base64 编码
     */
    public record Base64Content(String mediaType, String base64) {}

    // ════════════════════════════════════════════════════════════════════
    // 模板方法钩子（子类差异点）
    // ════════════════════════════════════════════════════════════════════

    /** 图片缓存目录名 · CC original: {@code IMAGE_STORE_DIR='image-cache'}（imageStore.ts:9）。 */
    @Override
    protected String cacheDirName() {
        return IMAGE_STORE_DIR;
    }

    /**
     * 由 mediaType 推扩展名 · CC imageStore.ts:34 {@code mediaType.split('/')[1] || 'png'}。
     * 注：CC 对 {@code image/svg+xml} 产出 {@code svg+xml} 扩展名，本类同样不特殊化（行为对齐）。
     */
    @Override
    protected String extensionOf(String mediaType) {
        if (mediaType == null) {
            return "png";
        }
        int slash = mediaType.indexOf('/');
        if (slash < 0 || slash == mediaType.length() - 1) {
            return "png";
        }
        return mediaType.substring(slash + 1);
    }

    /** 新建会话桶 · FIFO 插入序（CC imageStore.ts FIFO 语义；改 accessOrder=true 变 LRU）。 */
    @Override
    protected Map<Long, StoredImage> newSessionMap() {
        return new LinkedHashMap<>(256, 0.75f, false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 目录与路径（对齐 CC imageStore.ts:18-36）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 图片缓存目录 · CC original: {@code getImageStoreDir}（imageStore.ts:18-20）
     * {@code join(getClaudeConfigHomeDir(), 'image-cache', getSessionId())}；Java 写入基址
     * 经 {@link NexusaiPaths#getAppConfigHomeDir()}（决策 D1 自有根，见基类 {@code storeDir}）。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @return {@code {nexusaiConfigHome}/image-cache/{sessionId}}
     */
    public Path getImageStoreDir(String sessionId) {
        return storeDir(sessionId);
    }

    /**
     * 图片文件路径 · CC original: {@code getImagePath}（imageStore.ts:33-36）
     * {@code extension = mediaType.split('/')[1] || 'png'; join(getImageStoreDir(), `${id}.${extension}`)}。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @param mediaType MIME 类型；null / 无 '/' → 扩展名 'png' 兜底
     * @return {@code {storeDir}/{id}.{ext}}
     */
    public String getImagePath(String sessionId, long id, String mediaType) {
        return buildPath(sessionId, id, mediaType);
    }

    // ════════════════════════════════════════════════════════════════════
    // 落盘（对齐 CC imageStore.ts:54-99）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 落盘单张图片（自动分配 id）· 等价 CC {@code storeImage}（imageStore.ts:54-79）。
     *
     * @param base64    base64 图片内容
     * @param mediaType MIME 类型（null → image/png 兜底）
     * @return 落盘结果；失败返回 null
     */
    public StoredImage store(String base64, String mediaType) {
        return store(resolveSessionId(null), base64, mediaType);
    }

    /**
     * 落盘单张图片（自动分配 id，显式会话）· {@link #store(String, String)} 的显式 sessionId 重载。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param base64    base64 图片内容
     * @param mediaType MIME 类型
     * @return 落盘结果；失败返回 null
     */
    public StoredImage store(String sessionId, String base64, String mediaType) {
        String sid = resolveSessionId(sessionId);
        long id = nextId(sid);
        return storeWithId(sid, id, base64, mediaType);
    }

    /**
     * 落盘单张图片（流式输入）· [attachments-v2 扩展] 与 {@link MediaAttachmentStore#store} 同签名的
     * InputStream 重载：读全部字节 → base64 → 复用 {@link #store(String, String, String)} 落盘。
     *
     * <p>WHY：图片上传端点需与媒体/PDF 上传入口一致以流式输入落盘，而本类存储模型为 base64 写盘
     * （对齐 CC imageStore.ts:64-68 {@code fh.writeFile(...,{encoding:'base64'})}），故先读全字节转
     * base64 再走既有 base64 落盘路径，不引入第二条写盘通道（行为对齐，接口同构）。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param in        上传文件输入流（本方法内读取全部字节；调用方负责关闭）
     * @param size      声明大小（字节）；-1 表示未知，≥0 时读后核对，不一致 warn（fail loud，不静默）
     * @param filename  客户端原始文件名（仅日志使用；StoredImage 无此字段）
     * @param mediaType MIME 类型（null → image/png 兜底）
     * @return 落盘结果；失败返回 null
     */
    public StoredImage store(String sessionId, InputStream in, long size, String filename, String mediaType) {
        String sid = resolveSessionId(sessionId);
        try {
            byte[] bytes = in.readAllBytes();
            if (size >= 0 && bytes.length != size) {
                log.warn("图片流字节数与声明不一致：声明={} 实际={} session={}", size, bytes.length, sid);
            }
            String base64 = Base64.getEncoder().encodeToString(bytes);
            if (log.isDebugEnabled()) {
                log.debug("图片流式落盘：session={} filename={} mediaType={} 字节数={}（读全字节转 base64 → 复用既有 store）",
                        sid, filename, mediaType, bytes.length);
            }
            return store(sid, base64, mediaType);
        } catch (Exception e) {
            log.warn("图片流式落盘失败：session={} filename={} mediaType={} 原因={}", sid, filename, mediaType, e.getMessage());
            return null;
        }
    }

    /**
     * 判断 base64 图片是否超限 · [attachments-v2 扩展] 对齐 CC {@code API_IMAGE_MAX_BASE64_SIZE}
     * （Open-ClaudeCode/src/constants/apiLimits.ts:19）。
     *
     * <p>估算原始字节 {@code length * 3 / 4}（base64 编码放大 4/3），超过
     * {@link MediaLimitConstants#API_IMAGE_MAX_BASE64_SIZE}（5MB）→ true。上传/发送前门控用。
     *
     * @param base64 base64 图片内容；null → false
     * @return true = 估算原始字节 &gt; 5MB
     */
    public boolean isOversize(String base64) {
        if (base64 == null) {
            return false;
        }
        long estimatedRawBytes = (long) base64.length() * 3 / 4;
        return estimatedRawBytes > MediaLimitConstants.API_IMAGE_MAX_BASE64_SIZE;
    }

    /**
     * 落盘单张图片（显式 id）· 对齐 CC {@code storeImage}（imageStore.ts:54-79）。
     * 写入 base64 解码字节到 {@code {storeDir}/{id}.{ext}}，data-sync，随后逐出 + 登记内存索引。
     * 失败不抛出，返回 null（CC imageStore.ts:75-78 {@code catch { logForDebugging; return null }}）。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param id        图片 id（CC PastedContent.id）
     * @param base64    base64 图片内容
     * @param mediaType MIME 类型
     * @return 落盘结果；失败返回 null
     */
    public StoredImage storeWithId(String sessionId, long id, String base64, String mediaType) {
        String sid = resolveSessionId(sessionId);
        String resolvedMediaType = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
        try {
            Path dir = getImageStoreDir(sid);
            Files.createDirectories(dir);
            String path = getImagePath(sid, id, resolvedMediaType);
            writeBase64(path, base64);
            StoredImage stored = new StoredImage(id, path, resolvedMediaType);
            put(sid, id, stored);
            if (log.isDebugEnabled()) {
                log.debug("图片落盘成功：session={} id={} mediaType={} path={}", sid, id, resolvedMediaType, path);
            }
            return stored;
        } catch (Exception e) {
            log.warn("图片落盘失败：session={} id={} mediaType={} 原因={}", sid, id, resolvedMediaType, e.getMessage());
            return null;
        }
    }

    /**
     * 批量落盘 · 对齐 CC {@code storeImages}（imageStore.ts:84-99）：仅收集 type='image' 成功项。
     *
     * @param sessionId 会话 id
     * @param images    图片列表（本类 {@link PastedImage} 已限定为 image 类型）
     * @return id → 磁盘路径 的映射（仅含落盘成功的项）
     */
    public Map<Long, String> storeImages(String sessionId, List<PastedImage> images) {
        String sid = resolveSessionId(sessionId);
        Map<Long, String> pathMap = new LinkedHashMap<>();
        if (images == null) {
            return pathMap;
        }
        for (PastedImage img : images) {
            if (img == null) {
                continue;
            }
            StoredImage stored = storeWithId(sid, img.id(), img.base64(), img.mediaType());
            if (stored != null) {
                pathMap.put(stored.id(), stored.path());
            }
        }
        return pathMap;
    }

    /**
     * base64 写盘 + data-sync · 等价 CC imageStore.ts:64-68
     * {@code open(imagePath,'w',0o600); fh.writeFile(content,{encoding:'base64'}); fh.datasync(); fh.close()}。
     * 0o600 权限为 POSIX 语义，Windows 后端无 ACL 对等，略过。
     */
    private void writeBase64(String path, String base64) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            // CC fh.writeFile 对非法 base64 抛错并被 storeImage catch → null；Java 解码失败同样归入失败路径
            throw new IOException("非法 base64 内容: " + e.getMessage(), e);
        }
        Path p = Path.of(path);
        try (FileChannel ch = FileChannel.open(
                p, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(false); // 对齐 fh.datasync()：仅刷文件数据，不强制元数据
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // A4 待注入 prompt 图片（Java 新增 · CC 等价 AppState.pastedContents → buildImageContentBlocks）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 登记待注入图片 · A4 图片注入契约（发送侧 A1 调用）。
     *
     * <p>对齐 CC：prompt submit 时读取 AppState.pastedContents 构建 image content blocks
     * （attachments.ts:1062-1071）。发送侧（A1）把本次用户消息附带的图片落盘缓存后，
     * 经本方法登记为「待注入」，由 LlmAgentLoop A4 在首个 user 消息构造时消费。
     * 幂等追加：同会话可多次登记（多 prompt 排队），drain 一次性取走并清空。
     *
     * @param sessionId 会话 id（null/blank → MDC → 'unknown' 兜底）
     * @param images    本 prompt 附带的图片列表（含 id/base64/mediaType）；null/空 → no-op
     */
    public void registerPendingPromptImages(String sessionId, List<PastedImage> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        String sid = canonicalSessionId(sessionId);
        synchronized (lock) {
            pendingPromptImages.computeIfAbsent(sid, k -> new ArrayList<>()).addAll(images);
        }
        if (log.isDebugEnabled()) {
            log.debug("登记待注入 prompt 图片：session={} 图片数={}（A4 主 user 消息图片注入，CC attachments.ts:1062-1071）",
                    sid, images.size());
        }
    }

    /**
     * 取走并清空本会话待注入图片 · A4 消费侧（LlmAgentLoop 主 user 消息构造）。
     *
     * <p>只消费一次：首个 user 消息构造后 registry 清空，后续 turn 不重复注入
     * （对齐 CC：pastedContents 随 prompt submit 一次性构建 image blocks）。
     *
     * @param sessionId 会话 id
     * @return 待注入图片列表（原登记顺序）；无 → 空列表（不返回 null）
     */
    public List<PastedImage> drainPendingPromptImages(String sessionId) {
        String sid = canonicalSessionId(sessionId);
        synchronized (lock) {
            List<PastedImage> drained = pendingPromptImages.remove(sid);
            if (drained != null && !drained.isEmpty() && log.isDebugEnabled()) {
                log.debug("drain 待注入 prompt 图片：session={} 图片数={}（A4 主 user 消息消费，后续 turn 不重复注入）",
                        sid, drained.size());
            }
            return drained == null ? List.of() : drained;
        }
    }

    /**
     * 查询待注入图片数（不消费）· 供路由判定用（无图片 → 纯文本路径，A4）。
     *
     * @param sessionId 会话 id
     * @return 待注入图片数（0 = 无）
     */
    public int pendingPromptImageCount(String sessionId) {
        String sid = canonicalSessionId(sessionId);
        synchronized (lock) {
            List<PastedImage> images = pendingPromptImages.get(sid);
            return images == null ? 0 : images.size();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 快速路径缓存（对齐 CC imageStore.ts:41-49）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 仅缓存路径，不做文件 I/O · 对齐 CC {@code cacheImagePath}（imageStore.ts:41-49）：
     * 权限弹窗等场景文件已存在于源路径，无需复制，直接登记内存缓存供模型引用。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @param mediaType MIME 类型（null → image/png 兜底）
     * @return 缓存的磁盘路径
     */
    public String cacheImagePath(String sessionId, long id, String mediaType) {
        String sid = resolveSessionId(sessionId);
        String resolvedMediaType = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
        String path = getImagePath(sid, id, resolvedMediaType);
        put(sid, id, new StoredImage(id, path, resolvedMediaType));
        if (log.isDebugEnabled()) {
            log.debug("图片路径已登记缓存（无 I/O）：session={} id={} path={}", sid, id, path);
        }
        return path;
    }

    // ════════════════════════════════════════════════════════════════════
    // 读取（CC 仅 getStoredImagePath；getBase64/getDataUrl 为 Java 新增读取 API）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取图片磁盘路径 · 对齐 CC {@code getStoredImagePath}（imageStore.ts:104-106）
     * {@code storedImagePaths.get(imageId) ?? null}。仅命中内存缓存时返回；被 200 上限逐出的 id 返回 null
     * （CC 亦如此——文件仍在磁盘但路径已丢失）。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @return 磁盘路径；未命中返回 null
     */
    public String getStoredImagePath(String sessionId, long id) {
        StoredImage stored = get(sessionId, id);
        return stored == null ? null : stored.path();
    }

    /**
     * 读取内存缓存记录（含 mediaType）· Java 读取 API。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @return 存储记录；未命中返回 null
     */
    public StoredImage get(String sessionId, long id) {
        return super.get(sessionId, id);
    }

    /**
     * 读取图片文件 → base64 + mediaType · Java 读取 API（CC 无对应）。
     * 供图片直接发送（Anthropic {@code image} content block）与多模态工具读内容。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @return base64 + mediaType；未命中 / 读失败返回 null
     */
    public Base64Content getBase64(String sessionId, long id) {
        String sid = resolveSessionId(sessionId);
        StoredImage stored = get(sid, id);
        if (stored == null) {
            if (log.isDebugEnabled()) {
                log.debug("图片缓存未命中：session={} id={}", sid, id);
            }
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(stored.path()));
            String base64 = Base64.getEncoder().encodeToString(bytes);
            if (log.isDebugEnabled()) {
                log.debug("图片读取成功：session={} id={} mediaType={} 字节数={}", sid, id, stored.mediaType(), bytes.length);
            }
            return new Base64Content(stored.mediaType(), base64);
        } catch (Exception e) {
            log.warn("图片读取失败：session={} id={} path={} 原因={}", sid, id, stored.path(), e.getMessage());
            return null;
        }
    }

    /**
     * 读取图片 → base64 + mediaType，内存未命中时磁盘兜底 · [attachments-v2 扩展]。
     *
     * <p>WHY：内存索引可能因 200 上限 FIFO 逐出（仅删 Map 不动盘，imageStore.ts:115-124）或服务重启
     * 而丢失 id→路径映射，但文件仍在盘。本方法先走 {@link #getBase64} 内存命中，miss 时用
     * {@link Files#list} 扫描 {@code {storeDir}/{id}.*} 按文件名还原文件读回 —— 对齐 CC「文件在盘，
     * 路径可重建」路径通道语义（media-cache/pdf-cache 同理），保证服务重启/索引逐出后仍可拉图。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @return base64 + mediaType（mediaType 由扩展名反向推导 {@link #mediaTypeOfExtension}）；
     *         内存与磁盘均未命中 / 读失败返回 null
     */
    public Base64Content getBase64OrDisk(String sessionId, long id) {
        String sid = resolveSessionId(sessionId);
        Base64Content cached = getBase64(sid, id);
        if (cached != null) {
            return cached;
        }
        Path dir = getImageStoreDir(sid);
        if (!Files.isDirectory(dir)) {
            if (log.isDebugEnabled()) {
                log.debug("图片磁盘兜底目录不存在：session={} id={} dir={}", sid, id, dir);
            }
            return null;
        }
        String prefix = id + ".";
        try (Stream<Path> entries = Files.list(dir)) {
            Path match = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                if (log.isDebugEnabled()) {
                    log.debug("图片磁盘兜底未命中：session={} id={}（{} 下无 {id}.* 文件）", sid, id, dir);
                }
                return null;
            }
            byte[] bytes = Files.readAllBytes(match);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mediaType = mediaTypeOfExtension(fileExtension(match));
            if (log.isDebugEnabled()) {
                log.debug("图片磁盘兜底命中：session={} id={} path={} mediaType={} 字节数={}",
                        sid, id, match, mediaType, bytes.length);
            }
            return new Base64Content(mediaType, base64);
        } catch (Exception e) {
            log.warn("图片磁盘兜底读取失败：session={} id={} 原因={}", sid, id, e.getMessage());
            return null;
        }
    }

    /**
     * 从文件名取扩展名（{@code {id}.{ext}} → {@code ext}）· 磁盘兜底反推 mediaType 用。
     */
    private static String fileExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    /**
     * 扩展名 → 全 MIME（{@code png} → {@code image/png}）· 反向推导 {@link #extensionOf(String)}
     * （落盘侧由 {@code mediaType.split('/')[1]} 推 ext，此处逆向）；未知扩展名兜底 {@code image/{ext}}。
     */
    private static String mediaTypeOfExtension(String ext) {
        String lower = ext == null ? "" : ext.toLowerCase();
        if (lower.isBlank()) {
            return "image/png";
        }
        String mapped = EXTENSION_TO_MEDIA_TYPE.get(lower);
        return mapped != null ? mapped : "image/" + lower;
    }

    /** 常见图片扩展名 → MIME 映射（CC 支持的 image 类型；未知扩展名走 {@code image/{ext}} 兜底）。 */
    private static final Map<String, String> EXTENSION_TO_MEDIA_TYPE = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "svg", "image/svg+xml",
            "svg+xml", "image/svg+xml",
            "bmp", "image/bmp",
            "avif", "image/avif"
    );

    /**
     * 读取图片 → data URL · Java 读取 API。可直接用于 UI 预览 / 前端渲染。
     *
     * @param sessionId 会话 id
     * @param id        图片 id
     * @return {@code data:{mediaType};base64,{data}}；未命中 / 读失败返回 null
     */
    public String getDataUrl(String sessionId, long id) {
        Base64Content content = getBase64(sessionId, id);
        return content == null ? null : toDataUrl(content);
    }

    /** 拼 data URL · Java 工具方法。 */
    public String toDataUrl(Base64Content content) {
        return "data:" + content.mediaType() + ";base64," + content.base64();
    }

    // ════════════════════════════════════════════════════════════════════
    // 内存清理（对齐 CC imageStore.ts:111-113）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 清空全部会话图片内存缓存 · 对齐 CC {@code clearStoredImagePaths}（imageStore.ts:111-113），
     * 等价 CC /clear caches（caches.ts:87）—— 只清内存 Map，不动磁盘。
     */
    public void clearStoredImagePaths() {
        synchronized (lock) {
            pendingPromptImages.clear();
        }
        clearAll();
        if (log.isDebugEnabled()) {
            log.debug("已清空全部会话图片内存缓存（含待注入桶）");
        }
    }

    /**
     * 清空单会话图片内存缓存（不动磁盘）· Java 会话级清理。
     *
     * @param sessionId 会话 id
     */
    public void clearSessionImages(String sessionId) {
        String sid = resolveSessionId(sessionId);
        synchronized (lock) {
            pendingPromptImages.remove(sid);
        }
        clearSessionBucket(sid);
    }

    /**
     * 清理单个会话的图片缓存（会话结束/删除时调用）· 清内存桶（含待注入桶）+ 删除 {@code image-cache/{sessionId}/} 目录。
     * 对应 CC {@code cleanupOldImageCaches} 中对「非当前会话目录」的删除路径（imageStore.ts:147-153）。
     *
     * @param sessionId 会话 id
     */
    public void cleanupSession(String sessionId) {
        String sid = resolveSessionId(sessionId);
        synchronized (lock) {
            pendingPromptImages.remove(sid);
        }
        super.cleanupSession(sid);
    }

    /**
     * 清理历史会话的图片缓存 · 对齐 CC {@code cleanupOldImageCaches}（imageStore.ts:129-167）：
     * 扫描 {@code {configHome}/image-cache/} 下所有会话目录，删除<b>非当前会话</b>的目录
     * （CC 亦一并删除文件条目，rm recursive+force，imageStore.ts:149），目录清空后删除根目录
     * （imageStore.ts:156-162）。
     *
     * @param currentSessionId 当前会话 id（保留该目录）
     */
    public void cleanupOldImageCaches(String currentSessionId) {
        String current = resolveSessionId(currentSessionId);
        Path baseDir = Path.of(NexusaiPaths.getAppConfigHomeDir(), IMAGE_STORE_DIR);
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(baseDir)) {
            List<Path> sessionDirs = entries.filter(Files::exists).toList();
            for (Path entry : sessionDirs) {
                String name = entry.getFileName().toString();
                if (name.equals(current)) {
                    continue;
                }
                try {
                    deleteRecursively(entry);
                    clearSessionImages(name);
                    if (log.isDebugEnabled()) {
                        log.debug("已清理历史图片缓存目录：{}", entry);
                    }
                } catch (Exception e) {
                    // CC imageStore.ts:151-153 忽略单目录错误
                    log.debug("清理单个历史图片缓存目录失败（忽略）：{} 原因={}", entry, e.getMessage());
                }
            }
        } catch (Exception e) {
            // CC imageStore.ts:163-166 忽略读根目录错误
            log.debug("扫描图片缓存根目录失败（忽略）：{} 原因={}", baseDir, e.getMessage());
        }
        // CC imageStore.ts:156-162：剩余条目为空则删除根目录
        try (Stream<Path> remaining = Files.list(baseDir)) {
            if (remaining.findFirst().isEmpty()) {
                Files.deleteIfExists(baseDir);
            }
        } catch (Exception e) {
            log.debug("删除空图片缓存根目录失败（忽略）：{} 原因={}", baseDir, e.getMessage());
        }
    }

    /**
     * A4 待注入桶会话键：[session-id-short] 发送侧（A1）与消费侧（LlmAgentLoop A4
     * {@code params.sessionId()}）已统一 short 直键 → 恒等直返（原 canonicalUuid 归一化
     * 的桶键双形态错位根因消除）。
     */
    private String canonicalSessionId(String sessionId) {
        return resolveSessionId(sessionId);
    }
}

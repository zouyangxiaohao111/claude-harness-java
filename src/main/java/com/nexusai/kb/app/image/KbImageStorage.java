package com.nexusai.kb.app.image;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.app.config.KbFileStorage;
import com.nexusai.kb.app.config.KbStorageBackendService;
import com.nexusai.kb.app.config.LocalFileStorage;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/**
 * 知识库图片持久化存储 · 把解析器产出的临时图片搬到「解析结束后仍然存在」的地方。
 *
 * <h2>⭐ 为什么必须单独一层</h2>
 * 解析器把图片写在 {@code workDir}（系统临时目录）里，入库编排器收尾时会把整个目录递归删掉
 * （{@code KnowledgeParseProcessor#cleanup}）。如果不先搬走，图片在「写完分块」和「清理临时目录」
 * 之间就蒸发了 —— 这正是此前 {@code parsed.images()} 全仓 0 处消费的后果。
 *
 * <h2>⭐ 复用既有存储，不另起路径</h2>
 * 本仓已有 {@link KbFileStorage} 抽象（用户 2026-11-05 裁定「有 minio 就接，没有就本地」），
 * 本地实现落 {@code nexusai.knowledge.storage-dir}（默认 {@code ~/.nexusai/kb-storage}）。
 * 图片<b>复用同一个根</b>，只是键前缀不同（{@code kb-images/<knowledgeId>/…}）：
 * <ul>
 *   <li>不引入第二套「配置根」（configHome vs storage-dir 各有各的坑，混用会造出
 *       「某个 appName 下能读到、另一个读不到」）；</li>
 *   <li>切换到 MinIO/COS 时图片与原始文件一起走同一后端，不需要额外迁移。</li>
 * </ul>
 *
 * <h2>⭐ 键的形态（前端 URL 也由它派生）</h2>
 * <pre>
 *   storage key : kb-images/{knowledgeId}/{imageId}.{ext}
 *   可访问 URL  : /api/v1/kb/images/{knowledgeId}/{imageId}.{ext}
 * </pre>
 * 只存两段（knowledgeId + 文件名）⇒ 前端/后端都不需要知道存储根在哪。
 * ⚠️ {@code knowledgeId} 必须做片段校验（UUID 形态），否则 {@code ../} 会逃逸出存储根。
 *
 * <p><b>⚠️ 为什么不把解析器产物直接塞库</b>：图片二进制进 SQLite 会让库文件膨胀到 GB 级，
 * 且每次查询都要过 BLOB（{@code kb_chunk}` 的读取是检索热路径）。源侧同样把图片交给文件服务、
 * 库里只留 URL。
 */
@Component
public class KbImageStorage {

    private static final Logger log = LoggerFactory.getLogger(KbImageStorage.class);

    /** 存储键前缀（与原始文档的 {@code <kbId>/<knowledgeId>.<ext>} 分开命名空间）。 */
    public static final String KEY_PREFIX = "kb-images";

    /** 可访问 URL 前缀（Controller 侧必须与之一致）。 */
    public static final String URL_PREFIX = "/api/v1/kb/images";

    private final KbStorageBackendService storageBackendService;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final LocalFileStorage localFileStorage;

    @Autowired
    public KbImageStorage(KbStorageBackendService storageBackendService,
                          KbKnowledgeBaseMapper knowledgeBaseMapper,
                          LocalFileStorage localFileStorage) {
        this.storageBackendService = storageBackendService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.localFileStorage = localFileStorage;
    }

    /**
     * 直构构造器（单测 / 离线工具用）· <b>只用本地存储</b>。
     *
     * <p>⚠️ 生产必须用上面的 {@code @Autowired} 构造器：否则知识库绑定的 MinIO/COS 后端会被静默忽略，
     * 用户切了后端却发现图片还落本地盘。
     *
     * @param localFileStorage 本地文件存储
     */
    public KbImageStorage(LocalFileStorage localFileStorage) {
        this(null, null, localFileStorage);
    }

    /**
     * 把一张图片落盘并返回它的可访问 URL。
     *
     * @param knowledge 知识条目（取 id / knowledgeBaseId，用于决定存储后端与键前缀）
     * @param source    解析器产出的临时文件
     * @param imageId   本次解析内唯一的图片标识（调用方生成，建议 {@code img-001} 形态）
     * @return 落盘结果（URL / 存储键 / 字节数）；<b>源文件被判为图标时返回 null</b>
     * @throws IOException 读取源文件失败（调用方按「单图失败不阻断整篇解析」处理）
     */
    public Stored store(KbKnowledgeRecord knowledge, Path source, String imageId) throws IOException {
        if (knowledge == null || source == null) {
            return null;
        }
        long size = Files.size(source);
        // ⭐ 过滤 1/2：字节数下限（源侧 image_resolver.go:42-68 的 minImageBytes）
        if (size < MIN_IMAGE_BYTES) {
            log.info("[图片] 跳过图标（字节数过小）knowledge={} file={} 字节={} 阈值={}",
                knowledge.getId(), source.getFileName(), size, MIN_IMAGE_BYTES);
            return null;
        }
        // ⭐ 过滤 2/2：像素尺寸（源侧 isIconImage 的完整语义）
        //    ⚠️ 实测必需：2×2 的 PNG 也有 1.2KB，「只按字节」根本挡不住图标/装饰线。
        //    源侧用 Go 的 image.DecodeConfig —— 只读头部，不解码像素；这里用 ImageReader
        //    读宽高，语义等价且同样不分配整幅位图（大图不会被这一层拖垮）。
        int[] dims = readImageSize(source);
        if (dims != null && dims[0] < MIN_IMAGE_DIMENSION && dims[1] < MIN_IMAGE_DIMENSION) {
            log.info("[图片] 跳过图标（宽高过小）knowledge={} file={} 宽={} 高={} 阈值={}px",
                knowledge.getId(), source.getFileName(), dims[0], dims[1], MIN_IMAGE_DIMENSION);
            return null;
        }
        String ext = extensionOf(source);
        String key = buildKey(knowledge.getId(), imageId, ext);
        KbFileStorage storage = resolveStorage(knowledge);
        try (InputStream in = Files.newInputStream(source)) {
            String written = storage.write(key, in, size);
            String url = urlOf(key);
            log.info("[图片] 已落盘 knowledge={} key={} 字节={} 宽高={}x{} provider={} 写入结果={}",
                knowledge.getId(), key, size,
                dims == null ? -1 : dims[0], dims == null ? -1 : dims[1],
                storage.provider(), written);
            return new Stored(url, key, size, ext, dims == null ? -1 : dims[0], dims == null ? -1 : dims[1]);
        }
    }

    /**
     * 只读图头拿宽高 · 对齐源侧 {@code image.DecodeConfig}（{@code image_resolver.go:52}）。
     *
     * <p><b>为什么不 {@code ImageIO.read}</b>：那会把整幅位图解码进内存，
     * 一张 8000×8000 的扫描页就是 256MB —— 而这里只需要两个整数。
     * {@link ImageReader#getWidth(int)} 只解析头部。
     *
     * @param source 图片文件
     * @return {@code [width, height]}；无法识别格式（EMF/WMF/未知）时返回 null
     */
    private static int[] readImageSize(Path source) {
        ImageInputStream iis = null;
        ImageReader reader = null;
        try (InputStream in = Files.newInputStream(source)) {
            iis = ImageIO.createImageInputStream(in);
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(iis, true, true);
            return new int[]{reader.getWidth(0), reader.getHeight(0)};
        } catch (IOException | RuntimeException e) {
            // 尺寸读不出来不是错误：源侧此时回落到「只按字节判断」（这里已经先判过字节）
            if (log.isDebugEnabled()) {
                log.debug("[图片] 读取尺寸失败，按无尺寸处理 file={} 原因={}", source, e.toString());
            }
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (iis != null) {
                try {
                    iis.close();
                } catch (IOException ignored) {
                    // 关闭失败无影响
                }
            }
        }
    }

    /**
     * 读取已存图片（Controller 用）。
     *
     * @param storageKey 存储键（来自 URL 解析，已通过 {@link #isValidKey} 校验）
     * @return 内容流；调用方负责关闭
     */
    public InputStream open(String storageKey) {
        return resolveStorageByKey(storageKey).read(storageKey);
    }

    /**
     * 是否存在（前端探测 / 排障用）。
     *
     * @param storageKey 存储键
     * @return 本地后端看文件是否存在；对象存储实现不做 head（恒 true，交给 {@link #open} 抛错）
     */
    public boolean exists(String storageKey) {
        Path local = resolveStorageByKey(storageKey).localPath(storageKey);
        return local != null && Files.isRegularFile(local);
    }

    /** 存储键 → 本地绝对路径（对象存储返回 null）。仅供测试与排障。 */
    public Path localPath(String storageKey) {
        return resolveStorageByKey(storageKey).localPath(storageKey);
    }

    /**
     * 删除一张已存图片（失败回滚用）。
     *
     * <p>语义 = 尽力而为：拿不到合法键就跳过；实现抛错只记 WARN。
     * ⛔ 绝不因为「清理失败」把一次已经成功的解析翻成失败。
     *
     * @param storageKey 存储键
     */
    public void delete(String storageKey) {
        if (!isValidKey(storageKey)) {
            log.warn("[图片] 回滚跳过非法存储键 key={}", storageKey);
            return;
        }
        try {
            resolveStorageByKey(storageKey).delete(storageKey);
            log.info("[图片] 已回滚删除 key={}", storageKey);
        } catch (RuntimeException e) {
            log.warn("[图片] 回滚删除失败 key={} 原因={}", storageKey, e.toString());
        }
    }

    // ── 键 / URL ────────────────────────────────────────────────────

    /**
     * 拼存储键。
     *
     * @param knowledgeId 知识条目 ID（会做片段校验）
     * @param imageId     图片标识（会做片段校验）
     * @param ext         扩展名（不含点）
     * @return {@code kb-images/<knowledgeId>/<imageId>.<ext>}
     */
    public static String buildKey(String knowledgeId, String imageId, String ext) {
        requireSegment(knowledgeId, "knowledgeId");
        requireSegment(imageId, "imageId");
        return KEY_PREFIX + "/" + knowledgeId + "/" + imageId + "." + ext;
    }

    /** 存储键 → 前端可访问 URL（{@code /api/v1/kb/images/<knowledgeId>/<file>}）。 */
    public static String urlOf(String storageKey) {
        if (storageKey == null || !storageKey.startsWith(KEY_PREFIX + "/")) {
            return "";
        }
        return URL_PREFIX + storageKey.substring(KEY_PREFIX.length());
    }

    /**
     * URL 片段 → 存储键（Controller 的路径变量校验入口）。
     *
     * <p>⭐ <b>只接受两段</b>：{@code <knowledgeId>/<file>}。多一段（{@code ../}、嵌套目录）
     * 一律拒绝 —— 这是本类唯一的路径逃逸面。
     *
     * @param knowledgeId 知识条目 ID
     * @param fileName    文件名（含扩展名）
     * @return 存储键；非法输入返回 null（调用方按 404 处理，不回显原因）
     */
    public static String keyOf(String knowledgeId, String fileName) {
        if (!isSafeSegment(knowledgeId) || !isSafeSegment(fileName)) {
            return null;
        }
        return KEY_PREFIX + "/" + knowledgeId + "/" + fileName;
    }

    /** 是否为本类产出的合法存储键（偶发场景下调用方只拿到键）。 */
    public static boolean isValidKey(String storageKey) {
        if (storageKey == null || !storageKey.startsWith(KEY_PREFIX + "/")) {
            return false;
        }
        String rest = storageKey.substring(KEY_PREFIX.length() + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return false;
        }
        return isSafeSegment(rest.substring(0, slash)) && isSafeSegment(rest.substring(slash + 1));
    }

    /**
     * 单段路径校验：只允许字母/数字/横线/下划线/点，且不允许 {@code .} / {@code ..}。
     *
     * <p>比 {@link com.nexusai.kb.app.config.LocalFileStorage} 的「normalize 后仍在根下」更严：
     * 图片键的两段是 UUID + 生成的图片名，本就没有任何合法输入需要分隔符。
     */
    private static boolean isSafeSegment(String s) {
        if (s == null || s.isEmpty() || s.length() > 200) {
            return false;
        }
        if (".".equals(s) || "..".equals(s)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static void requireSegment(String s, String name) {
        if (!isSafeSegment(s)) {
            throw new ValidationException("非法图片路径片段 " + name + "=" + s);
        }
    }

    /** 源文件扩展名（小写不含点）；无法判定时按 {@code png}（解析器产出的都是位图）。 */
    private static String extensionOf(Path source) {
        String name = source.getFileName() == null ? "" : source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "png";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 白名单：只放行位图扩展名，避免把奇怪后缀写进键（读 endpoint 会按扩展名给 Content-Type）
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "jpg".equals(ext) ? "jpg" : ext;
            default -> "png";
        };
    }

    // ── 后端解析 ────────────────────────────────────────────────────

    /** 本地兜底实现（Spring 注入 {@code LocalFileStorage}；单测可传 null）。 */
    private KbFileStorage local() {
        if (localFileStorage == null) {
            throw new IllegalStateException("本地文件存储未装配（KbImageStorage 缺少 LocalFileStorage）");
        }
        return localFileStorage;
    }

    /** 按知识库绑定的存储后端选实现；库里没配 ⇒ 默认后端 ⇒ 本地。 */
    private KbFileStorage resolveStorage(KbKnowledgeRecord knowledge) {
        if (storageBackendService == null || knowledgeBaseMapper == null) {
            return local();
        }
        String backendId = null;
        try {
            String kbId = knowledge.getKnowledgeBaseId();
            if (kbId != null && !kbId.isBlank()) {
                KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(kbId);
                backendId = kb == null ? null : kb.getStorageBackendId();
            }
        } catch (RuntimeException e) {
            // 读库失败不该让图片整批失败：回落默认后端（与本地兜底同义）
            log.warn("[图片] 读知识库存储后端失败，回落默认 knowledge={} 原因={}",
                knowledge.getId(), e.toString());
        }
        try {
            return storageBackendService.resolveForKnowledgeBase(backendId).storage();
        } catch (RuntimeException e) {
            log.warn("[图片] 解析存储后端失败，回落本地 knowledge={} 原因={}",
                knowledge.getId(), e.toString());
            return local();
        }
    }

    /** 按存储键选实现（读路径只知道键）。 */
    private KbFileStorage resolveStorageByKey(String storageKey) {
        if (!isValidKey(storageKey)) {
            throw new ValidationException("非法图片存储键");
        }
        if (storageBackendService == null) {
            return local();
        }
        try {
            return storageBackendService.resolveForKnowledgeBase(null).storage();
        } catch (RuntimeException e) {
            log.warn("[图片] 读路径解析存储后端失败，回落本地 key={} 原因={}", storageKey, e.toString());
            return local();
        }
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /**
     * 落盘结果。
     *
     * @param url        可访问 URL（落 {@code kb_chunk.image_info[].url}）
     * @param storageKey 存储键（落 {@code attrs.storage_key}，读文件时用）
     * @param sizeBytes  字节数（落 {@code attrs.size_bytes}）
     * @param extension  实际扩展名（读 endpoint 据此给 Content-Type）
     * @param width      像素宽（读不到为 -1）
     * @param height     像素高（读不到为 -1）
     */
    public record Stored(String url, String storageKey, long sizeBytes, String extension,
                         int width, int height) {
    }

    /** 最小图片字节数 · 源侧 {@code minImageBytes}（{@code image_resolver.go:45}）。 */
    public static final long MIN_IMAGE_BYTES = 512;

    /**
     * 最小图标尺寸 · 源侧 {@code minImageDimension}（{@code image_resolver.go:42}）。
     *
     * <p>判定是「宽<b>和</b>高都小于」才丢（源侧 {@code isIconImage} 用 {@code &&}）：
     * 一张 800×60 的分隔线仍是内容，不该按图标处理。
     */
    public static final int MIN_IMAGE_DIMENSION = 64;
}

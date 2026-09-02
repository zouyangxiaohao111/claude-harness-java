package com.nexusai.apis.attachment;

import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.attachment.MediaAttachmentStore;
import com.nexusai.application.agent.attachment.PdfAttachmentStore;
import com.nexusai.application.agent.attachment.PdfAttachmentStore.StoredPdf;
import com.nexusai.application.agent.attachment.MediaAttachmentStore.StoredMedia;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.AttachmentService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.PdfUploadResponse;
import com.nexusai.repository.session.entity.AttachmentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件上传端点 · [attachments-v2 Step2] 多类型支持（image/video/audio/pdf multipart 上传落盘）。
 *
 * <p><b>端点</b>：{@code POST /api/v1/attachments/upload}（multipart form，字段 {@code file} +
 * 可选 {@code sessionId}）→ 校验类型白名单 + 单文件 ≤100MB + 魔数 → 分流落盘（cache）→
 * 注册附件表（{@link AttachmentService}）→ 返回 {@code {contentId, filename, mediaType, size}}，
 * contentId = 附件表自增 id（持久化 DB，F5/重启可恢复预览；附件双模式统一 contentId 注册中心）；
 * {@code GET /api/v1/attachments/config} → {@code {localRead}}（本地直读开关）；
 * {@code GET /api/v1/attachments/content/{sessionId}/{contentId}} → 附件表 contentId 解析落盘
 * path 流式字节预览（Range 206 支持）；
 * {@code GET /api/v1/attachments/image/{sessionId}/{id}} → 按 image-cache contentId 拉取图片
 * {@code {mediaType, base64}}（内存索引 + 磁盘兜底，服务重启/逐出后仍可拉图）。
 *
 * <p><b>混合传输路由（对齐 CC 路径通道）</b>：
 * <ul>
 *   <li>≤5MB 直传通道更优（图片附件通道 A1，{@code SendMessageRequest.attachments} 直接带
 *       {@code base64}，不调本端点）</li>
 *   <li>&gt;5MB / 大文件 → 本端点 multipart 上传落盘，返回 {@code contentId}，后续消息以
 *       {@code {type: 'pdf'|'image'|'video'|'audio'|'file', contentId: ...}} 引用，后端按磁盘<b>路径</b>
 *       解析（CC pdf.ts readPDF 路径通道）</li>
 * </ul>
 *
 * <p><b>分流</b>：pdf → {@link PdfAttachmentStore}（{@code pdf-cache}）；image →
 * {@link ImageAttachmentStore}（{@code image-cache}）；video/audio/file →
 * {@link MediaAttachmentStore}（{@code media-cache}）。三种 store 均只负责<b>落盘</b>（内部缓存），
 * 落盘后统一注册附件表（{@code AttachmentService.register(sourceType='upload')}），出站 contentId =
 * 附件表自增 id（不再是 store id）。≤5MB 图片 A1 直发 {@code imagePasteIds} 链路（不经本端点）不受影响。
 *
 * <p><b>校验</b>：
 * <ol>
 *   <li>空文件 → {@link ValidationException} 400</li>
 *   <li>&gt; 100MB → 400（{@link #MEDIA_MAX_SIZE}，对齐 apiLimits.ts:72 too_large）</li>
 *   <li>非白名单类型（mediaType 非 image/video/audio/pdf 且扩展名不在表）→ 400</li>
 *   <li>魔数不符（防 HTML/文本改名伪装媒体或 PDF 入库）→ 400</li>
 * </ol>
 *
 * <p><b>安全</b>：落盘文件名恒为 {@code {id}.{ext}}（id 自增），<b>不使用客户端原始文件名</b>落盘
 * （防路径穿越 / 恶意文件名）。原始文件名仅存元数据供显示。
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    /** 单文件上传上限 · 对齐 apiLimits.ts:72 too_large（PDF_MAX_EXTRACT_SIZE）。 */
    private static final long MEDIA_MAX_SIZE = 100L * 1024 * 1024;

    /** 魔数读取字节数 · 覆盖全部魔数表（png/jpeg/gif/webp/bmp/mp4(偏移4)/webm/mkv/mp3/wav/ogg/flac/pdf）。 */
    private static final int MAGIC_READ_BYTES = 16;

    /** 文件扩展名 → MIME 映射（{@code /content} 预览 Content-Type 反推，防 octet-stream 导致浏览器下载而非内联）。 */
    private static final Map<String, String> EXTENSION_TO_MIME = createExtensionToMime();

    private static Map<String, String> createExtensionToMime() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pdf", "application/pdf");
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("gif", "image/gif");
        m.put("webp", "image/webp");
        m.put("bmp", "image/bmp");
        m.put("svg", "image/svg+xml");
        m.put("mp4", "video/mp4");
        m.put("mov", "video/quicktime");
        m.put("m4v", "video/x-m4v");
        m.put("webm", "video/webm");
        m.put("mkv", "video/x-matroska");
        m.put("mp3", "audio/mpeg");
        m.put("wav", "audio/wav");
        m.put("ogg", "audio/ogg");
        m.put("oga", "audio/ogg");
        m.put("flac", "audio/flac");
        m.put("m4a", "audio/mp4");
        m.put("mp4a", "audio/mp4");
        return m;
    }

    @Autowired
    private PdfAttachmentStore pdfAttachmentStore;

    /** [attachments-v2 Step2] 媒体（video/audio/file）路径存储。 */
    @Autowired
    private MediaAttachmentStore mediaAttachmentStore;

    /** [attachments-v2] 图片（image）路径存储 · 对齐 CC utils/imageStore.ts image-cache 空间。 */
    @Autowired
    private ImageAttachmentStore imageAttachmentStore;

    /** 附件表业务（V64 · 附件双模式统一 contentId 注册中心）· upload 落盘后注册，返回附件表自增 id 作 contentId。 */
    @Autowired
    private AttachmentService attachmentService;

    /** 本地桌面直读开关（前后端同机 Tauri 传本地 path 后端直读，省 upload）· nexusai.attachments.local-read，默认关。 */
    @Value("${nexusai.attachments.local-read:false}")
    private boolean localRead;

    /**
     * 附件 multipart 上传 · 校验 → 分流落盘 → 返回 contentId。
     *
     * @param file      multipart 文件
     * @param sessionId 会话 id（可选；缺省 → MDC → 'unknown' 兜底）
     * @return {@link PdfUploadResponse}：{contentId, filename, mediaType, size}
     */
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.OK)
    public PdfUploadResponse upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (file == null || file.isEmpty()) {
            log.warn("附件上传请求：文件为空，拒绝");
            throw new ValidationException("附件上传失败：未收到文件或文件为空");
        }
        long size = file.getSize();
        String filename = file.getOriginalFilename();
        String mediaType = file.getContentType();
        if (log.isInfoEnabled()) {
            log.info("附件上传请求收到：session={} filename={} mediaType={} size={}B（multipart 上传端点）",
                    sessionId, filename, mediaType, size);
        }
        // 大小校验：> 100MB（对齐 apiLimits.ts:72 too_large）
        if (size > MEDIA_MAX_SIZE) {
            log.warn("附件上传拒绝：大小 {}B 超过上限 {}B session={} filename={}",
                    size, MEDIA_MAX_SIZE, sessionId, filename);
            throw new ValidationException(
                    "附件上传失败：文件大小 " + size + "B 超过上限 " + MEDIA_MAX_SIZE + "B");
        }
        // 类型校验：mediaType 主类型 image/video/audio 或扩展名白名单 或 PDF
        if (!isAllowedType(filename, mediaType)) {
            log.warn("附件上传拒绝：非白名单类型（mediaType={} filename={}）session={}", mediaType, filename, sessionId);
            throw new ValidationException("附件上传失败：不支持的文件类型（支持 image/video/audio/pdf）");
        }
        // 魔数校验（防 HTML/文本改名伪装媒体或 PDF）
        byte[] head = readHead(file, sessionId);
        if (!verifyMagic(head, mediaType)) {
            log.warn("附件上传拒绝：魔数与类型不符（mediaType={} filename={}）session={}", mediaType, filename, sessionId);
            throw new ValidationException("附件上传失败：文件内容与声明的类型不符（魔数校验失败）");
        }

        // 会话归一化：显式 sessionId → MDC（RequestContext.sessionId()）→ 'unknown' 兜底（同
        //   AttachmentStoreBase.resolveSessionId）——register 拒绝无主注册，附件表 session_id 需与
        //   store 落盘目录所属会话一致（F5 预览 URL /content/{sessionId}/{contentId} 的 sessionId 即此值）
        String resolvedSession = resolveSessionIdOrUnknown(sessionId);

        // 分流落盘：pdf → PdfAttachmentStore；image → ImageAttachmentStore；video/audio/file → MediaAttachmentStore
        try (InputStream in = file.getInputStream()) {
            if (isPdfFile(filename, mediaType)) {
                StoredPdf stored = pdfAttachmentStore.store(sessionId, in, size, filename);
                if (stored == null) {
                    throw new IllegalStateException("PDF 落盘失败，请重试");
                }
                // [附件双模式] store 落盘(cache) 后注册附件表 → contentId = 附件表自增 id（持久化 DB）
                long contentId = attachmentService.register(resolvedSession, stored.path(),
                        "application/pdf", stored.filename(), stored.size(), "upload");
                log.info("PDF 上传成功：session={} storeId={} contentId={}(附件表) filename={} size={}B "
                                + "path={}（落盘后注册附件表，返回附件表 id 作 contentId）",
                        resolvedSession, stored.id(), contentId, stored.filename(), stored.size(), stored.path());
                return new PdfUploadResponse(String.valueOf(contentId), stored.filename(),
                        "application/pdf", stored.size());
            }
            if (isImageFile(filename, mediaType)) {
                // 图片仍走 ImageAttachmentStore（image-cache）落盘；upload 大图 → 注册附件表（附件表 id 作 contentId）
                ImageAttachmentStore.StoredImage storedImage =
                        imageAttachmentStore.store(sessionId, in, size, filename, mediaType);
                if (storedImage == null) {
                    throw new IllegalStateException("图片落盘失败，请重试");
                }
                long contentId = attachmentService.register(resolvedSession, storedImage.path(),
                        storedImage.mediaType(), filename, size, "upload");
                log.info("图片上传成功：session={} storeId={} contentId={}(附件表) filename={} mediaType={} "
                                + "size={}B path={}（落盘后注册附件表，返回附件表 id 作 contentId）",
                        resolvedSession, storedImage.id(), contentId, filename, storedImage.mediaType(),
                        size, storedImage.path());
                return new PdfUploadResponse(String.valueOf(contentId), filename,
                        storedImage.mediaType(), size);
            }
            StoredMedia storedMedia = mediaAttachmentStore.store(sessionId, in, size, filename, mediaType);
            if (storedMedia == null) {
                throw new IllegalStateException("媒体落盘失败，请重试");
            }
            long contentId = attachmentService.register(resolvedSession, storedMedia.path(),
                    storedMedia.mediaType(), storedMedia.filename(), storedMedia.size(), "upload");
            log.info("媒体上传成功：session={} storeId={} contentId={}(附件表) filename={} mediaType={} "
                            + "size={}B path={}（落盘后注册附件表，返回附件表 id 作 contentId）",
                    resolvedSession, storedMedia.id(), contentId, storedMedia.filename(), storedMedia.mediaType(),
                    storedMedia.size(), storedMedia.path());
            return new PdfUploadResponse(String.valueOf(contentId), storedMedia.filename(),
                    storedMedia.mediaType(), storedMedia.size());
        } catch (IOException e) {
            log.error("附件上传失败：读取上传文件流失败 session={} filename={} 原因={}",
                    sessionId, filename, e.toString());
            throw new ValidationException("附件上传失败：读取文件流失败，原因=" + e.getMessage());
        }
    }

    /**
     * 附件本地直读配置 · 前端挂载时拉取，判断「大文件本地 path 直传 vs upload」。
     *
     * <p><b>WHY（附件双模式）</b>：local-read=true（前后端同机 Tauri）时前端把 &gt;5MB 大文件以本地
     * 绝对路径随消息发送（省 upload），走 {@code ChatService.resolveAttachments} path 分支注册附件表；
     * false 时仍走本 controller {@link #upload} multipart。值源 {@code nexusai.attachments.local-read}
     * （默认 false）。
     *
     * @return {@code {localRead: boolean}}
     */
    @GetMapping("/config")
    public Map<String, Boolean> config() {
        if (log.isDebugEnabled()) {
            log.debug("附件配置查询：localRead={}", localRead);
        }
        return Map.of("localRead", localRead);
    }

    /**
     * 附件字节流预览端点（Range 206 支持）· 附件表 contentId → 落盘/外部 path → 流式字节。
     *
     * <p><b>WHY（附件双模式统一预览）</b>：F5/重启后 user_attachments 的 contentId + 本 url
     * {@code GET /api/v1/attachments/content/{sessionId}/{contentId}} 统一经附件表解析落盘 path 读盘
     * （跨 store、重启无损），后期换文件服务器只改后端 url 解析层，前端零改动。≤5MB 图片
     * imagePasteIds 链路（getImage）不经本端点。path 附件（source='path'）同走附件表解析，可直接内联。
     *
     * <p><b>Range</b>：video/audio 拖动进度需要。注入 {@link List}{@code <HttpRange>}（Spring 标准
     * Range 头解析），多段范围取首段、忽略其余；无 Range 头 → 全量 200。响应带 Content-Type
     * （{@link #resolveContentType} 扩展名→MIME）+ {@code X-Content-Type-Options: nosniff}
     * （防 HTML/脚本伪装附件内容被浏览器执行）。
     *
     * @param sessionId 会话 id（路径变量，路由承载；对齐 DB 键）
     * @param contentId 附件表自增 id（= 出站 contentId）
     * @param ranges    Range 头解析出的字节范围（可空；多段取首段）
     * @return ResourceRegion 流式响应（200 全量 / 206 Partial Content）
     * @throws NotFoundException 附件表无记录 / 记录 path 为空 / 磁盘文件不存在 → 404
     */
    @GetMapping("/content/{sessionId}/{contentId}")
    public ResponseEntity<ResourceRegion> getContent(@PathVariable("sessionId") String sessionId,
                                                     @PathVariable("contentId") long contentId,
                                                     @RequestHeader(value = "Range", required = false)
                                                     List<HttpRange> ranges) {
        AttachmentRecord rec = attachmentService.getContent(contentId);
        if (rec == null) {
            log.warn("附件预览未命中：session={} contentId={}（附件表无记录）", sessionId, contentId);
            throw new NotFoundException("附件不存在: session=" + sessionId + " contentId=" + contentId);
        }
        String path = rec.getPath();
        if (path == null || path.isBlank()) {
            log.warn("附件预览拒绝：附件表记录 path 为空 session={} contentId={}", sessionId, contentId);
            throw new NotFoundException("附件路径为空: contentId=" + contentId);
        }
        Path file = Paths.get(path).normalize();
        if (!Files.exists(file) || Files.isDirectory(file)) {
            log.warn("附件预览未命中：磁盘文件不存在 session={} contentId={} path={}",
                    sessionId, contentId, path);
            throw new NotFoundException("附件文件不存在: contentId=" + contentId + " path=" + path);
        }
        if (log.isDebugEnabled()) {
            log.debug("附件预览命中：session={} contentId={} path={} sourceType={} mediaType={}",
                    sessionId, contentId, path, rec.getSourceType(), rec.getMediaType());
        }
        FileSystemResource resource = new FileSystemResource(file.toFile());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resolveContentType(rec.getFilename(), rec.getMediaType())));
        // X-Content-Type-Options 无 HttpHeaders 常量（spring-web 6.2 HttpHeaders 未暴露该头字段常量），
        //   用头名字面量（防 HTML/脚本伪装附件内容被浏览器执行，nosniff 语义）
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        try {
            long length = resource.contentLength();
            if (ranges == null || ranges.isEmpty()) {
                // 无 Range → 全量 200
                headers.setContentLength(length);
                return new ResponseEntity<>(new ResourceRegion(resource, 0, length), headers, HttpStatus.OK);
            }
            // 有 Range → 206 Partial Content；多段范围仅取首段（浏览器媒体请求常规单段）
            if (ranges.size() > 1 && log.isDebugEnabled()) {
                log.debug("附件预览收到多段 Range（共 {} 段），仅取首段响应 session={} contentId={}",
                        ranges.size(), sessionId, contentId);
            }
            ResourceRegion region = ranges.get(0).toResourceRegion(resource);
            headers.setContentLength(region.getCount());
            long start = region.getPosition();
            long end = start + Math.max(region.getCount() - 1, 0);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            return new ResponseEntity<>(region, headers, HttpStatus.PARTIAL_CONTENT);
        } catch (IOException e) {
            log.error("附件预览失败：读取文件元数据异常 session={} contentId={} path={} 原因={}",
                    sessionId, contentId, path, e.toString());
            throw new NotFoundException("附件文件读取失败: contentId=" + contentId + "，原因=" + e.getMessage());
        }
    }

    /**
     * 图片内容读取端点 · [attachments-v2] 按 image-cache contentId 拉取图片内容。
     *
     * <p><b>WHY</b>：图片上传（image → ImageAttachmentStore）返回的 contentId 与 A1 图片发送
     * {@code imagePasteIds} 同属 image-cache 空间；前端以 {@code {type:'image', contentId}} 引用时，
     * 本端点经 {@link ImageAttachmentStore#getBase64OrDisk} 先内存索引命中，miss 时磁盘兜底扫描
     * {@code {configHome}/image-cache/{sessionId}/{id}.*} 还原 —— 服务重启 / 200 上限 FIFO 逐出后仍可拉图
     * （对齐 CC「文件在盘，路径可重建」路径通道语义，imageStore.ts:104-124）。
     *
     * @param sessionId 会话 id（路径变量；null/blank → MDC → 'unknown' 兜底）
     * @param id        图片 id（image-cache 空间 contentId）
     * @return {@code {mediaType, base64}}；内存 + 磁盘均未命中 → {@link NotFoundException} 404
     */
    @GetMapping("/image/{sessionId}/{id}")
    public Map<String, String> getImage(@PathVariable("sessionId") String sessionId,
                                        @PathVariable("id") long id) {
        ImageAttachmentStore.Base64Content content = imageAttachmentStore.getBase64OrDisk(sessionId, id);
        if (content == null) {
            log.warn("图片内容读取未命中：session={} id={}（内存索引 + 磁盘兜底均未命中）", sessionId, id);
            throw new NotFoundException("图片不存在: session=" + sessionId + " id=" + id);
        }
        if (log.isDebugEnabled()) {
            log.debug("图片内容读取命中：session={} id={} mediaType={}（返回 base64 + mediaType）",
                    sessionId, id, content.mediaType());
        }
        // Map.of 安全：getBase64OrDisk 返回非 null 时 mediaType 恒非 null（内存命中已兜底 image/png，磁盘兜底扩展名反推恒有值）
        return Map.of("mediaType", content.mediaType(), "base64", content.base64());
    }

    /**
     * [AM-CC-20260825] 批量拉图 · 前端重拉消息时把全部消息的 imagePasteIds 去重后一次请求拿全部
     *  （避免逐 id N 次 GET /image/{sessionId}/{id} 的串行开销，用户 2026-08-25 拍板 b+缓存方案）。
     *  单 id miss 跳过（批量容忍，不 404）；前端本地映射缓存 + 乐观 imageData 优先，只对 miss 批量拉。
     *
     * @param sessionId 会话 id（路径变量）
     * @param body      {@code {"ids": ["1","2"]}}（image-cache 空间 contentId 列表）
     * @return {@code {id: {mediaType, base64}}}；无命中 → 空 Map
     */
    @PostMapping("/image/batch/{sessionId}")
    public Map<String, Map<String, String>> getImagesBatch(@PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, List<String>> body) {
        List<String> ids = body == null ? null : body.get("ids");
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (ids != null) {
            for (String id : ids) {
                try {
                    long imageId = Long.parseLong(id);
                    ImageAttachmentStore.Base64Content content = imageAttachmentStore.getBase64OrDisk(sessionId, imageId);
                    if (content != null) {
                        result.put(id, Map.of("mediaType", content.mediaType(), "base64", content.base64()));
                    }
                } catch (NumberFormatException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("批量拉图跳过非法 id={}: {}", id, e.toString());
                    }
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("批量拉图完成：session={} 请求 {} 个 id，命中 {} 个", sessionId,
                    ids == null ? 0 : ids.size(), result.size());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    // 校验工具
    // ════════════════════════════════════════════════════════════════════

    /**
     * 会话归一化 · 显式 sessionId → MDC（{@link RequestContext#sessionId()}）→ 'unknown' 兜底，
     * 镜像 {@code AttachmentStoreBase.resolveSessionId}（upload sessionId 可选，缺省时注册附件表
     * 需与 store 落盘目录所属会话一致）。
     */
    private String resolveSessionIdOrUnknown(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        String fromMdc = RequestContext.sessionId();
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return "unknown";
    }

    /**
     * 类型白名单 · mediaType 主类型 image/video/audio 或 PDF；或文件名扩展名白名单兜底。
     */
    private boolean isAllowedType(String filename, String mediaType) {
        if (isPdfFile(filename, mediaType)) {
            return true;
        }
        if (mediaType != null) {
            String primary = mediaType.toLowerCase();
            if (primary.startsWith("image/") || primary.startsWith("video/") || primary.startsWith("audio/")) {
                return true;
            }
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                    || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv")
                    || lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                    || lower.endsWith(".flac");
        }
        return false;
    }

    /**
     * Content-Type 反推（预览用）· 按文件名扩展名查 {@link #EXTENSION_TO_MIME}；
     * 扩展名未知 → 附件表 mediaType（形如 {@code type/subtype} 才可用，防解析异常）→
     * {@code application/octet-stream} 兜底。
     */
    private String resolveContentType(String filename, String recordMediaType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot + 1).toLowerCase();
                String mime = EXTENSION_TO_MIME.get(ext);
                if (mime != null) {
                    return mime;
                }
            }
        }
        if (recordMediaType != null) {
            int slash = recordMediaType.indexOf('/');
            if (slash > 0 && slash < recordMediaType.length() - 1) {
                return recordMediaType;
            }
        }
        return "application/octet-stream";
    }

    /** 类型判定：mediaType=application/pdf 或 文件名以 .pdf 结尾（大小写不敏感）。 */
    private boolean isPdfFile(String filename, String mediaType) {
        if (mediaType != null && "application/pdf".equalsIgnoreCase(mediaType)) {
            return true;
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".pdf")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类型判定：mediaType 主类型 image/* 或 文件名以图片扩展名结尾（大小写不敏感）。
     * 供 upload 分流 image → ImageAttachmentStore（video/audio/file 走 MediaAttachmentStore）。
     * 注：image/svg+xml 亦命中（ImageAttachmentStore 扩展名 svg+xml 落盘，对齐 CC imageStore.ts:34）。
     */
    private boolean isImageFile(String filename, String mediaType) {
        if (mediaType != null && mediaType.toLowerCase().startsWith("image/")) {
            return true;
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
        }
        return false;
    }

    /**
     * 读文件头（魔数校验用）· 读前 {@link #MAGIC_READ_BYTES} 字节；读失败抛 ValidationException 400。
     */
    private byte[] readHead(MultipartFile file, String sessionId) {
        byte[] head = new byte[MAGIC_READ_BYTES];
        try (InputStream in = file.getInputStream()) {
            int read = in.read(head);
            if (read < 4) {
                log.warn("附件上传拒绝：文件过短无法校验魔数（read={}）session={} filename={}",
                        read, sessionId, file.getOriginalFilename());
                throw new ValidationException("附件上传失败：文件内容过短，无法校验类型");
            }
            return head;
        } catch (IOException e) {
            log.warn("附件上传失败：读取魔数头失败 session={} filename={} 原因={}",
                    sessionId, file.getOriginalFilename(), e.toString());
            throw new ValidationException("附件上传失败：读取文件内容失败，原因=" + e.getMessage());
        }
    }

    /**
     * 魔数校验 · 按 mediaType 具体类型匹配魔数表；仅知主类型（image/* 等）时尝试该组全部魔数。
     * 校验失败返回 false（调用方 400）。
     */
    private boolean verifyMagic(byte[] h, String mediaType) {
        String mt = mediaType == null ? "" : mediaType.toLowerCase();
        if (mt.equals("image/png")) return isPng(h);
        if (mt.equals("image/jpeg")) return isJpeg(h);
        if (mt.equals("image/gif")) return isGif(h);
        if (mt.equals("image/webp")) return isWebp(h);
        if (mt.equals("image/bmp") || mt.equals("image/x-ms-bmp")) return isBmp(h);
        if (mt.equals("video/mp4")) return isMp4(h);
        if (mt.equals("video/webm") || mt.equals("video/x-matroska") || mt.equals("video/mkv")) return isWebm(h);
        if (mt.equals("audio/mpeg")) return isMp3(h);
        if (mt.equals("audio/wav") || mt.equals("audio/x-wav") || mt.equals("audio/wave")) return isWav(h);
        if (mt.equals("audio/ogg")) return isOgg(h);
        if (mt.equals("audio/flac") || mt.equals("audio/x-flac")) return isFlac(h);
        if (mt.equals("application/pdf")) return isPdf(h);
        if (mt.startsWith("image/")) {
            return isPng(h) || isJpeg(h) || isGif(h) || isWebp(h) || isBmp(h);
        }
        if (mt.startsWith("video/")) {
            return isMp4(h) || isWebm(h);
        }
        if (mt.startsWith("audio/")) {
            return isMp3(h) || isWav(h) || isOgg(h) || isFlac(h);
        }
        // mediaType 缺失（前端未传）：魔数表全组尝试
        return isPng(h) || isJpeg(h) || isGif(h) || isWebp(h) || isBmp(h)
                || isMp4(h) || isWebm(h) || isMp3(h) || isWav(h) || isOgg(h) || isFlac(h) || isPdf(h);
    }

    private boolean isPng(byte[] h) {
        return (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47;
    }

    private boolean isJpeg(byte[] h) {
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    private boolean isGif(byte[] h) {
        return h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
    }

    private boolean isWebp(byte[] h) {
        return h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
    }

    private boolean isBmp(byte[] h) {
        return h[0] == 'B' && h[1] == 'M';
    }

    private boolean isMp4(byte[] h) {
        return h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
    }

    private boolean isWebm(byte[] h) {
        return (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
    }

    private boolean isMp3(byte[] h) {
        return (h[0] == 'I' && h[1] == 'D' && h[2] == '3')
                || ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFB)
                || ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xF3);
    }

    private boolean isWav(byte[] h) {
        return h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
    }

    private boolean isOgg(byte[] h) {
        return h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S';
    }

    private boolean isFlac(byte[] h) {
        return h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C';
    }

    private boolean isPdf(byte[] h) {
        return h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
    }
}

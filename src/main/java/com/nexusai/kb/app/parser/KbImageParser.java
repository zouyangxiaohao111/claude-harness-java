package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图片解析器 · 单张图片过视觉模型转成 Markdown 描述（知识库入库链）。
 *
 * <p><b>通道来源</b>：复用 {@link KbVisionCaller} —— 与 {@link KbPdfParser} 的扫描件补强是<b>同一个</b>
 * 视觉通道（{@code KbVisionModelCaller} → {@code settings.multimodalModelName} + {@code LlmSideCallClient}）。
 *
 * <p><b>⛔ 这不是对话链的 {@code VisionAnalyzeTool}</b>：那条路的入参是 LLM 的 {@code tool_use} JSON、
 * 出参是 {@code AgentToolResult}，入口在 agent 工具循环里；本类是知识库入库链的
 * {@link KbDocumentParser} 实现，入参是文件路径、出参是 {@link KbParsedDocument}。
 * 两者入口不同，但共用同一套视觉模型通道（模型配置与记账来源一致）。
 *
 * <p><b>为什么 {@code @Order(15)}</b>：路由按 {@code @Order} 升序取第一个 {@code supports()} 命中的解析器。
 * 图片必须排在 {@link KbPdfParser}(10) 之后、{@link KbOfficeParser}(20) 之前 —— 前者的 MIME 判定
 * （{@code application/pdf}）与图片不重叠，后者的 Office MIME 分支同样不重叠，位置只是为了
 * 「越专用的越先」这一既有顺序语义不被打破。
 *
 * <p><b>fail-soft（硬约束，与 {@link KbPdfParser} 同语义）</b>：视觉不可用 / 模型返回空 / 调用抛异常，
 * 一律返回<b>空文本 + {@code visionFailed=true} + 原因</b>，⛔ 不抛 {@link KbParseException}。
 * 理由：图片入库的价值在「尽量有内容」，一次视觉超时不该让用户的上传白费；失败可观测即可
 * （未配置多模态模型时也不该让整个知识库任务红掉）。
 *
 * <p><b>产物形态</b>：{@code text} = 视觉模型给出的 Markdown；{@code images} 为空列表 ——
 * 源图本身就是被解析的对象，不另生成本地副产物，也不重复登记成引用。
 */
@Component
@Order(15)
public class KbImageParser implements KbDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(KbImageParser.class);

    /** 单文档文本上限（与 PDF / Office / 文本解析器统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /**
     * 原始图片大小上限 · 超过直接降级。
     *
     * <p>{@code KbVisionModelCaller} 对 base64 有 5MB 硬限制（约合 3.75MB 原始字节）；
     * 这里留 15MB 余量让「稍大的照片」仍能试一次，但挡住「几百 MB 的扫描件」直接发请求。
     */
    static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;

    /** 支持的图片扩展名（对齐源站 simple 引擎的 image 列表）。 */
    static final Set<String> IMAGE_EXTENSIONS =
        Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp");

    /**
     * 视觉通道按文件名判 mediaType 认不出的格式 · 必须先转 PNG。
     *
     * <p><b>WHY</b>：{@code KbVisionModelCaller.mediaTypeOf} 只认 png/webp/gif，
     * 其余一律按 {@code image/jpeg} 发送（它的注释写着「PdfSupport 渲染产出的就是 JPEG」）。
     * BMP/TIFF 的字节贴上 {@code image/jpeg} 发给 provider，轻则报错、重则解码出垃圾描述。
     * GIF 虽然被认出来（{@code image/gif}），但很多多模态端点不支持 GIF，而 ImageIO 能无损转 PNG ——
     * 多转一次只是多几毫秒，比「上传成功但描述为空」值得。
     * ⛔ 不改 {@code KbVisionCaller}（派单书约束），转换放在本解析器内。
     */
    static final Set<String> NEEDS_PNG_CONVERSION = Set.of("bmp", "tiff", "tif", "gif");

    /**
     * 图片描述提示词 · 面向知识库入库（要的是可检索的正文，不是寒暄）。
     *
     * <p>与 {@code KbVisionModelCaller.DEFAULT_PAGE_PROMPT} 的区别：那条是「逐行转写页面」，
     * 面向 PDF 扫描页；单张图片常常没有成篇文字（照片、示意图、图表），要求「详细描述内容」，
     * 同时保留文字/表格，避免纯图场景下模型返回 NO_TEXT 导致空结果。
     */
    static final String IMAGE_DESCRIPTION_PROMPT =
        "详细描述这张图片的内容，包括其中的文字、表格、图表信息，用 Markdown 输出";

    /** 视觉能力（可 null → 恒降级；Spring 装配恒注入，单测可传 null）。 */
    private final KbVisionCaller visionCaller;

    @Autowired
    public KbImageParser(KbVisionCaller visionCaller) {
        this.visionCaller = visionCaller;
    }

    /** 无视觉能力的构造（弱降级：恒返回空文本）· 供离线单测使用。 */
    KbImageParser() {
        this(null);
    }

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null) {
            // 有扩展名就以扩展名为准：⛔ 不能因为 MIME 是 image/* 就把 .svg 也吃下来 ——
            // SVG 是文本格式，ImageIO 读不出来，字节直接发给视觉模型只会得到垃圾描述。
            return IMAGE_EXTENSIONS.contains(ext);
        }
        String mime = KbParsers.normalizeMime(mimeType);
        // 无扩展名（上传流没有文件名）时才看 MIME；同样排除矢量/本机解不了的子类型。
        return mime.startsWith("image/") && !mime.contains("svg") && !mime.contains("heic");
    }

    /**
     * 能力声明 · 直接复用 {@link #supports} 用的扩展名常量（⛔ 不另抄一份，防止两处漂移）。
     *
     * <p>仅列扩展名：{@code supports} 里的「无扩展名时按 {@code image/*} MIME」分支无法用扩展名表达，
     * 故不在此列出。
     */
    @Override
    public Set<String> supportedExtensions() {
        return IMAGE_EXTENSIONS;
    }

    /** 展示名 · 前端「解析引擎」下拉用（本仓真实能力名，不是 anydoc/MarkItDown 之类的外部引擎）。 */
    @Override
    public String displayName() {
        return "视觉模型解析";
    }

    /** 引擎族名别名 · 源站图片走内置简单引擎 {@code simple}（另收 {@code image} 便于按类型点名）。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("simple", "image");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();

        // ── 1. 前置：视觉通道是否可用（不可用 = 弱降级，不是失败）──
        boolean visionAllowed = ctx == null || ctx.visionEnabled();
        if (!visionAllowed) {
            return degrade(fileName, file, "调用方已关闭视觉补强");
        }
        if (visionCaller == null || !visionCaller.isAvailable()) {
            return degrade(fileName, file, "视觉能力未配置");
        }

        // ── 2. 调视觉模型（异常不外抛：图片入库不该整份失败）──
        // BMP/TIFF/GIF 先转 PNG（见 NEEDS_PNG_CONVERSION）；超大原图先拒绝，免得白发一次请求
        Path toSend = file;
        Path converted = null;
        Path preprocessed = null;
        try {
            long size = java.nio.file.Files.size(file);
            if (size > MAX_IMAGE_BYTES) {
                String reason = "图片过大（" + size + " 字节，上限 " + MAX_IMAGE_BYTES + "）";
                log.warn("KbImageParser: {}，降级为空文本 file={}", reason, fileName);
                return degrade(fileName, file, reason);
            }
            converted = convertIfNeeded(file, ctx);
            if (converted != null) {
                toSend = converted;
            }
            // 扫描件 / 照片预处理（白边裁剪 + 小角度纠偏）· 只换「送视觉」的路径：
            //   开关关闭或预处理失败时 preprocess 原样返回入参 ⇒ 走原图，不需在此判空。
            Path pre = KbImagePreprocessor.preprocess(toSend, preprocessDir(ctx));
            if (!pre.equals(toSend)) {
                preprocessed = pre;
                toSend = pre;
            }
        } catch (Exception e) {
            log.warn("KbImageParser: 图片预处理失败，降级为空文本 file={} 原因={}", fileName, e.getMessage());
            return degrade(fileName, file, "图片预处理失败: " + e.getMessage());
        }

        String described;
        try {
            described = visionCaller.describeImage(toSend, IMAGE_DESCRIPTION_PROMPT, ctx);
        } catch (Exception e) {
            log.warn("KbImageParser: 视觉调用失败，降级为空文本 file={} 原因={}", fileName, e.getMessage());
            return degrade(fileName, file, e.getMessage() == null ? "视觉调用失败" : e.getMessage());
        } finally {
            deleteQuietly(converted);
            deleteQuietly(preprocessed);
        }

        // ── 3. 模型返回空内容（含 null）同样按降级处理，但原因要能区分 ──
        if (described == null || described.isBlank()) {
            log.info("KbImageParser: 视觉模型返回空内容，降级为空文本 file={}", fileName);
            return degrade(fileName, file, "视觉模型返回空内容");
        }

        // ── 4. 正常产物 ──
        KbParsers.Truncation t = KbParsers.truncate(described.strip(), MAX_TEXT_CHARS);
        Map<String, String> meta = baseMetadata(fileName, file);
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(t.text().length()));
        if (t.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }

        // ⭐ 源位置块：图片本身就是唯一来源 ⇒ 整篇一个块，覆盖全部码点。
        //   ⚠️ 这里说的是「这张图的描述文字对应到『原图』」，不是原图内部的区域 ——
        //   视觉模型不给坐标，本仓也没有 OCR 框，⛔ 不伪造 bbox。
        List<KbSourceBlock> sourceBlocks = List.of();
        int cp = t.text().codePointCount(0, t.text().length());
        if (cp > 0) {
            sourceBlocks = List.of(new KbSourceBlock(0, cp,
                KbSourceLocator.text(0, cp).withMapping(KbSourceLocator.MAPPING_EXACT)));
        }
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, String.valueOf(sourceBlocks.size()));
        log.info("[溯源] {} 产出 {} 个 source block（类型={} 覆盖码点={}/{}）",
            fileName, sourceBlocks.size(), KbSourceLocator.TYPE_TEXT, cp, cp);

        if (log.isInfoEnabled()) {
            log.info("KbImageParser: 解析完成 file={} mime={} chars={} sourceBlocks={}", fileName,
                meta.get(KbParsedDocument.META_MIME_TYPE), t.text().length(), sourceBlocks.size());
        }
        return new KbParsedDocument(t.text(), List.of(), meta, sourceBlocks);
    }

    /**
     * 降级产物 · 空文本 + {@code visionFailed=true} + 具体原因（对齐 {@link KbPdfParser} 的 fail-soft）。
     *
     * @param reason 给排查用的具体原因（⛔ 不写「未知错误」这类无信息量的话）
     */
    private KbParsedDocument degrade(String fileName, Path file, String reason) {
        Map<String, String> meta = baseMetadata(fileName, file);
        meta.put(KbParsedDocument.META_VISION_FAILED, "true");
        meta.put(KbParsedDocument.META_VISION_FAILURE_REASON, reason);
        // 长度恒等于实际产出的 text 长度（此处恒为 0，但走同一个输出点，避免两处口径漂移）
        meta.put(KbParsedDocument.META_TEXT_LENGTH, "0");
        // 降级 = 没有正文 ⇒ 没有源位置块（⛔ 不写 0 码点的空块：那会让上层以为「已覆盖」）
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, "0");
        if (log.isInfoEnabled()) {
            log.info("KbImageParser: 降级为空文本（弱降级，不抛异常）file={} 原因={}", fileName, reason);
        }
        return new KbParsedDocument("", List.of(), meta);
    }

    /** 与成败无关的公共元数据：文件名 / 探测到的 MIME / 解析器名。 */
    private Map<String, String> baseMetadata(String fileName, Path file) {
        Map<String, String> meta = KbParsers.newMeta();
        meta.put(KbParsedDocument.META_FILE_NAME, fileName);
        String mime = KbTikaSupport.detectMime(file, fileName);
        if (mime != null && !mime.isBlank()) {
            // ⛔ 探测失败就留空，不拿扩展名硬编一个假 MIME（同 KbParsers.fillCommonMetadata 口径）
            meta.put(KbParsedDocument.META_MIME_TYPE, mime);
        }
        meta.put(KbParsedDocument.META_PARSER, name());
        return meta;
    }

    /**
     * BMP/TIFF 转成临时 PNG（其余格式原样返回 null = 不需要转换）。
     *
     * <p>落盘位置：{@code ctx.workDir()}（调用方负责生命周期）或系统临时目录；
     * 无论哪条路径，调用方用完即由 {@link #deleteQuietly(Path)} 删掉。
     *
     * @return 转换后的 PNG 路径；无需转换时 null
     */
    private Path convertIfNeeded(Path file, KbParseContext ctx) throws java.io.IOException {
        String ext = KbParsers.extensionOf(file.getFileName() == null ? null : file.getFileName().toString());
        if (ext == null || !NEEDS_PNG_CONVERSION.contains(ext)) {
            return null;
        }
        Path dir = ctx != null && ctx.workDir() != null
            ? ctx.workDir()
            : java.nio.file.Files.createTempDirectory("kb-image-");
        java.nio.file.Files.createDirectories(dir);
        Path out = dir.resolve(baseName(file) + "-vision.png");
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(file.toFile());
        if (img == null) {
            // ImageIO 读不出来（如未安装 TIFF 插件）→ 交给调用方按「转码失败」降级，⛔ 不发垃圾字节给模型
            throw new java.io.IOException("无法解码 " + ext + " 图片（ImageIO 返回 null）");
        }
        try {
            if (!javax.imageio.ImageIO.write(img, "png", out.toFile())) {
                throw new java.io.IOException("PNG writer unavailable");
            }
        } finally {
            img.flush();
        }
        return out;
    }

    /** 临时文件名主干（不含扩展名，去掉路径分隔符）。 */
    private static String baseName(Path file) {
        String n = file.getFileName() == null ? "image" : file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        return stem.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    /**
     * 预处理产物目录 · 落在调用方给的工作目录（{@code ctx.workDir()}）或临时目录。
     *
     * <p>与 {@link #convertIfNeeded} 同一口径：产物是临时文件，用完由 {@link #deleteQuietly(Path)} 删。
     * ⛔ 不要写回原图所在目录 —— 那会往知识库存储里塞 {@code -pre.jpg} 垃圾。
     */
    private static Path preprocessDir(KbParseContext ctx) throws java.io.IOException {
        if (ctx != null && ctx.workDir() != null) {
            return ctx.workDir();
        }
        return java.nio.file.Files.createTempDirectory("kb-image-pre-");
    }

    /** 尽力删除临时文件（失败只记 debug：清理不成功不该把成功结果变失败）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(p);
        } catch (java.io.IOException e) {
            log.debug("KbImageParser: 删除临时转码文件失败 path={} 原因={}", p, e.getMessage());
        }
    }
}

package com.nexusai.kb.app.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * PaddleOCR-VL 解析器 · 把 PDF / 图片交给外部 PaddleOCR-VL 服务做版面解析 + 文本识别。
 *
 * <p><b>服务契约</b>（PaddleOCR-VL 完整服务）：
 * <ul>
 *   <li>{@code POST {endpoint}/layout-parsing}</li>
 *   <li>请求：{@code {"file": "<base64>", "fileType": 0|1, "useLayoutDetection": true, "prettifyMarkdown": true}}
 *       —— {@code fileType} 0 = PDF、1 = 图像</li>
 *   <li>响应：{@code {"errorCode":0,"errorMsg":"Success","result":{"layoutParsingResults":[{"markdown":{"text":"..."}}]}}}</li>
 *   <li>{@code layoutParsingResults} 按页序：图像 1 项、PDF 为页数</li>
 *   <li>{@code errorCode != 0} 或 HTTP 非 200 ⇒ 解析失败（抛 {@link KbParseException}）</li>
 * </ul>
 *
 * <p><b>为什么 {@code @Order(40)}</b>：路由按 {@code @Order} 升序取第一个 {@code supports()} 命中的解析器
 * （{@link KbParserRegistry#select}）。40 排在所有内置解析器之后
 * （PDF 10 / anydoc 12 / 图片 15 / Office 20 / 文本 30）⇒ <b>默认路由完全不变</b>：
 * {@code .pdf} 仍走 {@link KbPdfParser}、图片仍走 {@link KbImageParser}。
 * 只有知识库的 {@code parser_engine_rules} 显式点名 {@code KbPaddleOcrVlParser} 时才会走本解析器。
 *
 * <p><b>为什么还实现 {@link SelfDescribingAvailability}</b>：外部服务地址来自配置。没配时
 * {@code isAvailable()=false}，{@link KbParserEngineRouter} 会记 warn 并回落默认路由 ——
 * 一个「选了但没配地址」的库配置不该让整库文档解析全红。
 *
 * <p><b>⛔ 不写库</b>：只读文件、落图片副产物、返回 {@link KbParsedDocument}。
 */
@Component
@Order(40)
public class KbPaddleOcrVlParser implements KbDocumentParser, SelfDescribingAvailability {

    private static final Logger log = LoggerFactory.getLogger(KbPaddleOcrVlParser.class);

    /** 服务地址配置键（与字段上的 {@code @Value} 同源，⛔ 报错文案里不带占位符写法）。 */
    static final String ENDPOINT_PROPERTY = "nexusai.knowledge.ocr.paddleocr-vl.endpoint";

    /** API Key 配置键（留空 = 不发送 Authorization 头）。 */
    static final String API_KEY_PROPERTY = "nexusai.knowledge.ocr.paddleocr-vl.api-key";

    /** 版面解析路由（拼在 endpoint 之后）。 */
    static final String ROUTE_PATH = "/layout-parsing";

    /** 请求体 {@code fileType}：0 = PDF。 */
    static final int FILE_TYPE_PDF = 0;

    /** 请求体 {@code fileType}：1 = 图像。 */
    static final int FILE_TYPE_IMAGE = 1;

    /** 本解析器支持的扩展名（小写、不含点）· {@code supports()} 与 {@code supportedExtensions()} 同源。 */
    static final Set<String> EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "bmp", "tiff", "webp");

    /** 单文档文本上限（与 PDF / Office / 文本 / 图片解析器统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** {@code metadata.parser} 的值（排障时用来区分「这份文档是谁解析的」）。 */
    static final String META_PARSER_VALUE = "paddleocr_vl";

    /** {@code metadata} 键 · 每页字符数（逗号分隔，按页序）。 */
    static final String META_PAGE_CHARS = "pageChars";

    /** 图片引用来源 · PaddleOCR-VL 版面解析产出（{@link KbImageRef#sourceKind()}）。 */
    static final String KIND_OCR_LAYOUT = "ocr-layout";

    /** 页与页之间的分隔（两个换行 = 一个空行，与 {@link KbPdfParser} 按页拼 Markdown 的口径一致）。 */
    static final String PAGE_SEPARATOR = "\n\n";

    /** 连接超时 10s。 */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 请求超时 300s（整份 PDF 版面解析可能很慢，比普通 HTTP 调用宽松）。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);

    /** 非 200 响应体在异常文案里保留的最大字符数（防止把整页 HTML 抄进日志）。 */
    private static final int MAX_ERROR_BODY_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 共享 HttpClient（线程安全、可复用）· 仓库内没有统一的 OCR HTTP 客户端，故用 JDK 自带的。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Value("${nexusai.knowledge.ocr.paddleocr-vl.endpoint:}")
    private String endpoint = "";

    @Value("${nexusai.knowledge.ocr.paddleocr-vl.api-key:}")
    private String apiKey = "";

    /** Spring 用（字段注入）· 单测可直接 {@code new KbPaddleOcrVlParser()}。 */
    public KbPaddleOcrVlParser() {
    }

    /** 单测用构造 · 不走 Spring，直接给地址与 key。 */
    KbPaddleOcrVlParser(String endpoint, String apiKey) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 契约：能力声明
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public boolean supports(String fileName, String mimeType) {
        String ext = KbParsers.extensionOf(fileName);
        if (ext != null) {
            // 有扩展名就以扩展名为准（与本仓其它解析器同口径）
            return EXTENSIONS.contains(ext);
        }
        String mime = KbParsers.normalizeMime(mimeType);
        if (mime.isEmpty()) {
            return false;
        }
        // 无扩展名时才看 MIME：PDF 与位图（排除 SVG 等矢量格式 —— OCR 服务吃不了）
        return mime.equals("application/pdf") || (mime.startsWith("image/") && !mime.contains("svg"));
    }

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public String displayName() {
        return "PaddleOCR-VL（外部服务）";
    }

    /**
     * 引擎族名别名 · 源站引擎名 {@code paddleocr_vl}（云端部署形态另收 {@code paddleocr_vl_cloud}）。
     * 没有这份别名时，规则里写 {@code paddleocr_vl} 会因为 {@code name()} 是类名而永远匹配不到。
     */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("paddleocr_vl", "paddleocr_vl_cloud");
    }

    @Override
    public boolean isAvailable() {
        return !trimmed(endpoint).isEmpty();
    }

    @Override
    public String unavailableReason() {
        return isAvailable() ? "" : notConfiguredMessage();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 契约：解析
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        if (!isAvailable()) {
            // 配置缺失是「环境没配好」，不是「文件解析不了」⇒ IllegalStateException（与派单书一致）
            throw new IllegalStateException(notConfiguredMessage());
        }
        if (file == null || !Files.isRegularFile(file)) {
            throw new KbParseException("PaddleOCR-VL 待解析文件不存在或不是普通文件: " + file);
        }

        String url;
        try {
            url = layoutParsingUrl();
        } catch (IllegalStateException e) {
            throw e;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new KbParseException("PaddleOCR-VL 读取文件失败: " + fileName + " — " + e.getMessage(), e);
        }
        int fileType = fileTypeOf(fileName);
        String body;
        try {
            body = buildRequestBody(bytes, fileType);
        } catch (IllegalArgumentException e) {
            throw new KbParseException("PaddleOCR-VL 请求体构造失败: " + fileName + " — " + e.getMessage(), e);
        }

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String key = trimmed(apiKey);
            if (!key.isEmpty()) {
                builder.header("Authorization", "Bearer " + key);
            }
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KbParseException("PaddleOCR-VL 调用被中断: " + fileName, e);
        } catch (IOException | IllegalArgumentException e) {
            throw new KbParseException("PaddleOCR-VL 调用失败: " + fileName + " endpoint=" + url
                + " — " + e.getMessage(), e);
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        int status = response.statusCode();
        if (status != 200) {
            String snippet = snippet(response.body());
            log.warn("KbPaddleOcrVlParser: HTTP 非 200 file={} status={} 耗时ms={} 响应={}",
                fileName, status, elapsedMs, snippet);
            throw new KbParseException("PaddleOCR-VL 返回 HTTP " + status + ": " + fileName + " — " + snippet);
        }

        JsonNode root = readJson(response.body(), fileName);
        int errorCode = root.path("errorCode").asInt(0);
        if (errorCode != 0) {
            String errorMsg = root.path("errorMsg").asText("");
            log.warn("KbPaddleOcrVlParser: 服务返回错误 file={} errorCode={} errorMsg={} 耗时ms={}",
                fileName, errorCode, errorMsg, elapsedMs);
            throw new KbParseException("PaddleOCR-VL 返回错误 errorCode=" + errorCode
                + (errorMsg.isEmpty() ? "" : " errorMsg=" + errorMsg) + ": " + fileName);
        }

        JsonNode pageNodes = root.path("result").path("layoutParsingResults");
        if (!pageNodes.isArray()) {
            log.warn("KbPaddleOcrVlParser: 响应缺少 result.layoutParsingResults file={} 耗时ms={}",
                fileName, elapsedMs);
            throw new KbParseException("PaddleOCR-VL 响应缺少 result.layoutParsingResults: " + fileName);
        }

        List<String> pageTexts = new ArrayList<>(pageNodes.size());
        List<KbImageRef> images = new ArrayList<>();
        List<String> pageCharCounts = new ArrayList<>(pageNodes.size());
        Path imageDir = null;
        for (int i = 0; i < pageNodes.size(); i++) {
            int pageNo = i + 1;  // PDF 页号 1-based（与 KbImageRef / KbPdfParser 口径一致）
            JsonNode markdown = pageNodes.get(i).path("markdown");
            String pageText = markdown.path("text").asText("");
            if (pageText.isBlank()) {
                log.warn("KbPaddleOcrVlParser: 空页 file={} page={}（服务未识别出文本）", fileName, pageNo);
            }
            pageTexts.add(pageText.strip());
            pageCharCounts.add(String.valueOf(pageText.strip().length()));
            if (markdown.path("images").isArray() && markdown.path("images").size() > 0) {
                if (imageDir == null) {
                    imageDir = resolveImageDir(ctx);
                }
                images.addAll(decodeImages(markdown.path("images"), pageNo, imageDir, fileName));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String pageText : pageTexts) {
            if (pageText.isEmpty()) {
                continue;  // 空页不占正文位置，但页数/每页字符数仍按实际记录
            }
            if (sb.length() > 0) {
                sb.append(PAGE_SEPARATOR);
            }
            sb.append(pageText);
        }
        KbParsers.Truncation truncated = KbParsers.truncate(sb.toString(), MAX_TEXT_CHARS);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(KbParsedDocument.META_FILE_NAME, fileName);
        String mime = KbTikaSupport.detectMime(file, fileName);
        if (mime != null && !mime.isBlank()) {
            meta.put(KbParsedDocument.META_MIME_TYPE, mime);
        }
        meta.put(KbParsedDocument.META_PARSER, META_PARSER_VALUE);
        meta.put(KbParsedDocument.META_PAGE_COUNT, String.valueOf(pageTexts.size()));
        meta.put(META_PAGE_CHARS, String.join(",", pageCharCounts));
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(truncated.text().length()));
        if (truncated.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }
        if (!images.isEmpty()) {
            meta.put("imageCount", String.valueOf(images.size()));
        }
        // 本解析器拿不到原文件的坐标信息（服务只回 Markdown）⇒ 不伪造 source block
        meta.put(KbParsedDocument.META_SOURCE_BLOCKS, "0");

        log.info("KbPaddleOcrVlParser: 解析完成 file={} 页数={} chars={} 图片={} 耗时ms={}",
            fileName, pageTexts.size(), truncated.text().length(), images.size(), elapsedMs);
        return new KbParsedDocument(truncated.text(), images, meta);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 请求体
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构造 {@code /layout-parsing} 请求体（默认带版面检测 + Markdown 美化）。
     *
     * @param fileBytes 原始文件字节（必填）
     * @param fileType  {@link #FILE_TYPE_PDF}(0) 或 {@link #FILE_TYPE_IMAGE}(1)
     * @return JSON 字符串
     */
    static String buildRequestBody(byte[] fileBytes, int fileType) {
        return buildRequestBody(fileBytes, fileType, true, true);
    }

    /**
     * 构造请求体（可选字段可覆盖）。
     *
     * @param fileBytes          原始文件字节（必填）
     * @param fileType           0 = PDF、1 = 图像
     * @param useLayoutDetection 是否启用版面检测
     * @param prettifyMarkdown   是否美化输出 Markdown
     * @return JSON 字符串
     */
    static String buildRequestBody(byte[] fileBytes, int fileType,
                                   boolean useLayoutDetection, boolean prettifyMarkdown) {
        if (fileBytes == null) {
            throw new IllegalArgumentException("fileBytes 不能为空");
        }
        if (fileType != FILE_TYPE_PDF && fileType != FILE_TYPE_IMAGE) {
            throw new IllegalArgumentException("fileType 只允许 0(PDF) 或 1(图像)，实际=" + fileType);
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("file", Base64.getEncoder().encodeToString(fileBytes));
        body.put("fileType", fileType);
        body.put("useLayoutDetection", useLayoutDetection);
        body.put("prettifyMarkdown", prettifyMarkdown);
        return body.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部
    // ════════════════════════════════════════════════════════════════════════

    /** 按扩展名判 {@code fileType}：pdf → 0，其余（图片）→ 1。 */
    static int fileTypeOf(String fileName) {
        String ext = KbParsers.extensionOf(fileName);
        return "pdf".equals(ext) ? FILE_TYPE_PDF : FILE_TYPE_IMAGE;
    }

    /** 服务地址：去掉末尾斜杠后拼 {@link #ROUTE_PATH}；已带该后缀则不重复拼。 */
    private String layoutParsingUrl() {
        String base = trimmed(endpoint);
        if (base.isEmpty()) {
            throw new IllegalStateException(notConfiguredMessage());
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith(ROUTE_PATH) ? base : base + ROUTE_PATH;
    }

    /** 未配置时的固定文案（{@link #parse} 与 {@link #unavailableReason} 共用，避免两处漂移）。 */
    private static String notConfiguredMessage() {
        return "PaddleOCR-VL 服务未配置（" + ENDPOINT_PROPERTY + "）";
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static JsonNode readJson(String body, String fileName) {
        if (body == null || body.isBlank()) {
            throw new KbParseException("PaddleOCR-VL 返回空响应体: " + fileName);
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new KbParseException("PaddleOCR-VL 响应不是合法 JSON: " + fileName
                + " — " + snippet(body), e);
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String one = body.replaceAll("[\\r\\n\\t]+", " ").trim();
        return one.length() <= MAX_ERROR_BODY_CHARS ? one : one.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }

    /**
     * 图片落盘目录 · 优先 {@code ctx.workDir()}；未给则自建系统临时目录（生命周期由调用方兜底）。
     */
    private static Path resolveImageDir(KbParseContext ctx) {
        try {
            Path dir = ctx != null && ctx.workDir() != null
                ? ctx.workDir()
                : Files.createTempDirectory("kb-paddleocr-vl-");
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new KbParseException("PaddleOCR-VL 创建图片目录失败: " + e.getMessage(), e);
        }
    }

    /** 解一页的 {@code markdown.images}（base64）到磁盘，返回图片引用。 */
    private List<KbImageRef> decodeImages(JsonNode imagesNode, int pageNo, Path imageDir, String fileName) {
        List<KbImageRef> refs = new ArrayList<>();
        String stem = fileStem(fileName);
        for (int i = 0; i < imagesNode.size(); i++) {
            String base64 = base64Of(imagesNode.get(i));
            if (base64.isEmpty()) {
                log.warn("KbPaddleOcrVlParser: 图片项不含 base64 file={} page={} index={}", fileName, pageNo, i + 1);
                continue;
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                log.warn("KbPaddleOcrVlParser: 图片 base64 解码失败，跳过 file={} page={} index={} 原因={}",
                    fileName, pageNo, i + 1, e.getMessage());
                continue;
            }
            String ext = imageExtensionOf(data);
            if (ext == null) {
                log.warn("KbPaddleOcrVlParser: 图片格式无法识别，跳过 file={} page={} index={} 字节={}",
                    fileName, pageNo, i + 1, data.length);
                continue;
            }
            Path out = imageDir.resolve(stem + "-p" + pageNo + "-" + (i + 1) + "." + ext);
            try {
                Files.write(out, data);
            } catch (IOException e) {
                log.warn("KbPaddleOcrVlParser: 图片落盘失败，跳过 file={} page={} path={} 原因={}",
                    fileName, pageNo, out, e.getMessage());
                continue;
            }
            refs.add(KbImageRef.of(pageNo, out.toAbsolutePath().toString(), KIND_OCR_LAYOUT));
        }
        return refs;
    }

    /**
     * 从图片节点取 base64 字符串。
     *
     * <p>兼容三种形态：纯字符串、{@code {"base64": "..."}} / {@code {"data": "..."}} 对象、
     * 带 {@code data:image/png;base64,} 前缀的 data URI。
     */
    private static String base64Of(JsonNode node) {
        String value = null;
        if (node == null) {
            return "";
        }
        if (node.isTextual()) {
            value = node.asText();
        } else if (node.isObject()) {
            for (String field : new String[]{"base64", "data", "imageBase64", "image"}) {
                JsonNode candidate = node.get(field);
                if (candidate != null && candidate.isTextual()) {
                    value = candidate.asText();
                    break;
                }
            }
        }
        if (value == null) {
            return "";
        }
        String v = value.trim();
        int comma = v.indexOf("base64,");
        if (v.startsWith("data:") && comma >= 0) {
            v = v.substring(comma + "base64,".length());
        }
        return v.replaceAll("\\s+", "");
    }

    /** 按魔数判图片扩展名；认不出返回 null（⛔ 不写一个扩展名错误的垃圾文件）。 */
    static String imageExtensionOf(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "gif";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        boolean littleTiff = (data[0] & 0xFF) == 0x49 && (data[1] & 0xFF) == 0x49
            && data[2] == 0x2A && data[3] == 0x00;
        boolean bigTiff = (data[0] & 0xFF) == 0x4D && (data[1] & 0xFF) == 0x4D
            && data[2] == 0x00 && data[3] == 0x2A;
        if (littleTiff || bigTiff) {
            return "tiff";
        }
        return null;
    }

    /** 临时文件名主干（不含扩展名，去掉路径分隔符与文件系统非法字符）。 */
    private static String fileStem(String fileName) {
        String name = fileName == null || fileName.isEmpty() ? "document" : fileName;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String safe = stem.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return safe.isEmpty() ? "document" : safe;
    }

    /** 小写扩展名（供日志/测试使用；不放宽判定）。 */
    static String extensionOf(String fileName) {
        String ext = KbParsers.extensionOf(fileName);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}

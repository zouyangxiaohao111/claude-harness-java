package com.nexusai.application.session;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 产物文件类型判定 · <b>单一真源</b>（扩展名 → 文本类 / 二进制类 + MIME）。
 *
 * <p><b>WHY 必须是单一真源</b>：产物有三个出口 —— {@code GET /content}（文本预览）、
 * {@code GET /raw}（原始字节下发）、{@code PUT /content}（文本编辑写回）。三者对「这是不是
 * 文本类」「这个扩展名该给什么 MIME」的判断必须完全一致：
 * <ul>
 *   <li>判定分散在三处各写一份 if/else ⇒ {@code /content} 说「是文本」、{@code PUT} 说
 *       「是二进制」⇒ 用户在编辑器里保存就把 docx 写成了纯文本（文件当场损坏）；</li>
 *   <li>MIME 表与文本表各写一份 ⇒ 前端按 {@code text/markdown} 判定渲染、后端按另一张表
 *       给 {@code Content-Type} ⇒ 同名文件在不同端点拿到不同类型。</li>
 * </ul>
 *
 * <p><b>判定口径</b>（三类）：
 * <ul>
 *   <li><b>文本类</b>（{@link #TEXT}）：能按 UTF-8（回落 GB18030）解码 ⇒ 可预览、可编辑写回。
 *       SVG 归文本类（它是 XML 文本），但 MIME 仍是 {@code image/svg+xml}（浏览器要按图片渲染）。</li>
 *   <li><b>二进制类</b>（{@link #BINARY}）：⛔ 不下发内容、⛔ 不支持文本编辑，只能经 {@code /raw}
 *       取原始字节（docx / xlsx / pptx / pdf / 图片 / 音视频 / 压缩包 …）。</li>
 *   <li><b>未知扩展名</b> ⇒ 一律按二进制类 + {@code application/octet-stream}（保守：宁可不给
 *       预览，也不把未知二进制当文本写坏用户文件）。</li>
 * </ul>
 *
 * <p><b>与前端 {@code standalonePreview} 的 PreviewType 对齐</b>：前端把产物按扩展名分成
 * {@code html / pdf / docx / xlsx / image / video / audio / file} 八类渲染（见
 * {@code front/src/utils/standalonePreview.ts}）。本类的二进制扩展名集合按那套判定口径覆盖
 * （含 {@code avif} / {@code mp4} / {@code mp3} …），保证「前端认得能渲染的类型 ⇒ 后端给的
 * Content-Type 也是浏览器认得的那种」。
 *
 * <p>⛔ 本类是<b>纯判定</b>，不碰文件、不碰 DB（规则五：确定性归代码，不归模型）。
 */
public enum ArtifactFileKind {

    /** 文本类：可预览（{@code /content} 下发内容）+ 可编辑写回（{@code PUT /content}）。 */
    TEXT,

    /** 二进制类：⛔ 不下发内容、⛔ 不支持文本编辑；只能经 {@code /raw} 取原始字节。 */
    BINARY;

    /**
     * 文本类扩展名集合（小写、不含点）。
     *
     * <p>⚠️ 覆盖面刻意比「常见后缀」宽：老仓库里的交付物常是 {@code .tsv / .jsonl / .ini /
     * .properties / .scss / .kt / .rb / .go / .rs / .c / .h / .cpp / .hpp / .rst} 这类，
     * 漏一个就等于「该文件在编辑器里打不开」。
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "text", "log", "csv", "tsv",
            "html", "htm", "xml", "json", "jsonl", "yaml", "yml", "toml", "ini", "properties",
            "js", "jsx", "ts", "tsx", "css", "scss", "less", "java", "kt", "py", "rb", "go", "rs",
            "c", "h", "cpp", "hpp", "sh", "bat", "ps1", "sql", "rst", "svg",
            "vue", "svelte", "graphql", "gql", "tex", "bib", "diff", "patch", "env", "conf", "cfg");

    /**
     * 二进制类扩展名集合（小写、不含点）。
     *
     * <p>Office / PDF / 图片 / 音视频 / 字体 / 压缩包 —— 这些走 {@code /raw} 原始字节通道。
     * 图片与音视频的清单对齐前端 {@code previewKindOfAttachment} / {@code previewKindOfPath}
     * 的判定正则（{@code png|jpe?g|gif|webp|bmp|ico|avif} / {@code mp4|webm|mov|m4v|avi|mkv} /
     * {@code mp3|wav|m4a|ogg|aac|flac}）—— 前端认得 ⇒ 后端必须给对应 MIME。
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf",
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tiff", "avif", "heic",
            "mp4", "webm", "mov", "m4v", "avi", "mkv",
            "mp3", "wav", "m4a", "ogg", "aac", "flac",
            "woff", "woff2", "ttf", "otf", "eot",
            "zip", "rar", "7z", "gz", "tar", "epub");

    /**
     * MIME 覆盖表（扩展名 → MIME）。
     *
     * <p>只写「与默认值不一样」的条目：文本类默认 {@code text/plain}，二进制类默认
     * {@code application/octet-stream}。
     */
    private static final Map<String, String> MIME_OVERRIDES = Map.ofEntries(
            // ── 文本类 ──
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("json", "application/json"),
            Map.entry("jsonl", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("yaml", "text/yaml"),
            Map.entry("yml", "text/yaml"),
            Map.entry("css", "text/css"),
            Map.entry("scss", "text/css"),
            Map.entry("less", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("jsx", "text/javascript"),
            Map.entry("ts", "text/javascript"),
            Map.entry("tsx", "text/javascript"),
            Map.entry("sh", "text/x-shellscript"),
            Map.entry("bat", "text/x-shellscript"),
            Map.entry("ps1", "text/x-shellscript"),
            Map.entry("py", "text/x-python"),
            Map.entry("java", "text/x-java-source"),
            Map.entry("sql", "text/x-sql"),
            // SVG 是文本（XML），但浏览器要按图片渲染
            Map.entry("svg", "image/svg+xml"),
            // ── 二进制类：Office / PDF ──
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("pdf", "application/pdf"),
            // ── 二进制类：图片 ──
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("avif", "image/avif"),
            Map.entry("heic", "image/heic"),
            // ── 二进制类：音视频（对齐前端 PreviewType 的 video / audio）──
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("m4v", "video/x-m4v"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("aac", "audio/aac"),
            Map.entry("flac", "audio/flac"),
            // ── 二进制类：字体 / 压缩包 ──
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("zip", "application/zip"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("gz", "application/gzip"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("epub", "application/epub+zip"));

    /** 文本类默认 MIME。 */
    private static final String DEFAULT_TEXT_MIME = "text/plain";

    /** 二进制类默认 MIME。 */
    private static final String DEFAULT_BINARY_MIME = "application/octet-stream";

    /**
     * 扩展名 → 类别。
     *
     * @param ext 扩展名（小写、不含点）；null / 空 → {@link #BINARY}（未知一律当二进制）
     */
    public static ArtifactFileKind of(String ext) {
        String key = normalize(ext);
        if (TEXT_EXTENSIONS.contains(key)) {
            return TEXT;
        }
        if (BINARY_EXTENSIONS.contains(key)) {
            return BINARY;
        }
        // 未知扩展名 → 一律按二进制（保守：宁可不给预览，也不把未知二进制当文本写坏用户文件）
        return BINARY;
    }

    /**
     * 扩展名 → MIME（不含 charset 参数）。
     *
     * <p>⚠️ {@code /content} 的 {@code mime} 字段用这个值（前端按 {@code text/markdown} /
     * {@code text/html} 判渲染，前端契约里没有 charset ⇒ 这里也不加）。
     *
     * @param ext 扩展名（小写、不含点）
     */
    public static String mimeOf(String ext) {
        String key = normalize(ext);
        String override = MIME_OVERRIDES.get(key);
        if (override != null) {
            return override;
        }
        return of(key) == TEXT ? DEFAULT_TEXT_MIME : DEFAULT_BINARY_MIME;
    }

    /**
     * 扩展名 → HTTP {@code Content-Type}（{@code /raw} 用）。
     *
     * <p>与 {@link #mimeOf(String)} 的差别只有一处：<b>{@code text/*} 追加 {@code charset=utf-8}</b>。
     * WHY：{@code /raw} 是给浏览器直接打开的通道，HTML / Markdown 无 charset 时浏览器按本地编码猜
     * （中文 Windows 常见 GBK）⇒ 页面乱码。二进制（{@code image/*} / {@code application/*}）不加
     * charset：加了对二进制无意义，{@code image/svg+xml} 也要按图片渲染。
     *
     * @param ext 扩展名（小写、不含点）
     */
    public static String contentTypeOf(String ext) {
        String mime = mimeOf(ext);
        if (mime.startsWith("text/")) {
            return mime + "; charset=utf-8";
        }
        return mime;
    }

    /**
     * 是否文本类（可预览 / 可编辑写回）。
     *
     * @param ext 扩展名（小写、不含点）
     */
    public static boolean isText(String ext) {
        return of(ext) == TEXT;
    }

    /**
     * 文件名 → 扩展名（小写、不含点）；无扩展名 / 目录式结尾（{@code a.b.}）→ 空串。
     *
     * @param fileName 文件名（可含路径，取最后一段）
     */
    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 扩展名归一（小写、去空白、去前导点）。 */
    private static String normalize(String ext) {
        if (ext == null) {
            return "";
        }
        String trimmed = ext.trim();
        while (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}

package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown / HTML 里的图片引用抽取器 · 解析后把「只存在于正文里」的图落回磁盘。
 *
 * <p><b>为什么需要它</b>：解析引擎只吐 Markdown 时，图片的二进制不会有任何落盘产物
 * —— 正文里只留下 {@code ![](images/xxx.png)} / {@code data:image/...;base64,...} /
 * {@code <img src="...">} 这类<b>引用</b>。入库链的 {@code parsed.images()} 因此为空，
 * 分块投影里也就没有图（源侧靠 {@code ImageResolver} 在解析后统一补这一步）。
 *
 * <h2>对齐源侧，但只做「抽」，不做「改」</h2>
 * 源侧 {@code image_resolver.go:104-202} 的顺序是
 * {@code UnwrapLinkedImages → ResolveDataURIImages → ResolveHTMLDataURIImages →
 * ResolveBareBase64Content → 相对路径引用 → ResolveRemoteImages}，
 * 并且会把正文里的引用<b>原地替换</b>成 {@code provider://} URL。
 * <b>本仓不做替换</b>：分块坐标（{@code KbChunk.start/end}）与溯源定位都依赖原文的字符偏移，
 * 一旦重写正文，所有 {@code start/end} 都会漂移。故本类只返回
 * {@link KbImageRef} 列表，<b>入参 markdown 一个字符都不改</b>。
 *
 * <h2>四种来源（按源侧顺序）</h2>
 * <ol>
 *   <li>markdown 图片 data URI：{@code ![alt](data:image/png;base64,...)}</li>
 *   <li>HTML {@code <img>} 的 data URI：{@code <img src="data:image/png;base64,...">}</li>
 *   <li><b>裸 base64</b>（部分实现会丢掉 {@code data:image/<mime>} 前缀，只留 {@code base64,<payload>}）：
 *       本类只认带 {@code base64,} 标记的那一种，且解码后必须命中图片魔数才收；
 *       ⛔ 完全没有标记的纯 base64 长串<b>不抽</b> —— 它与哈希/签名/密钥串在字形上不可区分，
 *       强行抽会把文档里的普通文本误判成图片（这是有意的取舍，源侧也只做了
 *       {@code base64,} 前缀这一档）。</li>
 *   <li>相对路径：{@code ![](img/a.png)} / {@code <img src="img/a.png">}，
 *       相对 {@code sourceFile} 所在目录解析，<b>文件存在才用</b>；
 *       ⛔ 逃出该目录（{@code ../}）的引用一律不收（避免把任意本地文件读进知识库）。</li>
 *   <li>远程 http(s)：下载落盘。连接 5s / 请求 15s / 单图 20MB 上限；
 *       ⛔ 内网地址（127/8、10/8、172.16-31/12、192.168/16、169.254/16、localhost、
 *       IPv6 回环与 fc00::/7）直接拒绝；失败只 WARN 跳过。</li>
 * </ol>
 *
 * <h2>落盘</h2>
 * 写 {@code <workDir>/images/md-<序号>-<内容hash前8位>.<ext>}，<b>同内容只写一次</b>
 * （按完整 SHA-256 去重；文件名只带前 8 位便于人读）。
 *
 * <h2>fail-soft</h2>
 * 任何一张图读/写/解码失败都只 WARN，绝不让整篇解析失败；调用方另有兜底 catch。
 * 图标过滤（小于 512B、宽高均小于 64px）不在这里做 —— 那一步由
 * {@code KbImageStorage.store} 在持久化时统一判断，避免两处阈值漂移。
 *
 * <p><b>无状态</b>：全部方法静态，实例化无意义（私有构造器）。
 */
public final class KbMarkdownImageResolver {

    private static final Logger log = LoggerFactory.getLogger(KbMarkdownImageResolver.class);

    /**
     * 本类产出的图片 kind。
     *
     * <p>⚠️ {@link KbImageRef} 目前只有 {@code pdf-page / pdf-embedded / office-embedded}
     * 三个 kind，而本批被限定「不改其他文件」⇒ 这里<b>别名</b>到通用非 PDF kind
     * {@link KbImageRef#KIND_OFFICE_EMBEDDED}。存储文件名统一以 {@code md-} 开头，
     * 需要区分来源时看文件名即可；下游 {@code kb_chunk.image_info[].attrs.source_kind}
     * 只认这三个值，别名保证它不会被写进一个前端不认识的新值。
     */
    public static final String KIND_MARKDOWN = KbImageRef.KIND_OFFICE_EMBEDDED;

    /** 单张图上限（远程下载与 data URI 解码共用；源侧 {@code maxRemoteImageSize=10MB}，本仓放宽到 20MB）。 */
    static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    /** 单文档远程图上限（源侧 {@code maxRemoteImages=30}）。 */
    static final int MAX_REMOTE_IMAGES = 30;

    /** 单文档内联图上限（源侧 {@code maxInlineImagesPerDocument=1000}）。 */
    static final int MAX_INLINE_IMAGES = 1000;

    /** 连接超时（源侧只有整体 15s；连接单独给 5s，避免坏域名拖住整篇入库）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 请求超时（源侧 {@code remoteImageFetchTimeout=15s}）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** markdown 图片 data URI：{@code ![alt](data:image/png;base64,....)}（支持 {@code <...>} 包裹）。 */
    private static final Pattern MD_IMAGE_DATA_URI = Pattern.compile(
        "!\\[([^\\]]*)\\]\\(\\s*<?(data:image/([^;,\\s]+);base64,([A-Za-z0-9+/=\\r\\n\\t ]*?))>?\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    /** HTML {@code <img src="data:image/...">}（单/双引号）。 */
    private static final Pattern HTML_IMG_DATA_URI = Pattern.compile(
        "(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"'](data:image/([^;,\\s\"']+);base64,([^\"']*?))[\"'][^>]*>");

    /** 裸 base64：只认 {@code base64,<payload>}（前面不是 {@code ;} 或单词字符，避免重复命中完整 data URI）。 */
    private static final Pattern BARE_BASE64 = Pattern.compile(
        "(?<![;:\\w])base64,[ \\t\\r\\n]*([A-Za-z0-9+/=\\r\\n\\t ]{32,})",
        Pattern.CASE_INSENSITIVE);

    /** markdown 图片（通用）：{@code ![alt](target "title")}，target 允许 {@code <>} 包裹。 */
    private static final Pattern MD_IMAGE = Pattern.compile(
        "!\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)");

    /** HTML {@code <img ... src="...">}（单/双引号）。 */
    private static final Pattern HTML_IMG_SRC = Pattern.compile(
        "(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");

    /** HTML 标签里的 {@code alt} 属性（caption）。 */
    private static final Pattern HTML_ALT = Pattern.compile(
        "(?is)\\balt\\s*=\\s*[\"']([^\"']*)[\"']");

    /** 远程下载客户端（惰性创建；不可变，可安全共享）。 */
    private static volatile HttpClient httpClient;

    private KbMarkdownImageResolver() {
    }

    /**
     * 从 markdown / HTML 正文里抽图片引用并落盘。
     *
     * @param markdown   解析产物正文（可能为 null / 空）
     * @param sourceFile 原始文件（相对路径以它所在目录为基准；可为 null ⇒ 跳过相对路径分支）
     * @param workDir    本次解析的临时目录（图片写 {@code <workDir>/images/}）
     * @return 落盘后的图片引用（{@code pageIndex=0}，{@code caption=alt}，按在正文中出现位置升序）
     */
    public static List<KbImageRef> resolve(String markdown, Path sourceFile, Path workDir) {
        if (markdown == null || markdown.isEmpty() || workDir == null) {
            return List.of();
        }
        Stats stats = new Stats();
        Sink sink = new Sink(workDir.resolve("images"));
        List<Found> found = new ArrayList<>();

        // ── a. markdown data URI ──────────────────────────────────────
        Matcher mdData = MD_IMAGE_DATA_URI.matcher(markdown);
        while (mdData.find()) {
            stats.markdownDataUri++;
            byte[] data = decodePayload(mdData.group(4), "markdown data URI", stats);
            if (data == null) {
                continue;
            }
            addInline(found, mdData.start(), data, extensionForMime(mdData.group(3)), mdData.group(1), sink, stats);
        }

        // ── b. HTML img data URI ──────────────────────────────────────
        Matcher htmlData = HTML_IMG_DATA_URI.matcher(markdown);
        while (htmlData.find()) {
            stats.htmlDataUri++;
            byte[] data = decodePayload(htmlData.group(3), "HTML img data URI", stats);
            if (data == null) {
                continue;
            }
            String alt = altOfTag(htmlData.group(), "");
            addInline(found, htmlData.start(), data, extensionForMime(htmlData.group(2)), alt, sink, stats);
        }

        // ── c. 裸 base64（带 base64, 标记） ───────────────────────────
        Matcher bare = BARE_BASE64.matcher(markdown);
        while (bare.find()) {
            stats.bare++;
            byte[] data = decodePayload(bare.group(1), "bare base64", stats);
            if (data == null) {
                continue;
            }
            // 没有 mime 声明 ⇒ 必须命中图片魔数才收（否则会把任意 base64 文本当图）
            String mime = sniffImageMime(data);
            if (mime == null) {
                stats.skipped++;
                continue;
            }
            addInline(found, bare.start(), data, extensionForMime(mime), "", sink, stats);
        }

        // ── d. 相对路径（markdown + HTML） ────────────────────────────
        Path baseDir = baseDirOf(sourceFile);
        Matcher mdRel = MD_IMAGE.matcher(markdown);
        while (mdRel.find()) {
            String target = stripAngle(mdRel.group(2));
            if (!isLocalReference(target)) {
                continue;
            }
            stats.relative++;
            Path local = resolveRelative(target, baseDir);
            if (local == null) {
                continue;
            }
            addLocal(found, mdRel.start(), local, mdRel.group(1), sink, stats);
        }
        Matcher htmlRel = HTML_IMG_SRC.matcher(markdown);
        while (htmlRel.find()) {
            String src = stripAngle(htmlRel.group(1));
            if (!isLocalReference(src)) {
                continue;
            }
            stats.relative++;
            Path local = resolveRelative(src, baseDir);
            if (local == null) {
                continue;
            }
            addLocal(found, htmlRel.start(), local, altOfTag(htmlRel.group(), ""), sink, stats);
        }

        // ── e. 远程 http(s) ──────────────────────────────────────────
        Map<String, KbImageRef> byRemoteUrl = new LinkedHashMap<>();
        int remoteCount = 0;
        Matcher mdRemote = MD_IMAGE.matcher(markdown);
        while (mdRemote.find()) {
            String url = stripAngle(mdRemote.group(2));
            if (!isRemoteUrl(url)) {
                continue;
            }
            stats.remote++;
            if (remoteCount >= MAX_REMOTE_IMAGES) {
                stats.skipped++;
                continue;
            }
            Found remote = fetchRemote(mdRemote.start(), url, mdRemote.group(1), sink, stats, byRemoteUrl);
            if (remote != null) {
                remoteCount++;
                found.add(remote);
            }
        }
        Matcher htmlRemote = HTML_IMG_SRC.matcher(markdown);
        while (htmlRemote.find()) {
            String url = stripAngle(htmlRemote.group(1));
            if (!isRemoteUrl(url)) {
                continue;
            }
            stats.remote++;
            if (remoteCount >= MAX_REMOTE_IMAGES) {
                stats.skipped++;
                continue;
            }
            Found remote = fetchRemote(htmlRemote.start(), url,
                altOfTag(htmlRemote.group(), ""), sink, stats, byRemoteUrl);
            if (remote != null) {
                remoteCount++;
                found.add(remote);
            }
        }

        // 按正文出现位置排序（分块归属按图片顺序对齐占位，顺序错会让图挂错块）
        found.sort(Comparator.comparingInt(Found::offset));

        // 同一张图被引用多次 ⇒ 只保留一条（文件已由 Sink 按内容 hash 去重）
        Map<String, KbImageRef> byPath = new LinkedHashMap<>();
        for (Found f : found) {
            byPath.putIfAbsent(f.ref().imagePath(), f.ref());
        }
        List<KbImageRef> refs = new ArrayList<>(byPath.values());

        log.info("[图片] markdown 引用抽取 file={} data-uri={} html-data-uri={} bare={} relative={} remote={}"
                + " 落盘={} 去重={} 跳过={} 结果={}",
            fileNameOf(sourceFile), stats.markdownDataUri, stats.htmlDataUri, stats.bare,
            stats.relative, stats.remote, sink.writes, sink.dedupes, stats.skipped, refs.size());
        return refs;
    }

    /** 内联字节落盘（data URI / 裸 base64）。 */
    private static void addInline(List<Found> found, int offset, byte[] data, String ext,
                                  String caption, Sink sink, Stats stats) {
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            stats.skipped++;
            return;
        }
        if (sink.total() >= MAX_INLINE_IMAGES) {
            stats.skipped++;
            return;
        }
        try {
            Path path = sink.put(data, ext);
            found.add(new Found(offset, new KbImageRef(0, path.toString(), caption, KIND_MARKDOWN)));
        } catch (IOException | RuntimeException e) {
            stats.skipped++;
            log.warn("[图片] 内联图落盘失败，跳过 caption={} 原因={}", caption, e.toString());
        }
    }

    /** 相对路径文件落盘。 */
    private static void addLocal(List<Found> found, int offset, Path local, String caption,
                                 Sink sink, Stats stats) {
        try {
            if (!Files.isRegularFile(local)) {
                stats.skipped++;
                return;
            }
            if (Files.size(local) > MAX_IMAGE_BYTES) {
                stats.skipped++;
                log.warn("[图片] 相对路径图片超过 {} 字节上限，跳过 path={}", MAX_IMAGE_BYTES, local);
                return;
            }
            byte[] data = Files.readAllBytes(local);
            String ext = extensionForPath(local.toString());
            if (ext == null) {
                ext = extensionForMime(sniffImageMime(data));
            }
            Path stored = sink.put(data, ext);
            found.add(new Found(offset, new KbImageRef(0, stored.toString(), caption, KIND_MARKDOWN)));
        } catch (IOException | RuntimeException e) {
            stats.skipped++;
            log.warn("[图片] 相对路径图片落盘失败，跳过 path={} 原因={}", local, e.toString());
        }
    }

    /** 远程图片下载落盘（同 URL 只下一次）。 */
    private static Found fetchRemote(int offset, String url, String caption, Sink sink,
                                     Stats stats, Map<String, KbImageRef> cache) {
        KbImageRef cached = cache.get(url);
        if (cached != null) {
            return new Found(offset, cached);
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            stats.skipped++;
            log.warn("[图片] 远程图片 URL 非法，跳过 url={} 原因={}", url, e.toString());
            return null;
        }
        String blocked = blockedReason(uri);
        if (blocked != null) {
            stats.skipped++;
            log.warn("[图片] 远程图片被拒（{}），跳过 url={}", blocked, url);
            return null;
        }
        try {
            Downloaded downloaded = download(uri);
            String ext = extensionForMime(downloaded.contentType());
            if (ext == null) {
                ext = extensionForPath(uri.getPath());
            }
            if (ext == null) {
                ext = extensionForMime(sniffImageMime(downloaded.data()));
            }
            Path path = sink.put(downloaded.data(), ext);
            KbImageRef ref = new KbImageRef(0, path.toString(), caption, KIND_MARKDOWN);
            cache.put(url, ref);
            return new Found(offset, ref);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.skipped++;
            log.warn("[图片] 远程图片下载被中断，跳过 url={}", url);
            return null;
        } catch (IOException | RuntimeException e) {
            stats.skipped++;
            log.warn("[图片] 远程图片下载失败，跳过 url={} 原因={}", url, e.toString());
            return null;
        }
    }

    /** 解 base64（容忍换行/空白的 MIME 变体，再试 URL-safe 变体）；解码后超过上限或为空返回 null。 */
    private static byte[] decodePayload(String payload, String source, Stats stats) {
        if (payload == null) {
            stats.skipped++;
            return null;
        }
        String clean = payload.replaceAll("[\\s]", "");
        if (clean.isEmpty()) {
            stats.skipped++;
            return null;
        }
        byte[] data = null;
        try {
            data = Base64.getMimeDecoder().decode(clean);
        } catch (IllegalArgumentException ignored) {
            // 落到 URL-safe 变体
        }
        if (data == null) {
            try {
                data = Base64.getUrlDecoder().decode(clean);
            } catch (IllegalArgumentException e) {
                stats.skipped++;
                log.warn("[图片] {} base64 解码失败，跳过 长度={}", source, clean.length());
                return null;
            }
        }
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
            stats.skipped++;
            return null;
        }
        return data;
    }

    /** MIME → 扩展名（不含点）；未识别返回 null。 */
    static String extensionForMime(String mime) {
        if (mime == null) {
            return null;
        }
        return switch (mime.trim().toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp", "image/x-ms-bmp" -> "bmp";
            case "image/tiff", "image/tif" -> "tiff";
            case "image/svg+xml" -> "svg";
            default -> null;
        };
    }

    /** 路径后缀 → 扩展名（只认常见位图后缀）；未识别返回 null。 */
    static String extensionForPath(String path) {
        if (path == null) {
            return null;
        }
        String name = path;
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        int f = name.indexOf('#');
        if (f >= 0) {
            name = name.substring(0, f);
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png", "gif", "webp", "bmp", "tif", "tiff", "svg" -> ext;
            case "jpg", "jpeg" -> "jpg";
            default -> null;
        };
    }

    /** 按魔数识别位图 MIME；识别不出返回 null。 */
    static String sniffImageMime(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "image/gif";
        }
        if (data.length >= 12
            && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "image/bmp";
        }
        return null;
    }

    /** 去掉 markdown target 的 {@code <>} 包裹。 */
    static String stripAngle(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() >= 2 && t.charAt(0) == '<' && t.charAt(t.length() - 1) == '>') {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** 是否「相对本地路径」候选（非 data、非 http(s)、非 provider 协议、非纯锚点）。 */
    static boolean isLocalReference(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("http://") || lower.startsWith("https://")
            || lower.startsWith("//") || lower.startsWith("#")) {
            return false;
        }
        if (isProviderScheme(lower)) {
            return false;
        }
        // 其它 scheme（mailto: / javascript: / blob: …）不是文档内相对路径
        return !lower.matches("^[a-z][a-z0-9+.\\-]*:.*");
    }

    /** 绝对 http(s) URL。 */
    static boolean isRemoteUrl(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 对象存储协议（源侧 {@code isProviderScheme}）。 */
    static boolean isProviderScheme(String raw) {
        if (raw == null) {
            return false;
        }
        String t = raw.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"local://", "minio://", "cos://", "tos://", "s3://", "obs://"}) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 原始文件所在目录（绝对、规范化）；取不到返回 null。 */
    private static Path baseDirOf(Path sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        try {
            Path abs = sourceFile.toAbsolutePath().normalize();
            return abs.getParent();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 相对引用 → 磁盘文件（必须存在）。
     *
     * <p>先试 URL 解码后的路径（{@code %20} 这类），再试原样；两种都必须落在
     * {@code baseDir} 之内 —— ⛔ {@code ../../etc/passwd} 这类越界引用直接丢弃。
     */
    static Path resolveRelative(String raw, Path baseDir) {
        if (baseDir == null || raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        // 去掉查询串/锚点（图片引用偶尔带版本号）
        int cut = t.length();
        int q = t.indexOf('?');
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        int f = t.indexOf('#');
        if (f >= 0) {
            cut = Math.min(cut, f);
        }
        t = t.substring(0, cut);
        String decoded;
        try {
            decoded = URLDecoder.decode(t, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = t;
        }
        Path direct = containedRegularFile(baseDir, decoded);
        if (direct != null) {
            return direct;
        }
        return containedRegularFile(baseDir, t);
    }

    private static Path containedRegularFile(Path baseDir, String ref) {
        try {
            Path candidate = baseDir.resolve(ref).normalize();
            if (!candidate.startsWith(baseDir)) {
                return null;
            }
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 从 HTML 标签文本里取 {@code alt} 属性。 */
    private static String altOfTag(String tag, String fallback) {
        if (tag == null) {
            return fallback;
        }
        Matcher m = HTML_ALT.matcher(tag);
        return m.find() ? m.group(1) : fallback;
    }

    /** 判定远程 URL 是否指向内网/本机；返回拒绝原因，通过返回 null。 */
    static String blockedReason(URI uri) {
        if (uri == null) {
            return "空 URI";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "非 http(s)";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "缺少主机名";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return "localhost";
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            return "主机名无法解析";
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                return "内网地址 " + address.getHostAddress();
            }
        }
        return null;
    }

    /** 本机 / 内网 / 链路本地 / 组播地址判定。 */
    static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 16) {
            // fc00::/7（唯一本地地址）· Java 的 isSiteLocalAddress 只认已废弃的 fec0::/10
            return (b[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    private static HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (KbMarkdownImageResolver.class) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                    httpClient = c;
                }
            }
        }
        return c;
    }

    /**
     * 下载一张图（限 20MB；Content-Type 不是图片时按魔数兜底判定）。
     *
     * <p>⚠️ Java HttpClient 没有「读超时」这一项：{@link HttpRequest#timeout} 是整次交换的上限，
     * 这里用它覆盖「响应 + 读体」的 15s 预算；连接单独由 5s 的 connectTimeout 限制。
     */
    private static Downloaded download(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            // 部分 CDN 只认浏览器 UA（源侧同样设置了 UA）
            .header("User-Agent", "Mozilla/5.0 (compatible; NexusAI/1.0)")
            .GET()
            .build();
        HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            byte[] data = readLimited(in);
            String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
            int semi = mime.indexOf(';');
            if (semi >= 0) {
                mime = mime.substring(0, semi).trim();
            }
            if (!mime.startsWith("image/")) {
                // 不是 image/*（含 application/octet-stream）⇒ 只认能嗅出位图魔数的字节
                String sniffed = sniffImageMime(data);
                if (sniffed == null) {
                    throw new IOException("下载内容不是图片 Content-Type=" + contentType);
                }
                mime = sniffed;
            }
            return new Downloaded(data, mime);
        }
    }

    /** 按上限读流（超限直接失败，不把整份大文件读进内存）。 */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > MAX_IMAGE_BYTES) {
                throw new IOException("图片超过 " + MAX_IMAGE_BYTES + " 字节上限");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** 源文件名（仅用于日志）。 */
    private static String fileNameOf(Path sourceFile) {
        if (sourceFile == null) {
            return "";
        }
        Path name = sourceFile.getFileName();
        return name == null ? "" : name.toString();
    }

    /** 内容 SHA-256（小写 hex）。 */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境被改坏 ⇒ 让调用方 fail-soft 掉这张图
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 内部数据结构
    // ════════════════════════════════════════════════════════════════════════

    /** 一条命中：正文里的字符偏移 + 落盘后的引用（排序用）。 */
    private record Found(int offset, KbImageRef ref) {
    }

    /** 一次远程下载的结果。 */
    private record Downloaded(byte[] data, String contentType) {
    }

    /** 各来源命中计数（只用于一行 info 日志）。 */
    private static final class Stats {
        private int markdownDataUri;
        private int htmlDataUri;
        private int bare;
        private int relative;
        private int remote;
        private int skipped;
    }

    /**
     * 落盘器 · 按内容 hash 去重。
     *
     * <p>文件名 {@code md-<序号>-<hash前8位>.<ext>}：序号便于人读顺序，hash 前缀便于一眼看出
     * 「这两条引用是不是同一张图」。去重键用<b>完整 SHA-256</b>（不是前 8 位）——
     * 前 8 位只用于命名，32 bit 碰撞在万张量级仍有概率，拿它去重会静默把两张不同的图合成一张。
     */
    private static final class Sink {

        private final Path dir;
        private final Map<String, Path> byHash = new LinkedHashMap<>();
        private int seq;
        private int writes;
        private int dedupes;

        private Sink(Path dir) {
            this.dir = dir;
        }

        /** 已处理的图片条数（写入 + 去重），用于单文档上限。 */
        private int total() {
            return writes + dedupes;
        }

        /** 落盘一张图；同内容已在本次解析里写过则直接返回原路径。 */
        private Path put(byte[] data, String ext) throws IOException {
            String hash = sha256Hex(data);
            Path existing = byHash.get(hash);
            if (existing != null) {
                dedupes++;
                return existing;
            }
            Files.createDirectories(dir);
            seq++;
            String fileName = String.format(Locale.ROOT, "md-%04d-%s.%s",
                seq, hash.substring(0, 8), normalizeExtension(ext));
            Path file = dir.resolve(fileName).toAbsolutePath();
            Files.write(file, data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            byHash.put(hash, file);
            writes++;
            return file;
        }

        /** 扩展名兜底：只放字母数字，最长 5 位；拿不到就 {@code png}（与 KbImageStorage 的兜底一致）。 */
        private static String normalizeExtension(String ext) {
            if (ext == null) {
                return "png";
            }
            String e = ext.trim().toLowerCase(Locale.ROOT);
            if (e.startsWith(".")) {
                e = e.substring(1);
            }
            if (e.isEmpty() || e.length() > 5 || !e.matches("[a-z0-9]+")) {
                return "png";
            }
            return e;
        }
    }
}

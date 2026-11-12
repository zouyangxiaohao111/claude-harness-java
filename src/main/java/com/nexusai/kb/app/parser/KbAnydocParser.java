package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * anydoc 解析器 · 仅供 {@code parser_engine_rules} <b>显式点名</b>的 Office 解析引擎。
 *
 * <p><b>2026-11-12 用户裁定</b>：本仓 Office 类格式<b>默认改走 Tika</b>（{@link KbOfficeParser}），
 * anydoc 不再默认接管任何扩展名。理由是用户需要 <b>source block</b>（页码/段落/行号/单元格溯源）
 * 与<b>内嵌图片</b>，而 anydoc 只出 markdown、不提供结构（{@code to_markdown} 连图片引用都不产）。
 * 本类保留的用途：知识库配置里用 {@code anydoc} / {@code KbAnydocParser} 点名时仍能工作，
 * 故 {@link #engineFamilyNames()} 与 {@link #parse} 的完整能力（markdown + 内嵌图片）都保留。
 *
 * <p><b>为什么默认不选它（能力取舍）</b>：anydoc 直接从文档模型出<b>结构化 GFM Markdown</b>
 * （标题层级、列表标记、合并单元格、脚注都天然保留），markdown 质量优于 Tika 的 XHTML 往返；
 * 但它<b>不产出结构定位</b>（没有 source block），下游引用无法指回原文。需要最高 markdown 质量
 * 且不要求溯源时，可在 {@code parser_engine_rules} 里显式点名 {@code anydoc}。
 *
 * <p><b>为什么不接管 PDF（{@code ERR_PDF_NO_MODEL=7}）</b>：anydoc 对 PDF 没有 document model，
 * 只能走 {@code pdf-inspector} 出<b>扁平</b> markdown —— 图表、表格与版面一律丢失，且扫描件直接报
 * {@code NEEDS_OCR}。源站 {@code preferAnydocWhenAvailable} 也显式排除 {@code ft == "pdf"}（并写了
 * 注释说明理由）。本仓 PDF 走 {@link KbPdfParser}：它按<b>页</b>判文本层/扫描件，扫描页还能过视觉模型
 * 补强，是 anydoc 唯一做不了的那类文档的正确归宿。
 *
 * <p><b>为什么不接管 md/txt/csv/json（源站的「简单格式不接管」）</b>：这些格式本身就是文本，
 * 源站 {@code IsSimpleFormat} 判真 ⇒ {@code preferAnydocWhenAvailable} 返回空 ⇒ 走 {@code simple}。
 * 本仓对应 {@link KbTextParser}（已有成熟的 CSV→Markdown 表格 / JSON→围栏代码块实现，
 * 且对未知编码有 Tika 的 juniversalchardet 兜底）。⛔ 尤其是 csv：anydoc 虽支持，但本仓
 * {@code KbTextParser} 的转义（单元格内 {@code \} / {@code |} / 换行 → {@code <br />}）与源站
 * {@code csvCellReplacer} 逐字对齐，不能换成两套不一样的结果。
 *
 * <p><b>{@code @Order(12)}</b>：路由按 {@code @Order} 升序取第一个 {@code supports()} 命中的解析器。
 * 本类现在 {@link #supports} 恒返回 false（{@link #ANYDOC_EXT} 已清空），故 {@code @Order} 对
 * <b>默认路由已无影响</b>；保留 12 只是让诊断端点里引擎顺序与历史一致，且将来若恢复某类格式时
 * 位置仍排在 {@link KbPdfParser}(10) 之后、{@link KbImageParser}(15) / {@link KbOfficeParser}(20) 之前。
 *
 * <p><b>默认路由落点</b>：{@link #supports} 恒 false ⇒ 注册表继续往后走，Office 类文件落到
 * {@link KbOfficeParser}（Tika）。⛔ 规则点名且库不可用时由 {@link KbParserEngineRouter} 先判
 * {@link AnydocNative#isAvailable()} 并回落默认 —— 不能「接住再返回空文本」：那会让文档
 * 「解析成功但内容为空」入库，比解析失败更糟。
 *
 * <p><b>fail-soft（对齐 {@link KbPdfParser}）</b>：库可用但这次转换失败（文件损坏 / 加密 /
 * 超资源上限），catch 住返回<b>空文本 + 失败原因</b>，⛔ 不抛 {@link KbParseException} ——
 * 一份坏文档不该让整批入库任务红掉，原因写进 metadata 后可观测即可。
 */
@Component
@Order(12)
public class KbAnydocParser implements KbDocumentParser, SelfDescribingAvailability {

    private static final Logger log = LoggerFactory.getLogger(KbAnydocParser.class);

    /** 单文档文本上限（与 PDF / Office / 文本解析器统一口径）。 */
    static final int MAX_TEXT_CHARS = 2_000_000;

    /** 降级原因键（本引擎特有；PDF 那套 {@code visionFailureReason} 语义不同，不复用）。 */
    static final String META_FALLBACK_REASON = "fallbackReason";

    /** 内嵌图片张数 · 与 {@link KbOfficeParser} 的 {@code imageCount} 同口径（无图时不写）。 */
    static final String META_IMAGE_COUNT = "imageCount";

    /**
     * 抽图不可用的进程级提示开关。
     *
     * <p>抽图失败是<b>可选增强能力</b>的失败（正文照常产出），不该变成每份 Office 文档一条 warn；
     * 只在本进程第一次遇到时提示一次，完整原因写进那一条日志。
     */
    private static final AtomicBoolean ASSET_FALLBACK_WARNED = new AtomicBoolean(false);

    /**
     * anydoc <b>默认</b>接管的后缀 · 当前为<b>空集</b>。
     *
     * <p><b>2026-11-12 用户裁定</b>：原先列在这里的 Office / ODF / RTF（以及更早移出的 epub）
     * 全部不再由本类默认接管，默认改走 Tika（{@link KbOfficeParser}），以便拿到 source block 与
     * 内嵌图片。⛔ 不要因为「anydoc 出的 markdown 更好看」把它们加回来 —— 加回来就会把 Office
     * 默认路由抢回本类，source block 归零。
     *
     * <p>为什么保留字段而不是让 {@link #supports} 无条件 false：
     * <ul>
     *   <li>{@link #supportedExtensions()} 与 {@link #supports} 仍同源，前端引擎清单不会出现「假选项」；</li>
     *   <li>显式点名（{@code parser_engine_rules} 写 {@code anydoc} / {@code KbAnydocParser}）走的是
     *       {@code name()} / {@link #engineFamilyNames()} + {@link #parse}，<b>不经过</b> {@link #supports}，
     *       故「默认不接管」与「仍可被点名」并不冲突。</li>
     * </ul>
     *
     * <p>⛔ 不含：{@code pdf}（无 document model）、图片（{@link KbImageParser}）、
     * {@code csv/md/txt/json} 等简单格式（{@link KbTextParser}）、{@code mhtml}（anydoc 不支持，
     * 留给 {@link KbOfficeParser}）。
     */
    static final Set<String> ANYDOC_EXT = Set.of();

    /**
     * 明确<b>不</b>接管的后缀 · 双保险（与 {@link ANYDOC_EXT} 无交集，防将来有人往集合里误加）。
     *
     * <p>{@code pdf}：anydoc 无 PDF document model（{@code ERR_PDF_NO_MODEL=7}）。
     * 图片：视觉模型通道才是正解。简单格式：{@link KbTextParser} 的结构转换已对齐源站。
     */
    private static final Set<String> NEVER_EXT = Set.of(
        "pdf", "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp",
        "md", "markdown", "txt", "text", "csv", "tsv", "json", "log",
        "html", "htm", "xhtml", "xml", "mhtml"
    );

    /**
     * 是否可用 · 转问 {@link AnydocNative}（原生库加载结果在它那里缓存，这里每次现问不会重复扫盘）。
     */
    @Override
    public boolean isAvailable() {
        return AnydocNative.isAvailable();
    }

    /** 不可用原因 · 直接透出加载失败的明细（含已尝试的路径），供前端下拉置灰提示。 */
    @Override
    public String unavailableReason() {
        return AnydocNative.unavailableReason();
    }

    /**
     * 是否接管该文件（<b>默认路由</b>用）。
     *
     * <p>判定顺序：① 原生库不可用 → false；② 扩展名在 {@link #NEVER_EXT} → false；
     * ③ 扩展名在 {@link #ANYDOC_EXT} → true。当前 {@link #ANYDOC_EXT} 为空集，故本方法
     * <b>恒返回 false</b>：Office 类默认落到 {@link KbOfficeParser}（Tika），以拿到 source block
     * 与内嵌图片。显式点名不受影响（{@link KbParserEngineRouter} 走类名/族名，不看本方法）。
     *
     * <p>⛔ 不看 MIME：本引擎认的是一批<b>容器格式</b>，MIME 探测（Tika）对它们可能给出
     * {@code application/zip} / {@code application/octet-stream} 这类泛值，按 MIME 放行会把无关 zip 抢走；
     * 扩展名在入库链上是可靠信号（上游上传时就带着原始文件名）。
     */
    @Override
    public boolean supports(String fileName, String mimeType) {
        if (!AnydocNative.isAvailable()) {
            return false;
        }
        String ext = KbParsers.extensionOf(fileName);
        if (ext == null) {
            return false;
        }
        if (NEVER_EXT.contains(ext)) {
            return false;
        }
        return ANYDOC_EXT.contains(ext);
    }

    /** 能力声明 · 直接复用 {@link #supports} 用的扩展名常量（⛔ 不另抄一份，防止两处漂移）。 */
    @Override
    public Set<String> supportedExtensions() {
        return ANYDOC_EXT;
    }

    /** 展示名 · 前端「解析引擎」下拉用；对齐源站引擎名（{@code AnydocEngineName = "anydoc"}）。 */
    @Override
    public String displayName() {
        return "anydoc";
    }

    /** 引擎族名别名 · 源站即 {@code anydoc}；类名由 {@code name()} 提供，不必重复列出。 */
    @Override
    public Set<String> engineFamilyNames() {
        return Set.of("anydoc");
    }

    @Override
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        Map<String, String> meta = KbParsers.newMeta();
        meta.put(KbParsedDocument.META_FILE_NAME, fileName);
        // MIME 走 Tika 探测（可 null：探测失败不影响解析，只少一个描述性字段）
        String mime = KbTikaSupport.detectMime(file, fileName);
        if (mime != null && !mime.isBlank()) {
            meta.put(KbParsedDocument.META_MIME_TYPE, mime);
        }
        meta.put(KbParsedDocument.META_PARSER, name());

        // 库不可用（极端时序：supports() 之后 DLL 被删 / 平台不符）→ 与 supports 同语义地给空产物
        if (!AnydocNative.isAvailable()) {
            String reason = AnydocNative.unavailableReason();
            meta.put(META_FALLBACK_REASON, reason);
            meta.put(KbParsedDocument.META_TEXT_LENGTH, "0");
            log.warn("KbAnydocParser: 引擎不可用，返回空产物（应由注册表路由到内置 Office 解析）file={} 原因={}",
                fileName, reason);
            return KbParsedDocument.empty(meta);
        }

        KbParsers.Truncation t;
        try {
            String markdown = AnydocNative.toMarkdown(file);
            t = KbParsers.truncate(markdown == null ? "" : markdown.strip(), MAX_TEXT_CHARS);
        } catch (AnydocException e) {
            // fail-soft（对齐 KbPdfParser）：坏文档不抛，空文本 + 原因入库，可观测即可
            meta.put(META_FALLBACK_REASON, e.getMessage());
            meta.put(KbParsedDocument.META_TEXT_LENGTH, "0");
            log.warn("KbAnydocParser: 转换失败，返回空产物 file={} code={} 原因={}",
                fileName, e.code(), e.getMessage());
            return KbParsedDocument.empty(meta);
        }

        if (t.truncated()) {
            meta.put(KbParsedDocument.META_TRUNCATED, "true");
        }
        meta.put(KbParsedDocument.META_TEXT_LENGTH, String.valueOf(t.text().length()));

        // 内嵌图片：走文档模型（anydoc_to_document）另抽，⛔ 不碰正文 ——
        // 正文分块坐标依赖 anydoc_to_markdown 的原文，图片只在 images 列表里追加引用。
        List<KbImageRef> images = List.of();
        try {
            images = saveEmbeddedImages(file, ctx);
            if (!images.isEmpty()) {
                meta.put(META_IMAGE_COUNT, String.valueOf(images.size()));
            }
        } catch (Exception | LinkageError e) {
            // 可选增强能力失败：正文、metadata（除图片外）都保持原样。
            // 只兜 Exception + LinkageError（JNA 找不到符号抛 UnsatisfiedLinkError），
            // ⛔ 不吞 OutOfMemoryError —— 那是进程级故障，不该被当成「没图」静默。

            if (ASSET_FALLBACK_WARNED.compareAndSet(false, true)) {
                log.warn("KbAnydocParser: 内嵌图片提取不可用，正文不受影响（本进程只提示一次）file={} 原因={}",
                    fileName, brief(e));
            }
        }

        if (log.isInfoEnabled()) {
            // sourceBlocks 恒 0：anydoc 只出 markdown，没有页码/段落/行号结构 —— 这正是默认改走 Tika 的原因
            log.info("KbAnydocParser: 解析完成 file={} mime={} chars={} truncated={} images={} sourceBlocks=0",
                fileName, mime, t.text().length(), t.truncated(), images.size());
        }
        return new KbParsedDocument(t.text(), images, meta);
    }

    /**
     * 把文档模型里的内嵌图片落盘，产出 {@link KbImageRef}。
     *
     * <p>落盘位置 {@code <KbImageSink.resolveImageDir>/images/anydoc-<sha256 前 8 位>.<ext>}：
     * 内容哈希命名让「同一张图在文档里出现多次」只落一份，重复解析也天然幂等（同名同内容）。
     *
     * @param file 源文档
     * @param ctx  解析上下文（{@code workDir} 非空即落在其下；null 时由 {@link KbImageSink} 建临时目录）
     * @return 图片引用（无图/模型无资产时为空；顺序与文档内出现顺序一致）
     * @throws IOException        落盘失败
     * @throws AnydocException    原生库不可用 / 模型转换失败
     */
    private static List<KbImageRef> saveEmbeddedImages(Path file, KbParseContext ctx)
            throws IOException, AnydocException {
        List<AnydocNative.EmbeddedAsset> assets = AnydocNative.documentAssets(file);
        if (assets.isEmpty()) {
            return List.of();
        }
        Path dir = KbImageSink.resolveImageDir(file, ctx).resolve("images");
        Files.createDirectories(dir);
        List<KbImageRef> images = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AnydocNative.EmbeddedAsset asset : assets) {
            byte[] data = asset.data();
            if (data == null || data.length == 0) {
                continue;
            }
            String hash = shortSha256(data);
            if (!seen.add(hash)) {
                continue;   // 同一张图重复引用 → 只留一份
            }
            String ext = extensionFor(asset.mimeType(), asset.sourcePath());
            Path out = dir.resolve("anydoc-" + hash + "." + ext);
            Files.write(out, data);
            images.add(KbImageRef.of(0, out.toAbsolutePath().toString(), KbImageRef.KIND_OFFICE_EMBEDDED));
        }
        return images;
    }

    /** 内容 SHA-256 的前 8 位十六进制（8 字节 → 16 hex 太长，取 4 字节即 8 位）。 */
    private static String shortSha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** MIME → 落盘扩展名；未知 MIME 退回源容器路径的后缀，再退回 {@code bin}。 */
    private static String extensionFor(String mime, String sourcePath) {
        String ext = switch (mime == null ? "" : mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/webp" -> "webp";
            case "image/tiff" -> "tiff";
            case "image/svg+xml" -> "svg";
            case "image/emf" -> "emf";
            case "image/wmf" -> "wmf";
            default -> null;
        };
        if (ext != null) {
            return ext;
        }
        String fromName = KbParsers.extensionOf(sourcePath);
        return fromName == null || fromName.isBlank() ? "bin" : fromName.toLowerCase(Locale.ROOT);
    }

    /** 异常压成一行（日志字段不该带换行；限长避免刷屏）。 */
    private static String brief(Throwable t) {
        String msg = t == null ? "未知错误" : (t.getMessage() == null ? t.toString() : t.getMessage());
        String oneLine = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}

package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * anydoc 解析器 · Office 类格式的<b>默认引擎</b>（Word / Excel / PPT / 电子书 / RTF / ODF）。
 *
 * <p><b>出处</b>：WeKnora {@code internal/infrastructure/docparser/anydoc/}（引擎名 {@code anydoc}）
 * + {@code engines.go:44-64} 的 {@code preferAnydocWhenAvailable()}。用户已拍板「anydoc 作为默认引擎」，
 * 路由语义对齐源站：<b>Office 类格式优先 anydoc，PDF / 图片 / 简单格式不接管</b>。
 *
 * <p><b>为什么不用 Tika（{@link KbOfficeParser} 已有的通道）</b>：Tika 产出的是<b>扁平纯文本/HTML 事件流</b>，
 * 表格拍平成 {@code \t项目\t金额\t}、段落与标题的层级要靠本仓自己的 {@link KbMarkdownHandler} 反推；
 * anydoc 直接从文档模型出<b>结构化 GFM Markdown</b>（标题层级、列表标记、合并单元格、脚注、行内图片
 * 位置都天然保留），下游 {@code KbTextSplitter} 的「按标题切块 / 表格不切」保护直接可用。
 * 实测同一份 .docx：Tika 需经 XHTML 往返，anydoc 一步出 markdown（见 {@code AnydocNative} 类 javadoc）。
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
 * 排在 {@link KbPdfParser}(10) 之后、{@link KbImageParser}(15) 之前 —— 后两者的 {@code supports()}
 * 与本类不重叠（本类明确排除 pdf / 图片扩展名），位置只是为了贴合「anydoc 优先于内置 Office(20)」
 * 这一语义，并让诊断端点里引擎顺序与「谁优先」一致。
 *
 * <p><b>不可用时的路由（关键，不是「降级产物」）</b>：{@link #supports} 开头就判
 * {@link AnydocNative#isAvailable()}，不可用 ⇒ 直接返回 false ⇒ 注册表继续往后走，落到
 * {@link KbOfficeParser}。⛔ 不能先接住再返回空文本：那样文档会「解析成功但内容为空」入库，
 * 用户看到的是一份空知识库文档，比解析失败更糟。
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

    /**
     * anydoc 接管的后缀（小写、不含点）。
     *
     * <p>与 {@code anydoc-go/anydoc.go:136-153} 的 {@code supportedExtensions} 取交集后再按本仓语义调整：
     * <ul>
     *   <li>{@code docm/docx} 同族、{@code xls/xlsm/xlsx}、{@code ppt/pptm/pptx} —— anydoc 的 Rust 侧
     *       {@code Format::from_extension} 直接认（宏文档按基格式解析）</li>
     *   <li>{@code dot/dotx/xlt/xltx/pot/potx}（模板）—— 源站 Go 的 {@code supportedExtensions}
     *       <b>没有</b>列它们，但 anydoc 的内容探测是按 OPC 主部件的 content type 判（
     *       {@code detect.rs:173-184}，{@code -template} 变体同样命中
     *       {@code wordprocessingml}/{@code spreadsheetml}/{@code presentationml}）⇒ 模板文件走
     *       {@code anydoc_to_markdown} 能出结构化 markdown，故本仓收下。
     *       内置 {@link KbOfficeParser} 也认这批后缀，两者互为兜底，不冲突。</li>
     *   <li>{@code rtf/odt/ods/odp} —— anydoc 原生支持，Tika 也能解析；anydoc 出的结构更好</li>
     *   <li>{@code epub} —— anydoc 支持；内置 Office 解析也认（Tika EpubParser），互为兜底</li>
     * </ul>
     *
     * <p>⛔ 不含：{@code pdf}（无 document model）、图片（{@link KbImageParser}）、
     * {@code csv/md/txt/json} 等简单格式（{@link KbTextParser}）、{@code mhtml}（anydoc 不支持，
     * 留给 {@link KbOfficeParser}）。
     */
    static final Set<String> ANYDOC_EXT = Set.of(
        "doc", "docm", "docx", "dot", "dotx",
        "xls", "xlsm", "xlsx", "xlt", "xltx",
        "ppt", "pptm", "pptx", "pot", "potx",
        "rtf", "odt", "ods", "odp",
        "epub"
    );

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
     * 是否接管该文件。
     *
     * <p>判定顺序：① 原生库不可用 → false（让注册表路由到 {@link KbOfficeParser}）；
     * ② 扩展名在 {@link #NEVER_EXT} → false；③ 扩展名在 {@link #ANYDOC_EXT} → true。
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
        if (log.isInfoEnabled()) {
            log.info("KbAnydocParser: 解析完成 file={} mime={} chars={} truncated={}",
                fileName, mime, t.text().length(), t.truncated());
        }
        // anydoc 只出 Markdown 文本，不导出图片文件 ⇒ images 恒空（行内图片会以 ![](...) 链接形式留在正文）
        return new KbParsedDocument(t.text(), List.of(), meta);
    }
}

package com.nexusai.kb.app.parser;

import com.nexusai.kb.app.chunk.ParserEngineRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 解析引擎按库路由 · 对齐 WeKnora {@code ChunkingConfig.ResolveParserEngine}
 * （{@code knowledgebase.go:343-367}）+ 消费点 {@code knowledge_process.go:4389}。
 *
 * <p><b>源站语义（逐条）</b>：
 * <ol>
 *   <li>按 {@code parser_engine_rules} <b>声明顺序</b>找第一条 {@code file_types} 命中文档类型的规则
 *       （源侧 {@code ResolveParserEngineRule}，{@code knowledgebase.go:353-367}）</li>
 *   <li>命中 ⇒ 用规则里的 engine</li>
 *   <li>未命中 ⇒ {@code DefaultParserEngine(fileType)}（源侧 {@code :328-341}）</li>
 * </ol>
 *
 * <p><b>本仓的默认侧就是 {@code KbParserRegistry.select}</b>：注册表按 {@code @Order} 升序取第一个
 * {@code supports()} 命中的解析器，顺序 PDF(10) → anydoc(12) → 图片(15) → Office(20) → 文本(30)，
 * 与源站「anydoc 优先 / 类型默认」的结论等价（见 {@code KbParserRegistry} 类注释）。
 *
 * <p><b>⭐ engine 名的可匹配范围</b>：解析器的 {@code name()}（类名，如 {@code KbPdfParser}）
 * 与 {@link KbDocumentParser#engineFamilyNames()}（源站族名，如 {@code builtin} / {@code anydoc} /
 * {@code paddleocr_vl}）都算命中，比较时忽略大小写与 {@code _}/{@code -} 差异。
 * 这样源站原样迁移过来的 {@code parser_engine_rules} 不会静默失配。
 *
 * <p><b>⭐ engine 名匹配不到时的处理（有意偏离源站）</b>：源站 engine 是族名，映射失败会让
 * docreader 返回 nil ⇒ 该文档解析失败。本仓<b>记 warn 并回落默认路由</b> —— 一个写错的库配置
 * 不该让整库文档全部解析失败。回落时日志里带上「写的是什么、忽略后走了谁」。
 *
 * <p><b>⚠️ 可用性</b>：规则点名了某个解析器但它当前不可用（如 anydoc 在无原生库的平台）⇒
 * 同样回落默认。<b>不</b>强行选中一个不可用的解析器 —— 那会让 {@code parse()} 产出空文本
 * （「解析成功但内容为空」，比失败更糟）。
 */
public final class KbParserEngineRouter {

    private static final Logger log = LoggerFactory.getLogger(KbParserEngineRouter.class);

    private KbParserEngineRouter() {
    }

    /**
     * 按规则选解析器。
     *
     * @param registry 解析器注册表
     * @param fileName 文件名（用于取扩展名）
     * @param rules    知识库配置的规则（可 null / 空 = 走默认）
     * @return 选中的解析器；无匹配 ⇒ null（与 {@link KbParserRegistry#select} 同契约）
     */
    public static KbDocumentParser select(
        KbParserRegistry registry, String fileName, List<ParserEngineRule> rules) {
        if (registry == null) {
            return null;
        }
        String ext = extensionOf(fileName);
        String mime = null;  // 文件名路径不带 MIME；需要 MIME 的解析器（PDF/图片）走扩展名也能命中

        ParserEngineRule hit = firstMatching(rules, ext);
        if (hit != null) {
            KbDocumentParser byName = findByName(registry, hit.engine(), fileName);
            if (byName == null) {
                log.warn("[解析路由] 规则指定的引擎不存在，回落默认 file={} 类型={} 配置引擎=\"{}\" 可用引擎={}",
                    fileName, ext, hit.engine(), names(registry));
            } else if (byName instanceof SelfDescribingAvailability self && !self.isAvailable()) {
                log.warn("[解析路由] 规则指定的引擎当前不可用，回落默认 file={} 类型={} 配置引擎=\"{}\" 原因={}",
                    fileName, ext, hit.engine(), self.unavailableReason());
            } else {
                if (log.isInfoEnabled()) {
                    log.info("[解析路由] 命中规则 file={} 类型={} → {}", fileName, ext, byName.name());
                }
                return byName;
            }
        }
        return registry.select(fileName, mime);
    }

    /**
     * 一站式：探测 MIME → 按规则选解析器 → 解析。
     *
     * <p>与 {@link KbParserRegistry#parse} 的唯一差别是「选解析器」这一步走规则。
     * 文件存在性检查、MIME 探测、上下文默认值、异常文案全部与注册表保持一致
     * （⛔ 不另写一份，避免两条路径对同一输入给出不同错误）。
     *
     * @param registry 解析器注册表
     * @param file     文件（必须存在）
     * @param ctx      解析上下文（null → 默认）
     * @param rules    知识库配置的规则（可空）
     * @return 解析产物
     * @throws KbParseException 文件不存在 / 无匹配解析器 / 解析失败
     */
    public static KbParsedDocument parse(
        KbParserRegistry registry, Path file, KbParseContext ctx, List<ParserEngineRule> rules) {
        if (registry == null) {
            throw new KbParseException("解析器注册表未装配");
        }
        if (file == null || !Files.isRegularFile(file)) {
            throw new KbParseException("待解析文件不存在或不是普通文件: " + file);
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String mime = registry.detectMime(file);
        KbDocumentParser parser = select(registry, fileName, rules);
        if (parser == null) {
            throw new KbParseException("没有匹配的解析器: " + fileName + "（mime=" + mime + "）");
        }
        if (log.isInfoEnabled()) {
            log.info("[解析路由] 路由 file={} mime={} 规则数={} → {}", fileName, mime,
                rules == null ? 0 : rules.size(), parser.name());
        }
        return parser.parse(file, ctx == null ? KbParseContext.defaults() : ctx);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 第一条命中的规则（源侧 {@code ResolveParserEngineRule} 的双层循环，{@code knowledgebase.go:359-365}）。 */
    static ParserEngineRule firstMatching(List<ParserEngineRule> rules, String fileType) {
        if (rules == null || rules.isEmpty() || fileType == null || fileType.isEmpty()) {
            return null;
        }
        for (ParserEngineRule rule : rules) {
            if (rule != null && rule.matches(fileType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 按 {@code name()} <b>或</b> {@link KbDocumentParser#engineFamilyNames()} 匹配。
     *
     * <p>为什么要认族名：源站规则里写的是 {@code builtin} / {@code anydoc} / {@code simple} /
     * {@code paddleocr_vl} 这类引擎族名，而本仓 {@code name()} 是类名。只比类名会让照搬来的规则
     * 静默失配、回落默认路由（PaddleOCR-VL 则完全无法被规则点名）。两侧都做规范化后比较：
     * 去空白 + 小写 + {@code -} 视同 {@code _}。⛔ 仍不做前缀/模糊匹配。
     *
     * <p><b>主名优先</b>：先按 {@code name()} 精确匹配（存量配置的行为逐字不变），没有再比族名。
     *
     * <p><b>族名一对多时的消歧</b>：一个族名会覆盖多个解析器（{@code builtin} = PDF/HTML/XMind、
     * {@code simple} = 文本/图片）。这时不按 {@code @Order} 盲取第一个 —— 那会让
     * 「规则: html → builtin」落到 PDF 解析器上。改为优先取 {@code supports(fileName)} 认这个文件的
     * 那一个；都不认（或判定抛异常）才退回 {@code @Order} 第一个，与既有兜底一致。
     *
     * @param registry 解析器注册表
     * @param engine   规则里的 engine 名
     * @param fileName 待解析文件名（族名一对多时用于消歧；可 null）
     * @return 命中的解析器；无匹配 ⇒ null
     */
    static KbDocumentParser findByName(KbParserRegistry registry, String engine, String fileName) {
        if (engine == null || engine.isBlank()) {
            return null;
        }
        String wanted = canonicalEngine(engine);
        List<KbDocumentParser> aliasHits = new ArrayList<>(2);
        for (KbDocumentParser parser : registry.parsers()) {
            if (wanted.equals(canonicalEngine(parser.name()))) {
                return parser;
            }
            if (matchesFamily(wanted, parser)) {
                aliasHits.add(parser);
            }
        }
        if (aliasHits.isEmpty()) {
            return null;
        }
        for (KbDocumentParser parser : aliasHits) {
            if (supportsQuietly(parser, fileName)) {
                return parser;
            }
        }
        return aliasHits.get(0);
    }

    /** 规则里的 engine 是否命中该解析器声明的族名。 */
    private static boolean matchesFamily(String canonical, KbDocumentParser parser) {
        Set<String> families = parser.engineFamilyNames();
        if (families == null) {
            return false;
        }
        for (String family : families) {
            if (canonical.equals(canonicalEngine(family))) {
                return true;
            }
        }
        return false;
    }

    /** {@code supports} 的异常不影响路由（与 {@link KbParserRegistry#select} 同口径：跳过该候选）。 */
    private static boolean supportsQuietly(KbDocumentParser parser, String fileName) {
        try {
            return parser.supports(fileName == null ? "" : fileName, null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** engine 名规范化：去空白 + 小写 + 连字符等价下划线（{@code paddleocr-vl} ≡ {@code paddleocr_vl}）。 */
    static String canonicalEngine(String engine) {
        if (engine == null) {
            return "";
        }
        return engine.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 回落 warn 里列的「可写引擎名」· 主名之外带上族名别名（否则用户照提示改成 {@code anydoc}
     * 仍会看到它被判为「不存在」）。
     */
    private static List<String> names(KbParserRegistry registry) {
        List<String> all = new ArrayList<>();
        for (KbDocumentParser parser : registry.parsers()) {
            all.add(parser.name());
            Set<String> families = parser.engineFamilyNames();
            if (families != null) {
                for (String family : families) {
                    if (!all.contains(family)) {
                        all.add(family);
                    }
                }
            }
        }
        return List.copyOf(all);
    }

    /** 取扩展名（小写、不含点）· 与 {@link KbParsers#extensionOf} 同源，此处只做 null 安全包装。 */
    static String extensionOf(String fileName) {
        String ext = KbParsers.extensionOf(fileName);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}

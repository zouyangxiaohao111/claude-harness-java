package com.nexusai.kb.app.parser;

import com.nexusai.kb.app.chunk.ParserEngineRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
 * <p><b>⭐ engine 名匹配不到时的处理（有意偏离源站）</b>：源站 engine 是族名，映射失败会让
 * docreader 返回 nil ⇒ 该文档解析失败。本仓<b>记 warn 并回落默认路由</b> —— 一个写错的库配置
 * 不该让整库文档全部解析失败；而且前端存的是类名（{@code KbAnydocParser}），用户更容易手抖
 * （大小写差异、改了类名）。回落时日志里带上「写的是什么、忽略后走了谁」。
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
            KbDocumentParser byName = findByName(registry, hit.engine());
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

    /** 按 {@code name()} 精确匹配（⛔ 不做模糊/前缀匹配：两套名字并存会让配置与日志对不上）。 */
    static KbDocumentParser findByName(KbParserRegistry registry, String engine) {
        if (engine == null || engine.isBlank()) {
            return null;
        }
        for (KbDocumentParser parser : registry.parsers()) {
            if (engine.trim().equals(parser.name())) {
                return parser;
            }
        }
        return null;
    }

    private static List<String> names(KbParserRegistry registry) {
        return registry.parsers().stream().map(KbDocumentParser::name).toList();
    }

    /** 取扩展名（小写、不含点）· 与 {@link KbParsers#extensionOf} 同源，此处只做 null 安全包装。 */
    static String extensionOf(String fileName) {
        String ext = KbParsers.extensionOf(fileName);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }
}

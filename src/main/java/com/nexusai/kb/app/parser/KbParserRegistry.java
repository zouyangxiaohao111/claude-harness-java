package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 解析器注册表 · 按扩展名/MIME 选一个解析器（Spring 构造注入 {@code List<KbDocumentParser>}）。
 *
 * <p><b>选择顺序</b>：{@code @Order} 升序 —— PDF(10) → anydoc(12) → 图片(15) → Office(20) →
 * 文本/兜底(30)。这与「越专用的越先」一致：{@code KbTextParser} 是兜底的（对未知扩展名也返回 true），
 * 必须最后；anydoc 排在 Office 之前，是「Office 类格式默认走 anydoc、内置 Office 解析兜底」的落地。
 *
 * <p><b>为什么显式排序而不是依赖注入顺序</b>：Spring 对同类型集合注入的顺序不保证（取决于 bean 定义
 * 顺序），兜底解析器排到前面会把 .csv 抢走。故用 {@code AnnotationAwareOrderComparator} 显式排，
 * 顺序在代码里可见、可测。
 *
 * <p><b>⛔ 不写库</b>：注册表只做路由，解析结果交调用方。
 */
@Component
public class KbParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(KbParserRegistry.class);

    private final List<KbDocumentParser> parsers;

    /**
     * 构造注入 · Spring 会传入全部 {@link KbDocumentParser} 实现（按 {@code @Order} 排序）。
     *
     * @param parsers 所有解析器（可空列表 → 任何解析请求都抛 {@link KbParseException}）
     */
    public KbParserRegistry(List<KbDocumentParser> parsers) {
        List<KbDocumentParser> sorted = parsers == null ? List.of() : new java.util.ArrayList<>(parsers);
        sorted.sort(Comparator.comparingInt(KbParserRegistry::orderOf));
        this.parsers = List.copyOf(sorted);
        if (log.isInfoEnabled()) {
            log.info("KbParserRegistry: 已装配 {} 个解析器 {}", this.parsers.size(),
                this.parsers.stream().map(KbDocumentParser::name).toList());
        }
    }

    /**
     * 按文件名 + MIME 选解析器。
     *
     * <p>MIME 由调用方探测（{@link #detectMime(Path)}）或上传时已知；为空时只按扩展名选。
     *
     * @return 匹配的解析器；无匹配 → null（调用方决定报错文案）
     */
    public KbDocumentParser select(String fileName, String mimeType) {
        for (KbDocumentParser parser : parsers) {
            boolean matched;
            try {
                matched = parser.supports(fileName, mimeType);
            } catch (RuntimeException e) {
                // 某个解析器的判定逻辑出错不该让整条路由失败：记 warn 后继续试下一个
                log.warn("KbParserRegistry: 解析器 {} 的 supports() 抛异常，跳过 file={} 原因={}",
                    parser.name(), fileName, e.getMessage());
                continue;
            }
            if (matched) {
                return parser;
            }
        }
        return null;
    }

    /**
     * 探测 MIME（只读文件头）。
     *
     * @return MIME（可能 null = 探测失败，调用方仍可按扩展名走）
     */
    public String detectMime(Path file) {
        return KbTikaSupport.detectMime(file, file == null ? null : file.getFileName().toString());
    }

    /**
     * 一站式入口：探测 MIME → 选解析器 → 解析。
     *
     * @param file 文件（必须存在）
     * @param ctx  解析上下文
     * @return 解析产物
     * @throws KbParseException 文件不存在 / 无匹配解析器 / 解析失败
     */
    public KbParsedDocument parse(Path file, KbParseContext ctx) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new KbParseException("待解析文件不存在或不是普通文件: " + file);
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String mime = detectMime(file);
        KbDocumentParser parser = select(fileName, mime);
        if (parser == null) {
            throw new KbParseException("没有匹配的解析器: " + fileName + "（mime=" + mime + "）");
        }
        if (log.isInfoEnabled()) {
            log.info("KbParserRegistry: 路由 file={} mime={} → {}", fileName, mime, parser.name());
        }
        return parser.parse(file, ctx == null ? KbParseContext.defaults() : ctx);
    }

    /** 当前装配的解析器（只读视图，供测试与诊断端点）。 */
    public List<KbDocumentParser> parsers() {
        return parsers;
    }

    /**
     * 全部已装配解析器的能力快照（顺序 = {@code @Order} 升序，与 {@link #select} 的路由优先级一致）。
     *
     * <p><b>为什么由注册表出这份数据</b>：解析器集合是 Spring 装配出来的，注册表是唯一持有者；
     * 让端点直接读字段会绕开 {@code @Order} 排序，前端下拉的顺序就会随 bean 定义顺序漂移。
     *
     * <p><b>可用性来源</b>：实现了 {@link SelfDescribingAvailability} 的解析器自报（如
     * {@link KbAnydocParser} —— 依赖随包分发的原生库，非 Windows / 库缺失时不可用）；其余解析器
     * 按既有语义视为恒可用（{@code available=true} / {@code unavailableReason=""}）。
     * 每次现问而不缓存：装上 DLL 重启后应立刻变为可用，快照不该跟实现状态漂移。
     *
     * @return 能力快照（不含 {@code parsers()} 里的 null 项；永远非 null）
     */
    public List<ParserEngineInfo> describeEngines() {
        List<ParserEngineInfo> engines = new java.util.ArrayList<>(parsers.size());
        for (KbDocumentParser parser : parsers) {
            boolean available = true;
            String unavailableReason = "";
            if (parser instanceof SelfDescribingAvailability self) {
                available = self.isAvailable();
                String reason = self.unavailableReason();
                unavailableReason = reason == null ? "" : reason;
            }
            engines.add(new ParserEngineInfo(
                parser.name(),
                parser.displayName(),
                parser.supportedExtensions(),
                available,
                unavailableReason));
        }
        if (log.isDebugEnabled()) {
            log.debug("KbParserRegistry: describeEngines → {} 个 {}", engines.size(),
                engines.stream().map(ParserEngineInfo::name).toList());
        }
        return List.copyOf(engines);
    }

    private static int orderOf(KbDocumentParser parser) {
        org.springframework.core.annotation.Order order =
            org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                parser.getClass(), org.springframework.core.annotation.Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }
}

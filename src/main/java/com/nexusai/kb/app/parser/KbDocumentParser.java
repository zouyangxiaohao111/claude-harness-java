package com.nexusai.kb.app.parser;

import java.nio.file.Path;

/**
 * 文档解析器契约 · 一个解析器负责一类文件，产出 {@link KbParsedDocument}。
 *
 * <p><b>职责边界（反模式 4：解析不写库）</b>：实现类只能读文件、落副产物（图片）、返回结果。
 * 入库、状态机、分块一律由上层编排负责 —— 这样重建索引、预览、重解析三条路径能复用同一个解析器。
 *
 * <p><b>失败语义</b>：给出可信结果就返回；无法解析（文件损坏、密码保护、类型不支持）抛
 * {@link KbParseException}。⛔ 不返回半成品 + 静默吞异常（反模式 5）。
 */
public interface KbDocumentParser {

    /**
     * 是否能解析该类型。
     *
     * <p>实现必须先看扩展名（廉价且对本仓上传链可靠），再看 MIME（Tika 有时把 .md 探测成
     * {@code text/x-web-markdown}、把 .csv 探测成 {@code text/plain}，单看 MIME 会误判）。
     *
     * @param fileName 原始文件名（可含路径；实现自行取扩展名）
     * @param mimeType 探测到的 MIME（可 null）
     * @return true = 本解析器处理
     */
    boolean supports(String fileName, String mimeType);

    /**
     * 解析文档。
     *
     * @param file 待解析文件（调用方保证存在且可读）
     * @param ctx  解析上下文（副产物目录 / 是否允许视觉 / 用量归属）
     * @return 解析产物（恒非 null）
     * @throws KbParseException 无法给出可信结果时
     */
    KbParsedDocument parse(Path file, KbParseContext ctx);

    /**
     * 解析器标识 · 写入 {@code metadata.parser}，便于线上排查「这份文档是谁解析的」。
     *
     * @return 短名（如 {@code KbPdfParser}）
     */
    default String name() {
        return getClass().getSimpleName();
    }
}

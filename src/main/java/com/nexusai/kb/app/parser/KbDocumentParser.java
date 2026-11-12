package com.nexusai.kb.app.parser;

import java.nio.file.Path;
import java.util.Set;

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

    /**
     * 引擎族名别名 · 供 {@code parser_engine_rules} 用源站口径（{@code builtin} / {@code anydoc} /
     * {@code simple} / {@code paddleocr_vl} …）点名本解析器。
     *
     * <p><b>为什么需要别名</b>：{@link #name()} 返回类名（{@code KbPdfParser}），而源站
     * {@code parser_engine_rules} 存的是引擎族名；只比 {@code name()} 会让原样迁移过来的规则静默
     * 匹配不到、回落默认路由。别名只<b>追加</b>可匹配的名字，⛔ 不改 {@link #name()} / {@link #displayName()}
     * 的既有取值（引擎清单与已存数据都依赖它们）。
     *
     * @return 别名集合（不可变；默认只有 {@link #name()}，非 null）
     */
    default Set<String> engineFamilyNames() {
        return Set.of(name());
    }

    /**
     * 本解析器支持的扩展名（小写、不含点）· 用于注册表端点对外暴露能力。
     *
     * <p>默认空集合 = 「未声明」；实现类应返回 {@code supports()} 真正认的那批扩展名，
     * 与判定逻辑同源（复用类内常量，⛔ 不另抄一份），否则前端下拉会出现「选了却不生效」的选项。
     *
     * @return 扩展名集合（不可变；未声明时为空集，非 null）
     */
    default Set<String> supportedExtensions() {
        return Set.of();
    }

    /**
     * 展示名 · 前端下拉用（如 {@code 内置 PDF 解析}）。
     *
     * @return 展示名；默认同 {@link #name()}
     */
    default String displayName() {
        return name();
    }
}

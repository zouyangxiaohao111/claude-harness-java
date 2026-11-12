package com.nexusai.kb.app.chunk;

import java.util.List;
import java.util.Locale;

/**
 * 解析引擎路由规则 · 对齐 WeKnora {@code types.ParserEngineRule}（{@code knowledgebase.go:282-287,
 * 353-371}）。
 *
 * <p><b>语义</b>：{@code fileTypes} 里任一类型命中文档扩展名 ⇒ 用 {@code engine} 指定的解析器。
 * 规则<b>按声明顺序</b>匹配，先命中者胜（源侧 {@code ResolveParserEngineRule} 的双层 for 循环，
 * {@code :359-365}）。
 *
 * <p><b>engine 的取值口径（2026-11-12 起支持族名别名）</b>：源站存的是
 * {@code anydoc} / {@code builtin} / {@code markitdown} 这类<b>引擎族名</b>。本仓解析器以
 * Spring bean 装配，主名是 {@link com.nexusai.kb.app.parser.KbDocumentParser#name()}（类名），
 * 但每个解析器同时声明 {@code engineFamilyNames()}（如 {@code anydoc} / {@code simple} /
 * {@code paddleocr_vl}）。⇒ 本字段<b>两种写法都接受</b>：类名 或 族名（大小写不敏感、
 * {@code -} 与 {@code _} 等价）。
 *
 * <p><b>族名天然一对多，匹配必须消歧</b>：{@code builtin} 同时是 PDF / HTML / XMind 的族名，
 * {@code simple} 同时是 Image / Text 的族名。路由顺序固定为：先精确比 {@code name()}；
 * 族名多命中时用 {@code supports(fileName)} 挑真正认这个文件的；都不认才退回 {@code @Order} 第一个。
 * 命中与回落都会打日志，避免「配置写了族名、实际选了谁」无法判定。
 *
 * <p>前端下拉的展示名走 {@code displayName()}，与路由键严格分离。
 *
 * <p><b>类型规范化</b>：两侧都按源站 {@code normalizeParserFileType}（{@code knowledgebase.go:369-371}）
 * 做「小写 + 去空白 + 去前导点」，故配置里写 {@code ".PDF"} / {@code "pdf"} / {@code " pdf "} 等价。
 *
 * @param fileTypes 文件类型列表（扩展名，可带前导点；空列表 = 该规则永不命中）
 * @param engine    解析器名（{@link com.nexusai.kb.app.parser.KbDocumentParser#name()}；空白 = 无指定）
 */
public record ParserEngineRule(List<String> fileTypes, String engine) {

    public ParserEngineRule {
        fileTypes = fileTypes == null ? List.of() : List.copyOf(fileTypes);
        engine = engine == null ? "" : engine.trim();
    }

    /**
     * 该规则是否覆盖给定文件类型 · 对齐源站 {@code normalizeParserFileType} 两侧规范化
     * （{@code knowledgebase.go:359-365, 369-371}）。
     *
     * @param fileType 文件类型/扩展名（可带前导点、可含大小写与空白）
     * @return true = 命中
     */
    public boolean matches(String fileType) {
        String normalized = normalize(fileType);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String ft : fileTypes) {
            if (normalized.equals(normalize(ft))) {
                return true;
            }
        }
        return false;
    }

    /** 小写 + 去空白 + 去前导点 · 逐字对齐源站 {@code normalizeParserFileType}（{@code knowledgebase.go:369-371}）。 */
    public static String normalize(String fileType) {
        if (fileType == null) {
            return "";
        }
        String s = fileType.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s;
    }
}

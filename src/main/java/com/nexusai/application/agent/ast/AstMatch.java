package com.nexusai.application.agent.ast;

import java.util.Map;

/**
 * 一条 AST 匹配（ast-grep {@code --json=stream} 单行的 Java 投影）。
 *
 * @param file       文件路径（CLI 输出原样；绝对目标 ⇒ 绝对路径）
 * @param text       匹配到的源码文本（多行匹配以 {@code \n} 分隔）
 * @param language   ast-grep 推断/指定的语言名（如 {@code Java} / {@code JavaScript}）
 * @param line       起始行（<b>1 基</b>；CLI 原始 0 基已 +1）
 * @param column     起始列（1 基）
 * @param endLine    结束行（1 基；区间闭合于该行 endColumn 之前）
 * @param endColumn  结束列（1 基，排他）
 * @param startByte  起始字节偏移（UTF-8 字节，{@code range.byteOffset.start}）
 * @param endByte    结束字节偏移（UTF-8 字节，排他）
 * @param singleMeta 单节点元变量绑定（{@code metaVariables.single}，仅取 {@code text}；
 *                   多值 {@code multi} 不展开——匹配正文里已含）
 * @param replacement 替换文本（CLI 带 {@code -r} 时才非 null；{@code out:""} 时为 {@code ""}）
 */
public record AstMatch(
        String file,
        String text,
        String language,
        int line,
        int column,
        int endLine,
        int endColumn,
        int startByte,
        int endByte,
        Map<String, String> singleMeta,
        String replacement
) {
    public AstMatch {
        singleMeta = singleMeta == null ? Map.of() : Map.copyOf(singleMeta);
    }
}

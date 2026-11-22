package com.nexusai.application.agent.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AstEditPlan 的合并与字节级落地。
 *
 * <p>WHY：AstEdit 直接改用户源码，这里错了就是写坏文件：
 * <ul>
 *   <li>重叠区间必须拒绝（同时替换同一段字节会产生不可预期的结果）；</li>
 *   <li>多 op 命中同一处且替换相同必须去重（否则第二条会被判重叠，用户被迫拆 op）；</li>
 *   <li>必须按 UTF-8 <b>字节</b>拼接：BOM 与 CRLF 原样保留（字符串往返会改编码/行尾）；</li>
 *   <li>区间切在 UTF-8 字符中间必须拒绝（否则写出非法编码）。</li>
 * </ul>
 */
@DisplayName("AstEditPlanTest · 合并/去重/重叠/字节级拼接")
class AstEditPlanTest {

    private static AstMatch m(String file, int start, int end, String text, String replacement) {
        return new AstMatch(file, text, "JavaScript", 1, 1, 1, 2, start, end, java.util.Map.of(), replacement);
    }

    @Test
    @DisplayName("merge：跨文件分组、按字节排序、相同 (start,end,after) 去重")
    void mergeGroupsSortsDedupes() {
        List<AstEditPlan> plans = AstEditPlan.merge(List.of(
            m("/w/b.js", 0, 6, "foo(1)", "bar(1)"),
            m("/w/a.js", 10, 16, "foo(2)", "baz(2)"),
            m("/w/a.js", 10, 16, "foo(2)", "baz(2)"),
            m("/w/a.js", 2, 8, "foo(1)", "bar(1)")));

        assertThat(plans).extracting(AstEditPlan::file)
            .containsExactly(java.nio.file.Path.of("/w/a.js").toAbsolutePath().normalize().toString(),
                java.nio.file.Path.of("/w/b.js").toAbsolutePath().normalize().toString());
        assertThat(plans.get(0).changes()).hasSize(2);
        assertThat(plans.get(0).changes()).extracting(AstEditPlan.Change::startByte).containsExactly(2, 10);
    }

    @Test
    @DisplayName("merge：重叠（start < prevEnd）抛 AstEditException，文案含区间")
    void mergeRejectsOverlap() {
        assertThatThrownBy(() -> AstEditPlan.merge(List.of(
            m("/w/a.js", 0, 10, "foo(1)", "x"),
            m("/w/a.js", 5, 12, "oo(1)", "y"))))
            .isInstanceOf(AstEditException.class)
            .hasMessageContaining("overlapping replacements detected")
            .hasMessageContaining("byte 5..12");
    }

    @Test
    @DisplayName("applyToBytes：中文替换按 UTF-8 字节正确拼接")
    void appliesUtf8Chinese() {
        byte[] original = "前缀 foo(1) 后缀".getBytes(StandardCharsets.UTF_8);
        int start = "前缀 ".getBytes(StandardCharsets.UTF_8).length;
        int end = start + "foo(1)".length();
        byte[] out = AstEditPlan.applyToBytes(original, List.of(
            new AstEditPlan.Change(start, end, "foo(1)", "函数(2)", 1, 1, 1, 2)));
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("前缀 函数(2) 后缀");
    }

    @Test
    @DisplayName("applyToBytes：BOM + CRLF 文件字节级不变形（前缀 BOM、行尾 CRLF 保留）")
    void preservesBomAndCrlf() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] tail = "a\r\nfoo(1)\r\nz\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] original = new byte[bom.length + tail.length];
        System.arraycopy(bom, 0, original, 0, bom.length);
        System.arraycopy(tail, 0, original, bom.length, tail.length);
        int start = bom.length + "a\r\n".length();
        int end = start + "foo(1)".length();

        byte[] out = AstEditPlan.applyToBytes(original, List.of(
            new AstEditPlan.Change(start, end, "foo(1)", "bar(1)", 2, 1, 2, 7)));

        byte[] expectedTail = "a\r\nbar(1)\r\nz\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = new byte[bom.length + expectedTail.length];
        System.arraycopy(bom, 0, expected, 0, bom.length);
        System.arraycopy(expectedTail, 0, expected, bom.length, expectedTail.length);
        assertThat(out).isEqualTo(expected);
    }

    @Test
    @DisplayName("applyToBytes：越界 / 切在 UTF-8 续字节上 → AstEditException")
    void rejectsBadRanges() {
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8); // 3 字节
        assertThatThrownBy(() -> AstEditPlan.applyToBytes(chinese, List.of(
            new AstEditPlan.Change(1, 3, "中", "x", 1, 1, 1, 1))))
            .isInstanceOf(AstEditException.class)
            .hasMessageContaining("splits a UTF-8 character");
        assertThatThrownBy(() -> AstEditPlan.applyToBytes(chinese, List.of(
            new AstEditPlan.Change(0, 4, "中", "x", 1, 1, 1, 1))))
            .isInstanceOf(AstEditException.class)
            .hasMessageContaining("out of bounds");
    }

    @Test
    @DisplayName("空变更 → 返回原文（不移交副本，避免无谓复制）")
    void emptyChangesReturnsOriginal() {
        byte[] original = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(AstEditPlan.applyToBytes(original, List.of())).isSameAs(original);
    }
}

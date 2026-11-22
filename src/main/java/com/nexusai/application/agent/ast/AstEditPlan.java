package com.nexusai.application.agent.ast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个文件的改写计划（合并后的字节区间）与字节级落地。
 *
 * <h2>为什么自己合并不用 {@code --update-all}</h2>
 * AstEdit 支持多 op（{@code ops[{pat,out}]}）。若每个 op 各自 {@code --update-all}，后续 op 会作用在
 * 前一个 op 已改写的文件上 ⇒ 预览与落地不可复现、也无法做「预览后文件是否变过」的指纹校验。
 * 因此统一收集所有 op 的匹配，按 {@code (file, startByte)} 合并后<b>对同一份原始字节一次性拼接</b>。
 *
 * <h2>为什么按字节而不是字符串</h2>
 * CLI 的 {@code range.byteOffset} 是 <b>UTF-8 字节</b>偏移，且文件可能带 BOM / CRLF；
 * {@code Files.readString} + {@code writeString} 往返会重编码并可能改掉行尾 ⇒ 必须
 * {@code Files.readAllBytes} + 字节拼接 + {@code writeAtomic}。
 */
public record AstEditPlan(String file, List<Change> changes) {

    /**
     * 单条替换（字节区间来自 ast-grep，行列为 1 基展示信息）。
     *
     * @param startByte 起始字节（UTF-8，含）
     * @param endByte   结束字节（UTF-8，排他）
     * @param before    原文（{@code text}）
     * @param after     替换文本（{@code out} 展开后的 {@code replacement}；{@code ""} = 删除）
     */
    public record Change(int startByte, int endByte, String before, String after,
                         int line, int column, int endLine, int endColumn) {}

    public AstEditPlan {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 组内排序键（同文件按起始字节升序；相同起点按结束字节升序，保证确定性）。 */
    private static final Comparator<Change> BY_RANGE = Comparator
        .comparingInt(Change::startByte)
        .thenComparingInt(Change::endByte);

    /**
     * 把所有 op 的匹配合并成「每文件一份、按字节升序、无重叠」的改写计划。
     *
     * @param matches 全部匹配（可跨文件、可乱序；{@code replacement} 为 null 的条目被跳过）
     * @return 按展示路径升序的文件计划（仅含有变更的文件）
     * @throws AstEditException 同文件区间重叠（无 {@code replacement} 的按「不参与」跳过）
     */
    public static List<AstEditPlan> merge(List<AstMatch> matches) {
        Map<String, List<Change>> grouped = new LinkedHashMap<>();
        if (matches != null) {
            for (AstMatch m : matches) {
                if (m == null || m.replacement() == null) {
                    continue;
                }
                String key = AstGrepRunner.normalizePath(m.file());
                grouped.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Change(m.startByte(), m.endByte(), m.text(), m.replacement(),
                        m.line(), m.column(), m.endLine(), m.endColumn()));
            }
        }

        List<String> files = new ArrayList<>(grouped.keySet());
        files.sort(String::compareTo);

        List<AstEditPlan> plans = new ArrayList<>();
        for (String file : files) {
            List<Change> sorted = new ArrayList<>(grouped.get(file));
            sorted.sort(BY_RANGE);
            List<Change> kept = new ArrayList<>(sorted.size());
            record ChangeKey(int start, int end, String after) {}
            java.util.Set<ChangeKey> seen = new java.util.HashSet<>();
            int prevEnd = -1;
            for (Change c : sorted) {
                if (c.startByte() < 0 || c.endByte() < c.startByte()) {
                    throw new AstEditException("edit range is out of bounds (file " + file
                        + ", byte " + c.startByte() + ".." + c.endByte() + ")");
                }
                // 多 op 命中同一处且替换相同 → 去重（先于重叠判定）。
                if (!seen.add(new ChangeKey(c.startByte(), c.endByte(), c.after()))) {
                    continue;
                }
                if (!kept.isEmpty() && c.startByte() < prevEnd) {
                    throw new AstEditException("overlapping replacements detected (file " + file
                        + ", byte " + c.startByte() + ".." + c.endByte()
                        + "); refine the pattern to avoid ambiguous edits");
                }
                kept.add(c);
                prevEnd = c.endByte();
            }
            if (!kept.isEmpty()) {
                plans.add(new AstEditPlan(file, kept));
            }
        }
        return plans;
    }

    /**
     * 按变更区间拼接原始字节（单次前向遍历，不复制未涉及区间）。
     *
     * @param original 原始文件字节（含 BOM/CRLF 原样）
     * @param changes  已排序且不重叠的变更
     * @return 改写后的字节
     * @throws AstEditException 区间越界 / 不在 UTF-8 字符边界上
     */
    public static byte[] applyToBytes(byte[] original, List<Change> changes) {
        if (original == null) {
            throw new AstEditException("original bytes is null");
        }
        if (changes == null || changes.isEmpty()) {
            return original;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + 64);
        int cursor = 0;
        for (Change c : changes) {
            int start = c.startByte();
            int end = c.endByte();
            if (start < cursor || end < start || end > original.length) {
                throw new AstEditException("edit range is out of bounds (byte " + start + ".." + end
                    + ", file size " + original.length + ")");
            }
            if (start > cursor) {
                out.write(original, cursor, start - cursor);
            }
            requireUtf8Boundary(original, start);
            requireUtf8Boundary(original, end);
            out.writeBytes(c.after().getBytes(StandardCharsets.UTF_8));
            cursor = end;
        }
        if (cursor < original.length) {
            out.write(original, cursor, original.length - cursor);
        }
        return out.toByteArray();
    }

    /** 区间端点必须落在 UTF-8 字符边界（续字节 10xxxxxx 处切开会把字符劈成两半）。 */
    private static void requireUtf8Boundary(byte[] bytes, int index) {
        if (index < 0 || index > bytes.length) {
            throw new AstEditException("edit range is out of bounds (byte " + index + ")");
        }
        if (index < bytes.length && (bytes[index] & 0xC0) == 0x80) {
            throw new AstEditException("edit range splits a UTF-8 character at byte " + index);
        }
    }
}

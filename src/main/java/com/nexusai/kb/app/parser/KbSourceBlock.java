package com.nexusai.kb.app.parser;

/**
 * 源位置块 · 把「解析器 Markdown 输出里的码点区间」绑定到「原文件里的某个位置」。
 *
 * <p><b>源站对照</b>：WeKnora {@code internal/types/source_locator.go:74-80}（{@code SourceBlock}）。
 *
 * <p><b>{@code start}/{@code end} 是码点偏移</b>（源站 rune），指向<b>解析器最终 Markdown</b>
 * 而不是原始二进制 —— 因为分块器的坐标也是这份 Markdown。
 *
 * <p><b>⭐ 不变式</b>：{@code start >= 0} 且 {@code end > start}；不满足的块由
 * {@link KbSourceIndex#of} 直接丢弃（源站 {@code remap.go:129} 同口径），因为「空区间」定位不到任何东西。
 *
 * @param start   码点起始（含）
 * @param end     码点结束（不含）
 * @param locator 对应的源位置
 */
public record KbSourceBlock(int start, int end, KbSourceLocator locator) {

    public KbSourceBlock {
        if (locator == null) {
            // ⛔ 不允许 null locator：block 的唯一存在意义就是携带 locator
            throw new IllegalArgumentException("KbSourceBlock.locator 不能为 null");
        }
    }

    /** 区间是否有效（正长度）· 无效块不参与索引。 */
    public boolean isValid() {
        return start >= 0 && end > start;
    }

    /** 该块覆盖的码点数。 */
    public int length() {
        return end - start;
    }
}

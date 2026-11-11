package com.nexusai.kb.app.chunk;

/**
 * 分块策略层级 · 对齐 WeKnora {@code chunker.StrategyTier}
 * （{@code profiler.go:203-209}）。
 *
 * <p><b>为什么是枚举而不是字符串</b>：源侧 Go 用 {@code type StrategyTier string}，
 * 拼错一个字母（{@code "headng"}）编译器不会报，只会静默走到 legacy 分支。
 * Java 端用 enum ⇒ 非法层级在编译期就被拦下。
 *
 * <p><b>回退链语义</b>（{@code strategy.go:215-236}）：链是<b>有序</b>的，
 * 前一级输出被 {@link KbChunkValidator} 判为不合格才降级到下一级；
 * {@link #LEGACY} 永远排在链尾，作为「无论如何都给出结果」的兜底。
 */
public enum KbStrategyTier {

    /** Tier 1：按 Markdown 标题边界切分，并给每个块生成标题面包屑（{@code heading_splitter.go}）。 */
    HEADING("heading"),

    /** Tier 2：按编号/章节/分页符/分隔线等启发式边界切分（{@code heuristic_splitter.go}）。 */
    HEURISTIC("heuristic"),

    /** Tier 3（兜底）：递归分隔符 + 受保护片段 + 表格表头前置（{@code splitter.go SplitText}）。 */
    LEGACY("legacy");

    /** 源侧线缆值（配置 {@code strategy} 字段与预览接口 JSON 用的字符串）。 */
    private final String wire;

    KbStrategyTier(String wire) {
        this.wire = wire;
    }

    /**
     * 源侧字符串值。
     *
     * @return {@code heading} / {@code heuristic} / {@code legacy}
     */
    public String wire() {
        return wire;
    }
}

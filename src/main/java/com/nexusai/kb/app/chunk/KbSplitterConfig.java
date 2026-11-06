package com.nexusai.kb.app.chunk;

import java.util.List;

/**
 * 分块配置 · 对齐 WeKnora {@code chunker.SplitterConfig}（{@code splitter.go:64-76}）。
 *
 * <p><b>默认值真源</b>（源侧 {@code splitter.go:78-116} 的注释与常量，逐字核过）：
 * <ul>
 *   <li>{@code DefaultChunkSize = 512} 字符 —— 约 100–130 英文 token / 约 300 中文 token；
 *       源侧注释称该值在 Vecta 2026-02 基准（50 篇论文）上验证为强基线。</li>
 *   <li>{@code DefaultChunkOverlap = 80} 字符（≈15%）—— 源侧把历史上三处不同的默认值
 *       （Go DefaultConfig 64 / knowledge.go 50 / Python docreader 100）统一为 80。</li>
 *   <li>{@code Separators = ["\n\n", "\n", "。"]}。</li>
 * </ul>
 *
 * <p><b>⚠️ 与知识库默认 JSON 的差异</b>：{@code kb_knowledge_base.chunking_config} 的 DB 默认值
 * 写的是 {@code chunk_overlap: 50}（来自源侧 <b>000000_init.up.sql 的 DDL 默认值</b>），
 * 而 Go 侧代码常量是 80。两者在源侧本来就<b>不一致</b> —— 本移植保持源侧原样：
 * DB 默认值抄 DDL，代码默认值抄常量。详见 {@code OPEN_ISSUES.md} O-9。
 *
 * @param chunkSize    目标块大小（字符/runes）
 * @param chunkOverlap 块间重叠（字符/runes）
 * @param separators   分隔符优先级列表（从粗到细）
 * @param strategy     策略：{@code auto|heading|heuristic|recursive|legacy}；空 = legacy
 * @param tokenLimit   token 上限（0 = 只用 chunkSize 字符数）
 * @param languages    语言提示（空 = 自动探测）
 */
public record KbSplitterConfig(
    int chunkSize,
    int chunkOverlap,
    List<String> separators,
    String strategy,
    int tokenLimit,
    List<String> languages
) {

    /** 默认块大小（源侧 {@code DefaultChunkSize}）。 */
    public static final int DEFAULT_CHUNK_SIZE = 512;

    /** 默认重叠（源侧 {@code DefaultChunkOverlap}）。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 80;

    /** 默认分隔符（源侧 {@code DefaultConfig}）。 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。");

    /** 默认配置 · 对齐源侧 {@code DefaultConfig()}（{@code splitter.go:110-116}）。 */
    public static KbSplitterConfig defaults() {
        return new KbSplitterConfig(
            DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, DEFAULT_SEPARATORS, "", 0, List.of());
    }

    /**
     * 规范化 · 对齐源侧 {@code ensureDefaults} + {@code NormalizeSplitterConfig}
     * （{@code strategy.go:192-215}）。
     *
     * <p>规则（逐条对照源侧）：
     * <ol>
     *   <li>{@code chunkSize <= 0} → {@value #DEFAULT_CHUNK_SIZE}</li>
     *   <li>{@code chunkOverlap < 0} → 0</li>
     *   <li>{@code separators} 为空 → {@link #DEFAULT_SEPARATORS}</li>
     * </ol>
     *
     * @return 规范化后的配置
     */
    public KbSplitterConfig normalized() {
        int size = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int overlap = Math.max(chunkOverlap, 0);
        List<String> seps = (separators == null || separators.isEmpty())
            ? DEFAULT_SEPARATORS : separators;
        return new KbSplitterConfig(size, overlap, seps, strategy, tokenLimit, languages);
    }
}

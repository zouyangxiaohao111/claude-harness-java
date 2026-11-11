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
 * @param enableParentChild 是否启用父子分块（源侧 {@code enable_parent_child}，{@code knowledgebase.go:288-290}）
 * @param parentChunkSize   父块大小（源侧默认 4096，{@code knowledgebase.go:291-293}）
 * @param childChunkSize    子块大小（源侧默认 384，{@code knowledgebase.go:294-296}）
 * @param parserEngineRules 按文件类型指定解析引擎（源侧 {@code parser_engine_rules}，
 *                          {@code knowledgebase.go:282-287}）；空 = 走 {@code @Order} 默认顺序
 */
public record KbSplitterConfig(
    int chunkSize,
    int chunkOverlap,
    List<String> separators,
    String strategy,
    int tokenLimit,
    List<String> languages,
    boolean enableParentChild,
    int parentChunkSize,
    int childChunkSize,
    List<ParserEngineRule> parserEngineRules
) {

    /** 默认块大小（源侧 {@code DefaultChunkSize}）。 */
    public static final int DEFAULT_CHUNK_SIZE = 512;

    /** 默认重叠（源侧 {@code DefaultChunkOverlap}）。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 80;

    /** 默认分隔符（源侧 {@code DefaultConfig}）。 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。");

    /** 默认父块大小（源侧 {@code DeriveParentChildConfigs} 的 {@code parentSize <= 0} 分支，{@code strategy.go:231-233}）。 */
    public static final int DEFAULT_PARENT_CHUNK_SIZE = 4096;

    /** 默认子块大小（源侧 {@code DeriveParentChildConfigs} 的 {@code childSize <= 0} 分支，{@code strategy.go:234-236}）。 */
    public static final int DEFAULT_CHILD_CHUNK_SIZE = 384;

    /** 父块尺寸下限（前端滑块 512–8192，见 {@code KB-SETTINGS-ALIGN-PLAN.md}）。 */
    public static final int MIN_PARENT_CHUNK_SIZE = 512;

    /** 父块尺寸上限。 */
    public static final int MAX_PARENT_CHUNK_SIZE = 8192;

    /** 子块尺寸下限（前端滑块 64–2048）。 */
    public static final int MIN_CHILD_CHUNK_SIZE = 64;

    /** 子块尺寸上限。 */
    public static final int MAX_CHILD_CHUNK_SIZE = 2048;

    public KbSplitterConfig {
        separators = separators == null ? DEFAULT_SEPARATORS : separators;
        languages = languages == null ? List.of() : List.copyOf(languages);
        parserEngineRules = parserEngineRules == null ? List.of() : List.copyOf(parserEngineRules);
    }

    /**
     * 兼容构造器 · 不带父子与解析引擎规则（既有调用点与单测用）。
     *
     * <p>父子关闭、规则为空 ⇒ 行为与引入本组字段前完全一致。
     */
    public KbSplitterConfig(
        int chunkSize,
        int chunkOverlap,
        List<String> separators,
        String strategy,
        int tokenLimit,
        List<String> languages
    ) {
        this(chunkSize, chunkOverlap, separators, strategy, tokenLimit, languages,
            false, DEFAULT_PARENT_CHUNK_SIZE, DEFAULT_CHILD_CHUNK_SIZE, List.of());
    }

    /** 默认配置 · 对齐源侧 {@code DefaultConfig()}（{@code splitter.go:110-116}）。 */
    public static KbSplitterConfig defaults() {
        return new KbSplitterConfig(
            DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, DEFAULT_SEPARATORS, "", 0, List.of(),
            false, DEFAULT_PARENT_CHUNK_SIZE, DEFAULT_CHILD_CHUNK_SIZE, List.of());
    }

    /**
     * 规范化 · 对齐源侧 {@code ensureDefaults} + {@code NormalizeSplitterConfig}
     * （{@code strategy.go:192-215} / {@code :333-356}）。
     *
     * <p>五条规则（逐条对照源侧，⚠️ 第 4/5 条是本批新增，源侧 {@code :338-354}）：
     * <ol>
     *   <li>{@code chunkSize <= 0} → {@value #DEFAULT_CHUNK_SIZE}</li>
     *   <li>{@code chunkOverlap < 0} → 0</li>
     *   <li>{@code separators} 为空 → {@link #DEFAULT_SEPARATORS}</li>
     *   <li><b>{@code tokenLimit > 0} ⇒ chunkSize 收缩到字符预算</b>
     *       （{@link KbTokenEstimator#charsForTokenLimit}，×0.9 安全系数）。
     *       目的：让分块适配 embedding 模型的 token 硬上限，而不是只按字符数。</li>
     *   <li><b>{@code chunkOverlap > chunkSize/2} ⇒ 压到 {@code chunkSize/2}</b>
     *       （{@code strategy.go:352}）。目的：重叠超过一半时几乎每块都是上一块的副本，
     *       浪费存储还稀释检索多样性。</li>
     * </ol>
     *
     * <p>⚠️ 两条新规则<b>有次序</b>：先按 token 收缩 size，再按新 size 压 overlap ——
     * 反了的话 overlap 会按旧 size 放宽，用户仍然拿到「超过一半重叠」的配置。
     *
     * @return 规范化后的配置
     */
    public KbSplitterConfig normalized() {
        int size = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
        int overlap = Math.max(chunkOverlap, 0);
        List<String> seps = (separators == null || separators.isEmpty())
            ? DEFAULT_SEPARATORS : separators;

        // 规则 4：token 上限 → 字符预算（源侧 strategy.go:338-347）
        if (tokenLimit > 0) {
            String lang = (languages == null || languages.isEmpty())
                ? KbTokenEstimator.LANG_MIXED : languages.get(0);
            int charBudget = KbTokenEstimator.charsForTokenLimit(tokenLimit, lang);
            if (charBudget > 0 && charBudget < size) {
                size = charBudget;
            }
        }

        // 规则 5：重叠不得超过 chunkSize/2（源侧 strategy.go:352）
        if (size > 0 && overlap > size / 2) {
            overlap = size / 2;
        }

        // 父子尺寸 clamp（前端滑块给的是区间，越界一律夹紧而不是回落默认 ——
        // 用户调过头时「离得最近的那个合法值」比「默默变回 4096」更符合预期）。
        // ⚠️ <=0 视为「未设置」⇒ 用默认值（源侧 DeriveParentChildConfigs 的 <=0 分支同语义）。
        int parentSize = parentChunkSize <= 0 ? DEFAULT_PARENT_CHUNK_SIZE
            : Math.min(Math.max(parentChunkSize, MIN_PARENT_CHUNK_SIZE), MAX_PARENT_CHUNK_SIZE);
        int childSize = childChunkSize <= 0 ? DEFAULT_CHILD_CHUNK_SIZE
            : Math.min(Math.max(childChunkSize, MIN_CHILD_CHUNK_SIZE), MAX_CHILD_CHUNK_SIZE);

        return new KbSplitterConfig(size, overlap, seps, strategy, tokenLimit, languages,
            enableParentChild, parentSize, childSize, parserEngineRules);
    }
}

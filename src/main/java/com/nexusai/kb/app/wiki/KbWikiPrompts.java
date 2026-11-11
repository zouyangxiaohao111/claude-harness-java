package com.nexusai.kb.app.wiki;

import com.nexusai.kb.app.prompt.KbPromptLoader;

/**
 * Wiki 生成提示词（10 条）· W2-2。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/application/service/prompts_wiki.go}（597 行）。
 * 本类逐条中文重写，<b>硬约束、占位符、输出格式一字不改</b>。
 *
 * <p><b>提示词正文已外置</b>到 {@code resources/kb/prompt/wiki/*.md}（用户 2026-11-09 裁定
 * 「所有提示词都应该这么放，仅限 kb 场景」）。本类只保留<b>引用</b>与两个<b>代码逻辑</b>
 * （粒度文本 {@link #granularityBlock}、占位符清单 {@link #requiredPlaceholders}）。
 * 改提示词内容请改 {@code .md}，⛔ 不要往 Java 里塞回文本块。
 *
 * <h2>⭐⭐ 三条铁律（写这些提示词时必须守住）</h2>
 * <ol>
 *   <li><b>文件名 / 标题永不喂给模型</b>（源侧注释 {@code prompts_wiki.go:52-59}）：
 *       上传的文档常带无关文件名（扫描件按扫描仪型号命名 {@code MX5280.pdf}）。
 *       喂进去会在抽取内容稀薄时<b>诱发幻觉摘要</b>。
 *       ⇒ 摘要页 slug 用 {@code knowledgeId}；{@code sourceRef} 只用 ID。</li>
 *   <li><b>chunk id 永不进正文</b>（{@code [c003]} 是内部元数据）：
 *       ① 不许新写；② <b>编辑时还要删掉历史遗留的</b>。
 *       源关联由系统单独存 {@code chunk_refs} —— 两者分离才对。</li>
 *   <li><b>「你是编译器，不是作家」</b>（原文 {@code You are a COMPILER, not a creative writer}）：
 *       只许轻度重排/去重/合并相邻句，⛔ 不许为文风改写、不许扩写、不许加过渡词。
 *       润色就等于允许编造。</li>
 * </ol>
 *
 * <h2>⚠️ 提示词里的大括号不要写成 {@code \{} </h2>
 * 这些是普通字符串，不做格式化。占位符统一用 {@code {{NAME}}} 形式，
 * 由调用方 {@link String#replace} 替换（⛔ 不用 {@code String.format}，
 * JSON 示例里有大量 {@code {}} 会被误当格式符）。
 */
public final class KbWikiPrompts {

    private KbWikiPrompts() {
    }

    // ═══════════════════════════════════════════════════════════
    // Map 阶段
    // ═══════════════════════════════════════════════════════════

    /**
     * ① 候选 slug 抽取（源侧 {@code WikiCandidateSlugPrompt} {@code :172}）。
     *
     * <p><b>只抽清单，不写正文</b> —— 这样摘要与引用分类可以并行（互不依赖）。
     *
     * <p>两个必须保留的段落：
     * <ul>
     *   <li>{@code <previous_slugs>} —— 文档更新时<b>复用旧 slug</b>（同一实体不换 slug）；</li>
     *   <li>{@code <granularity>} —— 三档粒度指导（focused / standard / exhaustive）。</li>
     * </ul>
     * <p>正文：{@code resources/kb/prompt/wiki/candidate-slug.system.md}（外置，改内容改这里）。
     */
    public static final String CANDIDATE_SLUG_SYSTEM = KbPromptLoader.cached("wiki/candidate-slug.system.md");

    /**
     * 候选抽取的用户提示词（装数据，不装规则）。
     *
     * <h2>⚠️ 块顺序有性能意义（源侧按此顺序以命中 provider 前缀缓存）</h2>
     * {@code <document_content>} 放最后 —— 它是唯一每次变化的部分。
     * <p>正文：{@code resources/kb/prompt/wiki/candidate-slug.user.md}（外置，改内容改这里）。
     */
    public static final String CANDIDATE_SLUG_USER = KbPromptLoader.cached("wiki/candidate-slug.user.md");

    /** 三档粒度指导（源侧 {@code :537-596}）。 */
    public static String granularityBlock(String granularity) {
        String g = granularity == null ? "standard" : granularity.trim().toLowerCase();
        return switch (g) {
            case "focused" -> """
                <granularity>
                粒度：**聚焦**。
                只保留文档的**主要对象** —— 整篇文档真正在讲的那 3-7 个东西。
                以下一律**排除**：技术栈与框架、顺带提到的工具、举例里的对象、
                只出现一次的名字、上级类别（如「数据库」之于「MySQL」）。
                宁少勿多：漏掉一个次要对象可以接受，收录一个无关对象是错误。
                </granularity>
                """;
            case "exhaustive" -> """
                <granularity>
                粒度：**穷尽**。
                收录所有具名实体与可识别的概念，包括只提起一次的技术、工具、标准、指标。
                只排除真正的泛称（如「方法」「系统」「内容」这类没有指代对象的词）。
                有歧义时偏向收录。
                </granularity>
                """;
            default -> """
                <granularity>
                粒度：**标准**。
                收录：文档的主要对象 + 有独立段落或有多条要点的次要对象。
                排除：纯列表里顺带提到的名字、举例中的对象、上级类别、无指代对象的泛称。
                </granularity>
                """;
        };
    }

    /**
     * ② 文档摘要页（源侧 {@code WikiSummaryPrompt} {@code :60}）。
     *
     * <h2>⭐⭐ 文件名与标题<b>故意不传</b></h2>
     * 见类注释铁律 1。
     *
     * <h2>⚠️ 空内容规则</h2>
     * 正文为空（扫描件）⇒ 只输出固定句 + 简短说明，⛔ 不许猜主题、不许从别处推断。
     * <p>正文：{@code resources/kb/prompt/wiki/summary.system.md}（外置，改内容改这里）。
     */
    public static final String SUMMARY_SYSTEM = KbPromptLoader.cached("wiki/summary.system.md");

    /**
     * 摘要页的用户提示词。
     *
     * <p>⚠️ {@code <available_wiki_pages>} 在前（同一知识库内相对稳定，利于前缀缓存），
     * {@code <document_content>} 在最后（每次都变）。
     * <p>正文：{@code resources/kb/prompt/wiki/summary.user.md}（外置，改内容改这里）。
     */
    public static final String SUMMARY_USER = KbPromptLoader.cached("wiki/summary.user.md");

    /**
     * ③ chunk 引用分类（源侧 {@code WikiChunkCitationPrompt} {@code :260}）。
     *
     * <p><b>为什么不直接写正文</b>：引用只记 chunk id，<b>页面证据保持原文</b>，
     * 避免模型改写导致事实漂移。
     *
     * <p>⭐ 块顺序：候选清单在前（每文档不变，命中前缀缓存），chunk 在后（每批变化）。
     * <p>正文：{@code resources/kb/prompt/wiki/chunk-citation.system.md}（外置，改内容改这里）。
     */
    public static final String CHUNK_CITATION_SYSTEM = KbPromptLoader.cached("wiki/chunk-citation.system.md");

    /**
     * chunk 引用分类的用户提示词。
     *
     * <p>⭐ 顺序是刻意的：候选清单每文档不变 ⇒ 放前；chunk 每批变化 ⇒ 放后。
     * <p>正文：{@code resources/kb/prompt/wiki/chunk-citation.user.md}（外置，改内容改这里）。
     */
    public static final String CHUNK_CITATION_USER = KbPromptLoader.cached("wiki/chunk-citation.user.md");

    /**
     * ④ 去重判同（源侧 {@code WikiDeduplicationPrompt} {@code :470}）。
     *
     * <p><b>核心原则</b>：「宁可同一事物两个页面，也不要错误合并两个不同事物」。
     * <p>正文：{@code resources/kb/prompt/wiki/deduplication.system.md}（外置，改内容改这里）。
     */
    public static final String DEDUPLICATION_SYSTEM = KbPromptLoader.cached("wiki/deduplication.system.md");

    /**
     * 去重判同的用户提示词。
     *
     * <p>每个条目一个 {@code <candidates>} 块 —— 源侧强调「只能合并到该条目自己的 candidates」。
     * <p>正文：{@code resources/kb/prompt/wiki/deduplication.user.md}（外置，改内容改这里）。
     */
    public static final String DEDUPLICATION_USER = KbPromptLoader.cached("wiki/deduplication.user.md");

    // ═══════════════════════════════════════════════════════════
    // Reduce 阶段
    // ═══════════════════════════════════════════════════════════

    /**
     * ⑤ 目录规划（源侧 {@code WikiTaxonomyPlanPrompt} {@code :10}）。
     *
     * <p>⭐⭐ <b>一批只调一次</b>，给该批<b>所有页面</b>统一分目录。
     * 若每页各自规划，同一主题会被分到不同目录，树会分裂。
     * <p>正文：{@code resources/kb/prompt/wiki/taxonomy-plan.system.md}（外置，改内容改这里）。
     */
    public static final String TAXONOMY_PLAN_SYSTEM = KbPromptLoader.cached("wiki/taxonomy-plan.system.md");

    /**
     * 目录规划的用户提示词。
     * <p>正文：{@code resources/kb/prompt/wiki/taxonomy-plan.user.md}（外置，改内容改这里）。
     */
    public static final String TAXONOMY_PLAN_USER = KbPromptLoader.cached("wiki/taxonomy-plan.user.md");

    /**
     * ⑥ 页面合并 —— <b>系统提示词，只有规则</b>（源侧 {@code WikiPageModifySystemPrompt} {@code :321}）。
     *
     * <p>⭐⭐ 13 条规则全部是「不许做什么」。第二条（强制有据）与第三条（冲突处理）
     * 是防止事实漂移的核心。
     * <p>正文：{@code resources/kb/prompt/wiki/page-modify.system.md}（外置，改内容改这里）。
     */
    public static final String PAGE_MODIFY_SYSTEM = KbPromptLoader.cached("wiki/page-modify.system.md");

    /**
     * ⑥ 页面合并 —— 用户提示词，装数据（源侧 {@code WikiPageModifyUserPrompt} {@code :345}）。
     *
     * <h2>⚠️ 块顺序刻意如此</h2>
     * {@code <shared_source_contexts>} 在最前 —— 同文档的所有页面共享这段，
     * 利于命中 provider 的前缀缓存。{@code <new_information>} 靠后（每页不同）。
     *
     * <h2>⭐⭐ 冲突检查（原文强调两次）</h2>
     * 必须先核实新信息<b>真的</b>在讲这个页面。若新信息属于<b>另一个</b>相关事物
     * ⇒ <b>必须拒绝这部分新信息</b>，不要加进去。
     * <p>正文：{@code resources/kb/prompt/wiki/page-modify.user.md}（外置，改内容改这里）。
     */
    public static final String PAGE_MODIFY_USER = KbPromptLoader.cached("wiki/page-modify.user.md");

    /**
     * ⑦ 截断续写（源侧 {@code WikiPageModifyContinuationPrompt} {@code :413}）。
     *
     * <p>⭐⭐ <b>为什么必须单独做</b>：长枚举（如一百多行的证书台账）会逐行输出直到
     * 被 {@code finish_reason=length} 打断，后果是<b>页面存下来少了三分之一</b>，
     * 而且不报错。回放已写部分 + 本提示词，要求从断点第一个字符接着写。
     * <p>正文：{@code resources/kb/prompt/wiki/page-modify-continuation.system.md}（外置，改内容改这里）。
     */
    public static final String PAGE_MODIFY_CONTINUATION_SYSTEM = KbPromptLoader.cached("wiki/page-modify-continuation.system.md");

    /**
     * 续写的用户提示词。
     * <p>正文：{@code resources/kb/prompt/wiki/page-modify-continuation.user.md}（外置，改内容改这里）。
     */
    public static final String PAGE_MODIFY_CONTINUATION_USER = KbPromptLoader.cached("wiki/page-modify-continuation.user.md");

    /**
     * ⑧ 索引页介绍（首次，源侧 {@code WikiIndexIntroPrompt} {@code :427}）。
     *
     * <p>⛔ 不许生成目录列表 —— 那是系统拼的（会与真实放不进去的页面不一致）。
     * <p>正文：{@code resources/kb/prompt/wiki/index-intro.system.md}（外置，改内容改这里）。
     */
    public static final String INDEX_INTRO_SYSTEM = KbPromptLoader.cached("wiki/index-intro.system.md");

    /** ⑨ 索引页介绍（增量更新，源侧 {@code WikiIndexIntroUpdatePrompt} {@code :443}）。 */
    public static final String INDEX_INTRO_UPDATE_SYSTEM = KbPromptLoader.cached("wiki/index-intro-update.system.md");

    /**
     * 索引页介绍的用户提示词（首次 / 更新共用）。
     *
     * <p>{@code {{EXISTING_INTRO}}} 在首次生成时替换为空串。
     * <p>正文：{@code resources/kb/prompt/wiki/index-intro.user.md}（外置，改内容改这里）。
     */
    public static final String INDEX_INTRO_USER = KbPromptLoader.cached("wiki/index-intro.user.md");

    // ═══════════════════════════════════════════════════════════
    // 占位符清单（供测试断言"占位符齐全"）
    // ═══════════════════════════════════════════════════════════

    /** 每条提示词模板必须包含的占位符（供单测逐条核对）。 */
    public static java.util.Map<String, java.util.List<String>> requiredPlaceholders() {
        java.util.Map<String, java.util.List<String>> m = new java.util.LinkedHashMap<>();
        m.put("CANDIDATE_SLUG_USER",
            java.util.List.of("{{GRANULARITY_BLOCK}}", "{{PREVIOUS_SLUGS}}", "{{DOCUMENT_CONTENT}}"));
        m.put("SUMMARY_USER",
            java.util.List.of("{{AVAILABLE_PAGES}}", "{{DOCUMENT_CONTENT}}"));
        m.put("CHUNK_CITATION_USER",
            java.util.List.of("{{CANDIDATE_SLUGS}}", "{{CHUNKS}}"));
        m.put("DEDUPLICATION_USER",
            java.util.List.of("{{NEW_SLUGS}}", "{{CANDIDATES_BY_SLUG}}"));
        m.put("TAXONOMY_PLAN_USER",
            java.util.List.of("{{EXISTING_FOLDERS}}", "{{PAGES}}"));
        m.put("PAGE_MODIFY_USER",
            java.util.List.of("{{SHARED_SOURCE_CONTEXTS}}", "{{PAGE_SLUG}}", "{{PAGE_TITLE}}",
                "{{PAGE_TYPE}}", "{{PAGE_ALIASES}}", "{{EXISTING_CONTENT}}",
                "{{NEW_INFORMATION}}", "{{DELETION_BLOCK}}", "{{VALID_WIKI_LINKS}}"));
        m.put("PAGE_MODIFY_CONTINUATION_USER",
            java.util.List.of("{{WRITTEN_SO_FAR}}"));
        m.put("INDEX_INTRO_USER",
            java.util.List.of("{{EXISTING_INTRO}}", "{{CHANGES}}", "{{PAGES_OVERVIEW}}"));
        return m;
    }
}

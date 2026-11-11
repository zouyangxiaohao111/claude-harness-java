package com.nexusai.kb.app.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本切分器 · 自适应分块入口。
 *
 * <p><b>入口分层</b>（对齐 WeKnora {@code chunker/strategy.go:34-123}）：
 * <ul>
 *   <li>{@link #split} —— 热路径，只返回分块</li>
 *   <li>{@link #splitWithDiagnostics} —— 预览/调试用，额外返回「选了哪一级、为什么降级」</li>
 *   <li>{@link #splitLegacyRaw} —— 只跑 Tier3（legacy），供另两级内部复用</li>
 * </ul>
 *
 * <p><b>Tier3（legacy）三大步骤</b>（源侧 {@code splitter.go:9-25} 原注释）：
 * <ol>
 *   <li>找出受保护片段（公式/代码/链接/表格），它们是原子单元</li>
 *   <li>非保护区按分隔符优先级递归切分；受保护区整体保留</li>
 *   <li>把单元合并成分块，带重叠，并处理表头前置</li>
 * </ol>
 *
 * <p><b>⭐ 递归分隔符语义</b>（源侧 {@code splitter.go:215-223} 注释，照 Python 参考实现）：
 * 若 {@code \n\n} 切出的某片仍超长，则在该片内部继续用 {@code \n} 切 ——
 * <b>不是</b>对整段重新用低优先级切。
 *
 * <p><b>⭐ 绝对上限 7500</b>（源侧 {@code splitter.go:314} / {@code :407}）：
 * 受保护片段若超过 7500 字符会被强制切开；单块超过 7500 也会被强制切开。
 * 这是为下游 embedding API 的硬限制留余量。
 *
 * <p><b>⭐ 行尾不变式（本仓特有约束，务必读完）</b>：
 * 源侧在<b>上游</b>做行尾归一（{@code knowledge_create.go:1427} /
 * {@code chunker_debug.go:150}），切分原语本身<b>不改文本</b>。
 * 本仓 {@code KnowledgeParseProcessor} 用<b>未归一化</b>的 {@code parsed.text()} 做图片归属
 * （{@code assignImages} 的字符偏移 → code point 偏移），若切分器内部静默把 CRLF 压成 LF，
 * 块偏移与原文坐标系就会错位 ⇒ <b>图片静默挂错块</b>。
 * 因此 {@link #split} 遇到 {@code \r} 只<b>告警</b>、不改文本；
 * 需要归一化的调用方（预览接口）显式调 {@link #normalizeLineEndings}。
 */
public final class KbTextSplitter {

    private static final Logger log = LoggerFactory.getLogger(KbTextSplitter.class);

    /** 受保护片段的最大尺寸（源侧 {@code splitter.go:314 maxProtectedSize}）。 */
    private static final int MAX_PROTECTED_SIZE = 7500;

    /** 单块的绝对最大尺寸（源侧 {@code splitter.go:407 absoluteMaxSize}）。 */
    private static final int ABSOLUTE_MAX_SIZE = 7500;

    /** 强制切分时向前找断点的最大回溯距离（源侧 {@code splitter.go:438} / {@code :366}）。 */
    private static final int FORCE_SPLIT_LOOKBACK = 200;

    private KbTextSplitter() {
    }

    // ════════════════════════════════════════════════════════════════════
    // 入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * 切分文本 · 入口（签名不变：C 批与 {@code KnowledgeParseProcessor} 依赖）。
     *
     * @param text 原文
     * @param cfg  配置（会先规范化）
     * @return 分块列表；空文本返回空列表
     */
    public static List<KbChunk> split(String text, KbSplitterConfig cfg) {
        return splitWithDiagnostics(text, cfg).chunks();
    }

    /**
     * 父子分块 · 对齐源侧 {@code SplitParentChild}（{@code strategy.go:135-184}）。
     *
     * <p><b>算法逐条照抄源站</b>（{@code strategy.go:148-183}）：
     * <ol>
     *   <li>{@code parents = Split(text, parentCfg)}（策略感知，走三级链）</li>
     *   <li>每个父块再用 {@code childCfg} 切一次子块，并把子块偏移换回全文坐标
     *       （{@link #mapChildrenToSource}，源侧 {@code splitter.go:896-912}）</li>
     *   <li><b>父块只在「被切成 &gt;1 个子块」或「唯一子块内容 ≠ 父块内容」时保留</b>
     *       （源侧 {@code strategy.go:172}）。否则子块就是父块本身，存父指针纯属冗余 ——
     *       而冗余会带来「检索命中子块后又把同样的内容拼一遍」的双倍上下文。</li>
     *   <li>子块 seq 在全文档内单调递增（源侧 {@code childSeq}，{@code :167/177-180}）；
     *       context_header 取「父面包屑 + 子面包屑」合并（{@link #mergeBreadcrumbs}，
     *       源侧 {@code :178} 与 {@code :260-277}）。</li>
     * </ol>
     *
     * <p><b>⭐ 坐标系</b>：子块的 {@code start/end} 必须是<b>全文坐标</b> —— 源站靠
     * {@code mapChildrenToSource} 做偏移换算，⛔ 不是父块内相对坐标。落库后
     * 「命中子块 → 展开父块」要按 {@code start_at/end_at} 做文档重建，相对坐标会静默错位。
     *
     * @param text      原文（与返回的 start/end 同一坐标系）
     * @param parentCfg 父块配置（会用其 {@code chunkSize}；其余字段沿用）
     * @param childCfg  子块配置（通常 {@code chunkSize = childChunkSize}、{@code overlap = size/5}）
     * @return 父块列表 + 子块列表（父块只含<b>被保留</b>的那批；子块的 {@code parentIndex}
     *         指向父块列表下标，{@code -1} = 无父块）
     */
    public static ParentChildResult splitParentChild(
        String text, KbSplitterConfig parentCfg, KbSplitterConfig childCfg) {
        KbSplitterConfig pc = parentCfg == null ? KbSplitterConfig.defaults() : parentCfg;
        KbSplitterConfig cc = childCfg == null ? KbSplitterConfig.defaults() : childCfg;

        List<KbChunk> parents = split(text, pc);
        if (parents.isEmpty()) {
            log.info("[分块] 父子：父块为空 ⇒ 无父子产物");
            return ParentChildResult.EMPTY;
        }

        List<KbChunk> newParents = new ArrayList<>();
        List<KbChunk> children = new ArrayList<>();
        int childSeq = 0;
        int retainedParents = 0;
        int standaloneChildren = 0;

        for (KbChunk parent : parents) {
            List<KbChunk> subs = mapChildrenToSource(parent, split(parent.content(), cc));

            // ⭐ 父块保留条件（源侧 strategy.go:171-175）
            int parentIndex = KbChunk.NO_PARENT;
            if (subs.size() > 1 || (subs.size() == 1 && !subs.get(0).content().equals(parent.content()))) {
                parentIndex = newParents.size();
                newParents.add(parent);
                retainedParents++;
            } else {
                standaloneChildren += subs.size();
            }

            for (KbChunk sub : subs) {
                String header = mergeBreadcrumbs(parent.contextHeader(), sub.contextHeader());
                children.add(new KbChunk(
                    sub.content(), header, childSeq++, sub.start(), sub.end(),
                    sub.images(), sub.sourceLocators(), parentIndex));
            }
        }

        log.info("[分块] 父子：父块总数={} 保留父块={} 子块={} 无父块子块={} "
                + "父尺寸={} 子尺寸={} 子重叠={}",
            parents.size(), retainedParents, children.size(), standaloneChildren,
            pc.chunkSize(), cc.chunkSize(), cc.chunkOverlap());
        return new ParentChildResult(List.copyOf(newParents), List.copyOf(children));
    }

    /**
     * 把父块内相对偏移换算成全文偏移 · 对齐源侧 {@code mapChildrenToSource}
     * （{@code splitter.go:896-912}）。
     *
     * <p>源站算法逐字照抄（{@code :900-905}）：
     * <pre>
     *   offset     = parent.End - runeLen(parent.Content)
     *   child.Start = max(parent.Start, offset + child.Start)
     *   child.End  += offset
     *   丢弃 child.End &lt;= child.Start 的块
     * </pre>
     *
     * <p>⚠️ 为什么用 {@code parent.end() - 内容长度} 而不是直接 {@code parent.start()}：
     * 父块内容可能带<b>表头前置</b>（零宽合成单元，不占原文位置）⇒ 内容长度 &gt; span，
     * 直接加 start 会让子块偏移整体前移。源侧正是在处理这一点。
     * {@code Math.max} 同理：重叠会让 {@code offset + child.start} 落到父块起点之前。
     *
     * @param parent 父块（全文坐标）
     * @param subs   在 {@code parent.content()} 上切出的子块（相对坐标）
     * @return 换算后的子块（已丢弃空区间）
     */
    static List<KbChunk> mapChildrenToSource(KbChunk parent, List<KbChunk> subs) {
        if (subs == null || subs.isEmpty()) {
            return List.of();
        }
        int offset = parent.end() - KbTextUnits.codePointLength(parent.content());
        List<KbChunk> out = new ArrayList<>(subs.size());
        for (KbChunk child : subs) {
            int start = Math.max(parent.start(), offset + child.start());
            int end = offset + child.end();
            if (end <= start) {
                continue;
            }
            out.add(new KbChunk(child.content(), child.contextHeader(), child.seq(), start, end,
                child.images(), child.sourceLocators(), child.parentIndex()));
        }
        return out;
    }

    /**
     * 合并父/子面包屑 · 对齐源侧 {@code mergeBreadcrumbs}（{@code strategy.go:260-277}）。
     *
     * <p>子块重新在父块内容上跑标题检测时，它的第一条面包屑行通常与父块最后一行重复
     * （父块的开头标题就落在子块输入的首行）⇒ 去掉重复那行，避免 embedding 上下文冗余。
     *
     * @param parent 父块面包屑（可空）
     * @param child  子块面包屑（可空）
     * @return 合并结果（两侧都空 ⇒ 空串）
     */
    static String mergeBreadcrumbs(String parent, String child) {
        if (parent == null || parent.isEmpty()) {
            return child == null ? "" : child;
        }
        if (child == null || child.isEmpty()) {
            return parent;
        }
        String[] parentLines = parent.split("\n", -1);
        String[] childLines = child.split("\n", -1);
        int from = 0;
        if (parentLines.length > 0 && childLines.length > 0
            && parentLines[parentLines.length - 1].strip().equals(childLines[0].strip())) {
            from = 1;
        }
        if (from >= childLines.length) {
            return parent;
        }
        StringBuilder sb = new StringBuilder(parent);
        for (int i = from; i < childLines.length; i++) {
            sb.append('\n').append(childLines[i]);
        }
        return sb.toString();
    }

    /**
     * 切分文本并返回诊断 · 对齐源侧 {@code SplitWithDiagnostics}
     * （{@code strategy.go:88-123}）。
     *
     * <p><b>回退链语义</b>（逐条照抄源侧）：
     * <ol>
     *   <li>按链逐级试；某级输出通过 {@link KbChunkValidator} 就<b>立刻返回</b></li>
     *   <li>不通过 ⇒ 记 {@link TierRejection}，降级</li>
     *   <li>链尾是 LEGACY（源侧无条件追加）；若连它也不合格，仍返回它的结果
     *       （源侧 {@code :117-120}：宁可给一份不完美结果，也不给空）</li>
     *   <li>防御性兜底：再跑一次 legacy</li>
     * </ol>
     *
     * <p><b>⚠️ 与源站的差异（有意）</b>：源侧在显式 strategy（heading/heuristic/legacy）下
     * <b>不</b>做画像（{@code profile = nil}）。本实现<b>总是</b>做一次画像，
     * 因为：
     * <ol>
     *   <li>用户要求「日志要能回答为什么是这个分块结果」—— 画像信号是首要证据；</li>
     *   <li>预览接口无论如何都要补算画像（{@code chunker_debug.go:207-210}）；</li>
     *   <li>源侧自己写明画像「a few regex passes + rune counting」很便宜。</li>
     * </ol>
     * 行为（分块结果）不受影响，只多一趟扫描。
     *
     * @param text 原文
     * @param cfg  配置
     * @return 分块 + 诊断（永不为 null）
     */
    public static SplitDiagnostics splitWithDiagnostics(String text, KbSplitterConfig cfg) {
        KbSplitterConfig c = (cfg == null ? KbSplitterConfig.defaults() : cfg).normalized();
        if (text == null || text.isEmpty()) {
            log.info("[分块] 空文本 ⇒ 不产出分块");
            return new SplitDiagnostics(List.of(),
                new Diagnostics(KbStrategyTier.LEGACY, List.of(), List.of(), null));
        }

        warnIfCarriageReturn(text, c);

        // ── 画像（一次扫描，链选择与日志共用）──────────────────────────
        KbDocProfile profile = KbDocProfile.profile(text);
        int totalChars = profile.totalChars();
        List<KbStrategyTier> chain = resolveChain(c.strategy(), profile);

        if (log.isInfoEnabled()) {
            log.info("[分块] 画像 chars={} lines={} h1={} h2={} h3={} h4={} h5={} h6={} "
                    + "md总={} 密度={} 主导层级={} 编号={} 全大写={} 分隔线={} 换页符={} "
                    + "章节(德/英/中)={}/{}/{} 页脚={} 空段={} 表格={} 代码={} 代码占比={} 语言={}",
                profile.totalChars(), profile.totalLines(),
                profile.headingCount(1), profile.headingCount(2), profile.headingCount(3),
                profile.headingCount(4), profile.headingCount(5), profile.headingCount(6),
                profile.mdHeadingTotal(), String.format("%.4f", profile.headingDensity()),
                profile.dominantHeadingLevel(), profile.numberedSectionCount(),
                profile.allCapsShortLineCount(), profile.visualSepCount(), profile.formFeedCount(),
                profile.germanChapterCount(), profile.englishChapterCount(),
                profile.chineseChapterCount(), profile.repeatedFooterCount(),
                profile.blankParagraphBreaks(), profile.hasTables(), profile.hasCode(),
                String.format("%.3f", profile.codeRatio()), profile.detectedLangs());
            log.info("[分块] 策略链 {} （配置 strategy=\"{}\" chunkSize={} overlap={} tokenLimit={} languages={}）",
                chain, c.strategy(), c.chunkSize(), c.chunkOverlap(), c.tokenLimit(), c.languages());
        }

        List<TierRejection> rejected = new ArrayList<>();
        List<KbChunk> lastLegacyOut = null;

        for (int i = 0; i < chain.size(); i++) {
            KbStrategyTier tier = chain.get(i);
            long tierStart = System.currentTimeMillis();
            List<KbChunk> chunks = runTier(tier, text, c, profile);
            ChunkStats st = ChunkStats.of(chunks);

            KbChunkValidator.ValidationResult v =
                KbChunkValidator.validate(chunks, totalChars, c.chunkSize());
            String reason = v.ok() ? "" : v.reason();
            long cost = System.currentTimeMillis() - tierStart;

            if (v.ok()) {
                log.info("[分块] 命中 tier={} chunks={} avg={} max={} min={} 耗时={}ms",
                    tier, st.count, st.avg, st.max, st.min, cost);
                logContextHeaderStats(tier, chunks);
                return new SplitDiagnostics(chunks,
                    new Diagnostics(tier, chain, rejected, profile));
            }

            // ⭐ 降级原因必须能看出「为什么这一级不行」
            log.warn("[分块] tier={} 被拒 ⇒ 降级：{} (chunks={} avg={} max={} min={} 耗时={}ms)",
                tier, reason, st.count, st.avg, st.max, st.min, cost);
            rejected.add(new TierRejection(tier, reason));

            if (tier == KbStrategyTier.LEGACY && i == chain.size() - 1) {
                lastLegacyOut = chunks;
            }
        }

        if (lastLegacyOut != null) {
            log.warn("[分块] 全部 tier 不合格，回退 legacy 结果 chunks={}（源站同样兜底）",
                lastLegacyOut.size());
            logContextHeaderStats(KbStrategyTier.LEGACY, lastLegacyOut);
            return new SplitDiagnostics(lastLegacyOut,
                new Diagnostics(KbStrategyTier.LEGACY, chain, rejected, profile));
        }
        List<KbChunk> fallback = splitLegacyRaw(text, c);
        log.warn("[分块] 防御性兜底：链尾非 legacy，直接跑 legacy chunks={}", fallback.size());
        logContextHeaderStats(KbStrategyTier.LEGACY, fallback);
        return new SplitDiagnostics(fallback,
            new Diagnostics(KbStrategyTier.LEGACY, chain, rejected, profile));
    }

    /**
     * 行尾归一 · 对齐源侧 {@code NormalizeLineEndings}（{@code strategy.go:213-223}）。
     *
     * <p><b>为什么必须在上游做</b>（源侧注释原文）：浏览器 textarea 会把粘贴的 CRLF 归一成 LF，
     * 而上传文件保留原始行尾 —— 不归一会导致同一份文档算出不同的字符数与不同的块边界。
     *
     * <p>⚠️ <b>本类不内部调用它</b>（见类注释「行尾不变式」）：切分器返回的
     * {@code start/end} 必须与<b>调用方传进来的那个字符串</b>同坐标系。
     * 预览接口传的是用户原始粘贴文本、且不需要图片归属，故由它自己调本方法。
     *
     * @param text 原始文本
     * @return 归一化文本（无 {@code \r} 时原样返回）
     */
    public static String normalizeLineEndings(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 选策略链 · 对齐源侧 {@code resolveChainWithProfile}（{@code strategy.go:283-303}）。
     *
     * <table>
     *   <tr><th>配置 strategy</th><th>链</th></tr>
     *   <tr><td>{@code heading}</td><td>{@code [heading, legacy]}</td></tr>
     *   <tr><td>{@code heuristic}</td><td>{@code [heuristic, legacy]}</td></tr>
     *   <tr><td>{@code recursive}</td><td>{@code [legacy]}（公开 API 的 legacy 别名，为老配置保留）</td></tr>
     *   <tr><td>{@code legacy} / 空</td><td>{@code [legacy]}（空 = legacy，兼容 Strategy 字段出现前的存量配置）</td></tr>
     *   <tr><td>{@code auto} / 其它</td><td>画像驱动（{@link KbDocProfile#selectStrategy}）</td></tr>
     * </table>
     *
     * @param strategy 配置值
     * @param profile  画像
     * @return 有序链（非空）
     */
    static List<KbStrategyTier> resolveChain(String strategy, KbDocProfile profile) {
        String s = strategy == null ? "" : strategy.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "heading" -> List.of(KbStrategyTier.HEADING, KbStrategyTier.LEGACY);
            case "heuristic" -> List.of(KbStrategyTier.HEURISTIC, KbStrategyTier.LEGACY);
            case "recursive" -> List.of(KbStrategyTier.LEGACY);
            case "legacy", "" -> List.of(KbStrategyTier.LEGACY);
            default -> KbDocProfile.selectStrategy(profile);
        };
    }

    /** 分发到具体 tier 实现 · 对齐源侧 {@code runTier}（{@code strategy.go:314-324}）。 */
    private static List<KbChunk> runTier(KbStrategyTier tier, String text, KbSplitterConfig cfg,
                                         KbDocProfile profile) {
        return switch (tier) {
            case HEADING -> KbHeadingSplitter.split(text, cfg, profile);
            case HEURISTIC -> KbHeuristicSplitter.split(text, cfg, profile);
            case LEGACY -> splitLegacyRaw(text, cfg);
        };
    }

    // ════════════════════════════════════════════════════════════════════
    // Tier3 · legacy（原 split 主体，逐行保留）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 只跑 Tier3（legacy）· 对齐源侧 {@code SplitText}（{@code splitter.go:278-304}）。
     *
     * <p>⚠️ 本方法<b>假定</b> {@code cfg} 已规范化（{@link KbSplitterConfig#normalized()}）——
     * 内部不再归一，避免每次递归都重建配置对象；对外入口 {@link #split} 已归一。
     *
     * @param text 原文
     * @param cfg  已规范化配置
     * @return 分块
     */
    static List<KbChunk> splitLegacyRaw(String text, KbSplitterConfig cfg) {
        return legacy(text, cfg, true);
    }

    /**
     * legacy 实现主体。
     *
     * @param withBreadcrumbs 是否构建「标题面包屑索引」。
     *                        ⚠️ 另两级内部调本方法切<b>子串</b>时会传 false ——
     *                        上层（HEADING）自己按子标题算面包屑，上层（HEURISTIC）
     *                        不使用面包屑；为每个超长段各建一次索引是纯浪费
     *                        （超长段多时是 O(n·k)）。
     */
    private static List<KbChunk> legacy(String text, KbSplitterConfig cfg, boolean withBreadcrumbs) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int chunkSize = cfg.chunkSize() <= 0 ? KbSplitterConfig.DEFAULT_CHUNK_SIZE : cfg.chunkSize();
        int chunkOverlap = Math.max(cfg.chunkOverlap(), 0);

        // Step 1: 找受保护片段
        List<KbProtectedSpans.Span> protectedSpans = KbProtectedSpans.find(text);
        logProtectedSpans(text, protectedSpans);

        // Step 2: 切成单元（受保护区整体保留）
        List<KbTextUnits.SplitUnit> units =
            buildUnitsWithProtection(text, protectedSpans, cfg.separators(), chunkSize);
        log.info("[分块] LEGACY 单元数={} chunkSize={} overlap={} 面包屑={}",
            units.size(), chunkSize, chunkOverlap, withBreadcrumbs ? "开" : "关");

        // Step 3: 合并成块（带重叠 + 表头 + 标题面包屑）
        LegacyStats stats = new LegacyStats();
        KbHeadingSplitter.BreadcrumbIndex idx = withBreadcrumbs
            ? KbHeadingSplitter.BreadcrumbIndex.build(text)
            : KbHeadingSplitter.BreadcrumbIndex.empty();
        List<KbChunk> chunks = mergeUnits(units, chunkSize, chunkOverlap, stats, idx);
        log.info("[分块] LEGACY 明细：{}", stats.describe());
        return chunks;
    }

    /**
     * 供另两级内部切子串用：不建面包屑索引。
     *
     * @param text 子串
     * @param cfg  已归一化配置
     * @return 分块（context_header 恒空，由上层覆盖）
     */
    static List<KbChunk> splitLegacyInner(String text, KbSplitterConfig cfg) {
        return legacy(text, cfg, false);
    }

    /**
     * 打受保护片段分类计数。
     *
     * <p>分类依据是<b>片段自身的首个字符/形态</b>（不另写一套正则 ——
     * 切分的唯一真源是 {@link KbProtectedSpans}，此处只做归类统计）。
     */
    private static void logProtectedSpans(String text, List<KbProtectedSpans.Span> spans) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int latex = 0;
        int image = 0;
        int link = 0;
        int table = 0;
        int fencedCode = 0;
        int inlineCode = 0;
        for (KbProtectedSpans.Span s : spans) {
            String t = text.substring(s.start(), s.end());
            if (t.startsWith("$$")) {
                latex++;
            } else if (t.startsWith("![")) {
                image++;
            } else if (t.startsWith("[")) {
                link++;
            } else if (t.startsWith("|") || t.stripLeading().startsWith("|")) {
                table++;
            } else if (t.startsWith("```")) {
                fencedCode++;
            } else if (t.startsWith("`")) {
                inlineCode++;
            }
        }
        log.info("[分块] LEGACY 受保护片段 {} 个：代码块={} 行内代码={} 表格={} 链接={} 图片={} LaTeX={}",
            spans.size(), fencedCode, inlineCode, table, link, image, latex);
    }

    /**
     * 行尾告警（不改文本，见类注释「行尾不变式」）。
     *
     * <p>CRLF 文档会让 {@code (?m)^...$} 这类逐行正则在 {@code \r} 处失配 ⇒
     * 画像数不到标题、启发式边界也找不到。此处必须<b>说出来</b>，
     * 否则用户只会看到「明明有标题却走了 legacy」。
     */
    private static void warnIfCarriageReturn(String text, KbSplitterConfig cfg) {
        int crCount = 0;
        int crlfCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') {
                crCount++;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlfCount++;
                }
            }
        }
        if (crCount > 0) {
            log.warn("[分块] 文本含 {} 个 CR（其中 CRLF {} 个）⇒ 逐行正则可能失配、标题识别可能为 0。"
                    + "上游应先调 KbTextSplitter.normalizeLineEndings（本类不改文本，以免破坏调用方坐标）；"
                    + "strategy=\"{}\"",
                crCount, crlfCount, cfg.strategy());
        }
    }

    // ── Step 2 ──────────────────────────────────────────────────────

    /**
     * 把文本切成单元，受保护片段保持原子 · 对齐源侧
     * {@code buildUnitsWithProtection}（{@code splitter.go:313-389}）。
     *
     * <p>位置用 <b>code point</b> 偏移（源侧用 rune），因为下游合并按 rune 切片。
     *
     * @param text      原文
     * @param spans     受保护片段（char 偏移，已排序不重叠）
     * @param separators 分隔符优先级
     * @param chunkSize 目标块大小（传给递归切分用）
     * @return 单元列表（code point 偏移）
     */
    private static List<KbTextUnits.SplitUnit> buildUnitsWithProtection(
        String text,
        List<KbProtectedSpans.Span> spans,
        List<String> separators,
        int chunkSize
    ) {
        List<KbTextUnits.SplitUnit> units = new ArrayList<>();
        int charPos = 0;
        int cpPos = 0;

        for (KbProtectedSpans.Span p : spans) {
            if (p.start() > charPos) {
                String pre = text.substring(charPos, p.start());
                List<String> parts = splitBySeparators(pre, separators, chunkSize);
                int runeOffset = cpPos;
                for (String part : parts) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    int partLen = KbTextUnits.codePointLength(part);
                    units.add(new KbTextUnits.SplitUnit(part, runeOffset, runeOffset + partLen));
                    runeOffset += partLen;
                }
                cpPos += KbTextUnits.codePointLength(pre);
            }

            String protText = text.substring(p.start(), p.end());
            int protLen = KbTextUnits.codePointLength(protText);

            if (protLen > MAX_PROTECTED_SIZE) {
                // 受保护片段过长 ⇒ 强制切成多段
                units.addAll(forceSplit(protText, cpPos));
            } else {
                units.add(new KbTextUnits.SplitUnit(protText, cpPos, cpPos + protLen));
            }
            cpPos += protLen;
            charPos = p.end();
        }

        if (charPos < text.length()) {
            String remaining = text.substring(charPos);
            List<String> parts = splitBySeparators(remaining, separators, chunkSize);
            int runeOffset = cpPos;
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                int partLen = KbTextUnits.codePointLength(part);
                units.add(new KbTextUnits.SplitUnit(part, runeOffset, runeOffset + partLen));
                runeOffset += partLen;
            }
        }

        return units;
    }

    /**
     * 强制切分超长文本 · 源侧 {@code splitter.go:331-368} 与 {@code :430-455} 同一算法。
     *
     * <p>每 {@value #MAX_PROTECTED_SIZE} 个 code point 切一刀；切点前在
     * {@value #FORCE_SPLIT_LOOKBACK} 个 code point 内向后找最近的换行或空格，
     * 找到就在其后断（避免切断单词/句子）。
     *
     * @param text        待切文本
     * @param baseOffset  该文本在原文中的起始 code point 偏移
     * @return 切出的单元
     */
    private static List<KbTextUnits.SplitUnit> forceSplit(String text, int baseOffset) {
        List<KbTextUnits.SplitUnit> out = new ArrayList<>();
        int totalCp = KbTextUnits.codePointLength(text);
        int offset = 0; // code point 偏移
        while (offset < totalCp) {
            int chunkEnd = Math.min(offset + MAX_PROTECTED_SIZE, totalCp);
            if (chunkEnd < totalCp) {
                // 在 [chunkEnd-256, chunkEnd) 之间回找换行/空格（源侧 i > chunkEnd-200）
                int searchFrom = Math.max(offset, chunkEnd - FORCE_SPLIT_LOOKBACK);
                for (int i = chunkEnd - 1; i > searchFrom; i--) {
                    char c = charAtCp(text, i);
                    if (c == '\n' || c == ' ') {
                        chunkEnd = i + 1;
                        break;
                    }
                }
            }
            int fromChar = text.offsetByCodePoints(0, offset);
            int toChar = text.offsetByCodePoints(0, chunkEnd);
            out.add(new KbTextUnits.SplitUnit(
                text.substring(fromChar, toChar),
                baseOffset + offset,
                baseOffset + chunkEnd));
            offset = chunkEnd;
        }
        return out;
    }

    /** 取第 {@code cpIndex} 个 code point 对应的起始 char 的字符（仅用于比较是否为换行/空格）。 */
    private static char charAtCp(String text, int cpIndex) {
        return text.charAt(text.offsetByCodePoints(0, cpIndex));
    }

    /**
     * 按分隔符优先级递归切分 · 对齐源侧 {@code splitBySeparators}
     * （{@code splitter.go:224-270}）。
     *
     * <p>语义（源侧注释）：
     * <ul>
     *   <li>依次尝试每个分隔符；某分隔符切不出多片就试下一个</li>
     *   <li>切分时<b>保留分隔符本身</b>（它作为独立片追加，因为中文「。」等本身有语义）</li>
     *   <li>仍超长的片用<b>剩余</b>分隔符继续递归切（不是从头）</li>
     * </ul>
     *
     * @param text       待切文本
     * @param separators 分隔符优先级
     * @param chunkSize  目标块大小（&lt;=0 表示不做尺寸约束）
     * @return 切出的片
     */
    static List<String> splitBySeparators(String text, List<String> separators, int chunkSize) {
        if (text == null || text.isEmpty() || separators == null || separators.isEmpty()) {
            return List.of(text == null ? "" : text);
        }
        if (chunkSize > 0 && KbTextUnits.codePointLength(text) <= chunkSize) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String sep = separators.get(i);
            if (sep == null || sep.isEmpty()) {
                continue;
            }
            Pattern pattern = Pattern.compile("(" + Pattern.quote(sep) + ")");
            String[] splits = pattern.split(text, -1);
            List<String> matches = new ArrayList<>();
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                matches.add(m.group());
            }
            if (matches.isEmpty()) {
                continue;
            }

            // 交替拼：split 片段 + 匹配到的分隔符
            List<String> pieces = new ArrayList<>();
            for (int j = 0; j < splits.length; j++) {
                if (!splits[j].isEmpty()) {
                    pieces.add(splits[j]);
                }
                if (j < matches.size() && !matches.get(j).isEmpty()) {
                    pieces.add(matches.get(j));
                }
            }
            if (pieces.size() <= 1) {
                continue;
            }

            // 仍超长的片用剩余分隔符递归
            List<String> out = new ArrayList<>();
            List<String> remaining = separators.subList(i + 1, separators.size());
            for (String piece : pieces) {
                if (chunkSize > 0 && KbTextUnits.codePointLength(piece) > chunkSize
                    && !remaining.isEmpty()) {
                    out.addAll(splitBySeparators(piece, remaining, chunkSize));
                } else {
                    out.add(piece);
                }
            }
            return out;
        }
        return List.of(text);
    }

    // ── Step 3 ──────────────────────────────────────────────────────

    /**
     * 合并单元成块（不带统计/面包屑）· 保留旧签名供既有调用方。
     *
     * @param units        单元列表
     * @param chunkSize    目标块大小
     * @param chunkOverlap 重叠大小
     * @return 分块列表
     */
    static List<KbChunk> mergeUnits(List<KbTextUnits.SplitUnit> units, int chunkSize, int chunkOverlap) {
        return mergeUnits(units, chunkSize, chunkOverlap, null, null);
    }

    /**
     * 合并单元成块（带重叠、表头前置、绝对上限、标题面包屑）· 对齐源侧
     * {@code mergeUnits}（{@code splitter.go:402-529}）。
     *
     * @param units        单元列表
     * @param chunkSize    目标块大小
     * @param chunkOverlap 重叠大小
     * @param stats        统计收集器（可 null）
     * @param breadcrumbs  标题面包屑索引（可 null ⇒ context_header 恒空）
     * @return 分块列表
     */
    static List<KbChunk> mergeUnits(List<KbTextUnits.SplitUnit> units, int chunkSize, int chunkOverlap,
                                    LegacyStats stats, KbHeadingSplitter.BreadcrumbIndex breadcrumbs) {
        if (units.isEmpty()) {
            return List.of();
        }
        KbHeadingSplitter.BreadcrumbIndex idx =
            breadcrumbs == null ? KbHeadingSplitter.BreadcrumbIndex.empty() : breadcrumbs;

        KbHeaderTracker tracker = new KbHeaderTracker();
        List<KbChunk> chunks = new ArrayList<>();
        List<KbTextUnits.SplitUnit> current = new ArrayList<>();
        int curLen = 0;

        for (KbTextUnits.SplitUnit u : units) {
            int uLen = u.length();

            // ── 单个单元超过绝对上限 ⇒ 强制切（先落盘当前块）──────────
            if (uLen > ABSOLUTE_MAX_SIZE) {
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(current, chunks.size(), idx, stats));
                    current = new ArrayList<>();
                    curLen = 0;
                }
                tracker.update(u.text());

                int offset = 0;
                int totalCp = uLen;
                while (offset < totalCp) {
                    int chunkEnd = Math.min(offset + ABSOLUTE_MAX_SIZE, totalCp);
                    if (chunkEnd < totalCp) {
                        int searchFrom = Math.max(offset, chunkEnd - FORCE_SPLIT_LOOKBACK);
                        for (int i = chunkEnd - 1; i > searchFrom; i--) {
                            char c = charAtCp(u.text(), i);
                            if (c == '\n' || c == ' ') {
                                chunkEnd = i + 1;
                                break;
                            }
                        }
                    }
                    int fromChar = u.text().offsetByCodePoints(0, offset);
                    int toChar = u.text().offsetByCodePoints(0, chunkEnd);
                    chunks.add(new KbChunk(
                        u.text().substring(fromChar, toChar),
                        idx.at(u.start() + offset),
                        chunks.size(),
                        u.start() + offset, u.start() + chunkEnd));
                    if (stats != null) {
                        stats.forcedSplitChunks++;
                    }
                    offset = chunkEnd;
                }
                continue;
            }

            tracker.update(u.text());

            // 新表开始 ⇒ 先落盘（否则下一张表会继承上一张的表头上下文）
            if (tracker.headerEndedThisUnit() && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size(), idx, stats));
                current = new ArrayList<>();
                curLen = 0;
                if (stats != null) {
                    stats.tableBoundaryFlush++;
                }
            }

            String headers = tracker.getHeaders();
            int headersLen = KbTextUnits.codePointLength(headers);
            if (headersLen > chunkSize) {
                headers = "";
                headersLen = 0;
                if (stats != null) {
                    stats.headerDroppedTooLong++;
                }
            }

            // 加入本单元（含为下一块预留表头空间）会超限 ⇒ 落盘 + 保留重叠
            if (curLen + uLen + headersLen > chunkSize && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size(), idx, stats));

                // 保留尾部重叠（可能再因表头空间收缩）
                Overlap over = computeOverlap(current, chunkOverlap, chunkSize, uLen, stats);
                List<KbTextUnits.SplitUnit> ovUnits = over.units();
                int ovLen = over.length();
                current = new ArrayList<>(ovUnits);
                curLen = ovLen;
                if (stats != null) {
                    stats.chunkFlushes++;
                    if (chunkOverlap > 0) {
                        if (ovLen > 0) {
                            stats.overlapKept++;
                            stats.overlapCharsTotal += ovLen;
                        } else {
                            stats.overlapNone++;
                        }
                    }
                }

                if (!headers.isEmpty() && headersLen + uLen <= chunkSize) {
                    // 收缩重叠直到能容纳表头
                    while (!current.isEmpty() && curLen + uLen + headersLen > chunkSize) {
                        curLen -= current.get(0).length();
                        current.remove(0);
                    }
                    String overlapText = KbTextUnits.joinText(current);
                    if (!headerAlreadyPresent(headers, overlapText, u.text())
                        && !KbHeaderTracker.headerColumnMismatch(headers, u.text())) {
                        int startPos = current.isEmpty() ? u.start() : current.get(0).start();
                        // 合成零宽单元：start == end，不占原文位置
                        current.add(0, new KbTextUnits.SplitUnit(headers, startPos, startPos));
                        curLen += headersLen;
                        if (stats != null) {
                            stats.headerPrepended++;
                        }
                    }
                }
            }

            // 绝对上限保护
            if (curLen + uLen > ABSOLUTE_MAX_SIZE && !current.isEmpty()) {
                chunks.add(buildChunk(current, chunks.size(), idx, stats));
                current = new ArrayList<>();
                curLen = 0;
                if (stats != null) {
                    stats.absoluteMaxFlush++;
                }
            }

            current.add(u);
            curLen += uLen;
        }

        if (!current.isEmpty()) {
            chunks.add(buildChunk(current, chunks.size(), idx, stats));
        }
        return chunks;
    }

    /**
     * 单元列表 → 块 · 对齐源侧 {@code buildChunk}（{@code splitter.go:577-587}）。
     *
     * <p><b>⭐ context_header 在此接线（本批 P0）</b>：源侧 legacy 路径同样产出
     * {@code ContextHeader}（{@code splitter.go:45-51} 的 {@code EmbeddingContent}），
     * 做法是查「块起点所在的标题路径」。本仓移植前恒传 {@code ""}，
     * 导致 {@code kb_chunk.context_header} 全空、embedding 丢失章节上下文。
     *
     * <p>⚠️ 面包屑只挂 {@code ContextHeader}，⛔ <b>不</b>拼进 {@code content}：
     * 合进去会破坏 {@code end - start == code point 数(content)} 位置不变式。
     *
     * @param units 本块的单元（首个单元的 start 决定面包屑归属）
     * @param seq   序号
     * @param idx   面包屑索引
     * @param stats 统计（可 null）
     * @return 分块
     */
    private static KbChunk buildChunk(List<KbTextUnits.SplitUnit> units, int seq,
                                      KbHeadingSplitter.BreadcrumbIndex idx, LegacyStats stats) {
        StringBuilder sb = new StringBuilder();
        for (KbTextUnits.SplitUnit u : units) {
            sb.append(u.text());
        }
        int start = units.get(0).start();
        String header = idx.at(start);
        if (stats != null) {
            if (header.isEmpty()) {
                stats.chunksWithoutHeader++;
            } else {
                stats.chunksWithHeader++;
            }
        }
        return new KbChunk(
            sb.toString(),
            header,
            seq,
            start,
            units.get(units.size() - 1).end());
    }

    /**
     * 计算重叠后缀 · 对齐源侧 {@code computeOverlap}（{@code splitter.go:613-660}）。
     *
     * <p><b>关键语义</b>（源侧长注释）：配置的重叠是<b>硬上限</b>，不是原始字符切片。
     * 边界检测可多看 4 个 rune 的 lookbehind，但<b>保留内容绝不超上限</b>。
     * 边界优先级：段落断 &gt; 行断 &gt; 句末；同优先级取<b>最早</b>（保留更多有用上下文）。
     * 窗口内无合法边界 ⇒ 不保留重叠（确定性且不越界）。
     *
     * @return {@code [List<SplitUnit> 重叠单元, Integer 重叠长度]}
     */
    private static Overlap computeOverlap(
        List<KbTextUnits.SplitUnit> current, int chunkOverlap, int chunkSize, int nextLen,
        LegacyStats stats) {
        if (chunkOverlap <= 0) {
            return Overlap.EMPTY;
        }
        int maxOverlap = chunkOverlap;
        int remainingBudget = chunkSize - nextLen;
        if (remainingBudget < maxOverlap) {
            maxOverlap = remainingBudget;
        }
        if (maxOverlap <= 0) {
            return Overlap.EMPTY;
        }

        // 语义边界最长 2 个 rune（\r\n\r\n 是 4）—— 多看 4 个
        int lookbehind = 4;
        List<KbTextUnits.SplitUnit> window =
            KbTextUnits.semanticOverlapWindow(current, maxOverlap + lookbehind);
        if (window.isEmpty()) {
            return Overlap.EMPTY;
        }

        String windowText = KbTextUnits.joinText(window);
        int originalWindowStart = KbTextUnits.codePointLength(windowText) - maxOverlap;
        if (originalWindowStart < 0) {
            originalWindowStart = 0;
        }
        int boundaryEnd = KbSemanticBoundary.findBoundaryEndingAtOrAfter(
            windowText, originalWindowStart);
        if (boundaryEnd < 0) {
            if (stats != null) {
                stats.overlapNoSemanticBoundary++;
            }
            return Overlap.EMPTY;
        }

        List<KbTextUnits.SplitUnit> overlap = KbTextUnits.trimUnitsPrefix(window, boundaryEnd);
        int overlapLen = 0;
        for (KbTextUnits.SplitUnit u : overlap) {
            overlapLen += u.length();
        }
        if (overlapLen <= 0 || overlapLen > maxOverlap
            || KbTextUnits.joinText(overlap).strip().isEmpty()) {
            if (stats != null) {
                stats.overlapRejected++;
            }
            return Overlap.EMPTY;
        }
        return new Overlap(overlap, overlapLen);
    }

    /**
     * 表头是否已存在于重叠或下一单元中 · 对齐源侧 {@code headerAlreadyPresent}（{@code :540-558}）。
     *
     * <p>先找完整表头；找不到则退一步只比「列名行」——
     * 因为重叠部分可能只含列名行而不含分隔行。
     */
    private static boolean headerAlreadyPresent(String headers, String overlapText, String unitText) {
        if (overlapText.contains(headers) || unitText.contains(headers)) {
            return true;
        }
        String colRow = headerColumnRow(headers);
        if (colRow.isEmpty()) {
            return false;
        }
        return overlapText.contains(colRow) || unitText.contains(colRow);
    }

    /** 取表头里的列名行（首个非空、非分隔行的行）· 对齐源侧 {@code headerColumnRow}（{@code :560-575}）。 */
    private static String headerColumnRow(String header) {
        for (String line : header.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.contains("---")) {
                continue;
            }
            boolean onlyPipes = true;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c != '|' && c != ' ' && c != '\t') {
                    onlyPipes = false;
                    break;
                }
            }
            if (!onlyPipes) {
                return t;
            }
        }
        return "";
    }

    /**
     * 重叠计算结果（替代原来的 {@code Object[]} 二元组）。
     *
     * <p><b>WHY 用 record 而不是 {@code Object[]}</b>：{@code Object[]} 的
     * {@code [0]=List, [1]=Integer} 约定只存在于注释里，调用方必须
     * {@code @SuppressWarnings("unchecked")} + 强转；类型错位只会在运行期炸。
     * record 让契约进编译器。
     *
     * @param units  重叠单元（空列表 = 不保留重叠）
     * @param length 重叠的 rune 长度（0 = 不保留重叠）
     */
    private record Overlap(List<KbTextUnits.SplitUnit> units, int length) {

        /** 不保留重叠。 */
        static final Overlap EMPTY = new Overlap(List.of(), 0);
    }

    // ════════════════════════════════════════════════════════════════════
    // 诊断 / 统计（预览接口的返回值基础）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 切分结果 + 诊断。
     *
     * @param chunks 分块
     * @param diag   诊断
     */
    public record SplitDiagnostics(List<KbChunk> chunks, Diagnostics diag) {
    }

    /**
     * 父子分块产物 · 对齐源侧 {@code ParentChildResult}（{@code splitter.go:846-859}）。
     *
     * <p><b>为什么父块与子块要分成两个列表</b>：源站也是这个形态
     * （{@code knowledge_create.go:1456-1472} 分别灌进 {@code parsed} 与 {@code opts.ParentChunks}），
     * 因为两者的落库字段完全不同 —— 父块是 {@code chunk_type='parent_text'} 且不进向量索引，
     * 子块是 {@code chunk_type='text'} 且带 {@code parent_chunk_id}。合成一个列表就得靠字段猜身份。
     *
     * @param parents  保留的父块（全文坐标；{@code seq} 沿用父切分自己的序号）
     * @param children 子块（全文坐标；{@code seq} 是 0..N-1 的<b>子块</b>全局序号）
     */
    public record ParentChildResult(List<KbChunk> parents, List<KbChunk> children) {

        public ParentChildResult {
            parents = parents == null ? List.of() : List.copyOf(parents);
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** 空结果。 */
        public static final ParentChildResult EMPTY = new ParentChildResult(List.of(), List.of());

        /** 是否真的有可落库的父子结构（父块为空 ⇒ 等价普通切分）。 */
        public boolean hasParents() {
            return !parents.isEmpty();
        }
    }

    /**
     * 诊断 · 对齐源侧 {@code Diagnostics}（{@code strategy.go:75-82}）。
     *
     * @param selectedTier 实际产出结果的层级
     * @param tierChain    尝试过的完整链
     * @param rejected     被拒层级及原因（按尝试顺序）
     * @param profile      文档画像（永不为 null —— 本实现总是画像）
     */
    public record Diagnostics(
        KbStrategyTier selectedTier,
        List<KbStrategyTier> tierChain,
        List<TierRejection> rejected,
        KbDocProfile profile
    ) {
        public Diagnostics {
            tierChain = tierChain == null ? List.of() : List.copyOf(tierChain);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }
    }

    /**
     * 某一层级被拒原因 · 对齐源侧 {@code TierRejection}（{@code strategy.go:63-66}）。
     *
     * @param tier   被拒层级
     * @param reason 五条判据里的具体那一条（源侧英文原文，便于与源站日志对齐）
     */
    public record TierRejection(KbStrategyTier tier, String reason) {
    }

    /**
     * 分块尺寸统计。
     *
     * @param count 块数
     * @param avg   平均 rune 数（四舍五入）
     * @param max   最大 rune 数
     * @param min   最小 rune 数
     */
    private record ChunkStats(int count, int avg, int max, int min) {

        static ChunkStats of(List<KbChunk> chunks) {
            if (chunks == null || chunks.isEmpty()) {
                return new ChunkStats(0, 0, 0, 0);
            }
            long sum = 0;
            int max = 0;
            int min = Integer.MAX_VALUE;
            for (KbChunk c : chunks) {
                int len = KbTextUnits.codePointLength(c.content());
                sum += len;
                if (len > max) {
                    max = len;
                }
                if (len < min) {
                    min = len;
                }
            }
            return new ChunkStats(chunks.size(), (int) (sum / chunks.size()), max, min);
        }
    }

    /**
     * legacy 路径的可观测计数（只服务日志，不参与任何决策）。
     *
     * <p><b>为什么单独一个类而不是打散成局部变量</b>：这些计数发生在
     * {@link #mergeUnits} 的多个分支里，而 {@code mergeUnits} 的签名需要保持可复用；
     * 用一个可 null 的收集器比让方法返回复合对象更小侵入。
     */
    static final class LegacyStats {
        int chunkFlushes;
        int tableBoundaryFlush;
        int absoluteMaxFlush;
        int forcedSplitChunks;
        int headerPrepended;
        int headerDroppedTooLong;
        int overlapKept;
        int overlapNone;
        int overlapNoSemanticBoundary;
        int overlapRejected;
        long overlapCharsTotal;
        int chunksWithHeader;
        int chunksWithoutHeader;

        String describe() {
            String overlap = overlapKept + overlapNone == 0
                ? "无重叠场景"
                : String.format("重叠保留=%d 无重叠=%d(无语义边界=%d 被判空/超限=%d) 重叠均长=%.1f",
                    overlapKept, overlapNone, overlapNoSemanticBoundary, overlapRejected,
                    overlapKept == 0 ? 0.0 : (double) overlapCharsTotal / overlapKept);
            return String.format(
                "落盘=%d(表边界=%d 绝对上限=%d) 强制切块=%d 表头跨块补=%d 表头超长丢弃=%d %s",
                chunkFlushes, tableBoundaryFlush, absoluteMaxFlush,
                forcedSplitChunks, headerPrepended, headerDroppedTooLong, overlap);
        }
    }

    /**
     * 打 context_header 覆盖统计（用户要求：产出面包屑的块数 / 空的比例）。
     */
    private static void logContextHeaderStats(KbStrategyTier tier, List<KbChunk> chunks) {
        if (!log.isInfoEnabled() || chunks == null) {
            return;
        }
        int withHeader = 0;
        for (KbChunk c : chunks) {
            if (c.contextHeader() != null && !c.contextHeader().isEmpty()) {
                withHeader++;
            }
        }
        int total = chunks.size();
        String ratio = total == 0 ? "0%" : String.format("%.0f%%", 100.0 * withHeader / total);
        log.info("[分块] context_header tier={} 非空={}/{}（{}）",
            tier, withHeader, total, ratio);
    }
}

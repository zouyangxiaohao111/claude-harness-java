package com.nexusai.kb.app.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Tier 1 · Markdown 标题感知分块 · 对齐 WeKnora {@code chunker/heading_splitter.go:1-319}。
 *
 * <p><b>做什么</b>（源侧 {@code :1-4}）：把文档按 Markdown 标题边界切开，
 * 并给每个块挂上「当前标题路径」面包屑（{@code "# 第一章\n## 1.2 节"}）。
 *
 * <p><b>三种情形</b>：
 * <ol>
 *   <li>标题结构不可用（主导层级 0 / 边界 ≤1）⇒ 交回 legacy（{@code :39-47}）</li>
 *   <li>章节整体能塞进一块 ⇒ 直接产出一块，面包屑走 {@code ContextHeader}
 *       （⛔ <b>不并入 Content</b>，否则会破坏 {@code End-Start == rune 数(Content)} 的
 *       位置不变式 —— 源侧 {@code :84-87} 明文）</li>
 *   <li>章节太大 ⇒ 内部再走 legacy 切，再按子标题位置给每个子块算更细的面包屑
 *       （{@code :100-117}）</li>
 * </ol>
 *
 * <p><b>⭐ 合并小块的动机</b>（源侧 {@code :123-138}）：FAQ / 变更日志这类文档每个章节都很短，
 * 不合并会触发 {@link KbChunkValidator} 的「碎片过多」判据，整条链一路降级到 legacy ——
 * 而 legacy 恰恰不会保留标题结构。合并只在 {@code cur.end == next.start} 时进行，
 * 保住位置不变式（重叠产生的 legacy 子块因此天然被跳过）。
 */
final class KbHeadingSplitter {

    private static final Logger log = LoggerFactory.getLogger(KbHeadingSplitter.class);

    private KbHeadingSplitter() {
    }

    /**
     * 切分入口 · 对齐源侧 {@code splitByHeadingsImpl}（{@code :32-121}）。
     *
     * @param text    原文（已归一化行尾）
     * @param cfg     配置（已 {@code normalized()}）
     * @param profile 画像（可为 null ⇒ 现场算）
     * @return 分块；无法按标题切时返回 {@link KbTextSplitter#splitLegacyRaw} 的结果
     */
    static List<KbChunk> split(String text, KbSplitterConfig cfg, KbDocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        KbDocProfile p = profile != null ? profile : KbDocProfile.profile(text);
        int primaryLevel = p.dominantHeadingLevel();
        if (primaryLevel == 0) {
            log.info("[分块] HEADING 不适用：主导标题层级=0（无 Markdown 标题）⇒ 交回 legacy");
            return KbTextSplitter.splitLegacyRaw(text, cfg);
        }

        List<HeadingBoundary> bounds = findHeadingBoundaries(text, primaryLevel);
        log.info("[分块] HEADING 主导层级={} 边界数={}（含偏移 0 的起始边界）", primaryLevel, bounds.size());
        if (bounds.size() <= 1) {
            log.info("[分块] HEADING 不适用：只有起始边界，无 ≤{} 级标题 ⇒ 交回 legacy", primaryLevel);
            return KbTextSplitter.splitLegacyRaw(text, cfg);
        }

        int[] runes = text.codePoints().toArray();
        int totalRunes = runes.length;
        KbHeadingHierarchy hierarchy = new KbHeadingHierarchy();

        List<KbChunk> out = new ArrayList<>(bounds.size());
        int seq = 0;
        int singleChunkSections = 0;
        int oversizedSections = 0;
        int subChunksTotal = 0;
        int emptySections = 0;

        for (int i = 0; i < bounds.size(); i++) {
            HeadingBoundary b = bounds.get(i);
            int endRune = (i + 1 < bounds.size()) ? bounds.get(i + 1).runeStart() : totalRunes;
            if (b.line() != null && !b.line().isEmpty()) {
                hierarchy.observe(b.line());
            }
            String breadcrumb = hierarchy.breadcrumbWithHashes();
            // 冻结章节起点时的栈 —— 推子块面包屑时不能污染主栈（源侧 :73 `sectionStart := *hierarchy`）
            KbHeadingHierarchy sectionStart = hierarchy.copy();
            // 先把章节内更深的子标题喂进主栈，保证下一个章节的面包屑是「真实激活栈」
            observeSubHeadings(runes, b.runeStart(), endRune, primaryLevel, hierarchy);

            int secLen = endRune - b.runeStart();
            if (secLen <= 0) {
                emptySections++;
                continue;
            }
            String sectionContent = new String(runes, b.runeStart(), secLen);
            int bcLen = KbTextUnits.codePointLength(breadcrumb);

            // 情形 2：整章塞得下 ⇒ 单块，面包屑走 ContextHeader（+2 是 embedding 时的 "\n\n"）
            if (bcLen + 2 + secLen <= cfg.chunkSize()) {
                out.add(new KbChunk(sectionContent, breadcrumb, seq++, b.runeStart(), endRune));
                singleChunkSections++;
                continue;
            }

            // 情形 3：章太大 ⇒ 内部走 legacy，再按子标题偏移补更细的面包屑
            oversizedSections++;
            List<SectionBreadcrumb> subBreadcrumbs =
                sectionBreadcrumbs(runes, b.runeStart(), endRune, primaryLevel, sectionStart);
            List<KbChunk> subs = KbTextSplitter.splitLegacyInner(sectionContent, cfg);
            for (KbChunk sub : subs) {
                out.add(new KbChunk(
                    sub.content(),
                    breadcrumbAtOffset(subBreadcrumbs, sub.start(), breadcrumb),
                    seq++,
                    b.runeStart() + sub.start(),
                    b.runeStart() + sub.end()));
                subChunksTotal++;
            }
        }

        List<KbChunk> coalesced = coalesceTinyChunks(out, cfg.chunkSize());
        log.info("[分块] HEADING 明细：章节={} 单块章节={} 超长子章节={} 子块={} 空章节={} 合并前={} 合并后={}",
            bounds.size(), singleChunkSections, oversizedSections, subChunksTotal,
            emptySections, out.size(), coalesced.size());
        return coalesced;
    }

    // ════════════════════════════════════════════════════════════════════
    // 标题边界 · 对齐 heading_splitter.go:20-23 / :209-242
    // ════════════════════════════════════════════════════════════════════

    /**
     * 标题边界。
     *
     * <p>第一个边界固定在 rune 偏移 0（覆盖第一个标题之前的前言）；
     * 其后每个 {@code level <= primaryLevel} 且<b>不在围栏代码块内</b>的标题一个边界。
     *
     * @param runeStart 该边界在原文中的 rune 偏移
     * @param line      原始标题行（起始边界且首行不是标题时为 null）
     */
    record HeadingBoundary(int runeStart, String line) {
    }

    /**
     * 找标题边界 · 对齐源侧 {@code findHeadingBoundaries}（{@code :209-242}）。
     *
     * <p>⚠️ 标题必须<b>独占一行</b>才被识别（行首 {@code #{1,6} + 空格}）。
     * 围栏代码块内的 {@code # xxx} 是注释，不是标题。
     *
     * @param text         原文
     * @param primaryLevel 主导层级（只切 &lt;= 它的标题）
     * @return 边界（首个恒为偏移 0）
     */
    static List<HeadingBoundary> findHeadingBoundaries(String text, int primaryLevel) {
        List<HeadingBoundary> bounds = new ArrayList<>();
        bounds.add(new HeadingBoundary(0, null));
        if (text == null || text.isEmpty()) {
            return bounds;
        }

        String[] lines = text.split("\n", -1);
        int pos = 0; // rune 偏移
        KbDocProfile.KbMarkdownFence fence = new KbDocProfile.KbMarkdownFence();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!fence.consume(line).code()) {
                Matcher m = KbDocProfile.MARKDOWN_HEADING.matcher(line);
                if (m.find()) {
                    int level = m.group(1).length();
                    if (level >= 1 && level <= primaryLevel) {
                        if (pos > 0) {
                            bounds.add(new HeadingBoundary(pos, line));
                        } else {
                            // 首行就是标题 ⇒ 用第一个边界承载它（不再新增偏移 0 的重复边界）
                            bounds.set(0, new HeadingBoundary(0, line));
                        }
                    }
                }
            }
            pos += KbTextUnits.codePointLength(line);
            if (i < lines.length - 1) {
                pos++; // split 吃掉的 \n
            }
        }
        return bounds;
    }

    /**
     * 把章节内更深的子标题喂进主栈 · 对齐源侧 {@code observeSubHeadings}（{@code :248-267}）。
     *
     * <p>只喂 {@code level > primaryLevel} 的 —— 与主边界同级或更浅的标题已经是下一个章节了。
     */
    private static void observeSubHeadings(int[] runes, int from, int to, int primaryLevel,
                                           KbHeadingHierarchy h) {
        if (from >= to) {
            return;
        }
        String text = new String(runes, from, to - from);
        KbDocProfile.KbMarkdownFence fence = new KbDocProfile.KbMarkdownFence();
        for (String line : text.split("\n", -1)) {
            if (fence.consume(line).code()) {
                continue;
            }
            Matcher m = KbDocProfile.MARKDOWN_HEADING.matcher(line);
            if (!m.find()) {
                continue;
            }
            if (m.group(1).length() > primaryLevel) {
                h.observe(line);
            }
        }
    }

    /**
     * 章节内子标题 → 面包屑映射 · 对齐源侧 {@code sectionBreadcrumbs}（{@code :271-305}）。
     *
     * @param runes        全文 rune 数组
     * @param from         章节起点（全文 rune 偏移）
     * @param to           章节终点（全文 rune 偏移，不含）
     * @param primaryLevel 主导层级
     * @param seed         章节起点时的栈快照
     * @return 按 runeStart 升序、首项恒为 {@code {0, seed 面包屑}}
     */
    private static List<SectionBreadcrumb> sectionBreadcrumbs(
        int[] runes, int from, int to, int primaryLevel, KbHeadingHierarchy seed) {
        KbHeadingHierarchy h = seed.copy();
        List<SectionBreadcrumb> result = new ArrayList<>();
        result.add(new SectionBreadcrumb(0, h.breadcrumbWithHashes()));

        String text = new String(runes, from, to - from);
        String[] lines = text.split("\n", -1);
        int pos = 0;
        KbDocProfile.KbMarkdownFence fence = new KbDocProfile.KbMarkdownFence();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!fence.consume(line).code()) {
                Matcher m = KbDocProfile.MARKDOWN_HEADING.matcher(line);
                if (m.find() && m.group(1).length() > primaryLevel) {
                    h.observe(line);
                    result.add(new SectionBreadcrumb(pos, h.breadcrumbWithHashes()));
                }
            }
            pos += KbTextUnits.codePointLength(line);
            if (i < lines.length - 1) {
                pos++;
            }
        }
        return result;
    }

    /**
     * 该偏移处生效的面包屑 · 对齐源侧 {@code breadcrumbAtOffset}（{@code :307-319}）。
     *
     * <p>取最后一个 {@code runeStart <= offset} 的条目。
     *
     * @param bcs      子标题面包屑表
     * @param offset   章节内 rune 偏移
     * @param fallback 兜底（表为空时用）
     * @return 面包屑
     */
    private static String breadcrumbAtOffset(List<SectionBreadcrumb> bcs, int offset, String fallback) {
        String bc = fallback;
        for (SectionBreadcrumb e : bcs) {
            if (e.runeStart() > offset) {
                break;
            }
            bc = e.breadcrumb();
        }
        return bc;
    }

    /**
     * 章节内子标题面包屑条目。
     *
     * @param runeStart  章节内 rune 偏移
     * @param breadcrumb 从该偏移起生效的面包屑
     */
    private record SectionBreadcrumb(int runeStart, String breadcrumb) {
    }

    // ════════════════════════════════════════════════════════════════════
    // 小块合并 · 对齐 heading_splitter.go:139-203
    // ════════════════════════════════════════════════════════════════════

    /**
     * 合并相邻小块 · 对齐源侧 {@code coalesceTinyChunks}（{@code :139-176}）。
     *
     * <p><b>四条合并条件（缺一不可）</b>：
     * <ol>
     *   <li>两块面包屑有<b>行对齐公共前缀</b>（否则合并后上下文就错了）</li>
     *   <li>{@code cur.end == next.start}（保住位置不变式；重叠的 legacy 子块因此被跳过）</li>
     *   <li>当前块仍小于目标（{@code chunkSize/2}，下限 200）</li>
     *   <li>合并后不超过 {@code chunkSize}</li>
     * </ol>
     *
     * <p>合并后 {@code Seq} 必须重排成密集 0..N-1（源侧 {@code :170-175} 明文：下游依赖）。
     *
     * @param in        输入块
     * @param chunkSize 目标块大小
     * @return 合并并重排过 seq 的块
     */
    static List<KbChunk> coalesceTinyChunks(List<KbChunk> in, int chunkSize) {
        if (in == null || in.size() <= 1 || chunkSize <= 0) {
            return in == null ? List.of() : in;
        }
        int target = chunkSize / 2;
        if (target < 200) {
            target = 200;
        }

        List<KbChunk> out = new ArrayList<>(in.size());
        KbChunk cur = in.get(0);
        int curLen = KbTextUnits.codePointLength(cur.content());
        int merged = 0;

        for (int i = 1; i < in.size(); i++) {
            KbChunk next = in.get(i);
            int nextLen = KbTextUnits.codePointLength(next.content());
            String sharedHeader = commonHeadingPrefix(cur.contextHeader(), next.contextHeader());
            if (!sharedHeader.isEmpty() && cur.end() == next.start()
                && curLen < target && curLen + nextLen <= chunkSize) {
                cur = new KbChunk(
                    cur.content() + next.content(),
                    sharedHeader,
                    cur.seq(),
                    cur.start(),
                    next.end());
                curLen += nextLen;
                merged++;
                continue;
            }
            out.add(cur);
            cur = next;
            curLen = nextLen;
        }
        out.add(cur);

        // 重排 seq 为密集 0..N-1（下游按 seq 建链 / 落 chunk_index）
        List<KbChunk> resequenced = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            KbChunk c = out.get(i);
            resequenced.add(new KbChunk(c.content(), c.contextHeader(), i, c.start(), c.end(), c.images()));
        }
        if (merged > 0) {
            log.info("[分块] HEADING 合并小块 {} 次（目标 {}）", merged, target);
        }
        return resequenced;
    }

    /**
     * 最长行对齐公共前缀 · 对齐源侧 {@code commonHeadingPrefix}（{@code :178-203}）。
     *
     * <p>按行比较而非按字符 —— 半行截断会产出 {@code "# 第一"} 这种坏面包屑。
     *
     * @param a 面包屑 A
     * @param b 面包屑 B
     * @return 公共前缀（无公共行则空串）
     */
    static String commonHeadingPrefix(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;
        if (left.equals(right)) {
            return left;
        }
        String[] la = left.split("\n", -1);
        String[] lb = right.split("\n", -1);
        int n = Math.min(la.length, lb.length);
        int common = 0;
        for (int i = 0; i < n; i++) {
            if (!la[i].equals(lb[i])) {
                break;
            }
            common = i + 1;
        }
        if (common == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < common; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(la[i]);
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════
    // 行索引面包屑（legacy / heuristic 补 context_header 用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 按「行起始 rune 偏移」索引的面包屑表。
     *
     * <p><b>为什么需要它</b>：legacy 路径的块是「原文连续切片」，没有章节概念；
     * 但每个块仍然落在某个标题之下。本索引对全文做<b>一趟</b>行扫描，
     * 记录每行开头生效的面包屑，之后任一 rune 偏移都能 O(log n) 查到。
     *
     * <p>⚠️ 代码块内的 {@code #} 不计入（与画像、标题分块器同一套 fence 语义）。
     */
    static final class BreadcrumbIndex {

        private final int[] starts;
        private final String[] crumbs;

        private BreadcrumbIndex(int[] starts, String[] crumbs) {
            this.starts = starts;
            this.crumbs = crumbs;
        }

        /** 空索引（文本为空时）。 */
        static BreadcrumbIndex empty() {
            return new BreadcrumbIndex(new int[0], new String[0]);
        }

        /**
         * 构建索引。
         *
         * @param text 原文
         * @return 索引（永不为 null）
         */
        static BreadcrumbIndex build(String text) {
            if (text == null || text.isEmpty()) {
                return empty();
            }
            String[] lines = text.split("\n", -1);
            int[] starts = new int[lines.length];
            String[] crumbs = new String[lines.length];
            KbHeadingHierarchy h = new KbHeadingHierarchy();
            KbDocProfile.KbMarkdownFence fence = new KbDocProfile.KbMarkdownFence();
            int pos = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!fence.consume(line).code()) {
                    h.observe(line);
                }
                starts[i] = pos;
                crumbs[i] = h.breadcrumbWithHashes();
                pos += KbTextUnits.codePointLength(line);
                if (i < lines.length - 1) {
                    pos++;
                }
            }
            return new BreadcrumbIndex(starts, crumbs);
        }

        /**
         * 查该 rune 偏移处生效的面包屑。
         *
         * @param cpOffset rune 偏移
         * @return 面包屑；无标题 / 索引为空时返回空串
         */
        String at(int cpOffset) {
            if (starts.length == 0 || cpOffset < 0) {
                return "";
            }
            int lo = 0;
            int hi = starts.length - 1;
            int best = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= cpOffset) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return crumbs[best] == null ? "" : crumbs[best];
        }

        /** 索引是否有任何非空面包屑（避免给纯散文文档做无用填充）。 */
        boolean hasAnyHeading() {
            for (String c : crumbs) {
                if (c != null && !c.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}

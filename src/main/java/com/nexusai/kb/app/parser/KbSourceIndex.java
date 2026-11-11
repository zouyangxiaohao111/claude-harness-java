package com.nexusai.kb.app.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 源位置索引 · 回答「分块器的这段文本，对应原文件的哪些位置」。
 *
 * <p><b>源站对照</b>：WeKnora {@code internal/sourceloc/assign.go:13-237}（{@code Index}）、
 * {@code align.go:26-77}（{@code Align}）、{@code text.go:8-48}（{@code TextBlocks}）。
 * 本类是三者的 Java 移植，<b>算法逐行对齐</b>，不做简化。
 *
 * <h2>数据结构</h2>
 * <ul>
 *   <li>{@code runes}：Markdown 的<b>码点</b>数组（⛔ 不是 char 数组 —— 源站用 {@code []rune}）</li>
 *   <li>{@code blocks}：按 {@code start} 升序</li>
 *   <li>{@code maxEnd}：前缀最大值，{@code maxEnd[k] = max(blocks[0..k].end)}。
 *       <b>⭐ 没有它二分就是错的</b>：块可能互相包含（例如整页块里嵌一个表格块），
 *       只比 {@code blocks[k].start} 会把「后面才开始、但结束更早」的块漏掉。</li>
 * </ul>
 *
 * <h2>⭐ partial 语义（源站 :42-80）</h2>
 * 块区间内出现<b>任何</b>「不属于任何 source block 的非空文本」⇒ 整块标 {@code partial=true}。
 * 语义是「这段引用有一部分无法在原文定位」，查看器据此提示用户。判定用
 * {@link #normalizeMarkdown} 投影后的文本是否为空 —— 纯空白/纯 Markdown 记号不算内容。
 *
 * <h2>⭐ 折叠（fold）语义（源站 :110-148）</h2>
 * 相邻两块指向同一个地方时合并成一条：同页无区域的 PDF、同一张幻灯片、连续的行区间、
 * 邻近的纯文本区间。<b>有 bbox 的 PDF 不折叠</b>（区域不同 = 不同位置）。
 *
 * <h2>有意偏离</h2>
 * <ul>
 *   <li>源站 {@code NewIndex} 在无块时返回 {@code nil} 并靠 Go 的 nil-receiver 兜底；
 *       Java 没有 nil receiver，这里返回 {@link #empty()} 单例，<b>行为等价</b>（查询恒空）。</li>
 *   <li>{@code html.UnescapeString} 的 2000+ 实体表只实现常用集 + 数字引用；
 *       未命中的实体保持原样（宁可不转义，也不能吞掉正文）。</li>
 * </ul>
 */
public final class KbSourceIndex {

    private static final Logger log = LoggerFactory.getLogger(KbSourceIndex.class);

    /** 段落实体对齐时忽略过短单元（源站 align.go:21 {@code alignMinRunes}）。 */
    private static final int ALIGN_MIN_RUNES = 2;

    /** 长键分桶前缀的码点数（源站 align.go:83 用 32 <b>字节</b>；本仓按码点分桶，语义等价）。 */
    private static final int ALIGN_PREFIX_UNITS = 8;

    /** 单位行首回退的最大距离（源站 align.go:141 {@code maxLead}）。 */
    private static final int MAX_LEAD = 16;

    private final int[] runes;
    private final List<KbSourceBlock> blocks;
    private final int[] maxEnd;

    private static final KbSourceIndex EMPTY =
        new KbSourceIndex(new int[0], List.of(), new int[0]);

    private KbSourceIndex(int[] runes, List<KbSourceBlock> blocks, int[] maxEnd) {
        this.runes = runes;
        this.blocks = blocks;
        this.maxEnd = maxEnd;
    }

    /** 空索引（无源块时的等价物）· 查询恒返回空列表。 */
    public static KbSourceIndex empty() {
        return EMPTY;
    }

    /**
     * 建立索引（源站 {@code assign.go:22-36 NewIndex}）。
     *
     * @param markdown 解析器最终 Markdown（与分块器输入同一份）
     * @param sourceBlocks 解析器产出的源块；无用/空块被丢弃
     * @return 索引（永不 null；无有效块时返回 {@link #empty()}）
     */
    public static KbSourceIndex of(String markdown, List<KbSourceBlock> sourceBlocks) {
        if (sourceBlocks == null || sourceBlocks.isEmpty()) {
            return EMPTY;
        }
        List<KbSourceBlock> sorted = new ArrayList<>(sourceBlocks.size());
        int dropped = 0;
        for (KbSourceBlock b : sourceBlocks) {
            if (b == null || !b.isValid()) {
                dropped++;
                continue;
            }
            sorted.add(b);
        }
        if (dropped > 0) {
            log.warn("[溯源] 丢弃 {} 个无效 source block（区间为空或负）", dropped);
        }
        if (sorted.isEmpty()) {
            return EMPTY;
        }
        // 源站 SortBlocks：按 start 稳定升序
        sorted.sort(java.util.Comparator.comparingInt(KbSourceBlock::start));
        int[] maxE = new int[sorted.size()];
        for (int k = 0; k < sorted.size(); k++) {
            int e = sorted.get(k).end();
            maxE[k] = (k > 0 && maxE[k - 1] > e) ? maxE[k - 1] : e;
        }
        int[] cps = (markdown == null ? "" : markdown).codePoints().toArray();
        if (log.isInfoEnabled()) {
            log.info("[溯源] 建立源索引 码点={} 块数={} 首个块=[{}, {}) 末个块=[{}, {})",
                cps.length, sorted.size(),
                sorted.get(0).start(), sorted.get(0).end(),
                sorted.get(sorted.size() - 1).start(), sorted.get(sorted.size() - 1).end());
        }
        return new KbSourceIndex(cps, List.copyOf(sorted), maxE);
    }

    /** 是否没有任何块（一切查询恒空）。 */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * 覆盖码点区间 {@code [start, end)} 的源位置，各带被覆盖的原文片段。
     *
     * <p>源站 {@code assign.go:42-80 Locators}。<b>逐行照抄</b>，含 partial 判定与折叠。
     *
     * @param start 码点起点（含）
     * @param end   码点终点（不含）
     * @return 定位列表（可能为空；顺序与块顺序一致）
     */
    public List<KbSourceLocator> locators(int start, int end) {
        if (blocks.isEmpty() || end <= start) {
            return List.of();
        }
        int s = Math.max(start, 0);
        int e = Math.min(end, runes.length);
        if (e <= s) {
            return List.of();
        }
        // 二分：第一个「结束位置 > s」的块（靠 maxEnd 前缀最大值，块可互相包含）
        int i = firstIndexWithMaxEndGreaterThan(s);
        List<KbSourceLocator> out = new ArrayList<>();
        int covered = s;
        boolean partial = false;
        for (; i < blocks.size() && blocks.get(i).start() < e; i++) {
            KbSourceBlock b = blocks.get(i);
            int lo = Math.max(b.start(), s);
            int hi = Math.min(b.end(), e);
            if (hi <= lo) {
                continue;
            }
            // 前面有一段没被任何块覆盖、且其中有真实文字 ⇒ 本块是「部分可定位」
            if (lo > covered && !normalizeMarkdown(fullQuote(slice(covered, lo))).text.isEmpty()) {
                partial = true;
            }
            covered = Math.max(covered, hi);
            String quote = fullQuote(slice(lo, hi));
            // ⭐ PDF 特例（源站 :63）：区域里可能没有文字，空 quote 也要保留，否则整页定位丢失
            if (quote.isEmpty() && !KbSourceLocator.TYPE_PDF.equals(b.locator().type())) {
                continue;
            }
            append(out, b.locator().withQuote(quote));
        }
        // 尾部还有没被覆盖的文字 ⇒ 同样算 partial
        if (covered < e && !normalizeMarkdown(fullQuote(slice(covered, e))).text.isEmpty()) {
            partial = true;
        }
        if (partial) {
            for (int k = 0; k < out.size(); k++) {
                out.set(k, out.get(k).withPartial(true));
            }
        }
        if (out.isEmpty() && log.isInfoEnabled()) {
            log.info("[溯源] 区间 [{}, {}) 无源定位（块未覆盖此区间）", s, e);
        }
        return out;
    }

    /**
     * 包含码点位置 {@code pos} 的块，<b>不带 quote</b>（图片定位用，源站 {@code assign.go:84-99}）。
     *
     * @param pos 码点偏移
     * @return 定位列表（可能为空）
     */
    public List<KbSourceLocator> locatorsAt(int pos) {
        if (blocks.isEmpty() || pos < 0) {
            return List.of();
        }
        int i = firstIndexWithMaxEndGreaterThan(pos);
        List<KbSourceLocator> out = new ArrayList<>();
        for (; i < blocks.size() && blocks.get(i).start() <= pos; i++) {
            KbSourceBlock b = blocks.get(i);
            if (b.end() > pos) {
                out.add(b.locator().withQuote(""));
            }
        }
        return out;
    }

    /**
     * 追加并就地折叠（源站 {@code assign.go:103-108 AppendLocator}）。
     *
     * @param list 目标列表（会被修改）
     * @param loc  待追加
     * @return 同一列表（便于链式调用）
     */
    public static List<KbSourceLocator> append(List<KbSourceLocator> list, KbSourceLocator loc) {
        if (list == null || loc == null) {
            return list == null ? new ArrayList<>() : list;
        }
        int n = list.size();
        if (n > 0) {
            KbSourceLocator folded = foldInto(list.get(n - 1), loc);
            if (folded != null) {
                list.set(n - 1, folded);
                return list;
            }
        }
        list.add(loc);
        return list;
    }

    /**
     * 折叠判定（源站 {@code assign.go:110-148 foldInto}）。
     *
     * <p>前置条件（全部相等才可能折叠）：{@code type} / {@code partial} / {@code mapping} /
     * {@code sourceHash} / {@code sourceId}。
     *
     * @return 折叠后的合并项；{@code null} 表示不可折叠
     */
    static KbSourceLocator foldInto(KbSourceLocator last, KbSourceLocator loc) {
        if (!eq(last.type(), loc.type())
            || last.partial() != loc.partial()
            || !eq(last.mapping(), loc.mapping())
            || !eq(last.sourceHash(), loc.sourceHash())
            || !eq(last.sourceId(), loc.sourceId())) {
            return null;
        }
        KbSourceLocator merged;
        switch (loc.type()) {
            case KbSourceLocator.TYPE_PDF -> {
                // 同页且两边都没有区域才折叠：有 bbox 说明是页面内的不同区域
                if (!eq(last.page(), loc.page()) || nonEmpty(last.bbox()) || nonEmpty(loc.bbox())) {
                    return null;
                }
                merged = last;
            }
            case KbSourceLocator.TYPE_SLIDE -> {
                if (!eq(last.slide(), loc.slide())) {
                    return null;
                }
                merged = last;
            }
            case KbSourceLocator.TYPE_SECTION -> {
                if (!eq(last.section(), loc.section())) {
                    return null;
                }
                merged = last;
            }
            case KbSourceLocator.TYPE_SHEET -> {
                int lastStart = intOr0(last.rowStart());
                int lastEnd = intOr0(last.rowEnd());
                int locStart = intOr0(loc.rowStart());
                int locEnd = intOr0(loc.rowEnd());
                // 行区间必须相接或重叠（源站 :132）
                if (!eq(last.sheet(), loc.sheet())
                    || locStart > lastEnd + 1
                    || locEnd < lastStart) {
                    return null;
                }
                merged = last.withRowRange(Math.min(lastStart, locStart), Math.max(lastEnd, locEnd));
            }
            case KbSourceLocator.TYPE_TEXT -> {
                int lastStart = intOr0(last.start());
                int lastEnd = intOr0(last.end());
                int locStart = intOr0(loc.start());
                int locEnd = intOr0(loc.end());
                // 允许 2 个码点的缝隙（源站 :138：中间可能夹一个换行/空格）
                if (locStart > lastEnd + 2 || locEnd < lastStart) {
                    return null;
                }
                merged = last.withRuneRange(Math.min(lastStart, locStart), Math.max(lastEnd, locEnd));
            }
            default -> {
                // docx / time 等其余类型不折叠（源站 :143-144）
                return null;
            }
        }
        return merged.withQuote(joinQuote(merged.quote(), loc.quote()));
    }

    /**
     * 并集去重（源站 {@code assign.go:152-173 MergeLocators}）· 检索结果合并时用。
     *
     * @param a 列表 A（不会被修改）
     * @param b 列表 B（不会被修改）
     * @return 去重后的新列表
     */
    public static List<KbSourceLocator> merge(List<KbSourceLocator> a, List<KbSourceLocator> b) {
        if (b == null || b.isEmpty()) {
            return a == null ? List.of() : a;
        }
        if (a == null || a.isEmpty()) {
            return b;
        }
        List<KbSourceLocator> out = new ArrayList<>(a);
        for (KbSourceLocator loc : b) {
            boolean dup = false;
            for (KbSourceLocator have : out) {
                if (have.sameAs(loc)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(loc);
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 重映射（源站 remap.go:120-136 RemapBlocks）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把「按旧文本记账」的源块重新对准重写后的文本。
     *
     * <p><b>为什么必须有它</b>：入库前会把解析产物做行尾归一（CRLF → LF），
     * 删掉 {@code \r} 会让<b>后面所有码点偏移整体左移</b>。若直接把旧块套到新文本上，
     * 引用会指向错位的字符 —— 而且不报错。
     *
     * <p><b>做法（源站同款）</b>：不是按比例插值（那会为「被删掉的内容」编造位置），
     * 而是把每个旧块的归一化文本当「结构单元」，在新文本里重新 {@link #align} 一遍。
     * 对不上的块直接丢弃（宁可没有定位，也不给错的定位）。
     *
     * @param blocks  旧文本上的块
     * @param oldText 记账时的文本
     * @param newText 重写后的文本
     * @return 新文本上的块（可能为空）
     */
    public static List<KbSourceBlock> remap(List<KbSourceBlock> blocks, String oldText, String newText) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        if (oldText == null || newText == null || oldText.equals(newText)) {
            return blocks;
        }
        int[] oldRunes = oldText.codePoints().toArray();
        List<Unit> units = new ArrayList<>(blocks.size());
        int skipped = 0;
        for (KbSourceBlock b : blocks) {
            if (b == null) {
                skipped++;
                continue;
            }
            if (b.start() < 0 || b.end() > oldRunes.length || b.end() <= b.start()) {
                skipped++;
                continue;
            }
            String frag = new String(oldRunes, b.start(), b.end() - b.start());
            units.add(new Unit(normalizeMarkdown(frag).text, b.locator()));
        }
        List<KbSourceBlock> out = align(newText, units);
        if (log.isInfoEnabled()) {
            log.info("[溯源] 源块重映射 旧文本码点={} 新文本码点={} 输入块={} 无效跳过={} 重新对齐={}",
                oldRunes.length, newText.codePointCount(0, newText.length()),
                blocks.size(), skipped, out.size());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 引用文本清洗（源站 assign.go:198-237）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 展示用引用片段：清洗 Markdown 记号 + 压空白，并截断到 {@link KbSourceLocator#QUOTE_MAX} 码点。
     *
     * @param text 原文片段（可 null）
     * @return 清洗后的片段（可能为空串）
     */
    public static String cleanQuote(String text) {
        return capRunes(fullQuote(text), KbSourceLocator.QUOTE_MAX);
    }

    /**
     * 完整证据片段（不截断）· 源站 {@code assign.go:213-220 fullQuote}。
     *
     * <p><b>清洗顺序不可换</b>：先删图片 → 再处理链接（保留锚文本）→ 再删 HTML 标签 → 反转义实体
     * → 压空白。顺序换了会把「链接里的 URL」当成正文留下。
     */
    static String fullQuote(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text;
        s = QUOTE_IMAGE_RE.matcher(s).replaceAll(" ");
        s = QUOTE_LINK_RE.matcher(s).replaceAll("$1");
        s = QUOTE_TAG_RE.matcher(s).replaceAll(" ");
        s = unescapeHtml(s);
        s = QUOTE_SPACE_RE.matcher(s).replaceAll(" ");
        return s.strip();
    }

    /** 拼接两段引用（源站 {@code joinQuote}）：任一侧空则取另一侧，否则空格连接。 */
    static String joinQuote(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return a + " " + b;
    }

    /** 按<b>码点</b>截断（源站 {@code capRunes} 用 rune 数，⛔ 不是字节也不是 char）。 */
    static String capRunes(String s, int n) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        int idx = s.offsetByCodePoints(0, n);
        return s.substring(0, idx);
    }

    // ════════════════════════════════════════════════════════════════════
    // 纯文本 → 段落块（源站 text.go:8-48）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 纯文本（Markdown / TXT）按段落切块，locator 的码点区间与之相同
     * （源站 {@code text.go:8-48 TextBlocks}）。
     *
     * <p>段落定义：连续非空行组成一段；<b>连续空行中的第一个空行</b>作为段末，
     * 只含空白字符的行不算正文。这样「原文逐字未经解析器改写」时，块就是原文的段。
     *
     * @param content 原文
     * @return 段落块（按位置升序）
     */
    public static List<KbSourceBlock> textBlocks(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<KbSourceBlock> blocks = new ArrayList<>();
        boolean[] hasStart = {false};
        int start = -1;
        int blankRun = 0;
        boolean lineHasText = false;
        int lineStart = 0;
        int pos = 0;
        int len = content.codePointCount(0, content.length());
        for (int idx = 0; idx < content.length(); ) {
            int cp = content.codePointAt(idx);
            idx += Character.charCount(cp);
            switch (cp) {
                case '\n' -> {
                    if (!lineHasText) {
                        blankRun++;
                        if (blankRun == 1 && hasStart[0] && lineStart > start) {
                            blocks.add(new KbSourceBlock(start, lineStart, KbSourceLocator.text(start, lineStart)
                                .withMapping(KbSourceLocator.MAPPING_EXACT)));
                            hasStart[0] = false;
                        }
                    } else {
                        blankRun = 0;
                    }
                    lineHasText = false;
                    lineStart = pos + 1;
                }
                case ' ', '\t', '\r' -> {
                    // 段内空白：不开启新段，也不打断当前段
                }
                default -> {
                    if (!lineHasText && !hasStart[0]) {
                        start = lineStart;
                        hasStart[0] = true;
                    }
                    lineHasText = true;
                }
            }
            pos++;
        }
        if (hasStart[0] && pos > start) {
            blocks.add(new KbSourceBlock(start, pos, KbSourceLocator.text(start, pos)
                .withMapping(KbSourceLocator.MAPPING_EXACT)));
        }
        if (pos != len && log.isWarnEnabled()) {
            log.warn("[溯源] 段落切块码点计数与预期不一致 实计={} codePointCount={}", pos, len);
        }
        return blocks;
    }

    // ════════════════════════════════════════════════════════════════════
    // 结构单元 → Markdown 区间（源站 align.go:13-150）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 一个「结构单元」：原文件里的一段真实结构（Word 的一个段落、Excel 的一行、PPT 的一条文本）
     * 及其应有的定位（源站 {@code align.go:13-18 Unit}）。
     *
     * @param text    该结构的纯文本（用于在 Markdown 里定位）
     * @param locator 该结构指向的原文件位置
     */
    public record Unit(String text, KbSourceLocator locator) {
    }

    /**
     * 把结构单元对齐到解析器 Markdown，得到源块（源站 {@code align.go:22-77 Align}）。
     *
     * <p><b>⭐ 为什么不能「边拼 Markdown 边记账」</b>：Office 的 Markdown 由 Tika 产出，
     * docx 段落序号 / xlsx 行号 / pptx 页号都不在 XHTML 事件里，靠事件计数遇空段落就错位。
     * 源站的做法是「先抽结构单元，再按文本内容对齐」，本方法照此实现。
     *
     * <p><b>⭐ 严格已知（不猜）</b>：只有当某单元文本在文档里的<b>出现次数</b>与「同等文本的单元数」
     * 完全相等时才定位；否则放弃该单元（源站 :41-47 注释：把未知区间指派给前一块会指向完全不同的页）。
     *
     * @param markdown 解析器 Markdown
     * @param units    结构单元（按文档顺序）
     * @return 源块（按位置升序）；无法定位时为空列表
     */
    public static List<KbSourceBlock> align(String markdown, List<Unit> units) {
        if (markdown == null || markdown.isEmpty() || units == null || units.isEmpty()) {
            return List.of();
        }
        Normalized norm = normalizeMarkdown(markdown);
        int[] runes = markdown.codePoints().toArray();
        List<KbSourceBlock> blocks = new ArrayList<>();
        int cursor = 0;
        int floor = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (Unit u : units) {
            counts.merge(normalizeKey(u.text()), 1, Integer::sum);
        }
        Map<String, int[]> matches = indexNormalizedMatches(norm.text, counts);
        int skippedShort = 0;
        int skippedAmbiguous = 0;
        int skippedBeforeCursor = 0;
        for (Unit u : units) {
            String key = normalizeKey(u.text());
            if (runeCount(key) < ALIGN_MIN_RUNES) {
                skippedShort++;
                continue;
            }
            int[] positions = matches.get(key);
            if (positions == null || positions.length != counts.get(key)) {
                skippedAmbiguous++;
                continue;
            }
            int matchIndex = lowerBound(positions, cursor);
            if (matchIndex == positions.length) {
                skippedBeforeCursor++;
                continue;
            }
            int at = positions[matchIndex];
            int endIdx = at + key.length();
            int start = lineLeadStart(runes, norm.pos[at], floor);
            int end = norm.end[endIdx - 1];
            if (endIdx == norm.text.length()) {
                end = runes.length;
            } else {
                int next = norm.pos[endIdx];
                int lead = next;
                while (lead > 0 && runes[lead - 1] != '\n') {
                    lead--;
                }
                if (lead >= end) {
                    end = lead;
                }
            }
            if (end > start) {
                blocks.add(new KbSourceBlock(start, end,
                    u.locator().withMapping(KbSourceLocator.MAPPING_EXACT)));
            }
            cursor = endIdx;
            floor = end;
        }
        if (log.isInfoEnabled()) {
            log.info("[溯源] 结构对齐 单元={} 命中={} 过短跳过={} 出现次数不匹配跳过={} 已在游标之前跳过={}",
                units.size(), blocks.size(), skippedShort, skippedAmbiguous, skippedBeforeCursor);
        }
        return blocks;
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部：二分 / 归一化（源站 align.go:152-297）
    // ════════════════════════════════════════════════════════════════════

    /** 第一个「maxEnd > 目标」的块下标（源站 {@code sort.Search} + 前缀最大值）。 */
    private int firstIndexWithMaxEndGreaterThan(int target) {
        int lo = 0;
        int hi = maxEnd.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (maxEnd[mid] > target) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /** 有序数组里第一个 {@code >= target} 的下标（源站 {@code sort.SearchInts}）。 */
    private static int lowerBound(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 码点切片（{@code [start, end)}）→ 字符串。 */
    private String slice(int start, int end) {
        int s = Math.max(0, Math.min(start, runes.length));
        int e = Math.max(s, Math.min(end, runes.length));
        return new String(runes, s, e - s);
    }

    /**
     * Markdown 归一化投影（源站 {@code align.go:152-220}）。
     *
     * <p>{@code text} 只保留字母/数字与数值符号；{@code pos[i]} 是 {@code text} 第 i 个 char
     * 在源文本里的<b>码点</b>起点，{@code end[i]} 是码点终点（不含）。
     * 跳过链接/图片的目标地址与 HTML 标签 —— URL 不是文档正文。
     */
    static Normalized normalizeMarkdown(String markdown) {
        StringBuilder sb = new StringBuilder(markdown.length());
        List<Integer> posList = new ArrayList<>(markdown.length());
        List<Integer> endList = new ArrayList<>(markdown.length());
        int[] rs = markdown.codePoints().toArray();
        for (int i = 0; i < rs.length; i++) {
            int r = rs[i];
            if (r == '<' && i + 1 < rs.length
                && (isAsciiLetter(rs[i + 1]) || rs[i + 1] == '/' || rs[i + 1] == '!')) {
                int end = indexRune(rs, '>', i + 1, 4096);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (r == ']' && i + 1 < rs.length && rs[i + 1] == '(') {
                int end = indexRune(rs, ')', i + 2, 1 << 20);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (r == '&') {
                int end = indexRune(rs, ';', i + 1, 32);
                if (end > 0) {
                    String raw = new String(rs, i, end + 1 - i);
                    String decoded = unescapeHtml(raw);
                    if (!decoded.equals(raw)) {
                        // 实体按解码后的字符逐个 emit；上下文决定数值符号是否保留
                        int[] ctx = new int[decoded.codePointCount(0, decoded.length()) + 2];
                        ctx[0] = i > 0 ? rs[i - 1] : ' ';
                        int[] dec = decoded.codePoints().toArray();
                        System.arraycopy(dec, 0, ctx, 1, dec.length);
                        ctx[ctx.length - 1] = end + 1 < rs.length ? rs[end + 1] : ' ';
                        for (int j = 1; j + 1 < ctx.length; j++) {
                            int nr = matchRune(ctx, j);
                            if (nr >= 0) {
                                appendEmitted(sb, posList, endList, nr, i, end + 1);
                            }
                        }
                        i = end;
                        continue;
                    }
                }
            }
            int nr = matchRune(rs, i);
            if (nr >= 0) {
                appendEmitted(sb, posList, endList, nr, i, i + 1);
            }
        }
        int[] pos = new int[posList.size()];
        int[] ends = new int[endList.size()];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = posList.get(i);
            ends[i] = endList.get(i);
        }
        return new Normalized(sb.toString(), pos, ends);
    }

    private static void appendEmitted(StringBuilder sb, List<Integer> pos, List<Integer> ends,
                                      int r, int start, int end) {
        int before = sb.length();
        sb.appendCodePoint(r);
        for (int k = before; k < sb.length(); k++) {
            pos.add(start);
            ends.add(end);
        }
    }

    /** 归一化后的 Markdown 视图。 */
    static final class Normalized {
        final String text;
        final int[] pos;
        final int[] end;

        Normalized(String text, int[] pos, int[] end) {
            this.text = text;
            this.pos = pos;
            this.end = end;
        }
    }

    /** 纯文本归一化键（源站 {@code align.go:223-232 normalizeKey}）。 */
    static String normalizeKey(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int[] rs = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder(rs.length);
        for (int i = 0; i < rs.length; i++) {
            int r = matchRune(rs, i);
            if (r >= 0) {
                sb.appendCodePoint(r);
            }
        }
        return sb.toString();
    }

    /**
     * 单趟扫描找出全部请求键的出现位置（源站 {@code align.go:90-123}）。
     *
     * @return 归一化键 → 升序 char 下标数组
     */
    static Map<String, int[]> indexNormalizedMatches(String text, Map<String, Integer> counts) {
        Map<String, List<String>> longBuckets = new HashMap<>();
        Map<Integer, List<String>> shortBuckets = new HashMap<>();
        for (String key : counts.keySet()) {
            if (runeCount(key) < ALIGN_MIN_RUNES) {
                continue;
            }
            String prefix = prefixOf(key);
            if (prefix != null) {
                longBuckets.computeIfAbsent(prefix, k -> new ArrayList<>()).add(key);
            } else {
                shortBuckets.computeIfAbsent(key.codePointAt(0), k -> new ArrayList<>()).add(key);
            }
        }
        Map<String, List<Integer>> found = new HashMap<>();
        for (int at = 0; at < text.length(); ) {
            int cp = text.codePointAt(at);
            for (String key : shortBuckets.getOrDefault(cp, List.of())) {
                if (text.startsWith(key, at) && boundaryOk(text, at, key)) {
                    found.computeIfAbsent(key, k -> new ArrayList<>()).add(at);
                }
            }
            String prefix = prefixAt(text, at);
            if (prefix != null) {
                for (String key : longBuckets.getOrDefault(prefix, List.of())) {
                    if (text.startsWith(key, at) && boundaryOk(text, at, key)) {
                        found.computeIfAbsent(key, k -> new ArrayList<>()).add(at);
                    }
                }
            }
            at += Character.charCount(cp);
        }
        Map<String, int[]> out = new HashMap<>(found.size() * 2);
        for (Map.Entry<String, List<Integer>> e : found.entrySet()) {
            out.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return out;
    }

    /** 键的前 {@value #ALIGN_PREFIX_UNITS} 个码点（不足则 null = 走短键分桶）。 */
    private static String prefixOf(String key) {
        int units = key.codePointCount(0, key.length());
        if (units < ALIGN_PREFIX_UNITS) {
            return null;
        }
        return key.substring(0, key.offsetByCodePoints(0, ALIGN_PREFIX_UNITS));
    }

    /** 文本从 {@code at} 起的前 {@value #ALIGN_PREFIX_UNITS} 个码点（不足则 null）。 */
    private static String prefixAt(String text, int at) {
        int units = text.codePointCount(at, text.length());
        if (units < ALIGN_PREFIX_UNITS) {
            return null;
        }
        return text.substring(at, text.offsetByCodePoints(at, ALIGN_PREFIX_UNITS));
    }

    /** 拒绝「嵌在别的数字里」的命中（源站 {@code align.go:126-134}）。 */
    static boolean boundaryOk(String text, int at, String key) {
        int first = key.codePointAt(0);
        int last = key.codePointBefore(key.length());
        int before = at > 0 ? text.codePointBefore(at) : -1;
        int afterIdx = at + key.length();
        int after = afterIdx < text.length() ? text.codePointAt(afterIdx) : -1;
        boolean left = Character.isDigit(first)
            && (Character.isDigit(before) || ",.:/+−-".indexOf(before) >= 0);
        boolean right = Character.isDigit(last)
            && (Character.isDigit(after) || ".,:/%‰".indexOf(after) >= 0);
        return !left && !right;
    }

    /**
     * 行首回退（源站 {@code align.go:140-150 lineLeadStart}）。
     *
     * <p>命中位置前面若只有 Markdown 标记 / 序号（{@code "## "} / {@code "| "} / {@code "1. "}），
     * 回退到行首，让块把标题记号一起覆盖。<b>遇到字母立即停</b>（说明那里已经是正文）。
     */
    static int lineLeadStart(int[] runes, int pos, int floor) {
        int start = pos;
        while (start > floor && runes[start - 1] != '\n') {
            if (pos - start >= MAX_LEAD || Character.isLetter(runes[start - 1])) {
                return pos;
            }
            start--;
        }
        return start;
    }

    /**
     * 判断单个码点是否参与归一化（源站 {@code align.go:235-261 matchRune}）。
     *
     * <p>保留字母/数字；数值符号（{@code . , / : - + % < > =} 等）只在「与数字相邻」时保留，
     * 以便区分 {@code 1.5} 与 {@code 15}。
     *
     * @return 归一化后的码点；{@code -1} = 丢弃
     */
    static int matchRune(int[] rs, int i) {
        int r = rs[i];
        if (r >= 0xFF01 && r <= 0xFF5E) {
            r -= 0xFEE0;   // 全角 → 半角
        }
        if (".,:/+−-%‰<>=≤≥≠".indexOf(r) < 0) {
            return foldRune(r);
        }
        int before = i - 1;
        int after = i + 1;
        while (before >= 0 && (rs[before] == ' ' || rs[before] == '\t')) {
            before--;
        }
        while (after < rs.length && (rs[after] == ' ' || rs[after] == '\t')) {
            after++;
        }
        boolean prevDigit = before >= 0 && Character.isDigit(rs[before]);
        boolean nextDigit = after < rs.length && Character.isDigit(rs[after]);
        boolean keep = ((r == '.' || r == ',' || r == '/' || r == ':') && prevDigit && nextDigit)
            || ((r == '-' || r == '+' || r == '−') && nextDigit)
            || ((r == '%' || r == '‰') && prevDigit)
            || "<>=≤≥≠".indexOf(r) >= 0;
        if (keep) {
            return r == '−' ? '-' : r;
        }
        return foldRune(r);
    }

    /** 只保留字母/数字，转小写（源站 {@code align.go:265-273 foldRune}）。 */
    static int foldRune(int r) {
        if (r >= 0xFF01 && r <= 0xFF5E) {
            r -= 0xFEE0;
        }
        if (!Character.isLetter(r) && !Character.isDigit(r)) {
            return -1;
        }
        return Character.toLowerCase(r);
    }

    private static boolean isAsciiLetter(int r) {
        return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z');
    }

    /** 在 {@code runes[from..]} 里找 {@code target}；遇空行或超过 limit 个码点放弃（源站 {@code indexRune}）。 */
    private static int indexRune(int[] runes, int target, int from, int limit) {
        int end = Math.min(runes.length, from + limit);
        for (int i = from; i < end; i++) {
            if (runes[i] == target) {
                return i;
            }
            if (runes[i] == '\n' && i + 1 < runes.length && runes[i + 1] == '\n') {
                return -1;
            }
        }
        return -1;
    }

    /** 码点计数（⛔ 不用 {@code String.length()}）。 */
    static int runeCount(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部：HTML 实体反解（Go html.UnescapeString 的常用子集）
    // ════════════════════════════════════════════════════════════════════

    private static final Map<String, String> NAMED_ENTITIES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("amp", "&");
        m.put("lt", "<");
        m.put("gt", ">");
        m.put("quot", "\"");
        m.put("apos", "'");
        m.put("nbsp", "\u00A0");
        m.put("ensp", "\u2002");
        m.put("emsp", "\u2003");
        m.put("thinsp", "\u2009");
        m.put("ndash", "–");
        m.put("mdash", "—");
        m.put("hellip", "…");
        m.put("middot", "·");
        m.put("bull", "•");
        m.put("copy", "©");
        m.put("reg", "®");
        m.put("trade", "™");
        m.put("deg", "°");
        m.put("plusmn", "±");
        m.put("times", "×");
        m.put("divide", "÷");
        m.put("frac12", "½");
        m.put("frac14", "¼");
        m.put("frac34", "¾");
        m.put("sup2", "²");
        m.put("sup3", "³");
        m.put("micro", "µ");
        m.put("para", "¶");
        m.put("sect", "§");
        m.put("laquo", "«");
        m.put("raquo", "»");
        m.put("ldquo", "“");
        m.put("rdquo", "”");
        m.put("lsquo", "‘");
        m.put("rsquo", "’");
        m.put("larr", "←");
        m.put("rarr", "→");
        m.put("le", "≤");
        m.put("ge", "≥");
        m.put("ne", "≠");
        m.put("permil", "‰");
        NAMED_ENTITIES = Collections.unmodifiableMap(m);
    }

    /**
     * 反解 HTML 实体（Go {@code html.UnescapeString} 的常用子集）。
     *
     * <p>无法识别的实体<b>原样保留</b> —— 宁可留着 {@code &foo;} 让用户看见，也不能把它删掉
     * 导致正文缺字。
     */
    static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 32) {
                out.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, semi);
            String decoded = null;
            if (body.startsWith("#")) {
                decoded = decodeNumericEntity(body.substring(1));
            } else {
                decoded = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
            }
            if (decoded == null) {
                out.append(c);
                i++;
                continue;
            }
            out.append(decoded);
            i = semi + 1;
        }
        return out.toString();
    }

    /** {@code #NN} / {@code #xHH} → 字符；非法返回 null。 */
    private static String decodeNumericEntity(String body) {
        if (body.isEmpty()) {
            return null;
        }
        try {
            int cp;
            if (body.charAt(0) == 'x' || body.charAt(0) == 'X') {
                cp = Integer.parseInt(body.substring(1), 16);
            } else {
                cp = Integer.parseInt(body);
            }
            if (cp <= 0 || cp > Character.MAX_CODE_POINT || !Character.isValidCodePoint(cp)) {
                return null;
            }
            return new String(Character.toChars(cp));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 正则（源站 assign.go:198-203）· 顺序与语义逐条对齐 ──

    /** 图片：{@code ![alt](src)} —— 整段删除。 */
    private static final java.util.regex.Pattern QUOTE_IMAGE_RE =
        java.util.regex.Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\n]*\\)");

    /** 链接：{@code [anchor](url)} —— 只留锚文本。 */
    private static final java.util.regex.Pattern QUOTE_LINK_RE =
        java.util.regex.Pattern.compile("\\[([^\\]\\n]*)\\]\\([^)\\n]*\\)");

    /** HTML 标签与注释 —— 删除。 */
    private static final java.util.regex.Pattern QUOTE_TAG_RE =
        java.util.regex.Pattern.compile("</?[a-zA-Z][^>\\n]*>|<!--.*?-->");

    /** 空白压缩（Go {@code \s} 的等价集合：{@code \t \n \f \r 空格}）。 */
    private static final java.util.regex.Pattern QUOTE_SPACE_RE =
        java.util.regex.Pattern.compile("[\\t\\n\\f\\r ]+");

    // ── 小工具 ──

    private static boolean eq(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private static boolean nonEmpty(double[] arr) {
        return arr != null && arr.length > 0;
    }

    private static int intOr0(Integer v) {
        return v == null ? 0 : v;
    }
}

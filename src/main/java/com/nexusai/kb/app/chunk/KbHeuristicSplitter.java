package com.nexusai.kb.app.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Tier 2 · 启发式边界分块 · 对齐 WeKnora {@code chunker/heuristic_splitter.go:1-313}
 * （边界正则来自 {@code patterns.go}，已并入 {@link KbDocProfile} 的 patterns 区）。
 *
 * <p><b>解决什么问题</b>（源侧 {@code :1-9}）：没有 Markdown 标题、但有可识别结构线索的文档
 * —— 分页符、编号章节、多语章节标记、视觉分隔线、全大写标题、页码。
 *
 * <p><b>算法</b>：
 * <ol>
 *   <li>{@link #findHeuristicBoundaries} 收集所有候选边界（带优先级）</li>
 *   <li>丢掉<b>严格落在受保护片段内部</b>的边界（在表格/代码块/公式中间切会切碎语义），
 *       但落在片段<b>边缘</b>的保留</li>
 *   <li>贪心装箱：把边界之间的块累积到 {@code chunkSize} 上限；
 *       单个块自身超限 ⇒ 交给 legacy 切内部（这样受保护片段与尺寸预算都还是 legacy 那套）</li>
 *   <li>换块时用 {@link #applyOverlapAligned} 把重叠起点吸附到最近的语义边界或换行，
 *       不切在半行/半词上</li>
 * </ol>
 *
 * <p><b>⚠️ 与源站的坐标口径差异（有意）</b>：源侧 {@code chunk} 的 Start/End 是 rune 偏移，
 * 本仓 {@link KbChunk} 同样是 code point 偏移 ⇒ 一致，无需换算。
 */
final class KbHeuristicSplitter {

    private static final Logger log = LoggerFactory.getLogger(KbHeuristicSplitter.class);

    private KbHeuristicSplitter() {
    }

    /**
     * 切分入口 · 对齐源侧 {@code splitByHeuristicsImpl}（{@code :34-108}）。
     *
     * @param text    原文
     * @param cfg     配置（已归一化）
     * @param profile 画像（源侧此层不用，仅为签名一致；本实现用来在日志里报依据）
     * @return 分块
     */
    static List<KbChunk> split(String text, KbSplitterConfig cfg, KbDocProfile profile) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int[] runes = text.codePoints().toArray();
        int totalRunes = runes.length;
        if (totalRunes <= cfg.chunkSize()) {
            log.info("[分块] HEURISTIC 不适用：全文 {} rune ≤ chunkSize={} ⇒ 交回 legacy",
                totalRunes, cfg.chunkSize());
            return KbTextSplitter.splitLegacyRaw(text, cfg);
        }

        int markerTotal = profile == null ? -1 : profile.heuristicMarkerTotal();
        List<Boundary> bounds = findHeuristicBoundaries(text, cfg.languages());
        int rawBoundCount = bounds.size();
        log.info("[分块] HEURISTIC 候选边界={}（画像启发式标记={} 换页符={}）",
            rawBoundCount, markerTotal, profile == null ? -1 : profile.formFeedCount());

        // 丢弃严格落在受保护片段内部的边界
        List<RuneSpan> prot = protectedSpansRune(text);
        int droppedInProtected = 0;
        if (!prot.isEmpty()) {
            int before = bounds.size();
            bounds = dropBoundsInsideSpans(bounds, prot);
            droppedInProtected = before - bounds.size();
        }
        if (bounds.isEmpty()) {
            log.warn("[分块] HEURISTIC 无可用边界（受保护片段内丢弃 {} 个）⇒ 交回 legacy",
                droppedInProtected);
            return KbTextSplitter.splitLegacyRaw(text, cfg);
        }

        // 末尾哨兵（让装箱器能把最后一段 flush 出来）
        List<Boundary> all = new ArrayList<>(bounds);
        all.add(new Boundary(totalRunes, 0));
        if (all.get(0).runeStart() != 0) {
            all.add(0, new Boundary(0, 0));
        }

        // ── 贪心装箱 ────────────────────────────────────────────────
        List<KbChunk> out = new ArrayList<>();
        int seq = 0;
        int chunkStart = all.get(0).runeStart();
        int curEnd = chunkStart;
        int minChunkSize = cfg.chunkSize() / 4;
        if (minChunkSize < 50) {
            minChunkSize = 50;
        }
        int flushed = 0;
        int oversizeBlocks = 0;
        int overlapSnaps = 0;
        int overlapFallbacks = 0;

        for (int i = 1; i < all.size(); i++) {
            int nextEnd = all.get(i).runeStart();
            int blockLen = nextEnd - curEnd;

            if (blockLen > cfg.chunkSize()) {
                // 这一段自己就超预算 ⇒ 先 flush 已累积的，再交给 legacy 切它
                if (curEnd - chunkStart > 0) {
                    seq = appendChunk(out, runes, chunkStart, curEnd, seq);
                    flushed++;
                    chunkStart = curEnd;
                }
                int before = out.size();
                seq = appendOversizeBlock(out, runes, curEnd, nextEnd, cfg, seq);
                oversizeBlocks++;
                log.info("[分块] HEURISTIC 超长段 [{}..{}) 长={} ⇒ legacy 内部切成 {} 块",
                    curEnd, nextEnd, blockLen, out.size() - before);
                curEnd = nextEnd;
                chunkStart = nextEnd;
                continue;
            }

            int accumulated = nextEnd - chunkStart;
            if (accumulated > cfg.chunkSize() && curEnd - chunkStart >= minChunkSize) {
                seq = appendChunk(out, runes, chunkStart, curEnd, seq);
                flushed++;
                OverlapPick pick = applyOverlapAligned(runes, curEnd, cfg.chunkOverlap(), all);
                chunkStart = pick.start();
                if (pick.fromBoundary()) {
                    overlapSnaps++;
                } else if (pick.fromNewline()) {
                    overlapFallbacks++;
                }
            }
            curEnd = nextEnd;
        }
        if (curEnd > chunkStart) {
            seq = appendChunk(out, runes, chunkStart, curEnd, seq);
            flushed++;
        }

        log.info("[分块] HEURISTIC 明细：有效边界={} 受保护片段={} 片段内丢弃={} 装箱 flush={} "
                + "超长段={} 重叠吸附语义边界={} 重叠吸附换行={} 产出={}",
            rawBoundCount, prot.size(), droppedInProtected, flushed, oversizeBlocks,
            overlapSnaps, overlapFallbacks, out.size());
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 边界发现 · 对齐 heuristic_splitter.go:22-26 / :112-229
    // ════════════════════════════════════════════════════════════════════

    /**
     * 候选边界。
     *
     * @param runeStart 下一个块应从这里开始（rune 偏移）
     * @param priority  优先级（越高越强）
     */
    record Boundary(int runeStart, int priority) {
    }

    /**
     * rune 偏移的受保护区间。
     *
     * @param start 起始 rune 偏移（含）
     * @param end   结束 rune 偏移（不含）
     */
    record RuneSpan(int start, int end) {
    }

    /**
     * 找启发式边界 · 对齐源侧 {@code findHeuristicBoundaries}（{@code :112-188}）。
     *
     * <p>优先级从前到后<b>互斥</b>（一行只取最高优先级的那个边界）：
     * 换页符(100) &gt; 章节标记(85) &gt; 编号标题(90) &gt; 全大写(70) &gt; 分隔线(60) &gt; 页脚(50)，
     * 另有连续空行(40)。
     *
     * <p>⚠️ 源侧在行循环里对「章节标记 / 编号 / 全大写 / 分隔线 / 页脚」用了 {@code added} 短路，
     * 顺序是 <b>章节 → 编号 → 全大写 → 分隔线 → 页脚</b>（{@code :132-153}）。
     * 编号的优先级数值虽然(90)高于章节(85)，但短路顺序让章节先命中 —— 照抄该行为。
     *
     * @param text  原文
     * @param langs 语言提示（空 = 三种章节正则全用）
     * @return 按位置升序、同位置只留最高优先级的边界；无边界返回空列表
     */
    static List<Boundary> findHeuristicBoundaries(String text, List<String> langs) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Boundary> bounds = new ArrayList<>();

        // ① 换页符 —— 最强的单字符边界
        int cpIndex = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\f') {
                bounds.add(new Boundary(cpIndex, KbDocProfile.PRIO_FORM_FEED));
            }
            cpIndex++;
        }

        // ② 逐行模式
        String[] lines = text.split("\n", -1);
        List<java.util.regex.Pattern> chapterPatterns = KbDocProfile.chapterPatternsForLangs(langs);
        int pos = 0;
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                boolean added = false;
                for (java.util.regex.Pattern pat : chapterPatterns) {
                    if (pat.matcher(line).find()) {
                        bounds.add(new Boundary(pos, KbDocProfile.PRIO_CHAPTER_MARKER));
                        added = true;
                        break;
                    }
                }
                if (!added && KbDocProfile.NUMBERED_SECTION.matcher(line).find()) {
                    bounds.add(new Boundary(pos, KbDocProfile.PRIO_NUMBERED_HEAD));
                    added = true;
                }
                if (!added && KbDocProfile.ALL_CAPS_HEADING.matcher(line).find()) {
                    bounds.add(new Boundary(pos, KbDocProfile.PRIO_ALL_CAPS_HEADING));
                    added = true;
                }
                if (!added && KbDocProfile.VISUAL_SEPARATOR.matcher(line).find()) {
                    bounds.add(new Boundary(pos, KbDocProfile.PRIO_VISUAL_SEP));
                    added = true;
                }
                if (!added && KbDocProfile.PAGE_FOOTER.matcher(line).find()) {
                    bounds.add(new Boundary(pos, KbDocProfile.PRIO_PAGE_FOOTER));
                }
            }
            pos += KbTextUnits.codePointLength(line);
            if (i < lines.length - 1) {
                pos++; // \n
            }
        }

        // ③ 连续空行（\n{3,}）—— 源侧取匹配<b>结束</b>位置作为下一块起点
        Matcher blanks = KbDocProfile.EXCESSIVE_BLANKS.matcher(text);
        while (blanks.find()) {
            int runeStart = KbTextUnits.codePointOffset(text, blanks.end());
            bounds.add(new Boundary(runeStart, KbDocProfile.PRIO_BLANK_BLOCK));
        }

        if (bounds.isEmpty()) {
            return List.of();
        }

        // 按位置升序；同位置优先级高的在前
        bounds.sort(Comparator.comparingInt(Boundary::runeStart)
            .thenComparing(Comparator.comparingInt(Boundary::priority).reversed()));
        List<Boundary> deduped = new ArrayList<>(bounds.size());
        int prev = -1;
        for (Boundary b : bounds) {
            if (b.runeStart() != prev) {
                deduped.add(b);
                prev = b.runeStart();
            }
        }
        return deduped;
    }

    /**
     * 丢弃严格落在受保护区间内的边界 · 对齐源侧 {@code dropBoundsInsideSpans}（{@code :194-212}）。
     *
     * <p>落在片段<b>起点或终点</b>的边界保留 —— 它与片段边缘对齐，不会切开受保护内容。
     *
     * @param bounds 边界（按 runeStart 升序）
     * @param spans  受保护区间（按 start 升序）
     * @return 过滤后的边界
     */
    static List<Boundary> dropBoundsInsideSpans(List<Boundary> bounds, List<RuneSpan> spans) {
        if (spans.isEmpty()) {
            return bounds;
        }
        List<Boundary> out = new ArrayList<>(bounds.size());
        for (Boundary b : bounds) {
            boolean inside = false;
            for (RuneSpan s : spans) {
                if (s.start() >= b.runeStart()) {
                    break; // 后面的 span 都从 b 之后开始，不可能包含 b
                }
                if (b.runeStart() < s.end()) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * char 偏移的受保护片段 → rune 偏移 · 对齐源侧 {@code protectedSpansRune}（{@code splitter.go:141-168}）。
     *
     * <p>⚠️ <b>单趟前进</b>转换（源侧同样）：逐 span 调 {@code codePointCount(0, pos)} 是 O(n)
     * ⇒ k 个 span 就是 O(n·k)，2M 字符的文档会卡住。这里让 char/rune 两个游标只前进不回头。
     *
     * @param text 原文
     * @return 按 start 升序的 rune 区间
     */
    static List<RuneSpan> protectedSpansRune(String text) {
        List<KbProtectedSpans.Span> charSpans = KbProtectedSpans.find(text);
        if (charSpans.isEmpty()) {
            return List.of();
        }
        List<RuneSpan> out = new ArrayList<>(charSpans.size());
        int charIdx = 0;
        int runeIdx = 0;
        for (KbProtectedSpans.Span s : charSpans) {
            while (charIdx < s.start()) {
                charIdx += Character.charCount(text.codePointAt(charIdx));
                runeIdx++;
            }
            int startRune = runeIdx;
            while (charIdx < s.end()) {
                charIdx += Character.charCount(text.codePointAt(charIdx));
                runeIdx++;
            }
            out.add(new RuneSpan(startRune, runeIdx));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 装箱辅助 · 对齐 heuristic_splitter.go:231-313
    // ════════════════════════════════════════════════════════════════════

    /**
     * 切出一块并追加 · 对齐源侧 {@code appendChunk}（{@code :231-248}）。
     *
     * <p>纯空白切片跳过（边界聚集偶尔会产生）。Content 是原文「逐字切片」——
     * ⛔ 不 strip：位置不变式要求 {@code End-Start == rune 数(Content)}；
     * 供 embedding 用的 trim 发生在 {@link KbChunk#embeddingContent()}。
     *
     * @return 下一个 seq
     */
    private static int appendChunk(List<KbChunk> out, int[] runes, int start, int end, int seq) {
        if (end <= start) {
            return seq;
        }
        String raw = new String(runes, start, end - start);
        if (raw.strip().isEmpty()) {
            return seq;
        }
        out.add(new KbChunk(raw, "", seq, start, end));
        return seq + 1;
    }

    /**
     * 超长段交 legacy 切 · 对齐源侧 {@code appendOversizeBlock}（{@code :250-269}）。
     *
     * @return 下一个 seq
     */
    private static int appendOversizeBlock(List<KbChunk> out, int[] runes, int start, int end,
                                          KbSplitterConfig cfg, int seq) {
        if (end <= start) {
            return seq;
        }
        String subText = new String(runes, start, end - start);
        List<KbChunk> subs = KbTextSplitter.splitLegacyInner(subText, cfg);
        int next = seq;
        for (KbChunk s : subs) {
            out.add(new KbChunk(s.content(), "", next++, start + s.start(), start + s.end()));
        }
        return next;
    }

    /**
     * 重叠起点吸附结果。
     *
     * @param start        下一块起点（rune 偏移）
     * @param fromBoundary 是否吸附到语义边界
     * @param fromNewline  是否退而吸附到换行
     */
    record OverlapPick(int start, boolean fromBoundary, boolean fromNewline) {
    }

    /**
     * 算重叠起点 · 对齐源侧 {@code applyOverlapAligned}（{@code :271-313}）。
     *
     * <p>目标 {@code curEnd - overlap}，但：
     * <ol>
     *   <li>优先在 {@code [curEnd-2*overlap, curEnd)} 内找<b>最靠后</b>的语义边界</li>
     *   <li>找不到则从 target 往回找最近的换行（不越过窗口起点）</li>
     *   <li>都不行才用原始 target</li>
     * </ol>
     * {@code curEnd} 本身必定是边界，搜索时排除它（否则重叠为 0）。
     *
     * @param runes   全文 rune
     * @param curEnd  当前块结束位置
     * @param overlap 配置重叠
     * @param bounds  边界表
     * @return 吸附结果
     */
    static OverlapPick applyOverlapAligned(int[] runes, int curEnd, int overlap, List<Boundary> bounds) {
        if (overlap <= 0) {
            return new OverlapPick(curEnd, false, false);
        }
        int target = Math.max(curEnd - overlap, 0);
        int windowStart = Math.max(curEnd - 2 * overlap, 0);

        int bestBound = -1;
        for (Boundary b : bounds) {
            if (b.runeStart() >= windowStart && b.runeStart() < curEnd && b.runeStart() > bestBound) {
                bestBound = b.runeStart();
            }
        }
        if (bestBound >= 0) {
            return new OverlapPick(bestBound, true, false);
        }

        for (int i = target; i > windowStart && i < runes.length; i--) {
            if (runes[i] == '\n') {
                return new OverlapPick(i + 1, false, true);
            }
        }
        return new OverlapPick(target, false, false);
    }
}

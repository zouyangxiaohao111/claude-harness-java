package com.nexusai.kb.app.qa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 块文本合并工具 · 移植 WeKnora {@code internal/searchutil/chunkmerge.go} +
 * {@code textutil.go}。
 *
 * <h2>为什么不用位置坐标裁剪重叠</h2>
 * 分块器会做两件让「位置坐标 ≠ 文本长度」的事：
 * <ol>
 *   <li>被拆开的表格会<b>补写表头</b>，补进去的表头是零宽度的（start == end）——
 *       坐标表达不了它，正文却比 {@code endAt - startAt} 长；</li>
 *   <li>正文里可能保留 HTML 实体（{@code &#34;} / {@code &gt;}），字符数比原文区间长。</li>
 * </ol>
 * 按坐标裁剪在这两种数据上会静默错位、丢字或重复。⇒ 一律按<b>文本匹配</b>找重叠。
 *
 * <h2>⭐⭐ 长度一律用码点（rune），不用 {@link String#length()}</h2>
 * Go 的 {@code len([]rune(s))} 对应 Java 的 {@code codePointCount}。
 * {@code String.length()} 是 UTF-16 code unit —— 纯中文场景两者相等，
 * 一旦出现 emoji 或生僻字（代理对）就会差一倍，而<b>不会报错</b>。
 */
public final class KbMergeText {

    /** 参与匹配的最短重叠长度。太短（如表格分隔行 {@code |---|}）容易误匹配。 */
    static final int MIN_OVERLAP_RUNES = 12;

    /** 搜索窗口下限：位置信息缺失/为 0 时仍能检测一定范围内的真实重叠。 */
    static final int DEFAULT_SEARCH_SPAN = 400;

    private KbMergeText() {
    }

    /** 码点长度（rune 数）。 */
    public static int runeLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 拼接两个分隔符之间的内容（空串短路）。 */
    public static String join(String a, String separator, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return a + separator + b;
    }

    /**
     * 合并两段块正文（源侧 {@code JoinChunkContent}）。
     *
     * <p>顺序：完全包含 ⇒ 取长的；否则找「acc 后缀 == next 前缀」的最长重叠并去掉；
     * 找不到就加分隔符直接拼。
     *
     * <p><b>保守优先</b>：宁可留一点重复，也不静默丢掉用户编辑过的内容。
     */
    public static String joinChunkContent(String acc, String next, String separator) {
        if (acc == null || acc.isEmpty()) {
            return next == null ? "" : next;
        }
        if (next == null || next.isEmpty()) {
            return acc;
        }
        if (containsChunkContent(acc, next)) {
            return acc;
        }
        if (containsChunkContent(next, acc)) {
            return next;
        }

        int[] a = acc.codePoints().toArray();
        int[] b = next.codePoints().toArray();
        int maxOverlap = Math.min(a.length, b.length);
        // ⚠️ 设上限：可编辑块可能比解析产物大得多，无界后缀匹配会变成二次复杂度
        if (maxOverlap > DEFAULT_SEARCH_SPAN) {
            maxOverlap = DEFAULT_SEARCH_SPAN;
        }
        for (int overlap = maxOverlap; overlap >= MIN_OVERLAP_RUNES; overlap--) {
            if (codePointsEqual(a, a.length - overlap, b, 0, overlap)) {
                return acc + new String(b, overlap, b.length - overlap);
            }
        }
        return acc + separator + next;
    }

    /**
     * {@code contained} 是否被 {@code container} 安全包含。
     *
     * <p>⚠️ 极短的子串不认（{@code MIN_OVERLAP_RUNES}）—— 常见词与标点会造成误删。
     */
    public static boolean containsChunkContent(String container, String contained) {
        if (container == null || contained == null
            || container.isEmpty() || contained.isEmpty()) {
            return false;
        }
        if (container.equals(contained)) {
            return true;
        }
        return runeLen(contained) >= MIN_OVERLAP_RUNES && container.contains(contained);
    }

    /**
     * 按「位置估计的重叠量」拼接（源侧 {@code AppendWithOverlap}）。
     *
     * <p><b>⭐⭐ {@code positionOverlap <= 0} 时必须直接拼接，不做文本匹配</b>：
     * 两段在位置上严格相邻或不相交时，文本匹配会在 next 开头窗口内误命中 acc 后缀的
     * 真实重复（同一句话在文档里出现多次），把 next 开头整段当成「补写的表头」删掉 ——
     * 这是<b>不可逆的内容丢失</b>。源侧注释专门记了这个坑。
     *
     * @param positionOverlap 坐标估算的重叠量（仅用于界定搜索窗口大小）
     */
    public static String appendWithOverlap(String acc, String next, int positionOverlap) {
        if (acc == null || acc.isEmpty()) {
            return next == null ? "" : next;
        }
        if (next == null || next.isEmpty()) {
            return acc;
        }
        if (positionOverlap <= 0) {
            return acc + next;
        }

        int[] a = acc.codePoints().toArray();
        int[] b = next.codePoints().toArray();

        int maxK = Math.min(a.length, b.length);
        int cap = Math.max(positionOverlap * 3, DEFAULT_SEARCH_SPAN);
        if (maxK > cap) {
            maxK = cap;
        }
        // 重叠内容之前最多允许跳过多少前缀（即补写的表头这类合成文本）
        int headSlack = Math.max(positionOverlap * 2, 320);

        for (int k = maxK; k >= MIN_OVERLAP_RUNES; k--) {
            int pos = indexOf(b, a, a.length - k, k, headSlack);
            if (pos >= 0) {
                return acc + new String(b, pos + k, b.length - (pos + k));
            }
        }
        return acc + next;
    }

    /**
     * 坐标可信时按<b>精确重叠量</b>拼接（源侧 {@code AppendWithExactOverlap}）。
     *
     * <p>与 {@link #appendWithOverlap} 的区别是「不猜」：坐标可信时重叠量已知，
     * 不需要搜索 —— 搜索反而会把周期性文本（表格、日志）误判成重叠而裁掉真实内容。
     *
     * @return 校验不通过返回 {@code null}（调用方回退到文本匹配），⛔ 不返回半成品
     */
    public static String appendWithExactOverlap(String acc, String next, int overlap) {
        if (acc == null || acc.isEmpty()) {
            return next == null ? "" : next;
        }
        if (next == null || next.isEmpty()) {
            return acc;
        }
        if (overlap < 0) {
            return null;
        }
        if (overlap == 0) {
            return acc + next;
        }
        int[] a = acc.codePoints().toArray();
        int[] b = next.codePoints().toArray();
        if (overlap > a.length || overlap > b.length) {
            return null;
        }
        if (!codePointsEqual(a, a.length - overlap, b, 0, overlap)) {
            return null;
        }
        return acc + new String(b, overlap, b.length - overlap);
    }

    // ── 去重用的文本工具 ─────────────────────────────────────────────

    /** 去重签名：小写 + 空白折叠后的 MD5；空内容返回 {@code null}（= 不参与签名去重）。 */
    public static String contentSignature(String content) {
        String c = normalizeContent(content);
        if (c.isEmpty()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(c.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte x : digest) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必带算法，走到这里说明环境异常 ⇒ 显式失败比静默返回 null 好
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** 小写 + 去首尾空白 + 空白折叠成单空格。 */
    public static String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        /*
         * ⚠️ 源侧用 strings.Fields 按 Unicode 空白切分。Java 这里用正则 \s，
         * 但 \s 默认不匹配全角空格 U+3000 —— 中文正文里很常见，不处理会让
         * 「同样的内容」因全角空格产生两个不同签名，去重失效。
         */
        return content.toLowerCase().strip().replaceAll("[\\s\\u00A0\\u3000]+", " ");
    }

    /** 规范化后 {@code shortText} 是否为 {@code longText} 的子串（两者都必须已规范化）。 */
    public static boolean isContentContained(String normalizedShort, String normalizedLong) {
        if (normalizedShort == null || normalizedLong == null
            || normalizedShort.isEmpty() || normalizedLong.isEmpty()) {
            return false;
        }
        if (normalizedShort.length() > normalizedLong.length()) {
            return false;
        }
        return normalizedLong.contains(normalizedShort);
    }

    /**
     * 分词（去重比较专用）。
     *
     * <p><b>⚠️ 这是源侧 jieba 的近似替代，只用于「重叠率」比较，不用于检索。</b>
     * 本仓不引入中文分词依赖（避免为一个辅助判断背一个词典 + 启动开销）：
     * <ul>
     *   <li>含中文 ⇒ 取相邻两字（bigram）+ 连续的字母数字串；</li>
     *   <li>纯非中文 ⇒ 按非字母数字切分。</li>
     * </ul>
     * bigram 的粒度比 jieba 粗，重叠率普遍偏高 ⇒ 因此判「近似重复」时还必须配上
     * {@code shorterOnlyFraction}（见 {@link KbMergeStage}），不能只看重叠率。
     */
    public static Set<String> tokenizeSimple(String text) {
        if (text == null) {
            return Set.of();
        }
        String s = text.toLowerCase().strip();
        if (s.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        int[] cps = s.codePoints().toArray();

        // 连续字母数字串（中英混排时英文单词走这条）
        StringBuilder word = new StringBuilder();
        for (int cp : cps) {
            if (Character.isLetterOrDigit(cp) && !isHan(cp)) {
                word.appendCodePoint(cp);
            } else {
                addWordToken(out, word);
                word.setLength(0);
            }
        }
        addWordToken(out, word);

        // 中文 bigram（跳过标点与空白，避免跨句成词）
        int prevHan = -1;
        for (int cp : cps) {
            if (isHan(cp)) {
                if (prevHan >= 0) {
                    out.add(new String(Character.toChars(prevHan)) + new String(Character.toChars(cp)));
                }
                prevHan = cp;
            } else {
                prevHan = -1;
            }
        }
        return out;
    }

    /**
     * 重叠系数 = |交集| / |较小集合|（源侧 {@code TokenOverlapRatio}）。
     *
     * @return [0,1]；任一集合为空返回 0
     */
    public static double tokenOverlapRatio(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int inter = 0;
        for (String t : small) {
            if (large.contains(t)) {
                inter++;
            }
        }
        return (double) inter / small.size();
    }

    /**
     * {@code short} 里有多少比例的 token 不在 {@code long} 里。
     *
     * <p><b>为什么必须有这个判据</b>：重叠系数只问「短的有多大比例被长的覆盖」。
     * 两段共享大量样板文字（公文、模板）时重叠率轻易过 0.85，但各自还有自己的
     * <b>要求、数字、结论</b> —— 只看重叠率会把其中一条真实事实删掉。
     *
     * @return [0,1]；{@code short} 为空返回 1（空集不能证明被覆盖）
     */
    public static double shorterOnlyFraction(Set<String> shortSet, Set<String> longSet) {
        if (shortSet == null || shortSet.isEmpty()) {
            return 1;
        }
        int only = 0;
        for (String t : shortSet) {
            if (longSet == null || !longSet.contains(t)) {
                only++;
            }
        }
        return (double) only / shortSet.size();
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    /**
     * 把连续的字母数字串加入 token 集。
     *
     * <p>⭐⭐ <b>单个数字也要保留</b>：数字在文档里几乎总是事实
     * （「第 1 段」「88.9%」「3 座设施」）。丢掉单字符数字会让
     * 「第 1 段」与「第 2 段」的 token 集完全相同 ⇒ 被误判成近似重复而删掉一条，
     * 而那条里可能正是用户要的那个数字。
     *
     * <p>单字母仍然丢弃（「a」「x」这类当变量名/序号，信息量太低，
     * 全保留会让任何两段都被判重叠）。中文由 bigram 覆盖，不走这里。
     */
    private static void addWordToken(Set<String> out, StringBuilder word) {
        if (word.isEmpty()) {
            return;
        }
        String w = word.toString();
        boolean hasDigit = w.codePoints().anyMatch(Character::isDigit);
        if (w.length() > 1 || hasDigit) {
            out.add(w);
        }
    }

    private static boolean codePointsEqual(int[] a, int aFrom, int[] b, int bFrom, int len) {
        if (aFrom < 0 || bFrom < 0 || aFrom + len > a.length || bFrom + len > b.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (a[aFrom + i] != b[bFrom + i]) {
                return false;
            }
        }
        return true;
    }

    /** 在 {@code haystack} 里找 {@code needle} 的前 {@code len} 个码点，起始下标不超过 {@code maxStart}。 */
    private static int indexOf(int[] haystack, int[] needle, int needleFrom, int len, int maxStart) {
        if (len <= 0 || needleFrom < 0 || needleFrom + len > needle.length) {
            return -1;
        }
        if (len > haystack.length) {
            return -1;
        }
        int limit = haystack.length - len;
        if (maxStart < limit) {
            limit = maxStart;
        }
        for (int i = 0; i <= limit; i++) {
            if (codePointsEqual(haystack, i, needle, needleFrom, len)) {
                return i;
            }
        }
        return -1;
    }
}

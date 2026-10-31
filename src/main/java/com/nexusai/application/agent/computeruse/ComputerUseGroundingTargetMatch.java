package com.nexusai.application.agent.computeruse;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * 护栏 ① · <b>目标描述一致性</b>判定（grounding 模型回填的 {@code target} ↔ 请求的目标描述）。
 *
 * <h2>WHY 需要它（PoC 实测暴露的失效模式）</h2>
 * <p>PoC 真机实测：措辞含糊时视觉模型会<b>高置信度地指错到相邻的真实控件</b>
 * （avg 误差 76.7px，<b>静默错</b> —— 模型不报错、主模型不报错、点击落在别处）。
 * <p>⇒ 光靠「模型说它找到了」不足以判定它找对了 ⇒ 追加一条<b>可机器判定</b>的交叉检查：
 * <b>模型自报的目标名</b>必须与<b>请求的目标描述</b>指同一个东西。
 * 若两者差得远（模型说它找到的是「保存」而我们问的是「关闭」）⇒ 视为<b>没找到</b>（fail loud）。
 *
 * <h2>判定规则（简单 · 可解释 · 与实现逐行对应）</h2>
 * <p><b>归一化</b>：{@link #normalize(String)} —— 去掉所有空白与标点，保留字母 / 数字 / 汉字，
 * 并转小写（{@code "保存 按钮！" → "保存按钮"}）。
 * <p><b>同义词展开</b>：{@link #expandSynonyms(String)} —— 把常见 UI 控件名的
 * <b>中英同义写法</b>统一成同一个词（{@code "关闭" / "close" / 独立的 "x" ⇒ 都是 "close"}）。
 * <br><b>WHY 需要</b>：PoC 真机实测（2026-10-31）—— 请求用英文、模型回填中文
 * （"关闭"）时，<b>字符级相似度恒为 0</b> ⇒ 护栏会把「找对了」的定位误判为失败
 * （实测 7 次成功采样里被误拦 1 次）⇒ 跨语言同义词展开后，语义相同即放行。
 *
 * <p><b>按顺序判定</b>（先命中先返回）：
 * <ol>
 *   <li><b>规则 A · 相等</b>：归一化 + 展开后两串完全相同 ⇒ 通过；</li>
 *   <li><b>规则 0 · 互斥语义</b>：模型回填的串里出现了<b>请求里没有的另一个控件语义词</b>
 *       ⇒ <b>判错控件</b> ⇒ <b>失败</b>（例：请求「确定」/ 回填「确定取消」⇒ 引入 cancel 语义 ⇒ 拦下）；
 *       <br>⭐ 这一条是<b>互斥语义的守门人</b>（「关闭」vs「最小化」vs「最大化」必须仍能区分）；</li>
 *   <li><b>规则 B · 简称豁免（单向）</b>：模型回填的串是<b>请求串的子串</b>且长度 ≥
 *       {@value #MIN_ABBREVIATION_LEN} ⇒ 通过（典型：请求「记事本右上角的最小化按钮」、
 *       模型回填「最小化」⇒ 同一个目标的简称）；</li>
 *   <li><b>规则 C · 核心词覆盖</b>（2026-11-01 新增 · 修<b>假阴性</b>）—— 见下节；</li>
 *   <li><b>规则 D · 字符集合 Jaccard ≥ {@value #JACCARD_THRESHOLD}</b>
 *       （{@link #jaccard(String, String)}）⇒ 通过。</li>
 * </ol>
 * <p><b>不通过</b>（⇒ fail loud）：以上都不成立。
 *
 * <h2>规则 C · 核心词覆盖（2026-11-01 新增 · 修假阴性）</h2>
 * <p><b>WHY（实测缺陷）</b>：2026-11-01 真机端到端实测（原图组 10 次采样）里，
 * <b>3 次有效定位被护栏① 误拦</b>（{@code TARGET_MISMATCH}）—— <b>白丢 3 次有效定位</b>。
 * <br>典型实例：请求「<b>记事本窗口标题栏最右侧的关闭按钮</b>」vs 模型回填「<b>记事本窗口关闭按钮</b>」
 * ⇒ <b>语义完全正确</b>（模型只是<b>省略了方位修饰</b>），却被规则 B（子串不成立：请求中间多了
 * 「标题栏最右侧的」）与规则 D（Jaccard ≈ 0.55 &lt; 0.6）<b>双双拦下</b>。
 *
 * <p><b>规则内容（可解释 · 逐条）</b>：
 * <ol>
 *   <li><b>前提</b>：两边的<b>控件语义词集合完全相同且非空</b>
 *       （{@link #controlConcepts(String)}）⇒ <b>模型指的必然是同一个控件</b>
 *       （互斥语义已被规则 0 拦死 ⇒ 这里不可能是「关闭 vs 最小化」那种错控件）；</li>
 *   <li><b>核心串</b>：把归一化串里的<b>弱化词</b>剥掉（{@link #stripIgnorable(String)}）——
 *       弱化词 = <b>容器 / 区域</b>（窗口、界面、屏幕、标题栏、菜单栏、工具栏、状态栏、标签栏、
 *       面板、页面、对话框、弹窗、区域、边框）+ <b>方位 / 位置</b>（最右侧、最左侧、右上角、左上角、
 *       右下角、左下角、右侧、左侧、上方、下方、顶部、底部、中间、里面、内部、外部、附近、旁边 …）
 *       + <b>语气 / 动作</b>（点击、单击、双击、帮我、请、我想要、需要、麻烦 …）
 *       + <b>助词</b>（的、了、着）⇒ 这些词<b>只描述「在哪 / 怎么点」，不改变「是哪个控件」</b>；</li>
 *   <li><b>判定</b>：<b>核心串(回填) ⊆ 核心串(请求)</b>（<b>单向</b>：回填不得比请求多出核心内容）
 *       ⇒ 通过。</li>
 * </ol>
 *
 * <p><b>WHY 单向</b>：与规则 B 同款理由 —— 回填比请求<b>长</b>通常意味着它匹配到的是一个
 * <b>更大的复合元素</b>（请求「关闭」却回填「关闭所有标签页」）⇒ 那正是「指错相邻控件」的形态
 * ⇒ 宁可失败。
 *
 * <p><b>WHY 不下调 Jaccard 阈值</b>：阈值下调到 0 会让「完全不同语义的串」也蒙混过关
 * （例：请求「关闭」/ 回填「关闭所有标签页」字符集合高度重合）。规则 C 是<b>有解释的定向放宽</b>
 * （剥掉弱化词后比较核心），不是把闸门整体放松 ⇒ {@link #JACCARD_THRESHOLD} <b>保持 0.6 不变</b>。
 *
 * <p><b>与 CCB 的差异</b>：CCB 2.8.4 <b>没有</b> grounding 服务、也没有这条一致性检查
 * ⇒ 属<b>本仓增强</b>。
 */
public final class ComputerUseGroundingTargetMatch {

    /**
     * 字符集合 Jaccard 阈值 · {@value}。
     *
     * <p><b>取值依据</b>：请求「确定」/ 模型回填「确定取消」⇒ 回填引入 cancel 语义 ⇒ 由
     * <b>规则 0</b> 拦下（⛔ 不依赖本阈值）；本阈值负责兜住「<b>没有控件语义词</b>的领域专有名词」
     * （例：请求「提交订单」/ 回填「提交订单确认」）。
     * <br>取 0.6：留 0.1 的安全余量给「同义改写」；<b>2026-11-01 未下调</b>（假阴性改由规则 C 定向修）。
     */
    public static final double JACCARD_THRESHOLD = 0.6d;

    /**
     * 方向性豁免的最小长度 · {@value}。
     *
     * <p>模型回填的串至少要有这么多个有效字符才算「简称」⇒ 否则「的」「了」这种
     * 单字也会被当成子串豁免（等于没有检查）。
     */
    public static final int MIN_ABBREVIATION_LEN = 2;

    /**
     * 常见 UI 控件名的<b>中英同义组</b>（本仓增强 · CCB 无对应）。
     *
     * <p>每组的<b>第一个成员是规范词</b>；组内其它成员（中 / 英 / 独立符号）在归一化后被替换成规范词。
     * <p><b>替换规则</b>：纯 ASCII 字母的成员按<b>整词</b>匹配（{@code \b...\b}），
     * 避免 "x" 命中 "maximize" 这类子串误伤；中文成员按普通子串匹配。
     */
    private static final String[][] SYNONYM_GROUPS = {
            {"close", "关闭", "x"},
            {"minimize", "最小化"},
            {"maximize", "最大化"},
            {"restore", "还原"},
            {"save", "保存"},
            {"cancel", "取消"},
            {"confirm", "ok", "确定"},
            {"delete", "删除"},
            {"open", "打开"},
            {"search", "搜索"},
            {"settings", "设置"},
            {"refresh", "刷新"},
            {"back", "返回"},
            {"forward", "前进"},
            {"copy", "复制"},
            {"paste", "粘贴"},
            {"cut", "剪切"},
            {"undo", "撤销"},
            {"redo", "重做"},
            {"help", "帮助"},
            {"print", "打印"},
            {"new", "新建"},
            {"zoom", "放大"},
    };

    /**
     * <b>弱化词</b>表（规则 C 用）· 只描述「在哪 / 怎么点」，<b>不改变「是哪个控件」</b>。
     *
     * <p>⚠️ 边界：<b>不</b>包含任何<b>控件语义词</b>（关闭 / 最小化 / 最大化 / 保存 …）⇒
     * 剥弱化词<b>不会</b>把「关闭按钮」削成「最小化按钮」那种 ⇒ 互斥语义始终可区分。
     */
    private static final String[] IGNORABLE_WORDS = {
            // ── 容器 / 区域 ──
            "标题栏", "菜单栏", "工具栏", "状态栏", "标签栏",
            "对话框", "弹窗", "窗口", "界面", "屏幕", "页面", "面板", "区域", "边框",
            // ── 方位 / 位置 ──
            "最右侧", "最左侧", "最上面", "最下面",
            "右上角", "左上角", "右下角", "左下角",
            "右侧", "左侧", "上方", "下方", "顶部", "底部",
            "中间", "里面", "内部", "外部", "附近", "旁边",
            "上面", "下面", "左边", "右边",
            // ── 语气 / 动作 ──
            "点击", "单击", "双击", "按一下", "点一下", "按下",
            "帮我", "我想要", "需要", "麻烦", "请", "这个", "那个", "一个",
            // ── 助词 ──
            "的", "了", "着",
    };

    /**
     * 弱化词表（<b>按长度降序</b>）· 保证「最长匹配优先」。
     *
     * <p>WHY 要排序：先剥「最右侧」再剥「右侧」，否则「最右侧」会被削成「最」留下残渣。
     */
    private static final String[] IGNORABLE_WORDS_LONGEST_FIRST = buildLongestFirst();

    private ComputerUseGroundingTargetMatch() {
        throw new AssertionError("utility class");
    }

    /**
     * 判定「模型自报的目标」与「请求的目标描述」是否指同一个东西。
     *
     * @param requested 请求的目标描述（可空）
     * @param echoed    模型回填的目标名（可空）
     * @return true = 一致（放行）；false = 不一致（fail loud）
     */
    public static boolean matches(String requested, String echoed) {
        // ⚠️ 顺序要紧：同义词展开必须【先于】去空白 —— 整词匹配（\b）依赖词边界，
        //    而 normalize() 会把空格删掉（"the x button" → "thexbutton" ⇒ \b 失效）。
        String rRaw = expandSynonyms(requested);
        String eRaw = expandSynonyms(echoed);
        String r = normalize(rRaw);
        String e = normalize(eRaw);
        if (r.isEmpty() || e.isEmpty()) {
            // 有一边归一化后为空 ⇒ 无法判定 ⇒ 保守判不一致（fail loud，绝不放行）
            return false;
        }
        // ── 规则 A：完全相等 ──
        if (r.equals(e)) {
            return true;
        }
        // ── 规则 0：模型回填的「控件语义词」里出现了请求里没有的另一个 ⇒ 指错控件 ──
        //   例：请求「确定」/ 回填「确定取消」⇒ 回填引入了 cancel 语义 ⇒ 不是同一个控件。
        Set<String> requestGroups = controlConcepts(rRaw);
        Set<String> echoGroups = controlConcepts(eRaw);
        if (!requestGroups.isEmpty() && !echoGroups.isEmpty()) {
            Set<String> extra = new HashSet<>(echoGroups);
            extra.removeAll(requestGroups);
            if (!extra.isEmpty()) {
                return false;
            }
        }
        // ── 规则 B：方向性豁免（模型回填的是请求的子串 = 同一目标的简称 / 局部名）──
        if (e.length() >= MIN_ABBREVIATION_LEN && r.contains(e)) {
            return true;
        }
        // ── 规则 C：核心词覆盖（剥掉弱化词后比较核心控件词）──
        if (coreCovered(requestGroups, echoGroups, r, e)) {
            return true;
        }
        // ── 规则 D：字符集合 Jaccard ──
        return jaccard(r, e) >= JACCARD_THRESHOLD;
    }

    /**
     * 规则 C 的判定。
     *
     * <p><b>前提</b>：两边的控件语义词集合<b>完全相同且非空</b> ⇒ 模型指的必然是同一个控件
     * （互斥语义已在规则 0 拦下 ⇒ 这里只可能是「同一个控件的不同措辞」）。
     * <p><b>判定</b>：剥掉弱化词后的<b>核心串(回填) ⊆ 核心串(请求)</b>（单向）。
     *
     * @param requestGroups 请求命中的控件语义词
     * @param echoGroups    回填命中的控件语义词
     * @param r             归一化后的请求串
     * @param e             归一化后的回填串
     * @return true = 核心词覆盖 ⇒ 放行
     */
    private static boolean coreCovered(Set<String> requestGroups, Set<String> echoGroups,
                                       String r, String e) {
        if (requestGroups.isEmpty() || !requestGroups.equals(echoGroups)) {
            return false;
        }
        String rCore = stripIgnorable(r);
        String eCore = stripIgnorable(e);
        if (rCore.isEmpty() || eCore.isEmpty()) {
            return false;
        }
        return rCore.contains(eCore);
    }

    /**
     * 剥掉<b>弱化词</b>（容器 / 方位 / 语气 / 助词）· 规则 C 的核心串。
     *
     * <p>WHY 需要：请求常写成「记事本窗口<b>标题栏最右侧的</b>关闭按钮」，模型回填写成
     * 「记事本窗口关闭按钮」⇒ 两者<b>语义相同</b>但字符串不相等、也不是子串
     * ⇒ 剥掉只描述「在哪 / 怎么点」的词后，核心控件词就一致了。
     *
     * @param normalized 已归一化（去空白去标点）的串
     * @return 剥掉弱化词后的核心串
     */
    public static String stripIgnorable(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        int i = 0;
        while (i < normalized.length()) {
            int matched = 0;
            for (String word : IGNORABLE_WORDS_LONGEST_FIRST) {
                if (normalized.startsWith(word, i)) {
                    matched = word.length();
                    break;
                }
            }
            if (matched > 0) {
                i += matched;
                continue;
            }
            sb.append(normalized.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * 弱化词表按长度降序（最长匹配优先）。
     *
     * @return 排序后的弱化词数组
     */
    private static String[] buildLongestFirst() {
        String[] copy = IGNORABLE_WORDS.clone();
        Arrays.sort(copy, Comparator.comparingInt(String::length).reversed());
        return copy;
    }

    /**
     * 文本里命中的<b>控件语义词</b>集合（同义词组的规范词）。
     *
     * @param expanded 已展开（未去空白）的文本
     * @return 命中的规范词集合（可能为空）
     */
    public static Set<String> controlConcepts(String expanded) {
        Set<String> out = new HashSet<>();
        if (expanded == null || expanded.isEmpty()) {
            return out;
        }
        for (String[] group : SYNONYM_GROUPS) {
            String canonical = group[0];
            if (isAsciiLetters(canonical)) {
                if (expanded.matches("(?i).*\\b" + java.util.regex.Pattern.quote(canonical) + "\\b.*")) {
                    out.add(canonical);
                }
            } else if (expanded.contains(canonical)) {
                out.add(canonical);
            }
        }
        return out;
    }

    /**
     * 同义词展开：把常见 UI 控件名的中英同义写法统一成规范词（<b>保留空格</b>，供整词匹配）。
     *
     * @param raw 原文（可空；大小写不敏感）
     * @return 展开后的文本（已转小写）
     */
    public static String expandSynonyms(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        for (String[] group : SYNONYM_GROUPS) {
            String canonical = group[0];
            for (int i = 0; i < group.length; i++) {
                String member = group[i];
                if (member.isEmpty()) {
                    continue;
                }
                if (isAsciiLetters(member)) {
                    // 纯 ASCII 成员按【整词】替换（⛔ 否则 "x" 会命中 "maximize"）
                    s = s.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(member) + "\\b",
                            java.util.regex.Matcher.quoteReplacement(" " + canonical + " "));
                } else {
                    s = s.replace(member, " " + canonical + " ");
                }
            }
        }
        // 折叠空白（替换会留下多余空格）—— 保留空格是为了【整词匹配】的词边界
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 是否是纯 ASCII 字母（决定用整词匹配还是普通子串匹配）。 */
    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /**
     * 归一化：去空白 + 去标点 + 转小写（保留字母 / 数字 / 汉字）。
     *
     * @param text 原文（可空）
     * @return 归一化结果（null ⇒ 空串）
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 字符集合 Jaccard 相似度（两个已归一化串）。
     *
     * @param a 串 a
     * @param b 串 b
     * @return 交集 / 并集（0.0 – 1.0）
     */
    public static double jaccard(String a, String b) {
        Set<Character> sa = new HashSet<>();
        Set<Character> sb = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            sa.add(a.charAt(i));
        }
        for (int i = 0; i < b.length(); i++) {
            sb.add(b.charAt(i));
        }
        Set<Character> union = new HashSet<>(sa);
        union.addAll(sb);
        if (union.isEmpty()) {
            return 1.0d;
        }
        Set<Character> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        return (double) inter.size() / (double) union.size();
    }
}

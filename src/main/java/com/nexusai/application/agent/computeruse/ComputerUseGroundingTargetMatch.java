package com.nexusai.application.agent.computeruse;

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
 * <p><b>通过</b>（任一成立）：
 * <ol>
 *   <li><b>相等</b>：归一化 + 展开后两串完全相同；</li>
 *   <li><b>方向性豁免</b>：模型回填的串是<b>请求串的子串</b>且长度 ≥
 *       {@value #MIN_ABBREVIATION_LEN} —— 典型场景：请求「记事本右上角的最小化按钮」、
 *       模型回填「最小化」⇒ <b>同一个目标</b>的简称 ⇒ 放行；</li>
 *   <li><b>字符集合 Jaccard ≥ {@value #JACCARD_THRESHOLD}</b>（{@link #jaccard(String, String)}）。</li>
 * </ol>
 * <p><b>不通过</b>（⇒ fail loud）：以上都不成立。
 *
 * <h2>WHY 豁免是【单向】的（重要）</h2>
 * <p>规则 ② 只豁免「<b>模型回填得比请求更短</b>」的方向；反方向（模型回填得<b>更长</b>，
 * 如请求「确定」而模型回填「确定取消」）<b>不豁免</b> ⇒ 只能靠规则 ③ 的 Jaccard 过 ⇒
 * 「确定」/「确定取消」的 Jaccard = 2/4 = 0.5 &lt; {@value #JACCARD_THRESHOLD} ⇒ <b>拦下</b>。
 * <p>理由：模型回填得<b>更长</b>通常意味着它匹配到的是一个<b>更大的复合元素</b>
 * （例如请求「关闭」却匹配到「关闭所有标签页」），那正是「指错相邻控件」的形态 ⇒ 宁可失败。
 *
 * <p><b>与 CCB 的差异</b>：CCB 2.8.4 <b>没有</b> grounding 服务、也没有这条一致性检查
 * ⇒ 属<b>本仓增强</b>。
 */
public final class ComputerUseGroundingTargetMatch {

    /**
     * 字符集合 Jaccard 阈值 · {@value}。
     *
     * <p><b>取值依据</b>：请求「确定」/ 模型回填「确定取消」⇒ Jaccard = |{确,定}| / |{确,定,取,消}|
     * = 2/4 = 0.5 ⇒ 必须被拦下 ⇒ 阈值须 &gt; 0.5；而请求「保存按钮」/ 模型回填「保存按钮图标」
     * ⇒ 走规则 ②（子串豁免）不依赖本阈值 ⇒ 取 0.6（留 0.1 的安全余量给「同义改写」）。
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
        if (r.equals(e)) {
            return true;
        }
        // ── 规则 0：模型回填的「控件语义词」里出现了请求里没有的另一个 ⇒ 指错控件 ──
        //   例：请求「确定」/ 回填「确定取消」⇒ 回填引入了 cancel 语义 ⇒ 不是同一个控件。
        Set<String> requestGroups = controlConcepts(rRaw);
        Set<String> echoGroups = controlConcepts(eRaw);
        if (!requestGroups.isEmpty() && !echoGroups.isEmpty()) {
            Set<String> extra = new java.util.HashSet<>(echoGroups);
            extra.removeAll(requestGroups);
            if (!extra.isEmpty()) {
                return false;
            }
        }
        if (e.length() >= MIN_ABBREVIATION_LEN && r.contains(e)) {
            // 方向性豁免：模型回填的是请求的子串（同一目标的简称 / 局部名）
            return true;
        }
        return jaccard(r, e) >= JACCARD_THRESHOLD;
    }

    /**
     * 文本里命中的<b>控件语义词</b>集合（同义词组的规范词）。
     *
     * @param expanded 已展开（未去空白）的文本
     * @return 命中的规范词集合（可能为空）
     */
    public static Set<String> controlConcepts(String expanded) {
        Set<String> out = new java.util.HashSet<>();
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

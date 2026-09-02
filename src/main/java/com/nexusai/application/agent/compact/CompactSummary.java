package com.nexusai.application.agent.compact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压缩摘要解析器 · 对齐 CC prompt.ts formatCompactSummary() + getCompactUserSummaryMessage()
 *
 * <h2>CC 对齐</h2>
 * <p>对齐 CC prompt.ts:
 * <ul>
 *   <li>{@code formatCompactSummary(summary)} — 去除首个 &lt;analysis&gt; 草稿块（replace 无 /g），
 *       原位替换 &lt;summary&gt; 标签为 "Summary:\n"（prompt.ts:311-335）</li>
 *   <li>{@code getCompactUserSummaryMessage(summary, ...)} — 构建带前缀的摘要消息（prompt.ts:337-374）</li>
 * </ul>
 *
 * <h2>摘要格式</h2>
 * <p>LLM 返回的原始格式：
 * <pre>
 * &lt;analysis&gt;
 * [思考过程草稿，用于提升摘要质量，但无信息价值]
 * &lt;/analysis&gt;
 * &lt;summary&gt;
 * 1. Primary Request and Intent:
 *    ...
 * 2. Key Technical Concepts:
 *    ...
 * &lt;/summary&gt;
 * </pre>
 *
 * <p>格式化后去除首个 &lt;analysis&gt; 块（CC replace 无 /g 仅剥首个），&lt;summary&gt; 标签原位
 * 替换为 "Summary:\n"（保留标签外文本；空内容仍替换，对齐 CC prompt.ts:322-326）。
 */
public class CompactSummary {

    /** 去除 &lt;analysis&gt;...&lt;/analysis&gt; 的正则（对齐 CC prompt.ts:316-319） */
    private static final Pattern ANALYSIS_PATTERN = Pattern.compile(
        "<analysis>[\\s\\S]*?</analysis>", Pattern.DOTALL);

    /** 提取 &lt;summary&gt;...&lt;/summary&gt; 的正则（对齐 CC prompt.ts:322-323） */
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
        "<summary>([\\s\\S]*?)</summary>", Pattern.DOTALL);

    /** 连续换行压缩正则（对齐 CC prompt.ts:332） */
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n\\n+");

    /** 摘要前缀（对齐 CC prompt.ts:345） */
    private static final String SUMMARY_PREFIX =
        "This session is being continued from a previous conversation that ran out of context. "
        + "The summary below covers the earlier portion of the conversation.\n\n";

    /** 继续提示（对齐 CC prompt.ts:359） */
    private static final String CONTINUATION_PROMPT =
        "Continue the conversation from where it left off without asking the user any further questions. "
        + "Resume directly — do not acknowledge the summary, do not recap what was happening, "
        + "do not preface with \"I'll continue\" or similar. "
        + "Pick up the last task as if the break never happened.";

    /**
     * [SM-15] 自主续跑段 · CC prompt.ts:365-367（suppressFollowUp 分支内
     * {@code (feature('PROACTIVE') || feature('KAIROS')) && proactiveModule?.isProactiveActive()}
     * 时追加，前缀 `\n\n`）。
     *
     * <p><b>proactive 状态源登记</b>：CC 基线 checkout 无 {@code src/proactive/} 目录 →
     * {@code proactiveModule} 恒 null → {@code isProactiveActive()} 恒 falsy → 本段在 CC
     * 基线中不可达（dead branch）。Java 等价：{@link #setProactiveContinuationGate} 默认
     * {@code () -> false}（proactive 模块不存在）；KAIROS 部署标志（nexusai.feature.kairos /
     * NEXUSAI_FEATURE_KAIROS，NEW-6 同源）为未来接线位 —— 即便 KAIROS 开启，AND 恒 false
     * 本段仍不可达，与 CC 基线可观测行为一致。
     */
    private static final String PROACTIVE_CONTINUATION_PROMPT =
        "You are running in autonomous/proactive mode. This is NOT a first wake-up — you were "
        + "already working autonomously before compaction. Continue your work loop: pick up where "
        + "you left off based on the summary above. Do not greet the user or ask what to work on.";

    /**
     * [SM-15] 续跑段门控 · CC original: {@code (feature('PROACTIVE') || feature('KAIROS')) &&
     * proactiveModule?.isProactiveActive()}（prompt.ts:361-363）。默认 false（proactive 模块
     * 不存在 + feature 默认关）；测试/未来接线经 {@link #setProactiveContinuationGate} 注入。
     */
    private static volatile java.util.function.BooleanSupplier proactiveContinuationGate = () -> false;

    /** [SM-15] 注入续跑段门控（null → 默认 false）。 */
    public static void setProactiveContinuationGate(java.util.function.BooleanSupplier gate) {
        if (gate != null) {
            proactiveContinuationGate = gate;
        }
    }

    /**
     * 格式化压缩摘要 · 对齐 CC prompt.ts:311-335 formatCompactSummary()
     *
     * <ol>
     *   <li>去除首个 &lt;analysis&gt;...&lt;/analysis&gt; 块（思考草稿，CC replace 无 /g 仅剥首个）</li>
     *   <li>将首个 &lt;summary&gt;...&lt;/summary&gt; 原位替换为 "Summary:\n{content}"（保留标签外文本，
     *       空内容仍替换为 "Summary:\n"）</li>
     *   <li>合并连续换行</li>
     * </ol>
     *
     * @param rawSummary LLM 返回的原始摘要文本
     * @return 格式化后的摘要（去除草稿 + 保留结构化内容）
     */
    public static String format(String rawSummary) {
        if (rawSummary == null || rawSummary.isBlank()) {
            return "";
        }

        String formatted = rawSummary;

        // 对齐 CC prompt.ts:316-319 — 去除首个 analysis 草稿块（CC replace() 无 /g → 只替换首个
        //   <analysis> 块；Java 原 replaceAll 全剥 → 改 replaceFirst 仅剥首个，多块边缘形态仅剥首个）
        formatted = ANALYSIS_PATTERN.matcher(formatted).replaceFirst("");

        // 对齐 CC prompt.ts:322-326 — 提取 summary 内容并原位替换
        Matcher summaryMatcher = SUMMARY_PATTERN.matcher(formatted);
        if (summaryMatcher.find()) {
            String content = summaryMatcher.group(1);
            // [COMPACT-03/04] CC prompt.ts:324-325：`const content = summaryMatch[1] || ''` +
            //   `replace(/<summary>[\s\S]*?<\/summary>/, `Summary:\n${content.trim()}`)`——
            //   · 空内容仍替换为 "Summary:\n"（原 isBlank 守卫已移除，对齐 CC：空标签不再残留）；
            //   · 原位替换保留 <summary> 标签外 preamble/trailing 文本（原整串赋值丢弃外围，
            //     改 substring 窗口保留）。
            //   用 substring(start/end) 窗口而非 Matcher.replaceFirst(replacement)：后者 replacement
            //   含 $/\ 会被当组引用/转义（LLM 摘要内容可能含 $ 代码）；substring 天然规避，与 CC JS
            //   String.replace 的 $&/$'/$$ 展开语义在病理输入下存在微偏，已登记已知微偏不追求复刻。
            formatted = formatted.substring(0, summaryMatcher.start())
                + "Summary:\n" + (content != null ? content.trim() : "")
                + formatted.substring(summaryMatcher.end());
        }

        // 对齐 CC prompt.ts:332 — 压缩多余换行
        formatted = MULTI_NEWLINE.matcher(formatted).replaceAll("\n\n");

        return formatted.trim();
    }

    /**
     * 构建用户摘要消息 · 对齐 CC prompt.ts:337-374 getCompactUserSummaryMessage()
     *
     * <p>在格式化摘要前添加前缀，可选附带 transcript 路径和继续提示。
     *
     * @param rawSummary             LLM 原始摘要
     * @param transcriptPath         完整 transcript 文件路径（可选，null 不添加）
     * @param suppressFollowUp       是否抑制后续提问（对齐 CC suppressFollowUpQuestions）
     * @param recentMessagesPreserved 是否保留了最近的消息（对齐 CC recentMessagesPreserved）
     * @return 完整的用户摘要消息文本
     */
    public static String buildUserMessage(
        String rawSummary,
        String transcriptPath,
        boolean suppressFollowUp,
        boolean recentMessagesPreserved) {

        String formatted = format(rawSummary);
        StringBuilder sb = new StringBuilder();
        sb.append(SUMMARY_PREFIX);
        sb.append(formatted);

        if (transcriptPath != null && !transcriptPath.isBlank()) {
            sb.append("\n\nIf you need specific details from before compaction "
                + "(like exact code snippets, error messages, or content you generated), "
                + "read the full transcript at: ").append(transcriptPath);
        }

        if (recentMessagesPreserved) {
            sb.append("\n\nRecent messages are preserved verbatim.");
        }

        if (suppressFollowUp) {
            // [SM-15] 前导单 \n 对齐 CC prompt.ts:358（`${baseSummary}\nContinue the conversation…`）——
            //   旧实现 "\n\n" 多一个换行（DRIFT-22 字节差异一部分）。
            sb.append("\n").append(CONTINUATION_PROMPT);
            // [SM-15] PROACTIVE/KAIROS 续跑段（DRIFT-22）· CC prompt.ts:361-368：
            //   `continuation += "\n\nYou are running in autonomous/proactive mode. …"`
            //   Java proactive 模块不存在 → 门控默认 false（状态源登记见字段 javadoc）。
            if (proactiveContinuationGate.getAsBoolean()) {
                sb.append("\n\n").append(PROACTIVE_CONTINUATION_PROMPT);
            }
        }

        return sb.toString();
    }

}

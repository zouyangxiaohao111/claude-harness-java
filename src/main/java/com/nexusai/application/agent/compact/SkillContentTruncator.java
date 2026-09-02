package com.nexusai.application.agent.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skill 内容截断工具 · 对齐 CC services/compact/compact.ts truncateToTokens
 * + SKILL_TRUNCATION_MARKER + services/tokenEstimation.ts roughTokenCountEstimation.
 *
 * <p>三个原子能力（CC 真源，非注释转述）：
 * <ol>
 *   <li>{@link #SKILL_TRUNCATION_MARKER} — CC original: SKILL_TRUNCATION_MARKER
 *       (compact.ts:1657-1658)，len=100，双换行前导，提示模型可对 skill 路径 Read 取全文</li>
 *   <li>{@link #roughTokenCountEstimation(String)} — CC original: roughTokenCountEstimation
 *       (tokenEstimation.ts:203-208)，默认 bytesPerToken=4，{@code Math.round(content.length / bytesPerToken)}。</li>
 *   <li>{@link #truncateToTokens(String, int)} — CC original: truncateToTokens
 *       (compact.ts:1666-1672)：不超预算原样返回；超预算保头部 + marker，结果恒 {@code <= maxTokens} token。</li>
 * </ol>
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: skill 文件可能很大（CC 注释 verify=18.7KB、
 * claude-api=20.1KB），压缩后重注入必须截断到每 skill 5000 token 预算，保留文件头部
 * （setup/usage 指引通常在最前），并用 marker 告知模型需要全文时可 Read。
 *
 * <p><b>Math.round 而非整数除法</b>: 忠实 CC {@code Math.round(content.length / bytesPerToken)}
 * 必须 {@code Math.round(len/4.0)}；整数除法 {@code len/4} 会 floor 截断（len=6→1 vs CC 2），
 * 语义偏移。本类为 compact 模块唯一忠实原语（TokenEstimator.calculateToolResultTokens
 * 用整数除法且零调用方，CS-24 余留另议）。
 *
 * <p><b>无 null 防御（P3-37 · DEL-07）</b>: CC {@code roughTokenCountEstimation}（tokenEstimation.ts:203-208）
 * 对 {@code content.length} 遇 null 会抛异常，本类忠实对齐 —— 不提供 {@code null→0 / null→null}
 * 扩展。调用方保证非 null（PostCompactAttachmentRestorer:239 空内容跳过 / :455 空串兜底）。
 */
public final class SkillContentTruncator {

    private static final Logger log = LoggerFactory.getLogger(SkillContentTruncator.class);

    /**
     * 截断占位标记（CC original: SKILL_TRUNCATION_MARKER
     * Open-ClaudeCode/src/services/compact/compact.ts:1657-1658）。
     *
     * <p>len=100（node -e 实测，双 {@code \n} 前导）。提示模型 skill 内容被截断，
     * 需要全文时对 skill 路径执行 Read。
     */
    public static final String SKILL_TRUNCATION_MARKER =
        "\n\n[... skill content truncated for compaction; use Read on the skill path if you need the full text]";

    private SkillContentTruncator() {
        // 静态工具类不可实例化（对应 CC module-scope 函数）
    }

    /**
     * 粗略 token 估算 · CC original: roughTokenCountEstimation
     * (Open-ClaudeCode/src/services/tokenEstimation.ts:203-208)。
     *
     * <p>{@code bytesPerToken} 默认 4，{@code Math.round(content.length / bytesPerToken)}。
     * 忠实实现必须 {@code Math.round(len / 4.0)}（浮点除法），禁止整数除法 {@code len / 4}
     * 否则 0.5 边界向下取整。
     *
     * @param content 待估算内容
     * @return 估算 token 数
     */
    public static int roughTokenCountEstimation(String content) {
        return Math.round(content.length() / 4.0f);
    }

    /**
     * 截断到大致 maxTokens，保留头部 · CC original: truncateToTokens
     * (Open-ClaudeCode/src/services/compact/compact.ts:1666-1672)。
     *
     * <p>算法（逐行对齐 CC）：
     * <ol>
     *   <li>{@code roughTokenCountEstimation(content) <= maxTokens} → 原样返回（CC:1667-1669，
     *       不超预算不截断，{@code <=} 是 CC 语义不是 {@code <}）</li>
     *   <li>{@code charBudget = maxTokens * 4 - SKILL_TRUNCATION_MARKER.length}（CC:1670，
     *       roughTokenCountEstimation 默认 4 chars/token）</li>
     *   <li>{@code return content.slice(0, charBudget) + SKILL_TRUNCATION_MARKER}（CC:1671，
     *       保头部 + marker，结果恒 {@code <= maxTokens} token）</li>
     * </ol>
     *
     * <p>Java 忠实映射 JS {@code slice(0, charBudget)}：JS slice 对越界索引的钳制规则为
     * {@code charBudget >= 0 → min(charBudget, length)}；{@code charBudget < 0 →
     * max(length + charBudget, 0)}（负数从尾部计数）。Java {@code substring} 无此钳制，
     * 必须显式实现。生产 maxTokens=5000 恒 charBudget=19900，负数分支为纯防御
     * （CLAUDE.md 规则二：不做猜测性实现，此钳制为接口完整性所需，非过度设计）。
     *
     * @param content   待截断内容
     * @param maxTokens token 预算上限
     * @return 截断后的内容
     */
    public static String truncateToTokens(String content, int maxTokens) {
        int estimatedTokens = roughTokenCountEstimation(content);
        if (estimatedTokens <= maxTokens) {
            return content;
        }
        int charBudget = maxTokens * 4 - SKILL_TRUNCATION_MARKER.length();
        // 忠实映射 JS slice(0, charBudget) 钳制: 非负 → min(charBudget, length);
        // 负 → max(length + charBudget, 0)（负数从尾部计数）。
        int end = charBudget >= 0
            ? Math.min(charBudget, content.length())
            : Math.max(content.length() + charBudget, 0);
        String truncated = content.substring(0, end) + SKILL_TRUNCATION_MARKER;
        if (log.isDebugEnabled()) {
            log.debug("[SkillContentTruncator] 估算 {} tokens > 预算 {}，截断为 {} 字符 + marker",
                estimatedTokens, maxTokens, end);
        }
        return truncated;
    }
}

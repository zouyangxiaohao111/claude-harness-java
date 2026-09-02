package com.nexusai.application.agent.permission.classifier;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token 估算器 · 对齐 CC {@code yoloClassifier.ts} tokenCountWithEstimation 语义
 * （{@code utils/tokens.ts:226-261} + {@code services/tokenEstimation.ts:203-208}）。
 *
 * <h2>[S10] 对齐说明（O55：由弱删除候选转保留）</h2>
 * <ul>
 *   <li>{@link #estimateTokens} ← CC {@code roughTokenCountEstimation(content, 4)}
 *       （tokenEstimation.ts:203-208 {@code Math.round(content.length / bytesPerToken)}，
 *       bytesPerToken 默认 4）—— 旧 Java 实现 len/3 + {@code Math.max(1, …)} 为 Java 独有
 *       估算形态，已按 CC /4 + round 对齐。</li>
 *   <li>{@link #detectPromptTooLong} / {@link #parsePromptTooLongTokenCounts} ← CC
 *       {@code detectPromptTooLong}（yoloClassifier.ts:1463-1471）+ {@code parsePromptTooLongTokenCounts}
 *       （services/api/errors.ts:85-96）—— 解析 API 400 "prompt is too long: N tokens > M maximum"
 *       错误文本，判定 transcript 超出分类器上下文窗口（确定性错误，重试无效）。</li>
 * </ul>
 *
 * <p>[S06 重构] {@link #wouldOverflow} 已删除（⊕-04 / OPD-WF6-02）：CC 无 128000 预检、
 * 无 90% 阈值常量（依赖 API 'prompt is too long' 错误承载），超长检测能力统一到
 * {@link #detectPromptTooLong}，避免两套并存。</p>
 *
 * <p>零外部依赖：纯函数，方便单测。
 *
 * @see YoloClassifier
 * @see YoloClassifierImpl
 */
@Component
public class YoloTokenEstimator {

    /**
     * CC parsePromptTooLongTokenCounts 正则（errors.ts:90）：
     * {@code /prompt is too long[^0-9]*(\d+)\s*tokens?\s*>\s*(\d+)/i} —— 宽松匹配
     * SDK 前缀/JSON 包裹/大小写差异（Vertex），取 actual > limit 两个数字。
     */
    private static final Pattern PROMPT_TOO_LONG_PATTERN = Pattern.compile(
            "prompt is too long[^0-9]*?(\\d+)\\s*tokens?\\s*>\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 估算文本的 token 数量 · 对齐 CC {@code roughTokenCountEstimation}
     * （{@code tokenEstimation.ts:203-208}，CC 原名 {@code roughTokenCountEstimation}）。
     *
     * <p>CC 语义：{@code Math.round(content.length / bytesPerToken)}，bytesPerToken 默认 4
     * （英文约 4 字符/token）。
     *
     * @param text 输入文本（可为 null）
     * @return 估算的 token 数（null/空 → 0；CC 短文本可返回 0，如 1 字符 → round(0.25)=0）
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.round(text.length() / 4.0);
    }

    /**
     * prompt-too-long 错误解析结果 · 对齐 CC {@code parsePromptTooLongTokenCounts} 返回
     * {@code { actualTokens, limitTokens }}（errors.ts:85-96）。
     *
     * @param actualTokens 错误文本报告的当前 token 数（N；不可解析 → null）
     * @param limitTokens  错误文本报告的上限（M；不可解析 → null）
     */
    public record PromptTooLongTokenCounts(Integer actualTokens, Integer limitTokens) {}

    /**
     * 解析 prompt-too-long API 错误文本 · 对齐 CC {@code parsePromptTooLongTokenCounts}
     * （{@code services/api/errors.ts:85-96}，CC 原名 {@code parsePromptTooLongTokenCounts}）。
     *
     * <p>CC 正则 {@code /prompt is too long[^0-9]*(\d+)\s*tokens?\s*>\s*(\d+)/i}：
     * 匹配形如 "prompt is too long: 137500 tokens &gt; 135000 maximum" 的原始错误串
     * （可能带 SDK 前缀/JSON 包裹/不同大小写，故意宽松）。
     *
     * @param rawMessage 原始错误消息（可为 null）
     * @return actual/limit token 数；未匹配 → 两字段均为 null（CC 语义）
     */
    public PromptTooLongTokenCounts parsePromptTooLongTokenCounts(String rawMessage) {
        if (rawMessage == null) {
            return new PromptTooLongTokenCounts(null, null);
        }
        Matcher m = PROMPT_TOO_LONG_PATTERN.matcher(rawMessage);
        if (!m.find()) {
            return new PromptTooLongTokenCounts(null, null);
        }
        return new PromptTooLongTokenCounts(
                Integer.valueOf(m.group(1)),
                Integer.valueOf(m.group(2)));
    }

    /**
     * 判定 API 错误是否为 transcript 超长 · 对齐 CC {@code detectPromptTooLong}
     * （{@code yoloClassifier.ts:1463-1471}，CC 原名 {@code detectPromptTooLong}）。
     *
     * <p>CC 语义：非 Error → undefined；消息不含 "prompt is too long"（大小写不敏感）
     * → undefined；否则返回 {@link #parsePromptTooLongTokenCounts} 解析结果。
     * 这类错误是确定性的（相同 transcript → 相同错误），重试无意义 —— 消费方据此
     * 回退 prompting 而非 iron-gate deny（permissions.ts:818-842）。
     *
     * @param errorMessage 异常消息（可为 null）
     * @return 解析结果；非超长错误 → null（CC undefined）
     */
    public PromptTooLongTokenCounts detectPromptTooLong(String errorMessage) {
        if (errorMessage == null
                || !errorMessage.toLowerCase().contains("prompt is too long")) {
            return null;
        }
        return parsePromptTooLongTokenCounts(errorMessage);
    }
}

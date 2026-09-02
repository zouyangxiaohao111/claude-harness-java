package com.nexusai.infra.util;

import java.util.List;

/**
 * BashClassifierPermission · 对齐 CC utils/permissions/bashClassifier.ts.
 *
 * <p>L1 语义: Bash 权限 classifier (ant-only) — describe prompt rules + match/allow/deny classifications。
 * <p>CC 注释: 该模块是 ant-only stub,external builds 始终 disabled。
 * Java 等价: extractPromptDescription 对齐 CC checked-in stub（bashClassifier.ts:14-18）恒返 null;
 * createPromptRuleContent 对齐 CC 真实代码（bashClassifier.ts:20-22）构造 "prompt: description" 字符串。
 *
 * <p>2 静态方法 (extractPromptDescription stub + createPromptRuleContent) + ClassifierResult record.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + ClassifierResult record + ClassifierBehavior enum + 常量 PROMPT_PREFIX</li>
 *   <li><b>A2 Golden Trace</b>: extractPromptDescription(anything)→null（CC :14-18 stub 恒 null）;createPromptRuleContent("foo")→"prompt: foo";createPromptRuleContent(null)→"prompt: "</li>
 *   <li><b>A3 纯函数</b>: stateless;同 input→同 output</li>
 *   <li><b>A4 边界</b>: extractPromptDescription 恒 null（stub）;createPromptRuleContent(null)→"prompt: "</li>
 *   <li><b>A5 业务场景</b>: createPromptRuleContent 构造 prompt: 前缀规则（ANT-ONLY，extractPromptDescription stub 恒 null）</li>
 * </ul>
 *
 * <p>L3 升级: TS object literal → Java record;
 * TS const string → Java static final String;
 * TS template literal → Java string concat.
 */
public final class BashClassifierPermission {

    public static final String PROMPT_PREFIX = "prompt:";

    public enum ClassifierBehavior { deny, ask, allow }

    public record ClassifierResult(
        boolean matches,
        String matchedDescription,
        String confidence,
        String reason) {

        public static ClassifierResult notMatched(String reason) {
            return new ClassifierResult(false, null, "high", reason);
        }
    }

    private BashClassifierPermission() {}

    /**
     * Extract the description from a rule's content · CC original: {@code extractPromptDescription}
     * （bashClassifier.ts:14-18）。
     *
     * <p>CC checked-in stub 恒 {@code return null}（ANT-ONLY，外部构建分类器权限特性关闭），
     * Java 对齐 stub —— 不再真提取 {@code prompt:} 前缀。
     *
     * @param ruleContent 规则内容（可 null，stub 忽略）
     * @return 恒 {@code null}（对齐 CC {@code return null}）
     */
    public static String extractPromptDescription(String ruleContent) {
        return null;
    }

    /**
     * Construct a "prompt:" prefixed rule content from a description.
     */
    public static String createPromptRuleContent(String description) {
        return PROMPT_PREFIX + " " + (description == null ? "" : description.trim());
    }
}

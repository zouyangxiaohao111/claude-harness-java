package com.nexusai.application.agent.recovery.query;

/**
 * 查询恢复常量 · 对齐 CC query.ts。
 *
 * <h2>CC 对齐对照</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC 源码</th><th>行号</th><th>值</th></tr>
 *   <tr><td>MAX_OUTPUT_TOKENS_RECOVERY_LIMIT</td><td>query.ts</td><td>164</td><td>3</td></tr>
 * </table>
 *
 * <p><b>C3 拆分（ER-IMP-B-DOC）</b>：自旧 {@code ErrorRecoveryConstants} 迁出，按 CC 文件结构
 * （query.ts）归类。
 *
 * <p>不可实例化。
 */
public final class QueryConstants {

    private QueryConstants() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // max_tokens 恢复次数 · 对齐 CC query.ts:164
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: MAX_OUTPUT_TOKENS_RECOVERY_LIMIT (query.ts:164) = 3。
     *
     * <p>max_tokens 恢复最大次数（升级 1 次 + 续写 3 次 = 最多 4 次尝试）。
     * CC query.ts:1223 {@code maxOutputTokensRecoveryCount < MAX_OUTPUT_TOKENS_RECOVERY_LIMIT}
     * 门控。
     */
    public static final int MAX_OUTPUT_TOKENS_RECOVERY_LIMIT = 3;
}

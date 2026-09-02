package com.nexusai.application.agent.recovery.context;

/**
 * 上下文 token 常量 · 对齐 CC utils/context.ts。
 *
 * <h2>CC 对齐对照</h2>
 * <table>
 *   <tr><th>本字段</th><th>CC 源码</th><th>行号</th><th>值</th></tr>
 *   <tr><td>ESCALATED_MAX_TOKENS</td><td>utils/context.ts</td><td>25</td><td>64_000</td></tr>
 *   <tr><td>CAPPED_DEFAULT_MAX_TOKENS</td><td>utils/context.ts</td><td>24</td><td>8_000</td></tr>
 * </table>
 *
 * <p><b>C3 拆分（ER-IMP-B-DOC）</b>：自旧 {@code ErrorRecoveryConstants} 迁出，按 CC 文件结构
 * （utils/context.ts）归类。
 *
 * <p>不可实例化。
 */
public final class ContextConstants {

    private ContextConstants() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // Token 升级 · 对齐 CC utils/context.ts:24-25
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: ESCALATED_MAX_TOKENS (utils/context.ts:25) = 64_000。
     *
     * <p>max_tokens 恢复链升级目标值（8K→64K）。CC query.ts:1199-1205 + utils/context.ts:25。
     */
    public static final int ESCALATED_MAX_TOKENS = 64_000;

    /**
     * CC original: CAPPED_DEFAULT_MAX_TOKENS (utils/context.ts:24) = 8_000。
     *
     * <p>默认 max_tokens 值（cap 上限）。CC utils/context.ts:24 CAPPED_DEFAULT_MAX_TOKENS 等价。
     */
    public static final int CAPPED_DEFAULT_MAX_TOKENS = 8_000;
}

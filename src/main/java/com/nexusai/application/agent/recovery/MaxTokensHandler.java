package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.recovery.context.ContextConstants;
import com.nexusai.application.agent.recovery.query.QueryConstants;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * max_tokens 截断恢复处理器 · 对齐 CC query.ts:1188-1256。
 *
 * <h2>恢复路径（CC query.ts:1188-1256）</h2>
 * <ol>
 *   <li><b>首次截断 + gate 开启</b> — 升级 max_tokens 8K→64K，<b>不追加消息</b> ·
 *       CC query.ts:1199-1221（gate {@code tengu_otk_slot_v1} 默认关闭）</li>
 *   <li><b>已升级 / gate 关闭后再截断</b> — 追加 CONTINUATION_PROMPT 作为 user 消息续写 ·
 *       CC query.ts:1223-1252</li>
 *   <li><b>续写 ≤3 次后</b> — 耗尽，标记不可恢复 · CC query.ts:164 MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3</li>
 * </ol>
 *
 * <p><b>关键行为</b>：首次升级不追加消息（CC 行为），续写才追加消息。Handler 本身不修改
 * 消息列表，只返回 RecoveryResult 指示调用方如何操作。
 *
 * <p><b>[IMP-15 DRIFT-11] 64k 升级 gate</b>：CC 的升级受 {@code tengu_otk_slot_v1}
 * growthbook flag 门控（3P 默认 false）。gate 关闭时跳过升级、直走多轮续写（≤3）；
 * gate 开启且未升级且无 {@code settings.maxOutputTokens} override（F1 迁移自
 * {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} env，CC query.ts:1199-1203 同读 env；E4 发现 C）
 * 时才升级。
 */
public class MaxTokensHandler {

    private static final Logger log = LoggerFactory.getLogger(MaxTokensHandler.class);

    /**
     * 64k 升级 gate · CC original: {@code tengu_otk_slot_v1}（query.ts:1195-1198，默认 false）。
     *
     * <p>生产默认经 {@link AnthropicSdkProvider#isMaxTokensCapEnabled()}（系统属性
     * {@code nexusai.feature.tengu-otk-slot-v1}）注入；测试可显式传入。
     */
    private final boolean tenguOtkSlotV1Enabled;

    /**
     * settings.maxOutputTokens 读取器 · 单源解析（F1 迁移自 {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} env）。
     * 可注入便于测试（默认 {@link AnthropicSdkProvider#readSettingsMaxOutputTokens()}）。
     *
     * <p><b>WHY（可测试性）</b>: {@code canEscalate} 判定依赖 settings.maxOutputTokens 为 null
     * （未配置 → 64k 升级 gate 可达）。无 Spring 上下文时 staticSettingsMapper 未桥接 → null →
     * 等价"未配置 override"（测试天然可升级）。原 envProvider（读 CLAUDE_CODE_MAX_OUTPUT_TOKENS env，
     * 因 Java 无法直接改 System.getenv）随 F1 迁移已删除，本 Supplier 承载同款可注入 seam
     * （E4 发现 C：env 残留改 settings 单源）。
     */
    private java.util.function.Supplier<Integer> settingsOverrideProvider =
        AnthropicSdkProvider::readSettingsMaxOutputTokens;

    /** 默认构造器 · gate 从系统属性读取（对齐 CC growthbook flag 默认 false）。 */
    public MaxTokensHandler() {
        this(AnthropicSdkProvider.isMaxTokensCapEnabled());
    }

    /**
     * 显式 gate 构造器（测试注入用）。
     *
     * @param tenguOtkSlotV1Enabled true = 开启 64k 升级（tengu_otk_slot_v1）
     */
    public MaxTokensHandler(boolean tenguOtkSlotV1Enabled) {
        this.tenguOtkSlotV1Enabled = tenguOtkSlotV1Enabled;
    }

    /**
     * 注入 settings.maxOutputTokens 读取器（测试可注入 mock 模拟"已配置 override → 抑制 64k 升级"）。
     */
    public void setSettingsOverrideProvider(java.util.function.Supplier<Integer> settingsOverrideProvider) {
        this.settingsOverrideProvider = settingsOverrideProvider != null
            ? settingsOverrideProvider
            : AnthropicSdkProvider::readSettingsMaxOutputTokens;
    }

    /**
     * 处理 max_tokens 截断事件（调用方仅在 {@code msg.isMaxOutputTokensError()} 时调用）。
     *
     * @param state                   当前恢复状态（会被修改）
     * @param maxOutputTokensOverride 上一请求是否已带 max_tokens 覆盖（CC query.ts:1201
     *                                {@code maxOutputTokensOverride === undefined} 判定；null=未升级）
     * @return RecoveryResult 指示调用方如何操作
     */
    public RecoveryResult handle(RecoveryState state, Integer maxOutputTokensOverride) {
        // [IMP-15 DRIFT-11] 64k 升级 gate · CC query.ts:1199-1203
        //   gate 开启 && override===undefined && 无 settings.maxOutputTokens override → 升级；否则走多轮续写。
        //   [E4-C] F1 迁移：CC 的 env.CLAUDE_CODE_MAX_OUTPUT_TOKENS 判定 → settings.maxOutputTokens 单源
        //   （AnthropicSdkProvider.readSettingsMaxOutputTokens）。无 Spring 上下文 → null → 等价"未配置"。
        //   [ER-IMP-07 / DC-22] 升级判定从 RecoveryState.hasEscalated 粘性字段改为
        //   override===undefined re-arm（CC query.ts:1201）：升级后 override=64k（LlmAgentLoop
        //   注入），续写后回落 undefined，天然 re-arm —— 比 Java 粘性一次更贴近 CC。
        Integer settingsOverride = settingsOverrideProvider.get();
        boolean canEscalate = tenguOtkSlotV1Enabled
            && maxOutputTokensOverride == null
            && settingsOverride == null;
        if (canEscalate) {
            // 路径 1a: 首次截断 + gate 开启 → 升级 max_tokens（不追加消息）
            return escalate(state);
        }
        if (!tenguOtkSlotV1Enabled) {
            log.info("MaxTokensHandler: tengu_otk_slot_v1 gate 关闭, 跳过 64k 升级, 直走多轮续写 (≤{}) · CC query.ts:1199",
                QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT);
        } else if (settingsOverride != null) {
            // [E4-C] 数据流日志：settings.maxOutputTokens override 已配置 → 抑制 64k 升级（CC query.ts:1199-1203
            //   同读 env 判定"无 override 前置"，F1 迁移后 Java 读 settings 单源）
            log.info("MaxTokensHandler: settings.maxOutputTokens override 已配置={}, 抑制 64k 升级, 直走多轮续写 (≤{}) · CC query.ts:1202 · E4-C",
                settingsOverride, QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT);
        }

        if (state.getContinuationCount() >= QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT) {
            // 路径 1c: 续写次数耗尽
            return exhausted(state);
        }

        // 路径 1b: 已升级/无 gate 后再次截断 → 追加续写提示
        return continuation(state);
    }

    /**
     * 升级 max_tokens 8K→64K · CC query.ts:1199-1221。
     *
     * <p><b>不追加消息</b>，升级信号 = 返回的 RecoveryResult.reason MAX_OUTPUT_TOKENS_ESCALATE，
     * 由调用方注入 override=64k（CC query.ts:1204-1219 maxOutputTokensOverride: ESCALATED_MAX_TOKENS）。
     *
     * <p><b>[P-10]</b>：写 {@code state.setLastReason(MAX_OUTPUT_TOKENS_ESCALATE)} —— 对齐 CC query.ts:1217
     * {@code transition: { reason: 'max_output_tokens_escalate' }}（Java RecoveryState.lastReason =
     * CC transition.reason 镜像）；测试可经 {@code state.getLastReason()} 断言升级路径触发。
     *
     * @param state 当前恢复状态（写入 lastReason）
     */
    private RecoveryResult escalate(RecoveryState state) {
        state.setLastReason(LoopReason.MAX_OUTPUT_TOKENS_ESCALATE);
        log.info("MaxTokensHandler: max_tokens 8K→{}K 升级 (无消息追加, tengu_otk_slot_v1 gate 开启) · CC query.ts:1199-1221/:1217",
            ContextConstants.ESCALATED_MAX_TOKENS / 1000);
        return new RecoveryResult(true, LoopReason.MAX_OUTPUT_TOKENS_ESCALATE,
            "max_tokens 8K→" + (ContextConstants.ESCALATED_MAX_TOKENS / 1000) + "K");
    }

    /**
     * 追加续写提示 · CC query.ts:1223-1252。
     *
     * <p>递增 state.continuationCount，返回 CONTINUATION_PROMPT 文本，
     * 调用方应将其作为 user 消息追加到 messages 列表后重试。
     */
    private RecoveryResult continuation(RecoveryState state) {
        state.incrementContinuation();
        log.info("MaxTokensHandler: 续写 #{}/{} · CC query.ts:1223-1252",
            state.getContinuationCount(),
            QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT);
        return new RecoveryResult(true, LoopReason.MAX_OUTPUT_TOKENS_RECOVERY,
            "Output token limit hit. Resume directly — no apology, no recap of what you were doing. Pick up mid-thought if that is where the cut happened. Break remaining work into smaller pieces.");
    }

    /**
     * 续写次数耗尽，标记不可恢复。
     *
     * <p><b>ER-IMP-01</b>：reason 置 null —— CC 无 {@code exhausted} reason
     * （recoverable=false 已承载耗尽信号，DC-09），调用方仅在 recoverable=true 时读 reason。
     */
    private RecoveryResult exhausted(RecoveryState state) {
        state.setLastReason(null);
        log.error("MaxTokensHandler: 续写次数耗尽 ({} of {}) · CC MAX_OUTPUT_TOKENS_RECOVERY_LIMIT",
            state.getContinuationCount(),
            QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT);
        return new RecoveryResult(false, null,
            "max_tokens recovery exhausted after "
                + QueryConstants.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT
                + " continuations");
    }
}

package com.nexusai.application.agent.recovery;

import java.util.Map;

/**
 * 模型名工具 · 对齐 CC utils/model/model.ts。
 *
 * <p>承载两个 CC 符号的 Java 等价：
 * <ul>
 *   <li>{@link #isNonCustomOpusModel(String)} — CC model.ts:40-46 {@code isNonCustomOpusModel}</li>
 *   <li>{@link #renderModelName(String)} — CC model.ts:395-412 {@code renderModelName}
 *       （内部依赖 {@code getPublicModelDisplayName} model.ts:349-383）</li>
 * </ul>
 *
 * <p><b>CC 真源</b>（grep 自验，不信注释）：model.ts:40-46 为对 4 个 firstParty Opus 模型
 * 字符串的<b>精确相等</b>判定（非前缀）：
 * <pre>
 * export function isNonCustomOpusModel(model: ModelName): boolean {
 *   return (model === getModelStrings().opus40 || model === getModelStrings().opus41 ||
 *           model === getModelStrings().opus45 || model === getModelStrings().opus46)
 * }
 * </pre>
 * 模型字符串源自 configs.ts CLAUDE_OPUS_4_*_CONFIG.firstParty（:52/:59/:66/:73）。
 *
 * <p>renderModelName 的 ant 分支（mask codename，model.ts:398-408）在 Java 项目 N/A
 * （无 USER_TYPE='ant' 概念），未知模型直接返回原串（与 CC default 分支一致）。
 */
public final class ModelNameUtil {

    private ModelNameUtil() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════════
    // isNonCustomOpusModel · CC utils/model/model.ts:40-46
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否为非自定义 Opus 主模型 · 对齐 CC model.ts:40-46 {@code isNonCustomOpusModel}。
     *
     * <p>CC 对 {@code getModelStrings().opus40/41/45/46} 做精确相等判定；模型字符串
     * 为 firstParty 直连 ID（configs.ts:52/59/66/73）：claude-opus-4-20250514 /
     * claude-opus-4-1-20250805 / claude-opus-4-5-20251101 / claude-opus-4-6。
     *
     * <p><b>用途</b>：withRetry.ts:331-332 的 529 fallback 资格闸
     * {@code FALLBACK_FOR_ALL_PRIMARY_MODELS || (!isClaudeAISubscriber() && isNonCustomOpusModel(model))}；
     * isClaudeAISubscriber 本项目 N/A（ErrorClassifier.isClaudeAISubscriber 恒 false）。
     *
     * @param model 主模型 ID（与 CC options.model 语义一致）
     * @return true=精确命中任一 firstParty Opus 4.x 模型
     */
    public static boolean isNonCustomOpusModel(String model) {
        if (model == null) {
            return false;
        }
        // CC original: getModelStrings().opus40/41/45/46 (model.ts:40-46) —— 精确相等，非前缀
        return "claude-opus-4-20250514".equals(model)
            || "claude-opus-4-1-20250805".equals(model)
            || "claude-opus-4-5-20251101".equals(model)
            || "claude-opus-4-6".equals(model);
    }

    // ════════════════════════════════════════════════════════════════════════
    // renderModelName · CC utils/model/model.ts:395-412（依赖 getPublicModelDisplayName :349-383）
    // ════════════════════════════════════════════════════════════════════════

    /** 显示名 map · CC original: getPublicModelDisplayName switch (model.ts:351-379)。键=firstParty 模型 ID。 */
    private static final Map<String, String> PUBLIC_DISPLAY_NAMES = Map.ofEntries(
        Map.entry("claude-opus-4-6", "Opus 4.6"),
        Map.entry("claude-opus-4-6[1m]", "Opus 4.6 (1M context)"),
        Map.entry("claude-opus-4-5-20251101", "Opus 4.5"),
        Map.entry("claude-opus-4-1-20250805", "Opus 4.1"),
        Map.entry("claude-opus-4-20250514", "Opus 4"),
        Map.entry("claude-sonnet-4-6[1m]", "Sonnet 4.6 (1M context)"),
        Map.entry("claude-sonnet-4-6", "Sonnet 4.6"),
        Map.entry("claude-sonnet-4-5-20250929[1m]", "Sonnet 4.5 (1M context)"),
        Map.entry("claude-sonnet-4-5-20250929", "Sonnet 4.5"),
        Map.entry("claude-sonnet-4-20250514", "Sonnet 4"),
        Map.entry("claude-sonnet-4-20250514[1m]", "Sonnet 4 (1M context)"),
        Map.entry("claude-3-7-sonnet-20250219", "Sonnet 3.7"),
        Map.entry("claude-3-5-sonnet-20241022", "Sonnet 3.5"),
        Map.entry("claude-haiku-4-5-20251001", "Haiku 4.5"),
        Map.entry("claude-3-5-haiku-20241022", "Haiku 3.5")
    );

    /**
     * 模型显示名渲染 · 对齐 CC model.ts:395-412 {@code renderModelName}。
     *
     * <p>CC 顺序：getPublicModelDisplayName 命中 → 返回显示名；未命中且非 ant → 原样返回。
     * Java 项目无 USER_TYPE='ant' 概念（ant codename mask 分支 N/A），未知模型返回原串。
     *
     * @param model 模型 ID
     * @return 显示名（如 "Opus 4.6"）；未知模型返回原串
     */
    public static String renderModelName(String model) {
        if (model == null) {
            return null;
        }
        String publicName = PUBLIC_DISPLAY_NAMES.get(model);
        if (publicName != null) {
            return publicName;
        }
        // CC original: ant 分支 N/A；default 分支 return model (model.ts:411)
        return model;
    }
}

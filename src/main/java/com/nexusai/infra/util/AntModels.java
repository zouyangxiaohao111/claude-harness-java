package com.nexusai.infra.util;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * AntModels · 对齐 CC utils/model/antModels.ts.
 *
 * <p>L1 语义: Ant-only model override config + 查找 ant 内部 model。
 * <ul>
 *   <li>{@link AntModel} record (alias/model/label/description/defaultEffortValue/defaultEffortLevel/contextWindow/defaultMaxTokens/upperMaxTokensLimit/alwaysOnThinking)</li>
 *   <li>{@link AntModelSwitchCalloutConfig} record (modelAlias/description/version)</li>
 *   <li>{@link AntModelOverrideConfig} record (defaultModel/.../antModels/switchCallout)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 record + getAntModelOverrideConfig + getAntModels + resolveAntModel 3 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: 非 ant user→null;ant no override→[];resolveAntModel alias 匹配 case-insensitive</li>
 *   <li><b>A3 纯函数</b>: 静态;依赖 isAnt + overrideSupplier 注入</li>
 *   <li><b>A4 边界</b>: model undefined→undefined;overrideSupplier throws→[] (defensive)</li>
 *   <li><b>A5 业务场景</b>: ant 用户设 ANTHROPIC_DEFAULT_HAIKU_MODEL=custom-haiku ant model override mapping</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS env.USER_TYPE + GB lookup → Java isAnt supplier + overrideSupplier 注入式;
 * TS array.find → Java ArrayList + equalsIgnoreCase。
 */
public final class AntModels {

    public record EffortLevel(String name, int ordinal) {}

    public record AntModel(
        String alias,
        String model,
        String label,
        String description,
        Integer defaultEffortValue,
        EffortLevel defaultEffortLevel,
        Integer contextWindow,
        Integer defaultMaxTokens,
        Integer upperMaxTokensLimit,
        Boolean alwaysOnThinking) {}

    public record AntModelSwitchCalloutConfig(
        String modelAlias,
        String description,
        String version) {}

    public record AntModelOverrideConfig(
        String defaultModel,
        EffortLevel defaultModelEffortLevel,
        String defaultSystemPromptSuffix,
        List<AntModel> antModels,
        AntModelSwitchCalloutConfig switchCallout) {}

    private AntModels() {}

    /**
     * Whether the current user is an ant (internal Anthropic).
     *
     * @param userType value of {@code process.env.USER_TYPE}
     */
    public static boolean isAnt(String userType) {
        return "ant".equals(userType);
    }

    /**
     * Look up the ant model override config from GrowthBook.
     * Returns null if user is not ant or override is unset.
     *
     * @param userType value of {@code process.env.USER_TYPE}
     * @param overrideSupplier GrowthBook lookup; null returns null
     */
    public static AntModelOverrideConfig getAntModelOverrideConfig(
        String userType,
        Supplier<AntModelOverrideConfig> overrideSupplier) {
        if (!isAnt(userType)) return null;
        if (overrideSupplier == null) return null;
        try {
            return overrideSupplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Return all configured ant models (or empty if user is not ant).
     */
    public static List<AntModel> getAntModels(
        String userType,
        Supplier<AntModelOverrideConfig> overrideSupplier) {
        AntModelOverrideConfig cfg = getAntModelOverrideConfig(userType, overrideSupplier);
        if (cfg == null || cfg.antModels() == null) return List.of();
        return cfg.antModels();
    }

    /**
     * Resolve a model id to its ant model record via alias (case-sensitive) or
     * model-id substring (case-insensitive). Returns null for non-ant users.
     */
    public static AntModel resolveAntModel(
        String model,
        String userType,
        Supplier<AntModelOverrideConfig> overrideSupplier) {
        if (!isAnt(userType) || model == null) return null;
        List<AntModel> models = getAntModels(userType, overrideSupplier);
        String lower = model.toLowerCase(Locale.ROOT);
        for (AntModel m : models) {
            if (model.equals(m.alias())) return m;
            if (m.model() != null && lower.contains(m.model().toLowerCase(Locale.ROOT))) {
                return m;
            }
        }
        return null;
    }
}

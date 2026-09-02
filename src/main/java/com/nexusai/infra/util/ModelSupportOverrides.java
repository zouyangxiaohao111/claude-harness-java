package com.nexusai.infra.util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ModelSupportOverrides · 对齐 CC utils/model/modelSupportOverrides.ts.
 *
 * <p>L1 语义: 检查 3p 模型 capability override 是否设置在 pinned 模型 env vars。
 * 用于当 3p 用户通过 env 自定义 default model capabilities 时,识别匹配的 capability。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: get3PModelCapabilityOverride(model, capability, provider, env, envCapabilities)→Boolean (true/false/null);TIERS 3 tier 常量</li>
 *   <li><b>A2 Golden Trace</b>: firstParty provider → null;3p + model 不匹配 tier → null;3p + model 匹配 + capability 在 csv → true;不在 → false</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null model → null;env 缺 → null;capabilities 空 → false</li>
 *   <li><b>A5 业务场景</b>: 3p 用户设 ANTHROPIC_DEFAULT_OPUS_MODEL='my-custom-opus' + ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES='effort,thinking',model='my-custom-opus' capability='effort' → true</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS lodash memoize → Java LinkedHashMap cache;
 * TS process.env indexed access → Java Map indexed (caller wired);
 * TS string toLowerCase + split → Java Locale.ROOT + split。
 */
public final class ModelSupportOverrides {

    public enum Capability {
        EFFORT("effort"),
        MAX_EFFORT("max_effort"),
        THINKING("thinking"),
        ADAPTIVE_THINKING("adaptive_thinking"),
        INTERLEAVED_THINKING("interleaved_thinking");

        private final String value;
        Capability(String value) { this.value = value; }
        public String value() { return value; }
    }

    public record Tier(String modelEnvVar, String capabilitiesEnvVar) {}

    public static final List<Tier> TIERS = List.of(
        new Tier("ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL_SUPPORTED_CAPABILITIES"),
        new Tier("ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL_SUPPORTED_CAPABILITIES"),
        new Tier("ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL_SUPPORTED_CAPABILITIES"));

    private ModelSupportOverrides() {}

    /**
     * Check if {@code model} (lower-case) matches any pinned ANTHROPIC_DEFAULT_*_MODEL
     * env var, and if so, whether {@code capability} appears in its
     * capabilities csv env var.
     *
     * @param model       the model id under check
     * @param capability  capability being tested
     * @param provider    "firstParty" returns null immediately; "thirdParty" continues
     * @param env         env map (caller wires from System.getenv())
     * @param cache       optional memoization cache (null-safe)
     * @return true / false when match found, null when not matchable
     */
    public static Boolean get3PModelCapabilityOverride(
        String model,
        Capability capability,
        String provider,
        Map<String, String> env,
        Map<String, Boolean> cache) {

        if (cache != null) {
            String key = model.toLowerCase(Locale.ROOT) + ":" + capability.value();
            if (cache.containsKey(key)) return cache.get(key);
        }
        Boolean result = compute(model, capability, provider, env);
        if (cache != null) {
            String key = model.toLowerCase(Locale.ROOT) + ":" + capability.value();
            cache.put(key, result);
        }
        return result;
    }

    private static Boolean compute(
        String model,
        Capability capability,
        String provider,
        Map<String, String> env) {
        if ("firstParty".equals(provider)) return null;
        if (model == null || capability == null) return null;
        if (env == null) return null;
        String m = model.toLowerCase(Locale.ROOT);
        for (Tier tier : TIERS) {
            String pinned = env.get(tier.modelEnvVar());
            String capabilities = env.get(tier.capabilitiesEnvVar());
            if (pinned == null || capabilities == null) continue;
            if (!m.equals(pinned.toLowerCase(Locale.ROOT))) continue;
            String[] parts = capabilities.toLowerCase(Locale.ROOT).split(",");
            for (String p : parts) {
                if (p.trim().equals(capability.value())) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        return null;
    }
}

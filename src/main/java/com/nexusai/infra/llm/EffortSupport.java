package com.nexusai.infra.llm;

import java.util.Locale;

/**
 * [C-31] effort 支持判定静态工具 · 镜像 CC {@code src/utils/effort.ts}（全文 Read 实证）。
 *
 * <p><b>WHY</b>: skill frontmatter effort（{@code Command.effort} String）经
 * {@code SkillToolImpl.contextModifier} → {@code AgentState.effortValue} 进入 LLM 请求层后，
 * provider 需要按 CC 语义决定是否/如何注入 effort：模型能力门控（modelSupportsEffort）、
 * env 覆盖（CLAUDE_CODE_EFFORT_LEVEL）、模型默认值（getDefaultEffortForModel）与 max 降级。
 * 全量对齐 CC effort.ts 的解析链，使 Java 请求体输出与 CC 逐字段一致。
 *
 * <p><b>载体约定</b>: Java 端 effort 全链路 String 载体（{@code Command.effort} /
 * {@code AgentDefinition.effort} Optional&lt;String&gt; / {@code AgentState.effortValue}），
 * 不引入新 EffortValue 类型（避免双轨）。CC {@code EffortValue = EffortLevel | number} 的
 * 数值分支为 ant-only（{@code anthropic_internal.effort_override}，effort.ts:62-68），
 * Java 无 USER_TYPE 判定 → 登记为文档化部分实现（不伪造），数值解析返回 null。
 *
 * <p>auth/feature 层依赖登记（不伪造，见各方法 JavaDoc）: {@code isProSubscriber} /
 * {@code isMaxSubscriber} / {@code isTeamSubscriber}（CC utils/auth.ts）/ {@code isUltrathinkEnabled}
 * （CC utils/thinking.ts）/ {@code get3PModelCapabilityOverride}（CC model/modelSupportOverrides.ts）/
 * {@code getOpusDefaultEffortConfig}（CC growthbook tengu_grey_step2）。Java LLM 层无对应判定，
 * 以最简模型等价物代替（opus-4-6 → 'medium' 默认，未知模型默认支持 effort）。
 */
public final class EffortSupport {

    private EffortSupport() {
    }

    /** CC original: EFFORT_LEVELS (effort.ts:12-15) = ['low','medium','high','max']；[用户拍板 2026-08-22] 新增 xhigh（对齐 CC 新版本 UI，前端 EffortModal 含 xhigh 档）。 */
    private static final String[] EFFORT_LEVELS = {"low", "medium", "high", "xhigh", "max"};

    /**
     * 是否为合法 effort 等级 · CC original: {@code isEffortLevel} (effort.ts:70-72)。
     *
     * @param value 待判定字符串（null → false）
     * @return true 当且仅当 value ∈ {low, medium, high, max}（不区分大小写，已归一化）
     */
    public static boolean isEffortLevel(String value) {
        if (value == null) {
            return false;
        }
        for (String level : EFFORT_LEVELS) {
            if (level.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 effort 值 · CC original: {@code parseEffortValue} (effort.ts:74-87)。
     *
     * <p>CC 支持 string level + 数值（数值 ant-only）；Java String 载体仅解析 level 字符串，
     * 数值返回 null（concern ②：ant 分支条件不可达，不伪造）。
     *
     * @param value 原始字符串（null/空 → null）
     * @return 归一化 level（小写）或 null（无效/数值 ant-only）
     */
    public static String parseEffortValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String str = value.trim().toLowerCase(Locale.ROOT);
        if (isEffortLevel(str)) {
            return str;
        }
        // CC: typeof value === 'number' && isValidNumericEffort(value) → 数值 ant-only
        //   （effort.ts:76-78 + 62-68 anthropic_internal.effort_override）
        //   Java 无 USER_TYPE 判定 → 不建模，数值统一返回 null。
        return null;
    }

    /**
     * 模型是否支持 effort 参数 · CC original: {@code modelSupportsEffort} (effort.ts:19-38)。
     *
     * <p>判定顺序（对齐 CC）:
     * <ol>
     *   <li>env {@code CLAUDE_CODE_ALWAYS_ENABLE_EFFORT} truthy → true（CC effort.ts:22-24）</li>
     *   <li>{@code get3PModelCapabilityOverride(model, 'effort')} → Java 无 3P 覆盖表，省略
     *       （模型支持表仅 opus-4-6/sonnet-4-6 的 1P allowlist 等价覆盖）</li>
     *   <li>model 含 {@code opus-4-6} / {@code sonnet-4-6} → true（CC effort.ts:28-29）</li>
     *   <li>model 含 {@code haiku} / {@code sonnet} / {@code opus}（其它已知旧模型）→ false
     *       （CC effort.ts:31-34）</li>
     *   <li>默认 true（CC effort.ts:37-38 {@code getAPIProvider() === 'firstParty'} —— 未知 1P
     *       模型默认支持；Java 端 AnthropicSdkProvider 恒为 1P，故默认 true 等价）</li>
     * </ol>
     *
     * @param model 模型名（null → false）
     */
    public static boolean modelSupportsEffort(String model) {
        if (model == null) {
            return false;
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (isEnvTruthy("CLAUDE_CODE_ALWAYS_ENABLE_EFFORT")) {
            return true;
        }
        // get3PModelCapabilityOverride(model, 'effort')（CC model/modelSupportOverrides.ts）→ 省略
        if (m.contains("opus-4-6") || m.contains("sonnet-4-6")) {
            return true;
        }
        if (m.contains("haiku") || m.contains("sonnet") || m.contains("opus")) {
            return false;
        }
        // 默认 true（1P 未知模型 · CC effort.ts:37-38）
        return true;
    }

    /**
     * 模型是否支持 'max' effort · CC original: {@code modelSupportsMaxEffort} (effort.ts:40-48)。
     *
     * <p>仅 Opus 4.6 支持 max（公共模型）；CC 另含 {@code USER_TYPE==='ant' && resolveAntModel(model)}
     * 分支 —— ant-only，Java 不建模（返回 false）。{@code get3PModelCapabilityOverride(model,
     * 'max_effort')} 同样省略。
     *
     * @param model 模型名（null → false）
     */
    public static boolean modelSupportsMaxEffort(String model) {
        if (model == null) {
            return false;
        }
        // get3PModelCapabilityOverride(model, 'max_effort') → 省略
        if (model.toLowerCase(Locale.ROOT).contains("opus-4-6")) {
            return true;
        }
        // USER_TYPE==='ant' && resolveAntModel(model) → ant-only，不建模
        return false;
    }

    /**
     * env effort 覆盖 · CC original: {@code getEffortEnvOverride} (effort.ts:60-66)。
     *
     * <p>读取 {@code CLAUDE_CODE_EFFORT_LEVEL}；{@code 'unset'}/{@code 'auto'} 或缺失/无效 → null。
     * ⚠ CC 三态（null=显式抑制 / undefined=缺失回落 / level=覆盖）中"显式抑制"语义在
     * {@link #resolveAppliedEffort} 内部区分处理；本方法仅暴露解析后的覆盖 level（用于日志/审计）。
     *
     * @return 解析后的覆盖 level 或 null（缺失/'unset'/'auto'/无效）
     */
    public static String getEffortEnvOverride() {
        String envRaw = System.getenv("CLAUDE_CODE_EFFORT_LEVEL");
        if (envRaw == null) {
            return null;
        }
        String lower = envRaw.trim().toLowerCase(Locale.ROOT);
        if ("unset".equals(lower) || "auto".equals(lower)) {
            return null;
        }
        return parseEffortValue(envRaw);
    }

    /**
     * 三态 env 覆盖判定结果 · 对齐 CC {@code getEffortEnvOverride} 三态（effort.ts:136-142）：
     * undefined=缺失/无效（不覆盖）· null=显式抑制（'unset'/'auto'）· EffortValue=覆盖档位。
     *
     * @param raw      {@code CLAUDE_CODE_EFFORT_LEVEL} 原始串（env 缺失 → null）
     * @param level    解析后的覆盖档位（suppress/invalid → null）
     * @param suppress env 显式 'unset'/'auto'（CC null 态）
     */
    public record EffortEnvOverride(String raw, String level, boolean suppress) {
        /** CC undefined 态（env 缺失或无效）→ 无覆盖。 */
        public boolean isMissing() {
            return raw == null || (level == null && !suppress);
        }

        /** 存在覆盖（suppress 或档位）→ CC {@code envOverride !== undefined}。 */
        public boolean hasOverride() {
            return raw != null && (suppress || level != null);
        }

        /** 钉死具体档位（CC envOverride 为 EffortValue）· unsetEffortLevel 仅此态告警。 */
        public boolean pinsLevel() {
            return level != null;
        }
    }

    /**
     * 读取 CLAUDE_CODE_EFFORT_LEVEL 三态 · CC original: {@code getEffortEnvOverride}（effort.ts:136-142）。
     *
     * <p>三态：env 缺失/无效 → {@link EffortEnvOverride#isMissing()}（CC undefined，不覆盖）；
     * env='unset'/'auto' → suppress=true（CC null，显式抑制）；env=合法档位 → level。
     * 区别于 {@link #getEffortEnvOverride()}（已折叠为 null，日志/审计用）——命令层需区分
     * "缺失"与"显式抑制"（/effort set 的冲突判定依赖此区分，effort.tsx:35-51）。
     *
     * @return 三态封装（含原始 env 串，供消息展示）
     */
    public static EffortEnvOverride getEffortEnvState() {
        return parseEnvState(System.getenv("CLAUDE_CODE_EFFORT_LEVEL"));
    }

    /**
     * 纯函数三态解析 · CC original: {@code getEffortEnvOverride}（effort.ts:136-142），
     * 以 env 原始串为输入（可测性拆分 —— 命令层经 envProvider 接缝注入，BuiltInCommands.envProvider
     * 同款模式，JDK 9+ System.getenv 不可在测试内设置）。
     *
     * @param envRaw CLAUDE_CODE_EFFORT_LEVEL 原始串（null = env 缺失）
     * @return 三态封装（含原始 env 串，供消息展示）
     */
    public static EffortEnvOverride parseEnvState(String envRaw) {
        if (envRaw == null) {
            return new EffortEnvOverride(null, null, false);
        }
        String lower = envRaw.trim().toLowerCase(Locale.ROOT);
        if ("unset".equals(lower) || "auto".equals(lower)) {
            return new EffortEnvOverride(envRaw, null, true);
        }
        return new EffortEnvOverride(envRaw, parseEffortValue(envRaw), false);
    }

    /**
     * 可持久化档位 · CC original: {@code toPersistableEffort}（effort.ts:95-105）。
     *
     * <p>low/medium/high 恒可持久化（写入 settings.effortLevel）；max 仅 USER_TYPE=ant 可持久化
     * —— Java 无 USER_TYPE 判定 → 非 ant，max 不落盘（会话级）。数字/auto/无效 → null。
     *
     * @param value 归一化档位（小写，null → null）
     * @return 可持久化档位或 null（= 不写 settings.effortLevel）
     */
    public static String toPersistableEffort(String value) {
        if (value == null) {
            return null;
        }
        if ("low".equals(value) || "medium".equals(value) || "high".equals(value)) {
            return value;
        }
        // CC: value === 'max' && process.env.USER_TYPE === 'ant' → value（effort.ts:101-103）
        //   Java 无 USER_TYPE 判定 → 非 ant，max 会话级不落盘。
        return null;
    }

    /**
     * 档位用户可读描述 · CC original: {@code getEffortLevelDescription}（effort.ts:224-235）。
     *
     * @param level 档位（low/medium/high/max）
     * @return 人类可读描述（非法/null → medium 描述兜底，CC switch 无 default 但调用方值域受限）
     */
    public static String getEffortLevelDescription(String level) {
        if (level == null) {
            return "Balanced approach with standard implementation and testing";
        }
        switch (level) {
            case "low":
                return "Quick, straightforward implementation with minimal overhead";
            case "medium":
                return "Balanced approach with standard implementation and testing";
            case "high":
                return "Comprehensive implementation with extensive testing and documentation";
            case "max":
                return "Maximum capability with deepest reasoning (Opus 4.6 only)";
            default:
                // CC switch 无 default（EffortLevel 联合闭合）；Java 兜底 medium 描述（effort.ts:251 数值分支同款）
                return "Balanced approach with standard implementation and testing";
        }
    }

    /**
     * effort 值用户可读描述 · CC original: {@code getEffortValueDescription}（effort.ts:243-252）。
     *
     * <p>字符串档位 → {@link #getEffortLevelDescription}；非档位/数值（ant-only 不建模）→
     * medium 描述兜底（CC 数值分支 effort.ts:244-246 的 Java 等价）。
     *
     * @param value effort 值（null → medium 描述）
     * @return 人类可读描述
     */
    public static String getEffortValueDescription(String value) {
        if (value == null) {
            return "Balanced approach with standard implementation and testing";
        }
        return getEffortLevelDescription(value);
    }

    /**
     * 展示用 effort 档位 · CC original: {@code getDisplayedEffortLevel}（effort.ts:174-180）。
     *
     * <p>{@code resolveAppliedEffort(model, appStateEffort) ?? 'high'}（API 无 effort 参数时
     * 默认 high 语义）——状态栏与 /effort 展示单一真源（CC-1088）。
     *
     * @param model          模型名（null → 走模型默认回落）
     * @param appStateEffort 会话级 effort 值（可 null）
     * @return 展示档位（恒非 null）
     */
    public static String getDisplayedEffortLevel(String model, String appStateEffort) {
        String resolved = resolveAppliedEffort(model, appStateEffort);
        return resolved != null ? resolved : "high";
    }

    /**
     * 模型默认 effort · CC original: {@code getDefaultEffortForModel} (effort.ts:135-161)。
     *
     * <p><b>文档化部分实现</b>（不伪造）: CC 对 Opus 4.6 在 isProSubscriber 或
     * (tengu_grey_step2 enabled && (isMaxSubscriber || isTeamSubscriber)) 时默认 'medium'；
     * isUltrathinkEnabled && modelSupportsEffort 时默认 'medium'（ultrathink bump 到 high）。
     * Java LLM 层无 auth（utils/auth.ts）/ growthbook（tengu_grey_step2）/ ultrathink
     * （utils/thinking.ts）判定 → 以最简等价：opus-4-6 → 'medium'，其余 undefined。
     * skill frontmatter effort（管线核心）不受影响 —— 本默认仅在 appState 无 effort 时兜底。
     *
     * @param model 模型名（null → null）
     * @return 默认 effort level 或 null（= 不设 effort，API 侧等价 high）
     */
    public static String getDefaultEffortForModel(String model) {
        if (model == null) {
            return null;
        }
        if (model.toLowerCase(Locale.ROOT).contains("opus-4-6")) {
            return "medium";
        }
        return null;
    }

    /**
     * 解析实际发送到 API 的 effort 值 · CC original: {@code resolveAppliedEffort}
     * (effort.ts:68-82) + claude.ts:1458 {@code resolveAppliedEffort(options.model, options.effortValue)}。
     *
     * <p>优先级链（对齐 CC）:
     * <ol>
     *   <li>env {@code CLAUDE_CODE_EFFORT_LEVEL} 为 'unset'/'auto' → null（显式抑制，不发 effort）</li>
     *   <li>env 覆盖 level（{@code getEffortEnvOverride}）</li>
     *   <li>appState effort（skill contextModifier 写入的 {@code AgentState.effortValue}）</li>
     *   <li>模型默认（{@link #getDefaultEffortForModel}）</li>
     * </ol>
     * 解析后为 'max' 且模型不支持 max → 降级 'high'（CC effort.ts:76-79：API 拒绝非 Opus-4.6 的 max）。
     *
     * @param model                模型名
     * @param appStateEffortValue  会话级 effort 值（可 null = 未设置）
     * @return 实际注入的 effort level 或 null（= 不写 output_config.effort，仅可能追加 beta header）
     */
    public static String resolveAppliedEffort(String model, String appStateEffortValue) {
        String envRaw = System.getenv("CLAUDE_CODE_EFFORT_LEVEL");
        if (envRaw != null) {
            String lower = envRaw.trim().toLowerCase(Locale.ROOT);
            if ("unset".equals(lower) || "auto".equals(lower)) {
                // CC: envOverride === null → return undefined（显式抑制，不发 effort）
                return null;
            }
        }
        String envEffort = parseEffortValue(envRaw);
        // envOverride ?? appStateEffortValue ?? getDefaultEffortForModel(model)
        // Java appStateEffortValue 是原始字符串（skill frontmatter）→ 经 parseEffortValue 校验，
        // 无效/数值（ant-only 不建模）→ null 回落模型默认（对齐 CC：appState.effortValue 已是
        // 合法 EffortValue，Java 需自行校验，concern ②）。
        String resolved;
        if (envEffort != null) {
            resolved = envEffort;
        } else if (appStateEffortValue != null) {
            resolved = parseEffortValue(appStateEffortValue);
            if (resolved == null) {
                resolved = getDefaultEffortForModel(model);
            }
        } else {
            resolved = getDefaultEffortForModel(model);
        }
        // API 拒绝非 Opus-4.6 模型的 max → 降级 'high'
        if ("max".equals(resolved) && !modelSupportsMaxEffort(model)) {
            return "high";
        }
        return resolved;
    }

    /**
     * env truthy 判定 · CC original: {@code isEnvTruthy}（utils/envUtils.ts）——
     * 非空且非 'false'/'0'/'no'/'off'/'null'/'undefined'/'' 视为 truthy。
     *
     * @param envKey 环境变量名
     * @return 是否视为开启
     */
    private static boolean isEnvTruthy(String envKey) {
        String v = System.getenv(envKey);
        if (v == null) {
            return false;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return !("false".equals(lower) || "0".equals(lower) || "no".equals(lower)
            || "off".equals(lower) || "null".equals(lower) || "undefined".equals(lower));
    }
}

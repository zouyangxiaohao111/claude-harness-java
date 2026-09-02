package com.nexusai.application.agent.subagent;

import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.util.AntModels;
import com.nexusai.repository.settings.entity.SettingsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 技能模型覆盖解析器 · 对齐 CC {@code Open-ClaudeCode/src/utils/model/model.ts:523-536}
 * {@code resolveSkillModelOverride}。
 *
 * <p><b>CC 真源（Read 实证，不信注释）</b>:
 * <pre>
 * export function resolveSkillModelOverride(skillModel, currentModel): string {
 *   if (has1mContext(skillModel) || !has1mContext(currentModel)) {
 *     return skillModel
 *   }
 *   if (modelSupports1M(parseUserSpecifiedModel(skillModel))) {
 *     return skillModel + '[1m]'
 *   }
 *   return skillModel
 * }
 * </pre>
 *
 * <p><b>WHY（CC model.ts:508-521 注释）</b>: 把 {@code [1m]} 后缀顺延 —— 否则 1M 会话上
 * 调用 {@code model: opus} 的技能会把有效上下文窗口从 1M 塌缩到 200K，触发 autocompact
 * 误报 "Context limit reached"。技能作者写 {@code model: opus} 的意思是 "用 opus 档推理"，
 * 不是 "降级到 200K"。只有目标家族真正支持 1M（opus/sonnet-4）时才顺延 {@code [1m]}；
 * {@code model: haiku}（无 1M 变体）仍正常降级，随之而来的 autocompact 是正确行为；
 * 技能已显式写 {@code [1m]} 则原样保留。
 *
 * <p><b>比对前先规范化（EV-DR-012 / AD-19 △ 修复，deletion-manifest #21 解除）</b>:
 * CC 的 {@code modelSupports1M} 在 canonical ID 上判家族（{@code 'claude-sonnet-4'} /
 * {@code 'opus-4-6'}，context.ts:43-49），裸别名 {@code opus}/{@code sonnet} 经
 * {@code parseUserSpecifiedModel}（model.ts:445-506）先解析成各档默认 canonical 才命中；
 * {@code opusplan}→defaultSonnet（model.ts:459）、{@code best}→getBestModel()→defaultOpus
 * （model.ts:467）。旧 Java 在 {@code modelSupports1M} 层手补 {@code equals("opus")/("sonnet")}
 * + {@code contains("sonnet-4")}（过宽）补偿缺失的别名解析器 —— 本类现按 CC 结构实现完整
 * {@link #parseUserSpecifiedModel}（别名→canonical 家族标记），旧裸别名补偿删除。
 *
 * <p><b>env 门控（对齐 CC context.ts:31-49）</b>: {@code has1mContext} / {@code modelSupports1M}
 * 均受 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT}（isEnvTruthy）门控 —— 为真时 1M 判定恒 false
 * （HIPAA 关停场景）。旧 Java 忽略该 env（对齐缺口，本次补齐）。
 * {@code parseUserSpecifiedModel} 的 {@code has1mTag}（CC model.ts:451 经
 * {@code has1mContext(normalizedModel)} 计算，context.ts:35-40）同受该门控（D14/T1，
 * EV-WF5-MR-013）：禁用时别名/remap/自定义模型均不保留 {@code [1m]} 后缀，消除与
 * {@code resolveSkillModelOverride} 已门控路径的内部不一致。
 *
 * <p><b>USER_TYPE=ant 分支（R-A9，对齐 CC model.ts:485-498 + antModels.ts:34-64）</b>:
 * CC {@code parseUserSpecifiedModel} 在 legacy opus remap 之后、原样保大小写之前插入
 * {@code if (process.env.USER_TYPE === 'ant')} 分支 —— 把 {@code tengu_ant_model_override}
 * GrowthBook 配置的 {@code antModels}（别名 → 内部 codename model 映射）接入解析：
 * {@code resolveAntModel(baseAntModel)} 命中时返回 {@code antModel.model + (has1mTag?'[1m]':'')}，
 * 未命中（无配置/无匹配）落到 "Fall through to the alias string"（model.ts:495-498 注释）——
 * API 调用将用该字符串失败，但会经反馈机制暴露，用户可重启/等 flag cache 刷新。
 *
 * <p>Java 无 GrowthBook SDK → 用 {@link AntModels} 静态工具（{@code com.nexusai.infra.util}，
 * 对齐 antModels.ts）承载匹配逻辑；配置源经 {@link #ANT_MODEL_OVERRIDE_SUPPLIER} 注入缝提供
 * （默认 null → 恒落空，与 CC 无配置时行为一致），USER_TYPE 经 {@link #ENV_READER} 读（默认
 * {@code System.getenv}）。该分支仅 ant 内部环境生效，外部构建恒不触发。
 *
 * <p>消费点: {@code SkillToolImpl.buildContextModifier}（SkillTool.ts:810-821 三件套 model
 * 件）+ {@code LlmAgentLoop.getModelForCall}（handlePromptSubmit.ts:566 等价）。
 */
public final class SkillModelOverrideResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillModelOverrideResolver.class);

    /** CC getDefaultOpusModel（model.ts:105-116）→ getModelStrings().opus46 = 'claude-opus-4-6'。
     *  Java 同 PromptCaching.DEFAULT_OPUS。仅作 1M 家族判定标记。 */
    private static final String CANONICAL_DEFAULT_OPUS = "claude-opus-4-6";
    /** CC getDefaultSonnetModel（model.ts:119-130）→ getModelStrings().sonnet46 = 'claude-sonnet-4-6'。
     *  Java 同 PromptCaching.DEFAULT_SONNET。1P/3P 差异（sonnet45）不影响 claude-sonnet-4 家族判定。 */
    private static final String CANONICAL_DEFAULT_SONNET = "claude-sonnet-4-6";
    /** CC getDefaultHaikuModel（model.ts:131-138）→ getModelStrings().haiku45 = 'claude-haiku-4-5-20251001'。
     *  Java 同 PromptCaching.DEFAULT_HAIKU。 */
    private static final String CANONICAL_DEFAULT_HAIKU = "claude-haiku-4-5-20251001";

    /** CC LEGACY_OPUS_FIRSTPARTY（model.ts:538-543）——4 个已下线的 legacy Opus 4.0/4.1 显式字符串。
     *  isLegacyOpusFirstParty（model.ts:545-547）精确 includes 判定（非前缀匹配）。 */
    private static final Set<String> LEGACY_OPUS_FIRSTPARTY = Set.of(
        "claude-opus-4-20250514",
        "claude-opus-4-1-20250805",
        "claude-opus-4-0",
        "claude-opus-4-1");

    private SkillModelOverrideResolver() {
        throw new AssertionError("SkillModelOverrideResolver is a utility class");
    }

    /**
     * 环境变量读取器 · 测试注入缝（对齐 loadAgentsDir.java:683 ENV_READER 惯例）。
     *
     * <p>System.getenv 在 JVM 内只读不可 mutate，R-A9 使 {@code USER_TYPE=ant} 分支
     * （CC model.ts:485-498）依赖 USER_TYPE 后，测试经此缝注入 USER_TYPE 以验证
     * ant 分支（命中 ant model 映射）与非 ant 分支（不触发）。生产默认
     * {@code System::getenv}，行为零变化。
     */
    static volatile java.util.function.Function<String, String> ENV_READER = System::getenv;

    /**
     * ant model override 配置源 · 测试注入缝（对齐 CC antModels.ts:34-42
     * {@code getAntModelOverrideConfig()} 的 GrowthBook feature 查找）。
     *
     * <p>CC 从 GrowthBook feature {@code 'tengu_ant_model_override'} 读
     * {@code AntModelOverrideConfig}（antModels.ts:38-41），无配置时返回 null →
     * {@code getAntModels()} 返回空数组 → {@code resolveAntModel} 未命中 → 落空到
     * "Fall through to the alias string"（model.ts:495-498）。Java 无 GrowthBook SDK，
     * 本类以可替换 Supplier 承载（默认 null → 恒落空，与 CC 无配置行为一致）；
     * 若未来接入远端配置源，替换该 Supplier 即可，行为对齐 CC。
     *
     * <p>包可见（static volatile）供测试注入 AntModelOverrideConfig 验证 ant 分支。
     */
    static volatile java.util.function.Supplier<AntModels.AntModelOverrideConfig> ANT_MODEL_OVERRIDE_SUPPLIER = () -> null;

    /**
     * 解析技能 model 覆盖 · 对齐 CC model.ts:523-536。
     *
     * @param skillModel  技能 frontmatter 的 {@code model}（CC original: skillModel，model.ts:523）
     * @param currentModel 当前主循环模型（CC original: currentModel，model.ts:524；
     *                    Java = appStateRef 'mainLoopModel' 当前值）
     * @return 覆盖后的模型字符串；{@code skillModel == null} 返回 {@code null}（调用方跳过）
     */
    public static String resolveSkillModelOverride(String skillModel, String currentModel) {
        return resolveSkillModelOverride(skillModel, currentModel, is1mContextDisabled());
    }

    /**
     * 包可见重载（测试隔离）· 显式 1M 禁用开关。
     *
     * <p>生产路径 {@link #resolveSkillModelOverride(String, String)} 传
     * {@code is1mContextDisabled()}（读 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT}）；
     * 测试传可控布尔（Java System.getenv 只读，无法在测试内设置该 env）验证 env 门控分支。
     * 模式同 {@link AgentModelResolver#resolveWithEnv}（env 注入隔离）。
     */
    static String resolveSkillModelOverride(String skillModel, String currentModel, boolean disable1mContext) {
        return resolveSkillModelOverride(skillModel, currentModel, disable1mContext,
            isFirstParty(), isLegacyModelRemapEnabled(), getDefaultOpusModel());
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 legacy opus remap 全部门控（firstParty / remap 开关 / 默认 opus）。
     *
     * <p>remap 门控依赖 3 个源：{@code getAPIProvider()==='firstParty'}（CLAUDE_CODE_USE_BEDROCK/
     * VERTEX/FOUNDRY）、{@code isLegacyModelRemapEnabled()}（CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP）、
     * {@code getDefaultOpusModel()}（[W6-1] DB settings.strongModelId，原 ANTHROPIC_DEFAULT_OPUS_MODEL
     * env 已删）。测试注入可控值以确定性验证 remap 分支（不依赖测试机 ambient env）。
     *
     * @param skillModel         技能 frontmatter 的 {@code model}
     * @param currentModel       当前主循环模型
     * @param disable1mContext   CLAUDE_CODE_DISABLE_1M_CONTEXT 门控
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     */
    static String resolveSkillModelOverride(String skillModel, String currentModel, boolean disable1mContext,
            boolean firstParty, boolean legacyRemapEnabled, String defaultOpus) {
        if (log.isDebugEnabled()) {
            log.debug("[SkillModelOverrideResolver] resolveSkillModelOverride 入口: skillModel={}, currentModel={}, 1M上下文禁用={}",
                    skillModel, currentModel, disable1mContext);
        }
        if (skillModel == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] skillModel 为 null → 返回 null（调用方跳过覆盖）");
            }
            return null;
        }
        // CC model.ts:527 has1mContext(skillModel) || !has1mContext(currentModel) → 直接返回
        if (has1mContext(skillModel, disable1mContext)
                || currentModel == null
                || !has1mContext(currentModel, disable1mContext)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] skillModel 已带[1m] 或 当前非1M会话 → 直接返回原值: {}", skillModel);
            }
            return skillModel;
        }
        // CC model.ts:532 modelSupports1M(parseUserSpecifiedModel(skillModel)) → 拼 [1m]
        //   透传 disable1mContext（CC model.ts:451 has1mTag 经 has1mContext 计算，D14）：
        //   禁用时别名/remap 均不保留 [1m]，与本方法已门控的 has1mContext 保持一致
        String parsed = parseUserSpecifiedModel(skillModel, disable1mContext, firstParty, legacyRemapEnabled, defaultOpus);
        if (modelSupports1M(parsed, disable1mContext)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] 解析后 {} → {} 命中 1M 能力家族 → 顺延 [1m] 后缀: {}",
                        skillModel, parsed, skillModel + "[1m]");
            }
            return skillModel + "[1m]";
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillModelOverrideResolver] 解析后 {} → {} 家族不支持 1M → 原样返回: {}",
                    skillModel, parsed, skillModel);
        }
        return skillModel;
    }

    /**
     * 是否 1M 上下文模型 · 对齐 CC {@code utils/context.ts:35-40} has1mContext：
     * {@code is1mContextDisabled()} 为真恒 false，否则 {@code /\[1m\]/i.test(model)}。
     *
     * @param model 模型字符串（null → false）
     * @param disable1mContext CLAUDE_CODE_DISABLE_1M_CONTEXT 门控（context.ts:31-33/36-38）
     */
    private static boolean has1mContext(String model, boolean disable1mContext) {
        if (disable1mContext) {
            return false;
        }
        return model != null && model.toLowerCase(Locale.ROOT).contains("[1m]");
    }

    /**
     * 用户指定模型规范化 · 对齐 CC parseUserSpecifiedModel（model.ts:445-506）的别名扩展 +
     * legacy opus 首方 remap。
     *
     * <p>实现 CC 别名→canonical 家族映射（比对 {@code modelSupports1M} 前置步骤）：
     * <ul>
     *   <li>trim + 小写判别名；剥尾部 {@code [1m]} 再判（CC model.ts:451-454）</li>
     *   <li>{@code opusplan}→defaultSonnet（model.ts:459）、{@code sonnet}→defaultSonnet（:461）、
     *       {@code haiku}→defaultHaiku（:463）、{@code opus}→defaultOpus（:465）、
     *       {@code best}→getBestModel()→defaultOpus（:467）</li>
     *   <li>legacy opus 首方 remap（model.ts:477-483）：firstParty + 显式 legacy Opus 4.0/4.1
     *       字符串 + remap 未禁用 → 静默 remap 到 {@code getDefaultOpusModel()}（默认 opus-4-6）</li>
     *   <li>非别名 → 保原大小写原样返回（model.ts:500-505，Azure/Foundry 部署 ID 大小写敏感）</li>
     * </ul>
     *
     * <p>本类只消费解析结果的 1M 家族判定（含 {@code opus-4-6}/{@code claude-sonnet-4}），
     * 故 canonical 使用家族标记而非具体 endpoint；具体 endpoint 解析仍在 provider 层。
     *
     * <p>[D-5] 由 private 提升为包可见：供 {@link AgentModelResolver#parseUserSpecifiedModel}
     * 复用（别名展开 + legacy opus remap + [1m] 保真 + 大小写保留，单一真源防双实现漂移，
     * RF-6 ③ 同一模式）。
     *
     * @param model 原始模型字符串
     */
    static String parseUserSpecifiedModel(String model) {
        // CC is1mContextDisabled()（context.ts:31-33）+ getAPIProvider()==='firstParty'（providers.ts:6-14）
        //   + isLegacyModelRemapEnabled（model.ts:551-553）+ getDefaultOpusModel()（model.ts:105-116）
        return parseUserSpecifiedModel(model, is1mContextDisabled(), isFirstParty(),
            isLegacyModelRemapEnabled(), getDefaultOpusModel());
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 firstParty / legacyRemapEnabled / defaultOpus。
     *
     * <p>生产路径 {@link #parseUserSpecifiedModel(String)} 读 env（CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY
     * + CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP）+ DB settings.strongModelId（[W6-1] 原
     * ANTHROPIC_DEFAULT_OPUS_MODEL env 已删）；测试传可控值验证 remap 门控分支（不依赖测试机
     * ambient env）。模式同 {@link AgentModelResolver#resolveWithEnv}（env 注入隔离）。
     *
     * <p>[D14/T1] {@code has1mTag} 门控开关经 {@link #is1mContextDisabled()}（env）注入，
     * 由 {@link #parseUserSpecifiedModel(String, boolean, boolean, boolean, String)} 实际承载 ——
     * 测试如需确定性验证禁用分支，直接调用 5 参重载。
     *
     * @param model              原始模型字符串
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     */
    static String parseUserSpecifiedModel(String model, boolean firstParty, boolean legacyRemapEnabled,
            String defaultOpus) {
        return parseUserSpecifiedModel(model, is1mContextDisabled(), firstParty, legacyRemapEnabled, defaultOpus);
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 disable1mContext + firstParty / legacyRemapEnabled / defaultOpus。
     *
     * <p>[D14/T1] {@code has1mTag}（CC model.ts:451 {@code has1mContext(normalizedModel)}，
     * context.ts:35-40）受 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 门控：禁用时恒 false ——
     * 别名/remap/自定义模型分支均不保留 {@code [1m]} 后缀（HIPAA 关停场景，CC has1mContext
     * 禁用时恒 false）。模式同 {@link #resolveSkillModelOverride(String, String, boolean, boolean, boolean, String)}
     * （env 注入隔离，不依赖测试机 ambient env）。
     *
     * @param model              原始模型字符串
     * @param disable1mContext   CLAUDE_CODE_DISABLE_1M_CONTEXT 门控（CC original: is1mContextDisabled()）
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     */
    static String parseUserSpecifiedModel(String model, boolean disable1mContext, boolean firstParty,
            boolean legacyRemapEnabled, String defaultOpus) {
        if (model == null) {
            return null;
        }
        String modelInputTrimmed = model.trim();
        String normalizedModel = modelInputTrimmed.toLowerCase(Locale.ROOT);
        // CC model.ts:451 has1mTag = has1mContext(normalizedModel) —— 受 CLAUDE_CODE_DISABLE_1M_CONTEXT
        //   门控（D14/T1，context.ts:35-40）；禁用时恒 false，不再直接 contains("[1m]")
        boolean has1mTag = has1mContext(normalizedModel, disable1mContext);
        // CC model.ts:452-454：剥 [1m] 后缀再判别名
        String modelString = has1mTag
            ? normalizedModel.substring(0, normalizedModel.length() - "[1m]".length()).trim()
            : normalizedModel;

        String canonical = aliasCanonical(modelString);
        if (canonical != null) {
            // CC model.ts:458-468：别名 → 各档默认 canonical + 原 [1m] 后缀；
            // 唯一例外 best（:466-467 case 'best': return getBestModel()）不追加 [1m]，CC 原样返回 canonical
            String expanded = "best".equals(modelString)
                ? canonical
                : canonical + (has1mTag ? "[1m]" : "");
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] 别名展开: {} → {}（CC model.ts:458-468，best 不追加 [1m]）", modelString, expanded);
            }
            return expanded;
        }

        // CC model.ts:477-483：legacy opus 首方 remap。Opus 4/4.1 已在首方 API 下线，静默 remap 到
        //   当前 Opus 默认（opus-4-6）；3P 可能尚无 4.6 容量 → 原样通过（CC 注释 model.ts:473-476）。
        //   逻辑抽到 {@link #remapLegacyOpusFirstParty} 供 AgentModelResolver.parseUserSpecifiedModel
        //   复用（RF-6 ③ legacy opus has1mTag，单一真源防双实现漂移）。
        //   透传 disable1mContext（CC :482 has1mTag 同源自 :451 门控值，D14）
        String remapped = remapLegacyOpusFirstParty(model, disable1mContext, firstParty, legacyRemapEnabled, defaultOpus);
        if (remapped != null) {
            log.info("[SkillModelOverrideResolver] legacy opus 首方 remap: {} → {}（CC model.ts:477-483）",
                    model, remapped);
            return remapped;
        }

        // CC model.ts:485-498：USER_TYPE=ant 分支 —— 把 tengu_ant_model_override 配置的 antModels
        //   映射（别名/codename → 内部 model）接入解析。命中时返回 antModel.model + (has1mAntTag?'[1m]':'')；
        //   未命中落到 "Fall through to the alias string"（model.ts:495-498 注释）——API 调用将用该
        //   字符串失败，但会经反馈机制暴露，可重启/等 flag cache 刷新。仅 ant 内部环境生效，
        //   外部构建 USER_TYPE!=ant → 恒不触发。
        //   has1mAntTag（:486 has1mContext(normalizedModel)）与 :451 has1mTag 同源同门控值 → 直接复用 has1mTag；
        //   baseAntModel（:487 normalizedModel.replace(/\[1m]$/i,'').trim()）无条件剥 [1m]（区别于
        //   :452-454 modelString 仅 has1mTag 时剥）——故不复用 modelString，独立计算对齐 CC 无条件剥。
        if ("ant".equals(ENV_READER.apply("USER_TYPE"))) {
            String baseAntModel = normalizedModel.replaceAll("(?i)\\[1m\\]$", "").trim();
            AntModels.AntModel antModel = AntModels.resolveAntModel(baseAntModel, "ant", ANT_MODEL_OVERRIDE_SUPPLIER);
            if (antModel != null) {
                String resolved = antModel.model() + (has1mTag ? "[1m]" : "");
                if (log.isDebugEnabled()) {
                    log.debug("[SkillModelOverrideResolver] USER_TYPE=ant resolveAntModel 命中: {} → {}（CC model.ts:485-498）",
                            baseAntModel, resolved);
                }
                return resolved;
            }
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] USER_TYPE=ant resolveAntModel 未命中 {}（无配置/无匹配 → 落到 alias 串，CC model.ts:495-498）",
                        baseAntModel);
            }
        }

        // CC model.ts:500-505：非别名保原大小写，仅尾部 [1m] 原样保留
        if (has1mTag) {
            String base = modelInputTrimmed;
            if (base.regionMatches(true, base.length() - "[1m]".length(), "[1m]", 0, "[1m]".length())) {
                base = base.substring(0, base.length() - "[1m]".length()).trim();
            }
            return base + "[1m]";
        }
        return modelInputTrimmed;
    }

    /**
     * CC 别名 → canonical 家族标记 · 对齐 CC parseUserSpecifiedModel switch（model.ts:457-469）。
     *
     * @param modelString 小写、已剥 [1m] 的别名串
     * @return 命中别名的 canonical 家族标记；非别名返回 null
     */
    private static String aliasCanonical(String modelString) {
        return switch (modelString) {
            case "opusplan" -> CANONICAL_DEFAULT_SONNET; // CC model.ts:459 Sonnet 默认
            case "sonnet" -> CANONICAL_DEFAULT_SONNET;   // CC model.ts:461
            case "haiku" -> CANONICAL_DEFAULT_HAIKU;     // CC model.ts:463
            case "opus" -> CANONICAL_DEFAULT_OPUS;       // CC model.ts:465
            case "best" -> CANONICAL_DEFAULT_OPUS;       // CC model.ts:467 getBestModel()→defaultOpus
            default -> null;
        };
    }

    /**
     * 模型家族是否支持 1M 上下文 · 对齐 CC {@code utils/context.ts:43-49} modelSupports1M：
     * {@code is1mContextDisabled()} 为真恒 false，否则
     * {@code getCanonicalName(model)} 含 {@code 'claude-sonnet-4'} 或 {@code 'opus-4-6'}。
     *
     * <p><b>删旧补偿（deletion-manifest #21）</b>: 旧实现手补 {@code equals("opus")/("sonnet")}
     * + {@code contains("sonnet-4")} —— 现 {@link #parseUserSpecifiedModel} 已把裸别名解析成
     * canonical，此层只需精确匹配 CC 家族子串（裸 {@code sonnet-4-5} 不匹配
     * {@code 'claude-sonnet-4'}，正确不顺延）。
     *
     * @param skillModel 解析后的技能模型（canonical 家族标记或完整 ID）
     * @param disable1mContext CLAUDE_CODE_DISABLE_1M_CONTEXT 门控（context.ts:44-46）
     */
    private static boolean modelSupports1M(String skillModel, boolean disable1mContext) {
        if (disable1mContext) {
            return false;
        }
        if (skillModel == null) {
            return false;
        }
        // CC getCanonicalName（model.ts:279-283 + :264-269 regex）：剥 [1m] + 小写归一
        String canonical = canonicalName(skillModel);
        return canonical.contains("claude-sonnet-4") || canonical.contains("opus-4-6");
    }

    /**
     * canonical 归一 · 简化对齐 CC getCanonicalName（model.ts:264-269 regex 匹配
     * {@code claude-(\d+-\d+-)?\w+} 前缀）：trim + 小写 + 剥尾部 [1m]。
     * 本类输入为 canonical 家族标记或完整 ID，无 Bedrock ARN/区域前缀，regex→前缀等价于
     * 剥 [1m]，故 Java 简化无需完整 regex。
     */
    private static String canonicalName(String model) {
        String m = model.trim().toLowerCase(Locale.ROOT);
        if (m.endsWith("[1m]")) {
            m = m.substring(0, m.length() - "[1m]".length()).trim();
        }
        return m;
    }

    /**
     * 1M 上下文是否全局禁用 · 对齐 CC is1mContextDisabled（context.ts:31-33）：
     * {@code isEnvTruthy(CLAUDE_CODE_DISABLE_1M_CONTEXT)}。
     */
    private static boolean is1mContextDisabled() {
        return isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_1M_CONTEXT"));
    }

    /**
     * env truthy 判定 · 对齐 CC isEnvTruthy（utils/envUtils.ts:32-37）：
     * 值（lowercase+trim）∈ {1, true, yes, on} 为真，其余（含 null/空）为假。
     */
    private static boolean isEnvTruthy(String envVar) {
        if (envVar == null) {
            return false;
        }
        String normalized = envVar.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }

    /**
     * 是否 legacy opus 显式字符串 · 对齐 CC isLegacyOpusFirstParty（model.ts:545-547）：
     * {@code LEGACY_OPUS_FIRSTPARTY.includes(model)} 精确相等（非前缀）。
     *
     * @param model 已小写、已剥 [1m] 的模型串（CC original: modelString，model.ts:479）
     */
    private static boolean isLegacyOpusFirstParty(String model) {
        return LEGACY_OPUS_FIRSTPARTY.contains(model);
    }

    /**
     * legacy opus remap 是否启用 · 对齐 CC isLegacyModelRemapEnabled（model.ts:551-553）：
     * {@code !isEnvTruthy(CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP)}（opt-out 开关）。
     */
    private static boolean isLegacyModelRemapEnabled() {
        return !isEnvTruthy(System.getenv("CLAUDE_CODE_DISABLE_LEGACY_MODEL_REMAP"));
    }

    /**
     * 是否 firstParty provider · 对齐 CC getAPIProvider()==='firstParty'（providers.ts:6-14）：
     * 无 CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY 任一为真 → firstParty。
     */
    private static boolean isFirstParty() {
        return !isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))
            && !isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))
            && !isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"));
    }

    // [W6-1] 强档(opus)模型来源 · static volatile Supplier（同 TeamMemoryHttpClient.baseUrlSource
    //   W4-1 模式）：默认 null（未安装 DB 源）→ getDefaultOpusModel() 回落 CANONICAL_DEFAULT_OPUS。
    //   Spring 侧经 {@link #installDefaultOpusSource}（由 SkillToolImpl @Autowired 安装）读 DB
    //   settings.strongModelId；测试可直接 {@link #setDefaultOpusModelSource} 注入。

    /** [W6-1] 强档模型来源 · settings.strongModelId（V25 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> defaultOpusModelSource = () -> null;

    /**
     * 默认 Opus 模型 · 对齐 CC getDefaultOpusModel（model.ts:105-116）：
     * DB settings.strongModelId（CC env {@code ANTHROPIC_DEFAULT_OPUS_MODEL} 的 DB 承载，[W6-1]
     * env 路删除）非 blank → 用之；否则默认 {@code claude-opus-4-6}。Java 同
     * PromptCaching.DEFAULT_OPUS（本类常量 CANONICAL_DEFAULT_OPUS 同值）。
     */
    private static String getDefaultOpusModel() {
        String fromSettings = defaultOpusModelSource.get();
        if (fromSettings != null && !fromSettings.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillModelOverrideResolver] 强档模型 DB settings.strongModelId 命中: {}（[W6-1]）",
                    fromSettings);
            }
            return fromSettings;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillModelOverrideResolver] 强档模型 DB settings.strongModelId 未配置, 回落默认: {}（[W6-1]）",
                CANONICAL_DEFAULT_OPUS);
        }
        return CANONICAL_DEFAULT_OPUS;
    }

    /**
     * [W6-1] 注入强档模型来源 · 测试/部署 seam（同 TeammateModeSnapshot#setConfigTeammateModeSupplier）。
     *
     * @param source DB settings.strongModelId 反查后的 models.name；null → 重置为恒 null（回落默认）
     */
    public static void setDefaultOpusModelSource(Supplier<String> source) {
        defaultOpusModelSource = source != null ? source : () -> null;
    }

    /**
     * [W6-1] 安装强档模型 DB 来源 · 注入 {@link ModelConfigResolver}（内含 SettingsMapper，读 settings
     * 单例行 id=1）后将 {@link #defaultOpusModelSource} 切换为 DB settings.strongModelId 反查。
     * 由 Spring 侧接线（SkillToolImpl#setModelConfigResolver）调用；未注入 → 保持默认 null（回落
     * CANONICAL_DEFAULT_OPUS，等价 CC env 未设）。
     */
    public static void installDefaultOpusSource(ModelConfigResolver modelConfigResolver) {
        if (modelConfigResolver != null) {
            defaultOpusModelSource = () -> modelConfigResolver.settingsTierModelName(SettingsRecord::getStrongModelName);
            log.info("SkillModelOverrideResolver: ANTHROPIC_DEFAULT_OPUS_MODEL env 路删除，"
                + "强档模型改读 DB settings.strong_model_name（[W6-1][FN2] 字段改名）");
        }
    }

    /**
     * legacy opus 首方 remap（[1m] 保真）· 对齐 CC model.ts:477-483。
     *
     * <p>生产入口（读 env：firstParty / legacyRemapEnabled；defaultOpus 改读 DB settings.strongModelId，
     * [W6-1] env 路已删），供 {@link AgentModelResolver#parseUserSpecifiedModel} 复用
     * （RF-6 ③ legacy opus has1mTag）：
     * 显式 legacy Opus 4.0/4.1 字符串（firstParty + remap 未禁用）→ 静默 remap 到 defaultOpus，
     * [1m] 后缀顺延（CC model.ts:483 {@code getDefaultOpusModel() + (has1mTag ? '[1m]' : '')}）。
     *
     * @param model 原始模型字符串
     * @return remap 后的模型串；非 legacy opus / 非 firstParty / remap 禁用 → {@code null}
     *         （调用方保留原值）
     */
    static String remapLegacyOpusFirstParty(String model) {
        return remapLegacyOpusFirstParty(model, is1mContextDisabled(), isFirstParty(),
            isLegacyModelRemapEnabled(), getDefaultOpusModel());
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 legacy opus remap 门控三参。
     *
     * <p>模式同 {@link #parseUserSpecifiedModel(String, boolean, boolean, String)}（env 注入隔离，
     * 不依赖测试机 ambient env）。
     *
     * <p>[D14/T1] {@code has1mTag} 门控开关经 {@link #is1mContextDisabled()}（env）注入，
     * 由 {@link #remapLegacyOpusFirstParty(String, boolean, boolean, boolean, String)} 实际承载。
     *
     * @param model              原始模型字符串
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     * @return remap 后的模型串；非 remap 条件 → {@code null}
     */
    static String remapLegacyOpusFirstParty(String model, boolean firstParty, boolean legacyRemapEnabled,
            String defaultOpus) {
        return remapLegacyOpusFirstParty(model, is1mContextDisabled(), firstParty, legacyRemapEnabled, defaultOpus);
    }

    /**
     * 包可见重载（测试隔离）· 显式注入 disable1mContext + legacy opus remap 门控三参。
     *
     * <p>[D14/T1] remap 的 {@code has1mTag}（CC model.ts:482 同源自 :451 门控值）受
     * {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 门控：禁用时恒 false，remap 结果不保留
     * {@code [1m]} 后缀（context.ts:35-40 语义）。模式同 {@link #parseUserSpecifiedModel(String, boolean, boolean, boolean, String)}。
     *
     * @param model              原始模型字符串
     * @param disable1mContext   CLAUDE_CODE_DISABLE_1M_CONTEXT 门控（CC original: is1mContextDisabled()）
     * @param firstParty         是否 firstParty provider（CC original: getAPIProvider()==='firstParty'）
     * @param legacyRemapEnabled 是否启用 legacy opus remap（CC original: isLegacyModelRemapEnabled()）
     * @param defaultOpus        默认 Opus 模型（CC original: getDefaultOpusModel()，model.ts:483）
     * @return remap 后的模型串；非 remap 条件 → {@code null}
     */
    static String remapLegacyOpusFirstParty(String model, boolean disable1mContext, boolean firstParty,
            boolean legacyRemapEnabled, String defaultOpus) {
        if (model == null) {
            return null;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        // CC model.ts:482 has1mTag 同源自 :451 has1mContext 门控值（D14/T1）——禁用时恒 false
        boolean has1mTag = has1mContext(normalized, disable1mContext);
        String base = has1mTag
            ? normalized.substring(0, normalized.length() - "[1m]".length()).trim()
            : normalized;
        // CC model.ts:477-483：firstParty && isLegacyOpusFirstParty(modelString) && remapEnabled
        if (firstParty && isLegacyOpusFirstParty(base) && legacyRemapEnabled) {
            return defaultOpus + (has1mTag ? "[1m]" : "");
        }
        return null;
    }
}

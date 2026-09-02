package com.nexusai.application.agent.prompt;

import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.StructuredOutputsSupport;
import com.nexusai.repository.settings.entity.SettingsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Prompt caching 门控 · 对齐 CC {@code getPromptCachingEnabled}
 * （CC original: {@code getPromptCachingEnabled(model: string): boolean}
 * (Open-ClaudeCode/src/services/api/claude.ts:333-354)）。
 *
 * <p>发送边界组件（IMP-SP-06）· 决定是否给 system blocks 附加 {@code cache_control}：
 * <ol>
 *   <li>全局开关 {@code DISABLE_PROMPT_CACHING} 为真 → false（claude.ts:335-336）</li>
 *   <li>{@code DISABLE_PROMPT_CACHING_HAIKU} 为真且 model == smallFastModel → false（:338-343）</li>
 *   <li>{@code DISABLE_PROMPT_CACHING_SONNET} 为真且 model == defaultSonnet → false（:345-350）</li>
 *   <li>{@code DISABLE_PROMPT_CACHING_OPUS} 为真且 model == defaultOpus → false（:352-356）</li>
 *   <li>默认 → true</li>
 * </ol>
 *
 * <p><b>env truthy 语义</b>: {@code isEnvTruthy}（envUtils.ts:32-37）——{@code '1'/'true'/'yes'/'on'}
 * 视为真，其余假。
 *
 * <p><b>[W7-2] 模型档位来源</b>: 三档模型名（smallFast/sonnet/opus）<b>不再读 env</b>
 * （{@code ANTHROPIC_SMALL_FAST_MODEL} / {@code ANTHROPIC_DEFAULT_SONNET_MODEL} /
 * {@code ANTHROPIC_DEFAULT_OPUS_MODEL} env 路已删，用户拍板），改读 DB settings 档位字段
 * （fastModelId/mediumModelId/strongModelId）并经 {@link ModelConfigResolver#settingsTierModelName}
 * 反查为 DB models.name——对齐 CC {@code getSmallFastModel/getDefaultSonnetModel/getDefaultOpusModel}
 * （model.ts:36-38/119-130/105-116）。Java 端默认值登记见 {@link #DEFAULT_HAIKU} /
 * {@link #DEFAULT_SONNET} / {@link #DEFAULT_OPUS}。
 */
@Component
public class PromptCaching {

    /** Java 默认 smallFastModel · CC getDefaultHaikuModel → getModelStrings().haiku45。
     *  [IMP-SP-08 correction 5] 'claude-haiku-4-5' → 'claude-haiku-4-5-20251001'
     *  （CC constants/prompts.ts:124 haiku: 'claude-haiku-4-5-20251001' + utils/model/configs.ts:31
     *  firstParty: 'claude-haiku-4-5-20251001'）。 */
    public static final String DEFAULT_HAIKU = "claude-haiku-4-5-20251001";

    /** Java 默认 defaultSonnetModel · CC getDefaultSonnetModel（model.ts:119-130）。 */
    public static final String DEFAULT_SONNET = "claude-sonnet-4-6";

    /** Java 默认 defaultSonnetModel · CC getDefaultSonnetModel 3P 分支（model.ts:124-126）
     *  → getModelStrings().sonnet45 → configs.ts:44 firstParty 'claude-sonnet-4-5-20250929'。 */
    public static final String DEFAULT_SONNET_45 = "claude-sonnet-4-5-20250929";

    /** Java 默认 defaultOpusModel · CC getDefaultOpusModel（model.ts:105-116）。 */
    public static final String DEFAULT_OPUS = "claude-opus-4-6";

    private static final Logger log = LoggerFactory.getLogger(PromptCaching.class);

    // [W7-2] 三档模型来源 · static volatile Supplier（同 SkillImprovementHook.smallFastModelSource
    //   W6-1 模式 + SkillModelOverrideResolver.defaultOpusModelSource）：默认 null（未注入
    //   ModelConfigResolver）→ 回落 DEFAULT_* 常量。Spring 侧 {@link #setModelConfigResolver}
    //   （@Autowired(required=false)）安装 DB settings 读取（fastModelId/mediumModelId/strongModelId）。

    /** [W7-2] 小快档模型来源 · settings.fastModelId（V1 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> smallFastSource = () -> null;
    /** [W7-2] 中档(sonnet)模型来源 · settings.mediumModelId（V25 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> sonnetSource = () -> null;
    /** [W7-2] 强档(opus)模型来源 · settings.strongModelId（V25 列，models.id/全名 → models.name 反查）。 */
    static volatile Supplier<String> opusSource = () -> null;

    /** [G-20] 3P 分流 baseUrl 判定用共享解析器 · {@link #setModelConfigResolver} 注入（DB provider baseUrl
     *  来源，FindRelevantMemories 同源探针）。null = 测试/孤立运行 → baseUrl 判定不可得，回落 firstParty 默认。 */
    static volatile ModelConfigResolver modelConfigResolver;

    /** Spring 实例化 · 静态工具类按 @Component 托管仅用于 @Autowired(required=false) 安装 DB 来源。 */
    public PromptCaching() {}

    /**
     * 计算 prompt caching 是否启用 · 对齐 CC {@code getPromptCachingEnabled}（claude.ts:333-354）。
     *
     * @param model 本次 call 的模型名（可为 null → 跳过模型级判定，仅全局开关生效）
     * @return true 时 buildSystemPromptBlocks 才附加 cache_control
     */
    public static boolean getPromptCachingEnabled(String model) {
        if (isEnvTruthy(System.getenv("DISABLE_PROMPT_CACHING"))) {
            return false;
        }
        if (isEnvTruthy(System.getenv("DISABLE_PROMPT_CACHING_HAIKU"))) {
            if (Objects.equals(model, smallFastModel())) return false;
        }
        if (isEnvTruthy(System.getenv("DISABLE_PROMPT_CACHING_SONNET"))) {
            if (Objects.equals(model, defaultSonnetModel())) return false;
        }
        if (isEnvTruthy(System.getenv("DISABLE_PROMPT_CACHING_OPUS"))) {
            if (Objects.equals(model, defaultOpusModel())) return false;
        }
        return true;
    }

    /**
     * [W7-2] CC getSmallFastModel · DB settings.fastModelId 覆盖，默认 haiku（model.ts:36-38）。
     * env {@code ANTHROPIC_SMALL_FAST_MODEL} 路已删（用户拍板）→ smallFastSource（DB 反查名）。
     */
    static String smallFastModel() {
        String fromDb = smallFastSource.get();
        if (fromDb != null && !fromDb.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("PromptCaching: smallFastModel 命中 DB settings.fastModelId 反查名={}（CC model.ts:36-38，[W7-2]）",
                    fromDb);
            }
            return fromDb;
        }
        if (log.isDebugEnabled()) {
            log.debug("PromptCaching: smallFastModel DB 未配置/未命中，回落默认 {}（CC model.ts:36-38，[W7-2]）",
                DEFAULT_HAIKU);
        }
        return DEFAULT_HAIKU;
    }

    /**
     * [W7-2] CC getDefaultSonnetModel · DB settings.mediumModelId 覆盖，默认 sonnet-4-6（model.ts:119-130）。
     * env {@code ANTHROPIC_DEFAULT_SONNET_MODEL} 路已删（用户拍板）→ sonnetSource（DB 反查名）。
     *
     * <p>[G-7] 提升 public：{@link com.nexusai.application.agent.subagent.AgentModelResolver}
     * （getRuntimeMainLoopModel 的 haiku+plan → defaultSonnet 分支，CC model.ts:161-164）跨包消费，
     * 单一真源防双实现漂移（PromptCaching 为三档模型默认的 W7-2 权威源）。
     *
     * <p>[G-20] DB 未配置时按 provider baseUrl 分流（CC model.ts:124-127：3P → sonnet45，否则 sonnet46）：
     * 复用 {@link StructuredOutputsSupport#isFirstPartyAnthropicBaseUrl(String)} 判定，同
     * FindRelevantMemories#resolveDefaultSonnetModelName 探针模式；baseUrl 判定不可得 → 回落 sonnet46 登记。
     */
    public static String defaultSonnetModel() {
        String fromDb = sonnetSource.get();
        if (fromDb != null && !fromDb.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("PromptCaching: defaultSonnetModel 命中 DB settings.mediumModelId 反查名={}（CC model.ts:119-130，[W7-2]）",
                    fromDb);
            }
            return fromDb;
        }
        return resolveDefaultSonnetDefault(modelConfigResolver);
    }

    /**
     * [G-20] DB settings.mediumModelId 未配置时按 provider baseUrl 分流默认（CC getDefaultSonnetModel
     * model.ts:124-127：{@code getAPIProvider() !== 'firstParty' → sonnet45}，否则 sonnet46）。
     *
     * <p>Java 以 DB provider baseUrl 为 firstParty 代理判定（对齐 FindRelevantMemories#resolveDefaultSonnetModelName
     * 的探针模式）：复用 {@link StructuredOutputsSupport#isFirstPartyAnthropicBaseUrl(String)}——
     * baseUrl 空/默认 api.anthropic.com → first-party；自定义 URL（LiteLLM 代理 / Bedrock / Vertex）→ 3P。
     * （注：复用共享 infra 的 contains 子串判定，[B-2 登记 · IMP-MV2-40] 与 FindRelevantMemories 内联判定同源。）
     *
     * <p><b>baseUrl 判定不可得（登记）</b>：resolver null（测试/孤立运行，未注入）或
     * {@code DEFAULT_SONNET}/{@code DEFAULT_SONNET_45} 双探针均解析失败（DB 无 sonnet 系）→ 回落
     * firstParty 默认 sonnet46；双探针失败 log.warn 显式暴露（fail-loud，G-1 同款不经 fast 链语义）。
     *
     * @param resolver 共享解析器（可 null）
     * @return sonnet46（firstParty / 判定不可得）· sonnet45（3P）
     */
    static String resolveDefaultSonnetDefault(ModelConfigResolver resolver) {
        if (resolver == null) {
            if (log.isDebugEnabled()) {
                log.debug("PromptCaching: baseUrl 判定不可得（ModelConfigResolver 未注入），回落 firstParty 默认 {}（[G-20] 登记）",
                    DEFAULT_SONNET);
            }
            return DEFAULT_SONNET;
        }
        ModelConfigResolver.ResolvedModel rm = resolver.resolve(DEFAULT_SONNET);
        if (rm == null || rm.config() == null) {
            // 3P 可能仅有 sonnet45（CC model.ts:124-126 "3P may not have 4.6 yet"）→ 次探针
            rm = resolver.resolve(DEFAULT_SONNET_45);
        }
        if (rm == null || rm.config() == null) {
            // 探针全失败（DB 无 sonnet 系）→ baseUrl 判定不可得 → 回落 firstParty 默认 sonnet46（登记）
            log.warn("PromptCaching: medium 档未配置且探针 {} / {} 均解析失败，baseUrl 判定不可得，回落默认 {}（[G-20] 登记，fail-loud）",
                DEFAULT_SONNET, DEFAULT_SONNET_45, DEFAULT_SONNET);
            return DEFAULT_SONNET;
        }
        boolean firstParty = StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(rm.config().baseUrl());
        if (log.isDebugEnabled()) {
            log.debug("PromptCaching: medium 档未配置，按 provider 分流: {} → {}（CC getDefaultSonnetModel model.ts:124-127，[G-20]）",
                firstParty ? "firstParty" : "3P",
                firstParty ? DEFAULT_SONNET : DEFAULT_SONNET_45);
        }
        return firstParty ? DEFAULT_SONNET : DEFAULT_SONNET_45;
    }

    /**
     * [W7-2] CC getDefaultOpusModel · DB settings.strongModelId 覆盖，默认 opus-4-6（model.ts:105-116）。
     * env {@code ANTHROPIC_DEFAULT_OPUS_MODEL} 路已删（用户拍板）→ opusSource（DB 反查名）。
     *
     * <p>[G-7] 提升 public：{@link com.nexusai.application.agent.subagent.AgentModelResolver}
     * （getRuntimeMainLoopModel 的 opusplan+plan → defaultOpus 分支，CC model.ts:152-159）跨包消费，
     * 单一真源防双实现漂移。
     */
    public static String defaultOpusModel() {
        String fromDb = opusSource.get();
        if (fromDb != null && !fromDb.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("PromptCaching: defaultOpusModel 命中 DB settings.strongModelId 反查名={}（CC model.ts:105-116，[W7-2]）",
                    fromDb);
            }
            return fromDb;
        }
        if (log.isDebugEnabled()) {
            log.debug("PromptCaching: defaultOpusModel DB 未配置/未命中，回落默认 {}（CC model.ts:105-116，[W7-2]）",
                DEFAULT_OPUS);
        }
        return DEFAULT_OPUS;
    }

    /**
     * [W7-2] 安装三档模型 DB 来源 · 注入 {@link ModelConfigResolver}（内含 SettingsMapper，读 settings
     * 单例行 id=1）后将 {@link #smallFastSource}/{@link #sonnetSource}/{@link #opusSource} 切换为
     * DB settings.fastModelId/mediumModelId/strongModelId 反查（ANTHROPIC_* env 路删除，用户拍板）。
     * {@code @Autowired(required=false)}：测试/孤立运行不注入 → 保持默认 null（回落 DEFAULT_*）。
     * 同 SkillImprovementHook#setModelConfigResolver / SkillModelOverrideResolver#installDefaultOpusSource
     * 的 W6-1 注入风格。
     */
    @Autowired(required = false)
    public void setModelConfigResolver(ModelConfigResolver injectedResolver) {
        if (injectedResolver != null) {
            modelConfigResolver = injectedResolver;
            smallFastSource = () -> injectedResolver.settingsTierModelName(SettingsRecord::getFastModelName);
            sonnetSource = () -> injectedResolver.settingsTierModelName(SettingsRecord::getMediumModelName);
            opusSource = () -> injectedResolver.settingsTierModelName(SettingsRecord::getStrongModelName);
            log.info("PromptCaching: ANTHROPIC_SMALL_FAST_MODEL/ANTHROPIC_DEFAULT_SONNET_MODEL/"
                + "ANTHROPIC_DEFAULT_OPUS_MODEL env 路删除，三档模型改读 DB settings.fast_model_name/"
                + "medium_model_name/strong_model_name（[W7-2][FN2] 字段改名）");
        }
    }

    /** CC isEnvTruthy（envUtils.ts:32-37）· '1'/'true'/'yes'/'on' 视为真。 */
    static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }
}

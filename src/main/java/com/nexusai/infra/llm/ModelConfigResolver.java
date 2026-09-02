package com.nexusai.infra.llm;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 共享配置解析器 · modelName → (ProviderConfig, providerType) 单一来源。
 *
 * <p>对齐 CC 语义（Open-ClaudeCode/src/utils/model/model.ts + findRelevantMemories.ts:99 +
 * magicDocs.ts:104）：模型名经 DB enabled model → enabled provider → 解密 apiKey 解析出真实
 * 运行配置。不可用 → <b>warn + skip（返回 null），绝不构造 mock</b>（对齐
 * HookRegistry.resolvePromptProvider 语义，RV14B-GATE-01）。
 *
 * <p>[DEC-RV-14b] 解决 7 处生产恒 mock 站点（YoloClassifierImpl / FindRelevantMemories /
 * MagicDocUpdater / LlmAgentLoop:4954 / AgentLoopContext:663 / :1753 /
 * HaikuToolUseSummaryGenerator:95）的配置获取，替代各处手写 empty()/null 喂给
 * {@link LlmProviderFactory} 导致的恒 mock 根因（LlmProviderFactory:47-49）。
 *
 * <p>与 {@link com.nexusai.application.chat.ChatService#buildConfigForModel} 语义一致，
 * 但采用 warn+skip（null）而非抛异常——解析失败由调用方按调用链既定失败契约处理，
 * 不落 mock。ChatService 保留自身实现（抛异常契约被标题生成 catch），不强行融合（规则七）。
 */
@Component
public class ModelConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigResolver.class);

    /** 解析结果载体 · (真实 config, provider type) · CC original 语义：model → provider 路由。 */
    public record ResolvedModel(ProviderConfig config, String providerType) {}

    private static final int SETTINGS_SINGLETON_ID = 1;

    @Autowired private ModelMapper modelMapper;
    @Autowired private ProviderMapper providerMapper;
    @Autowired private SettingsMapper settingsMapper;
    @Autowired private ProviderService providerService;

    /**
     * [G-4] 安装 {@link ModelNameResolver} 别名展开档位来源 · settings strong/medium/weakModelName
     * 反查（{@link #settingsTierModelName} 全名/裸名反查唯一路径，[FN2]）→ DB models.name。
     * 未安装（测试/孤立运行）→ ModelNameResolver 回落 CC canonical 默认（claude-opus-4-6 等），
     * 等价 CC env 未设。同 PromptCaching#setModelConfigResolver（[W7-2]）/ SkillModelOverrideResolver
     * #installDefaultOpusSource（[W6-1]）的 DB settings 来源安装风格。
     */
    @PostConstruct
    public void installModelNameResolverTierSources() {
        ModelNameResolver.installTierSources(
            () -> settingsTierModelName(SettingsRecord::getStrongModelName),
            () -> settingsTierModelName(SettingsRecord::getMediumModelName),
            () -> settingsTierModelName(SettingsRecord::getWeakModelName));
        log.info("ModelConfigResolver: [G-4] ModelNameResolver 别名展开档位来源已安装"
            + "（settings strong/medium/weak_model_name → DB models.name）");
    }

    /**
     * modelName → 真实 (config, providerType)。
     *
     * <p>任一步不可用 → warn + 返回 null（<b>不构造 mock</b>）：
     * <ol>
     *   <li>modelName null/blank</li>
     *   <li>enabled model 未命中（models.name 精确匹配）</li>
     *   <li>provider 缺失 / 未 enabled</li>
     *   <li>apiKey 解密 null/blank</li>
     * </ol>
     *
     * @param modelName DB models.name 精确匹配的模型名（非配置名/字面量）
     * @return 真实 (config, providerType)；不可用 → null
     */
    public ResolvedModel resolve(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            log.warn("[ModelConfigResolver] modelName 为空，跳过解析（warn+skip 不落 mock）");
            return null;
        }
        ModelRecord model = findEnabledModelByName(modelName);
        if (model == null) {
            log.warn("[ModelConfigResolver] enabled model 未命中 modelName={}，跳过（warn+skip）", modelName);
            return null;
        }
        ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
        if (provider == null) {
            log.warn("[ModelConfigResolver] provider 缺失 modelName={} providerId={}，跳过（warn+skip）",
                modelName, model.getProviderId());
            return null;
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            log.warn("[ModelConfigResolver] provider 未 enabled modelName={} providerId={}，跳过（warn+skip）",
                modelName, model.getProviderId());
            return null;
        }
        String rawKey = providerService.getDecryptedApiKey(provider.getId());
        if (rawKey == null || rawKey.isBlank()) {
            log.warn("[ModelConfigResolver] provider apiKey 解密为空 modelName={} providerId={}，跳过（warn+skip）",
                modelName, model.getProviderId());
            return null;
        }
        String providerType = provider.getType() != null ? provider.getType() : "openai_compatible";
        if (log.isInfoEnabled()) {
            log.info("[ModelConfigResolver] 解析成功 modelName={} providerType={} baseUrl={}",
                modelName, providerType, provider.getBaseUrl());
        }
        return new ResolvedModel(new ProviderConfig(provider.getBaseUrl(), rawKey), providerType);
    }

    /**
     * [FIX-STRIP-PREFIX] 解析<b>裸模型名</b>（供 SDK model 参数）· 全名 providerName/modelName →
     * DB models.name（裸名）；裸名/别名未命中 → null（调用方回落原始值透传）。
     *
     * <p><b>WHY（剥 provider 前缀）</b>：前端传全名 {@code deepseek/deepseek-v4-flash}（settings 存
     * 全名/裸名，ModelPickerModal fullName=providerName/modelName），若直接传给 SDK 的
     * {@code model} 参数 → API 400（"supported API model names are deepseek-v4-flash..."）。
     * {@link ModelNameResolver#resolve} 已能按全名反查 {@link ModelRecord}，此处取其
     * {@code getName()}（裸名）回传，由 {@link com.nexusai.application.agent.loop.ModelCaller} 等
     * provider.stream 调用点替换发送名（CC 端 model 恒为裸名，无 provider 前缀概念）。
     *
     * @param modelName 模型全名（providerName/modelName）或裸模型名
     * @return DB models.name（裸名）；未命中 / 不可用 → null
     */
    public String resolveSdkModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        ModelRecord model = findEnabledModelByName(modelName);
        if (model == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] resolveSdkModelName 未命中 modelName={} → null（调用方回落原始值）",
                    modelName);
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] resolveSdkModelName: {} → 裸名 {}（剥 provider 前缀，SDK model 参数用裸名）",
                modelName, model.getName());
        }
        return model.getName();
    }

    /**
     * [MAINCHAIN-01] 共享 providerType 解析 · 单一来源（loop 直路 + ModelCaller deps 路共用）。
     *
     * <p>语义：{@code resolver.resolve(modelName)} 成功 → {@code providerType}（provider.type null →
     * "openai_compatible"）；resolver null / resolve null → null（调用方以 2 参工厂默认 openai_sdk
     * 回落，等价既有 1 参行为——不抛异常、不落 mock 文本进模型）。resolve 内部失败已自带 warn 日志。
     *
     * @param resolver  共享解析器（可空 → null）
     * @param modelName 模型名（params.modelName() / request.modelName()）
     * @return providerType（"openai_compatible" / "anthropic" / …）；不可用 → null
     */
    public static String resolveProviderType(com.nexusai.infra.llm.ModelConfigResolver resolver, String modelName) {
        if (resolver == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        ResolvedModel r = resolver.resolve(modelName);
        return r != null ? r.providerType() : null;
    }

    /**
     * 解析 <b>弱档（小快）模型</b> 名 · 完整三级回退 fast→weak→fallback。
     *
     * <p>[W7-1] 对齐 CC model.ts {@code getSmallFastModel}（model.ts:36-38：
     * {@code ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()}）+ {@code getDefaultHaikuModel}
     * （model.ts:131-138：{@code ANTHROPIC_DEFAULT_HAIKU_MODEL || haiku45}），完整三级：
     * <ol>
     *   <li><b>settings.fastModelName</b>（DB 承载 CC {@code ANTHROPIC_SMALL_FAST_MODEL}，
     *       model.ts:36-38；[FN2] 存全名/裸名）→ {@link #settingsTierModelName} 全名反查 → 命中返回</li>
     *   <li><b>settings.weakModelName</b>（DB 承载 CC {@code ANTHROPIC_DEFAULT_HAIKU_MODEL}，
     *       model.ts:132-134；[FN2] 存全名/裸名）→ {@link #settingsTierModelName} 全名反查 →
     *       命中返回（[W7-1] 新增第二级）</li>
     *   <li><b>fallbackModelName</b>（调用方传入固定默认，如 {@code claude-haiku-4-5-20251001}，
     *       等价 CC haiku45 兜底，model.ts:137）</li>
     * </ol>
     * 两档 settings 字段均经 {@link #settingsTierModelName} 全名反查唯一路径（[FN2]，同
     * installTierSources 来源）反查为 DB models.name（裸名，供 {@link #resolve} 精确匹配）。
     * <b>不回退 settings.mainModelName</b>（W2-2 已按 CC 语义移除——弱档未配置时用固定默认，
     * 避免弱档静默升级成中档主模型）。
     *
     * <p><b>[F3C-MODEL] 语义澄清</b>：本方法是<b>小快模型</b>（CC {@code getSmallFastModel}，
     * 供 memory/magicDocs 等后台轻量调用），<b>不是主循环模型</b>（CC {@code getMainLoopModel}，
     * model.ts:92-98 五层解析）。explainer / 主循环模型源请走 {@code LlmAgentLoop.getModelForCall}
     * 落盘的 {@code AgentState.currentModel}，切勿再误接本方法。
     *
     * @param fallbackModelName 固定默认模型名（fast/weak 档均未配置时使用，如 haiku45 字面量）
     * @return DB 可用的弱档模型名；fast/weak 档均未命中 → 固定默认
     */
    public String resolveFastModelName(String fallbackModelName) {
        // ① settings.fastModelName（[FN2] 存全名/裸名）· CC ANTHROPIC_SMALL_FAST_MODEL（model.ts:36-38）
        //   经 settingsTierModelName 全名/裸名反查唯一路径（[FN2]，同 installTierSources 来源）
        String fastName = settingsTierModelName(SettingsRecord::getFastModelName);
        if (fastName != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] 弱档(小快)模型命中 settings.fastModelName → name={} · CC getSmallFastModel (model.ts:36-38)",
                    fastName);
            }
            return fastName;
        }
        // ② settings.weakModelName（[FN2] 存全名/裸名）· CC ANTHROPIC_DEFAULT_HAIKU_MODEL（model.ts:132-134）
        String weakName = settingsTierModelName(SettingsRecord::getWeakModelName);
        if (weakName != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] 弱档(小快)模型命中 settings.weakModelName → name={} · CC getDefaultHaikuModel (model.ts:132-134)",
                    weakName);
            }
            return weakName;
        }
        // ③ fallbackModelName · CC haiku45 兜底（model.ts:137）
        if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] 弱档 fastModelName/weakModelName 均未命中，使用固定默认 fallback={}（CC getDefaultHaikuModel → haiku45, model.ts:131-138；不回退 mainModelName）",
                fallbackModelName);
        }
        return fallbackModelName;
    }

    /**
     * 解析 <b>强档模型</b> 名 · settings.strongModelName 全名反查 → DB models.name。
     *
     * <p>[W2-2] 对齐 CC {@code getDefaultOpusModel}（model.ts:105-116：env
     * {@code ANTHROPIC_DEFAULT_OPUS_MODEL} → opus46）的使用场景（alias opus/best、/insights 等）：
     * settings.强档 strongModelName 经 {@link #settingsTierModelName} 全名反查唯一路径（[FN2]，同
     * installTierSources 来源）反查为 DB models.name；未配置（或未命中 enabled model）时返回
     * 调用方传入的固定默认（如 {@code claude-opus-4-6}）。注意：返回值是 <b>DB models.name</b>
     * （用于 {@link #resolve} 精确匹配），非配置 ID。[FN2] settings 存全名/裸名。
     *
     * @param fallbackModelName 固定默认模型名（strongModelName 未配置时使用，如 opus46 字面量）
     * @return DB 可用的强档模型名；strongModelName 未配置 → 固定默认
     */
    public String resolveStrongModelName(String fallbackModelName) {
        // settings.strongModelName（[FN2] 存全名/裸名）· CC ANTHROPIC_DEFAULT_OPUS_MODEL（model.ts:105-116）
        //   经 settingsTierModelName 全名/裸名反查唯一路径（[FN2]，同 installTierSources 来源）
        String strongName = settingsTierModelName(SettingsRecord::getStrongModelName);
        if (strongName != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] 强档模型命中 settings.strongModelName → name={} · CC getDefaultOpusModel (model.ts:105-116)",
                    strongName);
            }
            return strongName;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] 强档 strongModelName 未配置，使用固定默认 fallback={}",
                fallbackModelName);
        }
        return fallbackModelName;
    }

    /**
     * [W6-1][FN2] 从 DB settings 单例行读取指定档位模型并反查为 DB models.name（裸名，供 LLM model 参数）。
     *
     * <p>[FN2] <b>去 id 路径</b>：settings 档位字段（fast/weak/medium/strong/subagent，V1/V25/V28 列）
     * 由前端 ModelPickerModal 持久化为<b>全名/裸名</b>（ModelPickerModal.tsx:115
     * {@code fullName=providerName/modelName}）；旧 W2-2 按 models.id 直查（selectOneById）路径
     * 已删除（用户拍板 B：全名化 + tts/asr 分开 + V28 RENAME + 存量 id 清除）。
     * <b>settings 存全名/裸名，全名反查唯一路径</b>：全名/裸名经 {@link ModelNameResolver#resolve} 反查
     * （前端契约）。命中返回 {@code models.name}；settings 未配置 / 未命中 enabled model / 读取异常 → null
     * （调用方回落各自默认，同 CC env 未设语义）。
     *
     * @param fieldGetter settings 档位字段 getter（如 {@code SettingsRecord::getStrongModelName}）
     * @return DB models.name（裸名）；未配置 / 未命中 / 异常 → null
     */
    public String settingsTierModelName(java.util.function.Function<SettingsRecord, String> fieldGetter) {
        if (fieldGetter == null) {
            return null;
        }
        try {
            SettingsRecord s = settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            String raw = s == null ? null : fieldGetter.apply(s);
            if (raw == null || raw.isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelConfigResolver] settings 档位模型未配置，返回 null（回落默认，[W6-1][FN2] DB settings 来源）");
                }
                return null;
            }
            // [FN2] 唯一反查路径：全名/裸名经 ModelNameResolver.resolve（前端契约：settings 存
            //   "provider/model"，ModelPickerModal.tsx:115 fullName=providerName/modelName）
            ModelRecord byName = ModelNameResolver.resolve(modelMapper, providerMapper, raw);
            if (byName != null && Boolean.TRUE.equals(byName.getEnabled())) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelConfigResolver] settings 档位模型全名反查命中: raw={} → name={}（[W6-1][FN2] 全名反查唯一路径）",
                        raw, byName.getName());
                }
                return byName.getName();
            }
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] settings 档位模型 {} 未命中 enabled model，返回 null（回落默认，[W6-1][FN2]）",
                    raw);
            }
            return null;
        } catch (Exception e) {
            log.warn("[ModelConfigResolver] settings 档位模型读取/反查失败，返回 null（回落默认，[W6-1][FN2]）: {}",
                e.toString());
            return null;
        }
    }

    /**
     * [TN1] 解析 <b>多模态档位模型</b> 名 · settings.multimodalModelName → DB models.name。
     *
     * <p>读取 settings.multimodalModelName（V28 列 multimodal_model_name，可空；用户拍板 B
     * tts/asr 分开承载）→ 经 {@link #settingsTierModelName} 全名/裸名反查唯一路径 → DB models.name。
     * 未配置 / 未命中 enabled model / 异常 → null（调用方回落各自默认，同 CC env 未设语义）。
     *
     * <p><b>使用先不使用</b>：当前无上游调用点（LLM 多模态调用未接线，grep 无现有引用）——
     * 仅 settings 层接线（字段可配置可读取），不上发 LLM 调用。未来调用点就绪后直接复用。
     *
     * @return DB models.name（裸名）；未配置 / 未命中 / 异常 → null
     */
    public String resolveMultimodalModelName() {
        String name = settingsTierModelName(SettingsRecord::getMultimodalModelName);
        if (name == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] 多模态档位 multimodalModelName 未配置/未命中，返回 null（[TN1] 使用先不使用，仅 settings 层接线）");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] 多模态档位 multimodalModelName → name={}（[TN1] settings 层接线命中，不上发 LLM）", name);
        }
        return name;
    }

    /**
     * [TN1] 解析 <b>TTS 档位模型</b> 名 · settings.ttsModelName → DB models.name。
     *
     * <p>读取 settings.ttsModelName（V28 列 tts_model_name，可空；用户拍板 B tts/asr 分开）→
     * 经 {@link #settingsTierModelName} 全名/裸名反查唯一路径 → DB models.name。
     * 未配置 / 未命中 enabled model / 异常 → null。
     *
     * <p><b>使用先不使用</b>：当前无上游调用点（LLM TTS 调用未接线，grep 无现有引用）——
     * 仅 settings 层接线（字段可配置可读取），不上发 LLM 调用。未来调用点就绪后直接复用。
     *
     * @return DB models.name（裸名）；未配置 / 未命中 / 异常 → null
     */
    public String resolveTtsModelName() {
        String name = settingsTierModelName(SettingsRecord::getTtsModelName);
        if (name == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] TTS 档位 ttsModelName 未配置/未命中，返回 null（[TN1] 使用先不使用，仅 settings 层接线）");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] TTS 档位 ttsModelName → name={}（[TN1] settings 层接线命中，不上发 LLM）", name);
        }
        return name;
    }

    /**
     * [TN1] 解析 <b>ASR 档位模型</b> 名 · settings.asrModelName → DB models.name。
     *
     * <p>读取 settings.asrModelName（V28 列 asr_model_name，可空；用户拍板 B tts/asr 分开）→
     * 经 {@link #settingsTierModelName} 全名/裸名反查唯一路径 → DB models.name。
     * 未配置 / 未命中 enabled model / 异常 → null。
     *
     * <p><b>使用先不使用</b>：当前无上游调用点（LLM ASR 调用未接线，grep 无现有引用）——
     * 仅 settings 层接线（字段可配置可读取），不上发 LLM 调用。未来调用点就绪后直接复用。
     *
     * @return DB models.name（裸名）；未配置 / 未命中 / 异常 → null
     */
    public String resolveAsrModelName() {
        String name = settingsTierModelName(SettingsRecord::getAsrModelName);
        if (name == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelConfigResolver] ASR 档位 asrModelName 未配置/未命中，返回 null（[TN1] 使用先不使用，仅 settings 层接线）");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[ModelConfigResolver] ASR 档位 asrModelName → name={}（[TN1] settings 层接线命中，不上发 LLM）", name);
        }
        return name;
    }

    /** models.name 精确匹配 enabled model · W1-2 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）。 */
    private ModelRecord findEnabledModelByName(String modelName) {
        return ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
    }
}

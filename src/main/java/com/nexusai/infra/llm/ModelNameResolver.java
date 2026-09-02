package com.nexusai.infra.llm;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 模型全名解析器（W1-2）· 全仓按 modelName 查询点的统一入口。
 *
 * <p>模型全名 = {@code {providerName}/{modelName}}（取第一个 / 拆分，但拆分是<b>试探性</b>的：
 * 仅当首段命中 providers 表精确前缀才当真拆，G-5）。provider.name 唯一（V1:10 UNIQUE），因此
 * 真全名可精确定位模型，解决跨提供商同名模型歧义。<b>真全名路径未命中（fail-loud，对齐 CC
 * getMainLoopModel model.ts:92-98：未知名直接传 API，失败即失败）→ 返回 null，绝不静默重绑其他
 * 提供商同名模型（G-2 修复）。</b>含 '/' 但首段非 provider 前缀 = 模型名不透明（Bedrock ARN /
 * Azure 部署 ID，CC model.ts:445-506 模型名不透明原样透传）→ 整体 name 透传走兼容路径（G-5）。
 * 历史数据不含 /（裸 modelName）→ {@link #parseFullName} 返回 null 标记兼容路径，
 * {@link #resolve} 走历史兼容路径：按 name 查第一条（enabled=true，ORDER BY provider_id,id 确定性）。
 *
 * <p>纯静态工具类（同 {@link com.nexusai.infra.util.ApiKeyHasher} 风格）：所有查询点
 * （含 static 上下文 LlmAgentLoop.resolveContextWindowTokens / AgentLoopContext.computeBudgetFromGates）
 * 均持有 mapper 引用，无需 Spring 注入即可接线。
 *
 * <p>[G-4] {@link #resolve} 入口前置<b>裸别名展开</b>（对齐 CC getMainLoopModel → parseUserSpecifiedModel，
 * model.ts:92-98 + :456-470）：opus→强档、sonnet/opusplan→中档、haiku→弱档、best→强档，各档先读
 * settings strong/medium/weakModelId 反查的 DB models.name，未配置回落 CC canonical 默认（claude-opus-4-6
 * 等）；[1m] 后缀保真（best 除外）。档位来源经 {@link #installTierSources}（ModelConfigResolver 启动安装）。
 */
public final class ModelNameResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelNameResolver.class);

    private ModelNameResolver() {}

    // ─────────────────────────── [G-4] 别名展开前置 ───────────────────────────
    // 对齐 CC parseUserSpecifiedModel（Open-ClaudeCode/src/utils/model/model.ts:445-506）：
    // getMainLoopModel（model.ts:92-98）对用户指定 model 无条件过 parseUserSpecifiedModel，
    // 别名 opus/sonnet/haiku/best/opusplan 展开为各档真实模型名（[1m] 保真），再走 DB 精确匹配。
    // 仅展开<b>裸别名</b>（无 '/'）；含 '/' 的模型名由 G-5 处理（首段 provider 前缀判定 / 不透明透传），
    // 本层不做 '/' 拆分展开，避免破坏 Bedrock ARN / Azure 部署 ID 等不透明模型名。

    /** CC getDefaultOpusModel（model.ts:105-116）→ getModelStrings().opus46。
     *  Java 同 PromptCaching.DEFAULT_OPUS（[W7-2]）。仅作强档未配置时的回落默认。 */
    private static final String DEFAULT_OPUS = "claude-opus-4-6";
    /** CC getDefaultSonnetModel（model.ts:119-130）→ getModelStrings().sonnet46。
     *  Java 同 PromptCaching.DEFAULT_SONNET（[W7-2]）。仅作中档未配置时的回落默认。 */
    private static final String DEFAULT_SONNET = "claude-sonnet-4-6";
    /** CC getDefaultHaikuModel（model.ts:131-138）→ getModelStrings().haiku45。
     *  Java 同 PromptCaching.DEFAULT_HAIKU（[W7-2]）。仅作弱档未配置时的回落默认。 */
    private static final String DEFAULT_HAIKU = "claude-haiku-4-5-20251001";

    /** [G-4] 强档(opus)模型名来源 · settings.strongModelId（V25 列）→ DB models.name 反查。
     *  默认 null（未安装 DB 源）→ 回落 {@link #DEFAULT_OPUS}（等价 CC env 未设）。 */
    static volatile Supplier<String> strongTierModelSource = () -> null;
    /** [G-4] 中档(sonnet)模型名来源 · settings.mediumModelId（V25 列）→ DB models.name 反查。 */
    static volatile Supplier<String> mediumTierModelSource = () -> null;
    /** [G-4] 弱档(haiku)模型名来源 · settings.weakModelId（V25 列）→ DB models.name 反查。 */
    static volatile Supplier<String> weakTierModelSource = () -> null;

    /** [G-4] 别名展开重入防护 · settings 档位反查（settingsTierModelName ②按名反查）内部会再调
     *  {@link #resolve}，若档位字段误存别名（settings.strongModelId="opus"）会无限递归；
     *  展开期间置 true 抑制嵌套展开，外层回落 CC canonical 默认。 */
    private static final ThreadLocal<Boolean> ALIAS_EXPANDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * [G-4] 安装三档别名展开来源 · 注入 DB settings 档位反查（ModelConfigResolver 启动时调用；
     *  同 PromptCaching#setModelConfigResolver [W7-2] 风格）。未安装 → 回落 CC canonical 默认。
     *
     * @param strong settings.strongModelId → DB models.name（null → 保持默认）
     * @param medium settings.mediumModelId → DB models.name（null → 保持默认）
     * @param weak   settings.weakModelId → DB models.name（null → 保持默认）
     */
    public static void installTierSources(Supplier<String> strong, Supplier<String> medium, Supplier<String> weak) {
        if (strong != null) strongTierModelSource = strong;
        if (medium != null) mediumTierModelSource = medium;
        if (weak != null) weakTierModelSource = weak;
    }

    /**
     * [G-4] 裸别名展开前置 · 对齐 CC parseUserSpecifiedModel（model.ts:456-470）。
     *
     * <p>仅当整个输入为<b>裸别名</b>（无 '/'，可带 [1m]）时展开：
     * <ul>
     *   <li>{@code opus} → 强档（CC :465 getDefaultOpusModel）</li>
     *   <li>{@code best} → 强档（CC :467 getBestModel() → getDefaultOpusModel）</li>
     *   <li>{@code sonnet} → 中档（CC :461 getDefaultSonnetModel）</li>
     *   <li>{@code opusplan} → 中档（CC :459 "Sonnet is default, Opus in plan mode"）</li>
     *   <li>{@code haiku} → 弱档（CC :463 getDefaultHaikuModel）</li>
     * </ul>
     * [1m] 保真：非 best 别名展开后原样保留 [1m] 后缀（CC :458-468）；best 不追加（:466-467）。
     * 含 '/' 的模型名（全名 / Bedrock ARN / Azure 部署 ID）与裸非别名 → null（调用方保留原值，
     * 由 G-5 / 既有路径处理，本层不破坏大小写）。null / blank → null。
     *
     * @param modelName 原始模型名
     * @return 展开后的模型名；非裸别名（含 '/' 或不匹配）→ null
     */
    static String expandAlias(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        if (Boolean.TRUE.equals(ALIAS_EXPANDING.get())) {
            // settings 档位反查内部再调 resolve() 时的重入防护（档位字段误存别名场景）
            return null;
        }
        String trimmed = modelName.trim();
        if (trimmed.indexOf('/') >= 0) {
            // 含 '/' 由 G-5 处理（首段 provider 前缀判定 / 不透明透传），本层不展开（避免破坏 ARN/部署 ID）
            return null;
        }
        ALIAS_EXPANDING.set(true);
        try {
            return expandAliasSegment(trimmed);
        } finally {
            ALIAS_EXPANDING.set(false);
        }
    }

    /**
     * 别名 → 档位展开（单段，已处理 [1m]）· 对齐 CC model.ts:451-470。
     *
     * @param segment 裸模型段（可为 null/blank → null）
     */
    private static String expandAliasSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return null;
        }
        String normalized = segment.trim().toLowerCase(Locale.ROOT);
        // CC model.ts:451-454：has1mTag（context.ts:35-40 has1mContext）前置，剥 [1m] 再判别名
        boolean has1mTag = normalized.endsWith("[1m]");
        String base = has1mTag
            ? normalized.substring(0, normalized.length() - "[1m]".length()).trim()
            : normalized;
        String canonical = aliasCanonical(base);
        if (canonical == null) {
            return null;
        }
        // CC model.ts:458-468：别名 → 各档默认 + 原 [1m] 后缀；唯一例外 best（:466-467）不追加 [1m]
        String expanded = "best".equals(base)
            ? canonical
            : canonical + (has1mTag ? "[1m]" : "");
        if (log.isDebugEnabled()) {
            log.debug("[ModelNameResolver] 别名展开: {} → {}（G-4，CC model.ts:457-468，best 不追加 [1m]）",
                base, expanded);
        }
        return expanded;
    }

    /**
     * CC 别名 → 档位模型名（settings 反查优先，未配置回落 CC canonical 默认）· 对齐
     * CC model.ts:457-469 switch（getDefaultOpusModel/getDefaultSonnetModel/getDefaultHaikuModel）。
     *
     * @param base 小写、已剥 [1m] 的别名串
     * @return 命中别名的档位模型名；非别名 → null
     */
    private static String aliasCanonical(String base) {
        return switch (base) {
            case "opusplan", "sonnet" -> tierOrDefault(mediumTierModelSource, DEFAULT_SONNET); // CC :459/:461
            case "haiku" -> tierOrDefault(weakTierModelSource, DEFAULT_HAIKU);                  // CC :463
            case "opus", "best" -> tierOrDefault(strongTierModelSource, DEFAULT_OPUS);           // CC :465/:467
            default -> null;
        };
    }

    /** settings 档位反查命中非 blank 用之，否则回落 CC canonical 默认。 */
    private static String tierOrDefault(Supplier<String> source, String fallback) {
        String fromDb = source.get();
        return (fromDb != null && !fromDb.isBlank()) ? fromDb : fallback;
    }

    /** 全名拆分结果 · fullName="{providerName}/{modelName}"。 */
    public record ParsedModelName(String providerName, String modelName) {}

    /**
     * 拆分模型全名：取第一个 / 拆出 providerName + modelName。<b>拆分是试探性的</b>——拆出的
     * providerName 是否真是 provider 前缀，由 {@link #resolve} 用 providers 表精确匹配验证（G-5）：
     * 首段非 provider 前缀 → '/' 是模型名一部分（Bedrock ARN / Azure 部署 ID），整体透传。方法本身
     * 只做语法拆分，不做 DB 校验。
     *
     * @param fullName 模型全名或裸模型名（null / blank / 无合法 / → null，标记兼容路径）
     */
    public static ParsedModelName parseFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        int idx = fullName.indexOf('/');
        if (idx <= 0 || idx == fullName.length() - 1) {
            return null;
        }
        String providerName = fullName.substring(0, idx).trim();
        String modelName = fullName.substring(idx + 1).trim();
        if (providerName.isEmpty() || modelName.isEmpty()) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ModelNameResolver] parseFullName 命中: fullName={} → providerName={} modelName={}",
                fullName, providerName, modelName);
        }
        return new ParsedModelName(providerName, modelName);
    }

    /**
     * 按模型名解析 enabled model（统一入口，全名感知）。
     *
     * <p>优先真全名路径：首段命中 providers 表精确前缀（selectOneByQuery eq name）才走联合查
     * models 表 eq name + eq provider_id + enabled=true（消除跨提供商同名歧义）。<b>首段未命中
     * provider 前缀 → '/' 是模型名一部分（Bedrock ARN / Azure 部署 ID，CC model.ts:445-506 模型名
     * 不透明原样透传），整体 name 透传走历史兼容路径（G-5）。</b>真全名路径命中 provider 但该提供商
     * 下无 enabled 模型（fail-loud）→ 返回 null，绝不再回退按 name 重绑其他提供商同名模型（对齐 CC
     * getMainLoopModel model.ts:92-98 语义：未知名直接传 API，失败即失败，G-2 修复）。
     * 无 /（裸 modelName）或 providerMapper 为 null → 历史兼容路径：按 name 查第一条
     * （enabled=true，ORDER BY provider_id,id 保证确定性——跨提供商同名模型取最小 provider_id 那条）。
     *
     * @param modelMapper   模型 mapper（调用方持有；null → null）
     * @param providerMapper 提供商 mapper（调用方持有；null → 直接走兼容路径）
     * @param modelName     模型全名（providerName/modelName）或裸模型名（含 '/' 不透明名视为整体）
     * @return enabled ModelRecord；未命中 → null（fail-loud 或兼容路径未命中，调用方处理回落/抛错）
     */
    public static ModelRecord resolve(ModelMapper modelMapper, ProviderMapper providerMapper, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        // [G-4] 别名展开前置 · 对齐 CC getMainLoopModel（model.ts:92-98）→ parseUserSpecifiedModel：
        //   裸别名 opus/sonnet/haiku/best/opusplan 展开为各档真实模型名（settings 反查或 CC canonical 默认）
        //   后再走 DB 精确匹配；非别名 / 含 '/'（G-5）→ null 原样透传，不改动既有解析路径。
        String expanded = expandAlias(modelName);
        if (expanded != null && !expanded.equals(modelName)) {
            log.info("[ModelNameResolver] 别名展开前置: {} → {}（G-4，对齐 CC parseUserSpecifiedModel model.ts:456-470）",
                modelName, expanded);
            modelName = expanded;
        }
        // 全名路径：首段命中 providers 表精确前缀才当真拆，再联合查 model（仅 providerMapper 可用且含 '/' 时进入）
        if (providerMapper != null) {
            ParsedModelName parsed = parseFullName(modelName);
            if (parsed != null) {
                ProviderRecord provider = providerMapper.selectOneByQuery(
                    QueryWrapper.create().eq("name", parsed.providerName()));
                if (provider != null) {
                    ModelRecord hit = modelMapper.selectOneByQuery(
                        QueryWrapper.create()
                            .eq("name", parsed.modelName())
                            .eq("provider_id", provider.getId())
                            .eq("enabled", true));
                    if (hit != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("[ModelNameResolver] 全名解析命中: fullName={} providerId={} modelId={}",
                                modelName, provider.getId(), hit.getId());
                        }
                        return hit;
                    }
                    // 真全名未命中（fail-loud）：该提供商下无 enabled 模型 → 返回 null，不回退重绑他提供商同名模型（G-2 修复）
                    log.warn("[ModelNameResolver] 全名未命中(fail-loud): 提供商 {} 下无 enabled 模型 {}, modelName={} → null（不回退重绑）",
                        parsed.providerName(), parsed.modelName(), modelName);
                    return null;
                }
                // G-5: 首段未命中 providers 表精确前缀 → '/' 是模型名的一部分（Bedrock ARN / Azure 部署 ID），
                // 模型名不透明（CC model.ts:445-506 原样透传）→ 整体 name 透传，走历史兼容路径按整体名查询。
                if (log.isDebugEnabled()) {
                    log.debug("[ModelNameResolver] 首段非 provider 前缀, 整体透传: modelName={}", modelName);
                }
            }
        }
        // 历史兼容路径（裸名无 '/'、providerMapper 为 null、或含 '/' 但首段非 provider 前缀（G-5）时到达）:
        // 按 name 查第一条（enabled=true）ORDER BY provider_id,id 确定性；
        // 含 '/' 且首段命中 provider 前缀的真全名未命中绝不落到此路径（fail-loud，G-2 修复）
        List<ModelRecord> ms = modelMapper.selectListByQuery(
            QueryWrapper.create()
                .eq("name", modelName)
                .eq("enabled", true)
                .orderBy("provider_id", true)
                .orderBy("id", true));
        ModelRecord model = (ms != null && !ms.isEmpty()) ? ms.get(0) : null;
        if (model == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] 历史兼容路径未命中: modelName={}", modelName);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[ModelNameResolver] 历史兼容路径命中(裸名): modelName={} modelId={}",
                modelName, model.getId());
        }
        return model;
    }

    /**
     * [W2-3] 按模型名解析 DB 可配 max_tokens · models.max_tokens 列（前端可配）的单一查询点。
     *
     * <p>复用 {@link #resolve} 的模型解析（全名感知 + enabled 过滤），取 {@code maxTokens} 列
     * （>0 有效）。DB 未命中 / 未配置 / 无效 / 异常 → null（调用方回落 CC 家族表或模型缺省）。
     * 纯静态工具类（同 {@link #resolve} 风格）：mapper 由调用方持有，Spring 与静态上下文均适用。
     *
     * @param modelMapper    模型 mapper（null → null，调用方无 DB 时静默回落）
     * @param providerMapper 提供商 mapper（null → 走按 name 兼容路径，无全名拆分）
     * @param modelName      模型全名（providerName/modelName）或裸模型名
     * @return models.max_tokens（>0）；未命中 / 无效 → null
     */
    public static Integer resolveMaxTokens(ModelMapper modelMapper, ProviderMapper providerMapper, String modelName) {
        if (modelMapper == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        try {
            ModelRecord model = resolve(modelMapper, providerMapper, modelName);
            Integer maxTokens = model != null ? model.getMaxTokens() : null;
            if (maxTokens == null || maxTokens <= 0) {
                if (log.isDebugEnabled()) {
                    log.debug("[ModelNameResolver] max_tokens 未配置或无效: modelName={} maxTokens={}",
                        modelName, maxTokens);
                }
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] max_tokens DB 命中: modelName={} maxTokens={}",
                    modelName, maxTokens);
            }
            return maxTokens;
        } catch (Exception e) {
            log.warn("[ModelNameResolver] max_tokens 解析异常, 回落: modelName={} err={}",
                modelName, e.toString());
            return null;
        }
    }
}

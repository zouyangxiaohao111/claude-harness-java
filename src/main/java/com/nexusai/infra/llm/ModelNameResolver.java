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
     * [T1] 剥掉尾部的 {@code [1m]} 后缀（大小写不敏感）· <b>只产出 DB 查询键，绝不回写任何字段</b>。
     *
     * <p><b>WHY</b>：{@code [1m]} 是<b>上下文窗口标记</b>，不是模型身份。本仓既有口径同此：
     * {@code AgentModelResolver.getCanonicalName}（剥 [1m] + 小写后再判族）、
     * {@code CompactThresholdSystem.has1mContext}（{@code model.toLowerCase().contains("[1m]")}
     * 只用来切窗口，不参与身份）。对齐 CC：{@code utils/context.ts:35-40 has1mContext}
     * （{@code /\[1m\]/i.test(model)}，大小写不敏感）+ {@code utils/model/model.ts:451-454}
     * （剥 [1m] 后再做模型名比对）。
     *
     * <p>会话主力模型名带 {@code [1m]}（如 {@code deepseek-flash[1m]}，
     * {@code settings.main_model_name} / {@code sessions.model_name} 由前端原样写入），而
     * {@code models.name} 存的是不带后缀的名字 ⇒ 按字面量精确查必然落空 ⇒
     * {@code AgentLoopContext.computeBudgetFromGates} 落到硬编码 200_000 兜底。
     *
     * @param modelName 原始模型名
     * @return 剥掉尾部 {@code [1m]} 并 trim 的查询键；无该后缀 / 剥后为空 / null → null
     */
    private static String strip1mTag(String modelName) {
        if (modelName == null) {
            return null;
        }
        String trimmed = modelName.trim();
        int tagLen = "[1m]".length();
        int len = trimmed.length();
        // regionMatches(true, …) = 大小写不敏感（同 SkillModelOverrideResolver 的 [1m] 判定风格）
        if (len <= tagLen || !trimmed.regionMatches(true, len - tagLen, "[1m]", 0, tagLen)) {
            return null;
        }
        String stripped = trimmed.substring(0, len - tagLen).trim();
        return stripped.isEmpty() ? null : stripped;
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
     * <p>[T1] <b>历史兼容路径容忍尾部 [1m] 后缀</b>：原键未命中且其尾部带 {@code [1m]}（大小写不敏感）
     * 时，用 {@link #strip1mTag} 剥出的键<b>同语义重查一次</b>（精确键优先、剥后缀键兜底）。
     * 这解决「会话主力模型名 {@code deepseek-flash[1m]} vs {@code models.name=deepseek-flash}」
     * 的错配 —— 修复前该错配令 {@code AgentLoopContext.computeBudgetFromGates} 落到硬编码
     * {@code FALLBACK_TOKEN_BUDGET=200_000}，1M 会话被静默按 20 万计窗口。
     * <b>只影响查询键</b>：返回的仍是 DB 原记录，剥后名不回写任何字段。真全名路径
     * （providerName/modelName）<b>不做</b>此剥离，语义不变。
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
        // [2026-10-22 根治 · 裸名歧义 fail-loud] 裸名命中多个 provider 的同名模型时**拒绝解析**，
        //   不再按 provider_id 字典序「猜一个」。
        //   WHY：该顺序与「key 是否有效 / 用户原本想用哪个」无关。实测（2026-10-21 夜间
        //   sess-aa022bdf）：{@code deepseek-flash} 同时存在于 deepseek / ds-zcw / ds-zcw-openai，
        //   字典序选中 ds-zcw-openai（key 已失效）⇒ 主会话被通知唤醒的 32 轮全部 HTTP 401，
        //   子进程侧一切正常、界面上「主代理一动不动」。
        //   ⛔ 静默选错 provider 比明确失败更危险：错误被推迟到调用外部 API 时才以 401 暴露，
        //   且用户无从判断是 key 问题还是模型问题。
        //   正确姿势：配置里一律写全名 {@code provider/model}（前端模型选择器已如此，
        //   settings/sessions 存量数据亦为全名）；裸名只在「全库唯一」时继续兼容。
        if (ms != null && ms.size() > 1) {
            StringBuilder providers = new StringBuilder();
            for (ModelRecord m : ms) {
                if (providers.length() > 0) providers.append(", ");
                providers.append(m.getProviderId()).append(':').append(m.getId());
            }
            log.error("[ModelNameResolver] 裸名歧义，拒绝解析(fail-loud): modelName={} 命中 {} 个 enabled 模型 [{}]"
                    + " —— 请改用全名 provider/model（如 deepseek/{}）",
                modelName, ms.size(), providers, modelName);
            return null;
        }
        // [T1] 查表容忍 [1m] 后缀：原键未命中且原键尾部带 [1m] 时，用剥后键【同语义】重查一次。
        //   语义 = 精确键优先、剥后缀键兜底（确定性）：
        //     · 表内确实存在字面量 "foo[1m]" 行 → 第一次即命中，行为与修复前逐字节相同（零回归）；
        //     · 表内只有 "foo" 行（常态，[1m] 是窗口标记非身份）→ 第二次剥后缀命中。
        //   为什么不「先剥再查」：那会把 foo[1m] 静默解析到 foo 行，令 1M 会话的窗口被降级成
        //   foo 行的值（很可能 200k）——正是本次要修的「静默降级」的反向版本。
        //   为什么重查必须以「剥后键 != 原键」为条件：ModelNameResolverG5Test 的
        //   verify(modelMapper).selectListByQuery(captor)（默认 times(1)）要求无 [1m] 时不得多发查询。
        //   对齐 CC：utils/context.ts:35-40 has1mContext + utils/model/model.ts:451-454（剥 [1m] 再比对）。
        if (model == null) {
            String stripped = strip1mTag(modelName);
            if (stripped != null && !stripped.equals(modelName)) {
                List<ModelRecord> msStripped = modelMapper.selectListByQuery(
                    QueryWrapper.create()
                        .eq("name", stripped)
                        .eq("enabled", true)
                        .orderBy("provider_id", true)
                        .orderBy("id", true));
                // [2026-10-22 根治] 剥 [1m] 后同样适用「裸名多命中 ⇒ fail-loud」，不猜 provider。
                if (msStripped != null && msStripped.size() > 1) {
                    StringBuilder providers = new StringBuilder();
                    for (ModelRecord m : msStripped) {
                        if (providers.length() > 0) providers.append(", ");
                        providers.append(m.getProviderId()).append(':').append(m.getId());
                    }
                    log.error("[ModelNameResolver] 裸名歧义（[1m] 剥离后），拒绝解析(fail-loud): 原键={} 查键={} "
                            + "命中 {} 个 enabled 模型 [{}] —— 请改用全名 provider/model",
                        modelName, stripped, msStripped.size(), providers);
                    return null;
                }
                model = (msStripped != null && !msStripped.isEmpty()) ? msStripped.get(0) : null;
                if (model != null) {
                    // 数据流日志：原键 → 实际查询键 → 命中 modelId（[1m] 只影响查询键，返回值仍是 DB 原记录）
                    log.info("[ModelNameResolver] [1m] 后缀剥离查表命中: 原键={} → 查键={} modelId={}"
                            + "（CC model.ts:451-454 剥 [1m] 再比对；只改查询键，不回写任何字段）",
                        modelName, stripped, model.getId());
                }
            }
        }
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
     * 宽松解析 · <b>只用于计费 / 上下文统计 / 能力查询 / SDK 发送名翻译，⛔ 不得用于选模型</b>。
     *
     * <p><b>与 {@link #resolve} 的关系</b>：解析结果逐条一致（全名精确路径、G-5 不透明名透传、
     * [1m] 剥离重查、裸名歧义返回 null 全部相同），<b>唯一差别是日志级别</b>——裸名歧义与
     * 未命中只打 DEBUG，不打 ERROR/WARN。
     *
     * <p><b>WHY</b>：{@link #resolve} 的「裸名歧义 → ERROR + null」是<b>选模型链路</b>的护栏
     * （2026-10-22 用户明确要求：裸名命中多个 provider 时拒绝静默选错，见 resolve 内注释）。
     * 但同一解析器也被计费（{@code ModelCostCalculator.resolveTier}）、上下文统计
     * （{@code ContextUsageCalculator}）、能力查询（{@code resolveMaxTokens} / 图片能力）、
     * SDK 发送名翻译（{@code ModelConfigResolver.resolveSdkModelName}）复用：
     * 这些场景<b>拿不到调用方 provider 身份</b>，且调用方本来就会在 null 时回落默认档/原始值 ——
     * 把「选模型的拒绝」记成 ERROR 只会刷屏、没有任何决策价值。
     * 实测（2026-10-29 backend error.log）：单日 1968 条同一裸名歧义（{@code deepseek-flash}），
     * 全部来自上述非选择路径。
     *
     * <p><b>为什么另起方法而不改 {@link #resolve}</b>：选模型护栏的语义由用户明确要求，
     * 任何改动都可能把「拒绝静默选错」降级为「静默选错」。两条路径物理隔离，护栏不可能被
     * 统计/计费改动误伤。
     *
     * @param modelMapper    模型 mapper（null → null）
     * @param providerMapper 提供商 mapper（null → 直接走按 name 兼容路径）
     * @param modelName      模型全名（providerName/modelName）或裸模型名
     * @return enabled ModelRecord；裸名歧义 / 未命中 → null（调用方回落默认档）
     */
    public static ModelRecord resolveQuiet(ModelMapper modelMapper, ProviderMapper providerMapper,
                                           String modelName) {
        if (modelMapper == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        String expanded = expandAlias(modelName);
        if (expanded != null && !expanded.equals(modelName)) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [宽松解析] 别名展开: {} → {}（G-4）", modelName, expanded);
            }
            modelName = expanded;
        }
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
                    if (hit == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("[ModelNameResolver] [宽松解析] 全名未命中: 提供商 {} 下无 enabled 模型 {}"
                                    + "（统计/计费路径，回落调用方默认值）",
                                parsed.providerName(), parsed.modelName());
                        }
                        return null;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[ModelNameResolver] [宽松解析] 全名命中: fullName={} modelId={}",
                            modelName, hit.getId());
                    }
                    return hit;
                }
                // G-5：首段非 provider 前缀 → '/' 是模型名一部分（ARN / 部署 ID）→ 整体透传
                if (log.isDebugEnabled()) {
                    log.debug("[ModelNameResolver] [宽松解析] 首段非 provider 前缀, 整体透传: modelName={}", modelName);
                }
            }
        }
        return legacyQuiet(modelMapper, modelName);
    }

    /**
     * 按 provider 限定解析 · <b>计费专用</b>（调用方已知本次调用实际走的是哪个 provider 类型）。
     *
     * <p>语义：
     * <ol>
     *   <li>全名 {@code provider/model} → 与 {@link #resolveQuiet} 相同的精确联合查询（provider 名唯一，
     *       不需要类型过滤）；</li>
     *   <li>裸名 + {@code providerType} → 只在 <b>该 type 且 enabled 的 provider</b> 名下的 enabled
     *       模型里找：唯一命中 → 返回；0 / 多命中 → null（调用方回落内置默认档）；</li>
     *   <li>{@code providerType} 为空 → 退回 {@link #resolveQuiet}（通用解析，语义同 {@link #resolve}
     *       但不打 ERROR）。</li>
     * </ol>
     *
     * <p><b>WHY 按「type + enabled」过滤</b>：{@code LlmCallRecord.providerType()} 是本次调用
     * <b>实际生效</b>的 provider 协议类型（{@code providers.type}）。disabled provider 不可能是
     * 一次真实调用的来源（{@code ModelConfigResolver.resolve} 对 disabled provider 直接
     * warn+skip），把它的同名模型行算进候选只会制造假歧义 —— 实测本机三行
     * {@code deepseek-flash} 里两行挂在已停用的 {@code ds-zcw} / {@code ds-zcw-openai} 上，
     * 真正在用的只有 {@code deepseek} 一行。
     *
     * <p><b>不削弱选模型护栏</b>：本方法只被 {@code ModelCostCalculator} 调用；选模型链路仍走
     * {@link #resolve}（多命中 ERROR + null）。另外即使过滤后仍多命中，这里也返回 null 而不是
     * 「挑一个」，计费会按内置默认档计价，不会把费用记到错误的模型行上。
     *
     * @param modelMapper    模型 mapper（null → null）
     * @param providerMapper 提供商 mapper（null → 无法限定，返回 null）
     * @param modelName      模型全名（providerName/modelName）或裸模型名
     * @param providerType   provider 类型（{@code openai_compatible} / {@code anthropic} …；空 → 通用解析）
     * @return 唯一命中的 enabled ModelRecord；0 / 多命中 → null
     */
    public static ModelRecord resolveByProvider(ModelMapper modelMapper, ProviderMapper providerMapper,
                                                String modelName, String providerType) {
        if (modelMapper == null || modelName == null || modelName.isBlank()) {
            return null;
        }
        String expanded = expandAlias(modelName);
        if (expanded != null && !expanded.equals(modelName)) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] 别名展开: {} → {}（G-4）", modelName, expanded);
            }
            modelName = expanded;
        }
        if (providerType == null || providerType.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] 无 providerType → 退回宽松解析: modelName={}",
                    modelName);
            }
            return resolveQuiet(modelMapper, providerMapper, modelName);
        }
        // 全名：provider 名唯一 → 直接精确联合查（等价宽松解析的全名分支）
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
                    if (hit == null && log.isDebugEnabled()) {
                        log.debug("[ModelNameResolver] [按 provider 限定] 全名未命中: 提供商 {} 下无 enabled 模型 {}",
                            parsed.providerName(), parsed.modelName());
                    }
                    return hit;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[ModelNameResolver] [按 provider 限定] 首段非 provider 前缀, 整体透传: modelName={}",
                        modelName);
                }
            }
        }
        // 裸名：按 provider 类型 + enabled 限定候选
        ModelRecord hit = scopedBareHit(modelMapper, providerMapper, modelName, providerType);
        if (hit == null) {
            String stripped = strip1mTag(modelName);
            if (stripped != null && !stripped.equals(modelName)) {
                hit = scopedBareHit(modelMapper, providerMapper, stripped, providerType);
                if (hit != null) {
                    log.info("[ModelNameResolver] [按 provider 限定] [1m] 后缀剥离查表命中: 原键={} → 查键={} modelId={}"
                            + "（只改查询键，不回写任何字段）",
                        modelName, stripped, hit.getId());
                }
            }
        }
        return hit;
    }

    /** 裸名 + providerType 唯一命中（0 / 多命中 → null，DEBUG 说明原因）。 */
    private static ModelRecord scopedBareHit(ModelMapper modelMapper, ProviderMapper providerMapper,
                                             String name, String providerType) {
        if (providerMapper == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] providerMapper 缺失, 无法按 type 限定: modelName={}", name);
            }
            return null;
        }
        List<ModelRecord> rows = modelMapper.selectListByQuery(
            QueryWrapper.create()
                .eq("name", name)
                .eq("enabled", true)
                .orderBy("provider_id", true)
                .orderBy("id", true));
        if (rows == null || rows.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] 裸名无 enabled 模型: modelName={}", name);
            }
            return null;
        }
        // providers.type + enabled 过滤（disabled provider 不可能是真实调用来源 → 不算候选）
        List<ProviderRecord> providers = providerMapper.selectListByQuery(
            QueryWrapper.create()
                .eq("type", providerType)
                .eq("enabled", true));
        if (providers == null || providers.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] providerType={} 下无 enabled provider, modelName={} → null",
                    providerType, name);
            }
            return null;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ProviderRecord p : providers) {
            if (p != null && p.getId() != null) {
                ids.add(p.getId());
            }
        }
        List<ModelRecord> scoped = new java.util.ArrayList<>();
        for (ModelRecord m : rows) {
            if (m != null && m.getProviderId() != null && ids.contains(m.getProviderId())) {
                scoped.add(m);
            }
        }
        if (scoped.size() == 1) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [按 provider 限定] 唯一命中: modelName={} providerType={} modelId={}"
                        + " providerId={}",
                    name, providerType, scoped.get(0).getId(), scoped.get(0).getProviderId());
            }
            return scoped.get(0);
        }
        if (log.isDebugEnabled()) {
            if (scoped.isEmpty()) {
                log.debug("[ModelNameResolver] [按 provider 限定] 裸名不在 providerType={} 的 enabled provider 下: modelName={}"
                        + " → null（计费回落内置默认档）", providerType, name);
            } else {
                StringBuilder providersDesc = new StringBuilder();
                for (ModelRecord m : scoped) {
                    if (providersDesc.length() > 0) providersDesc.append(", ");
                    providersDesc.append(m.getProviderId()).append(':').append(m.getId());
                }
                log.debug("[ModelNameResolver] [按 provider 限定] 同 providerType 内仍歧义: modelName={} providerType={}"
                        + " 命中 {} 个 [{}] → null（计费回落内置默认档，不猜）",
                    name, providerType, scoped.size(), providersDesc);
            }
        }
        return null;
    }

    /**
     * 历史兼容路径的「静默版」· 返回值与 {@link #resolve} 的历史兼容分支逐条一致，
     * 但裸名歧义只打 DEBUG（不 fail-loud、不返回「猜一个」的结果）。
     */
    private static ModelRecord legacyQuiet(ModelMapper modelMapper, String modelName) {
        List<ModelRecord> ms = modelMapper.selectListByQuery(
            QueryWrapper.create()
                .eq("name", modelName)
                .eq("enabled", true)
                .orderBy("provider_id", true)
                .orderBy("id", true));
        if (ms != null && ms.size() > 1) {
            if (log.isDebugEnabled()) {
                log.debug("[ModelNameResolver] [宽松解析] 裸名歧义 → null（不猜 provider；统计/计费路径回落默认值）:"
                        + " modelName={} 命中 {} 个 [{}]",
                    modelName, ms.size(), describeProviders(ms));
            }
            return null;
        }
        ModelRecord model = (ms != null && !ms.isEmpty()) ? ms.get(0) : null;
        if (model == null) {
            String stripped = strip1mTag(modelName);
            if (stripped != null && !stripped.equals(modelName)) {
                List<ModelRecord> msStripped = modelMapper.selectListByQuery(
                    QueryWrapper.create()
                        .eq("name", stripped)
                        .eq("enabled", true)
                        .orderBy("provider_id", true)
                        .orderBy("id", true));
                if (msStripped != null && msStripped.size() > 1) {
                    if (log.isDebugEnabled()) {
                        log.debug("[ModelNameResolver] [宽松解析] [1m] 剥离后裸名歧义 → null: 原键={} 查键={} 命中 {} 个 [{}]",
                            modelName, stripped, msStripped.size(), describeProviders(msStripped));
                    }
                    return null;
                }
                model = (msStripped != null && !msStripped.isEmpty()) ? msStripped.get(0) : null;
                if (model != null && log.isDebugEnabled()) {
                    log.debug("[ModelNameResolver] [宽松解析] [1m] 后缀剥离查表命中: 原键={} → 查键={} modelId={}",
                        modelName, stripped, model.getId());
                }
            }
        }
        if (log.isDebugEnabled()) {
            if (model == null) {
                log.debug("[ModelNameResolver] [宽松解析] 历史兼容路径未命中: modelName={}", modelName);
            } else {
                log.debug("[ModelNameResolver] [宽松解析] 历史兼容路径命中(裸名): modelName={} modelId={}",
                    modelName, model.getId());
            }
        }
        return model;
    }

    /** 候选模型按 {@code providerId:modelId} 拼接（仅日志用）。 */
    private static String describeProviders(List<ModelRecord> models) {
        StringBuilder sb = new StringBuilder();
        for (ModelRecord m : models) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(m.getProviderId()).append(':').append(m.getId());
        }
        return sb.toString();
    }

    /**
     * 由已解析的 ModelRecord 还原**全名**（{@code providerName/modelName}）· 2026-10-22 修复。
     *
     * <p>WHY：{@link #resolve} 出来的 ModelRecord 已经绑定 provider；若调用方只把裸名
     * （{@code model.getName()}）回传下游再解析一次，会重新走「按 name 取第一条」的兼容路径，
     * 可能命中**另一个** provider 的同名模型。实测（2026-10-21 夜间）：settings 存
     * {@code deepseek/deepseek-flash}，CronIdleExecutor 回传裸名 {@code deepseek-flash}，
     * 二次解析落到字典序更小的 {@code ds-zcw-openai}，其失效 key 导致主会话每轮 401、
     * 子代理完成通知虽已注入却整轮失败。
     *
     * <p>边界：模型名本身含 '/'（Bedrock ARN / Azure 部署 ID）→ 不拼前缀、原样返回
     * （{@link #parseFullName} 会把首段当非 provider 前缀整体透传）；provider 缺失 / 名为空
     * / 名含 '/' → 退回裸名（保持旧行为）。
     *
     * @param providerMapper 提供商 mapper（null → 退回裸名）
     * @param model          已解析的模型记录（null → null）
     * @return 全名 {@code provider/model}；无法安全拼接时返回裸名
     */
    public static String toFullName(ProviderMapper providerMapper, ModelRecord model) {
        if (model == null) {
            return null;
        }
        String name = model.getName();
        if (name == null || name.isBlank() || name.indexOf('/') >= 0 || providerMapper == null) {
            return name;
        }
        try {
            ProviderRecord provider = providerMapper.selectOneById(model.getProviderId());
            if (provider == null || provider.getName() == null || provider.getName().isBlank()
                    || provider.getName().indexOf('/') >= 0) {
                return name;
            }
            return provider.getName() + "/" + name;
        } catch (Exception e) {
            log.debug("[ModelNameResolver] toFullName 查询 provider 失败: modelId={} err={}",
                model.getId(), e.getMessage());
            return name;
        }
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
            // [2026-10-29] 能力查询走「宽松解析」：provider 层拿到的是 SDK 裸名（openai/anthropic
            //   协议要求裸名），多 provider 同名时 {@link #resolve} 会打 ERROR —— 但这里既不是选模型、
            //   也拿不到调用方 provider 身份，null 的既有结果（不发 max_tokens）已经是对的。
            //   ⛔ 选模型链路仍走 resolve（fail-loud 不变）。
            ModelRecord model = resolveQuiet(modelMapper, providerMapper, modelName);
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

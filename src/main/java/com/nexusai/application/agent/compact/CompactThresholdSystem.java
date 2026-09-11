package com.nexusai.application.agent.compact;

import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.function.ToIntFunction;

/**
 * 阈值体系 · 对齐 CC {@code autoCompact.ts:30-145} 的窗口/阈值/四态计算
 * （getEffectiveContextWindowSize / getAutoCompactThreshold / calculateTokenWarningState）。
 *
 * <p><b>WHY 存在（唯一目标）</b>: Java 端阈值/blocking 窗口此前固定 200_000（探查 S-2/S-3/DRIFT-4），
 * 与 CC model-aware + reserved 减法 + env 覆盖 偏移。本类是阈值体系的<b>统一窗口来源</b>：
 * auto-compact 阈值与 blocking 预检都从这里取窗（同源，OD-12/OD-16 裁决）。
 *
 * <p><b>[P3-d] 口径边界（本类是阈值口径，不是展示口径）</b>: 本类所有窗口
 * （{@link #getEffectiveContextWindowSize} = 原始窗口 − summary 预留 − settings 收窄）与全部百分比
 * （{@link #calculateTokenWarningState} 的 {@code thresholdRelativePercentLeft}）都只服务
 * <b>自动压缩阈值判定</b>。前端「上下文已用 / 窗口（剩余%）」的<b>唯一权威</b>是
 * {@code ContextUsageCalculator.Snapshot}（服务端真实 usage + 模型原始 {@code max_context_tokens}
 * + 窗口相对百分比）；两套数值不同，禁止互相替代渲染。
 *
 * <h2>CC 对齐</h2>
 * <ul>
 *   <li>{@link #getMaxOutputTokensForModel(String)} — [W2-3] <b>DB 优先</b>（models.max_tokens
 *       前端可配列，>0 用之），未命中回落 CC {@code services/api/claude.ts:3399-3419
 *       getMaxOutputTokensForModel} 完整解析（模型族 default + tengu_otk_slot_v1 cap +
 *       CLAUDE_CODE_MAX_OUTPUT_TOKENS 有界 override，经
 *       {@link com.nexusai.infra.llm.AnthropicSdkProvider} 兜底委托，IMP2-25 M-1 收敛）</li>
 *   <li>{@link #getEffectiveContextWindowSize(String)} — CC {@code autoCompact.ts:33}
 *       = getContextWindowForModel − min(getMaxOutputTokensForModel, 20_000) reserved 减法，
 *       被 DB settings.auto_compact_window（:40-46 收窄语义，[W3-1] settings 权威替代
 *       {@code CLAUDE_CODE_AUTO_COMPACT_WINDOW} env）收窄</li>
 *   <li>{@link #getAutoCompactThreshold(String)} — CCB {@code autoCompact.ts:101-120
 *       getAutoCompactThreshold}（CC autoCompact.ts:72-91 旧版）= effectiveWindow −
 *       {@link #getAutocompactBufferTokens(String)} 动态档位（CCB autoCompact.ts:77-82：
 *       &gt;=800k → 50k；&gt;=400k → 30k；否则 13k），被
 *       {@code CLAUDE_AUTOCOMPACT_PCT_OVERRIDE}（CCB autoCompact.ts:108）按百分比取 min</li>
 *   <li>{@link #calculateTokenWarningState(int, String, boolean)} — CC {@code autoCompact.ts:93}
 *       四态：warning / error / auto / blocking + percentLeft</li>
 *   <li>{@link #getBlockingLimit(String)} — blocking = effectiveWindow − 3_000（:65 MANUAL_COMPACT_BUFFER_TOKENS），
 *       被 {@code CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE}（:127）直接覆盖</li>
 * </ul>
 *
 * <p><b>env 注入</b>: 2 个 override env（PCT / BLOCKING）+ 1M 禁用门经 {@link CompactEnvProperties}
 * （@ConfigurationProperties，prefix = claude）注入；[W3-1] {@code CLAUDE_CODE_AUTO_COMPACT_WINDOW}
 * 已删读取路（DB settings.auto_compact_window 权威，前端可配，用户拍板）。未设置时等价 CC
 * env undefined（不参与计算）。
 *
 * <p><b>model 上下文窗口解析器</b>: {@link #setModelContextWindowResolver(ToIntFunction)} 允许
 * 外部注入 DB model 元数据解析（models.max_context_tokens 模型级窗口，对齐旧 {@code computeBudgetFromGates} 语义；
 * W2-1 运行时窗口源由 providers.max_context_tokens 迁移至模型级）；
 * <b>有</b>配置源但解析不到（resolver 返回 ≤ 0 / 抛异常）时回落<b>本产品</b>「未配置窗口」默认值
 * {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}（1_048_576 = 1M，
 * 与前端「留空=1M」契约一致，2026-09-11 起；<b>不再是</b> CC 的 200_000）；
 * 未注入解析器（本部署无窗口源）时仍回落 CC 末位默认 200_000。
 *
 * <p><b>⚠ 200_000（{@link CompactConstants#MODEL_CONTEXT_WINDOW_DEFAULT}）在本类中只剩两个用途</b>：
 * ① {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 生效时的<b>收窄目标</b>（CC context.ts:76-82）；
 * ② 「本部署无窗口配置源」的末位兜底（CC context.ts:96-98，测试/非 Spring 场景）。
 * 它<b>不再</b>承担「用户没配窗口」的回落职责。
 */
public class CompactThresholdSystem {

    private static final Logger log = LoggerFactory.getLogger(CompactThresholdSystem.class);

    // ════════════════════════════════════════════════════════════════════
    // 常量来自 CompactConstants（13k/20k/3k + 窗口默认值）
    // ════════════════════════════════════════════════════════════════════

    /** 3 个 override env（null = 未设置，等价 CC env undefined）。 */
    private final CompactEnvProperties env;

    // ════════════════════════════════════════════════════════════════════
    // CCB 动态 autocompact buffer 档位（claude-code-best 真源，较 CC 固定 13k 新增）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 动态 buffer 档位窗口阈值 800k · CCB original: {@code getAutocompactBufferTokens}
     * (claude-code-best/src/services/compact/autoCompact.ts:79) {@code effectiveWindow >= 800_000 → 50_000}。
     */
    private static final int AUTOCOMPACT_BUFFER_TIER_WINDOW_800K = 800_000;

    /**
     * 动态 buffer 档位窗口阈值 400k · CCB original: {@code getAutocompactBufferTokens}
     * (claude-code-best/src/services/compact/autoCompact.ts:80) {@code effectiveWindow >= 400_000 → 30_000}。
     */
    private static final int AUTOCOMPACT_BUFFER_TIER_WINDOW_400K = 400_000;

    /** 动态 buffer 50k 档 · CCB original: autoCompact.ts:79（CC autoCompact.ts:62 固定 13k 无此档）。 */
    private static final int AUTOCOMPACT_BUFFER_TIER_50K = 50_000;

    /** 动态 buffer 30k 档 · CCB original: autoCompact.ts:80（CC autoCompact.ts:62 固定 13k 无此档）。 */
    private static final int AUTOCOMPACT_BUFFER_TIER_30K = 30_000;

    /**
     * model 上下文窗口解析器（CC getContextWindowForModel 的 Java 载体）· 可由 DB model
     * 元数据解析器覆盖（生产由 {@code AgentLoopContextFactory.wireThresholdSystemResolver} 注入）。
     *
     * <p><b>未注入时回落 {@link CompactConstants#MODEL_CONTEXT_WINDOW_DEFAULT}（200_000）</b>
     * —— 语义 = 「本部署根本没有窗口配置源」（无 Spring / 单测），等价 CC
     * {@code context.ts:96-98} 的末位兜底；与「<b>有</b>配置源但该模型无值」（resolver 返回 ≤ 0
     * 或抛异常）区分开：后者才是「用户没配窗口」，回落
     * {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}（1_048_576，前端契约 留空=1M），
     * 见 {@link #getContextWindowForModel(String)}。
     */
    private ToIntFunction<String> modelContextWindowResolver =
        model -> CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;

    /**
     * 「未配置窗口 → 用默认值」的 fail-loud 去重集合（每个 model 只 warn 一次，防每 turn 刷屏）。
     * 键 = 触发回落的 model 名（null 用 {@link #NULL_MODEL_KEY} 占位）。
     */
    private final java.util.Set<String> unconfiguredWindowWarned =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** null 模型名在 {@link #unconfiguredWindowWarned} 中的占位键（ConcurrentHashMap 不允许 null 键）。 */
    private static final String NULL_MODEL_KEY = "<null>";

    /** max_tokens 解析器注入（测试隔离 model 解析，对齐 setModelContextWindowResolver）· null = 走真实解析链。 */
    private ToIntFunction<String> maxOutputTokensResolver;

    /** [W2-3] DB models.max_tokens 解析（前端可配）· null = 无 Spring 上下文，回落 CC 家族表。 */
    private ModelMapper modelMapper;
    /** [W2-3] DB 提供商 mapper（全名感知 max_tokens 解析）· null = 按 name 兼容路径。 */
    private ProviderMapper providerMapper;

    /** [W3-1] DB settings 单例行 mapper（auto_compact_window 列，settings 权威）· null = 无 Spring 上下文/未注入。 */
    private SettingsMapper settingsMapper;

    public CompactThresholdSystem(CompactEnvProperties env) {
        this.env = env != null ? env : new CompactEnvProperties();
    }

    /**
     * 注入 model 上下文窗口解析器（DB models.max_context_tokens，对齐旧 computeBudgetFromGates）。
     *
     * @param resolver model → 上下文窗口 token 数；返回 ≤ 0 时视为「未配置/查不到」
     *                 （回落 {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}，
     *                  fail-loud warn 由 {@link #getContextWindowForModel(String)} 输出）
     */
    public void setModelContextWindowResolver(ToIntFunction<String> resolver) {
        this.modelContextWindowResolver =
            resolver != null ? resolver : model -> CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
    }

    /**
     * 注入 max_tokens 解析器（测试隔离 model 解析，对齐 {@link #setModelContextWindowResolver}）·
     * null = 走真实解析链（DB → CC 家族表）。生产不注入。
     */
    public void setMaxOutputTokensResolver(ToIntFunction<String> resolver) {
        this.maxOutputTokensResolver = resolver;
    }

    /**
     * [W2-3] DB 模型 mapper（models.max_tokens 列，前端可配）· @Autowired(required=false)：
     * 无 Spring 上下文 / mapper 缺失时静默回落 CC 家族表（getMaxOutputTokensForModel 兜底分支）。
     */
    @Autowired(required = false)
    public void setModelMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    /**
     * [W2-3] DB 提供商 mapper（max_tokens 全名感知解析用，可 null → 按 name 兼容路径）·
     * @Autowired(required=false)，同 {@link #setModelMapper(ModelMapper)} 回落语义。
     */
    @Autowired(required = false)
    public void setProviderMapper(ProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    /**
     * [W3-1] DB settings mapper（settings.auto_compact_window 读取）· @Autowired(required=false)：
     * 无 Spring 上下文 / mapper 缺失时静默回落（settings 未配置 → 不参与收窄，等价 CC env undefined）。
     */
    @Autowired(required = false)
    public void setSettingsMapper(SettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    /**
     * [W3-1] 从 DB settings 单例行（id=1）解析 auto_compact_window · 对齐 CC {@code autoCompact.ts:40-46}
     * {@code parseInt(process.env.CLAUDE_CODE_AUTO_COMPACT_WINDOW, 10)} 的 DB 承载。
     *
     * @return &gt; 0 的窗口值；null = 未配置 / mapper 缺失 / 行缺失 / 读取失败（等价 CC env undefined，不参与收窄）
     */
    private Integer resolveAutoCompactWindowFromSettings() {
        if (settingsMapper == null) {
            return null;
        }
        try {
            SettingsRecord settings = settingsMapper.selectOneById(1);
            if (settings != null) {
                Integer window = settings.getAutoCompactWindow();
                if (window != null && window > 0) {
                    return window;
                }
            }
        } catch (Exception e) {
            log.warn("[CompactThresholdSystem] settings auto_compact_window 读取失败, 不参与收窄: {}",
                e.toString());
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // 窗口解析 · 对齐 CC utils/context.ts:35-98
    // ════════════════════════════════════════════════════════════════════

    /**
     * 是否 1M 上下文禁用 · 对齐 CC {@code context.ts:31-33 is1mContextDisabled}
     * （{@code isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_1M_CONTEXT)}，HIPAA 合规禁用场景）。
     *
     * <p>env 载体 = {@link CompactEnvProperties#getDisable1MContext()}（Spring 宽松绑定
     * CLAUDE_CODE_DISABLE_1M_CONTEXT → claude.code-disable-1m-context；StringToBooleanConverter
     * 接受 CC 全真值集 {'1','true','yes','on'}）。
     *
     * @return true = 1M 上下文被禁用（has1mContext 恒 false，窗口超 200k 钳制回落 200k）
     */
    public boolean is1mContextDisabled() {
        return Boolean.TRUE.equals(env.getDisable1MContext());
    }

    /**
     * 是否 1M 上下文模型 · 对齐 CC {@code context.ts:35-40 has1mContext}
     * （{@code /\[1m\]/i.test(model)}，前置 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 门，IMP2-25 M-3）。
     */
    public boolean has1mContext(String model) {
        if (is1mContextDisabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[CompactThresholdSystem] CLAUDE_CODE_DISABLE_1M_CONTEXT 已禁用 1M 上下文，"
                    + "has1mContext 恒 false（CC context.ts:36-38）");
            }
            return false;
        }
        return model != null && model.toLowerCase().contains("[1m]");
    }

    /**
     * 模型上下文窗口（原始窗口，未减 summary 预留）· 对齐 CC {@code utils/context.ts:51-98
     * getContextWindowForModel} 的解析链，<b>但「DB 配置窗口」按 CC 的「用户显式覆盖」分支处理</b>。
     *
     * <p><b>解析链（自上而下，命中即返回）</b>：
     * <ol>
     *   <li>{@code [1m]} 后缀（未被禁用）→ {@link CompactConstants#CONTEXT_1M_WINDOW}（1_000_000）</li>
     *   <li>DB 模型窗口 {@code models.max_context_tokens}（resolver）——<b>&gt; 0 即原样采用</b>
     *       （不论大小：用户配 90k 就是 90k）</li>
     *   <li>resolver <b>返回 ≤ 0 / 抛异常</b>（有配置源但该模型未配置、行缺失、NULL）→
     *       {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}（1_048_576）+ fail-loud warn</li>
     *   <li>1M 被禁用（{@code CLAUDE_CODE_DISABLE_1M_CONTEXT}）且窗口 &gt; 200_000 →
     *       收窄到 {@link CompactConstants#MODEL_CONTEXT_WINDOW_DEFAULT}（200_000，HIPAA）</li>
     * </ol>
     *
     * <p><b>「未注入 resolver」与「resolver 返回 ≤ 0」的区别（勿混）</b>：前者 = 本部署没有窗口
     * 配置源（无 Spring / 单测）→ 保持 CC 末位默认 200_000；后者 = 有配置源（DB）但该模型没有值
     * → 本产品「未配置窗口」默认 1M（前端契约）。生产恒为后者（resolver 由
     * {@code AgentLoopContextFactory} 注入，见 {@code wireThresholdSystemResolver}）。
     *
     * <p><b>⚠ 本批的两处语义变更（2026-09-11，契约统一）</b>：
     * <ol>
     *   <li><b>「有配置源但该模型无值」的回落值 200_000 → 1_048_576</b>：本产品的窗口由用户配置，
     *       前端契约「留空 = 1M」；未配置时必须按 1M 计。事故实证见
     *       {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}。
     *       （「本部署无配置源」的末位兜底仍是 200_000，见下。）</li>
     *   <li><b>删除「&lt; 100k 能力门」（原 G-11，CC context.ts:75 {@code cap.max_input_tokens
     *       >= 100_000}）</b>：CC 该门作用于 {@code getModelCapability(model)} 返回的<b>静态能力表</b>
     *       （模型内置硬上限）；NexusAI 无此表，resolver 返回的是<b>用户显式配置</b>
     *       （DB {@code models.max_context_tokens}），对应 CC 的
     *       {@code CLAUDE_CODE_MAX_CONTEXT_TOKENS} 显式覆盖分支（context.ts:52-60）——
     *       该分支<b>没有</b>任何能力门，直接原样采用。原先套用能力门导致「用户配 90k 被静默
     *       抬成 200k」，与本批要修的「静默吞掉配置」同类。删除后 resolver &gt; 0 一律原样采用
     *       （与 {@link ContextUsageCalculator} 的窗口口径同源）。</li>
     * </ol>
     */
    public int getContextWindowForModel(String model) {
        if (has1mContext(model)) {
            return CompactConstants.CONTEXT_1M_WINDOW;
        }
        int resolved;
        try {
            resolved = modelContextWindowResolver.applyAsInt(model);
        } catch (Exception e) {
            log.warn("[CompactThresholdSystem] 模型窗口解析器异常，模型 {} 按「未配置窗口」处理: err={}",
                model, e.toString());
            resolved = CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT;
        }
        if (resolved <= 0) {
            // [fail-loud] 走到「未配置/查不到窗口 → 用默认值」这条路必须一眼可见
            // （此前只有一条隐晦的 db=null debug），每 model 只告警一次防刷屏。
            if (unconfiguredWindowWarned.add(model != null ? model : NULL_MODEL_KEY)) {
                log.warn("[CompactThresholdSystem] 模型 {} 未配置/查不到上下文窗口"
                        + "（models.max_context_tokens 为空或模型未命中, resolver={}）→ "
                        + "使用「未配置窗口」默认值 {}（前端契约 留空=1M）；"
                        + "如需其它窗口请在模型配置中显式填写",
                    model, resolved, CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT);
            }
            resolved = CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT;
        }
        // CC context.ts:76-82 — 窗口超 200k 且 1M 被禁用 → 收窄到 200k
        // （HIPAA 禁用场景 1M 能力端点仍可用于本地决策，但窗口不按 1M 计算）。
        // 注：本收窄在「未配置默认 1M」之后统一生效——若无条件早返回，未配置模型会在 1M 禁用
        // 部署下拿到 1M 窗口，HIPAA 上限被绕过（2026-09-11 修正）。
        if (is1mContextDisabled() && resolved > CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT) {
            if (log.isDebugEnabled()) {
                log.debug("[CompactThresholdSystem] CLAUDE_CODE_DISABLE_1M_CONTEXT 生效，"
                    + "窗口 {} 收窄到 {}（CC context.ts:75-81）",
                    resolved, CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT);
            }
            return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
        }
        return resolved;
    }

    /**
     * 无 Spring 实例时的窗口兜底（G-10 收敛同源）· 对齐 CC {@code context.ts:35-98} 的
     * {@code [1m]} + 默认分支（能力分支依赖注入的 DB resolver，未接线时按默认窗口计）。
     *
     * <p><b>WHY</b>: {@link com.nexusai.application.agent.toolsearch.ToolSearchService} /
     * {@link com.nexusai.application.agent.LlmAgentLoop} 等非 Spring 场景在未注入本类 bean 时，
     * 复用本类同源逻辑（{@code [1m]} 前置 + 禁用门 + 默认），避免各自私有实现造成双轨（G-10）。
     *
     * <p><b>本静态兜底 = 「本部署无窗口配置源」场景（非 Spring / 单测），无 DB 查询能力</b>，
     * 故保持 CC 末位默认 200_000（{@link CompactConstants#MODEL_CONTEXT_WINDOW_DEFAULT}）；
     * 有 DB 配置源时的「未配置窗口」默认是 1_048_576
     * （{@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}，前端契约 留空=1M）——
     * 生产走 {@link #getContextWindowForModel(String)} 而非本方法。
     *
     * @param model               模型名（可 null）
     * @param is1mContextDisabled 1M 上下文是否禁用（CLAUDE_CODE_DISABLE_1M_CONTEXT 真值）
     * @return {@code [1m]} 且未禁用 → CONTEXT_1M_WINDOW；否则 MODEL_CONTEXT_WINDOW_DEFAULT
     */
    public static int resolveWindowFallback(String model, boolean is1mContextDisabled) {
        if (!is1mContextDisabled && model != null && model.toLowerCase().contains("[1m]")) {
            return CompactConstants.CONTEXT_1M_WINDOW;
        }
        return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
    }

    /**
     * 模型最大输出 token · [W2-3] <b>DB 优先</b>：按 modelName 查 {@code models.max_tokens}
     * （前端可配列，>0 用之），未命中/无效回落 CC {@code claude.ts:3399-3419
     * getMaxOutputTokensForModel} 完整解析链（模型族 default → {@code tengu_otk_slot_v1}
     * cap(8k) → {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} 有界 override）。
     *
     * <p>[G-18] <b>单源委托</b> {@link com.nexusai.infra.llm.AnthropicSdkProvider#resolveMaxOutputTokensForModel
     * (com.nexusai.repository.provider.mapper.ModelMapper, com.nexusai.repository.provider.mapper.ProviderMapper, String)}
     * —— 请求体 buildMessageParams 与压缩链共用同一"DB 优先 → 家族表回落"实现（修复前请求体
     * 纯家族表链，同模型两值；现两处同源，同一 models.max_tokens 列）。本方法仅透传自带 mapper
     * （Spring 注入，null → 回落家族表，语义不变），DB 命中/回落 debug 日志由单源方法统一输出。
     *
     * @param model 模型名（可 null → DB 跳过，直接家族表默认 32k/64k）
     * @return max_tokens 解析值（DB 命中值 / cap+env 全链）
     */
    public int getMaxOutputTokensForModel(String model) {
        // [测试隔离] 注入 resolver 优先（对齐 modelContextWindowResolver）——测试可控 model 解析
        if (maxOutputTokensResolver != null) {
            return maxOutputTokensResolver.applyAsInt(model);
        }
        // [G-18] 单源统一：DB 优先 → CC 家族表回落（请求体与压缩链同源）
        return com.nexusai.infra.llm.AnthropicSdkProvider
            .resolveMaxOutputTokensForModel(modelMapper, providerMapper, model);
    }

    // ════════════════════════════════════════════════════════════════════
    // 有效窗口 / 阈值 / 四态 · 对齐 CC autoCompact.ts:30-145
    // ════════════════════════════════════════════════════════════════════

    /**
     * 有效上下文窗口 · 对齐 CC {@code autoCompact.ts:33-49 getEffectiveContextWindowSize}。
     *
     * <p>{@code getContextWindowForModel(model) - min(getMaxOutputTokensForModel(model), 20_000)}
     * reserved 减法 + {@code CLAUDE_CODE_AUTO_COMPACT_WINDOW} 收窄。
     *
     * @param model 模型名
     */
    public int getEffectiveContextWindowSize(String model) {
        return getEffectiveContextWindowSize(model, null);
    }

    /**
     * 有效上下文窗口（带显式窗口覆盖）· 对齐 CC {@code autoCompact.ts:40-46}
     * {@code CLAUDE_CODE_AUTO_COMPACT_WINDOW} 收窄语义（min 收窄）。
     * [merge 适配 2026-08-14] {@link AutoCompactor} 的 {@code setContextWindow} 已删除，
     * 原「供 setContextWindow 显式窗口」说明悬空移除——显式窗口现仅经 {@code contextWindowOverride}
     * 参数（caller 侧）提供；[W3-1] {@code env.getCodeAutoCompactWindow()} 收窄路已删除，
     * DB settings.auto_compact_window（{@link #resolveAutoCompactWindowFromSettings()}）权威。
     *
     * @param model                模型名
     * @param contextWindowOverride 显式窗口上限（null = 不使用；caller 侧覆盖，min 收窄语义）
     */
    public int getEffectiveContextWindowSize(String model, Integer contextWindowOverride) {
        int reservedTokensForSummary = Math.min(getMaxOutputTokensForModel(model), CompactConstants.MAX_OUTPUT_TOKENS_FOR_SUMMARY);
        int contextWindow = getContextWindowForModel(model);

        // caller 侧显式窗口覆盖（contextWindowOverride 参数，语义等价 CC env undefined 未设置）
        if (contextWindowOverride != null && contextWindowOverride > 0) {
            contextWindow = Math.min(contextWindow, contextWindowOverride);
        }
        // [W3-1] DB settings auto_compact_window 收窄（settings 权威，替代 CC env CLAUDE_CODE_AUTO_COMPACT_WINDOW，
        // 对齐 CC autoCompact.ts:40-46 的 min 收窄语义；未配置/读取失败 → 等价 CC env undefined 不参与）
        Integer settingsWindow = resolveAutoCompactWindowFromSettings();
        if (settingsWindow != null) {
            if (log.isDebugEnabled()) {
                log.debug("[CompactThresholdSystem] settings auto_compact_window 收窄生效: "
                        + "window={} settings={}（DB settings 权威, CC autoCompact.ts:40-46 等价）",
                    contextWindow, settingsWindow);
            }
            contextWindow = Math.min(contextWindow, settingsWindow);
        }

        return contextWindow - reservedTokensForSummary;
    }

    /**
     * 上下文感知自动压缩缓冲区 · 对齐 CCB（claude-code-best）{@code autoCompact.ts:77-82
     * getAutocompactBufferTokens}（CCB 在 CC autoCompact.ts:62 固定 13k 之上新增的动态档位）。
     *
     * <p><b>WHY</b>: 大上下文窗口需要更多 headroom——单 turn 可产出按比例的更多 token
     * （更长的模型输出 + 更大的工具结果），固定 13k 对大窗口偏紧（CCB autoCompact.ts:72-76 注释）。
     *
     * <p>档位判定基于 {@link #getEffectiveContextWindowSize(String)}（含 reserved 减法 + settings
     * 收窄），与 {@link #getAutoCompactThreshold(String)} 同源（CCB autoCompact.ts:78）。
     *
     * @param model 模型名
     * @return effectiveWindow &gt;= 800k → 50_000；&gt;= 400k → 30_000；否则 13_000
     */
    public int getAutocompactBufferTokens(String model) {
        return autocompactBufferForWindow(getEffectiveContextWindowSize(model));
    }

    /**
     * 按 effectiveWindow 选动态 buffer 档位 · CCB original: {@code getAutocompactBufferTokens} 内部档位
     * 判断（claude-code-best/src/services/compact/autoCompact.ts:79-81）。供
     * {@link #getAutocompactBufferTokens(String)} 与 {@link #getAutoCompactThreshold(String, Integer)}
     * 共用同一窗口档位——override 路径 buffer 随实际收窄后的窗口选档，不重复解析窗口（与 CCB 单窗口语义等价）。
     */
    private int autocompactBufferForWindow(int effectiveWindow) {
        int buffer;
        if (effectiveWindow >= AUTOCOMPACT_BUFFER_TIER_WINDOW_800K) {
            buffer = AUTOCOMPACT_BUFFER_TIER_50K;
        } else if (effectiveWindow >= AUTOCOMPACT_BUFFER_TIER_WINDOW_400K) {
            buffer = AUTOCOMPACT_BUFFER_TIER_30K;
        } else {
            buffer = CompactConstants.AUTOCOMPACT_BUFFER_TOKENS;
        }
        if (log.isDebugEnabled()) {
            log.debug("[CompactThresholdSystem] autocompact buffer 动态档位: effectiveWindow={} buffer={}（CCB autoCompact.ts:77-82）",
                effectiveWindow, buffer);
        }
        return buffer;
    }

    /**
     * 自动压缩阈值 · 对齐 CCB {@code autoCompact.ts:101-120 getAutoCompactThreshold}
     * （CC {@code autoCompact.ts:72-91} 旧版固定 13k，CCB 改为动态档位）。
     *
     * <p>{@code effectiveWindow - getAutocompactBufferTokens(model)}（CCB autoCompact.ts:104-105，
     * 动态档位见 {@link #getAutocompactBufferTokens(String)}）+
     * {@code CLAUDE_AUTOCOMPACT_PCT_OVERRIDE} 按百分比取 min（CCB autoCompact.ts:108-116）。
     *
     * @param model 模型名
     */
    public int getAutoCompactThreshold(String model) {
        return getAutoCompactThreshold(model, null);
    }

    /**
     * 自动压缩阈值（带显式窗口覆盖）· 语义同上，窗口覆盖先于阈值计算。
     */
    public int getAutoCompactThreshold(String model, Integer contextWindowOverride) {
        int effectiveContextWindow = getEffectiveContextWindowSize(model, contextWindowOverride);

        // CCB autoCompact.ts:104-105 — effectiveWindow − getAutocompactBufferTokens(model)
        // （动态档位：>=800k → 50k；>=400k → 30k；否则 13k，CCB autoCompact.ts:77-82）
        int autocompactThreshold = effectiveContextWindow - autocompactBufferForWindow(effectiveContextWindow);

        // CCB autoCompact.ts:108-116 — CLAUDE_AUTOCOMPACT_PCT_OVERRIDE 按百分比取 min
        Double envPercent = env.getAutocompactPctOverride();
        if (envPercent != null && envPercent > 0 && envPercent <= 100) {
            int percentageThreshold = (int) Math.floor(effectiveContextWindow * (envPercent / 100));
            return Math.min(percentageThreshold, autocompactThreshold);
        }

        return autocompactThreshold;
    }

    /**
     * blocking 上限 · 对齐 CC {@code autoCompact.ts:122-134 calculateTokenWarningState} 内联逻辑
     * （默认 {@code effectiveWindow - MANUAL_COMPACT_BUFFER_TOKENS(3_000)} + CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE）。
     *
     * <p>抽出为独立方法使 blocking 预检（LlmAgentLoop）与四态计算共用同源窗口（OD-12 同源）。
     *
     * @param model 模型名
     */
    public int getBlockingLimit(String model) {
        int actualContextWindow = getEffectiveContextWindowSize(model);

        // CC autoCompact.ts:126 — defaultBlockingLimit = effectiveWindow − 3_000
        int defaultBlockingLimit = actualContextWindow - CompactConstants.MANUAL_COMPACT_BUFFER_TOKENS;

        // CC autoCompact.ts:127-134 — CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE 直接覆盖
        Integer blockingLimitOverride = env.getCodeBlockingLimitOverride();
        return (blockingLimitOverride != null && blockingLimitOverride > 0)
            ? blockingLimitOverride
            : defaultBlockingLimit;
    }

    /**
     * token 警告四态计算 · 对齐 CC {@code autoCompact.ts:93-145 calculateTokenWarningState}。
     *
     * <p><b>[P3-d] 本方法产出的百分比是「阈值相对」口径，不是「上下文剩余百分比」</b>：
     * 分母 = {@code threshold}（auto 启用时 = {@link #getAutoCompactThreshold}，否则 = 有效窗口），
     * 即 CC {@code autoCompact.ts:108-111} 的<b>阈值判定</b>口径 —— 服务 warning/error/auto/blocking
     * 四态。对外显示的唯一权威是 {@code ContextUsageCalculator.Snapshot}（服务端真实 usage +
     * 模型原始窗口 + 窗口相对百分比）。两者数值会不同（阈值相对分母更小 → 同 usage 下百分比更低），
     * 前端不得把 {@code token_warning.percentLeft} 当余量渲染（见 {@link TokenWarningState}）。
     *
     * @param tokenUsage        当前 token 用量
     * @param model             模型名
     * @param autoCompactEnabled 是否启用自动压缩（CC {@code isAutoCompactEnabled()} 的 Java 载体；
     *                          由调用方传入，DISABLE_* env 判定归 IMP-07）
     */
    public TokenWarningState calculateTokenWarningState(int tokenUsage, String model, boolean autoCompactEnabled) {
        int autoCompactThreshold = getAutoCompactThreshold(model);
        // CC autoCompact.ts:104-106 — auto 启用时用 auto 阈值，否则用有效窗口
        int threshold = autoCompactEnabled ? autoCompactThreshold : getEffectiveContextWindowSize(model);

        // CC autoCompact.ts:108-111 — percentLeft = max(0, round((threshold - usage) / threshold * 100))
        int percentLeft = Math.max(0,
            (int) Math.round(((double) (threshold - tokenUsage) / threshold) * 100));

        // CC autoCompact.ts:113-117 — warning/error 阈值 = threshold − 20_000
        int warningThreshold = threshold - CompactConstants.WARNING_THRESHOLD_BUFFER_TOKENS;
        int errorThreshold = threshold - CompactConstants.ERROR_THRESHOLD_BUFFER_TOKENS;

        boolean isAboveWarningThreshold = tokenUsage >= warningThreshold;
        boolean isAboveErrorThreshold = tokenUsage >= errorThreshold;

        // CC autoCompact.ts:119-120 — auto 阈值判定
        boolean isAboveAutoCompactThreshold = autoCompactEnabled && tokenUsage >= autoCompactThreshold;

        boolean isAtBlockingLimit = tokenUsage >= getBlockingLimit(model);

        if (log.isDebugEnabled()) {
            log.debug("[CompactThresholdSystem] calculateTokenWarningState: usage={} model={} threshold={} "
                    + "percentLeft={} warn={} error={} auto={} blocking={}",
                tokenUsage, model, threshold, percentLeft,
                isAboveWarningThreshold, isAboveErrorThreshold,
                isAboveAutoCompactThreshold, isAtBlockingLimit);
        }

        return new TokenWarningState(
            percentLeft,
            isAboveWarningThreshold,
            isAboveErrorThreshold,
            isAboveAutoCompactThreshold,
            isAtBlockingLimit);
    }

    /**
     * 四态计算结果 · 对齐 CC {@code autoCompact.ts:95-102} 返回对象
     * {@code {percentLeft, isAboveWarningThreshold, isAboveErrorThreshold,
     * isAboveAutoCompactThreshold, isAtBlockingLimit}}。
     *
     * <p><b>[P3-d] 命名澄清（两套口径不得混用）</b>：
     * <ul>
     *   <li>{@code thresholdRelativePercentLeft} = <b>阈值相对</b>百分比
     *       {@code max(0, round((threshold − usage) / threshold × 100))}（CC autoCompact.ts:108-111），
     *       分母是自动压缩阈值（或有效窗口），<b>只服务内部四态阈值判定</b>。</li>
     *   <li>用户可见的「已用 / 窗口（剩余%）」唯一权威 = {@code ContextUsageCalculator.Snapshot}
     *       （服务端真实 usage + 模型原始 {@code max_context_tokens} + 窗口相对百分比）。</li>
     * </ul>
     * 二者数值不同（分母不同），前端禁止把前者当「剩余百分比」渲染（P3-d 前端配合项）。
     *
     * @param thresholdRelativePercentLeft   阈值相对百分比（内部阈值口径；见上）
     * @param isAboveWarningThreshold        是否超过 warning 阈值（threshold − 20k）
     * @param isAboveErrorThreshold          是否超过 error 阈值（threshold − 10k）
     * @param isAboveAutoCompactThreshold    是否达到自动压缩阈值
     * @param isAtBlockingLimit              是否达到 blocking 上限
     */
    public record TokenWarningState(
        int thresholdRelativePercentLeft,
        boolean isAboveWarningThreshold,
        boolean isAboveErrorThreshold,
        boolean isAboveAutoCompactThreshold,
        boolean isAtBlockingLimit) {

        /**
         * 旧名访问器（本批预算外消费方：{@code LlmAgentLoop} token_warning 推送 +
         * {@code AutoCompactor} 阈值日志，二处均在本批改动范围外）—— 语义与
         * {@link #thresholdRelativePercentLeft()} 完全相同，<b>非</b>窗口相对余量。
         * 两处调用方迁到新名后可删本方法。
         */
        public int percentLeft() {
            return thresholdRelativePercentLeft;
        }
    }
}

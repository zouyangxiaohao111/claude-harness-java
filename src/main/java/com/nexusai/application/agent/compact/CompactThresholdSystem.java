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
 * 未注入时回落 CC 默认 200_000（context.ts:9 MODEL_CONTEXT_WINDOW_DEFAULT）。
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
     * model 上下文窗口解析器（CC getContextWindowForModel 的 Java 载体）·
     * 默认返回 CC MODEL_CONTEXT_WINDOW_DEFAULT；可由 DB model 元数据解析器覆盖。
     */
    private ToIntFunction<String> modelContextWindowResolver =
        model -> CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;

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
     * 注入 model 上下文窗口解析器（DB provider.maxContextTokens，对齐旧 computeBudgetFromGates）。
     *
     * @param resolver model → 上下文窗口 token 数；返回 ≤ 0 时视为不可用（回落默认）
     */
    public void setModelContextWindowResolver(ToIntFunction<String> resolver) {
        this.modelContextWindowResolver = resolver != null ? resolver : model -> CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
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

    public int getContextWindowForModel(String model) {
        if (has1mContext(model)) {
            return CompactConstants.CONTEXT_1M_WINDOW;
        }
        int resolved;
        try {
            resolved = modelContextWindowResolver.applyAsInt(model);
        } catch (Exception e) {
            log.warn("[CompactThresholdSystem] modelContextWindowResolver 异常, 回落默认: {}", e.toString());
            resolved = CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
        }
        if (resolved <= 0) {
            return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
        }
        // [G-11 100k 能力门] CC context.ts:75 — capability 分支仅当 cap.max_input_tokens >= 100_000
        // 生效；resolver 返回 <100_000（DB 手配 90k 等小窗口）→ 能力分支落穿 → 回落默认 200k。
        if (resolved < CompactConstants.CONTEXT_WINDOW_CAPABILITY_GATE) {
            if (log.isDebugEnabled()) {
                log.debug("[CompactThresholdSystem] resolver 窗口 {} < 100k 能力下限, 回落默认 {}（CC context.ts:75 cap>=100k 才应用）",
                    resolved, CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT);
            }
            return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
        }
        // CC context.ts:76-82 — capability 超 200k 且 1M 被禁用 → 钳制回落 200k
        // （HIPAA 禁用场景 1M 能力端点仍可用于本地决策，但窗口不按 1M 计算）
        if (is1mContextDisabled() && resolved > CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT) {
            if (log.isDebugEnabled()) {
                log.debug("[CompactThresholdSystem] CLAUDE_CODE_DISABLE_1M_CONTEXT 生效，"
                    + "窗口 {} 钳制回落 {}（CC context.ts:75-81）",
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
     */
    public record TokenWarningState(
        int percentLeft,
        boolean isAboveWarningThreshold,
        boolean isAboveErrorThreshold,
        boolean isAboveAutoCompactThreshold,
        boolean isAtBlockingLimit) {}
}

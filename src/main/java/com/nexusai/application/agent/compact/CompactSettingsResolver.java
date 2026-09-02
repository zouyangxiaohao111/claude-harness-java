package com.nexusai.application.agent.compact;

import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 压缩配置统一实时读源 · [V52 token-compact-fix B1-5] settings 单行多列直读。
 *
 * <p><b>WHY 存在（唯一目标）</b>: 压缩总开关/各子开关（auto/reactive/contextCollapse/snip/
 * SM×2/cached-MC/time-based-MC + DISABLE_COMPACT/DISABLE_AUTO_COMPACT 一票否决）此前只有
 * env（CompactEnvProperties）+ FeatureFlags（record 一次性装配）+ 硬编码 三条来源，DB 无可配列。
 * 本类承载 settings 12 列的<b>实时读取</b>——每次 {@link SettingsMapper#selectOneById(int)} 单行
 * （id=1，settings 单行多列），不缓存（前端 PUT /api/v1/settings 后下一轮即生效，对齐 V42
 * agent_swarms_enabled "前端开关→PUT→DB→实时读源" 权威先例）。
 *
 * <h2>回落语义（null = 未配置/行缺失/异常）</h2>
 * <p>所有方法返回 <code>Boolean</code>/<code>Integer</code> 可空：null = DB 无值 → 调用方
 * 回落 CC 原判定链（env/FeatureFlags/硬编码默认），<b>零行为变化</b>。非 null = DB 有值覆盖原逻辑。
 * 对齐 {@link CompactThresholdSystem#resolveAutoCompactWindowFromSettings()}（:133-150）的
 * 方法体范式：mapper null / selectOneById 异常 / 行缺失 → 回落 null，失败记 warn 日志。
 *
 * <p><b>消费方</b>: AutoCompactor / ReactiveCompactor / MicroCompactor / ContextCollapse /
 * SessionMemoryService / LlmAgentLoop（snip 门控）。全部经 setter 注入（@Autowired(required=false)），
 * 无 Spring 上下文时静默回落（与 {@link CompactThresholdSystem} 一致）。
 */
public class CompactSettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(CompactSettingsResolver.class);

    /** settings 单例行 id（V42 先例 SETTINGS_SINGLETON_ID）。 */
    private static final int SETTINGS_SINGLETON_ID = 1;

    /** DB settings mapper · @Autowired(required=false)：无 Spring 上下文 / mapper 缺失时静默回落 null。 */
    private SettingsMapper settingsMapper;

    /**
     * DB settings mapper 注入 · @Autowired(required=false)，同
     * {@link CompactThresholdSystem#setSettingsMapper(SettingsMapper)} 回落语义。
     *
     * @param settingsMapper settings mapper（可 null）
     */
    @Autowired(required = false)
    public void setSettingsMapper(SettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    /**
     * 实时读取 settings 单例行 · 对齐 {@code CompactThresholdSystem.resolveAutoCompactWindowFromSettings}
     * （CompactThresholdSystem.java:133-150）方法体范式。
     *
     * @return settings 单例行；null = mapper 缺失 / 行缺失 / 读取异常
     */
    private SettingsRecord resolveSettingsRow() {
        if (settingsMapper == null) {
            return null;
        }
        try {
            return settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
        } catch (Exception e) {
            log.warn("[CompactSettingsResolver] settings 单例行(id=1) 读取失败, 压缩配置回落原逻辑: {}",
                e.toString());
            return null;
        }
    }

    /**
     * 实时读 {@code settings.auto_compact_enabled} · CC original: autoCompactEnabled
     * （config.ts:594，全局配置默认 true；消费于 autoCompact.ts:157）。
     *
     * @return true/false = DB 有值；null = 未配置（回落默认 true / 字段值）
     */
    public Boolean autoCompactEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getAutoCompactEnabled() : null;
    }

    /**
     * 实时读 {@code settings.reactive_compact_enabled} · CC original: reactiveCompactEnabled
     * （reactiveCompact.ts:43-44，内部含 DISABLE_COMPACT 检查）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 FeatureFlags.reactiveCompact()）
     */
    public Boolean reactiveCompactEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getReactiveCompactEnabled() : null;
    }

    /**
     * 实时读 {@code settings.context_collapse_enabled} · CC original: contextCollapseEnabled
     * （contextCollapse/index.ts:45）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 FeatureFlags.contextCollapse()）
     */
    public Boolean contextCollapseEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getContextCollapseEnabled() : null;
    }

    /**
     * 实时读 {@code settings.history_snip_enabled} · CC original: feature('HISTORY_SNIP')
     * （query.ts:115）+ snipCompactIfNeeded 门控（query.ts:401-405）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 ctx.featureFlags().historySnip()）
     */
    public Boolean historySnipEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getHistorySnipEnabled() : null;
    }

    /**
     * 实时读 {@code settings.sm_session_memory_enabled} · CC original: tengu_session_memory flag
     * （sessionMemoryCompact.ts:410-413）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean smSessionMemoryEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getSmSessionMemoryEnabled() : null;
    }

    /**
     * 实时读 {@code settings.sm_compact_enabled} · CC original: tengu_sm_compact flag
     * （sessionMemoryCompact.ts:414-416）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean smCompactEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getSmCompactEnabled() : null;
    }

    /**
     * 实时读 {@code settings.cached_microcompact_enabled} · CC original: 缓存微压缩路径
     * （microCompact.ts:296-302）。[R5] CC 外部构建 DCE 恒关——null = 回落 false（不启用）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean cachedMicrocompactEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getCachedMicrocompactEnabled() : null;
    }

    /**
     * 实时读 {@code settings.time_based_mc_enabled} · CC original: TimeBasedMCConfig.enabled
     * 主开关（timeBasedMCConfig.ts:19-27，GrowthBook tengu_slate_heron；默认 false）。
     *
     * @return true/false = DB 有值；null = 未配置（回落原配置）
     */
    public Boolean timeBasedMcEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getTimeBasedMcEnabled() : null;
    }

    /**
     * 实时读 {@code settings.time_based_mc_gap_minutes} · CC original: gapThresholdMinutes
     * （timeBasedMCConfig.ts:22-25，默认 60）。
     *
     * @return 分钟数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 60）
     */
    public Integer gapThresholdMinutes() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer gap = row.getTimeBasedMcGapMinutes();
            if (gap != null && gap > 0) {
                return gap;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.time_based_mc_keep_recent} · CC original: keepRecent
     * （timeBasedMCConfig.ts:26-27，默认 5）。
     *
     * @return 条数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 5）
     */
    public Integer keepRecent() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer keep = row.getTimeBasedMcKeepRecent();
            if (keep != null && keep > 0) {
                return keep;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.disable_compact} · CC original: DISABLE_COMPACT 一票否决
     * （autoCompact.ts:148/:253、reactiveCompact.ts:44、compact/index.ts:9）。
     *
     * <p><b>[DB 主控]（用户决策：DB 直接改库即生效）</b>：本列有值（非 null）直接生效——
     * true = 禁用（覆盖 env DISABLE_COMPACT），false = 显式放行（同样覆盖 env）。
     * null = 未配置，消费方回落 env DISABLE_COMPACT（部署级强制覆盖 fallback，CC 原语义）。
     *
     * @return true/false = DB 有值（主控）；null = 未配置（回落 env）
     */
    public Boolean disableCompact() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getDisableCompact() : null;
    }

    /**
     * 实时读 {@code settings.disable_auto_compact} · CC original: DISABLE_AUTO_COMPACT
     * （autoCompact.ts:152，保留手动 /compact）。
     *
     * <p><b>[DB 主控]（用户决策：DB 直接改库即生效）</b>：本列有值（非 null）直接生效——
     * true = 禁用自动压缩（覆盖 env DISABLE_AUTO_COMPACT），false = 显式放行（同样覆盖 env）。
     * null = 未配置，消费方回落 env DISABLE_AUTO_COMPACT（部署级强制覆盖 fallback，CC 原语义）。
     *
     * @return true/false = DB 有值（主控）；null = 未配置（回落 env）
     */
    public Boolean disableAutoCompact() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getDisableAutoCompact() : null;
    }

    /**
     * 实时读 {@code settings.cached_microcompact_trigger_threshold} · CC original:
     * TRIGGER_THRESHOLD（cachedMicrocompact.ts:19，默认 10）。
     *
     * @return 触发阈值 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 10）
     */
    public Integer cachedMicrocompactTriggerThreshold() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getCachedMicrocompactTriggerThreshold();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.cached_microcompact_keep_recent} · CC original: KEEP_RECENT
     * （cachedMicrocompact.ts:20，默认 5）。
     *
     * @return 保留条数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 5）
     */
    public Integer cachedMicrocompactKeepRecent() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getCachedMicrocompactKeepRecent();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_min_tokens} · CC original: SessionMemoryCompactConfig.minTokens
     * （sessionMemoryCompact.ts:57-61，默认 10000）。
     *
     * @return 最小 token 数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 10000）
     */
    public Integer smMinTokens() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmMinTokens();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_min_text_block_messages} · CC original:
     * SessionMemoryCompactConfig.minTextBlockMessages（sessionMemoryCompact.ts:59，默认 5）。
     *
     * @return 最小含文本块消息数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 5）
     */
    public Integer smMinTextBlockMessages() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmMinTextBlockMessages();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_max_tokens} · CC original: SessionMemoryCompactConfig.maxTokens
     * （sessionMemoryCompact.ts:60，默认 40000）。
     *
     * @return 压缩后保留上限 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 40000）
     */
    public Integer smMaxTokens() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmMaxTokens();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_minimum_message_tokens_to_init} · CC original:
     * SessionMemoryConfig.minimumMessageTokensToInit（sessionMemoryUtils.ts:33，默认 10000）。
     *
     * @return 初始化阈值 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 10000）
     */
    public Integer smMinimumMessageTokensToInit() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmMinimumMessageTokensToInit();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_minimum_tokens_between_update} · CC original:
     * SessionMemoryConfig.minimumTokensBetweenUpdate（sessionMemoryUtils.ts:34，默认 5000）。
     *
     * @return 两次更新间最小 token 增长 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 5000）
     */
    public Integer smMinimumTokensBetweenUpdate() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmMinimumTokensBetweenUpdate();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.sm_tool_calls_between_updates} · CC original:
     * SessionMemoryConfig.toolCallsBetweenUpdates（sessionMemoryUtils.ts:35，默认 3）。
     *
     * @return 两次更新间最小 tool call 数 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 3）
     */
    public Integer smToolCallsBetweenUpdates() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSmToolCallsBetweenUpdates();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.max_consecutive_autocompact_failures} · CC original:
     * MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES（autoCompact.ts:70，默认 3）。
     *
     * @return 连续失败阈值 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 3）
     */
    public Integer maxConsecutiveAutocompactFailures() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getMaxConsecutiveAutocompactFailures();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.max_ptl_retries} · CC original: MAX_PTL_RETRIES
     * （compact.ts:227，默认 3）。
     *
     * @return 分段截断重试上限 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 3）
     */
    public Integer maxPtlRetries() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getMaxPtlRetries();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.max_compact_streaming_retries} · CC original:
     * MAX_COMPACT_STREAMING_RETRIES（compact.ts:131，默认 2）。
     *
     * @return 压缩流式重试上限 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落默认 2）
     */
    public Integer maxCompactStreamingRetries() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getMaxCompactStreamingRetries();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * 实时读 {@code settings.snip_nudge_threshold} · CC original: SNIP_NUDGE_THRESHOLD
     * （snipCompact.ts:11，默认 30）——DB 承载 + 上下文窗口自适应（V55）。
     *
     * <p>null = 未配置 / 非正 → 回落窗口自适应算法
     * （SnipCompactor.resolveSnipNudgeThreshold 按 effectiveWindow 档位：≥800k → 150；
     * &gt;600k → 100；≥400k → 60；其他 → 30（CC 默认））；&gt;0 = DB 值直接覆盖窗口自适应。
     *
     * @return snip nudge 消息数阈值 = DB 有值（&gt; 0）；null = 未配置 / 非正（回落窗口自适应）
     */
    public Integer snipNudgeThreshold() {
        SettingsRecord row = resolveSettingsRow();
        if (row != null) {
            Integer v = row.getSnipNudgeThreshold();
            if (v != null && v > 0) {
                return v;
            }
        }
        return null;
    }
}

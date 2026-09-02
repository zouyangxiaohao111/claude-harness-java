package com.nexusai.application.agent.prompt;

import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 提示词对齐门控统一实时读源 · [prompt-align G0-03 V56] settings 单行多列直读。
 *
 * <p><b>WHY 存在（唯一目标）</b>: 提示词装配链各门控（task_reminder / deferred_tools_delta /
 * boundary / proactive / coordinator / skill_search_intent / scratchpad / frc /
 * agent_main_thread / verify_plan_reminder + language / output_style 两段注入）此前只有
 * env（FeatureFlags）+ 硬编码默认 + 既有判定类 三条来源，DB 无可配列。本类承载 settings
 * 12 列的<b>实时读取</b>——每次 {@link SettingsMapper#selectOneById(int)} 单行（id=1，
 * settings 单行多列），不缓存（前端 PUT /api/v1/settings 后下一轮即生效，对齐 V42
 * agent_swarms_enabled "前端开关→PUT→DB→实时读源" 权威先例）。
 *
 * <h2>回落语义（null = 未配置/行缺失/异常）</h2>
 * <p>所有方法返回 <code>Boolean</code>/<code>String</code> 可空：null = DB 无值 → 调用方
 * 回落 CC 原判定链（env / FeatureFlags / 硬编码默认 / 既有判定类），<b>零行为变化</b>。
 * 非 null = DB 有值覆盖原逻辑。方法体范式对齐 {@code CompactSettingsResolver}
 * （CompactSettingsResolver.java:56-67）：mapper null / selectOneById 异常 / 行缺失 →
 * 回落 null，失败记 warn 日志。
 *
 * <p><b>不含会话级 3 列</b>：loop_mode_override / non_interactive_session /
 * auto_mode_enabled 属 sessions 会话列（SessionRecord，见 G0-02 与 V57），不进本全局读源
 * （多会话-vs-CC-单会话铁律）。
 *
 * <p><b>消费方</b>: 后续批次 A/G 提示词装配链（UP/CTX 域）。全部经 setter 注入
 * （@Autowired(required=false)），无 Spring 上下文时静默回落（与 {@code CompactSettingsResolver}
 * 一致）。
 */
public class PromptAlignSettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(PromptAlignSettingsResolver.class);

    /** settings 单例行 id（V42 先例 SETTINGS_SINGLETON_ID）。 */
    private static final int SETTINGS_SINGLETON_ID = 1;

    /** DB settings mapper · @Autowired(required=false)：无 Spring 上下文 / mapper 缺失时静默回落 null。 */
    private SettingsMapper settingsMapper;

    /**
     * DB settings mapper 注入 · @Autowired(required=false)，同
     * {@code CompactSettingsResolver#setSettingsMapper(SettingsMapper)} 回落语义。
     *
     * @param settingsMapper settings mapper（可 null）
     */
    @Autowired(required = false)
    public void setSettingsMapper(SettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    /**
     * 实时读取 settings 单例行 · 方法体范式对齐
     * {@code CompactSettingsResolver.resolveSettingsRow()}（CompactSettingsResolver.java:56-67）。
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
            log.warn("[PromptAlignSettingsResolver] settings 单例行(id=1) 读取失败, 门控回落原逻辑: {}",
                e.toString());
            return null;
        }
    }

    /**
     * 实时读 {@code settings.task_reminder_enabled} · CC original: isTodoV2Enabled()
     * （utils/tasks.ts:133-139，决定 Task V2 工具集启用）→ task_reminder 系统提示附件注入门
     * （utils/messages.ts:3680-3698 case 'task_reminder'，先判 !isTodoV2Enabled() 直接返回 []）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 TaskSystemConfig.isTodoV2Enabled()，
     *     经 MDC isInteractive 会话感知，决策 #65；保留现状不迁移，DocReflect R2）
     */
    public Boolean taskReminderEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getTaskReminderEnabled() : null;
    }

    /**
     * 实时读 {@code settings.deferred_tools_delta_enabled} · CC original:
     * deferred_tools_delta 系统提示附件（utils/messages.ts:4178-4195，deferred 工具新增/移除
     * delta 注入）。
     *
     * @return true/false = DB 有值；null = 未配置（回落当前 gate，OPD-H-06 默认关）
     */
    public Boolean deferredToolsDeltaEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getDeferredToolsDeltaEnabled() : null;
    }

    /**
     * 实时读 {@code settings.system_prompt_boundary_enabled} · CC original:
     * SYSTEM_PROMPT_DYNAMIC_BOUNDARY 注入门（constants/prompts.ts:572-573：
     * BOUNDARY MARKER @572 + shouldUseGlobalCacheScope 门 @573）+
     * shouldUseGlobalCacheScope（utils/betas.ts:227-233，firstParty && 未禁用实验 beta）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 GlobalCacheScope.shouldUseGlobalCacheScope()
     *     firstParty 判定链）
     */
    public Boolean systemPromptBoundaryEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getSystemPromptBoundaryEnabled() : null;
    }

    /**
     * 实时读 {@code settings.proactive_enabled} · CC original: utils/systemPrompt.ts:105
     * (feature('PROACTIVE') || feature('KAIROS')) && isProactiveActive（主线程 agent 时自定义
     * agent 指令追加模式，非替换默认 prompt）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean proactiveEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getProactiveEnabled() : null;
    }

    /**
     * 实时读 {@code settings.coordinator_mode_enabled} · CC original: utils/systemPrompt.ts:63-65
     * feature('COORDINATOR_MODE') && isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE) &&
     * !mainThreadAgentDefinition → 走 coordinator 专用 prompt。
     *
     * @return true/false = DB 有值；null = 未配置（回落 CoordinatorMode.isCoordinatorMode()，
     *     feature + env 双真）
     */
    public Boolean coordinatorModeEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getCoordinatorModeEnabled() : null;
    }

    /**
     * 实时读 {@code settings.skill_search_intent_enabled} · CC original:
     * services/skillSearch/intentNormalize.ts:80 process.env.SKILL_SEARCH_INTENT_ENABLED === '1'
     * （查询意图归一化，TF-IDF 见英文任务词）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 env，默认关）
     */
    public Boolean skillSearchIntentEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getSkillSearchIntentEnabled() : null;
    }

    /**
     * 实时读 {@code settings.scratchpad_enabled} · CC original: constants/prompts.ts:797-819
     * getScratchpadInstructions() 内 isScratchpadEnabled()（scratchpad 目录使用指令，非空才注入）。
     * Java 无 Statsig 门。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean scratchpadEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getScratchpadEnabled() : null;
    }

    /**
     * 实时读 {@code settings.frc_enabled} · CC original: constants/prompts.ts:821-839
     * getFunctionResultClearingSection() feature('CACHED_MICROCOMPACT') && getCachedMCConfigForFRC
     * （Function Result Clearing 段，工具结果自动清场提示）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean frcEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getFrcEnabled() : null;
    }

    /**
     * 实时读 {@code settings.agent_main_thread_enabled} · CC original: utils/systemPrompt.ts:77-83
     * mainThreadAgentDefinition 分支（主线程 agent 定义非空 → 其 getSystemPrompt() 作为系统提示
     * 而非默认段）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean agentMainThreadEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getAgentMainThreadEnabled() : null;
    }

    /**
     * 实时读 {@code settings.verify_plan_reminder_enabled} · CC original: utils/messages.ts:4240-4251
     * case 'verify_plan_reminder'（CLAUDE_CODE_VERIFY_PLAN==='true' → VerifyPlanExecution 校验
     * 提示注入）。
     *
     * @return true/false = DB 有值；null = 未配置（回落 false）
     */
    public Boolean verifyPlanReminderEnabled() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getVerifyPlanReminderEnabled() : null;
    }

    /**
     * 实时读 {@code settings.language} · CC original: constants/prompts.ts:142-149
     * getLanguageSection(languagePreference)（# Language 段，空 preference → null 不注入）。
     *
     * @return 语言串 = DB 有值；null = 未配置（不注入 Language 段）
     */
    public String language() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getLanguage() : null;
    }

    /**
     * 实时读 {@code settings.output_style} · CC original: constants/prompts.ts:151-158
     * getOutputStyleSection(outputStyleConfig)（# Output Style 段，null 配置 → 不注入）。
     *
     * @return 输出风格串 = DB 有值；null = 未配置（不注入 Output Style 段）
     */
    public String outputStyle() {
        SettingsRecord row = resolveSettingsRow();
        return row != null ? row.getOutputStyle() : null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // [SP-14] 静态槽位 · BoundaryReader 先例（CompactConversation.java setSettingsResolver）
    // ────────────────────────────────────────────────────────────────────────

    /** 静态 resolver 桥 · SystemPromptAssembler 静态上下文读取（ToolRegistrationConfig 接线）。 */
    private static volatile PromptAlignSettingsResolver staticResolver;

    /**
     * 注入静态槽位 · [SP-14] 由 ToolRegistrationConfig 接线（同 BoundaryReader.setSettingsResolver
     * 先例）。null 注入 → 复位（读侧回落 null → 原判定链）。
     *
     * @param resolver PromptAlignSettingsResolver bean（可 null）
     */
    public static void setStaticResolver(PromptAlignSettingsResolver resolver) {
        PromptAlignSettingsResolver.staticResolver = resolver;
    }

    /**
     * 静态读 boundary 门 · [SP-14] SystemPromptAssembler 全构造点经本方法读 DB
     * settings.system_prompt_boundary_enabled 覆盖 firstParty 判定链（null → 回落
     * globalCacheScopeGate）。与 {@link #systemPromptBoundaryEnabled()} 同一数据源（DB 单行），
     * 无分叉。
     *
     * @return true/false = DB 有值；null = 未配置（回落 GlobalCacheScope firstParty 判定）
     */
    public static Boolean staticSystemPromptBoundaryEnabled() {
        PromptAlignSettingsResolver r = staticResolver;
        return r != null ? r.systemPromptBoundaryEnabled() : null;
    }
}

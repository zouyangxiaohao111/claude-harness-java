package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 提示词对齐门控统一实时读源 · [prompt-align G0-03 V56] settings 单行多列直读。
 *
 * <p><b>WHY 存在（唯一目标）</b>: 提示词装配链各门控（task_reminder / boundary / proactive /
 * coordinator / skill_search_intent / scratchpad / frc / agent_main_thread /
 * verify_plan_reminder + language / output_style 两段注入）此前只有
 * env（FeatureFlags）+ 硬编码默认 + 既有判定类 三条来源，DB 无可配列。本类承载 settings
 * 11 列的<b>实时读取</b>——每次 {@link SettingsMapper#selectOneById(java.io.Serializable)} 单行（id=1，
 * settings 单行多列），不缓存（前端 PUT /api/v1/settings 后下一轮即生效，对齐 V42
 * agent_swarms_enabled "前端开关→PUT→DB→实时读源" 权威先例）。
 *
 * <p><b>口径：这里的「11」是【本类的读集】</b>（12 − 已退役的 {@code deferred_tools_delta_enabled}
 * —— 其读方法 {@code deferredToolsDeltaEnabled()} 已随 2.1.278 对齐删除；该 DB 列本身亦已由
 * V77 迁移 {@code DROP COLUMN} 清除）。{@code SettingsService} 的 merge / 回显（透出）集同步收为
 * 11 列 ⇒ <b>三处（本类读集 / merge 集 / 透出集）现同为 11 列，不再有数字分歧</b>。
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

    // ────────────────────────────────────────────────────────────────────────
    // [coordinator-session V75] 会话列读源 · sessions.coordinator_mode 按 sessionId 直查
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 会话行 mapper · @Autowired(required=false)：无 Spring 上下文 / mapper 缺失时
     * {@link #sessionCoordinatorMode(String)} 恒返 null（会话层缺席 → 判定回落 settings / env）。
     *
     * <p>与 {@link #settingsMapper} 同为「可空注入 + 失败记 warn」范式。生产由 Spring 注入
     * {@code SessionMapper} bean；单测可直接 {@link #setSessionMapper} 或走
     * {@link #coordinatorModeActive(Boolean, PromptAlignSettingsResolver, CoordinatorMode)}
     * 纯函数（不依赖本 mapper）。
     */
    private com.nexusai.repository.session.mapper.SessionMapper sessionMapper;

    /**
     * 会话行 mapper 注入 · @Autowired(required=false)，同 {@link #setSettingsMapper} 回落语义。
     *
     * @param sessionMapper 会话 mapper（可 null）
     */
    @Autowired(required = false)
    public void setSessionMapper(com.nexusai.repository.session.mapper.SessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /**
     * 实时读 {@code sessions.coordinator_mode}（V75 会话列）· <b>三态</b>：
     * <ul>
     *   <li>{@code TRUE}  = 该会话强制 coordinator（列 = 1，最高优先级层）</li>
     *   <li>{@code FALSE} = 该会话强制普通（列 = 0，压过全局 settings 开）</li>
     *   <li>{@code null}  = 未设置（列 NULL / 行缺失 / mapper 未注入 / 读取异常）→ 回落下一层</li>
     * </ul>
     *
     * <p><b>WHY 按 sessionId 直查而非进程级</b>：CC 的 coordinator 是会话属性，恢复会话时
     * {@code matchSessionMode}（coordinator/coordinatorMode.ts:49-78）翻的是<b>进程 env</b> —— 单进程
     * 单会话下成立；Web 多会话后端多会话并存同一进程，进程级 env 无法表达「A 会话是协调者、B 会话
     * 不是」，且本仓铁律禁止会话态经 ThreadLocal/MDC 读 ⇒ 唯一合规来源 = 按 sessionId 查 DB。
     *
     * <p>失败语义对齐 {@link #resolveSettingsRow()}：查询异常 → warn + null（回落下一层，
     * <b>不</b>抛出、<b>不</b>默认开）。
     *
     * @param sessionId 会话 ID（null/blank → null）
     * @return 见上三态
     */
    public Boolean sessionCoordinatorMode(String sessionId) {
        if (sessionMapper == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            com.nexusai.repository.session.entity.SessionRecord row = sessionMapper.selectOneById(sessionId);
            if (row == null || row.getCoordinatorMode() == null) {
                return null;
            }
            return row.getCoordinatorMode() != 0;
        } catch (Exception e) {
            log.warn("[PromptAlignSettingsResolver] 会话行(session={}) 读取失败, coordinator 会话级层缺席回落下一层: {}",
                sessionId, e.toString());
            return null;
        }
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

    // ────────────────────────────────────────────────────────────────────────
    // [coordinator-cfg] coordinator 模式门控 · 统一判定（唯一真源）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * coordinator 模式门控 <b>统一判定（唯一实现，全仓唯一真源）</b>：把「会话级覆盖」/
     * 「DB 覆盖」/「feature + env」三层合并为一个判定，供所有 coordinator 门共用。
     *
     * <p><b>优先级（唯一一份，就在本方法体内）</b>：
     * ① 会话级 {@code sessions.coordinator_mode}（V75 会话列，tri-state）
     * → ② 全局 {@code settings.coordinator_mode_enabled}（V56 单例列）
     * → ③ {@code feature('COORDINATOR_MODE') && env(CLAUDE_CODE_COORDINATOR_MODE)}
     * （{@link CoordinatorMode#isCoordinatorMode()}）。<b>低层只有在本层为 null 时才被求值</b>。
     *
     * <p><b>WHY 存在（本方法要修的故障形态）</b>：coordinator 的激活来源散在
     * 会话列 / DB settings / feature / env 多处（前两者都是「有值即覆盖」语义）——
     * DB {@code settings.coordinator_mode_enabled}（前端「设置 → 环境配置 → 协调者模式」勾选框，
     * 经 {@link #coordinatorModeEnabled()} 实时读）、feature {@code nexusai.feature.coordinator-mode}
     * （application.yml 恒 false 且无 UI 可改）、env {@code CLAUDE_CODE_COORDINATOR_MODE}。
     * 语义 = <b>上层有值即用；null 才回落下一层</b>。
     *
     * <p>此前该 DB 覆盖只落在<b>部分</b>消费点（提示词分支 / userContext），其余门仍只读
     * {@code CoordinatorMode.isCoordinatorMode()} ⇒ <b>DB-only 激活即半激活</b>：系统提示已切成协调者版
     * （要求 {@code subagent_type: "worker"}），而顶层工具池没裁、权限链没变、子代理 summary / fork
     * 互斥门没变。收敛为一处后，新增任何 coordinator 门都只有一个入口可调。
     *
     * <p><b>为什么是纯函数（收 resolver / 回落层为形参）</b>：调用方散布在静态上下文
     * （{@code LlmAgentLoop.sessionVisibleToolsBase} 等无 ctx 的 static 路径）与实例上下文，
     * 且各自的「feature+env 回落层」持有形式不同（静态槽 / 字段 / 方法形参）。判定<b>优先级</b>必须只有
     * 一份（本方法体），来源解析由调用方决定：静态上下文用
     * {@link #staticCoordinatorModeActive(CoordinatorMode)}（静态槽 = ToolRegistrationConfig 接线的
     * 主 bean），持有 ctx 的调用方传 ctx 的 resolver，{@code BuiltInAgents} 传自己的静态槽。
     *
     * <p><b>与 {@link CoordinatorMode#isCoordinatorMode()} 的分工</b>：后者是 CC 原判定链
     * （feature && env），本方法<b>不替换</b>它，而是它作为 DB 未配置时的回落层。
     *
     * @param sessionOverride   会话级层（{@code sessions.coordinator_mode} V75，经
     *                          {@link #sessionCoordinatorMode(String)} 取出；null = 该会话未设置）
     * @param dbResolver        DB 读源（{@link #coordinatorModeEnabled()} 的数据源；null = 无 DB 层）
     * @param envFeatureFallback feature+env 回落层（null = 无回落层，末层判 false）
     * @return true = coordinator 激活（三层任一有值即用；全 null → 回落层的 {@code isCoordinatorMode()}）
     */
    public static boolean coordinatorModeActive(
            Boolean sessionOverride,
            PromptAlignSettingsResolver dbResolver,
            CoordinatorMode envFeatureFallback) {
        if (sessionOverride != null) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptAlignSettingsResolver] coordinator 门统一判定: 会话级(sessions.coordinator_mode)"
                    + "={} 覆盖 settings / feature+env 层（会话列 NULL 才回落）", sessionOverride);
            }
            return sessionOverride;
        }
        Boolean dbValue = (dbResolver == null) ? null : dbResolver.coordinatorModeEnabled();
        if (dbValue != null) {
            if (log.isDebugEnabled()) {
                log.debug("[PromptAlignSettingsResolver] coordinator 门统一判定: DB(settings.coordinator_mode_enabled)"
                    + "={} 覆盖 feature+env 层（无 dbResolver 或该列为 null 才回落）", dbValue);
            }
            return dbValue;
        }
        boolean fallback = envFeatureFallback != null && envFeatureFallback.isCoordinatorMode();
        if (log.isDebugEnabled()) {
            log.debug("[PromptAlignSettingsResolver] coordinator 门统一判定: 会话列未设置 + DB 未配置"
                + " → 回落 feature('COORDINATOR_MODE') && env(CLAUDE_CODE_COORDINATOR_MODE)={}", fallback);
        }
        return fallback;
    }

    /**
     * 两层兼容重载（<b>无会话上下文</b>的调用方）· 等价
     * {@code coordinatorModeActive(null, dbResolver, envFeatureFallback)}。
     *
     * <p><b>⚠️ 持有 sessionId 的调用方不得用本重载</b>——那正是「半激活」的成因形态：会话级覆盖
     * 会被静默忽略，A 会话（协调者）与 B 会话（普通）判定出同一值。请改用
     * {@link #coordinatorModeActiveForSession(String, PromptAlignSettingsResolver, CoordinatorMode)}
     * 或 {@link #staticCoordinatorModeActiveForSession(String, CoordinatorMode)}。
     * 本重载仅保留给<b>结构上无会话</b>的门（启动期装配 / 进程级注册表构建）。
     *
     * @param dbResolver        DB 读源
     * @param envFeatureFallback feature+env 回落层
     * @return 见 {@link #coordinatorModeActive(Boolean, PromptAlignSettingsResolver, CoordinatorMode)}
     */
    public static boolean coordinatorModeActive(
            PromptAlignSettingsResolver dbResolver, CoordinatorMode envFeatureFallback) {
        return coordinatorModeActive(null, dbResolver, envFeatureFallback);
    }

    /**
     * 会话感知统一判定（<b>持有 sessionId 的调用方走本入口</b>）· 会话层来源 = 本方法按 sessionId
     * 读 {@code sessions.coordinator_mode}（{@link #sessionCoordinatorMode(String)}），随后并入
     * 唯一优先级实现。
     *
     * <p><b>与 CC resume 对齐的对应关系</b>：CC 在恢复会话时读存档 mode（{@code result.mode}）后
     * 调 {@code matchSessionMode} <b>翻进程 env</b>（coordinatorMode.ts:64-69），使后续
     * {@code isCoordinatorMode()} 读到会话模式；Web 多会话无进程级会话属性可翻，等价落点 = 判定时
     * 按 sessionId 直查会话列。差别是「对齐时点」：CC 一次性翻 env（此后进程内所有判定同值），
     * 本仓逐次按 sessionId 解析（因此天然支持多会话并存且互不串味）。
     *
     * @param sessionId         会话 ID（null/blank → 会话层缺席，等价两层重载）
     * @param dbResolver        DB 读源（{@link #sessionCoordinatorMode(String)} 的载体）
     * @param envFeatureFallback feature+env 回落层
     * @return 见 {@link #coordinatorModeActive(Boolean, PromptAlignSettingsResolver, CoordinatorMode)}
     */
    public static boolean coordinatorModeActiveForSession(
            String sessionId,
            PromptAlignSettingsResolver dbResolver,
            CoordinatorMode envFeatureFallback) {
        // 无会话身份（null/blank）→ 会话层结构性缺席：**不**调 mapper（省一次查询，且避免把
        //   「无会话」误当「该会话未设置」之外的东西）。
        // ⚠️ 夹具陷阱：Mockito 对返回 Boolean（包装类型）的方法默认返回 **FALSE**（不是 null，
        //   见 ReturnsEmptyValues → Primitives.defaultValue）。故任何 mock/桩化 resolver 若未显式
        //   stub 本方法，会被读成「该会话显式关」而压掉 settings / env 层。测试夹具必须
        //   `when(r.sessionCoordinatorMode(any())).thenReturn(null)` 表示「未设置」。
        Boolean sessionOverride = (dbResolver == null || sessionId == null || sessionId.isBlank())
            ? null
            : dbResolver.sessionCoordinatorMode(sessionId);
        return coordinatorModeActive(sessionOverride, dbResolver, envFeatureFallback);
    }

    /**
     * 静态槽位便捷重载 · DB 读源 = {@link #staticResolver}（ToolRegistrationConfig 接线的主 bean，
     * 与 ctx.sessionState() 的 resolver 同一实例）。
     * 供无 ctx 的静态门（工具池裁剪 / bare 追加 / 权限上下文 / 子代理 summary / fork 互斥）调用。
     *
     * @param envFeatureFallback feature+env 回落层（各调用方的 CoordinatorMode 持有物）
     * @return 见 {@link #coordinatorModeActive(PromptAlignSettingsResolver, CoordinatorMode)}
     */
    public static boolean staticCoordinatorModeActive(CoordinatorMode envFeatureFallback) {
        return coordinatorModeActive(staticResolver, envFeatureFallback);
    }

    /**
     * 静态槽位 + 会话感知重载 · 会话层来源 = {@link #staticResolver} 持有的 mapper 按 sessionId 直查
     * {@code sessions.coordinator_mode}（静态门拿不到 ctx 的 resolver 实例，但 staticResolver 槽
     * 就是同一个 Spring bean，见 {@link #setStaticResolver}）。
     *
     * <p><b>fail-soft</b>：{@link #staticResolver} 未接线（启动期 / 纯 POJO 单测）→ 会话层缺席，
     * 等价 {@link #staticCoordinatorModeActive(CoordinatorMode)}（两层链），不抛异常。
     *
     * @param sessionId         会话 ID（null/blank → 会话层缺席）
     * @param envFeatureFallback feature+env 回落层
     * @return 见 {@link #coordinatorModeActive(Boolean, PromptAlignSettingsResolver, CoordinatorMode)}
     */
    public static boolean staticCoordinatorModeActiveForSession(
            String sessionId, CoordinatorMode envFeatureFallback) {
        return coordinatorModeActiveForSession(sessionId, staticResolver, envFeatureFallback);
    }

    // ────────────────────────────────────────────────────────────────────────
    // [coordinator-session V75] transcript mode 行取值 · CC ModeEntry 字面量
    // ────────────────────────────────────────────────────────────────────────

    /** CC {@code ModeEntry.mode} 协调者字面量（types/logs.ts:140）。 */
    public static final String MODE_LABEL_COORDINATOR = "coordinator";

    /** CC {@code ModeEntry.mode} 普通字面量（types/logs.ts:140）。 */
    public static final String MODE_LABEL_NORMAL = "normal";

    /**
     * 会话当前有效 coordinator 模式的 <b>CC ModeEntry 标签</b>（{@code "coordinator"|"normal"}）·
     * 供 transcript {@code {"type":"mode","mode":…}} 行写入（{@code SessionStorage.reAppendSessionMetadata}）。
     *
     * <p><b>WHY 取有效值而非会话列原值</b>：CC 写 mode 行的是 {@code saveMode(isCoordinatorMode() ?
     * 'coordinator' : 'normal')}（cli/print.ts:5206 / commands/clear/conversation.ts:274 /
     * sessionRestore.ts:528）—— 写的是<b>判定结果</b>（含 env 兜底），不是某个单一来源。本方法同义：
     * 走唯一三层判定后再落字面量，读写两侧不会分叉。
     *
     * @param sessionId         会话 ID（会话层来源）
     * @param dbResolver        DB 读源（可 null）
     * @param envFeatureFallback feature+env 回落层（可 null）
     * @return {@link #MODE_LABEL_COORDINATOR} 或 {@link #MODE_LABEL_NORMAL}
     */
    public static String coordinatorSessionModeLabel(
            String sessionId,
            PromptAlignSettingsResolver dbResolver,
            CoordinatorMode envFeatureFallback) {
        return coordinatorModeActiveForSession(sessionId, dbResolver, envFeatureFallback)
            ? MODE_LABEL_COORDINATOR : MODE_LABEL_NORMAL;
    }

    /**
     * 静态槽位 + 会话感知的 mode 标签（transcript 写侧便捷入口）· 与
     * {@link #staticCoordinatorModeActiveForSession(String, CoordinatorMode)} 同源。
     *
     * @param sessionId         会话 ID
     * @param envFeatureFallback feature+env 回落层
     * @return {@link #MODE_LABEL_COORDINATOR} 或 {@link #MODE_LABEL_NORMAL}
     */
    public static String staticCoordinatorSessionModeLabel(
            String sessionId, CoordinatorMode envFeatureFallback) {
        return staticCoordinatorModeActiveForSession(sessionId, envFeatureFallback)
            ? MODE_LABEL_COORDINATOR : MODE_LABEL_NORMAL;
    }
}

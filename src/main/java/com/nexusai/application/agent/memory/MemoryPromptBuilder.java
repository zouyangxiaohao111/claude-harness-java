package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 行为指令 prompt 构建器 · 对齐 CC {@code memdir/memdir.ts} + {@code memdir/memoryTypes.ts}.
 *
 * <p>CC original: {@code buildMemoryLines}(memdir.ts:199-266) /
 * {@code buildMemoryPrompt}(:272-316) / {@code loadMemoryPrompt}(:419-507) /
 * {@code buildSearchingPastContextSection}(:375-407) /
 * {@code buildAssistantDailyLogPrompt}(:327-370) /
 * {@code ensureMemoryDirExists}(:129-147) / {@code logMemoryDirCounts}(:153-185) /
 * {@code truncateEntrypointContent}(:57-103) /
 * {@code DIR_EXISTS_GUIDANCE}/@{code DIRS_EXIST_GUIDANCE}(:116-119)。
 *
 * <p>职责边界：CC 的 memory 系统提示词（systemPromptSection('memory', () => loadMemoryPrompt())，
 * prompts.ts:495）由三类指令文本构成——四类 taxonomy（user/feedback/project/reference）、
 * 两步保存（写文件 + MEMORY.md 索引）、recall/access 护栏（WHEN_TO_ACCESS + drift caveat +
 * TRUSTING_RECALL）。CC 注入的是「行为指令」而非旧 Java 的泛化「# Memory + MEMORY.md 索引块」。
 *
 * <p>接口适配：CC 编译期 feature gate / GB flag（feature('KAIROS')、getKairosActive()、
 * feature('TEAMMEM')、getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern') 等）Java 端收敛为
 * 注入式 {@link BooleanSupplier} 构造参数，测试可逐开关 on/off；生产默认经
 * {@link #productionDefault()} 提供（KAIROS 经部署标志接线；TEAMMEM 经 FeatureFlags 双门控
 * 接线（IMP-MV2-19）；tengu_moth_copse 经 {@link #productionDefaultWithMothCopse} / 四参
 * 全量重载接线 FeatureFlags.tenguMothCopse（IMP-MV2-12 单轨收敛，loadMemoryPrompt 装配点
 * 不得再走无参/2 参重载）；其余 GB flag 未接入前默认关闭）。
 */
public final class MemoryPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryPromptBuilder.class);

    // ── 常量 · CC memdir.ts ──

    /** CC original: ENTRYPOINT_NAME (memdir.ts:34) = 'MEMORY.md' */
    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    /** CC original: MAX_ENTRYPOINT_LINES (memdir.ts:35) = 200 */
    public static final int MAX_ENTRYPOINT_LINES = 200;
    /** CC original: MAX_ENTRYPOINT_BYTES (memdir.ts:38) = 25_000（长行索引防 200 行内逃逸） */
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;
    /** CC original: AUTO_MEM_DISPLAY_NAME (memdir.ts:39) = 'auto memory' */
    public static final String AUTO_MEM_DISPLAY_NAME = "auto memory";

    /**
     * CC original: {@code DIR_EXISTS_GUIDANCE}（memdir.ts:116-117）。
     * 目录已由 ensureMemoryDirExists 保证存在 → 提示模型直接用 Write 写，不要 mkdir/检查存在。
     */
    public static final String DIR_EXISTS_GUIDANCE =
        "This directory already exists — write to it directly with the Write tool (do not run mkdir or check for its existence).";

    /**
     * CC original: {@code DIRS_EXIST_GUIDANCE}（memdir.ts:118-119）。
     * 双目录（private + team）变体。旧 Java 反 CC 目录存在性指导文本已废除（DEL-M-01）。
     */
    public static final String DIRS_EXIST_GUIDANCE =
        "Both directories already exist — write to them directly with the Write tool (do not run mkdir or check for their existence).";

    /**
     * 搜索段使用的 Grep 工具名。CC GREP_TOOL_NAME = 'Grep'（GrepTool/prompt.ts:4），
     * B2 后 Java GrepTool.name() 对齐 CC 'Grep' → 给模型的调用形态用注册主名（行为对齐、接口适配）。
     */
    public static final String GREP_TOOL_NAME = "Grep";

    /** CC original: CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES env（memdir.ts:441-446） */
    public static final String COWORK_EXTRA_GUIDELINES_ENV = "CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES";

    // ── 注入式 gate ──

    private final AutoMemPaths autoMemPaths;
    private final BooleanSupplier autoMemoryEnabled;
    private final BooleanSupplier kairosActive;
    private final BooleanSupplier teamMemoryEnabled;
    private final BooleanSupplier coralFernFlag;
    private final BooleanSupplier mothCopseFlag;
    private final BooleanSupplier herringClockFlag;
    private final Supplier<String> coworkExtraGuidelinesEnv;
    private final Function<String, String> entrypointReader;
    /** FIX-MC：telemetry（null-safe）· 供 logMemoryDirCounts tengu_memdir_loaded 双发射 */
    private final Telemetry telemetry;

    /**
     * [IMP-C-4 · OPD-CM5-C-08] 生产静态遥测兜底 · 子代理 agent-memory 路径计数事件透传通道。
     *
     * <p>背景：AgentMemoryDirectory 经 IMP-F2-4（F-21，DC-V5-10）统一为共享单例
     * （DefaultHolder.INSTANCE，构造期无 Spring 依赖）→ 其 MemoryPromptBuilder 由
     * {@link #productionDefault()} 构造，实例 telemetry=null → {@link #emitMemdirLoaded} 跳过
     * （探查 C/CM-C3 R-3 / △-1：AgentMemoryDirectory 装配链 telemetry=null）。CC 端
     * agentMemory.ts:169-176 → buildMemoryPrompt:298 门控通过时<b>无条件</b> logEvent
     * （CC logEvent 无 telemetry 前置条件）。本静态 holder 由生产装配点（ToolRegistrationConfig
     * {@code agentMemoryDirectory()} @Bean）注入，{@link #emitMemdirLoaded} 在实例 telemetry
     * 为 null 时回落 —— 共享单例与 SubagentTool 各自实例共用同一发射通道，无需逐一构造器透传。
     *
     * <p>约定：实例 telemetry 优先（主 loop 四参装配点 LlmAgentLoop 已带实例注入）；
     * 静态 holder 仅兜底无实例注入的装配（agent-memory / 子代理路径）。测试不设置 → null，
     * 零行为变化。
     */
    private static volatile Telemetry productionTelemetry;

    /** [IMP-C-4 · OPD-CM5-C-08] 设置生产静态遥测兜底（装配点注入；null → 清除回落通道）。 */
    public static void setProductionTelemetry(Telemetry telemetry) {
        productionTelemetry = telemetry;
    }

    /**
     * [IMP-C-6 · OPD-CM5-C-10] 生产静态 coralFern 兜底 · agent-memory 单例装配面。
     *
     * <p>背景：AgentMemoryDirectory 经 IMP-F2-4（F-21，DC-V5-10）统一为共享单例
     * （DefaultHolder.INSTANCE，构造期无 Spring 依赖）→ 其 MemoryPromptBuilder 由
     * {@link #productionDefault()}（≤5 参链）构造，coralFernFlag 恒 false（探查 C-6：agent-memory
     * 子代理变体 searching-past 段不输出）。CC 端 agentMemory.ts:138-177 → buildMemoryPrompt →
     * buildMemoryLines（memdir.ts:263）在 flag 开时<b>无条件</b>注入 buildSearchingPastContextSection
     * （memdir.ts:376 getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern', false)）。本静态 holder
     * 由生产装配点（ToolRegistrationConfig {@code agentMemoryDirectory()} @Bean）注入
     * FeatureFlags.coralFern() 值 —— 共享单例 ≤5 参 productionDefault 装配共用同一门控源，无需逐一
     * 构造器透传（对齐 {@link #setProductionTelemetry} 先例）。
     *
     * <p>约定：实例显式注入优先（LlmAgentLoop loadMemoryPrompt 走 6 参全量重载接
     * FeatureFlags.coralFern()，不读本 holder）；≤5 参装配（agent-memory 单例）经本 holder；
     * 测试不设置 → null → {@link #isProductionCoralFernEnabled()} 返回 false（零行为变化）。
     */
    private static volatile BooleanSupplier productionCoralFern;

    /** [IMP-C-6 · OPD-CM5-C-10] 设置生产静态 coralFern 兜底（装配点注入；null → 清除回落通道）。 */
    public static void setProductionCoralFern(BooleanSupplier coralFern) {
        productionCoralFern = coralFern;
    }

    /** 生产静态 coralFern 兜底读取 · null → false（测试/未接线零行为变化）。 */
    private static boolean isProductionCoralFernEnabled() {
        BooleanSupplier f = productionCoralFern;
        return f != null && f.getAsBoolean();
    }

    /**
     * 全参数构造器（测试注入式，9 参 · telemetry=null）。CC original: {@code loadMemoryPrompt} 的依赖
     * isAutoMemoryEnabled/feature('KAIROS')/getKairosActive()/feature('TEAMMEM')/
     * isTeamMemoryEnabled()/GB flags/process.env.CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES。
     *
     * @param autoMemPaths            CC getAutoMemPath/getAutoMemDailyLogPath（memdir/paths.ts）
     * @param autoMemoryEnabled        CC isAutoMemoryEnabled（paths.ts:30-56）
     * @param kairosActive             CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     * @param teamMemoryEnabled        CC feature('TEAMMEM') && isTeamMemoryEnabled()（memdir.ts:448-449；
     *                                 isTeamMemoryEnabled = isAutoMemoryEnabled() && tengu_herring_clock，
     *                                 teamMemPaths.ts:73-78；IMP-MV2-19 接线后生产 = FeatureFlags 双门控组合）
     * @param coralFernFlag            CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern', false)（memdir.ts:376）
     * @param mothCopseFlag            CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_moth_copse', false)（memdir.ts:422-425）→ skipIndex
     * @param herringClockFlag         CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)（memdir.ts:503）→
     *                                 disabled 分支 tengu_team_memdir_disabled 子事件门控
     * @param coworkExtraGuidelinesEnv CC process.env.CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES（memdir.ts:441-446）
     * @param entrypointReader         读 MEMORY.md 内容的函数（path → content；agent 变体 buildMemoryPrompt 用）
     */
    public MemoryPromptBuilder(
        AutoMemPaths autoMemPaths,
        BooleanSupplier autoMemoryEnabled,
        BooleanSupplier kairosActive,
        BooleanSupplier teamMemoryEnabled,
        BooleanSupplier coralFernFlag,
        BooleanSupplier mothCopseFlag,
        BooleanSupplier herringClockFlag,
        Supplier<String> coworkExtraGuidelinesEnv,
        Function<String, String> entrypointReader) {
        this(autoMemPaths, autoMemoryEnabled, kairosActive, teamMemoryEnabled,
            coralFernFlag, mothCopseFlag, herringClockFlag, coworkExtraGuidelinesEnv, entrypointReader, null);
    }

    /**
     * 全参数构造器（10 参 · 带 telemetry，null-safe）。FIX-MC：生产经 {@link #productionDefault(Telemetry)}
     * 注入 telemetry，测试传 null → 跳过 tengu_memdir_loaded/disabled 双发射（零行为变化）。
     *
     * @param telemetry telemetry（可 null；null → 遥测发射仅保留 debug 日志，不发射）
     */
    public MemoryPromptBuilder(
        AutoMemPaths autoMemPaths,
        BooleanSupplier autoMemoryEnabled,
        BooleanSupplier kairosActive,
        BooleanSupplier teamMemoryEnabled,
        BooleanSupplier coralFernFlag,
        BooleanSupplier mothCopseFlag,
        BooleanSupplier herringClockFlag,
        Supplier<String> coworkExtraGuidelinesEnv,
        Function<String, String> entrypointReader,
        Telemetry telemetry) {
        this.autoMemPaths = java.util.Objects.requireNonNull(autoMemPaths);
        this.autoMemoryEnabled = java.util.Objects.requireNonNull(autoMemoryEnabled);
        this.kairosActive = java.util.Objects.requireNonNull(kairosActive);
        this.teamMemoryEnabled = java.util.Objects.requireNonNull(teamMemoryEnabled);
        this.coralFernFlag = java.util.Objects.requireNonNull(coralFernFlag);
        this.mothCopseFlag = java.util.Objects.requireNonNull(mothCopseFlag);
        this.herringClockFlag = java.util.Objects.requireNonNull(herringClockFlag);
        this.coworkExtraGuidelinesEnv = java.util.Objects.requireNonNull(coworkExtraGuidelinesEnv);
        this.entrypointReader = java.util.Objects.requireNonNull(entrypointReader);
        this.telemetry = telemetry;
    }

    /**
     * 生产默认实例（telemetry=null · 兼容旧调用方）。
     * KAIROS/TEAMMEM/tengu_* GB flag 未接入 Java 前默认关闭
     * （CC feature() 编译期 flag 在 Web 后端为假；GB 读取归主 agent C 系列决策）。
     * <p>IMP-MV2-12（单轨收敛）：tengu_moth_copse 已接线 —— 生产装配点应使用
     * {@link #productionDefault(Telemetry, BooleanSupplier, BooleanSupplier)} 或
     * {@link #productionDefaultWithMothCopse(Telemetry, BooleanSupplier)} 注入
     * FeatureFlags.tenguMothCopse 值；本无参重载保留给不消费 skipIndex 的装配点
     * （如 agent-memory 变体 buildMemoryPrompt，CC agentMemory.ts 亦无 skipIndex 参数）。
     */
    public static MemoryPromptBuilder productionDefault() {
        return productionDefault(null);
    }

    /**
     * 生产默认实例（FIX-MC：带 telemetry 重载 · LlmAgentLoop/ToolRegistrationConfig 生产接线传入，
     * 使 logMemoryDirCounts 双发射真实出 telemetry）。
     *
     * <p>NEW-6（KAIROS 接线）：kairosActive supplier 由 {@link #isKairosDeploymentFlagEnabled()}
     * 提供——源 = {@code nexusai.feature.kairos} 部署标志（env {@code NEXUSAI_FEATURE_KAIROS}，
     * Spring relaxed-binding 同名；与 SubagentTool @Value、SupportedSettingsConfig:127 同源同键）。
     * CC {@code getKairosActive()}（state.ts:1085-1087）为 CLI 运行时状态（assistant/brief 模式
     * setKairosActive(true)）；Web 后端无该进程内状态 → 部署标志即源（NEW-6 登记说明）。
     *
     * @param telemetry telemetry（可 null；null → 不发射）
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry) {
        return productionDefault(telemetry, MemoryPromptBuilder::isKairosDeploymentFlagEnabled);
    }

    /**
     * 生产装配入口（NEW-6）· 显式注入 kairosActive supplier。
     * {@link #productionDefault(Telemetry)} 经 env 部署标志求值后走本重载；
     * 测试/未来装配点可直接注入（CC memdir.ts:432 三重组门控的 Java 等价 supplier 位）。
     * teamMemoryEnabled 取生产默认关（{@code () -> false}）——非 loadMemoryPrompt TEAMMEM
     * 消费方（agent memory 等）保持接线前行为（IMP-MV2-19 零行为变化面）。
     *
     * <p>IMP-MV2-12（单轨收敛）：本重载 mothCopseFlag 恒 false，仅供不消费 skipIndex 的
     * 装配点/既有调用方使用；生产 loadMemoryPrompt 装配点必须走
     * {@link #productionDefault(Telemetry, BooleanSupplier, BooleanSupplier)}。
     *
     * @param telemetry    telemetry（可 null）
     * @param kairosActive CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry, BooleanSupplier kairosActive) {
        return productionDefault(telemetry, kairosActive, () -> false);
    }

    /**
    /**
     * 生产装配入口（IMP-MV2-19 teamMemoryEnabled 接线）· 显式注入 kairosActive + teamMemoryEnabled。
     * {@link #productionDefault(Telemetry, BooleanSupplier)} 以 team 默认关委托本重载；LlmAgentLoop
     * 生产接线传 {@code feature('TEAMMEM') && tengu_herring_clock} 组合（FeatureFlags
     * {@code teamMem()/tenguHerringClock()}，CC memdir.ts:448-449 + teamMemPaths.ts:77）。
     * <b>auto-memory 门在本方法内合成</b>（isTeamMemoryEnabled = isAutoMemoryEnabled() &&
     * tengu_herring_clock，teamMemPaths.ts:73-78）——三闸（feature + auto-memory + tengu_herring_clock）
     * 全开时 TEAMMEM 分支可达（双计数 auto+team），任一闸关 → 回落 auto-only/disabled（零行为变化）。
     *
     * <p>[merge 裁决 wf-f/IMP-MV2-12] 本重载第三参保持 teamMemoryEnabled 语义（wf-c/IMP-MV2-19）；
     * mothCopseFlag 恒 false（不消费 skipIndex 的装配点）。消费 skipIndex 的 loadMemoryPrompt
     * 装配点必须走四参全量重载或 {@link #productionDefaultWithMothCopse}（tengu_moth_copse
     * 单轨收敛：与预取门控/claudemd 过滤/提取 prompt skipIndex 共用 FeatureFlags.tenguMothCopse）。
     *
     * @param telemetry         telemetry（可 null）
     * @param kairosActive      CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     * @param teamMemoryEnabled CC feature('TEAMMEM') && tengu_herring_clock（memdir.ts:448-449 +
     *                          teamMemPaths.ts:77）；auto-memory 门在本方法内合成（不要求调用方重复）
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry, BooleanSupplier kairosActive,
                                                        BooleanSupplier teamMemoryEnabled) {
        return productionDefault(telemetry, kairosActive, teamMemoryEnabled, () -> false);
    }

    /**
     * 生产装配入口（[merge] 四参全量）· 显式注入 kairosActive + teamMemoryEnabled + mothCopseFlag。
     * wf-c/IMP-MV2-19（teamMemoryEnabled 接线）+ wf-f/IMP-MV2-12（tengu_moth_copse 单轨收敛）
     * 两域语义的合并载体：LlmAgentLoop loadMemoryPrompt 生产装配点经本重载同时接线
     * {@code feature('TEAMMEM') && tengu_herring_clock}（FeatureFlags teamMem()/tenguHerringClock()，
     * CC memdir.ts:448-449 + teamMemPaths.ts:77）与 FeatureFlags.tenguMothCopse（CC
     * getFeatureValue_CACHED_MAY_BE_STALE('tengu_moth_copse', false)，memdir.ts:422-425 → skipIndex）。
     * <b>auto-memory 门在本方法内合成</b>（isTeamMemoryEnabled = isAutoMemoryEnabled() &&
     * tengu_herring_clock，teamMemPaths.ts:73-78）——三闸（feature + auto-memory + tengu_herring_clock）
     * 全开时 TEAMMEM 分支可达（双计数 auto+team），任一闸关 → 回落 auto-only/disabled（零行为变化）。
     *
     * @param telemetry         telemetry（可 null）
     * @param kairosActive      CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     * @param teamMemoryEnabled CC feature('TEAMMEM') && tengu_herring_clock（memdir.ts:448-449 +
     *                          teamMemPaths.ts:77）；auto-memory 门在本方法内合成（不要求调用方重复）
     * @param mothCopseFlag     CC tengu_moth_copse GB flag（memdir.ts:422-425）→ skipIndex
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry, BooleanSupplier kairosActive,
                                                        BooleanSupplier teamMemoryEnabled,
                                                        BooleanSupplier mothCopseFlag) {
        // [IMP-C-5 · OPD-CM5-C-09] herringClockFlag 默认关委托 5 参 —— 不消费 tengu_herring_clock 的
        //   装配点（ToolRegistrationConfig/既有测试）保持行为不变；消费 herring_clock 的 LlmAgentLoop
        //   loadMemoryPrompt 装配点走 5 参全量重载接 FeatureFlags.tenguHerringClock()（CC 动态读
        //   getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)，memdir.ts:503）。
        return productionDefault(telemetry, kairosActive, teamMemoryEnabled, mothCopseFlag, () -> false);
    }

    /**
     * 生产装配入口（[IMP-C-5 · OPD-CM5-C-09] 五参全量）· 显式注入 kairosActive + teamMemoryEnabled +
     * mothCopseFlag + herringClockFlag。LlmAgentLoop loadMemoryPrompt 装配点经本重载同时接线
     * {@code FeatureFlags.tenguHerringClock()}（CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock',
     * false)，memdir.ts:503-505 → disabled 分支 tengu_team_memdir_disabled 子事件门控；对应
     * teamMemPaths.ts:77 isTeamMemoryEnabled 运行时闸同源 flag）。
     * <b>auto-memory 门在本方法内合成</b>（isTeamMemoryEnabled = isAutoMemoryEnabled() &&
     * tengu_herring_clock，teamMemPaths.ts:73-78）——与 4 参一致。
     *
     * @param telemetry         telemetry（可 null）
     * @param kairosActive      CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     * @param teamMemoryEnabled CC feature('TEAMMEM') && tengu_herring_clock（memdir.ts:448-449 +
     *                          teamMemPaths.ts:77）；auto-memory 门在本方法内合成（不要求调用方重复）
     * @param mothCopseFlag     CC tengu_moth_copse GB flag（memdir.ts:422-425）→ skipIndex
     * @param herringClockFlag  CC tengu_herring_clock GB flag（memdir.ts:503-505）→ disabled 分支
     *                          tengu_team_memdir_disabled 子事件门控
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry, BooleanSupplier kairosActive,
                                                        BooleanSupplier teamMemoryEnabled,
                                                        BooleanSupplier mothCopseFlag,
                                                        BooleanSupplier herringClockFlag) {
        // [IMP-C-6 · OPD-CM5-C-10] coralFernFlag 默认接生产静态兜底（setProductionCoralFern 注入
        //   FeatureFlags.coralFern()，CC 动态读 getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern',
        //   false)，memdir.ts:376）——消费 coral_fern 的 LlmAgentLoop loadMemoryPrompt 装配点走 6 参
        //   全量重载（显式接线优先）；≤5 参装配点（AgentMemoryDirectory 共享单例 agent-memory 变体）
        //   经静态 holder 读到同一 flag 源（C-6 单例缺口）。测试未注入 → isProductionCoralFernEnabled()
        //   =false（零行为变化）。
        return productionDefault(telemetry, kairosActive, teamMemoryEnabled, mothCopseFlag,
            herringClockFlag, MemoryPromptBuilder::isProductionCoralFernEnabled);
    }

    /**
     * 生产装配入口（[IMP-C-6 · OPD-CM5-C-10] 六参全量）· 显式注入 kairosActive + teamMemoryEnabled +
     * mothCopseFlag + herringClockFlag + coralFernFlag。LlmAgentLoop loadMemoryPrompt 装配点经本重载
     * 同时接线 {@code FeatureFlags.coralFern()}（CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_coral_fern',
     * false)，memdir.ts:376 → buildSearchingPastContextSection 门控：feature 开 → 注入「Searching past
     * context」段，关 → 空数组；transcript 搜索路径用会话 projectRoot，:878 读 autoMemPaths.projectRoot()）。
     * <b>auto-memory 门在本方法内合成</b>（isTeamMemoryEnabled = isAutoMemoryEnabled() &&
     * tengu_herring_clock，teamMemPaths.ts:73-78）——与 5 参一致。
     *
     * @param telemetry         telemetry（可 null）
     * @param kairosActive      CC feature('KAIROS') && getKairosActive()（memdir.ts:432）
     * @param teamMemoryEnabled CC feature('TEAMMEM') && tengu_herring_clock（memdir.ts:448-449 +
     *                          teamMemPaths.ts:77）；auto-memory 门在本方法内合成（不要求调用方重复）
     * @param mothCopseFlag     CC tengu_moth_copse GB flag（memdir.ts:422-425）→ skipIndex
     * @param herringClockFlag  CC tengu_herring_clock GB flag（memdir.ts:503-505）→ disabled 分支
     *                          tengu_team_memdir_disabled 子事件门控
     * @param coralFernFlag     CC tengu_coral_fern GB flag（memdir.ts:376）→ buildSearchingPastContextSection
     *                          「Searching past context」段门控
     */
    public static MemoryPromptBuilder productionDefault(Telemetry telemetry, BooleanSupplier kairosActive,
                                                        BooleanSupplier teamMemoryEnabled,
                                                        BooleanSupplier mothCopseFlag,
                                                        BooleanSupplier herringClockFlag,
                                                        BooleanSupplier coralFernFlag) {
        BooleanSupplier teamGate = () ->
            teamMemoryEnabled.getAsBoolean() && BundledSkillEnabledGates.isAutoMemoryEnabled();
        return new MemoryPromptBuilder(
            AutoMemPaths.defaultInstance(),
            BundledSkillEnabledGates::isAutoMemoryEnabled,
            kairosActive,
            teamGate,
            coralFernFlag,
            mothCopseFlag,
            herringClockFlag,
            () -> System.getenv(COWORK_EXTRA_GUIDELINES_ENV),
            MemoryPromptBuilder::defaultEntrypointReader,
            telemetry);
    }

    /**
     * 生产装配入口（IMP-MV2-12）· 默认 kairos 源（{@link #isKairosDeploymentFlagEnabled()}）
     * + 显式 mothCopseFlag supplier。LlmAgentLoop loadMemoryPrompt 装配点专用
     * （kairos 维持 NEW-6 默认部署标志源，tengu_moth_copse 接线 FeatureFlags.tenguMothCopse）。
     *
     * @param telemetry     telemetry（可 null）
     * @param mothCopseFlag CC tengu_moth_copse GB flag（memdir.ts:422-425）→ skipIndex
     */
    public static MemoryPromptBuilder productionDefaultWithMothCopse(Telemetry telemetry,
                                                                     BooleanSupplier mothCopseFlag) {
        return productionDefault(telemetry, MemoryPromptBuilder::isKairosDeploymentFlagEnabled,
            () -> false, mothCopseFlag);
    }

    /**
     * NEW-6：{@code nexusai.feature.kairos} 部署标志（env {@code NEXUSAI_FEATURE_KAIROS}）→ kairosActive。
     * truthy 集合 = {@code 1/true/yes/on}（TaskSystemConfig.isEnvTruthy，对齐 CC envUtils.ts:32-37）。
     */
    public static boolean isKairosDeploymentFlagEnabled() {
        return com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(
            System.getenv("NEXUSAI_FEATURE_KAIROS"));
    }
    // [A-3/C-9 登记 · IMP-MV2-40] KAIROS 门控源差异（△-2，锚点 memdir.ts:432 ↔ 本方法）：
    //   CC 为 feature('KAIROS')（编译期宏，Web 构建恒 false）+ getKairosActive()（CLI 运行时状态）
    //   三重组门；Java 以部署标志 NEXUSAI_FEATURE_KAIROS 为唯一源 —— env 置真即进入 CC 在
    //   Web 构建不可达的 daily-log 分支。接口适配低风险（默认关两侧一致），登记不修。

    private static String defaultEntrypointReader(String path) {
        try {
            Path p = Path.of(path);
            if (Files.isRegularFile(p)) {
                return Files.readString(p);
            }
        } catch (IOException e) {
            log.debug("[MemoryPromptBuilder] 读取 entrypoint 失败，按空处理: {}", path);
        }
        return "";
    }

    // ════════════════════════════════════════════════════════════════
    // 行为指令段常量 · CC memoryTypes.ts 逐字搬运（snake_case 原名见各 JavaDoc）
    // ════════════════════════════════════════════════════════════════

    /**
     * 文本块 → 行列表。CC 段数组是「每行一个元素」的 string[]；
     * Java 文本块开头换行产生首空元素，此 helper 移除它。
     * 保留尾部空元素（CC TYPES_SECTION_* 数组以 '' 结尾）。
     */
    private static List<String> section(String block) {
        List<String> lines = new ArrayList<>(List.of(block.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        return List.copyOf(lines);
    }

    /** {@link #section} 变体：CC 数组无尾部 '' 时移除结尾空元素。 */
    private static List<String> sectionNoTrailing(String block) {
        List<String> lines = new ArrayList<>(section(block));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return List.copyOf(lines);
    }

    /**
     * {@code ## Types of memory} 段（INDIVIDUAL 单目录变体）· CC original: TYPES_SECTION_INDIVIDUAL
     * （memoryTypes.ts:113-178）。无 {@code <scope>} 标签，示例用 plain {@code [saves X memory: …]}。
     */
    public static final List<String> TYPES_SECTION_INDIVIDUAL = section(
        """
        ## Types of memory

        There are several discrete types of memory that you can store in your memory system:

        <types>
        <type>
            <name>user</name>
            <description>Contain information about the user's role, goals, responsibilities, and knowledge. Great user memories help you tailor your future behavior to the user's preferences and perspective. Your goal in reading and writing these memories is to build up an understanding of who the user is and how you can be most helpful to them specifically. For example, you should collaborate with a senior software engineer differently than a student who is coding for the very first time. Keep in mind, that the aim here is to be helpful to the user. Avoid writing memories about the user that could be viewed as a negative judgement or that are not relevant to the work you're trying to accomplish together.</description>
            <when_to_save>When you learn any details about the user's role, preferences, responsibilities, or knowledge</when_to_save>
            <how_to_use>When your work should be informed by the user's profile or perspective. For example, if the user is asking you to explain a part of the code, you should answer that question in a way that is tailored to the specific details that they will find most valuable or that helps them build their mental model in relation to domain knowledge they already have.</how_to_use>
            <examples>
            user: I'm a data scientist investigating what logging we have in place
            assistant: [saves user memory: user is a data scientist, currently focused on observability/logging]

            user: I've been writing Go for ten years but this is my first time touching the React side of this repo
            assistant: [saves user memory: deep Go expertise, new to React and this project's frontend — frame frontend explanations in terms of backend analogues]
            </examples>
        </type>
        <type>
            <name>feedback</name>
            <description>Guidance the user has given you about how to approach work — both what to avoid and what to keep doing. These are a very important type of memory to read and write as they allow you to remain coherent and responsive to the way you should approach work in the project. Record from failure AND success: if you only save corrections, you will avoid past mistakes but drift away from approaches the user has already validated, and may grow overly cautious.</description>
            <when_to_save>Any time the user corrects your approach ("no not that", "don't", "stop doing X") OR confirms a non-obvious approach worked ("yes exactly", "perfect, keep doing that", accepting an unusual choice without pushback). Corrections are easy to notice; confirmations are quieter — watch for them. In both cases, save what is applicable to future conversations, especially if surprising or not obvious from the code. Include *why* so you can judge edge cases later.</when_to_save>
            <how_to_use>Let these memories guide your behavior so that the user does not need to offer the same guidance twice.</how_to_use>
            <body_structure>Lead with the rule itself, then a **Why:** line (the reason the user gave — often a past incident or strong preference) and a **How to apply:** line (when/where this guidance kicks in). Knowing *why* lets you judge edge cases instead of blindly following the rule.</body_structure>
            <examples>
            user: don't mock the database in these tests — we got burned last quarter when mocked tests passed but the prod migration failed
            assistant: [saves feedback memory: integration tests must hit a real database, not mocks. Reason: prior incident where mock/prod divergence masked a broken migration]

            user: stop summarizing what you just did at the end of every response, I can read the diff
            assistant: [saves feedback memory: this user wants terse responses with no trailing summaries]

            user: yeah the single bundled PR was the right call here, splitting this one would've just been churn
            assistant: [saves feedback memory: for refactors in this area, user prefers one bundled PR over many small ones. Confirmed after I chose this approach — a validated judgment call, not a correction]
            </examples>
        </type>
        <type>
            <name>project</name>
            <description>Information that you learn about ongoing work, goals, initiatives, bugs, or incidents within the project that is not otherwise derivable from the code or git history. Project memories help you understand the broader context and motivation behind the work the user is doing within this working directory.</description>
            <when_to_save>When you learn who is doing what, why, or by when. These states change relatively quickly so try to keep your understanding of this up to date. Always convert relative dates in user messages to absolute dates when saving (e.g., "Thursday" → "2026-03-05"), so the memory remains interpretable after time passes.</when_to_save>
            <how_to_use>Use these memories to more fully understand the details and nuance behind the user's request and make better informed suggestions.</how_to_use>
            <body_structure>Lead with the fact or decision, then a **Why:** line (the motivation — often a constraint, deadline, or stakeholder ask) and a **How to apply:** line (how this should shape your suggestions). Project memories decay fast, so the why helps future-you judge whether the memory is still load-bearing.</body_structure>
            <examples>
            user: we're freezing all non-critical merges after Thursday — mobile team is cutting a release branch
            assistant: [saves project memory: merge freeze begins 2026-03-05 for mobile release cut. Flag any non-critical PR work scheduled after that date]

            user: the reason we're ripping out the old auth middleware is that legal flagged it for storing session tokens in a way that doesn't meet the new compliance requirements
            assistant: [saves project memory: auth middleware rewrite is driven by legal/compliance requirements around session token storage, not tech-debt cleanup — scope decisions should favor compliance over ergonomics]
            </examples>
        </type>
        <type>
            <name>reference</name>
            <description>Stores pointers to where information can be found in external systems. These memories allow you to remember where to look to find up-to-date information outside of the project directory.</description>
            <when_to_save>When you learn about resources in external systems and their purpose. For example, that bugs are tracked in a specific project in Linear or that feedback can be found in a specific Slack channel.</when_to_save>
            <how_to_use>When the user references an external system or information that may be in an external system.</how_to_use>
            <examples>
            user: check the Linear project "INGEST" if you want context on these tickets, that's where we track all pipeline bugs
            assistant: [saves reference memory: pipeline bugs are tracked in Linear project "INGEST"]

            user: the Grafana board at grafana.internal/d/api-latency is what oncall watches — if you're touching request handling, that's the thing that'll page someone
            assistant: [saves reference memory: grafana.internal/d/api-latency is the oncall latency dashboard — check it when editing request-path code]
            </examples>
        </type>
        </types>
        """);

    /**
     * {@code ## Types of memory} 段（COMBINED 双目录变体：private + team）· CC original: TYPES_SECTION_COMBINED
     * （memoryTypes.ts:37-106）。含 {@code <scope>} 标签与 team/private 限定示例。
     */
    public static final List<String> TYPES_SECTION_COMBINED = section(
        """
        ## Types of memory

        There are several discrete types of memory that you can store in your memory system. Each type below declares a <scope> of `private`, `team`, or guidance for choosing between the two.

        <types>
        <type>
            <name>user</name>
            <scope>always private</scope>
            <description>Contain information about the user's role, goals, responsibilities, and knowledge. Great user memories help you tailor your future behavior to the user's preferences and perspective. Your goal in reading and writing these memories is to build up an understanding of who the user is and how you can be most helpful to them specifically. For example, you should collaborate with a senior software engineer differently than a student who is coding for the very first time. Keep in mind, that the aim here is to be helpful to the user. Avoid writing memories about the user that could be viewed as a negative judgement or that are not relevant to the work you're trying to accomplish together.</description>
            <when_to_save>When you learn any details about the user's role, preferences, responsibilities, or knowledge</when_to_save>
            <how_to_use>When your work should be informed by the user's profile or perspective. For example, if the user is asking you to explain a part of the code, you should answer that question in a way that is tailored to the specific details that they will find most valuable or that helps them build their mental model in relation to domain knowledge they already have.</how_to_use>
            <examples>
            user: I'm a data scientist investigating what logging we have in place
            assistant: [saves private user memory: user is a data scientist, currently focused on observability/logging]

            user: I've been writing Go for ten years but this is my first time touching the React side of this repo
            assistant: [saves private user memory: deep Go expertise, new to React and this project's frontend — frame frontend explanations in terms of backend analogues]
            </examples>
        </type>
        <type>
            <name>feedback</name>
            <scope>default to private. Save as team only when the guidance is clearly a project-wide convention that every contributor should follow (e.g., a testing policy, a build invariant), not a personal style preference.</scope>
            <description>Guidance the user has given you about how to approach work — both what to avoid and what to keep doing. These are a very important type of memory to read and write as they allow you to remain coherent and responsive to the way you should approach work in the project. Record from failure AND success: if you only save corrections, you will avoid past mistakes but drift away from approaches the user has already validated, and may grow overly cautious. Before saving a private feedback memory, check that it doesn't contradict a team feedback memory — if it does, either don't save it or note the override explicitly.</description>
            <when_to_save>Any time the user corrects your approach ("no not that", "don't", "stop doing X") OR confirms a non-obvious approach worked ("yes exactly", "perfect, keep doing that", accepting an unusual choice without pushback). Corrections are easy to notice; confirmations are quieter — watch for them. In both cases, save what is applicable to future conversations, especially if surprising or not obvious from the code. Include *why* so you can judge edge cases later.</when_to_save>
            <how_to_use>Let these memories guide your behavior so that the user and other users in the project do not need to offer the same guidance twice.</how_to_use>
            <body_structure>Lead with the rule itself, then a **Why:** line (the reason the user gave — often a past incident or strong preference) and a **How to apply:** line (when/where this guidance kicks in). Knowing *why* lets you judge edge cases instead of blindly following the rule.</body_structure>
            <examples>
            user: don't mock the database in these tests — we got burned last quarter when mocked tests passed but the prod migration failed
            assistant: [saves team feedback memory: integration tests must hit a real database, not mocks. Reason: prior incident where mock/prod divergence masked a broken migration. Team scope: this is a project testing policy, not a personal preference]

            user: stop summarizing what you just did at the end of every response, I can read the diff
            assistant: [saves private feedback memory: this user wants terse responses with no trailing summaries. Private because it's a communication preference, not a project convention]

            user: yeah the single bundled PR was the right call here, splitting this one would've just been churn
            assistant: [saves private feedback memory: for refactors in this area, user prefers one bundled PR over many small ones. Confirmed after I chose this approach — a validated judgment call, not a correction]
            </examples>
        </type>
        <type>
            <name>project</name>
            <scope>private or team, but strongly bias toward team</scope>
            <description>Information that you learn about ongoing work, goals, initiatives, bugs, or incidents within the project that is not otherwise derivable from the code or git history. Project memories help you understand the broader context and motivation behind the work users are working on within this working directory.</description>
            <when_to_save>When you learn who is doing what, why, or by when. These states change relatively quickly so try to keep your understanding of this up to date. Always convert relative dates in user messages to absolute dates when saving (e.g., "Thursday" → "2026-03-05"), so the memory remains interpretable after time passes.</when_to_save>
            <how_to_use>Use these memories to more fully understand the details and nuance behind the user's request, anticipate coordination issues across users, make better informed suggestions.</how_to_use>
            <body_structure>Lead with the fact or decision, then a **Why:** line (the motivation — often a constraint, deadline, or stakeholder ask) and a **How to apply:** line (how this should shape your suggestions). Project memories decay fast, so the why helps future-you judge whether the memory is still load-bearing.</body_structure>
            <examples>
            user: we're freezing all non-critical merges after Thursday — mobile team is cutting a release branch
            assistant: [saves team project memory: merge freeze begins 2026-03-05 for mobile release cut. Flag any non-critical PR work scheduled after that date]

            user: the reason we're ripping out the old auth middleware is that legal flagged it for storing session tokens in a way that doesn't meet the new compliance requirements
            assistant: [saves team project memory: auth middleware rewrite is driven by legal/compliance requirements around session token storage, not tech-debt cleanup — scope decisions should favor compliance over ergonomics]
            </examples>
        </type>
        <type>
            <name>reference</name>
            <scope>usually team</scope>
            <description>Stores pointers to where information can be found in external systems. These memories allow you to remember where to look to find up-to-date information outside of the project directory.</description>
            <when_to_save>When you learn about resources in external systems and their purpose. For example, that bugs are tracked in a specific project in Linear or that feedback can be found in a specific Slack channel.</when_to_save>
            <how_to_use>When the user references an external system or information that may be in an external system.</how_to_use>
            <examples>
            user: check the Linear project "INGEST" if you want context on these tickets, that's where we track all pipeline bugs
            assistant: [saves team reference memory: pipeline bugs are tracked in Linear project "INGEST"]

            user: the Grafana board at grafana.internal/d/api-latency is what oncall watches — if you're touching request handling, that's the thing that'll page someone
            assistant: [saves team reference memory: grafana.internal/d/api-latency is the oncall latency dashboard — check it when editing request-path code]
            </examples>
        </type>
        </types>
        """);

    /**
     * {@code ## What NOT to save in memory} 段 · CC original: WHAT_NOT_TO_SAVE_SECTION
     * （memoryTypes.ts:183-195）。两种模式共用。H2 显式保存门（Eval-validated）：即使显式要求保存
     * PR 列表/activity 摘要，也要追问「什么令人意外」。
     */
    public static final List<String> WHAT_NOT_TO_SAVE_SECTION = sectionNoTrailing(
        """
        ## What NOT to save in memory

        - Code patterns, conventions, architecture, file paths, or project structure — these can be derived by reading the current project state.
        - Git history, recent changes, or who-changed-what — `git log` / `git blame` are authoritative.
        - Debugging solutions or fix recipes — the fix is in the code; the commit message has the context.
        - Anything already documented in CLAUDE.md files.
        - Ephemeral task details: in-progress work, temporary state, current conversation context.

        These exclusions apply even when the user explicitly asks you to save. If they ask you to save a PR list or activity summary, ask what was *surprising* or *non-obvious* about it — that is the part worth keeping.
        """);

    /**
     * recall 侧 drift caveat 单条 bullet · CC original: {@code MEMORY_DRIFT_CAVEAT}（memoryTypes.ts:201-202）。
     */
    public static final String MEMORY_DRIFT_CAVEAT =
        "- Memory records can become stale over time. Use memory as context for what was true at a given point in time. Before answering the user or building assumptions based solely on information in memory records, verify that the memory is still correct and up-to-date by reading the current state of the files or resources. If a recalled memory conflicts with current information, trust what you observe now — and update or remove the stale memory rather than acting on it.";

    /**
     * {@code ## When to access memories} 段 · CC original: WHEN_TO_ACCESS_SECTION（memoryTypes.ts:216-222）。
     * H6 branch-pollution 反模式 bullet（"ignore" ≠ "acknowledge then override"）。
     */
    public static final List<String> WHEN_TO_ACCESS_SECTION = sectionNoTrailing(
        """
        ## When to access memories
        - When memories seem relevant, or the user references prior-conversation work.
        - You MUST access memory when the user explicitly asks you to check, recall, or remember.
        - If the user says to *ignore* or *not use* memory: proceed as if MEMORY.md were empty. Do not apply remembered facts, cite, compare against, or mention memory content.
        """ + MEMORY_DRIFT_CAVEAT);

    /**
     * {@code ## Before recommending from memory} 段 · CC original: TRUSTING_RECALL_SECTION
     * （memoryTypes.ts:240-256）。Header 用「Before recommending」（决策点 action cue，Eval 3/3），
     * 非抽象「Trusting what you recall」。
     */
    public static final List<String> TRUSTING_RECALL_SECTION = sectionNoTrailing(
        """
        ## Before recommending from memory

        A memory that names a specific function, file, or flag is a claim that it existed *when the memory was written*. It may have been renamed, removed, or never merged. Before recommending it:

        - If the memory names a file path: check the file exists.
        - If the memory names a function or flag: grep for it.
        - If the user is about to act on your recommendation (not just asking about history), verify first.

        "The memory says X exists" is not the same as "X exists now."

        A memory that summarizes repo state (activity logs, architecture snapshots) is frozen in time. If the user asks about *recent* or *current* state, prefer `git log` or reading the code over recalling the snapshot.
        """);

    /**
     * frontmatter 格式示例（含 type 字段）· CC original: MEMORY_FRONTMATTER_EXAMPLE
     * （memoryTypes.ts:261-271）。
     */
    public static final List<String> MEMORY_FRONTMATTER_EXAMPLE = sectionNoTrailing(
        """
        ```markdown
        ---
        name: {{memory name}}
        description: {{one-line description — used to decide relevance in future conversations, so be specific}}
        type: {{user, feedback, project, reference}}
        ---

        {{memory content — for feedback/project types, structure as: rule/fact, then **Why:** and **How to apply:** lines}}
        ```
        """);

    // ════════════════════════════════════════════════════════════════
    // 截断 / 目录保证 / 目录计数 · CC memdir.ts:57-185
    // ════════════════════════════════════════════════════════════════

    /** CC original: EntrypointTruncation（memdir.ts:41-47） */
    public record EntrypointTruncation(
        String content,
        int lineCount,
        int byteCount,
        boolean wasLineTruncated,
        boolean wasByteTruncated) {}

    /**
     * 字节数 → 人类可读串 · CC original: {@code formatFileSize}（utils/format.ts:9-24）。
     * 1.5KB/24.4KB/MB/GB 形态，<1KB 原样 bytes。仅用于 truncate WARNING reason 文本对齐。
     */
    private static String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            return stripTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", kb)) + "KB";
        }
        double mb = kb / 1024;
        if (mb < 1024) {
            return stripTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", mb)) + "MB";
        }
        double gb = mb / 1024;
        return stripTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", gb)) + "GB";
    }

    /** CC {@code .toFixed(1).replace(/\.0$/, '')}（format.ts:13/16/19/22）等价。 */
    private static String stripTrailingZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * 将 MEMORY.md 内容截断到行数 + 字节双上限，追加命名触发上限的 WARNING。
     * 先按行截断（自然边界），再在字节上限前的最后一个换行处截断避免切断行。
     * CC original: {@code truncateEntrypointContent}（memdir.ts:57-103）。
     */
    public EntrypointTruncation truncateEntrypointContent(String raw) {
        String trimmed = raw.trim();
        String[] contentLines = trimmed.split("\n", -1);
        int lineCount = contentLines.length;
        int byteCount = trimmed.length();

        boolean wasLineTruncated = lineCount > MAX_ENTRYPOINT_LINES;
        boolean wasByteTruncated = byteCount > MAX_ENTRYPOINT_BYTES;

        if (!wasLineTruncated && !wasByteTruncated) {
            return new EntrypointTruncation(trimmed, lineCount, byteCount, false, false);
        }

        String truncated = wasLineTruncated
            ? String.join("\n", java.util.Arrays.copyOf(contentLines, MAX_ENTRYPOINT_LINES))
            : trimmed;

        if (truncated.length() > MAX_ENTRYPOINT_BYTES) {
            int cutAt = truncated.lastIndexOf('\n', MAX_ENTRYPOINT_BYTES);
            truncated = truncated.substring(0, cutAt > 0 ? cutAt : MAX_ENTRYPOINT_BYTES);
        }

        String reason;
        if (wasByteTruncated && !wasLineTruncated) {
            reason = formatFileSize(byteCount) + " (limit: " + formatFileSize(MAX_ENTRYPOINT_BYTES)
                + ") — index entries are too long";
        } else if (wasLineTruncated && !wasByteTruncated) {
            reason = lineCount + " lines (limit: " + MAX_ENTRYPOINT_LINES + ")";
        } else {
            reason = lineCount + " lines and " + formatFileSize(byteCount);
        }

        return new EntrypointTruncation(
            truncated + "\n\n> WARNING: " + ENTRYPOINT_NAME + " is " + reason
                + ". Only part of it was loaded. Keep index entries to one line under ~200 chars; move detail into topic files.",
            lineCount, byteCount, wasLineTruncated, wasByteTruncated);
    }

    /**
     * 确保 memory 目录存在（幂等）。CC original: {@code ensureMemoryDirExists}（memdir.ts:129-147）。
     * CC FsOperations.mkdir 递归 + 吞 EEXIST；Java 侧 Files.createDirectories 等价，失败仅告警
     * 不阻断 prompt 构建（模型 Write 会暴露真实权限错误）。
     *
     * @param memoryDir 目标目录
     */
    public void ensureMemoryDirExists(String memoryDir) {
        try {
            Files.createDirectories(Paths.get(memoryDir));
        } catch (IOException e) {
            log.warn("[MemoryPromptBuilder] ensureMemoryDirExists 失败(目录可能不可写): {} {}", memoryDir, e.getMessage());
        }
    }

    /**
     * CC memdir.ts:153-185 logMemoryDirCounts：共享静态守护线程池（fire-and-forget，不阻塞 prompt 构建）。
     * 线程隔离保证测试/生产互不阻塞，异步任务失败不抛回调用方。
     */
    private static final ExecutorService MEMORY_DIR_COUNTS_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "memory-dir-counts");
        t.setDaemon(true);
        return t;
    });

    /**
     * 异步记录 memory 目录文件/子目录计数（fire-and-forget，不阻塞 prompt 构建）。
     * CC original: {@code logMemoryDirCounts}（memdir.ts:153-185，tengu_memdir_loaded telemetry）。
     *
     * <p>对齐 CC 行为：
     * <ol>
     *   <li><b>异步</b>：{@code CompletableFuture.runAsync} 共享线程池 fire-and-forget（CC :162-184
     *       {@code void fs.readdir(...).then(...)}），不阻塞 prompt 构建</li>
     *   <li><b>双发射</b>：recordEvent（1P/Statsig 适配层计数）+ logOTelEvent（OTel 真实出事件，
     *       HookRegistry:559-560/:584-585/:645-646/:679-680 成对惯例），事件名 {@code tengu_memdir_loaded}（CC :174）</li>
     *   <li><b>目录不可读 → 无计数发射</b>（CC :180-183 error 分支 logEvent(baseMetadata)，
     *       仅 debug 日志 + 无计数发射）</li>
     * </ol>
     *
     * <p>telemetry 为 null（测试/未接线）→ 跳过双发射，仅保留 debug 日志（零行为变化）。
     *
     * @param memoryDir    目录
     * @param baseMetadata CC baseMetadata（buildMemoryPrompt：content_length/line_count/
     *                     was_truncated/was_byte_truncated/memory_type；loadMemoryPrompt：
     *                     memory_type=auto/team）
     */
    public void logMemoryDirCounts(String memoryDir, Map<String, Object> baseMetadata) {
        Map<String, Object> safeBase = baseMetadata != null ? baseMetadata : Map.of();
        CompletableFuture.runAsync(() -> {
            try (java.util.stream.Stream<Path> stream = Files.list(Paths.get(memoryDir))) {
                int fileCount = 0;
                int subdirCount = 0;
                for (Path p : stream.toList()) {
                    // CC :168-173 dirent.isFile()/isDirectory() —— Dirent 不 follow symlink
                    //   （Node Dirent：symlink 是 isSymbolicLink() 而非 isFile/isDirectory）。
                    //   Java Files.isRegularFile/isDirectory 默认跟链 → symlink→file 被误计为 file、
                    //   symlink→dir 被误计为 dir；NOFOLLOW_LINKS 对齐 Dirent 语义（D08-3）。
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        fileCount++;
                    } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        subdirCount++;
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[MemoryPromptBuilder] tengu_memdir_loaded dir={} 文件数={} 子目录数={}",
                        memoryDir, fileCount, subdirCount);
                }
                Map<String, Object> attrs = new LinkedHashMap<>(safeBase);
                attrs.put("total_file_count", fileCount);
                attrs.put("total_subdir_count", subdirCount);
                emitMemdirLoaded(attrs);
            } catch (Exception e) {
                // CC memdir.ts:180-183 目录不可读 → 无计数发射
                if (log.isDebugEnabled()) {
                    log.debug("[MemoryPromptBuilder] tengu_memdir_loaded 目录不可读（无计数发射）: {}", memoryDir);
                }
                emitMemdirLoaded(safeBase);
            }
        }, MEMORY_DIR_COUNTS_POOL);
    }

    /**
     * 双发射 tengu_memdir_loaded（recordEvent + logOTelEvent）· CC original: {@code logEvent}
     * （memdir.ts:174-178）。null-safe：实例 telemetry 为 null 时回落
     * {@link #setProductionTelemetry(Telemetry)} 注入的生产静态兜底（[IMP-C-4 · OPD-CM5-C-08]
     * 子代理 agent-memory 路径计数事件透传）—— 实例与静态均 null（测试/未接线）→ 跳过。
     */
    private void emitMemdirLoaded(Map<String, Object> attrs) {
        // [IMP-C-4 · OPD-CM5-C-08] 实例 telemetry 优先；生产静态兜底回落（agent-memory 单例装配）
        Telemetry t = this.telemetry != null ? this.telemetry : productionTelemetry;
        if (t == null) {
            return;
        }
        t.recordEvent("tengu_memdir_loaded", attrs);
        t.logOTelEvent("tengu_memdir_loaded", attrs);
    }

    // ════════════════════════════════════════════════════════════════
    // 行为指令构建 · CC memdir.ts:196-316
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建行为指令（不含 MEMORY.md 内容）· CC original: {@code buildMemoryLines}（memdir.ts:199-266）。
     * 四类 taxonomy 约束 + 两步保存（或 skipIndex 一步）+ recall/access 护栏。
     *
     * @param displayName    提示标题（auto memory / agent memory）
     * @param memoryDir      目录路径
     * @param extraGuidelines CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES 注入段（可为 null）
     * @param skipIndex      CC tengu_moth_copse flag：true 时省略 MEMORY.md 索引两步保存
     * @return 逐行指令文本
     */
    public List<String> buildMemoryLines(String displayName, String memoryDir,
                                         List<String> extraGuidelines, boolean skipIndex) {
        List<String> howToSave;
        if (skipIndex) {
            howToSave = new ArrayList<>(List.of(
                "## How to save memories",
                "",
                "Write each memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:",
                ""));
            howToSave.addAll(MEMORY_FRONTMATTER_EXAMPLE);
            howToSave.addAll(List.of(
                "",
                "- Keep the name, description, and type fields in memory files up-to-date with the content",
                "- Organize memory semantically by topic, not chronologically",
                "- Update or remove memories that turn out to be wrong or outdated",
                "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one."));
        } else {
            howToSave = new ArrayList<>(List.of(
                "## How to save memories",
                "",
                "Saving a memory is a two-step process:",
                "",
                "**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:",
                ""));
            howToSave.addAll(MEMORY_FRONTMATTER_EXAMPLE);
            howToSave.addAll(List.of(
                "",
                "**Step 2** — add a pointer to that file in `" + ENTRYPOINT_NAME + "`. `" + ENTRYPOINT_NAME
                    + "` is an index, not a memory — each entry should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. It has no frontmatter. Never write memory content directly into `"
                    + ENTRYPOINT_NAME + "`.",
                "",
                "- `" + ENTRYPOINT_NAME + "` is always loaded into your conversation context — lines after "
                    + MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise",
                "- Keep the name, description, and type fields in memory files up-to-date with the content",
                "- Organize memory semantically by topic, not chronologically",
                "- Update or remove memories that turn out to be wrong or outdated",
                "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one."));
        }

        List<String> lines = new ArrayList<>(List.of(
            "# " + displayName,
            "",
            "You have a persistent, file-based memory system at `" + memoryDir + "`. " + DIR_EXISTS_GUIDANCE,
            "",
            "You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.",
            "",
            "If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.",
            ""));
        lines.addAll(TYPES_SECTION_INDIVIDUAL);
        lines.addAll(WHAT_NOT_TO_SAVE_SECTION);
        lines.add("");
        lines.addAll(howToSave);
        lines.add("");
        lines.addAll(WHEN_TO_ACCESS_SECTION);
        lines.add("");
        lines.addAll(TRUSTING_RECALL_SECTION);
        lines.add("");
        lines.addAll(List.of(
            "## Memory and other forms of persistence",
            "Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.",
            "- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.",
            "- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.",
            ""));
        if (extraGuidelines != null) {
            lines.addAll(extraGuidelines);
        }
        // CC memdir.ts:259 `...(extraGuidelines ?? []), ''` —— 无论有无 extras 恒有尾部空行
        lines.add("");
        lines.addAll(buildSearchingPastContextSection(memoryDir));
        return lines;
    }

    /**
     * 构建行为指令 + MEMORY.md 内容（agent-memory 变体）· CC original: {@code buildMemoryPrompt}
     * （memdir.ts:272-316）。读 entrypoint（MEMORY.md），有内容则截断后追加 {@code ## MEMORY.md}，
     * 空则提示 currently empty。目录创建是调用方职责（loadMemoryPrompt / loadAgentMemoryPrompt），
     * 本方法只读不 mkdir。
     *
     * @param displayName    提示标题
     * @param memoryDir      目录路径
     * @param extraGuidelines 注入段（可为 null）
     * @return 完整 prompt 文本
     */
    public String buildMemoryPrompt(String displayName, String memoryDir, List<String> extraGuidelines) {
        String entrypointContent;
        try {
            entrypointContent = entrypointReader.apply(memoryDir + java.io.File.separator + ENTRYPOINT_NAME);
        } catch (RuntimeException e) {
            log.warn("[MemoryPromptBuilder] buildMemoryPrompt 读取 entrypoint 异常，按空处理: {} {}", memoryDir, e.getMessage());
            entrypointContent = "";
        }
        List<String> lines = new ArrayList<>(buildMemoryLines(displayName, memoryDir, extraGuidelines, false));

        if (entrypointContent != null && !entrypointContent.trim().isEmpty()) {
            EntrypointTruncation t = truncateEntrypointContent(entrypointContent);
            String memoryType = AUTO_MEM_DISPLAY_NAME.equals(displayName) ? "auto" : "agent";
            // CC memdir.ts:298-305 baseMetadata：content_length/line_count/was_truncated/
            // was_byte_truncated/memory_type（+ 内部追加 total_file_count/total_subdir_count）
            logMemoryDirCounts(memoryDir, java.util.Map.of(
                "content_length", t.byteCount(),
                "line_count", t.lineCount(),
                "was_truncated", t.wasLineTruncated(),
                "was_byte_truncated", t.wasByteTruncated(),
                "memory_type", memoryType));
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add(t.content());
        } else {
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add("Your " + ENTRYPOINT_NAME
                + " is currently empty. When you save new memories, they will appear here.");
        }
        return String.join("\n", lines);
    }

    // ════════════════════════════════════════════════════════════════
    // searching past context · CC memdir.ts:375-407（tengu_coral_fern 门控）
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建「Searching past context」段 · CC original: {@code buildSearchingPastContextSection}
     * （memdir.ts:375-407）。tengu_coral_fern GB flag 关闭时返回空列表。
     * Java 无 embedded search tools / REPL 模式 → 恒用 Grep 工具形态（GREP_TOOL_NAME="Grep"）。
     *
     * <p><b>[S2 迁移]</b>：transcript 搜索根从 boundProject（autoMemPaths.projectRoot()）改
     * config-home projects 项目 slug 目录（对齐 CC memdir.ts:379-391 用 config home 的
     * {@code getProjectDir(getOriginalCwd())}）——S2 后 transcript 落在 config-home，搜索提示
     * 必须指向 config-home 根，否则模型 grep 空目录提示失效。
     *
     * @param autoMemDir auto memory 目录
     * @return 段行列表（gate 关闭时空）
     */
    public List<String> buildSearchingPastContextSection(String autoMemDir) {
        if (!coralFernFlag.getAsBoolean()) {
            return List.of();
        }
        // ODF-A1: 会话 projectRoot（CC getOriginalCwd 等价）· 绝不读 JVM 进程工作目录
        String projectRoot = autoMemPaths != null ? autoMemPaths.projectRoot() : ".";
        if (projectRoot == null || projectRoot.isBlank()) {
            projectRoot = ".";
        }
        // S2：config-home 项目 slug 目录（CC getProjectDir(getOriginalCwd()) 等价，
        //   sessionStoragePortable.ts:323-329 getProjectDir = join(getProjectsDir(), sanitizePath(cwd))）
        java.nio.file.Path transcriptRoot =
            com.nexusai.application.agent.tool.SessionStorage.getProjectDir(java.nio.file.Path.of(projectRoot));
        // [A-4/C-3 登记 · IMP-MV2-40] embedded 恒 false（△-3，锚点 memdir.ts:384-389：
        //   hasEmbeddedSearchTools() || isReplModeEnabled()）—— Java 无 embedded tools / REPL
        //   平台 → 恒输出 Grep 工具形态 [N/A-保留]。
        boolean embedded = false;
        String memSearch = embedded
            ? "grep -rn \"<search term>\" " + autoMemDir + " --include=\"*.md\""
            : GREP_TOOL_NAME + " with pattern=\"<search term>\" path=\"" + autoMemDir + "\" glob=\"*.md\"";
        String transcriptSearch = embedded
            ? "grep -rn \"<search term>\" " + transcriptRoot + "/ --include=\"*.jsonl\""
            : GREP_TOOL_NAME + " with pattern=\"<search term>\" path=\"" + transcriptRoot + "/\" glob=\"*.jsonl\"";
        return List.of(
            "## Searching past context",
            "",
            "When looking for past context:",
            "1. Search topic files in your memory directory:",
            "```",
            memSearch,
            "```",
            "2. Session transcript logs (last resort — large files, slow):",
            "```",
            transcriptSearch,
            "```",
            "Use narrow search terms (error messages, file paths, function names) rather than broad keywords.",
            "");
    }

    // ════════════════════════════════════════════════════════════════
    // KAIROS daily-log · CC memdir.ts:327-370（feature('KAIROS') && getKairosActive()）
    // ════════════════════════════════════════════════════════════════

    /**
     * Assistant 模式 daily-log prompt · CC original: {@code buildAssistantDailyLogPrompt}
     * （memdir.ts:327-370）。KAIROS 门控由 {@link #loadMemoryPrompt()} 负责；本方法只构建文本。
     * 路径描述用「模式」而非今日字面路径（prompt 被 systemPromptSection 缓存，日期变更不失效，
     * 模型从 currentDate attachment 推导当日日期）。
     *
     * @param skipIndex CC tengu_moth_copse flag：true 时省略 MEMORY.md 蒸馏索引段
     * @return daily-log prompt 文本
     */
    public String buildAssistantDailyLogPrompt(boolean skipIndex) {
        String memoryDir = autoMemPaths.getAutoMemPath();
        String logPathPattern = Paths.get(memoryDir, "logs", "YYYY", "MM", "YYYY-MM-DD.md").toString();

        List<String> lines = new ArrayList<>(List.of(
            "# auto memory",
            "",
            "You have a persistent, file-based memory system found at: `" + memoryDir + "`",
            "",
            "This session is long-lived. As you work, record anything worth remembering by **appending** to today's daily log file:",
            "",
            "`" + logPathPattern + "`",
            "",
            "Substitute today's date (from `currentDate` in your context) for `YYYY-MM-DD`. When the date rolls over mid-session, start appending to the new day's file.",
            "",
            "Write each entry as a short timestamped bullet. Create the file (and parent directories) on first write if it does not exist. Do not rewrite or reorganize the log — it is append-only. A separate nightly process distills these logs into `MEMORY.md` and topic files.",
            "",
            "## What to log",
            "- User corrections and preferences (\"use bun, not npm\"; \"stop summarizing diffs\")",
            "- Facts about the user, their role, or their goals",
            "- Project context that is not derivable from the code (deadlines, incidents, decisions and their rationale)",
            "- Pointers to external systems (dashboards, Linear projects, Slack channels)",
            "- Anything the user explicitly asks you to remember",
            ""));
        lines.addAll(WHAT_NOT_TO_SAVE_SECTION);
        lines.add("");
        if (!skipIndex) {
            lines.addAll(List.of(
                "## " + ENTRYPOINT_NAME,
                "`" + ENTRYPOINT_NAME
                    + "` is the distilled index (maintained nightly from your logs) and is loaded into your context automatically. Read it for orientation, but do not edit it directly — record new information in today's log instead.",
                ""));
        }
        lines.addAll(buildSearchingPastContextSection(memoryDir));
        return String.join("\n", lines);
    }

    // ════════════════════════════════════════════════════════════════
    // 三路分发 · CC memdir.ts:419-507
    // ════════════════════════════════════════════════════════════════

    /**
     * 加载统一 memory prompt · CC original: {@code loadMemoryPrompt}（memdir.ts:419-507）。
     * <p>分发顺序（与 CC 完全一致）：
     * <ol>
     *   <li>KAIROS && autoEnabled && getKairosActive() → daily-log prompt（append-only，优先于 TEAMMEM）</li>
     *   <li>TEAMMEM && isTeamMemoryEnabled() → 合并 prompt（auto + team 双目录）</li>
     *   <li>autoEnabled → buildMemoryLines 行为指令（单目录）</li>
     *   <li>否则 → null（tengu_memdir_disabled）</li>
     * </ol>
     * Team memory 依赖 auto memory（isTeamMemoryEnabled 先查 isAutoMemoryEnabled），故无 team-only 分支。
     *
     * @return 行为指令 prompt 文本；auto memory 禁用时返回 {@code null}（INV-3）
     */
    public String loadMemoryPrompt() {
        boolean autoEnabled = autoMemoryEnabled.getAsBoolean();
        boolean skipIndex = mothCopseFlag.getAsBoolean();

        // 1. KAIROS daily-log（takes precedence over TEAMMEM）
        if (kairosActive.getAsBoolean() && autoEnabled) {
            // CC memdir.ts:433-436 logMemoryDirCounts(getAutoMemPath(), {memory_type: 'auto'})
            logMemoryDirCounts(autoMemPaths.getAutoMemPath(), java.util.Map.of("memory_type", "auto"));
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPromptBuilder] loadMemoryPrompt 走 KAIROS daily-log 分支");
            }
            return buildAssistantDailyLogPrompt(skipIndex);
        }

        // Cowork 注入 memory 策略文本 env var（memdir.ts:441-446）
        List<String> extraGuidelines = null;
        String cowork = coworkExtraGuidelinesEnv.get();
        if (cowork != null && !cowork.trim().isEmpty()) {
            extraGuidelines = List.of(cowork);
        }

        // 2. TEAMMEM 合并分支
        if (teamMemoryEnabled.getAsBoolean()) {
            String autoDir = autoMemPaths.getAutoMemPath();
            String teamDir = Paths.get(autoDir, "team").toString();
            ensureMemoryDirExists(teamDir);
            // CC memdir.ts:460-467：autoDir → memory_type=auto，teamDir → memory_type=team
            logMemoryDirCounts(autoDir, java.util.Map.of("memory_type", "auto"));
            logMemoryDirCounts(teamDir, java.util.Map.of("memory_type", "team"));
            String searchingPast = String.join("\n", buildSearchingPastContextSection(autoDir));
            return CombinedMemoryPrompt.buildCombinedMemoryPrompt(
                autoDir, teamDir, extraGuidelines, skipIndex, searchingPast);
        }

        // 3. auto-only 分支
        if (autoEnabled) {
            String autoDir = autoMemPaths.getAutoMemPath();
            ensureMemoryDirExists(autoDir);
            // CC memdir.ts:480-483 logMemoryDirCounts(autoDir, {memory_type: 'auto'})
            logMemoryDirCounts(autoDir, java.util.Map.of("memory_type", "auto"));
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPromptBuilder] loadMemoryPrompt 走 auto-only 分支 dir={}", autoDir);
            }
            return String.join("\n", buildMemoryLines(AUTO_MEM_DISPLAY_NAME, autoDir, extraGuidelines, skipIndex));
        }

        // 4. disabled → null（CC tengu_memdir_disabled telemetry：两属性 + herring_clock 时 team 子事件）
        emitMemdirDisabled();
        log.info("[MemoryPromptBuilder] loadMemoryPrompt 返回 null（auto memory 禁用，tengu_memdir_disabled）");
        return null;
    }

    /**
     * 发射 {@code tengu_memdir_disabled}（disabled 分支 telemetry）· CC original:
     * {@code logEvent('tengu_memdir_disabled', {...})}（memdir.ts:492-505）。
     *
     * <p>属性（NEW-2/G-17，CC :493-499）：
     * <ul>
     *   <li>{@code disabled_by_env_var} = {@code isEnvTruthy(CLAUDE_CODE_DISABLE_AUTO_MEMORY)}</li>
     *   <li>{@code disabled_by_setting} = {@code !isEnvTruthy(env) && getInitialSettings().autoMemoryEnabled === false}
     *       —— Java 字面实现：{@code !envDisabled && Boolean.FALSE.equals(
     *       BundledSkillEnabledGates.readAutoMemoryEnabledSetting())}（settings 三源读取 =
     *       CC getInitialSettings 合并序，BundledSkillEnabledGates:157-191；仅显式配置为 false 才置位，
     *       bare/remote 等其它禁用原因不置位本属性 —— F1 返工，修正排除法推导与字面语义在
     *       bare/remote 与 settings=false 并存角例的偏差）</li>
     * </ul>
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)}（CC :503）时追加
     * {@code tengu_team_memdir_disabled} 子事件（{}）。
     *
     * <p>载体：双发射 recordEvent + logOTelEvent（telemetry=null → 仅 debug 日志，零行为变化）——
     * 跟随本文件 tengu_memdir_loaded 落地载体（NEW-1 登记偏差不改）。
     */
    private void emitMemdirDisabled() {
        boolean envDisabled = com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(
            System.getenv("CLAUDE_CODE_DISABLE_AUTO_MEMORY"));
        // CC memdir.ts:496-498 字面语义：!isEnvTruthy(env) && getInitialSettings().autoMemoryEnabled === false
        boolean settingDisabled = !envDisabled && Boolean.FALSE.equals(
            com.nexusai.application.agent.skill.BundledSkillEnabledGates.readAutoMemoryEnabledSetting());
        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("disabled_by_env_var", envDisabled);
        attrs.put("disabled_by_setting", settingDisabled);
        Telemetry t = this.telemetry;
        if (t == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MemoryPromptBuilder] tengu_memdir_disabled（telemetry 未接线，仅日志）"
                    + " disabled_by_env_var={} disabled_by_setting={}", envDisabled, settingDisabled);
            }
            return;
        }
        t.recordEvent("tengu_memdir_disabled", attrs);
        t.logOTelEvent("tengu_memdir_disabled", attrs);
        // CC memdir.ts:503-505：GB tengu_herring_clock → team 子事件
        if (herringClockFlag.getAsBoolean()) {
            t.recordEvent("tengu_team_memdir_disabled", java.util.Map.of());
            t.logOTelEvent("tengu_team_memdir_disabled", java.util.Map.of());
        }
    }
}



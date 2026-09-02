package com.nexusai.domain.settings;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.model.settings.dto.FontSize;
import com.nexusai.model.settings.dto.SettingsDto;
import com.nexusai.model.settings.dto.Theme;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PostConstruct;

/**
 * Settings 业务逻辑（singleton, id=1）：
 * - get：读 id=1 行（V1__init_schema.sql 已默认插入）
 * - update：merge 策略（部分字段 PUT 保留其它字段），最后写回 id=1
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private static final int SINGLETON_ID = 1;

    /** [V61] enabledPlugins 列 JSON 文本序列化/反序列化（Map<String,Boolean> ↔ TEXT）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private SettingsMapper settingsMapper;

    // [IMPL-10] DEL-CCE-03: hookRegistry（ConfigChange 发射）字段已删除（发射随删除）。
    /**
     * [IMPL-08 D8-1] 多来源 hooks 配置加载链（applySettingsChange 等价调用点）。
     * REST 更新 settings 后刷新 hooks 快照 · 对齐 CC applySettingsChange.ts:42
     * {@code updateHooksConfigSnapshot()}（settings 变更 → 运行中 hook 生效）。
     * null → 无 bean（跳过）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.hook.MultiSourceHooksConfigLoader hooksConfigLoader;

    /**
     * [C-1 · 实施阶段补充登记 4 · memoize 冻结] AutoMemPaths 生产 @Bean 单例
     * （ToolRegistrationConfig.autoMemPaths，memoryStorage/memoryScanner/MemoryPrefetcher/
     * ReadFileTool 共用同一实例）。update 检测 autoMemoryDirectory 变更时调 clearCache()
     * 清 memoize 缓存（getAutoMemPath 按 projectRoot 槽缓存 → 运行期改 DB 列后单例不再
     * 保持旧路径至 JVM 重启）。null → 无 bean（跳过 · 测试 POJO 场景零行为变化）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AutoMemPaths autoMemPaths;

    /**
     * [C-05 · OPD-CM5-C-05] 装配后把 SettingsMapper 桥接到 AutoMemPaths / BundledSkillEnabledGates
     * 静态持有，使 auto_memory_directory / auto_memory_enabled 的 DB settings 列可被读链消费
     * （前端"模型配置页-环境配置"配置后立即生效）。对齐 AnthropicSdkProvider 桥接惯例：
     * POJO 单测不触发 Spring → 静态为 null → 读链回落 settings 文件（零行为变化）。
     */
    @PostConstruct
    void bridgeAutoMemorySettingsMapper() {
        AutoMemPaths.bridgeSettingsMapper(settingsMapper);
        BundledSkillEnabledGates.bridgeSettingsMapper(settingsMapper);
        if (log.isInfoEnabled()) {
            log.info("[SettingsService] autoMemoryDirectory/autoMemoryEnabled DB 列桥接注入完成（C-05 前端可配）");
        }
    }

    /**
     * [agent-swarms-global] 装配 settings.agentSwarmsEnabled 实时 DB 读源到 TaskSystemConfig
     * （全局开关，换会话不依赖 get/update 触发刷新）。对齐 ModelConfigResolver.installModelNameResolverTierSources
     * （ModelConfigResolver.java:58-66 @PostConstruct 静态 Supplier 注入）：每次
     * {@link TaskSystemConfig#isAgentSwarmsEnabled()} 调用实时 {@link #readDbAgentSwarmsEnabled()}
     * selectOneById(SINGLETON_ID)，全局配置所有会话即时生效。POJO 单测不触发 Spring → source 不安装
     * → isAgentSwarmsEnabled() 回落 setAgentSwarmsSettingsOverride 测试 seam（零行为变化）。
     */
    @PostConstruct
    void bridgeAgentSwarmsSettingsSource() {
        TaskSystemConfig.installAgentSwarmsSettingsSource(this::readDbAgentSwarmsEnabled);
        if (log.isInfoEnabled()) {
            log.info("[SettingsService] agentSwarmsEnabled 实时 DB 读源注入完成（全局配置，换会话无需 get/update 即生效）");
        }
    }

    public SettingsDto get() {
        SettingsRecord s = settingsMapper.selectOneById(SINGLETON_ID);
        if (s == null) throw new NotFoundException("Settings row not found (id=" + SINGLETON_ID + ")");
        SettingsDto dto = toDto(s);
        // [agent-swarms-setting V42 + agent-swarms-global] 读库后同步静态覆盖标志（兼容 POJO 单测契约）：
        //   生产权威源为 @PostConstruct 安装的实时 DB 读源（bridgeAgentSwarmsSettingsSource，source 优先，
        //   此处 override 不参与判定）；POJO 无 Spring → source 未安装 → 此处 override 回落生效（测试 seam）。
        //   保留调用零行为变化。
        TaskSystemConfig.setAgentSwarmsSettingsOverride(dto.agentSwarmsEnabled());
        return dto;
    }

    public SettingsDto update(SettingsDto req) {
        SettingsRecord s = settingsMapper.selectOneById(SINGLETON_ID);
        if (s == null) throw new NotFoundException("Settings row not found (id=" + SINGLETON_ID + ")");

        // merge 策略：仅覆盖非空字段
        if (req.theme() != null) s.setTheme(req.theme().name());
        if (req.fontSize() != null) s.setFontSize(req.fontSize().name());
        if (req.accent() != null) s.setAccent(req.accent());
        if (req.animationsEnabled() != null) s.setAnimationsEnabled(req.animationsEnabled());
        // [FN2] 档位/主/回落模型字段 merge（xxxModelName：settings 存全名/裸名，全名反查唯一路径）
        if (req.mainModelName() != null) s.setMainModelName(req.mainModelName());
        if (req.fastModelName() != null) s.setFastModelName(req.fastModelName());
        // [W2-2] 档位四字段 merge（weak/medium/strong/subagent）
        if (req.weakModelName() != null) s.setWeakModelName(req.weakModelName());
        if (req.mediumModelName() != null) s.setMediumModelName(req.mediumModelName());
        if (req.strongModelName() != null) s.setStrongModelName(req.strongModelName());
        if (req.subagentModelName() != null) s.setSubagentModelName(req.subagentModelName());
        // [F1] max output tokens 有界 override merge（null = 不覆盖；CC envValidation.ts:9-38 迁移）
        if (req.maxOutputTokens() != null) s.setMaxOutputTokens(req.maxOutputTokens());
        // [F4] 回落模型 merge（null = 不覆盖）
        if (req.fallbackModelName() != null) s.setFallbackModelName(req.fallbackModelName());
        // [TN1] 多模态/TTS/ASR 档位 merge（null = 不覆盖；使用先不使用：可配置可读取，不上发 LLM）
        if (req.multimodalModelName() != null) s.setMultimodalModelName(req.multimodalModelName());
        if (req.ttsModelName() != null) s.setTtsModelName(req.ttsModelName());
        if (req.asrModelName() != null) s.setAsrModelName(req.asrModelName());
        // [W3-1] 压缩窗口上限 merge（null = 不覆盖；CC autoCompact.ts:40-46）
        if (req.autoCompactWindow() != null) s.setAutoCompactWindow(req.autoCompactWindow());

        // [C-05 · OPD-CM5-C-05] autoMemoryDirectory 存 DB 列（V34 列 auto_memory_directory，
        // 前端"模型配置页-环境配置"可配置，对齐 CC 给默认值；null = 不覆盖）。
        // [C-1 · 实施阶段补充登记 4] 变更检测：仅当值实际变化才记录 changed（值相等 → 不
        // 触发 memoize 清理，避免无谓失效）。
        boolean autoMemoryDirChanged = false;
        if (req.autoMemoryDirectory() != null) {
            autoMemoryDirChanged = !Objects.equals(s.getAutoMemoryDirectory(), req.autoMemoryDirectory());
            s.setAutoMemoryDirectory(req.autoMemoryDirectory());
        }
        // [C-05] autoMemoryEnabled 也存 DB 列（V34 列 auto_memory_enabled，DB + 前端；null = 不覆盖）。
        if (req.autoMemoryEnabled() != null) s.setAutoMemoryEnabled(req.autoMemoryEnabled());
        // [V56 · 用户 2026-08-30 拍板] autoDreamEnabled 存 DB 列（V56 列 auto_dream_enabled，
        // 前端可配 + 默认开；null = 不覆盖）。弃用 settings.json 文件承载键——写侧经
        // BundledSkillEnabledGates.writeAutoMemoryToggles 只落 DB 列（不再写文件）。
        if (req.autoDreamEnabled() != null) s.setAutoDreamEnabled(req.autoDreamEnabled());

        // [websearch-ccalign V37] WebSearch 4 列 merge（null = 不覆盖；DB + 前端可配置）
        if (req.websearchEngine() != null) s.setWebsearchEngine(req.websearchEngine());
        if (req.apiKey() != null) s.setApiKey(req.apiKey());
        if (req.proxy() != null) s.setProxy(req.proxy());
        if (req.websearchUseSmallModel() != null) s.setWebsearchUseSmallModel(req.websearchUseSmallModel());
        // [websearch-resid R-B] WebSearch 第 5 列 websearchBaseUrl merge（V38；null = 不覆盖，对齐既有 merge）
        if (req.websearchBaseUrl() != null) s.setWebsearchBaseUrl(req.websearchBaseUrl());
        // [websearch-domaincheck V39] 域预检端点 merge（null = 不覆盖，对齐既有 merge；空 → 跳过预检）
        if (req.websearchDomainCheckUrl() != null) s.setWebsearchDomainCheckUrl(req.websearchDomainCheckUrl());
        // [agent-swarms-setting V42] Agent Swarms 开关 merge（null = 不覆盖，对齐既有 merge；写库后尾部同步静态覆盖）
        if (req.agentSwarmsEnabled() != null) s.setAgentSwarmsEnabled(req.agentSwarmsEnabled());
        // [V44] 全局默认权限模式 merge（null = 不覆盖，对齐既有 merge）：写侧 isSettable 校验——
        //   UI/HTTP 值必须在 6 值集合（default/plan/acceptEdits/bypassPermissions/dontAsk/auto），
        //   否则 fail-loud（ValidationException 400）而非静默折叠最严格 DEFAULT（列必须存 CC 串
        //   acceptEdits 而非枚举 name ACCEPT_EDITS，双防）。存 CC 串原样（round-trip 保真），非枚举 name。
        if (req.permissionMode() != null) {
            if (!PermissionMode.isSettable(req.permissionMode())) {
                throw new ValidationException(
                    "permissionMode 非法（允许 default/plan/acceptEdits/bypassPermissions/dontAsk/auto）");
            }
            s.setPermissionMode(req.permissionMode().trim());
        }
        // [V45] Yolo 分类器模型 merge（null = 不覆盖，对齐既有 merge；空白 → YoloClassifierImpl 主循环兜底）
        if (req.classifierModel() != null) s.setClassifierModel(req.classifierModel());
        // [V52 token-compact-fix B1-1] 压缩配置 12 列 merge（null = 不覆盖，对齐既有 merge；
        //   DB 列承载，前端「环境配置」可配；消费点经 CompactSettingsResolver 实时读，写库即生效）
        if (req.autoCompactEnabled() != null) s.setAutoCompactEnabled(req.autoCompactEnabled());
        if (req.reactiveCompactEnabled() != null) s.setReactiveCompactEnabled(req.reactiveCompactEnabled());
        if (req.contextCollapseEnabled() != null) s.setContextCollapseEnabled(req.contextCollapseEnabled());
        if (req.historySnipEnabled() != null) s.setHistorySnipEnabled(req.historySnipEnabled());
        if (req.smSessionMemoryEnabled() != null) s.setSmSessionMemoryEnabled(req.smSessionMemoryEnabled());
        if (req.smCompactEnabled() != null) s.setSmCompactEnabled(req.smCompactEnabled());
        if (req.cachedMicrocompactEnabled() != null) s.setCachedMicrocompactEnabled(req.cachedMicrocompactEnabled());
        if (req.timeBasedMcEnabled() != null) s.setTimeBasedMcEnabled(req.timeBasedMcEnabled());
        if (req.timeBasedMcGapMinutes() != null) s.setTimeBasedMcGapMinutes(req.timeBasedMcGapMinutes());
        if (req.timeBasedMcKeepRecent() != null) s.setTimeBasedMcKeepRecent(req.timeBasedMcKeepRecent());
        if (req.disableCompact() != null) s.setDisableCompact(req.disableCompact());
        if (req.disableAutoCompact() != null) s.setDisableAutoCompact(req.disableAutoCompact());
        // [V54 token-compact-fix B1-1 续] 压缩数值 11 列 merge（null = 不覆盖，对齐既有 merge；
        //   DB 列承载，前端「环境配置」可配；消费点经 CompactSettingsResolver 实时读，写库即生效）
        if (req.cachedMicrocompactTriggerThreshold() != null) s.setCachedMicrocompactTriggerThreshold(req.cachedMicrocompactTriggerThreshold());
        if (req.cachedMicrocompactKeepRecent() != null) s.setCachedMicrocompactKeepRecent(req.cachedMicrocompactKeepRecent());
        if (req.smMinTokens() != null) s.setSmMinTokens(req.smMinTokens());
        if (req.smMinTextBlockMessages() != null) s.setSmMinTextBlockMessages(req.smMinTextBlockMessages());
        if (req.smMaxTokens() != null) s.setSmMaxTokens(req.smMaxTokens());
        if (req.smMinimumMessageTokensToInit() != null) s.setSmMinimumMessageTokensToInit(req.smMinimumMessageTokensToInit());
        if (req.smMinimumTokensBetweenUpdate() != null) s.setSmMinimumTokensBetweenUpdate(req.smMinimumTokensBetweenUpdate());
        if (req.smToolCallsBetweenUpdates() != null) s.setSmToolCallsBetweenUpdates(req.smToolCallsBetweenUpdates());
        if (req.maxConsecutiveAutocompactFailures() != null) s.setMaxConsecutiveAutocompactFailures(req.maxConsecutiveAutocompactFailures());
        if (req.maxPtlRetries() != null) s.setMaxPtlRetries(req.maxPtlRetries());
        if (req.maxCompactStreamingRetries() != null) s.setMaxCompactStreamingRetries(req.maxCompactStreamingRetries());
        // [V55 fix-transcript-nudge] snip nudge 消息数阈值 merge（null = 不覆盖；V55 列
        //   snip_nudge_threshold，前端「环境配置」可配；消费点 CompactSettingsResolver 实时读，写库即生效）
        if (req.snipNudgeThreshold() != null) s.setSnipNudgeThreshold(req.snipNudgeThreshold());
        // [prompt-align G0-02 V56] 提示词对齐门控 12 列 merge（null = 不覆盖，对齐既有 merge；
        //   DB 列承载，前端「环境配置」可配；消费点经 PromptAlignSettingsResolver 实时读，写库即生效）
        if (req.taskReminderEnabled() != null) s.setTaskReminderEnabled(req.taskReminderEnabled());
        if (req.deferredToolsDeltaEnabled() != null) s.setDeferredToolsDeltaEnabled(req.deferredToolsDeltaEnabled());
        if (req.systemPromptBoundaryEnabled() != null) s.setSystemPromptBoundaryEnabled(req.systemPromptBoundaryEnabled());
        if (req.proactiveEnabled() != null) s.setProactiveEnabled(req.proactiveEnabled());
        if (req.coordinatorModeEnabled() != null) s.setCoordinatorModeEnabled(req.coordinatorModeEnabled());
        if (req.skillSearchIntentEnabled() != null) s.setSkillSearchIntentEnabled(req.skillSearchIntentEnabled());
        if (req.scratchpadEnabled() != null) s.setScratchpadEnabled(req.scratchpadEnabled());
        if (req.frcEnabled() != null) s.setFrcEnabled(req.frcEnabled());
        if (req.agentMainThreadEnabled() != null) s.setAgentMainThreadEnabled(req.agentMainThreadEnabled());
        if (req.verifyPlanReminderEnabled() != null) s.setVerifyPlanReminderEnabled(req.verifyPlanReminderEnabled());
        if (req.language() != null) s.setLanguage(req.language());
        if (req.outputStyle() != null) s.setOutputStyle(req.outputStyle());
        // [V61 插件配置 DB 化] 插件双读配置 2 列 merge（null = 不覆盖，对齐既有 merge；
        //   DB 列承载，前端「插件设置 / 插件管理页」可配）：
        //   enabledPlugins → Map 序列化 JSON 文本落 enabled_plugins 列（前端插件管理页写，
        //   InstalledPluginsManager 读链 DB 优先）；pluginClaudeFallback → 0/1 落
        //   plugin_claude_fallback 列（原 yml nexusai.feature.plugin-claude-fallback 迁移 DB）。
        if (req.enabledPlugins() != null) s.setEnabledPlugins(toJson(req.enabledPlugins()));
        if (req.pluginClaudeFallback() != null) s.setPluginClaudeFallback(req.pluginClaudeFallback());

        // [IMP-MV2-16 + V56] auto-memory/auto-dream 开关写链：
        //   autoMemoryEnabled → DB 列（V34 auto_memory_enabled）+ settings.json 双写（[C-05] DB 为主，
        //   文件承载兼容读链回落）；autoDreamEnabled → [V56 · 用户 2026-08-30 拍板] 仅 DB 列
        //   （V56 auto_dream_enabled，默认开），弃用 settings.json 文件承载（不再写 autoDreamEnabled）。
        //   上方两处 merge 已直接落 DB 列；writeAutoMemoryToggles 内 DB 写为幂等双写（对齐既有
        //   autoMemory 先例），文件写仅 autoMemoryEnabled。
        if (req.autoMemoryEnabled() != null || req.autoDreamEnabled() != null) {
            BundledSkillEnabledGates.writeAutoMemoryToggles(
                req.autoMemoryEnabled(), req.autoDreamEnabled());
        }

        settingsMapper.update(s);

        // [C-1 · 实施阶段补充登记 4 · memoize 冻结] autoMemoryDirectory 运行期变更 → 清 AutoMemPaths
        // memoize 缓存：getAutoMemPath 按 projectRoot 槽缓存（AutoMemPaths#autoMemPathCache，对齐 CC
        // paths.ts:223-235 lodash memoize + paths.ts:216-221 "settings 会话稳定、变更需 cache.clear"），
        // 生产 @Bean 单例（ToolRegistrationConfig.autoMemPaths）跨会话持有——前端运行期改 DB 列后不
        // clearCache 则单例保持旧路径至 JVM 重启（主会话路径每 prompt 新建实例正确）。DB+前端运行时
        // 配置（C-05）为 Java 特有源，故在 update 检测变更时补清理（对齐 CC 真源，不引入兼容层）。
        if (autoMemoryDirChanged && autoMemPaths != null) {
            autoMemPaths.clearCache();
            if (log.isInfoEnabled()) {
                log.info("[SettingsService] autoMemoryDirectory 变更，已清 AutoMemPaths memoize 缓存，运行期新路径立即生效");
            }
        }

        // [IMPL-10] DEL-CCE-03: ConfigChange 后置发射已删除 — CC ConfigChange 由 settings
        //   文件 watcher 前置触发（changeDetector.ts:289-301，blocked → 跳过 fanOut）；
        //   REST 更新后的快照刷新由下方 updateHooksConfigSnapshot（IMPL-08 applySettingsChange
        //   等价调用点）承担。
        // [IMPL-08 D8-1] 对齐 CC applySettingsChange.ts:42 — settings 更新后刷新
        //   hooks 配置快照（多来源重读 + 快照刷新，运行中配置变更生效，不重启）
        if (hooksConfigLoader != null) {
            try {
                hooksConfigLoader.updateHooksConfigSnapshot();
            } catch (Exception e) {
                // best-effort, 不影响主流程
            }
        }

        // [agent-swarms-setting V42 + agent-swarms-global] 写库后同步静态覆盖标志（兼容 POJO 单测契约）：
        //   生产权威源为 @PostConstruct 安装的实时 DB 读源（每次读 DB，source 优先，前端改开关即生效）；
        //   此处 override 仅在 POJO（无 Spring → source 未安装）回落生效。保留调用零行为变化。
        SettingsDto dto = toDto(s);
        TaskSystemConfig.setAgentSwarmsSettingsOverride(dto.agentSwarmsEnabled());
        return dto;
    }

    /** [agent-swarms-global] 实时读 settings 单例行 agentSwarmsEnabled（全局配置，每次调用读 DB）。行缺失/异常 → null（不覆盖 CC 原链）。 */
    private Boolean readDbAgentSwarmsEnabled() {
        try {
            SettingsRecord s = settingsMapper.selectOneById(SINGLETON_ID);
            return s == null ? null : s.getAgentSwarmsEnabled();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[SettingsService] 实时读 settings.agentSwarmsEnabled 失败，回落 null（不覆盖 CC 原判定链）: {}", e.toString());
            }
            return null;
        }
    }

    // ============== helpers ==============

    private static SettingsDto toDto(SettingsRecord s) {
        return new SettingsDto(
            s.getTheme() != null ? Theme.valueOf(s.getTheme()) : null,
            s.getFontSize() != null ? FontSize.valueOf(s.getFontSize()) : null,
            s.getAccent(),
            s.getAnimationsEnabled(),
            s.getMainModelName(),
            s.getFastModelName(),
            // [W2-2] 档位四字段透出（weak/medium/strong/subagent，可空）
            s.getWeakModelName(),
            s.getMediumModelName(),
            s.getStrongModelName(),
            s.getSubagentModelName(),
            // [F1] max output tokens 有界 override 透出（可空）
            s.getMaxOutputTokens(),
            // [F4] 回落模型透出（可空）
            s.getFallbackModelName(),
            // [TN1] 多模态/TTS/ASR 档位透出（可空；使用先不使用）
            s.getMultimodalModelName(),
            s.getTtsModelName(),
            s.getAsrModelName(),
            // [W3-1] 压缩窗口上限透出（可空；V26 列 auto_compact_window）
            s.getAutoCompactWindow(),
            // [C-05] autoMemoryDirectory DB 列透出（V34 列 auto_memory_directory；未配置 → null =
            //   AutoMemPaths per-project 默认计算，对齐 CC 给默认值）
            s.getAutoMemoryDirectory(),
            // [C-05] autoMemoryEnabled DB 列优先（V34 列 auto_memory_enabled，前端可配主通道）；
            //   DB 未配置 → 回落 settings.json 文件真值（BundledSkillEnabledGates 读链 DB 优先）
            s.getAutoMemoryEnabled() != null
                ? s.getAutoMemoryEnabled()
                : BundledSkillEnabledGates.readAutoMemoryEnabledSetting(),
            // [V56 · 用户 2026-08-30 拍板] autoDreamEnabled DB 列主控（V56 列 auto_dream_enabled，
            // 前端可配 + 默认开）；未配置 → null（门控消费点回落默认 true ·
            // AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv）。弃用 settings.json 文件
            // 承载键（旧 BundledSkillEnabledGates.readAutoDreamEnabledSetting 文件三源读取已删）。
            s.getAutoDreamEnabled(),
            // [websearch-ccalign V37] WebSearch 4 列透出（可空；缺省/未配置 → WebSearchTool 读链兜底默认）
            s.getWebsearchEngine(),
            s.getApiKey(),
            s.getProxy(),
            s.getWebsearchUseSmallModel(),
            // [websearch-resid R-B] WebSearch 第 5 列 websearchBaseUrl 透出（V38；null → WebSearchTool 兜底默认）
            s.getWebsearchBaseUrl(),
            // [websearch-domaincheck V39] 域预检端点透出（V39；null → WebFetchTool 跳过预检）
            s.getWebsearchDomainCheckUrl(),
            // [agent-swarms-setting V42] Agent Swarms 开关透出（V42；null = 未配置不覆盖 CC 原判定链）
            s.getAgentSwarmsEnabled(),
            // [V44] 全局默认权限模式透出（V44 列 permission_mode；null = 未配置 → 回落磁盘 settings.json
            //   defaultMode → default）。存 CC 串原样（round-trip 保真），非枚举 name。
            s.getPermissionMode(),
            // [V45] Yolo 分类器模型透出（V45 列 classifier_model；null = 未配置 → YoloClassifierImpl
            //   兜底主循环模型。yml 覆写 nexusai.classifier.model 在 YoloClassifierImpl 内部仍优先）
            s.getClassifierModel(),
            // [V52 token-compact-fix B1-1] 压缩配置 12 列透出（V52 列；null = 未配置 →
            //   消费点回落 CC 原判定链 env/FeatureFlags/硬编码默认）
            s.getAutoCompactEnabled(),
            s.getReactiveCompactEnabled(),
            s.getContextCollapseEnabled(),
            s.getHistorySnipEnabled(),
            s.getSmSessionMemoryEnabled(),
            s.getSmCompactEnabled(),
            s.getCachedMicrocompactEnabled(),
            s.getTimeBasedMcEnabled(),
            s.getTimeBasedMcGapMinutes(),
            s.getTimeBasedMcKeepRecent(),
            s.getDisableCompact(),
            s.getDisableAutoCompact(),
            // [V54 token-compact-fix B1-1 续] 压缩数值 11 列透出（V54 列；null = 未配置 →
            //   消费点回落 CC 硬编码默认）
            s.getCachedMicrocompactTriggerThreshold(),
            s.getCachedMicrocompactKeepRecent(),
            s.getSmMinTokens(),
            s.getSmMinTextBlockMessages(),
            s.getSmMaxTokens(),
            s.getSmMinimumMessageTokensToInit(),
            s.getSmMinimumTokensBetweenUpdate(),
            s.getSmToolCallsBetweenUpdates(),
            s.getMaxConsecutiveAutocompactFailures(),
            s.getMaxPtlRetries(),
            s.getMaxCompactStreamingRetries(),
            // [V55 fix-transcript-nudge] snip nudge 消息数阈值透出（V55 列 snip_nudge_threshold；
            //   null = 未配置 → 消费点回落窗口自适应算法 SnipCompactor.resolveSnipNudgeThreshold）
            s.getSnipNudgeThreshold(),
            // [prompt-align G0-02 V56] 提示词对齐门控 12 列透出（V56 列；null = 未配置 →
            //   消费点回落 CC 原判定链 env/FeatureFlags/硬编码默认/既有判定类）
            s.getTaskReminderEnabled(),
            s.getDeferredToolsDeltaEnabled(),
            s.getSystemPromptBoundaryEnabled(),
            s.getProactiveEnabled(),
            s.getCoordinatorModeEnabled(),
            s.getSkillSearchIntentEnabled(),
            s.getScratchpadEnabled(),
            s.getFrcEnabled(),
            s.getAgentMainThreadEnabled(),
            s.getVerifyPlanReminderEnabled(),
            s.getLanguage(),
            s.getOutputStyle(),
            // [V61] enabledPlugins DB 列透出（V61 列 enabled_plugins，JSON 文本 → Map；
            //   null/解析失败 = 未配置 → InstalledPluginsManager 读链回落 ConfigStorage settings.json
            //   → CC settings（~/.claude/settings.json）双读）
            parseEnabledPlugins(s.getEnabledPlugins()),
            // [V61] pluginClaudeFallback DB 列透出（V61 列 plugin_claude_fallback；
            //   null = 未配置 → 插件双读回落默认 true，原 yml nexusai.feature.plugin-claude-fallback:true）
            s.getPluginClaudeFallback()
        );
    }

    // ============== [V61] enabledPlugins JSON 编解码 ==============

    /** [V61] enabled_plugins JSON 文本 → Map<String,Boolean>；null/空白/解析失败 → null（未配置回落）。 */
    private static Map<String, Boolean> parseEnabledPlugins(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Boolean>>() { });
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[SettingsService] 解析 settings.enabled_plugins 失败，视为未配置: {}", e.getMessage());
            }
            return null;
        }
    }

    /** [V61] Map<String,Boolean> → JSON 文本（null → null 不落库，merge 不覆盖）。 */
    private static String toJson(Map<String, Boolean> map) {
        if (map == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("enabledPlugins 序列化失败: " + e.getMessage(), e);
        }
    }
}

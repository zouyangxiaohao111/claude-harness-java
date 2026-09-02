package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.plugin.BuiltinPluginRegistry;
import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.command.ClientEnv;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandAvailability;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import com.nexusai.repository.command.entity.CommandRecord;
import com.nexusai.repository.command.mapper.CommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 技能注册中心 · 聚合所有来源的技能（Bundled + User + Plugin + COMMANDS，MCP 分离 thread-in）
 *
 * <p>对齐 CC commands.ts getCommands() — 合并 bundledSkills + skillDirCommands + pluginSkills +
 * builtinPluginSkills + COMMANDS（DEC-9 第 5 源，commands.ts:467 ...COMMANDS() 最后合并）。
 * MCP 技能<b>不并入</b> getCommands（CC commands.ts:541-546 「These live outside getCommands() so
 * callers that need MCP skills in their skill index thread them through separately」，P2-9 分离语义）：
 * 消费方经 {@link #findCommandIncludingMcp}（SkillTool 搜索基座）与 {@link #getModelInvocableCommandsForListing}
 * （skill_listing 注入视图）thread-in 合并。
 *
 * <h2>CC 对齐</h2>
 * <table>
 *   <tr><th>CC 来源</th><th>Java 加载方式</th></tr>
 *   <tr><td>{@code bundledSkills}</td><td>{@link BundledSkills#getAll()}</td></tr>
 *   <tr><td>{@code skillDirCommands}</td><td>{@link SkillsLoader#getSkillDirCommands(String)}（P2-20 五源，
 *       cwdSupplier 注入；POJO 回退 {@link SkillsLoader#loadFromDirectory(String)}）</td></tr>
 *   <tr><td>{@code pluginSkills}</td><td>plugin 目录扫描（暂未实现）</td></tr>
 *   <tr><td>{@code builtinPluginSkills}</td><td>内置插件命令（暂未实现）</td></tr>
 *   <tr><td>{@code COMMANDS}</td><td>{@link BuiltInCommands#getAll()}（DEC-9 内置斜杠命令子集，
 *       source=BUILTIN；对齐 CC commands.ts:467 ...COMMANDS() 最后合并）</td></tr>
 * </table>
 *
 * <p>名称去重规则：按 source 优先级 — builtin > bundled > plugin > user。
 * 同名命令以最先出现的为准。
 *
 * <h2>memoize 缓存（P1-1 · 对齐 CC loadAllCommands / getSkillToolCommands）</h2>
 * <p>CC 用 {@code memoize(by cwd)} 对命令加载做缓存（commands.ts:449 {@code loadAllCommands}、
 * commands.ts:563 {@code getSkillToolCommands}、commands.ts:586 {@code getSlashCommandToolSkills}）。
 * IMP-E 起缓存键 = projectRoot + skillsRoot 复合（{@link #currentCacheKey()}）：projectRoot 分量经
 * {@link #cwdSupplier}（生产 = {@code AutoMemPaths::currentSessionProjectRoot}，per-session ThreadLocal）
 * 取值 → 会话 A(Pa)/B(Pb) 各自独立缓存槽，M-09 不可逆污染消除（CC memoize-by-cwd 的 Java 等价物）。
 * <ul>
 *   <li>{@link #getAllCommands()} → {@link #allCommandsCache}（对齐 CC {@code loadAllCommands}）</li>
 *   <li>{@link #getModelInvocableCommands()} → {@link #modelInvocableCache}（对齐 CC
 *       {@code getSkillToolCommands} 独立 memoize 层）</li>
 *   <li>{@link #getSlashCommandToolSkills()} → {@link #slashCommandToolSkillsCache}（P2-3 · 对齐 CC
 *       {@code getSlashCommandToolSkills} commands.ts:586 第二套过滤，getSkillInfo 数据源）</li>
 *   <li>{@link #refresh()} 为唯一显式失效入口（对齐 CC {@code clearCommandMemoizationCaches}
 *       commands.ts:523-531 + {@code clearSkillCaches} loadSkillsDir.ts:806-811）：磁盘变更
 *       在 refresh() 前不即时可见 —— 这正是 CC 语义（CC 靠 chokidar P1-16 显式 clear cache）；
 *       refresh() 同时清空全部三层缓存。MCP 技能不落缓存（thread-in 每调用新鲜合并，
 *       {@code McpServerService#getMcpSkillCommands} 门控即时生效）。</li>
 * </ul>
 *
 * <h2>每源错误隔离（P1-1 · 对齐 CC getSkills commands.ts:360-373）</h2>
 * <p>CC {@code getSkills} 对 skillDirCommands/pluginSkills 每源独立 {@code .catch(err => logError + return [])}，
 * 外层兜底全空。Java 等价：{@link #loadAllCommands()} 4 源各自 try-catch → log.warn + 该源跳过，
 * 任一源异常不再中断整体。
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 命令聚合缓存 · 对齐 CC {@code loadAllCommands} memoize（commands.ts:449）· 键 = projectRoot+skillsRoot 复合（{@link #currentCacheKey()}） */
    private final ConcurrentHashMap<String, List<Command>> allCommandsCache = new ConcurrentHashMap<>();
    /** 模型可调用命令过滤缓存 · 对齐 CC {@code getSkillToolCommands} 独立 memoize（commands.ts:563）· 键 = projectRoot+skillsRoot 复合（{@link #currentCacheKey()}） */
    private final ConcurrentHashMap<String, List<Command>> modelInvocableCache = new ConcurrentHashMap<>();
    /** 斜杠命令技能过滤缓存 · 对齐 CC {@code getSlashCommandToolSkills} 独立 memoize（commands.ts:586）· 键 = projectRoot+skillsRoot 复合（{@link #currentCacheKey()}） */
    private final ConcurrentHashMap<String, List<Command>> slashCommandToolSkillsCache = new ConcurrentHashMap<>();

    private final SkillsLoader loader = new SkillsLoader();
    private String skillsRoot;
    private BuiltinPluginRegistry builtinPluginRegistry;
    private McpServerService mcpServerService;
    /**
     * MPL6: plugin 加载器 · 对齐 CC getPluginCommands/getPluginSkills（loadPluginCommands.ts:414-942）
     * 经 loadAllPluginsCacheOnly 产出的 enabled plugins。
     *
     * <p>由 {@link #setPluginLoader} 注入；null 时 {@link #getAllCommands()} 无 plugin 命令/技能源
     * （POJO 兼容，现有测试不破）。生产装配点：ToolRegistrationConfig.skillRegistry()（MPL6 允许
     * 修改清单外，登记为 follow-up）。
     */
    private PluginLoader pluginLoader;
    /**
     * P2-20: cwd 供应 · 非 null 时文件系统源走五源 {@link SkillsLoader#getSkillDirCommands(String)}
     * （生产对齐 CC getCommands → getSkillDirCommands(cwd)，commands.ts:361-367）；null → 单目录回退
     * {@link SkillsLoader#loadFromDirectory}（POJO/测试，SkillRegistry(String) 直构不变）。
     */
    private Supplier<String> cwdSupplier;
    /**
     * P1-3: workflow 命令源 · 对齐 CC getWorkflowCommands（commands.ts:401-406 + :457/:464）。
     *
     * <p>CC {@code getWorkflowCommands = feature('WORKFLOW_SCRIPTS') ? require(createWorkflowCommand).getWorkflowCommands : null}，
     * {@code loadAllCommands} 第 4 源 {@code ...workflowCommands}（commands.ts:457/:464，bundled→builtinPlugin→
     * skillDir→workflow→pluginCommands→pluginSkills→COMMANDS）。WORKFLOW_SCRIPTS feature 关时
     * getWorkflowCommands 为 null → workflowCommands 产出 []。
     *
     * <p>Java 等价：可注入 {@link Supplier}{@code <List<Command>>}，null 默认 = feature 关（无 workflow 命令）；
     * 由 {@link #setWorkflowCommandProvider} 注入（生产装配点 ToolRegistrationConfig 按
     * {@code FeatureFlags.workflowScripts()} 门控接线）。未注入时 {@link #getAllCommands()} 输出不变
     * （POJO 兼容，现有测试不破）。
     */
    private Supplier<List<Command>> workflowCommandProvider;
    /**
     * P1-2: 动态技能管理器 · 对齐 CC getDynamicSkills（loadSkillsDir.ts:981-983）。
     * <p>由 {@link #setDynamicSkillsManager} 注入；null 时 {@link #getAllCommands()} 输出不变
     * （POJO 兼容，现有测试不破）。
     */
    private DynamicSkillsManager dynamicSkillsManager;

    /**
     * 方案1（用户拍板）: DB enabled 主控源 · 用户 skill 启用/禁用写 DB（前端 PATCH
     * {@code /api/command/{id}/toggle} / update enabled → CommandService 写
     * {@code commandMapper.update}）。{@link #loadAllCommands()} 合并五源后，若本字段注入
     * （生产装配点 ToolRegistrationConfig.skillRegistry()），一次性
     * {@code commandMapper.selectAll()} 建 name→enabled 映射，覆盖同名命令的 <b>enabled</b>
     * 字段（其余字段文件权威）—— 列表合并以本 registry 为权威（getAllCommands /
     * findCommandIncludingMcp 均读此），前端禁用/启用真实生效。
     *
     * <p><b>bundled skill 的 isEnabled supplier 保持优先</b>：{@link Command#isCommandEnabled()}
     * = supplier 非 null 新鲜求值，覆盖 enabled 字段对 supplier skill 无效（符合 CC isEnabled
     * 优先语义，types/command.ts:214-215 {@code isEnabled?.() ?? true}）—— 如 GB/autoMemory
     * 运行时 gate 命令不受 DB toggle 影响。
     *
     * <p>未注入时 {@link #getAllCommands()} 行为不变（POJO 测试兼容）。
     */
    private CommandMapper commandMapper;

    /**
     * P3-3: 认证/供应商状态供应 · 对齐 CC isClaudeAISubscriber（auth.ts:1564-1570）/
     * isUsing3PServices（auth.ts:1732-1738）/ isFirstPartyAnthropicBaseUrl（utils/model/providers.ts:25-40）。
     *
     * <p>由 {@link #setAvailabilityAuthState} 注入；未注入默认 subscriber=false / using3P=false /
     * firstParty=true（web 端无 claude-ai/console 订阅模型，DEC-8 待主 agent + 用户拍板）→
     * 现有命令 availability 全 null = universal 直通，运行时行为零变化（POJO 兼容，现有测试不破）。
     */
    private AvailabilityAuthState availabilityAuthState =
        new AvailabilityAuthState(() -> false, () -> false, () -> true);

    /**
     * P1-15: 技能使用追踪 · 对齐 CC utils/suggestions/skillUsageTracking.ts（recordSkillUsage + getSkillUsageScore）。
     * <p>本注册中心承载<b>读侧</b> {@link #getSkillUsageScore} 透传 API（消费方数据源，CC commandSuggestions
     * 以 getAllCommands + getSkillUsageScore 为排序数据源）；写侧调用点在 SkillToolImpl.doExecute
     * （CC SkillTool.ts:619）。由 {@link #setSkillUsageTracking} 注入；null 时 {@link #getSkillUsageScore}
     * 返回 0，行为不变（POJO 兼容）。
     */
    private SkillUsageTracking skillUsageTracking;

    /**
     * P3-5: skill-search 索引清除回调 · CC 原形 {@code clearSkillIndexCache?.()}
     * （clearCommandMemoizationCaches 内，commands.ts:531）。
     *
     * <p>{@link #refresh()} 对齐 CC {@code clearCommandMemoizationCaches}（commands.ts:523-531）——
     * CC 该函数在清完三层命令缓存后调用 {@code clearSkillIndexCache?.()}（:531，注释「getSkillIndex
     * 是 built ON TOP of getSkillToolCommands 的独立 memoize 层，必须显式清」）。Java 端无真实
     * skill-search 索引（concern #30 子系统范围外）→ 默认 no-op；经 {@link #setSkillIndexClearer}
     * 注入委托 {@code SkillDiscoveryPrefetch.clearSkillIndexCache()}（镜像 CC require-based 间接）。
     * SkillRegistry 与 SkillDiscoveryPrefetch 同包，setter 注入（POJO 兼容，null → no-op，
     * 镜像 {@link #setMcpServerService} 模式）。
     */
    private Runnable skillIndexClearer = () -> {
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 未接线 skill 索引清除器（skill-search 子系统范围外），refresh() 跳过 clearSkillIndexCache");
        }
    };

    public SkillRegistry(String skillsRoot) {
        this.skillsRoot = skillsRoot;
    }

    /**
     * 注入内置插件注册中心（POJO 兼容 · 不改构造器）· 对齐 CC builtinPlugins.
     *
     * <p>未注入时 {@link #getAllCommands()} 行为不变（POJO 测试兼容）.
     */
    public void setBuiltinPluginRegistry(BuiltinPluginRegistry builtinPluginRegistry) {
        this.builtinPluginRegistry = builtinPluginRegistry;
    }

    /**
     * MPL6: 注入 plugin 加载器（POJO 兼容 · setter）· 对齐 CC getPluginCommands/getPluginSkills
     * 源（loadPluginCommands.ts:414-942）。由 {@link PluginLoader#loadAllEnabledCommands()} /
     * {@link PluginLoader#loadAllEnabledSkills()} 产出 plugin 命令/技能，经
     * {@link #loadAllCommands()} 按 CC 合并序（bundled→builtinPlugin→FS→pluginCommands→pluginSkills→
     * dynamic→COMMANDS，commands.ts:460-468）并入。
     *
     * <p>未注入时 {@link #getAllCommands()} 无 plugin 源，行为不变（POJO 测试兼容）。
     */
    public void setPluginLoader(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 注入 PluginLoader (plugin 命令/技能源激活, 对齐 CC commands.ts:460-468)");
        }
    }

    /**
     * P1-3: 注入 workflow 命令源（POJO 兼容 · setter）· 对齐 CC getWorkflowCommands
     * （commands.ts:401-406，feature WORKFLOW_SCRIPTS 门控）。
     *
     * <p>null 默认 = WORKFLOW_SCRIPTS feature 关（CC getWorkflowCommands 为 null →
     * workflowCommands 产出 []）。生产装配点 {@code ToolRegistrationConfig.skillRegistry()}
     * 按 {@code FeatureFlags.workflowScripts()} 门控接线；workflow 域提供真实加载器
     * （createWorkflowCommand 等价物）后注入。
     *
     * <p>未注入时 {@link #getAllCommands()} 无 workflow 源，行为不变（POJO 测试兼容）。
     */
    public void setWorkflowCommandProvider(Supplier<List<Command>> workflowCommandProvider) {
        this.workflowCommandProvider = workflowCommandProvider;
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 注入 workflow 命令源 (对齐 CC getWorkflowCommands commands.ts:401-406/:457/:464)");
        }
    }

    /**
     * P2-9: 注入 MCP 服务器服务（POJO 兼容 · setter）· CC original: {@code getMcpSkillCommands}
     * commands.ts:547-559 thread-in 源。
     *
     * <p>MCP 技能不并入 {@link #getAllCommands()}（CC commands.ts:541-546「live outside getCommands」），
     * 本字段职责为<b>thread-in 源</b>：{@link #findCommandIncludingMcp} 经
     * {@code mcpServerService.getMcpSkillCommandsForSearch()}（S3 搜索视图，SkillTool.ts:81-94）、
     * {@link #getModelInvocableCommandsForListing} 经 {@code mcpServerService.getMcpSkillCommands()}
     * （listing 过滤视图，commands.ts:547-559）按需合并。未注入时上述方法退化为纯本地语义
     * （MCP 源跳过），POJO 测试兼容。
     */
    public void setMcpServerService(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    /**
     * P1-2: 注入动态技能管理器（POJO 兼容 · setter）· 对齐 CC getCommands 叠加 getDynamicSkills()
     * （commands.ts:476-517）+ skillsLoaded.emit → clearCommandMemoizationCaches（loadSkillsDir.ts:974/:1054）。
     *
     * <p>同时把 manager 注入 {@link SkillsLoader}（条件分离用，loadSkillsDir.ts:771-790），并注册
     * onChange 回调 → {@link #refresh()}（对齐 CC onDynamicSkillsLoaded → clearCommandMemoizationCaches；
     * 回调方式避免 DynamicSkillsManager → SkillRegistry → DynamicSkillsManager 循环依赖）。
     *
     * <p>未注入时 {@link #getAllCommands()} 行为不变（POJO 测试兼容）。
     */
    public void setDynamicSkillsManager(DynamicSkillsManager dynamicSkillsManager) {
        this.dynamicSkillsManager = dynamicSkillsManager;
        if (dynamicSkillsManager != null) {
            loader.setDynamicSkillsManager(dynamicSkillsManager);
            // 动态技能变更 → refreshCommandsOnly()（对齐 CC onDynamicSkillsLoaded 回调
            // skillChangeDetector.ts:94-97 → clearCommandMemoizationCaches commands.ts:523-531）：
            // CC 注释「we use clearCommandMemoizationCaches (not clearCommandsCache) because
            // clearCommandsCache would call clearSkillCaches which wipes out the dynamic skills
            // we just loaded」——窄变体只清命令三层缓存 + skillIndexClearer，不动
            // MarkdownConfigLoader/plugin/conditionalState（避免清掉刚加载的动态技能与条件激活态）。
            // △-6 多监听，忽略 unsubscribe 返值。
            dynamicSkillsManager.onDynamicSkillsLoaded(this::refreshCommandsOnly);
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 DynamicSkillsManager + onChange→refreshCommandsOnly() 已注册 (CC skillChangeDetector.ts:94-97 窄变体)");
            }
        }
    }

    /**
     * 方案1: 注入 DB enabled 主控源（POJO 兼容 · setter · null 安全）· CommandMapper 是
     * MyBatis-Flex mapper（由 MyBatis 扫描注册为 bean）。
     *
     * <p>未注入时 {@link #getAllCommands()} 行为不变（POJO 测试兼容，DB 覆盖跳过）。
     */
    public void setCommandMapper(CommandMapper commandMapper) {
        this.commandMapper = commandMapper;
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 注入 CommandMapper (DB enabled 主控源激活, 方案1: toggle/update 写 DB → loadAllCommands 覆盖文件默认 enabled)");
        }
    }

    /**
     * P2-12: 注入 plugin-only policy settings 读取器（setter · null 空安全转发）· 对齐 CC
     * isRestrictedToPluginOnly('skills')（loadSkillsDir.ts:650 + pluginOnlyPolicy.ts:19-27）。
     *
     * <p>内部空安全转发双目标：
     * <ul>
     *   <li>{@link SkillsLoader#setSettingsSupplier} — 用户技能根（getSkillDirCommands :650 门控落点）</li>
     *   <li>{@link DynamicSkillsManager#setSettingsSupplier} — addSkillDirectories :925-927 门控
     *       （manager 复用同一供应商，避免两处各自注入产生两套 settings 源）</li>
     * </ul>
     *
     * <p>转发模式对齐 {@link #setDynamicSkillsManager}（:128-139）。未注入时 loader/manager 均默认
     * {@code Map::of} = 不锁定（生产/测试行为不变）。
     */
    public void setSettingsSupplier(Supplier<Map<String, Object>> settingsSupplier) {
        if (settingsSupplier != null) {
            loader.setSettingsSupplier(settingsSupplier);
            if (dynamicSkillsManager != null) {
                dynamicSkillsManager.setSettingsSupplier(settingsSupplier);
            }
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 settingsSupplier (skillsLocked 门控激活: loader + dynamicSkillsManager 同源)");
            }
        }
    }

    /**
     * P2-2: 注入 user（userSettings）技能加载开关（转发 SkillsLoader）· 等价 CC
     * {@code isSettingSourceEnabled('userSettings')}（settings/constants.ts:174-177）。
     * 组合根配置（Java 无 CLI --settings，concern #2 补开关）。null → 忽略。
     */
    public void setUserSkillsEnabledSupplier(Supplier<Boolean> userSkillsEnabledSupplier) {
        if (userSkillsEnabledSupplier != null) {
            loader.setUserSkillsEnabledSupplier(userSkillsEnabledSupplier);
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 userSkillsEnabledSupplier (user 源加载开关激活, P2-2)");
            }
        }
    }

    /**
     * P2-2: 注入 project（projectSettings）技能加载开关（转发 SkillsLoader）· 等价 CC
     * {@code isSettingSourceEnabled('projectSettings')}（settings/constants.ts:174-177）。
     * 组合根配置（Java 无 CLI --settings，concern #2 补开关）。null → 忽略。
     */
    public void setProjectSkillsEnabledSupplier(Supplier<Boolean> projectSkillsEnabledSupplier) {
        if (projectSkillsEnabledSupplier != null) {
            loader.setProjectSkillsEnabledSupplier(projectSkillsEnabledSupplier);
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 projectSkillsEnabledSupplier (project 源加载开关激活, P2-2)");
            }
        }
    }

    /**
     * P1-15: 注入技能使用追踪（POJO 兼容 · setter）· 对齐 CC recordSkillUsage/getSkillUsageScore
     * （utils/suggestions/skillUsageTracking.ts）。由 SkillUsageTracking @Component 经 setter 注入。
     *
     * <p>未注入时 {@link #getSkillUsageScore} 返回 0，行为不变（POJO 测试兼容）。
     */
    public void setSkillUsageTracking(SkillUsageTracking skillUsageTracking) {
        this.skillUsageTracking = skillUsageTracking;
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 注入 SkillUsageTracking (getSkillUsageScore 读侧 API 激活)");
        }
    }

    /**
     * P3-5: 注入 skill-search 索引清除回调 · CC 原形 {@code clearSkillIndexCache?.()}
     * （commands.ts:531 clearCommandMemoizationCaches 内）。由 ToolRegistrationConfig.skillRegistry()
     * 组合根注入 {@code SkillDiscoveryPrefetch::clearSkillIndexCache}（POJO 兼容，null → 默认 no-op）。
     *
     * @param clearer 使 skill-search 索引失效的回调；null 视为 no-op（未接线不抛）
     */
    public void setSkillIndexClearer(Runnable clearer) {
        this.skillIndexClearer = clearer != null ? clearer : skillIndexClearer;
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] 注入 skill 索引清除器（clearSkillIndexCache 挂钩）");
        }
    }

    /**
     * P3-3: 注入认证/供应商状态（POJO 兼容 · setter）· 对齐 CC meetsAvailabilityRequirement
     * 的 auth 数据源（isClaudeAISubscriber auth.ts:1564 / isUsing3PServices auth.ts:1732 /
     * isFirstPartyAnthropicBaseUrl providers.ts:25）。
     *
     * <p>生产接线数据源待主 agent 拍板（concern DEC-8）：web 无 claude-ai 登录则 subscriber 恒 false；
     * 建议 env 驱动镜像 CC（CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY + ANTHROPIC_BASE_URL），或复用
     * 既有 OAuth 订阅态。未注入默认 subscriber=false / using3P=false / firstParty=true → 现有命令
     * availability=null universal 直通，行为不变（POJO 测试兼容）。
     *
     * @param availabilityAuthState 认证状态三元组；null → 保持默认（沿用
     *                              setDynamicSkillsManager/setSettingsSupplier null-guard 模式）
     */
    public void setAvailabilityAuthState(AvailabilityAuthState availabilityAuthState) {
        if (availabilityAuthState != null) {
            this.availabilityAuthState = availabilityAuthState;
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 AvailabilityAuthState (availability 门控激活: subscriber={}, using3P={}, firstParty={})",
                    availabilityAuthState.claudeAiSubscriber().getAsBoolean(),
                    availabilityAuthState.usingThirdPartyServices().getAsBoolean(),
                    availabilityAuthState.firstPartyAnthropicBaseUrl().getAsBoolean());
            }
        }
    }

    /**
     * P3-3: 认证状态三元组 · 对齐 CC commands.ts:417-443 meetsAvailabilityRequirement 依赖的
     * 三个判定函数（isClaudeAISubscriber auth.ts:1564 / isUsing3PServices auth.ts:1732 /
     * isFirstPartyAnthropicBaseUrl utils/model/providers.ts:25）。
     *
     * @param claudeAiSubscriber CC original: isClaudeAISubscriber()（auth.ts:1564-1570，
     *                           isAnthropicAuthEnabled() && shouldUseClaudeAIAuth(scopes)）
     * @param usingThirdPartyServices CC original: isUsing3PServices()（auth.ts:1732-1738，
     *                           env CLAUDE_CODE_USE_BEDROCK||VERTEX||FOUNDRY）
     * @param firstPartyAnthropicBaseUrl CC original: isFirstPartyAnthropicBaseUrl()（providers.ts:25-40，
     *                           ANTHROPIC_BASE_URL 未设或 api.anthropic.com(+api-staging for ant)）
     */
    public record AvailabilityAuthState(
        BooleanSupplier claudeAiSubscriber,
        BooleanSupplier usingThirdPartyServices,
        BooleanSupplier firstPartyAnthropicBaseUrl) {
    }

    /**
     * P2-20: 注入 cwd 供应（POJO 兼容 · setter）· 激活文件系统源五源加载。
     *
     * <p>非 null → {@link #loadAllCommands()} 文件系统块改走 {@link SkillsLoader#getSkillDirCommands(cwd)}
     * （对齐 CC getCommands → getSkillDirCommands(cwd)，commands.ts:361-367：managed/user/project-up-to-home/
     * additional/legacy 五源 + per-source 门控）；null → 单目录回退 {@link SkillsLoader#loadFromDirectory}
     * （POJO/测试，SkillRegistry(String) 直构不变）。
     */
    public void setCwdSupplier(Supplier<String> cwdSupplier) {
        if (cwdSupplier != null) {
            this.cwdSupplier = cwdSupplier;
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 cwdSupplier → 文件系统源走五源 getSkillDirCommands (CC commands.ts:361-367)");
            }
        }
    }

    /**
     * P2-20: 注入附加目录供应（POJO 兼容 · 转发 SkillsLoader）· 等价 CC
     * getAdditionalDirectoriesForClaudeMd（state.ts:206-207，--add-dir）。
     *
     * <p>bare 模式（loadSkillsDir.ts:659）与 additional 源（:699-708）依赖该列表；生产注入
     * {@link ClaudePaths#getAdditionalDirectoriesFromEnv}，POJO 默认空。
     */
    public void setAdditionalDirectoriesSupplier(Supplier<List<String>> additionalDirectoriesSupplier) {
        if (additionalDirectoriesSupplier != null) {
            loader.setAdditionalDirectoriesSupplier(additionalDirectoriesSupplier);
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 注入 additionalDirectoriesSupplier → SkillsLoader additional 源激活");
            }
        }
    }

    /**
    /**
     * IMP-E: 统一缓存键入口 · 对齐 CC memoize-by-cwd（commands.ts:449 {@code loadAllCommands} /
     * commands.ts:563 {@code getSkillToolCommands} / commands.ts:586 {@code getSlashCommandToolSkills}
     * —— 三层 memoize 均按 cwd 建槽）。
     *
     * <p>键 = projectRoot + skillsRoot 复合（M-09 不可逆污染消除）：
     * <ul>
     *   <li><b>projectRoot 分量</b>：{@link #cwdSupplier} 注入（生产 =
     *       {@code AutoMemPaths::currentSessionProjectRoot}，ToolRegistrationConfig:393，per-session
     *       ThreadLocal）时取其值 —— 同一 JVM 多会话并发各自独立缓存槽；首触发线程为工具线程
     *       （IMP-C 捕获-回放传播已在任务体开头注入会话值）时仍解析到会话绑定 P，不会把会话 A 的
     *       cwd 数据冻结进全 JVM 共享槽（M-09）。</li>
     *   <li><b>cwdSupplier 未注入（POJO/测试直构）</b>：静态回落
     *       {@link AutoMemPaths#currentSessionProjectRoot()}（CLAUDE_PROJECT_DIR env ?? config home，
     *       确定性非 null，绝不读 JVM user.dir）—— 与单目录回退 {@link SkillsLoader#loadFromDirectory}
     *       同源，实例内键稳定。</li>
     *   <li><b>skillsRoot 分量</b>：构造器注入的固定根，与 projectRoot 组合避免两维度输入串槽。</li>
     * </ul>
     *
     * <p>与加载 cwd 同源（{@link #loadAllCommands()} 亦取 {@link #cwdSupplier}），杜绝
     * "键用 cwd A、加载用 cwd B" 双轨（SkillsLoader 内部 cwdSupplier 仅为 cwd 入参为空时的回退，
     * 本方法恒传非空 → 不生效）。
     *
     * @return 当前缓存键（同一实例内同一 (projectRoot, skillsRoot) 组合稳定）
     */
    private String currentCacheKey() {
        String projectRoot = cwdSupplier != null
            ? cwdSupplier.get()
            : AutoMemPaths.currentSessionProjectRoot();
        return projectRoot + "|" + skillsRoot;
    }

    /**
     * [P2-18] 读取当前附加目录列表（公开 API · 转发 SkillsLoader）· 等价 CC
     * getAdditionalDirectoriesForClaudeMd（state.ts:206-207，--add-dir）。
     *
     * <p>附加目录技能以 {@code projectSettings} 源加载（loadSkillsDir.ts:699-708），
     * {@link SkillImprovementHook#findProjectSkill} 需把该子集纳入项目级判定（WF6-02 △-1
     * additionalDir 子集排除修复）——本 getter 提供消费入口。
     *
     * @return 当前附加目录列表（未注入供应 → 默认空 List）
     */
    public List<String> getAdditionalDirectories() {
        return loader.getAdditionalDirectories();
    }

    /**
     * 获取所有已注册的命令（合并所有来源）· 对齐 CC getCommands() → loadAllCommands()
     *
     * <p>去重规则：同名以首次出现为准（bundled > builtin plugin > filesystem）。
     *
     * <p>P1-1 memoize（raw）：按 (projectRoot, skillsRoot) 复合键缓存（{@link #currentCacheKey()}，
     * 对齐 CC {@code loadAllCommands = memoize(cwd)} commands.ts:449），磁盘变更仅在 {@link #refresh()}
     * 后可见（MCP 分离 thread-in，不落缓存）。
     *
     * <p>P2-6 isCommandEnabled 过滤：公开边界每调用新鲜求值（对齐 CC commands.ts:478 注释
     * 「The expensive loading is memoized, but availability and isEnabled checks run fresh every call」+
     * commands.ts:484 {@code meetsAvailabilityRequirement(_) && isCommandEnabled(_)}）。
     * <ul>
     *   <li><b>raw 仍 memoize</b>：{@link #loadAllCommands()} 结果缓存于 {@link #allCommandsCache}，
     *       过滤结果不缓存（不把 gate 冻结进缓存）。</li>
     *   <li><b>过滤语义</b>：{@code Command.isCommandEnabled()} = isEnabled supplier（惰性）非 null 求值，
     *       否则回退 enabled 字段（CC {@code isEnabled?.() ?? true}，types/command.ts:214-215）。
     *       禁用命令（enabled=false / supplier false）→ 不出现 → findCommand/getModelInvocableCommands/
     *       getSlashCommandToolSkills 经本方法继承该过滤，CC SkillTool.getAllCommands→getCommands 一致。</li>
     *   <li>⚠ 实例语义变化：重复调用不再返回同一 List 实例（P2-6 前 P1-1 返回缓存实例）；"不重扫"由
     *       raw memoize 保证（磁盘变更 refresh() 前不可见），与 CC 一致。</li>
     * </ul>
     */
    public List<Command> getAllCommands() {
        // CC original: loadAllCommands = memoize(async (cwd) => {...})（commands.ts:449）
        // Java 等价：by-(projectRoot, skillsRoot) 复合键缓存（currentCacheKey()，IMP-E）。computeIfAbsent
        // 单键单飞（ConcurrentHashMap 无同 map 递归：loadAllCommands 不回调 getAllCommands）。
        List<Command> raw = allCommandsCache.computeIfAbsent(currentCacheKey(), k -> loadAllCommands());
        // P3-3: availability 先于 isEnabled 求值（CC commands.ts:484 filter
        // `meetsAvailabilityRequirement(_) && isCommandEnabled(_)` + :411-416 注释「This runs before
        // isEnabled()… provider-gated commands are hidden regardless of feature-flag state」）。
        // raw 仍 memoize，availability/isEnabled 过滤每调用新鲜求值（CC :471-475「availability and
        // isEnabled checks run fresh every call」）。gate 单点落本方法 → getModelInvocableCommands /
        // getSlashCommandToolSkills / findCommand 经本方法继承（对齐 CC getSkillToolCommands/:586 内部
        // 调用 getCommands(cwd) :565/:589）；MCP thread-in（findCommandIncludingMcp /
        // getModelInvocableCommandsForListing）不过 gate（CC getMcpSkillCommands commands.ts:547-559
        // 无 availability 检查，MCP live outside getCommands :541-546）。
        List<Command> filtered = raw.stream()
            .filter(c -> meetsAvailabilityRequirement(c) && c.isCommandEnabled())
            .toList();
        if (log.isDebugEnabled()) {
            long gatedByAvailability = raw.stream().filter(c -> !meetsAvailabilityRequirement(c)).count();
            log.debug("[SkillRegistry] getAllCommands availability+enabled 过滤: raw {} 个 → 通过 {} 个 (availability 门控排除 {}，enabled 排除 {}；CC commands.ts:484，过滤新鲜求值)",
                raw.size(), filtered.size(), gatedByAvailability,
                raw.size() - gatedByAvailability - filtered.size());
        }
        return filtered;
    }

    /**
     * P3-3: availability 门控判定 · 对齐 CC commands.ts:417-443 meetsAvailabilityRequirement(cmd)
     * （逐字翻译，switch → 显式 if-else 链）。
     *
     * <p>语义（commands.ts:411-416 注释）：
     * <ul>
     *   <li>availability null/undefined → universal，直接放行（:418 {@code if (!cmd.availability) return true}）</li>
     *   <li>非 null → 逐个检查声明类型，命中任一即放行：'claude-ai' 要求 isClaudeAISubscriber（:422）；
     *       'console' 要求 !subscriber && !using3P && firstPartyBaseUrl（:428-433，
     *       commands.ts:425 注释「Console API key user = direct 1P API customer (not 3P, not claude.ai)」）</li>
     *   <li>default（未知值）→ continue，末尾 return false（:434-442）</li>
     * </ul>
     *
     * <p>不 memoize：认证状态会话内可变（如 /login），每调用新鲜求值（commands.ts:471-475 注释
     * 「availability and isEnabled checks run fresh every call」）。
     *
     * @param cmd 待判定命令
     * @return 满足 availability 声明 → true；否则 false
     */
    private boolean meetsAvailabilityRequirement(Command cmd) {
        // CC commands.ts:418 `if (!cmd.availability) return true`（null/undefined → universal）
        if (cmd.getAvailability() == null) {
            return true;
        }
        for (CommandAvailability a : cmd.getAvailability()) {
            if (a == CommandAvailability.CLAUDE_AI) {
                // CC commands.ts:421-423 case 'claude-ai': if (isClaudeAISubscriber()) return true
                if (availabilityAuthState.claudeAiSubscriber().getAsBoolean()) {
                    return true;
                }
            } else if (a == CommandAvailability.CONSOLE) {
                // CC commands.ts:427-433 case 'console': if (!isClaudeAISubscriber() &&
                //   !isUsing3PServices() && isFirstPartyAnthropicBaseUrl()) return true
                if (!availabilityAuthState.claudeAiSubscriber().getAsBoolean()
                    && !availabilityAuthState.usingThirdPartyServices().getAsBoolean()
                    && availabilityAuthState.firstPartyAnthropicBaseUrl().getAsBoolean()) {
                    return true;
                }
            }
            // CC default 分支（const _exhaustive: never = a; break）→ 其他未知值不命中，continue
        }
        // CC commands.ts:442 return false
        return false;
    }

    /**
     * DEC-8: 前端环境声明过滤 · CC original: meetsAvailabilityRequirement（commands.ts:417-443）
     * 的 web 扩展镜像（纯函数，无状态变更，POJO 可测）。
     *
     * <p><b>双门控共存（非双实现漂移，concern DEC-8）</b>：
     * <ul>
     *   <li><b>内部链</b> {@link #getAllCommands()} 认证门控（{@link #meetsAvailabilityRequirement}
     *       :377-401）——信号源 = {@link AvailabilityAuthState} auth 态（isClaudeAISubscriber /
     *       isUsing3PServices / isFirstPartyAnthropicBaseUrl），agent 循环<b>无请求上下文</b>，
     *       不可注入请求头，维持 CC commands.ts:484 对齐。</li>
     *   <li><b>本 REST 链</b> client-env 门控——信号源 = {@code X-Client-Env} 请求头（react|mobile），
     *       controller（CommandController.list）注入。前端环境声明是 web 扩展入口（CC 无
     *       client-env 概念），面向 REST 消费路径。</li>
     * </ul>
     * 两者面向不同消费路径、信号源不同，勿合并。
     *
     * <p>语义镜像 CC meetsAvailabilityRequirement（commands.ts:417-443）：
     * <ul>
     *   <li>clientEnv == null → 原样返回（无环境声明默认放行，web 兼容：前端不传 X-Client-Env 头）</li>
     *   <li>availability == null → universal 放行（:418 {@code if (!cmd.availability) return true}）</li>
     *   <li>声明中任一环境被 {@link ClientEnv#satisfies} 命中 → 放行（:419-441）</li>
     *   <li>否则排除（:442 {@code return false}）</li>
     * </ul>
     *
     * <p>不 memoize：请求头会话内可变，每调用新鲜求值（CC :471-475 注释「checks run fresh every call」）。
     *
     * @param commands 待过滤命令列表（领域层，如 {@code CommandService.listAllDomain} 输出）
     * @param clientEnv 前端声明环境；null → 原样返回（web 兼容默认放行）
     * @return 按声明环境过滤后的命令列表
     */
    public List<Command> filterByClientEnv(List<Command> commands, ClientEnv clientEnv) {
        if (clientEnv == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] filterByClientEnv: 无环境声明 (X-Client-Env 头缺失/未知) → 原样放行 {} 个命令 (DEC-8 web 兼容)",
                    commands.size());
            }
            return commands;
        }
        List<Command> filtered = commands.stream()
            .filter(c -> meetsAvailabilityForClientEnv(c, clientEnv))
            .toList();
        if (log.isDebugEnabled()) {
            long excluded = commands.size() - filtered.size();
            log.debug("[SkillRegistry] filterByClientEnv: 环境={} 过滤前 {} 个 → 过滤后 {} 个 (排除 {}；DEC-8 client-env 门控，CC commands.ts:417-443)",
                clientEnv, commands.size(), filtered.size(), excluded);
        }
        return filtered;
    }

    /**
     * DEC-8: 单命令 client-env 可用性判定 · CC original: meetsAvailabilityRequirement
     * commands.ts:417-443（switch → 显式 if-else 链，环境命中替代 auth 态判定）。
     *
     * <p>availability null/undefined → universal（:418）；非 null → 逐个检查声明类型，任一被
     * {@link ClientEnv#satisfies} 命中即放行；否则末尾 return false（:442）。
     *
     * @param cmd 待判定命令
     * @param env 前端声明环境（非 null）
     * @return 满足环境声明 → true；否则 false
     */
    private boolean meetsAvailabilityForClientEnv(Command cmd, ClientEnv env) {
        // CC commands.ts:418 `if (!cmd.availability) return true`（null/undefined → universal）
        if (cmd.getAvailability() == null) {
            return true;
        }
        for (CommandAvailability a : cmd.getAvailability()) {
            // CC commands.ts:419-441 任一环境命中 → return true（Java 侧以 ClientEnv.satisfies
            //   替代 auth 态判定，DEC-8 web 扩展）
            if (env.satisfies(a)) {
                return true;
            }
        }
        // CC commands.ts:442 return false
        return false;
    }

    /**
     * 执行 5 源加载合并（bundled + builtinPlugin + FS + dynamic + COMMANDS；MCP 分离，每源独立
     * try-catch，任一源异常仅跳过该源）。
     *
     * <p>P1-1 抽取自原 getAllCommands() 合并体 · 对齐 CC loadAllCommands（commands.ts:449-469）
     * + getSkills 每源独立 catch（commands.ts:360-373）。
     *
     * <p>P2-9 分离：CC loadAllCommands（commands.ts:449-469）源数组不含 MCP（bundled / builtinPlugin /
     * skillDir / workflow / plugin + COMMANDS），MCP 技能经 {@link #findCommandIncludingMcp} /
     * {@link #getModelInvocableCommandsForListing} thread-in —— Java 移除 MCP 合并块后与之精确对齐。
     *
     * <p>DEC-9：追加第 5 源 {@link BuiltInCommands#getAll()}（COMMANDS 内置命令）· 对齐 CC
     * commands.ts:467 {@code ...COMMANDS()} 合并序最后追加（CC 第 7 源且为 LAST，Java 动态技能之后）。
     * source=BUILTIN 命令经 getModelInvocableCommands:628 / getSlashCommandToolSkills:780 既有
     * {@code source != BUILTIN} 过滤排除（对齐 CC commands.ts:570/:593 source!=='builtin'），
     * 仅进入 getAllCommands/findCommand 消费面。
     *
     * <p>方案1（用户拍板）: DB enabled 主控 —— 五源合并后若注入 {@link #setCommandMapper}，
     * 一次性 selectAll() 建 name→enabled 映射，仅覆盖同名命令 enabled 字段（其余字段文件权威）。
     * bundled skill 的 isEnabled supplier（运行时 gate，如 GB/autoMemory）保持优先（覆盖对
     * supplier skill 无效，符合 CC isEnabled 优先语义 types/command.ts:214-215）。DB 覆盖读
     * 发生在本方法执行期（memoize 缓存内），toggle/update 后由 CommandService 调
     * {@link #refreshCommandsOnly()} 清缓存 → 下次重载读 DB 生效（方案2）。
     *
     * @return 合并后不可变命令列表（纯本地/bundled/builtinPlugin/dynamic/COMMANDS 五源 + DB enabled 主控覆盖，不含 MCP）
     */
    private List<Command> loadAllCommands() {
        Map<String, Command> byName = new LinkedHashMap<>();

        // 1. 捆绑技能（最高优先级）· 对齐 CC bundledSkills（commands.ts:374-375 同步源，外层防御兜底）
        try {
            for (Command c : BundledSkills.getAll()) {
                byName.putIfAbsent(c.getName(), c);
            }
        } catch (Exception e) {
            log.warn("[SkillRegistry] 捆绑技能源加载失败，跳过该源: {}", e.getMessage());
        }

        // 2. 内置插件技能 · 对齐 CC builtinPluginSkills（commands.ts:377）
        if (builtinPluginRegistry != null) {
            try {
                List<BuiltinPluginRegistry.Command> builtinCmds =
                    builtinPluginRegistry.getBuiltinPluginSkillCommands();
                for (BuiltinPluginRegistry.Command bc : builtinCmds) {
                    Command c = toCommand(bc);
                    byName.putIfAbsent(c.getName(), c);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] 注入 {} 个内置插件技能", builtinCmds.size());
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] 内置插件技能源加载失败，跳过该源: {}", e.getMessage());
            }
        }

        // 3. 文件系统技能 · 对齐 CC skillDirCommands（commands.ts:361-367 每源 catch → 该源返回空）
        //   P2-20：cwdSupplier 注入（生产）→ 五源 getSkillDirCommands(cwd)（managed/user/project/additional/legacy）；
        //   null（POJO/测试）→ 单目录 loadFromDirectory(skillsRoot) 回退。
        try {
            List<Command> fsSkills = cwdSupplier != null
                ? loader.getSkillDirCommands(cwdSupplier.get())
                : loader.loadFromDirectory(skillsRoot);
            for (Command c : fsSkills) {
                byName.putIfAbsent(c.getName(), c);
            }
        } catch (Exception e) {
            log.warn("[SkillRegistry] 文件系统技能源加载失败，跳过该源: {}", e.getMessage());
        }

        // 3a. workflow 命令 · 对齐 CC loadAllCommands 合并序 workflowCommands（commands.ts:457
        //    getWorkflowCommands ? getWorkflowCommands(cwd) : Promise.resolve([])，第 4 源）
        //    P1-3：可注入 workflowCommandProvider（null = WORKFLOW_SCRIPTS feature 关 → 空，对齐 CC :457
        //    feature 关时 Promise.resolve([])）。每源独立 try-catch（CC :360-373 每源 catch 语义）。
        if (workflowCommandProvider != null) {
            try {
                List<Command> workflowCommands = workflowCommandProvider.get();
                int added = 0;
                if (workflowCommands != null) {
                    for (Command c : workflowCommands) {
                        if (c != null && c.getName() != null && !c.getName().isBlank()) {
                            if (byName.putIfAbsent(c.getName(), c) == null) {
                                added++;
                            }
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] 追加 {} 个 workflow 命令 (新增 {}；CC commands.ts:464 ...workflowCommands)",
                        workflowCommands != null ? workflowCommands.size() : 0, added);
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] workflow 命令源加载失败，跳过该源: {}", e.getMessage());
            }
        }

        // 3b. plugin 命令 · 对齐 CC loadAllCommands 合并序 pluginCommands（commands.ts:465，
        //    bundled→builtinPlugin→skillDir→workflow→pluginCommands→pluginSkills→COMMANDS()）
        //    MPL6：PluginLoader 注入时经 loadAllEnabledCommands 扫 commandsPath/commandsPaths
        //    （source='plugin'，命令名 plugin:ns:name）。
        if (pluginLoader != null) {
            try {
                List<Command> pluginCommands = pluginLoader.loadAllEnabledCommands();
                int added = 0;
                for (Command c : pluginCommands) {
                    if (c != null && c.getName() != null && !c.getName().isBlank()) {
                        if (byName.putIfAbsent(c.getName(), c) == null) {
                            added++;
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] 追加 {} 个 plugin 命令 (新增 {}；CC commands.ts:465 pluginCommands)",
                        pluginCommands.size(), added);
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] plugin 命令源加载失败，跳过该源: {}", e.getMessage());
            }

            // 3c. plugin 技能 · 对齐 CC loadAllCommands 合并序 pluginSkills（commands.ts:466，
            //    在 pluginCommands 之后、dynamic 之前）
            try {
                List<Command> pluginSkills = pluginLoader.loadAllEnabledSkills();
                int added = 0;
                for (Command c : pluginSkills) {
                    if (c != null && c.getName() != null && !c.getName().isBlank()) {
                        if (byName.putIfAbsent(c.getName(), c) == null) {
                            added++;
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] 追加 {} 个 plugin 技能 (新增 {}；CC commands.ts:466 pluginSkills)",
                        pluginSkills.size(), added);
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] plugin 技能源加载失败，跳过该源: {}", e.getMessage());
            }
        }

        // 4. 动态技能 · 对齐 CC getCommands 叠加 getDynamicSkills()（commands.ts:476-517）
        //   - name 已存在 → 跳过（CC :492-498 baseCommandNames 去重）
        //   - 插入位: CC 插在 builtin 之前（commands.ts:504-516）；Java byName 首位即 bundled
        //     （内置命令），name 去重后新增技能追加尾部，功能等价（findCommand/getModelInvocable
        //     均按 name 搜索，位置不影响正确性）
        if (dynamicSkillsManager != null) {
            try {
                List<Command> dynamicSkills = dynamicSkillsManager.getDynamicSkills();
                int added = 0;
                for (Command c : dynamicSkills) {
                    if (c != null && c.getName() != null && !c.getName().isBlank()) {
                        if (byName.putIfAbsent(c.getName(), c) == null) {
                            added++;
                        }
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] 叠加 {} 个动态技能 (新增 {}，name 已存在跳过)",
                        dynamicSkills.size(), added);
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] 动态技能源加载失败，跳过该源: {}", e.getMessage());
            }
        }

        // 5. COMMANDS 内置命令 · 对齐 CC loadAllCommands 合并序最后追加 COMMANDS()（commands.ts:467，
        //    bundledSkills→builtinPluginSkills→skillDirCommands→workflowCommands→pluginCommands→
        //    pluginSkills→COMMANDS()，COMMANDS 为第 7 源且为 LAST）
        //    DEC-9：Java 五源（bundled→builtinPlugin→FS→dynamic→COMMANDS），内置命令恒追加尾部
        //    （动态技能在 COMMANDS 之前，与 CC 动态 skills 内置命令之后一致）。source=BUILTIN →
        //    getModelInvocableCommands:628 / getSlashCommandToolSkills:780 既有 `source != BUILTIN`
        //    过滤自动排除（对齐 CC commands.ts:570/:593 source!=='builtin'）。
        try {
            List<Command> builtinCommands = BuiltInCommands.getAll();
            int added = 0;
            for (Command c : builtinCommands) {
                if (c != null && c.getName() != null && !c.getName().isBlank()) {
                    if (byName.putIfAbsent(c.getName(), c) == null) {
                        added++;
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] 追加 {} 个 COMMANDS 内置命令 (新增 {}，name 已存在跳过；CC commands.ts:467 ...COMMANDS())",
                    builtinCommands.size(), added);
            }
        } catch (Exception e) {
            log.warn("[SkillRegistry] COMMANDS 内置命令源加载失败，跳过该源: {}", e.getMessage());
        }

        // 6. DB enabled 主控覆盖 · 方案1（用户拍板）：用户 skill 启用/禁用写 DB（前端
        //    PATCH /api/command/{id}/toggle / update enabled → CommandService 写
        //    commandMapper.update），此处一次性 selectAll() 建 name→enabled 映射，仅覆盖
        //    enabled 字段（其余字段文件权威）。—— 列表合并以本 registry 为权威
        //    （getAllCommands / findCommandIncludingMcp / getModelInvocableCommands /
        //    getSlashCommandToolSkills 均经 getAllCommands 继承），覆盖后前端禁用/启用真实生效。
        //    enabled 可空（DB 未落该行）→ 跳过该名（文件默认 enabled 保留）。
        //    bundled skill 的 isEnabled supplier 求值优先（Command.isCommandEnabled =
        //    supplier 非 null 新鲜求值，覆盖 enabled 字段对 supplier skill 无效 —— 符合 CC
        //    isEnabled 优先语义 types/command.ts:214-215）。DB 覆盖读发生在 memoize 缓存内，
        //    toggle/update 后由 CommandService 调 refreshCommandsOnly()（方案2）清缓存生效。
        if (commandMapper != null) {
            try {
                // [integration-gap 修复] DB 主控同时拷贝 id + enabled：SkillsLoader 加载文件 skill
                //   从不 setId（Command.id=null）→ 列表合并 SkillRegistry 权威 → DTO id=null →
                //   前端 PATCH /{id}/toggle 传 null → selectOneById(null) NotFound → DB 写不进、
                //   DB 主控覆盖永不参与。此处从 DB 行补 id（同名命中时），前端 toggle 才有真实 id
                //   走通 DB 写 → 方案1+2 全链路生效。仅拷贝 id/enabled，其余字段文件权威。
                Map<String, CommandRecord> dbRowsByName = new HashMap<>();
                for (CommandRecord rec : commandMapper.selectAll()) {
                    if (rec != null && rec.getName() != null && rec.getEnabled() != null) {
                        dbRowsByName.put(rec.getName(), rec);
                    }
                }
                int overridden = 0;
                int idSynced = 0;
                for (Map.Entry<String, Command> e : byName.entrySet()) {
                    CommandRecord rec = dbRowsByName.get(e.getKey());
                    if (rec != null) {
                        e.getValue().setEnabled(rec.getEnabled() != 0);
                        if (e.getValue().getId() == null && rec.getId() != null) {
                            e.getValue().setId(rec.getId());
                            idSynced++;
                        }
                        overridden++;
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] DB enabled 主控覆盖: DB {} 条, 覆盖 {} 个命令, 补 id {} 个 (方案1 前端 toggle → 加载覆盖 enabled + 列表 id 非 null)",
                        dbRowsByName.size(), overridden, idSynced);
                }
            } catch (Exception e) {
                log.warn("[SkillRegistry] DB enabled 主控源读取失败，跳过覆盖 (方案1 降级为文件默认 enabled): {}", e.getMessage());
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] loadAllCommands 汇总 {} 个命令 (五源+DB 主控，缓存键={})",
                byName.size(), currentCacheKey());
        }
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }

    /**
     * BuiltinPluginRegistry.Command → com.nexusai.model.command.Command.
     *
     * <p>BuiltinPluginRegistry 内部使用极简 record, 需补齐 model.Command 默认字段.
     *
     * <p>P2-21 source/loadedFrom 落位（对齐 CC builtinPlugins.ts:149-150）：
     * {@code source: 'bundled', loadedFrom: 'bundled'} —— CC 注释（:145-148）明确 'bundled' 而非
     * 'builtin'（'builtin' 在 Command.source 中指硬编码斜杠命令 /help /clear），用 'bundled' 使这些技能
     * 留在 SkillTool listing + prompt 截断豁免（prompt.ts:97 {@code source==='bundled'}）。旧 Java
     * 误标 PLUGIN（△），改后随 bundled 特权（catalog 不截断）正确对齐 CC。
     */
    private static Command toCommand(BuiltinPluginRegistry.Command bc) {
        Command c = new Command();
        c.setName(bc.name());
        c.setDescription(bc.description());
        // P2-15（DRF-PC-4）：CC skillDefinitionToCommand 字段契约补齐——
        //   hasUserSpecifiedDescription:true（builtinPlugins.ts:137）
        c.setHasUserSpecifiedDescription(bc.hasUserSpecifiedDescription());
        c.setAllowedTools(bc.allowedTools());
        c.setArgumentHint(bc.argumentHint());
        c.setWhenToUse(bc.whenToUse());
        //   model（:144）/ hooks（:157）/ context（:158）/ agent（:159）
        if (bc.model() != null) {
            c.setModel(bc.model());
        }
        if (bc.hooks() != null) {
            c.setHooks(bc.hooks());
        }
        if (bc.context() != null) {
            c.setContext(bc.context());
        }
        if (bc.agent() != null) {
            c.setAgent(bc.agent());
        }
        c.setDisableModelInvocation(bc.disableModelInvocation());
        c.setUserInvocable(bc.userInvocable());
        //   isEnabled ?? (() => true)（:160）→ 惰性 supplier（isCommandEnabled() 新鲜求值）
        if (bc.isEnabled() != null) {
            c.setIsEnabled(bc.isEnabled());
        }
        //   isHidden: !(userInvocable ?? true)（:161）/ progressMessage: 'running'（:162）
        c.setIsHidden(bc.isHidden());
        c.setProgressMessage(bc.progressMessage());
        c.setSource(CommandSource.BUNDLED);           // CC builtinPlugins.ts:149 source: 'bundled'
        c.setLoadedFrom(CommandLoadedFrom.BUNDLED);   // CC builtinPlugins.ts:150 loadedFrom: 'bundled'
        c.setBuiltin(Boolean.TRUE);
        if (bc.getPrompt() != null) {
            c.setContent(bc.getPrompt().get());
        }
        return c;
    }

    /**
     * 获取模型可调用的命令（纯本地/bundled/builtinPlugin/dynamic/COMMANDS 五源）· 对齐 CC getSkillToolCommands()
     *
     * <p>过滤五连（commands.ts:566-579）：type='prompt'（{@code cmd.type === 'prompt'} :568）
     * && !disableModelInvocation（:569）&& source != builtin（:570）
     * && (loadedFrom∈{bundled,skills,commands_DEPRECATED} || hasUserSpecifiedDescription || whenToUse)
     * （:574-578）。P2-21 起 loadedFrom 用独立字段 {@link CommandLoadedFrom} 判别（CC command.ts:32
     * source 与 :191-197 loadedFrom 是两独立字段）——旧 Java source∈{BUNDLED,USER} 代理 loadedFrom
     * 有两个行为 bug：① managed 技能 source=BUNDLED 被误放行（SkillsLoader 已修正为 source=USER +
     * loadedFrom=SKILLS，deleteList P2-21 第 3 项）；② commands_DEPRECATED 折叠进 USER 被误放行。
     * Plugin/MCP 命令必须 hasUserSpecifiedDescription=true 或 whenToUse 非空才进清单
     * （commands.ts:571-573 注释「Plugin/MCP commands still require an explicit description」）。
     *
     * <p>P2-9 过滤链精确对齐 CC getSkillToolCommands（commands.ts:563-581）：无 MCP 排除项 ——
     * MCP 命令 live outside getCommands（commands.ts:541-546），本过滤从数据源上就不含 MCP；
     * 需含 MCP 的 listing 视图走 {@link #getModelInvocableCommandsForListing()}（thread-in 合并，
     * 对齐 CC attachments.ts:2677-2682 {@code uniqBy([...localCommands, ...mcpSkills], 'name')}）。
     * loadedFrom∈{BUNDLED,SKILLS,COMMANDS_DEPRECATED} 在 allowlist 免显式描述自动放行（CC :574-576）。
     *
     * <p>P1-1 memoize：结果按 (projectRoot, skillsRoot) 复合键独立缓存（{@link #currentCacheKey()}，
     * 对齐 CC {@code getSkillToolCommands} 独立 memoize 层 commands.ts:563）；实现用 get-then-putIfAbsent
     * （非对同一 map 递归 computeIfAbsent，规避 ConcurrentHashMap 'Recursive update'）。
     */
    public List<Command> getModelInvocableCommands() {
        String key = currentCacheKey();
        List<Command> cached = modelInvocableCache.get(key);
        if (cached == null) {
            List<Command> all = getAllCommands();
            List<Command> computed = all.stream()
                .filter(c -> "prompt".equals(c.getType()))
                .filter(c -> !Boolean.TRUE.equals(c.getDisableModelInvocation()))
                .filter(c -> c.getSource() != CommandSource.BUILTIN)
                .filter(c -> c.getLoadedFrom() == CommandLoadedFrom.BUNDLED
                    || c.getLoadedFrom() == CommandLoadedFrom.SKILLS
                    || c.getLoadedFrom() == CommandLoadedFrom.COMMANDS_DEPRECATED
                    || Boolean.TRUE.equals(c.getHasUserSpecifiedDescription())
                    || (c.getWhenToUse() != null && !c.getWhenToUse().isBlank()))
                .toList();
            List<Command> raced = modelInvocableCache.putIfAbsent(key, computed);
            cached = raced != null ? raced : computed;
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] getModelInvocableCommands 过滤: 全部 {} 个 → 模型可调用 {} 个 (key={})",
                    all.size(), computed.size(), key);
            }
        }
        return cached;
    }

    /**
     * P2-9 + 拍板#2: 按名称查找命令（含 MCP thread-in）· CC original:
     * {@code SkillTool.getAllCommands(context)} SkillTool.ts:81-93 +
     * {@code findCommand(normalized, commands)} SkillTool.ts:399-402/:615-616。
     *
     * <p>MCP 技能 live outside getCommands（commands.ts:541-546），SkillTool 的 validateInput/
     * checkPermissions/call 三处搜索基座均为 getAllCommands(context) = {@code uniqBy([...localCommands,
     * ...mcpSkills], 'name')}（SkillTool.ts:86/:93）。Java 等价：本地 {@link #getAllCommands()} +
     * {@code mcpServerService.getMcpSkillCommandsForSearch()} 按 name 去重（local-first，同名本地胜）
     * 后复用三维匹配（name/userFacingName/aliases）。
     *
     * <p>S3 修正（R2B-DEC-9）：技能搜索基座用 {@link McpServerService#getMcpSkillCommandsForSearch()}
     * （对齐 CC SkillTool.getAllCommands SkillTool.ts:81-94，<b>无</b> !disableModelInvocation 预滤）——
     * disableModelInvocation MCP 技能保持可达，validateInput 命中后返回 errorCode 4
     * （SkillTool.ts:412-418）；旧实现用 {@code getMcpSkillCommands()}（listing 过滤，commands.ts:553-555）
     * 预滤 → 技能不可达 → errorCode 2「Unknown skill」（:406-407）错误码语义偏移。listing 视图
     * {@link #getModelInvocableCommandsForListing} 仍用过滤版（CC commands.ts:547-559）。
     *
     * <p>拍板#2（FIX-A2）：CC {@code fetchCommands} 产物（prompts，无 loadedFrom，client.ts:2054-2095）
     * 入 {@code AppState.mcp.commands} 可作 slash 命令；Java 搜索基座追加
     * {@link McpServerService#getMcpPromptCommandsForSearch()}（MCP prompt，无 loadedFrom），
     * 与技能搜索视图合并后统一三维匹配 —— findCommandIncludingMcp 同时命中 MCP skill
     * 与 MCP prompt（拍板#2：搜索基座含 MCP prompt）。
     *
     * <p>mcpServerService==null 或 MCP_SKILLS gate 关 → 退化为 {@link #findCommand} 纯本地语义
     * （prompt 池不受 gate 门控，CC fetchCommandsForClient 恒执行）。
     *
     * @param name 命令名（前导 '/' 自动剥除）
     * @return 命中命令；未命中返回 null
     */
    public Command findCommandIncludingMcp(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.startsWith("/") ? name.substring(1) : name;
        List<Command> local = getAllCommands();
        if (mcpServerService == null) {
            return findCommandIn(normalized, local);
        }
        List<Command> mcpSkills = mcpServerService.getMcpSkillCommandsForSearch();
        List<Command> mcpPrompts = mcpServerService.getMcpPromptCommandsForSearch();
        List<Command> mcp = new ArrayList<>(mcpSkills.size() + mcpPrompts.size());
        mcp.addAll(mcpSkills);
        mcp.addAll(mcpPrompts);
        List<Command> merged = uniqByName(local, mcp);
        Command hit = findCommandIn(normalized, merged);
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] findCommandIncludingMcp({}) 本地 {} + MCP 技能搜索视图 {} + MCP prompt 搜索视图 {} → 合并 {} 命中 {}（CC SkillTool.getAllCommands SkillTool.ts:81-93 + 拍板#2 fetchCommands 产物）",
                name, local.size(), mcpSkills.size(), mcpPrompts.size(), merged.size(), hit != null);
        }
        return hit;
    }

    /**
     * P2-9: 模型可调用命令的 listing 合并视图（含 MCP thread-in）· CC original:
     * {@code getSkillListingAttachments} attachments.ts:2677-2682
     * {@code localCommands = getSkillToolCommands(cwd)} + {@code mcpSkills = getMcpSkillCommands(...)}
     * → {@code uniqBy([...localCommands, ...mcpSkills], 'name')}。
     *
     * <p>skill_listing attachment 的数据源（LlmAgentLoop 注入侧）：本地视图 = {@link #getModelInvocableCommands()}
     * （对齐 CC getSkillToolCommands commands.ts:563-581），MCP 视图 = getMcpSkillCommands
     * （对齐 CC commands.ts:547-559），按 name 去重 local-first。
     *
     * <p>mcpServerService==null 或 MCP_SKILLS gate 关 → 返回纯本地视图。
     *
     * @return 模型可调用命令 + MCP 技能的合并列表（本地优先，按 name 去重）
     */
    public List<Command> getModelInvocableCommandsForListing() {
        List<Command> local = getModelInvocableCommands();
        if (mcpServerService == null) {
            return local;
        }
        List<Command> mcp = mcpServerService.getMcpSkillCommands();
        List<Command> merged = uniqByName(local, mcp);
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] getModelInvocableCommandsForListing: 本地 {} + MCP {} → 合并 {}（CC attachments.ts:2680-2682 uniqBy）",
                local.size(), mcp.size(), merged.size());
        }
        return merged;
    }

    /**
     * 按 name 去重合并（local-first）· CC original: {@code uniqBy([...local, ...mcp], 'name')}
     * attachments.ts:2681 / SkillTool.ts:93。
     *
     * <p>lodash uniqBy 保留首次出现 → local 在前，同名冲突 local 胜（CC 语义一致）。
     */
    private static List<Command> uniqByName(List<Command> local, List<Command> mcp) {
        Map<String, Command> byName = new LinkedHashMap<>();
        for (Command c : local) {
            if (c != null && c.getName() != null && !c.getName().isBlank()) {
                byName.putIfAbsent(c.getName(), c);
            }
        }
        for (Command c : mcp) {
            if (c != null && c.getName() != null && !c.getName().isBlank()) {
                byName.putIfAbsent(c.getName(), c);
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * 三维匹配（CC commands.ts:688-698 findCommand）· name 精确 / userFacingName / aliases，
     * 首个命中者胜。抽取自 {@link #findCommand}，供纯本地与含 MCP 搜索基座共用。
     */
    private static Command findCommandIn(String normalized, List<Command> commands) {
        for (Command c : commands) {
            // ① name 精确匹配（CC commands.ts:694）
            if (normalized.equals(c.getName())) return c;
            // ② getCommandName 匹配（CC :695 getCommandName = userFacingName?.() ?? name，
            //    types/command.ts:209-211；Java 侧 userFacingName() displayName 空回退 name）
            String userFacing = c.userFacingName();
            if (userFacing != null && normalized.equals(userFacing)) return c;
            // ③ aliases 匹配（CC :696）
            if (c.getAliases() != null && c.getAliases().contains(normalized)) return c;
        }
        return null;
    }

    /**
     * 获取斜杠命令技能 · 对齐 CC getSlashCommandToolSkills()（commands.ts:586-608 第二套过滤）。
     *
     * <p>过滤（commands.ts:590-598）：type='prompt'（:592）&& source != builtin（:593）
     * && (hasUserSpecifiedDescription || whenToUse)（:594）&& (loadedFrom∈{skills,plugin,bundled}
     * || disableModelInvocation)（:595-598）。{@code getSkillInfo}（prompt.ts:221-241）以本方法为
     * 数据源（统计斜杠命令技能数，SkillToolPrompt#getSkillInfo）。
     *
     * <p>P2-21 起 loadedFrom 用独立字段 {@link CommandLoadedFrom} 判别 —— CC :595-597
     * {@code loadedFrom∈{skills,plugin,bundled}} 明确<b>排除 commands_DEPRECATED</b>（legacy /commands/
     * 命令不再出现于斜杠技能集，deleteList P2-21 第 4 项；旧 Java source==USER 折叠使 legacy 命令
     * 误放行）；MCP 不在集合内（P2-9 分离后 Java 与 CC 一致：MCP live outside getCommands，
     * 本过滤从数据源上就不含 MCP）。builtinPlugin 技能 source/loadedFrom=BUNDLED（builtinPlugins.ts:149-150）
     * 在集合内。
     *
     * <p>P2-3 memoize：按 (projectRoot, skillsRoot) 复合键独立缓存（{@link #currentCacheKey()}，对齐
     * CC {@code getSlashCommandToolSkills = memoize}
     * commands.ts:586）；get-then-putIfAbsent（非 computeIfAbsent，规避 ConcurrentHashMap
     * 'Recursive update'）；{@link #refresh()} / {@link #setSkillsRoot} 显式清空。
     *
     * <p>P3-2 恒不抛契约（CC commands.ts:600-605）：compute 块（{@link #getAllCommands()} + 过滤链）
     * 包入 try-catch；加载失败（如 isCommandEnabled 门控 BooleanSupplier 抛错，Command.java:362-364
     * 惰性求值传播）→ log.warn（对齐 CC logError）+ log.debug（对齐 CC logForDebugging 'Returning empty
     * skills array due to load failure'）+ 缓存空列表（对齐 CC memoize 缓存失败解析值 []）+ 返回空列表。
     * 方法对外恒不抛 —— 技能加载失败不得拖垮 getSkillInfo/调用方（CC :602 注释「skills are non-critical」）；
     * 错误结果经 {@link #refresh()} 失效（与既有 refresh 清空三层缓存语义一致）。
     */
    public List<Command> getSlashCommandToolSkills() {
        String key = currentCacheKey();
        List<Command> cached = slashCommandToolSkillsCache.get(key);
        if (cached == null) {
            // 对齐 CC commands.ts:588-599 try 块（getCommands + filter）——加载失败不抛，缓存空列表并
            // 返回 []（CC commands.ts:600-605 恒不抛契约，技能加载失败不得拖垮 getSkillInfo/调用方）。
            try {
                List<Command> all = getAllCommands();
                List<Command> computed = all.stream()
                    .filter(c -> "prompt".equals(c.getType()))
                    .filter(c -> c.getSource() != CommandSource.BUILTIN)
                    .filter(c -> Boolean.TRUE.equals(c.getHasUserSpecifiedDescription())
                        || (c.getWhenToUse() != null && !c.getWhenToUse().isBlank()))
                    .filter(c -> c.getLoadedFrom() == CommandLoadedFrom.SKILLS
                        || c.getLoadedFrom() == CommandLoadedFrom.PLUGIN
                        || c.getLoadedFrom() == CommandLoadedFrom.BUNDLED
                        || Boolean.TRUE.equals(c.getDisableModelInvocation()))
                    .toList();
                List<Command> raced = slashCommandToolSkillsCache.putIfAbsent(key, computed);
                cached = raced != null ? raced : computed;
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] getSlashCommandToolSkills 过滤: 全部 {} 个 → 斜杠命令技能 {} 个 (key={})",
                        all.size(), computed.size(), key);
                }
            } catch (Exception e) {
                // 对齐 CC commands.ts:600-605：logError(toError(error)) + logForDebugging + return []
                //（CC :602 注释「skills are non-critical，防止技能加载失败拖垮整个系统」）。
                log.warn("[SkillRegistry] getSlashCommandToolSkills 加载失败 → 返回空列表 (CC commands.ts:600-605 恒不抛契约): {}",
                    e.getMessage());
                // 对齐 CC memoize：失败解析值 [] 同样被缓存（lodash memoize 缓存 Promise 解析结果），
                // 错误结果经 refresh() 显式失效（与 refresh() 清空三层缓存语义一致）。
                slashCommandToolSkillsCache.putIfAbsent(key, List.of());
                if (log.isDebugEnabled()) {
                    log.debug("[SkillRegistry] getSlashCommandToolSkills 加载失败 → 返回空数组并缓存 (CC commands.ts:604 logForDebugging 'Returning empty skills array due to load failure')");
                }
                return List.of();
            }
        }
        return cached;
    }

    /**
     * 按名称查找命令 · 对齐 CC findCommand()
     *
     * <p>三维匹配（commands.ts:688-698）：① {@code name === commandName}（:694）②
     * {@code getCommandName(_) === commandName}（:695）③ {@code _.aliases?.includes(commandName)}（:696）。
     * {@code getCommandName} = {@code cmd.userFacingName?.() ?? cmd.name}（types/command.ts:209-211），
     * Java 侧等价物为 {@link Command#userFacingName()}（displayName 空回退 name）。
     * CC {@code Array.find} → 首个命中者胜，命中顺序 = {@link #getAllCommands()} 加载序
     * （bundled→builtinPlugin→FS→dynamic→COMMANDS，与 CC 合并序一致；P2-9 分离后 MCP 不在此序，
     * 含 MCP 的搜索基座走 {@link #findCommandIncludingMcp}）。DEC-9：内置命令经本方法可
     * findCommand('clear') 命中（source=BUILTIN 仍进 findCommand 消费面，仅模型/斜杠过滤排除）。
     *
     * <p>前导 '/' 归一化保留 Java 内部（CC 在调用方剥 —— SkillTool.ts:437-438
     * {@code trimmed.startsWith('/') ? trimmed.substring(1) : trimmed}），净行为等价，不做本项迁移。
     */
    public Command findCommand(String name) {
        if (name == null || name.isBlank()) return null;
        // 去掉前导 /
        String normalized = name.startsWith("/") ? name.substring(1) : name;
        List<Command> all = getAllCommands();
        Command hit = findCommandIn(normalized, all);
        if (hit == null && log.isDebugEnabled()) {
            log.debug("[SkillRegistry] findCommand({}) 未命中: 已扫描 {} 个命令 (三维匹配 name/userFacingName/aliases 均未命中)",
                name, all.size());
        }
        return hit;
    }

    /**
     * 检查命令是否存在 · 对齐 CC hasCommand()
     */
    public boolean hasCommand(String name) {
        return findCommand(name) != null;
    }

    /**
     * 强制刷新（重新扫描文件系统）— 清空 memoize 缓存
     *
     * <p>P1-1 实装 · 对齐 CC {@code clearCommandMemoizationCaches()}（commands.ts:523-531）
     * + {@code clearSkillCaches()}（loadSkillsDir.ts:806-811）。清理面为
     * {@link #allCommandsCache} + {@link #modelInvocableCache} + {@link #slashCommandToolSkillsCache}
     * 三层 + MarkdownConfigLoader（loadMarkdownFilesForSubdir memoize，CC :807-808）+ DynamicSkillsManager
     * 条件状态（M27：conditionalSkills + activatedConditionalSkillNames 双清，CC :809-810，经
     * {@link DynamicSkillsManager#clearConditionalState()} 委托；<b>不清</b> dynamicSkills ——
     * CC clearSkillCaches 不清动态技能池，clearDynamicSkills :1070-1075 才是 4 态全清）。
     * 磁盘变更在调用本方法后的下一次查询可见（CC 靠 chokidar P1-16
     * 显式触发等价清理）；MCP 技能不落缓存（P2-9 thread-in 每调用新鲜合并），不依赖 refresh()。
     *
     * <p>P3-5: 末尾追加 {@link #skillIndexClearer} 挂钩 —— 对齐 CC {@code clearCommandMemoizationCaches}
     * 内 {@code clearSkillIndexCache?.()}（commands.ts:531，注释「getSkillIndex 是 built ON TOP of
     * getSkillToolCommands 的独立 memoize 层，必须显式清」）。默认 no-op（concern #30 skill-search
     * 子系统范围外）；已挂钩后可观测（注入计数 Runnable 断言触发）。
     */
    public void refresh() {
        int allSize = allCommandsCache.size();
        int invocableSize = modelInvocableCache.size();
        int slashSize = slashCommandToolSkillsCache.size();
        allCommandsCache.clear();
        modelInvocableCache.clear();
        // P2-3: 追加清空第二套过滤缓存（不补则 P1-16 skill 热更新后 getSlashCommandToolSkills 返回陈旧）
        slashCommandToolSkillsCache.clear();
        // P2-20: 追加清 MarkdownConfigLoader 缓存（loadMarkdownFilesForSubdir memoize，CC clearSkillCaches
        //   loadSkillsDir.ts:806-811）——五源后 legacy /commands/ 磁盘变更须同步失效，否则旧命令陈旧
        //   （P1-16 chokidar 等价路径一并覆盖）。
        MarkdownConfigLoader.clearCache();
        log.info("[SkillRegistry] refresh() 清空命令缓存 (allCommands {} 键, modelInvocable {} 键, slashCommandToolSkills {} 键, "
                + "MarkdownConfigLoader 已清) — 下次查询将重新扫描 5 源",
            allSize, invocableSize, slashSize);
        // P3-5: CC clearCommandMemoizationCaches 末尾 clearSkillIndexCache?.()（commands.ts:531）
        skillIndexClearer.run();
        // CI-21/CI-30: 追加 plugin 单一 feed 缓存清理 —— CC clearCommandsCache（commands.ts:534-539）
        //   在 clearCommandMemoizationCaches 后依次清 clearPluginCommandCache + clearPluginSkillsCache
        //   （loadPluginCommands.ts:679-681/944-946 双缓存）；Java 单一 feed 缓存由
        //   PluginLoader.clearPluginCache(String) 承载（pluginLoader.ts:3225-3243），此处串接对齐
        //   CC「插件安装/卸载后 refresh 重枚举 plugin 命令/技能」语义。pluginLoader 未注入（POJO/测试）
        //   → 跳过。
        if (pluginLoader != null) {
            pluginLoader.clearPluginCache("SkillRegistry.refresh");
        }
        // M27: 追加 DynamicSkillsManager 条件状态清理（对齐 CC clearSkillCaches loadSkillsDir.ts:809-810
        //   conditionalSkills.clear() + activatedConditionalSkillNames.clear() 双清）——P1-2 起条件技能
        //   状态由 manager 持有，refresh() 不双清则热更新后旧条件技能/激活标记滞留（探查 C-5/M27）。
        //   不清 dynamicSkills（CC clearSkillCaches 不清动态技能池；4 态全清是 clearDynamicSkills :1070-1075）。
        //   CC 调用序：clearCommandMemoizationCaches（含 :531 clearSkillIndexCache）→ clearCommandsCache
        //   → clearSkillCaches（commands.ts:534-539），故条件清理置于 skillIndexClearer 之后。
        if (dynamicSkillsManager != null) {
            dynamicSkillsManager.clearConditionalState();
        }
    }

    /**
     * 窄变体刷新 · 仅清命令 memoize 缓存（不动 skill 目录缓存）· 对齐 CC
     * {@code clearCommandMemoizationCaches()}（commands.ts:523-532）。
     *
     * <p><b>WHY 窄/宽分离（P2-13 · 对齐 CC skillChangeDetector.ts:94-97）</b>：
     * CC 对动态技能加载用<b>窄变体</b> {@code clearCommandMemoizationCaches}（skillChangeDetector.ts:94-97，
     * 注释「we use clearCommandMemoizationCaches (not clearCommandsCache) because clearCommandsCache would
     * call clearSkillCaches which wipes out the dynamic skills we just loaded」）——窄变体只清命令三层缓存
     * + {@link #skillIndexClearer}，<b>不动</b> MarkdownConfigLoader / plugin 缓存 / conditionalSkills /
     * activatedConditionalSkillNames。Java 旧实现 {@link #refresh()} 是三者并集（全量宽），动态技能加载
     * 路径经 {@link #setDynamicSkillsManager} 注册 {@code onDynamicSkillsLoaded(this::refresh)} 会顺带清掉
     * 条件技能激活状态（可观测：条件技能被重置），与 CC 窄变体语义偏移。
     *
     * <p>清理面 = 仅 {@link #allCommandsCache} + {@link #modelInvocableCache} +
     * {@link #slashCommandToolSkillsCache} + {@link #skillIndexClearer}（CC :530-531）。
     *
     * <p>P1-1 实装 · 对齐 CC commands.ts:523-532 窄变体。宽变体 {@link #refresh()} 仍为 SkillChangeDetector
     * 文件变更路径使用（对齐 CC scheduleReload skillChangeDetector.ts:274-275 的
     * {@code clearSkillCaches + clearCommandsCache} 全清语义）。
     */
    public void refreshCommandsOnly() {
        int allSize = allCommandsCache.size();
        int invocableSize = modelInvocableCache.size();
        int slashSize = slashCommandToolSkillsCache.size();
        allCommandsCache.clear();
        modelInvocableCache.clear();
        slashCommandToolSkillsCache.clear();
        log.info("[SkillRegistry] refreshCommandsOnly() 清空命令三层缓存 (allCommands {} 键, modelInvocable {} 键, "
                + "slashCommandToolSkills {} 键) — 不动 MarkdownConfigLoader/plugin/conditionalState "
                + "(CC clearCommandMemoizationCaches commands.ts:523-532 窄变体，skillChangeDetector.ts:94-97)",
            allSize, invocableSize, slashSize);
        // CC clearCommandMemoizationCaches 末尾 clearSkillIndexCache?.()（commands.ts:531）
        skillIndexClearer.run();
    }

    /**
     * P1-11: 读取当前 skillsRoot（对齐 CC memoize-by-cwd 的缓存键）· 供消费方数据流日志用.
     *
     * <p>P1-11 只读访问器，不改写任何状态（纯 additive，爆炸半径为零）。
     */
    public String getSkillsRoot() {
        return skillsRoot;
    }

    /**
     * P1-15: 技能使用评分（读侧 API · 透传）· 对齐 CC utils/suggestions/skillUsageTracking.ts:44-55
     * getSkillUsageScore()。
     *
     * <p>消费方数据源：CC commandSuggestions.ts:318（最近使用 top-5 filter score>0 + sort desc + slice 5）
     * 与 :419（搜索排序，type==='prompt' 才取分）以 getAllCommands + getSkillUsageScore 为排序数据源；
     * Java 侧本注册中心承载该读侧 API（{@link #getAllCommands} 即 getCommands 等价物）。
     *
     * <p>7 天半衰期 {@code 0.5^(daysSinceUse/7)} + 0.1 下限；无记录返回 0。未注入
     * {@link #setSkillUsageTracking} 时返回 0（POJO 测试兼容，行为不变）。
     *
     * @param skillName 技能名
     * @return 使用评分（0.0 = 无记录 / 未注入追踪）
     */
    public double getSkillUsageScore(String skillName) {
        if (skillUsageTracking == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] getSkillUsageScore({}) 未注入 SkillUsageTracking → 返回 0", skillName);
            }
            return 0.0;
        }
        double score = skillUsageTracking.getSkillUsageScore(skillName);
        if (log.isDebugEnabled()) {
            log.debug("[SkillRegistry] getSkillUsageScore({}) = {} (CC skillUsageTracking.ts:44-55)", skillName, score);
        }
        return score;
    }

    public void setSkillsRoot(String skillsRoot) {
        if (!Objects.equals(this.skillsRoot, skillsRoot)) {
            this.skillsRoot = skillsRoot;
            // 键变更 → 旧键不再命中（Map 键天然自失效）；hygiene: 显式清空防止陈旧键条目积累
            // （对齐 CC memoize-by-cwd：不同 cwd 各自缓存，直到显式 clear cache）。
            allCommandsCache.clear();
            modelInvocableCache.clear();
            slashCommandToolSkillsCache.clear();
            if (log.isDebugEnabled()) {
                log.debug("[SkillRegistry] setSkillsRoot({}) 变更 → 清空命令缓存 (含 slashCommandToolSkills)", skillsRoot);
            }
        }
    }
}

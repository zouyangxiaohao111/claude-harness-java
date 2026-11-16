package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookMatcherEngine;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HooksConfigSnapshot;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.McpToolHook;
import com.nexusai.application.agent.permission.hook.PromptHook;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin Loader 总模块 · 对齐 CC utils/plugins/pluginLoader.ts (3302 行).
 *
 * <p>FIX-PLUGIN-LOADER: 简化版 plugin cache/path/source/copy/install loader.
 *
 * <p>L1 行为: 给定 pluginName + 安装源 (path/git/marketplace), 加载 + 缓存到本地 ~/.nexusai/plugins/.
 *
 * <p>[ODF-C3] plugin agents 目录扫描接线 · 对齐 CC loadPluginAgents.ts:231-344:
 * loadAllPluginsCacheOnly 产出 enabled plugins → 每 plugin 扫 agentsPath。
 * {@link #loadAgents} 委托 {@code LoadPluginAgents} 扫描出 pluginName 前缀 + source='plugin'
 * 的 AgentDefinition。
 *
 * <p>LIMIT: 极简实现, 不覆盖 marketplace sync / GCS fetch / 完整 GitHub release 拉取.
 * 留 P1 接入完整 marketplaceManager.
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    public enum InstallSource { PATH, GIT, MARKETPLACE, NPM }

    private final Map<String, LoadedPlugin> cache = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    // [MPL7] feed memoize · 对齐 CC loadAllPluginsCacheOnly（pluginLoader.ts:3137-3146）
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: PluginError（pluginLoader.ts:2118-2122 plugin-cache-miss）· feed 错误条目。 */
    public record PluginError(String type, String source, String plugin, String installPath) {}

    /** CC original: PluginLoadResult（pluginLoader.ts:3206-3210 {enabled, disabled, errors}）· feed 结果。 */
    public record PluginLoadResult(List<LoadedPlugin> enabled, List<LoadedPlugin> disabled,
                                   List<PluginError> errors) {
        public PluginLoadResult {
            enabled = enabled == null ? List.of() : List.copyOf(enabled);
            disabled = disabled == null ? List.of() : List.copyOf(disabled);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /**
     * [MPL7] 无参单槽 memoize 缓存（CC lodash memoize key=undefined 等价）。
     * 对齐 CC loadAllPluginsCacheOnly（pluginLoader.ts:3137）与 loadAllPlugins（:3096）共用单槽：
     * 连续两次 {@link #loadAllPluginsCacheOnly()} 仅枚举一次；{@link #loadAllPlugins()} 完成后
     * 预热本缓存（CC :3106 loadAllPluginsCacheOnly.cache?.set(undefined, ...)）。
     */
    private volatile PluginLoadResult feedCache;

    /** feed 已装载标志 · 无参单槽 memoize 的"已缓存"位（CC cache 命中）。 */
    private volatile boolean feedLoaded = false;

    /** feed 枚举锁 · 单槽缓存读改写互斥。 */
    private final Object feedLock = new Object();

    /** [MPL7] feed 枚举计数（测试可观测：连续两次仅枚举一次）· Java 测试增强，CC 无此字段。 */
    private int enumerationCount;

    /** [MPL7] hooks.json 解析器 · CC loadPluginHooksFile 用 JSON.parse（pluginLoader.ts:1234-1235）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** [Wave2-J2] 解析期 JsonNode → Map 转换（纯静态，不依赖实例）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * [ODF-C3R] 已装 plugins 注册表 · 生产 feed 数据源（对齐 CC loadAllPluginsCacheOnly
     * "installed + enabled" 枚举，pluginLoader.ts:3198）。
     * 可选注入 (required=false)：未注入时 {@link #loadInstalledEnabledPlugins()} 短路，不破坏测试/直构。
     */
    private volatile InstalledPluginsManager installedPluginsManager;

    /**
     * [P2-14] 插件命令元数据 · 对齐 CC {@code CommandMetadata}
     * （Open-ClaudeCode/src/utils/plugins/schemas.ts:385-420 CommandMetadataSchema）。
     *
     * <p>CC 字段：{@code source}（相对插件根的 .md 路径，单文件 override 匹配用）、
     * {@code content}（inline markdown，无源文件）、{@code description}/{@code argumentHint}/
     * {@code model}/{@code allowedTools} 四覆盖字段；{@code source} 与 {@code content} 二选一
     * （.refine :410-417 {@code 'source' and not 'content', or 'content' and not 'source'}）。
     *
     * @param source       CC original: source · 命令 markdown 文件相对插件根路径
     * @param content      CC original: content · inline markdown 内容（无源文件命令）
     * @param description  CC original: description · 命令描述覆盖
     * @param argumentHint CC original: argumentHint · 参数提示覆盖（如 "[file]"）
     * @param model        CC original: model · 命令默认模型覆盖
     * @param allowedTools CC original: allowedTools · 命令允许工具覆盖
     */
    public record CommandMetadata(
        String source,
        String content,
        String description,
        String argumentHint,
        String model,
        List<String> allowedTools) {

        /** 便捷构造（source/content 二选一，覆盖字段置空）· 供 inline/单文件 override 装配。 */
        public CommandMetadata(String source, String content) {
            this(source, content, null, null, null, null);
        }
    }

    public record LoadedPlugin(String name, InstallSource source, Path localPath,
                               long loadedAt, boolean enabled,
                               /** CC original: agentsPath（loadPluginAgents.ts:250）· 插件默认 agents 目录 */
                               Path agentsPath,
                               /** CC original: agentsPaths（loadPluginAgents.ts:276）· manifest 附加路径（目录/单 .md） */
                               List<String> agentsPaths,
                               /** CC original: commandsPath（pluginLoader.ts:1388-1391）· 插件默认 commands 目录 */
                               Path commandsPath,
                               /** CC original: commandsPaths（pluginLoader.ts:1463-1466/1523-1525）· manifest 附加命令路径 */
                               List<String> commandsPaths,
                               /** CC original: skillsPath（pluginLoader.ts:1558-1561）· 插件默认 skills 目录 */
                               Path skillsPath,
                               /** CC original: skillsPaths（pluginLoader.ts:1580-1582）· manifest 附加技能路径 */
                               List<String> skillsPaths,
                               /** CC original: outputStylesPath（pluginLoader.ts:1586-1589）· 插件默认 output-styles 目录 */
                               Path outputStylesPath,
                               /** CC original: outputStylesPaths（pluginLoader.ts:1608-1610）· manifest 附加样式路径 */
                               List<String> outputStylesPaths,
                               /** [P2-14] CC original: commandsMetadata（types/plugin.ts:59）· manifest object-mapping 命令元数据 */
                               Map<String, CommandMetadata> commandsMetadata) {

        public LoadedPlugin(String name, InstallSource source, Path localPath,
                            long loadedAt, boolean enabled) {
            this(name, source, localPath, loadedAt, enabled, null, List.of(),
                null, List.of(), null, List.of(), null, List.of(), Map.of());
        }

        public LoadedPlugin(String name, InstallSource source, Path localPath,
                            long loadedAt, boolean enabled,
                            Path agentsPath, List<String> agentsPaths) {
            this(name, source, localPath, loadedAt, enabled, agentsPath, agentsPaths,
                null, List.of(), null, List.of(), null, List.of(), Map.of());
        }

        public LoadedPlugin(String name, InstallSource source, Path localPath,
                            long loadedAt, boolean enabled,
                            Path agentsPath, List<String> agentsPaths,
                            Path commandsPath, List<String> commandsPaths,
                            Path skillsPath, List<String> skillsPaths,
                            Path outputStylesPath, List<String> outputStylesPaths) {
            this(name, source, localPath, loadedAt, enabled, agentsPath, agentsPaths,
                commandsPath, commandsPaths, skillsPath, skillsPaths,
                outputStylesPath, outputStylesPaths, Map.of());
        }
    }

    public LoadedPlugin load(String pluginName, InstallSource source, Path sourcePath) {
        LoadedPlugin loaded = new LoadedPlugin(pluginName, source, sourcePath,
            System.currentTimeMillis(), true);
        cache.put(pluginName, loaded);
        invalidateFeed();
        log.info("PluginLoader: loaded plugin={} source={} path={}",
            pluginName, source, sourcePath);
        return loaded;
    }

    /**
     * [ODF-C3 返工#4] 带 agentsPath 的加载 · 对齐 CC loadPluginAgents.ts:250-276
     * （plugin.agentsPath 默认 agents 目录 + plugin.agentsPaths manifest 附加路径）。
     *
     * <p>生产装配：插件 manifest 解析出 agentsPath 后经本方法注册，随后
     * {@link #loadAllEnabledAgents()} 才能真正扫到插件 agents（5 参 load 的
     * LoadedPlugin 会丢 agentsPath 字段 → loadAllEnabledAgents 恒空）。</p>
     *
     * @param pluginName  插件名（agentType 前缀）
     * @param source      安装源
     * @param sourcePath  本地路径
     * @param agentsPath  插件默认 agents 目录（可 null → 仅附加路径生效）
     * @return 已缓存 LoadedPlugin
     */
    public LoadedPlugin load(String pluginName, InstallSource source, Path sourcePath, Path agentsPath) {
        LoadedPlugin loaded = new LoadedPlugin(pluginName, source, sourcePath,
            System.currentTimeMillis(), true, agentsPath, List.of());
        cache.put(pluginName, loaded);
        invalidateFeed();
        log.info("PluginLoader: loaded plugin={} source={} path={} agentsPath={}",
            pluginName, source, sourcePath, agentsPath);
        return loaded;
    }

    /**
     * [MPL6] 带 6 类路径字段的加载 · 对齐 CC pluginLoader.ts:1388-1610
     * （commandsPath/commandsPaths + skillsPath/skillsPaths + outputStylesPath/outputStylesPaths，
     * 与既有 agentsPath/agentsPaths 并列）。
     *
     * <p>生产装配：{@link InstalledPluginsManager#install}（13 参重载，形参表过长不逐一罗列）
     * 解析出插件 manifest 全部组件路径后经本方法注册，随后 {@link #loadAllEnabledCommands()} /
     * {@link #loadAllEnabledSkills()} / {@link #loadAllEnabledOutputStyles()} 才能扫到插件组件
     * （仅 4 参 load 会丢 6 类路径字段 → 组件扫描恒空）。</p>
     *
     * @param pluginName        插件名（组件名前缀）
     * @param source            安装源
     * @param sourcePath        插件本地安装目录（${CLAUDE_PLUGIN_ROOT} 替换上下文）
     * @param agentsPath        插件默认 agents 目录（可 null）
     * @param agentsPaths       manifest 附加 agents 路径（可空）
     * @param commandsPath      插件默认 commands 目录（可 null）
     * @param commandsPaths     manifest 附加命令路径（可空）
     * @param skillsPath        插件默认 skills 目录（可 null）
     * @param skillsPaths       manifest 附加技能路径（可空）
     * @param outputStylesPath  插件默认 output-styles 目录（可 null）
     * @param outputStylesPaths manifest 附加样式路径（可空）
     * @return 已缓存 LoadedPlugin
     */
    public LoadedPlugin load(String pluginName, InstallSource source, Path sourcePath,
                             Path agentsPath, java.util.List<String> agentsPaths,
                             Path commandsPath, java.util.List<String> commandsPaths,
                             Path skillsPath, java.util.List<String> skillsPaths,
                             Path outputStylesPath, java.util.List<String> outputStylesPaths) {
        return load(pluginName, source, sourcePath, agentsPath, agentsPaths,
            commandsPath, commandsPaths, skillsPath, skillsPaths,
            outputStylesPath, outputStylesPaths, Map.of());
    }

    /**
     * [P2-14] 带 6 类路径字段 + commandsMetadata 的加载 · 对齐 CC pluginLoader.ts:1388-1610 + :1467-1470。
     *
     * <p>比 {@link #load(String, InstallSource, Path, Path, java.util.List, Path, java.util.List, Path, java.util.List, Path, java.util.List)}
     * 多 commandsMetadata（manifest object-mapping 命令元数据，pluginLoader.ts:1405-1470 解析）——
     * LoadPluginCommands 据此做单文件 override + inline content 命令加载（loadPluginCommands.ts:504-668）。
     *
     * @param commandsMetadata manifest object-mapping 命令元数据（可空 → 无 override/inline 命令）
     * @return 已缓存 LoadedPlugin
     */
    public LoadedPlugin load(String pluginName, InstallSource source, Path sourcePath,
                             Path agentsPath, java.util.List<String> agentsPaths,
                             Path commandsPath, java.util.List<String> commandsPaths,
                             Path skillsPath, java.util.List<String> skillsPaths,
                             Path outputStylesPath, java.util.List<String> outputStylesPaths,
                             Map<String, CommandMetadata> commandsMetadata) {
        LoadedPlugin loaded = new LoadedPlugin(pluginName, source, sourcePath,
            System.currentTimeMillis(), true,
            agentsPath, agentsPaths,
            commandsPath, commandsPaths,
            skillsPath, skillsPaths,
            outputStylesPath, outputStylesPaths,
            commandsMetadata != null ? commandsMetadata : Map.of());
        cache.put(pluginName, loaded);
        invalidateFeed();
        if (log.isDebugEnabled()) {
            log.debug("PluginLoader: loaded plugin={} source={} path={} agentsPath={} commandsPath={} skillsPath={} outputStylesPath={}",
                pluginName, source, sourcePath, agentsPath, commandsPath, skillsPath, outputStylesPath);
        }
        return loaded;
    }

    /**
     * [MPL6] 由已装记录装载 · 生产 feed 全路径接线（对齐 CC loadAllPluginsCacheOnly
     * pluginLoader.ts:3198 enabled plugins，6 类组件路径随记录透传）。
     *
     * @param rec 已装插件记录（含 6 类组件路径字段）
     * @return 已缓存 LoadedPlugin
     */
    public LoadedPlugin loadFromInstalled(InstalledPluginsManager.InstalledRecord rec) {
        LoadedPlugin loaded = doLoadFromInstalled(rec);
        invalidateFeed();
        return loaded;
    }

    /** 装载内体（不失效 feed）· 供 feed 枚举内部调用，避免枚举中反复失效标志。 */
    private LoadedPlugin doLoadFromInstalled(InstalledPluginsManager.InstalledRecord rec) {
        LoadedPlugin loaded = new LoadedPlugin(rec.name(), sourceFor(rec.scope()), rec.sourcePath(),
            System.currentTimeMillis(), true,
            rec.agentsPath(), List.of(),
            rec.commandsPath(), rec.commandsPaths(),
            rec.skillsPath(), rec.skillsPaths(),
            rec.outputStylesPath(), rec.outputStylesPaths(),
            rec.commandsMetadata() != null ? rec.commandsMetadata() : Map.of());
        cache.put(rec.name(), loaded);
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 由已装记录装载: plugin={} scope={} commandsPath={} skillsPath={} outputStylesPath={}",
                rec.name(), rec.scope(), rec.commandsPath(), rec.skillsPath(), rec.outputStylesPath());
        }
        return loaded;
    }

    /**
     * [ODF-C3R] 已装 plugins 注册表注入 · 生产 feed 数据源（对齐 CC loadAllPluginsCacheOnly
     * "installed + enabled" 枚举，pluginLoader.ts:3198）。可选注入 (required=false)。
     */
    @Autowired(required = false)
    public void setInstalledPluginsManager(InstalledPluginsManager installedPluginsManager) {
        this.installedPluginsManager = installedPluginsManager;
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 已装 plugins 注册表注入: {}", installedPluginsManager != null);
        }
    }

    /** [MPL7] hook 注册中心（GenericHook 执行路径）· 可选注入，loadPluginHooks 注册 PLUGIN_HOOK。 */
    @Autowired(required = false)
    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] HookRegistry 注入: {}", hookRegistry != null);
        }
    }


    /** [MPL7] hooks 配置快照（引擎 getMatchingHooks 数据源）· 可选注入，注册后重捕获。 */
    @Autowired(required = false)
    public void setHooksConfigSnapshot(HooksConfigSnapshot hooksConfigSnapshot) {
        this.hooksConfigSnapshot = hooksConfigSnapshot;
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] HooksConfigSnapshot 注入: {}", hooksConfigSnapshot != null);
        }
    }

    /** [MPL7] 命令 hook 执行器（插件命令 hook 执行）· 可选注入。 */
    @Autowired(required = false)
    public void setCommandHookExecutor(com.nexusai.application.agent.permission.hook.CommandHookExecutor commandHookExecutor) {
        this.commandHookExecutor = commandHookExecutor;
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] CommandHookExecutor 注入: {}", commandHookExecutor != null);
        }
    }

    /**
     * [IMP-GAP03] hook matcher 引擎（插件 hook matcher 过滤判定）· 可选注入，null → 不过滤
     * （保持现状）。镜像 {@link #setHooksConfigSnapshot} 模式。
     */
    @Autowired(required = false)
    public void setHookMatcherEngine(HookMatcherEngine hookMatcherEngine) {
        this.hookMatcherEngine = hookMatcherEngine;
        if (log.isDebugEnabled()) {
            log.debug("[IMP-GAP03] HookMatcherEngine 注入: {}", hookMatcherEngine != null);
        }
    }

    /**
     * [P2-24 · 2026-09-11] 缓存级联工具注入 · 可选注入（required=false）。
     *
     * <p><b>{@code @Lazy} 断装配环</b>：{@link PluginCacheUtils} 反向注入本类（其 {@code setPluginLoader}）
     * 以清 feed 单槽 → 直注入构成 PluginLoader ↔ PluginCacheUtils 循环引用（Spring Boot 2.6+ 默认
     * 禁循环引用）。{@code @Lazy} 延迟到首次使用解析（同 PluginCacheUtils 对 SubagentTool 的既有先例）。
     *
     * <p>未注入（直构/单测）→ null → {@link #refreshActivePlugins()} 回退到原有的
     * {@code clearPluginCache + pruneRemovedPluginHooks}（行为不丢，只是少了命令/agent/prompt/
     * skill_listing 的级联清空）。
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    public void setPluginCacheUtils(PluginCacheUtils pluginCacheUtils) {
        this.pluginCacheUtils = pluginCacheUtils;
        if (log.isDebugEnabled()) {
            log.debug("[P2-24] PluginCacheUtils 注入: {}", pluginCacheUtils != null);
        }
    }

    /** [MPL7] hook 注册中心（可为 null → 插件 hook 注册跳过, 不破坏插件加载）。 */
    private volatile HookRegistry hookRegistry;

    /** [MPL7] hooks 配置快照（可为 null → 注册后跳过引擎重捕获）。 */
    private volatile HooksConfigSnapshot hooksConfigSnapshot;

    /** [MPL7] 命令 hook 执行器（可为 null → 插件 hook 执行时返回 proceed）。 */
    private volatile com.nexusai.application.agent.permission.hook.CommandHookExecutor commandHookExecutor;

    /** [IMP-GAP03] hook matcher 引擎（可为 null → 插件 hook matcher 不过滤, 保持现状）。 */
    private volatile HookMatcherEngine hookMatcherEngine;

    /**
     * [P2-24 · 2026-09-11] 缓存级联工具（可为 null → refreshActivePlugins 回退到 feed 单槽清空路径）。
     *
     * <p>{@link #refreshActivePlugins()} 经此调 CC 同款 {@code clearAllCaches()}（refresh.ts:76）。
     */
    private volatile PluginCacheUtils pluginCacheUtils;

    /**
     * [ODF-C3R] 生产 feed · 装配期枚举已装 enabled plugins 调 4 参 load 注册 agentsPath。
     *
     * <p>对齐 CC loadPluginAgents.ts:233-234（{@code loadAllPluginsCacheOnly} → enabled plugins）
     * + pluginLoader.ts:3198（{@code allPlugins.filter(p => p.enabled)}）。Java 等价源 =
     * {@link InstalledPluginsManager#list()}（已装 + enabled 标志）：每 enabled 记录调
     * {@link #load(String, InstallSource, Path, Path)}（4 参保留 agentsPath）→
     * {@link #loadAllEnabledAgents()} 才能真正扫到 plugin agents。
     *
     * <p>装配时序：本方法在 PluginLoader bean 创建期（@PostConstruct）执行，早于
     * SubagentTool.setPluginLoader（@Autowired 注入需 PluginLoader bean 就绪）→
     * mergePluginAgents() 装配期即可扫到已装 enabled plugins 的 agents。
     *
     * <p>幂等：已缓存插件跳过（外部 load 优先）。未注入注册表 → no-op（fail loud 不抛）。
     */
    @PostConstruct
    public void loadInstalledEnabledPlugins() {
        InstalledPluginsManager manager = this.installedPluginsManager;
        if (manager == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PluginLoader] 生产 feed 跳过: InstalledPluginsManager 未注入");
            }
            return;
        }
        PluginLoadResult result = loadAllPluginsCacheOnly();
        if (log.isInfoEnabled()) {
            log.info("[PluginLoader] 生产 feed 预热: {} enabled, {} disabled, {} cache-miss (对齐 CC loadAllPluginsCacheOnly @PostConstruct)",
                result.enabled().size(), result.disabled().size(), result.errors().size());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [MPL7] feed memoize 方法 · 对齐 CC pluginLoader.ts:3096-3243
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [MPL7] feed 懒枚举 · 对齐 CC loadAllPluginsCacheOnly（pluginLoader.ts:3137-3146）无参单槽 memoize。
     *
     * <p>CC 语义：零参 memoize → 单槽缓存（key=undefined）；连续两次调用仅枚举一次。
     * {@link #clearPluginCache(String)} 是唯一失效入口（CC clearPluginCache :3231-3232 清两个 memoize）。
     *
     * <p>启动消费方（getCommands/loadPluginAgents/组件扫描）经本方法读 feed —— 不阻塞 git clone。
     *
     * @return enabled/disabled/errors 分类插件结果
     */
    public PluginLoadResult loadAllPluginsCacheOnly() {
        if (feedLoaded) {
            return feedCache;
        }
        synchronized (feedLock) {
            if (feedLoaded) {
                return feedCache;
            }
            PluginLoadResult result = enumerateFeed();
            feedCache = result;
            feedLoaded = true;
            return result;
        }
    }

    /**
     * [MPL7] 全量加载 · 对齐 CC loadAllPlugins（pluginLoader.ts:3096-3108）强制新鲜枚举，
     * 完成后预热 cacheOnly 缓存（CC :3106 {@code loadAllPluginsCacheOnly.cache?.set(undefined, ...)}）。
     *
     * <p>刷新路径（/plugins、refreshActivePlugins）用本方法；启动消费方用
     * {@link #loadAllPluginsCacheOnly()}。预热后下游 cacheOnly 调用方不再 plugin-cache-miss
     * （CC :3100-3105 注释语义）。
     *
     * @return 新鲜枚举结果（同时作为预热 cacheOnly 缓存）
     */
    public PluginLoadResult loadAllPlugins() {
        synchronized (feedLock) {
            PluginLoadResult result = enumerateFeed();
            feedCache = result;
            feedLoaded = true;
            return result;
        }
    }

    /**
     * [MPL7] 清空 feed memoize · 对齐 CC clearPluginCache（pluginLoader.ts:3225-3243）
     * {@code loadAllPlugins.cache?.clear?.() + loadAllPluginsCacheOnly.cache?.clear?.()}。
     * 安装/卸载/启停插件或设置变更后调用，强制下次 {@link #loadAllPluginsCacheOnly()} 重枚举。
     *
     * @param reason 失效原因（仅日志）
     */
    public void clearPluginCache(String reason) {
        synchronized (feedLock) {
            invalidateFeed();
            if (reason != null && log.isInfoEnabled()) {
                log.info("[MPL7] clearPluginCache: 失效 feed memoize ({}), 下次 loadAllPluginsCacheOnly 重枚举", reason);
            }
        }
    }

    /** [MPL7] feed 枚举次数（测试可观测：连续两次 loadAllPluginsCacheOnly 仅枚举一次 = 1）。 */
    public int enumerationCount() {
        synchronized (feedLock) {
            return enumerationCount;
        }
    }

    /** [MPL7] 失效单槽缓存（feedLoaded 先置 false 防并发读见 feedLoaded=true 而 feedCache=null）。 */
    private void invalidateFeed() {
        feedLoaded = false;
        feedCache = null;
    }

    /**
     * [MPL7] feed 枚举体 · 对齐 CC assemblePluginLoadResult（pluginLoader.ts:3155-3211）。
     *
     * <p>从 installedPluginsManager.list() 枚举已装 enabled 记录（对齐 CC loadAllPluginsCacheOnly
     * "installed + enabled" 枚举，pluginLoader.ts:3198）→ 未缓存则 {@link #doLoadFromInstalled} 注册。
     * enabled 记录 sourcePath 缺失 → plugin-cache-miss 跳过（CC :2130-2138），不抛异常。
     * disabled 记录不进入 feed（既有契约：disabled 插件不得注册，InstalledPluginsManagerTest 回归）。
     */
    private PluginLoadResult enumerateFeed() {
        enumerationCount++;
        List<PluginError> errors = new ArrayList<>();
        InstalledPluginsManager manager = this.installedPluginsManager;
        if (manager != null) {
            for (InstalledPluginsManager.InstalledRecord rec : manager.list()) {
                if (rec == null || !rec.enabled()) {
                    continue;
                }
                if (rec.sourcePath() == null) {
                    errors.add(new PluginError("plugin-cache-miss", rec.scope(), rec.name(), "(not recorded)"));
                    continue;
                }
                if (!cache.containsKey(rec.name())) {
                    doLoadFromInstalled(rec);
                }
            }
        }
        List<LoadedPlugin> enabled = new ArrayList<>();
        List<LoadedPlugin> disabled = new ArrayList<>();
        for (LoadedPlugin p : cache.values()) {
            if (p.enabled()) {
                enabled.add(p);
            } else {
                disabled.add(p);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[MPL7] feed 枚举: {} enabled, {} disabled, {} cache-miss (对齐 CC loadAllPluginsCacheOnly)",
                enabled.size(), disabled.size(), errors.size());
        }
        return new PluginLoadResult(List.copyOf(enabled), List.copyOf(disabled), List.copyOf(errors));
    }

    /** scope → InstallSource · CC plugin.source 语义（pluginLoader.ts:3198 source 字段）。 */
    private static InstallSource sourceFor(String scope) {
        if (scope == null) {
            return InstallSource.PATH;
        }
        return switch (scope.toLowerCase()) {
            case "marketplace" -> InstallSource.MARKETPLACE;
            case "git" -> InstallSource.GIT;
            case "npm" -> InstallSource.NPM;
            default -> InstallSource.PATH;
        };
    }

    /**
     * [ODF-C3] plugin agents 目录扫描 · 对齐 CC loadPluginAgents.ts:250-309
     * (plugin.agentsPath 默认目录 + plugin.agentsPaths 附加路径)。
     *
     * <p>生产接线: SubagentTool/装配方对每个 enabled plugin 调本方法, 产出 pluginName 前缀 +
     * source='plugin' 的 AgentDefinition 后并入 AgentDefinitionRegistry（6 组覆盖合并）。
     *
     * @param agentsPath  插件 agents 目录（可为 null/不存在 → 跳过）
     * @param pluginName  插件名（agentType 前缀）
     * @return 扫描出的 plugin agents 列表
     */
    public List<com.nexusai.application.agent.subagent.AgentDefinition> loadAgents(
            Path agentsPath, String pluginName) {
        return loadAgents(agentsPath, pluginName, List.of());
    }

    /**
     * [ODF-C3] 带 manifest 附加路径的 plugin agents 扫描。
     *
     * @param agentsPath       插件 agents 目录
     * @param pluginName       插件名
     * @param additionalPaths manifest 附加路径（目录或单 .md 文件，loadPluginAgents.ts:276-309）
     * @return 扫描出的 plugin agents 列表
     */
    public List<com.nexusai.application.agent.subagent.AgentDefinition> loadAgents(
            Path agentsPath, String pluginName, List<String> additionalPaths) {
        return loadAgents(agentsPath, pluginName, additionalPaths, null);
    }

    /**
     * [ODF-C3 返工#2] 带 pluginPath 替换上下文的 plugin agents 扫描。
     *
     * @param agentsPath       插件 agents 目录
     * @param pluginName       插件名
     * @param additionalPaths manifest 附加路径（目录或单 .md 文件）
     * @param pluginPath      插件安装目录（作为 ${CLAUDE_PLUGIN_ROOT} 替换上下文，对齐 CC plugin.path）
     * @return 扫描出的 plugin agents 列表
     */
    public List<com.nexusai.application.agent.subagent.AgentDefinition> loadAgents(
            Path agentsPath, String pluginName, List<String> additionalPaths, Path pluginPath) {
        List<com.nexusai.application.agent.subagent.AgentDefinition> agents =
            com.nexusai.application.agent.subagent.LoadPluginAgents.load(
                agentsPath, pluginName, additionalPaths, pluginPath);
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] plugin agents 扫描完成: plugin={} agents={} pluginPath={}",
                pluginName, agents.size(), pluginPath);
        }
        return agents;
    }

    /**
     * [ODF-C3] 遍历 enabled plugins 扫描全部 plugin agents · 对齐 CC loadPluginAgents.ts:234-331
     * ({@code Promise.all(enabled.map(...))} → 每 plugin 扫 agentsPath + agentsPaths)。
     *
     * @return 所有 enabled plugin 的 agents（source='plugin'）
     */
    public List<com.nexusai.application.agent.subagent.AgentDefinition> loadAllEnabledAgents() {
        List<com.nexusai.application.agent.subagent.AgentDefinition> all = new java.util.ArrayList<>();
        // [MPL7] 经无参单槽 memoize feed（对齐 CC loadPluginAgents.ts:234 loadAllPluginsCacheOnly
        //   → enabled）· 连续两次调用命中缓存不重枚举。
        for (LoadedPlugin plugin : loadAllPluginsCacheOnly().enabled()) {
            // 以 localPath 为 ${CLAUDE_PLUGIN_ROOT} 替换上下文（CC loadPluginAgents.ts:234 plugin.path）
            all.addAll(loadAgents(plugin.agentsPath(), plugin.name(), plugin.agentsPaths(),
                plugin.localPath()));
        }
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 全部 enabled plugins agents: {}", all.size());
        }
        return all;
    }

    /**
     * [MPL6] 遍历 enabled plugins 扫描全部 plugin 命令 · 对齐 CC loadPluginCommands.ts
     * {@code getPluginCommands}（commandsPath + commandsPaths 扫描，命令名 plugin:ns:name）。
     *
     * <p>bare 门禁由 {@link MemoryBareModeConfig#isBareMode()} 注入（CI-28，对齐 CC
     * getPluginCommands :419-421 {@code isBareMode() && inlinePlugins 空 → []}）。
     *
     * @return 所有 enabled plugin 的命令（source='plugin'）
     */
    public List<com.nexusai.model.command.Command> loadAllEnabledCommands() {
        List<com.nexusai.model.command.Command> commands =
            LoadPluginCommands.loadCommands(enabledPlugins(), MemoryBareModeConfig.isBareMode());
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 全部 enabled plugins 命令: {}", commands.size());
        }
        return commands;
    }

    /**
     * [MPL6] 遍历 enabled plugins 扫描全部 plugin 技能 · 对齐 CC loadPluginCommands.ts
     * {@code getPluginSkills}（skillsPath + skillsPaths 扫描，name=plugin:basename）。
     *
     * <p>bare 门禁由 {@link MemoryBareModeConfig#isBareMode()} 注入（CI-29，对齐 CC
     * getPluginSkills :843-845 {@code isBareMode() && inlinePlugins 空 → []}）。
     *
     * @return 所有 enabled plugin 的技能（source='plugin'）
     */
    public List<com.nexusai.model.command.Command> loadAllEnabledSkills() {
        List<com.nexusai.model.command.Command> skills =
            LoadPluginCommands.loadSkills(enabledPlugins(), MemoryBareModeConfig.isBareMode());
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 全部 enabled plugins 技能: {}", skills.size());
        }
        return skills;
    }

    /**
     * [MPL6] 遍历 enabled plugins 扫描全部 plugin output styles · 对齐 CC loadPluginOutputStyles.ts
     * {@code loadPluginOutputStyles}（outputStylesPath + outputStylesPaths 扫描，name=plugin:base）。
     *
     * @return 所有 enabled plugin 的 output styles（source='plugin'）
     */
    public List<com.nexusai.application.agent.outputstyle.OutputStyleDirLoader.OutputStyle> loadAllEnabledOutputStyles() {
        List<com.nexusai.application.agent.outputstyle.OutputStyleDirLoader.OutputStyle> styles =
            LoadPluginOutputStyles.load(enabledPlugins());
        if (log.isDebugEnabled()) {
            log.debug("[PluginLoader] 全部 enabled plugins output styles: {}", styles.size());
        }
        return styles;
    }

    /** enabled plugins 列表（CC loadAllPluginsCacheOnly → enabled，pluginLoader.ts:3198）· [MPL7] 经 memoize feed。 */
    private List<LoadedPlugin> enabledPlugins() {
        return loadAllPluginsCacheOnly().enabled();
    }

    public LoadedPlugin get(String pluginName) {
        return cache.get(pluginName);
    }

    public boolean unload(String pluginName) {
        LoadedPlugin removed = cache.remove(pluginName);
        if (removed != null) {
            invalidateFeed();
        }
        return removed != null;
    }

    public boolean enable(String pluginName) {
        LoadedPlugin p = cache.get(pluginName);
        if (p == null) return false;
        // 保留全部组件路径字段（MPL6 起含 commands/skills/output-styles 三类，5 参构造会重置丢失扫描配置）
        cache.put(pluginName, withEnabled(p, true));
        invalidateFeed();
        return true;
    }

    public boolean disable(String pluginName) {
        LoadedPlugin p = cache.get(pluginName);
        if (p == null) return false;
        cache.put(pluginName, withEnabled(p, false));
        invalidateFeed();
        return true;
    }

    /** 翻转 enabled 标志并保留全部 6 类组件路径字段 + commandsMetadata（MPL6 / P2-14）。 */
    private static LoadedPlugin withEnabled(LoadedPlugin p, boolean enabled) {
        return new LoadedPlugin(p.name(), p.source(), p.localPath(), p.loadedAt(), enabled,
            p.agentsPath(), p.agentsPaths(),
            p.commandsPath(), p.commandsPaths(),
            p.skillsPath(), p.skillsPaths(),
            p.outputStylesPath(), p.outputStylesPaths(),
            p.commandsMetadata());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [MPL7] loadPluginHooks feed · 对齐 CC loadPluginHooks.ts:91-157
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [MPL7] 遍历 enabled 插件读 hooks/hooks.json 注册 GenericHook 执行路径。
     *
     * <p>对齐 CC loadPluginHooks（loadPluginHooks.ts:91-157）：{@code const { enabled } =
     * await loadAllPluginsCacheOnly()} → 每 enabled 插件读 hooks.json → convertPluginHooksToMatchers
     * → clear-then-register 原子换（:147-148）。缺 hooks/hooks.json 的插件静默跳过（CC loadPluginHooks.ts:125
     * {@code if (!plugin.hooksConfig) continue} —— 无 hooks 插件是常态，hooksConfig 仅在文件存在或 manifest
     * 声明时才填充，pluginLoader.ts:1617-1619）；manifest 声明 hooks 而文件缺失的 fail-loud（CC
     * loadPluginHooksFile :1228-1231 "Hooks file not found ... must exist"）留待接入 manifest hooks 字段后校验。
     *
     * <p><b>DEL-CFG-B（IMP-HOOKS-S1）单轨化</b>: 旧实现双轨 —— ① {@link HooksSettings}
     * PLUGIN_HOOK bySource 源写入（loadFromSource）供 UI/hook 列表分组展示; ②
     * {@link HookRegistry#registerPluginHook(String, String, GenericHook, HookEventType...)}
     * GenericHook 执行路径。Java 无 UI 消费端且 getAllHooks 折叠循环不含 PLUGIN_HOOK 源
     * （全仓 getFor(PLUGIN_HOOK) 仅 PluginLoader 内自写自读）→ ① 已删除，仅保留 ②
     * （CC 插件 hook 展示经 UI registeredHooks, hooksConfigManager.ts:270-365; Java 无对等物）。
     * 连带影响: 无 HooksSettings bean 时插件 hook 从"完全不注册"变为"正常注册 GenericHook"
     * （行为增强, 对齐 CC 无此依赖）。
     *
     * <p>保留链路: {@link HookRegistry#clearPluginHooks()}（clear-then-register 原子换 →
     * [IMP-HR-02 R-3] 同时清空 registeredHookMatchers 匹配 store, 对齐 CC loadPluginHooks.ts:147
     * clearRegisteredPluginHooks）→ {@link HookRegistry#registerPluginHook}（执行链 GenericHook）
     * + {@link HookRegistry#registerRegisteredHookMatcher}（匹配链, IMP-HR-02 接入, 对齐 CC
     * getHooksConfig registered 源 hooks.ts:1519-1529）注册全部 enabled 插件 →
     * {@link HooksConfigSnapshot#captureHooksConfigSnapshot()} 重捕获（引擎 settings 源数据,
     * HookMatcherEngine:79; registered 插件 matchers 由 HookRegistry.getMatchingHooks 并入引擎 3 参）。
     */
    public void loadPluginHooks() {
        List<PluginHookRegistration> registrations = new ArrayList<>();
        // [IMP-GAP03 补] 同插件同事件多 matcher 组注册名去重计数（每组调用内局部，clear-then-register 幂等）
        java.util.Map<String, Integer> regNameCounters = new java.util.HashMap<>();
        for (LoadedPlugin plugin : loadAllPluginsCacheOnly().enabled()) {
            if (plugin.localPath() == null) {
                continue; // 防御：feed 层 sourcePath 缺失已 miss 跳过
            }
            Path hooksJson = plugin.localPath().resolve("hooks").resolve("hooks.json");
            if (!Files.exists(hooksJson)) {
                // 对齐 CC loadPluginHooks.ts:125 if(!plugin.hooksConfig) continue —— 无 hooks 插件静默跳过
                // （manifest 声明 hooks 而文件缺失的 fail-loud 留待接入 manifest hooks 字段后校验）
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(hooksJson.toFile());
                JsonNode hooksNode = root.has("hooks") ? root.get("hooks") : root;
                if (hooksNode == null || !hooksNode.isObject()) {
                    continue;
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = hooksNode.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    HookEventType eventType = HookEventType.fromCcName(entry.getKey());
                    if (eventType == null || !entry.getValue().isArray()) {
                        continue;
                    }
                    for (JsonNode matcherNode : entry.getValue()) {
                        String matcher = matcherNode.path("matcher").asText("");
                        List<HookCommand> matcherHooks = new ArrayList<>();
                        for (JsonNode hookNode : matcherNode.path("hooks")) {
                            HookCommand cmd = parsePluginHookCommand(hookNode, plugin.name());
                            if (cmd != null) {
                                matcherHooks.add(cmd);
                            }
                        }
                        if (!matcherHooks.isEmpty()) {
                            // [IMP-GAP03 补] 同一插件同一事件可挂多个 matcher 组
                            //   （CC PluginHookMatcher[]，loadPluginHooks.ts:66-83 逐组 push），
                            //   HookRegistry.genericHooks 按名 put —— 同名后组覆盖前组
                            //   （仅最后一个 matcher 组存活，hook 丢失）。首组保持
                            //   plugin:{pluginName}:{event} 契约（HookRegistry:897 文档化），
                            //   同事件后续组追加序号去重（与 CC 逐组 push 语义一致）。
                            String baseName = "plugin:" + plugin.name() + ":" + eventType.ccName();
                            int groupIndex = regNameCounters.merge(baseName, 1, Integer::sum);
                            String regName = groupIndex == 1 ? baseName : baseName + ":" + groupIndex;
                            registrations.add(new PluginHookRegistration(
                                regName,
                                plugin.name(), eventType, matcher, List.copyOf(matcherHooks)));
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse hooks file " + hooksJson
                    + " for plugin " + plugin.name() + ": " + e.getMessage(), e);
            }
        }
        // 原子换：先清空旧插件 matchers，再注册全部 enabled 插件（CC loadPluginHooks.ts:147-148
        // clear-then-register）。
        //
        // [Wave2-J2] 插件 hook **只走统一 registered 匹配链**：删除旧的 command-only
        // genericHooks 双轨（{@code registerPluginHook} + {@code buildPluginGenericHook}）——
        //   ① 双轨只支持 command，prompt/agent/http/mcp_tool 会永不执行；
        //   ② 双轨与 matched 链并行时，HookRegistry.executeConfiguredHooks 必须跳过插件源
        //      matched hook 防双发，导致 5 类型插件 hook 全被跳过。
        // 现在统一由 HookRegistry.executeOneConfiguredHook 按实例类型分发，pluginRoot/pluginId
        // 经 MatchedHook 透传（command 执行器 env 需要），once 也统一在 executeConfiguredHooks 标记。
        HookRegistry registry = this.hookRegistry;
        if (registry != null) {
            registry.clearPluginHooks();
            for (PluginHookRegistration reg : registrations) {
                LoadedPlugin plugin = cache.get(reg.pluginName());
                String pluginRoot = plugin != null && plugin.localPath() != null
                    ? plugin.localPath().toString() : null;
                String pluginId = plugin != null && plugin.source() != null
                    ? plugin.source().name() : null;
                registry.registerRegisteredHookMatcher(reg.event(), reg.matcher(),
                    pluginRoot, pluginId, reg.pluginName(), null, reg.hooks());
            }
        }
        if (this.hooksConfigSnapshot != null) {
            hooksConfigSnapshot.captureHooksConfigSnapshot();
        }
        if (log.isInfoEnabled()) {
            log.info("[MPL7] loadPluginHooks: 注册 {} 个插件 hook matcher 组到统一 registered 链 (对齐 CC loadPluginHooks.ts:91)",
                registrations.size());
        }
    }

    /**
     * [MPL7] 插件 hook 注册条目（name/owner/event/matcher/命令列表）· 供 clear-then-register swap。
     *
     * <p>[IMP-GAP03] matcher 字段承载 CC PluginHookMatcher.matcher（loadPluginHooks.ts:74-81），
     * 由 {@link HookRegistry#getMatchingHooks} 统一链按事件 matchQuery 过滤（CC getMatchingHooks
     * hooks.ts:1683-1686）。
     *
     * <p>[Wave2-J2] hooks 从 {@code List<CommandHook>} 泛化为 {@code List<HookCommand>}：
     * 5 类（command/prompt/agent/http/mcp_tool）统一经 registered 匹配链执行。
     */
    private record PluginHookRegistration(String name, String pluginName, HookEventType event,
                                          String matcher, List<HookCommand> hooks) {}

    /**
     * [MPL7] 移除不再 enabled 插件的 hooks · 对齐 CC pruneRemovedPluginHooks（loadPluginHooks.ts:179-204）。
     *
     * <p>仅 prune 不移除新启用插件的 hooks（新插件等 /reload-plugins 全量 swap，与命令/agents/MCP
     * 行为一致）。从 clearAllCaches 级联调用，使卸载/禁用插件立即停止触发。
     *
     * <p><b>DEL-CFG-B（IMP-HOOKS-S1）</b>: HooksSettings PLUGIN_HOOK 源级 prune
     * （getFor 读 + 守卫 + 写回）已删除 —— 源存储已移除; 保留 registry 侧 prune
     * （pluginHookOwners 跟踪）与快照重捕获。
     */
    public void pruneRemovedPluginHooks() {
        Set<String> enabledNames = new LinkedHashSet<>();
        for (LoadedPlugin p : loadAllPluginsCacheOnly().enabled()) {
            enabledNames.add(p.name());
        }
        // [Wave2-J2] 插件 hook 只存在于 registered 匹配链 ⇒ 必须无条件 prune（旧守卫
        //   `!pluginHookNames().isEmpty()` 在无 genericHooks 时会跳过 registered matcher 剪除）。
        HookRegistry registry = this.hookRegistry;
        if (registry != null) {
            registry.prunePluginHooks(enabledNames);
        }
        if (this.hooksConfigSnapshot != null) {
            hooksConfigSnapshot.captureHooksConfigSnapshot();
        }
        if (log.isInfoEnabled()) {
            log.info("[MPL7] pruneRemovedPluginHooks: 已完成 registered 插件 matcher 剪除 (禁用/卸载插件 hooks 移除, CC loadPluginHooks.ts:179-204)");
        }
    }

    /**
     * [MPL7] 刷新启用插件 · 对齐 CC refreshActivePlugins（refresh.ts:72-186）。
     *
     * <p>clearAllCaches → loadAllPlugins 全量新鲜枚举（预热 cacheOnly）→ loadPluginHooks 全量 swap。
     * loadPluginHooks 失败仅登记 hook_load_failed，不丢失插件/命令/agent 数据（refresh.ts:152-161）。
     *
     * <p><b>[P2-24 · 2026-09-11 修复] 首步必须是 {@code clearAllCaches()} 级联，而非只清 feed 单槽。</b>
     * CC {@code refreshActivePlugins} 第一句即 {@code clearAllCaches()}（refresh.ts:74-79，README 注释
     * 「Clears ALL plugin caches (unlike the old needsRefresh path which only cleared loadAllPlugins and
     * returned stale data from downstream memoized loaders)」）——该级联含 {@code clearCommandsCache}
     * （cacheUtils.ts:46），而 skill 候选列表正是经命令 memoize 层枚举的。修复前本方法只做
     * {@code clearPluginCache + pruneRemovedPluginHooks}：feed 缓存清了，但<b>命令/技能 memoize 没清</b>
     * → {@code /reload-plugins} 时技能列表压根没重新枚举（审计 P2-24）。
     *
     * <p>未注入 {@link PluginCacheUtils}（直构/单测）→ 回退原有的 feed 单槽清空路径，保证既有测试
     * 与无 Spring 场景行为不退化。
     */
    public void refreshActivePlugins() {
        // CC refresh.ts:74 clearAllCaches() —— 必须打头，且必须是级联（见方法 javadoc P2-24 段）。
        PluginCacheUtils cacheUtils = this.pluginCacheUtils;
        if (cacheUtils != null) {
            cacheUtils.clearAllCaches();
        } else {
            // 未注入 → 退化路径（等价修复前的行 1-2；丢失命令/agent/prompt/skill_listing 级联）。
            if (log.isDebugEnabled()) {
                log.debug("[P2-24] refreshActivePlugins: PluginCacheUtils 未注入 → 仅清 feed 单槽（退化路径）");
            }
            clearPluginCache("refreshActivePlugins");
            pruneRemovedPluginHooks();
        }
        PluginLoadResult result = loadAllPlugins();
        try {
            loadPluginHooks();
        } catch (RuntimeException e) {
            log.warn("[MPL7] refreshActivePlugins: loadPluginHooks 失败 (hook_load_failed 登记, "
                + "不丢失插件/命令/agent 数据): {}", e.getMessage());
        }
        if (log.isInfoEnabled()) {
            log.info("[MPL7] refreshActivePlugins: {} enabled, {} disabled (CC refresh.ts:72-186)",
                result.enabled().size(), result.disabled().size());
        }
    }

    /**
     * 解析插件 hooks.json 单个 hook 条目 → {@link HookCommand}（CC 5 类 discriminated union）。
     *
     * <p>[Wave2-J1] 插件 hooks.json 是 <b>snake_case</b> 变体（cc exe off≈317,019,038）：
     * {@code on_failure / status_message / async_rewake / rewake_message / rewake_summary / args /
     * continue_on_block / allowed_env_vars}；{@code on_failure} 缺省/非法值是 <b>block</b>
     * （CC {@code .nullish().catch("block")}），与 settings camelCase 缺省 {@code continue} 不同。
     * 两种写法都接受（旧插件配置不静默失效）。
     *
     * <p>[Wave2-J2] 支持 5 类：command / prompt / agent / http / mcp_tool；缺必填字段 →
     * warn + 返回 null（调用方跳过），⛔ 不猜默认值。无 {@code type} 的字符串简写按 command。
     *
     * @param hookNode   hooks.json 单个 hook 条目
     * @param pluginName 插件名（日志用）
     * @return 对应 HookCommand；未知 type / 缺必填 → null（调用方跳过）
     */
    static HookCommand parsePluginHookCommand(JsonNode hookNode, String pluginName) {
        if (hookNode.isTextual()) {
            return new CommandHook(hookNode.asText(), null, null, null, null, null, null, null,
                null, "block", null, null, null);
        }
        String type = firstText(hookNode, "type");
        if (type == null || type.isBlank()) {
            type = "command"; // 无判别器的旧条目按 command（CC settings 对象形同）
        }
        String ifCondition = firstText(hookNode, "if");
        Integer timeout = hookNode.path("timeout").isInt() ? hookNode.path("timeout").asInt() : null;
        String statusMessage = firstText(hookNode, "status_message", "statusMessage");
        Boolean once = hookNode.path("once").isBoolean() ? hookNode.path("once").asBoolean() : null;
        switch (type) {
            case "command": {
                String command = firstText(hookNode, "command");
                if (command == null || command.isBlank()) {
                    log.warn("[MPL7] 插件 {} 的 command hook 缺 command 字段，跳过", pluginName);
                    return null;
                }
                JsonNode argsNode = hookNode.path("args");
                List<String> args = null;
                if (argsNode.isArray()) {
                    args = new ArrayList<>();
                    for (JsonNode a : argsNode) {
                        if (a.isTextual()) {
                            args.add(a.asText());
                        }
                    }
                }
                return new CommandHook(command, ifCondition, firstText(hookNode, "shell"), timeout,
                    statusMessage, once, boolOrNull(hookNode, "async"),
                    boolOrNull(hookNode, "async_rewake", "asyncRewake"), args,
                    pluginOnFailure(hookNode), firstText(hookNode, "rewake_message", "rewakeMessage"),
                    firstText(hookNode, "rewake_summary", "rewakeSummary"), null);
            }
            case "prompt": {
                String prompt = firstText(hookNode, "prompt");
                if (prompt == null || prompt.isBlank()) {
                    log.warn("[MPL7] 插件 {} 的 prompt hook 缺 prompt 字段，跳过", pluginName);
                    return null;
                }
                return new PromptHook(prompt, ifCondition, timeout, firstText(hookNode, "model"),
                    statusMessage, once,
                    boolOrNull(hookNode, "continue_on_block", "continueOnBlock"));
            }
            case "agent": {
                String prompt = firstText(hookNode, "prompt");
                if (prompt == null || prompt.isBlank()) {
                    log.warn("[MPL7] 插件 {} 的 agent hook 缺 prompt 字段，跳过", pluginName);
                    return null;
                }
                return new AgentHook(prompt, ifCondition, timeout, firstText(hookNode, "model"),
                    statusMessage, once);
            }
            case "http": {
                String url = firstText(hookNode, "url");
                if (url == null || url.isBlank()) {
                    log.warn("[MPL7] 插件 {} 的 http hook 缺 url 字段，跳过", pluginName);
                    return null;
                }
                return new HttpHook(url, ifCondition, timeout, headersMap(hookNode),
                    stringList(hookNode, "allowed_env_vars", "allowedEnvVars"), statusMessage, once,
                    pluginOnFailure(hookNode));
            }
            case "mcp_tool": {
                String server = firstText(hookNode, "server");
                String tool = firstText(hookNode, "tool");
                if (server == null || server.isBlank() || tool == null || tool.isBlank()) {
                    log.warn("[MPL7] 插件 {} 的 mcp_tool hook 缺 server/tool 字段，跳过", pluginName);
                    return null;
                }
                return new McpToolHook(server, tool, objectMap(hookNode, "input"), ifCondition,
                    timeout, statusMessage, once);
            }
            default:
                log.warn("[MPL7] 插件 {} 的 hook type={} 未知（CC discriminatedUnion 仅 5 类），跳过该条",
                    pluginName, type);
                return null;
        }
    }

    /** 插件侧 {@code on_failure}：缺省/非法 → block（CC {@code .nullish().catch("block")}）。 */
    private static String pluginOnFailure(JsonNode hookNode) {
        String onFailure = firstText(hookNode, "on_failure", "onFailure");
        if ("continue".equals(onFailure) || "block".equals(onFailure)) {
            return onFailure;
        }
        return "block";
    }

    /** 插件 http hook {@code headers}：对象 → Map&lt;String,String&gt;（非文本值跳过）。 */
    private static Map<String, String> headersMap(JsonNode hookNode) {
        JsonNode h = hookNode.path("headers");
        if (!h.isObject()) {
            return null;
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = h.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        }
        return out;
    }

    /** 字符串数组字段（snake/camel 两写法）；缺失/非数组 → null。 */
    private static List<String> stringList(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && v.isArray()) {
                List<String> out = new ArrayList<>();
                for (JsonNode item : v) {
                    if (item.isTextual()) {
                        out.add(item.asText());
                    }
                }
                return out;
            }
        }
        return null;
    }

    /** 对象字段 → Map&lt;String,Object&gt;；缺失/非对象 → null。 */
    private static Map<String, Object> objectMap(JsonNode node, String name) {
        JsonNode v = node.get(name);
        if (v == null || !v.isObject()) {
            return null;
        }
        try {
            return JSON.convertValue(v,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** snake_case 优先、camelCase 兼容取文本；缺失/非文本 → null。 */
    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && v.isTextual()) {
                return v.asText();
            }
        }
        return null;
    }

    /** 布尔取 null-safe（snake/camel 两写法）。 */
    private static Boolean boolOrNull(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode v = node.get(name);
            if (v != null && v.isBoolean()) {
                return v.asBoolean();
            }
        }
        return null;
    }
}
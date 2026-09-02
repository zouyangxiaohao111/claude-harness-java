package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.MarkdownConfigLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 从文件系统加载 Agent 定义 · 对齐 CC loadAgentsDir.ts
 */
public class loadAgentsDir {

    private static final Logger log = LoggerFactory.getLogger(loadAgentsDir.class);
    private static final String AGENTS_DIR = "agents";

    /**
     * [FIX-AM REQ-M-19] per-baseDir 一致性缓存 · 对齐 CC getAgentDefinitionsWithOverrides =
     * memoize(cwd)（loadAgentsDir.ts:296）+ clearAgentDefinitionsCache（:395）。
     *
     * <p>CC memoize key = cwd；Java load(baseDir, source) 单 source 加载，key 取
     * baseDir 绝对路径 + source（同一 baseDir 不同 source 是不同 agent 集）。值存
     * 快照初始化后的完整结果；{@link #load} 返回防御性 copy 防跨调用污染
     * （initializeAgentMemorySnapshots 原地替换 pendingSnapshotUpdate entry）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, AgentDefinition>> LOAD_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 对齐 CC clearAgentDefinitionsCache（loadAgentsDir.ts:395-398）· 配置/CLAUDE.md 变更后失效。
     *
     * <p>[IMP-SUB-09 REWORK R2] 联动清空 {@code MarkdownConfigLoader} memoize：多源路径
     * {@link #loadAllSources} 的文件发现层复用 {@code MarkdownConfigLoader.loadMarkdownFilesForSubdir}
     * 的 memoize（键 "{subdir}:{cwd}"，MarkdownConfigLoader.java:428-430）——若仅清
     * {@link #LOAD_CACHE}（单源 {@link #load} 缓存）而 MarkdownConfigLoader 不清，则
     * loadAllSources 返回陈旧文件列表（磁盘 agent 变更不可见）。
     *
     * <p>Java 架构下 CC 的 {@code getAgentDefinitionsWithOverrides.cache}（loadAgentsDir.ts:395-396）
     * 与 {@code loadMarkdownFilesForSubdir.cache}（loadSkillsDir.ts:808）两层被压平为
     * MarkdownConfigLoader 一层 —— 清 agent 定义缓存必须连带清该层。生产级联
     * {@code PluginCacheUtils.clearAllCaches} 本已先经 {@code SkillRegistry.refresh()} →
     * {@code MarkdownConfigLoader.clearCache()} 双清（该调用序已复核属实，非假设）；此处自含联动
     * 兜底，调用方无需记得先调 refresh/watcher。
     */
    public static void clearCache() {
        LOAD_CACHE.clear();
        MarkdownConfigLoader.clearCache();
        if (log.isDebugEnabled()) {
            log.debug("[loadAgentsDir] 清空 load 缓存 + MarkdownConfigLoader memoize（对齐 CC clearAgentDefinitionsCache + clearSkillCaches 双清）");
        }
    }

    /**
     * AGENT_MEMORY_SNAPSHOT 特性门 · 对齐 CC {@code feature('AGENT_MEMORY_SNAPSHOT')}
     * （loadAgentsDir.ts:348 / main.tsx:2258，bun:bundle 编译期常量）。
     *
     * <p>Java 无 bun:bundle 编译期机制，用 {@code nexusai.feature.agent-memory-snapshot}
     * 系统属性/环境变量建模（truthy = 启用）。<b>默认 true</b>：CC 编译期 define 值不可知，
     * 按"默认开启"对齐。BD-⊕2 自 {@code BundledSkillEnabledGates}（skill 域）越界挂靠迁入
     * 本类（agent/subagent 域），语义不变。
     *
     * @return true = 快照初始化（initializeAgentMemorySnapshots）启用
     */
    private static boolean isAgentMemorySnapshotEnabled() {
        String sysProp = System.getProperty("nexusai.feature.agent-memory-snapshot");
        if (sysProp != null && !sysProp.isBlank()) {
            return com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(sysProp);
        }
        String envVal = System.getenv("NEXUSAI_FEATURE_AGENT_MEMORY_SNAPSHOT");
        if (envVal != null && !envVal.isBlank()) {
            return com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(envVal);
        }
        return true;
    }

    /**
     * 从文件系统加载自定义 Agent 定义 · 对齐 CC loadAgentsDir.ts getAgentDefinitionsWithOverrides (:296+)
     * + loadMarkdownFilesForSubdir (markdownConfigLoader.ts:330-370).
     *
     * <p><b>[Session S1 P0-3 激活]</b>: 旧实现是 dead code (grep 全仓 0 调用方), {@code .claude/agents/*.md}
     * 被完全忽略. 本期由 {@code SubagentTool} 构造器接线调用, 激活自定义 agent 加载.
     *
     * <p>source 入参对齐 CC {@code SettingSource} (markdownConfigLoader.ts:342/352/365): 每个来源目录
     * 加载时打上来源标记 ({@code userSettings}/{@code projectSettings}/{@code policySettings}/
     * {@code flagSettings}). 旧实现 source 硬编码 "userSettings" 无法区分来源.
     * (CC 6 组 getActiveAgentsFromList 覆盖合并 (loadAgentsDir.ts:193-221) 由本文件
     * {@link #getActiveAgentsFromList} (:1207) 实现, 生产由 {@code AgentDefinitionRegistry.merge()}
     * 调用 ([ODF-C3] 复活, AgentDefinitionRegistry.java:129); 本方法单 source 加载只打来源标记,
     * 覆盖合并统一交给 registry)
     *
     * <p><b>[IMP-SUB-09 D8]</b>: 本方法单源加载（baseDir 一个来源目录，递归发现 .md）。
     * 全源（managed/user/project）递归加载走 {@link #loadAllSources}（生产接线目标，对齐 CC
     * loadMarkdownFilesForSubdir 三源），本方法保留供单源/测试场景使用。
     *
     * <p><b>[T3 内容读兼容]</b>：用户源（source={@code userSettings}）双目录 —— baseDir 为 nexusai
     * 自有根（~/.{appName}）或 claude 根（~/.claude）时，先 {@link #loadSingle nexusai} 再
     * {@link #loadSingle claude}，合并时 nexusai 已注册 agentType 用 putIfAbsent 保留（claude 同名
     * 丢弃）；nexusai 无同名则 claude 正常加载。baseDir 为其他目录（测试临时目录等）保持单目录语义。
     *
     * @param baseDir 包含 {@code agents} 子目录的配置根目录 (CC baseDir 语义: 如 {@code ~/.claude})
     * @param source  CC original: source (SettingSource) - 加载来源, 写入每个 CustomAgentDefinition
     * @return agentType -> AgentDefinition 映射; 目录不存在时返回空 Map
     */
    public static Map<String, AgentDefinition> load(Path baseDir, String source) {
        // T3: 内容读兼容（nexusai 复刻版 .claude 改造）—— 用户源双目录：nexusai 自有根优先 + claude 回落。
        //   baseDir 为任一用户配置根时加载双目录；否则保持单目录加载语义（测试临时目录等）。
        if ("userSettings".equals(source) && baseDir != null) {
            Path nexusaiBase = Path.of(NexusaiPaths.getAppConfigHomeDir());
            Path claudeBase = Path.of(ClaudePaths.getClaudeConfigHomeDir());
            if (pathEqualsNormalized(baseDir, nexusaiBase) || pathEqualsNormalized(baseDir, claudeBase)) {
                Map<String, AgentDefinition> merged = new HashMap<>();
                // 先 nexusai（高优先，putIfAbsent 保留已注册 agentType）
                for (Map.Entry<String, AgentDefinition> e : loadSingle(nexusaiBase, source).entrySet()) {
                    merged.putIfAbsent(e.getKey(), e.getValue());
                }
                // 再 claude（回落，同 agentType 时 nexusai 已注册 → 丢弃；nexusai 无同名则 claude 加载）
                for (Map.Entry<String, AgentDefinition> e : loadSingle(claudeBase, source).entrySet()) {
                    merged.putIfAbsent(e.getKey(), e.getValue());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[loadAgentsDir] 用户源双目录合并: nexusai={} claude={} → {} 个 agent（T3 内容读兼容，nexusai 优先）",
                        nexusaiBase, claudeBase, merged.size());
                }
                return merged;
            }
        }
        return loadSingle(baseDir, source);
    }

    /**
     * 单源加载原语 · {@link #load} 双目录用户源（nexusai + claude）的实际加载体；baseDir 一个来源目录
     * （如 {@code ~/.claude} 或 {@code ~/.{appName}}），递归发现 agents/*.md。缓存（CC memoize:296）、
     * 快照初始化、失败回退语义与原单源 load 一致。
     */
    private static Map<String, AgentDefinition> loadSingle(Path baseDir, String source) {
        // [FIX-AM REQ-M-19] per-baseDir 一致性缓存（CC memoize:296）· 命中直接返回防御性 copy
        String cacheKey = (baseDir != null ? baseDir.toAbsolutePath().normalize().toString() : "<null>")
            + "|" + (source != null ? source : "<null>");
        Map<String, AgentDefinition> cached = LOAD_CACHE.get(cacheKey);
        if (cached != null) {
            if (log.isDebugEnabled()) {
                log.debug("[loadAgentsDir] 命中 load 缓存: baseDir={} source={} agents={}",
                    baseDir, source, cached.size());
            }
            return new HashMap<>(cached);
        }
        Map<String, AgentDefinition> agents = new HashMap<>();
        if (baseDir == null) return agents;
        Path agentsDir = baseDir.resolve(AGENTS_DIR);
        if (!Files.isDirectory(agentsDir)) {
            if (log.isDebugEnabled()) {
                log.debug("[loadAgentsDir] agents 目录不存在, 跳过: {} (source={})", agentsDir, source);
            }
            return agents;
        }
        // [IMP-SUB-09 D8] 递归加载 .claude/agents/**/*.md（对齐 CC loadMarkdownFiles ripgrep
        //   {@code --files --hidden --follow --no-ignore --glob '*.md'} 递归扫描，
        //   markdownConfigLoader.ts:564-568）。旧实现 Files.list 仅顶层 → 子目录 agent .md
        //   被忽略（△-2 多目录递归缺口之一）。
        for (Path p : listMarkdownFilesRecursively(agentsDir)) {
            try {
                AgentDefinition def = loadAgentFile(p, baseDir, source);
                if (def != null) {
                    agents.put(def.agentType(), def);
                    log.info("[loadAgentsDir] 加载自定义 agent: {} 来自 {} (source={})",
                        def.agentType(), p, source);
                }
            } catch (Exception e) {
                log.warn("[loadAgentsDir] 加载 agent 失败 {}: {}", p, e.getMessage());
            }
        }
        // [FIX-AM REQ-M-19] 快照初始化双门控（对齐 CC loadAgentsDir.ts:348
        //   feature('AGENT_MEMORY_SNAPSHOT') && isAutoMemoryEnabled()）。旧实现仅 isAutoMemoryEnabled
        //   单门控（OPD-M-34/36 收敛）；本期补 AGENT_MEMORY_SNAPSHOT 等价门
        //   （本类 isAgentMemorySnapshotEnabled，默认 true，BD-⊕2 自 BundledSkillEnabledGates 迁入）。
        if (isAgentMemorySnapshotEnabled()
                && com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            try {
                initializeAgentMemorySnapshots(agents);
            } catch (Exception e) {
                // [AM-03/OPD-R2-AM-03] CC getAgentDefinitionsWithOverrides 外层 catch（:379-391）：
                //   快照初始化失败 → 整体回退 built-ins（Java 表达：custom agents 空 map，built-ins
                //   由 AgentDefinitionRegistry 独立合并）+ 失败日志。旧 Java 异常冒泡到调用方 → RED。
                //   回退结果同样入缓存（CC memoize 缓存 fallback 结果 :384-390）。
                log.warn("[loadAgentsDir] 快照初始化失败，custom agents 回退空集（对齐 CC :379-391 built-ins 回退）: {}",
                    e.getMessage());
                LOAD_CACHE.put(cacheKey, new HashMap<>());
                return new HashMap<>();
            }
        }
        // 缓存快照初始化后的完整结果（对齐 CC memoize 缓存 getAgentDefinitionsWithOverrides 全量）
        LOAD_CACHE.put(cacheKey, agents);
        return new HashMap<>(agents);
    }

    /**
     * [IMP-SUB-09 D8] 从全部配置源递归加载自定义 agent · 对齐 CC getAgentDefinitionsWithOverrides
     * 主链路（loadAgentsDir.ts:308）+ loadMarkdownFilesForSubdir（markdownConfigLoader.ts:297-430）。
     *
     * <p><b>修复 △-2（HIGH，生产仅 userSettings）</b>：旧实现 SubagentTool 只调
     * {@code load(workspaceDir, "userSettings")}，managed（policySettings）/ project（projectSettings）
     * agents 从不加载。本方法经 {@code MarkdownConfigLoader.loadMarkdownFilesForSubdir("agents", cwd)}
     * 全源加载（该加载器已实现 CC 多目录递归 + realpath 去重 + worktree 主仓回退 + tengu_dir_search
     * 遥测，MarkdownConfigLoader.java:223-265）：
     * <ul>
     *   <li><b>managedDir</b> → source {@code policySettings}（恒载，markdownConfigLoader.ts:337-345）</li>
     *   <li><b>userDir</b> → source {@code userSettings}（条件载，:346-356）</li>
     *   <li><b>projectDirs</b>（cwd 向上至 git root/home）→ source {@code projectSettings}（条件载，:357-372）</li>
     * </ul>
     * 每个 MarkdownFile 经 {@link #parseAgentFromMarkdown}（5 参签名对齐 CC :541-547）转 AgentDefinition，
     * source 已打标；CC :310-338 失败聚合通道（failedFiles）Java 侧仅 log（✗-1 登记，非本任务范围）。
     *
     * <p>memory 快照初始化双门控 + 失败回退空集（对齐 CC :348-354 + :379-391，与 {@link #load} 同构）。
     * 文件发现层由 MarkdownConfigLoader memoize（键 "{subdir}:{cwd}"，:428-430）承接，本方法不重复缓存。
     *
     * @param cwd 项目遍历起始目录（CC :296 cwd 入参；null → user.dir 兜底）
     * @return 全源自定义 agent 列表（source ∈ policySettings/userSettings/projectSettings）
     */
    public static List<AgentDefinition> loadAllSources(Path cwd) {
        String cwdStr = cwd != null
            ? cwd.toAbsolutePath().normalize().toString()
            : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
        List<AgentDefinition> agents = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        try {
            List<MarkdownConfigLoader.MarkdownFile> files =
                MarkdownConfigLoader.loadMarkdownFilesForSubdir("agents", cwdStr);
            for (MarkdownConfigLoader.MarkdownFile mf : files) {
                try {
                    AgentDefinition def = parseAgentFromMarkdown(mf.filePath(), mf.baseDir(),
                        mf.frontmatter(), mf.content(), mf.source());
                    if (def != null) {
                        agents.add(def);
                        log.info("[loadAgentsDir] 加载自定义 agent: {} 来自 {} (source={})",
                            def.agentType(), mf.filePath(), mf.source());
                    } else {
                        // CC loadAgentsDir.ts:321-338：非 agent markdown 静默跳过，仅「像 agent 尝试」
                        // （name truthy）计入 failedFiles。
                        // [IMP-SUB-09 REWORK R1] 用 JS falsy 语义对齐 CC !frontmatter['name']（:324）——
                        //   name 空串 ""/false/0/NaN 时 CC 静默跳过（!'' 为 truthy → 不计入 failedFiles），
                        //   Java 旧 `!= null` 会把空串误报 failed。
                        if (!isFalsy(mf.frontmatter().get("name"))) {
                            failedFiles.add(mf.filePath());
                            log.warn("[loadAgentsDir] 解析 agent 失败: {} (source={})", mf.filePath(), mf.source());
                        }
                    }
                } catch (Exception e) {
                    failedFiles.add(mf.filePath());
                    log.warn("[loadAgentsDir] 加载 agent 失败 {}: {}", mf.filePath(), e.getMessage());
                }
            }
        } catch (Exception e) {
            // 对齐 CC getAgentDefinitionsWithOverrides 外层 catch（:379-391）：整体失败 → built-ins 回退
            // （Java 表达：custom agents 空列表，built-ins 由 AgentDefinitionRegistry 独立合并）。
            log.warn("[loadAgentsDir] 多源加载 agent 定义失败，custom agents 回退空集（对齐 CC :379-391 built-ins 回退）: {}",
                e.getMessage());
            return List.of();
        }
        if (!failedFiles.isEmpty()) {
            log.warn("[loadAgentsDir] 多源加载 {} 个 agent 文件失败: {} (对齐 CC failedFiles 聚合通道，✗-1)",
                failedFiles.size(), failedFiles);
        }
        // memory 快照初始化双门控（对齐 CC loadAgentsDir.ts:348 feature('AGENT_MEMORY_SNAPSHOT')
        //   && isAutoMemoryEnabled()，与 load() 同构）。失败 → 回退空集（CC :379-391）。
        if (isAgentMemorySnapshotEnabled()
                && com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            try {
                // [IMP-SUB-09 REWORK R2-WF-E] 快照折叠同构修正：旧 byType.put last-wins 在序
                //   [managed,user,project]（本方法 agents 序）下 project 胜出 → managed agent
                //   在快照分支被 user/project 覆盖丢失，返回列表缺 managed 版本 —— 下游
                //   AgentDefinitionRegistry 构造器再按 6 组优先级折叠也救不回已丢的 agent。
                //   折叠先经 getActiveAgentsFromList（对齐 CC loadAgentsDir.ts:193-221，
                //   managed>flag>project>user>plugin>builtIn）再入 map，与构造路径同语义，
                //   保证 managed 定义跨快照分支与普通分支一致胜出。
                Map<String, AgentDefinition> byType = new LinkedHashMap<>();
                for (AgentDefinition a : loadAgentsDir.getActiveAgentsFromList(agents)) {
                    byType.put(a.agentType(), a);
                }
                initializeAgentMemorySnapshots(byType);
                if (log.isDebugEnabled()) {
                    log.debug("[loadAgentsDir] loadAllSources: 快照初始化完成，{} 个 agent", byType.size());
                }
                return new ArrayList<>(byType.values());
            } catch (Exception e) {
                log.warn("[loadAgentsDir] 快照初始化失败，custom agents 回退空集（对齐 CC :379-391 built-ins 回退）: {}",
                    e.getMessage());
                return List.of();
            }
        }
        return agents;
    }

    /**
     * 检查并初始化 agent memory 快照 · 对齐 CC initializeAgentMemorySnapshots
     * （loadAgentsDir.ts:262-294，生产默认路径/门控）。
     *
     * <p>仅 memory=='user' 的 agent：无本地 .md → initializeFromSnapshot（首次从项目快照拷贝）；
     * 快照比 syncedFrom 新 → 设 pendingSnapshotUpdate（prompt-update 状态，前端 dialog 消费 N/A）。
     * 生产默认 AgentMemoryDirectory + AgentMemorySnapshot（cwd = per-session projectRoot）。
     *
     * @param agents agentType → AgentDefinition 映射（可变，prompt-update 时原地替换为携带
     *               pendingSnapshotUpdate 的新实例）
     */
    public static void initializeAgentMemorySnapshots(Map<String, AgentDefinition> agents) throws IOException {
        com.nexusai.application.agent.agent.AgentMemoryDirectory dir =
            com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault();
        com.nexusai.application.agent.agent.AgentMemorySnapshot snapshot =
            new com.nexusai.application.agent.agent.AgentMemorySnapshot(
                // [AM-01/OPD-R2-AM-01] 快照 cwd 基改 per-session projectRoot（对齐 ODF-A1：
                //   与 AgentMemoryDirectory.productionDefault 同源）。旧实现 System.getProperty
                //   ("user.dir") 进程级 → session root ≠ user.dir 时快照目录错位（?-1）。
                com.nexusai.application.agent.memory.AutoMemPaths::currentSessionProjectRoot, dir);
        initializeAgentMemorySnapshots(agents, dir, snapshot);
    }

    /**
     * 可注入版快照初始化（测试用）· 语义同上，仅快照目录/agent memory 目录可注入临时目录。
     */
    static void initializeAgentMemorySnapshots(Map<String, AgentDefinition> agents,
                                               com.nexusai.application.agent.agent.AgentMemoryDirectory dir,
                                               com.nexusai.application.agent.agent.AgentMemorySnapshot snapshot) throws IOException {
        if (agents == null || agents.isEmpty()) {
            return;
        }
        // [F-1 登记 · IMP-MV2-40] △-1：本循环串行 for vs CC `await Promise.all(agents.map(...))`
        //   （loadAgentsDir.ts:265-266 并行初始化）—— 各 agent 快照互不依赖，结果等价（顺序无关），
        //   仅大 agent 数下初始化耗时差异 —— 登记不修。
        for (java.util.Map.Entry<String, AgentDefinition> entry
                : new ArrayList<>(agents.entrySet())) {
            AgentDefinition agent = entry.getValue();
            if (!"user".equals(agent.memory().orElse(null))) {
                continue;
            }
            com.nexusai.application.agent.agent.AgentMemorySnapshot.SnapshotCheckResult result =
                snapshot.checkAgentMemorySnapshot(
                    agent.agentType(), com.nexusai.application.agent.agent.AgentMemoryDirectory.AgentMemoryScope.USER);
            switch (result.action()) {
                case "initialize":
                    if (log.isDebugEnabled()) {
                        log.debug("[loadAgentsDir] 初始化 {} agent memory 从项目快照 (snapshot={})",
                            agent.agentType(), result.snapshotTimestamp());
                    }
                    snapshot.initializeFromSnapshot(agent.agentType(),
                        com.nexusai.application.agent.agent.AgentMemoryDirectory.AgentMemoryScope.USER,
                        result.snapshotTimestamp());
                    break;
                case "prompt-update":
                    AgentDefinition updated = withPendingSnapshotUpdate(agent, result.snapshotTimestamp());
                    if (updated != null) {
                        agents.put(entry.getKey(), updated);
                    }
                    log.info("[loadAgentsDir] {} agent memory 有更新快照可用 (snapshot={})",
                        agent.agentType(), result.snapshotTimestamp());
                    break;
                default:
                    // 'none'：无快照或已同步，无操作
                    break;
            }
        }
    }

    /** prompt-update → 重建 CustomAgentDefinition 携带 pendingSnapshotUpdate（record 不可变，CC 对象字面量可变）。 */
    private static AgentDefinition withPendingSnapshotUpdate(AgentDefinition agent, String snapshotTimestamp) {
        if (!(agent instanceof AgentDefinition.CustomAgentDefinition c)) {
            return null;
        }
        return AgentDefinition.CustomAgentDefinition.builder(
                c.agentType(), c.whenToUse(), c.source(), c.getSystemPrompt(null, List.of()))
            .tools(c.tools().orElse(null))
            .disallowedTools(c.disallowedTools().orElse(null))
            .skills(c.skills().orElse(null))
            .mcpServers(c.mcpServers().orElse(null))
            .hooks(c.hooks().orElse(null))
            .color(c.color().orElse(null))
            .model(c.model().orElse(null))
            .effort(c.effort().orElse(null))
            .permissionMode(c.permissionMode().orElse(null))
            .maxTurns(c.maxTurns().orElse(null))
            .filename(c.filename().orElse(null))
            .baseDir(c.baseDir().orElse(null))
            .criticalSystemReminder_EXPERIMENTAL(c.criticalSystemReminder_EXPERIMENTAL().orElse(null))
            .requiredMcpServers(c.requiredMcpServers().orElse(null))
            .background(c.background().orElse(null))
            .initialPrompt(c.initialPrompt().orElse(null))
            .memory(c.memory().orElse(null))
            .isolation(c.isolation().orElse(null))
            .pendingSnapshotUpdate(snapshotTimestamp)
            .omitClaudeMd(c.omitClaudeMd().orElse(null))
            .build();
    }

    /**
     * [IMP-SUB-09 D8] 递归列出 agents 目录下所有 markdown 文件 · 对齐 CC loadMarkdownFiles
     * （ripgrep {@code --files --hidden --follow --no-ignore --glob '*.md'}，markdownConfigLoader.ts:564-568；
     * 与 skill 域 {@code MarkdownConfigLoader.listMarkdownFiles} 同构，均仅匹配 {@code .md}）。
     *
     * <p><b>[IMP-SUB-09 REWORK R3]</b>: 扩展名收敛为仅 {@code .md} —— 旧实现还接受
     * {@code .markdown}，而 CC ripgrep glob 与 native walk 均只匹配 {@code *.md}
     * （markdownConfigLoader.ts:507/:519/:565）——生产会加载 CC 会忽略的 {@code .markdown} 文件，
     * 且与 skill 域 {@code MarkdownConfigLoader.listMarkdownFiles}（仅 {@code .md}）构成双轨矛盾。
     * 已收敛消除双路径。
     *
     * <p>Java 用 {@code Files.walk} 递归（默认不 follow symlink，避免循环）；CC {@code --follow}
     * 会跟随 symlink（native walk 以 dev:ino/realpath 循环检测），差异登记 concerns（Java 侧对称
     * 实现见 MarkdownConfigLoader.listMarkdownFiles，走 FOLLOW_LINKS + visitedDirs）。目录不存在 → 空列表。
     *
     * @param agentsDir agents 配置根目录
     * @return 递归发现的 markdown 文件（自然序稳定输出）
     */
    private static List<Path> listMarkdownFilesRecursively(Path agentsDir) {
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(agentsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md"))
                  .forEach(files::add);
        } catch (IOException e) {
            log.warn("[loadAgentsDir] 递归列出 agents 目录失败 {}: {}", agentsDir, e.getMessage());
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /**
     * 解析单个 agent markdown 文件 · 对齐 CC parseAgentFromMarkdown (loadAgentsDir.ts:541-755)。
     *
     * <p>读文件 + 解析 frontmatter 后委托 {@link #parseAgentFromMarkdown}（frontmatter/content 入参），
     * 供单源 {@link #load} 使用；多源 {@link #loadAllSources} 直接复用 parse 核心，避免对已由
     * {@code MarkdownConfigLoader} 解析过的 MarkdownFile 二次读文件 + 二次 frontmatter 解析。
     *
     * @param file   agent markdown 文件路径
     * @param baseDir CC original: baseDir (loadAgentsDir.ts:543 入参) — 写入 CustomAgentDefinition.baseDir
     * @param source CC original: source (SettingSource)
     * @return 解析出的 {@link AgentDefinition}; 缺 name/description/无效 frontmatter 时 return null
     */
    private static AgentDefinition loadAgentFile(Path file, Path baseDir, String source) throws IOException {
        String content = Files.readString(file);
        // P1-1: 接入统一 frontmatter-config 管线（CC loadAgentsDir.ts:308 复用 parseFrontmatter）
        //   —— 自建 SnakeYAML 解析器已删除（DEL-01），改用 ParseSkillFrontmatter.parseFrontmatter
        //   （quoteProblemativeValues 重试 + 不 trim content + 空 Map 兜底，frontmatterParser.ts:123-175）。
        ParseSkillFrontmatter.ParsedMarkdown parsed =
            ParseSkillFrontmatter.parseFrontmatterStatic(content, file.toString());
        return parseAgentFromMarkdown(file.toString(), baseDir.toString(),
            parsed.frontmatter(), parsed.content(), source);
    }

    /**
     * [IMP-SUB-09 D8] 从已解析 frontmatter/content 构建 agent 定义 · 对齐 CC parseAgentFromMarkdown
     * （loadAgentsDir.ts:541-755）。
     *
     * <p>多源路径（{@link #loadAllSources}）消费 {@code MarkdownConfigLoader.MarkdownFile} 的
     * frontmatter/content（已由 skill 域统一管线解析），与 CC 5 参签名
     * {@code parseAgentFromMarkdown(filePath, baseDir, frontmatter, content, source)}
     * （loadAgentsDir.ts:541-547）同构，避免重复读文件 + 重复解析 frontmatter。
     *
     * <p><b>[Session S1 P0-3]</b>: 旧实现仅解析 4 字段 (name/description/tools/body) + 缺 description
     * 用 "Custom agent" 兜底 + source 硬编码 "userSettings". 本期:
     * <ul>
     *   <li>缺 description 时 <b>return null</b> (对齐 CC :561, 非兜底) — 无效 agent 文件 Fail loud 跳过</li>
     *   <li>source 改为入参 (对齐 CC 5 参签名)</li>
     *   <li>补 16+ 字段解析 (color/model/background/memory/isolation/effort/permissionMode/maxTurns/
     *       filename/tools/disallowedTools/skills/initialPrompt/mcpServers/hooks/baseDir/source)</li>
     * </ul>
     *
     * <p>tools 语义对齐 CC parseAgentToolsFromFrontmatter (markdownConfigLoader.ts:113-124):
     * 缺字段 = 全部工具 (不设置, Optional.empty); 空字段 = 无工具 ([]); '*' = 全部工具.
     * CC parseAgentFromMarkdown <b>不解析 omitClaudeMd</b> (仅内置 agent 有), 故不设置 (缺省 empty).
     *
     * @param filePath   agent markdown 文件绝对路径（CC :541 filePath 入参，用于日志/filename）
     * @param baseDir    CC original: baseDir (loadAgentsDir.ts:542 入参) — 写入 CustomAgentDefinition.baseDir
     * @param frontmatter 已解析 frontmatter Map（CC :543 frontmatter 入参）
     * @param content    去除 frontmatter 后的正文（CC :544 content 入参；trim 后为 systemPrompt，CC :713）
     * @param source     CC original: source (SettingSource)
     * @return 解析出的 {@link AgentDefinition}; 缺 name/description/无效 frontmatter 时 return null
     */
    private static AgentDefinition parseAgentFromMarkdown(String filePath,
                                                          String baseDir,
                                                          Map<String, Object> frontmatter,
                                                          String content,
                                                          String source) {
        Map<String, Object> fm = frontmatter;
        if (fm == null || fm.isEmpty()) {
            log.warn("[loadAgentsDir] {} 无 frontmatter, 跳过 (非 agent 文件)", filePath);
            return null;
        }
        // [IMP-SUB-09 REWORK R1] name 校验改用 JS falsy + String 类型双语义，对齐 CC
        //   loadAgentsDir.ts:554（!agentType || typeof agentType !== 'string' → return null）。
        //   Java 旧 `asString(...) != null` 仅挡 null：name 空串 "" 会带着空 type 建 agent（比误报
        //   failed 更偏）——空串/false/0/NaN/非 String 一律拒绝，与 CC 静默跳过语义一致。
        Object agentTypeObj = fm.get("name");
        if (isFalsy(agentTypeObj) || !(agentTypeObj instanceof String)) {
            log.warn("[loadAgentsDir] {} frontmatter 缺/非法 'name', 跳过 (对齐 CC loadAgentsDir.ts:554)",
                filePath);
            return null;
        }
        String agentType = (String) agentTypeObj;
        // [IMP-SUB-09 REWORK R1] description 校验同样改 JS falsy + String 类型双语义（CC :557
        //   !whenToUse || typeof whenToUse !== 'string' → return null）——空串/非 String 描述拒绝，
        //   与 name 校验同款，保持 parse 入口 falsy 语义一致（旧 asString(...)!=null 放行空串）。
        Object descObj = fm.get("description");
        if (isFalsy(descObj) || !(descObj instanceof String)) {
            // 对齐 CC loadAgentsDir.ts:557: 缺 description 时 return null 拒绝该文件 (非 'Custom agent' 兜底)
            log.warn("[loadAgentsDir] agent 文件 {} 缺必填/非法 'description', 跳过 (对齐 CC parseAgentFromMarkdown:557)",
                filePath);
            return null;
        }
        String description = (String) descObj;
        // CC :564 unescape YAML 转义换行
        description = description.replace("\\n", "\n");

        AgentDefinition.CustomAgentDefinition.Builder builder = AgentDefinition.CustomAgentDefinition.builder(
                agentType, description, source, content.trim())
            .filename(getFileNameWithoutExtension(Path.of(filePath)))  // CC :657 basename without .md
            .baseDir(baseDir);                                         // CC :543 baseDir 入参

        // color · CC :558
        Object colorObj = fm.get("color");
        if (colorObj != null) builder.color(colorObj.toString());

        // model · CC :560-566 (trim, 'inherit' 保留小写)
        Object modelObj = fm.get("model");
        if (modelObj instanceof String ms && !ms.trim().isEmpty()) {
            String trimmed = ms.trim();
            builder.model("inherit".equals(trimmed.toLowerCase()) ? "inherit" : trimmed);
        }

        // background · CC :575 ('true'/true -> true, 其余忽略)
        Object bg = fm.get("background");
        if (Boolean.TRUE.equals(bg) || "true".equals(bg)) builder.background(true);

        // memory · CC :584 (user/project/local)；方法级变量供后续 tools 注入判断（:663-674）
        String memory = null;
        Object memoryObj = fm.get("memory");
        if (memoryObj != null) {
            memory = memoryObj.toString();
            if ("user".equals(memory) || "project".equals(memory) || "local".equals(memory)) {
                builder.memory(memory);
            } else {
                // [FIX-AM REQ-M-19] 对齐 CC :601-605 非法 memory 值 logForDebugging（D10 静默偏差修复）
                log.warn("[loadAgentsDir] agent 文件 {} 非法 memory 值 '{}'，有效选项: user/project/local (对齐 CC loadAgentsDir.ts:601-605)",
                    filePath, memory);
                memory = null;
            }
        }

        // isolation · CC :607-621 VALID_ISOLATION_MODES = USER_TYPE==='ant' ? ['worktree','remote'] : ['worktree']
        // (loadAgentsDir.ts:609-610). [IMP-SUB-17 D17/#10] 动态化: 'remote' 仅 ant 内部环境可用;
        // 非 ant (外部构建) 仅 'worktree' 合法, 'remote' 拒绝跳过 (CC :617-619 logForDebugging).
        Object isoObj = fm.get("isolation");
        if (isoObj != null) {
            String iso = isoObj.toString();
            if (validIsolationModes().contains(iso)) {
                builder.isolation(iso);
            } else {
                log.warn("[loadAgentsDir] agent 文件 {} isolation 值非法, 跳过: '{}', 有效选项: {} (对齐 CC loadAgentsDir.ts:609-610/617)",
                    filePath, iso, validIsolationModes());
            }
        }

        // effort · CC :603 (字符串级别或整数, 本期原样透传)
        Object effortObj = fm.get("effort");
        if (effortObj != null) builder.effort(effortObj.toString());

        // permissionMode · CC :620
        Object pmObj = fm.get("permissionMode");
        if (pmObj != null) builder.permissionMode(pmObj.toString());

        // maxTurns · CC :649 parsePositiveIntFromFrontmatter（正整数；非法 → undefined + logForDebugging :650-654）
        Object mtRaw = fm.get("maxTurns");
        Integer maxTurns = ParseSkillFrontmatter.parsePositiveIntFromFrontmatter(mtRaw);
        if (mtRaw != null && maxTurns == null) {
            log.warn("[loadAgentsDir] agent 文件 {} maxTurns 非法 '{}'，必须为正整数 (对齐 CC loadAgentsDir.ts:650-654)",
                filePath, mtRaw);
        }
        if (maxTurns != null) builder.maxTurns(maxTurns);

        // tools · CC :660 parseAgentToolsFromFrontmatter (缺字段=undefined=全部, 空=无工具, '*'=undefined=全部)
        //   P1-1 接入统一管线后直接复用 ParseSkillFrontmatter.parseAgentToolsFromFrontmatter
        //   （markdownConfigLoader.ts:113-126），替代自建 parseTools。
        //   [IMP-SUB-23 #9 / DEL-WF2-LD-01] 对象数组格式 [{name:'Bash'}] 已删除：CC parseToolListString
        //   数组分支仅保留 string 项（markdownConfigLoader.ts:91-95 filter(item => typeof item === 'string')），
        //   对象项被过滤 → []；旧自建 parseTools 会把对象数组解析为 name 列表（['Bash']），与 CC 值域不等价，
        //   故整方法删除并入统一管线（LoadPluginAgents.java:207 登记 DEL-01）。
        List<String> tools = ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(fm, "tools");
        // [IMP-M-P2-2] memory 启用 + tools 显式非通配 → 注入 Write/Edit/Read 工具
        //   （对齐 CC loadAgentsDir.ts:663-674）。parseAgentToolsFromFrontmatter 把 '*' 转
        //   null（undefined）→ 'tools !== null' 为 false → 注入跳过（markdownConfigLoader.ts:124-126）。
        if (tools != null && memory != null
                && com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            tools = injectMemoryTools(tools);
        }
        if (tools != null) builder.tools(tools);

        // disallowedTools · CC :676-681（缺=undefined 不设置；'*'=undefined 不设置）
        List<String> disallowedTools = ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(fm, "disallowedTools");
        if (disallowedTools != null) builder.disallowedTools(disallowedTools);

        // skills · CC :684 parseSlashCommandToolsFromFrontmatter (缺/空 = [])
        builder.skills(ParseSkillFrontmatter.parseSlashCommandToolsFromFrontmatter(fm.get("skills")));

        // initialPrompt · CC :699
        Object ipObj = fm.get("initialPrompt");
        if (ipObj instanceof String ips && !ips.trim().isEmpty()) builder.initialPrompt(ips);

        // mcpServers · CC :693-708 AgentMcpServerSpecSchema = union(z.string(), z.record())
        // (loadAgentsDir.ts:58-68): string 项按名引用已配置 server (CC :65 z.string()),
        // record 项为内联定义 (CC :66 z.record()). 逐项 safeParse, 失败项 CC logForDebugging
        // + 过滤 (:702-707).
        if (fm.get("mcpServers") instanceof List<?> mcpList && !mcpList.isEmpty()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            int stringRefCount = 0;
            for (Object item : mcpList) {
                if (item instanceof String ref) {
                    // CC :65 z.string() 分支 — 按名引用, 以 {"name": <string>} 表达 (下游
                    // SubagentExecutor.initializeAgentMcp 读 "name" 键). 消费侧已落地（MCP-I-9 Q-32）:
                    //   SubagentExecutor.initializeAgentMcp 按名查 DB（McpServerService.getServerConfigByName，
                    //   Q-09=C 唯一运行时源），命中建连 / 未命中 warn+跳过（对齐 CC runAgent.ts:140-151）.
                    // string 项连接失败仅 warn (AgentMcpServers 逐 spec try/catch), 不阻断解析.
                    servers.add(Map.of("name", ref));
                    stringRefCount++;
                } else if (item instanceof Map<?, ?> mm) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : mm.entrySet()) sm.put(String.valueOf(e.getKey()), e.getValue());
                    servers.add(sm);
                } else {
                    // CC :702-705 无效项 logForDebugging + 过滤 null
                    log.warn("[loadAgentsDir] agent 文件 {} mcpServers 项非法, 跳过: {} (对齐 CC loadAgentsDir.ts:702)",
                        filePath, item);
                }
            }
            // CC :722-724 过滤后非空才写入 (空数组不设置 mcpServers)
            if (!servers.isEmpty()) {
                builder.mcpServers(servers);
                log.info("[loadAgentsDir] agent {} mcpServers: {} 项 (含按名引用 {} 项)", agentType, servers.size(), stringRefCount);
            }
        }

        // hooks · CC :719 parseHooksFromFrontmatter — 原始 Map<CC事件名, HookMatcherSchema[]>,
        // 运行时由 FrontmatterHooks.fromMap (27 事件) 解析, 此处仅透传 raw Map
        Object hooksObj = fm.get("hooks");
        if (hooksObj instanceof Map<?, ?> hm && !hm.isEmpty()) {
            Map<String, Object> rawHooks = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : hm.entrySet()) rawHooks.put(String.valueOf(e.getKey()), e.getValue());
            builder.hooks(rawHooks);
        }

        // 注意: CC parseAgentFromMarkdown 不解析 omitClaudeMd (仅内置 agent 有) — 缺省 empty, 对齐 CC.
        return builder.build();
    }

    /** CC loadAgentsDir.ts:657 filename = basename without .md */
    private static String getFileNameWithoutExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * memory 启用时补 Write/Edit/Read 工具 · 对齐 CC loadAgentsDir.ts:666-672
     * （{@code for (const tool of [FILE_WRITE_TOOL_NAME, FILE_EDIT_TOOL_NAME, FILE_READ_TOOL_NAME])}）。
     * 保持原顺序 + 末尾追加缺省工具；LinkedHashSet 去重保序。
     */
    private static List<String> injectMemoryTools(List<String> tools) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(tools);
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_WRITE_TOOL_NAME); // "Write"
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_EDIT_TOOL_NAME);  // "Edit"
        set.add(com.nexusai.application.agent.tool.ToolNameConstants.FILE_READ_TOOL_NAME);   // "Read"
        if (log.isDebugEnabled()) {
            log.debug("[loadAgentsDir] memory 启用，注入 Write/Edit/Read 工具: {}", set);
        }
        return new ArrayList<>(set);
    }

    /**
     * JS falsy 等价判定 · CC original: {@code !frontmatter['name']}（loadAgentsDir.ts:324）/
     * {@code !agentType}（:554）。
     *
     * <p>[IMP-SUB-09 REWORK R1] Java 旧 `!= null` 仅判缺失，CC falsy 还覆盖空串 ""/false/0/NaN。
     * 值域内等价：null、空串 ""、Boolean.FALSE、数值 0（含 -0.0/0.0）、NaN → falsy；其余 truthy。
     */
    private static boolean isFalsy(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.isEmpty();
        }
        if (v instanceof Boolean b) {
            return !b;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == 0.0 || Double.isNaN(d);
        }
        return false;
    }

    /**
     * 路径等价比较（Windows 驱动器字母大小写不敏感 + 分隔符归一化）· 供 {@link #load} 判定 baseDir
     * 是否为 nexusai / claude 用户配置根（T3 双目录用户源）。
     */
    private static boolean pathEqualsNormalized(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        String s1 = a.toAbsolutePath().normalize().toString().replace('\\', '/');
        String s2 = b.toAbsolutePath().normalize().toString().replace('\\', '/');
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return win ? s1.toLowerCase(Locale.ROOT).equals(s2.toLowerCase(Locale.ROOT)) : s1.equals(s2);
    }

    // ════════════════════════════════════════════════════════════════
    // [AM-05/OPD-R2-AM-05] zod 等价 schema 校验 · CC AgentJsonSchema（loadAgentsDir.ts:73-99）
    // ════════════════════════════════════════════════════════════════

    /** CC EFFORT_LEVELS（utils/effort.ts:13-17）· low/medium/high/max（4 值，含 'max'） */
    private static final java.util.Set<String> EFFORT_LEVELS = java.util.Set.of("low", "medium", "high", "max");
    /** CC EXTERNAL_PERMISSION_MODES（types/permissions.ts:16-22）· TRANSCRIPT_CLASSIFIER 外部构建关 → 无 'auto' */
    private static final java.util.Set<String> PERMISSION_MODES = java.util.Set.of(
        "acceptEdits", "bypassPermissions", "default", "dontAsk", "plan");
    /** CC AgentJsonSchema memory enum（loadAgentsDir.ts:92） */
    private static final java.util.Set<String> VALID_MEMORY_SCOPES = java.util.Set.of("user", "project", "local");
    /**
     * 环境变量读取器 · 测试注入缝（对齐 TaskService.java:380 ENV_READER 惯例）。
     *
     * <p>System.getenv 在 JVM 内只读不可 mutate，[IMP-SUB-17 D17/#10] 使 isolation 判定
     * （{@link #validIsolationModes}）依赖 USER_TYPE 后，测试经此缝注入 USER_TYPE 以验证
     * ant 分支（remote 合法）与非 ant 分支（remote 拒绝）。生产默认 {@code System::getenv}，行为零变化。
     */
    static volatile java.util.function.Function<String, String> ENV_READER = System::getenv;

    /**
     * CC VALID_ISOLATION_MODES（loadAgentsDir.ts:609-610）：{@code process.env.USER_TYPE === 'ant'}
     * ? ['worktree', 'remote'] : ['worktree']。
     *
     * <p><b>[IMP-SUB-17 D17/#10]</b> 动态化（原静态常量仅 'worktree'，'remote' 恒死分支，open-decisions
     * §F2 #10）：'remote' 仅 ant 内部环境可用；非 ant（外部构建）仅 'worktree' 合法。USER_TYPE 判定
     * 用严格相等（对齐 CC {@code ===} 与 TaskService.java:392-394 / ToolSearchService.java:494 惯例），
     * 每次解析时求值（对齐 CC 函数体内 const，loadAgentsDir.ts:609）。
     *
     * @return 当前 USER_TYPE 下合法的 isolation 值集合
     */
    private static java.util.Set<String> validIsolationModes() {
        return "ant".equals(ENV_READER.apply("USER_TYPE"))
            ? java.util.Set.of("worktree", "remote")
            : java.util.Set.of("worktree");
    }
    /** CC McpServerConfigSchema 8 类 type 字面量（services/mcp/types.ts:58-135） */
    private static final java.util.Set<String> MCP_CONFIG_TYPES = java.util.Set.of(
        "stdio", "sse", "sse-ide", "ws-ide", "http", "ws", "sdk", "claudeai-proxy");
    /** CC HOOK_EVENTS（coreTypes.ts:25-53） */
    private static final java.util.Set<String> HOOK_EVENTS = java.util.Set.of(
        "PreToolUse", "PostToolUse", "PostToolUseFailure", "Notification", "UserPromptSubmit",
        "SessionStart", "SessionEnd", "Stop", "StopFailure", "SubagentStart", "SubagentStop",
        "PreCompact", "PostCompact", "PermissionRequest", "PermissionDenied", "Setup",
        "TeammateIdle", "TaskCreated", "TaskCompleted", "Elicitation", "ElicitationResult",
        "ConfigChange", "WorktreeCreate", "WorktreeRemove", "InstructionsLoaded", "CwdChanged",
        "FileChanged");
    /**
     * [AM-05/OPD-R2-AM-05] zod 等价整体校验 · CC AgentJsonSchema（loadAgentsDir.ts:73-99）。
     *
     * <p>任一字段非法 → 返回错误信息（整 agent 拒绝）；全部合法 → null。逐字段镜像 zod 语义：
     * description/prompt 非空 String（min1，不 trim）；tools/disallowedTools/skills String 数组；
     * model String trim 后非空；effort ∈ {low,medium,high,max} 或整数；permissionMode ∈ 外部 5 模式；
     * maxTurns 正整数；initialPrompt String；memory ∈ {user,project,local}；background Boolean；
     * isolation ∈ {worktree,remote}（USER_TYPE=ant 时含 remote，对齐 CC :94-97）；mcpServers = String 引用或 {name: 合法 MCP 配置}；
     * hooks = HOOK_EVENTS 键 + matcher 数组（hooks 必填数组 + matcher 可选 String；
     * hook type ∈ {command,prompt,agent,http} + 类型必填键 + 全字段类型级——委托
     * FrontmatterHooks.validateCommandStrict，与 FrontmatterHooks.fromMap 同口径）。
     * 键存在值为 null 视为非法（zod optional 只接受 undefined，JSON null 拒绝）。
     *
     * @return 非法原因；全部合法 → null
     */
    private static String validateAgentJsonSchema(Map<String, Object> def) {
        Object desc = def.get("description");
        if (!(desc instanceof String ds) || ds.isEmpty()) {
            return "description 必须为非空 String（z.string().min(1)）";
        }
        Object prompt = def.get("prompt");
        if (!(prompt instanceof String ps) || ps.isEmpty()) {
            return "prompt 必须为非空 String（z.string().min(1)）";
        }
        for (String field : List.of("tools", "disallowedTools", "skills")) {
            if (def.containsKey(field) && !isStringList(def.get(field))) {
                return field + " 必须为 String 数组（z.array(z.string())）";
            }
        }
        if (def.containsKey("model") && !isTrimmedNonEmptyString(def.get("model"))) {
            return "model 必须为 trim 后非空的 String（z.string().trim().min(1)）";
        }
        Object effort = def.get("effort");
        if (def.containsKey("effort") && effort == null) {
            return "effort 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (effort != null && !((effort instanceof String es && EFFORT_LEVELS.contains(es))
                || (effort instanceof Number en && isIntegerNumber(en)))) {
            return "effort 必须为 low/medium/high/max 或整数（z.union([z.enum(EFFORT_LEVELS), z.number().int()])）";
        }
        Object perm = def.get("permissionMode");
        if (def.containsKey("permissionMode") && perm == null) {
            return "permissionMode 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (perm != null && !(perm instanceof String pms && PERMISSION_MODES.contains(pms))) {
            return "permissionMode 必须为外部权限模式之一（z.enum(PERMISSION_MODES)）";
        }
        Object maxTurns = def.get("maxTurns");
        if (def.containsKey("maxTurns") && maxTurns == null) {
            return "maxTurns 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (maxTurns != null && !(maxTurns instanceof Number mt
                && isIntegerNumber(mt) && mt.doubleValue() > 0)) {
            return "maxTurns 必须为正整数（z.number().int().positive()）";
        }
        Object initialPrompt = def.get("initialPrompt");
        if (def.containsKey("initialPrompt") && initialPrompt == null) {
            return "initialPrompt 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (initialPrompt != null && !(initialPrompt instanceof String)) {
            return "initialPrompt 必须为 String（z.string()）";
        }
        Object memory = def.get("memory");
        if (def.containsKey("memory") && memory == null) {
            return "memory 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (memory != null && !(memory instanceof String ms && VALID_MEMORY_SCOPES.contains(ms))) {
            return "memory 必须为 user/project/local（z.enum(['user','project','local'])）";
        }
        Object background = def.get("background");
        if (def.containsKey("background") && background == null) {
            return "background 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (background != null && !(background instanceof Boolean)) {
            return "background 必须为 Boolean（z.boolean()）";
        }
        Object isolation = def.get("isolation");
        if (def.containsKey("isolation") && isolation == null) {
            return "isolation 显式 null 拒绝（zod .optional() 仅接受 undefined，JSON null 拒绝）";
        }
        if (isolation != null && !(isolation instanceof String iso && validIsolationModes().contains(iso))) {
            // [IMP-SUB-17 D17/#10] 错误文案动态化（原硬编码仅 worktree；USER_TYPE=ant 时含 remote）
            return "isolation 必须为 " + String.join("/", validIsolationModes())
                + "（对齐 CC USER_TYPE==='ant' ? z.enum(['worktree','remote']) : z.enum(['worktree'])，loadAgentsDir.ts:94-97）";
        }
        if (def.containsKey("mcpServers") && !isValidMcpServers(def.get("mcpServers"))) {
            return "mcpServers 项非法（z.array(z.union([z.string(), z.record(z.string(), McpServerConfigSchema())]))）";
        }
        if (def.containsKey("hooks") && !isValidHooks(def.get("hooks"))) {
            return "hooks 非法（HooksSchema：HOOK_EVENTS 键 + matcher 数组 + HookCommandSchema）";
        }
        return null;
    }

    private static boolean isStringList(Object v) {
        if (!(v instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (!(item instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrimmedNonEmptyString(Object v) {
        return v instanceof String s && !s.trim().isEmpty();
    }

    /** JS Number.isInteger 等价（z.number().int()）：整数值（1.0 是整数，1.5 不是）。 */
    private static boolean isIntegerNumber(Number n) {
        return n.doubleValue() == Math.rint(n.doubleValue());
    }

    /** CC AgentMcpServerSpecSchema（loadAgentsDir.ts:63-68）= union(z.string(), z.record(...))。 */
    private static boolean isValidMcpServers(Object v) {
        if (!(v instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof String) {
                continue; // 按名引用已配置 server（CC :65）
            }
            if (item instanceof Map<?, ?> record) {
                // z.record(z.string(), McpServerConfigSchema())：每个 value 必须是合法 MCP 配置
                for (Object value : record.values()) {
                    if (!isValidMcpConfig(value)) {
                        return false;
                    }
                }
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * CC McpServerConfigSchema 判别联合（services/mcp/types.ts:124-135）：type 字面量 + 类型必填键。
     * [FINDING-4 返工] type 缺失时按 stdio 处理——CC McpStdioServerConfigSchema
     * {@code type: z.literal('stdio').optional()}（types.ts:30，向后兼容），
     * 故 {@code {command: 'node'}} 无 type 也是合法 stdio 配置。
     */
    private static boolean isValidMcpConfig(Object v) {
        if (!(v instanceof Map<?, ?> cfg)) {
            return false;
        }
        Object type = cfg.get("type");
        if (type == null) {
            return isValidStdioConfig(cfg); // 无 type → stdio（向后兼容，types.ts:30）
        }
        if (!(type instanceof String ts) || !MCP_CONFIG_TYPES.contains(ts)) {
            return false;
        }
        switch (ts) {
            case "stdio":
                return isValidStdioConfig(cfg);
            case "sse":
            case "http":
            case "ws":
                return isStringKey(cfg, "url");
            case "sse-ide":
            case "ws-ide":
                return isStringKey(cfg, "url") && isStringKey(cfg, "ideName");
            case "sdk":
                return isStringKey(cfg, "name");
            case "claudeai-proxy":
                return isStringKey(cfg, "url") && isStringKey(cfg, "id");
            default:
                return false;
        }
    }

    /**
     * CC McpStdioServerConfigSchema（services/mcp/types.ts:28-35）：type 可选（向后兼容，已由调用方处理）；
     * command 非空 String（min(1)，空串拒绝）；args String 数组（default []）；env String→String record。
     */
    private static boolean isValidStdioConfig(Map<?, ?> cfg) {
        Object command = cfg.get("command");
        if (!(command instanceof String cs) || cs.isEmpty()) {
            return false; // z.string().min(1, 'Command cannot be empty')
        }
        if (cfg.containsKey("args") && !isStringList(cfg.get("args"))) {
            return false; // z.array(z.string())
        }
        if (cfg.containsKey("env")) {
            Object env = cfg.get("env");
            if (!(env instanceof Map<?, ?> envMap)) {
                return false; // z.record(z.string(), z.string())
            }
            for (Map.Entry<?, ?> e : envMap.entrySet()) {
                if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** CC HooksSchema（schemas/hooks.ts:211-213）：partialRecord(HOOK_EVENTS, array(HookMatcherSchema))。 */
    private static boolean isValidHooks(Object v) {
        if (!(v instanceof Map<?, ?> hooksMap)) {
            return false;
        }
        for (Map.Entry<?, ?> e : hooksMap.entrySet()) {
            if (!(e.getKey() instanceof String event) || !HOOK_EVENTS.contains(event)) {
                return false;
            }
            if (!(e.getValue() instanceof List<?> matchers)) {
                return false;
            }
            for (Object matcher : matchers) {
                if (!isValidHookMatcher(matcher)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** CC HookMatcherSchema（schemas/hooks.ts:194-204）：{matcher?: string, hooks: array(HookCommandSchema)}。 */
    private static boolean isValidHookMatcher(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return false;
        }
        // [IMP-SUB-10 返工2] 与 FrontmatterHooks.validateMatcherStrict 同口径: zod z.string().optional()
        // （hooks.ts:196-199）拒绝 JSON null —— matcher 键存在即须 String (null 亦拒绝).
        // 两校验点一致决策: JSON 路径（validateAgentJsonSchema）与 markdown 路径（fromMap）同语义.
        if (m.containsKey("matcher") && !(m.get("matcher") instanceof String)) {
            return false;
        }
        Object hooks = m.get("hooks");
        if (!(hooks instanceof List<?> hookList)) {
            return false; // hooks 必填数组（z.array(HookCommandSchema())）
        }
        for (Object hook : hookList) {
            if (!isValidHookCommand(hook)) {
                return false;
            }
        }
        return true;
    }

    /** CC HookCommandSchema discriminatedUnion（schemas/hooks.ts:176-189）：type + 必填键 + 全字段类型级。
     *  [IMP-SUB-10 返工] 委托 {@link FrontmatterHooks#validateCommandStrict}（zod 全字段类型级镜像）——
     *  两校验点一致决策：与 FrontmatterHooks.fromMap 同口径（字段类型非法同样整 agent 拒绝）。 */
    private static boolean isValidHookCommand(Object v) {
        return FrontmatterHooks.validateCommandStrict(v) == null;
    }

    private static boolean isStringKey(Map<?, ?> m, String key) {
        return m.get(key) instanceof String;
    }

    /**
     * [FIX-AM REQ-M-19] + [AM-05/OPD-R2-AM-05] 从 JSON 解析单个 agent · 对齐 CC parseAgentFromJson
     * （loadAgentsDir.ts:445-516）。
     *
     * <p>CC AgentJsonSchema（:73-99）zod 全字段严格校验：<b>任一字段非法 → catch（:510-515）→
     * 整 agent null</b>（旧 Java 逐字段宽松降级 → RED）。memory 为
     * {@code z.enum(['user','project','local'])} 可选（:92）。memory 启用且 tools 显式非通配时
     * 注入 Write/Edit/Read 工具（:456-467）；getSystemPrompt 闭包 memory 注入（:481-488）在 Java
     * 端由消费点（SubagentExecutor.buildAgentSystemPrompt / SubagentTool.getEffectiveSystemPrompt）
     * 经 agentDefinition.memory() 生成期追加 —— 本方法仅落 memory 字段，闭包语义由消费端等价实现。
     *
     * <p><b>[IMP-SUB-08 D7]</b> 补 mcpServers/hooks 消费（CC :495-498）：JSON/flagSettings agent 声明
     * 的 {@code mcpServers}（string 按名引用 + {serverName: config} 内联）与 {@code hooks}（raw
     * Map<CC事件名, HookMatcher[]>）此前被静默丢弃。现在与 markdown 路径（:384-420）同构写入
     * CustomAgentDefinition，由 SubagentExecutor.initializeAgentMcp（mcpServers）+ Step 13
     * registerAgentFrontmatterHooks（hooks）消费。
     *
     * @param name       agent type（agentsJson 的 key，CC :445）
     * @param definition 原始 JSON 对象（Map 形态）
     * @param source     CC original: source（缺省 'flagSettings'，CC :448）
     * @return 解析出的 CustomAgentDefinition；任一字段非法 → null（zod 整体拒绝）
     */
    public static AgentDefinition parseAgentFromJson(String name,
                                                     Map<String, Object> definition,
                                                     String source) {
        if (name == null || definition == null) {
            return null;
        }
        // [AM-05/OPD-R2-AM-05] zod 等价整体校验：任一字段非法 → 整 agent null（CC :510-515 catch）
        String schemaError = validateAgentJsonSchema(definition);
        if (schemaError != null) {
            log.warn("[loadAgentsDir] JSON agent '{}' schema 非法（整 agent 拒绝，对齐 CC AgentJsonSchema zod 整体拒绝）: {}",
                name, schemaError);
            return null;
        }
        String desc = (String) definition.get("description");
        String prompt = (String) definition.get("prompt");
        // memory schema · CC :92 z.enum(['user','project','local']).optional()（AM-05 已整体校验）
        String memory = (String) definition.get("memory");

        // tools · CC :453 parseAgentToolsFromFrontmatter（缺=undefined/全部，含 '*'=undefined，空=[]）
        //   P1-1 接入统一管线：复用 ParseSkillFrontmatter.parseAgentToolsFromFrontmatter。
        //   [IMP-SUB-23 #9 / DEL-WF2-LD-01] JSON tools 仅 string 数组（CC AgentJsonSchema
        //   z.array(z.string())，loadAgentsDir.ts:76）；对象数组由 validateAgentJsonSchema isStringList
        //   整体拒绝（整 agent null），parseToolListString 数组分支同样仅保留 string 项。
        List<String> tools = ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(definition, "tools");
        // memory 启用 + tools 显式非通配 → 注入 Write/Edit/Read（CC :456-467）
        if (memory != null && tools != null
                && com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            tools = injectMemoryTools(tools);
        }

        AgentDefinition.CustomAgentDefinition.Builder builder =
            AgentDefinition.CustomAgentDefinition.builder(name, desc, source, prompt)
                .memory(memory);
        if (tools != null) {
            builder.tools(tools);
        }
        // disallowedTools · CC :469-472（缺=undefined 不设置；'*'=undefined 不设置）
        List<String> disallowedTools =
            ParseSkillFrontmatter.parseAgentToolsFromFrontmatter(definition, "disallowedTools");
        if (disallowedTools != null) {
            builder.disallowedTools(disallowedTools);
        }
        // model · CC :79-84（trim；'inherit' 小写保留）
        if (definition.get("model") instanceof String modelRaw && !modelRaw.trim().isEmpty()) {
            String trimmed = modelRaw.trim();
            builder.model("inherit".equals(trimmed.toLowerCase()) ? "inherit" : trimmed);
        }
        // effort · CC :85（字符串级别或整数，原样透传）
        if (definition.get("effort") != null) {
            builder.effort(definition.get("effort").toString());
        }
        // permissionMode · CC :86
        if (definition.get("permissionMode") != null) {
            builder.permissionMode(definition.get("permissionMode").toString());
        }
        // maxTurns · CC :89（正整数）
        if (definition.get("maxTurns") instanceof Number maxTurnsNum) {
            builder.maxTurns(maxTurnsNum.intValue());
        }
        // skills · CC :90
        if (definition.get("skills") instanceof List<?> skillsList && !skillsList.isEmpty()) {
            List<String> skills = new ArrayList<>();
            for (Object item : skillsList) {
                if (item != null) skills.add(item.toString());
            }
            if (!skills.isEmpty()) builder.skills(skills);
        }
        // initialPrompt · CC :91
        if (definition.get("initialPrompt") instanceof String ip && !ip.trim().isEmpty()) {
            builder.initialPrompt(ip);
        }
        // background · CC :93（布尔 true）
        if (Boolean.TRUE.equals(definition.get("background"))) {
            builder.background(true);
        }
        // isolation · CC :94-97（USER_TYPE==='ant' ? z.enum(['worktree','remote']) : z.enum(['worktree'])）
        // [IMP-SUB-17 D17/#10] 动态化——'remote' 在 USER_TYPE=ant 时合法（原硬编码
        //   ("worktree".equals(iso) || "remote".equals(iso)) 使 remote 与 schema 校验矛盾：
        //   validateAgentJsonSchema 先拒 remote → 此处 remote 分支恒死；现改 validIsolationModes
        //   动态集合统一两路径，非 ant 环境 remote 仍被 schema 拒绝（DEL-WF2-LD-02 死分支裁决为
        //   修正非删，open-decisions §F2 #10）
        if (definition.get("isolation") instanceof String iso
                && validIsolationModes().contains(iso)) {
            builder.isolation(iso);
        }
        // mcpServers · CC :495-497（parsed.mcpServers && length > 0 才设置，空数组不设置）——
        //   AgentMcpServerSpecSchema（loadAgentsDir.ts:58-68）= union(z.string(), z.record())：
        //   string 项按名引用已配置 server → 以 {"name": ref} 表达（与 markdown 路径 :394 一致，
        //   下游 SubagentExecutor.initializeAgentMcp:2995 读 "name" 键走 Q-32 按名查 DB）；
        //   record 项为内联定义 {serverName: config} → 原样拷贝（下游 :2979 keyed-inline 判别）。
        //   schema 已在 validateAgentJsonSchema 整体校验通过（isValidMcpServers），此处仅形态转换。
        if (definition.get("mcpServers") instanceof List<?> mcpList && !mcpList.isEmpty()) {
            List<Map<String, Object>> servers = new ArrayList<>();
            int stringRefCount = 0;
            for (Object item : mcpList) {
                if (item instanceof String ref) {
                    // CC :65 z.string() 分支 — 按名引用, 以 {"name": <string>} 表达
                    servers.add(Map.of("name", ref));
                    stringRefCount++;
                } else if (item instanceof Map<?, ?> mm) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : mm.entrySet()) {
                        sm.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    servers.add(sm);
                } else {
                    // 防御分支：validateAgentJsonSchema 已保证 string|record，理论不可达
                    log.warn("[loadAgentsDir] JSON agent '{}' mcpServers 项非法, 跳过: {} (对齐 CC loadAgentsDir.ts:702)",
                        name, item);
                }
            }
            // CC :495-497 转换后非空才写入（与 markdown 路径 :407 一致）
            if (!servers.isEmpty()) {
                builder.mcpServers(servers);
                log.info("[loadAgentsDir] JSON agent '{}' mcpServers: {} 项 (含按名引用 {} 项)",
                    name, servers.size(), stringRefCount);
            }
        }
        // hooks · CC :498（parsed.hooks truthy 才设置）——原始 Map<CC事件名, HookMatcher[]>,
        // 运行时由 SubagentExecutor Step 13 registerAgentFrontmatterHooks → FrontmatterHooks.fromMap
        // (27 事件) 解析, 此处仅透传 raw Map（与 markdown 路径 :415-420 一致）。
        if (definition.get("hooks") instanceof Map<?, ?> hm) {
            Map<String, Object> rawHooks = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                rawHooks.put(String.valueOf(e.getKey()), e.getValue());
            }
            builder.hooks(rawHooks);
        }
        if (log.isDebugEnabled()) {
            log.debug("[loadAgentsDir] JSON 解析 agent: {} (memory={}, tools={})",
                name, memory, tools != null ? tools : "全部工具");
        }
        return builder.build();
    }

    /**
     * [FIX-AM REQ-M-19] + [AM-05/OPD-R2-AM-05] 从 JSON 对象批量解析 agents · 对齐 CC parseAgentsFromJson
     * （loadAgentsDir.ts:521-536）：{@code AgentsJsonSchema().parse(agentsJson)}（z.record 整批校验）
     * <b>任一 entry 非法 → catch（:530-535）→ 整批 []</b>（旧 Java 逐条跳过非法项 → RED）。
     *
     * @param agentsJson JSON agents 映射（key=agentType，value=agent 定义）
     * @param source     CC original: source（缺省 'flagSettings'，CC :523）
     * @return 全部合法 → 解析出的 agent 列表；任一 entry 非法 → 空列表 []
     */
    public static List<AgentDefinition> parseAgentsFromJson(Map<String, Object> agentsJson,
                                                            String source) {
        if (agentsJson == null || agentsJson.isEmpty()) {
            return Collections.emptyList();
        }
        // [AM-05] CC AgentsJsonSchema().parse(agentsJson) 整批校验：任一 entry 非法 → 整批 []（:521-536）
        for (Map.Entry<String, Object> entry : agentsJson.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> defMap)) {
                log.warn("[loadAgentsDir] parseAgentsFromJson: agent '{}' 定义非对象 → 整批拒绝 []（对齐 CC zod）",
                    entry.getKey());
                return Collections.emptyList();
            }
            Map<String, Object> def = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : defMap.entrySet()) {
                def.put(String.valueOf(e.getKey()), e.getValue());
            }
            if (validateAgentJsonSchema(def) != null) {
                log.warn("[loadAgentsDir] parseAgentsFromJson: agent '{}' schema 非法 → 整批拒绝 []（对齐 CC zod 整体拒绝）",
                    entry.getKey());
                return Collections.emptyList();
            }
        }
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : agentsJson.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> defMap) {
                Map<String, Object> def = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : defMap.entrySet()) {
                    def.put(String.valueOf(e.getKey()), e.getValue());
                }
                AgentDefinition agent = parseAgentFromJson(entry.getKey(), def, source);
                if (agent != null) {
                    result.add(agent);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[loadAgentsDir] parseAgentsFromJson: {} 输入解析出 {} 个 agent",
                agentsJson.size(), result.size());
        }
        return result;
    }

    /**
     * 检查 Agent 的必需 MCP 服务器是否可用 · 对齐 CC hasRequiredMcpServers
     */
    public static boolean hasRequiredMcpServers(AgentDefinition agent, java.util.List<String> availableServers) {
        if (agent == null) return true;
        var required = agent.requiredMcpServers();
        if (required.isEmpty() || required.get().isEmpty()) return true;
        if (availableServers == null || availableServers.isEmpty()) return false;
        return required.get().stream().allMatch(pattern ->
            availableServers.stream().anyMatch(server ->
                server.toLowerCase().contains(pattern.toLowerCase())
            )
        );
    }

    /**
     * 根据 MCP 服务器要求过滤 Agents · 对齐 CC filterAgentsByMcpRequirements
     */
    public static List<AgentDefinition> filterAgentsByMcpRequirements(
            List<AgentDefinition> agents, java.util.List<String> availableServers) {
        if (agents == null) return Collections.emptyList();
        return agents.stream()
            .filter(a -> hasRequiredMcpServers(a, availableServers))
            .toList();
    }

    /**
     * 获取活跃 Agents · 对齐 CC getActiveAgentsFromList
     * （loadAgentsDir.ts:193-221）：6 组按 source 覆盖合并，managed 最高优先。
     * 覆盖序：built-in → plugin → user → project → flag → managed（后组覆盖前组）。
     * 由 {@code AgentDefinitionRegistry} 在构造/合并时调用。
     */
    public static List<AgentDefinition> getActiveAgentsFromList(List<AgentDefinition> allAgents) {
        if (allAgents == null) return Collections.emptyList();
        List<AgentDefinition> builtInAgents = allAgents.stream().filter(AgentDefinition::isBuiltIn).toList();
        List<AgentDefinition> pluginAgents = allAgents.stream().filter(AgentDefinition::isPlugin).toList();
        List<AgentDefinition> userAgents = allAgents.stream().filter(a -> "userSettings".equals(a.source())).toList();
        List<AgentDefinition> projectAgents = allAgents.stream().filter(a -> "projectSettings".equals(a.source())).toList();
        List<AgentDefinition> flagAgents = allAgents.stream().filter(a -> "flagSettings".equals(a.source())).toList();
        List<AgentDefinition> managedAgents = allAgents.stream().filter(a -> "policySettings".equals(a.source())).toList();
        Map<String, AgentDefinition> merged = new LinkedHashMap<>();
        for (var agents : List.of(builtInAgents, pluginAgents, userAgents, projectAgents, flagAgents, managedAgents)) {
            for (var agent : agents) merged.put(agent.agentType(), agent);
        }
        return new ArrayList<>(merged.values());
    }
}

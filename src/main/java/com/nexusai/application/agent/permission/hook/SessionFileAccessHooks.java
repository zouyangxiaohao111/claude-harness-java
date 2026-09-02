package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PostToolUseHook;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Session File Access analytics hooks · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/sessionFileAccessHooks.ts:146-250}.
 *
 * <p>L1 语义: 通过 PostToolUse hooks 追踪"agent 访问会话记忆 / transcript / memdir 文件"
 * 的 analytics 事件 — 这些事件 (tengu_session_memory_accessed / tengu_transcript_accessed /
 * tengu_memdir_accessed + 子事件) 是隐私合规与 memory 行为审计的数据基础.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: getFilePathFromInput (Read/Edit/Write file_path, CC :49-69) +
 *       getSessionFileTypeFromInput (session_memory/session_transcript, CC :75-116) +
 *       isMemoryFileAccess (CC :123-141) + handleSessionFileAccess (CC :146-227) +
 *       registerSessionFileAccessHooks (5 工具 matcher hooks, CC :233-250)</li>
 *   <li><b>A2 Golden Trace</b>: Read ~/.claude/session-memory/x.md →
 *       getSessionFileTypeFromInput=session_memory → handleSessionFileAccess 发
 *       tengu_session_memory_accessed; Read memdir 文件 → tengu_memdir_accessed +
 *       tengu_memdir_file_read</li>
 *   <li><b>A3</b>: 纯函数检测 (path/pattern), telemetry 可注入 (null 兜底 noop)</li>
 *   <li><b>A4 边界</b>: 非文件工具 → null; 未知 tool → no event; null telemetry → noop</li>
 *   <li><b>A5 业务场景</b>: 会话记忆文件被 Read/Grep/Glob 访问 → 事件上报;</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS module-level function + logEvent → Java static 检测 + 实例
 * telemetry 注入; TS registerHookCallbacks (matcher map) → Java 逐工具 registerPostToolUse.
 */
public final class SessionFileAccessHooks {

    private static final Logger log = LoggerFactory.getLogger(SessionFileAccessHooks.class);

    /** 会话文件类型 · CC memoryFileDetection.ts:40-82 'session_memory' | 'session_transcript'. */
    public enum FileType {
        SESSION_MEMORY,
        SESSION_TRANSCRIPT
    }

    private final Telemetry telemetry;

    /** 可注入配置目录 (测试隔离; 生产默认 ~/.{appName} — NexusaiPaths 动态自有根, 决策 D1;
     *  2 参测试构造保留 ~/.claude 只读回落源). */
    private final java.util.function.Supplier<String> configHomeSupplier;

    /** 路径解析器 (per-project autoMemPath · DEL-M-06 对齐 CC paths.ts). */
    private final com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths;

    /** 记忆文件检测 (门控 + normalize + Windows 小写化 · OPD-M-47/REQ-M-21). */
    private final com.nexusai.application.agent.memory.MemoryFileDetection memoryFileDetection;

    /** team memory watcher (Edit/Write team 文件后 notifyTeamMemoryWrite · CC sessionFileAccessHooks.ts:201,205)·
     * 可注入; null → 跳过 notify. */
    private com.nexusai.application.agent.memory.TeamMemoryWatcher teamMemoryWatcher;

    public SessionFileAccessHooks(Telemetry telemetry) {
        this(telemetry, com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance());
    }

    public SessionFileAccessHooks(Telemetry telemetry,
                                  com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        // 2 参测试构造保留 ClaudePaths::getClaudeConfigHomeDir（=~/.claude）：
        //   生产不走本构造（LlmAgentLoop 用 4 参门控构造 → NexusaiPaths 动态自有根 ~/.{appName}, D1）；
        //   仅测试注入固定 ~/.claude 供 session-memory 检测断言（SessionFileAccessHooksTest DEFAULT）。
        this(telemetry, com.nexusai.application.agent.skill.ClaudePaths::getClaudeConfigHomeDir, autoMemPaths);
    }

    /**
     * 完整构造器（测试注入 configHome supplier + 路径解析器）。team 双门控默认全关
     * （FeatureFlags.ALL_DISABLED 方法引用，非恒 false supplier，对齐 IMP-CM-07/09 源级断言）；
     * 生产走 {@link #SessionFileAccessHooks(Telemetry, java.util.function.BooleanSupplier, java.util.function.BooleanSupplier)}
     * 注入真实 FeatureFlags 开关。
     */
    public SessionFileAccessHooks(Telemetry telemetry,
                                  java.util.function.Supplier<String> configHomeSupplier,
                                  com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        this(telemetry, configHomeSupplier, autoMemPaths,
            com.nexusai.application.agent.skill.BundledSkillEnabledGates::isAutoMemoryEnabled,
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED::teamMem,
            com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED::tenguHerringClock);
    }

    /**
     * 生产构造器（LlmAgentLoop registerSessionFileAccessHooks 注入）· IMP-CM-09 双门控拆分：
     * 编译开关 feature('TEAMMEM') + 运行时开关 tengu_herring_clock 由 FeatureFlags 注入
     * （与 teamMemPaths bean 同源，消除双实例门控分裂）。autoMemory 走 BundledSkillEnabledGates。
     *
     * <p><b>configHome 决策 D1</b>：configHome 传 nexusai 自有根（NexusaiPaths 动态
     * ~/.{appName}，生产 appName=nexusai → ~/.nexusai）；claude（~/.claude）只读回落由
     * {@link com.nexusai.application.agent.memory.MemoryFileDetection} 双根兜底
     * （isUnderConfigRoot 同时判定 nexusai 与 claude 根，session_memory/transcript 检测兼容旧路径）。
     */
    public SessionFileAccessHooks(Telemetry telemetry,
                                  java.util.function.BooleanSupplier teamMemFeatureEnabled,
                                  java.util.function.BooleanSupplier teamMemoryRuntimeEnabled) {
        this(telemetry, com.nexusai.application.agent.skill.ClaudePaths::getNexusaiConfigHomeDir,
            com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance(),
            com.nexusai.application.agent.skill.BundledSkillEnabledGates::isAutoMemoryEnabled,
            teamMemFeatureEnabled, teamMemoryRuntimeEnabled);
    }

    /**
     * 门控注入构造器（测试 seam · 镜像 {@link com.nexusai.application.agent.memory.MemoryFileDetection}
     * 注入式构造器，TMS-01 notify 装配断言用）。
     *
     * <p>[IMP-CM-09] 双门控拆分（OPD-CM3-11/B04）：编译开关 feature('TEAMMEM') + 运行时开关
     * tengu_herring_clock 双 supplier（生产 = FeatureFlags.teamMem()/tenguHerringClock()，与
     * {@code teamMemPaths} bean 同源）；测试注入全开 gate 驱动 team 文件 Edit/Write → notifyTeamMemoryWrite
     * 装配断言（双开时 team 分支真实启用以对齐 CC）。OAuth 可用性由 watcher/sync 层单独判定。
     *
     * @param autoMemoryEnabled        CC isAutoMemoryEnabled（paths.ts:30-56）
     * @param teamMemFeatureEnabled    编译开关 CC feature('TEAMMEM')（watcher.ts:253）
     * @param teamMemoryRuntimeEnabled 运行时开关 CC tengu_herring_clock（teamMemPaths.ts:77）
     */
    public SessionFileAccessHooks(Telemetry telemetry,
                                  java.util.function.Supplier<String> configHomeSupplier,
                                  com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths,
                                  java.util.function.BooleanSupplier autoMemoryEnabled,
                                  java.util.function.BooleanSupplier teamMemFeatureEnabled,
                                  java.util.function.BooleanSupplier teamMemoryRuntimeEnabled) {
        this.telemetry = telemetry;
        this.configHomeSupplier = configHomeSupplier;
        this.autoMemPaths = autoMemPaths;
        this.memoryFileDetection = new com.nexusai.application.agent.memory.MemoryFileDetection(
            autoMemPaths, configHomeSupplier, autoMemoryEnabled,
            teamMemFeatureEnabled, teamMemoryRuntimeEnabled);
    }

    /** 注入 team memory watcher（Edit/Write 后 notifyTeamMemoryWrite · 对齐 CC :201/:205）。 */
    public void setTeamMemoryWatcher(com.nexusai.application.agent.memory.TeamMemoryWatcher watcher) {
        this.teamMemoryWatcher = watcher;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. getFilePathFromInput · CC :49-69
    // ════════════════════════════════════════════════════════════════

    /**
     * 从工具输入提取文件路径 · CC {@code getFilePathFromInput} (sessionFileAccessHooks.ts:49-69).
     *
     * <p>覆盖 Read (file_path) / Edit (file_path) / Write (file_path); 其余工具返回 null.
     *
     * @param toolName 工具名 (Bash/Read/Edit/Write/...)
     * @param input    工具输入 (LLM 生成的 JSON 对象)
     * @return file_path 或 null
     */
    public static String getFilePathFromInput(String toolName, JsonNode input) {
        if (input == null) return null;
        switch (toolName) {
            case ToolNameConstants.FILE_READ_TOOL_NAME:   // "Read"
            case ToolNameConstants.FILE_EDIT_TOOL_NAME:   // "Edit"
            case ToolNameConstants.FILE_WRITE_TOOL_NAME:  // "Write"
                JsonNode fp = input.get("file_path");
                return (fp != null && fp.isTextual()) ? fp.asText() : null;
            default:
                return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 2. getSessionFileTypeFromInput · CC :75-116
    // ════════════════════════════════════════════════════════════════

    /**
     * 从工具输入检测会话文件类型 · CC {@code getSessionFileTypeFromInput} (:75-116).
     *
     * <p>Read: 检测 file_path (CC :80-84); Grep: 先 path 后 glob (CC :86-99);
     * Glob: 先 path 后 pattern (CC :100-112); 其余工具返回 null.
     *
     * @return session_memory / session_transcript / null
     */
    public FileType getSessionFileTypeFromInput(String toolName, JsonNode input) {
        if (input == null) return null;
        switch (toolName) {
            case ToolNameConstants.FILE_READ_TOOL_NAME: {
                String path = getFilePathFromInput(toolName, input);
                return path != null ? detectSessionFileType(path) : null;
            }
            case ToolNameConstants.GREP_TOOL_NAME: {
                JsonNode pathNode = input.get("path");
                if (pathNode != null && pathNode.isTextual()) {
                    FileType byPath = detectSessionFileType(pathNode.asText());
                    if (byPath != null) return byPath;
                }
                JsonNode globNode = input.get("glob");
                if (globNode != null && globNode.isTextual()) {
                    return detectSessionPatternType(globNode.asText());
                }
                return null;
            }
            case ToolNameConstants.GLOB_TOOL_NAME: {
                JsonNode pathNode = input.get("path");
                if (pathNode != null && pathNode.isTextual()) {
                    FileType byPath = detectSessionFileType(pathNode.asText());
                    if (byPath != null) return byPath;
                }
                JsonNode patternNode = input.get("pattern");
                if (patternNode != null && patternNode.isTextual()) {
                    return detectSessionPatternType(patternNode.asText());
                }
                return null;
            }
            default:
                return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 3. isMemoryFileAccess · CC :123-141
    // ════════════════════════════════════════════════════════════════

    /**
     * 工具使用是否构成 memory 文件访问 · CC {@code isMemoryFileAccess} (:123-141).
     *
     * <p>session_memory 类型 OR file_path 是 memdir (auto memory) 文件 OR team memory 文件
     * （CC :135 带编译门：{@code feature('TEAMMEM') && teamMemPaths!.isTeamMemFile(filePath)}）。
     * team 目录是 memdir 子目录，team 路径同时命中两者 —— team 分支用 feature 门 +
     * isTeamMemFile（含 teamMemoryEnabled 门控）判定；feature 关时 team 分支整体不可达，仅
     * autoMem 分支生效（与 notify 消费侧 CC :189 同根外层门，B2 S2）。
     *
     * @return true = 本次工具调用访问了会话记忆 / memdir / team memory 文件
     */
    public boolean isMemoryFileAccess(String toolName, JsonNode input) {
        if (getSessionFileTypeFromInput(toolName, input) == FileType.SESSION_MEMORY) {
            return true;
        }
        String filePath = getFilePathFromInput(toolName, input);
        return filePath != null
            && (isAutoMemFile(filePath)
                || (memoryFileDetection.teamMemPaths().isTeamMemFeatureEnabled()
                    && memoryFileDetection.isTeamMemFile(filePath)));
    }

    // ════════════════════════════════════════════════════════════════
    // 4. handleSessionFileAccess · CC :146-227
    // ════════════════════════════════════════════════════════════════

    /**
     * PostToolUse 回调 · 发射会话文件访问 analytics 事件 · CC {@code handleSessionFileAccess}.
     *
     * <p>事件 (对齐 CC 真源事件名 + subagent_name 事件属性):
     * <ul>
     *   <li>session_memory → {@code tengu_session_memory_accessed} (CC :161-162)</li>
     *   <li>session_transcript → {@code tengu_transcript_accessed} (CC :163-164)</li>
     *   <li>memdir 文件 → {@code tengu_memdir_accessed} + file_read/edit/write (CC :168-186)</li>
     *   <li>team memory 文件 → {@code tengu_team_mem_accessed} + file_read/edit/write +
     *       Edit/Write 后 notifyTeamMemoryWrite (CC :189-208)</li>
     *   <li>全事件附带 {@code subagent_name} 属性（CC :158-159）—— 仅 subagent 上下文才携带
     *       （{@code getSubagentLogName()} 非 subagent → undefined → 无该属性）</li>
     * </ul>
     *
     * @param toolName 工具名 (已由 matcher 过滤为 5 工具之一)
     * @param input    工具输入
     */
    public void handleSessionFileAccess(String toolName, JsonNode input) {
        if (telemetry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionFileAccessHooks] telemetry 未注入, 跳过埋点: tool={}", toolName);
            }
            return;
        }
        // CC :158-159 subagentName = getSubagentLogName()；subagentProps = subagentName ? {subagent_name} : {}
        java.util.Map<String, Object> subagentProps = subagentProps();
        FileType fileType = getSessionFileTypeFromInput(toolName, input);
        if (fileType == FileType.SESSION_MEMORY) {
            log.info("[SessionFileAccessHooks] 会话记忆文件被访问: tool={} subagent_name={}",
                toolName, subagentProps.get("subagent_name"));
            doubleEmit("tengu_session_memory_accessed", subagentProps);
        } else if (fileType == FileType.SESSION_TRANSCRIPT) {
            log.info("[SessionFileAccessHooks] 会话 transcript 文件被访问: tool={} subagent_name={}",
                toolName, subagentProps.get("subagent_name"));
            doubleEmit("tengu_transcript_accessed", subagentProps);
        }

        // Memdir 访问追踪 (CC :168-186)
        String filePath = getFilePathFromInput(toolName, input);
        if (filePath != null && isAutoMemFile(filePath)) {
            log.info("[SessionFileAccessHooks] memdir 文件被访问: tool={} path={}", toolName, filePath);
            doubleEmit("tengu_memdir_accessed",
                mergeAttrs(Map.of("tool", toolName), subagentProps));
            switch (toolName) {
                case ToolNameConstants.FILE_READ_TOOL_NAME ->
                    doubleEmit("tengu_memdir_file_read", subagentProps);
                case ToolNameConstants.FILE_EDIT_TOOL_NAME ->
                    doubleEmit("tengu_memdir_file_edit", subagentProps);
                case ToolNameConstants.FILE_WRITE_TOOL_NAME ->
                    doubleEmit("tengu_memdir_file_write", subagentProps);
                default -> { }
            }
        }

        // Team memory 访问追踪 (CC :189-208)：tengu_team_mem_accessed + file_read/edit/write；
        // Edit/Write 后 notifyTeamMemoryWrite（防 fs.watch 漏事件，watcher.ts:314-319）
        if (filePath != null
            && memoryFileDetection.teamMemPaths().isTeamMemFeatureEnabled()
            && memoryFileDetection.isTeamMemFile(filePath)) {
            log.info("[SessionFileAccessHooks] team memory 文件被访问: tool={} path={}", toolName, filePath);
            doubleEmit("tengu_team_mem_accessed",
                mergeAttrs(Map.of("tool", toolName), subagentProps));
            switch (toolName) {
                case ToolNameConstants.FILE_READ_TOOL_NAME ->
                    doubleEmit("tengu_team_mem_file_read", subagentProps);
                case ToolNameConstants.FILE_EDIT_TOOL_NAME -> {
                    doubleEmit("tengu_team_mem_file_edit", subagentProps);
                    if (teamMemoryWatcher != null) {
                        teamMemoryWatcher.notifyTeamMemoryWrite();
                    }
                }
                case ToolNameConstants.FILE_WRITE_TOOL_NAME -> {
                    doubleEmit("tengu_team_mem_file_write", subagentProps);
                    if (teamMemoryWatcher != null) {
                        teamMemoryWatcher.notifyTeamMemoryWrite();
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * subagent_name 事件属性 · CC original: {@code subagentProps}
     * （sessionFileAccessHooks.ts:158-159，getSubagentLogName 见 agentContext.ts:141-151）。
     *
     * <p>仅 subagent 上下文携带该属性（主线程无 subagentName → 空 map → 事件无 subagent_name）。
     * Java 端调 {@link com.nexusai.application.agent.subagent.AgentContext#getSubagentLogName()}
     * （interface static 方法隐式 public，跨包可用）。
     *
     * @return {@code {subagent_name: <name>}} 或空 map（非 subagent 上下文）
     */
    private static java.util.Map<String, Object> subagentProps() {
        String name = com.nexusai.application.agent.subagent.AgentContext.getSubagentLogName();
        return name != null ? java.util.Map.of("subagent_name", name) : java.util.Map.of();
    }

    /** 合并基础属性与 subagent_name 属性（base 不含 subagent_name 时返回 base 原 map）。 */
    private static java.util.Map<String, Object> mergeAttrs(
            java.util.Map<String, Object> base, java.util.Map<String, Object> extra) {
        if (extra.isEmpty()) {
            return base;
        }
        java.util.HashMap<String, Object> merged = new java.util.HashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    /**
     * [IMP-CM-17] 双发射遥测事件（recordEvent 1P 计数 + logOTelEvent OTel 转发 ·
     * HookRegistry:278-279 惯例）。CC original: logEvent（sessionFileAccessHooks.ts:161-186）。
     *
     * <p>WHY: 此前的 recordEvent 仅 in-memory 计数不达 OTel（全局报告 §3.8「部分缺失」），
     * 补 logOTelEvent 使 session/memdir/team 访问事件进 OTel 通道 —— 属性名/事件名与 CC 一致。
     */
    private void doubleEmit(String eventName, java.util.Map<String, Object> attrs) {
        if (telemetry == null) {
            return;
        }
        telemetry.recordEvent(eventName, attrs);
        telemetry.logOTelEvent(eventName, attrs);
    }

    // ════════════════════════════════════════════════════════════════
    // 5. registerSessionFileAccessHooks · CC :233-250
    // ════════════════════════════════════════════════════════════════

    /** CC 注册的 5 个工具 matcher (sessionFileAccessHooks.ts:242-248). */
    public static final java.util.List<String> REGISTERED_TOOLS = java.util.List.of(
        ToolNameConstants.FILE_READ_TOOL_NAME,   // Read
        ToolNameConstants.GREP_TOOL_NAME,        // Grep
        ToolNameConstants.GLOB_TOOL_NAME,        // Glob
        ToolNameConstants.FILE_EDIT_TOOL_NAME,   // Edit
        ToolNameConstants.FILE_WRITE_TOOL_NAME); // Write

    /**
     * PostToolUse 注册器 · 对齐 CC registerHookCallbacks (sessionFileAccessHooks.ts:241-249).
     *
     * <p>Java 端 {@link HookRegistry} 实现本接口 (registerPostToolUse), 测试可注入
     * 记录桩 — 与 CoordinatorPermissionHandler.HooksRunner 等注入式模式一致.
     * [D01] default internal 变体: 标记 internal callback (CC hooks.ts:1440-1442) —
     * HookRegistry 覆写以同步 internalHookNames; 未覆写时退化为普通注册 (安全).
     */
    @FunctionalInterface
    public interface PostToolUseRegistrar {
        void registerPostToolUse(String name, PostToolUseHook hook);

        /** [D01] internal 变体 · CC isInternalHook (hooks.ts:1440-1442). */
        default void registerPostToolUseInternal(String name, PostToolUseHook hook) {
            registerPostToolUse(name, hook);
        }
    }

    /**
     * 注册 5 个工具的 PostToolUse matcher hooks · CC {@code registerSessionFileAccessHooks}
     * (sessionFileAccessHooks.ts:233-250).
     *
     * <p>CC 用 registerHookCallbacks 一次性注册 Read/Grep/Glob/Edit/Write 5 个 matcher;
     * Java 端逐工具 registerPostToolUse (HookRegistry 无 matcher map, 用 hook 内 toolName
     * 过滤 — matcher 语义等价).
     *
     * @param registrar PostToolUse 注册目标 (生产传 HookRegistry, 测试传记录桩)
     */
    public void registerSessionFileAccessHooks(PostToolUseRegistrar registrar) {
        if (registrar == null) {
            log.warn("[SessionFileAccessHooks] PostToolUseRegistrar 未注入, 跳过 5 工具注册");
            return;
        }
        for (String tool : REGISTERED_TOOLS) {
            registrar.registerPostToolUseInternal("sessionFileAccess:" + tool, new PostToolUseHook() {
                @Override
                public GenericHook.HookResult onPostToolUse(
                        String toolName, JsonNode input,
                        com.nexusai.application.agent.tool.ToolResult result,
                        com.nexusai.application.agent.tool.ToolUseContext ctx, boolean stopHookActive) {
                    // matcher 过滤: 仅处理本 hook 对应工具 (CC matcher 等价)
                    if (toolName != null && toolName.equals(tool)) {
                        handleSessionFileAccess(toolName, input);
                    }
                    return GenericHook.HookResult.proceed();
                }
            });
        }
        log.info("[SessionFileAccessHooks] 注册 {} 个 PostToolUse matcher hooks: {}",
            REGISTERED_TOOLS.size(), REGISTERED_TOOLS);
    }

    // ════════════════════════════════════════════════════════════════
    // 检测辅助 · 对齐 CC memoryFileDetection.ts:40-92
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测文件路径是否为会话文件 · CC {@code detectSessionFileType} (memoryFileDetection.ts:40-59).
     *
     * <p>{@code configDir/session-memory/*.md} → session_memory;
     * {@code configDir/projects/*.jsonl} → session_transcript.
     * 委托 {@link MemoryFileDetection}（toComparable + Windows 小写化统一）。
     */
    FileType detectSessionFileType(String filePath) {
        String type = memoryFileDetection.detectSessionFileType(filePath);
        if ("session_memory".equals(type)) return FileType.SESSION_MEMORY;
        if ("session_transcript".equals(type)) return FileType.SESSION_TRANSCRIPT;
        return null;
    }

    /**
     * 检测 glob/pattern 是否为会话文件访问意图 · CC {@code detectSessionPatternType}
     * (memoryFileDetection.ts:65-82).
     */
    FileType detectSessionPatternType(String pattern) {
        String type = memoryFileDetection.detectSessionPatternType(pattern);
        if ("session_memory".equals(type)) return FileType.SESSION_MEMORY;
        if ("session_transcript".equals(type)) return FileType.SESSION_TRANSCRIPT;
        return null;
    }

    /**
     * 路径是否在 memdir (auto memory) 目录内 · CC {@code isAutoMemFile}
     * (memoryFileDetection.ts:87-92).
     *
     * <p>DEL-M-06 联动：基于 {@link AutoMemPaths#getAutoMemPath()}（per-project）判定 +
     * isAutoMemoryEnabled 门控 + normalize + Windows 小写化（OPD-M-47 / REQ-M-21）。
     */
    boolean isAutoMemFile(String filePath) {
        return memoryFileDetection.isAutoMemFile(filePath);
    }
}

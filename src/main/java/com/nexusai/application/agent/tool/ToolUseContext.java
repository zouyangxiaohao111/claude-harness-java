package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nexusai.application.agent.compact.CompactProgressEvent;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.subagent.AgentContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具调用上下文 · 对齐 CC {@code ToolUseContext}（{@code Tool.ts} 类型）。
 *
 * <p>字段顺序（最终态 47 字段, R32 b15 Stage 3.2+3.3+3.4 重建; S9 DEL-02c 删 prompt 回调后 47→46;
 * Q-30 mcpServerConnections + OPD-D1-01 fileReadingLimits 后 46→47）:
 * <ol start="0">
 *   <li>Phase 1+2+3.1 既有 17 字段</li>
 *   <li>[R32-b15 Stage 3.2 C2] 4 字段: getAppState/setAppState/setStreamMode/setSDKStatus</li>
 *   <li>[R32-b15 Stage 3.3 UI] 10 字段: addNotification/appendSystemMessage/sendOSNotification/
 *       setResponseLength/setHasInterruptibleToolInProgress/updateFileHistoryState/updateAttributionState/
 *       setConversationId/setToolJSX/openMessageSelector (prompt 回调通道 已删, S9 DEL-02c)</li>
 *   <li>[R32-b15 Stage 3.4 session] 13 字段: userModified/nestedMemoryAttachmentTriggers/loadedNestedMemoryPaths/
 *       dynamicSkillDirTriggers/discoveredSkillNames/agentType/requireCanUseTool/preserveToolUseResults/
 *       localDenialTracking/contentReplacementState/queryTracking/toolUseId/criticalSystemReminder_EXPERIMENTAL</li>
 *   <li>[Session L+ R1] 1 字段: readFileState (会话级 dedup 缓存 · 对齐 CC
 *       {@code QueryEngine.ts:191 readFileState: FileStateCache} + {@code runAgent.ts:377/705/828}
 *       父→子透传 + turn 结束 clear)</li>
 *   <li>[OPD-D1-01] 1 字段: fileReadingLimits (Read 输出上限 per-session override · 对齐 CC
 *       {@code Tool.ts:251 fileReadingLimits?: {maxTokens?, maxSizeBytes?}} + {@code FileReadTool.ts:502-516})</li>
 * </ol>
 *
 * <p>所有新增字段 (Stage 3.2 C2 4 字段 + Stage 3.3 UI 10 字段 + Stage 3.4 session 13 字段 + R1 readFileState 1 字段
 * + OPD-D1-01 fileReadingLimits 1 字段) 均
 * 加 {@code @JsonIgnore} 注解 —— 不进入 AgentState / EventPublisher / STOMP / LLM payload,
 * 符合 CLAUDE.md BudgetTracker local-only 约束.
 *
 * <h2>用途</h2>
 * <p>传给 {@link Tool#checkPermissions} / {@link Tool#validateInput}，
 * 让工具基于上下文做决策（如 mode、目录、session、permissionContext）。
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@link #agentId()} CC original: agentId? optional (Tool.ts:245) — 可省略; [session-id-short] 主线程保持 null
 *       （对齐 CC {@code !context.agentId}），不再 compact ctor 兜底派生 UUID; {@link #sessionId()} 必须非 null
 *       （short 形态 sess-xxx）</li>
 *   <li>其余字段均有 compact ctor 兜底默认（详见各字段 javadoc）</li>
 *   <li>所有 callback / session state 字段均为 {@code @JsonIgnore}</li>
 * </ul>
 *
 * @see Tool
 * @see PermissionMode
 * @see ToolPermissionContext
 */
public record ToolUseContext(
        // ═══════════════════ 1-17 既有字段 ═══════════════════
        UUID agentId,
        String sessionId,
        PermissionMode mode,
        Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
        List<Tool> availableTools,
        String taskListId,
        AbortController abortController,
        List<?> messages,
        ToolPermissionContext permissionContext,
        PermissionMode permissionMode,
        Map<String, McpClientRuntime> mcpClients,
        boolean isNonInteractiveSession,
        String renderedSystemPrompt,
        Path effectiveCwd,
        Function<Set<String>, Set<String>> inProgressToolUseIDs,
        Map<String, ToolDecisionInfo> toolDecisions,
        // [R32-b15 Stage 3.1 C13] onCompactProgress: per-session 桥接 Consumer · 严格 @JsonIgnore
        // WHY: callback 引用本身 + 其捕获的 session 状态均不可序列化 (Local-only, 对齐 BudgetTracker 约束).
        // 不加 @JsonIgnore 会让 Jackson 把 lambda 序列化时抛 InvalidDefinitionException, 或暴露 session 状态
        // 到 outbound DTO (ChatMessageDto / CompletionEvent / STOMP payload), 违反 local-only 约束.
        @JsonIgnore Consumer<CompactProgressEvent> onCompactProgress,
        // ═══════════════════ 18-21 Stage 3.2 C2 4 字段 ═══════════════════
        @JsonIgnore Function<Map<String, Object>, Map<String, Object>> getAppState,
        @JsonIgnore Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
        @JsonIgnore Consumer<SpinnerMode> setStreamMode,
        @JsonIgnore Consumer<SDKStatus> setSDKStatus,
        // ═══════════════════ 22-31 Stage 3.3 UI 10 字段 (prompt 回调通道 已删, S9 DEL-02c) ═══════════════════
        @JsonIgnore Consumer<Notification> addNotification,
        @JsonIgnore Consumer<SystemMessage> appendSystemMessage,
        @JsonIgnore Consumer<OSNotification> sendOSNotification,
        @JsonIgnore Consumer<String> setResponseLength,
        @JsonIgnore Consumer<Boolean> setHasInterruptibleToolInProgress,
        @JsonIgnore Consumer<FileHistoryState> updateFileHistoryState,
        @JsonIgnore Consumer<UiAttribution> updateAttributionState,
        @JsonIgnore Consumer<String> setConversationId,
        @JsonIgnore Consumer<Object> setToolJSX,
        @JsonIgnore Consumer<MessageSelector> openMessageSelector,
        // ═══════════════════ 33-45 Stage 3.4 session 13 字段 ═══════════════════
        @JsonIgnore boolean userModified,
        @JsonIgnore Set<String> nestedMemoryAttachmentTriggers,
        @JsonIgnore Set<String> loadedNestedMemoryPaths,
        @JsonIgnore Set<String> dynamicSkillDirTriggers,
        @JsonIgnore Set<String> discoveredSkillNames,
        @JsonIgnore String agentType,
        @JsonIgnore boolean requireCanUseTool,
        @JsonIgnore boolean preserveToolUseResults,
        @JsonIgnore Map<String, Object> localDenialTracking,
        @JsonIgnore Map<String, Object> contentReplacementState,
        @JsonIgnore Map<String, Object> queryTracking,
        @JsonIgnore String toolUseId,
        @JsonIgnore String criticalSystemReminder_EXPERIMENTAL,
        // ═══════════════════ 46 [Session L+ R1] readFileState ═══════════════════
        // [P-CC-02] 类型由 Caffeine Cache 升级为 FileStateCache (双限真 LRU).
        // WHY R1: dedup 状态从 ReadFileTool 实例字段上提到 TUC，会话级 + 跨工具共享。
        // 对齐 CC QueryEngine.ts:191 + runAgent.ts:377/705/828 + FileEditTool.ts:140/275/453/520。
        // [P-CC-02] 双限真 LRU: maxEntries=100 + maxSizeBytes=25MB (weigher 只算 content 字节),
        //   严格对齐 CC utils/fileStateCache.ts:30-39 ({@code max:100, maxSize:25MB,
        //   sizeCalculation: v => Math.max(1, Buffer.byteLength(v.content))}) — 用户
        //   2026-08-05 拍板"剩余拍板项严格和CC对齐", 撤销旧 500/100MB 放宽.
        //   替代原 Round 1 无界 ConcurrentHashMap — 此前会无限增长, 大文件 / 长 session 会放大 ctx 内存.
        // @JsonIgnore: dedup cache 是工具运行时状态, 不进 AgentState / EventPublisher / STOMP / LLM payload
        // (类比 BudgetTracker local-only 约束, 避免把会话内 dedup 状态泄漏到 outbound DTO).
        @JsonIgnore FileStateCache readFileState,
        // ═══════════════════ 47 [MCP-I-9 Q-30] mcpServerConnections ═══════════════════
        // [Q-30 连接继承] 子代理继承父 MCP 连接（连接对象数组，非 int 计数）· 对齐 CC
        // runAgent.ts:653-656 initializeAgentMcpServers(agentDefinition, toolUseContext.options.mcpClients)
        // + :685 agentOptions.mcpClients = mergedMcpClients.
        //
        // WHY 承载在 TUC（而非 AgentRunOptions）: 嵌套子代理的父连接经 CC
        // toolUseContext.options.mcpClients 跨 executor→loop→SubagentTool→executor 边界传递 —
        // Java 侧唯一跨该边界存活的对象是 ToolUseContext（loop per-turn TUC 由 base TUC 派生,
        // SubagentTool 读 mainLoop.getCurrentToolUseContext()）。父 executor Step 15 计算 mergedClients
        // 后须写入子 base TUC（Step 18 subagentCtx 重建时 withMcpServerConnections），嵌套第 2 层
        // 经 parentTUC.mcpServerConnections() 取到第 1 层 mergedClients（连接对象传递，非 int）。
        //
        // @JsonIgnore: 连接对象持有 transport/进程句柄, 是运行时状态, 绝不进 AgentState /
        // EventPublisher / STOMP / LLM payload（同 BudgetTracker local-only 约束）。
        @JsonIgnore java.util.List<com.nexusai.application.agent.subagent.AgentMcpServers.McpServerConnection> mcpServerConnections,
        // ═══════════════════ 47 [OPD-D1-01] fileReadingLimits ═══════════════════
        // Read 输出上限 per-session override · 对齐 CC Tool.ts:251-254
        //   {@code fileReadingLimits?: { maxTokens?: number, maxSizeBytes?: number }}。
        // 消费方 ReadFileTool.ts:502-516：{@code maxSizeBytes = fileReadingLimits?.maxSizeBytes ??
        //   getDefaultFileReadingLimits().maxSizeBytes}；maxTokens 同理；override 有值才触发
        //   tengu_file_read_limits_override 埋点（:511-516）。
        // 默认 null = 无覆写（回退 env/GB 默认），compact ctor 不兜底赋值。
        // @JsonIgnore: 会话级运行配置，不进 AgentState / EventPublisher / STOMP / LLM payload
        //   （同 readFileState / mcpServerConnections local-only 约束）。
        @JsonIgnore FileReadingLimits.Override fileReadingLimits,
        // ═══════════════════ 48 [openai-lazy] effectiveModelName ═══════════════════
        // 当前 turn 有效模型名 · Java 扩展（无 CC 对应字段，对齐 CC toolUseContext.options.model? 语义）。
        // 消费方 ToolSearchTool.execute：按 modelSupportsToolReference 分流渲染——
        //   Anthropic/Claude（支持 tool_reference）→ ToolSearch 命中保持纯 tool_reference 块（CC 原样）；
        //   openai_compatible（deepseek）→ 追加完整 JSONSchema 文本（模型直接拿参数调用）。
        // 由 AgentLoopContext.toolExecContext 从 AgentState.currentModel() 注入。
        // @JsonIgnore: 会话运行信息，不进 AgentState / EventPublisher / STOMP / LLM payload
        //   （同 readFileState / mcpServerConnections / fileReadingLimits local-only 约束）。
        @JsonIgnore String effectiveModelName
        // [Session J 方案 A] 撤回 E session 加的 querySource + assistantMessage 顶层字段:
        //   - CC 真源 (主 agent grep 实证 Pattern #9):
        //     · querySource: toolUseContext.options.querySource (Tool.ts:176), Java 端对齐
        //       AgentOptions.querySource (createSubagentContext.java:52)
        //     · assistantMessage: AgentTool.call() 方法第 4 参数 (AgentTool.tsx:250),
        //       不是 ToolUseContext 字段
        //   - 撤回方案: 按用户授权"对齐CC 可破约不留兼容壳", querySource 改用
        //     AgentOptions.querySource, assistantMessage 改用 SubagentTool.execute 五参方法参数透传.
) {

    // ════════════════════════════════════════════════════════════════════════
    // [P-CC-02] readFileState 双限 LRU 工厂 + 克隆
    // 用户 2026-08-05 拍板"剩余拍板项严格和CC对齐": 100 条 / 25MB (CC = 100 / 25MB).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [P-CC-02] readFileState 默认条目数上限 · <b>用户 2026-08-05 拍板严格对齐 CC</b>:
     * 100. CC {@code utils/fileStateCache.ts:18} READ_FILE_STATE_CACHE_SIZE=100 直译,
     * 撤销旧值 500 (L+ round 5 曾按"用户后续指令"放宽, 该指令全目录无记录, 无法追溯).
     */
    public static final int READ_FILE_STATE_CACHE_SIZE = 100;

    /**
     * [P-CC-02] readFileState 默认字节上限 · <b>用户 2026-08-05 拍板严格对齐 CC</b>:
     * 25MB. CC {@code utils/fileStateCache.ts:22} DEFAULT_MAX_CACHE_SIZE_BYTES=
     * 25*1024*1024 直译, 撤销旧值 100MB (L+ round 5 曾按"用户后续指令"放宽,
     * 该指令全目录无记录, 无法追溯).
     *
     * <p>WHY 25MB 足够: CC 以 25MB 防无界内存增长 (fileStateCache.ts:20-21 注释
     * "prevents unbounded memory growth from large file contents"); 单条 content
     * 超限时走 lru-cache maxEntrySize reject (v11.5.2 index.js:919-927: 删除已有同 key、
     * 不插入、不驱逐其它 entry), 不会因大文件撑爆缓存也不会连带清空其它 entry.
     */
    public static final long DEFAULT_MAX_CACHE_SIZE_BYTES = 25L * 1024L * 1024L;

    /**
     * 计算 {@link ReadState} 的 weight · <b>weigher 语义</b>严格对齐 CC
     * {@code utils/fileStateCache.ts:37}
     * {@code sizeCalculation: value => Math.max(1, Buffer.byteLength(value.content))}.
     *
     * <p><b>WHY 只算 content 字段</b>: mtimeMillis / offset / limit / isPartialView 是
     * 元数据 (字节 < 50), 算入会污染权重 → 单条会被判定为" >25MB" 触发驱逐. CC 源码明确
     * 只看 {@code value.content}.
     *
     * <p><b>WHY {@code Math.max(1, ...)}</b>: CC 源码同样兜底 (防止 size=0 让驱逐循环
     * 做无效的 eviction 决策). Java 端取 content 字节的 UTF-8 表示, 与
     * {@code Buffer.byteLength(value.content)} (Node.js) 对 ASCII 等价, 对
     * 多字节字符两者都正确按 UTF-8 计字节.
     *
     * @param state 待计算权重的 ReadState
     * @return 该 entry 占据的权重字节数 (≥1)
     */
    public static int weightOf(ReadState state) {
        if (state == null || state.content() == null) {
            return 1;  // 对齐 CC Math.max(1, ...) 兜底
        }
        return Math.max(1, state.content().getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 创建一个新的 readFileState 双限 LRU Cache · 行为对齐 CC
     * {@code utils/fileStateCache.ts:101-106} {@code createFileStateCacheWithSizeLimit(maxEntries, maxSizeBytes)}.
     *
     * <p>默认配置 (调用无参): maxEntries=100, maxSizeBytes=25MB, weigher=Math.max(1, content 字节)
     * — 用户 2026-08-05 拍板严格对齐 CC (CC=100/25MB).
     *
     * <p><b>WHY 双限 (maxEntries + maxSizeBytes) 而非单限</b>: CC LRUCache 构造
     * (fileStateCache.ts:34-38) {@code max}/{@code maxSize} 同时强制, 任一超限即驱逐;
     * 旧实现 (L+ round 5) 因 Caffeine maximumSize/maximumWeight 互斥只设字节限,
     * 条目数上限"隐式", 小文件场景可驻留远超 100 条 — 偏离 CC {@code max:100} 硬限.
     *
     * <p><b>eviction 策略</b>: {@link FileStateCache} 用 {@code LinkedHashMap}
     * {@code accessOrder=true} 实现真 LRU (与 CC lru-cache 同序), 同步驱逐.
     */
    public static FileStateCache createFileStateCache() {
        return createFileStateCache(
            READ_FILE_STATE_CACHE_SIZE, DEFAULT_MAX_CACHE_SIZE_BYTES);
    }

    /**
     * 带容量配置的 readFileState factory · 测试用 · 主路径走 {@link #createFileStateCache()}.
     *
     * <p><b>双参都生效</b>: {@code maxEntries} 条目数硬限 + {@code maxSizeBytes} 字节总量硬限
     * (CC {@code createFileStateCacheWithSizeLimit(maxEntries, maxSizeBytes)} 语义,
     * fileStateCache.ts:101-106; 构造选项 fileStateCache.ts:34-38).
     *
     * @param maxEntries   条目数上限 (CC original: {@code max})
     * @param maxSizeBytes 字节总量上限 (CC original: {@code maxSize})
     */
    public static FileStateCache createFileStateCache(
            long maxEntries, long maxSizeBytes) {
        return new FileStateCache(Math.toIntExact(maxEntries), maxSizeBytes);
    }

    /**
     * 克隆一个 readFileState Cache (capacity + entries) · 行为对齐 CC
     * {@code utils/fileStateCache.ts:122-126} {@code cloneFileStateCache}.
     *
     * <p><b>WHY 沿用源容量而非默认容量</b>: CC :123
     * {@code createFileStateCacheWithSizeLimit(cache.max, cache.maxSize)} — clone 出来的
     * 新 cache 用源 cache 当时构造的 max/maxSize; 生产全为默认容量 (100/25MB), 自定义
     * 容量场景 (测试工厂) 也严格一致.
     *
     * <p><b>WHY entries() 而非 asMap()</b>: FileStateCache 方法面严格对齐 CC
     * (fileStateCache.ts:41-84, 无 asMap); CC clone 用 {@code cache.dump()}/{@code load()}
     * (快照语义), Java 等价 = {@code entries()} 快照 + 逐条 {@code set}.
     *
     * <p><b>recency 语义（R2 返工实证）</b>: lru-cache {@code dump()} 输出 LRU→MRU
     * （{@code #indexes()} 从 tail=MRU 迭代 + {@code arr.unshift()} 反转，v10.4.3
     * index.js:816-835 / v11.5.2 index.js:812-831 自验 + Node 实测 E-PCC-02-07）；
     * {@code load()} 按数组顺序 {@code set}，最后 set 的源 MRU 成为子缓存 MRU →
     * <b>recency 保持（不反转）</b>。Java {@code entries()}（LinkedHashMap
     * accessOrder 迭代序）同为 LRU→MRU + 顺序 {@code set} → 与 CC 逐出序一致
     * （reflection R2「CC recency 反转」断言经源码+实测证伪，见 progress §9 C3）。
     *
     * @param source 父 cache (可为 null, null 时返回新空 cache)
     * @return 子 cache: 与父独立 (改 entries 不影响父), 沿用源容量配置
     */
    public static FileStateCache cloneFileStateCache(
            FileStateCache source) {
        if (source == null) {
            return createFileStateCache();
        }
        FileStateCache cloned = createFileStateCache(source.max(), source.maxSize());
        Iterator<Map.Entry<String, ReadState>> it = source.entries();
        while (it.hasNext()) {
            Map.Entry<String, ReadState> e = it.next();
            cloned.set(e.getKey(), e.getValue());
        }
        return cloned;
    }


    /**
     * compact constructor：不变量保护 + 防御性 copy + 全字段兜底默认.
     *
     * <p>WHY: sessionId 为 null 会让 hook / 审计日志失去归因 (必填抛异常);
     * [session-id-short] agentId 兜底已删（主线程 ctx.agentId() 保持 null，对齐 CC
     * {@code !context.agentId} 主线程判定；子代理路径都显式传非 null agentId）;
     * 外部 mutate Map 会污染上层规则匹配.
     *
     * <p>Stage 3.2 C2 4 字段: null → identity / noop Consumer 兜底.
     * <p>Stage 3.3 UI 10 字段: null → noop Consumer 兜底 (prompt 回调通道 已删, S9 DEL-02c).
     *     boolean 默认 false, String/Map 默认 null.
     */
    public ToolUseContext {
        // [session-id-short] agentId 兜底删除 —— 主线程 ctx.agentId()=null（对齐 CC !context.agentId），
        // 子代理路径（SubagentExecutor/createSubagentContext）都显式传非 null packed agentId。
        if (sessionId == null) {
            throw new IllegalArgumentException("ToolUseContext.sessionId is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("ToolUseContext.mode is required");
        }
        additionalWorkingDirectories = additionalWorkingDirectories == null
                ? Map.of()
                : Map.copyOf(additionalWorkingDirectories);
        availableTools = availableTools == null
                ? List.of()
                : List.copyOf(availableTools);
        if (taskListId == null) {
            taskListId = "";
        }
        if (abortController == null) {
            abortController = AbortController.NOOP;
        }
        if (messages == null) {
            messages = List.of();
        } else {
            messages = List.copyOf(messages);
        }
        if (permissionMode == null) {
            permissionMode = PermissionMode.DEFAULT;
        }
        mcpClients = mcpClients == null
                ? Map.of()
                : Map.copyOf(mcpClients);
        if (renderedSystemPrompt == null) {
            renderedSystemPrompt = "";
        }
        // [WF-1A · DEL-02] effectiveCwd 兜底走统一入口 CwdResolution（对齐 CC getCwd()，
        // 不直读 user.dir · INV-6）。生产 buildBaseToolUseContext 传 null → 此处经
        // CwdResolution.getCwd(sessionId) 解析（override ?? sessionCwd ?? boundProject ??
        // user.dir）。effectiveCwd 字段作「上下文快照」供技能发现 / 权限 baseDir 等消费
        // （已知简化：快照在 TUC 构造时冻结，非每调用取；文件工具相对路径基准走
        // PathGuard.workdir() 动态每调用取，INV-1 由 PathGuard 承载）。
        if (effectiveCwd == null) {
            effectiveCwd = Path.of(com.nexusai.application.agent.agent.CwdResolution.getCwd(sessionId));
        }
        if (inProgressToolUseIDs == null) {
            inProgressToolUseIDs = set -> Set.of();
        }
        toolDecisions = toolDecisions == null
                ? Map.of()
                : Map.copyOf(toolDecisions);
        if (onCompactProgress == null) {
            onCompactProgress = event -> {};
        }
        // [MCP-I-9 Q-30] mcpServerConnections 兜底：null → 空列表（主链/base TUC 无父连接，
        //   子代理 TUC 由父 executor Step 15 mergedClients 显式写入）。
        if (mcpServerConnections == null) {
            mcpServerConnections = List.of();
        }
        // [Stage 3.2 C2] 4 字段兜底
        if (getAppState == null) {
            getAppState = s -> s;
        }
        if (setAppState == null) {
            setAppState = updater -> {};
        }
        if (setStreamMode == null) {
            setStreamMode = m -> {};
        }
        if (setSDKStatus == null) {
            setSDKStatus = s -> {};
        }
        // [Stage 3.3 UI] 11 字段兜底
        if (addNotification == null) addNotification = value -> {};
        if (appendSystemMessage == null) appendSystemMessage = value -> {};
        if (sendOSNotification == null) sendOSNotification = value -> {};
        if (setResponseLength == null) setResponseLength = value -> {};
        if (setHasInterruptibleToolInProgress == null) setHasInterruptibleToolInProgress = value -> {};
        if (updateFileHistoryState == null) updateFileHistoryState = value -> {};
        if (updateAttributionState == null) updateAttributionState = value -> {};
        if (setConversationId == null) setConversationId = value -> {};
        if (setToolJSX == null) setToolJSX = value -> {};
        if (openMessageSelector == null) openMessageSelector = value -> {};
        // [Stage 3.4 session] 13 字段兜底
        // 4 个 Set 必须可变 (CC 工具会直接 .add()/.clear()) — 不能用 Set.copyOf 不可变集合
        // [ODF-B4R-LAZY 返工 finding 2] nestedMemoryAttachmentTriggers / loadedNestedMemoryPaths 改
        //   keepOrCopyMutableSet（对齐下方 dynamicSkillDirTriggers :360-365 共享引用语义）：lazy-load
        //   触发链是"文件工具 .add() → loop 每轮 getNestedMemoryAttachments 消费 .clear()"操作同一
        //   会话级 Set（CC attachments.ts:848/870/1038 生产者 + :2165-2190 消费者）。base TUC（
        //   buildBaseToolUseContext 传 session KeySetView ref）与 per-turn TUC（copyWith 派生）必须
        //   共享同一实例，否则触发集写入对 loop 消费端不可见、triggers.clear() 只清副本 —— 副本隔离。
        nestedMemoryAttachmentTriggers = nestedMemoryAttachmentTriggers == null
                ? ConcurrentHashMap.newKeySet()
                : keepOrCopyMutableSet(nestedMemoryAttachmentTriggers);
        loadedNestedMemoryPaths = loadedNestedMemoryPaths == null
                ? ConcurrentHashMap.newKeySet()
                : keepOrCopyMutableSet(loadedNestedMemoryPaths);
        // P1-2: dynamicSkillDirTriggers / discoveredSkillNames 保持同一可变 Set 引用（CC 共享语义）：
        //   QueryEngine.ts:372 new Set per submitMessage 传给 toolUseContext，文件工具 .add() 与
        //   getDynamicSkillAttachments .clear()（attachments.ts:2597）操作同一对象。Java 端 loop 传
        //   session 级 ConcurrentHashMap.newKeySet()（KeySetView），keepOrCopyMutableSet 对 KeySetView
        //   保持引用，让文件工具写入对 LlmAgentLoop A8 装配侧可见；不可变输入（Set.copyOf）仍复制为可变
        //   （对齐 R32B15Stage3_4 测试 "不可变 Set 输入被转换"）。
        dynamicSkillDirTriggers = dynamicSkillDirTriggers == null
                ? ConcurrentHashMap.newKeySet()
                : keepOrCopyMutableSet(dynamicSkillDirTriggers);
        discoveredSkillNames = discoveredSkillNames == null
                ? ConcurrentHashMap.newKeySet()
                : keepOrCopyMutableSet(discoveredSkillNames);
        // 3 个 Map 兜底 null (canonical source 在 AgentState / DenialTracker 全局, TUC 字段为 local bridge/view)
        if (localDenialTracking != null) {
            localDenialTracking = Map.copyOf(localDenialTracking);
        }
        if (contentReplacementState != null) {
            contentReplacementState = Map.copyOf(contentReplacementState);
        }
        if (queryTracking != null) {
            queryTracking = Map.copyOf(queryTracking);
        }
        // agentType / toolUseId / criticalReminder 兜底 null, userModified 默认 false (record 默认)
        // [Session L+ R1] readFileState 兜底 null → createFileStateCache() 双限真 LRU.
        // [P-CC-02] WHY: 1) dedup cache 跨工具共享, 必须可变 (ReadFileTool / EditFileTool /
        //     WriteFileTool 都会 .set()); FileStateCache 自身是 thread-safe, 无需外层 ConcurrentHashMap.
        //     2) 不可变 Map.copyOf 会让 ReadFileTool dispatchText set 时抛 UnsupportedOperationException;
        //     FileStateCache set 无此限制.
        //     3) 每次新建 ctx 默认独立 (per-session 隔离), createSubagentContext.create 显式 clone 父 ctx.
        //     4) [P-CC-02] 双限真 LRU: maxEntries=100 + maxSizeBytes=25MB 防无界增长
        //     (用户 2026-08-05 拍板严格对齐 CC).
        // WHY 不复制传入 cache: FileStateCache 已 thread-safe 且本类不修改 cache 内容, 共享引用即可;
        //   如需独立 (subagent fork), 调用方走 cloneFileStateCache() 显式 clone.
        if (readFileState == null) {
            readFileState = createFileStateCache();
        }
        // [OPD-D1-01] fileReadingLimits 兜底 null (CC Tool.ts:251 fileReadingLimits? optional = undefined).
        // 无需显式赋值: record compact 参数即组件, null 天然保留; 有值时由 withFileReadingLimits wither 提供.
    }

    /**
     * Set 兜底为可变并发 Set (CC 工具会 .add()/.clear()).
     * 输入 Set 复制到新的可变 ConcurrentHashMap.newKeySet().
     */
    private static Set<String> copyToMutableSet(Set<String> src) {
        Set<String> dst = ConcurrentHashMap.newKeySet();
        if (src != null) {
            dst.addAll(src);
        }
        return dst;
    }

    /**
     * P1-2: dynamicSkillDirTriggers/discoveredSkillNames 专用 —— 已是可变并发 Set（KeySetView，
     * ConcurrentHashMap.newKeySet() 产物）→ 保持同一引用（CC 共享 Set 语义，loop 装配侧读取文件工具
     * 写入的同一对象）；不可变 Set（Set.copyOf 等）→ 复制为可变（对齐既有测试 "不可变输入被转换"）。
     */
    private static Set<String> keepOrCopyMutableSet(Set<String> src) {
        if (src instanceof ConcurrentHashMap.KeySetView) {
            return src;
        }
        return copyToMutableSet(src);
    }

    // ════════════════════════════════════════════════════════════════════
    // 向后兼容构造器 (delegation 链, 全 45 字段)
    // ════════════════════════════════════════════════════════════════════

    /**
     * [MCP-I-9 Q-30] 45 参兼容构造器（缺 mcpServerConnections）· 供既有 full-arg 调用方
     * 零改动迁移（canonical 已扩为 46 参；prompt 回调通道 已删, S9 DEL-02c）。
     * 新字段缺省 → compact ctor 兜底 {@code List.of()}。
     */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                          Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                          List<Tool> availableTools, String taskListId, AbortController abortController,
                          List<?> messages, ToolPermissionContext permissionContext, PermissionMode permissionMode,
                          Map<String, McpClientRuntime> mcpClients, boolean isNonInteractiveSession,
                          String renderedSystemPrompt, Path effectiveCwd,
                          Function<Set<String>, Set<String>> inProgressToolUseIDs,
                          Map<String, ToolDecisionInfo> toolDecisions,
                          Consumer<CompactProgressEvent> onCompactProgress,
                          Function<Map<String, Object>, Map<String, Object>> getAppState,
                          Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                          Consumer<SpinnerMode> setStreamMode, Consumer<SDKStatus> setSDKStatus,
                          Consumer<Notification> addNotification,
                          Consumer<SystemMessage> appendSystemMessage,
                          Consumer<OSNotification> sendOSNotification,
                         Consumer<String> setResponseLength,
                         Consumer<Boolean> setHasInterruptibleToolInProgress,
                         Consumer<FileHistoryState> updateFileHistoryState,
                          Consumer<UiAttribution> updateAttributionState,
                          Consumer<String> setConversationId,
                          Consumer<Object> setToolJSX,
                          Consumer<MessageSelector> openMessageSelector,
                          boolean userModified,
                          Set<String> nestedMemoryAttachmentTriggers,
                          Set<String> loadedNestedMemoryPaths,
                          Set<String> dynamicSkillDirTriggers,
                          Set<String> discoveredSkillNames,
                          String agentType,
                          boolean requireCanUseTool,
                          boolean preserveToolUseResults,
                          Map<String, Object> localDenialTracking,
                          Map<String, Object> contentReplacementState,
                          Map<String, Object> queryTracking,
                          String toolUseId,
                          String criticalSystemReminder_EXPERIMENTAL,
                          FileStateCache readFileState) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, effectiveCwd, inProgressToolUseIDs,
             toolDecisions, onCompactProgress,
             getAppState, setAppState, setStreamMode, setSDKStatus,
             addNotification, appendSystemMessage, sendOSNotification, setResponseLength,
             setHasInterruptibleToolInProgress, updateFileHistoryState, updateAttributionState,
             setConversationId, setToolJSX, openMessageSelector,
             userModified, nestedMemoryAttachmentTriggers, loadedNestedMemoryPaths,
             dynamicSkillDirTriggers, discoveredSkillNames, agentType, requireCanUseTool,
             preserveToolUseResults, localDenialTracking, contentReplacementState,
             queryTracking, toolUseId, criticalSystemReminder_EXPERIMENTAL,
             readFileState,
             null);   // [MCP-I-9 Q-30] mcpServerConnections 缺省 → compact ctor 兜底 List.of()
    }

    /**
     * [OPD-D1-01] 46 参兼容构造器（缺 fileReadingLimits）· 供既有 full-arg 调用方零改动迁移
     * （canonical 已扩为 47 参）。
     * 新字段缺省 → compact ctor 兜底 {@code null}（CC Tool.ts:251 fileReadingLimits? optional = undefined）。
     */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                          Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                          List<Tool> availableTools, String taskListId, AbortController abortController,
                          List<?> messages, ToolPermissionContext permissionContext, PermissionMode permissionMode,
                          Map<String, McpClientRuntime> mcpClients, boolean isNonInteractiveSession,
                          String renderedSystemPrompt, Path effectiveCwd,
                          Function<Set<String>, Set<String>> inProgressToolUseIDs,
                          Map<String, ToolDecisionInfo> toolDecisions,
                          Consumer<CompactProgressEvent> onCompactProgress,
                          Function<Map<String, Object>, Map<String, Object>> getAppState,
                          Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                          Consumer<SpinnerMode> setStreamMode, Consumer<SDKStatus> setSDKStatus,
                          Consumer<Notification> addNotification,
                          Consumer<SystemMessage> appendSystemMessage,
                          Consumer<OSNotification> sendOSNotification,
                         Consumer<String> setResponseLength,
                         Consumer<Boolean> setHasInterruptibleToolInProgress,
                         Consumer<FileHistoryState> updateFileHistoryState,
                          Consumer<UiAttribution> updateAttributionState,
                          Consumer<String> setConversationId,
                          Consumer<Object> setToolJSX,
                          Consumer<MessageSelector> openMessageSelector,
                          boolean userModified,
                          Set<String> nestedMemoryAttachmentTriggers,
                          Set<String> loadedNestedMemoryPaths,
                          Set<String> dynamicSkillDirTriggers,
                          Set<String> discoveredSkillNames,
                          String agentType,
                          boolean requireCanUseTool,
                          boolean preserveToolUseResults,
                          Map<String, Object> localDenialTracking,
                          Map<String, Object> contentReplacementState,
                          Map<String, Object> queryTracking,
                          String toolUseId,
                          String criticalSystemReminder_EXPERIMENTAL,
                          FileStateCache readFileState,
                          java.util.List<com.nexusai.application.agent.subagent.AgentMcpServers.McpServerConnection> mcpServerConnections) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, effectiveCwd, inProgressToolUseIDs,
             toolDecisions, onCompactProgress,
             getAppState, setAppState, setStreamMode, setSDKStatus,
             addNotification, appendSystemMessage, sendOSNotification, setResponseLength,
             setHasInterruptibleToolInProgress, updateFileHistoryState, updateAttributionState,
             setConversationId, setToolJSX, openMessageSelector,
             userModified, nestedMemoryAttachmentTriggers, loadedNestedMemoryPaths,
             dynamicSkillDirTriggers, discoveredSkillNames, agentType, requireCanUseTool,
             preserveToolUseResults, localDenialTracking, contentReplacementState,
             queryTracking, toolUseId, criticalSystemReminder_EXPERIMENTAL,
             readFileState,
             mcpServerConnections,
             null,     // [OPD-D1-01] fileReadingLimits 缺省 → compact ctor 兜底 null (CC optional)
             null);    // [openai-lazy] effectiveModelName 缺省 → null（未知模型，ToolSearchTool 分流判支持）
    }

    /** Stage 3.1 4 参兼容构造器. */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 5 参兼容构造器 (s12 方案 C availableTools). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 6 参兼容构造器 (s12-2.3 taskListId). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 8 参兼容构造器 (Phase 2 PR 1 messages + abortController). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 10 参兼容构造器 (Phase 2 PR 1 permissionContext + permissionMode). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, Map.of(), false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 11 参兼容构造器 (P1.3 mcpClients). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients, false, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 12 参兼容构造器 (P1.3 isNonInteractiveSession). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients, boolean isNonInteractiveSession) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, "", null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 13 参兼容构造器 (P1.3 renderedSystemPrompt). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients,
                         boolean isNonInteractiveSession,
                         String renderedSystemPrompt) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, null, null, null, null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 15 参兼容构造器 (Phase A + R32-b8 #3 effectiveCwd + inProgressToolUseIDs). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients,
                         boolean isNonInteractiveSession,
                         String renderedSystemPrompt,
                         Path effectiveCwd,
                         Function<Set<String>, Set<String>> inProgressToolUseIDs) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, effectiveCwd,
             inProgressToolUseIDs, Map.of(), null,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.1 17 参兼容构造器 (R32-b12 D-4 + Stage 3.1 C13 toolDecisions + onCompactProgress).
     *  Stage 3.2 C2 4 字段 + Stage 3.3 UI 11 字段 + Stage 3.4 session 13 字段 传 null → compact ctor 兜底 noop / identity. */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients,
                         boolean isNonInteractiveSession,
                         String renderedSystemPrompt,
                         Path effectiveCwd,
                         Function<Set<String>, Set<String>> inProgressToolUseIDs,
                         Map<String, ToolDecisionInfo> toolDecisions,
                         Consumer<CompactProgressEvent> onCompactProgress) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode,
             mcpClients, isNonInteractiveSession, renderedSystemPrompt,
             effectiveCwd, inProgressToolUseIDs, toolDecisions, onCompactProgress,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }
    /** [Session L+ R1] 18 参兼容构造器 (含 readFileState 透传).
     *  Stage 3.2 C2 4 字段 + Stage 3.3 UI 11 字段 + Stage 3.4 session 13 字段 传 null → compact ctor 兜底.
     *  [P-CC-02] readFileState 类型为 FileStateCache (双限真 LRU, 对齐 CC fileStateCache.ts:30-93). */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId,
                         AbortController abortController, List<?> messages,
                         ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients,
                         boolean isNonInteractiveSession,
                         String renderedSystemPrompt,
                         Path effectiveCwd,
                         Function<Set<String>, Set<String>> inProgressToolUseIDs,
                         Map<String, ToolDecisionInfo> toolDecisions,
                         Consumer<CompactProgressEvent> onCompactProgress,
                         FileStateCache readFileState) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode,
             mcpClients, isNonInteractiveSession, renderedSystemPrompt,
             effectiveCwd, inProgressToolUseIDs, toolDecisions, onCompactProgress,
             null, null, null, null,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null,
             readFileState, null);
    }

    /** Stage 3.2 21 参兼容构造器 (含 4 C2 字段).
     *  Stage 3.3 UI 11 字段 + Stage 3.4 session 13 字段 传 null → compact ctor 兜底. */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId, AbortController abortController,
                         List<?> messages, ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients, boolean isNonInteractiveSession,
                         String renderedSystemPrompt, Path effectiveCwd,
                         Function<Set<String>, Set<String>> inProgressToolUseIDs,
                         Map<String, ToolDecisionInfo> toolDecisions,
                         Consumer<CompactProgressEvent> onCompactProgress,
                         Function<Map<String, Object>, Map<String, Object>> getAppState,
                         Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                         Consumer<SpinnerMode> setStreamMode, Consumer<SDKStatus> setSDKStatus) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, effectiveCwd, inProgressToolUseIDs,
             toolDecisions, onCompactProgress,
             getAppState, setAppState, setStreamMode, setSDKStatus,
             null, null, null, null, null, null, null, null, null, null,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    /** Stage 3.3 32 参兼容构造器 (含 C2 4 + UI 11 字段).
     *  Stage 3.4 session 13 字段 传 null → compact ctor 兜底. */
    public ToolUseContext(UUID agentId, String sessionId, PermissionMode mode,
                         Map<String, AdditionalWorkingDirectory> additionalWorkingDirectories,
                         List<Tool> availableTools, String taskListId, AbortController abortController,
                         List<?> messages, ToolPermissionContext permissionContext, PermissionMode permissionMode,
                         Map<String, McpClientRuntime> mcpClients, boolean isNonInteractiveSession,
                         String renderedSystemPrompt, Path effectiveCwd,
                         Function<Set<String>, Set<String>> inProgressToolUseIDs,
                         Map<String, ToolDecisionInfo> toolDecisions,
                         Consumer<CompactProgressEvent> onCompactProgress,
                         Function<Map<String, Object>, Map<String, Object>> getAppState,
                         Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                         Consumer<SpinnerMode> setStreamMode, Consumer<SDKStatus> setSDKStatus,
                         Consumer<Notification> addNotification,
                         Consumer<SystemMessage> appendSystemMessage,
                         Consumer<OSNotification> sendOSNotification,
                         Consumer<String> setResponseLength,
                         Consumer<Boolean> setHasInterruptibleToolInProgress,
                         Consumer<FileHistoryState> updateFileHistoryState,
                         Consumer<UiAttribution> updateAttributionState,
                         Consumer<String> setConversationId,
                         Consumer<Object> setToolJSX,
                         Consumer<MessageSelector> openMessageSelector) {
        this(agentId, sessionId, mode, additionalWorkingDirectories, availableTools, taskListId,
             abortController, messages, permissionContext, permissionMode, mcpClients,
             isNonInteractiveSession, renderedSystemPrompt, effectiveCwd, inProgressToolUseIDs,
             toolDecisions, onCompactProgress,
             getAppState, setAppState, setStreamMode, setSDKStatus,
             addNotification, appendSystemMessage, sendOSNotification, setResponseLength,
             setHasInterruptibleToolInProgress, updateFileHistoryState, updateAttributionState,
             setConversationId, setToolJSX, openMessageSelector,
             false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 便利工厂 of(...) · 委托 17 / 21 / 32 参 canonical delegation
    // ════════════════════════════════════════════════════════════════════

    /** 2 字段便利工厂: 默认 mode=DEFAULT, 全部 43 余字段 null/默认. */
    public static ToolUseContext of(UUID agentId, String sessionId) {
        return new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, null);
    }

    /** 3 字段便利工厂: 含 mode. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, null);
    }

    /** 4 字段便利工厂: 含 mode + availableTools (s12 方案 C). */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, null);
    }

    /** 5 字段便利工厂: s12-2.3 含 taskListId. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, null);
    }

    /** 6 字段便利工厂: 含 abortController (R28). */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId, abortController, List.of(), null, PermissionMode.DEFAULT, null);
    }

    /** 7 字段便利工厂: 含 messages (R28.3). */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId, abortController, messages, null, PermissionMode.DEFAULT, null);
    }

    /** 9 字段便利工厂: 含 permissionContext + permissionMode (Phase 2 PR 1). */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT, null);
    }

    /** 12 字段便利工厂: P1.3 含 MCP + 非交互 + 渲染提示词. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, null, null, null, null);
    }

    /** 13 字段便利工厂: Phase A 含 effectiveCwd. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt,
                                   Path effectiveCwd) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, effectiveCwd, null, null, null);
    }

    /** 14 字段便利工厂: R32-b8 #3 含 inProgressToolUseIDs. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt,
                                   Path effectiveCwd,
                                   Function<Set<String>, Set<String>> inProgressToolUseIDs) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, effectiveCwd,
            inProgressToolUseIDs, null, null);
    }

    /** 15 字段便利工厂: R32-b12 D-4 含 toolDecisions. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt,
                                   Path effectiveCwd,
                                   Function<Set<String>, Set<String>> inProgressToolUseIDs,
                                   Map<String, ToolDecisionInfo> toolDecisions) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, effectiveCwd,
            inProgressToolUseIDs, toolDecisions, null, null);
    }

    /** 16 字段便利工厂: R32-b15 Stage 3.1 C13 含 onCompactProgress. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt,
                                   Path effectiveCwd,
                                   Function<Set<String>, Set<String>> inProgressToolUseIDs,
                                   Map<String, ToolDecisionInfo> toolDecisions,
                                   Consumer<CompactProgressEvent> onCompactProgress) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, effectiveCwd,
            inProgressToolUseIDs, toolDecisions, onCompactProgress, null);
    }

    /** 20 字段便利工厂: R32-b15 Stage 3.2 C2 含 4 C2 桥接字段.
     *  Stage 3.3 UI 11 字段 + Stage 3.4 session 13 字段 传 null → compact ctor 兜底. */
    public static ToolUseContext of(UUID agentId, String sessionId, PermissionMode mode,
                                   List<Tool> availableTools, String taskListId,
                                   AbortController abortController, List<?> messages,
                                   ToolPermissionContext permissionContext, PermissionMode permissionMode,
                                   Map<String, McpClientRuntime> mcpClients,
                                   boolean isNonInteractiveSession,
                                   String renderedSystemPrompt,
                                   Path effectiveCwd,
                                   Function<Set<String>, Set<String>> inProgressToolUseIDs,
                                   Map<String, ToolDecisionInfo> toolDecisions,
                                   Consumer<CompactProgressEvent> onCompactProgress,
                                   Function<Map<String, Object>, Map<String, Object>> getAppState,
                                   Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState,
                                   Consumer<SpinnerMode> setStreamMode,
                                   Consumer<SDKStatus> setSDKStatus) {
        return new ToolUseContext(agentId, sessionId, mode, Map.of(), availableTools, taskListId,
            abortController, messages, permissionContext,
            permissionMode != null ? permissionMode : PermissionMode.DEFAULT,
            mcpClients, isNonInteractiveSession, renderedSystemPrompt, effectiveCwd,
            inProgressToolUseIDs, toolDecisions, onCompactProgress,
            getAppState, setAppState, setStreamMode, setSDKStatus);
    }

    /**
     * [Session L+ R1] ReadFileTool dedup 缓存条目 · 对齐 CC FileStateCache
     * ({@code utils/fileStateCache.ts}) + FileState 5 字段
     * (content / timestamp / offset / limit / isPartialView).
     *
     * <p>WHY R1 上提: dedup 状态从 ReadFileTool 实例字段上提到 TUC 字段 (Map&lt;String, ReadState&gt;),
     * 会话级 + 跨工具共享 (ReadFileTool / EditFileTool / WriteFileTool / BashTool 都能读写同一 cache),
     * 对齐 CC {@code QueryEngine.ts:191} + {@code runAgent.ts:377/705/828} +
     * {@code FileEditTool.ts:520} + {@code BashTool.tsx:404}.
     *
     * <p><b>[L+ round 3] WHY 加 {@code content} 字段</b>: 对齐 CC FileState.type
     * ({@code utils/fileStateCache.ts:4-15}) — CC 5 字段全列, Java 原实现漏了 {@code content}.
     * 作用: stale-write 内容比对兜底 (CC {@code FileEditTool.ts:299} + {@code FileWriteTool.ts:291}),
     * mtime 误增但 content 未变 (Windows 杀软 / 云同步) 时仍放行. 走源码: {@code content} 是
     * CRLF 归一化后的形式 ({@code readFileInRange.ts:165-179} 每行 strip 尾随 {@code \r};
     * {@code fileRead.ts:94} {@code raw.replaceAll('\r\n','\n')}), 写入与比对两侧都走
     * 同一归一化, 故比对兜底安全.
     *
     * <p>字段语义 (对齐 CC FileStateCache):
     * <ul>
     *   <li>{@code mtimeMillis} — 文件最后修改时间戳 (ms, {@code Math.floor} 整数),
     *       strict equality 比较</li>
     *   <li>{@code offset} — 1-based 起始行 (Edit/Write 写回时设 {@code null} → dedup 跳过本条,
     *       对齐 CC FileEditTool.ts:523 {@code offset: undefined})</li>
     *   <li>{@code limit} — 最大行数 (Edit/Write 写回时设 {@code null} → dedup 跳过本条,
     *       对齐 CC FileEditTool.ts:524 {@code limit: undefined})</li>
     *   <li>{@code isPartialView} — true 表示<b>内容与磁盘不一致的注入 entry</b>
     *       (memory/CLAUDE.md 自动注入, 模型只看到部分视图, 必须显式 Read 才能 Edit/Write),
     *       对齐 CC {@code attachments.ts:1739-1749} + {@code REPL.tsx:3815}
     *       ({@code isPartialView: memoryFile.contentDiffersFromDisk}) +
     *       {@code utils/fileStateCache.ts:9-14}. <b>Read/窗口/截断路径均 false</b>
     *       (CC Read 从不写该字段, FileReadTool.ts:1032-1037; 窗口读 isPartialView 恒 falsy
     *       可过 Edit/Write 门禁 FileEditTool.ts:276)。dedup 守卫 ({@code !isPartialView}) 与
     *       Edit/Write 门禁 ({@code readTimestamp.isPartialView → 拒绝}) 共用此语义。</li>
     *   <li>{@code content} — <b>CRLF 归一化后</b>的内容 (用于 stale-write 兜底比对).</li>
     * </ul>
    /**
     * <p><b>内存说明</b>: {@code content} 全量驻留, 大文件 / 长 session 会放大 ctx 内存.
     * [P-CC-02] 双限真 LRU: maxEntries=100 + maxSizeBytes=25MB
     * (weigher 只算 content 字节, 对齐 CC {@code fileStateCache.ts:37}).
     * 100 条 + 25MB 双约束防无界增长; 用户 2026-08-05 拍板严格对齐 CC, 撤销旧 500/100MB.
     */
    public record ReadState(
            long mtimeMillis,
            Integer offset,
            Integer limit,
            boolean isPartialView,
            String content
    ) {
        /** 便利构造器: Edit/Write 写回 (无 content, 强制 LLM 重新 Read) — 对齐 CC {@code offset: undefined}. */
        public static ReadState full(long mtimeMillis) {
            return new ReadState(mtimeMillis, null, null, false, null);
        }

        /** 便利构造器: Edit/Write 写回 (带 content 用于 stale-write 兜底) — 对齐 CC {@code FileEditTool.ts:520}. */
        public static ReadState full(long mtimeMillis, String content) {
            return new ReadState(mtimeMillis, null, null, false, content);
        }

        /**
         * 便利构造器: 行窗口读 (显式 offset/limit) — 对齐 CC Read 存
         * {@code {offset, limit}} 的窗口 entry (FileReadTool.ts:1035-1036).
         *
         * <p>[L+ GAP-C] isPartialView=false: 窗口读<b>不是</b> partial view —
         * CC 的 isPartialView 仅 memory 注入场景 (attachments.ts:1749), Read 路径
         * 从不写 (FileReadTool.ts:1032-1037)。旧实现标 true 使窗口读 entry 永远
         * 过不了 Edit/Write 门禁且永不 dedup, 与 CC 相反 (CC 窗口 entry falsy 可过
         * 门禁 FileEditTool.ts:276, 且同 range 二次读可 dedup :549-553)。
         */
        public static ReadState window(long mtimeMillis, int offset, int limit) {
            return new ReadState(mtimeMillis, offset, limit, false, null);
        }

        /** 便利构造器: 行窗口读 (带 content, 用于诊断) — isPartialView=false (同 GAP-C 语义). */
        public static ReadState window(long mtimeMillis, int offset, int limit, String content) {
            return new ReadState(mtimeMillis, offset, limit, false, content);
        }
    }

    /**
     * [L+ round 3] readFileState 的归一化 key 派生 · 对齐 CC
     * {@code utils/fileStateCache.ts:42,46,51,55} {@code LRUCache.get/set/has/delete}
     * 内部统一走 {@code path.normalize(key)}.
     *
     * <p><b>WHY 关键</b>: CC 用 {@code Node.path.normalize} 把 {@code "src\A.java"} /
     * {@code "./src/A.java"} / {@code "src/./A.java"} / 大小写不一致 (Windows) 等归一
     * 到同一 key. Java 不做归一化时, 三工具各用各的原始 input 串作 key, 会出现
     * "Read 用 {@code ./a.txt} 写入 entry, Edit 用 {@code a.txt} 取不到 entry" 的
     * 死循环, 让 LLM 永远拿不到 read-before-write 门禁通过.
     *
     * <p><b>WHY 单点函数</b> (而不是各工具各自实现): 三处 (Read / Edit / Write) 用同一
     * 派生函数避免再次漂移; grep 实测 {@code keyForReadFileState} 应只在 ToolUseContext
     * 定义 + 三处工具引用, 不允许内联 {@code path.normalize(...)}.
     *
     * <p><b>语义</b>: 用 {@link PathGuard#resolve(String)} 拿到归一化绝对路径,
     * 再 {@link Path#toAbsolutePath()} + {@link Path#normalize()} 双重保险, 然后
     * toString() 拿到统一形式的字符串. Windows 大小写不敏感目前由 {@code PathGuard}
     * 解析后 OS-level 处理, 与 CC 行为等价 (Windows NTFS 本身大小写不敏感).
     *
     * <p><b>容错</b>: {@link PathGuard#resolve(String)} 抛 {@code SecurityException}
     * (越狱) 时透传给 caller — caller 应在 validateInput 阶段已捕获, 此处再抛符合
     * "fail loud" 原则.
     *
     * @param guard PathGuard 实例 (注入 workspace 根)
     * @param rawPath 原始路径字符串 (LLM 入参)
     * @return 归一化绝对路径字符串 (用作 readFileState 的 map key)
     */
    public static String keyForReadFileState(PathGuard guard, String rawPath) {
        if (guard == null) {
            throw new IllegalArgumentException("PathGuard is null");
        }
        if (rawPath == null || rawPath.isBlank()) {
            return guard.workdir().toAbsolutePath().normalize().toString();
        }
        Path resolved = guard.resolve(rawPath);
        return resolved.toAbsolutePath().normalize().toString();
    }

    /**
     * 工作目录元数据（对齐 CC {@code AdditionalWorkingDirectory} {@code types/permissions.ts:138}）。
     */
    public record AdditionalWorkingDirectory(
            String source,
            String path
    ) {
        public AdditionalWorkingDirectory {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                    "AdditionalWorkingDirectory.source is required");
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                    "AdditionalWorkingDirectory.path is required");
            }
        }
    }

    /**
     * [Session B B 方案 + Session M1.1 字段扩容] 子 Agent 上下文覆写集合 · 对齐 CC
     * {@code SubagentContextOverrides} (Open-ClaudeCode/src/utils/forkedAgent.ts:260-304).
     *
     * <p><b>WHY（CLAUDE.md 规则一 · 规则八）</b>：
     * CC 用 object literal spread <code>{...parentContext, ...overrides}</code>
     * 实现 parent + overrides 模式. Java record 没有 spread 语法, 用此 record +
     * {@link #with(SubagentContextOverrides)} 方法模拟同构语义:
     * <ul>
     *   <li>每个字段为 nullable — <code>null</code> = "从 parent 继承" (与 CC 的
     *       <code>overrides?.x ?? parentContext.x</code> 等价)</li>
     *   <li>字段清单严格对齐 CC {@code SubagentContextOverrides} 14 字段 (CC 13 字段 +
     *       Java 端 permissionContext extra, 见 concern S7-6)。[S7] options/getAppState/
     *       requireCanUseTool 三字段已补齐 (CC :262/:274/:299) 并 wire 到 {@link #with(SubagentContextOverrides)}</li>
     *   <li>所有 Boolean 字段用 nullable Boolean (不能用 primitive boolean —— null
     *       才是 "use parent" 语义, false 是显式覆写)</li>
     * </ul>
     *
     * <p><b>[Session M1.1] 字段扩容说明</b>:
     * 增加 {@code shareAbortController} 字段 (第 11 字段) + {@code requireCanUseTool}
     * 字段 (第 12 字段), 用于 {@link #with} 内部显式决策 abortController 是 override /
     * share 父 / createChild / NOOP + requireCanUseTool 纯 override — 对齐 CC
     * {@code forkedAgent.ts:350-354 abortController: overrides?.abortController ?? (overrides?.shareAbortController ? parentContext.abortController : createChildAbortController(...))}
     * + {@code forkedAgent.ts:460 requireCanUseTool: overrides?.requireCanUseTool}.
     *
     * <p><b>[Session S7] 字段补齐说明</b>:
     * 补齐 options / getAppState / requireCanUseTool 三字段 (第 12-14 字段), 对齐 CC
     * {@code forkedAgent.ts:260-304 SubagentContextOverrides} 完整 13 字段集。三字段语义:
     * <ul>
     *   <li>{@code options} — CC :262 {@code options?: ToolUseContext['options']}。Java
     *       ToolUseContext 无 options 字段, {@link #with} 内映射 {@code options.tools} →
     *       {@code availableTools} (见 {@link #resolveToolsFromOptions})。</li>
     *   <li>{@code getAppState} — CC :274 {@code getAppState?: ToolUseContext['getAppState']},
     *       with() 内 override ?? parent。</li>
     *   <li>{@code requireCanUseTool} — CC :299 {@code requireCanUseTool?: boolean}, with()
     *       内 override ?? parent (Java primitive boolean 需 null 兜底)。</li>
     * </ul>
     *
     * <p><b>字段语义对齐表 (CC snake_case → Java camelCase)</b>:
     * <table>
     *   <tr><th>字段</th><th>CC 原名</th><th>CC 行号</th></tr>
     *   <tr><td>{@code agentId}</td><td>{@code agentId}</td><td>forkedAgent.ts:264, 448</td></tr>
     *   <tr><td>{@code agentType}</td><td>{@code agentType}</td><td>forkedAgent.ts:266, 449</td></tr>
     *   <tr><td>{@code messages}</td><td>{@code messages}</td><td>forkedAgent.ts:268, 446</td></tr>
     *   <tr><td>{@code abortController}</td><td>{@code abortController}</td><td>forkedAgent.ts:272, 350-354</td></tr>
     *   <tr><td>{@code shareAbortController}</td><td>{@code shareAbortController}</td><td>forkedAgent.ts:342 (新增字段, M1.1)</td></tr>
     *   <tr><td>{@code readFileState}</td><td>{@code readFileState}</td><td>forkedAgent.ts:270, 379-381</td></tr>
     *   <tr><td>{@code permissionContext}</td><td>{@code toolPermissionContext}</td><td>forkedAgent.ts:362-374 (经 getAppState 透传)</td></tr>
     *   <tr><td>{@code shareSetAppState}</td><td>{@code shareSetAppState}</td><td>forkedAgent.ts:281, 410</td></tr>
     *   <tr><td>{@code shareSetResponseLength}</td><td>{@code shareSetResponseLength}</td><td>forkedAgent.ts:287, 426</td></tr>
     *   <tr><td>{@code criticalSystemReminder_EXPERIMENTAL}</td><td>{@code criticalSystemReminder_EXPERIMENTAL}</td><td>forkedAgent.ts:296, 458-459</td></tr>
     *   <tr><td>{@code contentReplacementState}</td><td>{@code contentReplacementState}</td><td>forkedAgent.ts:303, 399-403</td></tr>
     *   <tr><td>{@code options}</td><td>{@code options}</td><td>forkedAgent.ts:262, 445 (Java 映射 options.tools → availableTools)</td></tr>
     *   <tr><td>{@code getAppState}</td><td>{@code getAppState}</td><td>forkedAgent.ts:274, 356-374 (override ?? parent)</td></tr>
     *   <tr><td>{@code requireCanUseTool}</td><td>{@code requireCanUseTool}</td><td>forkedAgent.ts:299, 460 (override ?? parent)</td></tr>
     * </table>
     *
     * <p><b>@JsonIgnore 约束 (CLAUDE.md BudgetTracker local-only)</b>:
     * overrides 字段可能含 callback / cache / session state, 加 {@code @JsonIgnore}
     * 避免 Jackson 序列化时污染 AgentState / EventPublisher / STOMP / LLM payload.
     *
     * @see #with(SubagentContextOverrides)
     * @see createSubagentContext#create(ToolUseContext, SubagentContextOverrides)
     */
    public record SubagentContextOverrides(
            @JsonIgnore UUID agentId,
            @JsonIgnore String agentType,
            @JsonIgnore List<?> messages,
            @JsonIgnore AbortController abortController,
            @JsonIgnore Boolean shareAbortController,
            @JsonIgnore FileStateCache readFileState,
            @JsonIgnore ToolPermissionContext permissionContext,
            @JsonIgnore Boolean shareSetAppState,
            @JsonIgnore Boolean shareSetResponseLength,
            @JsonIgnore String criticalSystemReminder_EXPERIMENTAL,
            @JsonIgnore Map<String, Object> contentReplacementState,
            @JsonIgnore Map<String, Object> options,
            @JsonIgnore Function<Map<String, Object>, Map<String, Object>> getAppState,
            @JsonIgnore Boolean requireCanUseTool
    ) {
        /**
         * [B 返工 R-2] 10/11/12 参向后兼容便利构造器已全部删除（无兼容壳）。
         * 唯一构造入口 = 14 参 canonical（CC forkedAgent.ts:260-304 13 字段 + Java permissionContext）。
         * 迁移记录: RunForkedAgent.java:217（生产 10 参）→ 14 参；测试 12 参 22 处 → 14 参
         * （ToolUseContextWithExplicitFieldDispatchTest 12 处 / CreateSubagentContextOverridesTest 6 处 /
         * ToolUseContextWithResidualsCleanupTest 2 处 / R32B15Stage3_5_QueryTrackingTest 1 处 /
         * SubagentContextFieldPropagationTest 1 处）。
         */
    }

    /**
     * [Session B B 方案 + Session M1.1 显式逐字段重构] parent + overrides 模式 wither 方法 ·
     * 对齐 CC {@code createSubagentContext} (Open-ClaudeCode/src/utils/forkedAgent.ts:345-462)
     * object literal spread 语义.
     *
     * <p><b>[Session M1.1] WHY 显式逐字段 (CLAUDE.md 规则三 · 规则十)</b>:
     * 旧 B 方案实现把所有字段用同一种"override ? override : this"三元塞进构造函数, 18 个
     * 字段无差别, 看似 DRY 实际隐藏了 CC 的差异化语义. 本次 M1.1 重构按 CC 真源 18 字段决策
     * (override / clone / share / new / undefined / noop / fallback chain) 逐一写明:
     * <ul>
     *   <li>每个字段独立构造, 加 JavaDoc + CC 行号标注 (Pattern #2 audit line 漂移防护)</li>
     *   <li>让 CC 的差异化语义 (e.g. agentId 总是新建, 4 个 Set 总是空, agentType 只取 override)
     *       不被 Java 三元掩盖</li>
     *   <li>后续审计 / 修改只需 grep 单字段, 不必整段重读</li>
     * </ul>
     *
     * <p><b>WHY 单一 with 而非 withXxx 方法族 (CLAUDE.md 规则二)</b>:
     * <ul>
     *   <li>CC 模式是 object spread — 单一接收 override 集合最贴近 CC 语义</li>
     *   <li>11 字段用 11 个 withXxx 方法 = 11× 重复样板 + N 个 caller 改 N 次, 违反
     *       "DRY" + "Simplicity First"</li>
     *   <li>单一 with(overrides) = 1 个调用点, caller 一次构造 SubagentContextOverrides
     *       record, 改字段不需改 caller 代码</li>
     * </ul>
     *
     * <p><b>不可变语义 (CLAUDE.md 规则三)</b>:
     * 返回新 record 实例, 原 <code>this</code> 完全不变. Java record 自身 immutable,
     * 加上 compact ctor 防御性 copy + Map/Set 兜底, 共享安全.
     *
     * <p><b>[M1.1] 字段继承规则 (按 CC 真源逐字段决策)</b>:
     * <table>
     *   <tr><th>字段</th><th>CC 决策</th><th>Java 决策</th></tr>
     *   <tr><td>abortController</td><td>override ?? share ? parent : createChild (CC :350-354)</td><td>显式 3 元链</td></tr>
     *   <tr><td>4 个 Set</td><td>总是 new Set&lt;string&gt;() (CC :382-386)</td><td>new ConcurrentHashMap.newKeySet()</td></tr>
     *   <tr><td>toolDecisions</td><td>undefined (CC :387)</td><td>null (Map.of() 占位)</td></tr>
     *   <tr><td>contentReplacementState</td><td>override ?? clone(parent) ?? undefined (CC :399-403)</td><td>显式 3 元链</td></tr>
     *   <tr><td>setAppState</td><td>share ? parent : ()=&gt;{} (CC :410-412)</td><td>显式 2 元</td></tr>
     *   <tr><td>setAppStateForTasks</td><td>parent.setAppStateForTasks ?? parent.setAppState (CC :416-417)</td><td>暂无字段, noop fallback</td></tr>
     *   <tr><td>localDenialTracking</td><td>share ? parent : createDenialTrackingState() (CC :420-422)</td><td>share ? parent : new HashMap&lt;&gt;()</td></tr>
     *   <tr><td>5 个 mutation callbacks</td><td>总是 noop (CC :425-435)</td><td>noop Consumer / undefined</td></tr>
     *   <tr><td>UI callbacks (5 个)</td><td>undefined (CC :438-441)</td><td>null (compact ctor 兜底)</td></tr>
     *   <tr><td>options</td><td>override ?? parent (CC :445)</td><td>override ? tools映射 : this.availableTools (S7, 见 resolveToolsFromOptions)</td></tr>
     *   <tr><td>getAppState</td><td>override ?? parent (CC :274/:358)</td><td>override != null ? override : this.getAppState (S7)</td></tr>
     *   <tr><td>messages</td><td>override ?? parent (CC :446)</td><td>override ? override : this</td></tr>
     *   <tr><td>agentId</td><td>override ?? createAgentId() (CC :448)</td><td>override ? override : packAgentId(createAgentId()) (R-A45)</td></tr>
     *   <tr><td>agentType</td><td>仅 override (CC :449)</td><td>override (无 override 时 = null, 不取 parent)</td></tr>
     *   <tr><td>queryTracking</td><td>{chainId:randomUUID, depth:parent.depth+1} (CC :452-455)</td><td>新建 QueryChainTracking-like Map</td></tr>
     *   <tr><td>userModified</td><td>parent (CC :457)</td><td>this.userModified()</td></tr>
     *   <tr><td>criticalSystemReminder_EXPERIMENTAL</td><td>仅 override (CC :458-459)</td><td>override</td></tr>
     *   <tr><td>requireCanUseTool</td><td>仅 override (CC :460)</td><td>override != null ? override : false (不取父)</td></tr>
     *   <tr><td>readFileState</td><td>clone(override ?? parent) (CC :379-381)</td><td>override ? override : clone(parent)</td></tr>
     * </table>
     *
     * <p><b>[S7] options / getAppState / requireCanUseTool 已补齐</b> (见 {@link SubagentContextOverrides}):
     * <ul>
     *   <li>{@code options} — CC :262 options?: ToolUseContext['options']。Java ToolUseContext
     *       无 options 字段, with() 内将 {@code overrides.options()["tools"]} (List&lt;Tool&gt;) 映射为
     *       {@code availableTools} 覆写 (CC :445 override ?? parent); 其余 options 维度 (model/
     *       thinkingConfig/mcpClients) 由 {@link createSubagentContext#create} 3 参 AgentOptions 通道承载。</li>
     *   <li>{@code getAppState} — CC :274/:358 override ?? parent 链: {@code overrides.getAppState() != null ?
     *       overrides.getAppState() : this.getAppState()}。</li>
     *   <li>{@code requireCanUseTool} — CC :460 仅 override; Java primitive boolean 需
     *       {@code overrides.requireCanUseTool() != null ? ... : this.requireCanUseTool()} 兜底。</li>
     * </ul>
     * setAppStateForTasks 仍无 Java 等价物 (Spring bean 由 DI 注入, 不通过 ctx 透传).
     *
     * @param overrides 覆写字段 (可为 null, null 时返回 <code>this</code>)
     * @return 新 ToolUseContext record 实例
     * @see SubagentContextOverrides
     */

    // ════════════════════════════════════════════════════════════════════════
    // [H7-arch Phase 5-2 P3-⑤] wither（record copy · compact ctor 语义保留）
    //   供 base-TUC 线程化：SubagentExecutor/ExecAgentHook 构造 base TUC（availableTools /
    //   nonInteractiveSession），loop 每轮从 base TUC 派生 per-turn TUC（queryTracking stamp +
    //   messages 快照 + permission context 重建）。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 覆写 availableTools（工具隔离来源 · 对齐 CC toolUseContext.options.tools）。
     * null 参数 → 保留现有 availableTools。
     */
    public ToolUseContext withAvailableTools(List<Tool> tools) {
        return copyWith(null, null, tools, null, null, null);
    }

    /**
     * 覆写 queryTracking（每轮 stamp · 对齐 CC query.ts:346-363）。null 参数 → 保留现有。
     */
    public ToolUseContext withQueryTracking(Map<String, Object> qt) {
        return copyWith(qt, null, null, null, null, null);
    }

    /**
     * [MCP-I-9 Q-30] 覆写 mcpServerConnections（子代理继承 MCP 连接）· 对齐 CC
     * runAgent.ts:685 {@code agentOptions.mcpClients = mergedMcpClients}.
     *
     * <p>WHY: 父 executor Step 15 计算 mergedClients 后写入子 base TUC（Step 18 subagentCtx
     * 重建时调用），嵌套第 2 层经 parentTUC.mcpServerConnections() 取到第 1 层 mergedClients
     * （连接对象传递，非 int 计数）。null 参数 → 保留现有。
     *
     * @param conns 待写入的连接列表（可为 null）
     * @return 新 ToolUseContext（仅 mcpServerConnections 覆写，其余字段透传）
     */
    public ToolUseContext withMcpServerConnections(
            java.util.List<com.nexusai.application.agent.subagent.AgentMcpServers.McpServerConnection> conns) {
        if (conns == null) {
            return this;
        }
        return new ToolUseContext(
            agentId(), sessionId(), mode(), additionalWorkingDirectories(),
            availableTools(), taskListId(), abortController(),
            messages(), permissionContext(), permissionMode(),
            mcpClients(),
            isNonInteractiveSession(), renderedSystemPrompt(), effectiveCwd(),
            inProgressToolUseIDs(), toolDecisions(), onCompactProgress(),
            getAppState(), setAppState(), setStreamMode(), setSDKStatus(),
            addNotification(), appendSystemMessage(), sendOSNotification(),
            setResponseLength(), setHasInterruptibleToolInProgress(), updateFileHistoryState(),
            updateAttributionState(), setConversationId(), setToolJSX(), openMessageSelector(),
            userModified(), nestedMemoryAttachmentTriggers(), loadedNestedMemoryPaths(),
            dynamicSkillDirTriggers(), discoveredSkillNames(), agentType(), requireCanUseTool(),
            preserveToolUseResults(), localDenialTracking(), contentReplacementState(),
            queryTracking(), toolUseId(), criticalSystemReminder_EXPERIMENTAL(),
            readFileState(),
            List.copyOf(conns),
            fileReadingLimits(),    // [OPD-D1-01] 透传 (null 保留 · CC Tool.ts:251 optional)
            effectiveModelName());  // [openai-lazy] 透传 (null 保留)
    }

    /** 覆写 messages 快照。null 参数 → 保留现有。 */
    public ToolUseContext withMessages(List<?> msgs) {
        return copyWith(null, msgs, null, null, null, null);
    }

    /**
     * [IMP-D2] 覆写 userModified · 对齐 CC {@code userModified ?? false} 透传语义
     * （FileEditTool.ts:567 data.userModified = ctx.userModified）。
     *
     * <p>Edit/Write 结构化输出读本字段：权限/前端修改建议后置 true，Edit 输出与
     * mapToolResult 文案（modifiedNote）随之对齐。测试经本 wither 构造 userModified=true
     * 的 ctx。
     */
    public ToolUseContext withUserModified(boolean userModified) {
        return new ToolUseContext(
            agentId(), sessionId(), mode(), additionalWorkingDirectories(),
            availableTools(), taskListId(), abortController(),
            messages(), permissionContext(), permissionMode(),
            mcpClients(),
            isNonInteractiveSession(), renderedSystemPrompt(), effectiveCwd(),
            inProgressToolUseIDs(), toolDecisions(), onCompactProgress(),
            getAppState(), setAppState(), setStreamMode(), setSDKStatus(),
            addNotification(), appendSystemMessage(), sendOSNotification(),
            setResponseLength(), setHasInterruptibleToolInProgress(), updateFileHistoryState(),
            updateAttributionState(), setConversationId(), setToolJSX(), openMessageSelector(),
            userModified, nestedMemoryAttachmentTriggers(), loadedNestedMemoryPaths(),
            dynamicSkillDirTriggers(), discoveredSkillNames(), agentType(), requireCanUseTool(),
            preserveToolUseResults(), localDenialTracking(), contentReplacementState(),
            queryTracking(), toolUseId(), criticalSystemReminder_EXPERIMENTAL(),
            readFileState(),
            mcpServerConnections(),
            fileReadingLimits(),    // [OPD-D1-01] 透传 (null 保留)
            effectiveModelName());  // [openai-lazy] 透传 (null 保留)
    }

    /**
     * [OPD-D1-01] 覆写 fileReadingLimits（Read 输出上限 per-session override）· 对齐 CC
     * {@code Tool.ts:251 fileReadingLimits?: { maxTokens?: number, maxSizeBytes?: number }}。
     *
     * <p>null 参数 → 保留现有（CC 无 override = undefined = 回退默认）。ReadFileTool
     * 消费点：{@code FileReadTool.ts:505-507 maxSizeBytes/maxTokens = fileReadingLimits?.x ??
     * getDefaultFileReadingLimits().x}。
     *
     * @param limits 待写入的 Read 上限覆写（可为 null）
     * @return 新 ToolUseContext（仅 fileReadingLimits 覆写，其余字段透传）
     */
    public ToolUseContext withFileReadingLimits(FileReadingLimits.Override limits) {
        return new ToolUseContext(
            agentId(), sessionId(), mode(), additionalWorkingDirectories(),
            availableTools(), taskListId(), abortController(),
            messages(), permissionContext(), permissionMode(),
            mcpClients(),
            isNonInteractiveSession(), renderedSystemPrompt(), effectiveCwd(),
            inProgressToolUseIDs(), toolDecisions(), onCompactProgress(),
            getAppState(), setAppState(), setStreamMode(), setSDKStatus(),
            addNotification(), appendSystemMessage(), sendOSNotification(),
            setResponseLength(), setHasInterruptibleToolInProgress(), updateFileHistoryState(),
            updateAttributionState(), setConversationId(), setToolJSX(), openMessageSelector(),
            userModified(), nestedMemoryAttachmentTriggers(), loadedNestedMemoryPaths(),
            dynamicSkillDirTriggers(), discoveredSkillNames(), agentType(), requireCanUseTool(),
            preserveToolUseResults(), localDenialTracking(), contentReplacementState(),
            queryTracking(), toolUseId(), criticalSystemReminder_EXPERIMENTAL(),
            readFileState(),
            mcpServerConnections(),
            limits,
            effectiveModelName());  // [openai-lazy] 透传 (null 保留)
    }

    /**
     * [openai-lazy] 覆写 effectiveModelName（当前 turn 有效模型名）· ToolSearchTool 渲染分流：
     * {@code modelSupportsToolReference} 判 Anthropic（纯 tool_reference）vs openai_compatible
     * （追加完整 JSONSchema 文本）。null 参数 → 保留现有。注入点 AgentLoopContext.toolExecContext
     * （从 {@code AgentState.currentModel()} 取值）。
     */
    public ToolUseContext withEffectiveModelName(String modelName) {
        if (modelName == null) {
            return this;
        }
        return new ToolUseContext(
            agentId(), sessionId(), mode(), additionalWorkingDirectories(),
            availableTools(), taskListId(), abortController(),
            messages(), permissionContext(), permissionMode(),
            mcpClients(),
            isNonInteractiveSession(), renderedSystemPrompt(), effectiveCwd(),
            inProgressToolUseIDs(), toolDecisions(), onCompactProgress(),
            getAppState(), setAppState(), setStreamMode(), setSDKStatus(),
            addNotification(), appendSystemMessage(), sendOSNotification(),
            setResponseLength(), setHasInterruptibleToolInProgress(), updateFileHistoryState(),
            updateAttributionState(), setConversationId(), setToolJSX(), openMessageSelector(),
            userModified(), nestedMemoryAttachmentTriggers(), loadedNestedMemoryPaths(),
            dynamicSkillDirTriggers(), discoveredSkillNames(), agentType(), requireCanUseTool(),
            preserveToolUseResults(), localDenialTracking(), contentReplacementState(),
            queryTracking(), toolUseId(), criticalSystemReminder_EXPERIMENTAL(),
            readFileState(),
            mcpServerConnections(),
            fileReadingLimits(),
            modelName);
    }

    /** 覆写 permissionContext + permissionMode（每轮经 ctx.permissionContextBuilder() 重建）。 */
    public ToolUseContext withPermissionContext(ToolPermissionContext permCtx, PermissionMode permMode) {
        return copyWith(null, null, null, null, permCtx, permMode);
    }

    /** 覆写 isNonInteractiveSession（ExecAgentHook base TUC = true · 对齐 CC :133）。 */
    public ToolUseContext withNonInteractiveSession(boolean v) {
        return copyWith(null, null, null, v, null, null);
    }

    /**
     * 通用 record copy · 仅替换非 null override 字段，其余 41 字段原样透传。
     * compact ctor 重新校验 agentId/sessionId/mode 非空（透传必然通过）。
     */
    private ToolUseContext copyWith(Map<String, Object> qt, List<?> msgs, List<Tool> tools,
                                    Boolean nonInteractive, ToolPermissionContext permCtx,
                                    PermissionMode permMode) {
        return new ToolUseContext(
            agentId(), sessionId(), mode(), additionalWorkingDirectories(),
            tools != null ? tools : availableTools(),
            taskListId(), abortController(),
            msgs != null ? msgs : messages(),
            permCtx != null ? permCtx : permissionContext(),
            permMode != null ? permMode : permissionMode(),
            mcpClients(),
            nonInteractive != null ? nonInteractive : isNonInteractiveSession(),
            renderedSystemPrompt(), effectiveCwd(), inProgressToolUseIDs(), toolDecisions(),
            onCompactProgress(), getAppState(), setAppState(), setStreamMode(), setSDKStatus(),
            addNotification(), appendSystemMessage(), sendOSNotification(),
            setResponseLength(), setHasInterruptibleToolInProgress(), updateFileHistoryState(),
            updateAttributionState(), setConversationId(), setToolJSX(), openMessageSelector(),
            userModified(), nestedMemoryAttachmentTriggers(), loadedNestedMemoryPaths(),
            dynamicSkillDirTriggers(), discoveredSkillNames(), agentType(), requireCanUseTool(),
            preserveToolUseResults(), localDenialTracking(), contentReplacementState(),
            qt != null ? qt : queryTracking(), toolUseId(), criticalSystemReminder_EXPERIMENTAL(),
            readFileState(),
            mcpServerConnections(),
            fileReadingLimits(),    // [OPD-D1-01] 透传 (null 保留)
            effectiveModelName());  // [openai-lazy] 透传 (null 保留)
    }

    public ToolUseContext with(SubagentContextOverrides overrides) {
        if (overrides == null) {
            return this;
        }

        // ═══════════════════ 显式逐字段决策 (CC forkedAgent.ts:376-461) ═══════════════════

        // [CC :350-354] abortController: override > share parent's > createChild > NOOP
        AbortController newAbortController;
        if (overrides.abortController() != null) {
            // CC: overrides?.abortController ?? ...
            newAbortController = overrides.abortController();
        } else if (overrides.shareAbortController() != null && overrides.shareAbortController()) {
            // CC: (... ?? overrides?.shareAbortController ? parentContext.abortController : ...)
            newAbortController = this.abortController();
        } else if (this.abortController() != null && this.abortController() != AbortController.NOOP) {
            // CC: (... : createChildAbortController(parentContext.abortController))
            newAbortController = this.abortController().createChild();
        } else {
            // 兜底: 父是 NOOP / null → 子 NOOP (与原 Java 兜底语义一致)
            newAbortController = AbortController.NOOP;
        }

        // [CC :382] nestedMemoryAttachmentTriggers: 总 new Set<string>()
        Set<String> newNestedTriggers = ConcurrentHashMap.newKeySet();
        // [CC :383] loadedNestedMemoryPaths: 总 new Set<string>()
        Set<String> newLoadedPaths = ConcurrentHashMap.newKeySet();
        // [CC :384] dynamicSkillDirTriggers: 总 new Set<string>()
        Set<String> newDynamicSkillTriggers = ConcurrentHashMap.newKeySet();
        // [CC :386] discoveredSkillNames: 总 new Set<string>()
        Set<String> newDiscoveredSkillNames = ConcurrentHashMap.newKeySet();

        // [CC :387] toolDecisions: undefined (override > null)
        Map<String, ToolDecisionInfo> newToolDecisions = null;

        // [CC :399-403] contentReplacementState: override > clone(parent) > new HashMap
        Map<String, Object> newContentReplacementState;
        if (overrides.contentReplacementState() != null) {
            newContentReplacementState = overrides.contentReplacementState();
        } else if (this.contentReplacementState() != null) {
            // CC: (... ?? (parentContext.contentReplacementState ? cloneContentReplacementState(...) : undefined))
            newContentReplacementState = Map.copyOf(this.contentReplacementState());
        } else {
            newContentReplacementState = new HashMap<>();
        }

        // [CC :410-412] setAppState: share ? parent : () => {} (noop)
        Consumer<Function<Map<String, Object>, Map<String, Object>>> newSetAppState;
        if (overrides.shareSetAppState() != null && overrides.shareSetAppState()) {
            newSetAppState = this.setAppState();
        } else {
            newSetAppState = updater -> { /* noop · CC :411 () => {} */ };
        }

        // [CC :420-422] localDenialTracking: share ? parent : createDenialTrackingState()
        Map<String, Object> newLocalDenialTracking;
        if (overrides.shareSetAppState() != null && overrides.shareSetAppState()) {
            // share 模式: 复用父 ctx 的 denial tracker (CC :420-422 shareSetAppState 同源)
            if (this.localDenialTracking() != null) {
                newLocalDenialTracking = Map.copyOf(this.localDenialTracking());
            } else {
                newLocalDenialTracking = new HashMap<>();
            }
        } else {
            // 非 share 模式: 新建独立 denial tracking state
            newLocalDenialTracking = new HashMap<>();
        }

        // [CC :425] setInProgressToolUseIDs: 总是 noop () => {}
        //   [Session M.1.1-R1] CC 真源 forkedAgent.ts:425 实证 setInProgressToolUseIDs: () => {}
        //   — 子 agent ctx 此 callback 必须 noop (子 agent 的 executor 不得调用父的
        //   in-progress 通知函数, CC 隔离语义). noop 形态与 compact ctor 默认一致
        //   (s -> Set.of(), R32B8 断言); 父透传兼容壳已删除.
        Function<Set<String>, Set<String>> newInProgressToolUseIDs = s -> Set.of();
        // [CC :426-428] setResponseLength: share ? parent : () => {}
        //   Java setResponseLength 字段类型是 Consumer<String> (CC 类型推断 set response 长度字符).
        Consumer<String> newSetResponseLength;
        if (overrides.shareSetResponseLength() != null && overrides.shareSetResponseLength()) {
            newSetResponseLength = this.setResponseLength();
        } else {
            newSetResponseLength = s -> { /* noop */ };
        }
        // [CC :432] updateFileHistoryState: 总是 noop
        //   [Session M.1.1-R2] CC 真源 forkedAgent.ts:432 实证 updateFileHistoryState: () => {}
        //   — 子 agent 不能控制父 UI 的 file-history 状态, 必须 noop (LlmAgentLoop:644
        //   主 loop 默认本就是 value -> {}, 与主 loop 语义一致); 父透传兼容壳已删除.
        Consumer<FileHistoryState> newUpdateFileHistoryState = fhs -> { /* noop · CC :432 */ };
        // [CC :434] updateAttributionState: 透传父 (CC 注释 "safe to share even when setAppState is stubbed")
        Consumer<UiAttribution> newUpdateAttributionState = this.updateAttributionState();

        // [CC :438-441] UI callbacks 5 个: 总是 undefined (Java: null → compact ctor 兜底)
        //   - addNotification
        //   - setToolJSX
        //   - setStreamMode
        //   - setSDKStatus
        //   - openMessageSelector
        //   旧 Java 实现是父透传 (this.addNotification() 等). 本次 M1.1 改为显式 null,
        //   对齐 CC "undefined for subagents (can't control parent UI)". compact ctor
        //   兜底 noop Consumer, 行为上等价于"noop 不可触达父 UI", 与 CC undefined 语义同构.

        // [CC :445] options: override ?? parent (CC forkedAgent.ts:445 options: overrides?.options ?? parentContext.options)
        //   [S7] Java ToolUseContext 无 options 字段 (CC ToolUseContext['options'] 含 tools/model/thinkingConfig 等),
        //   此处将 overrides.options()["tools"] (List<Tool>) 映射为 availableTools 覆写 (concern S7-5 option b);
        //   options 为 null 或不含 tools → 继承父 availableTools. 其余 options 维度走 3 参 create() AgentOptions 通道.
        List<Tool> newAvailableTools = resolveToolsFromOptions(overrides.options(), this.availableTools());
        // [CC :446] messages: overrides > parent
        List<?> newMessages = overrides.messages() != null ? overrides.messages() : this.messages();

        // [CC :448] agentId: override ?? createAgentId() (总是新建, 除非 override 提供)
        // [R-A45 A-4 D18/B2] override 缺省 → packAgentId(createAgentId())（CC createAgentId 产物
        //   经 S-12 可逆桥存入 UUID 字段），不再独立 UUID.randomUUID() —— 对齐 CC forkedAgent.ts:448
        //   {@code overrides?.agentId ?? createAgentId()} 语义（子 agent 每次 spawn 新 ID，不继承父）。
        UUID newAgentId = overrides.agentId() != null
            ? overrides.agentId()
            : AgentContext.packAgentId(AgentContext.createAgentId());

        // [CC :449] agentType: 仅 override (不取 parent, override null = 子 ctx.agentType = null)
        String newAgentType = overrides.agentType();

        // [CC :452-455] queryTracking: {chainId: randomUUID, depth: parent.depth + 1}
        Map<String, Object> newQueryTracking = new HashMap<>();
        newQueryTracking.put("chainId", UUID.randomUUID().toString());
        int parentDepth = -1;
        if (this.queryTracking() != null && this.queryTracking().get("depth") instanceof Integer d) {
            parentDepth = d;
        }
        newQueryTracking.put("depth", parentDepth + 1);

        // [CC :457] userModified: parent 透传
        boolean newUserModified = this.userModified();

        // [CC :458-459] criticalSystemReminder_EXPERIMENTAL: 仅 override
        String newCriticalReminder = overrides.criticalSystemReminder_EXPERIMENTAL();

        // [CC :460] requireCanUseTool: 仅 override (纯 override, 不取父值兜底)
        //   [Session M.1.1-R4] CC 真源 forkedAgent.ts:460 实证 requireCanUseTool: overrides?.requireCanUseTool
        //   — override 缺省 = undefined = falsy. 父值兜底在"父 true 子未指定"时产生
        //   false→true 的语义漂移 (父的 requiresUserInteraction 守卫会错误传染给子 agent),
        //   故 override 为 null 时一律 false, 绝不复用 this.requireCanUseTool().
        boolean newRequireCanUseTool =
            overrides.requireCanUseTool() != null ? overrides.requireCanUseTool() : false;

        // [CC :274] getAppState: override ?? parent (S7 补齐字段, forkedAgent.ts:274/358)
        //   CC :358: overrides?.getAppState ? overrides.getAppState : (...shouldAvoidPermissionPrompts wrap)
        Function<Map<String, Object>, Map<String, Object>> newGetAppState =
            overrides.getAppState() != null ? overrides.getAppState() : this.getAppState();
        // [CC :446/379-381] messages + readFileState: messages 已在上面处理; readFileState:
        //   overrides 优先, 否则从父 clone (CC :379-381 cloneFileStateCache)
        //   不可与父共享同一 FileStateCache 引用 (子 Agent 写 entry 不能污染父 cache).
        FileStateCache newReadFileState = overrides.readFileState() != null
            ? overrides.readFileState()
            : cloneFileStateCache(this.readFileState());

        if (log.isDebugEnabled()) {
            log.debug("[ToolUseContext.with] 显式逐字段决策完成: agentId={} (新a+16hex={}), agentType={} (override={}), "
                + "abortController={} (override={}, share={}), 4 Sets 全部重置, "
                + "queryTracking.depth={}, inProgressToolUseIDs=noop(CC :425), "
                + "updateFileHistoryState=noop(CC :432), UI callbacks 5 个=null (CC :438-442), "
                + "requireCanUseTool={} (override={})",
                newAgentId, overrides.agentId() == null ? "是" : "否",
                newAgentType, overrides.agentType() == null ? "否" : "是",
                newAbortController == AbortController.NOOP ? "NOOP" : "active",
                overrides.abortController() == null ? "否" : "是",
                overrides.shareAbortController() == null ? "否" : "是",
                newQueryTracking.get("depth"),
                newRequireCanUseTool,
                overrides.requireCanUseTool() == null ? "否" : "是");
        }

        return new ToolUseContext(
            newAgentId, this.sessionId(), this.mode(),
            this.additionalWorkingDirectories(), newAvailableTools, this.taskListId(),
            newAbortController, newMessages,
            overrides.permissionContext() != null ? overrides.permissionContext() : this.permissionContext(),
            this.permissionMode(), this.mcpClients(), this.isNonInteractiveSession(),
            this.renderedSystemPrompt(), this.effectiveCwd(),
            newInProgressToolUseIDs, newToolDecisions, this.onCompactProgress(),
            newGetAppState, newSetAppState, null,                                 // [CC :440] setStreamMode = undefined (子 agent 不能控制父 UI)
            null,                                                              // [CC :441] setSDKStatus = undefined (子 agent 不能控制父 UI)
            null,                                                              // [CC :438] addNotification = undefined
            this.appendSystemMessage(), this.sendOSNotification(),
            newSetResponseLength, this.setHasInterruptibleToolInProgress(),
            newUpdateFileHistoryState, newUpdateAttributionState,
            this.setConversationId(), null,                                     // [CC :439] setToolJSX = undefined
            null,                                                              // [CC :442] openMessageSelector = undefined
            newUserModified, newNestedTriggers, newLoadedPaths,
            newDynamicSkillTriggers, newDiscoveredSkillNames,
            newAgentType, newRequireCanUseTool, this.preserveToolUseResults(),
            newLocalDenialTracking, newContentReplacementState,
            newQueryTracking, this.toolUseId(), newCriticalReminder,
            newReadFileState,
            this.mcpServerConnections(),   // [MCP-I-9 Q-30] 连接继承 · 继承父 (with 不覆写)
            this.fileReadingLimits(),  // [OPD-D1-01] 继承父 · 对齐 CC forkedAgent.ts:456 fileReadingLimits: parentContext.fileReadingLimits
            this.effectiveModelName()   // [openai-lazy] 继承父 · 子代理共享父 turn 模型名（ToolSearch 分流渲染用）
    );
    }

    /**
     * [S7] 从 CC {@code options} 提取 tools 覆写 availableTools · 对齐 CC forkedAgent.ts:445
     * {@code options: overrides?.options ?? parentContext.options}.
     *
     * <p><b>WHY (concern S7-5)</b>: Java ToolUseContext 无 options 字段 (CC
     * {@code ToolUseContext['options']} 含 tools/model/thinkingConfig/mcpClients 等,
     * Tool.ts:159-179)。Java 端 options 的 tools 维度映射到 {@code availableTools} 覆写
     * (本方法), 其余维度由 {@code createSubagentContext#create} 3 参 AgentOptions 通道承载。
     *
     * <p><b>语义 (对齐 CC override ?? parent)</b>:
     * <ul>
     *   <li>{@code options == null} → 返回 parent (继承)</li>
     *   <li>{@code options["tools"]} 非 {@code List<Tool>} → 返回 parent (未提供有效 tools)</li>
     *   <li>{@code options["tools"]} 是 {@code List<Tool>} → 返回覆写后的 tools</li>
     * </ul>
     *
     * @param options    overrides.options (可为 null, CC forkedAgent.ts:262)
     * @param parentTools 父 availableTools (继承兜底, CC :445 parentContext.options)
     * @return 覆写后的 availableTools
     */
    @SuppressWarnings("unchecked")
    private static List<Tool> resolveToolsFromOptions(Map<String, Object> options, List<Tool> parentTools) {
        if (options == null || options.isEmpty()) {
            return parentTools;
        }
        Object toolsObj = options.get("tools");
        if (!(toolsObj instanceof List<?> toolsList) || toolsList.isEmpty()) {
            return parentTools;
        }
        boolean allTool = toolsList.stream().allMatch(Tool.class::isInstance);
        if (!allTool) {
            return parentTools;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ToolUseContext.resolveToolsFromOptions] options.tools 覆写 availableTools: {} 个工具",
                toolsList.size());
        }
        return (List<Tool>) toolsList;
    }

    // slf4j logger for with() (按 CLAUDE.md "编码后必须添加数据流日志")
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ToolUseContext.class);
}
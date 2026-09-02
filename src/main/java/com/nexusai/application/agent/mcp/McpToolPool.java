package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.skill.McpSkillBuilders;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.skill.SkillFrontmatterFields;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.ImageResizer;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.infra.util.UnicodeSanitizer;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MCP business 层 · 对齐 CC services/mcp/client.ts tools/list + tools/call + assemble_tool_pool.
 *
 * <p>L1 语义: 给定 MCP transport (stdio/SSE/WS/HTTP), 启动 → initialize → tools/list →
 * 把每个 tool 注册到 ToolRegistry (含 mcpInfo 标记). call(name, args) 委派给对应
 * MCP server's tools/call.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: assembleToolPool 返回 List&lt;Tool&gt;, 每个工具含 mcpInfo(serverName+toolName)</li>
 *   <li><b>A2 Golden Trace</b>: connect → initialize → tools/list → register tools → tools/call</li>
 *   <li><b>A3</b>: DISCONNECTED→CONNECTED→READY→DISCONNECTED, 串行调用互斥</li>
 *   <li><b>A4</b>: assemble 必须先 connect+initialize+list, 顺序强约束</li>
 *   <li><b>A5</b>: tools/call 委派结果作为 ToolResult 返回, 错误不抛 (CC services/mcp/client.ts:3092 callTool)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CompletableFuture + record + ToolRegistry 委派, 取代 CC tools.ts:345 assembleToolPool.
 */
@Component
public class McpToolPool {

    private static final Logger log = LoggerFactory.getLogger(McpToolPool.class);

    /** [S3] 伪工具 inputSchema 构造用（空 object schema，对齐 CC z.object({})）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // [tool-v3 合并裁决] tool-v3 的 [IMP-E2 S-7/M-8/S-10] 超时常量 + getMcpToolTimeoutMs/
    // getConnectionTimeoutMs 静态方法（DEFAULT_* 与 master [S02] 同名重复 → 编译冲突）已丢弃，
    // 保留 master [S02] 版本（本文件下方 §[S02] 连接/工具调用超时 区，getConnectionTimeoutMs 719 行
    // / getMcpToolTimeoutMs 738 行 + effective* 793-803 行）。orTimeout 机制（含
    // scheduleToolProgressLog/TOOL_PROGRESS_SCHEDULER）一并丢弃——master 用
    // connectWorker race + toolCallScheduler.applyToEither（CC Promise.race 等价）。

    /** 单个 MCP 工具注册项 (CC MCPTool + mcpInfo). */
    public record McpToolEntry(
        String serverName,
        String toolName,        // 来自 MCP server
        String mcpToolName,     // 实际注册到 registry 的工具名 (含 server 前缀)
        JsonNode inputSchema,
        Tool tool
    ) {}

    private final McpTransportFactory transportFactory;
    private final ToolRegistry toolRegistry;
    private final JsonRpcMcpClient jsonRpcMcpClient;
    /**
     * [impl-I-4 F2] 连接状态 Map 全部 {@link ConcurrentHashMap}（原 LinkedHashMap 非线程安全）：
     * T2 批连接 local/remote 3/20 线程池并发 put 到 activeTransports/serverConfigKeys/serverConfigs/
     * serverTools/serverCapabilities/serverInstructions 会数据竞态（丢条目/CME）。ConcurrentHashMap
     * 保证并发写安全（对齐 CC 无单线程假设，批连接并发为常态）。
     */
    private final Map<String, McpTransport> activeTransports = new ConcurrentHashMap<>();
    /**
     * [impl-I-4 T1] 连接缓存键含 config（对齐 CC {@code getServerCacheKey} client.ts:581-586
     * {@code `${name}-${jsonStringify(serverRef)}`}）：serverName → {@link #getServerCacheKey} 产物。
     * 同 name 换 config（key 变化）→ {@link #ensureConnectedClient} 自动重建连接。
     */
    private final Map<String, String> serverConfigKeys = new ConcurrentHashMap<>();
    /**
     * [impl-I-4 T1] 每个 server 最后一次装配的 config · 供 {@link #ensureConnectedClient(serverName, null)}
     * 惰性重连（CC ensureConnectedClient client.ts:1688-1704 用 client.config 重连）。
     */
    private final Map<String, McpTransport.TransportConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolEntry>> serverTools = new ConcurrentHashMap<>();
    /**
     * P1-17: per-server capabilities · 对齐 CC {@code client.capabilities}
     * （client.ts:2169 {@code supportsResources = !!client.capabilities?.resources}，
     * :2038 {@code !client.capabilities?.prompts}）。
     */
    private final Map<String, JsonRpcMcpClient.Capabilities> serverCapabilities = new ConcurrentHashMap<>();

    /**
     * [RES-L2 · C8] per-server instructions · 对齐 CC {@code ConnectedMCPServer.instructions}
     * (types.ts:189) + {@code client.getInstructions()} (client.ts:1160).
     *
     * <p>MCP initialize 握手响应中 {@code instructions} 字段（可选）, 由 server 提供使用说明.
     * 超长截断到 {@code MAX_MCP_DESCRIPTION_LENGTH=2048} (client.ts:218) + 追加截断提示
     * (client.ts:1163-1166). {@link com.nexusai.application.agent.prompt.SystemPromptSections}
     * 的 {@code mcp_instructions} 动态 section (prompts.ts:579-604) 消费.
     */
    private final Map<String, String> serverInstructions = new ConcurrentHashMap<>();

    /**
     * [RES-L2] 获取指定 MCP server 的 instructions（已截断）.
     *
     * <p>对齐 CC {@code client.getInstructions()} (client.ts:1160) + 截断逻辑
     * (client.ts:1162-1166 {@code MAX_MCP_DESCRIPTION_LENGTH=2048}).
     *
     * @param serverName MCP server 名
     * @return instructions 字符串; 未连接 / 无 instructions 时返回 null
     */
    public String getServerInstructions(String serverName) {
        return serverInstructions.get(serverName);
    }

    /**
     * 取 MCP server base URL · [IMP-E1 DC-2] McpServerInfo 不再承载 serverUrl（CC mcpInfo 仅
     * {serverName,toolName}），server URL 由配置层提供（对齐 CC getLoggingSafeMcpBaseUrl 输入，
     * metadata.ts:102-116）。stdio/sdk → TransportConfig.command 为可执行名 →
     * {@link McpUrlNormalizer#normalizeOfficial} 后非官方（fail-closed）。
     *
     * @param serverName MCP server 名
     * @return server base URL；未装配/无配置 → null
     */
    public String getServerBaseUrl(String serverName) {
        McpTransport.TransportConfig config = serverConfigs.get(serverName);
        return config != null ? config.command() : null;
    }

    /**
     * P2-15: CC MCP_FETCH_CACHE_SIZE（client.ts:1726）— per-server fetch 缓存容量。
     */
    public static final int MCP_FETCH_CACHE_SIZE = 20;

    /**
     * [RES-L2] CC MAX_MCP_DESCRIPTION_LENGTH = 2048 (client.ts:218).
     * instructions 超长截断阈值（对齐 CC client.ts:1162-1166）。
     */
    static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;

    /**
     * P2-15: per-server fetch LRU 缓存 · 对齐 CC {@code memoizeWithLRU(...).cache}
     * （memoize.ts:234-280，key=client.name，max=MCP_FETCH_CACHE_SIZE）。
     * tools/resources/commands/skills 四个 fetch（CC fetchToolsForClient/fetchResourcesForClient/
     * fetchCommandsForClient/fetchMcpSkillsForClient 均 memoizeWithLRU，client.ts:1743/:2000/:2033/
     * mcpSkills.ts）。容量满按 LRU 淘汰最久未用 server。
     */
    private final McpFetchCache<List<McpToolEntry>> toolsCache = new McpFetchCache<>(MCP_FETCH_CACHE_SIZE);
    private final McpFetchCache<List<McpResource>> resourcesCache = new McpFetchCache<>(MCP_FETCH_CACHE_SIZE);
    private final McpFetchCache<List<Command>> commandsCache = new McpFetchCache<>(MCP_FETCH_CACHE_SIZE);
    private final McpFetchCache<List<Command>> skillsCache = new McpFetchCache<>(MCP_FETCH_CACHE_SIZE);

    /**
     * P2-16: resource blob 图片判定集合 · 对齐 CC {@code IMAGE_MIME_TYPES}
     * （client.ts:449-454：image/jpeg / image/png / image/gif / image/webp）。用于
     * {@link #transformResultContent} resource blob 分支判断走 image 块还是落盘。
     */
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp");

    /**
     * [决策 #65] resource 工具恒注册：ListMcpResourcesTool/ReadMcpResourceTool 由 {@code @Component}
     * 恒注册（对齐 CC tools.ts:245-246 getAllBaseTools 恒含），<b>不再</b>经本池条件注册/反注册。
     * 原 {@code resourceToolsAdded} 条件注册（client.ts:2182-2191 / 2360-2364）语义被
     * getAllBaseTools 恒含取代 —— 无 resources 能力部署下两工具仍恒在池（execute fail-soft：
     * ListMcpResourcesTool 空 → EMPTY_RESULT_TEXT，ReadMcpResourceTool 前置 3 门控 → 错误文本）。
     * 移除 ensureResourceToolsRegistered/maybeUnregisterResourceTools/resourceToolsRegistered 标志
     * 三件套（探查 MC-09 原为条件注册，决策 #65 反转）。
     */

    /**
     * P2-15: MCP_SKILLS feature 门控 · 对齐 CC {@code feature('MCP_SKILLS')}
     * （useManageMCPConnections.ts:684/:718）。默认 false 对齐 CC 生产折叠（mcpSkills.ts DCE，
     * concern #23；P1-9，2026-08-16 拍板 Java 默认关），由 McpServerService.start() 注入
     * 与 McpServerService.mcpSkillsGate 同源（P2-9 接线，application.yml 可开）。
     */
    private BooleanSupplier mcpSkillsGate = () -> false;

    /**
     * P2-15: skill 池刷新回调 · CC 原形 {@code updateServer({commands:[...mcpPrompts,...mcpSkills]})
     * + clearSkillIndexCache?.()}（useManageMCPConnections.ts:731-738）——resources/list_changed
     * 刷新 skills 后由 McpServerService 重建 mcpSkillCommands。默认 no-op（纯 McpToolPool
     * 使用场景不接 skill 池）。
     */
    private Consumer<String> skillPoolRefresher = serverName -> {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 未接线 skill 池刷新器 server={}", serverName);
        }
    };

    /**
     * 拍板#2: MCP prompt 命令池刷新回调 · CC 原形 {@code updateServer({commands:
     * [...mcpPrompts, ...mcpSkills]})}（useManageMCPConnections.ts:688-691 prompts/list_changed）——
     * prompts 变化后由 McpServerService 重建 mcpPromptCommands（fetchCommands 产物落库）。
     * 默认 no-op（纯 McpToolPool 使用场景不接 prompt 池）。
     */
    private Consumer<String> promptPoolRefresher = serverName -> {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 未接线 prompt 池刷新器 server={}", serverName);
        }
    };

    /**
     * S04 (B4): LLM 工具池刷新回调 · CC 原形 {@code updateServer({...client, tools: newTools})}
     * （useManageMCPConnections.ts:656 tools/list_changed 处理器末尾）——tools/list_changed
     * 刷新后由 McpServerService 重建 mcpTools（LLM 池唯一源，前缀组替换语义 :255-258）。
     * 默认 no-op（纯 McpToolPool 使用场景不接 LLM 工具池）。
     */
    private Consumer<String> toolsPoolRefresher = serverName -> {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 未接线 LLM 工具池刷新器 server={}", serverName);
        }
    };

    /**
     * P3-5: skill-search 索引清除回调 · CC 原形 {@code clearSkillIndexCache?.()}
     * （useManageMCPConnections.ts:694 prompts/list_changed / :738 resources/list_changed）。
     *
     * <p>CC 语义：list_changed 后 MCP skills 集合可能变化 → 使 skill-search 索引失效，
     * 下次 discovery 用新集合重建。Java 侧无真实 skill-search 索引（concern #30 子系统
     * 范围外）→ 默认 no-op；由 McpServerService.start() 注入委托
     * {@code SkillDiscoveryPrefetch.clearSkillIndexCache()}（镜像 CC
     * {@code feature('EXPERIMENTAL_SKILL_SEARCH') ? require(localSearch).clearSkillIndexCache : undefined}
     * useManageMCPConnections.ts:27-30 的 require-based 间接）。
     */
    private Runnable skillIndexClearer = () -> {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 未接线 skill 索引清除器（skill-search 子系统范围外），list_changed 跳过 clearSkillIndexCache");
        }
    };

    /**
     * MCP skill 资源的 URI scheme 前缀 · 证据等级 E4（推断）。
     *
     * <p>CC 真源：{@code client.ts:2347} 注释「Discover skills from skill:// resources」
     * （grep -rn 'skill://' 全 CC src 仅 :2347 命中）。mcpSkills.ts 不在本 checkout（DCE），
     * 解析规则/资源内容提取为 E4 推断，不得自称 E2。若 mcpSkills.ts 源后续可取，重验升级。
     */
    public static final String SKILL_URI_SCHEME = "skill://";

    public McpToolPool(McpTransportFactory transportFactory, ToolRegistry toolRegistry,
                       JsonRpcMcpClient jsonRpcMcpClient) {
        this.transportFactory = transportFactory;
        this.toolRegistry = toolRegistry;
        this.jsonRpcMcpClient = jsonRpcMcpClient;
    }

    /**
     * P2-15: 注入 MCP_SKILLS 门控供应 · CC 原形 {@code feature('MCP_SKILLS')}
     * （useManageMCPConnections.ts:684/:718）。由 McpServerService.start() 注入（与其自身
     * mcpSkillsGate 同源，P2-9 ToolRegistrationConfig 接线）。null → 恒 false（P1-9，对齐 CC
     * 生产默认关）。
     *
     * @param mcpSkillsGate MCP_SKILLS 开关供应；null 视为恒 false
     */
    public void setMcpSkillsGate(BooleanSupplier mcpSkillsGate) {
        this.mcpSkillsGate = mcpSkillsGate != null ? mcpSkillsGate : () -> false;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 MCP_SKILLS 门控供应 (当前={})", this.mcpSkillsGate.getAsBoolean());
        }
    }

    /**
     * [S3] 注入 MCP OAuth token 持久化服务 · CC hasMcpDiscoveryButNoToken 读存储（auth.ts:349-363）。
     * null → 视为未接线（hasDiscoveryButNoToken 恒 false，仅靠 needs-auth 缓存跳过）。
     */
    public void setOAuthTokenStore(McpOAuthTokenService oauthTokenStore) {
        this.oauthTokenStore = oauthTokenStore;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 OAuth token 持久化服务（{}）", oauthTokenStore != null);
        }
    }

    /**
     * [S3] 注入 OAuth 成功后工具替换回调 · CC McpAuthTool.ts:140-161 setAppState 前缀替换
     * （McpServerService 注入更新 mcpTools；null → 默认 no-op）。
     */
    public void setMcpAuthToolSwapHandler(McpAuthToolSwapHandler handler) {
        this.authToolSwapHandler = handler != null ? handler : this.authToolSwapHandler;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 auth 工具替换回调");
        }
    }

    /**
     * P2-15: 注入 skill 池刷新回调 · CC 原形 {@code updateServer + clearSkillIndexCache}
     * （useManageMCPConnections.ts:731-738）。由 McpServerService.start() 注入
     * {@code this::refreshMcpSkillCommands}（重建 mcpSkillCommands）。
     *
     * @param refresher 按 serverName 重建 skill 命令池的回调；null 视为 no-op
     */
    public void setSkillPoolRefresher(Consumer<String> refresher) {
        this.skillPoolRefresher = refresher != null ? refresher : skillPoolRefresher;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 skill 池刷新回调");
        }
    }

    /**
     * 拍板#2: 注入 MCP prompt 命令池刷新回调 · CC 原形 {@code updateServer({commands:
     * [...mcpPrompts, ...mcpSkills]})}（useManageMCPConnections.ts:688-691 prompts/list_changed）。
     * 由 McpServerService.start()/startEnabledBatch() 注入 {@code this::refreshMcpPromptCommands}
     * （fetchCommands 产物落库 mcpPromptCommands）。
     *
     * @param refresher 按 serverName 重建 prompt 命令池的回调；null 视为 no-op
     */
    public void setPromptPoolRefresher(Consumer<String> refresher) {
        this.promptPoolRefresher = refresher != null ? refresher : promptPoolRefresher;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 prompt 池刷新回调");
        }
    }

    /**
     * S04 (B4): 注入 LLM 工具池刷新回调 · CC 原形 {@code updateServer({...client, tools: newTools})}
     * （useManageMCPConnections.ts:656）。由 McpServerService.start()/startEnabledBatch() 注入
     * {@code this::refreshMcpTools}（按 server 前缀重建 mcpTools）。
     *
     * @param refresher 按 serverName 重建 LLM 工具池的回调；null 视为 no-op
     */
    public void setToolsPoolRefresher(Consumer<String> refresher) {
        this.toolsPoolRefresher = refresher != null ? refresher : toolsPoolRefresher;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 LLM 工具池刷新回调");
        }
    }

    /**
     * P3-5: 注入 skill-search 索引清除回调 · CC 原形 {@code clearSkillIndexCache?.()}
     * （useManageMCPConnections.ts:694/:738）。由 McpServerService.start() 注入
     * {@code SkillDiscoveryPrefetch::clearSkillIndexCache}（POJO 兼容，null → 默认 no-op，
     * 镜像 {@link #setSkillPoolRefresher} null 保留语义）。
     *
     * @param clearer 使 skill-search 索引失效的回调；null 视为 no-op（未接线不抛）
     */
    public void setSkillIndexClearer(Runnable clearer) {
        this.skillIndexClearer = clearer != null ? clearer : skillIndexClearer;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 skill 索引清除器（clearSkillIndexCache 挂钩）");
        }
    }

    /**
     * [impl-I-3 T2] channel 入站通知处理器 · CC 原形 {@code client.setNotificationHandler(ChannelMessageNotificationSchema(), handler)}
     * （useManageMCPConnections.ts:517-530）。register → 注册 notifications/claude/channel handler
     * 包裹 + 入队。
     *
     * <p>[impl-I-3 rework #1] 生产接线：本字段为 {@code @Autowired}（required=true → 装配失败
     * 显式 fail-loud，非静默 skip）。两 bean 均为 @Component（同包），主上下文必然装配成功；
     * 测试经 {@link #setChannelNotification} 覆盖（`new McpToolPool` 不触发 Spring 注入）。
     */
    @Autowired
    private ChannelNotification channelNotification;

    /**
     * [impl-I-3 T2] channel 门控 · CC 原形 {@code gateChannelServer}
     * （channelNotification.ts:191-316）。register 判定由 gate 完成。
     *
     * <p>[impl-I-3 rework #1] 生产接线同 {@link #channelNotification}（@Autowired fail-loud）。
     */
    @Autowired
    private ChannelNotificationGate channelNotificationGate;

    /**
     * [impl-I-3 T2 · R2-3] pluginSource 解析器 · CC 原形 {@code plugin.source}
     * （mcpPluginIntegration.ts:341 addPluginScopeToServers = {@code name@marketplace}，无 plugin: 前缀）。
     * 默认 {@code s -> null}（非插件 server）→ plugin-kind entry 对无来源 server fail-closed
     * （对齐 CC {@code pluginSource ? parsePluginIdentifier(...) : undefined}），不产生安全绕过。
     * 真实来源接线登记后续批次（O3）。
     */
    private Function<String, String> pluginSourceResolver = s -> null;

    /**
     * [impl-I-4 T6] URL elicitation 状态机 · 供 {@link McpServerTool#execute} 捕获 -32042。
     * 默认新实例（responder 未接线 → auto-decline fail-closed）；前端通道接线由后续批次注入。
     */
    private McpElicitationStateMachine elicitationMachine = new McpElicitationStateMachine();

    /**
     * [WF-B] Elicitation hook 处理器 · 注入 {@link McpElicitationStateMachine}（@PostConstruct）。
     * @Autowired(required=false) 保证 hook 系统未装配时也能正常启动。
     */
    @Autowired(required = false)
    private ElicitationHandler elicitationHandler;

    /**
     * [WF-B] 把 ElicitationHandler 接线进 URL elicitation 状态机（hook 预解析 + ElicitationResult
     * override + elicitation_complete 通知）。对齐 CC callMCPToolWithUrlElicitationRetry
     * （client.ts:2924-2940/:3000-3006）。@PostConstruct 在字段注入后执行。
     */
    @PostConstruct
    private void initElicitationHandler() {
        if (elicitationMachine != null && elicitationHandler != null) {
            elicitationMachine.setElicitationHandler(elicitationHandler);
            log.info("[McpToolPool] URL elicitation 状态机已接线 ElicitationHandler（hook 预解析 + 结果 override）");
        }
    }

    /**
     * [impl-I-4 T5] MCP 输出 token 上限 · 由 McpProperties.output().maxMcpOutputTokens 注入
     * （yml {@code nexusai.mcp.output.max-tokens}，默认 25000）。McpServerTool.execute 读取
     * 判定大结果截断/落盘。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.mcp.config.McpProperties mcpProperties;

    /**
     * [S3 · R2-3] needs-auth 缓存 · 对齐 CC isMcpAuthCached/setMcpAuthCacheEntry/clearMcpAuthCache
     * （client.ts:257-316）。批连接缓存跳过路径（client.ts:2307-2322）与连接期 401 降级共用。
     *
     * <p>[R2-3] @Component 单例，内部注入 {@link McpNeedsAuthCacheStore} 共享存储（DB，跨实例
     * 一致）；测试 new McpToolPool 无 Spring → {@link #needsAuth()} 惰性自建纯内存实例
     * （store=null，R1 语义不变）。
     */
    @Autowired(required = false)
    private McpNeedsAuthCache needsAuthCache;

    /**
     * [S4 Q-08] MCP 连接状态注册表 · 对齐 CC AppState.mcp.clients + useMcpConnectivityStatus
     * 消费链。连接成功/失败/认证失败状态变化时更新（@Component 单例，前端查询接口待接线；
     * 测试 new McpToolPool 无 Spring → {@link #connectivity()} 惰性自建实例）。
     */
    @Autowired(required = false)
    private McpConnectivityStatusRegistry connectivityRegistry;

    /**
     * [S3] MCP OAuth token 持久化（DB，Q-01=A keychain→DB）· 供 {@link #hasDiscoveryButNoToken}
     * （CC hasMcpDiscoveryButNoToken auth.ts:349-363）读「已发现但无 token」状态 + 构建
     * {@link McpAuth}（performMCPOAuthFlow 编排）。
     */
    @Autowired(required = false)
    private McpOAuthTokenService oauthTokenStore;

    /** [S3] 惰性构建的 {@link McpAuth}（自建实例，对齐 McpAuthHeaderProvider 模式）。 */
    private volatile McpAuth mcpAuth;

    /** [S3] OAuth 编排专用线程池（低频一次性；可阻塞 5min 等浏览器回调，不占公共线程）。 */
    private volatile ExecutorService authFlowExecutor;

    // ═══════════════ [R2-1] WS 自动重连（对齐 CC onclose→清缓存→惰性重连 + 认证后重连）═══════════════

    /**
     * [R2-1] WS 非认证断开的退避重连延迟（ms）· 对齐 CC client.ts:1227-1228
     * {@code MAX_ERRORS_BEFORE_RECONNECT=3} 的「连续失败后停止」精神，Java 落地为
     * 有界 {1s,2s,4s} 三次尝试（CC 为纯惰性重连，此为本 session 追加的受控增强：
     * 断线后无需等待下次工具调用即自动重建连接；失败回退惰性路径）。
     */
    static final long[] WS_RECONNECT_BACKOFF_MS = {1000L, 2000L, 4000L};

    /**
     * [R2-1] WS 认证重连冷却（ms）· 防 OAuth 成功重连后 server 仍 4003（token 无权限）导致的
     * 无限 OAuth 循环：60s 内同一 server 不重复自动触发 S1 OAuth。
     */
    static final long WS_AUTH_RECONNECT_COOLDOWN_MS = 60_000L;

    /** [R2-1] WS 认证重连触发时间戳（serverName → 上次自动 OAuth 触发时刻）· 冷却守卫。 */
    private final Map<String, Long> wsAuthReconnectAt = new ConcurrentHashMap<>();

    /**
     * [R2-1 rework] WS 退避重连进行中的 server 集合（per-server 重连标志）。
     *
     * <p>语义：{@link #scheduleWsReconnect} 进入退避链时 add；退避链成功重连或真实用户
     * teardown 时 remove。
     *
     * <p>WHY（REFLECTOR R2-1）：退避链自身尝试会 remove activeTransports（Q-11-5 模式，
     * {@code activeTransports.remove}），若不加标志，attempt 1 失败后 attempt 2/3 命中
     * {@code current==null} 守卫空转——{1s,2s,4s} 三次退避退化为单次真实建连。标志存在 →
     * 即使 activeTransports 被本链清空仍继续重试；{@link #teardown}（真实用户 stop）→ remove
     * 标志 → 后续尝试命中守卫跳过（对齐 CC 用户主动 close 不重连，client.ts:1234-1239）。
     */
    private final Set<String> wsReconnecting = ConcurrentHashMap.newKeySet();

    /**
     * [R2-1] WS 退避重连调度器 · 测试可注入同步执行（对齐 {@code getMcpToolsCommandsAndResources}
     * 的 overrideExecutor 模式）；生产默认延迟执行器（{@link CompletableFuture#delayedExecutor}，
     * 公共池 daemon 线程）。
     */
    @FunctionalInterface
    interface WsReconnectScheduler {
        void schedule(long delayMillis, Runnable task);
    }

    private WsReconnectScheduler wsReconnectScheduler = (delay, task) ->
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(task);

    /**
     * [S3] OAuth 认证成功后 server 真实工具替换回调 · 对齐 CC McpAuthTool.ts:140-161
     * setAppState 前缀替换（reject mcp__&lt;server&gt;__* + 追加真实工具）。McpServerService 注入
     * 更新 LLM 工具池（mcpTools）；未接线（测试）→ 仅 ToolRegistry + 缓存更新（no-op 日志）。
     */
    @FunctionalInterface
    public interface McpAuthToolSwapHandler {
        void onServerToolsReplaced(String serverName, List<McpToolEntry> entries);
    }

    private McpAuthToolSwapHandler authToolSwapHandler = (name, entries) -> {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 未接线 auth 工具替换回调 server={}", name);
        }
    };

    /**
     * [impl-I-3 T2] 注入 channel 入站通知处理器 · null → 不注册 channel handler（对齐 CC
     * 无 handler 时入站静默忽略，useManageMCPConnections.ts 注册先例）。
     */
    public void setChannelNotification(ChannelNotification channelNotification) {
        this.channelNotification = channelNotification;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 channel 入站通知处理器（{}）", channelNotification != null);
        }
    }

    /**
     * [impl-I-3 T2] 注入 channel 门控 · null → channel 分支跳过（未接线不抛）。
     */
    public void setChannelNotificationGate(ChannelNotificationGate gate) {
        this.channelNotificationGate = gate;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 channel 门控（{}）", gate != null);
        }
    }

    /**
     * [impl-I-3 T2 · R2-3] 注入 pluginSource 解析器（serverName → pluginSource 字符串）·
     * null → 默认 {@code s -> null}（非插件 server）。
     */
    public void setPluginSourceResolver(Function<String, String> resolver) {
        this.pluginSourceResolver = resolver != null ? resolver : s -> null;
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 pluginSource 解析器");
        }
    }

    /**
     * [impl-I-4 T6] 注入 URL elicitation 状态机（前端/钩子响应通道接线点）。
     * null → 默认新实例（auto-decline fail-closed）。
     *
     * <p>[WF-B] 若 {@link #initElicitationHandler} 已注入 ElicitationHandler，替换机器时
     * 一并重接线（@PostConstruct 先于本 setter 执行，防御外部后注入新实例丢失 hook 接线）。
     */
    public void setElicitationStateMachine(McpElicitationStateMachine machine) {
        this.elicitationMachine = machine != null ? machine : new McpElicitationStateMachine();
        if (this.elicitationHandler != null) {
            this.elicitationMachine.setElicitationHandler(this.elicitationHandler);
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 注入 elicitation 状态机");
        }
    }

    /** [impl-I-4 T6] 获取 elicitation 状态机（McpServerTool.execute 消费）。 */
    McpElicitationStateMachine elicitationMachine() {
        return elicitationMachine;
    }

    /** [S4 Q-08] 获取连接状态注册表（Spring 注入或惰性自建；测试/查询访问）。 */
    McpConnectivityStatusRegistry connectivityStatusRegistry() {
        return connectivity();
    }

    /**
     * [impl-I-4 T5] MCP 输出 token 上限 · 对齐 CC getMaxMcpOutputTokens（mcpValidation.ts:26-47）。
     * 读取 McpProperties.output().maxMcpOutputTokens；未注入 / 无效 → 默认 25000。
     */
    public int maxMcpOutputTokens() {
        if (mcpProperties != null && mcpProperties.output() != null
            && mcpProperties.output().maxMcpOutputTokens() > 0) {
            return mcpProperties.output().maxMcpOutputTokens();
        }
        return McpOutputProcessor.DEFAULT_MAX_MCP_OUTPUT_TOKENS;
    }
    /** CC getMcpServerConnectionBatchSize 默认 3（client.ts:552-554）· local（stdio/sdk）并发上限。 */
    public static final int LOCAL_MCP_SERVER_CONNECTION_BATCH_SIZE = 3;


    /**
     * [S02 D-4] 批连接 local 并发上限 · 对齐 CC getMcpServerConnectionBatchSize
     * （client.ts:552-554：{@code parseInt(process.env.MCP_SERVER_CONNECTION_BATCH_SIZE || '') || 3}）。
     * 默认值下 McpBatchConnectTest 语义不变（env 未设 → 3）。
     */
    public static int getMcpServerConnectionBatchSize() {
        return envInt("MCP_SERVER_CONNECTION_BATCH_SIZE", LOCAL_MCP_SERVER_CONNECTION_BATCH_SIZE);
    }

    /**
     * [S02 D-4] 批连接 remote 并发上限 · 对齐 CC getRemoteMcpServerConnectionBatchSize
     * （client.ts:556-561：{@code parseInt(process.env.MCP_REMOTE_SERVER_CONNECTION_BATCH_SIZE || '') || 20}）。
     */
    public static int getRemoteMcpServerConnectionBatchSize() {
        return envInt("MCP_REMOTE_SERVER_CONNECTION_BATCH_SIZE", REMOTE_MCP_SERVER_CONNECTION_BATCH_SIZE);
    }

    /** [S02 D-4] env 正整数读取（非法/缺失 → 默认）。 */
    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            try {
                int parsed = Integer.parseInt(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值 → 默认
            }
        }
        return def;
    }
    /** CC getRemoteMcpServerConnectionBatchSize 默认 20（client.ts:556-561）· remote 并发上限。 */
    public static final int REMOTE_MCP_SERVER_CONNECTION_BATCH_SIZE = 20;

    // ────────────── [S02] 连接/工具调用超时（对齐 CC env 直译）──────────────

    /**
     * CC DEFAULT_MCP_TOOL_TIMEOUT_MS（client.ts:211）= 100_000_000（≈27.8h，实际无限）。
     */
    private static final long DEFAULT_MCP_TOOL_TIMEOUT_MS = 100_000_000L;

    /** CC getConnectionTimeoutMs 默认 30000（client.ts:456-458，env MCP_TIMEOUT 覆盖）。 */
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L;

    /** [S02] 工具调用进度日志间隔 · 对齐 CC client.ts:3062（每 30s「Tool 'x' still running」）。 */
    private static final long DEFAULT_PROGRESS_LOG_INTERVAL_MS = 30_000L;

    /**
     * [S02] 连接超时（ms）· 对齐 CC getConnectionTimeoutMs（client.ts:456-458：
     * {@code parseInt(process.env.MCP_TIMEOUT || '') || 30000}）。
     */
    static long getConnectionTimeoutMs() {
        String v = System.getenv("MCP_TIMEOUT");
        if (v != null && !v.isBlank()) {
            try {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值 → 默认
            }
        }
        return DEFAULT_CONNECTION_TIMEOUT_MS;
    }

    /**
     * [S02] 工具调用超时（ms）· 对齐 CC getMcpToolTimeoutMs（client.ts:222-229：
     * {@code parseInt(process.env.MCP_TOOL_TIMEOUT || '') || DEFAULT_MCP_TOOL_TIMEOUT_MS}）。
     */
    static long getMcpToolTimeoutMs() {
        String v = System.getenv("MCP_TOOL_TIMEOUT");
        if (v != null && !v.isBlank()) {
            try {
                long parsed = Long.parseLong(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法值 → 默认
            }
        }
        return DEFAULT_MCP_TOOL_TIMEOUT_MS;
    }

    /** [S02] 测试注入：连接超时覆盖（0 = 未注入 → env/默认）。 */
    private volatile long connectTimeoutOverrideMs = 0;

    /** [S02] 测试注入：工具调用超时覆盖（0 = 未注入 → env/默认）。 */
    private volatile long toolTimeoutOverrideMs = 0;

    /** [S02] 测试注入：进度日志间隔覆盖（0 = 未注入 → 30s）。 */
    private volatile long progressLogIntervalOverrideMs = 0;

    /** [S02] 连接握手 worker（daemon 缓存池）· 30s 超时 race 的执行载体。 */
    private final ExecutorService connectWorker =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-connect-worker");
            t.setDaemon(true);
            return t;
        });

    /** [S02] 工具调用超时 + 进度日志调度器（daemon 单线程）。 */
    private final ScheduledExecutorService toolCallScheduler =
        Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "mcp-tool-timeout");
            t.setDaemon(true);
            return t;
        });

    /** [S02] 测试注入：连接超时覆盖（对齐 setWsReconnectScheduler 先例）。 */
    void setConnectTimeoutOverrideMs(long ms) {
        this.connectTimeoutOverrideMs = ms;
    }

    /** [S02] 测试注入：工具调用超时覆盖。 */
    void setToolTimeoutOverrideMs(long ms) {
        this.toolTimeoutOverrideMs = ms;
    }

    /** [S02] 测试注入：工具进度日志间隔覆盖。 */
    void setProgressLogIntervalOverrideMs(long ms) {
        this.progressLogIntervalOverrideMs = ms;
    }

    private long effectiveConnectTimeoutMs() {
        return connectTimeoutOverrideMs > 0 ? connectTimeoutOverrideMs : getConnectionTimeoutMs();
    }

    private long effectiveToolTimeoutMs() {
        return toolTimeoutOverrideMs > 0 ? toolTimeoutOverrideMs : getMcpToolTimeoutMs();
    }

    private long effectiveProgressLogIntervalMs() {
        return progressLogIntervalOverrideMs > 0 ? progressLogIntervalOverrideMs : DEFAULT_PROGRESS_LOG_INTERVAL_MS;
    }


    /** 批连接 server 条目（name + config）。 */
    public record McpServerConfigEntry(String serverName, McpTransport.TransportConfig config) {}

    /**
     * 连接尝试回调 · 对齐 CC {@code onConnectionAttempt}（client.ts:2233-2238）。
     * disabled / 失败 → 空 tools/commands/resources 回调（fail-soft 不抛）。
     */
    @FunctionalInterface
    public interface McpConnectionAttempt {
        void onConnectionAttempt(String serverName, McpTransport.TransportConfig config,
                                 List<McpToolEntry> tools, List<Command> commands,
                                 List<McpResource> resources);
    }

    /**
     * local/remote 分组并发批连接 · 对齐 CC {@code getMcpToolsCommandsAndResources}（client.ts:2226-2403）。
     *
     * <p>语义（自验 CC）：
     * <ul>
     *   <li>分区：local（{@code isLocalMcpServer} = !type || stdio || sdk，:563-565）/ remote（其余）</li>
     *   <li>各自并发上限（local=3 / remote=20，:552-561）——Java 不读 CC env，用常量</li>
     *   <li>{@code processBatched} = pMap slot 释放制（:2218-2224）——Java 用 fixed-thread-pool
     *       （每 server 一任务，完成即释放线程跑下一个）</li>
     *   <li>两组合并 {@code Promise.all}（:2391-2402）——{@link CompletableFuture#allOf}</li>
     *   <li>每 server：connect + fetch tools/commands/resources/skills → onConnectionAttempt；
     *       catch → 空回调 fail-soft（:2388-2396）</li>
     * </ul>
     *
     * @param servers            待连接 server 列表（disabled 由调用方预滤，或回调空 tools）
     * @param onConnectionAttempt 连接尝试回调（消费方注册工具/刷新 skill）
     * @return 全部完成后完成；空输入立即完成
     */
    public CompletableFuture<Void> getMcpToolsCommandsAndResources(
            List<McpServerConfigEntry> servers, McpConnectionAttempt onConnectionAttempt) {
        return getMcpToolsCommandsAndResources(servers, onConnectionAttempt, null);
    }

    /**
     * 批连接（可注入 Executor，测试 single-thread 保序）。
     *
     * @param overrideExecutor 非 null → local/remote 共用此 executor（并发测试保序用）；null → 各自并发上限
     */
    public CompletableFuture<Void> getMcpToolsCommandsAndResources(
            List<McpServerConfigEntry> servers, McpConnectionAttempt onConnectionAttempt,
            Executor overrideExecutor) {
        if (servers == null || servers.isEmpty() || onConnectionAttempt == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<McpServerConfigEntry> local = new ArrayList<>();
        List<McpServerConfigEntry> remote = new ArrayList<>();
        for (McpServerConfigEntry entry : servers) {
            if (isLocalMcpServer(entry.config())) {
                local.add(entry);
            } else {
                remote.add(entry);
            }
        }
        Executor localExecutor = overrideExecutor != null ? overrideExecutor
            : fixedPool("mcp-local-connect", getMcpServerConnectionBatchSize());
        Executor remoteExecutor = overrideExecutor != null ? overrideExecutor
            : fixedPool("mcp-remote-connect", getRemoteMcpServerConnectionBatchSize());
        CompletableFuture<Void> localFuture = processBatched(local, localExecutor, onConnectionAttempt);
        CompletableFuture<Void> remoteFuture = processBatched(remote, remoteExecutor, onConnectionAttempt);
        return CompletableFuture.allOf(localFuture, remoteFuture);
    }

    /** pMap slot 释放制：fixed-thread-pool（每 server 一任务，完成释放线程跑下一个）。 */
    private CompletableFuture<Void> processBatched(List<McpServerConfigEntry> items,
                                                   Executor executor,
                                                   McpConnectionAttempt onConnectionAttempt) {
        if (items.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (McpServerConfigEntry item : items) {
            futures.add(CompletableFuture.runAsync(() -> processBatchServer(item, onConnectionAttempt), executor));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * 单 server 批处理 · 对齐 CC getMcpToolsCommandsAndResources processServer（client.ts:2282-2386）。
     *
     * <p>[S3 needs-auth 接线] 三段（对齐 CC）：
     * <ol>
     *   <li><b>缓存跳过路径</b>（client.ts:2307-2322）：http/sse 且 {@code isMcpAuthCached(name)}
     *       或 {@code hasMcpDiscoveryButNoToken(name, config)} → 不连，直接产出
     *       {@code mcp__<server>__authenticate} 伪工具（client.ts:2318）</li>
     *   <li><b>连接阶段</b>（connect + initialize）：401（{@link McpAuthError}）→ needs-auth，
     *       产出伪工具替换真实工具（client.ts:2331）；其它连接失败 → fail-soft 空回调</li>
     *   <li><b>获取阶段</b>（tools/commands/resources/skills）：失败 → fail-soft 空回调
     *       （client.ts:2388-2396 catch → type='failed'，非 needs-auth）</li>
     * </ol>
     */
    private void processBatchServer(McpServerConfigEntry entry, McpConnectionAttempt onConnectionAttempt) {
        String name = entry.serverName();
        // 缓存跳过路径（CC client.ts:2307-2322）：最近 401 / 已发现无 token → 不连，直接产伪工具
        if (shouldSkipConnectAsNeedsAuth(name, entry.config())) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] 批连接 server={} 跳过连接（needs-auth 缓存/已发现无 token），产出 authenticate 伪工具", name);
            }
            // [S4 Q-08] 连接状态注册表：缓存跳过 = needs-auth（CC client.ts:2318 type='needs-auth'）
            connectivity().updateNeedsAuth(name, entry.config().type());
            onConnectionAttempt.onConnectionAttempt(name, entry.config(),
                buildAuthToolEntries(name, entry.config()), List.of(), List.of());
            return;
        }
        // 连接阶段（connect + initialize）——401 → needs-auth 伪工具；其它失败 → fail-soft 空回调
        try {
            ensureConnectedClient(name, entry.config());
            // [S4 Q-08] 连接成功 → 注册表 connected（对齐 CC client.type='connected'）
            connectivity().updateConnected(name, entry.config().type());
        } catch (Exception e) {
            Throwable cause = unwrapCompletion(e);
            if (cause instanceof McpAuthError && isOAuthCapableTransport(entry.config())) {
                // CC connectToServer 抛 UnauthorizedError → handleRemoteAuthFailure → type='needs-auth'
                // （client.ts:1105-1107/:1121-1123）；Java 等价：连接期 401 → McpAuthError。
                needsAuth().setCached(name);
                // [S4 Q-08] 连接状态注册表：401 → needs-auth（对齐 CC handleRemoteAuthFailure :340-361）
                connectivity().updateNeedsAuth(name, entry.config().type());
                log.info("[McpToolPool] 批连接 server={} 需认证（401）→ 产出 authenticate 伪工具替换真实工具: {}",
                    name, cause.getMessage());
                onConnectionAttempt.onConnectionAttempt(name, entry.config(),
                    buildAuthToolEntries(name, entry.config()), List.of(), List.of());
            } else {
                log.warn("[McpToolPool] 批连接 server={} 连接失败（fail-soft 空回调）: {}",
                    name, cause != null ? cause.getMessage() : e.getMessage());
                // [S4 Q-08] 连接状态注册表：非认证失败 → failed（对齐 CC catch → type='failed' :2388-2396）
                connectivity().updateFailed(name, entry.config().type(),
                    cause != null ? cause.getMessage() : e.getMessage());
                onConnectionAttempt.onConnectionAttempt(name, entry.config(), List.of(), List.of(), List.of());
            }
            return;
        }
        // 获取阶段（tools/commands/resources/skills）——失败 fail-soft 空回调（CC :2388-2396）
        try {
            List<McpToolEntry> tools = fetchTools(name);
            // [impl-I-4 F1 rework] 批路径补齐 serverTools + toolRegistry 注册（对齐 assembleToolPool
            // :704/:709）。否则默认 prefetch-on-startup:true 预取走批路径后 activeServers() 为空，
            // T7 ReadMcpResourceTool + ListMcpResourcesTool/SubagentTool 对预取 server 报「Server not found」
            // （T3 与 T7 本批内互斥）。fetchTools 只做协议往返 + toolsCache，不注册——此处补注册。
            registerServerTools(name, tools);
            List<Command> commands = new ArrayList<>(fetchCommands(name));
            if (mcpSkillsGate.getAsBoolean()) {
                // CC :2348 feature('MCP_SKILLS') && supportsResources ? fetchMcpSkillsForClient : []
                commands.addAll(fetchMcpSkills(name));
            }
            List<McpResource> resources = fetchResources(name);
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] 批连接 server={} tools={} commands={} resources={}",
                    name, tools.size(), commands.size(), resources.size());
            }
            onConnectionAttempt.onConnectionAttempt(name, entry.config(), tools, commands, resources);
        } catch (Exception e) {
            log.warn("[McpToolPool] 批连接 server={} 获取资源失败（fail-soft 空回调）: {}", name, e.getMessage());
            // [S4 Q-08] 获取阶段失败 → failed（覆盖 connected，对齐 CC :2372-2385 catch → type='failed'）
            connectivity().updateFailed(name, entry.config().type(), e.getMessage());
            onConnectionAttempt.onConnectionAttempt(name, entry.config(), List.of(), List.of(), List.of());
        }
    }

    // ═══════════════ [S3 needs-auth 伪工具接线（Q-02/Q-21）] ═══════════════

    /**
     * CC client.ts:2307-2314 缓存跳过判定：http/sse 且（isMcpAuthCached(name) 或
     * hasMcpDiscoveryButNoToken(name, config)）。claudeai-proxy 不在范围（Q-26 TODO 未实现）。
     */
    private boolean shouldSkipConnectAsNeedsAuth(String name, McpTransport.TransportConfig config) {
        if (!isOAuthCapableTransport(config)) {
            return false;
        }
        if (needsAuth().isCached(name)) {
            return true;
        }
        return hasDiscoveryButNoToken(name, config);
    }

    /** CC hasMcpDiscoveryButNoToken（auth.ts:349-363）：存储存在该 serverKey 但无 access/refresh token。 */
    private boolean hasDiscoveryButNoToken(String name, McpTransport.TransportConfig config) {
        if (oauthTokenStore == null || config == null || config.command() == null) {
            return false;
        }
        // env 可能含 __mcp_oauth__ 镜像保留键 → 按 headers 语义剥除（否则 serverKey 与
        // saveClientSecret / McpAuthHeaderProvider 不同键，token 存储查找错行）
        Map<String, String> headers = McpOAuth.headersOnly(config.env());
        String serverKey = McpOAuth.getServerKey(name, config.type(), config.command(), headers);
        McpOAuthToken stored = oauthTokenStore.read(serverKey);
        if (stored == null) {
            return false;
        }
        boolean noAccess = stored.getAccessToken() == null || stored.getAccessToken().isBlank();
        boolean noRefresh = stored.getRefreshToken() == null || stored.getRefreshToken().isBlank();
        return noAccess && noRefresh;
    }

    /** 仅 sse/http 支持 OAuth from pseudo-tool（对齐 CC McpAuthTool.ts:101-108）。 */
    private static boolean isOAuthCapableTransport(McpTransport.TransportConfig config) {
        String type = config == null ? null : config.type();
        return "http".equals(type) || "sse".equals(type);
    }

    /**
     * [S03 R2-03 △-7] <b>调用期 401 收敛点</b> · 对齐 CC 工具执行层 {@code McpAuthError}
     * catch → appState {@code mcp.clients} 降级 {@code needs-auth}（toolExecution.ts:1601-1629）
     * + 连接期统一收敛路径（client.ts:340-361 handleRemoteAuthFailure → setMcpAuthCacheEntry
     * + 伪工具替换 client.ts:2318）。
     *
     * <p>由 {@link McpServerTool#execute} 捕获 transport 401（{@link McpAuthError}）后调用，
     * 与连接期 401（{@link #processBatchServer} / {@link #assembleSingleServer}）共用三件套：
     * <ol>
     *   <li><b>needs-auth 缓存标记</b> — {@link McpNeedsAuthCache#setCached}（15min TTL，
     *       对齐 CC setMcpAuthCacheEntry client.ts:293-309）</li>
     *   <li><b>连接状态注册表</b> — {@code updateNeedsAuth}（CC appState type='needs-auth'
     *       等价物，toolExecution.ts:1616-1620）</li>
     *   <li><b>伪工具替换</b> — sse/http OAuth-capable transport → {@code mcp__&lt;server&gt;__
     *       authenticate} 伪工具经 {@link #authToolSwapHandler} 替换真实工具（CC 调用期 401
     *       后模型可见 authenticate 工具触发重授权；与 {@link #reconnectServerForAuth} 认证
     *       成功后的工具替换为同一 handler 通道）</li>
     * </ol>
     *
     * <p>config 缺失（server 未装配/已 teardown）→ 仅缓存标记 + 注册表降级，不产伪工具
     * （无 config 无法构造 OAuth 流；CC 找不到 client 时 prev 原样返回）。
     *
     * @param serverName 触发 401 的 MCP server 名（{@link McpAuthError#serverName()}）
     */
    public void markServerNeedsAuth(String serverName) {
        if (serverName == null) {
            return;
        }
        McpTransport.TransportConfig config = serverConfigs.get(serverName);
        // ① needs-auth 缓存标记（15min TTL · 对齐 CC setMcpAuthCacheEntry client.ts:293-309）
        needsAuth().setCached(serverName);
        // ② 连接状态注册表 needs-auth（对齐 CC toolExecution.ts:1601-1629 appState 降级）
        connectivity().updateNeedsAuth(serverName, config != null ? config.type() : null);
        // ③ OAuth-capable transport → 伪工具替换（CC client.ts:2318 收敛；与连接期同一 handler）
        if (isOAuthCapableTransport(config)) {
            List<McpToolEntry> entries = buildAuthToolEntries(serverName, config);
            authToolSwapHandler.onServerToolsReplaced(serverName, entries);
            log.info("[McpToolPool] 调用期 401 → needs-auth 标记 + 伪工具替换 server={}（对齐 CC toolExecution.ts:1601-1629）",
                serverName);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 调用期 401 → needs-auth 标记（非 OAuth-capable/无 config，不产伪工具）server={}",
                serverName);
        }
    }

    /** 解开 CompletionException（CompletableFuture.join 包装），取原始 cause。 */
    private static Throwable unwrapCompletion(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException ce && ce.getCause() != null) {
            cur = ce.getCause();
        }
        return cur;
    }

    /**
     * 构建 needs-auth 伪工具注册项（CC createMcpAuthTool 等价，McpAuthTool.ts:49-215）。
     *
     * <p>伪工具经 {@link McpConnectionAttempt} 进入 {@code McpServerService.mcpTools}（LLM 池），
     * 每轮 turn {@code toolRegistry.assembleToolPool(getCurrentTools())} 再登记进 ToolRegistry 供
     * dispatch → 模型可见 {@code mcp__<server>__authenticate} 并触发 {@link McpAuth#performMCPOAuthFlow}。
     */
    private List<McpToolEntry> buildAuthToolEntries(String serverName, McpTransport.TransportConfig config) {
        McpAuthTool tool = buildMcpAuthTool(serverName, config);
        ObjectNode emptySchema = MAPPER.createObjectNode();
        McpToolEntry entry = new McpToolEntry(serverName, "authenticate",
            McpStringUtils.buildMcpToolName(serverName, "authenticate"), emptySchema, tool);
        return List.of(entry);
    }

    /**
     * 构建接线完成的 {@link McpAuthTool}（对齐 CC createMcpAuthTool 返回的 Tool）：
     * <ul>
     *   <li><b>OAuthFlowStarter</b> — 异步 {@link McpAuth#performMCPOAuthFlow}（skipBrowserOpen=true），
     *       onUrl 透传 authUrl，成功后 onComplete（触发后台 reconnect+swap；失败不触发）</li>
     *   <li><b>ReconnectRunner</b> — {@link #reconnectServerForAuth}（清缓存 + teardown + 重连 +
     *       真实工具替换回调）</li>
     *   <li><b>StateUpdater</b> — passthrough：生产 swap 已由 ReconnectRunner 落地到真实池
     *       （ToolRegistry + mcpTools），McpState 抽象仅服务测试（受控偏差登记 S3 报告）</li>
     * </ul>
     */
    private McpAuthTool buildMcpAuthTool(String serverName, McpTransport.TransportConfig config) {
        McpAuthTool.McpServerConfig mc = new McpAuthTool.McpServerConfig(
            config.type(), config.command(), null);
        McpAuth mcpAuthInstance = mcpAuth();
        McpAuthTool.OAuthFlowStarter starter = (s, c, onUrl, onComplete, opts) -> {
            // 低频一次性编排（可阻塞等浏览器回调）→ 专用 cached pool，避免占公共线程
            authFlowExecutor().execute(() -> {
                try {
                    McpAuth.OAuthServerConfig oauthCfg = new McpAuth.OAuthServerConfig(
                        c.type(), c.url(), Map.of(), null, null, null);
                    McpAuth.AuthResult result = mcpAuthInstance.performMCPOAuthFlow(
                        s, oauthCfg, onUrl, opts.skipBrowserOpen());
                    if (result.success()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[McpToolPool] McpAuthTool OAuth flow 成功 server={}，触发后台 reconnect", s);
                        }
                        // Consumer<String> onComplete：值仅作完成信号（McpAuthTool 内按前缀分流），
                        // 成功传 "success"（触发 reconnect），失败传 "error:..."（不触发 reconnect）
                        onComplete.accept("success");
                    } else {
                        // [IMP-E G2] 失败也触发 onComplete（error 信号）——否则 McpAuthTool 的
                        //   anyOf(authUrlFuture, completionFuture).get() 永久挂起（success=false 无信号）。
                        //   CC McpAuthTool.ts:167-172 oauthPromise reject → race reject → error status。
                        String reason = result.errorMessage() != null && !result.errorMessage().isBlank()
                            ? result.errorMessage() : String.valueOf(result.errorReason());
                        log.warn("[McpToolPool] McpAuthTool OAuth flow 未成功 server={} reason={}（触发 error 信号，不触发 reconnect）",
                            s, result.errorReason());
                        onComplete.accept("error:" + reason);
                    }
                } catch (Exception e) {
                    log.warn("[McpToolPool] McpAuthTool OAuth flow 异常 server={}: {}", s, e.getMessage());
                    onComplete.accept("error:" + e.getMessage());
                }
            });
        };
        McpAuthTool.ReconnectRunner reconnect = (s, c) -> {
            try {
                // McpAuthTool.McpServerConfig(type/url) → McpTransport.TransportConfig（command=url 承载，remote 语义）
                McpTransport.TransportConfig tc = new McpTransport.TransportConfig(
                    c.url(), List.of(), Map.of(), null, s, c.type());
                List<McpToolEntry> entries = reconnectServerForAuth(s, tc);
                List<String> names = entries.stream().map(McpToolEntry::mcpToolName).toList();
                return new McpAuthTool.ReconnectResult(
                    new McpAuthTool.McpState.Client(s), names, List.of(), null);
            } catch (Exception e) {
                log.warn("[McpToolPool] McpAuthTool OAuth 后重连失败 server={}: {}", s, e.getMessage());
                return new McpAuthTool.ReconnectResult(
                    new McpAuthTool.McpState.Client(s), List.of(), List.of(), null);
            }
        };
        // StateUpdater no-op：生产 swap 已由 ReconnectRunner.reconnectServerForAuth 落地到真实池
        // （ToolRegistry + serverTools + mcpTools），McpState 为测试用抽象，真实池无对应状态可应用
        // （受控偏差，S3 报告登记；CC setAppState 前缀替换等价物在 reconnectServerForAuth 内）。
        McpAuthTool.StateUpdater updater = ignored -> { };
        McpAuthTool.DebugLogger debug = (s, m) -> {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] McpAuthTool {} {}", s, m);
            }
        };
        McpAuthTool.ErrorLogger error = (s, m) -> log.warn("[McpToolPool] McpAuthTool {} {}", s, m);
        return new McpAuthTool(serverName, mc, starter, reconnect, updater, debug, error);
    }

    /**
     * OAuth 认证成功后重建 server · 对齐 CC McpAuthTool.ts:138-161 后台 continuation
     * （clearMcpAuthCache → reconnectMcpServerImpl → setAppState 前缀替换）。
     *
     * <p>落地：清 needs-auth 缓存 → teardown 旧连接（注销旧工具）→ 重连（assembleToolPool 内
     * registerServerTools 注册 ToolRegistry + serverTools）→ authToolSwapHandler 通知
     * McpServerService 更新 LLM 池（mcpTools 前缀替换伪工具）。
     *
     * @return 重连后的真实工具注册项
     */
    private List<McpToolEntry> reconnectServerForAuth(String serverName, McpTransport.TransportConfig config) {
        needsAuth().clear();
        teardown(serverName);
        List<McpToolEntry> entries = assembleToolPool(serverName, config);
        // [S4 Q-08] OAuth 认证成功 → 注册表 connected（清除 needs-auth 态，对齐 CC 认证后恢复连接）
        connectivity().updateConnected(serverName, config.type());
        authToolSwapHandler.onServerToolsReplaced(serverName, entries);
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] OAuth 认证成功，已重连 server={} 工具数={}", serverName, entries.size());
        }
        return entries;
    }

    // ═══════════════ [R2-1] WS 自动重连处理器（对齐 CC client.ts:1374-1402 onclose / 1688-1704 ensureConnectedClient）═══════════════

    /**
     * [R2-1] WS 断开处理器 · 对齐 CC {@code client.onclose}（client.ts:1374-1402）：
     * <ol>
     *   <li><b>清 per-server fetch 缓存</b>（CC :1389-1396
     *       {@code fetchToolsForClient.cache.delete(name) + fetchResources/Commands/Skills.cache.delete(name)}
     *       —— 重连创建新连接对象，不清则下次 fetch 返回旧连接陈旧结果）</li>
     *   <li><b>authRequired</b>（close 4003 认证失败且 token 刷新无法恢复）→ needs-auth 状态可见
     *       （对齐 CC handleRemoteAuthFailure type='needs-auth' client.ts:340-361）+ 异步 S1 OAuth
     *       → 成功后自动重连建立已认证会话（对齐 CC ensureConnectedClient 惰性重建的认证前置）</li>
     *   <li><b>非认证断开</b> → 有界退避主动重连（CC 惰性之上追加，防长时静默断连；失败回退惰性路径）</li>
     * </ol>
     *
     * <p>WHY（规则九）：WS 断线不重连则 server 工具静默不可用（callTool 失败），用户需手动
     * /mcp 或重启；本方法落地 session 目标「ws 断线/认证完成后自动重连，无需手动」。
     *
     * @param serverName  MCP server 名
     * @param config      transport 配置
     * @param authRequired 4003 认证关闭且刷新失败（→ needs-auth + OAuth 重连）
     */
    private void handleWsDisconnect(String serverName, McpTransport.TransportConfig config, boolean authRequired) {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] WS 断开 server={} authRequired={}", serverName, authRequired);
        }
        // CC onclose :1389-1396 清 fetch 缓存（重连不读旧快照）
        invalidateFetchCaches(serverName);
        if (authRequired) {
            // 4003 认证失败且刷新无法恢复 → needs-auth（对齐 CC handleRemoteAuthFailure → type='needs-auth'）
            connectivity().updateNeedsAuth(serverName, config.type());
            triggerWsAuthReconnect(serverName, config);
        } else {
            scheduleWsReconnect(serverName);
        }
    }

    /**
     * [R2-1] WS 认证重连 · 对齐 SseMcpTransport.handleConnect401 的「①refresh → ②S1 OAuth → ③重连」
     * 语义（WS 的 close 4003 为异步等价物）：S1 OAuth 流（performMCPOAuthFlow）成功后自动重连
     * 建立已认证会话。
     *
     * <p>冷却守卫：OAuth 成功重连后 server 仍 4003（token 无权限）→ {@code WS_AUTH_RECONNECT_COOLDOWN_MS}
     * 内不重复自动触发，防无限 OAuth 循环；OAuth 失败 → 保持 needs-auth（用户后续可经伪工具/
     * 前端重试）。
     *
     * @param serverName MCP server 名
     * @param config     transport 配置（type/command/env 供 serverKey/OAuthServerConfig）
     */
    private void triggerWsAuthReconnect(String serverName, McpTransport.TransportConfig config) {
        if (oauthTokenStore == null) {
            log.warn("[McpToolPool] WS 认证失败但 OAuth token 存储未接线，保持 needs-auth server={}", serverName);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = wsAuthReconnectAt.get(serverName);
        if (last != null && now - last < WS_AUTH_RECONNECT_COOLDOWN_MS) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] WS 认证重连冷却中，跳过自动 OAuth server={}", serverName);
            }
            return;
        }
        wsAuthReconnectAt.put(serverName, now);
        authFlowExecutor().execute(() -> {
            try {
                McpAuth.OAuthServerConfig oauthCfg = new McpAuth.OAuthServerConfig(
                    config.type(), config.command(),
                    // 剥除 __mcp_oauth__ 镜像保留键（headers 语义视图，防止保留键当 HTTP 头发送）
                    McpOAuth.headersOnly(config.env()),
                    null, null, null);
                McpAuth.AuthResult result = mcpAuth().performMCPOAuthFlow(serverName, oauthCfg,
                    authUrl -> log.info("[McpToolPool] WS OAuth 授权 URL server={} authUrl={}", serverName, authUrl),
                    true);
                if (result.success()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] WS OAuth 成功 server={}，自动重连建立已认证会话", serverName);
                    }
                    // 复用 McpAuthTool 认证后重连路径：清缓存 + teardown + assemble + connected + 工具替换
                    reconnectServerForAuth(serverName, config);
                } else {
                    log.warn("[McpToolPool] WS OAuth 未成功 server={} reason={}（保持 needs-auth，等待用户重试）",
                        serverName, result.errorReason());
                }
            } catch (Exception e) {
                log.warn("[McpToolPool] WS OAuth 流程异常 server={}: {}", serverName, e.getMessage());
            }
        });
    }

    /**
     * [R2-1] WS 有界退避主动重连 · CC 惰性重连（onclose 清缓存 → 下次 ensureConnectedClient 重建，
     * client.ts:1688-1704）之上追加的受控增强：断线后自动按 {1s,2s,4s} 重试建立连接，避免长时
     * 静默断连（目标「断线自动重连，无需手动」）；失败后回退惰性路径（下次工具调用重建）。
     *
     * <p>每次尝试前守卫：
     * <ul>
     *   <li>transport 已不在 activeTransports <b>且不在重连链</b>（真实用户 stop / teardown，
     *       teardown 已 remove {@code wsReconnecting} 标志）→ 跳过。本链自身尝试的 remove
     *       （Q-11-5 模式）不清标志 → 后续 2s/4s 尝试仍真正重试建连（REFLECTOR R2-1）</li>
     *   <li>transport 已 CONNECTED（惰性路径先重连成功）→ 跳过</li>
     * </ul>
     * 重连采用 Q-11-5 会话过期模式（activeTransports.remove + invalidateFetchCaches，
     * <b>保留已注册工具</b>）：失败不清 serverTools/ToolRegistry（对齐 CC clearServerCache
     * 保留 server.tools）。
     *
     * @param serverName MCP server 名
     */
    private void scheduleWsReconnect(String serverName) {
        McpTransport.TransportConfig config = serverConfigs.get(serverName);
        if (config == null) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] WS 断开但无保存 config，跳过主动重连 server={}", serverName);
            }
            return;
        }
        // [R2-1 rework] 进入退避链：add per-server 重连标志。WHY（REFLECTOR R2-1）：退避链自身
        // 尝试会 remove activeTransports（Q-11-5 模式），attempt 1 失败后若不加标志，attempt 2/3
        // 命中 current==null 守卫空转——{1s,2s,4s} 三次退避退化为单次真实建连；标志存在使
        // current==null 守卫仅对真实用户 stop/teardown（teardown 已 remove 标志）生效。
        wsReconnecting.add(serverName);
        for (int i = 0; i < WS_RECONNECT_BACKOFF_MS.length; i++) {
            long delay = WS_RECONNECT_BACKOFF_MS[i];
            int attempt = i + 1;
            wsReconnectScheduler.schedule(delay, () -> {
                try {
                    McpTransport current = activeTransports.get(serverName);
                    if (current == null) {
                        // 真实用户 stop/teardown（teardown 已 remove 重连标志）→ 跳过；
                        // 本链上次尝试已 remove（Q-11-5 模式）且仍处重连链 → 继续重试建连
                        if (!wsReconnecting.contains(serverName)) {
                            if (log.isDebugEnabled()) {
                                log.debug("[McpToolPool] WS 退避重连跳过（transport 已移除/teardown）server={}", serverName);
                            }
                            return;
                        }
                    } else if (current.getState() == McpTransport.State.CONNECTED) {
                        // 惰性路径（下次工具调用）或本链已重连成功 → 跳过；链已满足，收尾标志
                        wsReconnecting.remove(serverName);
                        if (log.isDebugEnabled()) {
                            log.debug("[McpToolPool] WS 退避重连跳过（已连接）server={}", serverName);
                        }
                        return;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] WS 退避重连尝试 {}/{} server={}",
                            attempt, WS_RECONNECT_BACKOFF_MS.length, serverName);
                    }
                    // Q-11-5 模式：移除旧 CLOSED transport + 清 fetch 缓存（保留已注册工具）
                    activeTransports.remove(serverName);
                    invalidateFetchCaches(serverName);
                    ensureConnectedClient(serverName, config);
                    // 重连成功 → 注册表 connected（对齐 CC 连接恢复 type='connected'）+ 链结束
                    connectivity().updateConnected(serverName, config.type());
                    wsReconnecting.remove(serverName);
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] WS 退避重连成功 server={}", serverName);
                    }
                } catch (Exception e) {
                    log.warn("[McpToolPool] WS 退避重连失败 server={} 尝试#{}: {}", serverName, attempt, e.getMessage());
                    // 失败：保持 wsReconnecting 标志 → 后续 attempt 2/3 仍重试建连（rework 核心）；
                    // 末次尝试后链自然结束（下次 onClose 新链重新 add，或真实 teardown 清理标志）
                }
            });
        }
    }

    /** [R2-1] 测试注入 WS 退避重连调度器（null → 默认延迟执行器）。 */
    void setWsReconnectScheduler(WsReconnectScheduler scheduler) {
        this.wsReconnectScheduler = scheduler != null ? scheduler : (delay, task) ->
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(task);
    }

    /** [S3] 惰性构建 {@link McpAuth}（DB tokenStore + 真实 OAuth HTTP + loopback 回调 + 端口分配）。 */
    private McpAuth mcpAuth() {
        McpAuth local = mcpAuth;
        if (local == null) {
            synchronized (this) {
                local = mcpAuth;
                if (local == null) {
                    local = new McpAuth(null, new DefaultOAuthHttpClient(),
                        u -> {}, new LoopbackCallbackHandler(), null,
                        oauthTokenStore, new OauthPort());
                    mcpAuth = local;
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] 已构建 McpAuth（tokenStore={}）", oauthTokenStore != null);
                    }
                }
            }
        }
        return local;
    }

    /** [S3 · R2-3] 惰性获取 needs-auth 缓存（Spring 未注入 → 自建纯内存实例；镜像 connectivity 模式）。 */
    private McpNeedsAuthCache needsAuth() {
        McpNeedsAuthCache local = needsAuthCache;
        if (local == null) {
            synchronized (this) {
                local = needsAuthCache;
                if (local == null) {
                    local = new McpNeedsAuthCache();
                    needsAuthCache = local;
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] 已构建 McpNeedsAuthCache（Spring 未注入，纯内存）");
                    }
                }
            }
        }
        return local;
    }

    /** [S4 Q-08] 惰性获取连接状态注册表（Spring 未注入 → 自建实例；镜像 mcpAuth 模式）。 */
    private McpConnectivityStatusRegistry connectivity() {
        McpConnectivityStatusRegistry local = connectivityRegistry;
        if (local == null) {
            synchronized (this) {
                local = connectivityRegistry;
                if (local == null) {
                    local = new McpConnectivityStatusRegistry();
                    connectivityRegistry = local;
                    if (log.isDebugEnabled()) {
                        log.debug("[McpToolPool] 已构建 McpConnectivityStatusRegistry（Spring 未注入）");
                    }
                }
            }
        }
        return local;
    }

    /** [S3] OAuth 编排专用 cached 线程池（低频一次性，可长阻塞）。 */
    private ExecutorService authFlowExecutor() {
        ExecutorService local = authFlowExecutor;
        if (local == null) {
            synchronized (this) {
                local = authFlowExecutor;
                if (local == null) {
                    local = Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "mcp-oauth-flow");
                        t.setDaemon(true);
                        return t;
                    });
                    authFlowExecutor = local;
                }
            }
        }
        return local;
    }

    /** CC isLocalMcpServer（client.ts:563-565）：!type || stdio || sdk。 */
    public static boolean isLocalMcpServer(McpTransport.TransportConfig config) {
        String type = config == null ? null : config.type();
        return type == null || "stdio".equals(type) || "sdk".equals(type);
    }

    /** 并发上限 fixed-thread-pool（pMap concurrency 语义）。 */
    private Executor fixedPool(String name, int concurrency) {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, name + "-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(Math.max(1, concurrency), tf);
    }

    /**
     * [impl-I-4 T1] 连接缓存键含 config · 对齐 CC {@code getServerCacheKey}
     * （client.ts:581-586 {@code `${name}-${jsonStringify(serverRef)}`}）。
     *
     * <p>确定性序列化 {@link McpTransport.TransportConfig} 各字段（type/command/cwd/serverName/args/env，
     * env 按键排序防 Map 无序漂移），同 name 换 config → key 变化 → 连接重建。
     *
     * @param name   MCP server 名（CC client.ts MCPServerConnection.name）
     * @param config transport 配置
     * @return 缓存键字符串（可 null 输入防御）
     */
    public String getServerCacheKey(String name, McpTransport.TransportConfig config) {
        StringBuilder sb = new StringBuilder(name == null ? "" : name).append('-');
        if (config == null) {
            return sb.append("<null>").toString();
        }
        sb.append(config.type() == null ? "" : config.type()).append('|');
        sb.append(config.command() == null ? "" : config.command()).append('|');
        sb.append(config.cwd() == null ? "" : config.cwd()).append('|');
        sb.append(config.serverName() == null ? "" : config.serverName()).append('|');
        List<String> args = config.args();
        if (args != null) {
            sb.append(String.join(",", args));
        }
        sb.append('|');
        Map<String, String> env = config.env();
        if (env != null) {
            List<String> sortedKeys = new ArrayList<>(env.keySet());
            Collections.sort(sortedKeys);
            for (String k : sortedKeys) {
                sb.append(k).append('=').append(env.get(k) == null ? "" : env.get(k)).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * [impl-I-4 T1] 惰性重连 · 对齐 CC {@code ensureConnectedClient}（client.ts:1688-1704）。
     *
     * <p>语义（自验 CC）：
     * <ul>
     *   <li>sdk 传输直返（CC :1693-1695 {@code client.config.type === 'sdk'} → 原 client）</li>
     *   <li>连接不存在 / 缓存键变化（同 name 换 config）/ transport 已 CLOSED → 重建连接
     *       （CC connectToServer = memoize(fn, getServerCacheKey) :595；onclose 清缓存 :1374-1402）</li>
     *   <li>重建前 teardown 旧连接 + 清 4 个 fetch 缓存（对齐 CC onclose 清 fetch 缓存）</li>
     *   <li>失败抛「MCP server not connected」等价异常（CC :1698-1703）</li>
     * </ul>
     *
     * @param serverName MCP server 名
     * @param config     transport 配置；null → 用该 server 上次装配的 config（惰性重连）
     * @return 已连接 transport（幂等：已连返回同对象）
     * @throws IllegalStateException 无 config 可重连 / 连接失败
     */
    public McpTransport ensureConnectedClient(String serverName, McpTransport.TransportConfig config) {
        McpTransport.TransportConfig effective = config != null ? config : serverConfigs.get(serverName);
        // [impl-I-4 F2 rework] 「是否装配过」以 serverConfigKeys（key 永非 null）为准——serverConfigs
        // 为 ConcurrentHashMap（不允许 null 值），null-config（fake factory 自决）场景不 put，
        // containsKey 语义由 serverConfigKeys 承接。对齐 CC :1698-1703 非 connected 抛
        // 「MCP server not connected」；装配过但值为 null（测试 fake factory 自决 config）→ 不抛。
        if (effective == null && !serverConfigKeys.containsKey(serverName)) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        return establishConnection(serverName, effective, getServerCacheKey(serverName, effective));
    }

    /**
     * [impl-I-4 T1] 连接建立核心：连接不存在 / 缓存键变化（同 name 换 config）/ transport CLOSED
     * → 清缓存 + 重建；否则幂等复用。
     *
     * @param serverName MCP server 名
     * @param config     本次连接 config（可为 null → 工厂自决，兼容测试 fake factory）
     * @param key        对应缓存键（调用方已算好，避免重复序列化）
     * @return 已连接 transport
     */
    private McpTransport establishConnection(String serverName, McpTransport.TransportConfig config, String key) {
        McpTransport existing = activeTransports.get(serverName);
        String existingKey = serverConfigKeys.get(serverName);
        if (existing != null && key.equals(existingKey)
            && existing.getState() != McpTransport.State.CLOSED) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] {} 已连接复用（幂等）", serverName);
            }
            return existing;
        }
        if (existing != null) {
            if (existing.getState() == McpTransport.State.CLOSED && key.equals(existingKey)) {
                // [S02 D-S02-5] 3-strike/会话过期关断后的惰性重连 → 轻量重建：仅清连接 memo +
                // fetch 缓存，保留 serverConfigs/serverConfigKeys/serverTools/ToolRegistry
                // （对齐 CC onclose 清 memo + fetch 缓存保留 tools，client.ts:1374-1402）。
                // 不再走 teardown——teardown 删 serverConfigs → callTool 惰性重连抛
                // 「not connected」（旧路径脏代码删除）。
                if (log.isDebugEnabled()) {
                    log.debug("[McpToolPool] {} transport 已 CLOSED，轻量重建连接（保留已注册工具）", serverName);
                }
                activeTransports.remove(serverName);
                invalidateFetchCaches(serverName);
            } else {
                // 连接缓存键变化（同 name 换 config）→ 全量 teardown（config 变了，旧工具/配置过期）
                log.info("[McpToolPool] {} 连接缓存键变化，重建连接（oldKey={} newKey={}）",
                    serverName, existingKey, key);
                teardown(serverName);
            }
        } else {
            invalidateFetchCaches(serverName);
        }
        McpTransport transport = connectTransport(serverName, config);
        serverConfigKeys.put(serverName, key);
        // [impl-I-4 F2 rework] ConcurrentHashMap 不允许 null 值：null-config（fake factory 自决）
        // 不 put serverConfigs（装配过标记由 serverConfigKeys 承接，ensureConnectedClient 已改判）。
        if (config != null) {
            serverConfigs.put(serverName, config);
        }
        return transport;
    }

    /**
     * [impl-I-4 T1] 连接传输（create + start + initialize + capabilities + handlers + initialized
     * notification）· 抽取自原 {@link #assembleToolPool} 的连接段，供 ensureConnectedClient / assemble 复用。
     *
     * @param serverName MCP server 名
     * @param config     transport 配置
     * @return 已连接 transport
     */
    private McpTransport connectTransport(String serverName, McpTransport.TransportConfig config) {
        long timeoutMs = effectiveConnectTimeoutMs();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        // [S02 X-4] 连接握手超时 race · 对齐 CC client.ts:1048-1077（connectPromise 与
        // getConnectionTimeoutMs() 超时 Promise race，超时 → transport.close() + 抛
        // 「connection timed out」）：create+start+initialize 在 worker 线程执行，超时即
        // close 已注册 transport + remove（防悬挂——任何连接路径不悬挂，I-2）。
        // [S07] MDC 会话回放到连接 worker · 对齐既有 LlmAgentLoop:3771-3803 /
        // YoloClassifierImpl:511-516 模式（注释明言 = CC AsyncLocalStorage 跨异步 continuation
        // 自动传播的 Java 等价）。WHY: gate 门序[3 session]（doConnectTransport channel 分支
        // :1636-1654）在 connectWorker 线程评估 ChannelSessionAllowlist.currentRequestSupplier()
        // （RequestContext MDC 解析）——无回放则调用方（HTTP 请求线程）设置的 sessionId 不可见，
        // 会话白名单注入生产不可达（恒 SESSION skip）。回放 + finally clear 防池化线程污染。
        final java.util.Map<String, String> mdcCtx = org.slf4j.MDC.getCopyOfContextMap();
        CompletableFuture<McpTransport> connectFuture = CompletableFuture.supplyAsync(() -> {
            if (mdcCtx != null) {
                org.slf4j.MDC.setContextMap(mdcCtx);
            }
            try {
                return doConnectTransport(serverName, config, timedOut);
            } finally {
                if (mdcCtx != null) {
                    org.slf4j.MDC.clear();
                }
            }
        }, connectWorker);
        try {
            return connectFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            timedOut.set(true);
            McpTransport registered = activeTransports.remove(serverName);
            if (registered != null) {
                safeClose(registered);
            }
            connectFuture.cancel(true);
            throw new IllegalStateException("MCP server \"" + serverName
                + "\" connection timed out after " + timeoutMs + "ms");
        } catch (ExecutionException ee) {
            // [S02 D-S02-7] 连接/initialize 失败 → close + remove（陈旧 transport 泄漏修复：
            // 此前失败 transport 以 CONNECTED 残留 activeTransports 被复用，偏离 CC 失败即
            // close，client.ts:1059+1632-1637）。worker 内已 safeClose；此处兜底 remove。
            activeTransports.remove(serverName);
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw cause instanceof RuntimeException re ? re
                : new IllegalStateException("MCP server \"" + serverName
                    + "\" connection failed: " + cause, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            activeTransports.remove(serverName);
            throw new IllegalStateException("MCP server \"" + serverName + "\" connection interrupted");
        }
    }

    /**
     * [S02 X-4] connectTransport 的 worker 本体（create + start + initialize + capabilities +
     * handlers + initialized notification）· 抽取自原 {@link #connectTransport}（impl-I-4 T1），
     * 在 {@link #connectWorker} 上执行以便 30s 超时 race 覆盖整个握手（CC client.ts:1048-1077）。
     * 每步检查 {@code timedOut}——超时触发后仍存活的 worker 关断本 transport 后中止（防僵尸）。
     */
    private McpTransport doConnectTransport(String serverName, McpTransport.TransportConfig config,
                                            AtomicBoolean timedOut) {
        McpTransport transport = transportFactory.create(config);
        transport.start(config);
        if (timedOut.get()) {
            safeClose(transport);
            throw new IllegalStateException("MCP server \"" + serverName + "\" connection timed out");
        }
        activeTransports.put(serverName, transport);

        // [R2-1] WS 断开 notifier 接线（对齐 CC client.onclose/onerror → 清缓存 → 惰性重连）：
        // 断开（含 4003 认证关闭）→ handleWsDisconnect 清 fetch 缓存 + 退避重连 / 认证后重连。
        if (transport instanceof WsMcpTransport wsTransport) {
            wsTransport.setDisconnectNotifier(authRequired ->
                handleWsDisconnect(serverName, config, authRequired));
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] {} WS 断开 notifier 已接线（authRequired→认证重连）", serverName);
            }
        }

        // A4 Step 2: initialize 握手
        CompletableFuture<JsonNode> initFuture = transport.sendRequest("initialize", Map.of(
            "protocolVersion", "2024-11-05",
            // [S02 X-9 / D-S02-4] capabilities 声明 {roots:{},elicitation:{}}（对齐 CC
            // client.ts:994-1001：空对象声明 capability——注释明言 {form:{},url:{}} 会破坏
            // Java MCP SDK server（Spring AI Elicitation 类零字段拒绝未知属性））。
            "capabilities", Map.of("roots", Map.of(), "elicitation", Map.of()),
            "clientInfo", Map.of("name", "nexusai-mcp-client", "version", "1.0.0")
        ));
        // [tool-v3 合并裁决] tool-v3 的 initialize orTimeout（IMP-E2 S-7）丢弃：master 已用
        // connectWorker 30s 超时 race + timedOut 逐段检查覆盖整个握手（connectTransport 1551 行
        // Promise.get(timeoutMs) + doConnectTransport timedOut 检查），orTimeout 冗余。
        JsonNode initResult;
        try {
            initResult = initFuture.join();
        } catch (Exception e) {
            // [S02 D-S02-7] initialize 失败 → 关断本 transport（陈旧连接泄漏修复）
            safeClose(transport);
            throw e;
        }
        if (timedOut.get()) {
            safeClose(transport);
            throw new IllegalStateException("MCP server \"" + serverName + "\" connection timed out");
        }
        log.info("[McpToolPool] {} initialized: {}", serverName,
            initResult.path("serverInfo").path("name").asText());

        // P1-17: 解析并持久化 capabilities（client.ts:2169 supportsResources /
        // :2038 supportsPrompts 能力门控数据源）
        JsonRpcMcpClient.Capabilities caps =
            JsonRpcMcpClient.Capabilities.fromInitializeResult(initResult);
        serverCapabilities.put(serverName, caps);
        // [决策 #65] resource 工具恒注册（@Component），此处不再条件注册 —— 对齐 CC getAllBaseTools 恒含。
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] {} capabilities: resources={} prompts={} " +
                    "toolsListChanged={} promptsListChanged={} resourcesListChanged={}",
                serverName, caps.resources(), caps.prompts(),
                caps.toolsListChanged(), caps.promptsListChanged(), caps.resourcesListChanged());
        }

        // [RES-L2 · C8] 提取并持久化 instructions（对齐 CC client.ts:1160-1169
        // client.getInstructions() + MAX_MCP_DESCRIPTION_LENGTH 截断）
        JsonNode instructionsNode = initResult.path("instructions");
        if (!instructionsNode.isMissingNode() && !instructionsNode.isNull()) {
            String rawInstructions = instructionsNode.asText();
            if (rawInstructions != null && !rawInstructions.isBlank()) {
                // CC client.ts:1162-1166 截断逻辑（MAX_MCP_DESCRIPTION_LENGTH=2048 at client.ts:218）
                if (rawInstructions.length() > MAX_MCP_DESCRIPTION_LENGTH) {
                    String truncated = rawInstructions.substring(0, MAX_MCP_DESCRIPTION_LENGTH) + "… [truncated]";
                    serverInstructions.put(serverName, truncated);
                    log.info("[McpToolPool] {} instructions 从 {} 截断到 {} 字符",
                        serverName, rawInstructions.length(), MAX_MCP_DESCRIPTION_LENGTH);
                } else {
                    serverInstructions.put(serverName, rawInstructions);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[McpToolPool] {} 已存储 server instructions (长度={})",
                        serverName, serverInstructions.get(serverName).length());
                }
            }
        }

        // P2-15: 按 capabilities.{tools,prompts,resources}.listChanged 门控注册
        // list_changed 通知处理器（对齐 CC useManageMCPConnections.ts:618/:667/:705，
        // setNotificationHandler(Schema, handler)，handler 闭包捕获 serverName）。
        if (caps.toolsListChanged()) {
            transport.setNotificationHandler("notifications/tools/list_changed",
                params -> handleToolsListChanged(serverName));
        }
        if (caps.promptsListChanged()) {
            transport.setNotificationHandler("notifications/prompts/list_changed",
                params -> handlePromptsListChanged(serverName));
        }
        if (caps.resourcesListChanged()) {
            transport.setNotificationHandler("notifications/resources/list_changed",
                params -> handleResourcesListChanged(serverName));
        }

        // [impl-I-4 F3/F6 rework] URL elicitation 完成通知 handler · 对齐 CC
        // elicitationHandler.ts registerElicitationHandler setNotificationHandler(
        // ElicitationCompleteNotificationSchema)：params {elicitationId} → markElicitationCompleted。
        // [F6] 完成通知只置 completed:true 启用「Retry now」按钮，不自动重试（重试由用户点 Retry now /
        // retryConfirm 驱动，等待期 showCancel:true 可取消）。缺注册 → completed 永不置位 → 用户点
        // Retry now 无效 → 等超时 fail-closed decline（F6 用户门语义；F3 曾为「完成→自动重试」，已修正）。
        // 对齐 CC try/catch（client 未声明 elicitation capability 时注册可能失败，CC 注释「nothing to register」）。
        try {
            transport.setNotificationHandler("notifications/elicitation/complete",
                params -> {
                    if (params != null && params.has("elicitationId")
                        && !params.path("elicitationId").asText("").isBlank()) {
                        elicitationMachine.markElicitationCompleted(params.path("elicitationId").asText());
                    } else {
                        log.warn("[McpToolPool] {} 收到 elicitation 完成通知但缺 elicitationId，忽略", serverName);
                    }
                });
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] {} 已注册 notifications/elicitation/complete handler", serverName);
            }
        } catch (Exception e) {
            log.warn("[McpToolPool] {} 注册 elicitation/complete handler 失败（无 elicitation capability）: {}",
                serverName, e.getMessage());
        }

        // [impl-I-3 T2] channel 分支 · 对齐 CC useManageMCPConnections.ts:507-530：
        //   capabilities.experimental['claude/channel'] 声明 → gateChannelServer →
        //   register → setNotificationHandler(notifications/claude/channel)（包裹+入队）；
        //   skip → 不注册（连接保持，L183-186「Connection stays up; handler not registered」）。
        //   channelNotification/gate 未接线 → 跳过（对齐 CC 无 callbacks 时 handler 不挂）。
        if (channelNotification != null && channelNotificationGate != null) {
            Map<String, Object> experimental = caps.experimental();
            if (experimental != null && experimental.containsKey("claude/channel")) {
                ChannelNotificationGate.ServerCapabilities serverCaps =
                    new ChannelNotificationGate.ServerCapabilities(experimental);
                ChannelNotificationGate.ChannelGateResult gateResult = channelNotificationGate.gateChannelServer(
                    serverName, serverCaps, pluginSourceResolver.apply(serverName));
                if ("register".equals(gateResult.action())) {
                    // Phase 4 (cron-notify): 捕获 channel 关联会话 sessionId（本方法在 connectWorker
                    // MDC 回放下执行，RequestContext.sessionId() = 建立连接的会话；CC useManageMCPConnections
                    // :523-530 channel 消息 enqueue 注入当前会话队列——Java 多会话须显式携带）。
                    // MDC 回放见 connectTransport :1516-1520（mdcCtx 回放至 connectWorker）。
                    final String channelSessionId = com.nexusai.common.RequestContext.sessionId();
                    transport.setNotificationHandler(ChannelNotification.NOTIFICATION_METHOD,
                        params -> channelNotification.receiveNotification(serverName, params, channelSessionId));
                    log.info("[McpToolPool] {} channel 入站通知 handler 已注册（notifications/claude/channel）sessionId={}",
                        serverName, channelSessionId);
                } else {
                    log.info("[McpToolPool] {} channel 跳过 (kind={})，handler 不注册（连接保持）",
                        serverName, gateResult.kind());
                }
            }
        }

        // [S02 X-5] 连接成功后 stdio stderr 摘要日志（对齐 CC client.ts:1081-1083
        // logMCPError(stderr) + 清空释放内存；日志截断 4000 字符防巨行——受控偏差见 concerns）
        if (transport instanceof StdioMcpTransport stdioTransport) {
            String stderr = stdioTransport.drainStderrLog();
            if (stderr != null && !stderr.isBlank()) {
                log.info("[McpToolPool] {} server stderr: {}", serverName, abbreviateForLog(stderr));
            }
        }

        // 发送 initialized notification (CC client.ts:202-207)
        transport.sendNotification("notifications/initialized", Map.of());
        return transport;
    }

    /** [S02] 幂等 close（日志不炸）。 */
    private static void safeClose(McpTransport transport) {
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("[McpToolPool] close transport 失败: {}", e.getMessage());
        }
    }

    /** [S02] 长日志截断（4000 字符，防单行巨日志）。 */
    private static String abbreviateForLog(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "… [truncated]";
    }

    /**
     * 完整装配: 给定 server 配置 → connect+initialize+tools/list → 注册到 ToolRegistry.
     *
     * <p>A2 Golden Trace 单个 server: connect → initialize → tools/list → register tools.
     *
     * <p>[impl-I-4 T1] 连接段改走 {@link #ensureConnectedClient}：缓存键含 config（
     * {@link #getServerCacheKey}）→ 同 name 换 config / transport CLOSED 自动重建连接；
     * 已装配且 key 未变 → 返回缓存（保持旧幂等语义）。
     *
     * <p>[R2-2 needs-auth] 连接期 401（{@link McpAuthError}）且 transport 为 sse/http →
     * <b>不抛错</b>，返回 {@code mcp__&lt;server&gt;__authenticate} 伪工具注册项（对齐 CC
     * {@code connectToServer} 连接失败返回 {@code type:'needs-auth'}，client.ts:1105-1107/
     * :1121-1123，由调用方按 needs-auth 产伪工具，client.ts:2331）。needs-auth 缓存置位 +
     * 连接状态注册表 needs-auth；OAuth 认证成功（{@link #reconnectServerForAuth}）后重建
     * 真实工具并替换伪工具。非认证失败仍抛 {@link RuntimeException}（McpServerService.start()
     * catch → status=error，契约保持）。
     *
     * @param serverName MCP 服务器名 (CC client.ts MCPServerConnection.name)
     * @param config transport 配置 (command/args/env 或 URL)
     * @return 该 server 注册的工具列表；连接期 401（needs-auth）→ 仅含 authenticate 伪工具
     */
    public List<McpToolEntry> assembleToolPool(String serverName, McpTransport.TransportConfig config) {
        String key = getServerCacheKey(serverName, config);
        String existingKey = serverConfigKeys.get(serverName);
        if (serverTools.containsKey(serverName) && key.equals(existingKey)) {
            log.info("[McpToolPool] {} already assembled, returning cached", serverName);
            return serverTools.get(serverName);
        }
        try {
            // A4 Step 1+2: connect transport + initialize + capabilities + handlers
            // （含同 name 换 config 重建；重建前 teardown 旧连接 + 清 fetch 缓存）。
            // config 为 null（测试 fake factory 自决场景）→ 直接走 establishConnection 不查
            // 存储 config（ensureConnectedClient 的「无 config → 抛」只用于惰性重连路径）。
            if (config != null) {
                ensureConnectedClient(serverName, config);
            } else {
                establishConnection(serverName, null, key);
            }

            // A4 Step 3: tools/list
            McpTransport transport = activeTransports.get(serverName);
            CompletableFuture<JsonNode> listFuture = transport.sendRequest("tools/list", Map.of());
            JsonNode listResult = listFuture.join();
            JsonNode toolsNode = listResult.path("tools");

            // FIX-A1: CC client.ts:1758 recursivelySanitizeUnicode(result.tools) —— tools/list 产物
            // 在解析为 Tool 前递归清洗（含对象键：inputSchema 属性名为攻击者可控，sanitization.ts:71-91）。
            sanitizeUnicodeInPlace(toolsNode);

            List<McpToolEntry> entries = new ArrayList<>();
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.path("name").asText("");
                    if (toolName.isEmpty()) continue;
                    // DEL-MCP-4: 工具名唯一规范化路径（CC client.ts:1768 fullyQualifiedName=buildMcpToolName(client.name,tool.name)）
                    // [impl-I-4 T9] 裸拼接 → McpStringUtils.buildMcpToolName（对齐 CC mcpStringUtils.ts:50-52，
                    // server 名含 . / 空格 / 大写时与 mcpInfoFromString 规范化消费方一致）
                    String mcpToolName = McpStringUtils.buildMcpToolName(serverName, toolName);
                    JsonNode inputSchema = toolNode.path("inputSchema");
                    // 7 参 wrapMcpTool 透传 annotations/_meta/description（CC F1-F4 语义映射，master 侧）；
                    // 注册统一走下方 registerServerTools（impl-I-4 F1 rework，批/单路径共享）
                    Tool tool = wrapMcpTool(serverName, toolName, mcpToolName, inputSchema,
                        toolNode.path("annotations"), toolNode.path("_meta"), toolNode.path("description").asText(null));
                    entries.add(new McpToolEntry(serverName, toolName, mcpToolName, inputSchema, tool));
                }
            }
            // [impl-I-4 F1 rework] 注册逻辑抽出为共享 helper（assembleToolPool 与批路径 processBatchServer 复用，
            // 避免两路径注册行为漂移——批路径必须同步 toolRegistry + serverTools）
            registerServerTools(serverName, entries);
            // P2-15: tools fetch 缓存与 serverTools 同步（对齐 CC fetchToolsForClient 缓存）
            toolsCache.put(serverName, entries);
            return entries;
        } catch (Exception e) {
            Throwable cause = unwrapCompletion(e);
            if (cause instanceof McpAuthError && isOAuthCapableTransport(config)) {
                // [R2-2 needs-auth] 单 server 装配连接期 401 → 不抛错，产出 authenticate 伪工具。
                // 对齐 CC connectToServer 连接失败返回 type='needs-auth'（client.ts:1105-1107
                // sse / :1121-1123 http → handleRemoteAuthFailure），调用方（reconnectMcpServerImpl /
                // processServer :2331）按 needs-auth 产 createMcpAuthTool。needs-auth 缓存置位 +
                // 连接状态注册表 needs-auth（对齐 batch 路径 processBatchServer 三段语义）。
                needsAuth().setCached(serverName);
                connectivity().updateNeedsAuth(serverName, config.type());
                log.info("[McpToolPool] 单 server 装配 server={} 需认证（401）→ 产出 authenticate 伪工具替换真实工具: {}",
                    serverName, cause.getMessage());
                return buildAuthToolEntries(serverName, config);
            }
            log.error("[McpToolPool] {} assembly failed: {}", serverName, e.getMessage());
            throw new RuntimeException("MCP assemble failed for " + serverName + ": " + e.getMessage(), e);
        }
    }

    /**
     * [impl-I-4 F1 rework] 注册 server 工具 + 填充 {@link #serverTools}（activeServers 数据源）。
     *
     * <p>对齐 CC onConnectionAttempt → updateServer 语义：无论 assembleToolPool（单 server 手动
     * start）还是 processBatchServer（批连接/启动预取）路径，工具必须同时进入 {@link ToolRegistry}
     * 与 {@link #serverTools}。原批路径只 fetchTools + 回调、不注册 → 预取后 activeServers() 为空
     * → ReadMcpResourceTool/ListMcpResourcesTool/SubagentTool 报「Server not found」（反射 F1）。
     *
     * <p>同一 server 重复注册（如批预取后手动 REST start）→ toolRegistry.register 按名覆盖
     * （ToolRegistry 日志 warn），serverTools.put 覆盖 —— 幂等，对齐 CC updateServer 覆盖语义。
     *
     * @param serverName MCP server 名
     * @param entries    tools/list 产物（含 wrapMcpTool 包装的 Tool）
     */
    private void registerServerTools(String serverName, List<McpToolEntry> entries) {
        for (McpToolEntry entry : entries) {
            toolRegistry.register(entry.tool());
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] registered mcp tool: {}", entry.mcpToolName());
            }
        }
        serverTools.put(serverName, entries);
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] serverTools 已填充 server={} tools={}", serverName, entries.size());
        }
    }

    /**
     * 拆卸: 注销工具 + 关闭 transport (A3 state → DISCONNECTED).
     *
     * <p>P2-15: 末尾调 {@link #invalidateServer} 清 fetch 缓存——对齐 CC 断开/清缓存
     * 时清 per-server memoize 缓存（client.ts:1389-1396 onclose / :1666-1672 clearServerCache），
     * 重连不读旧缓存。
     */
    public void teardown(String serverName) {
        // [R2-1 rework] 真实用户 stop/teardown → 中止 WS 退避重连链：remove 标志使链中后续
        // 尝试命中 current==null 守卫跳过（否则退避链会继续重连一个用户已停止的 server）。
        // 注：establishConnection/reconnectServerForAuth 内部 teardown 均伴随「已有另一路正在
        // 重建连接」，清标志是安全的（链会观察到 CONNECTED 或新连接已建立而跳过）。
        wsReconnecting.remove(serverName);
        McpTransport transport = activeTransports.remove(serverName);
        List<McpToolEntry> entries = serverTools.remove(serverName);
        serverCapabilities.remove(serverName);
        serverInstructions.remove(serverName);
        // [impl-I-4 T1] 连接缓存键/config 一并清除（重建连接不读旧配置快照）
        serverConfigKeys.remove(serverName);
        serverConfigs.remove(serverName);
        if (entries != null) {
            for (McpToolEntry entry : entries) {
                try {
                    toolRegistry.remove(entry.mcpToolName());
                } catch (Exception e) {
                    log.debug("[McpToolPool] remove {} failed: {}", entry.mcpToolName(), e.getMessage());
                }
            }
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception e) {
                log.debug("[McpToolPool] close {} failed: {}", serverName, e.getMessage());
            }
        }
        // P2-15: 断开清 fetch 缓存（对齐 CC onclose/clearServerCache 缓存失效）
        invalidateServer(serverName);
        // [决策 #65] resource 工具恒注册（@Component），teardown 不再反注册 —— 对齐 CC getAllBaseTools 恒含。
        // [S4 Q-08] 拆卸 → 连接状态注册表移除该 server（不再产生 failed/needs-auth 通知）
        connectivity().remove(serverName);
        log.info("[McpToolPool] {} torn down", serverName);
    }

    /**
     * [S02 X-8] 应用关闭时全传输清理 · 对齐 CC {@code registerCleanup(cleanup)}（client.ts:1574，
     * 全传输注册——stdio 走升级序列，网络传输 close 连接）。{@code @PreDestroy} = Spring
     * 容器关闭钩子（CC 进程退出前 registerCleanup 等价物）。
     */
    @PreDestroy
    public void closeAllTransports() {
        if (activeTransports.isEmpty()) {
            return;
        }
        log.info("[McpToolPool] 关闭 {} 个 MCP transport（registerCleanup 等价）", activeTransports.size());
        for (var entry : activeTransports.entrySet()) {
            try {
                entry.getValue().close();
                if (log.isDebugEnabled()) {
                    log.debug("[McpToolPool] closed transport server={}", entry.getKey());
                }
            } catch (Exception e) {
                log.warn("[McpToolPool] close transport 失败 server={}: {}", entry.getKey(), e.getMessage());
            }
        }
        activeTransports.clear();
    }

    /**
     * 已知 server 列表 · [G27③] 对齐 CC mcpClients 含 failed/needs-auth（TR-E2-DEC-2）：
     * 合并连接状态注册表降级态（failed/needs-auth），使「server not found」与「not connected」
     * 区分对齐 CC（ReadMcpResourceTool.ts:78-92 / ListMcpResourcesTool.ts:73-86 —— mcpClients.find
     * 命中 failed/needs-auth → not found 不成立，type!=='connected' → not connected）。
     *
     * <p>[G28②] 确定性有序（TreeSet 按名排序）——CC 为配置序，Java 运行时无配置序（并发批连接
     * processBatchServer），沿用 {@code getCurrentTools} 按名排序确定性快照模式（S04 B4，
     * tools.ts:362-364 allowedMcpTools.sort(byName)），替代原 {@code ConcurrentHashMap.keySet()}
     * 非确定序（TR-E2-DEC-5）。
     */
    public java.util.Set<String> activeServers() {
        java.util.Set<String> names = new java.util.TreeSet<>(serverTools.keySet());
        for (String degraded : connectivity().degradedNames()) {
            names.add(degraded);
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    /**
     * 已注册工具的 server 列表 · 对齐 CC AgentTool.tsx:395-404 {@code serversWithTools}（从
     * {@code mcp.tools} 收集 {@code name.startsWith('mcp__')} 的 server —— 仅连接且已注册工具的
     * server）。SubagentTool required-MCP 门控专用。
     *
     * <p>[F3 rework] 区别于 {@link #activeServers()}（合并 degraded failed/needs-auth，供
     * ReadMcpResourceTool/ListMcpResourcesTool 的 'server not found' 判定源）：degraded server
     * 无已注册工具（连接/获取失败），不应视为 'servers with tools'，否则 required-MCP 门控
     * （hasRequiredMcpServers/getMissingMcpServers/filterAgentsByMcpRequirements）把无工具降级
     * server 视为已满足，背离 CC。数据源 = {@link #serverTools}（注册了工具的 server 非空）。
     *
     * @return 确定性有序（按名）的已注册工具 server 列表；可能为空
     */
    public java.util.List<String> getServersWithTools() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Map.Entry<String, List<McpToolEntry>> e : serverTools.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                names.add(e.getKey());
            }
        }
        Collections.sort(names);
        return java.util.Collections.unmodifiableList(names);
    }

    /**
     * 指定 server 是否已连接 · 对齐 CC {@code client.type !== 'connected'}
     * （ReadMcpResourceTool.ts:86-88 前置门控 + ListMcpResourcesTool.ts:86）。
     *
     * <p>Java 语义：transport 已启动且状态为 {@link McpTransport.State#CONNECTED} 才算已连接；
     * 未装配 / 已 teardown / CLOSED 均视为未连接。
     *
     * @param serverName MCP server 名
     * @return true = 已连接
     */
    public boolean isServerConnected(String serverName) {
        McpTransport transport = activeTransports.get(serverName);
        return transport != null && transport.getState() == McpTransport.State.CONNECTED;
    }

    /** 单个 server 的工具列表. */
    public Optional<List<McpToolEntry>> getServerTools(String serverName) {
        return Optional.ofNullable(serverTools.get(serverName));
    }

    /**
     * [Q-11-5 DIV-2] 会话过期同调用内重试次数 · 对齐 CC {@code MAX_SESSION_RETRIES=1}
     * （client.ts:1859）。
     *
     * <p>语义（Read CC 真源自验，client.ts:1859-1922）：call() 主循环
     * {@code for (let attempt = 0; ; attempt++)} 内，捕获
     * {@code error instanceof McpSessionExpiredError && attempt < MAX_SESSION_RETRIES}
     * → {@code continue} 同调用内重试一次（此时 clearServerCache 已清缓存，
     * ensureConnectedClient 重建新 session）；重试（attempt=1）仍失败 → 不再重试，
     * 抛原错误。Java 落地：{@link #callToolAttempt} 的 attempt 计数 + 仅
     * {@code attempt < MAX_SESSION_RETRIES} 时递归重试，否则 {@link CompletableFuture#failedFuture}
     * 抛原 {@link McpSessionExpiredException}（CC 最终抛的仍为该错误语义，任务验收
     * 「重试仍失败则抛原错误」）。
     */
    static final int MAX_SESSION_RETRIES = 1;

    /**
     * 调用 MCP 工具 (CC client.ts tools/call) · 对齐 CC call() 主循环的
     * MAX_SESSION_RETRIES=1 同调用内会话过期重试（client.ts:1859-1922）。
     *
     * @return 服务端 isError + content blocks (对齐 CC CallToolResult)
     */
    public CompletableFuture<JsonNode> callTool(String serverName, String toolName, Map<String, Object> args) {
        return callToolAttempt(serverName, toolName, args == null ? Map.of() : args, 0);
    }

    /**
     * 单次工具调用尝试 · 对齐 CC call() 循环体（client.ts:1860-1922）：
     * ensureConnectedClient → tools/call；会话过期（{@link McpSessionExpiredException}）且
     * {@code attempt < MAX_SESSION_RETRIES} → 清缓存后同调用内重试（attempt+1）；重试仍失败
     * → 抛原错误（不再重试，对齐 CC client.ts:1914-1922 的 attempt 边界判定）。
     *
     * <p>[Q-11-5 DIV-1] 会话过期 → 清连接缓存（对齐 CC clearServerCache client.ts:1648-1673）：
     *   activeTransports.remove = 清连接 memo（重试的 ensureConnectedClient 重建新 session）；
     *   invalidateFetchCaches = 清 tools/resources/commands/skills fetch 缓存（下次 fetch 重拉）。
     *   不 teardown：保留已注册工具（CC server.tools 重连后仍可用）——teardown 会移除
     *   ToolRegistry 中的工具，导致重连后 MCP 工具不可调用（与 CC clearServerCache 语义相悖）。
     *
     * @param serverName MCP server 名
     * @param toolName   工具名
     * @param args       工具参数（已保证非 null）
     * @param attempt    当前尝试序号（0 起；会话过期重试时递增）
     * @return tools/call 结果 future（同调用重试在内部串行完成后以最终结果/最终错误完成）
     */
    private CompletableFuture<JsonNode> callToolAttempt(String serverName, String toolName,
                                                        Map<String, Object> args, int attempt) {
        McpTransport transport;
        try {
            // [impl-I-4 T1] 改走 ensureConnectedClient：CLOSED/会话过期 → 惰性重连；
            // 从未装配（无 config）→ 失败 future（保持旧契约，调用方 .join() 抛）
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        long toolStartTime = System.currentTimeMillis();
        // [S02 X-7] 工具调用超时 · 对齐 CC client.ts:3070-3118（Promise.race([callTool,
        // timeoutPromise]) → TelemetrySafeError「MCP tool timeout」）：getMcpToolTimeoutMs =
        // env MCP_TOOL_TIMEOUT || 100_000_000（client.ts:211/222-229）。超时仅拒绝本调用，
        // 不关闭 transport（CC 语义 client.ts:3073-3089）。
        long timeoutMs = effectiveToolTimeoutMs();
        long progressIntervalMs = effectiveProgressLogIntervalMs();
        CompletableFuture<JsonNode> callFuture = transport.sendRequest("tools/call", Map.of(
            "name", toolName,
            "arguments", args
        ));
        CompletableFuture<JsonNode> timeoutFuture = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask = toolCallScheduler.schedule(() ->
            timeoutFuture.completeExceptionally(new IllegalStateException(
                "MCP server \"" + serverName + "\" tool \"" + toolName + "\" timed out after "
                    + Math.max(1, timeoutMs / 1000) + "s")), timeoutMs, TimeUnit.MILLISECONDS);
        // [S02 X-7] 30s 进度日志 · 对齐 CC client.ts:3054-3066（setInterval 每 30s
        // 「Tool 'x' still running (Ns elapsed)」）；whenComplete/finally 取消定时器。
        ScheduledFuture<?> progressTask = toolCallScheduler.scheduleAtFixedRate(() -> {
            long elapsedSeconds = (System.currentTimeMillis() - toolStartTime) / 1000;
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] 工具 '{}' 仍在运行（{} 秒）server={}", toolName, elapsedSeconds, serverName);
            }
        }, progressIntervalMs, progressIntervalMs, TimeUnit.MILLISECONDS);
        return callFuture.applyToEither(timeoutFuture, r -> r)
            .handle((result, err) -> {
                timeoutTask.cancel(false);
                progressTask.cancel(false);
                if (err == null) {
                    return CompletableFuture.completedFuture(result);
                }
                McpSessionExpiredException sessionExpired = unwrapSessionExpired(err);
                if (sessionExpired != null) {
                    // [Q-11-5 DIV-1] 清连接缓存（对齐 CC clearServerCache client.ts:1648-1673）：
                    //   下次 ensureConnectedClient 重建新 session；保留已注册工具。
                    //   [S02] TimeoutException 天然不进入本分支（unwrapSessionExpired 白名单
                    //   仅认 McpSessionExpiredException）→ 超时不触发会话过期重试链。
                    activeTransports.remove(serverName);
                    invalidateFetchCaches(serverName);
                    if (attempt < MAX_SESSION_RETRIES) {
                        // 对齐 CC client.ts:1913-1922：attempt < MAX_SESSION_RETRIES → continue 重试
                        log.info("[McpToolPool] {} 会话过期（404+-32001），已清连接缓存，同调用内重试 "
                            + "attempt#{}→{} tool={}", serverName, attempt, attempt + 1, toolName);
                        return callToolAttempt(serverName, toolName, args, attempt + 1);
                    }
                    log.info("[McpToolPool] {} 会话过期重试仍失败（attempt={} 已达 MAX_SESSION_RETRIES={}），"
                        + "抛原错误 tool={}", serverName, attempt, MAX_SESSION_RETRIES, toolName);
                    return CompletableFuture.<JsonNode>failedFuture(sessionExpired);
                }
                return CompletableFuture.<JsonNode>failedFuture(err);
            }).thenCompose((CompletableFuture<JsonNode> f) -> f);
    }

    /**
     * 判定并解包会话过期错误 · 容忍 {@link CompletionException} 包装（各 transport future
     * 完成方式不同：直接 completeExceptionally 或经 compose 后包装），取最内层 cause 判别。
     *
     * @param t 待判定的异常
     * @return 最内层 {@link McpSessionExpiredException}；非会话过期 → null
     */
    private static McpSessionExpiredException unwrapSessionExpired(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException ce && ce.getCause() != null) {
            cur = ce.getCause();
        }
        return cur instanceof McpSessionExpiredException mse ? mse : null;
    }

    /**
     * P1-17: 拉取 MCP server 的 resources/list 资源 · 对齐 CC {@code client.ts:2000-2031 fetchResourcesForClient}.
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:2002 {@code client.type !== 'connected'} → []</li>
     *   <li>:2005-2007 无 {@code client.capabilities?.resources} → []</li>
     *   <li>:2009-2012 sendRequest({method:'resources/list'}, ListResourcesResultSchema)</li>
     *   <li>:2017-2020 每个 resource 追加 server: client.name</li>
     *   <li>:2021-2027 catch → logMCPError + []（fail-soft 不抛）</li>
     * </ul>
     *
     * <p>P2-15: memoizeWithLRU（:2029-2030）Java 化 — 结果按 serverName 缓存（
     * {@link #resourcesCache}，容量 {@link #MCP_FETCH_CACHE_SIZE}），命中直接返回；
     * miss 往返后缓存（含 fail-soft 空 list，对齐 CC 缓存 resolve 后的 []）。
     *
     * @param serverName MCP server 名
     * @return 该 server 的资源列表；未连接 / 无 resources 能力 / 协议失败返回空 list
     */
    public List<McpResource> fetchResources(String serverName) {
        // CC memoizeWithLRU 命中直接返回（key=client.name）
        List<McpResource> cached = resourcesCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        List<McpResource> result = doFetchResources(serverName);
        resourcesCache.put(serverName, result);
        return result;
    }

    /** P2-15: fetchResources 的协议往返本体（未命中缓存时执行，CC client.ts:2000-2031）。 */
    private List<McpResource> doFetchResources(String serverName) {
        // CC :2002 client.type !== 'connected' → []（[impl-I-4 T1] 未装配/连接失败 → [] fail-soft）
        McpTransport transport;
        try {
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            log.warn("[McpToolPool] fetchResources 跳过未连接 server={}", serverName);
            return List.of();
        }
        // CC :2005-2007 无 resources 能力 → []
        JsonRpcMcpClient.Capabilities caps = serverCapabilities.get(serverName);
        if (caps == null || !caps.resources()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchResources 跳过无 resources 能力 server={}", serverName);
            }
            return List.of();
        }
        try {
            // CC :2009-2012 resources/list 往返
            JsonNode result = transport.sendRequest("resources/list", Map.of()).join();
            // CC :2017-2020 追加 server 字段（listResourcesFromJson 内部完成）
            return jsonRpcMcpClient.listResourcesFromJson(result, serverName);
        } catch (Exception e) {
            // CC :2021-2027 fail-soft：catch → logMCPError + []
            log.warn("[McpToolPool] fetchResources 失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * P1-17: 拉取 MCP server 的 prompts/list 命令 · 对齐 CC {@code client.ts:2033-2107 fetchCommandsForClient}.
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:2035 非 connected → []</li>
     *   <li>:2038-2040 无 {@code client.capabilities?.prompts} → []</li>
     *   <li>:2043-2046 sendRequest({method:'prompts/list'}, ListPromptsResultSchema)</li>
     *   <li>:2054-2095 映射 Command（name='mcp__'+normalize+'__'+prompt.name、source:'mcp'、
     *       userFacingName='server:name (MCP)'）</li>
     *   <li>:2097-2103 catch → logMCPError + []</li>
     * </ul>
     *
     * <p>P2-15: memoizeWithLRU（:2106）Java 化 — 结果按 serverName 缓存（{@link #commandsCache}），
     * 命中直接返回；miss 往返后缓存。
     *
     * @param serverName MCP server 名
     * @return 该 server 的 prompt 命令列表（source='mcp'，无 loadedFrom → 普通 MCP prompt 非 skill）；
     *         未连接 / 无 prompts 能力 / 协议失败返回空 list
     */
    public List<Command> fetchCommands(String serverName) {
        // CC memoizeWithLRU 命中直接返回（key=client.name）
        List<Command> cached = commandsCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        List<Command> result = doFetchCommands(serverName);
        commandsCache.put(serverName, result);
        return result;
    }

    /** P2-15: fetchCommands 的协议往返本体（未命中缓存时执行，CC client.ts:2033-2107）。 */
    private List<Command> doFetchCommands(String serverName) {
        // CC :2035 非 connected → []（[impl-I-4 T1] 惰性重连；未装配 → [] fail-soft）
        McpTransport transport;
        try {
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            log.warn("[McpToolPool] fetchCommands 跳过未连接 server={}", serverName);
            return List.of();
        }
        // CC :2038-2040 无 prompts 能力 → []
        JsonRpcMcpClient.Capabilities caps = serverCapabilities.get(serverName);
        if (caps == null || !caps.prompts()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchCommands 跳过无 prompts 能力 server={}", serverName);
            }
            return List.of();
        }
        try {
            // CC :2043-2046 prompts/list 往返
            JsonNode result = transport.sendRequest("prompts/list", Map.of()).join();
            // MC-04a: CC :2051 recursivelySanitizeUnicode(result.prompts) —— 隐藏 Unicode 清洗
            // （在映射前递归 sanitize 全部字符串字段：prompt.name/description/arguments[].name 等）。
            sanitizeUnicodeInPlace(result);
            // CC :2054-2095 映射 Command；OQ-MC-01: CC :2073-2094 getPromptForCommand 闭包
            // → Command.promptFn 接线（fetchPrompt 孤儿方法落地为可执行 promptFn）。
            return wirePromptFunctions(serverName, jsonRpcMcpClient.listPromptsFromJson(result, serverName));
        } catch (Exception e) {
            // CC :2097-2103 fail-soft：catch → logMCPError + []
            log.warn("[McpToolPool] fetchCommands 失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * P1-17: MCP {@code prompts/get} 往返 · 对齐 CC {@code client.ts:2077-2080 getPrompt}
     * （fetchCommandsForClient 内 getPromptForCommand 调用）。
     *
     * <p>本项只建协议方法，消费侧接线（SkillTool 执行 MCP command 时调 getPrompt）
     * 留 P2-9/P2-13。
     *
     * @param serverName MCP server 名
     * @param promptName prompt 名（prompts/list 产物原名）
     * @param args       命名参数（CC zipObject(argNames, argsArray)）
     * @return prompts/get 响应 result 节点
     */
    public CompletableFuture<JsonNode> fetchPrompt(String serverName, String promptName,
                                                   Map<String, Object> args) {
        McpTransport transport;
        try {
            // [impl-I-4 T1] 惰性重连（CLOSED → 重建）；从未装配 → 失败 future
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            CompletableFuture<JsonNode> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        return transport.sendRequest("prompts/get", Map.of(
            "name", promptName,
            "arguments", args == null ? Map.of() : args
        ));
    }

    // ══════════════ MC-04a / OQ-MC-01: prompts/list sanitize + promptFn 接线（CC client.ts:2051 / :2073-2094）══════════════

    /**
     * MC-04a + FIX-A1: 递归 sanitize JSON 节点 · 对齐 CC {@code recursivelySanitizeUnicode}
     * （sanitization.ts:71-91）+ 应用点 {@code client.ts:2051 recursivelySanitizeUnicode(result.prompts)}
     * + {@code client.ts:1758 recursivelySanitizeUnicode(result.tools)}。
     *
     * <p>就地（in-place）改写 {@link ObjectNode} 字段与 {@link ArrayNode} 元素：
     * <ul>
     *   <li><b>字符串值</b> → {@link UnicodeSanitizer#partiallySanitizeUnicode}（NFKC + 移除
     *       Cf/Co/Cn + 显式 ranges）清洗隐藏 Unicode 攻击字符（HackerOne #3086545）</li>
     *   <li><b>对象键</b> → 同样经 partiallySanitizeUnicode 清洗（CC :83-85 对键与值同时 sanitize）。
     *       prompts/list 的键是固定协议字段（ASCII，清洗无观测差异）；tools/list 的
     *       {@code inputSchema} 属性名<b>为攻击者可控</b>（恶意 MCP server 可在 schema 键中隐藏
     *       Unicode），故必须按 CC 语义清洗键（FIX-A1 升级，原 MC-04a 仅清洗值）。</li>
     *   <li><b>容器</b> → 递归（数组逐元素 / 对象逐字段）</li>
     *   <li><b>非文本标量</b>（number/boolean/null）→ 不变（CC :89-90 原样返回）</li>
     * </ul>
     *
     * <p>键清洗实现（CC :80-87 建新对象语义）：收集原字段名 → 逐字段把「清洗后键」写入替换
     * ObjectNode（重复清洗键后写覆盖，等价 CC {@code sanitized[key]=val} 覆盖语义）→ 全量替换。
     * 不采用 remove+put 原地改键，避免遍历期键集变更。
     *
     * @param node 待 sanitize 的 JSON 节点（可为 null / 非容器）
     */
    private void sanitizeUnicodeInPlace(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fields::add);
            // CC :80-87 建新对象语义：键与值都经 recursivelySanitizeUnicode，重复键后写覆盖
            ObjectNode replacement = MAPPER.createObjectNode();
            for (String field : fields) {
                JsonNode child = obj.get(field);
                String sanitizedKey = UnicodeSanitizer.partiallySanitizeUnicode(field);
                if (child.isTextual()) {
                    replacement.put(sanitizedKey,
                        UnicodeSanitizer.partiallySanitizeUnicode(child.asText()));
                } else {
                    sanitizeUnicodeInPlace(child);
                    replacement.set(sanitizedKey, child);
                }
            }
            obj.removeAll();
            obj.setAll(replacement);
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) {
                    arr.set(i, TextNode.valueOf(UnicodeSanitizer.partiallySanitizeUnicode(child.asText())));
                } else {
                    sanitizeUnicodeInPlace(child);
                }
            }
        }
        // 非文本标量（number/boolean/null）不变（CC :89-90 原样返回）
    }

    /**
     * OQ-MC-01 + MC-04b: prompts/list 产物 Command 逐条接线 promptFn + isMcp · 对齐 CC
     * {@code client.ts:2064 isMcp: true} + {@code client.ts:2073-2094 getPromptForCommand}
     * 内嵌闭包（{@code ensureConnectedClient → getPrompt({name, arguments:
     * zipObject(argNames, argsArray)}) → transformResultContent → flat}）。
     *
     * <p>CC 闭包直接捕获 {@code prompt.name} + {@code argNames}（client.ts:2078 用
     * {@code prompt.name}，不复解复合命令名）；Java 等价：从已知前缀
     * {@code mcp__<normalizedServer>__}（client.ts:2058 构造）剥离得到原始 promptName，
     * 捕获 argNames 落位 {@code Command.promptFn}。NG-4：server 名含 {@code __} 时若用
     * {@link McpStringUtils#mcpInfoFromString} 反解会截断 server 段（mcpStringUtils.ts:15
     * 同限制）→ promptName 反解失效，故本方法不再反解。promptFn 签名
     * {@code (args, PromptFnContext) -> List<String>}（文本块，[拍板#9 part2] 会话通道，
     * MCP prompt 不消费 context 仅透传），失败 log + rethrow（CC :2087-2092
     * catch → logMCPError + throw）。
     *
     * <p>MC-04b：CC prompt Command 置 {@code isMcp: true}（独立布尔，判别式
     * {@code McpServerUtils.isMcpCommand} = {@code name.startsWith('mcp__') || isMcp === true}，
     * utils.ts:254-255）；字段由 CI-1 补，本方法落位消费侧接线。
     *
     * <p>FIX-C3（拍板#11 part · NG-5）：isMcp 值经 {@link McpServerUtils#isMcpCommand} 判别产出
     * ——使该判别成为生产消费方（prompts/list 产物恒带 {@code mcp__} 前缀 → 判别恒 true，
     * 可观测行为与 CC client.ts:2064 无条件 {@code isMcp: true} 一致；同时保证若未来命令源
     * 改造去掉前缀，字段值仍由 CC 判别式正确计算，而非硬编码）。
     *
     * @param serverName MCP server 名
     * @param commands   prompts/list 映射产物（listPromptsFromJson）
     * @return 逐条接线 promptFn + isMcp 后的同一列表
     */
    private List<Command> wirePromptFunctions(String serverName, List<Command> commands) {
        // NG-4: 对齐 CC 闭包直接捕获 prompt.name——从已知前缀剥离（不复解复合命令名），
        // server/prompt 名含 `__` 均不受影响（getMcpPrefix = `mcp__${normalize}__`，client.ts:2058 同构造）
        String promptPrefix = McpStringUtils.getMcpPrefix(serverName);
        for (Command cmd : commands) {
            if (cmd == null || cmd.getName() == null) {
                continue;
            }
            // MC-04b: CC client.ts:2064 isMcp: true（prompt Command 独立 MCP 标记）
            // FIX-C3: isMcp 值由 CC 判别式 McpServerUtils.isMcpCommand（utils.ts:254-255）消费产出
            cmd.setIsMcp(McpServerUtils.isMcpCommand(cmd));
            String name = cmd.getName();
            if (!name.startsWith(promptPrefix)) {
                continue;
            }
            String promptName = name.substring(promptPrefix.length());
            if (promptName.isEmpty()) {
                continue;
            }
            List<String> argNames = cmd.getArgNames() != null ? cmd.getArgNames() : List.of();
            cmd.setPromptFn((args, context) -> executePrompt(serverName, promptName, argNames, args));
        }
        return commands;
    }

    /**
     * OQ-MC-01: MCP prompt 执行（promptFn 运行时本体）· 对齐 CC {@code getPromptForCommand}
     * （client.ts:2074-2086）：{@code args.split(' ') → zipObject(argNames, argsArray) →
     * getPrompt → transformResultContent(each message.content) → flat}。
     *
     * <p>{@link #fetchPrompt} 内部已做 {@code ensureConnectedClient}（CC :2076）；失败
     * log + rethrow（CC :2087-2092）。返回 {@code List<ContentBlockParam>} 内容块
     * （CC getPromptForCommand 返回 {@code Array<ContentBlockParam>}，含 image 块；P2-16
     * 图片块通道——consumer SkillToolImpl 取 text 块 join 供文本管线，image 块透传进
     * newMessage contentBlocks）。
     */
    private List<ContentBlockParam> executePrompt(String serverName, String promptName,
                                                  List<String> argNames, String args) {
        // CC :2074 argsArray = args.split(' ')
        String[] argsArray = (args == null || args.isEmpty()) ? new String[0] : args.split(" ");
        // CC :2079 arguments: zipObject(argNames, argsArray)
        Map<String, Object> namedArgs = zipObject(argNames, argsArray);
        try {
            JsonNode result = fetchPrompt(serverName, promptName, namedArgs).join();
            List<ContentBlockParam> out = new ArrayList<>();
            // CC :2081-2085 result.messages.map(m => transformResultContent(m.content, name)).flat()
            JsonNode messages = result == null ? null : result.path("messages");
            if (messages != null && messages.isArray()) {
                for (JsonNode message : messages) {
                    out.addAll(transformResultContent(message.path("content"), serverName));
                }
            }
            return out;
        } catch (Exception e) {
            // CC :2088-2091 logMCPError + rethrow
            log.error("[McpToolPool] Error running command '{}': {}", promptName, e.getMessage());
            throw new RuntimeException("Error running command '" + promptName + "': " + e.getMessage(), e);
        }
    }

    /** OQ-MC-01: CC {@code zipObject(argNames, argsArray)}（lodash）—— 键序对齐 argNames，缺值补 null。 */
    private Map<String, Object> zipObject(List<String> argNames, String[] argsArray) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < argNames.size(); i++) {
            map.put(argNames.get(i), i < argsArray.length ? argsArray[i] : null);
        }
        return map;
    }

    /**
     * OQ-MC-01: transformResultContent · 对齐 CC {@code client.ts:2478-2591} 逐 type 分支。
     *
     * <p>Java 端 promptFn 返回 {@code List<ContentBlockParam>}（对齐 CC
     * {@code Array<ContentBlockParam>}，P2-16 图片块通道——移除旧文本约束降级）：
     * <ul>
     *   <li>text / resource(text) / resource_link → {@link ContentBlockParam.TextBlockParam}
     *       （等价 CC text 块 :2483-2489/:2528-2534/:2575-2587）</li>
     *   <li>image → {@link ContentBlockParam.ImageBlockParam}（CC :2503-2523 resize →
     *       image 块，Java 经 {@link ImageResizer#resizeMcpImage} 标准缩放，模型可见内联图）</li>
     *   <li>resource(blob) image → {@code [TextBlockParam(prefix), ImageBlockParam]}（CC :2535-2563）</li>
     *   <li>resource(blob) 非 image / audio → base64 解码落盘 → 保存提示文本
     *       （等价 CC persistBlobToTextBlock :2564-2571/:2490-2502）</li>
     *   <li>default → []（CC :2588-2589）</li>
     * </ul>
     *
     * <p>image 缩放失败（空 buffer / 超限且处理失败）→ {@link ImageResizeError} 上抛（CC
     * imageResizer.ts:174-180/:383-432 throw 语义），由 {@link #executePrompt} catch 统一 fail-loud。
     */
    private List<ContentBlockParam> transformResultContent(JsonNode content, String serverName) {
        if (content == null || content.isNull()) {
            return List.of();
        }
        String type = content.path("type").asText("");
        switch (type) {
            case "text": // CC :2483-2489
                return List.of(new ContentBlockParam.TextBlockParam(content.path("text").asText("")));
            case "resource": { // CC :2524-2574
                JsonNode resource = content.path("resource");
                String uri = resource.path("uri").asText("");
                String prefix = "[Resource from " + serverName + " at " + uri + "] ";
                // [G28④] 对齐 CC :2528 'text' in resource —— 键存在判定（含 text:null，与
                //   McpResultTransformer.transformResultContent 合并后同口径）
                if (resource.has("text")) { // CC :2528-2534
                    return List.of(new ContentBlockParam.TextBlockParam(prefix + resource.path("text").asText("")));
                }
                if (resource.has("blob") && !resource.path("blob").isNull()) { // CC :2535 blob 分支
                    String mimeType = resource.path("mimeType").asText(null);
                    // CC :2536 isImage = IMAGE_MIME_TYPES.has(resource.mimeType ?? '')
                    if (IMAGE_MIME_TYPES.contains(mimeType == null ? "" : mimeType)) {
                        // CC :2538-2563 image blob → [text prefix, image block]
                        return List.of(
                            new ContentBlockParam.TextBlockParam(prefix),
                            resizeImageToBlock(resource.path("blob").asText(""), mimeType, serverName));
                    }
                    // CC :2564-2571 非 image blob → persistBlobToTextBlock
                    return List.of(new ContentBlockParam.TextBlockParam(persistBlobToText(
                        resource.path("blob").asText(""), mimeType, prefix, serverName)));
                }
                return List.of(); // CC :2573 无 text 亦无 blob
            }
            case "resource_link": { // CC :2575-2587
                String name = content.path("name").asText("");
                String uri = content.path("uri").asText("");
                String text = "[Resource link: " + name + "] " + uri;
                if (content.has("description") && !content.path("description").isNull()) {
                    text += " (" + content.path("description").asText("") + ")";
                }
                return List.of(new ContentBlockParam.TextBlockParam(text));
            }
            case "image": // CC :2503-2523（P2-16：resize → image 块，模型可见内联图）
                return List.of(resizeImageToBlock(content.path("data").asText(""),
                    content.path("mimeType").asText(null), serverName));
            case "audio": // CC :2490-2502
                return List.of(new ContentBlockParam.TextBlockParam(persistBlobToText(
                    content.path("data").asText(""),
                    content.path("mimeType").asText(null),
                    "[Audio from " + serverName + "] ", serverName)));
            default: // CC :2588-2589
                return List.of();
        }
    }

    /**
     * P2-16: 图片 base64 → resize → image 内容块 · 对齐 CC client.ts:2503-2511
     * {@code Buffer.from(data,'base64') → maybeResizeAndDownsampleImageBuffer(buffer, length, ext)
     * → {type:'image', source:{type:'base64', media_type, data}}}
     * （imageResizer.ts:169-433 标准缩放，无 token 预算激进压缩）。
     *
     * <p>Java 严格 Base64 解码（CC Buffer.from lenient）：非法 base64 → IllegalArgumentException
     * 上抛（CC lenient 解码产物交 sharp 处理必失败 → ImageResizeError → 同 fail-loud 语义）。
     */
    private ContentBlockParam resizeImageToBlock(String base64Data, String mimeType, String serverName) {
        byte[] imageBuffer = Base64.getDecoder().decode(base64Data);
        ImageResizer.ResizedMcpImage resized = ImageResizer.resizeMcpImage(imageBuffer, mimeType);
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] MCP prompt image 缩放完成 server={} mediaType={} base64Len={} (CC client.ts:2512-2521)",
                serverName, resized.mediaType(), resized.base64().length());
        }
        return ContentBlockParam.ImageBlockParam.of(resized.mediaType(), resized.base64());
    }

    /**
     * OQ-MC-01: base64 二进制内容落盘 → 保存提示文本 · 对齐 CC {@code persistBlobToTextBlock}
     * （client.ts:2598-2609）+ {@code persistBinaryContent}（mcpOutputStorage.ts:148-174）。
     *
     * <p>promptFn 无会话目录（cwd 可取但无 sessionId）→ 落盘系统临时目录 tool-results
     * （ReadMcpResourceTool 纯测试直调同口径）；失败返回 text 错误（CC persist 失败 → text 错误）。
     */
    private String persistBlobToText(String base64Data, String mimeType, String prefix, String serverName) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            // NG-3: CC 端 Buffer.from(data, 'base64') lenient 不会在解码抛错（client.ts:2497/2540/2566），
            // Java 严格 Base64 会抛 → 该路径 CC 不存在，无字节计数可用（按 0 报，Java 特有路径）
            log.warn("[McpToolPool] base64 解码失败 server={}: {}", serverName, e.getMessage());
            return prefix + "Binary content (" + mimeTypeLabel(mimeType) + ", 0 bytes) could not be saved to disk: " + e.getMessage();
        }
        try {
            // CC :2604 persistId = `mcp-${normalizeNameForMCP(serverName)}-blob-${Date.now()}-${rand6}`
            String persistId = "mcp-" + McpStringUtils.normalizeNameForMCP(serverName)
                + "-blob-" + System.currentTimeMillis() + "-" + randomSuffix(6);
            Path dir = Path.of(System.getProperty("java.io.tmpdir", ".")).resolve("tool-results");
            McpOutputStorage.PersistBinaryResult persisted =
                McpOutputStorage.persistBinaryContent(dir, bytes, mimeType, persistId);
            if (persisted.isError()) {
                // NG-3: CC client.ts:2611 persistBlobToTextBlock error 分支错误文本含 (mimeType, N bytes)
                return prefix + "Binary content (" + mimeTypeLabel(mimeType) + ", " + bytes.length
                    + " bytes) could not be saved to disk: " + persisted.error();
            }
            return McpOutputStorage.getBinaryBlobSavedMessage(
                persisted.filepath(), mimeType, persisted.size(), prefix);
        } catch (Exception e) {
            log.warn("[McpToolPool] 二进制内容落盘失败 server={}: {}", serverName, e.getMessage());
            // NG-3: 同上，错误文本含 (mimeType, N bytes) 段
            return prefix + "Binary content (" + mimeTypeLabel(mimeType) + ", " + bytes.length
                + " bytes) could not be saved to disk: " + e.getMessage();
        }
    }

    /** CC client.ts:2611 `${mimeType || 'unknown type'}` —— mimeType 空值/空串回退文案（JS 空串 '' 亦为 falsy）。 */
    private static String mimeTypeLabel(String mimeType) {
        return (mimeType == null || mimeType.isEmpty()) ? "unknown type" : mimeType;
    }

    /** 6 位随机字母数字（CC {@code Math.random().toString(36).slice(2,8)} 等价）。 */
    private static String randomSuffix(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * P2-13: 从 {@code skill://} 资源发现 MCP 技能命令 · 对齐 CC {@code mcpSkills.ts fetchMcpSkillsForClient}
     * （消费点 client.ts:2174/2348，E4 推断）。
     *
     * <p>CC 语义（消费点推断 + fetchResourcesForClient 同构）：
     * <ul>
     *   <li>connected 门控（client.ts:2002 模式）：transport 不存在 → []</li>
     *   <li>resources capability 门控（client.ts:2005-2007 模式）：无 resources 能力 → []（不发请求）</li>
     *   <li>resources/list → 过滤 {@code uri.startsWith(SKILL_URI_SCHEME)}（client.ts:2347 注释）</li>
     *   <li>逐 skill {@link #readResource} 取内容 → {@code parseSkillFrontmatterFields} →
     *       {@code createSkillCommand}（{@link McpSkillBuilders#get()}）</li>
     *   <li>fail-soft（client.ts:2021-2027 模式）：catch → log.warn + []</li>
     * </ul>
     *
     * <p>命令名 = {@code McpStringUtils.buildMcpToolName(server, skillName)}（E4 推断，对齐
     * fetchCommandsForClient client.ts:2058 {@code 'mcp__'+normalize+'__'+prompt.name} 模式）；
     * loadedFrom=CommandSource.MCP（getMcpSkillCommands 过滤键 commands.ts:551-556）。
     * MCP 技能 baseDir=null（无本地 skill 目录，CC loadSkillsDir.ts:343 skillRoot null）；
     * 安全闸：MCP 技能永不执行内联 shell（CC loadSkillsDir.ts:374-396 {@code if (loadedFrom !== 'mcp')}
     * 才 executeShellCommandsInPrompt）——本方法仅静态落位 content，执行侧由消费方保证。
     *
     * <p>P2-15: memoizeWithLRU Java 化 — 结果按 serverName 缓存（{@link #skillsCache}），
     * 命中直接返回（CC prompts/list_changed 处理器「skills 走缓存」useManageMCPConnections.ts:681）；
     * resources/list_changed 时删除 + 重取（:723）。
     *
     * @param serverName MCP server 名
     * @return 该 server 的 MCP skill 命令列表；未连接 / 无 resources 能力 / 协议失败返回空 list
     */
    public List<Command> fetchMcpSkills(String serverName) {
        // CC memoizeWithLRU 命中直接返回（key=client.name）
        List<Command> cached = skillsCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        List<Command> result = doFetchMcpSkills(serverName);
        skillsCache.put(serverName, result);
        return result;
    }

    /** P2-15: fetchMcpSkills 的协议往返本体（未命中缓存时执行，CC mcpSkills.ts 消费点推断）。 */
    private List<Command> doFetchMcpSkills(String serverName) {
        // CC :2002 模式 connected 门控（[impl-I-4 T1] 惰性重连；未装配 → [] fail-soft）
        McpTransport transport;
        try {
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            log.warn("[McpToolPool] fetchMcpSkills 跳过未连接 server={}", serverName);
            return List.of();
        }
        // CC :2005-2007 模式 resources capability 门控
        JsonRpcMcpClient.Capabilities caps = serverCapabilities.get(serverName);
        if (caps == null || !caps.resources()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchMcpSkills 跳过无 resources 能力 server={}", serverName);
            }
            return List.of();
        }
        try {
            // CC :2009-2012 resources/list 往返
            JsonNode result = transport.sendRequest("resources/list", Map.of()).join();
            List<McpResource> resources = jsonRpcMcpClient.listResourcesFromJson(result, serverName);
            List<Command> skills = new ArrayList<>();
            for (McpResource resource : resources) {
                // CC client.ts:2347 注释「Discover skills from skill:// resources」——仅 skill:// 前缀资源是技能
                if (resource.uri() == null || !resource.uri().startsWith(SKILL_URI_SCHEME)) {
                    continue;
                }
                String content = readResource(serverName, resource.uri());
                if (content == null || content.isBlank()) {
                    continue;
                }
                Command skill = buildSkillCommand(serverName, resource, content);
                if (skill != null) {
                    skills.add(skill);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchMcpSkills server={} resources={} skills={}",
                    serverName, resources.size(), skills.size());
            }
            return skills;
        } catch (Exception e) {
            // CC :2021-2027 模式 fail-soft
            log.warn("[McpToolPool] fetchMcpSkills 失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    /**
     * P2-13: MCP {@code resources/read} 协议往返 · 对齐 CC mcpSkills.ts 内资源内容读取（E4 推断）。
     *
     * <p>connected 门控 + sendRequest('resources/read', {uri}) → 返回资源内容文本
     * （skill 资源为 markdown 文本，取 contents[0].text）。fail-soft 同 fetchResources。
     *
     * @param serverName MCP server 名
     * @param uri        资源 URI（须为 resources/list 产物）
     * @return 资源内容文本；未连接 / 协议失败 / 无 text 内容返回 null
     */
    public String readResource(String serverName, String uri) {
        McpTransport transport;
        try {
            // [impl-I-4 T1] 惰性重连（CLOSED → 重建）；未装配 → null fail-soft
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            log.warn("[McpToolPool] readResource 跳过未连接 server={}", serverName);
            return null;
        }
        try {
            // resources/read 请求：{ uri }（MCP 协议）
            JsonNode result = transport.sendRequest("resources/read", Map.of("uri", uri)).join();
            // 响应 result: { contents: [{ uri, mimeType, text }] } —— 取首个 content 的 text
            JsonNode contents = result.path("contents");
            if (contents.isArray() && contents.size() > 0) {
                JsonNode first = contents.get(0);
                if (first != null && first.has("text")) {
                    return first.path("text").asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[McpToolPool] readResource 失败 server={} uri={}: {}", serverName, uri, e.getMessage());
            return null;
        }
    }

    /**
     * MCP {@code resources/read} 完整内容往返 · 对齐 CC {@code ReadMcpResourceTool.ts:94-101}
     * （{@code connectedClient.client.request({method:'resources/read', params:{uri}}, ReadResourceResultSchema)}）。
     *
     * <p>区别于 {@link #readResource}（skills 用，仅取首个 content 的 text 字符串）：本方法返回
     * <b>完整 contents 数组</b>（每条含 uri/mimeType/text/blob 原始字段），供
     * {@link com.nexusai.application.agent.tool.impl.ReadMcpResourceTool} 做 blob 拦截
     * （base64 解码 + 落盘 + 替换为 blobSavedTo），对齐 CC ReadMcpResourceTool.ts:106-138。
     *
     * <p>前置门控（server 存在/已连接/有 resources 能力）由调用方（ReadMcpResourceTool）负责
     * （CC 3 前置，ReadMcpResourceTool.ts:78-92），本方法只做协议往返，不重复门控。
     *
     * <p>协议失败语义（WF-D 返工核验）：CC {@code ReadMcpResourceTool.ts:95-101} 对
     * {@code resources/read} 往返<b>无 try/catch → 抛错</b>（对比 {@code fetchResources}
     * {@code client.ts:2021-2027} 显式 catch → [] 的 fail-soft）。本方法协议往返失败同样<b>抛错</b>，
     * 错误经 {@link com.nexusai.application.agent.tool.impl.ReadMcpResourceTool#execute} 的
     * catch → {@code ToolResult.error}（Java Tool 契约等价于 CC throw→错误结果）。
     * transport 未装配（activeTransports 无此 server）为防御性空返回——工具路径已被调用方前置门控拦截。
     *
     * @param serverName MCP server 名
     * @param uri        资源 URI（须为 resources/list 产物）
     * @return resources/read 响应 {@code contents} 数组节点；transport 未装配返回空数组；协议失败抛异常
     */
    public List<JsonNode> readResourceContents(String serverName, String uri) {
        McpTransport transport = activeTransports.get(serverName);
        if (transport == null) {
            log.warn("[McpToolPool] readResourceContents 跳过未连接 server={}", serverName);
            return List.of();
        }
        try {
            // CC ReadMcpResourceTool.ts:95-101 resources/read 请求：{ uri }
            JsonNode result = transport.sendRequest("resources/read", Map.of("uri", uri)).join();
            JsonNode contents = result.path("contents");
            if (contents.isArray() && contents.size() > 0) {
                List<JsonNode> list = new ArrayList<>();
                for (JsonNode c : contents) {
                    list.add(c);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[McpToolPool] readResourceContents server={} uri={} 内容数={}", serverName, uri, list.size());
                }
                return list;
            }
            return List.of();
        } catch (Exception e) {
            // CC ReadMcpResourceTool.ts:95-101 无 fail-soft → 抛错（非返回空 contents）。
            // 对比 fetchResources client.ts:2021-2027 catch → []；Java 端经
            // ReadMcpResourceTool.execute catch → ToolResult.error，行为等价。
            log.warn("[McpToolPool] readResourceContents 协议失败 server={} uri={}: {}", serverName, uri, e.getMessage());
            throw new RuntimeException(
                "MCP resources/read failed for " + serverName + ":" + uri + " -> " + e.getMessage(), e);
        }
    }

    /**
     * P2-13: 单条 skill:// 资源 → skill Command · 对齐 CC createSkillCommand（loadSkillsDir.ts:270-401）。
     *
     * <p>frontmatter 经 {@code ParseSkillFrontmatter.parseFrontmatter} 拆分 → 16 字段
     * {@code parseSkillFrontmatterFields}（loadSkillsDir.ts:185-265）→ 22 入参
     * {@code createSkillCommand} 构建 25 属性 Command。
     *
     * @param serverName MCP server 名
     * @param resource   skill:// 资源（已过滤 uri 前缀）
     * @param content    resources/read 返回的 markdown 内容
     * @return skill Command；内容无 frontmatter/解析失败返回 null
     */
    private Command buildSkillCommand(String serverName, McpResource resource, String content) {
        ParseSkillFrontmatter.ParsedMarkdown parsed =
            new ParseSkillFrontmatter().parseFrontmatter(content, null);
        // skillName：资源 uri 去 skill:// 前缀后的段名（E4 推断，buildMcpToolName 规范化）
        String skillName = McpStringUtils.buildMcpToolName(serverName,
            resource.name() != null && !resource.name().isBlank()
                ? resource.name()
                : resource.uri().substring(SKILL_URI_SCHEME.length()));
        // record accessor（createSkillCommand/parseSkillFrontmatterFields 返回函数引用）→ 调用 SAM 方法
        SkillFrontmatterFields fields = McpSkillBuilders.get()
            .parseSkillFrontmatterFields()
            .parse(parsed.frontmatter(), parsed.content(), skillName, "Skill");
        return McpSkillBuilders.get()
            .createSkillCommand()
            .create(new CreateSkillCommand.Params(
            skillName,                      // CC: skillName
            fields.displayName(),           // CC: displayName
            fields.description(),           // CC: description
            fields.hasUserSpecifiedDescription(), // CC: hasUserSpecifiedDescription
            parsed.content(),               // CC: markdownContent（去除 frontmatter 后 body）
            fields.allowedTools(),          // CC: allowedTools
            fields.argumentHint(),          // CC: argumentHint
            fields.argumentNames(),         // CC: argumentNames
            fields.whenToUse(),             // CC: whenToUse
            fields.version(),               // CC: version
            fields.model(),                 // CC: model
            fields.disableModelInvocation(),// CC: disableModelInvocation
            fields.userInvocable(),         // CC: userInvocable
            CommandSource.MCP,              // CC: source='mcp'（command.ts:32 source 字段）
            null,                           // CC: baseDir=null（MCP 无本地 skill 目录，skillRoot null）
            CommandLoadedFrom.MCP,          // CC: loadedFrom='mcp'（loadSkillsDir.ts:73；getMcpSkillCommands 过滤键 commands.ts:554）
                                            //     P2-21：独立 loadedFrom 字段（旧 CommandSource.MCP 合一，M20 △）

            fields.hooks(),                 // CC: hooks
            fields.executionContext(),      // CC: executionContext（'fork' | undefined）
            fields.agent(),                 // CC: agent
            null,                           // CC: paths=undefined（MCP 无本地路径）
            fields.effort(),                // CC: effort
            fields.shell()                  // CC: shell
        ));
    }

    /** P1-17: 指定 server 的 capabilities（供 fetchResources/fetchCommands 能力门控 + P2-15 list_changed）。 */
    public Optional<JsonRpcMcpClient.Capabilities> getServerCapabilities(String serverName) {
        return Optional.ofNullable(serverCapabilities.get(serverName));
    }

    // ══════════════════ P2-15: list_changed 通知处理器（CC useManageMCPConnections.ts:618-751）══════════════════

    /**
     * tools/list_changed 通知处理器 · 对齐 CC useManageMCPConnections.ts:618-665
     * （{@code fetchToolsForClient.cache.delete(client.name)} :631 →
     * {@code updateServer({...client, tools: newTools})} :656 —— 全状态刷新，含 LLM 工具池）。
     *
     * @param serverName 通知来源 server（handler 注册时闭包捕获）
     */
    public void handleToolsListChanged(String serverName) {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 收到 tools/list_changed 通知 server={}，刷新 tools", serverName);
        }
        // CC :631 fetchToolsForClient.cache.delete(client.name)
        toolsCache.delete(serverName);
        // CC :655-656 fetchToolsForClient(client) + updateServer({...client, tools: newTools})
        List<McpToolEntry> newTools = fetchTools(serverName);
        serverTools.put(serverName, newTools);
        // S04 (B4): CC :656 updateServer({...client, tools: newTools}) → 刷新 LLM 工具池。
        // serverTools 是 activeServers()（ListMcpResourcesTool/ReadMcpResourceTool 的
        // 'Server not found' 判定源）的数据源，属另一注册表，保留；LLM 池 mcpTools 由
        // McpServerService.refreshMcpTools 经前缀组替换重建（镜像 promptPoolRefresher 模式）。
        toolsPoolRefresher.accept(serverName);
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] tools/list_changed 已刷新 LLM 工具池 server={} tools={}",
                serverName, newTools.size());
        }
    }

    /**
     * prompts/list_changed 通知处理器 · 对齐 CC useManageMCPConnections.ts:667-704。
     *
     * <p>CC 语义（自验）：{@code fetchCommandsForClient.cache.delete(client.name)}（:681）；
     * 注释「Skills come from resources, not prompts — don't invalidate their cache here」——
     * <b>不删 skills cache</b>，skills 走缓存命中（:684-685）；随后
     * {@code Promise.all([fetchCommandsForClient, fetchMcpSkillsForClient])}（:682-687）。
     * Java 侧 commandsCache 只存 prompts（fetchCommands 产物），skill 池 mcpSkillCommands
     * 独立维护，此处无需刷新。
     *
     * <p>P3-5: 末尾仍调 {@link #skillIndexClearer}（CC :694 {@code clearSkillIndexCache?.()}）——
     * CC prompts 处理器<b>不删 skills 缓存但调 clearSkillIndexCache</b>（updateServer 写入
     * commands 后索引可能引用旧快照，失效以防陈旧）。
     *
     * @param serverName 通知来源 server（handler 注册时闭包捕获）
     */
    public void handlePromptsListChanged(String serverName) {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 收到 prompts/list_changed 通知 server={}，刷新 prompts", serverName);
        }
        // CC :681 fetchCommandsForClient.cache.delete(client.name)
        commandsCache.delete(serverName);
        // CC :682-687 Promise.all([fetchCommandsForClient, feature('MCP_SKILLS') ? fetchMcpSkillsForClient : []])
        fetchCommands(serverName);
        if (mcpSkillsGate.getAsBoolean()) {
            // CC :684 注释「Skills come from resources, not prompts — don't invalidate their cache here」
            // → 不删 skillsCache；此处调用仅对齐 CC 的 Promise.all（缓存命中即返回，无副作用）
            fetchMcpSkills(serverName);
        }
        // 拍板#2: CC :688-691 updateServer({commands: [...mcpPrompts, ...mcpSkills]}) →
        // 刷新 prompt 命令池（fetchCommands 产物落库 mcpPromptCommands；skill 池走缓存命中）
        promptPoolRefresher.accept(serverName);
        // P3-5: CC :694 clearSkillIndexCache?.() → 使 skill-search 索引失效（默认 no-op）
        skillIndexClearer.run();
    }

    /**
     * resources/list_changed 通知处理器 · 对齐 CC useManageMCPConnections.ts:706-751。
     *
     * <p>CC 语义（自验）：
     * <ul>
     *   <li>:717 {@code fetchResourcesForClient.cache.delete(client.name)}</li>
     *   <li>MCP_SKILLS 分支（:718-738）：:723/:724 删 skills + commands cache（skills 从
     *       resources 发现，一并刷新）；:727-730 三路并行 refetch；:731-735 updateServer
     *       resources + commands；:738 clearSkillIndexCache</li>
     *   <li>else 分支（:740-741）仅 refetch resources</li>
     * </ul>
     * clearSkillIndexCache（EXPERIMENTAL_SKILL_SEARCH=false 生产折叠）Java 侧双路落地：
     * <ul>
     *   <li>skill 池刷新 → {@link #skillPoolRefresher}（McpServerService.refreshMcpSkillCommands
     *       重建 mcpSkillCommands，保证下一轮 skill 发现用新集合）</li>
     *   <li>skill-search 索引失效 → {@link #skillIndexClearer}（P3-5，镜像 CC :738
     *       {@code clearSkillIndexCache?.()}；默认 no-op，concern #30 子系统范围外）</li>
     * </ul>
     *
     * @param serverName 通知来源 server（handler 注册时闭包捕获）
     */
    public void handleResourcesListChanged(String serverName) {
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 收到 resources/list_changed 通知 server={}，刷新 resources", serverName);
        }
        // CC :717 fetchResourcesForClient.cache.delete(client.name)
        resourcesCache.delete(serverName);
        if (mcpSkillsGate.getAsBoolean()) {
            // CC :723/:724 删 skills + commands cache（skills 从 resources 发现，一并刷新）
            skillsCache.delete(serverName);
            commandsCache.delete(serverName);
            // CC :727-730 Promise.all([fetchResourcesForClient, fetchCommandsForClient, fetchMcpSkillsForClient])
            fetchResources(serverName);
            fetchCommands(serverName);
            fetchMcpSkills(serverName);
            // CC :731-738 updateServer({resources, commands}) → clearSkillIndexCache?.()
            skillPoolRefresher.accept(serverName);
            // P3-5: CC :738 clearSkillIndexCache?.() → 使 skill-search 索引失效（默认 no-op）
            skillIndexClearer.run();
        } else {
            // CC :740-741 else 仅 refetch resources
            fetchResources(serverName);
        }
    }

    /**
     * P2-15: 拉取 MCP server 的 tools/list · 对齐 CC {@code client.ts:1743-1806 fetchToolsForClient}
     * （memoizeWithLRU，能力门控 + fail-soft）。本方法只做协议往返与缓存，不触发
     * ToolRegistry 注册（assemble 路径已注册）。
     *
     * @param serverName MCP server 名
     * @return 该 server 的工具 entry 列表；未连接 / 无 tools 能力 / 协议失败返回空 list
     */
    public List<McpToolEntry> fetchTools(String serverName) {
        List<McpToolEntry> cached = toolsCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        List<McpToolEntry> result = doFetchTools(serverName);
        toolsCache.put(serverName, result);
        return result;
    }

    /** P2-15: fetchTools 的协议往返本体（未命中缓存时执行，CC client.ts:1743-1806）。 */
    private List<McpToolEntry> doFetchTools(String serverName) {
        // CC :1745 client.type !== 'connected' → []（[impl-I-4 T1] 惰性重连；未装配 → [] fail-soft）
        McpTransport transport;
        try {
            transport = ensureConnectedClient(serverName, null);
        } catch (Exception e) {
            log.warn("[McpToolPool] fetchTools 跳过未连接 server={}", serverName);
            return List.of();
        }
        // CC :1748 无 tools 能力 → []
        JsonRpcMcpClient.Capabilities caps = serverCapabilities.get(serverName);
        if (caps == null || !caps.toolsList()) {
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchTools 跳过无 tools 能力 server={}", serverName);
            }
            return List.of();
        }
        try {
            // CC :1752 tools/list 往返（对齐 assembleToolPool 的 tools/list 解析）
            JsonNode listResult = transport.sendRequest("tools/list", Map.of()).join();
            JsonNode toolsNode = listResult.path("tools");

            // FIX-A1: CC client.ts:1758 recursivelySanitizeUnicode(result.tools) —— tools/list 产物
            // 在解析为 Tool 前递归清洗（含对象键：inputSchema 属性名为攻击者可控，sanitization.ts:71-91）。
            sanitizeUnicodeInPlace(toolsNode);

            List<McpToolEntry> entries = new ArrayList<>();
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.path("name").asText("");
                    if (toolName.isEmpty()) continue;
                    // DEL-MCP-4: 工具名唯一规范化路径（CC client.ts:1768 fullyQualifiedName=buildMcpToolName(client.name,tool.name)）
                    // [impl-I-4 T9] 裸拼接 → McpStringUtils.buildMcpToolName（对齐 CC mcpStringUtils.ts:50-52）
                    String mcpToolName = McpStringUtils.buildMcpToolName(serverName, toolName);
                    JsonNode inputSchema = toolNode.path("inputSchema");
                    Tool tool = wrapMcpTool(serverName, toolName, mcpToolName, inputSchema,
                        toolNode.path("annotations"), toolNode.path("_meta"), toolNode.path("description").asText(null));
                    entries.add(new McpToolEntry(serverName, toolName, mcpToolName, inputSchema, tool));
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpToolPool] fetchTools server={} tools={}", serverName, entries.size());
            }
            return entries;
        } catch (Exception e) {
            // CC fetchToolsForClient fail-soft（catch → []）
            log.warn("[McpToolPool] fetchTools 失败 server={}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    // ══════════════════ P2-15: 缓存失效（CC client.ts:1389-1396/1666-1672）══════════════════

    /**
     * 清单个 server 的全部 fetch 缓存 · 对齐 CC onclose/clearServerCache 的
     * {@code fetchToolsForClient.cache.delete + fetchResourcesForClient.cache.delete +
     * fetchCommandsForClient.cache.delete + fetchMcpSkillsForClient.cache.delete}
     * （client.ts:1389-1394 / :1667-1671）。
     *
     * @param serverName MCP server 名
     */
    public void invalidateFetchCaches(String serverName) {
        toolsCache.delete(serverName);
        resourcesCache.delete(serverName);
        commandsCache.delete(serverName);
        skillsCache.delete(serverName);
        if (log.isDebugEnabled()) {
            log.debug("[McpToolPool] 失效 fetch 缓存 server={}", serverName);
        }
    }

    /**
     * 失效单个 server 的 fetch 缓存 + 装配状态 · 对齐 CC 断开/清缓存全量清理语义。
     * teardown 末尾调用（断开清缓存，重连不读旧快照）。
     *
     * @param serverName MCP server 名
     */
    public void invalidateServer(String serverName) {
        invalidateFetchCaches(serverName);
        serverTools.remove(serverName);
        serverCapabilities.remove(serverName);
        serverInstructions.remove(serverName);
    }

    // ────────────── 测试观察点（McpListChangedNotificationTest 验证缓存失效）──────────────

    /** P2-15 测试观察点：resources fetch 缓存。 */
    McpFetchCache<List<McpResource>> resourcesCache() {
        return resourcesCache;
    }

    /** P2-15 测试观察点：tools fetch 缓存。 */
    McpFetchCache<List<McpToolEntry>> toolsCache() {
        return toolsCache;
    }

    // ────────────── 内部: 把 MCP tool 包成 Tool 接口 ──────────────

    /**
     * 把 MCP tool 包成 Tool 接口 · 对齐 CC fetchToolsForClient（client.ts:1743-1806）逐字段
     * 透传 annotations/_meta/description，供 McpServerTool 做 CC 语义映射（F1-F4）。
     *
     * @param annotations tools/list 返回的 {@code annotations} 节点 · CC original: tool.annotations
     * @param meta        tools/list 返回的 {@code _meta} 节点 · CC original: tool._meta
     * @param description tools/list 返回的 {@code description} · CC original: tool.description
     */
    private Tool wrapMcpTool(String serverName, String toolName, String mcpToolName,
                             JsonNode inputSchema, JsonNode annotations, JsonNode meta, String description) {
        // [RES-07d] server base URL 承载于 TransportConfig.command（远程 http/sse/ws ·
        // CC metadata.ts:102-116 语义）· 供 isOfficialMcpUrl 判定（official URL → telemetry
        // 保留真实工具名）；stdio/sdk → command 为可执行名 → normalizeOfficial → null → 非官方 fail-closed。
        McpTransport.TransportConfig cfg = serverConfigs.get(serverName);
        String serverUrl = cfg != null ? cfg.command() : null;
        return new McpServerTool(serverName, toolName, mcpToolName, inputSchema,
            annotations, meta, description, serverUrl, this);
    }
}
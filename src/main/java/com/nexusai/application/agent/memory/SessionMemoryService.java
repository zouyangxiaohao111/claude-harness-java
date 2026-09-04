package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.compact.BoundaryReader;
import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.application.agent.compact.CompactSettingsResolver;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactHooks;
import com.nexusai.application.agent.compact.CompactSummary;
import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.PartialCompactConversation;
import com.nexusai.application.agent.compact.PlanProvider;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.compact.Tokens;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.MemoryRemoteModeConfig;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptSectionCache;
import com.nexusai.application.agent.session.SessionMemoryPrompts;
import com.nexusai.application.agent.subagent.createSubagentContext;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Session Memory 服务 · 对齐 CC services/SessionMemory/sessionMemory.ts +
 * services/compact/sessionMemoryCompact.ts.
 *
 * <p><b>P1-3 重建</b>: 旧版为简化教学实现（getOrCreate 缓存 + compact() 阈值误用 + messageCount
 * 制），DEL-M-09/10/11 删除；本类重建为 CC token 阈值状态机提取管线 + SM 压缩 messages-to-keep 语义：
 * <ul>
 *   <li><b>提取管线</b>（sessionMemory.ts:272-350）: repl_main_thread 门控（INV-9）→
 *       shouldExtractMemory token/tool 阈值状态机 → runForkedAgent 提取 →
 *       lastSummarizedMessageId 安全更新</li>
 *   <li><b>SM 压缩</b>（sessionMemoryCompact.ts:514-630）: waitForSessionMemoryExtraction →
 *       calculateMessagesToKeepIndex + adjustIndexToPreserveAPIInvariants（tool_use/tool_result +
 *       thinking 不拆）→ truncateSessionMemoryForCompact + 复用 CompactSummary.buildUserMessage</li>
 *   <li><b>读错误语义</b>（sessionMemoryUtils.ts:110-126）: NoSuchFileException→null，
 *       else re-throw（isFsInaccessible）</li>
 * </ul>
 *
 * <p><b>模块态归属</b>: config/阈值谓词/lastSummarizedMessageId/抽取状态已迁移到
 * {@link SessionMemoryUtils}（对齐 CC sessionMemoryUtils.ts 模块级状态），本类静态访问器委托。
 *
 * <p><b>[sm-cursor-sessionize 2026-08-30]</b>: CC 单会话的模块级游标
 * （lastSummarizedMessageId / extractionStartedAt / tokensAtLastExtraction /
 * sessionMemoryInitialized / lastMemoryMessageUuid）已全部按 sessionId 键控
 * （ConcurrentHashMap）——Web 多会话后端下 A 的游标不再被 B 读到/清空（对齐项目铁律
 * 「multi-session-vs-cc-single-session」）。EXTRACTION_LOCK 保持全局（CC sequential 全局队列）。
 */
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /** 0o700 目录权限 · CC sessionMemory.ts:190 mkdir mode（仅属主可读写）。 */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIR =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /** 0o600 文件权限 · CC sessionMemory.ts:196-206 writeFile mode（仅属主可读写）。 */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /**
     * 当前文件系统是否支持 POSIX 权限视图（Windows 不支持 → 权限位 no-op，与 CC Windows
     * 忽略 mode 同语义）。对齐 BundledSkillFileExtractor.POSIX_ENABLED 惯例。
     */
    private static final boolean POSIX_ENABLED;

    static {
        boolean posix = false;
        try {
            posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        } catch (Exception e) {
            // Windows/受限环境：POSIX 视图不可用，权限位 no-op
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] POSIX 权限视图不可用，0o700/0o600 权限位 no-op: {}",
                    e.getMessage());
            }
        }
        POSIX_ENABLED = posix;
    }

    private final Path baseDir;

    /**
     * per-session 会话根解析器 · sessionId → {@code {configHome}/projects/{slug}}（对齐 CC
     * sessionStorage.ts:202-205 getProjectDir(getOriginalCwd()) 分层，summary 与 transcript 同源）。
     *
     * <p><b>[sm-reloc]</b> 生产注入 {@code SessionStorage::sessionProjectDir}（按 sessionId 查
     * SessionCwdHolder/SessionProjectRoot 全局 map —— hook/压缩线程上也不依赖 ThreadLocal）；
     * null = legacy 固定 {@link #baseDir}（1-arg 构造 / 测试回落）。resolver 非 null 时目录由
     * {@code setupSessionMemoryFile} 惰性创建（构造器不 eager mkdir，防启动期污染 + bean hermetic）。</p>
     */
    private final Function<String, Path> sessionBaseDirResolver;

    /** env 读取器 · 可注入便于测试（默认 System::getenv）。SM 压缩门控读取用。 */
    private Function<String, String> envProvider = System::getenv;

    /** SM 压缩特性门控 ① · 对齐 CC {@code tengu_session_memory} flag（sessionMemoryCompact.ts:412-415）。 */
    private boolean smSessionMemoryEnabled;

    /** SM 压缩特性门控 ② · 对齐 CC {@code tengu_sm_compact} flag（sessionMemoryCompact.ts:416-419）。 */
    private boolean smCompactEnabled;

    /**
     * 压缩配置 DB 实时读源 · [V52 B1-6] @Autowired(required=false)：null = 未接线 → 回落
     * {@link #smSessionMemoryEnabled}/{@link #smCompactEnabled} 注入值，零行为变化。
     */
    private CompactSettingsResolver settingsResolver;

    /**
     * SM 提取特性门控 · 对齐 CC {@code tengu_session_memory}（sessionMemory.ts:80-82）。
     * 部署标志等价（OPD-M-52 模式），缺省关闭（CC GrowthBook 缺省 false）。
     *
     * <p><b>[SM-DB-gate]</b> 仅作<b>回落源</b>：提取门控实时解析 {@link #resolveSessionMemoryFeatureEnabled()}
     * 优先 DB settings.sm_session_memory_enabled（有值覆盖本字段，null 回落本值）。
     * 生产装配 = env NEXUSAI_SESSION_MEMORY / featureFlags.smSessionMemory 同源
     * （ToolRegistrationConfig:1408-1412）。</p>
     */
    private boolean sessionMemoryFeatureEnabled;

    /** fork 查询 seam · 生产注入 ProductionForkedQuery（ToolRegistrationConfig）。 */
    private volatile RunForkedAgent.ForkedQuery forkedQuery;

    /** cache-safe params 供应器 · fork 消息前缀 + 主线程工具集。 */
    private volatile Supplier<CacheSafeParams> cacheSafeParamsSupplier;

    /**
     * firstParty fork 缓存共享 gate 供应 · 兜底（RES-C5）CacheSafeParams 的
     * {@code useGlobalCacheScope} 来源 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)；由接线方经 GlobalCacheScope 单实现求值注入
     * （REQ-C5-4）；默认 false = Java 3P 默认（boundary 不插入）。
     */
    private volatile Supplier<Boolean> useGlobalCacheScopeSupplier = () -> false;

    /**
     * [IMP-CM-04] plan 文件提供者（plan_file_reference 附件数据源）· CC original:
     * getPlan/getPlanFilePath（plans.ts:119-145）+ createPlanAttachmentIfNeeded（compact.ts:1470-1486）。
     * SM 压缩结果构造注入 plan_file_reference（CC sessionMemoryCompact.ts:484-485）——传统路径
     * CompactConversation:303 populatePlanAttachment 用 typed state.attachments() 通道，SM 路径无
     * registry 访问 → 经本 seam 读磁盘 plan 注入 CompactionResult.attachments。未注入 → 按 sessionId
     * 回落构造 {@link PlanProviderImpl}（对齐 PostCompactAttachmentRestorer.resolvePlanProvider）。
     */
    private volatile PlanProvider planProvider;


    /** hook 已注册标记 · 防止 @PostConstruct 重复触发时重复注册（PostSamplingHookRegistry 是静态表）。 */
    private volatile boolean hookRegistered = false;

    /** 遥测注入 · null → 不发射（对齐 CC logEvent 可空上下文）。 */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    /**
     * isAutoCompactEnabled 供应器 · CC original: {@code isAutoCompactEnabled()}
     * （sessionMemory.ts:360，compact/autoCompact.ts:147-158）。
     *
     * <p>initSessionMemory 门控用（CC :369-371：autoCompact 未启用 → 不注册 hook）。
     * 生产由 ToolRegistrationConfig 注入 {@code autoCompactor::isAutoCompactEnabled}
     * （ObjectProvider 懒解析，避免 autoCompactor↔sessionMemoryService 循环依赖）；
     * null → 默认 true（不门控，向后兼容测试直构）。
     */
    private volatile Supplier<Boolean> autoCompactEnabledSupplier;

    /**
     * [SM-05] 提取互斥锁 · CC original: {@code sequential()} 包装（sessionMemory.ts:272 +
     * utils/sequential.ts:19-56，FIFO 队列逐次执行）。公平锁 = 先到先得（CC 队列语义），
     * 相邻轮次提取不并发（双 fork 同写 summary.md / extractionStartedAt 覆盖防护，DRIFT-3）。
     * static：hook 单实例注册，跨实例共享（CC 模块级 queue）。
     */
    private static final ReentrantLock EXTRACTION_LOCK = new ReentrantLock(true);

    /**
     * [SM-08] gate_disabled 一次性守卫 · CC original: {@code hasLoggedGateFailure}
     * （sessionMemory.ts:286-288，ant-only 且每 session 一次）。
     */
    private static volatile boolean hasLoggedGateFailure = false;

    /**
     * [G-41] 读取通道 · CC original: {@code FileReadTool.call}（sessionMemory.ts:217-226）——
     * SM 文件读取经权限层（PathGuard/deny 规则/dedup/CRLF 归一化），非 Files.readString 直读。
     * 生产由 ToolRegistrationConfig 注入；null → setupSessionMemoryFile fail-loud（无直读降级）。
     */
    private volatile ReadFileTool readFileTool;

    /**
     * [SM-02] SessionStart hooks 执行器 · CC original: {@code processSessionStartHooks('compact',
     * {model})}（sessionMemoryCompact.ts:583-586）。null → hookResults 空（等价 CC 无 hook 注册）。
     */
    private volatile HookRegistry sessionStartHookRegistry;

    /**
     * [SM-02] 主循环模型（hook input data.model）· CC original: {@code getMainLoopModel()}
     * （sessionMemoryCompact.ts:585）。生产由 ToolRegistrationConfig 注入（fork modelSupplier
     * 同源）；null → hook input 不含 model（HookEvent 容 null）。
     */
    private volatile Supplier<String> mainLoopModelSupplier = () -> null;

    /**
     * [SM-08/G-48] USER_TYPE==='ant' 判定 · CC original: {@code process.env.USER_TYPE === 'ant'}
     * （sessionMemory.ts:286/:363）。Java 无 USER_TYPE env 对应 → 用 {@code NEXUSAI_USER_TYPE}
     * 近似建模（同 ExtractMemoriesAgent:153 模式）。测试可经 {@link #setUserTypeIsAnt} 注入。
     */
    private volatile BooleanSupplier userTypeIsAnt = () -> "ant".equals(resolveEnv("NEXUSAI_USER_TYPE"));

    /** [SM-08] env 读取（测试可经 {@link #setEnvProvider} 覆盖）。 */
    private static String resolveEnv(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v;
    }

    /** [G-41] FileReadTool 调用构造用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 上次抽取消息 uuid · CC original: lastMemoryMessageUuid（sessionMemory.ts:99）模块态。
     * <b>[sm-cursor-sessionize 2026-08-30] 按 sessionId 键控</b>——CC 单会话 let 变量在 Web
     * 多会话后端会成为跨会话串扰源（A 的 lastMemoryMessageUuid 被 B 的 shouldExtractMemory
     * 读到 → 工具调用计数失真）。对齐项目铁律「multi-session-vs-cc-single-session」。
     */
    private static final Map<String, String> lastMemoryMessageUuidBySession = new ConcurrentHashMap<>();

    /** 会话态游标 null-safe key（null sessionId → "unknown"，与 {@link #sessionIdFrom} 同兜底）。 */
    private static String cursorKey(String sessionId) {
        return sessionId != null ? sessionId : "unknown";
    }

    /**
     * 获取本会话 lastMemoryMessageUuid · CC original: lastMemoryMessageUuid（sessionMemory.ts:99）
     * 模块态。null = 尚无上次抽取消息（首次/复位）。
     */
    public static String getLastMemoryMessageUuid(String sessionId) {
        return lastMemoryMessageUuidBySession.get(cursorKey(sessionId));
    }

    /**
     * 设置本会话 lastMemoryMessageUuid · CC original: lastMemoryMessageUuid（sessionMemory.ts:99）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @param uuid      上次抽取消息 uuid；null = 复位
     */
    public static void setLastMemoryMessageUuid(String sessionId, String uuid) {
        String key = cursorKey(sessionId);
        // ConcurrentHashMap 不允许 null value → null（undefined）语义用 remove 表达（absent = undefined）
        if (uuid == null) {
            lastMemoryMessageUuidBySession.remove(key);
        } else {
            lastMemoryMessageUuidBySession.put(key, uuid);
        }
    }

    private final SessionMemoryPrompts prompts = new SessionMemoryPrompts();

    /** token 估算器（CC estimateMessageTokens block 口径 · microCompact.ts:164）。 */
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    /** SM 压缩配置（默认对齐 CC DEFAULT_SM_COMPACT_CONFIG，sessionMemoryCompact.ts:57-61）。 */
    private SmCompactConfig smCompactConfig = SmCompactConfig.DEFAULT;

    /**
     * SM 压缩配置 · CC original: {@code SessionMemoryCompactConfig}
     * （sessionMemoryCompact.ts:47-54）+ DEFAULT_SM_COMPACT_CONFIG（:57-61）。
     */
    public record SmCompactConfig(int minTokens, int minTextBlockMessages, int maxTokens) {
        public static final SmCompactConfig DEFAULT = new SmCompactConfig(10_000, 5, 40_000);
    }

    /**
     * 1-arg 构造（legacy）· 委托 2-arg，resolver=null → 固定 {@code baseDir} 平铺语义
     * （测试 / 回落，目录构造期 createDirectories 行为不变）。
     */
    public SessionMemoryService(Path baseDir) {
        this(baseDir, null);
    }

    /**
     * 2-arg 主构造 · {@code resolver} 非 null = per-session 派生（生产注入
     * {@code SessionStorage::sessionProjectDir}）。
     *
     * <p><b>[sm-reloc] 目录创建语义</b>：{@code resolver == null}（legacy）→ 构造期
     * {@code createDirectories(baseDir)}（原 1-arg 行为保留）；{@code resolver != null} →
     * <b>不</b> eager mkdir —— per-session 目录由 {@code setupSessionMemoryFile} 惰性建
     * （{@code createDirectoriesOwnerOnly}），防启动期污染 + bean hermetic。</p>
     *
     * @param baseDir  legacy 固定基目录（resolver null 时生效；非 null）
     * @param resolver sessionId → {configHome}/projects/{slug}；可 null = legacy
     */
    public SessionMemoryService(Path baseDir, Function<String, Path> resolver) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.sessionBaseDirResolver = resolver;
        if (resolver == null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                log.warn("[SessionMemory] 无法创建 session memory 目录: {}", baseDir, e);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // lastSummarizedMessageId 静态访问器 · 委托 SessionMemoryUtils 模块态
    // ════════════════════════════════════════════════════════════════════

    /**
     * 获取本会话 lastSummarizedMessageId · 对齐 CC sessionMemoryUtils.ts:58
     * {@code getLastSummarizedMessageId()}（sessionMemoryCompact.ts:529 消费）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @return 摘要到哪条消息为止；null = 尚未摘要（undefined）
     */
    public static String getLastSummarizedMessageId(String sessionId) {
        return SessionMemoryUtils.getLastSummarizedMessageId(sessionId);
    }

    /**
     * 设置本会话 lastSummarizedMessageId · 对齐 CC sessionMemoryUtils.ts:65
     * {@code setLastSummarizedMessageId(messageId)}。
     *
     * <p><b>压缩成功路径必须置 undefined（null）</b>（INV-8 / REQ-12）—— CC
     * autoCompact.ts:296/325、commands/compact/compact.ts:112 在压缩成功后调用。
     * <b>[sm-cursor-sessionize]</b>：只清本会话游标，不再跨会话清空（旧 static volatile 语义
     * 会让 A 的压缩成功清掉 B 的 lastSummarizedMessageId → B 的 SM 提取时机错乱）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @param messageId 摘要到该 messageId；null = 复位（undefined）
     */
    public static void setLastSummarizedMessageId(String sessionId, String messageId) {
        SessionMemoryUtils.setLastSummarizedMessageId(sessionId, messageId);
    }

    /**
     * 重置 lastMemoryMessageUuid（测试用）· 对齐 CC {@code resetLastMemoryMessageUuid}
     * （sessionMemory.ts:104-106）。<b>[sm-cursor-sessionize]</b>：清空全部会话游标
     * （测试钩子语义 = 全局复位）。
     */
    public static void resetLastMemoryMessageUuid() {
        lastMemoryMessageUuidBySession.clear();
    }

    // ════════════════════════════════════════════════════════════════════
    // 注入器（门控 / fork seam）
    // ════════════════════════════════════════════════════════════════════

    /** 注入 env 读取器（测试用）。 */
    public void setEnvProvider(Function<String, String> envProvider) {
        this.envProvider = envProvider != null ? envProvider : System::getenv;
    }

    /**
     * 注入 SM 压缩特性门控 ①（对齐 CC tengu_session_memory · sessionMemoryCompact.ts:412-415）。
     * 与 {@link #setSmCompactEnabled} 作 AND 决定 shouldUseSessionMemoryCompaction。
     */
    public void setSmSessionMemoryEnabled(boolean enabled) {
        this.smSessionMemoryEnabled = enabled;
    }

    /**
     * 注入 SM 压缩特性门控 ②（对齐 CC tengu_sm_compact · sessionMemoryCompact.ts:416-419）。
     * 与 {@link #setSmSessionMemoryEnabled} 作 AND 决定 shouldUseSessionMemoryCompaction。
     */
    public void setSmCompactEnabled(boolean enabled) {
        this.smCompactEnabled = enabled;
    }

    /**
     * 注入压缩配置 DB 实时读源 · [V52 B1-6] @Autowired(required=false)，
     * 同 {@link CompactThresholdSystem#setSettingsMapper(SettingsMapper)} 回落语义（可 null）。
     * DB {@code settings.sm_session_memory_enabled / sm_compact_enabled} 有值覆盖
     * {@link #setSmSessionMemoryEnabled}/{@link #setSmCompactEnabled} 注入值（null 回落）。
     *
     * <p><b>[V52 token-compact-settings-fix]</b> 追加把读源同步进 {@link SessionMemoryUtils} 静态槽位
     * （{@link SessionMemoryUtils#setSettingsResolver}），使提取阈值谓词（DB &gt; 内存）在
     * SessionMemoryUtils 静态模块态读取点生效（生产接线 ToolRegistrationConfig:1411）。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
        SessionMemoryUtils.setSettingsResolver(settingsResolver);
    }

    /**
     * 注入 SM 提取特性门控（对齐 CC tengu_session_memory 部署标志等价）。
     * <b>[SM-DB-gate]</b> 仅作回落源：{@link #resolveSessionMemoryFeatureEnabled()} 优先 DB
     * settings.sm_session_memory_enabled（有值覆盖本注入值，null 回落）。
     */
    public void setSessionMemoryFeatureEnabled(boolean enabled) {
        this.sessionMemoryFeatureEnabled = enabled;
    }

    /**
     * 注入 fork 查询 seam · 生产接 {@link RunForkedAgent.ForkedQuery}（ProductionForkedQuery，
     * ToolRegistrationConfig）。未注入 → extractSessionMemory 显式日志跳过（plan risks）。
     */
    public void setForkedQuery(RunForkedAgent.ForkedQuery query) {
        this.forkedQuery = query;
    }

    /** 注入 cache-safe params 供应器（fork 消息前缀 + 主线程工具集）。 */
    public void setCacheSafeParamsSupplier(Supplier<CacheSafeParams> supplier) {
        this.cacheSafeParamsSupplier = supplier;
    }

    /**
     * 注入 firstParty fork 缓存共享 gate 供应（RES-C5）· 兜底 CacheSafeParams 的
     * useGlobalCacheScope 来源 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)；由接线方经 {@code GlobalCacheScope.shouldUseGlobalCacheScope(config)}
     * 单实现求值注入；null → 保持默认 false（3P 默认）。
     */
    public void setUseGlobalCacheScopeSupplier(Supplier<Boolean> supplier) {
        if (supplier != null) {
            this.useGlobalCacheScopeSupplier = supplier;
        }
    }

    /** 注入 SM 压缩配置（测试用）。 */
    public void setSmCompactConfig(SmCompactConfig config) {
        this.smCompactConfig = config != null ? config : SmCompactConfig.DEFAULT;
    }

    /**
     * 获取当前 SM 压缩配置（副本）· Web 调参通道（IMP-CM-35）读端：GET 返回运行期生效值
     * （CC original: {@code getSessionMemoryCompactConfig()} sessionMemoryCompact.ts:86-88，
     * 返回 {@code {...smCompactConfig}} 副本）。
     *
     * @return 当前生效的 SM 压缩配置（永不 null，未注入恒 DEFAULT）
     */
    public SmCompactConfig getSmCompactConfig() {
        return smCompactConfig;
    }

    /** 注入遥测（tengu_session_memory_loaded · sessionMemoryUtils.ts:117）。 */
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * 注入 isAutoCompactEnabled 供应器（FIX-SM · initSessionMemory 门控）。
     * 生产由 ToolRegistrationConfig 注入；null → 默认 true（不门控）。
     */
    public void setAutoCompactEnabledSupplier(Supplier<Boolean> autoCompactEnabledSupplier) {
        this.autoCompactEnabledSupplier = autoCompactEnabledSupplier;
    }

    /**
     * [G-41] 注入读取通道 · CC original: FileReadTool.call（sessionMemory.ts:217-226）。
     * null 保留 → setupSessionMemoryFile fail-loud（无 Files.readString 降级）。
     */
    public void setReadFileTool(ReadFileTool readFileTool) {
        this.readFileTool = readFileTool;
    }

    /**
     * [IMP-CM-04] 注入 plan 文件提供者（plan_file_reference 数据源 · CC plans.ts）。
     * 生产由 ToolRegistrationConfig 注入；未注入 → SM 路径按 sessionId 回落构造
     * {@link PlanProviderImpl}（默认 plans 目录，对齐 PostCompactAttachmentRestorer.resolvePlanProvider）。
     */
    public void setPlanProvider(PlanProvider planProvider) {
        this.planProvider = planProvider;
    }

    /**
     * [SM-02] 注入 SessionStart hooks 执行器 · CC original: processSessionStartHooks('compact',
     * {model})（sessionMemoryCompact.ts:583-586）。null → hookResults 空。
     */
    public void setSessionStartHookRegistry(HookRegistry registry) {
        this.sessionStartHookRegistry = registry;
    }

    /**
     * [SM-02] 注入主循环模型供应器（hook input data.model · CC getMainLoopModel()）。
     * null → 保持默认 null（hook input 不含 model）。
     */
    public void setMainLoopModelSupplier(Supplier<String> supplier) {
        if (supplier != null) {
            this.mainLoopModelSupplier = supplier;
        }
    }

    /**
     * [SM-08/G-48] 注入 USER_TYPE==='ant' 判定（null → false，对齐 CC extractMemories.ts:537
     * 恒 ant 判定关闭语义）。
     */
    public void setUserTypeIsAnt(BooleanSupplier ant) {
        this.userTypeIsAnt = ant != null ? ant : () -> false;
    }

    /** [SM-08] 测试辅助：重置 gate_disabled 一次性守卫（防跨测试污染）。 */
    static void resetGateDisabledLogging() {
        hasLoggedGateFailure = false;
    }

    /** 发射遥测事件（recordEvent in-memory + logOTelEvent）· null telemetry → 静默跳过。 */
    private void emitTelemetry(String event, java.util.Map<String, Object> attrs) {
        if (telemetry != null) {
            telemetry.recordEvent(event, attrs);
            telemetry.logOTelEvent(event, attrs);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // initSessionMemory · 对齐 CC sessionMemory.ts:357-375（注册 post-sampling hook）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 启动 hook · 对齐 CC {@code initSessionMemory()}（sessionMemory.ts:357-375）：
     * 注册 post-sampling hook（注册后门控/阈值 lazy 判定，CC :373-374 "Register hook
     * unconditionally - gate check happens lazily when hook runs"）。
     *
     * <p><b>[FIX-SM] isAutoCompactEnabled 门控（CC :360-371）</b>：Session memory 服务于压缩，
     * 必须尊重 auto-compact 设置 —— {@code autoCompactEnabled=false} → 不注册 hook。
     * 此前 Java 无条件注册（NOT_ALIGNED），现经 {@link #autoCompactEnabledSupplier} 门控
     * （生产 = ToolRegistrationConfig 注入 autoCompactor::isAutoCompactEnabled）。
     *
     * <p><b>WHY @PostConstruct</b>: Spring bean 装配完成后立即注册，模拟 CC 启动注册行为
     * （MagicDocsService:132-156 同模式）。hook 执行点已存在——LlmAgentLoop:4133-4157
     * 每轮 sampling 后 PostSamplingHookRegistry.executeAll（[IMP-HOOKS-S7 D12] 行号随
     * loop 重构修正为实测调用点，旧引用已失效）。
     */
    @PostConstruct
    public void initSessionMemory() {
        if (hookRegistered) {
            return;
        }
        // [SM-09] bareMode 接线门控（GAP-5）· CC setup.ts:293-294 `if (!isBareMode()) {
        //   initSessionMemory() }` —— bare 模式（CLAUDE_CODE_SIMPLE）下 CC 根本不调用
        //   initSessionMemory（含 init 事件在内整体跳过），Java @PostConstruct 等价早退。
        if (MemoryBareModeConfig.isBareMode()) {
            log.info("[SessionMemory] bare 模式（CLAUDE_CODE_SIMPLE）→ 不注册 PostSamplingHook"
                + "（CC setup.ts:293-294）");
            return;
        }
        // [IMP-CM-19] remote mode 跳过 · 对齐 CC sessionMemory.ts:358 `if (getIsRemoteMode())
        //   return`（STATE.isRemoteMode 默认 false :390，main.tsx:3328/:3447 setIsRemoteMode 置位）。
        //   bareMode 为 setup.ts 调用点级门（Java 已内联于上），remote 为 initSessionMemory
        //   内部首门（CC :358 位于 isAutoCompactEnabled :360 之前）。
        if (MemoryRemoteModeConfig.isRemoteMode()) {
            log.info("[SessionMemory] remote mode（nexusai.memory.remote-mode）→ 不注册 "
                + "PostSamplingHook（CC sessionMemory.ts:358）");
            return;
        }
        boolean autoCompactEnabled = autoCompactEnabledSupplier != null
            ? autoCompactEnabledSupplier.get() : true;
        // [G-48] init 事件 ant-only（DRIFT-15）· CC sessionMemory.ts:363-367
        //   `if (process.env.USER_TYPE === 'ant') logEvent(...)` —— 非 ant 不发射。
        if (userTypeIsAnt.getAsBoolean()) {
            emitTelemetry("tengu_session_memory_init",
                java.util.Map.of("auto_compact_enabled", autoCompactEnabled));
        }
        log.info("[SessionMemory] initSessionMemory: isAutoCompactEnabled={}（CC sessionMemory.ts:360-371）",
            autoCompactEnabled);
        if (!autoCompactEnabled) {
            log.info("[SessionMemory] isAutoCompactEnabled=false → 不注册 PostSamplingHook"
                + "（CC sessionMemory.ts:369-371）");
            return;
        }
        PostSamplingHookRegistry.register(this::onPostSampling);
        hookRegistered = true;
        log.info("[SessionMemory] 已注册 PostSamplingHook · 等价 CC initSessionMemory "
            + "registerPostSamplingHook(extractSessionMemory)");
    }

    /**
     * Post-sampling hook 入口 · 对齐 CC {@code extractSessionMemory}（sessionMemory.ts:272-350）。
     *
     * <p>hook 异常自隔离（对齐 CC postSamplingHooks.ts:62-69 logError continue）——
     * PostSamplingHookRegistry.executeAll 异步 fire-and-forget，SM 提取 LLM fork 可能慢，
     * 绝不能阻塞 LlmAgentLoop 主链。
     */
    void onPostSampling(PostSamplingContext psContext) {
        try {
            extractSessionMemory(psContext);
        } catch (Exception e) {
            log.warn("[SessionMemory] extractSessionMemory 异常已隔离: {}", e.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // extractSessionMemory · 对齐 CC sessionMemory.ts:272-350
    // ════════════════════════════════════════════════════════════════════

    /**
     * Session memory 提取 · 对齐 CC {@code extractSessionMemory}（sessionMemory.ts:272-350）。
     *
     * <p><b>[IMP-HOOKS-S7 D10 登记]</b> CC 端 {@code extractSessionMemory = sequential(...)}
     * （sessionMemory.ts:272）排队串行；Java 端跨批串行由
     * {@link PostSamplingHookRegistry} 单线程执行器保证（executeAll 批间 FIFO、互不重叠，
     * CC sequential 队列等价）—— 本方法本体不加锁（CC manual 路径 :387-453 亦不包装）。
     * 旧实现曾依赖 executeAll 并行 + MagicDocs 同批并发，现收敛为全 hook 串行。
     *
     * <p>门控顺序与 CC 完全一致：
     * <ol>
     *   <li>{@code querySource !== 'repl_main_thread'} → return（:278-281，INV-9）</li>
     *   <li>feature gate（:284-291，tengu_session_memory 部署标志等价）</li>
     *   <li>shouldExtractMemory 阈值（:296-298）</li>
     * </ol>
     * 然后 markExtractionStarted → setupSessionMemoryFile（mkdir + wx 创建 + 模板）→
     * buildSessionMemoryUpdatePrompt → runForkedAgent（canUseTool 仅 Edit 精确路径，
     * querySource='session_memory'）→ recordExtractionTokenCount →
     * updateLastSummarizedMessageIdIfSafe → markExtractionCompleted。
     */
    public void extractSessionMemory(PostSamplingContext psContext) {
        if (psContext == null) {
            return;
        }
        // [SM-05] sequential() 等价互斥（DRIFT-3）· CC sessionMemory.ts:272 提取 hook 由
        //   sequential() 包装（utils/sequential.ts:19-56 FIFO 队列逐次执行）——相邻轮次提取
        //   不并发（双 fork 同写 summary.md / extractionStartedAt 覆盖防护）。
        //   锁释放与提取状态无关（[OPD-CM3-28/F04] 异常/跳过路径由 doExtractSessionMemory 内
        //   finally 兜底清除滞留时间戳，与锁释放解耦）。
        EXTRACTION_LOCK.lock();
        try {
            doExtractSessionMemory(psContext);
        } finally {
            EXTRACTION_LOCK.unlock();
        }
    }

    /**
     * SM 提取门控实时解析 · CC original: {@code isSessionMemoryGateEnabled()}
     * （sessionMemory.ts:80-82，getFeatureValue_CACHED_MAY_BE_STALE('tengu_session_memory', false)）。
     *
     * <p><b>[SM-DB-gate 2026-08-30]</b> DB settings.sm_session_memory_enabled 有值覆盖
     * {@link #sessionMemoryFeatureEnabled}（env NEXUSAI_SESSION_MEMORY / featureFlags.smSessionMemory
     * 装配链，ToolRegistrationConfig:1408-1412）——前端配 DB=true 即获提取入口、DB=false 即阻断提取。
     * null（settingsResolver 未接线 / DB 未配置 / 行缺失 / 读取异常）回落注入值，零行为变化。
     *
     * <p>与 {@link #shouldUseSessionMemoryCompaction} 压缩门控①同列同 flag（CC 提取/压缩读同一
     * tengu_session_memory：sessionMemory.ts:80-82 + sessionMemoryCompact.ts:412-415）——
     * sm_session_memory_enabled = SM 功能总开关（提取+压缩共用），sm_compact_enabled 保持仅压缩
     * （tengu_sm_compact，sessionMemoryCompact.ts:416-419）。</p>
     *
     * @return true=SM 提取可用
     */
    private boolean resolveSessionMemoryFeatureEnabled() {
        Boolean dbSmSession = settingsResolver != null ? settingsResolver.smSessionMemoryEnabled() : null;
        return dbSmSession != null ? dbSmSession : sessionMemoryFeatureEnabled;
    }

    /**
     * 提取主体（[SM-05] 锁内执行）· 门控/管线语义见 {@link #extractSessionMemory}。
     */
    private void doExtractSessionMemory(PostSamplingContext psContext) {
        List<ChatMessageDto> messages = psContext.messages();

        // ── 门控 1: repl_main_thread（sessionMemory.ts:278，INV-9）──
        if (psContext.querySource() != QuerySource.REPL_MAIN_THREAD) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] 提取门控跳过: querySource={}（非主线程，子代理/teammate 不提取）",
                    psContext.querySource());
            }
            return;
        }

        // ── 门控 2: feature gate（sessionMemory.ts:284 tengu_session_memory）──
        // [SM-DB-gate] DB settings.sm_session_memory_enabled 有值覆盖注入 flag（前端入口），
        //   null 回落 sessionMemoryFeatureEnabled（env/feature 链）。CC 提取/压缩读同一 flag。
        if (!resolveSessionMemoryFeatureEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] 提取门控跳过: SM 功能开关=false"
                    + "（DB sm_session_memory_enabled 未置 true，回落 sessionMemoryFeatureEnabled={}）",
                    sessionMemoryFeatureEnabled);
            }
            // [SM-08] gate_disabled ant-only + 每 session 一次（DRIFT-5）· CC sessionMemory.ts:286-288
            //   `if (process.env.USER_TYPE === 'ant' && !hasLoggedGateFailure)` —— 非 ant 不发射、
            //   同 session 仅发射一次（旧实现每次 hook 触发都发，NOT_ALIGNED）。
            if (userTypeIsAnt.getAsBoolean() && !hasLoggedGateFailure) {
                hasLoggedGateFailure = true;
                emitTelemetry("tengu_session_memory_gate_disabled", java.util.Map.of());
            }
            return;
        }

        // ── 门控 3: shouldExtractMemory（sessionMemory.ts:296-298）──
        // [sm-cursor-sessionize] sessionId 前置解析（markExtractionStarted/shouldExtractMemory
        //   会话态游标读写均需本会话键，不能在 try 内才解析）
        String sessionId = sessionIdFrom(psContext.toolUseContext());
        if (!shouldExtractMemory(sessionId, messages)) {
            return;
        }

        markExtractionStarted(sessionId);
        // [OPD-CM3-28/F04] markExtractionStarted 后用 finally 保证 markExtractionCompleted：
        //   成功路径保持 CC :349 恰一次 markExtractionCompleted；异常/跳过路径（forkedQuery==null
        //   提前 return、runForkedAgent 异常）用 finally 兜底清除滞留时间戳——否则 extractionStartedAt
        //   恒滞留，waitForSessionMemoryExtraction 阻塞满 15s（S1）。取代旧 G-43 无 finally 语义
        //   （DRIFT-4，异常滞留靠 60s stale 早退）。SM-05 锁的释放由 extractSessionMemory 外层
        //   finally 承担（与提取状态无关）。
        boolean extractionCompleted = false;
        try {
            ToolUseContext toolUseContext = psContext.toolUseContext();

            // ── 隔离 setup 上下文（CC :303 createSubagentContext(toolUseContext)）──
            //    setup 阶段 readFileState 播种进 setupCtx；fork 共享该缓存（:324 overrides），
            //    避免污染父 REPL 上下文的 readFileState（CC "Create isolated context for setup
            //    to avoid polluting parent's cache"）
            ToolUseContext setupCtx = createSubagentContext.create(toolUseContext, null);

            // ── setupSessionMemoryFile（CC :183-233）──
            Path memoryPath = resolvePath(sessionId);
            String currentMemory = setupSessionMemoryFile(setupCtx, memoryPath);

            // ── buildSessionMemoryUpdatePrompt（CC :310-313）──
            String userPrompt = prompts.buildSessionMemoryUpdatePrompt(currentMemory, memoryPath.toString());
            List<ChatMessageDto> promptMessages = List.of(userMessage(userPrompt));

            // ── cache-safe params + fork（CC :318-325）──
            CacheSafeParams supplied = cacheSafeParamsSupplier != null ? cacheSafeParamsSupplier.get() : null;
            CacheSafeParams cacheSafeParams = supplied != null
                ? new CacheSafeParams(
                    // [RES-C5 rework] 生产 supplier（ToolRegistrationConfig.buildProductionCacheSafeParams）
                    //   systemPrompt 恒空 —— supplied.systemPrompt() 空时合并 psContext 会话原料
                    //   （REPLHookContext 等价 · forkedAgent.ts:131），保证生产 supplier 分支也走真实
                    //   会话 systemPrompt（缓存 key 与主循环对齐）；非空（未来 C2/C10 接线方注入）
                    //   保留原值。
                    mergeSystemPrompt(supplied.systemPrompt(), sessionSystemPrompt(psContext)),
                    mergeContext(supplied.userContext(), psContext.userContext()),
                    mergeContext(supplied.systemContext(), psContext.systemContext()),
                    supplied.toolUseContext(),   // 生产 supplier 携带真实工具集（保留，buildProductionCacheSafeParams 唯一有效载荷）
                    messages,
                    // [RES-C5 rework] gate 合并：生产 supplier 5 参便捷构造 gate=false（占位），
                    //   会话级 gate（GlobalCacheScope 单实现 · betas.ts:227-233）与 supplier 值
                    //   OR 合并 —— firstParty（useGlobalCacheScopeSupplier=true）时 fork 发送边界
                    //   boundary 剥离生效，与主线程同一判定（REQ-C5-4）。
                    supplied.useGlobalCacheScope() || useGlobalCacheScopeSupplier.get())
                : RunForkedAgent.createMinimalCacheSafeParams(
                    messages,
                    // [RES-C5] 兜底填真实会话 systemPrompt（REPLHookContext.systemPrompt 等价
                    //   forkedAgent.ts:131）· 空/无 → List.of() 降级（验收 2）
                    sessionSystemPrompt(psContext),
                    psContext.userContext(),          // [RES-C5] userContext 透传（forkedAgent.ts:132）
                    psContext.systemContext(),        // [RES-C5] systemContext 透传（forkedAgent.ts:133）
                    useGlobalCacheScopeSupplier.get()); // [RES-C5] gate 透传（GlobalCacheScope 单实现 · betas.ts:227-233）

            HookPermissionResolver.CanUseTool canUseTool = createMemoryFileCanUseTool(memoryPath.toString());
            // skipTranscript/skipCacheWrite 不设（false）· CC sessionMemory.ts:318-325 未传，
            // 保持 query 默认（undefined）语义
            // readFileState: setupContext.readFileState 共享（CC :324 overrides）——
            // fork 与 setup 共用同一缓存，setup 阶段播种的 memory 文件 full entry 让
            // fork 内 Edit 通过 read-before-write 门禁（sessionMemory.ts:216-226 + :324）
            ForkedAgentParams params = new ForkedAgentParams(
                promptMessages, cacheSafeParams, canUseTool,
                QuerySource.SESSION_MEMORY, "session_memory",
                /*maxOutputTokens*/ null,
                /*maxTurns*/ null,
                /*skipTranscript*/ false,
                /*skipCacheWrite*/ false,
                /*abortController*/ null,
                /*onMessage*/ null,
                setupCtx.readFileState());

            if (forkedQuery == null) {
                log.warn("[SessionMemory] fork 查询 seam 未注入（生产 ToolRegistrationConfig 必须 "
                    + "setForkedQuery 注入 ProductionForkedQuery），提取跳过（游标不动，下轮重试）");
                return;
            }

            log.info("[SessionMemory] 发起提取 fork: session={} currentMemory={}chars",
                sessionId, currentMemory.length());
            RunForkedAgent.run(params, forkedQuery);

            // ── recordExtractionTokenCount（CC :344）──
            SessionMemoryUtils.recordExtractionTokenCount(sessionId, tokenCountWithEstimation(messages));

            // ── updateLastSummarizedMessageIdIfSafe（CC :347）──
            updateLastSummarizedMessageIdIfSafe(sessionId, messages);

            // [FIX-SM] tengu_session_memory_extraction（sessionMemory.ts:332-341）遥测补发
            // OPD-R2-SM-01（DRIFT-2/G-44）: 补 cache_read_input_tokens/cache_creation_input_tokens
            // （CC :335-337；DTO null = provider 未捕获（S4-2b）→ 0，与 input/output 现状等价）
            ChatMessageDto lastMsg = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            // [V52 token-compact-settings-fix] 遥测取生效配置（DB > 内存 > 默认），
            // 与阈值谓词读端（getEffectiveSessionMemoryConfig）同源，DB 覆盖时上报真实阈值。
            SessionMemoryUtils.SessionMemoryConfig cfg = SessionMemoryUtils.getEffectiveSessionMemoryConfig();
            emitTelemetry("tengu_session_memory_extraction", java.util.Map.of(
                "input_tokens", lastMsg != null && lastMsg.inputTokens() != null ? lastMsg.inputTokens() : 0,
                "output_tokens", lastMsg != null && lastMsg.outputTokens() != null ? lastMsg.outputTokens() : 0,
                "cache_read_input_tokens", lastMsg != null && lastMsg.inputCacheReadTokens() != null
                    ? lastMsg.inputCacheReadTokens() : 0,
                "cache_creation_input_tokens", lastMsg != null && lastMsg.inputCacheCreationTokens() != null
                    ? lastMsg.inputCacheCreationTokens() : 0,
                "config_min_message_tokens_to_init", cfg.minimumMessageTokensToInit(),
                "config_min_tokens_between_update", cfg.minimumTokensBetweenUpdate(),
                "config_tool_calls_between_updates", cfg.toolCallsBetweenUpdates()));

            log.info("[SessionMemory] 提取完成: session={} · lastSummarizedMessageId={}",
                sessionId, getLastSummarizedMessageId(sessionId));
            // [OPD-CM3-28/F04] 成功路径 markExtractionCompleted（CC sessionMemory.ts:349 · 恰一次）
            markExtractionCompleted(sessionId);
            extractionCompleted = true;
        } finally {
            if (!extractionCompleted) {
                // [OPD-CM3-28/F04] 异常/跳过路径兜底：释放滞留时间戳（防 waitForSessionMemoryExtraction 阻塞满 15s）
                markExtractionCompleted(sessionId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // manuallyExtractSessionMemory · 对齐 CC sessionMemory.ts:387-453
    // ════════════════════════════════════════════════════════════════════

    /**
     * 手动触发 session memory 提取（绕过阈值检查）· CC original:
     * {@code manuallyExtractSessionMemory}（sessionMemory.ts:387-453），/summary 命令用。
     *
     * <p><b>[FIX-SM] 补实现（此前缺失，NOT_ALIGNED）</b>：Java 无 /summary 生产调用方，
     * 实现为 seam（sm 提取复用同一 {@link #setupSessionMemoryFile} + fork 管线），
     * 待命令层接线。语义对齐 CC：
     * <ol>
     *   <li>空消息 → {@code {success:false, error:'No messages to summarize'}}（:391-393）</li>
     *   <li>markExtractionStarted → 隔离 setup ctx → 建立/读取文件 → buildPrompt → fork
     *       （forkLabel='session_memory_manual'，querySource=session_memory）→
     *       tengu_session_memory_manual_extraction（:436）→ recordExtractionTokenCount（:439）
     *       → updateLastSummarizedMessageIdIfSafe（:442）</li>
     *   <li>异常 → {@code {success:false, error:errorMessage}}（:445-449）</li>
     *   <li>finally markExtractionCompleted（:450-452）</li>
     * </ol>
     *
     * @param messages       待摘要消息（空 → 失败）
     * @param toolUseContext 工具上下文（readFileState 播种；null → setup 跳过缓存播种）
     * @return 提取结果（success + memoryPath / error）
     */
    public ManualExtractionResult manuallyExtractSessionMemory(
            List<ChatMessageDto> messages, ToolUseContext toolUseContext) {
        if (messages == null || messages.isEmpty()) {
            return new ManualExtractionResult(false, null, "No messages to summarize");
        }
        // [sm-cursor-sessionize] sessionId 前置解析（markExtractionStarted 会话态游标读写需本会话键）
        String sessionId = sessionIdFrom(toolUseContext);
        markExtractionStarted(sessionId);
        try {
            // 隔离 setup 上下文（CC :398 createSubagentContext）
            ToolUseContext setupCtx = createSubagentContext.create(toolUseContext, null);
            Path memoryPath = resolvePath(sessionId);
            String currentMemory = setupSessionMemoryFile(setupCtx, memoryPath);

            String userPrompt = prompts.buildSessionMemoryUpdatePrompt(currentMemory, memoryPath.toString());
            List<ChatMessageDto> promptMessages = List.of(userMessage(userPrompt));

            // [SM-11] manual 路径真实 systemPrompt 组装（DRIFT-18）· CC sessionMemory.ts:410-428
            //   `getSystemPrompt(tools, mainLoopModel)` + `getUserContext()` + `getSystemContext()`
            //   —— 旧实现 supplier 占位值原样使用（生产 buildProductionCacheSafeParams systemPrompt
            //   恒空）且无 extract 路径的 mergeSystemPrompt/mergeContext 补偿 → 生产 manual fork
            //   systemPrompt 恒空（NOT_ALIGNED）。现与 extract 路径对称：supplied 非空保留原值，
            //   空 → 用 {@link #assembleManualSystemPrompt} 组装（getSystemPrompt 等价）。
            CacheSafeParams supplied = cacheSafeParamsSupplier != null ? cacheSafeParamsSupplier.get() : null;
            List<String> manualSystemPrompt = assembleManualSystemPrompt(toolUseContext);
            CacheSafeParams cacheSafeParams = supplied != null
                ? new CacheSafeParams(
                    mergeSystemPrompt(supplied.systemPrompt(), manualSystemPrompt),
                    mergeContext(supplied.userContext(), Map.of()),
                    mergeContext(supplied.systemContext(), Map.of()),
                    supplied.toolUseContext(), messages,
                    // [RES-C5 rework] gate 合并同 extractSessionMemory：生产 supplier 5 参便捷构造
                    //   gate=false 占位，与会话级 gate（GlobalCacheScope 单实现）OR 合并（REQ-C5-4）
                    supplied.useGlobalCacheScope() || useGlobalCacheScopeSupplier.get())
                : RunForkedAgent.createMinimalCacheSafeParams(
                    messages,
                    manualSystemPrompt,               // [SM-11] getSystemPrompt(tools, mainLoopModel) 等价
                    Map.of(),                         // userContext 降级（manual 无会话上下文通道）
                    Map.of(),                         // systemContext 降级
                    useGlobalCacheScopeSupplier.get()); // [RES-C5] gate 透传（GlobalCacheScope 单实现 · betas.ts:227-233）

            HookPermissionResolver.CanUseTool canUseTool = createMemoryFileCanUseTool(memoryPath.toString());
            ForkedAgentParams params = new ForkedAgentParams(
                promptMessages, cacheSafeParams, canUseTool,
                QuerySource.SESSION_MEMORY, "session_memory_manual",
                /*maxOutputTokens*/ null,
                /*maxTurns*/ null,
                /*skipTranscript*/ false,
                /*skipCacheWrite*/ false,
                /*abortController*/ null,
                /*onMessage*/ null,
                setupCtx.readFileState());

            if (forkedQuery == null) {
                log.warn("[SessionMemory] manuallyExtractSessionMemory: fork 查询 seam 未注入"
                    + "（生产 ToolRegistrationConfig 必须 setForkedQuery），返回失败");
                return new ManualExtractionResult(false, null, "ForkedQuery seam 未注入");
            }
            RunForkedAgent.run(params, forkedQuery);

            // CC :436 tengu_session_memory_manual_extraction
            emitTelemetry("tengu_session_memory_manual_extraction", java.util.Map.of());
            // CC :439 recordExtractionTokenCount
            SessionMemoryUtils.recordExtractionTokenCount(sessionId, tokenCountWithEstimation(messages));
            // CC :442 updateLastSummarizedMessageIdIfSafe
            updateLastSummarizedMessageIdIfSafe(sessionId, messages);

            log.info("[SessionMemory] 手动提取完成: session={} memoryPath={}",
                sessionId, memoryPath);
            return new ManualExtractionResult(true, memoryPath.toString(), null);
        } catch (Exception e) {
            log.warn("[SessionMemory] 手动提取失败: {}", e.toString());
            return new ManualExtractionResult(false, null, e.getMessage());
        } finally {
            markExtractionCompleted(sessionId);
        }
    }

    /**
     * 手动提取结果 · CC original: {@code ManualExtractionResult}（sessionMemory.ts:377-381）
     * {@code { success: boolean; memoryPath?: string; error?: string }}。
     *
     * @param success    是否成功
     * @param memoryPath session memory 文件路径（成功时非 null）
     * @param error      失败原因（失败时非 null）
     */
    public record ManualExtractionResult(boolean success, String memoryPath, String error) {}

    // ════════════════════════════════════════════════════════════════════
    // shouldExtractMemory · 对齐 CC sessionMemory.ts:134-181（token 阈值状态机）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 是否应提取 session memory · 对齐 CC {@code shouldExtractMemory}（sessionMemory.ts:134-181）。
     *
     * <p>阈值状态机（三阈值取生效配置 = DB &gt; 内存通道 &gt; 默认，[V52 token-compact-settings-fix]，
     * 括号为默认值）：
     * <ol>
     *   <li>未初始化且当前 token ≥ minimumMessageTokensToInit（默认 10000）→ markSessionMemoryInitialized</li>
     *   <li>token 增长 ≥ minimumTokensBetweenUpdate（默认 5000，自上次提取）</li>
     *   <li>lastMemoryMessageUuid 之后工具调用数 ≥ toolCallsBetweenUpdates（默认 3）</li>
     *   <li>最后 assistant turn 无工具调用（自然对话停顿）</li>
     * </ol>
     * 提取触发条件：token 阈值 <b>必须</b> 满足，且（工具阈值 或 最后 turn 无工具）（CC :160-170）。
     *
     * <p><b>[sm-cursor-sessionize]</b>：本状态机读写全部会话态游标
     * （sessionMemoryInitialized / tokensAtLastExtraction / lastMemoryMessageUuid），
     * 均按 sessionId 键控——B 的阈值判定不再被 A 的游标污染（旧 static volatile 语义下
     * A 已初始化 → B 跳过 init 阈值 → B 的 SM 提取时机错乱）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @param messages 消息列表
     * @return true=应提取
     */
    public boolean shouldExtractMemory(String sessionId, List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int currentTokenCount = tokenCountWithEstimation(messages);

        // 未初始化 → 检查 init 阈值（CC :136-143）
        if (!SessionMemoryUtils.isSessionMemoryInitialized(sessionId)) {
            if (!SessionMemoryUtils.hasMetInitializationThreshold(currentTokenCount)) {
                return false;
            }
            SessionMemoryUtils.markSessionMemoryInitialized(sessionId);
        }

        boolean hasMetTokenThreshold = SessionMemoryUtils.hasMetUpdateThreshold(sessionId, currentTokenCount);
        int toolCallsSinceLastUpdate =
            countToolCallsSince(messages, getLastMemoryMessageUuid(sessionId));
        boolean hasMetToolCallThreshold =
            toolCallsSinceLastUpdate >= SessionMemoryUtils.getToolCallsBetweenUpdates();
        boolean hasToolCallsInLastTurn = hasToolCallsInLastAssistantTurn(messages);

        boolean shouldExtract =
            (hasMetTokenThreshold && hasMetToolCallThreshold)
                || (hasMetTokenThreshold && !hasToolCallsInLastTurn);

        if (shouldExtract) {
            ChatMessageDto lastMessage = messages.get(messages.size() - 1);
            if (lastMessage != null && lastMessage.id() != null) {
                setLastMemoryMessageUuid(sessionId, lastMessage.id());
            }
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] shouldExtractMemory=true: tokens={} init={} update={} "
                        + "toolCalls={}/{} lastTurnTool={}",
                    currentTokenCount, SessionMemoryUtils.isSessionMemoryInitialized(sessionId),
                    hasMetTokenThreshold, toolCallsSinceLastUpdate,
                    SessionMemoryUtils.getToolCallsBetweenUpdates(), hasToolCallsInLastTurn);
            }
        }
        return shouldExtract;
    }

    /**
     * 统计 sinceUuid 之后的工具调用数 · CC original: {@code countToolCallsSince}
     * （sessionMemory.ts:108-132）。
     *
     * @param messages  消息列表
     * @param sinceUuid 游标 UUID（null = 全量计数，首次运行）
     * @return assistant 消息 tool_use 数
     */
    static int countToolCallsSince(List<ChatMessageDto> messages, String sinceUuid) {
        int toolCallCount = 0;
        boolean foundStart = sinceUuid == null;
        for (ChatMessageDto message : messages) {
            if (message == null) {
                continue;
            }
            if (!foundStart) {
                if (sinceUuid.equals(message.id())) {
                    foundStart = true;
                }
                continue;
            }
            if (message.role() == Role.assistant && message.toolCalls() != null) {
                toolCallCount += message.toolCalls().size();
            }
        }
        return toolCallCount;
    }

    /**
     * 最后 assistant turn 是否含工具调用 · CC original: {@code hasToolCallsInLastAssistantTurn}
     * （messages.ts:341-353）。
     *
     * <p><b>[FIX-SM] 修正 NOT_ALIGNED</b>：CC 对 assistant 消息的 content 分支判断 ——
     * <ul>
     *   <li>content 是数组且含 tool_use 块 → true（:348）</li>
     *   <li>content 是数组（无 tool_use，如空/纯文本块）→ false 立即返回（:347-350）</li>
     *   <li>content 是<b>纯字符串</b>（非数组）→ <b>继续向前扫</b>（:347 非 array 分支不返回）</li>
     * </ul>
     * 旧 Java 对第一条 assistant 消息无条件 return（纯文本末条 → 直接 false，遗漏更早的
     * tool_use 轮 → updateLastSummarizedMessageIdIfSafe 错误推进游标）。Java 折算：
     * toolCalls 非空 → true；contentBlocks 非空（结构化数组）→ false；contentBlocks 空
     * （纯文本字符串，Java 合成消息常态）→ 继续向前扫。
     *
     * @param messages 消息列表
     * @return true=最后 assistant turn 含 tool_use
     */
    static boolean hasToolCallsInLastAssistantTurn(List<ChatMessageDto> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    // content 数组含 tool_use 块（messages.ts:348）
                    return true;
                }
                if (m.contentBlocks() != null && !m.contentBlocks().isEmpty()) {
                    // content 数组但无 tool_use → 立即返回 false（messages.ts:347-350）
                    return false;
                }
                // content 纯字符串（contentBlocks 空 = Java 合成文本消息）→ 继续向前扫
                // （messages.ts:347 非 array 分支）
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════
    // setupSessionMemoryFile · 对齐 CC sessionMemory.ts:183-233
    // ════════════════════════════════════════════════════════════════════

    /**
     * 建立 session memory 文件并读取当前内容 · 对齐 CC {@code setupSessionMemoryFile}
     * （sessionMemory.ts:183-233）。
     *
     * <ol>
     *   <li>mkdir 目录（CC :190，mode 0o700；POSIX 视图下逐层 0o700，Windows 权限 no-op）</li>
     *   <li>文件不存在（wx=O_CREAT|O_EXCL，mode 0o600）→ 写入空 + 模板（CC :194-212）</li>
     *   <li>丢弃 readFileState 缓存项（CC :216 toolUseContext.readFileState.delete）——
     *       防 dedup 返回 file_unchanged 桩</li>
     *   <li><b>[G-41] 读回当前内容经权限层</b>（CC :217-226 FileReadTool.call）——注入
     *       {@link ReadFileTool} 真实读回（PathGuard/deny 规则/dedup/CRLF 归一化），
     *       readFileState 由 ReadFileTool 重新 populate（不再 Files.readString 直读 +
     *       手工 seed，DRIFT-14）。</li>
     * </ol>
     *
     * @param toolUseContext 工具上下文（readFileState 缓存清理；可为 null）
     * @param memoryPath     session memory 文件路径
     * @return 当前 memory 内容
     */
    String setupSessionMemoryFile(ToolUseContext toolUseContext, Path memoryPath) {
        try {
            Path dir = memoryPath.getParent();
            if (dir != null) {
                createDirectoriesOwnerOnly(dir);
            }
            if (!Files.exists(memoryPath)) {
                try {
                    if (POSIX_ENABLED) {
                        // CREATE_NEW + 0o600 = CC writeFile(…,{mode:0o600,flag:'wx'})
                        // （sessionMemory.ts:196-200）—— mode 仅在创建时生效
                        Files.createFile(memoryPath, OWNER_ONLY_FILE);
                    } else {
                        Files.writeString(memoryPath, "", java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE_NEW);
                    }
                    // 仅新建时加载模板（CC :201-206）· 文件已存在，0o600 权限保留
                    String template = prompts.loadSessionMemoryTemplate();
                    Files.writeString(memoryPath, template, java.nio.charset.StandardCharsets.UTF_8);
                } catch (FileAlreadyExistsException e) {
                    // 并发创建，忽略（CC :207-211 EEXIST 分支）
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("[SessionMemory] setupSessionMemoryFile 失败: " + memoryPath, e);
        }

        // readFileState 归一化 key · 与 EditFileTool/ReadFileTool 共用 keyForReadFileState
        // 派生（guard.resolve 对绝对路径 = toRealPath/normalize 后的规范绝对路径），
        // delete/读回两侧同 key 保证 Edit read-before-write 门禁命中（concern #4 实证）
        String seedKey = readFileStateKey(memoryPath);

        // 丢弃 readFileState 缓存项（CC :216 toolUseContext.readFileState.delete）——
        // 防 FileReadTool dedup 返回 file_unchanged 桩
        if (toolUseContext != null && toolUseContext.readFileState() != null) {
            toolUseContext.readFileState().delete(seedKey);
        }

        // [G-41] 读取改经权限层（DRIFT-14）· CC :217-226 FileReadTool.call ——
        //   ReadFileTool.execute 真实读回（PathGuard/deny 规则/dedup/CRLF 归一化），
        //   readFileState 由工具重新 populate（fork Edit read-before-write 门禁通过）。
        String content = readThroughFileReadTool(toolUseContext, memoryPath, seedKey);
        if (log.isDebugEnabled()) {
            log.debug("[SessionMemory] 经 FileReadTool 读取 session memory: path={} chars={} key={}",
                memoryPath, content.length(), seedKey);
        }
        // [FIX-SM] tengu_session_memory_file_read（sessionMemory.ts:228-230）遥测补发
        emitTelemetry("tengu_session_memory_file_read",
            java.util.Map.of("content_length", content.length()));
        return content;
    }

    /**
     * 递归创建目录并设 owner-only（0o700）· 对齐 CC {@code fs.mkdir(dir,{recursive:true,mode:0o700})}
     * （sessionMemory.ts:190 + fsOperations.ts:414-425）。POSIX 下每层 createDirectory 带 0o700
     * 属性（Node recursive mkdir 对每层新目录生效同语义）；Windows 无 POSIX 视图则普通创建
     * （权限 no-op，CC Windows 忽略 mode）。目录已存在则跳过（CC fs.mkdir EEXIST 容忍）。
     *
     * @param dir 待创建目录
     * @throws IOException 创建失败
     */
    private static void createDirectoriesOwnerOnly(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Path parent = dir.getParent();
        if (parent != null) {
            createDirectoriesOwnerOnly(parent);
        }
        if (POSIX_ENABLED) {
            try {
                Files.createDirectory(dir, OWNER_ONLY_DIR);
            } catch (FileAlreadyExistsException e) {
                // 并发创建，忽略（CC fs.mkdir EEXIST 容忍 fsOperations.ts:414-425）
            }
        } else {
            Files.createDirectories(dir);
        }
    }

    /**
     * [G-41] 经权限层读取 session memory 文件 · CC original: FileReadTool.call
     * （sessionMemory.ts:217-226）。
     *
     * <p><b>内容来源</b>：execute 返回值不可直接用 —— Java 渲染层把
     * {@code CYBER_RISK_MITIGATION_REMINDER} 追加进 text 分支返回串（ReadFileTool.java:799-800），
     * 而 CC {@code output.file.content} 为纯内容（FileReadTool.ts:1032-1037 缓存即干净内容）。
     * 故读 {@code readFileState} 缓存条目（ReadFileTool 在返回前写入的干净 CRLF 归一化内容）。
     *
     * <p><b>错误语义（对齐 CC :223-226）</b>：读取错误（含权限层拒绝）→ 非 text 输出 →
     * {@code currentMemory=''}（不抛，不打断提取）；仅 execute 抛出（unexpected）→ 上抛
     * （对齐 CC FileReadTool.call 异常经 extractSessionMemory 传播；[OPD-CM3-28/F04] 异常传播前
     * 由 extract 路径 finally 兜底清除 extractionStartedAt 滞留时间戳）。
     *
     * @param toolUseContext 工具上下文（readFileState 缓存；可 null → 无缓存条目 → 空内容）
     * @param memoryPath     session memory 文件路径
     * @param cacheKey       readFileState 归一化 key（与 ReadFileTool 写入 key 一致）
     * @return 文件内容（读失败 → ""）
     */
    private String readThroughFileReadTool(ToolUseContext toolUseContext, Path memoryPath, String cacheKey) {
        if (readFileTool == null) {
            // 无直读降级（对齐 MagicDocsService:358-362 同款 fail-loud）——G-41 移除 Files.readString
            throw new IllegalStateException("[SessionMemory] ReadFileTool 未注入（生产 ToolRegistrationConfig"
                + " 必须 setReadFileTool）——SM 读取经权限层，无 Files.readString 降级");
        }
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", memoryPath.toString());
        ToolUseBlock call = new ToolUseBlock(
            "session-memory-read-" + Integer.toHexString(memoryPath.toString().hashCode()),
            readFileTool.name(), input);
        com.nexusai.application.agent.tool.AgentToolResult<?> readResult;
        try {
            // offset/limit 不传 = full read（CC FileReadTool.ts:497 offset=1 + limit=undefined）
            readResult = readFileTool.execute(call, toolUseContext);
        } catch (Exception e) {
            // CC FileReadTool.call unexpected 异常 → 传播（不吞）
            throw new IllegalStateException("[SessionMemory] FileReadTool 读 session memory 失败: "
                + memoryPath, e);
        }
        if (com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(readResult.data())) {
            // CC :223-226 非 text 输出（含错误结果）→ currentMemory=''（不抛）
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] FileReadTool 读 session memory 错误 → 空内容: path={} err={}",
                    memoryPath, readResult.data());
            }
            return "";
        }
        // 缓存条目 = 干净内容（无渲染层前缀）· ReadFileTool.dispatchText:782-786 写入
        if (toolUseContext != null && toolUseContext.readFileState() != null) {
            ToolUseContext.ReadState entry = toolUseContext.readFileState().get(cacheKey);
            if (entry != null) {
                return entry.content();
            }
        }
        // 缓存条目不可得（ctx null / key 派生漂移防御）→ 用 execute 返回值
        Object data = readResult.data();
        return data instanceof String s ? s : "";
    }

    /**
     * readFileState 归一化 key · 与 {@link ToolUseContext#keyForReadFileState} 对
     * 绝对路径的派生一致（guard.resolve(绝对路径) → toRealPath（文件存在）→
     * toAbsolutePath().normalize()）：seed 侧无法持有 fork 的 EditFileTool guard，
     * 故以文件自身规范绝对路径为 key；guard 不重映射该路径（baseDir 即 guard workdir）
     * 时与 gate key 严格一致（integration test 实证）。
     *
     * @param memoryPath session memory 文件路径
     * @return 规范绝对路径字符串（readFileState key）
     */
    private static String readFileStateKey(Path memoryPath) {
        try {
            return memoryPath.toRealPath().toAbsolutePath().normalize().toString();
        } catch (IOException e) {
            return memoryPath.toAbsolutePath().normalize().toString();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // createMemoryFileCanUseTool · 对齐 CC sessionMemory.ts:460-482
    // ════════════════════════════════════════════════════════════════════

    /**
     * 创建受限 canUseTool · CC original: {@code createMemoryFileCanUseTool}
     * （sessionMemory.ts:460-482）：仅允许 Edit 且 file_path 精确等于 memory 文件路径；
     * 其余工具拒绝（含同文件之外的 Edit）。
     *
     * @param memoryPath session memory 文件绝对路径
     * @return 受限权限检查函数
     */
    public static HookPermissionResolver.CanUseTool createMemoryFileCanUseTool(String memoryPath) {
        return (tool, input, ctx, toolUseId, forceDecision) -> {
            String name = tool == null ? null : tool.name();
            if ("Edit".equals(name)
                && input != null && input.has("file_path") && input.get("file_path").isTextual()) {
                String filePath = input.get("file_path").asText();
                if (memoryPath.equals(filePath)) {
                    return ToolPermissionGate.DecisionResult.allow();
                }
            }
            // [SM-08] deny 补 message + decisionReason（DRIFT-12）· CC sessionMemory.ts:473-480
            //   `{behavior:'deny', message:'only Edit on {path} is allowed', decisionReason:
            //   {type:'other', reason:'only Edit on {path} is allowed'}}` —— 旧实现 deny(null)
            //   无 message → 模型自纠指引缺失（权限拒绝展示文本差异）。
            String denyMessage = "only Edit on " + memoryPath + " is allowed";
            return ToolPermissionGate.DecisionResult.deny(
                new PermissionResult.Deny(denyMessage,
                    new PermissionDecisionReason.Other(denyMessage),
                    toolUseId));
        };
    }

    /**
     * 提取成功后安全更新本会话 lastSummarizedMessageId · CC original:
     * {@code updateLastSummarizedMessageIdIfSafe}（sessionMemory.ts:488-495）。
     *
     * <p>仅当最后 turn 无工具调用时 set（避免孤儿 tool_result）。
     * <b>[sm-cursor-sessionize] 按 sessionId 键控</b>（只推进本会话游标）。
     *
     * @param sessionId 会话 ID（null → "unknown" 兜底键）
     * @param messages 消息列表
     */
    private static void updateLastSummarizedMessageIdIfSafe(String sessionId, List<ChatMessageDto> messages) {
        if (!hasToolCallsInLastAssistantTurn(messages)) {
            ChatMessageDto lastMessage = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (lastMessage != null && lastMessage.id() != null) {
                setLastSummarizedMessageId(sessionId, lastMessage.id());
            }
        }
    }

    /** 抽取标记开始（委托 SessionMemoryUtils 模块态，CC sessionMemoryUtils.ts:74，按 sessionId 键控）。 */
    private static void markExtractionStarted(String sessionId) {
        SessionMemoryUtils.markExtractionStarted(sessionId);
    }

    /** 抽取标记完成（委托 SessionMemoryUtils 模块态，CC sessionMemoryUtils.ts:81，按 sessionId 键控）。 */
    private static void markExtractionCompleted(String sessionId) {
        SessionMemoryUtils.markExtractionCompleted(sessionId);
    }

    /**
     * [SM-02] 执行 SessionStart hooks（source='compact'）· CC original:
     * {@code processSessionStartHooks('compact', {model: getMainLoopModel()})}
     * （sessionMemoryCompact.ts:583-586）——SM 压缩后恢复 CLAUDE.md/会话启动上下文，
     * hook 输出消息进 CompactionResult.hookResults（GAP-3）。
     *
     * <p><b>接线</b>：hookRegistry 由 ToolRegistrationConfig 注入（生产 HookRegistry bean，
     * 与 CompactConversation.buildAutoContext 同源）；model 由 {@link #mainLoopModelSupplier}
     * 提供（fork modelSupplier 同源，CC getMainLoopModel() 等价）；未注入 → 空结果
     * （等价 CC 无 hook 注册，CompactHooks.processSessionStartHooks registry null 短路）。
     *
     * @param sessionId 会话 ID
     * @param agentId   agent ID
     * @return hook 结果消息列表（无 hook / 无输出 → 空）
     */
    private List<ChatMessageDto> runSessionStartHooks(String sessionId, String agentId) {
        HookRegistry registry = sessionStartHookRegistry;
        if (registry == null) {
            return List.of();
        }
        CompactConversationContext hookCtx = new CompactConversationContext()
            .setSessionId(sessionId)
            .setAgentId(agentId)
            .setModel(mainLoopModelSupplier != null ? mainLoopModelSupplier.get() : null)
            .setHookRegistry(registry);
        List<ChatMessageDto> hookResults = CompactHooks.processSessionStartHooks(hookCtx);
        if (log.isDebugEnabled()) {
            log.debug("[SessionMemory] SM 压缩 SessionStart hooks('compact'): results={}",
                hookResults.size());
        }
        return hookResults;
    }

    /**
     * [SM-11] manual 提取真实 systemPrompt 组装 · CC original:
     * {@code getSystemPrompt(tools, mainLoopModel)}（sessionMemory.ts:410-417）——
     * 以调用方 ToolUseContext 的工具集 + appState mainLoopModel 经
     * {@link SystemPromptAssembler} 组装（Java getSystemPrompt 等价）；无装配原料
     * （TUC null / 空工具集 / 组装失败）→ 降级空列表（与 extract 路径 sessionSystemPrompt
     * 的 List.of() 降级同语义，不抛错）。
     *
     * @param toolUseContext 工具上下文（availableTools + appState mainLoopModel 源）
     * @return systemPrompt 数组（无原料 → 空）
     */
    private static List<String> assembleManualSystemPrompt(ToolUseContext toolUseContext) {
        if (toolUseContext == null) {
            return List.of();
        }
        java.util.Set<String> enabledTools = toolUseContext.availableTools() != null
            ? toolUseContext.availableTools().stream()
                .map(com.nexusai.application.agent.tool.Tool::name)
                .collect(java.util.stream.Collectors.toSet())
            : java.util.Set.of();
        String model = null;
        if (toolUseContext.getAppState() != null) {
            try {
                Object snapshot = toolUseContext.getAppState().apply(null);
                if (snapshot instanceof java.util.Map<?, ?> map) {
                    Object m = map.get("mainLoopModel");
                    if (m != null) {
                        model = String.valueOf(m);
                    }
                }
            } catch (Exception e) {
                // appState 读取失败 → model null（best-effort，同 ReadFileTool.resolveMainLoopModel）
                model = null;
            }
        }
        try {
            SystemPrompt sp = new SystemPromptAssembler(new SystemPromptSectionCache())
                .assemble(new SystemPromptAssemblyInput(
                    enabledTools, model, List.of(), List.of(), null, List.of(), null, null, false,
                    toolUseContext.sessionId()));  // [cwd-session 2026-08-25 修复] env_info_simple 会话 cwd（ToolUseContext 必填非空）
            return sp.elements() == null ? List.of() : sp.elements();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[SessionMemory] manual systemPrompt 组装失败，降级空: {}", e.toString());
            }
            return List.of();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // SM 压缩门控 · 对齐 CC sessionMemoryCompact.ts:403-432
    // ════════════════════════════════════════════════════════════════════

    /**
     * 是否应使用 session memory 压缩 · 对齐 CC {@code shouldUseSessionMemoryCompaction()}
     * （sessionMemoryCompact.ts:403-432）。
     *
     * <p>env override 优先：ENABLE_CLAUDE_CODE_SM_COMPACT truthy → true（:404-406）；
     * DISABLE_CLAUDE_CODE_SM_COMPACT truthy → false（:407-409）；否则回落 feature 门控
     * = {@code tengu_session_memory && tengu_sm_compact} 双 flag AND（:412-420，CC 同式）。
     *
     * @return true=SM 压缩可用
     */
    public boolean shouldUseSessionMemoryCompaction() {
        if (isEnvTruthy(envProvider.apply("ENABLE_CLAUDE_CODE_SM_COMPACT"))) {
            return true;
        }
        if (isEnvTruthy(envProvider.apply("DISABLE_CLAUDE_CODE_SM_COMPACT"))) {
            return false;
        }
        // [V52 B1-6] DB settings.sm_session_memory_enabled / sm_compact_enabled 有值覆盖注入 flag
        //   （null 回落 smSessionMemoryEnabled/smCompactEnabled，零行为变化）。
        Boolean dbSmSession = settingsResolver != null ? settingsResolver.smSessionMemoryEnabled() : null;
        Boolean dbSmCompact = settingsResolver != null ? settingsResolver.smCompactEnabled() : null;
        boolean sessionMemoryFlag = dbSmSession != null ? dbSmSession : smSessionMemoryEnabled;
        boolean smCompactFlag = dbSmCompact != null ? dbSmCompact : smCompactEnabled;
        boolean shouldUse = sessionMemoryFlag && smCompactFlag;
        // [F2/DRIFT-13] ant-only 发射 tengu_sm_compact_flag_check（sessionMemoryCompact.ts:422-429，
        //   三属性 tengu_session_memory / tengu_sm_compact / should_use）——env override 早退
        //   分支不发射（CC 同序，:404-409 提前 return）。
        if (userTypeIsAnt.getAsBoolean()) {
            emitTelemetry("tengu_sm_compact_flag_check",
                java.util.Map.of(
                    "tengu_session_memory", sessionMemoryFlag,
                    "tengu_sm_compact", smCompactFlag,
                    "should_use", shouldUse));
        }
        return shouldUse;
    }

    /**
     * 解析本会话已存在的 transcript 文件 · <b>[R1 读修复 2026-09-02]</b>。
     *
     * <p><b>WHY（活 bug）</b>：原读点 {@code SessionStorage.resolveExistingTranscript(baseDir, sessionId)}
     * 在 per-session 新布局下会把 SM 的 slug 基目录 {@code {configHome}/projects/{slug}} 误当
     * projectRoot 喂入 —— 其内部再 {@code getProjectDir(workspaceDir)} 一次 → 双重包裹
     * {@code {configHome}/projects/{sanitize(configHome/projects/{slug})}/{sessionId}.jsonl}，
     * 读不到真实 transcript。现按 SM 路径介质统一：summary 与 transcript <b>共享同一 slug 锚</b>。</p>
     *
     * <ul>
     *   <li>resolver 非空（per-session）→ {@code {projects/{slug}}/{sessionId}.jsonl} 直接判存
     *       （Files.isRegularFile），不存在 → null</li>
     *   <li>resolver null（legacy 固定 baseDir）→ 原 1-arg 语义
     *       {@code SessionStorage.resolveExistingTranscript(baseDir, sessionId)}（旧测试不变）</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @return 已存在的 nexusai transcript 文件路径；不存在 / sessionId null → null
     */
    private Path resolveExistingTranscriptForSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        if (sessionBaseDirResolver != null) {
            Path slugDir = sessionBaseDirResolver.apply(sessionId);
            if (slugDir != null) {
                Path f = slugDir.resolve(sessionId + ".jsonl");
                return Files.isRegularFile(f) ? f : null;
            }
        }
        return SessionStorage.resolveExistingTranscript(baseDir, sessionId);
    }

    // ════════════════════════════════════════════════════════════════════
    // trySessionMemoryCompaction · 对齐 CC sessionMemoryCompact.ts:514-630
    // ════════════════════════════════════════════════════════════════════

    /**
     * Session-memory 优先压缩 · 对齐 CC {@code trySessionMemoryCompaction}
     * （sessionMemoryCompact.ts:514-630，REQ-10/REQ-12）。
     *
     * <p>流程：门控（:519）→ waitForSessionMemoryExtraction（:527）→ 读内容（:530）→
     * 无文件/空模板回落（:533-543）→ calculateMessagesToKeepIndex（:571-574）→
     * 过滤 boundary → truncateSessionMemoryForCompact + buildUserMessage（:461-469）→
     * 超阈回落（:605-614）。SM 成功链由调用方执行（AutoCompactor/CompactCommand，
     * INV-8）。</p>
     *
     * @param messages            待压缩消息
     * @param sessionId           会话 ID（读 session memory 文件）
     * @param agentId             agent ID（日志/审计）
     * @param autoCompactThreshold 自动压缩阈值（post 超阈 → 回落，sessionMemoryCompact.ts:605-614）
     * @return SM 压缩结果；不可用 → null
     */
    public CompactionResult trySessionMemoryCompaction(
            List<ChatMessageDto> messages,
            String sessionId,
            String agentId,
            Integer autoCompactThreshold) {
        if (!shouldUseSessionMemoryCompaction()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] trySessionMemoryCompaction: gate=false 跳过 (SM 未启用)");
            }
            return null;
        }

        // waitForSessionMemoryExtraction（sessionMemoryCompact.ts:527）——
        // 异步提取 fire-and-forget，压缩前必须等待避免与提取竞态。
        // [sm-cursor-sessionize P1-1] 只等本会话的 extractionStartedAt——旧 static volatile 语义下
        // A 提取进行中，B 的 SM 压缩 wait 会等满 A 的 15s（跨会话 15s 阻塞）。
        SessionMemoryUtils.waitForSessionMemoryExtraction(sessionId);

        // [sm-cursor-sessionize] 只读本会话 lastSummarizedMessageId（B 不受 A 游标影响）
        String lastSummarizedId = getLastSummarizedMessageId(sessionId);
        String sessionMemory = getSessionMemoryContent(sessionId);

        // 无 session memory 文件或内容为空串 → null（tengu_sm_compact_no_session_memory，:533-536）
        // [IMP-MV2-05] CC `!sessionMemory` 为 falsy 判定（sessionMemoryCompact.ts:533）：
        //   读成功但文件被手工清空 → 内容 ""（getSessionMemoryContent 读成功返回原样）→
        //   Java 旧实现仅判 null，空串越过 → isSessionMemoryEmpty("")=false（模板非空）→
        //   继续 SM 压缩产出空摘要。对齐 falsy 语义：空串与 null 同分支回落 legacy。
        if (sessionMemory == null || sessionMemory.isEmpty()) {
            // [IMP-CM-17] CC logEvent('tengu_sm_compact_no_session_memory', {}) 结构化遥测
            emitTelemetry("tengu_sm_compact_no_session_memory", java.util.Map.of());
            log.info("[SessionMemory] tengu_sm_compact_no_session_memory: session={}", sessionId);
            return null;
        }
        // session memory 与模板相同（无实际内容）→ null（tengu_sm_compact_empty_template，:540-543）
        if (prompts.isSessionMemoryEmpty(sessionMemory)) {
            // [IMP-CM-17] CC logEvent('tengu_sm_compact_empty_template', {}) 结构化遥测
            emitTelemetry("tengu_sm_compact_empty_template", java.util.Map.of());
            log.info("[SessionMemory] tengu_sm_compact_empty_template: session={}", sessionId);
            return null;
        }

        try {
            int lastSummarizedIndex;
            if (lastSummarizedId != null) {
                lastSummarizedIndex = findMessageIndex(messages, lastSummarizedId);
                if (lastSummarizedIndex == -1) {
                    // 摘要 ID 不存在（消息被改）→ 回落（tengu_sm_compact_summarized_id_not_found，:554-560）
                    // [IMP-CM-17] CC logEvent('tengu_sm_compact_summarized_id_not_found', {}) 结构化遥测
                    emitTelemetry("tengu_sm_compact_summarized_id_not_found", java.util.Map.of());
                    log.info("[SessionMemory] tengu_sm_compact_summarized_id_not_found: id={} session={}",
                        lastSummarizedId, sessionId);
                    return null;
                }
            } else {
                // Resumed session：无 lastSummarizedId → 保留全部最近消息（:562-566）
                lastSummarizedIndex = messages.size() - 1;
                // [IMP-CM-17] CC logEvent('tengu_sm_compact_resumed_session', {}) 结构化遥测
                emitTelemetry("tengu_sm_compact_resumed_session", java.util.Map.of());
                log.info("[SessionMemory] tengu_sm_compact_resumed_session: session={}", sessionId);
            }

            // calculateMessagesToKeepIndex（sessionMemoryCompact.ts:571-574）
            int startIndex = calculateMessagesToKeepIndex(messages, lastSummarizedIndex);
            // 过滤旧 boundary（sessionMemoryCompact.ts:579-581）
            List<ChatMessageDto> messagesToKeep = new ArrayList<>();
            for (int i = startIndex; i < messages.size(); i++) {
                ChatMessageDto m = messages.get(i);
                if (!BoundaryReader.isCompactBoundaryMessage(m)) {
                    messagesToKeep.add(m);
                }
            }

            // [SM-02] SessionStart hooks（GAP-3）· CC sessionMemoryCompact.ts:583-586
            //   processSessionStartHooks('compact', {model: getMainLoopModel()}) ——
            //   SM 压缩后恢复 CLAUDE.md/会话启动上下文；hookResults 进 CompactionResult
            //   （旧实现 hookResults=List.of()，压缩后不恢复启动上下文，NOT_ALIGNED）。
            List<ChatMessageDto> hookResults = runSessionStartHooks(sessionId, agentId);

            // preCompactTokenCount · CC sessionMemoryCompact.ts:445 tokenCountFromLastAPIResponse
            // （末条 usage input+cache+output，无则 0）——OPD-R2-SM-01 DRIFT-6 修复：
            // 旧实现用简化 tokenCountWithEstimation（input+output+rough 尾段）→ boundary preTokens 漂移。
            // [A5-2 登记] 保持 1 参（anthropic 4 项和）——SM 路径无 model/mapper 上下文（本类无
            //   ModelMapper/ProviderMapper），deepseek 下 over-count 已知（AutoCompactorCcContractTest
            //   :610-626 锁定 4 项和语义），待 model 通道接入后分派。
            int preCompactTokenCount = Tokens.tokenCountFromLastAPIResponse(messages);
            String lastUuid = messages.isEmpty() ? null : messages.get(messages.size() - 1).id();
            CompactBoundaryMessage boundaryMarker = CompactBoundaryMessage.createCompactBoundaryMessage(
                "auto", preCompactTokenCount, lastUuid, null, null);

            // [SM-03] preCompactDiscoveredTools（GAP-4）· CC sessionMemoryCompact.ts:447-459
            //   非空时写入 compactMetadata（排序）——旧实现 5 参便捷构造 → compactMetadata
            //   preCompactDiscoveredTools 恒空（NOT_ALIGNED）。实现同
            //   PartialCompactConversation:341-352（compact.ts:1023-1028）。
            java.util.Set<String> preCompactDiscovered =
                PartialCompactConversation.extractDiscoveredToolNames(messages);
            if (!preCompactDiscovered.isEmpty()) {
                List<String> sorted = new ArrayList<>(preCompactDiscovered);
                java.util.Collections.sort(sorted);
                CompactBoundaryMessage.CompactMetadata meta = boundaryMarker.compactMetadata();
                boundaryMarker = boundaryMarker.withCompactMetadata(
                    new CompactBoundaryMessage.CompactMetadata(
                        meta.trigger(), meta.preTokens(), meta.userContext(), meta.messagesSummarized(),
                        sorted, meta.preservedSegment()));
                if (log.isDebugEnabled()) {
                    log.debug("[SessionMemory] SM boundary 记录 preCompactDiscoveredTools: {}", sorted);
                }
            }

            // 截断超大 section（sessionMemoryCompact.ts:461-462）
            SessionMemoryPrompts.TruncationResult truncation =
                prompts.truncateSessionMemoryForCompact(sessionMemory);
            // transcript 路径（sessionMemoryCompact.ts:464-469 第 3 参 + :589 getTranscriptPath）——
            // [S2] 扁平 {configHome}/projects/{slug}/{sessionId}.jsonl（SessionStorage.getTranscriptPath
            //   config-home 派生，对齐 CC sessionStorage.ts:202-205 getProjectDir(getOriginalCwd())）
            // [D3] 读兼容：经 resolveExistingTranscriptForSession 读 nexusai 现有 transcript（仅 nexusai，无 claude 回落）
            // [R1 活 bug 修复] 原 SessionStorage.resolveExistingTranscript(baseDir, sessionId) 把 per-session
            //   slug 目录（{configHome}/projects/{slug}）误当 projectRoot 再 getProjectDir 二次包裹；
            //   现 resolver 非空时 summary 与 transcript 共享同一 slug 锚直接判存（勿再喂 resolveExistingTranscript）
            Path transcriptPath = resolveExistingTranscriptForSession(sessionId);
            String summaryContent = CompactSummary.buildUserMessage(
                truncation.content(),
                transcriptPath == null ? null : transcriptPath.toString(),
                true, true);
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] SM 压缩摘要复用 CompactSummary.buildUserMessage: "
                        + "transcriptPath={} truncated={}",
                    transcriptPath == null ? null : transcriptPath.toString(),
                    truncation.wasTruncated());
            }
            if (truncation.wasTruncated()) {
                summaryContent += "\n\nSome session memory sections were truncated for length. "
                    + "The full session memory can be viewed at: " + resolvePath(sessionId);
            }

            List<ChatMessageDto> summaryMessages = List.of(
                CompactConversation.buildCompactSummaryMessage(summaryContent));

            // annotateBoundaryWithPreservedSegment（sessionMemoryCompact.ts:489-493）
            CompactBoundaryMessage annotated = CompactBoundaryMessage.annotateBoundaryWithPreservedSegment(
                boundaryMarker,
                summaryMessages.get(summaryMessages.size() - 1).id(),
                messagesToKeep);

            // [IMP-CM-04] SM plan 附件注入 · CC sessionMemoryCompact.ts:484-485
            //   const planAttachment = createPlanAttachmentIfNeeded(agentId)
            //   const attachments = planAttachment ? [planAttachment] : []
            // 数据源 PlanProvider（显式注入优先，否则按 sessionId 回落 PlanProviderImpl 读磁盘，
            // 对齐 PostCompactAttachmentRestorer.resolvePlanProvider）；无 plan 文件 → 空（CC 语义）。
            // 传统路径 CompactConversation:303 populatePlanAttachment 经 typed state.attachments() 通道，
            // SM 路径无 registry 访问 → 直接注入 CompactionResult.attachments（buildPostCompactMessages
            // :1327/:1343 两阶段均携带，CC :600-602 postCompactTokenCount 计 postCompactMessages 含附件）。
            List<ChatMessageDto> attachments = buildPlanAttachments(agentId, sessionId);

            // ── CC 两阶段（sessionMemoryCompact.ts:487-502 → :600-620）──
            // 阶段 1: 构造结果（postCompactTokenCount 先用摘要初值占位，CC :500-502）
            CompactionResult baseResult = new CompactionResult(
                annotated,
                summaryMessages,
                attachments,                                 // [IMP-CM-04] plan_file_reference 附件
                hookResults,                                 // [SM-02] SessionStart hooks 结果（GAP-3）
                messagesToKeep,
                null,                                        // userDisplayMessage
                preCompactTokenCount,
                tokenEstimator.estimateMessageTokens(summaryMessages),
                tokenEstimator.estimateMessageTokens(summaryMessages),
                null);                                       // compactionUsage

            // 阶段 2（CC :600-620）: postCompactTokenCount/truePostCompactTokenCount
            // = estimateMessageTokens(postCompactMessages)（block 统计 + ×4/3 padding，
            // 全 postCompact 消息）覆盖初值 —— OPD-R2-SM-01 DRIFT-7 修复：旧实现结果字段 =
            // rough(summaryMessages)（len/4 无 padding、仅摘要）且阈值比较不覆盖结果字段。
            int postCompactTokenCount = tokenEstimator.estimateMessageTokens(
                CompactionResult.buildPostCompactMessages(baseResult));
            CompactionResult result = new CompactionResult(
                annotated, summaryMessages, attachments, hookResults, messagesToKeep, null,
                preCompactTokenCount, postCompactTokenCount, postCompactTokenCount, null);
            if (autoCompactThreshold != null && postCompactTokenCount >= autoCompactThreshold) {
                // [IMP-CM-17] CC sessionMemoryCompact.ts:609-613
                //   logEvent('tengu_sm_compact_threshold_exceeded', {postCompactTokenCount, autoCompactThreshold})
                emitTelemetry("tengu_sm_compact_threshold_exceeded", java.util.Map.of(
                    "postCompactTokenCount", postCompactTokenCount,
                    "autoCompactThreshold", autoCompactThreshold));
                log.info("[SessionMemory] tengu_sm_compact_threshold_exceeded: post={} threshold={} session={}",
                    postCompactTokenCount, autoCompactThreshold, sessionId);
                return null;
            }

            log.info("[SessionMemory] trySessionMemoryCompaction 成功: session={} agent={} keep={} preTokens={}",
                sessionId, agentId, messagesToKeep.size(), preCompactTokenCount);
            return result;
        } catch (Exception e) {
            // CC 期望内错误（文件/路径问题）→ 返回 null，不污染错误日志（:621-629）
            // [IMP-CM-17] CC sessionMemoryCompact.ts:624 logEvent('tengu_sm_compact_error', {})
            //   —— 注释明确 "Use logEvent instead of logError since errors here are expected"
            emitTelemetry("tengu_sm_compact_error", java.util.Map.of());
            log.warn("[SessionMemory] tengu_sm_compact_error: session={} err={}", sessionId, e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-04] SM plan 附件注入 · CC sessionMemoryCompact.ts:484-485
    // ════════════════════════════════════════════════════════════════════

    /**
     * SM 压缩结果 attachments 构造 · 对齐 CC sessionMemoryCompact.ts:484-485
     * {@code const planAttachment = createPlanAttachmentIfNeeded(agentId);
     * const attachments = planAttachment ? [planAttachment] : []}。
     *
     * <p>数据源经 {@link PlanProvider#createPlanAttachmentIfNeeded(UUID)} 读磁盘 plan 文件
     * （plans.ts:119-145 getPlanFilePath / getPlan）；无 plan 文件 / provider 不可解析 →
     * 空列表（CC createPlanAttachmentIfNeeded 返回 null → attachments=[]）。
     *
     * <p><b>provider 解析</b>：显式注入的 {@link #planProvider} 优先；未注入 → 按 sessionId
     * 回落构造 {@link PlanProviderImpl}（默认 plans 目录），对齐
     * {@link PostCompactAttachmentRestorer#resolvePlanProvider}（传统路径同源语义）。
     *
     * @param agentId   当前 agent ID（null = 主会话；非 UUID → 主会话语义）
     * @param sessionId 会话 ID（planProvider 未注入时回落构造 PlanProviderImpl 的 slug 源）
     * @return plan_file_reference 附件消息列表；无 plan → 空
     */
    private List<ChatMessageDto> buildPlanAttachments(String agentId, String sessionId) {
        PlanProvider provider = planProvider;
        if (provider == null) {
            // [session-id-short] sessionId 已 short，PlanProviderImpl 直收 String（不再 UUID.fromString）
            provider = new PlanProviderImpl(sessionId);
        }
        UUID agentUuid = parseUuidOrNull(agentId);
        AttachmentMessageDto.PlanRef planRef = provider.createPlanAttachmentIfNeeded(agentUuid);
        if (planRef == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] SM plan 附件: 无 plan 文件，结果 attachments 为空（agentId={}）"
                    + " · CC createPlanAttachmentIfNeeded null", agentId);
            }
            return List.of();
        }
        ChatMessageDto planAttachment = PostCompactAttachmentRestorer.planFileReferenceMessage(planRef);
        log.info("[SessionMemory] SM 结果注入 plan_file_reference 附件（agentId={} path={} chars={}）"
            + " · CC sessionMemoryCompact.ts:484-485",
            agentId, planRef.planFilePath(),
            planRef.planContent() == null ? 0 : planRef.planContent().length());
        return List.of(planAttachment);
    }

    /**
     * [IMP-CM-04] agentId → UUID（null / 空白 / 非法 → null = 主会话语义）· 对齐
     * {@link PostCompactAttachmentRestorer#parseUuidOrNull}。
     */
    private static UUID parseUuidOrNull(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // getSessionMemoryContent · 对齐 CC sessionMemoryUtils.ts:110-126
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取 session memory 文件内容 · 对齐 CC {@code getSessionMemoryContent}
     * （sessionMemoryUtils.ts:110-126）。
     *
     * <p><b>[FIX-SM] 读错误语义五类对齐（CC errors.ts:186-195 isFsInaccessible）</b>：文件不可访问
     * （ENOENT/EACCES/EPERM/ENOTDIR/ELOOP）→ null（回落 legacy compact）；其他错误 → re-throw
     * （显式失败，不吞错）。旧 Java 仅 ENOENT（NoSuchFileException）→ null，EACCES/EPERM
     * （AccessDeniedException）会 re-throw 打断压缩（NOT_ALIGNED）。Java NIO 映射：
     * ENOENT→NoSuchFileException，EACCES/EPERM→AccessDeniedException；ENOTDIR/ELOOP 无专用
     * NIO 异常类——Unix 上 ENOTDIR 也映射为 NoSuchFileException、ELOOP 为
     * FileSystemException("Too many levels of symbolic links")；Windows ENOTDIR
     * （ERROR_DIRECTORY）为 FileSystemException("The directory name is invalid")，
     * 由 {@link #isFsInaccessibleReason} 按 reason 文本判定（G-42/DRIFT-16，X-7 联动）。</p>
     *
     * @param sessionId 会话 ID
     * @return 内容；文件不可访问 → null
     */
    public String getSessionMemoryContent(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Path filePath = resolvePath(sessionId);
        try {
            String content = Files.readString(filePath);
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] 读取 session memory 文件: path={} chars={}", filePath, content.length());
            }
            // CC sessionMemoryUtils.ts:117 tengu_session_memory_loaded（读成功才发，content_length）
            if (telemetry != null) {
                java.util.Map<String, Object> attrs =
                    java.util.Map.of("content_length", content.length());
                telemetry.recordEvent("tengu_session_memory_loaded", attrs);
                telemetry.logOTelEvent("tengu_session_memory_loaded", attrs);
            }
            return content;
        } catch (NoSuchFileException e) {
            // isFsInaccessible（ENOENT；Unix ENOTDIR 同映射为 NoSuchFileException）→ null
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] session memory 文件不存在: path={} → null（ENOENT/ENOTDIR）",
                    filePath);
            }
            return null;
        } catch (AccessDeniedException e) {
            // isFsInaccessible（EACCES/EPERM）→ null（CC errors.ts:190-191）
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemory] session memory 文件访问被拒: path={} → null（EACCES/EPERM）",
                    filePath);
            }
            return null;
        } catch (FileSystemException e) {
            // [G-42/DRIFT-16] ENOTDIR/ELOOP 形态（errors.ts:186-195 五类）→ null；
            //   其余 FileSystemException → re-throw（CC :124-125 显式失败）。
            //   Windows ENOTDIR（ERROR_DIRECTORY）→ "The directory name is invalid"；
            //   Unix ELOOP → "Too many levels of symbolic links"（JDK UnixException 映射）。
            if (isFsInaccessibleReason(e.getReason())) {
                if (log.isDebugEnabled()) {
                    log.debug("[SessionMemory] session memory 路径结构不可达: path={} reason={} → null（ENOTDIR/ELOOP）",
                        filePath, e.getReason());
                }
                return null;
            }
            throw new IllegalStateException("[SessionMemory] 读取 session memory 文件失败: " + filePath, e);
        } catch (IOException e) {
            // 其他读错误 → re-throw（CC :124-125，显式失败）
            throw new IllegalStateException("[SessionMemory] 读取 session memory 文件失败: " + filePath, e);
        }
    }

    /**
     * [G-42] isFsInaccessible 五类中 ENOTDIR/ELOOP 的 JVM reason 文本判定（errors.ts:186-195）。
     * ENOENT/EACCES/EPERM 已由 NoSuchFileException/AccessDeniedException 类型覆盖；ENOTDIR/ELOOP
     * 无专用 NIO 异常类，按 reason 判定：Unix ENOTDIR → "Not a directory"（但 Unix 上 ENOTDIR
     * 直接映射 NoSuchFileException，已由上一分支覆盖）；Windows ENOTDIR（ERROR_DIRECTORY）→
     * "The directory name is invalid"；ELOOP（Unix/JVM）→ "Too many levels of symbolic links"。
     *
     * @param reason FileSystemException.getReason()
     * @return true=五类不可访问语义
     */
    private static boolean isFsInaccessibleReason(String reason) {
        if (reason == null) {
            return false;
        }
        String r = reason.toLowerCase();
        return r.contains("not a directory")
            || r.contains("directory name is invalid")
            || r.contains("too many levels of symbolic links");
    }

    // ════════════════════════════════════════════════════════════════════
    // calculateMessagesToKeepIndex · 对齐 CC sessionMemoryCompact.ts:324-397
    // ════════════════════════════════════════════════════════════════════

    /**
     * 解析 SM 压缩阈值运行期生效值（DB &gt; Web 调参通道 &gt; DEFAULT）· [V52 token-compact-settings-fix]。
     *
     * <p>逐字段合并：DB {@code settings.sm_min_tokens / sm_min_text_block_messages / sm_max_tokens}
     * 非 null（且 &gt; 0，CompactSettingsResolver 内已过滤）优先；未配置字段回落
     * {@link #smCompactConfig}（Web 调参通道写 setSmCompactConfig 的内存值，未配置恒 DEFAULT）。
     * DB 每字段独立实时 {@code selectOneById(1)} 无缓存（前一次 PUT settings 后下一轮即生效）。
     *
     * @return 当前生效 SM 压缩阈值（永不 null）
     */
    SmCompactConfig resolveSmCompactConfig() {
        CompactSettingsResolver resolver = settingsResolver;
        if (resolver == null) {
            return smCompactConfig;
        }
        Integer dbMinTokens = resolver.smMinTokens();
        Integer dbMinTextBlockMessages = resolver.smMinTextBlockMessages();
        Integer dbMaxTokens = resolver.smMaxTokens();
        if (dbMinTokens == null && dbMinTextBlockMessages == null && dbMaxTokens == null) {
            return smCompactConfig;
        }
        return new SmCompactConfig(
            dbMinTokens != null ? dbMinTokens : smCompactConfig.minTokens(),
            dbMinTextBlockMessages != null ? dbMinTextBlockMessages : smCompactConfig.minTextBlockMessages(),
            dbMaxTokens != null ? dbMaxTokens : smCompactConfig.maxTokens());
    }

    /**
     * 计算压缩后保留消息的起始索引 · 对齐 CC {@code calculateMessagesToKeepIndex}
     * （sessionMemoryCompact.ts:324-397）。
     *
     * <p>从 lastSummarizedIndex 之后起步，向后展开直至满足双最低（minTokens / minTextBlockMessages）
     * 或触及 maxTokens 上限；floor 为最后 compact boundary 之后（preserved-segment 链的磁盘
     * 不连续点，:365-371）。最终 adjustIndexToPreserveAPIInvariants 保证 tool_use/tool_result 对
     * 与 thinking 块不被拆散。</p>
     *
     * @param messages           消息列表
     * @param lastSummarizedIndex lastSummarizedMessageId 索引（-1 或 >= length → 无保留）
     * @return 保留起始索引
     */
    int calculateMessagesToKeepIndex(List<ChatMessageDto> messages, int lastSummarizedIndex) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        // [IMP-CM-03] SM 阈值消费点：运行期生效值 = DB > Web 调参通道 > DEFAULT
        // （[V52 token-compact-settings-fix] DB 实时读无缓存；Web 通道 IMP-CM-35
        // SessionMemoryConfigChannel 写 setSmCompactConfig 仍作为回落）。
        SmCompactConfig config = resolveSmCompactConfig();

        // 从 lastSummarizedIndex 之后起步（:337-338）
        int startIndex = lastSummarizedIndex >= 0 ? lastSummarizedIndex + 1 : messages.size();

        // 计算 startIndex → end 的 token 与 text-block 消息数（:343-349）
        int totalTokens = 0;
        int textBlockMessageCount = 0;
        for (int i = startIndex; i < messages.size(); i++) {
            totalTokens += tokenEstimator.estimateMessageTokens(messages.get(i));
            if (hasTextBlocks(messages.get(i))) {
                textBlockMessageCount++;
            }
        }

        // 已达 max cap（:351-354）
        if (totalTokens >= config.maxTokens()) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }
        // 已满足双最低（:356-362）
        if (totalTokens >= config.minTokens()
            && textBlockMessageCount >= config.minTextBlockMessages()) {
            return adjustIndexToPreserveAPIInvariants(messages, startIndex);
        }

        // floor：最后 compact boundary 之后（:370-371）
        int floor = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (BoundaryReader.isCompactBoundaryMessage(messages.get(i))) {
                floor = i + 1;
                break;
            }
        }
        for (int i = startIndex - 1; i >= floor; i--) {
            ChatMessageDto msg = messages.get(i);
            totalTokens += tokenEstimator.estimateMessageTokens(msg);
            if (hasTextBlocks(msg)) {
                textBlockMessageCount++;
            }
            startIndex = i;
            if (totalTokens >= config.maxTokens()) {
                break;
            }
            if (totalTokens >= config.minTokens()
                && textBlockMessageCount >= config.minTextBlockMessages()) {
                break;
            }
        }

        return adjustIndexToPreserveAPIInvariants(messages, startIndex);
    }

    /**
     * 调整起始索引以不拆散 tool_use/tool_result 对与 thinking 块 · 对齐 CC
     * {@code adjustIndexToPreserveAPIInvariants}（sessionMemoryCompact.ts:232-314）。
     *
     * <p>Step 1：保留段内 tool_result 的 id 需在前方 assistant 找到对应 tool_use；
     * Step 2：保留段内 assistant message.id 相同的前方 assistant（thinking 块合并源）纳入。</p>
     *
     * @param messages   消息列表
     * @param startIndex 待调整起始索引
     * @return 调整后起始索引
     */
    int adjustIndexToPreserveAPIInvariants(List<ChatMessageDto> messages, int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }
        int adjustedIndex = startIndex;

        // ── Step 1: tool_use/tool_result 对（:243-286）──
        List<String> allToolResultIds = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            allToolResultIds.addAll(getToolResultIds(messages.get(i)));
        }
        if (!allToolResultIds.isEmpty()) {
            Set<String> toolUseIdsInKeptRange = new HashSet<>();
            for (int i = adjustedIndex; i < messages.size(); i++) {
                ChatMessageDto msg = messages.get(i);
                if (msg.role() == Role.assistant && msg.toolCalls() != null) {
                    for (com.nexusai.model.session.dto.ToolCallDto call : msg.toolCalls()) {
                        if (call.id() != null) {
                            toolUseIdsInKeptRange.add(call.id());
                        }
                    }
                }
            }
            Set<String> neededToolUseIds = new HashSet<>();
            for (String id : allToolResultIds) {
                if (!toolUseIdsInKeptRange.contains(id)) {
                    neededToolUseIds.add(id);
                }
            }
            for (int i = adjustedIndex - 1; i >= 0 && !neededToolUseIds.isEmpty(); i--) {
                ChatMessageDto message = messages.get(i);
                if (hasToolUseWithIds(message, neededToolUseIds)) {
                    adjustedIndex = i;
                    if (message.role() == Role.assistant && message.toolCalls() != null) {
                        for (com.nexusai.model.session.dto.ToolCallDto call : message.toolCalls()) {
                            neededToolUseIds.remove(call.id());
                        }
                    }
                }
            }
        }

        // ── Step 2: 同 message.id 的 assistant（thinking 块合并源，:288-311）──
        Set<String> messageIdsInKeptRange = new HashSet<>();
        for (int i = adjustedIndex; i < messages.size(); i++) {
            ChatMessageDto msg = messages.get(i);
            if (msg.role() == Role.assistant && msg.assistantMessageId() != null
                && !msg.assistantMessageId().isBlank()) {
                messageIdsInKeptRange.add(msg.assistantMessageId());
            }
        }
        for (int i = adjustedIndex - 1; i >= 0; i--) {
            ChatMessageDto message = messages.get(i);
            if (message.role() == Role.assistant && message.assistantMessageId() != null
                && messageIdsInKeptRange.contains(message.assistantMessageId())) {
                adjustedIndex = i;
            }
        }

        return adjustedIndex;
    }

    /**
     * 消息是否含 text 块 · 对齐 CC {@code hasTextBlocks}（sessionMemoryCompact.ts:135-150）。
     *
     * <p><b>[SM-14] string/array 互斥分支（DRIFT-21）</b>：CC 对 content 表示二选一
     * （user 先 string 后 array；assistant 恒 array）——旧实现 assistant 在 contentBlocks
     * 无 text 时回落 content() 判定、user 以 content() 优先于 contentBlocks（Java 模型
     * content 与 contentBlocks 双载状态时判定可不同，NOT_ALIGNED）。Java 映射：
     * assistant contentBlocks（= CC content 数组）非空 → 数组分支独占判定；contentBlocks
     * 空（Java 合成纯文本消息，CC 等价单 text 块）→ content 非空白。user 与 CC 同序：
     * content()（= string 分支）优先，其次 contentBlocks（= array 分支）。
     *
     * @param message 消息
     * @return true=含 text 内容
     */
    static boolean hasTextBlocks(ChatMessageDto message) {
        if (message == null) {
            return false;
        }
        if (message.role() == Role.assistant) {
            // CC :136-139 assistant content 恒数组 → some(block.type==='text')；数组分支独占。
            //   Java contentBlocks 非空 = CC content 数组 → 数组分支判定；contentBlocks 空/null
            //   = Java 合成纯文本消息（DTO 双字段，字符串即有效载荷，CC 等价 [text] 单块）
            //   → string 分支判定（content 非空白）。
            if (message.contentBlocks() != null && !message.contentBlocks().isEmpty()) {
                return containsTextBlock(message.contentBlocks());
            }
            return message.content() != null && !message.content().isBlank();
        }
        if (message.role() == Role.user) {
            // CC :141-147 user string/array 互斥分支（string 优先，CC 同序）
            if (message.content() != null) {
                return message.content().length() > 0;
            }
            if (message.contentBlocks() != null) {
                return containsTextBlock(message.contentBlocks());
            }
        }
        return false;
    }
    /** [SM-14] contentBlocks 数组是否含 text 块（CC block.type === 'text'）。 */
    // [A-8 登记 · IMP-MV2-40] △-4：node.has("text") 近似 CC block.type==='text'（sessionMemoryCompact.ts
    //   锚点）——实际 contentBlocks 中仅 text 块携带 text 字段，缺字段的理论边界差异极低，登记不修。
    private static boolean containsTextBlock(List<?> contentBlocks) {
        for (Object block : contentBlocks) {
            if (block instanceof com.fasterxml.jackson.databind.JsonNode node
                && node.isObject() && node.has("text")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 消息的 tool_result id 列表 · 对齐 CC {@code getToolResultIds}（sessionMemoryCompact.ts:155-170）。
     * Java role=tool 消息经 toolCallId 关联其 tool_use（CC tool_result 块在 user 消息）。
     *
     * @param message 消息
     * @return tool_result 关联的 tool_use id
     */
    private static List<String> getToolResultIds(ChatMessageDto message) {
        if (message == null || message.role() != Role.tool) {
            return List.of();
        }
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            return List.of(message.toolCallId());
        }
        return List.of();
    }

    /**
     * 消息是否含指定 tool_use id · 对齐 CC {@code hasToolUseWithIds}（sessionMemoryCompact.ts:175-186）。
     *
     * @param message     消息
     * @param toolUseIds  待匹配 tool_use id 集
     * @return true=含任一匹配 tool_use
     */
    private static boolean hasToolUseWithIds(ChatMessageDto message, Set<String> toolUseIds) {
        if (message == null || message.role() != Role.assistant || message.toolCalls() == null) {
            return false;
        }
        for (com.nexusai.model.session.dto.ToolCallDto call : message.toolCalls()) {
            if (call.id() != null && toolUseIds.contains(call.id())) {
                return true;
            }
        }
        return false;
    }

    /** 在消息列表中查找指定 messageId 的索引。 */
    private static int findMessageIndex(List<ChatMessageDto> messages, String messageId) {
        if (messageId == null) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    /** CC isEnvTruthy · 接受 "1"/"true"/"yes"/"on" 等 truthy 字符串（envUtils.ts:32-36）。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim().toLowerCase();
        // [SM-08] 补 'on'（DRIFT-11）· CC envUtils.ts:36 `['1','true','yes','on']`
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    /**
     * session memory 文件路径 · 对齐 CC {@code getSessionMemoryPath()}
     * （filesystem.ts:261-271）{@code {projectDir}/{sessionId}/session-memory/summary.md}。
     *
     * <p><b>[sm-reloc 2026-09-02]</b> 落点改 per-session：
     * <ul>
     *   <li>{@code sessionBaseDirResolver} 非 null（生产 = {@code SessionStorage::sessionProjectDir}
     *       = {@code {configHome}/projects/{sanitize(稳定锚 boundProject/originalCwd)}}，与 transcript
     *       同根分层；绑定项目也不写进用户项目真实目录）→
     *       {@code {projects/{slug}}/{sessionId}/session-memory/summary.md}</li>
     *   <li>resolver null = legacy 固定 baseDir（1-arg 构造 / 测试回落），Web 后端无
     *       getProjectDir(getCwd())（CC :261-264）以注入 baseDir 替代（OPD-M-25 登记）——
     *       {@code {baseDir}/{sessionId}/session-memory/summary.md}；sessionId null →
     *       {@code {baseDir}/session-memory/summary.md}（无会话分支）</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @return 文件绝对路径
     */
    Path resolvePath(String sessionId) {
        if (sessionId != null && sessionBaseDirResolver != null) {
            Path slugDir = sessionBaseDirResolver.apply(sessionId);
            if (slugDir != null) {
                return slugDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
            }
        }
        if (sessionId == null) {
            return baseDir.resolve("session-memory").resolve("summary.md");
        }
        return baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
    }

    /** 从 ToolUseContext 解析 sessionId（CC getSessionId() 等价；null → "unknown"）。 */
    private static String sessionIdFrom(ToolUseContext toolUseContext) {
        if (toolUseContext != null && toolUseContext.sessionId() != null) {
            return toolUseContext.sessionId();
        }
        return "unknown";
    }

    /**
     * 当前上下文窗口 token 估算 · 对齐 CC tokenCountWithEstimation（tokens.ts:226-260）
     * usage 四通道 + sibling 回溯 + rough 尾段。
     *
     * <p>OPD-R2-SM-01（DRIFT-1 修复）: 直接委托 canonical {@link Tokens} 完整镜像
     * （旧实现经 CompactConversation 简化版 = 仅 input+output 无回溯 → SM 提取阈值系统性低估；
     * CompactConversation 现已同源委托 Tokens，双路径同一实现）。
     */
    static int tokenCountWithEstimation(List<ChatMessageDto> messages) {
        return Tokens.tokenCountWithEstimation(messages);
    }

    /** fork 提取 prompt 的 user 消息（对齐 CC createUserMessage({content})）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /**
     * 兜底（RES-C5）会话 systemPrompt 数组原料 · 对齐 CC REPLHookContext.systemPrompt
     * （postSamplingHooks.ts:13 → forkedAgent.ts:131 createCacheSafeParams）。
     *
     * <p><b>WHY</b>: 主会话 cache-safe params 未注入时，兜底 CacheSafeParams 的 systemPrompt
     * 不再恒空 —— 取 PostSamplingContext（REPLHookContext 等价）携带的会话 systemPrompt。
     *
     * <p><b>[IMP-HOOKS-S7 D3]</b>: 旧实现自称"PostSamplingContext.systemPrompt 为 String
     * （RunRequest 自定义提示，非完整组装数组），故单元素 List 表达；完整组装数组需系统
     * 提示组装链，出本 session 范围"—— 该偏差已消除：LlmAgentLoop :4144 现传组装段数组
     * fullSystemPrompt（appendSystemContext 产物，含 boundary 段，对齐 CC query.ts:1001-1008
     * systemPrompt 段数组），此处直接 {@code List.copyOf} 直通（空 → 空 List 降级，不抛错）。
     *
     * @param psContext post-sampling hook 上下文（REPLHookContext 等价；null → 降级空）
     * @return 会话 systemPrompt 数组（原样拷贝；null/空 → 空 List）
     */
    private static List<String> sessionSystemPrompt(PostSamplingContext psContext) {
        if (psContext == null || psContext.systemPrompt() == null
                || psContext.systemPrompt().isEmpty()) {
            return List.of();
        }
        return List.copyOf(psContext.systemPrompt());
    }

    /**
     * 合并 systemPrompt 原料（RES-C5 rework）· 生产 supplier 分支专用。
     *
     * <p><b>WHY</b>: {@code ToolRegistrationConfig.buildProductionCacheSafeParams}
     * 的 systemPrompt 恒 {@code List.of()}（空占位，唯一有效载荷是 toolUseContext 工具集），
     * 消费方 {@code supplied != null} 分支恒胜 → 若不合并，生产 fork systemPrompt 仍空、
     * 缓存 key 与主循环不一致（REQ-C5-1）。合并语义：supplied 非空（未来 C2/C10 接线方注入
     * 完整组装数组）保留原值；空 → 用 psContext 会话原料（REPLHookContext.systemPrompt 等价
     * forkedAgent.ts:131）。
     *
     * @param supplied supplier 注入值（生产 = List.of() 占位；未来 = 组装链完整数组）
     * @param session  psContext 会话原料（null-safe：无 → List.of()）
     * @return supplied 非空 → supplied；否则 session
     */
    private static List<String> mergeSystemPrompt(List<String> supplied, List<String> session) {
        return supplied != null && !supplied.isEmpty() ? supplied : session;
    }

    /**
     * 合并 userContext/systemContext 原料（RES-C5 rework）· 生产 supplier 分支专用。
     *
     * <p><b>WHY</b>: 同 {@link #mergeSystemPrompt} —— buildProductionCacheSafeParams 的
     * userContext/systemContext 恒 {@code Map.of()}（forkedAgent.ts:61/63 cache key 组成部分），
     * 空 → 用 psContext 会话原料合并，保证 fork 与主循环 cache key 对齐。
     *
     * @param supplied supplier 注入值（生产 = Map.of() 占位）
     * @param session  psContext 会话原料（null-safe：无 → Map.of()）
     * @return supplied 非空 → supplied；否则 session
     */
    private static Map<String, String> mergeContext(Map<String, String> supplied, Map<String, String> session) {
        return supplied != null && !supplied.isEmpty() ? supplied : session;
    }
}

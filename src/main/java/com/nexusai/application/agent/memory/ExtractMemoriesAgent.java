package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkRawMaterial;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.config.MemoryRemoteModeConfig;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 记忆提取 Agent · 对齐 CC services/extractMemories/extractMemories.ts。
 *
 * <p><b>IMP-M-P0-3 重建（DEL-M-43..48）</b>：教学版单次 chat + JSON 数组契约
 * （recentlyWrittenFiles/EXTRACT_PROMPT/llmCallback/formatRecentMessages/
 * isValidCandidate/parseCandidates）已删除，重建为 CC forked-agent：
 * <ol>
 *   <li><b>游标（INV-4）</b>：{@link #lastMemoryMessageUuidBySession} 记录已处理到最后一条消息，
 *       每轮只 count 新增消息（{@link #countModelVisibleMessagesSince}）。游标成功推进 /
 *       失败不动 / compact 移除后回退全量计数。
 *       [sm-cursor-sessionize 2026-08-30] 按 sessionId 键控（CC 单会话闭包 let → Web 多会话
 *       Map，见 {@link #cursorKey}）。</li>
 *   <li><b>主/背景互斥（INV-5）</b>：主 agent 已写 auto-memory（{@link #hasMemoryWritesSince}）
 *       时后台 fork 冗余 → 跳过 fork + 推进游标（extractMemories.ts:345-360）。</li>
 *   <li><b>受限 forked-agent（INV-6）</b>：{@link #createAutoMemCanUseTool} 只允许
 *       Read/Grep/Glob + 只读 Bash + auto-memory 目录内 Edit/Write；skipTranscript=true +
 *       maxTurns=5（extractMemories.ts:415-427）。</li>
 *   <li><b>节流</b>：turnsSinceLastExtraction ≥ tengu_bramble_lintel（默认 1）才运行。</li>
 * </ol>
 *
 * <p><b>[sm 决策 2026-08-30] 提取总闸由 env 移至 DB（DB 主控）</b>：旧 env
 * {@code NEXUSAI_EXTRACT_MEMORIES}（默认 false）作总闸 → 前端 DB 配了但 env 没开 = 永不提取。
 * 现总闸 = DB settings 列 {@code auto_memory_enabled}（{@link #autoMemoryEnabled} 默认
 * {@link BundledSkillEnabledGates#isAutoMemoryEnabled()}，默认 true），直接 DB 改即生效；env
 * {@code NEXUSAI_EXTRACT_MEMORIES} 降级为可选强制关/开运维覆盖（{@link #extractionGate}，
 * null = 不影响）。非开关 env {@code NEXUSAI_EXTRACT_MEMORIES_INTERVAL}（节流间隔）保留。
 *
 * <p><b>IMP-M-P0-3b 生命周期对齐（REQ-M-18 条件缺口）</b>：
 * <ol>
 *   <li><b>memory_saved 系统消息</b>（extractMemories.ts:490-496 + messages.ts:4460-4471）：
 *       memoryPaths&gt;0 时经 appendSystemMessage 回调追加 type=system subtype=memory_saved
 *       writtenPaths（{@link SystemMessage#memorySaved}）。</li>
 *   <li><b>trailing run</b>（extractMemories.ts:557-564 / :503-522）：in-progress 时 stash
 *       pendingContext（覆盖旧值），finally 后取走尾随一轮（isTrailingRun=true 跳过节流）。</li>
 *   <li><b>drainPendingExtraction</b>（extractMemories.ts:579-586，print.ts:968 等价）：
 *       [rev2 EX-01] 仅 headless 类退出路径调用（Web 端非交互会话语义：应用关闭时
 *       {@link #shutdown()} @PreDestroy drain；每轮退出不阻塞），等待 in-flight+trailing
 *       完成（60s 软超时）。</li>
 *   <li><b>telemetry</b>：tengu_extract_memories_coalesced（:561）+ tengu_extract_memories_gate_disabled
 *       （:539，hasLoggedGateFailure 一次性）。</li>
 * </ol>
 *
 * <p><b>注入 seam（IMP-M-P0-3 生产接线）</b>：CC 调全局 query()；Java 经
 * {@link RunForkedAgent.ForkedQuery} 函数式 seam 注入（测试注入 RecordingQuery；生产
 * 由 ToolRegistrationConfig.extractMemoriesAgent 注入 {@link ProductionForkedQuery}
 * 专用多轮 fork loop，canUseTool 受限门控真实生效 INV-6）。{@link CacheSafeParams}
 * 由 {@link #cacheSafeParamsSupplier} 注入（null → RunForkedAgent.createMinimalCacheSafeParams
 * 5 参兜底 —— [RES-C5] systemPrompt/gate 原料由调用方注入、无工具集；生产 supplier 必须
 * 携带主线程工具集，见 ToolRegistrationConfig）。
 */
public class ExtractMemoriesAgent {

    private static final Logger log = LoggerFactory.getLogger(ExtractMemoriesAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC ENTRYPOINT_NAME（memdir/memdir.ts）· 索引文件不计入"已保存记忆"数 */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";

    // ── CC extractMemories.ts:299-325 closure-scoped 可变状态 ──
    /**
     * 所有未完成的提取 promise · CC original: {@code inFlightExtractions = new Set<Promise<void>>}
     * （extractMemories.ts:303）。coalesced 调用 stash-and-return 加快速 resolve 的 future（无害）；
     * 真正启动工作的调用加覆盖完整 trailing-run 链的 future（runExtraction 递归 finally）。
     * {@link #drainPendingExtraction(long)} 等待该集合清空。
     */
    private final Set<CompletableFuture<Void>> inFlightExtractions = ConcurrentHashMap.newKeySet();
    /**
     * 游标：已处理到的最后一条消息 UUID · CC original: lastMemoryMessageUuid
     * (extractMemories.ts:307)。
     *
     * <p>[sm-cursor-sessionize 2026-08-30] 按 sessionId 键控 —— CC 单会话闭包 let 变量在 Web
     * 多会话下是跨会话共享状态（A 会话推进游标后 B 会话只处理其"新增"消息 → 漏提取；同类串扰
     * 见 sm 修复）。{@link #cursorKey} 对 null 用 "unknown" 兜底键。
     */
    private final Map<String, String> lastMemoryMessageUuidBySession = new ConcurrentHashMap<>();
    /**
     * 一次性 gate_disabled 事件标志 · CC original: hasLoggedGateFailure (extractMemories.ts:310)。
     * [sm-cursor-sessionize] 按 sessionId 键控（A 会话触发后不得抑制 B 会话同一事件）。
     */
    private final Map<String, Boolean> hasLoggedGateFailureBySession = new ConcurrentHashMap<>();
    /**
     * 防重叠运行标志 · CC original: inProgress (extractMemories.ts:313)。
     * [sm-cursor-sessionize] 按 sessionId 键控（A 会话在跑时不得让 B 会话 stash 进 A 的尾随链）。
     */
    private final Map<String, Boolean> inProgressBySession = new ConcurrentHashMap<>();
    /**
     * 自上次提取起可提取的 turn 数 · CC original: turnsSinceLastExtraction (extractMemories.ts:316)
     * · [sm-cursor-sessionize] 按 sessionId 键控。
     */
    private final Map<String, Integer> turnsSinceLastExtractionBySession = new ConcurrentHashMap<>();
    /**
     * 防重叠运行时 stash 的待处理上下文（覆盖旧值，仅最新有用）· CC original:
     * {@code pendingContext}（extractMemories.ts:320-325）。finally 中取走启动尾随一轮。
     * [sm-cursor-sessionize] 按 sessionId 键控（A 会话 stash 不得被 B 会话尾随轮消费）。
     */
    private final Map<String, PendingContext> pendingContextBySession = new ConcurrentHashMap<>();
    /**
     * 节流间隔（tengu_bramble_lintel，默认 1 = 每轮）· CC original:
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_bramble_lintel', null) ?? 1}
     * （extractMemories.ts:380-385，每 run 读）。
     *
     * <p>[rev2 EX-02/OPD-R2-EX-02] 部署 env 通道：{@code NEXUSAI_EXTRACT_MEMORIES_INTERVAL}
     * （property 优先 → env），非法/缺省 → 1。每 run 读（IntSupplier）对齐 CC 每 run 读
     * feature 语义；setExtractionInterval 测试 seam 覆写为常量 supplier。
     */
    private volatile IntSupplier extractionInterval = () -> extractionIntervalFromEnv();

    /**
     * 待处理上下文 record · CC original:
     * {@code {context: REPLHookContext, appendSystemMessage?: AppendSystemMessageFn}}
     * （extractMemories.ts:320-325）。Java 端 context.messages 降级为消息快照列表。
     */
    private record PendingContext(List<ChatMessageDto> messages,
                                  Consumer<SystemMessage> appendSystemMessage,
                                  ForkRawMaterial forkRawMaterial) {}

    private final MemoryStorage storage;

    // ── 注入 seam ──
    /** fork 查询 seam · 测试注入 RecordingQuery；生产接 LlmAgentLoop queryLoop（R9/IMP-18） */
    private volatile RunForkedAgent.ForkedQuery forkedQuery;
    /** cache-safe params 供应 · null → createMinimalCacheSafeParams 5 参兜底（RES-C5：systemPrompt/gate 原料由调用方注入） */
    private volatile Supplier<CacheSafeParams> cacheSafeParamsSupplier;
    /**
     * firstParty fork 缓存共享 gate 供应 · 兜底（RES-C5）CacheSafeParams 的
     * {@code useGlobalCacheScope} 来源 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)；由接线方经 GlobalCacheScope 单实现求值注入
     * （REQ-C5-4）；默认 false = Java 3P 默认（boundary 不插入）。
     */
    private volatile Supplier<Boolean> useGlobalCacheScopeSupplier = () -> false;
    /**
     * isAutoMemoryEnabled 门控 · <b>[sm 决策 2026-08-30] 提取总闸（DB 主控）</b>。
     * CC original: {@code isAutoMemoryEnabled()}（extractMemories.ts:545，memdir/paths.ts:30-56）。
     * 默认 {@link BundledSkillEnabledGates#isAutoMemoryEnabled()}（DB settings 列 auto_memory_enabled
     * 优先 · SettingsService @PostConstruct 桥接，默认 true）—— 直接 DB 改即生效，无需 env。
     * 旧 env 总闸 {@code NEXUSAI_EXTRACT_MEMORIES}（默认 false）降级为可选强制关/开覆盖
     * {@link #extractionGate}。在 {@link #executeExtractMemoriesImpl} 入口检查（对齐 CC
     * executeExtractMemoriesImpl:545，不在 runExtraction 内）。
     */
    private volatile BooleanSupplier autoMemoryEnabled = BundledSkillEnabledGates::isAutoMemoryEnabled;

    /**
     * remote mode 门控 · CC original: {@code getIsRemoteMode()}（extractMemories.ts:550，
     * bootstrap/state.ts:1631-1633）。默认 {@link MemoryRemoteModeConfig#isRemoteMode()}
     * （{@code nexusai.memory.remote-mode} 配置，默认 false 对齐 CC state.ts:390）。
     * 测试可经 {@link #setRemoteMode} 注入覆盖。
     */
    private volatile BooleanSupplier remoteMode = MemoryRemoteModeConfig::isRemoteMode;

    /**
     * 提取门控（可选运维覆盖）· <b>[sm 决策 2026-08-30] 不再作总闸</b>。
     *
     * <p><b>总闸已移至 {@link #autoMemoryEnabled}（DB settings 列 auto_memory_enabled，默认 true）</b>
     * —— 前端 DB 配了即生效，无需 env（旧实现 env {@code NEXUSAI_EXTRACT_MEMORIES} 默认 false 作
     * 总闸 → 前端 DB 配了但 env 没开 = 永不提取）。
     *
     * <p>本字段保留为 env {@code NEXUSAI_EXTRACT_MEMORIES} 的 tri-state 运维覆盖（null = 未设，
     * 不影响）：
     * <ul>
     *   <li>null → 交 {@link #autoMemoryEnabled}（DB 主控）判定</li>
     *   <li>Boolean.TRUE → 强制开（绕过 DB gate）</li>
     *   <li>Boolean.FALSE → 强制关（发 tengu_extract_memories_gate_disabled + 跳过，CC
     *       extractMemories.ts:536-542 语义）</li>
     * </ul>
     * CC original: {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_passport_quail', false)}
     * （extractMemories.ts:536，Java 无 GrowthBook → env 近似建模）。测试可经
     * {@link #setExtractionGate} 注入强制覆盖。
     */
    //   [D-6 登记 · IMP-MV2-40] △-6：CC GrowthBook flag → env/property 通道建模；
    //   [sm 决策 2026-08-30] 由总闸降级为可选运维覆盖（DB 主控提位，默认不再需要 env）。
    private volatile Supplier<Boolean> extractionGate = () -> extractionEnvOverride();

    /**
     * USER_TYPE==='ant' · CC original: {@code process.env.USER_TYPE === 'ant'}
     * （extractMemories.ts:537）。Java 无 USER_TYPE env 对应 → 用 {@code NEXUSAI_USER_TYPE}
     * 近似建模（concern 拍板项）。gate 关闭且用户为 ant 时一次性发
     * tengu_extract_memories_gate_disabled。测试可经 {@link #setUserTypeIsAnt} 注入覆盖。
     */
    //   [D-6 登记 · IMP-MV2-40] △-6：USER_TYPE==='ant' env 近似建模（concern 拍板项）。
    private volatile BooleanSupplier userTypeIsAnt = () -> "ant".equals(resolveEnv("NEXUSAI_USER_TYPE"));

    /**
     * skipIndex 门控 · CC original: {@code getFeatureValue_CACHED_MAY_BE_STALE
     * ('tengu_moth_copse', false)}（extractMemories.ts:366-369）。
     * IMP-MV2-12（单轨收敛）: 生产由 ToolRegistrationConfig.extractMemoriesAgent bean
     * {@link #setSkipIndexGate} 接线 FeatureFlags.tenguMothCopse（nexusai.feature.tengu-moth-copse
     * 属性）——与 loadMemoryPrompt skipIndex / 预取门控 / claudemd 过滤同一 flag 源；
     * 本字段默认值 {@code NEXUSAI_EXTRACT_MEMORIES_SKIP_INDEX} env/system property 仅作
     * 未注入兜底（测试直接 new 的场景；默认 false 对齐 CC flag 缺省关闭）。
     * skipIndex=true → prompts.ts:56-66 单步 howToSave（只写文件不更新 MEMORY.md 索引）；
     * false → prompts.ts:68-82 两步 howToSave（写文件 + MEMORY.md 加索引行）。
     * 测试可经 {@link #setSkipIndexGate} 注入覆盖。
     */
    //   [D-6 登记 · IMP-MV2-40] △-6：skipIndex GB flag → env 通道；默认 false 一致。
    private volatile BooleanSupplier skipIndexGate = () -> isEnvTruthy(resolveEnv("NEXUSAI_EXTRACT_MEMORIES_SKIP_INDEX"));

    // ── 测试观察点（[sm-cursor-sessionize] 按 sessionId 键控）──
    private final Map<String, ForkedAgentParams> lastForkParamsBySession = new ConcurrentHashMap<>();
    private final Map<String, ExtractResult> lastResultBySession = new ConcurrentHashMap<>();

    /** 遥测注入 · null → 不发射（测试不注入时静默跳过，对齐 CC logEvent 可空上下文）。 */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    public ExtractMemoriesAgent(MemoryStorage storage) {
        this.storage = storage;
    }

    /**
     * auto-memory 目录（尾分隔符）· CC original: {@code getAutoMemPath()} (extractMemories.ts:339)。
     *
     * <p><b>[A1 重做 2026-09-04]</b>：本方法仅作<b>兜底/测试</b>入口（5 参便捷 executeExtractMemories
     * 委托、6 参 memoryDir=null 兜底）—— storage 冻结 Path（测试）时安全；生产 StopHookPipeline
     * 走 6 参带会话线程解析的 memoryDir，fork 内用参数不调本方法（避免异步 ForkJoinPool 无
     * ThreadLocal 回落 config-home 自身 slug 写错目录 —— 原 A1 惰性现算依赖当前线程 projectRoot，
     * 用户否决该隐式线程依赖，改参数直传）。</p>
     */
    private String memoryDir() {
        String dir = storage.memoryDir().toString();
        return (dir.endsWith("/") || dir.endsWith("\\")) ? dir : dir + java.io.File.separator;
    }

    public void setForkedQuery(RunForkedAgent.ForkedQuery query) {
        this.forkedQuery = query;
    }

    /** 注入遥测（tengu_extract_memories_* / tengu_auto_mem_tool_denied · extractMemories.ts:356/473/500/156）。 */
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    public void setCacheSafeParamsSupplier(Supplier<CacheSafeParams> supplier) {
        this.cacheSafeParamsSupplier = supplier;
    }

    /**
     * 注入 firstParty fork 缓存共享 gate 供应（RES-C5）· 兜底 CacheSafeParams 的
     * useGlobalCacheScope 来源 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)；由接线方经 GlobalCacheScope 单实现求值注入；
     * null → 保持默认 false（3P 默认）。
     */
    public void setUseGlobalCacheScopeSupplier(Supplier<Boolean> supplier) {
        if (supplier != null) {
            this.useGlobalCacheScopeSupplier = supplier;
        }
    }

    /**
     * 遥测双发射 · CC original: {@code logEvent}（extractMemories.ts:356/473/500/156）。
     *
     * <p>recordEvent（1P/Statsig 适配层计数）+ logOTelEvent（OTel 真实出事件 · Telemetry.java:197-229）
     * 双发射 · HookRegistry:278-279 惯例。telemetry 未注入（null）时静默跳过（对齐 CC logEvent
     * 可空上下文）。
     *
     * @param eventName  tengu_extract_memories_* / tengu_auto_mem_tool_denied
     * @param attributes 事件属性（CC logEvent metadata；null → 空）
     */
    private void emitTelemetry(String eventName, Map<String, ?> attributes) {
        if (telemetry == null) {
            return;
        }
        Map<String, Object> attrs = attributes == null
            ? Map.of() : new java.util.HashMap<>(attributes);
        telemetry.recordEvent(eventName, attrs);
        telemetry.logOTelEvent(eventName, attrs);
    }

    /** 注入 isAutoMemoryEnabled 门控（null → false，CC paths.ts:30-56 gate 关闭）。 */
    public void setAutoMemoryEnabled(BooleanSupplier enabled) {
        this.autoMemoryEnabled = enabled != null ? enabled : () -> false;
    }

    /**
     * 注入 remote mode 门控（null → false，CC extractMemories.ts:550 getIsRemoteMode 关闭）。
     * 测试用它确定性地触发 remote 跳过分支，避免依赖 nexusai.memory.remote-mode 配置。
     */
    public void setRemoteMode(BooleanSupplier remoteMode) {
        this.remoteMode = remoteMode != null ? remoteMode : () -> false;
    }

    /**
     * 注入提取门控覆盖（tri-state）· <b>[sm 决策 2026-08-30]</b> 总闸已由 env 移至 DB
     * auto_memory_enabled，本 seam 语义随 {@link #extractionGate} 更新：
     * true = 强制开（绕过 DB gate）/ false = 强制关（发 gate_disabled + 跳过）；
     * null → 恢复默认 env 解析（无覆盖 → 交 DB 主控 auto_memory_enabled）。旧语义
     * "null → false（gate 关闭）"已废弃 —— 测试现注入恒 true/false 确定性强制开关。
     */
    public void setExtractionGate(BooleanSupplier gate) {
        this.extractionGate = gate != null ? () -> gate.getAsBoolean() : () -> extractionEnvOverride();
    }

    /** 注入 skipIndex 门控（null → false，CC extractMemories.ts:366-369 tengu_moth_copse 默认关闭）。 */
    public void setSkipIndexGate(BooleanSupplier skipIndex) {
        this.skipIndexGate = skipIndex != null ? skipIndex : () -> false;
    }

    /** 注入 USER_TYPE==='ant' 判定（null → false，CC extractMemories.ts:537 恒 ant 判定关闭）。 */
    public void setUserTypeIsAnt(BooleanSupplier ant) {
        this.userTypeIsAnt = ant != null ? ant : () -> false;
    }

    /**
     * 设置节流间隔（tengu_bramble_lintel）· 默认 1（每轮）· 测试 seam：
     * 覆写为常量 supplier（生产默认走 NEXUSAI_EXTRACT_MEMORIES_INTERVAL env 通道）。
     */
    public void setExtractionInterval(int interval) {
        this.extractionInterval = () -> Math.max(1, interval);
    }

    /** 测试观察点：当前游标（默认会话键 "unknown"）。 */
    public String getLastMemoryMessageUuid() {
        return getLastMemoryMessageUuid(null);
    }

    /** 测试观察点：指定会话游标 · [sm-cursor-sessionize] 按 sessionId 键控。 */
    public String getLastMemoryMessageUuid(String sessionId) {
        return lastMemoryMessageUuidBySession.get(cursorKey(sessionId));
    }

    /** 测试观察点：最近一次构造的 fork 参数（默认会话键 "unknown"）。 */
    public ForkedAgentParams lastForkParams() {
        return lastForkParams(null);
    }

    /** 测试观察点：指定会话最近一次构造的 fork 参数。 */
    public ForkedAgentParams lastForkParams(String sessionId) {
        return lastForkParamsBySession.get(cursorKey(sessionId));
    }

    /** 测试观察点：最近一次提取结果（默认会话键 "unknown"）。 */
    public ExtractResult lastResult() {
        return lastResult(null);
    }

    /** 测试观察点：指定会话最近一次提取结果。 */
    public ExtractResult lastResult(String sessionId) {
        return lastResultBySession.get(cursorKey(sessionId));
    }

    // ════════════════════════════════════════════════════════════════════
    // 公开入口 · 对齐 CC extractor/executeExtractMemoriesImpl/drainer
    // （extractMemories.ts:527-586 / :598-615）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 异步提取入口（fire-and-forget）· 对齐 CC {@code executeExtractMemories}
     * （extractMemories.ts:598-603）+ {@code extractor}（:569-577）。
     *
     * <p>WHY: CC stopHooks.ts:149-152 在 stop hook 里 {@code void executeExtractMemories(...)}
     * fire-and-forget 触发 —— 后台 fork 不得阻塞主 turn；in-flight 登记到
     * {@link #inFlightExtractions} 供 {@link #drainPendingExtraction(long)} 在 headless 类
     * 退出路径等待（[rev2 EX-01] 应用关闭 @PreDestroy；CC print.ts:967-969）。当前轮已有
     * 提取运行时，本方法经 executeExtractMemoriesImpl stash 上下文 + 发 coalesced 事件，
     * 不叠加并发 fork。
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = 不追加 memory_saved 系统消息）
     */
    public void executeExtractMemories(List<ChatMessageDto> messages,
                                       Consumer<SystemMessage> appendSystemMessage) {
        // 2 参便捷重载 · 无 fork 原料（非主循环调用方：测试/直构）→ null 原料透传，
        // 保持既有兜底（supplier / createMinimalCacheSafeParams，不 fail-loud）。
        executeExtractMemories(messages, appendSystemMessage, null);
    }

    /**
     * 异步提取入口（fire-and-forget）· 对齐 CC {@code executeExtractMemories}
     * （extractMemories.ts:598-603）+ {@code extractor}（:569-577）。
     *
     * <p>WHY: CC stopHooks.ts:149-152 在 stop hook 里 {@code void executeExtractMemories(...)}
     * fire-and-forget 触发 —— 后台 fork 不得阻塞主 turn；in-flight 登记到
     * {@link #inFlightExtractions} 供 {@link #drainPendingExtraction(long)} 在 headless 类
     * 退出路径等待（[rev2 EX-01] 应用关闭 @PreDestroy；CC print.ts:967-969）。当前轮已有
     * 提取运行时，本方法经 executeExtractMemoriesImpl stash 上下文 + 发 coalesced 事件，
     * 不叠加并发 fork。
     *
     * <p><b>fork 原料（IMP-MV2-09 T9）</b>: {@code forkRawMaterial} 承载主线程
     * systemPrompt/userContext/systemContext/消息快照（LlmAgentLoop:5154 按会话捕获 ·
     * CC createCacheSafeParams(context) forkedAgent.ts:131-141）——修复 fork 空载荷
     * （ToolRegistrationConfig:1468-1469）导致的提取子代理无主系统提示 + prompt-cache key
     * 与主线程不一致；null = 无捕获（非主循环调用方），保持既有兜底。
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = 不追加 memory_saved 系统消息）
     * @param forkRawMaterial     fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     */
    public void executeExtractMemories(List<ChatMessageDto> messages,
                                       Consumer<SystemMessage> appendSystemMessage,
                                       ForkRawMaterial forkRawMaterial) {
        // 3 参便捷重载 · 无子代理上下文 → agentId=null（主线程语义）透传 4 参。
        // 非主循环调用方（测试/直构）直接调用时视为主线程，impl 内 agentId 防御检查不触发。
        executeExtractMemories(messages, appendSystemMessage, forkRawMaterial, null);
    }

    /**
     * 异步提取入口（fire-and-forget）· 对齐 CC {@code executeExtractMemories}
     * （extractMemories.ts:598-603）+ {@code extractor}（:569-577）。
     *
     * <p>WHY: CC stopHooks.ts:149-152 在 stop hook 里 {@code void executeExtractMemories(...)}
     * fire-and-forget 触发 —— 后台 fork 不得阻塞主 turn；in-flight 登记到
     * {@link #inFlightExtractions} 供 {@link #drainPendingExtraction(long)} 在 headless 类
     * 退出路径等待（[rev2 EX-01] 应用关闭 @PreDestroy；CC print.ts:967-969）。当前轮已有
     * 提取运行时，本方法经 executeExtractMemoriesImpl stash 上下文 + 发 coalesced 事件，
     * 不叠加并发 fork。
     *
     * <p><b>fork 原料（IMP-MV2-09 T9）</b>: {@code forkRawMaterial} 承载主线程
     * systemPrompt/userContext/systemContext/消息快照（LlmAgentLoop:5154 按会话捕获 ·
     * CC createCacheSafeParams(context) forkedAgent.ts:131-141）——修复 fork 空载荷
     * （ToolRegistrationConfig:1468-1469）导致的提取子代理无主系统提示 + prompt-cache key
     * 与主线程不一致；null = 无捕获（非主循环调用方），保持既有兜底。
     *
     * <p><b>agentId 双层防御（[IMP-E-2 OPD-CM5-E-04]）</b>: CC 在调用点（stopHooks.ts:143
     * {@code !toolUseContext.agentId}）与 impl 入口（extractMemories.ts:531-533
     * {@code if (context.toolUseContext.agentId) return}）双层防御。Java 端 StopHookPipeline
     * 调用点已 gate（:282）；本 4 参入口把 agentId 透传 impl，impl 入口二次防御 —— 拦截未来
     * 非 StopHookPipeline 调用方误传子代理片段写主会话记忆（决策 E-04 回补）。
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = 不追加 memory_saved 系统消息）
     * @param forkRawMaterial     fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @param agentId             子代理 id（null = 主线程；非空 → impl 入口跳过，CC :531-533）
     */
    public void executeExtractMemories(List<ChatMessageDto> messages,
                                       Consumer<SystemMessage> appendSystemMessage,
                                       ForkRawMaterial forkRawMaterial,
                                       String agentId) {
        // [sm-cursor-sessionize] sessionId=null → "unknown" 键兜底（非主循环调用方：测试/直构）
        executeExtractMemories(messages, appendSystemMessage, forkRawMaterial, agentId, null);
    }

    /**
     * 异步提取入口（fire-and-forget）· 对齐 CC {@code executeExtractMemories}
     * （extractMemories.ts:598-603）+ {@code extractor}（:569-577）。
     *
     * <p>WHY: CC stopHooks.ts:149-152 在 stop hook 里 {@code void executeExtractMemories(...)}
     * fire-and-forget 触发 —— 后台 fork 不得阻塞主 turn；in-flight 登记到
     * {@link #inFlightExtractions} 供 {@link #drainPendingExtraction(long)} 在 headless 类
     * 退出路径等待（[rev2 EX-01] 应用关闭 @PreDestroy；CC print.ts:967-969）。当前轮已有
     * 提取运行时，本方法经 executeExtractMemoriesImpl stash 上下文 + 发 coalesced 事件，
     * 不叠加并发 fork。
     *
     * <p><b>[sm-cursor-sessionize 2026-08-30]</b> sessionId 透传 impl —— 游标/stash/观察点
     * 全部按会话键控（CC 单会话闭包状态在 Web 多会话下是跨会话共享，见 {@link #lastMemoryMessageUuidBySession}）。
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = 不追加 memory_saved 系统消息）
     * @param forkRawMaterial     fork 原料（CC createCacheSafeParams(context) 三段 +
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @param agentId             子代理 id（null = 主线程；非空 → impl 入口跳过，CC :531-533）
     * @param sessionId           会话 ID（[sm-cursor-sessionize] 游标/stash/观察点按会话键控；
     *                            null → "unknown" 兜底键）
     */
    public void executeExtractMemories(List<ChatMessageDto> messages,
                                       Consumer<SystemMessage> appendSystemMessage,
                                       ForkRawMaterial forkRawMaterial,
                                       String agentId,
                                       String sessionId) {
        executeExtractMemories(messages, appendSystemMessage, forkRawMaterial, agentId, sessionId, memoryDir());
    }

    /**
     * 异步提取入口（fire-and-forget）· 对齐 CC {@code executeExtractMemories}
     * （extractMemories.ts:598-603）+ {@code extractor}（:569-577）。
     *
     * <p>[A1 重做 2026-09-04] <b>memoryDir 显式传参</b>—— 本方法在<b>会话线程</b>
     * （LlmAgentLoop stop-hook 同步调用，projectRoot ThreadLocal 可靠）被调用，故在进入
     * runAsync（ForkJoinPool，不继承会话 ThreadLocal）<b>之前</b>把 memoryDir 解析为不可变
     * 字符串，闭包贯穿 fork 全链 —— fork 线程绝不回读 AutoMemPaths/ThreadLocal，杜绝
     * 「ThreadLocal 获取失败 → 回落 config-home → 记忆写错目录」。
     *
     * <p>对齐 CC 真实形态：CC extractMemories.ts:339 runExtraction 在 fork 前
     * {@code const memoryDir = getAutoMemPath()} 算一次，目录是「算好传进 fork」而非 fork 内
     * 现算；CC 单会话全局 projectRoot 故随处可靠，Java 多会话把「解析点」收敛到会话线程入口。
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = 不追加 memory_saved 系统消息）
     * @param forkRawMaterial     fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @param agentId             子代理 id（null = 主线程；非空 → impl 入口跳过，CC :531-533）
     * @param sessionId           会话 ID（[sm-cursor-sessionize] 游标/stash/观察点按会话键控；
     *                            null → "unknown" 兜底键）
     * @param memoryDir           会话解析的 auto-memory 目录（尾分隔符，fork 全链用）
     */
    public void executeExtractMemories(List<ChatMessageDto> messages,
                                       Consumer<SystemMessage> appendSystemMessage,
                                       ForkRawMaterial forkRawMaterial,
                                       String agentId,
                                       String sessionId,
                                       String memoryDir) {
        // [A1 重做] memoryDir null（测试/非主循环调用方走 5 参委托）→ storage.memoryDir() 兜底
        //   （测试 storage Path 冻结安全）；生产 StopHookPipeline 传会话线程解析的 memoryDir。
        String memDir = memoryDir != null ? memoryDir : memoryDir();
        // extractor（CC :569-577）：把 promise 登记进 inFlightExtractions，await 后移除。
        // 覆盖完整 trailing-run 链（runExtraction 递归 finally），故 drain 等待它即覆盖尾随轮。
        CompletableFuture<Void> p = CompletableFuture.runAsync(() -> {
            executeExtractMemoriesImpl(messages, appendSystemMessage, forkRawMaterial, agentId, sessionId, memDir);
        });
        inFlightExtractions.add(p);
        p.whenComplete((v, e) -> inFlightExtractions.remove(p));
        if (log.isDebugEnabled()) {
            log.debug("[ExtractMemories] executeExtractMemories fire-and-forget 已登记 inFlight，当前 {} 个在途",
                inFlightExtractions.size());
        }
    }

    /**
     * 等待所有在-flight 提取（含 trailing 尾随轮）完成 · 对齐 CC {@code drainPendingExtraction}
     * （extractMemories.ts:611-615）+ {@code drainer}（:579-586）。
     *
     * <p>WHY: CC print.ts:962-969 在响应 flush 后、gracefulShutdownSync 前 drain —— 让 fork agent
     * 在 5s 关闭 failsafe 前完成。[rev2 EX-01/OPD-R2-EX-01] Java Web 端（非交互会话语义）：
     * headless 类退出路径 = 应用关闭（{@link #shutdown()} @PreDestroy 调用本方法）；每轮退出
     * 不阻塞（LlmAgentLoop 已移除轮次退出处同步 drain）。in-flight 为空即返回（无锁开销）；
     * 否则 60s 软超时（CC default timeoutMs=60_000），提取错误不抛出
     * （CC {@code Promise.all(...).catch(()=>{})}）。
     *
     * @param timeoutMs 超时毫秒（CC default 60_000）
     */
    public void drainPendingExtraction(long timeoutMs) {
        if (inFlightExtractions.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] drainPendingExtraction: 无在途提取，立即返回");
            }
            return;
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(
            inFlightExtractions.toArray(new CompletableFuture[0]));
        try {
            all.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] drainPendingExtraction: 在途提取已全部完成");
            }
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[ExtractMemories] drainPendingExtraction 超时（{}ms），不阻塞轮次退出（CC :584 setTimeout）",
                timeoutMs);
        } catch (Exception e) {
            log.warn("[ExtractMemories] drainPendingExtraction 等待异常（提取错误被吞，CC :582 catch）: {}",
                e.getMessage());
        }
    }

    /**
     * 应用关闭时 drain 在-flight 提取 · [rev2 EX-01/OPD-R2-EX-01] headless 类退出路径。
     *
     * <p>CC print.ts:962-969 仅在 headless（-p/SDK）响应 flush 后、gracefulShutdownSync 前
     * drain（交互式 REPL 不等待，fork 与下轮并发）。Java Web 端（非交互会话语义）：每轮退出
     * 不阻塞（LlmAgentLoop 已移除轮次退出处同步 drain），headless 类退出路径 = 应用关闭
     * （Spring 容器 shutdown）—— 此处等待 in-flight+trailing 完成（60s 软超时，错误吞），
     * 对齐 CC 关闭前 drain 语义。
     */
    @PreDestroy
    public void shutdown() {
        drainPendingExtraction(60_000);
    }

    /**
     * 提取执行门控入口 · 对齐 CC {@code executeExtractMemoriesImpl}（extractMemories.ts:527-567）。
     *
     * <p>门控顺序（对齐 CC，[sm 决策 2026-08-30] 总闸由 env 移至 DB）：
     * <ol>
     *   <li><b>子代理跳过</b>（:531-533 {@code context.toolUseContext.agentId}）—— [IMP-E-2
     *       OPD-CM5-E-04] Java 端双层防御：StopHookPipeline 调用点 gate（stopHooks.ts:143
     *       {@code !toolUseContext.agentId}，:282）+ 本方法入口 agentId 防御检查（CC 双层第二层，
     *       agentId 透传自调用方，见本方法体首个分支）</li>
     *   <li><b>env 运维覆盖</b>（{@link #extractionGate}，tri-state）：TRUE = 强制开（绕过 DB
     *       gate）；FALSE = 强制关（ant 用户一次性发 tengu_extract_memories_gate_disabled，
     *       CC :536-542，[sm-cursor-sessionize] 按会话防重复）；null = 不影响交下一级</li>
     *   <li><b>DB 主控 isAutoMemoryEnabled</b>（:545）—— DB settings 列 auto_memory_enabled
     *       （默认 true）作总闸，直接 DB 改即生效</li>
     *   <li><b>remote mode 跳过</b>（:549-552）—— [IMP-CM-19] 接入
 *       {@link MemoryRemoteModeConfig#isRemoteMode()}（nexusai.memory.remote-mode，默认 false）</li>
     *   <li><b>inProgress stash</b>（:557-564）—— 在跑时 stash pendingContext（覆盖旧值）+
     *       tengu_extract_memories_coalesced，不叠加并发 fork · [sm-cursor-sessionize] 按会话键控</li>
     * </ol>
     *
     * @param messages            主线程消息快照
     * @param appendSystemMessage UI 系统消息回调（null = 不追加）
     * @param agentId             子代理 id（null = 主线程；非空 → 本方法入口跳过，
     *                            CC :531-533 防御性二次检查，拦截非 StopHookPipeline 调用方）
     * @param sessionId           会话 ID（[sm-cursor-sessionize] 游标/stash/观察点按会话键控；
     *                            null → "unknown" 兜底键）
     */
    private void executeExtractMemoriesImpl(List<ChatMessageDto> messages,
                                            Consumer<SystemMessage> appendSystemMessage,
                                            ForkRawMaterial forkRawMaterial,
                                            String agentId,
                                            String sessionId,
                                            String memoryDir) {
        // 双层防御第二层 · CC extractMemories.ts:531-533 executeExtractMemoriesImpl 入口首个检查
        //   `if (context.toolUseContext.agentId) return` —— 主线程 agentId==null 才执行提取；
        //   未来非 StopHookPipeline 调用方若误传子代理片段，在此拦截不写主会话记忆（[IMP-E-2]）。
        if (agentId != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] impl 内 agentId={} 非空 → 跳过提取（CC extractMemories.ts:531-533，子代理防御）",
                    agentId);
            }
            return;
        }
        String key = cursorKey(sessionId);
        // [sm 决策 2026-08-30] 总闸已由 env NEXUSAI_EXTRACT_MEMORIES 移至 DB auto_memory_enabled
        //   （{@link #autoMemoryEnabled}，默认 true）。本 env 保留为可选强制关/开运维覆盖
        //   （null = 不影响，交 DB 主控）。模块级 feature('EXTRACT_MEMORIES') 不在此
        //   （stopHooks.ts:142 调用点 AND，见 StopHookPipeline.isExtractMemoriesModuleEnabled）。
        Boolean override = extractionGate.get();
        if (override != null && !override) {
            // env 强制关 → CC :537-540：gate 关闭且 USER_TYPE==='ant' 一次性发 gate_disabled
            //   （hasLoggedGateFailure 防重复 · [sm-cursor-sessionize] 按会话键控）
            if (userTypeIsAnt.getAsBoolean()
                    && !Boolean.TRUE.equals(hasLoggedGateFailureBySession.get(key))) {
                hasLoggedGateFailureBySession.put(key, Boolean.TRUE);
                emitTelemetry("tengu_extract_memories_gate_disabled", Map.of());
                if (log.isDebugEnabled()) {
                    log.debug("[ExtractMemories] env 强制关（NEXUSAI_EXTRACT_MEMORIES=false）且用户为 ant，发 gate_disabled（一次性）");
                }
            }
            return;
        }
        // DB 主控（CC :545 isAutoMemoryEnabled）· 无 env 强制开覆盖时 DB auto_memory_enabled=false
        //   → 跳过（对齐 CC executeExtractMemoriesImpl:545；CC 无 gate_disabled 遥测于此分支）。
        if (override == null && !autoMemoryEnabled.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] isAutoMemoryEnabled=false（DB settings 列 auto_memory_enabled，默认 true）→ 跳过提取");
            }
            return;
        }
        // remote mode 跳过（CC :549-552）· 对齐 getIsRemoteMode()（bootstrap/state.ts:1631-1633，
        //   nexusai.memory.remote-mode 配置默认 false）。位于 isAutoMemoryEnabled 之后、
        //   inProgress stash 之前（CC 门控序）。
        if (remoteMode.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] remote mode → 跳过提取（CC extractMemories.ts:549-552）");
            }
            return;
        }
        // 在跑时 stash 上下文（CC :557-564）· 覆盖旧值，仅最新有用（含最多消息）
        //   [sm-cursor-sessionize] 按会话键控（A 会话 stash 不得被 B 会话尾随轮消费）
        if (Boolean.TRUE.equals(inProgressBySession.get(key))) {
            pendingContextBySession.put(key, new PendingContext(messages, appendSystemMessage, forkRawMaterial));
            // CC :561 tengu_extract_memories_coalesced（真源行号，任务文本 :156-157 有误）
            emitTelemetry("tengu_extract_memories_coalesced", Map.of());
            log.info("[ExtractMemories] 提取进行中，stash 上下文待尾随轮（coalesced）· CC extractMemories.ts:557-564");
            return;
        }
        runExtraction(messages, appendSystemMessage, forkRawMaterial, false, sessionId, memoryDir);
    }

    // ── 核心 · 对齐 CC runExtraction（extractMemories.ts:329-523）──

    /**
     * 从对话中提取记忆 · 对齐 CC {@code runExtraction}（extractMemories.ts:329-523）。
     *
     * <p>流程（对齐 CC 顺序）：
     * <ol>
     *   <li>互斥检查 {@link #hasMemoryWritesSince} → 主 agent 已写 → skip fork + 推进游标（INV-5）</li>
     *   <li>节流（tengu_bramble_lintel，isTrailingRun 跳过）</li>
     *   <li>{@link #countModelVisibleMessagesSince} 计数新增消息</li>
     *   <li>预注入 manifest（复用 MemoryStorage.list）→ 构造提取 prompt（buildExtractAutoOnlyPrompt）</li>
     *   <li>RunForkedAgent.run(params, forkedQuery) · maxTurns=5 + skipTranscript=true（INV-6）</li>
     *   <li>游标成功推进 / 失败不动（INV-4）</li>
     *   <li>memoryPaths&gt;0 时经 appendSystemMessage 发 memory_saved 系统消息（:490-496）</li>
     *   <li>finally 中取走 pendingContext 启动尾随一轮（:503-522）</li>
     * </ol>
     *
     * @param messages            主线程消息快照（CC REPLHookContext.messages）
     * @param appendSystemMessage UI 系统消息回调（CC AppendSystemMessageFn；null = 不追加）
     * @param forkRawMaterial     fork 原料（CC createCacheSafeParams(context) 三段 +
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @param isTrailingRun       是否尾随轮（CC isTrailingRun，:377-385 跳过节流）
     * @param sessionId           会话 ID（[sm-cursor-sessionize] 游标/节流/stash/观察点按会话键控；
     *                            null → "unknown" 兜底键）
     * @param memoryDir           会话解析的 auto-memory 目录（尾分隔符，fork 全链用 —— 入口
     *                            executeExtractMemories 在会话线程算好传入，本方法在 fork 线程
     *                            绝不回读 storage.memoryDir()/AutoMemPaths，防 ThreadLocal 回落）
     * @return 提取结果（写入的记忆文件数 + 路径列表）
     */
    private ExtractResult runExtraction(List<ChatMessageDto> messages,
                                        Consumer<SystemMessage> appendSystemMessage,
                                        ForkRawMaterial forkRawMaterial,
                                        boolean isTrailingRun,
                                        String sessionId,
                                        String memoryDir) {
        String key = cursorKey(sessionId);
        if (messages == null || messages.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] 空对话，跳过提取");
            }
            lastResultBySession.put(key, new ExtractResult(0, List.of()));
            return lastResultBySession.get(key);
        }

        // CC extractMemories.ts:340 先计数新增消息（skipped_direct_write 事件需要 message_count）
        // [sm-cursor-sessionize] 游标按会话读取（A 会话的游标不影响 B 会话计数）
        String cursor = lastMemoryMessageUuidBySession.get(key);
        int newMessageCount = countModelVisibleMessagesSince(messages, cursor);

        // 1. 主/背景互斥（INV-5）· CC extractMemories.ts:345-360
        if (hasMemoryWritesSince(messages, cursor, memoryDir)) {
            log.info("[ExtractMemories] 主 agent 已写 auto-memory，跳过 fork 并推进游标（INV-5）");
            // CC extractMemories.ts:356-358 tengu_extract_memories_skipped_direct_write
            emitTelemetry("tengu_extract_memories_skipped_direct_write",
                Map.of("message_count", newMessageCount));
            ChatMessageDto last = messages.get(messages.size() - 1);
            if (last != null && last.id() != null) {
                lastMemoryMessageUuidBySession.put(key, last.id());
            }
            lastResultBySession.put(key, new ExtractResult(0, List.of()));
            return lastResultBySession.get(key);
        }

        // 2. 节流（tengu_bramble_lintel）· CC extractMemories.ts:374-386
        //    尾随轮（isTrailingRun）处理已提交的工作，不被节流（:377-385 注释：trailing
        //    extractions skip this check since they process already-committed work）
        if (!isTrailingRun) {
            int turns = turnsSinceLastExtractionBySession.getOrDefault(key, 0) + 1;
            turnsSinceLastExtractionBySession.put(key, turns);
            // [rev2 EX-02] 每 run 读 supplier（对齐 CC 每 run 读 feature · extractMemories.ts:380-385）
            int interval = extractionInterval.getAsInt();
            if (turns < interval) {
                if (log.isDebugEnabled()) {
                    log.debug("[ExtractMemories] 节流跳过: turnsSinceLastExtraction={} < interval={}", turns, interval);
                }
                lastResultBySession.put(key, new ExtractResult(0, List.of()));
                return lastResultBySession.get(key);
            }
        }
        turnsSinceLastExtractionBySession.put(key, 0);

        // 3. inProgress 置位（CC extractMemories.ts:388）· 防重叠由 executeExtractMemoriesImpl
        //    stash 语义取代（旧"inProgress 直返空结果"分支已删，IMP-M-P0-3b deleteList）
        //    [sm-cursor-sessionize] 按会话键控（A 会话在跑时 B 会话可独立提取）
        inProgressBySession.put(key, Boolean.TRUE);
        long startTime = System.currentTimeMillis();
        try {
            // 4. 预注入 manifest（CC extractMemories.ts:398-400）
            // [A1 重做] 按传入 memoryDir 参数扫（fork 线程不读 storage.memoryDir()/AutoMemPaths ——
            //   旧 storage.list() 内部现算 memoryDir，fork 线程无 ThreadLocal 回落 config-home，
            //   manifest 读到错误目录。MemoryStorage 惰性 memoryDir 属会话线程解析面，本处用参数）。
            List<MemoryEntry> existing = new MemoryScanner().scan(
                java.nio.file.Paths.get(memoryDir), null);
            // [rev2 D-06/OPD-R2-MEM-06] 单一入口：CC extractMemories.ts:398-400 复用同一
            // formatMemoryManifest（memoryScan.ts:84-94）—— 改调 FindRelevantMemories.formatManifest，
            // 旧私有重复实现已删（尾换行字节差消除，EX-03③）
            String manifest = FindRelevantMemories.formatManifest(existing);
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] 预注入 manifest: {} 条现有记忆, {} 行文本（CC memoryScan.ts:84-94 格式）",
                    existing.size(), manifest == null ? 0 : manifest.lines().count());
            }
            // skipIndex 门控（CC extractMemories.ts:366-369 tengu_moth_copse → :407/:412 传入
            // buildExtractAutoOnlyPrompt/buildExtractCombinedPrompt）· 决定 howToSave 单步/两步；
            // IMP-MV2-12: 生产源 = FeatureFlags.tenguMothCopse（ToolRegistrationConfig bean 接线）
            boolean skipIndex = skipIndexGate.getAsBoolean();
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] skipIndex(tengu_moth_copse)={}（生产源 nexusai.feature.tengu-moth-copse）· CC extractMemories.ts:366-369",
                    skipIndex);
            }
            String prompt = ExtractMemoriesOpener.buildExtractAutoOnlyPrompt(
                newMessageCount, manifest, skipIndex);
            List<ChatMessageDto> promptMessages = List.of(userMessage(prompt));

            // 5. cache-safe params + fork 参数（INV-6）
            // [IMP-MV2-09 T9] fork 原料注入：supplied（生产 supplier）三段恒空（toolUseContext
            //   工具集唯一载荷）→ 合并主线程原料（ForkRawMaterial · CC createCacheSafeParams(context)
            //   forkedAgent.ts:131-141）；supplied 非空（未来接线方注入完整数组）保留原值
            //   （mergeSystemPrompt/mergeContext "supplied 优先" · 同 SessionMemoryService RES-C5 语义）。
            //   forkContextMessages = 本轮消息快照（messages · CC context.messages）。T9 修复
            //   空载荷（ToolRegistrationConfig:1468-1469）→ fork 恢复主系统提示 + prompt-cache
            //   key 与主线程一致（cache 共享恢复）。null 原料（非主循环调用方）→ 既有兜底不变。
            CacheSafeParams supplied = cacheSafeParamsSupplier != null ? cacheSafeParamsSupplier.get() : null;
            CacheSafeParams cacheSafeParams = supplied != null
                ? new CacheSafeParams(
                    ForkRawMaterial.mergeSystemPrompt(supplied.systemPrompt(),
                        forkRawMaterial != null ? forkRawMaterial.systemPrompt() : null),
                    ForkRawMaterial.mergeContext(supplied.userContext(),
                        forkRawMaterial != null ? forkRawMaterial.userContext() : null),
                    ForkRawMaterial.mergeContext(supplied.systemContext(),
                        forkRawMaterial != null ? forkRawMaterial.systemContext() : null),
                    supplied.toolUseContext(), messages,
                    // [RES-C5 rework] gate 合并：生产 supplier 5 参便捷构造 gate=false 占位，
                    //   与会话级 gate（GlobalCacheScope 单实现 · betas.ts:227-233）OR 合并（REQ-C5-4）
                    supplied.useGlobalCacheScope() || useGlobalCacheScopeSupplier.get())
                : forkRawMaterial != null
                    ? RunForkedAgent.createMinimalCacheSafeParams(
                        messages,
                        forkRawMaterial.systemPrompt(),       // T9：主线程原料（forkedAgent.ts:131）
                        forkRawMaterial.userContext(),        // T9：主线程 userContext
                        forkRawMaterial.systemContext(),      // T9：主线程 systemContext
                        useGlobalCacheScopeSupplier.get())
                    : RunForkedAgent.createMinimalCacheSafeParams(
                        messages,
                        // [RES-C5] extract-memories 无 post-sampling 上下文（stop-hook 触发）→
                        // systemPrompt 降级（验收 2）；gate 仍透传（GlobalCacheScope 单实现 · betas.ts:227-233）
                        List.of(),
                        Map.of(),
                        Map.of(),
                        useGlobalCacheScopeSupplier.get());

            // deny 分支发 tengu_auto_mem_tool_denied（CC denyAutoMemTool extractMemories.ts:154-164）
            HookPermissionResolver.CanUseTool canUseTool = createAutoMemCanUseTool(memoryDir,
                toolName -> emitTelemetry("tengu_auto_mem_tool_denied",
                    Map.of("tool_name", toolName == null ? "<unknown>" : toolName)));
            ForkedAgentParams params = new ForkedAgentParams(
                promptMessages, cacheSafeParams, canUseTool,
                QuerySource.EXTRACT_MEMORIES, "extract_memories",
                /*maxOutputTokens*/ null,
                /*maxTurns*/ 5,
                /*skipTranscript*/ true,
                /*skipCacheWrite*/ false,
                /*abortController*/ null,
                /*onMessage*/ null);
            // [sm-cursor-sessionize] 观察点按会话键控（多会话并发 fork 各会话留档）
            this.lastForkParamsBySession.put(key, params);

            if (forkedQuery == null) {
                // IMP-M-P0-3: 生产恒注入（ToolRegistrationConfig.extractMemoriesAgent 注入
                // ProductionForkedQuery）。未注入 = 编程错误 → fail loud（不静默跳过 fork 假装
                // 成功；异常由下方 best-effort catch 记录 + 游标不动，下轮注入后仍可提取）。
                throw new IllegalStateException(
                    "[ExtractMemories] fork 查询 seam 未注入（生产 ToolRegistrationConfig 必须 setForkedQuery 注入 ProductionForkedQuery），fail loud");
            }

            log.info("[ExtractMemories] 发起 fork: {} 条新增消息, memoryDir={}, maxTurns=5, skipTranscript=true",
                newMessageCount, memoryDir);
            ForkedAgentResult result = RunForkedAgent.run(params, forkedQuery);

            // 6. 游标成功推进（INV-4）· CC extractMemories.ts:432-435 · [sm-cursor-sessionize] 按会话写
            ChatMessageDto last = messages.get(messages.size() - 1);
            if (last != null && last.id() != null) {
                lastMemoryMessageUuidBySession.put(key, last.id());
            }

            // 7. 提取写入路径 + memory 计数（排除 MEMORY.md 索引）· CC extractMemories.ts:437-485
            List<String> writtenPaths = extractWrittenPaths(result.messages());
            // memoryPaths = 排除 ENTRYPOINT_NAME(MEMORY.md) 索引后的写入文件 · CC :465-467
            //   basename 等值比较（basename(p) !== ENTRYPOINT_NAME）——endsWith 会把
            //   userMEMORY.md 等文件误排（CC 语义是 basename 恰等于 "MEMORY.md" 才排除）
            List<String> memoryPaths = new ArrayList<>();
            for (String p : writtenPaths) {
                if (p != null && !ENTRYPOINT_NAME.equals(basename(p))) {
                    memoryPaths.add(p);
                }
            }
            int memoryCount = memoryPaths.size();
            lastResultBySession.put(key, new ExtractResult(memoryCount, writtenPaths));
            log.info("[ExtractMemories] fork 完成: {} 个文件写入, {} 个记忆（不含索引）, 游标={}",
                writtenPaths.size(), memoryCount, lastMemoryMessageUuidBySession.get(key));

            // 8. 提取事件（CC extractMemories.ts:473-485）· team_memories_saved=0（Java 无
            //    TEAMMEM feature，对齐 CC feature('TEAMMEM')=false 分支）
            ForkedAgentResult.ForkUsage usage = result.totalUsage() != null
                ? result.totalUsage() : ForkedAgentResult.ForkUsage.empty();
            long turnCount = result.messages() == null ? 0L
                : result.messages().stream().filter(m -> m != null && m.role() == Role.assistant).count();
            // [IMP-MV2-10 + A 命中率口径] cache 命中率 hitPct 日志（CC extractMemories.ts:440-453）·
            //   协议分派（ContextUsageCalculator.computeCacheHitRate）：anthropic →
            //   read/(input+read+create)；非 anthropic（deepseek prompt_tokens 已含 cache hit）→
            //   read/input（旧恒三字段分母对 deepseek 双计 → 命中率恒为真实一半）。*100 后
            //   1 位小数（JS toFixed(1) 等价，Locale.ROOT 防小数分隔符漂移；read ≤ 0 / 分母 ≤ 0 →
            //   0 → "0.0"）。CC logForDebugging 调试级 → Java debug 级。usage 保真由
            //   ProductionForkedQuery 全量累计提供（forkedAgent.ts:557-566 · IMP-MV2-10）。
            double hitRatePct = ContextUsageCalculator.computeCacheHitRate(
                usage.inputTokens(), usage.cacheReadInputTokens(),
                usage.cacheCreationInputTokens(), result.isAnthropic()) * 100.0;
            String hitPct = String.format(Locale.ROOT, "%.1f", hitRatePct);
            log.debug("[ExtractMemories] finished — {} files written, cache: read={} create={} "
                    + "input={} ({}% hit)",
                writtenPaths.size(), usage.cacheReadInputTokens(), usage.cacheCreationInputTokens(),
                usage.inputTokens(), hitPct);
            emitTelemetry("tengu_extract_memories_extraction", Map.of(
                "input_tokens", usage.inputTokens(),
                "output_tokens", usage.outputTokens(),
                "cache_read_input_tokens", usage.cacheReadInputTokens(),
                "cache_creation_input_tokens", usage.cacheCreationInputTokens(),
                "message_count", newMessageCount,
                "turn_count", turnCount,
                "files_written", writtenPaths.size(),
                "memories_saved", memoryCount,
                "team_memories_saved", 0,
                "duration_ms", System.currentTimeMillis() - startTime));

            // 9. memory_saved 系统消息（CC extractMemories.ts:490-496）· memoryPaths>0 时经
            //    appendSystemMessage 追加 type=system subtype=memory_saved writtenPaths
            //    （messages.ts:4460-4471 createMemorySavedMessage）。teamCount 仅 TEAMMEM
            //    feature 分支（Java 恒 false，:492-494 N/A）。
            if (memoryPaths.size() > 0 && appendSystemMessage != null) {
                appendSystemMessage.accept(SystemMessage.memorySaved(memoryPaths));
                if (log.isDebugEnabled()) {
                    log.debug("[ExtractMemories] memory_saved 系统消息已追加: {} 条路径", memoryPaths.size());
                }
            }
            return lastResultBySession.get(key);
        } catch (Exception e) {
            // best-effort（extractMemories.ts:497-502）：错误不抛给调用方，游标不动 → 下轮重试
            log.warn("[ExtractMemories] fork 失败（best-effort，游标不动，下轮重试）: {}", e.getMessage());
            // CC extractMemories.ts:500-502 tengu_extract_memories_error（duration_ms）
            emitTelemetry("tengu_extract_memories_error",
                Map.of("duration_ms", System.currentTimeMillis() - startTime));
            lastResultBySession.put(key, new ExtractResult(0, List.of()));
            return lastResultBySession.get(key);
        } finally {
            inProgressBySession.put(key, Boolean.FALSE);
            // [D-4 登记 · IMP-MV2-40] △-4：本 finally 内 inProgress=false 之后到 trailing 递归置 true
            //   之间存在极窄并发窗口（需 extract 在途 + 恰两新调用落在首两行之间）→ 一次并行 fork，
            //   inProgress 语义短期失真；非数据损坏 —— 单线程调度下不可达，登记不修。
            // CC extractMemories.ts:503-522 · 运行期间若有调用 stash 了上下文，取走后
            // 尾随一轮（isTrailingRun=true 跳过节流）。尾随轮相对已推进的游标计数，只取
            // 两次调用之间新增的消息（:507-509 注释）。pendingContext 覆盖语义保证仅最新
            // 上下文被处理（:510-511 取走即置 undefined）。[sm-cursor-sessionize] 按会话取走
            // （A 会话的 stash 只由 A 会话尾随轮消费）。
            PendingContext trailing = pendingContextBySession.remove(key);
            if (trailing != null) {
                log.info("[ExtractMemories] 运行尾随提取（stashed 上下文）· CC extractMemories.ts:510-521");
                runExtraction(trailing.messages(), trailing.appendSystemMessage(),
                    trailing.forkRawMaterial(), true, sessionId, memoryDir);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CC helpers · countModelVisibleMessagesSince / hasMemoryWritesSince
    // ════════════════════════════════════════════════════════════════════

    /** CC isModelVisibleMessage（extractMemories.ts:78-80）= user 或 assistant。 */
    private static boolean isModelVisibleMessage(ChatMessageDto m) {
        return m != null && (m.role() == Role.user || m.role() == Role.assistant);
    }

    /**
     * 统计 sinceUuid 之后的模型可见消息数 · CC original: {@code countModelVisibleMessagesSince}
     * （extractMemories.ts:82-110）。
     *
     * <p>sinceUuid 为 null → 全量计数（首次运行）。sinceUuid 未找到（被 context compaction
     * 移除）→ 回退全量计数而非返回 0（否则提取永久禁用，extractMemories.ts:103-108）。
     *
     * @param messages 消息列表
     * @param sinceUuid 游标 UUID（null = 全量）
     * @return 模型可见消息数
     */
    public static int countModelVisibleMessagesSince(List<ChatMessageDto> messages, String sinceUuid) {
        if (messages == null) {
            return 0;
        }
        if (sinceUuid == null || sinceUuid.isEmpty()) {
            return (int) messages.stream().filter(ExtractMemoriesAgent::isModelVisibleMessage).count();
        }
        boolean foundStart = false;
        int n = 0;
        for (ChatMessageDto m : messages) {
            if (!foundStart) {
                if (sinceUuid.equals(m.id())) {
                    foundStart = true;
                }
                continue;
            }
            if (isModelVisibleMessage(m)) {
                n++;
            }
        }
        if (!foundStart) {
            return (int) messages.stream().filter(ExtractMemoriesAgent::isModelVisibleMessage).count();
        }
        return n;
    }

    /**
     * 自 sinceUuid 后是否有 assistant 消息的 Write/Edit tool_use 目标指向 auto-memory 路径 ·
     * CC original: {@code hasMemoryWritesSince}（extractMemories.ts:121-148）。
     *
     * <p>主 agent 的 prompt 有完整保存指令 —— 它已写记忆时后台 fork 冗余。返回 true 时
     * {@link #executeExtractMemories} 跳过 fork 并推进游标（INV-5 主/背景互斥）。
     *
     * @param messages 消息列表
     * @param sinceUuid 游标 UUID（null = 从首条开始）
     * @param memoryDir auto-memory 目录（尾分隔符）
     * @return true = 主 agent 已写 auto-memory
     */
    public static boolean hasMemoryWritesSince(
            List<ChatMessageDto> messages, String sinceUuid, String memoryDir) {
        if (messages == null) {
            return false;
        }
        boolean foundStart = sinceUuid == null || sinceUuid.isEmpty();
        for (ChatMessageDto m : messages) {
            if (!foundStart) {
                if (sinceUuid.equals(m.id())) {
                    foundStart = true;
                }
                continue;
            }
            if (m == null || m.role() != Role.assistant || m.toolCalls() == null) {
                continue;
            }
            for (ToolCallDto tc : m.toolCalls()) {
                String filePath = getWrittenFilePath(tc);
                if (filePath != null && isAutoMemPath(filePath, memoryDir)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** CC getWrittenFilePath（extractMemories.ts:232-249）：从 Write/Edit tool_use 提取 file_path。 */
    private static String getWrittenFilePath(ToolCallDto tc) {
        if (tc == null) {
            return null;
        }
        String name = tc.name();
        if (name == null || !("Edit".equals(name) || "Write".equals(name))) {
            return null;
        }
        String args = tc.arguments();
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(args);
            JsonNode fp = root.path("file_path");
            return fp.isTextual() ? fp.asText() : null;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemories] 解析 tool_use arguments 失败: {}", e.getMessage());
            }
            return null;
        }
    }

    /** 绝对路径是否在 auto-memory 目录内（normalize + 前缀检查，防路径遍历）。 */
    private static boolean isAutoMemPath(String filePath, String memoryDir) {
        if (filePath == null || memoryDir == null) {
            return false;
        }
        String normalized;
        try {
            normalized = java.nio.file.Paths.get(filePath).normalize().toString();
        } catch (Exception e) {
            return false;
        }
        return normalized.startsWith(memoryDir);
    }

    /**
     * 从 fork 结果消息提取所有 Write/Edit 写入路径（去重）· CC original:
     * {@code extractWrittenPaths}（extractMemories.ts:251-269）。
     */
    private static List<String> extractWrittenPaths(List<ChatMessageDto> agentMessages) {
        List<String> paths = new ArrayList<>();
        if (agentMessages == null) {
            return paths;
        }
        for (ChatMessageDto m : agentMessages) {
            if (m == null || m.role() != Role.assistant || m.toolCalls() == null) {
                continue;
            }
            for (ToolCallDto tc : m.toolCalls()) {
                String fp = getWrittenFilePath(tc);
                if (fp != null && !paths.contains(fp)) {
                    paths.add(fp);
                }
            }
        }
        return paths;
    }

    /** 构造 fork 的 user 消息（CC createUserMessage）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }


    /**
     * 路径 basename · 对齐 CC {@code basename(p)}（extractMemories.ts:466）。
     * 非法路径（Paths.get 抛 InvalidPathException）回退原串（等值比较不会误匹配 ENTRYPOINT_NAME）。
     */
    private static String basename(String p) {
        try {
            return java.nio.file.Paths.get(p).getFileName().toString();
        } catch (Exception e) {
            return p;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // createAutoMemCanUseTool · extractMemories.ts:171-222
    // ════════════════════════════════════════════════════════════════════

    /**
     * 创建受限 canUseTool · CC original: {@code createAutoMemCanUseTool}（extractMemories.ts:171-222）。
     *
     * <p>共享于 extractMemories 与 autoDream（extractMemories.ts:166-170 注释）：
     * <ul>
     *   <li>REPL 无条件放行（首分支，extractMemories.ts:173-182）</li>
     *   <li>Read/Grep/Glob 无条件放行（只读）</li>
     *   <li>Bash 先 schema 预校验（command 必填）再只读判定（:195-204，fail-closed）</li>
     *   <li>Edit/Write 仅 auto-memory 目录内路径（isAutoMemPath）</li>
     *   <li>其余工具拒绝（MCP/Agent/写 Bash 等）</li>
     * </ul>
     *
     * @param memoryDir auto-memory 目录（尾分隔符）
     * @return 受限权限检查函数
     */
    public static HookPermissionResolver.CanUseTool createAutoMemCanUseTool(String memoryDir) {
        return createAutoMemCanUseTool(memoryDir, null);
    }

    /**
     * 创建受限 canUseTool（带 deny 遥测回调）· CC original: {@code createAutoMemCanUseTool}
     * （extractMemories.ts:171-222）+ {@code denyAutoMemTool}（:154-164，
     * tengu_auto_mem_tool_denied + tool_name）。
     *
     * <p>CC denyAutoMemTool 在 Bash 非只读 / Edit-Write 非 auto-memory 路径 / 其余工具拒绝时
     * 发 {@code tengu_auto_mem_tool_denied(tool_name)}。Java 端经 onToolDenied 回调注入
     * telemetry 发射（extractMemories/autoDream 各自带自己的 Telemetry）。onToolDenied 为 null
     * → 静默跳过（测试/无 telemetry 场景，对齐 CC logEvent 可空上下文）。
     *
     * <p>[rev2 EX-04] REPL 放行首分支（extractMemories.ts:173-182）：REPL 模式开启时基础工具
     * 隐藏、fork 改调 REPL，内层 VM 重查仍受限（REPLTool 包装器再调本 canUseTool）。
     *
     * <p>[v5 E-03] Bash 分支先整 schema 校验（CC :196 {@code inputSchema.safeParse}，
     * command 必填 + timeout 类型等全字段，OPD-CM5-E-03）——缺 command 或非法字段类型
     * （如 {@code {"command":"ls","timeout":"bad"}}）→ deny（fail-closed）；空串 command
     * 两侧均放行（safeParse 接受空串 + isReadOnly 空串分支 allow）。
     *
     * <p>[rev2 EX-07/G-102] deny 载荷对齐 CC denyAutoMemTool：message = 具体指引文本 +
     * decisionReason {@code {type:'other', reason}}（旧恒 deny(null) → 模型收到通用
     * "Permission denied for tool use." 无指引）。
     *
     * @param memoryDir    auto-memory 目录（尾分隔符）
     * @param onToolDenied deny 时回调（参数 = 被拒工具名；null → 不发射）
     * @return 受限权限检查函数
     */
    public static HookPermissionResolver.CanUseTool createAutoMemCanUseTool(
            String memoryDir, java.util.function.Consumer<String> onToolDenied) {
        return (tool, input, ctx, toolUseId, forceDecision) -> {
            String name = tool == null ? null : tool.name();
            if (name == null) {
                return denyWithGuide(onToolDenied, "<unknown>", toolUseId, OTHER_TOOLS_GUIDE(memoryDir));
            }
            // [rev2 EX-04] REPL 无条件放行（extractMemories.ts:173-182）· 放行理由：
            //   REPL 模式开启时基础工具隐藏、fork 改调 REPL；REPL 的 VM 上下文对内层基础工具
            //   重调本 canUseTool（toolWrappers.ts createToolWrapper），Read/Bash/Edit/Write
            //   检查仍生效 → 放行 REPL 不给 fork 额外权限，且工具列表参与 prompt-cache key
            //   （forkedAgent.ts CacheSafeParams），改工具列表会破坏缓存共享（:176-179 注释）。
            if ("REPL".equals(name)) {
                return ToolPermissionGate.DecisionResult.allow();
            }
            // Read/Grep/Glob 无条件放行（extractMemories.ts:184-191）
            if ("Read".equals(name) || "Grep".equals(name) || "Glob".equals(name)) {
                return ToolPermissionGate.DecisionResult.allow();
            }
            // Bash 仅只读命令（extractMemories.ts:194-204）
            if ("Bash".equals(name)) {
                // [v5 E-03] 整 schema 校验（extractMemories.ts:196-197）：CC
                //   `tool.inputSchema.safeParse(input)` 校验整 schema（command 必填 string、timeout
                //   integer 等）成功后才 `isReadOnly(parsed.data)`。Java 等价 =
                //   ToolInputValidator.safeParseSchema（toolExecution.ts:615 同源）——缺 command 字段
                //   → deny（fail-closed）；非法 timeout 类型（如 {"command":"ls","timeout":"bad"}）
                //   → deny（OPD-CM5-E-03 修复，替代旧仅查 command 字段存在性的 △-5 近似 IMP-MV2-40）。
                //   空串 command safeParse 通过 → isReadOnly("") 空串分支 allow（同侧）。
                boolean schemaOk = new ToolInputValidator().safeParseSchema(tool, input).ok();
                boolean readOnly = schemaOk && tool.isReadOnly(input);
                if (readOnly) {
                    return ToolPermissionGate.DecisionResult.allow();
                }
                // [rev2 EX-07] CC denyAutoMemTool Bash 非只读文案（extractMemories.ts:200-203）
                return denyWithGuide(onToolDenied, name, toolUseId, BASH_READ_ONLY_GUIDE);
            }
            // Edit/Write 仅 auto-memory 目录内（extractMemories.ts:206-215）
            if ("Edit".equals(name) || "Write".equals(name)) {
                if (input != null && input.has("file_path") && input.get("file_path").isTextual()) {
                    String fp = input.get("file_path").asText();
                    if (isAutoMemPath(fp, memoryDir)) {
                        return ToolPermissionGate.DecisionResult.allow();
                    }
                }
                // [rev2 EX-07] CC 落"其余工具拒绝"通用文案（extractMemories.ts:217-220）
                return denyWithGuide(onToolDenied, name, toolUseId, OTHER_TOOLS_GUIDE(memoryDir));
            }
            // 其余工具拒绝（extractMemories.ts:217-221）· [rev2 EX-07] 带具体指引文案
            return denyWithGuide(onToolDenied, name, toolUseId, OTHER_TOOLS_GUIDE(memoryDir));
        };
    }

    /** CC denyAutoMemTool Bash 非只读指引（extractMemories.ts:200-203）。 */
    private static final String BASH_READ_ONLY_GUIDE =
        "Only read-only shell commands are permitted in this context (ls, find, grep, cat, stat, wc, head, tail, and similar)";

    /** CC denyAutoMemTool 其余工具指引（extractMemories.ts:217-220，memoryDir 注入）。 */
    private static String OTHER_TOOLS_GUIDE(String memoryDir) {
        return "only Read, Grep, Glob, read-only Bash, and Edit/Write within " + memoryDir + " are allowed";
    }

    /**
     * deny 决策 + 载荷 · [rev2 EX-07/G-102] 对齐 CC denyAutoMemTool（extractMemories.ts:154-164）
     * 返回值 {@code {behavior:'deny', message: reason, decisionReason:{type:'other', reason}}}：
     * 先发 tengu_auto_mem_tool_denied 遥测，再返回带 message + decisionReason 的 deny
     * （HookPermissionResolver.gateDecisionToPermission 透传 → ToolPermissionGate.toToolResult
     * 把 message 注入模型 tool_result，模型收到 CC 具体指引而非通用拒绝文本）。
     */
    private static ToolPermissionGate.DecisionResult denyWithGuide(
            java.util.function.Consumer<String> onToolDenied, String toolName,
            String toolUseId, String guide) {
        denyAutoMemTool(onToolDenied, toolName);
        return ToolPermissionGate.DecisionResult.deny(
            new com.nexusai.application.agent.permission.PermissionResult.Deny(
                guide, new com.nexusai.application.agent.permission.PermissionDecisionReason.Other(guide),
                toolUseId));
    }

    /**
     * deny 遥测 · CC original: {@code denyAutoMemTool}（extractMemories.ts:154-164）。
     * 只发 {@code tengu_auto_mem_tool_denied(tool_name)}；工具名来自 tool.name()（代码常量集，
     * analytics 安全，无需 CC sanitizeToolNameForAnalytics 消毒）。
     */
    private static void denyAutoMemTool(java.util.function.Consumer<String> onToolDenied, String toolName) {
        if (onToolDenied != null) {
            onToolDenied.accept(toolName);
        }
        if (log.isDebugEnabled()) {
            log.debug("[ExtractMemories] 受限 canUseTool 拒绝工具: tool_name={}", toolName);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // env 读取 helper · CC getFeatureValue_CACHED_MAY_BE_STALE / process.env 等价
    // ════════════════════════════════════════════════════════════════════

    /**
     * 会话态游标 null-safe key · [sm-cursor-sessionize 2026-08-30] null sessionId → "unknown"，
     * 与 {@link com.nexusai.application.agent.memory.SessionMemoryUtils#keyOf} 同兜底惯例。
     */
    private static String cursorKey(String sessionId) {
        return sessionId != null ? sessionId : "unknown";
    }

    /**
     * 读取 env/system property · system property 优先, 回退 System.getenv
     * （CC 读 process.env；Java 测试可设 property 注入，对齐 StopHookPipeline.resolveEnvOrProperty）。
     */
    private static String resolveEnv(String key) {
        String v = System.getProperty(key);
        if (v != null) {
            return v;
        }
        return System.getenv(key);
    }

    /**
     * 节流间隔 env 通道 · [rev2 EX-02/OPD-R2-EX-02] 部署标志等价建模：
     * CC {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_bramble_lintel', null) ?? 1}
     * （extractMemories.ts:380-381）—— Java 用 {@code NEXUSAI_EXTRACT_MEMORIES_INTERVAL}
     * env/system property 建模（property 优先，resolveEnv 语义）；非法/缺省 → 1（对齐 CC
     * 缺省 1）；下限 1（对齐 setter 语义）。
     */
    private static int extractionIntervalFromEnv() {
        String v = resolveEnv("NEXUSAI_EXTRACT_MEMORIES_INTERVAL");
        if (v == null || v.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** CC isEnvTruthy · 接受 "1"/"true"/"yes"/"on" 等 truthy 字符串。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String t = value.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t)
            || "yes".equalsIgnoreCase(t) || "on".equalsIgnoreCase(t);
    }

    /**
     * env {@code NEXUSAI_EXTRACT_MEMORIES} tri-state 解析 · <b>[sm 决策 2026-08-30]</b>
     * 可选强制关/开运维覆盖（总闸已移至 DB auto_memory_enabled）。null = 未设/不可解析 →
     * 不影响（交 DB 主控）；TRUE = 强制开（绕过 DB gate）；FALSE = 强制关。
     * CC original: {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_passport_quail', false)}
     * （extractMemories.ts:536）。StopHookPipeline.isExtractModeActive 复用同一解析避免两处漂移。
     */
    public static Boolean extractionEnvOverride() {
        return parseTriState(resolveEnv("NEXUSAI_EXTRACT_MEMORIES"));
    }

    /** env tri-state 解析 · null = 未设/空白/不可解析（不影响）；truthy → TRUE；falsy → FALSE。 */
    private static Boolean parseTriState(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.trim();
        if ("false".equalsIgnoreCase(t) || "0".equals(t)
            || "off".equalsIgnoreCase(t) || "no".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        if ("true".equalsIgnoreCase(t) || "1".equals(t)
            || "on".equalsIgnoreCase(t) || "yes".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        return null;
    }

    // ── record ──

    /**
     * 提取结果 · 对齐 CC extractMemories.ts 的 writtenPaths/memoryPaths 语义
     * （memoryPaths = 排除 MEMORY.md 索引后的写入文件数）。
     *
     * @param newCount     写入的记忆文件数（不含 MEMORY.md 索引）· CC original: memoryPaths.length
     * @param writtenPaths fork 写入的全部文件路径 · CC original: writtenPaths
     */
    public record ExtractResult(int newCount, List<String> writtenPaths) {
        public ExtractResult {
            if (writtenPaths == null) {
                writtenPaths = List.of();
            }
        }
    }
}

package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkRawMaterial;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.config.MemoryRemoteModeConfig;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.tasks.DreamTaskRegistry;
import com.nexusai.application.agent.tasks.DreamTaskState;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 记忆自动整合器 · 对齐 CC services/autoDream/autoDream.ts。
 *
 * <p><b>IMP-M-P2-1 锁并发/rollback/遥测（DEL-M-23..27/28 落地）</b>：
 * <ol>
 *   <li><b>单 lock-mtime</b>（consolidationLock.ts:1-5）—— 锁文件 mtime 即
 *       lastConsolidatedAt；`.lastConsolidatedAt`/`.lastScanAt` 三文件戳记已移除（DEL-M-28），
 *       锁 IO 收敛到 {@link ConsolidationLock}（readLastConsolidatedAt/tryAcquire/rollback）。</li>
 *   <li><b>锁并发</b>（consolidationLock.ts:46-84）—— stale 窗口(1h)内且持有者 PID 存活 → 阻塞；
 *       死 PID/超 stale → 回收；写 PID + re-read 校验（双 reclaimer 竞态 loser bail）。</li>
 *   <li><b>rollback</b>（consolidationLock.ts:91-108）—— 合并失败回退 mtime（priorMtime=0 → unlink）。
 *       CC abort 中 DreamTask.kill() 已回滚（DreamTask.ts:136-155），catch 不双回滚（autoDream.ts:262-265）；
 *       Java 接入 kill 后：registry.kill 内回滚（唯一回滚点），catch abort 分支经
 *       {@link DreamTaskRegistry#isKilled} 判定不双回滚；非 kill 中止仍回滚锁（防时间门 24h 阻断）。</li>
 *   <li><b>遥测</b>（autoDream.ts:195-198/252-257/267）—— tengu_auto_dream_fired/completed/failed
 *       （recordEvent + logOTelEvent 双发射 · HookRegistry:278-279 惯例）。</li>
 *   <li><b>isGateOpen 四重门控</b>（autoDream.ts:95-100）—— KAIROS 维度 Web 后端 N/A
 *       （OPD-M-26）；remote 门（autoDream.ts:97 getIsRemoteMode）经
 *       {@link MemoryRemoteModeConfig} 建模（nexusai.memory.remote-mode，默认 false，IMP-MV2-14
 *       D2-R6 补齐），autoMemory + autoDream 三重 + 时间/扫描节流/会话/锁。</li>
 *   <li><b>会话门控数据源</b>（consolidationLock.ts:118-124 listSessionsTouchedSince）—— 扫
 *       <b>扁平</b> transcript（[S2] {configHome}/projects/{slug}/{sessionId}.jsonl，
 *       SessionStorage.getTranscriptPath config-home 派生布局，对齐 listCandidates:169-198 扁平扫描），
 *       UUID 校验排除 agent-*.jsonl（非 UUID 前缀），
 *       排除当前 session（autoDream.ts:164），.md 计数偏移已修正（OPD-M-31）。</li>
 *   <li><b>fork 写文件</b>（autoDream.ts:224-233）—— 恒 runForkedAgent + abortController +
 *       onMessage watcher（makeDreamProgressWatcher 等价，收集 touchedPaths）。</li>
 *   <li><b>dream registry 接线</b>（OPD-TP-09 · autoDream.ts:203-208/235/268/306-311）—— fork 前
 *       registerDreamTask（sessionsReviewing/priorMtime/abortController）→ watcher addDreamTurn
 *       → 成功 completeDreamTask（:235）/ 失败 failDreamTask（:268）/ kill 路径 abort 不双回滚
 *       （:262-265）。</li>
 *   <li><b>D5-A/M-11 参数化</b> —— consolidateIfNeeded/scanSessionTranscripts 改收
 *       (workspaceDir, sessionId) 显式参数（替代共享 volatile 读写）：@Bean 共享 + 异步触发
 *       （StopHookPipeline runAsync）的跨会话交错窗口消除（会话 A 注入后 B 改写 volatile，
 *       A 的异步合并扫到 B 目录/排除 B session）。调用点按会话捕获后透传。</li>
 * </ol>
 *
 * <p><b>注入 seam</b>：CC 调全局 query()；Java 经 {@link RunForkedAgent.ForkedQuery} 函数式
 * seam 注入（生产由 ToolRegistrationConfig.autoDreamConsolidator 注入
 * {@link com.nexusai.application.agent.compact.fork.ProductionForkedQuery}）。
 *
 * <p><b>手动 /dream（OPD-CM5-E-06）</b>：CC 补回真源 {@code skills/bundled/dream.ts} 后，
 * recordConsolidation 非死代码（CC /dream skill 消费）；Java 侧 {@link #doDream} 手动整合
 * 核心执行（{@link ConsolidationLock#recordConsolidation()} 盖章 + DREAM_PROMPT_PREFIX 前缀 +
 * buildConsolidationPrompt + fork 执行）。<b>保留未接线</b>（dead-code-decision-rule：CC 有
 * dream.ts 对应，倾向保留）—— 生产 REST /dream 由 ExtractMemoriesController.dream() 返回
 * prompt 文本（CC getPromptForCommand 忠实等价），不调用本方法；语义分歧见 {@link #doDream}。
 *
 * <p><b>未实现项</b>：bg-tasks UI（DreamDetailDialog 前端渲染）—— Web 后端无 Ink TUI，dream
 * task 以 TaskFrameworkService store + SDK 事件暴露（OPD-TP-11 前端联调）。
 */
public class AutoDreamConsolidator {

    private static final Logger log = LoggerFactory.getLogger(AutoDreamConsolidator.class);

    /**
     * CC autoDream.ts:56 SESSION_SCAN_INTERVAL_MS = 10min 扫描节流（CC 固定常量，非 GB 动态）。
     */
    private static final long SCAN_THROTTLE_MS = 10 * 60 * 1000L;

    /**
     * 手动 /dream prompt 前缀 · CC original: {@code DREAM_PROMPT_PREFIX}
     * (Open-ClaudeCode/src/skills/bundled/dream.ts:12-16)。与自动 dream 的区别提示：
     * 全权限 + 用户围观（CC 交互循环语义；Web 后端 fork 仍以受限 canUseTool 落地，见
     * {@link #doDream}）。
     */
    private static final String DREAM_PROMPT_PREFIX = "# Dream: Memory Consolidation (manual run)\n\n"
        + "You are performing a manual dream — a reflective pass over your memory files. "
        + "Unlike the automatic background dream, this run has full tool permissions and the user is watching. "
        + "Synthesize what you've learned recently into durable, well-organized memories so that future sessions can orient quickly.\n\n";

    /**
     * 调度阈值配置 · CC original: {@code type AutoDreamConfig}（autoDream.ts:58-61）——
     * GB tengu_onyx_plover 的 minHours/minSessions（config.ts + autoDream.ts:73-91 getConfig()）。
     * Java 无 GrowthBook → Spring property/env 代偿（部署可改），生产注入见 ToolRegistrationConfig。
     */
    public record AutoDreamConfig(double minHours, int minSessions) {}

    /** CC 缺省 · autoDream.ts:63-66 DEFAULTS = { minHours: 24, minSessions: 5 } */
    public static final AutoDreamConfig DEFAULTS = new AutoDreamConfig(24.0, 5);

    /**
     * 动态阈值供应 · CC original: {@code getConfig()}（autoDream.ts:73-91）每轮 runAutoDream
     * 读取一次（字段校验 + 缺省兜底）。默认 {@link #DEFAULTS}；生产经 setConfigSupplier 注入
     * Spring property（{@code nexusai.autodream.min-hours}/{@code min-sessions}）。
     */
    private volatile Supplier<AutoDreamConfig> configSupplier = () -> DEFAULTS;

    /** UUID 校验 · CC original: sessionStoragePortable.ts:23 uuidRegex（/^[0-9a-f]{8}-.../i） */
    private static final java.util.regex.Pattern UUID_PATTERN =
        java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final MemoryStorage storage;

    /**
     * remote mode 门控 · CC original: {@code getIsRemoteMode()}（autoDream.ts:97，
     * bootstrap/state.ts:1631-1633）。默认 {@link MemoryRemoteModeConfig#isRemoteMode()}
     * （{@code nexusai.memory.remote-mode} 配置，默认 false 对齐 CC state.ts:390）。
     * 测试可经 {@link #setRemoteMode} 注入覆盖。
     */
    private volatile BooleanSupplier remoteMode = MemoryRemoteModeConfig::isRemoteMode;

    /**
     * isAutoMemoryEnabled 门控 · CC original: isGateOpen 内的 isAutoMemoryEnabled()
     * （autoDream.ts:98，paths.ts:30-56）。默认 BundledSkillEnabledGates（env 门控，缺省 true）。
     */
    private volatile BooleanSupplier autoMemoryEnabled = BundledSkillEnabledGates::isAutoMemoryEnabled;

    /**
     * isAutoDreamEnabled 门控 · CC original: {@code isAutoDreamEnabled()}（config.ts:13-21）。
     * <b>[V56 · 用户 2026-08-30 拍板] autoDream 统一走 DB 表列 + 默认开 + 弃文件</b>：
     * 覆盖旧 OPD-CM3-24 Q1（2026-08-15「记忆提取默认关对齐 CC 需显式开启」）——新决策为
     * <b>默认开</b>，且不再读 settings.json 文件承载键（DB settings 列
     * auto_dream_enabled 为主控，V56 建列；对齐 autoMemory 的 V34 auto_memory_enabled 先例）。
     *
     * <p><b>IMP-MV2-13 D2-R5</b> 备案：SupportedSettings:140 注册 + SettingsSchemaGenerator:240
     * schema 声明的键原为「settings→env→false 三源链」消费（CC config.ts:13-21 同构）；
     * V56 后该键改由 DB 列消费（settings.json schema 声明语义随之过时，归属 schema 生成侧
     * 【待验证】是否移除）。CC supportedSettings.ts:64-67 与 types.ts:950-953 均声明该键 →
     * DB 列承载仍满足「配置面不悬空」意图。
     */
    private volatile BooleanSupplier autoDreamEnabled =
        AutoDreamConsolidator::isAutoDreamEnabledBySettingsOrEnv;

    /**
     * DB→env→默认true 三源链 · CC original: {@code isAutoDreamEnabled()}（config.ts:13-21）——
     * {@code getInitialSettings().autoDreamEnabled} 显式布尔设置覆盖（config.ts:14-15 直接
     * 返回）；未设 → GB tengu_onyx_plover。
     *
     * <p><b>[V56 · 用户 2026-08-30 拍板] autoDream 统一走 DB 表列 + 默认开 + 弃文件</b>：
     * <ol>
     *   <li>DB settings 列 auto_dream_enabled（{@link BundledSkillEnabledGates
     *       #readAutoDreamEnabledSetting()}，V56 建列）优先——有值用之（前端可配，对齐
     *       autoMemoryEnabled 的 V34 先例）。</li>
     *   <li>未配置 DB → NEXUSAI_AUTO_DREAM env 显式值可选强制覆盖：非 blank 值经
     *       {@link #isEnvTruthy} 判定（"1"/"true"/"yes" → true，其余 → false=关闭）。
     *       <b>登记（低危 · V56 反思）</b>：'2'/'abc' 等非 truthy 也非显式 falsy 的值同样判
     *       false（关闭）——与声明「truthy→true / 显式 falsy→false / 未设跳过」略有偏差，
     *       但为与兄弟模块 ExtractMemoriesAgent extractionGate 同模式（isEnvTruthy 语义），
     *       属可接受一致性分叉，不改代码；未设/blank → 跳过本段（默认 true）。</li>
     *   <li>DB 与 env 均未配置 → <b>默认 true（默认开）</b>——用户决策覆盖旧 OPD-CM3-24 Q1
     *       「默认关对齐 CC 需显式开启」。</li>
     * </ol>
     *
     * <p><b>弃用</b>：不再读 settings.json 文件的 autoDreamEnabled（三源 local→project→user
     * 文件读取 {@code readAutoDreamEnabledSetting/readAutoDreamEnabledFromFile} 已删除）；
     * 文件承载键写侧同步弃用（BundledSkillEnabledGates.writeAutoMemoryToggles 不再写文件）。
     *
     * @return true = auto-dream 启用（DB 显式开启 / env 显式 truthy / 默认开）
     */
    static boolean isAutoDreamEnabledBySettingsOrEnv() {
        Boolean setting = BundledSkillEnabledGates.readAutoDreamEnabledSetting();
        if (setting != null) {
            return setting;
        }
        String env = resolveEnv("NEXUSAI_AUTO_DREAM");
        if (env != null && !env.isBlank()) {
            return isEnvTruthy(env);
        }
        return true;
    }

    /**
     * env/system property 双通道读取 · 对齐 ExtractMemoriesAgent.resolveEnv
     * （ExtractMemoriesAgent.java:878-885）—— Java 测试可设 property 注入，生产读 env。
     */
    private static String resolveEnv(String key) {
        String v = System.getProperty(key);
        if (v != null) {
            return v;
        }
        return System.getenv(key);
    }

    /** 扫描节流内存变量 · CC original: 闭包内存变量 lastSessionScanAt（autoDream.ts:123/143-151） */
    private volatile long lastSessionScanAt = 0L;

    /** fork 查询 seam · 测试注入 RecordingQuery；生产接 ProductionForkedQuery */
    private volatile RunForkedAgent.ForkedQuery forkedQuery;

    /** cache-safe params 供应 · null → createMinimalCacheSafeParams 兜底 */
    private volatile Supplier<CacheSafeParams> cacheSafeParamsSupplier;

    /**
     * firstParty fork 缓存共享 gate 供应 · 兜底（RES-C5）CacheSafeParams 的
     * {@code useGlobalCacheScope} 来源 · CC original: {@code shouldUseGlobalCacheScope()}
     * (utils/betas.ts:227-233)；由接线方经 GlobalCacheScope 单实现求值注入
     * （REQ-C5-4）；默认 false = Java 3P 默认（boundary 不插入）。
     */
    private volatile Supplier<Boolean> useGlobalCacheScopeSupplier = () -> false;

    /** 遥测注入 · null → 不发射（测试不注入时静默跳过，对齐 CC logEvent 可空上下文） */
    private volatile Telemetry telemetry;


    /**
     * dream 任务注册表 · OPD-TP-09（registerDreamTask + addDreamTurn + complete/fail/kill）。
     *
     * <p>对齐 CC autoDream.ts 接线：fork 前 register（:203-208）→ watcher addDreamTurn（:306-311）
     * → 成功 complete（:235）/ 失败 fail（:268）/ kill 路径 abort 不双回滚（:262-265）。
     * null → 不注册（测试直构无装配时跳过，日志/Improved/遥测行为不变）。
     */
    private volatile DreamTaskRegistry dreamTaskRegistry;

    public AutoDreamConsolidator(MemoryStorage storage) {
        this.storage = storage;
    }

    /**
     * 记忆目录 · CC original: {@code getAutoMemPath()}（paths.ts:223-235）。
     *
     * <p><b>[A1 修复 2026-09-04]</b>：由构造期冻结字段改<b>惰性现算</b>—— 每次调用
     * {@code storage.memoryDir()}（生产 = AutoMemPaths 按当前线程 projectRoot 解析 per-project）。
     * 旧实现构造冻结：bean 构造时无会话上下文 → 回落 config-home 自身 slug（C--Users-WIN--nexusai），
     * 所有会话记忆/锁写错目录。调用方（StopHookPipeline runAsync 入口）须先经
     * {@link AutoMemPaths#setCurrentProjectRoot} 注入会话 projectRoot，否则异步 fork 线程
     * （ForkJoinPool）无 ThreadLocal 仍回落 config-home。
     */
    private Path memoryDir() {
        return storage.memoryDir();
    }

    /**
     * 合并锁 · 绑定 memoryDir（锁文件位于记忆目录内，consolidationLock.ts:21-23 lockPath）。
     * [A1 修复] 由构造期冻结字段改惰性现算 —— 随 {@link #memoryDir()} 每次现算（同一 memoryDir
     * 派生同一锁路径，无状态泄漏；CC 每轮读锁/写锁同样按当前 memory 目录操作）。
     */
    private ConsolidationLock consolidationLock() {
        return new ConsolidationLock(memoryDir());
    }

    /** 注入 isAutoMemoryEnabled 门控（null → false，CC paths.ts:30-56 gate 关闭）。 */
    public void setAutoMemoryEnabled(BooleanSupplier enabled) {
        this.autoMemoryEnabled = enabled != null ? enabled : () -> false;
    }

    /** 注入 isAutoDreamEnabled 门控（null → false，测试/部署显式开启入口 · OPD-CM3-24 Q1）。 */
    public void setAutoDreamEnabled(BooleanSupplier enabled) {
        this.autoDreamEnabled = enabled != null ? enabled : () -> false;
    }

    /**
     * 注入 remote mode 门控（null → false，CC autoDream.ts:97 getIsRemoteMode 关闭；
     * 同 ExtractMemoriesAgent.setRemoteMode 语义）。
     */
    public void setRemoteMode(BooleanSupplier remoteMode) {
        this.remoteMode = remoteMode != null ? remoteMode : () -> false;
    }

    /**
     * 注入 dream 任务注册表 · OPD-TP-09（对齐 CC autoDream.ts:203-208 register 接线）。
     *
     * <p>同时把 {@link #rollbackConsolidationLockSeam} 注入注册表 —— DreamTask.kill
     * （DreamTask.ts:153-155）回退锁 mtime 时经此 seam 调用本类 ConsolidationLock（单回滚点）。
     *
     * @param registry dream 任务注册表（null → 不注册，测试直构跳过）
     */
    public void setDreamTaskRegistry(DreamTaskRegistry registry) {
        this.dreamTaskRegistry = registry;
        if (registry != null) {
            registry.setRollbackConsolidationLock(this::rollbackConsolidationLockSeam);
        }
    }

    /**
     * kill 回退锁 mtime seam · CC original: {@code rollbackConsolidationLock(priorMtime)}
     * （DreamTask.ts:153-155 + autoDream.ts:270 同路径）。
     *
     * <p>由 {@link DreamTaskRegistry#kill} 在 running→killed 后调用（非 null 时）；
     * priorMtime=0 → unlink，否则写空 body + utimes 回退（consolidationLock.ts:91-108）。
     */
    void rollbackConsolidationLockSeam(long priorMtime) {
        consolidationLock().rollbackConsolidationLock(priorMtime);
    }

    /** 注入遥测（tengu_auto_dream_* 事件 · autoDream.ts:195/252/267）。 */
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** 注入 fork 查询 seam。 */
    public void setForkedQuery(RunForkedAgent.ForkedQuery query) {
        this.forkedQuery = query;
    }

    /** 注入 cache-safe params 供应。 */
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
     * 注入动态阈值供应 · CC original: autoDream.ts:73-91 getConfig()（每轮读取 +
     * 字段校验 + DEFAULTS 兜底）。生产由 ToolRegistrationConfig.autoDreamConsolidator
     * 注入 Spring property（nexusai.autodream.min-hours/min-sessions）。null → DEFAULTS。
     */
    public void setConfigSupplier(Supplier<AutoDreamConfig> supplier) {
        this.configSupplier = supplier != null ? supplier : () -> DEFAULTS;
    }

    // ── 主入口 ──

    /**
     * 检查门控，满足条件时执行 fork 合并 · CC original: {@code runAutoDream}
     * （autoDream.ts:125-272）。
     *
     * <p>门控顺序（autoDream.ts:5-9 注释，最便宜先）：
     * <ol>
     *   <li>isGateOpen（remoteMode + autoMemoryEnabled + autoDreamEnabled，autoDream.ts:95-100；KAIROS N/A）</li>
     *   <li>时间门控（readLastConsolidatedAt 单 stat，autoDream.ts:131-141）</li>
     *   <li>扫描节流（内存变量 lastSessionScanAt，autoDream.ts:143-151）</li>
     *   <li>会话门控（transcript 扫描 + 排除当前 session，autoDream.ts:153-171）</li>
     *   <li>锁门控（tryAcquireConsolidationLock，autoDream.ts:173-190）</li>
     * </ol>
     *
     * <p><b>FIX-AD 动态阈值</b>：每轮经 {@code configSupplier.get()} 读取
     * {@link AutoDreamConfig}（对齐 CC autoDream.ts:126 {@code const cfg = getConfig()}，
     * GB tengu_onyx_plover 每轮读取 + 字段校验），时间/会话门用 cfg 值。
     *
     * <p><b>D5-A/M-11 参数化</b>：workspaceDir/sessionId 显式传参替代共享 volatile 读写
     * （@Bean 共享 + 异步 consolidateIfNeeded 的跨会话交错窗口），调用点（LlmAgentLoop
     * stop-hook 注入处）按会话捕获后经 StopHookPipeline 透传。
     *
     * @param workspaceDir 会话 transcript 扫描根目录（CC getProjectDir(cwd) ·
     *                     consolidationLock.ts:121）
     * @param sessionId    当前 session ID（排除自身 · CC autoDream.ts:164；null = 不排除）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = fork 成功不追加 Improved 完成消息）
     * @see <a href="https://github.com/anthropics/claude-code/blob/main/src/services/autoDream/autoDream.ts#L319-L324">CC executeAutoDream</a>
     */
    public void consolidateIfNeeded(Path workspaceDir, String sessionId,
                                    Consumer<SystemMessage> appendSystemMessage) {
        // 3 参便捷重载 · 无 fork 原料（非主循环调用方：测试/直构）→ null 原料透传，
        // 保持既有兜底（supplier / createMinimalCacheSafeParams，不 fail-loud）。
        consolidateIfNeeded(workspaceDir, sessionId, appendSystemMessage, null);
    }

    /**
     * 检查门控，满足条件时执行 fork 合并 · CC original: {@code runAutoDream}
     * （autoDream.ts:125-272）。
     *
     * <p>门控顺序（autoDream.ts:5-9 注释，最便宜先）：
     * <ol>
     *   <li>isGateOpen（remoteMode + autoMemoryEnabled + autoDreamEnabled，autoDream.ts:95-100；KAIROS N/A）</li>
     *   <li>时间门控（readLastConsolidatedAt 单 stat，autoDream.ts:131-141）</li>
     *   <li>扫描节流（内存变量 lastSessionScanAt，autoDream.ts:143-151）</li>
     *   <li>会话门控（transcript 扫描 + 排除当前 session，autoDream.ts:153-171）</li>
     *   <li>锁门控（tryAcquireConsolidationLock，autoDream.ts:173-190）</li>
     * </ol>
     *
     * <p><b>FIX-AD 动态阈值</b>：每轮经 {@code configSupplier.get()} 读取
     * {@link AutoDreamConfig}（对齐 CC autoDream.ts:126 {@code const cfg = getConfig()}，
     * GB tengu_onyx_plover 每轮读取 + 字段校验），时间/会话门用 cfg 值。
     *
     * <p><b>D5-A/M-11 参数化</b>：workspaceDir/sessionId 显式传参替代共享 volatile 读写
     * （@Bean 共享 + 异步 consolidateIfNeeded 的跨会话交错窗口），调用点（LlmAgentLoop
     * stop-hook 注入处）按会话捕获后经 StopHookPipeline 透传。
     *
     * <p><b>fork 原料（IMP-MV2-09 T9）</b>: {@code forkRawMaterial} 承载主线程
     * systemPrompt/userContext/systemContext/消息快照（LlmAgentLoop:5154 按会话捕获 ·
     * CC createCacheSafeParams(context) forkedAgent.ts:131-141，autoDream.ts:226 消费）——
     * 修复 fork 空载荷（ToolRegistrationConfig:1468-1469）→ dream fork 恢复主系统提示 +
     * prompt-cache key 与主线程一致；null = 无捕获（非主循环调用方），保持既有兜底。
     *
     * @param workspaceDir 会话 transcript 扫描根目录（CC getProjectDir(cwd) ·
     *                     consolidationLock.ts:121）
     * @param sessionId    当前 session ID（排除自身 · CC autoDream.ts:164；null = 不排除）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = fork 成功不追加 Improved 完成消息）
     * @param forkRawMaterial     fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @see <a href="https://github.com/anthropics/claude-code/blob/main/src/services/autoDream/autoDream.ts#L319-L324">CC executeAutoDream</a>
     */
    public void consolidateIfNeeded(Path workspaceDir, String sessionId,
                                    Consumer<SystemMessage> appendSystemMessage,
                                    ForkRawMaterial forkRawMaterial) {
        // 4 参便捷重载 · 无显式 memoryDir（非主循环调用方：测试/直构 storage Path 冻结安全；
        //   生产 StopHookPipeline 必须走 5 参带会话线程解析的 memoryDir —— 本重载现算
        //   storage.memoryDir()，fork 线程下调会回落 config-home，故生产禁用）。
        consolidateIfNeeded(workspaceDir, sessionId, appendSystemMessage, forkRawMaterial, memoryDir());
    }

    /**
     * 检查门控，满足条件时执行 fork 合并 · CC original: {@code runAutoDream}
     * （autoDream.ts:125-272）。
     *
     * <p>[A1 重做 2026-09-04] <b>memoryDir 显式传参</b> —— consolidateIfNeeded 由 StopHookPipeline
     * 在 runAsync（ForkJoinPool）内调用，无会话线程 ThreadLocal。生产调用方（LlmAgentLoop stop-hook，
     * 会话线程）按 boundProject 经 {@link AutoMemPaths#getAutoMemPath(String)} 解析 memoryDir
     * 后显式传入本方法 —— 内部所有锁/合并操作用该 memoryDir（consolidationLock.ts 锁文件位于
     * memory 目录内），fork 线程绝不回读 AutoMemPaths/ThreadLocal。
     *
     * <p>门控顺序（autoDream.ts:5-9 注释，最便宜先）：
     * <ol>
     *   <li>isGateOpen（remoteMode + autoMemoryEnabled + autoDreamEnabled，autoDream.ts:95-100；KAIROS N/A）</li>
     *   <li>时间门控（readLastConsolidatedAt 单 stat，autoDream.ts:131-141）</li>
     *   <li>扫描节流（内存变量 lastSessionScanAt，autoDream.ts:143-151）</li>
     *   <li>会话门控（transcript 扫描 + 排除当前 session，autoDream.ts:153-171）</li>
     *   <li>锁门控（tryAcquireConsolidationLock，autoDream.ts:173-190）</li>
     * </ol>
     *
     * <p><b>FIX-AD 动态阈值</b>：每轮经 {@code configSupplier.get()} 读取
     * {@link AutoDreamConfig}（对齐 CC autoDream.ts:126 {@code const cfg = getConfig()}，
     * GB tengu_onyx_plover 每轮读取 + 字段校验），时间/会话门用 cfg 值。
     *
     * <p><b>D5-A/M-11 参数化</b>：workspaceDir/sessionId/memoryDir 显式传参替代共享 volatile /
     * ThreadLocal 读写（@Bean 共享 + 异步 consolidateIfNeeded 的跨会话交错窗口），调用点
     * （LlmAgentLoop stop-hook 注入处）按会话捕获后经 StopHookPipeline 透传。
     *
     * @param workspaceDir 会话 transcript 扫描根目录（CC getProjectDir(cwd) ·
     *                     consolidationLock.ts:121）
     * @param sessionId    当前 session ID（排除自身 · CC autoDream.ts:164；null = 不排除）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            null = fork 成功不追加 Improved 完成消息）
     * @param forkRawMaterial     fork 原料（主线程 systemPrompt/userContext/systemContext/
     *                            快照 · forkedAgent.ts:131-141；null = 无捕获兜底）
     * @param memoryDir    会话解析的 auto-memory 目录（LlmAgentLoop 会话线程 getAutoMemPath(boundProject)
     *                     算好传入 · fork 全链用，绝不回读 ThreadLocal）
     * @see <a href="https://github.com/anthropics/claude-code/blob/main/src/services/autoDream/autoDream.ts#L319-L324">CC executeAutoDream</a>
     */
    public void consolidateIfNeeded(Path workspaceDir, String sessionId,
                                    Consumer<SystemMessage> appendSystemMessage,
                                    ForkRawMaterial forkRawMaterial,
                                    Path memoryDir) {
        // 动态阈值（autoDream.ts:126 每轮 getConfig · FIX-AD 替代硬编码 24/5）
        AutoDreamConfig cfg = effectiveConfig();
        ConsolidationLock lock = new ConsolidationLock(memoryDir);
        // isGateOpen · remote mode（autoDream.ts:97 getIsRemoteMode；位于 autoMemory 之前 =
        //   CC 门序 autoDream.ts:95-100 最便宜先，KAIROS=N/A · OPD-M-26）
        if (remoteMode.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] remote mode → 跳过合并（CC autoDream.ts:97）");
            }
            return;
        }
        // isGateOpen · autoMemory（autoDream.ts:98 isAutoMemoryEnabled）
        if (!autoMemoryEnabled.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] isAutoMemoryEnabled=false（paths.ts:30-56），跳过合并");
            }
            return;
        }
        // isGateOpen · isAutoDreamEnabled（config.ts:13）
        if (!autoDreamEnabled.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] isAutoDreamEnabled=false（config.ts:13 + 部署标志），跳过合并");
            }
            return;
        }
        // 时间门控（consolidationLock.ts:29-36 单 stat · 动态 minHours）
        Long lastAt = checkTimeGate(cfg.minHours(), lock);
        if (lastAt == null) {
            return;
        }
        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        // 扫描节流（autoDream.ts:143-151）
        if (!checkScanThrottle()) {
            return;
        }
        // 会话门控（consolidationLock.ts:118-124 listSessionsTouchedSince 等价 · 动态 minSessions）
        List<String> sessionIds = scanSessionTranscripts(workspaceDir, sessionId, lastAt);
        if (sessionIds.size() < cfg.minSessions()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] session_gate 阻断: {} sessions since last, need {}（动态阈值）",
                    sessionIds.size(), cfg.minSessions());
            }
            return;
        }
        // 锁门控（consolidationLock.ts:46-84 tryAcquireConsolidationLock）
        Long priorMtime = acquireLock(lock);
        if (priorMtime == null) {
            return;
        }
        doConsolidate(workspaceDir, priorMtime, sessionIds, hoursSince, appendSystemMessage, forkRawMaterial, memoryDir, lock);
    }

    /**
     * 每轮求值动态阈值 · CC original: {@code getConfig()}（autoDream.ts:73-91）——
     * 逐字段校验（typeof number && isFinite && >0），非法/缺失回退 DEFAULTS（24/5）。
     * Java 无 GB 类型风险 → 仍按 CC 语义做防御性校验（部署配置错写不破时间门）。
     */
    private AutoDreamConfig effectiveConfig() {
        AutoDreamConfig raw = configSupplier != null ? configSupplier.get() : null;
        double minHours = (raw != null && Double.isFinite(raw.minHours()) && raw.minHours() > 0)
            ? raw.minHours() : DEFAULTS.minHours();
        int minSessions = (raw != null && raw.minSessions() > 0)
            ? raw.minSessions() : DEFAULTS.minSessions();
        return new AutoDreamConfig(minHours, minSessions);
    }

    /**
     * 时间门控 · CC original: autoDream.ts:131-141（readLastConsolidatedAt）。
     *
     * <p>锁文件 mtime 即 lastConsolidatedAt；无锁 → 0 → 首次运行通过（hoursSince 巨大）。
     * 读失败 → 0（CC fail-open，readLastConsolidatedAt 内部 catch 返回 0，OPD-M-32 回归）。
     *
     * @param minHours 动态阈值（CC autoDream.ts:141 {@code hoursSince < cfg.minHours}）
     * @param lock     本会话 memoryDir 派生的锁（[A1 重做] 显式传入，不现算 storage.memoryDir()）
     * @return 通过 → 上次合并时间 ms（0 = 从未合并）；阻断 → null
     */
    Long checkTimeGate(double minHours, ConsolidationLock lock) {
        long lastAt = lock.readLastConsolidatedAt();
        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        if (hoursSince < minHours) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] time_gate 阻断: hoursSince={}h < {}h（动态阈值）", hoursSince, minHours);
            }
            return null;
        }
        return lastAt;
    }

    /**
     * 扫描节流 · CC original: autoDream.ts:143-151 闭包内存变量 lastSessionScanAt。
     *
     * <p>时间门控通过但会话门控未过时，锁 mtime 不推进 → 时间门控每轮都过；节流避免每轮
     * 重扫 transcript（SESSION_SCAN_INTERVAL_MS=10min）。内存变量（非文件，DEL-M-28）。
     */
    boolean checkScanThrottle() {
        long sinceScanMs = System.currentTimeMillis() - lastSessionScanAt;
        if (sinceScanMs < SCAN_THROTTLE_MS) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] scan_throttle 阻断: 上次扫描 {}s 前", Math.round(sinceScanMs / 1000.0));
            }
            return false;
        }
        lastSessionScanAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 会话门控 · CC original: {@code listSessionsTouchedSince}（consolidationLock.ts:118-124）
     * = {@code listCandidates(getProjectDir(cwd), true)} + mtime 过滤 + autoDream.ts:153-171。
     *
     * <p><b>IMP-M-P2-1 扁平化修正</b>：CC 扫<b>扁平</b> {projectDir}/*.jsonl（listCandidates:169-198
     * 对 readdir 顶层 *.jsonl 做 UUID 校验，非嵌套 session 目录）。生产 sessionId=UUID
     * （LlmAgentLoop:1531 params.sessionId()），transcript 走 SessionStorage.getTranscriptPath
     * [S2] config-home 派生扁平布局（{configHome}/projects/{slug}/{sessionId}.jsonl）；旧嵌套
     * {workspaceDir}/{sessionId}/session.jsonl 会令生产 auto-dream 永不触发。
     * 对齐 listCandidates 语义：name.endsWith('.jsonl') + validateUuid(name 去 .jsonl)
     * （sessionStoragePortable.ts:26-30，agent-*.jsonl 非 UUID 被排除）+ mtime > sinceMs + 排除当前 session。
     *
     * @param workspaceDir 会话 transcript 扫描根目录（[S2] 调用方已传 config-home 项目 slug 目录
     *                     = CC getProjectDir(cwd) · consolidationLock.ts:121；D5-A/M-11 参数化显式传入；
     *                     null → 会话门阻断）
     * @param sessionId    当前 session ID（排除自身 · CC autoDream.ts:164；null = 不排除）
     * @param sinceMs      上次合并时间 ms
     * @return mtime 晚于 sinceMs 的 sessionId 列表（已排除当前 session）
     */
    List<String> scanSessionTranscripts(Path workspaceDir, String sessionId, long sinceMs) {
        List<String> result = new ArrayList<>();
        // [BUG-FIX 2026-09-04 autoDream 永不触发] transcript 不在 workspaceDir(项目根)平铺——
        //   生产 transcript = {configHome}/projects/{sanitize(workspaceDir)}/{sessionId}.jsonl(S2 扁平布局,
        //   SessionStorage.getProjectDir(workspaceDir) = getProjectsDir()/sanitizePath(projectRoot))，
        //   对齐 CC listSessionsTouchedSince → getProjectDir(getOriginalCwd())（consolidationLock.ts:121-122）。
        //   原实现 base=workspaceDir(项目根,无 .jsonl) → 会话门恒 0 < minSessions=5 → autoDream 永不
        //   fire → auto-memory 永远空。workspaceDir(null/不存在)仍阻断(防御)。
        Path base = workspaceDir == null ? null : SessionStorage.getProjectDir(workspaceDir);
        if (base == null || !Files.isDirectory(base)) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] transcript 基目录不存在或不可读，会话门阻断: {}", base);
            }
            return result;
        }
        try (Stream<Path> files = Files.list(base)) {
            files.forEach(file -> {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".jsonl")) {
                    return; // listCandidates:183 name.endsWith('.jsonl')
                }
                String candidateId = fileName.substring(0, fileName.length() - ".jsonl".length());
                // [B1 修复 2026-09-04] 会话门过滤对齐 CC 意图（排除 agent-*.jsonl 子代理 sidechain）
                //   —— 生产主会话 transcript 文件名 = sess-<hex>.jsonl（[session-id-short] 键型
                //   UUID→String short 形态），旧实现照抄 CC validateUuid（sessionStoragePortable.ts:26-30）
                //   只认 UUID → 生产 sess-*.jsonl 全被排除 → 会话门恒 0 → autoDream 永不触发。
                //   CC validateUuid 的本意（listSessionsImpl.ts:184 注释）是排除 agent-*.jsonl
                //   （子代理文件，CC 主会话恰好 UUID 命名故用 UUID 校验）。Java 主会话 = sess-*，
                //   故按意图对齐：排除 agent-* 前缀 + 计入 sess-* 前缀（UUID 老格式仍兼容保留）。
                if (candidateId.startsWith("agent-")) {
                    return; // 子代理 sidechain（SessionStorage:85 agent-${agentId}.jsonl）排除
                }
                if (!isUuid(candidateId) && !candidateId.startsWith("sess-")) {
                    return; // 非会话文件排除（CC validateUuid 等价意图）
                }
                if (sessionId != null && sessionId.equals(candidateId)) {
                    return; // 排除当前 session（autoDream.ts:164 · D5-A 参数化显式传入）
                }
                try {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime > sinceMs) {
                        result.add(candidateId);
                    }
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[AutoDream] 读取 transcript mtime 失败: {}", e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[AutoDream] 扫描 transcript 目录失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * UUID 校验 · CC original: {@code validateUuid}（sessionStoragePortable.ts:26-30，
     * uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i）。
     * 生产 sessionId=UUID（LlmAgentLoop:1531 params.sessionId()），agent-*.jsonl 等非 UUID 文件名
     * 经此排除。
     */
    private static boolean isUuid(String s) {
        return UUID_PATTERN.matcher(s).matches();
    }

    /**
     * 锁门控 · CC original: {@code tryAcquireConsolidationLock}（consolidationLock.ts:46-84）。
     *
     * <p>stale 窗口(1h)内且持有者 PID 存活 → null（阻塞）；否则写 PID + re-read 校验，
     * 返回 priorMtime（0 = 获取前无锁文件）。
     *
     * @param lock 本会话 memoryDir 派生的锁（[A1 重做] 显式传入，不现算 storage.memoryDir()）
     * @return 获取成功 → priorMtime（供 rollback）；被持有/竞态失败 → null
     */
    Long acquireLock(ConsolidationLock lock) {
        Long priorMtime = lock.tryAcquireConsolidationLock();
        if (priorMtime == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] lock_gate 阻断: 锁被持有（stale 窗口内 PID 存活）");
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[AutoDream] 锁已获取: priorMtime={}", priorMtime);
        }
        return priorMtime;
    }

    // ── 合并逻辑 · CC autoDream.ts:210-271（fork 直接写文件，无 JSON keep/delete）──

    /**
     * 执行合并 · 对齐 CC autoDream.ts:210-271 runAutoDream fork 部分。
     *
     * <ol>
     *   <li>遥测 fired（autoDream.ts:195-198，hours_since + sessions_since）</li>
     *   <li>构造 buildConsolidationPrompt（4 阶段 dream prompt）+ 受限 canUseTool + extra
     *       （tool constraints + sessions 列表，autoDream.ts:216-221）</li>
     *   <li>RunForkedAgent.run(params, forkedQuery) · skipTranscript=true + querySource='auto_dream'
     *       + abortController + onMessage watcher（autoDream.ts:224-233）</li>
     *   <li>成功：遥测 completed（autoDream.ts:252-257）；<b>不删锁</b> —— 锁 mtime 即
     *       lastConsolidatedAt 留在 now（consolidationLock.ts:42-44）</li>
     *   <li>失败：aborted → 回滚锁 + 不发射 failed（CC kill 已回滚终态 autoDream.ts:262-265；
     *       Java 无 kill 路径此分支为唯一回滚点）；否则遥测 failed +
     *       rollbackConsolidationLock(priorMtime)（autoDream.ts:266-271）</li>
     * </ol>
     *
     * @param workspaceDir 会话 transcript 扫描根目录（[S2] 调用方根已迁 config-home 项目 slug 目录，
     *                     D5-A/M-11 参数化透传；null → transcriptDir 降级 memoryRoot）
     * @param priorMtime  锁获取前 mtime（rollback 回退目标）
     * @param sessionIds  门控收集的待审 session 列表（extra + 遥测）
     * @param hoursSince  距上次合并小时数（fired 遥测）
     * @param appendSystemMessage UI 系统消息回调（CC toolUseContext.appendSystemMessage；
     *                            成功且 touchedPaths 非空时经其追加 Improved 完成消息，autoDream.ts:238-248）
     */
    private void doConsolidate(Path workspaceDir, long priorMtime, List<String> sessionIds,
                               double hoursSince, Consumer<SystemMessage> appendSystemMessage,
                               ForkRawMaterial forkRawMaterial, Path memoryDir, ConsolidationLock lock) {
        emitTelemetry("tengu_auto_dream_fired", Map.of(
            "hours_since", Math.round(hoursSince),
            "sessions_since", sessionIds.size()));
        log.info("[AutoDream] 发起合并: {}h since last, {} sessions to review",
            String.format("%.1f", hoursSince), sessionIds.size());
        AbortController abortController = new AbortController();
        List<String> touchedPaths = new ArrayList<>();
        // OPD-TP-09: 注册 dream 任务（对齐 autoDream.ts:203-208 —— fired 遥测后、fork 前
        //   registerDreamTask({sessionsReviewing: sessionIds.length, priorMtime, abortController})）
        //   null-safe：未装配 registry（测试直构）时 taskId=null → 不注册，其余行为不变。
        String dreamTaskId = null;
        if (dreamTaskRegistry != null) {
            dreamTaskId = dreamTaskRegistry.registerDreamTask(
                sessionIds.size(), priorMtime, abortController);
        }
        try {
            // 2. fork prompt（autoDream.ts:211-222 buildConsolidationPrompt）
            String memoryRoot = memoryDir.toString();
            String transcriptDir = workspaceDir != null ? workspaceDir.toString() : memoryRoot;
            String extra = buildExtra(sessionIds);
            String prompt = ConsolidationPrompt.buildConsolidationPrompt(memoryRoot, transcriptDir, extra);

            // 3. fork 直接写文件（autoDream.ts:224-233 · overrides.abortController + onMessage）
            ForkedAgentParams params = buildForkParams(prompt, dreamTaskId, abortController,
                touchedPaths, forkRawMaterial, memoryDir);

            ForkedAgentResult result = RunForkedAgent.run(params, forkedQuery);
            ForkedAgentResult.ForkUsage usage = result.totalUsage() != null
                ? result.totalUsage() : ForkedAgentResult.ForkUsage.empty();

            // 3.5 OPD-TP-09: fork 成功 → completeDreamTask（对齐 autoDream.ts:235 —— completed +
            //   endTime + notified:true 立即 + abortController 清空）
            if (dreamTaskId != null) {
                dreamTaskRegistry.completeDreamTask(dreamTaskId);
            }
            // 4. 遥测 completed（autoDream.ts:249-257）—— 成功不删锁，锁 mtime 留 now
            emitTelemetry("tengu_auto_dream_completed", Map.of(
                "cache_read", usage.cacheReadInputTokens(),
                "cache_created", usage.cacheCreationInputTokens(),
                "output", usage.outputTokens(),
                "sessions_reviewed", sessionIds.size()));
            log.info("[AutoDream] 合并完成（fork 直接写文件 · 锁 mtime 即 lastConsolidatedAt，不删锁）"
                + " cache_read={} cache_created={} output={} touchedPaths={}",
                usage.cacheReadInputTokens(), usage.cacheCreationInputTokens(),
                usage.outputTokens(), touchedPaths.size());
            // 5. Improved 完成消息 · CC original: autoDream.ts:238-248 —— fork 成功且
            //    filesTouched.length > 0 时，经 appendSystemMessage 追加 verb='Improved' 的
            //    memory_saved 系统消息（主 transcript 内联摘要，同 extract memory_saved surface）。
            //    空 touchedPaths 不发（autoDream.ts:242 dreamState.filesTouched.length > 0）。
            if (appendSystemMessage != null && !touchedPaths.isEmpty()) {
                appendSystemMessage.accept(SystemMessage.memorySavedImproved(touchedPaths));
                log.info("[AutoDream] 已追加 Improved 完成消息（memory_saved verb=Improved, writtenPaths={}）",
                    touchedPaths.size());
            }
            return;
        } catch (Exception e) {
            // abort 分支 · 对齐 CC autoDream.ts:262-265：用户从 bg-tasks dialog kill →
            //   DreamTask.kill 已 abort + rollback 锁 + 置 status=killed，catch 直接 return
            //   不双回滚也不发 failed。
            //   Java 防御（S1 对齐 + 平台差异）：仅当 registry 确认任务已被 kill（status=killed）
            //   才跳过回滚；非 kill 中止（如用户 Esc 直通共享 controller）仍回滚锁（原行为，
            //   防 mtime=now 使时间门 24h 阻断，下轮无法重试）。
            //   [D-9 登记 · IMP-MV2-40] △-D3：非 kill abort 额外回滚 = 平台防御的有意分叉（CC 假定
            //   abort 必来自 kill → 直接 return）；isKilled 判定闭环保证 rollback 恰好一次 —— 登记保留。
            if (abortController.isCancelled()) {
                boolean killed = dreamTaskRegistry != null && dreamTaskRegistry.isKilled(dreamTaskId);
                if (killed) {
                    if (log.isDebugEnabled()) {
                        log.debug("[AutoDream] 合并被 kill（TaskStop 分发），DreamTask.kill 已回滚锁，catch 不双回滚（autoDream.ts:262-265）");
                    }
                    return;
                }
                if (log.isDebugEnabled()) {
                    log.debug("[AutoDream] 合并被中止（非 kill 路径），回滚锁（防 mtime=now 阻断下轮）");
                }
                lock.rollbackConsolidationLock(priorMtime);
                return;
            }
            // 5. 遥测 failed + rollback 回退 mtime（autoDream.ts:266-271）
            emitTelemetry("tengu_auto_dream_failed", Map.of());
            log.error("[AutoDream] 合并失败，回退锁: {}", e.getMessage());
            if (dreamTaskId != null) {
                dreamTaskRegistry.failDreamTask(dreamTaskId);
            }
            lock.rollbackConsolidationLock(priorMtime);
            return;
        }
    }

    /**
     * 手动 /dream 整合 · CC original: {@code skills/bundled/dream.ts getPromptForCommand}
     * （Open-ClaudeCode/src/skills/bundled/dream.ts:27-42）。
     *
     * <p><b>OPD-CM5-E-06 doDream 核心整合执行</b>：CC /dream skill 为手动斜杠命令 ——
     * {@code memoryRoot = getAutoMemPath()}（= 本类 memoryDir）+ {@code transcriptDir =
     * getProjectDir(getOriginalCwd())}（= workspaceDir 参数）+ {@code recordConsolidation()}
     * （手动乐观盖章，dream.ts:32）+ {@code DREAM_PROMPT_PREFIX + buildConsolidationPrompt(...)}
     * （:34-35）+ 可选 {@code \n\n## Additional context from user\n\n} + args（:37-38）。
     *
     * <p><b>Web 后端等价与接线状态</b>：CC 交互循环返回 prompt 由主 agent 执行；Java Web 后端
     * 无交互 loop。生产 REST /dream（ExtractMemoriesController.dream()）返回 prompt 文本
     * （CC getPromptForCommand 忠实等价），<b>不调用本方法</b>；本方法为后端受限 canUseTool
     * fork 执行（createAutoMemCanUseTool 共享自动 dream 权限面），与 Controller 的交互式主
     * agent 语义分歧（自动 dream 式后台受限 fork vs 全权限交互执行）<b>显式披露</b> —— 当前
     * <strong>保留未接线</strong>（dead-code-decision-rule：CC 有 dream.ts 对应，倾向保留），
     * 未来如需服务端执行模式再接线。执行链：① gate（isAutoMemoryEnabled，dream.ts:26
     * isEnabled）→ ② recordConsolidation 盖章 → ③ 组装手动 prompt（DREAM_PROMPT_PREFIX +
     * 4 阶段模板 + args）→ ④ buildForkParams 构建受限 canUseTool fork → ⑤ RunForkedAgent
     * 执行整合。返回 {@link DreamResult}（当前仅测试消费）。
     *
     * @param workspaceDir 会话 transcript 扫描根目录（CC getProjectDir(getOriginalCwd()) ·
     *                     consolidationLock.ts:121；null → transcriptDir 降级 memoryRoot）
     * @param args         用户附加上下文（CC dream.ts:37-38 {@code ## Additional context from user}；
     *                     null/blank → 不附加）
     * @return 手动整合结果（started + writtenPaths）
     */
    public DreamResult doDream(Path workspaceDir, String args) {
        return doDream(workspaceDir, args, null);
    }

    /**
     * 手动 /dream 整合（带 fork 原料）· 同 {@link #doDream(Path, String)}，追加
     * {@code forkRawMaterial} 透传（主线程 systemPrompt/userContext/systemContext/快照 ·
     * forkedAgent.ts:131-141；null = 无捕获兜底，走 createMinimalCacheSafeParams）。
     *
     * @param workspaceDir   会话 transcript 扫描根目录（CC getProjectDir(getOriginalCwd())）
     * @param args           用户附加上下文（CC dream.ts:37-38；null/blank → 不附加）
     * @param forkRawMaterial fork 原料（主线程 systemPrompt 等 · forkedAgent.ts:131-141；null = 兜底）
     * @return 手动整合结果（started + writtenPaths）
     */
    public DreamResult doDream(Path workspaceDir, String args, ForkRawMaterial forkRawMaterial) {
        // ① gate：CC dream.ts:26 isEnabled = () => isAutoMemoryEnabled()（skill 仅在
        //    auto-memory 启用时注册；本方法同语义 gate，关闭时拒绝执行）
        if (!autoMemoryEnabled.getAsBoolean()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] doDream 跳过：isAutoMemoryEnabled=false（dream.ts:26 isEnabled）");
            }
            return new DreamResult(false, List.of());
        }
        // ② 手动乐观盖章（dream.ts:32 await recordConsolidation() · 与自动 dream 区别：
        //    不 tryAcquire/不 rollback，best-effort 静默，失败不炸断 /dream 命令）
        consolidationLock().recordConsolidation();

        // ③ 组装手动 prompt（dream.ts:27-38）
        String memoryRoot = memoryDir().toString();
        String transcriptDir = workspaceDir != null ? workspaceDir.toString() : memoryRoot;
        String basePrompt = ConsolidationPrompt.buildConsolidationPrompt(memoryRoot, transcriptDir, "");
        String prompt = DREAM_PROMPT_PREFIX + basePrompt;
        if (args != null && !args.isBlank()) {
            prompt += "\n\n## Additional context from user\n\n" + args;
        }
        if (log.isInfoEnabled()) {
            log.info("[AutoDream] 手动 /dream 发起整合: memoryRoot={} transcriptDir={} args={}",
                memoryRoot, transcriptDir, args);
        }

        // ④⑤ fork 执行（同自动 dream：受限 canUseTool + skipTranscript + querySource=auto_dream；
        //    手动无 DreamTask 注册 → watcher taskId=null，仅收集 touchedPaths）
        AbortController abortController = new AbortController();
        List<String> touchedPaths = new ArrayList<>();
        try {
            ForkedAgentParams params = buildForkParams(prompt, null, abortController,
                touchedPaths, forkRawMaterial, memoryDir());
            ForkedAgentResult result = RunForkedAgent.run(params, forkedQuery);
            ForkedAgentResult.ForkUsage usage = result.totalUsage() != null
                ? result.totalUsage() : ForkedAgentResult.ForkUsage.empty();
            log.info("[AutoDream] 手动 /dream 整合完成: touchedPaths={} cache_read={} cache_created={} output={}",
                touchedPaths.size(), usage.cacheReadInputTokens(), usage.cacheCreationInputTokens(),
                usage.outputTokens());
            return new DreamResult(true, touchedPaths);
        } catch (Exception e) {
            log.error("[AutoDream] 手动 /dream 整合失败: {}", e.getMessage());
            return new DreamResult(true, List.of());
        }
    }

    /**
     * 手动 /dream 整合结果 · {@link #doDream} 返回载体（doDream 保留未接线，当前仅测试消费；
     * 未来服务端执行模式接线时作为 REST 回包载体）。
     *
     * @param started      true = 已发起整合 fork（gate 通过）；false = isAutoMemoryEnabled 关闭拒绝
     * @param writtenPaths fork 内 Edit/Write 触摸的 memory 文件路径（watcher 收集；空 = 未写入）
     */
    public record DreamResult(boolean started, List<String> writtenPaths) {
        public DreamResult {
            if (writtenPaths == null) {
                writtenPaths = List.of();
            }
        }
    }

    /**
     * 构建 dream fork 参数 · 自动（doConsolidate）与手动（doDream）共用。
     *
     * <p>CC original: {@code runForkedAgent}（autoDream.ts:224-233，forkedAgent.ts:83-113）——
     * cacheSafeParams 合并（T9 原料 + RES-C5 gate）+ 受限 canUseTool（createAutoMemCanUseTool，
     * 同 extractMemories 共享权限面）+ {@code skipTranscript=true} + {@code querySource='auto_dream'}
     * + {@code forkLabel='auto_dream'} + abortController + onMessage watcher。
     *
     * @param prompt          dream prompt 文本（自动 4 阶段 / 手动 DREAM_PROMPT_PREFIX 前缀）
     * @param dreamTaskId     dream 任务 id（自动注册非 null → watcher addDreamTurn；手动 null）
     * @param abortController abortController 透传（CC overrides.abortController）
     * @param touchedPaths    watcher 收集的 touched paths holder（自动/手动共享）
     * @param forkRawMaterial fork 原料（主线程 systemPrompt 等 · forkedAgent.ts:131-141；null = 兜底）
     * @param memoryDir       本会话 auto-memory 目录（[A1 重做] 显式传入 —— doConsolidate 从
     *                        consolidateIfNeeded 5 参透传；doDream 内部现算 storage.memoryDir()）
     * @return ForkedAgentParams（promptMessages + cacheSafeParams + canUseTool + 接线）
     */
    private ForkedAgentParams buildForkParams(String prompt, String dreamTaskId,
                                              AbortController abortController,
                                              List<String> touchedPaths,
                                              ForkRawMaterial forkRawMaterial,
                                              Path memoryDir) {
        String memoryRoot = memoryDir.toString();
        List<ChatMessageDto> promptMessages = List.of(userMessage(prompt));

        // [IMP-MV2-09 T9] fork 原料注入：supplied（生产 supplier）三段恒空（toolUseContext
        //   工具集唯一载荷）→ 合并主线程原料（ForkRawMaterial · CC createCacheSafeParams(context)
        //   forkedAgent.ts:131-141，autoDream.ts:226 消费）；supplied 非空保留原值
        //   （"supplied 优先" · 同 SessionMemoryService RES-C5 语义）。forkContextMessages =
        //   主线程消息快照（raw.forkContextMessages · CC context.messages —— 修复旧 List.of()：
        //   dream fork 无消息前缀 → cache key 与主线程不一致）。null 原料 → 既有兜底不变。
        CacheSafeParams supplied = cacheSafeParamsSupplier != null ? cacheSafeParamsSupplier.get() : null;
        CacheSafeParams cs = supplied != null
            ? new CacheSafeParams(
                ForkRawMaterial.mergeSystemPrompt(supplied.systemPrompt(),
                    forkRawMaterial != null ? forkRawMaterial.systemPrompt() : null),
                ForkRawMaterial.mergeContext(supplied.userContext(),
                    forkRawMaterial != null ? forkRawMaterial.userContext() : null),
                ForkRawMaterial.mergeContext(supplied.systemContext(),
                    forkRawMaterial != null ? forkRawMaterial.systemContext() : null),
                supplied.toolUseContext(),
                forkRawMaterial != null ? forkRawMaterial.forkContextMessages() : List.of(),
                // [RES-C5 rework] gate 合并：生产 supplier 5 参便捷构造 gate=false 占位，
                //   与会话级 gate（GlobalCacheScope 单实现 · betas.ts:227-233）OR 合并（REQ-C5-4）
                supplied.useGlobalCacheScope() || useGlobalCacheScopeSupplier.get())
            : forkRawMaterial != null
                ? RunForkedAgent.createMinimalCacheSafeParams(
                    forkRawMaterial.forkContextMessages(),
                    forkRawMaterial.systemPrompt(),     // T9：主线程原料（forkedAgent.ts:131）
                    forkRawMaterial.userContext(),      // T9：主线程 userContext
                    forkRawMaterial.systemContext(),    // T9：主线程 systemContext
                    useGlobalCacheScopeSupplier.get())
                : RunForkedAgent.createMinimalCacheSafeParams(
                    // [RES-C5] auto-dream 无 post-sampling 上下文（stop-hook 触发）→
                    // systemPrompt 降级（验收 2）；gate 仍透传（GlobalCacheScope 单实现 · betas.ts:227-233）
                    List.of(), List.of(), Map.of(), Map.of(),
                    useGlobalCacheScopeSupplier.get());

        String memDirWithSep = memoryRoot.endsWith("/") || memoryRoot.endsWith("\\")
            ? memoryRoot : memoryRoot + java.io.File.separator;
        // deny 分支发 tengu_auto_mem_tool_denied（CC denyAutoMemTool extractMemories.ts:154-164，
        // autoDream 共享 createAutoMemCanUseTool）
        HookPermissionResolver.CanUseTool canUseTool =
            ExtractMemoriesAgent.createAutoMemCanUseTool(memDirWithSep,
                toolName -> emitTelemetry("tengu_auto_mem_tool_denied",
                    Map.of("tool_name", toolName == null ? "<unknown>" : toolName)));

        return new ForkedAgentParams(
            promptMessages, cs, canUseTool,
            QuerySource.AUTO_DREAM, "auto_dream",
            /*maxOutputTokens*/ null,
            /*maxTurns*/ null,
            /*skipTranscript*/ true,
            /*skipCacheWrite*/ false,
            abortController,
            makeDreamProgressWatcher(dreamTaskId, touchedPaths));
    }

    /**
     * 遥测双发射 · CC original: {@code logEvent}（autoDream.ts:195/252/267）。
     *
     * <p><b>IMP-M-P2-1 补 logOTelEvent 发射</b>：recordEvent（1P/Statsig 适配层计数）+ logOTelEvent
     * （OTel 真实出事件，EventsLogger body=claude_code.* · Telemetry.java:197-229）双发射 ·
     * HookRegistry:278-279 惯例。telemetry 未注入（null）时静默跳过（对齐 CC logEvent 可空上下文）。
     *
     * @param eventName  tengu_auto_dream_fired/completed/failed
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

    /**
     * 构造 extra 附加上下文 · CC original: autoDream.ts:216-221（tool constraints +
     * sessions since last consolidation 列表）。
     */
    private String buildExtra(List<String> sessionIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n**Tool constraints for this run:** Bash is restricted to read-only ")
            .append("commands (`ls`, `find`, `grep`, `cat`, `stat`, `wc`, `head`, `tail`, and similar). ")
            .append("Anything that writes, redirects to a file, or modifies state will be denied. ")
            .append("Plan your exploration with this in mind — no need to probe.\n");
        sb.append("\nSessions since last consolidation (").append(sessionIds.size()).append("):\n");
        // [G-77] CC autoDream.ts:216-221 `sessionIds.map(id => `- ${id}`).join('\n')` —— 条目间
        //   `\n` 连接、结尾**无尾换行**（consolidationPrompt.ts:64 收尾反引号紧跟插值亦无尾换行）；
        //   旧实现每条 `- id\n` 使 extra 尾部多 1 个 `\n`（△-1，F-2：extra 非空时 prompt 共多 2 个）。
        for (int i = 0; i < sessionIds.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("- ").append(sessionIds.get(i));
        }
        return sb.toString();
    }

    /**
     * onMessage watcher · CC original: {@code makeDreamProgressWatcher}
     * （autoDream.ts:281-313）。
     * <p><b>OPD-TP-09 addDreamTurn 接线</b>（autoDream.ts:306-311）：每条 assistant 消息组装
     * {@link DreamTaskState.DreamTurn DreamTurn(text.trim(), toolUseCount)} + 本条消息的
     * touchedPaths → {@link DreamTaskRegistry#addDreamTurn}（filesTouched 去重 + no-op 跳过 +
     * phase 翻转 + turns 截断在 registry 内）。taskId=null（未装配 registry）→ 跳过。
     *
     * <p><b>AD-02 watcher text 取证（E2，2026-08-14）</b>：CC makeDreamProgressWatcher
     * （autoDream.ts:287-305）拼接 content blocks 中全部 text 块（`text += block.text` 无分隔）
     * → 整体 `text.trim()`；tool_use 独立计数 + Edit/Write 的 input.file_path 收集。Java
     * ChatMessageDto.content = provider text 块无分隔拼接：非流式 buildAssistantMessageFromMessage
     * （AnthropicSdkProvider.java:721-731 `text.append(s)`）+ 流式 mapStreamEvent（:792-799
     * `state.content.append(s)`）均无分隔；toolCalls 独立字段 → `content().trim()` ≡ CC
     * `text.trim()`，toolCalls().size() ≡ CC toolUseCount。无代码改动（OPD-R2-AD-02 闭合）。
     *
     * <p><b>G-79 时序</b>：RunForkedAgent 已改流式 —— onMessage 经 ForkQueryParams 透传给
     * query loop（ProductionForkedQuery 每产出一条 assistant/tool_result 消息即回调，
     * forkedAgent.ts:578），回调发生在 completeDreamTask 之前（run 返回后才 complete，
     * 对齐 CC 流式顺序）；post-hoc replay 已删除（IMP-18 先行者风险闭合）。
     */
    Consumer<ChatMessageDto> makeDreamProgressWatcher(String taskId, List<String> touchedPathsHolder) {
        return msg -> {
            if (msg == null || msg.role() != Role.assistant) {
                return;
            }
            String text = msg.content() == null ? "" : msg.content().trim();
            int toolUseCount = 0;
            List<String> messageTouched = new ArrayList<>();
            if (msg.toolCalls() != null) {
                toolUseCount = msg.toolCalls().size();
                for (ToolCallDto tc : msg.toolCalls()) {
                    if (tc.name() == null) {
                        continue;
                    }
                    if (ToolNameConstants.FILE_EDIT_TOOL_NAME.equals(tc.name())
                        || ToolNameConstants.FILE_WRITE_TOOL_NAME.equals(tc.name())) {
                        String fp = extractFilePath(tc.arguments());
                        if (fp != null && !fp.isBlank()) {
                            messageTouched.add(fp);
                            touchedPathsHolder.add(fp);
                        }
                    }
                }
            }
            // OPD-TP-09: addDreamTurn（对齐 autoDream.ts:306-311 · text.trim() + toolUseCount + touchedPaths）
            if (taskId != null && dreamTaskRegistry != null) {
                dreamTaskRegistry.addDreamTurn(taskId,
                    new DreamTaskState.DreamTurn(text, toolUseCount), messageTouched);
            }
            if (log.isDebugEnabled()) {
                log.debug("[AutoDream] watcher assistant 消息: textLen={} toolUseCount={} touchedPaths={}",
                    text.length(), toolUseCount, touchedPathsHolder);
            }
        };
    }

    /** 从工具 arguments JSON 提取 file_path · CC makeDreamProgressWatcher block.input.file_path。 */
    private static String extractFilePath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(arguments);
            JsonNode fp = node.get("file_path");
            if (fp != null && fp.isTextual()) {
                return fp.asText();
            }
        } catch (IOException e) {
            // 单条 arguments 解析失败跳过
        }
        return null;
    }

    /** 构造 fork 的 user 消息（CC createUserMessage）。 */
    private static ChatMessageDto userMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** CC isEnvTruthy · 接受 "1"/"true"/"yes" 等 truthy 字符串。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }
}

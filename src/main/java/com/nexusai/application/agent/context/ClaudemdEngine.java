package com.nexusai.application.agent.context;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * claudemd 引擎 · 全量对齐 CC {@code Open-ClaudeCode/src/utils/claudemd.ts}（1479 行）。
 *
 * <p><b>唯一目标</b>（OPD-M-45 结构性决策）：getMemoryFiles 加载序 Managed→User→Project→
 * Local→AutoMem→TeamMem（INV-13）+ memoize + MAX_MEMORY_CHARACTER_COUNT=40000（OPD-M-46）
 * + processMemoryFile/@include（MAX_INCLUDE_DEPTH=5）+ processMdRules/processConditionedMdRules
 * + 缓存控制 + isMemoryFilePath/getAllMemoryFilePaths + getClaudeMds（MEMORY_INSTRUCTION_PROMPT
 * 契约）。删除 ClaudemdParser 孤儿（DEL-M-32 / OPD-M-49）。
 *
 * <p><b>注入载体</b>（concern #1）：CC 的 claudeMd 上下文经 {@code prependUserContext}
 * （api.ts:449-469）合成 <b>system-reminder user message</b> 前置到消息队首，非 system prompt
 * section —— 生产唯一实现为 {@link com.nexusai.application.agent.loop.AgentLoopContext#prependUserContext}
 * （LlmAgentLoop 消息组装区调用）；引擎侧不再保留重复实现（FIX-CL 删双轨）。
 *
 * <p><b>门控</b>（concern #3）：Java 无 isSettingSourceEnabled 概念（MarkdownConfigLoader:156
 * 记恒启用），userSettings/projectSettings/localSettings 用 {@link Supplier}{@code <Boolean>}
 * 默认 true 对齐结构。claudeMdExcludes 无 settings 源 → 默认空列表（永不排除）。
 *
 * <p><b>Java 差异记录</b>：
 * <ul>
 *   <li>telemetry（tengu_claude_md_permission_error / tengu_claudemd__initial_load）经
 *       {@link #setTelemetry} 注入（IMP-M-C-1 域）</li>
 *   <li>缓存失效接线（FIX-CL）：{@link #resetGetMemoryFilesCache} 由 PostCompactCleanup
 *       main-thread 分支调用（对齐 CC postCompactCleanup.ts:51-60）；{@link #clearMemoryFileCaches}
 *       由 TeamMemorySyncService 写盘后调用（FIX-TM 接线）</li>
 *   <li>InstructionsLoaded hook（FIX-CL）：getMemoryFiles 尾部按指令类型触发
 *       {@link HookEvent#instructionsLoaded}（对齐 CC claudemd.ts:1042-1071），hookRegistry 未注入
 *       → 不发射（对齐 CC hasInstructionsLoadedHook 空判定）；ODF-B4 改独立 executor 异步
 *       fire-and-forget（对齐 CC {@code void executeInstructionsLoadedHooks} claudemd.ts:1060），
 *       主路径不等待 audit 事件</li>
 * </ul>
 */
public class ClaudemdEngine {

    private static final Logger log = LoggerFactory.getLogger(ClaudemdEngine.class);

    /**
     * 记忆文件推荐最大字符数 · CC original: {@code MAX_MEMORY_CHARACTER_COUNT}（claudemd.ts:92）
     * {@code = 40000}（OPD-M-46）。
     */
    public static final int MAX_MEMORY_CHARACTER_COUNT = 40000;

    /** @include 最大递归深度 · CC original: {@code MAX_INCLUDE_DEPTH}（claudemd.ts:537）{@code = 5}。 */
    static final int MAX_INCLUDE_DEPTH = 5;

    /** MEMORY.md 入口行数上限 · CC memdir.ts:35 {@code MAX_ENTRYPOINT_LINES = 200}。 */
    private static final int MAX_ENTRYPOINT_LINES = 200;

    /** MEMORY.md 入口字节上限 · CC memdir.ts:38 {@code MAX_ENTRYPOINT_BYTES = 25_000}。 */
    private static final int MAX_ENTRYPOINT_BYTES = 25000;

    /** MEMORY.md 入口文件名 · CC memdir.ts:34 {@code ENTRYPOINT_NAME = 'MEMORY.md'}。 */
    private static final String ENTRYPOINT_NAME = "MEMORY.md";

    /**
     * MEMORY_INSTRUCTION_PROMPT · CC original: {@code MEMORY_INSTRUCTION_PROMPT}
     * （claudemd.ts:89-90）逐字对齐。
     */
    public static final String MEMORY_INSTRUCTION_PROMPT =
        "Codebase and user instructions are shown below. Be sure to adhere to these instructions. "
            + "IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow "
            + "them exactly as written.";

    /** @include 允许的文本扩展名 · CC claudemd.ts:96-227 {@code TEXT_FILE_EXTENSIONS}。 */
    static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
        ".md", ".txt", ".text",
        ".json", ".yaml", ".yml", ".toml", ".xml", ".csv",
        ".html", ".htm", ".css", ".scss", ".sass", ".less",
        ".js", ".ts", ".tsx", ".jsx", ".mjs", ".cjs", ".mts", ".cts",
        ".py", ".pyi", ".pyw",
        ".rb", ".erb", ".rake",
        ".go", ".rs",
        ".java", ".kt", ".kts", ".scala",
        ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".hxx",
        ".cs", ".swift",
        ".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd",
        ".env", ".ini", ".cfg", ".conf", ".config", ".properties",
        ".sql", ".graphql", ".gql", ".proto",
        ".vue", ".svelte", ".astro",
        ".ejs", ".hbs", ".pug", ".jade",
        ".php", ".pl", ".pm", ".lua", ".r", ".R", ".dart",
        ".ex", ".exs", ".erl", ".hrl", ".clj", ".cljs", ".cljc", ".edn",
        ".hs", ".lhs", ".elm", ".ml", ".mli", ".f", ".f90", ".f95", ".for",
        ".cmake", ".make", ".makefile", ".gradle", ".sbt",
        ".rst", ".adoc", ".asciidoc", ".org", ".tex", ".latex",
        ".lock", ".log", ".diff", ".patch");

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // ── 依赖（Spring 注入，测试可覆写）──

    private final AutoMemPaths autoMemPaths;
    private final MemoryFileDetection memoryFileDetection;
    private final Supplier<String> originalCwdSupplier;
    private final Supplier<Boolean> userSettingsEnabled;
    private final Supplier<Boolean> projectSettingsEnabled;
    private final Supplier<Boolean> localSettingsEnabled;
    private volatile Supplier<Boolean> teamMemoryEnabled;
    private final Supplier<List<String>> claudeMdExcludesSupplier;

    /** getMemoryFiles memoize 缓存（CC lodash memoize keyed on forceIncludeExternal）。 */
    private final Map<Boolean, List<MemoryFileInfo>> memoryFilesCache = new ConcurrentHashMap<>();

    /** 遥测注入 · null → 不发射（对齐 CC logEvent 可空上下文）。 */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    /** tengu_moth_copse 门控 · CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_moth_copse', false)（claudemd.ts:1145-1148）。 */
    private volatile Supplier<Boolean> mothCopseGate;

    /**
     * tengu_paper_halyard 门控 · CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_paper_halyard', false)
     * （claudemd.ts:1158-1161）。true=跳过 Project/Local（getClaudeMds + getNestedMemoryAttachmentsForFile
     * 两处共用，claudemd.ts:1165-1166 / attachments.ts:1823-1835）。未注入 → null → 恒 false
     * （feature 关，对齐 CC GB flag 缺省）。
     */
    private volatile Supplier<Boolean> paperHalyardGate;

    /**
     * 外部 include 审批态 · CC original: {@code hasClaudeMdExternalIncludesApproved}
     * （config.ts:115，缺省 false :146）。
     *
     * <p>接入审批态（OPD-CM5-F-09 / 探查 △-15 / T-9 / R11）：CC getMemoryFiles includeExternal =
     * {@code forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false}
     * （claudemd.ts:798-801）。Java 无 getCurrentProjectConfig 概念 → 注入式 Supplier 装配缝
     * （对齐 mothCopseGate 先例），默认 false（CC config 缺省）。审批对话框接受 → true。
     * 未注入 → null → 恒 false。
     */
    private volatile Supplier<Boolean> hasClaudeMdExternalIncludesApproved;

    /**
     * 外部 include 警告已示态 · CC original: {@code hasClaudeMdExternalIncludesWarningShown}
     * （config.ts:116，缺省 false :147）。
     *
     * <p>接入审批态（OPD-CM5-F-09）：shouldShowClaudeMdExternalIncludesWarning 先查
     * approved/warningShown，任一 true → false（claudemd.ts:1423-1426）。审批对话框无论
     * 接受/拒绝均置 true。未注入 → null → 恒 false。
     */
    private volatile Supplier<Boolean> hasClaudeMdExternalIncludesWarningShown;

    /** InstructionsLoaded hook 发射器 · null → 不发射（对齐 CC hasInstructionsLoadedHook 空判定）。 */
    private volatile HookRegistry hookRegistry;

    /**
     * InstructionsLoaded hook 独立执行器（fire-and-forget）· 对齐 CC {@code void
     * executeInstructionsLoadedHooks(...)}（claudemd.ts:1060 不 await）+ hooks.ts:4335
     * {@code executeHooksOutsideREPL}（REPL 外异步跑完）。懒建 fixedThreadPool(2, daemon)
     * （参照 MemoryPrefetcher:118-125 先例；禁用 unbounded cached，session §8）。
     */
    private volatile java.util.concurrent.ExecutorService hookExecutor;

    /** 当前会话 ID · CC original: executeInstructionsLoadedHooks 的 sessionId 参数（claudemd.ts:1059-1068）。 */
    private volatile Supplier<String> sessionIdSupplier = () -> com.nexusai.common.RequestContext.sessionId();

    /** 下一次 eager 加载要上报的 reason · CC claudemd.ts:1093 {@code nextEagerLoadReason}（one-shot，读后复位）。 */
    private volatile String nextEagerLoadReason = "session_start";

    /** InstructionsLoaded hook 是否应在下次缓存 miss 时发射 · CC claudemd.ts:1100 {@code shouldFireHook}。 */
    private volatile boolean shouldFireHook = true;

    /** tengu_claudemd__initial_load 一次性标记 · CC original: hasLoggedInitialLoad（claudemd.ts:87）。 */
    private volatile boolean hasLoggedInitialLoad = false;

    /**
     * 生产构造器（Spring）· 门控对齐 OPD-M-47：isAutoMemoryEnabled 走
     * {@link BundledSkillEnabledGates}；team memory 默认 false（CC feature('TEAMMEM')），
     * 生产经 {@link #setTeamMemoryEnabled} 接 FeatureFlags.teamMem()（探查 F-02/△-5）。
     *
     * <p><b>WF-1B / G7 / DEL-04</b>：{@code originalCwdSupplier} 走
     * {@link CwdResolution#getOriginalCwdLayer()}（对齐 CC {@code getOriginalCwd()}
     * claudemd.ts:851 —— STATE.originalCwd 启动 cwd，随 worktree/resume 重锚），
     * 替代旧 {@code () -> System.getProperty("user.dir")} 直读。CLAUDE.md 扫描根
     * （Project/Local 向上遍历起点）= 绑定项目层覆盖 user.dir 时取对扫描根。
     */
    public ClaudemdEngine(AutoMemPaths autoMemPaths, MemoryFileDetection memoryFileDetection) {
        this(autoMemPaths, memoryFileDetection,
            CwdResolution::getOriginalCwdLayer,
            () -> true, () -> true, () -> true,
            () -> false,
            () -> List.of());
    }

    /**
     * 注入式构造器（测试隔离）。
     *
     * @param originalCwdSupplier CC getOriginalCwd()（bootstrap/state.ts）等价
     * @param userSettingsEnabled     CC isSettingSourceEnabled('userSettings')
     * @param projectSettingsEnabled  CC isSettingSourceEnabled('projectSettings')
     * @param localSettingsEnabled    CC isSettingSourceEnabled('localSettings')
     * @param teamMemoryEnabled       CC feature('TEAMMEM')
     * @param claudeMdExcludesSupplier CC getInitialSettings().claudeMdExcludes（无 settings 源 → 空）
     */
    public ClaudemdEngine(AutoMemPaths autoMemPaths,
                          MemoryFileDetection memoryFileDetection,
                          Supplier<String> originalCwdSupplier,
                          Supplier<Boolean> userSettingsEnabled,
                          Supplier<Boolean> projectSettingsEnabled,
                          Supplier<Boolean> localSettingsEnabled,
                          Supplier<Boolean> teamMemoryEnabled,
                          Supplier<List<String>> claudeMdExcludesSupplier) {
        this.autoMemPaths = autoMemPaths;
        this.memoryFileDetection = memoryFileDetection;
        this.originalCwdSupplier = originalCwdSupplier;
        this.userSettingsEnabled = userSettingsEnabled;
        this.projectSettingsEnabled = projectSettingsEnabled;
        this.localSettingsEnabled = localSettingsEnabled;
        this.teamMemoryEnabled = teamMemoryEnabled;
        this.claudeMdExcludesSupplier = claudeMdExcludesSupplier;
    }

    /** 注入遥测（tengu_claude_md_permission_error / tengu_claude_rules_md_permission_error /
     *   tengu_claudemd__initial_load · claudemd.ts:411/781/1027）· Spring @Bean 注册自动装配
     *   （required=false 容错，测试可手动注入）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** 遥测双发射 · CC original: {@code logEvent}（claudemd.ts:411/781/1027）。telemetry 未注入 → 静默跳过。 */
    private void emitTelemetry(String eventName, java.util.Map<String, ?> attributes) {
        if (telemetry == null) {
            return;
        }
        java.util.Map<String, Object> attrs = attributes == null
            ? java.util.Map.of() : new java.util.HashMap<>(attributes);
        telemetry.recordEvent(eventName, attrs);
        telemetry.logOTelEvent(eventName, attrs);
    }

    /** 注入 tengu_moth_copse 门控 · CC getFeatureValue_CACHED_MAY_BE_STALE（claudemd.ts:1145-1148）。
     *  未注入 → null → 恒 false（feature 关，对齐 MemoryPrefetcher 先例）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMothCopseGate(java.util.function.Supplier<Boolean> mothCopseGate) {
        this.mothCopseGate = mothCopseGate;
    }

    /**
     * 注入 tengu_paper_halyard 门控 · CC {@code getFeatureValue_CACHED_MAY_BE_STALE
     * ('tengu_paper_halyard', false)}（claudemd.ts:1158-1161 / attachments.ts:1823-1826）。
     *
     * <p><b>WHY 存在</b>（探查 △-6 / R7 / T-4 / OPD-CM5-F-05）：feature 开 → {@code getClaudeMds} 与
     * {@link #getNestedMemoryAttachmentsForFile} 跳过 Project/Local（claudemd.ts:1165-1166 /
     * attachments.ts:1833-1835/1850-1852）。本 setter 提供装配缝：由外部装配点（ToolRegistrationConfig
     * Engine bean 组装）注入 {@code () -> featureFlags != null && featureFlags.tenguPaperHalyard()}
     * （对齐 {@link #setMothCopseGate} 先例；FeatureFlags 需补 {@code tenguPaperHalyard} 字段 +
     * {@code nexusai.feature.tengu-paper-halyard} 属性 + 访问器，见部署/装配清单）。
     * <b>当前生产尚未注入 → null → 恒 false</b>（feature 关 = Project/Local 仍注入，与 CC GB flag
     * 缺省行为一致）；测试可直接 {@code setPaperHalyardGate(() -> true)} 验证开启语义。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPaperHalyardGate(java.util.function.Supplier<Boolean> paperHalyardGate) {
        this.paperHalyardGate = paperHalyardGate;
    }

    /**
     * 注入外部 include 审批态 · CC original: {@code hasClaudeMdExternalIncludesApproved}
     * （config.ts:115，claudemd.ts:798-801 消费）。装配缝（OPD-CM5-F-09）：前端审批对话框
     * 接受后置 true。未注入 → null → 恒 false（CC config 缺省）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHasClaudeMdExternalIncludesApproved(java.util.function.Supplier<Boolean> hasClaudeMdExternalIncludesApproved) {
        this.hasClaudeMdExternalIncludesApproved = hasClaudeMdExternalIncludesApproved;
    }

    /**
     * 注入外部 include 警告已示态 · CC original: {@code hasClaudeMdExternalIncludesWarningShown}
     * （config.ts:116，claudemd.ts:1423-1426 消费）。装配缝（OPD-CM5-F-09）：前端审批对话框
     * 显示后（无论接受/拒绝）置 true。未注入 → null → 恒 false。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHasClaudeMdExternalIncludesWarningShown(java.util.function.Supplier<Boolean> hasClaudeMdExternalIncludesWarningShown) {
        this.hasClaudeMdExternalIncludesWarningShown = hasClaudeMdExternalIncludesWarningShown;
    }

    /**
     * 注入 teamMemoryEnabled 门控 · CC {@code feature('TEAMMEM')}（claudemd.ts:995/1035/1173/1180）。
     *
     * <p><b>WHY 存在</b>（探查 △-5 / R1 / T-1）：生产 {@code @Bean}（ToolRegistrationConfig）此前走
     * 2 参构造 → {@code teamMemoryEnabled = () -> false} 恒 false，与 enum 门控
     * {@code ClaudemdMemoryType.setTeamMemEnabled(featureFlags.teamMem())}（OPD-CM3-35/IMP-CM-11
     * 已接线）及 {@link MemoryFileDetection} 双门控（IMP-CM-09）内部不一致——feature 开启时值域恢复
     * 但 getMemoryFiles 入口恒不注入 TeamMem 入口。本 setter 提供装配缝：ToolRegistrationConfig
     * 注入 {@code () -> featureFlags != null && featureFlags.teamMem()}（与 mothCopseGate 装配一致）。
     * 未注入 → 保留构造器值（2 参生产构造 = false，feature 关 = TeamMem 入口不注入）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTeamMemoryEnabled(java.util.function.Supplier<Boolean> teamMemoryEnabled) {
        this.teamMemoryEnabled = teamMemoryEnabled;
    }

    /** 注入 InstructionsLoaded hook 发射器 · @Bean 注册自动装配（required=false 容错，测试可手动注入）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /** 注入当前会话 ID 供应器 · 默认 {@link com.nexusai.common.RequestContext#sessionId()}（MDC）。 */
    public void setSessionIdSupplier(java.util.function.Supplier<String> sessionIdSupplier) {
        this.sessionIdSupplier = sessionIdSupplier;
    }

    /** 注入 InstructionsLoaded hook 独立执行器 · 测试可注入受控 executor（覆写懒建默认）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHookExecutor(java.util.concurrent.ExecutorService hookExecutor) {
        this.hookExecutor = hookExecutor;
    }

    /** 懒建/取 InstructionsLoaded hook 执行器 · fixedThreadPool(2, daemon)（参照 MemoryPrefetcher:118-125）。 */
    private java.util.concurrent.ExecutorService hookExecutor() {
        java.util.concurrent.ExecutorService ex = hookExecutor;
        if (ex == null) {
            synchronized (this) {
                if (hookExecutor == null) {
                    hookExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                        Thread t = new Thread(r, "claudemd-instructions-loaded");
                        t.setDaemon(true);
                        return t;
                    });
                }
                ex = hookExecutor;
            }
        }
        return ex;
    }

    // ════════════════════════════════════════════════════════════════
    // getMemoryFiles（memoize）+ clearMemoryFileCaches · claudemd.ts:790-1130
    // ════════════════════════════════════════════════════════════════

    /**
     * 加载全部记忆文件 · CC original: {@code getMemoryFiles}（claudemd.ts:790-1075，memoize）。
     *
     * <p>加载序（INV-13 / claudemd.ts:1-26 头注释）：
     * <ol>
     *   <li><b>Managed</b>（恒加载）：{@code getManagedFilePath()/CLAUDE.md} + Managed rules</li>
     *   <li><b>User</b>（userSettings 门控）：{@code configHome/CLAUDE.md} + User rules
     *       （User includeExternal 恒 true，claudemd.ts:833）</li>
     *   <li><b>Project/Local</b>（向上遍历 cwd→root，root→cwd 反向处理，越近 cwd 优先级越高）</li>
     *   <li><b>AutoMem</b> 入口（isAutoMemoryEnabled 门控）</li>
     *   <li><b>TeamMem</b> 入口（feature('TEAMMEM') 门控）</li>
     * </ol>
     * 文件加载序与优先级相反：后加载的优先级更高（claudemd.ts:9-10）。
     *
     * <p>memoize + <b>单飞</b>（CLD-01/OPD-R2-CLD-01）：CC lodash memoize 缓存 Promise
     * （claudemd.ts:790）—— 并发首次调用共享一次计算、一次 hook/遥测发射。Java 经
     * {@link ConcurrentHashMap#computeIfAbsent} 实现：并发线程同时 miss 时仅一个线程执行
     * {@link #computeMemoryFiles}，其余线程阻塞等待同一结果（与「缓存 Promise 后 await」可观测
     * 语义等价：一次 I/O + 一次发射）；旧 get→compute 非原子实现会双算双发。缓存由
     * {@link #clearMemoryFileCaches()} 失效（CC claudemd.ts:1119-1122）。
     *
     * @param forceIncludeExternal 强制包含外部 @include（CC 仅供外部 include 审批检查使用）；
     *                             另有审批态 {@code hasClaudeMdExternalIncludesApproved}（OPD-CM5-F-09），
     *                             true 时即使 forceIncludeExternal=false 也包含外部 @include
     *                             （CC claudemd.ts:798-801）
     * @return 有序 MemoryFileInfo 列表（父在前，@include 子在后）
     */
    public List<MemoryFileInfo> getMemoryFiles(boolean forceIncludeExternal) {
        return memoryFilesCache.computeIfAbsent(forceIncludeExternal, this::computeMemoryFiles);
    }

    /**
     * getMemoryFiles 实际计算体 · 仅在缓存 miss 时由 {@link #getMemoryFiles} 单飞执行一次
     * （CLD-01）。one-shot 标志（{@link #consumeNextEagerLoadReason}/{@link #hasLoggedInitialLoad}）
     * 在单飞语义下每次缓存 miss 仅消费一次。
     */
    private List<MemoryFileInfo> computeMemoryFiles(boolean forceIncludeExternal) {
        long startTime = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("[ClaudemdEngine] getMemoryFiles 开始: forceIncludeExternal={}", forceIncludeExternal);
        }

        List<MemoryFileInfo> result = new ArrayList<>();
        Set<String> processedPaths = new LinkedHashSet<>();
        // CC claudemd.ts:798-801：includeExternal = forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved || false
        boolean includeExternal = forceIncludeExternal || Boolean.TRUE.equals(
            hasClaudeMdExternalIncludesApproved != null ? hasClaudeMdExternalIncludesApproved.get() : null);

        // 1. Managed file（恒加载 - policy settings）
        processMemoryFileInto(result, getMemoryPath(ClaudemdMemoryType.MANAGED),
            ClaudemdMemoryType.MANAGED, processedPaths, includeExternal, 0, null);

        // 2. Managed rules
        processMdRulesInto(result, getManagedClaudeRulesDir(), ClaudemdMemoryType.MANAGED,
            processedPaths, includeExternal, false, new LinkedHashSet<>());

        // 3. User file（userSettings 门控）· 决策 D1/D3：getMemoryPath(USER) 已 nexusai 自有根
        //    优先 + claude 回落；User rules 双目录加载（nexusai 自有根 + claude 只读兼容回落，
        //    rules 为多文件按路径加载两目录无 name 冲突）
        if (bool(userSettingsEnabled)) {
            processMemoryFileInto(result, getMemoryPath(ClaudemdMemoryType.USER),
                ClaudemdMemoryType.USER, processedPaths, true, 0, null);
            processMdRulesInto(result, getUserClaudeRulesDir(), ClaudemdMemoryType.USER,
                processedPaths, true, false, new LinkedHashSet<>());
            processMdRulesInto(result, getClaudeUserClaudeRulesDir(), ClaudemdMemoryType.USER,
                processedPaths, true, false, new LinkedHashSet<>());
        }

        // 4. Project + Local：从 originalCwd 向上遍历到 root（到 root 前停止，root 不入 dirs ·
        //    CC claudemd.ts:854-857 while(currentDir !== parse(currentDir).root)）
        String originalCwd = originalCwdSupplier.get();
        List<String> dirs = new ArrayList<>();
        Path currentPath = Paths.get(originalCwd);
        Path root = currentPath.getRoot();
        while (currentPath != null && !currentPath.equals(root)) {
            dirs.add(currentPath.toString());
            Path parent = currentPath.getParent();
            if (parent == null || parent.equals(currentPath)) {
                break;
            }
            currentPath = parent;
        }

        // 嵌套 worktree 检测（claudemd.ts:868-876）—— worktree 在主子仓库内时跳过主仓库
        // 已入库文件（worktree 自带 checkout，避免 CLAUDE.md 双加载）
        String gitRoot = findGitRoot(originalCwd);
        String canonicalRoot = AutoMemPaths.findCanonicalGitRoot(originalCwd);
        boolean isNestedWorktree = gitRoot != null
            && canonicalRoot != null
            && !normalizeForComparison(gitRoot).equals(normalizeForComparison(canonicalRoot))
            && pathInWorkingPath(gitRoot, canonicalRoot);

        // 从 root 向下到 cwd 处理（reverse）
        java.util.Collections.reverse(dirs);
        for (String dir : dirs) {
            boolean skipProject = isNestedWorktree
                && pathInWorkingPath(dir, canonicalRoot)
                && !pathInWorkingPath(dir, gitRoot);
            if (bool(projectSettingsEnabled) && !skipProject) {
                processMemoryFileInto(result, Paths.get(dir, "CLAUDE.md").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, 0, null);
                // 决策 D1/D6（T4 补充）：项目级 .claude/CLAUDE.md 内容一次性导入 .nexusai/ 后
                //   nexusai 优先 + claude 回落（resolveFirstExisting，未导入/用户手建 .claude 时兼容）
                processMemoryFileInto(result, resolveFirstExisting(
                    Paths.get(dir, NexusaiPaths.getProjectDirName(), "CLAUDE.md"),
                    Paths.get(dir, ".claude", "CLAUDE.md")),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, 0, null);
                // rules 双目录加载（nexusai + claude，按路径无 name 冲突，D1 同 User rules 双目录模式）
                processMdRulesInto(result, Paths.get(dir, NexusaiPaths.getProjectDirName(), "rules").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, false,
                    new LinkedHashSet<>());
                processMdRulesInto(result, Paths.get(dir, ".claude", "rules").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, false,
                    new LinkedHashSet<>());
            }
            if (bool(localSettingsEnabled)) {
                processMemoryFileInto(result, Paths.get(dir, "CLAUDE.local.md").toString(),
                    ClaudemdMemoryType.LOCAL, processedPaths, includeExternal, 0, null);
            }
        }

        // 5. 附加目录（--add-dir 等价 · CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD 门控）
        if (isEnvTruthy(System.getenv("CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"))) {
            for (String dir : getAdditionalDirectoriesForClaudeMd()) {
                processMemoryFileInto(result, Paths.get(dir, "CLAUDE.md").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, 0, null);
                processMemoryFileInto(result, resolveFirstExisting(
                    Paths.get(dir, NexusaiPaths.getProjectDirName(), "CLAUDE.md"),
                    Paths.get(dir, ".claude", "CLAUDE.md")),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, 0, null);
                processMdRulesInto(result, Paths.get(dir, NexusaiPaths.getProjectDirName(), "rules").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, false,
                    new LinkedHashSet<>());
                processMdRulesInto(result, Paths.get(dir, ".claude", "rules").toString(),
                    ClaudemdMemoryType.PROJECT, processedPaths, includeExternal, false,
                    new LinkedHashSet<>());
            }
        }

        // 6. Memdir entrypoint（MEMORY.md）· isAutoMemoryEnabled 门控
        if (BundledSkillEnabledGates.isAutoMemoryEnabled()) {
            MemoryFileInfo entry = safelyReadEntrypoint(autoMemPaths.getAutoMemEntrypoint(),
                ClaudemdMemoryType.AUTO_MEM);
            if (entry != null) {
                String normalized = normalizeForComparison(entry.path());
                if (!processedPaths.contains(normalized)) {
                    processedPaths.add(normalized);
                    result.add(entry);
                }
            }
        }

        // 7. Team memory entrypoint · feature('TEAMMEM') 门控
        if (bool(teamMemoryEnabled) && memoryFileDetection.isTeamMemoryEnabled()) {
            MemoryFileInfo entry = safelyReadEntrypoint(
                Paths.get(memoryFileDetection.getTeamMemPath(), "MEMORY.md").toString(),
                ClaudemdMemoryType.TEAM_MEM);
            if (entry != null) {
                String normalized = normalizeForComparison(entry.path());
                if (!processedPaths.contains(normalized)) {
                    processedPaths.add(normalized);
                    result.add(entry);
                }
            }
        }

        int totalContentLength = result.stream().mapToInt(f -> f.content().length()).sum();
        if (log.isDebugEnabled()) {
            log.debug("[ClaudemdEngine] getMemoryFiles 完成: fileCount={} totalContentLength={} durationMs={}",
                result.size(), totalContentLength, System.currentTimeMillis() - startTime);
        }

        // CC claudemd.ts:1021-1043 tengu_claudemd__initial_load（一次性 hasLoggedInitialLoad 去重；
        //   typeCounts 按 MemoryType 字面量分桶，TeamMem 仅在 TEAMMEM feature 下包含 —— Java 恒省略）
        if (!hasLoggedInitialLoad) {
            hasLoggedInitialLoad = true;
            java.util.Map<String, Long> typeCounts = new java.util.HashMap<>();
            for (MemoryFileInfo f : result) {
                typeCounts.merge(f.type().ccName(), 1L, Long::sum);
            }
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("file_count", result.size());
            attrs.put("total_content_length", totalContentLength);
            attrs.put("user_count", typeCounts.getOrDefault("User", 0L));
            attrs.put("project_count", typeCounts.getOrDefault("Project", 0L));
            attrs.put("local_count", typeCounts.getOrDefault("Local", 0L));
            attrs.put("managed_count", typeCounts.getOrDefault("Managed", 0L));
            attrs.put("automem_count", typeCounts.getOrDefault("AutoMem", 0L));
            attrs.put("duration_ms", System.currentTimeMillis() - startTime);
            emitTelemetry("tengu_claudemd__initial_load", attrs);
        }

        // CC claudemd.ts:1042-1071 InstructionsLoaded hook —— fire-and-forget（audit/observability 仅）。
        // 门控 !forceIncludeExternal：forceIncludeExternal=true 仅供外部 include 审批检查（getExternalClaudeMdIncludes），
        // 不构建 context —— 发射会造成启动期双发。one-shot flag 在每次 !forceIncludeExternal 缓存 miss 时消费
        // （不 gate hasInstructionsLoadedHook），保证 flag 释放即使无 hook 配置（否则 session 中途 hook 注册 +
        // 直接 cache.clear 会以陈旧 'session_start' reason 误发）。
        // AutoMem/TeamMem 排除：独立 memory 系统，非 CLAUDE.md/rules 意义下的 instructions（claudemd.ts:1044-1045）。
        if (!forceIncludeExternal) {
            String eagerLoadReason = consumeNextEagerLoadReason();
            if (eagerLoadReason != null && hookRegistry != null) {
                for (MemoryFileInfo file : result) {
                    if (!isInstructionsMemoryType(file.type())) {
                        continue;
                    }
                    String loadReason = file.parent() != null ? "include" : eagerLoadReason;
                    fireInstructionsLoaded(file, loadReason);
                }
            }
        }

        return List.copyOf(result);
    }

    /**
     * 消费下一次 eager 加载 reason · CC original: {@code consumeNextEagerLoadReason}
     * （claudemd.ts:1102-1108）。{@code shouldFireHook=false} → 返回 null（不发射）；读后复位为
     * {@code 'session_start'}（one-shot）。
     */
    private String consumeNextEagerLoadReason() {
        if (!shouldFireHook) {
            return null;
        }
        shouldFireHook = false;
        String reason = nextEagerLoadReason;
        nextEagerLoadReason = "session_start";
        return reason;
    }

    /** 指令类型判定 · CC original: {@code isInstructionsMemoryType}（claudemd.ts:1077-1086）。 */
    private static boolean isInstructionsMemoryType(ClaudemdMemoryType type) {
        return type == ClaudemdMemoryType.USER
            || type == ClaudemdMemoryType.PROJECT
            || type == ClaudemdMemoryType.LOCAL
            || type == ClaudemdMemoryType.MANAGED;
    }

    /**
     * 发射 InstructionsLoaded hook（fire-and-forget）· CC original: {@code void
     * executeInstructionsLoadedHooks(...)}（claudemd.ts:1059-1069）—— 主路径不 await；
     * hook 在独立 executor 异步跑完（对齐 CC executeHooksOutsideREPL，hooks.ts:4335/4364）。
     *
     * <p><b>事件参数先快照</b>（session §8）：{@code sessionIdSupplier} 依赖 RequestContext/MDC，
     * 异步线程已切换上下文 —— 必须在调用线程同步调 {@code sessionIdSupplier.get()} 并构建
     * {@code HookEvent}，lambda 仅捕获 per-iteration 不可变 event（无循环变量复用）。
     *
     * <p><b>降级不崩溃</b>（验收 §5.4）：executor 不可用/已关闭/提交被拒 → warn 并跳过，
     * 不静默丢任务、不阻断 getMemoryFiles 主路径。
     *
     * @param file       记忆文件（path/type/globs/parent 在调用线程快照）
     * @param loadReason load_reason（'session_start'/'compact'/'include'）
     */
    private void fireInstructionsLoaded(MemoryFileInfo file, String loadReason) {
        // eager 主路径：triggerFilePath=null（CC getMemoryFiles 主路径不传 triggerFilePath）
        fireInstructionsLoaded(file, loadReason, null);
    }

    /**
     * 发射 InstructionsLoaded hook 的 3 参 overload（lazy 路径）· CC original:
     * {@code void executeInstructionsLoadedHooks(path, type, loadReason, {globs, triggerFilePath,
     * parentFilePath})}（attachments.ts:1758-1770 同构）。主路径（2 参，triggerFilePath=null）
     * 委托本方法 —— 单实现、行为不变，避免双轨漂移。
     *
     * @param file            记忆文件（path/type/globs/parent 在调用线程快照）
     * @param loadReason      load_reason（'path_glob_match'/'include'/'nested_traversal'）
     * @param triggerFilePath 触发文件路径（nested 加载语义，CC triggerFilePath；无 → null）
     */
    private void fireInstructionsLoaded(MemoryFileInfo file, String loadReason, String triggerFilePath) {
        // 调用线程同步快照事件参数（sessionIdSupplier 不得延迟到异步线程调，MDC 已切换）
        String sessionId = sessionIdSupplier != null ? sessionIdSupplier.get() : null;
        HookEvent event = HookEvent.instructionsLoaded(
            file.path(), file.type().ccName(), loadReason,
            sessionId, file.globs(), triggerFilePath, file.parent());

        java.util.concurrent.ExecutorService ex = hookExecutor();
        if (ex == null) {
            log.warn("[ClaudemdEngine] InstructionsLoaded hook 执行器不可用，跳过发射（不阻断 getMemoryFiles）: path={}",
                file.path());
            return;
        }
        if (ex.isShutdown()) {
            log.warn("[ClaudemdEngine] InstructionsLoaded hook 执行器已关闭，跳过发射（不阻断 getMemoryFiles）: path={}",
                file.path());
            return;
        }
        try {
            ex.submit(() -> {
                try {
                    hookRegistry.executeEvent(event);
                    if (log.isDebugEnabled()) {
                        log.debug("[ClaudemdEngine] InstructionsLoaded hook 异步执行完成: path={} type={} loadReason={}",
                            file.path(), file.type().ccName(), loadReason);
                    }
                } catch (Exception e) {
                    log.warn("[ClaudemdEngine] InstructionsLoaded hook 执行失败（不阻断 getMemoryFiles）: {}",
                        e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("[ClaudemdEngine] InstructionsLoaded hook 提交被拒绝（不阻断 getMemoryFiles）: {}",
                e.getMessage());
        }
    }

    /**
     * 清除 getMemoryFiles memoize 缓存（不触发 InstructionsLoaded hook）· CC original:
     * {@code clearMemoryFileCaches}（claudemd.ts:1119-1122）。
     *
     * <p><b>用途</b>：纯正确性失效（worktree 进出 / 设置同步 / /memory 对话框）——CC 明确
     * 此类失效不应触发 InstructionsLoaded hook 误报（claudemd.ts:1095-1099）。
     */
    public void clearMemoryFileCaches() {
        memoryFilesCache.clear();
        if (log.isDebugEnabled()) {
            log.debug("[ClaudemdEngine] clearMemoryFileCaches: getMemoryFiles 缓存已清空");
        }
    }

    /**
     * 清缓存并重置 one-shot InstructionsLoaded 发射态 · CC original: {@code resetGetMemoryFilesCache}
     * （claudemd.ts:1124-1130）。
     *
     * <p>设置 {@code nextEagerLoadReason} + {@code shouldFireHook=true}（下次缓存 miss 发射 hook 上报
     * 真实 reason，如 'compact' 而非误报 'session_start'）+ 清缓存。压缩（PostCompactCleanup main-thread
     * 分支）与代表"instructions 真正重载入 context"的事件调用。
     *
     * @param reason 下一次 eager 加载要上报的 reason（null → 默认 'session_start'，对齐 CC 缺省参数）
     */
    public void resetGetMemoryFilesCache(String reason) {
        nextEagerLoadReason = reason == null ? "session_start" : reason;
        shouldFireHook = true;
        clearMemoryFileCaches();
        log.info("[ClaudemdEngine] resetGetMemoryFilesCache: reason={} 已置 one-shot 发射态并清空缓存（对齐 CC claudemd.ts:1124-1130）",
            nextEagerLoadReason);
    }

    private static boolean bool(Supplier<Boolean> s) {
        return s != null && Boolean.TRUE.equals(s.get());
    }

    // ════════════════════════════════════════════════════════════════
    // processMemoryFile / @include · claudemd.ts:618-685
    // ════════════════════════════════════════════════════════════════

    /**
     * getMemoryFiles 内联处理器 · 递归 {@link #processMemoryFile} 并把结果并入 result。
     * 空文件/被排除 → 不并入（CC processMemoryFile 返回空数组）。
     */
    private void processMemoryFileInto(List<MemoryFileInfo> result, String filePath,
                                       ClaudemdMemoryType type, Set<String> processedPaths,
                                       boolean includeExternal, int depth, String parent) {
        result.addAll(processMemoryFile(filePath, type, processedPaths, includeExternal, depth, parent));
    }

    /**
     * 递归处理记忆文件及其全部 @include · CC original: {@code processMemoryFile}
     * （claudemd.ts:618-685）。
     *
     * <p>语义：
     * <ul>
     *   <li>已处理（processedPaths 命中）或 depth >= MAX_INCLUDE_DEPTH → 返回空</li>
     *   <li>claudeMdExcludes 命中 → 返回空（仅 User/Project/Local，claudemd.ts:547-573）</li>
     *   <li>读失败（ENOENT/EISDIR）→ 返回空</li>
     *   <li>内容为空（trim 后）→ 返回空（claudemd.ts:652-654）</li>
     *   <li>父文件先入 result，再递归 include 子文件（子文件 parent = 当前文件，
     *       claudemd.ts:663-682）</li>
     *   <li>include 外部文件（不在 originalCwd 内）需 includeExternal=true 才包含
     *       （claudemd.ts:667-671）</li>
     * </ul>
     *
     * @return 有序 MemoryFileInfo（父在前，includes 在后）
     */
    public List<MemoryFileInfo> processMemoryFile(String filePath, ClaudemdMemoryType type,
                                                  Set<String> processedPaths, boolean includeExternal,
                                                  int depth, String parent) {
        // 已处理 / 超深（claudemd.ts:629-632）
        String normalizedPath = normalizeForComparison(filePath);
        if (processedPaths.contains(normalizedPath) || depth >= MAX_INCLUDE_DEPTH) {
            if (log.isDebugEnabled()) {
                log.debug("[ClaudemdEngine] processMemoryFile 跳过（已处理={} 超深={}）: {} depth={}",
                    processedPaths.contains(normalizedPath), depth >= MAX_INCLUDE_DEPTH, filePath, depth);
            }
            return List.of();
        }
        if (isClaudeMdExcluded(filePath, type)) {
            return List.of();
        }

        // symlink 解析（CC safeResolvePath）· 简化：realpath 可得则用。仅作 @include 基准 + 去重；
        //   读原路径 filePath、MemoryFileInfo.path=原路径（CC claudemd.ts:650-651
        //   safelyReadMemoryFileAsync(filePath, type, resolvedPath) —— path 保留原始路径，
        //   resolvedPath 仅传 includeBasePath 供 @include 解析）
        String resolvedPath = resolveSymlink(filePath);
        processedPaths.add(normalizedPath);
        if (!resolvedPath.equals(filePath)) {
            processedPaths.add(normalizeForComparison(resolvedPath));
        }

        MemoryFileInfo memoryFile = safelyReadMemoryFile(filePath, type);
        if (memoryFile == null || memoryFile.content().trim().isEmpty()) {
            return List.of();
        }
        MemoryFileInfo withParent = (parent != null)
            ? new MemoryFileInfo(memoryFile.path(), memoryFile.type(), memoryFile.content(), parent,
                memoryFile.globs(), memoryFile.contentDiffersFromDisk(), memoryFile.rawContent())
            : memoryFile;

        List<MemoryFileInfo> result = new ArrayList<>();
        result.add(withParent);

        // @include 解析（includeBasePath = resolvedPath）
        List<String> includePaths = extractIncludes(resolvedPath, type, memoryFile);
        for (String includePath : includePaths) {
            boolean isExternal = !pathInWorkingPath(includePath, originalCwdSupplier.get());
            if (isExternal && !includeExternal) {
                continue;
            }
            result.addAll(processMemoryFile(includePath, type, processedPaths, includeExternal,
                depth + 1, filePath));
        }
        return result;
    }

    /**
     * @include 路径提取 · 复用 lexer（跳过 code/codespan，处理 html 注释 residue）。
     * 基于 {@code content()}（已 strip frontmatter + strip HTML 注释）提取 —— CC 在
     * parseMemoryFileContent 对 withoutFrontmatter tokens 一次 lex 同时产出 includes
     * （claudemd.ts:376-379），本实现独立 lex 但语义等价（注释已 strip，extract 本就
     * 跳过 html token）。AutoMem/TeamMem 不经过 processMemoryFile（走 entrypoint 直读），
     * 故此处无截断干扰。
     */
    private List<String> extractIncludes(String resolvedPath, ClaudemdMemoryType type, MemoryFileInfo memoryFile) {
        return ClaudemdLexer.extractIncludePaths(memoryFile.content(), resolvedPath);
    }

    // ════════════════════════════════════════════════════════════════
    // parseMemoryFileContent / safelyReadMemoryFile · claudemd.ts:343-437
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析记忆文件内容为 MemoryFileInfo · CC original: {@code parseMemoryFileContent}
     * （claudemd.ts:343-400）。纯函数（无 I/O）。
     *
     * <p>流程：
     * <ol>
     *   <li>非文本扩展名 → null（防加载二进制，claudemd.ts:349-354）</li>
     *   <li>parseFrontmatterPaths：剥离 frontmatter + 提取 paths globs
     *       （claudemd.ts:356-357）</li>
     *   <li>stripHtmlComments（仅含 {@code <!--} 时触发 lex，claudemd.ts:362-374）</li>
     *   <li>AutoMem/TeamMem 截断（truncateEntrypointContent，claudemd.ts:381-385）</li>
     *   <li>contentDiffersFromDisk = 处理内容 != 原始（claudemd.ts:388-396）</li>
     * </ol>
     *
     * @return MemoryFileInfo 或 null（非文本/读取失败）
     */
    MemoryFileInfo parseMemoryFileContent(String rawContent, String filePath, ClaudemdMemoryType type) {
        String ext = fileExtension(filePath);
        if (!ext.isEmpty() && !TEXT_FILE_EXTENSIONS.contains(ext)) {
            if (log.isDebugEnabled()) {
                log.debug("[ClaudemdEngine] @include 跳过非文本文件: {}", filePath);
            }
            return null;
        }
        ClaudemdLexer.FrontmatterResult fm = ClaudemdLexer.parseFrontmatter(rawContent);
        List<String> globs = parseFrontmatterPaths(fm);
        String withoutFrontmatter = fm.content();

        boolean hasComment = withoutFrontmatter.contains("<!--");
        String strippedContent = hasComment
            ? ClaudemdLexer.stripHtmlComments(withoutFrontmatter).content()
            : withoutFrontmatter;

        String finalContent = strippedContent;
        if (type == ClaudemdMemoryType.AUTO_MEM || type == ClaudemdMemoryType.TEAM_MEM) {
            finalContent = truncateEntrypointContent(strippedContent).content();
        }

        boolean contentDiffersFromDisk = !finalContent.equals(rawContent);
        return new MemoryFileInfo(filePath, type, finalContent, null, globs, contentDiffersFromDisk,
            contentDiffersFromDisk ? rawContent : null);
    }

    /** 从 frontmatter 提取 paths globs · CC parseFrontmatterPaths（claudemd.ts:254-279）。 */
    private List<String> parseFrontmatterPaths(ClaudemdLexer.FrontmatterResult fm) {
        Object pathsVal = fm.frontmatter().get("paths");
        if (pathsVal == null) {
            return null;
        }
        // CLD-03：paths 值可为 String 或 List（内联数组 [a.md, b.md] 由 parseSimpleYaml 解析为
        // List）—— CC splitPathInFrontmatter 接受 string | string[]（frontmatterParser.ts:189-192
        // flatMap），逐元素拆分 + brace 展开
        List<String> patternChunks = new ArrayList<>();
        if (pathsVal instanceof java.util.List<?> list) {
            for (Object o : list) {
                patternChunks.add(String.valueOf(o));
            }
        } else {
            patternChunks.add(String.valueOf(pathsVal));
        }
        List<String> patterns = new ArrayList<>();
        for (String chunk : patternChunks) {
            patterns.addAll(ClaudemdLexer.splitPathInFrontmatter(chunk));
        }
        List<String> cleaned = new ArrayList<>();
        for (String pattern : patterns) {
            // 去掉 /** 后缀（ignore 库视 'path' 同时匹配自身与内部，claudemd.ts:266-268）
            String p = pattern.endsWith("/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (!p.isEmpty()) {
                cleaned.add(p);
            }
        }
        // 全为 **（match-all）→ 视为无 globs（claudemd.ts:274-276）
        if (cleaned.isEmpty() || cleaned.stream().allMatch(p -> p.equals("**"))) {
            return null;
        }
        return cleaned;
    }

    /** 读文件 + 解析 · CC safelyReadMemoryFileAsync（claudemd.ts:424-437）。path=filePath（原路径）。 */
    private MemoryFileInfo safelyReadMemoryFile(String filePath, ClaudemdMemoryType type) {
        try {
            // REQ-15△（OPD-CM3-19/D05）：读前 isRegularFile 判定（对齐 safelyReadEntrypoint:775-777）。
            //   目录/不存在路径 → null 静默返回，不再进 Files.readString —— Windows 11
            //   Files.readString(目录) 抛 AccessDeniedException（非 NoSuchFileException）→ 误发
            //   tengu_claude_md_permission_error（CC 对 EISDIR 静默 claudemd.ts:405；E4 实测 EV-F1-20）。
            if (!Files.isRegularFile(Paths.get(filePath))) {
                return null;
            }
            String rawContent = Files.readString(Paths.get(filePath));
            return parseMemoryFileContent(rawContent, filePath, type);
        } catch (IOException | RuntimeException e) {
            handleMemoryFileReadError(e, filePath);
            return null;
        }
    }

    /**
     * Memdir/TeamMem 入口直读 · CC getMemoryFiles:981-1007 的 {@code safelyReadMemoryFileAsync}
     * （AutoMem/TeamMem 不走 processMemoryFile，无 @include）。解析失败 → null。
     */
    private MemoryFileInfo safelyReadEntrypoint(String filePath, ClaudemdMemoryType type) {
        try {
            if (!Files.isRegularFile(Paths.get(filePath))) {
                return null;
            }
            String rawContent = Files.readString(Paths.get(filePath));
            return parseMemoryFileContent(rawContent, filePath, type);
        } catch (IOException | RuntimeException e) {
            handleMemoryFileReadError(e, filePath);
            return null;
        }
    }

    /** 读错误处理 · CC handleMemoryFileReadError（claudemd.ts:402-416）。 */
    private void handleMemoryFileReadError(Throwable error, String filePath) {
        // ENOENT/EISDIR 期望内 → 静默；其余 debug 日志（PII 最小化，不打完整路径）
        if (error instanceof java.nio.file.NoSuchFileException) {
            return;
        }
        // EACCES 权限错误 → telemetry（CC claudemd.ts:411-416 tengu_claude_md_permission_error；
        //   不打完整文件路径防 PII/安全，has_home_dir 只记是否在 configHome 或 nexusai 自有根）
        if (error instanceof java.nio.file.AccessDeniedException) {
            boolean inHome = filePath.contains(ClaudePaths.getClaudeConfigHomeDir())
                || filePath.contains(NexusaiPaths.getAppConfigHomeDir());
            emitTelemetry("tengu_claude_md_permission_error", java.util.Map.of(
                "is_access_error", 1,
                "has_home_dir", inHome ? 1 : 0));
        }
        if (log.isDebugEnabled()) {
            log.debug("[ClaudemdEngine] 记忆文件读取失败（非 ENOENT）: cause={}", error.toString());
        }
    }

    /** 文件扩展名（含点，小写）· CC extname(filePath).toLowerCase()。 */
    private static String fileExtension(String filePath) {
        String name = Paths.get(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** claudeMdExcludes 命中判定 · CC isClaudeMdExcluded（claudemd.ts:547-573）。无 settings 源 → false。 */
    boolean isClaudeMdExcluded(String filePath, ClaudemdMemoryType type) {
        if (type != ClaudemdMemoryType.USER
            && type != ClaudemdMemoryType.PROJECT
            && type != ClaudemdMemoryType.LOCAL) {
            return false;
        }
        List<String> patterns = claudeMdExcludesSupplier != null ? claudeMdExcludesSupplier.get() : null;
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String normalizedPath = filePath.replace('\\', '/');
        // CC claudemd.ts:565-566 —— realpath 静态前缀展开（symlink 两侧匹配）+ 空 pattern 过滤
        List<String> expandedPatterns = resolveExcludePatterns(patterns).stream()
            .filter(p -> !p.isEmpty()).toList();
        if (expandedPatterns.isEmpty()) {
            return false;
        }
        // CC claudemd.ts:572 —— picomatch.isMatch(path, patterns, {dot:true})
        return ClaudemdPicomatch.isMatch(normalizedPath, expandedPatterns);
    }

    /**
     * 绝对 pattern 静态前缀 realpath 展开 · CC resolveExcludePatterns（claudemd.ts:581-612）。
     * 对每个以 {@code /} 开头的 pattern，取其 glob 元字符（{@code *?{[}）之前的静态前缀，
     * 对前缀目录 realpathSync 解析并追加「解析后前缀 + 原 pattern 剩余」到展开列表 —— symlink
     * （如 /tmp→/private/tmp）两侧均参与匹配。目录不存在 → 静默跳过（CC catch）。
     * 新追加项继续参与迭代（CC for-of 覆盖 push 后增长列表；realpath 幂等 → 收敛）。
     */
    static List<String> resolveExcludePatterns(List<String> patterns) {
        List<String> expanded = new ArrayList<>();
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            expanded.add(p.replace('\\', '/'));
        }
        for (int i = 0; i < expanded.size(); i++) {
            String normalized = expanded.get(i);
            if (!normalized.startsWith("/")) {
                continue; // 非绝对 pattern（如 **/*.md）无文件系统前缀可解析
            }
            int globStart = indexOfGlobChar(normalized);
            String staticPrefix = globStart == -1 ? normalized : normalized.substring(0, globStart);
            String dirToResolve = dirname(staticPrefix);
            String resolvedDir;
            try {
                resolvedDir = Paths.get(dirToResolve).toRealPath().toString().replace('\\', '/');
            } catch (IOException e) {
                continue; // 目录不存在 → 跳过解析（CC :606-608）
            }
            if (!resolvedDir.equals(dirToResolve)) {
                expanded.add(resolvedDir + normalized.substring(dirToResolve.length()));
            }
        }
        return expanded;
    }

    /** 首个 glob 元字符（* ? { [）下标 · CC {@code /[*?{[]/}。 */
    private static int indexOfGlobChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '[') {
                return i;
            }
        }
        return -1;
    }

    /** JS path.dirname 等价（POSIX 斜杠语义）· CC resolveExcludePatterns 用 path.dirname。 */
    private static String dirname(String path) {
        int idx = path.lastIndexOf('/');
        if (idx == -1) {
            return ".";
        }
        if (idx == 0) {
            return "/";
        }
        return path.substring(0, idx);
    }

    /** symlink 解析（CC safeResolvePath）· realpath 失败（文件不存在等）→ 原路径。 */
    private static String resolveSymlink(String filePath) {
        try {
            return Paths.get(filePath).toRealPath().toString();
        } catch (IOException e) {
            return filePath;
        }
    }

    /**
     * 截断 MEMORY.md 入口到行数 + 字节双上限 · CC truncateEntrypointContent（memdir.ts:57-103）。
     * 行截断优先（自然边界），再字节截断到 cap 前最后一个换行；追加 WARNING 注明触发哪个 cap。
     */
    private ClaudemdLexer.StripResult truncateEntrypointContent(String raw) {
        String trimmed = raw.trim();
        String[] contentLines = trimmed.split("\\n", -1);
        int lineCount = contentLines.length;
        int byteCount = trimmed.length();
        boolean wasLineTruncated = lineCount > MAX_ENTRYPOINT_LINES;
        boolean wasByteTruncated = byteCount > MAX_ENTRYPOINT_BYTES;
        if (!wasLineTruncated && !wasByteTruncated) {
            return new ClaudemdLexer.StripResult(trimmed, false);
        }
        String truncated = wasLineTruncated
            ? joinLines(contentLines, MAX_ENTRYPOINT_LINES)
            : trimmed;
        if (truncated.length() > MAX_ENTRYPOINT_BYTES) {
            int cutAt = truncated.lastIndexOf('\n', MAX_ENTRYPOINT_BYTES);
            truncated = truncated.substring(0, cutAt > 0 ? cutAt : MAX_ENTRYPOINT_BYTES);
        }
        String reason = wasByteTruncated && !wasLineTruncated
            ? formatFileSize(byteCount) + " (limit: " + formatFileSize(MAX_ENTRYPOINT_BYTES)
                + ") — index entries are too long"
            : wasLineTruncated && !wasByteTruncated
                ? lineCount + " lines (limit: " + MAX_ENTRYPOINT_LINES + ")"
                : lineCount + " lines and " + formatFileSize(byteCount);
        String warning = "\n\n> WARNING: " + ENTRYPOINT_NAME + " is " + reason
            + ". Only part of it was loaded. Keep index entries to one line under ~200 chars; "
            + "move detail into topic files.";
        return new ClaudemdLexer.StripResult(truncated + warning, true);
    }

    /**
     * 字节数人类可读格式化 · CC original: {@code formatFileSize}（utils/format.ts:9-23，
     * memdir.ts:89/92 消费）—— {@code <1KB → "N bytes"}；{@code <1024KB → "X.XKB"}（去掉
     * {@code .0} 尾）；MB/GB 同理。WARNING 文本字节形态对齐（NEW-5/OPD-R2-CLD-05⑥）。
     */
    private static String formatFileSize(int sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            return trimTrailingDotZero(String.format(Locale.ROOT, "%.1f", kb)) + "KB";
        }
        double mb = kb / 1024;
        if (mb < 1024) {
            return trimTrailingDotZero(String.format(Locale.ROOT, "%.1f", mb)) + "MB";
        }
        double gb = mb / 1024;
        return trimTrailingDotZero(String.format(Locale.ROOT, "%.1f", gb)) + "GB";
    }

    /** CC {@code toFixed(1).replace(/\.0$/, '')}（format.ts:15）等价：去掉 {@code .0} 尾。 */
    private static String trimTrailingDotZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String joinLines(String[] lines, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count && i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // processMdRules / processConditionedMdRules · claudemd.ts:697-788 / 1354-1397
    // ════════════════════════════════════════════════════════════════

    /** processMdRules 内联处理器（并入 result）· 环检测 visitedDirs 每规则目录独立。 */
    private void processMdRulesInto(List<MemoryFileInfo> result, String rulesDir, ClaudemdMemoryType type,
                                    Set<String> processedPaths, boolean includeExternal,
                                    boolean conditionalRule, Set<String> visitedDirs) {
        result.addAll(processMdRules(rulesDir, type, processedPaths, includeExternal,
            conditionalRule, visitedDirs));
    }

    /**
     * 处理 {@code .claude/rules/} 目录下全部 .md 文件 · CC original: {@code processMdRules}
     * （claudemd.ts:697-788）。
     *
     * <p>语义（对齐 CC claudemd.ts:697-788）：
     * <ul>
     *   <li>visitedDirs 环检测双登记：字面 rulesDir + symlink 时的 resolvedRulesDir
     *       （claudemd.ts:712-727），realpath 去重防 symlink 环无限递归</li>
     *   <li>readdir 用 resolved 路径（claudemd.ts:732）；目录递归与 .md 处理均用
     *       resolvedEntryPath（claudemd.ts:756-771）→ MemoryFileInfo.path=realpath</li>
     *   <li>readdir ENOENT/EACCES/ENOTDIR → 空（claudemd.ts:733-739）</li>
     *   <li>conditionalRule=true → 仅保留有 globs 的文件；false → 仅保留无 globs 的文件
     *       （claudemd.ts:773）</li>
     * </ul>
     *
     * @param conditionalRule true=条件规则（有 frontmatter paths）；false=无条件规则
     * @param visitedDirs     已访问目录集合（环检测；跨调用传同一集合可合并）
     */
    public List<MemoryFileInfo> processMdRules(String rulesDir, ClaudemdMemoryType type,
                                               Set<String> processedPaths, boolean includeExternal,
                                               boolean conditionalRule, Set<String> visitedDirs) {
        if (visitedDirs.contains(rulesDir)) {
            return List.of();
        }

        // CC safeResolvePath（fsOperations.ts:138-178）· realpath 解析失败 → 原路径。
        // 环检测双登记：字面 rulesDir + symlink 时的 resolvedRulesDir（claudemd.ts:719-727），
        // 使经不同 symlink 路径到达同一真实目录的第二次访问被拦截，防 symlink 环无限递归。
        String resolvedRulesDir = resolveSymlink(rulesDir);
        boolean isRulesDirSymlink = !resolvedRulesDir.equals(rulesDir);
        visitedDirs.add(rulesDir);
        if (isRulesDirSymlink) {
            visitedDirs.add(resolvedRulesDir);
        }

        List<MemoryFileInfo> result = new ArrayList<>();
        // readdir 用 resolved 路径（claudemd.ts:732 readdir(resolvedRulesDir)）
        java.io.File dir = new java.io.File(resolvedRulesDir);
        java.io.File[] entries;
        try {
            if (!dir.isDirectory()) {
                return List.of();
            }
            entries = dir.listFiles();
        } catch (SecurityException e) {
            return List.of();
        }
        if (entries == null) {
            // readdir 权限失败（Java listFiles()→null ≈ CC EACCES 内层 catch 返回空 claudemd.ts:733-739）。
            // △-12 已知保留：Java 此处发遥测，而 CC 内层静默（EACCES 遥测仅在外层 catch claudemd.ts:779-784）。
            boolean rulesInHome = rulesDir.contains(ClaudePaths.getClaudeConfigHomeDir())
                || rulesDir.contains(NexusaiPaths.getAppConfigHomeDir());
            emitTelemetry("tengu_claude_rules_md_permission_error", java.util.Map.of(
                "is_access_error", 1,
                "has_home_dir", rulesInHome ? 1 : 0));
            if (log.isDebugEnabled()) {
                log.debug("[ClaudemdEngine] rules 目录不可读（readdir 权限失败），返回空: rulesDir={}", rulesDir);
            }
            return List.of();
        }
        for (java.io.File entry : entries) {
            // 每项经 safeResolvePath（claudemd.ts:743-746）：递归与 .md 处理均用 resolvedEntryPath，
            // 使 MemoryFileInfo.path=realpath（info.path 去重与 @include 基准对齐 CC claudemd.ts:765-771）
            String resolvedEntryPath = resolveSymlink(entry.getAbsolutePath());
            if (new java.io.File(resolvedEntryPath).isDirectory()) {
                result.addAll(processMdRules(resolvedEntryPath, type, processedPaths,
                    includeExternal, conditionalRule, visitedDirs));
            } else if (new java.io.File(resolvedEntryPath).isFile() && entry.getName().endsWith(".md")) {
                List<MemoryFileInfo> files = processMemoryFile(resolvedEntryPath, type,
                    processedPaths, includeExternal, 0, null);
                for (MemoryFileInfo f : files) {
                    if (conditionalRule ? f.globs() != null : f.globs() == null) {
                        result.add(f);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 处理条件规则（frontmatter paths 匹配目标路径）· CC original:
     * {@code processConditionedMdRules}（claudemd.ts:1354-1397）。
     *
     * <p>语义：
     * <ul>
     *   <li>调 {@link #processMdRules}(conditionalRule=true) 收集有 globs 的文件</li>
     *   <li>glob 相对基准：Project 规则 → {@code dirname(dirname(rulesDir))}（.claude 的父目录）；
     *       Managed/User → originalCwd（claudemd.ts:1375-1380）</li>
     *   <li>拒绝 {@code ../}、绝对路径、空 relativePath（claudemd.ts:1382-1394）——
     *       基准外的文件不可能被基准相对 glob 匹配</li>
     *   <li>{@code ignore().add(globs).ignores(relativePath)} 判定（claudemd.ts:1395）</li>
     * </ul>
     *
     * @param targetPath 目标文件路径（glob 匹配对象）
     */
    public List<MemoryFileInfo> processConditionedMdRules(String targetPath, String rulesDir,
                                                          ClaudemdMemoryType type,
                                                          Set<String> processedPaths,
                                                          boolean includeExternal) {
        List<MemoryFileInfo> conditioned = processMdRules(rulesDir, type, processedPaths,
            includeExternal, true, new LinkedHashSet<>());
        List<MemoryFileInfo> matched = new ArrayList<>();
        String baseDir = (type == ClaudemdMemoryType.PROJECT)
            ? Paths.get(rulesDir).getParent() == null ? null : Paths.get(rulesDir).getParent().getParent().toString()
            : originalCwdSupplier.get();
        for (MemoryFileInfo file : conditioned) {
            if (file.globs() == null || file.globs().isEmpty()) {
                continue;
            }
            String relativePath;
            if (Paths.get(targetPath).isAbsolute()) {
                if (baseDir == null) {
                    continue;
                }
                relativePath = relativize(baseDir, targetPath);
            } else {
                relativePath = targetPath;
            }
            if (relativePath == null
                || relativePath.startsWith("..")
                || Paths.get(relativePath).isAbsolute()) {
                continue;
            }
            if (ClaudemdGlob.empty().add(file.globs()).ignores(relativePath)) {
                matched.add(file);
            }
        }
        return matched;
    }

    // ════════════════════════════════════════════════════════════════
    // 嵌套目录加载 · claudemd.ts:1205-1342（getManagedAndUserConditionalRules /
    //   getMemoryFilesForNestedDirectory / getConditionalRulesForCwdLevelDirectory）
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取 Managed/User 条件规则（匹配目标路径）· 嵌套记忆加载第一阶段 · CC original:
     * {@code getManagedAndUserConditionalRules}（claudemd.ts:1205-1238）。
     *
     * @param targetPath     匹配 glob 的目标文件路径
     * @param processedPaths 已处理路径集合（会被修改）
     * @return 匹配的 Managed + User 条件规则（有 globs 且 glob 命中 targetPath）
     */
    public List<MemoryFileInfo> getManagedAndUserConditionalRules(String targetPath, Set<String> processedPaths) {
        List<MemoryFileInfo> result = new ArrayList<>();
        result.addAll(processConditionedMdRules(targetPath, getManagedClaudeRulesDir(),
            ClaudemdMemoryType.MANAGED, processedPaths, false));
        if (bool(userSettingsEnabled)) {
            // 决策 D1/D3：User 条件规则双目录（nexusai 自有根 + claude 只读兼容回落）
            result.addAll(processConditionedMdRules(targetPath, getUserClaudeRulesDir(),
                ClaudemdMemoryType.USER, processedPaths, true));
            result.addAll(processConditionedMdRules(targetPath, getClaudeUserClaudeRulesDir(),
                ClaudemdMemoryType.USER, processedPaths, true));
        }
        return result;
    }

    /**
     * 获取单个嵌套目录的记忆文件（cwd 与 target 之间）· CC original:
     * {@code getMemoryFilesForNestedDirectory}（claudemd.ts:1249-1318）。
     *
     * <p>加载 CLAUDE.md / .claude/CLAUDE.md / CLAUDE.local.md + 无条件 rules（独立 processedPaths
     * 副本避免污染条件规则去重）+ 条件 rules（glob 匹配 targetPath）。无条件路径随后种子回
     * processedPaths（供后续目录去重，claudemd.ts:1312-1315）。
     *
     * @param dir            待处理目录
     * @param targetPath     目标文件路径（条件规则匹配）
     * @param processedPaths 已处理路径集合（会被修改）
     * @return 该目录的记忆文件列表
     */
    public List<MemoryFileInfo> getMemoryFilesForNestedDirectory(String dir, String targetPath,
                                                                 Set<String> processedPaths) {
        List<MemoryFileInfo> result = new ArrayList<>();
        if (bool(projectSettingsEnabled)) {
            result.addAll(processMemoryFile(Paths.get(dir, "CLAUDE.md").toString(),
                ClaudemdMemoryType.PROJECT, processedPaths, false, 0, null));
            // 决策 D1/D6（T4 补充）：项目级 CLAUDE.md nexusai 优先 + claude 回落
            result.addAll(processMemoryFile(resolveFirstExisting(
                Paths.get(dir, NexusaiPaths.getProjectDirName(), "CLAUDE.md"),
                Paths.get(dir, ".claude", "CLAUDE.md")),
                ClaudemdMemoryType.PROJECT, processedPaths, false, 0, null));
        }
        if (bool(localSettingsEnabled)) {
            result.addAll(processMemoryFile(Paths.get(dir, "CLAUDE.local.md").toString(),
                ClaudemdMemoryType.LOCAL, processedPaths, false, 0, null));
        }
        // 决策 D1/D6：rules 双目录（nexusai + claude）
        String rulesDir = Paths.get(dir, NexusaiPaths.getProjectDirName(), "rules").toString();
        String claudeRulesDir = Paths.get(dir, ".claude", "rules").toString();
        // 无条件 rules（未 eager 加载）· 独立 processedPaths 副本避免把条件规则文件标为已处理
        Set<String> unconditionalProcessedPaths = new LinkedHashSet<>(processedPaths);
        result.addAll(processMdRules(rulesDir, ClaudemdMemoryType.PROJECT, unconditionalProcessedPaths,
            false, false, new LinkedHashSet<>()));
        result.addAll(processMdRules(claudeRulesDir, ClaudemdMemoryType.PROJECT, unconditionalProcessedPaths,
            false, false, new LinkedHashSet<>()));
        // 条件 rules（glob 匹配 targetPath）· 双目录
        result.addAll(processConditionedMdRules(targetPath, rulesDir, ClaudemdMemoryType.PROJECT,
            processedPaths, false));
        result.addAll(processConditionedMdRules(targetPath, claudeRulesDir, ClaudemdMemoryType.PROJECT,
            processedPaths, false));
        // 无条件路径种子回 processedPaths（供后续目录去重，claudemd.ts:1312-1315）
        processedPaths.addAll(unconditionalProcessedPaths);
        return result;
    }

    /**
     * 获取 cwd 级目录的条件规则（root→cwd）· CC original:
     * {@code getConditionalRulesForCwdLevelDirectory}（claudemd.ts:1329-1342）。
     *
     * <p>仅条件规则（无条件规则已 eager 加载），Project 类型，glob 匹配 targetPath。
     *
     * @param dir            待处理目录
     * @param targetPath     目标文件路径（条件规则匹配）
     * @param processedPaths 已处理路径集合（会被修改）
     * @return 匹配的条件规则列表
     */
    public List<MemoryFileInfo> getConditionalRulesForCwdLevelDirectory(String dir, String targetPath,
                                                                        Set<String> processedPaths) {
        // 决策 D1/D6：条件 rules 双目录（nexusai + claude，glob 匹配）
        String rulesDir = Paths.get(dir, NexusaiPaths.getProjectDirName(), "rules").toString();
        String claudeRulesDir = Paths.get(dir, ".claude", "rules").toString();
        List<MemoryFileInfo> nexusaiRules = processConditionedMdRules(targetPath, rulesDir,
            ClaudemdMemoryType.PROJECT, processedPaths, false);
        List<MemoryFileInfo> claudeRules = processConditionedMdRules(targetPath, claudeRulesDir,
            ClaudemdMemoryType.PROJECT, processedPaths, false);
        nexusaiRules.addAll(claudeRules);
        return nexusaiRules;
    }

    // ════════════════════════════════════════════════════════════════
    // lazy-load 发射点 · attachments.ts:1710-1870 memoryFilesToAttachments /
    //   getNestedMemoryAttachmentsForFile + :2165-2190 getNestedMemoryAttachments
    // ════════════════════════════════════════════════════════════════

    /**
     * nested memory 文件 → 新加载列表 + instructions-type 发射 InstructionsLoaded +
     * readFileState 注册 · CC original: {@code memoryFilesToAttachments}（attachments.ts:1710-1770）。
     *
     * <p><b>lazy-load 发射点</b>（ODF-B4R-LAZY）：与 {@code getMemoryFiles} 主路径
     * （eager，claudemd.ts:1042-1071）语义区分 —— 本方法仅 nested 加载路径按需调用，发射
     * reason 三态（attachments.ts:1760-1763）：globs 非空 → {@code 'path_glob_match'}；
     * parent 非空 → {@code 'include'}；否则 → {@code 'nested_traversal'}。
     *
     * <p><b>双源去重</b>（CLD-02/OPD-R2-CLD-02，attachments.ts:1719-1725）：
     * <ol>
     *   <li>{@code loadedNestedMemoryPaths.has}（会话级非驱逐 Set）—— LRU 驱逐后重注入回归
     *       守卫（REPL.tsx:1964-1967/Tool.ts:216-220 注释）</li>
     *   <li>{@code readFileState.has}（100 条目双限 LRU）—— 命中 = 模型本会话已 Read/Edit/Write
     *       该文件，内容已在上下文 → 跳过注入（attachments.ts:1725）</li>
     * </ol>
     *
     * <p><b>注入后注册 readFileState</b>（CLD-02，attachments.ts:1742-1750）：
     * {@code set(path, {content: rawContent??content, timestamp: Date.now(), offset: undefined,
     * limit: undefined, isPartialView: contentDiffersFromDisk})} —— isPartialView=true 时
     * Edit/Write 门禁拒绝「未 Read 直接写」（FileEditTool.ts:276 ↔ Java EditFileTool:369-373 /
     * WriteFileTool:314-318），getChangedFiles 变更检测亦基于该 entry。
     *
     * @param memoryFiles              候选记忆文件（已按阶段去重）
     * @param loadedNestedMemoryPaths  会话级已加载路径集合（会被修改）
     * @param readFileState            会话级 readFileState 缓存（会被写入；null → 跳过
     *                                 has/set，行为退化为单源去重 —— 仅测试/无 TUC 场景）
     * @param triggerFilePath          触发文件路径（CC triggerFilePath，发射事件上报）
     * @return 新加载（未被去重）的记忆文件列表
     */
    public List<MemoryFileInfo> memoryFilesToAttachments(List<MemoryFileInfo> memoryFiles,
                                                         Set<String> loadedNestedMemoryPaths,
                                                         com.nexusai.application.agent.tool.FileStateCache readFileState,
                                                         String triggerFilePath) {
        List<MemoryFileInfo> newlyLoaded = new ArrayList<>();
        boolean shouldFireHook = hookRegistry != null;   // CC hasInstructionsLoadedHook() 空判定
        for (MemoryFileInfo memoryFile : memoryFiles) {
            if (loadedNestedMemoryPaths.contains(memoryFile.path())) {
                continue;
            }
            String cacheKey = readFileStateKey(memoryFile.path());
            // 双源去重 ②：readFileState 命中（本会话已 Read/Edit/Write）→ 跳过注入
            if (readFileState != null && readFileState.has(cacheKey)) {
                continue;
            }
            loadedNestedMemoryPaths.add(memoryFile.path());
            newlyLoaded.add(memoryFile);
            // 注入后注册（CC :1742-1750）：content = 内容与磁盘不一致时 rawContent ?? content；
            // isPartialView = contentDiffersFromDisk → Edit/Write 门禁要求先真实 Read
            if (readFileState != null) {
                String content = memoryFile.contentDiffersFromDisk()
                    ? (memoryFile.rawContent() != null ? memoryFile.rawContent() : memoryFile.content())
                    : memoryFile.content();
                readFileState.set(cacheKey, new com.nexusai.application.agent.tool.ToolUseContext.ReadState(
                    System.currentTimeMillis(), null, null, memoryFile.contentDiffersFromDisk(), content));
            }
            if (shouldFireHook && isInstructionsMemoryType(memoryFile.type())) {
                String loadReason = (memoryFile.globs() != null && !memoryFile.globs().isEmpty())
                    ? "path_glob_match"
                    : memoryFile.parent() != null ? "include" : "nested_traversal";
                fireInstructionsLoaded(memoryFile, loadReason, triggerFilePath);
            }
        }
        return newlyLoaded;
    }

    /**
     * readFileState key 归一化 · 对齐 {@code ToolUseContext.keyForReadFileState} 的
     * {@code Path.toAbsolutePath().normalize()} 语义（CC path.normalize(key)，
     * fileStateCache.ts:42/46/51/55）。memoryFile.path 为绝对路径，幂等。
     */
    private static String readFileStateKey(String path) {
        return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString();
    }

    /**
     * 为单个触发文件加载 nested memory 并发射 · CC original:
     * {@code getNestedMemoryAttachmentsForFile}（attachments.ts:1792-1870）。
     *
     * <p><b>多阶段（处理序必须保持，attachments.ts:1795-1798 注释）</b>：
     * <ol>
     *   <li>Managed/User 条件规则（glob 匹配 targetPath）</li>
     *   <li>Nested 目录（CWD→target）：CLAUDE.md + 无条件 + 条件 rules</li>
     *   <li>CWD 级目录（root→CWD）：仅条件 rules（无条件 rules 已 eager 加载）</li>
     * </ol>
     * {@code processedPaths} 贯穿去重（CC :1804）。tengu_paper_halyard feature 门控
     * （attachments.ts:1823-1826 read + :1833-1835/:1850-1852 filter）经 {@link #paperHalyardGate}
     * 注入（默认 null → false，feature 关 = 不跳过 Project/Local）。
     *
     * @param filePath                触发文件路径
     * @param loadedNestedMemoryPaths 会话级已加载路径集合（会被修改）
     * @param readFileState           会话级 readFileState 缓存（注入后注册 + 双源去重，CLD-02）
     * @return 新加载的记忆文件列表
     */
    public List<MemoryFileInfo> getNestedMemoryAttachmentsForFile(String filePath,
                                                                  Set<String> loadedNestedMemoryPaths,
                                                                  com.nexusai.application.agent.tool.FileStateCache readFileState) {
        List<MemoryFileInfo> attachments = new ArrayList<>();
        try {
            // 早期返回：路径不在 allowed working paths 内（CC pathInAllowedWorkingPath → Java 等价判定，
            // 探查 △-14 / T-6 / OPD-CM5-F-07：原 pathInWorkingPath(originalCwd) 仅 cwd 包含，权限面弱于 CC）
            if (!pathInAllowedWorkingPath(filePath)) {
                return attachments;
            }
            Set<String> processedPaths = new LinkedHashSet<>();
            String originalCwd = originalCwdSupplier.get();
            // tengu_paper_halyard · CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_paper_halyard', false)（attachments.ts:1823-1826）
            boolean skipProjectLevel = paperHalyardGate != null && Boolean.TRUE.equals(paperHalyardGate.get());

            // Phase 1: Managed/User 条件规则（匹配 targetPath）
            attachments.addAll(memoryFilesToAttachments(
                getManagedAndUserConditionalRules(filePath, processedPaths),
                loadedNestedMemoryPaths, readFileState, filePath));

            // Phase 2: 目录计算（CWD→target 与 root→CWD）
            DirectoriesToProcess dirs = getDirectoriesToProcess(filePath, originalCwd);

            // Phase 3: nested 目录（CWD→target）：CLAUDE.md + 无条件 + 条件 rules
            for (String dir : dirs.nestedDirs()) {
                List<MemoryFileInfo> memoryFiles = getMemoryFilesForNestedDirectory(dir, filePath, processedPaths)
                    .stream()
                    .filter(f -> !skipProjectLevel
                        || (f.type() != ClaudemdMemoryType.PROJECT && f.type() != ClaudemdMemoryType.LOCAL))
                    .toList();
                attachments.addAll(memoryFilesToAttachments(memoryFiles, loadedNestedMemoryPaths, readFileState, filePath));
            }

            // Phase 4: CWD 级目录（root→CWD）：仅条件 rules
            for (String dir : dirs.cwdLevelDirs()) {
                List<MemoryFileInfo> conditionalRules = getConditionalRulesForCwdLevelDirectory(dir, filePath, processedPaths)
                    .stream()
                    .filter(f -> !skipProjectLevel
                        || (f.type() != ClaudemdMemoryType.PROJECT && f.type() != ClaudemdMemoryType.LOCAL))
                    .toList();
                attachments.addAll(memoryFilesToAttachments(conditionalRules, loadedNestedMemoryPaths, readFileState, filePath));
            }
        } catch (Exception e) {
            log.warn("[ClaudemdEngine] nested memory 加载失败（不阻断主路径，对齐 CC logError）: path={} err={}",
                filePath, e.getMessage());
        }
        return attachments;
    }

    /**
     * 消费 nestedMemoryAttachmentTriggers（触发集驱动，逐文件处理）· CC original:
     * {@code getNestedMemoryAttachments}（attachments.ts:2165-2190）。
     *
     * <p>先查触发集再取状态（CC 注释：check triggers first —— 常见情况是空触发集，快速返回）；
     * 逐触发文件调 {@link #getNestedMemoryAttachmentsForFile}；处理后清空触发集（CC :2186 clear）。
     *
     * @param triggers                nestedMemoryAttachmentTriggers 触发集（会被清空）
     * @param loadedNestedMemoryPaths 会话级已加载路径集合（会被修改）
     * @param readFileState           会话级 readFileState 缓存（注入后注册 + 双源去重，CLD-02）
     * @return 新加载的记忆文件列表（供接线：CLD-06 渲染 nested_memory 附件消息进 LLM 消息流）
     */
    public List<MemoryFileInfo> getNestedMemoryAttachments(Set<String> triggers,
                                                           Set<String> loadedNestedMemoryPaths,
                                                           com.nexusai.application.agent.tool.FileStateCache readFileState) {
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        List<MemoryFileInfo> attachments = new ArrayList<>();
        for (String filePath : triggers) {
            attachments.addAll(getNestedMemoryAttachmentsForFile(filePath, loadedNestedMemoryPaths, readFileState));
        }
        triggers.clear();
        return attachments;
    }

    /**
     * 目录遍历计算（CWD→target 与 root→CWD）· CC original:
     * {@code getDirectoriesToProcess}（attachments.ts:1656-1686）。
     *
     * <p>nestedDirs：target 目录向上走到 originalCwd（含 startsWith 判定）后反转 → 父→子序；
     * cwdLevelDirs：originalCwd 向上走到 root 后反转 → 根→CWD 序。
     */
    private DirectoriesToProcess getDirectoriesToProcess(String targetPath, String originalCwd) {
        String targetDir = Paths.get(targetPath).toAbsolutePath().normalize().getParent().toString();
        List<String> nestedDirs = new ArrayList<>();
        String currentDir = targetDir;
        while (!currentDir.equals(originalCwd) && !isPathRoot(currentDir)) {
            if (currentDir.startsWith(originalCwd)) {
                nestedDirs.add(currentDir);
            }
            currentDir = Paths.get(currentDir).toAbsolutePath().normalize().getParent().toString();
        }
        java.util.Collections.reverse(nestedDirs);

        List<String> cwdLevelDirs = new ArrayList<>();
        currentDir = originalCwd;
        while (!isPathRoot(currentDir)) {
            cwdLevelDirs.add(currentDir);
            currentDir = Paths.get(currentDir).toAbsolutePath().normalize().getParent().toString();
        }
        java.util.Collections.reverse(cwdLevelDirs);
        return new DirectoriesToProcess(nestedDirs, cwdLevelDirs);
    }

    private record DirectoriesToProcess(List<String> nestedDirs, List<String> cwdLevelDirs) {
    }

    /** 路径是否已到根（C:/ 或 /）· CC parse(currentDir).root 判定等价。 */
    private static boolean isPathRoot(String path) {
        Path p = Paths.get(path);
        return p.equals(p.getRoot()) || p.getParent() == null;
    }

    /** 跨平台相对路径（Windows 跨盘符 → null，CC relative() 返回绝对 → 拒绝语义）。 */
    private static String relativize(String baseDir, String targetPath) {
        try {
            Path base = Paths.get(baseDir);
            Path target = Paths.get(targetPath);
            if (base.getRoot() != null && target.getRoot() != null
                && !base.getRoot().equals(target.getRoot())) {
                return null; // 跨盘符 → 拒绝（CC Windows cross-drive relative() 返回绝对）
            }
            return base.relativize(target).toString().replace('\\', '/');
        } catch (Exception e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 路径助手 · config.ts:1779-1807 getMemoryPath/getManagedClaudeRulesDir/getUserClaudeRulesDir
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取指定类型记忆文件路径 · CC original: {@code getMemoryPath}（config.ts:1779-1799）。
     * <pre>
     * User    → join(getClaudeConfigHomeDir(), 'CLAUDE.md')（决策 D1/D3：改 NexusaiPaths
     *            自有根优先，nexusai 文件缺失时 claude 回落 → resolveFirstExisting）
     * Local   → join(cwd, 'CLAUDE.local.md')
     * Project → join(cwd, 'CLAUDE.md')
     * Managed → join(getManagedFilePath(), 'CLAUDE.md')
     * AutoMem → getAutoMemEntrypoint()
     * TeamMem → teamMemPaths.getTeamMemEntrypoint()
     * </pre>
     * 注：Project/Local 的 {@code cwd} 参数在 getMemoryFiles 中逐目录传 dir，本方法仅供
     * Managed/User/AutoMem 用（CC 亦如此 —— getMemoryPath('Project') 只在单目录场景使用）。
     */
    public String getMemoryPath(ClaudemdMemoryType type) {
        return switch (type) {
            // 决策 D1/D3：User memory 改 NexusaiPaths 自有根优先；nexusai 文件缺失时读取链
            //   claude 回落（读 ~/.claude/CLAUDE.md，CC 只读兼容）。
            case USER -> resolveFirstExisting(
                Paths.get(NexusaiPaths.getAppConfigHomeDir(), "CLAUDE.md"),
                Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "CLAUDE.md"));
            case LOCAL -> Paths.get(originalCwdSupplier.get(), "CLAUDE.local.md").toString();
            case PROJECT -> Paths.get(originalCwdSupplier.get(), "CLAUDE.md").toString();
            case MANAGED -> Paths.get(ClaudePaths.getManagedFilePath(), "CLAUDE.md").toString();
            case AUTO_MEM -> autoMemPaths.getAutoMemEntrypoint();
            case TEAM_MEM -> Paths.get(memoryFileDetection.getTeamMemPath(), "MEMORY.md").toString();
        };
    }

    /** Managed rules 目录 · CC original: {@code getManagedClaudeRulesDir}（config.ts:1801-1803）。 */
    public String getManagedClaudeRulesDir() {
        return Paths.get(ClaudePaths.getManagedFilePath(), ".claude", "rules").toString();
    }

    /** User rules 目录（nexusai 自有根）· CC original: {@code getUserClaudeRulesDir}（config.ts:1805-1807）。 */
    public String getUserClaudeRulesDir() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir(), "rules").toString();
    }

    /**
     * claude 用户 rules 目录（只读兼容回落源）· {@code ~/.claude/rules}（config.ts:1805-1807）。
     * 决策 D1/D3：User rules 为多文件按路径加载，两目录（nexusai 自有根 + claude）无 name
     * 冲突 → 读取链双目录加载。
     */
    public String getClaudeUserClaudeRulesDir() {
        return Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "rules").toString();
    }

    /** 首个已存在的文件路径（nexusai 自有根优先，claude 只读兼容回落）· 决策 D1/D3。 */
    private static String resolveFirstExisting(Path primary, Path fallback) {
        return Files.isRegularFile(primary) ? primary.toString() : fallback.toString();
    }

    /** --add-dir 附加目录（env 门控）· CC getAdditionalDirectoriesForClaudeMd（state.ts:206-207）。 */
    List<String> getAdditionalDirectoriesForClaudeMd() {
        return ClaudePaths.getAdditionalDirectoriesFromEnv();
    }

    private static boolean isEnvTruthy(String v) {
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1")
            || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on"));
    }

    /** 路径比较归一化（Windows drive letter 大小写 + 分隔符）· CC normalizePathForComparison。 */
    static String normalizeForComparison(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        if (IS_WINDOWS && p.length() >= 2 && p.charAt(1) == ':') {
            p = Character.toLowerCase(p.charAt(0)) + p.substring(1);
        }
        return p;
    }

    /**
     * child 是否在 base 内（同路径 true）· CC pathInWorkingPath（permissions/filesystem.ts）。
     * normalize + 相对路径不含 ../。
     */
    static boolean pathInWorkingPath(String child, String base) {
        if (child == null || base == null) {
            return false;
        }
        String rel = relativize(base, child);
        if (rel == null) {
            return false;
        }
        if (rel.isEmpty()) {
            return true;
        }
        if (rel.startsWith("..") || rel.contains("../")) {
            return false;
        }
        return !Paths.get(rel).isAbsolute();
    }

    /**
     * 路径是否在 allowed working paths 内 · CC {@code pathInAllowedWorkingPath}（permissions/filesystem.ts:683-707）。
     *
     * <p><b>WHY 存在</b>（探查 △-14 / T-6 / OPD-CM5-F-07）：CC 在 {@code getNestedMemoryAttachmentsForFile}
     * 早期返回用 {@code pathInAllowedWorkingPath(filePath, appState.toolPermissionContext)}（permission context
     * 全量判定：所有工作目录 = originalCwd + additionalWorkingDirectories），Java 原仅
     * {@code pathInWorkingPath(filePath, originalCwdSupplier.get())}（仅 cwd 包含判定）——cwd 内但不在允许路径
     * 的文件会被放行，权限面弱于 CC。web 无 toolPermissionContext 通道 → 以 originalCwd + 附加目录
     * （{@link #getAdditionalDirectoriesForClaudeMd()}，--add-dir env 门控）为 allowed working paths 全集；
     * filePath 命中任一工作目录即判定通过（对齐 CC every/some 语义，单路径场景归约为 some）。
     */
    boolean pathInAllowedWorkingPath(String filePath) {
        if (pathInWorkingPath(filePath, originalCwdSupplier.get())) {
            return true;
        }
        for (String dir : getAdditionalDirectoriesForClaudeMd()) {
            if (pathInWorkingPath(filePath, dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最近 git 根（含 .git 目录或文件的祖先）· CC findGitRoot（git.ts:27-86）。
     * 复用 AutoMemPaths.findCanonicalGitRoot 的 findGitRoot 内部逻辑简化实现。
     */
    static String findGitRoot(String startPath) {
        if (startPath == null || startPath.isEmpty()) {
            return null;
        }
        Path current = Paths.get(startPath).toAbsolutePath().normalize();
        while (current != null) {
            Path gitPath = current.resolve(".git");
            if (Files.exists(gitPath)
                && (Files.isDirectory(gitPath) || Files.isRegularFile(gitPath))) {
                return current.toString();
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // 输出契约：getLargeMemoryFiles / filterInjectedMemoryFiles / getClaudeMds
    //           isMemoryFilePath / getAllMemoryFilePaths / getExternalClaudeMdIncludes
    // ════════════════════════════════════════════════════════════════

    /**
     * 过滤超过 MAX_MEMORY_CHARACTER_COUNT 的大文件 · CC original: {@code getLargeMemoryFiles}
     * （claudemd.ts:1132-1134）{@code files.filter(f => f.content.length > 40000)}。
     */
    public List<MemoryFileInfo> getLargeMemoryFiles(List<MemoryFileInfo> files) {
        return files.stream()
            .filter(f -> f.content().length() > MAX_MEMORY_CHARACTER_COUNT)
            .toList();
    }

    /**
     * 外部 @include 项 · CC original: {@code ExternalClaudeMdInclude}（claudemd.ts:1399-1402）。
     *
     * @param path   外部文件绝对路径
     * @param parent 包含该文件的主文件路径
     */
    public record ExternalClaudeMdInclude(String path, String parent) {}

    /**
     * 收集外部 @include · CC original: {@code getExternalClaudeMdIncludes}
     * （claudemd.ts:1404-1414）。
     *
     * <p>非 User 类型 + 有 parent（@include 引入）+ 路径不在 originalCwd 内 → 外部 include。
     * User 类型排除：User memory 恒可 include 外部文件（claudemd.ts:833），不属"外部"审批范畴。
     *
     * @param files getMemoryFiles() 结果（含 @include 子文件）
     * @return 外部 include 列表
     */
    public List<ExternalClaudeMdInclude> getExternalClaudeMdIncludes(List<MemoryFileInfo> files) {
        List<ExternalClaudeMdInclude> externals = new ArrayList<>();
        for (MemoryFileInfo file : files) {
            if (file.type() != ClaudemdMemoryType.USER && file.parent() != null
                && !pathInWorkingPath(file.path(), originalCwdSupplier.get())) {
                externals.add(new ExternalClaudeMdInclude(file.path(), file.parent()));
            }
        }
        return externals;
    }

    /** 是否存在外部 @include · CC original: {@code hasExternalClaudeMdIncludes}（claudemd.ts:1416-1418）。 */
    public boolean hasExternalClaudeMdIncludes(List<MemoryFileInfo> files) {
        return !getExternalClaudeMdIncludes(files).isEmpty();
    }

    /**
     * 是否应显示外部 include 审批警告 · CC original: {@code shouldShowClaudeMdExternalIncludesWarning}
     * （claudemd.ts:1420-1430）。
     *
     * <p>接入审批态（OPD-CM5-F-09）：CC 先查 {@code hasClaudeMdExternalIncludesApproved ||
     * hasClaudeMdExternalIncludesWarningShown}，任一 true → false（claudemd.ts:1423-1426）；
     * 否则 {@code hasExternalClaudeMdIncludes(getMemoryFiles(true))}（:1428）。Java 经
     * {@link #setHasClaudeMdExternalIncludesApproved}/{@link #setHasClaudeMdExternalIncludesWarningShown}
     * 注入（未注入 → 恒 false，对齐 CC config 缺省）。forceIncludeExternal=true 仅用于审批检查，
     * 不构建 context（claudemd.ts:1047-1049）。
     *
     * @return true = 存在外部 include 且既未审批也未显示过警告
     */
    public boolean shouldShowClaudeMdExternalIncludesWarning() {
        boolean approved = hasClaudeMdExternalIncludesApproved != null
            && Boolean.TRUE.equals(hasClaudeMdExternalIncludesApproved.get());
        boolean warningShown = hasClaudeMdExternalIncludesWarningShown != null
            && Boolean.TRUE.equals(hasClaudeMdExternalIncludesWarningShown.get());
        if (approved || warningShown) {
            return false;
        }
        return hasExternalClaudeMdIncludes(getMemoryFiles(true));
    }

    /**
     * 过滤已注入记忆文件 · CC original: {@code filterInjectedMemoryFiles}
     * （claudemd.ts:1142-1151）。
     *
     * <p><b>WHY（tengu_moth_copse）</b>：feature 开启时 findRelevantMemories 预取经 attachments
     * 暴露记忆文件，MEMORY.md 索引不再注入 system prompt —— context builder 应过滤 AutoMem/TeamMem。
     * Java 无 GB feature flag → {@code mothCopseGate} 注入（默认 false，feature 关 = 原样返回）。
     *
     * @param files 待过滤记忆文件（getMemoryFiles() 结果）
     * @return mothCopse 开启 → 剔除 AutoMem/TeamMem；否则原样返回
     */
    public List<MemoryFileInfo> filterInjectedMemoryFiles(List<MemoryFileInfo> files) {
        boolean mothCopse = mothCopseGate != null && Boolean.TRUE.equals(mothCopseGate.get());
        if (!mothCopse) {
            return files;
        }
        List<MemoryFileInfo> filtered = files.stream()
            .filter(f -> f.type() != ClaudemdMemoryType.AUTO_MEM
                && f.type() != ClaudemdMemoryType.TEAM_MEM)
            .toList();
        if (log.isDebugEnabled()) {
            log.debug("[ClaudemdEngine] filterInjectedMemoryFiles: tengu_moth_copse 开启，AutoMem/TeamMem 已过滤 {} → {}",
                files.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * 渲染 getClaudeMds system prompt 段 · CC original: {@code getClaudeMds}
     * （claudemd.ts:1153-1195）。
     *
     * <p>契约逐字对齐：每条 {@code Contents of {path}{desc}:\n\n{trim}}，多条 {@code \n\n}
     * 连接；TeamMem 包 {@code <team-memory-content source="shared">}；无内容 → 返回空串；
     * 有内容 → 前缀 {@code MEMORY_INSTRUCTION_PROMPT\n\n}。
     *
     * <p><b>WHY（tengu_paper_halyard）</b>：feature 开 → 跳过 Project/Local（claudemd.ts:1165-1166）。
     * Java 本 feature 无预置 GB flag 访问器 → {@code paperHalyardGate} 注入（默认 false，
     * feature 关 = 注入全部；对齐 CC {@code getFeatureValue_CACHED_MAY_BE_STALE} 缺省）。
     *
     * @param memoryFiles 记忆文件列表（通常 getMemoryFiles() 结果）
     * @param filter      类型过滤（null = 不过滤）
     * @return 渲染文本或空串
     */
    public String getClaudeMds(List<MemoryFileInfo> memoryFiles,
                               java.util.function.Predicate<ClaudemdMemoryType> filter) {
        List<String> memories = new ArrayList<>();
        // tengu_paper_halyard · CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_paper_halyard', false)（claudemd.ts:1158-1161）
        boolean skipProjectLevel = paperHalyardGate != null && Boolean.TRUE.equals(paperHalyardGate.get());
        for (MemoryFileInfo file : memoryFiles) {
            if (filter != null && !filter.test(file.type())) {
                continue;
            }
            // CC claudemd.ts:1165-1166：skipProjectLevel && (Project || Local) → continue
            if (skipProjectLevel && (file.type() == ClaudemdMemoryType.PROJECT
                || file.type() == ClaudemdMemoryType.LOCAL)) {
                continue;
            }
            if (!file.content().isEmpty()) {
                String description = switch (file.type()) {
                    case PROJECT -> " (project instructions, checked into the codebase)";
                    case LOCAL -> " (user's private project instructions, not checked in)";
                    case TEAM_MEM -> " (shared team memory, synced across the organization)";
                    case AUTO_MEM -> " (user's auto-memory, persists across conversations)";
                    default -> " (user's private global instructions for all projects)";
                };
                String content = file.content().trim();
                if (file.type() == ClaudemdMemoryType.TEAM_MEM) {
                    memories.add("Contents of " + file.path() + description + ":\n\n"
                        + "<team-memory-content source=\"shared\">\n" + content
                        + "\n</team-memory-content>");
                } else {
                    memories.add("Contents of " + file.path() + description + ":\n\n" + content);
                }
            }
        }
        if (memories.isEmpty()) {
            return "";
        }
        return MEMORY_INSTRUCTION_PROMPT + "\n\n" + String.join("\n\n", memories);
    }

    /**
     * 判断路径是否为记忆文件（CLAUDE.md / CLAUDE.local.md / .claude/rules/*.md）· CC original:
     * {@code isMemoryFilePath}（claudemd.ts:1435-1452）。
     */
    public boolean isMemoryFilePath(String filePath) {
        if (filePath == null) {
            return false;
        }
        String name = Paths.get(filePath).getFileName().toString();
        if (name.equals("CLAUDE.md") || name.equals("CLAUDE.local.md")) {
            return true;
        }
        if (name.endsWith(".md")) {
            // CLD-05⑤：平台原生分隔符拼接（CC claudemd.ts:1446 `${sep}.claude${sep}rules${sep}`）——
            // Windows 上正斜杠输入不命中（旧实现先 replace('\\','/') 再查 → accept-more）。
            // 决策 D1/D6：.nexusai/rules/（NexusaiPaths.getProjectDirName() = .{appName}）与
            //   .claude/rules/ 等价（项目规则段，nexusai 复刻版 .claude 改造）
            String sep = java.io.File.separator;
            if (filePath.contains(sep + ".claude" + sep + "rules" + sep)
                    || filePath.contains(sep + NexusaiPaths.getProjectDirName() + sep + "rules" + sep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集全部记忆文件路径 · CC original: {@code getAllMemoryFilePaths}
     * （claudemd.ts:1460-1479）。
     *
     * @param files           getMemoryFiles() 结果（content 非空才计入）
     * @param readFileStateKeys readFileState 缓存的 key 集合（匹配记忆文件模式的计入）
     * @return 去重路径列表
     */
    public List<String> getAllMemoryFilePaths(List<MemoryFileInfo> files, List<String> readFileStateKeys) {
        Set<String> paths = new LinkedHashSet<>();
        for (MemoryFileInfo file : files) {
            if (!file.content().trim().isEmpty()) {
                paths.add(file.path());
            }
        }
        if (readFileStateKeys != null) {
            for (String filePath : readFileStateKeys) {
                if (isMemoryFilePath(filePath)) {
                    paths.add(filePath);
                }
            }
        }
        return new ArrayList<>(paths);
    }

}

package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.bash.BashCommandClassification;
import com.nexusai.application.agent.bash.BashCommandOperatorPermissions;
import com.nexusai.application.agent.bash.BashRuleMatcher;
import com.nexusai.application.agent.bash.BashModeValidation;
import com.nexusai.application.agent.bash.BashOutputUtils;
import com.nexusai.application.agent.bash.BashParser;
import com.nexusai.application.agent.bash.BashPathValidator;
import com.nexusai.application.agent.bash.ParseForSecurityResult;
import com.nexusai.application.agent.bash.BashHeredocExtractor;
import com.nexusai.application.agent.bash.BashSecurityValidator;
import com.nexusai.application.agent.bash.BashToolPrompt;
import com.nexusai.application.agent.bash.BashShellQuote;
import com.nexusai.application.agent.bash.ShellExecutor;
import com.nexusai.application.agent.bash.BashParser.Token;
import com.nexusai.application.agent.bash.BashParser.TokenKind;
import com.nexusai.application.agent.bash.CommandSemanticsInterpreter;
import com.nexusai.application.agent.config.GitInstructionConfig;
import com.nexusai.application.agent.bash.SedValidation;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskDecider;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.LocalBashTaskRunner;
import com.nexusai.application.agent.tasks.TaskIdGenerator;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.ShellError;
import com.nexusai.application.agent.tool.ToolLimits;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.EndTruncatingAccumulator;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.BashCommandClassifier;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.infra.util.BashClassifierPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.nexusai.application.agent.tool.ToolResultStorage.PersistedToolResult;

/**
 * Bash 工具 · 对齐 CC {@code tools/BashTool/} (BashTool.tsx + 18 helper 文件)。
 *
 * <h2>生产级安全</h2>
 * <ul>
 *   <li><b>危险命令权限 ask</b>：{@code rm -rf /}, {@code sudo}, {@code shutdown},
 *       {@code mkfs}, 命令替换/进程替换/注入等危险构造由 {@link #checkPermissions} 权限链
 *       （bashSecurity 恒 ask + path/read-only，对齐 CC bashSecurity.ts/bashPermissions.ts）
 *       判定 ask，用户批准后执行——execute 层不做硬阻断（G1 / DEL-TR-B1-01，对齐 CC）。</li>
 *   <li><b>超时</b>：默认 {@link BashToolPrompt#getDefaultTimeoutMs()}（env
 *       {@code BASH_DEFAULT_TIMEOUT_MS} 可配，缺省 120s）后 kill（避免 LLM 误调
 *       {@code sleep 9999} 挂死 loop）。</li>
 *   <li><b>per-input 并发判断</b>：委托 {@link BashParser#parseForReadOnly}（tokenizer
 *       walker + 只读 allowlist, fail-closed）——对齐 CC BashTool.isReadOnly
 *       (BashTool.tsx:437-442) + readOnlyValidation.ts。只读命令 → safe；写命令 /
 *       重定向 / substitution / 未引号展开 → 不 safe。</li>
 *   <li><b>输出截断</b>：formatOutput 30k 字符封顶（{@link BashOutputUtils#BASH_MAX_OUTPUT_DEFAULT}，
 *       对齐 CC getMaxOutputLength）+ 超阈值落盘持久化，无二次 50k 截断（G30① / DEL-TR-B1-02）。</li>
 * </ul>
 */
@Component
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** s04 PR 4: Sandbox 管理器（@Autowired(required=false) 向后兼容）。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.sandbox.SandboxManager sandboxManager;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent 埋点
     * （BashTool.tsx:683 trackGitOperations + :755 tengu_bash_tool_command_executed）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）；@Autowired(required=false) + setter
     * 注入（对齐 SubagentTool.analyticsTracker 同款短路语义）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
        if (log.isDebugEnabled()) {
            log.debug("[BashTool] [IMP-T G15] analyticsTracker 注入={}（CC tengu_bash_tool_command_executed / tengu_git_operation）",
                analyticsTracker != null);
        }
    }

    /**
     * [IMP-B1] Bash 系统提示词归因供应 · CC original: {@code getAttributionTexts()}
     * （utils/attribution.ts:74-98）返回 {@code {commit, pr}}。
     *
     * <p>{@link #prompt()} 生成 git 指令段时消费 {commit, pr} 归因文本。默认回退
     * {@link BashToolPrompt.Attribution#defaultTexts()}（CC 外部构建回退：Co-Authored-By: Claude Opus 4.6
     * + 🤖 Generated with Claude Code）；接入方可用 {@link #setBashAttributionSupplier} 注入真实模型名。
     * @Autowired(required=false) 无 bean 时默认回退（对齐 CC includeCoAuthoredBy 默认 true）。
     */
    @Autowired(required = false)
    private java.util.function.Supplier<BashToolPrompt.Attribution> bashAttributionSupplier;

    /** [IMP-B1] 归因供应注入（非 Spring 场景 / 测试）。 */
    public void setBashAttributionSupplier(
            java.util.function.Supplier<BashToolPrompt.Attribution> bashAttributionSupplier) {
        this.bashAttributionSupplier = bashAttributionSupplier;
    }

    /**
     * [WF-4 DEC-05] BASH_CLASSIFIER 特性 bean · 对齐 CC {@code feature('BASH_CLASSIFIER')}
     * (bashPermissions.ts:1429-1433) 单一门。读 {@code nexusai.feature.bash-classifier}
     * 配置（Spring 注入）；启用时 checkPermissions 对"待评估"命令返回
     * Ask + {@link PermissionResult.PendingClassifierCheck}（异步 classifier 检查路径才有载体）。
     * 缺省 false（缺省不改变既有 Passthrough 行为）。@Autowired(required=false) 无 bean 时
     * 由 {@link #setBashClassifierFeatureBean} 手动注入（测试场景）。
     *
     * <p>[DEL-WF4-02-04 / OPD-WF4-DEC-05] 统一所有 Bash 分类器消费点共用本单一门
     * （coordinatorHandler/interactiveHandler/swarmWorkerHandler/PermissionContext/
     * yoloClassifier/withRetry 均读 {@link BashClassifierFeature#isEnabled()}）；
     * 删除旧 Predicate 注入双轨（OR 门控），避免测试注入与生产配置双轨漂移。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.BashClassifierFeature bashClassifierFeatureBean;

    /**
     * [H14-FIX] classifier feature 开关 · 对齐 CC feature('BASH_CLASSIFIER') / isClassifierPermissionsEnabled.
     *
     * <p>WHY: H14 对抗核验发现全工程无一处 {@code new PermissionResult.PendingClassifierCheck}
     * (生产 Ask/Passthrough 全传 null) → coordinatorPendingCheck 在生产恒为死路径.
     * CC 由 BashTool.checkPermissions 经
     * buildPendingClassifierCheck(input.command, ctx) (bashPermissions.ts:1459/1762/1962/2029) 赋值.
     * Java 端 classifier 子系统由 {@link AutoModeGate} (nexusai.auto-mode.enabled) 门控 —
     * 本字段作为 Bash 侧等价门控.
     * null (未注入 bean) → 视为 disabled, 不构建结构体 (向后兼容 / 测试手动注入).
     */
    @Autowired(required = false)
    private AutoModeGate autoModeGate;

    /**
     * [Q-BS-4] Haiku/LLM classifyBashCommand 通道 · 对齐 CC bashClassifier.ts:40-49.
     *
     * <p>@Autowired(required=false) 无 bean 时回退 stub（matches=false，对齐 CC 外部构建 no-op）。
     * deny/ask 并行分类块（checkPermissions）经 {@link #classifyDenyAskParallel} 消费本字段。
     * 缺省 {@link BashCommandClassifierImpl} 恒 matches=false → deny/ask 块恒不触发（诚实对齐）。
     */
    @Autowired(required = false)
    private BashCommandClassifier bashCommandClassifier;

    /** [H14-FIX] TRANSCRIPT_CLASSIFIER feature · 对齐 CC feature('TRANSCRIPT_CLASSIFIER') (toolExecution.ts:1075). */
    @Value("${nexusai.classifier.transcript.enabled:true}")
    private boolean transcriptClassifierEnabled;

    /** Windows 平台判定 · 对齐 CC {@code getPlatform() === 'windows'}（G8 xargs UNC/SMB 数据桥防护用）。 */
    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Bash 输出落盘触发阈值（CC original: {@code getMaxOutputLength()} 默认
     * {@code BASH_MAX_OUTPUT_DEFAULT = 30000}，Open-ClaudeCode/src/utils/shell/outputLimits.ts:5）；
     * 完整输出超过该阈值 → spill 临时文件并持久化到 tool-results（对齐 CC BashTool.tsx:731-752）。
     * <p>与组装层 {@code getPersistenceThreshold("Bash", 30000)}=30000 一致，小输出（&lt;30k）不落盘。
     */
    private static final long SPILL_THRESHOLD = BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT;

    // ════════════════════════════════════════════════════════════════════
    // G5 组常量（超时语义 / 自动后台 / 进度轮询 / 图片上限 / 信号退出码）
    // ════════════════════════════════════════════════════════════════════

    /** G5-1/G5-9 信号退出码 · CC original: SIGTERM = 143（ShellCommand.ts:49-50）。超时 kill 后强制码。 */
    private static final int EXIT_SIGTERM = 143;

    /** G5-1/G5-9 信号退出码 · CC original: SIGKILL = 137（ShellCommand.ts:49-50）。size-kill / 强杀强制码。 */
    private static final int EXIT_SIGKILL = 137;

    /** G5-1/G5-9 信号杀进程退出码 · CC original: #exitHandler signal==='SIGTERM' ? 144 : 1（ShellCommand.ts:195-203）。 */
    private static final int EXIT_SIGNALED = 144;

    /** G5-2 assistant 主线程阻塞命令自动后台化阈值 · CC original: ASSISTANT_BLOCKING_BUDGET_MS（BashTool.tsx:57）= 15_000。 */
    private static final long ASSISTANT_BLOCKING_BUDGET_MS = 15_000L;

    /** G5-4 进度显示阈值 · CC original: PROGRESS_THRESHOLD_MS（BashTool.tsx:55）= 2_000（2s 前不显示进度）。 */
    private static final long PROGRESS_THRESHOLD_MS = 2_000L;

    /** G5-4 进度轮询间隔 · CC TaskOutput #tick 每秒一次（BashTool.tsx:1029 startPolling + TaskOutput.ts #tick）。 */
    private static final long PROGRESS_POLL_INTERVAL_MS = 1_000L;

    /** G5-7 图片数据大小上限 · CC original: MAX_IMAGE_FILE_SIZE（BashTool/utils.ts:96）= 20MB（API 只收 5MB base64）。 */
    private static final long MAX_IMAGE_FILE_SIZE = 20L * 1024L * 1024L;

    /** G5-8 成功时无 stdout 的命令 · CC original: BASH_SILENT_COMMANDS（BashTool.tsx:81）。 */
    private static final Set<String> BASH_SILENT_COMMANDS = Set.of(
        "mv", "cp", "rm", "mkdir", "rmdir", "chmod", "chown", "chgrp", "touch", "ln",
        "cd", "export", "unset", "wait");

    /** G5-8 语义中性命令 · CC original: BASH_SEMANTIC_NEUTRAL_COMMANDS（BashTool.tsx:77-78）。 */
    private static final Set<String> BASH_SEMANTIC_NEUTRAL_COMMANDS =
        Set.of("echo", "printf", "true", "false", ":");

    /** G5-2 CC DISALLOWED_AUTO_BACKGROUND_COMMANDS（BashTool.tsx:220-221）—— sleep 显式后台或前台。 */
    private static final Set<String> DISALLOWED_AUTO_BACKGROUND_COMMANDS = Set.of("sleep");

    /**
     * 命令语义解释器 · 对齐 CC {@code interpretCommandResult}（BashTool.tsx:690）消费。
     *
     * <p>注入真实 splitter {@link BashParser#splitCommands}（引号感知 + 顶层 {@code ;|&} 切段），
     * 对齐 CC {@code heuristicallyExtractBaseCommand}（commandSemantics.ts:112-119）取管道末段首词
     * 作为 base command。不得使用默认构造器（{@code List.of(s)} 不切管道，误装配产生错误管道语义）。
     */
    private final CommandSemanticsInterpreter commandSemantics =
        new CommandSemanticsInterpreter(BashParser::splitCommands);

    /**
     * Bash 命令操作符权限检查 · 对齐 CC tools/BashTool/bashCommandHelpers.ts。
     *
     * <p>A4 接线：subshell/命令组 → ask、管道段剥离重定向保引号、multi-cd/cd+git 守卫、
     * 逐段全量求值（deny 不早退）。{@link #checkPermissions} 在 mode 分支之后调用。
     */
    private final BashCommandOperatorPermissions operatorPermissions =
        new BashCommandOperatorPermissions();

    /**
     * CC CommandIdentityCheckers · 对齐 isNormalizedCdCommand / isNormalizedGitCommand
     * （bashPermissions.ts:2567-2611）。前缀匹配近似 CC stripSafeWrappers + shell-quote 首词解析。
     */
    private final BashCommandOperatorPermissions.CommandIdentityCheckers bashIdentityCheckers =
        new BashCommandOperatorPermissions.CommandIdentityCheckers(
            BashTool::isNormalizedCdCommand,
            BashTool::isNormalizedGitCommand);

    // IMP-B2（组 1-4 B）：删除 execute DANGEROUS 正则黑名单（TR-B1-⊕1，推翻 OPD-PERM-28）。
    //   CC 危险检测在 bashSecurity.ts/bashPermissions.ts（恒 ask + path/read-only），非字符串黑名单；
    //   危险命令经 checkPermissions 判定路径走 ask（bashSecurity.ts 恒 ask 零 deny），由用户审批，
    //   而非 execute 层硬阻断（EV-B1-D2/D3）。
    // IMP-B G1（DEL-TR-B1-01）：execute 层 parseForSecurity 硬阻断亦已删除——CC 真源
    //   parseForSecurity 仅用于 preparePermissionMatcher（BashTool.tsx:451），execute/call 路径不调用；
    //   命令替换/进程替换/eval 等危险类型由 checkPermissions AST 链（splitForSecurity fail-safe）
    //   + BashSecurityValidator（bashSecurity 恒 ask）承接，用户批准后正常执行。
    //   删除对象登记：06-deletion-manifest TR-B1-⊕1（已执行删除 2026-08-15）。

    @Override
    public String name() { return ToolNameConstants.BASH_TOOL_NAME; }

    /**
     * 结果落盘阈值 — 对齐 CC BashTool.tsx:424 {@code maxResultSizeChars: 30_000}。
     * 工具结果超过此阈值落盘到文件, 模型收到文件路径 + 预览。
     */
    @Override
    public long maxResultSizeChars() { return 30_000L; }

    /**
     * 严格模式 — 对齐 CC BashTool.tsx:425 {@code strict: true}。
     * 严格模式下 API 更严格遵循工具指令与参数 schema, 模型不可注入额外字段。
     */
    @Override
    public boolean strict() { return true; }

    /**
     * 工具描述 · 对齐 CC BashTool.description（BashTool.tsx:426-430）无 input 回退值
     * {@code 'Run shell command'}（CC {@code description || 'Run shell command'}）。
     *
     * <p>G10 对齐：旧描述含「50k/5k 截断」「120s 硬超时」等已删除/已改语义（G30①/G33②），
     * 替换为 CC 回退文案。输入感知描述见 {@link #description(JsonNode)}。
     */
    @Override
    public String description() {
        return "Run shell command";
    }

    /**
     * 输入感知描述 · 对齐 CC BashTool.description({description})（BashTool.tsx:426-430）：
     * {@code description || 'Run shell command'}（input.description 非空 → 返回之，否则 CC 回退）。
     *
     * @param input 工具输入（含 description 字段；null → 回退）
     * @return 输入描述或 CC 回退文案
     */
    @Override
    public String description(JsonNode input) {
        if (input != null) {
            String desc = input.path("description").asText("");
            if (!desc.isBlank()) {
                return desc;
            }
        }
        return "Run shell command";
    }

    /**
     * 工具系统提示词 · 对齐 CC {@code BashTool.tsx:432} {@code prompt() { return getSimplePrompt() }}。
     *
     * <p>P0-1（IMP-B1）：Java BashTool 原未 override {@link Tool#prompt()}（默认 null，Tool.java:627）→
     * LLM 看不到 Bash 使用规范 / git 安全协议 / sandbox 语义引导（EV-B1-006 / REQ-P0-1）。本 override
     * 完整移植 CC getSimplePrompt 369 行（{@code BashToolPrompt}），消费点
     * {@code ToolRegistry.toOpenAiToolsArray:459} {@code prompt() 非 null 优先于 description()}
     * （CC api.ts:171 同语义）注入 LLM 可见工具描述；{@link #description()} 仍供 ToolSearch 等用途。
     *
     * <p>动态值组装（对齐 CC 真源）：monitorTool=featureFlags.monitorTool()（sleep 子项分叉，
     * 同 validateInput 门控）；sandbox=SandboxManager.isEnabled()（启用态用 CC 默认限制配置，
     * 沙箱配置基础设施属 IMP-B2）；git=GitInstructionConfig.shouldIncludeGitInstructions()；
     * 归因=注入 supplier 或 CC 外部构建默认（Claude Opus 4.6 回退）。
     */
    @Override
    public String prompt() {
        BashToolPrompt.Attribution attr = bashAttributionSupplier != null
            ? bashAttributionSupplier.get()
            : BashToolPrompt.Attribution.defaultTexts();
        BashToolPrompt.SandboxState sandbox = BashToolPrompt.SandboxState.disabled();
        if (sandboxManager != null && sandboxManager.isEnabled()) {
            sandbox = BashToolPrompt.SandboxState.ccDefaults(true);
        }
        BashToolPrompt.PromptInputs inputs = new BashToolPrompt.PromptInputs(
            BashToolPrompt.getDefaultTimeoutMs(),
            BashToolPrompt.getMaxTimeoutMs(),
            featureFlags != null && featureFlags.monitorTool(),
            backgroundTasksDisabled,
            sandbox,
            GitInstructionConfig.shouldIncludeGitInstructions(),
            attr != null ? attr.commit() : null,
            attr != null ? attr.pr() : null);
        String prompt = BashToolPrompt.getSimplePrompt(inputs);
        if (log.isDebugEnabled()) {
            log.debug("BashTool.prompt(): 生成 CC 对齐 getSimplePrompt 系统提示词, 长度={} (git={}, sandbox={}, monitorTool={})",
                prompt.length(), inputs.gitInstructionsEnabled(), sandbox.enabled(), inputs.monitorToolEnabled());
        }
        return prompt;
    }

    /** CC BashTool.tsx:224-226 — isBackgroundTasksDisabled 对等开关 */
    private boolean backgroundTasksDisabled = false;

    /**
     * [G5-2] CC {@code feature('KAIROS')} 编译期门（BashTool.tsx:976）· Java 等价接入点 =
     * {@code nexusai.feature.kairos} 属性。默认 false —— 对齐 CC 外部构建 KAIROS 默认关
     * （assistant 自动后台化整链断链）。测试可经 {@link #setBackgroundTaskDecider} 注入 decider 覆写。
     */
    @Value("${nexusai.feature.kairos:false}")
    private boolean kairosEnabled;

    /** [G5-2] 注入式后台决策器（非 Spring 场景 / 测试覆写 kairos 门）· 构造自 {@link #newBackgroundTaskDecider()}。 */
    private BackgroundTaskDecider backgroundTaskDecider;

    /**
     * [G5-2] 后台决策器注入（非 Spring 场景 / 测试用）· 对齐 setBackgroundTaskRunner 模式。
     *
     * @param decider 后台决策器（null = 用字段构造 {@link #newBackgroundTaskDecider()}）
     */
    public void setBackgroundTaskDecider(BackgroundTaskDecider decider) {
        this.backgroundTaskDecider = decider;
    }

    /**
     * [G5-2] 构造后台决策器 · 对齐 CC BashTool.tsx:976 的 {@code feature('KAIROS') &&
     * getKairosActive() && isMainThread && !isBackgroundTasksDisabled && run_in_background !== true}。
     *
     * <p>{@code kairosActive}（CC getKairosActive()，会话级 assistant 模式激活）Java 端无等价
     * 会话标记，默认 false（对齐 CC bootstrap/state.ts:1085 默认 false）——生产 KAIROS 门关时
     * 整链断链，与 CC 外部构建一致；测试注入 decider 开启后验证 15s 自动后台。
     *
     * @return 后台决策器（backgroundTasksEnabled = !backgroundTasksDisabled）
     */
    BackgroundTaskDecider newBackgroundTaskDecider() {
        if (backgroundTaskDecider != null) {
            return backgroundTaskDecider;
        }
        return new BackgroundTaskDecider(!backgroundTasksDisabled, kairosEnabled, false);
    }

    /**
     * [G5-4] Bash 进度事件接收器 · CC original: {@code onProgress}（BashTool.tsx:663-677
     * ToolCallProgress{@code type:'bash_progress'}）。
     *
     * <p>生产接线：Spring 注入 {@link BashProgressPublisher}（{@code Consumer<BashProgress>} bean，
     * 把进度发射为 {@code tool_call_progress} STOMP 事件到会话流 topic {@code /topic/sessions/{sid}/stream}）。
     * 未注入（非 Spring 直构测试 / 无 bean 场景）→ null → 仅 log.debug 登记，不破坏既有调用。
     * @Autowired(required=false) 无 bean 不破坏既有调用（对齐 analyticsTracker 短路语义）。
     */
    @Autowired(required = false)
    private java.util.function.Consumer<BashProgress> bashProgressSink;

    /** [G5-4] 进度事件接收器注入（非 Spring 场景 / 测试）。 */
    public void setBashProgressSink(java.util.function.Consumer<BashProgress> sink) {
        this.bashProgressSink = sink;
    }

    /**
     * [monitor-rework] MONITOR_TOOL 门控 · 睡眠拦截 validateInput 用（CC BashTool.tsx:525
     * {@code feature('MONITOR_TOOL') && !isBackgroundTasksDisabled && !input.run_in_background}）。
     * required=false 容错：测试/无 bean 场景 → null → 拦截不触发（对齐 CC flag-off 行为）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /** [monitor-rework] 测试注入门控（非 Spring 场景）· 对齐 setBashClassifierFeatureBean 模式。 */
    public void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        this.featureFlags = featureFlags;
    }

    /** s13 后台任务执行器 (@Autowired required=false 向后兼容) */
    @Autowired(required = false)
    private BackgroundTaskRunner backgroundTaskRunner;

    /**
     * [WF-2A] 后台任务执行器测试注入（非 Spring 场景 / 测试用；AC-2 验证后台路径不更新 cwd）。
     *
     * @param runner 后台任务执行器（null = 关闭后台路径，回退前台）
     */
    public void setBackgroundTaskRunner(BackgroundTaskRunner runner) {
        this.backgroundTaskRunner = runner;
    }

    /**
     * [WF-4 DEC-05] BASH_CLASSIFIER 特性 bean 注入（非 Spring 场景 / 测试）。
     *
     * @param bashClassifierFeatureBean BASH_CLASSIFIER 特性 bean（null = 关闭）
     */
    public void setBashClassifierFeatureBean(
            com.nexusai.application.agent.permission.BashClassifierFeature bashClassifierFeatureBean) {
        this.bashClassifierFeatureBean = bashClassifierFeatureBean;
    }

    /** [WF-4 DEC-05] BASH_CLASSIFIER 启用判定 · 单一门 = BashClassifierFeature.isEnabled()。 */
    private boolean isBashClassifierEnabled() {
        return bashClassifierFeatureBean != null && bashClassifierFeatureBean.isEnabled();
    }

    /** 后台任务输出目录 · 批次4 #10：static final user.dir 常量 → {@link BackgroundTaskRunner#taskOutputPath} 方法调用
     *  （spawn 时算 sessionId，对齐 CC diskOutput.ts:50-55 getTaskOutputDir=join(getProjectTempDir(), getSessionId(), 'tasks')）。 */

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ObjectNode command = JSON.createObjectNode();
        command.put("type", "string");
        command.put("description", "Shell command to execute");
        properties.set("command", command);
        // CC BashTool.tsx:229 — timeout 字段（最大 getMaxTimeoutMs()，Java 默认
        // BashToolPrompt.getDefaultTimeoutMs()，env BASH_DEFAULT_TIMEOUT_MS 可配）
        ObjectNode timeout = JSON.createObjectNode();
        timeout.put("type", "integer");
        timeout.put("minimum", 1);
        timeout.put("description",
            "Optional timeout in milliseconds (overrides default " + BashToolPrompt.getDefaultTimeoutMs() + "ms). " +
            "Commands exceeding this will be killed.");
        properties.set("timeout", timeout);
        // CC BashTool.tsx:241 — run_in_background boolean 可选字段
        // CC BashTool.tsx:254-256 — isBackgroundTasksDisabled 时 conditional omit
        if (!backgroundTasksDisabled) {
            ObjectNode runInBg = JSON.createObjectNode();
            runInBg.put("type", "boolean");
            runInBg.put("description", "Set to true to run this command in the background. Use Read to read the output later.");
            properties.set("run_in_background", runInBg);
        }
        // CC BashTool.tsx inputSchema — description: 5k token 内的人类可读注释
        ObjectNode desc = JSON.createObjectNode();
        desc.put("type", "string");
        desc.put("description", "Clear, concise description of what this command does in 5-10 words. " +
            "Examples: 'List files in current directory', 'Check git status', 'Install dependencies'.");
        properties.set("description", desc);
        // CC BashTool.tsx:242 — dangerouslyDisableSandbox 字段（沙箱禁用 escape hatch）
        ObjectNode disableSandbox = JSON.createObjectNode();
        disableSandbox.put("type", "boolean");
        disableSandbox.put("description",
            "Set this to true to dangerously override sandbox mode and run commands without sandboxing. " +
            "Use only when the sandbox incorrectly blocks a legitimate command.");
        properties.set("dangerouslyDisableSandbox", disableSandbox);
        // [G5-5 返工] _simulatedSedEdit 不得出现在 model-facing inputSchema · 对齐 CC
        //   BashTool.tsx:249-259 z.omit（{@code fullInputSchema().omit({ _simulatedSedEdit: true })}，
        //   安全理由 :251-252：schema 暴露会让模型拿无害命令配对任意文件写绕过权限/沙箱）。
        //   Java 端不再声明该字段：
        //   1) schema 校验（StreamingToolExecutor:1324 safeParseSchema）用的是 strippedInput——
        //      InputSanitizer.stripInternalFields（:1297-1304）在执行前已剥 _simulatedSedEdit，
        //      故移除声明不会导致权限链注入的字段被 additionalProperties:false 拒收；
        //   2) 权限链注入（SedEditPermissionRequest 用户批准预览后经 updatedInput 重建
        //      effectiveCall，StreamingToolExecutor:1776/1812/1842）发生在 schema 校验<b>之后</b>，
        //      注入字段永不经过 safeParseSchema —— 声明在 schema 反而使其对模型可见。
        //   注：execute 开头仍消费 call.input()._simulatedSedEdit（applySimulatedSedEdit），
        //      与 InputSanitizer 剥离互不冲突（剥离仅作用于校验视图，execute 收原始 input）。
        //      不再声明 → 模型直接注入该字段在 ToolInputValidator 语义层即被拒（严格模式），
        //      与 CC strictObject + omit 的拒绝语义对齐。
        ArrayNode required = JSON.createArrayNode();
        required.add("command");
        root.set("required", required);
        // [H-WF2-01 WF2-X1 I-2c] 对齐 CC BashTool.tsx:227 z.strictObject → zodToJsonSchema 输出
        //   additionalProperties:false（未知键拒绝）。ToolInputValidator.safeParseSchema 门禁
        //   按本旗标拒绝未声明键（HookMatcherEngine.prepareContentMatcher safeParse）。
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 工具级语义验证 · 对齐 CC BashTool.tsx:524-538 validateInput — 睡眠拦截。
     *
     * <p><b>WHY（对齐 CC detectBlockedSleepPattern）</b>：裸 {@code Bash(sleep N)}（N≥2 首命令）是
     * "阻塞轮询"反模式——LLM 本应把长轮询交给 {@code run_in_background} 或 Monitor 工具，睡眠期间
     * 白白烧掉 turn。CC 在 validateInput 阶段拦截（errorCode 10），消息引导模型改用
     * {@code run_in_background: true} / Monitor 工具 / &lt;2s 延迟。门控与 MonitorTool 同 flag：
     * {@code feature('MONITOR_TOOL') && !isBackgroundTasksDisabled && !input.run_in_background}。
     *
     * <p>只拦<b>首命令</b>裸 sleep N≥2（{@link #detectBlockedSleepPattern}）——不误伤管道/脚本内 sleep
     * （{@code cat file | sleep 5} 首子命令是 cat）、浮点 sleep（{@code sleep 0.5} 是合法 pacing）。
     */
    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        boolean monitorToolGate = featureFlags != null && featureFlags.monitorTool();
        if (monitorToolGate && !backgroundTasksDisabled
                && !input.path("run_in_background").asBoolean(false)) {
            String command = input.path("command").asText("");
            String sleepPattern = detectBlockedSleepPattern(command);
            if (sleepPattern != null) {
                if (log.isDebugEnabled()) {
                    log.debug("BashTool 拦截阻塞 sleep 模式：{}（CC BashTool.tsx:322-337 + :530 message, errorCode 10）",
                        sleepPattern);
                }
                return ValidationResult.fail("10",
                    "Blocked: " + sleepPattern + ". Run blocking commands in the background with "
                        + "run_in_background: true — you'll get a completion notification when done. "
                        + "For streaming events (watching logs, polling APIs), use the Monitor tool. "
                        + "If you genuinely need a delay (rate limiting, deliberate pacing), keep it under 2 seconds.");
            }
        }
        return ValidationResult.pass();
    }

    /**
     * 检测被阻塞的 sleep 模式 · 逐字移植 CC BashTool.tsx:322-337 detectBlockedSleepPattern。
     *
     * <p>语义（CC 真源，grep -n 自验 BashTool.tsx:322-337 + commands.ts splitCommand_DEPRECATED）：
     * <ol>
     *   <li>{@code splitCommand_DEPRECATED} 切段 → 取第一子命令 {@code first}
     *       （Java 用 {@link BashParser#tokenize} — shell-quote 等价分词：连续 WORD 合并，
     *       {@code && || ; ;; | > >> >& <&} 及单 {@code &} 独立 OPERATOR token；
     *       first = 首个子命令（WORD 序列直到操作符），对齐 CC parts[0]）</li>
     *   <li>正则 {@code /^sleep\s+(\d+)\s*$/}（BashTool.tsx:328）— 仅整数 sleep N；
     *       浮点 sleep 0.5 合法（:327 注释 "Float durations ... are allowed — legit pacing, not polls"）</li>
     *   <li>{@code secs < 2} → null（:331 "sub-2s sleeps are fine"）</li>
     *   <li>rest = 首子命令后重组（BashTool.tsx:335 parts.slice(1).join(' ')）→ 对齐 CC：
     *       剔多字符操作符（filterControlOperators commands.ts:251-257，{@code && || ; ;; | > >> >&}，
     *       {@code sleep 10 && check} → rest=check 而非 "&& check"）；
     *       <b>保留单 {@code &}</b>（CC ALL_SUPPORTED_CONTROL_OPERATORS commands.ts:523-536 无单 &，
     *       {@code sleep 10 & check} → rest="& check"，R2 对齐）；
     *       剥重定向静态 target（splitCommand_DEPRECATED redirection-stripping commands.ts:268-363，
     *       {@code sleep 10 && check >> log.txt} → rest=check 而非 "check >> log.txt"，R1 对齐）；
     *       非空 → {@code sleep N followed by: rest}，空 → {@code standalone sleep N}（:333-336）</li>
     * </ol>
     *
     * @param command 原始 bash 命令（input.command）
     * @return 命中模式描述（非 null = 应拦截）；未命中 → null
     */
    static String detectBlockedSleepPattern(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        // 用 tokenize 精确分词（对齐 CC splitCommand_DEPRECATED 语义）：
        // shell-quote 把 && || ; ;; | > >> >& <& 及单 & 作为独立操作符 token，
        // 连续 WORD 合并为子命令。单 & 不在 CC ALL_SUPPORTED_CONTROL_OPERATORS
        // （commands.ts:523-536），故保留；多字符操作符被 filterControlOperators 剔除。
        List<Token> tokens = BashParser.tokenize(command);
        if (tokens.isEmpty()) {
            return null;
        }
        // first = 首个子命令（连续 WORD 合并，直到操作符/EOF）→ 对齐 CC parts[0]。
        // 例：sleep 10 && check → tokens=[WORD sleep, WORD 10, OP &&, WORD check] → first="sleep 10"
        // 记录 first 消费的 token 索引（rest 从 first 之后开始）
        StringBuilder firstBuf = new StringBuilder();
        int firstEndIndex = 0; // first 之后第一个 token 的索引（rest 起点）
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == TokenKind.WORD || t.kind() == TokenKind.STRING
                    || t.kind() == TokenKind.RAW_STRING || t.kind() == TokenKind.VARIABLE) {
                if (firstBuf.length() > 0) {
                    firstBuf.append(' ');
                }
                firstBuf.append(t.text());
                firstEndIndex = i + 1;
            } else if (t.kind() == TokenKind.OPERATOR || t.kind() == TokenKind.REDIRECT
                    || t.kind() == TokenKind.EOF || t.kind() == TokenKind.NEWLINE) {
                break; // 操作符结束首子命令
            }
        }
        String first = firstBuf.toString().trim();
        java.util.regex.Matcher m = SLEEP_PATTERN.matcher(first);
        if (!m.matches()) {
            return null;
        }
        int secs;
        try {
            secs = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool detectBlockedSleepPattern：sleep 时长解析失败（{}）→ 放行", m.group(1));
            }
            return null;
        }
        if (secs < 2) {
            return null; // sub-2s sleeps 合法（CC BashTool.tsx:331）
        }
        // rest = 首子命令之后的重组文本（对齐 CC parts.slice(1).join(' ')）：
        // 剔多字符操作符（&& || ; ;; ;& | > >> >& <&，对齐 CC filterControlOperators），
        // 保留单 &（CC ALL_SUPPORTED_CONTROL_OPERATORS 无单 &），剥重定向静态 target
        // （对齐 CC splitCommand_DEPRECATED redirection-stripping commands.ts:268-363）。
        // 例：sleep 10 && check >> log.txt → rest="check"（>> 与 log.txt 剥除）
        //     sleep 10 & check → rest="& check"（单 & 保留，对齐 CC）
        StringBuilder rest = new StringBuilder();
        boolean skipNext = false; // 剥重定向 target 后跳过下一个 WORD
        for (int ti = firstEndIndex; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            if (t.kind() == TokenKind.WHITESPACE || t.kind() == TokenKind.EOF) {
                continue;
            }
            if (skipNext) {
                skipNext = false; // 跳过重定向 target
                continue;
            }
            if (t.kind() == TokenKind.OPERATOR || t.kind() == TokenKind.REDIRECT) {
                String op = t.text();
                // 多字符操作符（CC ALL_SUPPORTED_CONTROL_OPERATORS + Java 重定向变体）→ 剔除
                if (CONTROL_OPERATOR_TOKENS.contains(op)) {
                    // 重定向操作符剥除其静态 target（对齐 CC isStaticRedirectTarget）
                    if (op.equals(">") || op.equals(">>") || op.equals(">&")
                            || op.equals("<") || op.equals("<<") || op.equals("<&")) {
                        skipNext = true;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool detectBlockedSleepPattern：剔除控制操作符 '{}'（对齐 CC filterControlOperators）", op);
                    }
                    continue;
                }
                // 单 &（CC ALL_SUPPORTED_CONTROL_OPERATORS 无单 &）→ 保留
                if (op.equals("&")) {
                    if (rest.length() > 0) {
                        rest.append(' ');
                    }
                    rest.append(op);
                    continue;
                }
                continue; // 其他操作符（;& 等）剔除
            }
            // WORD/STRING 等命令 token → 追加（前置已剥离重定向 target 的不追加）
            if (rest.length() > 0) {
                rest.append(' ');
            }
            rest.append(t.text());
        }
        return rest.isEmpty()
            ? "standalone sleep " + secs
            : "sleep " + secs + " followed by: " + rest;
    }

    /** CC BashTool.tsx:328 {@code /^sleep\s+(\d+)\s*$/} — 仅整数 sleep N（浮点放行）。 */
    private static final Pattern SLEEP_PATTERN = Pattern.compile("^sleep\\s+(\\d+)\\s*$");

    /**
     * 多字符控制操作符 token 集合 — rest 构建前剔除，对齐 CC commands.ts:523-536
     * {@code ALL_SUPPORTED_CONTROL_OPERATORS}（&& || ; ;; | > >& >>）。
     *
     * <p>CC filterControlOperators（commands.ts:251-257）从 {@code splitCommand_DEPRECATED} 输出
     * 剔除 {@code && || ; ;; | > >& >>}，故 {@code sleep 10 && check} → rest=check 而非 "&& check"。
     * Java 用 {@link BashParser#tokenize} 精确分词（对齐 CC shell-quote）：单 {@code &} 是独立
     * OPERATOR token 但不在 CC ALL_SUPPORTED_CONTROL_OPERATORS → 方法内单独保留（对齐 CC
     * {@code sleep 10 & check} → rest="& check"）。这里补 {@code ;&} / {@code < << <&} 变体
     * （BashParser.tryOperator 识别的重定向/后台操作符），剥重定向时 target 一并跳过。
     */
    private static final Set<String> CONTROL_OPERATOR_TOKENS = Set.of(
        "&&", "||", ";", ";;", ";&", "|", ">&", ">", ">>",  // CC commands.ts:523-536 + BashParser 变体
        "<", "<<", "<&", "<<<");                            // 重定向变体（BashParser.tryOperator）

    /** CC readOnlyValidation.ts:427 {@code /^[a-zA-Z]*e[a-zA-Z]*$/} — ps BSD 裸字母 e 修饰符（ps axe env 泄露）。 */
    private static final Pattern PS_BSD_E_MODIFIER = Pattern.compile("^[a-zA-Z]*e[a-zA-Z]*$");

    /**
     * CC 对齐：{@code Bash.isConcurrencySafe(input) === isReadOnly(input)}（BashTool.tsx:434-436）。
     * 只读命令（ls / cat / grep / find -type f 等）→ safe；写命令 → 不 safe。
     *
     * <p>委托 {@link #isReadOnly(JsonNode)} — 与 CC {@code this.isReadOnly?.(input) ?? false}
     * 同构；isReadOnly 走 {@link BashParser#parseForReadOnly(String)}（tokenizer walker,
     * 非 regex 简化版）。
     */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return isReadOnly(input);
    }

    /**
     * 是否只读 — 对齐 CC BashTool.isReadOnly (BashTool.tsx:437-442):
     * {@code checkReadOnlyConstraints(input) === 'allow'}。
     *
     * <p>委托 {@link BashParser#parseForReadOnly(String)} — tokenizer walker + 只读
     * allowlist + fail-closed（substitution / 未引号展开 / 重定向 / 非只读命令 → false）。
     * 7 helper (pathValidation/sedValidation/bashPermissions 等) 移 P2 后续批次。
     *
     * <p>[IMP-7 NG-1 DRIFT-3] 先跑 {@link BashSecurityValidator#check(String)}（对齐 CC
     * readOnlyValidation.ts:1893-1899 checkReadOnlyConstraints：先
     * {@code bashCommandIsSafe_DEPRECATED(command)}，{@code behavior !== 'passthrough'}（即 ask）
     * → 非只读，交权限检查）。反斜杠转义操作符（{@code cat safe.txt \; echo ~/.ssh/id_rsa}，
     * validateBackslashEscapedOperators 命中）会被 parseForReadOnly 把 {@code \;} 当 WORD 吞掉，
     * 使 {@code echo ~/.ssh/id_rsa}（读私钥）被误判只读 auto-allow 放行——正是 CC
     * bashSecurity.ts:1606-1618 注释描述的私钥泄露攻击面，须在此门禁阻断。
     */
    @Override
    public boolean isReadOnly(JsonNode input) {
        String cmd = input.path("command").asText("");
        // [IMP-7 NG-1 DRIFT-3] BashSecurity 校验器接入 read-only 路径 · 对齐 CC
        // readOnlyValidation.ts:1893-1899：bashCommandIsSafe_DEPRECATED(command).behavior
        // !== 'passthrough' → 非只读。Java check() 只有 passthrough/ask 两态（early-allow
        // 已转 passthrough），故 security.ask() 即 CC 的 "!== passthrough"。
        BashSecurityValidator.Result security = BashSecurityValidator.check(cmd);
        if (security.ask()) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool.isReadOnly: BashSecurity 校验器命中 checkId={} subId={} → 非只读: {}",
                    security.checkId(), security.subId(), abbreviate(cmd, 120));
            }
            return false;
        }
        // IMP-B2 RO-16/17（readOnlyValidation.ts:1719-1750 + 1911-1966）：git 只读守卫
        // （-c/--exec-path/--config-env 危险 flag + bare-repo/cd+git/git-internal/orig-cwd）
        // 命中 → 非只读（交权限链 ask，EV-B2-029 缺失项补齐）。cd+git 复合由 operator 层
        // 另行 ask（Check 4），此处主链兜底。
        if (gitReadOnlyGuardBlocked(cmd, null)) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool.isReadOnly: git 只读守卫命中 → 非只读（CC readOnlyValidation.ts RO-16/17）: {}",
                    abbreviate(cmd, 120));
            }
            return false;
        }
        return BashParser.parseForReadOnly(cmd);
    }

    /**
     * 自动分类器输入 · 对齐 CC {@code BashTool.tsx:442-444}
     * {@code toAutoClassifierInput(input) { return input.command }}。
     *
     * <p>[OPD-24 G1] 接线：Bash 是高安全相关工具，若未 override 会走
     * {@link Tool#toAutoClassifierInput(JsonNode)} 默认 {@code ''}（CC Tool.ts:767），
     * 消费侧 yoloClassifier.ts:411/:1021-1024 空串短路 ALLOW —— 即 auto-mode 对 Bash
     * 完全不分类，形成安全缺口（G6 阻断项）。本投影让分类器拿到原始命令文本。
     *
     * @param input 工具输入（含 {@code command}）
     * @return 命令文本；缺失 → {@code ''}（CC nullish 语义空串=无安全相关性）
     */
    @Override
    public String toAutoClassifierInput(JsonNode input) {
        String command = input == null ? "" : input.path("command").asText("");
        if (log.isDebugEnabled()) {
            log.debug("BashTool.toAutoClassifierInput: command 投影完成, 长度={} (CC BashTool.tsx:442 input.command)",
                command.length());
        }
        return command;
    }

    /**
     * 搜索提示 · 对齐 CC BashTool.tsx:422 {@code searchHint: 'execute shell commands'}（G9）。
     *
     * <p>供 ToolSearch 关键词匹配（3-10 词、无尾句号）。
     */
    @Override
    public String searchHint() {
        return "execute shell commands";
    }

    /**
     * 工具活动描述 · 对齐 CC BashTool.getActivityDescription（BashTool.tsx:517-523，G9）。
     *
     * <p>spinner 现在时描述：{@code Running <description|truncate(command,50)>}；无 command
     * → "Running command"（CC :519）。description 优先，缺省回退 truncate(command, 50)
     * （{@link ToolLimits#TOOL_SUMMARY_MAX_LENGTH}）。
     *
     * @param input 工具输入（含 command/description；null → "Running command"）
     * @return 活动描述
     */
    @Override
    public String getActivityDescription(JsonNode input) {
        String command = input == null ? "" : input.path("command").asText("");
        if (command.isBlank()) {
            return "Running command";
        }
        String desc = input.path("description").asText("");
        String display = desc.isBlank()
            ? truncate(command, (int) ToolLimits.TOOL_SUMMARY_MAX_LENGTH)
            : desc;
        return "Running " + display;
    }

    /**
     * 工具使用摘要 · 对齐 CC BashTool.getToolUseSummary（BashTool.tsx:504-516，B1-4 / G9）。
     *
     * <p>PreToolUse hook 摘要：优先 description（模型写的命令意图描述），缺省回退
     * {@code truncate(command, TOOL_SUMMARY_MAX_LENGTH)}（CC toolLimits.ts:56 = 50）。
     *
     * @param processedInput 工具输入（Map 形式，含 command/description）
     * @return 摘要文本；无 command → null（对齐 CC :505-507）
     */
    @Override
    public String getToolUseSummary(java.util.Map<String, Object> processedInput) {
        Object commandObj = processedInput == null ? null : processedInput.get("command");
        if (commandObj == null || commandObj.toString().isBlank()) {
            return null;
        }
        Object descObj = processedInput == null ? null : processedInput.get("description");
        if (descObj != null && !descObj.toString().isBlank()) {
            return descObj.toString();
        }
        return truncate(commandObj.toString(), (int) ToolLimits.TOOL_SUMMARY_MAX_LENGTH);
    }

    /**
     * 用户可见工具名 · 对齐 CC BashTool.userFacingName（BashTool.tsx:484-503，G9）。
     *
     * <p>CC 为 input-aware（input.command 含 sed 就地编辑 → FileEdit 显示名；沙箱 indicator
     * env → "SandboxedBash"）；Java {@link Tool} 接口仅有无参 {@code userFacingName()}，无法承接
     * input 分支，返回 CC 无 input 兜底值 "Bash"（与 {@link #name()} 一致）。沙箱 indicator /
     * sed-edit 分支登记 Java-idiom 偏离（接口无 input 签名）。
     */
    @Override
    public String userFacingName() {
        return "Bash";
    }

    /**
     * [G2] 搜索/读取分类 · 对齐 CC {@code BashTool.tsx:469-477 isSearchOrReadCommand}
     * （{@code inputSchema().safeParse(input)} → {@code isSearchOrReadBashCommand(command)}）。
     * Java 端 command 参数直接读 {@code input.path("command")}（execute 同款解析）；分类算法
     * port {@link BashCommandClassification#classify}（CC BashTool.tsx:95-172 三命令集 +
     * 操作符切分 + 语义中性 + 整条不折叠陷阱）。
     *
     * @param input LLM 给的参数（含 command 字段）
     * @return 4 态分类（Bash 折叠消费方用，对齐 collapseReadSearch.ts:220 isCollapsible）
     */
    @Override
    public SearchReadKind searchReadKind(JsonNode input) {
        String command = input.path("command").asText("");
        BashCommandClassification.SearchReadClassification c = BashCommandClassification.classify(command);
        if (c.isSearch()) {
            return SearchReadKind.IS_SEARCH;
        }
        if (c.isRead()) {
            return SearchReadKind.IS_READ;
        }
        if (c.isList()) {
            return SearchReadKind.IS_LIST;
        }
        return SearchReadKind.NONE;
    }

    /**
     * hook if 内容匹配器 · CC original: {@code preparePermissionMatcher}
     * （{@code BashTool.tsx:445-468}，{@code (pattern) => boolean}）。
     *
     * <p>WHY: hook if 过滤是「不匹配 → 跳过 hook」的 deny 语义，复合命令
     * （如 {@code ls && git push}）必须<b>任一子命令</b>命中才触发 {@code Bash(git *)}
     * 安全 hook（BashTool.tsx:448-450 注释原文）。CC 用 tree-sitter 解析成 subcommands
     * （argv 拼接），匹配规则：
     * <ol>
     *   <li>{@code parseForSecurity} 非 simple（too-complex / parse-unavailable）→ fail-safe
     *       返回 true（运行 hook，BashTool.tsx:451-455）。[FIX-EX-A 移植] Java 用
     *       {@link BashParser#splitForSecurity} 三态：failSafe 触发条件 (DIV-1..4 + REF-A)——
     *       进程替换、裸命令替换参数位、非引号定界 heredoc {@code <<EOF}、定界符同行结构、
     *       双引号 solo {@code $()}（占位符绕过下游路径校验）。删除旧粗粒度
     *       containsCommandSubstitution/containsProcessSubstitution 无条件 fail-safe
     *       （无法区分裸参数位 vs 双引号/赋值内提取 → 引号内 $() 误 fail-safe over-fire）。</li>
     *   <li>{@link BashParser#splitForSecurity} 拆分外层子命令 + 双引号/赋值内 $() 提取的
     *       内层子命令（CC collectCommandSubstitution → 独立 subcommand 逐条匹配, DIV-4）</li>
     *   <li>{@code permissionRuleExtractPrefix(pattern)} 命中 {@code :*} → 前缀语义:
     *       {@code cmd === prefix || cmd.startsWith(prefix + ' ')}；否则
     *       {@code matchWildcardPattern(pattern, cmd)}（锚定全匹配，含精确/通配）</li>
     * </ol>
     * argv 剥离前置 {@code VAR=val}（对齐 CC tree-sitter envVars 分离，BashTool.tsx:456-458）。
     *
     * @param input 工具输入（含 {@code command} 字段）
     * @return 内容匹配谓词；无命令/空命令时匹配任意 pattern（ruleContent 非空也不命中，
     *         交给调用方 ruleContent 空→true 语义）
     */
    @Override
    public Predicate<String> preparePermissionMatcher(JsonNode input) {
        String command = input == null ? null : input.path("command").asText(null);
        if (command == null || command.isBlank()) {
            return pattern -> false;
        }
        // [P0-1] 改用 G4 parseForSecurity（CC BashTool.tsx:451-468 真源）——不再走 splitForSecurity
        //   文本拆分。kind != 'simple'（too-complex / parse-unavailable）→ fail-safe 运行 hook
        //   （BashTool.tsx:451-455）。kind === 'simple' → 用 argv.join(' ') 匹配规则
        //   （argv 已剥离前置 VAR=val · BashTool.tsx:456-458；命令替换内层已提取为独立
        //   SimpleCommand → 逐条匹配等价 CC subcommands 列表）。
        ParseForSecurityResult parsed = BashParser.parseForSecurity(command);
        if (!(parsed instanceof ParseForSecurityResult.Simple simple)) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool.preparePermissionMatcher: 命令无法静态分析 (G4 non-simple) → fail-safe 运行 hook: {}",
                    abbreviate(command, 120));
            }
            return pattern -> true;
        }
        java.util.List<String> subcommands = new java.util.ArrayList<>();
        for (ParseForSecurityResult.BashSimpleCommand cmd : simple.commands()) {
            String joined = String.join(" ", cmd.argv()).trim();
            if (!joined.isEmpty()) {
                subcommands.add(joined);
            }
        }
        return rulePattern -> {
            String trimmed = rulePattern == null ? "" : rulePattern.trim();
            String prefix = BashRuleMatcher.extractLegacyPrefix(trimmed);
            for (String sub : subcommands) {
                if (matchesSubcommand(prefix, trimmed, sub)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** 单子命令匹配: {@code :*} 前缀 (cmd === prefix || cmd.startsWith(prefix + ' ')) 或通配全匹配
     *  （CC BashTool.tsx:459-466）。 */
    private static boolean matchesSubcommand(String prefix, String pattern, String sub) {
        if (prefix != null) {
            return sub.equals(prefix) || sub.startsWith(prefix + " ");
        }
        return BashRuleMatcher.matchWildcardPattern(pattern, sub);
    }

    /**
     * P0-2: 递归剥离前导 {@code !} 否定运算符 · 对齐 CC ast.ts:567-577 negated_command
     * （递归剥 {@code !} 产出真实 argv）。{@code ! rm -rf /} → {@code rm -rf /}。
     *
     * <p>SECURITY：权限匹配路径（deny/ask/exact）必须先剥 {@code !} 再匹配 —— 否则
     * {@code Bash(rm:*)} deny 规则被 {@code ! rm -rf /} 绕过（legacy 前缀匹配看命令原文
     * 不以 {@code rm} 开头 → 不命中 → 用户 deny 失效、命令真实执行）。
     *
     * <p>边界：仅剥离<b>独立 {@code !} token</b>（后随空白或串尾，bash 保留字否定符）；
     * {@code !foo}（词内叹号）、{@code !=}（比较）不剥。无变化时返回原串。
     *
     * <p>注：G4 parseForSecurity（P0-3 negated_command）已在 argv 层剥 {@code !}；本方法是
     * legacy 前缀匹配路径的双保险（checkEarlyExitDeny / checkSemanticsDeny / matchingDenyOrAskRule
     * 的输入命令）。原 {@code stripLeadingEnvVars} 已删除 —— P0-1 接 G4 后 argv 已剥离前置 env vars。
     *
     * @param cmd 命令串（可为 null）
     * @return 剥 {@code !} 后的命令串；无 {@code !} 前缀 → 原串
     */
    static String stripNegationPrefix(String cmd) {
        if (cmd == null) {
            return null;
        }
        String s = cmd;
        boolean changed = false;
        for (;;) {
            String t = s.trim();
            if (!t.startsWith("!")) {
                return changed ? t : cmd;
            }
            if (t.length() > 1 && !Character.isWhitespace(t.charAt(1))) {
                // "!foo" / "!=x"：叹号是词的一部分，非否定运算符
                return changed ? t : cmd;
            }
            if (t.length() == 1) {
                // 裸 "!"：无实际命令 → 空串（上层按无命令处理）
                return "";
            }
            s = t.substring(1);
            changed = true;
        }
    }

    /**
     * [G2] tool_result 块 · 对齐 CC {@code BashTool.tsx:555-623 mapToolResultToToolResultBlockParam}
     * （成功路径被调 toolExecution.ts:1292）。
     *
     * <p><b>Java 端 data 已在 execute 层完成 CC 同款组装</b>（BashTool.java:455-509）:
     * processedStdout 去前导空行 + trimEnd（CC BashTool.tsx:571-603）；持久化落盘时
     * data = buildLargeToolResultMessage 预览（CC BashTool.tsx:604-612 persistedOutputPath 分支）；
     * interrupted → isError 透传（CC BashTool.tsx:617-623 {@code is_error: interrupted}）。
     * structuredContent / image 通道 Java 端分别走 data 泛型 / {@code ToolResult.image} 短路
     * （BashTool.java:564），不进本 mapper。
     *
     * @param result 工具执行结果（data 为已格式化的 stdout 文本 / 持久化预览）
     * @return tool_result 块（tool_use_id/type/content/is_error）
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (result == null) {
            return null;
        }
        String content = result.data() == null ? "" : String.valueOf(result.data());
        if (log.isDebugEnabled()) {
            log.debug("BashTool.mapToToolResultBlockParam: id={} isError={} contentLen={}（CC BashTool.tsx:617-623）",
                toolUseId, isError, content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * execute + 运行时上下文 · 对齐 CC {@code Tool.call(input, toolUseContext, ...)}（Tool.ts:379-385）。
     * <p>E4 / OPD-32：call 内实现 Bash 输出持久化（对齐 CC BashTool.tsx:731-752）——
     * 完整输出超 {@link #SPILL_THRESHOLD}（30k）时捕获全部输出并持久化到
     * {workspaceDir}/{sessionId}/tool-results/{call.id()}.txt，输出 data = &lt;persisted-output&gt; 预览消息 +
     * structuredOutput 携带 persistedOutputPath/persistedOutputSize；持久化失败降级保留截断 stdout。
     * {@code ctx == null}（dispatch 兼容路径）时跳过持久化（无 sessionId/workspaceDir），不抛错。
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        String command = call.input().path("command").asText("");
        String description = call.input().path("description").asText("");
        boolean disableSandbox = call.input().path("dangerouslyDisableSandbox").asBoolean(false);

        // G5-5: _simulatedSedEdit 直接写文件（不跑 sed）· 对齐 CC BashTool.tsx:627-629 call 入口
        //   （权限弹窗预览批准后把 sed 编辑结果直接写文件，保证"预览即所见"）。内部字段，模型不可见
        //   （CC :249-259 omit），由 SedEditPermissionRequest 注入。
        JsonNode simulatedSedEdit = call.input().path("_simulatedSedEdit");
        if (!simulatedSedEdit.isMissingNode() && !simulatedSedEdit.isNull()) {
            String sedFilePath = simulatedSedEdit.path("filePath").asText("");
            String sedNewContent = simulatedSedEdit.path("newContent").asText("");
            return applySimulatedSedEdit(call.id(), sedFilePath, sedNewContent, ctx);
        }
        if (command.isBlank()) {
            return ToolResult.error(call.id(), "command is empty");
        }

        // IMP-B G1（DEL-TR-B1-01）：删除 execute 层 parseForSecurity 硬阻断。
        //   CC 真源 parseForSecurity 仅用于 preparePermissionMatcher（BashTool.tsx:451），
        //   execute/call 路径不调用；危险命令判定归 checkPermissions（bashSecurity 恒 ask +
        //   path/read-only），用户批准后正常执行（对齐 CC）。命令替换/进程替换/eval 等
        //   危险构造由 checkPermissions AST 决策链（splitForSecurity fail-safe）+ 3.3
        //   BashSecurityValidator 承接，execute 不再二次拦截。
        // IMP-DEL1：删除 missing-description log.warn（TR-B1-⊕-5，CC BashTool.tsx 无此警告；
        // description 仅用于下方数据流日志，空描述不再单独告警）。
        log.info("BashTool: $ {} [desc={}]", abbreviate(command, 200),
            description.isBlank() ? "(none)" : abbreviate(description, 80));

        // ── CC BashTool.tsx:989 — 路径 1: 显式 run_in_background ──
        boolean runInBackground = call.input().path("run_in_background").asBoolean(false);
        if (runInBackground && !backgroundTasksDisabled && backgroundTaskRunner != null) {
            // Phase 4 (cron-notify): 透传创建会话 sessionId（ctx.sessionId()，可靠源 —— 本工具在
            // tool-exec 池线程执行，MDC 无值）→ BackgroundTaskRunner.spawn → 完成通知注入创建会话回合。
            String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
            return executeBackground(command, call.id(), sessionId);
        }

        // E4: stdout/stderr 捕获载体（reader 线程写入，finally 清理 spill 临时文件）
        OutputCapture[] stdoutCap = new OutputCapture[1];
        OutputCapture[] stderrCap = new OutputCapture[1];
        Path combinedSpill = null;
        // [WF-2A] cd 追踪临时文件（外层声明，供 finally 清理）。
        Path cwdTrackFile = null;
        // G5-2/G5-1: 前台任务登记 + 后台化状态（G1-2 registerForeground / backgroundExistingForegroundTask）
        String foregroundTaskId = null;
        LocalBashTaskRunner foregroundRunner = null;
        String backgroundTaskId = null;       // G5-1/G5-2: 转后台后任务 ID（非 null = 命令仍在跑）
        boolean interrupted = false;               // G5-9: 被信号/强杀中断标记（CC interrupted = code===SIGKILL）
        boolean timedOutKilled = false;            // G5-1/G5-9: 超时 kill 标记（→ 143 + stderr 前缀）
        int forcedExitCode = -1;                   // G5-1/G5-9: 超时/强杀后强制退出码（143/137）
        String timeoutStderrPrefix = null;         // G5-1: "Command timed out after Xs" stderr 前缀
        // G5-2/G5-1: 后台化唤醒标记（15s 定时器置 true，wait 循环据此返回后台化结果）· 用数组 holder
        //   供 15s 定时器 lambda 捕获写入（lambda 只能捕获 effectively-final，数组引用本身 final）。
        final boolean[] backgroundedFlag = {false};
        // G5-2: assistant 主线程 15s 自动后台标记（CC BashTool.tsx:980）· 定时器 lambda 写入，数组 holder。
        final boolean[] assistantAutoBackgrounded = {false};
        final boolean isMainThread = ctx == null || ctx.agentId() == null; // CC isMainThread（BashTool.tsx:642 !context.agentId）
        // G5-10: 沙箱 ghost 文件清理载体（CC Shell.ts:391-393 cleanupAfterCommand 等价）——
        //   spawn 前快照 cwd 0 字节点文件，命令结束后删新出现的（bwrap 挂载点占位防写非存在路径）。
        java.util.Set<String> sandboxGhostBefore = null;

        try {
            // ── [WF-2A · DEL-08] cd 持久化 · 对齐 CC Shell.ts:380-470 cd tracking 全机制 ──
            // CC 真源（自验，不信注释）：bashProvider.ts:184-187 把命令包成
            //   `source ... && eval ${quotedCommand} && pwd -P >| $cwdFilePath`
            // Shell.ts:385-421 跑完 readFileSync 读回 newCwd，NFC 比对，变化时 setCwd +
            // invalidateSessionEnvCache + onCwdChangedForHooks；仅前台 !backgroundTaskId；
            // preventCwdChanges 不更新；realpath+NFC 归一化（setCwd Shell.ts:447-464）。
            // Java 等价：spawn 前解析会话 cwd 作 pb.directory（对齐 CC spawn cwd=getCwd()）；
            // 命令尾部追加 pwd/cd 重定向到临时文件；跑完读回 → NFC 比对 → SessionCwdHolder.set
            // （内部 realpath+NFC，对齐 setCwdState + setCwd realpathSync）。
            String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
            String sessionCwd = CwdResolution.getCwd(sessionId);

            // G2-4: spawn cwd 校验与回退 · 对齐 CC Shell.ts:218-238（realpath(cwd) ENOENT → 回落
            // getOriginalCwd()；启动目录也不存在 → null → 按 CC :234-236 文案生成错误）。
            // null/blank 会话 cwd（dispatch 兼容路径无会话绑定）→ 原样透传（bash 三参不设 directory
            // → 子进程回落 user.dir，对齐旧行为），不触发"目录不存在"错误。
            String resolvedCwd = (sessionCwd != null && !sessionCwd.isBlank())
                ? ShellExecutor.resolveSpawnCwd(sessionCwd)
                : sessionCwd;
            if (sessionCwd != null && !sessionCwd.isBlank() && resolvedCwd == null) {
                return ToolResult.error(call.id(),
                    "Working directory \"" + sessionCwd + "\" no longer exists. "
                    + "Please restart Claude from an existing directory.");
            }

            // G2-1: 会话级快照（懒生成一次，bashProvider.ts:63-70）· 快照消失 → 回退 login shell
            Path snapshot = ShellExecutor.getOrCreateSnapshot().orElse(null);
            if (snapshot != null && !ShellExecutor.isSnapshotValid(snapshot)) {
                snapshot = null;
            }

            // 临时文件承载命令后 pwd 输出（对齐 CC nativeCwdFilePath；避免污染 stdout）。
            // preventCwdChanges：Java 前台主线程等价 preventCwdChanges=false（对齐 CC isMainThread）；
            // 子代理（agentId 非 null）CC preventCwdChanges=true → 不追踪 cwd。
            String wrappedCommand = command;
            try {
                cwdTrackFile = Files.createTempFile("bash-cwd-", ".tmp");
                // 统一 bash 语法（对齐 CC bashProvider.ts:184-187 eval quotedCommand && pwd -P）：
                //   ShellExecutor.wrapForCwdTracking 内部完成单引号转义（隔离用户命令，处理尾随
                //   ; & | # 等边界）+ trackFile Windows→POSIX（/c/...，对齐 bashProvider.ts:118-121
                //   shellTmpdir 转换）；pwd -P 仅在命令成功时执行（对齐 CC `&&` join；cd+fail 不更新
                //   为已知 CC 行为），输出 POSIX 路径（/c/...），读回时转 native（Shell.ts:400-402）。
                //   G2-1 快照存在 → wrapForExec 前置 `source <snapshot> 2>/dev/null || true`（对齐
                //   bashProvider.ts:156-187 命令链），否则走 wrapForCwdTracking。
                wrappedCommand = snapshot != null
                    ? ShellExecutor.wrapForExec(command, cwdTrackFile, snapshot)
                    : ShellExecutor.wrapForCwdTracking(command, cwdTrackFile);
            } catch (Exception cwdEx) {
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: cd 追踪临时文件创建失败，降级不追踪 cwd: {}", cwdEx.toString());
                }
                cwdTrackFile = null;
            }

            // ── 执行器：统一 bash/zsh（对齐 CC findSuitableShell Shell.ts:73-137）──
            // ShellExecutor.bash 内部 resolveShell()（Windows 走 Git Bash bash.exe，非 Windows 探测
            // bash/zsh）找不到 → 抛 IllegalStateException（CC 同款 "requires a Posix shell environment"
            // 错误，fail-loud，绝不静默 cmd.exe/chcp 兜底）→ 外层 catch 转 ToolResult.error。
            // G2-1: 三参 bash（快照存在 → 跳过 -l；无快照 → -l login shell，对齐 CC skipLoginShell
            //   bashProvider.ts:200-206）。
            // CC BashTool.tsx:229 — timeout 字段覆盖默认（BashToolPrompt.getDefaultTimeoutMs()，
            // env BASH_DEFAULT_TIMEOUT_MS 可配 · G33②）
            long timeoutMs = call.input().has("timeout")
                ? call.input().path("timeout").asLong(BashToolPrompt.getDefaultTimeoutMs())
                : BashToolPrompt.getDefaultTimeoutMs();

            // ── G5-10: 沙箱执行判定 + 命令包装 · 对齐 CC shouldUseSandbox（shouldUseSandbox.ts:130-153，
            //   Java 端 SandboxManager.shouldUseSandbox 全量实现：isEnabled 三闸 + dangerouslyDisableSandbox
            //   + excludedCommands 前缀/exact/wildcard）──
            // [G5-10 返工] 判定必须在 pb 构造前执行：命中 → 先把命令串 bwrap 包装（Shell.ts:259-266
            //   wrapWithSandbox），再构造 pb（外层仍是 <bash> -c <bwrap…>），随后注入沙箱 env
            //   （bashProvider.ts:235-247，含宿主 TMPDIR mkdir 0o700 Shell.ts:267-272）。
            //   旧实现仅注入 env 不包装命令串 → 命令未沙箱化，与 checkPermissions 沙箱 auto-allow
            //   （"沙箱内执行"即 auto-allow）构成 fail-open 安全洞。
            boolean useSandbox = sandboxManager != null && sandboxManager.shouldUseSandbox("Bash", call.input());
            if (useSandbox && log.isDebugEnabled()) {
                log.debug("BashTool: 沙箱执行接线 useSandbox=true command={}（G5-10, shouldUseSandbox.ts:130-153）",
                    abbreviate(command, 80));
            }
            if (useSandbox) {
                wrappedCommand = ShellExecutor.wrapWithSandbox(wrappedCommand, ShellExecutor.resolveShell(), resolvedCwd);
            }
            ProcessBuilder pb = ShellExecutor.bash(wrappedCommand, resolvedCwd, snapshot);
            // [P1-3] 合并 stdout/stderr 单流（redirectErrorStream(true)，等价 ShellExecutor.bashMerged）·
            //   对齐 CC 文件模式 Shell.ts:289-313（stdio[1]/stdio[2] 同 fd O_APPEND 原子交错）→
            //   result.stderr 恒空（ShellCommand.ts:301）。旧分离流（redirectErrorStream(false)）已弃。
            pb.redirectErrorStream(true);
            if (useSandbox) {
                // 宿主侧建 TMPDIR(0o700) + 注入 TMPDIR/CLAUDE_CODE_TMPDIR（Shell.ts:267-272 + bashProvider.ts:235-247）
                ShellExecutor.applySandboxExecEnv(pb);
                // G5-10: spawn 前快照 cwd 0 字节点文件（bwrap ghost 清理对比基准，Shell.ts:391-393）
                sandboxGhostBefore = snapshotSandboxGhostFiles(resolvedCwd);
            }

            Process process = pb.start();

            // G5-4: 命令启动时间戳 · CC original: {@code startTime = Date.now()}（BashTool.tsx:1004，
            //   runShellCommand 入口即记）—— 进度 {@code elapsedTimeSeconds} 相对【命令启动】时刻，
            //   非阈值后首 tick 时刻（旧实现 poller run() 内自取，偏小 ~2s）。
            long commandStartTime = System.currentTimeMillis();

            // G5-4: 进度累加器（reader 线程逐块喂入，poller 尾部轮询读增量）· 对齐 CC TaskOutput
            //   stdoutToFile 尾部轮询（TaskOutput.ts #tick 每秒一次）。
            ProgressAccumulator progressAccum = new ProgressAccumulator();

            // 异步读合并 stdout/stderr · E4: 流式捕获完整输出，超 SPILL_THRESHOLD(30k) 时 spill 到临时文件
            //（对齐 CC ShellCommand 大输出写 outputFilePath；内存仅留前 30k preview，
            //  移除旧 50k/5k 内存截断上限 → 模型可经 FileRead 读全量）。
            // [P1-3] redirectErrorStream(true) 单流 = stdout+stderr 时间交错序 → 只读 getInputStream()，
            //   getErrorStream() 空流（CC 文件模式 result.stderr 恒空）。
            Thread stdoutReader = new Thread(() -> {
                try {
                    stdoutCap[0] = captureOutput(process.getInputStream(),
                        (int) SPILL_THRESHOLD, SPILL_THRESHOLD, progressAccum::append);
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool stdout 捕获失败: {}", e.toString());
                    }
                }
            }, "bash-stdout");
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            // ── G5-2: 前台任务登记（G1-2 registerForeground）+ 15s 自动后台定时器 ──
            // shouldAutoBackground = CC isAutobackgroundingAllowed（BashTool.tsx:307-315，sleep 除外）
            //   && !isBackgroundTasksDisabled —— 仅门控【超时转后台】路径（:1324 canBackground）。
            // autoBgEligible = G1-3 isAssistantAutoBackgroundEligible（feature('KAIROS') &&
            //   getKairosActive() && isMainThread && !disabled && !run_in_background，BashTool.tsx:976）。
            // [P1-1 返工] 15s assistant 定时器【不】受 isAutobackgroundingAllowed 门控 —— CC 真源
            //   BashTool.tsx:976 定时器只 gate KAIROS/mainThread/!disabled/!run_in_background，不查
            //   DISALLOWED_AUTO_BACKGROUND_COMMANDS。故 sleep 30 等在 assistant 主线程也会 15s 自动
            //   后台（对齐 CC）。前台任务登记需在 shouldAutoBackground 或 autoBgEligible 任一成立时执行
            //   （15s 定时器 backgroundExistingForegroundTask 依赖已登记的 foregroundTaskId）。
            boolean autoBgEligible = newBackgroundTaskDecider()
                .isAssistantAutoBackgroundEligible(isMainThread, runInBackground);
            boolean shouldAutoBackground = !backgroundTasksDisabled && isAutobackgroundingAllowed(command);
            if ((shouldAutoBackground || autoBgEligible) && backgroundTaskRunner != null) {
                String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
                String outputFile = BackgroundTaskRunner.taskOutputPath(taskId);
                try {
                    Files.createDirectories(Path.of(outputFile).getParent());
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool: 前台任务输出目录创建失败: {}", e.toString());
                    }
                }
                BackgroundTask fgTask = new BackgroundTask(
                    taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
                    abbreviate(command, 100), call.id(),
                    System.currentTimeMillis(), null, null, outputFile, 0L, false,
                    null, false).withSessionId(sessionId);
                foregroundRunner = new LocalBashTaskRunner();
                // G5-1/G5-2 桥接：adoptForegroundProcess 让 BackgroundTaskRunner 的
                //   backgroundExistingForegroundTask 守卫（runner.isProcessAlive）能看到本前台 Process。
                foregroundRunner.adoptForegroundProcess(process);
                foregroundTaskId = backgroundTaskRunner.registerForeground(fgTask, foregroundRunner);
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: 前台任务已登记 taskId={}（G1-2 registerForeground, LocalShellTask.tsx:259-287）",
                        foregroundTaskId);
                }

                // G5-2: 15s 自动后台定时器 · 仅 G1-3 全门通过才启动（对齐 CC BashTool.tsx:976-982）。
                if (autoBgEligible) {
                    String fgId = foregroundTaskId;
                    Thread autoBgTimer = new Thread(() -> {
                        try {
                            Thread.sleep(ASSISTANT_BLOCKING_BUDGET_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (!process.isAlive() || backgroundedFlag[0]) {
                            return;
                        }
                        if (backgroundTaskRunner.backgroundExistingForegroundTask(fgId)) {
                            assistantAutoBackgrounded[0] = true;
                            backgroundedFlag[0] = true; // 唤醒 wait 循环返回后台化结果
                            log.info("BashTool: assistant 主线程阻塞命令超预算自动后台化 taskId={} "
                                    + "（G5-2, BashTool.tsx:976-982 ASSISTANT_BLOCKING_BUDGET_MS={}s）",
                                fgId, ASSISTANT_BLOCKING_BUDGET_MS / 1000);
                        }
                    }, "bash-auto-background-timer");
                    autoBgTimer.setDaemon(true);
                    autoBgTimer.start();
                }
            }

            // G5-4: 进度轮询线程（PROGRESS_THRESHOLD_MS=2s 前不显示；超阈值后每秒 tick 发增量）
            //   sessionId/toolCallId/commandStartTime 注入：STOMP 路由 + CC elapsed 基准（BashTool.tsx:1004）。
            ProgressPoller poller = new ProgressPoller(process, progressAccum,
                sessionId, call.id(), commandStartTime);
            poller.start();

            // ── G5-1/G5-9: 等待 + 超时（轮询 wait + backgroundedFlag 唤醒）──
            // CC 语义：#handleTimeout（ShellCommand.ts:135-141）→ shouldAutoBackground 且已注册
            //   onTimeout 回调 → 转后台（进程不杀，返回 backgroundTaskId）；否则 #doKill(SIGTERM)
            //   → exit code 143 + stderr 前缀 "Command timed out after <formatDuration>"（:323-328）。
            // [manual-bg 修复] 外部后台化感知: 用户手动转后台(Ctrl+B/前端按钮经 TaskController→
            //   backgroundExistingForegroundTask)翻转任务 isBackgrounded,但 BashTool 局部 backgroundedFlag
            //   仅自动路径(15s/超时)置 —— 若不查任务后台化状态,execute 会一直等进程自然结束,agent 循环
            //   卡住(对齐 CC BashTool.tsx:1090-1102 前台 progress loop 查 shellCommand.status==='backgrounded')。
            //   foregroundTaskId 非 effectively final(1090 初始化 + 1263 赋值) → 复制到 final 供 lambda 捕获。
            final String fgTaskIdForAwait = foregroundTaskId;
            int await = awaitForegroundProcess(process, timeoutMs, backgroundedFlag,
                () -> fgTaskIdForAwait != null && backgroundTaskRunner != null
                    && backgroundTaskRunner.getTask(fgTaskIdForAwait)
                        .map(t -> t.isBackgrounded()).orElse(false));

            if (backgroundedFlag[0]) {
                // G5-2: 15s 定时器已就地转后台（backgroundExistingForegroundTask 成功）→ 返回后台化结果
                backgroundTaskId = foregroundTaskId;
                startBackgroundedCompletionWatcher(process, foregroundRunner, foregroundTaskId,
                    stdoutCap, stderrCap, resolvedCwd, sandboxGhostBefore);
                poller.stop();
                String outputFile = taskOutputFileOf(foregroundTaskId);
                log.info("BashTool: 命令已转后台 taskId={} assistantAutoBackgrounded={}（G5-2）",
                    backgroundTaskId, assistantAutoBackgrounded[0]);
                return buildBackgroundedResult(call.id(), backgroundTaskId, outputFile,
                    assistantAutoBackgrounded[0], false);
            }
            if (await == AWAIT_INTERRUPTED) {
                // G5-9: 中断 → SIGKILL 强杀 + interrupted=true（对齐 CC #abortHandler → kill()
                //   → #doKill(SIGKILL)，interrupted = code===SIGKILL ShellCommand.ts:302）。
                interrupted = true;
                forcedExitCode = EXIT_SIGKILL;
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: 命令被中断 → SIGKILL 强杀 pid={}（G5-9, interrupted=137）",
                        process.pid());
                }
                ShellExecutor.killProcessTreeSafely(process);
                if (foregroundTaskId != null) {
                    // [BUG2-EVENT] 中断强杀 → 补发 KILLED 终态（前端 status 收尾）再注销
                    backgroundTaskRunner.emitForegroundTerminal(foregroundTaskId,
                        BackgroundTaskStatus.KILLED);
                    backgroundTaskRunner.unregisterForeground(foregroundTaskId);
                }
            } else if (await == AWAIT_TIMEOUT) {
                // 超时：可自动后台 → 就地转后台（进程不杀，返回 backgroundTaskId）
                boolean canBackground = shouldAutoBackground && foregroundTaskId != null && process.isAlive();
                if (canBackground) {
                    boolean bg = backgroundTaskRunner.backgroundExistingForegroundTask(foregroundTaskId);
                    if (bg) {
                        backgroundTaskId = foregroundTaskId;
                        startBackgroundedCompletionWatcher(process, foregroundRunner, foregroundTaskId,
                            stdoutCap, stderrCap, resolvedCwd, sandboxGhostBefore);
                        poller.stop();
                        String outputFile = taskOutputFileOf(foregroundTaskId);
                        log.info("BashTool: 命令超时转后台运行 taskId={}（G5-1, #handleTimeout 转后台分支）",
                            backgroundTaskId);
                        return buildBackgroundedResult(call.id(), backgroundTaskId, outputFile,
                            false, false);
                    }
                }
                // 不可后台 → SIGTERM → 5s → SIGKILL；exit code 143；stderr 前缀（对齐 CC #doKill(SIGTERM)）
                timedOutKilled = true;
                forcedExitCode = EXIT_SIGTERM;
                timeoutStderrPrefix = "Command timed out after " + ShellExecutor.formatDuration(timeoutMs);
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: 命令超时 kill（SIGTERM 语义）pid={} {}（G5-1, ShellCommand.ts:323-328）",
                        process.pid(), timeoutStderrPrefix);
                }
                ShellExecutor.killProcessTreeSafely(process);
                if (foregroundTaskId != null) {
                    // [BUG2-EVENT] 超时 kill → 补发 FAILED 终态（前端 status 收尾）再注销
                    backgroundTaskRunner.emitForegroundTerminal(foregroundTaskId,
                        BackgroundTaskStatus.FAILED);
                    backgroundTaskRunner.unregisterForeground(foregroundTaskId);
                }
            }

            // 等 reader 线程读完（超时/中断 kill 路径 reader 已被进程 EOF 自然结束）
            stdoutReader.join(2000);
            poller.stop();

            // G5-10: 前台命令结束后清理沙箱 ghost 文件（CC Shell.ts:391-393 cleanupAfterCommand 等价；
            //   仅删"运行期间新出现的 0 字节点文件"——bwrap 挂载点占位，绝不删命令前已存在的）
            if (sandboxGhostBefore != null) {
                cleanupSandboxGhostFiles(resolvedCwd, sandboxGhostBefore);
            }

            // G5-1/G5-9: 信号退出码语义（对齐 ShellCommand.ts:195-203 #exitHandler：超时 kill → 143；
            //   中断强杀 → 137；自然退出 → resolveNaturalExitCode()（无退出码 → 144，CC signal 分支））
            int exitCode = forcedExitCode >= 0 ? forcedExitCode : resolveNaturalExitCode(process);
            OutputCapture stdoutOut = stdoutCap[0] != null ? stdoutCap[0]
                : new OutputCapture("", 0, null);
            OutputCapture stderrOut = stderrCap[0] != null ? stderrCap[0]
                : new OutputCapture("", 0, null);
            String out = stdoutOut.preview();
            String err = stderrOut.preview();
            // [P1-3] 合并单流：stderr 恒空（getErrorStream() 空流），err 不再拼接；
            //   timeout/abort 前缀折入合并流 out（对齐 CC 文件模式 —— 前缀写 stderr fd，与 stdout 同 fd
            //   时间交错进 outputFilePath，模型在合并流看到）。
            if (timeoutStderrPrefix != null) {
                // 对齐 CC prependStderr（ShellCommand.ts:56-58 + :323-328）：前缀 + 原合并输出
                out = out.isEmpty() ? timeoutStderrPrefix : timeoutStderrPrefix + " " + out;
            }
            if (interrupted) {
                // CC mapToolResultToToolResultBlockParam（BashTool.tsx:601-605）：
                //   interrupted → errorMessage 尾部追加 "<error>Command was aborted before completion</error>"
                String abortMark = "<error>Command was aborted before completion</error>";
                out = out.isEmpty() ? abortMark : out + "\n" + abortMark;
            }

            // 自然完成且前台任务未转后台 → 注销（对齐 CC unregisterForeground LocalShellTask.tsx:491-514）
            if (foregroundTaskId != null && backgroundTaskId == null) {
                // [BUG2-EVENT] 自然完成 → 补发终态（exitCode 语义：0 → completed，否则 failed）再注销
                backgroundTaskRunner.emitForegroundTerminal(foregroundTaskId,
                    exitCode == 0 ? BackgroundTaskStatus.COMPLETED : BackgroundTaskStatus.FAILED);
                backgroundTaskRunner.unregisterForeground(foregroundTaskId);
            }

            // ── [WF-2A · CC-CWD-06] 前台命令跑完读回 cwd 更新 SessionCwdHolder ──
            // 对齐 CC Shell.ts:395-414：仅前台命令（executeBackground 路径不经此）；
            // readFileSync 读回 newCwd → NFC 比对（newCwd.normalize('NFC') !== cwd）→
            // 变化时 setCwd（realpath+NFC）。Java 等价：读临时文件 → NFC 比对 sessionCwd
            // → 变化时 SessionCwdHolder.set（内部 realpath+NFC，对齐 setCwdState）。
            // sessionId=null（dispatch 兼容路径）跳过持久化（无会话载体，对齐 CC 无 STATE）。
            if (cwdTrackFile != null && sessionId != null) {
                try {
                    // readCwdTracked 读回 trim + Windows 内做 POSIX→native 转换（Shell.ts:400-402）
                    String newCwd = ShellExecutor.readCwdTracked(cwdTrackFile, IS_WINDOWS);
                    if (!newCwd.isBlank()) {
                        String normalizedNew = Normalizer.normalize(newCwd, Normalizer.Form.NFC);
                        if (!normalizedNew.equals(sessionCwd)) {
                            SessionCwdHolder.set(sessionId, newCwd);
                            if (log.isDebugEnabled()) {
                                log.debug("[BashTool] cd 持久化: sessionId {} {} -> {}",
                                    sessionId, sessionCwd, newCwd);
                            }
                        }
                    }
                } catch (Exception trackEx) {
                    // 对齐 CC Shell.ts:411-413 catch 兜底：读回失败记 debug 不抛（命令可能 pwd 前失败）。
                    if (log.isDebugEnabled()) {
                        log.debug("[BashTool] cd 追踪读回失败（命令可能于 pwd 前失败）: {}", trackEx.toString());
                    }
                }
            }

            // ── G5-6: claude-code-hint 旁路标签剥离 · 对齐 CC BashTool.tsx:774-784 + claudeCodeHints.ts:72-120 ──
            //   CLI/SDK 在 CLAUDECODE=1 下向 stderr 发射 `<claude-code-hint />`（合并进 stdout）。
            //   剥离无条件执行（子代理输出必须干净，:779）；主线程 hints 非空才登记（:782-783，Java 端
            //   log.debug 登记，无 maybeRecordPluginHint UI 通道）。
            //   [G5-3 返工] 剥离在 interpretExitCodeResult<b>之前</b>执行 → 最终成功文本不含 hint 标签
            //   （对齐 CC :780 strippedStdout → :803 data.stdout）；错误路径（ShellError throw）保留
            //   rawOut 原始 stdout（对齐 CC outputWithSbFailures :710，未经 hints 剥离）。
            String rawOut = out;   // 错误路径 ShellError 用原始 stdout
            HintsResult hintExtract = extractClaudeCodeHints(out, command);
            if (!hintExtract.hints().isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: 剥离 claude-code-hint 标签 {} 个（G5-6, claudeCodeHints.ts:72-120）",
                        hintExtract.hints().size());
                }
                if (isMainThread) {
                    for (java.util.Map<String, String> hint : hintExtract.hints()) {
                        log.info("BashTool: claude-code-hint 登记（CC maybeRecordPluginHint 等价）{}", hint);
                    }
                }
            }
            out = hintExtract.stripped();

            // ── 退出码语义解释 · 对齐 CC BashTool.tsx:690 interpretCommandResult 消费 ──
            // DEL-A2-01：删除硬编码「非 0 退出码 → error」分支。grep 1（no matches）/ rg 1 /
            // diff 1（files differ）/ find 1（partial success）/ test 1（condition false）由
            // CommandSemanticsInterpreter 正确表达为非 error（commandSemantics.ts:31-89）。
            // [G5-3 返工] 语义解释 + 错误检查在 image 检测（:785）<b>之前</b>执行（对齐 CC：
            //   interpretCommandResult :690 → ShellError throw :714-719 → isImage :785），
            //   失败命令输出 data URI 时不产 image。
            BashExitCodeResult baseResult =
                interpretExitCodeResult(call.id(), command, exitCode, out, err);

            // ── [IMP-T G15] 命令执行遥测 · 对齐 CC BashTool.tsx:683 + :755-761 ──
            // trackGitOperations（:683，exitCode==0 才检测 git/gh/glab/curl PR，错误路径亦先 track，
            //   对齐 CC :683 在 :714 throw 之前）+ tengu_bash_tool_command_executed（:755，仅非错误
            //   路径 —— CC :718 ShellError throw 跳过；image 早退路径不入此块为受控遥测 gap）。
            //   stdout_length 用 rawOut（CC :757 stdout.length 为未剥离合并输出）。
            emitBashCommandExecutedTelemetry(command, exitCode, rawOut, err, baseResult);

            // ── G5-3: ShellError 接线 · 对齐 CC BashTool.tsx:714-719 ──
            //   interpretationResult.isError && !isInterrupt → `throw new ShellError('',
            //   outputWithSbFailures, result.code, result.interrupted)`（:714-718）。isError 由
            //   CommandSemanticsInterpreter 判定（grep 1 / diff 1 / test 1 等非 error 不抛）。
            //   StreamingToolExecutor catch(Throwable) → ToolErrorFormatter.formatError → getErrorParts
            //   渲染 "Exit code N" + interrupted 标记 + stderr + stdout（toolErrors.ts:24-32）。
            //   interrupted（G5-9 信号/强杀）按 CC isInterrupt 不抛（:714），走下方文本输出
            //   （mapToolResultToToolResultBlockParam is_error=interrupted 语义）。
            //   [G5-3 返工] 错误检查必须在 isImage（:785）之前：失败命令输出 data URI 时 CC 抛
            //   ShellError（错误路径），Java 不再被 maybeBuildImageResult 图片短路抢先（顺序对齐）。
            if (baseResult.isError() && !interrupted) {
                // CC merged fd 下 stdout 传 '' 防 getErrorParts 重复（toolErrors.ts:28-31 分区已有 stderr 段）；
                //   Java 分离流场景经集成验证保留真实 stdout 不产生重复（toolErrors.ts:26-32 分区语义对齐）。
                //   error 路径用 rawOut（未经 hints 剥离，对齐 CC outputWithSbFailures :710）。
                throw new ShellError(rawOut, err, exitCode, interrupted);
            }
            if (interrupted) {
                // G5-9: 中断结果文本输出（对齐 CC BashTool.tsx:601-605 errorMessage + is_error: interrupted；
                //   :780 hints 剥离亦作用于中断路径 → 用已剥离 out）
                String combinedOut = out + (err.isEmpty() ? "" : "\n" + err);
                return ToolResult.error(call.id(), BashOutputUtils.stripEmptyLines(combinedOut));
            }

            // ── G5-8: noOutputExpected · 对齐 CC BashTool.tsx:809 + :178-217 isSilentBashCommand ──
            //   前端显示 "Done" 而非 "(No output)"。Java 输出为文本 data（无 output schema 字段分发），
            //   持久化 structuredOutput 携带该字段，其余路径 log.debug 登记。
            boolean noOutputExpected = isSilentBashCommand(command);
            if (log.isDebugEnabled()) {
                log.debug("BashTool: noOutputExpected={} command={}（G5-8, BashTool.tsx:809 isSilentBashCommand）",
                    noOutputExpected, abbreviate(command, 80));
            }

            // ── 输出格式化接线 · 对齐 CC BashTool.tsx:772-785 ──
            // strippedStdout = stripEmptyLines(stdout) → isImageOutput(strippedStdout)。
            // Java 主链：stdout 先 stripEmptyLines → isImageOutput（前缀锚+换行边界）→
            // image 走 ToolResult.image（buildImageToolResult 拆 mediaType + base64）；
            // 否则交持久化块返回文本 data。
            // [G5-3 返工] image 检测在错误检查之后（对齐 CC :785 在 :714 throw 之后）。
            ToolResult<JsonNode> imageResult = maybeBuildImageResult(call.id(), out, stdoutOut);
            if (imageResult != null) {
                return imageResult;
            }

            // ── Bash 输出持久化 · 对齐 CC BashTool.tsx:731-752 ──
            // 完整输出（stdout+stderr 合计）超 SPILL_THRESHOLD → persistOutputFile 落盘
            // {workspaceDir}/{sessionId}/tool-results/{call.id()}.txt（>64MB 先 truncate → link/copy）。
            // IMP-DEL1（TR-B1-⊕-9）：落盘 = 原始输出（无 stderr 分隔行，对齐 CC 合并 fd
            // outputFilePath 语义）；旧合并落盘方法已删除，改 writeOriginalOutputSpill。
            // 成功 → data = buildLargeToolResultMessage 预览 + structuredOutput 两字段
            // （persistedOutputPath/persistedOutputSize，CC schema :292-293）；失败 → 降级保留 base。
            // CC 语义错误先 throw（:718）不达持久化块 → Java 等价仅在 !isError 时持久化。
            // [IMP-C2 返工 R2] 用显式 baseResult.isError()（CommandSemanticsInterpreter 判定），
            //   替换 isToolErrorData 前缀启发式（Bash 错误载荷 "Exit code" 前缀漏检）。
            try {
                long combinedTotal = stdoutOut.totalBytes() + stderrOut.totalBytes();
                if (!baseResult.isError()
                        && combinedTotal > SPILL_THRESHOLD
                        && ctx != null && ctx.sessionId() != null) {
                    combinedSpill = writeOriginalOutputSpill(stdoutOut, stderrOut);
                    // 批次4 #11：persist workspaceDir 稳定锚 = getOriginalCwdLayer(sessionId)
                    //   （对齐 CC toolResultStorage.ts:97-104 getSessionDir=join(getProjectDir(getOriginalCwd()),
                    //   getSessionId())；去 effectiveCwd 优先层——CC 不用 getCwd 做 tool-result 持久化锚，
                    //   cd 后落盘目录漂移是 bug，落启动/worktree 目录）。
                    Path workspaceDir = Path.of(
                        CwdResolution.getOriginalCwdLayer(ctx.sessionId()));
                    PersistedToolResult persisted = ToolResultStorage.persistOutputFile(
                        workspaceDir, ctx.sessionId(), call.id(),
                        combinedSpill, ToolResultStorage.MAX_PERSISTED_SIZE);
                    if (persisted != null) {
                        String previewMessage =
                            ToolResultStorage.buildLargeToolResultMessage(persisted);
                        // G5-8: noOutputExpected 随持久化 structuredOutput 透传（CC outputSchema :290）
                        Map<String, Object> structuredOutput = new java.util.HashMap<>();
                        structuredOutput.put("persistedOutputPath", persisted.filepath());
                        structuredOutput.put("persistedOutputSize", persisted.originalSize());
                        structuredOutput.put("noOutputExpected", noOutputExpected);
                        if (log.isDebugEnabled()) {
                            log.debug("BashTool 输出落盘成功: path={} originalSize={} 预览len={} noOutputExpected={}",
                                persisted.filepath(), persisted.originalSize(),
                                previewMessage.length(), noOutputExpected);
                        }
                        return ToolResult.successWithStructuredOutput(
                            call.id(), previewMessage, structuredOutput);
                    }
                    // persisted == null → 降级保留 base（已截断 stdout），不抛错（CC :736/:750）
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool 持久化降级（保留截断 stdout）: total={}", combinedTotal);
                    }
                }
            } catch (Exception pe) {
                if (log.isDebugEnabled()) {
                    log.debug("BashTool 持久化异常降级（保留截断 stdout）: {}", pe.toString());
                }
            }
            return baseResult.result();

        } catch (ShellError se) {
            // G5-3: ShellError 必须透传给 StreamingToolExecutor → ToolErrorFormatter.formatError
            //   （getErrorParts 展开 "Exit code N" + interrupted + stderr + stdout，toolErrors.ts:24-32）。
            //   不得被下方通用 catch(Exception) 吞成 "BashTool error: Shell command failed"。
            throw se;
        } catch (IllegalStateException shellEx) {
            // ShellExecutor.bash 内部 resolveShell() 失败（找不到可用 Posix shell）→ CC 同款显式
            // 错误转 ToolResult.error（fail-loud，绝不静默 cmd.exe/chcp 兜底）。
            if (log.isDebugEnabled()) {
                log.debug("BashTool: 未找到可用 Posix shell: {}", shellEx.getMessage());
            }
            return ToolResult.error(call.id(), shellEx.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolResult.error(call.id(), "interrupted");
        } catch (Exception e) {
            log.error("BashTool: unexpected error", e);
            return ToolResult.error(call.id(), "BashTool error: " + e.getMessage());
        } finally {
            // G5-1/G5-2: 已转后台 → 保留 spill 临时文件（reader 线程仍在写，completion watcher
            //   结束后由进程退出自然清理；删除开放 fd 在 Windows 会失败、POSIX 删后 fd 仍写孤文件）。
            //   非后台化 → 清理 spill 临时文件（persistOutputFile 已 link/copy 进 tool-results，源可删）。
            if (backgroundTaskId == null) {
                if (stdoutCap[0] != null) {
                    deleteQuietly(stdoutCap[0].spillFile());
                }
                if (stderrCap[0] != null) {
                    deleteQuietly(stderrCap[0].spillFile());
                }
                deleteQuietly(combinedSpill);
            }
            // [WF-2A] cd 追踪临时文件清理（对齐 CC Shell.ts:417 unlinkSync，失败静默）。
            deleteQuietly(cwdTrackFile);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // G5 辅助实现（超时/自动后台/进度/sed/hints/silent/沙箱）
    // ════════════════════════════════════════════════════════════════════

    /** awaitForegroundProcess 返回码：进程自然完成。 */
    private static final int AWAIT_COMPLETED = 0;
    /** awaitForegroundProcess 返回码：超时（或 15s 定时器已转后台）。 */
    private static final int AWAIT_TIMEOUT = 1;
    /** awaitForegroundProcess 返回码：当前线程被中断（→ SIGKILL + interrupted=true）。 */
    private static final int AWAIT_INTERRUPTED = 2;

    /**
     * G5-1/G5-9: 轮询等待前台进程 · 替代旧 {@code process.waitFor(timeout)} + destroyForcibly。
     *
     * <p>轮询而非阻塞 waitFor(timeout)：让 G5-2 15s 定时器转后台后能唤醒（{@code backgroundedFlag[0]}）
     * 立即返回后台化结果（CC generator 的 Promise.race 同语义，BashTool.tsx:1007-1010 / :1035-1037）。
     * 中断 → 返回 {@link #AWAIT_INTERRUPTED}（调用方 SIGKILL + interrupted=true，对齐 CC #abortHandler）。
     *
     * <p><b>[P1-4 · G2-3 executeTimed 裁决]</b>：本方法为 BashTool 前台等待的<b>唯一生产路径</b>，
     * 不迁移 {@link ShellExecutor#executeTimed}，理由（裁决，非模糊共存）：
     * <ul>
     *   <li><b>executeTimed 接口无法承载 BashTool 的 spawn 前置集成</b>——BashTool 的
     *       {@code ProcessBuilder} 需注入沙箱 env（{@link ShellExecutor#applySandboxExecEnv}）、
     *       快照 login-shell 跳过（三参 {@code bash}）、cd 追踪包装（wrapForCwdTracking）；
     *       executeTimed 内部自行构造 {@code bashMerged(command, cwd)}（两参），不暴露 pb，无法注入。</li>
     *   <li><b>15s assistant 自动后台定时器</b>（{@link #ASSISTANT_BLOCKING_BUDGET_MS}）与 G5-1
     *       超时转后台共用 {@code backgroundedFlag} 唤醒（P1-1 后 15s 定时器独立于
     *       isAutobackgroundingAllowed）；executeTimed 的 timeout timer 独立触发，需跨对象回传
     *       background 回调，复杂度更高且丢失 15s 语义。</li>
     *   <li>P1-3 已把 BashTool 前台改为<b>合并单流</b>（redirectErrorStream(true)），与 executeTimed
     *       输出模型一致；行为语义亦一致（超时 → SIGTERM 143 + "Command timed out after X" 前缀、
     *       可后台 → 转后台不杀、中断 → SIGKILL 137）——两者均映射自同一 CC
     *       #handleTimeout/#exitHandler 真源，无契约漂移。</li>
     *   <li>executeTimed 保留于 ShellExecutor 作为<b>独立执行层原语</b>（合并单流 + ProcessTreeKiller
     *       树杀 + onTimeoutBackgroundFn 回调），供未来/他处消费者，不与本方法构成双向模糊死代码
     *       （本方法=生产路径；executeTimed=执行层工具，接口语义各异）。</li>
     * </ul>
     *
     * @param process         前台进程
     * @param timeoutMs       超时毫秒（CC BashTool.tsx:229 timeout 字段）
     * @param backgroundedFlag 单元素布尔数组（15s 定时器转后台后置 true → 立即返回）
     * @return {@link #AWAIT_COMPLETED} / {@link #AWAIT_TIMEOUT} / {@link #AWAIT_INTERRUPTED}
     */
    /**
     * 等待前台进程结束(≤250ms 轮询)。
     *
     * <p>[manual-bg 修复 2026-09-04] 除本地 backgroundedFlag(仅 BashTool 自动路径 —— 15s 定时器/超时
     * 置位)外,还检查 externallyBackgrounded(外部<b>手动</b>转后台:Ctrl+B/前端「转后台」按钮经
     * TaskController → {@code BackgroundTaskRunner.backgroundExistingForegroundTask} 翻转任务
     * isBackgrounded,<b>不置</b> BashTool 局部 backgroundedFlag)。对齐 CC BashTool.tsx:1090-1102
     * {@code shellCommand.status === 'backgrounded'} 每 tick 检测 —— CC 手动 Ctrl+B 由前台 progress loop
     * 检测到后返回 backgroundTaskId 让 agent 循环继续。任一成立 → 置 flag + 返回 AWAIT_TIMEOUT(调用方
     * :1307 走 backgroundedFlag 分支返回后台化结果,进程不杀)。
     */
    private static int awaitForegroundProcess(Process process, long timeoutMs, boolean[] backgroundedFlag,
                                              java.util.function.BooleanSupplier externallyBackgrounded) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (backgroundedFlag[0] || externallyBackgrounded.getAsBoolean()) {
                backgroundedFlag[0] = true;
                return AWAIT_TIMEOUT; // 已转后台(本地自动 15s/超时 或 外部手动 Ctrl+B),调用方按后台化结果处理
            }
            if (!process.isAlive()) {
                return AWAIT_COMPLETED;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return AWAIT_TIMEOUT;
            }
            try {
                process.waitFor(Math.min(remaining, 250), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AWAIT_INTERRUPTED;
            }
        }
    }

    /**
     * G5-9: 自然退出码解析 · 对齐 CC #exitHandler（ShellCommand.ts:195-203）。
     *
     * <p>CC 语义：{@code code !== null ? code : signal === 'SIGTERM' ? 144 : 1} —— 进程被信号杀且
     * 无退出码时 SIGTERM → 144（{@link #EXIT_SIGNALED}）。Java {@code Process.exitValue()} 在 POSIX
     * 对信号杀进程返回 {@code 128+signal}（SIGTERM → 143），与命令自行 {@code exit 143} 无法区分
     * → 保留原码不重映射（诚实记录 Java 平台差异：CC 有 code===null 信号通道，Java 无）；
     * 唯一可判定"无退出码"的场景是 {@code exitValue()} 抛
     * {@code IllegalThreadStateException}（进程未被正常回收，等价 CC {@code code===null}）
     * → 返回 {@link #EXIT_SIGNALED}（144，CC signal===SIGTERM 分支兜底）。
     *
     * @param process 已结束的进程（调用方保证 waitFor 已返回 / !isAlive）
     * @return 自然退出码；无法取码（等价 CC code===null）→ {@link #EXIT_SIGNALED}
     */
    private static int resolveNaturalExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            // 等价 CC #exitHandler code===null：进程未被正常回收（信号杀/未 waitFor）→ 144
            if (log.isDebugEnabled()) {
                log.debug("BashTool: 进程退出码不可读（等价 CC code===null）→ EXIT_SIGNALED=144"
                    + "（G5-9, ShellCommand.ts:195-203 signal==='SIGTERM' ? 144 : 1）");
            }
            return EXIT_SIGNALED;
        }
    }

    /**
     * G5-1/G5-2: 后台化前台任务的 completion watcher · 对齐 CC backgroundExistingForegroundTask 的
     * {@code shellCommand.result.then}（LocalShellTask.tsx:445-472，Java 侧由 BackgroundTaskRunner
     * completeForegroundBackgroundedTask 承接终态推进）。
     *
     * <p>本 watcher 在 BashTool execute 线程返回后台化结果后运行：等前台进程自然结束 →
     * 读 stdout/stderr capture → 写任务输出文件（对齐 CC 文件模式 outputFilePath，stderr 行
     * {@code [stderr] } 前缀）→ {@link LocalBashTaskRunner#signalForegroundCompletion} 填充
     * lastResult + 释放 completionLatch（BackgroundTaskRunner 的 awaitCompletion 由此唤醒，
     * getLastResult 读到终态结果）。
     *
     * @param process      前台进程（转后台后继续运行）
     * @param runner       已 adopt 该进程的 LocalBashTaskRunner（registerForeground 时创建）
     * @param taskId       后台任务 ID
     * @param stdoutCap    stdout 捕获载体（reader 线程仍在写）
     * @param stderrCap    stderr 捕获载体
     * @param cwd          进程工作目录（沙箱 ghost 清理目录；null → 跳过）
     * @param sandboxGhostBefore 沙箱 ghost 清理基准快照（spawn 前 cwd 0 字节点文件；null=未沙箱/无基准）
     */
    private void startBackgroundedCompletionWatcher(Process process, LocalBashTaskRunner runner,
            String taskId, OutputCapture[] stdoutCap, OutputCapture[] stderrCap,
            String cwd, java.util.Set<String> sandboxGhostBefore) {
        String outputFile = taskOutputFileOf(taskId);
        Thread w = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int code;
            try {
                code = process.exitValue();
            } catch (Exception e) {
                // 进程未完全回收 / 信号杀无退出码（等价 CC code===null）→ EXIT_SIGNALED=144
                //   （G5-9, ShellCommand.ts:195-203 signal==='SIGTERM' ? 144 : 1）
                code = EXIT_SIGNALED;
            }
            // G5-10: 后台化进程结束后清理沙箱 ghost 文件（CC Shell.ts:391-393 cleanupAfterCommand 等价）
            if (sandboxGhostBefore != null && cwd != null && !cwd.isBlank()) {
                cleanupSandboxGhostFiles(cwd, sandboxGhostBefore);
            }
            String out = stdoutCap[0] != null ? stdoutCap[0].preview() : "";
            String err = stderrCap[0] != null ? stderrCap[0].preview() : "";
            // 写任务输出文件（对齐 CC 文件模式 stdoutToFile；stderr 行 [stderr] 前缀）。
            // [P1-2] 写【全量】输出（spill 全量落盘，非 preview 30k）——对齐 CC 文件模式
            //   Shell.ts:289-313（文件模式进程直接写任务输出文件 fd，后台输出=完整输出，
            //   模型 Read 后台输出文件能看到全量而非仅 30k）。P1-3 合并单流下 stderr 恒空，
            //   stdoutCap 即全量合并输出（>30k 时 spillFile 持有全量）。
            if (outputFile != null && !outputFile.isBlank()) {
                try {
                    Path p = Path.of(outputFile);
                    Files.createDirectories(p.getParent());
                    writeFullOutputToFile(p, stdoutCap[0]);
                    if (stderrCap[0] != null && stderrCap[0].totalBytes() > 0) {
                        // 分离流场景（未来/回退）：stderr 以 [stderr] 前缀追加
                        try (BufferedWriter bw = Files.newBufferedWriter(
                                p, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                            if (stdoutCap[0] != null && stdoutCap[0].totalBytes() > 0) {
                                bw.write("\n");
                            }
                            bw.write("[stderr] ");
                            appendFullOutput(bw, stderrCap[0]);
                        }
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool: 后台化前台任务输出文件写入失败 taskId={}: {}", taskId, e.toString());
                    }
                }
            }
            if (runner != null) {
                // 填充 lastResult + 释放 completionLatch → BackgroundTaskRunner 终态推进读取
                runner.signalForegroundCompletion(new LocalBashTaskRunner.BashResult(code, out, err));
            }
            if (log.isDebugEnabled()) {
                log.debug("BashTool: 后台化前台任务进程结束 pid={} code={} taskId={}（G5-1/G5-2 completion watcher）",
                    process.pid(), code, taskId);
            }
        }, "bash-backgrounded-completion");
        w.setDaemon(true);
        w.start();
    }

    /**
     * G5-1/G5-2: 后台化结果 ToolResult · 对齐 CC BashTool.tsx:1015-1024 / :1078-1085 返回
     * {@code {stdout:'', stderr:'', code:0, backgroundTaskId, assistantAutoBackgrounded}} +
     * mapToolResultToToolResultBlockParam backgroundInfo（BashTool.tsx:606-616）消息文本。
     *
     * @param toolUseId             工具调用 ID
     * @param taskId                后台任务 ID
     * @param outputFile            任务输出文件路径（提示消息展示）
     * @param assistantAutoBackgrounded assistant 主线程 15s 自动后台标记（CC BashTool.tsx:980）
     * @param backgroundedByUser    用户 Ctrl+B 手动后台标记（Java 无 Ctrl+B 通道，恒 false）
     * @return ToolResult（successWithStructuredOutput：消息文本 + backgroundTaskId/assistantAutoBackgrounded）
     */
    private ToolResult buildBackgroundedResult(String toolUseId, String taskId, String outputFile,
            boolean assistantAutoBackgrounded, boolean backgroundedByUser) {
        String backgroundInfo;
        if (assistantAutoBackgrounded) {
            backgroundInfo = "Command exceeded the assistant-mode blocking budget ("
                + (ASSISTANT_BLOCKING_BUDGET_MS / 1000)
                + "s) and was moved to the background with ID: " + taskId
                + ". It is still running — you will be notified when it completes. "
                + "Output is being written to: " + outputFile
                + ". In assistant mode, delegate long-running work to a subagent or use "
                + "run_in_background to keep this conversation responsive.";
        } else if (backgroundedByUser) {
            backgroundInfo = "Command was manually backgrounded by user with ID: " + taskId
                + ". Output is being written to: " + outputFile;
        } else {
            backgroundInfo = "Command running in background with ID: " + taskId
                + ". Output is being written to: " + outputFile;
        }
        Map<String, Object> structured = new java.util.HashMap<>();
        structured.put("backgroundTaskId", taskId);
        structured.put("assistantAutoBackgrounded", assistantAutoBackgrounded);
        if (log.isDebugEnabled()) {
            log.debug("BashTool: 后台化结果 taskId={} assistantAutoBackgrounded={}（G5-1/G5-2）",
                taskId, assistantAutoBackgrounded);
        }
        return ToolResult.successWithStructuredOutput(toolUseId, backgroundInfo, structured);
    }

    /**
     * 前台任务输出文件路径 · BackgroundTaskRunner.taskOutputPath（registerForeground 时生成）。
     *
     * @param taskId 任务 ID（null → null）
     * @return 输出文件路径；任务不存在 → null
     */
    private String taskOutputFileOf(String taskId) {
        if (taskId == null || backgroundTaskRunner == null) {
            return null;
        }
        return backgroundTaskRunner.getTask(taskId)
            .map(BackgroundTask::outputFile)
            .orElse(null);
    }

    // ── G5-5: _simulatedSedEdit 直接写文件 ──

    /**
     * G5-5: 应用模拟 sed 编辑 · 对齐 CC BashTool.tsx:360-419 applySedEdit。
     *
     * <p>权限弹窗预览批准后把 sed 编辑结果直接写文件（不跑 sed）：读原内容（ENOENT 返回
     * {@code stderr="sed: <filePath>: No such file or directory\nExit code 1"}，:381-387）→
     * 行尾检测 → 写 newContent（:397-398）→ 更新 readFileState（:404-409）→ 返回空 stdout/stderr
     * success（:412-418，sed 成功无输出）。notifyVscodeFileUpdated（:401）Java 无 VS Code 通道，
     * log.debug 登记。
     *
     * @param toolUseId  工具调用 ID
     * @param filePath   目标文件路径（相对路径基于会话 cwd）
     * @param newContent 新文件内容
     * @param ctx        工具调用上下文（sessionId + readFileState）
     * @return ToolResult（成功空输出 / 文件不存在 stderr 错误）
     */
    private ToolResult applySimulatedSedEdit(String toolUseId, String filePath, String newContent,
            ToolUseContext ctx) {
        Path absPath = resolveSedEditPath(filePath, ctx);
        String original;
        try {
            original = Files.readString(absPath, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException e) {
            // 对齐 CC :381-387 —— ENOENT 返回 stderr "sed: <filePath>: No such file or directory\nExit code 1"
            if (log.isDebugEnabled()) {
                log.debug("BashTool: _simulatedSedEdit 目标文件不存在（G5-5, BashTool.tsx:381-387）: {}", absPath);
            }
            return ToolResult.error(toolUseId,
                "sed: " + filePath + ": No such file or directory\nExit code 1");
        } catch (IOException e) {
            return ToolResult.error(toolUseId, "BashTool sed edit failed: " + e.getMessage());
        }
        try {
            String lineEnding = detectLineEnding(original);
            String toWrite = normalizeLineEndings(newContent, lineEnding);
            Files.writeString(absPath, toWrite, StandardCharsets.UTF_8);
            // 更新 readFileState（对齐 CC :404-409 readFileState.set 换新 content + timestamp）
            if (ctx != null && ctx.readFileState() != null) {
                long mtime = Files.getLastModifiedTime(absPath).toMillis();
                ctx.readFileState().set(absPath.toAbsolutePath().normalize().toString(),
                    ToolUseContext.ReadState.full(mtime, newContent));
            }
            if (log.isDebugEnabled()) {
                log.debug("BashTool: _simulatedSedEdit 直接写文件成功 path={}（G5-5, BashTool.tsx:360-419）",
                    absPath);
            }
            // 对齐 CC :412-418 —— sed 成功无输出，返回空 stdout/stderr success
            return ToolResult.success(toolUseId, "");
        } catch (IOException e) {
            return ToolResult.error(toolUseId, "BashTool sed edit failed: " + e.getMessage());
        }
    }

    /**
     * 解析 sed 编辑目标绝对路径 · 对齐 CC {@code expandPath(filePath)}（基于 cwd 的相对路径展开）。
     */
    private static Path resolveSedEditPath(String filePath, ToolUseContext ctx) {
        Path p = Path.of(filePath);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        String cwd = ctx != null
            ? CwdResolution.getCwd(ctx.sessionId())
            : System.getProperty("user.dir", ".");
        return Path.of(cwd).resolve(p).normalize();
    }

    /** 行尾检测 · 对齐 CC detectLineEndings（utils/file.ts，CRLF vs LF）。 */
    private static String detectLineEnding(String content) {
        return content.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
    }

    /** 新内容行尾归一化 · 对齐 CC writeTextContent(filePath, newContent, encoding, endings)。 */
    private static String normalizeLineEndings(String content, String eol) {
        if ("\r\n".equals(eol)) {
            return content.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        return content.replace("\r\n", "\n");
    }

    // ── G5-6: claude-code-hint 剥离 ──

    /**
     * G5-6: 提取并剥离 claude-code-hint 标签 · 对齐 CC utils/claudeCodeHints.ts:72-120 extractClaudeCodeHints。
     *
     * <p>自闭合 {@code <claude-code-hint .../>} 整行剥离（含不支持版本/类型的行，CC replace 回调
     * 恒返回 '' 移除，仅 hints 记录分版本过滤）；剥后折叠连续 3+ 空行（:114-117）。
     *
     * <p><b>[P2-10]</b> 对齐 CC 双参签名 {@code extractClaudeCodeHints(output, command)}（:72）：
     * hint 记录携带 {@code sourceCommand} = 命令首 token（CC :81 {@code firstCommandToken}，
     * :130-134 —— trim 后第一个空白分隔段），供安装提示展示"发射 hint 的工具 vs 推荐的插件"。
     *
     * @param output  原始 stdout
     * @param command 产出该输出的命令（首 token 记入 sourceCommand）
     * @return {stripped, hints} —— stripped = 剥标签后文本；hints = 解析出的 hint 列表（含 sourceCommand）
     */
    static HintsResult extractClaudeCodeHints(String output, String command) {
        if (output == null || !output.contains("<claude-code-hint")) {
            return new HintsResult(output == null ? "" : output, java.util.List.of());
        }
        // P2-10: CC firstCommandToken（claudeCodeHints.ts:130-134）—— 空/纯空白命令 → 空串
        String sourceCommand = "";
        if (command != null) {
            String trimmed = command.trim();
            int sp = trimmed.indexOf(' ');
            sourceCommand = sp < 0 ? trimmed : trimmed.substring(0, sp);
        }
        java.util.List<java.util.Map<String, String>> hints = new java.util.ArrayList<>();
        StringBuilder stripped = new StringBuilder();
        boolean removed = false;
        String[] lines = output.split("\n", -1);
        for (String line : lines) {
            Matcher m = HINT_TAG_RE.matcher(line);
            if (m.matches()) {
                java.util.Map<String, String> attrs = parseHintAttrs(line);
                String v = attrs.get("v");
                String type = attrs.get("type");
                String value = attrs.get("value");
                // 整行剥离（CC replace 回调恒 return ''）；仅支持版本/类型 + 非空 value 记录 hint
                removed = true;
                if ("1".equals(v) && "plugin".equals(type)
                        && value != null && !value.isEmpty()) {
                    hints.add(java.util.Map.of(
                        "v", v, "type", type, "value", value, "sourceCommand", sourceCommand));
                }
                continue;
            }
            stripped.append(line).append('\n');
        }
        String result = stripped.toString();
        if (removed) {
            result = result.replaceAll("\n{3,}", "\n\n"); // 对齐 CC :114-117 折叠
        }
        return new HintsResult(result, hints);
    }

    /** G5-6: claude-code-hint 提取结果 · CC original: {@code {hints, stripped}}（claudeCodeHints.ts:75）。 */
    record HintsResult(String stripped, java.util.List<java.util.Map<String, String>> hints) {}

    /** G5-6: 自闭合标签整行匹配 · CC original: HINT_TAG_RE（claudeCodeHints.ts:53）{@code /^[ \t]*<claude-code-hint\s+([^>]*?)\s*\/>[ \t]*$/m}。 */
    private static final Pattern HINT_TAG_RE =
        Pattern.compile("^[ \\t]*<claude-code-hint\\s+[^>]*?\\s*/>[ \\t]*$");

    /** G5-6: 属性匹配 · CC original: ATTR_RE（claudeCodeHints.ts:61）{@code /(\w+)=(?:"([^"]*)"|([^\s/>]+))/g}。 */
    private static final Pattern ATTR_RE = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^\\s/>]+))");

    /** G5-6: 解析标签属性 · CC original: parseAttrs（claudeCodeHints.ts:122-128）。 */
    private static java.util.Map<String, String> parseHintAttrs(String tagLine) {
        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        Matcher m = ATTR_RE.matcher(tagLine);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            attrs.put(m.group(1), value == null ? "" : value);
        }
        return attrs;
    }

    // ── G5-8: isSilentBashCommand ──

    /**
     * G5-8: 命令是否预期成功时无 stdout · 对齐 CC BashTool.tsx:178-217 isSilentBashCommand。
     *
     * <p>返回 {@code noOutputExpected}（CC outputSchema :290，前端显示 "Done" 而非 "(No output)"）。
     * 语义（逐条对齐 :178-217）：
     * <ol>
     *   <li>{@code splitCommandWithOperators} 分段（引号感知，操作符独立成段）；parse 异常/空 → false；</li>
     *   <li>重定向操作符（{@code >}/{@code >>}/{@code >\&}）跳读目标（:196-199）；</li>
     *   <li>操作符段（{@code ||}/{@code &&}/{@code |}/{@code ;}）记 lastOperator（:200-203）；</li>
     *   <li>{@code ||} 后语义中性命令跳过（:208-210）；</li>
     *   <li>存在非 silent 段 → false；全 silent → true（:211-215）；无命令段 → false（:216）。</li>
     * </ol>
     *
     * @param command bash 命令串
     * @return {@code true} 命令预期成功时无 stdout
     */
    static boolean isSilentBashCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        List<String> parts;
        try {
            parts = splitCommandWithOperatorsForSilent(command);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool.isSilentBashCommand: 切分异常 → false（CC BashTool.tsx:181-184）: {}",
                    e.toString());
            }
            return false;
        }
        if (parts.isEmpty()) {
            return false;
        }
        boolean hasNonFallbackCommand = false;
        String lastOperator = null;
        boolean skipNextAsRedirectTarget = false;
        for (String part : parts) {
            if (skipNextAsRedirectTarget) {
                skipNextAsRedirectTarget = false;
                continue;
            }
            if (part.equals(">") || part.equals(">>") || part.equals(">&")) {
                skipNextAsRedirectTarget = true;
                continue;
            }
            if (part.equals("||") || part.equals("&&") || part.equals("|") || part.equals(";")) {
                lastOperator = part;
                continue;
            }
            String trimmed = part.trim();
            int ws = trimmed.indexOf(' ');
            String baseCommand = ws < 0 ? trimmed : trimmed.substring(0, ws);
            if (baseCommand.isEmpty()) {
                continue;
            }
            if ("||".equals(lastOperator) && BASH_SEMANTIC_NEUTRAL_COMMANDS.contains(baseCommand)) {
                continue; // CC :208-210 —— || 后语义中性跳过
            }
            hasNonFallbackCommand = true;
            if (!BASH_SILENT_COMMANDS.contains(baseCommand)) {
                return false;
            }
        }
        return hasNonFallbackCommand;
    }

    /**
     * G5-8: 引号感知操作符切分 · 对齐 CC {@code splitCommandWithOperators}（commands.ts:85-…），
     * 与 {@code BashCommandClassification.splitCommandWithOperators}（bash 包 package-private，
     * BashTool 不可访问）行为等价。操作符独立成段、引号内不切分、heredoc 体不切段、反斜杠续行合并。
     */
    private static List<String> splitCommandWithOperatorsForSilent(String command) {
        String joined = joinContinuationsForSilent(command);
        List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = joined.length();
        int i = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < n) {
            char c = joined.charAt(i);
            if (!inSingle && !inDouble && c == '\\' && i + 1 < n) {
                current.append(c).append(joined.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                i++;
                continue;
            }
            if (!inSingle && !inDouble) {
                if (c == '<' && i + 1 < n && joined.charAt(i + 1) == '<'
                        && !(i + 2 < n && joined.charAt(i + 2) == '<')) {
                    current.append("<<");
                    i += 2;
                    i = consumeHeredocForSilent(joined, i, current);
                    continue;
                }
                if (i + 1 < n) {
                    String two = joined.substring(i, i + 2);
                    if (two.equals(">>") || two.equals(">&") || two.equals("&&") || two.equals("||")) {
                        flushPart(current, parts);
                        parts.add(two);
                        i += 2;
                        continue;
                    }
                }
                char op = c;
                if (op == '>' || op == '|' || op == ';' || op == '&') {
                    flushPart(current, parts);
                    parts.add(String.valueOf(op));
                    i++;
                    continue;
                }
                if (op == '\n') {
                    flushPart(current, parts);
                    i++;
                    continue;
                }
            }
            current.append(c);
            i++;
        }
        flushPart(current, parts);
        return parts;
    }

    /** 反斜杠续行合并 · 对齐 CC commands.ts:99-115（奇数个反斜杠 + 换行 → 移除）。 */
    private static String joinContinuationsForSilent(String command) {
        StringBuilder sb = new StringBuilder(command.length());
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (c == '\\') {
                int backslashCount = 0;
                int j = i;
                while (j < n && command.charAt(j) == '\\') {
                    backslashCount++;
                    j++;
                }
                if (j < n && command.charAt(j) == '\n') {
                    if (backslashCount % 2 == 1) {
                        sb.append("\\".repeat(backslashCount - 1));
                        i = j + 1;
                        continue;
                    }
                    sb.append("\\".repeat(backslashCount));
                    i = j;
                    continue;
                }
                sb.append("\\".repeat(backslashCount));
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 跳过 heredoc 正文（<<TAG ... TAG 不切段）。 */
    private static int consumeHeredocForSilent(String joined, int from, StringBuilder current) {
        int j = from;
        int len = joined.length();
        if (j < len && joined.charAt(j) == '-') {
            current.append('-');
            j++;
        }
        StringBuilder tag = new StringBuilder();
        while (j < len && !Character.isWhitespace(joined.charAt(j)) && joined.charAt(j) != '\n') {
            tag.append(joined.charAt(j));
            j++;
        }
        current.append(tag);
        if (tag.length() == 0) {
            return j;
        }
        String delimiter = tag.toString().trim();
        StringBuilder body = new StringBuilder();
        while (j < len) {
            int nl = joined.indexOf('\n', j);
            String line = nl < 0 ? joined.substring(j) : joined.substring(j, nl);
            if (line.trim().equals(delimiter)) {
                body.append(line);
                current.append('\n').append(body);
                return nl < 0 ? len : nl + 1;
            }
            body.append('\n').append(line);
            if (nl < 0) {
                break;
            }
            j = nl + 1;
        }
        current.append('\n').append(body);
        return len;
    }

    /** 冲刷当前文本段（trim 后非空才加入）。 */
    private static void flushPart(StringBuilder sb, List<String> parts) {
        String s = sb.toString().trim();
        if (!s.isEmpty()) {
            parts.add(s);
        }
        sb.setLength(0);
    }

    // ── G5-2: isAutobackgroundingAllowed ──

    /**
     * G5-2: 命令是否允许自动后台化 · 对齐 CC BashTool.tsx:307-315 isAutobackgroundingAllowed
     * （DISALLOWED_AUTO_BACKGROUND_COMMANDS 首段命中 → false；{@code sleep} 须显式后台，:220-221）。
     *
     * @param command bash 命令串
     * @return {@code true} 允许自动后台化
     */
    static boolean isAutobackgroundingAllowed(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }
        List<String> parts;
        try {
            parts = BashParser.splitCommandDeprecated(command);
        } catch (Exception e) {
            return true;
        }
        if (parts.isEmpty()) {
            return true;
        }
        String base = parts.get(0).trim();
        if (base.isEmpty()) {
            return true;
        }
        return !DISALLOWED_AUTO_BACKGROUND_COMMANDS.contains(base);
    }

    // ── G5-4: 进度轮询 ──

    /**
     * G5-4: Bash 进度事件 · CC original: BashProgress（BashTool.tsx:665-677
     * {@code {type:'bash_progress', output, fullOutput, elapsedTimeSeconds, totalLines, totalBytes, taskId, timeoutMs}}）。
     *
     * <p>生产接线：经 {@link #bashProgressSink} 回调 → {@link BashProgressPublisher} 发射
     * {@code tool_call_progress} STOMP 事件（payload 字段对齐 CC：output/fullOutput/
     * elapsedTimeSeconds/totalLines/totalBytes；taskId/timeoutMs 缺省登记已知差异，前端经
     * 持久化消息承载 taskId）。
     *
     * @param output             本 tick 增量输出（CC lastProgressOutput / {@code output}）
     * @param fullOutput         累计输出（CC {@code fullOutput}）
     * @param elapsedTimeSeconds 已运行秒数，相对【命令启动】时刻（CC {@code startTime} BashTool.tsx:1004）
     * @param totalLines         累计行数（CC {@code totalLines}）
     * @param totalBytes         累计字节数（CC {@code totalBytes}）
     * @param sessionId          目标会话 ID（STOMP topic 路由 /topic/sessions/{sid}/stream；可空=未接线）
     * @param toolCallId         工具调用 ID（call.id()，前端归组到对应工具卡片）
     */
    public record BashProgress(String output, String fullOutput, long elapsedTimeSeconds,
            long totalLines, long totalBytes, String sessionId, String toolCallId) {

        /** [G5-4] 兼容 5 参构造器 · sessionId/toolCallId 缺省 null（非 STOMP 接线场景 / 测试直构）。 */
        public BashProgress(String output, String fullOutput, long elapsedTimeSeconds,
                long totalLines, long totalBytes) {
            this(output, fullOutput, elapsedTimeSeconds, totalLines, totalBytes, null, null);
        }
    }

    /** G5-4: 进度累加器 · 线程安全增量累积（reader 线程喂入，poller 尾部读增量）。 */
    private static final class ProgressAccumulator {
        private final StringBuilder sb = new StringBuilder();

        synchronized void append(String s) {
            if (s != null && !s.isEmpty()) {
                sb.append(s);
            }
        }

        synchronized String full() {
            return sb.toString();
        }

        synchronized int length() {
            return sb.length();
        }
    }

    /**
     * G5-4: 进度轮询线程 · 对齐 CC TaskOutput #tick（每秒一次）→ onProgress → generator yield
     * （BashTool.tsx:1003-1025 + TaskOutput.ts #tick）。PROGRESS_THRESHOLD_MS=2s 前不发射；
     * 之后每秒读 stdout 增量（lastProgressOutput），增量非空 → {@link #bashProgressSink} 回调。
     * 命令完成/进程退出自动退出；调用方显式 {@link #stop()}。
     */
    private final class ProgressPoller {
        private final Process process;
        private final ProgressAccumulator accum;
        private final String sessionId;
        private final String toolCallId;
        private final long commandStartTime;
        private final Thread thread;
        private volatile boolean stopped;

        /**
         * @param process          前台命令进程
         * @param accum            进度累加器（reader 线程喂入，本线程尾部读增量）
         * @param sessionId        会话 ID（STOMP topic 路由；可空）
         * @param toolCallId       工具调用 ID（call.id()；可空）
         * @param commandStartTime 命令启动时间戳（CC BashTool.tsx:1004 startTime）
         */
        ProgressPoller(Process process, ProgressAccumulator accum,
                String sessionId, String toolCallId, long commandStartTime) {
            this.process = process;
            this.accum = accum;
            this.sessionId = sessionId;
            this.toolCallId = toolCallId;
            this.commandStartTime = commandStartTime;
            this.thread = new Thread(this::run, "bash-progress-poller");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            thread.interrupt();
        }

        private void run() {
            try {
                Thread.sleep(PROGRESS_THRESHOLD_MS); // 2s 阈值前不显示进度（CC :55/:1003-1010）
            } catch (InterruptedException e) {
                return;
            }
            if (stopped || !process.isAlive()) {
                return;
            }
            int lastPos = 0;
            long startTime = commandStartTime; // elapsed 基准 = 命令启动时刻（CC BashTool.tsx:1004）
            while (!stopped && process.isAlive()) {
                emitIncrement(lastPos, startTime);
                lastPos = accum.length();
                try {
                    Thread.sleep(PROGRESS_POLL_INTERVAL_MS); // 每秒 tick（对齐 TaskOutput #tick）
                } catch (InterruptedException e) {
                    return;
                }
            }
            // 进程结束：最后读一次累积增量（防最后一块输出因进程退出未被 tick 读取）
            if (!stopped) {
                emitIncrement(lastPos, startTime);
            }
        }

        private void emitIncrement(int from, long startTime) {
            String full = accum.full();
            String last = from <= full.length() ? full.substring(from) : "";
            if (last.isEmpty()) {
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
            BashProgress p = new BashProgress(last, full, elapsedSeconds,
                countNewlines(full), full.length(), sessionId, toolCallId);
            if (bashProgressSink != null) {
                bashProgressSink.accept(p);
            }
            if (log.isDebugEnabled()) {
                log.debug("BashTool 进度: elapsed={}s totalBytes={} lastLines={}（G5-4, PROGRESS_THRESHOLD_MS={}ms）",
                    elapsedSeconds, p.totalBytes(), countNewlines(last), PROGRESS_THRESHOLD_MS);
            }
        }

        private long countNewlines(String s) {
            long n = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * G5-10: 沙箱执行是否生效 · 判定已由 {@link SandboxManager#shouldUseSandbox} 全量实现
     * （CC shouldUseSandbox.ts:130-153：isEnabled 三闸 + dangerouslyDisableSandbox + excludedCommands）。
     * 本方法供 G5-10 测试/日志复验（wrap 语义见 ShellExecutor.applySandboxExecEnv）。
     *
     * @param input 工具输入（含 command / dangerouslyDisableSandbox）
     * @return {@code true} 该命令应沙箱化
     */
    boolean shouldUseSandboxForExecution(JsonNode input) {
        return sandboxManager != null && sandboxManager.shouldUseSandbox("Bash", input);
    }

    /**
     * image 输出 → ToolResult.image · 对齐 CC BashTool.tsx:772-785 +
     * utils.ts:49-51 isImageOutput + utils.ts:71-91 buildImageToolResult。
     *
     * <p>stdout 先 stripEmptyLines（CC :772），再 isImageOutput 前缀锚识别（:785）；
     * 识别成功后 buildImageToolResult 拆 mediaType + base64（无 {@code data:} 前缀），
     * 经 {@link ToolResult#image} 构造 image tool_result（ToolResultMapper imageContent
     * 序列化 {@code [{type:"image", source:{type:"base64", media_type, data}}]}）。
     *
     * <p><b>[G5-7 返工] 完整输出重读</b>：Java 主链传 {@code out} 为 stdout preview
     * （captureOutput 内存仅保前 {@link #SPILL_THRESHOLD}=30k，BashTool.java:2554 previewLimit）。
     * &gt;20MB 图片的 base64 在 preview 中已截断 → Base64.decode 抛异常 → originalSize=0 →
     * 20MB 上限检查被绕过 → 返回截断/损坏 image block。现当 {@code stdoutCap} 已 spill（完整输出
     * 落临时文件）时，从 {@code stdoutCap.spillFile()} 重读完整输出作解码源（对齐 CC
     * resizeShellImageOutput utils.ts:110-131：{@code if (outputFilePath) source =
     * await readFile(outputFilePath)}，preview 截断的 base64 解码成损坏图 → 重读文件）。
     *
     * @param toolUseId 工具调用 ID
     * @param out       stdout（未 strip；可能是 preview 截断值）
     * @param stdoutCap stdout 捕获载体（spilled → 重读完整文件；null/未 spill → 用 out 本身）
     * @return image ToolResult；非 image 输出返回 null（交退出码语义路径）
     */
    /**
     * 两参便捷重载（无 stdout 捕获载体 → 无 spill 重读，用 out 本身）· 聚焦单测 / 兼容入口。
     *
     * @param toolUseId 工具调用 ID
     * @param out       stdout（完整 data URI 或非 image 文本）
     * @return image ToolResult；非 image 输出返回 null
     */
    ToolResult<JsonNode> maybeBuildImageResult(String toolUseId, String out) {
        return maybeBuildImageResult(toolUseId, out, null);
    }

    ToolResult<JsonNode> maybeBuildImageResult(String toolUseId, String out, OutputCapture stdoutCap) {
        String stripped = BashOutputUtils.stripEmptyLines(out);
        if (!BashOutputUtils.isImageOutput(stripped)) {
            return null;
        }
        // G5-7 返工：spill 时重读完整输出作解码源（CC resizeShellImageOutput utils.ts:116-120）。
        String source = stripped;
        Path spill = stdoutCap != null ? stdoutCap.spillFile() : null;
        if (spill != null) {
            // CC utils.ts:117-118 先查文件大小：超 MAX_IMAGE_FILE_SIZE 直接降级文本，不读入内存（防 OOM）
            try {
                if (Files.size(spill) > MAX_IMAGE_FILE_SIZE) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashTool: image spill 文件超 {}MB 上限（{}B）→ 降级文本（G5-7, utils.ts:117-118）",
                            MAX_IMAGE_FILE_SIZE / (1024 * 1024), Files.size(spill));
                    }
                    return null;
                }
            } catch (IOException e) {
                // stat 失败 → 继续尝试读（后续 catch 兜底）
            }
            try {
                source = Files.readString(spill, StandardCharsets.UTF_8);
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: image 输出已 spill，重读完整文件作解码源（G5-7, utils.ts:116-120）: {}",
                        spill);
                }
            } catch (IOException e) {
                // 读失败 → 回退 preview（降级文本，不抛错）
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: image spill 重读失败，回退 preview: {}", e.toString());
                }
                source = stripped;
            }
        }
        BashOutputUtils.ImageToolResult img = BashOutputUtils.buildImageToolResult(source, toolUseId);
        if (img == null) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool: isImageOutput 命中但 buildImageToolResult 解析失败（非 data URI），按文本处理");
            }
            return null;
        }
        // 原始字节数（CC parseDataUri data 为 base64 载荷；ToolResult.image originalSize 需解码长度）
        int originalSize = 0;
        try {
            originalSize = java.util.Base64.getDecoder().decode(img.data()).length;
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool: image base64 解码失败 originalSize=0（G5-7: preview 截断源已由 spill 重读规避）: {}",
                    e.toString());
            }
        }
        // G5-7: 20MB 上限 · 对齐 CC utils.ts:93-96 MAX_IMAGE_FILE_SIZE + :791-802 resizeShellImageOutput
        //   （超限 → resizeShellImageOutput 返回 null → isImage=false 降级文本，:796-801 同步保留
        //   isImage 标签准确）。Java 无图像缩放实现，先做安全方向上限降级（缩放登记待办）。
        if (originalSize > MAX_IMAGE_FILE_SIZE) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool: image 输出超 {}MB 上限（originalSize={}B）→ 降级文本（G5-7, "
                        + "utils.ts:93-96/791-802 resizeShellImageOutput）",
                    MAX_IMAGE_FILE_SIZE / (1024 * 1024), originalSize);
            }
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("BashTool: 检测到 image 输出 mediaType={} originalSize={}B", img.mediaType(), originalSize);
        }
        return ToolResult.image(toolUseId, "Image output (" + img.mediaType() + ")", img.data(),
            img.mediaType(), originalSize, null);
    }

    /**
     * [IMP-C2 返工 R2] 退出码解释结果 · 显式携带 isError 标志。
     *
     * <p><b>WHY</b>: ToolResult 4 字段契约删除 isError 后，直接 execute 消费链（BashTool/PowerShellTool
     * 持久化守卫）需要错误语义。CC 端 is_error 由<b>实际执行路径</b>推导（commandSemantics
     * interpretCommandResult），非载荷文本前缀判定。故本记录在<b>知悉错误的边界</b>
     * （interpretExitCodeResult 内已得 CommandSemanticsInterpreter.Result.isError）同步携带标志，
     * 替换数据前缀启发式 {@code LlmAgentLoop.isToolErrorData} —— 后者对 Bash 错误载荷
     * {@code <输出>\nExit code N} 前缀漏检（"Exit code" 在末尾）、对成功 "Error:" 开头文本误检。
     *
     * @param result  ToolResult（isError 错误 → ToolResult.error(data=错误消息)）
     * @param isError 显式错误标志（CC interpretCommandResult.isError 推导，非前缀启发式）
     */
    record BashExitCodeResult(ToolResult<String> result, boolean isError) {}

    /**
     * 解释命令退出码并按语义分派 ToolResult · 对齐 CC BashTool.tsx:690 消费
     * {@code interpretCommandResult(input.command, result.code, result.stdout || '', '')}
     * + commandSemantics.ts:94-99 getCommandSemantic → DEFAULT 回退。
     *
     * <p><b>package-private</b>：供合成退出码聚焦单测（不依赖真实 grep/find 进程，
     * Windows 宿主可能缺失）。CC 的 message（BashTool.tsx:808 {@code returnCodeInterpretation}）
     * 在 Java ToolResult 无对应字段，折入 data 文本（Java-idiom 偏离，登记于 A2 concerns）。
     *
     * <p><b>[IMP-C2 返工 R2]</b>: 返回 {@link BashExitCodeResult} 显式携带 isError（错误路径
     * 由 {@code CommandSemanticsInterpreter} 判定，非数据前缀启发式），直接 execute 消费链
     * （持久化守卫）据此跳过错误结果落盘。
     *
     * @param toolUseId 工具调用 ID（CC mapper 透传）
     * @param command   原始 bash 命令（CC BashTool.tsx:690 第一参 {@code input.command}）
     * @param exitCode  进程退出码（CC {@code result.code}）
     * @param out       stdout（CC {@code result.stdout}）
     * @param err       stderr（Java 端与 stdout 分离读取；CC 合并进 stdout，语义函数不读该参）
     */
    BashExitCodeResult interpretExitCodeResult(String toolUseId, String command, int exitCode, String out, String err) {
        // IMP-DEL1：对齐 CC BashTool.tsx mapToolResultToToolResultBlockParam 输出语义
        // （TR-B1-⊕-3/⊕-4）——CC 合并 fd 无 stderr 分隔行（[stdout, stderr]
        // filter(Boolean).join('\n')），空输出由 UI 显示 "(No output)" 而非注入模型可见文本。
        // 拼接输出（stdout 与 stderr 以单个换行衔接，无分隔符）。
        StringBuilder combined = new StringBuilder();
        if (out != null && !out.isEmpty()) combined.append(out);
        if (err != null && !err.isEmpty()) {
            if (combined.length() > 0) combined.append("\n");
            combined.append(err);
        }
        String result = BashOutputUtils.stripEmptyLines(combined.toString());

        // A3 输出格式化接线：stripEmptyLines → formatOutput（截断 + lines 计数 · CC
        // utils.ts:133-165，阈值 BASH_MAX_OUTPUT_DEFAULT=30000）。
        // image 输出已在 execute 层经 maybeBuildImageResult → ToolResult.image 短路
        // （BashTool.tsx:772-785 isImageOutput）；此处 isImage 分支仅作文本兜底。
        BashOutputUtils.FormattedOutput formatted =
            BashOutputUtils.formatOutput(result, BashOutputUtils.BASH_MAX_OUTPUT_DEFAULT);
        result = formatted.truncatedContent();
        if (log.isDebugEnabled()) {
            log.debug("BashTool: 输出格式化 totalLines={} isImage={} resultLen={}",
                formatted.totalLines(), formatted.isImage(), result.length());
        }
        if (formatted.isImage()) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool: image data URI 文本兜底透传（execute 层 maybeBuildImageResult 未短路）");
            }
        }

        CommandSemanticsInterpreter.Result r =
            commandSemantics.interpretCommandResult(command, exitCode, out, err);
        if (log.isDebugEnabled()) {
            log.debug("BashTool: 退出码语义解释 command={} exitCode={} isError={} message={}",
                abbreviate(command, 100), exitCode, r.isError(), r.message());
        }
        if (r.isError()) {
            // 对齐 CC BashTool.tsx:687+699：stdout 先入输出（trimEnd），isError 时在输出之后
            // append "Exit code N"（大写 E · 无后缀换行），而非 Java 旧前置小写 "exit code N\n"。
            // CC 的 stdout 与退出码文本同属一条 stdoutAccumulator（:687 先 append，:699 后 append）。
            // G30①（DEL-TR-B1-02）：result 已由 formatOutput 30k 截断（BASH_MAX_OUTPUT_DEFAULT），
            // 无二次 50k 封顶——CC 输出限制仅 formatOutput(30k)+64MB 持久化截断。
            return new BashExitCodeResult(ToolResult.error(toolUseId,
                result + "\nExit code " + exitCode), true);
        }
        if (r.message() != null) {
            // CC 走 data.returnCodeInterpretation（:808）；Java ToolResult 无此字段，
            // 折入 success 文本（grep 1 → "No matches found"）。
            return new BashExitCodeResult(
                ToolResult.success(toolUseId, r.message() + "\n" + result), false);
        }
        return new BashExitCodeResult(
            ToolResult.success(toolUseId, result), false);
    }

    /**
     * [IMP-T G15] Bash 命令执行遥测 · 对齐 CC BashTool.tsx:683 trackGitOperations +
     * :755-761 logEvent('tengu_bash_tool_command_executed')。
     *
     * <p>metadata 值域对齐 CC LogEventMetadata（boolean|number|undefined）：
     * <ul>
     *   <li>{@code command_type} — CC :754 {@code input.command.split(' ')[0]} 首词，
     *       经 {@link AnalyticsTracker#verified} 包装（CC AnalyticsMetadata_I_VERIFIED 标记）</li>
     *   <li>{@code stdout_length}/{@code stderr_length} — 输出长度（number）。
     *       <b>受控差异</b>：CC Bash 合并 fd 硬编码 {@code stderr_length:0}（:758）；
     *       Java Bash 分离 stdout/stderr 流 → 传真实 err 长度（对齐 CC PowerShell
     *       PowerShellTool.tsx:640 分离流语义），stdout_length 不含 stderr</li>
     *   <li>{@code exit_code} — 进程退出码（number）</li>
     *   <li>{@code interrupted} — 恒 false：Java 中断走 InterruptedException 早退
     *       （:1148），不达本块，故此处到达的运行均未中断</li>
     * </ul>
     *
     * @param command   原始命令文本
     * @param exitCode  进程退出码
     * @param out       stdout（preview）
     * @param err       stderr（preview）
     * @param baseResult 退出码语义解释结果（isError → 不发射命令事件，对齐 CC :718 throw）
     */
    private void emitBashCommandExecutedTelemetry(String command, int exitCode,
            String out, String err, BashExitCodeResult baseResult) {
        if (analyticsTracker == null) {
            return;
        }
        // CC BashTool.tsx:683 trackGitOperations（成功门在内，exitCode==0 才检测）
        analyticsTracker.trackGitOperations(command, exitCode, out);
        // CC :718 ShellError throw → 命令事件不发射；Java 等价 = isError 门
        if (baseResult.isError()) {
            return;
        }
        String commandType = command == null || command.isBlank() ? "" : command.trim().split("\\s+")[0];
        int stdoutLength = out == null ? 0 : out.length();
        int stderrLength = err == null ? 0 : err.length();
        analyticsTracker.logEvent("tengu_bash_tool_command_executed",
            Map.<String, Object>of(
                "command_type", AnalyticsTracker.verified(commandType),
                "stdout_length", stdoutLength,
                "stderr_length", stderrLength,
                "exit_code", exitCode,
                "interrupted", false));
        if (log.isDebugEnabled()) {
            log.debug("[BashTool] [IMP-T G15] 遥测 tengu_bash_tool_command_executed: command_type={} stdout_len={} exit={}",
                commandType, stdoutLength, exitCode);
        }
    }

    /**
     * 后台执行路径 — 对齐 CC BashTool.tsx:989 + LocalShellTask.tsx:60-95
     *
     * <p>生成 taskId → 创建 BackgroundTask(RUNNING) → spawn 到 BackgroundTaskRunner
     * <br>返回: task_id 和 output_file 提示消息
     */
    private ToolResult executeBackground(String command, String toolUseId, String createSessionId) {
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_BASH);
        // 批次4 #10：outputFile 走 taskOutputPath 同源分层 {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks/{taskId}.output
        // （对齐 CC getTaskOutputPath = join(getTaskOutputDir(), `${taskId}.output`)，diskOutput.ts:72-74；
        //   sessionId 防并发会话 clobber；扩展名 .output 对齐 CC，旧 user.dir/.nexusai/tasks + .txt 已删除）。
        String outputFile = BackgroundTaskRunner.taskOutputPath(taskId);

        // 确保输出父目录存在（分层格式父目录不天然存在 · CC ensureOutputDir diskOutput.ts:65-67）
        try {
            Files.createDirectories(Path.of(outputFile).getParent());
        } catch (Exception e) {
            log.warn("BashTool: cannot create task output dir {}: {}", outputFile, e.getMessage());
        }

        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            abbreviate(command, 100), toolUseId,
            System.currentTimeMillis(), null, null,
            outputFile, 0L, false
        );

        backgroundTaskRunner.spawn(task, command, createSessionId);

        log.info("BashTool: background task {} spawned, output={}, createSessionId={}", taskId, outputFile, createSessionId);
        return ToolResult.success(toolUseId,
            "Background task started: " + taskId
            + "\nUse TaskOutput to read the result from: " + outputFile);
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }

    /**
     * effectiveCwd 缺失时的兜底 cwd · 对齐 CC getCwd()（bashPermissions.ts checkPathConstraints
     * 以 getCwd 为越界基准）。cwd-align-ext：user.dir 兜底 → 会话 cwd；无 sessionId 回落 user.dir
     * （方案 1，零行为变化）。
     */
    private static String fallbackCwd() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n... (truncated, " + (s.length() - max) + " more chars)" : s;
    }

    // ── Bash 完整输出捕获 · CC ShellCommand outputFilePath 的 Java 等价（E4 / OPD-32）──

    /**
     * 进程单流捕获结果（stdout / stderr 各自一路）。
     *
     * @param preview    内存保留前缀（前 {@code SPILL_THRESHOLD} 字符，供模型小输出路径与解释语义用）
     * @param totalBytes 该流完整字节数（不受 preview 上限截断）
     * @param spillFile  完整输出 spill 临时文件；输出未超阈值时为 null
     */
    private record OutputCapture(String preview, long totalBytes, Path spillFile) {
        boolean spilled() {
            return spillFile != null;
        }
    }

    /**
     * 流式捕获进程单路输出：内存仅保留前 {@code previewLimit} 字符（CC {@code getStdout} 首块语义），
     * 完整输出超 {@code spillThreshold} 时 spill 到临时文件（CC ShellCommand 大输出写 outputFilePath）。
     * <p>内存有界：{@code preview} 与 spill 文件不重复累积同一行（spill 启动时先 dump 已缓冲 preview，
     * 之后行只写 spill；spill 未启动的行只进 preview）。{@code previewLimit == spillThreshold} 时无丢行。
     */
    private static OutputCapture captureOutput(InputStream stream, int previewLimit, long spillThreshold)
            throws IOException {
        return captureOutput(stream, previewLimit, spillThreshold, null);
    }

    /**
     * G5-4: 带进度回调的流式捕获 · 每读到一行（含换行）经 {@code onChunk} 回传，供
     * {@link ProgressPoller} 尾部轮询读增量（对齐 CC TaskOutput 文件模式增量，onChunk 喂
     * {@link ProgressAccumulator}）。
     */
    private static OutputCapture captureOutput(InputStream stream, int previewLimit, long spillThreshold,
            java.util.function.Consumer<String> onChunk) throws IOException {
        // G6-2：内存累积缓冲改用 EndTruncatingAccumulator（CC 真源 BashTool.tsx:636
        // {@code stdoutAccumulator = new EndTruncatingAccumulator()} + stringUtils.ts:140-215）。
        // 语义：内存累积上限 MAX_STRING_LENGTH=33MB 硬顶（stringUtils.ts:88），超限从末尾截断 +
        // {@code "output truncated - NKB removed"} 标记（:181-189）。previewLimit（getMaxOutputLength=30K）
        // 远小于 maxSize，常规路径不触发截断——33MB 硬顶是超大输出的防崩溃安全网（CC 同款）。
        EndTruncatingAccumulator preview = new EndTruncatingAccumulator();
        Path spillFile = null;
        BufferedWriter spillWriter = null;
        long total = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                total += line.length() + 1L;
                // G5-4: 逐行回传增量进度（喂 ProgressAccumulator，poller 尾部读增量）
                if (onChunk != null) {
                    onChunk.accept(line + "\n");
                }
                boolean needSpill = spillWriter != null;
                if (!needSpill && total > spillThreshold) {
                    // 首超阈值：把已缓冲 preview（不含本行）dump 进 spill，之后行全写文件
                    spillFile = Files.createTempFile("bash-out-", ".txt");
                    spillWriter = Files.newBufferedWriter(spillFile, StandardCharsets.UTF_8);
                    spillWriter.write(preview.toString());
                    needSpill = true;
                }
                if (needSpill) {
                    spillWriter.write(line);
                    spillWriter.newLine();
                } else if (preview.length() < previewLimit) {
                    preview.append(line + "\n");
                }
            }
        } finally {
            if (spillWriter != null) {
                spillWriter.close();
            }
        }
        return new OutputCapture(preview.toString(), total, spillFile);
    }

    /**
     * P1-2: 将捕获输出【全量】写入目标文件 · 对齐 CC 文件模式（Shell.ts:289-313 进程直接写
     * 任务输出文件 fd → 后台/持久化输出=完整输出）。spillFile 存在 → 全量 spill（>30k 大输出）；
     * 否则 preview（≤30k 即全量）。写入覆盖模式（新文件）。
     *
     * @param target 目标文件路径
     * @param cap    捕获结果（null / totalBytes==0 → 不写，目标文件保持空/不存在）
     */
    private static void writeFullOutputToFile(Path target, OutputCapture cap) throws IOException {
        if (cap == null || cap.totalBytes() == 0) {
            return;
        }
        if (cap.spilled() && cap.spillFile() != null) {
            try (InputStream in = Files.newInputStream(cap.spillFile());
                 OutputStream os = Files.newOutputStream(target)) {
                in.transferTo(os);
            }
        } else {
            Files.writeString(target, cap.preview(), StandardCharsets.UTF_8);
        }
    }

    /**
     * P1-2: 将捕获输出【全量】追加写入 writer · 供 stderr [stderr] 前缀段复用
     * （分离流回退场景；P1-3 合并单流下 stderr 恒空不触发）。
     */
    private static void appendFullOutput(BufferedWriter w, OutputCapture cap) throws IOException {
        if (cap == null || cap.totalBytes() == 0) {
            return;
        }
        if (cap.spilled() && cap.spillFile() != null) {
            try (InputStream in = Files.newInputStream(cap.spillFile())) {
                w.write(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } else {
            w.write(cap.preview());
        }
    }

    /**
     * 持久化原始输出到临时文件（IMP-DEL1 · TR-B1-⊕-9，替代已删除的旧合并落盘方法）。
     * 对齐 CC 持久化「原始 outputFilePath」语义：CC 合并 fd，stderr 已并入 stdout 单流，
     * 落盘文件 = 完整输出、无 stderr 分隔行。Java 分离读取两路，落盘时按模型可见内容
     * 同款衔接（stdout 后跟单个换行再跟 stderr，对齐 mapToolResultToToolResultBlockParam
     * [stdout, stderr].filter(Boolean).join('\n')）。任一参数 spillFile 为 null 时用其内存
     * preview 回填，保证合并文件 = 完整输出。仅当 combined 超阈值（调用方判定）时调用。
     */
    private static Path writeOriginalOutputSpill(OutputCapture stdoutCap, OutputCapture stderrCap) throws IOException {
        Path combined = Files.createTempFile("bash-out-", ".txt");
        try {
            if (stdoutCap.spilled()) {
                try (InputStream in = Files.newInputStream(stdoutCap.spillFile());
                     var w = Files.newOutputStream(combined)) {
                    in.transferTo(w);
                }
            } else if (stdoutCap.totalBytes() > 0) {
                Files.writeString(combined, stdoutCap.preview(), StandardCharsets.UTF_8);
            }
            if (stderrCap.totalBytes() > 0) {
                try (BufferedWriter w = Files.newBufferedWriter(
                        combined, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                    if (stdoutCap.totalBytes() > 0) {
                        w.write("\n");
                    }
                    if (stderrCap.spilled()) {
                        try (BufferedReader r = Files.newBufferedReader(
                                stderrCap.spillFile(), StandardCharsets.UTF_8)) {
                            r.transferTo(w);
                        }
                    } else {
                        w.write(stderrCap.preview());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(combined);
            throw e;
        }
        return combined;
    }

    /** 静默删除临时文件（清理 spill 载体，失败仅 debug 日志）。 */
    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("BashTool 清理临时文件失败（忽略）: path={} err={}", p, e.toString());
            }
        }
    }

    // ── G5-10: 沙箱 ghost 文件 cleanup（CC Shell.ts:391-393 SandboxManager.cleanupAfterCommand 等价）──

    /**
     * G5-10: 快照 cwd 中 0 字节文件集合 · spawn 前调用，作 ghost 清理对比基准。
     *
     * <p>CC cleanupAfterCommand（srt 适配器）删 bwrap 在宿主创建的 0 字节挂载点占位文件
     * （防写非存在路径 .bashrc/HEAD 等，命令结束后残留为 cwd 中 ghost dotfile）。Java 无 srt，
     * 采用保守等价：spawn 前快照 cwd 0 字节点文件，结束后删除"运行期间新出现"的 0 字节点文件
     * （绝不删命令前已存在的）。目录不可读/解析失败 → 空集（cleanup 亦 no-op）。
     *
     * @param cwd 进程工作目录（null/blank → 空集）
     * @return spawn 前存在的 0 字节点文件名集合
     */
    static java.util.Set<String> snapshotSandboxGhostFiles(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return java.util.Set.of();
        }
        Path dir;
        try {
            dir = Path.of(cwd).toRealPath();
        } catch (IOException e) {
            return java.util.Set.of();
        }
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    if (Files.isRegularFile(p) && Files.size(p) == 0L) {
                        existing.add(p.getFileName().toString());
                    }
                } catch (IOException ignore) {
                    // 单个文件 stat 失败跳过
                }
            });
        } catch (IOException e) {
            return java.util.Set.of(); // 目录不可读 → 空基准（cleanup 亦 no-op）
        }
        return existing;
    }

    /**
     * G5-10: 删除沙箱 ghost 0 字节文件 · 对齐 CC Shell.ts:391-393 cleanupAfterCommand。
     *
     * <p>只删「spawn 前不存在 + 0 字节」的文件（bwrap 挂载点占位），绝不删命令前已存在的文件
     * （防误删用户正常文件）。删除失败仅 debug 日志（对齐 CC cleanup 静默容错）。
     *
     * @param cwd    进程工作目录（null/blank → no-op）
     * @param before spawn 前 0 字节点文件名快照（null → no-op）
     */
    static void cleanupSandboxGhostFiles(String cwd, java.util.Set<String> before) {
        if (cwd == null || cwd.isBlank() || before == null) {
            return;
        }
        Path dir;
        try {
            dir = Path.of(cwd).toRealPath();
        } catch (IOException e) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    if (Files.isRegularFile(p) && Files.size(p) == 0L
                            && !before.contains(p.getFileName().toString())) {
                        Files.deleteIfExists(p);
                        if (log.isDebugEnabled()) {
                            log.debug("BashTool: 删除沙箱 ghost 0 字节文件 {}（G5-10, Shell.ts:391-393 cleanupAfterCommand）",
                                p);
                        }
                    }
                } catch (IOException ignore) {
                    // 单文件删除失败跳过（对齐 CC cleanup 静默容错）
                }
            });
        } catch (IOException e) {
            // 目录不可读 → 跳过
        }
    }

    /**
     * PR 3 selective override: tool's own permission stance (invoked by layer 1c of the 10-layer rules).
     *
     * <h2>Aligns with CC</h2>
     * <p>{@code utils/permissions/permissions.ts:1208-1223} layer 1c calls
     * {@code tool.checkPermissions(input, context)}. This override is the content-aware version for BashTool.
     *
     * <h2>Rules (Phase 1 simplified)</h2>
     * <ol>
     *   <li><strong>Dangerous command -> Ask</strong>（对齐 CC bashSecurity.ts 危险检测恒 ask 零 deny；
     *       bashPermissions.ts:1221-1238 bashCommandIsSafeAsync → ask + type:'other' + suggestions:[]）:
     *       contains destructive-recursive-flag-then-root / mkfs / dd if= / fork bomb</li>
     *   <li><strong>Read-only command -> Allow</strong>:
     *       ls / cat / pwd / echo / head / tail / wc / grep / find</li>
     *   <li><strong>Other -> Passthrough</strong>: defer to general rules (2a / 2b / 3)</li>
     * </ol>
     *
     *
     * <h2>IMP-B2（组 1-4 B）dangerous[] 黑名单删除</h2>
     * <p>旧实现用字符串 contains 集合判危（对齐旧 {@code execute} DANGEROUS 正则），
     * IMP-B2 已删除（TR-B1-⊕2，推翻 OPD-PERM-28）：危险命令判定改由 CC 真源链承接
     * （3.3 BashSecurityValidator + 3.4 BashPathValidator，恒 ask + path/read-only），
     * 与本方法 CC 语义对齐（bashSecurity 恒 ask 零 deny，非黑名单）。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        // 1. Extract command string (input.path("command") consistent with CC)
        String command = input.path("command").asText("");
        // 0. [IMP-4] AST 决策链 · 对齐 CC bashToolHasPermission :1670-1827（三态）
        //    [P0-1] 改用 G4 parseForSecurity（全仓此前 0 生产调用的死代码已接线）。
        //    non-simple（too-complex / parse-unavailable）→ checkEarlyExitDeny（exact allow 覆盖 +
        //    prefix deny）→ Ask(Other)；Simple → checkSemantics（post-argv 语义检查，eval-like
        //    builtins / 空名 / 片段）→ 失败 checkSemanticsDeny（逐子命令 .text prefix deny）→ Ask(Other)；
        //    通过 → 落 legacy 链。execute 层不再有 parseForSecurity 硬阻断兜底（G1 / DEL-TR-B1-01，
        //    对齐 CC：危险命令仅权限 ask，批准后执行）。
        //    SECURITY：用户 deny 规则在 too-complex/语义失败时必须生效，不得降级为 ask
        //    （CC :1420-1424）。ctx/permissionContext 缺失时 deny 检查跳过（管线外直调无规则集）。
        {
            ParseForSecurityResult parsed = BashParser.parseForSecurity(command);
            if (!(parsed instanceof ParseForSecurityResult.Simple simple)) {
                // too-complex / parse-unavailable：respect exact+prefix deny（CC :1741-1769）
                PermissionResult earlyExit = checkEarlyExitDeny(command, input, ctx);
                if (earlyExit != null) {
                    return earlyExit;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Bash AST 决策链：命令含不可静态分析结构（too-complex/parse-unavailable）"
                        + " → Ask(Other)：{}", abbreviate(command, 80));
                }
                // isBashSecurityCheckForMisparsing=true：too-complex 命令（命令替换/进程替换/
                // 未引号 heredoc）本质是注入风险，与 legacy 3.3 misparsing gate 同语义——
                // ask 必须 bypass-immune（PermissionPipeline 消费），不得被 bypass 模式绕过。
                // CC too-complex ask 无此字段（Java 内部 marker），设 true 不冲突且保留安全不变量。
                return new PermissionResult.Ask(
                    "Permission to use Bash with command " + command
                        + " requires approval because it cannot be statically analyzed.",
                    new PermissionDecisionReason.Other(
                        "Command contains shell constructs that cannot be statically analyzed"),
                    java.util.List.of(),
                    null, null, null, true,
                    buildPendingClassifierCheck(command, ctx),
                    null);
            }
            // simple：checkSemantics（CC :1771-1806）——直接对 G4 结构化命令做 post-argv 语义检查
            String semReason = BashParser.checkSemantics(simple.commands());
            if (semReason != null) {
                // 子命令 .text span 列表（CC astCommands.map(c => c.text)，bashPermissions.ts:1436-1450）
                java.util.List<String> semSubcommands = new java.util.ArrayList<>();
                for (ParseForSecurityResult.BashSimpleCommand sc : simple.commands()) {
                    if (sc.text() != null && !sc.text().isBlank()) {
                        semSubcommands.add(sc.text());
                    }
                }
                PermissionResult earlyExit = checkSemanticsDeny(command, input, ctx, semSubcommands);
                if (earlyExit != null) {
                    return earlyExit;
                }
                if (log.isDebugEnabled()) {
                    log.debug("Bash AST 决策链：语义检查失败（{}）→ Ask(Other)：{}",
                        semReason, abbreviate(command, 80));
                }
                return new PermissionResult.Ask(
                    "Permission to use Bash with command " + command
                        + " requires approval: " + semReason,
                    new PermissionDecisionReason.Other(semReason),
                    java.util.List.of(),
                    null, null, null, false, null, null);
            }
        }
        // 2. Dangerous command -> Ask（对齐 CC bashSecurity ask，零 deny）
        //    IMP-B2（组 1-4 B）：删除 dangerous[] 字符串黑名单（TR-B1-⊕2，推翻 OPD-PERM-28）。
        //    CC bashSecurity.ts 危险检测恒 ask（grep -c "behavior: 'deny'" = 0），非字符串黑名单；
        //    危险命令判定由 CC 真源链承接：
        //      3.3 BashSecurityValidator.check（bashSecurity.ts 移植：注入/重定向/变量危险上下文 → ask）
        //      3.4 BashPathValidator.check（pathValidation.ts 移植：危险删除 rm /、rm ~、rm /etc → ask）
        //    两层均在危险命令命中时返回 Ask（Other 归因，suggestions 由 CC bashPermissions.ts:1224 语义
        //    恒空），与旧 dangerous[] 黑名单等效且更贴合 CC（EV-B1-D2/D3）。
        // 3.0 [Q-BS-4] deny/ask 并行分类块 · 对齐 CC bashPermissions.ts:1856-1971
        //     "Check Bash prompt deny and ask rules in parallel (both use Haiku)"。
        //     gate = isClassifierPermissionsEnabled() && !(TRANSCRIPT_CLASSIFIER && mode==='auto')
        //     deny 优先于 ask，两者优先于 allow。CC 外部构建 isClassifierPermissionsEnabled()=false
        //     且 getBashPrompt{Deny,Ask}Descriptions 恒 [] → 本块 no-op（hasDeny/hasAsk=false 跳过）。
        PermissionMode ccPermMode = ctx != null ? ctx.permissionMode() : PermissionMode.DEFAULT;
        if (isBashClassifierEnabled()
                && !(transcriptClassifierEnabled && ccPermMode == PermissionMode.AUTO)) {
            // CC bashClassifier.ts:28-34 外部 stub 恒返回 [] → deny/ask descriptions 恒空（对齐 CC）。
            List<String> denyDescriptions = java.util.List.of();
            List<String> askDescriptions = java.util.List.of();
            boolean hasDeny = !denyDescriptions.isEmpty();
            boolean hasAsk = !askDescriptions.isEmpty();
            if (hasDeny || hasAsk) {
                String cwdStr = ctx != null && ctx.effectiveCwd() != null
                    ? ctx.effectiveCwd().toString()
                    : fallbackCwd();
                DenyAskClassification denyAsk = classifyDenyAskParallel(
                    command, cwdStr, denyDescriptions, askDescriptions);
                if (log.isDebugEnabled()) {
                    log.debug("BashTool deny/ask 分类块：hasDeny={} hasAsk={} denyMatches={} askMatches={} command={}",
                        hasDeny, hasAsk,
                        denyAsk.deny() != null && denyAsk.deny().matches(),
                        denyAsk.ask() != null && denyAsk.ask().matches(),
                        abbreviate(command, 80));
                }
                // deny 优先（CC :1920 "Deny takes precedence"）
                if (denyAsk.deny() != null && denyAsk.deny().matches()
                        && "high".equals(denyAsk.deny().confidence())) {
                    String msg = "Denied by Bash prompt rule: \"" + denyAsk.deny().matchedDescription() + "\"";
                    return new PermissionResult.Deny(
                        msg,
                        new PermissionDecisionReason.Other(msg),
                        null);
                }
                // ask 次之（CC :1932）· suggestions 走 suggestionForExactCommand（CC :1941），
                // pendingClassifierCheck 走 buildPendingClassifierCheck（CC :1960-1963）
                if (denyAsk.ask() != null && denyAsk.ask().matches()
                        && "high".equals(denyAsk.ask().confidence())) {
                    List<PermissionUpdate> suggestions =
                        BashRuleMatcher.suggestionForExactCommand(command);
                    PermissionResult.PendingClassifierCheck pending =
                        buildPendingClassifierCheck(command, ctx);
                    return new PermissionResult.Ask(
                        "Permission to use tool 'Bash' requires approval",
                        new PermissionDecisionReason.Other(
                            "Required by Bash prompt rule: \"" + denyAsk.ask().matchedDescription() + "\""),
                        suggestions,
                        null, null, null, false, pending, null);
                }
            }
        }
        // 2b. content-specific ask rule → Ask（Rule 归因）· 对齐 CC bashToolCheckPermission
        //     2b（bashPermissions.ts:1095-1104）：matchingAskRules[0] → {behavior:'ask',
        //     message:createPermissionRequestMessage(BashTool.name), decisionReason:{type:'rule',
        //     rule:matchingAskRules[0]}}。内容 ask 规则（如 Bash(npm publish:*)）在工具
        //     checkPermissions 内求值（ask 桶 stripAllEnvVars=true + skipCompoundCheck=true，
        //     与 deny 桶同，CC bashPermissions.ts:957-966），产出的 Ask+Rule 归因由管线 1f
        //     （CheckLayer1f_ContentSpecificAskRule）消费为 bypass-immune。
        //     位置约束（[WF2-02]）：必须在 sandbox auto-allow（step 3）与 content allow
        //     （step 3.5）之前——CC checkSandboxAutoAllow（bashPermissions.ts:1270-1359）
        //     deny/ask 优先于 auto-allow，且 2b ask 先于 4/5 allow（git:* ask + git status
        //     exact allow 仍 Ask，不破坏对齐）。ctx/permCtx 缺失时跳过（管线外直调无规则集）。
        if (ctx != null && ctx.permissionContext() != null) {
            com.nexusai.application.agent.permission.PermissionRule askRule =
                com.nexusai.application.agent.permission.check.RuleQuery
                    .getAskRuleByContentsForTool(ctx.permissionContext(), this, input);
            if (askRule != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash 内容 ask 规则命中 → Ask(Rule 归因): rule={} command={}",
                        com.nexusai.application.agent.permission.check.RuleQuery
                            .ruleToString(askRule), abbreviate(command, 50));
                }
                return new PermissionResult.Ask(
                    "Claude requested permissions to use Bash, but you haven't granted it yet.",
                    new PermissionDecisionReason.Rule(askRule),
                    List.of(),
                    null, null, null, false, null, null);
            }
        }
        // 3. s04 PR 4: Sandbox auto-allow — 如果沙箱启用且命令可在沙箱中执行 → auto allow
        // 对齐 CC bashPermissions.ts:1832-1838 三闸：
        //   SandboxManager.isSandboxingEnabled() && isAutoAllowBashIfSandboxedEnabled() && shouldUseSandbox(input)
        //   [IMP-5] isEnabled() 已升级为 CC isSandboxingEnabled 三闸四门（isSupportedPlatform +
        //   checkDependencies + isPlatformInEnabledList + getSandboxEnabledSetting，sandbox-adapter.ts:532-547）——
        //   平台不支持/依赖缺失/白名单排除/未开启时，命令不得被当作"沙箱内执行"而 auto-allow（fail-closed）。
        if (sandboxManager != null && sandboxManager.isEnabled()
                && sandboxManager.isAutoAllowBashIfSandboxed()) {
            if (sandboxManager.shouldUseSandbox("Bash", input)) {
                // [WF-4 S01] 沙箱 auto-allow 前接入 matchingDenyOrAskRule 预检 · 对齐 CC
                //   checkSandboxAutoAllow（bashPermissions.ts:1276-1348）：全命令 deny →
                //   compound 逐子命令 deny/ask → 全命令 ask → 才 auto-allow。
                //   deny→拒、ask→弹窗、均不命中→放行。堵住 Bash(rm:*) 对
                //   "echo hi && rm -rf /" 的绕过（R3，原 [S09] 兜底已删，主链逐子命令收敛于此）。
                if (ctx != null && ctx.permissionContext() != null) {
                    // P0-2: deny/ask 匹配先剥前导 `!`（CC negated_command 真实 argv）
                    BashRuleMatcher.DenyOrAskRule denyOrAsk =
                        BashRuleMatcher.matchingDenyOrAskRule(stripNegationPrefix(command), ctx.permissionContext());
                    if (denyOrAsk != null) {
                        if (denyOrAsk.deny()) {
                            if (log.isDebugEnabled()) {
                                log.debug("沙箱 auto-allow deny 预检命中 → 拒（CC checkSandboxAutoAllow :1284）: command={}",
                                    abbreviate(command, 50));
                            }
                            return new PermissionResult.Deny(
                                "Permission to use Bash with command " + command + " has been denied.",
                                new PermissionDecisionReason.Rule(denyOrAsk.rule()),
                                null);
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("沙箱 auto-allow ask 预检命中 → 弹窗（CC checkSandboxAutoAllow :1327/:1339）: command={}",
                                abbreviate(command, 50));
                        }
                        return new PermissionResult.Ask(
                            "Claude requested permissions to use Bash, but you haven't granted it yet.",
                            new PermissionDecisionReason.Rule(denyOrAsk.rule()),
                            List.of(), null, null, null, false, null, null);
                    }
                }
                log.debug("Sandbox auto-allow: command can be sandboxed: {}", abbreviate(command, 50));
                return new PermissionResult.Allow(
                    input,
                    new PermissionDecisionReason.Other("Sandboxed bash command auto-allowed"),
                    null, false, null, null);
            }
        }
        // 3.3 [RV-D-01 NG-1] BashSecurity 校验器门禁 · 对齐 CC bashPermissions.ts:2085-2142
        //     legacy misparsing gate：仅 misparsing ask（isBashSecurityCheckForMisparsing）阻断。
        //     CC 在 sandbox auto-allow(:1831) 之后、path 约束(:2276) 之前——injection ask 优先于
        //     path ask、优先于 exact allow（CC :2334）。命中先 stripSafeHeredocSubstitutions 重检
        //     remainder（CC :2100-2107，$() 由安全 heredoc 头触发时剥除后重检）；非 misparsing
        //     ask（validateNewlines/validateRedirections）defer 交既存 path/重定向层；early-allow
        //     （git commit 简单消息 / 安全 heredoc）在主链内已转 passthrough 继续管线。
        {
            BashSecurityValidator.Result security = BashSecurityValidator.check(command);
            if (security.ask() && security.misparsing()) {
                String remainder = BashHeredocExtractor.stripSafeHeredocSubstitutions(command);
                BashSecurityValidator.Result remainderResult = remainder != null
                        ? BashSecurityValidator.check(remainder)
                        : null;
                if (remainder == null
                        || (remainderResult != null && remainderResult.ask() && remainderResult.misparsing())) {
                    if (log.isDebugEnabled()) {
                        log.debug("BashSecurity 校验器阻断 misparsing 注入：checkId={} subId={} command={}",
                                security.checkId(), security.subId(), abbreviate(command, 120));
                    }
                    // 3.3a [FIX-A-R3] misparsing ask 的 exact-allow 覆盖 · 对齐 CC
                    //     bashPermissions.ts:2105-2117：remainder 仍 misparsing-ask 时，先以
                    //     exact 模式查 allow 桶（bashToolCheckExactMatchPermission :991-1021），
                    //     命中显式 allow 规则则 allow 覆盖（用户对该具体命令做过 conscious choice），
                    //     未命中才构造 Ask。SECURITY: 必须 exactMode=true（非 3.5 步 prefix 模式），
                    //     否则 wildcard/prefix 规则会过宽放行（安全降级）。ctx==null 时跳过
                    //     （对齐 3.4/3.5 既有 guard，SecurityGateTest 直调 null ctx 仍返回 Ask）。
                    if (ctx != null && ctx.permissionContext() != null) {
                        // P0-2: exact allow 亦剥前导 `!`（CC argv 匹配语义，bash 实际运行同命令）
                        com.nexusai.application.agent.permission.PermissionRule exactAllow =
                            BashRuleMatcher.matchingExactAllowRule(
                                stripNegationPrefix(command), ctx.permissionContext());
                        if (exactAllow != null) {
                            if (log.isDebugEnabled()) {
                                log.debug("Bash misparsing ask 被 exact-allow 覆盖: rule={} command={}",
                                    com.nexusai.application.agent.permission.check.RuleQuery
                                        .ruleToString(exactAllow), abbreviate(command, 80));
                            }
                            return new PermissionResult.Allow(
                                input,
                                new PermissionDecisionReason.Rule(exactAllow),
                                null, false, null, null);
                        }
                    }
                    return new PermissionResult.Ask(
                        security.message(),
                        new PermissionDecisionReason.Other(security.message()),
                        java.util.List.of(),
                        null, null, null, true,
                        buildPendingClassifierCheck(command, ctx),
                        null);
                }
            }
        }
        // [IMP-3 R5] 子命令扇出上限 · 对齐 CC bashPermissions.ts:2159-2179
        //     （MAX_SUBCOMMANDS_FOR_SECURITY_CHECK=50，CC-643 防 REPL 冻结）：splitCommand
        //     切分后子命令数超限 → ask（"too many to safety-check individually"）。
        //     Java 无 AST（astSubcommands 恒 null），恒走 splitCommand 等价（splitCommandSegments）。
        //     位置在 sandbox auto-allow（step 3）之后、per-subcommand deny/ask（R1）之前，
        //     对齐 CC checkSandboxAutoAllow 早于扇出上限的时序。
        //     [G33②] 先经 filterCdCwdSubcommands 过滤 `cd ${cwd}` 前缀子命令（CC :2152 同款，
        //     模型常预置的无操作 cd 不占扇出计数）。
        List<String> fanoutSubcommands = filterCdCwdSubcommands(
            BashRuleMatcher.splitCommandSegments(command),
            ctx != null && ctx.effectiveCwd() != null
                ? ctx.effectiveCwd().toString()
                : fallbackCwd());
        if (fanoutSubcommands.size() > BashRuleMatcher.MAX_SUBCOMMANDS_FOR_SECURITY_CHECK) {
            int fanoutCount = fanoutSubcommands.size();
            if (log.isDebugEnabled()) {
                log.debug("Bash 子命令扇出超限（CC bashPermissions.ts:2162-2179）: {} 段 > {} command={}",
                    fanoutCount, BashRuleMatcher.MAX_SUBCOMMANDS_FOR_SECURITY_CHECK,
                    abbreviate(command, 50));
            }
            String reason = "Command splits into " + fanoutCount
                + " subcommands, too many to safety-check individually";
            return new PermissionResult.Ask(
                reason,
                new PermissionDecisionReason.Other(reason),
                java.util.List.of(),
                null, null, null, false, null, null);
        }
        // [IMP-3 R1] 主链 prefix deny/ask 桶检查 · 对齐 CC bashToolCheckPermission
        //     （bashPermissions.ts:1083-1104）：在 path 约束（3.4）之前独立检查 prefix
        //     deny/ask 桶（matchingDenyOrAskRule 等价主链接入，OPD-WF4-01-R1 拍板）。
        //     非沙箱路径此前仅 2b ask 覆盖（RuleQuery.getAskRuleByContentsForTool），deny 桶
        //     在主链无独立检查（EV-WF4-01-027）→ deny 规则对非沙箱命令可被绕过。本检查
        //     复用 sandbox 预检同一 matchingDenyOrAskRule（checkSandboxAutoAllow 等价，
        //     :1276-1348），全命令 deny → compound 逐子 deny/ask → 全命令 ask。
        //     ctx/permCtx 缺失时跳过（管线外直调场景无规则集可查）。
        if (ctx != null && ctx.permissionContext() != null) {
            // P0-2: deny/ask 匹配先剥前导 `!`（CC negated_command 真实 argv）
            BashRuleMatcher.DenyOrAskRule denyOrAsk =
                BashRuleMatcher.matchingDenyOrAskRule(stripNegationPrefix(command), ctx.permissionContext());
            if (denyOrAsk != null) {
                if (denyOrAsk.deny()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Bash 主链 prefix deny 命中（CC bashPermissions.ts:1083-1092）: rule={} command={}",
                            com.nexusai.application.agent.permission.check.RuleQuery
                                .ruleToString(denyOrAsk.rule()), abbreviate(command, 50));
                    }
                    return new PermissionResult.Deny(
                        "Permission to use Bash with command " + command + " has been denied.",
                        new PermissionDecisionReason.Rule(denyOrAsk.rule()),
                        null);
                }
                if (log.isDebugEnabled()) {
                    log.debug("Bash 主链 prefix ask 命中（CC bashPermissions.ts:1095-1104）: rule={} command={}",
                        com.nexusai.application.agent.permission.check.RuleQuery
                            .ruleToString(denyOrAsk.rule()), abbreviate(command, 50));
                }
                return new PermissionResult.Ask(
                    "Claude requested permissions to use Bash, but you haven't granted it yet.",
                    new PermissionDecisionReason.Rule(denyOrAsk.rule()),
                    java.util.List.of(),
                    null, null, null, false, null, null);
            }
        }
        // 3.4 [Q-BS-2] path 约束层 · 对齐 CC bashToolCheckPermission 第 3 步 checkPathConstraints
        //     （bashPermissions.ts:1112，在 allow 规则之前、deny/ask 之后）：路径越界（ls /etc、
        //     cat ~/.ssh）、cd+write、cd+redirect、危险删除（rm /、rm ~、rm /etc）、sed 写文件 四类
        //     安全防护。deny=Edit-deny 规则；ask=越界/不可静态校验。ctx/permCtx 缺失时跳过
        //     （管线外直调场景无规则集/工作目录上下文）。原探查 D-03 登记 "path 约束未实现" 已补齐。
        if (ctx != null && ctx.permissionContext() != null) {
            // cwd-align-ext：path 约束越界基准兜底 = 会话 cwd（CC bashPermissions.ts:1112 checkPathConstraints 用 getCwd）
            Path cwd = ctx.effectiveCwd() != null
                ? ctx.effectiveCwd()
                : Path.of(fallbackCwd());
            PermissionResult pathResult = BashPathValidator.check(command, cwd, ctx.permissionContext());
            if (!(pathResult instanceof PermissionResult.Passthrough)) {
                if (log.isDebugEnabled()) {
                    log.debug("BashTool: path 约束命中（{}），command={}",
                        pathResult instanceof PermissionResult.Deny ? "deny" : "ask",
                        abbreviate(command, 120));
                }
                return pathResult;
            }
        }
        // 3.5 [IMP-3 R2] content-specific allow rule → allow · 对齐 CC bashToolCheckPermission
        //     step 4/5 (bashPermissions.ts:1124-1139)：CC 先 exact 先查（:1059 独立
        //     bashToolCheckExactMatchPermission，:1124-1127 exactMatchResult.behavior==='allow'
        //     优先返回），再 prefix allow（:1129-1139 matchingAllowRules[0]）。旧实现 3.5 用
        //     前缀模式查询（RuleQuery exactMode=false，EV-WF4-01-061），exact 优先语义不成立。
        //     OPD-WF4-01-R2 拍板：复用 matchingExactAllowRule（:975，exact 模式独立查询：
        //     wildcard 恒拒绝、prefix 要求整体相等）为主链 exact allow，3.5 前缀查询降为兜底。
        //     ctx/permCtx 缺失时跳过（管线外直调场景无规则集可查）。
        if (ctx != null && ctx.permissionContext() != null) {
            // step 4: exact allow 优先（CC bashPermissions.ts:1124-1127）· P0-2 剥前导 `!`
            com.nexusai.application.agent.permission.PermissionRule exactAllow =
                BashRuleMatcher.matchingExactAllowRule(
                    stripNegationPrefix(command), ctx.permissionContext());
            if (exactAllow != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash content exact allow 命中（CC bashPermissions.ts:1124-1127）: rule={} command={}",
                        com.nexusai.application.agent.permission.check.RuleQuery
                            .ruleToString(exactAllow), abbreviate(command, 50));
                }
                return new PermissionResult.Allow(
                    input,
                    new PermissionDecisionReason.Rule(exactAllow),
                    null, false, null, null);
            }
            // step 5: prefix allow 兜底（CC bashPermissions.ts:1129-1139）
            com.nexusai.application.agent.permission.PermissionRule allowRule =
                com.nexusai.application.agent.permission.check.RuleQuery
                    .getAllowRuleByContentsForTool(ctx.permissionContext(), this, input);
            if (allowRule != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash content prefix allow 命中（CC bashPermissions.ts:1129-1139）: rule={} command={}",
                        com.nexusai.application.agent.permission.check.RuleQuery
                            .ruleToString(allowRule), abbreviate(command, 50));
                }
                return new PermissionResult.Allow(
                    input,
                    new PermissionDecisionReason.Rule(allowRule),
                    null, false, null, null);
            }
        }
        // 3.6 sed 约束 + mode 分支 + operator 检查 · 对齐 CC bashToolCheckPermission
        //     (bashPermissions.ts:1141-1151)：deny → allow-rule → path 约束(3.4, 已补齐)
        //     → sed(5b) → mode(6) → operator(checkCommandOperatorPermissions, mode 之后)
        //     → read-only(7) → passthrough(8)。ctx 缺失时跳过（管线外直调场景无
        //     mode/permission 上下文）。
        if (ctx != null) {
            // mode 判定用 ToolPermissionGate.modeToCcString（CC 小写字面量 acceptEdits/…），
            // 勿用 name().toLowerCase()（会得 accept_edits，错配 CC mode）。
            String ccMode = ToolPermissionGate.modeToCcString(ctx.permissionMode());
            // sed 约束层（CC 5b：危险 sed w/W/e/E → ask，acceptEdits 仅放行 -i 就地编辑）
            PermissionResult sedResult = SedValidation.checkSedConstraints(command, ccMode);
            if (!(sedResult instanceof PermissionResult.Passthrough)) {
                return sedResult;
            }
            // mode 分支（CC 6：acceptEdits + 7 filesystem 命令 → allow；bypass/dontAsk → passthrough）
            PermissionResult modeResult = BashModeValidation.checkPermissionMode(command, ccMode);
            if (!(modeResult instanceof PermissionResult.Passthrough)) {
                return modeResult;
            }
            // operator 层（CC 顶层 checkCommandOperatorPermissions：subshell/命令组 → ask；
            // 管道段剥离重定向保引号后逐段权限检查）。递归以逐段重新 checkPermissions，
            // 单段无管道 → 在 operator 处 passthrough，终止递归。
            PermissionResult opResult = operatorPermissions.checkCommandOperatorPermissions(
                command,
                seg -> this.checkPermissions(buildCommandInput(seg), ctx),
                bashIdentityCheckers);
            // [IMP-3 R4] operator all-allow 的原命令安全重检 · 对齐 CC bashPermissions.ts:1992-2056
            //   （EV-WF4-01-051）：管道段剥离重定向后逐段检查，段级 allow 可能漏掉原命令的
            //   重定向目标/危险模式（echo 'x' | xargs printf '%s' >> /tmp/file 的 >> 重定向）。
            //   all-allow 时对原命令补：1) 危险模式重检（bashCommandIsSafeAsync 等价 → ask）；
            //   2) checkPathConstraints（commandHasAnyCd 语义由 BashPathValidator 内部承担）。
            //   OPD-WF4-01-R4 拍板补实现。
            if (opResult instanceof PermissionResult.Allow) {
                PermissionResult recheck = recheckOperatorAllAllow(command, ctx);
                if (recheck != null) {
                    return recheck;
                }
            }
            if (!(opResult instanceof PermissionResult.Passthrough)) {
                return opResult;
            }
        }
        // 4. Read-only command -> Allow (input passed through + reason explains)
        //    [A7a] 接线共享表：删硬编码 9 命令名数组（DEL-A7-01a），改查
        //    ReadOnlyCommandTable.BASH_COMMAND_ALLOWLIST（对齐 CC COMMAND_ALLOWLIST
        //    readOnlyValidation.ts:128-1137，flag 级 CommandConfig）。首词提取后查表
        //    （保留旧 word-match 语义），命中且 flag 级校验全通过 → Allow(reason=首词)；
        //    否则落 Passthrough（比旧命令名级更严格，IMP-OPD-05 已拍板）。
        String trimmed = command.trim();
        String firstWord = firstWordOf(trimmed);
        ReadOnlyCommandTable.CmdletConfig readOnlyCfg = ReadOnlyCommandTable.lookupBashCommand(firstWord);
        // IMP-B2 RO-16/17（readOnlyValidation.ts:1719-1750 + 1911-1966）：git 命令的
        // -c/--exec-path/--config-env 危险 flag（RO-16）、bare-repo cwd / git-internal 路径写入 /
        // 沙箱启用下 cwd≠orig-cwd（RO-17）守卫命中 → 只读 Allow 拦截（落 Passthrough → 外层询问）。
        // 防御性接线：git 当前不在 BASH_COMMAND_ALLOWLIST（Java 偏保守 fail-closed），守卫保证
        // 即使 git/gh/docker 将来入表也不只读放行（EV-B2-029 缺失项补齐）。
        if (readOnlyCfg != null && bashFlagsReadOnly(trimmed, firstWord, readOnlyCfg)
                && !gitReadOnlyGuardBlocked(trimmed, ctx)) {
            if (log.isDebugEnabled()) {
                log.debug("Bash 只读 Allow：首词 [{}] 命中共享表 BASH_COMMAND_ALLOWLIST 且 flag 级校验通过",
                    firstWord);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("Bash read-only command: " + firstWord),
                null, false, null, null);
        }
        // 4.1 [A7c] echo 名字层简单形式回退 —— 对齐 CC isCommandReadOnly regex tier
        //     (readOnlyValidation.ts:1719)：flag 级 COMMAND_ALLOWLIST miss 后，echo 简单形式
        //     （readOnlyValidation.ts:1516 正则 + :1600 未引号展开守卫 + :1682 2>&1 预剥离）
        //     仍只读 Allow（用户拍板 A：仅回退 echo，ls/cat/pwd/head/tail/wc/find 维持
        //     Passthrough 比 CC 严格，IMP-OPD-05）。全命令正则校验防裸名字层放行写命令
        //     （cat a>b / echo a|b）。不改 Passthrough 分支。
        if (ReadOnlyCommandTable.matchesBashReadonlyEcho(trimmed)) {
            if (log.isDebugEnabled()) {
                log.debug("Bash 只读 Allow（echo 名字层回退）：[{}] 命中 CC READONLY_COMMAND_REGEXES "
                    + "echo 正则（readOnlyValidation.ts:1516）简单形式", firstWord);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("Bash read-only command: " + firstWord),
                null, false, null, null);
        }
        if (log.isDebugEnabled()) {
            log.debug("Bash 只读未 Allow：首词 [{}] {}，落 Passthrough 交上层规则",
                firstWord, readOnlyCfg == null ? "不在共享表 BASH_COMMAND_ALLOWLIST" : "flag 级校验未通过");
        }
        // 4. Other commands -> Passthrough (defer to general rules)
        //    [H14-FIX] 对齐 CC buildPendingClassifierCheck (bashPermissions.ts:1459-1476):
        //    分类器 feature 启用时把 {command, cwd, descriptions} 结构体挂到 Passthrough,
        //    供 coordinatorPendingCheck / 异步 classifier 检查消费.
        return new PermissionResult.Passthrough(
            "Bash command requires evaluation (not whitelisted as read-only, not blocked as dangerous)",
            new PermissionDecisionReason.Other("bash default passthrough"),
            java.util.List.of(),
            null,
            buildPendingClassifierCheck(command, ctx));
    }

    /**
     * [IMP-3 R4] operator all-allow 的原命令安全重检 · 对齐 CC bashPermissions.ts:1992-2056。
     *
     * <p>CC 在管道段全 allow（operator 返回 allow）时，对<b>原命令</b>补两道重检，堵住段级
     * 剥离重定向造成的绕过面：
     * <ol>
     *   <li><b>危险模式重检</b>（:2002-2036 {@code bashCommandIsSafeAsync}，Java 恒走
     *       astSubcommands===null 分支 → {@link BashSecurityValidator#check}）——反引号/
     *       {@code $()} 在重定向目标里（{@code echo x | xargs echo > `pwd`/evil.txt}）段级剥离后
     *       不可见，须在原命令上重查；ask → Ask（{@code type:'other'}）。</li>
     *   <li><b>原命令 path 约束</b>（:2045-2055 {@code checkPathConstraints} with
     *       {@code commandHasAnyCd(input.command)}，Java 由 {@link BashPathValidator#check}
     *       内部承担 compoundCommandHasCd）——{@code echo 'x' | xargs printf '%s' >> /tmp/file}
     *       的 {@code >>} 重定向目标必须校验。</li>
     * </ol>
     *
     * @param command 原命令（含重定向，未经段剥离）
     * @param ctx     工具调用上下文
     * @return 重检命中（Ask/Deny）返回该结果；两道均通过返回 null（放行 operator Allow）
     */
    private PermissionResult recheckOperatorAllAllow(String command, ToolUseContext ctx) {
        // 1. 危险模式重检（CC :2002-2036，astSubcommands===null 恒走 bashCommandIsSafeAsync）
        BashSecurityValidator.Result security = BashSecurityValidator.check(command);
        if (security.ask()) {
            if (log.isDebugEnabled()) {
                log.debug("Bash operator all-allow 危险重检命中（CC bashPermissions.ts:2006-2036）: "
                    + "checkId={} subId={} command={}", security.checkId(), security.subId(),
                    abbreviate(command, 80));
            }
            return new PermissionResult.Ask(
                security.message(),
                new PermissionDecisionReason.Other(security.message()),
                java.util.List.of(),
                null, null, null, false,
                buildPendingClassifierCheck(command, ctx),
                null);
        }
        // 2. 原命令 path 约束重检（CC :2045-2055 checkPathConstraints with commandHasAnyCd）
        if (ctx != null && ctx.permissionContext() != null) {
            Path cwd = ctx.effectiveCwd() != null
                ? ctx.effectiveCwd()
                : Path.of(fallbackCwd());
            PermissionResult pathResult = BashPathValidator.check(command, cwd, ctx.permissionContext());
            if (!(pathResult instanceof PermissionResult.Passthrough)) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash operator all-allow path 重检命中（CC bashPermissions.ts:2045-2055）: command={}",
                        abbreviate(command, 80));
                }
                return pathResult;
            }
        }
        return null;
    }

    /**
     * checkEarlyExitDeny 等价 · 对齐 CC bashPermissions.ts:1391-1415。
     *
     * <p>AST too-complex / checkSemantics 失败路径的 deny 早退：先 exact allow 覆盖
     * （CC bashToolCheckExactMatchPermission allow 分支 :991-1021，用户对该具体命令做过
     * conscious choice → Allow），再整命令 prefix deny（CC matchingRulesForInput 'prefix'
     * → matchingDenyRules[0] :1402-1412）。命中 deny → Deny（不降级为 ask）；无命中 → null
     * （调用方落 Ask(Other)）。
     *
     * <p>SECURITY（CC :1420-1424）：用户 deny 规则必须在 too-complex/语义失败时仍生效，
     * 不得把 deny 降级为 ask。ctx/permissionContext 缺失时跳过（管线外直调无规则集）。
     *
     * @param command 工具 input 的 command 字段原文
     * @param input   工具 input（exact allow 覆盖的 updatedInput）
     * @param ctx     工具权限上下文（deny/allow 桶 + mode）
     * @return Deny/Allow（exact 覆盖）或 null（落 Ask(Other)）
     */
    private PermissionResult checkEarlyExitDeny(String command, JsonNode input, ToolUseContext ctx) {
        if (ctx == null || ctx.permissionContext() == null) {
            return null;
        }
        // P0-2: deny 匹配先剥前导 `!`（CC negated_command 产出真实 argv）——防 `! rm -rf /`
        //   绕过 Bash(rm:*) deny（ast.ts:567-577）
        String matchCommand = stripNegationPrefix(command);
        // 1. exact allow 覆盖（CC :1026-1035）——用户对该具体命令做过显式 allow
        com.nexusai.application.agent.permission.PermissionRule exactAllow =
            BashRuleMatcher.matchingExactAllowRule(matchCommand, ctx.permissionContext());
        if (exactAllow != null) {
            if (log.isDebugEnabled()) {
                log.debug("Bash AST 决策链：exact allow 覆盖（CC :1026-1035）rule={} command={}",
                    com.nexusai.application.agent.permission.check.RuleQuery.ruleToString(exactAllow),
                    abbreviate(command, 60));
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Rule(exactAllow),
                null, false, null, null);
        }
        // 2. 整命令 prefix deny（CC :1402-1412）· P0-2 用剥 `!` 后的命令匹配
        com.nexusai.application.agent.permission.PermissionRule denyRule =
            BashRuleMatcher.matchingPrefixDenyRule(matchCommand, ctx.permissionContext());
        if (denyRule != null) {
            if (log.isDebugEnabled()) {
                log.debug("Bash AST 决策链：deny 早退命中（CC :1407-1412）rule={} command={}",
                    com.nexusai.application.agent.permission.check.RuleQuery.ruleToString(denyRule),
                    abbreviate(command, 60));
            }
            return new PermissionResult.Deny(
                "Permission to use Bash with command " + command + " has been denied.",
                new PermissionDecisionReason.Rule(denyRule),
                null);
        }
        return null;
    }

    /**
     * checkSemanticsDeny 等价 · 对齐 CC bashPermissions.ts:1431-1453。
     *
     * <p>checkSemantics 失败路径的 deny 强制：先 {@link #checkEarlyExitDeny}（exact allow
     * 覆盖 + 整命令 prefix deny），再对每个子命令 .text span 逐条 prefix deny
     * （CC :1438-1450，filterRulesByContentsMatchingInput 的 compound guard 在单子命令 span
     * 上不触发 → {@code Bash(eval:*)} 能匹配管道中 {@code echo foo | eval rm} 的 eval）。
     * 命中 deny → Deny；无命中 → null（调用方落 Ask(Other)）。
     *
     * <p>SECURITY（CC :1417-1424）：与 checkEarlyExitDeny 同因——用户 deny 不得降级为 ask。
     *
     * @param command     工具 input 的 command 字段原文
     * @param input       工具 input（exact allow 覆盖的 updatedInput）
     * @param ctx         工具权限上下文（deny/allow 桶 + mode）
     * @param subcommands 子命令 .text span 列表（CC astCommands）
     * @return Deny/Allow（exact 覆盖）或 null（落 Ask(Other)）
     */
    private PermissionResult checkSemanticsDeny(String command, JsonNode input, ToolUseContext ctx,
            List<String> subcommands) {
        PermissionResult earlyExit = checkEarlyExitDeny(command, input, ctx);
        if (earlyExit != null) {
            return earlyExit;
        }
        if (ctx == null || ctx.permissionContext() == null || subcommands == null) {
            return null;
        }
        for (String sub : subcommands) {
            if (sub == null || sub.isBlank()) {
                continue;
            }
            // P0-2: 子命令 span 也剥前导 `!`（CC negated_command SimpleCommand.text = 内层 span；
            //   Java .text 可能保留 `!`，此处双保险）
            com.nexusai.application.agent.permission.PermissionRule subDeny =
                BashRuleMatcher.matchingPrefixDenyRule(stripNegationPrefix(sub), ctx.permissionContext());
            if (subDeny != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash AST 决策链：checkSemanticsDeny 逐子命令 prefix deny 命中"
                        + "（CC :1444-1450）rule={} sub={} command={}",
                        com.nexusai.application.agent.permission.check.RuleQuery.ruleToString(subDeny),
                        abbreviate(sub, 40), abbreviate(command, 60));
                }
                return new PermissionResult.Deny(
                    "Permission to use Bash with command " + command + " has been denied.",
                    new PermissionDecisionReason.Rule(subDeny),
                    null);
            }
        }
        return null;
    }

    /** 提取命令首词（保留旧 word-match 语义：首词查表）。CC 用 shell-quote 取首个 token（readOnlyValidation.ts:1250-1253）。 */
    private static String firstWordOf(String trimmed) {
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.split("\\s+", 2)[0];
    }

    /**
     * [G33②] 过滤 {@code cd ${cwd}} 前缀子命令 · 对齐 CC filterCdCwdSubcommands
     * （bashPermissions.ts:1367-1382，消费点 :2152）。
     *
     * <p>CC 在 subcommand 列表（astSubcommands / splitCommand）上过滤模型常预置的
     * {@code cd <当前工作目录>} 无操作前缀（防扇出计数与子命令规则匹配被污染）；
     * Windows 上额外比较 POSIX 化路径（{@code C:\foo} → {@code C:/foo}，CC
     * {@code windowsPathToPosixPath}，:2148-2149）。
     *
     * @param rawSubcommands 原始子命令列表（null → 原样返回）
     * @param cwd            当前工作目录（null/空 → 不执行 cwd 前缀过滤）
     * @return 过滤后子命令列表
     */
    static List<String> filterCdCwdSubcommands(List<String> rawSubcommands, String cwd) {
        if (rawSubcommands == null || cwd == null || cwd.isBlank()) {
            return rawSubcommands;
        }
        String cwdMingw = cwd.replace('\\', '/');
        List<String> out = new java.util.ArrayList<>(rawSubcommands.size());
        for (String cmd : rawSubcommands) {
            if (cmd != null && (cmd.equals("cd " + cwd) || cmd.equals("cd " + cwdMingw))) {
                continue;
            }
            out.add(cmd);
        }
        return out;
    }

    /**
     * Bash COMMAND_ALLOWLIST flag 级只读校验 · 对齐 CC validateFlags
     * (utils/shell/readOnlyCommandValidation.ts:1684-1893) 的 bash 简化版。
     *
     * <p>CmdletConfig 仅持 {@code Set<String> safeFlags}（无 CC FlagArgType 类型，见
     * ReadOnlyCommandTable.CmdletConfig JavaDoc），故本 walker 为 CC validateFlags 简化：
     * <code>--</code> 后为参数结束校验（respectsDoubleDash，CC :1719-1731）、
     * <code>--flag=value</code> 取 = 前段查 safeFlags（CC :1752-1760）、combined short flags
     * （-nr）拆字符全命中（CC :1812-1830）、未知 flag → false（CC :1829）、
     * 非 flag token → 位置参数放行（CC :1886-1889）。
     *
     * <p>安全语义：
     * <ul>
     *   <li>{@code allowAllFlags=true} → 全放行（对齐 CC CommandConfig.allowAllFlags）</li>
     *   <li>{@code argLeaksValue=true} → 占位拒绝：CC additionalCommandIsDangerousCallback
     *       （readOnlyValidation.ts:41）需元素类型校验，Java CmdletConfig 无类型，保守拒绝
     *       （bash 表当前无 argLeaksValue 键，纯占位防御）</li>
     * </ul>
     *
     * <p>已知简化（登记 A7a concerns，超"只碰 BashTool.java"范围不动共享表结构）：无 FlagArgType
     * 类型校验（如 NONE flag 带 = 值）、无 xargs target 特判（CC :1703-1717）。
     * {@code $} 变量展开与 brace expansion 混淆前置守卫已补（CC readOnlyValidation.ts:1351-1368，
     * 见本方法守卫段——checkPermissions 只读 Allow 路径无 parseForReadOnly 调用，无兜底层，
     * 必须在 flag walker 内自守，F2 收口）。
     *
     * @param trimmed   去掉首尾空白的命令全文（tokens[0] == firstWord）
     * @param firstWord 命令首词（已查表命中，保留签名以对齐 CC commandName 语义）
     * @param cfg       共享表查到的 CmdletConfig（调用方保证非 null）
     */
    private static boolean bashFlagsReadOnly(String trimmed, String firstWord,
                                             ReadOnlyCommandTable.CmdletConfig cfg) {
        // IMP-B2 RO-16（readOnlyValidation.ts:1719-1750）：git -c / --exec-path / --config-env 拒。
        // 这些 flag 可注入任意 git config（core.fsmonitor/diff.external/core.gitProxy）执行任意命令，
        // 即使命令查中 COMMAND_ALLOWLIST（git=safe 全 flag 放行）也绝不只读放行（EV-B2-029 缺失补齐）。
        // 放在 allowAllFlags 早退之前——否则 git=safe() 会直接绕过 flag 校验。
        if (("git".equals(firstWord) || isNormalizedGitCommand(trimmed))) {
            if (GIT_C_FLAG.matcher(trimmed).find()
                    || GIT_EXEC_PATH.matcher(trimmed).find()
                    || GIT_CONFIG_ENV.matcher(trimmed).find()) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash 只读拒绝：git 危险 flag -c/--exec-path/--config-env"
                        + "（CC readOnlyValidation.ts:1721-1747）: {}", abbreviate(trimmed, 80));
                }
                return false;
            }
        }
        // IMP-B G8（EV-B2-137）：Windows xargs UNC/SMB 数据桥防护 · 对齐 CC
        // readOnlyValidation.ts:1201-1215 getCommandAllowlist（Windows 平台移除 xargs）。
        // `cat file | xargs cat` 中 file 内容含 UNC 路径时，xargs 把该路径喂给 cat 触发 SMB
        // 解析（路径在文件内容中，regex 检测不可见）。Windows 上 xargs 不得只读放行。
        if (IS_WINDOWS && "xargs".equals(firstWord)) {
            if (log.isDebugEnabled()) {
                log.debug("Bash 只读拒绝：Windows xargs（CC readOnlyValidation.ts:1207 UNC/SMB 数据桥），"
                    + "落 Passthrough 交权限链: {}", abbreviate(trimmed, 80));
            }
            return false;
        }
        if (cfg.allowAllFlags()) {
            return true;
        }
        if (cfg.argLeaksValue()) {
            return false; // 占位：需元素类型校验，保守拒绝（bash 表当前无此键）
        }
        String[] tokens = trimmed.split("\\s+");
        // 对齐 CC PowerShellTool/readOnlyValidation.ts:1346（callback 在 flag 校验前 invoke）+
        // BashTool/readOnlyValidation.ts:827 hostname regex 语义：附加危险回调在 token 循环前 invoke，
        // Bash 侧 hostname 挂位置参数拒绝回调（hostname NAME 设置主机名 → 拒绝，仅纯 flag 放行）。
        if (cfg.callback() != null) {
            java.util.List<String> positionalArgs = new java.util.ArrayList<>();
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].isEmpty()) {
                    positionalArgs.add(tokens[i]);
                }
            }
            if (cfg.callback().isDangerous(firstWord, positionalArgs)) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash 只读拒绝：附加危险回调判危 firstWord={} args={}", firstWord, positionalArgs);
                }
                return false;
            }
        }
        // IMP-B G8（EV-B2-133）：ps BSD-'e' 环境变量泄露防护 · 对齐 CC
        // readOnlyValidation.ts:418-429 ps additionalCommandIsDangerousCallback。
        // BSD 风格裸字母 token 含 'e'（ps axe / ps aux e → 全部进程 env 泄露）判危险；
        // UNIX 风格 -e（select all）在 safeFlags 内放行。与 CC 回调同语义：非 '-' 开头的
        // 字母 token 且含 'e' → 判危险。`ps ax"$Z"e` 混淆由下方 $ 守卫拦截（CC :1343-1346）。
        if ("ps".equals(firstWord)) {
            for (int i = 1; i < tokens.length; i++) {
                String tok = tokens[i];
                if (!tok.startsWith("-") && PS_BSD_E_MODIFIER.matcher(tok).matches()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Bash 只读拒绝：ps BSD-'e' 修饰符 [{}]（CC readOnlyValidation.ts:426，"
                            + "env 泄露）: {}", tok, abbreviate(trimmed, 80));
                    }
                    return false;
                }
            }
        }
        // [F2 收口] CC readOnlyValidation.ts:1351-1368 前置守卫（checkPermissions 只读 Allow 路径
        // 无 parseForReadOnly 兜底，must 自守）：对首词后全部 token 拒绝含 $ 变量展开 / brace
        // expansion 混淆。CC 用 shell-quote + env=>`$${env}` 回调保留 $VAR 为字面量，bash 运行时
        // 展开 → parser 差异可绕过 validateFlags（$ 前缀不走 startsWith('-') 分支）与 callback
        // （ps ax"$Z"e → 正则不匹配）。Java split("\\s+") 不解析引号，token 仍含字面 $ → 同样拦截。
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            // CC :1355-1357：任何含 $ 的 token 拒绝（变量展开不可知运行时值 → 无法验证只读安全）
            if (token.contains("$")) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash 只读拒绝：token [{}] 含 $ 变量展开（CC readOnlyValidation.ts:1355），"
                        + "只读 Allow 判定失败", token);
                }
                return false;
            }
            // CC :1366-1368：同时含 { 与 (`,` 或 `..`) 的 brace expansion 拒绝。
            // 需 { + 逗号/.. 双条件，避免误伤 stash@{0}（有 { 无 ,）、{{.State}}（无 ,）、
            // prefix-{}-suffix（无 ,）、{1..5}（有 { + ..）。
            if (token.contains("{") && (token.contains(",") || token.contains(".."))) {
                if (log.isDebugEnabled()) {
                    log.debug("Bash 只读拒绝：token [{}] 含 brace expansion（{ 且含 , 或 ..，"
                        + "CC readOnlyValidation.ts:1366），只读 Allow 判定失败", token);
                }
                return false;
            }
        }
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            if ("--".equals(token)) {
                break; // 尊重 POSIX `--`：之后为位置参数（CC validateFlags:1719-1731）
            }
            if (token.startsWith("-") && token.length() > 1) {
                String flag = token;
                int eq = token.indexOf('=');
                if (eq > 0) {
                    flag = token.substring(0, eq); // --flag=value / -flag=value（CC :1752-1753）
                }
                if (flag.startsWith("-") && !flag.startsWith("--") && flag.length() > 2) {
                    // combined short flags（-nr）· CC validateFlags:1812-1830 逐字符校验
                    boolean allSafe = true;
                    for (int j = 1; j < flag.length(); j++) {
                        if (!cfg.safeFlags().contains("-" + flag.charAt(j))) {
                            allSafe = false;
                            break;
                        }
                    }
                    if (!allSafe) {
                        return false;
                    }
                } else {
                    if (!cfg.safeFlags().contains(flag)) {
                        return false;
                    }
                }
            }
            // 非 flag token → 位置参数，放行（CC validateFlags:1886-1889）
        }
        return true;
    }

    /**
     * [H14-FIX] 构建待分类器检查结构体 · 对齐 CC buildPendingClassifierCheck
     * (bashPermissions.ts:1459-1476).
     *
     * <p>WHY: CC 在 BashTool.checkPermissions 各 ask/passthrough 分支把
     * {@code pendingClassifierCheck: buildPendingClassifierCheck(input.command, ctx)} 挂上,
     * 支撑异步 bash classifier 投机竞速 (classifier auto-approval). Java H14 升级结构体后
     * 只把第 5 参从 false 改成 null, 未移植赋值逻辑 → 生产 Ask/Passthrough 全传 null,
     * 竞速路径恒死. 本方法移植 CC 守卫:
     * <ul>
     *   <li>classifier 未启用 → null (CC :1463 isClassifierPermissionsEnabled)</li>
     *   <li>TRANSCRIPT_CLASSIFIER && mode==='auto' → null (CC :1465-1466, auto mode 自行决策)</li>
     *   <li>mode==='bypassPermissions' → null (CC :1467-1468)</li>
     *   <li>getBashPromptAllowDescriptions 为空 → null (CC :1469-1471)</li>
     *   <li>否则 → {command, cwd, descriptions} (CC :1473-1478)</li>
     * </ul>
     *
     * @param command bash 命令字符串 (input.command)
     * @param ctx     工具调用上下文 (mode 判别 + effectiveCwd)
     * @return PendingClassifierCheck 或 null (不触发分类器检查)
     */
    PermissionResult.PendingClassifierCheck buildPendingClassifierCheck(String command, ToolUseContext ctx) {
        // 1. classifier feature 未启用 → undefined (CC :1463 isClassifierPermissionsEnabled)
        //    [H14 v3 Gap③] 第一道闸用 isClassifierPermissionsEnabled 语义（BASH_CLASSIFIER 特性 =
        //    nexusai.feature.bash-classifier），不再误用 autoModeGate.isEnabled()（nexusai.auto-mode.enabled）。
        //    CC buildPendingClassifierCheck 里 isClassifierPermissionsEnabled 与 auto mode 是两维度：
        //    auto mode 只影响第二道闸（TRANSCRIPT_CLASSIFIER && mode==='auto' 跳过）。
        if (!isBashClassifierEnabled()) {
            return null;
        }
        // 2. TRANSCRIPT_CLASSIFIER && mode==='auto' → skip (auto mode 分类器自行决策, CC :1465-1466)
        PermissionMode permMode = ctx != null ? ctx.permissionMode() : PermissionMode.DEFAULT;
        if (transcriptClassifierEnabled && permMode == PermissionMode.AUTO) {
            return null;
        }
        // 3. bypassPermissions → skip (CC :1467-1468)
        if (permMode == PermissionMode.BYPASS_PERMISSIONS) {
            return null;
        }
        // 4. getBashPromptAllowDescriptions 恒空 (CC bashClassifier.ts:36-38 外部 stub 返回 []) → null (CC :1474)
        List<String> allowDescriptions = java.util.List.of();
        if (allowDescriptions.isEmpty()) {
            return null;
        }
        // 5. cwd = effectiveCwd (对齐 CC getCwd) (CC :1474)
        String cwd = ctx != null && ctx.effectiveCwd() != null
            ? ctx.effectiveCwd().toString()
            : fallbackCwd();
        return new PermissionResult.PendingClassifierCheck(command, cwd, allowDescriptions);
    }

    /** [Q-BS-4] deny/ask 二元分类结果载体（CC Promise.all([deny, ask]) 的解构产物）。 */
    private record DenyAskClassification(
            BashClassifierPermission.ClassifierResult deny,
            BashClassifierPermission.ClassifierResult ask) {}

    /**
     * [Q-BS-4] deny/ask 并行分类 · 对齐 CC bashPermissions.ts:1876
     * {@code Promise.all([hasDeny ? classifyBashCommand(...,'deny',...) : null,
     * hasAsk ? classifyBashCommand(...,'ask',...) : null])}。
     *
     * <p>CC 用 Promise.all 并行求值 deny 与 ask；Java 端 stub 同步双调（单线程顺序双调），
     * 语义等价（两结果均先求值，deny 优先判定在消费方）。LLM 通道落地时再引入真正并行。
     *
     * @param command          命令字符串
     * @param cwd              工作目录
     * @param denyDescriptions deny 描述数组（非空才调 deny 分类）
     * @param askDescriptions  ask 描述数组（非空才调 ask 分类）
     * @return deny/ask 二元结果（对应侧描述为空时为 null）
     */
    private DenyAskClassification classifyDenyAskParallel(
            String command, String cwd,
            List<String> denyDescriptions, List<String> askDescriptions) {
        BashClassifierPermission.ClassifierResult deny = null;
        BashClassifierPermission.ClassifierResult ask = null;
        if (!denyDescriptions.isEmpty()) {
            deny = dispatchClassify(command, cwd, denyDescriptions,
                BashClassifierPermission.ClassifierBehavior.deny);
        }
        if (!askDescriptions.isEmpty()) {
            ask = dispatchClassify(command, cwd, askDescriptions,
                BashClassifierPermission.ClassifierBehavior.ask);
        }
        return new DenyAskClassification(deny, ask);
    }

    /** [Q-BS-4] classifyBashCommand 分发 · 无 bean 时回退 stub（CC 外部构建 matches=false）。 */
    private BashClassifierPermission.ClassifierResult dispatchClassify(
            String command, String cwd, List<String> descriptions,
            BashClassifierPermission.ClassifierBehavior behavior) {
        if (bashCommandClassifier != null) {
            return bashCommandClassifier.classifyBashCommand(command, cwd, descriptions, behavior);
        }
        // CC bashClassifier.ts:47-50 外部 stub：恒 matches=false
        return BashClassifierPermission.ClassifierResult.notMatched("This feature is disabled");
    }

    /** 由 HasPermissionFn 的 {@code {command: seg}} 构造 {@code input} JsonNode（operator 递归用）。 */
    private static JsonNode buildCommandInput(Map<String, Object> seg) {
        Object cmd = seg.get("command");
        return JSON.createObjectNode().put("command", cmd instanceof String s ? s : String.valueOf(cmd));
    }

    /** CC isNormalizedCdCommand（bashPermissions.ts:2603-2611）：无快路径，直接
     *  stripSafeWrappers 剥 env/wrapper 前缀（timeout/time/nice/stdbuf/nohup +
     *  SAFE_ENV_VARS 白名单）→ shell-quote 首词归一化（tryParseShellCommand 等价）→
     *  tokens[0] ∈ {cd,pushd,popd}，防 `FORCE_COLOR=1 cd sub` / `timeout 10 cd` /
     *  `'cd' .claude`（引号包裹首词）绕过 multi-cd / cd+git 守卫。 */
    static boolean isNormalizedCdCommand(String command) {
        if (command == null) return false;
        String stripped = BashRuleMatcher.stripSafeWrappers(command);
        List<String> tokens = BashShellQuote.parseUnquotedTokens(stripped);
        if (!tokens.isEmpty()) {
            String cmd = tokens.get(0);
            boolean isCd = cmd.equals("cd") || cmd.equals("pushd") || cmd.equals("popd");
            if (isCd && log.isDebugEnabled() && !stripped.equals(command.trim())) {
                log.debug("BashTool: wrapper/env 前缀掩蔽 cd 已被剥离并命中, 原命令=[{}] 剥离后=[{}]", command, stripped);
            }
            return isCd;
        }
        return CD_OR_PUSHD_POPD_PREFIX.matcher(stripped.trim()).find();
    }

    /** CC isNormalizedCdCommand 兜底正则（bashPermissions.ts:2611 {@code /^(?:cd|pushd|popd)(?:\s|$)/}）。 */
    private static final Pattern CD_OR_PUSHD_POPD_PREFIX =
        Pattern.compile("^(?:cd|pushd|popd)(?:\\s|$)");

    /**
     * CC isNormalizedGitCommand（bashPermissions.ts:2567-2580）：快路径 → stripSafeWrappers →
     * shell-quote 首词归一化（tokens[0]==='git' 或 xargs+contains('git')）→ 正则兜底。
     * 防 `'git' status`（引号包裹首词）/ `NO_COLOR=1 git status`（env 前缀）绕过裸正则。
     */
    static boolean isNormalizedGitCommand(String command) {
        if (command == null) return false;
        // 快路径：最常用形态免解析（CC :2568-2570；git\t 形态落入 parse 路径由 tokenize
        // 按 Character.isWhitespace 切分兜住）
        String t = command.trim();
        if (t.equals("git") || t.startsWith("git ")) {
            return true;
        }
        String stripped = BashRuleMatcher.stripSafeWrappers(command);
        List<String> tokens = BashShellQuote.parseUnquotedTokens(stripped);
        if (!tokens.isEmpty()) {
            String first = tokens.get(0);
            boolean git = first.equals("git")
                || (first.equals("xargs") && tokens.contains("git"));
            if (git && log.isDebugEnabled()) {
                log.debug("BashTool: git 首词归一化命中, 原命令=[{}] 剥离后=[{}] 首词=[{}]",
                    command, stripped, first);
            }
            return git;
        }
        return GIT_PREFIX.matcher(stripped.trim()).find();
    }

    /** CC isNormalizedGitCommand 兜底正则（bashPermissions.ts:2580 {@code /^git(?:\s|$)/}）。 */
    private static final Pattern GIT_PREFIX = Pattern.compile("^git(?:\\s|$)");

    // ════════════════════════════════════════════════════════════════════
    // IMP-B2 RO-16/17 git 只读守卫（readOnlyValidation.ts:1719-1750 + 1911-1966）
    // ════════════════════════════════════════════════════════════════════

    /** RO-16 git -c flag（readOnlyValidation.ts:1726 {@code /\s-c[\s=]/}）——任意 inline git config 注入。 */
    private static final Pattern GIT_C_FLAG = Pattern.compile("\\s-c[\\s=]");
    /** RO-16 git --exec-path flag（readOnlyValidation.ts:1734 {@code /\s--exec-path[\s=]/}）——可执行目录覆盖。 */
    private static final Pattern GIT_EXEC_PATH = Pattern.compile("\\s--exec-path[\\s=]");
    /** RO-16 git --config-env flag（readOnlyValidation.ts:1744 {@code /\s--config-env[\s=]/}）——env config 注入。 */
    private static final Pattern GIT_CONFIG_ENV = Pattern.compile("\\s--config-env[\\s=]");

    /** RO-17 git-internal 路径模式（readOnlyValidation.ts:1771-1776 GIT_INTERNAL_PATTERNS）。 */
    private static final List<Pattern> GIT_INTERNAL_PATTERNS = List.of(
        Pattern.compile("^HEAD$"),
        Pattern.compile("^objects(?:/|$)"),
        Pattern.compile("^refs(?:/|$)"),
        Pattern.compile("^hooks(?:/|$)"));

    /** RO-17 仅删除/就地修改不创建新文件（readOnlyValidation.ts:1788 NON_CREATING_WRITE_COMMANDS）。 */
    private static final Set<String> NON_CREATING_WRITE_COMMANDS = Set.of("rm", "rmdir", "sed");

    /**
     * IMP-B2 RO-16/17 git 只读守卫 · 对齐 CC readOnlyValidation.ts checkReadOnlyConstraints
     * （:1911-1966）+ isCommandReadOnly regex tier（:1719-1750）。
     *
     * <p>命中任一守卫 → git 命令<b>不</b>只读放行（落权限链 ask）：
     * <ol>
     *   <li><b>RO-16</b> git {@code -c}/{@code --exec-path}/{@code --config-env} 危险 flag（:1719-1750）</li>
     *   <li><b>RO-17a</b> 当前目录形如 bare/被利用 git repo（:1930-1936 isCurrentDirectoryBareGitRepo）</li>
     *   <li><b>RO-17b</b> 复合命令写 git-internal 路径且含 git（:1943-1949 commandWritesToGitInternalPaths）</li>
     *   <li><b>RO-17c</b> 沙箱启用且 cwd≠orig-cwd（:1956-1966，orig-cwd 受 sandbox denyWrite 保护）</li>
     * </ol>
     *
     * <p>cd+git 复合（:1917-1923）已由 {@code BashCommandOperatorPermissions} Check 4 拦截，
     * 本方法不重复。Java git 当前不在 BASH_COMMAND_ALLOWLIST（fail-closed），守卫为显式语义
     * + 防御（将来入表不回归）。
     *
     * @param command 命令全文
     * @param ctx     工具调用上下文（可能 null；提供 effectiveCwd 用于 bare-repo/orig-cwd 判定）
     * @return {@code true} 守卫命中（不只读放行）
     */
    private boolean gitReadOnlyGuardBlocked(String command, ToolUseContext ctx) {
        if (!isNormalizedGitCommand(command)) {
            return false;
        }
        // RO-16：git -c / --exec-path / --config-env 危险 flag（readOnlyValidation.ts:1719-1750）
        if (GIT_C_FLAG.matcher(command).find()
                || GIT_EXEC_PATH.matcher(command).find()
                || GIT_CONFIG_ENV.matcher(command).find()) {
            return true;
        }
        // RO-17a：bare/被利用 git repo cwd（readOnlyValidation.ts:1930-1936）
        Path cwd = ctx != null && ctx.effectiveCwd() != null
            ? ctx.effectiveCwd()
            : Path.of(fallbackCwd());
        if (isCurrentDirectoryBareGitRepo(cwd)) {
            return true;
        }
        // RO-17b：复合命令写 git-internal 路径且含 git（readOnlyValidation.ts:1943-1949）
        if (commandWritesToGitInternalPaths(command)) {
            return true;
        }
        // RO-17c：沙箱启用且 cwd≠orig-cwd（readOnlyValidation.ts:1956-1966）
        // 批次4 #17：sessionId 取自 ctx.sessionId（无则 RequestContext），getOriginalCwdLayer 对齐 CC getOriginalCwd。
        String sessionId = ctx != null && ctx.sessionId() != null
            ? ctx.sessionId()
            : RequestContext.sessionId();
        if (sandboxManager != null && sandboxManager.isEnabled()
                && cwdDiffersFromOriginal(cwd, sessionId)) {
            return true;
        }
        return false;
    }

    /**
     * RO-17a 当前目录是否形如 bare/被利用 git repo · 对齐 CC
     * {@code isCurrentDirectoryBareGitRepo}（utils/git.ts:876-908）。
     *
     * <p>先查 {@code .git/HEAD} 有效 → 正常 repo 非 bare；再查裸仓库指示物
     * （{@code HEAD} 文件 / {@code objects/} 目录 / {@code refs/} 目录）任一存在 → bare。
     * 防 git 把 cwd 当 gitdir 执行恶意 hooks。
     *
     * @param cwd 当前工作目录
     * @return {@code true} 当前目录形如 bare git repo
     */
    private static boolean isCurrentDirectoryBareGitRepo(Path cwd) {
        Path gitPath = cwd.resolve(".git");
        if (Files.isRegularFile(gitPath)) {
            // worktree/submodule——git 跟随 gitdir 引用，非 bare
            return false;
        }
        if (Files.isDirectory(gitPath)) {
            Path gitHeadPath = gitPath.resolve("HEAD");
            if (Files.isRegularFile(gitHeadPath)) {
                // 正常 repo——.git/HEAD 有效，git 不会回退 cwd 发现
                return false;
            }
            // .git 存在但 HEAD 非普通文件 → 落入裸仓库指示物检查
        }
        // 无有效 .git/HEAD → 检查裸仓库指示物（逐项 try/catch，一项报错不掩盖其他）
        try {
            if (Files.isRegularFile(cwd.resolve("HEAD"))) {
                return true;
            }
        } catch (Exception ignore) {
            // no HEAD
        }
        try {
            if (Files.isDirectory(cwd.resolve("objects"))) {
                return true;
            }
        } catch (Exception ignore) {
            // no objects/
        }
        try {
            if (Files.isDirectory(cwd.resolve("refs"))) {
                return true;
            }
        } catch (Exception ignore) {
            // no refs/
        }
        return false;
    }

    /**
     * RO-17b 复合命令是否写 git-internal 路径 · 对齐 CC
     * {@code commandWritesToGitInternalPaths}（readOnlyValidation.ts:1840-1864）。
     *
     * <p>拆子命令后检查两类写目标：
     * <ul>
     *   <li>路径命令（mkdir/touch/cp/mv 等 PATH_EXTRACTORS 提取的写路径）</li>
     *   <li>输出重定向（{@code echo x > hooks/pre-commit}）</li>
     * </ul>
     * 命中 {@code HEAD/objects/refs/hooks} 且命令含 git → 不只读放行
     * （防 {@code mkdir -p objects refs hooks && echo 'malicious' > hooks/pre-commit && git status}）。
     *
     * @param command 完整命令字符串
     * @return {@code true} 命令写 git-internal 路径
     */
    private static boolean commandWritesToGitInternalPaths(String command) {
        List<String> subcommands = BashParser.splitCommandDeprecated(command);
        for (String subcmd : subcommands) {
            String trimmed = subcmd.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 输出重定向（echo x > hooks/pre-commit / touch > HEAD）· 对齐 CC :1854-1860
            BashParser.OutputRedirections redirs = BashParser.extractOutputRedirectTargets(trimmed);
            for (BashParser.RedirectTarget rt : redirs.redirections()) {
                if (isGitInternalPath(rt.target())) {
                    return true;
                }
            }
            // 路径命令写目标（mkdir/touch/cp/mv → 写路径）· 对齐 CC :1846-1852 简化版：
            // 首词 ∈ 可创建命令（排除 rm/rmdir/sed）时，取非 flag 位置参数逐项查 git-internal。
            List<String> args = BashParser.extractCommandArguments(trimmed);
            if (!args.isEmpty()) {
                String base = BashParser.firstCommandName(trimmed);
                if (base != null && !NON_CREATING_WRITE_COMMANDS.contains(base)
                        && BashPathValidator.isWriteOrCreateCommand(base)) {
                    for (String arg : args) {
                        if (isGitInternalPath(arg)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * RO-17b 路径是否 git-internal（HEAD/objects/refs/hooks）· 对齐 CC
     * {@code isGitInternalPath}（readOnlyValidation.ts:1781-1785）。
     *
     * @param path 原始路径（剥前导 {@code ./} 或 {@code /}）
     * @return {@code true} 路径是 git-internal 路径
     */
    private static boolean isGitInternalPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String normalized = path.replaceAll("^\\.?/", "");
        return GIT_INTERNAL_PATTERNS.stream().anyMatch(p -> p.matcher(normalized).find());
    }

    /**
     * RO-17c 当前 cwd 是否偏离原始 cwd · 对齐 CC {@code getCwd() !== getOriginalCwd()}
     * （readOnlyValidation.ts:1956-1966）。orig-cwd 受 sandbox denyWrite 保护，沙箱启用时
     * 偏离则 git 不只读放行（防 worktree/子目录内被利用 git repo 逃逸）。
     *
     * @param cwd       当前命令执行目录（ctx.effectiveCwd 或会话 cwd 兜底）
     * @param sessionId 会话 ID（null 回落 boundProject/user.dir）
     * @return {@code true} cwd 偏离进程原始 cwd
     */
    private static boolean cwdDiffersFromOriginal(Path cwd, String sessionId) {
        if (cwd == null) {
            return false;
        }
        // 批次4 #17：original 走 getOriginalCwdLayer(sessionId)（对齐 CC readOnlyValidation.ts:1959
        //   getCwd() !== getOriginalCwd()——original = STATE.originalCwd，非 user.dir；boundProject/worktree
        //   场景 user.dir 恒不偏离会误判）。无会话回落 user.dir 零行为变化。
        Path original = Path.of(CwdResolution.getOriginalCwdLayer(sessionId));
        return !cwd.toAbsolutePath().normalize().equals(original.toAbsolutePath().normalize());
    }

}

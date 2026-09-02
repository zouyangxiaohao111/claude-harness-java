package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.command.AdvisorCommand;
import com.nexusai.application.agent.command.BriefCommand;
import com.nexusai.application.agent.command.CommitCommand;
import com.nexusai.application.agent.command.CommitPushPrCommand;
import com.nexusai.application.agent.command.CostCommand;
import com.nexusai.application.agent.command.FilesCommand;
import com.nexusai.application.agent.command.HeapDumpCommand;
import com.nexusai.application.agent.command.KeybindingsCommand;
import com.nexusai.application.agent.command.RenameCommand;
import com.nexusai.application.agent.command.ReviewRemoteService;
import com.nexusai.application.agent.command.StatuslineCommand;
import com.nexusai.application.agent.command.UltrareviewVisibilityChecker;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.skill.BundledSkillDefinition;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.KeybindingsSkill;
import com.nexusai.application.agent.skill.LocalReviewPrompt;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.PromptBlock;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.util.UndercoverCheck;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 命令注册配置 · 对齐 CC commands.ts getCommands() 合并清单（未注册命令类接线暴露给 web）。
 *
 * <p><b>WHY（探查 GAP：12 个命令 handler 类 0 生产调用方）</b>：{@code command/} 包下
 * AdvisorCommand / BriefCommand / CommitCommand / CommitPushPrCommand / CostCommand / FilesCommand /
 * HeapDumpCommand / KeybindingsCommand / RenameCommand / ReviewRemoteService / StatuslineCommand /
 * UltrareviewVisibilityChecker 均为「已实现、未接线」的孤儿类——不在 {@link BundledSkills} /
 * {@link com.nexusai.application.agent.skill.BuiltInCommands} / {@link UserInputDispatcher} 任一
 * 注册面，web 端不可见、模型不可调用、/name 无法触发。本类以 Spring {@code @Bean} 副作用模式
 * （同 {@code ToolRegistrationConfig.compactCommandRegistration}）完成接线。
 *
 * <h2>接线通道（按 CC 命令类型）</h2>
 * <ul>
 *   <li><b>prompt 型</b>（CC getPromptForCommand 生成 prompt 注入上下文 → 模型执行）→ 包装成
 *       {@link BundledSkillDefinition} 注册进 {@link BundledSkills}——经 SkillRegistry.getAllCommands
 *       合并进 web GET /api/command + getModelInvocableCommands（loadedFrom=BUNDLED 放行）+ 模型经
 *       SkillTool 调用时生成 prompt（用户 /name 有 prompt）。对齐 CC commands.ts:568-579 过滤语义。</li>
 *   <li><b>local / local-jsx 型</b>（CC 本地执行）→ ① 元数据 Command（type='local'/'local-jsx'）注册进
 *       {@link BundledSkills}（对齐 CC getCommands 合并清单——CC 本地命令同样出现在命令列表），
 *       ② {@link UserInputDispatcher#registerSlashCommandResult} 注册执行 handler（前端 /name → 后端
 *       直接执行 + text 结果回传 <local-command-stdout>；local-jsx immediate 仍走
 *       {@link UserInputDispatcher#registerSlashCommand} void 面，同 /color AgentColorCommand:90 模式）。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控</b>（对齐 CC types/command.ts:214-215 isEnabled?.() ?? true）：
 * brief（KAIROS gate 默认 false）/ files（USER_TYPE==='ant'）/ keybindings（GB gate 默认 false）/
 * ultrareview（GB gate 默认 false）经 BooleanSupplier 惰性求值；其余无 gate 默认 true。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>advisor：stateReader/stateWriter/settingsUpdater 无真实持久化（AgentState 无 advisorModel 字段、
 *       Java 无 settings 写通道）→ 命令返回 CC 契约文本，但状态不跨轮持久化（{@code log.warn} 披露）。</li>
 *   <li>commit / commit-push-pr：shell 执行（CC executeShellCommandsInPrompt 替换 {@code !`cmd`}）未接线
 *       → prompt 保留字面占位（模型可经自身 Bash 工具执行，allowedTools 已注入）。</li>
 *   <li>files：readFileState cache 无 Java 等价 → 恒空列表（输出 "No files in context"）。</li>
 *   <li>heapdump：无 JS 堆转储服务 → 返回失败文案。</li>
 *   <li>keybindings：web 无编辑器打开能力 → 消息含 "Could not open in editor: ..."。</li>
 *   <li>rename：generateName 的 Haiku 生成未接线（无 LLM 调用）→ 无参 /rename 走 "Could not generate a name"；
 *       有参 /rename 真实落 transcript custom-title + agent-name。</li>
 *   <li>brief：isBriefOnly/userMsgOptIn 会话状态未接线 → 默认门控关闭，handler 仅披露。</li>
 *   <li>ultrareview：无 claude.ai overage/quota 服务 → 默认 gate 关闭；开启后走 ReviewRemoteService 简化路径。</li>
 * </ul>
 */
@Configuration
public class CommandRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrationConfig.class);

    // ════════════════════════════════════════════════════════════════════════
    // 1. Bundled 命令注册（prompt 型 + local 元数据）· 对齐 CC getCommands 合并清单
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 4 个 prompt 命令 + 8 个 local 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 {@code ToolRegistrationConfig.compactCommandRegistration} 模式）。
     * 不依赖外部 bean（构造器零参），plain JUnit 缺省跳过（不参与 BundledSkillsBootstrapper 测试集）。
     */
    @Bean
    public CommandBundledRegistration commandBundledRegistration() {
        registerCommitCommand();          // CC commands/commit.ts（prompt）
        registerCommitPushPrCommand();    // CC commands/commit-push-pr.ts（prompt）
        registerStatuslineCommand();      // CC commands/statusline.tsx（prompt）
        registerReviewCommand();          // CC commands/review.ts（prompt，LOCAL_REVIEW_PROMPT）
        registerAdvisorMetadata();        // CC commands/advisor.ts（local）
        registerBriefMetadata();          // CC commands/brief.ts（local-jsx，KAIROS gate 默认 false）
        registerCostMetadata();           // CC commands/cost/index.ts（local）
        registerFilesMetadata();          // CC commands/files/index.ts（local，ant 门控）
        registerHeapDumpMetadata();       // CC commands/heapdump/index.ts（local，isHidden）
        registerKeybindingsMetadata();    // CC commands/keybindings/index.ts（local，GB gate 默认 false）
        registerRenameMetadata();         // CC commands/rename/index.ts（local-jsx）
        registerUltrareviewMetadata();    // CC commands/review.ts ultrareview（local-jsx，GB gate 默认 false）
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfig] bundled 命令注册完成：prompt 4 + local 元数据 8（BundledSkills 现 {} 条）",
                BundledSkills.count());
        }
        return new CommandBundledRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandBundledRegistration} 的注册副作用在 context refresh 时执行。 */
    public record CommandBundledRegistration() {}

    /**
     * /commit · CC commands/commit.ts（type='prompt'，:57-90）。
     *
     * <p>promptFn → {@link CommitCommand#getPromptContent(boolean, String)}。isAnt = USER_TYPE==='ant'
     * （CC :16 process.env.USER_TYPE==='ant'）；attribution 恒 null（COMMIT_ATTRIBUTION_ENABLED=false，
     * RegisterAttributionHooks:77）。isUndercover 走 {@link UndercoverCheck#isUndercover}（CC utils/undercover.ts）。
     * shell 执行（executeShellCommandsInPrompt）未接线 → 保留 {@code !`cmd`} 字面（受控差异）。
     */
    private void registerCommitCommand() {
        CommitCommand commitCommand = new CommitCommand(
            () -> UndercoverCheck.isUndercover(System.getenv("USER_TYPE"), null, null),
            () -> null);
        registerPromptSkill("commit", "Create a git commit",
            CommitCommand.ALLOWED_TOOLS, null,
            (args, ctx) -> List.of(PromptBlock.text(commitCommand.getPromptContent(
                isAnt(), null))));
    }

    /**
     * /commit-push-pr · CC commands/commit-push-pr.ts（type='prompt'，:108-156）。
     *
     * <p>promptFn → {@link CommitPushPrCommand#handle(String)}（返回 List&lt;PromptBlock&gt;）。
     * defaultBranch 读 git 默认分支（main 优先，master 兜底）；attribution 恒 null（同 /commit）；
     * undercover 走 {@link UndercoverCheck}；safeUser/user 读 env。shell 执行未接线（受控差异）。
     */
    private void registerCommitPushPrCommand() {
        CommitPushPrCommand commitPushPrCommand = new CommitPushPrCommand(
            () -> defaultBranch(),
            () -> null,                                       // enhancedPRAttribution
            () -> new CommitPushPrCommand.Attributions(null, null), // commit + pr attribution
            prompt -> prompt,                                 // shellExecutor（未接线，保留 !`cmd` 字面）
            () -> isAnt(),
            () -> UndercoverCheck.isUndercover(System.getenv("USER_TYPE"), null, null),
            () -> UndercoverCheck.getUndercoverInstructions(System.getenv("USER_TYPE")),
            () -> System.getenv("SAFEUSER"),
            () -> System.getenv("USER"));
        registerPromptSkill("commit-push-pr", "Commit, push, and open a PR",
            CommitPushPrCommand.ALLOWED_TOOLS, "creating commit and PR",
            (args, ctx) -> commitPushPrCommand.handle(args).stream()
                .map(pb -> PromptBlock.text(pb.text()))
                .toList());
    }

    /**
     * /statusline · CC commands/statusline.tsx（type='prompt'，:4-22）。
     *
     * <p>promptFn → {@link StatuslineCommand#getPromptForCommand(String)}（纯静态）。allowedTools 3 项
     * （AgentTool + Read + Edit）；progressMessage 对齐 CC statusline.tsx:9。disableNonInteractive=true
     * 无 Java 等价字段（web 无非交互模式），登记差异。
     */
    private void registerStatuslineCommand() {
        registerPromptSkill(StatuslineCommand.NAME, StatuslineCommand.DESCRIPTION,
            StatuslineCommand.ALLOWED_TOOLS, StatuslineCommand.PROGRESS_MESSAGE,
            (args, ctx) -> List.of(PromptBlock.text(StatuslineCommand.getPromptForCommand(args))));
    }

    /**
     * /review · CC commands/review.ts review（type='prompt'，:33-43）。
     *
     * <p>promptFn → {@link LocalReviewPrompt#render(String)}（LOCAL_REVIEW_PROMPT 模板）。progressMessage
     * 对齐 CC review.ts:38 'reviewing pull request'。CC review 未声明 allowedTools（模型全工具），null。
     */
    private void registerReviewCommand() {
        registerPromptSkill("review", "Review a pull request", null, "reviewing pull request",
            (args, ctx) -> List.of(PromptBlock.text(LocalReviewPrompt.render(args))));
    }

    /** prompt 命令统一注册 · BundledSkillDefinition → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerPromptSkill(String name, String description, List<String> allowedTools,
                                     String progressMessage,
                                     BiFunction<String, PromptFnContext, List<PromptBlock>> promptFn) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            name, description,
            null,        // aliases
            null,        // whenToUse
            null,        // argumentHint
            allowedTools,
            null,        // model
            null,        // disableModelInvocation（CC undefined → false）
            true,        // userInvocable（CC 命令默认 true）
            null,        // isEnabled（无 gate → 恒启用）
            null,        // hooks
            null,        // context
            null,        // agent
            null,        // files
            promptFn);
        Command command = def.toCommand();
        if (progressMessage != null) {
            command.setProgressMessage(progressMessage);
        }
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfig] registered prompt command '{}'（对齐 CC {}）",
            name, ccSourceFor(name));
    }

    // ── local 命令元数据注册（对齐 CC getCommands 合并清单）──

    /** /advisor 元数据 · CC commands/advisor.ts:96-108（type='local'，isEnabled=canUserConfigureAdvisor）。 */
    private void registerAdvisorMetadata() {
        registerLocalMetadata("advisor", "local", "Configure the advisor model", "[<model>|off]", () -> true, false);
    }

    /**
     * /brief 元数据 · CC commands/brief.ts:47-128（type='local-jsx'，isEnabled=KAIROS feature &&
     * enable_slash_command 默认 false，brief.ts:51-56 + DEFAULT_BRIEF_CONFIG.enable_slash_command=false :29-31）。
     * Java 无 KAIROS feature → 默认 false（对齐 CC 生产默认不注册门控语义）。
     */
    private void registerBriefMetadata() {
        registerLocalMetadata(BriefCommand.NAME, "local-jsx", BriefCommand.DESCRIPTION, null, () -> false, false);
    }

    /** /cost 元数据 · CC commands/cost/index.ts:8-21（type='local'，无 isEnabled gate）。 */
    private void registerCostMetadata() {
        registerLocalMetadata("cost", "local", "Show the total cost and duration of the current session", null, null, false);
    }

    /** /files 元数据 · CC commands/files/index.ts:3-10（type='local'，isEnabled=USER_TYPE==='ant'）。 */
    private void registerFilesMetadata() {
        registerLocalMetadata("files", "local", "List all files currently in context", null, () -> isAnt(), false);
    }

    /** /heapdump 元数据 · CC commands/heapdump/index.ts:3-10（type='local'，isHidden=true）。 */
    private void registerHeapDumpMetadata() {
        registerLocalMetadata("heapdump", "local", "Dump the JS heap to ~/Desktop", null, null, true);
    }

    /** /keybindings 元数据 · CC commands/keybindings/index.ts:4-12（type='local'，isEnabled=isKeybindingCustomizationEnabled 默认 false）。 */
    private void registerKeybindingsMetadata() {
        registerLocalMetadata("keybindings", "local", "Open or create your keybindings configuration file", null,
            KeybindingsSkill::isKeybindingCustomizationEnabled, false);
    }

    /** /rename 元数据 · CC commands/rename/index.ts:3-10（type='local-jsx'，immediate=true，无 gate）。 */
    private void registerRenameMetadata() {
        registerLocalMetadata("rename", "local-jsx", "Rename the current conversation", "[name]", null, false);
    }

    /** /ultrareview 元数据 · CC commands/review.ts:48-54（type='local-jsx'，isEnabled=isUltrareviewEnabled 默认 false）。 */
    private void registerUltrareviewMetadata() {
        registerLocalMetadata("ultrareview", "local-jsx",
            "~10–20 min · Finds and verifies bugs in your branch. Runs in NexusAI on the web.",
            null, ultrareviewGate(), false);
    }

    /** local 命令元数据统一注册 · Command(type) → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerLocalMetadata(String name, String type, String description,
                                       String argumentHint, BooleanSupplier isEnabled, boolean isHidden) {
        Command command = new Command();
        command.setName(name);
        command.setType(type);
        command.setDescription(description);
        if (argumentHint != null) {
            command.setArgumentHint(argumentHint);
        }
        command.setIsEnabled(isEnabled);
        command.setIsHidden(isHidden);
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfig] registered local command metadata '{}' (type={}, enabled={}, hidden={})",
            name, type, isEnabled != null ? isEnabled.getAsBoolean() : true, isHidden);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Local 命令执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 8 个 local 命令的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>type=local 命令（advisor/cost/files/heapdump/keybindings）经
     * {@link UserInputDispatcher#registerSlashCommandResult} 注册（text 结果回传
     * <local-command-stdout>，Fix-P1 修复对用户静默）；local-jsx 命令（brief/rename/ultrareview）
     * 仍走 {@link UserInputDispatcher#registerSlashCommand} void 面（CC local-jsx 语义降级）。
     * 前端 /name 输入 → 拦截器 local 分支 → dispatchResult → 命名 result handler 执行。
     * CostTracker / SessionAgentStateRegistry 为 @Component bean；plain JUnit 缺省 null →
     * handler 内空安全回退。
     */
    @Bean
    public CommandLocalSlashRegistration commandLocalSlashRegistration(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) CostTracker costTracker,
            @Autowired(required = false) SessionAgentStateRegistry sessionAgentStateRegistry) {
        if (dispatcher == null) {
            log.warn("[CommandRegistrationConfig] UserInputDispatcher 未注入，local 命令执行 handler 注册跳过");
            return new CommandLocalSlashRegistration();
        }
        registerAdvisorHandler(dispatcher, sessionAgentStateRegistry);
        registerBriefHandler(dispatcher);
        registerCostHandler(dispatcher, costTracker);
        registerFilesHandler(dispatcher);
        registerHeapDumpHandler(dispatcher);
        registerKeybindingsHandler(dispatcher);
        registerRenameHandler(dispatcher, sessionAgentStateRegistry);
        registerUltrareviewHandler(dispatcher);
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfig] local 命令执行 handler 注册完成：advisor/brief/cost/files/heapdump/keybindings/rename/ultrareview");
        }
        return new CommandLocalSlashRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandLocalSlashRegistration} 的注册副作用在 context refresh 时执行。 */
    public record CommandLocalSlashRegistration() {}

    /**
     * /advisor handler · CC commands/advisor.ts:16-94 call。
     *
     * <p>构造 {@link AdvisorCommand}（生产 env）+ handle(args) → CommandResult 文本。受控差异：
     * stateReader 读会话 AgentState.currentModel（advisorModel 恒 null）；stateWriter/settingsUpdater
     * 无持久化通道（AgentState 无 advisorModel 字段）→ log.warn 披露。
     */
    private void registerAdvisorHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry registry) {
        AdvisorCommand advisor = new AdvisorCommand(
            () -> true,       // canUserConfigureAdvisor（web 默认允许）
            s -> s,           // normalizeModel（identity；无 API-string normalize 等价，登记差异）
            s -> s,           // parseUserSpecifiedModel（identity）
            s -> new AdvisorCommand.ModelValidation(true, null), // validateModel（恒通过；无真实模型库校验）
            s -> true,        // isValidAdvisorModel
            s -> true,        // modelSupportsAdvisor
            () -> currentModel(registry),   // defaultMainLoopModel（CC getDefaultMainLoopModelSetting）
            () -> advisorAppState(registry),// stateReader（CC context.getAppState()）
            s -> {
                // CC context.setAppState(...)：AgentState 无 advisorModel 字段 → 仅披露
                log.warn("[CommandRegistrationConfig] /advisor 状态写入未持久化（AgentState 无 advisorModel 字段，受控差异）");
            },
            (source, key, value) -> {
                // CC updateSettingsForSource('userSettings', ...)：Java 无 userSettings 写通道 → 仅披露
                log.warn("[CommandRegistrationConfig] /advisor settings 更新未持久化（source={} key={}，受控差异）",
                    source, key);
            });
        dispatcher.registerSlashCommandResult("advisor", args -> {
            if (!advisor.isEnabled()) {
                log.warn("[CommandRegistrationConfig] /advisor 被 isEnabled 门控关闭，返回说明（fail loud）");
                return UserInputDispatcher.LocalCommandResult.text(
                    "Advisor 命令被 isEnabled 门控关闭（web 端无开启通道，受控差异）。");
            }
            AdvisorCommand.CommandResult result = advisor.handle(args);
            log.info("[CommandRegistrationConfig] /advisor 执行完成: {}", result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfig] /advisor 已注册为生产 slash command result handler（对齐 CC commands/advisor.ts call，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /brief handler · CC commands/brief.ts:59-126 call（local-jsx，immediate）。
     *
     * <p>默认门控关闭（KAIROS feature + enable_slash_command=false，brief.ts:51-56）→ handler 仅披露。
     * isBriefOnly / userMsgOptIn 会话状态未接线（BriefTool 注释「后端 getUserMsgOptIn() 无进程内状态」），
     * 故开启后也无法真实切换 brief 模式 —— 受控差异（fail loud）。
     */
    private void registerBriefHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand(BriefCommand.NAME, args -> {
            log.warn("[CommandRegistrationConfig] /brief 被门控关闭（KAIROS feature + enable_slash_command 默认 false，"
                + "对齐 CC brief.ts:51-56）；isBriefOnly/userMsgOptIn 会话状态未接线（受控差异）");
        });
        log.info("[CommandRegistrationConfig] /brief 已注册为生产 slash command（门控默认关，对齐 CC brief.ts DEFAULT_BRIEF_CONFIG）");
    }

    /**
     * /cost handler · CC commands/cost/cost.ts:6-24 call。
     *
     * <p>{@link CostCommand#execute(SubscriptionEnv)}。web 无 claude.ai 订阅模型 → isClaudeAISubscriber=false
     * （非订阅用户分支 → formatTotalCost）；totalCost 读 {@link CostTracker#formatCost}/{@link CostTracker#totalCostYuan}
     * （真实 bean）。costTracker 未注入 → "¥0.00" 兜底。
     */
    private void registerCostHandler(UserInputDispatcher dispatcher, CostTracker costTracker) {
        CostCommand costCommand = new CostCommand();
        dispatcher.registerSlashCommandResult("cost", args -> {
            boolean isAnt = isAnt();
            CostCommand.SubscriptionEnv env = new CostCommand.SubscriptionEnv(
                false,                       // isClaudeAISubscriber（web 无 claude.ai 订阅）
                false,                       // isUsingOverage
                isAnt,                       // isAntUser（USER_TYPE==='ant' → [ANT-ONLY] 段）
                () -> costTracker != null
                    ? costTracker.formatCost(costTracker.totalCostYuan())
                    : "¥0.00");
            CostCommand.CommandResult result = costCommand.execute(env);
            log.info("[CommandRegistrationConfig] /cost 执行完成: {}", result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfig] /cost 已注册为生产 slash command result handler（对齐 CC commands/cost/cost.ts call，totalCost 源=CostTracker，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /files handler · CC commands/files/files.ts:7-19 call。
     *
     * <p>{@link FilesCommand#execute(String, Collection, BiFunction)}。cwd 经 {@link CwdResolution#getCwd} 解析
     * （CC getCwd()）；readFileState cache 无 Java 等价 → 恒空列表（输出 "No files in context"，受控差异）。
     */
    private void registerFilesHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommandResult("files", args -> {
            String cwd = CwdResolution.getCwd(RequestContext.sessionId());
            if (cwd == null || cwd.isBlank()) {
                cwd = System.getProperty("user.dir", ".");
            }
            FilesCommand.CommandResult result = FilesCommand.execute(cwd, List.of(), (abs, c) -> abs);
            log.info("[CommandRegistrationConfig] /files 执行完成: {}（readFileState cache 未接线 → 恒空，受控差异）",
                result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfig] /files 已注册为生产 slash command result handler（对齐 CC commands/files/files.ts call，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /heapdump handler · CC commands/heapdump/heapdump.ts:3-17 call。
     *
     * <p>{@link HeapDumpCommand#execute(Supplier)}。Java web 后端无 JS 堆转储服务（CC performHeapDump 等价物
     * 缺失）→ 返回失败文案（受控差异，fail loud 不伪造）。
     */
    private void registerHeapDumpHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommandResult("heapdump", args -> {
            HeapDumpCommand.CommandResult result = HeapDumpCommand.execute(() ->
                HeapDumpCommand.HeapDumpResult.fail("Java web 后端无 JS 堆转储服务（对齐 CC performHeapDump 不可用路径，受控差异）"));
            log.info("[CommandRegistrationConfig] /heapdump 执行完成: {}", result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfig] /heapdump 已注册为生产 slash command result handler（对齐 CC commands/heapdump/heapdump.ts call，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /keybindings handler · CC commands/keybindings/keybindings.ts:11-53 call。
     *
     * <p>{@link KeybindingsCommand#execute(Environment)}。isCustomizationEnabled 走
     * {@link KeybindingsSkill#isKeybindingCustomizationEnabled}（CC GB gate，默认 false）；keybindingsPath
     * = ~/.{appName}/keybindings.json（决策 D5：只维护 nexusai，不读 claude）；writer 创建目录 + 写空模板
     * （wx 语义：已存在 → fileExists=true）；editor 未接线（web 无编辑器）→ 消息含 "Could not open in editor: ..."。
     */
    private void registerKeybindingsHandler(UserInputDispatcher dispatcher) {
        KeybindingsCommand keybindingsCommand = new KeybindingsCommand();
        dispatcher.registerSlashCommandResult("keybindings", args -> {
            KeybindingsCommand.Environment env = new KeybindingsCommand.Environment(
                KeybindingsSkill.isKeybindingCustomizationEnabled(),
                keybindingsPath().toString(),
                path -> writeKeybindingsTemplate(path),
                path -> KeybindingsCommand.EditorResult.error("web 后端无编辑器打开能力（CC editFileInEditor 不可用，受控差异）"));
            KeybindingsCommand.CommandResult result = keybindingsCommand.execute(env);
            log.info("[CommandRegistrationConfig] /keybindings 执行完成: {}", result.value());
            return UserInputDispatcher.LocalCommandResult.text(result.value());
        });
        log.info("[CommandRegistrationConfig] /keybindings 已注册为生产 slash command result handler（对齐 CC commands/keybindings/keybindings.ts call，text 结果回传 <local-command-stdout>）");
    }

    /**
     * /rename handler · CC commands/rename/rename.ts:21-87 call（local-jsx，immediate）。
     *
     * <p>{@link RenameCommand#execute(String, Env)}。有参 /rename 真实落 transcript custom-title + agent-name
     * （SessionStorage.reAppendSessionMetadata）；无参 /rename 的 Haiku 生成未接线（无 LLM 调用）→
     * "Could not generate a name"（受控差异）；bridge title 同步 best-effort no-op（web 无 bridge 会话）。
     */
    private void registerRenameHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry registry) {
        RenameCommand renameCommand = new RenameCommand();
        dispatcher.registerSlashCommand("rename", args -> {
            RenameCommand.Env env = new RenameCommand.Env(
                Teammate::isTeammate,                       // CC isTeammate()
                CommandRegistrationConfig::resolveSessionUuid,
                CommandRegistrationConfig::resolveTranscriptPath,
                (messages, signal) -> CompletableFuture.completedFuture(null), // CC generateSessionName 未接线
                (sid, name) -> { persistSessionMetadata(sid, name, true); return CompletableFuture.completedFuture(null); },
                (sid, name) -> { persistSessionMetadata(sid, name, false); return CompletableFuture.completedFuture(null); },
                (bridgeId, name) -> CompletableFuture.completedFuture(null),   // CC updateBridgeSessionTitle best-effort no-op
                name -> {
                    // CC context.setAppState(standaloneAgentContext.name)：Java 无 name 字段 → 披露
                    if (log.isDebugEnabled()) {
                        log.debug("[CommandRegistrationConfig] /rename setAppStateName 未持久化 name={}（受控差异）", name);
                    }
                },
                msg -> log.info("[CommandRegistrationConfig] /rename onDone: {}", msg));
            RenameCommand.RenameResult result = renameCommand.execute(args, env);
            log.info("[CommandRegistrationConfig] /rename 执行完成: renamed={} display={} message={}",
                result.renamed(), result.display(), result.message());
        });
        log.info("[CommandRegistrationConfig] /rename 已注册为生产 slash command（对齐 CC commands/rename/rename.ts call）");
    }

    /**
     * /ultrareview handler · CC commands/review.ts ultrareview + reviewRemote.ts（local-jsx）。
     *
     * <p>门控 = {@link UltrareviewVisibilityChecker#isUltrareviewEnabled()}（默认 false，无 GrowthBook →
     * env/system 属性可开）。开启后走 {@link ReviewRemoteService#checkOverageGate}（默认 proceed，无真实
     * quota 服务）→ {@link ReviewRemoteService#launchRemoteReview} 简化路径（无真实 teleport）。
     */
    private void registerUltrareviewHandler(UserInputDispatcher dispatcher) {
        UltrareviewVisibilityChecker checker = new UltrareviewVisibilityChecker(
            CommandRegistrationConfig::ultrareviewFeature);
        ReviewRemoteService reviewRemoteService = new ReviewRemoteService();
        dispatcher.registerSlashCommand("ultrareview", args -> {
            if (!checker.isUltrareviewEnabled()) {
                log.warn("[CommandRegistrationConfig] /ultrareview 被 isUltrareviewEnabled 门控关闭（无 GrowthBook，默认 false）");
                return;
            }
            ReviewRemoteService.OverageGate gate = reviewRemoteService.checkOverageGate(
                new ReviewRemoteService.OverageContext(
                    t -> false, t -> false,                 // isTeamSubscriber / isEnterpriseSubscriber
                    CompletableFuture.completedFuture(null),
                    CompletableFuture.completedFuture(null),
                    () -> null));
            List<ReviewRemoteService.ContentBlockParam> blocks = reviewRemoteService.launchRemoteReview(
                args,
                new ReviewRemoteService.RepoInfo("github.com", "user", "repo"), // repo 解析未接线（受控差异）
                defaultBranch(), "",
                false, 0, gate, "");
            String text = blocks.stream()
                .map(b -> "text".equals(b.type()) ? b.text() : "")
                .filter(s -> !s.isEmpty())
                .reduce((a, b) -> a + "\n" + b).orElse("");
            log.info("[CommandRegistrationConfig] /ultrareview 执行完成: {}", text);
        });
        log.info("[CommandRegistrationConfig] /ultrareview 已注册为生产 slash command（对齐 CC commands/review.ts ultrareview + reviewRemote.ts）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生产 env 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /** USER_TYPE==='ant'（CC 大小写敏感 === 'ant'；Java 侧容错增强 equalsIgnoreCase，登记差异同 BundledSkillsBootstrapper）。 */
    private static boolean isAnt() {
        return "ant".equalsIgnoreCase(System.getenv("USER_TYPE"));
    }

    /** 当前会话 AgentState.currentModel()（CC getMainLoopModelSetting）· 未注册/无模型 → "claude-sonnet-4-6" 兜底。 */
    private static String currentModel(SessionAgentStateRegistry registry) {
        if (registry == null) {
            return "claude-sonnet-4-6";
        }
        String sessionId = RequestContext.sessionId();
        if (sessionId == null) {
            return "claude-sonnet-4-6";
        }
        AgentState state = registry.get(sessionId);
        String model = state != null ? state.currentModel() : null;
        return model != null && !model.isBlank() ? model : "claude-sonnet-4-6";
    }

    /** advisor stateReader · 读会话 AgentState.currentModel()；advisorModel 恒 null（AgentState 无该字段）。 */
    private static AdvisorCommand.AppState advisorAppState(SessionAgentStateRegistry registry) {
        return new AdvisorCommand.AppState(currentModel(registry), null);
    }

    /** 从 RequestContext（MDC）解析当前会话 UUID · null = 无会话上下文（同 AgentColorCommand 模式）。 */
    private static UUID resolveSessionUuid() {
        String raw = RequestContext.sessionId();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** transcript 路径（CC getTranscriptPath · sessionStorage.ts）· 无会话 → null。
     *  <b>D3 读兼容</b>：走 {@link SessionStorage#resolveExistingTranscript} 读 nexusai
     *  自有 transcript（仅 nexusai 会话，无 claude ~/.claude/projects 回落）。 */
    private static String resolveTranscriptPath() {
        UUID sid = resolveSessionUuid();
        if (sid == null) {
            return null;
        }
        Path transcript = SessionStorage.resolveExistingTranscript(
            Path.of(resolveWorkspaceDir(sid.toString())), sid.toString());
        return transcript != null ? transcript.toString() : null;
    }

    /** 会话存档根 · 对齐 CC sessionStorage.ts getTranscriptPath()（原始项目根 ?? user.dir）。 */
    private static String resolveWorkspaceDir(String sessionId) {
        String root = CwdResolution.getOriginalCwdLayer(sessionId);
        return root != null && !root.isBlank() ? root : System.getProperty("user.dir", ".");
    }

    /** 持久化 custom-title（isTitle=true）或 agent-name（isTitle=false）到 transcript（CC saveCustomTitle/saveAgentName）。 */
    private static void persistSessionMetadata(UUID sessionId, String name, boolean isTitle) {
        if (sessionId == null) {
            return;
        }
        try {
            Path ws = Path.of(resolveWorkspaceDir(sessionId.toString()));
            SessionStorage.reAppendSessionMetadata(ws, sessionId.toString(),
                new SessionStorage.SessionMetadata(null, isTitle ? name : null, null,
                    isTitle ? null : name, null, null, null, null, null, null, null));
        } catch (Exception e) {
            log.warn("[CommandRegistrationConfig] persistSessionMetadata 失败: session={} title={} err={}",
                sessionId, isTitle, e.getMessage());
        }
    }

    /** git 默认分支 · CC getDefaultBranch（git.ts）；探测失败回落 "main"。 */
    private static String defaultBranch() {
        try {
            Process p = new ProcessBuilder("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!out.isEmpty()) {
                String branch = out.substring(out.lastIndexOf('/') + 1);
                if (!branch.isBlank()) {
                    return branch;
                }
            }
        } catch (Exception ignored) {
            // 非 git 仓库 / git 不可用 → 回落 "main"
        }
        return "main";
    }

    /** ~/.{appName}/keybindings.json（决策 D5：只维护 nexusai，不读 claude · CC getKeybindingsPath）。 */
    private static Path keybindingsPath() {
        return Path.of(NexusaiPaths.getAppConfigHomeDir(), "keybindings.json");
    }

    /** 写空 keybindings 模板（CC generateKeybindingsTemplate + wx exclusive）· 目标 nexusai home（D5）· 已存在 → true。 */
    private static boolean writeKeybindingsTemplate(String path) {
        try {
            Path p = Path.of(path);
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String template = """
                {
                  "$schema": "https://www.schemastore.org/claude-code-keybindings.json",
                  "bindings": []
                }
                """;
            Files.writeString(p, template, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
            return false; // 新建
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return true; // 已存在（CC EEXIST → fileExists=true）
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write keybindings template: " + e.getMessage(), e);
        }
    }

    /** ultrareview GrowthBook 特性源 · 无 GrowthBook → env/system 属性可开（NEXUSAI_FEATURE_ULTRA_REVIEW truthy）。 */
    private static Map<String, Object> ultrareviewFeature() {
        String sysProp = System.getProperty("nexusai.feature.ultra-review");
        if (sysProp != null && !sysProp.isBlank()) {
            return Boolean.parseBoolean(sysProp) ? Map.of("enabled", Boolean.TRUE) : Map.of();
        }
        String envVal = System.getenv("NEXUSAI_FEATURE_ULTRA_REVIEW");
        if (envVal != null && !envVal.isBlank()) {
            return Boolean.parseBoolean(envVal) ? Map.of("enabled", Boolean.TRUE) : Map.of();
        }
        return Map.of();
    }

    /** /ultrareview isEnabled gate · CC isUltrareviewEnabled（ultrareviewEnabled.ts:8-14）。 */
    private static BooleanSupplier ultrareviewGate() {
        return () -> new UltrareviewVisibilityChecker(CommandRegistrationConfig::ultrareviewFeature)
            .isUltrareviewEnabled();
    }

    /** CC 源文件标注（中文 JavaDoc 要求）· 供日志定位。 */
    private static String ccSourceFor(String name) {
        return switch (name) {
            case "commit" -> "commands/commit.ts";
            case "commit-push-pr" -> "commands/commit-push-pr.ts";
            case "statusline" -> "commands/statusline.tsx";
            case "review" -> "commands/review.ts";
            default -> "commands/<unknown>";
        };
    }
}

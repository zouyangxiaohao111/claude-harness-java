package com.nexusai.application.agent.config;

import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.branch.BranchService;
import com.nexusai.application.agent.command.AddDirValidator;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdateApplier;
import com.nexusai.application.agent.permission.PermissionUpdatePersister;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.skill.BundledSkillDefinition;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.PromptBlock;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.RequestContext;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * 组A（git/目录）命令注册配置 · 补 5 个命令入口接线（branch / diff / rewind / add-dir / pr-comments）。
 *
 * <p><b>WHY</b>：{@link CommandRegistrationConfig} 已完成 12 命令（commit/advisor/cost/…）接线，
 * 本组补 CC slash command 清单中剩余 git/目录相关 5 命令。不碰共享注册入口
 * （CommandDto / CommandRegistrationConfig / BundledSkillsBootstrapper），独立 @Configuration
 * 以 @Bean 副作用模式（同 CommandRegistrationConfig / ToolRegistrationConfig.compactCommandRegistration）
 * 完成接线。
 *
 * <h2>接线通道（按 CC 命令类型 · 对齐 CommandRegistrationConfig:59-69）</h2>
 * <ul>
 *   <li><b>prompt 型</b>（pr-comments · CC createMovedToPluginCommand）→ 包装成
 *       {@link BundledSkillDefinition} 注册进 {@link BundledSkills}——经 SkillRegistry.getAllCommands
 *       合并进 web GET /api/command + getModelInvocableCommands（loadedFrom=BUNDLED 放行），模型经
 *       SkillTool 调用时生成 prompt（用户 /pr-comments 有 prompt）。</li>
 *   <li><b>local / local-jsx 型</b>（branch / diff / rewind / add-dir）→ ① 元数据 Command
 *       （type='local'/'local-jsx'）注册进 {@link BundledSkills}（对齐 CC getCommands 合并清单），
 *       ② {@link UserInputDispatcher#registerSlashCommand} 注册执行 handler（前端 /name → 后端直接执行）。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控</b>（对齐 CC types/command.ts:214-215 isEnabled?.() ?? true）：本组 5 命令
 * CC 均无 isEnabled gate → 恒启用（BooleanSupplier null → Command.isCommandEnabled() 回落 enabled=TRUE）。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>branch：transcript fork 真实执行（{@link BranchService}），但「resume 切入新会话」未接线
 *       （web 无 CC context.resume，branch.ts:279-281）→ 仅生成 fork transcript + 标题，不切换会话。</li>
 *   <li>diff：CC DiffDialog 为 React 交互组件（diff.tsx:4-7），Java web 后端暴露 git status 文本
 *       （{@link GitStatusProvider}）；单文件 diff 走 {@code GitDiffFetcher}（gate 默认关）。
 *       交互 UI 属前端，后端仅披露数据。</li>
 *   <li>rewind：CC call 仅调 context.openMessageSelector()（UI 消息选择器）+ 返回 {@code {type:'skip'}}
 *       （rewind.ts:8-13）。Java web 无 openMessageSelector → handler 仅披露 + 不追加消息（对齐 skip 语义）。</li>
 *   <li>add-dir：目录校验真实执行（{@link AddDirValidator}，对齐 CC validation.ts），工作目录写入经
 *       {@link PermissionUpdatePersister} 持久化 additionalDirectories；CC 默认 session 归属（remember=false）
 *       → Java web 无 remember 表单，落 LOCAL_SETTINGS 持久化（对齐 persistPermissionUpdate localSettings
 *       通道）；权限上下文 per-turn 重建（PermissionContextBuilder 现仅注入 symlink PWD），
 *       pathGuard 生效范围受限。</li>
 *   <li>pr-comments：CC source='builtin'（createMovedToPluginCommand），经 BundledSkills 注册 source=BUNDLED
 *       （同 CommandRegistrationConfig 既有 prompt 命令模式）；USER_TYPE==='ant' 分支返回插件安装指引
 *       （对齐 createMovedToPluginCommand:28-43）。</li>
 * </ul>
 */
@Configuration
public class CommandRegistrationConfigGroupAGitDir {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrationConfigGroupAGitDir.class);

    // ════════════════════════════════════════════════════════════════════════
    // 1. Bundled 命令注册（prompt 型 + local 元数据）· 对齐 CC getCommands 合并清单
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 1 个 prompt 命令 + 4 个 local 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 CommandRegistrationConfig / ToolRegistrationConfig 模式）。
     * 不依赖外部 bean（构造器零参），plain JUnit 缺省跳过（不参与 BundledSkillsBootstrapper 测试集）。
     */
    @Bean
    public CommandBundledRegistrationGroupA commandBundledRegistrationGroupA() {
        registerPrCommentsSkill();      // CC commands/pr_comments/index.ts（prompt）
        registerBranchMetadata();       // CC commands/branch/index.ts（local-jsx）
        registerDiffMetadata();         // CC commands/diff/index.ts（local-jsx）
        registerRewindMetadata();       // CC commands/rewind/index.ts（local）
        registerAddDirMetadata();       // CC commands/add-dir/index.ts（local-jsx）
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigGroupAGitDir] bundled 命令注册完成：prompt 1 + local 元数据 4（BundledSkills 现 {} 条）",
                BundledSkills.count());
        }
        return new CommandBundledRegistrationGroupA();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandBundledRegistrationGroupA} 的注册副作用在 context refresh 时执行。 */
    public record CommandBundledRegistrationGroupA() {}

    /**
     * /pr-comments · CC commands/pr_comments/index.ts（type='prompt'，
     * {@code createMovedToPluginCommand} :20-69）。
     *
     * <p>命令已迁移至插件（marketplace private 期间保留内建 prompt）。promptFn → CC
     * {@code getPromptWhileMarketplaceIsPrivate}（:31-66）：ant 用户（USER_TYPE==='ant'）返回插件
     * 安装指引（:28-43）；其余用户返回 gh CLI 拉取 PR 评论的完整指令。progressMessage='fetching PR
     * comments'（:24）。source：CC 'builtin' → Java BundledSkills 注册 source=BUNDLED（受控差异）。
     * Java 无 gh CLI 校验 → 由模型经自身 Bash 工具执行 gh 命令（allowedTools 含 Bash，未声明 → null
     * 全工具）。
     */
    private void registerPrCommentsSkill() {
        registerPromptSkill("pr-comments", "Get comments from a GitHub pull request",
            null, "fetching PR comments",
            (args, ctx) -> List.of(PromptBlock.text(isAnt()
                ? PR_COMMENTS_ANT_PLUGIN_PROMPT
                : PR_COMMENTS_PROMPT + (args != null && !args.isBlank() ? "\nAdditional user input: " + args : ""))));
    }

    /** CC createMovedToPluginCommand:32-42 插件安装指引（USER_TYPE==='ant' 分支）。 */
    private static final String PR_COMMENTS_ANT_PLUGIN_PROMPT =
        "This command has been moved to a plugin. Tell the user:\n"
        + "\n"
        + "1. To install the plugin, run:\n"
        + "   claude plugin install pr-comments@claude-code-marketplace\n"
        + "\n"
        + "2. After installation, use /pr-comments:pr-comments to run this command\n"
        + "\n"
        + "3. For more information, see: https://github.com/anthropics/claude-code-marketplace/blob/main/pr-comments/README.md\n"
        + "\n"
        + "Do not attempt to run the command. Simply inform the user about the plugin installation.";

    /** CC getPromptWhileMarketplaceIsPrivate（pr_comments/index.ts:31-66）逐字对齐。 */
    private static final String PR_COMMENTS_PROMPT =
        "You are an AI assistant integrated into a git-based version control system. "
        + "Your task is to fetch and display comments from a GitHub pull request.\n"
        + "\n"
        + "Follow these steps:\n"
        + "\n"
        + "1. Use `gh pr view --json number,headRepository` to get the PR number and repository info\n"
        + "2. Use `gh api /repos/{owner}/{repo}/issues/{number}/comments` to get PR-level comments\n"
        + "3. Use `gh api /repos/{owner}/{repo}/pulls/{number}/comments` to get review comments. "
        + "Pay particular attention to the following fields: `body`, `diff_hunk`, `path`, `line`, etc. "
        + "If the comment references some code, consider fetching it using eg "
        + "`gh api /repos/{owner}/{repo}/contents/{path}?ref={branch} | jq .content -r | base64 -d`\n"
        + "4. Parse and format all comments in a readable way\n"
        + "5. Return ONLY the formatted comments, with no additional text\n"
        + "\n"
        + "Format the comments as:\n"
        + "\n"
        + "## Comments\n"
        + "\n"
        + "[For each comment thread:]\n"
        + "- @author file.ts#line:\n"
        + "```diff\n"
        + "[diff_hunk from the API response]\n"
        + "```\n"
        + "> quoted comment text\n"
        + "\n"
        + "[any replies indented]\n"
        + "\n"
        + "If there are no comments, return \"No comments found.\"\n"
        + "\n"
        + "Remember:\n"
        + "1. Only show the actual comments, no explanatory text\n"
        + "2. Include both PR-level and code review comments\n"
        + "3. Preserve the threading/nesting of comment replies\n"
        + "4. Show the file and line number context for code review comments\n"
        + "5. Use jq to parse the JSON responses from the GitHub API";

    /**
     * /branch · CC commands/branch/index.ts:3-16（type='local-jsx'，argumentHint '[name]'，
     * aliases: FORK_SUBAGENT feature off → ['fork']，:8-9）。
     *
     * <p>fork 当前会话 transcript → 新会话（{@link BranchService}，对齐 branch.ts）。CC feature
     * FORK_SUBAGENT 默认 off → alias 'fork' 注册；无 isEnabled gate 恒启用。
     */
    private void registerBranchMetadata() {
        registerLocalMetadata("branch", "local-jsx",
            "Create a branch of the current conversation at this point",
            "[name]", null, false, List.of("fork"));
    }

    /**
     * /diff · CC commands/diff/index.ts:2-9（type='local-jsx'，description 'View uncommitted
     * changes and per-turn diffs'）。无 isEnabled gate 恒启用。
     */
    private void registerDiffMetadata() {
        registerLocalMetadata("diff", "local-jsx",
            "View uncommitted changes and per-turn diffs", null, null, false, null);
    }

    /**
     * /rewind · CC commands/rewind/index.ts:5-14（type='local'，aliases ['checkpoint']，
     * argumentHint ''，supportsNonInteractive false）。
     *
     * <p>supportsNonInteractive=false 无 Java 等价字段（web 无非交互模式），登记差异。
     */
    private void registerRewindMetadata() {
        registerLocalMetadata("rewind", "local",
            "Restore the code and/or conversation to a previous point",
            "", null, false, List.of("checkpoint"));
    }

    /**
     * /add-dir · CC commands/add-dir/index.ts:4-11（type='local-jsx'，argumentHint '<path>'，
     * description 'Add a new working directory'）。无 isEnabled gate 恒启用。
     */
    private void registerAddDirMetadata() {
        registerLocalMetadata("add-dir", "local-jsx",
            "Add a new working directory", "<path>", null, false, null);
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
        log.info("[CommandRegistrationConfigGroupAGitDir] registered prompt command '{}'（对齐 CC commands/pr_comments/index.ts）",
            name);
    }

    /** local 命令元数据统一注册 · Command(type) → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerLocalMetadata(String name, String type, String description,
                                       String argumentHint, BooleanSupplier isEnabled,
                                       boolean isHidden, List<String> aliases) {
        Command command = new Command();
        command.setName(name);
        command.setType(type);
        command.setDescription(description);
        if (argumentHint != null) {
            command.setArgumentHint(argumentHint);
        }
        command.setIsEnabled(isEnabled);
        command.setIsHidden(isHidden);
        if (aliases != null && !aliases.isEmpty()) {
            command.setAliases(aliases);
        }
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfigGroupAGitDir] registered local command metadata '{}' (type={}, enabled={}, hidden={})",
            name, type, isEnabled != null ? isEnabled.getAsBoolean() : true, isHidden);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Local 命令执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 4 个 local 命令的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>前端 /name 输入 → UserInputDispatcher.dispatch → 命名 handler 执行（同 CommandRegistrationConfig
     * 模式）。PermissionUpdatePersister / PermissionUpdateApplier 为 @Component bean；plain JUnit 缺省
     * null → handler 内空安全回退。
     */
    @Bean
    public CommandLocalSlashRegistrationGroupA commandLocalSlashRegistrationGroupA(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) PermissionUpdatePersister permissionUpdatePersister,
            @Autowired(required = false) PermissionUpdateApplier permissionUpdateApplier) {
        if (dispatcher == null) {
            log.warn("[CommandRegistrationConfigGroupAGitDir] UserInputDispatcher 未注入，local 命令执行 handler 注册跳过");
            return new CommandLocalSlashRegistrationGroupA();
        }
        registerBranchHandler(dispatcher);
        registerDiffHandler(dispatcher);
        registerRewindHandler(dispatcher);
        registerAddDirHandler(dispatcher, permissionUpdatePersister, permissionUpdateApplier);
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigGroupAGitDir] local 命令执行 handler 注册完成：branch/diff/rewind/add-dir");
        }
        return new CommandLocalSlashRegistrationGroupA();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandLocalSlashRegistrationGroupA} 的注册副作用在 context refresh 时执行。 */
    public record CommandLocalSlashRegistrationGroupA() {}

    /**
     * /branch handler · CC commands/branch/branch.ts:222-296 call（local-jsx）。
     *
     * <p>读当前会话 transcript（{@link SessionStorage#resolveExistingTranscript}，D3 读兼容：仅 nexusai 自有 transcript，
     * 对齐 CC getTranscriptPath）→ {@link BranchService#createFork} 生成 fork transcript
     * （新 sessionId + forkedFrom 语义简化）→ {@link BranchService#getUniqueForkName} 计算唯一标题
     * （Java 侧无 searchSessionsByCustomTitle，碰撞检测恒返回候选名，受控差异）→ 日志。受控差异：
     * CC context.resume 切入新会话未接线（branch.ts:279-281），fork 文件已生成但不切换当前会话。
     */
    private void registerBranchHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand("branch", args -> {
            String sessionId = RequestContext.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                log.warn("[CommandRegistrationConfigGroupAGitDir] /branch 无会话上下文，无法读取 transcript（CC branch.ts:69 getSessionId + :78-87 读文件失败报 'No conversation to branch'）");
                return;
            }
            try {
                String workspaceDir = resolveWorkspaceDir(sessionId);
                // D3 读兼容：经 SessionStorage.resolveExistingTranscript 读 nexusai 现有 transcript（仅 nexusai，无 claude 回落）
                Path transcript = SessionStorage.resolveExistingTranscript(Path.of(workspaceDir), sessionId);
                if (transcript == null || !Files.exists(transcript)) {
                    log.warn("[CommandRegistrationConfigGroupAGitDir] /branch transcript 不存在，无法 fork: path={}（对齐 CC branch.ts:78-87 'No conversation to branch'）",
                        transcript);
                    return;
                }
                String content = Files.readString(transcript);
                String customTitle = args != null && !args.isBlank() ? args.trim() : null;
                BranchService service = new BranchService();
                BranchService.ForkResult fork = service.createFork(sessionId, workspaceDir, content, customTitle);
                // CC getUniqueForkName（branch.ts:179-220）：Java 无 searchSessionsByCustomTitle →
                // titleExists 恒 false（碰撞检测未接线，受控差异）
                String effectiveTitle = service.getUniqueForkName(fork.title(), name -> false);
                log.info("[CommandRegistrationConfigGroupAGitDir] /branch 执行完成: 新会话={} title={} forkPath={} 消息行数={}（resume 切换未接线，受控差异）",
                    fork.sessionId(), effectiveTitle, fork.forkPath(), fork.serializedMessages().size());
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfigGroupAGitDir] /branch 执行失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfigGroupAGitDir] /branch 已注册为生产 slash command（对齐 CC commands/branch/branch.ts call）");
    }

    /**
     * /diff handler · CC commands/diff/diff.tsx:3-8 call（local-jsx）。
     *
     * <p>CC DiffDialog 为 React 交互组件（渲染 uncommitted changes + per-turn diffs）。Java web 后端
     * 经 {@link GitStatusProvider#getGitStatus()}（对齐 CC getGitStatus）暴露 git status 文本；
     * cwd 经 {@link CwdResolution#getCwd} 解析。单文件 diff（GitDiffFetcher）gate 默认关（CLAUDE_CODE_REMOTE
     * && tengu_quartz_lantern），此处不强行触发。交互 UI 属前端组件，受控差异。
     */
    private void registerDiffHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand("diff", args -> {
            String cwd = CwdResolution.getCwd(RequestContext.sessionId());
            if (cwd == null || cwd.isBlank()) {
                cwd = System.getProperty("user.dir", ".");
            }
            GitStatusProvider provider = new GitStatusProvider(Path.of(cwd));
            String gitStatus = provider.getGitStatus();
            log.info("[CommandRegistrationConfigGroupAGitDir] /diff 执行完成: cwd={} hasGitStatus={}（DiffDialog 交互 UI 属前端组件，后端暴露 git status 文本，受控差异）",
                cwd, gitStatus != null);
        });
        log.info("[CommandRegistrationConfigGroupAGitDir] /diff 已注册为生产 slash command（对齐 CC commands/diff/diff.tsx call）");
    }

    /**
     * /rewind handler · CC commands/rewind/rewind.ts:4-13 call（local）。
     *
     * <p>CC call 仅调 {@code context.openMessageSelector()}（UI 打开消息选择器供用户选回退点）并返回
     * {@code {type:'skip'}}（不追加任何消息，:11-12）。Java web 无 openMessageSelector 消息选择器
     * → handler 仅披露触发（前端负责消息选择 UI），不追加消息（对齐 skip 语义，受控差异）。
     */
    private void registerRewindHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommandResult("rewind", args -> {
            log.info("[CommandRegistrationConfigGroupAGitDir] /rewind 已触发：消息选择器 UI 属前端组件（CC rewind.ts:8-13 openMessageSelector + return {type:'skip'}），后端不追加消息（受控差异）");
            // CC rewind.ts:11-12 恒 return {type:'skip'}（不追加消息）→ 无 stdout，回传 skip 对齐
            return UserInputDispatcher.LocalCommandResult.skip();
        });
        log.info("[CommandRegistrationConfigGroupAGitDir] /rewind 已注册为生产 slash command（对齐 CC commands/rewind/rewind.ts call）");
    }

    /**
     * /add-dir handler · CC commands/add-dir/add-dir.tsx:65-125 call（local-jsx）。
     *
     * <p>有参路径：{@link AddDirValidator#validateDirectoryForWorkspace}（对齐 CC validation.ts）校验 →
     * 成功 → {@link PermissionUpdatePersister} 持久化 additionalDirectories（LOCAL_SETTINGS，Java web
     * 无 remember 表单 → 对齐 persistPermissionUpdate localSettings 通道）+ {@link PermissionUpdateApplier}
     * 应用到会话权限上下文（best-effort，per-turn 重建后仅内存可见）。无参路径：CC 显示 AddWorkspaceDirectory
     * 输入表单（:112-116）→ web 需前端弹表单（受控差异，后端披露）。
     */
    private void registerAddDirHandler(UserInputDispatcher dispatcher,
                                       PermissionUpdatePersister persister,
                                       PermissionUpdateApplier applier) {
        dispatcher.registerSlashCommand("add-dir", args -> {
            String directoryPath = args != null ? args.trim() : "";
            if (directoryPath.isEmpty()) {
                log.warn("[CommandRegistrationConfigGroupAGitDir] /add-dir 缺少路径参数（CC add-dir.tsx:112-116 无路径显示 AddWorkspaceDirectory 输入表单，web 需前端弹表单，受控差异）");
                return;
            }
            // 当前会话已有工作目录（Java web 无会话级 store → 空列表，受控差异）
            List<String> currentWorkingDirs = List.of();
            AddDirValidator.Result result =
                AddDirValidator.validateDirectoryForWorkspace(directoryPath, currentWorkingDirs, AddDirValidator.DEFAULT_STAT);
            if (result instanceof AddDirValidator.SuccessResult success) {
                // CC add-dir.tsx:70-108 handleAddDirectory：destination = remember ? 'localSettings' : 'session'
                //（remember 默认 false → session 归属）；Java web 无 remember 表单 → LOCAL_SETTINGS 持久化
                PermissionUpdate.AddDirectories update =
                    new PermissionUpdate.AddDirectories(PermissionUpdate.Destination.LOCAL_SETTINGS,
                        List.of(success.absolutePath()));
                if (persister != null) {
                    persister.persist(update);
                }
                if (applier != null) {
                    // best-effort 应用到默认权限上下文（per-turn 重建，仅内存演示；pathGuard 生效受限，受控差异）
                    ToolPermissionContext applied = applier.apply(update,
                        ToolPermissionContext.strict(PermissionMode.DEFAULT));
                    log.debug("[CommandRegistrationConfigGroupAGitDir] /add-dir applyPermissionUpdate: additionalDirs={}",
                        applied.additionalWorkingDirectories().keySet());
                }
                log.info("[CommandRegistrationConfigGroupAGitDir] /add-dir 执行完成: Added {} as a working directory（CC add-dir.tsx:95-107 handleAddDirectory）",
                    success.absolutePath());
            } else {
                log.warn("[CommandRegistrationConfigGroupAGitDir] /add-dir 校验失败: {}（CC add-dir.tsx:117-121 addDirHelpMessage）",
                    AddDirValidator.addDirHelpMessage(result));
            }
        });
        log.info("[CommandRegistrationConfigGroupAGitDir] /add-dir 已注册为生产 slash command（对齐 CC commands/add-dir/add-dir.tsx call）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生产 env 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /** USER_TYPE==='ant'（CC 大小写敏感 === 'ant'；Java 侧容错增强 equalsIgnoreCase，登记差异同 CommandRegistrationConfig）。 */
    private static boolean isAnt() {
        return "ant".equalsIgnoreCase(System.getenv("USER_TYPE"));
    }

    /** 会话存档根 · 对齐 CC sessionStorage.ts getTranscriptPath()（原始项目根 ?? user.dir）。 */
    private static String resolveWorkspaceDir(String sessionId) {
        String root = CwdResolution.getOriginalCwdLayer(sessionId);
        return root != null && !root.isBlank() ? root : System.getProperty("user.dir", ".");
    }
}

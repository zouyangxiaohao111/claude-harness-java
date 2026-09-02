package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.LoadMemoryPrompt;
import com.nexusai.application.agent.subagent.SubagentEnvInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SystemPromptSection 双工厂 · 对齐 CC constants/systemPromptSections.ts 的两个工厂函数。
 *
 * <p>CC original 全量（utils/systemPromptSections.ts）：
 * <ul>
 *   <li>{@code systemPromptSection(name, compute)} —— 可缓存（cacheBreak=false）</li>
 *   <li>{@code DANGEROUS_uncachedSystemPromptSection(name, compute, _reason)} —— 易失（cacheBreak=true），reason 忽略</li>
 * </ul>
 */
public final class SystemPromptSections {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSections.class);
    /**
     * 可注入 cwd 读取缝（测试注入临时目录控制 git/worktree 用例；默认走统一入口）。
     *
     * <p>对齐 CC {@code getCwd()}（cwd.ts，per-async-context override）的测试可控性；
     * null 语义：{@link #setCwdSupplier} 传 null → 复位默认（防跨用例污染）。
     *
     * <p><b>WF-1B</b>：默认从 {@link CwdResolution#getCwd()} 取（对齐 CC env 段
     * {@code findGitRoot(getCwd())} git.ts:222 / isWorktree 同源），替代旧
     * {@code Path.of(System.getProperty("user.dir"))} 直读——绑定项目/worktree 场景取对仓库。
     */
    private static volatile java.util.function.Supplier<Path> cwdSupplier =
        () -> Path.of(CwdResolution.getCwd());

    /**
     * 测试缝：覆盖 cwd 来源。null → 复位默认（走 {@link CwdResolution#getCwd()}）。
     *
     * @param supplier cwd 提供者（null = 默认）
     */
    static void setCwdSupplier(java.util.function.Supplier<Path> supplier) {
        cwdSupplier = supplier != null ? supplier : () -> Path.of(CwdResolution.getCwd());
    }

    /** cwd 读取 · 经 {@link #cwdSupplier}（默认走 {@link CwdResolution#getCwd()}，反斜杠归一）。 */
    private static Path cwd() {
        return cwdSupplier.get();
    }

    /**
     * 会话 cwd 读取 · 显式传 sessionId（绕过 MDC）· [cwd-session 2026-08-25 修复]。
     *
     * <p><b>WHY</b>：env_info_simple 渲染在 ForkJoinPool.commonPool 线程（SystemPromptSectionRegistry
     * resolveAll 的 supplyAsync），该线程 MDC sessionId=null → {@code cwd()} 走无参
     * {@code CwdResolution.getCwd()}（读 MDC）回落 user.dir（后端启动目录）→ 系统提示
     * {@code Primary working directory} 注入错误项目（用户实测答 nexusai-backend 应为绑定项目）。
     *
     * <p>sessionId 非空 → {@code CwdResolution.getCwd(sessionId)}（override ?? sessionCwd ??
     * boundProject ?? user.dir 四层，CwdResolution.java:105-142，直接用参数不读 MDC）→ 绑定项目
     * 场景取对目录。sessionId null（web analyze 等无会话上下文）→ 回落 {@link #cwd()}（既有
     * cwdSupplier 测试缝 / MDC 兜底，保留 EnvSectionTest setCwdSupplier 用例）。
     *
     * @param sessionId 会话 ID（可空；null → 回落 {@link #cwd()}）
     * @return 会话 cwd（sessionId 非空）或兜底 cwd
     */
    private static Path cwd(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return Path.of(com.nexusai.application.agent.agent.CwdResolution.getCwd(sessionId));
        }
        return cwd();
    }

    private SystemPromptSections() {
        // 工具类，禁止实例化
    }

    /**
     * 可缓存 section 工厂 · 对齐 CC {@code systemPromptSection}
     * （CC original: {@code systemPromptSection(name: string, compute: ComputeFn): SystemPromptSection}
     * (constants/systemPromptSections.ts:20-25)）。
     *
     * <p>结果 {@code cacheBreak=false}：计算一次，/clear 或 /compact 前缓存不变化。
     *
     * @param name    唯一标识（如 "identity" / "workspace" / "tools" / "memory"）
     * @param compute 延迟求值回调（async）
     * @return 可缓存的 SystemPromptSection
     */
    public static SystemPromptSection systemPromptSection(
        String name,
        SystemPromptSection.ComputeFn compute
    ) {
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] 创建可缓存 section: name={}, cacheBreak=false", name);
        }
        return new SystemPromptSection(name, compute, false);
    }

    /**
     * 易失（每轮重算）section 工厂 · 对齐 CC {@code DANGEROUS_uncachedSystemPromptSection}
     * （CC original: {@code DANGEROUS_uncachedSystemPromptSection(name, compute, _reason): SystemPromptSection}
     * (constants/systemPromptSections.ts:32-38)）。
     *
     * <p>结果 {@code cacheBreak=true}：每轮都重新计算，值变化时打破 prompt 缓存。
     * {@code reason} 参数 CC 以 {@code _reason} 前缀标记为忽略，Java 同样忽略（仅文档语义）。
     *
     * @param name    唯一标识
     * @param compute 延迟求值回调（async）
     * @param reason  忽略 —— CC original 原样吞掉（_reason），仅说明为何需要破缓存
     * @return 易失的 SystemPromptSection（cacheBreak=true）
     */
    public static SystemPromptSection dangerousUncachedSystemPromptSection(
        String name,
        SystemPromptSection.ComputeFn compute,
        String reason
    ) {
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] 创建易失 section: name={}, cacheBreak=true（reason 忽略）", name);
        }
        return new SystemPromptSection(name, compute, true);
    }

    /**
     * 14 条目动态 section 注册清单 · 对齐 CC getSystemPrompt 的 {@code dynamicSections} 数组
     * （prompts.ts:490-554）。
     *
     * <p><b>I-12 不变量</b>：9 无条件 + 1 DANGEROUS_uncached（mcp_instructions）+ 1 feature-gated
     * （token_budget，由 {@link SystemPromptAssemblyInput#tokenBudgetEnabled()} 门控）+
     * 2 feature-gated（N/A，恒不注册）：
     * <pre>{@code
     * session_guidance / memory / ant_model_override / env_info_simple / language /
     * output_style / scratchpad / frc / summarize_tool_results          ← 9 systemPromptSection
     * mcp_instructions                                                  ← 1 DANGEROUS_uncached
     * token_budget (TOKEN_BUDGET feature)                               ← 1 feature-gated（tokenBudgetEnabled 门控）
     * numeric_length_anchors (USER_TYPE==='ant') / brief (KAIROS|KAIROS_BRIEF feature)
     *                                                                   ← 2 feature-gated 恒不注册
     * }</pre>
     *
     * <p><b>compute 全部本类实现</b>：env_info_simple 为惰性 supplier 闭包捕获 input
     * （本类实现全字段，对齐 CC computeSimpleEnvInfo prompts.ts:651-710，worktree 子弹 /
     * knowledgeCutoff / promo 3 行 / marketing 名均含，见 {@code envInfoSimpleCompute} Javadoc）；
     * ant_model_override Java 恒 null
     * （CC getAntModelOverrideSection prompts.ts:146-151，USER_TYPE!=='ant' → null）。
     * memory 走现有 {@link LoadMemoryPrompt}（memory 模块）。
     *
     * <p>每条 {@code name} 对齐 CC 动态数组字面量（prompts.ts:492/496/500/503/507/510/513/518/522/525/546）。
     *
     * @param input 组装输入（enabledTools/model/additionalWorkingDirs/mcpClients/outputStyleConfig/
     *              skillToolCommands/language/memoryLoader/tokenBudgetEnabled）
     * @return 按 CC 注册序的 10~11 条 SystemPromptSection（tokenBudgetEnabled=true 时含 token_budget；
     *         resolve 前按此序解析）
     */
    public static List<SystemPromptSection> buildDynamicSections(SystemPromptAssemblyInput input) {
        List<SystemPromptSection> sections = new ArrayList<>();
        // 1. session_guidance · CC original: systemPromptSection('session_guidance', ...) (prompts.ts:492-494)
        //    [SP-10] nonInteractiveSession 门控来源改会话列（input.nonInteractiveSession()）：
        //    runtimeDefaults(input.nonInteractiveSession()) —— cron/后台非交互会话 '!' 子弹被抑制（CC 对齐）
        sections.add(systemPromptSection("session_guidance", () ->
            CompletableFuture.completedFuture(SessionGuidanceSection.build(
                input.enabledTools(), input.skillToolCommands(),
                SessionGuidanceSection.SessionGuidanceFlags.runtimeDefaults(input.nonInteractiveSession())))));
        // 2. memory · CC original: systemPromptSection('memory', () => loadMemoryPrompt()) (prompts.ts:495-496)
        sections.add(systemPromptSection("memory", () -> memoryCompute(input)));
        // 3. ant_model_override · CC original: getAntModelOverrideSection() (prompts.ts:497-500)；Java 恒 null
        sections.add(systemPromptSection("ant_model_override", () -> CompletableFuture.completedFuture(null)));
        // 4. env_info_simple · CC original: computeSimpleEnvInfo(model, additionalWorkingDirectories) (prompts.ts:501-503)
        //    —— 本类实现全字段（对齐 CC computeSimpleEnvInfo prompts.ts:651-710）
        sections.add(systemPromptSection("env_info_simple", () -> envInfoSimpleCompute(input)));
        // 5. language · CC original: getLanguageSection(settings.language) (prompts.ts:504-506)
        sections.add(systemPromptSection("language", () -> languageCompute(input)));
        // 6. output_style · CC original: getOutputStyleSection(outputStyleConfig) (prompts.ts:507-509)
        sections.add(systemPromptSection("output_style", () -> outputStyleCompute(input)));
        // 7. mcp_instructions · CC original: DANGEROUS_uncachedSystemPromptSection('mcp_instructions', ..., reason)
        //    (prompts.ts:511-516) —— cacheBreak=true，MCP 连接/断开跨轮变化
        sections.add(dangerousUncachedSystemPromptSection("mcp_instructions", () -> mcpInstructionsCompute(input),
            "MCP servers connect/disconnect between turns"));
        // 8. scratchpad · CC original: getScratchpadInstructions() (prompts.ts:517-518 = :797-819)
        //    [SP-05] 恒 null → scratchpadCompute：input.scratchpadEnabled() 门控（resolver，null→false）
        sections.add(systemPromptSection("scratchpad", () -> scratchpadCompute(input)));
        // 9. frc · CC original: getFunctionResultClearingSection(model) (prompts.ts:519-520 = :821-839)
        //    [SP-06] 恒 null → frcCompute：input.frcEnabled() 门控（resolver，null→false）
        sections.add(systemPromptSection("frc", () -> frcCompute(input)));
        // 10. summarize_tool_results · CC original: SUMMARIZE_TOOL_RESULTS_SECTION 常量 (prompts.ts:521-525)
        sections.add(systemPromptSection("summarize_tool_results", () -> CompletableFuture.completedFuture(SUMMARIZE_TOOL_RESULTS_SECTION)));
        // 11. token_budget · CC original: feature('TOKEN_BUDGET') 门控 systemPromptSection('token_budget', ...)
        //     (prompts.ts:538-551)。文案逐字 CC prompts.ts:548（含 em-dash U+2014；直引号）。
        //     CC 注释：cached unconditionally（"When the user specifies..." 措辞使无预算时 no-op，
        //     不设 tail attachment —— first-response 与 budget-continuation 路径看不到 attachment，#21577）。
        if (input.tokenBudgetEnabled()) {
            sections.add(systemPromptSection("token_budget", () ->
                CompletableFuture.completedFuture(TOKEN_BUDGET_SECTION)));
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] buildDynamicSections 注册 {} 条（9 无条件 + 1 DANGEROUS_uncached mcp_instructions"
                    + "；token_budget 门控={}；numeric_length_anchors/brief 2 feature-gated N/A 恒不注册）",
                sections.size(), input.tokenBudgetEnabled());
        }
        return sections;
    }

    /**
     * TOKEN_BUDGET_SECTION 常量 · 对齐 CC prompts.ts:548 systemPromptSection('token_budget') 文案逐字
     * （em-dash U+2014 · 直引号 · hard minimum / automatically continue 措辞）。
     */
    private static final String TOKEN_BUDGET_SECTION =
        "When the user specifies a token target (e.g., \"+500k\", \"spend 2M tokens\", \"use 1B tokens\"), your output token count will be shown each turn. Keep working until you approach the target \u2014 plan your work to fill it productively. The target is a hard minimum, not a suggestion. If you stop early, the system will automatically continue you.";

    /**
     * SUMMARIZE_TOOL_RESULTS_SECTION 常量 · 对齐 CC prompts.ts:841。
     */
    private static final String SUMMARIZE_TOOL_RESULTS_SECTION =
        "When working with tool results, write down any important information you might need later in your response, as the original tool result may be cleared later.";

    /**
     * memory compute · 对齐 CC {@code loadMemoryPrompt()}（memdir.ts:419-490）。
     *
     * <p>依赖现有 {@link LoadMemoryPrompt}（memory 模块）；memoryLoader 未注入（null）时返回 null。
     * 空附件 → null。
     *
     * @param input 组装输入（含 memoryLoader）
     * @return 记忆文本或 null
     */
    private static CompletableFuture<String> memoryCompute(SystemPromptAssemblyInput input) {
        LoadMemoryPrompt loader = input.memoryLoader();
        if (loader == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] memory compute 跳过：memoryLoader 未注入 → null");
            }
            return CompletableFuture.completedFuture(null);
        }
        List<LoadMemoryPrompt.MemoryAttachment> attachments =
            loader.loadMemoryPromptAttachments();
        String formatted = loader.formatForSystemPrompt(attachments);
        if (formatted == null || formatted.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] memory compute 为空 → null");
            }
            return CompletableFuture.completedFuture(null);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] memory compute 完成: {} chars", formatted.length());
        }
        return CompletableFuture.completedFuture(formatted);
    }

    /**
     * env_info_simple compute · 对齐 CC {@code computeSimpleEnvInfo(model, additionalWorkingDirectories)}
     * （prompts.ts:651-710）。
     *
     * <p><b>本类实现全字段</b>（对齐 CC computeSimpleEnvInfo）：
     * <ul>
     *   <li>worktree 子弹经 {@link SessionCwdHolder#isWorktreeBound(String)}（会话级判定，对齐 CC
     *       getCurrentWorktreeSession !== null 门控，prompts.ts:675-681）——[SP-11] 原 git 级检测
     *       （GitStatusProvider.isWorktree：.git 普通文件 + gitdir 目标含 commondir）为超集偏差
     *       （手工 git worktree add 亦命中），用户已拍板改回 CC 会话级判定；git 级实现保留（死代码
     *       不删规则：CC 有 worktree.ts 对应物），主消费点已切会话级；</li>
     *   <li>isGit 经 {@link GitStatusProvider#isGit()}（findGitRoot 沿 cwd 上溯，git.ts:27-86），
     *       替换旧 Files.isDirectory(cwd/.git) 窄判定（SP-08 遗留 4）；</li>
     *   <li>knowledgeCutoff / marketing 名复用 {@link SubagentEnvInfo} 同源映射
     *       （prompts.ts:713-730 / model.ts:570-614，两通道一致，G9 关闭）。</li>
     * </ul>
     *
     * @param input 组装输入（model/additionalWorkingDirs）
     * @return {@code # Environment} 段
     */
    private static CompletableFuture<String> envInfoSimpleCompute(SystemPromptAssemblyInput input) {
        // [cwd-session 2026-08-25 修复] 用 input.sessionId() 显式解析会话 cwd（渲染在 ForkJoinPool
        // 线程无 MDC，旧 cwd() 回落 user.dir 致 Primary working directory 注入后端启动目录）。
        // sessionId null → cwd() 兜底（cwdSupplier 测试缝 / MDC）。
        Path cwdPath = cwd(input.sessionId());
        String cwd = cwdPath.toString().replace('\\', '/');
        // CC computeSimpleEnvInfo :655 Promise.all([getIsGit(), ...]) —— isGit 走 GitStatusProvider
        // walk-up（git.ts:27-86）；异常 → false（SubagentEnvInfo.isGitRepository :160-168 同款）
        boolean isGit = safeIsGit(cwdPath);
        // [SP-11] worktree 判定改回 CC 会话级：isWorktree = getCurrentWorktreeSession() !== null
        //   （prompts.ts:675-681），仅 EnterWorktree 工具进入的会话消费 '!' 子弹；sessionId null →
        //   false（无会话级 worktree 会话 = 非 worktree）。原 git 级 safeIsWorktree 已弃用
        //   （保留实现供诊断/其他消费，见 :350）。
        boolean isWorktree = com.nexusai.application.agent.agent.SessionCwdHolder.isWorktreeBound(input.sessionId());
        // CC :663-667 modelDescription：marketing 名存在 → named 形态，否则 model id 兜底；
        // null/blank model → 抑制（保持既有 blank 抑制语义）
        String modelDescription = null;
        if (input.model() != null && !input.model().isBlank()) {
            String marketingName = SubagentEnvInfo.marketingNameForModel(input.model());
            modelDescription = marketingName != null
                ? "You are powered by the model named " + marketingName
                    + ". The exact model ID is " + input.model() + "."
                : "You are powered by the model " + input.model() + ".";
        }
        // CC :669-672 knowledgeCutoffMessage（主通道无 \n\n 前缀，prompts.ts:670-671）
        String cutoff = SubagentEnvInfo.knowledgeCutoff(input.model());
        String knowledgeCutoffMessage = cutoff != null
            ? "Assistant knowledge cutoff is " + cutoff + "."
            : null;

        // envItems 顺序对齐 CC :677-703
        List<Object> envItems = new ArrayList<>();
        envItems.add("Primary working directory: " + cwd);
        if (isWorktree) {
            envItems.add("This is a git worktree — an isolated copy of the repository. Run all commands from this directory. Do NOT `cd` to the original repository root.");
        }
        envItems.add(List.of("Is a git repository: " + isGit));
        List<String> dirs = input.additionalWorkingDirs();
        if (dirs != null && !dirs.isEmpty()) {
            envItems.add("Additional working directories:");
            envItems.add(dirs);
        }
        envItems.add("Platform: " + ccPlatform());
        envItems.add(shellInfoLine());
        envItems.add("OS Version: " + ccOsVersion());
        if (modelDescription != null) {
            envItems.add(modelDescription);
        }
        if (knowledgeCutoffMessage != null) {
            envItems.add(knowledgeCutoffMessage);
        }
        // promo 3 行恒注入（CC :694-702 ant undercover 抑制分支 Java N/A → 恒注入，OPD-SP-05 已闭环）
        envItems.add(PROMO_AVAILABILITY_LINE);
        List<String> head = new ArrayList<>();
        head.add("# Environment");
        head.add("You have been invoked in the following environment: ");
        head.addAll(StaticPromptSections.prependBullets(envItems));
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] env_info_simple compute 完成: {} 行（全字段，对齐 CC computeSimpleEnvInfo）", head.size());
        }
        return CompletableFuture.completedFuture(String.join("\n", head));
    }

    /**
     * CC {@code computeSimpleEnvInfo} promo 常量区 · 对齐 prompts.ts:694-702/:118/:121-125。
     *
     * <p>ant undercover 抑制分支（USER_TYPE==='ant' && isUndercover()）Java N/A → 恒注入。
     */
    // [2026-09-01 用户拍板] 去掉 Claude 模型家族 / Opus 描述——系统提示已说明当前模型，避免模型
    //   自称 Claude；NexusAI 无 /fast 模式（原 FAST_MODE 行一并去掉）。保留 availability。
    private static final String PROMO_AVAILABILITY_LINE =
        "NexusAI is available as a desktop assistant on Windows and macOS.";

    /** isGit 判定 · GitStatusProvider walk-up（git.ts:27-86）；异常 → false（不阻断 env 块）。 */
    private static boolean safeIsGit(Path cwdPath) {
        try {
            return new GitStatusProvider(cwdPath).isGit();
        } catch (RuntimeException e) {
            log.warn("[SystemPromptSections] isGit 判定失败，按非 git 处理: cwd={} err={}", cwdPath, e.getMessage());
            return false;
        }
    }

    /**
     * worktree 判定 · GitStatusProvider isWorktree（git 级检测，见方法 Javadoc）；异常 → false。
     * <p>[SP-11] 主消费点 envInfoSimpleCompute 已切会话级 {@link SessionCwdHolder#isWorktreeBound}；
     * 本方法保留供诊断/其他消费（死代码不删规则：CC 有 worktree.ts 对应物），异常仍回落 false。
     */
    private static boolean safeIsWorktree(Path cwdPath) {
        try {
            return new GitStatusProvider(cwdPath).isWorktree();
        } catch (RuntimeException e) {
            log.warn("[SystemPromptSections] isWorktree 判定失败，按非 worktree 处理: cwd={} err={}", cwdPath, e.getMessage());
            return false;
        }
    }

    /**
     * CC {@code env.platform} 等价 · 对齐 Node process.platform（prompts.ts:702 env.platform）。
     *
     * @return "win32" / "darwin" / "linux" / 其余 os.name 小写
     */
    private static String ccPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) return "win32";
        if (os.contains("mac")) return "darwin";
        if (os.contains("linux")) return "linux";
        return os;
    }

    /**
     * CC {@code getShellInfoLine()} 等价 · 对齐 prompts.ts:727-735。
     *
     * @return Shell 行（win32 追加 Unix 语法提示）
     */
    private static String shellInfoLine() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "unknown";
        }
        String shellName = shell.contains("zsh") ? "zsh" : shell.contains("bash") ? "bash" : shell;
        if ("win32".equals(ccPlatform())) {
            return "Shell: " + shellName + " (use Unix shell syntax, not Windows — e.g., /dev/null not NUL, forward slashes in paths)";
        }
        return "Shell: " + shellName;
    }

    /**
     * CC {@code getUnameSR()} 等价 · 对齐 prompts.ts:737-748。
     *
     * @return OS 名称 + 版本（如 "Windows 11 10.0"）
     */
    private static String ccOsVersion() {
        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "");
        return (osVersion == null || osVersion.isEmpty()) ? osName : osName + " " + osVersion;
    }

    /**
     * language compute · 对齐 CC {@code getLanguageSection}（prompts.ts:154-162）。
     *
     * @param input 组装输入（language）
     * @return {@code # Language} 段；无语言偏好 → null
     */
    private static CompletableFuture<String> languageCompute(SystemPromptAssemblyInput input) {
        String language = input.language();
        if (language == null || language.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String section = "# Language\n"
            + "Always respond in " + language + ". Use " + language
            + " for all explanations, comments, and communications with the user. Technical terms and code identifiers should remain in their original form.";
        return CompletableFuture.completedFuture(section);
    }

    /**
     * output_style compute · 对齐 CC {@code getOutputStyleSection}（prompts.ts:163-168）。
     *
     * @param input 组装输入（outputStyleConfig）
     * @return {@code # Output Style: <name>} 段；配置为 null → null
     */
    private static CompletableFuture<String> outputStyleCompute(SystemPromptAssemblyInput input) {
        OutputStyleConfig config = input.outputStyleConfig();
        if (config == null) {
            return CompletableFuture.completedFuture(null);
        }
        String section = "# Output Style: " + config.name() + "\n" + config.prompt();
        return CompletableFuture.completedFuture(section);
    }

    /**
     * mcp_instructions compute · 对齐 CC {@code getMcpInstructionsSection} + {@code getMcpInstructions}
     * （prompts.ts:170-173 + :578-608）。
     *
     * <p>仅 connected 且含 instructions 的客户端生成指令块（CC :579-582 过滤）；无命中 → null。
     *
     * @param input 组装输入（mcpClients）
     * @return {@code # MCP Server Instructions} 段；无命中 → null
     */
    static CompletableFuture<String> mcpInstructionsCompute(SystemPromptAssemblyInput input) {
        List<SystemPromptAssemblyInput.McpClientInfo> clients = input.mcpClients();
        if (clients == null || clients.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> blocks = new ArrayList<>();
        for (SystemPromptAssemblyInput.McpClientInfo c : clients) {
            if (c.connected() && c.instructions() != null && !c.instructions().isBlank()) {
                blocks.add("## " + c.name() + "\n" + c.instructions());
            }
        }
        if (blocks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String section = "# MCP Server Instructions\n"
            + "\n"
            + "The following MCP servers have provided instructions for how to use their tools and resources:\n"
            + "\n"
            + String.join("\n\n", blocks);
        return CompletableFuture.completedFuture(section);
    }

    // ────────────────────────────────────────────────────────────────────────
    // [SP-05] scratchpad + [SP-06] frc section compute · 对齐 CC prompts.ts:797-839
    // ────────────────────────────────────────────────────────────────────────

    /**
     * FRC（Function Result Clearing）轻量配置 · [SP-06] 批次 F 新增。
     *
     * <p>对齐 CC getFunctionResultClearingSection 读取的 config 字段（prompts.ts:823-836：
     * {@code config.supportedModels / config.enabled / config.systemPromptSuggestSummaries /
     * config.keepRecent}）。<b>CC 真源缺陷登记</b>：getCachedMCConfig()
     * （cachedMicrocompact.ts:37-41）实际仅返回 {@code {triggerThreshold, keepRecent}}，
     * supportedModels/enabled/systemPromptSuggestSummaries 均为 undefined —— FRC section 在
     * CC 运行时恒 null（{@code !config.enabled} 判真早退）。Java 以本记录承载，默认
     * supportedModels 空 = isModelSupported 恒 false（复现 CC 恒 null 行为）。
     * 默认值（keepRecent=5 = CC KEEP_RECENT cachedMicrocompact.ts:20）待拍板登记（SP-06）。
     *
     * @param enabled                     section 启用（CC original: config.enabled；Java 由
     *                                    input.frcEnabled() 门控承载，本字段恒 true）
     * @param systemPromptSuggestSummaries 是否建议 summarize（CC original: config.systemPromptSuggestSummaries）
     * @param keepRecent                  保留最近 N 条（CC original: config.keepRecent = KEEP_RECENT=5）
     * @param supportedModels             模型支持模式（CC original: config.supportedModels；空 → 恒不命中）
     */
    public record FrCConfig(
        boolean enabled,
        boolean systemPromptSuggestSummaries,
        int keepRecent,
        List<String> supportedModels
    ) {
        /** 默认配置 · supportedModels 空（CC 死代码复现）+ keepRecent=5（CC KEEP_RECENT）。 */
        public static final FrCConfig DEFAULTS = new FrCConfig(true, true, 5, List.of());
    }

    /**
     * scratchpad 目录路径 · 对齐 CC {@code getScratchpadDir()}
     * （permissions/filesystem.ts:384-388 = {@code join(getProjectTempDir(), getSessionId(), 'scratchpad')}；
     * getProjectTempDir :376-382 = {@code join(getClaudeTempDir(), sanitizePath(getOriginalCwd())) + sep}）。
     *
     * <p>Java 等价：claudeTempDir = {@code java.io.tmpdir}/claude（CC Windows 分支
     * getClaudeTempDirName filesystem.ts:307-309 返回 'claude'）；sanitizePath 走
     * {@link com.nexusai.application.agent.memory.AutoMemPaths#sanitizePath}；originalCwd 走
     * {@link CwdResolution#getOriginalCwdLayer(String)}（会话 original cwd，CC getOriginalCwd()）。
     *
     * @param sessionId 会话 ID（CC original: getSessionId()）；null/空 → 返回 null（scratchpad 会话级，
     *                  无会话不注入）
     * @return scratchpad 目录路径；无会话/计算失败 → null
     */
    public static String getScratchpadDir(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Path claudeTempDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "claude");
            String originalCwd = CwdResolution.getOriginalCwdLayer(sessionId);
            if (originalCwd == null || originalCwd.isBlank()) {
                originalCwd = System.getProperty("user.dir", ".");
            }
            String sanitized = com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(originalCwd);
            return claudeTempDir.resolve(sanitized).resolve(sessionId).resolve("scratchpad").toString();
        } catch (Exception e) {
            log.warn("[SystemPromptSections] getScratchpadDir 计算失败，返回 null（scratchpad 段不注入）: {}", e.toString());
            return null;
        }
    }

    /**
     * scratchpad compute · 对齐 CC {@code getScratchpadInstructions()}
     * （prompts.ts:797-819：isScratchpadEnabled 门 → '# Scratchpad Directory' 段）。
     *
     * <p>门控 = input.scratchpadEnabled()（resolver，null→false）；scratchpadDir 经
     * {@link #getScratchpadDir(String)}（sessionId null → null 不注入，scratchpad 会话级）。
     * 文案逐字 CC prompts.ts:799-818。
     *
     * @param input 组装输入（scratchpadEnabled/sessionId）
     * @return {@code # Scratchpad Directory} 段；门关/无会话 → null
     */
    private static CompletableFuture<String> scratchpadCompute(SystemPromptAssemblyInput input) {
        if (!input.scratchpadEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] scratchpad compute 跳过：scratchpadEnabled=false → null（CC prompts.ts:798-799）");
            }
            return CompletableFuture.completedFuture(null);
        }
        String scratchpadDir = getScratchpadDir(input.sessionId());
        if (scratchpadDir == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] scratchpad compute 跳过：sessionId null → 不注入（scratchpad 会话级）");
            }
            return CompletableFuture.completedFuture(null);
        }
        String section = "# Scratchpad Directory\n"
            + "\n"
            + "IMPORTANT: Always use this scratchpad directory for temporary files instead of `/tmp` or other system temp directories:\n"
            + "`" + scratchpadDir + "`\n"
            + "\n"
            + "Use this directory for ALL temporary file needs:\n"
            + "- Storing intermediate results or data during multi-step tasks\n"
            + "- Writing temporary scripts or configuration files\n"
            + "- Saving outputs that don't belong in the user's project\n"
            + "- Creating working files during analysis or processing\n"
            + "- Any file that would otherwise go to `/tmp`\n"
            + "\n"
            + "Only use `/tmp` if the user explicitly requests it.\n"
            + "\n"
            + "The scratchpad directory is session-specific, isolated from the user's project, and can be used freely without permission prompts.";
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] scratchpad compute 完成: dir={}", scratchpadDir);
        }
        return CompletableFuture.completedFuture(section);
    }

    /**
     * frc compute · 对齐 CC {@code getFunctionResultClearingSection(model)}
     * （prompts.ts:821-839：feature('CACHED_MICROCOMPACT') && config.enabled &&
     * config.systemPromptSuggestSummaries && model 含 supportedPattern）。
     *
     * <p>门控 = input.frcEnabled()（resolver，null→false；无 CACHED_MICROCOMPACT feature
     * 等价物 → 门控承载）；model 支持判定 = FRC_CONFIG.supportedModels 任一 pattern 被
     * input.model() 包含（CC :825-826 {@code config.supportedModels?.some(pattern =>
     * model.includes(pattern))}）。文案逐字 CC prompts.ts:835-838。
     *
     * @param input 组装输入（frcEnabled/model）
     * @return {@code # Function Result Clearing} 段；门关/config 未启用/model 不支持 → null
     */
    private static CompletableFuture<String> frcCompute(SystemPromptAssemblyInput input) {
        if (!input.frcEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] frc compute 跳过：frcEnabled=false → null");
            }
            return CompletableFuture.completedFuture(null);
        }
        FrCConfig config = FRC_CONFIG;
        if (!config.enabled() || !config.systemPromptSuggestSummaries()) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] frc compute 跳过：config.enabled/systemPromptSuggestSummaries 关 → null（CC prompts.ts:832-834）");
            }
            return CompletableFuture.completedFuture(null);
        }
        String model = input.model();
        boolean modelSupported = false;
        if (model != null && config.supportedModels() != null) {
            for (String pattern : config.supportedModels()) {
                if (pattern != null && model.contains(pattern)) {
                    modelSupported = true;
                    break;
                }
            }
        }
        if (!modelSupported) {
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSections] frc compute 跳过：model='{}' 不匹配 supportedModels → null（CC prompts.ts:825-826/833-836）", model);
            }
            return CompletableFuture.completedFuture(null);
        }
        String section = "# Function Result Clearing\n"
            + "\n"
            + "Old tool results will be automatically cleared from context to free up space. The "
            + config.keepRecent() + " most recent results are always kept.";
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] frc compute 完成: keepRecent={}", config.keepRecent());
        }
        return CompletableFuture.completedFuture(section);
    }

    /** FRC 轻量配置 · 默认见 {@link FrCConfig#DEFAULTS}（SP-06，默认值待拍板登记）。 */
    private static final FrCConfig FRC_CONFIG = FrCConfig.DEFAULTS;
}

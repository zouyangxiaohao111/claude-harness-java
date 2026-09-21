package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.LoadMemoryPrompt;
import com.nexusai.application.agent.skill.NexusaiPaths;
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
 * <p>CC original 全量（constants/systemPromptSections.ts:20-38）：
 * <ul>
 *   <li>{@code systemPromptSection(name, compute)} —— 可缓存（cacheBreak=false）</li>
 *   <li>{@code DANGEROUS_uncachedSystemPromptSection(name, compute, _reason)} —— 易失
 *       （cacheBreak=true，只写不读）；{@code _reason} 是<b>必填形参</b>，CC 实现体忽略其值，
 *       Java 侧把「必填」落到运行期（空白 ⇒ fail loud）并额外要求段名在
 *       {@link SystemPromptCacheBreakWhitelist} 白名单内（见该工厂 javadoc）</li>
 * </ul>
 *
 * <p><b>当前实际使用（2026-09-21 · 对齐 CC 2.1.278）</b>：白名单为空表 ⇒
 * {@code dangerousUncachedSystemPromptSection} <b>无任何调用点</b>（工厂作为机制保留）；
 * {@link #buildDynamicSections} 注册的 10 条动态段<b>全部</b>走可缓存工厂。
 */
public final class SystemPromptSections {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSections.class);
    /** [批 3c] 「无会话 cwd 回落」只 WARN 一次（env 段每次组装都会求值，不得刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PROCESS_CWD_WARNED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 可注入 cwd 读取缝（测试注入临时目录控制 git/worktree 用例；默认走进程级「无会话」解析）。
     *
     * <p>对齐 CC {@code getCwd()}（cwd.ts，per-async-context override）的测试可控性；
     * null 语义：{@link #setCwdSupplier} 传 null → 复位默认（防跨用例污染）。
     *
     * <p><b>[批 3c 会话态显式化]</b>：本类为<b>静态工具</b>，<b>无会话入参</b> ⇒ 默认 supplier
     * 显式按「无会话」解析 {@link CwdResolution#getCwd(String)}{@code (null)}（跳过
     * sessionCwd/boundProject 会话层，仅 override / 进程 {@code user.dir} 层）= 旧实现
     * 「MDC 为空」分支的等价语义。对齐 CC env 段 {@code findGitRoot(getCwd())}（git.ts:222）/
     * isWorktree 同源。<b>需要会话 cwd 的调用方必须走 {@link #cwd(String)}（显式 sessionId），
     * 不得依赖本默认值</b>。
     */
    private static volatile java.util.function.Supplier<Path> cwdSupplier = defaultProcessCwdSupplier();

    /** 默认（进程级「无会话」）cwd supplier · 首次求值 WARN 一次，说明回落 user.dir 及穿参通道。 */
    private static java.util.function.Supplier<Path> defaultProcessCwdSupplier() {
        return () -> {
            if (PROCESS_CWD_WARNED.compareAndSet(false, true)) {
                log.warn("[SystemPromptSections] cwdSupplier 未注入且无会话入参 → cwd 回落进程 user.dir={}；"
                    + "如需会话 cwd 须由调用方显式传入（cwd(sessionId)）", System.getProperty("user.dir"));
            }
            return Path.of(CwdResolution.getCwdForNonSession());
        };
    }

    /**
     * 测试缝：覆盖 cwd 来源。null → 复位默认（进程级「无会话」解析 → 进程 user.dir）。
     *
     * @param supplier cwd 提供者（null = 默认）
     */
    static void setCwdSupplier(java.util.function.Supplier<Path> supplier) {
        cwdSupplier = supplier != null ? supplier : defaultProcessCwdSupplier();
    }

    /** cwd 读取 · 经 {@link #cwdSupplier}（默认按「无会话」解析 → 进程 user.dir，反斜杠归一）。 */
    private static Path cwd() {
        return cwdSupplier.get();
    }

    /**
     * 会话 cwd 读取 · 显式传 sessionId（不经任何隐式会话槽）· [cwd-session 2026-08-25 修复]。
     *
     * <p><b>WHY</b>：env_info_simple 渲染在 ForkJoinPool.commonPool 线程（SystemPromptSectionRegistry
     * resolveAll 的 supplyAsync），该线程<b>读不到会话态</b>（批 3c 前经裸 MDC 读 sessionId）→
     * {@code cwd()} 回落 user.dir（后端启动目录）→ 系统提示 {@code Primary working directory}
     * 注入错误项目（用户实测答 nexusai-backend 应为绑定项目）。
     *
     * <p>sessionId 非空 → {@code CwdResolution.getCwd(sessionId)}（override ?? sessionCwd ??
     * boundProject ?? user.dir 四层，CwdResolution.java:105-142，直接用参数）→ 绑定项目
     * 场景取对目录。sessionId null（web analyze 等无会话上下文）→ 回落 {@link #cwd()}（既有
     * cwdSupplier 测试缝 / 进程 user.dir 兜底，保留 EnvSectionTest setCwdSupplier 用例）。
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
     * <p>结果 {@code cacheBreak=true}：每轮都重新计算（<b>只写不读</b> —— 见
     * {@link SystemPromptSectionRegistry#resolveAll} 的短路条件），值变化时打破 prompt 缓存。
     *
     * <p><b>reason 的 Java 侧语义（与原实现的注释相反，已按代码改准）</b>：CC 的实现体忽略
     * {@code _reason} 的<b>值</b>，但它是<b>必填形参</b>（TS 签名级强制「必须显式标注理由」）。
     * Java 侧把这条强制<b>落到运行期</b>：{@code reason} 为 null/空白 ⇒ 直接 fail loud。
     * 值本身仍不进 {@link SystemPromptSection}（CC 的 record 也只有 name/compute/cacheBreak）。
     *
     * <p><b>重算白名单（Java 侧守卫 · 对齐 CC 2.1.278「全份提示 {@code cacheBreak:true} 计数 0」）</b>：
     * 段名必须已在 {@link SystemPromptCacheBreakWhitelist} 登记（含理由），否则 fail loud。
     * ⚠️ 该白名单<b>当前为空表（0 条）</b> ⇒ 本工厂当前对<b>任何</b>段名 fail loud；
     * 工厂 + 白名单 + record 构造器的双点守卫作为机制保留（新增易失段仍须显式登记理由），
     * ⛔ 不是「工厂已废弃可删」。
     *
     * @param name    唯一标识（必须在 {@link SystemPromptCacheBreakWhitelist} 内；
     *                ⚠️ 该表当前为 0 条 ⇒ 眼下<b>任何</b>段名都会被拒）
     * @param compute 延迟求值回调（async）
     * @param reason  重算理由（必填 · 非空白；值不落 {@link SystemPromptSection}）
     * @return 易失的 SystemPromptSection（cacheBreak=true）
     * @throws IllegalArgumentException reason 为空白，或 name 不在重算白名单内（fail loud，
     *                                  ⛔ 不允许「顺手加一个易失段」悄悄打掉前缀缓存）
     */
    public static SystemPromptSection dangerousUncachedSystemPromptSection(
        String name,
        SystemPromptSection.ComputeFn compute,
        String reason
    ) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "cacheBreak=true 的 section 必须显式标注重算理由（CC DANGEROUS_uncachedSystemPromptSection 的 "
                    + "_reason 为必填形参，systemPromptSections.ts:32-38）: name=" + name);
        }
        if (!SystemPromptCacheBreakWhitelist.isAllowed(name)) {
            throw new IllegalArgumentException(
                "cacheBreak=true 的段名不在重算白名单内（Java 侧守卫，对齐 CC 2.1.278「全份提示 cacheBreak=true 计数 0」"
                    + "的不变量）: name=" + name + ", 已登记=" + SystemPromptCacheBreakWhitelist.allowedNames()
                    + "；新增易失段必须先登记理由（SystemPromptCacheBreakWhitelist）");
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSections] 创建易失 section: name={}, cacheBreak=true（每轮重算只写不读）"
                    + " 传入理由={} 登记理由={}", name, reason, SystemPromptCacheBreakWhitelist.reasonFor(name));
        }
        return new SystemPromptSection(name, compute, true);
    }

    /**
     * 14 条目动态 section 注册清单 · 对齐 CC getSystemPrompt 的 {@code dynamicSections} 数组
     * （prompts.ts:490-554）。
     *
     * <p><b>I-12 不变量（2026-09-21 按 CC 2.1.278 口径改准 —— 原版为「+ 1 DANGEROUS_uncached」）</b>：
     * <b>10 无条件全可缓存</b> + 1 feature-gated
     * （token_budget，由 {@link SystemPromptAssemblyInput#tokenBudgetEnabled()} 门控）+
     * 2 feature-gated（N/A，恒不注册）：
     * <pre>{@code
     * session_guidance / memory / ant_model_override / env_info_simple / language /
     * output_style / mcp_instructions / scratchpad / frc / summarize_tool_results
     *                                                                   ← 10 systemPromptSection（全 cacheBreak=false）
     * token_budget (TOKEN_BUDGET feature)                               ← 1 feature-gated（tokenBudgetEnabled 门控）
     * numeric_length_anchors (USER_TYPE==='ant') / brief (KAIROS|KAIROS_BRIEF feature)
     *                                                                   ← 2 feature-gated 恒不注册
     * }</pre>
     * ⇒ <b>{@code cacheBreak=true} 计 0</b>（对齐 2.1.278 实测 {@code cacheBreak:!0}=0 处）。
     * 2.1.88 时代本条曾是「9 + 1 DANGEROUS_uncached（mcp_instructions）」，2.1.278 删掉了该段。
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
        //    ⚠️ [divergence-registered 2026-09-21] **放置分歧（有意登记，本批不改放置）**：
        //      CC 2.1.278 行为轴实测 —— 动态环境块（cwd / Is a git repository / platform / shell / OS）**不在
        //      system 数组里**，而是靠 `mid-conversation-system-2026-04-07` beta 作为 **role:"system" 的
        //      mid-conversation 消息**放在 messages 里；system[2] 里那个 `# Environment` 只是**静态模型信息段**。
        //      而本仓把它作为 **system 段**（本节）渲染 ⇒ **结构性分歧**。
        //      本批（前缀缓存头部冻结）**只做字节冻结**（会话级 section 缓存 ⇒ 跨 run 字节稳定），
        //      ⛔ **未做**「把 env 块迁到 messages 的 mid-conversation system 消息」这条放置迁移。
        //      理由：本批目标是前缀字节稳定，放置迁移属**独立的架构对齐项**，混入会放大改动面与回归风险。
        //      ⇒ 若后续要完全对齐，这是一个**已登记待做项**，不是遗漏。
        //    [2.1.88 口径] 该段在 2.1.88 源码里确为 system 段（prompts.ts:501-503），故本仓形态**对 2.1.88 成立**。
        sections.add(systemPromptSection("env_info_simple", () -> envInfoSimpleCompute(input)));
        // 5. language · CC original: getLanguageSection(settings.language) (prompts.ts:504-506)
        sections.add(systemPromptSection("language", () -> languageCompute(input)));
        // 6. output_style · CC original: getOutputStyleSection(outputStyleConfig) (prompts.ts:507-509)
        sections.add(systemPromptSection("output_style", () -> outputStyleCompute(input)));
        // 7. mcp_instructions · [C4-A2 · 2026-09-21 · 对齐 CC 2.1.278] cacheBreak=true → false（会话级冻结）
        //    [2.1.88 口径（已作废 · 留作对照）] prompts.ts:511-516
        //      DANGEROUS_uncachedSystemPromptSection('mcp_instructions', ..., 'MCP servers connect/disconnect between turns')
        //    [2.1.278 口径（当前依据）] 实测 cacheBreak:!0/cacheBreak:true 均为 0 处，且独立段名
        //      'mcp_instructions' 已不存在（11 次命中全是 mcp_instructions_delta 9 + pool_change 2）
        //      ⇒ CC 改成走 **delta 尾部附件**投递 MCP 指令，不再有「每轮重算的 system 段」。
        //    ⭐ 已知分歧（登记 · **A1 已补通道** 2026-09-21）：改为可缓存段后，**MCP 服务器在会话
        //      中途连接/断开，其指令不再即时进系统提示** —— 但 A1 已按 CC 2.1.88 形态补上
        //      **每轮 attachments 流水线上的 delta 尾部投递通道**
        //      （LlmAgentLoop 每轮 `maybeEmitMcpInstructionsDelta`，位于 date_change 之后、
        //       changed_files 之前 = CC attachments.ts:830 → :854 → :871 的相对序），
        //      指令变更经**尾部追加**的 mcp_instructions_delta 消息即时可见，本 system 段**保持
        //      会话冻结是正确的**（⛔ 不要把 cacheBreak 改回去）。
        //      [2026-09-21 去门] 上面那条「残留边界」已关闭：该通道的 gate
        //        `isMcpInstructionsDeltaEnabled()` 已按 2.1.278 整体删除（发行产物 0 命中）
        //        ⇒ delta 通道无条件每轮运行，本 system 段保持会话冻结不再有「默认配置下分歧仍在」的问题。
        //    [准确事实 · 2026-09-21 复验重读 2.1.88 真源 + 2.1.278 发行产物]
        //      ⛔ 上面「CC 门关 ⇒ 指令不即时可见」的暗示是**错的**，别据此推「本仓与 CC 同形」：
        //      2.1.88 `constants/prompts.ts:513-520` 的 mcp_instructions 段是
        //        `DANGEROUS_uncachedSystemPromptSection('mcp_instructions',
        //           () => isMcpInstructionsDeltaEnabled() ? null : getMcpInstructionsSection(mcpClients),
        //           'MCP servers connect/disconnect between turns')`
        //      而 `constants/systemPromptSections.ts:32-38` = `{name, compute, cacheBreak:true}`，
        //      `resolveSystemPromptSections`（:49-56）对 cacheBreak 段**每轮重算并覆写缓存项**
        //      ⇒ CC 门**关**时走的是「每轮重算的 system 段」通道：MCP 中途连接**仍然即时可见**，
        //      代价是 late connect 把 prompt cache 打爆（函数名 DANGEROUS_ 与 reason 文案自述）。
        //      门**开**时该段 compute 返 null（段消失），改由 delta 附件投递。
        //      ⇒ CC 的两个分支**都**即时可见；本仓「段会话冻结 + 门默认关」= CC 里不存在的**第三态**
        //        ⇒ 「同形」不成立（见 findingBFact）。
        //      2.1.278 发行产物（`bin/claude.exe`, package.json version=2.1.278）实测：
        //        `mcp_instructions_delta` 的挂点（offset 206242680 `Ja("mcp_instructions_delta",...)`）
        //        与 producer（offset 206259433 `function tXn(...)`）**外层无任何 gate**；字符串
        //        `CLAUDE_CODE_MCP_INSTR_DELTA` / `tengu_basalt_3kr` 各自 **0 次命中**（同命令可命中
        //        `tengu_mcp_instructions_pool_change` 等）⇒ 2.1.278 **已删除该门**，delta 无条件每轮跑。
        sections.add(systemPromptSection("mcp_instructions", () -> mcpInstructionsCompute(input)));
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
            log.debug("[SystemPromptSections] buildDynamicSections 注册 {} 条（10 无条件全 cacheBreak=false"
                    + "（含 mcp_instructions，对齐 CC 2.1.278 cacheBreak=true 计数 0）"
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
        // [r10b · D9] 已接边界（sessionSlots 非 null）→ 读槽（边界解析一次 ⇒ 槽 memoize）；
        //   未接边界 → 保留旧路径逐点不变（fail-loud 面不变）。
        Path cwdPath = input.sessionSlots() != null
            ? Path.of(input.sessionSlots().cwd())
            : cwd(input.sessionId());
        String cwd = cwdPath.toString().replace('\\', '/');
        // CC computeSimpleEnvInfo :655 Promise.all([getIsGit(), ...]) —— isGit 走 GitStatusProvider
        // walk-up（git.ts:27-86）；异常 → false（SubagentEnvInfo.isGitRepository :160-168 同款）
        boolean isGit = safeIsGit(cwdPath);
        // [SP-11] worktree 判定改回 CC 会话级：isWorktree = getCurrentWorktreeSession() !== null
        //   （prompts.ts:675-681），仅 EnterWorktree 工具进入的会话消费 '!' 子弹；sessionId null →
        //   false（无会话级 worktree 会话 = 非 worktree）。原 git 级 safeIsWorktree 已弃用
        //   （保留实现供诊断/其他消费，见 :350）。
        // [r10b · D9] 同一会话态读取收口到槽（未接边界时回落原读取点，语义逐点不变）
        boolean isWorktree = input.sessionSlots() != null
            ? input.sessionSlots().worktreeBound()
            : com.nexusai.application.agent.agent.SessionCwdHolder.isWorktreeBound(input.sessionId());
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
     * <p>Java 等价：appTempDir = {@link NexusaiPaths#getAppTempDir()}（运行时临时根，per-user 层
     * 品牌名 = {appName} 自有，行为对齐 CC getClaudeTempDir / getClaudeTempDirName
     * filesystem.ts:331-347/:307-315）；sanitizePath 走
     * {@link com.nexusai.application.agent.memory.AutoMemPaths#sanitizePath}；originalCwd 走
     * {@link CwdResolution#getOriginalCwdLayer(String)}（会话 original cwd，CC getOriginalCwd()）。
     *
     * @param sessionId 会话 ID（CC original: getSessionId()）；null/空 → 返回 null（scratchpad 会话级，
     *                  无会话不注入）
     * @return scratchpad 目录路径；无会话/计算失败 → null
     */
    public static String getScratchpadDir(String sessionId) {
        // [r10b · D9] 旧 1 参签名保留为薄包装（{@code LlmAgentLoop:4658} 等既有调用点零改动）
        return getScratchpadDir(sessionId, null);
    }

    /**
     * scratchpad 目录路径（会话态槽承载版）· [r10b · D9] 新增重载。
     *
     * <p>{@code originalCwdSlot} 非 null 时改从槽读 originalCwd（{@link PromptSessionSlots#originalCwd()}，
     * 边界解析一次 + memoize）；null 时回落 {@link CwdResolution#getOriginalCwdLayer(String)}。
     *
     * <p>⚠️ <b>槽读必须在 {@code try} 内</b>（本方法体的 try）：槽解析会抛（fail-loud），而本方法的
     * 契约是「解析失败 ⇒ 返回 null + ERROR 日志」（[S2 · F-10 · 用户裁定 #6]，跳过但 ≥ERROR）。
     * 把 {@code originalCwdSlot.get()} 挪到 try 外会改变该异常面（抛穿而非降级）⇒ ⛔ 不得调整位置。
     *
     * @param sessionId       会话 ID；null/空 → 返回 null（scratchpad 会话级，无会话不注入）
     * @param originalCwdSlot 会话原始 cwd 槽（{@code Supplier}，惰性；null = 未接边界，走旧路径）
     * @return scratchpad 目录路径；无会话/计算失败 → null
     */
    public static String getScratchpadDir(String sessionId, java.util.function.Supplier<String> originalCwdSlot) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Path appTempDir = NexusaiPaths.getAppTempPath();
            String originalCwd = originalCwdSlot != null
                ? originalCwdSlot.get()
                : CwdResolution.getOriginalCwdLayer(sessionId);
            if (originalCwd == null || originalCwd.isBlank()) {
                originalCwd = System.getProperty("user.dir", ".");
            }
            String sanitized = com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(originalCwd);
            return appTempDir.resolve(sanitized).resolve(sessionId).resolve("scratchpad").toString();
        } catch (Exception e) {
            // [S2 · F-10 2026-09-14 · 用户裁定 #6] 日志提为 ERROR（原 WARN）：本 catch 覆盖了
            //   :541 的 CwdResolution.getOriginalCwdLayer(sessionId) 的 fail-loud ⇒ 「项目根解析
            //   失败 / 无法判定」会被降级成「scratchpad 段不注入」。分类 = (b) 跳过（system prompt
            //   段缺失不应打死整轮），但必须 ≥WARN 且如实反映严重度（ERROR）。
            log.error("[SystemPromptSections] getScratchpadDir 计算失败，返回 null（scratchpad 段不注入）：",
                e);
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
        // [r10b · D9] 已接边界 → 槽（originalCwd 语义，与 CC getProjectTempDir 的 getOriginalCwd() 同源）
        String scratchpadDir = getScratchpadDir(input.sessionId(),
            input.sessionSlots() != null ? input.sessionSlots()::originalCwd : null);
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

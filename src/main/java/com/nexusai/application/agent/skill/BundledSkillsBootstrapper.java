package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.GitRemoteResolver;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.util.ChromePrompt;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bundled skill 启动注册 · 对齐 CC {@code initBundledSkills()}（src/skills/bundled/index.ts:24-79）。
 *
 * <p>P2-4 注册方式：非 {@code @Component}——由 {@link BundledSkillFeatureFlagsConfig#bundledSkillsBootstrapper}
 * 显式 {@code @Bean} 注册，注入 application.yml {@code nexusai.skill.features.*} 绑定的
 * {@link BundledSkillFeatureFlags}，使注册门控真正可配置（否则 Spring 会用本类无参构造器 →
 * 硬编码 {@link BundledSkillFeatureFlags#DEFAULTS}，yml 门控失效）。测试直接 new 本类构造器。
 *
 * <p><b>DEC-15 幽灵 skill</b>：CC bundled/index.ts:35-77 另有 3 个 feature-gated 注册块
 * （dream：KAIROS|KAIROS_DREAM :35-40 / hunter：REVIEW_ARTIFACT :41-45 /
 * runSkillGenerator：RUN_SKILL_GENERATOR :73-77），源文件 dream.ts / hunter.ts / runSkillGenerator.ts
 * 本 checkout 缺失（DCE 剔除，CC 上游缺陷：flag 开则 CC 懒 require 抛 MODULE_NOT_FOUND）。
 * Java 已实现 {@link DreamSkillRegistrar} / {@link HunterSkillRegistrar} /
 * {@link RunSkillGeneratorSkillRegistrar} 空壳类登记该缺陷，但<b>明确不注册</b>（不进本类注册列表，
 * 注册集对齐 CC 生产 bundle（三 flag 编译 false 永不注册）；ALIGN-BUNDLED-1 后 skillify/stuck 经
 * USER_TYPE 真实门控（CC skillify.ts:159-161 / stuck.ts:62-64），ant 环境注册、非 ant 不注册）。
 */
public class BundledSkillsBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BundledSkillsBootstrapper.class);

    /**
     * nexusai-in-chrome 注册门控 · CC original: shouldAutoEnableClaudeInChrome()
     * (src/skills/bundled/index.ts:70-72 {@code if (shouldAutoEnableClaudeInChrome()) registerClaudeInChromeSkill()})。
     *
     * <p><b>生产接线（browser-mcp-align，2026-08-30）</b>：由
     * {@link BundledSkillFeatureFlagsConfig#bundledSkillsBootstrapper} 注入
     * {@code () -> browserWsChannel != null && browserWsChannel.hasSessionConnection()} ——
     * <b>全局</b>有 Chrome 扩展 WS 连接 → true（注册），无 → false（跳过）。语义对齐 CC setup.ts:72-84
     * {@code shouldAutoEnableClaudeInChrome()}（交互会话 + 扩展可用）—— Java web 中「扩展可用」
     * 即运行时存在扩展连接（一个扩展连接服务所有会话）。
     *
     * <p><b>启动时序限制（fail loud 登记）</b>：本 gate 在 {@link #run(ApplicationArguments)} 启动期
     * 求值一次，此时无会话上下文（{@code RequestContext.sessionId()}=null）→ 生产启动时若扩展尚未
     * 连接，技能不注册（等价 CC 非 Chrome 环境默认关）。有会话连接后需重跑注册（或未来改惰性注册）
     * 才会进命令列表 —— 与 CC 机器级「启动时扩展已安装」判定在 web 多会话模型下的映射差异。
     *
     * <p>测试可注入 gate supplier（如 {@code () -> true}）直接驱动注册链。
     */
    private final BooleanSupplier shouldAutoEnableNexusaiInChrome;

    /**
     * ant 门控 · CC original: {@code process.env.USER_TYPE !== 'ant'} 早返
     * （loremIpsum.ts:235-237 / remember.ts:5-7 / skillify.ts:159-161 / stuck.ts:62-64）。
     * 默认读 env USER_TYPE（loremIpsum 同款）；lorem-ipsum/remember/skillify/stuck 共用此源。
     *
     * <p><b>语义差异（显式标注，2026-08-16）</b>：CC 为大小写敏感严格比较 {@code === 'ant'}；
     * Java 默认构造器用 {@code "ant".equalsIgnoreCase(...)}（大小写不敏感，容错增强）——
     * {@code USER_TYPE="ANT"} 时 CC 不注册 ant-gated skill，Java 会注册。属刻意的容错放宽，
     * 非缺陷；如需严格对齐 CC 大小写语义，将默认构造器改为 {@code "ant".equals(...)} 即可。
     */
    private final BooleanSupplier isAntSupplier;

    /**
     * P2-4：bundled skill 注册 feature flag 门控 · 对齐 CC {@code feature('...')} 编译期门控
     * (bundled/index.ts:47/:56/:64) 在 Java 的 Spring 运行时等价（nexusai.skill.features.*）。
     * 生产默认三 flag true（{@link BundledSkillFeatureFlags#DEFAULTS}，mcpSkills 默认 false P1-9）
     * 匹配 CC 生产 bundle（cli.js G15 loop/schedule/claudeApi 无条件注册，E6）。
     */
    private final BundledSkillFeatureFlags featureFlags;

    /**
     * P2-6：loop isEnabled 的 GB 'tengu_kairos_cron' 运行时供应 · CC original:
     * getFeatureValue_CACHED_WITH_REFRESH('tengu_kairos_cron', true, 5min)
     * (ScheduleCronTool/prompt.ts:41)。默认 {@code () -> true} 对齐 CC GB 无配置默认 true
     * （Java 无 GrowthBook）；是否接 yml 可配置源由主 agent 决策（concerns C3）。
     */
    private final BooleanSupplier kairosCronRuntime;

    /**
     * P2-6：remember isEnabled gate 供应 · CC original: () =&gt; isAutoMemoryEnabled()
     * (memdir/paths.ts:30-56)。默认 {@link BundledSkillEnabledGates#isAutoMemoryEnabled()}——
     * env CLAUDE_CODE_DISABLE_AUTO_MEMORY / CLAUDE_CODE_SIMPLE / CLAUDE_CODE_REMOTE 门控 + 默认 true。
     */
    private final BooleanSupplier autoMemoryEnabled;

    /**
     * P1-3：bundled skill 参考文件解压器 · CC bundledSkills.ts extractBundledSkillFiles。
     * 进程内字段实例（无外部依赖）；per-process nonce 由该类<b>静态</b>单例（rootCache）保证
     * —— 故本处 {@code new} 实例与权限层注入的 {@code @Component} bean（V-BD-5）共享同一
     * nonce 根目录，解压落盘路径与权限检查路径同源。
     */
    private final BundledSkillFileExtractor fileExtractor = new BundledSkillFileExtractor();

    /**
     * [拍板#9 part2] session memory 服务 · CC original: getSessionMemoryContent()
     * （skillify.ts:180，sessionMemoryUtils.ts:110-126 读当前会话 memory 文件）。
     *
     * <p>注入式（可选）：生产经 {@link BundledSkillFeatureFlagsConfig} 注入 SessionMemoryService bean
     * （getSessionMemoryContent(sessionId)），skillify 会话通道真实读取；测试/未接线时 null →
     * skillify 回落 'No session memory available.'（等价旧 {@code () -> ""} 桩的可观测回退，但不再有
     * 「生产恒空会话数据」缺口 —— 生产 bean 必注入）。
     */
    private final SessionMemoryService sessionMemoryService;

    public BundledSkillsBootstrapper() {
        // 生产默认：nexusai-in-chrome 门控关（无 Chrome MCP 检测）+ lorem-ipsum 按 USER_TYPE==='ant'
        // + feature flags 全 true（DEFAULTS，对齐 CC 生产 bundle 注册集）
        this(() -> false, () -> "ant".equalsIgnoreCase(System.getenv("USER_TYPE")),
            BundledSkillFeatureFlags.DEFAULTS);
    }

    public BundledSkillsBootstrapper(BooleanSupplier shouldAutoEnableNexusaiInChrome,
            BooleanSupplier isAntSupplier) {
        this(shouldAutoEnableNexusaiInChrome, isAntSupplier, BundledSkillFeatureFlags.DEFAULTS);
    }

    public BundledSkillsBootstrapper(BooleanSupplier shouldAutoEnableNexusaiInChrome,
            BooleanSupplier isAntSupplier, BundledSkillFeatureFlags featureFlags) {
        // P2-6：3 参构造器默认注入真实 gate 供应（kairosCronRuntime=() -> true 对齐 CC GB 默认；
        // autoMemoryEnabled=isAutoMemoryEnabled 对齐 CC paths.ts:30-56 默认 true），
        // 使 BundledSkillsFeatureGatingTest 等既有测试构造器也走真实 gate，不残留 () -> false 桩。
        this(shouldAutoEnableNexusaiInChrome, isAntSupplier, featureFlags,
            () -> true, BundledSkillEnabledGates::isAutoMemoryEnabled);
    }

    /**
     * P2-6 全参构造器（chrome/ant/featureFlags + 两个 isEnabled gate 供应）· 供
     * {@link BundledSkillFeatureFlagsConfig} 生产 @Bean 接线，与测试构造器共用真实 gate source。
     * sessionMemoryService 缺省 null（skillify 会话 memory 回落默认文案；生产经 6 参构造器注入）。
     */
    public BundledSkillsBootstrapper(BooleanSupplier shouldAutoEnableNexusaiInChrome,
            BooleanSupplier isAntSupplier, BundledSkillFeatureFlags featureFlags,
            BooleanSupplier kairosCronRuntime, BooleanSupplier autoMemoryEnabled) {
        this(shouldAutoEnableNexusaiInChrome, isAntSupplier, featureFlags,
            kairosCronRuntime, autoMemoryEnabled, null);
    }

    /**
     * [拍板#9 part2] 含 sessionMemoryService 的构造器 · 生产
     * {@link BundledSkillFeatureFlagsConfig#bundledSkillsBootstrapper} 注入 SessionMemoryService bean，
     * skillify 会话通道真实读取（getSessionMemoryContent(sessionId)）。
     */
    public BundledSkillsBootstrapper(BooleanSupplier shouldAutoEnableNexusaiInChrome,
            BooleanSupplier isAntSupplier, BundledSkillFeatureFlags featureFlags,
            BooleanSupplier kairosCronRuntime, BooleanSupplier autoMemoryEnabled,
            SessionMemoryService sessionMemoryService) {
        this.shouldAutoEnableNexusaiInChrome = Objects.requireNonNull(shouldAutoEnableNexusaiInChrome);
        this.isAntSupplier = Objects.requireNonNull(isAntSupplier);
        this.featureFlags = Objects.requireNonNull(featureFlags);
        this.kairosCronRuntime = Objects.requireNonNull(kairosCronRuntime);
        this.autoMemoryEnabled = Objects.requireNonNull(autoMemoryEnabled);
        this.sessionMemoryService = sessionMemoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // P2-9 注册序严格对齐 CC bundled/index.ts:24-72 initBundledSkills() 调用序
        // （updateConfig→keybindings→verify→debug→loremIpsum→skillify→remember→simplify→batch→stuck
        //  →ultracode→cron-list→cron-delete→[AGENT_TRIGGERS]loop→[AGENT_TRIGGERS_REMOTE]schedule
        //  →[BUILDING_CLAUDE_APPS]claudeApi
        //  →[shouldAutoEnableClaudeInChrome]claudeInChrome；KAIROS/REVIEW_ARTIFACT/RUN_SKILL_GENERATOR
        //  三 feature-gated 块为 DEC-15 空壳不注册）。bundled 为合并命令列表第一源
        // （SkillRegistry:534 BundledSkills.getAll() 第一，LinkedHashMap putIfAbsent 保序），
        // 故 bundled 内部相对顺序在命令列表中可观察——重排使命令列表用户面次序与 CC 一致（△-5 关闭）。
        registerUpdateConfigSkill();   // CC bundled/index.ts:25
        registerKeybindingsSkill();    // CC :26
        registerVerifySkill();         // CC :27
        registerDebugSkill();          // CC :28
        registerLoremIpsumSkill();     // CC :29（ant 门控）
        registerSkillifySkill();       // CC :30（ant 门控）
        registerRememberSkill();       // CC :31（ant 门控 + isAutoMemoryEnabled）
        registerSimplifySkill();       // CC :32
        registerBatchSkill();          // CC :33
        registerStuckSkill();          // CC :34（ant 门控）
        registerUltracodeSkill();      // CC bundled/index.ts:39（ultracode.ts:219，无条件注册，无 isEnabled gate）
        registerImportCcSkill();       // NexusAI 扩展 · /import-cc 一键导入 CC 技能/插件（无条件注册，无 isEnabled gate）
        registerCronListSkill();       // CC bundled/index.ts:40（cronManage.ts:8，isEnabled=isKairosCronEnabled）
        registerCronDeleteSkill();     // CC bundled/index.ts:41（cronManage.ts:27，isEnabled=isKairosCronEnabled）
        // CC :35-45 KAIROS|KAIROS_DREAM(dream) / :41-45 REVIEW_ARTIFACT(hunter) — DEC-15 空壳不注册
        // P2-4：loop 注册门控 · CC original: feature('AGENT_TRIGGERS') bundled/index.ts:47
        // 生产 bundle 编译 true → 无条件注册（cli.js G15）；Java 用 Spring 运行时 flag 等价。
        // 注意 CC index.ts:51-53 注释写 'Registered unconditionally' 但实际代码有 if(feature('AGENT_TRIGGERS'))
        // 门控——注释错误，以代码为准（CLAUDE.md 规则：不信 CC 注释）。
        if (featureFlags.agentTriggers()) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillsBootstrapper] loop 注册通过门控（feature('AGENT_TRIGGERS')=true）· 对齐 CC bundled/index.ts:47");
            }
            registerLoopSkill();
        } else {
            log.info("[BundledSkillsBootstrapper] loop 注册被门控跳过（feature('AGENT_TRIGGERS')=false）· 对齐 CC bundled/index.ts:47");
        }
        // P2-4：schedule 注册门控 · CC original: feature('AGENT_TRIGGERS_REMOTE') bundled/index.ts:56
        // 生产 bundle 编译 true → 无条件注册（cli.js G15）；Java 用 Spring 运行时 flag 等价。
        if (featureFlags.agentTriggersRemote()) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillsBootstrapper] schedule 注册通过门控（feature('AGENT_TRIGGERS_REMOTE')=true）· 对齐 CC bundled/index.ts:56");
            }
            registerScheduleSkill();
        } else {
            log.info("[BundledSkillsBootstrapper] schedule 注册被门控跳过（feature('AGENT_TRIGGERS_REMOTE')=false）· 对齐 CC bundled/index.ts:56");
        }
        // P2-4：claude-api 注册门控 · CC original: feature('BUILDING_CLAUDE_APPS') bundled/index.ts:64
        // 生产 bundle 编译 true → 无条件注册（cli.js G15）；Java 用 Spring 运行时 flag 等价。
        if (featureFlags.buildingClaudeApps()) {
            if (log.isDebugEnabled()) {
                log.debug("[BundledSkillsBootstrapper] claude-api 注册通过门控（feature('BUILDING_CLAUDE_APPS')=true）· 对齐 CC bundled/index.ts:64");
            }
            registerClaudeApiSkill();
        } else {
            log.info("[BundledSkillsBootstrapper] claude-api 注册被门控跳过（feature('BUILDING_CLAUDE_APPS')=false）· 对齐 CC bundled/index.ts:64");
        }
        // CC :70-72 nexusai-in-chrome（CC original gate: shouldAutoEnableClaudeInChrome 门控）· :73-77 RUN_SKILL_GENERATOR DEC-15 空壳不注册
        registerNexusaiInChromeSkill();
        log.info("Total bundled skills registered: {}", BundledSkills.count());
    }

    /**
     * 注册 /keybindings-help · 对齐 CC keybindings.ts:292 registerKeybindingsSkill()（index.ts:26 always-on）。
     *
     * <p>BundledSkillDefinition 字段：whenToUse（keybindings.ts:296 区段）+ userInvocable=false（:298）+
     * allowedTools=['Read']（:297）。isEnabled 门控（:299 isKeybindingCustomizationEnabled）归 P2-6。
     */
    private void registerKeybindingsSkill() {
        registerSkill("keybindings-help", () -> new KeybindingsSkill(this::register).registerSkill());
    }

    /**
     * 注册 /nexusai-in-chrome · 对齐 CC claudeInChrome.ts:16 registerClaudeInChromeSkill()。
     *
     * <p>注册前走 shouldAutoEnableClaudeInChrome() 门控（CC index.ts:70-72）；
     * <b>生产 gate = BrowserWsChannel 连接探测</b>（{@link BundledSkillFeatureFlagsConfig} 注入，
     * 当前会话有扩展 WS 连接 → true）。registerSkill(List.of()) → allowedTools 空（Java 无
     * BROWSER_TOOLS 来源，CCB 18 工具经 BrowserToolRegistry 独立注册，不依赖本 skill 的 allowedTools）。
     */
    private void registerNexusaiInChromeSkill() {
        if (!shouldAutoEnableNexusaiInChrome.getAsBoolean()) {
            log.warn("[BundledSkillsBootstrapper] nexusai-in-chrome 注册被门控跳过（shouldAutoEnableNexusaiInChrome=false）· 对齐 CC index.ts:70-72");
            return;
        }
        registerSkill("nexusai-in-chrome", () -> new NexusaiInChromeSkill(
            ChromePrompt::getChromeSystemPrompt,
            shouldAutoEnableNexusaiInChrome::getAsBoolean,
            this::register
        ).registerSkill(List.of()));
    }

    /**
     * 注册 /lorem-ipsum · 对齐 CC loremIpsum.ts:234 registerLoremIpsumSkill()。
     *
     * <p>注册前走 ant 早返（loremIpsum.ts:235-237 {@code USER_TYPE!=='ant'} return），
     * 由 {@code new LoremIpsumSkill(isAntSupplier).isAvailable()} 判定；
     * BundledSkillDefinition 用 NAME/DESCRIPTION/ARGUMENT_HINT + userInvocable=true（:240/:243/:244）构造。
     */
    private void registerLoremIpsumSkill() {
        LoremIpsumSkill skill = new LoremIpsumSkill(isAntSupplier);
        if (!skill.isAvailable()) {
            log.warn("[BundledSkillsBootstrapper] lorem-ipsum 注册被门控跳过（USER_TYPE!==ant）· 对齐 CC loremIpsum.ts:235-237 ant-only 早返");
            return;
        }
        registerSkill("lorem-ipsum", () -> {
            BundledSkillDefinition def = new BundledSkillDefinition(
                LoremIpsumSkill.NAME,
                LoremIpsumSkill.DESCRIPTION,
                null,   // aliases
                null,   // whenToUse
                LoremIpsumSkill.ARGUMENT_HINT,
                null,   // allowedTools
                null,   // model
                null,   // disableModelInvocation
                true,   // userInvocable (CC loremIpsum.ts:244)
                null,   // isEnabled
                null,   // hooks
                null,   // context
                null,   // agent
                null,   // files
                (args, cwd) -> skill.handleCommand(args)
            );
            register(def);
        });
    }

    private void registerBatchSkill() {
        // ALIGN-BUNDLED-1（R2B-DEC-18 / BD-7）：真实 isGit 检测 · CC batch.ts:116-119 getIsGit()
        // （utils/git.ts:218-229 → findGitRoot(getCwd()) !== null）。旧桩 () -> false 使 /batch
        // 恒返回 NOT_A_GIT_REPO（可观察缺陷）。复用既有通道 GitStatusProvider.isGit()
        // （walk-up .git 目录/文件，与 findGitRoot 同语义）；默认 cwd = 进程 cwd（对齐 CC getCwd()）。
        // [INFERENCE] CC getIsGit 为进程级 memoize（git.ts:218），Java 每调用重算——
        // 与 SubagentEnvInfo:159-164 同款，避免 Spring 多会话串状态（GitStatusProvider javadoc 同决议）。
        registerSkill("batch", () -> register(new BatchSkillRegistrar(
            () -> new GitStatusProvider().isGit()).register()));
    }

    private void registerClaudeApiSkill() {
        // ALIGN-BUNDLED-2（R2B-DEC-17 / BD-5）：生产内容供给接真实 ClaudeApiSkillContent ——
        // CC claudeApiContent.ts 结构（SKILL_PROMPT + SKILL_MODEL_VARS + 25 SKILL_FILES 文档映射，
        // 键序与模型常量逐一实测对齐）。旧匿名桩（SKILL_FILES=Map.of()、SKILL_PROMPT="# Claude API"、
        // SKILL_MODEL_VARS=Map.of()，探查 M3.16 空文档桩）→ 删除，注册链唯一真实内容实现。
        // entries 供应：生产 detectLanguage 经 promptFn 运行时 cwd（ctx.effectiveCwd）真实 readdir
        // （CC claudeApi.ts:37-39 readdir(getCwd())）；无会话 cwd 时回落 listCwdEntries（进程 cwd）。
        registerSkill("claude-api", () -> register(new ClaudeApiSkillRegistrar(
            ClaudeApiSkillRegistrar::listCwdEntries, ClaudeApiSkillContent::getInstance).register()));
    }

    private void registerDebugSkill() {
        // 拍板#9 part1（NG-CDB-1 关闭）：debug 生产 wiring 全桩 → 真实文件型 debug log 基建。
        // 旧全桩（isAnt=()->false / path="/tmp/debug.log" / enabler=()->true / tail=空 /
        // stat=抛 NoSuchFile / formatter=Long::toString / settingsPath=桩）删除——生产 /debug
        // 恒 ENOENT + justEnabled 恒不渲染（EV-V-CDB-011/012/014）。现注入 DebugSkillRegistrar.DebugLogging
        // 真实数据源：读真实 debug log 文件（stat + 64KB tail + ENOENT fallback）+ 真实 enabler
        // （wasAlreadyLogging=isDebugMode()||USER_TYPE==='ant'）+ 真实 settings 三路径。
        registerSkill("debug", () -> register(new DebugSkillRegistrar(
            isAntSupplier,                                  // CC debug.ts:16 process.env.USER_TYPE === 'ant' desc
            DebugSkillRegistrar.DebugLogging::getDebugLogPath,    // CC utils/debug.ts:230-236
            DebugSkillRegistrar.DebugLogging::enableDebugLogging, // CC utils/debug.ts:64-69 返回 wasAlreadyLogging
            DebugSkillRegistrar.DebugLogging::readTail,          // CC debug.ts:38-48 tail 读
            DebugSkillRegistrar.DebugLogging::stat,              // CC debug.ts:35 stat
            DebugSkillRegistrar.DebugLogging::formatFileSize,    // CC utils/format.ts:9-24
            DebugSkillRegistrar.DebugLogging::settingsPathFor    // CC settings.ts:274-296
        ).register()));
    }

    private void registerLoopSkill() {
        // P2-6：真实 gate 供应 · CC loop.ts:83 isEnabled: isKairosCronEnabled 默认 true。
        // 旧桩 () -> false 使 loop Command.enabled 恒 false（E1+E2，P2-6 删除硬编码桩）。
        // agentTriggers 已在此方法外的注册门控（feature('AGENT_TRIGGERS') bundled/index.ts:47）判定，
        // 供应端再含一次 agentTriggers 保持 CC prompt.ts:36-45 isKairosCronEnabled 语义一致。
        registerSkill("loop", () -> new LoopSkillRegistrar().register(this::register,
            () -> BundledSkillEnabledGates.isKairosCronEnabled(featureFlags.agentTriggers(), kairosCronRuntime)));
    }

    private void registerUltracodeSkill() {
        // CC ultracode.ts:219 registerUltracodeSkill()（bundled/index.ts:39）· 无条件注册（无 isEnabled gate，
        // 无 feature 门控）——纯知识注入 skill，userInvocable=true。命令接线（/workflows 等）归 commands-integration 域。
        registerSkill("ultracode", () -> new UltracodeSkillRegistrar().register(this::register));
    }

    private void registerImportCcSkill() {
        // /import-cc · NexusAI 自包含导入（非 CC bundled skill，本项目扩展）· 无条件注册（无 isEnabled gate，
        // 无 feature 门控）——纯提示型 skill，指导模型把 ~/.claude 技能/插件复制到 ~/.nexusai +
        // enabledPlugins 落 DB + 关插件双读。对齐 registerUltracodeSkill 注册位（无条件、userInvocable=true）。
        registerSkill("import-cc", () -> new ImportCcSkillRegistrar().register(this::register));
    }

    private void registerCronListSkill() {
        // P2-6：真实 gate 供应 · CC cronManage.ts:15 isEnabled: isKairosCronEnabled（与 loop 同源，
        // BundledSkillEnabledGates#isKairosCronEnabled）。skill 始终注册，enabled 惰性门控。
        registerSkill("cron-list", () -> new CronListSkillRegistrar().register(this::register,
            () -> BundledSkillEnabledGates.isKairosCronEnabled(featureFlags.agentTriggers(), kairosCronRuntime)));
    }

    private void registerCronDeleteSkill() {
        // CC cronManage.ts:35 isEnabled: isKairosCronEnabled（与 loop 同源）。skill 始终注册，enabled 惰性门控。
        registerSkill("cron-delete", () -> new CronDeleteSkillRegistrar().register(this::register,
            () -> BundledSkillEnabledGates.isKairosCronEnabled(featureFlags.agentTriggers(), kairosCronRuntime)));
    }

    private void registerRememberSkill() {
        // P2-6：真实 gate 供应 · CC remember.ts:5-7 ant-only 早返（USER_TYPE!=='ant' return）
        // + :71 isEnabled: () => isAutoMemoryEnabled() 默认 true。
        // 旧硬编码 true（isAntUser 恒注册，E1）+ () -> false 桩（isEnabled 恒 false，E1+E2）——P2-6 删除。
        if (log.isDebugEnabled()) {
            log.debug("[BundledSkillsBootstrapper] remember 注册：isAntSupplier={} autoMemoryEnabled 求值={}（CC remember.ts:5-7 ant 早返 + :71 isEnabled）",
                isAntSupplier.getAsBoolean(), autoMemoryEnabled.getAsBoolean());
        }
        registerSkill("remember", () -> new RememberSkillRegistrar().register(
            this::register, isAntSupplier.getAsBoolean(), autoMemoryEnabled));
    }

    /**
     * 注册 /schedule · 对齐 CC scheduleRemoteAgents.ts:324 registerScheduleRemoteAgentsSkill()。
     *
     * <p>P2-10 接线（△-9/△-12 关闭）：<b>currentGitRepoUrl</b> + <b>userTimezone</b> 由硬编码桩 →
     * 真实 Java 源：
     * <ul>
     *   <li>currentGitRepoUrl → {@link GitRemoteResolver#getRemoteHttpsUrl}（CC getCurrentRepoHttpsUrl
     *       scheduleRemoteAgents.ts:123-133：getRemoteUrl→parseGitRemote→https 拼装）</li>
     *   <li>userTimezone → {@code ZoneId.systemDefault().getId()}（CC Intl.DateTimeFormat()
     *       .resolvedOptions().timeZone :424）</li>
     * </ul>
     *
     * <p>isEnabled 已由 ScheduleRemoteAgentsSkillRegistrar 接线为 featureEnabled && policyAllowed
     * （CC :332-333 双开关）。此处注入 {@code () -> false} → Command.enabled=false，与 CC 默认
     * feature 关闭（getFeatureValue_CACHED_MAY_BE_STALE('tengu_surreal_dali', false)=false）语义一致。
     *
     * <p>其余 supplier 维持 CC 默认语义（Java 无 claude.ai cloud 基建，登记事实，不伪造）：
     * <ul>
     *   <li>C2 OAuth：CC :337 getClaudeAIOAuthTokens() → Java 无 claude.ai OAuth provider（API-key 模式）
     *       → oauthAccessToken 恒 null → 引导登录（CC 无 token 同路径）</li>
     *   <li>C3 远程环境：CC :348 fetchEnvironments / :364 createDefaultCloudEnvironment → Java 无
     *       claude.ai cloud environments API → 空 supplier，registrar 按 CC :348-378 错误文案兜底</li>
     *   <li>C4-C6 connector 枚举：CC :415-417 getConnectedClaudeAIConnectors(context.options.mcpClients)
     *       → Java 无 claudeai-proxy connected client（Q-26 TODO）→ List.of；registrar 内
     *       taggedIdToUUID/sanitizeConnectorName/formatConnectorsInfo(name) 已按 CC 就绪（P2-10 纯函数）</li>
     *   <li>C8 GitHub access：CC :388-408 checkRepoForRemoteAccess → Java 无实现（恒 false，P2-7 concerns）</li>
     * </ul>
     */
    private void registerScheduleSkill() {
        registerSkill("schedule", () -> register(new ScheduleRemoteAgentsSkillRegistrar(
            () -> false, () -> false, () -> null, List::of,
            name -> new ScheduleRemoteAgentsSkillRegistrar.EnvironmentResource(name, "default", "cloud"),
            // P2-10：真实系统时区 · CC userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone（scheduleRemoteAgents.ts:424）
            () -> java.time.ZoneId.systemDefault().getId(),
            List::of,
            // P2-10：真实 git remote https URL · CC getCurrentRepoHttpsUrl（scheduleRemoteAgents.ts:123-133）
            // cwd-align-ext：git 解析基准 = 会话运行时 cwd（CC getCurrentRepoHttpsUrl → resolveGitDir → resolve(getCwd())，
            //   gitFilesystem.ts:40-43）；无 sessionId 回落 user.dir（方案 1，零行为变化）。
            () -> GitRemoteResolver.getRemoteHttpsUrl(
                java.nio.file.Path.of(resolveCwdForGitRemote()))).register()));
    }

    /**
     * schedule 远程 agent 的 git remote 解析基准 cwd · 对齐 CC getCwd()（scheduleRemoteAgents.ts:123-133
     * → gitFilesystem.ts:40-43 resolve(startPath ?? getCwd())）。
     *
     * <p>supplier 运行期求值，经 RequestContext 取会话 cwd；无 sessionId 回落 user.dir（零行为变化）。
     */
    private static String resolveCwdForGitRemote() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    private void registerSimplifySkill() {
        registerSkill("simplify", () -> new SimplifySkillRegistrar().register(this::register));
    }

    private void registerSkillifySkill() {
        // ALIGN-BUNDLED-1（R2B-DEC-16 / BD-1）：CC skillify.ts:159-161 USER_TYPE!=='ant' 早返不注册。
        // 旧硬编码 () -> "ant" 使门控恒通过 → 改真实 USER_TYPE 判定，复用 isAntSupplier
        // （loremIpsum 同款）。非 ant → 传 null → registrar 内 "ant".equals(null)=false 不注册。
        //
        // [拍板#9 part2] 会话通道真实注入（替代旧 () -> "" / ignored -> List.of() 空桩，NG-CDB-2）：
        //   - sessionMemoryResolver = SessionMemoryService.getSessionMemoryContent(sessionId)
        //     （CC getSessionMemoryContent skillify.ts:180，sessionMemoryUtils.ts:110-126；未接线 null → 回落默认）
        //   - userMessagesExtractor = extractUserMessages（CC skillify.ts:6-20；boundary 剥离在 registrar 内，
        //     本 extractor 接收已剥离消息）
        registerSkill("skillify", () -> new SkillifySkillRegistrar(
            () -> isAntSupplier.getAsBoolean() ? "ant" : null,
            sessionId -> sessionMemoryService == null
                ? null
                : sessionMemoryService.getSessionMemoryContent(sessionId),
            messages -> SkillifySkillRegistrar.extractUserMessages(messages),
            this::register).register());
    }

    private void registerStuckSkill() {
        // ALIGN-BUNDLED-1（R2B-DEC-16 / BD-2）：CC stuck.ts:62-64 USER_TYPE!=='ant' 早返不注册。
        // 旧硬编码 true 使门控恒通过 → 改真实 USER_TYPE 判定（isAntSupplier，loremIpsum 同款）。
        registerSkill("stuck", () -> new StuckSkillRegistrar()
            .register(this::register, isAntSupplier.getAsBoolean()));
    }

    private void registerUpdateConfigSkill() {
        // BD-27 生产 wiring：默认构造器 → REAL_SETTINGS_SCHEMA（CC updateConfig.ts:10-13 generateSettingsSchema()
        // 运行时 toJSONSchema(SettingsSchema(), { io: 'input' }) 真实 schema）。旧 () -> "{}" 使生产 /update-config
        // 的 "Full Settings JSON Schema" 段恒为 {}（探查 §8.10 中危缺口本体），已关闭。
        registerSkill("update-config", () -> register(new UpdateConfigSkillRegistrar().register()));
    }

    private void registerVerifySkill() {
        // CC verify.ts:5 parseFrontmatter(SKILL_MD) → SKILL_BODY：必须剥离 '---\ndescription:...\n---' 头
        // 再传给 VerifySkillRegistrar（否则 prompt 以 '---' 开头，违反 VerifySkillRegistrar javadoc :49 契约）
        String skillBody = new ParseSkillFrontmatter().extractBody(VerifySkillContent.SKILL_MD);
        if (log.isDebugEnabled()) {
            log.debug("[BundledSkillsBootstrapper] verify skillBody 已剥离 frontmatter，body 首 30 字符='{}'（CC verify.ts:5 parseFrontmatter→SKILL_BODY）",
                skillBody.length() > 30 ? skillBody.substring(0, 30) : skillBody);
        }
        // P2-12（OQ-6 R1 残留收口）：CC verify.ts:13-15 USER_TYPE!=='ant' 早返 → 改真实 isAntSupplier
        // （loremIpsum/stuck/skillify 同款 USER_TYPE 判定）。旧 "ant"::equals 恒真 → verify 恒注册，
        // 与 CC 非 ant 部署（不注册）相悖 —— 现非 ant 环境 verify 不注册（对齐 CC verify.ts:13-15）。
        registerSkill("verify", () -> new VerifySkillRegistrar().register(
            this::register, isAntSupplier, VerifySkillRegistrar.FALLBACK_DESCRIPTION,
            skillBody, VerifySkillContent.SKILL_FILES));
    }

    /**
     * 唯一 bundled skill 注册入口 · 对齐 CC registerBundledSkill（bundledSkills.ts:53-100）。
     *
     * <p>P1-4 统一入口：把 {@link BundledSkillDefinition} 经 {@link BundledSkillDefinition#toCommand()}
     * 全字段映射为 Command（CC :75-98），再处理 files 解压 + baseDir，最后 {@code BundledSkills.register}。
     *
     * <p>P1-3 files 语义（bundledSkills.ts:59-72）：<b>P2-8 eager 定值</b>——CC skillRoot =
     * getBundledSkillExtractDir(name) 在<b>注册期</b>立即定值（:60）并落 Command.skillRoot（:91，即使
     * 解压失败也有值，首调前即可读）；解压本身惰性（extractionPromise memoize :64-72）。Java 等价：
     * 注册期 eager setBaseDir（= CC skillRoot），解压包装 promptFn 首调惰性执行
     * （{@link BundledSkillFileExtractor#lazyExtract}）；解压失败 → 清空 baseDir → 消费侧
     * withBaseDirPrefix 不加前缀（CC :70-71 仅 extractedDir!==null 才 prependBaseDir）。
     */
    private void register(BundledSkillDefinition def) {
        Command command = def.toCommand();
        Map<String, String> files = def.files();
        if (files != null && !files.isEmpty()) {
            // FIX-B6（BD-⊕-1 / 拍板#12）：inner==null 死分支删除 —— CC getPromptForCommand 为必填字段
            // （bundledSkills.ts:37-40），registerBundledSkill 无 null 检查，直接
            // const inner = definition.getPromptForCommand（:65）+ await inner(args, ctx)（:69）调用；
            // Java toCommand() 亦仅 getPromptForCommand != null 才设 promptFn（BundledSkillDefinition.java:134-137），
            // files 恒配非 null promptFn → 旧防御分支（:368-374，注册期 eager 解压）实际不可达，属「影子路径」残留 → 删除。
            // 保留惰性解压逻辑（CC extractionPromise memoize 语义）。若未来 inner 为 null，首调
            // inner.apply 抛 NPE —— 对齐 CC undefined inner 直接 throw（fail-loud，bundledSkills.ts:69）。
            // [FIX-D2 拍板#9 part2] promptFn 第二参升级 PromptFnContext（cwd+messages+sessionId），
            // 包装闭包原样透传 context（bundled skill 内容源不消费会话上下文）。
            BiFunction<String, PromptFnContext, List<ContentBlockParam>> inner = command.getPromptFn();
            // P2-8 eager：CC skillRoot = getBundledSkillExtractDir(name)（bundledSkills.ts:60）注册期定值
            // （:91 skillRoot 恒有值，首调前可读）——Java 对齐为注册期 eager setBaseDir。
            // 目录确定性（getBundledSkillExtractDir 与解压成功返回同路径）→ 解压成功不覆盖、失败清空。
            command.setBaseDir(fileExtractor.getBundledSkillExtractDir(def.name()).toString());
            // 惰性解压：CC extractionPromise memoize（bundledSkills.ts:64-72）——首调解压。
            Supplier<Path> lazyDir = fileExtractor.lazyExtract(def.name(), files);
            command.setPromptFn((args, context) -> {
                Path extractedDir = lazyDir.get();
                if (extractedDir != null) {
                    // 解压成功：baseDir 已在注册期 eager 定值（= extractedDir 同路径），消费侧 withBaseDirPrefix 加前缀
                    if (log.isDebugEnabled()) {
                        log.debug("[BundledSkillsBootstrapper] skill '{}' 参考文件惰性解压至 {}，base-dir 前缀生效（CC extractionPromise bundledSkills.ts:64-72）",
                            def.name(), extractedDir);
                    }
                } else {
                    // CC :70-71 仅 extractedDir!==null 才 prependBaseDir → 失败清空 baseDir，
                    // 消费侧 withBaseDirPrefix 据此不加前缀（fail-soft 对齐 CC bundledSkills.ts:139-144）。
                    command.setBaseDir(null);
                    log.warn("[BundledSkillsBootstrapper] skill '{}' 参考文件惰性解压失败，清空 baseDir（无 base-dir 前缀，fail-soft 对齐 CC bundledSkills.ts:139-144）",
                        def.name());
                }
                return inner.apply(args, context);
            });
        }
        if (log.isDebugEnabled()) {
            log.debug("[BundledSkillsBootstrapper] register BundledSkillDefinition name={} userInvocable={} allowedTools={} isEnabled={} baseDir={}（CC registerBundledSkill bundledSkills.ts:75-98）",
                def.name(), command.getUserInvocable(), command.getAllowedTools(), command.getEnabled(), command.getBaseDir());
        }
        BundledSkills.register(command);
    }

    private void registerSkill(String skillName, Runnable registration) {
        // ALIGN-BUNDLED-1（X15 改判 fail-loud）：CC registerBundledSkill（bundledSkills.ts:53-91）
        // 无任何 try/catch，注册异常直接上抛 → 启动即失败。旧 registerSafely catch+log.error
        // 静默吞异常属 ⊕（09-open-decisions.md §7.1 X15 行）→ 移除，异常传播与 CC 一致。
        registration.run();
        log.info("[BundledSkillsBootstrapper] registered {} skill", skillName);
    }
}

package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.command.InsightsCollector;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.plugin.InstalledPluginsManager;
import com.nexusai.application.agent.skill.BundledSkillDefinition;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.PromptBlock;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * 组 B 命令注册配置 · 会话/压缩/插件 域对齐 CC slash command（force-snip / btw / insights / sandbox / plugin）。
 *
 * <p><b>WHY</b>：本类隔离注册 5 个命令，不触碰共享注册入口
 * {@link CommandRegistrationConfig} / {@code CommandDto} / {@code BundledSkillsBootstrapper}
 * （并行组占用）。接线通道同 {@link CommandRegistrationConfig}：
 * <ul>
 *   <li><b>prompt 型</b>（{@code /insights}）→ 包装成 {@link BundledSkillDefinition} 注册进
 *       {@link BundledSkills}——经 SkillRegistry.getAllCommands 合并进 web GET /api/command +
 *       getModelInvocableCommands（loadedFrom=BUNDLED 放行）+ 模型经 SkillTool 调用时生成 prompt。</li>
 *   <li><b>local / local-jsx 型</b>（{@code /force-snip} / {@code /btw} / {@code /sandbox} /
 *       {@code /plugin}）→ ① 元数据 Command 注册进 {@link BundledSkills}（web GET /api/command 可见），
 *       ② {@link UserInputDispatcher#registerSlashCommand} 注册执行 handler（前端 /name → 后端直接执行）。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控</b>（对齐 CC types/command.ts:214-215 {@code isEnabled?.() ?? true}）：
 * force-snip（HISTORY_SNIP feature 默认 false，commands.ts:83-85）经 {@code BooleanSupplier} 惰性求值；
 * 其余命令 CC 无 isEnabled 门控默认 true。
 *
 * <p><b>isHidden 门控</b>：sandbox（commands/sandbox-toggle/index.ts:39-44
 * {@code get isHidden(){ return !isSupportedPlatform() || !isPlatformInEnabledList() }}）——平台不支持
 * 或不在 enabledPlatforms 白名单时隐藏；经 {@link SandboxManager#isSupportedPlatformEnv()} +
 * {@link SandboxManager#isPlatformInEnabledList()} 注册时求值。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>force-snip：CC 真源 force-snip.js 为编译产物（commands.ts:83-85 动态 require），源码不可读；
 *       行为按 snipCompact.ts:83-147 + QueryEngine.ts:337-346（slash command 经 setMessages 变更消息数组）
 *       推断 = {@link SnipCompactor#snipCompactIfNeeded(List, boolean)} 强制变体。</li>
 *   <li>btw：CC runSideQuestion（fork 上下文独立查询）无 Java 等价 fork 通道 → 退化为单轮
 *       {@link LlmProviderFactory#getProvider} + {@link LlmProvider#chatWithOptions} 旁路查询；
 *       未注入 LLM 基础设施时仅记录提问（fail loud）。</li>
 *   <li>insights：CC getPromptForCommand 生成 HTML 报告 + 长 prompt；Java 经 {@link InsightsCollector}
 *       生成 markdown 报告文本注入 prompt。source 差异：CC source='builtin'（commands.ts:570
 *       source!=='builtin' 过滤不进模型调用清单），Java 经 BundledSkillDefinition → source=BUNDLED
 *       （模型可调用，能力超集；若需严格对齐可改 source=BUILTIN 待决策）。</li>
 *   <li>sandbox：CC local-jsx 渲染配置 UI；Java 无 Ink UI → handler 仅记录当前沙箱状态（受控差异）。</li>
 *   <li>plugin：CC local-jsx 渲染插件管理 UI；Java handler 仅列已安装插件（受控差异）。</li>
 * </ul>
 */
@Configuration
public class CommandRegistrationConfigGroupB {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrationConfigGroupB.class);

    // ════════════════════════════════════════════════════════════════════════
    // 1. Bundled 命令注册（prompt 型 + local/local-jsx 元数据）· 对齐 CC getCommands 合并清单
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 1 个 prompt 命令 + 4 个 local/local-jsx 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 {@code CommandRegistrationConfig.commandBundledRegistration} 模式）。
     *
     * @param featureFlags   HISTORY_SNIP 门控源（@Component/@Bean；plain JUnit 缺省 null → ALL_DISABLED）
     * @param sandboxManager 沙箱管理器（isHidden 门控源；plain JUnit 缺省 null → 仅平台探针）
     */
    @Bean
    public CommandGroupBBundledRegistration commandGroupBBundledRegistration(
            @Autowired(required = false) FeatureFlags featureFlags,
            @Autowired(required = false) SandboxManager sandboxManager) {
        registerForceSnipMetadata(featureFlags);   // CC commands/force-snip.js（local，HISTORY_SNIP 门控）
        registerBtwMetadata();                     // CC commands/btw/index.ts（local-jsx）
        registerInsightsPrompt();                  // CC commands/insights.ts（prompt）
        registerSandboxMetadata(sandboxManager);   // CC commands/sandbox-toggle/index.ts（local-jsx，isHidden）
        registerPluginMetadata();                  // CC commands/plugin/index.tsx（local-jsx）
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigGroupB] bundled 命令注册完成：prompt 1 + local 元数据 4（BundledSkills 现 {} 条）",
                BundledSkills.count());
        }
        return new CommandGroupBBundledRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandGroupBBundledRegistration} 的注册副作用在 context refresh 时执行。 */
    public record CommandGroupBBundledRegistration() {}

    /**
     * /force-snip · CC commands/force-snip.js（commands.ts:83-85 {@code forceSnip = feature('HISTORY_SNIP')
     * ? require('./commands/force-snip.js').default : null}，INTERNAL_ONLY_COMMANDS）。
     *
     * <p><b>type='local'</b>：CC slash command 变更消息数组（QueryEngine.ts:337-346 注释
     * 「Slash commands that mutate the message array (e.g. /force-snip)」经 setMessages），
     * 非 prompt 生成 / 非 JSX 渲染 → local。
     *
     * <p><b>门控</b>：HISTORY_SNIP feature flag（commands.ts:83），Java = {@link FeatureFlags#historySnip()}
     * （nexusai.feature.history-snip，默认 false）。featureFlags null → ALL_DISABLED.historySnip()=false。
     *
     * <p><b>行为</b>（受控差异，真源编译产物不可读）：执行 {@link SnipCompactor#snipCompactIfNeeded(List, boolean)}
     * force 变体——存在 snip_boundary 时按 removedUuids 剔除历史消息，释放上下文（snipCompact.ts:83-147）。
     */
    private void registerForceSnipMetadata(FeatureFlags featureFlags) {
        FeatureFlags flags = featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED;
        BooleanSupplier gate = flags::historySnip;
        registerLocalMetadata("force-snip", "local",
            "Force snip old messages from conversation history to free up context window space",
            null, gate, false);
    }

    /**
     * /btw · CC commands/btw/index.ts:3-13
     * {@code {type:'local-jsx', name:'btw', description:'Ask a quick side question without interrupting the
     * main conversation', immediate:true, argumentHint:'<question>', load:()=>import('./btw.js')}}。
     *
     * <p>immediate=true（btw/index.ts:8）；无 isEnabled 门控。CC runSideQuestion 旁路提问
     * （fork 上下文独立查询，btw.tsx:24 + utils/sideQuestion.ts）——Java 无 fork 通道，
     * handler 退化为单轮 LLM 旁路查询（受控差异，见 {@link #registerBtwHandler}）。
     */
    private void registerBtwMetadata() {
        Command c = new Command();
        c.setName("btw");
        c.setType("local-jsx");
        c.setDescription("Ask a quick side question without interrupting the main conversation");
        c.setImmediate(Boolean.TRUE);            // CC btw/index.ts:8 immediate:true
        c.setArgumentHint("<question>");         // CC btw/index.ts:9
        c.setSource(CommandSource.BUNDLED);
        BundledSkills.register(c);
        log.info("[CommandRegistrationConfigGroupB] registered local command metadata 'btw' (type=local-jsx) · 对齐 CC commands/btw/index.ts:3-13");
    }

    /**
     * /insights · CC commands/insights.ts:3039-3045
     * {@code {type:'prompt', name:'insights', description:'Generate a report analyzing your NexusAI sessions',
     * contentLength:0, progressMessage:'analyzing your sessions', source:'builtin'}}。
     *
     * <p>promptFn → {@link InsightsCollector#generateReport(String)}（markdown 报告）。progressMessage
     * 对齐 CC insights.ts:3044 'analyzing your sessions'。source 差异见类头「受控差异」。
     */
    private void registerInsightsPrompt() {
        registerPromptSkill("insights", "Generate a report analyzing your NexusAI sessions",
            "analyzing your sessions",
            (args, ctx) -> List.of(PromptBlock.text(buildInsightsPrompt(ctx))));
    }

    /**
     * /sandbox · CC commands/sandbox-toggle/index.ts:5-48
     * {@code {name:'sandbox', type:'local-jsx', immediate:true, argumentHint:'exclude "command pattern"',
     * get isHidden(){ return !isSupportedPlatform() || !isPlatformInEnabledList() }}}。
     *
     * <p>isHidden 门控（sandbox-toggle/index.ts:39-44）：平台不支持 或 不在 enabledPlatforms 白名单时隐藏。
     * sandboxManager null（plain JUnit）→ 仅平台探针 {@link SandboxManager#isSupportedPlatformEnv()}。
     */
    private void registerSandboxMetadata(SandboxManager sandboxManager) {
        boolean supported = SandboxManager.isSupportedPlatformEnv();
        boolean inEnabledList = sandboxManager == null || sandboxManager.isPlatformInEnabledList();
        Command c = new Command();
        c.setName("sandbox");
        c.setType("local-jsx");
        c.setDescription("Toggle Bash sandboxing settings (⏎ to configure)");
        c.setImmediate(Boolean.TRUE);            // CC sandbox-toggle/index.ts:45 immediate:true
        c.setArgumentHint("exclude \"command pattern\""); // CC sandbox-toggle/index.ts:38
        c.setIsHidden(!supported || !inEnabledList);     // CC sandbox-toggle/index.ts:39-44
        c.setSource(CommandSource.BUNDLED);
        BundledSkills.register(c);
        log.info("[CommandRegistrationConfigGroupB] registered local command metadata 'sandbox' "
            + "(type=local-jsx, isHidden={} platformSupported={} inEnabledList={}) · 对齐 CC commands/sandbox-toggle/index.ts:5-48",
            c.getIsHidden(), supported, inEnabledList);
    }

    /**
     * /plugin · CC commands/plugin/index.tsx:3-9
     * {@code {type:'local-jsx', name:'plugin', description:'Manage NexusAI plugins', immediate:true}}。
     */
    private void registerPluginMetadata() {
        Command c = new Command();
        c.setName("plugin");
        c.setType("local-jsx");
        c.setDescription("Manage NexusAI plugins");
        c.setImmediate(Boolean.TRUE);            // CC plugin/index.tsx:7 immediate:true
        c.setSource(CommandSource.BUNDLED);
        BundledSkills.register(c);
        log.info("[CommandRegistrationConfigGroupB] registered local command metadata 'plugin' (type=local-jsx) · 对齐 CC commands/plugin/index.tsx:3-9");
    }

    /** prompt 命令统一注册 · BundledSkillDefinition → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerPromptSkill(String name, String description, String progressMessage,
                                     BiFunction<String, PromptFnContext, List<PromptBlock>> promptFn) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            name, description,
            null,        // aliases
            null,        // whenToUse
            null,        // argumentHint
            null,        // allowedTools
            null,        // model
            null,        // disableModelInvocation
            true,        // userInvocable（CC 命令默认 true）
            null,        // isEnabled（无 gate → 恒启用）
            null,        // hooks
            null,        // context
            null,        // agent
            null,        // files
            promptFn);
        Command command = def.toCommand();
        command.setProgressMessage(progressMessage);
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfigGroupB] registered prompt command '{}'（对齐 CC commands/{}.ts）",
            name, name);
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
        log.info("[CommandRegistrationConfigGroupB] registered local command metadata '{}' (type={}, enabled={}, hidden={})",
            name, type, isEnabled != null ? isEnabled.getAsBoolean() : true, isHidden);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Local 命令执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 4 个 local/local-jsx 命令的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>前端 /name 输入 → UserInputDispatcher.dispatch → 命名 handler 执行（同 /compact
     * ToolRegistrationConfig:1952 / /color AgentColorCommand:90 模式）。全部依赖 @Autowired(required=false)：
     * plain JUnit 缺省 null → handler 内空安全回退。
     */
    @Bean
    public CommandGroupBLocalSlashRegistration commandGroupBLocalSlashRegistration(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) SessionAgentStateRegistry sessionRegistry,
            @Autowired(required = false) SandboxManager sandboxManager,
            @Autowired(required = false) InstalledPluginsManager installedPluginsManager,
            @Autowired(required = false) LlmProviderFactory llmProviderFactory,
            @Autowired(required = false) ModelConfigResolver modelConfigResolver) {
        if (dispatcher == null) {
            log.warn("[CommandRegistrationConfigGroupB] UserInputDispatcher 未注入，local 命令执行 handler 注册跳过");
            return new CommandGroupBLocalSlashRegistration();
        }
        registerForceSnipHandler(dispatcher, sessionRegistry);
        registerBtwHandler(dispatcher, sessionRegistry, llmProviderFactory, modelConfigResolver);
        registerSandboxHandler(dispatcher, sandboxManager);
        registerPluginHandler(dispatcher, installedPluginsManager);
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfigGroupB] local 命令执行 handler 注册完成：force-snip/btw/sandbox/plugin");
        }
        return new CommandGroupBLocalSlashRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandGroupBLocalSlashRegistration} 的注册副作用在 context refresh 时执行。 */
    public record CommandGroupBLocalSlashRegistration() {}

    /**
     * /force-snip handler · 对齐 CC force-snip.js（编译产物）+ QueryEngine.ts:337-346（setMessages 变更消息数组）。
     *
     * <p>会话解析：RequestContext.sessionId（MDC）→ SessionAgentStateRegistry.get；AgentState 未注册 →
     * log.warn fail loud。执行 {@link SnipCompactor#snipCompactIfNeeded(List, boolean)} force 变体
     * （force=true，snipCompact.ts:85 参数从未使用 + QueryEngine.ts:1281 snipReplay 同款）；
     * executed=true → {@link AgentState#replaceMessages} 物理移除被裁剪消息，boundary（含摘要）保留。
     */
    private void registerForceSnipHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry sessionRegistry) {
        dispatcher.registerSlashCommandResult("force-snip", args -> {
            if (sessionRegistry == null) {
                log.warn("[CommandRegistrationConfigGroupB] /force-snip 无法执行：SessionAgentStateRegistry 未注入");
                return UserInputDispatcher.LocalCommandResult.text(
                    "/force-snip 无法执行：SessionAgentStateRegistry 未注入。");
            }
            String rawSessionId = RequestContext.sessionId();
            if (rawSessionId == null || rawSessionId.isBlank()) {
                log.warn("[CommandRegistrationConfigGroupB] /force-snip 无法解析当前 session（RequestContext.sessionId 为空）");
                return UserInputDispatcher.LocalCommandResult.text(
                    "/force-snip 无法解析当前 session（无请求上下文）。");
            }
            AgentState state = sessionRegistry.get(rawSessionId);
            if (state == null) {
                log.warn("[CommandRegistrationConfigGroupB] /force-snip 会话未注册 AgentState: sessionId={}", rawSessionId);
                return UserInputDispatcher.LocalCommandResult.text(
                    "/force-snip 会话未注册 AgentState（无进行中循环）。");
            }
            List<ChatMessageDto> messages = state.messages();
            if (messages == null || messages.isEmpty()) {
                log.warn("[CommandRegistrationConfigGroupB] /force-snip 会话消息为空: sessionId={}", rawSessionId);
                return UserInputDispatcher.LocalCommandResult.text("/force-snip 会话消息为空。");
            }
            SnipCompactor.SnipReplayResult result = new SnipCompactor().snipCompactIfNeeded(messages, true);
            if (!result.executed()) {
                log.info("[CommandRegistrationConfigGroupB] /force-snip 无 snip_boundary 消息，跳过（executed=false）· CC snipCompact.ts:111-113");
                return UserInputDispatcher.LocalCommandResult.text(
                    "/force-snip 无 snip_boundary 消息，未执行裁剪（executed=false）。");
            }
            state.replaceMessages(result.messages());
            log.info("[CommandRegistrationConfigGroupB] /force-snip 执行完成: {} → {} 条消息 · CC force-snip.js + QueryEngine.ts:1281（snipReplay force=true）",
                messages.size(), result.messages().size());
            return UserInputDispatcher.LocalCommandResult.text(
                String.format("/force-snip 执行完成: %d → %d 条消息（snipReplay force=true）",
                    messages.size(), result.messages().size()));
        });
        log.info("[CommandRegistrationConfigGroupB] /force-snip 已注册为生产 slash command（对齐 CC commands/force-snip.js + QueryEngine.ts:337-346）");
    }

    /**
     * /btw handler · 对齐 CC commands/btw/btw.tsx:229-242 call（旁路提问，不打断主对话）。
     *
     * <p><b>受控差异</b>：CC runSideQuestion fork 独立上下文查询（btw.tsx:24 + utils/sideQuestion.ts）——
     * Java 无 fork 通道 → 退化为单轮旁路查询（{@link LlmProviderFactory#getProvider} +
     * {@link LlmProvider#chatWithOptions}）。LLM 基础设施未注入 → 仅记录提问（fail loud）。
     */
    private void registerBtwHandler(UserInputDispatcher dispatcher,
                                    SessionAgentStateRegistry sessionRegistry,
                                    LlmProviderFactory llmProviderFactory,
                                    ModelConfigResolver modelConfigResolver) {
        dispatcher.registerSlashCommand("btw", args -> {
            String question = args == null ? "" : args.trim();
            if (question.isEmpty()) {
                log.warn("[CommandRegistrationConfigGroupB] /btw 用法: /btw <你的问题>（CC btw.tsx:232-236 Usage）");
                return;
            }
            if (llmProviderFactory == null || modelConfigResolver == null || sessionRegistry == null) {
                log.warn("[CommandRegistrationConfigGroupB] /btw 旁路提问基础设施未注入（LlmProviderFactory/ModelConfigResolver/"
                    + "SessionAgentStateRegistry），仅记录提问: {}（受控差异，CC runSideQuestion fork 未接线）", question);
                return;
            }
            try {
                String model = currentModel(sessionRegistry);
                ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(model);
                if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
                    log.warn("[CommandRegistrationConfigGroupB] /btw 模型配置不可用（model={}），仅记录提问: {}", model, question);
                    return;
                }
                LlmProvider provider = llmProviderFactory.getProvider(resolved.config(), resolved.providerType());
                LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                    List.of(), null, null, null, null, "btw", null, 1024);
                String answer = provider.chatWithOptions(resolved.config(), model,
                    "You are a helpful assistant answering a quick side question.", question, options);
                log.info("[CommandRegistrationConfigGroupB] /btw 旁路提问回答: model={} question={} answer={}",
                    model, question, answer);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfigGroupB] /btw 旁路提问失败: {}", e.toString(), e);
            }
        });
        log.info("[CommandRegistrationConfigGroupB] /btw 已注册为生产 slash command（对齐 CC commands/btw/btw.tsx call，旁路单轮查询）");
    }

    /**
     * /sandbox handler · 对齐 CC commands/sandbox-toggle/sandbox-toggle.tsx（local-jsx 状态 UI）。
     *
     * <p><b>受控差异</b>：CC 渲染 Ink UI 配置沙箱；Java 无 Ink UI → handler 仅记录当前沙箱状态
     * （isEnabled / auto-allow / unsandboxed / 平台 / 依赖）。
     */
    private void registerSandboxHandler(UserInputDispatcher dispatcher, SandboxManager sandboxManager) {
        dispatcher.registerSlashCommand("sandbox", args -> {
            if (sandboxManager == null) {
                log.warn("[CommandRegistrationConfigGroupB] /sandbox 无法执行：SandboxManager 未注入");
                return;
            }
            boolean enabled = sandboxManager.isEnabled();
            boolean autoAllow = sandboxManager.isAutoAllowBashIfSandboxed();
            String platform = SandboxManager.currentPlatform();
            boolean supported = SandboxManager.isSupportedPlatformEnv();
            boolean depsOk = SandboxManager.checkDependenciesEnv();
            boolean inEnabledList = sandboxManager.isPlatformInEnabledList();
            log.info("[CommandRegistrationConfigGroupB] /sandbox 状态: enabled={} autoAllow={} unsandboxed 默认=true "
                + "platform={} supported={} depsOk={} inEnabledList={} args={} · 对齐 CC sandbox-toggle/sandbox-toggle.tsx",
                enabled, autoAllow, platform, supported, depsOk, inEnabledList, args);
        });
        log.info("[CommandRegistrationConfigGroupB] /sandbox 已注册为生产 slash command（对齐 CC commands/sandbox-toggle）");
    }

    /**
     * /plugin handler · 对齐 CC commands/plugin/plugin.tsx（local-jsx 插件管理 UI）。
     *
     * <p><b>受控差异</b>：CC 渲染插件管理 UI（browse/install/uninstall）；Java handler 仅列已安装插件
     * （{@link InstalledPluginsManager#list()}）。
     */
    private void registerPluginHandler(UserInputDispatcher dispatcher, InstalledPluginsManager installedPluginsManager) {
        dispatcher.registerSlashCommand("plugin", args -> {
            if (installedPluginsManager == null) {
                log.warn("[CommandRegistrationConfigGroupB] /plugin 无法执行：InstalledPluginsManager 未注入");
                return;
            }
            List<InstalledPluginsManager.InstalledRecord> installed = installedPluginsManager.list();
            String names = installed.isEmpty() ? "(none)" : installed.stream()
                .map(r -> r.name() + "@" + r.version() + (r.enabled() ? "" : "(disabled)"))
                .reduce((a, b) -> a + ", " + b).orElse("");
            log.info("[CommandRegistrationConfigGroupB] /plugin 已安装 {} 个插件: {} · 对齐 CC commands/plugin/plugin.tsx",
                installed.size(), names);
        });
        log.info("[CommandRegistrationConfigGroupB] /plugin 已注册为生产 slash command（对齐 CC commands/plugin）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 生产 env 工具方法
    // ════════════════════════════════════════════════════════════════════════

    /** 当前会话 AgentState.currentModel() · 未注册/无模型 → "claude-sonnet-4-6" 兜底（同 CommandRegistrationConfig.currentModel）。 */
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

    /** 构建 /insights prompt 文本 · CC insights.ts:3156-3181（report 数据注入 prompt，模型呈现报告）。 */
    private static String buildInsightsPrompt(PromptFnContext ctx) {
        InsightsCollector collector = new InsightsCollector(() -> readSessionLogLines(ctx));
        InsightsCollector.HtmlReport report = collector.generateReport(
            ctx != null && ctx.cwd() != null ? ctx.cwd() : System.getProperty("user.dir", "."));
        return "The user just ran /insights to generate a usage report analyzing their NexusAI sessions.\n\n"
            + "Here is the session insights report:\n\n"
            + report.content() + "\n\n"
            + "Now present this report to the user clearly.";
    }

    /** 读取会话 transcript JSONL 行（供 /insights 统计）· 无会话/读取失败 → 空列表。 */
    private static List<String> readSessionLogLines(PromptFnContext ctx) {
        if (ctx == null || ctx.sessionId() == null || ctx.sessionId().isBlank()) {
            return List.of();
        }
        try {
            String sessionId = ctx.sessionId();
            String workspace = CwdResolution.getOriginalCwdLayer(sessionId);
            if (workspace == null || workspace.isBlank()) {
                workspace = System.getProperty("user.dir", ".");
            }
            // D3 读兼容：经 SessionStorage.resolveExistingTranscript 读 nexusai 现有 transcript（仅 nexusai，无 claude 回落）
            Path transcript = SessionStorage.resolveExistingTranscript(Path.of(workspace), sessionId);
            if (transcript == null || !Files.exists(transcript)) {
                return List.of();
            }
            return Files.readAllLines(transcript, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[CommandRegistrationConfigGroupB] /insights 读取会话 transcript 失败: {}", e.toString());
            return List.of();
        }
    }
}

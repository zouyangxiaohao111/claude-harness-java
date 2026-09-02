package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.command.InsightsCollector;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.application.agent.context.ContextAnalyzeService;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.mcp.dto.McpServerDto;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.SessionDto;
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
import java.util.function.BooleanSupplier;

/**
 * 命令注册配置 28 · 第二批 14 个 core 命令接线（对齐 CC commands/ 各 index.ts 合并清单）。
 *
 * <p><b>WHY（延续 {@link CommandRegistrationConfig} 的 GAP 探查）</b>：mcp / permissions / plan /
 * hooks / skills / agents / tasks / export / context / status / tag / usage / stats / diff 均为
 * CC {@code type='local-jsx'} 命令——渲染 React 面板（MCPSettings / PermissionRuleList / HooksConfigMenu /
 * SkillsMenu / AgentsMenu / BackgroundTasksDialog / ExportDialog / ContextVisualization / Settings /
 * DiffDialog 等）。Java web 后端<b>无 React 渲染等价物</b>，故按「打开面板/元数据」类
 * 命令处理：注册 Command 定义（web GET /api/command 可见 + type 正确）为<b>主交付</b>，UserInputDispatcher
 * 注册薄执行 handler（能接真实后端服务则接，否则仅披露）。
 *
 * <p><b>接线通道（对齐 CommandRegistrationConfig）</b>：
 * <ul>
 *   <li><b>元数据</b>：14 个命令全 type='local-jsx'（context 取 CC 交互变体），注册进 {@link BundledSkills}
 *       （source=BUNDLED + loadedFrom=BUNDLED）→ SkillRegistry.getAllCommands 合并进 web GET /api/command。
 *       经 getModelInvocableCommands 的 {@code type==='prompt'} 过滤天然排除（对齐 CC commands.ts:568）。</li>
 *   <li><b>执行 handler</b>：{@link UserInputDispatcher#registerSlashCommand} 注册——能真实执行的后端服务
 *       （McpServerService enable/disable / HookRegistry 计数 / SkillRegistry 列表 / TaskFrameworkService
 *       列表 / ContextAnalyzeService 分析 / SessionService 状态 /
 *       InsightsCollector 报告 / GitStatusProvider git 状态 / PlanProviderImpl plan 读取 / SessionStorage 打标）
 *       接真实逻辑（null-safe），纯面板命令（permissions / agents / export）仅披露。</li>
 * </ul>
 *
 * <p><b>[commands-real-exec · 2026-08-30] 6 个薄披露命令升真实执行</b>：
 * <ul>
 *   <li><b>/permissions</b> → 注入 3 个可编辑 settings 源加载器（UserSettingsLoader/ProjectSettingsLoader/
 *       LocalSettingsLoader）+ DenialTracker，真实列出 allow/deny/ask 规则。</li>
 *   <li><b>/agents</b> → 注入 SubagentTool（agentRegistry）+ AgentSummaryService，真实列出 agent 定义
 *       （含 shadowed 覆盖关系）+ 活跃子代理。</li>
 *   <li><b>/export</b> → 注入 SessionService + MessageService，真实渲染会话 markdown 导出。</li>
 *   <li><b>/skills</b> → SkillRegistry.getAllCommands 真实完整列表 + source 分类统计。</li>
 *   <li><b>/plan</b> → 真实切换/读取会话 plan 模式（AgentState.planMode 会话级字段）+ PlanProviderImpl 读盘。</li>
 *   <li><b>/stats</b> → InsightsCollector 接真实 transcript JSONL 日志源 + 活跃 AgentState 消息数。</li>
 * </ul>
 *
 * <p><b>isEnabled 门控（对齐 CC index.ts）</b>：
 * tag（USER_TYPE==='ant'，tag/index.ts:7）默认关 → 从
 * getAllCommands 过滤（对齐 CC commands.ts:484）。context（context/index.ts:7 isEnabled=!nonInteractive）
 * web 恒交互 → 启用。其余无 gate 默认 true。
 *
 * <p><b>受控差异（fail loud 登记）</b>：
 * <ul>
 *   <li>availability：usage（claude-ai）CC 声明 availability；web 无
 *       claude-ai/console 订阅模型（DEC-8），沿用 CommandRegistrationConfig 不设 availability →
 *       universal 可见（受控差异）。</li>
 *   <li>context 非交互变体（context/index.ts:12-24 local 型 supportsNonInteractive）未注册——web 无非交互会话。</li>
 *   <li>tag：CC toggle（同 tag 再执行移除，需读当前 tag）未接线 → 恒 add（写空串移除的读侧通道缺）；门控
 *       ant 默认关。</li>
 *   <li>plan：/plan handler 写会话级 AgentState.planMode（对齐 CC plan.tsx setAppState mode='plan'）；loop
 *       初始 mode 解析链（InitialPermissionModeResolver → settings.defaultMode）暂不读本字段，实际 PLAN
 *       permission 切换仍经 EnterPlanModeTool（登记差异）。</li>
 *   <li>stats：transcript 日志源为文件读（同 /insights），无实时事件流；会话空闲无 AgentState 时仅文件统计。</li>
 * </ul>
 */
@Configuration
public class CommandRegistrationConfig28 {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrationConfig28.class);

    // ════════════════════════════════════════════════════════════════════════
    // 1. Bundled 命令元数据注册（14 个 local-jsx · 对齐 CC commands/ 各 index.ts 合并清单）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：注册 14 个 local-jsx 命令元数据进 {@link BundledSkills}。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使注册副作用
     * 在上下文刷新时必然执行（同 {@link CommandRegistrationConfig#commandBundledRegistration} 模式）。
     */
    @Bean
    public CommandBundledRegistration28 commandBundledRegistration28() {
        registerMcpMetadata();          // CC commands/mcp/index.ts
        registerPermissionsMetadata();  // CC commands/permissions/index.ts
        registerPlanMetadata();         // CC commands/plan/index.ts
        registerHooksMetadata();        // CC commands/hooks/index.ts
        registerSkillsMetadata();       // CC commands/skills/index.ts
        registerAgentsMetadata();       // CC commands/agents/index.ts
        registerTasksMetadata();        // CC commands/tasks/index.ts
        registerExportMetadata();       // CC commands/export/index.ts
        registerContextMetadata();      // CC commands/context/index.ts（交互变体）
        registerStatusMetadata();       // CC commands/status/index.ts
        registerTagMetadata();          // CC commands/tag/index.ts
        registerUsageMetadata();        // CC commands/usage/index.ts
        registerStatsMetadata();        // CC commands/stats/index.ts
        registerDiffMetadata();         // CC commands/diff/index.ts
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfig28] bundled 命令注册完成：local-jsx 元数据 14 个（BundledSkills 现 {} 条）",
                BundledSkills.count());
        }
        return new CommandBundledRegistration28();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandBundledRegistration28} 的注册副作用在 context refresh 时执行。 */
    public record CommandBundledRegistration28() {}

    /** /mcp · CC commands/mcp/index.ts:3-12（type='local-jsx'，immediate=true）。 */
    private void registerMcpMetadata() {
        registerLocalMetadata28("mcp", "Manage MCP servers",
            "[enable|disable [server-name]]", null, false, true, null);
    }

    /** /permissions · CC commands/permissions/index.ts:3-9（aliases=['allowed-tools']）。 */
    private void registerPermissionsMetadata() {
        registerLocalMetadata28("permissions", "Manage allow & deny tool permission rules",
            null, null, false, false, List.of("allowed-tools"));
    }

    /** /plan · CC commands/plan/index.ts:3-9（argumentHint='[open|<description>]'）。 */
    private void registerPlanMetadata() {
        registerLocalMetadata28("plan", "Enable plan mode or view the current session plan",
            "[open|<description>]", null, false, false, null);
    }

    /** /hooks · CC commands/hooks/index.ts:3-9（immediate=true）。 */
    private void registerHooksMetadata() {
        registerLocalMetadata28("hooks", "View hook configurations for tool events",
            null, null, false, true, null);
    }

    /** /skills · CC commands/skills/index.ts:3-9。 */
    private void registerSkillsMetadata() {
        registerLocalMetadata28("skills", "List available skills",
            null, null, false, false, null);
    }

    /** /agents · CC commands/agents/index.ts:3-9。 */
    private void registerAgentsMetadata() {
        registerLocalMetadata28("agents", "Manage agent configurations",
            null, null, false, false, null);
    }

    /** /tasks · CC commands/tasks/index.ts:3-9（aliases=['bashes']）。 */
    private void registerTasksMetadata() {
        registerLocalMetadata28("tasks", "List and manage background tasks",
            null, null, false, false, List.of("bashes"));
    }

    /** /export · CC commands/export/index.ts:3-10（argumentHint='[filename]'）。 */
    private void registerExportMetadata() {
        registerLocalMetadata28("export", "Export the current conversation to a file or clipboard",
            "[filename]", null, false, false, null);
    }

    /** /context · CC commands/context/index.ts:4-10（isEnabled=!getIsNonInteractiveSession()；web 恒交互 → 启用）。 */
    private void registerContextMetadata() {
        registerLocalMetadata28("context", "Visualize current context usage as a colored grid",
            null, () -> true, false, false, null);
    }

    /** /status · CC commands/status/index.ts:3-12（immediate=true）。 */
    private void registerStatusMetadata() {
        registerLocalMetadata28("status",
            "Show NexusAI status including version, model, account, API connectivity, and tool statuses",
            null, null, false, true, null);
    }

    /** /tag · CC commands/tag/index.ts:3-9（isEnabled=USER_TYPE==='ant'，argumentHint='<tag-name>'）。 */
    private void registerTagMetadata() {
        registerLocalMetadata28("tag", "Toggle a searchable tag on the current session",
            "<tag-name>", () -> isAnt(), false, false, null);
    }

    /** /usage · CC commands/usage/index.ts:3-9（availability=['claude-ai'] 受控差异：web 不设 availability）。 */
    private void registerUsageMetadata() {
        registerLocalMetadata28("usage", "Show plan usage limits",
            null, null, false, false, null);
    }

    /** /stats · CC commands/stats/index.ts:3-9。 */
    private void registerStatsMetadata() {
        registerLocalMetadata28("stats", "Show your NexusAI usage statistics and activity",
            null, null, false, false, null);
    }

    /** /diff · CC commands/diff/index.ts:3-9。 */
    private void registerDiffMetadata() {
        registerLocalMetadata28("diff", "View uncommitted changes and per-turn diffs",
            null, null, false, false, null);
    }

    /** local-jsx 命令元数据统一注册 · Command(type='local-jsx') → BundledSkills（source=BUNDLED + loadedFrom=BUNDLED）。 */
    private void registerLocalMetadata28(String name, String description,
                                         String argumentHint, BooleanSupplier isEnabled,
                                         boolean isHidden, boolean immediate, List<String> aliases) {
        Command command = new Command();
        command.setName(name);
        command.setType("local-jsx");
        command.setDescription(description);
        if (argumentHint != null) {
            command.setArgumentHint(argumentHint);
        }
        command.setIsEnabled(isEnabled);
        command.setIsHidden(isHidden);
        command.setImmediate(immediate);
        if (aliases != null) {
            command.setAliases(aliases);
        }
        BundledSkills.register(command);
        log.info("[CommandRegistrationConfig28] registered local-jsx command metadata '{}' (type=local-jsx, enabled={}, hidden={}, immediate={})",
            name, isEnabled != null ? isEnabled.getAsBoolean() : true, isHidden, immediate);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. Local 命令执行 handler 注册（UserInputDispatcher registerSlashCommand）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 副作用 @Bean：把 14 个 local-jsx 命令的<b>执行 handler</b> 注册进 {@link UserInputDispatcher}。
     *
     * <p>前端 /name 输入 → UserInputDispatcher.dispatch → 命名 handler 执行（同 CommandRegistrationConfig
     * advisor/cost 模式）。各服务为 @Component/@Service/@Bean；plain JUnit 缺省 null → handler 内空安全回退
     * （仅披露）。
     *
     * <p><b>[commands-real-exec] 6 个薄披露命令升真实执行</b>：permissions / agents / export / skills /
     * plan / stats 接入真实后端服务（权限规则加载器 / SubagentTool+AgentSummaryService /
     * SessionService+MessageService / SkillRegistry / PlanProviderImpl+AgentState.planMode /
     * InsightsCollector+transcript），btw 已在 GroupB 接
     * LlmProviderFactory 旁路查询。新增注入依赖均为 @Autowired(required=false)（plain JUnit 缺省 null →
     * handler 空安全回退）。
     */
    @Bean
    public CommandLocalSlashRegistration28 commandLocalSlashRegistration28(
            UserInputDispatcher dispatcher,
            @Autowired(required = false) McpServerService mcpServerService,
            @Autowired(required = false) HookRegistry hookRegistry,
            @Autowired(required = false) SkillRegistry skillRegistry,
            @Autowired(required = false) TaskFrameworkService taskFrameworkService,
            @Autowired(required = false) SessionService sessionService,
            @Autowired(required = false) ContextAnalyzeService contextAnalyzeService,
            @Autowired(required = false) AnalyticsTracker analyticsTracker,
            @Autowired(required = false) SessionAgentStateRegistry sessionRegistry,
            @Autowired(required = false) UserSettingsLoader userSettingsLoader,
            @Autowired(required = false) ProjectSettingsLoader projectSettingsLoader,
            @Autowired(required = false) LocalSettingsLoader localSettingsLoader,
            @Autowired(required = false) DenialTracker denialTracker,
            @Autowired(required = false) SubagentTool subagentTool,
            @Autowired(required = false) AgentSummaryService agentSummaryService,
            @Autowired(required = false) MessageService messageService) {
        if (dispatcher == null) {
            log.warn("[CommandRegistrationConfig28] UserInputDispatcher 未注入，local 命令执行 handler 注册跳过");
            return new CommandLocalSlashRegistration28();
        }
        registerMcpHandler(dispatcher, mcpServerService);
        registerPermissionsHandler(dispatcher, userSettingsLoader, projectSettingsLoader, localSettingsLoader, denialTracker);
        registerPlanHandler(dispatcher, sessionRegistry);
        registerHooksHandler(dispatcher, hookRegistry);
        registerSkillsHandler(dispatcher, skillRegistry);
        registerAgentsHandler(dispatcher, subagentTool, agentSummaryService);
        registerTasksHandler(dispatcher, taskFrameworkService);
        registerExportHandler(dispatcher, sessionService, messageService);
        registerContextHandler(dispatcher, contextAnalyzeService);
        registerStatusHandler(dispatcher, sessionService);
        registerTagHandler(dispatcher);
        registerUsageHandler(dispatcher, analyticsTracker);
        registerStatsHandler(dispatcher, sessionRegistry);
        registerDiffHandler(dispatcher);
        if (log.isDebugEnabled()) {
            log.debug("[CommandRegistrationConfig28] local 命令执行 handler 注册完成：mcp/permissions/plan/hooks/skills/agents/tasks/export/context/status/tag/usage/stats/diff（含 6 个真实执行升级）");
        }
        return new CommandLocalSlashRegistration28();
    }

    /** 副作用 bean 标记 record · 使 {@link #commandLocalSlashRegistration28} 的注册副作用在 context refresh 时执行。 */
    public record CommandLocalSlashRegistration28() {}

    /**
     * /mcp handler · CC commands/mcp/mcp.tsx:63-84 call（local-jsx）。
     *
     * <p>参数解析对齐 CC mcp.tsx：{@code enable/disable [server-name]} → 真实调
     * {@link McpServerService#start}/{@link McpServerService#stop}（by id）；无参 → 列出全部 server
     * 状态；reconnect/no-redirect 面板 → 披露（web 无等价）。mcpServerService 未注入 → 披露。
     */
    private void registerMcpHandler(UserInputDispatcher dispatcher, McpServerService mcpServerService) {
        dispatcher.registerSlashCommand("mcp", args -> {
            if (mcpServerService == null) {
                log.warn("[CommandRegistrationConfig28] /mcp 未注入 McpServerService，跳过（web 端 MCP 面板走 REST /api/mcp）");
                return;
            }
            String trimmed = args != null ? args.trim() : "";
            try {
                List<McpServerDto> servers = mcpServerService.listAll();
                if (trimmed.isBlank() || "no-redirect".equals(trimmed)) {
                    StringBuilder sb = new StringBuilder();
                    for (McpServerDto s : servers) {
                        sb.append(String.format("  %s status=%s enabled=%s%n", s.name(), s.status(), s.enabled()));
                    }
                    log.info("[CommandRegistrationConfig28] /mcp 执行完成: {} 个 MCP server:%n{}", servers.size(), sb);
                    return;
                }
                String[] parts = trimmed.split("\\s+");
                if (("enable".equals(parts[0]) || "disable".equals(parts[0])) && parts.length > 1) {
                    String target = trimmed.substring(parts[0].length()).trim();
                    boolean enable = "enable".equals(parts[0]);
                    McpServerDto hit = servers.stream()
                        .filter(s -> s.name().equals(target)).findFirst().orElse(null);
                    if (hit == null) {
                        log.warn("[CommandRegistrationConfig28] /mcp {} target server \"{}\" 未找到（CC mcp.tsx:34 'not found'）",
                            parts[0], target);
                        return;
                    }
                    try {
                        if (enable) {
                            mcpServerService.start(hit.id());
                            log.info("[CommandRegistrationConfig28] /mcp enable server={} 完成", target);
                        } else {
                            mcpServerService.stop(hit.id());
                            log.info("[CommandRegistrationConfig28] /mcp disable server={} 完成", target);
                        }
                    } catch (Exception e) {
                        log.warn("[CommandRegistrationConfig28] /mcp {} server={} 执行失败: {}（CC mcp.tsx onComplete 报错路径）",
                            parts[0], target, e.getMessage());
                    }
                    return;
                }
                log.info("[CommandRegistrationConfig28] /mcp args={}（reconnect/其他参数面板 web 无等价，走 REST /api/mcp）", trimmed);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /mcp 执行失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /mcp 已注册为生产 slash command（对齐 CC commands/mcp/index.ts + mcp.tsx call）");
    }

    /**
     * /permissions handler · CC commands/permissions/permissions.tsx:5-9 call（local-jsx PermissionRuleList 面板）。
     *
     * <p><b>[commands-real-exec] 薄披露 → 真实执行</b>：注入 3 个可编辑 settings 源加载器
     * （{@link UserSettingsLoader}/{@link ProjectSettingsLoader}/{@link LocalSettingsLoader}，对齐
     * PermissionRulesController.list 同源）+ {@link DenialTracker} 分类器拒绝计数，handler 真实列出
     * 当前 allow/deny/ask 规则（按 source+behavior 分组）。loader 未注入 → 仅披露（fail loud）。
     */
    private void registerPermissionsHandler(UserInputDispatcher dispatcher,
                                            UserSettingsLoader userSettingsLoader,
                                            ProjectSettingsLoader projectSettingsLoader,
                                            LocalSettingsLoader localSettingsLoader,
                                            DenialTracker denialTracker) {
        dispatcher.registerSlashCommand("permissions", args -> {
            List<PermissionSourceLoaderRef> loaders = permissionLoaders(
                userSettingsLoader, projectSettingsLoader, localSettingsLoader);
            if (loaders.isEmpty()) {
                log.warn("[CommandRegistrationConfig28] /permissions 权限规则加载器未注入（web 端权限规则走 REST /api/v1/permissions/rules，仅披露）");
                return;
            }
            try {
                StringBuilder sb = new StringBuilder();
                int total = 0;
                int[] behaviorCounts = new int[3]; // allow/deny/ask
                for (PermissionSourceLoaderRef ref : loaders) {
                    List<PermissionRule> rules = ref.loader().load();
                    if (rules == null || rules.isEmpty()) {
                        continue;
                    }
                    sb.append(String.format("  [%s] %d 条%n", ccSourceName(ref.source()), rules.size()));
                    for (PermissionRule r : rules) {
                        PermissionBehavior b = r.ruleBehavior();
                        sb.append(String.format("    %s  %s%n", ccBehaviorName(b), r.ruleValue().toRuleString()));
                        behaviorCounts[b.ordinal()]++;
                    }
                    total += rules.size();
                }
                String denial = denialTracker != null
                    ? String.format("（分类器拒绝: consecutive=%d total=%d，熔断=%s）",
                        denialTracker.getConsecutiveDenials(), denialTracker.getTotalDenials(),
                        denialTracker.shouldFallbackToPrompting())
                    : "";
                log.info("[CommandRegistrationConfig28] /permissions 执行完成: 共 {} 条可编辑规则"
                    + "（allow={} deny={} ask={}）%n{}{}（对齐 CC permissions.tsx PermissionRuleList）",
                    total, behaviorCounts[0], behaviorCounts[1], behaviorCounts[2], sb, denial);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /permissions 读取规则失败: {}（fail loud）", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /permissions 已注册为生产 slash command（真实权限规则列表，对齐 CC permissions.tsx PermissionRuleList）");
    }

    /**
     * /plan handler · CC commands/plan/plan.tsx:64-121 call（local-jsx）。
     *
     * <p><b>[commands-real-exec] 薄披露 → 真实执行</b>：
     * <ul>
     *   <li><b>非 plan 模式</b> → 真实切换会话 plan 模式（{@link AgentState#setPlanMode}，web 会话级
     *       存 AgentState，对齐 CC plan.tsx:73-82 setAppState toolPermissionContext mode='plan'）；有
     *       描述参数（非 open）→ 附带 shouldQuery 语义（CC plan.tsx:84-87）。</li>
     *   <li><b>已处 plan 模式</b> → 读盘 {@link PlanProviderImpl#getPlan} 展示当前 plan / open 显示
     *       路径（CC plan.tsx:94-112）。</li>
     * </ul>
     *
     * <p>PlanProviderImpl 以 sessionId 为 slug 直构（非 Spring bean）。sessionRegistry 未注入或无
     * 活跃 AgentState（会话空闲）→ planMode 仅本次日志记录（受控差异：AgentState 生命周期内有效）。
     */
    private void registerPlanHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry sessionRegistry) {
        dispatcher.registerSlashCommand("plan", args -> {
            String sessionId = RequestContext.sessionId();
            String trimmed = args != null ? args.trim() : "";
            PlanProviderImpl planProvider;
            try {
                planProvider = new PlanProviderImpl(sessionId);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /plan 无法构造 PlanProviderImpl: {}", e.getMessage());
                return;
            }
            boolean planMode = readSessionPlanMode(sessionRegistry, sessionId);
            if (!planMode) {
                // CC plan.tsx:73-82 —— 非 plan 模式 → 启用 plan 模式
                writeSessionPlanMode(sessionRegistry, sessionId, true);
                boolean shouldQuery = !trimmed.isBlank() && !"open".equals(trimmed);
                log.info("[CommandRegistrationConfig28] /plan → 已启用 plan 模式（web 会话级 AgentState.planMode=true）"
                    + " shouldQuery={}（对齐 CC plan.tsx:75-89 handlePlanModeTransition + setAppState mode='plan'）",
                    shouldQuery);
                return;
            }
            // 已处 plan 模式 → 展示当前 plan（CC plan.tsx:94-112）
            if ("open".equals(trimmed)) {
                log.info("[CommandRegistrationConfig28] /plan open → web 无编辑器打开能力（受控差异），plan 路径: {}",
                    planProvider.getPlanFilePath(null));
                return;
            }
            String plan = planProvider.getPlan(null);
            if (plan == null || plan.isBlank()) {
                log.info("[CommandRegistrationConfig28] /plan → 已处 plan 模式但无 plan 写入（CC plan.tsx:98 'No plan written yet'）");
                return;
            }
            log.info("[CommandRegistrationConfig28] /plan → 当前 plan:%n{}", plan);
        });
        log.info("[CommandRegistrationConfig28] /plan 已注册为生产 slash command（真实切换/读取会话 plan 模式，对齐 CC commands/plan/index.ts + plan.tsx call）");
    }

    /** /hooks handler · CC commands/hooks/hooks.tsx:6-12 call → HookRegistry 计数披露。 */
    private void registerHooksHandler(UserInputDispatcher dispatcher, HookRegistry hookRegistry) {
        dispatcher.registerSlashCommand("hooks", args -> {
            if (hookRegistry == null) {
                log.warn("[CommandRegistrationConfig28] /hooks 未注入 HookRegistry（web 端 hook 配置走 REST）");
                return;
            }
            log.info("[CommandRegistrationConfig28] /hooks 执行完成: genericHooks={} pluginHookNames={} pluginConfigs={}（对齐 CC hooks.tsx HooksConfigMenu）",
                hookRegistry.genericHookCount(), hookRegistry.pluginHookNames().size(),
                hookRegistry.getRegisteredPluginHookConfigs().size());
        });
        log.info("[CommandRegistrationConfig28] /hooks 已注册为生产 slash command（对齐 CC commands/hooks/index.ts + hooks.tsx call）");
    }

    /**
     * /skills handler · CC commands/skills/skills.tsx:5-7 call → SkillRegistry 完整真实列表。
     *
     * <p><b>[commands-real-exec] 计数披露 → 真实完整列表</b>：{@link SkillRegistry#getAllCommands()}
     * 返回合并全部来源（bundled / builtinPlugin / 文件系统 / dynamic / COMMANDS）的真实命令清单，
     * handler 按 {@link CommandSource} + type 分类统计并列出（bundled/disk/plugin 等源分类）。
     */
    private void registerSkillsHandler(UserInputDispatcher dispatcher, SkillRegistry skillRegistry) {
        dispatcher.registerSlashCommand("skills", args -> {
            if (skillRegistry == null) {
                log.warn("[CommandRegistrationConfig28] /skills 未注入 SkillRegistry（web 端技能列表走 GET /api/command）");
                return;
            }
            try {
                List<Command> cmds = skillRegistry.getAllCommands();
                long promptLike = cmds.stream().filter(c -> "prompt".equals(c.getType())).count();
                long localLike = cmds.stream().filter(c -> !"prompt".equals(c.getType())).count();
                // 按 source 分类（CC commands.ts:374-467 五源合并 + source 值面）
                int bundled = (int) cmds.stream().filter(c -> c.getSource() == CommandSource.BUNDLED).count();
                int builtin = (int) cmds.stream().filter(c -> c.getSource() == CommandSource.BUILTIN).count();
                int user = (int) cmds.stream().filter(c -> c.getSource() == CommandSource.USER).count();
                int plugin = (int) cmds.stream().filter(c -> c.getSource() == CommandSource.PLUGIN).count();
                int other = cmds.size() - bundled - builtin - user - plugin;
                StringBuilder sb = new StringBuilder();
                for (Command c : cmds.stream().limit(30).toList()) {
                    sb.append(String.format("  %s [%s/%s] %s%n",
                        c.getName(),
                        ccSourceDisplay(c.getSource()),
                        c.getType() != null ? c.getType() : "?",
                        c.getDescription() != null ? c.getDescription() : ""));
                }
                if (cmds.size() > 30) {
                    sb.append(String.format("  ... 其余 %d 个省略%n", cmds.size() - 30));
                }
                log.info("[CommandRegistrationConfig28] /skills 执行完成: 共 {} 个命令（prompt 型 {} / local 型 {}；"
                        + "source: bundled={} builtin={} user/disk={} plugin={} other={}）%n{}{}（对齐 CC skills.tsx SkillsMenu）",
                    cmds.size(), promptLike, localLike, bundled, builtin, user, plugin, other, sb,
                    cmds.size() > 30 ? String.format("  total=%d", cmds.size()) : "");
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /skills 查询失败: {}（fail loud）", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /skills 已注册为生产 slash command（真实完整命令列表，对齐 CC commands/skills/index.ts + skills.tsx call）");
    }

    /**
     * /agents handler · CC commands/agents/agents.tsx:6-11 call（local-jsx AgentsMenu 面板）。
     *
     * <p><b>[commands-real-exec] 薄披露 → 真实执行</b>：注入 {@link SubagentTool}（暴露
     * {@link AgentDefinitionRegistry}，CC toolUseContext.options.agentDefinitions 等价）+
     * {@link AgentSummaryService}（活跃周期摘要子代理，CC services/AgentSummary 等价）。
     * handler 真实列出可用 agent 定义（agentType + source + 覆盖关系）+ 活跃子代理。
     */
    private void registerAgentsHandler(UserInputDispatcher dispatcher,
                                       SubagentTool subagentTool,
                                       AgentSummaryService agentSummaryService) {
        dispatcher.registerSlashCommand("agents", args -> {
            AgentDefinitionRegistry registry = subagentTool != null ? subagentTool.agentRegistry() : null;
            if (registry == null) {
                log.warn("[CommandRegistrationConfig28] /agents SubagentTool 未注入（web 端 subagent 管理走 REST SubagentController，仅披露）");
                return;
            }
            try {
                List<AgentDefinitionRegistry.ResolvedAgentDefinition> resolved = registry.resolveAgentOverrides();
                int total = resolved.size();
                int shadowed = (int) resolved.stream().filter(r -> r.overriddenBy() != null).count();
                StringBuilder sb = new StringBuilder();
                for (AgentDefinitionRegistry.ResolvedAgentDefinition ra : resolved) {
                    String shadow = ra.overriddenBy() != null ? " (shadowed by " + ra.overriddenBy() + ")" : "";
                    sb.append(String.format("  %s [%s]%s%n", ra.agent().agentType(), ra.agent().source(), shadow));
                }
                String active = agentSummaryService != null && !agentSummaryService.activeAgents().isEmpty()
                    ? String.format("，活跃子代理 %d 个: %s", agentSummaryService.activeAgents().size(),
                        String.join(", ", agentSummaryService.activeAgents()))
                    : "，无活跃子代理";
                log.info("[CommandRegistrationConfig28] /agents 执行完成: 共 {} 个 agent 定义（shadowed={}）%n{}{}（对齐 CC agents.tsx AgentsMenu + agentDisplay.ts resolveAgentOverrides）",
                    total, shadowed, sb, active);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /agents 查询失败: {}（fail loud）", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /agents 已注册为生产 slash command（真实 agent 定义列表，对齐 CC commands/agents/index.ts + agents.tsx call）");
    }

    /** /tasks handler · CC commands/tasks/tasks.tsx:5-7 call → TaskFrameworkService.listAll 计数。 */
    private void registerTasksHandler(UserInputDispatcher dispatcher, TaskFrameworkService taskFrameworkService) {
        dispatcher.registerSlashCommand("tasks", args -> {
            if (taskFrameworkService == null) {
                log.warn("[CommandRegistrationConfig28] /tasks 未注入 TaskFrameworkService（web 端后台任务走 REST TaskController）");
                return;
            }
            try {
                List<BackgroundTask> tasks = taskFrameworkService.listAll();
                log.info("[CommandRegistrationConfig28] /tasks 执行完成: {} 个后台任务（对齐 CC tasks.tsx BackgroundTasksDialog）", tasks.size());
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /tasks 查询失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /tasks 已注册为生产 slash command（对齐 CC commands/tasks/index.ts + tasks.tsx call）");
    }

    /**
     * /export handler · CC commands/export/export.tsx call（local-jsx ExportDialog 面板）。
     *
     * <p><b>[commands-real-exec] 薄披露 → 真实执行</b>：注入 {@link SessionService} + {@link MessageService}，
     * handler 真实渲染当前会话 markdown 导出（对齐 ExportController.renderMarkdown），返回消息数 +
     * 字符数 + 内容摘要（首/末条）。服务未注入 / 无会话 → 仅披露（fail loud）。
     */
    private void registerExportHandler(UserInputDispatcher dispatcher,
                                       SessionService sessionService,
                                       MessageService messageService) {
        dispatcher.registerSlashCommand("export", args -> {
            String sessionId = RequestContext.sessionId();
            if (sessionService == null || messageService == null || sessionId == null || sessionId.isBlank()) {
                log.warn("[CommandRegistrationConfig28] /export 未注入 SessionService/MessageService 或无会话上下文"
                    + "（web 端会话导出走 REST /api/v1/export/{sessionId}，filename={}）",
                    args != null ? args.trim() : "");
                return;
            }
            try {
                SessionDto dto = sessionService.getById(sessionId);
                if (dto == null) {
                    log.warn("[CommandRegistrationConfig28] /export 会话 {} 不存在", sessionId);
                    return;
                }
                List<ChatMessageDto> messages = messageService.listBySession(sessionId);
                String md = renderExportMarkdown(dto, messages);
                String summary = messages.isEmpty() ? "(空会话)" : String.format(
                    "首条[%s]: %s | 末条[%s]: %s",
                    messages.get(0).role(),
                    truncate(messages.get(0).content(), 60),
                    messages.get(messages.size() - 1).role(),
                    truncate(messages.get(messages.size() - 1).content(), 60));
                log.info("[CommandRegistrationConfig28] /export 执行完成: session={} title={} 消息 {} 条，"
                        + "markdown {} 字符%n  摘要: {}（对齐 CC export.tsx ExportDialog + ExportController.renderMarkdown）",
                    sessionId, dto.title(), messages.size(), md.length(), summary);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /export 导出失败: {}（fail loud）", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /export 已注册为生产 slash command（真实会话 markdown 导出，对齐 CC commands/export/index.ts + export.tsx call）");
    }

    /**
     * /context handler · CC commands/context/context.tsx:30-45 call（local-jsx）。
     *
     * <p>{@link ContextAnalyzeService#analyze} 真实执行（system/memory/tools/skill 四段计数），输出分类合计。
     * 未注入 → 披露。
     */
    private void registerContextHandler(UserInputDispatcher dispatcher, ContextAnalyzeService contextAnalyzeService) {
        dispatcher.registerSlashCommand("context", args -> {
            if (contextAnalyzeService == null) {
                log.warn("[CommandRegistrationConfig28] /context 未注入 ContextAnalyzeService，跳过（web 上下文可视化走 REST）");
                return;
            }
            try {
                ContextAnalyzeService.ContextAnalyzeResult result = contextAnalyzeService.analyze(null, null);
                int total = result.categories().stream()
                    .mapToInt(ContextAnalyzeService.ContextCategory::tokens).sum();
                log.info("[CommandRegistrationConfig28] /context 执行完成: {} 个分类段，合计 {} tokens（对齐 CC context.tsx ContextVisualization）",
                    result.categories().size(), total);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /context analyze 失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /context 已注册为生产 slash command（对齐 CC commands/context/index.ts + context.tsx call）");
    }

    /** /status handler · CC commands/status/status.tsx:5-7 call → SessionService 当前会话状态。 */
    private void registerStatusHandler(UserInputDispatcher dispatcher, SessionService sessionService) {
        dispatcher.registerSlashCommand("status", args -> {
            String sessionId = RequestContext.sessionId();
            if (sessionService == null || sessionId == null || sessionId.isBlank()) {
                log.warn("[CommandRegistrationConfig28] /status 未注入 SessionService 或无会话上下文（对齐 CC status.tsx Settings 面板，web 走 SessionController REST）");
                return;
            }
            try {
                SessionDto dto = sessionService.getById(sessionId);
                if (dto == null) {
                    log.warn("[CommandRegistrationConfig28] /status 会话 {} 不存在", sessionId);
                    return;
                }
                log.info("[CommandRegistrationConfig28] /status 执行完成: session={} title={}（对齐 CC status.tsx Settings/Status 面板）",
                    dto.id(), dto.title());
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /status 查询失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /status 已注册为生产 slash command（对齐 CC commands/status/index.ts + status.tsx call）");
    }

    /**
     * /tag handler · CC commands/tag/tag.tsx:205-214 call + ToggleTagAndClose。
     *
     * <p>{@code /tag <name>} 真实写 transcript tag 元数据（{@link SessionStorage#reAppendSessionMetadata}）。
     * 受控差异：CC toggle（同 tag 再执行移除，需读当前 tag）读侧通道未接线 → 恒 add；空参/help → 用法披露。
     */
    private void registerTagHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand("tag", args -> {
            String tag = args != null ? args.trim() : "";
            if (tag.isBlank() || "help".equals(tag) || "--help".equals(tag)) {
                log.info("[CommandRegistrationConfig28] /tag 用法: /tag <tag-name>（对齐 CC tag.tsx ShowHelp；门控 USER_TYPE==='ant' 默认关）");
                return;
            }
            String sessionId = RequestContext.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                log.warn("[CommandRegistrationConfig28] /tag 无活动会话可打标（CC tag.tsx:95 'No active session to tag'）");
                return;
            }
            try {
                Path ws = Path.of(resolveWorkspaceDir(sessionId));
                SessionStorage.reAppendSessionMetadata(ws, sessionId,
                    new SessionStorage.SessionMetadata(null, null, tag, null, null,
                        null, null, null, null, null, null));
                log.info("[CommandRegistrationConfig28] /tag session={} 已打标 #{}（对齐 CC tag.tsx saveTag；toggle 移除读侧未接线，受控差异）",
                    sessionId, tag);
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /tag 失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /tag 已注册为生产 slash command（对齐 CC commands/tag/index.ts + tag.tsx call）");
    }

    /** /usage handler · CC commands/usage/usage.tsx:4-6 call → AnalyticsTracker 事件计数披露。 */
    private void registerUsageHandler(UserInputDispatcher dispatcher, AnalyticsTracker analyticsTracker) {
        dispatcher.registerSlashCommand("usage", args -> {
            if (analyticsTracker == null) {
                log.warn("[CommandRegistrationConfig28] /usage 未注入 AnalyticsTracker（web 计划用量走 SessionController/Usage REST）");
                return;
            }
            Map<String, Long> counts = analyticsTracker.countsByEventName();
            log.info("[CommandRegistrationConfig28] /usage 执行完成: {} 类事件，合计 {}（对齐 CC usage.tsx Settings/Usage 面板）",
                counts.size(), counts.values().stream().mapToLong(Long::longValue).sum());
        });
        log.info("[CommandRegistrationConfig28] /usage 已注册为生产 slash command（对齐 CC commands/usage/index.ts + usage.tsx call）");
    }

    /**
     * /stats handler · CC commands/stats/stats.tsx:4-6 call → {@link InsightsCollector#generateReport}。
     *
     * <p><b>[commands-real-exec] 空统计 → 真实日志源</b>：注入 {@link SessionAgentStateRegistry}，
     * handler 真实读取会话 transcript JSONL（{@link SessionStorage#resolveExistingTranscript}，D3 读兼容
     * 仅 nexusai 自有 transcript，同 GroupB /insights 读源）+ 活跃 AgentState 消息数，经 {@link InsightsCollector} 生成
     * 真实统计（turn 数 / 工具调用排行 / 时长 / 模型）。transcript 不存在 / 读失败 → 空统计（fail loud）。
     */
    private void registerStatsHandler(UserInputDispatcher dispatcher, SessionAgentStateRegistry sessionRegistry) {
        dispatcher.registerSlashCommand("stats", args -> {
            String sessionId = RequestContext.sessionId();
            String projectRoot = sessionId != null && !sessionId.isBlank()
                ? CwdResolution.getOriginalCwdLayer(sessionId)
                : System.getProperty("user.dir", ".");
            try {
                InsightsCollector collector = new InsightsCollector(
                    () -> readSessionLogLines(sessionId));
                InsightsCollector.HtmlReport report = collector.generateReport(
                    projectRoot != null ? projectRoot : ".");
                int liveMessages = liveSessionMessageCount(sessionRegistry, sessionId);
                log.info("[CommandRegistrationConfig28] /stats 执行完成: {}（turnCount={} 工具调用 {} 类，"
                        + "活跃 AgentState 消息 {} 条；对齐 CC stats.tsx Stats 面板 + insights.ts collectSessionStats）",
                    report.title(),
                    reportContentTurnCount(report.content()),
                    reportContentToolCalls(report.content()),
                    liveMessages);
                log.info("[CommandRegistrationConfig28] /stats 报告:%n{}", report.content());
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /stats 生成失败: {}（fail loud）", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /stats 已注册为生产 slash command（真实 transcript 统计，对齐 CC commands/stats/index.ts + stats.tsx call）");
    }

    /**
     * /diff handler · CC commands/diff/diff.tsx:3-8 call → {@link GitStatusProvider#getGitStatus}。
     *
     * <p>构造器以会话 cwd 直构（非 Spring bean），git 状态真实读取；非 git 仓库 / 无变更 → 披露。
     */
    private void registerDiffHandler(UserInputDispatcher dispatcher) {
        dispatcher.registerSlashCommand("diff", args -> {
            String sessionId = RequestContext.sessionId();
            String cwd = CwdResolution.getCwd(sessionId);
            if (cwd == null || cwd.isBlank()) {
                cwd = System.getProperty("user.dir", ".");
            }
            try {
                GitStatusProvider provider = new GitStatusProvider(Path.of(cwd));
                String status = provider.getGitStatus();
                log.info("[CommandRegistrationConfig28] /diff 执行完成: {}（对齐 CC diff.tsx DiffDialog；web 端 per-turn diff 走 REST）",
                    status != null ? "git status:\n" + status : "非 git 仓库或无变更");
            } catch (Exception e) {
                log.warn("[CommandRegistrationConfig28] /diff 失败: {}", e.getMessage());
            }
        });
        log.info("[CommandRegistrationConfig28] /diff 已注册为生产 slash command（对齐 CC commands/diff/index.ts + diff.tsx call）");
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

    // ════════════════════════════════════════════════════════════════════════
    // [commands-real-exec] 6 命令真实执行工具方法
    // ════════════════════════════════════════════════════════════════════════

    /** /permissions 可编辑源加载器引用 · (loader, source 枚举)。 */
    private record PermissionSourceLoaderRef(PermissionSourceLoader loader, PermissionRuleSource source) {}

    /** 收集 3 个可编辑 settings 源加载器（对齐 PermissionRulesController.list 同源）；未注入 → 跳过。 */
    private static List<PermissionSourceLoaderRef> permissionLoaders(
            UserSettingsLoader user, ProjectSettingsLoader project, LocalSettingsLoader local) {
        List<PermissionSourceLoaderRef> list = new java.util.ArrayList<>();
        if (user != null) {
            list.add(new PermissionSourceLoaderRef(user, PermissionRuleSource.USER_SETTINGS));
        }
        if (project != null) {
            list.add(new PermissionSourceLoaderRef(project, PermissionRuleSource.PROJECT_SETTINGS));
        }
        if (local != null) {
            list.add(new PermissionSourceLoaderRef(local, PermissionRuleSource.LOCAL_SETTINGS));
        }
        return list;
    }

    /** Java PermissionRuleSource 枚举 → CC source 字面量（对齐 PermissionRulesController.ccSourceName）。 */
    private static String ccSourceName(PermissionRuleSource source) {
        return switch (source) {
            case USER_SETTINGS -> "userSettings";
            case PROJECT_SETTINGS -> "projectSettings";
            case LOCAL_SETTINGS -> "localSettings";
            case FLAG_SETTINGS -> "flagSettings";
            case POLICY_SETTINGS -> "policySettings";
            case CLI_ARG -> "cliArg";
            case COMMAND -> "command";
            case SESSION -> "session";
        };
    }

    /** Java PermissionBehavior 枚举 → CC behavior 字面量（对齐 PermissionRulesController.ccBehaviorName）。 */
    private static String ccBehaviorName(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
        };
    }

    /** CommandSource → 人读源标签（/skills 分类展示）。 */
    private static String ccSourceDisplay(CommandSource source) {
        if (source == null) {
            return "unknown";
        }
        return switch (source) {
            case BUILTIN -> "builtin";
            case BUNDLED -> "bundled";
            case USER, PROJECT_SETTINGS, LOCAL_SETTINGS, FLAG_SETTINGS, POLICY_SETTINGS -> "disk";
            case PLUGIN -> "plugin";
            case MCP -> "mcp";
        };
    }

    /** 读会话 plan 模式开关 · sessionRegistry 无活跃 AgentState → false（fail loud 前题：仅运行时内有效）。 */
    private static boolean readSessionPlanMode(SessionAgentStateRegistry registry, String sessionId) {
        if (registry == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        AgentState state = registry.get(sessionId);
        return state != null && state.planMode();
    }

    /** 写会话 plan 模式开关（CC plan.tsx:75-82 setAppState toolPermissionContext mode='plan' 的会话级记录）。 */
    private static void writeSessionPlanMode(SessionAgentStateRegistry registry, String sessionId, boolean planMode) {
        if (registry == null || sessionId == null || sessionId.isBlank()) {
            log.warn("[CommandRegistrationConfig28] /plan 无会话上下文/registry 未注入，planMode 仅本次记录（受控差异）");
            return;
        }
        AgentState state = registry.get(sessionId);
        if (state == null) {
            log.warn("[CommandRegistrationConfig28] /plan 会话无活跃 AgentState（会话空闲），planMode 写失败（受控差异：AgentState 生命周期内有效）");
            return;
        }
        state.setPlanMode(planMode);
    }

    /** 渲染会话 markdown 导出摘要 · 对齐 ExportController.renderMarkdown 核心段（title + 消息数 + 逐条 role/content）。 */
    private static String renderExportMarkdown(SessionDto dto, List<ChatMessageDto> messages) {
        StringBuilder sb = new StringBuilder();
        String title = dto.title() != null && !dto.title().isBlank() ? dto.title() : "Session " + dto.id();
        sb.append("# ").append(title).append("\n\n");
        if (dto.modelName() != null && !dto.modelName().isBlank()) {
            sb.append("**Model**: ").append(dto.modelName()).append("\n");
        }
        sb.append("**Messages**: ").append(messages.size()).append("\n\n---\n\n");
        for (ChatMessageDto m : messages) {
            String role = m.role() != null ? m.role().name() : "unknown";
            String content = m.content() != null ? m.content() : "";
            sb.append("## ").append(role).append("\n\n").append(content).append("\n\n");
        }
        return sb.toString();
    }

    /** 截断长文本（导出摘要用）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** 读取会话 transcript JSONL 行（/stats 真实日志源，同 GroupB /insights readSessionLogLines）。 */
    private static List<String> readSessionLogLines(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
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
            log.warn("[CommandRegistrationConfig28] /stats 读取会话 transcript 失败: {}", e.toString());
            return List.of();
        }
    }

    /** 活跃 AgentState 消息数（/stats 补充维度）；无会话/无活跃 state → 0。 */
    private static int liveSessionMessageCount(SessionAgentStateRegistry registry, String sessionId) {
        if (registry == null || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        AgentState state = registry.get(sessionId);
        return state != null && state.messages() != null ? state.messages().size() : 0;
    }

    /** 从 InsightsCollector 报告内容解析 turn 数（"**Turn count**: N"）。 */
    private static int reportContentTurnCount(String content) {
        if (content == null) {
            return 0;
        }
        int idx = content.indexOf("**Turn count**:");
        if (idx < 0) {
            return 0;
        }
        String rest = content.substring(idx + "**Turn count**:".length()).trim();
        int end = rest.indexOf('\n');
        if (end < 0) {
            end = rest.length();
        }
        try {
            return Integer.parseInt(rest.substring(0, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 InsightsCollector 报告内容解析工具调用类数（"- `Tool`: N calls" 行数）。 */
    private static int reportContentToolCalls(String content) {
        if (content == null) {
            return 0;
        }
        int count = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith("- `")) {
                count++;
            }
        }
        return count;
    }
}

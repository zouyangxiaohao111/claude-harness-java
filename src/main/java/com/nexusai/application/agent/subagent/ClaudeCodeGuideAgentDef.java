package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.settings.SettingsCache;
import com.nexusai.application.agent.skill.BuiltInCommands;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * ClaudeCodeGuideAgentDef · 对齐 CC tools/AgentTool/built-in/claudeCodeGuideAgent.ts:98-205.
 *
 * <p>L1 语义: 内置 claude-code-guide agent 定义 - 回答 Claude Code / Agent SDK / Claude API 三域问题,
 * 用 haiku 模型 + dontAsk 权限. 工具集在 embedded search 构建下用 Bash+Read, 否则 Glob+Grep+Read
 * (均含 WebFetch/WebSearch).
 *
 * <p><b>[Session S1 P1-5]</b>: 旧实现仅常量 (死代码, 未注册到 {@link BuiltInAgents}). 本期补
 * {@link #create()} 构造 {@link AgentDefinition.BuiltInAgentDefinition} 并注册进
 * {@link BuiltInAgents#getBuiltInAgents()} 动态列表 (非 SDK 入口 gate, 对齐 CC
 * builtInAgents.ts:54-61), 激活 agent.
 *
 * <p><b>[A10 / DEC-SUB-29]</b>: CC {@code getSystemPrompt({toolUseContext})}
 * (claudeCodeGuideAgent.ts:121-204) 动态拼 custom skills/agents/mcp/plugin/settings 5 段上下文 +
 * getFeedbackGuideline, 依赖 toolUseContext.options. Java 端 {@code systemPromptFn} 签名固定为
 * {@code (modelId, additionalWorkingDirectories)}, 不携带 toolUseContext. 本期在写集内 (仅本文件)
 * 实现完整 CC 逻辑结构 {@link #buildGuideSystemPrompt(List, List, List, Map)}, 数据源取自
 * 静态可达 holder (built-in commands / built-in agents / session settings); 活会话的
 * toolUseContext 数据 (custom skills/agents/mcpClients) 需改 getSystemPrompt 签名逐调用下传
 * (超出本写集, 记 concerns). contextSections 为空时返回 basePromptWithFeedback (对齐 CC
 * L201-203 "Return the base prompt if no context to add").
 */
public final class ClaudeCodeGuideAgentDef {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeGuideAgentDef.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String AGENT_TYPE = "claude-code-guide";
    /** CC claudeCodeGuideAgent.ts:119 model='haiku'. */
    public static final String MODEL = "haiku";
    /** CC claudeCodeGuideAgent.ts:120 permissionMode='dontAsk'. */
    public static final String PERMISSION_MODE = "dontAsk";
    public static final String SOURCE = "built-in";
    /** nexusai 无在线文档——改为本地项目文档路径（CLAUDE.md / 探查/ / CHANGELOG / 待前端对接 / 经验） */
    public static final String DOCS_MAP_URL = "CLAUDE.md + 探查/ + CHANGELOG.md（本地项目文档）";
    /** nexusai 无外部 SDK/API 文档——对应"当前没有"项（如 Claude Agent SDK / Claude API 直连） */
    public static final String CDP_DOCS_MAP_URL = "(当前没有对应物：nexusai 无 Agent SDK / API 直连文档，见 buildBaseSystemPrompt 的 '当前没有' 说明)";

    /**
     * CC claudeCodeGuideAgent.ts:100 whenToUse 结构对齐（三域），内容换 nexusai 对应物。
     */
    public static final String WHEN_TO_USE =
        "Use this agent when the user asks questions (\"Can nexusai...\", \"Does nexusai...\", \"How do I...\") " +
        "about: (1) nexusai 后端（Spring Boot）——features, hooks, skills, commands, MCP servers, settings, " +
        "keybindings, subagents, sandboxing（有对应实现的教用法；当前没有的如 IDE 集成明确说明没有）; " +
        "(2) nexusai 模型接入——各厂商模型 API（OpenAI/DeepSeek 等）配置、推理字段、工具调用; " +
        "(3) nexusai 前端对接——API 契约、STOMP 事件、权限冒泡、会话/消息模型。 " +
        "有对应实现如实回答；当前没有的功能（如 Agent SDK、IDE 集成）明确说明当前没有。 " +
        "**IMPORTANT:** Before spawning a new agent, check if there is already a running or recently " +
        "completed guide agent that you can continue via SendMessage.";

    /** CC :26-28 WEB_FETCH_TOOL_NAME='WebFetch'（WebFetchTool/prompt.ts:1）。 */
    private static final String WEB_FETCH_TOOL_NAME = "WebFetch";
    /** CC :26-28 WEB_SEARCH_TOOL_NAME='WebSearch'（WebSearchTool/prompt.ts:1）。 */
    private static final String WEB_SEARCH_TOOL_NAME = "WebSearch";

    private ClaudeCodeGuideAgentDef() {}

    /**
     * nexusai 本地 guide 工具集：本地项目搜索（Glob/Grep/Read）。
     * nexusai 无在线文档，不再需要 WebFetch/WebSearch（改为读本地代码与文档）。
     *
     * <p>hasEmbeddedSearchTools 参数保留（兼容既有调用签名）：embedded 构建可用 Bash+Read（读文件/跑命令），
     * 否则 Glob+Grep+Read（纯搜索）。
     */
    public static List<String> tools(boolean hasEmbeddedSearchTools) {
        return hasEmbeddedSearchTools
            ? List.of("Bash", "Read")
            : List.of("Glob", "Grep", "Read");
    }

    /** 本地搜索提示片段。 */
    public static String localSearchHint(boolean hasEmbeddedSearchTools) {
        return hasEmbeddedSearchTools ? "Read and Bash" : "Read, Glob, and Grep";
    }

    /**
     * 构造 claude-code-guide 内置 Agent 定义 · 对齐 CC CLAUDE_CODE_GUIDE_AGENT (claudeCodeGuideAgent.ts:98-120).
     *
     * <p>外部用户场景 (non-embedded): tools=[Glob,Grep,Read,WebFetch,WebSearch], model='haiku',
     * permissionMode='dontAsk'. systemPromptFn 调用动态 getSystemPrompt 结构 ({@link #buildGuideSystemPrompt()}),
     * 对齐 CC getSystemPrompt({toolUseContext}) (claudeCodeGuideAgent.ts:121-204).
     *
     * @return {@link AgentDefinition.BuiltInAgentDefinition} 注册到 {@link BuiltInAgents}
     */
    public static AgentDefinition.BuiltInAgentDefinition create() {
        BiFunction<String, List<String>, String> systemPromptFn = (modelId, dirs) -> buildGuideSystemPrompt();
        return AgentDefinition.BuiltInAgentDefinition.builder(
                AGENT_TYPE,
                WHEN_TO_USE,
                systemPromptFn)
            .tools(tools(false))                 // CC :103-116 non-embedded (外部用户)
            .model(MODEL)                         // CC :119 'haiku'
            .permissionMode(PERMISSION_MODE)      // CC :120 'dontAsk'
            .build();
    }

    /**
     * 动态 getSystemPrompt 无参入口（create() 的 systemPromptFn 调用）· 对齐 CC getSystemPrompt
     * (claudeCodeGuideAgent.ts:121-204)。
     *
     * <p>Java 端 systemPromptFn 签名 {@code (modelId, additionalWorkingDirectories)} 不携带
     * toolUseContext, 故 5 段上下文的 commands/activeAgents/mcpClients 数据取自静态可达 holder
     * (built-in 命令/agent + session settings); 活会话 custom skills/agents/mcpClients 逐调用
     * 下传需改 getSystemPrompt 签名 (超出本写集, 记 concerns)。数据缺失时对应段省略 →
     * contextSections 为空 → 返回 basePromptWithFeedback (对齐 CC L201-203)。
     */
    static String buildGuideSystemPrompt() {
        List<Command> commands = BuiltInCommands.getAll();
        List<AgentDefinition> activeAgents = BuiltInAgents.getBuiltInAgents();
        List<String> mcpClientNames = List.of();
        Map<String, Object> settings = readSessionSettings();
        return buildGuideSystemPrompt(commands, activeAgents, mcpClientNames, settings);
    }

    /**
     * 完整动态 getSystemPrompt · 对齐 CC getSystemPrompt({toolUseContext}) (claudeCodeGuideAgent.ts:121-204)。
     *
     * <p>5 段上下文 + getFeedbackGuideline + basePromptWithFeedback + config header 块, 逐行翻译 CC:
     * <ol>
     *   <li>custom skills (:128-136) — commands.filter(type==='prompt')</li>
     *   <li>custom agents (:139-150) — activeAgents.filter(source !== 'built-in')</li>
     *   <li>MCP servers (:153-159) — mcpClients names</li>
     *   <li>plugin commands (:162-170) — commands.filter(type==='prompt' && source==='plugin')</li>
     *   <li>user settings (:173-180) — getSettings_DEPRECATED() JSON</li>
     * </ol>
     * contextSections 非空 → 追加 "# User's Current Configuration" 头块 (:188-200);
     * 否则返回 basePromptWithFeedback (:201-203)。
     *
     * @param commands      全部命令列表（CC original: toolUseContext.options.commands）
     * @param activeAgents  活跃 agent 定义列表（CC original: toolUseContext.options.agentDefinitions.activeAgents）
     * @param mcpClientNames MCP client 名称列表（CC original: toolUseContext.options.mcpClients）
     * @param settings       用户 settings 映射（CC original: getSettings_DEPRECATED()）
     * @return 完整 system prompt（base + feedback + 可选 config header）
     */
    static String buildGuideSystemPrompt(List<Command> commands, List<AgentDefinition> activeAgents,
                                         List<String> mcpClientNames, Map<String, Object> settings) {
        List<String> contextSections = new ArrayList<>();

        // 1. Custom skills（CC :128-136）
        List<Command> customCommands = commands.stream()
            .filter(cmd -> "prompt".equals(cmd.getType()))
            .collect(Collectors.toList());
        if (!customCommands.isEmpty()) {
            String commandList = customCommands.stream()
                .map(cmd -> "- /" + cmd.getName() + ": " + safeDescription(cmd.getDescription()))
                .collect(Collectors.joining("\n"));
            contextSections.add("**Available custom skills in this project:**\n" + commandList);
        }

        // 2. Custom agents from .claude/agents/（CC :139-150）
        List<AgentDefinition> customAgents = activeAgents.stream()
            .filter(a -> a.source() != null && !"built-in".equals(a.source()))
            .collect(Collectors.toList());
        if (!customAgents.isEmpty()) {
            String agentList = customAgents.stream()
                .map(a -> "- " + a.agentType() + ": " + safeDescription(a.whenToUse()))
                .collect(Collectors.joining("\n"));
            contextSections.add("**Available custom agents configured:**\n" + agentList);
        }

        // 3. MCP servers（CC :153-159）
        if (mcpClientNames != null && !mcpClientNames.isEmpty()) {
            String mcpList = mcpClientNames.stream()
                .map(n -> "- " + n)
                .collect(Collectors.joining("\n"));
            contextSections.add("**Configured MCP servers:**\n" + mcpList);
        }

        // 4. Plugin commands（CC :162-170）
        List<Command> pluginCommands = commands.stream()
            .filter(cmd -> "prompt".equals(cmd.getType()) && cmd.getSource() == CommandSource.PLUGIN)
            .collect(Collectors.toList());
        if (!pluginCommands.isEmpty()) {
            String pluginList = pluginCommands.stream()
                .map(cmd -> "- /" + cmd.getName() + ": " + safeDescription(cmd.getDescription()))
                .collect(Collectors.joining("\n"));
            contextSections.add("**Available plugin skills:**\n" + pluginList);
        }

        // 5. User settings（CC :173-180）
        if (settings != null && !settings.isEmpty()) {
            String settingsJson = toSettingsJson(settings);
            contextSections.add("**User's settings.json:**\n```json\n" + settingsJson + "\n```");
        }

        // getFeedbackGuideline + basePromptWithFeedback（CC :183-185）
        String feedbackGuideline = getFeedbackGuideline();
        String basePromptWithFeedback = buildBaseSystemPrompt() + "\n" + feedbackGuideline;

        // contextSections 非空 → 追加配置头块（CC :188-200）; 否则返回 basePromptWithFeedback（CC :201-203）
        if (!contextSections.isEmpty()) {
            String joined = String.join("\n\n", contextSections);
            if (log.isDebugEnabled()) {
                log.debug("[ClaudeCodeGuideAgentDef] guide getSystemPrompt 动态拼接 {} 段配置上下文 (custom skills={} custom agents={} mcp={} plugin={} settings={})",
                    contextSections.size(), customCommands.size(), customAgents.size(),
                    mcpClientNames != null ? mcpClientNames.size() : 0, pluginCommands.size(),
                    settings != null ? settings.size() : 0);
            }
            return basePromptWithFeedback
                + "\n\n---\n\n# User's Current Configuration\n\n"
                + "The user has the following custom setup in their environment:\n\n"
                + joined
                + "\n\nWhen answering questions, consider these configured features and proactively suggest them when relevant.";
        }
        return basePromptWithFeedback;
    }

    /**
     * 静态基础 system prompt · 对齐 CC getClaudeCodeGuideBasePrompt (claudeCodeGuideAgent.ts:24-86) 全文。
     *
     * <p><b>[A10 / D2 △-016]</b>: 旧版为缩短版（approach 仅 1-5 步、guidelines 整体缺失）。本期补全 CC
     * 全文：approach 1-7 步（含 WebSearch / 本地文件引用 localSearchHint）+ guidelines 5 条 + 收尾句。
     * 外部用户 localSearchHint="Read, Glob, and Grep"（:26-28 non-embedded 分支）。
     */
    static String buildBaseSystemPrompt() {
        return "You are the nexusai guide agent. Your primary responsibility is helping developers understand " +
            "and use the nexusai project — a Java (Spring Boot) replica of Claude Code with web multi-session support.\n\n" +
            "**Your expertise spans three domains:**\n\n" +
            "1. **nexusai 后端**（the system）: 架构（AgentLoop/Hooks/Skills/Commands/MCP/权限），" +
            "启动/编译，配置（application.yml、DB settings），目录语义（~/.nexusai 自有根 [appName 动态 " + NexusaiPaths.getAppName() + "] + ~/.claude 兼容读）。\n\n" +
            "2. **模型接入**（对应 CC 的 Claude API 域）: 各厂商模型 API（OpenAI/DeepSeek 等）配置、" +
            "推理字段（openai-reasoning-field）、工具调用、流式（STOMP/SSE）。\n\n" +
            "3. **前端对接**（nexusai 特有域）: API 契约、STOMP 事件流、权限冒泡、会话/消息模型、待前端对接登记。\n\n" +
            "**Documentation sources（本地项目文件，用 " + localSearchHint(false) + " 读取）：**\n\n" +
            "- **CLAUDE.md**（项目根）: 开发规则（先思后码/目标驱动/编译命令/对齐 CC 经验）、变更登记、Git 规范。\n" +
            "- **探查/** 目录: 目录语义登记（claude-dir-io-register）、nexusai 复刻目录兼容策略（nexusai-claude-dir-strategy）。\n" +
            "- **CHANGELOG.md** / **待前端对接.md** / **经验.md**: 变更记录、前端对接登记、开发经验。\n" +
            "- **Open-ClaudeCode/src**（CC 真源）: 行为对齐参照。\n\n" +
            "**Approach:**\n" +
            "1. Determine which domain the user's question falls into\n" +
            "2. Use " + localSearchHint(false) + " to search the project source and docs\n" +
            "3. Read the most relevant files (CLAUDE.md, module code, 探查/ docs, CHANGELOG)\n" +
            "4. Provide clear, actionable guidance based on the actual project state\n" +
            "5. If the feature does NOT exist in nexusai（如 Agent SDK、IDE 集成），explicitly state it is not currently implemented\n\n" +
            "**Guidelines:**\n" +
            "- Always ground answers in the actual project code and docs — never assume\n" +
            "- Keep responses concise and actionable\n" +
            "- Include specific file paths and code locations when helpful\n" +
            "- Reference exact file paths in your responses\n" +
            "- Help users discover features by proactively suggesting related modules or capabilities\n" +
            "- 涉及行为对齐的问题，参考 Open-ClaudeCode/src 真源与 探查/ 报告\n\n" +
            "Complete the user's request by providing accurate, code-grounded guidance.";
    }

    /**
     * getFeedbackGuideline · 对齐 CC (claudeCodeGuideAgent.ts:89-96)。
     *
     * <p>CC :92-95 为条件结构：isUsing3PServices() 为真走 :93 ISSUES_EXPLAINER（github issues 指引），
     * 否则走 :95 /feedback。Java 仅 anthropic provider 直连，无 bedrock/vertex/foundry 3P 通道 →
     * isUsing3PServices() 恒 false，故本方法<b>省略 3P 分支</b>，直接返回 :95 /feedback 常量
     * （行为等价 CC 恒非 3P 分支；不保留恒 false 条件结构，如实标注分支省略）。
     */
    static String getFeedbackGuideline() {
        return "- When you cannot find an answer or the feature doesn't exist, direct the user to use /feedback to report a feature request or bug";
    }

    /**
     * 读取 session settings（对齐 CC getSettings_DEPRECATED()）。
     *
     * <p>从 {@link SettingsCache#instance()} 读 sessionSettingsCache；非 {@link Map} / null → 空 Map
     * （settings 段省略, 对齐 CC Object.keys(settings).length === 0）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readSessionSettings() {
        Object cached = SettingsCache.instance().getSessionSettingsCache();
        if (cached instanceof Map<?, ?>) {
            return (Map<String, Object>) cached;
        }
        return Map.of();
    }

    private static String safeDescription(String description) {
        return description != null ? description : "";
    }

    /** settings 序列化 · 对齐 CC jsonStringify(settings, null, 2)（slowOperations.ts）。 */
    private static String toSettingsJson(Map<String, Object> settings) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[ClaudeCodeGuideAgentDef] settings JSON 序列化失败, 回退 toString: {}", e.getMessage());
            }
            return String.valueOf(settings);
        }
    }
}

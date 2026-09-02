package com.nexusai.application.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.skill.SkillsLoader;
import com.nexusai.application.agent.telemetry.skill.SkillLoadedEvent;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.SkillToolPrompt;
import com.nexusai.model.command.Command;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.SystemPromptSectionCache;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemTokenCounts;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.infra.llm.CountTokensClient;
import com.nexusai.infra.llm.CountTokensClient.ToolSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * /context analyze token 消费方 · 对齐 CC {@code analyzeContextUsage}
 * （Open-ClaudeCode/src/utils/analyzeContext.ts:918-983）的 system/memory/tools 计数段。
 *
 * <p>RES-R5（09 §六 R5 用户拍板：接入 /context analyze web 接口形式）——为重建的纯能力
 * {@link SystemPromptTokenCounter}（09 §五③）提供可验证消费方；RES-R5-2（09 §十一 R5-2）
 * 补齐 memory/tools 计数段。CC {@code analyzeContextUsage} 在 Promise.all 中并行执行
 * {@code countSystemTokens / countMemoryFileTokens / countBuiltInToolTokens / countMcpToolTokens}
 * （analyzeContext.ts:950-983），Java 本服务顺序执行三计数段（语义等价：各段独立、结果聚合，
 * CC 并行是 async 实现细节，非结果语义）。
 *
 * <p>组装链（D1 段 · analyzeContext.ts:938-947）与 LlmAgentLoop 同款组件：
 * <ol>
 *   <li><b>默认组装</b>：{@link SystemPromptAssembler#assemble}（7 静态 + registry 动态，
 *       prompts.ts:562-576），输入为 web 无状态 best-effort（enabledTools 空/model null，
 *       对齐 ToolRegistrationConfig buildManualDefaultSysPromptAssemble 同款偏差登记）；</li>
 *   <li><b>effectiveSystemPrompt</b>：{@link EffectiveSystemPromptBuilder#build}（custom 替换
 *       default，append 恒末尾，systemPrompt.ts:115-122）——web 端点无 AgentState，custom/append
 *       从请求参数传入（09 §九 RES-R5 登记通道）；</li>
 *   <li><b>systemContext</b>：{@link SystemPromptContextProvider#getSystemContext}（gitStatus?/
 *       cacheBreaker?，context.ts:116-150），快照语义：每次 analyze 构建全新 provider
 *       （CC getSystemContext 进程级 memoize 在 Java 端为会话级实例，本端点无会话 → 按调用即取）；</li>
 * </ol>
 *
 * <p><b>计数段（RES-R5-2）</b>：
 * <ul>
 *   <li><b>system 段</b>（analyzeContext.ts:963-964）：{@link SystemPromptTokenCounter#count} →
 *       {@code {systemPromptTokens, systemPromptSections}}；</li>
 *   <li><b>memory 段</b>（analyzeContext.ts:320-361 countMemoryFileTokens）：注入的 memory 文件
 *       逐文件 {@code countTokensWithFallback([{role:'user',content}], [])}（:342-345）求和 →
 *       {@code {claudeMdTokens, memoryFileDetails}}；</li>
 *   <li><b>tools 段</b>（analyzeContext.ts:363-515 countBuiltInToolTokens + :616-730
 *       countMcpToolTokens）：built-in（{@code !isMcp}）与 MCP（{@code isMcp}）分类，
 *       各经 countToolDefinitionTokens（:234-258）计数 → {@code {builtInToolTokens, mcpToolTokens}}。</li>
 * </ul>
 *
 * <p><b>生产接线（IMP-CM-16 · OPD-CM3-05/A03）</b>：web analyze 无 AgentState/工具上下文，
 * 但 memory 文件源与 tools 列表<b>经 Spring 注入真实生产源</b>——memory 段接
 * {@link ClaudemdEngine#getMemoryFiles(boolean)} + {@link ClaudemdEngine#filterInjectedMemoryFiles}
 * （CC analyzeContext.ts:329，F1 已有），tools 段接 {@link ToolRegistry#getTools}
 * （CC buildAllTools print.ts:1474-1500，tool 模块）；测试/POJO 仍经构造注入假原料。
 * 权限 deny 过滤（CC appState.toolPermissionContext）web 无上下文 → 不应用（与
 * minimalAssemblyInput enabledTools 空 的既有偏差登记保持一致）。
 *
 * <p><b>Java 端工具计数（RES-C9 对齐 CC）</b>：CC countToolDefinitionTokens 用
 * {@code countTokensWithFallback([], toolSchemas)}（tools 数组随请求发送，:250）——Java 端
 * {@link CountTokensClient#countTokensForTools(List)} 对齐此语义（tools 数组作为请求参数，
 * 非序列化 JSON 文本），{@link AnthropicCountTokensClient} 请求体含 tools 数组（tokenEstimation.ts:172-187）。
 * 相应 {@code TOOL_TOKEN_COUNT_OVERHEAD=500}（analyzeContext.ts:68-75，tools 数组 API 前缀开销补偿）
 * 在 {@link #countToolDefinitionTokens(List)} 中按 {@code Math.max(0, raw - 500)} 扣减
 * （analyzeContext.ts:479/:638-641，每组 bulk 调用扣减一次）。
 */
@Service
public class ContextAnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(ContextAnalyzeService.class);

    // ════════════════════════════════════════════════════════════════════════
    // 返回结构（RES-R5-2）· 对齐 CC analyzeContextUsage 各计数段返回
    // ════════════════════════════════════════════════════════════════════════

    /**
     * memory 文件明细 · CC original: MemoryFile（analyzeContext.ts:353-357）。
     *
     * @param path   memory 文件相对路径（CC original: file.path）
     * @param type   memory 文件类型（CC original: file.type，如 'claude' / 'rules'）
     * @param tokens 该文件 content 真实 countTokens 数（CC original: :347 tokens||0）
     */
    public record MemoryFileDetail(String path, String type, int tokens) {
    }

    /**
     * memory 段计数 · CC original: countMemoryFileTokens 返回结构（analyzeContext.ts:320-361）。
     *
     * @param claudeMdTokens 逐文件计数求和（:351-357）
     * @param memoryFiles    逐文件明细（:353-357）
     */
    public record MemoryTokenCounts(int claudeMdTokens, List<MemoryFileDetail> memoryFiles) {
    }

    /**
     * tools 段计数 · CC original: countBuiltInToolTokens + countMcpToolTokens
     * （analyzeContext.ts:363-515 / :616-730）· 对应分类输出 'System tools'/'MCP tools'
     * （analyzeContext.ts:1021-1029 / :1033-1039）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] {@code builtInToolTokens} 对齐 CC 响应字段
     * （analyzeContext.ts:501-514）＝ built-in 工具 schema <b>全量</b>计数，不扣
     * skillFrontmatterTokens；skill 扣减（{@code systemToolsTokens = builtInToolTokens -
     * skillFrontmatterTokens}，analyzeContext.ts:1021）由 {@link #buildCategories} 在
     * categories 中承载（CC :1022-1029）。
     *
     * @param builtInToolTokens built-in（非 MCP）工具 schema 全量计数（:501-514，非 deferred 路径，不扣 skill）
     * @param mcpToolTokens     MCP 工具 schema 计数（:722-725）
     */
    public record ToolTokenCounts(int builtInToolTokens, int mcpToolTokens) {
    }

    /**
     * [ALIGN-HS-1 OQ-1] 单技能 frontmatter token 明细 · CC original: {@code SkillFrontmatter}
     * （analyzeContext.ts:187 + :589-595 {@code {name, source, tokens}}）。
     *
     * @param name   技能名（CC original: name，getCommandName(skill) 产物）
     * @param source 技能源（CC original: source，skill.type==='prompt' ? skill.source : 'plugin'）
     * @param tokens frontmatter token 估算（CC original: estimateSkillFrontmatterTokens(skill)）
     */
    public record SkillFrontmatterDetail(String name, String source, int tokens) {
    }

    /**
     * [ALIGN-HS-1 OQ-1] skill 段计数 · CC original: {@code countSkillTokens} 的
     * {@code skillInfo}（analyzeContext.ts:560-564 {@code {totalSkills, includedSkills, skillFrontmatter}}）
     * + 汇总 {@code skillFrontmatterTokens}（analyzeContext.ts:994-997 reduce 求和）。
     *
     * @param totalSkills            技能总数（CC original: totalSkills）
     * @param skillFrontmatterTokens 技能 frontmatter token 汇总（CC original: skillFrontmatterTokens，:994）
     * @param skillFrontmatter       逐技能 frontmatter 明细（CC original: skillFrontmatter，:599-604）
     */
    public record SkillTokenCounts(int totalSkills, int skillFrontmatterTokens, List<SkillFrontmatterDetail> skillFrontmatter) {
    }

    /**
     * 展示分类条目 · CC original: {@code ContextCategory}（analyzeContext.ts:111-115
     * {@code {name, tokens, color, isDeferred?}}）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] web 端点仅产出 CC 可计算的非 deferred 类别：
     * 'System prompt' / 'System tools' / 'MCP tools' / 'Memory files' / 'Skills'；
     * Custom agents / Messages / deferred 类别（无 agentDefinitions / messages 原料）不产出。
     *
     * @param name   类别名（CC original: name，'System prompt' / 'System tools' / 'MCP tools' / 'Memory files' / 'Skills'）
     * @param tokens 该类别 token（CC original: tokens）
     * @param color  主题色键（CC original: color: keyof Theme，'promptBorder' / 'inactive' / 'cyan_FOR_SUBAGENTS_ONLY' / 'claude' / 'warning'）
     */
    public record ContextCategory(String name, int tokens, String color) {
    }

    /**
     * /context analyze 总返回 · CC original: analyzeContextUsage 各计数段结果
     * （analyzeContext.ts:950-983 Promise.all 解构 + :1007-1087 分类）。
     *
     * @param system     system 段（systemPromptTokens + systemPromptSections）
     * @param memory     memory 段（claudeMdTokens + memoryFileDetails）
     * @param tools      tools 段（builtInToolTokens（全量，不扣）+ mcpToolTokens）
     * @param skill      [ALIGN-HS-1 OQ-1] skill 段（totalSkills + skillFrontmatterTokens + 明细）
     * @param categories [IMP-F2-2 · OPD-CM5-F-17] 展示分类列表（CC original: categories，:1007-1087；
     *                   'System tools' 承载 builtInToolTokens - skillFrontmatterTokens 扣减值，:1021-1029）
     */
    public record ContextAnalyzeResult(SystemTokenCounts system, MemoryTokenCounts memory,
                                       ToolTokenCounts tools, SkillTokenCounts skill,
                                       List<ContextCategory> categories) {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 计数原料（web 无状态 best-effort 空列表 · 测试注入假原料）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * memory 段计数原料 · CC original: getMemoryFiles() + filterInjectedMemoryFiles 产物
     * （claudemd.ts）。生产（claudemdEngine 注入）走 {@link #resolveMemoryFiles()} 解析真实
     * 文件；测试/POJO 经构造注入假列表。
     *
     * @param path    memory 文件相对路径
     * @param type    memory 文件类型（CC original: file.type，ccName() 如 'Project'/'Local'）
     * @param content memory 文件内容（作为 user 消息计数，analyzeContext.ts:342-345）
     */
    public record MemoryFileEntry(String path, String type, String content) {
    }

    /**
     * tools 段计数原料 · CC original: Tool schema 投影（toolToAPISchema 产物 name/description/input_schema）。
     * 生产（toolRegistry 注入）走 {@link #resolveTools()} 解析真实工具列表；测试/POJO 经构造注入假列表。
     *
     * @param name       工具名（CC original: tool.name）
     * @param description 工具描述（CC original: toolToAPISchema description，prompt() ?? description()）
     * @param inputSchema 输入 schema（CC original: toolToAPISchema input_schema，inputJSONSchema ?? inputSchema）
     * @param mcp        是否 MCP 工具（CC original: tool.isMcp，:375/:628 分类依据）
     */
    public record ToolDefinition(String name, String description, JsonNode inputSchema, boolean mcp) {
    }

    /** systemContext 来源（CC getSystemContext 产物）· null → 懒建并缓存真实 provider */
    private final Supplier<Map<String, String>> systemContextSource;

    /** 逐 section countTokens 客户端 · CC original: countTokensWithFallback（analyzeContext.ts:301）。 */
    private final CountTokensClient countTokensClient;

    /** memory 段计数原料（CC getMemoryFiles 产物）· 构造注入（测试假列表）；生产走 {@link #claudemdEngine} */
    private final List<MemoryFileEntry> memoryFiles;

    /** tools 段计数原料（CC tools 列表投影）· 构造注入（测试假列表）；生产走 {@link #toolRegistry} */
    private final List<ToolDefinition> tools;

    /** [ALIGN-HS-1 OQ-1 / FIX-B2] skill 段计数原料（CC getLimitedSkillToolCommands 产物）· 测试注入假列表；
     *  生产走 {@link #skillRegistry} 解析（FIX-B2 拍板#4，不再 List.of() 空） */
    private final List<Command> skills;

    /** [FIX-B2 拍板#4] 生产 skill 数据源（CC {@code getLimitedSkillToolCommands(getCwd())}
     *  analyzeContext.ts:567 → {@code getSkillToolCommands(cwd)} prompt.ts:213-215 → Java
     *  {@link SkillToolPrompt#getLimitedSkillToolCommands}）。
     *  <p>Spring 构造注入；null（测试/POJO）→ 回退注入 {@link #skills} 列表。生产经
     *  {@link SkillRegistry#getModelInvocableCommands()}（对齐 CC commands.ts:563 getSkillToolCommands）
     *  每 analyze 调用解析真实技能列表（CC countSkillTokens 内部调用点 analyzeContext.ts:567，Java
     *  惰性解析对齐 memoize + refresh 语义）。 */
    private final SkillRegistry skillRegistry;

    /**
     * 生产 memory 段数据源（IMP-CM-16 · OPD-CM3-05/A03）· CC {@code getMemoryFiles()} +
     * {@code filterInjectedMemoryFiles}（claudemd.ts:790-1075 / :1142-1151）。
     * Spring 构造注入（ToolRegistrationConfig @Bean）；null（测试/POJO）→ 回退注入 {@link #memoryFiles}。
     * 生产经 {@code getMemoryFiles(false)} → filterInjectedMemoryFiles 每 analyze 调用解析真实记忆文件
     * （对齐 CC countMemoryFileTokens 内部调用点 analyzeContext.ts:329，memoize 由 ClaudemdEngine 承载）。
     */
    private final ClaudemdEngine claudemdEngine;

    /**
     * 生产 tools 段数据源（IMP-CM-16 · OPD-CM3-05/A03）· CC {@code buildAllTools(appState)}
     * （print.ts:1474-1500 assembleToolPool → getTools）。
     * Spring 构造注入（@Component）；null（测试/POJO）→ 回退注入 {@link #tools}。
     * 生产经 {@link ToolRegistry#getTools}（SPECIAL_TOOLS 剔除 + isEnabled 过滤，对齐 CC
     * assembleToolPool → tools.ts:271-327 getTools；permCtx=null → 无 deny 过滤，web 无状态 best-effort）
     * 每 analyze 调用解析真实工具列表。
     */
    private final ToolRegistry toolRegistry;

    /** 懒建缓存的真实 SystemPromptContextProvider（对齐 CC getSystemContext 进程级 memoize 单实例） */
    private volatile SystemPromptContextProvider cachedProvider;

    /**
     * Spring 入口（RES-04 修复 + FIX-B2 拍板#4 + IMP-CM-16 OPD-CM3-05/A03）· 显式 {@code @Autowired}
     * 让容器在多构造器下按 CountTokensClient + SkillRegistry + ClaudemdEngine + ToolRegistry 注入：
     * 真实 systemContext（懒建单实例 provider）+ 真实 countTokens 客户端 + 真实 skill 数据源
     * （SkillRegistry → {@link SkillToolPrompt#getLimitedSkillToolCommands}，对齐 CC
     * {@code getLimitedSkillToolCommands(getCwd())} analyzeContext.ts:567）+ 真实 memory 源
     * （{@link ClaudemdEngine#getMemoryFiles} + filterInjectedMemoryFiles，claudemd.ts:329）
     * + 真实 tools 源（{@link ToolRegistry#getTools}，CC buildAllTools print.ts:1474-1500）。
     * package-private 测试构造器不受影响。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ContextAnalyzeService(CountTokensClient countTokensClient, SkillRegistry skillRegistry,
                                 ClaudemdEngine claudemdEngine, ToolRegistry toolRegistry) {
        this(null, countTokensClient, List.of(), List.of(), List.of(), skillRegistry, claudemdEngine, toolRegistry);
    }

    /** 便捷构造（既有测试 2 参：真实计数器 + 真实 skill 数据源）· memory/tools 未注入 claudemdEngine/
     *  toolRegistry → 回退注入空列表（FIX-B2 拍板#4 测试语义不变）。 */
    ContextAnalyzeService(CountTokensClient countTokensClient, SkillRegistry skillRegistry) {
        this(null, countTokensClient, List.of(), List.of(), List.of(), skillRegistry, null, null);
    }

    /**
     * 测试注入：可控 systemContext 来源 + 可控计数器（memory/tools/skill 空）。

     *
     * @param systemContextSource systemContext map 提供者（null → 构建真实 provider）
     * @param countTokensClient   countTokens 客户端（逐 section 计数委托）
     */
    ContextAnalyzeService(Supplier<Map<String, String>> systemContextSource, CountTokensClient countTokensClient) {
        this(systemContextSource, countTokensClient, List.of(), List.of(), List.of(), null, null, null);
    }

    /**
     * 全参注入（RES-R5-2 测试）：可控 systemContext + 计数器 + memory/tools 假原料。
     *
     * @param systemContextSource systemContext map 提供者（null → 构建真实 provider）
     * @param countTokensClient   countTokens 客户端（system/memory/tools 三计数段共用）
     * @param memoryFiles         memory 段计数原料（CC getMemoryFiles 产物）
     * @param tools               tools 段计数原料（CC tools 列表投影）
     */
    ContextAnalyzeService(Supplier<Map<String, String>> systemContextSource,
                          CountTokensClient countTokensClient,
                          List<MemoryFileEntry> memoryFiles,
                          List<ToolDefinition> tools) {
        this(systemContextSource, countTokensClient, memoryFiles, tools, List.of(), null, null, null);
    }

    /**
     * 全参注入 + skill 原料（[ALIGN-HS-1 OQ-1]）：追加 skills（CC getLimitedSkillToolCommands 产物）。
     *
     * @param systemContextSource systemContext map 提供者（null → 构建真实 provider）
     * @param countTokensClient   countTokens 客户端（system/memory/tools/skill 各计数段共用）
     * @param memoryFiles         memory 段计数原料（CC getMemoryFiles 产物）
     * @param tools               tools 段计数原料（CC tools 列表投影）
     * @param skills              [ALIGN-HS-1 OQ-1] skill 段计数原料（CC getLimitedSkillToolCommands 产物）
     */
    ContextAnalyzeService(Supplier<Map<String, String>> systemContextSource,
                          CountTokensClient countTokensClient,
                          List<MemoryFileEntry> memoryFiles,
                          List<ToolDefinition> tools,
                          List<Command> skills) {
        this(systemContextSource, countTokensClient, memoryFiles, tools, skills, null, null, null);
    }

    /**
     * 全参注入 + skill 数据源（[FIX-B2 拍板#4]）+ 生产 memory/tools 源（[IMP-CM-16 OPD-CM3-05/A03]）。
     *
     * @param systemContextSource systemContext map 提供者（null → 构建真实 provider）
     * @param countTokensClient   countTokens 客户端（system/memory/tools/skill 各计数段共用）
     * @param memoryFiles         memory 段计数原料（CC getMemoryFiles 产物，测试注入；生产走 claudemdEngine）
     * @param tools               tools 段计数原料（CC tools 列表投影，测试注入；生产走 toolRegistry）
     * @param skills              测试注入 skill 列表（CC getLimitedSkillToolCommands 产物）
     * @param skillRegistry       生产 skill 数据源（null → 回退 skills 列表）
     * @param claudemdEngine      生产 memory 段数据源（CC getMemoryFiles + filterInjectedMemoryFiles，null → 回退 memoryFiles）
     * @param toolRegistry        生产 tools 段数据源（CC buildAllTools → getTools，null → 回退 tools）
     */
    ContextAnalyzeService(Supplier<Map<String, String>> systemContextSource,
                          CountTokensClient countTokensClient,
                          List<MemoryFileEntry> memoryFiles,
                          List<ToolDefinition> tools,
                          List<Command> skills,
                          SkillRegistry skillRegistry,
                          ClaudemdEngine claudemdEngine,
                          ToolRegistry toolRegistry) {
        this.systemContextSource = systemContextSource;
        this.countTokensClient = countTokensClient;
        this.memoryFiles = memoryFiles == null ? List.of() : memoryFiles;
        this.tools = tools == null ? List.of() : tools;
        this.skills = skills == null ? List.of() : skills;
        this.skillRegistry = skillRegistry;
        this.claudemdEngine = claudemdEngine;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 分析 context token 使用 · CC original: analyzeContextUsage（analyzeContext.ts:938-983）
     * 的 system/memory/tools 三计数段（CC Promise.all 并行 → Java 顺序执行，结果语义等价）。
     *
     * @param customSystemPrompt 自定义系统提示（非空替换 default，CC original:
     *                           {@code options.customSystemPrompt} systemPrompt.ts:118-119）
     * @param appendSystemPrompt 追加系统提示（恒末尾，CC original:
     *                           {@code options.appendSystemPrompt} systemPrompt.ts:121）
     * @return system/memory/tools 三计数段（各段均真实 countTokens API，无 rough 主路径）
     */
    public ContextAnalyzeResult analyze(String customSystemPrompt, String appendSystemPrompt) {
        long start = System.currentTimeMillis();
        // 生产源解析（IMP-CM-16 · OPD-CM3-05/A03）：memory 接 ClaudemdEngine + tools 接 ToolRegistry
        List<MemoryFileEntry> memorySource = resolveMemoryFiles();
        List<ToolDefinition> toolsSource = resolveTools();
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] analyze 开始: custom={}, append={}, memoryFiles={}, tools={}",
                customSystemPrompt != null, appendSystemPrompt != null, memorySource.size(), toolsSource.size());
        }

        // D1: effectiveSystemPrompt（analyzeContext.ts:938-947）
        SystemPrompt effectiveSystemPrompt = buildEffectiveSystemPrompt(customSystemPrompt, appendSystemPrompt);
        // systemContext（CC getSystemContext，context.ts:116-150）
        Map<String, String> systemContext = resolveSystemContext();
        // D3: 三计数段（analyzeContext.ts:950-983 Promise.all → Java 顺序执行）
        SystemTokenCounts systemCounts = SystemPromptTokenCounter.count(
            effectiveSystemPrompt.elements(), systemContext, countTokensClient);
        MemoryTokenCounts memoryCounts = countMemoryFileTokens(memorySource);
        // [ALIGN-HS-1 OQ-1] skill 段（analyzeContext.ts:986-997 countSkillTokens + skillFrontmatterTokens reduce）
        SkillTokenCounts skillCounts = countSkillTokens();
        ToolTokenCounts toolCounts = countToolTokens(toolsSource);
        // [IMP-F2-2 · OPD-CM5-F-17 改不扣] 展示分类承载 skill 扣减值
        // （CC analyzeContext.ts:1007-1087；'System tools' = builtInToolTokens - skillFrontmatterTokens，:1021）
        List<ContextCategory> categories = buildCategories(systemCounts, memoryCounts, toolCounts, skillCounts);

        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] analyze 完成: 耗时 {} ms, effective 元素数={}, "
                    + "systemContext keys={}, systemPromptTokens={}, sections={}, "
                    + "claudeMdTokens={}, memoryFiles={}, builtInToolTokens={}, mcpToolTokens={}, "
                    + "skillFrontmatterTokens={}, totalSkills={}, categories={}",
                System.currentTimeMillis() - start,
                effectiveSystemPrompt.elements().size(),
                systemContext.keySet(),
                systemCounts.systemPromptTokens(),
                systemCounts.systemPromptSections().size(),
                memoryCounts.claudeMdTokens(),
                memoryCounts.memoryFiles().size(),
                toolCounts.builtInToolTokens(),
                toolCounts.mcpToolTokens(),
                skillCounts.skillFrontmatterTokens(),
                skillCounts.totalSkills(),
                categories.size());
        }
        return new ContextAnalyzeResult(systemCounts, memoryCounts, toolCounts, skillCounts, categories);
    }

    /**
     * [ALIGN-HS-1 OQ-1 + FIX-B2 拍板#4] skill 段计数 · CC original: {@code countSkillTokens}
     * （analyzeContext.ts:554-614）的 skillFrontmatter 估算。
     *
     * <p>CC 对每个技能调 {@code estimateSkillFrontmatterTokens(skill)}（loadSkillsDir.ts:100-105：
     * {@code [name, description, whenToUse].filter(Boolean).join(' ') → round(len/4)}）累加求和
     * （analyzeContext.ts:994-997 {@code skillFrontmatter.reduce((sum, s) => sum + s.tokens, 0)}），
     * 产出 {@code skillInfo.skillFrontmatter}（:599-604）。Java 复用
     * {@link SkillsLoader#estimateSkillFrontmatterTokens(Command)}（迁移后的唯一实现，P3-8）。
     *
     * <p><b>FIX-B2 生产数据源（拍板#4，总汇 §6.5）</b>：CC 在函数内部调用
     * {@code getLimitedSkillToolCommands(getCwd())}（:567）取真实技能列表；Java 生产经
     * {@link #resolveSkillSource()} 解析 {@link SkillToolPrompt#getLimitedSkillToolCommands}
     * （= {@link SkillRegistry#getModelInvocableCommands()}，对齐 CC commands.ts:563
     * getSkillToolCommands）——不再 {@code List.of()} 空注入。测试注入 {@link #skills} 假列表。
     *
     * <p><b>错误隔离（对齐 CC :605-613）</b>：CC countSkillTokens 整体 try/catch →
     * {@code {skillTokens: 0, skillInfo: {totalSkills: 0, includedSkills: 0, skillFrontmatter: []}}}
     * （技能加载失败不中断整个 context analyze）；Java 同步包裹（getModelInvocableCommands 的
     * isCommandEnabled 惰性求值可能抛错，SkillRegistry.getSlashCommandToolSkills:907 同款防御）。
     *
     * @return skill 段计数（totalSkills + skillFrontmatterTokens + 逐技能明细）
     */
    private SkillTokenCounts countSkillTokens() {
        try {
            List<Command> source = resolveSkillSource();
            if (source.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ContextAnalyzeService] countSkillTokens: 空 skill 原料（生产 registry 空 或 测试注入空）→ {0, 0, []}");
                }
                return new SkillTokenCounts(0, 0, List.of());
            }
            List<SkillFrontmatterDetail> details = new ArrayList<>(source.size());
            int skillFrontmatterTokens = 0;
            for (Command skill : source) {
                if (skill == null) {
                    continue;
                }
                int tokens = SkillsLoader.estimateSkillFrontmatterTokens(skill);
                skillFrontmatterTokens += tokens;
                // CC analyzeContext.ts:591-593: source = (skill.type==='prompt' ? skill.source : 'plugin')
                //   skill.source 为 SettingSource camelCase（constants.ts:7-21）——复用 SkillLoadedEvent
                //   的 CC 精确字符串映射（SU-△-2 / OQ-1 同源），避免 name().toLowerCase() 漂移为
                //   "user"/"policy_settings" 与遥测 "userSettings"/"policySettings" 并存。
                String sourceVal = "prompt".equals(skill.getType())
                    ? SkillLoadedEvent.skillSourceCcValue(skill.getSource())
                    : "plugin";
                details.add(new SkillFrontmatterDetail(skill.getName(), sourceVal, tokens));
            }
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] countSkillTokens: {} 个技能，skillFrontmatterTokens={}（CC analyzeContext.ts:554-614 + loadSkillsDir.ts:100-105）",
                    details.size(), skillFrontmatterTokens);
            }
            return new SkillTokenCounts(source.size(), skillFrontmatterTokens, details);
        } catch (Exception e) {
            // CC analyzeContext.ts:605-613：技能加载失败 → 返回零值，不中断整个 context analyze
            log.warn("[ContextAnalyzeService] countSkillTokens 失败 → 返回 {0, 0, []} (CC analyzeContext.ts:605-613 错误隔离): {}",
                e.getMessage());
            return new SkillTokenCounts(0, 0, List.of());
        }
    }

    /**
     * [FIX-B2 拍板#4] skill 段计数原料解析 · CC original: {@code getLimitedSkillToolCommands(getCwd())}
     * （analyzeContext.ts:567 → prompt.ts:213-215 → {@code getSkillToolCommands(cwd)} commands.ts:563）。
     *
     * <p>生产（skillRegistry != null）→ {@link SkillToolPrompt#getLimitedSkillToolCommands}
     * （= {@link SkillRegistry#getModelInvocableCommands()}，模型可调用命令清单，与 skill listing
     * 同源）；测试/POJO（null）→ 回退注入 {@link #skills} 列表。每 analyze 调用解析一次（CC
     * countSkillTokens 内部调用点，memoize + refresh 语义由 SkillRegistry 承载）。
     *
     * @return 真实/注入技能列表（CC getLimitedSkillToolCommands 等价物）
     */
    private List<Command> resolveSkillSource() {
        if (skillRegistry != null) {
            List<Command> real = SkillToolPrompt.getLimitedSkillToolCommands(skillRegistry);
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] resolveSkillSource: 生产 registry 解析 {} 个技能 (CC getLimitedSkillToolCommands analyzeContext.ts:567 → prompt.ts:213-215)",
                    real.size());
            }
            return real;
        }
        return skills;
    }

    /**
     * [IMP-CM-16 · OPD-CM3-05/A03] memory 段计数原料解析 · CC original:
     * {@code filterInjectedMemoryFiles(await getMemoryFiles())}（analyzeContext.ts:329）。
     *
     * <p>生产（claudemdEngine != null）→ {@link ClaudemdEngine#getMemoryFiles(boolean)} +
     * {@link ClaudemdEngine#filterInjectedMemoryFiles}（claudemd.ts:790-1075 + :1142-1151，
     * 逐文件投影为 {@link MemoryFileEntry}（path / type.ccName() / content）；测试/POJO（null）→
     * 回退构造注入 {@link #memoryFiles} 列表。每 analyze 调用解析一次（CC countMemoryFileTokens
     * 内部调用点，memoize 语义由 ClaudemdEngine 承载）。
     *
     * @return 真实/注入 memory 文件列表（CC getMemoryFiles + filterInjectedMemoryFiles 产物）
     */
    private List<MemoryFileEntry> resolveMemoryFiles() {
        if (claudemdEngine != null) {
            List<MemoryFileInfo> files = claudemdEngine.filterInjectedMemoryFiles(claudemdEngine.getMemoryFiles(false));
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] resolveMemoryFiles: 生产 claudemdEngine 解析 {} 个 memory 文件（CC analyzeContext.ts:329 filterInjectedMemoryFiles(getMemoryFiles())）",
                    files.size());
            }
            return files.stream()
                .map(f -> new MemoryFileEntry(f.path(), f.type().ccName(), f.content()))
                .toList();
        }
        return memoryFiles;
    }

    /**
     * [IMP-CM-16 · OPD-CM3-05/A03] tools 段计数原料解析 · CC original:
     * {@code buildAllTools(appState)}（print.ts:1474-1500 assembleToolPool → getTools）产物。
     *
     * <p>生产（toolRegistry != null）→ {@link ToolRegistry#getTools(null)}（getTools 语义：
     * SPECIAL_TOOLS 剔除 + isEnabled 过滤，对齐 CC assembleToolPool → tools.ts:271-327 getTools；
     * permCtx=null → 无 deny rule 过滤，web 无状态 best-effort），逐工具投影为
     * {@link ToolDefinition}（name / description / inputSchema / isMcp，投影逻辑与
     * {@link ToolRegistry#toOpenAiToolsArray} 同源 —— CC toolToAPISchema api.ts:157-244）；
     * 测试/POJO（null）→ 回退构造注入 {@link #tools} 列表。每 analyze 调用解析一次
     * （CC /context 命令执行时 buildAllTools 求值）。
     *
     * @return 真实/注入工具列表（CC buildAllTools 产物）
     */
    private List<ToolDefinition> resolveTools() {
        if (toolRegistry != null) {
            return toolRegistry.getTools(null).stream()
                .map(t -> new ToolDefinition(
                    t.name(),
                    toolDescription(t),
                    toolInputSchema(t),
                    t.isMcp()))
                .toList();
        }
        return tools;
    }

    /** 工具描述投影 · CC api.ts:171 {@code description: await tool.prompt(...)}，prompt() 非 null 优先
     *  （对齐 {@link ToolRegistry#toOpenAiToolsArray}）。 */
    private static String toolDescription(Tool t) {
        String prompt = t.prompt();
        return prompt != null ? prompt : t.description();
    }

    /** 工具 schema 投影 · CC api.ts:157-160 {@code inputJSONSchema in tool ? ... : inputSchema}，
     *  inputJSONSchema() 非 null 优先；双 null → 空 object（对齐 {@link ToolRegistry#toOpenAiToolsArray}）。 */
    private static JsonNode toolInputSchema(Tool t) {
        JsonNode schema = t.inputJSONSchema();
        if (schema == null) {
            schema = t.inputSchema();
        }
        return schema != null ? schema : EMPTY_SCHEMA;
    }

    /** 空 JSON object（工具 schema 缺省投影，对齐 toOpenAiToolsArray fn.putObject("parameters")）。 */
    private static final JsonNode EMPTY_SCHEMA = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();

    /**
     * memory 段计数 · CC original: countMemoryFileTokens（analyzeContext.ts:320-361）。
     *
     * <p>已解析的 memory 文件逐文件 {@code countTokensWithFallback([{role:'user',content:file.content}], [])}
     * （:340-349）→ {@code tokens||0} 求和（:351-357）。空原料 → {@code {0, []}} 短路（:333-338）。
     *
     * @param source 已解析 memory 文件列表（{@link #resolveMemoryFiles()} 产物）
     * @return memory 段计数（claudeMdTokens + memoryFileDetails）
     */
    private MemoryTokenCounts countMemoryFileTokens(List<MemoryFileEntry> source) {
        if (source.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] countMemoryFileTokens: 空 memory 原料 → {0, []}");
            }
            return new MemoryTokenCounts(0, List.of());
        }
        List<MemoryFileDetail> details = new ArrayList<>(source.size());
        int claudeMdTokens = 0;
        for (MemoryFileEntry file : source) {
            Integer raw = countTokensClient.countTokens(file.content());
            int tokens = raw == null ? 0 : raw; // analyzeContext.ts:347 tokens||0
            claudeMdTokens += tokens;
            details.add(new MemoryFileDetail(file.path(), file.type(), tokens));
        }
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] countMemoryFileTokens: {} 个 memory 文件，claudeMdTokens={}",
                details.size(), claudeMdTokens);
        }
        return new MemoryTokenCounts(claudeMdTokens, details);
    }

    /**
     * tools 段计数 · CC original: countBuiltInToolTokens（analyzeContext.ts:363-515）+ countMcpToolTokens
     * （:616-730）。built-in（{@code !isMcp}，:375）与 MCP（{@code isMcp}，:628）分类，各经
     * countToolDefinitionTokens 单 bulk 调用计数（:401-409/:631-636）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] {@code builtInToolTokens} 返回 built-in 工具 schema
     * <b>全量</b>计数（对齐 CC 响应字段 :501-514），<b>不</b>再扣 skillFrontmatterTokens；
     * skill 扣减（{@code systemToolsTokens = builtInToolTokens - skillFrontmatterTokens}，:1021）
     * 移至 {@link #buildCategories}（categories 的 'System tools' 类别承载，:1022-1029）。
     *
     * @param defs 已解析工具列表（{@link #resolveTools()} 产物）
     * @return tools 段计数（builtInToolTokens（全量，不扣 skill）+ mcpToolTokens）
     */
    private ToolTokenCounts countToolTokens(List<ToolDefinition> defs) {
        List<ToolDefinition> builtIn = defs.stream().filter(t -> !t.mcp()).toList();
        List<ToolDefinition> mcp = defs.stream().filter(ToolDefinition::mcp).toList();
        return new ToolTokenCounts(
            countToolDefinitionTokens(builtIn),
            countToolDefinitionTokens(mcp));
    }

    /**
     * 展示分类构建 · CC original: analyzeContextUsage 的 categories 段（analyzeContext.ts:1007-1087）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] 分类承载 builtIn/skill 扣减：'System tools' =
     * {@code Math.max(0, builtInToolTokens - skillFrontmatterTokens)}（CC :1021-1029，仅正值展示），
     * 'Skills' = skillFrontmatterTokens（CC :1082-1087，单独展示避免与 SlashCommandTool schema
     * 双重计数）。web 端点无 agentDefinitions / messages 原料 → Custom agents / Messages /
     * deferred 类别（CC :1042-1060/:1076-1081）不产出。
     *
     * @param system system 段（systemPromptTokens）
     * @param memory memory 段（claudeMdTokens）
     * @param tools  tools 段（builtInToolTokens 全量 + mcpToolTokens）
     * @param skill  skill 段（skillFrontmatterTokens）
     * @return 分类列表（仅含正 token 类别，顺序对齐 CC :1010-1087）
     */
    private static List<ContextCategory> buildCategories(SystemTokenCounts system, MemoryTokenCounts memory,
                                                        ToolTokenCounts tools, SkillTokenCounts skill) {
        List<ContextCategory> cats = new ArrayList<>(5);
        // System prompt 恒首（CC :1010-1017，fixed overhead）
        if (system.systemPromptTokens() > 0) {
            cats.add(new ContextCategory("System prompt", system.systemPromptTokens(), "promptBorder"));
        }
        // System tools（builtInToolTokens - skillFrontmatterTokens，扣减值，仅正值展示，CC :1021-1029）
        int systemToolsTokens = Math.max(0, tools.builtInToolTokens() - skill.skillFrontmatterTokens());
        if (systemToolsTokens > 0) {
            cats.add(new ContextCategory("System tools", systemToolsTokens, "inactive"));
        }
        // MCP tools（CC :1033-1039）
        if (tools.mcpToolTokens() > 0) {
            cats.add(new ContextCategory("MCP tools", tools.mcpToolTokens(), "cyan_FOR_SUBAGENTS_ONLY"));
        }
        // Memory files（CC :1072-1078）
        if (memory.claudeMdTokens() > 0) {
            cats.add(new ContextCategory("Memory files", memory.claudeMdTokens(), "claude"));
        }
        // Skills（frontmatter token 汇总，单独展示避免双重计数，CC :1082-1087）
        if (skill.skillFrontmatterTokens() > 0) {
            cats.add(new ContextCategory("Skills", skill.skillFrontmatterTokens(), "warning"));
        }
        return List.copyOf(cats);
    }

    /**
     * CC original: TOOL_TOKEN_COUNT_OVERHEAD（analyzeContext.ts:68-75）· API 在 tools 存在时
     * 添加约 500 token 的工具前缀开销（tool prompt preamble），每组 bulk 调用含一次。
     *
     * <p>扣减语义（analyzeContext.ts:479/:638-641）：每组 bulk 调用 {@code Math.max(0, raw - 500)}，
     * 即 built-in 组扣一次、MCP 组扣一次（与 CC countBuiltInToolTokens 单 bulk + countMcpToolTokens
     * 单 bulk 各扣一次等价）。
     */
    static final int TOOL_TOKEN_COUNT_OVERHEAD = 500;

    /**
     * 工具定义 token 计数 · CC original: countToolDefinitionTokens（analyzeContext.ts:234-258）。
     *
     * <p>RES-C9 对齐 CC：tools schema 经 {@link CountTokensClient#countTokensForTools(List)}
     * 以 tools 数组随请求发送（analyzeContext.ts:250 {@code countTokensWithFallback([], toolSchemas)}），
     * 不再把 schema JSON 当 user 消息文本。相应 {@code TOOL_TOKEN_COUNT_OVERHEAD=500} 补偿：
     * {@code Math.max(0, raw - 500)}（analyzeContext.ts:479/:638-641，每组 bulk 调用扣一次）。
     *
     * @param defs 分类后的工具定义（空 → 0）
     * @return 该类工具 schema 总 token（含 overhead 补偿；API 失败/null → 0，:251-257）
     */
    private int countToolDefinitionTokens(List<ToolDefinition> defs) {
        if (defs.isEmpty()) {
            return 0;
        }
        // ToolDefinition → ToolSchema（CC toolToAPISchema 产物投影）
        List<ToolSchema> schemas = defs.stream()
            .map(d -> new ToolSchema(
                d.name(),
                d.description(),
                d.inputSchema()))
            .toList();
        Integer raw = countTokensClient.countTokensForTools(schemas);
        int rawCount = raw == null ? 0 : raw;
        // TOOL_TOKEN_COUNT_OVERHEAD=500 补偿（analyzeContext.ts:479/:638-641）
        int compensated = Math.max(0, rawCount - TOOL_TOKEN_COUNT_OVERHEAD);
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] countToolDefinitionTokens: {} 个工具，raw={}, "
                + "overhead={}, compensated={}（CC TOOL_TOKEN_COUNT_OVERHEAD=500，analyzeContext.ts:68-75）",
                defs.size(), rawCount, TOOL_TOKEN_COUNT_OVERHEAD, compensated);
        }
        return compensated;
    }

    /**
     * D1 段 effectiveSystemPrompt 构建 · CC original: buildEffectiveSystemPrompt
     * （analyzeContext.ts:938-947 → systemPrompt.ts:41-123）。
     *
     * <p>web 无 AgentState：mainThreadAgentDefinition=undefined（N/A），custom/append 由请求传入，
     * default 由 SystemPromptAssembler 组装（与 LlmAgentLoop 主循环同款组件层）。
     *
     * @param customSystemPrompt 自定义系统提示（null/空 → 走默认组装）
     * @param appendSystemPrompt 追加系统提示（null/空 → 不追加）
     * @return effectiveSystemPrompt（元素序即计数序）
     */
    SystemPrompt buildEffectiveSystemPrompt(String customSystemPrompt, String appendSystemPrompt) {
        SystemPromptAssembler assembler = new SystemPromptAssembler(new SystemPromptSectionCache());
        return EffectiveSystemPromptBuilder.build(
            () -> assembler.assemble(minimalAssemblyInput()),  // default 组装（custom 短路时不调用）
            null,                                             // overrideSystemPrompt（CC analyzeContext.ts:939 调用点不传 → 保持 null，SP-01）
            customSystemPrompt,
            appendSystemPrompt);
    }

    /**
     * 最小组装输入 · best-effort 对齐 LlmAgentLoop buildSystemPromptAssemblyInput 的 3P 默认
     * （enabledTools 空/model null/mcpClients 空/outputStyle null/language null）。
     *
     * <p>web analyze 无 per-turn ToolUseContext/SkillCatalog/memoryStorage → 相应字段空，
     * 对齐 ToolRegistrationConfig buildManualDefaultSysPromptAssemble 同款偏差登记
     * （memory 段不产出、intro 无模型名差异）。
     *
     * @return 组装输入（全部可空字段走空/默认）
     */
    private static SystemPromptAssemblyInput minimalAssemblyInput() {
        return new SystemPromptAssemblyInput(
            Set.of(),           // enabledTools（web analyze 无工具上下文）
            null,               // model（analyze 无运行模型）
            List.of(),          // additionalWorkingDirs（Java 主循环单工作目录）
            List.of(),          // mcpClients（Java loop 无 McpClientInfo 通道）
            null,               // outputStyleConfig（Java 无输出风格配置注入）
            List.of(),          // skillToolCommands（web analyze 无 SkillCatalog 通道）
            null,               // language（Java 无语言设置通道）
            null,               // memoryLoader（web analyze 无 memoryStorage 通道）
            false,              // tokenBudgetEnabled（analyze 无 TOKEN_BUDGET flag 通道 · 对齐 CC prompts.ts:538 关时恒不注册）
            null);              // sessionId（web analyze 无会话上下文 → env cwd 走 cwdSupplier/MDC 兜底，现行为）
    }

    /**
     * 生命周期终结 · RES-R5-4 注销接线：服务实例销毁（Spring 容器关闭）时关闭懒建单实例
     * provider，从 {@code SystemPromptInjection.CACHE_CLEAR_HOOKS} 注销其缓存清理回调，
     * 静态表不再累积（register/unregister 成对）。
     *
     * <p><b>时机说明</b>：本服务懒建单实例 provider 复用（RES-R5 REWORK 修复），存活期间
     * <b>不得</b>注销（否则 analyze 后续调用不再受 setter 双清通知）；仅 bean 销毁时关闭。
     *
     * <p><b>约定</b>：无 CC 等价（Java 内部卫生，09 §十一 R5-4 用户拍板）；不触碰计数段
     * （RES-R5-2 独占）。
     */
    @PreDestroy
    void closeProvider() {
        SystemPromptContextProvider provider = cachedProvider;
        if (provider != null) {
            provider.close();
            log.info("[ContextAnalyzeService] closeProvider: 已注销懒建 provider 的缓存清理回调（RES-R5-4）");
        }
    }

    /**
     * systemContext 来源解析 · CC original: getSystemContext（context.ts:116-150）。
     *
     * <p>测试注入 supplier 非 null → 直接用；否则<b>懒建单实例</b>
     * SystemPromptContextProvider（会话冻结日期=首次调用日，用户上下文=进程 cwd 单文件子集，
     * git=真实仓库 try/catch→null）并缓存复用。
     *
     * <p><b>生命周期防泄漏（reflector REWORK + RES-R5-4 根治）</b>：SystemPromptContextProvider 构造即向
     * {@code SystemPromptInjection.CACHE_CLEAR_HOOKS} 静态表注册缓存清理回调；若每请求新建 provider →
     * 每请求永久泄漏一个 Runnable。两层防线：① 对齐 CC getSystemContext 进程级 memoize（单实例），
     * 服务实例持有一个懒建 provider，缓存复用，仅首次调用注册 1 个 hook（REWORK 修复）；
     * ② 静态表补 remove 通道（RES-R5-4，unregisterCacheClearHook + 本服务 {@link #closeProvider()}
     * 销毁时注销）——register/unregister 成对，表不再累积。
     *
     * @return systemContext map（gitStatus?/cacheBreaker?，均条件包含）
     */
    private Map<String, String> resolveSystemContext() {
        if (systemContextSource != null) {
            return systemContextSource.get();
        }
        SystemPromptContextProvider provider = cachedProvider;
        if (provider == null) {
            synchronized (this) {
                provider = cachedProvider;
                if (provider == null) {
                    // [SP-12 补查结论] 生产可达路径改为完整链：原 new UserContextProvider() 无参 →
                    //   claudemdEngine=null → 单文件回退（仅 projectRoot/CLAUDE.md 子集），与 LLM prompt
                    //   注入链（ClaudemdEngine 完整 getClaudeMds + MEMORY_INSTRUCTION_PROMPT 前缀）字节
                    //   不一致 → /context/analyze 的 claudeMd 段低估/漏报 memory 文件。改传 claudemdEngine
                    //   （本类 :246 字段，生产 @Autowired 注入恒非 null；null 时仍回落单文件回退，测试兼容）。
                    //   对齐 CC analyzeContext.ts 消费 getClaudeMds(getMemoryFiles()) 完整链。
                    provider = new SystemPromptContextProvider(
                        LocalDate.now().toString(),
                        new UserContextProvider(claudemdEngine),
                        new GitStatusProvider());
                    cachedProvider = provider;
                }
            }
        }
        return provider.getSystemContext();
    }
}

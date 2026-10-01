package com.nexusai.application.agent.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
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
import com.nexusai.infra.llm.TokenSource;
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
 * {@link ClaudemdEngine#getMemoryFiles(boolean, String)} + {@link ClaudemdEngine#filterInjectedMemoryFiles}
 * （CC analyzeContext.ts:329，F1 已有），tools 段接 {@link ToolRegistry#getTools}
 * （CC buildAllTools print.ts:1474-1500，tool 模块）；测试/POJO 仍经构造注入假原料。
 * 权限 deny 过滤（CC appState.toolPermissionContext）web 无上下文 → 不应用（与
 * minimalAssemblyInput enabledTools 空 的既有偏差登记保持一致）。
 *
 * <p><b>Java 端工具计数（RES-C9 对齐 CC）</b>：CC countToolDefinitionTokens 用
 * {@code countTokensWithFallback([], toolSchemas)}（tools 数组随请求发送，:250）——Java 端
 * {@link CountTokensClient#countTokensForTools(List)} 对齐此语义（tools 数组作为请求参数，
 * 非序列化 JSON 文本），{@link AnthropicCountTokensClient} 请求体含 tools 数组（tokenEstimation.ts:172-187）。
 * 相应 {@code TOOL_TOKEN_COUNT_OVERHEAD=500}（analyzeContext.ts:68-75，<b>API 侧</b> tools 前缀开销补偿）
 * 在 {@link #countToolDefinitionTokens(List)} 中按 {@code Math.max(0, raw - 500)} 扣减
 * （analyzeContext.ts:477-480/:638-641，每组 bulk 调用扣减一次），<b>且只在数值确实来自服务端
 * 计数接口时扣</b>（判据 = {@link CountTokensClient#sourceKind()} == {@link TokenSource#API}）——
 * 本地估算（tiktoken）不含这笔 API 开销，扣了就是系统性低估 500 并被夹成假 0。
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
     * <p>[四态标注] {@code tokens=null} = 算不出来（面板显示「—」），{@code tokenSource} = 数值来源
     * （{@link TokenSource#API} / {@link TokenSource#ESTIMATE} / {@link TokenSource#UNAVAILABLE}）。
     *
     * @param path        memory 文件相对路径（CC original: file.path）
     * @param type        memory 文件类型（CC original: file.type，如 'claude' / 'rules'）
     * @param tokens      该文件 content 的 countTokens 数；<b>null = 算不出来</b>（⛔ 绝不写成 0）
     * @param tokenSource 数值来源
     */
    public record MemoryFileDetail(String path, String type, Integer tokens, TokenSource tokenSource) {
    }

    /**
     * memory 段计数 · CC original: countMemoryFileTokens 返回结构（analyzeContext.ts:320-361）。
     *
     * <p>[四态标注] 任一文件算不出来 ⇒ 合计也是 {@code null}（部分求和会低估却看似精确）。
     *
     * @param claudeMdTokens       逐文件计数求和（:351-357）；<b>null = 算不出来</b>
     * @param claudeMdTokensSource 合计来源
     * @param memoryFiles          逐文件明细（:353-357）
     */
    public record MemoryTokenCounts(Integer claudeMdTokens, TokenSource claudeMdTokensSource,
                                    List<MemoryFileDetail> memoryFiles) {
    }

    /**
     * tools 段计数 · CC original: countBuiltInToolTokens + countMcpToolTokens
     * （analyzeContext.ts:363-515 / :616-730）· 对应分类输出 内置工具（不含技能）/MCP 工具
     * （CC 原名 'System tools'/'MCP tools'，analyzeContext.ts:1021-1029 / :1033-1039）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] {@code builtInToolTokens} 对齐 CC 响应字段
     * （analyzeContext.ts:501-514）＝ built-in 工具 schema <b>全量</b>计数，不扣
     * skillFrontmatterTokens；skill 扣减（{@code systemToolsTokens = builtInToolTokens -
     * skillFrontmatterTokens}，analyzeContext.ts:1021）由 {@link #buildCategories} 在
     * categories 中承载（CC :1022-1029）。
     *
     * <p>[四态标注] 每段各自带 {@code tokenSource}；{@code tokens=null} = 算不出来
     * （原实现 {@code Math.max(0, 0-500)} 会把它抹成 0）。
     *
     * @param builtInToolTokens      built-in（非 MCP）工具 schema 全量计数（:501-514，非 deferred 路径，不扣 skill）；
     *                               <b>null = 算不出来</b>
     * @param builtInToolTokensSource built-in 段数值来源
     * @param mcpToolTokens           MCP 工具 schema 计数（:722-725）；<b>null = 算不出来</b>
     * @param mcpToolTokensSource     MCP 段数值来源
     */
    public record ToolTokenCounts(Integer builtInToolTokens, TokenSource builtInToolTokensSource,
                                  Integer mcpToolTokens, TokenSource mcpToolTokensSource) {
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
     * <p>[四态标注] 数值正常时 skill 段<b>恒为本地粗估</b>（{@link SkillsLoader#estimateSkillFrontmatterTokens}
     * 的 {@code round(len/4)}，与 provider 无关，即 (a3) 态）⇒ 来源固定
     * {@link TokenSource#ESTIMATE}，但仍在 wire 上显式标出，避免前端硬编码「技能恒估算」而日后静默失效。
     * 技能<b>加载失败</b>时改为 {@code skillFrontmatterTokens=null} + {@link TokenSource#UNAVAILABLE}
     * （见 {@link #countSkillTokens()}，有意偏离 CC 的「失败即 0」）。
     *
     * @param totalSkills                 技能总数（CC original: totalSkills）；
     *                                    <b>null = 不知道</b>（加载失败，见 {@link #countSkillTokens()}）——
     *                                    ⛔ 不得写 0：那会让面板出现「技能数 0 / 0」却同时显示「— 不可用」的自相矛盾
     * @param skillFrontmatterTokens      技能 frontmatter token 汇总（CC original: skillFrontmatterTokens，:994）；
     *                                    <b>null = 算不出来</b>
     * @param skillFrontmatterTokensSource 汇总来源（正常 {@link TokenSource#ESTIMATE}；加载失败 {@link TokenSource#UNAVAILABLE}）
     * @param skillFrontmatter            逐技能 frontmatter 明细（CC original: skillFrontmatter，:599-604）
     */
    public record SkillTokenCounts(Integer totalSkills, Integer skillFrontmatterTokens,
                                   TokenSource skillFrontmatterTokensSource,
                                   List<SkillFrontmatterDetail> skillFrontmatter) {
    }

    /**
     * 展示分类条目 · CC original: {@code ContextCategory}（analyzeContext.ts:111-115
     * {@code {name, tokens, color, isDeferred?}}）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] web 端点仅产出 CC 可计算的非 deferred 类别：
     * 系统提示词 / 内置工具（不含技能）/ MCP 工具 / 记忆文件 / 技能（CC 原名 'System prompt' /
     * 'System tools' / 'MCP tools' / 'Memory files' / 'Skills'，本地化 + 扣减行改名见
     * {@link #buildCategories}）；Custom agents / Messages / deferred 类别
     * （无 agentDefinitions / messages 原料）不产出。
     *
     * <p>[四态标注] {@code tokens=null} = 该类别算不出来（面板显示「—」+「不可用」角标）；
     * 产出策略见 {@link #addCategory}（真 0 仍省略，不可用仍产出）。
     *
     * @param name             类别名（<b>中文</b>，本地化偏离 CC；CC original: name，
     *                         'System prompt' / 'System tools' / 'MCP tools' / 'Memory files' / 'Skills'）
     * @param tokens           该类别 token（CC original: tokens）；<b>null = 算不出来</b>
     * @param color            主题色键（CC original: color: keyof Theme，'promptBorder' / 'inactive' / 'cyan_FOR_SUBAGENTS_ONLY' / 'claude' / 'warning'）
     * @param tokenSource      数值来源
     * @param unavailableCause 该行<b>不可用的成因</b>（见 {@link UnavailableCause}，面板据此选提示文案）。
     *                         仅 {@code tokens == null} 时有值；数值正常的行恒为 {@code null}，wire 上省略
     */
    public record ContextCategory(String name, Integer tokens, String color, TokenSource tokenSource,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) UnavailableCause unavailableCause) {
    }

    /**
     * 分类行「不可用」（{@code tokens == null}）的<b>成因</b> · wire 上为小写字符串。
     *
     * <p><b>为什么需要它</b>：{@link TokenSource#UNAVAILABLE} 只说明「算不出来」，但面板上同一个
     * 「—」的成因分<b>两侧</b> —— 提供商侧（服务端计数接口缺失 / 调用失败）与<b>本地</b>
     * （技能清单读不到）。悬停提示必须跟着成因走，否则会把用户指向结构上不可能的原因
     * （用户裁定：技能加载失败时，分类节的「技能」行与「内置工具（不含技能）」行都不得再弹
     * 归因提供商的文案）。
     *
     * <p>⛔ 前端<b>不得</b>按 {@link ContextCategory#name()}（显示名，已本地化、日后可能再改）
     * 反推成因 —— 成因由后端下发，与显示名解耦（前端的同类禁令见 types.ts）。
     */
    public enum UnavailableCause {

        /**
         * 提供商侧：服务端计数接口缺失 / 调用失败。
         *
         * <p>⚠️ <b>只对实际走服务端计数的行成立</b> —— 即产生该行数值的客户端
         * {@link CountTokensClient#sourceKind()} == {@link TokenSource#API}
         * （{@link AnthropicCountTokensClient}）。非 anthropic 提供商装配的是本地估算客户端
         * （sourceKind == ESTIMATE），同一批行（系统提示词 / MCP 工具 / 记忆文件）的 null
         * <b>与提供商无关</b>，成因是 {@link #LOCAL_ESTIMATE}；判据见 {@link #buildCategories}
         * （与「500 扣减」同源判据）。⛔ 不得无条件写死本值。
         */
        PROVIDER("provider"),

        /**
         * 本地：<b>技能清单读不到</b>（{@link #countSkillTokens()} 的 catch 分支）。
         *
         * <p>技能 frontmatter token 恒由本地算出（{@code round(len/4)}，全程无 HTTP）⇒ 技能行的
         * 「不可用」只可能来自本地，与提供商无关。
         *
         * <p>⚠️ 与 {@link #LOCAL_ESTIMATE} 是<b>两件事</b>（本值 = 技能的清单/文件读不到；
         * 那个 = 本地估算器自己出错）⇒ 不共用一个值，也不共用一句面板文案（同屏两件事两种说法）。
         */
        LOCAL("local"),

        /**
         * 本地：<b>本地估算这一步出错</b>（{@link OpenAICountTokensClient} 的 tiktoken 估算抛错，
         * 见其 {@code countTokens}/{@code countTokensForTools} 的 catch → {@code null}）。
         *
         * <p>非 anthropic 提供商走的是本地估算路径、<b>全程无 HTTP</b> ⇒ 这些行
         * （系统提示词 / MCP 工具 / 记忆文件）的「不可用」成因在本地，与提供商无关。若仍弹
         * 「提供商不提供精确计数」，就是把用户指向一个结构上不可能的原因（用户裁定「一起修掉」）。
         *
         * <p>判据 = 产生该行数值的客户端 {@link CountTokensClient#sourceKind()} ==
         * {@link TokenSource#ESTIMATE}（与「500 扣减」同源判据，见 {@link #buildCategories}）。
         */
        LOCAL_ESTIMATE("localEstimate"),

        /**
         * 派生值行：本行是<b>扣减/合成</b>出来的（「内置工具（不含技能）」= 内置工具全量 − 技能
         * frontmatter，CC analyzeContext.ts:1021），其不可用由<b>参与运算的输入不齐</b>造成 ——
         * 成因可能在本地（技能清单读不到，见 {@link #LOCAL}）也可能在提供商侧（工具计数失败，
         * 见 {@link #PROVIDER}）。
         *
         * <p>⛔ 故本行<b>不下单侧结论</b>：面板提示不指向提供商。用户裁定「两行一起改」的场景
         * 正是它 —— 技能加载失败时同一个 {@code null} 会被写进「技能」行与「内置工具（不含技能）」行，
         * 若仍弹归因提供商的文案，同一面板同一个数字会出现两种互相矛盾的归因。
         *
         * <p>⚠️ 若要按成因细分本行（本地 / 提供商 / 两者），必须一并决定提供商侧那半怎么措辞 ——
         * 那会重新引入「本行归因提供商」，与上面这条裁定冲突，故本批次<b>有意不分</b>。
         */
        DERIVED("derived");

        private final String wire;

        UnavailableCause(String wire) {
            this.wire = wire;
        }

        /**
         * wire 表示（小写）· 前端「分类行成因」联合类型的唯一真源。
         *
         * @return {@code "provider"} / {@code "local"} / {@code "localEstimate"} / {@code "derived"}
         */
        @JsonValue
        public String wire() {
            return wire;
        }
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
     *                   「内置工具（不含技能）」承载 builtInToolTokens - skillFrontmatterTokens 扣减值，:1021-1029）
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
     *  {@link SkillRegistry#getModelInvocableCommands(String)}（对齐 CC commands.ts:563 getSkillToolCommands）
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
        // computedKind 一并下发：系统提示词 / MCP 工具 / 记忆文件三行的不可用成因按实际客户端分侧
        // （API ⇒ 提供商侧；ESTIMATE ⇒ 本地估算出错），见 buildCategories javadoc
        List<ContextCategory> categories = buildCategories(systemCounts, memoryCounts, toolCounts, skillCounts,
            countTokensClient.sourceKind());

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
     * （= {@link SkillRegistry#getModelInvocableCommands(String)}，对齐 CC commands.ts:563
     * getSkillToolCommands）——不再 {@code List.of()} 空注入。测试注入 {@link #skills} 假列表。
     *
     * <p><b>错误隔离（对齐 CC :605-613 的「不中断」，偏离其「失败即 0」）</b>：CC countSkillTokens
     * 整体 try/catch → {@code {skillTokens: 0, skillInfo: {totalSkills: 0, includedSkills: 0,
     * skillFrontmatter: []}}}（技能加载失败不中断整个 context analyze）；Java 同样整体包裹
     * （{@code getModelInvocableCommands} 的 isCommandEnabled 惰性求值 / cwd supplier 可能抛错，
     * SkillRegistry.getSlashCommandToolSkills:907 同款防御），但<b>失败不再塌成 0</b>：按四态口径记
     * {@code null + UNAVAILABLE}（见 catch 分支注释）。
     *
     * <p>⚠️ <b>覆盖面说明（实测）</b>：{@link SkillRegistry#loadAllCommands(String)} 对<b>每一个</b>
     * 技能源都做了 try/catch（bundled / 内置插件 / 文件系统 / workflow / plugin / dynamic / COMMANDS /
     * DB 主控，逐源独立 —— 对齐 CC commands.ts:360-373「每源 catch → 该源返回空」）⇒ <b>「技能目录读不出」
     * 这一类失败在 registry 内部就被吞成空列表了</b>，走不到本方法的 catch，会表现为「技能数偏少 / 为 0」。
     * 能走到 catch 的是<b>隔离层之外</b>的失败：cwd supplier（{@code SessionProjectRoot.getForSession}）、
     * {@code meetsAvailabilityRequirement} / {@code isCommandEnabled()} 逐命令新鲜求值抛错等。
     * 要让「目录读取失败」在面板上也如实标不可用，需改 {@link SkillRegistry} 的逐源隔离语义
     * （跨模块改动，超出本批次范围，已登记）。
     *
     * @return skill 段计数（totalSkills + skillFrontmatterTokens + 逐技能明细）
     */
    private SkillTokenCounts countSkillTokens() {
        try {
            List<Command> source = resolveSkillSource();
            if (source.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ContextAnalyzeService] countSkillTokens: 空 skill 原料（生产 registry 空 或 测试注入空）→ {0, 0, estimate, []}");
                }
                // 空列表 = 确实没有技能 ⇒ 真 0（与「加载失败」的不可用态区分；分类侧按 CC「仅正值展示」省略）
                return new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of());
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
            return new SkillTokenCounts(source.size(), skillFrontmatterTokens, TokenSource.ESTIMATE, details);
        } catch (Exception e) {
            // [四态标注 · 有意偏离 CC] CC analyzeContext.ts:605-613：技能加载失败 → 零值
            //   （{skillTokens:0, skillInfo:{totalSkills:0, includedSkills:0, skillFrontmatter:[]}}）。
            //   Java 保留「不中断整个 context analyze」，但**不保留「失败即 0」**：
            //   ① 0 兼任两义 ——「加载失败」与「确实没有技能」在面板上完全同形（技能段整节消失），
            //      用户无从知道技能清单这次没读到；② 上一轮做完四态后，技能行的 UNAVAILABLE 态
            //      在生产上结构不可达（本方法三条返回路径原先都非 null），四态只剩三态可达。
            //   现按四态口径：数值 null + 来源 UNAVAILABLE ⇒ 面板显示「— 不可用」，类别仍产出。
            //   totalSkills 一并置 null（⛔ 不写 0）：加载失败时「一共有几个技能」同样未知，
            //   写 0 会让面板出现「技能数 0 / 0」+「— 不可用」这种自相矛盾的组合（0 说「没有」，
            //   角标说「不知道」）—— 计数没有第三种取值，null 就表示「不知道」。
            // [已按用户裁定分开两种成因] 上面这条与「提供商不提供精确计数」是两回事（此处是本地加载
            //   失败）。用户已裁定：技能加载失败时，分类节的「技能」行与「内置工具（不含技能）」行
            //   （后者被同一个 null 传染，= 全量 − 技能）都不得再弹归因提供商的提示。落地分两层：
            //   ① 后端在 buildCategories 给行标成因 —— 技能行 UnavailableCause.LOCAL、
            //      「内置工具（不含技能）」行 UnavailableCause.DERIVED（合成值，不归因单侧）、
            //      系统提示词 / MCP 工具 / 记忆文件三行按客户端分侧（API ⇒ PROVIDER、
            //      ESTIMATE ⇒ LOCAL_ESTIMATE，见 buildCategories javadoc）；
            //   ② 前端 ContextAnalyzeModal 按成因选文案（local ⇒ 本地那句，provider ⇒ 提供商那句，
            //      derived ⇒ 不归因），⛔ 不按类别名反推成因。
            log.warn("[ContextAnalyzeService] countSkillTokens 加载失败 → 返回 {null, null, unavailable, []}"
                + "（不中断 context analyze；CC analyzeContext.ts:605-613 只保留了「不中断」，零值改成不可用）: {}",
                e.toString());
            return new SkillTokenCounts(null, null, TokenSource.UNAVAILABLE, List.of());
        }
    }

    /**
     * [FIX-B2 拍板#4] skill 段计数原料解析 · CC original: {@code getLimitedSkillToolCommands(getCwd())}
     * （analyzeContext.ts:567 → prompt.ts:213-215 → {@code getSkillToolCommands(cwd)} commands.ts:563）。
     *
     * <p>生产（skillRegistry != null）→ {@link SkillToolPrompt#getLimitedSkillToolCommands}
     * （= {@link SkillRegistry#getModelInvocableCommands(String)}，模型可调用命令清单，与 skill listing
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
     * <p>生产（claudemdEngine != null）→ {@link ClaudemdEngine#getMemoryFiles(boolean, String)} +
     * {@link ClaudemdEngine#filterInjectedMemoryFiles}（claudemd.ts:790-1075 + :1142-1151，
     * 逐文件投影为 {@link MemoryFileEntry}（path / type.ccName() / content）；测试/POJO（null）→
     * 回退构造注入 {@link #memoryFiles} 列表。每 analyze 调用解析一次（CC countMemoryFileTokens
     * 内部调用点，memoize 语义由 ClaudemdEngine 承载）。
     *
     * @return 真实/注入 memory 文件列表（CC getMemoryFiles + filterInjectedMemoryFiles 产物）
     */
    private List<MemoryFileEntry> resolveMemoryFiles() {
        if (claudemdEngine != null) {
            // [批 3c] 本方法无会话入参 → (b) 类合法跳过：显式传 null（回落进程 user.dir，与旧实现「MDC 为空」等价）；
            //   仅影响 InstructionsLoaded hook 载荷 session_id，memory 段计数本身与会话无关。
            if (log.isWarnEnabled()) {
                log.warn("[ContextAnalyzeService] resolveMemoryFiles 无会话入参 → 记忆文件解析按进程默认（user.dir），"
                    + "InstructionsLoaded hook 载荷 session_id 为 null（批 3c：会话态显式化，不再回落 MDC）");
            }
            List<MemoryFileInfo> files = claudemdEngine.filterInjectedMemoryFiles(claudemdEngine.getMemoryFiles(false, null));
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
     * （:340-349）求和（:351-357）。空原料 → {@code {0, [], 客户端来源}} 短路（:333-338）。
     *
     * <p>[四态标注 · collapse 点 2/3] 原 {@code int tokens = raw == null ? 0 : raw;}（CC :347
     * {@code tokens||0}）把「算不出来」抹成 0；现保留 null + 标 {@link TokenSource#UNAVAILABLE}，
     * 且任一文件不可用 ⇒ {@code claudeMdTokens=null}。
     *
     * @param source 已解析 memory 文件列表（{@link #resolveMemoryFiles()} 产物）
     * @return memory 段计数（claudeMdTokens + claudeMdTokensSource + memoryFileDetails）
     */
    private MemoryTokenCounts countMemoryFileTokens(List<MemoryFileEntry> source) {
        if (source.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] countMemoryFileTokens: 空 memory 原料 → {0, [], {}}",
                    countTokensClient.sourceKind().wire());
            }
            // 结构上确切的 0（没有文件可数）→ 来源随客户端，真 0 不标角标
            return new MemoryTokenCounts(0, countTokensClient.sourceKind(), List.of());
        }
        List<MemoryFileDetail> details = new ArrayList<>(source.size());
        int claudeMdTokens = 0;
        boolean anyUnavailable = false;
        for (MemoryFileEntry file : source) {
            Integer raw = countTokensClient.countTokens(file.content());
            if (raw == null) {
                anyUnavailable = true;
                details.add(new MemoryFileDetail(file.path(), file.type(), null, TokenSource.UNAVAILABLE));
                continue;
            }
            claudeMdTokens += raw;
            details.add(new MemoryFileDetail(file.path(), file.type(), raw, countTokensClient.sourceKind()));
        }
        Integer total = anyUnavailable ? null : claudeMdTokens;
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] countMemoryFileTokens: {} 个 memory 文件，claudeMdTokens={}（含不可用文件={}）",
                details.size(), total, anyUnavailable);
        }
        return new MemoryTokenCounts(total, TokenSource.of(total, countTokensClient.sourceKind()), details);
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
        Integer builtInTokens = countToolDefinitionTokens(builtIn);
        Integer mcpTokens = countToolDefinitionTokens(mcp);
        return new ToolTokenCounts(
            builtInTokens, TokenSource.of(builtInTokens, countTokensClient.sourceKind()),
            mcpTokens, TokenSource.of(mcpTokens, countTokensClient.sourceKind()));
    }

    /**
     * 展示分类构建 · CC original: analyzeContextUsage 的 categories 段（analyzeContext.ts:1007-1087）。
     *
     * <p>[IMP-F2-2 · OPD-CM5-F-17 改不扣] 分类承载 builtIn/skill 扣减：'内置工具（不含技能）' =
     * {@code Math.max(0, builtInToolTokens - skillFrontmatterTokens)}（CC :1021-1029），
     * '技能' = skillFrontmatterTokens（CC :1082-1087，单独展示避免与 SlashCommandTool schema
     * 双重计数）。web 端点无 agentDefinitions / messages 原料 → Custom agents / Messages /
     * deferred 类别（CC :1042-1060/:1076-1081）不产出。
     *
     * <p><b>[UI 本地化 · 有意偏离 CC（用户裁定）]</b> 类别名由 CC 的英文串
     * （'System prompt' :1013 / 'System tools' :1026 / 'MCP tools' :1036 / 'Memory files' :1075 /
     * 'Skills' :1084）改为<b>中文</b>——理由：同一屏的其余文案（分节标题、行名）已是中文，只有分类行
     * 还是英文（同一物两种写法）。⛔ 这是<b>有意的本地化偏离，不是漏抄 CC</b>，后人不要「改回英文对齐 CC」。
     * 译名沿用「上下文分析」面板既有用词（ContextAnalyzeModal.tsx：「系统提示词」「内置工具」
     * 「MCP 工具」「记忆文件」「技能」），⛔ 不新造一套。color 键保持 CC 原值（主题色键，非文案）。
     *
     * <p><b>[扣减行改名 · 用户裁定]</b> 「内置工具」一词在面板出现两处，<b>数值口径不同</b>：
     * 工具节的「内置工具」= {@code builtInToolTokens}（<b>全量</b>），分类行 = 全量 − 技能 frontmatter
     * （CC :1021 扣减）。技能未知时分类行显示「—」而工具节显示真数 ⇒ 用户看到<b>同名两行一真一假</b>。
     * 用户裁定<b>只改分类行那行的名字</b>（工具节保持「内置工具」），故分类行叫
     * <b>「内置工具（不含技能）」</b>——仍在「内置工具」一族内，只点明它扣掉了技能那部分。
     * ⛔ 扣减语义<b>不变</b>：若为了让两行数值一致而让分类行改用全量值，技能那部分 token 会同时算进
     * 「内置工具（不含技能）」与「技能」两行 = 重复计数，比同名更糟。
     *
     * <p><b>[四态标注 · 产出策略重定]</b> 原策略是「{@code tokens &gt; 0} 才产出」——在「算不出来」
     * 那一态下整条类别会被省略，前端<b>没有地方</b>渲染「—」。现策略：
     * <ul>
     *   <li><b>算不出来（{@code tokens == null}）⇒ 仍然产出</b>（带 {@link TokenSource#UNAVAILABLE}），
     *       面板显示「— 不可用」；</li>
     *   <li><b>真 0（可计算且 {@code &lt;= 0}）⇒ 仍然省略</b> —— 这表示「本来就没有这类内容」，
     *       强行造出一条 0 只会给面板添噪声，也可能被误读成「有这一段但没有 token」。</li>
     * </ul>
     * 两者的区分判据就是 {@code tokens 是否为 null}：null = 不知道（要出现），0 = 确切的没有（不出现）。
     * ⛔ 绝不能反过来用 0 兼任两义（那正是本批次要修的根）。
     *
     * <p><b>[成因按行取对客户端 · 用户裁定「一起修掉」]</b> 系统提示词 / MCP 工具 / 记忆文件三行的
     * 不可用成因<b>不写死</b>，而是按<b>实际产生该行数值的客户端</b>分侧（{@code computedKind} =
     * {@link CountTokensClient#sourceKind()}，判据与「500 扣减」同源）：
     * <ul>
     *   <li>{@link TokenSource#API}（{@link AnthropicCountTokensClient}）⇒
     *       {@link UnavailableCause#PROVIDER} —— 三行的 null 来自服务端计数接口缺失 / 调用失败；</li>
     *   <li>{@link TokenSource#ESTIMATE}（{@link OpenAICountTokensClient}，非 anthropic 提供商走的
     *       就是这条）⇒ {@link UnavailableCause#LOCAL_ESTIMATE} —— 三行的 null 来自<b>本地 tiktoken
     *       估算出错</b>，与提供商毫无关系。若此处仍标 PROVIDER，面板会弹「当前模型提供商不提供精确
     *       计数」，把用户指向一个结构上不可能的原因（正是用户最初那条病灶换到了别的行）。</li>
     * </ul>
     * 三行同用一个客户端实例（system 段经 {@link SystemPromptTokenCounter#count} 复用本服务的
     * {@code countTokensClient}，非单独装配；memory / tools 段亦经本服务同一字段）⇒ 本方法取一次
     * {@code computedKind} 即可覆盖三行。<b>「技能」行不参与此分侧</b>：它的数字全程本地产出，
     * 恒 {@link UnavailableCause#LOCAL}；扣减行恒 {@link UnavailableCause#DERIVED}（合成值）。
     *
     * @param system       system 段（systemPromptTokens）
     * @param memory       memory 段（claudeMdTokens）
     * @param tools        tools 段（builtInToolTokens 全量 + mcpToolTokens）
     * @param skill        skill 段（skillFrontmatterTokens）
     * @param computedKind 产生 system/memory/tools 三行数值的客户端来源类别
     *                     （{@link CountTokensClient#sourceKind()}），决定这三行不可用时的成因
     * @return 分类列表（含「正 token」与「算不出来」两类，顺序对齐 CC :1010-1087）
     */
    private static List<ContextCategory> buildCategories(SystemTokenCounts system, MemoryTokenCounts memory,
                                                        ToolTokenCounts tools, SkillTokenCounts skill,
                                                        TokenSource computedKind) {
        List<ContextCategory> cats = new ArrayList<>(5);
        // 系统提示词 / MCP 工具 / 记忆文件三行的不可用成因：按产生该行数值的客户端分侧
        //   （API ⇒ 提供商侧计数接口；ESTIMATE ⇒ 本地估算出错）。⛔ 不写死 PROVIDER —— 非 anthropic
        //   提供商装配的是本地估算客户端，这三行的 null 与提供商无关（用户裁定「一起修掉」）。
        UnavailableCause countedRowCause = computedKind == TokenSource.API
            ? UnavailableCause.PROVIDER
            : UnavailableCause.LOCAL_ESTIMATE;
        // 系统提示词 恒首（CC 'System prompt' :1010-1017，fixed overhead）
        //   成因按上述判据（逐 section 计数走同一个 countTokensClient，见 SystemPromptTokenCounter）
        addCategory(cats, "系统提示词", system.systemPromptTokens(), system.systemPromptTokensSource(), "promptBorder",
            countedRowCause);
        // 内置工具（builtInToolTokens - skillFrontmatterTokens，扣减值，CC 'System tools' :1021-1029）
        //   任一项不可用 ⇒ 该类别也不可用（仍产出，前端才有地方标「—」）
        //   ⚠️ 行名与工具节的「内置工具」（全量）**不同名**（用户裁定，理由见方法 javadoc）：
        //      扣减行的数值口径与工具节那行不同，同名会让技能未知时出现「同名两行一真一假」。
        //   ⚠️ 成因记 DERIVED（不是 PROVIDER）：本行是合成值，其不可用可能来自本地（技能清单读不到）
        //      也可能来自提供商侧（工具计数失败）——面板提示不归因单侧（见 UnavailableCause#DERIVED）。
        Integer systemToolsTokens = (tools.builtInToolTokens() == null || skill.skillFrontmatterTokens() == null)
            ? null
            : Math.max(0, tools.builtInToolTokens() - skill.skillFrontmatterTokens());
        addCategory(cats, "内置工具（不含技能）", systemToolsTokens,
            systemToolsTokens == null
                ? TokenSource.UNAVAILABLE
                : TokenSource.weaker(tools.builtInToolTokensSource(), skill.skillFrontmatterTokensSource()),
            "inactive", UnavailableCause.DERIVED);
        // MCP 工具（CC 'MCP tools' :1033-1039）· 成因按客户端分侧（countedRowCause）
        addCategory(cats, "MCP 工具", tools.mcpToolTokens(), tools.mcpToolTokensSource(), "cyan_FOR_SUBAGENTS_ONLY",
            countedRowCause);
        // 记忆文件（CC 'Memory files' :1072-1078）· 成因按客户端分侧（countedRowCause）
        addCategory(cats, "记忆文件", memory.claudeMdTokens(), memory.claudeMdTokensSource(), "claude",
            countedRowCause);
        // 技能（frontmatter token 汇总，单独展示避免双重计数，CC 'Skills' :1082-1087）
        //   成因恒在本地：frontmatter token 由本地 round(len/4) 粗估得来，全程无 HTTP（见 countSkillTokens）
        addCategory(cats, "技能", skill.skillFrontmatterTokens(), skill.skillFrontmatterTokensSource(), "warning",
            UnavailableCause.LOCAL);
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] buildCategories: 分类 {} 个，其中不可用 {} 行"
                    + "（技能清单读不到 {} 行、本地估算出错 {} 行、合成成因 {} 行、提供商侧 {} 行）"
                    + "——面板按成因选提示文案（UnavailableCause）",
                cats.size(),
                cats.stream().filter(c -> c.tokens() == null).count(),
                cats.stream().filter(c -> c.unavailableCause() == UnavailableCause.LOCAL).count(),
                cats.stream().filter(c -> c.unavailableCause() == UnavailableCause.LOCAL_ESTIMATE).count(),
                cats.stream().filter(c -> c.unavailableCause() == UnavailableCause.DERIVED).count(),
                cats.stream().filter(c -> c.unavailableCause() == UnavailableCause.PROVIDER).count());
        }
        return List.copyOf(cats);
    }

    /**
     * 追加一条分类 · [四态标注 · 产出策略重定] 省略条件 =「确切的 0」。
     *
     * <p>即：{@code tokens != null && tokens <= 0} 才跳过（真的没有这类内容）。
     * {@code tokens == null}（算不出来）与 {@code tokens > 0}（有内容）都产出 ——
     * 前者是本次新增的产出理由，面板需要它来渲染「— 不可用」。
     *
     * @param cats             分类累加器
     * @param name             类别名
     * @param tokens           该类别 token；null = 算不出来（仍产出）
     * @param source           数值来源
     * @param color            主题色键
     * @param unavailableCause 该行不可用（{@code tokens == null}）时的<b>成因</b>
     *                         （{@link UnavailableCause}，面板据此选提示文案）；数值正常的行记录为 null。
     *                         ⛔ 不可用的行必须给一个 —— 漏传时前端只能退化成不归因的兜底文案（会告警）
     */
    private static void addCategory(List<ContextCategory> cats, String name, Integer tokens,
                                    TokenSource source, String color, UnavailableCause unavailableCause) {
        if (tokens != null && tokens <= 0) {
            // 确切的 0：这一段本来就没有内容 → 省略（对齐 CC「仅正值展示」的可观测形态）
            return;
        }
        if (tokens == null && unavailableCause == null) {
            // fail-loud：不可用的行必须声明成因（面板按成因选提示文案）——
            // 这是调用方漏传，不是数据态；静默下去只会让前端退化成「不归因」文案（用户看不到真原因）
            log.warn("[ContextAnalyzeService] 分类行「{}」不可用（tokens=null）但未声明成因"
                + "（unavailableCause=null）→ 面板将退化为不归因文案，请补 UnavailableCause", name);
        }
        cats.add(new ContextCategory(name, tokens, color, source, tokens == null ? unavailableCause : null));
    }

    /**
     * CC original: TOOL_TOKEN_COUNT_OVERHEAD（analyzeContext.ts:68-75）· <b>API 侧</b>开销：
     * 请求里带 tools 时，服务端会给一次计数加约 500 token 的工具前缀（tool prompt preamble），
     * 每次调用含一次。故「单次 bulk 调用拿到的数字」要扣一次 —— 判据见
     * {@link #countToolDefinitionTokens(List)}。
     *
     * <p><b>主语限定（原注释此处失真，已按 2.1.284 发行产物实测更正）</b>：原文写
     * 「本地估算根本没有这次调用，数字里不含这 500」—— 这句<b>只对本仓 Java 客户端成立</b>，
     * 对 CC 的本地兜底<b>不成立</b>：
     * <ul>
     *   <li><b>本仓 Java 客户端（成立）</b>：{@link OpenAICountTokensClient} 用 tiktoken 直接数
     *       schema 文本，结构上就没有这次 API 调用，数字里确实不含这 500 ⇒ 不扣
     *       （见 {@link #countToolDefinitionTokens(List)} 里 {@code sourceKind != API} 的分支）。</li>
     *   <li><b>CC 的本地兜底（不成立）</b>：CC 的本地估算器<b>自己主动补上这 500</b>，语句为
     *       {@code K+=GYe(b(W),"json")+e$}（常量声明 {@code var e$=500}；
     *       {@code GYe(b(W),"json")} 是工具数组的 JSON 文本长度，外层 {@code if(W.length>0)}
     *       表示只在本次带工具时才加）。目的是让调用点那句<b>无条件</b>的
     *       {@code Math.max(0, raw - 500)}（CC {@code tZn} / {@code lZn}）在走兜底时依然成立 ——
     *       即 CC 的「本地兜底」与「API 计数」在扣减前是<b>同一个口径</b>，不是本仓这种
     *       「本地不扣」的分叉。⇒ 本仓日后若补本地兜底，必须连带决定这 500 补不补，
     *       ⛔ 不能把本条正文的「本地估算不含」照搬过去。</li>
     * </ul>
     * ⚠️ 版本锚点 = <b>CC 2.1.284</b>（本机 claude.exe，246,480,032 字节，内嵌
     * {@code // Version: 2.1.284}）；minify 名（{@code e$} / {@code GYe} / {@code tZn}）随构建漂，
     * 换版本后须按语义重核。
     *
     * <p>CC 扣减点（逐一核过）：
     * <ul>
     *   <li>:638-641 —— MCP 组单次 bulk 调用 {@code Math.max(0, totalTokensRaw - 500)}；</li>
     *   <li>:477-480 —— deferred built-in 工具<b>逐个</b>计数（:465-473 每个工具一次调用，各含一次开销），
     *       故每个 {@code Math.max(0, tokens - 500)}；</li>
     *   <li>:424-427 —— ant-only 明细分摊（{@code distributable = Math.max(0, alwaysLoadedTokens - 500)}），
     *       只影响展示分摊，不改类别值；</li>
     *   <li>always-loaded built-in 的 bulk 计数（:401-409）CC <b>未扣</b> —— 与 MCP 组同形却不同待遇，
     *       是 CC 自身的不一致（Java 端两组都扣，登记为偏离，见下方方法注释）。</li>
     * </ul>
     */
    static final int TOOL_TOKEN_COUNT_OVERHEAD = 500;

    /**
     * 工具定义 token 计数 · CC original: countToolDefinitionTokens（analyzeContext.ts:234-258）。
     *
     * <p>RES-C9 对齐 CC：tools schema 经 {@link CountTokensClient#countTokensForTools(List)}
     * 以 tools 数组随请求发送（analyzeContext.ts:250 {@code countTokensWithFallback([], toolSchemas)}），
     * 不再把 schema JSON 当 user 消息文本。
     *
     * <p><b>[500 扣减的判据 · 只扣服务端计数]</b> {@code TOOL_TOKEN_COUNT_OVERHEAD=500} 是
     * <b>API 侧</b>开销（analyzeContext.ts:68-75 原文：「The API adds a tool prompt preamble
     * (~500 tokens) once per API call when tools are present … We subtract this overhead from
     * per-tool counts」）⇒ <b>只有当这个数字确实来自那次服务端计数调用时才存在这笔开销</b>，
     * 判据 = {@link CountTokensClient#sourceKind()} == {@link TokenSource#API}
     * （{@link AnthropicCountTokensClient} 覆写为 API；{@link OpenAICountTokensClient} 是本地
     * tiktoken、不发请求，覆写为 ESTIMATE）。
     *
     * <p>⛔ 本地估算路径不得扣这 500：tiktoken 直接数 schema 文本，本来就不含 API 的前缀开销，
     * 扣了就是<b>系统性低估 500</b>，小工具集还会被 {@code Math.max(0, raw-500)} 夹成 <b>假 0</b>
     * （该类别被当成「确实没有这类工具」而省略 → 面板整行消失），正是本批次要消灭的「0 兼任两义」。
     *
     * <p><b>[有意偏离 CC]</b> CC 只对 MCP 组 bulk 与 deferred 单个工具扣，always-loaded built-in
     * bulk（:401-409）不扣；Java 端 built-in / MCP 两组 bulk 都扣（各扣一次）。理由：两者同为
     * 「一次 bulk 调用」，CC 对 MCP 组的注释（:637「Subtract the single overhead since we made one
     * bulk call」）同样适用于 built-in 组 —— 保留 Java 现有更自洽的算法，不随 CC 的不一致回退。
     *
     * <p>[四态标注 · collapse 点 3/3] 原 {@code int rawCount = raw == null ? 0 : raw;} 紧接
     * {@code Math.max(0, rawCount - 500)} —— 算不出来时被抹成 0 后又被 overhead 地板夹成 0，
     * 与「这类工具真是 0 token」字节相同。现 null 原样返回为不可用，不参与扣减。
     *
     * @param defs 分类后的工具定义（空 → 0，结构上确切的「没有这类工具」）
     * @return 该类工具 schema 总 token；<b>null = 算不出来</b>（:251-257 的 API 失败）
     */
    private Integer countToolDefinitionTokens(List<ToolDefinition> defs) {
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
        if (raw == null) {
            // [四态·3/3] 算不出来：不扣 overhead、不夹 0，直接返回 null（前端显示「— 不可用」）
            if (log.isWarnEnabled()) {
                log.warn("[ContextAnalyzeService] countToolDefinitionTokens: {} 个工具计数不可用（客户端返回 null）"
                    + " → 返回 null（⛔ 不再落成 Math.max(0, 0-500)=0 的假 0）", defs.size());
            }
            return null;
        }
        if (countTokensClient.sourceKind() != TokenSource.API) {
            // 本地估算（如 tiktoken）：数字不含 API 的 tools 前缀开销 ⇒ 不扣、不夹 0，原样返回。
            if (log.isDebugEnabled()) {
                log.debug("[ContextAnalyzeService] countToolDefinitionTokens: {} 个工具，来源={} 非服务端计数"
                        + " → 不扣 TOOL_TOKEN_COUNT_OVERHEAD={}，raw={} 原样返回"
                        + "（CC analyzeContext.ts:68-75 的 500 是 API 侧工具前缀开销，本地估算不含）",
                    defs.size(), countTokensClient.sourceKind().wire(), TOOL_TOKEN_COUNT_OVERHEAD, raw);
            }
            return raw;
        }
        // TOOL_TOKEN_COUNT_OVERHEAD=500 补偿（服务端计数，单次 bulk 调用含一次开销；analyzeContext.ts:477-480/:638-641）
        int compensated = Math.max(0, raw - TOOL_TOKEN_COUNT_OVERHEAD);
        if (log.isDebugEnabled()) {
            log.debug("[ContextAnalyzeService] countToolDefinitionTokens: {} 个工具，raw={}, "
                + "overhead={}, compensated={}（CC TOOL_TOKEN_COUNT_OVERHEAD=500，analyzeContext.ts:68-75）",
                defs.size(), raw, TOOL_TOKEN_COUNT_OVERHEAD, compensated);
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

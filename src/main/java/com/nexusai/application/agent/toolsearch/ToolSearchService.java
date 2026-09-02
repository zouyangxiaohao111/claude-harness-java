package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.infra.llm.CountTokensClient;
import com.nexusai.infra.llm.CountTokensClient.ToolSchema;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ToolSearch 共享门控服务 · 镜像 CC {@code Open-ClaudeCode/src/utils/toolSearch.ts}
 * 模块函数：
 * <ul>
 *   <li>{@link #getToolSearchMode()} — CC {@code getToolSearchMode}（toolSearch.ts:172-198）</li>
 *   <li>{@link #isToolSearchEnabledOptimistic()} — CC {@code isToolSearchEnabledOptimistic}
 *       （toolSearch.ts:270-320）</li>
 *   <li>{@link #isToolSearchToolAvailable(List)} — CC {@code isToolSearchToolAvailable}
 *       （toolSearch.ts:330-334）</li>
 *   <li>{@link #isDeferredTool(Tool, JsonNode)} — CC {@code isDeferredTool}
 *       （tools/ToolSearchTool/prompt.ts:62-108）</li>
 *   <li>{@link #isToolSearchEnabled(List, String)} / {@link #isToolSearchEnabled(List, String, CountTokensClient)}
 *       — CC {@code isToolSearchEnabled} definitive 门控（toolSearch.ts:385-473，claude.ts:1120 主循环入口；
 *       3 参重载 token 优先，2 参 = 纯 char fallback）</li>
 *   <li>{@link #computeDeferredToolNames(List)} — CC claude.ts:1128-1134 预计算</li>
 *   <li>{@link #filterToolsForSchema(List, boolean, Set, Set)} — CC claude.ts:1154-1172
 *       filteredTools 语义</li>
 *   <li>{@link #extractDiscoveredToolNames(List)} — 复用 {@link SchemaNotSentHint} H2 真扫描
 *       （toolSearch.ts:545-592）</li>
 *   <li>{@link #isDeferredToolsDeltaEnabled()} — CC toolSearch.ts:629-633 delta 门控</li>
 * </ul>
 *
 * <p><b>单一常量源</b>: 复用 {@link ToolNameConstants#TOOL_SEARCH_TOOL_NAME}
 * （不重复定义，消除 SchemaNotSentHint 旧私有常量双轨）。
 *
 * <p><b>@Service bean + 静态门面（IMP-C6 bean 化）</b>: 镜像 CC 模块级函数（{@code export
 * function ...}），循 SchemaNotSentHint 既有静态约定；ToolSearchTool / SchemaNotSentHint /
 * LlmAgentLoop / PostCompactAttachmentRestorer 静态调用保持兼容。CC 模块内
 * {@code countToolDefinitionTokens}（analyzeContext.ts:234-258）直接可用，Java 端对应
 * {@link CountTokensClient} 是 Spring bean —— 故本类改为 {@code @Service} 单例，经
 * {@code @Autowired(required=false)} 注入 {@link #tokenClient}（单一 {@code countTokensClient}
 * bean，ToolRegistrationConfig），{@code @PostConstruct} 写入 {@link #INSTANCE}，静态门面
 * 内部取 {@link #resolveTokenClient(CountTokensClient)}：显式参数优先，兜底注入实例。
 * 主循环 3 参注入见 {@link #isToolSearchEnabled(List, String, CountTokensClient)}。
 *
 * <p><b>测试 seam</b>: {@link #envOverride} 镜像 CC 测试对 {@code process.env} 的直接
 * 赋值（CC toolSearch.test.ts 设置 ENABLE_TOOL_SEARCH 后断言 gate 行为）。Java 端
 * {@code System.getenv()} 不可写，测试通过本字段注入 env 快照；{@code null} → 读
 * {@code System.getenv()}（生产路径）。
 *
 * <p><b>已知限制（fail loud）</b>: CC {@code isToolSearchEnabledOptimistic} 的 provider
 * 子检查（toolSearch.ts:299-311，{@code getAPIProvider()==='firstParty'} &&
 * {@code !isFirstPartyAnthropicBaseUrl()} → false）Java 无 API provider 抽象 → N/A，
 * 不镜像，仅镜像同名 env 门控。CC {@code isDeferredTool} 的 FORK_SUBAGENT Agent
 * （prompt.ts:76-81）、KAIROS Brief（:88-94）、KAIROS SendUserFile + REPL bridge
 * （:98-105）三个 feature 分支 Java 无对应通道 → N/A，结构保留、恒走默认 shouldDefer。
 */
@Service
public final class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);

    /**
     * Spring 单例门面持有者 · IMP-C6 bean 化：{@code @Service} 单例在容器启动时实例化，
     * {@code @PostConstruct} 写回本静态引用，静态门面方法经
     * {@link #resolveTokenClient(CountTokensClient)} 取注入的 {@link #tokenClient}
     * （CC 模块内 countToolDefinitionTokens 直接可用的对等物）。
     * 非 Spring 单测场景（无容器）为 null → token 注入不可用，静态方法退回既有行为。
     */
    private static volatile ToolSearchService INSTANCE;

    /**
     * [activate-on-search→mode=activate] 已激活的 defer 工具名（ToolSearch 确认后加入，全局跨会话）·
     * 2026-09-01 扩展（对齐 CC 的 tool_reference→discovered 激活语义，但用 ToolSearch 调用侧激活，
     * 不依赖模型 tool_reference 能力——deepseek 等 openai_compatible 模型无此消息类型）。
     * 激活后 filterToolsForSchema 视为 discovered 保留（工具进 API tools 模型可调用）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> ACTIVATED_TOOLS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * [openai-lazy 三态互斥] openai_compatible（deepseek）模型工具策略 · 用户拍板 2026-09-01：
     * <ul>
     *   <li>{@link #SEARCH}（默认）——懒加载：defer 工具不进 API tools，模型经 ToolSearch 搜索拿
     *       完整 schema 当场调用（不激活）</li>
     *   <li>{@link #ACTIVATE}——懒加载 + 激活：ToolSearch 确认的工具写入 {@link #ACTIVATED_TOOLS}，
     *       下一轮进 API tools 直接可调（搜索 + 激活提示）</li>
     *   <li>{@link #FULL}——全发：所有工具（含 defer）全量进 schema，模型直接调用，排除 ToolSearch
     *       （无搜索环节，对齐上轮「完整 schema 模式」语义）</li>
     * </ul>
     */
    public enum ToolSearchOpenAiMode {
        SEARCH, ACTIVATE, FULL
    }

    /**
     * yml {@code nexusai.toolsearch.mode} 三态互斥（search | activate | full，默认 search）·
     * 替代废弃的 {@code activate-on-search} 布尔（mode=activate 即旧开关开；mode=search 即关；
     * mode=full 新增全发）。非法值 → 回落 SEARCH。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.toolsearch.mode:search}")
    private volatile String mode;

    /** 测试 seam · 生产读 {@link #mode}；测试 override 优先（null → 生产值）。 */
    public static volatile String modeOverride;

    /** 纯解析 · 非法值/未配 → 回落 SEARCH（默认懒加载，最安全）。 */
    static ToolSearchOpenAiMode parseMode(String m) {
        if (m != null) {
            switch (m.toLowerCase(java.util.Locale.ROOT).trim()) {
                case "activate":
                    return ToolSearchOpenAiMode.ACTIVATE;
                case "full":
                    return ToolSearchOpenAiMode.FULL;
                default:
                    return ToolSearchOpenAiMode.SEARCH;
            }
        }
        return ToolSearchOpenAiMode.SEARCH;
    }

    /** 解析当前 openai 工具模式 · override 优先（测试），否则 @Value mode。 */
    ToolSearchOpenAiMode modeEnum() {
        return parseMode(modeOverride != null ? modeOverride : this.mode);
    }

    /**
     * [openai-lazy] 是否全发模式（mode=full · 所有工具含 defer 直接进 schema，排除 ToolSearch）。
     * 测试 seam：modeOverride 非 null → 直接解析（不依赖 INSTANCE，非 Spring 测试可用）。
     */
    public static boolean isFullSchemaMode() {
        if (modeOverride != null) {
            return parseMode(modeOverride) == ToolSearchOpenAiMode.FULL;
        }
        ToolSearchService inst = INSTANCE;
        return inst != null && inst.modeEnum() == ToolSearchOpenAiMode.FULL;
    }

    /** [openai-lazy] 是否激活模式（mode=activate · ToolSearch 确认后激活进 API tools）。 */
    public static boolean isActivateMode() {
        if (modeOverride != null) {
            return parseMode(modeOverride) == ToolSearchOpenAiMode.ACTIVATE;
        }
        ToolSearchService inst = INSTANCE;
        return inst != null && inst.modeEnum() == ToolSearchOpenAiMode.ACTIVATE;
    }

    /**
     * [activate-on-search→mode=activate] 静态门面 · ToolSearchTool 确认 matches 后调用
     * （mode=activate 时激活）。
     *
     * @param names 匹配的 defer 工具名
     * @return 实际激活的工具名（非 activate 模式 / 空输入 → 空列表），供 ToolSearch 返回激活提示
     */
    public static java.util.List<String> activateTools(java.util.List<String> names) {
        ToolSearchService inst = INSTANCE;
        if (inst == null) {
            return java.util.List.of(); // 非 Spring 单测（无 INSTANCE）→ 跳过激活（无副作用）
        }
        return inst.doActivateTools(names);
    }

    private java.util.List<String> doActivateTools(java.util.List<String> names) {
        if (modeEnum() != ToolSearchOpenAiMode.ACTIVATE || names == null || names.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> activated = new java.util.ArrayList<>(names.size());
        for (String name : names) {
            if (name == null || ACTIVATED_TOOLS.containsKey(name)) {
                continue; // 已激活 / null 跳过
            }
            ACTIVATED_TOOLS.put(name, Boolean.TRUE);
            activated.add(name);
        }
        if (!activated.isEmpty() && log.isInfoEnabled()) {
            log.info("[ToolSearch] mode=activate: 激活 {} 个 defer 工具（下轮进 API tools）: {}",
                activated.size(), String.join(", ", activated));
        }
        return java.util.List.copyOf(activated);
    }

    /** [activate-on-search→mode=activate] 工具是否已被 ToolSearch 激活。 */
    public static boolean isActivated(String name) {
        return name != null && ACTIVATED_TOOLS.containsKey(name);
    }

    /**
     * count_tokens 客户端（Spring 注入）· 对齐 CC 模块内 {@code countToolDefinitionTokens}
     * （analyzeContext.ts:234-258）直接可用的语义。单一 {@code countTokensClient} bean
     * （ToolRegistrationConfig），{@code @Autowired(required=false)} 容错无 bean 场景。
     */
    @Autowired(required = false)
    private CountTokensClient tokenClient;

    /**
     * models 表 mapper（Spring 注入，保留为<b>信息性日志</b>）· 原 W4-2 供 {@link #resolveContextWindow}
     * 按 modelName 查 {@code models.max_context_tokens} 的 DB 解析链已删除（G-10 收敛）：模型级窗口
     * 现由 {@link CompactThresholdSystem} 的 DB resolver（AgentLoopContextFactory
     * resolveModelContextWindow → ModelNameResolver）统一承担。{@code @Autowired(required=false)}
     * 容错无 bean 场景（非 Spring 单测）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    /**
     * providers 表 mapper（Spring 注入，保留为<b>信息性日志</b>）· 原 G-15 全名优先消歧解析链已删除
     * （G-10 收敛）：providers 联合查现由 {@link CompactThresholdSystem} 的 DB resolver
     * （AgentLoopContextFactory resolveModelContextWindow 传 providerMapper 走全名优先路径，
     * 对齐 CC modelCapabilities.ts:75-83 exact-id/子串匹配消歧语义）统一承担。
     * {@code @Autowired(required=false)} 容错无 bean 场景（非 Spring 单测）。
     */
    @Autowired(required = false)
    private ProviderMapper providerMapper;

    /**
     * 阈值体系（G-10 收敛）· 全仓窗口解析<b>唯一入口</b>
     * {@link CompactThresholdSystem#getContextWindowForModel}。Spring 注入共享单例
     * （DB model 窗口解析器由 {@code AgentLoopContextFactory.wireThresholdSystemResolver} 启动注入，
     * 含 G-15 全名优先消歧）；{@code @Autowired(required=false)} 容错无 bean 场景（非 Spring 单测）→
     * {@link #resolveContextWindow} 走 {@link CompactThresholdSystem#resolveWindowFallback} 同源静态兜底。
     */
    @Autowired(required = false)
    private CompactThresholdSystem compactThresholdSystem;

    /**
     * 测试 seam · 镜像 CC 测试对 {@code process.env} 的直接赋值（同 SchemaNotSentHint
     * 旧实现语义迁移）。{@code null} → 读 {@code System.getenv()}（生产路径）。
     * public：跨包测试（agent 包 LlmAgentLoopDeferLoadingPipelineTest）需确定性控制
     * ENABLE_TOOL_SEARCH 模式；包内 H3 测试照旧。
     */
    public static volatile Map<String, String> envOverride;

    /** CC isToolSearchEnabledOptimistic 模块级一次性日志标志（toolSearch.ts:269）。 */
    private static volatile boolean loggedOptimistic = false;

    /** CC toolSearch.ts:204 DEFAULT_UNSUPPORTED_MODEL_PATTERNS（负向模式：小写 contains 命中即不支持）·
     *  Java 扩展：+deepseek —— openai_compatible provider 无 tool_reference 块语义（CC 的
     *  defer_loading 是 Anthropic API 专属），若误判「支持 tool_reference」→ useToolSearch=true →
     *  defer 工具被 filterToolsForSchema 从 API tools 过滤（模型调不到）+ ToolSearch 输出仅 matches
     *  不激活工具（discovered 需模型实际调用才有）→ 死锁（联调实测 deepseek 反复 ToolSearch
     *  vision_analyze 称「无 schema 无法调用」）。判不支持 → useToolSearch=false → 全部工具直接
     *  发送完整 schema，模型视作普通工具直接调用（无搜索环节）。 */
    private static final Set<String> DEFAULT_UNSUPPORTED_MODEL_PATTERNS = Set.of("haiku", "deepseek");

    /** CC toolSearch.ts:49 DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE = 10（10% 上下文窗口）. */
    private static final int DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE = 10;

    /** CC toolSearch.ts:99 CHARS_PER_TOKEN = 2.5（MCP 工具定义字符/token 近似，char fallback 用）. */
    private static final double CHARS_PER_TOKEN = 2.5;

    /** CC analyzeContext.ts:75 TOOL_TOKEN_COUNT_OVERHEAD = 500（API 工具前缀开销，每组 bulk 调用扣一次）. */
    private static final int TOOL_TOKEN_COUNT_OVERHEAD = 500;

    /** 包私有构造（@Service 单例实例化用；外部禁止 new）。 */
    ToolSearchService() {
        // @Service 单例由容器实例化；静态门面方法不依赖实例
    }

    /** Spring 启动时写入单例门面持有者（IMP-C6 bean 化）。 */
    @PostConstruct
    void init() {
        INSTANCE = this;
        if (log.isInfoEnabled()) {
            log.info("[ToolSearchService] @Service 单例就绪：CountTokensClient 注入={}（CC toolSearch.ts 模块内 countToolDefinitionTokens 对等物，tst-auto token 优先路径可用），"
                    + "CompactThresholdSystem 注入={}（G-10 收敛：resolveContextWindow 委托 getContextWindowForModel 同源），"
                    + "ModelMapper 注入={}（信息性：DB 窗口解析已委托 CompactThresholdSystem resolver），"
                    + "ProviderMapper 注入={}（信息性：G-15 消歧已在 CompactThresholdSystem resolver 内）",
                tokenClient != null, compactThresholdSystem != null, modelMapper != null, providerMapper != null);
        }
    }

    /**
     * 读取当前 env 快照 · 测试经 {@link #envOverride} 注入，生产读 {@code System.getenv()}。
     *
     * @return env 快照（非 null）
     */
    private static Map<String, String> currentEnv() {
        return envOverride != null ? envOverride : System.getenv();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getToolSearchMode · CC toolSearch.ts:172-198
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 工具搜索模式 · 对齐 CC {@code getToolSearchMode()}（toolSearch.ts:172-198）。
     *
     * <p>返回 {@code 'tst'} | {@code 'tst-auto'} | {@code 'standard'}：
     * <ul>
     *   <li>{@code CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS} truthy → {@code 'standard'}
     *       （CC :181-183 beta kill switch，proxy 网关逃生阀）</li>
     *   <li>{@code ENABLE_TOOL_SEARCH='auto:0'} → {@code 'tst'}（恒启用，CC :189）</li>
     *   <li>{@code 'auto:100'} → {@code 'standard'}（恒禁用，CC :190）</li>
     *   <li>{@code 'auto'/'auto:1-99'} → {@code 'tst-auto'}（CC :191-193）</li>
     *   <li>truthy（{@code '1'/'true'/'yes'/'on'}）→ {@code 'tst'}（CC :195）</li>
     *   <li>defined falsy（{@code '0'/'false'/'no'/'off'}）→ {@code 'standard'}（CC :196）</li>
     *   <li>unset → {@code 'tst'}（默认启用，CC :197）</li>
     * </ul>
     *
     * @return 工具搜索模式
     */
    public static String getToolSearchMode() {
        return getToolSearchMode(currentEnv());
    }

    /**
     * env 注入版（测试用）· 语义同上，env 以参数传入便于覆盖分支。
     *
     * @param env 环境变量快照（null → 空 map）
     * @return 工具搜索模式
     */
    static String getToolSearchMode(Map<String, String> env) {
        Map<String, String> e = env == null ? Map.of() : env;
        // CC :181-183 CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS kill switch
        if (isEnvTruthy(e.get("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"))) {
            return "standard";
        }
        String value = e.get("ENABLE_TOOL_SEARCH");
        Integer autoPercent = parseAutoPercentage(value);
        if (autoPercent != null && autoPercent == 0) {
            return "tst";      // auto:0 = 永远启用（CC :189）
        }
        if (autoPercent != null && autoPercent == 100) {
            return "standard"; // auto:100 = 永远禁用（CC :190）
        }
        if (isAutoToolSearchMode(value)) {
            return "tst-auto"; // auto 或 auto:1-99（CC :191-193）
        }
        if (isEnvTruthy(value)) {
            return "tst";      // true/1/yes/on（CC :195）
        }
        if (isEnvDefinedFalsy(value)) {
            return "standard"; // false/0/no/off（CC :196）
        }
        return "tst";          // unset → 默认启用（CC :197）
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isToolSearchEnabledOptimistic · CC toolSearch.ts:270-320
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ToolSearch 乐观可用 · 对齐 CC {@code isToolSearchEnabledOptimistic()}
     * （toolSearch.ts:270-320）· 第 1 道门（SchemaNotSentHint gate1）。
     *
     * <p>仅在 mode='standard'（明确禁用）时返回 false；其余（tst / tst-auto）返回 true。
     *
     * <p>CC provider 子检查（:299-311，{@code !ENABLE_TOOL_SEARCH} && firstParty &&
     * !firstPartyBaseUrl → false）：Java 无 API provider 抽象 → N/A（登记，不镜像）。
     *
     * @return true = ToolSearch 乐观可用
     */
    public static boolean isToolSearchEnabledOptimistic() {
        return isToolSearchEnabledOptimistic(currentEnv());
    }

    /**
     * env 注入版（测试用）· 语义同上。
     *
     * @param env 环境变量快照（null → 空 map）
     * @return true = ToolSearch 乐观可用
     */
    static boolean isToolSearchEnabledOptimistic(Map<String, String> env) {
        Map<String, String> e = env == null ? Map.of() : env;
        String mode = getToolSearchMode(e);
        if ("standard".equals(mode)) {
            logOptimisticOnce("mode=" + mode + ", ENABLE_TOOL_SEARCH=" + e.get("ENABLE_TOOL_SEARCH")
                + ", result=false");
            return false;
        }
        // CC provider 子检查（toolSearch.ts:299-311）Java 无 API provider 抽象 → N/A（见类 javadoc）
        logOptimisticOnce("mode=" + mode + ", ENABLE_TOOL_SEARCH=" + e.get("ENABLE_TOOL_SEARCH")
            + ", result=true");
        return true;
    }

    /**
     * CC 模块级一次性日志（toolSearch.ts:270 loggedOptimistic）· debug 级 + 中文语境
     * 标注（CLAUDE.md 数据流日志规范）。
     */
    private static void logOptimisticOnce(String ccDetail) {
        if (loggedOptimistic) {
            return;
        }
        loggedOptimistic = true;
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearch:optimistic] " + ccDetail);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isToolSearchToolAvailable · CC toolSearch.ts:330-334
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ToolSearch 工具是否在工具列表 · 对齐 CC {@code isToolSearchToolAvailable}
     * （toolSearch.ts:330-334）· 第 2 道门（SchemaNotSentHint gate2）。
     *
     * <p>CC {@code tools.some(toolMatchesName(tool, TOOL_SEARCH_TOOL_NAME))}，其中
     * {@code toolMatchesName}（Tool.ts:348-353）语义为 {@code name === name ||
     * aliases?.includes(name)}。Java 端按 name + aliases 双路径匹配（Tool 被重命名时
     * 老名进 {@code aliases} 列表，Tool.java:634-636，对齐 CC Tool.ts:368-371）。
     *
     * @param tools 工具列表（CC {@code tools: readonly {name}[]}）
     * @return true = ToolSearch 在列表中（name 或 aliases 命中）
     */
    public static boolean isToolSearchToolAvailable(List<Tool> tools) {
        if (tools == null) {
            return false;
        }
        return tools.stream().anyMatch(t -> t != null && matchesToolSearchName(t));
    }

    /**
     * 工具名是否 ToolSearch · 对齐 CC {@code toolMatchesName}（Tool.ts:348-353）
     * {@code name === name || aliases?.includes(name)} 语义 · ToolSearch 相关门控共用单一实现。
     */
    private static boolean matchesToolSearchName(Tool t) {
        if (t == null) {
            return false;
        }
        if (ToolNameConstants.TOOL_SEARCH_TOOL_NAME.equals(t.name())) {
            return true;
        }
        return t.aliases() != null && t.aliases().contains(ToolNameConstants.TOOL_SEARCH_TOOL_NAME);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isDeferredTool · CC tools/ToolSearchTool/prompt.ts:62-108
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 是否 deferred 工具 · 对齐 CC {@code isDeferredTool}（prompt.ts:62-108）· 第 3 道门
     * （SchemaNotSentHint gate3）+ ToolSearchTool 检索过滤。
     *
     * <p>顺序移植 CC 7 规则：
     * <ol>
     *   <li>{@code tool.alwaysLoad()} → false（CC :65，优先级最高，MCP 工具可 opt-out）</li>
     *   <li>{@code tool.isMcp()} → true（CC :68，MCP 工具永远 defer）</li>
     *   <li>{@code tool.name() == TOOL_SEARCH_TOOL_NAME} → false（CC :71，模型需要它加载其他工具）</li>
     *   <li>FORK_SUBAGENT Agent（CC :76-81）→ N/A（Java 无该 feature 通道）</li>
     *   <li>KAIROS Brief（CC :88-94）→ N/A（Java 无该 feature 通道）</li>
     *   <li>KAIROS SendUserFile + REPL bridge（CC :98-105）→ N/A（Java 无该 feature 通道）</li>
     *   <li>默认 {@code tool.shouldDefer(input)}（CC :107）</li>
     * </ol>
     *
     * @param tool  待判定工具
     * @param input 本次调用输入（Java shouldDefer 入参；ToolSearchTool 检索场景传 null）
     * @return true = tool 是 deferred
     */
    public static boolean isDeferredTool(Tool tool, JsonNode input) {
        if (tool == null) {
            return false;
        }
        // CC prompt.ts:65 — Explicit opt-out via alwaysLoad，最先检查
        if (tool.alwaysLoad()) {
            return false;
        }
        // CC prompt.ts:68 — MCP 工具永远 defer（workflow-specific）
        if (tool.isMcp()) {
            return true;
        }
        // CC prompt.ts:71 — ToolSearch 自身永不 defer（模型需要它加载其他工具）
        if (ToolNameConstants.TOOL_SEARCH_TOOL_NAME.equals(tool.name())) {
            return false;
        }
        // CC prompt.ts:76-105 — FORK_SUBAGENT Agent / KAIROS Brief / KAIROS SendUserFile
        //   子规则：Java 无对应 feature → N/A（见类 javadoc 已知限制）
        // CC prompt.ts:107 — 默认规则
        return tool.shouldDefer(input);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isToolSearchEnabled (definitive 门控) · CC toolSearch.ts:385-473
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 模型是否支持 tool_reference · 对齐 CC {@code modelSupportsToolReference}
     * （toolSearch.ts:239-252）· 负向模式：模型默认支持，除非命中不支持列表
     * {@code DEFAULT_UNSUPPORTED_MODEL_PATTERNS}（小写 contains 匹配，CC :241-244）。
     *
     * <p>GrowthBook 可配 {@code tengu_tool_search_unsupported_models}（toolSearch.ts:210-223）
     * Java 无等价物 → N/A（登记，仅默认列表）。
     *
     * @param model 模型名（null → 保守 false）
     * @return true = 模型支持 tool_reference
     */
    public static boolean modelSupportsToolReference(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.toLowerCase();
        for (String pattern : DEFAULT_UNSUPPORTED_MODEL_PATTERNS) {
            if (normalized.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    /**
     * definitive 门控（纯 char fallback）· 对齐 CC {@code isToolSearchEnabled}（toolSearch.ts:385-473）·
     * 主循环工具搜索开关（claude.ts:1120 调用）。
     *
     * <p>委托 {@link #isToolSearchEnabled(List, String, CountTokensClient)} 传 {@code tokenClient=null}，
     * tst-auto 走纯 char fallback 分支（CC toolSearch.ts:742-754）。无 count_tokens 通道注入时
     * 的保守降级路径（3 参重载 Javadoc 见注入说明）。
     *
     * @param tools 可用工具列表
     * @param model 本次调用模型名
     * @return true = 本 turn 工具搜索启用
     */
    public static boolean isToolSearchEnabled(List<Tool> tools, String model) {
        return isToolSearchEnabled(tools, model, null);
    }

    /**
     * definitive 门控（token 优先 + char fallback）· 对齐 CC {@code isToolSearchEnabled}
     * （toolSearch.ts:385-473）· 主循环工具搜索开关（claude.ts:1120 调用）。
     *
     * <p>顺序：modelSupportsToolReference（CC :411-418）→ isToolSearchToolAvailable
     * （:420-427）→ mode 分派（tst / tst-auto / standard，:429-472）。
     *
     * <p><b>tst-auto token 优先</b>（CC checkAutoThreshold :712-756）：先经
     * {@link #checkAutoThreshold(List, String, CountTokensClient)} 走 count_tokens 精确计数
     * （CC getDeferredToolTokenCount :124-152），token 结果非 null → {@code enabled = tokens >= threshold}
     * （:730-738）；token 不可得（client null / API 失败 / 0 工具）→ char fallback（:742-754）。
     *
     * <p><b>注入说明（IMP-C6 已闭环）</b>: {@link ToolSearchService} 已是 {@code @Service} bean，
     * 经 {@link #resolveTokenClient(CountTokensClient)} 取得 count_tokens 通道：显式参数
     * {@code tokenClient} 优先（主循环 {@link com.nexusai.application.agent.LlmAgentLoop} 3 参注入
     * 自己的 {@code CountTokensClient}），null 时兜底 {@link #INSTANCE} 注入的客户端
     * （CC 模块内 countToolDefinitionTokens 直接可用对等物）。2 参纯 char 路径仅在 token 通道
     * 完全不可得时生效（非 Spring 单测 / 无 countTokensClient bean）。
     *
     * <p>CC {@code logModeDecision} 遥测（:395-409）Java 无事件通道 → N/A。CC provider 子检查
     * （:299-311）H3 已登记 N/A。CC {@code getDeferredToolTokenCount} memoize（:124-152）按 deferred
     * 工具名缓存、MCP connect/disconnect 失效——Java 端已实现等价缓存
     * {@link #getDeferredToolTokenCount(List, CountTokensClient)}（IMP-C6），工具集变化 → 新键
     * 自然失效；显式失效 {@link #invalidateDeferredToolTokenCountCache} 待 MCP 生命周期接线
     * （登记 OPD-IMP-30）。
     *
     * @param tools       可用工具列表
     * @param model       本次调用模型名
     * @param tokenClient count_tokens 客户端（null → 兜底注入客户端；两者均不可得 → 纯 char fallback）
     * @return true = 本 turn 工具搜索启用
     */
    public static boolean isToolSearchEnabled(List<Tool> tools, String model, CountTokensClient tokenClient) {
        if (tools == null || model == null) {
            return false;
        }
        if (!modelSupportsToolReference(model)) {
            debugLog("ToolSearch.isToolSearchEnabled: model '" + model + "' 不支持 tool_reference → false（CC toolSearch.ts:411-418）");
            return false;
        }
        if (!isToolSearchToolAvailable(tools)) {
            debugLog("ToolSearch.isToolSearchEnabled: ToolSearch 不在可用工具列表 → false（CC toolSearch.ts:420-427）");
            return false;
        }
        String mode = getToolSearchMode();
        switch (mode) {
            case "tst":
                debugLog("ToolSearch.isToolSearchEnabled: mode=tst → true");
                return true;
            case "tst-auto": {
                AutoThresholdDecision d = checkAutoThreshold(tools, model, resolveTokenClient(tokenClient));
                debugLog("ToolSearch.isToolSearchEnabled: mode=tst-auto → enabled=" + d.enabled()
                    + "（" + d.debugDescription() + "，CC toolSearch.ts:430-461）");
                return d.enabled();
            }
            default: // standard
                debugLog("ToolSearch.isToolSearchEnabled: mode=standard → false");
                return false;
        }
    }

    /**
     * count_tokens 通道解析 · IMP-C6 bean 化：显式参数优先（主循环 3 参注入），null → 兜底
     * {@link #INSTANCE} 注入的 {@link #tokenClient}（CC 模块内 countToolDefinitionTokens 直接
     * 可用对等物）。两者均不可得 → null（tst-auto 走 char fallback）。
     *
     * @param tokenClient 调用方显式传入的客户端（可 null）
     * @return 生效的 count_tokens 客户端；无可用通道 → null
     */
    private static CountTokensClient resolveTokenClient(CountTokensClient tokenClient) {
        if (tokenClient != null) {
            return tokenClient;
        }
        ToolSearchService inst = INSTANCE;
        return inst != null ? inst.tokenClient : null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // computeDeferredToolNames · CC claude.ts:1128-1134
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 预计算 deferred 工具名集合 · 对齐 CC claude.ts:1128-1134
     * （{@code for (t of tools) if (isDeferredTool(t)) deferredToolNames.add(t.name)}）。
     *
     * @param tools 可用工具列表
     * @return deferred 工具名集合（可空）
     */
    public static Set<String> computeDeferredToolNames(List<Tool> tools) {
        Set<String> names = new HashSet<>();
        if (tools == null) {
            return names;
        }
        for (Tool t : tools) {
            if (isDeferredTool(t, null)) {
                names.add(t.name());
            }
        }
        return names;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // filterToolsForSchema · CC claude.ts:1154-1172
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 过滤发送给 API 的工具 schema · 对齐 CC {@code filteredTools}（claude.ts:1154-1172）。
     *
     * <p>{@code useToolSearch=true}（Anthropic/Claude，动态工具加载）：
     * <ul>
     *   <li>非 deferred 恒留（claude.ts:1163-1164）</li>
     *   <li>ToolSearch 恒留（:1165-1166，模型可继续发现工具）</li>
     *   <li>deferred 工具仅当在 discovered set 中才留（:1167-1168，tool_reference 已发现）</li>
     * </ul>
     *
     * <p>{@code useToolSearch=false}（openai_compatible / deepseek，<b>[openai-lazy] Java 扩展
     * 2026-09-01，偏离 CC claude.ts:1170-1172「排除 ToolSearch + 全发」</b>）· 三态互斥由
     * {@link #mode}（search | activate | full）决定：
     * <ul>
     *   <li><b>full</b>（mode=full）→ 排除 ToolSearch + 全量发送（含 deferred，模型直接调用无搜索环节，
     *       对齐 CC :1170-1172 + 旧「完整 schema 模式」）</li>
     *   <li><b>search / activate</b>（mode=search 默认 / mode=activate）→ 非 deferred 恒留 +
     *       <b>ToolSearch 恒留</b>（CC :1170-1172 在此分支排除 ToolSearch —— 因 Anthropic 认为模型不支持
     *       tool_reference 则搜索无意义；Java 扩展保留：openai 模型无 tool_reference，ToolSearch 是唯一
     *       通道拿到 defer 工具完整 schema（命中返回 {@code <functions>} 文本），若排除 → deferred 工具
     *       永不暴露 → 死锁）+ deferred 仅当 discovered（openai 恒空）或激活（mode=activate 的激活集）
     *       中才留 —— <b>懒加载始终成立</b>：vision_analyze 等 defer 工具默认不进 API tools，不占 prompt</li>
     *   <li><b>短路</b>（deferred 空，claude.ts:1140-1147）→ ToolSearch 无对象可搜 → 排除（省 schema token）</li>
     * </ul>
     *
     * @param tools              可用工具列表
     * @param useToolSearch      本 turn 工具搜索开关（true=Anthropic tool_reference 语义；
     *                           false=openai_compatible，按 {@link #mode} 三态）
     * @param deferredToolNames  预计算 deferred 名（可 null）
     * @param discoveredToolNames 消息历史发现的 tool_reference 名集合（可 null；openai 恒空）
     * @return 过滤后的工具列表
     */
    public static List<Tool> filterToolsForSchema(
            List<Tool> tools, boolean useToolSearch,
            Set<String> deferredToolNames, Set<String> discoveredToolNames) {
        if (tools == null) {
            return List.of();
        }
        Set<String> deferred = deferredToolNames == null ? Set.of() : deferredToolNames;
        Set<String> discovered = discoveredToolNames == null ? Set.of() : discoveredToolNames;
        if (useToolSearch) {
            return tools.stream()
                .filter(t -> t != null)
                .filter(t -> {
                    if (!deferred.contains(t.name())) {
                        return true;                  // 非 deferred 恒留
                    }
                    if (matchesToolSearchName(t)) {
                        return true;                  // ToolSearch 恒留
                    }
                    // [activate-on-search] discovered（CC tool_reference 语义）或 ToolSearch 激活（Java 扩展）含才留
                    return discovered.contains(t.name()) || isActivated(t.name());
                })
                .toList();
        }
        // [openai-lazy] useToolSearch=false 不再「排除 ToolSearch + 全发」· 三态互斥（mode 枚举）：
        //   FULL（mode=full）或短路（无 deferred，claude.ts:1140-1147）→ 排除 ToolSearch + 全量
        //   （含 deferred；全发模式模型直接调用无搜索环节；短路 ToolSearch 无对象可搜）；
        //   其余（SEARCH/ACTIVATE）→ 有 deferred → 保留 ToolSearch（模型搜索拿 schema 的唯一通道）
        //   + deferred 照常过滤（懒加载；discovered 恒空，activated 由 mode=activate 的激活集控制）。
        if (deferred.isEmpty() || isFullSchemaMode()) {
            return tools.stream()
                .filter(t -> t != null && !matchesToolSearchName(t))
                .toList();
        }
        return tools.stream()
            .filter(t -> t != null)
            .filter(t -> {
                if (!deferred.contains(t.name())) {
                    return true;                  // 非 deferred 恒留
                }
                if (matchesToolSearchName(t)) {
                    return true;                  // ToolSearch 恒留（openai 搜索拿 schema 通道）
                }
                // deferred 仅 discovered（openai 恒空）或 ToolSearch 激活（mode=activate，Java 扩展）含才留
                return discovered.contains(t.name()) || isActivated(t.name());
            })
            .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // extractDiscoveredToolNames（复用 H2 真扫描）· CC toolSearch.ts:545-592
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 从消息历史提取已发现的 deferred 工具名 · 对齐 CC {@code extractDiscoveredToolNames}
     * （toolSearch.ts:545-592）· <b>复用 {@link SchemaNotSentHint#extractDiscoveredToolNames}
     * H2 真扫描</b>（同包静态方法，instanceof ChatMessageDto 守卫 + tool_result→tool_reference→
     * tool_name + compact boundary carry），不另写双轨。主循环 schema 构建前调用
     * （claude.ts:1158）。
     *
     * @param messages 消息历史（可 null → 空集）
     * @return 已发现的 deferred 工具名集合
     */
    public static Set<String> extractDiscoveredToolNames(List<?> messages) {
        return SchemaNotSentHint.extractDiscoveredToolNames(messages);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // isDeferredToolsDeltaEnabled · CC toolSearch.ts:629-633
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * deferred 工具 delta attachment 是否启用 · 对齐 CC {@code isDeferredToolsDeltaEnabled}
     * （toolSearch.ts:629-633）· {@code USER_TYPE==='ant' || glacier feature flag}。
     *
     * <p>Java 无 glacier feature flag（FeatureFlags 无 {@code tengu_glacier_2xr} 键）→ 只读
     * {@code USER_TYPE}。true → 完整 deferred_tools_delta attachment 承载（跨 compact 残留
     * OPD-H-06）；false → 主循环走 claude.ts:1330 prepend 路径（本批次实现）。
     *
     * @return true = delta attachment 启用（prepend 路径关闭）
     */
    public static boolean isDeferredToolsDeltaEnabled() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 私有 tst-auto 阈值辅助 · 镜像 CC toolSearch.ts:83-152 / 340-365 / 712-756
    // ═══════════════════════════════════════════════════════════════════════

    /** CC {@code getAutoToolSearchPercentage}（toolSearch.ts:83-97）· 默认 10%，ENABLE_TOOL_SEARCH='auto:N' 覆盖. */
    private static int getAutoToolSearchPercentage() {
        String value = currentEnv().get("ENABLE_TOOL_SEARCH");
        if (value == null || "auto".equals(value)) {
            return DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE;
        }
        Integer parsed = parseAutoPercentage(value);
        return parsed != null ? parsed : DEFAULT_AUTO_TOOL_SEARCH_PERCENTAGE;
    }

    /** CC {@code getAutoToolSearchTokenThreshold}（toolSearch.ts:104-112）· floor(contextWindow * percent/100). */
    private static int getAutoToolSearchTokenThreshold(String model) {
        int contextWindow = resolveContextWindow(model);
        return (int) Math.floor(contextWindow * (getAutoToolSearchPercentage() / 100.0));
    }

    /** CC {@code getAutoToolSearchCharThreshold}（toolSearch.ts:115-117）· floor(tokenThreshold * 2.5). */
    private static int getAutoToolSearchCharThreshold(String model) {
        return (int) Math.floor(getAutoToolSearchTokenThreshold(model) * CHARS_PER_TOKEN);
    }

    /**
     * 模型上下文窗口 · <b>G-10 收敛：委托 {@link CompactThresholdSystem#getContextWindowForModel}</b>
     * （对齐 CC {@code getContextWindowForModel}（utils/context.ts:51-98）七层链单一入口）。
     *
     * <p>解析顺序由 CompactThresholdSystem 统一承担（CC 同源）：
     * <ol>
     *   <li>{@code [1m]} 后缀 → {@code CONTEXT_1M_WINDOW}（utils/context.ts:69-72 显式 opt-in 优先，
     *       前置 {@code CLAUDE_CODE_DISABLE_1M_CONTEXT} 门 → has1mContext，G-13）</li>
     *   <li>DB 模型级窗口（models.max_context_tokens，G-15 全名优先消歧）→ 100k 能力门（G-11，
     *       &lt;100k 回落默认 200k）→ 1M 禁用钳制（G-14，&gt;200k 且禁用 → 200k）</li>
     *   <li>回落 {@code MODEL_CONTEXT_WINDOW_DEFAULT}（utils/context.ts:96-98）</li>
     * </ol>
     *
     * <p><b>独立 DB→[1m] 链已删除（原 W4-2 resolveDbContextWindow / G-15 私有解析）</b>: 共享 bean 的
     * DB resolver 由 {@code AgentLoopContextFactory.wireThresholdSystemResolver} 启动注入
     * （models.max_context_tokens 含 G-15 全名优先消歧）；未注入（非 Spring 单测）→
     * {@link CompactThresholdSystem#resolveWindowFallback} 同源静态兜底（[1m] 前置 + 禁用门 + 默认）。
     *
     * @param model 模型名（可 null）
     * @return 模型上下文窗口 tokens（恒 &gt; 0，CompactThresholdSystem 有默认兜底）
     */
    private static int resolveContextWindow(String model) {
        CompactThresholdSystem system = INSTANCE != null ? INSTANCE.compactThresholdSystem : null;
        if (system != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchService] resolveContextWindow 委托 CompactThresholdSystem（G-10 同源）: model={}",
                    model);
            }
            return system.getContextWindowForModel(model);
        }
        // 非 Spring / 阈值体系未注入 → 同源静态兜底（[1m] 前置 + 禁用门 + 默认 200k）
        return CompactThresholdSystem.resolveWindowFallback(model, is1mContextDisabled());
    }

    /**
     * 是否 1M 上下文禁用 · 对齐 CC {@code context.ts:31-33 is1mContextDisabled}
     * （{@code isEnvTruthy(process.env.CLAUDE_CODE_DISABLE_1M_CONTEXT)}，HIPAA 合规禁用场景）。
     *
     * <p><b>保留用途</b>: {@link #resolveContextWindow} 非 Spring 兜底经
     * {@link CompactThresholdSystem#resolveWindowFallback} 传禁用门（生产禁用门由
     * CompactThresholdSystem 内部 CompactEnvProperties 承载）。
     *
     * @return true = 1M 上下文被禁用（has1mContext 恒 false）
     */
    private static boolean is1mContextDisabled() {
        return isEnvTruthy(currentEnv().get("CLAUDE_CODE_DISABLE_1M_CONTEXT"));
    }

    /** CC {@code calculateDeferredToolDescriptionChars}（toolSearch.ts:340-365）· name+prompt+schema 字符和. */
    private static int calculateDeferredToolDescriptionChars(List<Tool> tools) {
        int total = 0;
        if (tools == null) {
            return 0;
        }
        for (Tool tool : tools) {
            if (!isDeferredTool(tool, null)) {
                continue;
            }
            String description = tool.prompt();
            if (description == null) {
                description = "";
            }
            total += tool.name().length() + description.length() + jsonStringifyInputSchema(tool).length();
        }
        return total;
    }

    /** CC {@code jsonStringify(inputSchema)}（toolSearch.ts:349-352）· inputJSONSchema 优先，否则 inputSchema. */
    private static String jsonStringifyInputSchema(Tool tool) {
        JsonNode schema = tool.inputJSONSchema();
        if (schema == null) {
            schema = tool.inputSchema();
        }
        return schema == null ? "" : schema.toString();
    }

    /**
     * CC {@code checkAutoThreshold}（toolSearch.ts:712-756）· token 优先 + char fallback。
     *
     * <p>顺序：先 {@link #getDeferredToolTokenCount}（CC :722-728 getDeferredToolTokenCount），
     * 非 null → {@code enabled = deferredToolTokens >= getAutoToolSearchTokenThreshold(model)}
     * （CC :730-738，metrics 含 deferredToolTokens/threshold）；null → char fallback
     * （CC :741-755 calculateDeferredToolDescriptionChars + getAutoToolSearchCharThreshold）。
     *
     * <p><b>阻塞成本（fail loud，IMP-C6 memoize 已缓解）</b>: CC {@code getDeferredToolTokenCount}
     * 是 async + memoize（toolSearch.ts:124-152 按 deferred 工具名缓存），Java
     * {@link CountTokensClient#countTokensForTools} 是同步 HTTP 调用；tst-auto 主循环每 turn
     * 同步计数有阻塞成本——IMP-C6 已实现等价缓存 {@link #getDeferredToolTokenCount(List, CountTokensClient)}
     * （工具集不变 → 缓存命中免 HTTP；MCP connect/disconnect → 新键自然失效，显式失效
     * {@link #invalidateDeferredToolTokenCountCache} 登记 OPD-IMP-30）。
     *
     * @param tools       可用工具列表
     * @param model       本次调用模型名
     * @param tokenClient count_tokens 客户端（null → 纯 char fallback）
     */
    private static AutoThresholdDecision checkAutoThreshold(
            List<Tool> tools, String model, CountTokensClient tokenClient) {
        if (tokenClient != null) {
            Integer deferredToolTokens = getDeferredToolTokenCount(tools, tokenClient);
            if (deferredToolTokens != null) {
                int threshold = getAutoToolSearchTokenThreshold(model);
                if (log.isDebugEnabled()) {
                    log.debug("[ToolSearchService] checkAutoThreshold token 分支: deferredToolTokens={}, "
                        + "threshold={}, {}% of context（CC toolSearch.ts:730-738）",
                        deferredToolTokens, threshold, getAutoToolSearchPercentage());
                }
                return new AutoThresholdDecision(
                    deferredToolTokens >= threshold,
                    deferredToolTokens + " tokens (threshold: " + threshold + ", "
                        + getAutoToolSearchPercentage() + "% of context)");
            }
        }
        return checkAutoThresholdCharFallback(tools, model);
    }

    /**
     * CC {@code getDeferredToolTokenCount} memoize 缓存（toolSearch.ts:124-152）· 键 = deferred
     * 工具名逗号连接（sorted，确定性，等价 CC resolver {@code tools.filter(isDeferredTool).map(name).join(',')}）；
     * 值 = 精确 token 数；{@link #CACHE_API_UNAVAILABLE} 表示 API 不可得（CC memoize 缓存 null 结果，
     * 避免同工具集重复打 count_tokens）。
     *
     * <p>失效语义（对齐 CC）：工具集变化（MCP connect/disconnect → deferred 名变化）→ 新键自然
     * 失效；显式失效 {@link #invalidateDeferredToolTokenCountCache} 供 MCP 生命周期接线
     * （登记 OPD-IMP-30，McpToolPool connect/disconnect 时调用）。
     */
    private static final ConcurrentHashMap<String, Integer> DEFERRED_TOKEN_CACHE = new ConcurrentHashMap<>();

    /** 缓存哨兵：API 不可得（countTokensForTools null/0/异常）→ 调用方回退 char（CC memoize 缓存 null）. */
    private static final int CACHE_API_UNAVAILABLE = -1;

    /**
     * 显式清空 deferred token 计数缓存 · 对齐 CC memoize 缓存生命周期：MCP server connect/disconnect
     * 时工具集变化，理论上新键自然失效，但显式清理可防止旧键残留累积。接线点：MCP 连接生命周期
     * （McpToolPool connect/disconnect，登记 OPD-IMP-30）。幂等。
     */
    public static void invalidateDeferredToolTokenCountCache() {
        DEFERRED_TOKEN_CACHE.clear();
        if (log.isDebugEnabled()) {
            log.debug("[ToolSearchService] invalidateDeferredToolTokenCountCache: deferred token 计数缓存已清空（MCP 生命周期接线，OPD-IMP-30）");
        }
    }

    /**
     * CC {@code getDeferredToolTokenCount}（toolSearch.ts:124-152）· deferred 工具集 count_tokens，
     * <b>memoize 化（IMP-C6）</b>。
     * {@code deferredTools 空 → 0}（非 null → token 分支 enabled=false）；缓存命中 → 直接返回；
     * 未命中 → {@code countTokensForTools} 计算并缓存；返回 null/0 → 缓存哨兵并回退 char；
     * 否则 {@code max(0, raw - TOOL_TOKEN_COUNT_OVERHEAD)}；异常 → 缓存哨兵回退 char。
     */
    private static Integer getDeferredToolTokenCount(List<Tool> tools, CountTokensClient tokenClient) {
        List<Tool> deferredTools = tools == null ? List.of()
            : tools.stream().filter(t -> isDeferredTool(t, null)).toList();
        if (deferredTools.isEmpty()) {
            return 0; // CC :127 空 deferred → 0（非 null → token 分支 enabled=false）
        }
        String cacheKey = deferredTools.stream()
            .map(Tool::name)
            .sorted()
            .collect(Collectors.joining(","));
        Integer cached = DEFERRED_TOKEN_CACHE.get(cacheKey);
        if (cached != null) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchService] getDeferredToolTokenCount 缓存命中: key={}, value={}（CC toolSearch.ts:124-152 memoize）",
                    cacheKey, cached == CACHE_API_UNAVAILABLE ? "API不可得→char回退" : cached);
            }
            return cached == CACHE_API_UNAVAILABLE ? null : cached;
        }
        try {
            List<ToolSchema> schemas = deferredTools.stream()
                .map(ToolSearchService::toToolSchema)
                .toList();
            Integer total = tokenClient.countTokensForTools(schemas);
            if (total == null || total == 0) {
                DEFERRED_TOKEN_CACHE.put(cacheKey, CACHE_API_UNAVAILABLE);
                if (log.isDebugEnabled()) {
                    log.debug("[ToolSearchService] getDeferredToolTokenCount: countTokensForTools 返回 "
                        + "{}, {} 个 deferred 工具 → 缓存哨兵, 回退 char fallback（CC toolSearch.ts:133-134）",
                        total, deferredTools.size());
                }
                return null;
            }
            Integer result = Math.max(0, total - TOOL_TOKEN_COUNT_OVERHEAD);
            DEFERRED_TOKEN_CACHE.put(cacheKey, result);
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchService] getDeferredToolTokenCount: 计算并缓存 {} tokens（key={}，{} 个 deferred 工具，CC toolSearch.ts:130-142）",
                    result, cacheKey, deferredTools.size());
            }
            return result;
        } catch (RuntimeException e) {
            DEFERRED_TOKEN_CACHE.put(cacheKey, CACHE_API_UNAVAILABLE);
            if (log.isDebugEnabled()) {
                log.debug("[ToolSearchService] getDeferredToolTokenCount 异常 → 缓存哨兵, 回退 char fallback（CC toolSearch.ts:135-136）: {}",
                    e.getMessage());
            }
            return null;
        }
    }

    /**
     * Tool → CountTokensClient.ToolSchema · 对齐 CC {@code toolToAPISchema} 产物投影
     * （analyzeContext.ts:234-258 countToolDefinitionTokens 计数口径：name + prompt + input_schema）。
     *
     * <p>description 用 {@code tool.prompt()}（CC analyzeContext.ts:652 {@code await t.prompt(...)}，
     * 非 {@code description()}——与 {@link #calculateDeferredToolDescriptionChars} 同口径）；
     * inputSchema 用 {@code inputJSONSchema()} 优先、{@code inputSchema()} 兜底（CC :655
     * {@code t.inputJSONSchema ?? {}}，同 {@link #jsonStringifyInputSchema} 优先逻辑）。
     */
    private static ToolSchema toToolSchema(Tool tool) {
        String prompt = tool.prompt();
        JsonNode schema = tool.inputJSONSchema();
        if (schema == null) {
            schema = tool.inputSchema();
        }
        return new ToolSchema(tool.name(), prompt == null ? "" : prompt, schema);
    }

    /**
     * CC {@code checkAutoThreshold} char fallback 分支（toolSearch.ts:741-755）· 仅 char 阈值
     * （token 计数不可得时走此路径）。
     */
    private static AutoThresholdDecision checkAutoThresholdCharFallback(List<Tool> tools, String model) {
        int deferredToolDescriptionChars = calculateDeferredToolDescriptionChars(tools);
        int charThreshold = getAutoToolSearchCharThreshold(model);
        return new AutoThresholdDecision(
            deferredToolDescriptionChars >= charThreshold,
            deferredToolDescriptionChars + " chars (threshold: " + charThreshold + ", "
                + getAutoToolSearchPercentage() + "% of context) (char fallback)");
    }

    /** checkAutoThreshold 返回载体（enabled + 调试描述，镜像 CC 返回对象两字段）. */
    private record AutoThresholdDecision(boolean enabled, String debugDescription) {
    }

    /** debug 级数据流日志（CLAUDE.md 规范：slf4j + if(isDebugEnabled) 包裹 + 中文语境）. */
    private static void debugLog(String msg) {
        if (log.isDebugEnabled()) {
            log.debug(msg);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 私有 env 辅助 · 镜像 CC toolSearch.ts / envUtils.ts
    // ═══════════════════════════════════════════════════════════════════════

    /** CC {@code parseAutoPercentage}（toolSearch.ts:55-70）· 'auto:N' → clamp(0-100)，否则 null. */
    private static Integer parseAutoPercentage(String value) {
        if (value == null || !value.startsWith("auto:")) {
            return null;
        }
        try {
            int percent = Integer.parseInt(value.substring(5));
            return Math.max(0, Math.min(100, percent));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** CC {@code isAutoToolSearchMode}（toolSearch.ts:75-78）· 'auto' 或 'auto:...'. */
    private static boolean isAutoToolSearchMode(String value) {
        return value != null && (value.equals("auto") || value.startsWith("auto:"));
    }

    /** CC {@code isEnvTruthy}（envUtils.ts:32-37）· 1/true/yes/on（case-insensitive trim）. */
    private static boolean isEnvTruthy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase().trim();
        return List.of("1", "true", "yes", "on").contains(normalized);
    }

    /** CC {@code isEnvDefinedFalsy}（envUtils.ts:39-47）· 已定义且为 0/false/no/off. */
    private static boolean isEnvDefinedFalsy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String normalized = value.toLowerCase().trim();
        return List.of("0", "false", "no", "off").contains(normalized);
    }
}

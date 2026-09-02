package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.infra.llm.StructuredOutputsSupport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具注册中心 · 对齐 s02 的 TOOL_HANDLERS dict + CC 的 {@code getAllBaseTools()}。
 *
 * <p>职责：
 * <ul>
 *   <li>按 name 查表分发（O(1)）</li>
 *   <li>汇总所有 tool 的 JSON schema 给 LLM</li>
 *   <li>未注册的工具调用 → 返回标准错误结果（不抛异常）</li>
 * </ul>
 *
 * <p>实现细节：内部用 {@link LinkedHashMap} 保留注册顺序，schema 数组顺序与注册顺序
 * 一致 —— LLM 看到工具列表的顺序与代码定义一致，便于测试和调试。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    // 早期工具
    private List<Tool> earlyTools;

    /**
     * B5 (OPD-TOOL-02-1): todoTaskTools @Bean 返回 {@code List<Tool>}（bean 类型是 List 而非 Tool）。
     *
     * <p>Spring 集合注入 {@code @Autowired List<Tool>} 只收集「元素类型为 Tool 的独立 Tool bean」，
     * 不展开 bean 类型本身为 {@code List<Tool>} 的集合 bean（EV-RG-023 E4 实证）→
     * {@code todoTaskTools} 内部的 TodoWriteTool + DiscoverSkillsTool + 8 个 feature 桩永不进入
     * {@code earlyTools}。此处按 bean 名 {@code @Resource} 显式注入，在 {@link #init()} 内
     * {@link #registerAll(List)} 逐个注册，对齐 CC tools.ts getAllBaseTools 扁平工具数组语义
     * （逐个工具实例，无「List 嵌套再展开」语义）。
     *
     * <p><b>{@code @Lazy} 破循环</b>：本字段若 eager 注入会拉出
     * toolRegistry → todoTaskTools → toolRegistrationConfig → mcpServerService → mcpToolPool →
     * toolRegistry 的循环依赖（toolRegistrationConfig 的 {@code mcpServerService} 字段链回
     * {@code McpToolPool} 构造器 param 1 = toolRegistry）。{@code @Lazy} 使本字段注入惰性代理，
     * 实际解析推迟到 {@link #init()}（ApplicationReadyEvent，此时所有 bean 已就绪）。
     */
    @Lazy
    @Resource(name = "todoTaskTools")
    private List<Tool> todoTaskTools;

    /**
     * [browser-mcp-align] nexusai-in-chrome 浏览器工具 @Bean（{@code List<Tool>}）。
     *
     * <p>同 {@link #todoTaskTools} 通道：Spring 集合注入 {@code @Autowired List<Tool>} 不展开
     * {@code List<Tool>} 集合 bean → 经本字段 {@code @Resource(name="browserMcpTools")} 显式注入，
     * 在 {@link #init()} 内 {@link #registerAll(List)} 逐个注册（对齐 CC tools.ts getAllBaseTools
     * 扁平工具数组语义；BrowserMcpToolConfig @Bean 构建 18 个 mcp__nexusai-in-chrome__* 工具）。
     * {@code @Lazy} 破循环（同 todoTaskTools 先例，延迟到 ApplicationReadyEvent 解析）。
     */
    @Lazy
    @Resource(name = "browserMcpTools")
    private List<Tool> browserMcpTools;

    /**
     * H7-arch Phase 3: 跳过 SPECIAL_TOOLS 过滤 · 对齐 CC execAgentHook.ts:93-105
     * hook agent 工具集手工构建（父工具过滤 + StructuredOutput，无 SPECIAL_TOOLS 排除）。
     *
     * <p>WHY: {@link #toOpenAiToolsArray} / {@link #getTools} 默认过滤 SPECIAL_TOOLS
     * （含 StructuredOutput），导致 hook agent 的 SyntheticOutputTool 不暴露给 LLM。
     * hook agent 的 effectiveRegistry 仅含 [父工具过滤 + SyntheticOutputTool]，set 本 flag=true
     * 让 StructuredOutput 进入 LLM schema。其余 registry（主循环/subagent）保持 false 不变。
     *
     * <p>对齐 CC: CC 端 hook agent 的 tools 数组是手工构建的（filteredTools + structuredOutputTool），
     * 不经过 specialTools 过滤；Java 用此 flag 等价（effectiveRegistry 只含 hook 工具，跳过过滤安全）。
     */
    private boolean skipSpecialToolsFilter = false;

    /**
     * P1.2: alias → Tool 反向映射 · 对齐 CC Tool.ts:355-360 {@code findToolByName}.
     *
     * <p>当 Tool 声明 {@code aliases()}, 每个 alias 也映射到同一 Tool 实例,
     * 保证 {@link #get(String)} + {@link #has(String)} 按 name OR alias 都能查到.
     *
     * <p>不变量: aliasMap 与 tools 引用同步 (同一 Tool 实例), 不同 alias 可映射到
     * 同一 Tool (LLM 历史 transcript backward-compat — e.g. 老调用"Read"也能反查到
     * 新版 read_file).
     */
    private final Map<String, Tool> aliasMap = new LinkedHashMap<>();

    /**
     * s19-P1-6: 上一次 assemble_tool_pool 注入的 MCP 工具名集合.
     *
     * <p>用于在下次 assemble 时识别"已下线"的 MCP 工具并移除.
     * builtin 工具不在此 set 中, 因此不会被误删.
     */
    private final java.util.Set<String> mcpToolNames = new java.util.LinkedHashSet<>();

    /**
     * <b>[IMP-C4 REQ-G3-2-5] 显式工具序</b> · 对齐 CC {@code tools.ts:194-250 getAllBaseTools()}
     * 的显式 46+ 位次工具序（prompt-cache 稳定不变量，01 §1.1 不变量3）。
     *
     * <p><b>WHY</b>: Java {@code @Autowired List<Tool>} 按 Spring bean 发现序收集，与 CC 显式序漂移，
     * 使 LLM 工具 schema 数组（{@link #toOpenAiToolsArray} 遍历 {@link #tools} LinkedHashMap 插入序）
     * 顺序不稳定 → 破坏 prompt-cache 跨会话稳定。CC 端 getAllBaseTools() 显式排列全部工具
     * （tools.ts:194-250），本列表镜像该顺序（含 feature-gated 桩，未注册者自然跳过）。
     *
     * <p>排序语义（{@link #sortByCcOrder}）：已知工具按本列表位次升序；未知工具（MCP 工具 /
     * 非 CC 列表的 Java-only 工具）保持相对顺序置于已知工具之后（稳定排序）——与 CC
     * assembleToolPool/getMergedTools 的「builtin 前缀 + MCP 后缀」分区一致。
     */
    private static final java.util.List<String> CC_TOOL_ORDER = java.util.List.of(
        // ── tools.ts:195-219 主干 + 门控（按 CC 字面量顺序）──
        com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME,       // AgentTool :195
        ToolNameConstants.TASK_OUTPUT_TOOL_NAME,                                     // TaskOutputTool :196
        ToolNameConstants.BASH_TOOL_NAME,                                            // BashTool :197
        ToolNameConstants.GLOB_TOOL_NAME,                                            // GlobTool :201
        ToolNameConstants.GREP_TOOL_NAME,                                            // GrepTool :201
        ToolNameConstants.EXIT_PLAN_MODE_TOOL_NAME,                                  // ExitPlanModeV2Tool :202
        ToolNameConstants.FILE_READ_TOOL_NAME,                                       // FileReadTool :203
        ToolNameConstants.FILE_EDIT_TOOL_NAME,                                       // FileEditTool :204
        ToolNameConstants.FILE_WRITE_TOOL_NAME,                                      // FileWriteTool :205
        ToolNameConstants.NOTEBOOK_EDIT_TOOL_NAME,                                   // NotebookEditTool :206
        ToolNameConstants.WEB_FETCH_TOOL_NAME,                                       // WebFetchTool :207
        ToolNameConstants.TODO_WRITE_TOOL_NAME,                                      // TodoWriteTool :208
        ToolNameConstants.WEB_SEARCH_TOOL_NAME,                                      // WebSearchTool :209
        ToolNameConstants.TASK_STOP_TOOL_NAME,                                       // TaskStopTool :210
        ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME,                               // AskUserQuestionTool :211
        ToolNameConstants.SKILL_TOOL_NAME,                                           // SkillTool :212
        ToolNameConstants.ENTER_PLAN_MODE_TOOL_NAME,                                 // EnterPlanModeTool :213
        ToolNameConstants.CONFIG_TOOL_NAME,                                          // ConfigTool :214 (ant)
        ToolNameConstants.TUNGSTEN_TOOL_NAME,                                        // TungstenTool :215 (ant)
        ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME,                           // SuggestBackgroundPRTool :216
        ToolNameConstants.WEB_BROWSER_TOOL_NAME,                                     // WebBrowserTool :217
        ToolNameConstants.TASK_CREATE_TOOL_NAME,                                     // TaskCreateTool :219 (todoV2)
        ToolNameConstants.TASK_GET_TOOL_NAME,                                        // TaskGetTool :219 (todoV2)
        ToolNameConstants.TASK_UPDATE_TOOL_NAME,                                     // TaskUpdateTool :219 (todoV2)
        ToolNameConstants.TASK_LIST_TOOL_NAME,                                       // TaskListTool :219 (todoV2)
        ToolNameConstants.CTX_INSPECT_TOOL_NAME,                                     // CtxInspectTool :222
        ToolNameConstants.TERMINAL_CAPTURE_TOOL_NAME,                                // TerminalCaptureTool :223
        ToolNameConstants.LSP_TOOL_NAME,                                             // LSPTool :224
        ToolNameConstants.ENTER_WORKTREE_TOOL_NAME,                                  // EnterWorktreeTool :225
        ToolNameConstants.EXIT_WORKTREE_TOOL_NAME,                                   // ExitWorktreeTool :225
        ToolNameConstants.SEND_MESSAGE_TOOL_NAME,                                    // SendMessageTool :226
        ToolNameConstants.LIST_PEERS_TOOL_NAME,                                      // ListPeersTool :227
        ToolNameConstants.TEAM_CREATE_TOOL_NAME,                                     // TeamCreateTool :229 (swarms)
        ToolNameConstants.TEAM_DELETE_TOOL_NAME,                                     // TeamDeleteTool :229 (swarms)
        ToolNameConstants.VERIFY_PLAN_EXECUTION_TOOL_NAME,                           // VerifyPlanExecutionTool :231
        ToolNameConstants.REPL_TOOL_NAME,                                            // REPLTool :232 (ant)
        ToolNameConstants.WORKFLOW_TOOL_NAME,                                        // WorkflowTool :233
        ToolNameConstants.SLEEP_TOOL_NAME,                                           // SleepTool :234
        ToolNameConstants.CRON_CREATE_TOOL_NAME,                                     // CronCreateTool :235
        ToolNameConstants.CRON_DELETE_TOOL_NAME,                                     // CronDeleteTool :235
        ToolNameConstants.CRON_LIST_TOOL_NAME,                                       // CronListTool :235
        "RemoteTrigger",                                                             // RemoteTriggerTool :236（CC prompt.ts:1 真名，无 ToolNameConstants 常量）
        ToolNameConstants.MONITOR_TOOL_NAME,                                         // MonitorTool :237
        ToolNameConstants.BRIEF_TOOL_NAME,                                           // BriefTool (SendUserMessage) :238
        ToolNameConstants.SEND_USER_FILE_TOOL_NAME,                                  // SendUserFileTool :239
        ToolNameConstants.PUSH_NOTIFICATION_TOOL_NAME,                               // PushNotificationTool :240
        ToolNameConstants.SUBSCRIBE_PR_TOOL_NAME,                                    // SubscribePRTool :241
        ToolNameConstants.POWER_SHELL_TOOL_NAME,                                     // PowerShellTool :242
        ToolNameConstants.SNIP_TOOL_NAME,                                            // SnipTool :243
        ToolNameConstants.TESTING_PERMISSION_TOOL_NAME,                              // TestingPermissionTool :244
        ToolNameConstants.LIST_MCP_RESOURCES_TOOL_NAME,                              // ListMcpResourcesTool :245
        ToolNameConstants.READ_MCP_RESOURCE_TOOL_NAME,                               // ReadMcpResourceTool :246
        ToolNameConstants.TOOL_SEARCH_TOOL_NAME                                      // ToolSearchTool :249
    );

    /**
     * <b>[IMP-C4 REQ-G3-2-5] 按 CC 显式序稳定排序</b> · 对齐 CC getAllBaseTools() 位次。
     *
     * <p>已知工具按 {@link #CC_TOOL_ORDER} 位次升序；未在列表中的工具（MCP / Java-only）
     * 保持相对顺序置于已知工具之后。{@code List.sort} 稳定 → 未知工具相对序不漂。
     *
     * @param tools 待排序工具列表（原列表不改）
     * @return 排序后的新列表
     */
    static java.util.List<Tool> sortByCcOrder(java.util.List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return tools == null ? java.util.List.of() : new java.util.ArrayList<>(tools);
        }
        java.util.List<Tool> sorted = new java.util.ArrayList<>(tools);
        java.util.Map<String, Integer> pos = new java.util.HashMap<>();
        for (int i = 0; i < CC_TOOL_ORDER.size(); i++) {
            pos.put(CC_TOOL_ORDER.get(i), i);
        }
        sorted.sort(java.util.Comparator.comparingInt(
            t -> pos.getOrDefault(t.name(), Integer.MAX_VALUE)));
        return sorted;
    }

    /**
     * Spring 注入所有 {@link Tool} bean（5 个内置工具），按注册顺序保留（{@link LinkedHashMap}）。
     * 测试时也可以 {@code new ToolRegistry()} 手动调 {@link #register(Tool)}。
     */
    @Autowired
    public ToolRegistry(@Lazy List<Tool> toolBeans) {
        // 只保存懒加载列表，不做任何访问
        this.earlyTools = toolBeans;
    }

    // 在 Spring 容器完全启动后，再注册所有工具
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // B2 (OPD-09): 早期注册 bean 改走 register() 建 aliasMap —— 旧直 put tools 绕过
        // register 导致 aliasMap 为空, LLM 历史 transcript 老名 (read_file 等) 经 dispatch
        // 查不到 → async 子 agent 丢 4 工具. register/get/has 的 alias 语义既有, 不改.
        // [IMP-C4 REQ-G3-2-5] 显式工具序: @Autowired List<Tool> 按 Spring 收集序漂移 →
        // 注册前按 CC getAllBaseTools() 位次稳定排序（prompt-cache 不变量）。
        java.util.List<Tool> orderedEarly = sortByCcOrder(earlyTools);
        for (Tool t : orderedEarly) {
            register(t);
        }
        if (log.isDebugEnabled()) {
            log.debug("ToolRegistry: init 完成, 已接线 {} 个早期 bean (含 aliasMap), 总计 {} 工具",
                orderedEarly.size(), tools.size());
        }

        // B5 (OPD-TOOL-02-1): 显式注册 todoTaskTools @Bean（List<Tool>）内的工具。
        // Spring `@Autowired List<Tool>` 只收集独立 Tool bean，不展开 `@Bean List<Tool>` 集合 bean，
        // 故 TodoWriteTool / DiscoverSkillsTool / 8 个 feature 桩必须经此通道进入生产 ToolRegistry。
        // 对齐 CC tools.ts getAllBaseTools 扁平工具数组（逐个工具实例，无「List 嵌套再展开」语义）。
        if (todoTaskTools != null && !todoTaskTools.isEmpty()) {
            log.info("ToolRegistry: init 接线 todoTaskTools @Bean（List<Tool>），共 {} 个工具: {}",
                todoTaskTools.size(), todoTaskTools.stream().map(Tool::name).toList());
            // [IMP-C4 REQ-G3-2-5] todoTaskTools 亦按 CC 显式序注册（TodoWrite/Task*/8 桩位次）
            registerAll(sortByCcOrder(todoTaskTools));
            log.info("ToolRegistry: todoTaskTools registerAll 完成，当前总计 {} 工具", tools.size());
        } else if (log.isDebugEnabled()) {
            log.debug("ToolRegistry: todoTaskTools @Bean 为空或未接线，跳过（对齐 CC flag-off 全关 → 无待注册工具）");
        }

        // [browser-mcp-align] 显式注册 browserMcpTools @Bean（List<Tool>）内的 18 个浏览器工具。
        // 同 B5 通道：browserMcpTools 由 BrowserMcpToolConfig @Bean 构建（mcp__nexusai-in-chrome__*），
        // 本处注册进生产 registry（对齐 CC getAllBaseTools 扁平工具数组逐个注册）。
        if (browserMcpTools != null && !browserMcpTools.isEmpty()) {
            log.info("ToolRegistry: init 接线 browserMcpTools @Bean（List<Tool>），共 {} 个工具: {}",
                browserMcpTools.size(), browserMcpTools.stream().map(Tool::name).toList());
            registerAll(sortByCcOrder(browserMcpTools));
            log.info("ToolRegistry: browserMcpTools registerAll 完成，当前总计 {} 工具", tools.size());
        } else if (log.isDebugEnabled()) {
            log.debug("ToolRegistry: browserMcpTools @Bean 为空或未接线，跳过（nexusai-in-chrome 工具未注册）");
        }

        if (!tools.isEmpty()) {
            log.info("ToolRegistry: {} tools registered: {}", tools.size(), tools.keySet());
        }
    }

    /** 无 Spring 场景用（测试）—— 后续手动 {@link #register}。 */
    public ToolRegistry() {
    }

    /**
     * H7-arch Phase 3: 设置跳过 SPECIAL_TOOLS 过滤 · hook agent effectiveRegistry 用。
     * 对齐 CC execAgentHook.ts:93-105 手工构建 tools（含 StructuredOutput）。
     */
    public void setSkipSpecialToolsFilter(boolean skipSpecialToolsFilter) {
        this.skipSpecialToolsFilter = skipSpecialToolsFilter;
    }


    public ToolRegistry register(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("tool is null");
        if (tools.containsKey(tool.name())) {
            log.warn("ToolRegistry: tool '{}' already registered, overwriting", tool.name());
        }
        tools.put(tool.name(), tool);
        log.debug("ToolRegistry: registered '{}'", tool.name());

        // P1.2: register 时同步建立 alias 反向映射 (CC Tool.ts:368-371 toolMatchesName 语义)
        List<String> aliases = tool.aliases();
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.isBlank()) {
                    log.warn("ToolRegistry: skipping null/blank alias for tool '{}'",
                        tool.name());
                    continue;
                }
                if (alias.equals(tool.name())) {
                    log.debug("ToolRegistry: alias '{}' == name() for tool '{}', skipping",
                        alias, tool.name());
                    continue;
                }
                if (aliasMap.containsKey(alias) && aliasMap.get(alias) != tool) {
                    log.warn("ToolRegistry: alias '{}' already mapped to another tool "
                        + "(existing='{}', new='{}'), overwriting",
                        alias, aliasMap.get(alias).name(), tool.name());
                }
                aliasMap.put(alias, tool);
                log.debug("ToolRegistry: registered alias '{}' → '{}'", alias, tool.name());
            }
        }
        return this;
    }

    public ToolRegistry registerAll(List<Tool> toolList) {
        toolList.forEach(this::register);
        return this;
    }

    /**
     * [H7-arch Phase 5-2 P3 D7] 从工具列表构建临时隔离 registry · 对齐 CC toolUseContext.options.tools。
     *
     * <p><b>WHY</b>: P3-⑤ 工具隔离迁移后，loop / buildStreamingExecutor / MCP 池刷新的工具来源
     * 从「共享 {@code ctx.toolRegistry()}」改为「per-turn TUC 的 {@code availableTools()}」。
     * 本静态工厂把 {@code List<Tool>} 适配为临时 {@link ToolRegistry}（逐项 {@link #register}
     * 跳过 null；空/null → 空 registry），供 LLM schema / executor 构造 / assembleToolPool 使用，
     * 不触碰共享单例 registry 内部状态。
     *
     * @param tools 工具列表（可为 null / 含 null → 跳过）
     * @return 仅含有效工具的临时 registry（空/null 输入 → 空 registry）
     */
    public static ToolRegistry from(List<Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        if (tools != null) {
            // [IMP-C4 REQ-G3-2-5] 临时 registry 亦按 CC 显式序注册（fork/subagent LLM schema 稳定）
            for (Tool t : sortByCcOrder(tools)) {
                if (t == null) {
                    continue;
                }
                registry.register(t);
            }
        }
        return registry;
    }

    /**
     * s20-P1-1: 动态移除工具 · 对齐 CC assemble_tool_pool() 动态增删 (MCP 客户端下线).
     *
     * <p>P1.2: 当按主名 {@code name} 移除时, 同步清理 aliasMap 中该 tool 的所有 alias
     * 映射. 当按 alias 移除时, 仅移除该 alias 映射, 主名仍保留.
     *
     * <p>返回是否成功移除 (false = 工具名不存在).
     */
    public boolean remove(String name) {
        if (name == null) return false;
        Tool t = tools.remove(name);
        if (t != null) {
            // 主名移除: 同步清掉该 tool 的所有 alias (CC 源配套清, 不留 dangling alias)
            List<String> aliases = t.aliases();
            if (aliases != null) {
                for (String a : aliases) {
                    aliasMap.remove(a);
                }
            }
            return true;
        }
        // 按 alias 移除: 找到对应 tool 但保留 tool 本身 (保留主名 + 其他 alias)
        return aliasMap.remove(name) != null;
    }

    /**
     * s19-P1-6: assembleToolPool · 对齐 CC tools.ts:345 assembleToolPool.
     *
     * <p>合并 builtin 工具 + 外部 MCP tools 到当前 pool, <b>替换</b>同名旧 MCP entry
     * 且<b>移除</b>已下线的 MCP 工具. 典型用法: LlmAgentLoop 每轮 LLM 调用前调本方法
     * 刷新 pool (MCP 客户端上线/下线 → 工具集合动态变化).
     *
     * <p>实现策略:
     * <ol>
     *   <li>计算新 MCP 工具名集合 (仅保留 {@link Tool#isEnabled()} 的工具)</li>
     *   <li>移除旧 MCP 集合中<b>已下线</b>的 entry (差集) — builtin 不受影响</li>
     *   <li>register 新 MCP tools (同名替换 + 新增, isEnabled=false 跳过)</li>
     *   <li>更新 mcpToolNames 跟踪集合</li>
     * </ol>
     *
     * <p>向后兼容: 传入 null/空 list 时只移除已下线 MCP 工具, 不动 builtin.
     *
     * <p><b>[R32-#11]</b> isEnabled 守卫: MCP 工具运行时关闭 → 不进入 pool
     * (CC 端 tools.ts:325 getTools 也调 isEnabled 过滤). 此处保留工具注册条目
     * (不删 builtin), 仅在 MCP tools 注入时按 isEnabled 跳过 — builtin 不受影响.
     *
     * @param mcpTools MCP server 暴露的工具列表 (可为 null/空)
     * @return 合并后 pool 总数
     */
    public int assembleToolPool(List<Tool> mcpTools) {
        java.util.Set<String> newMcpNames = new java.util.LinkedHashSet<>();
        if (mcpTools != null) {
            for (Tool t : mcpTools) {
                if (t != null && t.isEnabled()) newMcpNames.add(t.name());
            }
        }
        int before = tools.size();

        // 1. 移除旧 MCP 集合中已下线的 entry (差集)
        int removed = 0;
        java.util.Set<String> toRemove = new java.util.LinkedHashSet<>();
        for (String oldName : mcpToolNames) {
            if (!newMcpNames.contains(oldName)) {
                toRemove.add(oldName);
            }
        }
        for (String name : toRemove) {
            tools.remove(name);
            mcpToolNames.remove(name);
            removed++;
            log.info("ToolRegistry.assemble_tool_pool: removing offline MCP '{}'", name);
        }

        // 2. 移除新 MCP tools 的所有同名旧 entry (替换语义) + register 新 MCP tools
        int added = 0;
        int skipped = 0;
        if (mcpTools != null) {
            for (Tool newTool : mcpTools) {
                if (newTool == null) continue;
                // [R32-#11] isEnabled=false 跳过: 与 CC tools.ts:325 行为对齐
                if (!newTool.isEnabled()) {
                    skipped++;
                    log.info("ToolRegistry.assemble_tool_pool: skipping disabled MCP '{}'",
                        newTool.name());
                    continue;
                }
                // [R32-#14] uniqBy builtin 覆盖 MCP: 对齐 CC tools.ts:357-365.
                // builtin 已经在 tools Map 中, 如果 MCP 同名则跳过 (builtin 优先).
                // 这是与上一版不同的语义 (旧版是 MCP 覆盖 builtin).
                String name = newTool.name();
                if (tools.containsKey(name) && !mcpToolNames.contains(name)) {
                    // builtin 已存在同名 → 跳过 MCP, builtin 优先
                    log.info("ToolRegistry.assemble_tool_pool: builtin overrides MCP '{}' (skipping MCP)", name);
                    skipped++;
                    continue;
                }
                boolean existed = tools.containsKey(name);
                if (existed) {
                    log.info("ToolRegistry.assemble_tool_pool: replacing MCP '{}'", name);
                }
                tools.put(name, newTool);
                mcpToolNames.add(name);
                if (!existed) added++;
            }
        }
        int after = tools.size();
        if (removed > 0 || added > 0 || skipped > 0) {
            log.info("ToolRegistry.assemble_tool_pool: pool {} -> {} (added {}, removed {}, skipped {})",
                before, after, added, removed, skipped);
        }
        return after;
    }

    /**
     * 查表分发。找不到时返回 {@link Optional#empty()}（调用方负责转 error result）。
     *
     * <p>P1.2: 支持按 {@code name} 或 {@link Tool#aliases()} 任一 alias 查表（CC
     * Tool.ts:355-360 findToolByName 语义, backward-compat 通道）。
     */
    public Optional<Tool> get(String name) {
        if (name == null) return Optional.empty();
        Tool t = tools.get(name);
        if (t != null) return Optional.of(t);
        // 查 alias 反向映射 (LLM 历史 transcript 老名兼容)
        t = aliasMap.get(name);
        return Optional.ofNullable(t);
    }

    /**
     * P1.2: 是否已注册 (按 name OR alias 查表).
     *
     * <p>对齐 CC {@code toolMatchesName} (CC Tool.ts:346-352) + {@code findToolByName}
     * (CC Tool.ts:355-360) 语义.
     */
    public boolean has(String name) {
        if (name == null) return false;
        return tools.containsKey(name) || aliasMap.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

    /**
     * 所有已注册且启用的工具（按注册顺序）。
     *
     * <p><b>[R32-#11]</b> 接入 {@link Tool#isEnabled()} 守卫：isEnabled=false 的工具
     * 不出现在结果中。对齐 CC {@code tools.ts:181 getToolsForDefaultPreset} 的
     * {@code tools.filter(tool => tool.isEnabled())} 行为。
     *
     * <p><b>注意</b>: 本方法 <b>不</b> 过滤 {@link com.nexusai.application.agent.tool.ToolNameConstants#SPECIAL_TOOLS}
     * (ListMcpResources / ReadMcpResource / SyntheticOutput), 因为内部 dispatch 工具
     * (如 ToolSearchTool) 需要访问它们. SPECIAL_TOOLS 过滤只在 LLM-facing API
     * ({@link #toOpenAiToolsArray} / {@link #getTools}) 中应用.
     *
     * <p>WHY：原实现直接返回所有已注册工具，导致运行时禁用的工具（如管理员临时关闭
     * Bash）仍会被 LLM 看到并尝试调用，破坏 disable 语义。
     */
    public List<Tool> all() {
        List<Tool> enabled = new ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            if (t.isEnabled()) enabled.add(t);
        }
        return Collections.unmodifiableList(enabled);
    }

    /**
     * 生成 OpenAI function calling 格式的 tools 数组 · 发给 LLM。
     *
     * <p><b>[R32-#11]</b> 接入 {@link Tool#isEnabled()} 守卫：isEnabled=false 的工具
     * 不出现在 LLM schema 中。对齐 CC {@code tools.ts:325 getTools} 的
     * {@code allowedTools.filter(t => t.isEnabled())} 行为。
     *
     * <p><b>description 来源（todo-write 对齐 CC api.ts:171）</b>：CC 在工具序列化时
     * {@code description: await tool.prompt({...})}——即 <b>prompt() 返回值作为 API 工具数组
     * 的 description 发给模型</b>；{@code tool.description()} 供 ToolSearch 等其他用途。
     * Java 对应：description 优先取 {@link Tool#prompt()}，未覆盖 prompt() 的工具
     * （默认返回 null）fallback 到 {@link Tool#description()}，行为不变。
     * <pre>
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "Bash",
     *       "description": "...",
     *       "parameters": { ...JSON Schema... }
     *     }
     *   },
     *   ...
     * ]
     * </pre>
     */
    /**
     * 构建 LLM 工具 schema 数组 · 无 defer_loading 发射（等价空集合重载，保留旧契约；
     * compact 直调方 ProductionForkedQuery:413 / StreamCompactSummary:391 走本签名，
     * 不参与 tool-search 过滤/发射 → 登记残留）。
     */
    public ArrayNode toOpenAiToolsArray() {
        return toOpenAiToolsArray(Set.of());
    }

    /**
     * 构建 LLM 工具 schema 数组 · 对齐 CC {@code toolToAPISchema}（api.ts:211-244）·
     * 对命中 {@code deferLoadingNames} 的工具在 wrapper 顶层写 {@code defer_loading: true}
     * （CC api.ts:223-225 {@code schema.defer_loading = true}，顶层 schema 语义，与
     * type/function 同层）。
     *
     * @param deferLoadingNames 本 turn 需 defer 的工具名集合（CC willDefer 语义，
     *                          claude.ts:1208-1209 + 1236-1243）；空集合 → 无发射
     */
    public ArrayNode toOpenAiToolsArray(Set<String> deferLoadingNames) {
        ArrayNode arr = JSON.createArrayNode();
        Set<String> deferNames = deferLoadingNames == null ? Set.of() : deferLoadingNames;
        for (Tool tool : tools.values()) {
            // [R32-#11] isEnabled 守卫: LLM schema 不暴露运行时禁用的工具
            if (!tool.isEnabled()) {
                log.debug("ToolRegistry.toOpenAiToolsArray: skipping disabled '{}'", tool.name());
                continue;
            }
            // [R32-#12] SPECIAL_TOOLS 过滤收窄（S04 B4）: 内部 dispatch 工具不暴露给 LLM
            // 对齐 CC tools.ts:301-307 specialTools 只过滤 builtin 基座（getAllBaseTools）；
            // ListMcpResourcesTool/ReadMcpResourceTool 为恒注册工具（决策 #65 @Component，
            //   对齐 CC tools.ts:245-246 getAllBaseTools 恒含），schema 期不再按名过滤 → 对 LLM 可见；
            // 主链 builtin 分区特例唯一 = SYNTHETIC_OUTPUT_TOOL_NAME。
            // H7-arch Phase 3: skipSpecialToolsFilter=true 时跳过（hook agent effectiveRegistry
            //   需暴露 StructuredOutput 给 LLM，对齐 CC execAgentHook.ts:93-105 手工构建 tools）
            if (!skipSpecialToolsFilter
                && com.nexusai.application.agent.tool.ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME.equals(tool.name())) {
                log.debug("ToolRegistry.toOpenAiToolsArray: skipping special '{}'", tool.name());
                continue;
            }
            ObjectNode wrapper = arr.addObject();
            wrapper.put("type", "function");
            // [H4] defer_loading 发射（CC api.ts:223-225）· wrapper 顶层（与 type/function 同层）。
            // 仅 when useToolSearch（willDefer 语义由 llmToolsArray 预先计算，claude.ts:1208-1209）。
            if (deferNames.contains(tool.name())) {
                wrapper.put("defer_loading", true);
                if (log.isDebugEnabled()) {
                    log.debug("ToolRegistry.toOpenAiToolsArray: tool '{}' 发射 defer_loading=true（CC api.ts:223-225）",
                        tool.name());
                }
            }
            ObjectNode fn = wrapper.putObject("function");
            fn.put("name", tool.name());
            // [todo-write 对齐 CC] api.ts:171 实证：CC 序列化工具时 description 取 prompt() 返回值；
            // tool.description() 供 ToolSearch 等其他用途。prompt() 非 null 优先，否则 fallback description()。
            String desc = tool.prompt();
            if (desc == null) {
                desc = tool.description();
            }
            fn.put("description", desc);
            // [G3] CC api.ts:157-160: inputJSONSchema in tool && tool.inputJSONSchema
            //   ? tool.inputJSONSchema : zodToJsonSchema(tool.inputSchema) — inputJSONSchema 优先。
            // Java 端 inputJSONSchema() 非 null 即声明（MCP/SyntheticOutput 直接 JSON Schema），
            // 否则回退 inputSchema()。
            JsonNode schema = tool.inputJSONSchema();
            if (schema == null) {
                schema = tool.inputSchema();
            }
            if (schema == null) {
                fn.putObject("parameters");
            } else {
                fn.set("parameters", schema);
            }
            // [G4] strict 意图层（CC api.ts:185-192 前两条件）：flag && tool.strict() → fn.strict=true。
            //   模型层门控（model != null + firstParty + 白名单）由 provider 侧判定
            //   （AnthropicSdkProvider.toSdkTool / OpenAiSdkProvider.toOpenAiSdkTool），本层只写模型无关意图。
            if (StructuredOutputsSupport.tenguToolPearEnabled() && tool.strict()) {
                fn.put("strict", true);
                if (log.isDebugEnabled()) {
                    log.debug("ToolRegistry.toOpenAiToolsArray: tool '{}' 标记 strict=true（flag && tool.strict() · CC api.ts:185-192）",
                        tool.name());
                }
            }
        }
        return arr;
    }

    /**
     * 处理一次工具调用（查表 + execute）。如果工具未注册，返回标准错误结果。
     *
     * <p>这是 {@link com.nexusai.application.agent.LlmAgentLoop} 在 turn 内的核心调用：
     * <pre>
     * for (ToolUseBlock call : assistantMessage.toolCalls()) {
     *     ToolResult result = registry.dispatch(call);
     *     messages.add(result);
     * }
     * </pre>
     */
    public ToolResult dispatch(ToolUseBlock call) {
        return dispatch(call, null);
    }

    /**
     * [R32-#9] filterToolsByDenyRules · 对齐 CC {@code tools.ts:262-269}.
     *
     * <p>过滤掉被 permission context 整工具 deny 的工具. 支持 MCP server-prefix
     * 规则 (e.g. "mcp__server1" 屏蔽该 server 全部工具) — 通过
     * {@link com.nexusai.application.agent.permission.check.RuleQuery#toolMatchesRule} 实现.
     *
     * <p>对齐 CC: <pre>
     * export function filterToolsByDenyRules<T>(
     *   tools: readonly T[],
     *   permissionContext: ToolPermissionContext
     * ): T[] {
     *   return tools.filter(tool => !getDenyRuleForTool(permissionContext, tool))
     * }
     * </pre>
     *
     * @param tools 候选工具列表 (传入 null/empty → 返回空 list)
     * @param permCtx 权限上下文 (null → 返回原 tools 列表, 不做过滤)
     * @return 过滤后的 tool 列表 (无 deny rule match 的工具)
     */
    public List<Tool> filterToolsByDenyRules(List<Tool> tools, ToolPermissionContext permCtx) {
        if (tools == null || tools.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        if (permCtx == null) {
            return new java.util.ArrayList<>(tools);
        }
        java.util.List<Tool> filtered = new java.util.ArrayList<>(tools.size());
        for (Tool tool : tools) {
            if (com.nexusai.application.agent.permission.check.RuleQuery.getDenyRuleForTool(permCtx, tool) == null) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /**
     * [R32-#8] getTools(permCtx) 4 分支决策树 · 对齐 CC {@code tools.ts:271-327}.
     *
     * <p>4 分支:
     * <ol>
     *   <li>{@code CLAUDE_CODE_SIMPLE=1} + {@code REPL} + coordinator → REPL + TaskStop + SendMessage</li>
     *   <li>{@code CLAUDE_CODE_SIMPLE=1} + {@code REPL} → 仅 REPL</li>
     *   <li>{@code CLAUDE_CODE_SIMPLE=1} + coordinator → Bash + Read + Edit + Agent + TaskStop + SendMessage</li>
     *   <li>默认 (主生产路径) → getAllBaseTools - specialTools → filterByDenyRules → (REPL?) filter REPL_ONLY → isEnabled</li>
     * </ol>
     *
     * <p>Java 简化: 不实现 CLAUDE_CODE_SIMPLE 路径 (Java 端无此 env).
     * 主生产路径对应 CC 分支 4.
     *
     * <p>[D session] REPL 分支: CC 在 REPL 模式开启时用
     * {@link com.nexusai.application.agent.tool.ToolNameConstants#REPL_ONLY_TOOLS}
     * 隐藏基础工具 (REPLTool/constants.ts:37-46). Java 端无 REPL 工具 (ant-only),
     * 该分支不实施, 常量集已按 CC 结构补全 (见 ToolNameConstants).
     *
     * @param permCtx 权限上下文 (可为 null — null 时不应用 deny rule 过滤)
     * @return 经 specialTools 过滤 + deny rule 过滤 + isEnabled 过滤后的 tool 列表
     */
    public List<Tool> getTools(ToolPermissionContext permCtx) {
        // 默认主生产路径: 所有已注册 builtin tool
        List<Tool> allTools = new ArrayList<>(this.tools.values());

        // 1) specialTools 过滤 (R32-#12)
        // H7-arch Phase 3: skipSpecialToolsFilter=true 时跳过（hook agent 需暴露 StructuredOutput）
        if (!skipSpecialToolsFilter) {
            allTools.removeIf(t -> com.nexusai.application.agent.tool.ToolNameConstants.SPECIAL_TOOLS.contains(t.name()));
        }

        // 2) deny rule 过滤 (R32-#9)
        if (permCtx != null) {
            allTools = filterToolsByDenyRules(allTools, permCtx);
        }

        // 3) isEnabled 过滤 (R32-#11)
        allTools.removeIf(t -> !t.isEnabled());

        return Collections.unmodifiableList(allTools);
    }

    /**
     * CC tools.ts:161 {@code TOOL_PRESETS = ['default']} — --tools 标志支持的预设.
     * Java 端唯一支持 'default'.
     */
    public static final java.util.List<String> TOOL_PRESETS = java.util.List.of("default");

    /**
     * [D session] parseToolPreset · 对齐 CC {@code tools.ts:165-171}.
     *
     * <p>大小写不敏感 (CC {@code preset.toLowerCase()}), 未识别预设返回 null
     * (CC 返回 {@code ToolPreset | null}).
     *
     * @param preset 预设名 (e.g. "default" / "Default")
     * @return 规范化后的预设名; 未识别返回 null
     */
    public static String parseToolPreset(String preset) {
        if (preset == null) return null;
        String presetString = preset.toLowerCase(java.util.Locale.ROOT);
        if (!TOOL_PRESETS.contains(presetString)) {
            log.warn("ToolRegistry.parseToolPreset: 未识别预设 '{}' (支持: {})", preset, TOOL_PRESETS);
            return null;
        }
        return presetString;
    }

    /**
     * [D session] getToolsForDefaultPreset · 对齐 CC {@code tools.ts:179-183}.
     *
     * <p>返回当前注册且 {@link Tool#isEnabled()} 的工具名列表.
     * CC 侧: {@code getAllBaseTools().filter(t => t.isEnabled()).map(t => t.name)} —
     * 不含 specialTools 剔除 (CC 此函数不做 specialTools 过滤, 与 getTools 不同).
     *
     * @return 启用工具名列表 (不可变)
     */
    public List<String> getToolsForDefaultPreset() {
        java.util.List<String> names = new java.util.ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            if (t.isEnabled()) names.add(t.name());
        }
        return java.util.Collections.unmodifiableList(names);
    }

    /**
     * [D session] getMergedTools · 对齐 CC {@code tools.ts:383-389}.
     *
     * <p>{@code [...getTools(permissionContext), ...mcpTools]} — 全量合并 builtin
     * (经 SPECIAL_TOOLS/deny/isEnabled 过滤) + 全部 MCP 工具. <b>不去重不排序</b>
     * ([IMP-C4 REQ-G3-2-2] 2-arg assembleToolPool(permCtx, mcpTools) 已删 — 生产 0 调用方;
     * LLM-facing pool 装配由 getTools + 显式 CC_TOOL_ORDER 承担).
     *
     * @param permCtx   权限上下文 (可为 null)
     * @param mcpTools  MCP 工具列表 (可为 null/empty)
     * @return builtin + MCP 全量合并 (不可变)
     */
    public List<Tool> getMergedTools(ToolPermissionContext permCtx, List<Tool> mcpTools) {
        List<Tool> builtInTools = getTools(permCtx);
        List<Tool> merged = new ArrayList<>(builtInTools.size() + (mcpTools == null ? 0 : mcpTools.size()));
        merged.addAll(builtInTools);
        if (mcpTools != null) {
            merged.addAll(mcpTools);
        }
        return java.util.Collections.unmodifiableList(merged);
    }

    /**
     * <b>[IMP-C4 REQ-G3-2-2] 2-arg assembleToolPool(permCtx, mcpTools) 已删除</b>（DC-TR-A3-1 关联）——
     * 该 Java 2-arg 形式生产 0 调用方（EV-A2-008），且其「整体 name 字典序排序」破坏 CC
     * assembleToolPool 的 builtin 前缀分区不变量（S-3）。CC 真源 assembleToolPool(permissionContext,
     * mcpTools)（tools.ts:345-367）由 React 层 useMergedTools/REPL 消费；Java LLM-facing pool 装配
     * 由 {@link #getTools(ToolPermissionContext)}（builtin 经 SPECIAL_TOOLS/deny/isEnabled 过滤）
     * + {@link #assembleToolPool(List)}（1-arg，生产 MCP 刷新唯一入口，J-8）承担，无需 2-arg 死 API。
     * 删除对象: 06-deletion-manifest（IMP-C4 增补）；验证: grep "assembleToolPool(ToolPermissionContext"
     * src/main 0 命中。
     */

    /**
     * 处理一次工具调用（含运行时上下文）· s02 [P1] CC Tool.ts:379-385 对齐。
     *
     * <p>新重载：传入 {@link ToolUseContext} 让工具能做 context-aware 决策。
     * 默认调用 {@code tool.execute(call, ctx)}（新接口），
     * 向后兼容：ctx=null 时工具 fallback 到 {@code execute(call)}（旧接口）。
     */
    public ToolResult dispatch(ToolUseBlock call, ToolUseContext ctx) {
        // B2 (OPD-09): 查表走 get(name) —— 主名后 alias 兜底 (CC Tool.ts:358 findToolByName),
        // 保护 LLM 历史 transcript 中旧 snake_case 名经 alias 仍可派发.
        Tool tool = get(call.name()).orElse(null);
        if (tool == null) {
            log.warn("ToolRegistry: unknown tool '{}' (id={})", call.name(), call.id());
            return ToolResult.error(call.id(), "No such tool available: " + call.name());
        }
        // [IMP-C4 REQ-G3-2-1] isEnabled 分发层守卫 · 对齐 CC tools.ts:325 getTools
        //   （isEnabled 过滤在可见集层）→ 关闭的工具不在可见集 → findToolByName 查不到 →
        //   StreamingToolExecutor.ts:91 `No such tool available`。Java 注册表保留全部已注册
        //   工具（LLM schema 已按 isEnabled 过滤），此处执行层补守卫：disabled 工具报
        //   "No such tool available"（与 unknown 同文案），不得真实执行。
        if (!tool.isEnabled()) {
            log.warn("ToolRegistry: tool '{}' (id={}) disabled → No such tool available", call.name(), call.id());
            return ToolResult.error(call.id(), "No such tool available: " + call.name());
        }
        try {
            log.info("ToolRegistry: dispatching '{}' (id={})", call.name(), call.id());
            AgentToolResult<?> rawResult = tool.execute(call, ctx);
            // [A1·退役 ExtendedToolResult] sealed permits 只剩 ToolResult, 不再解包 er.base()
            return (ToolResult<?>) rawResult;
        } catch (Exception e) {
            // Tool.execute 内部应该 catch 所有异常并返回 error result
            // 这里 catch 是兜底 —— 如果某个 tool 实现忘了 catch，至少不会挂掉 loop
            log.error("ToolRegistry: tool '{}' (id={}) threw unhandled exception",
                call.name(), call.id(), e);
            return ToolResult.error(call.id(), "Internal tool error: " + e.getMessage());
        }
    }
}

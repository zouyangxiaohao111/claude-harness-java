package com.nexusai.kb.agent;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.kb.app.prompt.KbPromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识库场景内置 Agent 定义 · 对齐源站 WeKnora {@code config/builtin_agents.yaml}。
 *
 * <h2>为什么单独一个类，不塞进 {@code BuiltInAgents}</h2>
 * {@link com.nexusai.application.agent.subagent.BuiltInAgents} 是 <b>CC 对齐</b>的注册表
 * （{@code general-purpose} / {@code Explore} / {@code Plan} / …），它的字段语义、工具名、
 * 人格载体都按 Claude Code 建模。知识库这 6 个 agent 来自另一个产品（WeKnora），
 * 语义不同（{@code agent_mode} / {@code agent_type} / kb 专属工具集），混在一起会让
 * 「CC 对齐 vs 源站对齐」两条线互相污染。
 *
 * <h2>人格载体：外置 md（用户 2026-11-09 裁定）</h2>
 * 6 份人格全部放 {@code backend/src/main/resources/kb/agent/*.md}，由
 * {@link KbPromptLoader#cachedFrom} 读取 —— <b>缺失即抛，⛔ 不回退空串</b>。
 * 空 system prompt 会让模型自由发挥且整条链零报错，属于静默缺陷。
 *
 * <p>⚠️ 现有 6 个 CC 对齐内置 agent 的人格<b>仍是 Java 内联常量</b>，本轮不迁移。
 *
 * <h2>对象范围（用户 2026-11-10 裁定）</h2>
 * 源站 yaml 共 7 个 entry，本仓做 <b>6 个</b>：
 * <ul>
 *   <li>跳过 {@code builtin-skill-installer}（本仓无技能安装能力）</li>
 *   <li>{@code builtin-weknora-assistant} → 改名 {@code builtin-assistant}（界面不出现 WeKnora）</li>
 * </ul>
 *
 * <h2>工具名映射（用户裁定：用本仓真名）</h2>
 * <pre>
 *   源站 ask_user     → AskUserQuestion
 *   源站 todo_write   → TodoWrite
 *   源站 web_search   → WebSearch    （走 web_search_enabled 开关，不进 allowed_tools）
 *   源站 web_fetch    → WebFetch
 *   其余 kb/wiki 工具 → 同名
 * </pre>
 *
 * <h2>agentMode / kbAgentType 的取舍</h2>
 * <ul>
 *   <li>{@code agentMode} = <b>真实行为分支</b>（quick-answer 走 RAG 流水线，smart-reasoning 走 ReAct）</li>
 *   <li>{@code kbAgentType} = <b>只存不消费</b>（源站运行时零引用，只是编辑器表单预填种子；
 *       本仓不对齐 {@code /platform/agents} 页面，故暂无消费点）</li>
 * </ul>
 *
 * @see com.nexusai.kb.app.prompt.KbPromptLoader#AGENT_RESOURCE_BASE
 */
public final class KbBuiltInAgentDefs {

    private static final Logger log = LoggerFactory.getLogger(KbBuiltInAgentDefs.class);

    // ── agent id（沿用源站，仅 assistant 改名）──────────────────────────────────

    public static final String QUICK_ANSWER = "builtin-quick-answer";
    public static final String SMART_REASONING = "builtin-smart-reasoning";
    public static final String DATA_ANALYST = "builtin-data-analyst";
    public static final String WIKI_RESEARCHER = "builtin-wiki-researcher";
    public static final String WIKI_FIXER = "builtin-wiki-fixer";
    public static final String ASSISTANT = "builtin-assistant";

    // ── agentMode（源站 AgentMode 常量）─────────────────────────────────────────

    public static final String MODE_QUICK_ANSWER = "quick-answer";
    public static final String MODE_SMART_REASONING = "smart-reasoning";

    // ── kbAgentType（源站 AgentType 常量，只存不消费）───────────────────────────

    public static final String TYPE_RAG_QA = "rag-qa";
    public static final String TYPE_WIKI_QA = "wiki-qa";
    public static final String TYPE_DATA_ANALYSIS = "data-analysis";
    public static final String TYPE_CUSTOM = "custom";

    // ── 工具清单（源站 allowed_tools 逐项翻译成本仓真名）────────────────────────

    /** smart-reasoning 的 5 个工具（源站 :77-82）。 */
    static final List<String> TOOLS_SMART_REASONING = List.of(
        "search_knowledge",
        "read_document",
        "list_documents",
        "query_knowledge_graph",
        "AskUserQuestion",
        // ⭐ 读会话临时附件（解析后的 .md，见 KbChatAttachmentService）——
        //    源站清单里没有它（agent 轮用 read_file 读 attachment://），本仓用同名 Read 工具。
        "Read"
    );

    /** data-analyst 的 3 个工具（源站 :127-130）。 */
    static final List<String> TOOLS_DATA_ANALYST = List.of(
        "data_schema",
        "data_analysis",
        "AskUserQuestion",
        // ⭐ 读会话临时附件（解析后的 .md，见 KbChatAttachmentService）——
        //    源站清单里没有它（agent 轮用 read_file 读 attachment://），本仓用同名 Read 工具。
        "Read"
    );

    /** wiki-researcher 的 4 个工具（源站 :169-173）。 */
    static final List<String> TOOLS_WIKI_RESEARCHER = List.of(
        "wiki_search",
        "wiki_read_page",
        "read_document",
        "wiki_flag_issue",
        // ⭐ 读会话临时附件（解析后的 .md，见 KbChatAttachmentService）——
        //    源站清单里没有它（agent 轮用 read_file 读 attachment://），本仓用同名 Read 工具。
        "Read"
    );

    /** wiki-fixer 的 9 个工具（源站 :213-222）。 */
    static final List<String> TOOLS_WIKI_FIXER = List.of(
        "wiki_search",
        "wiki_read_page",
        "read_document",
        "wiki_write_page",
        "wiki_replace_text",
        "wiki_rename_page",
        "wiki_delete_page",
        "wiki_read_issue",
        "wiki_update_issue",
        // ⭐ 读会话临时附件（解析后的 .md，见 KbChatAttachmentService）——
        //    源站清单里没有它（agent 轮用 read_file 读 attachment://），本仓用同名 Read 工具。
        "Read"
    );

    /**
     * assistant 的 14 个工具（源站 :311-325）。
     *
     * <p>⚠️ 源站 {@code web_search} / {@code web_fetch} <b>不在此列</b> —— yaml :326-328 明写
     * 「They follow this switch rather than allowed_tools」：联网能力由
     * {@code web_search_enabled} 开关控制，不进白名单。
     */
    static final List<String> TOOLS_ASSISTANT = List.of(
        "AskUserQuestion",
        "TodoWrite",
        "search_knowledge",
        "read_document",
        "list_documents",
        "wiki_search",
        "wiki_read_page",
        "wiki_write_page",
        "wiki_replace_text",
        "wiki_rename_page",
        "wiki_delete_page",
        "wiki_flag_issue",
        "wiki_read_issue",
        "wiki_update_issue",
        // ⭐ 读会话临时附件（解析后的 .md，见 KbChatAttachmentService）——
        //    源站清单里没有它（agent 轮用 read_file 读 attachment://），本仓用同名 Read 工具。
        "Read"
    );

    private KbBuiltInAgentDefs() {
    }

    /**
     * 6 个知识库内置 Agent 的全部定义 · 供
     * {@code BuiltInAgents.getBuiltInAgents()} 追加进返回列表。
     *
     * <p>每次调用新建 record 实例（不可变），人格正文由 loader 缓存，不会重复读盘。
     *
     * @return 6 个 agent 定义（顺序与源站 yaml 一致）
     */
    public static List<AgentDefinition> all() {
        return List.of(
            quickAnswer(),
            smartReasoning(),
            dataAnalyst(),
            wikiResearcher(),
            wikiFixer(),
            assistant()
        );
    }

    /**
     * 启动自检：把 6 个 agent 的<b>实际注册内容</b>打进日志，并立即验证人格 md 可加载。
     *
     * <p><b>为什么要在启动时打印工具清单</b>：工具白名单写错（拼错名字、用了不存在的工具）
     * 在运行期的表现是「模型不去用那个工具」，而<b>不会报任何错</b> ——
     * 属于最难排查的一类。启动时打印一遍，人与机器都能立刻对比源站配置。
     *
     * <p><b>为什么启动时就加载人格</b>：{@link KbPromptLoader} 是「缺失即抛」语义，
     * 但 {@code getSystemPrompt} 只有在真正跑对话时才会被调用 ——
     * 那时用户已经在等了。启动期探一次，把「忘记打包 md」这种部署问题挡在启动阶段。
     *
     * <p>⚠️ 本方法<b>不做</b>任何失败兜底：人格缺失就该启动失败（这是设计意图）。
     *
     * @return 自检通过时的 agent 数量
     */
    public static int selfCheckOnStartup() {
        List<AgentDefinition> defs = all();
        for (AgentDefinition def : defs) {
            // 触发一次加载：缺失 / 为空 ⇒ 直接抛
            String prompt = def.getSystemPrompt(null, List.of());
            String tools = def.tools().map(t -> String.join(",", t)).orElse("(none)");
            log.info("[KB内置Agent] 注册 {} mode={} type={} 工具数={} 工具=[{}] 人格={}字符",
                def.agentType(),
                def.agentMode().orElse("-"),
                def.kbAgentType().orElse("-"),
                def.tools().map(List::size).orElse(0),
                tools,
                prompt.length());
        }
        log.info("[KB内置Agent] 启动自检完成 共 {} 个 agent", defs.size());
        return defs.size();
    }

    /**
     * 快速问答 · 源站 {@code builtin-quick-answer}。
     *
     * <p><b>零工具</b>：源站按 {@code agent_mode: quick-answer} 走固定 RAG 流水线，
     * 由本仓 {@code KbQaPipeline} 承载（阶段 E 接线）。
     */
    public static AgentDefinition quickAnswer() {
        return base(QUICK_ANSWER, "知识库快速问答：基于文档分块的检索式问答，不调用工具。",
                MODE_QUICK_ANSWER, null, List.of())
            .build();
    }

    /** 智能推理 · 源站 {@code builtin-smart-reasoning}，ReAct + 4 个 kb 工具 + AskUserQuestion。 */
    public static AgentDefinition smartReasoning() {
        return base(SMART_REASONING, "知识库智能推理：多步思考并调用知识库检索工具，适合需要综合多份资料的问题。",
                MODE_SMART_REASONING, TYPE_RAG_QA, TOOLS_SMART_REASONING)
            .build();
    }

    /** 数据分析师 · 源站 {@code builtin-data-analyst}，DuckDB 内存库 + SQL。 */
    public static AgentDefinition dataAnalyst() {
        return base(DATA_ANALYST, "知识库数据分析：对 CSV / Excel 表格类资料做 SQL 查询与统计。",
                MODE_SMART_REASONING, TYPE_DATA_ANALYSIS, TOOLS_DATA_ANALYST)
            .build();
    }

    /** 维基问答 · 源站 {@code builtin-wiki-researcher}，只读 wiki。 */
    public static AgentDefinition wikiResearcher() {
        return base(WIKI_RESEARCHER, "知识库维基问答：在 Wiki 页面之间导航并回答，只读不写。",
                MODE_SMART_REASONING, TYPE_WIKI_QA, TOOLS_WIKI_RESEARCHER)
            .build();
    }

    /** 维基修订 · 源站 {@code builtin-wiki-fixer}，可写 wiki。 */
    public static AgentDefinition wikiFixer() {
        return base(WIKI_FIXER, "知识库维基修订：整理 Wiki 页面结构、修正内容与链接，可写操作。",
                MODE_SMART_REASONING, TYPE_CUSTOM, TOOLS_WIKI_FIXER)
            .build();
    }

    /** 知识库助手 · 源站 {@code builtin-weknora-assistant}（本仓改名去 WeKnora），14 个工具。 */
    public static AgentDefinition assistant() {
        return base(ASSISTANT, "知识库助手：全面管理知识库、文档与 Wiki，可多步调用工具完成任务。",
                MODE_SMART_REASONING, TYPE_CUSTOM, TOOLS_ASSISTANT)
            .build();
    }

    /**
     * 公共构造：模式 + 预设类别 + 工具 + 外置人格。
     *
     * <p>人格通过 {@link KbPromptLoader#cachedFrom} 在读表时加载 —— <b>缺文件即抛</b>。
     * ⚠️ 这是<b>延迟加载</b>（不是类静态初始化），因为 {@code all()} 每次调用都会读；
     * 若改成静态常量，{@code class} 初始化早于 Spring 上下文就绪，出错时丢失资源路径的诊断信息。
     *
     * @param agentType       agent id（注册表主键）
     * @param whenToUse       一句话用途（给调度方选 Agent 用）
     * @param agentMode       quick-answer / smart-reasoning
     * @param kbAgentType     预设类别；quick-answer 传 {@code null}（源站该模式下忽略）
     * @param tools           工具白名单；空列表 = 不调工具
     */
    private static AgentDefinition.BuiltInAgentDefinition.Builder base(
            String agentType, String whenToUse,
            String agentMode, String kbAgentType, List<String> tools) {
        String mdFile = agentType + ".md";
        AgentDefinition.BuiltInAgentDefinition.Builder b =
            AgentDefinition.BuiltInAgentDefinition.builder(
                agentType,
                whenToUse,
                (modelId, dirs) -> KbPromptLoader.cachedFrom(KbPromptLoader.AGENT_RESOURCE_BASE, mdFile))
            .agentMode(agentMode)
            .tools(tools.isEmpty() ? List.of() : tools);
        if (kbAgentType != null) {
            b.kbAgentType(kbAgentType);
        }
        if (log.isDebugEnabled()) {
            log.debug("[KB内置Agent] 构造 {} mode={} type={} 工具数={} 人格=kb/agent/{}",
                agentType, agentMode, kbAgentType, tools.size(), mdFile);
        }
        return b;
    }
}

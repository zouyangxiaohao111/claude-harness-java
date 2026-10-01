package com.nexusai.apis.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.context.ContextAnalyzeService;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextAnalyzeResult;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextCategory;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillFrontmatterDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
import com.nexusai.infra.llm.TokenSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /context analyze web 端点 · RES-R5（09 §六 R5 用户拍板：countSystemTokens 接入
 * /context analyze web 接口形式）+ RES-R5-2（09 §十一 R5-2：补 memory/tools 计数段）。
 *
 * <p>为重建纯能力 {@link com.nexusai.application.agent.prompt.SystemPromptTokenCounter}
 * 提供可验证消费方：构建 effectiveSystemPrompt（CC analyzeContextUsage D1 段，
 * analyzeContext.ts:938-947）→ system/memory/tools 三计数段（D3 段，:950-983）→ 返回
 * {@code {systemPromptTokens, systemPromptSections, claudeMdTokens, memoryFiles,
 * builtInToolTokens, mcpToolTokens}}。
 *
 * <p>对齐 CC {@code context-noninteractive.ts:61-76 collectContextData}：analyzeContextUsage
 * 只读 {@code options.{customSystemPrompt,appendSystemPrompt}}（:68-72）——web 端点无 AgentState，
 * custom/append 从请求体透传（09 §九 RES-R5 登记通道）。
 *
 * <p>POST /api/v1/context/analyze
 * <pre>
 * Request:  { "customSystemPrompt": "..." (optional), "appendSystemPrompt": "..." (optional) }
 * Response: { "systemPromptTokens": int|null,        // null = 算不出来
 *             "systemPromptTokensSource": "api"|"estimate"|"unavailable",
 *             "systemPromptSections": [{"name": "...", "tokens": int|null, "tokenSource": "..."}],
 *             "claudeMdTokens": int|null,
 *             "claudeMdTokensSource": "api"|"estimate"|"unavailable",
 *             "memoryFiles": [{"path": "...", "type": "...", "tokens": int|null, "tokenSource": "..."}],
 *             "builtInToolTokens": int|null,
 *             "builtInToolTokensSource": "api"|"estimate"|"unavailable",
 *             "mcpToolTokens": int|null,
 *             "mcpToolTokensSource": "api"|"estimate"|"unavailable",
 *             "skills": {"totalSkills": int, "includedSkills": int, "tokens": int|null,
 *                        "tokensSource": "estimate", "skillFrontmatter": [...]},
 *             "categories": [{"name": "...", "tokens": int|null, "color": "...", "tokenSource": "...",
 *                             "unavailableCause": "provider"|"local"|"localEstimate"|"derived"}]
 *                            //  unavailableCause 仅在该行 tokens=null 时下发（否则整个键省略）：
 *                            //  该行「算不出来」的成因，面板据此选悬停文案 —— provider=提供商侧
 *                            //  （服务端计数接口缺失/失败，实际用的是 anthropic 客户端时）；
 *                            //  local=本地读不到技能清单；localEstimate=本地估算出错（非 anthropic
 *                            //  提供商走本地估算，其失败与提供商无关）；derived=扣减/合成行，不归因单侧。
 *                            //  ⛔ 成因与显示名解耦，前端不得按 name 反推（见 ContextAnalyzeService）
 * </pre>
 * 每个 token 数值都带来源标注（四态详见 {@code ContextAnalyzeResponse} javadoc）。
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextAnalyzeController {

    private static final Logger log = LoggerFactory.getLogger(ContextAnalyzeController.class);

    @Autowired private ContextAnalyzeService contextAnalyzeService;

    @PostMapping(value = "/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public ContextAnalyzeResponse analyze(@RequestBody(required = false) ContextAnalyzeRequest req) {
        String customSystemPrompt = req == null ? null : req.customSystemPrompt();
        String appendSystemPrompt = req == null ? null : req.appendSystemPrompt();
        if (log.isInfoEnabled()) {
            log.info("[ContextAnalyzeController] 收到 /context analyze 请求: custom={}, append={}",
                customSystemPrompt != null, appendSystemPrompt != null);
        }
        ContextAnalyzeResult result = contextAnalyzeService.analyze(customSystemPrompt, appendSystemPrompt);
        // FIX-B2 拍板#4（总汇 §6.5 NG-4）：REST 补 skills 对象 · CC analyzeContext.ts:1368-1376
        //   skills: skillFrontmatterTokens > 0 ? {totalSkills, includedSkills, tokens, skillFrontmatter} : undefined
        //   （CC :601-602 includedSkills === totalSkills === skills.length；:1373 tokens = skillFrontmatterTokens reduce 和）
        // [四态标注 · 产出策略重定] 产出条件由「>0」放宽为「有正计数 或 算不出来」：不可用时也要出现，
        //   前端才有地方渲染「— 不可用」；仅「确切的 0」（真没技能/空原料）才省略键（保留 CC :1369
        //   undefined 的可观测形态）。判据 = tokens 是否为 null（null=不知道，要出现；0=没有，不出现）。
        Integer skillTokens = result.skill().skillFrontmatterTokens();
        //   [四态 · 技能加载失败] service 侧失败时返回 {totalSkills=null, tokens=null, UNAVAILABLE}
        //   → 此处原样透传：totalSkills/includedSkills 也置 null，面板才不会出现
        //   「技能数 0 / 0」+「— 不可用」的自相矛盾（0 说「没有」，角标说「不知道」）。
        SkillInfoView skillsView = (skillTokens != null && skillTokens <= 0)
            ? null
            : new SkillInfoView(
                result.skill().totalSkills(),
                result.skill().totalSkills(),
                skillTokens,
                result.skill().skillFrontmatterTokensSource(),
                result.skill().skillFrontmatter());
        // OPD-CM5-F-13（A15 展示数据段）：categories 展示分类（IMP-F2-2 已在 service 计算，
        //   CC analyzeContext.ts:1007-1087，含 'System tools' 扣减值；名称已本地化为中文，
        //   有意偏离见 ContextAnalyzeService#buildCategories）→ REST 透传
        List<ContextCategory> categories = result.categories();
        if (log.isInfoEnabled()) {
            log.info("[ContextAnalyzeController] /context analyze 完成: systemPromptTokens={}, claudeMdTokens={}, "
                    + "builtInToolTokens={}, mcpToolTokens={}, skillFrontmatterTokens={}, categories={}",
                result.system().systemPromptTokens(), result.memory().claudeMdTokens(),
                result.tools().builtInToolTokens(), result.tools().mcpToolTokens(),
                skillTokens, categories.size());
        }
        return new ContextAnalyzeResponse(
            result.system().systemPromptTokens(),
            result.system().systemPromptTokensSource(),
            result.system().systemPromptSections(),
            result.memory().claudeMdTokens(),
            result.memory().claudeMdTokensSource(),
            result.memory().memoryFiles(),
            result.tools().builtInToolTokens(),
            result.tools().builtInToolTokensSource(),
            result.tools().mcpToolTokens(),
            result.tools().mcpToolTokensSource(),
            skillsView,
            categories);
    }

    /**
     * 分析请求 · CC original: options.{customSystemPrompt,appendSystemPrompt}
     * （context-noninteractive.ts:68-72，两字段均 optional）。
     *
     * @param customSystemPrompt 自定义系统提示（非空替换 default，systemPrompt.ts:118-119）
     * @param appendSystemPrompt 追加系统提示（恒末尾，systemPrompt.ts:121）
     */
    public record ContextAnalyzeRequest(String customSystemPrompt, String appendSystemPrompt) {}

    /**
     * 分析响应 · CC original: analyzeContextUsage 各计数段结果
     * （analyzeContext.ts:950-983 + 分类 :1007-1047）。
     *
     * <p><b>[四态标注]</b> 每个 token 数值都配一个 {@code *Source} 字段
     * （{@link TokenSource}，wire 小写 {@code api|estimate|unavailable}），四态在 wire 上的区分：
     * <pre>
     *   (a1) 服务端真实计数 → tokens=非空, *Source="api"
     *   (a2)(a3) 本地估算   → tokens=非空, *Source="estimate"
     *   (b)  算不出来       → tokens=null, *Source="unavailable"（面板显示「— 不可用」）
     *   (c)  真 0           → tokens=0,    *Source="api"|"estimate"（面板显示 0，无角标）
     * </pre>
     * ⛔ 不得用 0 兼任「零」与「未知」—— 这正是本次要修的根。可空数值字段显式声明
     * {@code @JsonInclude(ALWAYS)}，保证 {@code null} 一定出现在 JSON 里（同级的 {@code *Source}
     * 恒非 null，两条独立线索都指向「算不出来」）。
     *
     * @param systemPromptTokens       系统提示总 token（含 systemContext 非空条目）；<b>null = 算不出来</b>
     * @param systemPromptTokensSource 该合计的来源
     * @param systemPromptSections     逐 section 明细（boundary/空串已过滤）
     * @param claudeMdTokens           memory 段总 token（逐文件求和，analyzeContext.ts:351-357）；<b>null = 算不出来</b>
     * @param claudeMdTokensSource     该合计的来源
     * @param memoryFiles              memory 文件明细（CC original: memoryFileDetails，:353-357）
     * @param builtInToolTokens        built-in 工具 schema 计数（CC original: builtInToolTokens，:501-514）；<b>null = 算不出来</b>
     * @param builtInToolTokensSource  该数值来源
     * @param mcpToolTokens            MCP 工具 schema 计数（CC original: mcpToolTokens，:722-725）；<b>null = 算不出来</b>
     * @param mcpToolTokensSource      该数值来源
     * @param skills                   [FIX-B2 拍板#4] skill 段对象（CC original: skills，analyzeContext.ts:1368-1376；
     *                                 [四态] 确切的 0 → null（@JsonInclude(NON_NULL) 省略键，对齐 CC :1369
     *                                 undefined）；算不出来（null）→ 仍产出，前端才能标「—」；
     *                                 技能加载失败 → {@code tokens=null} 且 {@code totalSkills/includedSkills=null}）
     * @param categories               [OPD-CM5-F-13 A15] 展示分类段（CC original: categories，analyzeContext.ts:1344
     *                                 = :1007-1087 service {@link ContextAnalyzeService} 计算透传；
     *                                 Java 产出 系统提示词/内置工具（不含技能）/MCP 工具/记忆文件/技能
     *                                 （<b>中文</b>，本地化偏离 CC 英文名，见 service buildCategories）
     *                                 可计数子集，Messages/Free space/buffer 因无消息输入与无 contextWindow
     *                                 归 N/A；service 返回非 null 列表 → 空原料空数组恒序列化，键恒存在）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextAnalyzeResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer systemPromptTokens,
        TokenSource systemPromptTokensSource,
        List<SystemPromptSectionDetail> systemPromptSections,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer claudeMdTokens,
        TokenSource claudeMdTokensSource,
        List<MemoryFileDetail> memoryFiles,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer builtInToolTokens,
        TokenSource builtInToolTokensSource,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer mcpToolTokens,
        TokenSource mcpToolTokensSource,
        SkillInfoView skills,
        List<ContextCategory> categories
    ) {}

    /**
     * skill 段 REST 视图 · CC original: ContextData.skills（analyzeContext.ts:1368-1376
     * {@code {totalSkills, includedSkills, tokens, skillFrontmatter}}）。
     *
     * @param totalSkills      技能总数（CC original: totalSkills = skills.length，:1371）；
     *                         <b>null = 不知道</b>（技能加载失败，见 ContextAnalyzeService#countSkillTokens）——
     *                         ⛔ 不写 0：否则面板会同时显示「技能数 0 / 0」与「— 不可用」（自相矛盾）
     * @param includedSkills   技能包含数（CC original: includedSkills === totalSkills === skills.length，:601-602/:1372）；
     *                         <b>null = 不知道</b>（与 totalSkills 同生共死）
     * @param tokens           技能 frontmatter token 汇总（CC original: tokens = skillFrontmatterTokens reduce 和，:994-997/:1373）；
     *                         <b>null = 算不出来</b>
     * @param tokensSource     该汇总来源（正常恒 {@link TokenSource#ESTIMATE}：本地 round(len/4) 粗估，与提供商无关；
     *                         技能加载失败时 {@link TokenSource#UNAVAILABLE}）；
     *                         ⚠️ 与下方 {@code skillFrontmatter[].source}（<b>技能来源</b> userSettings/plugin）不是一回事
     * @param skillFrontmatter 逐技能明细（CC original: skillFrontmatter = skillInfo.skillFrontmatter，:1374）
     */
    public record SkillInfoView(
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer totalSkills,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer includedSkills,
        @JsonInclude(JsonInclude.Include.ALWAYS) Integer tokens,
        TokenSource tokensSource,
        List<SkillFrontmatterDetail> skillFrontmatter
    ) {}

}

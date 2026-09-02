package com.nexusai.apis.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.context.ContextAnalyzeService;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextAnalyzeResult;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextCategory;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillFrontmatterDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
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
 * Response: { "systemPromptTokens": int,
 *             "systemPromptSections": [{"name": "...", "tokens": int}],
 *             "claudeMdTokens": int,
 *             "memoryFiles": [{"path": "...", "type": "...", "tokens": int}],
 *             "builtInToolTokens": int,
 *             "mcpToolTokens": int,
 *             "categories": [{"name": "...", "tokens": int, "color": "..."}] }
 * </pre>
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
        SkillInfoView skillsView = result.skill().skillFrontmatterTokens() > 0
            ? new SkillInfoView(
                result.skill().totalSkills(),
                result.skill().totalSkills(),
                result.skill().skillFrontmatterTokens(),
                result.skill().skillFrontmatter())
            : null;
        // OPD-CM5-F-13（A15 展示数据段）：categories 展示分类（IMP-F2-2 已在 service 计算，
        //   CC analyzeContext.ts:1007-1087，含 'System tools' 扣减值）→ REST 透传
        List<ContextCategory> categories = result.categories();
        if (log.isInfoEnabled()) {
            log.info("[ContextAnalyzeController] /context analyze 完成: systemPromptTokens={}, claudeMdTokens={}, "
                    + "builtInToolTokens={}, mcpToolTokens={}, skillFrontmatterTokens={}, categories={}",
                result.system().systemPromptTokens(), result.memory().claudeMdTokens(),
                result.tools().builtInToolTokens(), result.tools().mcpToolTokens(),
                result.skill().skillFrontmatterTokens(), categories.size());
        }
        return new ContextAnalyzeResponse(
            result.system().systemPromptTokens(),
            result.system().systemPromptSections(),
            result.memory().claudeMdTokens(),
            result.memory().memoryFiles(),
            result.tools().builtInToolTokens(),
            result.tools().mcpToolTokens(),
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
     * @param systemPromptTokens   系统提示总 token（含 systemContext 非空条目）
     * @param systemPromptSections 逐 section 明细（boundary/空串已过滤）
     * @param claudeMdTokens       memory 段总 token（逐文件求和，analyzeContext.ts:351-357）
     * @param memoryFiles          memory 文件明细（CC original: memoryFileDetails，:353-357）
     * @param builtInToolTokens    built-in 工具 schema 计数（CC original: builtInToolTokens，:501-514）
     * @param mcpToolTokens        MCP 工具 schema 计数（CC original: mcpToolTokens，:722-725）
     * @param skills               [FIX-B2 拍板#4] skill 段对象（CC original: skills，analyzeContext.ts:1368-1376；
     *                             skillFrontmatterTokens==0 → null，@JsonInclude(NON_NULL) 省略键，对齐 CC :1369 undefined）
     * @param categories           [OPD-CM5-F-13 A15] 展示分类段（CC original: categories，analyzeContext.ts:1344
     *                             = :1007-1087 service {@link ContextAnalyzeService} 计算透传；
     *                             Java 产出 System prompt/System tools/MCP tools/Memory files/Skills
     *                             可计数子集，Messages/Free space/buffer 因无消息输入与无 contextWindow
     *                             归 N/A；service 返回非 null 列表 → 空原料空数组恒序列化，键恒存在）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextAnalyzeResponse(
        int systemPromptTokens,
        List<SystemPromptSectionDetail> systemPromptSections,
        int claudeMdTokens,
        List<MemoryFileDetail> memoryFiles,
        int builtInToolTokens,
        int mcpToolTokens,
        SkillInfoView skills,
        List<ContextCategory> categories
    ) {}

    /**
     * skill 段 REST 视图 · CC original: ContextData.skills（analyzeContext.ts:1368-1376
     * {@code {totalSkills, includedSkills, tokens, skillFrontmatter}}）。
     *
     * @param totalSkills      技能总数（CC original: totalSkills = skills.length，:1371）
     * @param includedSkills   技能包含数（CC original: includedSkills === totalSkills === skills.length，:601-602/:1372）
     * @param tokens           技能 frontmatter token 汇总（CC original: tokens = skillFrontmatterTokens reduce 和，:994-997/:1373）
     * @param skillFrontmatter 逐技能明细（CC original: skillFrontmatter = skillInfo.skillFrontmatter，:1374）
     */
    public record SkillInfoView(
        int totalSkills,
        int includedSkills,
        int tokens,
        List<SkillFrontmatterDetail> skillFrontmatter
    ) {}

}

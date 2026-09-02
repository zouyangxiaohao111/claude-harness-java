package com.nexusai.apis.context;

import com.nexusai.application.agent.context.ContextAnalyzeService;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextAnalyzeResult;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextCategory;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryTokenCounts;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillFrontmatterDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillTokenCounts;
import com.nexusai.application.agent.context.ContextAnalyzeService.ToolTokenCounts;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemTokenCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [RES-R5] {@link ContextAnalyzeController} 意图测试 · /context analyze web 端点
 * （对齐 CC analyzeContextUsage 的 web 消费形式，09 §六 R5 用户拍板）。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：SystemPromptTokenCounter 重建为"纯能力无消费方"
 * （09 §五③）。本测试钉死 web 端点契约：
 * <ol>
 *   <li><b>POST /api/v1/context/analyze 返回 {systemPromptTokens, systemPromptSections}</b>——
 *       React 前端消费结构（对齐 context-noninteractive.ts:61-76 collectContextData）。</li>
 *   <li><b>custom/append 从请求体透传</b>——CC analyzeContextUsage 只读
 *       options.{customSystemPrompt,appendSystemPrompt}（context-noninteractive.ts:68-72），
 *       web 端点无 AgentState → 请求参数通道（09 §九 RES-R5）。</li>
 * </ol>
 */
class ContextAnalyzeControllerTest {

    private ContextAnalyzeController controller;
    private ContextAnalyzeService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ContextAnalyzeController();
        service = mock(ContextAnalyzeService.class);
        ReflectionTestUtils.setField(controller, "contextAnalyzeService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/context/analyze → 200 + system/memory/tools 计数段结构")
    void analyze_returnsSystemPromptTokenStructure() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, List.of(
                new SystemPromptSectionDetail("System", 8),
                new SystemPromptSectionDetail("gitStatus", 4))),
            new MemoryTokenCounts(9, List.of(new MemoryFileDetail(".claude/CLAUDE.md", "claude", 9))),
            new ToolTokenCounts(25, 40),
            new SkillTokenCounts(0, 0, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.systemPromptTokens").value(12))
            .andExpect(jsonPath("$.systemPromptSections[0].name").value("System"))
            .andExpect(jsonPath("$.systemPromptSections[0].tokens").value(8))
            .andExpect(jsonPath("$.systemPromptSections[1].name").value("gitStatus"))
            // RES-R5-2: memory/tools 计数段暴露
            .andExpect(jsonPath("$.claudeMdTokens").value(9))
            .andExpect(jsonPath("$.memoryFiles[0].path").value(".claude/CLAUDE.md"))
            .andExpect(jsonPath("$.memoryFiles[0].tokens").value(9))
            .andExpect(jsonPath("$.builtInToolTokens").value(25))
            .andExpect(jsonPath("$.mcpToolTokens").value(40));
    }

    @Test
    @DisplayName("custom/append 请求体透传 service（对齐 CC options.{customSystemPrompt,appendSystemPrompt}）")
    void analyze_passesCustomAndAppendThrough() throws Exception {
        when(service.analyze(eq("my custom"), eq("my append")))
            .thenReturn(new ContextAnalyzeResult(
                new SystemTokenCounts(0, List.of()),
                new MemoryTokenCounts(0, List.of()),
                new ToolTokenCounts(0, 0),
                new SkillTokenCounts(0, 0, List.of()),
                List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON)
                .content("{\"customSystemPrompt\":\"my custom\",\"appendSystemPrompt\":\"my append\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.systemPromptTokens").value(0))
            .andExpect(jsonPath("$.claudeMdTokens").value(0))
            .andExpect(jsonPath("$.builtInToolTokens").value(0))
            .andExpect(jsonPath("$.mcpToolTokens").value(0));
    }

    /**
     * [FIX-B2 拍板#4 / NG-4] REST 响应补 skills 对象 · CC original:
     * analyzeContext.ts:1368-1376 {@code skills: {totalSkills, includedSkills, tokens, skillFrontmatter}}
     * （skillFrontmatterTokens > 0 时返回）。
     *
     * <p><b>WHY（规则九）</b>：旧响应 6 字段无 skill 段（NG-4），skill frontmatter token 计量在生产
     * web 端不可观测；拍板#4 要求 REST 补 skills 对象。本测试钉死响应形状 = CC
     * {@code {totalSkills, includedSkills, tokens, skillFrontmatter}}——若 controller 未透传
     * result.skill()（如回退 6 字段响应），skills 键缺失变红。
     */
    @Test
    @DisplayName("FIX-B2: REST 响应含 skills 对象（totalSkills/includedSkills/tokens/skillFrontmatter，CC analyzeContext.ts:1368-1376）")
    void analyze_includesSkillsObject_whenTokensPositive() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, List.of()),
            new MemoryTokenCounts(0, List.of()),
            new ToolTokenCounts(25, 40),
            new SkillTokenCounts(2, 30,
                List.of(new SkillFrontmatterDetail("skill-a", "userSettings", 10),
                        new SkillFrontmatterDetail("skill-b", "plugin", 20))),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skills.totalSkills").value(2))
            // CC :601-602 includedSkills === totalSkills === skills.length
            .andExpect(jsonPath("$.skills.includedSkills").value(2))
            // CC :1373 tokens = skillFrontmatterTokens（reduce 和 30）
            .andExpect(jsonPath("$.skills.tokens").value(30))
            .andExpect(jsonPath("$.skills.skillFrontmatter[0].name").value("skill-a"))
            .andExpect(jsonPath("$.skills.skillFrontmatter[0].source").value("userSettings"))
            .andExpect(jsonPath("$.skills.skillFrontmatter[0].tokens").value(10))
            .andExpect(jsonPath("$.skills.skillFrontmatter[1].name").value("skill-b"))
            .andExpect(jsonPath("$.skills.skillFrontmatter[1].tokens").value(20));
    }

    /**
     * [FIX-B2 拍板#4 / NG-4] skillFrontmatterTokens==0 → skills 键省略（CC analyzeContext.ts:1369
     * {@code skillFrontmatterTokens > 0 ? {...} : undefined}）。
     *
     * <p>WHY：CC 无技能时返回 undefined（JSON 省略键）；Java @JsonInclude(NON_NULL) 使 null → 省略。
     * 若响应恒含 skills 键（含 0 值），与 CC 可观测响应不一致。
     */
    @Test
    @DisplayName("FIX-B2: skillFrontmatterTokens==0 → skills 键省略（对齐 CC :1369 undefined）")
    void analyze_omitsSkillsObject_whenTokensZero() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, List.of()),
            new MemoryTokenCounts(0, List.of()),
            new ToolTokenCounts(25, 40),
            new SkillTokenCounts(0, 0, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skills").doesNotExist());
    }

    /**
     * [OPD-CM5-F-13 / A15 展示数据段] REST 响应补 categories 分类段 · CC original:
     * ContextData.categories（analyzeContext.ts:1344 = :1007-1087）。
     *
     * <p><b>WHY（规则九）</b>：探查 ✗-2 钉死 REST 无 categories/展示数据段，前端无法按 CC
     * 分类网格（System prompt / System tools / MCP tools / Memory files / Skills）渲染；
     * 拍板 F-13「后端补 REST 字段」要求 categories 段可观测。分类计算（含 'System tools' =
     * builtInToolTokens - skillFrontmatterTokens 扣减值，:1021）由 IMP-F2-2 在 service
     * 完成，本测试钉死 controller <b>透传 result.categories()</b>——若 controller 未透传
     * （如回退 7 字段响应），categories 键缺失变红。
     */
    @Test
    @DisplayName("OPD-CM5-F-13: REST 响应含 categories 展示分类段（透传 service 分类，CC analyzeContext.ts:1344）")
    void analyze_includesCategoriesDisplaySegment() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, List.of()),
            new MemoryTokenCounts(9, List.of(new MemoryFileDetail(".claude/CLAUDE.md", "claude", 9))),
            new ToolTokenCounts(100, 40),
            new SkillTokenCounts(2, 30,
                List.of(new SkillFrontmatterDetail("skill-a", "userSettings", 10),
                        new SkillFrontmatterDetail("skill-b", "plugin", 20))),
            // IMP-F2-2 service 已计算分类（'System tools' = 100-30 = 70 扣减值，CC :1021）
            List.of(
                new ContextCategory("System prompt", 12, "promptBorder"),
                new ContextCategory("System tools", 70, "inactive"),
                new ContextCategory("MCP tools", 40, "cyan_FOR_SUBAGENTS_ONLY"),
                new ContextCategory("Memory files", 9, "claude"),
                new ContextCategory("Skills", 30, "warning"))));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            // CC 分类出现顺序 :1011-1087
            .andExpect(jsonPath("$.categories[0].name").value("System prompt"))
            .andExpect(jsonPath("$.categories[0].tokens").value(12))
            .andExpect(jsonPath("$.categories[0].color").value("promptBorder"))
            .andExpect(jsonPath("$.categories[1].name").value("System tools"))
            .andExpect(jsonPath("$.categories[1].tokens").value(70))
            .andExpect(jsonPath("$.categories[1].color").value("inactive"))
            .andExpect(jsonPath("$.categories[2].name").value("MCP tools"))
            .andExpect(jsonPath("$.categories[2].tokens").value(40))
            .andExpect(jsonPath("$.categories[3].name").value("Memory files"))
            .andExpect(jsonPath("$.categories[3].tokens").value(9))
            .andExpect(jsonPath("$.categories[4].name").value("Skills"))
            .andExpect(jsonPath("$.categories[4].tokens").value(30))
            .andExpect(jsonPath("$.categories[4].color").value("warning"));
    }

    /**
     * [OPD-CM5-F-13 / A15 展示数据段] 空分类 → categories 空数组但键恒存在。
     *
     * <p>WHY：CC categories 因恒含 "Free space"（:1152-1156）永不为空；Java web 端点无
     * contextWindow 无法产出 Free space/buffer → service 返回空列表，controller 透传后
     * categories 键以空数组恒存在——防止前端按 undefined 分支降级。若 controller 将
     * categories 置 null（被 NON_NULL 省略），本断言变红。
     */
    @Test
    @DisplayName("OPD-CM5-F-13: 空分类 → categories 空数组但键恒存在（对齐 CC categories 恒存在）")
    void analyze_returnsEmptyCategoriesArray_whenServiceReturnsEmpty() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(0, List.of()),
            new MemoryTokenCounts(0, List.of()),
            new ToolTokenCounts(0, 0),
            new SkillTokenCounts(0, 0, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories").isArray())
            .andExpect(jsonPath("$.categories.length()").value(0));
    }
}

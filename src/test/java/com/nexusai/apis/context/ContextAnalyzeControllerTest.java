package com.nexusai.apis.context;

import com.nexusai.application.agent.context.ContextAnalyzeService;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextAnalyzeResult;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextCategory;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryTokenCounts;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillFrontmatterDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillTokenCounts;
import com.nexusai.application.agent.context.ContextAnalyzeService.ToolTokenCounts;
import com.nexusai.application.agent.context.ContextAnalyzeService.UnavailableCause;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemTokenCounts;
import com.nexusai.infra.llm.TokenSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
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
 *   <li><b>[四态标注] 每个 token 数值都带来源</b>（{@code tokenSource} / {@code *Source}），
 *       且「算不出来」以 {@code tokens: null} 出现在 wire 上（⛔ 不是 0）。这是本次修复的根：
 *       原实现把 null 抹成 0，「端点缺失」与「真 0」字节相同，前端只能显示一个骗人的 0。</li>
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
    @DisplayName("POST /api/v1/context/analyze → 200 + system/memory/tools 计数段结构（含四态来源标注）")
    void analyze_returnsSystemPromptTokenStructure() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, TokenSource.API, List.of(
                new SystemPromptSectionDetail("System", 8, TokenSource.API),
                new SystemPromptSectionDetail("gitStatus", 4, TokenSource.API))),
            new MemoryTokenCounts(9, TokenSource.API,
                List.of(new MemoryFileDetail(".claude/CLAUDE.md", "claude", 9, TokenSource.API))),
            new ToolTokenCounts(25, TokenSource.API, 40, TokenSource.API),
            new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.systemPromptTokens").value(12))
            // [四态] (a1) 服务端真实计数 → 来源 api，前端不加角标
            .andExpect(jsonPath("$.systemPromptTokensSource").value("api"))
            .andExpect(jsonPath("$.systemPromptSections[0].name").value("System"))
            .andExpect(jsonPath("$.systemPromptSections[0].tokens").value(8))
            .andExpect(jsonPath("$.systemPromptSections[0].tokenSource").value("api"))
            .andExpect(jsonPath("$.systemPromptSections[1].name").value("gitStatus"))
            // RES-R5-2: memory/tools 计数段暴露
            .andExpect(jsonPath("$.claudeMdTokens").value(9))
            .andExpect(jsonPath("$.claudeMdTokensSource").value("api"))
            .andExpect(jsonPath("$.memoryFiles[0].path").value(".claude/CLAUDE.md"))
            .andExpect(jsonPath("$.memoryFiles[0].tokens").value(9))
            .andExpect(jsonPath("$.memoryFiles[0].tokenSource").value("api"))
            .andExpect(jsonPath("$.builtInToolTokens").value(25))
            .andExpect(jsonPath("$.builtInToolTokensSource").value("api"))
            .andExpect(jsonPath("$.mcpToolTokens").value(40))
            .andExpect(jsonPath("$.mcpToolTokensSource").value("api"));
    }

    /**
     * [四态标注] 「算不出来」必须与「真 0」在 wire 上可区分。
     *
     * <p><b>WHY（规则九）</b>：这是本次修复的根 —— provider 没有 count_tokens 端点时，后端原先把
     * {@code null} 抹成 0，前端于是显示「0」并让用户以为统计成功。本用例钉死 wire 契约：
     * {@code tokens} 字段<b>存在且为 null</b>（{@code @JsonInclude(ALWAYS)} 保证不被 NON_NULL 吞掉），
     * 同级 {@code tokenSource="unavailable"}。
     *
     * <p>⚠️ 断言用 {@code nullValue()} 而非 {@code doesNotExist()}：JsonPath 在路径缺失时会抛
     * PathNotFoundException，故 {@code value(nullValue())} 恰恰证明「键存在且值为 null」。
     * 若把 collapse 点改回 {@code raw == null ? 0 : raw}，本用例变红（拿到的是 0）。
     */
    @Test
    @DisplayName("[四态] 算不出来 → tokens 字段存在且为 null + Source=unavailable（⛔ 不是 0）")
    void analyze_unavailable_emitsNullTokens_notZero() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(null, TokenSource.UNAVAILABLE, List.of(
                new SystemPromptSectionDetail("System", null, TokenSource.UNAVAILABLE),
                new SystemPromptSectionDetail("gitStatus", null, TokenSource.UNAVAILABLE))),
            new MemoryTokenCounts(null, TokenSource.UNAVAILABLE,
                List.of(new MemoryFileDetail(".claude/CLAUDE.md", "claude", null, TokenSource.UNAVAILABLE))),
            new ToolTokenCounts(null, TokenSource.UNAVAILABLE, null, TokenSource.UNAVAILABLE),
            new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of()),
            List.of(new ContextCategory("系统提示词", null, "promptBorder", TokenSource.UNAVAILABLE,
                UnavailableCause.PROVIDER))));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.systemPromptTokens").value(nullValue()))
            .andExpect(jsonPath("$.systemPromptTokensSource").value("unavailable"))
            .andExpect(jsonPath("$.systemPromptSections[0].tokens").value(nullValue()))
            .andExpect(jsonPath("$.systemPromptSections[0].tokenSource").value("unavailable"))
            .andExpect(jsonPath("$.claudeMdTokens").value(nullValue()))
            .andExpect(jsonPath("$.claudeMdTokensSource").value("unavailable"))
            .andExpect(jsonPath("$.memoryFiles[0].tokens").value(nullValue()))
            .andExpect(jsonPath("$.memoryFiles[0].tokenSource").value("unavailable"))
            .andExpect(jsonPath("$.builtInToolTokens").value(nullValue()))
            .andExpect(jsonPath("$.builtInToolTokensSource").value("unavailable"))
            .andExpect(jsonPath("$.mcpToolTokens").value(nullValue()))
            .andExpect(jsonPath("$.mcpToolTokensSource").value("unavailable"))
            // 「算不出来」的段仍然产出 —— 否则前端无处渲染「—」
            .andExpect(jsonPath("$.categories[0].name").value("系统提示词"))
            .andExpect(jsonPath("$.categories[0].tokens").value(nullValue()))
            .andExpect(jsonPath("$.categories[0].tokenSource").value("unavailable"))
            // [四态·成因] 不可用的行必须带成因（面板按成因选提示文案）：wire 小写字符串
            .andExpect(jsonPath("$.categories[0].unavailableCause").value("provider"));
    }

    @Test
    @DisplayName("custom/append 请求体透传 service（对齐 CC options.{customSystemPrompt,appendSystemPrompt}）")
    void analyze_passesCustomAndAppendThrough() throws Exception {
        when(service.analyze(eq("my custom"), eq("my append")))
            .thenReturn(new ContextAnalyzeResult(
                new SystemTokenCounts(0, TokenSource.API, List.of()),
                new MemoryTokenCounts(0, TokenSource.API, List.of()),
                new ToolTokenCounts(0, TokenSource.API, 0, TokenSource.API),
                new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of()),
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
     *
     * <p>[四态] skill 段恒为本地 {@code round(len/4)} 粗估 ⇒ {@code tokensSource="estimate"}，
     * 面板标「估算」。⚠️ 这是<b>汇总</b>的来源；下方 {@code skillFrontmatter[].source} 是
     * <b>技能来源</b>（userSettings/plugin），两者不可混为一谈。
     */
    @Test
    @DisplayName("FIX-B2: REST 响应含 skills 对象（totalSkills/includedSkills/tokens/skillFrontmatter，CC analyzeContext.ts:1368-1376）")
    void analyze_includesSkillsObject_whenTokensPositive() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, TokenSource.API, List.of()),
            new MemoryTokenCounts(0, TokenSource.API, List.of()),
            new ToolTokenCounts(25, TokenSource.API, 40, TokenSource.API),
            new SkillTokenCounts(2, 30, TokenSource.ESTIMATE,
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
            .andExpect(jsonPath("$.skills.tokensSource").value("estimate"))
            .andExpect(jsonPath("$.skills.skillFrontmatter[0].name").value("skill-a"))
            // ⚠️ 这是技能来源（userSettings），不是 token 来源 —— 别与 tokensSource 混淆
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
     *
     * <p>[四态 · 产出策略重定] 省略条件由「{@code >0}」精确化为「<b>确切的 0</b>」：
     * {@code tokens==null}（算不出来）时<b>仍产出</b>，只有「真的没有技能」才省略 —— 否则
     * 「技能数 0」会被当成「算不出来」，用户看不到「—」。
     */
    @Test
    @DisplayName("FIX-B2: skillFrontmatterTokens==0 → skills 键省略（对齐 CC :1369 undefined）")
    void analyze_omitsSkillsObject_whenTokensZero() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, TokenSource.API, List.of()),
            new MemoryTokenCounts(0, TokenSource.API, List.of()),
            new ToolTokenCounts(25, TokenSource.API, 40, TokenSource.API),
            new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skills").doesNotExist());
    }

    /**
     * [四态 · 产出策略重定 + 技能加载失败] skill 段算不出来 → skills 键<b>仍然产出</b>
     * （tokens: null，且 totalSkills/includedSkills 也 null）。
     *
     * <p><b>WHY（规则九）</b>：与「真 0 省略」配对使用才能证明省略判据是 {@code tokens==null}
     * 而不是数值大小 —— 两条用例合起来把「null=不知道（要出现）／0=没有（不出现）」钉死。
     * 另：技能<b>加载失败</b>时技能数同样未知 ⇒ {@code totalSkills/includedSkills} 必须是 wire 上的
     * {@code null}（⛔ 不是 0），否则面板会同时显示「技能数 0 / 0」与「— 不可用」这种自相矛盾的组合。
     * 断言用 {@code nullValue()}（而非 {@code doesNotExist()}）证明<b>键存在且值为 null</b> ——
     * 若 SkillInfoView 的两个计数字段没加 {@code @JsonInclude(ALWAYS)}，类级 NON_NULL 会把它们从
     * JSON 里<b>删掉</b>，JsonPath 抛 PathNotFoundException，本用例变红。
     */
    @Test
    @DisplayName("[四态] skill 段算不出来 → skills 键仍产出且 tokens/totalSkills 均为 null（tokensSource=unavailable）")
    void analyze_keepsSkillsObject_whenUnavailable() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, TokenSource.API, List.of()),
            new MemoryTokenCounts(0, TokenSource.API, List.of()),
            new ToolTokenCounts(25, TokenSource.API, 40, TokenSource.API),
            new SkillTokenCounts(null, null, TokenSource.UNAVAILABLE, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skills.tokens").value(nullValue()))
            .andExpect(jsonPath("$.skills.tokensSource").value("unavailable"))
            .andExpect(jsonPath("$.skills.totalSkills").value(nullValue()))
            .andExpect(jsonPath("$.skills.includedSkills").value(nullValue()));
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
     *
     * <p>[UI 本地化 · 有意偏离 CC] 类别名是<b>中文</b>（用户裁定「分类行一起翻成中文」），
     * ⛔ 不是漏抄 CC 英文名 —— 断言里刻意用中文串，防止有人「顺手改回英文对齐 CC」。
     */
    @Test
    @DisplayName("OPD-CM5-F-13: REST 响应含 categories 展示分类段（透传 service 分类，CC analyzeContext.ts:1344）")
    void analyze_includesCategoriesDisplaySegment() throws Exception {
        when(service.analyze(eq(null), eq(null))).thenReturn(new ContextAnalyzeResult(
            new SystemTokenCounts(12, TokenSource.API, List.of()),
            new MemoryTokenCounts(9, TokenSource.API,
                List.of(new MemoryFileDetail(".claude/CLAUDE.md", "claude", 9, TokenSource.API))),
            new ToolTokenCounts(100, TokenSource.API, 40, TokenSource.API),
            new SkillTokenCounts(2, 30, TokenSource.ESTIMATE,
                List.of(new SkillFrontmatterDetail("skill-a", "userSettings", 10),
                        new SkillFrontmatterDetail("skill-b", "plugin", 20))),
            // IMP-F2-2 service 已计算分类（'System tools' = 100-30 = 70 扣减值，CC :1021）
            // [UI 本地化 · 有意偏离 CC] 类别名由 service 输出中文（⛔ 不是漏抄 CC 英文名）
            List.of(
                new ContextCategory("系统提示词", 12, "promptBorder", TokenSource.API, null),
                // [扣减行改名 · 用户裁定] 分类行那行不再与工具节的「内置工具」（全量）同名
                new ContextCategory("内置工具（不含技能）", 70, "inactive", TokenSource.API, null),
                new ContextCategory("MCP 工具", 40, "cyan_FOR_SUBAGENTS_ONLY", TokenSource.API, null),
                new ContextCategory("记忆文件", 9, "claude", TokenSource.API, null),
                new ContextCategory("技能", 30, "warning", TokenSource.ESTIMATE, null))));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            // 分类出现顺序对齐 CC :1011-1087（名称已本地化）
            .andExpect(jsonPath("$.categories[0].name").value("系统提示词"))
            .andExpect(jsonPath("$.categories[0].tokens").value(12))
            .andExpect(jsonPath("$.categories[0].color").value("promptBorder"))
            .andExpect(jsonPath("$.categories[0].tokenSource").value("api"))
            .andExpect(jsonPath("$.categories[1].name").value("内置工具（不含技能）"))
            .andExpect(jsonPath("$.categories[1].tokens").value(70))
            .andExpect(jsonPath("$.categories[1].color").value("inactive"))
            // [四态·成因] 数值正常的行不带成因（wire 上省略该键）——「有成因 ⟺ 不可用」这条锚定关系
            .andExpect(jsonPath("$.categories[1].unavailableCause").doesNotExist())
            .andExpect(jsonPath("$.categories[2].name").value("MCP 工具"))
            .andExpect(jsonPath("$.categories[2].tokens").value(40))
            .andExpect(jsonPath("$.categories[3].name").value("记忆文件"))
            .andExpect(jsonPath("$.categories[3].tokens").value(9))
            .andExpect(jsonPath("$.categories[4].name").value("技能"))
            .andExpect(jsonPath("$.categories[4].tokens").value(30))
            .andExpect(jsonPath("$.categories[4].color").value("warning"))
            .andExpect(jsonPath("$.categories[4].tokenSource").value("estimate"));
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
            new SystemTokenCounts(0, TokenSource.API, List.of()),
            new MemoryTokenCounts(0, TokenSource.API, List.of()),
            new ToolTokenCounts(0, TokenSource.API, 0, TokenSource.API),
            new SkillTokenCounts(0, 0, TokenSource.ESTIMATE, List.of()),
            List.of()));

        mockMvc.perform(post("/api/v1/context/analyze")
                .contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories").isArray())
            .andExpect(jsonPath("$.categories.length()").value(0));
    }
}

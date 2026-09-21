package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组装 getSystemPrompt 等价测试 · 对齐 CC getSystemPrompt 常规分支（prompts.ts:444-577）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）: 组装序是 CC 行为契约——7 静态固定序（I-11）+ doingTasks
 * outputStyle 门控（:564-566）+ boundary 独立数组元素（I-7，:573）+ registry 解析 + null filter
 * （I-3，:576）。测试钉死顺序与门控，防止组装器回归到旧 PromptAssembler 的无序/无条件插 boundary
 * 行为（DEL-SP-02）。
 */
class SystemPromptAssemblerTest {

    private static SystemPromptAssemblyInput input(OutputStyleConfig outputStyle) {
        return new SystemPromptAssemblyInput(
            Set.of("Read", "Edit"),
            "claude-sonnet-4-6",
            List.of(),
            List.of(),
            outputStyle,
            List.of(),
            null,
            null,
            false // tokenBudgetEnabled（默认关 · 对齐 CC feature('TOKEN_BUDGET') 缺省）
        );
    }

    /** tokenBudgetEnabled=true 的组装输入 · ER-IMP-2026-04 P-20 token_budget section 用例用。 */
    private static SystemPromptAssemblyInput tokenBudgetInput() {
        return new SystemPromptAssemblyInput(
            Set.of("Read", "Edit"),
            "claude-sonnet-4-6",
            List.of(),
            List.of(),
            null,
            List.of(),
            null,
            null,
            true
        );
    }

    // ── I-11 静态集序 ──

    @Test
    @DisplayName("I-11：7 静态 section 固定顺序 intro/system/doingTasks/actions/usingYourTools/toneAndStyle/outputEfficiency")
    void staticOrder_sevenSections() {
        SystemPromptAssembler assembler = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> false);

        SystemPrompt prompt = assembler.assemble(input(null));

        assertThat(prompt.elements()).containsSubsequence(
            StaticPromptSections.simpleIntroSection(null),
            StaticPromptSections.simpleSystemSection(),
            StaticPromptSections.simpleDoingTasksSection(),
            StaticPromptSections.actionsSection(),
            StaticPromptSections.usingYourToolsSection(Set.of("Read", "Edit")),
            StaticPromptSections.simpleToneAndStyleSection(),
            StaticPromptSections.outputEfficiencySection()
        );
        assertThat(prompt.elements().get(0)).as("intro 恒为首元素（CC:562）").startsWith("\nYou are an interactive agent");
        assertThat(prompt.elements().get(1)).as("system 次位（CC:563）").startsWith("# System\n");
        assertThat(prompt.elements().get(2)).as("doingTasks 第三位（CC:565-567）").startsWith("# Doing tasks\n");
    }

    // ── doingTasks outputStyle 门控（prompts.ts:564-566）──

    @Test
    @DisplayName("doingTasks 门控：outputStyleConfig=null → 注入；keepCodingInstructions=true → 注入；false → 不注入（:564-566）")
    void doingTasks_outputStyleGate() {
        // 每个组装用独立 cache —— output_style 属会话级缓存（I-1），同 cache 复算会命中首值
        SystemPromptAssembler noStyleAsm = new SystemPromptAssembler(new SystemPromptSectionCache());
        SystemPromptAssembler keepCodingAsm = new SystemPromptAssembler(new SystemPromptSectionCache());
        SystemPromptAssembler dropCodingAsm = new SystemPromptAssembler(new SystemPromptSectionCache());

        SystemPrompt noStyle = noStyleAsm.assemble(input(null));
        SystemPrompt keepCoding = keepCodingAsm.assemble(input(
            new OutputStyleConfig("Explanatory", null, "Explain.", "built-in", true)));
        SystemPrompt dropCoding = dropCodingAsm.assemble(input(
            new OutputStyleConfig("Concise", null, "Be brief.", "built-in", false)));

        assertThat(noStyle.elements()).as("config null → 注入 doingTasks").anyMatch(s -> s.startsWith("# Doing tasks\n"));
        assertThat(keepCoding.elements()).as("keepCodingInstructions=true → 注入 doingTasks").anyMatch(s -> s.startsWith("# Doing tasks\n"));
        assertThat(dropCoding.elements()).as("keepCodingInstructions=false → doingTasks 被 Output Style 取代，不注入")
            .noneMatch(s -> s != null && s.startsWith("# Doing tasks\n"));
        assertThat(dropCoding.elements()).as("Output Style 动态 section 注入（output_style compute）")
            .anyMatch(s -> s != null && s.startsWith("# Output Style: Concise\n"));
    }

    // ── I-7 boundary 门控独立数组元素 ──

    @Test
    @DisplayName("I-7：boundary 仅门控为真时作为独立数组元素（:573）")
    void boundary_gatedIndependentElement() {
        SystemPromptAssembler withBoundary = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> true);
        SystemPromptAssembler withoutBoundary = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> false);

        SystemPrompt on = withBoundary.assemble(input(null));
        SystemPrompt off = withoutBoundary.assemble(input(null));

        assertThat(on.elements()).as("门控 true → boundary 作为独立数组元素").contains(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        assertThat(off.elements()).as("门控 false → boundary 不插入").doesNotContain(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        int boundaryIdx = on.elements().indexOf(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        assertThat(boundaryIdx).as("boundary 在 7 静态之后、动态之前").isEqualTo(7);
    }

    // ── I-3 null filter ──

    @Test
    @DisplayName("I-3：null 段被 filter 移除，不出现在结果数组（:576）")
    void nullSections_filteredOut() {
        // memoryLoader=null → memory compute null；language=null → language null；无 MCP → mcp null；
        // ant_model_override 恒 null → 均被 filter 移除
        SystemPromptAssembler assembler = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> true);

        SystemPrompt prompt = assembler.assemble(input(null));

        assertThat(prompt.elements()).doesNotContainNull();
        assertThat(prompt.elements()).as("无任何 null 段残留").noneMatch(s -> s == null);
    }

    // ── I-12 13 条目注册清单 ──

    @Test
    @DisplayName("I-12：buildDynamicSections 默认注册 10 条且全部可缓存（无 DANGEROUS_uncached）；token_budget 门控关时恒不注册")
    void dynamicRegistration_thirteenEntries() {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(input(null));

        assertThat(sections).extracting(SystemPromptSection::name)
            .as("CC 动态数组注册序（prompts.ts:492-525）")
            .containsExactly(
                "session_guidance",
                "memory",
                "ant_model_override",
                "env_info_simple",
                "language",
                "output_style",
                "mcp_instructions",
                "scratchpad",
                "frc",
                "summarize_tool_results"
            );
        assertThat(sections).as("10 条全部 cacheBreak=false（对齐 CC 2.1.278：cacheBreak:true 计数 0）")
            .filteredOn(SystemPromptSection::cacheBreak)
            .isEmpty();
        assertThat(sections).as("3 feature-gated（numeric_length_anchors/token_budget/brief）门控关时不注册")
            .extracting(SystemPromptSection::name)
            .doesNotContain("numeric_length_anchors", "token_budget", "brief");
    }

    // ── [ER-IMP-2026-04 P-20] token_budget section 门控（CC prompts.ts:538-551）──

    @Test
    @DisplayName("P-20：tokenBudgetEnabled=true → token_budget section 注册（summarize_tool_results 之后）且文案逐字对齐 CC prompts.ts:548")
    void tokenBudgetEnabled_registersTokenBudgetSection() {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(tokenBudgetInput());

        assertThat(sections).extracting(SystemPromptSection::name)
            .as("tokenBudgetEnabled=true → 11 条（10 无条件 + token_budget，CC 注册序 prompts.ts:492-549）")
            .containsExactly(
                "session_guidance",
                "memory",
                "ant_model_override",
                "env_info_simple",
                "language",
                "output_style",
                "mcp_instructions",
                "scratchpad",
                "frc",
                "summarize_tool_results",
                "token_budget"
            );
        assertThat(sections).as("token_budget 注册序在 summarize_tool_results 之后（CC prompts.ts:521-551）")
            .extracting(SystemPromptSection::name)
            .containsSubsequence("summarize_tool_results", "token_budget");
    }

    @Test
    @DisplayName("P-20：tokenBudgetEnabled=true → section compute 文案逐字 CC prompts.ts:548（em-dash U+2014 · 直引号 · hard minimum）")
    void tokenBudgetSection_textMatchesCcPrompt() throws Exception {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(tokenBudgetInput());
        SystemPromptSection section = sections.stream()
            .filter(s -> "token_budget".equals(s.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("tokenBudgetEnabled=true 时 token_budget section 必须注册"));

        // record 访问器 compute() 返回 ComputeFn；二次调用 compute() 得 CompletableFuture（CC async 语义）
        String text = section.compute().compute().get();
        assertThat(text)
            .as("文案逐字对齐 CC prompts.ts:548（CC 原文含 em-dash U+2014 与直引号）")
            .isEqualTo("When the user specifies a token target (e.g., \"+500k\", \"spend 2M tokens\", \"use 1B tokens\"), your output token count will be shown each turn. Keep working until you approach the target \u2014 plan your work to fill it productively. The target is a hard minimum, not a suggestion. If you stop early, the system will automatically continue you.");
    }

    @Test
    @DisplayName("P-20：tokenBudgetEnabled=false → token_budget section 不存在（CC prompts.ts:538 feature 关 → 不注册）")
    void tokenBudgetDisabled_noTokenBudgetSection() {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(input(null));
        assertThat(sections).as("tokenBudgetEnabled=false → token_budget 不注册")
            .extracting(SystemPromptSection::name)
            .doesNotContain("token_budget");
    }

    // ── 动态 section 解析 ──

    @Test
    @DisplayName("registry 解析：dynamic sections 经 resolveAll 拼入静态之后；boundary 门控时在 boundary 之后")
    void dynamicResolvedAfterStatic() {
        SystemPromptAssembler assembler = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> true);

        SystemPrompt prompt = assembler.assemble(input(null));

        int boundaryIdx = prompt.elements().indexOf(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        int summarizeIdx = prompt.elements().indexOf("When working with tool results, write down any important information you might need later in your response, as the original tool result may be cleared later.");
        assertThat(boundaryIdx).as("boundary 存在").isNotNegative();
        assertThat(summarizeIdx).as("summarize_tool_results（registry 末条）在 boundary 之后").isGreaterThan(boundaryIdx);
    }

    @Test
    @DisplayName("不变量（2.1.278 口径）：动态 section 里 cacheBreak=true 恰好 0 条（全份提示无可破前缀缓存段）")
    void dynamicSections_noCacheBreakSection() {
        // WHY：cacheBreak=true 的段每轮重算并覆盖缓存 ⇒ 毁掉其后<b>全部</b>前缀的命中。
        //   CC 2.1.278 实测 cacheBreak:!0 / cacheBreak:true 均 0 处（2.1.88 时代的那 1 处
        //   mcp_instructions 已被上游删除，MCP 指令改走 delta 尾部附件）⇒ 本仓对齐为 0 处。
        //   本用例把这条不变量钉在<b>注册表实际产物</b>上：一旦有人新增易失段，本用例立刻变红。
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(tokenBudgetInput());

        List<SystemPromptSection> volatileSections = sections.stream()
            .filter(SystemPromptSection::cacheBreak)
            .toList();

        assertThat(volatileSections)
            .as("动态 section 里 cacheBreak=true 恰好 0 条（对齐 CC 2.1.278）")
            .isEmpty();
        assertThat(SystemPromptCacheBreakWhitelist.size())
            .as("重算白名单同为 0 条（守「破坏缓存必须显式 + 需理由」的纪律，机制保留）")
            .isZero();
    }

    // ── 真入口可达性：mcp_instructions 经 SystemPromptAssembler 走会话级缓存命中路径 ──

    @Test
    @DisplayName("真入口可达性：同会话第二次 assemble（MCP 指令已变）仍产出首值 ⇒ mcp_instructions 确实走会话级缓存命中")
    void mcpInstructions_secondAssembleHitsSessionCache() {
        // WHY：本批把 mcp_instructions 从 cacheBreak=true 改为可缓存段，「会话级冻结」只有在
        //   SystemPromptAssembler 真的把会话级 SystemPromptSectionCache 透传给 registry.resolveAll 时才成立。
        //   本用例走**真入口** SystemPromptAssembler.assemble（生产路径 = LlmAgentLoop.collectRunMaterial
        //   :4902-4916 构造同一个 assembler），用同一 cache 组装两次、第二次换掉 MCP 指令
        //   ⇒ 断言第二次仍拿到首值：若接线断了（例如每次 new 一个空 cache），第二次必然拿到新值 → 红。
        SystemPromptSectionCache sessionCache = new SystemPromptSectionCache();
        SystemPromptAssembler assembler = new SystemPromptAssembler(sessionCache);

        SystemPrompt first = assembler.assemble(mcpInput("github", "首轮指令（会话首值）"));
        String firstMcp = elementStartingWith(first, "# MCP Server Instructions\n");
        SystemPrompt second = assembler.assemble(mcpInput("github", "会话中途换过的指令"));
        String secondMcp = elementStartingWith(second, "# MCP Server Instructions\n");

        assertThat(firstMcp).as("首轮 assemble 必须产出 MCP 段（否则本用例无意义）")
            .isNotNull()
            .contains("首轮指令（会话首值）");
        assertThat(secondMcp)
            .as("第二次 assemble 换掉了 MCP 指令却仍返回首值 ⇒ mcp_instructions 走会话级缓存命中"
                + "（要 /clear 或 /compact 才刷新 —— 已登记分歧）")
            .isEqualTo(firstMcp)
            .doesNotContain("会话中途换过的指令");
        assertThat(sessionCache.has("mcp_instructions"))
            .as("会话级分段缓存里确有该段（生产同一 cache 实例来自 SessionPromptCacheStore#sectionCache）")
            .isTrue();
    }

    /** 含单个 MCP 客户端的组装输入（其余字段同 {@link #input}）。 */
    private static SystemPromptAssemblyInput mcpInput(String serverName, String instructions) {
        return new SystemPromptAssemblyInput(
            Set.of("Read", "Edit"),
            "claude-sonnet-4-6",
            List.of(),
            List.of(new SystemPromptAssemblyInput.McpClientInfo(serverName, instructions, true)),
            null,
            List.of(),
            null,
            null,
            false
        );
    }

    /** 取 elements 里以 prefix 开头的首个元素（无 → null）。 */
    private static String elementStartingWith(SystemPrompt prompt, String prefix) {
        return prompt.elements().stream()
            .filter(e -> e != null && e.startsWith(prefix))
            .findFirst()
            .orElse(null);
    }
}

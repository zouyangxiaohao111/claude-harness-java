package com.nexusai.application.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.context.ContextAnalyzeService.ContextAnalyzeResult;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.MemoryFileEntry;
import com.nexusai.application.agent.context.ContextAnalyzeService.SkillFrontmatterDetail;
import com.nexusai.application.agent.context.ContextAnalyzeService.ToolDefinition;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemPromptSectionDetail;
import com.nexusai.application.agent.prompt.SystemPromptTokenCounter.SystemTokenCounts;
import com.nexusai.application.agent.skill.BundledSkills;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.llm.CountTokensClient;
import com.nexusai.infra.llm.TokenSource;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [RES-R5] {@link ContextAnalyzeService} 意图测试 · 消费方接入 /context analyze（对齐 CC
 * analyzeContextUsage D1 段 effectiveSystemPrompt 构建 analyzeContext.ts:938-947 + D3 段
 * countSystemTokens 聚合 analyzeContext.ts:963-964）。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>：SystemPromptTokenCounter 是重建的纯能力
 * （09 §五③），唯一目标是为它接入 web 消费方。本测试钉死服务端 D1/D3 语义：
 * <ol>
 *   <li><b>custom 替换 default + append 恒末尾</b>（D1，systemPrompt.ts:115-122）——若服务退化
 *       为"custom 与 default 拼接"或 append 插入非末尾，token 明细会偏离 CC 组装链。</li>
 *   <li><b>boundary 过滤 + 空短路</b>（D3，analyzeContext.ts:287/295-297）——boundary 是缓存标记
 *       不可入 section；全 boundary 的 effective prompt 必须返回 {0, []} 而非报错。</li>
 *   <li><b>默认组装 7 静态 section 计数</b>（D1 无 custom/append → 走默认组装）——消费方必须复用
 *       LlmAgentLoop 同款组装链（SystemPromptAssembler + EffectiveSystemPromptBuilder），
 *       不能另起一套假组装。</li>
 * </ol>
 */
class ContextAnalyzeServiceTest {

    private static final String BOUNDARY = SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY;

    /** 固定计数（逐 section 返回 5）· 结构断言不依赖具体数值。 */
    private static final CountTokensClient FIXED_5 = content -> 5;

    /** 注入可控 systemContext（CC getSystemContext 产物）+ 固定计数器，避免单测真跑 git 子进程/LLM API。 */
    private static ContextAnalyzeService service(Map<String, String> systemContext) {
        return new ContextAnalyzeService(() -> systemContext, FIXED_5);
    }

    @Test
    @DisplayName("D1: custom 替换 default（default section 不出现）+ append 恒末尾（systemPrompt.ts:115-122）")
    void customReplacesDefault_appendAppendedAtEnd() {
        ContextAnalyzeResult result = service(Map.of())
            .analyze("# My Custom\ncustom content", "# My Append\nappend content");

        // custom 替换 default：default 静态 section（# System 等）不得出现
        assertThat(result.system().systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("My Custom", "My Append");
        // rough 求和（tokenEstimation.ts:203-208）：总 token = Σ section token
        assertThat(result.system().systemPromptTokens()).isEqualTo(
            result.system().systemPromptSections().stream().mapToInt(SystemPromptSectionDetail::tokens).sum());
        assertThat(result.system().systemPromptTokens()).isPositive();
    }

    @Test
    @DisplayName("D3: 全 boundary 的 effective prompt → {0, []} 短路（analyzeContext.ts:287/295-297）")
    void boundaryOnly_shortCircuitsToZero() {
        ContextAnalyzeResult result = service(Map.of()).analyze(BOUNDARY, null);
        assertThat(result.system().systemPromptTokens()).isZero();
        assertThat(result.system().systemPromptSections()).isEmpty();
    }

    @Test
    @DisplayName("D3: append=boundary 数组元素 → boundary 剔除、custom section 保留（analyzeContext.ts:287）")
    void boundaryAppend_filteredOut() {
        // effective = [custom, BOUNDARY]（append 恒末尾，systemPrompt.ts:121）→ countSystemTokens
        // 过滤 boundary 数组元素（:287）→ 仅 custom section 计入
        ContextAnalyzeResult result = service(Map.of()).analyze("# First\ncontent1", BOUNDARY);
        assertThat(result.system().systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .containsExactly("First");
    }

    @Test
    @DisplayName("D1: 无 custom/append → 默认组装 7 静态 section，总 token = Σ section（prompts.ts:562-576）")
    void noCustom_assemblesDefaultStaticSections() {
        ContextAnalyzeResult result = service(Map.of()).analyze(null, null);
        // 默认组装产出 7 静态 section（dynamic 全 null filter 剔除）
        assertThat(result.system().systemPromptSections()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(result.system().systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .contains("System");
        // D3 聚合恒等式
        assertThat(result.system().systemPromptTokens()).isEqualTo(
            result.system().systemPromptSections().stream().mapToInt(SystemPromptSectionDetail::tokens).sum());
    }

    @Test
    @DisplayName("D1+D3: systemContext 非空条目并入计数（key 作 name，analyzeContext.ts:290-293）")
    void systemContext_mergedAsNamedEntries() {
        Map<String, String> ctx = Map.of("gitStatus", "# branch master\non branch master");
        ContextAnalyzeResult result = service(ctx).analyze("# My Custom\ncustom", null);
        assertThat(result.system().systemPromptSections()).extracting(SystemPromptSectionDetail::name)
            .contains("My Custom", "gitStatus");
    }

    // ════════════════════════════════════════════════════════════════════════
    // RES-R5-2：memory / tools 计数段（对齐 CC analyzeContextUsage 并行段 analyzeContext.ts:950-983）
    // ════════════════════════════════════════════════════════════════════════

    private static final JsonNode SCHEMA = new ObjectMapper().createObjectNode().put("type", "object");

    /** 记录每次 countTokens 入参的客户端 · 证明"真实 CountTokensClient 被调用"而非 rough 估算。 */
    private static final class RecordingClient implements CountTokensClient {
        private final Function<String, Integer> fn;
        private final List<String> calls = new ArrayList<>();
        private final TokenSource sourceKind;

        RecordingClient(Function<String, Integer> fn) {
            this(fn, TokenSource.API);
        }

        RecordingClient(Function<String, Integer> fn, TokenSource sourceKind) {
            this.fn = fn;
            this.sourceKind = sourceKind;
        }

        @Override
        public Integer countTokens(String content) {
            calls.add(content);
            return fn.apply(content);
        }

        @Override
        public TokenSource sourceKind() {
            return sourceKind;
        }

        List<String> calls() {
            return calls;
        }
    }

    @Test
    @DisplayName("R5-2: memory 段逐文件真实 countTokens 计数（CC countMemoryFileTokens analyzeContext.ts:320-361）")
    void memorySegment_countedViaRealClient_nonZero() {
        RecordingClient client = new RecordingClient(content -> 7);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(new MemoryFileEntry(".claude/CLAUDE.md", "claude", "## Memory\nproject memory content")),
            List.of());

        ContextAnalyzeResult result = svc.analyze(null, null);

        // claudeMdTokens = 单文件真实计数求和（FIXED 7）；明细含 path/type/tokens
        assertThat(result.memory().claudeMdTokens()).isEqualTo(7);
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::path)
            .containsExactly(".claude/CLAUDE.md");
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::type)
            .containsExactly("claude");
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::tokens)
            .containsExactly(7);
        // [四态] (a1) 态：真实计数 → 来源 api，明细逐行带来源
        assertThat(result.memory().claudeMdTokensSource()).isEqualTo(TokenSource.API);
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::tokenSource)
            .containsExactly(TokenSource.API);
        // 真实客户端以 memory 文件 content 为入参（非 rough 估算）
        assertThat(client.calls()).anyMatch(c -> c.contains("## Memory"));
    }

    /**
     * [四态标注] memory 段「算不出来」不得抹成 0。
     *
     * <p><b>WHY（规则九）</b>：{@code claudeMdTokens=0} 与 {@code claudeMdTokens=null} 在前端是两种
     * 完全不同的呈现（「真的没有记忆文件」vs「这个提供商算不出来，显示 —」）。原实现
     * {@code int tokens = raw == null ? 0 : raw}（CC analyzeContext.ts:347 tokens||0）把两者塌成同一个
     * 字节 ⇒ 用户看到「0」并以为统计成功。若把该分支改回 {@code ? 0 : raw}，本用例变红。
     */
    @Test
    @DisplayName("[四态] memory 段计数失败 → tokens=null + tokenSource=unavailable（⛔ 不是 0），合计亦 null")
    void memorySegment_unavailable_keptAsNull_notZero() {
        RecordingClient client = new RecordingClient(content -> null); // API 失败 / 端点缺失
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(new MemoryFileEntry(".claude/CLAUDE.md", "claude", "## Memory\ncontent")),
            List.of());

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::tokens)
            .as("算不出来必须是 null，⛔ 不是 0")
            .containsExactly((Integer) null);
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::tokenSource)
            .containsExactly(TokenSource.UNAVAILABLE);
        assertThat(result.memory().claudeMdTokens()).isNull();
        assertThat(result.memory().claudeMdTokensSource()).isEqualTo(TokenSource.UNAVAILABLE);
    }

    @Test
    @DisplayName("R5-2: memory 段空原料 → {0, []} 短路（CC countMemoryFileTokens analyzeContext.ts:333-338）")
    void memorySegment_emptySource_shortCircuitsToZero() {
        ContextAnalyzeResult result = service(Map.of()).analyze(null, null); // 无注入 memory → 空
        assertThat(result.memory().claudeMdTokens()).isZero();
        assertThat(result.memory().memoryFiles()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // RES-C9: tools 数组路径 + TOOL_TOKEN_COUNT_OVERHEAD=500 补偿
    // ════════════════════════════════════════════════════════════════════

    /** 记录 tools 数组调用的客户端 · RES-C9 验证 tools 数组路径（非文本）+ overhead 补偿。
     *  {@code sourceKind} 决定「是否扣 TOOL_TOKEN_COUNT_OVERHEAD=500」：默认 {@link TokenSource#API}
     *  （服务端真实计数，数字含 API 的 tools 前缀开销 ⇒ 扣）；{@link TokenSource#ESTIMATE} 模拟
     *  {@code OpenAICountTokensClient}（本地 tiktoken，数字不含该开销 ⇒ 不扣）。 */
    private static final class ToolsRecordingClient implements CountTokensClient {
        private final List<List<CountTokensClient.ToolSchema>> toolsCalls = new ArrayList<>();
        private final java.util.function.Function<List<CountTokensClient.ToolSchema>, Integer> toolsFn;
        private final TokenSource sourceKind;

        ToolsRecordingClient(java.util.function.Function<List<CountTokensClient.ToolSchema>, Integer> toolsFn) {
            this(toolsFn, TokenSource.API);
        }

        ToolsRecordingClient(java.util.function.Function<List<CountTokensClient.ToolSchema>, Integer> toolsFn,
                             TokenSource sourceKind) {
            this.toolsFn = toolsFn;
            this.sourceKind = sourceKind;
        }

        @Override
        public Integer countTokens(String content) {
            return 0;
        }

        @Override
        public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
            toolsCalls.add(tools);
            return toolsFn.apply(tools);
        }

        @Override
        public TokenSource sourceKind() {
            return sourceKind;
        }

        List<List<CountTokensClient.ToolSchema>> toolsCalls() {
            return toolsCalls;
        }
    }

    @Test
    @DisplayName("C9: tools 段走 tools 数组路径（非 JSON 文本）+ TOOL_TOKEN_COUNT_OVERHEAD=500 补偿")
    void toolsSegment_toolsArrayPath_withOverheadCompensation() {
        // tools 数组返回 800 → 减 500 overhead → 300（Math.max(0, 800-500)=300）
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 800);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(
                new ToolDefinition("read_file", "read", SCHEMA, false),
                new ToolDefinition("bash", "run", SCHEMA, false),
                new ToolDefinition("mcp__tool", "mcp", SCHEMA, true)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        // 走 tools 数组路径（countTokensForTools 被调用，非 countTokens(String) 拼文本）
        assertThat(client.toolsCalls()).hasSize(2); // built-in 组 + MCP 组 各一次 bulk 调用
        // overhead 500 补偿：800 - 500 = 300（每组各扣一次，CC analyzeContext.ts:479/:638-641）
        assertThat(result.tools().builtInToolTokens()).isEqualTo(300);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(300);
        // [四态] (a1) 态：客户端 clountTokensForTools 走真实端点 → 来源 api
        assertThat(result.tools().builtInToolTokensSource()).isEqualTo(TokenSource.API);
        assertThat(result.tools().mcpToolTokensSource()).isEqualTo(TokenSource.API);
        // tools 数组入参为真实 schema（含工具名），非 JSON 字符串
        assertThat(client.toolsCalls().get(0)).extracting(CountTokensClient.ToolSchema::name)
            .containsExactly("read_file", "bash");
        assertThat(client.toolsCalls().get(1)).extracting(CountTokensClient.ToolSchema::name)
            .containsExactly("mcp__tool");
    }

    @Test
    @DisplayName("C9: 服务端计数 overhead 补偿 ≤ 0 时 clamp 为 0（CC Math.max(0, raw - 500)）")
    void toolsSegment_overheadExceedsRaw_clampedToZero() {
        // 服务端计数客户端（sourceKind=API）返回 200 → 200 - 500 = -300 → clamp 0
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 200, TokenSource.API);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("tiny", "t", SCHEMA, false)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.tools().builtInToolTokens()).isZero(); // 200 - 500 → 0
        // [四态] 这是<b>真 0</b>（可计算，只是被 overhead 夹到 0）⇒ 不得标不可用，且分类仍按 CC 省略
        assertThat(result.tools().builtInToolTokensSource()).isEqualTo(TokenSource.API);
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .as("真 0 ≠ 算不出来：「确实没有内容」的类别仍按 CC 省略，不强行造段")
            .isEmpty();
    }

    /**
     * [500 扣减判据 · 1/2] 数值来自<b>本地估算</b>（tiktoken）⇒ <b>不扣</b> TOOL_TOKEN_COUNT_OVERHEAD。
     *
     * <p><b>WHY（规则九）</b>：这 500 是 <b>API 侧</b>开销 —— CC analyzeContext.ts:68-75 原文
     * 「The API adds a tool prompt preamble (~500 tokens) once per API call when tools are present …
     * We subtract this overhead from per-tool counts」。{@code OpenAICountTokensClient} 走本地
     * tiktoken、<b>不发这次请求</b>，它数出来的值里本来就不含这 500 ⇒ 再扣就是<b>系统性低估 500</b>，
     * 面板上「内置工具 / MCP 工具」两行会恒比真值少 500。若把扣减判据去掉（回到「非 null 就无条件扣」），
     * 本用例变红（800 → 300）。
     */
    @Test
    @DisplayName("[500 判据] 本地估算来源 → 不扣 TOOL_TOKEN_COUNT_OVERHEAD（只有服务端计数才扣）")
    void toolsSegment_localEstimate_noOverheadDeduction() {
        // 本地估算客户端（sourceKind=ESTIMATE，等价 OpenAICountTokensClient 的 tiktoken 路径）
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 800, TokenSource.ESTIMATE);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(
                new ToolDefinition("read_file", "read", SCHEMA, false),
                new ToolDefinition("mcp__tool", "mcp", SCHEMA, true)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.tools().builtInToolTokens())
            .as("本地估算不含 API 的 500 工具前缀开销 ⇒ 原样返回 800，⛔ 不是 800-500=300")
            .isEqualTo(800);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(800);
        // 来源仍如实标「估算」（数值可信度不变，只是不再被系统性低估）
        assertThat(result.tools().builtInToolTokensSource()).isEqualTo(TokenSource.ESTIMATE);
        assertThat(result.tools().mcpToolTokensSource()).isEqualTo(TokenSource.ESTIMATE);
    }

    /**
     * [500 扣减判据 · 2/2] 本地估算 + 小工具集 ⇒ 不再被 {@code Math.max(0, raw-500)} 夹成<b>假 0</b>。
     *
     * <p><b>WHY（规则九）</b>：原实现对任何非 null 值都扣 500 再夹 0 ⇒ tiktoken 数出的 200 token
     * 工具集显示 0，被 {@code addCategory} 当成「确实没有这类工具」而整条省略 —— 面板上那一行
     * <b>直接消失</b>，用户既看不到真实值、也无处看到「— 不可用」。本用例钉死：本地估算下小工具集
     * 保留真值，且「内置工具（不含技能）」类别仍产出。若把扣减改回无条件，本用例变红（值 0 + categories 空）。
     */
    @Test
    @DisplayName("[500 判据] 本地估算 + 小工具集 → 不被 max(0,·) 夹成假 0（面板不整行消失）")
    void toolsSegment_localEstimate_tinySet_notClampedToZero() {
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 200, TokenSource.ESTIMATE);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("tiny", "t", SCHEMA, false)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.tools().builtInToolTokens())
            .as("本地估算不扣 500 ⇒ 保留真值 200，⛔ 不是被夹成的假 0")
            .isEqualTo(200);
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .as("有内容 ⇒ 扣减行类别必须出现（真 0 才会被省略）")
            .containsExactly("内置工具（不含技能）");
    }

    /**
     * [四态标注 · 产出策略重定] 「算不出来」的段必须仍然产出（否则前端无处渲染「—」）。
     *
     * <p><b>WHY（规则九）</b>：原策略 {@code tokens > 0} 才产出 category —— 提供商不提供精确计数时
     * 整条类别会被省略，面板上「工具」那一段直接消失，用户只看到少了一行，无从知道是「没有工具」
     * 还是「算不出来」。本用例钉死：不可用的 built-in 段 ⇒ 「内置工具（不含技能）」类别<b>仍然出现</b>且
     * {@code tokens=null} + {@code tokenSource=unavailable}。若把 addCategory 的省略条件改回
     * {@code tokens > 0}（或把 null 抹成 0），本用例变红。
     */
    @Test
    @DisplayName("[四态] 工具段算不出来 → 仍产出类别（tokens=null + unavailable），前端才有处渲染「—」")
    void toolsSegment_unavailable_stillProducesCategory() {
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> null); // 端点缺失 → 算不出来
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("read_file", "read", SCHEMA, false)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.tools().builtInToolTokens())
            .as("⛔ 不是 Math.max(0, 0-500)=0 的假 0")
            .isNull();
        assertThat(result.tools().builtInToolTokensSource()).isEqualTo(TokenSource.UNAVAILABLE);
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .contains("内置工具（不含技能）");
        assertThat(result.categories()).filteredOn(c -> c.name().equals("内置工具（不含技能）"))
            .singleElement()
            .satisfies(c -> {
                assertThat(c.tokens()).isNull();
                assertThat(c.tokenSource()).isEqualTo(TokenSource.UNAVAILABLE);
            });
    }

    // ════════════════════════════════════════════════════════════════════════
    // [ALIGN-HS-1 OQ-1 + IMP-F2-2 OPD-CM5-F-17 改不扣]：skill frontmatter 估算
    //  + 扣减值承载于 categories（builtInToolTokens 字段不扣）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY（规则九 · 验证意图）：CC analyzeContext.ts:554-614 countSkillTokens 对每个技能调
     * {@code estimateSkillFrontmatterTokens}（loadSkillsDir.ts:100-105 [name,description,whenToUse]
     * join(' ') → round(len/4)）累加求和（:994-997）。OPD-CM5-F-17 改不扣：响应字段
     * {@code builtInToolTokens} 对齐 CC（:501-514）返回全量，扣减（:1021
     * {@code systemToolsTokens = builtInToolTokens - skillFrontmatterTokens}）由 categories
     * 的「内置工具（不含技能）」类别承载（CC 原名 'System tools'，:1022-1029）。若 skill 段缺失（旧
     * AnalyzeContext.java 整类删除 → estimateSkillFrontmatterTokens 孤死），技能 frontmatter 的
     * 上下文 token 账会漏记，本测试钉死回补后的计量口径 + 扣减值在 categories 中的承载。
     */
    @Test
    @DisplayName("OQ-1+F-17: builtInToolTokens 全量不扣 + 扣减值承载于 categories（CC :1021-1029）")
    void skillSegment_frontmatterTokens_carriedInCategories() {
        // tools 数组返回 800，来源 API（服务端计数）→ 减 500 overhead → 300（builtIn 全量值）
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 800, TokenSource.API);
        Command skill = new Command();
        skill.setName("my-skill");
        skill.setDescription("does a thing");
        skill.setWhenToUse("when needed");
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("read_file", "read", SCHEMA, false)),
            List.of(skill));

        ContextAnalyzeResult result = svc.analyze(null, null);

        // frontmatter 文本 = "my-skill does a thing when needed" → round(len/4)
        int expected = (int) Math.round("my-skill does a thing when needed".length() / 4.0);
        assertThat(result.skill().skillFrontmatterTokens()).isEqualTo(expected);
        // [四态] (a3) 态：Skills 一栏恒为本地 round(len/4) 粗估，与 provider 无关 ⇒ 来源恒 estimate
        assertThat(result.skill().skillFrontmatterTokensSource()).isEqualTo(TokenSource.ESTIMATE);
        assertThat(result.skill().totalSkills()).isEqualTo(1);
        assertThat(result.skill().skillFrontmatter()).hasSize(1);
        assertThat(result.skill().skillFrontmatter().get(0).name()).isEqualTo("my-skill");
        // [IMP-F2-2 · OPD-CM5-F-17 改不扣] builtInToolTokens = 全量 300（不扣 skill，对齐 CC :501-514）
        assertThat(result.tools().builtInToolTokens()).isEqualTo(300);
        // 扣减值承载于 categories：「内置工具（不含技能）」= Math.max(0, 300 - expected)（CC :1021-1029）
        // 无 system/memory/mcp 原料 → 仅扣减行 +「技能」两类
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("内置工具（不含技能）", "技能");
        assertThat(result.categories()).filteredOn(c -> c.name().equals("内置工具（不含技能）"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(Math.max(0, 300 - expected));
        // [四态] 扣减合成的来源取「较弱」一方：工具段 api ∧ 技能段恒 estimate ⇒ 扣减行只能标 estimate
        assertThat(result.categories()).filteredOn(c -> c.name().equals("内置工具（不含技能）"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokenSource)
            .isEqualTo(TokenSource.ESTIMATE);
        assertThat(result.categories()).filteredOn(c -> c.name().equals("技能"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(expected);
        // MCP 组不受 skill 扣减影响
        assertThat(result.tools().mcpToolTokens()).isZero();
    }

    /**
     * WHY（规则九 · 验证意图）：CC analyzeContextUsage 的 categories（analyzeContext.ts:1007-1087）
     * 是 builtIn/skill 扣减的承载位（'System tools' = builtInToolTokens - skillFrontmatterTokens，
     * :1021-1029）。OPD-CM5-F-17 改不扣后，builtInToolTokens 字段保持全量，前端如需 CC 原值展示
     * 必须读 categories 的「内置工具（不含技能）」扣减值。本测试钉死全类别组装顺序、扣减值，以及
     * [UI 本地化] 类别名已是中文（有意偏离 CC 英文名，见 service buildCategories）。
     */
    @Test
    @DisplayName("F-17: categories 全类别组装（中文名）+「内置工具（不含技能）」扣减值（CC :1007-1087）")
    void categories_fullAssembly_systemToolsDeducted() {
        CountTokensClient client = new CountTokensClient() {
            @Override
            public Integer countTokens(String content) {
                return 5; // system 各 section + memory 各文件
            }

            @Override
            public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
                return 800; // 默认 sourceKind()=ESTIMATE（本地估算）→ 不扣 500，built-in 组与 MCP 组各 800
            }
        };
        Command skill = new Command();
        skill.setName("full-skill");
        skill.setDescription("a thing");
        skill.setWhenToUse("when used");
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(new MemoryFileEntry(".claude/CLAUDE.md", "claude", "## Memory\ncontent")),
            List.of(
                new ToolDefinition("read_file", "read", SCHEMA, false),
                new ToolDefinition("mcp__db", "query", SCHEMA, true)),
            List.of(skill));

        ContextAnalyzeResult result = svc.analyze(null, null);

        // 全量 builtInToolTokens（不扣 skill，CC :501-514）+ mcpToolTokens 独立；
        //   匿名客户端未覆写 sourceKind() ⇒ 默认 ESTIMATE ⇒ 不扣 500，原样 800
        assertThat(result.tools().builtInToolTokens()).isEqualTo(800);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(800);
        // 类别顺序对齐 CC :1010-1087（System prompt → System tools → MCP tools → Memory files →
        //   Skills），名称已本地化为中文（有意偏离 CC 英文名 —— ⛔ 不是漏抄）
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("系统提示词", "内置工具（不含技能）", "MCP 工具", "记忆文件", "技能");
        // 扣减行承载扣减值：builtInToolTokens(800) - skillFrontmatterTokens(expected)
        int expected = (int) Math.round("full-skill a thing when used".length() / 4.0);
        assertThat(result.categories()).filteredOn(c -> c.name().equals("内置工具（不含技能）"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(800 - expected);
        // 「技能」承载 skillFrontmatterTokens 汇总（CC :1082-1087）
        assertThat(result.categories()).filteredOn(c -> c.name().equals("技能"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(expected);
    }

    /**
     * WHY（规则九 · 验证意图）：CC analyzeContext.ts:591-593
     * {@code source = (skill.type === 'prompt' ? skill.source : 'plugin')}，且 {@code skill.source}
     * 为 SettingSource camelCase（constants.ts:7-21 userSettings/policySettings…）。旧实现
     * {@code name().toLowerCase()} 漂移为 {@code "user"/"policy_settings"}，与同一批次 SU-△-2
     * 遥测 {@code "userSettings"/"policySettings"} 自相矛盾（source 字符串两种表示并存）。本测试
     * 钉死 countSkillTokens 的 source 字段复用 {@code SkillLoadedEvent.skillSourceCcValue}
     * 精确映射（USER→userSettings / POLICY_SETTINGS→policySettings / 非 prompt→plugin）。
     */
    @Test
    @DisplayName("OQ-1: skill source 字段 CC camelCase（USER→userSettings / POLICY_SETTINGS→policySettings / 非 prompt→plugin）")
    void skillSegment_source_usesCcCamelCase() {
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 0); // tools 不参与 source 断言
        Command userSkill = new Command();
        userSkill.setName("user-skill");
        userSkill.setDescription("d");
        userSkill.setWhenToUse("w");
        userSkill.setSource(CommandSource.USER);
        Command policySkill = new Command();
        policySkill.setName("policy-skill");
        policySkill.setDescription("d");
        policySkill.setWhenToUse("w");
        policySkill.setSource(CommandSource.POLICY_SETTINGS);
        Command nonPrompt = new Command();
        nonPrompt.setName("local-jsx");
        nonPrompt.setType("local-jsx"); // 非 prompt 型 → source 恒 'plugin'（CC :593）

        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(), List.of(), List.of(userSkill, policySkill, nonPrompt));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.skill().skillFrontmatter()).extracting(SkillFrontmatterDetail::source)
            .containsExactly("userSettings", "policySettings", "plugin");
    }

    /**
     * [FIX-B2 拍板#4] 生产数据源：Spring 构造注入真实 {@link SkillRegistry}（CC
     * getLimitedSkillToolCommands(getCwd()) analyzeContext.ts:567 → prompt.ts:213-215），
     * countSkillTokens 不再 List.of() 空注入。
     *
     * <p><b>WHY（规则九）</b>：旧实现生产 skills=List.of() 空 → countSkillTokens 恒 {0,0,[]}
     * （NG-3 OQ-1 生产数据源空，web 端 skill frontmatter token 计量不可观测）。拍板#4 要求生产
     * 注入真实技能列表。本测试证明：注入含真实 SKILL.md 的 SkillRegistry → 生产构造器
     * {@code new ContextAnalyzeService(client, registry)} → countSkillTokens 解析真实技能
     * （totalSkills>0，skillFrontmatter 含该技能，token>0）。若生产回退回 List.of()，本测试变红。
     */
    @Test
    @DisplayName("FIX-B2: 生产 SkillRegistry 注入 → countSkillTokens 有真实数据（不再 {0,0,[]}）")
    void productionSkillRegistry_realSkills_makesCountSkillTokensNonEmpty(@TempDir Path tempDir) throws Exception {
        BundledSkills.clear(); // 隔离跨测试泄漏的 bundled 注册集（否则 totalSkills 混入 bundled 技能，非 1）
        Path skillsRoot = tempDir.resolve("skills");
        Files.createDirectories(skillsRoot.resolve("skill-a"));
        Files.writeString(skillsRoot.resolve("skill-a").resolve("SKILL.md"),
                "---\nname: skill-a\ndescription: does a thing\nwhen_to_use: when needed\n---\nbody\n");
        SkillRegistry registry = new SkillRegistry(skillsRoot.toString());
        // 真实计数器（不参与 skill 段断言）+ 生产构造器（systemContextSource=null → 懒建真实 provider）
        ContextAnalyzeService svc = new ContextAnalyzeService(FIXED_5, registry);

        ContextAnalyzeResult result = svc.analyze(null, null);

        // 生产解析真实技能列表（CC getLimitedSkillToolCommands）：totalSkills=1，明细含 skill-a
        assertThat(result.skill().totalSkills()).isEqualTo(1);
        assertThat(result.skill().skillFrontmatter()).extracting(SkillFrontmatterDetail::name)
            .containsExactly("skill-a");
        // frontmatter token = round("skill-a does a thing when needed".length / 4) > 0
        assertThat(result.skill().skillFrontmatterTokens()).isPositive();
        assertThat(result.skill().skillFrontmatter().get(0).tokens())
            .isEqualTo((int) Math.round("skill-a does a thing when needed".length() / 4.0));
    }

    /**
     * [FIX-B2 拍板#4] 空 skill registry → countSkillTokens 仍 {0,0,[]}（CC countSkillTokens
     * getLimitedSkillToolCommands 空列表 → skillFrontmatter []，analyzeContext.ts:597-604）。
     *
     * <p>WHY：生产数据源可加载空（无技能目录），必须短路为 {0,0,[]} 而非抛错；对齐 CC 空列表语义。
     */
    @Test
    @DisplayName("FIX-B2: 空 SkillRegistry → countSkillTokens {0,0,[]}（CC 空列表短路）")
    void productionSkillRegistry_emptyRegistry_shortCircuits(@TempDir Path tempDir) {
        BundledSkills.clear(); // 隔离跨测试泄漏的 bundled 注册集（否则空 registry 仍统计到 bundled 技能，非 0）
        SkillRegistry registry = new SkillRegistry(tempDir.resolve("nonexistent-skills").toString());
        ContextAnalyzeService svc = new ContextAnalyzeService(FIXED_5, registry);

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.skill().totalSkills()).isZero();
        assertThat(result.skill().skillFrontmatterTokens()).isZero();
        assertThat(result.skill().skillFrontmatter()).isEmpty();
    }

    /**
     * [四态标注] 技能清单<b>加载失败</b> → 不可用（not 0），且「技能」类别仍产出 —— 让技能行的
     * UNAVAILABLE 态在生产上真正可达。
     *
     * <p><b>WHY（规则九）</b>：CC analyzeContext.ts:605-613 的 catch 把加载失败返回成零值
     * （{@code {skillTokens: 0, skillInfo: {totalSkills: 0, includedSkills: 0, skillFrontmatter: []}}}），
     * 于是「加载失败」与「确实没有技能」在面板上完全同形 —— 技能段整节消失，用户无从知道这次没读到；
     * 上一轮改完四态后，技能行的 UNAVAILABLE 在生产上更是<b>结构不可达</b>（三条返回路径原先都非 null）。
     * 本用例钉死：catch 分支返回 {@code {tokens=null, source=unavailable}}，类别仍产出（前端才有处渲染
     * 「— 不可用」）。若把 catch 改回 {@code {0, 0, estimate, []}}，本用例变红。
     *
     * <p>totalSkills 一并置 null 的理由：加载失败时「一共有几个技能」同样未知，⛔ 写 0 会让面板出现
     * 「技能数 0 / 0」+「— 不可用」的自相矛盾组合（0 说「确实没有」，角标说「不知道」）。
     */
    @Test
    @DisplayName("[四态] 技能加载失败 → tokens/totalSkills=null + unavailable（⛔ 不是 0），技能类别仍产出")
    void skillSegment_loadFailure_markedUnavailable_notZero() {
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getModelInvocableCommands(null))
            .thenThrow(new IllegalStateException("技能清单加载失败（模拟）"));
        ContextAnalyzeService svc = new ContextAnalyzeService(FIXED_5, registry);

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.skill().skillFrontmatterTokens())
            .as("加载失败≠没有技能：⛔ 不是 0")
            .isNull();
        assertThat(result.skill().skillFrontmatterTokensSource()).isEqualTo(TokenSource.UNAVAILABLE);
        assertThat(result.skill().totalSkills())
            .as("连有几个技能都不知道 ⇒ null，⛔ 不是 0（否则面板出现「技能数 0 / 0」+「不可用」）")
            .isNull();
        assertThat(result.skill().skillFrontmatter()).isEmpty();
        // 类别仍产出（否则前端无处渲染「—」）
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .contains("技能");
        assertThat(result.categories()).filteredOn(c -> c.name().equals("技能"))
            .singleElement()
            .satisfies(c -> {
                assertThat(c.tokens()).isNull();
                assertThat(c.tokenSource()).isEqualTo(TokenSource.UNAVAILABLE);
            });
    }

    /**
     * [四态 · 成因] 分类行的「不可用」必须带**成因**下发（面板据此选悬停提示）。
     *
     * <p><b>WHY（规则九）</b>：同一个「—」在面板上的成因分两侧 —— 提供商侧（服务端计数接口失败）
     * 与本地（技能清单读不到）。技能加载失败时，同一个 {@code null} 被写进分类节的「技能」行与
     * 「内置工具（不含技能）」行（后者 = 全量 − 技能，扣减值，CC :1021）—— 若这两行不给成因，
     * 前端只能沿用通用文案「当前模型提供商不提供精确计数」，而同一屏的技能节行写着「本地没能读到
     * 技能清单」= 同一面板同一个数字两种互相矛盾的归因（用户裁定「两行一起改」）。
     *
     * <p>本用例一次逼出两侧成因：逐 section 计数恒 {@code null}（提供商侧）+ 技能清单加载抛错
     * （本地）。钉死技能行 = LOCAL、扣减行 = DERIVED（合成值，⛔ 不归因提供商）、提供商侧行 = PROVIDER。
     * 若 buildCategories 把成因传错或漏传（例如两行都标 PROVIDER），本用例变红。
     *
     * <p>⚠️ <b>fixture 修正（第 5 轮）</b>：本用例的客户端<b>必须显式声明
     * {@code sourceKind() == API}</b> 才代表「提供商侧」。原先写 lambda {@code content -> null}
     * —— lambda 未覆写 {@code sourceKind()} ⇒ 接口默认 {@link TokenSource#ESTIMATE}，即
     * <b>本地估算客户端</b>；本轮把「系统提示词 / MCP 工具 / 记忆文件」三行的成因改为按客户端分侧后，
     * 那个 fixture 描述的其实是「本地估算失败」这一侧，与它断言的 PROVIDER 自相矛盾。
     * 客户端换成本类（语义 = anthropic 计数客户端、端点缺失/调用失败），断言的意图（提供商侧 ⇒
     * PROVIDER）不变；本地估算那一侧由 {@code categories_estimateClient_*} 单独覆盖。
     */
    @Test
    @DisplayName("[四态·成因] 分类行下发成因：技能=local、扣减行=derived、提供商侧=provider")
    void categories_carryUnavailableCause_perSide() {
        // 服务端计数客户端（anthropic 路径）：端点缺失/调用失败 → 算不出来
        CountTokensClient nullApiClient = new CountTokensClient() {
            @Override
            public Integer countTokens(String content) {
                return null;
            }

            @Override
            public TokenSource sourceKind() {
                return TokenSource.API; // 显式声明：本用例要的是「提供商侧」那一侧
            }
        };
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.getModelInvocableCommands(null))
            .thenThrow(new IllegalStateException("技能清单加载失败（模拟）")); // 本地：读不到
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of("gitStatus", "clean"), nullApiClient,
            List.of(), List.of(), List.of(), registry, null, null);

        ContextAnalyzeResult result = svc.analyze(null, null);

        // 前提（否则下面的断言是空谈）：两侧确实都不可用
        assertThat(result.system().systemPromptTokens()).as("前提：提供商侧计数不可用").isNull();
        assertThat(result.skill().skillFrontmatterTokens()).as("前提：本地技能清单读不到").isNull();
        // 无 memory/mcp 原料 → 只剩这三行（系统提示词 / 扣减行 / 技能）
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("系统提示词", "内置工具（不含技能）", "技能");
        // 提供商侧的行：PROVIDER —— 面板用「提供商不提供精确计数」那句是**对的**
        assertThat(result.categories()).filteredOn(c -> c.name().equals("系统提示词"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::unavailableCause)
            .isEqualTo(ContextAnalyzeService.UnavailableCause.PROVIDER);
        // 本地成因的行：LOCAL（技能 frontmatter 恒本地 round(len/4)，与提供商无关）
        assertThat(result.categories()).filteredOn(c -> c.name().equals("技能"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::unavailableCause)
            .isEqualTo(ContextAnalyzeService.UnavailableCause.LOCAL);
        // 扣减/合成行：DERIVED —— ⛔ 不是 PROVIDER（否则面板又把成因推给提供商）
        assertThat(result.categories()).filteredOn(c -> c.name().equals("内置工具（不含技能）"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::unavailableCause)
            .as("合成值行不下单侧结论（成因可能在本地也可能在提供商侧）")
            .isEqualTo(ContextAnalyzeService.UnavailableCause.DERIVED);
    }

    /**
     * [四态 · 成因] 数值正常的分类行<b>不带</b>成因（wire 上省略该键）。
     *
     * <p><b>WHY</b>：成因只在「不可用」时有意义 —— 给正常行也塞一个，会让「有成因 ⟺ 不可用」这条
     * 锚定关系失效，前端也可能误按成因给正常行选文案。若 {@code addCategory} 不再把正常行的成因
     * 清成 {@code null}，本用例变红。
     */
    @Test
    @DisplayName("[四态·成因] 数值正常的分类行不带成因（unavailableCause=null）")
    void categories_normalRows_haveNoUnavailableCause() {
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 900, TokenSource.API);
        Command skill = new Command();
        skill.setName("some-skill");
        skill.setDescription("does a thing");
        skill.setWhenToUse("when needed");
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("read_file", "read", SCHEMA, false)),
            List.of(skill));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.categories()).isNotEmpty();
        assertThat(result.categories()).allSatisfy(c -> assertThat(c.unavailableCause())
            .as("数值正常（tokens=%s）的行不得带成因", c.tokens())
            .isNull());
    }

    /**
     * 三类原料全部让客户端回 {@code null} 的服务（用于「三行成因按客户端分侧」的成对用例）。
     *
     * <p>构造要点：{@code countTokens} 回 null 打到「系统提示词」的逐 section 计数与「记忆文件」的
     * 逐文件计数；{@code countTokensForTools} 回 null 打到 MCP 组计数（built-in 组空 → 结构上 0，
     * 该类别被「确切 0」省略）；空技能列表 → 「技能」行省略、扣减行 = max(0, 0-0) = 0 亦省略
     * ⇒ 分类恰好三行，正是要按行取成因的那三行。
     *
     * @param clientKind 该客户端的来源类别（API = 服务端计数；ESTIMATE = 本地估算）
     */
    private static ContextAnalyzeService nullCountingService(TokenSource clientKind) {
        CountTokensClient client = new CountTokensClient() {
            @Override
            public Integer countTokens(String content) {
                return null;
            }

            @Override
            public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
                return null;
            }

            @Override
            public TokenSource sourceKind() {
                return clientKind;
            }
        };
        return new ContextAnalyzeService(() -> Map.of("gitStatus", "clean"), client,
            List.of(new MemoryFileEntry(".claude/CLAUDE.md", "claude", "## Memory\ncontent")),
            List.of(new ToolDefinition("mcp__db", "query", SCHEMA, true)),
            List.of(), null, null, null);
    }

    /** 取名为 {@code rowName} 的那一行（全分类中唯一）的不可用成因。 */
    private static ContextAnalyzeService.UnavailableCause causeOf(ContextAnalyzeResult result, String rowName) {
        List<ContextAnalyzeService.ContextCategory> rows = result.categories().stream()
            .filter(c -> c.name().equals(rowName))
            .toList();
        assertThat(rows).as("分类应恰好含一行「%s」", rowName).hasSize(1);
        return rows.get(0).unavailableCause();
    }

    /**
     * [四态·成因 · 第 5 轮] <b>本地估算</b>客户端（非 anthropic 提供商装配的就是它）⇒
     * 「系统提示词 / MCP 工具 / 记忆文件」三行的成因 = {@code localEstimate}，⛔ 不是 {@code provider}。
     *
     * <p><b>WHY（规则九）</b>：这三行原先在 buildCategories 里写死 {@code PROVIDER}，而那只在装配
     * {@link com.nexusai.infra.llm.AnthropicCountTokensClient}（sourceKind=API）时成立。非 anthropic
     * 提供商走 {@link com.nexusai.infra.llm.OpenAICountTokensClient} 的本地 tiktoken、<b>全程无
     * HTTP</b> —— 这时三行显示「不可用」的成因是<b>本地估算出错</b>，面板却仍弹「当前模型提供商不提供
     * 精确计数」= 把用户指向一个结构上不可能的原因（正是用户最初那条病灶换到了别的行）。
     *
     * <p>变异判别力：把 {@code buildCategories} 的判据退回「一律 PROVIDER」，本用例变红
     * （拿到 PROVIDER ≠ LOCAL_ESTIMATE）；把判据写成「一律 LOCAL_ESTIMATE」则由
     * {@link #categories_apiClient_countedRowsAreProvider()} 变红。
     */
    @Test
    @DisplayName("[四态·成因] 本地估算客户端 → 三行成因=localEstimate（⛔ 不是 provider）")
    void categories_estimateClient_countedRowsAreLocalEstimate_notProvider() {
        ContextAnalyzeResult result = nullCountingService(TokenSource.ESTIMATE).analyze(null, null);

        // 前提（否则下面的成因断言是空谈）：三行确实都不可用
        assertThat(result.system().systemPromptTokens()).as("前提：系统提示词算不出来").isNull();
        assertThat(result.memory().claudeMdTokens()).as("前提：记忆文件算不出来").isNull();
        assertThat(result.tools().mcpToolTokens()).as("前提：MCP 工具算不出来").isNull();
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("系统提示词", "MCP 工具", "记忆文件");

        for (String row : List.of("系统提示词", "MCP 工具", "记忆文件")) {
            assertThat(causeOf(result, row))
                .as("非 anthropic 提供商下「%s」行走本地估算 ⇒ 算不出来是本地估算出错，"
                    + "⛔ 不得标成提供商侧（那会让面板说「当前模型提供商不提供精确计数」）", row)
                .isEqualTo(ContextAnalyzeService.UnavailableCause.LOCAL_ESTIMATE);
        }
        // wire 契约（前端联合类型的真源）：与技能行的 local 必须是两个不同的值 —— 两件事不共用一个值
        assertThat(ContextAnalyzeService.UnavailableCause.LOCAL_ESTIMATE.wire()).isEqualTo("localEstimate");
        assertThat(ContextAnalyzeService.UnavailableCause.LOCAL_ESTIMATE.wire())
            .as("localEstimate（本地估算出错）与 local（读不到技能清单）是两件事，⛔ 不得同值")
            .isNotEqualTo(ContextAnalyzeService.UnavailableCause.LOCAL.wire());
    }

    /**
     * [四态·成因 · 第 5 轮] 反向面：<b>服务端计数</b>客户端（anthropic 路径）⇒ 同三行的成因 = {@code provider}。
     *
     * <p><b>WHY（规则九）</b>：按客户端分侧不能变成「一律本地」—— 走 anthropic count_tokens 的段，
     * 算不出来确实是提供商侧的事（第三方 anthropic 兼容商没有该端点 / 调用失败）。若判据被写成
     * 「一律 LOCAL_ESTIMATE」，本用例变红；若被写成「一律 PROVIDER」，
     * {@link #categories_estimateClient_countedRowsAreLocalEstimate_notProvider()} 变红。
     */
    @Test
    @DisplayName("[四态·成因] 服务端计数客户端 → 三行成因=provider（分侧的另一个方向）")
    void categories_apiClient_countedRowsAreProvider() {
        ContextAnalyzeResult result = nullCountingService(TokenSource.API).analyze(null, null);

        assertThat(result.system().systemPromptTokens()).as("前提：系统提示词算不出来").isNull();
        assertThat(result.memory().claudeMdTokens()).as("前提：记忆文件算不出来").isNull();
        assertThat(result.tools().mcpToolTokens()).as("前提：MCP 工具算不出来").isNull();
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("系统提示词", "MCP 工具", "记忆文件");

        for (String row : List.of("系统提示词", "MCP 工具", "记忆文件")) {
            assertThat(causeOf(result, row))
                .as("走服务端计数的「%s」行算不出来 = 提供商侧（计数接口缺失/调用失败）", row)
                .isEqualTo(ContextAnalyzeService.UnavailableCause.PROVIDER);
        }
    }

    /**
     * [IMP-CM-16 · OPD-CM3-05/A03] 生产构造：memory 段接 {@link ClaudemdEngine#getMemoryFiles} +
     * filterInjectedMemoryFiles（CC analyzeContext.ts:329），tools 段接 {@link ToolRegistry#getTools}
     * （CC buildAllTools print.ts:1474-1500）→ /context analyze 返回真实上下文用量。
     *
     * <p><b>WHY（规则九）</b>：旧实现生产构造 {@code this(null, countTokensClient, List.of(), List.of(),
     * List.of(), skillRegistry)} → claudeMdTokens / memoryFiles / builtInToolTokens / mcpToolTokens
     * 恒 0（F DELTA-2）。拍板 A03 要求生产注入真实 memory/tools 源。本测试证明：注入真实
     * ClaudemdEngine（含 memory 文件）+ ToolRegistry（含 built-in 与 MCP 工具）→ 生产构造器解析
     * 真实原料（claudeMdTokens&gt;0 / memoryFiles 非空 / builtInToolTokens&gt;0 / mcpToolTokens&gt;0）。
     * 若生产回退 List.of() 空注入，本测试变红。
     */
    @Test
    @DisplayName("IMP-CM-16: 生产构造接 ClaudemdEngine+ToolRegistry → memory/tools 真实计数（不再恒 0）")
    void productionConstructor_wiredToClaudemdEngineAndToolRegistry_memoryAndToolsNonEmpty() {
        // memory 源：真实 ClaudemdEngine（getMemoryFiles(false, sessionId) + filterInjectedMemoryFiles，
        //   mothCopse 关 → 原样返回）
        MemoryFileInfo mem = MemoryFileInfo.of("/repo/CLAUDE.md", ClaudemdMemoryType.PROJECT,
            "## project\ncontent", null);
        ClaudemdEngine engine = mock(ClaudemdEngine.class);
        // [批 3c] 无会话 → 显式 null：生产 ContextAnalyzeService.resolveMemoryFiles 调
        //   getMemoryFiles(false, null)（analyze 端点无会话入参），stub 必须同参
        when(engine.getMemoryFiles(false, null)).thenReturn(List.of(mem));
        when(engine.filterInjectedMemoryFiles(any())).thenAnswer(inv -> inv.getArgument(0));

        // tools 源：真实 ToolRegistry（注册 built-in + MCP 工具各一，经 getTools(null) 投影）。
        // Mockito mock（非匿名实现）→ 无需实现 execute(ToolUseBlock)；isMcp()/isEnabled() 显式 stub。
        ToolRegistry registry = new ToolRegistry();
        Tool builtIn = mock(Tool.class);
        when(builtIn.name()).thenReturn("read_file");
        when(builtIn.description()).thenReturn("read");
        when(builtIn.inputSchema()).thenReturn(SCHEMA);
        when(builtIn.isEnabled()).thenReturn(true);
        when(builtIn.isMcp()).thenReturn(false);
        Tool mcpTool = mock(Tool.class);
        when(mcpTool.name()).thenReturn("mcp__tool");
        when(mcpTool.description()).thenReturn("mcp");
        when(mcpTool.inputSchema()).thenReturn(SCHEMA);
        when(mcpTool.isEnabled()).thenReturn(true);
        when(mcpTool.isMcp()).thenReturn(true);
        registry.register(builtIn);
        registry.register(mcpTool);

        // 真实计数器：memory 段 7/文件，tools 段 800（匿名客户端未覆写 sourceKind() ⇒ 默认 ESTIMATE
        //   = 本地估算 ⇒ 不扣 TOOL_TOKEN_COUNT_OVERHEAD=500，两组各 800）
        CountTokensClient client = new CountTokensClient() {
            @Override public Integer countTokens(String content) { return 7; }
            @Override public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) { return 800; }
        };
        // 生产构造器（Spring 4 参：client + null skillRegistry + claudemdEngine + toolRegistry）
        ContextAnalyzeService svc = new ContextAnalyzeService(client, null, engine, registry);

        ContextAnalyzeResult result = svc.analyze(null, null);

        // memory 段：真实 ClaudemdEngine 文件 → claudeMdTokens=7、明细含 path/type='Project'/tokens=7
        assertThat(result.memory().claudeMdTokens()).isEqualTo(7);
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::path)
            .containsExactly("/repo/CLAUDE.md");
        assertThat(result.memory().memoryFiles()).extracting(MemoryFileDetail::type)
            .containsExactly("Project");
        // tools 段：built-in 组 + MCP 组各一次 bulk 调用（本地估算不扣 500 → 各 800），
        //   built-in 不被 skill 扣减（无技能）
        assertThat(result.tools().builtInToolTokens()).isEqualTo(800);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(800);
    }

    /**
     * [REWORK 回归] 生产路径（无注入 supplier）重复 analyze 不得向
     * {@link SystemPromptInjection#CACHE_CLEAR_HOOKS} 每请求泄漏一个缓存清理回调。
     *
     * <p><b>WHY (CLAUDE.md 规则九)</b>：reflector 独立核验发现——SystemPromptContextProvider 构造即
     * 向 {@code SystemPromptInjection.CACHE_CLEAR_HOOKS} 静态表注册缓存清理回调，且该表无 remove 路径
     * （SystemPromptInjection.java:32/68-73）。旧实现每次 analyze 新建 provider → 每次请求永久泄漏一个
     * Runnable。修复后服务实例懒建<b>单实例</b> provider 并缓存复用（对齐 CC getSystemContext 进程级
     * memoize）→ 仅首次调用 +1 hook，后续调用 +0。若业务逻辑退化回"每请求新建 provider"，本测试变红。
     */
    @Test
    @DisplayName("REWORK: 生产路径重复 analyze 仅注册 1 个 cache-clear hook（不随请求数泄漏）")
    void productionPath_repeatedAnalyze_registersSingleCacheHook() throws Exception {
        Field hooksField = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        hooksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> hooks = (List<Runnable>) hooksField.get(null);
        int before = hooks.size();

        ContextAnalyzeService svc = new ContextAnalyzeService(FIXED_5, null); // 生产构造：systemContextSource=null, skillRegistry=null
        svc.analyze(null, null);
        int afterFirst = hooks.size();
        svc.analyze(null, null);
        int afterSecond = hooks.size();

        // 首次调用懒建 provider → +1 hook；第二次复用缓存 provider → +0（无泄漏）
        assertThat(afterFirst - before)
            .as("首次 analyze 懒建单实例 provider，注册 1 个 cache-clear hook")
            .isEqualTo(1);
        assertThat(afterSecond - afterFirst)
            .as("第二次 analyze 复用缓存 provider，不得再注册 hook（旧实现此处 +1 泄漏）")
            .isZero();
    }
}

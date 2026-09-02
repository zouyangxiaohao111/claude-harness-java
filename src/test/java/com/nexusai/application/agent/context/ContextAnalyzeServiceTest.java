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

        RecordingClient(Function<String, Integer> fn) {
            this.fn = fn;
        }

        @Override
        public Integer countTokens(String content) {
            calls.add(content);
            return fn.apply(content);
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
        // 真实客户端以 memory 文件 content 为入参（非 rough 估算）
        assertThat(client.calls()).anyMatch(c -> c.contains("## Memory"));
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

    /** 记录 tools 数组调用的客户端 · RES-C9 验证 tools 数组路径（非文本）+ overhead 补偿。 */
    private static final class ToolsRecordingClient implements CountTokensClient {
        private final List<List<CountTokensClient.ToolSchema>> toolsCalls = new ArrayList<>();
        private final java.util.function.Function<List<CountTokensClient.ToolSchema>, Integer> toolsFn;

        ToolsRecordingClient(java.util.function.Function<List<CountTokensClient.ToolSchema>, Integer> toolsFn) {
            this.toolsFn = toolsFn;
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
        // tools 数组入参为真实 schema（含工具名），非 JSON 字符串
        assertThat(client.toolsCalls().get(0)).extracting(CountTokensClient.ToolSchema::name)
            .containsExactly("read_file", "bash");
        assertThat(client.toolsCalls().get(1)).extracting(CountTokensClient.ToolSchema::name)
            .containsExactly("mcp__tool");
    }

    @Test
    @DisplayName("C9: overhead 补偿 ≤ 0 时 clamp 为 0（CC Math.max(0, raw - 500)）")
    void toolsSegment_overheadExceedsRaw_clampedToZero() {
        // 返回 200 → 200 - 500 = -300 → clamp 0
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 200);
        ContextAnalyzeService svc = new ContextAnalyzeService(() -> Map.of(), client,
            List.of(),
            List.of(new ToolDefinition("tiny", "t", SCHEMA, false)));

        ContextAnalyzeResult result = svc.analyze(null, null);

        assertThat(result.tools().builtInToolTokens()).isZero(); // 200 - 500 → 0
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
     * 的 'System tools' 类别承载（:1022-1029）。若 skill 段缺失（旧 AnalyzeContext.java 整类删除 →
     * estimateSkillFrontmatterTokens 孤死），技能 frontmatter 的上下文 token 账会漏记，本测试
     * 钉死回补后的计量口径 + 扣减值在 categories 中的承载。
     */
    @Test
    @DisplayName("OQ-1+F-17: builtInToolTokens 全量不扣 + 扣减值承载于 categories（CC :1021-1029）")
    void skillSegment_frontmatterTokens_carriedInCategories() {
        // tools 数组返回 800 → 减 500 overhead → 300（builtIn 全量值）
        ToolsRecordingClient client = new ToolsRecordingClient(tools -> 800);
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
        assertThat(result.skill().totalSkills()).isEqualTo(1);
        assertThat(result.skill().skillFrontmatter()).hasSize(1);
        assertThat(result.skill().skillFrontmatter().get(0).name()).isEqualTo("my-skill");
        // [IMP-F2-2 · OPD-CM5-F-17 改不扣] builtInToolTokens = 全量 300（不扣 skill，对齐 CC :501-514）
        assertThat(result.tools().builtInToolTokens()).isEqualTo(300);
        // 扣减值承载于 categories：'System tools' = Math.max(0, 300 - expected)（CC :1021-1029）
        // 无 system/memory/mcp 原料 → 仅 'System tools' + 'Skills' 两类
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("System tools", "Skills");
        assertThat(result.categories()).filteredOn(c -> c.name().equals("System tools"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(Math.max(0, 300 - expected));
        assertThat(result.categories()).filteredOn(c -> c.name().equals("Skills"))
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
     * 必须读 categories 的 'System tools' 扣减值。本测试钉死全类别组装顺序与扣减值（System prompt
     * → System tools → MCP tools → Memory files → Skills，:1010-1087）。
     */
    @Test
    @DisplayName("F-17: categories 全类别组装 + 'System tools' 扣减值（CC :1007-1087）")
    void categories_fullAssembly_systemToolsDeducted() {
        CountTokensClient client = new CountTokensClient() {
            @Override
            public Integer countTokens(String content) {
                return 5; // system 各 section + memory 各文件
            }

            @Override
            public Integer countTokensForTools(List<CountTokensClient.ToolSchema> tools) {
                return 800; // built-in 组 + MCP 组各一次 bulk → 各 300
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

        // 全量 builtInToolTokens（不扣 skill，CC :501-514）+ mcpToolTokens 独立
        assertThat(result.tools().builtInToolTokens()).isEqualTo(300);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(300);
        // 类别顺序对齐 CC :1010-1087（System prompt → System tools → MCP tools → Memory files → Skills）
        assertThat(result.categories()).extracting(ContextAnalyzeService.ContextCategory::name)
            .containsExactly("System prompt", "System tools", "MCP tools", "Memory files", "Skills");
        // 'System tools' 承载扣减值：builtInToolTokens(300) - skillFrontmatterTokens(expected)
        int expected = (int) Math.round("full-skill a thing when used".length() / 4.0);
        assertThat(result.categories()).filteredOn(c -> c.name().equals("System tools"))
            .singleElement()
            .extracting(ContextAnalyzeService.ContextCategory::tokens)
            .isEqualTo(300 - expected);
        // 'Skills' 承载 skillFrontmatterTokens 汇总（CC :1082-1087）
        assertThat(result.categories()).filteredOn(c -> c.name().equals("Skills"))
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
        // memory 源：真实 ClaudemdEngine（getMemoryFiles(false) + filterInjectedMemoryFiles，mothCopse 关 → 原样返回）
        MemoryFileInfo mem = MemoryFileInfo.of("/repo/CLAUDE.md", ClaudemdMemoryType.PROJECT,
            "## project\ncontent", null);
        ClaudemdEngine engine = mock(ClaudemdEngine.class);
        when(engine.getMemoryFiles(false)).thenReturn(List.of(mem));
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

        // 真实计数器：memory 段 7/文件，tools 段 800（→ 扣 TOOL_TOKEN_COUNT_OVERHEAD=500 → 300）
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
        // tools 段：built-in 组 + MCP 组各一次 bulk 调用（800-500=300），built-in 不被 skill 扣减（无技能）
        assertThat(result.tools().builtInToolTokens()).isEqualTo(300);
        assertThat(result.tools().mcpToolTokens()).isEqualTo(300);
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

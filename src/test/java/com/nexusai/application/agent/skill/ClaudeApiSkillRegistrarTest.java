package com.nexusai.application.agent.skill;

import com.nexusai.model.command.PromptFnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ALIGN-BUNDLED-2（R2B-DEC-17 / BD-5）claude-api 生产内容对齐测试（RED→GREEN）·
 * 对齐 CC claudeApi.ts（196 行）+ claudeApiContent.ts（75 行）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>生产注册必须用真实内容实现</b>——旧 Bootstrapper 匿名桩（SKILL_FILES=Map.of()、
 *       SKILL_PROMPT="# Claude API"，探查 M3.16 空文档桩）使 /claude-api 输出空文档。本测试经
 *       {@link ClaudeApiSkillContent#getInstance()} 直连注册链，断言 prompt 含 25 个 {@code <doc>}
 *       路径与阅读指南（含 {@code {lang}} 替换），若有人回退成空桩此断言必红。</li>
 *   <li><b>detectLanguage 必须用运行时 cwd 真实 readdir</b>（CC claudeApi.ts:37-39
 *       readdir(getCwd())）——旧 Bootstrapper 传 {@code List::of} 恒空 → detectLanguage 恒 null →
 *       恒"全文档+询问"分支。① 断言真实目录（pom.xml）检测为 java；② 断言无会话 cwd（null）回落
 *       注入 supplier 的既有契约不破坏。</li>
 *   <li><b>processContent 语义</b>——HTML 注释循环剥离 + {{var}} 缺 key 保留原样（CC claudeApi.ts:64-80），
 *       用带 {{OPUS_ID}} 的测试内容断言替换与保留两个边界。</li>
 *   <li><b>内容结构防漂移</b>——SKILL_FILES 25 键（顺序同 CC Record 字面量）+ SKILL_MODEL_VARS 7 值
 *       逐项断言（claudeApiContent.ts:36-75）；正文为显式 N/A marker（CC .md 源 checkout 缺失，DEC-15
 *       同处置不伪造），键结构变更此断言必红。</li>
 * </ol>
 */
class ClaudeApiSkillRegistrarTest {

    @TempDir
    Path tempDir;

    /** 构造注册器：测试注入空 entries supplier（无会话 cwd 回落路径）。 */
    private ClaudeApiSkillRegistrar registrarWith(ClaudeApiSkillRegistrar.SkillContent content) {
        return new ClaudeApiSkillRegistrar(List::of, () -> content);
    }

    @Test
    @DisplayName("detectLanguage(cwd) 真实 readdir：pom.xml → java（CC claudeApi.ts:35-53 A5 golden trace）")
    void detectLanguageReadsRealCwdEntries() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("src").resolve("Main.java"), "");

        ClaudeApiSkillRegistrar registrar = registrarWith(ClaudeApiSkillContent.getInstance());

        assertThat(registrar.detectLanguage(tempDir.toString()))
            .as("pom.xml 在 cwd → java（CC LANGUAGE_INDICATORS java: ['.java','pom.xml','build.gradle']）")
            .isEqualTo(ClaudeApiSkillRegistrar.DetectedLanguage.JAVA);
    }

    @Test
    @DisplayName("detectLanguage(cwd) readdir 失败 → null（CC claudeApi.ts:38-40 catch → 全文档分支）")
    void detectLanguageMissingCwdReturnsNull() {
        ClaudeApiSkillRegistrar registrar = registrarWith(ClaudeApiSkillContent.getInstance());
        assertThat(registrar.detectLanguage(tempDir.resolve("not-exist").toString())).isNull();
    }

    @Test
    @DisplayName("detectLanguage(null) 回落注入 supplier（测试确定性契约不破坏）")
    void detectLanguageNullCwdFallsBackToInjectedSupplier() {
        ClaudeApiSkillRegistrar registrar = new ClaudeApiSkillRegistrar(
            () -> List.of("go.mod"), () -> ClaudeApiSkillContent.getInstance());
        assertThat(registrar.detectLanguage(null))
            .isEqualTo(ClaudeApiSkillRegistrar.DetectedLanguage.GO);
    }

    @Test
    @DisplayName("生产主链：真实内容 + java cwd → prompt 含 <doc> 文档块 + {lang} 替换 + User Request（CC buildPrompt）")
    void registerPromptWithRealContentAndJavaCwd() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        ClaudeApiSkillRegistrar registrar = registrarWith(ClaudeApiSkillContent.getInstance());
        BundledSkillDefinition def = registrar.register();

        List<PromptBlock> blocks = def.getPromptForCommand().apply("怎么用 tool use",
            PromptFnContext.of(tempDir.toString(), List.of(), null));
        String prompt = String.join("\n\n", blocks.stream().map(PromptBlock::text).toList());

        // lang=java → 仅 java/* + shared/* 文档（CC getFilesForLanguage claudeApi.ts:55-60）
        assertThat(prompt)
            .contains("<doc path=\"java/claude-api.md\">")
            .contains("<doc path=\"shared/error-codes.md\">")
            .doesNotContain("<doc path=\"python/claude-api/README.md\">");
        // 阅读指南 {lang} 替换（CC buildPrompt claudeApi.ts:144-146 replace(/\{lang\}/g)）
        assertThat(prompt)
            .contains("→ Refer to `java/claude-api/README.md`")
            .doesNotContain("{lang}");
        // 结构段：basePrompt → reading guide → Included Documentation → User Request（CC :132-173）
        assertThat(prompt)
            .contains("## Reference Documentation")
            .contains("## Included Documentation")
            .contains("## User Request\n\n怎么用 tool use");
    }

    @Test
    @DisplayName("无 cwd（null）→ 全文档 + 询问用户分支（CC claudeApi.ts:149-158）")
    void registerPromptWithoutCwdIncludesAllDocsAndAskUser() {
        ClaudeApiSkillRegistrar registrar = registrarWith(ClaudeApiSkillContent.getInstance());
        BundledSkillDefinition def = registrar.register();

        List<PromptBlock> blocks = def.getPromptForCommand().apply("",
            PromptFnContext.of(null, List.of(), null));
        String prompt = String.join("\n\n", blocks.stream().map(PromptBlock::text).toList());

        assertThat(prompt)
            .contains("No project language was auto-detected. Ask the user which language they are using")
            .contains("<doc path=\"python/claude-api/README.md\">")
            .contains("<doc path=\"typescript/claude-api/tool-use.md\">")
            .contains("→ Refer to `unknown/claude-api/README.md`");
    }

    @Test
    @DisplayName("processContent：HTML 注释循环剥离 + {{var}} 替换，未知 key 保留（CC claudeApi.ts:62-80）")
    void processContentStripsCommentsAndSubstitutesVars() {
        ClaudeApiSkillRegistrar.SkillContent content = new ClaudeApiSkillRegistrar.SkillContent() {
            public Map<String, String> SKILL_FILES() { return Map.of(); }
            public String SKILL_PROMPT() { return "unused"; }
            public Map<String, String> SKILL_MODEL_VARS() {
                return Map.of("OPUS_ID", "claude-opus-4-6");
            }
        };

        // 期望值经 node 实测 CC 正则语义（claudeApi.ts:64-70）：
        //   'keep <!--c1--> {{OPUS_ID}} <!-- <!--c2--> --> {{UNKNOWN_VAR}}'
        //   → 循环剥离：'<!--c1-->' + '<!-- <!--c2-->'（lazy 至首个 '-->'）→ 'keep  {{OPUS_ID}}  --> {{UNKNOWN_VAR}}'
        //   → 替换：'keep  claude-opus-4-6  --> {{UNKNOWN_VAR}}'
        String out = registrarWith(content).processContent(
            "keep <!--c1--> {{OPUS_ID}} <!-- <!--c2--> --> {{UNKNOWN_VAR}}", content);

        assertThat(out)
            .isEqualTo("keep  claude-opus-4-6  --> {{UNKNOWN_VAR}}")
            .as("嵌套注释循环剥离（CC :64-70）+ {{var}} 缺 key 保留原样（CC :77-82）");
    }

    @Test
    @DisplayName("ClaudeApiSkillContent 结构对齐 claudeApiContent.ts：25 键（CC 字面量顺序）+ 7 模型常量 + SKILL_PROMPT 非空")
    void contentStructureMatchesCcLiteral() {
        ClaudeApiSkillContent content = ClaudeApiSkillContent.getInstance();

        assertThat(content.SKILL_FILES().keySet()).containsExactly(
            "csharp/claude-api.md",
            "curl/examples.md",
            "go/claude-api.md",
            "java/claude-api.md",
            "php/claude-api.md",
            "python/agent-sdk/README.md",
            "python/agent-sdk/patterns.md",
            "python/claude-api/README.md",
            "python/claude-api/batches.md",
            "python/claude-api/files-api.md",
            "python/claude-api/streaming.md",
            "python/claude-api/tool-use.md",
            "ruby/claude-api.md",
            "shared/error-codes.md",
            "shared/live-sources.md",
            "shared/models.md",
            "shared/prompt-caching.md",
            "shared/tool-use-concepts.md",
            "typescript/agent-sdk/README.md",
            "typescript/agent-sdk/patterns.md",
            "typescript/claude-api/README.md",
            "typescript/claude-api/batches.md",
            "typescript/claude-api/files-api.md",
            "typescript/claude-api/streaming.md",
            "typescript/claude-api/tool-use.md");

        Map<String, String> vars = content.SKILL_MODEL_VARS();
        assertThat(vars).hasSize(7);
        assertThat(vars.get("OPUS_ID")).isEqualTo("claude-opus-4-6");
        assertThat(vars.get("OPUS_NAME")).isEqualTo("Claude Opus 4.6");
        assertThat(vars.get("SONNET_ID")).isEqualTo("claude-sonnet-4-6");
        assertThat(vars.get("SONNET_NAME")).isEqualTo("Claude Sonnet 4.6");
        assertThat(vars.get("HAIKU_ID")).isEqualTo("claude-haiku-4-5");
        assertThat(vars.get("HAIKU_NAME")).isEqualTo("Claude Haiku 4.5");
        assertThat(vars.get("PREV_SONNET_ID")).isEqualTo("claude-sonnet-4-5");

        assertThat(content.SKILL_PROMPT()).isNotBlank();
    }
}

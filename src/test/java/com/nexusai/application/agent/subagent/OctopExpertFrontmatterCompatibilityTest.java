package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Octop 内置专家（272 个）与本仓 agent md 加载器的兼容性测试。
 *
 * <h2>WHY（意图验证）</h2>
 * <p>272 个专家是 Octop 上游（TencentCloud/Octop，MIT）的中文 agent md，只作为 classpath 资源随包内置，
 * 默认不激活。它们必须能被生产加载链（{@code loadAgentsDir.load → ParseSkillFrontmatter → parseAgentFromMarkdown}）
 * 原样解析，否则用户在市场安装单个专家后会静默消失。本用例锁定三件事：
 * <ol>
 *   <li>8 个跨分类样例（含 tools / services / author 额外键）解析出正确的 name / description / tools / 正文；</li>
 *   <li>272 个全量资源的 frontmatter 必填字段非空、正文非空；</li>
 *   <li>name 全局唯一（272 个），且不含 U+2E80–U+2FFF 区异体字（含已修的 U+2F8F）。</li>
 * </ol>
 *
 * <p>样例落盘使用 {@link TempDir}，不碰本机 {@code ~/.nexusai/agents}。
 */
@DisplayName("[octop-experts] 内置专家 frontmatter 兼容性")
class OctopExpertFrontmatterCompatibilityTest {

    private static final String SAMPLE_PATTERN = "classpath*:octop-experts/*.md";
    private static final String EXPERT_PATTERN = "classpath*:experts/octop/*/*.md";

    @TempDir
    Path root;

    private static List<Resource> resolve(String pattern) throws Exception {
        return Arrays.asList(new PathMatchingResourcePatternResolver().getResources(pattern));
    }

    private static boolean hasCjkRadicalSupplement(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x2E80 && cp <= 0x2FFF);
    }

    @Test
    @DisplayName("8 个跨分类样例可被生产加载器解析（name/description/tools/正文）")
    void samplesParseThroughProductionLoader() throws Exception {
        Path agentsDir = root.resolve("agents");
        Files.createDirectories(agentsDir);

        List<Resource> samples = resolve(SAMPLE_PATTERN);
        assertThat(samples).as("样例数量").hasSize(8);
        for (Resource sample : samples) {
            try (InputStream in = sample.getInputStream()) {
                Files.write(agentsDir.resolve(Objects.requireNonNull(sample.getFilename())), in.readAllBytes());
            }
        }

        // 走生产单源加载路径：baseDir/agents/**/*.md → ParseSkillFrontmatter → parseAgentFromMarkdown
        Map<String, AgentDefinition> loaded = loadAgentsDir.load(root, "userSettings");

        assertThat(loaded).hasSize(8);
        assertThat(loaded).containsKeys(
            "后端架构师", "代码审查员", "应用安全工程师", "SEO 专家",
            "营销轮播增长引擎", "付费媒体审计员", "提示词工程师（专项）", "行为助推引擎");
        assertThat(loaded).as("重名已消解，不应再出现裸的「提示词工程师」").doesNotContainKey("提示词工程师");

        for (AgentDefinition def : loaded.values()) {
            assertThat(def.whenToUse()).as("%s description", def.agentType()).isNotBlank();
            assertThat(def.getSystemPrompt(null, List.of())).as("%s 正文", def.agentType()).isNotBlank();
            assertThat(def.source()).isEqualTo("userSettings");
        }

        AgentDefinition architect = loaded.get("后端架构师");
        assertThat(architect.color()).contains("blue");
        assertThat(architect.tools()).as("未声明 tools 的专家应保持 Optional.empty（=全部工具）").isEmpty();

        AgentDefinition seo = loaded.get("SEO 专家");
        assertThat(seo.tools().orElseThrow()).contains("WebFetch", "WebSearch", "Read", "Write", "Edit");

        AgentDefinition auditor = loaded.get("付费媒体审计员");
        assertThat(auditor.tools().orElseThrow()).contains("Bash");

        AgentDefinition nudge = loaded.get("行为助推引擎");
        String nudgePrompt = nudge.getSystemPrompt(null, List.of());
        assertThat(nudgePrompt).doesNotContain("\u2F8F");
        assertThat(hasCjkRadicalSupplement(nudgePrompt)).as("行为助推引擎正文不含异体字").isFalse();
    }

    @Test
    @DisplayName("272 个全量专家：必填字段非空、正文非空、name 唯一、无异体字")
    void allBundledExpertsAreValidAndUnique() throws Exception {
        List<Resource> resources = resolve(EXPERT_PATTERN);
        assertThat(resources).as("内置专家文件数").hasSize(272);

        Set<String> names = new TreeSet<>();
        for (Resource resource : resources) {
            String fileName = Objects.requireNonNull(resource.getFilename());
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            assertThat(hasCjkRadicalSupplement(text))
                .as("%s 含 U+2E80–U+2FFF 区异体字", fileName)
                .isFalse();

            ParseSkillFrontmatter.ParsedMarkdown parsed =
                ParseSkillFrontmatter.parseFrontmatterStatic(text, fileName);
            assertThat(parsed.frontmatter()).as("%s frontmatter", fileName).isNotEmpty();

            Object name = parsed.frontmatter().get("name");
            Object description = parsed.frontmatter().get("description");
            assertThat(name).as("%s 缺 name", fileName).isInstanceOf(String.class);
            assertThat(((String) name).trim()).as("%s name 为空", fileName).isNotEmpty();
            assertThat(description).as("%s 缺 description", fileName).isInstanceOf(String.class);
            assertThat(description.toString().trim()).as("%s description 为空", fileName).isNotEmpty();
            String body = parsed.content().replaceFirst("(?s)^\\s*<!--.*?-->", "").trim();
            assertThat(body).as("%s 正文为空（去掉来源注释后）", fileName).isNotEmpty();

            names.add(((String) name).trim());
        }

        assertThat(names).as("frontmatter name 唯一计数").hasSize(272);
        assertThat(names).contains(
            "行为助推引擎",
            "提示词工程师（工程）", "提示词工程师（专项）",
            "B站内容策略师（内容）", "B站内容策略师（运营）");
        assertThat(names.stream().noneMatch(n -> n.contains("\u2F8F"))).isTrue();
    }
}

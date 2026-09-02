package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P3-7] SkillImprovementHook 三个语义微调测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/skillImprovement.ts:252/:263} +
 * {@code model.ts:36-38/:131-138} + {@code messages.ts:633-687}.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 与 Java 在三个细节上偏离, 每项偏离都会产生错误行为:
 * <ul>
 *   <li><b>extractTag 空内容不跳过</b> (旧共享 TAG_PATTERN): {@code <updates></updates><updates>real</updates>}
 *       旧实现返回 "" (空串) → parseResponse 误把空标签当「无更新」; CC 跳空继续找后续非空, 返回 "real".</li>
 *   <li><b>extractTag 大小写敏感</b>: LLM 输出标签大小写不可控, 旧实现 {@code <Updates>x</Updates>} + tagName="updates"
 *       返回 null → 检测器误判无更新; CC 'gi' 不敏感返回 "x".</li>
 *   <li><b>写回 .trim() 破坏字节</b> (旧 L525): 标签内首尾空白是 LLM 输出原文, {@code \n} 等首尾空白被 trim 后
 *       写回的文件与 LLM 生成内容不等价 (破坏 frontmatter 换行等); CC :263 原样写回.</li>
 *   <li><b>getSmallFastModel 缺第二 env 层 + 默认值无日期</b> (旧 L540): CC 默认经 getDefaultHaikuModel →
 *       firstParty haiku45 = 'claude-haiku-4-5-20251001' (model.ts:131-138 + configs.ts:31),
 *       旧硬编码 'claude-haiku-4-5' 无日期, 且未读 ANTHROPIC_DEFAULT_HAIKU_MODEL env.</li>
 * </ul>
 *
 * <p>独立测试类 (05-task-register.csv P3-7 规划), 不并入 451 行 SkillImprovementHookTest
 * (后者聚焦门控/上报/注册), 与 P1-14 SkillImprovementApplierTest 分层同范式.
 */
@DisplayName("[P3-7] SkillImprovementHook 语义微调: extractTag 嵌套/大小写 + getSmallFastModel env链 + 写回不trim")
class SkillImprovementSemanticTest {

    // ════════════════════════════════════════════════════════════════════
    // 1. extractTag · 对齐 CC messages.ts:633-687
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC messages.ts:679-681 仅 depth==0 && content (truthy) 才返回, 空标签
     * ({@code <updates></updates>}) 跳过、lastIndex 前进继续找后续非空 (:683).
     * 旧 TAG_PATTERN 实现第一个匹配 content="" 直接返回 "" — parseResponse 误判「无更新」.
     */
    @Test
    @DisplayName("extractTag 空内容标签跳过, 返回后续非空匹配")
    void extractTag_emptyTagSkipped_returnsSubsequentNonEmpty() {
        assertThat(SkillImprovementHook.extractTag("<updates></updates><updates>real</updates>", "updates"))
                .isEqualTo("real");
    }

    /**
     * WHY: CC :638 'gi' 标志大小写不敏感; LLM 输出标签大小写不可控, 旧实现区分大小写 → null.
     */
    @Test
    @DisplayName("extractTag 大小写不敏感 (对齐 CC 'gi')")
    void extractTag_caseInsensitive() {
        assertThat(SkillImprovementHook.extractTag("<Updates>x</Updates>", "updates")).isEqualTo("x");
        assertThat(SkillImprovementHook.extractTag("<updated_file>\n# New\n</updated_file>", "updated_file"))
                .isEqualTo("\n# New\n");
    }

    /**
     * WHY: 同名多标签时 depth 计数只取最外层 (depth==0) 的首次匹配内容, 非贪婪匹配天然返回第一个
     * 兄弟标签的内容 (CC messages.ts:658-681). 若 depth 计数误判或首匹配取错, 会返回 B.
     */
    @Test
    @DisplayName("extractTag 同名多标签返回首个 depth0 内容")
    void extractTag_multipleSiblings_returnsFirstDepth0() {
        assertThat(SkillImprovementHook.extractTag("<updates>A</updates><updates>B</updates>", "updates"))
                .isEqualTo("A");
    }

    /**
     * WHY: 带属性标签应被识别 (CC :646 {@code (?:\s+[^>]*)?} 可选属性), 旧 TAG_PATTERN 亦支持,
     * 回归锁住不回归.
     */
    @Test
    @DisplayName("extractTag 支持带属性标签")
    void extractTag_tagWithAttributes() {
        assertThat(SkillImprovementHook.extractTag("<updates lang=\"en\">x</updates>", "updates")).isEqualTo("x");
    }

    /**
     * WHY: 无完整闭合标签 / 无匹配 tagName / 空输入 → null (CC messages.ts:686).
     * 旧实现同返回 null, 此测试锁住契约防回归.
     */
    @Test
    @DisplayName("extractTag 无匹配/未闭合/空输入返回 null")
    void extractTag_noMatch_returnsNull() {
        assertThat(SkillImprovementHook.extractTag("<updates>unclosed", "updates")).isNull();
        assertThat(SkillImprovementHook.extractTag("<foo>x</foo>", "updates")).isNull();
        assertThat(SkillImprovementHook.extractTag("", "updates")).isNull();
        assertThat(SkillImprovementHook.extractTag("<updates>x</updates>", "")).isNull();
        assertThat(SkillImprovementHook.extractTag(null, "updates")).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. applySkillImprovement 写回不 trim · 对齐 CC skillImprovement.ts:263
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC :263 {@code fs.writeFile(filePath, updatedContent, 'utf-8')} 不 trim — 标签内首尾
     * 空白是 LLM 输出原文. 旧实现写 {@code updatedContent.trim()} 会把文件改写为 "# New"
     * (破坏 frontmatter 换行等). 本测试锁住「写回保留首尾空白」契约.
     */
    @Test
    @DisplayName("applySkillImprovement 写回保留标签内首尾空白 (不 trim)")
    void applySkillImprovement_writeBackPreservesLeadingTrailingWhitespace(@TempDir Path tempDir) throws Exception {
        // R9-2：项目级技能目录随 appName 动态（决策 D1/D6）= <baseDir>/<getProjectDirName()>/skills
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Original content");

        String modelResponse = "<updated_file>\n# New\n</updated_file>";
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, prompt, options) -> modelResponse,
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        CompletableFuture<Void> future = hook.applySkillImprovement("my-skill",
                List.of(new SkillUpdate("new step", "ask energy", "user asked")));
        future.join();

        assertThat(Files.readString(skillMd)).isEqualTo("\n# New\n");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. getSmallFastModel env 链 · 对齐 CC model.ts:36-38 / :131-138 / configs.ts:31
    // ════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC getSmallFastModel (model.ts:36-38) 先读 ANTHROPIC_SMALL_FAST_MODEL, 否则
     * getDefaultHaikuModel (model.ts:131-138) 读 ANTHROPIC_DEFAULT_HAIKU_MODEL, 否则默认
     * firstParty haiku45 = 'claude-haiku-4-5-20251001' (configs.ts:31). 旧实现无第二 env 层
     * 且默认值 'claude-haiku-4-5' 无日期 (仅 Foundry 值).
     */
    @Test
    @DisplayName("getSmallFastModel 三层 env 链: SMALL_FAST → DEFAULT_HAIKU → 默认 firstParty haiku45")
    void getSmallFastModel_envChainResolution() {
        assertThat(SkillImprovementHook.getSmallFastModel("fast", "h")).isEqualTo("fast");
        assertThat(SkillImprovementHook.getSmallFastModel("", "h")).isEqualTo("h");
        assertThat(SkillImprovementHook.getSmallFastModel(null, "h")).isEqualTo("h");
        assertThat(SkillImprovementHook.getSmallFastModel("   ", "h")).isEqualTo("h");
        assertThat(SkillImprovementHook.getSmallFastModel("", "")).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(SkillImprovementHook.getSmallFastModel(null, null)).isEqualTo("claude-haiku-4-5-20251001");
    }
}

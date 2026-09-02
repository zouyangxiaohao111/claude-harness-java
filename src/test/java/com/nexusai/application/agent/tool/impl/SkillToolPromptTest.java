package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-3] SkillToolPrompt 五函数簇 + SkillToolImpl.prompt()/toAutoClassifierInput 定向测试 ·
 * 对齐 CC {@code tools/SkillTool/prompt.ts} + {@code SkillTool.ts:344/352}。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）:
 * <ol>
 *   <li><b>prompt() 必须补全 ms-office-suite:pdf 全限定名示例</b>——CC prompt.ts:186 明确教导模型
 *       用「冒号全限定名」调用 bundled 技能（ms-office-suite 多技能包）。旧 Java 内联文本缺该行
 *       （SkillToolImpl.java:430-452），ToolRegistry:424 以 prompt() 作为 LLM 可见工具描述 →
 *       模型不会用全限定名调用。RED：实施前断言含该行必失败。</li>
 *   <li><b>toAutoClassifierInput fallback 必须返回空串而非工具名</b>——CC SkillTool.ts:352
 *       {@code skill ?? ''}。分类器（backseat/skill-coach）依赖「skill 缺失 → 空串」判定无技能调用；
 *       旧实现返回 {@code name()}="Skill" 会让分类器误判发生了一次 skill 调用。RED：实施前返回
 *       "Skill" 必失败。</li>
 *   <li><b>getPrompt memoize + clearPromptCache</b>——CC prompt.ts:173 getPrompt 按 cwd memoize +
 *       prompt.ts:217-219 clearPromptCache。Java 文本静态 → 单值缓存，连续调用同引用；clear 后
 *       可重建（非 null、完整文本）。</li>
 *   <li><b>getSkillToolInfo / getLimitedSkillToolCommands 数据源 = getModelInvocableCommands</b>——
 *       CC prompt.ts:198-208/213-215 均基于 getSkillToolCommands(cwd)（commands.ts:563）；
 *       P2-18 analyzeContext 据此统计 skill token。若数据源漂移，analyzeContext 计数失真。</li>
 *   <li><b>getSkillInfo 数据源 = 新 getSlashCommandToolSkills 第二套过滤</b>——CC prompt.ts:221-241
 *       基于 getSlashCommandToolSkills（commands.ts:586），与 getModelInvocableCommands 是两套
 *       不同过滤（disableModelInvocation 技能在后者被排除、在前者入选）。</li>
 * </ol>
 */
@DisplayName("P2-3 · SkillToolPrompt 五函数簇 + prompt()/toAutoClassifierInput (CC prompt.ts / SkillTool.ts)")
class SkillToolPromptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 隔离静态 BundledSkills 注册表（对齐 SkillRegistrySlashCommandToolSkillsTest.clearBundledSkills 惯例）——
     * 同 JVM 先跑的测试（SkillRegistryMemoizeTest/LoopRememberParamsTest 等）若注册 bundled 技能未清理，
     * 会泄漏进本测试 SkillRegistry.getAllCommands，使 getModelInvocableCommands/getSlashCommandToolSkills
     * 计数失真。P2-21 回归触发后补（loadedFrom 过滤语义下泄漏技能仍被 allowlist 放行）。
     */
    @org.junit.jupiter.api.BeforeEach
    void clearBundledSkills() {
        com.nexusai.application.agent.skill.BundledSkills.clear();
    }

    /** 写一个最小 SKILL.md（无额外 frontmatter）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name) throws Exception {
        writeSkill(root, dir, name, null);
    }

    /** 写一个最小 SKILL.md（frontmatter name + 可选额外字段）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name, String extraFrontmatter) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        StringBuilder fm = new StringBuilder("---\nname: ").append(name).append('\n');
        if (extraFrontmatter != null) {
            fm.append(extraFrontmatter).append('\n');
        }
        fm.append("---\n# ").append(name).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), fm.toString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // ① prompt() 补全 ms-office-suite:pdf（RED→GREEN）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("prompt() 含 ms-office-suite:pdf 全限定名示例行 (RED: 旧内联文本缺该行)")
    void prompt_containsMsOfficeSuitePdfExample(@TempDir Path tempDir) throws Exception {
        SkillToolImpl tool = new SkillToolImpl(null); // prompt() 不依赖 registry

        String prompt = tool.prompt();

        // RED 于旧内联文本（SkillToolImpl.java:430-452 无该行）；GREEN 于 P2-3
        assertThat(prompt).contains("`skill: \"ms-office-suite:pdf\"` - invoke using fully qualified name");
    }

    @Test
    @DisplayName("prompt() 输出与 CC prompt.ts 关键行逐字一致（pdf/commit/review-pr 示例顺序）")
    void prompt_matchesCcKeyLines(@TempDir Path tempDir) {
        SkillToolImpl tool = new SkillToolImpl(null);

        String prompt = tool.prompt();

        // 对齐 CC prompt.ts:183-186 示例块（顺序 + 文本逐字一致）
        assertThat(prompt).contains("  - `skill: \"pdf\"` - invoke the pdf skill\n");
        assertThat(prompt).contains("  - `skill: \"commit\", args: \"-m 'Fix bug'\"` - invoke with arguments\n");
        assertThat(prompt).contains("  - `skill: \"review-pr\", args: \"123\"` - invoke with arguments\n");
        assertThat(prompt).contains("  - `skill: \"ms-office-suite:pdf\"` - invoke using fully qualified name\n");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ② toAutoClassifierInput fallback 空串（RED→GREEN）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toAutoClassifierInput(null) → 空串 (RED: 旧实现返回 name()='Skill')")
    void toAutoClassifierInput_nullInput_returnsEmpty() {
        SkillToolImpl tool = new SkillToolImpl(null);

        String result = tool.toAutoClassifierInput((JsonNode) null);

        // 对齐 CC SkillTool.ts:352 skill ?? ''；RED 于旧实现返回 "Skill"
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("toAutoClassifierInput 缺 skill 字段 → 空串 (RED: 旧实现返回 name()='Skill')")
    void toAutoClassifierInput_missingSkillField_returnsEmpty() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("args", "hello");

        String result = tool.toAutoClassifierInput(input);

        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("toAutoClassifierInput skill 为显式 null 字面量 → 空串 (CC ?? 语义)")
    void toAutoClassifierInput_nullSkillLiteral_returnsEmpty() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ObjectNode input = MAPPER.createObjectNode();
        input.putNull("skill");

        String result = tool.toAutoClassifierInput(input);

        // NullNode.asText() == ""，与 CC null ?? '' 语义等价
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("toAutoClassifierInput skill 有值 → 原样返回 (不加 trim)")
    void toAutoClassifierInput_withSkill_returnsValue() {
        SkillToolImpl tool = new SkillToolImpl(null);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "review-pr");

        String result = tool.toAutoClassifierInput(input);

        assertThat(result).isEqualTo("review-pr");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ③ getPrompt memoize + clearPromptCache
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPrompt 连续调用返回同引用（memoize 生效）· clearPromptCache 后仍可重建")
    void getPrompt_memoized_and_rebuildable() {
        String first = SkillToolPrompt.getPrompt();
        String second = SkillToolPrompt.getPrompt();

        // memoize：重复调用返回同引用（对齐 CC lodash memoize 按 cwd 缓存）
        assertThat(second).isSameAs(first);
        // 文本完整（含 ms-office-suite:pdf 行）
        assertThat(first).contains("ms-office-suite:pdf");

        // clear 后重建：缓存置 null → 下次调用返回有效完整文本（非 null、不残留空值）
        SkillToolPrompt.clearPromptCache();
        String rebuilt = SkillToolPrompt.getPrompt();
        assertThat(rebuilt).isNotNull().contains("ms-office-suite:pdf");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ④ getSkillToolInfo == getModelInvocableCommands().size()
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSkillToolInfo.totalCommands == getModelInvocableCommands().size() (CC prompt.ts:198-208)")
    void getSkillToolInfo_matchesModelInvocableCount(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");                                      // USER 默认可模型调用
        writeSkill(tempDir, "skill-b", "skill-b", "disable-model-invocation: true");    // 模型不可调用
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        SkillToolPrompt.SkillToolInfo info = SkillToolPrompt.getSkillToolInfo(registry);

        // totalCommands === includedCommands === getModelInvocableCommands().size()（CC: 两值恒相等）
        assertThat(info.totalCommands()).isEqualTo(registry.getModelInvocableCommands().size());
        assertThat(info.includedCommands()).isEqualTo(registry.getModelInvocableCommands().size());
        // 只统计模型可调用技能（skill-b 被 exclude，只剩 skill-a）
        assertThat(info.totalCommands()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑤ getLimitedSkillToolCommands == getModelInvocableCommands()
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getLimitedSkillToolCommands 直接返回 getModelInvocableCommands (CC prompt.ts:213-215)")
    void getLimitedSkillToolCommands_equalsModelInvocable(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        List<Command> limited = SkillToolPrompt.getLimitedSkillToolCommands(registry);

        assertThat(limited).isEqualTo(registry.getModelInvocableCommands());
        assertThat(limited).extracting(Command::getName).contains("skill-a");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑥ getSkillInfo == getSlashCommandToolSkills().size()
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSkillInfo.totalSkills == getSlashCommandToolSkills().size() (CC prompt.ts:221-241)")
    void getSkillInfo_matchesSlashCommandToolSkills(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a", "when_to_use: 用户请求时使用");       // 有 whenToUse → 技能
        writeSkill(tempDir, "skill-b", "skill-b");                                      // 无描述无 whenToUse → 排除
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        SkillToolPrompt.SkillInfo info = SkillToolPrompt.getSkillInfo(registry);

        assertThat(info.totalSkills()).isEqualTo(registry.getSlashCommandToolSkills().size());
        assertThat(info.includedSkills()).isEqualTo(registry.getSlashCommandToolSkills().size());
        // 第二套过滤：无 hasUserSpecifiedDescription 且无 whenToUse 的 USER 技能被排除
        assertThat(info.totalSkills()).isEqualTo(1);
    }
}

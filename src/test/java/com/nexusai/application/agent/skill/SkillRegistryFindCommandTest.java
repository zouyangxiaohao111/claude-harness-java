package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-12 findCommand 三维匹配测试 · 对齐 CC findCommand（commands.ts:688-698）+ getCommandName
 * （types/command.ts:209-211）+ skill userFacingName（loadSkillsDir.ts:337-339）。
 *
 * <p>CC {@code findCommand} = {@code commands.find(_ => _.name === n || getCommandName(_) === n
 * || _.aliases?.includes(n))} —— 三维匹配，首个命中者胜。Java 现状只有二维
 * （name + aliases，SkillRegistry.java:304-305），缺 {@code getCommandName} 第三维。
 * {@code getCommandName} = {@code userFacingName?.() ?? name}；skill 命令的
 * {@code userFacingName()} = {@code displayName || skillName}（displayName 来自 frontmatter.name）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>命令应以 displayName 被 findCommand 命中</b>——CC SkillTool.ts:402(validateInput)/
 *       :447(checkPermissions)/:616(execute) 依赖三维命中：用户/模型输入「网页搜索」
 *       （frontmatter.name）应命中目录名 skill-a 的命令并触发技能执行。若 displayName 维度缺失，
 *       合法输入被解析为 Unknown skill → 技能无法通过展示名调用。这正是 P1-12 的对齐目标。</li>
 *   <li><b>name/alias/前导'/' 行为不得回退</b>——三维匹配是加宽；既有二维命中路径（精确名、
 *       alias、剥前导'/'）必须保持 GREEN，否则回归。</li>
 *   <li><b>displayName 缺省回退 name</b>——CC {@code userFacingName() { return displayName || skillName }}：
 *       无 frontmatter.name 时仍以目录名命中（name 维度兜底）。</li>
 * </ol>
 */
class SkillRegistryFindCommandTest {

    /** 写一个最小 SKILL.md（可带额外 frontmatter）· 对齐 SkillsLoader.loadFromSkillMd */
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

    @Test
    @DisplayName("displayName(frontmatter.name) 可被 findCommand 命中 · 对齐 CC getCommandName(userFacingName) 第三维")
    void byDisplayName_userFacingName(@TempDir Path tempDir) throws Exception {
        // 目录 skill-a + frontmatter name: 网页搜索 → displayName="网页搜索", name="skill-a"
        writeSkill(tempDir, "skill-a", "网页搜索", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        // RED 于现状（二维 name+aliases，displayName 维度缺失 → null）；GREEN 于 P1-12
        Command hit = registry.findCommand("网页搜索");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("skill-a");
    }

    @Test
    @DisplayName("精确名匹配回归 · 对齐 CC :694 name === commandName")
    void byExactName(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        Command hit = registry.findCommand("skill-a");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("skill-a");
    }

    @Test
    @DisplayName("硬编码 alias 匹配回归 · 对齐 CC :696 aliases?.includes（BuiltInCommands 硬编码别名，非 frontmatter）")
    void byAlias(@TempDir Path tempDir) throws Exception {
        // WHY: aliases 只来自硬编码源（BuiltInCommands.clear 的 aliases=['reset','new']，
        // CC commands/clear/index.ts:10-16），不再从 SKILL.md frontmatter 解析（LD-⊕-2 删除
        // frontmatter aliases 解析）。三维匹配的 aliases 维度（CC :696）须仍可命中。
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        Command hit = registry.findCommand("reset");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("clear");
    }

    @Test
    @DisplayName("前导 '/' 归一化回归（Java findCommand 内部剥；净行为等价 CC 调用方剥）")
    void stripsLeadingSlash(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.findCommand("/skill-a")).isNotNull();
        assertThat(registry.findCommand("/skill-a").getName()).isEqualTo("skill-a");
    }

    @Test
    @DisplayName("displayName 缺省回退 name · 对齐 CC userFacingName() { return displayName || skillName }")
    void noDisplayNameFallbackToName(@TempDir Path tempDir) throws Exception {
        // 无 frontmatter name（displayName 空）→ userFacingName() 回退目录名
        Path skillDir = tempDir.resolve("skill-a");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\n---\n# skill-a\n");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        Command hit = registry.findCommand("skill-a");
        assertThat(hit).isNotNull();
        assertThat(hit.getName()).isEqualTo("skill-a");
        // userFacingName() 回退语义自验（loadSkillsDir.ts:337-339）
        assertThat(hit.userFacingName()).isEqualTo("skill-a");
    }

    @Test
    @DisplayName("未命中返回 null（Unknown skill 判定输入）· 对齐 CC findCommand 返回 undefined")
    void missReturnsNull(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a", null);
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        assertThat(registry.findCommand("not-exist")).isNull();
        assertThat(registry.findCommand("")).isNull();
        assertThat(registry.findCommand(null)).isNull();
    }
}

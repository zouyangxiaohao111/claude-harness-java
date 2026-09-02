package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-20 legacy /commands/ 加载器测试（RED→GREEN）· 对齐 CC loadSkillsDir.ts:484-623
 * （isSkillFile / transformSkillFiles / buildNamespace / getSkillCommandName /
 * getRegularCommandName / getCommandName / loadSkillsFromCommandsDir）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>目录内 SKILL.md 取第一个并取父目录名</b>——CC :489-491/:508-514：同目录多 skill 文件只载一个，
 *       且 skill 命令名 = 父目录名（非文件名）。</li>
 *   <li><b>namespace a:b:c</b>——CC :523-534：子目录命令以 ':' 层级前缀限定，避免跨目录重名冲突。</li>
 *   <li><b>普通 .md 取文件名去 .md</b>——CC :545-552。</li>
 *   <li><b>skill.md 大小写不敏感</b>——CC :485 {@code /^skill\.md$/i}。</li>
 *   <li><b>legacy 产物缺省</b>——loadedFrom='commands_DEPRECATED'（Java USER）、displayName=undefined、
 *       paths=undefined（CC :604/:608/:609）。</li>
 * </ol>
 */
class LegacyCommandsLoaderTest {

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        MarkdownConfigLoader.clearCache();
    }

    /** 构造 MarkdownFile（单元测试用，frontmatter 空 + source=projectSettings）。 */
    private static MarkdownConfigLoader.MarkdownFile md(String filePath, String baseDir) {
        return new MarkdownConfigLoader.MarkdownFile(filePath, baseDir, Map.of(), "", "projectSettings");
    }

    // ── isSkillFile ──

    @Test
    @DisplayName("skill.md 大小写不敏感（/^skill\\.md$/i，CC :485）")
    void isSkillFile_caseInsensitive() {
        assertThat(LegacyCommandsLoader.isSkillFile("commands/skill.md")).isTrue();
        assertThat(LegacyCommandsLoader.isSkillFile("commands/SKILL.md")).isTrue();
        assertThat(LegacyCommandsLoader.isSkillFile("commands/Skill.MD")).isTrue();
        assertThat(LegacyCommandsLoader.isSkillFile("commands/sub/skill.md")).isTrue();
        assertThat(LegacyCommandsLoader.isSkillFile("commands/other.md")).isFalse();
        assertThat(LegacyCommandsLoader.isSkillFile("commands/skill.txt")).isFalse();
        assertThat(LegacyCommandsLoader.isSkillFile(null)).isFalse();
    }

    // ── transformSkillFiles ──

    @Test
    @DisplayName("目录内 SKILL.md 存在 → 只保留 skill 文件（丢弃同目录普通 .md，CC :505-514）")
    void transformSkillFiles_skillWinsInDir() {
        String dir = Path.of("p", ".claude", "commands", "myskill").toString();
        MarkdownConfigLoader.MarkdownFile skill =
            md(Path.of(dir, "SKILL.md").toString(), Path.of("p", ".claude", "commands").toString());
        MarkdownConfigLoader.MarkdownFile note =
            md(Path.of(dir, "note.md").toString(), Path.of("p", ".claude", "commands").toString());

        List<MarkdownConfigLoader.MarkdownFile> result =
            LegacyCommandsLoader.transformSkillFiles(List.of(skill, note));

        assertThat(result).extracting(MarkdownConfigLoader.MarkdownFile::filePath)
            .containsExactly(skill.filePath());
    }

    @Test
    @DisplayName("目录内多 SKILL.md → 取第一个（CC :508-513）")
    void transformSkillFiles_multipleSkillFiles_firstWins() {
        String dir = Path.of("p", ".claude", "commands", "dup").toString();
        MarkdownConfigLoader.MarkdownFile skillA =
            md(Path.of(dir, "skill.md").toString(), Path.of("p", ".claude", "commands").toString());
        MarkdownConfigLoader.MarkdownFile skillB =
            md(Path.of(dir, "SKILL.md").toString(), Path.of("p", ".claude", "commands").toString());

        List<MarkdownConfigLoader.MarkdownFile> result =
            LegacyCommandsLoader.transformSkillFiles(List.of(skillA, skillB));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).filePath()).isEqualTo(skillA.filePath());
    }

    @Test
    @DisplayName("目录无 skill 文件 → 保留全部（CC :515-516）")
    void transformSkillFiles_noSkill_keepAll() {
        String dir = Path.of("p", ".claude", "commands", "plain").toString();
        MarkdownConfigLoader.MarkdownFile one =
            md(Path.of(dir, "one.md").toString(), Path.of("p", ".claude", "commands").toString());
        MarkdownConfigLoader.MarkdownFile two =
            md(Path.of(dir, "two.md").toString(), Path.of("p", ".claude", "commands").toString());

        List<MarkdownConfigLoader.MarkdownFile> result =
            LegacyCommandsLoader.transformSkillFiles(List.of(one, two));

        assertThat(result).hasSize(2);
    }

    // ── buildNamespace / getCommandName 族 ──

    @Test
    @DisplayName("buildNamespace：同 baseDir → ''；子目录 → a:b:c（CC :523-534）")
    void buildNamespace_nestedColon() {
        String base = Path.of("p", ".claude", "commands").toString();
        assertThat(LegacyCommandsLoader.buildNamespace(base, base)).isEmpty();
        assertThat(LegacyCommandsLoader.buildNamespace(
            Path.of("p", ".claude", "commands", "sub").toString(), base)).isEqualTo("sub");
        assertThat(LegacyCommandsLoader.buildNamespace(
            Path.of("p", ".claude", "commands", "sub", "deep").toString(), base)).isEqualTo("sub:deep");
        // 不在 baseDir 之下 → ''（Java 防御，不复制 JS slice 垃圾串）
        assertThat(LegacyCommandsLoader.buildNamespace(
            Path.of("other", "x").toString(), base)).isEmpty();
    }

    @Test
    @DisplayName("getSkillCommandName：SKILL.md 取父目录名 + namespace（CC :536-543）")
    void getSkillCommandName_usesParentDirName() {
        String base = Path.of("p", ".claude", "commands").toString();
        String file = Path.of("p", ".claude", "commands", "myskill", "SKILL.md").toString();
        String nestedFile = Path.of("p", ".claude", "commands", "sub", "deep", "myskill", "SKILL.md").toString();

        assertThat(LegacyCommandsLoader.getSkillCommandName(file, base)).isEqualTo("myskill");
        assertThat(LegacyCommandsLoader.getSkillCommandName(nestedFile, base)).isEqualTo("sub:deep:myskill");
    }

    @Test
    @DisplayName("getRegularCommandName：普通 .md 取文件名去 .md + namespace（CC :545-552）")
    void getRegularCommandName_stripsMd() {
        String base = Path.of("p", ".claude", "commands").toString();
        String file = Path.of("p", ".claude", "commands", "regular.md").toString();
        String nestedFile = Path.of("p", ".claude", "commands", "sub", "deep", "regular.md").toString();

        assertThat(LegacyCommandsLoader.getRegularCommandName(file, base)).isEqualTo("regular");
        assertThat(LegacyCommandsLoader.getRegularCommandName(nestedFile, base)).isEqualTo("sub:deep:regular");
    }

    // ── loadSkillsFromCommandsDir 集成 ──

    @Test
    @DisplayName("集成：五类 legacy 命令名 + 缺省 loadedFrom/displayName/paths/baseDir · CC :566-623")
    void loadSkillsFromCommandsDir_integration(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("proj");
        Path git = project.resolve(".git");
        Files.createDirectories(git);
        Path commands = Files.createDirectories(project.resolve(".claude").resolve("commands"));

        // ① skill 格式：skillA/SKILL.md（frontmatter.name=DisplayA → 应被 displayName=undefined 覆盖）
        Path skillA = Files.createDirectories(commands.resolve("skillA"));
        Files.writeString(skillA.resolve("SKILL.md"),
            "---\nname: DisplayA\n---\n# Skill A\n");
        // ② 嵌套 skill：sub/skillB/SKILL.md → 'sub:skillB'
        Path skillB = Files.createDirectories(commands.resolve("sub").resolve("skillB"));
        Files.writeString(skillB.resolve("SKILL.md"), "---\n---\n# Skill B\n");
        // ③ 普通 .md：regular.md → 'regular'
        Files.writeString(commands.resolve("regular.md"), "# Regular command\n");
        // ④ 嵌套普通 .md：nested/another.md → 'nested:another'
        Path nested = Files.createDirectories(commands.resolve("nested"));
        Files.writeString(nested.resolve("another.md"), "# Another command\n");

        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());

        List<Command> skills = LegacyCommandsLoader.loadSkillsFromCommandsDir(project.toString());

        assertThat(skills).extracting(Command::getName)
            .containsExactlyInAnyOrder("skillA", "sub:skillB", "regular", "nested:another");

        // P2-21: source（透传 markdown 源，CC :606）+ loadedFrom 独立落位 —— legacy 命令
        //   loadedFrom=COMMANDS_DEPRECATED（CC :608，此前误折叠为 CommandSource.USER）；
        //   P2-19 拆分：项目级 legacy 命令 markdown 源 = projectSettings（MarkdownConfigLoader :357-372）
        //   → CommandSource.fromString("projectSettings") = PROJECT_SETTINGS（不再折叠 USER）
        assertThat(skills).allSatisfy(c -> assertThat(c.getSource())
            .as("source 透传 markdown 源（CC :606，项目级=projectSettings）")
            .isEqualTo(com.nexusai.model.command.CommandSource.PROJECT_SETTINGS));
        assertThat(skills).allSatisfy(c -> assertThat(c.getLoadedFrom())
            .as("loadedFrom='commands_DEPRECATED'（CC :608，getSlashCommandToolSkills 排除键）")
            .isEqualTo(CommandLoadedFrom.COMMANDS_DEPRECATED));
        // displayName=undefined（CC :604 覆盖 frontmatter.name）
        assertThat(skills).allSatisfy(c -> assertThat(c.getDisplayName()).isNull());
        // paths=undefined（CC :609）
        assertThat(skills).allSatisfy(c -> assertThat(c.getPaths()).isNull());
        // baseDir=skillDirectory（仅 skill 格式，CC :607）
        for (Command c : skills) {
            if (c.getName().equals("skillA")) {
                assertThat(c.getBaseDir()).isEqualTo(skillA.toString());
            } else if (c.getName().equals("sub:skillB")) {
                assertThat(c.getBaseDir()).isEqualTo(skillB.toString());
            } else {
                assertThat(c.getBaseDir()).isNull();
            }
        }
        // userInvocable 缺省 true（CC :564 commands default user-invocable: true）
        assertThat(skills).allSatisfy(c -> assertThat(c.getUserInvocable()).isTrue());
    }
}

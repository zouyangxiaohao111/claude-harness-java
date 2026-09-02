package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
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
 * P2-21 CommandLoadedFrom 6 值 + SkillRegistry 过滤测试 · 对齐 CC command.ts:191-197
 * LoadedFrom 类型联合 + commands.ts:574-578/:595-597 过滤语义。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>loadedFrom 是独立于 source 的 6 值加载渠道字段</b>——Java 旧架构把 source/loadedFrom
 *       合一进 Command.source，'commands_deprecated'→USER 与 'managed'→BUNDLED 有损折叠（M20 △ 根因）
 *       丢失渠道区分。fromString 必须严格解析 6 值、未知 → null（CC undefined），不猜测折叠。</li>
 *   <li><b>commands_DEPRECATED 被 getSlashCommandToolSkills 明确排除</b>——CC commands.ts:595-597
 *       {@code loadedFrom∈{skills,plugin,bundled}}；旧折叠（→USER∈{USER,PLUGIN,BUNDLED}）误放行
 *       legacy /commands/ 命令进斜杠技能集。RED 于旧实现。</li>
 *   <li><b>managed 目录技能失去 bundled 特权</b>——SkillsLoader managed 源（CC loadSkillsDir.ts:467/:688
 *       经 loadSkillsFromSkillsDir loadedFrom 恒 'skills'，绝非 bundled）旧实现 source=BUNDLED，
 *       使 managed 技能在 SkillCatalog 免截断 + 凭 source==BUNDLED 自动进模型可调用；改后
 *       source=POLICY_SETTINGS（△-1/组4 对齐 CC :688）+ loadedFrom=SKILLS。RED 于旧实现。</li>
 * </ol>
 */
class CommandLoadedFromTest {

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        MarkdownConfigLoader.clearCache();
    }

    @Test
    @DisplayName("fromString 严格解析 6 值（command.ts:191-197）")
    void fromString_allSixValues() {
        assertThat(CommandLoadedFrom.fromString("commands_deprecated")).isEqualTo(CommandLoadedFrom.COMMANDS_DEPRECATED);
        assertThat(CommandLoadedFrom.fromString("skills")).isEqualTo(CommandLoadedFrom.SKILLS);
        assertThat(CommandLoadedFrom.fromString("plugin")).isEqualTo(CommandLoadedFrom.PLUGIN);
        assertThat(CommandLoadedFrom.fromString("managed")).isEqualTo(CommandLoadedFrom.MANAGED);
        assertThat(CommandLoadedFrom.fromString("bundled")).isEqualTo(CommandLoadedFrom.BUNDLED);
        assertThat(CommandLoadedFrom.fromString("mcp")).isEqualTo(CommandLoadedFrom.MCP);
        // 大小写不敏感 + trim
        assertThat(CommandLoadedFrom.fromString("  BUNDLED ")).isEqualTo(CommandLoadedFrom.BUNDLED);
    }

    @Test
    @DisplayName("fromString null / blank / 未知 → null（CC undefined，不猜测折叠）")
    void fromString_invalid_returnsNull() {
        assertThat(CommandLoadedFrom.fromString(null)).isNull();
        assertThat(CommandLoadedFrom.fromString("")).isNull();
        assertThat(CommandLoadedFrom.fromString("  ")).isNull();
        // RED 于旧 CommandSource.fromString 折叠（未知 → USER）；GREEN：loadedFrom 未知 → null
        assertThat(CommandLoadedFrom.fromString("bogus")).isNull();
        // 'user'/'builtin' 是 source 值非 loadedFrom 值 → null
        assertThat(CommandLoadedFrom.fromString("user")).isNull();
        assertThat(CommandLoadedFrom.fromString("builtin")).isNull();
    }

    @Test
    @DisplayName("commands_DEPRECATED 命令（带 whenToUse）被 getSlashCommandToolSkills 排除（CC :595-597；RED 于旧 USER 折叠）")
    void commandsDeprecated_excludedFromSlashCommandToolSkills(@TempDir Path tempDir) {
        Command legacy = new Command();
        legacy.setName("legacy-cmd");
        legacy.setType("prompt");
        legacy.setWhenToUse("用户手动触发的 legacy 命令");
        legacy.setSource(CommandSource.USER);              // CC :606 透传 markdown 源
        legacy.setLoadedFrom(CommandLoadedFrom.COMMANDS_DEPRECATED); // CC :608

        // RED 于旧实现（loadedFrom 折叠进 CommandSource.USER → source==USER∈{USER,PLUGIN,BUNDLED} 放行）；
        // GREEN：COMMANDS_DEPRECATED ∉ {SKILLS,PLUGIN,BUNDLED} → 排除（commands.ts:595-597）
        assertThat(new FixtureRegistry(List.of(legacy)).getSlashCommandToolSkills())
            .extracting(Command::getName)
            .doesNotContain("legacy-cmd");
    }

    @Test
    @DisplayName("commands_DEPRECATED 命令在 getModelInvocableCommands allowlist（CC :576）")
    void commandsDeprecated_includedInModelInvocable(@TempDir Path tempDir) {
        Command legacy = new Command();
        legacy.setName("legacy-cmd");
        legacy.setType("prompt");
        legacy.setSource(CommandSource.USER);
        legacy.setLoadedFrom(CommandLoadedFrom.COMMANDS_DEPRECATED);

        // CC :574-576 loadedFrom∈{bundled,skills,commands_DEPRECATED} 免显式描述自动放行
        assertThat(new FixtureRegistry(List.of(legacy)).getModelInvocableCommands())
            .extracting(Command::getName)
            .contains("legacy-cmd");
    }

    @Test
    @DisplayName("loadedFrom=null 且无显式描述/whenToUse 的 Command 被 getModelInvocableCommands 排除（RED 于旧 source==USER 自动放行）")
    void nullLoadedFrom_withoutDesc_excludedFromModelInvocable(@TempDir Path tempDir) {
        Command noLoadedFrom = new Command();
        noLoadedFrom.setName("plain");
        noLoadedFrom.setType("prompt");
        noLoadedFrom.setSource(CommandSource.USER);   // 旧实现：source==USER 在 allowlist → 自动放行

        // 新实现：loadedFrom=null ∉ {BUNDLED,SKILLS,COMMANDS_DEPRECATED} 且无 hasUserSpecifiedDescription /
        // whenToUse → 排除（CC :574-578 无 null 分支）——测试构造/DB 路径命令不再凭默认 source 自动进模型清单
        assertThat(new FixtureRegistry(List.of(noLoadedFrom)).getModelInvocableCommands())
            .extracting(Command::getName)
            .doesNotContain("plain");
    }

    @Test
    @DisplayName("managed 目录技能 source=POLICY_SETTINGS + loadedFrom=SKILLS（RED 于旧 BUNDLED 折叠）→ catalog 失去 bundled 不截断特权")
    void managedSkill_loadsAsUserSkill_losesBundledPrivilege(@TempDir Path temp) throws Exception {
        // managed 源（CC loadSkillsDir.ts:688 policySettings → :467 loadedFrom:'skills'，绝非 bundled）
        Path managedRoot = Files.createDirectories(temp.resolve("managed"));
        Path skillsDir = Files.createDirectories(managedRoot.resolve(".claude").resolve("skills"));
        Path skillDir = Files.createDirectories(skillsDir.resolve("mng"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: mng\n---\n# Managed\n" + "长".repeat(120) + "\n");
        ClaudePaths.setManagedFilePathOverride(managedRoot.toString());
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        Path project = Files.createDirectories(temp.resolve("proj"));
        Files.createDirectories(project.resolve(".git"));

        SkillsLoader loader = new SkillsLoader();
        loader.setManagedSkillsEnabledSupplier(() -> true);   // 隔离 CLAUDE_CODE_DISABLE_POLICY_SKILLS
        loader.setBareModeSupplier(() -> false);              // 隔离 CLAUDE_CODE_SIMPLE
        loader.setAdditionalDirectoriesSupplier(List::of);
        loader.setSettingsSupplier(Map::of);                  // 不锁定 skills 面

        Command mng = loader.getSkillDirCommands(project.toString()).stream()
            .filter(c -> c.getName().equals("mng"))
            .findFirst().orElseThrow();
        // RED 于旧实现（SkillsLoader :289 managed→BUNDLED）；GREEN：source=POLICY_SETTINGS（△-1/组4 对齐
        //   CC :688 policySettings，不再折叠 USER）+ loadedFrom=SKILLS（CC :467）
        assertThat(mng.getSource()).as("managed source=POLICY_SETTINGS（CC :688）").isEqualTo(CommandSource.POLICY_SETTINGS);
        assertThat(mng.getLoadedFrom()).as("managed loadedFrom=SKILLS（CC :467，非 bundled）")
            .isEqualTo(CommandLoadedFrom.SKILLS);

        // catalog 预算内不保留完整描述（bundled 特权随 source=BUNDLED 消失；CC prompt.ts:97 分区按 source）
        SkillCatalog catalog = new SkillCatalog(new SkillRegistry(temp.toString()));
        String listing = catalog.formatListing(List.of(mng), 100);
        assertThat(listing).doesNotContain("长".repeat(120));
    }

    @Test
    @DisplayName("bundled 命令（source=BUNDLED）仍享 catalog 不截断特权（对照，CC prompt.ts:97 分区）")
    void bundledCommand_keepsNoTruncatePrivilege(@TempDir Path tempDir) {
        Command bd = new Command();
        bd.setName("bd");
        bd.setDescription("长".repeat(120));   // width 240，超预算
        bd.setSource(CommandSource.BUNDLED);
        bd.setLoadedFrom(CommandLoadedFrom.BUNDLED);

        SkillCatalog catalog = new SkillCatalog(new SkillRegistry(tempDir.toString()));
        String listing = catalog.formatListing(List.of(bd), 100);
        // bundled 分区（source==='bundled'）→ 完整描述保留
        assertThat(listing).contains("长".repeat(120));
    }

    /** 覆写 getAllCommands 直接喂入显式构造命令（隔离过滤器判别，对齐 SkillRegistrySlashCommandToolSkillsTest.FixtureRegistry） */
    private static final class FixtureRegistry extends SkillRegistry {
        private final List<Command> cmds;

        FixtureRegistry(List<Command> cmds) {
            super(".claude/skills");
            this.cmds = cmds;
        }

        @Override
        public List<Command> getAllCommands() {
            return cmds;
        }
    }
}

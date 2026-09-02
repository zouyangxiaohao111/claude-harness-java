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
 * P2-12 skillsLocked 门控测试（RED→GREEN）· 对齐 CC getSkillDirCommands（loadSkillsDir.ts:650
 * {@code const skillsLocked = isRestrictedToPluginOnly('skills')}）+ 锁定→用户源空（:689）
 * + pluginOnlyPolicy.ts:19-27 语义（true→全锁 / array→includes / 缺省→false）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>锁定→用户技能根直接空、不扫磁盘</b>——CC loadSkillsDir.ts:689 {@code isSettingSourceEnabled('userSettings')
 *       && !skillsLocked ? loadSkillsFromSkillsDir(userSkillsDir,'userSettings') : Promise.resolve([])}：
 *       锁定后用户源（Java 唯一技能根）从数据源上消失，用户技能对模型不可见（CC 锁定语义）。</li>
 *   <li><b>低层 loader 无门控</b>——CC 门控只在 getSkillDirCommands(:650) 与 addSkillDirectories(:925-927)
 *       两个调用点，loadSkillsFromSkillsDir（:407 定义）本身无 skillsLocked 检查；Java 由
 *       DynamicSkillsManager.isProjectSettingsEnabled(:468-470) 对齐 addSkillDirectories 门控，
 *       SkillsLoader 若在无条件变体/私有核心双门控即偏离 CC（结果等价但结构漂移）。</li>
 * </ol>
 */
class SkillsLoaderPluginOnlyGateTest {

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        MarkdownConfigLoader.clearCache();
    }

    private static void writeSkill(Path skillsRoot, String skillName) throws Exception {
        Path dir = skillsRoot.resolve(skillName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + skillName + "\n---\n# " + skillName + "\n");
    }

    // ── ① locked=true → 空且不读磁盘 ──
    @Test
    @DisplayName("locked=true：loadFromDirectory 返回空（不扫磁盘，CC :689 用户源锁定）")
    void lockedTrue_returnsEmptyEvenWhenSkillsExist(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        SkillsLoader loader = new SkillsLoader();
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));

        // 目录下有真实技能仍返回空 → 证明门控短路在目录扫描之前（对齐 CC :650 锁定 → 用户源 Promise.resolve([])）
        assertThat(loader.loadFromDirectory(skillsRoot.toString())).isEmpty();
    }

    // ── ② array 含 skills → 空 ──
    @Test
    @DisplayName("policy=array 含 'skills'：loadFromDirectory 返回空（pluginOnlyPolicy.ts:25 includes）")
    void lockedArrayContainsSkills_returnsEmpty(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        SkillsLoader loader = new SkillsLoader();
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", List.of("skills")));

        assertThat(loader.loadFromDirectory(skillsRoot.toString())).isEmpty();
    }

    // ── ③ array 不含 skills → 正常加载 ──
    @Test
    @DisplayName("policy=array 不含 'skills'（如 ['agents']）：正常加载（pluginOnlyPolicy.ts:25 仅锁清单内 surface）")
    void lockedArrayExcludesSkills_loadsNormally(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        SkillsLoader loader = new SkillsLoader();
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", List.of("agents")));

        List<Command> loaded = loader.loadFromDirectory(skillsRoot.toString());
        assertThat(loaded).extracting(Command::getName).containsExactly("my-skill");
    }

    // ── ④ 缺省 Map::of → 正常加载（现有行为不回归）──
    @Test
    @DisplayName("缺省 supplier（Map::of）：不锁定 → 正常加载（pluginOnlyPolicy.ts:26 缺省 policy → false）")
    void defaultSupplier_loadsNormally(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        // 未调用 setSettingsSupplier → 默认 Map::of = 不锁定（对齐 DynamicSkillsManager.java:114 默认）
        SkillsLoader loader = new SkillsLoader();

        List<Command> loaded = loader.loadFromDirectory(skillsRoot.toString());
        assertThat(loaded).extracting(Command::getName).containsExactly("my-skill");
    }

    // ── ⑤ loadFromDirectoryUnconditional 在 locked 下仍正常加载（无双门控）──
    @Test
    @DisplayName("loadFromDirectoryUnconditional 不受 skillsLocked 门控（CC 门控在 addSkillDirectories:925-927 调用点，Java 由 DynamicSkillsManager 门控）")
    void unconditionalLoadsWhenLocked(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        SkillsLoader loader = new SkillsLoader();
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));

        // 动态目录加载走无条件变体 → 仍加载（SkillsLoader 不得双门控；锁定语义由 addSkillDirectories 门控承载）
        List<Command> loaded = loader.loadFromDirectoryUnconditional(skillsRoot.toString());
        assertThat(loaded).extracting(Command::getName).containsExactly("my-skill");
    }

    // ── ⑥ 空技能目录 locked→空、unlocked→空（门控短路在目录存在性检查之前，CC :659-675）──
    @Test
    @DisplayName("空技能目录：locked→空 / unlocked→空（结果等价；锁定分支短路在扫描前，CC bare/锁定分支）")
    void emptyDir_lockedAndUnlockedBothEmpty(@TempDir Path skillsRoot) throws Exception {
        // 目录存在但无 SKILL.md
        Files.createDirectories(skillsRoot);

        SkillsLoader locked = new SkillsLoader();
        locked.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));
        assertThat(locked.loadFromDirectory(skillsRoot.toString())).isEmpty();

        SkillsLoader unlocked = new SkillsLoader();
        assertThat(unlocked.loadFromDirectory(skillsRoot.toString())).isEmpty();
    }

    // ── ⑦ null supplier 空安全：setSettingsSupplier(null) 忽略，保持默认不锁 ──
    @Test
    @DisplayName("setSettingsSupplier(null) 空安全：忽略 → 默认不锁，正常加载（对齐 DynamicSkillsManager.setSettingsSupplier 空安全）")
    void nullSupplierIgnored(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");

        SkillsLoader loader = new SkillsLoader();
        loader.setSettingsSupplier(null);

        assertThat(loader.loadFromDirectory(skillsRoot.toString()))
            .extracting(Command::getName).containsExactly("my-skill");
    }

    // ════════════════════════════════════════════════════════════════════════
    // P2-20: getSkillDirCommands 级 per-source 门控断言（补充）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 搭建 getSkillDirCommands fixture：managed(user) + user(cfg) + project(proj) + legacy(proj/.claude/commands)。
     * config-home/managed 路径经 ClaudePaths 覆写；bare/managed 门控注入隔离真实 env。
     */
    private static void writeMultiSourceFixture(Path temp, String skillName) throws Exception {
        Path project = temp.resolve("proj");
        Files.createDirectories(project.resolve(".git"));
        // managed 源（锁定时仍加载 · CC :686-688 仅 CLAUDE_CODE_DISABLE_POLICY_SKILLS 门控）
        writeSkill(Files.createDirectories(temp.resolve("managed").resolve(".claude").resolve("skills")), skillName);
        // user 源
        writeSkill(Files.createDirectories(temp.resolve("cfg").resolve("skills")), skillName);
        // project 源
        writeSkill(Files.createDirectories(project.resolve(".claude").resolve("skills")), skillName);
        // legacy 源
        Files.writeString(Files.createDirectories(project.resolve(".claude").resolve("commands"))
            .resolve("legacy-cmd.md"), "# Legacy\n");
    }

    private static SkillsLoader newMultiSourceLoader(Path temp) {
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（SkillsLoader.java:380）→ 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        SkillsLoader loader = new SkillsLoader();
        loader.setManagedSkillsEnabledSupplier(() -> true);
        loader.setBareModeSupplier(() -> false);
        return loader;
    }

    @Test
    @DisplayName("getSkillDirCommands managed 门控：CLAUDE_CODE_DISABLE_POLICY_SKILLS → managed 源空 · CC :686-688")
    void getSkillDirCommands_managedGateDisabled(@TempDir Path temp) throws Exception {
        writeMultiSourceFixture(temp, "same-skill");
        SkillsLoader loader = newMultiSourceLoader(temp);
        loader.setManagedSkillsEnabledSupplier(() -> false);

        // managed(user/managed/.claude/skills) 被门控；user/project/legacy 仍加载。
        // P2-19 source 拆分：user 源 → USER（:689 userSettings）；project/legacy 源 →
        // PROJECT_SETTINGS（:695/:713 projectSettings）→ 无 POLICY_SETTINGS 即可证 managed 门控。
        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName).contains("same-skill", "legacy-cmd");
        assertThat(skills).extracting(Command::getSource)
            .as("managed 门控：无 POLICY_SETTINGS；user→USER、project/legacy→PROJECT_SETTINGS（P2-19 拆分）")
            .containsExactlyInAnyOrder(CommandSource.USER, CommandSource.PROJECT_SETTINGS, CommandSource.PROJECT_SETTINGS);
    }

    @Test
    @DisplayName("getSkillDirCommands user skillsLocked：user/project/legacy 空，managed 仍加载 · CC :689/:713")
    void getSkillDirCommands_userSkillsLocked(@TempDir Path temp) throws Exception {
        writeMultiSourceFixture(temp, "managed-skill");
        SkillsLoader loader = newMultiSourceLoader(temp);
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));

        // 仅 managed 存活（skillsLocked 不锁 managed，仅锁 user/project/additional/legacy）
        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName).containsExactly("managed-skill");
        // P2-21：managed（policySettings）source=POLICY_SETTINGS（△-1/组4 对齐 CC :688，不再折叠 USER）+
        //   loadedFrom=SKILLS（CC :467 绝非 bundled —— 旧实现 managed→BUNDLED 有损折叠，deleteList P2-21 第 5 项）
        assertThat(skills).extracting(Command::getSource).containsOnly(CommandSource.POLICY_SETTINGS);
        assertThat(skills).extracting(Command::getLoadedFrom).containsOnly(CommandLoadedFrom.SKILLS);
    }
}

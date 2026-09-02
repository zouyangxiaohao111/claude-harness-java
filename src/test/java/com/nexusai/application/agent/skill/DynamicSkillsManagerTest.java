package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.util.GitIgnoreHelper;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 DynamicSkillsManager 测试 · 对齐 CC loadSkillsDir.ts:820-1075
 * （discoverSkillDirsForPaths / addSkillDirectories / activateConditionalSkillsForPaths /
 * getDynamicSkills / T7 遥测 / onChange）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）: 文件工具发现技能目录 + 条件技能激活 +
 * T7 遥测是 P1-2 的核心行为 —— 目录发现上界（不含 cwd）/ gitignore 拦截 / deeper-first 优先级 /
 * paths 匹配激活任一环节偏离 CC，动态技能就会在错误时机暴露或不暴露。
 */
@DisplayName("P1-2 DynamicSkillsManager · 动态/条件技能发现+激活+T7")
class DynamicSkillsManagerTest {

    /** 默认 gitExec 桩：exit 1 = 不忽略（fail-open 等价 git 无命中）。 */
    private static GitIgnoreHelper.ExecResult notIgnored(String[] args, String cwd) {
        return new GitIgnoreHelper.ExecResult(1, "", "");
    }

    /** gitignore 命中桩：exit 0 = 忽略。 */
    private static GitIgnoreHelper.ExecResult ignored(String[] args, String cwd) {
        return new GitIgnoreHelper.ExecResult(0, "", "");
    }

    private static DynamicSkillsManager newManager() {
        DynamicSkillsManager m = new DynamicSkillsManager();
        m.setGitExec(DynamicSkillsManagerTest::notIgnored);
        return m;
    }

    /** 写一个最小 SKILL.md（frontmatter name 显式）到 skillsRoot/skillName/SKILL.md。 */
    private static void writeSkill(Path skillsRoot, String skillName) throws Exception {
        writeSkill(skillsRoot, skillName, skillName);
    }

    private static void writeSkill(Path skillsRoot, String skillName, String name) throws Exception {
        Path dir = skillsRoot.resolve(skillName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    // ══════════════════════════════════════════════════════════════════════
    // discoverSkillDirsForPaths · 对齐 CC loadSkillsDir.ts:861-915
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("discoverSkillDirsForPaths：从文件父目录向上走（不含 cwd），deeper-first 返回存在的 .claude/skills")
    void discoversDirsWalkUpDeeperFirst(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("a/b/c/file.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        // shallower: workspace/a/b/.claude/skills；deeper: workspace/a/b/c/.claude/skills
        // 注: cwd 级（workspace/.claude/skills）不发现 —— CC :874-876 "CWD-level skills are
        // loaded at startup, so we only discover nested ones"
        Files.createDirectories(workspace.resolve("a/b/.claude/skills"));
        Files.createDirectories(workspace.resolve("a/b/c/.claude/skills"));

        DynamicSkillsManager m = newManager();
        List<String> dirs = m.discoverSkillDirsForPaths(List.of(file.toString()), workspace);

        // CC :912-914 deeper-first sort
        assertThat(dirs)
            .containsExactly(
                workspace.resolve("a/b/c/.claude/skills").toString(),
                workspace.resolve("a/b/.claude/skills").toString());
    }

    @Test
    @DisplayName("discoverSkillDirsForPaths：cwd 上界不含 cwd 本身（文件在 cwd 级 → 空）· CC :874-876")
    void cwdIsUpperBoundExclusive(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("top.txt");
        Files.writeString(file, "x");
        Files.createDirectories(workspace.resolve(".claude/skills"));

        DynamicSkillsManager m = newManager();
        // dirname(top.txt) == workspace == cwd，不满足 startsWith(cwd+sep) → 空
        assertThat(m.discoverSkillDirsForPaths(List.of(file.toString()), workspace)).isEmpty();
    }

    @Test
    @DisplayName("discoverSkillDirsForPaths：dynamicSkillDirs 去重（同目录二次发现不重复返回）· CC :882")
    void dedupesSkillDirs(@TempDir Path workspace) throws Exception {
        Path fileA = workspace.resolve("src/a.txt");
        Path fileB = workspace.resolve("src/b.txt");
        Files.createDirectories(fileA.getParent());
        Files.writeString(fileA, "a");
        Files.writeString(fileB, "b");
        Files.createDirectories(workspace.resolve("src/.claude/skills"));

        DynamicSkillsManager m = newManager();
        List<String> first = m.discoverSkillDirsForPaths(List.of(fileA.toString()), workspace);
        List<String> second = m.discoverSkillDirsForPaths(List.of(fileB.toString()), workspace);

        // hit/miss 都记录 → 第二次不再返回（CC :882 dynamicSkillDirs.has(skillDir) 跳过）
        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("discoverSkillDirsForPaths：gitignore 拦截（git check-ignore exit 0 → 目录不发现）· CC :892")
    void gitignoredDirSkipped(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("node_modules/pkg/x.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.createDirectories(workspace.resolve("node_modules/pkg/.claude/skills"));

        DynamicSkillsManager m = newManager();
        m.setGitExec(DynamicSkillsManagerTest::ignored); // 全部视为 gitignored

        assertThat(m.discoverSkillDirsForPaths(List.of(file.toString()), workspace)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    // addSkillDirectories · 对齐 CC loadSkillsDir.ts:923-975
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("addSkillDirectories：加载目录技能 → getDynamicSkills + T7 遥测 + onChange 触发")
    void addSkillDirectories_loadsAndFires(@TempDir Path workspace) throws Exception {
        Path skillDir = workspace.resolve(".claude/skills");
        writeSkill(skillDir, "my-skill");

        DynamicSkillsManager m = newManager();
        Telemetry t = new Telemetry();
        m.setTelemetry(t);
        AtomicBoolean onChange = new AtomicBoolean(false);
        m.onDynamicSkillsLoaded(() -> onChange.set(true));

        m.addSkillDirectories(List.of(skillDir.toString()));

        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("my-skill");
        assertThat(m.getConditionalSkillCount()).isZero();
        assertThat(onChange.get()).as("onChange 必须触发（CC :974 skillsLoaded.emit）").isTrue();
        assertThat(t.getCounter(DynamicSkillsManager.TENGU_DYNAMIC_SKILLS_CHANGED))
            .as("T7 遥测必须上报（CC :962-969）").isEqualTo(1);
    }

    @Test
    @DisplayName("addSkillDirectories：reverse 处理 shallower 先让 deeper 覆盖同名技能 · CC :945-951")
    void deeperOverridesShallower(@TempDir Path workspace) throws Exception {
        Path shallower = workspace.resolve(".claude/skills");
        Path deeper = workspace.resolve("sub/deep/.claude/skills");
        writeSkill(shallower, "dup", "dup");
        writeSkill(deeper, "dup", "dup");
        // 改 content 区分优先级：deeper 版 content 含 marker
        Files.writeString(deeper.resolve("dup/SKILL.md"),
            "---\nname: dup\n---\n# deeper-version\n");
        Files.writeString(shallower.resolve("dup/SKILL.md"),
            "---\nname: dup\n---\n# shallower-version\n");

        DynamicSkillsManager m = newManager();
        // dirs 按 deeper-first 传入（discoverSkillDirsForPaths 的返回序）
        m.addSkillDirectories(List.of(deeper.toString(), shallower.toString()));

        Command dup = m.getDynamicSkills().stream()
            .filter(c -> "dup".equals(c.getName())).findFirst().orElseThrow();
        // reverse 循环: shallower 先加载、deeper 后 put 覆盖 → deeper content 胜出
        assertThat(dup.getContent()).contains("deeper-version");
    }

    // ══════════════════════════════════════════════════════════════════════
    // activateConditionalSkillsForPaths · 对齐 CC loadSkillsDir.ts:997-1058
    // ══════════════════════════════════════════════════════════════════════

    private static Command conditionalSkill(String name, String... paths) {
        Command c = new Command();
        c.setName(name);
        c.setPaths(List.of(paths));
        return c;
    }

    @Test
    @DisplayName("activateConditionalSkillsForPaths：paths 匹配 → 激活入 dynamicSkills + T7 + onChange")
    void activatesMatchingConditional(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        Telemetry t = new Telemetry();
        m.setTelemetry(t);
        AtomicInteger onChangeCount = new AtomicInteger(0);
        m.onDynamicSkillsLoaded(onChangeCount::incrementAndGet);
        m.registerConditional(conditionalSkill("my-cond", "src/**/*.ts"));

        Path file = workspace.resolve("src/main/Foo.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");

        List<String> activated = m.activateConditionalSkillsForPaths(List.of(file.toString()), workspace);

        assertThat(activated).containsExactly("my-cond");
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("my-cond");
        assertThat(m.getConditionalSkillCount()).isZero(); // 激活后从 conditional 移除（CC :1032）
        assertThat(onChangeCount.get()).as("onChange 必须触发（CC :1054）").isEqualTo(1);
        assertThat(t.getCounter(DynamicSkillsManager.TENGU_DYNAMIC_SKILLS_CHANGED)).isEqualTo(1);
    }

    @Test
    @DisplayName("activateConditionalSkillsForPaths：paths 不匹配 → 不激活（conditional 保留）")
    void nonMatchingStaysConditional(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        m.registerConditional(conditionalSkill("docs-cond", "docs/**"));

        Path file = workspace.resolve("src/Foo.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");

        assertThat(m.activateConditionalSkillsForPaths(List.of(file.toString()), workspace)).isEmpty();
        assertThat(m.getConditionalSkillCount()).isEqualTo(1);
        assertThat(m.getDynamicSkills()).isEmpty();
    }

    @Test
    @DisplayName("activateConditionalSkillsForPaths：.. 开头相对路径跳过（文件在 cwd 外）· CC :1021-1027")
    void pathsOutsideCwdSkipped(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        m.registerConditional(conditionalSkill("cond", "**/*.ts"));

        Path outside = workspace.getParent().resolve("outside.ts");
        Files.writeString(outside, "x");

        assertThat(m.activateConditionalSkillsForPaths(List.of(outside.toString()), workspace)).isEmpty();
        assertThat(m.getConditionalSkillCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("activateConditionalSkillsForPaths：已激活去重（二次调用空）· CC activatedConditionalSkillNames")
    void alreadyActivatedDedup(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        m.registerConditional(conditionalSkill("cond", "src/**"));

        Path f1 = workspace.resolve("src/a.ts");
        Path f2 = workspace.resolve("src/b.ts");
        Files.createDirectories(f1.getParent());
        Files.writeString(f1, "a");
        Files.writeString(f2, "b");

        assertThat(m.activateConditionalSkillsForPaths(List.of(f1.toString()), workspace))
            .containsExactly("cond");
        // 已激活 → conditional 已移除 → 二次调用空（CC :1031-1032 delete + activated 记录）
        assertThat(m.activateConditionalSkillsForPaths(List.of(f2.toString()), workspace)).isEmpty();
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("cond");
    }

    @Test
    @DisplayName("clearDynamicSkills：4 状态全清 · CC :1070-1075")
    void clearDynamicSkillsClearsAll(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        m.registerConditional(conditionalSkill("cond", "src/**"));
        Path skillDir = workspace.resolve(".claude/skills");
        writeSkill(skillDir, "dyn");
        m.addSkillDirectories(List.of(skillDir.toString()));
        m.discoverSkillDirsForPaths(List.of(workspace.resolve("x.txt").toString()), workspace);

        assertThat(m.getDynamicSkills()).isNotEmpty();
        m.clearDynamicSkills();
        assertThat(m.getDynamicSkills()).isEmpty();
        assertThat(m.getConditionalSkillCount()).isZero();
    }

    @Test
    @DisplayName("M27: clearConditionalState 双清 conditionalSkills + activatedConditionalSkillNames（CC clearSkillCaches :809-810）且不清 dynamicSkills")
    void clearConditionalStateClearsOnlyConditional(@TempDir Path workspace) throws Exception {
        // WHY: M27（R2I-DEC-6 / R2D-DEC-1）——SkillRegistry.refresh() 对齐 CC clearSkillCaches
        //   （loadSkillsDir.ts:806-811）须双清条件状态（conditionalSkills + activatedConditionalSkillNames），
        //   但<b>不得</b>清 dynamicSkills/dynamicSkillDirs（CC clearSkillCaches 不清动态技能池；
        //   4 态全清是 clearDynamicSkills :1070-1075）。若 refresh 误用 clearDynamicSkills，
        //   skill 热更新后已激活动态技能消失，偏离 CC。
        DynamicSkillsManager m = newManager();
        // 条件技能先注册再激活（paths 命中 → 移入 dynamicSkills + 记录 activated）
        m.registerConditional(conditionalSkill("cond", "src/**"));
        Path file = workspace.resolve("src/a.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a");
        assertThat(m.activateConditionalSkillsForPaths(List.of(file.toString()), workspace))
            .containsExactly("cond");
        assertThat(m.getConditionalSkillCount()).isZero();
        assertThat(m.isActivatedConditionalSkill("cond")).isTrue();
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("cond");
        // 未激活条件技能保留（待激活态）
        m.registerConditional(conditionalSkill("cond-2", "docs/**"));
        assertThat(m.getConditionalSkillCount()).isEqualTo(1);

        // 对齐 CC clearSkillCaches loadSkillsDir.ts:809-810 双清
        m.clearConditionalState();

        assertThat(m.getConditionalSkillCount()).isZero();
        assertThat(m.isActivatedConditionalSkill("cond")).isFalse();
        // dynamicSkills 保留（CC clearSkillCaches 不清动态技能池）
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("cond");
    }

    // ══════════════════════════════════════════════════════════════════════
    // △-6 onDynamicSkillsLoaded 多监听 + unsubscribe · CC loadSkillsDir.ts:839-851
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-6 多监听 + unsubscribe：多监听均触发，unsubscribe 后该监听不再触发")
    void multipleListenersAndUnsubscribe(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        AtomicInteger l1 = new AtomicInteger(0);
        AtomicInteger l2 = new AtomicInteger(0);
        Runnable unsub1 = m.onDynamicSkillsLoaded(l1::incrementAndGet);
        m.onDynamicSkillsLoaded(l2::incrementAndGet);

        Path skillDir = workspace.resolve(".claude/skills");
        writeSkill(skillDir, "dyn");
        m.addSkillDirectories(List.of(skillDir.toString()));

        assertThat(l1.get()).as("监听 1 触发").isEqualTo(1);
        assertThat(l2.get()).as("监听 2 触发").isEqualTo(1);

        // unsubscribe l1 → 后续变更仅 l2 触发（CC onDynamicSkillsLoaded 返回 unsubscribe）
        unsub1.run();
        m.addSkillDirectories(List.of(skillDir.toString()));
        assertThat(l1.get()).as("unsubscribe 后监听 1 不再触发").isEqualTo(1);
        assertThat(l2.get()).as("监听 2 继续触发").isEqualTo(2);
    }

    @Test
    @DisplayName("△-6 监听器异常被捕获不中断其他监听（CC onDynamicSkillsLoaded 每监听 try/catch）")
    void listenerExceptionIsolated(@TempDir Path workspace) throws Exception {
        DynamicSkillsManager m = newManager();
        AtomicInteger healthy = new AtomicInteger(0);
        m.onDynamicSkillsLoaded(() -> {
            throw new IllegalStateException("boom");
        });
        m.onDynamicSkillsLoaded(healthy::incrementAndGet);

        Path skillDir = workspace.resolve(".claude/skills");
        writeSkill(skillDir, "dyn");
        m.addSkillDirectories(List.of(skillDir.toString()));

        // 抛异常的监听不中断后续监听（CC :844-848 每监听 try/catch）
        assertThat(healthy.get()).as("健康监听仍触发").isEqualTo(1);
    }
}

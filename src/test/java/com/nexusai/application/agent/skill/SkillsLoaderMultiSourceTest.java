package com.nexusai.application.agent.skill;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
 * P2-20 五源技能加载测试（RED→GREEN）· 对齐 CC getSkillDirCommands(cwd)（loadSkillsDir.ts:638-804）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>五源合并</b>——CC :679-724：managed/user/project-up-to-home/additional/legacy 并行加载后扁平合并。</li>
 *   <li><b>per-source 门控</b>——managed=CLAUDE_CODE_DISABLE_POLICY_SKILLS（:686-688）、user=skillsLocked（:689-691）、
 *       project+additional=projectSettingsEnabled（:692-708）、legacy=skillsLocked（:713）。</li>
 *   <li><b>bare 模式</b>——CC :658-675：仅 additionalDirs/.claude/skills，无去重。</li>
 *   <li><b>realpath 去重 first-wins</b>——CC :728-763：同一物理文件跨源只载一次（managed&gt;user&gt;project&gt;additional&gt;legacy）。</li>
 *   <li><b>条件分离</b>——CC :771-790：paths 非空且未激活 → registerConditional，不随返回值暴露。</li>
 *   <li><b>POJO 单目录不回归</b>——loadFromDirectory/loadFromDirectoryUnconditional 保留（CC loadSkillsFromSkillsDir 原语）。</li>
 * </ol>
 *
 * <p>环境确定性：Java 无法进程内改 env（CLAUDE_CODE_SIMPLE / CLAUDE_CODE_DISABLE_POLICY_SKILLS），
 * 经 {@link SkillsLoader#setBareModeSupplier} / {@link SkillsLoader#setManagedSkillsEnabledSupplier}
 * 注入（P2-20 测试 seam）；config-home/managed 路径经 {@link ClaudePaths} 覆写。
 */
class SkillsLoaderMultiSourceTest {

    /** G5：nexusai 自有根唯一 appName（loader user 源 = nexusai 优先 + claude 回落，双目录都要隔离）。 */
    private static final java.util.concurrent.atomic.AtomicInteger NEXUSAI_SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        MarkdownConfigLoader.clearCache();
    }

    /** 写一个最小 SKILL.md（frontmatter name + 可选额外字段）。 */
    private static void writeSkill(Path dir, String name) throws Exception {
        Path skillDir = dir.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: " + name + "\n---\n# " + name + "\n");
    }

    /** 构造五源 loader（确定性注入 bare/managed 门控，隔离真实 env）。 */
    private static SkillsLoader newLoader(Path configHome, Path managed, List<String> additionalDirs) {
        ClaudePaths.setConfigDirOverride(configHome.toString());
        ClaudePaths.setManagedFilePathOverride(managed.toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（SkillsLoader.java:380）→ 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + NEXUSAI_SEQ.incrementAndGet());
        SkillsLoader loader = new SkillsLoader();
        loader.setAdditionalDirectoriesSupplier(() -> additionalDirs != null ? additionalDirs : List.of());
        loader.setManagedSkillsEnabledSupplier(() -> true);   // 隔离 CLAUDE_CODE_DISABLE_POLICY_SKILLS env
        loader.setBareModeSupplier(() -> false);              // 隔离 CLAUDE_CODE_SIMPLE env
        return loader;
    }

    /** 搭建五源 fixture（project 含 .git 停止边界）。 */
    private static void setupFiveSources(Path temp) throws Exception {
        Path project = temp.resolve("proj");
        Files.createDirectories(project.resolve(".git"));
        // managed 源：{managed}/.claude/skills
        Path managedSkills = Files.createDirectories(temp.resolve("managed").resolve(".claude").resolve("skills"));
        writeSkill(managedSkills, "managed-skill");
        // user 源：{cfg}/skills
        Path userSkills = Files.createDirectories(temp.resolve("cfg").resolve("skills"));
        writeSkill(userSkills, "user-skill");
        // project 源：{proj}/.claude/skills
        Path projectSkills = Files.createDirectories(project.resolve(".claude").resolve("skills"));
        writeSkill(projectSkills, "proj-skill");
        // additional 源：{add}/.claude/skills
        Path addSkills = Files.createDirectories(temp.resolve("add").resolve(".claude").resolve("skills"));
        writeSkill(addSkills, "add-skill");
        // legacy 源：{proj}/.claude/commands
        Path commands = Files.createDirectories(project.resolve(".claude").resolve("commands"));
        Files.writeString(commands.resolve("legacy-cmd.md"), "# Legacy\n");
    }

    @Test
    @DisplayName("五源合并：managed+user+project+additional+legacy 全量返回 · CC :679-724")
    void fiveSourceMerge(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());

        assertThat(skills).extracting(Command::getName)
            .containsExactlyInAnyOrder("managed-skill", "user-skill", "proj-skill", "add-skill", "legacy-cmd");
        // P2-21 source/loadedFrom 落位 + P2-19 source 拆分：managed（policySettings）→ source=POLICY_SETTINGS
        //   （△-1/组4 对齐 CC loadSkillsDir.ts:688）；user → source=USER（:689 userSettings）；
        //   project/additional → source=PROJECT_SETTINGS（P2-19 拆分，CC :695/:704 'projectSettings'）；
        //   四磁盘源 loadedFrom=SKILLS（CC loadSkillsDir.ts:467 —— managed 绝非 bundled）；
        //   legacy loadedFrom=COMMANDS_DEPRECATED（CC :608）
        for (Command c : skills) {
            switch (c.getName()) {
                case "managed-skill" -> {
                    assertThat(c.getSource()).isEqualTo(CommandSource.POLICY_SETTINGS);
                    assertThat(c.getLoadedFrom()).isEqualTo(CommandLoadedFrom.SKILLS);
                }
                case "user-skill" -> {
                    assertThat(c.getSource()).isEqualTo(CommandSource.USER);
                    assertThat(c.getLoadedFrom()).isEqualTo(CommandLoadedFrom.SKILLS);
                }
                case "proj-skill", "add-skill" -> {
                    assertThat(c.getSource()).isEqualTo(CommandSource.PROJECT_SETTINGS);
                    assertThat(c.getLoadedFrom()).isEqualTo(CommandLoadedFrom.SKILLS);
                }
                case "legacy-cmd" -> {
                    // 项目级 legacy 命令 source=projectSettings（CC markdownConfigLoader :357-372
                    // 项目目录源 projectSettings；Java fromString P2-19 不再折叠 USER）
                    assertThat(c.getSource()).isEqualTo(CommandSource.PROJECT_SETTINGS);
                    assertThat(c.getLoadedFrom()).isEqualTo(CommandLoadedFrom.COMMANDS_DEPRECATED);
                }
            }
        }
    }

    @Test
    @DisplayName("realpath 去重 first-wins：config-home 在项目内时同一物理文件只载一次 · CC :728-763")
    void realpathDedupFirstWins(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("proj");
        Files.createDirectories(project.resolve(".git"));
        // config-home 指向项目内 .claude → userDir == projectDir（同一物理目录）
        Path configHome = Files.createDirectories(project.resolve(".claude"));
        Path skillsDir = Files.createDirectories(project.resolve(".claude").resolve("skills"));
        writeSkill(skillsDir, "same-skill");

        SkillsLoader loader = newLoader(configHome, temp.resolve("managed"), List.of());

        List<Command> skills = loader.getSkillDirCommands(project.toString());
        assertThat(skills).extracting(Command::getName).containsExactly("same-skill");
    }

    @Test
    @DisplayName("managed 门控：CLAUDE_CODE_DISABLE_POLICY_SKILLS → managed 源空 · CC :686-688")
    void managedGate_disablesManagedSource(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setManagedSkillsEnabledSupplier(() -> false);

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName)
            .containsExactlyInAnyOrder("user-skill", "proj-skill", "add-skill", "legacy-cmd")
            .doesNotContain("managed-skill");
    }

    @Test
    @DisplayName("user skillsLocked：user/project/additional/legacy 空，managed 仍加载 · CC :689-713")
    void skillsLocked_keepsManaged(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setSettingsSupplier(() -> Map.of("strictPluginOnlyCustomization", true));

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        // managed 不受 skillsLocked 门控（仅 CLAUDE_CODE_DISABLE_POLICY_SKILLS）；其余 4 源全空
        assertThat(skills).extracting(Command::getName).containsExactly("managed-skill");
    }

    @Test
    @DisplayName("bare 模式：仅加载 --add-dir 目录；无 additionalDirs → 空 · CC :658-675")
    void bareMode_onlyAdditionalDirs(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setBareModeSupplier(() -> true);

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName).containsExactly("add-skill");

        // bare + 无 additionalDirs → 空（CC :659 additionalDirs.length===0）
        SkillsLoader noAdd = newLoader(temp.resolve("cfg"), temp.resolve("managed"), List.of());
        noAdd.setBareModeSupplier(() -> true);
        assertThat(noAdd.getSkillDirCommands(temp.resolve("proj").toString())).isEmpty();
    }

    @Test
    @DisplayName("条件分离：paths 非空且未激活 → registerConditional，不随返回值暴露 · CC :771-790")
    void conditionalSkill_separated(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("proj");
        Files.createDirectories(project.resolve(".git"));
        Path configHome = temp.resolve("cfg");
        Path userSkills = Files.createDirectories(configHome.resolve("skills"));
        // 条件技能（paths 非空）
        Path cond = Files.createDirectories(userSkills.resolve("cond-skill"));
        Files.writeString(cond.resolve("SKILL.md"),
            "---\nname: cond-skill\npaths:\n  - src/**\n---\n# Cond\n");
        // 无条件技能
        writeSkill(userSkills, "plain-skill");

        DynamicSkillsManager manager = new DynamicSkillsManager();
        SkillsLoader loader = newLoader(configHome, temp.resolve("managed"), List.of());
        loader.setDynamicSkillsManager(manager);

        List<Command> skills = loader.getSkillDirCommands(project.toString());
        assertThat(skills).extracting(Command::getName).containsExactly("plain-skill").doesNotContain("cond-skill");
        assertThat(manager.getConditionalSkillCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POJO 单目录不回归：loadFromDirectory / loadFromDirectoryUnconditional 保留（CC loadSkillsFromSkillsDir 原语）")
    void pojoSingleDirPreserved(@TempDir Path skillsRoot) throws Exception {
        writeSkill(skillsRoot, "my-skill");
        SkillsLoader loader = new SkillsLoader();

        assertThat(loader.loadFromDirectory(skillsRoot.toString()))
            .extracting(Command::getName).containsExactly("my-skill");
        assertThat(loader.loadFromDirectoryUnconditional(skillsRoot.toString()))
            .extracting(Command::getName).containsExactly("my-skill");
    }

    @Test
    @DisplayName("P2-2 user 源开关：userSettings 禁用 → user 源空，managed/project/additional/legacy 保留 · CC isSettingSourceEnabled('userSettings') :689-691")
    void userSourceSwitch_disablesUser(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setUserSkillsEnabledSupplier(() -> false);

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName)
            .containsExactlyInAnyOrder("managed-skill", "proj-skill", "add-skill", "legacy-cmd")
            .doesNotContain("user-skill");
    }

    @Test
    @DisplayName("P2-2 project 源开关：projectSettings 禁用 → project+additional 空，user/managed/legacy 保留 · CC projectSettingsEnabled :651-652/:692-708")
    void projectSourceSwitch_disablesProjectAndAdditional(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setProjectSkillsEnabledSupplier(() -> false);

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).extracting(Command::getName)
            .containsExactlyInAnyOrder("managed-skill", "user-skill", "legacy-cmd")
            .doesNotContain("proj-skill", "add-skill");
    }

    @Test
    @DisplayName("P2-2 bare + projectSettings 禁用 → 空（CC :659 !projectSettingsEnabled）")
    void bareMode_projectSettingsDisabled_empty(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"),
            List.of(temp.resolve("add").toString()));
        loader.setBareModeSupplier(() -> true);
        loader.setProjectSkillsEnabledSupplier(() -> false);

        List<Command> skills = loader.getSkillDirCommands(temp.resolve("proj").toString());
        assertThat(skills).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P1a F-04] 无会话 cwd：默认 cwdSupplier 不得回落进程 user.dir（裁定 (B)：跳过 + ≥WARN）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 未绑定会话（cwd 解析为 null）时，旧默认 {@code cwdSupplier = () -> System.getProperty("user.dir")}
     * 会把<b>后端 JVM 启动目录</b>当会话项目根 ⇒ 该会话的技能列表静默混入后端自身仓库的项目级技能
     * （A 项目会话看到 B 项目技能），且只有 DEBUG 可见（生产不可观测）。
     *
     * <p><b>鉴别力来源（夹具自检）</b>：本仓 {@code backend/.claude/skills} 真实存在项目级技能，
     * 故同一个 loader 用<b>显式 user.dir</b> 调用时必须扫出 PROJECT_SETTINGS 源（正向对照）；
     * cwd=null 时必须一个都没有。旧实现在 ② 处回落 user.dir ⇒ 该断言变红。
     */
    @Test
    @DisplayName("[P1a F-04] 默认 cwdSupplier 不得回落进程 user.dir：cwd=null ⇒ 跳过 project/legacy 源 + ≥WARN")
    void noCwd_defaultSupplier_neverFallsBackToProcessUserDir(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"), List.of());
        String processCwd = System.getProperty("user.dir");

        // ① 正向对照（夹具自检）：显式传 user.dir（= 本模块目录）→ 确实扫出 PROJECT_SETTINGS 源
        List<Command> withProcessCwd = loader.getSkillDirCommands(processCwd);
        assertThat(withProcessCwd).extracting(Command::getSource)
            .as("夹具自检：显式 cwd=user.dir 必须能扫出 PROJECT_SETTINGS 源（否则本用例零鉴别力）")
            .contains(CommandSource.PROJECT_SETTINGS);

        // ② 主断言：cwd=null（未绑定会话）→ 默认 supplier 必须为 null，⛔ 不得回落 user.dir
        ListAppender<ILoggingEvent> app = attachWarnCapture();
        try {
            List<Command> noCwd = loader.getSkillDirCommands(null);

            assertThat(noCwd).extracting(Command::getSource)
                .as("无会话 cwd 必须跳过 project/legacy（两者都是 PROJECT_SETTINGS 源）——"
                    + "旧实现在此回落 user.dir，把后端自身的项目级技能混进该会话列表")
                .doesNotContain(CommandSource.PROJECT_SETTINGS);
            assertThat(noCwd).extracting(Command::getName)
                .as("cwd 依赖源按名核对：project(proj-skill)/legacy(legacy-cmd) 均不得出现")
                .doesNotContain("proj-skill", "legacy-cmd");
            assertThat(noCwd).extracting(Command::getName)
                .as("cwd 无关源继续加载（裁定 (B)：不抛、列表仍可用）")
                .contains("user-skill", "managed-skill");
            assertThat(app.list.stream().map(ILoggingEvent::getFormattedMessage))
                .as("跳过必须 ≥WARN（规则十二：取不到会话态不得静默，禁只 DEBUG）")
                .anyMatch(m -> m.contains("无会话 cwd"));
        } finally {
            detachWarnCapture(app);
        }
    }

    /** 注入的 cwdSupplier 返回 null（生产 = SessionProjectRoot 未绑定）：同裁定 (B) 的跳过 + ≥WARN。 */
    @Test
    @DisplayName("[P1a F-04] 注入的 cwdSupplier 返回 null ⇒ 同款跳过 + ≥WARN（且 null 路径不 NPE）")
    void noCwd_injectedNullSupplier_skipsCwdDependentSources(@TempDir Path temp) throws Exception {
        setupFiveSources(temp);
        SkillsLoader loader = newLoader(temp.resolve("cfg"), temp.resolve("managed"), List.of());
        loader.setCwdSupplier(() -> null);

        ListAppender<ILoggingEvent> app = attachWarnCapture();
        try {
            List<Command> noCwd = loader.getSkillDirCommands(null);

            assertThat(noCwd).extracting(Command::getSource)
                .as("注入 null cwdSupplier ⇒ 无 PROJECT_SETTINGS 源（旧实现在此处对 null cwd 直接派发，"
                    + "会 NPE / 静默扫到进程 cwd）")
                .doesNotContain(CommandSource.PROJECT_SETTINGS);
            assertThat(noCwd).extracting(Command::getName)
                .doesNotContain("proj-skill", "legacy-cmd")
                .contains("user-skill");
            assertThat(app.list.stream().map(ILoggingEvent::getFormattedMessage))
                .as("≥WARN 必须留下可观测信号")
                .anyMatch(m -> m.contains("无会话 cwd"));
        } finally {
            detachWarnCapture(app);
        }
    }

    // ── logback 捕获 helpers（≥WARN 判据） ──

    private static ListAppender<ILoggingEvent> attachWarnCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SkillsLoader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachWarnCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SkillsLoader.class);
        logger.detachAppender(appender);
    }
}

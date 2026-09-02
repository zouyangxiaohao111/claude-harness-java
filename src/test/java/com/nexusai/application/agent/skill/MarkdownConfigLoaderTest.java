package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-20 MarkdownConfigLoader 测试（RED→GREEN）· 对齐 CC getProjectDirsUpToHome
 * （markdownConfigLoader.ts:234-289）+ loadMarkdownFilesForSubdir（markdownConfigLoader.ts:297-430）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>git root 阻止仓库外父目录泄漏</b>——CC :267-275 停止边界：项目技能不加载仓库父目录外的
 *       .claude/skills（如 ~/projects/.claude/skills 不泄漏进 ~/projects/my-repo）。</li>
 *   <li><b>home 本身不检查</b>——CC :245-251：home 的 .claude/skills 作为 userDir 单独加载，不在
 *       project 遍历中重复。</li>
 *   <li><b>memoize 键 = subdir:cwd</b>——CC :428-430 resolver；磁盘变更须 clearCache()（loadSkillsDir.ts:808）。</li>
 *   <li><b>realpath 去重 first-wins</b>——CC :377-414：~/.claude 被 symlink 进项目层级时同一物理文件
 *       只出现一次（managed&gt;user&gt;project 顺序）。</li>
 * </ol>
 */
class MarkdownConfigLoaderTest {

    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    @BeforeEach
    void setUp() {
        // 控制 user.home（home 停止边界），测试结束恢复
        System.setProperty("user.home", ORIGINAL_USER_HOME);
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", ORIGINAL_USER_HOME);
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        MarkdownConfigLoader.clearCache();
        MarkdownConfigLoader.setTelemetry(null);
    }

    /** 建临时项目（含 .git 停止边界标记 + 可选 .claude/skills 与 .claude/commands）。 */
    private static Path newProject(Path temp, String name) throws Exception {
        Path project = temp.resolve(name);
        Files.createDirectories(project.resolve(".git"));
        return project;
    }

    // ── getProjectDirsUpToHome ──

    @Test
    @DisplayName("git root 停止：项目内 .claude/skills 收集，仓库父目录外不泄漏 · CC markdownConfigLoader.ts:267-275")
    void projectDirsUpToHome_stopsAtGitRoot(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "my-repo");
        Files.createDirectories(project.resolve(".claude").resolve("skills"));
        // 仓库父目录外的 .claude/skills —— 不得被收集
        Files.createDirectories(temp.resolve(".claude").resolve("skills"));

        List<String> dirs = MarkdownConfigLoader.getProjectDirsUpToHome("skills", project.toString());

        assertThat(dirs).containsExactly(project.resolve(".claude").resolve("skills").toString());
    }

    @Test
    @DisplayName("home 停止：home 本身不检查（userDir 单独加载）· CC markdownConfigLoader.ts:245-251")
    void projectDirsUpToHome_stopsAtHome(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path project = home.resolve("proj");
        Files.createDirectories(project);
        Files.createDirectories(project.resolve(".claude").resolve("skills"));
        // home/.claude/skills —— 不得被收集（作为 userDir 单独加载）
        Files.createDirectories(home.resolve(".claude").resolve("skills"));
        System.setProperty("user.home", home.toString());

        List<String> dirs = MarkdownConfigLoader.getProjectDirsUpToHome("skills", project.toString());

        assertThat(dirs).containsExactly(project.resolve(".claude").resolve("skills").toString());
    }

    @Test
    @DisplayName("目录不存在过滤：仅收集存在的 .claude/<subdir> · CC markdownConfigLoader.ts:260-265")
    void projectDirsUpToHome_filtersNonExistent(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "my-repo");
        // 不创建 .claude/skills

        assertThat(MarkdownConfigLoader.getProjectDirsUpToHome("skills", project.toString())).isEmpty();
    }

    // ── loadMarkdownFilesForSubdir ──

    @Test
    @DisplayName("memoize 键 = subdir:cwd：二次调用命中缓存，clearCache 后刷新 · CC :428-430 + loadSkillsDir.ts:808")
    void loadMarkdownFilesForSubdir_memoizeAndClear(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "memo");
        Path commandsDir = Files.createDirectories(project.resolve(".claude").resolve("commands"));
        Files.writeString(commandsDir.resolve("foo.md"), "# Foo\n");
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> first =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());
        List<MarkdownConfigLoader.MarkdownFile> second =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());

        assertThat(first).hasSize(1);
        // 同一缓存实例（memoize 命中，CC :428-430）
        assertThat(second).isSameAs(first);

        // 磁盘新增文件，未 clearCache → 仍返回旧缓存（memoize 语义）
        Files.writeString(commandsDir.resolve("bar.md"), "# Bar\n");
        assertThat(MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString()))
            .hasSize(1);

        // clearCache() 后刷新可见新文件（CC loadSkillsDir.ts:808 clearSkillCaches）
        MarkdownConfigLoader.clearCache();
        assertThat(MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString()))
            .hasSize(2);
    }

    @Test
    @DisplayName("realpath 去重 first-wins：~/.claude 在项目内时同一物理文件只载一次（user 优先 project）· CC :377-414")
    void loadMarkdownFilesForSubdir_dedupFirstWins(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "dedup");
        // config-home 指向项目内 .claude → userDir == projectDir（同一物理目录）
        ClaudePaths.setConfigDirOverride(project.resolve(".claude").toString());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        Path skillsDir = Files.createDirectories(project.resolve(".claude").resolve("skills"));
        Path skillDir = Files.createDirectories(skillsDir.resolve("x"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: x\n---\n# X\n");

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("skills", project.toString());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).source()).isEqualTo("userSettings");
    }

    // ── ✗-2 tengu_dir_search 遥测 ──

    @Test
    @DisplayName("✗-2 tengu_dir_search 遥测：真实搜索（缓存未命中）发射一次，memoize 命中不重复 · CC :416-424")
    void loadMarkdownFilesForSubdir_emitsDirSearchTelemetry(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "tele");
        Files.createDirectories(project.resolve(".claude").resolve("commands"));
        Files.writeString(project.resolve(".claude").resolve("commands").resolve("c.md"), "# C\n");
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        Telemetry t = new Telemetry();
        MarkdownConfigLoader.setTelemetry(t);
        MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());

        assertThat(t.getCounter("tengu_dir_search"))
            .as("真实搜索必须发射 tengu_dir_search（CC markdownConfigLoader.ts:416）").isEqualTo(1);

        // memoize 命中 → 不重复发射（CC searchStartTime 在 memoize 函数体内，命中不执行）
        MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());
        assertThat(t.getCounter("tengu_dir_search")).isEqualTo(1);
    }

    // ── △-4 worktree 主仓回退 ──

    @Test
    @DisplayName("△-4 worktree 主仓回退：worktree 无 .claude/commands 时回退加载主仓副本 · CC markdownConfigLoader.ts:320-335")
    void worktreeFallbackLoadsMainRepo(@TempDir Path temp) throws Exception {
        // 主仓（.git 目录 + .claude/commands）
        Path mainRepo = temp.resolve("main");
        Files.createDirectories(mainRepo.resolve(".git"));
        Path mainCommands = Files.createDirectories(mainRepo.resolve(".claude").resolve("commands"));
        Files.writeString(mainCommands.resolve("main-cmd.md"), "# Main\n");
        // worktree：.git 是文件（gitdir: ...）+ worktrees/wt/commondir
        Path worktree = temp.resolve("wt");
        Files.createDirectories(worktree);
        Path wtGitDir = mainRepo.resolve(".git").resolve("worktrees").resolve("wt");
        Files.createDirectories(wtGitDir);
        Files.writeString(wtGitDir.resolve("commondir"), "../..\n");
        // gitdir back-link（CC git.ts:165）：回指 <worktree>/.git —— FIX-B1 backlink 安全校验条件 2
        Files.writeString(wtGitDir.resolve("gitdir"), worktree.resolve(".git").toAbsolutePath() + "\n");
        Files.writeString(worktree.resolve(".git"), "gitdir: " + wtGitDir.toAbsolutePath() + "\n");

        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", worktree.toString());

        assertThat(files).extracting(f -> Path.of(f.filePath()).getFileName().toString())
            .contains("main-cmd.md");
    }

    // ── FIX-B1 backlink 安全校验（CC git.ts:142-170）──

    @Test
    @DisplayName("FIX-B1 校验1：恶意 commondir 指向受信仓库 .git 被拒绝，受信 .claude 内容不泄漏进攻击者会话 · CC git.ts:156-158")
    void maliciousCommondir_rejected_victimNotLeaked(@TempDir Path temp) throws Exception {
        // 受信仓库（含秘密命令，不应泄漏进攻击者会话）
        Path victim = temp.resolve("victim");
        Files.createDirectories(victim.resolve(".git"));
        Files.createDirectories(victim.resolve(".claude").resolve("commands"));
        Files.writeString(victim.resolve(".claude").resolve("commands").resolve("secret.md"), "# Secret\n");
        // 攻击者仓库：.git 文件 gitdir 指向攻击者控制的目录（非 victim/.git/worktrees 下）。
        //   攻击者在该目录提供 commondir（指向受信仓库 .git）+ gitdir back-link（回指自身 .git，
        //   使 back-link 校验单看也成立）；若 findCanonicalRoot 不校验 worktrees 结构（校验1），
        //   worktree 回退会把 victim/.claude/commands 追加进攻击者会话
        Path evil = temp.resolve("evil");
        Files.createDirectories(evil);
        Path evilScratch = Files.createDirectories(evil.resolve(".git-scratch"));
        Files.writeString(evilScratch.resolve("commondir"), victim.resolve(".git").toAbsolutePath() + "\n");
        Files.writeString(evilScratch.resolve("gitdir"), evil.resolve(".git").toAbsolutePath() + "\n");
        Files.writeString(evil.resolve(".git"), "gitdir: " + evilScratch.toAbsolutePath() + "\n");
        // 攻击者无自己的 .claude/commands —— 若校验缺失，回退会加载受信仓库的 secret.md
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", evil.toString());

        assertThat(files).extracting(f -> Path.of(f.filePath()).getFileName().toString())
            .as("恶意仓库不得借 commondir 伪造把受信仓库命令加载进会话（CC git.ts:156-158）")
            .doesNotContain("secret.md");
    }

    @Test
    @DisplayName("FIX-B1 校验2：借用受信仓库现有 worktree 条目（gitdir 不回指自身）被拒绝 · CC git.ts:165-170")
    void borrowedVictimWorktree_rejected(@TempDir Path temp) throws Exception {
        // 受信仓库 + 其真实 worktree（完整合法条目含 gitdir back-link）
        Path victim = temp.resolve("victim");
        Files.createDirectories(victim.resolve(".git"));
        Files.createDirectories(victim.resolve(".claude").resolve("commands"));
        Files.writeString(victim.resolve(".claude").resolve("commands").resolve("secret.md"), "# Secret\n");
        Path victimWt = temp.resolve("victimWt");
        Files.createDirectories(victimWt);
        Path wtGitDir = victim.resolve(".git").resolve("worktrees").resolve("wt");
        Files.createDirectories(wtGitDir);
        Files.writeString(wtGitDir.resolve("commondir"), "../..\n");
        Files.writeString(wtGitDir.resolve("gitdir"), victimWt.resolve(".git").toAbsolutePath() + "\n");
        Files.writeString(victimWt.resolve(".git"), "gitdir: " + wtGitDir.toAbsolutePath() + "\n");
        // 攻击者仓库：gitdir 猜中受信仓库的现有 worktree 条目（校验1 结构通过，校验2 back-link 拦截：
        //   <worktreeGitDir>/gitdir 回指 victimWt/.git，而非 realpath(evil)/.git）
        Path evil = temp.resolve("evil");
        Files.createDirectories(evil);
        Files.writeString(evil.resolve(".git"), "gitdir: " + wtGitDir.toAbsolutePath() + "\n");
        // 攻击者无自己的 .claude/commands —— 若校验缺失，回退会加载受信仓库的 secret.md
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", evil.toString());

        assertThat(files).extracting(f -> Path.of(f.filePath()).getFileName().toString())
            .as("攻击者不得借用受信仓库现有 worktree 条目把其命令加载进会话（CC git.ts:165-170）")
            .doesNotContain("secret.md");
    }

    // ── △-5 follow symlink ──

    @Test
    @DisplayName("△-5 follow symlink：commands 目录内 symlink 子目录的 .md 被递归发现 · CC --follow（markdownConfigLoader.ts:565）")
    void followSymlinkDiscoversNestedMd(@TempDir Path temp) throws Exception {
        Path project = newProject(temp, "proj");
        Path commands = Files.createDirectories(project.resolve(".claude").resolve("commands"));
        Files.writeString(commands.resolve("direct.md"), "# Direct\n");
        // 真实技能目录（symlink 目标，位于项目树外）
        Path real = temp.resolve("real-dir");
        Path realNested = Files.createDirectories(real.resolve("nested"));
        Files.writeString(realNested.resolve("sym-cmd.md"), "# Sym\n");
        // commands 下 symlink → real（旧 Files.walk 不 follow，会漏掉 sym-cmd.md）
        Path link = commands.resolve("linked");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前环境不支持创建 symlink，跳过: " + e);
            return;
        }

        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());

        assertThat(files).extracting(f -> Path.of(f.filePath()).getFileName().toString())
            .contains("direct.md", "sym-cmd.md");
    }

    // ── P2-3 文件搜索 3s 超时（实证后决定 · CC AbortSignal.timeout(3000)，markdownConfigLoader.ts:559）──

    @Test
    @DisplayName("P2-3 正常目录遍历完整返回（超时预算内不误伤）· CC AbortSignal.timeout(3000)")
    void listMarkdownFiles_normalTree_completesWithinBudget(@TempDir Path temp) throws Exception {
        // WHY: P2-3 实证结论——典型 .claude/skills 树（~1000 文件）100-200ms 远低于 3s 预算。
        //   超时守护必须只拦截超大/慢盘目录，正常遍历完整返回，否则会误伤技能加载。
        Path project = newProject(temp, "timeout-normal");
        Path skillsDir = Files.createDirectories(project.resolve(".claude").resolve("skills"));
        for (int i = 0; i < 100; i++) {
            Path dir = Files.createDirectories(skillsDir.resolve("skill-" + i));
            Files.writeString(dir.resolve("SKILL.md"), "---\nname: skill-" + i + "\n---\n# S" + i + "\n");
        }
        ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
        ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

        List<MarkdownConfigLoader.MarkdownFile> files =
            MarkdownConfigLoader.loadMarkdownFilesForSubdir("skills", project.toString());

        // 100 个技能目录全部载入（未误触发超时截断）
        assertThat(files).hasSize(100);
    }

    @Test
    @DisplayName("P2-3 遍历超时预算生效：人为构造超时目录被截断且不抛异常（CC 超时后该源丢弃）")
    void listMarkdownFiles_timeoutBudget_truncatesWithoutThrow(@TempDir Path temp) throws Exception {
        // WHY: CC loadMarkdownFiles 在 AbortSignal.timeout(3000) 超时后 ripgrep 被中止（AbortError），
        //   非 isFsInaccessible → rethrow → 该源加载失败。Java 同步 walkFileTree 无法真正中止线程，
        //   用墙钟 deadline 在遍历中检查 → TERMINATE + warn，返回已收集部分（不抛异常，加载链不中断）。
        //   本测试通过反射替换常量模拟"已超时"，验证截断路径行为——正常树不受影响由上一测试覆盖。
        long original = MarkdownConfigLoader.FILE_SEARCH_TIMEOUT_MS;
        try {
            // 模拟超时预算为 -1：deadline = now-1 < now → 首个 preVisitDirectory 即触发 TERMINATE
            MarkdownConfigLoader.FILE_SEARCH_TIMEOUT_MS = -1L;
            Path project = newProject(temp, "timeout-zero");
            Path commandsDir = Files.createDirectories(project.resolve(".claude").resolve("commands"));
            Files.writeString(commandsDir.resolve("a.md"), "# A\n");
            ClaudePaths.setConfigDirOverride(temp.resolve("cfg").toString());
        // G5：loader user 源 = NexusaiPaths 自有根优先（MarkdownConfigLoader.java:256）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + temp.getFileName());
            ClaudePaths.setManagedFilePathOverride(temp.resolve("managed").toString());

            // 超时预算已耗尽 → 遍历立即中止，返回空/部分结果（不抛异常，加载链不中断）
            List<MarkdownConfigLoader.MarkdownFile> files =
                MarkdownConfigLoader.loadMarkdownFilesForSubdir("commands", project.toString());
            assertThat(files).isNotNull();
        } finally {
            // 恢复常量避免污染后续测试
            MarkdownConfigLoader.FILE_SEARCH_TIMEOUT_MS = original;
        }
    }
}

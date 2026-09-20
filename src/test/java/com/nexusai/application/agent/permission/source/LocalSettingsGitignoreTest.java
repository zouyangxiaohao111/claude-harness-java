package com.nexusai.application.agent.permission.source;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.SessionKeys;
import com.nexusai.infra.util.GitIgnoreHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * [批 A4c P4] {@code <项目>/.nexusai/settings.local.json} 必须被 git 忽略。
 *
 * <h2>WHY（缺陷本体 · 实测）</h2>
 * <pre>
 * $ git check-ignore -v ".nexusai/settings.local.json"
 * (无输出) ; exit=1          ← 无任何规则覆盖
 * </pre>
 * <p>而 {@code localSettings} 的语义是「<b>个人、不共享</b>」（{@code LocalSettingsLoader} 类 javadoc
 * 「个人项目覆盖，不应提交到 git」；{@code PermissionRuleSource.LOCAL_SETTINGS} 的 javadoc 更直接写着
 * 「{@code .nexusai/settings.local.json}（<b>gitignored</b>）」—— 而实测它<b>没有</b>被忽略，
 * 典型「注释不可信」）。后果：用户点「始终允许」写下的个人规则会被提交，变成团队共享规则。
 *
 * <p>CC 在同一时机做同一件事 —— {@code settings.ts:508-514}：
 * {@code if (source === 'localSettings') void addFileGlobRuleToGitignore(
 * getRelativeSettingsFilePathForSource('localSettings'), getOriginalCwd())}；
 * {@code gitignore.ts:53-98} 把 {@code **&#47;.claude/settings.local.json} 追加到<b>全局</b>
 * ignore（{@code ~/.config/git/ignore}），整体 try/catch + {@code logError}，永不抛。
 *
 * <h2>RED tooth</h2>
 * <ol>
 *   <li>{@link #writeIsFollowedByGitignoreGuard()} —— 删掉
 *       {@code LocalSettingsLoader.ensureGitignored(sessionId)} 调用 ⇒ 全局 ignore 不产生该行 ⇒ 红；</li>
 *   <li>{@link #missingGuard_warnsInsteadOfStayingSilent()} —— 把 WARN 降成 DEBUG ⇒ 红；</li>
 *   <li>{@link #notInGitRepo_skips()} —— 去掉 {@code dirIsInGitRepo} 闸 ⇒ 红。</li>
 * </ol>
 *
 * <p>⛔ 本测试全部用 {@code @TempDir} 作为「全局 ignore」落点 ⇒ <b>绝不会碰</b>开发者真实的
 * {@code ~/.config/git/ignore}。
 */
@DisplayName("[批 A4c P4] settings.local.json 的 gitignore 守护")
class LocalSettingsGitignoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SettingsJsonParser PARSER = new SettingsJsonParser(JSON, new PermissionRuleValueParser());

    /** {@code git check-ignore} 桩：exit 1 = 未被忽略（真实实测状态）。 */
    private static final BiFunction<String[], String, GitIgnoreHelper.ExecResult> NOT_IGNORED =
        (args, cwd) -> new GitIgnoreHelper.ExecResult(1, "", "");
    /** {@code git check-ignore} 桩：exit 0 = 已被忽略。 */
    private static final BiFunction<String[], String, GitIgnoreHelper.ExecResult> ALREADY_IGNORED =
        (args, cwd) -> new GitIgnoreHelper.ExecResult(0, ".gitignore:1:**/x\n", "");

    private static LocalSettingsGitignore guard(Path globalIgnore, boolean inGitRepo,
                                               BiFunction<String[], String, GitIgnoreHelper.ExecResult> gitExec) {
        LocalSettingsGitignore g = new LocalSettingsGitignore();
        g.setGlobalGitignorePathSupplier(globalIgnore::toString);
        g.setDirIsInGitRepoFn(p -> inGitRepo);
        g.setGitExec(gitExec);
        return g;
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> target, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(target)).detachAppender(appender);
        appender.stop();
    }

    private static boolean hasAtLeast(ListAppender<ILoggingEvent> appender, Level level, String needle) {
        return appender.list.stream()
            .anyMatch(e -> e.getLevel().isGreaterOrEqual(level) && e.getFormattedMessage().contains(needle));
    }

    // ══════════════════════ 组件本体（对齐 CC gitignore.ts） ══════════════════════

    @Test
    @DisplayName("1. 未被忽略 + 全局 ignore 不存在 ⇒ 创建并写入 `**/<projectDir>/settings.local.json`")
    void createsGlobalIgnoreWithEntry(@TempDir Path tempDir) throws Exception {
        Path global = tempDir.resolve("gitconfig/ignore");   // 父目录也不存在 → 覆盖 mkdir -p
        guard(global, true, NOT_IGNORED).ensureIgnored(tempDir.toString(), ".nexusai");

        assertThat(global).exists();
        assertThat(Files.readString(global, StandardCharsets.UTF_8))
            .as("entry 必须与 CC `**/${filename}` 同构（filename = .nexusai/settings.local.json）")
            .contains("**/.nexusai/settings.local.json");
    }

    @Test
    @DisplayName("2. 全局 ignore 已存在 ⇒ 追加且保留原内容（不整文件重写）")
    void appendsPreservingExistingContent(@TempDir Path tempDir) throws Exception {
        Path global = tempDir.resolve("ignore");
        Files.writeString(global, "*.log\n", StandardCharsets.UTF_8);
        guard(global, true, NOT_IGNORED).ensureIgnored(tempDir.toString(), ".nexusai");

        String content = Files.readString(global, StandardCharsets.UTF_8);
        assertThat(content).contains("*.log");
        assertThat(content).contains("**/.nexusai/settings.local.json");
    }

    @Test
    @DisplayName("3. 幂等：全局 ignore 已含该 entry ⇒ 文件不再变（CC content.includes → return）")
    void idempotentWhenEntryAlreadyPresent(@TempDir Path tempDir) throws Exception {
        Path global = tempDir.resolve("ignore");
        Files.writeString(global, "**/.nexusai/settings.local.json\n", StandardCharsets.UTF_8);
        String before = Files.readString(global, StandardCharsets.UTF_8);
        guard(global, true, NOT_IGNORED).ensureIgnored(tempDir.toString(), ".nexusai");
        assertThat(Files.readString(global, StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    @DisplayName("4. 已被任何现有 ignore 源覆盖（check-ignore exit 0）⇒ 不写（CC gitignore.ts:62-71）")
    void alreadyIgnoredIsNoOp(@TempDir Path tempDir) throws Exception {
        Path global = tempDir.resolve("ignore");
        guard(global, true, ALREADY_IGNORED).ensureIgnored(tempDir.toString(), ".nexusai");
        assertThat(global).as("已被现有规则覆盖 ⇒ 不得多此一举写全局 ignore").doesNotExist();
    }

    @Test
    @DisplayName("5. 不在 git 仓库 ⇒ 跳过（CC gitignore.ts:58-60 dirIsInGitRepo 闸）")
    void notInGitRepo_skips(@TempDir Path tempDir) throws Exception {
        Path global = tempDir.resolve("ignore");
        guard(global, false, NOT_IGNORED).ensureIgnored(tempDir.toString(), ".nexusai");
        assertThat(global).doesNotExist();
    }

    @Test
    @DisplayName("6. 项目根 / 目录名缺失 ⇒ 不抛、不猜，WARN 留痕")
    void missingContext_warns(@TempDir Path tempDir) {
        Path global = tempDir.resolve("ignore");
        ListAppender<ILoggingEvent> appender = attach(LocalSettingsGitignore.class);
        try {
            LocalSettingsGitignore g = guard(global, true, NOT_IGNORED);
            assertThatNoException().isThrownBy(() -> g.ensureIgnored(null, ".nexusai"));
            assertThatNoException().isThrownBy(() -> g.ensureIgnored(tempDir.toString(), null));
            assertThat(hasAtLeast(appender, Level.WARN, "无法加入忽略列表")).isTrue();
        } finally {
            detach(LocalSettingsGitignore.class, appender);
        }
    }

    @Test
    @DisplayName("7. 写全局 ignore 失败 ⇒ 永不抛（CC logError 语义）+ WARN 给手工补救指令")
    void writeFailureNeverThrows(@TempDir Path tempDir) throws Exception {
        // 把「全局 ignore 的父目录」做成一个**文件** ⇒ createDirectories 必失败
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a dir", StandardCharsets.UTF_8);
        Path global = blocker.resolve("ignore");

        ListAppender<ILoggingEvent> appender = attach(LocalSettingsGitignore.class);
        try {
            LocalSettingsGitignore g = guard(global, true, NOT_IGNORED);
            assertThatCode(() -> g.ensureIgnored(tempDir.toString(), ".nexusai"))
                .as("CC 整体 try/catch + logError ⇒ 永不抛；本仓只要求不阻断写盘")
                .doesNotThrowAnyException();
            assertThat(hasAtLeast(appender, Level.WARN, "加入全局 ignore 失败")).isTrue();
            assertThat(hasAtLeast(appender, Level.WARN, "手工")).isTrue();
        } finally {
            detach(LocalSettingsGitignore.class, appender);
        }
    }

    @Test
    @DisplayName("8. dirIsInGitRepo：逐级向上找 .git（目录或文件），无 .git 的临时目录 = false")
    void dirIsInGitRepoWalksUp(@TempDir Path tempDir) throws Exception {
        Path deep = Files.createDirectories(tempDir.resolve("a/b/c"));
        assertThat(LocalSettingsGitignore.dirIsInGitRepo(deep.toString()))
            .as("@TempDir 不在任何 git 仓库内").isFalse();
        assertThat(LocalSettingsGitignore.dirIsInGitRepo(null)).isFalse();
        // worktree / submodule 里 .git 是**文件**（对齐 CC findGitRoot：isDirectory() || isFile()）
        Files.writeString(deep.resolve(".git"), "gitdir: /elsewhere\n", StandardCharsets.UTF_8);
        assertThat(LocalSettingsGitignore.dirIsInGitRepo(deep.toString()))
            .as(".git 为文件（worktree/submodule）同样算仓库").isTrue();
        Path child = Files.createDirectories(deep.resolve("child/grand"));
        assertThat(LocalSettingsGitignore.dirIsInGitRepo(child.toString()))
            .as("必须逐级向上找，不能只看当前目录").isTrue();
    }

    // ══════════════════════ 接线：LocalSettingsLoader 写盘后必须调用 ══════════════════════

    @Test
    @DisplayName("9. 接线：savePermissionsField 成功写盘后，守护拿到【该项目根】+ 项目目录名")
    void writeIsFollowedByGitignoreGuard(@TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("proj"));
        Path global = tempDir.resolve("ignore");

        ListAppender<ILoggingEvent> appender = attach(LocalSettingsGitignore.class);
        try {
            LocalSettingsLoader loader = new LocalSettingsLoader(PARSER, projectRoot::toString);
            loader.setGitignoreGuard(guard(global, true, NOT_IGNORED));

            loader.savePermissionsField("allow", List.of("Bash(echo A4C_P4_ONLY)"), SessionKeys.NO_SESSION);

            // ① 设置文件确实写了（守护不是唯一的写盘副作用）
            Path settings = projectRoot.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json");
            assertThat(settings).exists();
            assertThat(Files.readString(settings, StandardCharsets.UTF_8)).contains("A4C_P4_ONLY");
            // ② 守护被调用 ⇒ entry 落进全局 ignore，且带的是**本会话项目根**的目录名
            assertThat(global)
                .as("写盘成功后必须把 settings.local.json 加入忽略列表（CC settings.ts:508-514 同时机）")
                .exists();
            assertThat(Files.readString(global, StandardCharsets.UTF_8))
                .isEqualTo("**/" + NexusaiPaths.getProjectDirName() + "/settings.local.json\n");
            assertThat(NexusaiPaths.getProjectDirName())
                .as("生产项目级目录名 = .nexusai（决策 D1/D6）").isEqualTo(".nexusai");
        } finally {
            detach(LocalSettingsGitignore.class, appender);
        }
    }

    @Test
    @DisplayName("10. 未接线守护 ⇒ 写盘仍成功，但必须 WARN（不许静默失效）")
    void missingGuard_warnsInsteadOfStayingSilent(@TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("proj2"));
        LocalSettingsLoader loader = new LocalSettingsLoader(PARSER, projectRoot::toString);
        // 不 setGitignoreGuard → 未接线（POJO 测试路径 / Spring 注入缺失）

        ListAppender<ILoggingEvent> appender = attach(LocalSettingsLoader.class);
        try {
            loader.savePermissionsField("allow", List.of("Bash(echo A4C_P4_UNWIRED)"), SessionKeys.NO_SESSION);
            assertThat(projectRoot.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.local.json"))
                .exists();
            assertThat(hasAtLeast(appender, Level.WARN, "gitignore 守护未接线"))
                .as("未接线必须 ≥WARN（禁只 DEBUG）—— 静默正是这类缺陷躲过三个批次的原因")
                .isTrue();
        } finally {
            detach(LocalSettingsLoader.class, appender);
        }
    }
}

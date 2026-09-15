package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.application.agent.tool.ContentBlockParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [r10b · D5 · 裁定 #10 第二域] {@code /batch} 的 isGit 判定必须吃<b>会话 cwd</b>。
 *
 * <p><b>WHY（守护什么）</b>：CC 的 {@code getIsGit} 是进程级 memoize + ambient
 * {@code getCwd()}（git.ts:218-222）—— 在 CC 单进程单会话前提下 ambient cwd 恒 = 会话 cwd；
 * 本仓 1 JVM : N 会话 ⇒ ambient cwd = 后端启动目录 ⇒ <b>语义不等价</b>：非 git 项目里的会话
 * 会被误判为「在 git 仓库中」，{@code /batch} 放行并让模型去开 worktree / 提 PR。
 * 会话 cwd 本就在 {@code PromptFnContext} 形参里（{@code ctx.cwd()}）⇒ 必须显式传参。
 *
 * <p><b>装置（P0-2 锚点夹具同款）</b>：会话 cwd = 临时目录 A（<b>非</b> git 仓库）；
 * 进程 {@code user.dir} = 临时目录 B（<b>是</b> git 仓库）。
 *
 * <p><b>反向实验配方</b>：把 {@code BundledSkillsBootstrapper.registerBatchSkill()} 里的
 * {@code cwd -> new GitStatusProvider(Path.of(cwd)).isGit()} 改回
 * {@code () -> new GitStatusProvider().isGit()}（且 {@code BatchSkillRegistrar} 相应回到无参判定）
 * ⇒ 锚 B（git）⇒ {@code /batch} 返回 plan 正文而非 {@code NOT_A_GIT_REPO_MESSAGE} ⇒ 红。
 */
@DisplayName("[r10b-D5] /batch isGit 锚点 = 会话 cwd（非进程 user.dir）")
class BatchSkillGitAnchorTest {

    private String savedUserDir;

    @BeforeEach
    void clearRegistryAndSaveUserDir() {
        savedUserDir = System.getProperty("user.dir");
        BundledSkills.clear();
    }

    @AfterEach
    void cleanup() {
        BundledSkills.clear();
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        } else {
            System.clearProperty("user.dir");
        }
    }

    /**
     * ⭐ 核心断言：非 git 项目的会话调 {@code /batch} ⇒ {@code NOT_A_GIT_REPO_MESSAGE}。
     */
    @Test
    @DisplayName("D5 会话 cwd 非 git 仓库 ⇒ /batch 返回 NOT_A_GIT_REPO（即使进程 user.dir 是 git 仓库）")
    void batch_usesSessionCwd_forGitDetection(@TempDir Path tmp) throws Exception {
        Path sessionDir = Files.createDirectories(tmp.resolve("session-project"));
        Path processDir = Files.createDirectories(tmp.resolve("process-launch-dir"));
        Files.createDirectory(processDir.resolve(".git"));

        // 夹具前置：会话目录 A 必须真的不在任何 git 仓库内
        assertThat(new com.nexusai.application.agent.prompt.GitStatusProvider(sessionDir).findGitRoot())
            .as("夹具前置失败：临时目录 A 落在某个 git 仓库内 ⇒ 锚点不可分辨，须停下报告")
            .isNull();
        System.setProperty("user.dir", processDir.toString());

        // 生产注册链（真实 lambda wiring，非本地重写）
        new BundledSkillsBootstrapper(() -> false, () -> true).run(null);
        Command batch = BundledSkills.getAll().stream()
            .filter(c -> "batch".equals(c.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("bundled 注册集缺 batch（防过度删除断言失败）"));

        List<ContentBlockParam> blocks = batch.getPromptFn().apply(
            "add type annotations everywhere",
            PromptFnContext.of(sessionDir.toString(), List.of(), "sess-r10b-batch"));

        assertThat(blocks).hasSize(1);
        assertThat(((ContentBlockParam.TextBlockParam) blocks.get(0)).text())
            .as("⭐ 会话 cwd 非 git ⇒ NOT_A_GIT_REPO（若锚到进程 user.dir=B 则会放行 plan 正文）")
            .isEqualTo(BatchSkillRegistrar.NOT_A_GIT_REPO_MESSAGE);
    }

    /**
     * 正实验（对照）：同一套装置下，会话 cwd <b>是</b> git 仓库 ⇒ 放行 plan 正文。
     *
     * <p>WHY 需要它：没有这条，「恒返回 NOT_A_GIT_REPO」这种坏实现也会让上一条用例绿
     * （本仓已登记「声称守护 X、实际守不住」的失效模式）。
     */
    @Test
    @DisplayName("D5 对照：会话 cwd 是 git 仓库 ⇒ /batch 放行（证上一条不是「恒 NOT_A_GIT_REPO」）")
    void batch_allowsWhenSessionCwdIsGitRepo(@TempDir Path tmp) throws Exception {
        Path sessionDir = Files.createDirectories(tmp.resolve("session-git-project"));
        Files.createDirectory(sessionDir.resolve(".git"));
        Path processDir = Files.createDirectories(tmp.resolve("process-nongit"));
        System.setProperty("user.dir", processDir.toString());

        new BundledSkillsBootstrapper(() -> false, () -> true).run(null);
        Command batch = BundledSkills.getAll().stream()
            .filter(c -> "batch".equals(c.getName()))
            .findFirst()
            .orElseThrow();

        List<ContentBlockParam> blocks = batch.getPromptFn().apply(
            "add type annotations everywhere",
            PromptFnContext.of(sessionDir.toString(), List.of(), "sess-r10b-batch2"));

        assertThat(((ContentBlockParam.TextBlockParam) blocks.get(0)).text())
            .as("会话 cwd 是 git 仓库 ⇒ 放行 plan 正文（含 Phase 1 标题）")
            .contains("Phase 1: Research and Plan")
            .isNotEqualTo(BatchSkillRegistrar.NOT_A_GIT_REPO_MESSAGE);
    }
}

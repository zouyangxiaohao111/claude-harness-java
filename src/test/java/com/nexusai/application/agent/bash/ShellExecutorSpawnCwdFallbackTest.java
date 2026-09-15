package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [欠账清理批 · c3 漏传修复] {@link ShellExecutor#resolveSpawnCwd(String, String)} 的
 * <b>回落层锚定</b>契约测试。
 *
 * <p><b>WHY（规则九 · 意图验证）</b>：CC {@code Shell.ts:218-238} 的 spawn cwd 语义是
 * 「{@code realpath(cwd)} 失败（cwd 被删）→ 回落 <b>同一会话</b>的 {@code getOriginalCwd()}」。
 * 本仓原实现从 {@code BashTool} 走 1 参重载（内部 {@code sessionId=null}）⇒ 回落层按「无会话」
 * 解析成 <b>进程 {@code user.dir}</b>（后端 JVM 启动目录）。
 *
 * <p>后果不是崩溃而是<b>静默跑错目录</b>：会话工作目录被删（worktree 被清理 / 临时项目目录被回收）
 * 时，bash 子进程会 spawn 到后端启动目录 —— 命令在<b>别的项目</b>里执行，不报错、日志里也看不出。
 * 该重载的 javadoc 早已写明「会话态调用方须改调 2 参重载」，但唯一生产调用方一直走 1 参
 * ⇒ 属「有会话却走无会话出口」的 c3 漏传。
 *
 * <p><b>鉴别力设计</b>：本用例<b>必须</b>同时钉住两条腿，否则就是「只覆盖一侧」——
 * <ol>
 *   <li>{@link #deletedCwd_fallsBackToSessionOriginalCwd_notProcessUserDir(Path, Path)}：会话态
 *       ⇒ 回落 = 会话 originalCwd（<b>且显式断言不等于 user.dir</b>，否则「回落 user.dir」也能过）；</li>
 *   <li>{@link #nullSession_stillFallsBackToProcessUserDir(Path, Path)}：无会话（null）
 *       ⇒ 回落 = 进程 user.dir（<b>正向对照</b>：证明上一条的差异来自会话载体，不是「函数恒返回 projectDir」）。</li>
 * </ol>
 * <p>变异验证（见交付报告）：把 {@code resolveSpawnCwd} 内回落层的 {@code sessionId} 换成
 * {@code null}（= 退回 c3 漏传形态）⇒ 第 1 条<b>红</b>；把整个回落层短路成 {@code return cwd}
 * ⇒ 第 1 条红（无回落）；两条腿合并成一条断言 ⇒ 第 2 条红。
 */
class ShellExecutorSpawnCwdFallbackTest {

    @AfterEach
    void clearSessionState() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
    }

    @Test
    @DisplayName("cwd 被删 + 有会话 → 回落「该会话的 originalCwd」（≠ 进程 user.dir）")
    void deletedCwd_fallsBackToSessionOriginalCwd_notProcessUserDir(@TempDir Path projectDir,
                                                                   @TempDir Path scratch) throws Exception {
        // WHY：这是 c3 漏传的失效现场 —— worktree/临时目录被清理后，bash 必须在**本会话**的项目
        //   目录里跑，而不是后端 JVM 的启动目录（那是「同 JVM 多会话」下最容易串台的值）。
        String sessionId = "sess-c3-spawn-fallback";
        SessionProjectRoot.setForSession(sessionId, projectDir.toString());

        // 制造「cwd 不存在」：先建再删（realpath 失败是回落层的唯一触发条件）
        Path deletedCwd = Files.createDirectory(scratch.resolve("gone"));
        Files.delete(deletedCwd);

        String resolved = ShellExecutor.resolveSpawnCwd(deletedCwd.toString(), sessionId);

        String expected = projectDir.toRealPath().toString();
        String userDir = Path.of(System.getProperty("user.dir")).toRealPath().toString();
        assertThat(resolved)
            .as("会话 cwd 被删 ⇒ 回落层必须是**该会话**的 originalCwd（CC Shell.ts:225 getOriginalCwd）")
            .isEqualTo(expected);
        assertThat(resolved)
            .as("若回落成进程 user.dir，说明 sessionId 又被漏传（c3 回归）——本条是鉴别力的核心")
            .isNotEqualTo(userDir);
    }

    @Test
    @DisplayName("正向对照：无会话（sessionId=null）→ 仍回落进程 user.dir（无会话出口语义不变）")
    void nullSession_stillFallsBackToProcessUserDir(@TempDir Path projectDir,
                                                    @TempDir Path scratch) throws Exception {
        // WHY：证明上一条的差异确实来自「显式会话载体」，而不是「函数恒返回某个固定目录」。
        //   同时钉住 (b) 类语义：确无会话时才允许取进程 user.dir（并打 WARN 留痕）。
        String sessionId = "sess-c3-spawn-other";
        SessionProjectRoot.setForSession(sessionId, projectDir.toString());

        Path deletedCwd = Files.createDirectory(scratch.resolve("gone2"));
        Files.delete(deletedCwd);

        String resolved = ShellExecutor.resolveSpawnCwd(deletedCwd.toString(), null);

        assertThat(resolved)
            .as("显式无会话 ⇒ 命名出口语义（进程 user.dir），不得取到上面那条会话的绑定项目")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString())
            .isNotEqualTo(projectDir.toRealPath().toString());
    }

    @Test
    @DisplayName("主路径不变：cwd 存在 → 恒返回其 realpath（与 sessionId 无关）")
    void existingCwd_returnsRealPath_unchanged(@TempDir Path projectDir, @TempDir Path workDir) throws Exception {
        // WHY：本批只改**回落层**的锚。主路径（cwd 存在）必须零行为变化，否则就是超出范围的改动。
        String sessionId = "sess-c3-spawn-main";
        SessionProjectRoot.setForSession(sessionId, projectDir.toString());

        assertThat(ShellExecutor.resolveSpawnCwd(workDir.toString(), sessionId))
            .isEqualTo(workDir.toRealPath().toString());
        assertThat(ShellExecutor.resolveSpawnCwd(workDir.toString(), null))
            .as("主路径与 sessionId 无关（回落层才用它）")
            .isEqualTo(workDir.toRealPath().toString());
    }
}

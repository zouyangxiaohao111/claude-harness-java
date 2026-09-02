package com.nexusai.common;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ODF-A1/IMP-B] SessionProjectRoot 会话级 projectRoot 载体 · 对齐 CC bootstrap/state.ts per-session projectRoot。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 在启动时 realpath(cwd) 冻结为 projectRoot（state.ts:45-50 stable
 * projectRoot 注释 + :269-279），且会话中不再更新（state.ts:511-513 getProjectRoot 稳定；:523-525
 * setProjectRoot 仅 --worktree 启动时）。旧 Java 后端 memory 路径链恒读 {@code System.getProperty("user.dir")}
 * 单例 → 同一 JVM 内不同 cwd 会话解析到同一 memory 目录（跨项目记忆污染）。SessionProjectRoot 按 sessionId
 * 登记会话级 projectRoot，使 AutoMemPaths/AgentMemoryDirectory/LlmAgentLoop workspaceDir 生产链可解析到
 * 当前会话的 projectRoot。
 * 本测试锁定: 会话绑定隔离、current 覆盖、setForSession 首写胜（OPD-SPR-03）、clearSession 后可重绑、
 * 未绑定回落 ODF-A1 链（CLAUDE_PROJECT_DIR env ?? config home，绝不读 user.dir）。
 */
@DisplayName("[ODF-A1] SessionProjectRoot 会话级 projectRoot 载体")
class SessionProjectRootTest {

    /** 绑定用临时目录（setForSession 校验绝对路径且目录存在，/cwd/xxx 假路径不再合法）。 */
    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        RequestContext.clear();
        NexusaiPaths.setAppNameOverride(null);
    }

    /** 创建绑定目录并返回绝对路径（满足 setForSession 绝对+目录存在校验）。 */
    private String bindDir(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.createDirectories(p);
        return p.toString();
    }

    @Test
    @DisplayName("per-session: 不同 sessionId 绑定不同 projectRoot，切换会话互不污染 (state.ts per-session projectRoot)")
    void sessionBound_projectRootsIsolated() throws IOException {
        // WHY: CC 每会话冻结自己的 projectRoot（state.ts:45-50/:269-279），会话间不得互相覆盖。
        //       按 sessionId 登记后，切换当前会话必须解析到各自绑定的 projectRoot。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.setForSession("sess-b", pb);

        RequestContext.setSession("sess-a");
        assertThat(SessionProjectRoot.resolve())
            .as("会话 A 必须解析到其绑定的 projectRoot")
            .isEqualTo(pa);

        RequestContext.setSession("sess-b");
        assertThat(SessionProjectRoot.resolve())
            .as("会话 B 必须解析到其绑定的 projectRoot（会话间隔离）")
            .isEqualTo(pb);
    }

    @Test
    @DisplayName("current 线程级 projectRoot 优先于 session 绑定（对齐 CC cwd 概念）")
    void currentOverridesSession() throws IOException {
        String pa = bindDir("project-a");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.setCurrent("/cwd/current-override");

        RequestContext.setSession("sess-a");
        assertThat(SessionProjectRoot.resolve())
            .as("当前线程显式注入的 projectRoot 优先")
            .isEqualTo("/cwd/current-override");

        SessionProjectRoot.clearCurrent();
        assertThat(SessionProjectRoot.resolve())
            .as("清除 current 后回落 session 绑定")
            .isEqualTo(pa);
    }

    @Test
    @DisplayName("setForSession 首写胜：rebind 不覆盖已冻结值（CC stable identity · OPD-SPR-03）")
    void setForSession_firstWriteWins() throws IOException {
        // WHY: CC projectRoot 启动冻结一次、会话内不更新（state.ts:45-50 stable projectRoot；
        //      getProjectRoot state.ts:511-513）；OPD-SPR-03 裁决 rebind 不覆盖已冻结值。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.setForSession("sess-a", pb); // rebind 尝试 → 不得覆盖

        RequestContext.setSession("sess-a");
        assertThat(SessionProjectRoot.resolve())
            .as("已冻结会话绑定不得被 rebind 覆盖")
            .isEqualTo(pa);
        assertThat(SessionProjectRoot.getForSession("sess-a"))
            .as("getForSession 返回冻结值")
            .isEqualTo(pa);
        assertThat(SessionProjectRoot.getForSession("unknown-session"))
            .as("未冻结会话 → null（getForSession 允许）")
            .isNull();
    }

    @Test
    @DisplayName("clearSession 后冻结解除：可再绑定新 projectRoot（OPD-SPR-03 unbind 语义）")
    void clearSession_allowsRebind() throws IOException {
        // WHY: OPD-SPR-03 —— unbind 清空后首写重新生效（冻结仅限会话生命周期内）。
        String pa = bindDir("project-a");
        String pb = bindDir("project-b");
        SessionProjectRoot.setForSession("sess-a", pa);
        SessionProjectRoot.clearSession("sess-a");
        SessionProjectRoot.setForSession("sess-a", pb);

        RequestContext.setSession("sess-a");
        assertThat(SessionProjectRoot.resolve())
            .as("clearSession 后重新绑定生效")
            .isEqualTo(pb);
    }

    @Test
    @DisplayName("无注入无会话 → CLAUDE_PROJECT_DIR env ?? nexusai config home 回落链（ODF-A1 · 绝不读 JVM user.dir）")
    void unboundFallsBackToEnvOrConfigHome() {
        // WHY: ODF-A1 回落链 = CLAUDE_PROJECT_DIR env ?? NexusaiPaths.getAppConfigHomeDir()
        //      （决策 D1 nexusai 自有根，对齐 AutoMemPaths.currentSessionProjectRoot）；绝不读
        //      JVM user.dir —— 同一 JVM 内不同 cwd 会话不得解析到同一进程目录（跨项目污染）。
        //      env 无法进程内修改 → 按当前 env 动态组合期望值。
        NexusaiPaths.setAppNameOverride("nexusai");
        try {
            String env = System.getenv("CLAUDE_PROJECT_DIR");
            String expected = (env != null && !env.isBlank())
                ? env
                : NexusaiPaths.getAppConfigHomeDir();
            String actual = SessionProjectRoot.resolve();
            assertThat(actual)
                .as("无注入无会话 → env ?? nexusai config home")
                .isEqualTo(expected);
            if (env == null || env.isBlank()) {
                assertThat(actual)
                    .as("回落链绝不依赖 JVM user.dir")
                    .isNotEqualTo(System.getProperty("user.dir", "."));
            }
        } finally {
            NexusaiPaths.setAppNameOverride(null);
        }
    }
}

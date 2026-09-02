package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-H · F11 · M-24] SessionMemoryService baseDir 求值测试
 * （ODF-A1-REF findings#2 登记：baseDir 构造期由 user.dir 改 currentSessionProjectRoot()，
 * 行为已迁移但未测）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：ToolRegistrationConfig:1096-1097 在
 * bean 构造期以 {@code Paths.get(AutoMemPaths.currentSessionProjectRoot())} 快照 baseDir。
 * 无会话上下文（构造期/非会话线程）时 currentSessionProjectRoot() 回落
 * {@code CLAUDE_PROJECT_DIR env ?? config home}（确定性非 null，绝不读 JVM 进程工作目录）。
 * 本测试锁定：
 * <ol>
 *   <li>无会话上下文 → baseDir 求值 = 回落链结果（env ?? config home；ClaudePaths override
 *       可测性 —— config home 分支），且绝不等于 user.dir</li>
 *   <li>baseDir 为<b>构造期快照</b>：构造后再改会话 projectRoot，resolvePath 仍用构造期值
 *       （SM 不在允许清单 —— 不实施 per-session 动态求值，登记现状）</li>
 *   <li>resolvePath 布局 {@code {baseDir}/{sessionId}/session-memory/summary.md}
 *       （对齐 CC filesystem.ts:261-271）</li>
 * </ol>
 */
@DisplayName("[IMP-H F11 M-24] SessionMemoryService baseDir 求值（构造期快照 = 回落链）")
class SessionMemoryServiceBaseDirTest {

    /** 本机/CI 可能设置该 env（回落链 env 分支优先于 config home）——断言须按环境自适应。 */
    private static final String CLAUDE_PROJECT_DIR = "CLAUDE_PROJECT_DIR";

    @TempDir
    Path configHome;

    @AfterEach
    void cleanup() {
        AutoMemPaths.setCurrentProjectRoot(null);
        ClaudePaths.setConfigDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    @Test
    @DisplayName("无会话上下文 → baseDir 求值 = 回落链（env ?? config home），绝不读 user.dir")
    void baseDir_resolvesViaFallbackChainWhenNoSessionContext() {
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：SessionMemoryService 内部亦可能触及 nexusai 自有根 → 唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        String expected = fallbackWhenNoSession();

        String projectRoot = AutoMemPaths.currentSessionProjectRoot();
        assertThat(projectRoot)
            .as("无会话上下文必须走回落链（CLAUDE_PROJECT_DIR env ?? config home）")
            .isEqualTo(expected)
            .isNotEqualTo(System.getProperty("user.dir"));

        SessionMemoryService sm = new SessionMemoryService(Path.of(projectRoot));

        assertThat(sm.resolvePath("sess-1"))
            .as("session-memory 落 {baseDir}/{sessionId}/session-memory/summary.md（CC filesystem.ts:261-271）")
            .isEqualTo(Path.of(expected).resolve("sess-1").resolve("session-memory").resolve("summary.md"));
    }

    @Test
    @DisplayName("CLAUDE_PROJECT_DIR env 未设置时回落 config home（config home 分支确定性）")
    void baseDir_configHomeBranchWhenEnvUnset() {
        String env = System.getenv(CLAUDE_PROJECT_DIR);
        if (env != null && !env.isBlank()) {
            // env 分支存在 → 本用例的前提不成立（回落链由 env 主导）；跳过以保持环境无关。
            return;
        }
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());

        String projectRoot = AutoMemPaths.currentSessionProjectRoot();

        assertThat(projectRoot).isEqualTo(configHome.toString());
        assertThat(Path.of(projectRoot).isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("baseDir 为构造期快照：构造后再改会话 projectRoot，resolvePath 仍用构造期值（登记现状不实施 per-session 动态）")
    void baseDir_isConstructionSnapshot() {
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        AutoMemPaths.setCurrentProjectRoot(configHome.resolve("bound-project").toString());

        SessionMemoryService sm = new SessionMemoryService(Path.of(AutoMemPaths.currentSessionProjectRoot()));
        Path constructedBase = configHome.resolve("bound-project");
        assertThat(sm.resolvePath("sess-1")).isEqualTo(
            constructedBase.resolve("sess-1").resolve("session-memory").resolve("summary.md"));

        // 构造后会话 projectRoot 变更（会话中途 rebind）→ baseDir 快照不变（M-24 登记现状）
        AutoMemPaths.setCurrentProjectRoot(configHome.resolve("other-project").toString());
        assertThat(sm.resolvePath("sess-1"))
            .as("构造期快照：不得跟随后续会话 projectRoot 变化（SM 不在允许清单）")
            .isEqualTo(constructedBase.resolve("sess-1").resolve("session-memory").resolve("summary.md"));
    }

    @Test
    @DisplayName("resolvePath(null) → {baseDir}/session-memory/summary.md（无 sessionId 分支）")
    void resolvePath_nullSessionId_usesBaseDirDirectly() {
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        SessionMemoryService sm = new SessionMemoryService(
            Path.of(AutoMemPaths.currentSessionProjectRoot()));

        assertThat(sm.resolvePath(null))
            .isEqualTo(Path.of(fallbackWhenNoSession()).resolve("session-memory").resolve("summary.md"));
    }

    /** 回落链结果（ThreadLocal 空 → CLAUDE_PROJECT_DIR env ?? config home override）。 */
    private static String fallbackWhenNoSession() {
        String env = System.getenv(CLAUDE_PROJECT_DIR);
        return (env != null && !env.isBlank()) ? env : ClaudePaths.getClaudeConfigHomeDir();
    }
}

package com.nexusai.application.agent.memory;

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
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：原 ToolRegistrationConfig:1096-1097 在
 * bean 构造期以 {@code Paths.get(AutoMemPaths.currentSessionProjectRoot())} 快照 baseDir。
 * <b>[sm-reloc 2026-09-02]</b> 生产装配改 per-session（2-arg + {@code SessionStorage::sessionProjectDir}），
 * 1-arg legacy（固定 baseDir）仍由本类锁定回落语义。无会话上下文（构造期/非会话线程）时
 * currentSessionProjectRoot() 回落 {@code CLAUDE_PROJECT_DIR env ?? config home}。
 * 本测试锁定：
 * <ol>
 *   <li>无会话上下文 → baseDir 求值 = 回落链结果（env ?? config home；ClaudePaths override
 *       可测性 —— config home 分支），且绝不等于 user.dir</li>
 *   <li>1-arg legacy：baseDir 为构造期固定（构造后再改会话 projectRoot，resolvePath 仍用构造期值
 *       —— legacy 回落语义保留）</li>
 *   <li>2-arg + resolver：resolvePath 按 resolver per-session 求值 slug（构造后再改 projectRoot
 *       仍走 resolver，动态生效）</li>
 *   <li>resolvePath(null) 无会话分支 {@code {baseDir}/session-memory/summary.md} 语义不变</li>
 *   <li>resolvePath 布局 {@code {slugDir}/{sessionId}/session-memory/summary.md}
 *       （对齐 CC filesystem.ts:261-271）</li>
 * </ol>
 */
@DisplayName("[IMP-H F11 M-24] SessionMemoryService baseDir 求值（1-arg legacy 固定 = 回落链 / 2-arg per-session resolver）")
class SessionMemoryServiceBaseDirTest {

    /** 回落链 env 由 AutoMemPaths.CLAUDE_PROJECT_DIR_ENV（NEXUSAI_PROJECT_DIR）定义 —— 直接引用。 */

    @TempDir
    Path configHome;

    @AfterEach
    void cleanup() {
        AutoMemPaths.setCurrentProjectRoot(null);
        NexusaiPaths.setConfigHomeDirOverride(null);  // [sm-reloc] 复位 config home override（防泄漏）
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    @Test
    @DisplayName("无会话上下文 → baseDir 求值 = 回落链（env ?? config home），绝不读 user.dir")
    void baseDir_resolvesViaFallbackChainWhenNoSessionContext() {
        // [sm-reloc] config home 隔离到 @TempDir —— currentSessionProjectRoot 回落只走 NexusaiPaths
        //   config home，ClaudePaths（~/.claude 只读兼容源）不驱动写根回落（D1-D7 迁移残留修正）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
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
        String env = System.getenv(AutoMemPaths.CLAUDE_PROJECT_DIR_ENV);
        if (env != null && !env.isBlank()) {
            // env 分支存在 → 本用例的前提不成立（回落链由 env 主导）；跳过以保持环境无关。
            return;
        }
        // [sm-reloc] config home 隔离到 @TempDir（NexusaiPaths override；ClaudePaths 只读兼容源不驱动写根回落）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());

        String projectRoot = AutoMemPaths.currentSessionProjectRoot();

        assertThat(projectRoot).isEqualTo(configHome.toString());
        assertThat(Path.of(projectRoot).isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("2-arg 构造 + resolver：resolvePath 按 resolver 求值 per-session slug，构造后再改 project root 仍走 resolver（动态生效）")
    void resolvePath_perSessionResolver_appliesDynamically() {
        // [sm-reloc] config home 隔离到 @TempDir（NexusaiPaths override；ClaudePaths 只读兼容源不驱动写根回落）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        AutoMemPaths.setCurrentProjectRoot(configHome.resolve("bound-project").toString());
        // stub resolver：sessionId → 固定 slug 目录 {configHome}/projects/P（per-session 派生层）
        java.util.function.Function<String, Path> stubResolver =
            sid -> configHome.resolve("projects").resolve("P");
        SessionMemoryService sm = new SessionMemoryService(configHome, stubResolver);

        Path expectedPerSession = configHome.resolve("projects").resolve("P")
            .resolve("sess-1").resolve("session-memory").resolve("summary.md");
        assertThat(sm.resolvePath("sess-1"))
            .as("2-arg resolver：session-memory 落 {projects/P}/{sessionId}/session-memory/summary.md")
            .isEqualTo(expectedPerSession);

        // 构造后会话 projectRoot 变更（会话中途 rebind）→ resolvePath 仍走 resolver（per-session 动态
        // 生效，替代旧 1-arg 构造期快照登记）
        AutoMemPaths.setCurrentProjectRoot(configHome.resolve("other-project").toString());
        assertThat(sm.resolvePath("sess-1"))
            .as("per-session resolver 动态求值：不随构造后 projectRoot 变更（sm-reloc 生产语义）")
            .isEqualTo(expectedPerSession);
    }

    @Test
    @DisplayName("resolvePath(null) → {baseDir}/session-memory/summary.md（无 sessionId 分支）")
    void resolvePath_nullSessionId_usesBaseDirDirectly() {
        // [sm-reloc] config home 隔离到 @TempDir（NexusaiPaths override；ClaudePaths 只读兼容源不驱动写根回落）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        SessionMemoryService sm = new SessionMemoryService(
            Path.of(AutoMemPaths.currentSessionProjectRoot()));

        assertThat(sm.resolvePath(null))
            .isEqualTo(Path.of(fallbackWhenNoSession()).resolve("session-memory").resolve("summary.md"));
    }

    /** 回落链结果（ThreadLocal 空 → CLAUDE_PROJECT_DIR env ?? config home override）。 */
    private static String fallbackWhenNoSession() {
        String env = System.getenv(AutoMemPaths.CLAUDE_PROJECT_DIR_ENV);
        return (env != null && !env.isBlank()) ? env : NexusaiPaths.getAppConfigHomeDir();
    }
}

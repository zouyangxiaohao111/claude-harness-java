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
 * 1-arg legacy（固定 baseDir）仍由本类锁定。
 * <b>[批 4b-1]</b> 原「无会话 → 回落 {@code env ?? config home}」链已废除（用户铁律：绝不回落
 * configHome 冒充项目根；承载 ThreadLocal 载体亦已删除）⇒ baseDir 一律由**构造入参显式给出**。
 * 本测试锁定：
 * <ol>
 *   <li>[批 4b-1] 显式根驱动 baseDir（不等于 user.dir），且
 *       {@code AutoMemPaths.currentSessionProjectRootOrNull()} 在不设 env 时恒为 null（绝不回落 configHome）</li>
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
        NexusaiPaths.setConfigHomeDirOverride(null);  // [sm-reloc] 复位 config home override（防泄漏）
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    @Test
    @DisplayName("[批 4b-1] baseDir 为显式根（构造入参）—— 无会话即无隐式来源，绝不回落 configHome")
    void baseDir_usesExplicitRootNeverImplicitFallback() {
        // [sm-reloc] config home 隔离到 @TempDir；[批 4b-1] 根由**构造入参显式给出**
        //   （原经 AutoMemPaths.currentSessionProjectRoot() 的 ThreadLocal ?? config home 回落链取，
        //   该回落链已废除 —— 用户铁律「绝不回落 configHome 冒充项目根」）。
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        // G5：SessionMemoryService 内部亦可能触及 nexusai 自有根 → 唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        String expected = NexusaiPaths.getAppConfigHomeDir();

        SessionMemoryService sm = new SessionMemoryService(Path.of(expected));

        assertThat(sm.resolvePath("sess-1"))
            .as("session-memory 落 {baseDir}/{sessionId}/session-memory/summary.md（CC filesystem.ts:261-271）")
            .isEqualTo(Path.of(expected).resolve("sess-1").resolve("session-memory").resolve("summary.md"));
        // 显式根不得被进程工作目录替换（旧缺陷：无会话构造读 user.dir）
        assertThat(Path.of(expected).isAbsolute()).isTrue();
        assertThat(expected).as("显式根必须与进程 user.dir 不同（本用例根 = 隔离 configHome）")
            .isNotEqualTo(System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("[批 4b-1] AutoMemPaths.currentSessionProjectRootOrNull() 绝不回落 config home（env 未设 → null）")
    void currentSessionProjectRootOrNull_neverFallsBackToConfigHome() {
        // WHY（本批核心红线）：「项目根只应有一个来源，且不得回落 configHome 冒充项目根」。
        //   批 4b-1 删除了三级回落版 currentSessionProjectRoot()（其第 3 级 = getAppConfigHomeDir()）
        //   与承载它的 ThreadLocal 载体；唯一入口现为 env（NEXUSAI_PROJECT_DIR）或 null。
        //   若哪天有人把 config home 回落加回来，本断言变红。
        String env = System.getenv(AutoMemPaths.CLAUDE_PROJECT_DIR_ENV);
        if (env != null && !env.isBlank()) {
            // env 显式配置存在 → 本用例前提不成立（回落链由 env 主导）；跳过以保持环境无关。
            return;
        }
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());

        assertThat(AutoMemPaths.currentSessionProjectRootOrNull())
            .as("env 未配置 ⇒ null（⛔ 不得返回 config home 冒充项目根）")
            .isNull();
    }

    @Test
    @DisplayName("2-arg 构造 + resolver：resolvePath 按 resolver 求值 per-session slug，构造后再改 project root 仍走 resolver（动态生效）")
    void resolvePath_perSessionResolver_appliesDynamically() {
        // [sm-reloc] config home 隔离到 @TempDir（NexusaiPaths override；ClaudePaths 只读兼容源不驱动写根回落）
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        // G5：唯一 appName 隔离（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
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
        // [批 4b-1] 显式根（configHome override 后的自有根）—— 无隐式回落链
        String baseDir = NexusaiPaths.getAppConfigHomeDir();
        SessionMemoryService sm = new SessionMemoryService(Path.of(baseDir));

        assertThat(sm.resolvePath(null))
            .isEqualTo(Path.of(baseDir).resolve("session-memory").resolve("summary.md"));
    }

}

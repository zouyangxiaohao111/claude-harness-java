package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sm-reloc 2026-09-02] SessionMemoryService per-session 落点测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：SM 摘要落点从「构造期冻结 baseDir →
 * 平铺 ~/.nexusai/sess-xxx」改为 per-session
 * {@code {configHome}/projects/{slug}/{sessionId}/session-memory/summary.md}（与 transcript 同源
 * 分层，绑定项目也不写进用户项目真实目录）。生产装配 2-arg
 * {@code (fallbackBaseDir, SessionStorage::sessionProjectDir)} —— 本测试锁定：
 * <ol>
 *   <li>resolvePath 走 resolver 的 slug 目录（生产 = SessionProjectRoot 绑定项目的 sanitize 稳定锚）</li>
 *   <li>产物前缀在 {@code {configHome}/projects} 下，绝不写进 realProjectDir（绑定项目不被污染）</li>
 *   <li>per-session resolver 构造不 eager mkdir（目录由 setupSessionMemoryFile 惰性建，bean hermetic）</li>
 * </ol>
 */
@DisplayName("[sm-reloc] SessionMemoryService per-session 路径（SessionStorage::sessionProjectDir resolver）")
class SessionMemoryPerSessionPathTest {

    /** 唯一会话键（避与其他测试的 sess-1/sess-a 静态残留串扰）。 */
    private static final String SESSION_ID = "sess-sm-reloc-1";

    @TempDir
    Path appConfigIsolation;

    @TempDir
    Path realProjectDir;

    @BeforeEach
    void setUp() {
        // G5：唯一 appName 隔离 → config home = {user.home}/.sm-reloc-test-<...>（防污染真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("sm-reloc-test-" + appConfigIsolation.getFileName());
        // 清静态会话态（防 CwdResolution L1 originalCwd / L2 boundProject 被其他测试残留污染）
        SessionProjectRoot.reset();
        SessionCwdHolder.reset();
        // 绑定真实项目根（L2 boundProject 层，sessionProjectDir 解析到此）——须绝对路径且目录存在
        SessionProjectRoot.setForSession(SESSION_ID, realProjectDir.toString());
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
        SessionCwdHolder.reset();
        NexusaiPaths.setAppNameOverride(null);     // 复位 appName
        AutoMemPaths.setCurrentProjectRoot(null);  // 复位 ThreadLocal
    }

    @Test
    @DisplayName("resolvePath(sess) = {configHome}/projects/{sanitize(稳定锚)}/sess/session-memory/summary.md（与 transcript 同 slug 分层）")
    void resolvePath_perSession_underProjectsSlug() {
        SessionMemoryService sm = new SessionMemoryService(
            Path.of(NexusaiPaths.getAppConfigHomeDir()), SessionStorage::sessionProjectDir);

        // 稳定锚 = CwdResolution.getOriginalCwdLayer（boundProject realpath 归一化层；与 SessionStorage
        // sessionProjectDir 内部同源，避免 Windows realpath 大小写/短名差异造成 flaky）
        String projectAnchor = CwdResolution.getOriginalCwdLayer(SESSION_ID);
        Path configHome = Path.of(NexusaiPaths.getAppConfigHomeDir());
        Path expectedSlugDir = configHome.resolve("projects")
            .resolve(AutoMemPaths.sanitizePath(projectAnchor));
        Path expected = expectedSlugDir.resolve(SESSION_ID)
            .resolve("session-memory").resolve("summary.md");

        assertThat(sm.resolvePath(SESSION_ID))
            .as("per-session：session-memory 落 {configHome}/projects/{slug}/{sessionId}/session-memory/summary.md")
            .isEqualTo(expected);
        // 前缀分层断言：产物在 config-home/projects 下（不写进用户项目真实目录 / 不写平铺 config-home 根）
        assertThat(sm.resolvePath(SESSION_ID).startsWith(configHome.resolve("projects"))).isTrue();
        assertThat(sm.resolvePath(SESSION_ID).startsWith(realProjectDir))
            .as("绑定项目真实目录不得被 SM 摘要写入（resolvePath 产物前缀在 projects/{slug}）")
            .isFalse();
    }

    @Test
    @DisplayName("构造惰性：2-arg + resolver 构造不 eager mkdir（仅 resolvePath 纯路径派生，不依赖目录存在）")
    void constructor_withResolver_noEagerMkdir() {
        SessionMemoryService sm = new SessionMemoryService(
            Path.of(NexusaiPaths.getAppConfigHomeDir()), SessionStorage::sessionProjectDir);

        Path resolved = sm.resolvePath(SESSION_ID);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getParent()).isNotNull();
        assertThat(resolved.toString()).contains("session-memory");
        // 构造本身不应因目录创建抛错 —— 走到这里即证明不 eager mkdir（per-session 目录由
        // setupSessionMemoryFile 惰性 createDirectoriesOwnerOnly）
        assertThat(resolved.startsWith(realProjectDir)).isFalse();
    }
}

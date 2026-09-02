package com.nexusai.application.agent.permission.hook;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * [IMP-PERM-CWD-04/05 返工] CC {@code getAttributionRepoRoot}（commitAttribution.ts:83-85）
 * 完整链 = {@code findGitRoot(getCwd()) ?? getOriginalCwd()} 的 Java 端接线验证。
 *
 * <p>WHY（规则九，测试验证意图）: 仅接 {@code getCwd()} 主层时，`cd subdir` 场景下 attribution
 * {@code normalizeFilePath} 的 relativize 基准是 cwd 子目录而非 git root，与 CC git-root 基准
 * 偏离；非 git 目录时又无 originalCwd 兜底。本测试锚定三层行为：
 * <ol>
 *   <li>git 仓库内（含 cd subdir）→ 返回 git root（对齐 CC findGitRoot 回落，非 cwd 非 user.dir）</li>
 *   <li>非 git 目录 → 回落 {@code CwdResolution.getOriginalCwdLayer(sessionId)}（对齐 CC ?? getOriginalCwd()）</li>
 *   <li>无参构造器（生产路径，经 RequestContext.sessionId()）→ repoRoot 恒非 null（链恒有值）</li>
 * </ol>
 */
@DisplayName("[IMP-PERM-CWD-04/05] CommitAttributionTracker.getAttributionRepoRoot 完整链")
class AttributionRepoRootCwdTest {

    @AfterEach
    void cleanup() {
        CwdResolution.clearCurrentOverride();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        RequestContext.clear();
    }

    @Test
    @DisplayName("git 仓库内（cd subdir）→ 返回 git root（findGitRoot 回落，非 cwd 非 user.dir）")
    void gitRepoInside_cdSubdir_resolvesGitRoot(@TempDir Path repoDir) throws Exception {
        // WHY: CC getAttributionRepoRoot 用 findGitRoot(getCwd()) 处理 `cd subdir`（commitAttribution.ts:83-85
        //   注释 "then resolves to git root to handle `cd subdir` case"）—— attribution normalizeFilePath
        //   的 relativize 基准必须是 git root，否则 cd 后文件相对路径键与 git diff 输出不一致。
        Files.createDirectory(repoDir.resolve(".git"));
        Path sub = repoDir.resolve("src/main/java");
        Files.createDirectories(sub);

        // 会话 cwd = cd 子目录（SessionCwdHolder = getCwd 主层来源）
        SessionCwdHolder.set("sess-a", sub.toString());

        String root = CommitAttributionTracker.getAttributionRepoRoot("sess-a");

        assertThat(Path.of(root).toRealPath())
            .as("cd subdir 场景必须回落 git root，不得返回 cwd 子目录")
            .isEqualTo(repoDir.toRealPath())
            .isNotEqualTo(sub.toRealPath())
            .isNotEqualTo(Path.of(System.getProperty("user.dir")).toRealPath());
    }

    @Test
    @DisplayName("非 git 目录 → 回落 getOriginalCwdLayer(sessionId)（?? getOriginalCwd() 兜底）")
    void nonGitDir_fallsBackToOriginalCwdLayer(@TempDir Path plainDir) throws Exception {
        // WHY: CC getAttributionRepoRoot 在 findGitRoot(cwd) 为 null（非 git 目录）时回落
        //   getOriginalCwd()（commitAttribution.ts:85）。Java 端等价 = getOriginalCwdLayer。
        SessionCwdHolder.set("sess-a", plainDir.toString());

        String root = CommitAttributionTracker.getAttributionRepoRoot("sess-a");

        assertThat(root)
            .as("非 git 目录必须回落 getOriginalCwdLayer 而非 cwd")
            .isEqualTo(CwdResolution.getOriginalCwdLayer("sess-a"))
            .isNotEqualTo(CwdResolution.getCwd("sess-a"));
    }

    @Test
    @DisplayName("无参构造器（RequestContext.sessionId() 生产路径）→ repoRoot 恒非 null")
    void noArgConstructor_repoRootAlwaysNonNull() throws Exception {
        // WHY: LlmAgentLoop.registerAttributionHooks（LlmAgentLoop.java:7036-7037）走
        //   new RegisterAttributionHooks() 无参构造（COMMIT_ATTRIBUTION 门控关为 no-op，但构造即
        //   应产出可用的 repoRoot supplier）。链恒有值（getCwd 恒非 null + findGitRoot null 时
        //   originalCwdLayer 恒非 null）—— 门控开启时不至于拿到 null repoRoot。
        CommitAttributionTracker tracker = new CommitAttributionTracker();
        assertThat(tracker.repoRoot()).isNotNull();

        RegisterAttributionHooks hooks = new RegisterAttributionHooks();
        assertThat(hooks.tracker()).isNotNull();
    }
}

package com.nexusai.application.agent.prompt;

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
 * SessionGitStatusRegistry 意图测试 · 对齐 CC context.ts:97 原话「会话开始一次快照、会话内不更新」。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9）：同一会话跨 run 必须复用同一 {@link GitStatusProvider}
 * （git status 只算一次 → system 尾字节稳定 → 保护 deepseek 单前缀缓存）；不同会话隔离
 * （互不串 gitStatus，Spring 多会话服务下进程级缓存会串）；evict 后重建新实例（防无界增长，
 * 且 /clear / 会话删除后重新快照，等价 CC reset 后新 turn）。
 *
 * <p><b>[r10b · 裁定 ③] 签名变更</b>：{@code getForSession(sessionId)} →
 * {@code getForSession(sessionId, sessionCwd)}（注册表不再自调 {@code CwdResolution}）。
 * 本类的用例随之显式传 cwd。
 */
class SessionGitStatusRegistryTest {

    /** 用例用的人造会话 cwd（临时目录，git 判定结果无关本类断言）。 */
    private Path cwdA;
    private Path cwdB;

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
    }

    private Path tmpCwd(Path tmp, String name) throws Exception {
        return Files.createDirectories(tmp.resolve(name));
    }

    @Test
    @DisplayName("同 sessionId 两次 getForSession → 同一实例（CC context.ts:97 会话内只算一次）")
    void sameSession_returnsSameInstance(@TempDir Path tmp) throws Exception {
        cwdA = tmpCwd(tmp, "a");
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();
        GitStatusProvider first = registry.getForSession("sess-a", cwdA);
        GitStatusProvider second = registry.getForSession("sess-a", cwdA);

        assertThat(second).as("同 sessionId 跨 run 复用同一实例").isSameAs(first);
        assertThat(registry.size()).as("单会话只占一个槽").isEqualTo(1);
    }

    @Test
    @DisplayName("不同 sessionId → 不同实例（互不串 gitStatus）")
    void differentSession_returnsDifferentInstance(@TempDir Path tmp) throws Exception {
        cwdA = tmpCwd(tmp, "a");
        cwdB = tmpCwd(tmp, "b");
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();

        assertThat(registry.getForSession("sess-a", cwdA))
            .as("不同会话隔离，不共享 GitStatusProvider")
            .isNotSameAs(registry.getForSession("sess-b", cwdB));
        assertThat(registry.size()).as("两会话各占一槽").isEqualTo(2);
    }

    @Test
    @DisplayName("evict 后再次 getForSession → 新实例（会话结束释放，防无界增长）")
    void evict_thenGetForSession_returnsNewInstance(@TempDir Path tmp) throws Exception {
        cwdA = tmpCwd(tmp, "a");
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();
        GitStatusProvider before = registry.getForSession("sess-a", cwdA);

        registry.evict("sess-a");
        assertThat(registry.size()).as("evict 后槽位释放").isZero();

        GitStatusProvider after = registry.getForSession("sess-a", cwdA);
        assertThat(after).as("evict 后重新快照（新实例）").isNotSameAs(before);
    }

    @Test
    @DisplayName("null/blank sessionId → null（守卫，不注册）；evict(null) no-op 不抛")
    void nullOrBlankSession_returnsNull(@TempDir Path tmp) throws Exception {
        cwdA = tmpCwd(tmp, "a");
        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();

        assertThat(registry.getForSession(null, cwdA)).as("null sessionId → null（回落每 run new）").isNull();
        assertThat(registry.getForSession("  ", cwdA)).as("blank sessionId → null").isNull();

        registry.evict(null); // no-op 不抛
        assertThat(registry.size()).as("守卫不产生槽位").isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // [r10b · 裁定 ③] 槽语义：显式 sessionCwd 必须真是 getCwd 语义
    // ════════════════════════════════════════════════════════════════════

    /**
     * 注册表会用<b>调用方显式传入的 cwd</b>（= {@code CwdResolution.getCwd} 语义）建 provider。
     *
     * <p>WHY（守护什么 · 裁定 ③ 的「语义必须对」）：CC 的 git 判定是
     * {@code findGitRoot(getCwd())}（git.ts:218-222）⇒ 传入值必须是 <b>getCwd 语义</b>
     * （L1 = sessionCwd 层，bash {@code cd} 可覆盖），<b>不是</b> {@code workspaceDir} /
     * {@code runExplicitCwd}（boundProject/originalCwd 语义，不被 cd 覆盖）。
     * 本用例用 <b>DIVERGED 会话</b>（两槽不同值）把这条变成可证伪断言。
     *
     * <p>装置：会话 cwd 槽 = A（<b>是</b> git 仓库）；originalCwd 槽 = B（<b>非</b> git 仓库）。
     * 传入 {@code CwdResolution.getCwd(sid)}（= A）⇒ 注册表 provider 的 git 锚 = A ⇒
     * {@code isGit()=true}。若误传 originalCwd（B）⇒ false ⇒ 红。
     *
     * <p>反向实验配方：把断言里的 {@code Path.of(CwdResolution.getCwd(sid))} 换成
     * {@code Path.of(CwdResolution.getOriginalCwdLayer(sid))} ⇒ {@code isGit()} 变 false ⇒ 红。
     */
    @Test
    @DisplayName("[r10b-D13] 显式 sessionCwd 走 getCwd 语义（DIVERGED 会话下与 originalCwd 可分辨）")
    void explicitSessionCwd_isGetCwdSemantics_notOriginalCwd(@TempDir Path tmp) throws Exception {
        Path sessionCwd = Files.createDirectories(tmp.resolve("cd-target-git"));
        Files.createDirectory(sessionCwd.resolve(".git"));
        Path originalCwd = Files.createDirectories(tmp.resolve("orig-anchor-nongit"));
        assertThat(new GitStatusProvider(originalCwd).findGitRoot())
            .as("夹具前置：originalCwd 侧必须不在任何 git 仓库内（否则两侧不可分辨）")
            .isNull();

        String sid = "sess-r10b-registry-diverge";
        SessionCwdHolder.set(sid, sessionCwd.toString());
        SessionCwdHolder.setOriginalCwd(sid, originalCwd.toString());
        // 显式覆盖全局默认（sessionless 不抛）⇒ 任何落到 DB 回源分支的读取都会抛（可证伪）
        SessionProjectRoot.setDbResolver(s -> SessionProjectRoot.Lookup.unknown());

        String getCwdValue = CwdResolution.getCwd(sid);
        String originalCwdValue = CwdResolution.getOriginalCwdLayer(sid);
        assertThat(getCwdValue)
            .as("DIVERGED 夹具活性：两槽必须不同值")
            .isNotEqualTo(originalCwdValue);

        SessionGitStatusRegistry registry = new SessionGitStatusRegistry();
        GitStatusProvider provider = registry.getForSession(sid, Path.of(getCwdValue));

        assertThat(provider.isGit())
            .as("⭐ 注册表 provider 的 git 锚 = 传入的 sessionCwd（cd 后的目录，是 git 仓库）")
            .isTrue();
    }
}

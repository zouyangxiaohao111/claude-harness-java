package com.nexusai.application.agent.agent;

import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CwdResolution 工作目录域统一入口 · 对齐 CC utils/cwd.ts pwd()/getCwd() + bootstrap/state.ts 四层 STATE。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 cwd 解析是<b>分层回落</b>语义：{@code pwd() = override ?? STATE.cwd}
 * （cwd.ts:19-21），{@code getCwd() = try pwd() catch → getOriginalCwd()}（cwd.ts:26-32）。若 Java 端分层
 * 顺序错误或某层异常抛出，则文件工具/git/权限相对路径会取错 cwd（跨项目污染 / cd 后用旧 cwd）。本测试
 * 锁定五场景分层正确 + 异常 safeGet 回落 + cd-in-worktree 合并存储语义（INV-2）。
 *
 * <p>场景对应 AC-1 五场景：
 * <ol>
 *   <li>override 非空 → override</li>
 *   <li>override 空 + sessionCwd 非空 → sessionCwd</li>
 *   <li>+ boundProject 非空 → boundProject（override/sessionCwd 均空时）</li>
 *   <li>全空 → user.dir</li>
 *   <li><b>活跃 worktree 内 cd 后 getCwd 返回 cd 子目录</b>（合并存储，WorktreeCwdTracker 不作优先层，INV-2）</li>
 * </ol>
 */
@DisplayName("[CC-CWD-01/02/04] CwdResolution 三层 getCwd + override + originalCwd 层")
class CwdResolutionTest {

    @AfterEach
    void cleanup() {
        CwdResolution.clearCurrentOverride();
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        // 回源解析器是 static 注入口 → 用例必须自行注销，否则跨类污染（本类内用 try/finally 亦可）。
        SessionProjectRoot.setDbResolver(null);
    }

    @Test
    @DisplayName("场景①: override 非空 → 返回 override (对齐 CC cwdOverrideStorage.getStore ??)")
    void scenario1_overrideWins(@TempDir Path overrideDir) throws Exception {
        // WHY: CC pwd() 优先取 AsyncLocalStorage override（cwd.ts:19-21），并发 agent 各自隔离。
        SessionCwdHolder.set("sess-a", "/some/session-cwd");
        SessionProjectRoot.setForSession("sess-a", "/some/bound-project");

        String result = CwdResolution.runWithCwdOverride(overrideDir.toString(),
                () -> CwdResolution.getCwd("sess-a"));

        assertThat(result)
            .as("override 必须压过 sessionCwd / boundProject")
            .isEqualTo(overrideDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景②: override 空 + sessionCwd 非空 → 返回 sessionCwd (对齐 CC STATE.cwd)")
    void scenario2_sessionCwdWinsWhenNoOverride(@TempDir Path sessionDir) throws Exception {
        // WHY: 无 override 时 pwd() 回 getCwdState()=STATE.cwd。worktree 入口与 cd 共用此层 [Fix-R1]。
        SessionProjectRoot.setForSession("sess-a", "/some/bound-project");
        SessionCwdHolder.set("sess-a", sessionDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("sessionCwd 必须压过 boundProject")
            .isEqualTo(sessionDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景③: override+sessionCwd 均 空 + boundProject 非空 → 返回 boundProject (D-1: getForSession)")
    void scenario3_boundProjectWhenNoSessionCwd(@TempDir Path projectDir) throws Exception {
        // WHY: boundProject 层对齐 CC originalCwd（启动目录）。D-1 裁决：只读 getForSession，不读 resolve()
        // （resolve 回落 env/config home 属身份域，会使 user.dir 成死代码 + 身份域泄入工作目录域）。
        SessionProjectRoot.setForSession("sess-a", projectDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("boundProject 必须是 getForSession 的绑定值，不得读 resolve() 回落链")
            .isEqualTo(projectDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景④ [批 4a] DB 无此会话（无解析器/合成 id）→ 按无会话出口解析（进程 user.dir），不抛")
    void scenario4_unknownSessionGoesToNonSessionExit() throws Exception {
        // WHY（规则九 · 批 4a 实测）：合成 sessionId 的生产路径多且合法（MCP 入站 / standalone
        //   fork / subagent / 文档更新器现造 id）⇒ 「DB 无此会话」属「确无会话」，走命名出口；
        //   ⛔ 不得 fail-loud（那会打死合成 id 的合法路径：实测 5 处生产点 + 100+ 测试类）。
        //   RED: 把 unknown 分支也改成抛 ⇒ 本用例红。
        assertThat(CwdResolution.getCwd("sess-not-in-db"))
            .as("DB 无此会话 ⇒ 无会话出口（进程 user.dir）")
            .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
    }

    @Test
    @DisplayName("[批 4a #7] 会话存在（DB 有行）却无绑定项目根 → fail-loud（不再回落 user.dir）")
    void knownSessionWithoutBinding_failsLoud() {
        // WHY（规则九 · 用户 2026-09-14 裁定 #7）：「web 会话必须绑定项目才能进行，没有就代表报错了，
        //   数据链路异常了」⇒ 判据 = <b>DB 认得该会话</b>（sessionKnown）却解析不出项目根 ⇒ fail-loud，
        //   不得用进程 user.dir（后端启动目录）冒充会话项目根（工具/权限/transcript 会全锚错）。
        // RED: 把 getCwd/getOriginalCwdLayer 里的 sessionKnown() 分支改回 `return getCwdForNonSession()`
        //   ⇒ 本用例红。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());
        try {
            assertThatThrownBy(() -> CwdResolution.getCwd("sess-known-unbound"))
                .as("有会话却无项目根 ⇒ 必须抛（不得静默回落 user.dir）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-known-unbound");
            assertThatThrownBy(() -> CwdResolution.getOriginalCwdLayer("sess-known-unbound"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-known-unbound");
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    @Test
    @DisplayName("[批 4a #13] 无会话出口 = override ?? 进程 user.dir（只对确无会话开放）")
    void nonSessionExit_resolvesOverrideThenProcessUserDir(@TempDir Path projectDir, @TempDir Path overrideDir)
            throws Exception {
        // WHY（规则九 · 用户裁定 #13）：无会话路径必须<b>命名自解释</b>且只对「确无会话」开放；
        //   它不得偷看会话层，否则「确无会话」与「漏传 sessionId」再次混为一谈。
        String expected = Path.of(System.getProperty("user.dir")).toRealPath().toString();
        assertThat(CwdResolution.getCwdForNonSession()).isEqualTo(expected);
        assertThat(CwdResolution.getOriginalCwdLayerForNonSession()).isEqualTo(expected);

        // override 层仍生效（与旧 getCwd(null) 逐字节同行为）
        assertThat(CwdResolution.runWithCwdOverride(overrideDir.toString(), CwdResolution::getCwdForNonSession))
            .as("无会话出口仍认显式 override 层（对齐 CC cwdOverrideStorage）")
            .isEqualTo(overrideDir.toRealPath().toString());

        // 反向锚：已绑定会话存在时，无会话出口仍返回 user.dir（不串会话层）
        SessionProjectRoot.setForSession("sess-a", projectDir.toString());
        assertThat(CwdResolution.getCwdForNonSession())
            .as("无会话出口不得偷看 boundProject（否则与 getCwd(sessionId) 无差别）")
            .isEqualTo(expected)
            .isNotEqualTo(projectDir.toRealPath().toString());
    }

    @Test
    @DisplayName("[批 4a #9] 冻结表 miss ⇒ 回源 DB 并回填 ⇒ getCwd 命中（后端重启后首条消息前）")
    void dbRefill_onFrozenTableMiss(@TempDir Path projectDir) throws Exception {
        // WHY（规则九 · impact C）：SessionProjectRoot.BY_SESSION 是纯内存表，只在 bind / 首 run 写入
        //   ⇒ 后端重启后、首条消息前查表必然 miss。若把「内存 miss」当「未绑定」，新 fail-loud 会把
        //   <b>正常会话</b>误判成数据链路异常。判据必须是「DB 也查不到」⇒ miss 时回源 DB 并回填。
        // RED: 删掉 refillFromDb 里的 setForSession 回填 ⇒ 第二次调用仍回源（calls==2）⇒ 本用例红。
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            calls.incrementAndGet();
            return SessionProjectRoot.Lookup.bound(projectDir.toString());
        });
        try {
            String first = CwdResolution.getCwd("sess-restarted");
            assertThat(first)
                .as("冻结表 miss + DB 有绑定 ⇒ 必须解析出绑定项目根，而非 fail-loud")
                .isEqualTo(projectDir.toRealPath().toString());

            assertThat(CwdResolution.getCwd("sess-restarted"))
                .as("第二次读必须命中回填后的冻结表")
                .isEqualTo(projectDir.toRealPath().toString());
            assertThat(calls.get())
                .as("回源只应发生一次（miss ⇒ 回源 ⇒ <b>回填</b>；只回源不回填会每次查 DB）")
                .isEqualTo(1);
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    @Test
    @DisplayName("[批 4a] 真实线程：池线程按显式 sessionId 解析（不依赖任何线程本地会话态）")
    void resolvesOnRealWorkerThread(@TempDir Path projectDir) throws Exception {
        // WHY（规则九 · 本仓铁律「会话态一律不得经 ThreadLocal/MDC 读」）：解析必须由显式 sessionId
        //   决定，与执行线程无关 —— 子代理/Hook/tool-exec 池线程上必须拿到<b>同一会话</b>的项目根。
        //   夹具必须经真实线程（⛔ 不得同线程设值再断言）。
        SessionProjectRoot.setForSession("sess-t", projectDir.toString());
        // 另一会话在 DB 中「存在但无绑定」⇒ 池线程上同样 fail-loud（不因线程复用读到别会话的值）
        SessionProjectRoot.setDbResolver(sid -> "sess-other-unbound".equals(sid)
            ? SessionProjectRoot.Lookup.unbound()
            : SessionProjectRoot.Lookup.unknown());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> new Thread(r, "cwd-resolution-probe"));
        try {
            assertThat(pool.submit(() -> CwdResolution.getCwd("sess-t")).get())
                .as("池线程按显式 sessionId 解析出绑定项目根")
                .isEqualTo(projectDir.toRealPath().toString());

            Throwable wrapped = catchThrowable(() ->
                pool.submit(() -> CwdResolution.getCwd("sess-other-unbound")).get());
            assertThat(wrapped).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(wrapped.getCause())
                .as("同一池线程上的「有会话未绑定」同样 fail-loud（不因线程复用读到别会话的值）")
                .isInstanceOf(IllegalStateException.class);
        } finally {
            pool.shutdownNow();
            SessionProjectRoot.setDbResolver(null);
        }
    }

    @Test
    @DisplayName("场景⑤ [Fix-R1]: 活跃 worktree 内 cd 后 getCwd 返回 cd 子目录 (合并存储 INV-2)")
    void scenario5_cdInWorktreeReturnsCdSubdir(@TempDir Path worktreeBase) throws Exception {
        // WHY: [Fix-R1] worktree 入口与 cd 共用 SessionCwdHolder（对齐 CC 单 STATE.cwd）。
        //       若 WorktreeCwdTracker 作 getCwd 优先层，活跃 worktree 内 cd 后 getCwd 会返回 worktree 基路径
        //       而非 cd 子目录，违反 INV-2「cd 后用新 cwd」与 CC Shell.ts 行为。
        //       本测试验证 CwdResolution 不读 WorktreeCwdTracker，sessionCwd 层（cd 写入）才是 worktree 内的真相。
        Path sub = worktreeBase.resolve("deep/sub");
        sub.toFile().mkdirs();

        // worktree 入口：写 SessionCwdHolder（与 bash cd 同槽）
        SessionCwdHolder.set("sess-a", worktreeBase.toString());
        // 模拟 WorktreeCwdTracker 仍记录基路径（退出恢复用）—— CwdResolution 不应读它
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setCwd("sess-a", worktreeBase);

        // 入口时 getCwd 应返回 worktree 基路径（sessionCwd 层）
        String afterEnter = CwdResolution.getCwd("sess-a");
        assertThat(afterEnter).isEqualTo(worktreeBase.toRealPath().toString());

        // bash cd 子目录 → 覆盖同槽 sessionCwd
        SessionCwdHolder.set("sess-a", sub.toString());

        String afterCd = CwdResolution.getCwd("sess-a");
        assertThat(afterCd)
            .as("cd 后 getCwd 必须返回 cd 子目录，不得返回 WorktreeCwdTracker 记录的基路径 (INV-2)")
            .isEqualTo(sub.toRealPath().toString())
            .isNotEqualTo(worktreeBase.toRealPath().toString());

        // 清理 tracker（避免跨测试污染）
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd("sess-a");
    }

    @Test
    @DisplayName("getOriginalCwdLayer [批 4a #7]: 已绑定 → boundProject；解绑后会话仍存在 → fail-loud")
    void originalCwdLayer_boundProjectThenFailsLoud(@TempDir Path projectDir) throws Exception {
        // WHY: CC getOriginalCwd (state.ts:500-502) 作 CLAUDE.md 扫描/存档锚。D-1 裁决 Java 端 =
        //   getForSession（不读 resolve 回落链）。[批 4a #7] 解绑后<b>不得</b>回落 user.dir
        //   （「有会话却解析不出」= 数据链路异常，fail-loud）—— 解绑只清冻结表，DB 里会话仍在
        //   （sessionKnown=true）⇒ 走 fail-loud 而非无会话出口。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());
        try {
            SessionProjectRoot.setForSession("sess-a", projectDir.toString());
            assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
                .isEqualTo(projectDir.toRealPath().toString());

            SessionProjectRoot.clearSession("sess-a");
            assertThatThrownBy(() -> CwdResolution.getOriginalCwdLayer("sess-a"))
                .as("解绑后（会话仍存在）必须抛（fail-loud），不得回落 user.dir")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-a");
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    @Test
    @DisplayName("[INV-3] 进 worktree 后 getOriginalCwdLayer 返回 worktreePath 非 boundProject")
    void originalCwdLayer_worktreeEntryReanchorsToWorktreePath(@TempDir Path boundProject,
                                                              @TempDir Path worktreePath) throws Exception {
        // WHY (规则九): CC EnterWorktreeTool.ts:94-96 三连 process.chdir + setCwd(worktreePath)
        //   + setOriginalCwd(getCwd())。setOriginalCwd 此时=getCwd()=worktreePath（setCwd 已先写），
        //   故 STATE.originalCwd 重锚到 worktreePath。CC claudemd.ts:851 getOriginalCwd() 用作
        //   CLAUDE.md 扫描根、会话存档锚——worktree 会话内这两者必须走 worktreePath 而非 boundProject，
        //   否则 worktree 内的 CLAUDE.md 不会被读到、存档锚到 boundProject 错位。
        //   D-1 红线不变：getOriginalCwdLayer 不读 resolve()（新 originalCwd 槽是独立槽，非 resolve）。
        SessionProjectRoot.setForSession("sess-a", boundProject.toString());

        // 进 worktree 前：originalCwdLayer = boundProject（启动锚）
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("进 worktree 前 originalCwdLayer 必须是 boundProject")
            .isEqualTo(boundProject.toRealPath().toString());

        // 进 worktree：对齐 CC setOriginalCwd(getCwd())=worktreePath 重锚 originalCwd 层
        SessionCwdHolder.setOriginalCwd("sess-a", worktreePath.toString());

        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("进 worktree 后 originalCwdLayer 必须重锚到 worktreePath (INV-3)，不得仍是 boundProject")
            .isEqualTo(worktreePath.toRealPath().toString())
            .isNotEqualTo(boundProject.toRealPath().toString());
    }

    @Test
    @DisplayName("[INV-3] Exit worktree 后 originalCwdLayer 回落 boundProject (对齐 CC ExitWorktreeTool:129 恢复)")
    void originalCwdLayer_exitRestoresBoundProject(@TempDir Path boundProject,
                                                    @TempDir Path worktreePath) throws Exception {
        // WHY: CC ExitWorktreeTool.ts:126-129 restoreSessionToOriginalCwd: setCwd(originalCwd)
        //   + setOriginalCwd(originalCwd)——退出恢复到 pre-worktree originalCwd。Java 端无 pre-worktree
        //   originalCwd 持久化（boundProject 是稳定身份不变），clearOriginalCwd 回落 boundProject
        //   对齐 CC pre-worktree originalCwd=boundProject 语义。退出后 CLAUDE.md 扫描/存档应回到 boundProject。
        SessionProjectRoot.setForSession("sess-a", boundProject.toString());
        SessionCwdHolder.setOriginalCwd("sess-a", worktreePath.toString());
        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .isEqualTo(worktreePath.toRealPath().toString());

        // Exit worktree：clear originalCwd 槽（对齐 CC 退出恢复）
        SessionCwdHolder.clearOriginalCwd("sess-a");

        assertThat(CwdResolution.getOriginalCwdLayer("sess-a"))
            .as("Exit worktree 后 originalCwdLayer 必须回落 boundProject (INV-3 恢复)")
            .isEqualTo(boundProject.toRealPath().toString())
            .isNotEqualTo(worktreePath.toRealPath().toString());
    }

    @Test
    @DisplayName("normalizeCwd: realpath + NFC 归一化，不抛异常")
    void normalizeCwd_realpathAndNfcSafe(@TempDir Path dir) throws Exception {
        // WHY: 对齐 CC setCwdState NFC + Shell.ts realpathSync，避免符号链接/Unicode 假阳性。目录被删不抛（catch 兜底）。
        String normalized = CwdResolution.normalizeCwd(dir.toString());
        assertThat(normalized).isEqualTo(dir.toRealPath().toString());

        // 不存在路径不抛异常，回原值 + NFC
        String ghost = CwdResolution.normalizeCwd("/nonexistent/ghost/path");
        assertThat(ghost).isNotNull();
    }

    @Test
    @DisplayName("safeGet: 会话层 MISS 但无 sessionId（null/空白）→ 按无会话解析，不抛")
    void safeGet_nullSessionGoesToNonSessionExit() throws Exception {
        // WHY: [批 4a #13] null/空白 sessionId 是<b>兼容路由</b>（130 个既有调用点行为零变化）：
        //   它等价于显式的无会话出口，而不是「有会话却解析不出」⇒ 不抛、返回 override ?? user.dir。
        //   ⛔ 不得把 null 路由改成抛异常：会把启动期 bean / MCP transport 一并打死
        //   （须先给那些调用点补会话通道，见批 4a 报告未决项）。
        String expected = Path.of(System.getProperty("user.dir")).toRealPath().toString();
        assertThat(CwdResolution.getCwd(null)).isEqualTo(expected);
        assertThat(CwdResolution.getCwd("   ")).isEqualTo(expected);
        assertThat(CwdResolution.getOriginalCwdLayer(null)).isEqualTo(expected);
        assertThat(CwdResolution.getOriginalCwdLayer("   ")).isEqualTo(expected);
    }
}

package com.nexusai.application.agent.agent;

import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>场景对应（[S2 · F-07 2026-09-14] 原「场景① override 非空 → override」已随 override 通道按用户
 * 裁定 #8 删除而**整段移除** —— 该层不再存在，无替代可锚；方法名保留历史序号以对齐 git 历史）：
 * <ol>
 *   <li>场景②：sessionCwd 非空 → sessionCwd</li>
 *   <li>场景③：+ boundProject 非空 → boundProject（sessionCwd 空时）</li>
 *   <li>场景④：DB 无此会话 ⇒ 无会话出口（[S2 F-09/F-20] 语义已按用户裁定变更）</li>
 *   <li><b>活跃 worktree 内 cd 后 getCwd 返回 cd 子目录</b>（合并存储，WorktreeCwdTracker 不作优先层，INV-2）</li>
 * </ol>
 */
@DisplayName("[CC-CWD-01/02/04] CwdResolution 分层 getCwd + originalCwd 层")
class CwdResolutionTest {

    /**
     * [fix-junit 2026-09-14] <b>本类自行拥有 static 回源槽，不依赖任何全局默认。</b>
     *
     * <p><b>WHY</b>：本类是「回源四态（bound / unbound / unknown / resolutionFailure）」的守护者，其中
     * {@link #scenario4_unwiredResolverFailsLoudInsteadOfNonSessionExit} 断言的是<b>「未接线」这一态本身</b>
     * ⇒ 绝不能让「环境恰好没装解析器」这种偶然来充当装置。
     *
     * <p><b>与全局默认的执行顺序</b>：JUnit 的 {@code BeforeEachCallback}（测试期全局默认
     * {@code NoDatabaseSessionProjectRootExtension} 会装一个答 {@code unknown()} 的解析器）<b>先于</b>
     * 本方法执行 ⇒ 本方法显式 {@code setDbResolver(null)} <b>覆盖</b>它，把本类置回「未接线」态。
     * ⛔ 这不是「削弱全局默认」，而是把「本类要测的那一态」写成<b>显式、可读、不依赖环境</b>的装置
     * （原注释「本类 @AfterEach 恒 setDbResolver(null) ⇒ 本用例运行在未接线态」只在「本类第一个用例」
     * 成立，属顺序依赖；本方法消除该依赖）。
     *
     * <p>⚠️ 本类其余用例全部自行显式装置解析器（{@code setDbResolver}）或只用已绑定会话
     * （{@code setForSession} ⇒ 冻结表命中，不回源）⇒ 本方法对它们无行为影响。
     */
    @BeforeEach
    void ownResolverSlot_unwiredByDefault() {
        SessionProjectRoot.setDbResolver(null);
    }

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        // 回源解析器是 static 注入口 → 用例必须自行注销，否则跨类污染（本类内用 try/finally 亦可）。
        SessionProjectRoot.setDbResolver(null);
    }

    // [S2 · F-07 2026-09-14] 原「场景① override 非空 → 返回 override」（scenario1_overrideWins ×
    //   CwdResolution.runWithCwdOverride）已**整段删除**：被测机制 = CwdResolution.CURRENT_OVERRIDE
    //   （ThreadLocal + runWithCwdOverride/setCurrentOverride/clearCurrentOverride），已按用户裁定 #8
    //   **整条删除**（生产 0 写入点 ⇒ 死管线；CC 的 cwdOverrideStorage 在本仓由 TUC.effectiveCwd 承接）。
    //   ⛔ 这是「机制消失 ⇒ 无替代可锚」的删除，**不是**「用例被弱化」；不得用其它层伪造「override 仍生效」。

    @Test
    @DisplayName("场景②: sessionCwd 非空 → 返回 sessionCwd (对齐 CC STATE.cwd；[S2 F-07] override 层已删)")
    void scenario2_sessionCwdWinsWhenNoOverride(@TempDir Path sessionDir) throws Exception {
        // WHY: CC pwd() 回 getCwdState()=STATE.cwd。worktree 入口与 cd 共用此层 [Fix-R1]。
        SessionProjectRoot.setForSession("sess-a", "/some/bound-project");
        SessionCwdHolder.set("sess-a", sessionDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("sessionCwd 必须压过 boundProject")
            .isEqualTo(sessionDir.toRealPath().toString());
    }

    @Test
    @DisplayName("场景③: sessionCwd 空 + boundProject 非空 → 返回 boundProject (D-1: getForSession)")
    void scenario3_boundProjectWhenNoSessionCwd(@TempDir Path projectDir) throws Exception {
        // WHY: boundProject 层对齐 CC originalCwd（启动目录）。D-1 裁决：只读 getForSession，不读 resolve()
        // （resolve 回落 env/config home 属身份域，会使 user.dir 成死代码 + 身份域泄入工作目录域）。
        SessionProjectRoot.setForSession("sess-a", projectDir.toString());

        String result = CwdResolution.getCwd("sess-a");

        assertThat(result)
            .as("boundProject 必须是 getForSession 的绑定值，不得读 resolve() 回落链")
            .isEqualTo(projectDir.toRealPath().toString());
    }

    /**
     * ⚠️⚠️ <b>[S2 · F-09/F-20 2026-09-14] 本用例<b>反转</b>批 4a 用户已裁定的场景④ —— 必须显式记录</b>。
     *
     * <p><b>反转的是哪条</b>：批 4a 裁定「<b>无解析器</b> / DB 无此会话 ⇒ 不抛，走无会话出口（进程
     * user.dir）」。本批按用户裁定（F-09/F-20「新增解析失败态，仍 fail-loud」）把其中
     * 「<b>解析器未接线</b>」这一半改为 <b>fail-loud</b>。
     *
     * <p><b>为什么推翻</b>：批 4a 把「未接线」与「DB 明确答无此会话」焊死成同一个 {@code unknown()}。
     * 但「未接线」是<b>装配异常</b>（{@code setDbResolver} 未被调用 = 本该有却没有），把它当选票投给
     * 「确无会话」的直接后果是：用户裁定 #7 的 fail-loud 会因装配异常而<b>全进程静默失效且零日志</b>
     * （原实现连 DEBUG 都没有）。这正是 G2 组要治的失败模式，也是用户裁定「不许静默失效」的落点。
     *
     * <p><b>旧行为</b>：{@code getCwd("sess-not-in-db")} 返回进程 user.dir（realpath+NFC），不抛。
     * <br><b>新行为</b>：抛 {@link IllegalStateException}，message 含 sessionId 且含「无法判定」
     * （与「会话存在但未绑定」的文案<b>可辨识</b>，用户裁定 F-09 的「保留可辨识语义与纠错文案」）。
     *
     * <p><b>生产影响 = 零</b>：{@code ToolRegistrationConfig:1227} 已接线（{@code isDbResolverWired()}
     * 见 {@link SessionProjectRoot}），故「未接线」分支在生产不可达 —— 只在纯 JUnit / 装配故障时暴露。
     *
     * <p><b>正向对照</b>：本用例下半段接线后断言同一 id 仍走「确无会话」出口 ⇒ 证明红的是
     * 「未接线」这一特定态，不是「解析整体坏了」。⛔ 「DB 明确答无此会话」（{@code Lookup.unknown()}）
     * 仍<b>不抛</b>，批 4a 该半条裁定不变（见 {@link #noSessionSentinel_goesToNamedNonSessionExit}）。
     */
    @Test
    @DisplayName("场景④ [S2 F-09/F-20 反转批 4a 的一侧] 解析器未接线 ⇒ 按「解析失败」fail-loud（不再当「确无会话」）")
    void scenario4_unwiredResolverFailsLoudInsteadOfNonSessionExit() throws Exception {
        // 前置：本类的 @BeforeEach（ownResolverSlot_unwiredByDefault）显式清空回源槽 ⇒ 本用例运行在
        //   「未接线」态。⛔ 该断言同时是「全局默认扩展的执行顺序」的实测装置：全局默认的
        //   BeforeEachCallback 先装、本类的 @BeforeEach 后清；若顺序反过来，此处立刻变红。
        assertThat(SessionProjectRoot.isDbResolverWired())
            .as("前置装置：本用例必须运行在「回源解析器未接线」态，否则测的不是本态")
            .isFalse();

        assertThatThrownBy(() -> CwdResolution.getCwd("sess-not-in-db"))
            .as("解析器未接线 = 无法判定 ⇒ 必须 fail-loud（⛔ 不得回落进程 user.dir 冒充项目根）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sess-not-in-db")
            .hasMessageContaining("无法判定");
        assertThatThrownBy(() -> CwdResolution.getOriginalCwdLayer("sess-not-in-db"))
            .as("getOriginalCwdLayer 同判据（同一 fail-loud 出口）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法判定");

        // 正向对照：接线 + 「本环境确无会话」⇒ 同一个 id 走命名出口（⛔ 注意：步骤 2 起这里
        //   **必须**是 sessionless —— DB 明确答「无此会话」已改为 fail-loud 抛，见
        //   {@link #dbAnsweredNoSuchSession_failsLoud}）。
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        try {
            assertThat(CwdResolution.getCwd("sess-not-in-db"))
                .as("接线 + 本环境确无会话（sessionless）⇒ 无会话出口（进程 user.dir），不抛")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
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
    @DisplayName("[批 4a #13] 无会话出口 = 进程 user.dir（只对确无会话开放；[S2 F-07] override 层已删）")
    void nonSessionExit_resolvesProcessUserDir(@TempDir Path projectDir) throws Exception {
        // WHY（规则九 · 用户裁定 #13）：无会话路径必须<b>命名自解释</b>且只对「确无会话」开放；
        //   它不得偷看会话层，否则「确无会话」与「漏传 sessionId」再次混为一谈。
        // [S2 · F-07 2026-09-14] 原装置还断言「无会话出口仍认显式 override 层」
        //   （runWithCwdOverride + cwdOverrideStorage 对齐）。override 通道已按用户裁定 #8 整条删除
        //   ⇒ 该断言**整段删除**（机制消失，无替代可锚），出口现恒等于 normalizeCwd(进程 user.dir)。
        String expected = Path.of(System.getProperty("user.dir")).toRealPath().toString();
        assertThat(CwdResolution.getCwdForNonSession()).isEqualTo(expected);
        assertThat(CwdResolution.getOriginalCwdLayerForNonSession()).isEqualTo(expected);

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

    /**
     * [S2 · F-09 验证 #1/#3] 回源 DB <b>炸了</b> ⇒ 第 4 态「解析失败」⇒ cwd 域 fail-loud（⛔ 不回落 user.dir）。
     *
     * <p><b>WHY（规则九 · 意图）</b>：dbResolver 抛错 = 解析本身失败，与「DB 明确答无此会话」是两件事。
     * 原实现把两者焊死（catch → unknown → 走无会话出口返回进程 user.dir）⇒ 用户裁定 #7 的 fail-loud
     * 被整体旁路，且**零日志**（catch 里连 WARN 都没有）。
     *
     * <p><b>RED（反向实验 · 有鉴别力）</b>：把 {@code refillFromDb} 的 catch 分支改回
     * {@code return Lookup.unknown()} ⇒ 本用例红（getCwd 会返回 user.dir 而不是抛）。
     *
     * <p><b>正反对照（同一用例两臂）</b>：同一时刻把解析器换成「答无此会话」⇒ 同一 id 走无会话出口
     * 且不抛 —— 证明红的是「DB 抛错」这一态，不是「解析整体坏了」。
     */
    @Test
    @DisplayName("[S2 F-09] 回源 DB 抛错 ⇒ 解析失败态 ⇒ getCwd 抛（⛔ 不回落 user.dir）")
    void dbResolverThrowing_failsLoudInsteadOfUserDir() throws Exception {
        SessionProjectRoot.setDbResolver(sid -> {
            throw new RuntimeException("db down");
        });
        try {
            assertThatThrownBy(() -> CwdResolution.getCwd("sess-db-down"))
                .as("DB 回源抛错 = 无法判定 ⇒ 必须 fail-loud（原实现静默返回进程 user.dir）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-db-down")
                .hasMessageContaining("无法判定");
            assertThatThrownBy(() -> CwdResolution.getOriginalCwdLayer("sess-db-down"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法判定");
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }

        // 正向对照：同一 id，接线正常 + 「本环境确无会话」⇒ 无会话出口，不抛
        //   （⛔ 步骤 2 起必须用 sessionless：`unknown` = DB 明确答无此会话 ⇒ fail-loud，见
        //   {@link #dbAnsweredNoSuchSession_failsLoud}）
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        try {
            assertThat(CwdResolution.getCwd("sess-db-down"))
                .as("接线正常且本环境确无会话 ⇒ 无会话出口（进程 user.dir），不抛")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [cwd3 步骤 2 · RE-2a-1] <b>DB 明确答「无此会话」⇒ fail-loud 抛</b>（⛔ 不再回落进程 user.dir）。
     *
     * <p><b>WHY（规则九 · 意图）</b>：{@code unknown} 现在只剩「查了 DB、DB 说没有这一行」一个含义
     * ⇒ 属数据链路异常（会话已删 / id 来源不明）。回落 {@code user.dir} 会把工具/权限/transcript
     * 全锚到后端启动目录 —— 本仓已因此造成过真实误删。确无会话的调用方必须显式传
     * {@code SessionKeys.NO_SESSION} 哨兵或走命名出口。
     *
     * <p><b>RED（反向实验）</b>：把 {@code CwdResolution.getCwd} 的 unknown 分支改回
     * {@code warnUnknownSession(...); return getCwdForNonSession();} ⇒ 本用例红（返回 user.dir 而不抛）。
     *
     * <p><b>正反对照（同一用例两臂）</b>：{@code unknown} ⇒ 抛；同一刻 {@code sessionless} ⇒ 走
     * 无会话出口且不抛 —— 证明红的是「DB 答了没有」这一态，不是「解析整体坏了」。
     */
    @Test
    @DisplayName("[cwd3 步骤2 RE-2a-1] DB 明确答「无此会话」⇒ fail-loud 抛（⛔ 不回落 user.dir）")
    void dbAnsweredNoSuchSession_failsLoud() throws Exception {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        try {
            assertThatThrownBy(() -> CwdResolution.getCwd("sess-ghost-cwd3"))
                .as("DB 明确答无此会话 = 数据链路异常 ⇒ 必须抛（原实现静默回落进程 user.dir）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-ghost-cwd3")
                .hasMessageContaining("DB 明确答「无此会话」");
            assertThatThrownBy(() -> CwdResolution.getOriginalCwdLayer("sess-ghost-cwd3"))
                .as("getOriginalCwdLayer 同判据（同一 flip）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 明确答「无此会话」");
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }

        // 正向对照：同一 id，同一刻换成 sessionless ⇒ 无会话出口，不抛
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        try {
            assertThat(CwdResolution.getCwd("sess-ghost-cwd3"))
                .as("sessionless（本环境确无会话）⇒ 命名出口，不抛 —— 证明红的是 unknown 这一态")
                .isEqualTo(Path.of(System.getProperty("user.dir")).toRealPath().toString());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [S2 · F-09 验证 #2 · 反向实验] 两处 catch 必须**同时**变异才应全红（依铁律「冗余守卫多点变异」）：
     * 本用例只守「CwdResolution 侧收窄后的 safeLookup」这一半，{@code refillFromDb} 那一半由
     * {@link #dbResolverThrowing_failsLoudInsteadOfUserDir} 守 —— 两半独立、各自可分辨。
     *
     * <p>装置：把 {@code SessionProjectRoot.lookup} 换成会抛的桩（直接覆盖 static 槽不可能），故本用例
     * 改为断言「解析器自身返回第 4 态」也被 cwd 域同样处理（不因「解析器自己说失败」而降级成 unknown）。
     */
    @Test
    @DisplayName("[S2 F-09] 解析器自行判定「解析失败」⇒ cwd 域同样 fail-loud（不降级成 unknown）")
    void resolverSelfReportedFailure_failsLoud() {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.resolutionFailure());
        try {
            assertThatThrownBy(() -> CwdResolution.getCwd("sess-self-failed"))
                .as("解析器自报「无法判定」⇒ 必须原样上浮到 cwd 域 fail-loud")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法判定");
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [批 6] 显式「确无会话」哨兵（{@code SessionKeys.NO_SESSION}）⇒ 命名无会话出口，不查 DB、不抛。
     *
     * <p><b>WHY（规则九）</b>：哨兵是「有意声明确无会话」（MCP 入站 / standalone fork·子代理 /
     * 无会话 plan provider），与「DB 查无此会话」的 {@code unknown} 分支**语义不同**（后者是
     * 来源不明信号）。若哨兵落进 {@code unknown} 分支 ⇒ 打误导性「伪造 id」告警 + 多一次 DB 查询。
     *
     * <p><b>正反对照（同一测试内两臂）</b>：哨兵 ⇒ 无会话出口；同一时刻真实绑定会话 ⇒ 返回其
     * boundProject（证明哨兵臂不是「解析整体坏了」而绿）。
     */
    @Test
    @DisplayName("[批 6] NO_SESSION 哨兵 → 命名无会话出口（不抛）；真实绑定会话 → boundProject（正反对照）")
    void noSessionSentinel_goesToNamedNonSessionExit(@TempDir Path projectDir) throws Exception {
        SessionProjectRoot.setForSession("sess-real-b6", projectDir.toString());

        String expectedUserDir = Path.of(System.getProperty("user.dir")).toRealPath().toString();
        // 负向臂：哨兵 ⇒ 无会话出口（= getCwdForNonSession()），且**不**命中会话绑定
        assertThat(CwdResolution.getCwd(com.nexusai.common.SessionKeys.NO_SESSION))
            .as("哨兵必须走命名无会话出口（进程 user.dir），而不是会话绑定")
            .isEqualTo(expectedUserDir)
            .isEqualTo(CwdResolution.getCwdForNonSession());
        assertThat(CwdResolution.getOriginalCwdLayer(com.nexusai.common.SessionKeys.NO_SESSION))
            .isEqualTo(CwdResolution.getOriginalCwdLayerForNonSession());

        // 正向对照：真实绑定会话照常解析出 boundProject（证明上方不是「解析整体坏了」）
        assertThat(CwdResolution.getCwd("sess-real-b6"))
            .as("真实绑定会话仍按其 boundProject 解析")
            .isEqualTo(projectDir.toRealPath().toString());
    }

    /**
     * [批 6] 哨兵必须**短路**，不得落进「DB 查无此会话」分支（否则每次 MCP 调用白查一次 DB
     * 且打误导性「伪造 id」告警）。
     *
     * <p>⚠️ <b>本用例是「零鉴别力」修复的产物</b>：哨兵分支与 {@code unknown} 分支<b>返回值相同</b>
     * （都是 {@code getCwdForNonSession()}）⇒ 只断言返回值<b>永远绿</b>。真正的鉴别点是「是否查询了
     * DB」——故经 {@link SessionProjectRoot#setDbResolver} 计数。
     *
     * <p><b>正反对照（同一测试内两臂）</b>：哨兵 ⇒ 回源计数 0；非哨兵的来源不明 id ⇒ 回源计数 &gt; 0。
     * <br><b>RED</b>：删掉 {@code getCwd} 里的 {@code SessionKeys.isNoSession} 短路 ⇒ 哨兵臂计数变 1 ⇒ 红。
     */
    @Test
    @DisplayName("[批 6] 哨兵短路：不查 DB（计数 0）；来源不明 id 则回源（正反对照）")
    void noSessionSentinel_shortCircuitsWithoutDbLookup() {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            calls.incrementAndGet();
            // [cwd3 步骤 2] 这里必须是 **sessionless**（不是 unknown）：unknown 自步骤 2 起
            //   fail-loud 抛，会打断「计数」这一被测点本身（本用例守的是「是否查了 DB」，不是返回值）。
            return SessionProjectRoot.Lookup.sessionlessEnvironment();
        });
        try {
            CwdResolution.getCwd(com.nexusai.common.SessionKeys.NO_SESSION);
            assertThat(calls.get())
                .as("哨兵是「有意声明无会话」⇒ 不得触发 DB 回源（0 次）")
                .isZero();

            // 正向对照：非哨兵 id 必回源（证明计数装置有效，非恒 0）
            CwdResolution.getCwd("sess-not-in-db-b6");
            assertThat(calls.get())
                .as("非哨兵的 DB-miss id 必须回源（证明上面的 0 不是「解析器没生效」）")
                .isGreaterThan(0);
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [cwd3 步骤 2 · S2.6] <b>三个</b>入口的 ≥WARN 闸必须<b>各自独立</b>（[批 P15b] 由两入口扩到三入口）。
     *
     * <p><b>WHY（规则九 · 意图）</b>：原实现两个入口共用<b>同一个</b>一次性 AtomicBoolean ⇒
     * 可见度上限 1 行日志/进程：先触发的入口会把另一入口的告警吃掉，后者永远不打印
     * （「静默失效」的一种）。判据 = 让 {@link CwdResolution#getCwd(String)} 先触发 ⇒
     * {@link CwdResolution#getOriginalCwdLayer(String)} /
     * {@link CwdResolution#getProjectRoot(String)} <b>仍须各有一条自己的 WARN</b>。
     *
     * <p><b>[批 P15b] 为什么必须补第三个入口</b>：{@code getProjectRoot} 的入口专属闸
     * （{@code SESSIONLESS_WARNED_GET_PROJECT_ROOT}，批 P10a 新增）在补本臂之前<b>零测试引用</b>
     * —— 它被删掉、被合并、被写成恒 {@code true} 都不会有任何用例变红（本仓反复栽的
     * 「以为守住了、其实没守」）。本臂的名字判据是「warn 文案里出现的是<b>哪个入口名</b>」，
     * 故它对「闸对象被换成别人」有鉴别力。
     *
     * <p>RED（反向实验）：① 把三个 {@code SESSIONLESS_WARNED_*} 合并回单个 static 闸 ⇒ 本用例红
     * （第 2/3 个断言的 anyMatch 找不到）；② 把 {@code getProjectRoot} 的 sessionless 分支改成
     * logger 打到别处 / 不调用 {@code warnSessionlessEnvironment} ⇒ 第 3 个断言红。
     *
     * <p>装置说明：用 <b>sessionless</b> 路径（不抛）以便连续调三个入口；若用 {@code unknown}
     * （抛）则后续入口之前的语句就中断了。⚠️ 必须先复位一次性闸 —— 否则先跑的用例用掉闸，
     * 本用例假红（故有 {@code resetWarnGatesForTesting}）。
     */
    @Test
    @DisplayName("[cwd3 S2.6 · P15b 扩三入口] 三入口告警闸独立：getCwd 先触发 ⇒ 另两入口仍各有 WARN")
    void warnGatesAreIndependentPerEntry() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CwdResolution.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        CwdResolution.resetWarnGatesForTesting();
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        try {
            CwdResolution.getCwd("sess-warn-entry-a");
            CwdResolution.getOriginalCwdLayer("sess-warn-entry-b");
            CwdResolution.getProjectRoot("sess-warn-entry-c");

            java.util.List<String> msgs = app.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(msgs)
                .as("getCwd 入口必须有「确无会话」WARN")
                .anyMatch(m -> m.contains("getCwd 判定「本环境确无会话」"));
            assertThat(msgs)
                .as("⭐ 第二个入口必须**仍有**自己的 WARN —— 单闸实现下这一条会被第一条吃掉")
                .anyMatch(m -> m.contains("getOriginalCwdLayer 判定「本环境确无会话」"));
            assertThat(msgs)
                .as("⭐ [P15b] 第三个入口（getProjectRoot）必须**仍有**自己的 WARN —— 补批 P10a "
                    + "SESSIONLESS_WARNED_GET_PROJECT_ROOT 的零测试洞")
                .anyMatch(m -> m.contains("getProjectRoot 判定「本环境确无会话」"));
        } finally {
            logger.detachAppender(app);
            app.stop();
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [批 P15b · 补 P10a 零测试洞] {@code unknown} 态的 <b>三入口</b> ≥WARN 闸必须各自独立。
     *
     * <p><b>WHY（规则九 · 意图）</b>：{@link #warnGatesAreIndependentPerEntry} 走的是 sessionless
     * 路径，只覆盖 {@code SESSIONLESS_WARNED_*} 三闸；而批 P10a 新增的
     * {@code UNKNOWN_SESSION_WARNED_GET_PROJECT_ROOT} 走的是 <b>unknown</b> 路径
     * （{@code warnUnknownSession}）。两者是不同的分支、不同的闸对象 ⇒ 前者绿不能推出后者守得住。
     * 补本臂前，全仓对 {@code UNKNOWN_SESSION_WARNED_GET_PROJECT_ROOT} 的引用<b>只有定义处</b>。
     *
     * <p>判据 = 让 {@code getCwd} 先触发（吃掉自己的闸）⇒ {@code getProjectRoot} <b>仍须有自己的
     * WARN 且文案里点名 getProjectRoot</b>。
     *
     * <p>RED（反向实验）：把 {@code UNKNOWN_SESSION_WARNED_GET_PROJECT_ROOT} 与
     * {@code UNKNOWN_SESSION_WARNED_GET_CWD} 指向同一个 AtomicBoolean ⇒ 本用例红（第三个断言的
     * anyMatch 找不到：getCwd 已把共享闸用掉）。
     */
    @Test
    @DisplayName("[P15b · 补 P10a] unknown 态三入口告警闸独立：getCwd 先触发 ⇒ getProjectRoot 仍有 WARN")
    void unknownWarnGatesAreIndependentPerEntry() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CwdResolution.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        CwdResolution.resetWarnGatesForTesting();
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        try {
            // unknown 态是 fail-loud ⇒ 三个入口都抛；告警在抛之前打（warnUnknownSession 在 throw 前）
            catchThrowable(() -> CwdResolution.getCwd("sess-unk-a"));
            catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-unk-b"));
            catchThrowable(() -> CwdResolution.getProjectRoot("sess-unk-c"));

            java.util.List<String> msgs = app.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(msgs)
                .as("getCwd 入口必须有「DB 中不存在」WARN")
                .anyMatch(m -> m.contains("getCwd 的 sessionId=sess-unk-a 在 DB 中不存在"));
            assertThat(msgs)
                .as("getOriginalCwdLayer 入口必须有自己的 WARN（[cwd3 S2.6] 拆闸）")
                .anyMatch(m -> m.contains("getOriginalCwdLayer 的 sessionId=sess-unk-b 在 DB 中不存在"));
            assertThat(msgs)
                .as("⭐ [P15b] getProjectRoot 入口必须有自己的 WARN —— 补 P10a 那只闸的零测试洞")
                .anyMatch(m -> m.contains("getProjectRoot 的 sessionId=sess-unk-c 在 DB 中不存在"));
        } finally {
            logger.detachAppender(app);
            app.stop();
            CwdResolution.resetWarnGatesForTesting();
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [批 P15b · 补 P10a 零测试洞] {@code getProjectRoot} <b>自己那两只</b>闸必须互异（跨态不互吞）。
     *
     * <p><b>WHY（规则九 · 意图）</b>：批 P10a 给 {@code getProjectRoot} 加了<b>两只</b>一次性闸
     * （{@code UNKNOWN_SESSION_WARNED_GET_PROJECT_ROOT} 与 {@code SESSIONLESS_WARNED_GET_PROJECT_ROOT}），
     * 补本臂前两只都<b>零测试引用</b>。两只闸若被写成同一个对象（或其中一只被删、被替换），
     * 「同一入口先走 A 态、后走 B 态」时后一条告警会被永久吃掉 —— 而
     * {@link #warnGatesAreIndependentPerEntry} 只跑 sessionless、
     * {@link #unknownWarnGatesAreIndependentPerEntry} 只跑 unknown，<b>两条都不会红</b>。
     * 故必须有本臂：把两态<b>依次</b>打在同一个入口上。
     *
     * <p>RED（反向实验 · 派单书 §三 指定）：把
     * {@code SESSIONLESS_WARNED_GET_PROJECT_ROOT} 与 {@code UNKNOWN_SESSION_WARNED_GET_PROJECT_ROOT}
     * 合并为同一个 AtomicBoolean（或让二者的 getProjectRoot 调用点指向同一对象）⇒ 本用例红
     * （第二段断言的 anyMatch 找不到：第一段已把共享闸用掉）。
     */
    @Test
    @DisplayName("[P15b · 补 P10a] getProjectRoot 两条闸互异：先 sessionless 后 unknown，两条告警都在")
    void projectRootWarnGatesAreIndependentPerState() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CwdResolution.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        CwdResolution.resetWarnGatesForTesting();
        try {
            // 第一段：sessionless 态 ⇒ 消耗 SESSIONLESS_WARNED_GET_PROJECT_ROOT
            SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
            CwdResolution.getProjectRoot("sess-pr-gate-1");
            // 第二段：换成 unknown 态 ⇒ 必须仍能打出自己那条（证明与上一只隔开）
            SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
            catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-gate-2"));

            java.util.List<String> msgs = app.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(msgs)
                .as("sessionless 态告警（第一段）必须在")
                .anyMatch(m -> m.contains("getProjectRoot 判定「本环境确无会话」"));
            assertThat(msgs)
                .as("⭐ unknown 态告警（第二段）必须**仍在** —— 两闸若合并，这条会被第一段吃掉")
                .anyMatch(m -> m.contains("getProjectRoot 的 sessionId=sess-pr-gate-2 在 DB 中不存在"));
        } finally {
            logger.detachAppender(app);
            app.stop();
            CwdResolution.resetWarnGatesForTesting();
            SessionProjectRoot.setDbResolver(null);
        }
    }

    // ==================================================================================
    // [批 P15b] getProjectRoot 六臂矩阵
    //
    // WHY（规则九 · 意图）：补臂前实测 —— 全仓对 CwdResolution.getProjectRoot( 的直接调用 = 0，
    //   其四个非命中臂（unbound / unknown / resolutionFailure / sessionless）一个测试都没有；
    //   loader 侧（ProjectSettingsLoader:205-209）在 null/哨兵臂就短路了 ⇒ 永远不会抵达本入口的
    //   sessionless 分支。这正是「以为守住了、其实没守」的形态。
    //
    // 每臂的判据不只是「结果对不对」，还包括「与另两入口 getCwd / getOriginalCwdLayer 是否同构」
    //   —— 后者是派单书 §四 的停下条件（不一致 ⇒ 说明三段合并会把行为差异吃进抽象里）。
    //   ⚠️ 因此本组用例是**带鉴别力的合并前置**，不是重复断言。
    //
    // ⚠️ 每条断言同粒度口径（与既有用例一致，均用 hasMessageContaining 级别的包含断言），
    //   但额外加了「三入口 message 逐字相等」这一条 —— 既有用例全仓无一条全文相等断言，
    //   故文案漂移在它们眼里不可见；本组补上该可见度（详见交付报告「局限」段）。
    // ==================================================================================

    /**
     * [P15b 臂 1 · bound] 命中绑定 ⇒ 返回该目录（normalizeCwd realpath+NFC），且与该夹具下另两入口同值。
     *
     * <p>夹具 = {@code setForSession}（冻结表命中 ⇒ 不回源），sessionCwd / originalCwd 两槽皆空
     * ⇒ 三入口都只落到 boundProject 层，必须同值。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 里 {@code return normalizeCwd(boundProject)} 改成
     * {@code return getOriginalCwdLayerForNonSession()} ⇒ 本用例红（返回进程 user.dir 而非临时目录）。
     */
    @Test
    @DisplayName("[P15b 臂1 bound] getProjectRoot 命中绑定 ⇒ 与另两入口同值")
    void projectRoot_armBound_sameValueAsOtherTwoEntries(@TempDir Path projectDir) throws Exception {
        SessionProjectRoot.setForSession("sess-pr-bound", projectDir.toString());
        String expected = projectDir.toRealPath().toString();

        String fromProjectRoot = CwdResolution.getProjectRoot("sess-pr-bound");

        assertThat(fromProjectRoot)
            .as("命中绑定 ⇒ 归一化后的绑定目录（经 normalizeCwd realpath+NFC）")
            .isEqualTo(expected);
        assertThat(fromProjectRoot)
            .as("⭐ 三入口同夹具必须同值 —— 不一致即派单书 §四 停下条件")
            .isEqualTo(CwdResolution.getCwd("sess-pr-bound"))
            .isEqualTo(CwdResolution.getOriginalCwdLayer("sess-pr-bound"));
    }

    /**
     * [P15b 臂 2 · unbound] 会话存在（DB 有行）却无绑定 ⇒ <b>抛</b>，文案含 sessionId，且与另两入口逐字相同。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 的 {@code if (bound.sessionKnown()) throw ...} 改成
     * {@code return getOriginalCwdLayerForNonSession()} ⇒ 本用例红（不抛）。
     */
    @Test
    @DisplayName("[P15b 臂2 unbound] getProjectRoot ⇒ 抛，文案与另两入口逐字相同")
    void projectRoot_armUnbound_throwsSameMessageAsOtherTwoEntries() {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unbound());
        try {
            Throwable pr = catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-unbound"));
            Throwable cwd = catchThrowable(() -> CwdResolution.getCwd("sess-pr-unbound"));
            Throwable orig = catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-pr-unbound"));

            assertThat(pr)
                .as("会话存在却无绑定 = 数据链路异常 ⇒ 必须 fail-loud（⛔ 不回落 user.dir）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-pr-unbound");
            assertThat(pr.getMessage())
                .as("⭐ 三入口同夹具必须同文案（逐字）—— 不一致即派单书 §四 停下条件")
                .isEqualTo(cwd.getMessage())
                .isEqualTo(orig.getMessage());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [P15b 臂 3 · unknown] DB 明确答「无此会话」⇒ <b>抛</b>，文案含「DB 明确答「无此会话」」+ 哨兵提示，
     * 且与另两入口逐字相同。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 的 {@code warnUnknownSession(...); throw ...} 改回
     * {@code return getOriginalCwdLayerForNonSession()} ⇒ 本用例红（不抛）。
     */
    @Test
    @DisplayName("[P15b 臂3 unknown] getProjectRoot ⇒ 抛，文案含哨兵提示且与另两入口逐字相同")
    void projectRoot_armUnknown_throwsSameMessageAsOtherTwoEntries() {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        try {
            Throwable pr = catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-unknown"));
            Throwable cwd = catchThrowable(() -> CwdResolution.getCwd("sess-pr-unknown"));
            Throwable orig = catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-pr-unknown"));

            assertThat(pr)
                .as("DB 明确答无此会话 = 数据链路异常 ⇒ 必须抛")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-pr-unknown")
                .hasMessageContaining("DB 明确答「无此会话」")
                .hasMessageContaining("SessionKeys.");
            assertThat(pr.getMessage())
                .as("⭐ 三入口同夹具必须同文案（逐字）—— 不一致即派单书 §四 停下条件")
                .isEqualTo(cwd.getMessage())
                .isEqualTo(orig.getMessage());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [P15b 臂 4 · resolutionFailure（未接线）] {@code setDbResolver(null)} ⇒ <b>抛</b>，文案含「无法判定」，
     * 且与另两入口逐字相同。
     *
     * <p>装置来自本类 {@code @BeforeEach}（显式 {@code setDbResolver(null)}），此处<b>再断言一次前置态</b>
     * —— 否则「环境恰好没装解析器」会冒充本臂装置（既有 {@code scenario4} 同款做法）。
     */
    @Test
    @DisplayName("[P15b 臂4 resolutionFailure(未接线)] getProjectRoot ⇒ 抛「无法判定」，与另两入口逐字相同")
    void projectRoot_armResolutionFailureUnwired_throwsSameMessageAsOtherTwoEntries() {
        assertThat(SessionProjectRoot.isDbResolverWired())
            .as("前置装置：本用例必须运行在「回源解析器未接线」态，否则测的不是本臂")
            .isFalse();

        Throwable pr = catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-unwired"));
        Throwable cwd = catchThrowable(() -> CwdResolution.getCwd("sess-pr-unwired"));
        Throwable orig = catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-pr-unwired"));

        assertThat(pr)
            .as("未接线 = 装配异常 = 无法判定 ⇒ 必须 fail-loud")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sess-pr-unwired")
            .hasMessageContaining("无法判定");
        assertThat(pr.getMessage())
            .as("⭐ 三入口同夹具必须同文案（逐字）—— 不一致即派单书 §四 停下条件")
            .isEqualTo(cwd.getMessage())
            .isEqualTo(orig.getMessage());
    }

    /**
     * [P15b 臂 5 · resolutionFailure（回源抛错）] 解析器抛异常 ⇒ <b>抛</b>，与臂 4 同文案，
     * 且与另两入口逐字相同。
     *
     * <p><b>RED</b>：删掉 {@code getProjectRoot} 的 {@code if (bound.resolutionFailed()) throw ...}
     * 整段 ⇒ 本用例红（落到 sessionKnown/sessionless/unknown 都不成立 ⇒ 最终走 unknown 分支抛出的文案
     * 变成「DB 明确答「无此会话」」⇒ {@code hasMessageContaining("无法判定")} 红）。
     */
    @Test
    @DisplayName("[P15b 臂5 resolutionFailure(回源抛错)] getProjectRoot ⇒ 抛「无法判定」，与另两入口逐字相同")
    void projectRoot_armResolutionFailureResolverThrows_throwsSameMessageAsOtherTwoEntries() {
        SessionProjectRoot.setDbResolver(sid -> {
            throw new RuntimeException("db down");
        });
        try {
            Throwable pr = catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-dbdown"));
            Throwable cwd = catchThrowable(() -> CwdResolution.getCwd("sess-pr-dbdown"));
            Throwable orig = catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-pr-dbdown"));

            assertThat(pr)
                .as("回源抛错 = 无法判定 ⇒ 必须 fail-loud（⛔ 不得当成「无此会话」）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sess-pr-dbdown")
                .hasMessageContaining("无法判定");
            assertThat(pr.getMessage())
                .as("⭐ 三入口同夹具必须同文案（逐字）—— 不一致即派单书 §四 停下条件")
                .isEqualTo(cwd.getMessage())
                .isEqualTo(orig.getMessage());
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [P15b 臂 6a · sessionless（null / 空白 / NO_SESSION 哨兵）] 不装绑定 ⇒
     * <b>== {@code getOriginalCwdLayerForNonSession()}</b>，且与该夹具下另两入口同值。
     *
     * <p>三种夹具都走 {@code getProjectRoot} 的**前两个 if**（null/空白 → warnNullSession；
     * 哨兵 → warnNoSessionSentinel），都不查 DB。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 的 sessionless 分支 {@code return
     * getOriginalCwdLayerForNonSession()} 改成返回别的值（如 {@code normalizeCwd("/")}）⇒ 本用例红。
     */
    @Test
    @DisplayName("[P15b 臂6a sessionless null/空白/哨兵] getProjectRoot == getOriginalCwdLayerForNonSession()")
    void projectRoot_armSessionlessByNullBlankSentinel_matchesNonSessionExit() {
        String expected = CwdResolution.getOriginalCwdLayerForNonSession();

        assertThat(CwdResolution.getProjectRoot(null))
            .as("null sessionId ⇒ 无会话命名出口")
            .isEqualTo(expected);
        assertThat(CwdResolution.getProjectRoot("   "))
            .as("空白 sessionId ⇒ 无会话命名出口")
            .isEqualTo(expected);
        assertThat(CwdResolution.getProjectRoot(com.nexusai.common.SessionKeys.NO_SESSION))
            .as("显式「确无会话」哨兵 ⇒ 无会话命名出口")
            .isEqualTo(expected);

        // ⭐ 三入口同夹具同值（另两入口的 null 路由走各自命名出口，但值同源 = normalizeCwd(user.dir)）
        assertThat(CwdResolution.getProjectRoot(null))
            .as("⭐ 三入口同夹具必须同值 —— 不一致即派单书 §四 停下条件")
            .isEqualTo(CwdResolution.getCwd(null))
            .isEqualTo(CwdResolution.getOriginalCwdLayer(null));
        assertThat(CwdResolution.getProjectRoot("   "))
            .isEqualTo(CwdResolution.getCwd("   "))
            .isEqualTo(CwdResolution.getOriginalCwdLayer("   "));
        assertThat(CwdResolution.getProjectRoot(com.nexusai.common.SessionKeys.NO_SESSION))
            .isEqualTo(CwdResolution.getCwd(com.nexusai.common.SessionKeys.NO_SESSION))
            .isEqualTo(CwdResolution.getOriginalCwdLayer(com.nexusai.common.SessionKeys.NO_SESSION));
    }

    /**
     * [P15b 臂 6b · sessionless（DB 答 sessionless）] 解析器答「本环境确无会话」⇒
     * <b>== {@code getOriginalCwdLayerForNonSession()}</b>，且与另两入口同值。
     *
     * <p>本臂是唯一「经回源链路抵达 sessionless」的形态（6a 是入口短路）。补臂前无任何用例
     * 从 {@code getProjectRoot} 打到这一分支。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 的 {@code if (bound.sessionless()) return ...}
     * 整段删掉 ⇒ 本用例红（落到 unknown 分支 ⇒ 抛，而非返回值）。
     */
    @Test
    @DisplayName("[P15b 臂6b sessionless via DB] getProjectRoot == getOriginalCwdLayerForNonSession()")
    void projectRoot_armSessionlessFromDb_matchesNonSessionExit() {
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.sessionlessEnvironment());
        try {
            String expected = CwdResolution.getOriginalCwdLayerForNonSession();
            String fromProjectRoot = CwdResolution.getProjectRoot("sess-pr-sessionless");

            assertThat(fromProjectRoot)
                .as("DB 答 sessionless（本环境确无会话）⇒ 命名出口，不抛")
                .isEqualTo(expected);
            assertThat(fromProjectRoot)
                .as("⭐ 三入口同夹具必须同值 —— 不一致即派单书 §四 停下条件")
                .isEqualTo(CwdResolution.getCwd("sess-pr-sessionless"))
                .isEqualTo(CwdResolution.getOriginalCwdLayer("sess-pr-sessionless"));
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }

    /**
     * [P15b 臂 8 · bound 但绑定失效] 绑定目录在<b>绑定之后被删掉</b> ⇒ <b>抛</b>「boundProject 无效」，
     * 且与另两入口逐字相同。
     *
     * <p><b>WHY（规则九）</b>：这是 {@code getProjectRoot} 最后一个此前<b>零覆盖</b>的分支
     * （其余 7 个分支由臂 1-6 覆盖）。语义 = 2026-08-24「cwd 污染修复」：绑定失效（目录被删/移走）
     * 时⛔ 不得把无效路径交给工具（Bash/Glob/Read 会全失败），必须 fail-loud。
     *
     * <p>⚠️ <b>夹具为什么要「先绑有效目录、再删」</b>（实测得出，⛔ 不是随意选的）：
     * {@code SessionProjectRoot} 的两条写入通道都会把「无效路径」折掉 ——
     * ①{@code setForSession} 自带 {@code isValidProjectRoot} 校验，<b>直接拒绑</b>
     * （实测：喂不存在的路径 ⇒ 打「拒绝绑定无效项目根」WARN 且 {@code BY_SESSION} 无条目）；
     * ②{@code refillFromDb} 对 DB 给的无效路径折成 {@code Lookup.unbound()}（返回 {@code unbound}）。
     * ⇒ 唯一能抵达本分支的形态是<b>绑定当时有效、之后失效</b>（{@code lookup} 命中冻结表时
     * <b>不重新校验</b>）。本用例据此构造，并显式断言该前置态。
     *
     * <p><b>RED</b>：把 {@code getProjectRoot} 的
     * {@code throw unresolvedProjectRoot(sessionId, "boundProject 无效（需绝对路径且目录存在）: " + ...)}
     * 改成 {@code return normalizeCwd(boundProject)} ⇒ 本用例红（不抛，返回无效路径）。
     */
    @Test
    @DisplayName("[P15b 臂8 绑定后失效] getProjectRoot ⇒ 抛「boundProject 无效」，与另两入口逐字相同")
    void projectRoot_armBoundButInvalidDir_throwsSameMessageAsOtherTwoEntries(@TempDir Path doomedDir) throws Exception {
        // ① 绑定当时**有效**（否则 setForSession 会拒绑，测的就不是本分支）
        SessionProjectRoot.setForSession("sess-pr-baddir", doomedDir.toString());
        assertThat(SessionProjectRoot.isValidProjectRoot(doomedDir.toString()))
            .as("前置装置 ①：绑定时该目录必须有效，否则会被 setForSession 拒绑")
            .isTrue();

        // ② 绑定之后失效（目录被删）—— 冻结表仍持有该路径，且 lookup 命中时不重新校验
        java.nio.file.Files.delete(doomedDir);
        assertThat(SessionProjectRoot.isValidProjectRoot(doomedDir.toString()))
            .as("前置装置 ②：删目录后该路径必须已失效，否则测的不是本分支")
            .isFalse();

        Throwable pr = catchThrowable(() -> CwdResolution.getProjectRoot("sess-pr-baddir"));
        Throwable cwd = catchThrowable(() -> CwdResolution.getCwd("sess-pr-baddir"));
        Throwable orig = catchThrowable(() -> CwdResolution.getOriginalCwdLayer("sess-pr-baddir"));

        assertThat(pr)
            .as("绑定失效（目录已删）⇒ 必须 fail-loud，⛔ 不得返回该路径污染工具 cwd")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sess-pr-baddir")
            .hasMessageContaining("boundProject 无效");
        assertThat(pr.getMessage())
            .as("⭐ 三入口同夹具必须同文案（逐字）—— 不一致即派单书 §四 停下条件")
            .isEqualTo(cwd.getMessage())
            .isEqualTo(orig.getMessage());
    }

    /**
     * [P15b 臂 7 · <b>有意偏离（非六臂，不触发 §四 停下条件）</b>] sessionCwd / originalCwd 两槽有值时，
     * {@code getProjectRoot} <b>不受影响</b>。
     *
     * <p><b>WHY（规则九）</b>：这是 {@code getProjectRoot} 存在的<b>唯一理由</b>（类 javadoc：
     * 「不随 mid-session worktree 重锚的项目身份根」；CC {@code state.ts:498-508}）。它<b>有意</b>与另两
     * 入口不同构：{@code getCwd} 的 L1 会被 bash {@code cd} 挪走、{@code getOriginalCwdLayer} 的 L1
     * 会被 {@code EnterWorktreeTool} 重锚。⛔ 本臂断言的是「三入口<b>应当</b>不同」，
     * 与派单书 §四「同夹具下行为不一致 ⇒ 停下」是两件事。
     *
     * <p><b>RED</b>：在 {@code getProjectRoot} 里插入读 {@code SessionCwdHolder.get(sessionId)}
     * 的 L1 层（照抄 {@code getCwd}）⇒ 本用例红。
     */
    @Test
    @DisplayName("[P15b 臂7 有意偏离] getProjectRoot 不被 cd / worktree 重锚（另两入口被挪走）")
    void projectRoot_ignoresSessionCwdAndOriginalCwdSlots(@TempDir Path projectDir,
                                                          @TempDir Path cdDir,
                                                          @TempDir Path worktreePath) throws Exception {
        SessionProjectRoot.setForSession("sess-pr-identity", projectDir.toString());
        // bash cd ⇒ 挪走 getCwd 的 L1（sessionCwd 槽）
        SessionCwdHolder.set("sess-pr-identity", cdDir.toString());
        // EnterWorktreeTool ⇒ 重锚 getOriginalCwdLayer 的 L1（originalCwd 槽）
        SessionCwdHolder.setOriginalCwd("sess-pr-identity", worktreePath.toString());

        assertThat(CwdResolution.getProjectRoot("sess-pr-identity"))
            .as("projectRoot = 项目身份根 ⇒ ⛔ 不被 cd（sessionCwd 槽）挪走")
            .isEqualTo(projectDir.toRealPath().toString())
            .isNotEqualTo(cdDir.toRealPath().toString());
        assertThat(CwdResolution.getProjectRoot("sess-pr-identity"))
            .as("projectRoot = 项目身份根 ⇒ ⛔ 不被 worktree 入口（originalCwd 槽）重锚")
            .isNotEqualTo(worktreePath.toRealPath().toString());

        // 正向对照：同夹具同刻，另两入口**确实**被各自的重锚源挪走（证明本臂不是「槽没生效」而绿）
        assertThat(CwdResolution.getCwd("sess-pr-identity"))
            .as("对照：getCwd 的 L1 已被 cd 覆盖 ⇒ 返回 cd 子目录")
            .isEqualTo(cdDir.toRealPath().toString());
        assertThat(CwdResolution.getOriginalCwdLayer("sess-pr-identity"))
            .as("对照：getOriginalCwdLayer 的 L1 已被 worktree 重锚 ⇒ 返回 worktreePath")
            .isEqualTo(worktreePath.toRealPath().toString());
    }

    /**
     * [批 P5 2026-09-15 · 铁律「不许静默失效」出口 (b)] 无会话命名出口必须 <b>≥WARN</b> 且 <b>warn-once</b>。
     *
     * <p><b>WHY（规则九 · 意图）</b>：{@link CwdResolution#getCwdForNonSession()} /
     * {@link CwdResolution#getOriginalCwdLayerForNonSession()} <b>恒返回进程 {@code user.dir}</b>
     * —— 它<b>不是</b>任何会话的项目根。这是本批「值流轴」的核心：只改读侧答不了「是否真的不再
     * 回落 user.dir」，必须让这条值传播<b>可观测</b>。原实现只打 {@code log.debug}
     * ⇒ 默认日志级别下「本该有会话却漏传 sessionId」完全静默（静默失效）。
     *
     * <p>判据 = <b>恰好 1 条</b>：<b>0 条</b> ⇒ 只 DEBUG（原缺陷）；<b>2+ 条</b> ⇒ 无一次性闸
     * （本族 31 个调用点会淹日志）；两出口<b>共闸</b> ⇒ 第二个出口不得追加第二条。
     *
     * <p>RED（反向实验）：把 {@code NON_SESSION_EXIT_WARNED.compareAndSet(false, true)} 改成
     * {@code if (true)}（或改回 {@code log.debug}）⇒ 本用例红（计数 3 / 计数 0）。
     *
     * <p>装置说明：命名出口<b>不读会话层</b>（不查 DB / 不查冻结表）⇒ 本用例用<b>计数解析器</b>
     * 断言「0 次回源」，以此证明无会话出口结构上不触及会话态（⭐ 该断言不受
     * {@code NoDatabaseSessionProjectRootExtension} 对任意 sessionId 答 sessionless 的陷阱影响，
     * 因为它断言的是「查了几次」而非「抛不抛」）。
     */
    @Test
    @DisplayName("[批 P5] 无会话命名出口 ≥WARN 且 warn-once：两次调用恰好 1 条（两出口共闸）")
    void nonSessionExit_warnsExactlyOnceAndNeverQueriesSessionLayer(@TempDir Path projectDir) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CwdResolution.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
            new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        CwdResolution.resetWarnGatesForTesting();
        final java.util.concurrent.atomic.AtomicInteger dbCalls = new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(sid -> {
            dbCalls.incrementAndGet();
            return SessionProjectRoot.Lookup.bound(projectDir.toString());
        });
        try {
            CwdResolution.getCwdForNonSession();
            CwdResolution.getCwdForNonSession();
            // 同族另一出口：与前者共用一个闸 ⇒ 不得再追加第二条告警
            CwdResolution.getOriginalCwdLayerForNonSession();

            java.util.List<String> warns = app.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("是无会话命名出口"))
                .toList();
            assertThat(warns)
                .as("⭐ 两次调用只允许 1 条 ≥WARN —— 0 条=只 DEBUG（原缺陷）；2 条=无一次性闸")
                .hasSize(1);
            assertThat(warns.get(0))
                .as("告警必须点名具体出口方法（占位符已被实参替换）")
                .contains("getCwdForNonSession 是无会话命名出口");
            assertThat(app.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("{}"))
                .toList())
                .as("⛔ 不得残留未替换的 {} 占位符（参数化占位符个数必须与实参一致）")
                .isEmpty();

            // 命名出口不得触及会话层：即便解析器已接线且能答 bound，也不得查（0 次回源）
            assertThat(dbCalls.get())
                .as("无会话命名出口 = 进程 user.dir，结构上不读会话层 ⇒ 0 次 DB 回源")
                .isZero();
        } finally {
            logger.detachAppender(app);
            app.stop();
            CwdResolution.resetWarnGatesForTesting();
            SessionProjectRoot.setDbResolver(null);
        }
    }
}

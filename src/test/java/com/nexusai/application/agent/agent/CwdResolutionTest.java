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

        // 正向对照：接线后，同一个 id 走「确无会话」命名出口（批 4a 该半条裁定保持不变）
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        try {
            assertThat(CwdResolution.getCwd("sess-not-in-db"))
                .as("接线 + DB 明确答「无此会话」⇒ 无会话出口（进程 user.dir），仍不抛")
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

        // 正向对照：同一 id，DB 明确答「无此会话」⇒ 无会话出口，仍不抛
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
        try {
            assertThat(CwdResolution.getCwd("sess-db-down"))
                .as("接线正常且 DB 答无此会话 ⇒ 无会话出口（进程 user.dir），不抛")
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
            return SessionProjectRoot.Lookup.unknown();
        });
        try {
            CwdResolution.getCwd(com.nexusai.common.SessionKeys.NO_SESSION);
            assertThat(calls.get())
                .as("哨兵是「有意声明无会话」⇒ 不得触发 DB 回源（0 次）")
                .isZero();

            // 正向对照：来源不明 id 走 unknown 分支 ⇒ 确实回源（证明计数装置有效，非恒 0）
            CwdResolution.getCwd("sess-not-in-db-b6");
            assertThat(calls.get())
                .as("非哨兵的 DB-miss id 必须回源（证明上面的 0 不是「解析器没生效」）")
                .isGreaterThan(0);
        } finally {
            SessionProjectRoot.setDbResolver(null);
        }
    }
}

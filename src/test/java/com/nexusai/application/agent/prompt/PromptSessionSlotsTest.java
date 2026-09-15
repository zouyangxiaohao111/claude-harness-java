package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0 前置夹具（本批 r10b 裁决 #10 第二域 · prompt 域）· DIVERGED + 锚点。
 *
 * <p><b>为什么先做这两个夹具</b>：它们是 D1/D2/D3/D5（git 锚点会话 cwd）与 D13
 * （{@code SessionGitStatusRegistry} 槽语义）的<b>共同验收装置</b>；做不出来则后续
 * 「锚对了 / 语义没串」的断言全部不可信。
 *
 * <p><b>⚠️ 为什么每个用例都必须显式 {@link SessionProjectRoot#setDbResolver}</b>：
 * 测试类路径上自动注册的全局扩展 {@code NoDatabaseSessionProjectRootExtension}
 * （{@code src/test/resources/META-INF/services}）对<b>任意</b> sessionId 答
 * {@link SessionProjectRoot.Lookup#sessionlessEnvironment()} ⇒ {@code CwdResolution} 走
 * 「确无会话」命名出口（进程 {@code user.dir}），<b>fail-loud 分支结构上不可达</b>。
 * 本类显式覆盖为 {@link SessionProjectRoot.Lookup#unknown()}（DB 明确答「无此会话」⇒
 * 会话层全 MISS 时必抛）⇒ <b>任何落到 DB 回源分支的读取都会抛</b>，从而把「值来自会话层」
 * 变成可证伪的断言，而不是「恰好没抛」。先例：{@code CwdResolutionTest}。
 *
 * <p>本类同时是 {@code PromptSessionSlots}（P2/D7 新增）的单元测试宿主：夹具先行，槽后补。
 */
@DisplayName("[r10b-P0] prompt 域前置夹具：DIVERGED 双槽 + git 锚点")
class PromptSessionSlotsTest {

    private static final String DIVERGED_SESSION = "sess-r10b-diverge";
    private static final String ANCHOR_SESSION = "sess-r10b-anchor";

    /** 进程级属性 {@code user.dir} 原值（用例内覆盖，finally 复原）。 */
    private String savedUserDir;

    /**
     * 显式覆盖全局默认解析器：答「DB 明确答无此会话」。
     *
     * <p>全局扩展（{@code BeforeEachCallback}）<b>先于</b>本方法执行 ⇒ 本方法覆盖有效；
     * 若会话层命中，读取<b>不会</b>触达回源器；一旦触达 ⇒ {@code CwdResolution} fail-loud 抛。
     */
    @BeforeEach
    void divergedFixture_resolverMustBeExplicit() {
        savedUserDir = System.getProperty("user.dir");
        SessionProjectRoot.setDbResolver(sid -> SessionProjectRoot.Lookup.unknown());
    }

    @AfterEach
    void cleanup() {
        SessionCwdHolder.reset();
        SessionProjectRoot.reset();
        SessionProjectRoot.setDbResolver(null);
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        } else {
            System.clearProperty("user.dir");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // P0-1 · DIVERGED 夹具：bash cd 覆盖 sessionCwd 后 getCwd ≠ getOriginalCwdLayer
    // ════════════════════════════════════════════════════════════════════════

    /**
     * DIVERGED 夹具<b>自身活性</b>证明：两个 cwd 层在「cd 过的会话」里必然不同值。
     *
     * <p>WHY（守护什么）：D13 的槽选择、R3 的「双槽不可互换」、{@code PromptSessionSlots}
     * 的 javadoc 警告全部依赖这一事实。若夹具做不出来（两值恒相同），后续所有
     * 「没串味」的断言都是空断言。
     *
     * <p>反向实验配方（本夹具的否证）：把 {@code SessionCwdHolder.set(sid, cdTarget)}
     * 改成 {@code setOriginalCwd(sid, cdTarget)}（即只写 originalCwd 槽）⇒
     * {@code getCwd} 解析不到 sessionCwd 层 → 落到 boundProject/DB 分支 → 因本类
     * 显式装了 {@code unknown()} 解析器而<b>抛</b> ⇒ 本用例红。
     */
    @Test
    @DisplayName("P0-1 DIVERGED 夹具活性：cd 覆盖 cwd 槽后 getCwd ≠ getOriginalCwdLayer（且两者都取自会话层）")
    void p0_divergedFixture_cwdAndOriginalCwdDiverge(@TempDir Path tmp) throws Exception {
        Path originalCwd = Files.createDirectories(tmp.resolve("orig-anchor"));
        Path cdTarget = Files.createDirectories(tmp.resolve("cd/sub"));
        assertThat(originalCwd).isNotEqualTo(cdTarget);

        // worktree/启动锚写 originalCwd 槽（对齐 CC STATE.originalCwd）
        SessionCwdHolder.setOriginalCwd(DIVERGED_SESSION, originalCwd.toString());
        // bash cd 写 cwd 槽（对齐 CC Shell.ts setCwd → STATE.cwd），覆盖关系 [Fix-R1]
        SessionCwdHolder.set(DIVERGED_SESSION, cdTarget.toString());

        String cwd = CwdResolution.getCwd(DIVERGED_SESSION);
        String original = CwdResolution.getOriginalCwdLayer(DIVERGED_SESSION);

        assertThat(cwd)
            .as("getCwd 必须取 cd 后的子目录（cwd 槽被 bash cd 覆盖）")
            .isEqualTo(cdTarget.toRealPath().toString());
        assertThat(original)
            .as("getOriginalCwdLayer 必须取重锚层（不被 bash cd 覆盖）")
            .isEqualTo(originalCwd.toRealPath().toString());
        assertThat(cwd)
            .as("⭐ DIVERGED 成立：两槽在 cd 过的会话里必须不同值（否则本夹具无效，后续断言全空）")
            .isNotEqualTo(original);
    }

    /**
     * ⭐ <b>显式覆盖证据</b>：证本类装的 {@code unknown()} 解析器真的生效（fail-loud 可达）。
     *
     * <p>WHY：全局默认答 {@code sessionless}（不抛）⇒ 若本类不显式覆盖，「会话层未命中就抛」
     * 这条判据<b>结构上不可达</b>，P0-1 的「值来自会话层」便退化成「恰好没抛」。
     * 本用例把可达性本身钉住：未登记的合成 id ⇒ 必抛。
     */
    @Test
    @DisplayName("P0-1b 显式 setDbResolver 证据：未登记会话 ⇒ getCwd fail-loud 抛（装置可达）")
    void p0_explicitResolver_unknownSessionFailsLoud() {
        assertThatThrownBy(() -> CwdResolution.getCwd("sess-r10b-never-registered"))
            .as("本类显式装的 unknown() 解析器必须让会话层全 MISS 的读取 fail-loud 抛 —— "
                + "否则 P0-1 的「值来自会话层」不可证伪（测试环境全局默认答 sessionless，不抛）")
            .isInstanceOf(IllegalStateException.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // P0-2 · 锚点夹具：会话 cwd = 临时目录 A（非 git）；进程 user.dir = 临时目录 B（git 仓库）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 锚点夹具<b>自身活性</b>证明：git 锚点确实可以「锚 A 不锚 B」。
     *
     * <p>WHY（守护什么）：D1/D2/D3/D5 的缺陷形态全部是「手里有会话却调无参
     * {@code new GitStatusProvider()}} ⇒ 锚到进程 {@code user.dir}。本夹具把
     * 「A 非 git / B 是 git」这一对立面造出来，使「锚错」可观测（本会话下 isGit 必须 false）。
     *
     * <p>反向实验配方：把断言里的
     * {@code new GitStatusProvider(Path.of(CwdResolution.getCwd(ANCHOR_SESSION)))} 改回无参
     * {@code new GitStatusProvider()} ⇒ 锚到 B（git 仓库）⇒ {@code isGit()} 变 true ⇒ 红。
     */
    @Test
    @DisplayName("P0-2 锚点夹具活性：会话 cwd=A(非 git) vs 进程 user.dir=B(git) ⇒ 显式传会话 cwd 时 isGit=false")
    void p0_anchorFixture_sessionCwdIsNotProcessUserDir(@TempDir Path tmp) throws Exception {
        Path sessionDir = Files.createDirectories(tmp.resolve("session-project"));
        Path processDir = Files.createDirectories(tmp.resolve("process-launch-dir"));
        Files.createDirectory(processDir.resolve(".git"));

        // 夹具前置：A 必须真的不在任何 git 仓库内（否则「锚 A」与「锚 B」不可分辨）
        assertThat(new GitStatusProvider(sessionDir).findGitRoot())
            .as("夹具前置失败：临时目录 A 落在某个 git 仓库内（@TempDir 根位置变化）⇒ 本夹具无效，须停下报告")
            .isNull();

        System.setProperty("user.dir", processDir.toString());
        SessionCwdHolder.set(ANCHOR_SESSION, sessionDir.toString());

        assertThat(new GitStatusProvider().isGit())
            .as("基线：无参构造锚进程 user.dir = B，B 是 git 仓库 ⇒ true")
            .isTrue();
        assertThat(new GitStatusProvider(Path.of(CwdResolution.getCwd(ANCHOR_SESSION))).isGit())
            .as("⭐ 锚点夹具成立：显式传会话 cwd = A（非 git）⇒ false（与无参构造的可观测差）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // R3 · 双槽不可互换（cwd vs originalCwd）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 守护：{@link PromptSessionSlots#cwd()} 与 {@link PromptSessionSlots#originalCwd()} <b>不得互换</b>。
     *
     * <p>反向实验配方：把 {@code cwd()} 的方法体改成 {@code return originalCwd();} ⇒
     * 本用例两条断言同时红（值变成 B，且两值相等）。
     */
    @Test
    @DisplayName("R3 双槽不可互换：cwd 取 cd 后目录、originalCwd 取重锚目录（DIVERGED 会话下必然不同）")
    void r3_slotsAreNotInterchangeable(@TempDir Path tmp) throws Exception {
        Path cdTarget = Files.createDirectories(tmp.resolve("cd/sub"));
        Path originalAnchor = Files.createDirectories(tmp.resolve("orig-anchor"));
        SessionCwdHolder.setOriginalCwd(DIVERGED_SESSION, originalAnchor.toString());
        SessionCwdHolder.set(DIVERGED_SESSION, cdTarget.toString());

        PromptSessionSlots slots = PromptSessionSlots.of(DIVERGED_SESSION);

        assertThat(slots.cwd())
            .as("cwd 槽 = getCwd 语义（bash cd 可覆盖）")
            .isEqualTo(cdTarget.toRealPath().toString());
        assertThat(slots.originalCwd())
            .as("originalCwd 槽 = getOriginalCwdLayer 语义（不被 cd 覆盖）")
            .isEqualTo(originalAnchor.toRealPath().toString());
        assertThat(slots.cwd())
            .as("⭐ 两槽在 cd 过的会话里必须不同值（用 cwd 顶替 originalCwd 会让 scratchpad 锚漂走）")
            .isNotEqualTo(slots.originalCwd());
    }

    /** 守护：{@link PromptSessionSlots#worktreeBound()} 走会话级判定（非 git 级检测）。 */
    @Test
    @DisplayName("R3b worktreeBound 槽：仅 EnterWorktree 标记过的会话为 true")
    void r3b_worktreeBoundSlot() {
        PromptSessionSlots unmarked = PromptSessionSlots.of("sess-r10b-wt-none");
        assertThat(unmarked.worktreeBound()).as("未标记 ⇒ false").isFalse();

        SessionCwdHolder.markWorktree("sess-r10b-wt-yes");
        PromptSessionSlots marked = PromptSessionSlots.of("sess-r10b-wt-yes");
        assertThat(marked.worktreeBound()).as("EnterWorktree 标记过 ⇒ true").isTrue();
        SessionCwdHolder.clearWorktree("sess-r10b-wt-yes");
    }

    // ════════════════════════════════════════════════════════════════════
    // R4 · 惰性 · section 缓存命中面不扩（⭐ 必须证伪「把它改成 eager 也不红」）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 守护：槽是<b>惰性</b>的 —— 构造零解析；且 section 缓存命中时槽<b>零读取</b>。
     *
     * <p>装置（⭐ 必须显式覆盖全局默认，否则本用例是空断言）：
     * 计数版 {@code DbResolver}（答 {@code sessionlessEnvironment()}：不抛、不冻结 ⇒ 每次
     * {@code getCwd} 都回源、计数准确）。若沿用测试环境全局默认（同样答 sessionless 但<b>不计数</b>），
     * 则「eager 化了却不红」——本用例正是为了不让那种情况发生。
     *
     * <p>反向实验配方（两条独立否证）：
     * <ol>
     *   <li>把 {@code PromptSessionSlots.of(...)} 改成构造期 eager 解析（三个槽在构造器里求值）⇒
     *       第一条断言（{@code of()} 后计数 = 0）红；</li>
     *   <li>把 {@code SystemPromptSectionRegistry.resolveAll} 的缓存短路删掉 ⇒
     *       最后一条断言（turn2 计数不增长）红。</li>
     * </ol>
     */
    @Test
    @DisplayName("R4 惰性：of() 零解析 + section 缓存命中 ⇒ 槽零读取（计数可证伪）")
    void r4_slotIsLazy_andCacheHitDoesNotReadIt() {
        final java.util.concurrent.atomic.AtomicInteger dbLookups =
            new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(s -> {
            dbLookups.incrementAndGet();
            return SessionProjectRoot.Lookup.sessionlessEnvironment();
        });

        String sid = "sess-r10b-r4";
        // 基线：fresh 槽单线程读一次 cwd ⇒ 「1 次槽解析」对应的回源次数（自校准，不写死数字）
        PromptSessionSlots probe = PromptSessionSlots.of(sid);
        probe.cwd();
        final int perResolve = dbLookups.get();
        assertThat(perResolve).as("基线：一次槽解析确实会回源（装置非空）").isGreaterThan(0);

        dbLookups.set(0);
        PromptSessionSlots slots = PromptSessionSlots.of(sid);
        assertThat(dbLookups.get())
            .as("⭐ of() 必须零解析（惰性）—— eager 化后此处立即非 0")
            .isZero();

        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(
            java.util.Set.of(), "m", java.util.List.of(), java.util.List.of(), null,
            java.util.List.of(), null, null, false, sid, false, false, false, slots);
        assertThat(dbLookups.get())
            .as("⭐ 构造 input（含槽）也必须零解析")
            .isZero();

        final java.util.concurrent.atomic.AtomicInteger computes =
            new java.util.concurrent.atomic.AtomicInteger();
        SystemPromptSectionRegistry registry = new SystemPromptSectionRegistry();
        SystemPromptSectionCache cache = new SystemPromptSectionCache();
        registry.register(SystemPromptSections.systemPromptSection("env_info_simple", () -> {
            computes.incrementAndGet();
            slots.cwd();  // 模拟 env_info_simple 的槽读（D9 后 compute 内读槽）
            return java.util.concurrent.CompletableFuture.completedFuture("env");
        }));

        // turn1：冷缓存 → compute 执行 → 读槽一次
        registry.resolveAll(cache);
        assertThat(computes.get()).as("turn1 冷缓存 ⇒ compute 执行一次").isEqualTo(1);
        assertThat(dbLookups.get())
            .as("turn1 ⇒ 恰好一次槽解析（perResolve 由基线自校准）")
            .isEqualTo(perResolve);

        // turn2：缓存命中 → compute 短路 → 槽零读取
        int afterTurn1 = dbLookups.get();
        registry.resolveAll(cache);
        assertThat(computes.get()).as("turn2 缓存命中 ⇒ compute 不执行").isEqualTo(1);
        assertThat(dbLookups.get())
            .as("⭐ turn2 缓存命中 ⇒ 槽零读取（惰性未被 eager 化）")
            .isEqualTo(afterTurn1);
    }

    // ════════════════════════════════════════════════════════════════════
    // R5 · 惰性 · assemble 短路面不扩（ResumeService / SubagentTool 形态）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 守护：把「构造 input + 槽」放在 {@code Supplier} <b>之外</b>（{@code ResumeService:475} /
     * {@code SubagentTool:4104} 的形态）也<b>不新增抛出</b>。
     *
     * <p>装置（⭐ 显式 {@code setDbResolver(unknown)}，本类 {@code @BeforeEach}）：该类会话解析
     * <b>必抛</b>（{@code Lookup.unknown()} = DB 明确答无此会话 ⇒ cwd 域 fail-loud）。
     * 因此本用例同时给出正反两面：
     * <ul>
     *   <li>① {@code of(sid)} + 构造 input <b>不抛</b>（惰性 ✓）；</li>
     *   <li>② 真去读槽 {@code slots.cwd()} <b>必抛</b>（证明 ① 不是「装置本来就不抛」的空断言）。</li>
     * </ul>
     * ③ 另证 custom 短路：{@code EffectiveSystemPromptBuilder} 在 custom 非空时不调 {@code assemble}。
     *
     * <p>反向实验配方：把 {@code of()} 改成 eager ⇒ ① 直接抛 ⇒ 红。
     */
    @Test
    @DisplayName("R5 惰性：input 构造（Supplier 之外）不新增抛出；且装置可证伪（读槽必抛）")
    void r5_inputConstructionIsLazy_assembleShortCircuit() {
        String sid = "sess-r10b-r5-unresolvable";

        PromptSessionSlots slots = PromptSessionSlots.of(sid);
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(
            java.util.Set.of(), "m", java.util.List.of(), java.util.List.of(), null,
            java.util.List.of(), null, null, false, sid, false, false, false, slots);
        assertThat(input.sessionSlots()).as("① 构造 input（Supplier 之外）不抛，槽原样承载").isSameAs(slots);

        // ② 反证装置是活的：真读槽 ⇒ fail-loud 抛（否则 ① 是空断言）
        assertThatThrownBy(slots::cwd)
            .as("② 装置可证伪：本会话的 cwd 解析确实会抛（① 才有意义）")
            .isInstanceOf(IllegalStateException.class);

        // ③ custom 非空 ⇒ default 组装（Supplier）根本不被调用
        final java.util.concurrent.atomic.AtomicBoolean assembleCalled =
            new java.util.concurrent.atomic.AtomicBoolean();
        SystemPrompt effective = com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder.build(
            () -> {
                assembleCalled.set(true);
                return SystemPrompt.from(java.util.List.of("DEFAULT"));
            },
            null,        // overrideSystemPrompt
            "CUSTOM",    // customSystemPrompt（替换 default）
            null,        // memoryMechanicsPrompt
            null);       // appendSystemPrompt
        assertThat(assembleCalled)
            .as("③ custom 非空 ⇒ EffectiveSystemPromptBuilder 短路，不调 assemble（CC queryContext.ts:62-63）")
            .isFalse();
        assertThat(effective.elements()).containsExactly("CUSTOM");
    }

    // ════════════════════════════════════════════════════════════════════
    // R6 · 槽 memoize 并发单飞（⛔ 不得照抄 r10 的裸字段写法）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 守护：<b>多线程并发读同一槽只解析一次</b>（{@code ForkJoinPool} 上多 section compute 会并发
     * 读同一槽，见 {@link SystemPromptSectionRegistry#resolveAll} 的 {@code supplyAsync}）。
     *
     * <p>装置：计数版 {@code DbResolver}（答 {@code sessionlessEnvironment()}：不抛、不冻结）
     * + {@code CyclicBarrier} 同时起跑 N 线程 + 自校准基线（单线程一次槽解析 = 多少回源）。
     *
     * <p>反向实验配方：删掉 {@link PromptSessionSlots} 的 {@code synchronized} 双检
     * （照抄 {@code SlashCommandContext} 的裸字段写法，字段也不必 volatile）⇒
     * N 个线程各自解析 ⇒ 计数 &gt; perResolve ⇒ 红。
     */
    @Test
    @DisplayName("R6 并发单飞：N 线程同时读同一槽 ⇒ 只解析一次（volatile + synchronized 双检）")
    void r6_slotMemoizeIsSingleFlightUnderConcurrency() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger dbLookups =
            new java.util.concurrent.atomic.AtomicInteger();
        SessionProjectRoot.setDbResolver(s -> {
            dbLookups.incrementAndGet();
            return SessionProjectRoot.Lookup.sessionlessEnvironment();
        });
        String sid = "sess-r10b-r6";

        PromptSessionSlots probe = PromptSessionSlots.of(sid);
        probe.cwd();
        final int perResolve = dbLookups.get();
        assertThat(perResolve).as("基线：一次槽解析确实会回源（装置非空）").isGreaterThan(0);

        final int threads = 16;
        final int rounds = 3;
        for (int round = 0; round < rounds; round++) {
            dbLookups.set(0);
            PromptSessionSlots slots = PromptSessionSlots.of(sid);
            java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(threads);
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        barrier.await();
                        return slots.cwd();
                    }));
                }
                String first = futures.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
                for (java.util.concurrent.Future<String> f : futures) {
                    assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .as("并发读到的值必须一致").isEqualTo(first);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(dbLookups.get())
                .as("⭐ 第 %d 轮：%d 线程并发读同一槽 ⇒ 只解析一次（= perResolve）", round + 1, threads)
                .isEqualTo(perResolve);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // R7 · getScratchpadDir 异常面逐点不变（槽读必须在 try 内）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 守护：{@code getScratchpadDir} 的「解析失败 ⇒ 返回 null（记 ERROR），不抛穿」契约
     * （[S2 · F-10 · 用户裁定 #6]：跳过但 ≥ERROR），2 参槽版与 1 参旧版<b>逐点一致</b>。
     *
     * <p>反向实验配方：把 {@code SystemPromptSections.getScratchpadDir(String,Supplier)} 里的
     * {@code originalCwdSlot.get()} 挪到 {@code try} 之外 ⇒ 第一条断言由「返回 null」变成抛 ⇒ 红。
     */
    @Test
    @DisplayName("R7 scratchpad：槽解析抛出 ⇒ 返回 null（不抛穿）；旧 1 参路径同款降级")
    void r7_scratchpadDirSlotFailureDegradesToNull(@TempDir Path tmp) throws Exception {
        String sid = "sess-r10b-r7";
        java.util.function.Supplier<String> throwing = () -> {
            throw new IllegalStateException("slot-resolution-boom");
        };
        assertThat(SystemPromptSections.getScratchpadDir(sid, throwing))
            .as("槽解析抛出 ⇒ try 内吞掉并返回 null（不阻断组装）")
            .isNull();

        // 旧 1 参路径（未接边界）：本类装了 unknown() ⇒ getOriginalCwdLayer 会抛 ⇒ 同款降级
        assertThat(SystemPromptSections.getScratchpadDir(sid))
            .as("旧 1 参路径同款降级（未接边界时 fail-loud 仍被本方法的 try 承接）")
            .isNull();

        // 正向：合法槽值 ⇒ 目录真的按槽值派生（originalCwd 语义；不是 user.dir）
        Path originalCwd = Files.createDirectories(tmp.resolve("scratch-orig"));
        String dir = SystemPromptSections.getScratchpadDir(sid, originalCwd::toString);
        assertThat(dir)
            .as("scratchpad 目录按槽值（originalCwd 语义）派生，非 user.dir")
            .isNotNull()
            .contains(com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(originalCwd.toString()))
            .endsWith("scratchpad");
    }

    // ════════════════════════════════════════════════════════════════════
    // D9 · SystemPromptSections 的槽分支真的被读（可分辨装置：先建槽 ⇒ 再改 holder）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 从 {@code buildDynamicSections} 取 {@code env_info_simple} 并触发 compute（同 EnvSectionTest 手法）。
     */
    private static String envText(SystemPromptAssemblyInput input) {
        return SystemPromptSections.buildDynamicSections(input).stream()
            .filter(s -> "env_info_simple".equals(s.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("env_info_simple 未注册"))
            .compute().compute().join();
    }

    /** 从 buildDynamicSections 取 scratchpad section 并触发 compute（走真实接线）。 */
    private static String scratchpadSectionText(SystemPromptAssemblyInput input) {
        String v = SystemPromptSections.buildDynamicSections(input).stream()
            .filter(s -> "scratchpad".equals(s.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("scratchpad 未注册"))
            .compute().compute().join();
        assertThat(v).as("scratchpadEnabled=true ⇒ 段必须产出（否则断言为空断言）").isNotNull();
        return v;
    }

    private static SystemPromptAssemblyInput inputWithSlots(String sessionId, PromptSessionSlots slots,
                                                            boolean scratchpadEnabled) {
        return new SystemPromptAssemblyInput(
            java.util.Set.of(), "m", java.util.List.of(), java.util.List.of(), null,
            java.util.List.of(), null, null, false, sessionId, false, scratchpadEnabled, false, slots);
    }

    /**
     * 守护 D9 的 {@code :291} 槽分支：env 段的 cwd <b>取自槽</b>（而非当场再读会话态）。
     *
     * <p>⭐ <b>为什么这个装置可分辨</b>：建槽 → <b>先读一次</b>（{@code slots.cwd()}，镜像边界
     * {@code LlmAgentLoop:4865} 对同一槽实例的 eager 读）→ 再把 {@code SessionCwdHolder} 的 cwd 槽
     * 改成另一个目录 → 触发 env compute。读槽 ⇒ 得到<b>改前</b>的 A（槽 memoize）；当场再读 holder
     * ⇒ 得到<b>改后</b>的 B。两条路径产出不同值 ⇒ 断言可证伪。
     *
     * <p>反向实验配方：把 {@code SystemPromptSections} 的
     * {@code input.sessionSlots() != null ? Path.of(input.sessionSlots().cwd()) : cwd(...)} 三元
     * 恒取 else 分支 ⇒ 得到 B ⇒ 红。
     */
    @Test
    @DisplayName("D9 env 段读槽：槽已解析后改 holder 不影响 env cwd（证 :291 走槽而非当场再读）")
    void d9_envSectionReadsCwdSlot(@TempDir Path tmp) throws Exception {
        Path slotCwd = Files.createDirectories(tmp.resolve("slot-cwd"));
        Path holderLater = Files.createDirectories(tmp.resolve("holder-later"));
        String sid = "sess-r10b-d9-cwd";
        SessionCwdHolder.set(sid, slotCwd.toString());

        PromptSessionSlots slots = PromptSessionSlots.of(sid);
        SystemPromptAssemblyInput input = inputWithSlots(sid, slots, false);
        slots.cwd();   // 边界对同一槽实例的首次读（镜像 LlmAgentLoop:4865）⇒ 槽 memoize = A

        // 槽已解析之后再改 holder ⇒ 只有「当场再读 holder」的实现才会看到新值
        SessionCwdHolder.set(sid, holderLater.toString());

        String text = envText(input);
        assertThat(text)
            .as("⭐ env 段的 Primary working directory 取槽值（A），不是改后的 holder 值 B")
            .contains("Primary working directory: " + slotCwd.toString().replace('\\', '/'))
            .doesNotContain(holderLater.toString().replace('\\', '/'));
    }

    /**
     * 守护 D9 的 {@code :576} 槽分支：scratchpad 目录取自槽的 {@code originalCwd} 槽。
     *
     * <p>可分辨装置同上（建槽后改 holder 的 originalCwd）。
     *
     * <p>反向实验配方：把 {@code scratchpadCompute} 里的
     * {@code input.sessionSlots()::originalCwd} 改回 {@code null}（恒走旧路径）⇒ 读到改后的 B ⇒ 红。
     */
    @Test
    @DisplayName("D9 scratchpad 段读槽：槽已解析后改 originalCwd holder 不影响 scratchpad 目录")
    void d9_scratchpadSectionReadsOriginalCwdSlot(@TempDir Path tmp) throws Exception {
        Path slotOriginal = Files.createDirectories(tmp.resolve("slot-original"));
        Path holderLater = Files.createDirectories(tmp.resolve("holder-later-orig"));
        String sid = "sess-r10b-d9-scratch";
        SessionCwdHolder.setOriginalCwd(sid, slotOriginal.toString());

        PromptSessionSlots slots = PromptSessionSlots.of(sid);
        SystemPromptAssemblyInput input = inputWithSlots(sid, slots, true);   // scratchpadEnabled=true
        slots.originalCwd();   // 槽首次读 ⇒ memoize = A（之后走同一槽实例的读都取 A）

        SessionCwdHolder.setOriginalCwd(sid, holderLater.toString());

        // ⛔ 必须走 section compute 路径（scratchpadCompute 的接线），而不是直接调
        //   getScratchpadDir(sid, supplier) —— 后者只覆盖到 overload 本身，
        //   变异 scratchpadCompute 的三元不会变红（实测：绕过接线时该反向实验恒绿）。
        String scratchpad = scratchpadSectionText(input);
        assertThat(scratchpad)
            .as("⭐ scratchpad 目录取槽的 originalCwd（槽已解析值 A），不是改后的 holder 值 B")
            .contains(com.nexusai.application.agent.memory.AutoMemPaths
                .sanitizePath(slotOriginal.toString()))
            .doesNotContain(com.nexusai.application.agent.memory.AutoMemPaths
                .sanitizePath(holderLater.toString()));
        assertThat(input.sessionSlots()).isSameAs(slots);
    }

    /**
     * 守护 D9 的 {@code :300} 槽分支：env 段的 worktree 子弹取自槽的 {@code worktreeBound()}。
     *
     * <p><b>⚠️ 照实声明本用例的分辨力</b>：槽值与回落值<b>同源</b>（都来自
     * {@code SessionCwdHolder}），故「删掉槽分支」这一变异<b>不会</b>变红 —— 两条路径在本例下
     * 语义等价。本用例真正守住的是「槽本身被读且值正确」：反向实验配方 = 把
     * {@code PromptSessionSlots.worktreeBound()} 改成 {@code return false;} ⇒ 红。
     */
    @Test
    @DisplayName("D9 env 段读槽：worktreeBound 槽 true ⇒ 注入 worktree 子弹；false ⇒ 不注入")
    void d9_envSectionReadsWorktreeSlot(@TempDir Path tmp) throws Exception {
        Path cwd = Files.createDirectories(tmp.resolve("wt-cwd"));
        String sidOn = "sess-r10b-d9-wt-on";
        SessionCwdHolder.set(sidOn, cwd.toString());
        SessionCwdHolder.markWorktree(sidOn);
        PromptSessionSlots on = PromptSessionSlots.of(sidOn);
        assertThat(envText(inputWithSlots(sidOn, on, false)))
            .as("槽 = true ⇒ 注入 worktree 子弹")
            .contains("This is a git worktree");

        String sidOff = "sess-r10b-d9-wt-off";
        SessionCwdHolder.set(sidOff, cwd.toString());
        PromptSessionSlots off = PromptSessionSlots.of(sidOff);
        assertThat(envText(inputWithSlots(sidOff, off, false)))
            .as("槽 = false ⇒ 不注入 worktree 子弹")
            .doesNotContain("This is a git worktree");
    }
}

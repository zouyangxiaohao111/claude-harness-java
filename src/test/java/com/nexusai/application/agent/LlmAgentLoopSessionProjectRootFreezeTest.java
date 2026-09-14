package com.nexusai.application.agent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.LlmProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-A · F1 · D1-A/OPD-SPR-03] resolveSessionProjectRoot 会话级冻结实证。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 在启动时 realpath(cwd) 冻结为
 * projectRoot（state.ts:45-50 stable projectRoot 注释 + :269-279 getInitialState），会话中
 * 不再更新（state.ts:511-513）。IMP-A F1 让 Java 端 run() 入口的 resolveSessionProjectRoot()
 * 先查 {@link SessionProjectRoot#getForSession(String)}（首 run 冻结 / bind() 登记）→ 命中
 * 直接复用、<b>不再查 DB</b>（resolver 不被调用）；未命中才走 {@code sessionProjectRootResolver}
 * 并 {@code setForSession} 首 run 冻结。
 *
 * <p><b>RED 条件</b>：删除 F1 冻结逻辑（恒走 resolver）→ 第二次 resolve 调用 resolver 计数 = 2
 * （重复查 DB），本测试变红。F7 归一（realpath + NFC，对齐 state.ts:270-275 / :271 EPERM 回退）
 * 随行锁定：resolver 返回值落 workspaceDir 处产出字节恒 NFC+realpath。
 *
 * <p>驱动方式：{@code new LlmAgentLoop(factory)}（单测裸构造，同 ResumeRestoreEntryTest 基建）
 * + {@code setStreamContext}（设 streamSessionId）+ {@code setSessionProjectRootResolver}
 * （计数 mock）→ 反射调用 private {@code resolveSessionProjectRoot(String projectRootOverride)}。
 *
 * <p><b>[批 1 · 方向 C 改锚]</b> 方法签名从无参改为单显式参数（= {@code RunRequest.boundProject()}
 * 直传的项目锚）：原「调用方 {@code setCronProjectRootOverride} 写实例字段 → 消费端读字段并在
 * 同一线程写 AutoMemPaths ThreadLocal」的隐式通道已删，改为值随参数进入。本节两个 cron 用例的
 * 断言目标（workspaceDir / AutoMemPaths / 不冻结 SessionProjectRoot）原样保留，仅载体改锚。
 *
 * <p><b>⚠️ [F-18] 覆盖边界（本类守护哪一段）</b>：本类用例经<b>反射直接调</b>
 * {@code resolveSessionProjectRoot(String)}，因此只覆盖<b>被调用方内部</b>；{@code doRun} 里那一行
 * 调用点（{@code resolveSessionProjectRoot(params.boundProject())}）<b>本类不覆盖</b> —— 实证：
 * 把该行实参改成 {@code null}，本类 9 条<b>仍全绿</b>。<b>调用点覆盖见</b>
 * {@link LlmAgentLoopRunBoundProjectWiringTest}（以 {@code run(RunRequest)} 为唯一入口）。
 */
@DisplayName("[IMP-A F1] resolveSessionProjectRoot 会话级冻结：首 run 冻结、会话内不重查 DB")
class LlmAgentLoopSessionProjectRootFreezeTest {

    private static final String SESSION_ID = "sess-f1-freeze";

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        // [批 4b-1] 原 AutoMemPaths.resetCurrentProjectRoot() 已删（ThreadLocal 载体删除）。
    }

    /**
     * 反射调 {@code resolveSessionProjectRoot(String projectRootOverride)}。
     *
     * <p>[批 1 · 方向 C] 方法签名从无参改为<b>单显式参数</b>（run 携带的项目锚，
     * {@code RunRequest.boundProject()} 直传）：原「无参 + 实例字段 cronProjectRootOverride」
     * 的 ThreadLocal 通道已删。锚 = null 复现无锚路径（普通会话 / SESSION fire）。
     */
    private static void invokeResolve(LlmAgentLoop loop, String projectRootOverride) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("resolveSessionProjectRoot", String.class);
        m.setAccessible(true);
        m.invoke(loop, projectRootOverride);
    }

    /** 无锚调用（普通会话 / SESSION fire 形态）。 */
    private static void invokeResolve(LlmAgentLoop loop) throws Exception {
        invokeResolve(loop, null);
    }

    @Test
    @DisplayName("首 run resolver 解析+冻结；第二次 run 命中冻结、resolver 不再被调（不重查 DB）")
    void firstRun_freezes_secondRun_skipsResolver() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("proj-a")).toRealPath();
        AtomicInteger resolverCalls = new AtomicInteger();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        loop.setStreamContext(null, SESSION_ID, "msg-1");
        // 返回原始（可能短路径）字符串 —— 断言端用 toRealPath 对比，同时验证 F7 realpath 生效
        loop.setSessionProjectRootResolver(sessionId -> {
            resolverCalls.incrementAndGet();
            return tempDir.resolve("proj-a").toString();
        });

        // ── 首 run：resolver 调用 1 次 → 归一（realpath+NFC）→ workspaceDir / ThreadLocal / 冻结 ──
        invokeResolve(loop);
        assertThat(resolverCalls.get())
            .as("首 run 必须经 resolver 解析一次").isEqualTo(1);
        assertThat(loop.workspaceDir())
            .as("F7: resolver 返回值必须 realpath 归一后落 workspaceDir")
            .isEqualTo(real);
        // [批 4b-1] 原断言「AutoMemPaths.currentSessionProjectRoot() == real」随 ThreadLocal 载体删除 ——
        //   会话项目根的唯一载体 = workspaceDir（上面已断言），不再有第二份线程槽副本可比对。
        assertThat(SessionProjectRoot.getForSession(SESSION_ID))
            .as("F1: 首 run 解析成功必须冻结会话（setForSession 首写胜）")
            .isEqualTo(real.toString());

        // ── 第二次 run：命中冻结 → resolver 不再被调（不重查 DB）──
        invokeResolve(loop);
        assertThat(resolverCalls.get())
            .as("F1: 会话内冻结命中后不得重查 DB（resolver 调用次数必须仍为 1）")
            .isEqualTo(1);
        assertThat(loop.workspaceDir())
            .as("F1: 冻结命中路径 workspaceDir 与首 run 一致")
            .isEqualTo(real);
    }

    @Test
    @DisplayName("bind() 预登记冻结值（IMP-B 来源）：命中直接复用，resolver 为 null 也工作")
    void frozenByBindingService_usedWithoutResolver() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("proj-b")).toRealPath();
        // IMP-B: ProjectSessionBindingService.bind() → setForSession(sessionId, p.getPath()) 未归一
        SessionProjectRoot.setForSession(SESSION_ID, tempDir.resolve("proj-b").toString());

        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        loop.setStreamContext(null, SESSION_ID, "msg-1");
        // resolver 不注入（null）—— 命中冻结路径必须先于 resolver null 检查生效
        invokeResolve(loop);

        assertThat(loop.workspaceDir())
            .as("F1: bind() 冻结值（未归一）命中后必须归一为 NFC+realpath，与 resolver 分支产出一致")
            .isEqualTo(real);
        // [批 4b-1] 同上：不再有 ThreadLocal 副本可断言（冻结表值见上方 getForSession 断言）。
    }

    @Test
    @DisplayName("[TL-W2 P8] resolver 未注入且未冻结 → workspaceDir 保持 null（无有效项目，绝不回落 configHome）")
    void noResolver_noFrozen_keepsDefault() throws Exception {
        // WHY（规则九 · 测试验证意图改写）：旧实现字段初始化器 = Path.of(currentSessionProjectRoot())
        //   → 构造期 ThreadLocal 恒空 ⇒ 初值恒为 env ?? ~/.nexusai（configHome）⇒ 未命中分支经
        //   buildSessionStateFromInstance 把 configHome 塞进 AgentState（审计 P8）。现默认 null：
        //   未解析到绑定项目就保持 null，下游按「无有效项目」skip（A′）。
        //   RED（[S4-residual] 重锚到现存可执行变异）：把字段初始化器改回「构造期读环境态」
        //   —— 即 {@code workspaceDir = Path.of(NexusaiPaths.getAppConfigHomeDir())}
        //   （= 已删的 AutoMemPaths.currentSessionProjectRoot() 的 config-home 第 3 级）
        //   → 首断言（构造期 workspaceDir 必须为 null）变红；漏到 resolve 后仍非 null 则第二断言亦红。
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        loop.setStreamContext(null, SESSION_ID, "msg-1");

        assertThat(loop.workspaceDir())
            .as("构造期 workspaceDir 必须为 null（字段初始化器不再读 ThreadLocal）")
            .isNull();

        invokeResolve(loop);

        assertThat(loop.workspaceDir())
            .as("未注入未冻结（无绑定项目）→ workspaceDir 保持 null，不得回落 configHome")
            .isNull();
        // [批 4b-1] 原断言「ThreadLocal 未被触碰 → 仍非 null」随载体删除 —— 现「确无项目根」的唯一
        //   表达即 workspaceDir=null（上方断言），且不得再有任何回落链（config home 回落已废除）。
    }

    @Test
    @DisplayName("F7 realpath 失败回退原文 NFC（对齐 CC state.ts:271 EPERM 回退）+ NFC 归一化")
    void realpathFail_fallsBackToNfcRaw() {
        // 分解形路径且目录不存在 → toRealPath 抛 NoSuchFileException → 回退原文仅 NFC。
        // [2026-08-24 cwd 污染修复] setForSession 校验目录存在后，loop 不再绑定无效路径，
        //   realpath 失败回原文由 CwdResolution.normalizeCwd 承担——此处直接验证其行为（意图不变）。
        Path decomposed = tempDir.resolve("café-project").resolve("nested");
        String raw = decomposed.toString();
        String expectedNfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        assertThat(CwdResolution.normalizeCwd(raw))
            .as("F7: realpath 失败必须回退原文（仅 NFC 归一），不抛异常")
            .isEqualTo(expectedNfc);
    }

    @Test
    @DisplayName("无 streamSessionId → 直接回落，不查冻结也不查 resolver")
    void noSessionId_returnsWithoutSideEffects() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        // 不 setStreamContext → streamSessionId=null
        loop.setSessionProjectRootResolver(sessionId -> {
            resolverCalls.incrementAndGet();
            return tempDir.resolve("proj-x").toString();
        });

        invokeResolve(loop);

        assertThat(resolverCalls.get()).as("无 streamSessionId 不得调用 resolver").isZero();
        assertThat(SessionProjectRoot.getForSession(SESSION_ID)).isNull();
    }

    // ====== [批 1 方向 C] DURABLE cron 项目锚：显式参数传值（原 ThreadLocal 通道已删） ======

    @Test
    @DisplayName("[批 1 方向 C] 显式项目锚（DURABLE 项目身份）→ streamSessionId=null 空守卫也注入锚值，不冻结 SessionProjectRoot")
    void explicitProjectAnchor_injectsBeforeNullGuard_withoutFreeze() throws Exception {
        // WHY（规则九）: CC durable cron fire 把 prompt 塞回创建会话命令队列（useScheduledTasks.ts:71-82
        // enqueueForLead，不新建会话/无全局会话）→ 该回合 memory 归属创建项目 projectRoot git root
        // （cronTasks.ts:74-83 文件位置锚 → paths.ts:223-235 getAutoMemPath）。DURABLE cron 的
        // streamSessionId 可为 null（创建会话已关 → headless）→ 若锚检查放在 null 守卫【之后】，
        // 守卫会永远提前 return → memory/workspaceDir 落 CLAUDE_PROJECT_DIR env ?? config-home
        // （全局，偏离 CC）。锚检查必须在 null 守卫【之前】→ workspaceDir + AutoMemPaths 同时锚
        // 显式锚值；不冻结 SessionProjectRoot（GLOBAL_SESSION_UUID 是所有 DURABLE 任务的共享兜底键，
        // 冻结会造成跨项目 memory 污染）。
        //
        // [批 1 改锚] 载体从「实例字段 setCronProjectRootOverride（消费端写 ThreadLocal）」换成
        // 「方法显式参数 projectRootOverride（= RunRequest.boundProject 直传，对齐 CC 把 cwd 作为值
        // 带在队列命令上 useScheduledTasks.ts:52/:110）」。
        // RED（反向实验 · 有鉴别力）: 实现退回旧通道（忽略入参、改读实例字段）⇒ 本测试入参传锚
        // 而无实例字段 → 走 null 守卫 return → workspaceDir/AutoMemPaths 断言变红。
        Path real = Files.createDirectories(tempDir.resolve("proj-cron")).toRealPath();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        // 不 setStreamContext → streamSessionId=null（headless DURABLE 形态）

        invokeResolve(loop, tempDir.resolve("proj-cron").toString());   // 显式锚 = RunRequest.boundProject

        assertThat(loop.workspaceDir())
            .as("显式锚非空 → workspaceDir 锚该值（realpath 归一）")
            .isEqualTo(real);
        // [批 4b-1] 原断言「ThreadLocal 锚该值」随载体删除：显式锚现由 workspaceDir 承载，
        //   auto-memory 消费点从 workspaceDir 显式取根（上方 workspaceDir 断言即覆盖本语义）。
        assertThat(SessionProjectRoot.getForSession(SESSION_ID))
            .as("显式锚路径不得冻结 SessionProjectRoot（GLOBAL 兜底键防跨任务污染）")
            .isNull();
    }

    @Test
    @DisplayName("[批 1 方向 C] 无显式锚（SESSION/普通路径）→ 既有会话解析不变（resolver 仍被调用、照常冻结）")
    void noAnchor_sessionPathUnchanged() throws Exception {
        // WHY（规则九）: 显式锚参数 null → 不得触碰 SESSION 路径。DURABLE 才携带 boundProject
        // （ScheduleService 仅 DURABLE 存 bound_project 列，且 SESSION fire 的 boundProject 恒 null）；
        // SESSION fire / 普通会话 run 必须保持既有 streamSessionId 解析（resolver 调用 / 冻结）零变化。
        // RED: 若锚检查误放错位置（如意外短路 session 分支）→ resolver 计数/冻结断言变红。
        Path real = Files.createDirectories(tempDir.resolve("proj-session")).toRealPath();
        AtomicInteger resolverCalls = new AtomicInteger();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        loop.setStreamContext(null, SESSION_ID, "msg-1");
        loop.setSessionProjectRootResolver(sessionId -> {
            resolverCalls.incrementAndGet();
            return tempDir.resolve("proj-session").toString();
        });
        // override 不置（null）

        invokeResolve(loop);

        assertThat(resolverCalls.get())
            .as("override=null → SESSION 路径照常走 resolver")
            .isEqualTo(1);
        assertThat(loop.workspaceDir())
            .as("override=null → workspaceDir 照常锚会话绑定项目")
            .isEqualTo(real);
        assertThat(SessionProjectRoot.getForSession(SESSION_ID))
            .as("override=null → SESSION 路径照常冻结（F1 首 run 冻结）")
            .isEqualTo(real.toString());
    }

    // ============ [cron-durable-session-fire] DURABLE fire 归创建会话（去 per-task 虚拟键） ============

    @Test
    @DisplayName("[批 1 方向 C] 显式项目锚只锚 workspaceDir/memory；transcript 纯 sessionId 解析归创建会话（锚不落入 transcript 键）")
    void explicitProjectAnchor_setsWorkspaceDir_transcriptResolvesBySessionId() throws Exception {
        // WHY（规则九 · 测试验证意图）: 显式项目锚（RunRequest.boundProject → 本方法入参）只锚
        // workspaceDir + AutoMemPaths 项目身份；transcript 键由 RunRequest.sessionId
        // （CronIdleExecutor 存活判定后传创建会话 key）经 SessionStorage 纯 sessionId 解析 →
        // {configHome}/projects/{slug}/{创建会话UUID}.jsonl。已删 per-task 虚拟键 companion ——
        // RED: 若锚值被当作 transcript 键（或残留 per-task override），transcript 落虚拟键文件
        // 而非创建会话文件 → 变红。
        Path real = Files.createDirectories(tempDir.resolve("proj-durable-s")).toRealPath();
        String creatingSessionUuid =
            com.nexusai.common.SessionKeys.canonicalUuid("sess-1234abcd").toString();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        // 不 setStreamContext → streamSessionId=null（headless DURABLE 形态）

        invokeResolve(loop, tempDir.resolve("proj-durable-s").toString());   // 显式锚 = RunRequest.boundProject

        assertThat(loop.workspaceDir())
            .as("显式项目锚 → workspaceDir 锚该值（项目身份注入）")
            .isEqualTo(real);
        // 消费方统一底层 = SessionStorage.getTranscriptPath（SessionMemoryService:1365 /
        // CompactConversation:970 / PartialCompactConversation:670 / CommandHookExecutor:1921）
        // [S2] transcript 锚点迁 config-home：getProjectDir(real) = {configHome}/projects/{sanitize(real)}
        Path transcript = com.nexusai.application.agent.tool.SessionStorage.getTranscriptPath(
            real, creatingSessionUuid);
        assertThat(transcript)
            .as("transcript 纯 sessionId 解析 → {configHome}/projects/{slug}/{创建会话UUID}.jsonl（S2 迁 config-home）")
            .isEqualTo(com.nexusai.application.agent.tool.SessionStorage
                .getProjectDir(real).resolve(creatingSessionUuid + ".jsonl"));
    }

    @Test
    @DisplayName("transcript 纯 sessionId 解析：null sessionId → 路径 null（headless 无 transcript）")
    void transcriptNullSessionId_resolvesNullPath() throws Exception {
        // WHY（规则九）: DURABLE 创建会话已关 → RunRequest.sessionId=null → SessionStorage 三 seam
        // 返回 null → 消费方跳过写 transcript（不产生创建会话/GLOBAL 文件）。纯 sessionId 解析下
        // null 即"无 transcript"的载体 —— RED: 若 GLOBAL 兜底，headless fire 落 GLOBAL.jsonl 共享污染。
        Path real = Files.createDirectories(tempDir.resolve("proj-durable-null")).toRealPath();
        Path transcript = com.nexusai.application.agent.tool.SessionStorage.getTranscriptPath(
            real, null);
        assertThat(transcript)
            .as("sessionId=null → transcript 路径 null（headless 无 transcript）")
            .isNull();
    }
}

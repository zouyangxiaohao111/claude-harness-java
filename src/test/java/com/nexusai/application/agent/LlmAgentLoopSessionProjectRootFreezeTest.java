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
 * （计数 mock）→ 反射调用 private {@code resolveSessionProjectRoot()}。
 */
@DisplayName("[IMP-A F1] resolveSessionProjectRoot 会话级冻结：首 run 冻结、会话内不重查 DB")
class LlmAgentLoopSessionProjectRootFreezeTest {

    private static final String SESSION_ID = "sess-f1-freeze";

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        SessionProjectRoot.reset();
        AutoMemPaths.resetCurrentProjectRoot();
    }

    private static void invokeResolve(LlmAgentLoop loop) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("resolveSessionProjectRoot");
        m.setAccessible(true);
        m.invoke(loop);
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
        assertThat(AutoMemPaths.currentSessionProjectRoot())
            .as("F7: ThreadLocal 注入值必须与 workspaceDir 一致（NFC+realpath）")
            .isEqualTo(real.toString());
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
        assertThat(AutoMemPaths.currentSessionProjectRoot())
            .as("F1: 冻结命中路径 ThreadLocal 注入归一值")
            .isEqualTo(real.toString());
    }

    @Test
    @DisplayName("resolver 未注入且未冻结 → 保持现状回落（不炸、workspaceDir 不动）")
    void noResolver_noFrozen_keepsDefault() throws Exception {
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        loop.setStreamContext(null, SESSION_ID, "msg-1");
        Path before = loop.workspaceDir();

        invokeResolve(loop);

        assertThat(loop.workspaceDir()).isEqualTo(before);
        assertThat(AutoMemPaths.currentSessionProjectRoot())
            .as("未注入未冻结 → ThreadLocal 未被触碰，回落链（CLAUDE_PROJECT_DIR env ?? config-home）恒有值")
            .isNotNull()
            .isNotBlank();
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

    // ============ 批次乙 cron-mem: DURABLE cron 项目身份 override（boundProject 整体注入） ============

    @Test
    @DisplayName("批次乙 cron-mem: cronProjectRootOverride（DURABLE 项目身份）→ streamSessionId=null 空守卫也注入 boundProject，不冻结 SessionProjectRoot")
    void cronProjectRootOverride_injectsBeforeNullGuard_withoutFreeze() throws Exception {
        // WHY（规则九）: CC durable cron fire 把 prompt 塞回创建会话命令队列（useScheduledTasks.ts:71-82
        // enqueueForLead，不新建会话/无全局会话）→ 该回合 memory 归属创建项目 projectRoot git root
        // （cronTasks.ts:74-83 文件位置锚 → paths.ts:223-235 getAutoMemPath）。DURABLE cron 的
        // streamSessionId 恒 null → 旧 resolveSessionProjectRoot 在 null 守卫提前 return → memory/
        // workspaceDir 落 CLAUDE_PROJECT_DIR env ?? config-home（全局，偏离 CC）。override 检查必须
        // 放在 null 守卫【之前】→ workspaceDir + AutoMemPaths ThreadLocal 同时锚 boundProject；
        // 不冻结 SessionProjectRoot（GLOBAL_SESSION_UUID 是所有 DURABLE 任务的共享兜底键，
        // 冻结会造成跨项目 memory 污染）。RED: 删除 override 首行检查 → 本测试在 streamSessionId=null
        // 下恒走空守卫 return，workspaceDir/ThreadLocal 断言变红。
        Path real = Files.createDirectories(tempDir.resolve("proj-cron")).toRealPath();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        // 不 setStreamContext → streamSessionId=null（DURABLE cron 形态）
        loop.setCronProjectRootOverride(tempDir.resolve("proj-cron").toString());

        invokeResolve(loop);

        assertThat(loop.workspaceDir())
            .as("override 非空 → workspaceDir 锚 boundProject（realpath 归一，对齐 :7349-7350 注入点）")
            .isEqualTo(real);
        assertThat(AutoMemPaths.currentSessionProjectRoot())
            .as("override 非空 → memory ThreadLocal 锚 boundProject（AutoMemPaths 落 projects/<gitRoot>/memory）")
            .isEqualTo(real.toString());
        assertThat(SessionProjectRoot.getForSession(SESSION_ID))
            .as("override 路径不得冻结 SessionProjectRoot（GLOBAL 兜底键防跨任务污染）")
            .isNull();
    }

    @Test
    @DisplayName("批次乙 cron-mem: override 未置（SESSION/普通路径）→ 既有会话解析不变（resolver 仍被调用、照常冻结）")
    void noOverride_sessionPathUnchanged() throws Exception {
        // WHY（规则九）: override 默认 null → 不得触碰 SESSION 路径。cron-mem 批次只对 DURABLE
        // boundProject 非空时注入（ScheduleService 仅 DURABLE 存 bound_project 列）；SESSION fire /
        // 普通会话 run 必须保持既有 streamSessionId 解析（resolver 调用 / 冻结）零变化。RED: 若 override
        // 检查误放错位置（如意外短路 session 分支）→ resolver 计数/冻结断言变红。
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
    @DisplayName("cronProjectRootOverride 只锚 workspaceDir/memory；transcript 纯 sessionId 解析归创建会话（无 override 残留）")
    void cronProjectRootOverride_setsWorkspaceDir_transcriptResolvesBySessionId() throws Exception {
        // WHY（规则九 · 测试验证意图）: DURABLE fire 项目身份注入（cronProjectRootOverride）只锚
        // workspaceDir + AutoMemPaths（批次乙 cron-mem）；transcript 键由 RunRequest.sessionId
        // （CronIdleExecutor 存活判定后传创建会话 UUID）经 SessionStorage 纯 sessionId 解析 →
        // {boundProject}/{创建会话UUID}.jsonl。已删 per-task 虚拟键 override companion ——
        // RED: 若残留 override 注入，transcript 落虚拟键文件而非创建会话文件 → 变红。
        Path real = Files.createDirectories(tempDir.resolve("proj-durable-s")).toRealPath();
        String creatingSessionUuid =
            com.nexusai.common.SessionKeys.canonicalUuid("sess-1234abcd").toString();
        LlmAgentLoop loop = new LlmAgentLoop(Mockito.mock(LlmProviderFactory.class));
        // 不 setStreamContext → streamSessionId=null（DURABLE cron 形态）
        loop.setCronProjectRootOverride(tempDir.resolve("proj-durable-s").toString());

        invokeResolve(loop);

        assertThat(loop.workspaceDir())
            .as("cronProjectRootOverride → workspaceDir 锚 boundProject（项目身份注入）")
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

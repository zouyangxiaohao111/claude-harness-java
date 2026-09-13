package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [批 3b] hook 域会话态<b>显式传参</b>在<b>真实派生线程</b>上的实证（含反向对照）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：批 3b 删除了三处「派生线程内读 {@code RequestContext.sessionId()}
 * （MDC / ThreadLocal）」：
 * <ol>
 *   <li>{@link CommandHookExecutor#enrichBaseFields} —— session_id 回退源由 MDC 改为显式载体链
 *       {@code event.sessionId() ?? parentTuc.sessionId()}；</li>
 *   <li>{@link CommandHookExecutor#resolveSpawnCwd} —— 唯一源 = {@code event.sessionId()}；</li>
 *   <li>{@code SkillImprovementHook.applySkillImprovement} —— baseDir 由
 *       {@code () -> getCwd(RequestContext.sessionId())} 改为显式 sessionId 入参
 *       （方法体在 {@code CompletableFuture.runAsync} 的 ForkJoinPool 派生线程执行）。</li>
 * </ol>
 * 本测试的关键不是「传了参数」，而是证明 <b>ambient 路线在该线程上取不到值</b>：
 * <ul>
 *   <li>断言求值线程 ≠ 断言线程（真派生线程）；</li>
 *   <li>在该线程上 <b>故意设置</b>「上一个任务残留的、别的会话的」MDC（第三态）⇒ 断言结果
 *       <b>不</b>来自它 —— 这是旧实现必红的反向对照（旧实现读 MDC 会拿到残留会话）；</li>
 *   <li>断言结果 == 显式传入值。</li>
 * </ul>
 *
 * <p>注意：本测试<b>不</b>设置「调用方线程」的 MDC 就断言派生线程读不到（那只证明 ThreadLocal
 * 不传播，是 JDK 常识）；真正有鉴别力的是<b>在派生线程本身上写残留值</b>（模拟池化线程复用），
 * 旧实现会据此串会话。
 */
@DisplayName("批 3b · hook 域会话态显式传参（真实派生线程 + 残留 MDC 反向对照）")
class HookSessionExplicitDerivedThreadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 派生线程池（等价 tool-exec / HOOK_EXECUTOR 的池化语义：线程复用、ThreadLocal 可能残留）。 */
    private static final ExecutorService DERIVED = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "batch3b-derived");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        SessionProjectRoot.reset();
    }

    /** 把任务丢到派生线程执行并等结果（断言线程 ≠ 执行线程）。 */
    private static <T> T onDerivedThread(java.util.function.Supplier<T> body) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        DERIVED.execute(() -> {
            try {
                out.set(body.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("派生线程任务 10s 未完成");
        }
        if (err.get() != null) {
            throw new AssertionError("派生线程任务抛异常", err.get());
        }
        return out.get();
    }

    private static ToolUseContext ctxOf(String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId);
    }

    // ════════════════════════════════════════════════════════════════════
    // ① enrichBaseFields：显式载体（parentTuc.sessionId）而非 MDC
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("① enrichBaseFields 在派生线程求值：注入的 session_id 来自 parentTuc，派生线程上的残留 MDC 不参与")
    void enrichBaseFields_usesExplicitTucSession_notResidualMdc() throws Exception {
        // event 自带 sessionId=null（hook 发射线程没带上下文）→ 唯一显式源 = parentTuc.sessionId()
        HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode(), null, null);
        ToolUseContext tuc = ctxOf("sess-EXPLICIT");

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> mdcOnThreadBefore = new AtomicReference<>();
        HookEvent enriched = onDerivedThread(() -> {
            threadName.set(Thread.currentThread().getName());
            // 反向对照：在该<b>池化派生线程</b>上写「上一个任务残留的、别的会话的」sessionId
            //   （MDC 第三态）。旧实现读 MDC → 会把它当成本次 hook 的 session_id（静默串会话）。
            RequestContext.set("sess-STALE-THIRD-STATE", "msg-stale");
            mdcOnThreadBefore.set(RequestContext.sessionId());
            try {
                return CommandHookExecutor.enrichBaseFields(event, tuc);
            } finally {
                RequestContext.clear();
            }
        });

        assertThat(threadName.get())
            .as("必须在派生线程（池线程）求值，而非断言线程")
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(mdcOnThreadBefore.get())
            .as("前置条件：派生线程上确实存在残留 MDC（第三态），本用例的鉴别力来自它")
            .isEqualTo("sess-STALE-THIRD-STATE");
        assertThat(enriched.sessionId())
            .as("session_id 必须来自显式载体 parentTuc.sessionId()；旧实现（读 MDC）会得到 sess-STALE-THIRD-STATE")
            .isEqualTo("sess-EXPLICIT");
    }

    @Test
    @DisplayName("①b enrichBaseFields：event.sessionId() 优先于 parentTuc；无显式源时即便当前线程有 MDC 也省略")
    void enrichBaseFields_eventSessionWins_overTuc() {
        HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-EVENT", null);
        HookEvent enriched = CommandHookExecutor.enrichBaseFields(event, ctxOf("sess-TUC"));
        assertThat(enriched.sessionId())
            .as("event 顶层已有值优先（REQ-06 单值约束），不读 MDC")
            .isEqualTo("sess-EVENT");

        // 无任何显式源（parentTuc=null）→ 省略。反向对照：本线程**有** MDC，
        //   旧实现 `if (sessionId == null) sessionId = RequestContext.sessionId();` 会产出该值。
        HookEvent bare = HookEvent.toolPre("Bash", JSON.createObjectNode(), null, null);
        RequestContext.set("sess-MDC-ONLY", "msg-mdc-only");
        try {
            HookEvent noneEnriched = CommandHookExecutor.enrichBaseFields(bare, null);
            assertThat(noneEnriched.sessionId())
                .as("无显式源 → session_id 省略（旧实现会回落 MDC 得到 sess-MDC-ONLY）；"
                    + "缺值不伪造，仅记 WARN")
                .isNull();
        } finally {
            RequestContext.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ② resolveSpawnCwd：唯一源 = event.sessionId()
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("② resolveSpawnCwd 在派生线程求值：cwd 取自 event.sessionId() 的会话绑定项目，残留 MDC 的会话不参与")
    void resolveSpawnCwd_usesEventSession_notResidualMdc(@TempDir Path tmp) throws Exception {
        Path explicitProject = Files.createDirectories(tmp.resolve("explicit-project"));
        Path staleProject = Files.createDirectories(tmp.resolve("stale-project"));
        SessionProjectRoot.setForSession("sess-EXPLICIT", explicitProject.toString());
        SessionProjectRoot.setForSession("sess-STALE-THIRD-STATE", staleProject.toString());
        try {
            HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode(), "sess-EXPLICIT", null);
            AtomicReference<String> threadName = new AtomicReference<>();
            String resolved = onDerivedThread(() -> {
                threadName.set(Thread.currentThread().getName());
                RequestContext.set("sess-STALE-THIRD-STATE", "msg-stale"); // 残留第三态
                try {
                    return CommandHookExecutor.resolveSpawnCwd(event);
                } finally {
                    RequestContext.clear();
                }
            });

            assertThat(threadName.get())
                .as("resolveSpawnCwd 实际跑在 HOOK_EXECUTOR 类池线程上（此处用等价派生线程复现）")
                .isNotEqualTo(Thread.currentThread().getName());
            assertThat(resolved)
                .as("spawn cwd 必须来自 event.sessionId() 的绑定项目；旧实现（MDC 回退）会得到残留会话的 %s",
                    staleProject)
                .isEqualTo(CwdResolution.normalizeCwd(explicitProject.toString()));
        } finally {
            SessionProjectRoot.clearSession("sess-EXPLICIT");
            SessionProjectRoot.clearSession("sess-STALE-THIRD-STATE");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 生产链端到端：HookRegistry.executePreToolUse → enrich（调用线程）+ resolveSpawnCwd（池线程）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("③ 生产链：configured hook 的 stdin session_id 与 spawn cwd 均取自显式 TUC 会话（调用线程残留 MDC 不参与）")
    void configuredHook_endToEnd_explicitSessionReachesStdinAndCwd(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        SessionProjectRoot.setForSession("sess-PROD", project.toString());
        try {
            CapturingExecutor stub = new CapturingExecutor();
            HookRegistry registry = registryWithConfiguredHook(stub);
            ToolUseContext tuc = ctxOf("sess-PROD");

            // 调用线程写残留 MDC（别的会话）—— 生产链上不读取（反向对照）
            RequestContext.set("sess-CALLER-STALE", "msg-stale");
            try {
                registry.executePreToolUse("Bash", JSON.createObjectNode(), tuc, "tu-3b");
            } finally {
                RequestContext.clear();
            }

            assertThat(stub.capturedJsonInput.get())
                .as("hook stdin 的 session_id 必须来自显式 TUC 会话（批 3b：MDC 回退已删）")
                .isNotNull()
                .contains("\"session_id\":\"sess-PROD\"")
                .doesNotContain("sess-CALLER-STALE");
            assertThat(stub.capturedHookCwd.get())
                .as("hook spawn cwd = event.sessionId() 的会话绑定项目（HOOK_EXECUTOR 池线程求值）")
                .isEqualTo(CwdResolution.normalizeCwd(project.toString()));
            assertThat(stub.executedOnThread.get())
                .as("configured hook 必须在 HOOK_EXECUTOR 池线程执行（证明上述取值发生在派生线程）")
                .isNotNull()
                .contains("nexusai-hook-");
        } finally {
            SessionProjectRoot.clearSession("sess-PROD");
        }
    }

    /** 捕获 stdin / hookCwd / 执行线程的 stub（不启动真实进程）。 */
    static class CapturingExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();
        final AtomicReference<String> capturedHookCwd = new AtomicReference<>();
        final AtomicReference<String> executedOnThread = new AtomicReference<>();

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution, AbortController parentAbort,
                                         long defaultTimeoutMs, String hookCwd) {
            executedOnThread.set(Thread.currentThread().getName());
            capturedHookCwd.set(hookCwd);
            capturedJsonInput.set(jsonInput);
            return new CommandHookResult("{}", "", jsonInput, 0, false, false);
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                         String jsonInput, String pluginRoot, String pluginId,
                                         String skillRoot, Integer hookIndex,
                                         boolean forceSyncExecution, AbortController parentAbort) {
            executedOnThread.set(Thread.currentThread().getName());
            capturedJsonInput.set(jsonInput);
            return new CommandHookResult("{}", "", jsonInput, 0, false, false);
        }
    }

    private static HookRegistry registryWithConfiguredHook(CommandHookExecutor executor) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.PRE_TOOL_USE,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(executor);
        return registry;
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ SkillImprovement：ForkJoinPool 派生线程 + 显式 sessionId 驱动 baseDir
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("④ applySkillImprovement：baseDir 求值发生在 ForkJoinPool 派生线程，键 = 显式 sessionId（残留 MDC 不参与）")
    void skillImprovement_baseDirFromExplicitSession_onForkJoinThread(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("skill-proj"));
        Path skillDir = project.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# original");

        AtomicReference<String> evalThread = new AtomicReference<>();
        AtomicReference<String> mdcOnEvalThread = new AtomicReference<>("<未求值>");
        AtomicReference<String> sessionSeen = new AtomicReference<>();
        SkillImprovementHook hook = new SkillImprovementHook(
            (systemPrompt, llm, options) -> "<updated_file># rewritten</updated_file>",
            ctx -> java.util.Optional.empty(),
            new com.nexusai.application.agent.telemetry.Telemetry(),
            (skillName, updates) -> {},
            sid -> {
                evalThread.set(Thread.currentThread().getName());
                mdcOnEvalThread.set(RequestContext.sessionId());
                sessionSeen.set(sid);
                return project;
            },
            null,
            false);

        RequestContext.set("sess-STALE-THIRD-STATE", "msg-stale"); // 调用线程残留（不传播到 ForkJoinPool）
        try {
            hook.applySkillImprovement("sess-EXPLICIT", "my-skill",
                List.of(new SkillImprovementHook.SkillUpdate("s", "c", "r"))).join();
        } finally {
            RequestContext.clear();
        }

        assertThat(sessionSeen.get())
            .as("baseDir 求值必须收到显式传入的 sessionId（旧实现是无参 supplier，只能读 MDC）")
            .isEqualTo("sess-EXPLICIT");
        assertThat(evalThread.get())
            .as("apply 体在 ForkJoinPool 派生线程执行（非断言线程）")
            .isNotNull()
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(mdcOnEvalThread.get())
            .as("派生线程上 RequestContext.sessionId() 必须为 null —— 证明 baseDir 不来自 ambient 路线")
            .isNull();
        assertThat(Files.readString(skillDir.resolve("SKILL.md")))
            .as("写回落在显式会话对应的项目目录（证明 baseDir 真的用了显式 sessionId）")
            .isEqualTo("# rewritten");
    }

    @Test
    @DisplayName("④b 生产注入点：@Autowired 构造的 baseDirForSession(sessionId) 必须按该会话解析 cwd（产出端覆盖）")
    void skillImprovement_productionBaseDirMapping(@TempDir Path tmp) throws Exception {
        // WHY（覆盖「产出端」）：④ 用测试自建的 baseDirForSession 观察「消费端拿到 sessionId」，
        //   但生产 lambda（@Autowired 构造内 `sessionId -> Path.of(CwdResolution.getCwd(sessionId))`）
        //   若写错（例如仍旧读 MDC），④ 仍会绿 —— 本用例补上产出端的真实映射断言。
        Path project = Files.createDirectories(tmp.resolve("prod-mapping-proj"));
        SessionProjectRoot.setForSession("sess-PROD-MAP", project.toString());
        try {
            // 生产构造（LlmProviderFactory 未装配 → modelQuery 为 warn+skip 空实现，不触达 LLM）
            SkillImprovementHook hook = new SkillImprovementHook(
                new com.nexusai.infra.llm.LlmProviderFactory(),
                new com.nexusai.application.agent.telemetry.Telemetry(),
                null, null, null, null, false);
            java.lang.reflect.Field f = SkillImprovementHook.class.getDeclaredField("baseDirForSession");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Function<String, Path> prodMapping = (Function<String, Path>) f.get(hook);
            assertThat(prodMapping).as("生产构造必须装配 baseDirForSession（非 null）").isNotNull();
            assertThat(prodMapping.apply("sess-PROD-MAP"))
                .as("产出端：baseDirForSession(sessionId) 必须解析该会话的 cwd（boundProject）")
                .isEqualTo(Path.of(CwdResolution.getCwd("sess-PROD-MAP")));
            assertThat(prodMapping.apply("sess-PROD-MAP").toString())
                .as("且确实命中 boundProject=%s（不是 user.dir 兜底）", project)
                .isEqualTo(CwdResolution.normalizeCwd(project.toString()));
        } finally {
            SessionProjectRoot.clearSession("sess-PROD-MAP");
        }
    }

    @Test
    @DisplayName("④c applySkillImprovement：sessionId 缺省 → 抛（缺值 fail-loud，不静默回落 user.dir）")
    void skillImprovement_blankSession_failsLoud() {
        SkillImprovementHook hook = new SkillImprovementHook(
            (systemPrompt, llm, options) -> "",
            ctx -> java.util.Optional.empty(),
            new com.nexusai.application.agent.telemetry.Telemetry(),
            (skillName, updates) -> {},
            sid -> Path.of("."),
            null,
            false);

        assertThatThrownBy(() -> hook.applySkillImprovement(null, "my-skill", List.of()))
            .as("null sessionId → IllegalArgumentException（会话态一律显式传参）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> hook.applySkillImprovement("   ", "my-skill", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

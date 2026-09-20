package com.nexusai.application.agent.permission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.source.InitialPermissionModeSource;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tasks.CronIdleExecutor;
import com.nexusai.application.agent.tasks.MainSessionBackgroundService;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.util.AutoModeState;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [批 A4e] <b>权限模式「null 传递」健壮性</b> —— 来源标注（{@link PermissionModeSource}）的
 * 消费点守护与生产调用点来源装配。
 *
 * <h2>缺陷（本批要抓的第三态）</h2>
 * <p>{@code RunRequest.permissionModeCli == null} 有两个<b>合法</b>身份（「没有覆盖」⇒ 回落全局；
 * 「CLI 确实没给」⇒ {@code auto} opt-in 判断依据，CC main.tsx:1409），外加一个<b>缺陷身份</b>
 * 「调用方本该解析会话选定模式却没解析」。三者数据上完全同形 ⇒ 批 A4b 的
 * {@code CronIdleExecutor} 缺陷（走硬编码 {@code null} 的便捷重载 ⇒ 整轮静默绕过权限检查）
 * <b>静默穿越三个批次</b>。本批加显式来源标注 + 消费点守护把它变成可检的。
 *
 * <h2>RED 条件（硬指标 · 反向实验见批 A4e 报告）</h2>
 * <ul>
 *   <li><b>层 1 = 守护的消费点</b>（{@code LlmAgentLoop.guardPermissionModeSource} 的<b>调用</b>）：
 *       删掉 {@code doRun} 里的调用 ⇒ {@link #notApplicableWithRealSessionWarns()} /
 *       {@link #debugLevelNeverSatisfiesWarnAssertion()} 红。</li>
 *   <li><b>层 1' = 守护的打点级别</b>：把守护的 {@code log.warn} 改成 {@code log.debug}
 *       ⇒ {@link #notApplicableWithRealSessionWarns()} 红（本用例断言的是
 *       {@code hasAtLeast(appender, Level.WARN, ...)}，<b>DEBUG 不满足</b> —— 这正是上一批
 *       「WARN→DEBUG 变异溜过去」缺口的堵法）。</li>
 *   <li><b>层 2 = 来源标注的取值</b>（硬编码 null 重载声明的来源）：把 {@code RunRequest} 7 参
 *       {@code session(...)} 里的 {@code NOT_APPLICABLE} 改成 {@code CLI_ARGUMENT}
 *       ⇒ {@link #notApplicableWithRealSessionWarns()} 红（守护不再触发）。</li>
 *   <li><b>层 2' = 生产调用点的来源装配</b>：把 {@code CronIdleExecutor} 的
 *       {@code sessionFromSessionOverride} 退回任一「硬编码 null permissionModeCli」的便捷重载
 *       ⇒ {@link #cronDrainSinglePathDeclaresSessionOverrideSource()} 红。</li>
 * </ul>
 *
 * <h2>⛔ 不回归约束（硬约束 1）</h2>
 * <p>守护<b>只打日志不改值</b>：{@link #autoOptInIntentSurvivesNullCli()} 钉死
 * 「{@code permissionModeCli == null} 且 settings.defaultMode==auto ⇒ {@code autoModeFlagCli} 置位」
 * —— 一旦有人把 {@code null} 换成「解析后的全局值」，该用例立即红（且 auto 链路静默失效）。
 */
@DisplayName("[批 A4e] 权限模式来源标注：消费点守护 + 生产调用点来源装配")
class PermissionModeSourceGuardTest {

    private static final String SESSION = "sess-a4e0001";
    /** 守护 WARN 的判别串（同时用于守护的 WARN 与 DEBUG 两个分支 —— 便于「同串跨级别」对照）。 */
    private static final String GUARD_NEEDLE = "[批 A4e] 权限模式来源标注";

    private Logger loopLogger;
    private Level savedLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // 强制 DEBUG：让守护的「合法路径」分支也进入 appender —— 才能断言
        // 「同一判别串在 DEBUG 下不满足 hasAtLeast(WARN)」（WARN/DEBUG 可区分）。
        loopLogger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        savedLevel = loopLogger.getLevel();
        loopLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        loopLogger.addAppender(appender);
        AutoModeState.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        loopLogger.detachAppender(appender);
        loopLogger.setLevel(savedLevel);
        AutoModeState.resetForTesting();
    }

    // ═════════════════════ ① 消费点守护（真 doRun 入口） ═════════════════════

    @Test
    @DisplayName("A4e-①: source=CLI_ARGUMENT + 值 plan ⇒ 不 WARN（正常路径不得误报）")
    void resolvedSourceWithValueDoesNotWarn() {
        // WHY: 反向断言 —— 正常路径（ChatService 型：已解析 per-call ?? 会话 override）不得被守护误报。
        //   RED: 若守护的条件写成「source != NOT_APPLICABLE ⇒ WARN」则本用例红。
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(tucRef, null, false);

        loop.run(RunRequest.session("hello", SESSION, null, ProviderConfig.empty(), "test-model",
            null, null, null, "plan", false, null));

        assertThat(guardWarns()).as("已解析且带值的正常路径不得出现守护 WARN").isEmpty();
        assertThat(tucRef.get()).isNotNull();
        assertThat(tucRef.get().permissionMode())
            .as("守护只打日志不改值：初始 mode 仍按 CLI 槽解析为 PLAN")
            .isEqualTo(PermissionMode.PLAN);
    }

    @Test
    @DisplayName("A4e-②: 同一串在 DEBUG 下不满足 hasAtLeast(WARN)（WARN/DEBUG 可区分 —— 堵变异缺口）")
    void debugLevelNeverSatisfiesWarnAssertion() {
        // WHY: 上一批的缺口是「把 WARN 改成 DEBUG，断言『有日志』仍绿」。本用例把判别串同时压在
        //   两个级别上做对照：DEBUG 分支命中（hasAtLeast(DEBUG) 真）但 hasAtLeast(WARN) 假。
        //   RED: 若 ListAppender 判据退化为「有日志即可」（不看级别）则本用例红。
        LlmAgentLoop loop = newLoop(new AtomicReference<>(), null, false);

        // CLI_ARGUMENT + null + 真实会话 = 「该槽确实为空」的合法路径 ⇒ 守护走 DEBUG 分支
        loop.run(RunRequest.session("hello", SESSION, null, ProviderConfig.empty(), "test-model",
            null, null, null, null, false, null));

        assertThat(hasAtLeast(appender, Level.DEBUG, GUARD_NEEDLE))
            .as("合法路径必须有 DEBUG 留痕（证明守护确实被执行到，本用例非空转）")
            .isTrue();
        assertThat(hasAtLeast(appender, Level.WARN, GUARD_NEEDLE))
            .as("同一判别串出现在 DEBUG ⇒ ⛔ 不得满足 hasAtLeast(WARN)（WARN 与 DEBUG 必须可区分）")
            .isFalse();
        assertThat(guardWarns()).isEmpty();
    }

    @Test
    @DisplayName("A4e-③: source=NOT_APPLICABLE 却携带真实会话 ⇒ ≥WARN（「忘了传」的可检形态）")
    void notApplicableWithRealSessionWarns() {
        // WHY: 本批核心守护。批 A4b 的缺陷形态 = 调用点为了省事走「硬编码 permissionModeCli=null」
        //   的便捷重载，而该 run 其实带着真实会话 ⇒ 该轮回落全局 settings.permission_mode
        //   （本机实测可 = bypassPermissions）⇒ 静默绕过全部权限检查（含 deny）。
        //   RED: ① 删 doRun 的守护调用；② 把守护的 log.warn 改 log.debug；
        //        ③ 把 7 参 session(...) 的来源标注从 NOT_APPLICABLE 改 CLI_ARGUMENT。
        LlmAgentLoop loop = newLoop(new AtomicReference<>(), null, false);

        // 7 参便捷重载 = 硬编码 permissionModeCli=null（来源标注 NOT_APPLICABLE）× 真实会话
        loop.run(RunRequest.session("hello", SESSION, null, ProviderConfig.empty(), "test-model",
            null, null));

        assertThat(hasAtLeast(appender, Level.WARN, GUARD_NEEDLE))
            .as("声明「不承载会话权限语义」却携带真实会话 ⇒ 结构性矛盾，必须 ≥WARN 留痕（不许静默）")
            .isTrue();
        assertThat(guardWarns().stream().anyMatch(m -> m.contains("NOT_APPLICABLE")))
            .as("WARN 必须点名矛盾的两侧（来源标注 + sessionId），便于运维定位调用点")
            .isTrue();
    }

    @Test
    @DisplayName("A4e-④: source=NOT_APPLICABLE 且无会话 ⇒ 合法，不 WARN")
    void notApplicableWithoutSessionDoesNotWarn() {
        // WHY: 反向断言 —— 无会话的 run（verify / 主线程 / 测试夹具）本就无权限模式来源，
        //   不得被守护误报（噪声会淹没真问题）。
        LlmAgentLoop loop = newLoop(new AtomicReference<>(), null, false);

        loop.run(RunRequest.forTest("hello", "test-model", null));

        assertThat(guardWarns()).as("无会话 + NOT_APPLICABLE 是合法组合，不得 WARN").isEmpty();
    }

    @Test
    @DisplayName("A4e-⑤: auto opt-in 不回归 —— permissionModeCli==null 且 settings.defaultMode=auto ⇒ 置 autoModeFlagCli")
    void autoOptInIntentSurvivesNullCli() {
        // WHY: 硬约束 1（CC main.tsx:1409 第三腿）：
        //   `!permissionModeCli && isDefaultPermissionModeAuto()` ⇒ autoModeFlagCli=true。
        //   守护「只打日志不改值」的全部意义就在此 —— 一旦有人把 null 换成「解析后的全局值」，
        //   autoModeIntent 的第三腿永久失效（auto 链路静默失效），本用例立即红。
        // 构造：classifier 开（autoModeGate.isEnabled）+ circuit broken（resolver 折叠 auto→default，
        //   使 autoModeIntent 只能靠第三腿成立）+ settings.defaultMode=auto + CLI 槽 null。
        AutoModeState.setAutoModeCircuitBroken(true);
        LlmAgentLoop loop = newLoop(new AtomicReference<>(), "auto", true);

        loop.run(RunRequest.session("hello", SESSION, null, ProviderConfig.empty(), "test-model",
            null, null, null, null, false, null));

        assertThat(AutoModeState.getAutoModeFlagCli())
            .as("permissionModeCli==null && settings.defaultMode==auto ⇒ autoModeFlagCli 必须置位"
                + "（CC main.tsx:1409；若把 null 替换为解析后的全局值则本断言红）")
            .isTrue();
    }

    @Test
    @DisplayName("A4e-⑥: auto opt-in 对照 —— CLI 槽给了显式非 auto 值 ⇒ 不得置 autoModeFlagCli")
    void explicitCliValueSuppressesAutoOptIn() {
        // WHY: 对照组（证明 ⑤ 的断言有判别力，不是恒真）：显式 CLI 值 ⇒ autoModeIntent 三腿全假。
        AutoModeState.setAutoModeCircuitBroken(true);
        LlmAgentLoop loop = newLoop(new AtomicReference<>(), "auto", true);

        loop.run(RunRequest.session("hello", SESSION, null, ProviderConfig.empty(), "test-model",
            null, null, null, "plan", false, null));

        assertThat(AutoModeState.getAutoModeFlagCli())
            .as("显式 CLI --permission-mode plan ⇒ autoModeIntent 三腿全假 ⇒ 不得置位")
            .isFalse();
    }

    // ═════════════ ② 生产调用点的来源装配（审计表的机械落点） ═════════════

    @Test
    @DisplayName("A4e-⑦: CronIdleExecutor drain 单条 ⇒ source=SESSION_OVERRIDE + 值=会话选定模式")
    void cronDrainSinglePathDeclaresSessionOverrideSource() {
        // WHY: 审计表里「会话 override 槽」的调用点，来源标注必须是 SESSION_OVERRIDE（而非复用了
        //   CLI 槽语义的 13 参重载）。RED: 把该处退回任意硬编码 null permissionModeCli 的便捷重载
        //   （值也会一并丢失）或改成 CLI_ARGUMENT ⇒ 本断言红。
        NotificationQueue queue = new NotificationQueue();
        CronIdleExecutor executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
        SessionRecord rec = new SessionRecord();
        rec.setPermissionMode("plan");
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(rec);
        ReflectionTestUtils.setField(executor, "sessionMapper", mapper);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", item("队列任务", SESSION, null));

        RunRequest req = capturedRun(loop);
        assertThat(req.permissionModeCli()).isEqualTo("plan");
        assertThat(req.permissionModeSource())
            .as("cron drain 的值来自 sessions.permission_mode 列 ⇒ 来源必须标 SESSION_OVERRIDE")
            .isEqualTo(PermissionModeSource.SESSION_OVERRIDE);
    }

    @Test
    @DisplayName("A4e-⑧: CronIdleExecutor drain 批量（sessionBatch）⇒ source=SESSION_OVERRIDE")
    void cronDrainBatchPathDeclaresSessionOverrideSource() {
        // WHY: 同一 run 两条 drain 子路径（批量 sessionBatch / 单条 session）两条都必须标对来源
        //   —— 批 A4b 的教训就是「只覆盖一侧」。
        NotificationQueue queue = new NotificationQueue();
        CronIdleExecutor executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
        SessionRecord rec = new SessionRecord();
        rec.setPermissionMode("acceptEdits");
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(rec);
        ReflectionTestUtils.setField(executor, "sessionMapper", mapper);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        ReflectionTestUtils.invokeMethod(executor, "runAgentLoop",
            item("批量任务", SESSION, null), null, List.of("第二条通知"));

        RunRequest req = capturedRun(loop);
        assertThat(req.permissionModeCli()).isEqualTo("acceptEdits");
        assertThat(req.permissionModeSource()).isEqualTo(PermissionModeSource.SESSION_OVERRIDE);
    }

    @Test
    @DisplayName("A4e-⑨: 主会话后台化（残留 R2）⇒ source=SESSION_OVERRIDE + 值=会话选定模式")
    void mainSessionBackgroundDeclaresSessionOverrideSource() {
        // WHY: A4b 残留 R2 —— 主会话后台化派生查询跑的是同一个会话，却走了硬编码 null 的便捷重载
        //   ⇒ 静默回落全局 settings.permission_mode。本批一并修，来源标注与 cron drain 同源。
        //   RED: 把 MainSessionBackgroundService 的 sessionFromSessionOverride 退回
        //   session(userPrompt, sessionUuid, agentUuid, cfg, modelName, null, null, taskBudget)
        //   ⇒ value/permissionModeSource 双断言红。
        LlmAgentLoop loop = backgroundHarness("plan");

        RunRequest req = capturedRun(loop);
        assertThat(req.permissionModeCli())
            .as("后台派生查询必须携带会话选定模式（不得回落全局 settings.permission_mode）")
            .isEqualTo("plan");
        assertThat(req.permissionModeSource()).isEqualTo(PermissionModeSource.SESSION_OVERRIDE);
    }

    @Test
    @DisplayName("A4e-⑩: 主会话后台化取不到会话行 ⇒ source=SESSION_OVERRIDE + 值 null + ≥WARN 留痕")
    void mainSessionBackgroundMissingSessionRowWarns() {
        // WHY: 「不许静默失效」——取不到会话时必须 ≥WARN（区别于「会话存在但未设 override」的正常末态）。
        Logger svcLogger = (Logger) LoggerFactory.getLogger(MainSessionBackgroundService.class);
        ListAppender<ILoggingEvent> svcAppender = new ListAppender<>();
        svcAppender.start();
        svcLogger.addAppender(svcAppender);
        try {
            LlmAgentLoop loop = backgroundHarness(null);   // mapper 查无该行
            List<String> warns = svcAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("会话行不存在"))
                .collect(Collectors.toList());
            assertThat(warns)
                .as("取不到会话行 ⇒ 回落全局 settings 必须 ≥WARN 可观测（⛔ 禁止只 DEBUG / 静默）")
                .isNotEmpty();
            RunRequest req = capturedRun(loop);
            assertThat(req.permissionModeCli()).isNull();
            assertThat(req.permissionModeSource())
                .as("取不到会话仍属「会话 override 槽」（来源不变，只是该槽取不到值）")
                .isEqualTo(PermissionModeSource.SESSION_OVERRIDE);
        } finally {
            svcLogger.detachAppender(svcAppender);
        }
    }

    // ═════════════ ③ 「不许省」的机械落点（紧凑构造器强校验） ═════════════

    @Test
    @DisplayName("A4e-⑪: permissionModeSource 为 null ⇒ 紧凑构造器立即抛（⛔ 不给默认值，逼作者思考）")
    void nullSourceIsRejectedByCompactConstructor() {
        // WHY: 派单书要求「硬编码 null 的便捷重载必须显式声明来源，⛔ 不要给默认值让它可以省」——
        //   本断言是该要求的**机械落点**：任何新增构造点/工厂若漏声明来源，会在构造时立即抛，
        //   而不是静默退化成某个默认枚举（静默失效）。
        //   RED: 删掉 RunRequest 紧凑构造器里的 permissionModeSource null 校验 ⇒ 本用例红。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RunRequest(
            "hi", null, "m", QuerySource.USER,
            null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null, null))
            .as("每个 RunRequest 构造点必须显式声明 permissionModeSource（无默认值可省）")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("permissionModeSource");
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** 主会话后台化装配（镜像 MainSessionBackgroundService*Test 的既有式样：同步 executor + mock loop）。 */
    private static LlmAgentLoop backgroundHarness(String sessionPermissionMode) {
        SdkEventQueue sdkEventQueue = new SdkEventQueue();
        TaskFrameworkService taskFrameworkService = new TaskFrameworkService(sdkEventQueue);
        MainSessionBackgroundService service = new MainSessionBackgroundService();
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> loopProvider = mock(ObjectProvider.class);
        when(loopProvider.getObject()).thenReturn(loop);
        SessionMapper mapper = mock(SessionMapper.class);
        SessionRecord rec = sessionPermissionMode == null ? null : new SessionRecord();
        if (rec != null) {
            rec.setPermissionMode(sessionPermissionMode);
        }
        when(mapper.selectOneById(any())).thenReturn(rec);
        ReflectionTestUtils.setField(service, "taskFrameworkService", taskFrameworkService);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        ReflectionTestUtils.setField(service, "sdkEventQueue", sdkEventQueue);
        ReflectionTestUtils.setField(service, "notificationQueue", new NotificationQueue());
        ReflectionTestUtils.setField(service, "backgroundExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "sessionMapper", mapper);
        // chatService 不注入（@Autowired(required=false)）→ 跳过落库武装（非本用例关注点，保持最小装配）

        service.startBackgroundSession(
            SESSION, "bg query", List.of(), null, "hi", "mock-fast", ProviderConfig.empty(), null);
        return loop;
    }

    /** 建 12 参 QueueItem（同 CronIdleDrainPermissionModeTest 式样）。 */
    private static QueueItem item(String value, String sessionId, String boundProject) {
        return new QueueItem(value, "prompt", Priority.LATER, null, null, true,
            NotificationQueue.WORKLOAD_CRON, false, null, sessionId, boundProject, null);
    }

    private static RunRequest capturedRun(LlmAgentLoop loop) {
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        return captor.getValue();
    }

    /**
     * 本用例集内 LlmAgentLoop logger 上出现过的 <b>守护</b> WARN 文本。
     *
     * <p>⚠️ 只筛 {@link #GUARD_NEEDLE}：{@code doRun} 本身还有若干与本批无关的 WARN
     * （auto-memory 无会话 / ModelConfigResolver 未注入 等）—— 若断言「logger 上无任何 WARN」
     * 会被这些无关告警污染成假红。<b>断言要盯住被测的那一条</b>。
     */
    private List<String> guardWarns() {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m != null && m.contains(GUARD_NEEDLE))
            .collect(Collectors.toList());
    }

    /**
     * 「级别 ≥ {@code level} 且含 {@code needle}」判据（复用本仓既有口径：
     * {@code LocalSettingsGitignoreTest.hasAtLeast} / {@code PermissionPersistFailureFailLoudTest}）。
     *
     * <p>刻意用 {@code isGreaterOrEqual} 而非「级别 ==」/「有日志即可」—— 这是
     * 「WARN 被降级成 DEBUG 却仍判绿」缺口的唯一堵法（见 A4e-② 对照用例）。
     */
    private static boolean hasAtLeast(ListAppender<ILoggingEvent> appender, Level level, String needle) {
        return appender.list.stream().anyMatch(e ->
            e.getLevel().isGreaterOrEqual(level)
                && e.getFormattedMessage() != null
                && e.getFormattedMessage().contains(needle));
    }

    /**
     * 装配真实 loop + mocked provider（镜像 {@code RevFix2ProductionInputWiringTest.newLoop}）。
     *
     * @param tucRef     捕获 per-turn ToolUseContext（可传 new AtomicReference<>() 忽略）
     * @param settingsDefaultMode 注入 settings 磁盘 meta 的 defaultMode（null = 无 settings 源）
     * @param classifierOn 注入 autoModeGate.isEnabled()（true = TRANSCRIPT_CLASSIFIER 门开）
     */
    private static LlmAgentLoop newLoop(AtomicReference<ToolUseContext> tucRef,
                                        String settingsDefaultMode, boolean classifierOn) {
        LlmProvider provider = mock(LlmProvider.class);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        ReflectionTestUtils.setField(loop, "permissionContextBuilder", new PermissionContextBuilder());
        // settings 源：InitialPermissionModeSource.resolveInput(...) → 固定 Input（settings 层）
        InitialPermissionModeSource source = mock(InitialPermissionModeSource.class);
        // ⚠️ 本 mock 只负责「settings 层」取值（defaultMode）；CLI 槽必须**原样透传**调用方实参
        //   —— 用 thenReturn 固定 Input 会把 permissionModeCli 一并钉成 null，使被测语义失真。
        // ⚠️ 第 3 参是原生 boolean ⇒ 必须 anyBoolean()（用 any() 会 NPE：拆箱 null）
        when(source.resolveInput(any(), any(), anyBoolean())).thenAnswer(inv ->
            new InitialPermissionModeResolver.Input(
                inv.getArgument(1), inv.getArgument(2), settingsDefaultMode, false));
        ReflectionTestUtils.setField(loop, "initialPermissionModeSource", source);
        // classifier 门：autoModeGate.isEnabled()（决定 LlmAgentLoop 是否进入 autoModeIntent 判定）
        AutoModeGate gate = mock(AutoModeGate.class);
        when(gate.isEnabled()).thenReturn(classifierOn);
        ReflectionTestUtils.setField(loop, "autoModeGate", gate);
        doAnswer(inv -> {
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from A4e wiring");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return loop;
    }
}

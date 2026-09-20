package com.nexusai.application.agent.tasks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [批 A4b] 队列 drain 起轮的<b>权限模式传递</b>守护测试。
 *
 * <h2>缺陷（已实证，本测试守它）</h2>
 * <p>{@code CronIdleExecutor} 队列 drain 起轮原先走 {@code RunRequest} 的<b>无 permissionModeCli
 * 便捷重载</b>（{@code session(...)} 10 参 / {@code sessionBatch(...)} 7 参），这两个重载把
 * {@code permissionModeCli} <b>硬编码为 {@code null}</b> ⇒ {@code InitialPermissionModeResolver}
 * 回落 settings 槽（DB 全局 {@code settings.permission_mode}；本机实测 = {@code bypassPermissions}）
 * ⇒ <b>整轮静默绕过全部权限检查（含 deny）</b>。而 HTTP 入口
 * {@code ChatService.processUserMessage:983-991} 是<b>传</b>的（{@code per-call ?? 会话 override}）
 * ⇒ 两条路径行为不一致（同一能力两套判据）。
 *
 * <h2>修复语义</h2>
 * <p>drain 侧取<b>会话选定模式</b>，与 ChatService <b>共用同一判据点</b>
 * {@code ChatService.resolveEffectivePermissionMode}（drain 无 per-call ⇒ 传 null）。
 * <b>拿不到会话时 ≥WARN 显式留痕</b>（⛔ 不许静默回落全局）。
 *
 * <h2>反向实验（强制）</h2>
 * <p>把 {@code runAgentLoop} 里两处 {@code effectivePermissionMode} 改回 {@code null}
 * ⇒ {@link #drainRunCarriesSessionSelectedPermissionMode} 与 {@link #batchDrainPathCarriesSessionSelectedPermissionMode}
 * 必变红（见批 A4b 报告）。
 */
class CronIdleDrainPermissionModeTest {

    private static final String SESSION_ID = "sess-a4b00001";

    private NotificationQueue queue;
    private CronIdleExecutor executor;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    /** 建 10 参 QueueItem（前 10 个 record 组件；attachments/boundProject/scheduleId 默认）。 */
    private static QueueItem item(String value, String sessionId, String boundProject) {
        // 12 参形态：value, mode, priority, agentId, uuid, isMeta, workload, skipSlashCommands,
        //            origin, sessionId, boundProject, scheduleId
        return new QueueItem(value, "prompt", Priority.LATER, null, null, true,
            NotificationQueue.WORKLOAD_CRON, false, null, sessionId, boundProject, null);
    }

    private LlmAgentLoop stubLoop() {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        return loop;
    }

    private void stubSession(String sessionId, String permissionMode) {
        SessionRecord rec = new SessionRecord();
        rec.setPermissionMode(permissionMode);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(rec);
        ReflectionTestUtils.setField(executor, "sessionMapper", mapper);
    }

    private static RunRequest capturedRun(LlmAgentLoop loop) {
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        return captor.getValue();
    }

    // ───────────────────────── 正向：模式必须被带上 ─────────────────────────

    @Test
    @DisplayName("A4b: 队列 drain 单条路径 ⇒ permissionModeCli = 会话选定模式（不得回落全局）")
    void drainRunCarriesSessionSelectedPermissionMode() {
        // WHY: 会话选定 plan ⇒ drain 轮的 RunRequest.permissionModeCli 必须是 "plan"。
        // 回归（原缺陷）: 走 10 参便捷重载 ⇒ 该字段硬编码 null ⇒ 回落全局 settings
        // （本机实测 bypassPermissions）⇒ 整轮绕过权限检查。
        stubSession(SESSION_ID, "plan");
        LlmAgentLoop loop = stubLoop();

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", item("队列任务", SESSION_ID, null));

        RunRequest req = capturedRun(loop);
        assertThat(req.permissionModeCli())
            .as("drain 轮必须携带会话选定模式；null = 回落全局 settings.permission_mode（本机实测可为 "
                + "bypassPermissions ⇒ 静默绕过全部权限检查，含 deny）")
            .isEqualTo("plan");
        // 对照组：不得凭空把 dangerouslySkip 置 true（新增绕过面）
        assertThat(req.dangerouslySkipPermissions())
            .as("drain 侧无 per-call 来源 ⇒ dangerouslySkipPermissions 恒 false")
            .isFalse();
    }

    @Test
    @DisplayName("A4b: 队列 drain 批量路径（sessionBatch）同样携带会话选定模式")
    void batchDrainPathCarriesSessionSelectedPermissionMode() {
        // WHY: 同一 run 有两条 drain 子路径（批量 sessionBatch / 单条 session），两条都必须带模式。
        // ⛔ 上一批栽在「只覆盖一侧」—— 本用例专门守 sessionBatch 那一侧。
        stubSession(SESSION_ID, "acceptEdits");
        LlmAgentLoop loop = stubLoop();

        ReflectionTestUtils.invokeMethod(executor, "runAgentLoop",
            item("批量任务", SESSION_ID, null), null, List.of("第二条通知"));

        RunRequest req = capturedRun(loop);
        assertThat(req.permissionModeCli())
            .as("sessionBatch 路径同样必须携带会话选定模式（只修单条路径 = 只覆盖一侧）")
            .isEqualTo("acceptEdits");
    }

    @Test
    @DisplayName("A4b: 会话存在但 permission_mode 为空 ⇒ null（三态链正常末态，回落全局属设计语义）")
    void sessionWithoutOverrideYieldsNullButIsNotSilent() {
        stubSession(SESSION_ID, null);
        LlmAgentLoop loop = stubLoop();

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", item("队列任务", SESSION_ID, null));

        assertThat(capturedRun(loop).permissionModeCli())
            .as("会话未设 override ⇒ null ⇒ resolver 回落全局；这是设计的三态链末态，不是静默失效")
            .isNull();
    }

    // ───────────────────── 反向：拿不到会话必须 ≥WARN ─────────────────────

    @Test
    @DisplayName("A4b: 无会话（NO_SESSION 哨兵）⇒ ≥WARN 留痕，不得静默回落全局")
    void noSessionWarnsInsteadOfSilentGlobalFallback() {
        stubLoop();   // 无 sessionId ⇒ resolveSessionUuid 返回 GLOBAL_SESSION_KEY(=NO_SESSION)
        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", item("全局任务", null, null));

            List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("确无会话"))
                .collect(Collectors.toList());
            assertThat(warns)
                .as("拿不到会话 ⇒ 回落全局 settings 必须 ≥ WARN 可观测（⛔ 禁止只 DEBUG / 静默）")
                .isNotEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("A4b: DURABLE headless（sessionUuid=null）⇒ ≥WARN 留痕")
    void headlessDurableWarnsInsteadOfSilentGlobalFallback() throws Exception {
        // DURABLE 创建会话已关（sessionMapper 查无该行）且带项目锚 ⇒ sessionUuid=null（headless）。
        Path tmp = Files.createTempDirectory("a4b-headless-mode");
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(null);
        ReflectionTestUtils.setField(executor, "sessionMapper", mapper);
        stubLoop();

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop",
                item("持久化任务", SESSION_ID, tmp.toAbsolutePath().toString()));

            List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("拿不到会话"))
                .collect(Collectors.toList());
            assertThat(warns)
                .as("headless（sessionUuid=null）⇒ 无法取会话模式，必须 ≥ WARN 显式留痕")
                .isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("A4b: 会话行不存在 ⇒ ≥WARN 留痕 + permissionModeCli=null")
    void missingSessionRowWarns() {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(null);   // 会话已删
        ReflectionTestUtils.setField(executor, "sessionMapper", mapper);
        LlmAgentLoop loop = stubLoop();

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", item("队列任务", SESSION_ID, null));

            List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("会话行不存在"))
                .collect(Collectors.toList());
            assertThat(warns)
                .as("会话行不可读 ⇒ 必须 ≥ WARN（区别于「会话存在但无 override」的正常末态）")
                .isNotEmpty();
            assertThat(capturedRun(loop).permissionModeCli()).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}

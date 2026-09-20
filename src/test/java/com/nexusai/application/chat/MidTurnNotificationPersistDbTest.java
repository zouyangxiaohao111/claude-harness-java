package com.nexusai.application.chat;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [C2 2026-09-19] mid-turn 注入的<b>通知</b>必须落库（RAW content + queued_origin + is_meta），
 * 且同一 uuid <b>只落一条</b>。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 把 mid-turn 注入的排队命令/通知落 transcript
 * —— 落库过滤器 {@code isLoggableMessage} 只滤 progress / 非 ant attachment，<b>不看 isMeta</b>
 * （本机真实 CC transcript 实测有 queued_command attachment 在盘）。nexusai 原实现「仅 busy-queued
 * 登记 registry，task-notification/coordinator/channel/cron 不落库，仅 state 暂态」⇒
 * <b>子代理/后台任务跑完的通知在 F5 / resume 后永久消失</b>。原依据
 * （messages.ts:3753-3756「isMeta ⇒ 不落库」）系误引 —— 那里只讲 isMeta 用于 UI 隐藏。
 *
 * <p><b>RED tooth（反向实验）</b>：删掉 LlmAgentLoop drain 里「mid-turn 注入项登记 registry」段
 * ⇒ 该通知不落库 → 断言 ② 变红；把 ChatService 两条落库路径的 isMeta 改回硬编码 false ⇒
 * 断言 ④（is_meta=true）变红。
 *
 * <p>harness 与 {@link BusyQueuedAttachmentSnapshotDbTest} 同源：真实 SQLite（Flyway 迁移到
 * 临时文件 · 绝不碰用户真库）+ 真实 MessageService + 真实 ChatService 实时落库 + 真实 queryLoop。
 */
@DisplayName("[C2] mid-turn 通知落库（RAW + queued_origin + is_meta）且不重复落库")
class MidTurnNotificationPersistDbTest {

    private static final String NOTIF_XML =
        "<task-notification>\n"
            + "<task-id>t-c2-anchor</task-id>\n"
            + "<task-type>monitor</task-type>\n"
            + "<status>completed</status>\n"
            + "<summary>Background command \"ls\" completed</summary>\n"
            + "</task-notification>\n";

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private String session;
    private MessageService messageService;
    private ChatService chatService;
    private NotificationQueue queue;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "midturn-notif-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("flex.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        session = "sess-midturn-notif-" + UUID.randomUUID().toString().substring(0, 8);

        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("mid-turn notification persist");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);

        // 真实 MessageService（仅替换 mapper，无 Spring 上下文）
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        // 真实 ChatService（实时落库宿主）+ 真实 NotificationQueue
        queue = new NotificationQueue();
        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
        ReflectionTestUtils.setField(chatService, "notificationQueue", queue);
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
    }

    @Test
    @DisplayName("工具轮入队的 NEXT 通知 → drain 注入即实时落库（RAW + queued_origin=task-notification + is_meta=true）；补落路径幂等不重复")
    void midTurnNotification_persistedRawWithOriginAndIsMeta_singleRow() {
        String originalId = "msg-orig-" + UUID.randomUUID().toString().substring(0, 8);
        messageService.createQueuedUserMessage(session, originalId, "hello", OffsetDateTime.now(), false);

        AgentState state = new AgentState("sys", session, null);
        state.appendMessage(new ChatMessageDto(
            originalId, session, Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        // 生产同构：run 开始后由 ChatService 武装实时落库（appendListener）
        chatService.armRealTimePersist(state, session, null, null, originalId);

        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            if (call == 0) {
                // 工具轮期间入队一条 NEXT 通知（monitor 通知同款：uuid=null）
                queue.enqueue(new NotificationQueue.QueueItem(
                    NOTIF_XML, NotificationQueue.MODE_TASK_NOTIFICATION, NotificationQueue.Priority.NEXT,
                    null, null, false, null, false, null, session));
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode input = json.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("need tool", "tool_calls",
                    List.of(new ToolUseBlock("toolu_c2", "Bash", input)), null, null));
            } else {
                onChunk.accept("final answer");
                onMsg.accept(new AssistantMessage("final answer", "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = ctxWithQueue(factory, queue);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), session)
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", 8, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());

        // ── 断言 ① 场景完整性 + 登记载体 ──
        assertThat(callCount.get()).as("工具轮 → 收尾 = 2 次 LLM 调用").isEqualTo(2);
        assertThat(state.injectedQueuedMessages()).as("[C2] 通知须登记 registry").hasSize(1);
        String notifId = state.injectedQueuedMessages().get(0).uuid();
        assertThat(notifId).as("uuid 非空（队列项 uuid=null → 用产出消息自身 id）").isNotBlank();

        // ── 断言 ② ★C2 核心判据：DB 里查得到该通知行（RAW content） ──
        MessageRecord row = rowById(notifId);
        assertThat(row).as("★ mid-turn 通知必须落库（原实现：仅 busy-queued 落库，通知 resume 后永久消失）")
            .isNotNull();
        assertThat(row.getContent()).as("DB content = RAW XML（壳不落库，请求组装时生成）").isEqualTo(NOTIF_XML);
        assertThat(row.getQueuedOrigin()).as("queued_origin = task-notification（resume 发送层据此重包壳）")
            .isEqualTo("task-notification");
        assertThat(row.getRole()).as("role=user").isEqualTo("user");

        // ── 断言 ③ 同一 uuid 只一条（实时落库 + 补落路径不得双写） ──
        assertThat(countById(notifId)).as("同一 uuid 恒一条").isEqualTo(1);

        // ── 断言 ④ is_meta=true（resume 后 UI 隐藏，与 live 语义一致） ──
        assertThat(Boolean.TRUE.equals(row.getIsMeta()))
            .as("★ is_meta=true：resume 后前端 MessageList 按 isMeta 隐藏（原硬编码 false ⇒ 通知变用户气泡）")
            .isTrue();

        // ── 断言 ⑤ resume 读回：queuedOrigin/isMeta/内容 三件套如实还原 ──
        ChatMessageDto re = findById(messageService.listRawForTranscript(session), notifId);
        assertThat(re).as("resume 重拉必须能拿到该通知行").isNotNull();
        assertThat(re.content()).isEqualTo(NOTIF_XML);
        assertThat(re.queuedOrigin()).isEqualTo("task-notification");
        assertThat(re.isMeta()).as("resume 后仍 isMeta=true（UI 隐藏）").isTrue();

        // ── 断言 ⑥ 反向：轮末补落（persistInjectedQueuedMessages）幂等 —— 不产生第二条 ──
        ReflectionTestUtils.invokeMethod(chatService, "persistInjectedQueuedMessages", state, session);
        assertThat(countById(notifId)).as("补落路径 existsById 幂等跳过 ⇒ 仍恒一条（无重复落库）")
            .isEqualTo(1);
        assertThat(findById(messageService.listRawForTranscript(session), notifId).content())
            .as("补落不得改写 content（仍 RAW）").isEqualTo(NOTIF_XML);
    }

    /**
     * [F1 2026-09-19] <b>纯兜底落库路径</b>（appendListener <b>未武装</b>）⇒ 轮末
     * {@code persistInjectedQueuedMessages} 是唯一写入者 ⇒ {@code inj.isMeta()} 真实承重。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：通知场景下实时路径（appendListener → persistAppendedMessage
     * user 分支）<b>先落库</b>、轮末补落被 {@code existsById} 跳过 ⇒ 两条
     * {@code createQueuedUserMessage(...)} 的 {@code isMeta} 参数<b>从不执行</b>（本批变异 D：
     * 把两处 {@code inj.isMeta()} 改回硬编码 {@code false} ⇒ 全批 5 类零红 ⇒ 覆盖缺口）。
     * 但它<b>不是死代码</b>：{@code ChatService.run} 注释自陈的两条兜底路径（appendListener
     * 未执行 / 漏落）下它是<b>唯一写入者</b>，此时 isMeta 承重（错则通知 resume 后按普通 user
     * 气泡渲染，与 live 的 UI 隐藏语义相悖）。
     *
     * <p><b>RED tooth（反向实验）</b>：把 {@code persistInjectedQueuedMessages} 里 6 参 / 8 参
     * 两处 {@code inj.isMeta()} 改回硬编码 {@code false} ⇒ 断言 ④ / ⑤ 变红（分别对应两条重载）。
     * 只改其中一处 ⇒ 该处对应断言变红（两处各有独立断言，互不代偿）。
     */
    @Test
    @DisplayName("[F1] 不武装 appendListener ⇒ 轮末补落是唯一写入者：is_meta 按 inj.isMeta() 落（6 参 + 8 参两条路径）")
    void fallbackPersist_withoutAppendListener_isMetaFromRegistry() {
        final String CRON_XML = "<cron-prompt>nightly build</cron-prompt>";
        final ChatMessageDto.UserAttachmentInfo ATT =
            new ChatMessageDto.UserAttachmentInfo("path", "nightly.pdf", "application/pdf",
                "cid-nightly-1", "file:///tmp/nightly.pdf");

        String originalId = "msg-fb-orig-" + UUID.randomUUID().toString().substring(0, 8);
        messageService.createQueuedUserMessage(session, originalId, "hello", OffsetDateTime.now(), false);

        AgentState state = new AgentState("sys", session, null);
        state.appendMessage(new ChatMessageDto(
            originalId, session, Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        // ⛔ 故意<b>不</b>调 chatService.armRealTimePersist(...) —— 本用例测的就是「没有实时落库」的
        //   兜底路径（= ChatService.run 注释自陈的「无 listener 场景」）。

        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            if (call == 0) {
                // 工具轮期间入队两条 NEXT 项（此刻 turn-0 drain 已过）：
                //   ① task-notification（uuid=null、无附件）→ 轮末补落走 6 参重载，isMeta 公式 = true
                queue.enqueue(new NotificationQueue.QueueItem(
                    NOTIF_XML, NotificationQueue.MODE_TASK_NOTIFICATION, NotificationQueue.Priority.NEXT,
                    null, null, false, null, false, null, session));
                //   ② cron 排队项（mode=prompt + workload=cron）携<b>非图片附件快照</b> ⇒ 走 8 参重载
                //      （生产唯一携快照的 origin 是 busy-queued，但 busy 恒 isMeta=false；
                //        本项用 cron 使 8 参分支的 isMeta=true 腿可达 —— 见返回契约的语义说明）
                queue.enqueue(new NotificationQueue.QueueItem(
                    CRON_XML, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
                    null, null, false, NotificationQueue.WORKLOAD_CRON, false, null, session,
                    null, null, null, List.of(ATT)));
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode input = json.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("need tool", "tool_calls",
                    List.of(new ToolUseBlock("toolu_f1", "Bash", input)), null, null));
            } else {
                onChunk.accept("final answer");
                onMsg.accept(new AssistantMessage("final answer", "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = ctxWithQueue(factory, queue);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), session)
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", 8, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());

        // ── 断言 ① 场景完整性 + 两条注入项均已登记 registry ──
        assertThat(callCount.get()).as("工具轮 → 收尾 = 2 次 LLM 调用").isEqualTo(2);
        assertThat(state.injectedQueuedMessages()).as("两条 mid-turn 注入项均登记").hasSize(2);
        AgentState.InjectedQueuedMessage notifInj = state.injectedQueuedMessages().stream()
            .filter(m -> "task-notification".equals(m.queuedOrigin())).findFirst().orElse(null);
        AgentState.InjectedQueuedMessage cronInj = state.injectedQueuedMessages().stream()
            .filter(m -> "cron".equals(m.queuedOrigin())).findFirst().orElse(null);
        assertThat(notifInj).as("task-notification 项已登记").isNotNull();
        assertThat(cronInj).as("cron 项已登记").isNotNull();
        String notifId = notifInj.uuid();
        String cronId = cronInj.uuid();
        assertThat(notifInj.userAttachments()).as("6 参腿：无附件快照").isNullOrEmpty();
        assertThat(cronInj.userAttachments()).as("8 参腿：携附件快照").hasSize(1);
        assertThat(notifInj.isMeta()).as("task-notification isMeta=true").isTrue();
        assertThat(cronInj.isMeta()).as("cron isMeta=true").isTrue();

        // ── 断言 ② ★路径证据：实时写入者缺席 ⇒ 此刻 DB 无这两行（补落路径还未被调用） ──
        assertThat(rowById(notifId)).as("未武装 appendListener ⇒ 实时路径未落库（本用例前提成立）").isNull();
        assertThat(rowById(cronId)).as("未武装 appendListener ⇒ 实时路径未落库（本用例前提成立）").isNull();

        // ── 计数器证据：spy 记录两条重载各被调用一次（且 isMeta 实参=true） ──
        MessageService spyMs = Mockito.spy(messageService);
        ReflectionTestUtils.setField(chatService, "messageService", spyMs);

        // ── 执行轮末补落（= processUserMessage 收口所调；state 无 listener ⇒ 它是唯一写入者） ──
        ReflectionTestUtils.invokeMethod(chatService, "persistInjectedQueuedMessages", state, session);

        // ── 断言 ③ 两条重载各被执行一次，且 isMeta 实参 = true（路径确实走到的直接证据） ──
        Mockito.verify(spyMs, Mockito.times(1)).createQueuedUserMessage(
            org.mockito.ArgumentMatchers.eq(session), org.mockito.ArgumentMatchers.eq(notifId),
            org.mockito.ArgumentMatchers.eq(NOTIF_XML), org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
            org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq("task-notification"));
        Mockito.verify(spyMs, Mockito.times(1)).createQueuedUserMessage(
            org.mockito.ArgumentMatchers.eq(session), org.mockito.ArgumentMatchers.eq(cronId),
            org.mockito.ArgumentMatchers.eq(CRON_XML), org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
            org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq("cron"),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // ── 断言 ④ 6 参腿：is_meta 按 inj.isMeta() 落 true（变异 → false ⇒ 本断言变红） ──
        MessageRecord nRow = rowById(notifId);
        assertThat(nRow).as("★ 纯兜底路径必须落库").isNotNull();
        assertThat(nRow.getContent()).as("content = RAW XML").isEqualTo(NOTIF_XML);
        assertThat(nRow.getQueuedOrigin()).as("queued_origin = task-notification").isEqualTo("task-notification");
        assertThat(Boolean.TRUE.equals(nRow.getIsMeta()))
            .as("★ [6 参腿] is_meta 必须 = inj.isMeta() = true（硬编码 false ⇒ 通知 resume 后当用户气泡）")
            .isTrue();

        // ── 断言 ⑤ 8 参腿（携附件）：is_meta 同样按 inj.isMeta() 落 true + 附件快照落 V63 列 ──
        MessageRecord cRow = rowById(cronId);
        assertThat(cRow).as("★ 纯兜底路径（8 参腿）必须落库").isNotNull();
        assertThat(cRow.getContent()).as("content = RAW").isEqualTo(CRON_XML);
        assertThat(cRow.getQueuedOrigin()).as("queued_origin = cron").isEqualTo("cron");
        assertThat(Boolean.TRUE.equals(cRow.getIsMeta()))
            .as("★ [8 参腿] is_meta 必须 = inj.isMeta() = true（硬编码 false ⇒ 本断言变红）")
            .isTrue();
        assertThat(cRow.getUserAttachments()).as("附件快照落 V63 列（8 参腿）").contains("cid-nightly-1");

        // ── 断言 ⑥ 同一 uuid 各恒一条（补落不重复） ──
        assertThat(countById(notifId)).as("同一 uuid 恒一条").isEqualTo(1);
        assertThat(countById(cronId)).as("同一 uuid 恒一条").isEqualTo(1);

        // ── 断言 ⑦ resume 读回：isMeta 如实还原（UI 隐藏语义可复原） ──
        assertThat(findById(messageService.listRawForTranscript(session), notifId).isMeta()).isTrue();
        assertThat(findById(messageService.listRawForTranscript(session), cronId).isMeta()).isTrue();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** 真实 NotificationQueue 注入位置 4 的最小 AgentLoopContext（同 OdD2 helper）。 */
    private AgentLoopContext ctxWithQueue(LlmProviderFactory factory, NotificationQueue queue) {
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1 toolRegistry
            null, null, queue, null,                              // 2-5
            null, null, null, null,                               // 6-9
            null, factory, null, null, null, null,                // 10-15
            null, null, null, null,                               // 16-19
            FeatureFlags.ALL_DISABLED, null, null, null, null, null,   // 20-25
            null, null, null, null, null, null, null);            // 26-32
    }

    private MessageRecord rowById(String id) {
        for (MessageRecord r : messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", session).orderBy("seq", true))) {
            if (id.equals(r.getId())) {
                return r;
            }
        }
        return null;
    }

    private long countById(String id) {
        return messageMapper.selectListByQuery(
                QueryWrapper.create().eq("session_id", session)).stream()
            .filter(r -> id.equals(r.getId()))
            .count();
    }

    private static ChatMessageDto findById(List<ChatMessageDto> msgs, String id) {
        for (ChatMessageDto m : msgs) {
            if (m != null && id.equals(m.id())) {
                return m;
            }
        }
        return null;
    }
}

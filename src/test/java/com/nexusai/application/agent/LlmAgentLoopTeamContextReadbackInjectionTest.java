package com.nexusai.application.agent;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [C1] 会话 teamContext doRun 回灌注入测试 · 对齐 CC appState.teamContext 会话恢复
 * （attachments.ts:3625-3647 + reconnection.ts:75-119）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>C1 是读侧单向断头路</b> —— LlmAgentLoop prototype 每 send 新实例 ⇒ appStateRef 恒空
 *       （:610-611），队长跨轮拿不到「我是队长」⇒ {@code AgentLoopContext.maybeInjectTeammateMailbox}
 *       的门控 2/3 读不到 {@code appState.teamContext} ⇒ <b>队长永远读不到队员消息</b>。
 *       sessions.team_context（V39 列）是跨 send 唯一真源，doRun 入口必须回灌。</li>
 *   <li><b>回灌必须是 todos 回读的「兄弟语句」，不能嵌进 todos 分支</b> —— 会话从未 TodoWrite 时
 *       todos 列为空，若回灌写进 {@code if (!persistedTodos.isEmpty())} 之内就会被「todos 为空」劫持，
 *       <b>C1 在多数会话仍死</b>（计划 §六#10 明列的最易踩坑）。第 2 条用例专钉这一点。</li>
 *   <li><b>回灌必须真的让 inbox 变成可消费</b> —— 只断言「appState 里有 teamContext」不足以证明
 *       修复：真正要恢复的能力是队长轮能把队员消息注入 LLM 上下文（构建后才标已读）。</li>
 *   <li><b>身份 ≠ 消费资格（C1 收口）</b> —— 回灌让同会话每个 run 都拿到「我是队长」身份，但
 *       inbox 消费资格由 per-run 的 {@code setTeammateInboxConsumer} 独立门控，唯一置位处 =
 *       {@code ChatService}（交互式前台用户回合）。否则 cron / 后台 run 会抢先 markRead 把队员
 *       消息吃掉（症状与 C1 未修相同、且时序相关更难查）。</li>
 * </ol>
 *
 * <p><b>测试基建</b>：复用 {@code LlmAgentLoopTodosReadbackInjectionTest} 同款真实 run 模式
 * （裸 {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop）+ {@code setSessionMapper}
 * 注入 mock；inbox 夹具复用 {@code TeammateMailboxAttachmentInjectTest} 的
 * {@code nexusai.task.config-dir} + {@code nexusai.experimental.agent-teams} 系统属性。
 */
@DisplayName("[C1] 会话 teamContext doRun 回灌注入（队长跨轮身份）")
class LlmAgentLoopTeamContextReadbackInjectionTest {

    private static final String SESSION = "sess-abcdef01";
    private static final String TEAM = "c1-readback-team";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.experimental.agent-teams");
        System.clearProperty("nexusai.task.config-dir");
        System.clearProperty("nexusai.team.name");
        TaskSystemConfig.clearForTest();
    }

    // ── 夹具（对齐 LlmAgentLoopTodosReadbackInjectionTest:48-76）──

    /** provider 首调返回 stop 纯文本 → loop 正常退出。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    private static LlmAgentLoop loopWithSessionMapper(SessionMapper sessionMapper) {
        return buildLoop(sessionMapper, true);
    }

    /**
     * 非交互 run 形态：模拟 {@code CronIdleExecutor} / {@code MainSessionBackgroundService}
     * （经 {@code RunRequest.session} 起 loop，但**不**置位 inbox 消费资格）。
     */
    private static LlmAgentLoop backgroundLoop(SessionMapper sessionMapper) {
        return buildLoop(sessionMapper, false);
    }

    private static LlmAgentLoop buildLoop(SessionMapper sessionMapper, boolean inboxConsumer) {
        LlmProvider provider = stopProvider("c1 readback response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        if (sessionMapper != null) {
            loop.setSessionMapper(sessionMapper);
        }
        // 置位 = ChatService:983 前台交互式回合（唯一置位处）；不置位 = cron / 后台 run
        if (inboxConsumer) {
            loop.setTeammateInboxConsumer(true);
        }
        return loop;
    }

    private static SessionMapper mapperReturning(SessionRecord session) {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(session);
        return sessionMapper;
    }

    private static SessionRecord sessionWith(String todos, String teamContext) {
        SessionRecord session = new SessionRecord();
        session.setTodos(todos);
        session.setTeamContext(teamContext);
        return session;
    }

    private static void runOnce(LlmAgentLoop loop) {
        loop.run(RunRequest.session("c1 query", SESSION, null,
            ProviderConfig.empty(), "test-model", null, null));
    }

    // ═══════════════════════ 回灌 ═══════════════════════

    /**
     * 【C1 核心】sessions.team_context 列已持久化（TeamCreateTool.java:438-439 写）→ 新 run 的 doRun
     * 入口回灌 appStateRef.teamContext。若回灌回归丢失，队长跨轮读不到 teamContext ⇒
     * maybeInjectTeammateMailbox 门控 2/3 直接 return ⇒ 队员消息永远进不了队长上下文。
     */
    @Test
    @DisplayName("[C1] sessions.team_context 已持久化 → doRun 回灌注入 appStateRef.teamContext")
    void persistedTeamContextReadbackInjectedIntoAppState() {
        LlmAgentLoop loop = loopWithSessionMapper(mapperReturning(
            sessionWith(null, "{\"teamName\":\"" + TEAM + "\",\"leadAgentId\":\"team-lead\"}")));

        runOnce(loop);

        Object tc = loop.getAppStateSnapshot().get("teamContext");
        assertThat(tc)
            .as("doRun 回灌必须注入 appStateRef.teamContext（队长跨轮唯一身份来源）")
            .isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) tc).get("teamName"))
            .as("回灌内容必须来自 sessions.team_context 列（跨 send 真源）")
            .isEqualTo(TEAM);
        assertThat(((Map<?, ?>) tc).get("leadAgentId")).isEqualTo("team-lead");
    }

    /**
     * 【决定性反向断言 · 计划 §P0-4 最易踩坑】todos 列 <b>null</b>（会话从未 TodoWrite）但
     * team_context 已持久化 ⇒ teamContext <b>仍必须</b>回灌。
     *
     * <p>若实现把回灌写进 {@code if (!persistedTodos.isEmpty())} 之内，本例必红 —— 而这正是
     * 「多数会话」的常态（大多数团队会话从不 TodoWrite）⇒ C1 会被静默修死。
     */
    @Test
    @DisplayName("[C1 关键] todos 列为 null（从未 TodoWrite）→ teamContext 仍必须回灌（不得被 todos 空劫持）")
    void todosColumnEmpty_teamContextStillInjected() {
        LlmAgentLoop loop = loopWithSessionMapper(mapperReturning(
            sessionWith(null, "{\"teamName\":\"" + TEAM + "\"}")));

        runOnce(loop);

        Map<String, Object> snapshot = loop.getAppStateSnapshot();
        assertThat(snapshot.get("todos"))
            .as("前置条件：todos 列 null ⇒ 本 run 的 todos 回读被跳过")
            .isNull();
        assertThat(snapshot.get("teamContext"))
            .as("todos 为空绝不能阻断 teamContext 回灌 —— 回灌必须是 todos 回读的兄弟语句，"
                + "否则 C1 在「从未 TodoWrite」的多数会话仍死")
            .isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) snapshot.get("teamContext")).get("teamName")).isEqualTo(TEAM);
    }

    /** 【负向】team_context 列 null（非团队会话）→ 不注入该键、不崩。 */
    @Test
    @DisplayName("[C1] sessions.team_context 列 null → 不注入 teamContext 键不崩（非团队会话空态）")
    void nullTeamContextColumn_noInjectionNoCrash() {
        LlmAgentLoop loop = loopWithSessionMapper(mapperReturning(sessionWith(null, null)));

        runOnce(loop);

        assertThat(loop.getAppStateSnapshot().get("teamContext"))
            .as("非团队会话（team_context null）→ 不注入 teamContext 键（空态，不崩）")
            .isNull();
    }

    /** 【容错】未注入 SessionMapper（POJO 单测 / 单体工具场景）→ 跳过回灌不 NPE。 */
    @Test
    @DisplayName("[C1] 未注入 SessionMapper → 跳过回灌不 NPE（sessionMapper null 容错）")
    void sessionMapperNull_noCrash() {
        LlmAgentLoop loop = loopWithSessionMapper(null);

        runOnce(loop);

        assertThat(loop.getAppStateSnapshot()).as("sessionMapper==null → 跳过回灌，run 正常完成不崩").isNotNull();
        assertThat(loop.getAppStateSnapshot().get("teamContext")).isNull();
    }

    // ═══════════ 后果断言：回灌真的让队长 inbox 可消费（C1 要恢复的能力） ═══════════

    /**
     * 【C1 主判据 · 单元层可达形式】队员发一条<b>普通</b>消息 ⇒ 队长下一轮 turn 必须能读到它。
     *
     * <p>断言链（缺一不可）：① sessions.team_context 有值 ⇒ ② 回灌后 appStateRef 有 teamContext ⇒
     * ③ 该 run 的 LLM 调用前 inbox 未读消息被构建注入并<b>标已读</b>（«构建后才标已读»，
     * 对齐 CC attachments.ts:3769-3796）。
     *
     * <p>改动前：② 缺失 ⇒ 门控 3（{@code appState.get("teamContext")}）直接 return ⇒ 消息<b>保持未读</b>，
     * 队长永远看不到。故本用例 RED→GREEN 直接反映 C1 是否真被修活。
     */
    @Test
    @DisplayName("[C1 主判据] 队员发普通消息 → 队长该会话 turn 真消费到（inbox 未读→已读）")
    void teammateMessageConsumedByLeaderTurnAfterReadback() {
        TeammateMailbox.writeToMailbox("team-lead",
            TeammateMailbox.TeammateMessage.of("researcher", "我把方案写好了", TeammateMailbox.isoNow(), "cyan"),
            TEAM);
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("前置条件：队员消息此刻未读").hasSize(1);

        LlmAgentLoop loop = loopWithSessionMapper(mapperReturning(
            sessionWith(null, "{\"teamName\":\"" + TEAM + "\"}")));
        runOnce(loop);

        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("回灌生效 ⇒ 队长轮把队员消息构建进 LLM 上下文并标已读；若回灌缺失，此处仍为 1 条未读"
                + "（C1 未修活）")
            .isEmpty();
    }

    /**
     * 【C2 反实验 · 计划 §P0-5 验收第 3 条】先写一条普通消息（read=false）→ <b>打开收件箱</b> →
     * 触发队长一次 turn ⇒ 仍能读到并注入。
     *
     * <p>「打开收件箱」在服务端的全部动作 = {@code GET /{team}/inbox} → {@code TeammateMailbox.readMailbox}
     * （纯读，不写文件）。改动前前端展开收件箱还会额外打 {@code POST /inbox/read} 把消息全标 read=true
     * ⇒ 模型侧静默看不到；该端点已按 C2 删除，故本例现在必须通过。
     *
     * <p>断言链：读 mailbox 后消息<b>仍未读</b>（打开收件箱不吞消息）→ leader turn 后<b>被消费</b>
     * （队长确实读到了）。两步缺一不可：只验「仍未读」会漏掉注入侧，只验「被消费」会漏掉「谁先标读」。
     */
    @Test
    @DisplayName("[C2 反实验] 打开收件箱后消息仍未读，队长 turn 仍能注入 —— 展开收件箱不再吞消息")
    void openingInboxDoesNotConsumeMessage_leaderTurnStillInjects() {
        TeammateMailbox.writeToMailbox("team-lead",
            TeammateMailbox.TeammateMessage.of("researcher", "这条不能被展开收件箱吃掉", TeammateMailbox.isoNow(), null),
            TEAM);
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM)).hasSize(1);

        // 「展开收件箱」= 前端仅剩的 GET inbox 调用（TeamController.inbox → readMailbox，纯读）
        List<TeammateMailbox.TeammateMessage> opened = TeammateMailbox.readMailbox("team-lead", TEAM);
        assertThat(opened).as("收件箱列表仍显示全部消息").hasSize(1);
        assertThat(opened.get(0).read()).as("消息列表本身带 read=false（前端 TeamPanel 从不消费该字段）").isFalse();
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("【C2 核心】打开收件箱不得把消息标已读 —— 否则队长模型侧静默看不到这条消息")
            .hasSize(1);

        // 触发队长一次 turn ⇒ 仍能读到并注入（构建后才标已读）
        LlmAgentLoop loop = loopWithSessionMapper(mapperReturning(
            sessionWith(null, "{\"teamName\":\"" + TEAM + "\"}")));
        runOnce(loop);

        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("队长 turn 必须仍能消费该消息 —— 改动前它已在「展开收件箱」那一步被标读吞掉（读不到）")
            .isEmpty();
    }

    /**
     * 【C1 收口 · 后台 run 不得消费】计划 §P0-4 验收第 2 条：同会话存在后台 / cron run 时，队员消息
     * <b>不得</b>被它抢先 mark read 吞掉。
     *
     * <p>机制：回灌（{@code sessions.team_context} → appState）只赋予「我是队长」<b>身份</b>，不赋予
     * inbox <b>消费资格</b>；消费资格由 per-run 的 {@code setTeammateInboxConsumer} 显式门控，
     * 唯一置位处 = {@code ChatService}（交互式前台用户回合）。{@code CronIdleExecutor} /
     * {@code MainSessionBackgroundService} 走同一条 {@code RunRequest.session} 入口但**不**置位
     * ⇒ 既不注入、也不标读。
     *
     * <p>断言链（缺一不可）：
     * <ol>
     *   <li>非交互 run 之后消息<b>仍未读</b> —— 后台 run 不消费、不 mark read（收口生效）；</li>
     *   <li>随后前台 run <b>仍能消费</b> —— 消息没被弄丢，主判据不破（收口没有过度）。</li>
     * </ol>
     * 若收口缺失（回到「谁跑谁消费」），第 1 步必红 —— 这正是改动前实测到的抢占路径。
     */
    @Test
    @DisplayName("[C1 收口] 非交互 run（cron/后台形态）不消费队长 inbox、不标读；随后前台 run 仍能消费")
    void nonInteractiveRun_doesNotConsumeInbox_foregroundStillCan() {
        SessionMapper mapper = mapperReturning(sessionWith(null, "{\"teamName\":\"" + TEAM + "\"}"));

        // 前台 turn 先跑一次（无消息）
        runOnce(loopWithSessionMapper(mapper));
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM)).isEmpty();

        // 队员此刻发消息，而该会话又起了一个**非交互** run（cron 空闲代跑 / 后台任务形态）
        TeammateMailbox.writeToMailbox("team-lead",
            TeammateMailbox.TeammateMessage.of("researcher", "还在吗", TeammateMailbox.isoNow(), null), TEAM);
        runOnce(backgroundLoop(mapper));

        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("非交互 run（未置位消费资格）不得消费、不得 markRead 队长 inbox —— 否则队员消息在用户"
                + "下一轮之前就被吃掉，症状与 C1 未修一模一样且更难查")
            .hasSize(1);

        // 前台回合到了 —— 消息必须仍在，并被正常消费注入
        runOnce(loopWithSessionMapper(mapper));
        assertThat(TeammateMailbox.readUnreadMessages("team-lead", TEAM))
            .as("收口不得把消息弄丢：前台交互式回合仍必须消费到（主判据不破）")
            .isEmpty();
    }
}

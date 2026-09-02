package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.eventbus.ws.MessageCompleteEvent;
import com.nexusai.eventbus.ws.MessageErrorEvent;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.domain.provider.ProviderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [mid-turn-align] ChatService 层：mid-turn 注入排队 user 消息的落库时机 + error 逃生门。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：busy-queued 由 LlmAgentLoop 工具边界 mid-turn 注入当前轮
 * 上下文（同轮回答），排队 user 消息<b>不立即落库</b>（goal 2），轮结束由 ChatService 补落库
 * （createQueuedUserMessage，指定 id = 队列 uuid，DB 顺序 = user → assistant... → queued-user）。
 * 本测试锁定三个行为意图：
 * <ol>
 *   <li><b>成功路径</b>：{@code replayAndPersist} 按 {@code state.messages()} 位置<b>原位落库</b>
 *       mid-turn 注入的 queued-user（走 4 参单调重载，含空 content 一条；顺序 = messages() 位置，
 *       与 assistant 的相对序见 ChatServiceReplayPersistReasoningDurationTest.inPlaceQueuedUserCreatedAt
 *       ——assistantA &lt; queued-user &lt; assistantB）。</li>
 *   <li><b>cancel 分支</b>：与成功路径共用同一 {@code persistInjectedQueuedMessages} 补落库
 *       （cancel 仅 return 前多调一次同 helper，行为由本文件 helper 用例覆盖）。</li>
 *   <li><b>error 分支</b>：{@code loop.run()} 抛异常时 state 恒 null（赋值未完成）→ 走 loop 逃生门
 *       {@code loop.injectedQueuedMessages()} 逐个以<b>原 uuid</b> re-enqueue 回 NotificationQueue
 *       （workload=busy-queued, priority=NEXT）+ emitChanged —— 消息不永久丢失，交 CronIdleExecutor 兜底。</li>
 * </ol>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock；{@code processUserMessage}
 * 直调（无 Spring proxy → @Async 不生效，同步执行）；loopProvider 返回 mock LlmAgentLoop（run 桩返回/抛异常）。
 */
@DisplayName("[mid-turn-align] 排队 user 消息轮结束补落库 + error 逃生门")
class ChatServiceTest {

    private ChatService service;
    private SessionMapper sessionMapper;
    private MessageMapper messageMapper;
    private NotificationQueue notificationQueue;
    private QueueEventPublisher queueEventPublisher;
    private com.nexusai.domain.session.MessageService messageService;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LlmAgentLoop> loopProvider;
    private SimpMessagingTemplate wsTemplate;
    private String sid;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        messageMapper = mock(MessageMapper.class);
        when(messageMapper.selectListByQuery(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "modelMapper", mock(ModelMapper.class));
        ReflectionTestUtils.setField(service, "providerMapper", mock(ProviderMapper.class));
        ReflectionTestUtils.setField(service, "settingsMapper", mock(SettingsMapper.class));
        ReflectionTestUtils.setField(service, "providerService", mock(ProviderService.class));
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(mock(LlmProvider.class));
        ReflectionTestUtils.setField(service, "llmProviderFactory", llmProviderFactory);
        ReflectionTestUtils.setField(service, "modelConfigResolver", mock(ModelConfigResolver.class));

        notificationQueue = new NotificationQueue();
        ReflectionTestUtils.setField(service, "notificationQueue", notificationQueue);
        queueEventPublisher = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(service, "queueEventPublisher", queueEventPublisher);
        messageService = mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(service, "messageService", messageService);

        loopProvider = mock(ObjectProvider.class);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        wsTemplate = mock(SimpMessagingTemplate.class);

        SessionRecord session = mock(SessionRecord.class);
        when(sessionMapper.selectOneById(sid)).thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(sid);
    }

    @Test
    @DisplayName("成功路径：mid-turn 注入 queued-user 原位 4 参落库（含空 content），顺序 = messages() 位置")
    void processUserMessage_successPath_persistsInjectedQueuedMessagesInPlace() {
        // GIVEN: mock loop.run() 返回 state——queued-user 同时 append 进 messages()（真实 LlmAgentLoop
        //   工具边界注入模型）并登记 injectedQueuedMessages（含空 content 一条）
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "同轮回复", null));
        state.appendMessage(LlmAgentLoop.toMessage(Role.user, "忙时追问", null, "msg-queued-1"));
        state.addInjectedQueuedMessage("msg-queued-1", "忙时追问");
        state.appendMessage(LlmAgentLoop.toMessage(Role.user, "", null, "msg-queued-2"));
        state.addInjectedQueuedMessage("msg-queued-2", "");   // 空 content 也落库（busy 入队 content 可为空串）
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenReturn(state);
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程（busy 判定 false → 正常跑完整轮）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        // THEN: 原位落库走 4 参重载（单调时间戳 createdAt=baseTs.plusNanos(seq)），顺序 = messages()
        //   位置（msg-queued-1 先于 msg-queued-2）；与 assistant 落库的相对序由
        //   ChatServiceReplayPersistReasoningDurationTest.inPlaceQueuedUserCreatedAt 锚定
        //   （assistantA < queued-user < assistantB）。
        InOrder inOrder = inOrder(messageService);
        inOrder.verify(messageService).createQueuedUserMessage(
            eq(sid), eq("msg-queued-1"), eq("忙时追问"), any(OffsetDateTime.class));
        inOrder.verify(messageService).createQueuedUserMessage(
            eq(sid), eq("msg-queued-2"), eq(""), any(OffsetDateTime.class));
        // 队列已空（mid-turn 注入已 drain 消费，轮结束不再残留）
        assertThat(notificationQueue.size()).isZero();
    }

    @Test
    @DisplayName("error 分支：loop.run() 抛异常 → state 恒 null，经 loop 逃生门 re-enqueue 回队列（原 uuid + busy-queued + NEXT）+ emitChanged")
    void processUserMessage_errorBranch_reenqueuesInjectedQueuedMessages() {
        // GIVEN: mock loop.run() 抛异常，loop.injectedQueuedMessages() 有值（error 逃生门镜像）
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.injectedQueuedMessages()).thenReturn(List.of(
            new AgentState.InjectedQueuedMessage("msg-queued-err", "错误前追问")));
        when(mockLoop.run(any(RunRequest.class))).thenThrow(new RuntimeException("boom"));
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程（run() 抛异常 → error 分支）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        // THEN: 已 mid-turn 注入的排队消息以原 uuid 重新入队（不丢失，交 CronIdleExecutor 兜底）
        assertThat(notificationQueue.size()).as("error 分支必须 re-enqueue 排队消息回队列").isEqualTo(1);
        NotificationQueue.QueueItem item = notificationQueue.peek(q -> true).orElseThrow();
        assertThat(item.uuid()).isEqualTo("msg-queued-err");
        assertThat(item.workload()).isEqualTo("busy-queued");
        assertThat(item.priority()).isEqualTo(NotificationQueue.Priority.NEXT);
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(item.sessionId()).isEqualTo(sid);
        verify(queueEventPublisher).emitChanged(sid);
    }

    @Test
    @DisplayName("helper：persistInjectedQueuedMessages 空注入列表 → createQueuedUserMessage 零调用（no-op）")
    void persistInjectedQueuedMessages_noInjected_noop() throws Exception {
        AgentState state = new AgentState("sys", sid, null);
        java.lang.reflect.Method m = ChatService.class.getDeclaredMethod(
            "persistInjectedQueuedMessages", AgentState.class, String.class);
        m.setAccessible(true);
        m.invoke(service, state, sid);

        verify(messageService, org.mockito.Mockito.never()).createQueuedUserMessage(anyString(), anyString(), any());
        verify(messageService, org.mockito.Mockito.never()).createQueuedUserMessage(
            anyString(), anyString(), any(), any(OffsetDateTime.class));
        assertThat(notificationQueue.size()).isZero();
    }

    // ── [reflect-blocker 回归锚点] 终态事件同源：message.complete / message.error 必须携带真实
    //    assistant id（=turnAssistantId / 在飞 currentAssistantMessageId），不得再带 msg-pending- 占位。
    //    WHY（CLAUDE.md 规则九 · 测试验证意图）：流式 chunk/tool_call 用真实 turnAssistantId 建气泡，
    //    终态事件携带占位 → 前端 assistantGroups.get(占位)=undefined 静默 no-op（气泡永不 locked、
    //    streaming 光标不停）、message.error 走 assistantGroupFor(占位) 建幽灵气泡。该缺陷在纯
    //    replayAndPersist 反射用例不可见（complete 事件在 processUserMessage 发送），故在此文件
    //    （processUserMessage 直调 harness）锚定。修复前占位 msg-pending- 前缀 → 断言 fails。

    @Test
    @DisplayName("成功路径 message.complete 携带末条 assistant 真实 id（=turnAssistantId，非 msg-pending- 占位）")
    void processUserMessage_completeEvent_carriesRealLastAssistantId() {
        // GIVEN: mock loop.run() 返回末条 assistant id = 'a-final' 的 state
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "同轮回复", null, "a-final"));
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenReturn(state);
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程（busy 判定 false → 正常跑完整轮，发送 message.complete）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        // THEN: complete 事件必须携带末条 assistant 真实 id（流式 chunk 同源契约）
        String topic = "/topic/sessions/" + sid + "/stream";
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeast(1)).convertAndSend(eq(topic), captor.capture());
        MessageCompleteEvent complete = captor.getAllValues().stream()
            .filter(MessageCompleteEvent.class::isInstance)
            .map(MessageCompleteEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(complete.getAssistantMessageId())
            .as("message.complete 必须携带末条 assistant 真实 id（=turnAssistantId，前端按 id 锁气泡）")
            .isEqualTo("a-final");
        assertThat(complete.getAssistantMessageId())
            .as("终态事件不得再携带 msg-pending- 占位（修复前 assistantGroups.get(占位)=undefined 静默 no-op）")
            .doesNotStartWith("msg-pending-");
    }

    @Test
    @DisplayName("[V-TOK] message.complete 携带真实 usage + 会话累计花费/上下文（非 mock 42）")
    void processUserMessage_completeEvent_carriesRealUsageAndSessionCost() {
        // GIVEN: mock loop.run() 返回带真实 usage 的末条 assistant + 会话累计
        // WHY: 验收 1/2/3 —— complete 事件 usage 是真实值（非 mock 42）、total_cost_usd=state 会话累计、
        //   上下文三字段每轮带（照抄 CC result 事件结构，plan §一 目标 JSON）。
        AgentState state = new AgentState("sys", sid, null);
        state.setCurrentModel("deepseek-v4-flash");
        ChatMessageDto asst = LlmAgentLoop.toMessage(Role.assistant, "同轮回复", null, "a-final")
            .withUsage(new AgentUsage(1000L, 500L, 100L, 200L,
                new AgentUsage.ServerToolUse(1L, 0L), "standard",
                new AgentUsage.CacheCreation(0L, 0L)));
        state.appendMessage(asst);
        // 模拟 LlmAgentLoop E2/E3 生产路径写 state 会话累计
        state.addSessionCostYuan(0.0123);
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenReturn(state);
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程（busy 判定 false → 正常跑完整轮，发送 message.complete）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        // THEN: complete 事件 usage/cost/上下文全部真实（替代 mock 42）
        String topic = "/topic/sessions/" + sid + "/stream";
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeast(1)).convertAndSend(eq(topic), captor.capture());
        MessageCompleteEvent complete = captor.getAllValues().stream()
            .filter(MessageCompleteEvent.class::isInstance)
            .map(MessageCompleteEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(complete.getUsage())
            .as("usage 必须携带真实 provider usage（非 mock 42，非 null）").isNotNull();
        assertThat(complete.getUsage().inputTokens())
            .as("usage.input_tokens = 末条 assistant provider 真实值").isEqualTo(1000L);
        assertThat(complete.getUsage().outputTokens())
            .as("usage.output_tokens = 末条 assistant provider 真实值").isEqualTo(500L);
        assertThat(complete.getUsage().cacheReadInputTokens())
            .as("usage.cache_read_input_tokens 透传").isEqualTo(200L);
        assertThat(complete.getTotalCostUsd())
            .as("total_cost_usd = state 会话累计花费（元，用户拍板值用元）").isEqualTo(0.0123);
        assertThat(complete.getContextTokensUsed())
            .as("[B2-R2] contextTokensUsed 协议分派：deepseek-v4-flash（openai_compatible，非 Anthropic）→ "
                + "仅 input=1000（prompt_tokens 已含 cache hit，加 cacheRead 会双计）；"
                + "Anthropic 才是 input+cache_read+cache_creation 三字段和（ContextUsageCalculatorTest "
                + "compute_openaiCompatibleIgnoresCache / compute_anthropicSumsAllThree 分别锚定）")
            .isEqualTo(1000L);
        assertThat(complete.getContextWindow())
            .as("contextWindow = 模型 max_context_tokens（mock 无记录 → 回落 1M）").isEqualTo(1_048_576L);
        assertThat(complete.getPercentLeft())
            .as("percentLeft = 余量百分比（常驻每轮带，对齐 CC StatusLine）").isNotNull();
        assertThat(complete.getNumTurns())
            .as("num_turns = state.turnCount()").isEqualTo(state.turnCount());
    }

    @Test
    @DisplayName("error 分支 message.error 携带 registry 在飞 currentAssistantMessageId（非 msg-pending- 占位）")
    void processUserMessage_errorBranch_errorEvent_carriesInFlightAssistantId() {
        // GIVEN: run() 抛异常 → state 恒 null；registry 持在飞 state（currentAssistantMessageId 已预分配）
        AgentState inFlight = new AgentState(null, sid, null);
        inFlight.prepareAssistantMessageId();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(sid, inFlight);
        ReflectionTestUtils.setField(service, "sessionAgentStateRegistry", registry);

        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenThrow(new RuntimeException("boom"));
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程（run() 抛异常 → error 分支发送 message.error）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        // THEN: error 事件必须携带在飞 currentAssistantMessageId（=prepareAssistantMessageId 结果，
        //   与流式 chunk 同源；修复前回落 task 占位 msg-pending- 建幽灵气泡）
        String topic = "/topic/sessions/" + sid + "/stream";
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeast(1)).convertAndSend(eq(topic), captor.capture());
        MessageErrorEvent err = captor.getAllValues().stream()
            .filter(MessageErrorEvent.class::isInstance)
            .map(MessageErrorEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(err.getAssistantMessageId())
            .as("message.error 必须携带在飞 currentAssistantMessageId（registry 同源）")
            .isEqualTo(inFlight.currentAssistantMessageId());
        assertThat(err.getAssistantMessageId())
            .as("error 终态事件不得再携带 msg-pending- 占位")
            .doesNotStartWith("msg-pending-");
    }

    // ============ [P1 · slash-align] processUserMessage slash 接线（对齐 CC processSlashCommand.tsx:309-921） ============

    @Test
    @DisplayName("[P1] prompt 型 slash → isMeta 技能内容落库 + 原文 /command 起 turn（防双注入）")
    void processUserMessage_slashPromptType_persistsIsMetaAndRunsLoop() {
        // WHY（规则九 · P1 直连路径完成标准）: processUserMessage 对 '/' 开头输入走
        //   SlashCommandInterceptor（对齐 CC processSlashCommand.tsx:309-921）。prompt 型
        //   （shouldQuery=true）→ 技能内容先落 isMeta DB 消息（CC :915-918 createUserMessage isMeta:true，
        //   resume/压缩按 id 排除当前 user、metaId 独立 id 会被载入历史 → 技能内容随 transcript 持久可恢复），
        //   userPrompt 保持原文 /command（技能内容经 isMeta 历史重载，防双注入）。
        //   RED: isMeta 未落库 / loop.run 未起 / userPrompt 泄漏技能内容 → 变红。
        SlashCommandInterceptor slashInterceptor = mock(SlashCommandInterceptor.class);
        ReflectionTestUtils.setField(service, "slashInterceptor", slashInterceptor);
        when(slashInterceptor.intercept(eq(sid), eq("msg-user"), eq("/import-cc --skill=test"), any(), eq(wsTemplate)))
            .thenReturn(new SlashCommandInterceptor.SlashResolution(
                true, true, "技能内容（SKILL.md 渲染）", null, null, "技能内容（isMeta）", null));
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenReturn(new AgentState("sys", sid, null));
        when(loopProvider.getObject()).thenReturn(mockLoop);

        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "/import-cc --skill=test", null, null, null, null, null, null, null, null), wsTemplate);

        // isMeta 技能内容落库（UI 隐藏、模型可见、DB 持久化）
        verify(messageService).createQueuedUserMessage(eq(sid), any(), eq("技能内容（isMeta）"),
            any(OffsetDateTime.class), eq(true));
        // 起 turn：userPrompt=原文 /command（技能内容经 isMeta 历史重载，无双注入）
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(mockLoop).run(captor.capture());
        assertThat(captor.getValue().userPrompt()).isEqualTo("/import-cc --skill=test");
    }

    @Test
    @DisplayName("[P1] 非查询型 slash 终态（unknown）→ 结果落库 + 推 idle，不起 loop.run（CC shouldQuery=false）")
    void processUserMessage_slashNonQuerying_terminatesWithoutLoop() {
        // WHY（规则九 · P1 非查询型终态完成标准）: shouldQuery=false（unknown/local/userInvocable-false）
        //   → 不跑 loop.run，显式推结果消息 + status=idle + inProgress.remove（防 cancelSession 幽灵任务）。
        //   若非查询型分支缺失 → 用户敲 /nosuch 会以原文起 LLM turn（模型收到无意义命令），前端无终态。
        //   RED: loop.run 被调用 / idle 未推 / 结果未落库 → 变红。
        SlashCommandInterceptor slashInterceptor = mock(SlashCommandInterceptor.class);
        ReflectionTestUtils.setField(service, "slashInterceptor", slashInterceptor);
        when(slashInterceptor.intercept(eq(sid), eq("msg-user"), eq("/nosuch"), any(), eq(wsTemplate)))
            .thenReturn(new SlashCommandInterceptor.SlashResolution(
                true, false, null, "Unknown skill: nosuch", null, null, "msg-slash-1"));
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(loopProvider.getObject()).thenReturn(mockLoop);

        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "/nosuch", null, null, null, null, null, null, null, null), wsTemplate);

        // 结果落库（isMeta=false，用户可见）
        verify(messageService).createQueuedUserMessage(eq(sid), eq("msg-slash-1"), eq("Unknown skill: nosuch"),
            any(OffsetDateTime.class), eq(false));
        // 推 status=idle（CC shouldQuery:false 等价，前端终态不悬挂）
        String topic = "/topic/sessions/" + sid + "/stream";
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeast(1)).convertAndSend(eq(topic), eventCaptor.capture());
        boolean hasIdle = eventCaptor.getAllValues().stream()
            .anyMatch(e -> e instanceof com.nexusai.eventbus.ws.SessionStatusEvent s && "idle".equals(s.getStatus()));
        assertThat(hasIdle).as("P1: 非查询型终态推 status=idle").isTrue();
        // 不起模型 turn
        verify(mockLoop, never()).run(any());
    }
}

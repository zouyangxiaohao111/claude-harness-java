package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.eventbus.ws.MessageCancelledEvent;
import com.nexusai.eventbus.ws.MessageCompleteEvent;
import com.nexusai.eventbus.ws.MessageErrorEvent;
import com.nexusai.eventbus.ws.SessionStatusEvent;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import com.nexusai.domain.provider.ProviderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [C6] 停止键可见性 —— 主路径「run 收口」必推 {@code session.status=idle}（idle 必须在 finally 里）。
 *
 * <p><b>WHY（用户需求「能够 UI 中手动终止会话循环」· CLAUDE.md 规则九 测试验证意图）</b>：
 * 前端的停止键靠三路信号【或】出来（见 {@code front/src/utils/turnRunning.ts}），其中
 * {@code serverRunning}（服务端权威运行态）<b>只有两种</b>翻回 false 的途径：
 * <ol>
 *   <li>实时 —— 收到本会话的 {@code session.status=idle}（本测试钉的就是它）；</li>
 *   <li>重建 —— 载入 / 切会话 / 重连时 GET {@code /sessions/{id}/running}（另一条独立通道）。</li>
 * </ol>
 * 于是「idle 必达」是硬约束：一旦收口段某步抛出把 idle 吞掉，前端就<b>永久</b>停在「运行中」——
 * 停止键永不消失 + Esc 永走停止分支 + 对话操作弹窗永久打不开，直到 F5 / 切会话 / 重连。
 *
 * <p><b>缺陷现场（本测试的 GIVEN）</b>：收口段里有一步做 DB 读-改-写且<b>无 try</b> ——
 * {@code costTracker.saveCurrentSessionCosts}（CostTracker:202-218 先 {@code sessionMapper.selectOneById}
 * 再 {@code update}）。修复前 idle 写在它<b>之后</b>（原 ChatService:1111-1114）⇒ 该方法一抛即跳过 idle。
 * 本测试用「costTracker 必抛」把这一步变成确定性的，断言 idle <b>仍然推出</b>、且异常照抛（fail loud 不吞）。
 *
 * <p><b>反向实验（判据必须是「改回缺口即红」）</b>：把 idle 挪回 try 内（即复原成「costTracker 之后」）
 * ⇒ 本测试第一条用例必红（idle 零推送）。
 *
 * <p><b>手法</b>：沿用同包 {@code ChatServiceTest} 的 {@code new ChatService()} +
 * {@link ReflectionTestUtils} harness（{@code processUserMessage} 直调，无 Spring proxy → @Async 不生效，
 * 同步执行），只多注入一个「必抛」的 CostTracker mock。
 */
@DisplayName("[C6] run 收口必推 status=idle（停止键唯一的实时解除信号）")
class ChatServiceTurnEndIdleAlwaysPushedTest {

    private ChatService service;
    private SessionMapper sessionMapper;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LlmAgentLoop> loopProvider;
    private SimpMessagingTemplate wsTemplate;
    private String sid;
    private String topic;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        topic = "/topic/sessions/" + sid + "/stream";

        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        MessageMapper messageMapper = mock(MessageMapper.class);
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
        ReflectionTestUtils.setField(service, "notificationQueue", new NotificationQueue());
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
        ReflectionTestUtils.setField(service, "messageService", mock(com.nexusai.domain.session.MessageService.class));

        loopProvider = mock(ObjectProvider.class);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        wsTemplate = mock(SimpMessagingTemplate.class);

        when(sessionMapper.selectOneById(sid)).thenReturn(mock(SessionRecord.class));
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(sid);
    }

    /** 一次性 mock loop（run 返回末条 assistant id = 'a-final' 的正常终态 state）+ wsTemplate 装配。 */
    private void wireLoopReturningNormalState() {
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "同轮回复", null, "a-final"));
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenReturn(state);
        when(loopProvider.getObject()).thenReturn(mockLoop);
    }

    private List<Object> capturedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeast(1)).convertAndSend(eq(topic), captor.capture());
        return captor.getAllValues();
    }

    /** 在飞 task 表（生产是 private field；测试经反射读，用于断言「收口必清」与「cancel 落点」）。 */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> inFlightTasks() {
        return (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(service, "inProgress");
    }

    private static List<SessionStatusEvent> statusEvents(List<Object> events) {
        return events.stream()
            .filter(SessionStatusEvent.class::isInstance)
            .map(SessionStatusEvent.class::cast)
            .toList();
    }

    @Test
    @DisplayName("⭐ 收口段抛异常（会话累计落库失败）⇒ status=idle 仍必推 —— 否则停止键永久卡死")
    void costTrackerThrows_stillPushesIdle() {
        wireLoopReturningNormalState();

        // GIVEN: 收口段的 costTracker.saveCurrentSessionCosts 必抛（DB 读-改-写失败；该方法内无 try）
        CostTracker costTracker = mock(CostTracker.class);
        doThrow(new RuntimeException("db down: sessions 表 update 失败"))
            .when(costTracker).saveCurrentSessionCosts(eq(sid), any(AgentState.class));
        ReflectionTestUtils.setField(service, "costTracker", costTracker);

        // WHEN: 主流程正常跑完一轮（run 返回），收口段在 costTracker 处炸
        assertThatThrownBy(() -> service.processUserMessage(sid, "msg-user", new SendMessageRequest(
                "主问题", null, null, null, null, null, null, null, null), wsTemplate))
            .as("收口段异常必须照抛（fail loud，不吞）—— 修复只保证「先推完 idle」，不改异常语义")
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("db down");

        // THEN 1: complete 仍已推出（装配在 costTracker 之前，前端据此锁气泡）
        List<Object> events = capturedEvents();
        assertThat(events.stream().anyMatch(MessageCompleteEvent.class::isInstance))
            .as("message.complete 在 costTracker 之前装配 → 即使随后抛出也必须已推出")
            .isTrue();

        // THEN 2: ⭐ idle 必达，且是本轮最后一个事件（前端据此把 serverRunning 翻回 false → 停止键消失）
        List<SessionStatusEvent> statuses = statusEvents(events);
        assertThat(statuses)
            .as("run 收口必须推 session.status=idle —— 这是前端停止键唯一的实时解除信号；"
                + "被收口段异常吞掉即「停止键永不消失 + Esc 永走停止分支 + 弹窗永久打不开」")
            .isNotEmpty();
        SessionStatusEvent last = statuses.get(statuses.size() - 1);
        assertThat(last.getStatus())
            .as("最后一条会话状态必须是 idle（不是 thinking）")
            .isEqualTo("idle");
        assertThat(last.getSessionId()).isEqualTo(sid);
        assertThat(events.get(events.size() - 1))
            .as("idle 必须是本轮最后一个推送（前端不看时序也能收口）")
            .isInstanceOf(SessionStatusEvent.class);

        // THEN 3: inProgress 也必须已清（finally 同一处）——残留会让 cancelSession 找到幽灵任务
        assertThat(inFlightTasks())
            .as("收口异常不得残留 inProgress 条目（幽灵任务）")
            .doesNotContainKey(sid);
    }

    @Test
    @DisplayName("error 分支（run 抛异常）⇒ 即便 message.error 推送本身失败，status=idle 仍必推")
    void errorBranch_wsPushFails_stillPushesIdle() {
        // GIVEN: run() 抛异常（走 error 分支），且 message.error 的 STOMP 推送本身也失败
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenThrow(new RuntimeException("llm boom"));
        when(loopProvider.getObject()).thenReturn(mockLoop);
        List<Object> sent = new ArrayList<>();
        doAnswer(inv -> {
            Object evt = inv.getArgument(1);
            sent.add(evt);
            if (evt instanceof MessageErrorEvent) throw new RuntimeException("stomp down");
            return null;
        }).when(wsTemplate).convertAndSend(any(String.class), any(Object.class));

        // WHEN: 主流程（error 分支；首个推送的异常照抛 —— fail loud 不吞）
        boolean threw = false;
        try {
            service.processUserMessage(sid, "msg-user", new SendMessageRequest(
                "主问题", null, null, null, null, null, null, null, null), wsTemplate);
        } catch (RuntimeException ex) {
            threw = true;
        }

        // THEN: 异常照抛，但 idle 必须已推出（同一把「停止键卡死」的锁）
        assertThat(threw).as("推送异常照抛（fail loud），修复只保证先推完 idle").isTrue();
        assertThat(statusEvents(sent).stream().map(SessionStatusEvent::getStatus).toList())
            .as("message.error 推送失败不得吞掉 status=idle（否则前端停止键永久卡住）")
            .contains("idle");
        assertThat(inFlightTasks())
            .as("error 分支收口不得残留 inProgress 条目（幽灵任务）")
            .doesNotContainKey(sid);
    }

    @Test
    @DisplayName("cancel 分支（task.cancel 置位）⇒ cancelled + 恰好一条 idle（finally 单点推，不重不漏）")
    void cancelBranch_stillPushesExactlyOneIdle() {
        // GIVEN: run() 返回时把在飞 task 的 cancel 置位（模拟用户点了「停止」——cancelSession 置的就是它）
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "半截回复", null, "a-final"));
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenAnswer(inv -> {
            Object task = inFlightTasks().get(sid);
            assertThat(task).as("前置：run 期间 inProgress 必有该会话 task（cancel 的落点）").isNotNull();
            AtomicBoolean cancel = (AtomicBoolean) ReflectionTestUtils.getField(task, "cancel");
            assertThat(cancel).isNotNull();
            cancel.set(true);
            return state;
        });
        when(loopProvider.getObject()).thenReturn(mockLoop);

        // WHEN: 主流程走 cancel 分支（该分支 return 在 try 内 → idle 由 finally 出）
        service.processUserMessage(sid, "msg-user", new SendMessageRequest(
            "主问题", null, null, null, null, null, null, null, null), wsTemplate);

        List<Object> events = capturedEvents();
        assertThat(events.stream().anyMatch(MessageCancelledEvent.class::isInstance))
            .as("cancel 分支必须推 message.cancelled（前端清流式块）")
            .isTrue();

        List<SessionStatusEvent> idles = statusEvents(events).stream()
            .filter(s -> "idle".equals(s.getStatus())).toList();
        assertThat(idles)
            .as("cancel 分支的 idle 挪进 finally 后必须仍恰好一条：0 条 ⇒ 停止键不消失；2 条 ⇒ 重复推（前端重复收口）")
            .hasSize(1);
        assertThat(events.get(events.size() - 1))
            .as("idle 必须是本轮最后一个推送")
            .isInstanceOf(SessionStatusEvent.class);
    }
}

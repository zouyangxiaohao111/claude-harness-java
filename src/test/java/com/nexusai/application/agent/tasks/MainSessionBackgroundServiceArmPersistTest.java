package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SM/compact 对齐 CC] 主会话后台化入口的「仅落库」监听武装（{@code armPersistenceListeners}）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：后台派生查询（
 * {@code MainSessionBackgroundService.runBackgroundQuery}）此前<b>完全没有</b>武装任何持久化监听 →
 * 该通道下 {@code state.appendMessage} 不落库、compact 结果不落库 → 下一 run 从 DB 恢复全量历史
 * → <b>反复自动压缩</b>（本批的真实 bug 根因之一）。本批新增
 * {@code loop.setPostHistoryPersistEnabler(state2 -> chatService.armPersistenceListeners(state2, sessionId))}
 * 补齐该通道。若这行被删/改空，行为会静默回退到「后台 run 不落库」，而全仓测试仍绿 —— 本测试锁死它。
 *
 * <p><b>「不推 STOMP」的断言口径（先读签名再断言，不猜）</b>：
 * {@link ChatService#armPersistenceListeners(AgentState, String)} 的签名里<b>根本没有</b>
 * topic / {@code SimpMessagingTemplate} 参数 —— 它内部固定
 * {@code armRealTimePersist(state, sessionId, null, null, null)}（见 ChatService.java:1300-1303），
 * 而 {@code persistAppendedMessage} 的推送入口 {@code sendAndLog(ws == null)} 直接 return。
 * 故「不推 STOMP」是<b>结构性保证</b>：本测试用 (a) 反射锁死该签名不得出现推送参数
 * （{@link #armPersistenceListeners_hasNoPushChannel()}），(b) 端到端 arm+append 后
 * 断言后台入口持有的 wsTemplate 零 {@code convertAndSend}（{@link #armedState_persistsWithoutPushingStomp()}）。
 *
 * <p><b>RED 条件（硬指标）</b>：
 * <ul>
 *   <li>删除 {@code MainSessionBackgroundService.java:384-391} 的
 *       {@code loop.setPostHistoryPersistEnabler(...)} 整块 → {@link #backgroundRun_armsPersistenceListener()} 红
 *       （捕获不到 enabler → state 未武装）。</li>
 *   <li>把该 lambda 体改为空实现（{@code state2 -> {}}）→ 同测试 {@code isAppendPersistenceArmed()} 红。</li>
 *   <li>改传错误 sessionId（如 taskId）→ {@link #armedState_persistsWithoutPushingStomp()} 捕获的
 *       {@code MessageRecord.getSessionId()} 断言红。</li>
 *   <li>给 {@code ChatService.armPersistenceListeners} 加 {@code SimpMessagingTemplate}/topic 参数 →
 *       {@link #armPersistenceListeners_hasNoPushChannel()} 红。</li>
 * </ul>
 *
 * <p><b>装配</b>：对齐既有 {@code MainSessionBackgroundServiceTest} —— 真实
 * {@link TaskFrameworkService}/{@link SdkEventQueue}/{@link NotificationQueue} + mock {@code LlmAgentLoop}
 * （捕获 {@code setPostHistoryPersistEnabler} 的 enabler 并手工回调，模拟
 * {@code LlmAgentLoop.doRun} 在历史注入后回调，见 LlmAgentLoop.java:2438-2439）+ 同步
 * {@code backgroundExecutor}（{@code Runnable::run}）保证确定性。ChatService 为真实实例
 * （mock messageMapper + 真实 MessageService），使「落库」是真断言而非自证 mock。
 */
@DisplayName("[SM/compact 对齐 CC] 主会话后台化入口武装『仅落库』监听")
class MainSessionBackgroundServiceArmPersistTest {

    private static final String SESSION = "sess-x";

    private MainSessionBackgroundService service;
    private LlmAgentLoop loop;
    private ChatService chatService;
    private MessageMapper messageMapper;
    private SimpMessagingTemplate wsTemplate;

    @BeforeEach
    void setUp() {
        SdkEventQueue sdkEventQueue = new SdkEventQueue();
        TaskFrameworkService taskFrameworkService = new TaskFrameworkService(sdkEventQueue);
        NotificationQueue notificationQueue = new NotificationQueue();

        service = new MainSessionBackgroundService();
        ObjectProvider<LlmAgentLoop> loopProvider = mock(ObjectProvider.class);
        loop = mock(LlmAgentLoop.class);
        when(loopProvider.getObject()).thenReturn(loop);

        // 真实 ChatService（仅替换落库 mapper + 注入真实 MessageService）→ 落库断言为真。
        chatService = new ChatService();
        messageMapper = mock(MessageMapper.class);
        ToolCallMapper toolCallMapper = mock(ToolCallMapper.class);
        MessageService messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);

        wsTemplate = mock(SimpMessagingTemplate.class);

        ReflectionTestUtils.setField(service, "taskFrameworkService", taskFrameworkService);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        ReflectionTestUtils.setField(service, "sdkEventQueue", sdkEventQueue);
        ReflectionTestUtils.setField(service, "notificationQueue", notificationQueue);
        // 同步执行器（现有 MainSessionBackgroundServiceTest 同款）：loop.run 在调用线程内执行 → 确定性
        ReflectionTestUtils.setField(service, "backgroundExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "chatService", chatService);
    }

    /**
     * 驱动后台入口，捕获 {@code setPostHistoryPersistEnabler} 的 enabler 并<b>立即以 armedState 回调</b>
     * （模拟 LlmAgentLoop.doRun 历史注入后回调）。
     *
     * @return 被武装的 AgentState（本轮后台 run 的 state）
     */
    private AgentState startBackgroundAndCaptureArmedState(AtomicReference<Consumer<AgentState>> enablerRef) {
        AgentState armedState = new AgentState("sys");
        doAnswer(inv -> {
            Consumer<AgentState> enabler = inv.getArgument(0);
            enablerRef.set(enabler);
            // LlmAgentLoop.java:2438-2439: postHistoryPersistEnabler.accept(state)
            enabler.accept(armedState);
            return null;
        }).when(loop).setPostHistoryPersistEnabler(any());

        service.startBackgroundSession(
            SESSION, "bg query", List.of(), wsTemplate, "hi", "mock-fast", ProviderConfig.empty(), null);
        return armedState;
    }

    private static ChatMessageDto assistantText(String id, String content) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, null, content, null,
            List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    @Test
    @DisplayName("后台 run 武装落库监听：enabler 已注册 + state.isAppendPersistenceArmed()==true")
    void backgroundRun_armsPersistenceListener() {
        // WHY: 未武装 → 后台 run 的 append 全部不落库（本批修复前的状态）→ 下轮从 DB 恢复全量 →
        //   反复自动压缩。RED: 删 MainSessionBackgroundService.java:384-391 的武装块 → enablerRef 为 null → 红。
        AtomicReference<Consumer<AgentState>> enablerRef = new AtomicReference<>();

        AgentState armedState = startBackgroundAndCaptureArmedState(enablerRef);

        assertThat(enablerRef.get())
            .as("后台入口必须在 run 前注册 postHistoryPersistEnabler（否则该通道零武装）")
            .isNotNull();
        assertThat(armedState.isAppendPersistenceArmed())
            .as("enabler 回调后 state 的 appendListener 必须已武装（append 即落库）")
            .isTrue();
    }

    @Test
    @DisplayName("武装后 append 即落库且不推 STOMP：messageMapper.insert 命中 + wsTemplate 零 convertAndSend")
    void armedState_persistsWithoutPushingStomp() {
        // WHY: 后台入口的流走任务级 topic（loop.setTaskStreamContext），持久化通道必须「仅落库不推」，
        //   否则 STOMP 双推（后台任务流 + 会话流）。RED: 若武装改成带 ws 的重载 → 端到端推送断言红；
        //   若武装未生效 → insert 断言红。sessionId 断言锁「落主会话库」而非 taskId 库。
        AtomicReference<Consumer<AgentState>> enablerRef = new AtomicReference<>();
        AgentState armedState = startBackgroundAndCaptureArmedState(enablerRef);

        armedState.appendMessage(assistantText("a1", "后台派生回复"));

        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getSessionId())
            .as("后台派生查询落的是主会话 messages（复用真实 sessionId，非 taskId 独立库）")
            .isEqualTo(SESSION);
        assertThat(captor.getValue().getSeq())
            .as("后台落库行同样带 seq（与前台 writer 同域取号）")
            .isNotNull();
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("结构性锁：armPersistenceListeners 签名不含推送通道（不推 STOMP 是结构保证，非运行时巧合）")
    void armPersistenceListeners_hasNoPushChannel() throws Exception {
        // WHY: 「仅落库不推 STOMP」的可信来源 = armPersistenceListeners 没有 topic/ws 参数，
        //   内部固定传 null（ChatService.java:1300-1303）。若将来有人给它加 SimpMessagingTemplate
        //   参数以启用推送，后台通道就会意外双推 —— 本反射断言把该 API 形状锁死。
        Method armPersist = ChatService.class.getMethod("armPersistenceListeners", AgentState.class, String.class);
        assertThat(armPersist.getParameterCount())
            .as("armPersistenceListeners 只接 (state, sessionId) —— 无 topic/ws 参数")
            .isEqualTo(2);
        for (Class<?> p : armPersist.getParameterTypes()) {
            assertThat(p)
                .as("参数类型不得为 SimpMessagingTemplate（有则『仅落库不推』不再成立）")
                .isNotEqualTo(SimpMessagingTemplate.class);
        }
        // 对照：带推送通道的是 5 参 armRealTimePersist（主路径用）
        Method withPush = ChatService.class.getMethod("armRealTimePersist",
            AgentState.class, String.class, String.class, SimpMessagingTemplate.class, String.class);
        assertThat(withPush.getParameterCount()).isEqualTo(5);
    }
}

package com.nexusai.kb.app.qa;

import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import com.nexusai.eventbus.ws.StreamEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link KbQaProgress} · 工具调用事件的三态文案与 fail-soft 契约。
 *
 * <p><b>WHY 这些断言</b>：
 * <ul>
 *   <li><b>三态文案逐字</b>：前端直接展示这行字，且「检索到 N 条」与「命中 N 条候选，未用于回答」
 *       对用户是两种完全不同的结论 —— 一个说答案用上了这些内容，一个说没用上。文案错一个字，
 *       用户就会被误导成「答案基于这些内容」。</li>
 *   <li><b>searchNothing 时 count 归零</b>：源站真事故 —— 把召回数当结果数报出去，时间线承诺了
 *       一个答案从未见过的条数，点开还没有引用。</li>
 *   <li><b>fail-soft</b>：进度事件是附加展示，推送抛异常绝不能打断问答主链。</li>
 * </ul>
 */
@DisplayName("[RAG 进度] 工具调用事件")
class KbQaProgressTest {

    private static final String TOPIC = "/topic/sessions/s1/stream";

    private SimpMessagingTemplate ws;

    @BeforeEach
    void setUp() {
        KbQaProgress.reset();
        StreamEventSeq.reset("s1");
        ws = mock(SimpMessagingTemplate.class);
    }

    @AfterEach
    void tearDown() {
        KbQaProgress.reset();
        StreamEventSeq.reset("s1");
    }

    private KbQaProgress.Stage begin() {
        KbQaProgress.Stage stage = KbQaProgress.beginRetrieval(
            ws, TOPIC, "s1", "u1", "a1", "污水处理工艺", "knowledge");
        assertThat(stage).as("开关默认开 + ws 非空 ⇒ 必须开启窗口").isNotNull();
        clearInvocations();
        return stage;
    }

    private void clearInvocations() {
        org.mockito.Mockito.clearInvocations(ws);
    }

    /** 捕获 end 发出的那一帧 tool_result。 */
    private MessageToolResultEvent capturedResult() {
        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws).convertAndSend(eq(TOPIC), captor.capture());
        StreamEvent evt = captor.getValue();
        assertThat(evt).isInstanceOf(MessageToolResultEvent.class);
        return (MessageToolResultEvent) evt;
    }

    @Test
    @DisplayName("begin 发 knowledge_search 调用帧，arguments 带 search_source + query")
    void beginEmitsToolCall() {
        KbQaProgress.beginRetrieval(ws, TOPIC, "s1", "u1", "a1", "污水处理工艺", "web");

        ArgumentCaptor<StreamEvent> captor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws).convertAndSend(eq(TOPIC), captor.capture());
        MessageToolCallEvent evt = (MessageToolCallEvent) captor.getValue();

        assertThat(evt.getType()).isEqualTo("message.tool_call");
        assertThat(evt.getToolName()).isEqualTo("knowledge_search");
        assertThat(evt.getToolCallId()).isNotBlank();
        assertThat(evt.getArguments()).containsEntry("search_source", "web");
        assertThat(evt.getArguments()).containsEntry("query", "污水处理工艺");
        assertThat(evt.getAssistantMessageId()).isEqualTo("a1");
    }

    @Test
    @DisplayName("三态①：count > 0 ⇒ 「检索到 3 条相关内容」")
    void foundText() {
        KbQaProgress.Stage stage = begin();

        KbQaProgress.endRetrieval(stage, false, 3, 0, null);

        MessageToolResultEvent evt = capturedResult();
        assertThat(evt.getResult()).isEqualTo("检索到 3 条相关内容");
        assertThat(evt.getIsError()).isFalse();
        assertThat(evt.getToolCallId()).isEqualTo(stage.toolCallId());
    }

    @Test
    @DisplayName("⭐ 三态②：searchNothing ⇒ count 归零，报「命中 7 条候选，相关性不足，未用于回答」")
    void candidateTextWhenSearchNothing() {
        KbQaProgress.Stage stage = begin();

        // 召回 7 条、全部被阈值筛掉：真正进入上下文的是 0 条
        KbQaProgress.endRetrieval(stage, true, 7, 7, null);

        MessageToolResultEvent evt = capturedResult();
        assertThat(evt.getResult())
            .isEqualTo("命中 7 条候选，相关性不足，未用于回答")
            .doesNotContain("检索到");
        assertThat(evt.getIsError()).as("短路进兜底不是错误").isFalse();
    }

    @Test
    @DisplayName("⭐ searchNothing 且未单独给候选数 ⇒ 退回原始召回数当候选数")
    void candidateFallbackToCount() {
        KbQaProgress.Stage stage = begin();

        KbQaProgress.endRetrieval(stage, true, 5, 0, null);

        assertThat(capturedResult().getResult())
            .isEqualTo("命中 5 条候选，相关性不足，未用于回答");
    }

    @Test
    @DisplayName("三态③：都为零 ⇒ 「未检索到相关内容」")
    void nothingText() {
        KbQaProgress.Stage stage = begin();

        KbQaProgress.endRetrieval(stage, false, 0, 0, null);

        assertThat(capturedResult().getResult()).isEqualTo("未检索到相关内容");
    }

    @Test
    @DisplayName("ws 为 null：begin 返回 null，end 不抛且不推送")
    void nullWsIsNoop() {
        KbQaProgress.Stage stage = KbQaProgress.beginRetrieval(
            null, TOPIC, "s1", "u1", "a1", "q", "knowledge");

        assertThat(stage).isNull();
        assertThatCode(() -> KbQaProgress.endRetrieval(stage, false, 3, 0, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("开关关闭：begin 返回 null，零事件")
    void switchOffEmitsNothing() {
        KbQaProgress.setEnabled(false);

        KbQaProgress.Stage stage = KbQaProgress.beginRetrieval(
            ws, TOPIC, "s1", "u1", "a1", "q", "knowledge");
        KbQaProgress.endRetrieval(stage, false, 3, 0, null);

        assertThat(stage).isNull();
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("durationMs 非负")
    void durationIsNonNegative() {
        KbQaProgress.Stage stage = begin();

        KbQaProgress.endRetrieval(stage, false, 1, 0, null);

        Long durationMs = capturedResult().getDurationMs();
        assertThat(durationMs).isNotNull();
        assertThat(durationMs).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("推送抛异常 ⇒ 只 WARN，不抛给调用方（begin 返回 null，end 静默）")
    void pushFailureIsSoft() {
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
            .when(ws).convertAndSend(anyString(), any(Object.class));

        KbQaProgress.Stage stage = KbQaProgress.beginRetrieval(
            ws, TOPIC, "s1", "u1", "a1", "q", "knowledge");

        assertThat(stage).isNull();
        assertThatCode(() -> KbQaProgress.endRetrieval(stage, false, 3, 0, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("改写阶段：toolName=query_understand，成功文案「已完成问题理解」")
    void queryUnderstand() {
        KbQaProgress.Stage stage = KbQaProgress.beginQueryUnderstand(
            ws, TOPIC, "s1", "u1", "a1", "原始问题");
        assertThat(stage).isNotNull();

        ArgumentCaptor<StreamEvent> callCaptor = ArgumentCaptor.forClass(StreamEvent.class);
        verify(ws).convertAndSend(eq(TOPIC), callCaptor.capture());
        MessageToolCallEvent call = (MessageToolCallEvent) callCaptor.getValue();
        assertThat(call.getToolName()).isEqualTo("query_understand");
        assertThat(call.getArguments()).containsEntry("query", "原始问题");

        clearInvocations();
        KbQaProgress.endQueryUnderstand(stage, true, null);

        assertThat(capturedResult().getResult()).isEqualTo("已完成问题理解");
    }
}

package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.ToolCallProgressEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * G5-4 遗留项 · tool_call_progress 生产接线验证。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：BashTool 的 ProgressPoller/ProgressAccumulator/
 * bashProgressSink 此前是生产死代码——全仓无 {@code tool_call_progress} STOMP 事件类型、无 bean 注入
 * 点，生产路径 never 发射。本测试验证两条接线契约：
 * <ol>
 *   <li>前台命令运行超 PROGRESS_THRESHOLD_MS=2s 阈值后，BashTool 经 bashProgressSink 发射
 *       {@link BashTool.BashProgress}（CC original: BashTool.tsx:663-677 onProgress，payload 字段
 *       output/fullOutput/elapsedTimeSeconds/totalLines/totalBytes）—— 次数 ≥ 1 且字段对齐。</li>
 *   <li>{@link BashProgressPublisher} 把 BashProgress 发射为 {@link ToolCallProgressEvent}
 *       （type={@code tool_call_progress}）到会话级流 topic {@code /topic/sessions/{sessionId}/stream}
 *       —— 与 MessageToolCallEvent / MessageToolResultEvent 同一会话单 topic。</li>
 * </ol>
 */
@DisplayName("BashTool tool_call_progress 生产接线（G5-4 遗留）")
class BashToolProgressPublishTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final BashTool bashTool = new BashTool();

    @TempDir
    Path tempDir;

    // ── helpers ──

    private ToolUseContext ctx(Path workspaceDir, String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, null, Map.of(),
            false, "", workspaceDir);
    }

    private ToolUseBlock call(String callId, String command) {
        JsonNode input = JSON.createObjectNode().put("command", command);
        return new ToolUseBlock(callId, "Bash", input);
    }

    /**
     * 慢前台命令：首行立即输出，随后 sleep 越过 2s 阈值，末尾再补一行。
     *
     * <p><b>WHY 无单引号/反斜杠</b>：Windows ProcessBuilder → {@code bash -c} 传参对含
     * 单引号+反斜杠的命令串会改写（ShellQuoteParser.quoteOne 双引号分支 + Windows 引号转义），
     * 导致 eval 收到被截断的串（printf 无格式参数报 usage）。本测试聚焦进度发射，规避该
     * 既有 Windows 命令传递局限（与本任务无关）。
     */
    private static String slowCommand() {
        return "echo start; sleep 2.5; echo end";
    }

    // ── t1 BashTool.execute 前台慢命令 → 经 sink 发射进度 ──

    @Test
    @DisplayName("t1 前台慢命令（>2s 阈值）经 bashProgressSink 发射进度：次数≥1 且字段对齐 CC payload")
    void t1_slowForegroundCommand_emitsProgressThroughSink() throws Exception {
        String sessionId = "sess-progress-" + UUID.randomUUID().toString().substring(0, 8);
        String callId = "progress-1";
        CopyOnWriteArrayList<BashTool.BashProgress> received = new CopyOnWriteArrayList<>();
        bashTool.setBashProgressSink(received::add);

        ToolResult<String> r;
        try {
            r = bashTool.execute(call(callId, slowCommand()), ctx(tempDir, sessionId));
        } catch (com.nexusai.application.agent.tool.ShellError e) {
            throw new AssertionError("ShellError: code=" + e.code()
                + " stdout=[" + e.stdout() + "] stderr=[" + e.stderr() + "]", e);
        }

        // 主结果路径不受影响（进度是旁路发射）
        assertThat(r.data()).contains("end");

        // 进度事件被发射：至少 1 条（2s 阈值后首 tick 必发；进程退出前可能再发一条）
        assertThat(received)
            .as("前台命令运行超 2s 阈值必须发射 ≥1 条进度（此前生产 never 发射）")
            .isNotEmpty();

        BashTool.BashProgress last = received.get(received.size() - 1);
        assertThat(last.sessionId()).isEqualTo(sessionId);
        assertThat(last.toolCallId()).isEqualTo(callId);
        // CC payload 字段：fullOutput 累计输出含首行；totalLines/totalBytes 计数；elapsed 相对命令启动
        assertThat(last.fullOutput()).contains("start");
        assertThat(last.totalLines()).isGreaterThanOrEqualTo(1);
        assertThat(last.totalBytes()).isGreaterThan(0);
        assertThat(last.elapsedTimeSeconds())
            .as("elapsed 相对命令启动时刻（CC BashTool.tsx:1004 startTime），≥ 2s 阈值")
            .isGreaterThanOrEqualTo(2);
    }

    // ── t2 BashProgressPublisher → STOMP tool_call_progress 事件 ──

    @Test
    @DisplayName("t2 BashProgressPublisher 把 BashProgress 发射为 tool_call_progress STOMP 事件到会话流 topic")
    void t2_publisher_sendsToolCallProgressToSessionStream() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        BashProgressPublisher publisher = new BashProgressPublisher();
        publisher.setWsTemplate(ws);

        BashTool.BashProgress p = new BashTool.BashProgress(
            "line-two\n", "line-one\nline-two\n", 3L, 2L, 22L, "sess-pub-1", "call-pub-1");

        publisher.accept(p);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/sessions/sess-pub-1/stream"),
            payloadCaptor.capture());

        assertThat(payloadCaptor.getValue())
            .isInstanceOf(ToolCallProgressEvent.class);
        ToolCallProgressEvent evt = (ToolCallProgressEvent) payloadCaptor.getValue();
        assertThat(evt.getType()).isEqualTo("tool_call_progress");
        assertThat(evt.getSessionId()).isEqualTo("sess-pub-1");
        assertThat(evt.getToolCallId()).isEqualTo("call-pub-1");
        // CC payload 字段对齐
        assertThat(evt.getOutput()).isEqualTo("line-two\n");
        assertThat(evt.getFullOutput()).isEqualTo("line-one\nline-two\n");
        assertThat(evt.getElapsedTimeSeconds()).isEqualTo(3L);
        assertThat(evt.getTotalLines()).isEqualTo(2L);
        assertThat(evt.getTotalBytes()).isEqualTo(22L);
    }

    // ── t3 fail-soft：wsTemplate 未注入 → 跳过推送不抛错 ──

    @Test
    @DisplayName("t3 fail-soft：wsTemplate 未注入（无 WebSocket 场景）→ 跳过推送，不破坏调用方")
    void t3_publisher_withoutWsTemplate_noOp() {
        BashProgressPublisher publisher = new BashProgressPublisher(); // 未注入 wsTemplate

        BashTool.BashProgress p = new BashTool.BashProgress(
            "out\n", "out\n", 1L, 1L, 4L, "sess-pub-2", "call-pub-2");

        assertThatCode(() -> publisher.accept(p))
            .as("wsTemplate null 时不抛错（fail-soft，对齐 StreamingToolExecutor push 短路语义）")
            .doesNotThrowAnyException();
    }

    // ── t4 fail-soft：sessionId 空 → 跳过推送 ──

    @Test
    @DisplayName("t4 fail-soft：sessionId 空 → 跳过推送，不调用 convertAndSend")
    void t4_publisher_blankSessionId_noSend() {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        BashProgressPublisher publisher = new BashProgressPublisher();
        publisher.setWsTemplate(ws);

        BashTool.BashProgress p = new BashTool.BashProgress(
            "out\n", "out\n", 1L, 1L, 4L, null, "call-pub-3");

        publisher.accept(p);

        verifyNoInteractions(ws);
    }
}

package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.compact.CompactProgressEvent;
import com.nexusai.application.agent.compact.CompactProgressState;
import com.nexusai.application.agent.compact.CompactWarningState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [批 2 · C 类] 子代理循环路径的压缩推送注册回归测试。
 *
 * <p><b>缺陷（R2 实证）</b>：{@code SubagentExecutor} 直调<b>静态</b> {@code LlmAgentLoop.queryLoop}，
 * <b>不经 {@code run()}</b> —— 而 {@code run()} 是 {@code CompactWarningState.pushContext} /
 * {@code CompactProgressState.push} 的<b>唯一生产注册点</b>（子代理 loop 跑在工具池 / asyncWorker
 * 线程，ThreadLocal 不跨线程）⇒ 子代理 reactive 压缩（其 {@code ctx.reactiveCompactor()} 由
 * {@code AgentLoopContextFactory} bean 注入，与主循环同源、生产可达）的进度推送静默丢弃：
 * token-warning 侧 {@code CompactWarningState:206-212} 静默 return（仅 DEBUG）；progress 侧
 * {@code CompactProgressState.current()} 返 null → {@code CompactConversationContext:186} 回落
 * {@code TUC.onCompactProgress} 字段，而该字段生产无任何接线者 ⇒ 恒 noop。
 *
 * <p><b>修法</b>：注册体抽为单点 {@code LlmAgentLoop.registerSessionPushContext(ws, sessionId)}
 * （{@code run()} 与子代理路径共用），子代理路径在 queryLoop 前后成对注册/清除。
 *
 * <p><b>夹具说明</b>：用例 1/2 全部在<b>独立真实池线程</b>（模拟子代理 loop 线程）上执行，
 * 且先断言「未注册时两条通道均静默」再断言「注册后两条通道均达 STOMP」——同一条用例内自带
 * <b>缺陷态对照</b>（反向实验的一部分）。用例 3 是<b>接线守卫（源级）</b>：只守「子代理调用点
 * 存在且成对」，不守运行时行为（运行时行为由用例 1 覆盖）——命名与断言已按此边界标注。
 */
@DisplayName("[批 2 · C] 子代理循环路径压缩推送注册（池线程 · 双通道）")
class LlmAgentLoopSessionPushContextTest {

    private static final String POOL_THREAD_NAME = "test-subagent-loop-pool";

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private ExecutorService newSubagentLoopPool() {
        pool = Executors.newSingleThreadExecutor(r -> new Thread(r, POOL_THREAD_NAME));
        return pool;
    }

    private void runOnPoolThread(ExecutorService exec, Runnable body) throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        exec.execute(() -> {
            try {
                threadName.set(Thread.currentThread().getName());
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).as("池线程任务须在 10s 内完成").isTrue();
        if (failure.get() != null) {
            throw new AssertionError("池线程执行抛出: " + failure.get(), failure.get());
        }
        assertThat(threadName.get()).isEqualTo(POOL_THREAD_NAME);
    }

    // ══════════════════════════════════════════════════════════════════════
    // #1 未注册 → 双通道静默（缺陷态）；注册后 → 双通道达 STOMP（修复态）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("池线程：未注册双通道静默 → registerSessionPushContext 后 progress+token-warning 均达 STOMP → clear 还原")
    void poolThread_register_CarriesBothCompactChannels() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        String sessionId = "sess-c0ffee01";

        runOnPoolThread(newSubagentLoopPool(), () -> {
            // ── token-warning 侧：未注册（= 修复前的子代理 loop 线程状态）──
            CompactWarningState.publishTokenWarning(sessionId, false, 123L, 200_000L, 42);
            verify(ws, never()).convertAndSend(any(String.class), any(Object.class));
            // compact-progress 消费点1（ccCtx）与消费点2（StreamCompactSummary:886）都读 current()，
            // 未注册 → 无 sink 可推（此处以 current()==null 表达「静默 return」的根因）。

            // ── [批 5a] 进度通道：显式 sink 工厂产物，池线程直接可用（与线程身份无关）──
            //   原断言「ThreadLocal 不跨线程 ⇒ 池线程读不到 current()」——载体已删，
            //   该缺口不复存在（这正是本改造要消灭的失效模式）。
            java.util.function.Consumer<CompactProgressEvent> sink =
                LlmAgentLoop.compactProgressSink(ws, sessionId);
            assertThat(sink).as("ws+sessionId 齐备 → 有 sink（与线程无关）").isNotNull();

            // 消费点1/2：进度事件 → STOMP compact-progress topic
            sink.accept(new CompactProgressEvent.SummaryProgress(7));
            ArgumentCaptor<Object> progressPayload = ArgumentCaptor.forClass(Object.class);
            verify(ws).convertAndSend(eq(CompactProgressState.topic(sessionId)), progressPayload.capture());
            assertThat(progressPayload.getValue()).as("载荷 = toFrontendJson(事件)").isInstanceOf(ObjectNode.class);
            assertThat(((ObjectNode) progressPayload.getValue()).path("type").asText())
                .as("前端契约 type 字段").isNotBlank();

            // ── token-warning 侧：注册（= run()/子代理同源单点，[批 5a] 本通道未改）──
            boolean registered = LlmAgentLoop.registerSessionPushContext(ws, sessionId);
            assertThat(registered).as("ws+sessionId 齐备 → 已注册").isTrue();

            // 消费点3：token-warning → STOMP token-warning topic
            CompactWarningState.publishTokenWarning(sessionId, false, 123L, 200_000L, 42);
            ArgumentCaptor<Object> warningPayload = ArgumentCaptor.forClass(Object.class);
            verify(ws).convertAndSend(eq("/topic/sessions/" + sessionId + "/token-warning"),
                warningPayload.capture());
            assertThat(warningPayload.getValue()).isInstanceOf(AgentEvent.TokenWarning.class);
            assertThat(((AgentEvent.TokenWarning) warningPayload.getValue()).sessionId()).isEqualTo(sessionId);

            // ── 成对清除（防线程复用串台）→ 再推必须静默（次数不增）──
            LlmAgentLoop.clearSessionPushContext(registered);
            CompactWarningState.publishTokenWarning(sessionId, false, 1L, 1L, 1);
            verify(ws, times(1)).convertAndSend(eq("/topic/sessions/" + sessionId + "/token-warning"),
                any(Object.class));
            verify(ws, times(1)).convertAndSend(eq(CompactProgressState.topic(sessionId)), any(Object.class));
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // #2 非 STOMP 路径（ws==null）→ 不注册（差分对照）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ws==null（非 STOMP 路径）→ 返回 false 且不注册（推送安全跳过 · 差分对照）")
    void nullWs_skipsRegistration() throws Exception {
        AtomicBoolean registered = new AtomicBoolean(true);

        runOnPoolThread(newSubagentLoopPool(), () -> {
            registered.set(LlmAgentLoop.registerSessionPushContext(null, "sess-c0ffee02"));
            assertThat(LlmAgentLoop.compactProgressSink(null, "sess-c0ffee02"))
                .as("ws 缺失 → 无 sink（调用方按「不推」处理）").isNull();
        });

        assertThat(registered.get()).as("无 wsTemplate → false（调用方据此跳过 clear）").isFalse();
    }

    @Test
    @DisplayName("sessionId==null → 返回 false 且不注册（差分对照）")
    void nullSessionId_skipsRegistration() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        AtomicBoolean registered = new AtomicBoolean(true);

        runOnPoolThread(newSubagentLoopPool(), () ->
            registered.set(LlmAgentLoop.registerSessionPushContext(ws, null)));
        assertThat(LlmAgentLoop.compactProgressSink(ws, null))
            .as("sessionId 缺失 → 无 sink").isNull();

        assertThat(registered.get()).isFalse();
        verify(ws, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // #3 接线守卫（源级）：子代理调用点存在且成对
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[接线守卫·源级] SubagentExecutor 在 queryLoop 前注册 / 后清除；run() 走同一单点")
    void subagentPath_registrationIsWired() throws Exception {
        // 本用例只守「调用点存在且成对」（运行时行为由用例 1 覆盖）。若有人删除子代理侧的注册
        // 调用，本用例变红（这是它唯一的鉴别力，已在 @DisplayName 标注为源级守卫）。
        String subagent = readSource(
            "src/main/java/com/nexusai/application/agent/tool/impl/SubagentExecutor.java");
        int register = subagent.indexOf("registerSessionPushContext(");
        int loopCall = subagent.indexOf("LlmAgentLoop.queryLoop(");
        int clear = subagent.indexOf("clearSessionPushContext(");
        assertThat(register).as("子代理路径必须注册推送上下文").isGreaterThan(-1);
        assertThat(loopCall).as("子代理路径必须调静态 queryLoop（本缺陷的前提）").isGreaterThan(-1);
        assertThat(clear).as("子代理路径必须成对清除").isGreaterThan(-1);
        assertThat(register).as("注册必须在 queryLoop 之前").isLessThan(loopCall);
        assertThat(clear).as("清除必须在 queryLoop 之后").isGreaterThan(loopCall);

        String loop = readSource("src/main/java/com/nexusai/application/agent/LlmAgentLoop.java");
        assertThat(loop).as("run() 必须复用同一注册单点（否则两条路径分叉）")
            .contains("registerSessionPushContext(this.wsTemplate, params.sessionId())");
        assertThat(loop).as("run() 必须成对清除")
            .contains("clearSessionPushContext(sessionPushRegistered)");
    }

    private static String readSource(String relativePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader reader =
                 new java.io.BufferedReader(new java.io.FileReader(relativePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}

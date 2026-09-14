package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 压缩推送上下文 · token-warning 通道（[批 5a-2] 显式化后的回归测试）。
 *
 * <p><b>改造前（缺陷态）</b>：{@code CompactWarningState.pushContext} 是 {@code ThreadLocal}，
 * 靠 {@code LlmAgentLoop.run()} / manual / <b>子代理</b>三处成对注册维持。子代理直调静态
 * {@code queryLoop} 不经 {@code run()}，且 loop 跑在工具池 / asyncWorker 线程 ⇒
 * 「ThreadLocal 不跨线程」使子代理压缩的 token-warning 推送静默丢弃，必须**额外补偿注册**
 * （原 {@code SubagentExecutor} 的 {@code registerSessionPushContext} 调用即为此而生）。
 *
 * <p><b>改造后</b>：载体删除，push 上下文由 {@link LlmAgentLoop#compactWarningPushContext} 在
 * **消费点就地构造** —— 两个输入 {@code ctx.wsTemplate()} 与 {@code state.sessionId()} 本就随
 * loop 参数显式携带 ⇒ 任一线程上都可得，补偿注册全部消失。
 *
 * <p><b>夹具说明（规则九）</b>：用例 1 在<b>独立真实池线程</b>上执行并断言「推送真的到达正确
 * topic + 载荷 sessionId 正确」；用例 2 是<b>差分对照</b>（ws 缺失 ⇒ 无 pushCtx ⇒ 一条都不推，
 * 且不抛 = 不阻断压缩）。二者合起来才能区分「推送可用」与「静默吞掉」。
 */
@DisplayName("[批 5a-2] token-warning 推送上下文（显式就地构造 · 池线程可达）")
class LlmAgentLoopSessionPushContextTest {

    private static final String POOL_THREAD_NAME = "test-subagent-loop-pool-1";

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        CompactWarningState.resetForTesting();
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
    // #1 池线程上由 (ctx.wsTemplate(), state.sessionId()) 就地构造 → 推达 STOMP
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("池线程：compactWarningPushContext 就地构造 → publishTokenWarning 推达 token-warning topic（载荷 sessionId 正确）")
    void derivedPushContext_fromPoolThread_reachesTokenWarningTopic() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        String sessionId = "sess-c0ffee01";
        AgentState state = new AgentState("sys", sessionId, (java.util.UUID) null);
        AgentLoopContext ctx = TestContexts.agentLoopContextWithWs(null, null, null, ws);

        runOnPoolThread(newSubagentLoopPool(), () -> {
            // 前置对照：未构造 push 上下文时（null）不推 —— 证明下面那次推送不是「总能推」
            CompactWarningState.publishTokenWarning(null, false, 123L, 200_000L, 42);
            verify(ws, never()).convertAndSend(any(String.class), any(Object.class));

            // 就地构造（= 生产消费点同款调用）
            CompactWarningState.SessionPushContext pushCtx =
                LlmAgentLoop.compactWarningPushContext(ctx, state);
            assertThat(pushCtx).as("ws+sessionId 齐备 → 有 push 上下文（与线程身份无关）").isNotNull();
            assertThat(pushCtx.sessionId()).as("sessionId 取自 state（= 原注册用的 params/subagentCtx 值）")
                .isEqualTo(sessionId);

            CompactWarningState.publishTokenWarning(pushCtx, false, 123L, 200_000L, 42);

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(ws).convertAndSend(eq("/topic/sessions/" + sessionId + "/token-warning"),
                payload.capture());
            assertThat(payload.getValue()).isInstanceOf(AgentEvent.TokenWarning.class);
            assertThat(((AgentEvent.TokenWarning) payload.getValue()).sessionId())
                .as("载荷 sessionId = 会话 ID（前端据此归属）").isEqualTo(sessionId);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // #2/#3 差分对照：ws / sessionId 缺失 → 无 push 上下文（跳过推送，不抛）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("wsTemplate==null（非 STOMP 路径）→ 无 push 上下文：跳过推送且不抛（不阻断压缩）")
    void nullWs_yieldsNullPushContext_andPublishDoesNotThrow() throws Exception {
        AgentState state = new AgentState("sys", "sess-c0ffee02", (java.util.UUID) null);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, null, null, null, null);
        assertThat(ctx.wsTemplate()).as("前置：该 ctx 无 wsTemplate").isNull();

        runOnPoolThread(newSubagentLoopPool(), () -> {
            CompactWarningState.SessionPushContext pushCtx =
                LlmAgentLoop.compactWarningPushContext(ctx, state);
            assertThat(pushCtx).as("ws 缺失 → 无 push 上下文").isNull();
            // (b) 类：跳过推送但**不抛**（压缩照常），且 ≥WARN 可观测由 CompactWarningState 承担。
            //   触发点 3（publishTokenWarning）本身不改 store；触发点 1（suppress）才推进 store。
            CompactWarningState.publishTokenWarning(pushCtx, true, 1L, 1L, 1);
            CompactWarningState.suppressCompactWarning(state.sessionId(), pushCtx);
            assertThat(CompactWarningState.isCompactWarningSuppressed(state.sessionId()))
                .as("store 状态仍被推进（跳过的是推送，不是状态机）").isTrue();
        });
    }

    @Test
    @DisplayName("sessionId==null → 无 push 上下文（差分对照）")
    void nullSessionId_yieldsNullPushContext() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        AgentState stateNoSid = new AgentState("sys", null, (java.util.UUID) null);
        AgentLoopContext ctx = TestContexts.agentLoopContextWithWs(null, null, null, ws);

        runOnPoolThread(newSubagentLoopPool(), () -> {
            assertThat(LlmAgentLoop.compactWarningPushContext(ctx, stateNoSid))
                .as("sessionId 缺失 → 无 push 上下文").isNull();
            verify(ws, never()).convertAndSend(any(String.class), any(Object.class));
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // #4 子代理路径行为等价：topic 用父会话 short id（= 原注册值）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("子代理路径行为等价：push 上下文 sessionId = state.sessionId()（子代理 state 由 subagentCtx.sessionId() 构造）")
    void subagentPath_topicUsesParentSessionId() {
        // SubagentExecutor:4032 `String sessionId = subagentCtx.sessionId()` → :4050
        // `new AgentState(agentSystemPrompt, sessionId, agentId)` ⇒ state.sessionId() 就是原
        // registerSessionPushContext 用的那个值 ⇒ 推导出的 topic 逐字不变（改造前后一致）。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        String parentSessionId = "sess-parent01";
        AgentState subagentState = new AgentState("subagent-sys", parentSessionId, java.util.UUID.randomUUID());
        AgentLoopContext subagentCtx = TestContexts.agentLoopContextWithWs(null, null, null, ws);

        CompactWarningState.SessionPushContext pushCtx =
            LlmAgentLoop.compactWarningPushContext(subagentCtx, subagentState);
        assertThat(pushCtx).as("子代理路径同样可得 push 上下文（无需补偿注册）").isNotNull();
        assertThat(pushCtx.sessionId()).as("父会话 short id（与前端 useChatSocket 订阅的 topic 一致）")
            .isEqualTo(parentSessionId);

        CompactWarningState.publishTokenWarning(pushCtx, true, 9L, 100L, 10);
        verify(ws).convertAndSend(eq("/topic/sessions/" + parentSessionId + "/token-warning"),
            any(Object.class));
    }
}

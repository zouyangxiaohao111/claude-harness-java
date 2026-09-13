package com.nexusai.infra.llm;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.common.RequestContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 3b] {@code AnthropicSdkProvider.consumePostCompactionAtApiSuccess} 的会话源
 * 在<b>真实虚拟线程</b>（STREAM_EXECUTOR 等价物）上的实证 + 残留 MDC 反向对照。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：该方法由 {@code doStream}（成功路径）在
 * {@code LlmAgentLoop.STREAM_EXECUTOR}（{@code Executors.newVirtualThreadPerTaskExecutor()}）
 * 的虚拟线程上调用。旧实现：
 * <pre>
 *   String sessionId = SessionIdResolver.fromHistory(history);
 *   if (sessionId == null) sessionId = RequestContext.sessionId();   // ← 批 3b 删除
 * </pre>
 * 虚拟线程不继承创建线程的 ThreadLocal ⇒ 该 MDC 读要么 null（靠 LlmAgentLoop 的 MDC 回放装置
 * 兜住），要么是<b>该虚拟线程上一个任务残留的、别的会话的 id</b>（线程池复用第三态）。
 * 两者都会让 {@link PostCompactionState#consumePostCompaction(String)} 消费到错误的会话布尔
 * ⇒ 遥测把「压缩导致的 cache miss」归错会话。
 *
 * <p>本测试的鉴别力来自<b>在被调线程本身上写残留 MDC</b>（模拟第三态），断言：
 * <ul>
 *   <li>history 携带的会话被消费（显式源生效）；</li>
 *   <li>残留 MDC 指向的<b>另一个</b>会话<b>未</b>被消费（旧实现必红）；</li>
 *   <li>求值线程 ≠ 断言线程（真虚拟线程）。</li>
 * </ul>
 */
@DisplayName("批 3b · provider 压缩后标记消费：会话源 = history（虚拟线程 + 残留 MDC 反向对照）")
class PostCompactionSessionExplicitDerivedThreadTest {

    private static final String SESSION_HISTORY = "sess-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String SESSION_STALE = "sess-" + UUID.randomUUID().toString().substring(0, 8);

    private SessionAgentStateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionAgentStateRegistry();
        registry.register(SESSION_HISTORY, new AgentState("system", SESSION_HISTORY, UUID.randomUUID()));
        registry.register(SESSION_STALE, new AgentState("system", SESSION_STALE, UUID.randomUUID()));
        PostCompactionState.setSessionAgentStateRegistry(registry);
        PostCompactionState.reset();
    }

    @AfterEach
    void tearDown() {
        PostCompactionState.setSessionAgentStateRegistry(null);
        PostCompactionState.reset();
        RequestContext.clear();
    }

    /** 反射调用被测私有方法（真实 production 方法体；入参 history 即生产的显式载体）。 */
    private static void invokeConsumeAtApiSuccess(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod(
            "consumePostCompactionAtApiSuccess", List.class);
        m.setAccessible(true);
        m.invoke(null, history);
    }

    private static ChatMessageDto msgWithSession(String sessionId) {
        return new ChatMessageDto(null, sessionId, Role.user, null, "hi",
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    /** 在真实虚拟线程上跑被测方法；线程内先写入「上一个任务残留的、别的会话」的 MDC。 */
    private static void onVirtualThreadWithStaleMdc(List<ChatMessageDto> history,
                                                    AtomicReference<String> threadName,
                                                    AtomicReference<String> staleMdcSeen) throws Exception {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        try {
            vt.submit(() -> {
                threadName.set(Thread.currentThread().getName());
                RequestContext.set(SESSION_STALE, "msg-stale-3b");
                staleMdcSeen.set(RequestContext.sessionId());
                try {
                    invokeConsumeAtApiSuccess(history);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    RequestContext.clear();
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } finally {
            vt.shutdownNow();
        }
    }

    @Test
    @DisplayName("① history 带会话 → 消费该会话的标记；虚拟线程上的残留 MDC（别的会话）不被消费")
    void consumeAtApiSuccess_usesHistorySession_notResidualMdc() throws Exception {
        PostCompactionState.markPostCompaction(SESSION_HISTORY);
        PostCompactionState.markPostCompaction(SESSION_STALE);

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> staleMdcSeen = new AtomicReference<>();
        onVirtualThreadWithStaleMdc(List.of(msgWithSession(SESSION_HISTORY)), threadName, staleMdcSeen);

        assertThat(threadName.get())
            .as("必须在虚拟线程（STREAM_EXECUTOR 等价物）执行，而非断言线程")
            .isNotNull()
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(staleMdcSeen.get())
            .as("前置条件：该虚拟线程上确实存在残留 MDC（第三态）")
            .isEqualTo(SESSION_STALE);
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_HISTORY))
            .as("history 携带的会话的压缩后标记必须被消费（显式源生效）")
            .isFalse();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_STALE))
            .as("残留 MDC 指向的另一会话<b>不得</b>被消费 —— 旧实现（读 MDC）必红：它会消费 %s",
                SESSION_STALE)
            .isTrue();
    }

    @Test
    @DisplayName("② history 无 sessionId（不可解析）→ 不消费任何会话（进程级回落语义不动）；残留 MDC 不参与")
    void consumeAtApiSuccess_noHistorySession_doesNotConsultMdc() throws Exception {
        PostCompactionState.markPostCompaction(SESSION_STALE);

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> staleMdcSeen = new AtomicReference<>();
        // history 存在但无 sessionId（如 chatWithRaw 的 null history 等价场景）
        onVirtualThreadWithStaleMdc(List.of(msgWithSession(null)), threadName, staleMdcSeen);

        assertThat(staleMdcSeen.get()).isEqualTo(SESSION_STALE);
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_STALE))
            .as("history 无法解析会话时不得回落 MDC 消费 %s（批 3b 已删该兜底；缺值仅 WARN）",
                SESSION_STALE)
            .isTrue();
    }
}

package com.nexusai.infra.llm;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.PostCompactionState;
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
 * 在<b>真实虚拟线程</b>（STREAM_EXECUTOR 等价物）上的实证。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：该方法由 {@code doStream}（成功路径）在
 * {@code LlmAgentLoop.STREAM_EXECUTOR}（{@code Executors.newVirtualThreadPerTaskExecutor()}）
 * 的虚拟线程上调用。旧实现：
 * <pre>
 *   String sessionId = SessionIdResolver.fromHistory(history);
 *   if (sessionId == null) sessionId = &lt;裸 MDC 的会话槽&gt;;   // ← 批 3b 删除兜底 / 批 3c 删载体
 * </pre>
 * 虚拟线程不继承创建线程的 ThreadLocal ⇒ 该 ambient 读要么 null，要么是<b>该虚拟线程上一个任务
 * 残留的、别的会话的 id</b>（线程池复用第三态）。两者都会让
 * {@link PostCompactionState#consumePostCompaction(String)} 消费到错误的会话布尔
 * ⇒ 遥测把「压缩导致的 cache miss」归错会话。
 *
 * <p>本测试锁定（在<b>真实虚拟线程</b>上求值）：
 * <ul>
 *   <li>history 携带的会话被消费（显式源生效）；</li>
 *   <li>另一个（未被 history 携带的）会话<b>未</b>被消费；</li>
 *   <li>求值线程 ≠ 断言线程（真虚拟线程）。</li>
 * </ul>
 *
 * <p><b>[批 3c] 语义消失（已登记待裁定）</b>：原用例在被调线程本身写「别的会话」的残留 MDC
 * 当诱饵（第三态），并断言①该诱饵确实存在（前置条件）②诱饵会话未被消费。批 3c 把裸 MDC
 * 会话槽<b>整类删除</b> ⇒ 诱饵无法再构造，上述两条断言已删除（见
 * {@code onVirtualThread} 内 `[批 3c]` 注释）。剩余真虚拟线程 + 显式源两条断言仍有效。
 */
@DisplayName("批 3b · provider 压缩后标记消费：会话源 = history（真实虚拟线程）")
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

    /** 在真实虚拟线程上跑被测方法（STREAM_EXECUTOR 等价物）。 */
    private static void onVirtualThread(List<ChatMessageDto> history,
                                        AtomicReference<String> threadName) throws Exception {
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        try {
            vt.submit(() -> {
                threadName.set(Thread.currentThread().getName());
                // [批 3c] 语义消失：原此处写「上一个任务残留的、别的会话」的裸 MDC 当诱饵（第三态），
                //   并捕获其值做前置条件断言。该裸 MDC 会话槽已整类删除（主代码
                //   consumePostCompactionAtApiSuccess 的该兜底也已删）⇒ 诱饵与观察点均无法构造，
                //   相关断言已随之删除（登记在类 javadoc 与批报告里）。
                try {
                    invokeConsumeAtApiSuccess(history);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } finally {
            vt.shutdownNow();
        }
    }

    @Test
    @DisplayName("① history 带会话 → 只消费该会话的标记；未被 history 携带的会话不被消费")
    void consumeAtApiSuccess_usesHistorySession_notResidualMdc() throws Exception {
        PostCompactionState.markPostCompaction(SESSION_HISTORY);
        PostCompactionState.markPostCompaction(SESSION_STALE);

        AtomicReference<String> threadName = new AtomicReference<>();
        onVirtualThread(List.of(msgWithSession(SESSION_HISTORY)), threadName);

        assertThat(threadName.get())
            .as("必须在虚拟线程（STREAM_EXECUTOR 等价物）执行，而非断言线程")
            .isNotNull()
            .isNotEqualTo(Thread.currentThread().getName());
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_HISTORY))
            .as("history 携带的会话的压缩后标记必须被消费（显式源生效）")
            .isFalse();
        assertThat(PostCompactionState.isPostCompactionPending(SESSION_STALE))
            .as("未被 history 携带的另一会话<b>不得</b>被消费（会话源只认 history）")
            .isTrue();
    }

    @Test
    @DisplayName("② history 无 sessionId（不可解析）→ 不消费任何会话（缺值不回落 ambient；进程级语义不动）")
    void consumeAtApiSuccess_noHistorySession_doesNotConsultMdc() throws Exception {
        PostCompactionState.markPostCompaction(SESSION_STALE);

        AtomicReference<String> threadName = new AtomicReference<>();
        // history 存在但无 sessionId（如 chatWithRaw 的 null history 等价场景）
        onVirtualThread(List.of(msgWithSession(null)), threadName);

        assertThat(PostCompactionState.isPostCompactionPending(SESSION_STALE))
            .as("history 无法解析会话时不得回落 ambient 会话消费 %s（批 3b 已删该兜底；缺值仅 WARN）",
                SESSION_STALE)
            .isTrue();
    }
}

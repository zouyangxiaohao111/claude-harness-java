package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 3c · 2026-09-13] <b>工具池线程的会话标识 = 显式传参，且跨会话不串</b>（真线程隔离测试）。
 *
 * <h2>本类取代 {@code StreamingToolExecutorMdcPropagationTest}（同类已删）</h2>
 * <p>旧类锁的是「{@code executeAsync} 调度线程捕获 MDC context map → 池线程
 * {@code MDC.setContextMap} 回放 → 池线程 {@code RequestContext.requestId()} 非 null」。
 * 那套装置的本质是<b>用环境态会话槽（ThreadLocal/MDC）冒充显式传递</b>，已于批 3c 连同
 * {@code RequestContext} 类一起删除。旧夹具的断言形状（「池线程读到的 MDC == 父线程设的值」）
 * 在新架构下<b>无法表达</b> —— 因为它断言的是「回放是否发生」，而回放这件事已不存在。
 * 故本类<b>重新表达</b>为继续有意义、且更强的性质：
 * <b>池线程上工具拿到的会话标识，只能来自显式传入的 {@code ToolUseContext}，且必须是本会话自己的</b>。
 *
 * <h2>为什么这条性质重要（WHY，而非 WHAT）</h2>
 * <p>{@code StreamingToolExecutor} 用 {@code CompletableFuture.runAsync(..., executor)} 在
 * fixed-N 池线程执行工具，<b>ThreadLocal 不跨线程</b>。历史上正因为如此，才需要 «捕获-回放» 把父线程的
 * MDC 搬过去；而回放机制的毒点在于：池线程复用下，若某次任务**没有**回放（或回放到别会话的值），
 * 工具就会读到<b>上一个任务残留的、别的会话的标识</b>（第三态：不是 null，是看起来合法的错值）。
 * 批 3c 删掉会话槽后，同一线程上先后跑两个不同会话的工具时，**唯一**的会话来源是各自任务显式携带的
 * {@code ToolUseContext} —— 本类用同一个固定池（复用线程）+ 两个不同会话，把这个性质变成可证伪的断言。
 *
 * <h2>判别力（若有人把会话来源改回线程局部槽，本类必红）</h2>
 * <ul>
 *   <li>两个会话**先后**在**同一个**池线程上执行（fixed(1) 池 + 预热，保证线程复用）；</li>
 *   <li>断言每次执行读到的是<b>本次的</b> sessionId（不是上一次的、也不是 null）；</li>
 *   <li>断言探针确实跑在池线程（非测试线程）—— 验证跨线程面真实存在。</li>
 * </ul>
 */
@DisplayName("批 3c · 工具池线程会话标识来自显式 ToolUseContext（真线程跨会话隔离，取代原 MDC 传播测试）")
class StreamingToolExecutorCrossSessionIsolationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 在工具执行线程（池线程）读取注入的 ToolUseContext 会话标识的探针工具。 */
    private static Tool probeTool(AtomicReference<String> seenSessionId,
                                  AtomicReference<String> seenThreadName) {
        return new Tool() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "probe sessionId from ToolUseContext on tool thread"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

            /** 单参重载：**不应**被本测试依赖（会话只能来自这里显式拒绝的路径）。 */
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                seenSessionId.set("<single-arg-execute-was-called>");
                return ToolResult.success(call.id(), "done");
            }

            /** 唯一的会话来源：显式传入的 ctx。 */
            @Override public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
                seenThreadName.set(Thread.currentThread().getName());
                seenSessionId.set(ctx != null ? ctx.sessionId() : "<null-ctx>");
                return ToolResult.success(call.id(), "done");
            }
        };
    }

    /**
     * 预热池线程：在工具任务提交前先跑一个 no-op，使 fixed(1) 池线程在此刻创建。
     * WHY：池线程复用是本测试刻意的「污染面」；预热确保两次工具执行落在**同一个**线程上，
     * 「第二次读到第一次的会话」这条错误路径因此真实可达（否则新线程天然干净，测试无判别力）。
     */
    private static void warmUpPool(ExecutorService pool) throws Exception {
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("同一池线程先后执行两个会话的工具 → 各读到**本次**会话标识，不残留上一次（真线程隔离）")
    void twoSessionsOnSamePoolThread_doNotBleed() throws Exception {
        String sessionA = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionB = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        AtomicReference<String> seenSessionId = new AtomicReference<>();
        AtomicReference<String> seenThreadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        warmUpPool(pool);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seenSessionId, seenThreadName));
        try {
            // ── 会话 A ──
            StreamingToolExecutor execA = new StreamingToolExecutor(registry, pool, ctxFor(sessionA));
            execA.add(call("c-a", "probe"));
            List<ToolResult> resultsA = execA.getRemainingResults();
            assertThat(resultsA).hasSize(1);

            assertThat(seenThreadName.get())
                .as("探针必须在池线程执行（非测试线程）—— 验证跨线程面真实存在")
                .isNotEqualTo(Thread.currentThread().getName());
            String threadOfA = seenThreadName.get();
            assertThat(seenSessionId.get())
                .as("会话 A 的工具必须读到 A 的 sessionId（来自显式 ToolUseContext）")
                .isEqualTo(sessionA);

            // ── 会话 B：同一池（fixed(1) → 复用同一线程）──
            StreamingToolExecutor execB = new StreamingToolExecutor(registry, pool, ctxFor(sessionB));
            execB.add(call("c-b", "probe"));
            List<ToolResult> resultsB = execB.getRemainingResults();
            assertThat(resultsB).hasSize(1);

            assertThat(seenThreadName.get())
                .as("两次执行必须落在同一池线程上（fixed(1) + 预热）—— 否则本用例的「复用污染面」不成立，"
                    + "断言会退化成无判别力的顺次检查")
                .isEqualTo(threadOfA);
            assertThat(seenSessionId.get())
                .as("会话 B 的工具必须读到 B 的 sessionId，**绝不能**残留 A 的值（第三态串会话）；"
                    + "若此处读到 %s 或 null，说明会话标识退化成了线程局部槽（MDC 回放式的环境态传递）",
                    sessionA)
                .isEqualTo(sessionB);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("工具只有单参 execute（不覆写 ctx 重载）时不得被当作会话来源 —— 会话只认显式 ToolUseContext")
    void singleArgExecute_isNotASessionSource() throws Exception {
        String session = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<String> seenSessionId = new AtomicReference<>();
        AtomicReference<String> seenThreadName = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        warmUpPool(pool);
        ToolRegistry registry = new ToolRegistry().register(probeTool(seenSessionId, seenThreadName));
        try {
            StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, ctxFor(session));
            exec.add(call("c1", "probe"));
            exec.getRemainingResults();

            // 本断言是「判别力守卫」：若派发退化成只调单参 execute，探针会写 <single-arg-execute-was-called>
            // —— 那意味着工具拿不到显式会话，会话来源又变成了某种隐式东西。
            assertThat(seenSessionId.get())
                .as("探针必须经 2 参 execute(call, ctx) 拿到显式会话；读到哨兵值说明派发链退化了")
                .isEqualTo(session);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext ctxFor(String sessionId) {
        return ToolUseContext.of(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> java.util.Collections.unmodifiableSet(java.util.Set.of()));
    }
}

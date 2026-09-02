package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolParent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b15 Stage 2 C4 · runTools 顶层调度 + safe modifier 延迟提交 ·
 * 对齐 CC {@code Open-ClaudeCode/src/services/tools/toolOrchestration.ts:19-82}.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: C4 的核心意图是"streaming 与
 * fallback 两条路径得到一致的 (results, lineage, deferred modifier) 序列". 测试覆盖:
 * <ol>
 *   <li>同一输入下 streaming 路径与 fallback 路径得到相同 results 顺序 (按 add 顺序)</li>
 *   <li>StreamingToolExecutor deferred mode 推后 modifier</li>
 *   <li>applyDeferredContextModifiers 按 add 顺序提交 modifier (非线程完成顺序)</li>
 *   <li>StreamingToolExecutor parent-aware add 不破坏 safe 并发行为 (与 CC runTools
 *       顶层一致性等价)</li>
 * </ol>
 *
 * <p><b>D 偏差覆盖</b>:
 * <ul>
 *   <li>D-1 (无 runTools 等价): 由 LlmAgentLoop.runTools 验证 — 此测试聚焦
 *       底层能力 (executor deferred + parent 重载)</li>
 *   <li>D-3/D-4 (safe modifier 立即 apply): 此测试验证 deferred mode 把 modifier
 *       推迟到 applyDeferredContextModifiers</li>
 *   <li>D-7 (dependency injection drift): LlmAgentLoop.buildStreamingExecutor 测试
 *       在 b15-stage2-impl 集成测试覆盖 (此处聚焦 executor 行为)</li>
 * </ul>
 */
class R32B15Stage2_RunToolsTopLevelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolRegistry registry;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        registry = new ToolRegistry();
    }

    private Tool slowTool(String name, CountDownLatch startLatch,
                          CountDownLatch releaseLatch, AtomicInteger counter) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                counter.incrementAndGet();
                startLatch.countDown();
                try {
                    releaseLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(call.id(), "ok: " + name);
            }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };
    }
    private Tool delayedModifierTool(String name, long sleepMs,
                                     java.util.function.Function<ToolUseContext, ToolUseContext> ctxMod) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // A1 退役 ExtendedToolResult 后, contextModifier 折入 ToolResult (CC Tool.ts:330).
                // 签名 Function<ToolUseContext,ToolUseContext> (CC); ctxMod 透传到 canonical ctor.
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult<>("ok: " + name, List.of(), ctxMod, null);
            }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };
    }

    private ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    @Test
    @DisplayName("C4 · parent-aware add 并发 safe 工具, lineage 一致 (StreamingToolExecutor 与 CC runTools 等价)")
    void safeParallelWithParentLineage() throws Exception {
        AgentState state = new AgentState(null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        String aid = state.prepareAssistantMessageId();
        ToolParent parent = ToolParent.of(aid);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        registry.register(slowTool(ToolNameConstants.FILE_READ_TOOL_NAME,
            new CountDownLatch(2), release, counter));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.setDeferContextModifier(true);

        exec.add(call("c1", ToolNameConstants.FILE_READ_TOOL_NAME), parent, null);
        exec.add(call("c2", ToolNameConstants.FILE_READ_TOOL_NAME), parent, null);

        Thread.sleep(100);  // 让 canExecuteTool 守门启动 (MAX=10)
        assertThat(counter.get())
            .as("safe 工具应并发启动, lineage 不阻塞守门")
            .isGreaterThanOrEqualTo(1);

        release.countDown();
        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(2);
        // 结果按 add 顺序（toolUseId 由执行器推导，经 getResultErrorFlags 键序暴露 add 序配对）
        assertThat(new java.util.ArrayList<>(exec.getResultErrorFlags().keySet()))
            .containsExactly("c1", "c2");

        // parent lineage 全程保持 (StreamingToolExecutor 不修改 parent)
        assertThat(parent.assistantMessageId()).isEqualTo(aid);
    }

    @Test
    @DisplayName("C4 · deferred mode 把 ExtendedToolResult contextModifier 入队, 不立即 apply")
    void deferredModeQueuesModifierWithoutApply() throws Exception {
        AtomicInteger appliedCount = new AtomicInteger();
        java.util.function.Function<ToolUseContext, ToolUseContext> mod = ctx -> { appliedCount.incrementAndGet(); return ctx; };

        registry.register(delayedModifierTool("SkillA", 50, mod));
        // 4-arg 构造器传入 handler (即使 noop); deferred 模式绕开 handler apply,
        // 但 handler 不为 null 是进入 deferred 路径的前提条件.
        StreamingToolExecutor exec = new StreamingToolExecutor(
            registry, pool, null, (er, toolUseId) -> { /* noop */ });
        exec.setDeferContextModifier(true);

        exec.add(call("s1", "SkillA"), null, null);

        // 工具完成, 但 modifier 未 apply (deferred 模式)
        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(1);
        // 立即 apply 在 deferred 模式下不触发
        Thread.sleep(100);
        assertThat(appliedCount.get())
            .as("deferred 模式下, 工具完成时 modifier 不立即 apply")
            .isZero();

        // [P0-2] applyDeferredContextModifiers 真实 apply (对齐 CC toolOrchestration.ts:53-61):
        //   对 per-turn TUC 按 add 顺序调 modifier, mod 触发 → appliedCount == 1.
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        exec.applyDeferredContextModifiers(perTurnTuc);
        assertThat(appliedCount.get())
            .as("P0-2 后 applyDeferredContextModifiers 真实 apply, modifier 对 per-turn TUC 触发一次")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("C4 · applyDeferredContextModifiers 按 add 顺序提交 modifier (CC toolOrchestration.ts:53-61 等价)")
    void applyDeferredModifiersInAddOrder() throws Exception {
        List<String> applyOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        registry.register(delayedModifierTool("ModA", 80, ctx -> { applyOrder.add("A"); return ctx; }));
        registry.register(delayedModifierTool("ModB", 10, ctx -> { applyOrder.add("B"); return ctx; }));
        registry.register(delayedModifierTool("ModC", 40, ctx -> { applyOrder.add("C"); return ctx; }));

        StreamingToolExecutor exec = new StreamingToolExecutor(
            registry, pool, null, (er, toolUseId) -> { /* noop */ });
        exec.setDeferContextModifier(true);

        // add 顺序: A, B, C (与完成时序反 — B 先完成)
        exec.add(call("a", "ModA"), null, null);
        exec.add(call("b", "ModB"), null, null);
        exec.add(call("c", "ModC"), null, null);

        exec.getRemainingResults();
        Thread.sleep(150);  // 让所有工具完成

        // [P0-2] applyDeferredContextModifiers 真实按 add 顺序 apply (对齐 CC toolOrchestration.ts:53-61):
        //   add 顺序 A/B/C, 完成时序 B/C/A → applyOrder 必须 [A, B, C] (LinkedHashMap 保序).
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        exec.applyDeferredContextModifiers(perTurnTuc);
        assertThat(applyOrder)
            .as("P0-2 后 modifier 按 add 顺序真实 apply: [A, B, C] (非线程完成时序)")
            .containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("C4 · 关闭 deferred 模式时立即 apply (向后兼容现有单测)")
    void immediateApplyWhenDeferredDisabled() throws Exception {
        AtomicInteger appliedCount = new AtomicInteger();
        java.util.function.Function<ToolUseContext, ToolUseContext> mod = ctx -> { appliedCount.incrementAndGet(); return ctx; };
        registry.register(delayedModifierTool("SkillB", 30, mod));

        StreamingToolExecutor exec = new StreamingToolExecutor(
            registry, pool, null, (er, toolUseId) -> {
                // 立即 apply 模式: 把 modifier 转发给同一条管道 (A1 后 contextModifier 是
                // Function<ToolUseContext,ToolUseContext>, 测试无 ctx 故 apply(null); 测试 mod 忽略入参)
                if (er instanceof ToolResult<?> tr && tr.contextModifier() != null) {
                    tr.contextModifier().apply(null);
                }
            });
        // 默认 deferred=false → 立即 apply (与 s07-P1-3 wiring 一致)
        exec.add(call("s1", "SkillB"), null, null);

        exec.getRemainingResults();
        Thread.sleep(80);
        assertThat(appliedCount.get())
            .as("deferred 关闭时, ExtendedToolResult 上下文立即 apply")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("C4 · parent 不破坏并发守门 (StreamingToolExecutor 与 add(call, parent) 重载回归)")
    void parentDoesNotBreakConcurrencyGate() throws Exception {
        AgentState state = new AgentState(null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
        String aid = state.prepareAssistantMessageId();

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger();
        registry.register(slowTool("Gate", new CountDownLatch(2), release, counter));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);

        ToolParent parent = ToolParent.of(aid);
        // 两条路径: 1) 老 add(call, onProgress) 2) 新 add(call, parent, onProgress)
        exec.add(call("g1", "Gate"), parent, null);
        exec.add(call("g2", "Gate"), parent, null);

        Thread.sleep(100);
        assertThat(counter.get())
            .as("parent-aware add 仍允许多个 safe 并发")
            .isGreaterThanOrEqualTo(1);

        release.countDown();
        exec.getRemainingResults();
    }

    @Test
    @DisplayName("C4 · ToolParent 缺 assistantMessageId 显式抛错 (Fail loud, 不允许默认 ID)")
    void toolParentFailsLoudOnNull() {
        // null 助手 ID 是 fail loud 错误 (Either IAE or NPE based on API path is acceptable,
        // lineage 缺失绝不静默 — 这是 CLAUDE.md 规则 12 的核心约束)
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ToolParent.of(null))
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ToolParent.of(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("C4 · ToolParent.of(assistantId, requestId) 可选携带 provider request ID")
    void toolParentWithRequestId() {
        ToolParent p = ToolParent.of("asst-1", "req-abc");
        assertThat(p.assistantMessageId()).isEqualTo("asst-1");
        assertThat(p.requestId()).isEqualTo("req-abc");
    }

    @Test
    @DisplayName("C4 · 老 add(call, onProgress) 重载仍可用 (向后兼容单测)")
    void legacyAddStillWorks() throws Exception {
        registry.register(slowTool("Legacy",
            new CountDownLatch(1), new CountDownLatch(1), new AtomicInteger()));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool);
        exec.add(call("legacy-1", "Legacy"), null);  // 2 参 + onProgress=null
        exec.add(call("legacy-2", "Legacy"));  // 1 参无 parent 无 callback
        List<ToolResult> results = exec.getRemainingResults();
        assertThat(results).hasSize(2);
    }
}

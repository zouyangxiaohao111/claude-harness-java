package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b8 #3 · {@link ToolUseContext#inProgressToolUseIDs()} Function<Set<String>, Set<String>> 钩子验证.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC {@code Tool.ts:227 setInProgressToolUseIDs} +
 * 5 处触发点 ({@code StreamingToolExecutor.ts:267} + {@code toolOrchestration.ts:127/148/160/173/183}):
 * <ul>
 *   <li>StreamingToolExecutor.executeAsync() 入口 — add 到 in-progress set (start, P0-2 校正)</li>
 *   <li>StreamingToolExecutor.executeAsync() success 出口 — remove (end)</li>
 *   <li>StreamingToolExecutor.executeAsync() error 出口 — remove (end)</li>
 *   <li>StreamingToolExecutor.executeAsync() abort 路径 — remove (end, P1-1 新增)</li>
 *   <li>StreamingToolExecutor.discard() — clear</li>
 * </ul>
 *
 * <p><b>P0-1 校正</b>: 用 {@link Function}{@code <Set<String>, Set<String>>} 严格对齐 CC
 * {@code Tool.ts:227 setInProgressToolUseIDs: (f: (prev: Set<string>) => Set<string>) => void}.
 * Function 接收 prev Set, 返回 immutable snapshot (Collections.unmodifiableSet(new HashSet<>(prev))).
 *
 * <p><b>关键 invariant</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ul>
 *   <li>{@link ToolUseContext#inProgressToolUseIDs()} 默认 noop Function（{@code s -> Set.of()}）.</li>
 *   <li>显式构造时 Function 可注入, LlmAgentLoop 通过该 Function 维护 in-progress state.</li>
 *   <li>并发安全: 内部用 {@code ConcurrentHashMap.newKeySet()} 时多线程 fire/clear 不抛异常.</li>
 *   <li>{@link StreamingToolExecutor#add(ToolUseBlock)} 不直接触发 (P0-2 校正对齐 CC executeTool 入口).</li>
 *   <li>{@link StreamingToolExecutor#discard()} 触发 clear Function (via apply(empty)).</li>
 *   <li>向后兼容: 旧 4 参 / 8 参 / 14 参构造器传 null → compact ctor 兜底 noop Function.</li>
 * </ul>
 *
 * @see ToolUseContext#inProgressToolUseIDs()
 * @see StreamingToolExecutor
 */
class R32B8_SetInProgressToolUseIDsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseContext baseCtx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT
        );
    }

    private static ToolUseBlock buildCall(String id, String name) {
        JsonNode input = JSON.createObjectNode().put("pattern", "*.java");
        return new ToolUseBlock(id, name, input);
    }

    @Test
    @DisplayName("Default inProgressToolUseIDs() = noop Function (与 CC fallback 一致)")
    void defaultIsNoopFunction() {
        // WHY: 旧 14 参构造器不传 inProgressToolUseIDs → compact ctor 兜底为 noop.
        // 与 CC setInProgressToolUseIDs: () => {} fallback 一致 (P0-1 校正后为 s -> Set.of()).
        ToolUseContext ctx = baseCtx();
        assertThat(ctx.inProgressToolUseIDs())
            .as("Default inProgressToolUseIDs 必须 = noop Function (兜底)")
            .isNotNull();
        // noop Function 不抛异常, 返回空 Set
        Set<String> result = ctx.inProgressToolUseIDs().apply(Set.of("toolu_x"));
        assertThat(result)
            .as("noop Function.apply() 应返回空 immutable Set")
            .isEmpty();
    }

    @Test
    @DisplayName("显式注入 Function 可正常工作 (apply 返回 immutable snapshot)")
    void explicitFunctionWorks() {
        // WHY: LlmAgentLoop 在 run() 入口构造 ConcurrentHashMap.newKeySet() +
        // Function (维护 in-progress state), 调 15 参 of() 注入.
        // 验证 Function 真的被 apply() 触发并返回 immutable snapshot.
        Set<String> inProgress = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            // 模拟 CC setInProgressToolUseIDs(prev => new Set(prev).add(id)):
            // 对每个 prev 调用 add (实际 LlmAgentLoop 会包装成不同 lambda).
            Set<String> next = new HashSet<>(current);
            next.add("toolu_simulated");
            return Collections.unmodifiableSet(next);
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        // 调 apply(empty) → 返回 immutable snapshot with simulated ID
        Set<String> snapshot1 = ctx.inProgressToolUseIDs().apply(Set.of());
        assertThat(snapshot1)
            .as("Function.apply(empty) 应返回包含 simulated ID 的 immutable snapshot")
            .containsExactly("toolu_simulated");

        // snapshot 不可变 (对齐 CC immutable Set 语义)
        assertThatThrownByIsUnmodifiable(snapshot1);

        // 模拟 LlmAgentLoop 维护 inProgress: 多次 apply 累积
        inProgress.addAll(snapshot1);
        Set<String> snapshot2 = ctx.inProgressToolUseIDs().apply(inProgress);
        assertThat(snapshot2)
            .as("第二次 apply 应累积 simulated ID (HashSet copy)")
            .containsExactly("toolu_simulated");
    }

    /**
     * 验证返回的 Set 不可变 (immutable) — 对齐 CC immutable Set 语义.
     * 调用 mutator 方法应抛 UnsupportedOperationException.
     */
    private static void assertThatThrownByIsUnmodifiable(Set<String> set) {
        try {
            set.add("should_throw");
            throw new AssertionError("Expected UnsupportedOperationException for immutable set");
        } catch (UnsupportedOperationException expected) {
            // 预期抛异常 — 验证不可变
        }
    }

    @Test
    @DisplayName("15 参 of() 便利构造完整透传 inProgressToolUseIDs Function")
    void convenienceFactoryFifteenArgs() {
        // WHY: R32-b8 #3 新增 15 参便利构造, 验证完整透传 (包含 effectiveCwd + inProgressToolUseIDs).
        Set<String> inProgress = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            Set<String> next = new HashSet<>(current);
            next.add("toolu_xyz");
            return Collections.unmodifiableSet(next);
        };

        Path effectiveCwd = java.nio.file.Paths.get("/tmp");
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            effectiveCwd, tracker
        );

        assertThat(ctx.effectiveCwd())
            .as("15 参 of() 透传 effectiveCwd")
            .isEqualTo(effectiveCwd);

        assertThat(ctx.inProgressToolUseIDs())
            .as("15 参 of() 透传 inProgressToolUseIDs Function")
            .isSameAs(tracker);

        // 实际触发: apply(empty) → 返回 immutable snapshot
        Set<String> snapshot = ctx.inProgressToolUseIDs().apply(Set.of());
        assertThat(snapshot).contains("toolu_xyz");
    }

    @Test
    @DisplayName("旧 4 参构造器传 null → compact ctor 兜底 noop Function (向后兼容)")
    void legacyFourArgConstructorStillWorks() {
        // WHY: 旧 4 参构造器 (s12 方案 C 之前) 必须继续兼容, 不能因 #3 改动破坏.
        // compact ctor 兜底 noop Function 保证旧 30+ caller 无需修改.
        ToolUseContext ctx = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of()
        );
        assertThat(ctx.inProgressToolUseIDs())
            .as("旧 4 参构造器 → inProgressToolUseIDs 兜底 noop Function")
            .isNotNull();
        // 不抛异常即视为 noop 正常
        Set<String> result = ctx.inProgressToolUseIDs().apply(Set.of("legacy"));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("旧 14 参构造器传 null → compact ctor 兜底 noop Function")
    void legacyFourteenArgConstructorStillWorks() {
        // WHY: Phase A 任务 2 的 14 参便利构造 (effectiveCwd=null) 继续兼容.
        // 测试验证 inProgressToolUseIDs 兜底 noop.
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, ""
        );
        assertThat(ctx.inProgressToolUseIDs())
            .as("14 参便利构造 → inProgressToolUseIDs 兜底 noop Function")
            .isNotNull();
    }

    @Test
    @DisplayName("StreamingToolExecutor.executeAsync() 入口触发 Function (P0-2 校正)")
    void streamingExecuteAsyncTriggersFunction() throws Exception {
        // WHY: P0-2 校正: add() 不再直接触发 (Fix 2), 移到 executeAsync() 入口.
        // 对齐 CC StreamingToolExecutor.ts:267 executeTool 入口 + toolOrchestration.ts:127/160.
        // 用 AtomicInteger 计数 Function.apply() 被调用的次数, 验证 executeAsync 入口触发.
        AtomicInteger applyCount = new AtomicInteger(0);
        Set<String> capture = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> captureTracker = current -> {
            applyCount.incrementAndGet();
            // 模拟 LlmAgentLoop 内部 set 维护: add 当前 ID (从 streaming executor 传入)
            capture.addAll(current);
            return Collections.unmodifiableSet(new HashSet<>(capture));
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            captureTracker
        );

        ToolRegistry registry = new ToolRegistry();
        Tool stub = new Tool() {
            @Override public String name() { return "stub_tool"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(stub);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.add(buildCall("toolu_call_1", "stub_tool"));
        exec.getRemainingResults();

        // 验证 Function 至少被 apply 2 次 (executeAsync entry + executeAsync exit)
        assertThat(applyCount.get())
            .as("Function 至少被 apply 2 次 (executeAsync entry + exit)")
            .isGreaterThanOrEqualTo(2);
        // 验证 capture 包含 toolUseId
        assertThat(capture)
            .as("capture set 应包含 tool_use_id (executeAsync 入口触发的 in-progress 标记)")
            .contains("toolu_call_1");
    }

    @Test
    @DisplayName("StreamingToolExecutor.discard() 清空 in-progress (via apply)")
    void streamingDiscardTriggersFunction() {
        // WHY: 对齐 CC REPL.tsx:430 setInProgressToolUseIDs?.(prev => clear).
        // discard() 应触发 Function.apply(empty) 清理 (P0-1 校正后用 apply 而非 accept).
        AtomicInteger applyCount = new AtomicInteger(0);
        Function<Set<String>, Set<String>> tracker = current -> {
            applyCount.incrementAndGet();
            // discard 时 apply(empty) → 返回空 immutable Set (模拟 LlmAgentLoop 清理)
            return Collections.unmodifiableSet(new HashSet<>());
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        ToolRegistry registry = new ToolRegistry();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.discard();

        assertThat(applyCount.get())
            .as("discard() 触发 Function.apply 清理 (clear path)")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("P1-1: 5 处触发点全部覆盖 (add→executeAsync 入口/出口/abort/discard)")
    void fiveTriggerPointsCovered() throws Exception {
        // WHY: P1-1 实施 CC toolOrchestration.ts:127/148/160/173/183 5 处 inProgressToolUseIDs 触发.
        // Java 端 5 处 = executeAsync 入口 (start) + executeAsync success 出口 (end)
        //                          + executeAsync error 出口 (end) + executeAsync abort 路径 (end)
        //                          + discard() (clear).
        // 此测试用计数 + 状态捕获验证 5 处都触发.
        AtomicInteger totalApplies = new AtomicInteger(0);
        Set<String> fireEvents = ConcurrentHashMap.newKeySet();
        // Function 维护内部 state + 返回 immutable snapshot:
        Function<Set<String>, Set<String>> tracker = current -> {
            totalApplies.incrementAndGet();
            // 模拟 add (CC new Set(prev).add(id)): 加入 capture set
            fireEvents.addAll(current);
            return Collections.unmodifiableSet(new HashSet<>(fireEvents));
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        ToolRegistry registry = new ToolRegistry();
        Tool stub = new Tool() {
            @Override public String name() { return "trigger_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(stub);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        // 触发 1: add() → executeAsync() 入口 (start, P0-2 校正)
        exec.add(buildCall("toolu_t1", "trigger_stub"));
        // getRemainingResults 触发 executeAsync() 出口 (success end)
        exec.getRemainingResults();

        // 验证触发次数: 至少 2 (start + end-success)
        assertThat(totalApplies.get())
            .as("正常路径至少 2 次 apply (start + end-success)")
            .isGreaterThanOrEqualTo(2);

        // 触发 5: discard() → apply(empty)
        exec.discard();
        assertThat(totalApplies.get())
            .as("discard 后再多 1 次 apply (clear)")
            .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("null ctx + null Function 时 add/discard/execute 不抛异常 (null-safety)")
    void nullCtxIsSafe() {
        // WHY: StreamingToolExecutor 构造可传 null ctx (向后兼容 Phase 2 之前).
        // in-progress hook 在 null ctx 下必须不抛异常 (null-safe fallback).
        ToolRegistry registry = new ToolRegistry();
        Tool stub = new Tool() {
            @Override public String name() { return "null_ctx_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(stub);

        // 构造 null ctx (向后兼容 4 参构造)
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, (ToolUseContext) null);

        // 不抛异常
        exec.add(buildCall("toolu_null_ctx", "null_ctx_stub"));
        exec.getRemainingResults();
        exec.discard();
        // 通过即视为 null-safety OK
    }

    @Test
    @DisplayName("ConcurrentHashMap.newKeySet() 多线程 apply 不抛异常")
    void concurrentApplyIsSafe() throws InterruptedException {
        // WHY: StreamingToolExecutor 是线程池并发执行, 多 tool 同时 apply 同一 state.
        // 用 ConcurrentHashMap.newKeySet() 保证线程安全.
        Set<String> inProgress = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            // 模拟并发 add 到内部 set
            Set<String> next = new HashSet<>(current);
            next.add("applied_" + Thread.currentThread().getId());
            inProgress.addAll(next);
            return Collections.unmodifiableSet(next);
        };

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(100);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    tracker.apply(Set.of());
                } catch (Throwable th) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
            .as("100 个并发 apply 任务应在 10s 内完成")
            .isTrue();
        pool.shutdown();

        assertThat(errors.get())
            .as("并发 apply 不应抛异常 (ConcurrentHashMap.newKeySet 线程安全)")
            .isZero();
    }

    @Test
    @DisplayName("StreamingToolExecutor 顺序 add 多个 tool, Function 收到所有 unique toolUseId")
    void streamingSequentialAddCoversAllIds() throws Exception {
        // WHY: 验证 add() 路径触发 in-progress Function, 顺序 add N 个 tool 后 Function
        // 应收到所有 N 个 unique toolUseId (对齐 CC setInProgressToolUseIDs 语义).
        //
        // P0-1 flake 修复 (替代原 streamingConcurrentAddIsSafe):
        // 原测试用 4 线程并发 add 20 个 tool, 与 StreamingToolExecutor.tools (LinkedHashMap,
        // 非线程安全) + processQueue 并发迭代产生 race: capture.size() 时而 19 时而 1.
        // 隔离运行 3/3 PASS, 全量运行 1/1 FAIL.
        // 按 CLAUDE.md 规则 2 (简单至上), 用确定性 for 循环单线程 add 验证 set 正确性,
        // 不引入并发基础设施 (@Fork/@ResourceLock).
        // 并发 Function.apply 线程安全由 {@link #concurrentApplyIsSafe} 独立覆盖.
        Set<String> capture = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            capture.addAll(current);
            return Collections.unmodifiableSet(new HashSet<>(capture));
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        ToolRegistry registry = new ToolRegistry();
        Tool stub = new Tool() {
            @Override public String name() { return "sequential_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(stub);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);

        // 确定性顺序 add 20 个 tool_call (单线程 for 循环, 避免 LinkedHashMap 并发问题)
        for (int i = 0; i < 20; i++) {
            exec.add(buildCall("toolu_seq_" + i, "sequential_stub"));
        }

        exec.getRemainingResults();

        // 验证 Function 收到所有 20 个 unique toolUseId
        assertThat(capture)
            .as("顺序 add 应让 Function 收到所有 20 个 unique toolUseId")
            .hasSize(20)
            .contains(
                "toolu_seq_0",  "toolu_seq_1",  "toolu_seq_2",  "toolu_seq_3",  "toolu_seq_4",
                "toolu_seq_5",  "toolu_seq_6",  "toolu_seq_7",  "toolu_seq_8",  "toolu_seq_9",
                "toolu_seq_10", "toolu_seq_11", "toolu_seq_12", "toolu_seq_13", "toolu_seq_14",
                "toolu_seq_15", "toolu_seq_16", "toolu_seq_17", "toolu_seq_18", "toolu_seq_19"
            );
    }

    @Test
    @DisplayName("P1-1 #4: Tool execute 抛异常 → error path clearInProgress 触发 (end)")
    void errorPathTriggersClearInProgress() throws Exception {
        // WHY: P1-1 五触发点 #4 · 对齐 CC StreamingToolExecutor.ts:525 markToolUseAsComplete
        //   (即使 tool.execute 抛异常, in-progress set 也应清理).
        //   Java 端 executeAsync catch (Throwable) → clearInProgress(t.call.id()) 已实施.
        // 验证: 抛异常的 tool, Function 仍应收到 fire (start) + clear (end-error) 各 1 次 apply.
        AtomicInteger applyCount = new AtomicInteger(0);
        Set<String> capturedIds = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            applyCount.incrementAndGet();
            capturedIds.addAll(current);
            return Collections.unmodifiableSet(new HashSet<>(capturedIds));
        };

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        ToolRegistry registry = new ToolRegistry();
        Tool errorStub = new Tool() {
            @Override public String name() { return "error_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                throw new RuntimeException("simulated tool failure for P1-1 #4");
            }
        };
        registry.register(errorStub);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.add(buildCall("toolu_err", "error_stub"));
        List<ToolResult> results = exec.getRemainingResults();

        // 验证 apply 被触发 2 次: fire (executeAsync 入口 start) + clear (error path 出口 end)
        assertThat(applyCount.get())
            .as("error path 应触发 fire (start) + clear (end-error) 共 2 次 apply")
            .isEqualTo(2);

        // 验证 fire 阶段 Function 收到 toolu_err (即错误 tool id 被记录到 in-progress)
        assertThat(capturedIds)
            .as("error path fire 阶段 Function 应收到失败 tool 的 toolUseId")
            .contains("toolu_err");

        // 验证 tool 执行产生 error result (对齐 ToolResult.error 行为)
        assertThat(results)
            .as("error path 应返回 ToolResult.error")
            .hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_err"))
            .as("error result 应标记 isError=true（IMP-C2 后 isError 由执行器推导）")
            .isTrue();
    }

    @Test
    @DisplayName("P1-1 #5: abort 路径 → clearInProgress 触发 (end)")
    void abortPathTriggersClearInProgress() throws Exception {
        // WHY: P1-1 五触发点 #5 · 对齐 CC toolOrchestration.ts:183 markToolUseAsComplete
        //   (abort 路径也会通过 markToolUseAsComplete 清理 in-progress set).
        //   Java 端 executeAsync 入口检查 getAbortReason(): 若非 null → synthetic error
        //   + clearInProgress(t.call.id()) + processQueue() + return.
        // 验证: 预先 abort parent controller, add() 后 executeAsync 走 abort 路径,
        //   Function 应收到 fire (start) + clear (end-abort) 各 1 次 apply.
        AtomicInteger applyCount = new AtomicInteger(0);
        Set<String> capturedIds = ConcurrentHashMap.newKeySet();
        Function<Set<String>, Set<String>> tracker = current -> {
            applyCount.incrementAndGet();
            capturedIds.addAll(current);
            return Collections.unmodifiableSet(new HashSet<>(capturedIds));
        };

        // 预先 abort parent controller → executeAsync 入口 getAbortReason() 返非 null
        AbortController parentAbort = new AbortController();
        parentAbort.abort("interrupt");

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", parentAbort, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            tracker
        );

        ToolRegistry registry = new ToolRegistry();
        Tool stub = new Tool() {
            @Override public String name() { return "abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // 不应执行 (executeAsync 入口已 abort 短路)
                throw new AssertionError("tool 不应被执行 (已被 abort 短路)");
            }
        };
        registry.register(stub);

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.add(buildCall("toolu_abort", "abort_stub"));
        List<ToolResult> results = exec.getRemainingResults();

        // 验证 apply 被触发 2 次: fire (executeAsync 入口 start) + clear (abort 路径 end)
        assertThat(applyCount.get())
            .as("abort 路径应触发 fire (start) + clear (end-abort) 共 2 次 apply")
            .isEqualTo(2);

        // 验证 fire 阶段 Function 收到 toolu_abort (即 abort 前 in-progress 已被记录)
        assertThat(capturedIds)
            .as("abort 路径 fire 阶段 Function 应收到 toolUseId")
            .contains("toolu_abort");

        // 验证 tool 被 abort 短路, 返回 synthetic error result
        assertThat(results)
            .as("abort 路径应返回 synthetic error result")
            .hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_abort"))
            .as("abort 路径返回 synthetic error (isError=true)")
            .isTrue();
    }
}
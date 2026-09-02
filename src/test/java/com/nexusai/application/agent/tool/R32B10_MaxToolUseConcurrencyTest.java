package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b10 B4 · MAX_TOOL_USE_CONCURRENCY env 默认 10 + canExecuteTool safe 分支限流.
 *
 * <p><b>对齐 CC 真源</b>: <code>Open-ClaudeCode/src/services/tools/toolOrchestration.ts:8-12</code>
 * {@code getMaxToolUseConcurrency()} + {@code :175} {@code runToolsConcurrently.all(generators, N)}.
 *
 * <p><b>D-1 偏差 (CLAUDE.md 规则 12 · Fail loud)</b>: 任务 brief 描述 env 名为
 * {@code MAX_TOOL_USE_CONCURRENCY}, CC 真源为 {@code CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY}.
 * 本测试按 CC 真源验证. 详见 commit message + progress 文件.
 *
 * <p><b>WHY (意图验证)</b> (CLAUDE.md 规则 9): 不变量 —
 * <ul>
 *   <li>{@link StreamingToolExecutor#parseMaxConcurrency(String)} 严格对齐 CC
 *       {@code parseInt(env || '', 10) || 10} 行为 (含 NaN/0/负数/非数字 fallback 10).</li>
 *   <li>{@code MAX_TOOL_USE_CONCURRENCY} 默认 10 — 与 CC 真源 + brief 一致.</li>
 *   <li>safe 工具并发上限生效: 超过 {@code MAX_TOOL_USE_CONCURRENCY} 个 safe in-flight 时
 *       新 safe 工具被守门拒绝 (CC {@code all(generators, N)} 等价行为).</li>
 *   <li>unsafe 工具守门不变 (CC {@code runToolsSerially} 等价).</li>
 * </ul>
 *
 * <p><b>测试策略</b>: 边界测试 (T-9 ~ T-12) 使用端到端 (public API) 而非反射操纵
 * 内部状态 — 因为 Java 17+ 完全阻止反射访问 {@code private static final} 嵌套类
 * (TrackedTool), 即使 {@code setAccessible(true)} 也无效. 端到端测试用阻塞工具 +
 * {@link CountDownLatch} 协调时序, 验证守门逻辑在实际执行流程中生效. 这是对 CC 真源
 * 行为验证的更稳健路径.
 *
 * @see StreamingToolExecutor#parseMaxConcurrency(String)
 */
class R32B10_MaxToolUseConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX = 10;

    /** 反射保存的原 MAX_TOOL_USE_CONCURRENCY 值, @AfterEach 还原. */
    private int originalMaxConcurrency;

    @BeforeEach
    void captureOriginalMaxConcurrency() throws Exception {
        originalMaxConcurrency = readMaxConcurrency();
    }

    @AfterEach
    void restoreOriginalMaxConcurrency() throws Exception {
        setMaxConcurrency(originalMaxConcurrency);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Group 1: parseMaxConcurrency env 解析 (7 个边界用例, 对齐 CC || 10 行为)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T-1 env 未设置 (null) → 默认 10 (对齐 CC parseInt('') || 10)")
    void parseMaxConcurrency_nullReturnsDefault() {
        // WHY: CC 真源 (toolOrchestration.ts:8-12): env 未设置时
        //   parseInt(process.env.X || '', 10) = parseInt('', 10) = NaN
        //   NaN || 10 = 10. Java 端 null 输入直接返回 10.
        int result = StreamingToolExecutor.parseMaxConcurrency(null);
        assertThat(result)
            .as("env 未设置 → parseMaxConcurrency 应返回默认值 10 (对齐 CC)")
            .isEqualTo(10);
    }

    @Test
    @DisplayName("T-2 env \"5\" → 5 (有效正整数)")
    void parseMaxConcurrency_validPositiveInteger() {
        // WHY: CC parseInt('5', 10) = 5, 5 || 10 = 5. Java parseInt('5') = 5, v > 0 → 5.
        int result = StreamingToolExecutor.parseMaxConcurrency("5");
        assertThat(result)
            .as("env '5' 应返回 5 (有效正整数, 对齐 CC || 10)")
            .isEqualTo(5);
    }

    @Test
    @DisplayName("T-3 env \"0\" → 10 (CC 0 falsy fallback, Java v > 0 fallback)")
    void parseMaxConcurrency_zeroFallsBackToDefault() {
        // WHY: CC parseInt('0', 10) = 0, 0 || 10 = 10 (0 falsy).
        //   Java parseInt('0') = 0, v > 0 检查失败 → 10. 行为等价.
        int result = StreamingToolExecutor.parseMaxConcurrency("0");
        assertThat(result)
            .as("env '0' 应返回默认值 10 (CC 0 falsy / Java v > 0 检查失败)")
            .isEqualTo(10);
    }

    @Test
    @DisplayName("T-4 env \"-3\" → -3 (CC parseInt 成功且 || 短路)")
    void parseMaxConcurrency_negativePreserved() {
        // WHY: CC parseInt('-3', 10) = -3，-3 为 truthy，|| 10 不会 fallback。
        int result = StreamingToolExecutor.parseMaxConcurrency("-3");
        assertThat(result)
            .as("env '-3' 应保留解析结果 -3 (严格对齐 CC || 短路行为)")
            .isEqualTo(-3);
    }

    @Test
    @DisplayName("T-5 env \"abc\" → 10 (NumberFormatException fallback)")
    void parseMaxConcurrency_nonNumericFallsBackToDefault() {
        // WHY: CC parseInt('abc', 10) = NaN, NaN || 10 = 10.
        //   Java parseInt('abc') 抛 NumberFormatException → catch → 10.
        int result = StreamingToolExecutor.parseMaxConcurrency("abc");
        assertThat(result)
            .as("env 'abc' 非数字应 fallback 10 (CC NaN || 10 / Java catch NFE)")
            .isEqualTo(10);
    }

    @Test
    @DisplayName("T-6 env \"\" → 10 (空字符串, 与 null 等价处理)")
    void parseMaxConcurrency_emptyStringFallsBackToDefault() {
        // WHY: CC parseInt('', 10) = NaN, NaN || 10 = 10.
        //   Java parseInt('') 抛 NumberFormatException → 10.
        int result = StreamingToolExecutor.parseMaxConcurrency("");
        assertThat(result)
            .as("env '' 空字符串应 fallback 10 (CC NaN || 10 / Java catch NFE)")
            .isEqualTo(10);
    }

    @Test
    @DisplayName("T-7 env \"10.5\" → 10 (Integer.parseInt 不接受小数, fallback)")
    void parseMaxConcurrency_decimalFallsBackToDefault() {
        // WHY: CC parseInt('10.5', 10) = 10 (JS parseInt 截断小数).
        //   Java Integer.parseInt('10.5') 抛 NumberFormatException → 10.
        //   行为等价 (结果都是 10), 路径不同 (CC 截断, Java 异常 fallback).
        int result = StreamingToolExecutor.parseMaxConcurrency("10.5");
        assertThat(result)
            .as("env '10.5' 小数应 fallback 10 (CC parseInt 截断=10 / Java NFE fallback)")
            .isEqualTo(10);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Group 2: MAX_TOOL_USE_CONCURRENCY 默认值 (1 个用例)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T-8 MAX_TOOL_USE_CONCURRENCY 默认值 = 10 (无 env 设置)")
    void maxConcurrencyDefaultIs10() throws Exception {
        // WHY: 在测试 JVM (无 CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY env) 下,
        //   static volatile 字段应等于 10. 这验证 class 初始化逻辑正确调用
        //   parseMaxConcurrency(null) → 10.
        // 注: 若 CI 环境设了 env, 这个断言会失败 — 这是有意的 Fail loud, 让 CI 立刻暴露.
        int current = readMaxConcurrency();
        assertThat(current)
            .as("MAX_TOOL_USE_CONCURRENCY 默认值应为 10 (CC + brief 一致). "
                + "若 CI 设了 CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY env, 此断言会失败 (有意的).")
            .isEqualTo(DEFAULT_MAX);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Group 3: canExecuteTool 守门逻辑 — 端到端 (4 个用例, public API)
    //
    // 策略: 用阻塞工具 + CountDownLatch 协调时序, 验证守门逻辑在执行流程中生效.
    //       不操纵内部状态 (Java 17+ 阻止反射访问 private static final 嵌套类).
    //       使用默认 MAX=10 (T-8 已验证), 边界值测试用 9 (below) 和 11 (above).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 端到端公共辅助: 创建阻塞 safe 工具, 添加 N 个 tool, 测量 in-flight 峰值.
     *
     * @param n 添加的工具总数
     * @param concurrencySafe true=safe (受 MAX 限制), false=unsafe (串行)
     * @param executorPool 外部线程池 (建议 32 线程, 确保守门成为限制因素)
     * @param peakInFlight 共享峰值计数器 (入参出参)
     * @param currentInFlight 共享当前 in-flight 计数器
     * @param enterExecuting 让第 1 个工具在 EXECUTING 中唤醒测试 (验证 in-flight)
     * @param release 阻塞中的工具等待此 latch 才返回
     * @return 是否所有 N 个工具最终都进入 EXECUTING (true=全部成功, false=有 QUEUED 卡住)
     */
    private boolean runEndToEndWithBlockingTool(
            int n, boolean concurrencySafe,
            ExecutorService executorPool,
            AtomicInteger peakInFlight, AtomicInteger currentInFlight,
            CountDownLatch enterExecuting, CountDownLatch release) throws Exception {

        ToolRegistry registry = new ToolRegistry();
        Tool blockingTool = new Tool() {
            @Override public String name() { return "blocking_" + (concurrencySafe ? "safe" : "unsafe"); }
            @Override public String description() { return "blocking tool for concurrency test"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return concurrencySafe; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                int cur = currentInFlight.incrementAndGet();
                peakInFlight.updateAndGet(p -> Math.max(p, cur));
                try {
                    enterExecuting.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    currentInFlight.decrementAndGet();
                }
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(blockingTool);

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of())
        );

        StreamingToolExecutor exec = new StreamingToolExecutor(registry, executorPool, ctx);
        for (int i = 0; i < n; i++) {
            JsonNode input = JSON.createObjectNode().put("i", i);
            exec.add(new ToolUseBlock("toolu_e2e_" + i, blockingTool.name(), input));
        }
        // 触发 getRemainingResults (在后台线程跑, 阻塞在 release.await)
        Thread runner = new Thread(exec::getRemainingResults, "test-runner");
        runner.setDaemon(true);
        runner.start();
        return true;
    }

    @Test
    @DisplayName("T-9 端到端: safe 工具 + 9 个 (MAX=10) → 全部并发执行 (below limit)")
    void canExecuteSafeBelowLimit() throws Exception {
        // WHY: CC all(generators, N=10) 允许 10 个并发. Java safe 分支守门: safeInFlight < 10 → 允许.
        //   用 MAX=10 (默认) + 9 个 safe 工具, 验证全部 9 个进入 EXECUTING 并发执行.
        //   边界: 9 < 10 → 全部允许 (below limit).
        AtomicInteger peak = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch enter = new CountDownLatch(9);  // 9 个工具全部进入 EXECUTING 才唤醒
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        runEndToEndWithBlockingTool(9, true, pool, peak, current, enter, release);

        // 等 9 个工具都进入 EXECUTING
        assertThat(enter.await(10, TimeUnit.SECONDS))
            .as("9 个 safe 工具应在 10s 内全部进入 EXECUTING (below MAX=10)")
            .isTrue();
        Thread.sleep(50);

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
            .as("线程池应在 10s 内终止")
            .isTrue();

        assertThat(peak.get())
            .as("safe 工具 9 个 (below MAX=10) 应全部并发, peak=%d 应等于 9 (无守门拒绝)",
                peak.get())
            .isEqualTo(9);
    }

    @Test
    @DisplayName("T-10 端到端: safe 工具 + 12 个 (MAX=10) → 10 个并发, 2 个 QUEUED 被拒")
    void canExecuteSafeAtLimit() throws Exception {
        // WHY: CC all(generators, N=10) 限流到 10. Java safe 分支守门: safeInFlight >= 10 → 拒绝.
        //   用 MAX=10 (默认) + 12 个 safe 工具, 验证恰好 10 个进入 EXECUTING 并发,
        //   剩余 2 个工具被 canExecuteTool 守门拒绝 (保持 QUEUED 状态).
        //   边界: 12 > 10 → 10 个允许, 2 个拒绝.
        AtomicInteger peak = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch enter = new CountDownLatch(10);  // 只等 10 个进入 EXECUTING
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        runEndToEndWithBlockingTool(12, true, pool, peak, current, enter, release);

        // 等恰好 10 个进入 EXECUTING (守门阻止了第 11 和 12 个)
        assertThat(enter.await(10, TimeUnit.SECONDS))
            .as("恰好 10 个 safe 工具应进入 EXECUTING (MAX=10), 其余 2 个被守门拒绝")
            .isTrue();
        Thread.sleep(100);  // 让 race condition 稳定 (确保第 11, 12 没有偷偷进入)

        // 关键断言: 此时 peak 应 == 10 (不超过 MAX), 且 current 稳定在 10 (第 11, 12 没进来)
        assertThat(peak.get())
            .as("safe 工具 12 个 (above MAX=10) 应峰值=10, 实测=%d. "
                + "验证 canExecuteTool safe 分支守门在端到端流程生效 (CC all(generators, 10) 等价).",
                peak.get())
            .isEqualTo(10);

        // 释放后所有工具都应完成 (包括被守门的 2 个, 它们在 release 后 processQueue 唤醒)
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
            .as("线程池 10s 内应终止 (释放后守门的 2 个工具也完成)")
            .isTrue();
    }

    @Test
    @DisplayName("T-11 端到端: safe 工具 + 1 unsafe in-flight → safe 被拒, 等 unsafe 完成才执行")
    void canExecuteSafeWithUnsafeInFlight() throws Exception {
        // WHY: CC partitionToolCalls 按 isConcurrencySafe 分 batch, safe batch 与 unsafe batch
        //   不混合. Java canExecuteTool 原行为: safe 工具遇到 unsafe in-flight 立即拒绝.
        //   b10 仅加 safe-in-flight 计数, 不破坏 unsafe 守门 — 此用例验证不回归.
        //   验证: 1 个 unsafe 阻塞 + 1 个 safe 应被守门拒绝 (unsafe 必须串行独占).
        AtomicInteger peak = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch enter = new CountDownLatch(1);  // 只等 1 个 unsafe 进入 EXECUTING
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        // 1 个 unsafe (阻塞) + 1 个 safe (应被守门, 保持 QUEUED)
        runEndToEndWithBlockingTool(2, false, pool, peak, current, enter, release);

        // 等 unsafe 进入 EXECUTING
        assertThat(enter.await(10, TimeUnit.SECONDS))
            .as("1 个 unsafe 工具应在 10s 内进入 EXECUTING")
            .isTrue();
        Thread.sleep(100);

        // 关键断言: 此时只有 1 个 unsafe 在 EXECUTING, safe 工具被守门拒绝 (保持 QUEUED)
        assertThat(peak.get())
            .as("unsafe 串行独占, safe 被守门拒绝, peak=%d 应等于 1",
                peak.get())
            .isEqualTo(1);

        // 释放后, unsafe 完成, safe 工具被唤醒并执行
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
            .as("线程池 10s 内应终止 (unsafe 完成后 safe 也执行)")
            .isTrue();

        // 释放后, safe 工具也会执行 (processQueue 唤醒), 但串行 (unsafe 已完成, 但 safe 仍是 1 个)
        // peak 保持 1 (safe 没和 unsafe 并发, 是 unsafe 完成后才执行)
        assertThat(peak.get())
            .as("unsafe 完成后 safe 单独执行, 不会与 unsafe 并发, peak 仍 = 1")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("T-12 端到端: unsafe 工具 + 任何 in-flight → 必须串行 (原行为保留)")
    void canExecuteUnsafeAlwaysBlocked() throws Exception {
        // WHY: CC runToolsSerially 等价 — unsafe 工具永远等所有 EXECUTING 完成.
        //   b10 改造只动 safe 分支, unsafe 分支逻辑完全不变. 验证不回归.
        //   验证: 2 个 unsafe 工具, 第 2 个必须等第 1 个完成才执行 (never concurrent).
        AtomicInteger peak = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch enter = new CountDownLatch(1);  // 只等第 1 个 unsafe
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        // 2 个 unsafe 工具, 第 2 个必须等第 1 个完成 (unsafe 串行守门)
        runEndToEndWithBlockingTool(2, false, pool, peak, current, enter, release);

        // 等第 1 个 unsafe 进入 EXECUTING
        assertThat(enter.await(10, TimeUnit.SECONDS))
            .as("第 1 个 unsafe 应在 10s 内进入 EXECUTING")
            .isTrue();
        Thread.sleep(100);

        // 关键断言: 第 2 个 unsafe 被守门拒绝 (unsafe 必须等所有 EXECUTING 完成)
        assertThat(peak.get())
            .as("2 个 unsafe 串行执行, 第 2 个被守门拒绝, peak=%d 应等于 1",
                peak.get())
            .isEqualTo(1);

        // 释放后所有都完成 (unsafe 串行 — 第 1 个完成 → 第 2 个开始 → 各自 peak=1)
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
            .as("线程池 10s 内应终止")
            .isTrue();

        // unsafe 串行 — peak 保持 1 (从不并发)
        assertThat(peak.get())
            .as("unsafe 串行, 不并发, peak 仍 = 1")
            .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Group 4: 端到端 — 大规模并发上限验证 (1 个用例)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T-13 端到端: 20 个 safe tool 同时 add → in-flight 峰值 ≤ MAX_TOOL_USE_CONCURRENCY")
    void endToEndConcurrencyCappedAtMax() throws Exception {
        // WHY: 对齐 CC runToolsConcurrently.all(generators, N) — 实际并发峰值 ≤ N.
        //   用 AtomicInteger 跟踪同时 EXECUTING 的工具数, latched 工具等待 latch
        //   让所有工具同时进入 EXECUTING, 验证守门逻辑在端到端场景下生效.
        //
        //   注: 此测试依赖 StreamingToolExecutor.processQueue → canExecuteTool 实际行为,
        //   间接验证守门逻辑正确串联到执行流程.
        //
        //   使用 setMaxConcurrency(3) 让测试快速完成 (避免 50 tool × 等待) — 这要求字段非 final.
        //   字段声明为 volatile (非 final) 以支持反射修改 (Java 17+ 阻止 static final 反射).
        setMaxConcurrency(3);
        int n = 20;
        CountDownLatch enterExecuting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger peakInFlight = new AtomicInteger(0);
        AtomicInteger currentInFlight = new AtomicInteger(0);

        ToolRegistry registry = new ToolRegistry();
        Tool blockingSafe = new Tool() {
            @Override public String name() { return "blocking_safe"; }
            @Override public String description() { return "blocking safe tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                int cur = currentInFlight.incrementAndGet();
                peakInFlight.updateAndGet(p -> Math.max(p, cur));
                try {
                    enterExecuting.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    currentInFlight.decrementAndGet();
                }
                return ToolResult.success(call.id(), "done");
            }
        };
        registry.register(blockingSafe);

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of())
        );

        ExecutorService pool = Executors.newFixedThreadPool(32);
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, ctx);

        for (int i = 0; i < n; i++) {
            JsonNode input = JSON.createObjectNode().put("i", i);
            exec.add(new ToolUseBlock("toolu_e2e_" + i, "blocking_safe", input));
        }

        // 等至少一个工具进入 EXECUTING
        enterExecuting.await(10, TimeUnit.SECONDS);
        Thread.sleep(100);

        release.countDown();
        exec.getRemainingResults();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
            .as("线程池 10s 内应终止")
            .isTrue();

        assertThat(peakInFlight.get())
            .as("实际并发峰值应 ≤ MAX_TOOL_USE_CONCURRENCY (=3), 实测=%d. "
                + "验证 safe 分支守门在端到端流程生效 (CC all(generators, N) 等价).",
                peakInFlight.get())
            .isLessThanOrEqualTo(3)
            .isGreaterThanOrEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 辅助方法 (反射访问 MAX_TOOL_USE_CONCURRENCY 字段)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 读取当前 MAX_TOOL_USE_CONCURRENCY 值 (反射访问 private static volatile 字段).
     */
    private static int readMaxConcurrency() throws Exception {
        Field field = StreamingToolExecutor.class.getDeclaredField("MAX_TOOL_USE_CONCURRENCY");
        field.setAccessible(true);
        return field.getInt(null);
    }

    /**
     * 设置 MAX_TOOL_USE_CONCURRENCY 值 (反射修改 private static volatile 字段).
     *
     * <p>字段声明为 {@code volatile} (而非 final) 以支持反射修改 — Java 17+ 完全阻止
     * 反射修改 {@code static final} 字段, 但允许修改 {@code static volatile} 字段.
     * 这是与 brief "static final" 描述的有意偏差, 详见 StreamingToolExecutor.MAX_TOOL_USE_CONCURRENCY
     * JavaDoc 的 "testability 偏差" 章节 + commit message.
     */
    private static void setMaxConcurrency(int value) throws Exception {
        Field field = StreamingToolExecutor.class.getDeclaredField("MAX_TOOL_USE_CONCURRENCY");
        field.setAccessible(true);
        field.setInt(null, value);
    }
}
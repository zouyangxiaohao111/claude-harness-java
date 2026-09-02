package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.AgentRunner;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.JournalEntry;
import com.nexusai.application.agent.workflow.JournalStore;
import com.nexusai.application.agent.workflow.PermissionGate;
import com.nexusai.application.agent.workflow.ProgressEmitter;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.RunStatus;
import com.nexusai.application.agent.workflow.TaskRegistrar;
import com.nexusai.application.agent.workflow.WorkflowLogger;
import com.nexusai.application.agent.workflow.WorkflowPorts;
import com.nexusai.application.agent.workflow.WorkflowRunResult;
import com.nexusai.application.agent.workflow.WorkflowJournal;
import com.nexusai.application.agent.workflow.agent.AgentAdapter;
import com.nexusai.application.agent.workflow.agent.AgentAdapterCapabilities;
import com.nexusai.application.agent.workflow.agent.AgentAdapterContext;
import com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry;
import com.nexusai.application.agent.workflow.script.WorkflowScriptExecutor;
import com.nexusai.application.agent.workflow.script.WorkflowScriptParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowRunEngine run 状态机测试 · 对齐 CC runWorkflow.ts:31-139 + hooks.ts:59-263 + P0-core-doc §8。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>journal 是编排可信核心</b>（A 域）— resume 命中复用缓存（adapter.run 次数=0）、
 *       schema 复验失效 → invalidate + 重跑、key 分叉 → 截断、scriptChanged → truncate 全量重跑。
 *       若 journal 命中/重放错位，恢复后同一 workflow 会重复执行 agent（双发）或返回过期结果。</li>
 *   <li><b>信号量防 overspend</b>（B 域）— 预算检查必须在信号量临界区内，否则 N 个在 spent=0 时
 *       入队的 waiter 唤醒后全过检查、同时超支。</li>
 *   <li><b>abort 不伪装完成</b>（C 域）— WorkflowAbortedError → killed（非 failed）；parse fail →
 *       run_done failed 直接返回不抛（DETACHED 不炸）；脚本结束补发 terminal phase_done 防 UI 卡转圈。</li>
 * </ol>
 */
class WorkflowRunEngineTest {

    // ─────────────────────── 测试桩 ───────────────────────

    /** 内存 ports 桩。 */
    static final class FakePorts implements WorkflowPorts {
        final FakeJournalStore journal = new FakeJournalStore();
        final FakeTaskRegistrar registrar = new FakeTaskRegistrar();
        final BufferingEmitter emitter = new BufferingEmitter();
        final AgentAdapterRegistry registry = new AgentAdapterRegistry();
        final List<AgentRunResult> adapterResults = new ArrayList<>();
        final AtomicInteger adapterCallCount = new AtomicInteger();

        @Override
        public AgentRunner agentRunner() {
            return null; // 引擎走 registry（fail-fast），不回落到 agentRunner
        }

        @Override
        public com.nexusai.application.agent.workflow.AgentAdapterRegistry agentAdapterRegistry() {
            return registry;
        }

        @Override
        public ProgressEmitter progressEmitter() {
            return emitter;
        }

        @Override
        public TaskRegistrar taskRegistrar() {
            return registrar;
        }

        @Override
        public JournalStore journalStore() {
            return journal;
        }

        @Override
        public PermissionGate permissionGate() {
            return host -> false;
        }

        @Override
        public WorkflowLogger logger() {
            return new WorkflowLogger() {
                @Override
                public void debug(String message) {
                }

                @Override
                public void warn(String message, Object... args) {
                }

                @Override
                public void event(String name, Map<String, Object> metadata) {
                }
            };
        }
    }

    /** 内存 JournalStore（append 收集，read 原样返回）。 */
    static final class FakeJournalStore implements JournalStore {
        final Map<String, List<JournalEntry>> store = new HashMap<>();

        @Override
        public List<JournalEntry> read(String runId) {
            return new ArrayList<>(store.getOrDefault(runId, List.of()));
        }

        @Override
        public void append(String runId, JournalEntry entry) {
            store.computeIfAbsent(runId, k -> new ArrayList<>()).add(entry);
        }

        @Override
        public void truncate(String runId) {
            store.remove(runId);
        }
    }

    /** 内存 TaskRegistrar（P0 恒 null pendingAction）。 */
    static final class FakeTaskRegistrar implements TaskRegistrar {
        @Override
        public RegisterResult register(RegisterOpts opts, HostHandle host) {
            return null;
        }

        @Override
        public void complete(String runId, String summary) {
        }

        @Override
        public void fail(String runId, String error) {
        }

        @Override
        public void kill(String runId) {
        }

        @Override
        public boolean killAgent(String runId, int agentId) {
            return false;
        }

        @Override
        public PendingAction pendingAction(String runId) {
            return null;
        }

        @Override
        public void registerAgentAbort(String runId, int agentId, AbortController abortController) {
        }

        @Override
        public void unregisterAgentAbort(String runId, int agentId) {
        }
    }

    /** 缓冲事件发射器（断言事件序）。 */
    static final class BufferingEmitter implements ProgressEmitter {
        final List<ProgressEvent> events = new ArrayList<>();

        @Override
        public void emit(ProgressEvent event) {
            events.add(event);
        }
    }

    // ─────────────────────── 工具方法 ───────────────────────

    /** 便捷构造 ok（AgentRunResult 无静态工厂，顶层 record 直构）。 */
    private static AgentRunResult ok(String output, int tokens) {
        return new AgentRunResultOk(output, tokens, null, null, null);
    }

    /** buildParams(prompt, opts) 等价的 AgentRunParams（用于预置 journal key）。 */
    private AgentRunParams params(String prompt) {
        return new AgentRunParams(prompt, null, null, null, null, null, null, null, null);
    }

    /** 用 fake executor 跑一次 run（脚本源/预算可自定义；cwd 固定 "cwd"）。 */
    private CompletableFuture<WorkflowRunResult> run(FakePorts ports, WorkflowScriptExecutor executor,
                                                     String script, String runId, Integer budgetTotal,
                                                     boolean resume, boolean scriptChanged) {
        return run(ports, executor, script, runId, budgetTotal, resume, scriptChanged, "cwd");
    }

    /** 用 fake executor 跑一次 run（cwd 可自定义，供子 workflow 命名脚本解析测试用）。 */
    private CompletableFuture<WorkflowRunResult> run(FakePorts ports, WorkflowScriptExecutor executor,
                                                     String script, String runId, Integer budgetTotal,
                                                     boolean resume, boolean scriptChanged, String cwd) {
        RunWorkflowOptions opts = new RunWorkflowOptions(
                script, null, runId, "wf", ports, HostHandle.create("host"),
                new AbortController(), cwd, budgetTotal, null, resume, scriptChanged);
        WorkflowRunEngine engine = new WorkflowRunEngine(new WorkflowScriptParser(), executor);
        return engine.run(opts);
    }

    /** 在 registry 注册一个假 adapter，其每次 run 依次返回 canned 结果。 */
    private void registerAdapter(FakePorts ports, List<AgentRunResult> results) {
        ports.adapterResults.addAll(results);
        ports.registry.register(new AgentAdapter() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public AgentAdapterCapabilities capabilities() {
                return AgentAdapterCapabilities.full();
            }

            @Override
            public CompletableFuture<AgentRunResult> run(AgentRunParams params, AgentAdapterContext ctx) {
                int idx = Math.min(ports.adapterCallCount.getAndIncrement(), results.size() - 1);
                return CompletableFuture.completedFuture(results.get(idx));
            }
        }).defaultAdapter("fake");
    }

    // ─────────────────────── C 域：run 状态机 ───────────────────────

    /** C.1 parse fail → run_done failed 直接返回不抛（DETACHED 不炸，runWorkflow.ts:36-48）。 */
    @Test
    @DisplayName("C.1 parse fail：run_done failed 直接返回，不抛异常，且不 emit run_started")
    void parseFailureEmitsRunDoneFailed() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("x", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> CompletableFuture.completedFuture("unreachable"),
                        "import x from 'y'",   // 静态 import → ScriptError
                        "run-1", null, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("import is not supported");
        // run_done failed 已 emit；未 emit run_started（解析在 run_started 之前）
        assertThat(ports.emitter.events).extracting(ProgressEvent::getClass)
                .contains(ProgressEvent.RunDone.class);
        assertThat(ports.emitter.events).extracting(ProgressEvent::getClass)
                .doesNotContain(ProgressEvent.RunStarted.class);
        ProgressEvent.RunDone done = (ProgressEvent.RunDone) ports.emitter.events
                .stream().filter(ProgressEvent.RunDone.class::isInstance).findFirst().orElseThrow();
        assertThat(done.status()).isEqualTo(ProgressEvent.RunStatus.FAILED);
    }

    /** C.2 正常 → completed + terminal phase_done 补发 + 事件序。 */
    @Test
    @DisplayName("C.2 正常路径：completed + terminal phase_done 补发 + 事件序")
    void completedWithEventOrderAndTerminalPhaseDone() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("ok-output", 3)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> {
                            hooks.phase("p1");
                            Object r = hooks.agent("do", Map.of()).join();
                            return CompletableFuture.completedFuture(r);
                        },
                        "agent('do')", "run-1", null, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.returnValue()).isEqualTo("ok-output");
        // 事件序（run_started → phase_started → agent_started → agent_done → phase_done → run_done）
        assertThat(ports.emitter.events).extracting(ProgressEvent::getClass)
                .containsExactly(
                        ProgressEvent.RunStarted.class,
                        ProgressEvent.PhaseStarted.class,
                        ProgressEvent.AgentStarted.class,
                        ProgressEvent.AgentDone.class,
                        ProgressEvent.PhaseDone.class,
                        ProgressEvent.RunDone.class);
    }

    /** C.3 abort/kill：脚本内抛 WorkflowAbortedError → killed（不是 failed）。 */
    @Test
    @DisplayName("C.3 kill：WorkflowAbortedError → run_done killed")
    void abortMapsToKilled() {
        FakePorts ports = new FakePorts();

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> CompletableFuture.failedFuture(
                                new com.nexusai.application.agent.workflow.WorkflowAbortedError()),
                        "agent('do')", "run-1", null, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.KILLED);
        ProgressEvent.RunDone done = (ProgressEvent.RunDone) ports.emitter.events
                .stream().filter(ProgressEvent.RunDone.class::isInstance).findFirst().orElseThrow();
        assertThat(done.status()).isEqualTo(ProgressEvent.RunStatus.KILLED);
    }

    /** C.4 普通 throw → failed（runWorkflow.ts:129）。 */
    @Test
    @DisplayName("C.4 普通 throw：run_done failed + error 携带消息")
    void throwMapsToFailed() {
        FakePorts ports = new FakePorts();

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> CompletableFuture.failedFuture(new RuntimeException("boom")),
                        "agent('do')", "run-1", null, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("boom");
    }

    // ─────────────────────── A 域：journal resume ───────────────────────

    /** A.1 命中复用：key 一致 → 不调 adapter，直接返回缓存 result（adapter.run 次数=0）。 */
    @Test
    @DisplayName("A.1 journal 命中：adapter.run 调用次数=0，直接复用缓存（hooks.ts:87-109）")
    void journalHitReusesCachedResultWithoutAdapterCall() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("live-output", 1)));

        // 预置 journal：与 agent("do") 相同 key 的缓存 ok 结果
        String key = WorkflowJournal.agentCallKey("do", params("do"));
        ports.journal.append("run-1", new JournalEntry(key, 0, ok("cached-output", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> hooks.agent("do", Map.of()),
                        "agent('do')", "run-1", null, true, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        // 返回缓存输出，而非 live 输出
        assertThat(result.returnValue()).isEqualTo("cached-output");
        assertThat(ports.adapterCallCount.get()).isZero();
        // agent_done 事件携带缓存 result
        ProgressEvent.AgentDone done = ports.emitter.events.stream()
                .filter(ProgressEvent.AgentDone.class::isInstance)
                .map(ProgressEvent.AgentDone.class::cast)
                .findFirst().orElseThrow();
        assertThat(done.result()).isEqualTo(new AgentRunResultOk("cached-output", 0, null, null, null));
    }

    /**
     * A.2 schema 复验失效：缓存 ok 结果不满足新 schema → invalidateJournal + 重跑（hooks.ts:94-98）。
     *
     * <p>journal key 包含 schema（canonicalParams 序列化 schema 字段），故预置 key 必须用
     * <b>含 schema</b> 的 params 计算，否则落入 A.3 分叉而非本分支。schema 用 {@code {type:"object"}}
     * （引擎最小校验器可判别）：缓存 String 不符 → 复验 dead → invalidate + 重跑；
     * live String 也不符 type=object → validateStructuredResult dead → retry-once 二次 → dead。
     * 断言核心是「缓存被作废、agent 重跑、绝不复用过期结果」，而非「重跑必然成功」。</p>
     */
    @Test
    @DisplayName("A.2 schema 复验失效：invalidateJournal + 重跑，不复用过期结果（hooks.ts:94-98）")
    void journalSchemaRevalidationInvalidatesAndReruns() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("live-1", 0)));

        Map<String, Object> schema = Map.of("type", "object");
        // 缓存 ok 结果为 String，但 schema 声明 type=object → 复验必失败；key 含 schema
        String key = WorkflowJournal.agentCallKey("do",
                new AgentRunParams("do", schema, null, null, null, null, null, null, null));
        ports.journal.append("run-1", new JournalEntry(key, 0, ok("stale-string", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> hooks.agent("do", Map.of("schema", schema)),
                        "agent('do')", "run-1", null, true, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        // 缓存作废 → 重跑 live（adapter 被调：首次 live + retry-once 二次）
        assertThat(ports.adapterCallCount.get()).isEqualTo(2);
        // dead → null，绝不返回过期的 "stale-string"
        assertThat(result.returnValue()).isNull();
    }

    /** A.3 key 分叉 → invalidateJournal（truncate 被调）+ 后续全 live。 */
    @Test
    @DisplayName("A.3 journal key 分叉：截断（truncate 被调）+ 全 live（hooks.ts:110-113）")
    void journalDivergenceTruncatesAndRunsLive() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("live-output", 0)));

        // 预置不同 prompt 的 journal 条目 → agent("do") key 分叉
        String otherKey = WorkflowJournal.agentCallKey("other", params("other"));
        ports.journal.append("run-1", new JournalEntry(otherKey, 0, ok("stale", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> hooks.agent("do", Map.of()),
                        "agent('do')", "run-1", null, true, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        // 分叉后 stale("other") 被截断（truncate 移除），journal 仅含 live("do") 条目
        assertThat(ports.adapterCallCount.get()).isEqualTo(1);
        assertThat(result.returnValue()).isEqualTo("live-output");
        List<JournalEntry> entries = ports.journal.store.get("run-1");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).key()).isEqualTo(WorkflowJournal.agentCallKey("do", params("do")));
        assertThat(entries.get(0).result()).isEqualTo(new AgentRunResultOk("live-output", 0, null, null, null));
    }

    /** A.4 scriptChanged=true → truncate + journalInvalidated + 全量重跑。 */
    @Test
    @DisplayName("A.4 scriptChanged：truncate journal + 全量重跑（runWorkflow.ts:57-59）")
    void scriptChangedTruncatesJournalAndRunsLive() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("live-output", 0)));

        // 预置一条 journal（scriptChanged 时应被丢弃）
        String key = WorkflowJournal.agentCallKey("do", params("do"));
        ports.journal.append("run-1", new JournalEntry(key, 0, ok("stale", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> hooks.agent("do", Map.of()),
                        "agent('do')", "run-1", null, false, true)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        // scriptChanged → truncate 清空旧 journal，live 条目追加（非旧 "stale"）
        assertThat(ports.adapterCallCount.get()).isEqualTo(1);
        assertThat(result.returnValue()).isEqualTo("live-output");
        List<JournalEntry> entries = ports.journal.store.get("run-1");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).result()).isEqualTo(new AgentRunResultOk("live-output", 0, null, null, null));
    }

    // ─────────────────────── B 域：并发信号量 + 预算临界区 ───────────────────────

    /** B.3 预算临界区：spent 达 total 后下个 agent() 在临界区内抛 BudgetExhaustedError（hooks.ts:124-128）。 */
    @Test
    @DisplayName("B.3 预算临界区：spent 达 total 后 agent() 抛 BudgetExhaustedError")
    void budgetExhaustedGatesAgentInsideCriticalSection() {
        FakePorts ports = new FakePorts();
        // 每次 agent ok 累计 5 tokens；budgetTotal=5 → 第一个 agent 耗尽，第二个在临界区内被闸
        registerAdapter(ports, List.of(ok("a", 5), ok("b", 5)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> {
                            hooks.agent("a", Map.of()).join();
                            hooks.agent("b", Map.of()).join();
                            return CompletableFuture.completedFuture("done");
                        },
                        "agent('do')", "run-1", 5, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("budget exhausted");
        // 第一个 agent 成功（agent_done），第二个在预算检查处失败
        assertThat(ports.adapterCallCount.get()).isEqualTo(1);
    }

    // ─────────────────────── parallel 语义 ───────────────────────

    /** parallel：单项失败 → null 占位 + 其余成功（hooks.ts:265-284）。 */
    @Test
    @DisplayName("parallel 单项失败 → null 占位 + 其余成功")
    void parallelSingleFailureBecomesNull() {
        FakePorts ports = new FakePorts();
        registerAdapter(ports, List.of(ok("a", 0)));

        WorkflowRunResult result = run(ports,
                        (hooks, args, budget) -> hooks.parallel(List.of(
                                () -> CompletableFuture.completedFuture("ok"),
                                () -> CompletableFuture.failedFuture(new RuntimeException("item-fail"))))
                                .thenApply(v -> (Object) v),
                        "agent('do')", "run-1", null, false, false)
                .join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        List<Object> values = (List<Object>) result.returnValue();
        assertThat(values).containsExactly("ok", null);
    }

    // ─────────────────────── 子 workflow args 透传（GAP-1 回归）───────────────────────

    /**
     * GAP-1 回归：workflow('name', args) 的 args 必须透传给子脚本 execute（runWorkflow.ts:97）。
     *
     * <p><b>WHY</b>：子 workflow 若依赖调用方参数（如 args.projectDir），args 被静默丢弃会导致子脚本以
     * null 参数产出错误结果且无任何告警——比显式失败更危险。Reflect-W1c/d/e 判定 GAP-1 为真实行为缺口：
     * WorkflowRunEngine.runSubWorkflow 原硬编码 {@code null} 传给子脚本 execute（WorkflowRunEngine.java:206），
     * 而 CC runWorkflow.ts:97 直传 {@code sub.args}。本测试断言子脚本 executor 实际收到与调用方传入一致的
     * args（而非 null）——旧实现下 subArgs 为 null，断言变红。</p>
     */
    @Test
    @DisplayName("GAP-1：workflow('name', args) 的 args 透传给子脚本 execute（runWorkflow.ts:97）")
    void subWorkflowArgsPassThrough(@TempDir Path tempDir) throws IOException {
        // 在 cwd/.claude/workflows 预置命名子脚本 sub.ts（引擎 resolveNamedScript 按 name 解析）
        Path wfDir = tempDir.resolve(".claude/workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("sub.ts"), "const sub = 'ok';");

        FakePorts ports = new FakePorts();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Object> subArgs = new AtomicReference<>();

        // fake executor：第 0 次 = 顶层脚本（args=null），第 1 次 = 子脚本（应收到透传 args）
        WorkflowScriptExecutor executor = (hooks, args, budget) -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                return hooks.workflow("sub", Map.of("key", "value"));
            }
            subArgs.set(args);
            return CompletableFuture.completedFuture("sub-saw-args");
        };

        WorkflowRunResult result = run(ports, executor, "workflow('sub', {key:'value'})",
                "run-1", null, false, false, tempDir.toString()).join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        // 顶层 + 子脚本各执行一次（子 workflow 确实被调用）
        assertThat(calls.get()).isEqualTo(2);
        // args 透传且非 null（GAP-1 核心断言：旧实现传 null 此处必红）
        assertThat(subArgs.get()).isEqualTo(Map.of("key", "value"));
        // 子脚本返回值经 workflow() 上抛
        assertThat(result.returnValue()).isEqualTo("sub-saw-args");
    }
}

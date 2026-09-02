package com.nexusai.application.agent.workflow.script;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.ProgressEmitter;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.RunStatus;
import com.nexusai.application.agent.workflow.WorkflowRunResult;
import com.nexusai.application.agent.workflow.engine.RunWorkflowOptions;
import com.nexusai.application.agent.workflow.engine.WorkflowRunEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RestrictedScriptExecutor 单测（G-2 受限 DSL 解释器）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：生产 WorkflowServiceImpl → new WorkflowRunEngine() 无注入 executor，
 * parser 默认编译本解释器。若解释器对真实脚本（canonical review / loop-until-dry / resume 形态）不
 * 对齐 CC 核心 hook 语义，生产 workflow 要么无法启动（编译期 ScriptError）要么产出错误结果——G-2 验收
 * 即「真实脚本 → hook 解释器（DEC-P0-02）接通」。本测试验证解释器驱动 WorkflowHooks 的语义与
 * CC engine/script.ts execute（script.ts:214-227）+ hooks 契约一致。</p>
 *
 * <p><b>受限模型边界</b>：非全 JS（GraalJS 引擎 jar 缺失，见 {@link RestrictedScriptExecutor} 类 Javadoc）；
 * 子集外构造编译期 fail-loud ScriptError，Date.now()/new Date() 无参/Math.random() 抛
 * {@link NonDeterministicError}（沙箱保 resume 确定性）。</p>
 */
class RestrictedScriptExecutorTest {

    private final WorkflowScriptParser parser = new WorkflowScriptParser();

    // ════════════════════════════════════════════════════════════════════
    // 基础子集求值
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("算术/字符串/模板/对象/数组/if/while/for...of 求值")
    void evaluatesBasicSubset() {
        assertEval("return 1 + 2", 3L, null);
        assertEval("return 'a' + 1", "a1", null);
        assertEval("const a = []; a.push(1); a.push(2); return a.length", 2L, null);
        assertEval("return `v=${args.n}`", "v=5", Map.of("n", 5L));
        assertEval("let x = 0; if (args.n > 1) { x = 10 } else { x = 20 } return x", 10L, Map.of("n", 2L));
        assertEval("let i = 0; let s = 0; while (i < 5) { s = s + i; i = i + 1 } return s", 10L, null);
        assertEval("const out = []; for (const v of args.list) { out.push(v * 2) } return out",
                List.of(2L, 4L, 6L), Map.of("list", List.of(1L, 2L, 3L)));
    }

    @Test
    @DisplayName("对象字面量 shorthand / 成员访问 / 三元")
    void evaluatesObjectAndMember() {
        Object v = eval("const a = { name: 'x' }; const out = { ...a, size: 2 }; return out", null);
        assertThat(v).isEqualTo(Map.of("name", "x", "size", 2L));
        assertEval("return args.a === 1 ? 'one' : 'other'", "one", Map.of("a", 1L));
    }

    // ════════════════════════════════════════════════════════════════════
    // canonical 模式（CC integration.test.ts CANONICAL_REVIEW_SCRIPT 逐字复刻）
    // ════════════════════════════════════════════════════════════════════

    /**
     * canonical review 模式端到端（pipeline→parallel→agent(schema)→phase）· 对齐 CC integration.test.ts:68-95。
     *
     * <p>WHY：这是 Workflow tool 定义里的 canonical 脚本形态，是生产最可能遇到的编排模式；解释器必须
     * 在 pipeline 阶段函数 / parallel thunk / .then 链 / flat / filter(Boolean) / filter 谓词 / object
     * spread / shorthand 全链路下产出与 CC 相同的结果（total=2, confirmed=2, agent 调用 4 次）。</p>
     */
    @Test
    @DisplayName("canonical review：pipeline→parallel→agent→phase 产出 total/confirmed 与 CC 一致")
    void canonicalReviewPattern() throws Exception {
        String script = """
                export const meta = {
                  name: 'review-changes',
                  description: 'Review changed files across dimensions',
                  phases: [{ title: 'Review' }, { title: 'Verify' }],
                }
                const DIMENSIONS = [
                  { key: 'bugs', prompt: 'review-bugs' },
                  { key: 'perf', prompt: 'review-perf' },
                ]
                const FINDINGS_SCHEMA = { type: 'object' }
                const VERDICT_SCHEMA = { type: 'object' }

                phase('Review')
                const results = await pipeline(
                  DIMENSIONS,
                  d => agent(d.prompt, { label: 'review:' + d.key, phase: 'Review', schema: FINDINGS_SCHEMA }),
                  review => parallel(
                    review.findings.map(f => () =>
                      agent('verify: ' + f.title, { label: 'verify:' + f.file, phase: 'Verify', schema: VERDICT_SCHEMA })
                        .then(v => ({ ...f, verdict: v }))
                    )
                  )
                )
                const all = results.flat().filter(Boolean)
                const confirmed = all.filter(f => f.verdict && f.verdict.isReal)
                return { confirmed, total: all.length }
                """;

        RecordingHooks hooks = new RecordingHooks();
        Object out = parser.parse(script).execute(hooks, null, null).get();

        assertThat(out).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) out;
        assertThat(result.get("total")).isEqualTo(2L);
        assertThat(result.get("confirmed")).asList().hasSize(2);
        // 2 review + 2 verify = 4 次 agent hook 调用（CC integration.test.ts:117 只按前缀计数，不锁顺序——
        //   pipeline 各 item 经 allOf/thenCompose 并发，顺序不确定，与 CC 一致）
        assertThat(hooks.agentCalls).hasSize(4);
        assertThat(hooks.agentCalls.stream().filter(p -> p.startsWith("review-")).count()).isEqualTo(2);
        assertThat(hooks.agentCalls.stream().filter(p -> p.startsWith("verify")).count()).isEqualTo(2);
        // phase('Review') 显式调用一次；verify 的 phase:'Verify' 只是展示 label 不触发 phase_started
        assertThat(hooks.phaseCalls).containsExactly("Review");
    }

    // ════════════════════════════════════════════════════════════════════
    // loop-until-dry（CC integration.test.ts:143-216）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("loop-until-dry：while + filter + includes + push(...) 收敛")
    void loopUntilDryPattern() throws Exception {
        String script = """
                const seen = []
                const confirmed = []
                let dry = 0
                while (dry < 2) {
                  const found = (await agent('find bugs')).bugs
                  const fresh = found.filter(b => !seen.includes(b.b))
                  if (fresh.length === 0) { dry++; continue }
                  dry = 0
                  for (const b of fresh) seen.push(b.b)
                  confirmed.push(...fresh)
                }
                return { confirmed }
                """;

        RecordingHooks hooks = new RecordingHooks();
        hooks.agentFn = prompt -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (hooks.agentCalls.size() <= 1) {
                out.put("bugs", List.of(Map.of("b", 1L)));
            } else if (hooks.agentCalls.size() == 2) {
                out.put("bugs", List.of(Map.of("b", 2L)));
            } else {
                out.put("bugs", List.of());
            }
            return out;
        };
        Object out = parser.parse(script).execute(hooks, null, null).get();

        assertThat(out).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) out;
        assertThat(result.get("confirmed")).asList().hasSize(2);
        // round1 {b:1}, round2 {b:2}，round3/4 空 → 收敛（CC 注释：round 3+ 返回空）
        assertThat(hooks.agentCalls.size()).isGreaterThanOrEqualTo(4);
    }

    // ════════════════════════════════════════════════════════════════════
    // resume 形态（CC integration.test.ts:246-251）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resume 形态：phase + 串行 await agent + 对象返回")
    void resumeShape() throws Exception {
        String script = """
                phase('A')
                const a = await agent('do-a')
                const b = await agent('do-b')
                return { a, b }
                """;
        RecordingHooks hooks = new RecordingHooks();
        Object out = parser.parse(script).execute(hooks, null, null).get();
        assertThat(out).isEqualTo(Map.of("a", "do-a-out", "b", "do-b-out"));
        assertThat(hooks.phaseCalls).containsExactly("A");
    }

    // ════════════════════════════════════════════════════════════════════
    // 子 workflow（hooks.workflow）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("workflow('sub', args) → hooks.workflow 返回透传结果")
    void subWorkflowHook() throws Exception {
        String script = "const r = await workflow('sub', { k: 1 }); return r";
        RecordingHooks hooks = new RecordingHooks();
        hooks.workflowFn = (name, args) -> Map.of("subResult", name + "/" + args);
        Object out = parser.parse(script).execute(hooks, null, null).get();
        assertThat(out).isEqualTo(Map.of("subResult", "sub/{k=1}"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 沙箱拒绝（保 resume 确定性）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Date.now() / new Date() 无参 / Math.random() → NonDeterministicError")
    void sandboxRejectsNonDeterminism() {
        assertRuntimeError("return Date.now()", NonDeterministicError.class);
        assertRuntimeError("return new Date()", NonDeterministicError.class);
        assertRuntimeError("return Math.random()", NonDeterministicError.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 生产接线 G-2：new WorkflowRunEngine()（无参）走 parser 默认 RestrictedScriptExecutor
    // ════════════════════════════════════════════════════════════════════

    /**
     * G-2 验收：生产 WorkflowServiceImpl → {@code new WorkflowRunEngine()}（无参，executor=null）
     * 经 parser 默认编译 RestrictedScriptExecutor，真实脚本 `phase + await agent + return` 全链跑通。
     *
     * <p>WHY：G-2 的核心缺口是「生产 new WorkflowRunEngine() 无 executor → NOT_WIRED IllegalStateException」。
     * 本测试直接以无参构造 + 真实引擎 + 真实 parser 默认执行器跑真实脚本，断言 completed + agent 返回值
     * ——旧实现（NOT_WIRED）此处必抛 IllegalStateException。</p>
     */
    @Test
    @DisplayName("G-2：new WorkflowRunEngine() 无参 + 默认 RestrictedScriptExecutor 跑真实脚本 → completed")
    void productionNoArgEngineRunsScript() {
        MinimalPorts ports = new MinimalPorts();
        WorkflowRunEngine engine = new WorkflowRunEngine();
        RunWorkflowOptions opts = new RunWorkflowOptions(
                "phase('A')\nconst r = await agent('do')\nreturn r",
                null, "run-g2", "g2", ports,
                HostHandle.create("host"), new AbortController(),
                System.getProperty("user.dir"), null, null, false, false);

        WorkflowRunResult result = engine.run(opts).join();

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.returnValue()).isEqualTo("real-out");
        assertThat(ports.phaseCalls).containsExactly("A");
    }

    /** 最小 WorkflowPorts：真 AgentAdapterRegistry（fake adapter 返回 ok）+ 内存 journal/registrar/emitter。 */
    static final class MinimalPorts implements com.nexusai.application.agent.workflow.WorkflowPorts {
        final com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry registry =
                new com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry();
        final java.util.List<String> phaseCalls = new ArrayList<>();

        MinimalPorts() {
            registry.register(new com.nexusai.application.agent.workflow.agent.AgentAdapter() {
                @Override public String id() { return "fake"; }
                @Override public com.nexusai.application.agent.workflow.agent.AgentAdapterCapabilities capabilities() {
                    return com.nexusai.application.agent.workflow.agent.AgentAdapterCapabilities.full();
                }
                @Override public CompletableFuture<com.nexusai.application.agent.workflow.AgentRunResult> run(
                        com.nexusai.application.agent.workflow.AgentRunParams params,
                        com.nexusai.application.agent.workflow.agent.AgentAdapterContext ctx) {
                    return CompletableFuture.completedFuture(
                            new com.nexusai.application.agent.workflow.AgentRunResultOk("real-out", 1, null, null, null));
                }
            }).defaultAdapter("fake");
        }

        @Override public com.nexusai.application.agent.workflow.AgentRunner agentRunner() { return null; }
        @Override public com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry agentAdapterRegistry() { return registry; }
        @Override public ProgressEmitter progressEmitter() {
            return new ProgressEmitter() {
                @Override public void emit(ProgressEvent event) {
                    if (event instanceof ProgressEvent.PhaseStarted ps) {
                        phaseCalls.add(ps.phase());
                    }
                }
            };
        }
        @Override public com.nexusai.application.agent.workflow.TaskRegistrar taskRegistrar() {
            return new com.nexusai.application.agent.workflow.TaskRegistrar() {
                @Override public RegisterResult register(RegisterOpts opts, com.nexusai.application.agent.workflow.HostHandle host) { return null; }
                @Override public void complete(String runId, String summary) { }
                @Override public void fail(String runId, String error) { }
                @Override public void kill(String runId) { }
                @Override public boolean killAgent(String runId, int agentId) { return false; }
                @Override public PendingAction pendingAction(String runId) { return null; }
                @Override public void registerAgentAbort(String runId, int agentId, com.nexusai.application.agent.tool.AbortController abortController) { }
                @Override public void unregisterAgentAbort(String runId, int agentId) { }
            };
        }
        @Override public com.nexusai.application.agent.workflow.JournalStore journalStore() {
            return new com.nexusai.application.agent.workflow.JournalStore() {
                final java.util.Map<String, java.util.List<com.nexusai.application.agent.workflow.JournalEntry>> store = new LinkedHashMap<>();
                @Override public java.util.List<com.nexusai.application.agent.workflow.JournalEntry> read(String runId) {
                    return new ArrayList<>(store.getOrDefault(runId, java.util.List.of()));
                }
                @Override public void append(String runId, com.nexusai.application.agent.workflow.JournalEntry entry) {
                    store.computeIfAbsent(runId, k -> new ArrayList<>()).add(entry);
                }
                @Override public void truncate(String runId) { store.remove(runId); }
            };
        }
        @Override public com.nexusai.application.agent.workflow.PermissionGate permissionGate() { return host -> false; }
        @Override public com.nexusai.application.agent.workflow.WorkflowLogger logger() {
            return new com.nexusai.application.agent.workflow.WorkflowLogger() {
                @Override public void debug(String message) { }
                @Override public void warn(String message, Object... args) { }
                @Override public void event(String name, java.util.Map<String, Object> metadata) { }
            };
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 子集外构造 → 编译期 fail-loud ScriptError
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("function 声明 / class / C 风格 for → 编译期 ScriptError（fail loud，非静默）")
    void unsupportedConstructFailsLoud() {
        assertThrows(ScriptError.class, () -> parser.parse("function foo() { return 1 } return foo()"));
        assertThrows(ScriptError.class, () -> parser.parse("return /abc/"));
        assertThrows(ScriptError.class, () -> parser.parse("for (let i = 0; i < 3; i++) {} return 1"));
        assertThrows(ScriptError.class, () -> parser.parse("class Foo {} return 1"));
    }

    // ════════════════════════════════════════════════════════════════════
    // 工具
    // ════════════════════════════════════════════════════════════════════

    private void assertEval(String body, Object expected, Object args) {
        Object actual = eval(body, args);
        assertThat(actual).describedAs("body=" + body).isEqualTo(expected);
    }

    private Object eval(String body, Object args) {
        try {
            return parser.parse(body).execute(new RecordingHooks(), args, null).get();
        } catch (Exception e) {
            throw new AssertionError("eval 失败: " + body, e);
        }
    }

    private void assertRuntimeError(String body, Class<? extends Throwable> type) {
        CompletableFuture<Object> f = parser.parse(body).execute(new RecordingHooks(), null, null);
        Throwable t = null;
        try {
            f.join();
        } catch (CompletionException e) {
            t = e.getCause();
        }
        assertThat(t).describedAs("body=" + body).isInstanceOf(type);
    }

    /**
     * 假 hooks：agent 按 prompt 前缀返回 canned 结果；parallel/pipeline 镜像
     * WorkflowHooksImpl 语义（allOf + 单项失败 null + thenCompose 链）。
     */
    static final class RecordingHooks implements WorkflowHooks {
        final List<String> agentCalls = new ArrayList<>();
        final List<String> phaseCalls = new ArrayList<>();
        java.util.function.Function<String, Object> agentFn = prompt -> {
            if (prompt.startsWith("review-")) {
                return Map.of("findings", List.of(Map.of("title", prompt, "file", "a.ts")));
            }
            if (prompt.startsWith("verify")) {
                return Map.of("isReal", true);
            }
            if (prompt.startsWith("find bugs")) {
                return Map.of("bugs", List.of());
            }
            if (prompt.startsWith("do-")) {
                return prompt + "-out";
            }
            return null;
        };
        java.util.function.BiFunction<String, Object, Object> workflowFn = (name, args) -> null;

        @Override
        public CompletableFuture<Object> agent(String prompt, Map<String, Object> opts) {
            agentCalls.add(prompt);
            return CompletableFuture.completedFuture(agentFn.apply(prompt));
        }

        @Override
        public CompletableFuture<List<Object>> parallel(List<Supplier<CompletableFuture<Object>>> thunks) {
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (Supplier<CompletableFuture<Object>> t : thunks) {
                try {
                    futures.add(t.get());
                } catch (Exception e) {
                    futures.add(CompletableFuture.completedFuture(null));
                }
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<Object> out = new ArrayList<>();
                        for (CompletableFuture<Object> f : futures) {
                            out.add(f.join());
                        }
                        return out;
                    });
        }

        @Override
        public CompletableFuture<List<Object>> pipeline(List<Object> items, List<PipelineStage> stages) {
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                CompletableFuture<Object> chain = CompletableFuture.completedFuture(items.get(i));
                for (PipelineStage stage : stages) {
                    chain = chain.thenCompose(prev -> {
                        try {
                            return stage.apply(prev, items.get(idx), idx);
                        } catch (Exception e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
                }
                futures.add(chain);
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<Object> out = new ArrayList<>();
                        for (CompletableFuture<Object> f : futures) {
                            out.add(f.join());
                        }
                        return out;
                    });
        }

        @Override
        public void phase(String title) {
            phaseCalls.add(title);
        }

        @Override
        public void log(String message) {
        }

        @Override
        public CompletableFuture<Object> workflow(String nameOrRef, Object args) {
            return CompletableFuture.completedFuture(workflowFn.apply(nameOrRef, args));
        }
    }
}

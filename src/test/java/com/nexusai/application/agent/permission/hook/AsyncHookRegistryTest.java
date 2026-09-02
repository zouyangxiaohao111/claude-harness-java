package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H10] AsyncHookRegistry · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/AsyncHookRegistry.ts} (310 行全文).
 *
 * <p>WHY (规则九 · 测试验证意图): async hook 的生命周期是"注册 → 轮询 → 响应交付/清理",
 * 每个环节出错都会造成 hook 结果<b>静默丢失</b> (async hook 的 stdout 无人消费, 进程孤儿化):
 * <ol>
 *   <li>不注册 → 结果永远丢失 → 测试 1/2 验证注册语义 (含默认 timeout)</li>
 *   <li>killed/running/空 stdout 不清理 → 池泄漏 → 测试 3/4/5 验证清理与跳过</li>
 *   <li>async 声明行被当响应解析 → 死循环 → 测试 6 验证逐行解析跳过 async 键</li>
 *   <li>完成不广播 response 事件 → UI/SDK 收不到完成信号 → 测试 7/8 验证 finalizeHook 事件</li>
 *   <li>退出不收尾 → 进程泄漏 → 测试 10 验证 finalizePendingAsyncHooks</li>
 *   <li>交付失败的 hook 无法清理 → 测试 11 验证 removeDeliveredAsyncHooks</li>
 * </ol>
 *
 * <p><b>fake 进程</b>: {@link FakeAsyncHookProcess} 实现 {@link PendingAsyncHook.AsyncHookProcess},
 * status/stdout/exitCode 直接注入, 不依赖真实 shell (镜像 CommandHookExecutorTest 的
 * FakeHookProcess 模式).
 *
 * @since Session H10
 */
@DisplayName("[H10] AsyncHookRegistry 对齐 CC AsyncHookRegistry.ts")
class AsyncHookRegistryTest {

    // ════════════════════════════════════════════════════════════════════════
    // Fake 进程 (注入用) · status/stdout 直接配置
    // ════════════════════════════════════════════════════════════════════════

    /** 内存 fake async 进程 · status: 'running'|'completed'|'killed' (对齐 CC ShellCommand.status). */
    static class FakeAsyncHookProcess implements PendingAsyncHook.AsyncHookProcess {
        final String initialStatus;
        final String stdout;
        final String stderr;
        final int exitCode;
        volatile boolean killed;
        volatile boolean cleanupCalled;

        FakeAsyncHookProcess(String initialStatus, String stdout, String stderr, int exitCode) {
            this.initialStatus = initialStatus;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        static FakeAsyncHookProcess completed(String stdout, String stderr, int exitCode) {
            return new FakeAsyncHookProcess("completed", stdout, stderr, exitCode);
        }

        static FakeAsyncHookProcess running(String stdout) {
            return new FakeAsyncHookProcess("running", stdout, "", 0);
        }

        static FakeAsyncHookProcess killed(String stdout) {
            return new FakeAsyncHookProcess("killed", stdout, "", 0);
        }

        @Override public String status() { return killed ? "killed" : initialStatus; }
        @Override public String stdout() { return stdout; }
        @Override public String stderr() { return stderr; }
        @Override public void cleanup() { cleanupCalled = true; }
        @Override public void kill() { killed = true; }
        @Override public int exitCode() { return exitCode; }
    }

    private HookEventBus eventBus;
    private AsyncHookRegistry registry;
    private List<HookEventBus.HookExecutionEvent> events;

    @BeforeEach
    void setUp() {
        eventBus = new HookEventBus();
        registry = new AsyncHookRegistry(eventBus);
        events = new CopyOnWriteArrayList<>();
        eventBus.registerHookEventHandler(events::add);
    }

    @AfterEach
    void tearDown() {
        registry.clearAllAsyncHooks();
        eventBus.clearHookEventState();
    }

    /** 注册辅助 · asyncResponse 缺省 (asyncTimeout null → 默认 15000). */
    private void register(String processId, String hookEvent, PendingAsyncHook.AsyncHookProcess proc) {
        register(processId, hookEvent, proc, null);
    }

    private void register(String processId, String hookEvent, PendingAsyncHook.AsyncHookProcess proc, Long asyncTimeout) {
        registry.registerPendingAsyncHook(processId, "hook-" + processId,
            new HookJSONOutput.AsyncHookOutput(true, asyncTimeout),
            "testHook", hookEvent, "echo hi", proc, null, null);
    }

    private HookEventBus.HookResponseEvent lastResponseEvent() {
        return events.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .findFirst().orElse(null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1-2. 注册语义 (CC :30-83)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("registerPendingAsyncHook 写入池: key=processId, timeout 默认 15000 (CC :30-83)")
    void register_writesToPool_withDefaultTimeout() {
        // WHY: 不注册 = async hook 结果永远丢失 — 进程后台跑了但无人读取其 stdout.
        //      CC :51 {@code asyncResponse.asyncTimeout || 15000} — 未传超时用默认 15s 存储.
        registry.registerPendingAsyncHook("p1", "h1",
            new HookJSONOutput.AsyncHookOutput(true, null),
            "testHook", "PreToolUse", "echo", FakeAsyncHookProcess.running(""), null, null);

        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        PendingAsyncHook hook = registry.getPendingAsyncHooks().get(0);
        assertThat(hook.processId()).isEqualTo("p1");
        assertThat(hook.timeout()).isEqualTo(15000L);
        assertThat(hook.responseAttachmentSent()).isFalse();
    }

    @Test
    @DisplayName("register 带 asyncTimeout=5000 → timeout 字段=5000 (CC :51 存储语义)")
    void register_withAsyncTimeout_storesValue() {
        // WHY: H10 spec 曾假设"asyncTimeout 超时未完成 → 处理超时", 已证伪 —
        //      CC 源码中 timeout 只存不用 (AsyncHookRegistry.ts:20/51/53/78 无消费点).
        //      本测试验证的是<b>存储</b>语义 (调用方传入的 timeout 必须原样落池), 不是超时行为.
        register("p2", "PreToolUse", FakeAsyncHookProcess.running(""), 5000L);

        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        assertThat(registry.getPendingAsyncHooks().get(0).timeout()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("registerPendingAsyncHook 不发射 started (决策 2-3 / D-WF5-05): started 在执行入口")
    void register_doesNotEmitStartedEvent() {
        // WHY (hooks_v3 决策 2-3 / D-WF5-05, X-WF5-01): CC 注册侧不发 started —
        //      registerPendingAsyncHook (AsyncHookRegistry.ts:30-83) 无任何 emitHookStarted;
        //      started 在 hook 执行入口 emit (hooks.ts:2297/:2446), 与 response 同 hookId 配对.
        //      旧实现注册侧发 started (D-01) → 「执行入口 S + 注册侧 R」双发孤儿 started
        //      (WF5-02 ⊕-1 / S4). 本测试验证注册侧<b>不再</b>发射 started — hookEvent 用
        //      SessionStart (ALWAYS_EMITTED 白名单), 若注册侧仍发必被 handler 收到.
        registry.registerPendingAsyncHook("p-d1", "h-d1",
            new HookJSONOutput.AsyncHookOutput(true, null),
            "testHook", "SessionStart", "echo", FakeAsyncHookProcess.running(""), null, null);

        HookEventBus.HookStartedEvent started = events.stream()
            .filter(e -> e instanceof HookEventBus.HookStartedEvent)
            .map(e -> (HookEventBus.HookStartedEvent) e)
            .findFirst().orElse(null);
        assertThat(started)
            .as("注册侧不得发 started (CC AsyncHookRegistry.ts:30-83 无 emitHookStarted)")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3-5. 轮询状态分流 (CC :152-181)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("checkForAsyncHookResponses: killed → remove 出池 + cleanup (CC :162-169)")
    void check_killedProcess_removedFromPool() {
        // WHY: 进程已被 kill (中断/清理) → 留在池中只会让 finalizePendingAsyncHooks 对死进程
        //      再 kill 一次; CC 直接 remove + cleanup, 释放进程资源.
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.killed("partial");
        register("p3", "PreToolUse", proc);

        assertThat(registry.checkForAsyncHookResponses()).isEmpty();
        assertThat(registry.getPendingAsyncHooks()).isEmpty();
        assertThat(proc.cleanupCalled).isTrue();
    }

    @Test
    @DisplayName("checkForAsyncHookResponses: running (未完成) → skip 留在池中 (CC :171-173)")
    void check_runningProcess_skipped() {
        // WHY: 进程未完成时 stdout 不完整, 立即解析会产出半截响应; 必须等下轮轮询
        //      (CC status !== 'completed' → skip, 不 remove).
        register("p4", "PreToolUse", FakeAsyncHookProcess.running(""));

        assertThat(registry.checkForAsyncHookResponses()).isEmpty();
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
    }

    @Test
    @DisplayName("checkForAsyncHookResponses: completed 但 stdout 空 → remove (CC :175-181)")
    void check_completedButEmptyStdout_removed() {
        // WHY: 完成但无 stdout = 没有可交付的 async 响应; 留池只会空转轮询浪费
        //      (CC responseAttachmentSent || !stdout.trim() → remove).
        register("p5", "PreToolUse", FakeAsyncHookProcess.completed("", "", 0));

        assertThat(registry.checkForAsyncHookResponses()).isEmpty();
        assertThat(registry.getPendingAsyncHooks()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6-9. 响应解析与交付 (CC :183-268)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("逐行解析: 首 { 行 async JSON 跳过, 次行 sync JSON 返回 (CC :192-212)")
    void check_lineParsing_skipsAsyncLine_returnsSyncResponse() {
        // WHY: async hook 协议是 stdout 首行输出 {"async":true} 声明, 次行才是 sync 响应.
        //      把声明行当响应解析 = 把 async 声明原样返回给调用方 → 调用方再次后台化 → 死循环.
        //      CC 按 {@code 'async' in parsed} 键存在性跳过声明行 (含 async:false 的行同样跳过).
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{\"async\":true}\n{\"continue\":false}\n", "", 0);
        register("p6", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        AsyncHookRegistry.AsyncHookResponse r = responses.get(0);
        assertThat(r.processId()).isEqualTo("p6");
        assertThat(r.response()).isNotNull();
        // 次行 sync JSON 被正确解析为 SyncHookOutput
        assertThat(r.response().continueExecution()).isFalse();
        // stdout 全量原样携带 (CC :229 stdout 字段, 含 async 声明行)
        assertThat(r.stdout()).isEqualTo("{\"async\":true}\n{\"continue\":false}\n");
        assertThat(r.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("raw-accept+break: 类型偏差首行接受为响应 (CC :199-205), 不跳过扫描后续行 (OPD-WF2-PRS-06)")
    void check_rawAccept_typeDeviantFirstLine_delivered() {
        // WHY: CC 逐行提取 response = parsed 直接接受 (不 schema 校验) — 首个可解析非 async 行
        //      即 break, 类型偏差行 ({"continue":"false"}) 仍被交付. Java 旧实现走严格
        //      deserializeHookJSONOutput → 该行 throw → 跳过 → 交付后续合法行 — 方向相反
        //      (X-PROBE EV-XP-W2-022/023). 本测试用 systemMessage 区分交付哪一行:
        //      若仍走旧严格路径, 首行被跳过, 交付的将是次行 {"continue":true} (systemMessage=null).
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{\"continue\":\"false\",\"systemMessage\":\"first\"}\n{\"continue\":true}\n", "", 0);
        register("p-rs", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        AsyncHookRegistry.AsyncHookResponse r = responses.get(0);
        assertThat(r.processId()).isEqualTo("p-rs");
        // 交付的是首行 (raw-accept): systemMessage='first' 保留
        assertThat(r.response().systemMessage()).isEqualTo("first");
        // continue 为字符串 "false" 类型偏差 → 宽松置 null (不触发下游 stop; CC 下游按值比较不匹配)
        assertThat(r.response().continueExecution()).isNull();
    }

    @Test
    @DisplayName("E4-2 类型偏差行递送: stdout={\"continue\":\"false\"}\\n{\"continue\":true} 交付首行 (CC raw-accept+break, X-PROBE E4-2)")
    void check_e4_2_typeDeviantFirstLine_delivered_exactScenario() {
        // WHY (X-PROBE E4-2 / 探查-wf2-verify.md §2 表 E4-2): 精确场景 stdout =
        //      {"continue":"false"}\n{"continue":true} — CC AsyncHookRegistry.ts:191-212
        //      对首个可解析非 async 行 response = parsed 直接交付 (raw-accept+break),
        //      类型偏差行 {"continue":"false"} 被接受为首个响应, break 停止扫描次行
        //      {"continue":true}. 旧 Java 严格 deserializeHookJSONOutput 会对 continue
        //      非 boolean throw → 跳过首行 → 交付次行 continue=true — 方向相反.
        //      断言 continueExecution() 为 null (首行被交付, "false" 字符串经宽松 boolOrNull
        //      置 null) 而非 true (次行), 直接锁定交付方向 — 回归到旧严格路径本测试即 RED.
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{\"continue\":\"false\"}\n{\"continue\":true}\n", "", 0);
        register("p-e42", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        AsyncHookRegistry.AsyncHookResponse r = responses.get(0);
        assertThat(r.processId()).isEqualTo("p-e42");
        // 交付的是首行 {"continue":"false"} (raw-accept): "false" 字符串类型偏差 → 宽松置 null.
        // 若仍走旧严格路径, 首行被跳过 → 交付次行 {"continue":true} → 此处为 true — 断言 null 锁定方向.
        assertThat(r.response().continueExecution()).isNull();
        // 两行均无 systemMessage — 交付行与次行字段一致, 以 continue 值区分交付方向
        assertThat(r.response().systemMessage()).isNull();
    }

    @Test
    @DisplayName("break 语义: 首个非 async 行即停 (CC :203-204), 后续行不覆盖 (OPD-WF2-PRS-06)")
    void check_break_stopsAtFirstSyncLine() {
        // WHY: CC 找到首个可解析非 async 行即 response = parsed + break — 后续行不参与扫描.
        //      若未 break, 次行会覆盖首行 → systemMessage 变 'second'. 断言首行胜出.
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{\"systemMessage\":\"first\"}\n{\"systemMessage\":\"second\"}\n", "", 0);
        register("p-br", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).response().systemMessage()).isEqualTo("first");
    }

    @Test
    @DisplayName("尾随 token: 双 JSON 行 {\\\"continue\\\":true}{\\\"x\\\":1} 拒绝 → 空响应 (E4-1)")
    void check_trailingJsonObject_lineRejected_emptyResponse() {
        // WHY (E4-1 / OPD-WF2-PRS-03): CC jsonParse = JSON.parse (slowOperations.ts:204-211)
        //      对 {"continue":true}{"x":1} 抛 SyntaxError → 该行跳过 (AsyncHookRegistry.ts:198
        //      catch → 下一行), 无有效 sync 行 → response 保持空 {} (CC :191). Java 旧行为
        //      readTree 默认容忍尾随 token (只取首值 {"continue":true}, 忽略 {"x":1}) →
        //      误交付 continue=true — 方向与 CC 相反. 对齐后该行应被拒绝, 交付空响应.
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{\"continue\":true}{\"x\":1}\n", "", 0);
        register("p-e4a", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).response().continueExecution())
            .as("双 JSON 尾随行被拒绝 → 空响应 (CC JSON.parse 抛错, AsyncHookRegistry.ts:198)")
            .isNull();
    }

    @Test
    @DisplayName("尾随 token: {} extra 行拒绝并继续扫描次行 (E4-1)")
    void check_trailingBareToken_lineRejected_continueScansNext() {
        // WHY (E4-1): {"continue":true}{...} 与 {} extra 同源 — JSON.parse 对尾随内容抛错 →
        //      本行跳过, 循环继续扫描后续行 (CC :206-210 catch). Java 旧行为 readTree 对
        //      "{} extra" 容忍 (只取 {}) → 把空对象当响应返回并 break, 永不扫描次行. 对齐后
        //      首行被拒绝 → 次行 {"continue":true} 被交付 (skip-and-continue).
        FakeAsyncHookProcess proc = FakeAsyncHookProcess.completed(
            "{} extra\n{\"continue\":true}\n", "", 0);
        register("p-e4b", "PreToolUse", proc);

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).response().continueExecution())
            .as("{} extra 行被拒绝 → 次行 continue=true 被交付 (CC skip-and-continue)")
            .isTrue();
    }

    @Test
    @DisplayName("完成 → finalizeHook → HookEventBus 收到 response 事件 outcome=success (CC :214-215)")
    void check_completed_finalizeEmitsResponseEvent() {
        // WHY: 交付响应必须同时广播 response 事件 (CC finalizeHook → emitHookResponse),
        //      否则 UI/SDK 永远等不到"hook 已完成"信号. hookEvent=SessionStart
        //      (ALWAYS_EMITTED) 保证事件真实发出而不是被白名单过滤.
        register("p7", "SessionStart", FakeAsyncHookProcess.completed("{\"continue\":true}\n", "", 0));

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        HookEventBus.HookResponseEvent evt = lastResponseEvent();
        assertThat(evt).isNotNull();
        assertThat(evt.outcome()).isEqualTo(HookEventBus.HookOutcome.SUCCESS);
        assertThat(evt.exitCode()).isEqualTo(0);
        assertThat(evt.hookName()).isEqualTo("testHook");
        assertThat(evt.hookId()).isEqualTo("hook-p7");
    }

    @Test
    @DisplayName("exit=2 → outcome=error (CC :215 exitCode===0?'success':'error')")
    void check_exit2_outcomeError() {
        // WHY: exit 2 = blocking error — 响应事件必须标 error, 否则下游把阻断当成功处理,
        //      hook 的错误被静默吞掉 (CC finalizeHook outcome 三值语义).
        register("p8", "SessionStart", FakeAsyncHookProcess.completed("{\"decision\":\"block\"}\n", "boom", 2));

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).exitCode()).isEqualTo(2);
        HookEventBus.HookResponseEvent evt = lastResponseEvent();
        assertThat(evt).isNotNull();
        assertThat(evt.outcome()).isEqualTo(HookEventBus.HookOutcome.ERROR);
    }

    @Test
    @DisplayName("SessionStart 完成 → 响应返回 (isSessionStart 语义; Java 端仅日志对齐, CC :257-262)")
    void check_sessionStart_completed_returnsResponse() {
        // WHY: CC 在 SessionStart hook 完成后 invalidateSessionEnvCache (环境变量可能已变);
        //      Java 无 session env cache 机制 → 仅日志对齐 (concern H10-3). 但响应本身必须
        //      照常返回 — 不能因"无缓存可失效"而丢响应.
        register("p9", "SessionStart", FakeAsyncHookProcess.completed("{\"continue\":true}\n", "", 0));

        List<AsyncHookRegistry.AsyncHookResponse> responses = registry.checkForAsyncHookResponses();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).hookEvent()).isEqualTo("SessionStart");
        assertThat(responses.get(0).processId()).isEqualTo("p9");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 10-11. 收尾与清理 (CC :270-309)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("finalizePendingAsyncHooks: completed → finalize 真实结果; running → kill + cancelled (CC :281-301)")
    void finalizePendingAsyncHooks_completedAndRunning() {
        // WHY: 会话结束/应用退出必须收尾全部挂起 hook: 已完成的交付真实 exit code 结果,
        //      未完成的先 kill 再标 cancelled (CC :285-297) — 否则进程泄漏 + 挂起 hook
        //      永远等不到 finalize.
        register("pa", "SessionStart", FakeAsyncHookProcess.completed("{\"continue\":true}\n", "", 0));
        FakeAsyncHookProcess runningProc = FakeAsyncHookProcess.running("");
        register("pb", "SessionStart", runningProc);

        registry.finalizePendingAsyncHooks();

        assertThat(registry.getPendingAsyncHooks()).isEmpty();
        // 未完成的先 kill (CC :293-295) 再 cancelled (exit=1)
        assertThat(runningProc.killed).isTrue();
        HookEventBus.HookResponseEvent cancelled = events.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .filter(e -> e.hookId().equals("hook-pb")).findFirst().orElse(null);
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.outcome()).isEqualTo(HookEventBus.HookOutcome.CANCELLED);
        assertThat(cancelled.exitCode()).isEqualTo(1);
        // 已完成的按真实 exit code finalize success
        HookEventBus.HookResponseEvent success = events.stream()
            .filter(e -> e instanceof HookEventBus.HookResponseEvent)
            .map(e -> (HookEventBus.HookResponseEvent) e)
            .filter(e -> e.hookId().equals("hook-pa")).findFirst().orElse(null);
        assertThat(success).isNotNull();
        assertThat(success.outcome()).isEqualTo(HookEventBus.HookOutcome.SUCCESS);
    }

    @Test
    @DisplayName("removeDeliveredAsyncHooks: 只删 responseAttachmentSent=true 的 (CC :270-279)")
    void removeDeliveredAsyncHooks_onlyRemovesDelivered() {
        // WHY: 交付失败 (finalizeHook 抛异常) 的 hook 会以 responseAttachmentSent=true 残留在池中
        //      (CC allSettled 语义: 已应用的 flag 不因异常回滚, :236-246). 清理通道必须只删
        //      已标记交付的 — 误删未交付 hook = 还在跑的 hook 结果丢失.
        FakeAsyncHookProcess delivered = FakeAsyncHookProcess.completed("{\"continue\":true}\n", "", 0);
        register("pd", "SessionStart", delivered);
        FakeAsyncHookProcess pendingProc = FakeAsyncHookProcess.running("");
        register("pe", "PreToolUse", pendingProc);

        // 制造交付失败: handler 抛异常 → finalizeHook 抛 → hook 留池 (flag=true)
        eventBus.registerHookEventHandler(e -> { throw new RuntimeException("delivery failed"); });
        assertThat(registry.checkForAsyncHookResponses()).isEmpty();
        // pd 因 flag=true 被 getPendingAsyncHooks 过滤但仍留在 map; pe 未完成不受影响
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        assertThat(registry.getPendingAsyncHooks().get(0).processId()).isEqualTo("pe");

        // 恢复 handler 后执行清理
        eventBus.registerHookEventHandler(events::add);
        registry.removeDeliveredAsyncHooks(List.of("pd", "pe"));

        // 只删了 pd (已交付), pe 仍在池中
        assertThat(registry.getPendingAsyncHooks()).hasSize(1);
        assertThat(registry.getPendingAsyncHooks().get(0).processId()).isEqualTo("pe");
    }
}

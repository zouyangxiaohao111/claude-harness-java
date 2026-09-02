package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DreamTaskRegistry 定向测试 · 对齐 CC tasks/DreamTask/DreamTask.ts（真源 157L）+ framework.ts:59-63/77
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>registerDreamTask</b>（DreamTask.ts:52-74）——fork 出来的 memory-consolidation
 *       subagent 原本不可见，注册进 registry + SDK task_started 后前端 footer/dialog 才显示
 *       其运行状态；status 必须 running（覆盖 createTaskStateBase 的 pending）、phase 从
 *       starting 起步（首个 Edit/Write tool_use 才翻 updating）。</li>
 *   <li><b>addDreamTurn 的 no-op 跳过</b>（framework.ts:59-63）——空 turn 且无新 touched 时
 *       返回原引用不写 store，避免纯 no-op 触发 re-render / 状态抖动。</li>
 *   <li><b>filesTouched 去重 + phase 翻转</b>（DreamTask.ts:83-100）——"至少这些被改过"的
 *       INCOMPLETE 反射语义；第一个 Edit/Write 落点才把 'starting' 翻 'updating'。</li>
 *   <li><b>turns 截断 MAX_TURNS=30</b>（DreamTask.ts:12/:101）——仅保留最近 N 个 turn 供
 *       live display，防 turns 无限增长。</li>
 *   <li><b>complete/fail/kill 置终态 + notified:true 立即</b>（DreamTask.ts:106-156）——dream
 *       UI-only 无模型通知路径，eviction 要求 terminal+notified（:110-112）；abortController
 *       清空释放 fork 引用。kill 仅 running（:139 no-op）+ 捕获 priorMtime 回退锁（:153-155），
 *       否则锁 mtime=now 使时间门 24h 阻断下轮无法重试。</li>
 * </ul>
 */
@DisplayName("[OPD-TP-09] DreamTaskRegistry（registerDreamTask + addDreamTurn + complete/fail/kill）")
class DreamTaskRegistryTest {

    private DreamTaskRegistry newRegistry() {
        return new DreamTaskRegistry();
    }

    @Test
    @DisplayName("registerDreamTask 产出 DREAM/RUNNING/phase=starting/前缀 d/注入三字段（DreamTask.ts:60-71）")
    void registerDreamTask_createsRunningDreamTaskWithDreamFields() {
        DreamTaskRegistry registry = newRegistry();
        AbortController abort = new AbortController();

        String taskId = registry.registerDreamTask(3, 123456789L, abort);

        // 前缀 d + 8 base36（CC Task.ts:86 dream:'d' + generateTaskId:98-106）
        assertThat(taskId).startsWith("d").hasSize(9);
        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.type()).isEqualTo(TaskType.DREAM);
        assertThat(state.status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        assertThat(state.phase()).isEqualTo(DreamTaskState.DreamPhase.STARTING);
        assertThat(state.description()).isEqualTo("dreaming");
        assertThat(state.sessionsReviewing()).isEqualTo(3);
        assertThat(state.priorMtime()).isEqualTo(123456789L);
        assertThat(state.abortController()).isSameAs(abort);
        assertThat(state.filesTouched()).isEmpty();
        assertThat(state.turns()).isEmpty();
        assertThat(state.startTime()).isGreaterThan(0);
        assertThat(state.notified()).isFalse();
    }

    @Test
    @DisplayName("registerDreamTask 经 TaskFrameworkService.registerTask 落库 + 发 task_started（framework.ts:77/104-116）")
    void registerDreamTask_registersIntoFrameworkAndEmitsTaskStarted() {
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        DreamTaskRegistry registry = new DreamTaskRegistry(framework);

        String taskId = registry.registerDreamTask(2, 99L, new AbortController());

        // 统一 store 可见 + DREAM/RUNNING/'dreaming'（前端面板卡）
        BackgroundTask bg = framework.getTask(taskId).orElseThrow();
        assertThat(bg.type()).isEqualTo(TaskType.DREAM);
        assertThat(bg.status()).isEqualTo(BackgroundTaskStatus.RUNNING);
        assertThat(bg.description()).isEqualTo("dreaming");
        assertThat(bg.notified()).isFalse();

        // SDK task_started 已发（对齐 framework.ts:104-116）
        SdkEventQueue.TaskStartedEvent evt =
            (SdkEventQueue.TaskStartedEvent) sdk.drainSdkEvents("sess").get(0).event();
        assertThat(evt.taskId()).isEqualTo(taskId);
        assertThat(evt.taskType()).isEqualTo("dream"); // CC TaskType 枚举小写值
        assertThat(evt.description()).isEqualTo("dreaming");
    }

    @Test
    @DisplayName("addDreamTurn 空 turn + 无新 touched → no-op 跳过（framework.ts:59-63 原引用）")
    void addDreamTurn_emptyTurnAndNoTouched_isNoop() {
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(1, 1L, new AbortController());
        DreamTaskState before = registry.getDreamTask(taskId).orElseThrow();

        registry.addDreamTurn(taskId, new DreamTaskState.DreamTurn("", 0), List.of());

        // 原引用未变（store 未写）——纯 no-op 不触发 re-render
        assertThat(registry.getDreamTask(taskId)).get().isSameAs(before);
        assertThat(before.turns()).isEmpty();
        assertThat(before.filesTouched()).isEmpty();
        assertThat(before.phase()).isEqualTo(DreamTaskState.DreamPhase.STARTING);
    }

    @Test
    @DisplayName("addDreamTurn 去重 touched + 首个 Edit/Write 翻 phase=updating（DreamTask.ts:83-100）")
    void addDreamTurn_deduplicatesTouchedAndFipsPhaseToUpdating() {
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(1, 1L, new AbortController());

        registry.addDreamTurn(taskId,
            new DreamTaskState.DreamTurn("分析完成", 2), List.of("/a", "/b", "/a"));

        DreamTaskState s1 = registry.getDreamTask(taskId).orElseThrow();
        assertThat(s1.phase()).isEqualTo(DreamTaskState.DreamPhase.UPDATING);
        assertThat(s1.filesTouched()).containsExactly("/a", "/b"); // 同批次去重
        assertThat(s1.turns()).hasSize(1);
        assertThat(s1.turns().get(0).text()).isEqualTo("分析完成");
        assertThat(s1.turns().get(0).toolUseCount()).isEqualTo(2);

        // 跨批次去重：/b 已见过，只新增 /c
        registry.addDreamTurn(taskId,
            new DreamTaskState.DreamTurn("收尾", 1), List.of("/b", "/c"));
        DreamTaskState s2 = registry.getDreamTask(taskId).orElseThrow();
        assertThat(s2.filesTouched()).containsExactly("/a", "/b", "/c");
        assertThat(s2.phase()).isEqualTo(DreamTaskState.DreamPhase.UPDATING);
        assertThat(s2.turns()).hasSize(2);
    }

    @Test
    @DisplayName("addDreamTurn 非空 turn（无 touched）也更新 turns——no-op 严格判据（DreamTask.ts:87-93）")
    void addDreamTurn_nonEmptyTurnWithoutTouched_stillAppendsTurn() {
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(1, 1L, new AbortController());

        registry.addDreamTurn(taskId, new DreamTaskState.DreamTurn("有文本", 0), List.of());
        registry.addDreamTurn(taskId, new DreamTaskState.DreamTurn("", 0), List.of()); // no-op
        registry.addDreamTurn(taskId, new DreamTaskState.DreamTurn("", 5), List.of()); // count>0 非 no-op

        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.turns()).hasSize(2); // no-op 未计入
        assertThat(state.turns().get(0).text()).isEqualTo("有文本");
        assertThat(state.turns().get(1).toolUseCount()).isEqualTo(5);
        // 无新 touched → phase 保持 starting（DreamTask.ts:96）
        assertThat(state.phase()).isEqualTo(DreamTaskState.DreamPhase.STARTING);
    }

    @Test
    @DisplayName("addDreamTurn turns 截断至 MAX_TURNS=30（DreamTask.ts:12/:101 slice(-(MAX-1)).concat）")
    void addDreamTurn_truncatesTurnsToMaxTurns() {
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(1, 1L, new AbortController());

        for (int i = 1; i <= 31; i++) {
            registry.addDreamTurn(taskId,
                new DreamTaskState.DreamTurn("t" + i, 0), List.of());
        }

        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.turns()).hasSize(30);
        assertThat(state.turns().get(0).text()).isEqualTo("t2"); // t1 被挤出
        assertThat(state.turns().get(29).text()).isEqualTo("t31");
    }

    // ═══════════════════ complete / fail / kill（W7-02 · OPD-TP-09）═══════════════════

    @Test
    @DisplayName("completeDreamTask 置 completed + endTime + notified=true 立即 + abortController 清空（DreamTask.ts:106-120）")
    void completeDreamTask_setsTerminalStateNotifiedAndClearsAbortController() {
        // WHY: dream UI-only，complete 无模型通知路径；eviction 要求 terminal+notified（:110-112），
        //   notified 不立即 true → evictTerminalTask/generateTaskAttachments 永不回收（泄露）。
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        DreamTaskRegistry registry = new DreamTaskRegistry(framework);
        AbortController abort = new AbortController();
        String taskId = registry.registerDreamTask(3, 123L, abort);

        registry.completeDreamTask(taskId);

        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(state.endTime()).isNotNull().isGreaterThanOrEqualTo(state.startTime());
        assertThat(state.notified()).as("eviction 要求 terminal+notified（DreamTask.ts:110-112）").isTrue();
        assertThat(state.abortController()).isNull(); // :118 abortController: undefined
        // 统一 store 同步终态 + notified=true → evictTerminalTask 可回收
        BackgroundTask bg = framework.getTask(taskId).orElseThrow();
        assertThat(bg.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(bg.notified()).isTrue();
        // 保留 phase/filesTouched/turns/priorMtime（CC spread 保留）
        assertThat(state.priorMtime()).isEqualTo(123L);
    }

    @Test
    @DisplayName("failDreamTask 置 failed + endTime + notified=true + abortController 清空（DreamTask.ts:122-130）")
    void failDreamTask_setsFailedStateNotifiedAndClearsAbortController() {
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(2, 456L, new AbortController());

        registry.failDreamTask(taskId);

        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(state.endTime()).isNotNull();
        assertThat(state.notified()).isTrue();
        assertThat(state.abortController()).isNull();
    }

    @Test
    @DisplayName("kill running 任务 → abort + killed + notified + priorMtime 回退 seam 触发（DreamTask.ts:132-156）")
    void kill_runningTask_abortsCapturesPriorMtimeAndRollsBackLock() {
        // WHY: kill 是用户终止 dream 的唯一入口（TaskStop 分发）；必须 abort fork（:140）、捕获
        //   priorMtime 回退锁（:153-155，同 fork 失败路径）——否则锁 mtime=now 使时间门 24h 阻断。
        DreamTaskRegistry registry = newRegistry();
        AbortController abort = new AbortController();
        long priorMtime = 888L;
        String taskId = registry.registerDreamTask(1, priorMtime, abort);
        AtomicReference<Long> rolledBackMtime = new AtomicReference<>(-1L);
        registry.setRollbackConsolidationLock(rolledBackMtime::set);

        boolean killed = registry.kill(taskId);

        assertThat(killed).isTrue();
        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(state.endTime()).isNotNull();
        assertThat(state.notified()).isTrue();
        assertThat(state.abortController()).isNull();
        // abort() 已触发（fork 的中止信号）—— kill 是唯一 abort 入口
        assertThat(abort.isCancelled()).isTrue();
        // priorMtime 已回退（rollback seam 收到捕获的 mtime）
        assertThat(rolledBackMtime.get()).isEqualTo(priorMtime);
    }

    @Test
    @DisplayName("kill 非 running 任务 → no-op（不 abort 不回退 · DreamTask.ts:139/153-155 priorMtime undefined 跳过）")
    void kill_nonRunningTask_isNoop() {
        // WHY: complete 后（fork 已成功）TaskStop 再 kill 必须短路——不 abort 已完成 fork、
        //   不覆盖终态、不重复回退锁（CC :139 return task）。
        DreamTaskRegistry registry = newRegistry();
        String taskId = registry.registerDreamTask(1, 777L, new AbortController());
        registry.completeDreamTask(taskId);
        AtomicInteger rollbackCalls = new AtomicInteger();
        registry.setRollbackConsolidationLock(m -> rollbackCalls.incrementAndGet());

        boolean killed = registry.kill(taskId);

        assertThat(killed).isFalse();
        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.status()).isEqualTo(BackgroundTaskStatus.COMPLETED); // 终态不被覆盖
        assertThat(rollbackCalls.get()).as("非 running 不 rollback（CC priorMtime undefined 跳过）").isZero();
    }

    @Test
    @DisplayName("kill 不存在的任务 → false（CC not_found 语义）")
    void kill_missingTask_returnsFalse() {
        DreamTaskRegistry registry = newRegistry();
        assertThat(registry.kill("no-such-dream")).isFalse();
    }

    @Test
    @DisplayName("终态 + notified 的 dream 任务可被 evictTerminalTask 回收（framework.ts:124-147）")
    void terminalDreamTask_canBeEvicted() {
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        DreamTaskRegistry registry = new DreamTaskRegistry(framework);
        String taskId = registry.registerDreamTask(1, 1L, new AbortController());

        registry.completeDreamTask(taskId);
        framework.evictTerminalTask(taskId);

        assertThat(framework.getTask(taskId)).isEmpty();
        // rich store 保留（Java 双存储：eviction 只清统一 store；registry 自有 store 归 GC）
        assertThat(registry.getDreamTask(taskId)).isPresent();
    }
}

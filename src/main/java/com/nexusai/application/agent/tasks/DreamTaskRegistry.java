package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Dream 任务注册表 · 对齐 CC tasks/DreamTask/DreamTask.ts:52-104（registerDreamTask + addDreamTurn）
 *
 * <p>让不可见的 auto-dream（memory consolidation）forked agent 在任务面板可见（CC 头注释
 * DreamTask.ts:1-4 "pure UI surfacing via the existing task registry"）。dream agent 本体不变。
 *
 * <p><b>双存储架构</b>（对齐 plan T1 决策锚点 OPD-TP-09）：
 * <ul>
 *   <li>本类自有 {@code ConcurrentHashMap} store —— 保存富 {@link DreamTaskState}
 *       （phase/sessionsReviewing/filesTouched/turns/abortController/priorMtime）</li>
 *   <li>经 {@link TaskFrameworkService#registerTask} 落统一 store（构 {@link BackgroundTask}
 *       DREAM/RUNNING/'dreaming'）——触发 SDK task_started（framework.ts:77/104-116），
 *       终态由 evictTerminalTask/惰性 GC 清理（framework.ts:124-147）</li>
 * </ul>
 *
 * <p>本类覆盖 CC DreamTask.ts 全部注册表行为：
 * <ul>
 *   <li><b>registerDreamTask + addDreamTurn</b>（:52-104，W7-01）——注册 + 进度收集</li>
 *   <li><b>completeDreamTask / failDreamTask</b>（:106-130）——终态 completed/failed +
 *       endTime + <b>notified:true 立即</b>（dream UI-only，eviction 要求 terminal+notified，
 *       :110-112 注释）+ abortController 清空</li>
 *   <li><b>kill</b>（:132-156）——仅 running：abort() + 捕获 priorMtime → killed + endTime +
 *       notified:true + abortController 清空 → 经 {@link #rollbackConsolidationLock} seam
 *       回退锁 mtime（:153-155；no-op 时 priorMtime undefined → 跳过）</li>
 * </ul>
 *
 * <p><b>终态落统一 store</b>：complete/fail/kill 均经
 * {@link TaskFrameworkService#updateTaskState} 把统一 store 的 BackgroundTask 同步为终态 +
 * notified=true，使 evictTerminalTask / 惰性 GC 可回收（framework.ts:124-147）。
 * <b>不发射 task_terminated SDK 事件</b>（对齐 CC 真源：DreamTask.ts complete/fail/kill 无
 * emitTaskTerminatedSdk 调用，dream 仅 UI 可见性）。
 */
public class DreamTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(DreamTaskRegistry.class);

    /** 仅保留最近 N 个 turn 供 live display · 对齐 CC DreamTask.ts:12 MAX_TURNS=30 */
    public static final int MAX_TURNS = 30;

    /** 富 dream 状态存储（Java 侧 DreamTaskState 的 home） */
    private final ConcurrentHashMap<String, DreamTaskState> store = new ConcurrentHashMap<>();

    /** 统一任务存储 + SDK 事件通道（可为 null —— 测试直构无 bean 时仅存本类 store，不落统一 store） */
    private final TaskFrameworkService taskFrameworkService;

    /**
     * 锁 mtime 回退 seam · CC original: {@code rollbackConsolidationLock}
     * （DreamTask.ts:153-155 + consolidationLock.ts:91-108）。
     *
     * <p>{@link #kill} 把 running → killed 后调用（对齐 DreamTask.kill）；由接线方注入
     * AutoDreamConsolidator 的 ConsolidationLock 等价体（ToolRegistrationConfig 装配）。
     * 可为 null（未装配时 kill 不回退锁 —— CC priorMtime undefined → 跳过等价）。
     */
    private volatile Consumer<Long> rollbackConsolidationLock;

    public DreamTaskRegistry() {
        this(null);
    }

    public DreamTaskRegistry(TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
    }

    /** 注入锁 mtime 回退 seam（kill 时回退 consolidation lock mtime · DreamTask.ts:153-155）。 */
    public void setRollbackConsolidationLock(Consumer<Long> rollbackConsolidationLock) {
        this.rollbackConsolidationLock = rollbackConsolidationLock;
    }

    /**
     * 注册 dream 任务 — 对齐 CC DreamTask.ts:52-74 registerDreamTask
     *
     * <p>语义（CC 真源，DreamTask.ts:60-73）：
     * <ol>
     *   <li>{@code generateTaskId('dream')} — 前缀 'd' + 8 base36（Task.ts:86/98-106）</li>
     *   <li>{@code createTaskStateBase(id,'dream','dreaming')} 产出 status:'pending' →
     *       <b>覆盖 status:'running'</b> + phase:'starting' + 注入 sessionsReviewing /
     *       filesTouched:[] / turns:[] / abortController / priorMtime</li>
     *   <li>{@code registerTask}（framework.ts:77 含 SDK task_started）</li>
     * </ol>
     *
     * @param sessionsReviewing 被 consolidation 审阅的 session 数（autoDream.ts:204 sessionIds.length）
     * @param priorMtime        锁 mtime 快照（kill/fail 回退用）
     * @param abortController   fork 的 AbortController（autoDream.ts:203 创建）
     * @return 新任务 id（'d' 前缀）
     */
    public String registerDreamTask(int sessionsReviewing, long priorMtime, AbortController abortController) {
        String id = TaskIdGenerator.generate(TaskType.DREAM);
        long now = System.currentTimeMillis();
        DreamTaskState state = new DreamTaskState(
            id, TaskType.DREAM, BackgroundTaskStatus.RUNNING, "dreaming",
            now, null, false,
            DreamTaskState.DreamPhase.STARTING, sessionsReviewing,
            List.of(), List.of(), abortController, priorMtime);
        store.put(id, state);
        if (taskFrameworkService != null) {
            taskFrameworkService.registerTask(toBackgroundTask(state));
        }
        log.info("DreamTaskRegistry.registerDreamTask: taskId={}, phase={}, sessionsReviewing={}, priorMtime={}, abortController={}",
            id, state.phase(), sessionsReviewing, priorMtime, abortController);
        return id;
    }

    /**
     * 追加一条 dream turn — 对齐 CC DreamTask.ts:76-104 addDreamTurn
     *
     * <p>语义（CC 真源）：
     * <ul>
     *   <li><b>filesTouched 去重</b>：{@code touchedPaths.filter(p => !seen.has(p) && seen.add(p))}
     *       （DreamTask.ts:83-84）——seen 初始为既有 filesTouched，同批内重复 + 跨批重复均滤除</li>
     *   <li><b>no-op 跳过</b>：text 空 && toolUseCount=0 && 无新 touched → 不写 store
     *       （DreamTask.ts:87-93 返回原引用；framework.ts:59-63 跳过 re-render）</li>
     *   <li><b>phase 翻转</b>：仅当 newTouched&gt;0 才 'starting'→'updating'（DreamTask.ts:96）</li>
     *   <li><b>turns 截断</b>：{@code turns.slice(-(MAX_TURNS-1)).concat(turn)}
     *       （DreamTask.ts:101，MAX_TURNS=30）</li>
     * </ul>
     *
     * @param taskId       dream 任务 id
     * @param turn         本条助手 turn（text + 工具计数）
     * @param touchedPaths 本条观察到的 Edit/Write 路径（可为空列表）
     */
    public void addDreamTurn(String taskId, DreamTaskState.DreamTurn turn, List<String> touchedPaths) {
        DreamTaskState existing = store.get(taskId);
        if (existing == null) {
            log.warn("DreamTaskRegistry.addDreamTurn: 任务不存在, 跳过 taskId={}", taskId);
            return;
        }
        List<String> touched = touchedPaths != null ? touchedPaths : List.of();
        // CC DreamTask.ts:83-84: filter(p => !seen.has(p) && seen.add(p))
        Set<String> seen = new HashSet<>(existing.filesTouched());
        List<String> newTouched = new ArrayList<>();
        for (String p : touched) {
            if (!seen.contains(p)) {
                seen.add(p);
                newTouched.add(p);
            }
        }
        boolean emptyTurn = (turn.text() == null || turn.text().isEmpty()) && turn.toolUseCount() == 0;
        if (emptyTurn && newTouched.isEmpty()) {
            // CC DreamTask.ts:87-93: no-op 跳过 —— 纯 no-op 不触发 re-render（framework.ts:59-63）
            if (log.isDebugEnabled()) {
                log.debug("DreamTaskRegistry.addDreamTurn: no-op 跳过 taskId={}（text 空且无新 touched）", taskId);
            }
            return;
        }
        DreamTaskState.DreamPhase newPhase =
            !newTouched.isEmpty() ? DreamTaskState.DreamPhase.UPDATING : existing.phase();
        List<String> newFiles = !newTouched.isEmpty()
            ? concat(existing.filesTouched(), newTouched)
            : existing.filesTouched();
        List<DreamTaskState.DreamTurn> newTurns = truncateTurns(existing.turns(), turn);
        DreamTaskState updated = new DreamTaskState(
            existing.id(), existing.type(), existing.status(), existing.description(),
            existing.startTime(), existing.endTime(), existing.notified(),
            newPhase, existing.sessionsReviewing(), newFiles, newTurns,
            existing.abortController(), existing.priorMtime());
        store.put(taskId, updated);
        log.info("DreamTaskRegistry.addDreamTurn: taskId={}, phase={}, turns={}, touched={}（新 {}）",
            taskId, newPhase, newTurns.size(), newFiles.size(), newTouched.size());
    }

    /**
     * 完成 dream 任务 — 对齐 CC DreamTask.ts:106-120 completeDreamTask
     *
     * <p>语义（CC 真源）：completed + endTime=now + <b>notified:true 立即</b>（dream 无模型通知
     * 路径，仅 UI；eviction 要求 terminal+notified，:110-112）+ abortController 清空
     * （:118）。不发射 task_terminated SDK 事件（CC 同，无 emitTaskTerminatedSdk 调用）。
     *
     * @param taskId dream 任务 id
     */
    public void completeDreamTask(String taskId) {
        DreamTaskState existing = store.get(taskId);
        if (existing == null) {
            log.warn("DreamTaskRegistry.completeDreamTask: 任务不存在, 跳过 taskId={}", taskId);
            return;
        }
        long now = System.currentTimeMillis();
        DreamTaskState updated = withTerminal(existing, BackgroundTaskStatus.COMPLETED, now);
        store.put(taskId, updated);
        if (taskFrameworkService != null) {
            taskFrameworkService.updateTaskState(taskId, toBackgroundTask(updated));
        }
        log.info("DreamTaskRegistry.completeDreamTask: taskId={}, status=completed, endTime={}, notified=true, abortController 清空",
            taskId, now);
    }

    /**
     * 失败 dream 任务 — 对齐 CC DreamTask.ts:122-130 failDreamTask
     *
     * <p>语义（CC 真源）：failed + endTime=now + notified:true + abortController 清空（:127）。
     * 不发射 task_terminated SDK 事件（CC 同）。
     *
     * @param taskId dream 任务 id
     */
    public void failDreamTask(String taskId) {
        DreamTaskState existing = store.get(taskId);
        if (existing == null) {
            log.warn("DreamTaskRegistry.failDreamTask: 任务不存在, 跳过 taskId={}", taskId);
            return;
        }
        long now = System.currentTimeMillis();
        DreamTaskState updated = withTerminal(existing, BackgroundTaskStatus.FAILED, now);
        store.put(taskId, updated);
        if (taskFrameworkService != null) {
            taskFrameworkService.updateTaskState(taskId, toBackgroundTask(updated));
        }
        log.info("DreamTaskRegistry.failDreamTask: taskId={}, status=failed, endTime={}, notified=true, abortController 清空",
            taskId, now);
    }

    /**
     * kill dream 任务 — 对齐 CC DreamTask.ts:132-156 DreamTask.kill
     *
     * <p>语义（CC 真源）：
     * <ol>
     *   <li><b>仅 running</b>：{@code if (task.status !== 'running') return task}（:139）——
     *       已终态 no-op，不 abort 不 rollback（CC priorMtime 保持 undefined → 跳过 :153-155）</li>
     *   <li>{@code task.abortController?.abort()}（:140）—— 中止 fork</li>
     *   <li>捕获 {@code priorMtime}（:141）→ killed + endTime + notified:true +
     *       abortController 清空（:142-148）</li>
     *   <li>回退锁 mtime：{@code if (priorMtime !== undefined) await rollbackConsolidationLock(priorMtime)}
     *       （:153-155）——同 fork 失败路径，让下轮时间门可重试</li>
     * </ol>
     *
     * <p><b>Java 防御（非 CC 差异）</b>：abort 由 TaskStop 分发触发；本方法回退锁后，调用方
     * （AutoDreamConsolidator catch）据 {@link #isKilled} 判定不双回滚（autoDream.ts:262-265）。
     *
     * @param taskId dream 任务 id
     * @return true 实际 kill（running→killed + abort + rollback）；false 任务不存在或非 running
     */
    public boolean kill(String taskId) {
        DreamTaskState existing = store.get(taskId);
        if (existing == null) {
            log.warn("DreamTaskRegistry.kill: 任务不存在, 跳过 taskId={}", taskId);
            return false;
        }
        if (existing.status() != BackgroundTaskStatus.RUNNING) {
            if (log.isDebugEnabled()) {
                log.debug("DreamTaskRegistry.kill: task {} status={} 非 running, no-op（CC :139）",
                    taskId, existing.status().getStatusString());
            }
            return false;
        }
        long priorMtime = existing.priorMtime();
        AbortController ac = existing.abortController();
        if (ac != null) {
            ac.abort();
        }
        long now = System.currentTimeMillis();
        DreamTaskState killed = withTerminal(existing, BackgroundTaskStatus.KILLED, now);
        store.put(taskId, killed);
        if (taskFrameworkService != null) {
            taskFrameworkService.updateTaskState(taskId, toBackgroundTask(killed));
        }
        log.info("DreamTaskRegistry.kill: taskId={}, status=killed, endTime={}, priorMtime={}, abortController 已 abort + 清空",
            taskId, now, priorMtime);
        Consumer<Long> rollback = rollbackConsolidationLock;
        if (rollback != null) {
            rollback.accept(priorMtime);
        }
        return true;
    }

    /** 是否已被 kill（status=killed）· AutoDreamConsolidator catch 判定 kill 是否已回滚锁。 */
    public boolean isKilled(String taskId) {
        DreamTaskState s = store.get(taskId);
        return s != null && s.status() == BackgroundTaskStatus.KILLED;
    }

    /**
     * 构造终态副本 · CC original: complete/fail/kill 的 {@code {...task, status, endTime,
     * notified: true, abortController: undefined}}（DreamTask.ts:113-119/123-129/142-148）。
     * 保留 phase/filesTouched/turns/priorMtime（CC spread 保留）。
     */
    private static DreamTaskState withTerminal(DreamTaskState s, BackgroundTaskStatus status, long endTime) {
        return new DreamTaskState(
            s.id(), s.type(), status, s.description(),
            s.startTime(), endTime, true,
            s.phase(), s.sessionsReviewing(), s.filesTouched(), s.turns(),
            null, s.priorMtime());
    }

    /** 取 dream 富状态（测试/观测用） */
    public Optional<DreamTaskState> getDreamTask(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    /** 列出全部 dream 富状态（测试/观测用）。 */
    public List<DreamTaskState> listAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * 构造统一 store 用的 BackgroundTask · 对齐 CC createTaskStateBase（Task.ts:108-126）。
     *
     * <p><b>register 路径</b>：status=RUNNING（registerDreamTask 覆盖 pending）、toolUseId 缺省、
     * startTime=Date.now()、notified=false。dream 无输出文件 → outputFile 留空
     * （getTaskOutputDelta 对空 outputFile 返回空增量，不产生读）。
     *
     * <p><b>终态路径</b>（complete/fail/kill）：status=终态 + endTime + notified=true —— 统一 store
     * 同步终态，使 evictTerminalTask / 惰性 GC 可回收（framework.ts:124-147）。
     */
    private BackgroundTask toBackgroundTask(DreamTaskState s) {
        return new BackgroundTask(
            s.id(), TaskType.DREAM, s.status(), s.description(),
            null, s.startTime(), s.endTime(), null, "", 0L, s.notified(), null, true);
    }

    /** 追加去重（不修改入参列表） */
    private static List<String> concat(List<String> base, List<String> extra) {
        List<String> result = new ArrayList<>(base.size() + extra.size());
        result.addAll(base);
        result.addAll(extra);
        return result;
    }

    /** CC DreamTask.ts:101 turns.slice(-(MAX_TURNS-1)).concat(turn) */
    private static List<DreamTaskState.DreamTurn> truncateTurns(
            List<DreamTaskState.DreamTurn> turns, DreamTaskState.DreamTurn turn) {
        int from = Math.max(0, turns.size() - (MAX_TURNS - 1));
        List<DreamTaskState.DreamTurn> kept = turns.subList(from, turns.size());
        List<DreamTaskState.DreamTurn> result = new ArrayList<>(kept.size() + 1);
        result.addAll(kept);
        result.add(turn);
        return result;
    }
}

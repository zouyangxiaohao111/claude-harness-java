package com.nexusai.application.agent.workflow.progress;

import com.nexusai.application.agent.workflow.ProgressBus;
import com.nexusai.application.agent.workflow.ProgressEvent;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultOk;
import com.nexusai.application.agent.workflow.AgentRunResultSkipped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 从 bus 事件归约出 RunProgress 的响应式 store · CC original: {@code createProgressStoreFromBus}
 * (Open-ClaudeCode/src/workflow/progress/store.ts:52-200)。
 *
 * <p>订阅 {@link ProgressBus}，把 {@code ProgressEvent} 8 变体归约进 {@code byId} Map，
 * 维护最新快照 {@code list()}（按 updatedAt 降序，store.ts:57-60）；{@code subscribe} 供
 * useSyncExternalStore/面板刷新。W-1e WorkflowService 经 {@code listRuns()/getRun()} 暴露。
 *
 * <p><b>store 归约边界</b>（store.ts:82-181）：
 * <ul>
 *   <li>{@code log} 显式 early-exit 忽略（面板无 log 视图，避免无谓 snapshot 重建），bus 上仍广播。</li>
 *   <li>agent_started/agent_done 用引擎盖章的 {@code id} 精确 upsert（修旧 LIFO 竞态）。</li>
 *   <li>agent_done ok 分支补 outputShape（output 为对象→'object' 否则 'text'）/tokenCount/toolCount/model；
 *       dead/skipped 只留 resultKind。</li>
 *   <li>run_done 覆盖终态 + returnValue/error。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap} 存储 + 快照不可变 List（后台 agent 并发 emit，
 * Java 侧须安全发布——CC 单线程无此约束）。{@link #hydrate} 跳过已存在 runId（内存优先，
 * 对齐 store.ts:189-193）。
 *
 * <p>Spring 单例：构造即订阅 bus（对齐 CC createProgressStoreFromBus 立即订阅）。P2 W-3a 可复用。
 */
@Component
public final class ProgressStore {

    private static final Logger log = LoggerFactory.getLogger(ProgressStore.class);

    /** runId → RunProgress · CC original: {@code byId} (store.ts:53)。 */
    private final Map<String, RunProgress> byId = new ConcurrentHashMap<>();

    /** 最新快照（不可变 List，按 updatedAt 降序）· CC original: {@code snapshot} (store.ts:54)。 */
    private volatile List<RunProgress> snapshot = List.of();

    /** React 订阅者（useSyncExternalStore）· CC original: {@code listeners} (store.ts:55)。 */
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    /**
     * 构造并订阅 bus · CC original: createProgressStoreFromBus (store.ts:52)。
     *
     * @param bus 进度总线（Spring 注入同一实例给 telemetry 共享）
     */
    public ProgressStore(ProgressBus bus) {
        if (bus != null) {
            bus.subscribe(this::apply);
        }
    }

    /**
     * 归约一条事件 · CC original: {@code apply} (store.ts:82-181)。
     *
     * @param event 进度事件（8 变体；log 显式忽略）
     */
    public void apply(ProgressEvent event) {
        if (event == null) {
            return;
        }
        // log 不产生可见状态变化（面板无 log 视图）：early-exit 避免无谓 snapshot 重建（store.ts:83-84）
        if (event instanceof ProgressEvent.WorkflowLog) {
            return;
        }
        String runId = event.runId();
        String workflowName = workflowNameOf(event);
        RunProgress current = byId.get(runId);
        long now = System.currentTimeMillis();
        RunProgress updated = switch (event) {
            case ProgressEvent.RunStarted e -> applyRunStarted(current, runId, workflowName, e, now);
            case ProgressEvent.PhaseStarted e -> applyPhaseStarted(current, runId, workflowName, e, now);
            case ProgressEvent.PhaseDone e -> applyPhaseDone(current, runId, workflowName, e, now);
            case ProgressEvent.AgentStarted e -> applyAgentStarted(current, runId, workflowName, e, now);
            case ProgressEvent.AgentProgress e -> applyAgentProgress(current, runId, workflowName, e, now);
            case ProgressEvent.AgentDone e -> applyAgentDone(current, runId, workflowName, e, now);
            case ProgressEvent.RunDone e -> applyRunDone(current, runId, workflowName, e, now);
            case ProgressEvent.WorkflowLog e -> current; // 已在上面 early-exit，理论不可达
        };
        byId.put(runId, updated);
        notifyListeners();
        if (log.isDebugEnabled()) {
            log.debug("ProgressStore.apply：runId={} event={} status={}（对齐 CC store.ts:82-181）",
                    runId, event.getClass().getSimpleName(), updated.status());
        }
    }

    /** 当前所有 run（按 updatedAt 降序）· CC original: {@code list()} (store.ts:186)。 */
    public List<RunProgress> list() {
        return snapshot;
    }

    /** 按 runId 取 · CC original: {@code get(id)} (store.ts:187)。 */
    public RunProgress get(String runId) {
        return runId == null ? null : byId.get(runId);
    }

    /**
     * 直接注入磁盘读到的 run（绕过 bus）· CC original: {@code hydrate} (store.ts:189-193)。
     * 跳过已存在 runId（内存优先）。
     *
     * @param run 从磁盘读到的 RunProgress
     */
    public void hydrate(RunProgress run) {
        if (run == null || byId.containsKey(run.runId())) {
            return;
        }
        byId.put(run.runId(), run);
        notifyListeners();
    }

    /**
     * 订阅快照变更 · CC original: {@code subscribe(fn)} (store.ts:194-197)。
     *
     * @param listener 变更通知
     * @return 退订 Runnable
     */
    public Runnable subscribe(Runnable listener) {
        if (listener == null) {
            return () -> {
            };
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 当前快照（useSyncExternalStore 用）· CC original: {@code getSnapshot()} (store.ts:198)。 */
    public List<RunProgress> getSnapshot() {
        return snapshot;
    }

    // ────────────────────────────── 归约实现（store.ts:82-181）──────────────────────────────

    private static RunProgress applyRunStarted(RunProgress current, String runId, String workflowName,
                                               ProgressEvent.RunStarted e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<String> declared = new ArrayList<>();
        if (e.meta() != null && e.meta().phases() != null) {
            for (var ph : e.meta().phases()) {
                if (ph != null && ph.title() != null) {
                    declared.add(ph.title());
                }
            }
        }
        return p.toBuilder()
                .workflowName(e.workflowName())
                .status(RunProgress.Status.RUNNING)
                .declaredPhases(declared)
                .description(e.meta() != null ? e.meta().description() : null)
                .updatedAt(now)
                .build();
    }

    private static RunProgress applyPhaseStarted(RunProgress current, String runId, String workflowName,
                                                 ProgressEvent.PhaseStarted e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<RunProgress.Phase> phases = new ArrayList<>(p.phases());
        if (phases.stream().noneMatch(ph -> ph.title().equals(e.phase()))) {
            phases.add(new RunProgress.Phase(e.phase(), RunProgress.PhaseState.RUNNING));
        }
        return p.toBuilder().phases(phases).currentPhase(e.phase()).updatedAt(now).build();
    }

    private static RunProgress applyPhaseDone(RunProgress current, String runId, String workflowName,
                                              ProgressEvent.PhaseDone e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<RunProgress.Phase> phases = new ArrayList<>();
        for (RunProgress.Phase ph : p.phases()) {
            phases.add(ph.title().equals(e.phase())
                    ? new RunProgress.Phase(ph.title(), RunProgress.PhaseState.DONE)
                    : ph);
        }
        String currentPhase = e.phase().equals(p.currentPhase()) ? null : p.currentPhase();
        return p.toBuilder().phases(phases).currentPhase(currentPhase).updatedAt(now).build();
    }

    private static RunProgress applyAgentStarted(RunProgress current, String runId, String workflowName,
                                                 ProgressEvent.AgentStarted e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<AgentProgress> agents = new ArrayList<>(p.agents());
        int idx = indexOfAgent(agents, e.agentId());
        if (idx < 0) {
            agents.add(new AgentProgress(e.agentId(), e.label(), e.phase(),
                    AgentProgress.Status.RUNNING, null, null, null, null, null));
        } else {
            AgentProgress a = agents.get(idx);
            agents.set(idx, new AgentProgress(a.id(), e.label(), e.phase(),
                    AgentProgress.Status.RUNNING, a.resultKind(), a.outputShape(), a.model(),
                    a.tokenCount(), a.toolCount()));
        }
        return p.toBuilder().agents(agents).agentCount(agents.size()).updatedAt(now).build();
    }

    private static RunProgress applyAgentProgress(RunProgress current, String runId, String workflowName,
                                                  ProgressEvent.AgentProgress e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<AgentProgress> agents = new ArrayList<>(p.agents());
        int idx = indexOfAgent(agents, e.agentId());
        if (idx >= 0) {
            AgentProgress a = agents.get(idx);
            agents.set(idx, new AgentProgress(a.id(), a.label(), a.phase(), a.status(),
                    a.resultKind(), a.outputShape(), a.model(), e.tokenCount(), e.toolCount()));
        }
        return p.toBuilder().agents(agents).updatedAt(now).build();
    }

    private static RunProgress applyAgentDone(RunProgress current, String runId, String workflowName,
                                              ProgressEvent.AgentDone e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        List<AgentProgress> agents = new ArrayList<>(p.agents());
        int idx = indexOfAgent(agents, e.agentId());
        AgentRunResult r = e.result();
        String resultKind = kindOf(r);
        String outputShape = null;
        Integer tokenCount = null;
        Integer toolCount = null;
        String model = null;
        if (r instanceof AgentRunResultOk ok) {
            Object out = ok.output();
            outputShape = (out instanceof java.util.Map || out instanceof com.fasterxml.jackson.databind.JsonNode)
                    ? "object" : "text";
            tokenCount = ok.tokenCount();
            toolCount = ok.toolCount();
            model = ok.model();
        }
        if (idx < 0) {
            agents.add(new AgentProgress(e.agentId(), e.label(), e.phase(),
                    AgentProgress.Status.DONE, resultKind, outputShape, model, tokenCount, toolCount));
        } else {
            AgentProgress a = agents.get(idx);
            agents.set(idx, new AgentProgress(a.id(), a.label(), a.phase(),
                    AgentProgress.Status.DONE, resultKind, outputShape, model, tokenCount, toolCount));
        }
        return p.toBuilder().agents(agents).agentCount(agents.size()).updatedAt(now).build();
    }

    private static RunProgress applyRunDone(RunProgress current, String runId, String workflowName,
                                            ProgressEvent.RunDone e, long now) {
        RunProgress p = ensure(current, runId, workflowName, now);
        RunProgress.Status status = switch (e.status()) {
            case COMPLETED -> RunProgress.Status.COMPLETED;
            case FAILED -> RunProgress.Status.FAILED;
            case KILLED -> RunProgress.Status.KILLED;
        };
        return p.toBuilder()
                .status(status)
                .returnValue(e.returnValue())
                .error(e.error())
                .updatedAt(now)
                .build();
    }

    /** 无记录则初始化（对齐 store.ts:62-80 ensure）。 */
    private static RunProgress ensure(RunProgress current, String runId, String workflowName, long now) {
        if (current != null) {
            return current;
        }
        return RunProgress.builder()
                .runId(runId)
                .workflowName(workflowName)
                .status(RunProgress.Status.RUNNING)
                .phases(List.of())
                .declaredPhases(List.of())
                .currentPhase(null)
                .agents(List.of())
                .agentCount(0)
                .startedAt(now)
                .updatedAt(now)
                .build();
    }

    /** 事件携带的 workflowName · CC original: store.ts:86-88 {@code 'workflowName' in event ? event.workflowName : 'workflow'}。 */
    private static String workflowNameOf(ProgressEvent event) {
        return event instanceof ProgressEvent.RunStarted rs
                ? rs.workflowName()
                : "workflow";
    }

    private static int indexOfAgent(List<AgentProgress> agents, int agentId) {
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).id() == agentId) {
                return i;
            }
        }
        return -1;
    }

    /** agent 结果类型 · CC original: {@code result.kind}（ok/skipped/dead）。 */
    private static String kindOf(AgentRunResult r) {
        if (r instanceof AgentRunResultOk) {
            return "ok";
        }
        if (r instanceof AgentRunResultSkipped) {
            return "skipped";
        }
        return "dead";
    }

    private void notifyListeners() {
        List<RunProgress> next = new ArrayList<>(byId.values());
        next.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        snapshot = List.copyOf(next);
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception ex) {
                log.warn("ProgressStore 订阅者异常，不阻断其余订阅者: {}", ex.toString());
            }
        }
    }
}

package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.JournalEntry;
import com.nexusai.application.agent.workflow.WorkflowPorts;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 workflow run 的执行上下文 · 对齐 CC {@code engine/context.ts:20-32 EngineContext}。
 *
 * <p>可变类（非 record）：journal/journalIndex/journalInvalidated/currentPhase 随执行推进变化。
 * resources 为 {@link SharedResources}（子 workflow 按引用共享）。</p>
 *
 * <p>W-1c 支撑集：依赖 W-1d 的 {@link WorkflowPorts}（progressEmitter/taskRegistrar/journalStore/
 * agentAdapterRegistry/logger 等）。</p>
 */
public final class EngineContext {

    private final WorkflowPorts ports;
    private final HostHandle host;
    /** 取消信号（CC AbortSignal 语义，Java 复用 AbortController）。 */
    private final AbortController signal;
    private final String runId;
    private final String workflowName;
    private final String cwd;
    private final SharedResources resources;
    /** CC original: journal (context.ts:26) — 当前 journal（resume 命中/append 就地变更）。 */
    private List<JournalEntry> journal;
    /** CC original: journalIndex (context.ts:27) — 下一条待校验的 journal 索引。 */
    private int journalIndex;
    /** CC original: journalInvalidated (context.ts:28) — scriptChanged 或 key 分叉后置 true。 */
    private boolean journalInvalidated;
    /** CC original: currentPhase (context.ts:29) — 当前阶段（null=未进入阶段）。 */
    private String currentPhase;

    private EngineContext(WorkflowPorts ports, HostHandle host, AbortController signal, String runId,
                          String workflowName, String cwd, SharedResources resources, List<JournalEntry> journal) {
        this.ports = ports;
        this.host = host;
        this.signal = signal;
        this.runId = runId;
        this.workflowName = workflowName;
        this.cwd = cwd;
        this.resources = resources;
        this.journal = new ArrayList<>(journal);
    }

    /** 工厂 · 对齐 CC createEngineContext (context.ts:47-73)。 */
    public static EngineContext create(WorkflowPorts ports, HostHandle host, AbortController signal,
                                       String runId, String workflowName, String cwd,
                                       Integer budgetTotal, Integer maxConcurrency, List<JournalEntry> journal) {
        SharedResources resources = new SharedResources(budgetTotal, maxConcurrency);
        return new EngineContext(ports, host, signal, runId, workflowName, cwd, resources, journal);
    }

    public WorkflowPorts ports() {
        return ports;
    }

    public HostHandle host() {
        return host;
    }

    public AbortController signal() {
        return signal;
    }

    public String runId() {
        return runId;
    }

    public String workflowName() {
        return workflowName;
    }

    public String cwd() {
        return cwd;
    }

    public SharedResources resources() {
        return resources;
    }

    public List<JournalEntry> journal() {
        return journal;
    }

    public void setJournal(List<JournalEntry> journal) {
        this.journal = journal;
    }

    public int journalIndex() {
        return journalIndex;
    }

    public void setJournalIndex(int journalIndex) {
        this.journalIndex = journalIndex;
    }

    public boolean journalInvalidated() {
        return journalInvalidated;
    }

    public void setJournalInvalidated(boolean journalInvalidated) {
        this.journalInvalidated = journalInvalidated;
    }

    public String currentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }
}

package com.nexusai.application.agent.workflow.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子 workflow 共享的资源 · 对齐 CC {@code engine/context.ts:10-17 SharedResources}。
 *
 * <p>嵌套时 semaphore/budget/agentCountBox/agentIdSeq <b>按引用共享</b>，depth 临时 +1。
 * {@code agentCountBox}/{@code agentIdSeq} 用 {@link AtomicInteger}（跨线程计数 + 子 workflow
 * 按引用共享语义，P0-plan §4.4）。</p>
 */
public final class SharedResources {

    private final Semaphore semaphore;
    private final Budget budget;

    /** CC original: agentCountBox (context.ts:12) — 当前 in-flight agent 数，MAX_TOTAL_AGENTS 闸。 */
    private final AtomicInteger agentCountBox = new AtomicInteger();

    /** CC original: agentIdSeq (context.ts:14) — agent() 调用唯一序号，精确关联 started/done。 */
    private final AtomicInteger agentIdSeq = new AtomicInteger();

    /** CC original: depth (context.ts:15) — 子 workflow 嵌套深度（仅允许一层）。 */
    private int depth;

    public SharedResources(Integer budgetTotal, Integer maxConcurrency) {
        this.semaphore = new Semaphore(Semaphore.clampMaxConcurrency(maxConcurrency));
        this.budget = new Budget(budgetTotal);
    }

    public Semaphore semaphore() {
        return semaphore;
    }

    public Budget budget() {
        return budget;
    }

    public AtomicInteger agentCountBox() {
        return agentCountBox;
    }

    public AtomicInteger agentIdSeq() {
        return agentIdSeq;
    }

    public int depth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }
}

package com.nexusai.application.agent.workflow;

/**
 * 进度事件发射器 · CC original: {@code ProgressEmitter}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:48-50)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「telemetry/进度总线」。引擎 emit → {@link ProgressBus}
 * 广播给所有订阅者（store / telemetry）。
 *
 * @see ProgressBus
 */
public interface ProgressEmitter {

    /**
     * 发射一条进度事件 · CC original: {@code emit(event): void} (ports.ts:49)。
     *
     * @param event CC original: {@code event} — ProgressEvent 8 变体（均带 runId）
     */
    void emit(ProgressEvent event);
}

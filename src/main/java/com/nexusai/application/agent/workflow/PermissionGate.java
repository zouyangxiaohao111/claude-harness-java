package com.nexusai.application.agent.workflow;

/**
 * 取消/权限门 · CC original: {@code PermissionGate}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:99-101)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「权限门」。CC 核心层实现恒返回 false
 * （{@code isAborted: () => false}，引擎用 ctx.signal 检查 abort，src/workflow/ports.ts:195）。
 */
public interface PermissionGate {

    /**
     * host 是否被 abort · CC original: {@code isAborted(host): boolean} (ports.ts:100)。
     * P0 恒返回 false（引擎侧 abort 走 ctx.signal）。
     *
     * @param host CC original: {@code host} — 不透明 HostHandle
     * @return 是否已 abort（P0 恒 false）
     */
    boolean isAborted(HostHandle host);
}

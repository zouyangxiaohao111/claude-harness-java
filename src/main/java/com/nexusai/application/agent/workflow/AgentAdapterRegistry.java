package com.nexusai.application.agent.workflow;

/**
 * 多后端 agent adapter 注册表（W-1d 端口层抽象）· CC original: {@code AgentAdapterRegistry}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:94-155)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一。CC 中 {code agentAdapterRegistry} 在 {@code WorkflowPorts}
 * 上是可选字段（ports.ts:139-142），核心层始终提供：{@code buildRegistry()}（src/workflow/registry.ts:9-13）。
 *
 * <p><b>P0 边界</b>：W-1d 只定义端口层抽象 + {@code WorkflowPortsImpl} 返回<b>空表</b>
 * （P0-plan §6.2 空注册表，P1 W-2a 注入 {@code ClaudeCodeBackendAdapter} 并 {@code .default('claude-code')}）。
 * 完整实现（register/default/route/resolve/initializeAll/disposeAll + AdapterRouteRule/AdapterNotFoundError）
 * 属 W-1e（agentAdapter.ts:94-155）。本接口为引擎 hooks.agent 所需的最小契约。
 */
public interface AgentAdapterRegistry {

    /**
     * 是否已注册指定 id 的 adapter · CC original: {@code has(id)} (agentAdapter.ts:145)。
     *
     * @param id CC original: {@code id} — adapter 标识
     * @return 是否已注册
     */
    boolean has(String id);

    /**
     * 按 AgentRunParams 解析 adapter · CC original: {@code resolve(params)}
     * (agentAdapter.ts:147-151)：按插入序 matchRule → default → 无 → AdapterNotFoundError。
     *
     * <p>P0 空表恒抛（fail-closed）。返回类型在 W-1e 收敛为 {@code AgentAdapter}；
     * 此处以 Object 承载端口层签名，避免 W-1d 引入 W-1e 的 AgentAdapter 类型。
     *
     * @param params CC original: {@code params} — AgentRunParams（agentType/model 路由依据）
     * @return 解析到的 adapter（P0 空表恒抛 IllegalStateException）
     * @throws IllegalStateException P0 空表无 adapter 时 fail-loud（DEC-P0-04 语义）
     */
    Object resolve(AgentRunParams params);
}

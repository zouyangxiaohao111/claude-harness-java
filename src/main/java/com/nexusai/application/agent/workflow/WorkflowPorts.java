package com.nexusai.application.agent.workflow;

/**
 * 全部 ports 聚合 · 对齐 CC {@code engine/ports.ts:136-149 WorkflowPorts}（8 项）。
 *
 * <p><b>W-1c 自包含编译声明</b>：本接口由 W-1c（Execute-W1c）为引擎自包含编译/单测创建，
 * 覆盖引擎 hooks 运行期读到的 7 项（progressEmitter/taskRegistrar/journalStore/agentAdapterRegistry/
 * logger/permissionGate/agentRunner + host）。{@code agentAdapterRegistry()} 返回
 * {@link AgentAdapterRegistry}（W-1d 端口层接口，{@code Object resolve}）；{@code hostFactory} 为
 * W-1d/W-1e 域（makeHostFactory 装配），W-1c 引擎不使用，故以 default 方法占位（fail-loud 抛错）。</p>
 *
 * <p>实现契约：{@link WorkflowPortsImpl} 返回 7 项 + hostFactory（W-1d 装配）。</p>
 */
public interface WorkflowPorts {

    /** CC original: agentRunner (ports.ts:137) — registry 缺省时的回落后端。 */
    AgentRunner agentRunner();

    /** CC original: agentAdapterRegistry? (ports.ts:139) — 多后端 adapter 注册表（P0 fail-fast 必设）。 */
    AgentAdapterRegistry agentAdapterRegistry();

    /** CC original: progressEmitter (ports.ts:141) — 进度事件总线。 */
    ProgressEmitter progressEmitter();

    /** CC original: taskRegistrar (ports.ts:142) — 后台任务生命周期。 */
    TaskRegistrar taskRegistrar();

    /** CC original: journalStore (ports.ts:143) — journal 持久化。 */
    JournalStore journalStore();

    /** CC original: permissionGate (ports.ts:144) — 取消/权限闸。 */
    PermissionGate permissionGate();

    /** CC original: logger (ports.ts:145) — 日志 + telemetry。 */
    WorkflowLogger logger();

    /** CC original: hostFactory (ports.ts:146) — W-1d/W-1e 域；W-1c 引擎不使用。 */
    default HostFactory hostFactory() {
        throw new UnsupportedOperationException(
                "hostFactory 未装配：W-1d/W-1e 域（makeHostFactory），W-1c 引擎不使用。");
    }
}

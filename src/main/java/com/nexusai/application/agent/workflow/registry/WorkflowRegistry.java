package com.nexusai.application.agent.workflow.registry;

import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry;
import com.nexusai.application.agent.workflow.agent.ClaudeCodeBackendAdapter;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 多后端 adapter 注册表构建器 · CC original: {@code buildRegistry}
 * (Open-ClaudeCode/src/workflow/registry.ts:9-13)。
 *
 * <pre>{@code
 * export function buildRegistry(): AgentAdapterRegistry {
 *   const reg = new AgentAdapterRegistry()
 *   reg.register(claudeCodeBackend).default('claude-code')
 *   return reg
 * }
 * }</pre>
 *
 * <p><b>W-2a 边界</b>：v1 只注册单个 {@code claude-code} adapter 为 default，不预填路由规则
 * （扩展第二 provider 时再 {@code .route(...)}）。{@link ClaudeCodeBackendAdapter}（委托现有
 * runAgent/subagent 编排）在 W-2a 注入——P0 曾为空表（DEC-P0-06：feature 默认关）。
 *
 * <p>数据流：本类是 ports 装配的一部分——{@code WorkflowPortsImpl} 注入
 * {@link SubagentExecutor}（Spring @Bean）后经本方法产出 {@code agentAdapterRegistry()} 端口
 * （resolve 恒命中 claude-code default，不再抛 AdapterNotFoundError）。
 */
public final class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private WorkflowRegistry() {
    }

    /**
     * 构建 adapter 注册表 · CC original: registry.ts:9-13。
     *
     * @param subagentExecutor runAgent 委托（Spring @Bean，注入 ClaudeCodeBackendAdapter）
     * @param worktreeManager  worktree 隔离 manager（D-1 接线 · Spring @Component，注入
     *                         ClaudeCodeBackendAdapter；isolation:'worktree' fail-closed 建树）
     * @return 注册 claude-code 为 default 的注册表
     */
    public static AgentAdapterRegistry buildRegistry(SubagentExecutor subagentExecutor,
                                                     AgentWorktreeManager worktreeManager) {
        // [Fix-G1] 生产回落注册：把 WORKFLOW_AGENT（agentType='workflow-worker'）注册进
        //   SubagentExecutor.additionalAgentDefinitions。E2E G-1：此前测试手动注册才打通，
        //   生产回落路径 resolveAgentDefinition('workflow-worker') 返回 null →
        //   SubagentExecutor.executeStreaming 抛 AgentNotFoundException（SubagentExecutor:1293）。
        //   对齐 CC：claudeCodeBackend.ts:36-44 WORKFLOW_AGENT 由 resolveAgentDefinition 回落返回，
        //   runAgent 直接持有该定义（runAgent.ts:703 agentType: agentDefinition.agentType）；
        //   Java executeStreaming Step 1 二次按 agentType 解析（SubagentExecutor:1267），
        //   注册后二次解析命中 = CC「WORKFLOW_AGENT 定义可达」语义等价。
        //   安全边界：SubagentExecutor @Bean（ToolRegistrationConfig.subagentExecutor）不预置
        //   additionalAgentDefinitions（默认 Map.of()），SubagentTool 均用 fresh executor，
        //   本处为唯一写入点，setAdditionalAgentDefinitions REPLACE 不覆盖其他注册。
        subagentExecutor.setAdditionalAgentDefinitions(Map.of(
                ClaudeCodeBackendAdapter.WORKFLOW_AGENT.agentType(),
                ClaudeCodeBackendAdapter.WORKFLOW_AGENT));
        if (log.isInfoEnabled()) {
            log.info("WorkflowRegistry.buildRegistry：注册 workflow-worker（WORKFLOW_AGENT）到 SubagentExecutor "
                    + "additionalAgentDefinitions，生产回落路径 resolveAgentDefinition('workflow-worker') 命中"
                    + "（Fix-G1，对齐 CC claudeCodeBackend.ts:36-44）");
        }
        AgentAdapterRegistry reg = new AgentAdapterRegistry();
        reg.register(new ClaudeCodeBackendAdapter(subagentExecutor, worktreeManager))
                .defaultAdapter("claude-code");
        if (log.isDebugEnabled()) {
            log.debug("WorkflowRegistry.buildRegistry：注册 claude-code adapter 为 default（对齐 registry.ts:9-13）");
        }
        return reg;
    }
}

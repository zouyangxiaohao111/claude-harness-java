package com.nexusai.application.agent.team;

import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.infra.util.AgentIdFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * teammate 身份 sysprop 启动接线 · 对齐 CC main.tsx CLI 参数注入 dynamicTeamContext。
 *
 * <p>CC 真源（grep 自验，不信注释）：
 * <ul>
 *   <li>CC teammate.ts:44-51 {@code dynamicTeamContext} 由 CLI 参数（{@code --agent-id}/{@code --agent-name}
 *       /{@code --team-name}）在 main.tsx:1203 由 {@code setDynamicTeamContext} 填充；</li>
 *   <li>CC 身份解析（{@code isTeammate} teammate.ts:125-131）只查 in-process（AsyncLocalStorage）
 *       与 dynamicTeamContext，<b>无 env/sysprop 逐次回退</b>——dynamicTeamContext 在启动阶段一次性
 *       填充，运行期身份判断不再重复读环境。</li>
 * </ul>
 *
 * <p>Java 无 CLI，部署侧以 sysprop 代理 CLI 参数（{@code nexusai.agent.name}/{@code nexusai.team.name}/
 * {@code nexusai.agent.color}，见 {@link TaskSystemConfig}）。本类在 Spring 启动阶段读这些 sysprop，
 * 一次性调用 {@link Teammate#setDynamicTeamContext}，使 {@link Teammate#isTeammate()} 等身份方法
 * 运行期不再逐次读 sysprop（对齐 CC 启动注入语义）。
 *
 * <p><b>字段映射</b>（CC CLI → Java sysprop → DynamicTeamContext）：
 * <ul>
 *   <li>{@code --agent-id}（CC :46）→ 无对应 sysprop，经 {@link AgentIdFormatter#formatAgentId} 由
 *       {@code agentName@teamName} 派生（CC formatAgentId agentId.ts:38 确定性 ID）；</li>
 *   <li>{@code --agent-name}（CC :47）→ {@code nexusai.agent.name}；</li>
 *   <li>{@code --team-name}（CC :48）→ {@code nexusai.team.name}；</li>
 *   <li>{@code --color}（CC :49，可选）→ {@code nexusai.agent.color}；</li>
 *   <li>{@code --plan-mode-required}（CC :50）→ 无对应 sysprop，缺省 false（teammate 未要求 plan 模式）。</li>
 * </ul>
 *
 * <p><b>注册模式</b>：{@code @Component} + {@code @PostConstruct}（对齐 {@code PluginStartupAssembler}/
 * {@code SessionMemoryService} 先例）。仅依赖静态工具类 {@link TaskSystemConfig}/{@link Teammate}，
 * 无 bean 依赖时序问题，故不需 {@code ApplicationRunner}。
 */
@Component
public class TeammateContextBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TeammateContextBootstrap.class);

    /**
     * Spring 启动阶段读 sysprop → 一次性填充 {@link Teammate} dynamicTeamContext。
     *
     * <p>agentName + teamName 均非空 → 判定本进程为 teammate 部署（CC tmux teammate 等价），
     * 派生 agentId 并设置动态上下文；否则视为 standalone 会话，跳过（dynamicTeamContext 保持 null）。
     */
    @PostConstruct
    public void init() {
        String agentName = TaskSystemConfig.getAgentName();
        String teamName = TaskSystemConfig.getTeamName();
        if (agentName == null || agentName.isBlank() || teamName == null || teamName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeammateContextBootstrap] 未检测到 sysprop nexusai.agent.name/nexusai.team.name，"
                        + "跳过 dynamicTeamContext 初始化（standalone 会话，对齐 CC 无 CLI 参数注入）");
            }
            return;
        }
        String agentId = AgentIdFormatter.formatAgentId(agentName, teamName);
        String color = TaskSystemConfig.getTeammateColor();
        Teammate.setDynamicTeamContext(new Teammate.DynamicTeamContext(
                agentId, agentName, teamName, color, false, null));
        if (log.isInfoEnabled()) {
            log.info("[TeammateContextBootstrap] sysprop 启动接线：dynamicTeamContext 初始化 agentId={} teamName={} "
                    + "（对齐 CC main.tsx CLI 参数注入，身份判断不再每次查 sysprop）",
                    agentId, teamName);
        }
    }
}

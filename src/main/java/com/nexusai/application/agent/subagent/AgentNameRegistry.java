package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent name→agentId 注册表 · 对齐 CC {@code appState.agentNameRegistry}
 * （Open-ClaudeCode/src/state/AppStateStore.ts，写点 AgentTool.tsx:703-712，读点
 * SendMessageTool.ts:804 {@code appState.agentNameRegistry.get(input.to)}）。
 *
 * <p>IMP-G4（组 11-1）TR-G1 C7 修复：探查证实 Java 全仓 {@code agentNameRegistry}
 * 无写入点（仅 AppState.java:17 文档提及）→ SendMessage 按名路由断。本组件提供
 * 会话级 name→agentId 注册 + CC queuePendingMessage 等价待投递队列。
 *
 * <p><b>写点</b>：{@link SubagentTool} async spawn（CC AgentTool.tsx:703-712
 * {@code if (name) rootSetAppState(... agentNameRegistry: next)}）——post-registerAsyncAgent，
 * spawn 失败不残留；agent 终态 unregister。
 *
 * <p><b>读点</b>：{@link com.nexusai.application.agent.tool.impl.SendMessageTool}
 * 按名路由（CC SendMessageTool.ts:800-813 {@code registered ?? toAgentId(input.to)}）——
 * 已注册且任务存活 → queue 到待投递队列（CC queuePendingMessage）；子 agent 下一轮
 * {@link SubagentExecutor#runSubagentQueryLoop} drain 为 user 消息（CC LocalAgentTask
 * 每轮消费 pendingMessages）。
 *
 * <p><b>WHY 独立 @Component</b>（Pattern #14 static seam 反转）：CC 注册表是 React
 * appState 的一部分（会话作用域）；Java 无 appState 字段化承载 → 独立会话级组件，
 * SubagentTool（写）/SendMessageTool（读）/SubagentExecutor（drain）三方 DI 共享
 * 同一实例（单例 Spring bean），与 CC 会话级 appState 语义等价（多实例共享一致性
 * 登记：单机部署下同进程即会话隔离边界）。
 */
@Component
public class AgentNameRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentNameRegistry.class);

    /** name → agentId · 对齐 CC {@code appState.agentNameRegistry}（Map&lt;string, agentId&gt;） */
    private final Map<String, String> nameToAgentId = new ConcurrentHashMap<>();

    /** agentId → 待投递消息队列（FIFO）· 对齐 CC {@code queuePendingMessage} 待办队列 */
    private final Map<String, ArrayDeque<String>> pendingMessages = new ConcurrentHashMap<>();

    /**
     * 注册 name→agentId · 对齐 CC AgentTool.tsx:703-712
     * {@code next.set(name, asAgentId(asyncAgentId))}（post-registerAsyncAgent）。
     * 重复注册覆盖（CC Map.set 语义）。
     *
     * @param name    子 agent 名（工具输入 {@code name} 字段，可为 null 表示无名）
     * @param agentId 子 agent UUID（async 路径 agentId===taskId）
     */
    public void register(String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null) {
            return;
        }
        String prev = nameToAgentId.put(name, agentId);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注册 name→agentId: name='{}' agentId={} (CC AgentTool.tsx:703-712, 覆盖前={})",
                name, agentId, prev);
        }
    }

    /**
     * 注销 name→agentId · 对齐 CC 无显式注销（React 状态随会话结束 GC）；
     * Java 在子 agent 终态清理时调用，避免 name 映射残留指向已终止 agentId。
     *
     * @param name 子 agent 名
     */
    public void unregister(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        String removed = nameToAgentId.remove(name);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 注销 name→agentId: name='{}' 移除={}", name, removed);
        }
    }

    /**
     * 按名解析 agentId · 对齐 CC SendMessageTool.ts:804
     * {@code appState.agentNameRegistry.get(input.to)}。
     *
     * @param name 子 agent 名
     * @return agentId 或 null（未注册）
     */
    public String resolve(String name) {
        return name == null ? null : nameToAgentId.get(name);
    }

    /**
     * 是否存在该 name 注册（路由可用性判定）。
     */
    public boolean contains(String name) {
        return name != null && nameToAgentId.containsKey(name);
    }

    /**
     * 投递待办消息到子 agent · 对齐 CC {@code queuePendingMessage(agentId, message, setAppState)}
     * （SendMessageTool.ts:810-813，子 agent 存活时按名路由的落点）。子 agent 下一轮
     * {@link SubagentExecutor#runSubagentQueryLoop} 经 {@link #drain} 消费。
     *
     * @param agentId 目标子 agent UUID
     * @param message 待投递消息文本
     */
    public void queue(String agentId, String message) {
        if (agentId == null || message == null) {
            return;
        }
        pendingMessages.computeIfAbsent(agentId, k -> new ArrayDeque<>()).addLast(message);
        if (log.isDebugEnabled()) {
            log.debug("[AgentNameRegistry] 投递待办消息: agentId={} 消息长度={} (CC queuePendingMessage)",
                agentId, message.length());
        }
    }

    /**
     * 取出并清空该 agent 的待投递消息（FIFO）· 对齐 CC LocalAgentTask 每轮消费
     * {@code pendingMessages}。无待办 → 空列表。
     *
     * @param agentId 子 agent UUID
     * @return 待投递消息列表（FIFO 序，消费后清除）
     */
    public List<String> drain(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        ArrayDeque<String> q = pendingMessages.remove(agentId);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        return List.copyOf(q);
    }

    /**
     * 是否有待投递消息（路由层判定子 agent 是否被阻塞等待）。
     */
    public boolean hasPending(String agentId) {
        ArrayDeque<String> q = agentId == null ? null : pendingMessages.get(agentId);
        return q != null && !q.isEmpty();
    }

    /** 当前注册条目数（测试/观测）。 */
    public int size() {
        return nameToAgentId.size();
    }
}

package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-G4 C7] AgentNameRegistry 单元测试 · 对齐 CC appState.agentNameRegistry
 * （AgentTool.tsx:703-712 写点 / SendMessageTool.ts:804 读点 / queuePendingMessage 待办队列）。
 *
 * <p>WHY 这些行为重要（规则九）：SendMessage 按名路由的前提是 name→agentId 注册存在
 * （TR-G1 C7 实证 Java 无写入点 → 路由断），待办队列消费是"按名投递到子 agent 下一轮"
 * 的可观察语义（CC SendMessageTool.ts:810-813）。
 */
class AgentNameRegistryTest {

    @Test
    @DisplayName("register+resolve: name→agentId 注册后可按名解析（CC AgentTool.tsx:703-712 / SendMessageTool.ts:804）")
    void registerThenResolve() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");

        assertThat(registry.resolve("worker-a")).as("注册后按名解析到 agentId").isEqualTo("agent-111");
        assertThat(registry.contains("worker-a")).isTrue();
        assertThat(registry.resolve("unknown")).as("未注册名解析 null").isNull();
    }

    @Test
    @DisplayName("重复注册覆盖（CC Map.set 语义）+ 空名/空 agentId 忽略")
    void reRegisterOverwritesAndBlankIgnored() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");
        registry.register("worker-a", "agent-222");
        assertThat(registry.resolve("worker-a")).as("重复注册覆盖旧映射").isEqualTo("agent-222");

        registry.register("", "agent-333");
        registry.register("worker-b", null);
        assertThat(registry.resolve("")).as("空名不注册").isNull();
        assertThat(registry.contains("worker-b")).as("空 agentId 不注册").isFalse();
    }

    @Test
    @DisplayName("unregister: 子 agent 终态注销后按名解析 null（避免残留映射指向已终止 agentId）")
    void unregisterRemovesMapping() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");
        registry.unregister("worker-a");
        assertThat(registry.resolve("worker-a")).as("注销后解析 null").isNull();
        assertThat(registry.contains("worker-a")).isFalse();
    }

    @Test
    @DisplayName("[终态清理] unregister: 注销 name 时同步清空其映射 agentId 的待投递队列（否则消息永久滞留：终态 agent 不再 drain）")
    void unregisterClearsPendingQueueOfMappedAgentId() {
        // WHY（CLAUDE.md 规则九）：pendingMessages 的唯一消费点是 drain(agentId)（SubagentExecutor
        //   启动段 / LlmAgentLoop 每轮 drainPendingAgentMessages），两者都只在 agent 存活期发生。
        //   agent 终态 → unregister；此后投递的消息既送不到（调用方已拿到 success:true）、
        //   也永远不被消费 → 静默丢失 + 队列永久驻留（内存泄漏）。
        //   变异点：unregister 去掉 pendingMessages.remove(removed) → 本用例红。
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");
        registry.register("worker-b", "agent-222");
        registry.queue("agent-111", "hello");
        registry.queue("agent-222", "keep-me");

        registry.unregister("worker-a");

        assertThat(registry.hasPending("agent-111"))
            .as("注销后该 name 映射到的 agentId 的待投递队列必须清空").isFalse();
        assertThat(registry.drain("agent-111"))
            .as("drain 不再返回滞留消息（终态 agent 已无消费点）").isEmpty();
        assertThat(registry.hasPending("agent-222"))
            .as("⛔ 只清本 name 映射到的 agentId，不得误清其它存活 agent 的队列").isTrue();
    }

    @Test
    @DisplayName("queue+drain: 按名路由待办消息 FIFO 消费（CC queuePendingMessage → LocalAgentTask 每轮消费）")
    void queueThenDrainFifo() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.queue("agent-111", "first");
        registry.queue("agent-111", "second");

        assertThat(registry.hasPending("agent-111")).as("有待办消息").isTrue();
        assertThat(registry.drain("agent-111")).as("FIFO 序消费").containsExactly("first", "second");
        assertThat(registry.hasPending("agent-111")).as("消费后清空").isFalse();
        assertThat(registry.drain("agent-111")).as("再次 drain 空列表").isEmpty();
    }

    @Test
    @DisplayName("queue 未注册 agentId 亦可投递（路由层先 resolve 后 queue，子 agent 启动时消费）")
    void queueWorksForAnyAgentId() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.queue("any-id", "hello");
        assertThat(registry.drain("any-id")).containsExactly("hello");
    }
}

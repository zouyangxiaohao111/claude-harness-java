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
    @DisplayName("[retirement] unregister: 注销进退役表，名字仍可寻址（CC names keep working after completion）")
    void unregisterMovesToRetirement() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");
        registry.unregister("worker-a");
        // 活跃映射已删（contains 只看活跃表）；
        assertThat(registry.contains("worker-a")).as("活跃表不再含该名").isFalse();
        // 但解析仍然命中退役表（解析顺序：活跃 → 退役 → not-found）。
        assertThat(registry.resolve("worker-a"))
            .as("退役表中名字仍解析到原 agentId（SendMessage 据此走 resume 分支）")
            .isEqualTo("agent-111");
        assertThat(registry.retiredSize()).isEqualTo(1);
        assertThat(registry.resolveEntry(null, "worker-a").agentId()).isEqualTo("agent-111");
        assertThat(registry.entryById("agent-111")).as("按 agentId 反查也命中退役表").isNotNull();
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

    // ── 保留名 / 名字形态 · 对齐 CC（claude.exe 2.1.328 只读字节检索） ─────────────

    @Test
    @DisplayName("保留名 = main/team-lead/user/system：teammate 与 * 不是保留名（CC v1 @317883761）")
    void reservedNamesMatchCc() {
        AgentNameRegistry registry = new AgentNameRegistry();

        // a) CC 不把 teammate / * 当保留名 ⇒ 注册必须成功（改前本仓多算 ⇒ 本用例红）
        assertThat(registry.register("s1", "teammate", "a1").registered())
            .as("teammate 不是保留名（CC v1 不含）").isTrue();
        assertThat(registry.register("s1", "*", "a2").registered())
            .as("* 不是保留名（CC 只被名字字符集/地址层挡）").isTrue();

        // b/c) CC 真实保留名（按归一化比较，任意拼写）
        for (String reserved : new String[]{"user", "system", "main", "team-lead",
                "User", " SYSTEM ", "MAIN", "Team-Lead"}) {
            AgentNameRegistry.RegistrationOutcome outcome =
                registry.register("s1", reserved, "a-" + reserved.trim());
            assertThat(outcome.registered())
                .as("保留名 %s 必须被拒绝（CC in any spelling）", reserved).isFalse();
            assertThat(outcome.reservedReason()).as("拒绝原因非空").isNotBlank();
        }
        assertThat(registry.resolveActiveEntry("s1", "main")).as("保留名不写任何映射").isNull();
        assertThat(registry.resolveActiveEntry("s1", "user")).isNull();
    }

    @Test
    @DisplayName("agent id 形 / session id 形名字被拒（CC registerName 唯一拒绝入口 @342171068）")
    void agentIdAndSessionIdShapeRejected() {
        AgentNameRegistry registry = new AgentNameRegistry();

        // agent id 形：^a(?:.+-)?[0-9a-f]{16}$
        for (String shaped : new String[]{"a0123456789abcdef", "acompact-0123456789abcdef"}) {
            assertThat(AgentNameRegistry.hasAgentIdShape(shaped))
                .as("%s 是 agent id 形", shaped).isTrue();
            assertThat(registry.register("s1", shaped, "x1").registered()).isFalse();
            assertThat(registry.registerIfAbsent("s1", shaped, "x2").registered()).isFalse();
        }
        // session id 形：UUID 8-4-4-4-12
        String sessionShaped = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(AgentNameRegistry.hasSessionIdShape(sessionShaped)).isTrue();
        assertThat(registry.register("s1", sessionShaped, "x3").registered()).isFalse();
        // 对照组：普通名字不受影响
        assertThat(registry.register("s1", "a0123456789abcde", "x4").registered())
            .as("16 位不得少一位（否则不是 agent id 形）").isTrue();
    }

    @Test
    @DisplayName("名字格式 ^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$（CC Hrl @316706232）")
    void nameFormatPattern() {
        assertThat(AgentNameRegistry.isValidAgentName("researcher")).isTrue();
        assertThat(AgentNameRegistry.isValidAgentName("a_b-c9")).isTrue();
        assertThat(AgentNameRegistry.isValidAgentName("9lives")).as("首字符可以是数字").isTrue();
        assertThat(AgentNameRegistry.isValidAgentName("x".repeat(64))).as("64 合法").isTrue();

        assertThat(AgentNameRegistry.isValidAgentName("x".repeat(65))).as("65 超长").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("-abc")).as("首字符不得是 -").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("_abc")).as("首字符不得是 _").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("ab c")).as("空格非法").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("ab.c")).as("点非法").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("*")).as("* 不在字符集内").isFalse();
        assertThat(AgentNameRegistry.isValidAgentName("")).isFalse();
        assertThat(AgentNameRegistry.isValidAgentName(null)).isFalse();
    }

    @Test
    @DisplayName("registerIfAbsent：先注册者保号，后者不注册（CC async 子代理 @333963676）")
    void registerIfAbsentKeepsFirst() {
        AgentNameRegistry registry = new AgentNameRegistry();

        assertThat(registry.registerIfAbsent("s1", "worker", "agent-1").registered()).isTrue();
        AgentNameRegistry.RegistrationOutcome second =
            registry.registerIfAbsent("s1", "worker", "agent-2");
        assertThat(second.registered()).as("同名第二个 agent 不注册").isFalse();
        assertThat(second.reservedReason()).contains("already registered");
        assertThat(registry.resolveActiveEntry("s1", "worker").agentId())
            .as("⛔ 先注册者保持（不得覆盖）").isEqualTo("agent-1");
        // 同 agentId 重复注册幂等
        assertThat(registry.registerIfAbsent("s1", "worker", "agent-1").registered()).isTrue();
        // 不同 owner 互不影响
        assertThat(registry.registerIfAbsent("s2", "worker", "agent-2").registered()).isTrue();
    }
}

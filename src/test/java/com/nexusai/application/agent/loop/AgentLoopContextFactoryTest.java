package com.nexusai.application.agent.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H7-arch Phase 5-2 P3-②] {@link AgentLoopContextFactory} 单测。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>shared() / forSession() 构造有效 ctx</b> — 工厂是 P3-③ 删 carrier 后 Subagent/Hook
 *       接入的唯一 ctx 来源；构造失败 / 组件缺失会在运行时 NPE。</li>
 *   <li><b>stream 三字段隔离</b> — shared() 恒 null（Subagent/Hook 无 STOMP 流）；
 *       forSession() 透传（主循环 per-session）。</li>
 *   <li><b>per-session 状态隔离</b> — 每次构造全新 {@link AgentLoopContext.LoopSessionState}，
 *       等价 CC 各 agent 各自模块级状态（sentSkillNames / taskSummary 时间门控不跨 session 共享）。</li>
 * </ol>
 */
class AgentLoopContextFactoryTest {

    @Test
    @DisplayName("shared() 构造有效 ctx：stream 三字段 null + 4 D5 组件 + sessionState 非空 + 各自全新 sessionState")
    void shared_buildsValidContext() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();

        AgentLoopContext ctx = factory.shared(null);

        assertThat(ctx)
            .as("shared() 必须构造非空 ctx（P3-③ 接入前提）")
            .isNotNull();
        assertThat(ctx.streamTopic()).as("shared() 无 STOMP 流").isNull();
        assertThat(ctx.streamSessionId()).isNull();
        assertThat(ctx.streamUserMessageId()).isNull();
        assertThat(ctx.toolExecutionBeans()).as("ToolExecutionBeans 必须非空（D5）").isNotNull();
        assertThat(ctx.tokenBudgetBeans()).as("TokenBudgetBeans 必须非空（D5）").isNotNull();
        assertThat(ctx.eventBridge()).as("EventBridge 必须非空（D5）").isNotNull();
        assertThat(ctx.sessionState()).as("LoopSessionState 必须非空（会话状态容器）").isNotNull();
    }

    @Test
    @DisplayName("forSession() 透传 stream 三字段 + 与 shared() 各自全新 sessionState（per-session 隔离）")
    void forSession_passesStreamFieldsAndIsolatesSessionState() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();

        AgentLoopContext sessionCtx = factory.forSession("/topic/x", "sess-1", "msg-1");
        AgentLoopContext sharedCtx = factory.shared(null);

        assertThat(sessionCtx.streamTopic()).isEqualTo("/topic/x");
        assertThat(sessionCtx.streamSessionId()).isEqualTo("sess-1");
        assertThat(sessionCtx.streamUserMessageId()).isEqualTo("msg-1");
        assertThat(sharedCtx.streamTopic()).isNull();
        assertThat(sessionCtx.sessionState())
            .as("每次构造必须全新 sessionState（不跨 session 共享 sentSkillNames / todoReminderCache）")
            .isNotSameAs(sharedCtx.sessionState());
    }

    @Test
    @DisplayName("非 Spring 场景 ctx 仍可构造（无依赖注入 → 组件全 null 空值保护）")
    void shared_withoutBeans_constructsNullSafeContext() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();

        AgentLoopContext ctx = factory.shared(null);

        assertThat(ctx).isNotNull();
        assertThat(ctx.tokenBudgetBeans()).isNotNull();
        assertThat(ctx.eventBridge()).isNotNull();
        assertThat(ctx.sessionState()).isNotNull();
    }

    @Test
    @DisplayName("forSession 5 参重载：注入 session + overridePublisher 透传到 ctx（主循环 per-session 通道）")
    void forSession_fiveArg_passesSessionAndOverridePublisher() {
        AgentLoopContextFactory factory = new AgentLoopContextFactory();
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        org.springframework.context.ApplicationEventPublisher override =
            Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);

        AgentLoopContext ctx = factory.forSession("/t", "s", "m", session, override);

        assertThat(ctx.sessionState()).as("5 参重载必须使用调用方传入的 sessionState").isSameAs(session);
        assertThat(ctx.eventBridge().overridePublisher())
            .as("5 参重载必须透传 override 事件通道（VerifyChatController.setEventPublisher）")
            .isSameAs(override);
    }
}

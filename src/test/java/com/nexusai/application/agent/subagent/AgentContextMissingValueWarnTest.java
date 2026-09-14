package com.nexusai.application.agent.subagent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1-T14 · 缺值策略 (b)] invokingRequestId 缺值时 = 「<b>缺值 ⇒ 无属性</b>」，绝不归因到 ambient。
 *
 * <p><b>WHY（规则九 · 意图）</b>：{@code invokingRequestId / invocationKind} 是「哪个子代理调用
 * 引发了这次 API 调用」的归因边，CC 用 sparse-edge 语义（agentContext.ts:159-178）：仅在每个
 * invocation 的**第一个** terminal API event 出现一次。当上下文存在但 {@code invokingRequestId}
 * 缺值时，正确行为是<b>不写这两个属性</b>——若此时回落到任何 ambient / 进程级状态，
 * 就会把这次调用**归因到别的 agent**（错误归因比缺失更坏：指标被污染，且不可从数据上察觉）。
 *
 * <p>本测试锁 3 件事：
 * <ol>
 *   <li><b>context == null</b>（主线程，绝大多数调用）⇒ 无属性、无告警（(b) 类「本就不需要」）；</li>
 *   <li><b>context 存在但 invokingRequestId 缺值</b> ⇒ 返回 null、attrs 中<b>零</b>
 *       {@code invokingRequestId}/{@code invocationKind} 键，且留下 <b>≥WARN</b> 披露；</li>
 *   <li><b>该 WARN 是「每上下文实例一次」而不是「每 JVM 一次」</b>：两个独立实例各触发 ⇒
 *       必须捕到<b>两条</b> WARN（一次性闸会把第二个会话的缺陷结构性变成不可观测，本仓裁定-8）；</li>
 * </ol>
 * 外加 sparse-edge 正方向：同一实例消费一次后第二次返回 null（一次翻转，不被本次改动破坏）。
 *
 * <p><b>RED teeth</b>：把 {@code invokingRequestId == null} 分支删掉（改为归因到 ambient/默认值）
 * ⇒ 断言 2 红；把 warn 降为 debug ⇒ 断言 2/3 红。
 */
class AgentContextMissingValueWarnTest {

    private static AgentContext.SubagentContext ctx(String invokingRequestId) {
        // 便捷构造器：agentType='subagent' + invocationEmitted=new AtomicBoolean(false)
        return new AgentContext.SubagentContext(
            "aa3f2c1b4d5e6f7a8", null, "Explore", true, invokingRequestId, "spawn");
    }

    private static ListAppender<ILoggingEvent> captureWarn() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AgentContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        return appender;
    }

    private static void stopCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AgentContext.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<ILoggingEvent> missingValueWarns(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
            .filter(e -> e.getFormattedMessage().contains("缺值策略(b)"))
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言1: 缺值 ⇒ 不产生 invokingRequestId/invocationKind 属性（不归因到 ambient）")
    void missingInvokingRequestId_writesNoAttributionAttrs() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("pre_existing", "keep-me");

        ListAppender<ILoggingEvent> logs = captureWarn();
        AgentContext.InvokingRequestEdge edge;
        try {
            edge = AgentContext.attachInvokingRequestEdge(attrs, ctx(null));
        } finally {
            stopCapture(logs);
        }

        assertThat(edge).as("缺值 ⇒ 无边").isNull();
        assertThat(attrs)
            .as("⭐ 缺值 ⇒ 无属性（绝不把这次调用归因到别的 agent）")
            .doesNotContainKeys("invokingRequestId", "invocationKind")
            .containsEntry("pre_existing", "keep-me");
        assertThat(missingValueWarns(logs))
            .as("缺值必须留下 ≥WARN 披露")
            .hasSize(1);
    }

    @Test
    @DisplayName("断言2: context == null（主线程）⇒ 无属性、无告警（(b) 类本就不需要，避免热路径刷屏）")
    void nullContext_isSilent() {
        Map<String, Object> attrs = new HashMap<>();

        ListAppender<ILoggingEvent> logs = captureWarn();
        try {
            assertThat(AgentContext.attachInvokingRequestEdge(attrs, null)).isNull();
        } finally {
            stopCapture(logs);
        }

        assertThat(attrs).doesNotContainKeys("invokingRequestId", "invocationKind");
        assertThat(missingValueWarns(logs))
            .as("主线程（无归因上下文）不属缺值，不得告警——否则每次 API 调用都刷屏")
            .isEmpty();
    }

    @Test
    @DisplayName("断言3: 告警是「每上下文实例一次」而非「每 JVM 一次」——两实例 ⇒ 两条 WARN")
    void warnIsPerContextInstance_notPerJvm() {
        ListAppender<ILoggingEvent> logs = captureWarn();
        try {
            // 会话/子代理 1 的上下文实例（同一实例多次 terminal event 只发一次）
            AgentContext.SubagentContext c1 = ctx(null);
            AgentContext.attachInvokingRequestEdge(new HashMap<>(), c1);
            AgentContext.attachInvokingRequestEdge(new HashMap<>(), c1);
            // 会话/子代理 2 的上下文实例 —— 必须**再**发一条（一次性进程级闸在这里会红）
            AgentContext.SubagentContext c2 = ctx(null);
            AgentContext.attachInvokingRequestEdge(new HashMap<>(), c2);
        } finally {
            stopCapture(logs);
        }

        assertThat(missingValueWarns(logs))
            .as("⭐ 两个独立上下文实例各披露一次 ⇒ 必须捕到两条（进程级 one-shot 闸会让第二条消失）")
            .hasSize(2);
    }

    // ── 对照：sparse-edge 正方向不被本次改动破坏 ──

    @Test
    @DisplayName("对照: 有 invokingRequestId ⇒ 首个 terminal event 写入属性，第二个不再写（一次翻转）")
    void presentInvokingRequestId_sparseEdgeStillWorks() {
        AgentContext.SubagentContext context = ctx("req-abc");

        Map<String, Object> first = new HashMap<>();
        AgentContext.InvokingRequestEdge e1 =
            AgentContext.attachInvokingRequestEdge(first, context);
        assertThat(e1).isNotNull();
        assertThat(first)
            .containsEntry("invokingRequestId", "req-abc")
            .containsEntry("invocationKind", "spawn");

        Map<String, Object> second = new HashMap<>();
        assertThat(AgentContext.attachInvokingRequestEdge(second, context))
            .as("已消费 ⇒ 第二次无边（CC agentContext.ts:173 一次翻转）")
            .isNull();
        assertThat(second).doesNotContainKeys("invokingRequestId", "invocationKind");
    }

    @Test
    @DisplayName("对照: 有 invokingRequestId 时**不得**产生缺值告警")
    void presentInvokingRequestId_noMissingValueWarn() {
        ListAppender<ILoggingEvent> logs = captureWarn();
        try {
            AgentContext.attachInvokingRequestEdge(new HashMap<>(), ctx("req-abc"));
        } finally {
            stopCapture(logs);
        }
        assertThat(missingValueWarns(logs)).isEmpty();
    }
}

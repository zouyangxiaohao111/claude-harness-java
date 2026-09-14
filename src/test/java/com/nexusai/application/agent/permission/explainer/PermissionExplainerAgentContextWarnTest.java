package com.nexusai.application.agent.permission.explainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>[S1-T7 · 坦率项 #2] 权限解释器的「归因上下文显式为空」告警必须是<b>逐次</b>的</b>。
 *
 * <h2>WHY（本仓裁定-8 的关键技术点，不是泛泛而谈）</h2>
 * <p>缺值策略 (b) 类允许「本就不需要 ⇒ 可跳过」，但要求日志 <b>≥WARN</b>。真正会把它变成
 * 空头承诺的是<b>闸的位置</b>：若用<b>进程级一次性闸</b>（{@code static AtomicBoolean} /
 * {@code static volatile boolean}）去抑制重复告警，则「≥WARN 可观测」会<b>结构性退化</b>为
 * 「每 JVM 一行」—— <b>第二个会话的同一缺值从此不可观测</b>（本仓已存在两处同族载体：
 * {@code TaskService} 的首次-WARN-后续-debug 闸、{@code CompactWarningState.suppressed}）。
 *
 * <p>本类把「逐次」钉成可证伪断言：<b>两个不同 sessionId 各触发一次</b> ⇒ 必须捕获到
 * <b>两条独立 WARN</b>（不是一条）。
 *
 * <p><b>反向实验配方</b>：把 {@code PermissionExplainer.warnAgentContextExplicitlyNull} 的调用
 * 改成由进程级一次性闸守卫（例如新增 {@code private static final AtomicBoolean suppressed = new AtomicBoolean();}
 * 并在 {@code if (!suppressed.getAndSet(true))} 内调用）⇒ 本类第二条断言必须红（只捕获到 1 条 WARN）。
 */
class PermissionExplainerAgentContextWarnTest {

    private static final String WARN_MARKER = "agent 归因上下文显式为空";

    private ch.qos.logback.classic.Logger explainerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        explainerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PermissionExplainer.class);
        appender = new ListAppender<>();
        appender.start();
        explainerLogger.addAppender(appender);
        explainerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppender() {
        explainerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("两个不同会话各触发一次 ⇒ 捕获两条独立 WARN（逐次，非进程级一次性闸）")
    void twoSessions_eachProduceOwnWarn() {
        // 到达 options 构造点需要：门控开 + 会话主循环模型可解析 + 模型配置可用 + provider 存在。
        // provider 返回 null（不返回 tool_use）→ 方法在解析段返回 null；**不影响**：本类断言的是
        // 「WARN 已发出」这一副作用（它在 options 构造后、LLM 调用前）。
        LlmProviderFactory providerFactory = mock(LlmProviderFactory.class);
        ModelConfigResolver resolver = mock(ModelConfigResolver.class);
        AnalyticsTracker analytics = mock(AnalyticsTracker.class);
        LlmProvider provider = mock(LlmProvider.class);

        when(providerFactory.getProvider(any(), any())).thenReturn(provider);
        when(resolver.resolve("test-model")).thenReturn(new ModelConfigResolver.ResolvedModel(
            new ProviderConfig("https://example.com", "sk-test"), "openai_sdk"));
        when(provider.chatWithOptionsMessage(any(), any(), any(), any(), any())).thenReturn(null);

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        String sessionA = "sess-warn-a";
        String sessionB = "sess-warn-b";
        for (String sid : List.of(sessionA, sessionB)) {
            AgentState state = mock(AgentState.class);
            when(state.currentModel()).thenReturn("test-model");
            registry.register(sid, state);
        }
        PermissionExplainer explainer =
            new PermissionExplainer(providerFactory, resolver, analytics, true);
        explainer.setSessionAgentStateRegistry(registry);

        appender.list.clear();
        explainer.generatePermissionExplanation(
            sessionA, "Bash", com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode().put("command", "ls -la"), null, null, null);
        explainer.generatePermissionExplanation(
            sessionB, "Bash", com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .createObjectNode().put("command", "ls -la"), null, null, null);

        long warnCount = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains(WARN_MARKER))
            .count();

        assertThat(warnCount)
            .as("会话 A 与会话 B **各自**必须留下一条归因空值 WARN（= 逐次告警）。"
                + "若为 1 ⇒ 有人把它改成了进程级一次性闸 ⇒ 第二个会话的缺值在结构上不可观测（裁定-8）")
            .isEqualTo(2);

        // 并且两条 WARN 必须携带各自的 sessionId（可定位到具体会话，而非泛泛一行）
        List<String> aHits = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains(WARN_MARKER))
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains(sessionA))
            .toList();
        List<String> bHits = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains(WARN_MARKER))
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains(sessionB))
            .toList();
        assertThat(aHits).as("WARN 必须携带会话 A 的 sessionId（可定位）").hasSize(1);
        assertThat(bHits).as("WARN 必须携带会话 B 的 sessionId（可定位）").hasSize(1);
    }
}

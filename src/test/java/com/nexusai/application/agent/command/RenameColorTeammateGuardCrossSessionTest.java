package com.nexusai.application.agent.command;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.config.CommandRegistrationConfig;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S1-T12] 斜杠命令的 teammate 守卫必须按 **handler 形参 sessionId 解析会话身份**，
 * 不得在分派线程上求值「线程局部 / 进程级」身份载体。
 *
 * <p><b>WHY（规则九 · 意图）</b>：{@code /rename}（CC rename.ts:30-35）与 {@code /color}
 * （CC color.ts:24-30）在 CC 侧都以 {@code isTeammate()} 拒绝 teammate 会话。原 Java 实现把该判据
 * 写成 <b>方法引用</b> {@code Teammate::isTeammate} / {@code Teammate.isTeammate(null)}——
 * 它在**命令分派线程**（HTTP/WS handler）求值，而该线程上不存在任何 teammate 身份载体
 * ⇒ 守卫<b>恒为 false</b>、静默失效：teammate 会话照样能改名/改色（而 CC 明确拒绝）。
 *
 * <p>修复 = 把求值从「线程态」改为「按 handler 已持有的 {@code sessionId} 查会话级身份」。
 * 本测试用**跨会话夹具**锁定该语义：同一进程、同一 Dispatcher、同一注册实例下，
 * <ul>
 *   <li><b>会话 A</b>（该会话的 TUC 显式携带 teammate 身份）⇒ {@code /rename} 与 {@code /color}
 *       <b>都被拒</b>；</li>
 *   <li><b>会话 B</b>（TUC 无 teammate 身份）⇒ <b>都放行</b>。</li>
 * </ul>
 * ⛔ 本测试**不**直接调 {@code RenameCommand.execute(args, 手工造 Env)}——那等于自己伪造
 * {@code isTeammate}，测不到「身份从哪来」；一切都经**生产注册的 handler** +
 * 真实 sessionId 走通。
 *
 * <p><b>RED teeth</b>：把两处守卫改回 {@code Teammate::isTeammate} 式的恒 false / 或改成
 * {@code () -> true}，断言中的一方向即红（详见报告的反向实验配方）。
 */
class RenameColorTeammateGuardCrossSessionTest {

    /** 会话 A = teammate 会话；会话 B = 普通会话。
     *
     *  <p><b>[session-id-short]</b>取 <b>UUID 形态</b>作会话键，用于证明「会话键形态不影响路由」——
     *  颜色状态通道按 {@code registry.get(String)}（sessions map）查，与键是不是 UUID 形态无关。
     *  生产 short 键形态由 {@code AgentColorCommandTest} 覆盖。 */
    private static final String SESSION_A = "3f1b6c2e-0000-4000-8000-0000000000aa";
    private static final String SESSION_B = "3f1b6c2e-0000-4000-8000-0000000000bb";

    private static final String TEAMMATE_AGENT_ID_A = "researcher@team-a";

    // ════════════════════════════════════════════════════════════════════════
    // 夹具：两个会话各自注册 AgentState + 身份
    // ════════════════════════════════════════════════════════════════════════

    private static AgentState stateFor(String sessionId, boolean teammate) {
        AgentState state = new AgentState("sys", sessionId, null);
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), sessionId);
        if (teammate) {
            tuc = tuc.withTeammateIdentity(new TeammateIdentity(
                TEAMMATE_AGENT_ID_A, "researcher", "team-a", "#ff0000", false, "leader-sess"));
        }
        state.setCurrentToolUseContext(tuc);
        return state;
    }

    private static SessionAgentStateRegistry registryWithBothSessions(AgentState a, AgentState b) {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        // [session-id-short] 只注册 **String 会话键**（sessions map）——这正是生产的注册形态。
        //   ⛔ 原实现额外 register(UUID.fromString(SESSION_x)) 到 agents map，是为兼容旧
        //   setAppStateColor 的 `resolveSessionUuid → get(UUID)` 错路由；该路由已删 ⇒ 那两条
        //   UUID 注册成为死夹具，且会**掩盖回归**（若颜色回退到 UUID 查，本测试仍绿）⇒ 一并删除。
        registry.register(SESSION_A, a);
        registry.register(SESSION_B, b);
        return registry;
    }

    /** 捕获 {@code CommandRegistrationConfig} 的 /rename 结果日志（info 级，含 renamed=true/false）。 */
    private static ListAppender<ILoggingEvent> captureRenameLogs() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CommandRegistrationConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        return appender;
    }

    private static void stopCapture(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(CommandRegistrationConfig.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> renameLogLines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("/rename 执行完成"))
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // /rename
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/rename: 会话A(teammate) 被拒 / 会话B(非 teammate) 放行 —— 同一注册实例、按 sessionId 解析")
    void rename_guardIsPerSession() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        AgentState stateA = stateFor(SESSION_A, true);
        AgentState stateB = stateFor(SESSION_B, false);
        new CommandRegistrationConfig().commandLocalSlashRegistration(
            dispatcher, null, registryWithBothSessions(stateA, stateB));

        ListAppender<ILoggingEvent> logs = captureRenameLogs();
        try {
            // A：teammate 会话 → CC rename.ts:30-35 拒绝
            dispatcher.dispatch("/rename renamed-by-a", SESSION_A, null);
            // B：普通会话 → 放行
            dispatcher.dispatch("/rename renamed-by-b", SESSION_B, null);
        } finally {
            stopCapture(logs);
        }

        List<String> lines = renameLogLines(logs);
        assertThat(lines).as("/rename 两侧各应产生一条结果日志").hasSize(2);
        assertThat(lines.get(0))
            .as("⭐ 会话 A（TUC 携带 teammate 身份）必须被拒：renamed=false")
            .contains("renamed=false");
        assertThat(lines.get(1))
            .as("⭐ 会话 B（无 teammate 身份）必须放行：renamed=true（证明判据由 sessionId 驱动，不是恒 false）")
            .contains("renamed=true");
    }

    // ════════════════════════════════════════════════════════════════════════
    // /color
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/color: 会话A(teammate) 被拒(颜色不变) / 会话B(非 teammate) 放行(颜色=blue)")
    void color_guardIsPerSession() {
        UserInputDispatcher dispatcher = new UserInputDispatcher();
        AgentState stateA = stateFor(SESSION_A, true);
        AgentState stateB = stateFor(SESSION_B, false);
        SessionAgentStateRegistry registry = registryWithBothSessions(stateA, stateB);

        // /color 经 AgentColorCommand 的 @PostConstruct 注册面（字段注入 → 反射装配，plain JUnit 无容器）
        AgentColorCommand colorCommand = new AgentColorCommand();
        inject(colorCommand, "userInputDispatcher", dispatcher);
        inject(colorCommand, "sessionAgentStateRegistry", registry);
        colorCommand.registerSlashCommand();

        dispatcher.dispatch("/color blue", SESSION_A, null);
        dispatcher.dispatch("/color blue", SESSION_B, null);

        assertThat(stateA.color())
            .as("⭐ 会话 A（teammate）被拒 ⇒ 颜色不得被写入")
            .isNull();
        assertThat(stateB.color())
            .as("⭐ 会话 B（非 teammate）放行 ⇒ 颜色必须真实写入（证明守卫不是恒拒）")
            .isEqualTo("blue");
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("测试装配失败: " + fieldName, e);
        }
    }
}

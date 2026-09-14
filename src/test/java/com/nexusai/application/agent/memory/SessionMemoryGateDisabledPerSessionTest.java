package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T15-4 · {@code SessionMemoryService} 的 {@code gate_disabled} 守卫必须<b>按会话键控</b>
 * · 对齐 CC {@code sessionMemory.ts:271} + <b>CC 注释原文
 * {@code // Log gate failure once per session (ant-only)}</b>（sessionMemory.ts:292-296）。
 *
 * <p><b>WHY（判据：可观测语义 / 指标基数 —— 与 {@code ClaudemdEngine.hasLoggedInitialLoad} 相反）</b>：
 * 本事件 {@code tengu_session_memory_gate_disabled} 的语义由 CC 明写为 <b>per session</b> ——
 * CC 侧是模块级 {@code let}，在 CC（单进程单会话）里「模块态」恰好就是「会话态」。本仓
 * <b>一 JVM 多会话</b>，原实现的单份 {@code static volatile boolean} 会让<b>会话 A 发射后
 * 会话 B 的同一事件永不发射</b> ⇒ 「≥可观测」在多会话下<b>结构性退化为「每 JVM 一行」</b>。
 * 对照：{@code ClaudemdEngine.hasLoggedInitialLoad} 承载 {@code tengu_claudemd__initial_load}，
 * 语义是「本进程首次加载」⇒ 会话化会改变<b>指标基数</b> ⇒ 明确<b>不</b>会话化（两条判据的差别
 * 已写进两处代码注释）。
 *
 * <p><b>兄弟实现收敛证据</b>：{@link ExtractMemoriesAgent} 的同名概念
 * （{@code hasLoggedGateFailureBySession}，:119/:733-734，[sm-cursor-sessionize] 2026-08-30）
 * <b>已按会话键控</b> —— 同一 CC 概念两套判据即缺陷，本用例把两条并到同一判据上。
 *
 * <p><b>⚠️ 观测装置说明</b>：该站点发射的是<b>遥测事件</b>（CC {@code logEvent}），不是日志行，
 * 故本用例以 telemetry 计数为断言装置（与 {@code SessionMemoryRev2AlignmentTest} 同装置）；
 * 未使用 logback appender —— 那里没有可计的日志行，用 appender 会得到一个恒 0 的假观测点。
 */
class SessionMemoryGateDisabledPerSessionTest {

    private static final String SESSION_A = "sess-sm-a";
    private static final String SESSION_B = "sess-sm-b";

    @TempDir
    Path baseDir;

    @AfterEach
    void resetStaticState() {
        SessionMemoryService.resetGateDisabledLogging();
    }

    @Test
    @DisplayName("T15-4: 两个会话各触发一次 ⇒ 必须捕到 2 条 gate_disabled（同会话第二次仍只 1 条/会话）")
    void twoSessions_eachEmitOnce() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> true);
        svc.setSessionMemoryFeatureEnabled(false);
        AttrCountingTelemetry telemetry = new AttrCountingTelemetry();
        svc.setTelemetry(telemetry);

        svc.extractSessionMemory(postSampling(SESSION_A));
        svc.extractSessionMemory(postSampling(SESSION_A)); // 同会话幂等
        svc.extractSessionMemory(postSampling(SESSION_B));
        svc.extractSessionMemory(postSampling(SESSION_B)); // 同会话幂等

        assertThat(telemetry.countsByEvent.getOrDefault("tengu_session_memory_gate_disabled", 0))
            .as("每会话一次（不是每 JVM 一次）⇒ 两会话必须各发射一条。"
                + "原 static volatile 单布尔实现下 A 置真后 B 不再发射 ⇒ expected: 2 but was: 1")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("T15-4: 非 ant 仍不发射（ant-only 判据不因键控而回归）")
    void nonAnt_stillNotEmitted() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> false);
        svc.setSessionMemoryFeatureEnabled(false);
        AttrCountingTelemetry telemetry = new AttrCountingTelemetry();
        svc.setTelemetry(telemetry);

        svc.extractSessionMemory(postSampling(SESSION_A));
        svc.extractSessionMemory(postSampling(SESSION_B));

        assertThat(telemetry.countsByEvent.getOrDefault("tengu_session_memory_gate_disabled", 0))
            .as("非 ant 不发射（CC `process.env.USER_TYPE === 'ant'` 前置条件）").isZero();
    }

    private static PostSamplingContext postSampling(String sessionId) {
        return new PostSamplingContext(
            List.of(), List.of(), Map.of(), Map.of(), tuc(sessionId), QuerySource.REPL_MAIN_THREAD);
    }

    /** 显式 sessionId 的 TUC（8 参便捷 ctor；本用例只关心 sessionId 维度）。 */
    private static ToolUseContext tuc(String sessionId) {
        return new ToolUseContext(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    /** 记录各事件发射次数（对齐 {@code SessionMemoryRev2AlignmentTest.AttrRecordingTelemetry} 装置）。 */
    private static final class AttrCountingTelemetry
            extends com.nexusai.application.agent.telemetry.Telemetry {
        final Map<String, Integer> countsByEvent = new HashMap<>();

        @Override
        public void recordEvent(String eventName, Map<String, Object> attributes) {
            countsByEvent.merge(eventName, 1, Integer::sum);
            super.recordEvent(eventName, attributes);
        }
    }
}

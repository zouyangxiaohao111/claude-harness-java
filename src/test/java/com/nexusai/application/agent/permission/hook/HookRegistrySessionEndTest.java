package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.HookRegistryDispatchTest.FakeLauncher;
import com.nexusai.application.agent.permission.hook.HookRegistryDispatchTest.StubMatcherEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S5 D-01/D-02] executeSessionEndHooks 聚焦测试 · 对齐 CC executeSessionEndHooks
 * （hooks.ts:4097-4141）：
 * <ul>
 *   <li>D-02 失败结果逐个 log.error（CC :4127-4134 stderr 写，服务端 → log）</li>
 *   <li>D-02 执行后 clearSessionHooks（CC :4136-4140）</li>
 *   <li>D-01 整体 cap：挂起 hook 被截断返回（per-hook 缺省 1500ms + 批级 abort，
 *       CC gracefulShutdown.ts:475 AbortSignal.timeout）</li>
 * </ul>
 */
@DisplayName("[IMP-HOOKS-S5 D-01/D-02] executeSessionEndHooks：失败日志 + clearSessionHooks + 超时 cap")
class HookRegistrySessionEndTest {

    /** exit 2（blocking）command hook → 失败结果 → log.error（CC stderr 写等价）. */
    @Test
    @DisplayName("D-02: 失败结果逐个 log.error（'SessionEnd hook [cmd] failed: output'）")
    void failedResult_logsErrorPerResult() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HookRegistry.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.ERROR);
        try {
            HookRegistry registry = registryWithCommandHook("", "disk full: session end", 2);

            registry.executeSessionEndHooks("sess-1", null, ExitReasons.OTHER, null);

            assertThat(appender.list)
                .as("D-02: 失败结果必须 log.error（CC :4130-4132 stderr 写 'SessionEnd hook [command] failed: output'）")
                .anySatisfy(e -> {
                    String msg = e.getFormattedMessage();
                    assertThat(msg).contains("SessionEnd hook [").contains("failed: ");
                });
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** 成功结果 → 无错误日志（CC :4129 !result.succeeded 才写）. */
    @Test
    @DisplayName("D-02: 成功结果不 log.error（CC :4128-4134 仅失败写）")
    void successResult_noErrorLog() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HookRegistry.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.ERROR);
        try {
            HookRegistry registry = registryWithCommandHook("ok output", "", 0);

            registry.executeSessionEndHooks("sess-1", null, ExitReasons.OTHER, null);

            assertThat(appender.list)
                .as("D-02: 成功结果不产生 'SessionEnd hook ... failed' 日志")
                .noneSatisfy(e -> e.getFormattedMessage().contains("SessionEnd hook ["));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** 执行后 session hooks 被清（CC :4136-4140 clearSessionHooks）. */
    @Test
    @DisplayName("D-02: 执行后 clearSessionHooks（CC :4136-4140）")
    void afterExecution_clearSessionHooks() {
        HookRegistry registry = new HookRegistry();
        registry.addSessionHook("sess-1", HookEventType.SESSION_END, "*",
            new CommandHook("echo bye", null, null, null, null, null, null, null),
            null, null);
        assertThat(registry.getSessionHooks("sess-1", HookEventType.SESSION_END).isEmpty())
            .as("前置：session hook 已注册")
            .isFalse();

        registry.executeSessionEndHooks("sess-1", null, ExitReasons.OTHER, null);

        assertThat(registry.getSessionHooks("sess-1", HookEventType.SESSION_END).isEmpty())
            .as("D-02: SessionEnd 执行后 session hooks 必须清理（CC :4136-4140 setAppState 时 clearSessionHooks）")
            .isTrue();
    }

    /** 挂起 hook → 1500ms cap 内截断返回 + destroyForcibly（per-hook 缺省超时 = SessionEnd 预算）. */
    @Test
    @DisplayName("D-01: 挂起 command hook 被 1500ms cap 截断（destroyForcibly + 有界返回）")
    void hangingHook_boundedByCap() throws Exception {
        AtomicInteger destroyed = new AtomicInteger();
        CommandHookExecutor.HookProcess hanging = new CommandHookExecutor.HookProcess() {
            @Override public java.io.OutputStream stdin() { return new java.io.ByteArrayOutputStream(); }
            @Override public java.io.InputStream stdout() { return new java.io.ByteArrayInputStream(new byte[0]); }
            @Override public java.io.InputStream stderr() { return new java.io.ByteArrayInputStream(new byte[0]); }
            @Override public boolean waitFor(long timeout, TimeUnit unit) {
                // 真实进程语义：阻塞至超时预算耗尽仍不退出（timeout 参数需被尊重，
                // runProcess 用 min(100ms, remaining) 切片轮询 deadline）
                try {
                    Thread.sleep(unit.toMillis(timeout));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            @Override public void destroyForcibly() { destroyed.incrementAndGet(); }
            @Override public int exitValue() { return 1; }
        };
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new CommandHook("hang.sh", null, null, null, null, null, null, null),
            null, null, null, "settings")));
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(new CommandHookExecutor(new FakeLauncher(hanging), null, null, null, null));

        long start = System.nanoTime();
        registry.executeSessionEndHooks("sess-1", null, ExitReasons.OTHER, null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs)
            .as("D-01: SessionEnd cap（1500ms 缺省）必须截断挂起 hook，返回有界（1500+2000 轮询 + 宽松余量）")
            .isLessThan(5000L);
        assertThat(destroyed.get())
            .as("D-01: 截断必须销毁子进程（等价 CC AbortSignal.timeout + createCombinedAbortSignal）")
            .isGreaterThanOrEqualTo(1);
    }

    /**
     * [IMP-HR-07 R-2 · 反思 F-A required_rework 4] 主 SessionEnd（UUID 会话运行中）session function
     * hook 恰好执行一次 + 随后 clearSessionHooks。WHY：SESSION_END 已加入 CC_APP_STATE_PRESENT_EVENTS
     * （CC appState 发射点，executeSessionEndHooks → executeHooksOutsideREPL 传 getAppState
     * hooks.ts:4118 → :3015 appState 可定义 → :1541 并入 session hooks），须复核主路径不产生
     * 双发（executeEventAll → executeSessionHooks 唯一执行路径）。Java 生产 SessionEnd 发射点唯一
     * （LlmAgentLoop:2203），无独立 clear/compact SessionEnd 路径 → 无双发。
     */
    @Test
    @DisplayName("IMP-HR-07 R-2: SESSION_END 入集合后主路径 session function hook 恰好执行 1 次 + clearSessionHooks")
    void sessionEndRunningUuid_sessionHook_singleFire_thenCleared() {
        String sessionUuid = "00000000-0000-0000-0000-0000000000d7";
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));
        AtomicInteger calls = new AtomicInteger();
        registry.addFunctionHook(sessionUuid.toString(), HookEventType.SESSION_END, null,
            (messages, signal) -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }, "function-blocked", null, null);
        assertThat(registry.getSessionFunctionHooks(sessionUuid.toString(), HookEventType.SESSION_END)
                .get(HookEventType.SESSION_END))
            .as("前置：SESSION_END session function hook 已注册")
            .isNotEmpty();

        com.nexusai.application.agent.LlmAgentLoop.markRunning(sessionUuid);
        try {
            registry.executeSessionEndHooks(sessionUuid.toString(), null, ExitReasons.OTHER, null);
        } finally {
            com.nexusai.application.agent.LlmAgentLoop.markIdle(sessionUuid);
        }

        assertThat(calls.get())
            .as("SESSION_END 入 CC_APP_STATE_PRESENT_EVENTS 后主路径 session function hook 必须恰好执行一次（无 0 发 / 无双发）")
            .isEqualTo(1);
        assertThat(registry.getSessionFunctionHooks(sessionUuid.toString(), HookEventType.SESSION_END)
                .get(HookEventType.SESSION_END))
            .as("SessionEnd 执行后 clearSessionHooks（CC :4136-4140）→ 同一会话不再残留 SESSION_END hook")
            .isNullOrEmpty();
    }

    // ── helpers（镜像 LlmAgentLoopHookMessageInjectionTest）──

    private static HookRegistry registryWithCommandHook(String stdout, String stderr, int exitCode) {
        StubMatcherEngine engine = new StubMatcherEngine();
        engine.setHooks(List.of(new MatchedHook(
            new CommandHook("check.sh", null, null, null, null, null, null, null),
            null, null, null, "settings")));
        HookRegistryDispatchTest.FakeHookProcess proc =
            new HookRegistryDispatchTest.FakeHookProcess(stdout, stderr, exitCode);
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(new CommandHookExecutor(new FakeLauncher(proc), null, null, null, null));
        return registry;
    }
}

package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [reqId MDC 传播] SubagentTool async worker 线程继承父 requestId（真实 async 线程集成测试）。
 *
 * <p>WHY (决策 #65 父 V2/子 V1 工具集分叉): subagent 异步执行在 {@code new Thread(asyncWorker,
 * "async-subagent-...")}（executeAsync）上。logback MDC 在该环境<b>不</b>随 {@code new Thread}
 * 继承（实测 requestId 读 null）→ 修复 = executeAsync 调度线程捕获 {@code MDC.getCopyOfContextMap()}
 * → worker 线程体开头 {@code MDC.setContextMap} → finally restore（对齐 LlmAgentLoop STREAM_EXECUTOR
 * mdcCtx 先例）。修复后 worker 线程 RequestContext.requestId() 继承父（非 null）→
 * isTodoV2Enabled()=true → Task V2 工具集与父会话一致。
 *
 * <p><b>可观测 seam（规则九 · 验证意图）</b>: worker 线程内 {@code log.error("Async subagent ... 失败")}
 * （null LLM provider 抛异常属预期）触发时，logback appender 在<b>worker 线程</b>同步执行 ——
 * 自定义 appender 在 {@code append()} 内直接读 {@code RequestContext}（= worker 线程 MDC），
 * 据此断言 worker 线程的 requestId/sessionId 已回放父值。
 *
 * <p><b>RED 条件</b>: 删除 SubagentTool async worker 的 mdcCtx 回放 → worker 线程
 * RequestContext.requestId() 读 null（logback 不随 new Thread 继承）→ 断言红。
 */
@DisplayName("reqId MDC 传播 · SubagentTool async worker 线程继承父 requestId（真实 async 线程）")
class SubagentToolMdcPropagationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 在日志线程（= worker 线程）内直接读 RequestContext 的探针 appender。 */
    private static final class MdcRecordingAppender extends AppenderBase<ILoggingEvent> {
        final CopyOnWriteArrayList<Recorded> records = new CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) {
            // append() 在产生日志的线程同步执行（logback 默认同步）—— worker 线程日志 =
            //   worker 线程当前 MDC（RequestContext.requestId() 直读，不依赖 event MDC 捕获语义）。
            records.add(new Recorded(
                Thread.currentThread().getName(),
                RequestContext.requestId(),
                RequestContext.sessionId()));
        }
    }

    private record Recorded(String threadName, String reqId, String sessionId) {}

    private Logger subagentLogger;
    private MdcRecordingAppender appender;

    @BeforeEach
    void captureWorkerLogs() {
        subagentLogger = (Logger) LoggerFactory.getLogger(SubagentTool.class);
        subagentLogger.setLevel(Level.DEBUG);
        appender = new MdcRecordingAppender();
        appender.start();
        subagentLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        subagentLogger.detachAppender(appender);
        RequestContext.clear();
    }

    /** minimal tool_use block · subagent_type 缺省（undefined）→ fork path（CC :322）→ forceAsync。 */
    private static ToolUseBlock forkCall() {
        ObjectNode input = JSON.createObjectNode();
        input.put("description", "Fork child task");
        input.put("prompt", "Subagent prompt for fork child");
        return new ToolUseBlock("tool-fork-mdc-test", "Agent", input);
    }

    private static void invokeDoExecute(SubagentTool tool, ToolUseBlock call) {
        try {
            Method m = SubagentTool.class.getDeclaredMethod("doExecute", ToolUseBlock.class,
                com.nexusai.application.agent.tool.ToolUseContext.class,
                java.util.function.Consumer.class,
                Class.forName("com.nexusai.application.agent.subagent.createSubagentContext$AgentOptions"),
                Class.forName("com.nexusai.application.agent.subagent.ForkSubagentMessages$Message"));
            m.setAccessible(true);
            try {
                // async 路径返回 async_launched，不阻塞；下游 null LLM provider 抛异常属预期
                m.invoke(tool, call, null, null, null, null);
            } catch (Throwable ignored) {
                // 断言只依赖 worker 线程日志（MDC 已捕获），忽略下游异常
            }
        } catch (Exception e) {
            throw new AssertionError("doExecute 反射调用失败", e);
        }
    }

    @Test
    @DisplayName("父线程含 sessionId+reqId → async worker 线程 requestId()/sessionId() == 父值（回放传播）")
    void asyncWorkerThread_inheritsParentRequestId() {
        // GIVEN: 父线程（工具池线程，经 StreamingToolExecutor 回放已含 MDC）含 sessionId + reqId
        SubagentTool tool = new SubagentTool();
        // 接通真实 BackgroundTaskRunner → executeAsync 走 async worker 线程分支（非降级同步）
        tool.setBackgroundTaskRunner(new BackgroundTaskRunner(
            new NotificationQueue(), new TaskFrameworkService()));
        RequestContext.set("sess-parent", "req-parent");

        // WHEN: 触发 fork async spawn（默认 SubagentTool fork gate on → forceAsync）
        invokeDoExecute(tool, forkCall());

        // THEN: worker 线程（async-subagent-*）日志时点 RequestContext 已回放父值
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        boolean sawWorkerWithParentMdc = false;
        while (Instant.now().isBefore(deadline) && !sawWorkerWithParentMdc) {
            sawWorkerWithParentMdc = appender.records.stream().anyMatch(r ->
                r.threadName().startsWith("async-subagent-")
                    && "req-parent".equals(r.reqId())
                    && "sess-parent".equals(r.sessionId()));
            if (!sawWorkerWithParentMdc) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        assertThat(sawWorkerWithParentMdc)
            .as("async worker 线程必须继承父线程 requestId/sessionId（MDC 回放）——"
                + "否则 isTodoV2Enabled()=false → 子代理回落 V1 TodoWrite、父 V2/子 V1 工具集分叉")
            .isTrue();
        assertThat(appender.records)
            .as("必须有 async-subagent-* worker 线程的日志（证明 worker 真实执行）")
            .anySatisfy(r -> assertThat(r.threadName()).startsWith("async-subagent-"));
    }

    @Test
    @DisplayName("父线程无 MDC → worker 线程 requestId 保持 null（null 捕获值不注入）")
    void asyncWorkerThread_withoutParentMdc_keepsNull() {
        // WHY: cron/后台/无会话上下文父线程捕获 null → 不 set → worker 线程保持 null（回落 V1，对齐决策 #65）。
        SubagentTool tool = new SubagentTool();
        tool.setBackgroundTaskRunner(new BackgroundTaskRunner(
            new NotificationQueue(), new TaskFrameworkService()));
        RequestContext.clear();

        invokeDoExecute(tool, forkCall());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        boolean sawWorkerEvent = false;
        while (Instant.now().isBefore(deadline) && !sawWorkerEvent) {
            sawWorkerEvent = appender.records.stream()
                .anyMatch(r -> r.threadName().startsWith("async-subagent-"));
            if (!sawWorkerEvent) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        assertThat(sawWorkerEvent)
            .as("worker 必须真实执行（有 async-subagent-* 日志）").isTrue();
        assertThat(appender.records.stream()
                .filter(r -> r.threadName().startsWith("async-subagent-"))
                .allMatch(r -> r.reqId() == null))
            .as("null 捕获值不注入 —— worker 线程 requestId 保持 null（回落语义）").isTrue();
    }
}

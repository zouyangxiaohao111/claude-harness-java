package com.nexusai.application.agent.attachment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.llm.ModelConfigResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [reqId MDC 传播] PdfSubagentParser 后台 worker 线程继承父 requestId（真实后台线程集成测试）。
 *
 * <p>WHY (决策 #65 父 V2/子 V1 工具集分叉 · PDF 子代理路径): {@code startBackgroundParse} /
 * {@code parseSubagent} 用 {@code new Thread("pdf-subagent-parse-*" / "pdf-subagent-bg-*")} 执行
 * {@code runBackgroundWorker}（其内调 {@code SubagentExecutor.execute}）。logback MDC 在该环境
 * <b>不</b>随 {@code new Thread} 继承（实测 requestId 读 null）→ 修复 = 调度线程捕获
 * {@code MDC.getCopyOfContextMap()} → worker 线程体开头 {@code MDC.setContextMap} → finally restore
 * （{@code runWorkerWithMdc}，对齐 SubagentTool async worker 同款模式）。修复后 worker 线程
 * RequestContext.requestId() 继承父（非 null）→ PDF 子代理判交互正确 + 日志带 sessionId/reqId 前缀。
 *
 * <p><b>可观测 seam（规则九 · 验证意图）</b>: worker 线程内 {@code log.debug("PDF subagent 解析:
 * 逐页渲染开始 ...")}（renderPagesWithProgress 入口）与后续失败 {@code log.error("PDF subagent 解析失败 ...")}
 * 触发时，logback appender 在 <b>worker 线程</b>同步执行 —— 自定义 appender 在 {@code append()}
 * 内直接读 {@code RequestContext}（= worker 线程 MDC），据此断言 worker 线程 requestId/sessionId
 * 已回放父值。用不存在的 pdfPath + pageCount=30 直入私有 4 参 {@code startBackgroundParse}（反射，
 * 绕过 getPDFPageCount 的 PDF 页数前置），worker 在逐页渲染处失败 —— 日志仍发生在 worker 线程。
 *
 * <p><b>RED 条件</b>: 删除 PdfSubagentParser worker 的 mdcCtx 捕获/回放 → worker 线程
 * RequestContext.requestId() 读 null（logback 不随 new Thread 继承）→ 断言红。
 */
@DisplayName("reqId MDC 传播 · PdfSubagentParser 后台 worker 线程继承父 requestId（真实后台线程）")
class PdfSubagentParserMdcPropagationTest {

    /** 在日志线程（= PDF worker 线程）内直接读 RequestContext 的探针 appender。 */
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

    private Logger parserLogger;
    private MdcRecordingAppender appender;

    @AfterEach
    void tearDown() {
        if (parserLogger != null) {
            parserLogger.detachAppender(appender);
        }
        RequestContext.clear();
    }

    private PdfSubagentParser newParser() {
        SubagentExecutor subagentExecutor = mock(SubagentExecutor.class);
        ModelConfigResolver modelConfigResolver = mock(ModelConfigResolver.class);
        when(modelConfigResolver.resolveMultimodalModelName()).thenReturn("vision-model");
        BackgroundTaskRunner backgroundTaskRunner = mock(BackgroundTaskRunner.class);
        SdkEventQueue sdkEventQueue = mock(SdkEventQueue.class);
        return new PdfSubagentParser(subagentExecutor, modelConfigResolver,
            backgroundTaskRunner, sdkEventQueue);
    }

    /** 直入私有 4 参 startBackgroundParse（绕过 getPDFPageCount 前置），启动后台 worker 线程。 */
    private static String startBackgroundParse(PdfSubagentParser parser, String pdfPath,
                                               String sessionId, String filename, int pageCount) {
        try {
            Method m = PdfSubagentParser.class.getDeclaredMethod(
                "startBackgroundParse", String.class, String.class, String.class, int.class);
            m.setAccessible(true);
            return (String) m.invoke(parser, pdfPath, sessionId, filename, pageCount);
        } catch (Exception e) {
            throw new AssertionError("startBackgroundParse 反射调用失败", e);
        }
    }

    @Test
    @DisplayName("父线程含 sessionId+reqId → PDF 后台 worker 线程 requestId()/sessionId() == 父值（回放传播）")
    void pdfBgWorkerThread_inheritsParentRequestId() throws Exception {
        parserLogger = (Logger) LoggerFactory.getLogger(PdfSubagentParser.class);
        Level prevLevel = parserLogger.getLevel();
        parserLogger.setLevel(Level.DEBUG);
        appender = new MdcRecordingAppender();
        appender.start();
        parserLogger.addAppender(appender);
        try {
            RequestContext.set("sess-pdf", "req-pdf");
            PdfSubagentParser parser = newParser();
            String taskId = startBackgroundParse(parser, "/nonexistent/report.pdf",
                "sess-1", "report.pdf", 30);
            assertThat(taskId).as("后台 worker 必须启动（返回 taskId）").isNotNull();

            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            boolean sawWorkerWithParentMdc = false;
            while (Instant.now().isBefore(deadline) && !sawWorkerWithParentMdc) {
                sawWorkerWithParentMdc = appender.records.stream().anyMatch(r ->
                    r.threadName().startsWith("pdf-subagent-bg-")
                        && "req-pdf".equals(r.reqId())
                        && "sess-pdf".equals(r.sessionId()));
                if (!sawWorkerWithParentMdc) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            assertThat(sawWorkerWithParentMdc)
                .as("PDF 后台 worker 线程必须继承父线程 requestId/sessionId（MDC 回放）——"
                    + "否则 PDF 子代理线程 requestId()=null → 回落 V1 TodoWrite 工具集 + 日志丢 sessionId/reqId 前缀")
                .isTrue();
            assertThat(appender.records)
                .as("必须有 pdf-subagent-bg-* worker 线程的日志（证明 worker 真实执行）")
                .anySatisfy(r -> assertThat(r.threadName()).startsWith("pdf-subagent-bg-"));
        } finally {
            parserLogger.detachAppender(appender);
            parserLogger.setLevel(prevLevel);
            RequestContext.clear();
        }
    }

    @Test
    @DisplayName("父线程无 MDC → PDF 后台 worker 线程 requestId 保持 null（null 捕获值不注入）")
    void pdfBgWorkerThread_withoutParentMdc_keepsNull() {
        parserLogger = (Logger) LoggerFactory.getLogger(PdfSubagentParser.class);
        Level prevLevel = parserLogger.getLevel();
        parserLogger.setLevel(Level.DEBUG);
        appender = new MdcRecordingAppender();
        appender.start();
        parserLogger.addAppender(appender);
        try {
            // WHY: cron/后台/无会话上下文父线程捕获 null → 不 set → worker 线程保持 null（回落语义）。
            RequestContext.clear();
            PdfSubagentParser parser = newParser();
            String taskId = startBackgroundParse(parser, "/nonexistent/report.pdf",
                "sess-1", "report.pdf", 30);
            assertThat(taskId).as("后台 worker 必须启动（返回 taskId）").isNotNull();

            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            boolean sawWorkerEvent = false;
            while (Instant.now().isBefore(deadline) && !sawWorkerEvent) {
                sawWorkerEvent = appender.records.stream()
                    .anyMatch(r -> r.threadName().startsWith("pdf-subagent-bg-"));
                if (!sawWorkerEvent) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            assertThat(sawWorkerEvent)
                .as("worker 必须真实执行（有 pdf-subagent-bg-* 日志）").isTrue();
            assertThat(appender.records.stream()
                    .filter(r -> r.threadName().startsWith("pdf-subagent-bg-"))
                    .allMatch(r -> r.reqId() == null))
                .as("null 捕获值不注入 —— worker 线程 requestId 保持 null（回落语义）").isTrue();
        } finally {
            parserLogger.detachAppender(appender);
            parserLogger.setLevel(prevLevel);
            RequestContext.clear();
        }
    }
}

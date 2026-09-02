package com.nexusai.application.agent.attachment;

import com.nexusai.application.agent.tool.impl.PdfSupport;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tasks.AsyncAgentResult;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.infra.llm.ModelConfigResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 分页解析器 · R2：{@code >20≤100} 页 subagent 解析（指定多模态/vision 模型）+ {@code >100} 页
 * pdf_reference 轻量注入 + 超时/重活转 {@link BackgroundTaskRunner} 后台执行。
 *
 * <p><b>定位（PDF 三态路由的分页层，用户拍板）</b>：
 * <pre>
 *   ≤20 页       → 主循环 document/image block 直传（R1 已实现，本类不处理，Route.DIRECT 放行）
 *   &gt;20≤100 页   → 本类派 {@link SubagentExecutor} 子代理（指定 {@code resolveMultimodalModelName()}
 *                   vision 模型），for 循环逐页 {@link PdfSupport#extractPDFPages} 渲染页图，
 *                   子代理逐页读图输出每页摘要 → 结构化 {@code {page, summary}} 一次返回主代理
 *   &gt;100 页      → {@code {type:'text', text:'PDF {path} 共 N 页，请用 Read 工具 + pages 参数读取'}}
 *                   轻量注入（对齐 CC attachments.ts:2984-3008 tryGetPDFReference pdf_reference，
 *                   CC 以 {@code PDF_AT_MENTION_INLINE_THRESHOLD}（10）为阈值，Java 三态方案沿用
 *                   {@link PdfSupport#API_PDF_MAX_PAGES}（100，apiLimits.ts:59）作 subagent↔reference 分界）
 * </pre>
 *
 * <p><b>子代理解析机制</b>：主模型（非 vision）收不到 PDF 页图 → 本类先
 * {@code for (p=1..N) PdfSupport.extractPDFPages(pdfPath, outputDir, p, p)} 逐页渲染 JPEG 页图
 * （对齐 CC pdf.ts pdftoppm 语义 + ReadFileTool pages 分支），再派 general-purpose 子代理
 * （modelOverride = 多模态档位模型名，CC model.ts 语义），prompt 指示子代理用 Read 工具逐页读取
 * {@code page-XX.jpg} 并输出每页摘要。结构化解析用确定性正则（CLAUDE.md 规则五：模型仅用于裁量，
 * 数据转换由代码完成）。
 *
 * <p><b>超时/重活 → 后台（R2 交付项 3）</b>：
 * <ul>
 *   <li><b>重活</b>：页数 &gt; {@link #BACKGROUND_PAGE_THRESHOLD}（50）→ 直接
 *       {@link #startBackgroundParse}（注册 {@link BackgroundTaskRunner} + task_started / 逐页
 *       task_progress / 终态 task_notification SDK 事件，前端可见）。</li>
 *   <li><b>超时</b>：{@link #parseSubagent} 前台等待 {@link #SYNC_TIMEOUT_MS} 未完成 → 同一后台任务
 *       继续执行（不重启不双发），前台返回 backgrounded=true，结果经 task_notification 异步到达。</li>
 * </ul>
 *
 * <p><b>CC 对齐对照</b>：
 * <table>
 *   <tr><th>本类元素</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>subagent 解析（多模态模型）</td><td>runAgent.ts modelOverride / vision 模型读图</td><td>runAgent.ts:667-695</td></tr>
 *   <tr><td>pdf_reference</td><td>tryGetPDFReference</td><td>attachments.ts:2984-3018</td></tr>
 *   <tr><td>页图渲染</td><td>extractPDFPages（pdftoppm）</td><td>pdf.ts:179-300</td></tr>
 *   <tr><td>task_started/progress/notification</td><td>sdkEventQueue.ts / sdkProgress.ts</td><td>sdkEventQueue.ts:6-54 / sdkProgress.ts:10-36</td></tr>
 * </table>
 *
 * <p>本类为 {@code @Component}（attachment 包自动扫描）；依赖 {@code subagentExecutor} @Bean
 * （ToolRegistrationConfig）、{@code backgroundTaskRunner}/{@code sdkEventQueue} @Bean（TaskConfiguration）、
 * {@code modelConfigResolver} @Component。构造依赖 backgroundTaskRunner 加 {@code @Lazy}（对齐
 * subagentExecutor @Bean 的循环依赖破圈先例）。
 */
@Component
public class PdfSubagentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfSubagentParser.class);

    // ════════════════════════════════════════════════════════════════════
    // 阈值常量 · 对齐 PdfSupport / CC apiLimits.ts
    // ════════════════════════════════════════════════════════════════════

    /**
     * subagent 解析页数下限 · {@code pageCount > 该值} 走 subagent 解析。
     * 对齐 {@link PdfSupport#PDF_MAX_PAGES_PER_READ}（apiLimits.ts:77 单次 pages 参数最多 20 页）——
     * ≤20 页由主循环 document/image block 直传（R1），超过则单次 Read 无法承载，交子代理逐页解析。
     */
    public static final int SUBAGENT_PAGE_THRESHOLD = PdfSupport.PDF_MAX_PAGES_PER_READ;

    /**
     * pdf_reference 页数上限 · {@code pageCount > 该值} 走 pdf_reference 轻量注入。
     * 对齐 {@link PdfSupport#API_PDF_MAX_PAGES}（apiLimits.ts:59 API 侧硬限制 100 页）——
     * 超过 100 页既无法 document 块直传也无法逐页子代理解析（成本过高），仅注入 Read 工具指引。
     */
    public static final int REFERENCE_PAGE_THRESHOLD = PdfSupport.API_PDF_MAX_PAGES;

    /** 后台化页数阈值 · {@code pageCount > 该值}（重活）直接转 {@link BackgroundTaskRunner} 后台，不等前台。 */
    public static final int BACKGROUND_PAGE_THRESHOLD = 50;

    /** 前台同步解析超时（毫秒）· 超过则同一后台任务继续执行（超时 → 后台，不重启不双发）。 */
    public static final long SYNC_TIMEOUT_MS = 120_000;

    /** 子代理类型 · 复用 CC 内置通用子代理（general-purpose，含 Read 工具可读页图）。 */
    public static final String SUBAGENT_TYPE = "general-purpose";

    /** 每页摘要结构化行正则 · {@code PAGE <n>: <summary>}（确定性解析，CLAUDE.md 规则五）。 */
    static final Pattern PAGE_LINE = Pattern.compile("^PAGE\\s+(\\d+)\\s*:\\s*(.+)$");

    // ════════════════════════════════════════════════════════════════════
    // 路由判定产物
    // ════════════════════════════════════════════════════════════════════

    /**
     * 三态路由 · DIRECT=主循环 document/image 直传（R1）；SUBAGENT=本类派子代理；REFERENCE=pdf_reference；
     * ERROR=无法确定页数/异常。
     */
    public enum Route { DIRECT, SUBAGENT, REFERENCE, ERROR }

    /**
     * 路由判定结果。
     *
     * @param route         三态路由
     * @param pageCount     实测页数（ERROR 时为 0）
     * @param referenceText 仅 {@code route==REFERENCE} 时填充的 pdf_reference 文本（{@code type:'text'} 载荷）
     */
    public record RouteDecision(Route route, int pageCount, String referenceText) {}

    /** 单页摘要 · {@code {page, summary}}（CC/任务契约的每页结构化输出）。 */
    public record PageSummary(int page, String summary) {}

    /**
     * subagent 解析结果 · 一次返回主代理。
     *
     * @param success       是否成功（后台启动成功也视为 true，结果经 task_notification 异步到达）
     * @param backgrounded  true = 已转后台（重活/超时），结果经 {@link #taskId} 的 task 事件/输出文件异步消费
     * @param taskId        后台任务 id（backgrounded=true 时填充；同步完成时亦为注册任务 id）
     * @param error         失败原因（success=false 时填充）
     * @param pageCount     PDF 页数
     * @param pages         结构化每页摘要（同步完成时填充；backgrounded 时为空，读 task 输出）
     * @param summaryText   子代理原始结论文本（含 OVERVIEW 概述）
     */
    public record SubagentParseResult(
            boolean success,
            boolean backgrounded,
            String taskId,
            String error,
            int pageCount,
            List<PageSummary> pages,
            String summaryText) {

        public static SubagentParseResult failure(String error, int pageCount) {
            return new SubagentParseResult(false, false, null, error, pageCount, List.of(), null);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 依赖 · @Component 自动扫描
    // ════════════════════════════════════════════════════════════════════

    /** 子代理执行器（ToolRegistrationConfig @Bean，含完整工具注册表/fork 接线）。 */
    private final SubagentExecutor subagentExecutor;

    /** 共享配置解析器（多模态档位模型名单一来源）。 */
    private final ModelConfigResolver modelConfigResolver;

    /** 后台任务运行器（TaskConfiguration @Bean；@Lazy 破圈，对齐 subagentExecutor @Bean 先例）。 */
    private final BackgroundTaskRunner backgroundTaskRunner;

    /** SDK 事件队列（task_started/progress/notification 前端可见通道）。 */
    private final SdkEventQueue sdkEventQueue;

    public PdfSubagentParser(SubagentExecutor subagentExecutor,
                             ModelConfigResolver modelConfigResolver,
                             @Lazy BackgroundTaskRunner backgroundTaskRunner,
                             SdkEventQueue sdkEventQueue) {
        this.subagentExecutor = subagentExecutor;
        this.modelConfigResolver = modelConfigResolver;
        this.backgroundTaskRunner = backgroundTaskRunner;
        this.sdkEventQueue = sdkEventQueue;
    }

    // ════════════════════════════════════════════════════════════════════
    // ① 路由判定
    // ════════════════════════════════════════════════════════════════════

    /**
     * 纯路由判定（可单测）· 按页数三态分发。
     *
     * @param pageCount 实测页数（null = 无法确定 → ERROR）
     * @return 三态路由（REFERENCE 的 referenceText 由调用方经 {@link #buildPdfReferenceText} 补全）
     */
    public static RouteDecision decideRoute(Integer pageCount) {
        if (pageCount == null) {
            return new RouteDecision(Route.ERROR, 0, null);
        }
        if (pageCount <= SUBAGENT_PAGE_THRESHOLD) {
            // ≤20 页 · document/image 块直传（R1，本类不处理）
            return new RouteDecision(Route.DIRECT, pageCount, null);
        }
        if (pageCount > REFERENCE_PAGE_THRESHOLD) {
            // >100 页 · pdf_reference 轻量注入（CC attachments.ts:2984-3018 tryGetPDFReference 等价）
            return new RouteDecision(Route.REFERENCE, pageCount, null);
        }
        // >20≤100 页 · 派子代理逐页解析
        return new RouteDecision(Route.SUBAGENT, pageCount, null);
    }

    /**
     * 路由判定入口 · 读 PDF 页数（{@link PdfSupport#getPDFPageCount}）后三态分发。
     * REFERENCE 路由填充 {@link #buildPdfReferenceText} 的 {@code {type:'text'}} 载荷。
     *
     * @param pdfPath PDF 磁盘绝对路径
     * @return 路由判定（含 REFERENCE 文本）
     */
    public RouteDecision decide(String pdfPath) {
        Integer pageCount = PdfSupport.getPDFPageCount(Path.of(pdfPath));
        RouteDecision d = decideRoute(pageCount);
        if (d.route() == Route.REFERENCE) {
            RouteDecision withRef = new RouteDecision(Route.REFERENCE, d.pageCount(),
                buildPdfReferenceText(pdfPath, d.pageCount()));
            if (log.isDebugEnabled()) {
                log.debug("PDF 路由判定: pageCount={} route=REFERENCE referenceText='{}'（R2 >100 页 pdf_reference）",
                    d.pageCount(), withRef.referenceText());
            }
            return withRef;
        }
        if (log.isDebugEnabled()) {
            log.debug("PDF 路由判定: pageCount={} route={}（R2 分页路由：≤20 DIRECT / >20≤100 SUBAGENT / >100 REFERENCE）",
                d.pageCount(), d.route());
        }
        return d;
    }

    /**
     * pdf_reference 轻量注入文本 · 对齐 CC {@code tryGetPDFReference}（attachments.ts:2984-3018）的
     * {@code {type:'pdf_reference', ...}} 降级形态。任务契约：{@code {type:'text',
     * text:'PDF {path} 共 N 页，请用 Read 工具 + pages 参数读取'}}——主代理读到该文本后自行用
     * Read 工具 + pages 参数分页读取（≤20 页/次，apiLimits.ts:77），避免 >100 页全量注入击穿上下文。
     *
     * @param pdfPath   PDF 磁盘绝对路径
     * @param pageCount 实测页数（> {@link #REFERENCE_PAGE_THRESHOLD}）
     * @return {@code type:'text'} 载荷文本
     */
    public static String buildPdfReferenceText(String pdfPath, int pageCount) {
        return "PDF " + pdfPath + " 共 " + pageCount + " 页，请用 Read 工具 + pages 参数读取";
    }

    // ════════════════════════════════════════════════════════════════════
    // ② subagent 解析（>20≤100 页）
    // ════════════════════════════════════════════════════════════════════

    /**
     * subagent 解析主入口 · 页数 &gt; {@link #BACKGROUND_PAGE_THRESHOLD}（重活）直接转后台；
     * 否则前台执行，超过 {@link #SYNC_TIMEOUT_MS} 未完成转同一后台任务（超时 → 后台，不重启不双发）。
     *
     * <p><b>统一通道</b>：SUBAGENT 路由<b>总是</b>先注册 {@link BackgroundTaskRunner} 后台任务
     * （对齐 CC sync 子代理 registerAgentForeground 语义 + task_started 事件），前台只决定「等多久」：
     * 轻量等待返回结构化结果；超时/重活立即返回 backgrounded=true，结果经 task_notification 异步到达。
     *
     * @param pdfPath   PDF 磁盘绝对路径
     * @param sessionId 会话 id（task 通知注入创建会话回合）
     * @param filename  PDF 原始文件名（显示名，可 null）
     * @return 结构化解析结果（成功/后台化/失败）
     */
    public SubagentParseResult parseSubagent(String pdfPath, String sessionId, String filename) {
        Integer pageCount = PdfSupport.getPDFPageCount(Path.of(pdfPath));
        if (pageCount == null || pageCount <= SUBAGENT_PAGE_THRESHOLD
                || pageCount > REFERENCE_PAGE_THRESHOLD) {
            log.warn("PDF 页数不在 subagent 解析范围，放弃: pdfPath={} pageCount={}（R2 需 >20≤100 页）",
                pdfPath, pageCount);
            return SubagentParseResult.failure(
                "PDF 页数超出 subagent 解析范围（需 20<页数≤100）: " + pageCount,
                pageCount == null ? 0 : pageCount);
        }
        if (pageCount > BACKGROUND_PAGE_THRESHOLD) {
            if (log.isDebugEnabled()) {
                log.debug("PDF subagent 解析判定重活转后台: pdfPath={} pages={} > {}（R2 BackgroundTaskRunner）",
                    pdfPath, pageCount, BACKGROUND_PAGE_THRESHOLD);
            }
            String taskId = startBackgroundParse(pdfPath, sessionId, filename, pageCount);
            return taskId != null
                ? new SubagentParseResult(true, true, taskId, null, pageCount, List.of(), null)
                : SubagentParseResult.failure("后台解析启动失败", pageCount);
        }

        // 前台执行 + 超时转后台（同一后台任务，不重启）
        String model = resolveMultimodalModel();
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        String description = buildDescription(filename, pageCount);
        if (backgroundTaskRunner == null) {
            log.error("PDF subagent 解析: BackgroundTaskRunner 未注入（@Lazy 未解析），无法注册后台任务 pdfPath={}",
                pdfPath);
            return SubagentParseResult.failure("BackgroundTaskRunner 未注入", pageCount);
        }
        backgroundTaskRunner.registerAsyncAgent(agentId, description,
            buildPromptPreview(pdfPath, pageCount), SUBAGENT_TYPE, null, sessionId);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<SubagentParseResult> resultRef = new AtomicReference<>();
        // [reqId MDC 传播] 调度线程（会话线程）捕获 MDC context map → worker 线程回放
        //   （实测：logback MDC 不随 new Thread 继承，须显式回放；对齐 SubagentTool async worker 同款模式）。
        //   WHY: worker 线程（runBackgroundWorker → SubagentExecutor.execute）RequestContext.requestId()=null
        //   → PDF 子代理线程回落 V1 TodoWrite 工具集 + 日志丢 sessionId/reqId 前缀。
        final java.util.Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
        Thread worker = new Thread(
            () -> runWorkerWithMdc(mdcCtx,
                () -> runBackgroundWorker(taskId, pdfPath, sessionId, pageCount, model, done, resultRef)),
            "pdf-subagent-parse-" + taskId);
        worker.setDaemon(true);
        worker.start();
        if (log.isDebugEnabled()) {
            log.debug("PDF subagent 解析前台执行已启动: taskId={} pdfPath={} pages={}（等 {}ms 未完成转后台）",
                taskId, pdfPath, pageCount, SYNC_TIMEOUT_MS);
        }

        boolean finished;
        try {
            finished = done.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            if (log.isDebugEnabled()) {
                log.debug("PDF subagent 解析前台等待超时({}ms)，转后台执行: taskId={}（R2 超时 → 后台，worker 继续，结果经 task_notification 异步到达）",
                    SYNC_TIMEOUT_MS, taskId);
            }
            return new SubagentParseResult(true, true, taskId, null, pageCount, List.of(), null);
        }
        SubagentParseResult result = resultRef.get();
        return result != null
            ? result
            : SubagentParseResult.failure("subagent 解析 worker 异常退出", pageCount);
    }

    /**
     * 直接后台解析入口（重活 > {@link #BACKGROUND_PAGE_THRESHOLD} 或调用方主动后台化）·
     * 注册 {@link BackgroundTaskRunner} 任务（task_started）→ daemon worker 逐页渲染（task_progress）
     * → 子代理解析 → 终态（task_notification）。立即返回 taskId，不阻塞前台。
     *
     * @param pdfPath   PDF 磁盘绝对路径
     * @param sessionId 会话 id
     * @param filename  PDF 原始文件名（可 null）
     * @return 后台任务 id（= agentId.toString()）；页数不在 subagent 范围/未注入 → null
     */
    public String startBackgroundParse(String pdfPath, String sessionId, String filename) {
        Integer pageCount = PdfSupport.getPDFPageCount(Path.of(pdfPath));
        if (pageCount == null || pageCount <= SUBAGENT_PAGE_THRESHOLD
                || pageCount > REFERENCE_PAGE_THRESHOLD) {
            log.warn("PDF 页数不在 subagent 解析范围，无法后台解析: pdfPath={} pageCount={}（R2 需 >20≤100 页）",
                pdfPath, pageCount);
            return null;
        }
        return startBackgroundParse(pdfPath, sessionId, filename, pageCount);
    }

    /** 后台解析内部入口 · 页数已知时复用（parseSubagent 重活路径调用）。 */
    private String startBackgroundParse(String pdfPath, String sessionId, String filename, int pageCount) {
        if (backgroundTaskRunner == null) {
            log.error("PDF subagent 解析: BackgroundTaskRunner 未注入（@Lazy 未解析），无法后台解析 pdfPath={}",
                pdfPath);
            return null;
        }
        String model = resolveMultimodalModel();
        UUID agentId = UUID.randomUUID();
        String taskId = agentId.toString();
        String description = buildDescription(filename, pageCount);
        backgroundTaskRunner.registerAsyncAgent(agentId, description,
            buildPromptPreview(pdfPath, pageCount), SUBAGENT_TYPE, null, sessionId);
        // [reqId MDC 传播] 调度线程（会话线程）捕获 MDC context map → worker 线程回放
        //   （实测：logback MDC 不随 new Thread 继承，须显式回放；对齐 SubagentTool async worker 同款模式）。
        //   WHY: 同 parseSubagent 前台 worker —— PDF 子代理线程 RequestContext.requestId()=null
        //   → 回落 V1 TodoWrite 工具集 + 日志丢 sessionId/reqId 前缀。
        final java.util.Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
        Thread worker = new Thread(
            () -> runWorkerWithMdc(mdcCtx,
                () -> runBackgroundWorker(taskId, pdfPath, sessionId, pageCount, model, null, null)),
            "pdf-subagent-bg-" + taskId);
        worker.setDaemon(true);
        worker.start();
        log.info("PDF subagent 解析已转后台: taskId={} pdfPath={} pages={}（R2 BackgroundTaskRunner + task_started/progress/notification）",
            taskId, pdfPath, pageCount);
        return taskId;
    }

    // ════════════════════════════════════════════════════════════════════
    // ③ 后台 worker（渲染 + 子代理执行 + 终态化）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 后台 worker 体 · 被 parseSubagent / startBackgroundParse 的 daemon 线程共用。
     *
     * <p>流程：逐页 {@link PdfSupport#extractPDFPages} 渲染页图（每页 task_progress）→ 构造
     * subagent prompt（含页图目录）→ {@link SubagentExecutor#execute} 指定多模态模型 → 确定性正则
     * 解析每页摘要 → {@code completeAsyncAgent}/{@code failAsyncAgent}（task_notification + 输出文件）。
     *
     * @param taskId    后台任务 id（= agentId.toString()）
     * @param pdfPath   PDF 磁盘绝对路径
     * @param sessionId 会话 id（进度事件归属）
     * @param pageCount PDF 页数
     * @param model     子代理模型（多模态档位模型名）
     * @param done      前台等待 latch（前台路径传入，直接后台传 null）
     * @param resultRef 前台结果回传（前台路径传入，直接后台传 null）
     */
    private void runBackgroundWorker(String taskId, String pdfPath, String sessionId, int pageCount,
                                     String model, CountDownLatch done,
                                     AtomicReference<SubagentParseResult> resultRef) {
        long startMs = System.currentTimeMillis();
        try {
            // for 循环逐页 extractPDFPages 渲染页图（每页 task_progress，前端可见进度）
            Path outputDir = renderPagesWithProgress(taskId, sessionId, pdfPath, pageCount, startMs);
            String prompt = buildSubagentPrompt(pdfPath, outputDir, pageCount);
            if (log.isDebugEnabled()) {
                log.debug("PDF subagent 解析: 派子代理开始 taskId={} model={} outputDir={}（R2 指定多模态模型）",
                    taskId, model, outputDir);
            }
            SubagentExecutor.SubagentResult res = subagentExecutor.execute(
                prompt, SUBAGENT_TYPE, model, null);
            List<PageSummary> pages = parsePages(res.summaryText());
            String structured = formatStructured(pages, res.summaryText());
            long durationMs = System.currentTimeMillis() - startMs;
            // 终态化 → task_notification SDK 事件 + outputFile 写结构化结果（completeAsyncAgent 内部发射）
            backgroundTaskRunner.completeAsyncAgent(taskId,
                AsyncAgentResult.success(structured, 0, durationMs, taskId));
            log.info("PDF subagent 解析完成: taskId={} pages={} durationMs={}（R2 结果一次返回主代理）",
                taskId, pages.size(), durationMs);
            if (resultRef != null) {
                resultRef.set(new SubagentParseResult(true, false, taskId, null,
                    pageCount, pages, structured));
            }
        } catch (Exception e) {
            log.error("PDF subagent 解析失败: taskId={} 原因={}（R2 failAsyncAgent）", taskId, e.toString());
            backgroundTaskRunner.failAsyncAgent(taskId, "PDF subagent 解析失败: " + e.getMessage());
            if (resultRef != null) {
                resultRef.set(SubagentParseResult.failure("PDF subagent 解析失败: " + e.getMessage(), pageCount));
            }
        } finally {
            if (done != null) {
                done.countDown();
            }
        }
    }

    /**
     * [reqId MDC 传播] worker 线程体包 MDC 回放 · 对齐 SubagentTool async worker 同款成对模式。
     *
     * <p>WHY: logback MDC 不随 {@code new Thread} 继承（纯 ThreadLocal，实测）→ PDF 子代理线程
     * {@code RequestContext.requestId()}=null → 子代理回落 V1 TodoWrite、父 V2/子 V1 工具集分叉
     * （决策 #65）+ 日志丢 sessionId/reqId 前缀。调度线程（本调用方）捕获父 MDC → worker 线程体
     * 开头 {@code setContextMap} → finally restore（成对，防线程复用泄漏；null 捕获值不注入）。
     *
     * @param mdcCtx  调度线程捕获的父 MDC context map（null 捕获值不注入，保持 worker 原状）
     * @param worker  worker 线程体
     */
    private static void runWorkerWithMdc(java.util.Map<String, String> mdcCtx, Runnable worker) {
        java.util.Map<String, String> prevMdc = MDC.getCopyOfContextMap();
        if (mdcCtx != null) {
            MDC.setContextMap(mdcCtx);
        }
        try {
            worker.run();
        } finally {
            if (prevMdc != null) {
                MDC.setContextMap(prevMdc);
            } else {
                MDC.clear();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ④ 页图渲染 / prompt 构造 / 结构化解析（确定性代码，CLAUDE.md 规则五）
    // ════════════════════════════════════════════════════════════════════

    /**
     * for 循环逐页渲染页图 · {@code for (p=1..N) PdfSupport.extractPDFPages(pdfPath, outputDir, p, p)}
     * → {@code outputDir/page-%02d.jpg}。每页渲染后经 {@link SdkEventQueue#emitTaskProgress} 发射
     * task_progress（前端可见逐页进度）。任一页渲染失败 → 抛异常（fail-loud，不静默跳过）。
     *
     * @param taskId    后台任务 id（null=无任务上下文，跳过进度事件）
     * @param sessionId 会话 id
     * @param pdfPath   PDF 磁盘绝对路径
     * @param pageCount 总页数
     * @param startMs   任务开始时间（duration_ms 计算）
     * @return 页图输出目录（{@code page-%02d.jpg} 所在）
     */
    private Path renderPagesWithProgress(String taskId, String sessionId, String pdfPath,
                                         int pageCount, long startMs) throws IOException {
        Path outputDir = Files.createTempDirectory("pdf-subagent-pages-");
        if (log.isDebugEnabled()) {
            log.debug("PDF subagent 解析: 逐页渲染开始 pdfPath={} pages={} outputDir={}（R2 for 循环逐页 extractPDFPages）",
                pdfPath, pageCount, outputDir);
        }
        for (int p = 1; p <= pageCount; p++) {
            PdfSupport.PdfExtractResult r = PdfSupport.extractPDFPages(Path.of(pdfPath), outputDir, p, p);
            if (!r.success()) {
                throw new IllegalStateException("PDF 页图渲染失败 page=" + p
                    + " reason=" + r.error().reason() + " message=" + r.error().message());
            }
            if (log.isDebugEnabled()) {
                log.debug("PDF subagent 解析: 第 {}/{} 页渲染完成 outputDir={}（逐页 extractPDFPages）",
                    p, pageCount, outputDir);
            }
            if (taskId != null && sdkEventQueue != null) {
                sdkEventQueue.emitTaskProgress(taskId, null, "PDF subagent 解析",
                    startMs, 0, p, "extractPDFPages", "已渲染第 " + p + "/" + pageCount + " 页");
            }
        }
        return outputDir;
    }

    /**
     * 子代理 prompt · 对齐任务契约 {@code prompt='逐页解析 PDF {path}，输出每页摘要/文本'}，
     * 追加页图目录 + 每页一行的结构化输出格式（确定性解析锚点 PAGE N: ...）。
     */
    private static String buildSubagentPrompt(String pdfPath, Path outputDir, int pageCount) {
        return "逐页解析 PDF " + pdfPath + "，输出每页摘要/文本。"
            + "该 PDF 共 " + pageCount + " 页，页图已渲染至 " + outputDir
            + "（page-01.jpg ~ page-" + String.format(Locale.ROOT, "%02d", pageCount) + ".jpg）。"
            + "请用 Read 工具逐页读取每张页图，理解每页内容后按以下格式输出（每页一行）：\n"
            + "PAGE 1: <第 1 页摘要>\nPAGE 2: <第 2 页摘要>\n...\n"
            + "最后单独输出一行整体文档概述：OVERVIEW: <文档整体概述>";
    }

    /** 注册期 prompt 预览（outputDir 未知时用于 task 描述/事件，真实 prompt 在 worker 内构造）。 */
    private static String buildPromptPreview(String pdfPath, int pageCount) {
        return "逐页解析 PDF " + pdfPath + "（共 " + pageCount + " 页），输出每页摘要/文本";
    }

    /** 后台任务人类可读描述 · 前端 task 列表展示。 */
    private static String buildDescription(String filename, int pageCount) {
        String name = (filename == null || filename.isBlank()) ? "PDF" : filename;
        return "PDF subagent 解析: " + name + "（" + pageCount + " 页）";
    }

    /**
     * 确定性解析子代理输出 · 逐行匹配 {@code PAGE <n>: <summary>} 提取每页摘要（CLAUDE.md 规则五：
     * 数据转换由代码完成，模型只产出原始文本）。非法行/OVERVIEW 行忽略；未匹配任何行 → 空列表
     * （调用方回退 summaryText 整体摘要）。
     *
     * @param text 子代理结论文本
     * @return 有序每页摘要列表
     */
    static List<PageSummary> parsePages(String text) {
        List<PageSummary> pages = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return pages;
        }
        for (String line : text.split("\\R")) {
            Matcher m = PAGE_LINE.matcher(line.trim());
            if (m.matches()) {
                try {
                    pages.add(new PageSummary(Integer.parseInt(m.group(1)), m.group(2).trim()));
                } catch (NumberFormatException ignored) {
                    // 页号非法行忽略（解析容错，不抛）
                }
            }
        }
        return pages;
    }

    /** 结构化文本组装 · 每页一行 {@code PAGE n: summary}，供 task 输出文件 / 通知承载。 */
    private static String formatStructured(List<PageSummary> pages, String summaryText) {
        if (pages == null || pages.isEmpty()) {
            return summaryText != null ? summaryText : "";
        }
        StringBuilder sb = new StringBuilder();
        for (PageSummary ps : pages) {
            sb.append("PAGE ").append(ps.page()).append(": ").append(ps.summary()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 多模态档位模型解析 · {@link ModelConfigResolver#resolveMultimodalModelName()}（settings
     * multimodalModelName → DB models.name，vision 模型）。未配置/未命中 → null（execute 回落
     * AgentDefinition 模型，fail-loud warn 提示 vision 缺失）。
     */
    private String resolveMultimodalModel() {
        String name = modelConfigResolver != null ? modelConfigResolver.resolveMultimodalModelName() : null;
        if (name == null || name.isBlank()) {
            log.warn("PDF subagent 解析: 多模态档位 multimodalModelName 未配置/未命中，子代理回落默认模型"
                + "（vision 缺失可能无法读页图，建议配置 settings.multimodalModelName）");
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("PDF subagent 解析: 指定多模态模型 model={}（R2 resolveMultimodalModelName）", name);
        }
        return name;
    }
}

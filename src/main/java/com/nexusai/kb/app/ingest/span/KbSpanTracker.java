package com.nexusai.kb.app.ingest.span;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbProcessingSpanRecord;
import com.nexusai.kb.repo.mapper.KbProcessingSpanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库处理阶段 span 树的写入口 · 对齐源站
 * {@code D:/tmp/WeKnora/internal/application/service/knowledge_span_tracker.go}。
 *
 * <p>生命周期与源站一致：
 * <pre>
 *   AttemptRoot a = tracker.openAttempt(knowledgeId);           // 建 root（kind=root, status=running）
 *   KbSpan stage  = tracker.beginStage(kid, a.attempt(), "chunking", input);
 *   ... 干活 ...
 *   tracker.endSpan(stage, output);          // done
 *   tracker.failSpan(stage, code, msg, err); // failed + 级联取消下游 stage
 *   tracker.skipSpan(stage, reason);         // skipped
 *   tracker.finalizeAttempt(kid, a.attempt(), "done", output);  // root 收口
 *   tracker.abortAttempt(kid, a.attempt(), "用户取消");         // 全部未终态置 cancelled
 * </pre>
 *
 * <p><b>永不抛</b>：DB 写失败只 {@code log.warn} 后继续 —— trace 是旁路观测层，
 * 不能因为写 span 失败就让入库失败。{@code knowledge.parse_status} 才是完成度的真源
 * （源站包注释原话）。
 *
 * <p><b>⛔ 不加 {@code @Transactional}</b>：span 必须在写入后立刻对别的线程 / 进程可见
 * （前端轮询时间线），也不能跟着业务事务一起回滚。
 *
 * <h2>⭐ 与源站的已知偏离（本批有意为之）</h2>
 * <ol>
 *   <li><b>spanId 用 {@link UUID#randomUUID()} 的带横线形态</b>（36 字符）。源站
 *       {@code newSpanID()} 是 uuid 去掉横线的 32 位 hex。两者都满足「同 attempt 内唯一」，
 *       本仓选带横线版只为与其它表 / 日志里的 UUID 形态一致，便于肉眼比对。</li>
 *   <li><b>不做 {@code fitSpanName}</b>（源站按 255 rune 截断 + 8 位 hash 后缀）：
 *       本仓 {@code kb_processing_span.name} 是 SQLite TEXT 无长度上限，截断只会让
 *       {@link #lookupSpanByName} 的名字对不上调用方。名字按原文落库。</li>
 *   <li><b>没有 {@code touchKnowledgeHeartbeat}</b>（源站每次 span 变更顺手 UPDATE
 *       knowledge.updated_at）：本表自带 {@code update_time}，批 2 的 stall 判定按
 *       {@code MAX(update_time)} 现算，不必再打业务热行。</li>
 *   <li><b>无 langfuse trace id 入参</b>：源站 {@code OpenAttempt(ctx, kid, traceID)} 会写
 *       {@code metadata.langfuse_trace_id}；本仓不接 langfuse，metadata 留空。</li>
 *   <li><b>{@link #finalizeAttempt} 会把仍 running/pending 的子 span 一并收口</b>：
 *       按派单书 2.5 / 用例 8 实现；源站 939 行的 FinalizeAttempt 只写 root 一行
 *       （子 span 靠 cascade / Abort 收）。多这一步是为了成功收口路径下不留下永远
 *       running 的孤儿。</li>
 *   <li><b>{@link #failSpanRetryable} 恒定按「调用方还会重试」处理</b>：源站用
 *       {@code attemptStillHasRetry(ctx)} 读 asynq / 任务元数据判断预算，本仓无该上下文；
 *       只保留「不因这次失败而收口 root」这一条对调用方有意义的差别，
 *       真正会不会重试由批 2 的 worker 决定（本批不做重试调度）。</li>
 *   <li><b>时长口径</b>：优先进程内 {@code nanoTime} 差（{@link KbSpan#elapsedMs()}）；
 *       拿不到起点（{@link KbSpan#fromRecord} 还原出来的跨进程 span）时退回
 *       {@code started_at} 文本差，仍拿不到则 0。源站同款两段回退。</li>
 *   <li><b>时间列</b>用 {@link KbTimeFormat#now()}（{@code yyyy-MM-dd HH:mm:ss}，
 *       与 SQLite {@code datetime('now')} 逐字节同款）。</li>
 * </ol>
 */
@Component
public class KbSpanTracker {

    private static final Logger log = LoggerFactory.getLogger(KbSpanTracker.class);

    /** root span 的固定名字（源站 OpenAttempt 写死 {@code knowledge_processing}）。 */
    private static final String ROOT_NAME = "knowledge_processing";

    /** error_message 上限（源站 1024 字节）。 */
    private static final int MAX_ERROR_MESSAGE_CHARS = 1024;

    /** error_detail 上限（源站 8192 字节）。 */
    private static final int MAX_ERROR_DETAIL_CHARS = 8192;

    /** 级联取消时写入的错误码（源站 CancelDescendants 写死 UPSTREAM_FAILED）。 */
    private static final String UPSTREAM_FAILED = "UPSTREAM_FAILED";

    /** 用户取消的错误码（源站 AbortAttempt 默认值）。 */
    private static final String USER_CANCELLED = "USER_CANCELLED";

    /** 新一轮 attempt 顶掉旧轮时写入的错误码（源站 OpenAttempt）。 */
    private static final String ATTEMPT_SUPERSEDED = "ATTEMPT_SUPERSEDED";

    /** 同名子任务重跑时顶掉旧行写入的错误码（源站 BeginSubSpan）。 */
    private static final String TASK_SUPERSEDED = "TASK_SUPERSEDED";

    /** span 树的最大遍历深度（源站 CancelDescendants 的 depth 上限）。 */
    private static final int MAX_CASCADE_DEPTH = 16;

    /** 五大主流水线阶段（源站 {@code types.AllStages}，顺序即时间线顺序）。 */
    private static final List<String> ALL_STAGES =
            List.of("docreader", "chunking", "embedding", "multimodal", "postprocess");

    /**
     * 阶段上游依赖 —— 源站 {@code internal/types/knowledge_span.go:70 StageDependencies}
     * 的<b>逐条照抄</b>。
     *
     * <p>⚠️ 注意方向：值是「本阶段依赖谁」。源站
     * {@code knowledge_span_tracker.go:717 stagesDependingOn} 正是<b>反着走</b>这张表求
     * 传递闭包 ⇒ 得到「谁因为本阶段失败而废掉」。
     *
     * <p>特别地 multimodal <b>不</b>依赖 embedding（源站 :66 注释原话），两者共享
     * chunking；postprocess 同时依赖 embedding 与 multimodal。
     */
    private static final Map<String, List<String>> STAGE_UPSTREAM = Map.of(
            "docreader", List.of(),
            "chunking", List.of("docreader"),
            "embedding", List.of("chunking"),
            "multimodal", List.of("chunking"),
            "postprocess", List.of("embedding", "multimodal"));

    private final KbProcessingSpanMapper mapper;

    public KbSpanTracker(KbProcessingSpanMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@link #openAttempt(String)} 的返回：root 快照 + 本次 attempt 号。
     *
     * @param root    新建的 root span 快照（建库失败时仍可能有值，只是没落库）
     * @param attempt 本次 attempt 号；文档 ID 为空时为 0
     */
    public record AttemptRoot(KbSpan root, int attempt) {
    }

    // ── attempt 生命周期 ────────────────────────────────────────────

    /**
     * 开一轮新的处理 attempt：{@code latestAttempt+1}，插一条
     * {@code kind=root / status=running / name=knowledge_processing} 的 span。
     *
     * <p>源站 {@code OpenAttempt}：新 attempt 顶掉旧轮 —— 把更早 attempt 里仍
     * pending/running 的行全部置 {@code cancelled}（{@code ATTEMPT_SUPERSEDED}）。
     * 没有这一步，上一轮 worker 猝死后留下的 running root 会永远挂在那里，
     * 与新一轮同时显示成「进行中」。
     *
     * @param knowledgeId 知识条目 ID
     * @return root 快照 + attempt 号；{@code knowledgeId} 为空时 root=null、attempt=0
     */
    public AttemptRoot openAttempt(String knowledgeId) {
        if (isBlank(knowledgeId)) {
            return new AttemptRoot(null, 0);
        }
        int attempt = latestAttempt(knowledgeId) + 1;
        KbSpan root = KbSpan.of(newSpanId(), null, knowledgeId, attempt, ROOT_NAME,
                KbSpanKind.ROOT, KbSpanStatus.RUNNING, null);
        root.startedAt = KbTimeFormat.now();
        try {
            if (attempt > 1) {
                cancelOpenSpansBeforeAttempt(knowledgeId, attempt);
            }
            insertSpan(root);
        } catch (RuntimeException e) {
            // trace 是旁路：落库失败也要把句柄交回去，后续 stage 仍可挂上去
            log.warn("[span] openAttempt 落库失败 knowledge={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
        }
        return new AttemptRoot(root, attempt);
    }

    /**
     * 该文档最大的 attempt 号；无记录 ⇒ 0（源站 {@code LatestAttempt}，读
     * {@code COALESCE(MAX(attempt), 0)}）。
     *
     * @param knowledgeId 知识条目 ID
     * @return 最大 attempt；文档 ID 为空或库不可读时 0
     */
    public int latestAttempt(String knowledgeId) {
        if (isBlank(knowledgeId)) {
            return 0;
        }
        try {
            List<KbProcessingSpanRecord> rows = mapper.selectListByQuery(QueryWrapper.create()
                    .where("knowledge_id = ?", knowledgeId)
                    .orderBy("attempt", false)
                    .limit(1));
            if (rows == null || rows.isEmpty() || rows.get(0).getAttempt() == null) {
                return 0;
            }
            return rows.get(0).getAttempt();
        } catch (RuntimeException e) {
            log.warn("[span] latestAttempt 查询失败 knowledge={}: {}", knowledgeId, e.toString());
            return 0;
        }
    }

    /**
     * 开启一个处理阶段（五大 stage 之一）。
     *
     * <p>源站语义两条：
     * <ol>
     *   <li>{@code parent_span_id} 取本 attempt 的 root.span_id；找不到 root
     *       （老数据 / openAttempt 写失败）时记一条 <b>无父</b> 的 stage 行并 warn
     *       ——「总比什么都不记」；</li>
     *   <li><b>重入不能新建第二行</b>：同 attempt 下已有同名 stage 行时复用它的
     *       {@code span_id}（挂在它下面的子 span 才不会断），把状态重置成 running、
     *       刷新 {@code started_at}、清掉终态字段（output / finished_at / duration_ms /
     *       error*）。否则重试一次时间线上就多出一段同名 stage。</li>
     * </ol>
     *
     * @param knowledgeId 知识条目 ID
     * @param attempt     attempt 号
     * @param stage       阶段名（docreader / chunking / embedding / multimodal / postprocess）
     * @param inputJson   输入快照（JSON 文本，可 null）
     * @return stage span 快照；入参非法或写库失败时 null（调用方必须容忍 null）
     */
    public KbSpan beginStage(String knowledgeId, int attempt, String stage, String inputJson) {
        if (isBlank(knowledgeId) || isBlank(stage) || attempt <= 0) {
            return null;
        }
        try {
            List<KbProcessingSpanRecord> rows = listByAttempt(knowledgeId, attempt);
            String rootId = null;
            KbProcessingSpanRecord existing = null;
            for (KbProcessingSpanRecord row : rows) {
                if (rootId == null && KbSpanKind.ROOT.equals(row.getKind())) {
                    rootId = row.getSpanId();
                }
                if (KbSpanKind.STAGE.equals(row.getKind()) && stage.equals(row.getName())) {
                    existing = row;
                }
            }
            String now = KbTimeFormat.now();
            if (existing != null) {
                KbSpan span = KbSpan.of(existing.getSpanId(), existing.getParentSpanId(),
                        knowledgeId, attempt, stage, KbSpanKind.STAGE, KbSpanStatus.RUNNING, inputJson);
                span.startedAt = now;
                Map<String, Object> cols = new LinkedHashMap<>();
                cols.put("status", KbSpanStatus.RUNNING);
                if (inputJson != null) {
                    // 源站 re-entry 的行不带 Input 时不写该列 ⇒ 保留上一轮的输入
                    cols.put("input", inputJson);
                }
                cols.put("output", null);
                cols.put("error_code", null);
                cols.put("error_message", null);
                cols.put("error_detail", null);
                cols.put("started_at", now);
                cols.put("finished_at", null);
                cols.put("duration_ms", null);
                updateSpan(span, cols);
                return span;
            }
            if (rootId == null) {
                log.warn("[span] beginStage 找不到 root，按无父 stage 记录 knowledge={} attempt={} stage={}",
                        knowledgeId, attempt, stage);
            }
            KbSpan span = KbSpan.of(newSpanId(), rootId, knowledgeId, attempt, stage,
                    KbSpanKind.STAGE, KbSpanStatus.RUNNING, inputJson);
            span.startedAt = now;
            insertSpan(span);
            return span;
        } catch (RuntimeException e) {
            log.warn("[span] beginStage 写库失败 knowledge={} attempt={} stage={}: {}",
                    knowledgeId, attempt, stage, e.toString());
            return null;
        }
    }

    /**
     * 在 parent 下开一条子 span（{@code subspan} 或 {@code generation}）。
     *
     * <p><b>⭐ parent == null ⇒ 返回 null 且<b>不写库</b></b>（源站
     * {@code BeginSubSpan} 的第一行判据）。调用方必须容忍 null：某个 stage 的 span
     * 没建成时，它的子任务照样要把活干完，不能因此抛异常。
     *
     * <p>源站另外两条语义保留：① kind 只认 subspan / generation，其余一律降级成
     * subspan；② 同名子任务重跑前把仍 pending/running 的同名行置 cancelled
     * （{@code TASK_SUPERSEDED}），否则 asynq 重试 / 进程重启会让时间线上出现
     * 两条同名锯齿。
     *
     * @param parent    父 span（null ⇒ 直接返回 null）
     * @param name      子任务名（例：{@code multimodal.image[0]}）
     * @param kind      {@link KbSpanKind#SUBSPAN} 或 {@link KbSpanKind#GENERATION}
     * @param inputJson 输入快照（JSON 文本，可 null）
     * @return 子 span 快照；parent 为 null、name 为空或写库失败时 null
     */
    public KbSpan beginSubSpan(KbSpan parent, String name, String kind, String inputJson) {
        if (parent == null || isBlank(name)) {
            return null;
        }
        try {
            String actualKind = (KbSpanKind.GENERATION.equals(kind) || KbSpanKind.SUBSPAN.equals(kind))
                    ? kind : KbSpanKind.SUBSPAN;
            cancelOpenSpansByName(parent.knowledgeId, parent.attempt, name);
            String now = KbTimeFormat.now();
            KbSpan span = KbSpan.of(newSpanId(), parent.spanId, parent.knowledgeId,
                    parent.attempt, name, actualKind, KbSpanStatus.RUNNING, inputJson);
            span.startedAt = now;
            insertSpan(span);
            return span;
        } catch (RuntimeException e) {
            log.warn("[span] beginSubSpan 写库失败 parent={} name={}: {}",
                    parent.spanId, name, e.toString());
            return null;
        }
    }

    /**
     * 成功收口：{@code status=done} + {@code finished_at} + {@code duration_ms}。
     *
     * @param span       要收口的 span（null 安全）
     * @param outputJson 输出快照（JSON 文本，可 null）
     */
    public void endSpan(KbSpan span, String outputJson) {
        if (span == null) {
            return;
        }
        try {
            String now = KbTimeFormat.now();
            span.status = KbSpanStatus.DONE;
            span.output = outputJson;
            span.finishedAt = now;
            span.durationMs = elapsedMs(span);
            upsert(span, terminalColumns(span));
            logTerminal(span);
        } catch (RuntimeException e) {
            log.warn("[span] endSpan 写库失败 span={}: {}", span.spanId, e.toString());
        }
    }

    /**
     * 失败收口：{@code status=failed} + 三个 error 字段 + 级联取消下游。
     *
     * <p>级联两层（源站 {@code failSpan}）：
     * <ol>
     *   <li><b>子树</b>：本 span 的所有后代里仍 pending/running 的置 {@code cancelled}
     *       （{@code UPSTREAM_FAILED}）；</li>
     *   <li><b>兄弟 stage</b>：{@code kind=stage} 时按 {@link #STAGE_UPSTREAM} 反查
     *       依赖本阶段的下游 stage 一并取消 —— 否则 chunking 失败后 embedding /
     *       multimodal 会永远停在 pending，前端就是三个转不完的圈。</li>
     * </ol>
     *
     * <p>另外：主流水线 stage 的终态失败会顺手把 root 收口成 failed（源站同款），
     * 否则根节点永远 running。
     *
     * @param span         span（null 安全）
     * @param errorCode    错误码（例：{@code PARSER_ERROR}）
     * @param errorMessage 给用户看的短句（超 1024 字符截断）
     * @param detail       异常（取其 {@code toString()} 存 error_detail，超 8192 字符截断）
     */
    public void failSpan(KbSpan span, String errorCode, String errorMessage, Throwable detail) {
        fail(span, errorCode, errorMessage, detail, false);
    }

    /**
     * 「还会重试」的失败收口：落库字段与 {@link #failSpan} 完全一致，唯一差别是
     * <b>不把 root 收口成 failed</b>（源站用重试预算决定；本仓没有该上下文，
     * 恒定按「调用方会重试」处理，见类注释偏离 6）。
     *
     * @param span         span（null 安全）
     * @param errorCode    错误码
     * @param errorMessage 给用户看的短句
     * @param detail       异常
     */
    public void failSpanRetryable(KbSpan span, String errorCode, String errorMessage, Throwable detail) {
        fail(span, errorCode, errorMessage, detail, true);
    }

    /**
     * 跳过：{@code status=skipped}，{@code output={"reason": "..."}}。
     *
     * <p>与 cancelled 的区别（源站注释原话）：skipped 是「我们选择不跑」
     * （如纯文本文档不做 multimodal），cancelled 是「上游挂了所以没跑」。
     *
     * @param span   span（null 安全）
     * @param reason 跳过原因
     */
    public void skipSpan(KbSpan span, String reason) {
        if (span == null) {
            return;
        }
        try {
            String now = KbTimeFormat.now();
            span.status = KbSpanStatus.SKIPPED;
            span.output = reasonJson(reason);
            span.finishedAt = now;
            span.durationMs = elapsedMs(span);
            upsert(span, terminalColumns(span));
            logTerminal(span);
        } catch (RuntimeException e) {
            log.warn("[span] skipSpan 写库失败 span={}: {}", span.spanId, e.toString());
        }
    }

    // ── 查询 ───────────────────────────────────────────────────────

    /**
     * 按 {@code name + kind=stage} 查本 attempt 的 stage 快照。
     *
     * <p>用途（源站注释）：跨进程 / 跨线程的 worker（如图片理解任务）要在上游 stage
     * 下面挂子 span，但手里只有 (knowledgeId, attempt, stage) 三元组。
     *
     * @param knowledgeId 知识条目 ID
     * @param attempt     attempt 号
     * @param stage       阶段名
     * @return 快照；查不到 / 入参非法 / 库不可读时 null
     */
    public KbSpan lookupStage(String knowledgeId, int attempt, String stage) {
        if (isBlank(knowledgeId) || isBlank(stage) || attempt <= 0) {
            return null;
        }
        try {
            for (KbProcessingSpanRecord row : listByAttempt(knowledgeId, attempt)) {
                if (KbSpanKind.STAGE.equals(row.getKind()) && stage.equals(row.getName())) {
                    return KbSpan.fromRecord(row);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[span] lookupStage 查询失败 knowledge={} attempt={} stage={}: {}",
                    knowledgeId, attempt, stage, e.toString());
        }
        return null;
    }

    /**
     * 按 {@code name} 查本 attempt 任意 kind 的第一条 span（源站同名方法）。
     *
     * <p>用途：扇出型 worker（如问题生成批次）要挂到一个「分组 span」下面，
     * 这个分组 span 是编排器早先建的；查不到时调用方应退回挂到 stage 上。
     *
     * @param knowledgeId 知识条目 ID
     * @param attempt     attempt 号
     * @param name        名字
     * @return 快照；查不到 / 入参非法 / 库不可读时 null
     */
    public KbSpan lookupSpanByName(String knowledgeId, int attempt, String name) {
        if (isBlank(knowledgeId) || isBlank(name) || attempt <= 0) {
            return null;
        }
        try {
            for (KbProcessingSpanRecord row : listByAttempt(knowledgeId, attempt)) {
                if (name.equals(row.getName())) {
                    return KbSpan.fromRecord(row);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[span] lookupSpanByName 查询失败 knowledge={} attempt={} name={}: {}",
                    knowledgeId, attempt, name, e.toString());
        }
        return null;
    }

    // ── 收口 ───────────────────────────────────────────────────────

    /**
     * 收口一轮 attempt：root 置终态，并把<b>其余仍 pending/running 的子 span 一并置该
     * status</b>（派单书 2.5 / 用例 8；源站 FinalizeAttempt 只写 root，见类注释偏离 5）。
     *
     * <p><b>幂等</b>：root 已经是终态则整次调用是 no-op（源站同款），所以成功编排器、
     * 级联失败、死信处理三条路径可以各自随手调用，不用互相协调。
     *
     * @param knowledgeId 知识条目 ID
     * @param attempt     attempt 号
     * @param status      终态（空 ⇒ done）
     * @param outputJson  root 的输出快照（null ⇒ 保留库里已有的 output）
     */
    public void finalizeAttempt(String knowledgeId, int attempt, String status, String outputJson) {
        finalizeAttemptInternal(knowledgeId, attempt, status, outputJson, null, null);
    }

    /**
     * 用户取消：把本 attempt <b>所有</b> 非终态 span 平铺置 {@code cancelled}
     * （{@code USER_CANCELLED}），再收口 root。
     *
     * <p>源站注释解释了为什么是「平铺」而不是像级联那样做树遍历：扇出型 stage
     * （多模态识别）派发完就把自己 {@code EndSpan} 成 done 了，它的子任务还在 running；
     * 停在终态父节点上的 BFS 会漏掉这些叶子 ⇒ 用户点了取消，时间线上还挂着几条
     * 「进行中」。
     *
     * @param knowledgeId 知识条目 ID
     * @param attempt     attempt 号
     * @param reason      取消原因（空 ⇒ {@code user cancelled}）
     */
    public void abortAttempt(String knowledgeId, int attempt, String reason) {
        if (isBlank(knowledgeId) || attempt <= 0) {
            return;
        }
        String actualReason = isBlank(reason) ? "user cancelled" : reason;
        String now = KbTimeFormat.now();
        try {
            UpdateChain<KbProcessingSpanRecord> chain = UpdateChain.of(mapper)
                    .set(new QueryColumn("status"), KbSpanStatus.CANCELLED, true);
            chain.set(new QueryColumn("error_code"), USER_CANCELLED, true);
            chain.set(new QueryColumn("error_message"), actualReason, true);
            chain.set(new QueryColumn("finished_at"), now, true);
            chain.set(new QueryColumn("update_time"), now, true);
            chain.where("knowledge_id = ?", knowledgeId)
                    .and("attempt = ?", attempt)
                    .and("status IN (?, ?)", KbSpanStatus.PENDING, KbSpanStatus.RUNNING)
                    .update();
            log.info("[span] 处理中止 knowledge={} attempt={} 原因={}", knowledgeId, attempt, actualReason);
        } catch (RuntimeException e) {
            log.warn("[span] abortAttempt 扫尾失败 knowledge={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
        }
        finalizeAttemptInternal(knowledgeId, attempt, KbSpanStatus.CANCELLED, null,
                USER_CANCELLED, actualReason);
    }

    // ── 内部：失败与收口 ───────────────────────────────────────────

    /**
     * {@link #failSpan} 与 {@link #failSpanRetryable} 的共同实现。
     *
     * @param willRetry true ⇒ 不收口 root（调用方还会重试）
     */
    private void fail(KbSpan span, String errorCode, String errorMessage, Throwable detail,
                      boolean willRetry) {
        if (span == null) {
            return;
        }
        try {
            String trimmedCode = trimToNull(errorCode);
            String message = truncate(errorMessage, MAX_ERROR_MESSAGE_CHARS);
            String detailText = detail == null ? null
                    : truncate(detail.toString(), MAX_ERROR_DETAIL_CHARS);
            String now = KbTimeFormat.now();
            span.status = KbSpanStatus.FAILED;
            span.errorCode = trimmedCode;
            span.errorMessage = message;
            span.errorDetail = detailText;
            span.finishedAt = now;
            span.durationMs = elapsedMs(span);
            upsert(span, terminalColumns(span));
            logTerminal(span);

            String reason = "upstream " + span.name + " failed"
                    + (trimmedCode == null ? "" : " (" + trimmedCode + ")");
            cancelDescendants(span, reason);
            if (KbSpanKind.STAGE.equals(span.kind)) {
                cascadeDependentStages(span, reason);
                if (ALL_STAGES.contains(span.name) && !willRetry) {
                    finalizeAttemptInternal(span.knowledgeId, span.attempt,
                            KbSpanStatus.FAILED, null, trimmedCode, message);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[span] failSpan 写库失败 span={}: {}", span.spanId, e.toString());
        }
    }

    /**
     * {@link #finalizeAttempt} / {@link #abortAttempt} 的共同实现。
     *
     * @param errorCode    写入 root 的错误码（null ⇒ 不动）
     * @param errorMessage 写入 root 的错误摘要（null ⇒ 不动）
     */
    private void finalizeAttemptInternal(String knowledgeId, int attempt, String status,
                                         String outputJson, String errorCode, String errorMessage) {
        if (isBlank(knowledgeId) || attempt <= 0) {
            return;
        }
        try {
            String actualStatus = isBlank(status) ? KbSpanStatus.DONE : status;
            List<KbProcessingSpanRecord> rows = listByAttempt(knowledgeId, attempt);
            KbProcessingSpanRecord root = null;
            for (KbProcessingSpanRecord row : rows) {
                if (KbSpanKind.ROOT.equals(row.getKind())) {
                    root = row;
                    break;
                }
            }
            if (root == null) {
                log.warn("[span] finalizeAttempt 找不到 root knowledge={} attempt={}",
                        knowledgeId, attempt);
                return;
            }
            KbSpan rootSpan = KbSpan.fromRecord(root);
            if (rootSpan.isTerminal()) {
                // 幂等：已经收口过的 root 不再改写（源站 :835 同款早退）
                return;
            }
            String now = KbTimeFormat.now();
            rootSpan.status = actualStatus;
            rootSpan.finishedAt = now;
            rootSpan.durationMs = elapsedFromText(root.getStartedAt(), now);
            if (outputJson != null) {
                rootSpan.output = outputJson;
            }
            if (errorCode != null) {
                rootSpan.errorCode = trimToNull(errorCode);
            }
            if (errorMessage != null) {
                rootSpan.errorMessage = truncate(errorMessage, MAX_ERROR_MESSAGE_CHARS);
            }
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("status", rootSpan.status);
            cols.put("finished_at", rootSpan.finishedAt);
            cols.put("duration_ms", rootSpan.durationMs);
            cols.put("error_code", rootSpan.errorCode);
            cols.put("error_message", rootSpan.errorMessage);
            if (outputJson != null) {
                cols.put("output", outputJson);
            }
            updateSpan(rootSpan, cols);
            closeOpenChildren(knowledgeId, attempt, root.getSpanId(), actualStatus, now);
            logTerminal(rootSpan);
        } catch (RuntimeException e) {
            log.warn("[span] finalizeAttempt 写库失败 knowledge={} attempt={}: {}",
                    knowledgeId, attempt, e.toString());
        }
    }

    /** 把本 attempt 里除 root 外仍 pending/running 的行一并收口成 {@code status}。 */
    private void closeOpenChildren(String knowledgeId, int attempt, String rootSpanId,
                                   String status, String now) {
        List<KbProcessingSpanRecord> rows = listByAttempt(knowledgeId, attempt);
        for (KbProcessingSpanRecord row : rows) {
            if (rootSpanId != null && rootSpanId.equals(row.getSpanId())) {
                continue;
            }
            if (!KbSpanStatus.PENDING.equals(row.getStatus())
                    && !KbSpanStatus.RUNNING.equals(row.getStatus())) {
                continue;
            }
            KbSpan child = KbSpan.fromRecord(row);
            child.status = status;
            child.finishedAt = now;
            child.durationMs = elapsedFromText(row.getStartedAt(), now);
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("status", status);
            cols.put("finished_at", now);
            cols.put("duration_ms", child.durationMs);
            updateSpan(child, cols);
        }
    }

    // ── 内部：级联取消 ─────────────────────────────────────────────

    /**
     * 按 {@link #STAGE_UPSTREAM} 反查「谁因为本阶段失败而废掉」，把这些<b>兄弟</b> stage
     * 行置 {@code cancelled}；再各自把自己的子树取消掉。
     *
     * <p>源站 {@code knowledge_span_tracker.go:675 cascadeDependentStages}。第二次递归是
     * 必需的：上游失败前已经开工的子 span（例如 chunking 挂掉时正在跑的某个 embedding
     * 批次）否则会停在 running，在已取消的父节点下继续转圈。
     *
     * <p>只处理 pending/running 的 stage 行：已经终态的行保留原结局（成功过就是成功过）。
     */
    private void cascadeDependentStages(KbSpan failedStage, String reason) {
        List<String> dependents = stagesDependingOn(failedStage.name);
        if (dependents.isEmpty()) {
            return;
        }
        String now = KbTimeFormat.now();
        for (KbProcessingSpanRecord row : listByAttempt(failedStage.knowledgeId, failedStage.attempt)) {
            if (!KbSpanKind.STAGE.equals(row.getKind())) {
                continue;
            }
            if (!KbSpanStatus.PENDING.equals(row.getStatus())
                    && !KbSpanStatus.RUNNING.equals(row.getStatus())) {
                continue;
            }
            if (!dependents.contains(row.getName())) {
                continue;
            }
            KbSpan dependent = KbSpan.fromRecord(row);
            dependent.status = KbSpanStatus.CANCELLED;
            dependent.errorCode = UPSTREAM_FAILED;
            dependent.errorMessage = reason;
            dependent.finishedAt = now;
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("status", KbSpanStatus.CANCELLED);
            cols.put("error_code", UPSTREAM_FAILED);
            cols.put("error_message", reason);
            cols.put("finished_at", now);
            updateSpan(dependent, cols);
            cancelDescendants(dependent, reason);
        }
    }

    /**
     * 求「以 {@code stage} 为上游（直接或间接）的阶段」传递闭包。
     *
     * <p><b>逐行移植</b>源站 {@code knowledge_span_tracker.go:717 stagesDependingOn}：
     * 沿 {@link #STAGE_UPSTREAM} 反向逐层扩散，只遍历 {@link #ALL_STAGES} 里的 5 个阶段，
     * 因此朴素 O(N²) 足够（源站注释同款）。
     *
     * <p>例：{@code docreader} ⇒ [chunking, embedding, multimodal, postprocess]
     * （chunking 先被发现，再由它扩散到 embedding/multimodal，最后是 postprocess）；
     * {@code postprocess} ⇒ 空（下游没有阶段）。
     *
     * @param stage 失败阶段名
     * @return 会被级联取消的阶段名（不保证顺序，只保证集合正确）
     */
    private static List<String> stagesDependingOn(String stage) {
        List<String> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        List<String> frontier = new ArrayList<>(List.of(stage));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String candidate : ALL_STAGES) {
                if (seen.contains(candidate)) {
                    continue;
                }
                for (String dependency : STAGE_UPSTREAM.getOrDefault(candidate, List.of())) {
                    if (frontier.contains(dependency)) {
                        seen.add(candidate);
                        out.add(candidate);
                        next.add(candidate);
                        break;
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    /**
     * 把 {@code parent} 的所有后代里仍 pending/running 的行置 {@code cancelled}
     * （{@code UPSTREAM_FAILED} + reason）。逐层 BFS，每层一条批量 UPDATE，
     * 深度上限 {@link #MAX_CASCADE_DEPTH}（源站 repo 同款可移植实现）。
     *
     * <p>注意：源站这里<b>不</b>写 {@code finished_at}（只改 status / error_code /
     * error_message），保留原 started_at 让前端还能看出它是从什么时候挂起的 —— 本仓照抄。
     */
    private void cancelDescendants(KbSpan parent, String reason) {
        String now = KbTimeFormat.now();
        List<String> frontier = new ArrayList<>(List.of(parent.spanId));
        for (int depth = 0; depth < MAX_CASCADE_DEPTH && !frontier.isEmpty(); depth++) {
            String inClause = placeholders(frontier.size());
            List<Object> args = new ArrayList<>();
            args.add(parent.knowledgeId);
            args.add(parent.attempt);
            args.addAll(frontier);
            args.add(KbSpanStatus.PENDING);
            args.add(KbSpanStatus.RUNNING);
            List<KbProcessingSpanRecord> children = mapper.selectListByQuery(QueryWrapper.create()
                    .where("knowledge_id = ? AND attempt = ? AND parent_span_id IN (" + inClause
                            + ") AND status IN (?, ?)", args.toArray())
                    .orderBy("id", true));
            if (children == null || children.isEmpty()) {
                return;
            }
            List<String> childIds = new ArrayList<>(children.size());
            List<String> nextFrontier = new ArrayList<>(children.size());
            for (KbProcessingSpanRecord child : children) {
                childIds.add(child.getSpanId());
                nextFrontier.add(child.getSpanId());
            }
            UpdateChain<KbProcessingSpanRecord> chain = UpdateChain.of(mapper)
                    .set(new QueryColumn("status"), KbSpanStatus.CANCELLED, true);
            chain.set(new QueryColumn("error_code"), UPSTREAM_FAILED, true);
            chain.set(new QueryColumn("error_message"), reason, true);
            chain.set(new QueryColumn("update_time"), now, true);
            chain.where("knowledge_id = ?", parent.knowledgeId)
                    .and("attempt = ?", parent.attempt)
                    .and("span_id IN (" + placeholders(childIds.size()) + ")", childIds.toArray())
                    .update();
            frontier = nextFrontier;
        }
    }

    /** 把更早 attempt 里仍未终态的行置 cancelled（新 attempt 顶掉旧轮）。 */
    private void cancelOpenSpansBeforeAttempt(String knowledgeId, int attempt) {
        String now = KbTimeFormat.now();
        UpdateChain<KbProcessingSpanRecord> chain = UpdateChain.of(mapper)
                .set(new QueryColumn("status"), KbSpanStatus.CANCELLED, true);
        chain.set(new QueryColumn("error_code"), ATTEMPT_SUPERSEDED, true);
        chain.set(new QueryColumn("error_message"), "superseded by a newer parse attempt", true);
        chain.set(new QueryColumn("finished_at"), now, true);
        chain.set(new QueryColumn("update_time"), now, true);
        chain.where("knowledge_id = ?", knowledgeId)
                .and("attempt < ?", attempt)
                .and("status IN (?, ?)", KbSpanStatus.PENDING, KbSpanStatus.RUNNING)
                .update();
    }

    /** 把同 attempt 下同名且仍未终态的行置 cancelled（同名子任务重跑前顶掉旧行）。 */
    private void cancelOpenSpansByName(String knowledgeId, int attempt, String name) {
        String now = KbTimeFormat.now();
        UpdateChain<KbProcessingSpanRecord> chain = UpdateChain.of(mapper)
                .set(new QueryColumn("status"), KbSpanStatus.CANCELLED, true);
        chain.set(new QueryColumn("error_code"), TASK_SUPERSEDED, true);
        chain.set(new QueryColumn("error_message"), "superseded by a new run of the same subtask", true);
        chain.set(new QueryColumn("finished_at"), now, true);
        chain.set(new QueryColumn("update_time"), now, true);
        chain.where("knowledge_id = ?", knowledgeId)
                .and("attempt = ?", attempt)
                .and("name = ?", name)
                .and("status IN (?, ?)", KbSpanStatus.PENDING, KbSpanStatus.RUNNING)
                .update();
    }

    // ── 内部：落库 ─────────────────────────────────────────────────

    /** 按 (knowledge_id, attempt) 拉整棵树，{@code id ASC} 保持自然插入序（源站 repo 同款）。 */
    private List<KbProcessingSpanRecord> listByAttempt(String knowledgeId, int attempt) {
        List<KbProcessingSpanRecord> rows = mapper.selectListByQuery(QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("attempt = ?", attempt)
                .orderBy("id", true));
        return rows == null ? List.of() : rows;
    }

    /**
     * 写一行（update 命中不到 ⇒ insert）。
     *
     * <p>这就是源站 {@code Upsert}（ON CONFLICT(knowledge_id, attempt, span_id)
     * DO UPDATE）的等价物：本仓不用 SQLite 的 UPSERT 语法，因为要控制「哪些列写、哪些列
     * 保留」—— {@code output / input / metadata} 只在调用方真给了值时才动，
     * 否则 {@code endSpan} 会把 {@code beginStage} 写的 input 清成 NULL
     * （源站 repo 动态构造 DoUpdates 列的同一考量）。
     *
     * @param span    span 快照
     * @param columns 本次要写的列（null 值只有在 {@code Map} 里显式出现时才写 NULL）
     */
    private void upsert(KbSpan span, Map<String, Object> columns) {
        if (!updateSpan(span, columns)) {
            insertSpan(span);
        }
    }

    private boolean updateSpan(KbSpan span, Map<String, Object> columns) {
        UpdateChain<KbProcessingSpanRecord> chain = UpdateChain.of(mapper)
                .where("knowledge_id = ?", span.knowledgeId)
                .and("attempt = ?", span.attempt)
                .and("span_id = ?", span.spanId);
        for (Map.Entry<String, Object> entry : columns.entrySet()) {
            // 第三参 true = 这一列一定写（值为 null 时也要写成 NULL，用于「清空终态字段」）
            chain.set(new QueryColumn(entry.getKey()), entry.getValue(), true);
        }
        chain.set(new QueryColumn("update_time"), KbTimeFormat.now(), true);
        return chain.update();
    }

    private void insertSpan(KbSpan span) {
        // insertSelective：跳过 null 列，让审计六列的 DB 默认值生效
        mapper.insertSelective(toRecord(span));
    }

    private KbProcessingSpanRecord toRecord(KbSpan span) {
        KbProcessingSpanRecord row = new KbProcessingSpanRecord();
        row.setKnowledgeId(span.knowledgeId);
        row.setAttempt(span.attempt);
        row.setSpanId(span.spanId);
        row.setParentSpanId(span.parentSpanId);
        row.setName(span.name);
        row.setKind(span.kind);
        row.setStatus(span.status);
        row.setInput(span.input);
        row.setOutput(span.output);
        row.setMetadata(span.metadata);
        row.setErrorCode(span.errorCode);
        row.setErrorMessage(span.errorMessage);
        row.setErrorDetail(span.errorDetail);
        row.setStartedAt(span.startedAt);
        row.setFinishedAt(span.finishedAt);
        row.setDurationMs(span.durationMs);
        return row;
    }

    /** 终态要写的一组列（output 为 null 时显式置空，避免上一轮的输出粘在新状态上）。 */
    private static Map<String, Object> terminalColumns(KbSpan span) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("status", span.status);
        cols.put("output", span.output);
        cols.put("error_code", span.errorCode);
        cols.put("error_message", span.errorMessage);
        cols.put("error_detail", span.errorDetail);
        cols.put("started_at", span.startedAt);
        cols.put("finished_at", span.finishedAt);
        cols.put("duration_ms", span.durationMs);
        return cols;
    }

    // ── 内部：工具 ─────────────────────────────────────────────────

    /** 本仓 span id：带横线的 UUID（源站是去横线 hex，见类注释偏离 1）。 */
    private static String newSpanId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 耗时：优先进程内计时起点，退回 {@code started_at} 文本差，再退回库里已有的值。
     * 永不为负（时钟回拨 / 秒级精度下同一秒内起止都可能算出 0 或负数）。
     */
    private static long elapsedMs(KbSpan span) {
        long fromNanos = span.elapsedMs();
        if (fromNanos > 0L) {
            return fromNanos;
        }
        long fromText = elapsedFromText(span.startedAt, KbTimeFormat.now());
        if (fromText > 0L) {
            return fromText;
        }
        return span.durationMs == null ? 0L : Math.max(span.durationMs, 0L);
    }

    /** 两个 {@code yyyy-MM-dd HH:mm:ss} 文本之间的毫秒差；任一解析失败 ⇒ 0。 */
    private static long elapsedFromText(String startedAt, String finishedAt) {
        LocalDateTime start = parseQuietly(startedAt);
        LocalDateTime end = parseQuietly(finishedAt);
        if (start == null || end == null) {
            return 0L;
        }
        return Math.max(Duration.between(start, end).toMillis(), 0L);
    }

    private static LocalDateTime parseQuietly(String text) {
        try {
            return KbTimeFormat.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code {"reason": "..."}}（手写转义，避免为一个字段引入 JSON 库）。 */
    private static String reasonJson(String reason) {
        String value = reason == null ? "" : reason;
        StringBuilder sb = new StringBuilder("{\"reason\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"}").toString();
    }

    /**
     * 每个终态一条 info 日志，格式固定：
     * {@code [span] 阶段结束 knowledge=… attempt=… stage=… 状态=… 耗时=…ms}。
     */
    private static void logTerminal(KbSpan span) {
        log.info("[span] 阶段结束 knowledge={} attempt={} stage={} 状态={} 耗时={}ms",
                span.knowledgeId, span.attempt, span.name, span.status,
                span.durationMs == null ? 0L : span.durationMs);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

}

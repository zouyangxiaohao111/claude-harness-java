package com.nexusai.kb.app.ingest.span;

/**
 * 处理 span 的进程内快照对象（不落库本身，落库走
 * {@link com.nexusai.kb.repo.entity.KbProcessingSpanRecord}）。
 *
 * <p>对应源站 {@code knowledge_span_tracker.go} 的 {@code Span} 结构。
 * 语义是「快照」：身份字段（spanId / parentSpanId / knowledgeId / attempt /
 * name / kind）创建后不变，结果字段（status / output / error* / finishedAt /
 * durationMs）由 tracker 在收口时回填并同步写库。
 *
 * <p>{@code input / output / metadata} 一律是 JSON 文本（String），
 * 与表列同口径。
 *
 * <p>{@link #startNanos} 只在进程内用于算耗时，<b>不落库</b>。
 */
public final class KbSpan {

    /** span 唯一标识。 */
    public final String spanId;

    /** 父 span id；root 为 null。 */
    public final String parentSpanId;

    /** 关联知识条目 ID。 */
    public final String knowledgeId;

    /** 处理轮次。 */
    public final int attempt;

    /** 展示名（stage 名 / 子任务名）。 */
    public final String name;

    /** 节点类型（见 {@link KbSpanKind}）。 */
    public final String kind;

    /** 状态（见 {@link KbSpanStatus}）。 */
    public String status;

    /** 输入快照（JSON 文本）。 */
    public String input;

    /** 输出快照（JSON 文本）。 */
    public String output;

    /** 附加元数据（JSON 文本）。 */
    public String metadata;

    /** 错误码。 */
    public String errorCode;

    /** 错误摘要。 */
    public String errorMessage;

    /** 错误详情。 */
    public String errorDetail;

    /** 开始时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    public String startedAt;

    /** 结束时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    public String finishedAt;

    /** 耗时（毫秒）。 */
    public Long durationMs;

    /** 进程内计时起点（{@link System#nanoTime()}），不落库。 */
    public long startNanos;

    private KbSpan(String spanId, String parentSpanId, String knowledgeId, int attempt,
                   String name, String kind, String status, String input) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.knowledgeId = knowledgeId;
        this.attempt = attempt;
        this.name = name;
        this.kind = kind;
        this.status = status;
        this.input = input;
    }

    /**
     * 新建一个「初始状态」的 span（身份字段定死，结果字段留空）。
     *
     * @param spanId       span 唯一标识（本仓用 UUID）
     * @param parentSpanId 父 span；root 传 null
     * @param knowledgeId  知识条目 ID
     * @param attempt      处理轮次
     * @param name         展示名
     * @param kind         {@link KbSpanKind} 之一
     * @param status       {@link KbSpanStatus} 之一
     * @param input        输入 JSON 文本（可 null）
     */
    public static KbSpan of(String spanId, String parentSpanId, String knowledgeId, int attempt,
                            String name, String kind, String status, String input) {
        KbSpan span = new KbSpan(spanId, parentSpanId, knowledgeId, attempt, name, kind, status, input);
        span.startNanos = System.nanoTime();
        return span;
    }

    /**
     * 从库行还原成快照（tracker lookup 用）。
     * <p>注意：还原出的 span 没有进程内计时起点，{@code startNanos} 为 0，
     * 收口时应改用行里已有的 {@code durationMs} 而非重算。
     */
    public static KbSpan fromRecord(com.nexusai.kb.repo.entity.KbProcessingSpanRecord record) {
        int attempt = record.getAttempt() == null ? 0 : record.getAttempt();
        KbSpan span = new KbSpan(record.getSpanId(), record.getParentSpanId(),
                record.getKnowledgeId(), attempt, record.getName(), record.getKind(),
                record.getStatus(), record.getInput());
        span.output = record.getOutput();
        span.metadata = record.getMetadata();
        span.errorCode = record.getErrorCode();
        span.errorMessage = record.getErrorMessage();
        span.errorDetail = record.getErrorDetail();
        span.startedAt = record.getStartedAt();
        span.finishedAt = record.getFinishedAt();
        span.durationMs = record.getDurationMs();
        return span;
    }

    /** 进程内已耗时（毫秒）；未记录起点时返回 0。 */
    public long elapsedMs() {
        if (startNanos == 0L) {
            return 0L;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 是否已到终态（终态 span 不再被收口改写）。 */
    public boolean isTerminal() {
        return KbSpanStatus.DONE.equals(status)
                || KbSpanStatus.FAILED.equals(status)
                || KbSpanStatus.SKIPPED.equals(status)
                || KbSpanStatus.CANCELLED.equals(status);
    }
}

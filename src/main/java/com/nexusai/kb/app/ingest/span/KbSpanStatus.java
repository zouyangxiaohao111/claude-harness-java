package com.nexusai.kb.app.ingest.span;

/**
 * span 状态取值（{@code kb_processing_span.status}）。
 *
 * <p>对应源站 {@code knowledge_span_tracker.go} 的状态字符串。
 * 只放常量不建枚举：落库就是 TEXT，前端也按字符串比对，
 * 用枚举反而多一层转换与漏值风险。
 *
 * <p>⚠️ 全仓<b>唯一</b>定义处（实体 {@code KbProcessingSpanRecord} 不重复定义）。
 */
public final class KbSpanStatus {

    /** 已创建、尚未开始执行。 */
    public static final String PENDING = "pending";

    /** 执行中（stage / subspan 的初始状态）。 */
    public static final String RUNNING = "running";

    /** 成功结束。 */
    public static final String DONE = "done";

    /** 失败结束（含 errors 三字段）。 */
    public static final String FAILED = "failed";

    /** 主动跳过（如「无子任务」「状态已变」），output 里带 reason。 */
    public static final String SKIPPED = "skipped";

    /** 被取消（级联取消或 abortAttempt 收口）。 */
    public static final String CANCELLED = "cancelled";

    /**
     * 收口中：root span 已派发完全部子任务、等子任务跑完的状态。
     * 源站前端认这个值，故必须保留。
     */
    public static final String FINALIZING = "finalizing";

    /**
     * 处理中（源站文档级 processing 口径，前端据此判「还在跑」）。
     * 源站前端认这个值，故必须保留。
     */
    public static final String PROCESSING = "processing";

    private KbSpanStatus() {
    }
}

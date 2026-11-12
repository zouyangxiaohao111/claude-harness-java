package com.nexusai.kb.app.ingest.span;

/**
 * span 节点类型（{@code kb_processing_span.kind}）。
 *
 * <p>对应源站 {@code knowledge_span_tracker.go} 的 kind 取值：
 * <ul>
 *   <li>{@code root} —— 一次 attempt 的根节点（name=knowledge）；</li>
 *   <li>{@code stage} —— 五大处理阶段（docreader / chunking / embedding /
 *       multimodal / postprocess）；</li>
 *   <li>{@code group} —— 阶段内的分组节点（前端时间线按组折叠）；</li>
 *   <li>{@code subspan} —— 组内的具体子任务（如 postprocess.&lt;type&gt;）；</li>
 *   <li>{@code generation} —— 模型生成类节点（摘要 / 问题 / 打标等）。</li>
 * </ul>
 *
 * <p>⚠️ 全仓<b>唯一</b>定义处（实体 {@code KbProcessingSpanRecord} 不重复定义）。
 */
public final class KbSpanKind {

    /** 一次 attempt 的根节点。 */
    public static final String ROOT = "root";

    /** 处理阶段。 */
    public static final String STAGE = "stage";

    /** 阶段内的分组。 */
    public static final String GROUP = "group";

    /** 组内的具体子任务。 */
    public static final String SUBSPAN = "subspan";

    /** 模型生成类节点。 */
    public static final String GENERATION = "generation";

    private KbSpanKind() {
    }
}

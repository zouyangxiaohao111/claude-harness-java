package com.nexusai.application.agent.permission.classifier;

/**
 * YoloClassifier 决策结果 · 对齐 CC {@code YoloClassifierResult}
 * （Open-ClaudeCode/src/types/permissions.ts:346-396）+ 2-stage XML 返回对象
 * （yoloClassifier.ts:810-994）。
 *
 * <p><b>[S06 重构] 契约变更声明（⊕-01/⊕-02，OPD-WF6-01）</b>：
 * <ol>
 *   <li><b>删除</b> allow/deny/ask 三态 + {@code PermissionBehavior} 面（⊕-02）——
 *       对齐 CC 布尔 {@code shouldBlock}（permissions.ts:347）。</li>
 *   <li><b>删除</b> {@code confidence} / {@code tokenCount} / {@code model}(dup) /
 *       {@code usage}(Integer) 字段（⊕-01）——{@code usage} 对齐 CC
 *       {@link ClassifierUsage} 4 字段（permissions.ts:339-343）；模型唯一字段为
 *       {@code model}（CC {@code model}，permissions.ts:358）。</li>
 * </ol>
 *
 * <p><b>CC 真源字段（snake_case → Java camelCase，JavaDoc 标注 CC 原名+行号）</b>:
 * <ol>
 *   <li>{@link #shouldBlock} ← CC {@code shouldBlock}（types/permissions.ts:347，布尔决策）</li>
 *   <li>{@link #reason} ← CC {@code reason}（permissions.ts:348）</li>
 *   <li>{@link #model} ← CC {@code model}（permissions.ts:358）</li>
 *   <li>{@link #unavailable} ← CC {@code unavailable}（permissions.ts:349，API error / abort）</li>
 *   <li>{@link #transcriptTooLong} ← CC {@code transcriptTooLong}（permissions.ts:355，
 *       API 'prompt is too long' → 回退 prompting）</li>
 *   <li>{@link #usage} ← CC {@code usage}（permissions.ts:360，ClassifierUsage）</li>
 *   <li>{@link #durationMs} ← CC {@code durationMs}（permissions.ts:361）</li>
 *   <li>{@link #promptLengths} ← CC {@code promptLengths}（permissions.ts:363-366，三段）</li>
 *   <li>{@link #errorDumpPath} ← CC {@code errorDumpPath}（permissions.ts:368）</li>
 *   <li>{@link #stage} ← CC {@code stage: 'fast'|'thinking'}（permissions.ts:370，Java int 1='fast'/2='thinking'）</li>
 *   <li>{@link #stage1Usage} ← CC {@code stage1Usage}（permissions.ts:374）</li>
 *   <li>{@link #stage1DurationMs} ← CC {@code stage1DurationMs}（permissions.ts:376）</li>
 *   <li>{@link #stage1RequestId} ← CC {@code stage1RequestId}（permissions.ts:382，yoloClassifier.ts:798 extractRequestId）</li>
 *   <li>{@link #stage1MsgId} ← CC {@code stage1MsgId}（permissions.ts:388，yoloClassifier.ts:799）</li>
 *   <li>{@link #stage2Usage} ← CC {@code stage2Usage}（permissions.ts:390）</li>
 *   <li>{@link #stage2DurationMs} ← CC {@code stage2DurationMs}（permissions.ts:392）</li>
 *   <li>{@link #stage2RequestId} ← CC {@code stage2RequestId}（permissions.ts:394，yoloClassifier.ts:884）</li>
 *   <li>{@link #stage2MsgId} ← CC {@code stage2MsgId}（permissions.ts:396，yoloClassifier.ts:885）</li>
 *   <li>{@link #thinking} ← CC {@code thinking}（permissions.ts:346，stage2 parseXmlThinking yoloClassifier.ts:924）</li>
 * </ol>
 *
 * <p><b>失败语义（对齐 CC classifyYoloActionXml，yoloClassifier.ts:941-995）</b>：
 * <ul>
 *   <li>abort → shouldBlock=true + unavailable=true，reason='Classifier request aborted'</li>
 *   <li>transcript too long → shouldBlock=true + transcriptTooLong=true，reason=
 *       'Classifier transcript exceeded context window'（确定性，重试无效）</li>
 *   <li>其它 API error → shouldBlock=true + unavailable=true，reason=
 *       'Classifier unavailable - blocking for safety' / 'Stage 2 classifier error...'</li>
 *   <li>解析失败 → shouldBlock=true（unavailable=false），reason='Classifier stage N unparseable - blocking for safety'</li>
 * </ul>
 *
 * @see ClassifierUsage
 * @see PromptLengths
 */
public record YoloClassifierResult(
        String thinking,
        boolean shouldBlock,
        String reason,
        Boolean unavailable,
        Boolean transcriptTooLong,
        String model,
        ClassifierUsage usage,
        long durationMs,
        PromptLengths promptLengths,
        String errorDumpPath,
        int stage,
        ClassifierUsage stage1Usage,
        Long stage1DurationMs,
        String stage1RequestId,
        String stage1MsgId,
        ClassifierUsage stage2Usage,
        Long stage2DurationMs,
        String stage2RequestId,
        String stage2MsgId
) {

    /** stage=1 常量 · CC 'fast'（yoloClassifier.ts:820 fast 直接返回路径） */
    public static final int STAGE_FAST = 1;
    /** stage=2 常量 · CC 'thinking'（yoloClassifier.ts:898+ stage2 思考路径） */
    public static final int STAGE_THINKING = 2;

    /**
     * 紧凑构造器：不变量保护 + 字段归一化（CC 布尔语义）。
     */
    public YoloClassifierResult {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is blank");
        }
        if (stage != STAGE_FAST && stage != STAGE_THINKING) {
            throw new IllegalArgumentException("stage must be 1 (fast) or 2 (thinking): " + stage);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs is negative: " + durationMs);
        }
        unavailable = unavailable != null ? unavailable : false;
        transcriptTooLong = transcriptTooLong != null ? transcriptTooLong : false;
    }

    /**
     * 放行工厂 · 对齐 CC '' 短路 ALLOW（yoloClassifier.ts:1023-1028
     * {@code shouldBlock:false, reason:'Tool declares no classifier-relevant input'}）
     * 与 stage1 放行（:810-823 {@code 'Allowed by fast classifier'}）。
     *
     * @param reason 放行原因（CC reason）
     * @param model  分类器模型（CC model）
     * @return shouldBlock=false + stage=1（fast）
     */
    public static YoloClassifierResult allowed(String reason, String model) {
        return new YoloClassifierResult(null, false, reason, false, false,
                model, null, 0L, null, null, STAGE_FAST,
                null, null, null, null, null, null, null, null);
    }

    /**
     * 阻断工厂（stage=1）· 对齐 CC stage1 block 路径（yoloClassifier.ts:842-856）
     * 与 stage2 block（:898-940）。
     *
     * @param reason 阻断原因（CC reason）
     * @param model  分类器模型（CC model）
     * @return shouldBlock=true + stage=1（fast）
     */
    public static YoloClassifierResult blocked(String reason, String model) {
        return blocked(reason, model, STAGE_FAST);
    }

    /**
     * 阻断工厂（指定 stage）· 对齐 CC 2-stage 各阶段 block 返回。
     *
     * @param reason 阻断原因（CC reason）
     * @param model  分类器模型（CC model）
     * @param stage  分类阶段（CC stage：1='fast'/2='thinking'）
     * @return shouldBlock=true + 指定 stage
     */
    public static YoloClassifierResult blocked(String reason, String model, int stage) {
        return new YoloClassifierResult(null, true, reason, false, false,
                model, null, 0L, null, null, stage,
                null, null, null, null, null, null, null, null);
    }

    /**
     * unavailable 工厂 · 对齐 CC abort / API error 返回
     * （yoloClassifier.ts:945-952 / :973-995，shouldBlock=true + unavailable=true）。
     *
     * @param reason 不可用原因（'Classifier request aborted' / 'Classifier unavailable - blocking for safety'）
     * @param model  分类器模型（CC model）
     * @return shouldBlock=true + unavailable=true + stage=1
     */
    public static YoloClassifierResult unavailable(String reason, String model) {
        return unavailable(reason, model, STAGE_FAST);
    }

    /**
     * unavailable 工厂（指定 stage）。
     *
     * @param reason 不可用原因（CC reason）
     * @param model  分类器模型（CC model）
     * @param stage  分类阶段（CC stage）
     * @return shouldBlock=true + unavailable=true + 指定 stage
     */
    public static YoloClassifierResult unavailable(String reason, String model, int stage) {
        return new YoloClassifierResult(null, true, reason, true, false,
                model, null, 0L, null, null, stage,
                null, null, null, null, null, null, null, null);
    }

    /**
     * 1-stage 分类结果工厂 · 对齐 CC 1-stage tool_use 路径（yoloClassifier.ts:1203-1249）。
     *
     * <p>CC 1-stage 单次 API 调用即产出最终决策（无 stage2），返回对象携带
     * {@code usage} / {@code durationMs} / {@code promptLengths} / {@code stage1RequestId} /
     * {@code stage1MsgId}（:1203-1249），不设置 {@code stage}（undefined）。
     * Java 端 {@code stage} 字段为 int 不可空 → 归一化为 {@link #STAGE_FAST}（单次决策，
     * 与 unavailable 工厂既有归一化一致）。
     *
     * @param thinking       模型推理内容（可为 null，CC parsed.thinking）
     * @param shouldBlock    是否阻断（CC shouldBlock）
     * @param reason         阻断/放行原因（CC reason，null → 'No reason provided'）
     * @param model          分类器模型（CC model）
     * @param usage          API token 用量（CC usage，可为 null = provider 未暴露）
     * @param durationMs     单次 API 调用耗时（CC durationMs）
     * @param promptLengths  三段 prompt 长度（CC promptLengths）
     * @param stage1RequestId API request_id（CC stage1RequestId，extractRequestId）
     * @param stage1MsgId     消息 id（CC stage1MsgId，可为 null —— Java
     *                        {@code chatWithOptionsMessage} 返回 {@code AssistantMessage}
     *                        不含 Anthropic message id）
     * @return 1-stage 分类结果
     */
    public static YoloClassifierResult singleStage(
            String thinking, boolean shouldBlock, String reason, String model,
            ClassifierUsage usage, long durationMs, PromptLengths promptLengths,
            String stage1RequestId, String stage1MsgId) {
        return new YoloClassifierResult(thinking, shouldBlock,
            reason != null && !reason.isBlank() ? reason : "No reason provided",
            false, false, model,
            usage, durationMs, promptLengths, null, STAGE_FAST,
            null, null, stage1RequestId, stage1MsgId, null, null, null, null);
    }

    /**
     * 便捷访问器：是否明确放行（CC {@code !shouldBlock}）。
     *
     * @return true = 不阻断（放行）
     */
    public boolean isAllowed() {
        return !shouldBlock;
    }
}

package com.nexusai.kb.app.graph;

/**
 * 图谱抽取任务的结果 · 与后处理其余子任务同构（复用四态语义）。
 *
 * <p>⚠️ 为什么不直接复用 {@code KbPostProcessOutcome}：图谱是<b>可选增强</b>，
 * 且它自己的失败语义里有一条独特的分支 —— 「抽不到实体」应当算
 * {@code SKIPPED}（终态）而不是 {@code FAILED}（可重试）。
 * 一篇纯图片文档无论重试多少次都抽不出实体，判 FAILED 只会烧掉 3 轮配额。
 *
 * <p>映射到槽位账时按 {@code status()} 直接对到 {@code KbPostProcessOutcome.Status}。
 */
public record KbGraphOutcome(Status status, String detail) {

    /** 与 {@code KbPostProcessOutcome.Status} 一一对应。 */
    public enum Status {
        /** 抽取成功（可能有 0 条关系，但抽到了实体）。 */
        DONE,
        /** 终态跳过（无文本 / 未配置模型 / 抽不出实体）。 */
        SKIPPED,
        /** 陈旧任务 · ⚠️ 不递减槽位。 */
        SUPERSEDED,
        /** 可重试失败。 */
        FAILED
    }

    public static KbGraphOutcome done(String detail) {
        return new KbGraphOutcome(Status.DONE, detail);
    }

    public static KbGraphOutcome skipped(String detail) {
        return new KbGraphOutcome(Status.SKIPPED, detail);
    }

    public static KbGraphOutcome superseded(String detail) {
        return new KbGraphOutcome(Status.SUPERSEDED, detail);
    }

    public static KbGraphOutcome failed(String detail) {
        return new KbGraphOutcome(Status.FAILED, detail);
    }

    /** 转成后处理链条的通用结果（供 worker 统一结算槽位）。 */
    public com.nexusai.kb.app.ingest.KbPostProcessOutcome toOutcome() {
        return switch (status) {
            case DONE -> com.nexusai.kb.app.ingest.KbPostProcessOutcome.done(detail);
            case SKIPPED -> com.nexusai.kb.app.ingest.KbPostProcessOutcome.skipped(detail);
            case SUPERSEDED ->
                com.nexusai.kb.app.ingest.KbPostProcessOutcome.superseded(detail);
            case FAILED -> com.nexusai.kb.app.ingest.KbPostProcessOutcome.failed(detail);
        };
    }
}

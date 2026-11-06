package com.nexusai.kb.app.ingest;

/**
 * 后处理子任务的执行结果 · 对齐源侧 asynq 的「返回值语义」。
 *
 * <p><b>⭐⭐ 为什么需要 4 个状态而不是一个 boolean</b>：
 * 每个状态对应一种<b>不同的槽位账处理</b>，选错会让文档永久卡在「处理中」：
 * <table>
 *   <tr><th>状态</th><th>槽位递减</th><th>queued 清理</th><th>含义</th></tr>
 *   <tr><td>{@link Status#DONE}</td><td>✅</td><td>done</td><td>真做完了</td></tr>
 *   <tr><td>{@link Status#SKIPPED}</td><td>✅</td><td>done</td><td>幂等跳过（已删/无文本/未配置）—— 终态，重试只会重复跳过</td></tr>
 *   <tr><td>{@link Status#SUPERSEDED}</td><td>❌ <b>不递减</b></td><td>done</td>
 *       <td>用户已重新解析，本任务属上一轮。递减会把<b>新一轮</b>的计数减掉 ⇒ 新轮提前「完成」</td></tr>
 *   <tr><td>{@link Status#FAILED}</td><td>看是否还有重试</td><td>queued 或 dead</td>
 *       <td>可重试错误（模型超时/5xx）。还有重试次数 ⇒ 不递减，等它真跑完再减</td></tr>
 * </table>
 *
 * <p>源侧等价物：{@code finalizeSubtaskDetached(ctx, repo, id, name, retErr, superseded, isFinalAttempt)}
 * —— 第二参 {@code superseded} 正是「跳过递减」的开关（{@code knowledge_process.go}）。
 *
 * @param status 结果分类
 * @param detail 人可读说明（落日志 / 便于排查；可为空）
 */
public record KbPostProcessOutcome(Status status, String detail) {

    /** 结果分类 · 见类注释的槽位账表。 */
    public enum Status {
        /** 执行成功。 */
        DONE,
        /** 幂等跳过（终态，不是错误）。 */
        SKIPPED,
        /** 陈旧任务（被新一轮解析取代）· ⚠️ 不递减槽位。 */
        SUPERSEDED,
        /** 可重试失败。 */
        FAILED
    }

    public static KbPostProcessOutcome done(String detail) {
        return new KbPostProcessOutcome(Status.DONE, detail);
    }

    public static KbPostProcessOutcome skipped(String detail) {
        return new KbPostProcessOutcome(Status.SKIPPED, detail);
    }

    public static KbPostProcessOutcome superseded(String detail) {
        return new KbPostProcessOutcome(Status.SUPERSEDED, detail);
    }

    public static KbPostProcessOutcome failed(String detail) {
        return new KbPostProcessOutcome(Status.FAILED, detail);
    }
}

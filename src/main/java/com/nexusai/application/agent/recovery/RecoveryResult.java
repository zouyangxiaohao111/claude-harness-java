package com.nexusai.application.agent.recovery;

/**
 * 恢复操作结果 record · 对齐 CC query.ts transition reason。
 *
 * <p>不可变 record，包含三个字段：
 * <ul>
 *   <li>{@code recoverable} — 是否可以继续（true=继续循环，false=退出）</li>
 *   <li>{@code reason} — CC query reason（{@link LoopReason}）；{@code null} 表示
 *       该恢复动作属 withRetry 域 / 已耗尽（recoverable=false 已承载信号，CC 无对应 reason）</li>
 *   <li>{@code message} — 诊断/日志消息</li>
 * </ul>
 *
 * <p><b>本 session 对齐（ER-IMP-15 / DC-03）</b>：旧四字段含 {@code compactedMessages}
 * 死分量（全仓 8 处 new RecoveryResult 全 3 参，无写非空无读取）已删除，收敛为三字段规范 record。
 *
 * <p><b>本 session 对齐</b>：旧 {@code transition: Transition}（withRetry/CC 混合 8 值）
 * 收敛为 {@code reason: LoopReason}（CC query 17 reason 全集）。backoff / exhausted / fatal
 * 等 withRetry 域动作无 CC reason，置 {@code null}。
 */
public record RecoveryResult(
    boolean recoverable,
    LoopReason reason,
    String message
) {}

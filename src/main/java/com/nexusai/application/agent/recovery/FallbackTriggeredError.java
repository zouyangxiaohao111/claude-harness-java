package com.nexusai.application.agent.recovery;

/**
 * [H7-arch Phase 5 P4 C3] 模型 fallback 触发错误 · 对齐 CC
 * {@code FallbackTriggeredError} (services/api/withRetry.ts:160-168)。
 *
 * <pre>
 * export class FallbackTriggeredError extends Error {
 *   constructor(
 *     public readonly originalModel: string,
 *     public readonly fallbackModel: string,
 *   ) {
 *     super(`Model fallback triggered: ${originalModel} -> ${fallbackModel}`)
 *     this.name = 'FallbackTriggeredError'
 *   }
 * }
 * </pre>
 *
 * <p><b>WHY</b>: CC withRetry.ts 在连续 529 达到阈值（MAX_529_RETRIES=3）时抛出此错误，
 * query.ts:894-953 捕获后切换 {@code currentModel = fallbackModel} + 清理消息 + 重建 executor +
 * yield warning system message 后重试。Java 端由 {@link TransientErrorHandler} 在连续 529
 * 达阈值且配置了 fallback 模型时抛出（默认源 = settings.fallbackModelId，经
 * {@link TransientErrorHandler#FALLBACK_MODEL_SUPPLIER} 读取；F4 由 env FALLBACK_MODEL_ID
 * 迁移而来，CC 无此 env），loop 捕获后对齐 CC 做同样的恢复。也可由 provider 层直接抛出
 * （如实现 CC 等价 withRetry）。
 */
public class FallbackTriggeredError extends RuntimeException {

    /** 触发 fallback 的原始模型。 */
    private final String originalModel;
    /** 切换到的备用模型。 */
    private final String fallbackModel;

    public FallbackTriggeredError(String originalModel, String fallbackModel) {
        super("Model fallback triggered: " + originalModel + " -> " + fallbackModel);
        this.originalModel = originalModel;
        this.fallbackModel = fallbackModel;
    }

    public String originalModel() {
        return originalModel;
    }

    public String fallbackModel() {
        return fallbackModel;
    }
}

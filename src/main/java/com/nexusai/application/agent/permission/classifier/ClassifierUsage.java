package com.nexusai.application.agent.permission.classifier;

/**
 * 分类器 API token usage · 对齐 CC {@code ClassifierUsage}
 * （Open-ClaudeCode/src/types/permissions.ts:339-343）。
 *
 * <p>CC 真源字段（snake_case → Java camelCase）:
 * <ul>
 *   <li>{@code inputTokens} ← CC {@code inputTokens}
 *       （permissions.ts:340，分类器 API 调用 input_tokens）</li>
 *   <li>{@code outputTokens} ← CC {@code outputTokens}
 *       （permissions.ts:341，output_tokens）</li>
 *   <li>{@code cacheReadInputTokens} ← CC {@code cacheReadInputTokens}
 *       （permissions.ts:342，cache_read_input_tokens ?? 0，yoloClassifier.ts:615）</li>
 *   <li>{@code cacheCreationInputTokens} ← CC {@code cacheCreationInputTokens}
 *       （permissions.ts:343，cache_creation_input_tokens ?? 0，yoloClassifier.ts:616）</li>
 * </ul>
 *
 * <p>CC extractUsage（yoloClassifier.ts:609-618）从 API 响应 usage 提取；Java 端
 * {@code LlmRawResponse} 不携带 usage（四字段 record 无 usage 通道），故 Java 结果
 * usage 字段可能为 null（provider 未暴露）。本 record 承载 CC 类型契约（⊕-01：
 * 旧 {@code usage: Integer} 单字段 → CC ClassifierUsage 4 字段）。
 *
 * @param inputTokens         input tokens（CC inputTokens）
 * @param outputTokens        output tokens（CC outputTokens）
 * @param cacheReadInputTokens    缓存读取 tokens（CC cacheReadInputTokens，缺省 0）
 * @param cacheCreationInputTokens 缓存创建 tokens（CC cacheCreationInputTokens，缺省 0）
 */
public record ClassifierUsage(
        int inputTokens,
        int outputTokens,
        int cacheReadInputTokens,
        int cacheCreationInputTokens
) {

    /**
     * 紧凑构造器：不变量保护 · 四个 token 计数均不可负（CC number 语义）。
     */
    public ClassifierUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens is negative: " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens is negative: " + outputTokens);
        }
        if (cacheReadInputTokens < 0) {
            throw new IllegalArgumentException("cacheReadInputTokens is negative: " + cacheReadInputTokens);
        }
        if (cacheCreationInputTokens < 0) {
            throw new IllegalArgumentException("cacheCreationInputTokens is negative: " + cacheCreationInputTokens);
        }
    }

    /**
     * 全零 usage 工厂 · 对齐 CC extractUsage 缺省（cache 字段 ?? 0）语义的 Java 载体。
     *
     * @return 4 字段全 0 的 ClassifierUsage
     */
    public static ClassifierUsage empty() {
        return new ClassifierUsage(0, 0, 0, 0);
    }
}

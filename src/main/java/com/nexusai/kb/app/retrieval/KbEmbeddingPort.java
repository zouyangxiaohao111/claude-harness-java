package com.nexusai.kb.app.retrieval;

import com.nexusai.application.embedding.EmbeddingService;
import com.nexusai.model.provider.dto.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 嵌入调用的薄封装 · 让检索/索引层<b>只依赖一个函数</b>，而不是整个 {@code EmbeddingService}。
 *
 * <p><b>WHY 需要这一层</b>（不是画蛇添足）：
 * <ul>
 *   <li>{@link EmbeddingService} 的 {@code embed()} 是具体方法且强依赖
 *       {@code ModelEndpointResolver}（会查库解 provider 配置）——单测无法注入假实现，
 *       只能连真模型，这违反「⛔ 禁 @SpringBootTest / 禁真网」。</li>
 *   <li>检索层实际只用到「文本 → 向量」这一个语义，把它提成接口后，
 *       测试可给确定性假向量（可断言排序），生产仍走真实服务。</li>
 * </ul>
 *
 * <p>⚠️ 生产实现 {@link ServiceBacked} 只做一件事：转调 {@link EmbeddingService}，
 * ⛔ 不在此处加业务逻辑（否则假实现与真实现的语义会分叉）。
 */
public interface KbEmbeddingPort {

    /**
     * 文本 → 向量。
     *
     * @param modelId 模型 ID
     * @param inputs  文本列表（非空）
     * @return 向量（顺序与 inputs 一致）
     */
    List<float[]> embed(String modelId, List<String> inputs);

    /** 生产实现：转调 {@link EmbeddingService}。 */
    @Component
    class ServiceBacked implements KbEmbeddingPort {

        private final EmbeddingService embeddingService;

        public ServiceBacked(EmbeddingService embeddingService) {
            this.embeddingService = embeddingService;
        }

        @Override
        public List<float[]> embed(String modelId, List<String> inputs) {
            EmbeddingResponse resp = embeddingService.embed(modelId, inputs, null);
            return resp == null || resp.vectors() == null ? List.of() : resp.vectors();
        }
    }
}

package com.nexusai.application.embedding;

import com.nexusai.application.provider.ModelEndpointResolver;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.embedding.EmbeddingClient;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.provider.dto.EmbeddingResponse;
import com.nexusai.repository.provider.entity.ModelRecord;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 向量嵌入应用服务 · 把「DB 里的模型配置」翻译成「一次 embeddings 调用」。
 *
 * <p><b>职责链</b>（顺序不可换）：
 * <ol>
 *   <li>按 {@code modelId} 取模型行 + provider 行；</li>
 *   <li><b>类型门</b>：{@code models.type} 必须是 {@code embedding} —— 对话/重排/多模态模型一律
 *       拒绝（规则十二 fail loud，⛔ 不静默拿 chat 模型去调 embeddings）；</li>
 *   <li>provider 必须 enabled 且有可解密的 API key；</li>
 *   <li>输入校验（条数 / 单条长度 / 总长度）—— 模型与网络之前拦，避免把超长文本发出去；</li>
 *   <li>取模型级配置：{@code embedding_dimensions}（可被请求覆盖）、{@code embedding_batch_size}
 *       （缺省 10）；</li>
 *   <li>交给 {@link EmbeddingClient}（OpenAI 兼容协议）。</li>
 * </ol>
 *
 * <p><b>本服务不写库、不落向量</b>：知识库/切片/向量存储是后续独立批次；本批只提供
 * 「给定模型 + 文本 → 向量」的通用能力，供未来 RAG 链路注入使用。
 *
 * <p><b>日志纪律</b>：只打印模型/条数/维度/token；⛔ 不打印文本正文、向量值、API key。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** 单请求文本条数上限（与 EmbeddingClient 的硬上限一致，服务层先拦）。 */
    static final int MAX_INPUTS = 64;
    /** 单条文本字符上限（粗粒度防滥用；token 上限由服务端判定）。 */
    static final int MAX_CHARS_PER_INPUT = 32_768;
    /** 单次请求总字符上限。 */
    static final long MAX_TOTAL_CHARS = 200_000;
    /** 模型未配置批大小时的默认值（百炼单请求上限）。 */
    static final int DEFAULT_BATCH_SIZE = 10;

    private final ModelEndpointResolver resolver;
    private final EmbeddingClient embeddingClient;

    @Autowired
    public EmbeddingService(ModelEndpointResolver resolver, EmbeddingClient embeddingClient) {
        this.resolver = resolver;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 向量化。
     *
     * @param modelId           模型行 id（{@code models.id}）
     * @param inputs            文本列表
     * @param dimensionsOverride 请求级维度覆盖（null → 用模型级配置）
     * @return 向量结果
     */
    public EmbeddingResponse embed(String modelId, List<String> inputs, Integer dimensionsOverride) {
        ModelEndpointResolver.Resolved resolved = resolver.resolve(modelId, "embedding");
        ModelRecord model = resolved.model();
        ProviderConfig config = resolved.config();

        validateInputs(inputs);
        int batchSize = resolveBatchSize(model);
        Integer dimensions = resolveDimensions(model, dimensionsOverride);

        if (log.isDebugEnabled()) {
            log.debug("[EmbeddingService] 发起向量化: model={} provider={} 文本数={} 维度={} 批大小={}",
                model.getName(), resolved.provider().getId(), inputs.size(), dimensions, batchSize);
        }
        EmbeddingClient.Result result = embeddingClient.embed(
            config, model.getName(), inputs, dimensions, batchSize);
        if (log.isInfoEnabled()) {
            log.info("[EmbeddingService] 向量化完成: model={} 条数={} 维度={} promptTokens={} 批次={}",
                model.getName(), result.vectors().size(), result.dimensions(),
                result.promptTokens(), result.batches());
        }
        return new EmbeddingResponse(model.getName(), result.dimensions(),
            result.vectors().size(), result.vectors(), result.promptTokens());
    }

    /** 类型门：只有 {@code models.type = embedding} 才允许走向量链路。 */
    static void assertEmbeddingModel(ModelRecord model) {
        ModelEndpointResolver.assertType(model, "embedding");
    }

    /**
     * 模型级批大小：显式配置优先；未配置时按模型名推断（对齐源站 catalog 的 max_batch_size）。
     *
     * <p>源站每个模型的上限不同（`text-embedding-v4`=10 / `v3`=10 / `qwen3.7-text-embedding`=20 /
     * `v2`=25，见 `internal/models/catalog/data/overrides.json`）。本仓没有模型目录，
     * 用名称近似映射覆盖常见百炼模型；识别不了就走默认 10（安全值）。
     *
     * <p>硬上限 64 不变：即使配置写大也不给单请求塞更多条（服务端通常远低于此值）。
     */
    static int resolveBatchSize(ModelRecord model) {
        Integer configured = model.getEmbeddingBatchSize();
        if (configured == null || configured <= 0) {
            return inferBatchSizeFromName(model.getName());
        }
        return Math.min(configured, 64);
    }

    /** 模型名 → 单请求上限推断；未识别 ⇒ {@link #DEFAULT_BATCH_SIZE}。 */
    static int inferBatchSizeFromName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        String name = modelName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("qwen3.7-text-embedding")) {
            return 20;
        }
        if (name.contains("text-embedding-v2")) {
            return 25;
        }
        if (name.contains("text-embedding-v4") || name.contains("text-embedding-v3")) {
            return 10;
        }
        return DEFAULT_BATCH_SIZE;
    }

    /** 维度优先级：请求覆盖 > 模型级配置；null/≤0 → null（不发送 dimensions）。 */
    static Integer resolveDimensions(ModelRecord model, Integer override) {
        Integer value = override != null ? override : model.getEmbeddingDimensions();
        return value == null || value <= 0 ? null : value;
    }

    /** 输入校验：非空、条数上限、单条长度、总长度。 */
    static void validateInputs(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ValidationException("inputs 不能为空");
        }
        if (inputs.size() > MAX_INPUTS) {
            throw new ValidationException("inputs 最多 " + MAX_INPUTS + " 条");
        }
        long total = 0;
        for (int i = 0; i < inputs.size(); i++) {
            String text = inputs.get(i);
            if (text == null || text.isBlank()) {
                throw new ValidationException("inputs[" + i + "] 不能为空");
            }
            if (text.length() > MAX_CHARS_PER_INPUT) {
                throw new ValidationException("inputs[" + i + "] 超过 "
                    + MAX_CHARS_PER_INPUT + " 字符");
            }
            total += text.length();
        }
        if (total > MAX_TOTAL_CHARS) {
            throw new ValidationException("inputs 总字符数超过 " + MAX_TOTAL_CHARS);
        }
    }
}

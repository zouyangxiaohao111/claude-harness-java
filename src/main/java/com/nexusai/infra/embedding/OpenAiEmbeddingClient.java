package com.nexusai.infra.embedding;

import com.nexusai.infra.llm.OpenAiSdkProvider;
import com.nexusai.infra.llm.ProviderConfig;

import com.openai.client.OpenAIClient;
import com.openai.models.CreateEmbeddingResponse;
import com.openai.models.Embedding;
import com.openai.models.EmbeddingCreateParams;
import com.openai.models.EmbeddingModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容向量嵌入实现 · 官方 openai-java SDK（与对话链路同一 SDK 族）。
 *
 * <p>端点：{@code POST {baseUrl}/embeddings}（SDK 在 baseUrl 后追加固定路径）。
 * baseUrl 由 provider 配置给出；百炼填
 * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 实际请求即 {@code .../v1/embeddings}。
 *
 * <p><b>分批</b>：百炼单请求最多 10 条文本，其它兼容端点可能更宽。批大小由模型配置
 * （{@code models.embedding_batch_size}）决定，缺省 10，硬上限 {@value #MAX_BATCH_SIZE}。
 * 多批次结果按批次顺序拼接，批内按响应 {@code index} 排序 ⇒ 最终顺序与入参一致。
 *
 * <p><b>维度</b>：{@code dimensions} 有值才发送；百炼 text-embedding-v4 支持
 * 2048/1536/1024/768/512/256/128/64。响应实际维度与请求不一致时 WARN（不静默改配置）。
 *
 * <p><b>日志纪律</b>：只打印模型名 / 文本条数 / 批次 / 维度 / token / 耗时；
 * ⛔ 不打印文本正文、不打印向量值、不打印 key。
 */
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    /** 百炼单请求上限（安全默认）· 模型未配置 batch size 时使用。 */
    static final int DEFAULT_BATCH_SIZE = 10;

    /** 硬上限 · 防止误配成超大单请求（服务端通常远低于此值）。 */
    static final int MAX_BATCH_SIZE = 64;

    /**
     * 可重试错误的尝试次数（含首次）。
     *
     * <p>对齐源站 {@code batchEmbedWithBackoff}：{@code embedRetryAttempts = 5}
     * （{@code internal/application/service/retriever/keywords_vector_hybrid_indexer.go:30}）。
     */
    static final int MAX_ATTEMPTS = 5;

    /**
     * 首次退避 200ms，之后每次翻倍：200 / 400 / 800 / 1600 / 3200 ms。
     *
     * <p>与源站 {@code embedRetryBaseDelay = 200 * time.Millisecond} 同值。
     */
    static final long BASE_BACKOFF_MS = 200L;

    @Override
    public Result embed(ProviderConfig config, String modelName, List<String> inputs,
                        Integer dimensions, int batchSize) {
        if (config == null || !config.isUsable()) {
            throw new IllegalArgumentException("embedding provider 未配置可用的 API key");
        }
        if (inputs == null || inputs.isEmpty()) {
            return new Result(List.of(), modelName, 0, 0, 0);
        }

        int bs = batchSize <= 0 ? DEFAULT_BATCH_SIZE : Math.min(batchSize, MAX_BATCH_SIZE);
        OpenAIClient client = OpenAiSdkProvider.buildClient(config, null);

        List<float[]> vectors = new ArrayList<>(inputs.size());
        long promptTokens = 0;
        int batches = 0;
        long startedAt = System.nanoTime();
        try {
            for (int from = 0; from < inputs.size(); from += bs) {
                List<String> batch = inputs.subList(from, Math.min(from + bs, inputs.size()));
                EmbeddingCreateParams.Builder params = EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.of(modelName))
                    .inputOfArrayOfStrings(batch)
                    .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT);
                if (dimensions != null && dimensions > 0) {
                    params.dimensions(dimensions.longValue());
                }
                CreateEmbeddingResponse response = createWithRetry(client, params.build(), modelName);

                List<Embedding> data = new ArrayList<>(response.data());
                // 响应 index 是批内下标；排序后与 batch 顺序对齐（防御服务端乱序返回）
                data.sort(Comparator.comparingLong(Embedding::index));
                for (Embedding embedding : data) {
                    List<Double> raw = embedding.embedding();
                    float[] vector = new float[raw.size()];
                    for (int i = 0; i < raw.size(); i++) {
                        vector[i] = raw.get(i).floatValue();
                    }
                    vectors.add(vector);
                }
                if (response.usage() != null) {
                    promptTokens += response.usage().promptTokens();
                }
                batches++;
            }
        } catch (Exception e) {
            // 显式失败（规则十二）：模型/批次数可见；⛔ 不把文本正文写进日志或异常消息。
            log.error("[OpenAiEmbeddingClient] 向量化调用失败: model={} 文本数={} 批次={} err={}",
                modelName, inputs.size(), batches, e.toString());
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }

        int actualDimensions = vectors.isEmpty() ? 0 : vectors.get(0).length;
        if (dimensions != null && dimensions > 0 && actualDimensions > 0
                && actualDimensions != dimensions) {
            log.warn("[OpenAiEmbeddingClient] 响应维度与请求不一致: model={} 请求={} 实际={}"
                    + "（以服务端返回为准；请核对模型支持的维度档位）",
                modelName, dimensions, actualDimensions);
        }
        if (log.isInfoEnabled()) {
            log.info("[OpenAiEmbeddingClient] 向量化完成: model={} 文本数={} 批次={} 维度={} "
                    + "promptTokens={} 耗时={}ms",
                modelName, vectors.size(), batches, actualDimensions, promptTokens,
                (System.nanoTime() - startedAt) / 1_000_000);
        }
        return new Result(List.copyOf(vectors), modelName, actualDimensions, promptTokens, batches);
    }

    /**
     * 单次请求 + 指数退避重试。
     *
     * <p>只对「限流 / 服务端 5xx / 超时」重试；其它错误（400 参数错、401 鉴权错）立即抛出 ——
     * 重试只会把同一个错误再打一遍。
     */
    private static CreateEmbeddingResponse createWithRetry(
            OpenAIClient client, EmbeddingCreateParams params, String modelName) {
        long backoff = BASE_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return client.embeddings().create(params);
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e;
                }
                log.warn("[OpenAiEmbeddingClient] 可重试错误，{}ms 后重试（第 {}/{} 次）: model={} err={}",
                    backoff, attempt, MAX_ATTEMPTS - 1, modelName, e.toString());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff *= 2;
            }
        }
    }

    /** 异常链任一层命中限流 / 5xx / 超时 ⇒ 可重试。 */
    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String text = (t.getClass().getSimpleName() + " " + t.getMessage()).toLowerCase();
            if (text.contains("429")
                    || text.contains("rate limit")
                    || text.contains("insufficient_quota")
                    || text.contains("too many requests")
                    || text.contains("overloaded")
                    || text.contains("timeout")
                    || text.contains("timed out")
                    || java.util.regex.Pattern.compile("\\b(500|502|503|504)\\b").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}

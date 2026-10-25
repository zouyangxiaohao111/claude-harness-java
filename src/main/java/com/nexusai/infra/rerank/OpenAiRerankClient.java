package com.nexusai.infra.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.infra.llm.ProviderConfig;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * OpenAI/Cohere 兼容重排实现 · 只做 schema 映射，HTTP 生命周期由
 * {@link AbstractJsonRerankClient} 承担。
 *
 * <p><b>端点推导</b>（用户 2026-10-26 裁定：不新增 provider type）：
 * <ul>
 *   <li>百炼：provider baseUrl 通常是 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}
 *       （对话/向量用）⇒ 自动替换为
 *       {@code https://dashscope.aliyuncs.com/compatible-api/v1/reranks}；</li>
 *   <li>其它 OpenAI/Cohere 兼容端点：{@code {baseUrl}/reranks}；</li>
 *   <li>baseUrl 已以 {@code /reranks} 结尾：原样使用（逃生口）。</li>
 * </ul>
 *
 * <p><b>wire schema</b>：请求 {@code {model, query, documents[], top_n?, instruct?}}；
 * 响应 {@code {results[{index, relevance_score, document?}], usage.total_tokens}}。
 */
@Component
public class OpenAiRerankClient extends AbstractJsonRerankClient<OpenAiRerankClient.OpenAiRerankRequest, JsonNode> {

    /** OpenAI 兼容重排的 wire 请求（topN/instruct 可空）。 */
    public record OpenAiRerankRequest(String model, String query, List<String> documents,
                                      Integer topN, String instruct) {
    }

    @Override
    public String protocol() {
        return "openai-compatible-rerank";
    }

    @Override
    protected OpenAiRerankRequest buildRequest(RerankCommand command) {
        return new OpenAiRerankRequest(command.model(), command.query(),
            command.documents(), command.topN(), command.instruct());
    }

    @Override
    protected String endpoint(ProviderConfig config) {
        return rerankUrl(config.baseUrl());
    }

    @Override
    protected ObjectNode toJson(OpenAiRerankRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model());
        body.put("query", request.query());
        ArrayNode documents = body.putArray("documents");
        for (String document : request.documents()) {
            documents.add(document);
        }
        if (request.topN() != null && request.topN() > 0) {
            body.put("top_n", request.topN());
        }
        if (request.instruct() != null && !request.instruct().isBlank()) {
            body.put("instruct", request.instruct());
        }
        return body;
    }

    @Override
    protected JsonNode fromJson(JsonNode node) {
        return node;
    }

    @Override
    protected RerankResult parse(JsonNode root, RerankCommand command) {
        List<RerankScore> scores = new ArrayList<>();
        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                double score = result.path("relevance_score").asDouble(Double.NaN);
                String document = null;
                if (result.hasNonNull("document")) {
                    JsonNode docNode = result.path("document");
                    document = docNode.isTextual() ? docNode.asText() : docNode.path("text").asText(null);
                }
                scores.add(new RerankScore(index, score, document));
            }
        }
        long totalTokens = root.path("usage").path("total_tokens").asLong(0);
        String model = root.path("model").asText(command.model());
        return new RerankResult(List.copyOf(scores), model, totalTokens);
    }

    /**
     * 从 provider baseUrl 推导重排序端点（纯函数，便于单测）。
     *
     * <pre>
     * .../compatible-mode/v1      → .../compatible-api/v1/reranks   （百炼）
     * .../v1                      → .../v1/reranks                  （通用 OpenAI/Cohere 兼容）
     * .../reranks                 → 原样
     * </pre>
     *
     * @param baseUrl provider 配置的 baseUrl
     * @return 重排序完整 URL
     */
    static String rerankUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("rerank provider 未配置 baseUrl");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/reranks")) {
            return url;
        }
        if (url.contains("/compatible-mode/")) {
            return url.replace("/compatible-mode/", "/compatible-api/") + "/reranks";
        }
        return url + "/reranks";
    }
}

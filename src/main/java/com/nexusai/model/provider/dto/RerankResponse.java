package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 重排序响应体。
 *
 * @param model       实际使用的模型名
 * @param count       返回条数
 * @param results     排序结果（以服务端顺序为准，通常按 relevance_score 降序）
 * @param totalTokens 服务端上报的总 token（未上报 → 0）
 */
public record RerankResponse(String model, int count, List<RerankItem> results, long totalTokens) {
}

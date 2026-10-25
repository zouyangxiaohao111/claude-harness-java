package com.nexusai.infra.rerank;

import java.util.List;

/**
 * 重排结果（厂商无关）。
 *
 * @param scores      排序结果（以服务端返回顺序为准，通常按分数降序）
 * @param model       实际模型名
 * @param totalTokens 服务端上报的总 token（未上报 → 0）
 */
public record RerankResult(List<RerankScore> scores, String model, long totalTokens) {
}

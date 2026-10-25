package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 向量嵌入响应体。
 *
 * @param model        实际使用的模型名
 * @param dimensions   实际向量维度（取服务端返回的维度，可能与请求不同 → 服务端会 WARN）
 * @param count        向量条数（= inputs 中的有效文本数）
 * @param vectors      向量列表，与 inputs 顺序一一对应；每条是 float 数组
 * @param promptTokens 服务端上报的 prompt token 合计（多批次累加；未上报 → 0）
 */
public record EmbeddingResponse(String model, int dimensions, int count,
                                List<float[]> vectors, long promptTokens) {
}

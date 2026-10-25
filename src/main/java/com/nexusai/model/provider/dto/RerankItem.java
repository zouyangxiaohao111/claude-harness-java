package com.nexusai.model.provider.dto;

/**
 * 单条重排序结果。
 *
 * @param index          原始 documents 下标
 * @param relevanceScore 相关性分数（越大越相关）
 * @param document       服务端回显的文档文本（未回显 → null）
 */
public record RerankItem(int index, double relevanceScore, String document) {
}

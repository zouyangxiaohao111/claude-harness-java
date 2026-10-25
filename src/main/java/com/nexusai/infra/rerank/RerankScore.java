package com.nexusai.infra.rerank;

/**
 * 单条重排结果（厂商无关）。
 *
 * @param index    原始 documents 下标
 * @param score    相关性分数（越大越相关；各厂商量纲可能不同，调用方不应跨厂商比较）
 * @param document 服务端回显的文档文本（未回显 → null）
 */
public record RerankScore(int index, double score, String document) {
}

package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 向量嵌入请求体 · {@code POST /api/v1/providers/{providerId}/models/{id}/embed}。
 *
 * @param inputs     待向量化文本（必填，1..64 条；单条 ≤32768 字符）
 * @param dimensions 目标维度（可选；null → 用模型级配置 {@code models.embedding_dimensions}，
 *                   该值也为 null 时不发送 {@code dimensions}，由服务端使用模型默认维度）
 */
public record EmbeddingRequest(List<String> inputs, Integer dimensions) {
}

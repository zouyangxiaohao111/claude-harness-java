package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 重排序请求体 · {@code POST /api/v1/providers/{providerId}/models/{id}/rerank}。
 *
 * @param query     查询（必填）
 * @param documents 候选文档（必填，1..500 条；单条 ≤32768 字符）
 * @param topN      返回前 N 条（可选；null → 服务端默认；超过候选数 → 收敛到候选数）
 * @param instruct  指令（可选；qwen3-rerank 支持）
 */
public record RerankRequest(String query, List<String> documents, Integer topN, String instruct) {
}

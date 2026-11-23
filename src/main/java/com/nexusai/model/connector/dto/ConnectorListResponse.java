package com.nexusai.model.connector.dto;

import java.util.List;

/**
 * 连接器列表响应 · {@code GET /api/market/connectors}。
 *
 * @param connectors 连接器视图（默认不含 hidden 条目）
 */
public record ConnectorListResponse(List<ConnectorDto> connectors) {
}

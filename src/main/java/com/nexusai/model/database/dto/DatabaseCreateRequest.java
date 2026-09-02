package com.nexusai.model.database.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/databases 请求 · PATCH 也复用此结构（全字段可选） */
public record DatabaseCreateRequest(
    @NotBlank @Size(max = 64) String name,
    DatabaseType type,                               // 缺省 postgres
    String host,
    Integer port,
    String database,
    String user,
    String password                                  // 明文；后端 hash 后存库，响应里 mask
) {}

package com.nexusai.model.database.dto;

/** 响应：数据库连接完整信息（passwordMasked 恒为 "****"） */
public record DatabaseConnectionDto(
    String id,
    String name,
    DatabaseType type,
    String host,
    Integer port,
    String database,
    String user,
    String passwordMasked,                           // 永远 "****"，绝不暴露 hash
    DatabaseStatus status,
    String lastError
) {}

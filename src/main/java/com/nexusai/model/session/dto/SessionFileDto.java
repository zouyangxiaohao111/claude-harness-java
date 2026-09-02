package com.nexusai.model.session.dto;

/** 响应：会话文件变更（按 openapi 规范，无 id 字段） */
public record SessionFileDto(
    String path,
    String status,        // 'modified'|'added'|'deleted'|'renamed'
    Integer additions,
    Integer deletions,
    String oldRev,
    String newRev
) {}

package com.nexusai.model.search.dto;

/** 响应：搜索结果项（v1 placeholder shape） */
public record SearchItemDto(
    String type,           // 'session' | 'message' | 'file' | 'skill' ...
    String title,
    String subtitle,
    String id
) {}

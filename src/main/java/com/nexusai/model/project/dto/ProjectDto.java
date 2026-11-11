package com.nexusai.model.project.dto;

import java.time.OffsetDateTime;

/** 响应：Project 完整信息 */
public record ProjectDto(
    String id,
    String name,
    String path,
    String branch,
    Integer dirty,
    Integer agents,
    OffsetDateTime lastIndexedAt,
    boolean bound,
    /**
     * 注册时间（V280 列 projects.created_at）· 左栏「任务」区**项目分组序**的唯一依据。
     *
     * <p>口径 = 注册先后（升序，先注册的在前），**不随会话活动变化** —— 这是 2026-11-10 用户裁定：
     * 组间序排完不动，只有组内会话序按 updatedAt 动。null = 迁移前未回填异常行（前端排在有值之后）。
     */
    OffsetDateTime createdAt
) {}

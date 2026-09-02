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
    boolean bound
) {}

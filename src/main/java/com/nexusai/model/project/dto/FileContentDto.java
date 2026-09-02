package com.nexusai.model.project.dto;

/**
 * 项目文件内容（GET /api/v1/projects/{id}/file?path= 响应）。
 *
 * <p>path 为仓库相对路径（与文件树 FileNodeDto.path 一致）。
 */
public record FileContentDto(
    String path,
    String content,
    long size
) {}

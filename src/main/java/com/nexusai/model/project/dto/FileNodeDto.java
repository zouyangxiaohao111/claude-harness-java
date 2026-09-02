package com.nexusai.model.project.dto;

import java.util.List;

/**
 * 项目文件树节点（GET /api/v1/projects/{id}/files 响应）。
 *
 * <p>IDE 项目结构树：目录节点含 children（可能为 null=未展开/空目录），
 * 文件节点 children 恒 null。path 为仓库相对路径（git ls-files 语义）。
 */
public record FileNodeDto(
    String name,
    String path,
    String type, // "dir" | "file"
    List<FileNodeDto> children
) {}

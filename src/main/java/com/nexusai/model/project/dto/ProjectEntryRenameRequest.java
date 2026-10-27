package com.nexusai.model.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 项目文件树「重命名」请求（只允许同目录内改名）。
 *
 * @param path    原项目内相对路径（/ 分隔）
 * @param newName 新名称（不能含路径分隔符）
 */
public record ProjectEntryRenameRequest(
    @NotBlank String path,
    @NotBlank String newName
) {}

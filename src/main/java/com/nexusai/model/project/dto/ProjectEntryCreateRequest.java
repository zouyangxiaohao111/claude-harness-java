package com.nexusai.model.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 项目文件树「新建文件/文件夹」请求。
 *
 * @param path 项目内相对路径（/ 分隔）
 * @param type {@code dir}/{@code folder} = 目录；其余（含 null）= 文件
 */
public record ProjectEntryCreateRequest(
    @NotBlank String path,
    String type
) {}

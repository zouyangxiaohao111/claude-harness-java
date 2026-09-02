package com.nexusai.model.command.dto;

import com.nexusai.model.command.CommandSource;

import java.util.List;

/**
 * Command 响应 DTO · 对齐 CC command.ts PromptCommand 对外暴露字段
 *
 * <p>与 {@link CommandDto} 区别：此 DTO 包含完整内容（content 字段），
 * 用于单个命令详情接口。
 */
public record CommandDto(
    String id,
    String name,
    String description,
    String version,
    CommandSource source,
    List<String> aliases,
    String argumentHint,
    String whenToUse,
    boolean userInvocable,
    boolean disableModelInvocation,
    boolean isHidden,
    boolean isSensitive,
    boolean immediate,
    String kind,
    String context,
    String agent,
    List<String> allowedTools,
    String model,
    String effort,
    List<String> paths,
    String hooks,
    String content,
    String contentPath,
    String baseDir,
    String progressMessage,
    boolean enabled,
    boolean builtin,
    /** 命令类型 · 对齐 Command.type（CC types/command.ts:26 PromptCommand.type = 'prompt'；
     *  local / local-jsx 全集属 M23 范围）。默认 'prompt'，供前端区分 prompt/local/local-jsx 触发路径。 */
    String type,
    /** 所属插件名（source='plugin'/'bundled' 时来自 pluginInfo.pluginManifest.name · 前端 /插件名 提示其技能） */
    String pluginName
) {}

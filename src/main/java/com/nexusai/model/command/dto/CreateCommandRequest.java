package com.nexusai.model.command.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/command 创建请求 · 对齐 CC SKILL.md frontmatter 字段
 *
 * <p>核心字段（对应 SKILL.md YAML frontmatter）：
 * <ul>
 *   <li>name — 命令名称（必填，≤64 字符）</li>
 *   <li>description — 命令描述</li>
 *   <li>allowedTools — 允许的工具列表</li>
 *   <li>model — 模型覆盖</li>
 *   <li>context — 执行上下文（inline | fork）</li>
 *   <li>agent — fork 模式的 agent 类型</li>
 *   <li>userInvocable — 用户是否可调用</li>
 *   <li>disableModelInvocation — 禁止模型调用</li>
 *   <li>version — 版本号</li>
 * </ul>
 */
public record CreateCommandRequest(
    @NotBlank @Size(max = 64) String name,
    String description,
    String content,                                       // SKILL.md 正文
    List<String> aliases,
    List<String> allowedTools,
    String model,
    String context,                                       // 'inline' | 'fork'
    String agent,
    List<String> paths,                                   // 文件路径 glob 模式
    String version,
    String argumentHint,
    String whenToUse,
    String effort,
    String hooks,                                         // JSON
    Boolean userInvocable,
    Boolean disableModelInvocation,
    Boolean enabled                                       // 缺省 = true
) {}

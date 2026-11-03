package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 拉取远端模型清单的结果。
 *
 * <p>{@code ok=false} 时 {@code models} 为空列表，{@code message} 说明原因（已翻译成中文给用户看）。
 * 失败<b>不抛异常</b>：拉模型是「尽力而为」的辅助动作，失败不该打断用户正在做的配置流程
 * —— 前端据此保留「手动添加」入口。
 *
 * @param ok      是否拉取成功
 * @param models  归一化后的模型清单（成功时非空；提供商返回空列表时也是成功）
 * @param message 失败原因 / 附加说明
 */
public record RemoteModelsResponse(
    boolean ok,
    List<RemoteModelDto> models,
    String message
) {}

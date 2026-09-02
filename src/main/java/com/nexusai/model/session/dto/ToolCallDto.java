package com.nexusai.model.session.dto;

/** 工具调用记录 DTO（嵌入在 ChatMessageDto.toolCalls[] 中） */
public record ToolCallDto(
    String id,
    String name,
    String arguments,       // JSON 字符串，Phase 5 由前端反序列化
    String result,
    Boolean isError
) {}

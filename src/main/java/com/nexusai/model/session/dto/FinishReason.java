package com.nexusai.model.session.dto;

/** 模型生成结束原因 */
public enum FinishReason {
    stop,
    length,
    tool_calls,
    content_filter,
    error
}

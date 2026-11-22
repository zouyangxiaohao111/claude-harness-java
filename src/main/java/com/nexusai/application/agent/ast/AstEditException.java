package com.nexusai.application.agent.ast;

/** 结构化改写失败（重叠 / 越界 / 上限 / 预览过期）· 工具层 catch 后转 {@code ToolResult.error}。 */
public class AstEditException extends RuntimeException {

    public AstEditException(String message) {
        super(message);
    }
}

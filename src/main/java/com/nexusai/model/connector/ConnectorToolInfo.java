package com.nexusai.model.connector;

/**
 * 连接器测得的单个 MCP 工具（name + description）· {@code TestResponse.tools[]} 元素。
 *
 * <p>对齐 Octop 探测结果的信息结构（工具名 + 描述），前端据此渲染真实工具列表，
 * 而不是只看「tools=N」一行字。
 */
public record ConnectorToolInfo(String name, String description) {
}

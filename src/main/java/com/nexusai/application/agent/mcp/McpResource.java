package com.nexusai.application.agent.mcp;

import java.util.Objects;

/**
 * MCP 资源元数据 · 对齐 CC {@code ServerResource} + 追加 server 字段
 * （client.ts:2017-2020 {@code result.resources.map(resource => ({...resource, server: client.name}))}）。
 *
 * <p>CC ListMcpResourcesTool.ts outputSchema（:25-35）：
 * {@code {uri, name, mimeType?, description?, server}}。
 *
 * @param uri         资源 URI（必填 · ListResourcesResultSchema）
 * @param name        资源名（必填 · ListResourcesResultSchema）
 * @param mimeType    资源 MIME 类型（可选 · CC outputSchema {@code mimeType?: string}）
 * @param description 资源描述（可选 · CC outputSchema {@code description?: string}）
 * @param server      提供该资源的 MCP server 名（client.ts:2019 追加）
 */
public record McpResource(
        String uri,
        String name,
        String mimeType,
        String description,
        String server
) {
    public McpResource {
        Objects.requireNonNull(uri, "uri");
    }
}

package com.nexusai.model.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * POST /api/v1/mcp 请求 · PATCH 也复用此结构（全字段可选）。
 *
 * <p>对齐 CC {@code claude mcp add}（addCommand.ts:33-280）新增远程 server 支持：
 * {@code url}（sse/http）/ {@code headers} / {@code oauth} / {@code clientSecret}
 * （keychain 等价物，不进 config）/ {@code scope}（写目标，REST 缺省 project，AC-1.3）。
 *
 * <p>{@code command} 去 {@code @NotBlank} 改条件校验（CC addCommand 三分发）：stdio 必填
 * command，sse/http 必填 url——否则远程 server 只填 url 会被原 @NotBlank 误拒。
 */
public record McpCreateRequest(
    @NotBlank @Size(max = 64) String name,
    String command,                                   // stdio 必填；sse/http 用 url（条件校验）
    List<String> args,
    Map<String, String> env,
    Boolean enabled,                                  // 缺省 = true
    @Size(max = 32) String type,                      // 传输类型 stdio|sse|http，缺省 = stdio（T8）
    String url,                                       // 远程（sse/http）地址
    Map<String, String> headers,                      // 远程请求头（sse/http）
    McpOAuthRequest oauth,                            // 远程 OAuth 配置（sse/http）
    String clientSecret,                              // OAuth client secret（仅当 clientId 同时存在才落 keychain）
    @Size(max = 32) String scope                      // local|user|project|dynamic|enterprise|claudeai|managed
) {
    public McpCreateRequest {
        // REST 缺省 project（G5 以 .mcp.json 为对象；CC CLI 缺省 local 是 CLI 交互选择，AC-1.3 显式偏离）
        scope = (scope == null || scope.isBlank()) ? "project" : scope;
    }

    /** 既有 6 参便捷构造（缺省远程字段 + scope=project）· 兼容既有调用方/测试。 */
    public McpCreateRequest(String name, String command, List<String> args,
                            Map<String, String> env, Boolean enabled, String type) {
        this(name, command, args, env, enabled, type, null, null, null, null, null);
    }
}

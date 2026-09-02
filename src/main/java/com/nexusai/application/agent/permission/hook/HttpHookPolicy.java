package com.nexusai.application.agent.permission.hook;

import java.util.List;

/**
 * HTTP hook 全局策略 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/execHttpHook.ts:49-58}
 * {@code getHttpHookPolicy()} 返回结构.
 *
 * <p>WHY: CC http hook 的 URL allowlist 与 env var allowlist 是<b>全局策略</b>
 * (从 merged settings 读取), 而非单个 hook 配置字段. 这与 MCP server allowlist
 * 同一套语义: {@code undefined} -> 不限制; {@code []} -> 全拦; 非空 -> 须匹配.
 * 本 record 承载这两个策略字段, 供 {@link ExecHttpHook} 在请求前做 allowlist 校验
 * 与 env var 双重白名单求交集 (CC execHttpHook.ts:137-145, 163-167).
 *
 * <p><b>三态语义 (对齐 CC)</b>:
 * <ul>
 *   <li>{@code allowedUrls == null} (undefined) -> 不限制 URL (任意 URL 可请求)</li>
 *   <li>{@code allowedUrls == []} (空 list) -> 全拦 (管理员显式禁止所有 http hook)</li>
 *   <li>{@code allowedUrls 非空} -> URL 必须匹配某 pattern (通配符 * 语义)</li>
 *   <li>{@code allowedEnvVars == null} -> 不限制 (用 hook 自身 allowedEnvVars)</li>
 *   <li>{@code allowedEnvVars 非空} -> 与 hook.allowedEnvVars 求交集 (最严限制生效)</li>
 * </ul>
 *
 * @param allowedUrls    CC original: allowedHttpHookUrls (execHttpHook.ts:55) · null=undefined=不限制
 * @param allowedEnvVars CC original: httpHookAllowedEnvVars (execHttpHook.ts:56) · null=undefined=不限制
 */
public record HttpHookPolicy(
    List<String> allowedUrls,
    List<String> allowedEnvVars
) {
}

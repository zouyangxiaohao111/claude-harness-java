package com.nexusai.domain.connector;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.connector.ConnectorDefinition;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器端点/请求头模板渲染（[2026-11-23] 官方 MCP 接入）。
 *
 * <p>支持两类模板：
 * <ul>
 *   <li>URL：{@code https://mcp.map.baidu.com/mcp?ak={ak}}（占位值 URL-encode）</li>
 *   <li>Header：{@code X-Lark-MCP-UAT: {accessToken}}（原样替换）</li>
 * </ul>
 * 缺失/空占位 ⇒ {@link ValidationException}（明确报字段名，⛔ 不生成半截 URL 去请求）。
 */
public final class ConnectorEndpointResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private ConnectorEndpointResolver() {
    }

    /** 渲染 URL 模板（占位值 URL-encode）。 */
    public static String renderUrl(String template, Map<String, String> values) {
        return render(template, values, true);
    }

    /** 渲染 header 值模板（占位值原样替换）。 */
    public static String renderHeader(String template, Map<String, String> values) {
        return render(template, values, false);
    }

    /** 连接器实际绑定 URL（query 模板优先，其次 mcpUrl）。 */
    public static String endpoint(ConnectorDefinition def, Map<String, String> values) {
        String template = def.endpointTemplate();
        if (template == null || template.isBlank()) {
            throw new ValidationException("连接器「" + def.name() + "」缺少 MCP 端点地址");
        }
        return renderUrl(template, values);
    }

    /** 连接器需要注入的请求头（占位值替换后）。 */
    public static Map<String, String> headers(ConnectorDefinition def, Map<String, String> values) {
        if (def.mcpHeaders() == null || def.mcpHeaders().isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        def.mcpHeaders().forEach((k, v) -> out.put(k, renderHeader(v, values)));
        return out;
    }

    private static String render(String template, Map<String, String> values, boolean urlEncode) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String field = m.group(1);
            String raw = values == null ? null : values.get(field);
            if (raw == null || raw.isBlank()) {
                throw new ValidationException("缺少凭据字段：" + field);
            }
            String value = urlEncode ? URLEncoder.encode(raw, StandardCharsets.UTF_8) : raw;
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

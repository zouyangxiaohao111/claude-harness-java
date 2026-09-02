package com.nexusai.infra.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API 访问日志配置 · 绑定 application.yml {@code nexusai.access-log} 段。
 *
 * <p>控制 {@link ApiAccessLogInterceptor} 是否打印「开始请求 / 完成请求」两条日志。
 * 背景：前端 2s 轮询端点（GET {@code /api/v1/schedules} 定时任务 + GET
 * {@code /api/v1/tasks} 异步任务）每 2s 打后端，默认全打会刷屏；本配置支持
 * 总开关关闭或按 URI 前缀排除。
 *
 * <p>配置示例（application.yml）：
 * <pre>{@code
 * nexusai:
 *   access-log:
 *     enabled: true
 *     exclude-uris:
 *       - /api/v1/schedules
 *       - /api/v1/tasks
 * }</pre>
 *
 * <p>匹配规则：请求 URI 以 {@code exclude-uris} 中任一 pattern 开头即命中
 * （前缀匹配，startsWith）。字段默认值保持现状行为（默认打印全部请求日志）。
 *
 * <p>注册模式：{@code @Component + @ConfigurationProperties}，组件扫描自动注册，
 * 可注入任意消费方（对齐 WebConfig 字段注入）。
 */
@Component
@ConfigurationProperties(prefix = "nexusai.access-log")
public class ApiAccessLogProperties {

    /**
     * 请求访问日志总开关 · 默认 true（保持现状打印）。
     * false = 全部请求静默，不打印「开始请求 / 完成请求」两条日志。
     */
    private boolean enabled = true;

    /**
     * 排除 URI 前缀列表 · 请求 URI 以任一前缀开头则跳过日志（前缀匹配）。
     * 空列表 = 不排除任何请求。典型用途：排除前端 2s 轮询端点
     * （/api/v1/schedules / /api/v1/tasks）。
     */
    private List<String> excludeUris = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludeUris() {
        return excludeUris;
    }

    public void setExcludeUris(List<String> excludeUris) {
        this.excludeUris = excludeUris == null ? new ArrayList<>() : excludeUris;
    }
}

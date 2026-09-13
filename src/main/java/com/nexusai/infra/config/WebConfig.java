package com.nexusai.infra.config;

import com.nexusai.infra.filter.ApiAccessLogInterceptor;
import com.nexusai.infra.filter.CacheRequestBodyFilter;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置：允许 Tauri 开发服务器（默认 tauri://localhost 或 http://localhost:1420）跨域
 *
 * <p>v1 简单粗暴用 allowedOriginPatterns("*")，因为是本地单用户应用，不存在安全风险。
 * v2 接入鉴权后收紧。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * API 访问日志 Interceptor（@Component bean）· [access-log 可配置] yml
     * {@code nexusai.access-log} 控制是否打印 / 排除 2s 轮询端点，不再手动 new。
     */
    @Autowired
    private ApiAccessLogInterceptor apiAccessLogInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-Trace-Id")
            .allowCredentials(false)
            .maxAge(3600);

        // WebSocket 在 Phase 5 配置，单独处理
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAccessLogInterceptor);
    }
    /**
     * 创建 RequestBodyCacheFilter Bean，可重复读取请求内容
     */
    @Bean
    public FilterRegistrationBean<CacheRequestBodyFilter> requestBodyCacheFilter() {
        return createFilterBean(new CacheRequestBodyFilter(), Integer.MIN_VALUE + 500);
    }

    public static <T extends Filter> FilterRegistrationBean<T> createFilterBean(T filter, Integer order) {
        FilterRegistrationBean<T> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(order);
        return bean;
    }
}
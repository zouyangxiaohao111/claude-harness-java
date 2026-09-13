package com.nexusai.infra.config;

import com.nexusai.infra.filter.ApiAccessLogInterceptor;
import com.nexusai.infra.filter.CacheRequestBodyFilter;
import com.nexusai.infra.filter.RequestContextCleanupFilter;
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

    /**
     * [TL-W3] 入站 MDC 统一清理 Filter（最外层）· 修「Tomcat 线程复用跨会话残留」。
     *
     * <p><b>WHY</b>：全仓无任何 Filter/Interceptor 清 {@link com.nexusai.common.RequestContext}，
     * 4 个 REST 入口只 {@code setSession} 不 {@code clear}（TeamController/TaskController/
     * MemoryController×2）⇒ 线程归还池后 {@code sessionId} 残留，下游按「query 缺参 → MDC 兜底」
     * 读取就会静默串到上一会话（team 名册 / 任务清单 / 记忆文件列表 / hook 载荷的
     * session_id+cwd+transcript_path）。本 Filter 放最外层（{@code Integer.MIN_VALUE}），
     * 其 {@code finally} 在全部 Filter/Interceptor 之后执行 ⇒ 请求边界单点清理，不依赖控制器自觉。
     *
     * <p><b>与 {@code MemoryController:143} / {@code TaskController:145} / {@code TeamController:95}
     * 等漏 clear 点的关系</b>：本 Filter 是兜底保证；各控制器内既有成对 clear 保留（提前释放）。
     */
    @Bean
    public FilterRegistrationBean<RequestContextCleanupFilter> requestContextCleanupFilter() {
        return createFilterBean(new RequestContextCleanupFilter(), Integer.MIN_VALUE);
    }

    public static <T extends Filter> FilterRegistrationBean<T> createFilterBean(T filter, Integer order) {
        FilterRegistrationBean<T> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(order);
        return bean;
    }
}
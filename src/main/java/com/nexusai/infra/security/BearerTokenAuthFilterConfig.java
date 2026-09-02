package com.nexusai.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 {@link BearerTokenAuthFilter} 到五端点族（S5 + IMP-MV2-17 鉴权收敛）。
 *
 * <p>覆盖面（IMP-MV2-17 登记：F2 R-1 / D3 ?1 / F3 EVD-F3-21 / 模块 §8.2-12）：
 * <ul>
 *   <li>{@code /api/v1/schedules}（既有，S5）；</li>
 *   <li>{@code /api/v1/memory}（memory 命令 REST 等价：GET/POST /files + GET/PUT /config）；</li>
 *   <li>{@code /api/v1/sessions}（会话 CRUD 全族，含 ChatController/SessionFileController 子路径）；</li>
 *   <li>{@code /api/agent/away-summary}（away-summary REST 载体，前端接线即暴露须同步纳入）；</li>
 *   <li>{@code /api/agent/dream}（/dream 手动整合 Web 等价 REST 载体 · OPD-CM5-E-06）；</li>
 *   <li>{@code /api/v1/session-memory/config}（SM 阈值调参通道，含 /sm、/sm-compact 子路径）；</li>
 *   <li>{@code /api/v1/session-memory/export}（会话记忆导出 · OPD-CM5-F-24，getAgentMemoryEntrypoint
 *       入口路径 + 记忆文件内容导出）。</li>
 *   <li>{@code /api/v1/attachments}（附件族 · 附件双模式 plan §6 鉴权补登：{@code /content} 附件表
 *       文件字节流式预览、{@code /upload} 大文件 multipart 落盘、{@code /image} 图片拉取——均为会话
 *       域内文件读/写端点，补登覆盖既有缺口，防匿名枚举 contentId 拉取/写入本地 cache）。</li>
 * </ul>
 * 网关兜底未在仓库内证实（安全配置 grep 全仓仅本类一处），故鉴权在应用内强制。
 *
 * <p>项目无 Spring Security 依赖（pom 仅 starter-web/webflux/websocket），故用
 * {@link FilterRegistrationBean} 注册纯 {@link jakarta.servlet.Filter}，不走
 * {@code SecurityFilterChain}（任务清单里的 {@code WebSecurityConfig.java} 不存在且语义不符，
 * 已改名为本类，见 concerns）。
 *
 * <p><b>requireOAuthAuth 注入源</b>：由本 @Bean 方法经 {@code @Value("${nexusai.security.require-oauth-auth:true}")}
 * 注入（默认 {@code true}=deny-all）。该 fail-closed 默认语义对齐 CC
 * {@code validateForceLoginOrg}（Open-ClaudeCode/src/utils/auth.ts:1960）——
 * 无法校验 org 时 {@code return { valid: false } } 拒绝，非放行。
 * {@code false}=条件放行（fail-open 过渡态逃逸阀，仅 token 生产链未接通过渡期；接通后必须保持 true）。
 * 配置键可经 env {@code NEXUSAI_SECURITY_REQUIRE_OAUTH_AUTH=false} 覆盖。
 */
@Configuration
public class BearerTokenAuthFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthFilterConfig.class);

    /**
     * 构造 filter bean（AccountOAuthTokenService 为 {@code @Service}，ObjectMapper 由 Spring Boot 自动配置）。
     *
     * <p>{@code requireOAuthAuth} 由配置项 {@code nexusai.security.require-oauth-auth}（默认 true）注入，
     * 消除「2 参构造器默认 true + Javadoc 口头约束」上线前忘记翻回 true 的遗漏风险。
     */
    @Bean
    public BearerTokenAuthFilter bearerTokenAuthFilter(
            AccountOAuthTokenService accountTokenService, ObjectMapper objectMapper,
            @Value("${nexusai.security.require-oauth-auth:true}") boolean requireOAuthAuth) {
        BearerTokenAuthFilter filter = new BearerTokenAuthFilter(accountTokenService, objectMapper, requireOAuthAuth);
        if (log.isDebugEnabled()) {
            log.debug("[BearerTokenAuthFilterConfig] 装配鉴权过滤器 requireOAuthAuth={}"
                + "（nexusai.security.require-oauth-auth，默认 true=deny-all 对齐 CC auth.ts:1960 fail-closed）",
                requireOAuthAuth);
        }
        return filter;
    }

    /**
     * 注册过滤器到五端点族（每族双 pattern：Servlet 规范 {@code /prefix/*} 不匹配裸路径本身
     * —— list/create 等端点均在裸路径或子路径上，须双 pattern 同时覆盖）。
     *
     * <p>IMP-MV2-17 扩展：原仅 /api/v1/schedules 双 pattern，现覆盖 memory/sessions/
     * away-summary/session-memory-config 四端点族（鉴权收敛，见类 javadoc）。
     * 附件双模式 plan §6：补登 /api/v1/attachments 族（content/upload/image）。
     */
    @Bean
    public FilterRegistrationBean<BearerTokenAuthFilter> bearerTokenAuthFilterRegistration(
            BearerTokenAuthFilter filter) {
        FilterRegistrationBean<BearerTokenAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(
            "/api/v1/schedules", "/api/v1/schedules/*",
            "/api/v1/memory", "/api/v1/memory/*",
            "/api/v1/sessions", "/api/v1/sessions/*",
            "/api/agent/away-summary",
            "/api/agent/dream",
            "/api/v1/session-memory/config", "/api/v1/session-memory/config/*",
            "/api/v1/session-memory/export", "/api/v1/session-memory/export/*",
            "/api/v1/attachments/content", "/api/v1/attachments/content/*",
            "/api/v1/attachments/upload",
            "/api/v1/attachments/image/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("bearerTokenAuthFilter");
        return registration;
    }
}

package com.nexusai.infra.filter;

import com.nexusai.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * [TL-W3] 入站 MDC 统一清理 Filter · 消除 Tomcat 线程复用导致的跨会话残留。
 *
 * <p><b>WHY（缺陷）</b>：全仓<b>没有</b>任何 Filter / HandlerInterceptor / STOMP ChannelInterceptor
 * 写或清 {@link RequestContext} 的 MDC —— 会话标识靠各 REST 端点<b>散点手写</b>
 * {@code RequestContext.setSession(...)}。其中 4 个入口（{@code TeamController.resolveSessionId}、
 * {@code TaskController.listTasksMerged}、{@code MemoryController.listFiles} /
 * {@code MemoryController.updateFile}）只写不 {@code clear()}：Tomcat 工作线程归还池后仍带
 * 上一请求的 {@code sessionId}，而下游多处按「query 缺参 → MDC 兜底」读取
 * （{@code RequestContext.sessionId()} / {@code CwdResolution.getCwd()} /
 * {@code CommandHookExecutor.enrichBaseFields}）⇒ 下一个不带 {@code ?sessionId=} 的请求
 * <b>静默拿到另一个会话的 id</b>（team 名册 / 任务清单 / 记忆文件列表串会话，
 * hook 载荷 session_id/cwd/transcript_path 指向别会话）。
 *
 * <p><b>机制</b>：请求边界单点清理 —— {@code finally} 恒清。注册顺序取最外
 * （{@link com.nexusai.infra.config.WebConfig#requestContextCleanupFilter()} 的
 * {@code Integer.MIN_VALUE}），使本 Filter 的 {@code finally} 在所有 Filter/Interceptor
 * （含 {@code ApiAccessLogInterceptor}）之后执行 ⇒ <b>请求结束即无残留</b>，
 * 不依赖任何控制器自觉。
 *
 * <p><b>与各控制器内既有 {@code finally { RequestContext.clear(); }} 的关系</b>：那些是本请求内的
 * 提前释放（语义仍成立，保留）；本 Filter 是<b>兜底保证</b>（覆盖未手写清理的入口与将来新增入口）。
 *
 * <p><b>不做的事</b>：<b>不注入</b> sessionId（无统一入站会话标识来源 —— 各端点入参形态不一：
 * query / body / header），只负责清理。注入侧仍是各端点的显式 {@code setSession}（按会话 id 现算，
 * 与会话态「不读 ThreadLocal、直传或按 id 现算」铁律一致）。
 *
 * <p><b>@Async / 定时器线程不受影响</b>：本 Filter 只在 Servlet 请求线程上执行；{@code chatExecutor}
 * / {@code pollScheduler} / {@code CronIdleExecutor} 等线程各自成对 set/clear（已核实）。
 */
public class RequestContextCleanupFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestContextCleanupFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, ServletException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 恒清（Tomcat 线程复用防泄漏）——本请求若写了 sessionId/reqId，返回池前必须移除
            RequestContext.clear();
            if (log.isDebugEnabled()) {
                log.debug("[RequestContextCleanupFilter] 请求结束 MDC 已清理: {} {}", request.getMethod(),
                    request.getRequestURI());
            }
        }
    }
}

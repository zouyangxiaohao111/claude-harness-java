package com.nexusai.infra.filter;

import com.nexusai.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [TL-W3] 入站 MDC 清理 Filter · 契约测试。
 *
 * <p>WHY（规则九 · 验证意图）：本 Filter 存在的意义不是「调用了一次 clear()」，而是
 * <b>Tomcat 线程复用下跨会话标识不得泄漏</b>：REST 端点散点写 MDC 后若不清理，线程归还池，
 * 下一个「query 缺参 → MDC 兜底」的请求会静默拿到上一会话的 sessionId（team 名册 / 任务清单 /
 * 记忆文件列表串会话；hook 载荷 session_id/cwd/transcript_path 指向别会话）。故断言必须落在
 * 「请求结束后 MDC 无残留」与「异常路径同样无残留」两点上 —— 若 Filter 不 clear（或只 try 不
 * finally），本类 RED。
 */
@DisplayName("TL-W3 · RequestContextCleanupFilter 请求边界 MDC 清理")
class RequestContextCleanupFilterTest {

    private final RequestContextCleanupFilter filter = new RequestContextCleanupFilter();

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("请求结束 → sessionId/reqId 无残留（线程归还池前必须清理）")
    void clearsMdcAfterRequest() throws Exception {
        // 模拟端点内散点写（TeamController/TaskController/MemoryController 同款）
        RequestContext.set("sess-A", "req-A");

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/teams"),
            new MockHttpServletResponse(), new MockFilterChain());

        assertThat(RequestContext.sessionId()).as("请求结束 sessionId 必须无残留").isNull();
        assertThat(RequestContext.requestId()).as("请求结束 reqId 必须无残留").isNull();
    }

    @Test
    @DisplayName("链路抛异常 → 仍无残留（finally 语义，防异常请求把会话 id 留在池线程）")
    void clearsMdcEvenWhenChainThrows() {
        RequestContext.set("sess-B", "req-B");
        FilterChain throwing = new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/memory/files"),
            new MockHttpServletResponse(), throwing))
            .isInstanceOf(ServletException.class);

        assertThat(RequestContext.sessionId()).as("异常路径同样必须清理").isNull();
        assertThat(RequestContext.requestId()).isNull();
    }

    @Test
    @DisplayName("上一请求的残留不得被下一请求读到（跨会话泄漏的验收点）")
    void residueDoesNotLeakIntoNextRequest() throws Exception {
        AtomicReference<String> seenByNextRequest = new AtomicReference<>("unset");

        // 请求 1：写 MDC（模拟 MemoryController.listFiles 只 set 不 clear）
        RequestContext.set("sess-PREVIOUS", "req-1");
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/memory/files"),
            new MockHttpServletResponse(), new MockFilterChain());

        // 请求 2：不带 ?sessionId= → 端点按 MDC 兜底读（RequestContext.sessionId()）
        FilterChain reader = new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws IOException, ServletException {
                seenByNextRequest.set(String.valueOf(RequestContext.sessionId()));
            }
        };
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/tasks/list"),
            new MockHttpServletResponse(), reader);

        assertThat(seenByNextRequest.get())
            .as("下一请求 MDC 兜底必须为 null（不得读到上一会话 id）")
            .isEqualTo("null");
    }
}

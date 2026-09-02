package com.nexusai.application.agent.api;

import com.nexusai.infra.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic Tracking Service · 对齐 CC services/diagnosticTracking.ts.
 *
 * <p>FIX-SVC-1: API 错误诊断信息记录.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>{@link #recordError(String, Throwable)}: 记录 API 错误 (按 endpoint 分组)</li>
 *   <li>{@link #getStats(String)}: 获取某 endpoint 的错误统计</li>
 *   <li>{@link #getAll()}: 获取所有 endpoint 的统计</li>
 * </ul>
 *
 * <p>用于调试 LLM API 频繁失败的问题 (SSL 错误 / 429 限速 / 网络超时等).
 */
@Service
public class DiagnosticTrackingService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticTrackingService.class);

    private final Map<String, ErrorStats> stats = new ConcurrentHashMap<>();

    public void recordError(String endpoint, Throwable error) {
        if (endpoint == null) return;
        ErrorStats s = stats.computeIfAbsent(endpoint, k -> new ErrorStats());
        s.totalCount.incrementAndGet();
        String category = categorizeError(error);
        s.categoryCount.computeIfAbsent(category, k -> new AtomicLong(0)).incrementAndGet();
        log.debug("DiagnosticTrackingService: endpoint={} category={} err={}",
            endpoint, category, error.getClass().getSimpleName());
    }

    public ErrorStats getStats(String endpoint) {
        return stats.get(endpoint);
    }

    public Map<String, ErrorStats> getAll() {
        return Map.copyOf(stats);
    }

    private String categorizeError(Throwable err) {
        if (err == null) return "UNKNOWN";
        if (err instanceof LlmApiException apiEx) {
            int status = apiEx.status();
            if (status == 429) return "RATE_LIMIT";
            if (status == 401 || status == 403) return "AUTH";
            if (status >= 500) return "SERVER";
            if (status >= 400) return "CLIENT";
            return "OTHER_" + status;
        }
        String name = err.getClass().getSimpleName();
        if (name.contains("SSL") || name.contains("Cert")) return "SSL";
        if (name.contains("Timeout")) return "TIMEOUT";
        if (name.contains("Network") || name.contains("IOException")) return "NETWORK";
        return "EXCEPTION_" + name;
    }

    public static final class ErrorStats {
        public final AtomicLong totalCount = new AtomicLong(0);
        public final Map<String, AtomicLong> categoryCount = new ConcurrentHashMap<>();
    }
}
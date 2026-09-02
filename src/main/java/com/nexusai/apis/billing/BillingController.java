package com.nexusai.apis.billing;

import com.nexusai.domain.provider.ProviderService;
import com.nexusai.model.provider.dto.ProviderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Billing REST 端点 · 对齐 CC /usage, /extra-usage, /mock-limits, /reset-limits 命令.
 *
 * <p>FIX-CMD-4: 用量查询 + mock-limits 测试支持.
 * <p>FIX-R10-3: 真调 {@link ProviderService#getDecryptedApiKey} 返回 masked key metadata,
 * 不暴露完整 API key.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>GET /api/v1/billing/usage - 当前会话用量 (mock-limits 持久状态)</li>
 *   <li>POST /api/v1/billing/extra-usage - 启用 extra usage</li>
 *   <li>POST /api/v1/billing/mock-limits - 设置 mock 限速 (测试用)</li>
 *   <li>DELETE /api/v1/billing/limits - 重置限速</li>
 *   <li>GET /api/v1/billing/rate-limit-options - 限速档位 (配置常量)</li>
 *   <li>GET /api/v1/billing/providers - 列出所有 provider + masked key (NEW R10)</li>
 *   <li>GET /api/v1/billing/providers/{id}/key - 单 provider masked key + 是否可解密 (NEW R10)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    @Autowired
    private ProviderService providerService;

    /** mock-limits 持久状态 (in-memory 测试用, 重启丢失) */
    private final ConcurrentHashMap<String, MockLimitState> mockLimits = new ConcurrentHashMap<>();

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        long totalInput = 0L;
        long totalOutput = 0L;
        long totalCacheRead = 0L;
        long totalCacheCreation = 0L;
        for (MockLimitState s : mockLimits.values()) {
            totalInput += s.inputTokens;
            totalOutput += s.outputTokens;
            totalCacheRead += s.cacheReadTokens;
            totalCacheCreation += s.cacheCreationTokens;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputTokens", totalInput);
        body.put("outputTokens", totalOutput);
        body.put("cacheReadTokens", totalCacheRead);
        body.put("cacheCreationTokens", totalCacheCreation);
        body.put("costUsd", 0.0);
        body.put("activeMockProviders", mockLimits.size());
        return body;
    }

    @PostMapping("/extra-usage")
    public Map<String, Object> enableExtraUsage() {
        return Map.of("extraUsageEnabled", true, "limit", 100_000L);
    }

    @PostMapping("/mock-limits")
    public Map<String, Object> mockLimits(@RequestBody Map<String, Object> req) {
        String providerId = (String) req.getOrDefault("providerId", "default");
        String type = (String) req.getOrDefault("type", "rate");
        long limit = ((Number) req.getOrDefault("limit", 1000L)).longValue();
        long inputTokens = ((Number) req.getOrDefault("inputTokens", 0L)).longValue();
        long outputTokens = ((Number) req.getOrDefault("outputTokens", 0L)).longValue();

        mockLimits.put(providerId, new MockLimitState(type, limit, inputTokens, outputTokens, 0L, 0L));
        log.info("[BillingController] mock-limits set provider={} type={} limit={}", providerId, type, limit);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mocked", true);
        body.put("providerId", providerId);
        body.put("type", type);
        body.put("limit", limit);
        return body;
    }

    @DeleteMapping("/limits")
    public Map<String, Object> resetLimits() {
        int cleared = mockLimits.size();
        mockLimits.clear();
        log.info("[BillingController] reset-limits cleared {} entries", cleared);
        return Map.of("reset", true, "clearedCount", cleared);
    }

    @GetMapping("/rate-limit-options")
    public List<Map<String, Object>> rateLimitOptions() {
        return List.of(
                Map.of("name", "default", "rps", 5),
                Map.of("name", "high", "rps", 50),
                Map.of("name", "max", "rps", 200)
        );
    }

    /**
     * FIX-R10-3: 列出所有 provider + masked key metadata (不暴露完整 API key).
     */
    @GetMapping("/providers")
    public List<Map<String, Object>> listProviders() {
        List<ProviderDto> all = providerService.listAll();
        List<Map<String, Object>> result = new ArrayList<>(all.size());
        for (ProviderDto p : all) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", p.id());
            entry.put("name", p.name());
            entry.put("type", p.type());
            entry.put("baseUrl", p.baseUrl());
            entry.put("apiKeyMasked", p.apiKeyMasked());
            entry.put("enabled", p.enabled());
            entry.put("modelCount", p.models() != null ? p.models().size() : 0);
            entry.put("hasKey", p.apiKeyMasked() != null && !p.apiKeyMasked().isBlank());
            result.add(entry);
        }
        return result;
    }

    /**
     * FIX-R10-3: 单 provider masked key + 解密状态 (供内部 LlmProvider 调用诊断).
     */
    @GetMapping("/providers/{id}/key")
    public ResponseEntity<Map<String, Object>> getProviderKey(@PathVariable String id) {
        ProviderDto p = providerService.getById(id);
        String decrypted = providerService.getDecryptedApiKey(id);
        String maskFirstLast = maskApiKey(decrypted);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerId", id);
        body.put("name", p.name());
        body.put("type", p.type());
        body.put("enabled", p.enabled());
        body.put("hasKey", decrypted != null && !decrypted.isBlank());
        body.put("keyLength", decrypted != null ? decrypted.length() : 0);
        body.put("keyPreview", maskFirstLast);
        body.put("apiKeyMasked", p.apiKeyMasked());
        return ResponseEntity.ok(body);
    }

    /**
     * 掩码 API key: 前 4 + ... + 后 4. 长度 < 8 → "****".
     */
    private static String maskApiKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static final class MockLimitState {
        final String type;
        final long limit;
        final long inputTokens;
        final long outputTokens;
        final long cacheReadTokens;
        final long cacheCreationTokens;

        MockLimitState(String type, long limit, long inputTokens, long outputTokens,
                       long cacheReadTokens, long cacheCreationTokens) {
            this.type = type;
            this.limit = limit;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.cacheReadTokens = cacheReadTokens;
            this.cacheCreationTokens = cacheCreationTokens;
        }
    }
}
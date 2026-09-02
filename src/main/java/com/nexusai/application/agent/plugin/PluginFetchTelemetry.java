package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plugin Fetch Telemetry · 对齐 CC utils/plugins/fetchTelemetry.ts.
 *
 * <p>FIX-PLUGIN-MISC: plugin fetch telemetry/error classify.
 */
@Component
public class PluginFetchTelemetry {

    private static final Logger log = LoggerFactory.getLogger(PluginFetchTelemetry.class);

    public enum ErrorClass { NETWORK, NOT_FOUND, FORBIDDEN, INVALID_FORMAT, OTHER }

    private final AtomicLong fetchCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final Map<ErrorClass, AtomicLong> errorByClass = new ConcurrentHashMap<>();

    public void recordFetch(String pluginName, boolean success, ErrorClass errorClass) {
        fetchCount.incrementAndGet();
        if (!success) {
            errorCount.incrementAndGet();
            errorByClass.computeIfAbsent(errorClass, k -> new AtomicLong(0)).incrementAndGet();
        }
        log.debug("PluginFetchTelemetry: plugin={} success={} class={}", pluginName, success, errorClass);
    }

    public long totalFetches() {
        return fetchCount.get();
    }

    public long totalErrors() {
        return errorCount.get();
    }

    public Map<ErrorClass, Long> errorsByClass() {
        return errorByClass.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}
package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repl Bridge · 对齐 CC bridge/replBridge.ts (主链).
 *
 * <p>FIX-BRIDGE-6: 简化版 REPL bridge 主链 — handle 创建 + writeMessages + sendControlRequest.
 *
 * <p>LIMIT: 真实实现需要 SSE/WS 双向流; 当前是内存版本.
 */
@Component
public class ReplBridge {

    private static final Logger log = LoggerFactory.getLogger(ReplBridge.class);

    public record ReplBridgeHandle(String bridgeSessionId, String environmentId,
                                    String sessionIngressUrl) {}

    private final Map<String, ReplBridgeHandle> handles = new ConcurrentHashMap<>();

    public ReplBridgeHandle create(String bridgeSessionId, String environmentId, String sessionIngressUrl) {
        ReplBridgeHandle handle = new ReplBridgeHandle(bridgeSessionId, environmentId, sessionIngressUrl);
        handles.put(bridgeSessionId, handle);
        log.info("ReplBridge: created handle session={} env={}", bridgeSessionId, environmentId);
        return handle;
    }

    public void teardown(String bridgeSessionId) {
        handles.remove(bridgeSessionId);
        log.info("ReplBridge: teardown session={}", bridgeSessionId);
    }

    public ReplBridgeHandle get(String bridgeSessionId) {
        return handles.get(bridgeSessionId);
    }

    public void writeMessages(String sessionId, Object messages) {
        log.debug("ReplBridge: writeMessages session={} count={}",
            sessionId, messages == null ? 0 : 1);
    }
}
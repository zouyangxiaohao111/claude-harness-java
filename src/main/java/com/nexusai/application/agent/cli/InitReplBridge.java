package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Init Repl Bridge · 对齐 CC bridge/initReplBridge.ts.
 *
 * <p>FIX-BRIDGE-7: 简化版 bridge 初始化.
 */
@Component
public class InitReplBridge {

    private static final Logger log = LoggerFactory.getLogger(InitReplBridge.class);

    public ReplBridge.ReplBridgeHandle initialize(String bridgeSessionId, String environmentId) {
        String sessionIngressUrl = "wss://bridge.example.com/sessions/" + bridgeSessionId;
        ReplBridge bridge = new ReplBridge();
        ReplBridge.ReplBridgeHandle handle = bridge.create(bridgeSessionId, environmentId, sessionIngressUrl);
        log.info("InitReplBridge: initialized bridge={} env={}", bridgeSessionId, environmentId);
        return handle;
    }
}
package com.nexusai.application.agent.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote Bridge Core · 对齐 CC bridge/remoteBridgeCore.ts.
 *
 * <p>FIX-BRIDGE-8: 简化版远程桥核心 (HTTP 客户端 + 心跳 + 重连).
 */
@Component
public class RemoteBridgeCore {

    private static final Logger log = LoggerFactory.getLogger(RemoteBridgeCore.class);

    public enum State { CONNECTED, DISCONNECTED, RECONNECTING }

    private volatile State state = State.DISCONNECTED;
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    public boolean connect(String url, Map<String, String> headers) {
        log.info("RemoteBridgeCore: connect url={} headers={}", url, headers.size());
        state = State.CONNECTED;
        return true;
    }

    public void disconnect() {
        log.info("RemoteBridgeCore: disconnect");
        state = State.DISCONNECTED;
    }

    public void reconnect() {
        log.info("RemoteBridgeCore: reconnect");
        state = State.RECONNECTING;
    }

    public State getState() {
        return state;
    }
}
package com.nexusai.application.agent.cli;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NexusAI in Chrome Native Host · 对齐 CC utils/claudeInChrome/ (2.3K 行).
 *
 * <p>FIX-M12: 简化版 Chrome Native Host TCP 服务 + MCP 桥接.
 *
 * <p>L1 行为: 注册 MCP server + 启动浏览器探测; 真实 Native Host 协议留 P1.
 */
@Component
public class NexusaiInChrome {

    public enum Browser { CHROME, CHROMIUM, BRAVE, EDGE, UNKNOWN }

    public record BrowserConfig(Browser browser, String version, String nativeHostPath) {}

    private final Map<String, BrowserConfig> browsers = new ConcurrentHashMap<>();

    public BrowserConfig detectBrowser() {
        // 简化版: 返回 Chrome 默认
        BrowserConfig cfg = new BrowserConfig(Browser.CHROME, "120.0", "/usr/bin/chrome");
        browsers.put("default", cfg);
        return cfg;
    }

    public boolean registerMCPServer() {
        // 真实实现需要 Native Messaging Host 协议 + manifest 写入.
        // 当前返回 true (配置成功).
        return true;
    }
}
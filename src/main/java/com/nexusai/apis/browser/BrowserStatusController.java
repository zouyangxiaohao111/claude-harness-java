package com.nexusai.apis.browser;

import com.nexusai.application.agent.browser.BrowserWsChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NexusAI in Chrome 浏览器扩展状态 REST · 前端 ChromePanel 真实显示「WS 已连接」。
 *
 * <p><b>WHY（修复 ChromePanel 状态误导）</b>：browser-mcp-align 批次登记欠账「后端无连接查询
 * 端点，ChromePanel 只能静态引导」——此前 ChromePanel 的「已安装 ✓」只查 Chrome 浏览器存在 +
 * localStorage 记忆，无法真实区分「扩展已加载 / WS 已连接」。本端点读
 * {@link BrowserWsChannel#hasSessionConnection()}（<b>全局语义</b>：一个扩展连接服务所有会话，
 * 有连接即 true），前端据此真实显示连接状态，不再靠记忆误导。
 *
 * <p><b>fail loud</b>：无扩展连接时返回 {@code connected:false}（不误报），前端据此提示
 * 「扩展未连接，请在 Chrome 扩展 popup 点『连接』」。
 */
@RestController
@RequestMapping("/api/v1/browser")
public class BrowserStatusController {

    private static final Logger log = LoggerFactory.getLogger(BrowserStatusController.class);

    private final BrowserWsChannel browserWsChannel;

    @Autowired
    public BrowserStatusController(BrowserWsChannel browserWsChannel) {
        this.browserWsChannel = browserWsChannel;
    }

    /**
     * 浏览器扩展连接状态 · GET /api/v1/browser/status
     *
     * <p><b>全局语义</b>：一个扩展 popup 一次连接（hello）服务所有会话——
     * {@code hasSessionConnection()} 有 open 的全局连接即 true，与会话无关。
     *
     * @return {@code {"connected": boolean}}（true = 扩展已连上 {@code /ws/browser}）
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean connected = browserWsChannel != null && browserWsChannel.hasSessionConnection();
        if (log.isDebugEnabled()) {
            log.debug("[BrowserStatusController] GET /api/v1/browser/status → connected={}", connected);
        }
        return Map.of("connected", connected);
    }
}

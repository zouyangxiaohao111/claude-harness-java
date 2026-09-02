package com.nexusai.application.agent.tool.impl.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;

/**
 * WebSearch 引擎共用代理解析工具（AnySearchEngine + WebFetchSecurity）。
 *
 * <p>[websearch-domaincheck T1] 公共化自 {@code AnySearchEngine.java:114-137} + {@code WebFetchSecurity.java:173-196}
 * 双镜像——两处逐行相同逻辑（null/blank → 直连；{@code http(s)://} 前缀剥除；无端口/端口非数字 → warn + null），
 * 抽公共静态工具消除双改维护成本（C4「anysearch 不动」约定已过期，用户 2026-08-23 拍板公共化）。
 *
 * <p><b>CC 对照</b>：CC 无此概念（CC {@code WebFetchTool/utils.ts} 直接读 {@code settings.proxy}
 * → {@code httpProxyAgent}，无 {@code host:port} 解析层）；本类为 Java 增强（DB settings.proxy
 * 以 {@code host:port} 字符串承载 + 兼容协议前缀）。
 *
 * <p><b>语义</b>（逐字搬运原实现，日志前缀统一 {@code [ProxySelectors]}）：
 * <ul>
 *   <li>null / blank → null（直连，不设 ProxySelector）</li>
 *   <li>兼容 {@code http://host:port} / {@code https://host:port} 前缀（DB settings.proxy 可能带协议）</li>
 *   <li>无端口 / 端口非数字 → warn + null（fail-loud，不中断抓取）</li>
 * </ul>
 */
public final class ProxySelectors {

    private static final Logger log = LoggerFactory.getLogger(ProxySelectors.class);

    private ProxySelectors() {
        // 工具类，禁止实例化
    }

    /**
     * 解析 {@code host:port} → ProxySelector；null/blank → null（直连）；格式非法 → warn + null。
     *
     * @param proxy HTTP 代理 {@code host:port}（null/blank → 直连；非法格式 → warn + 直连）
     * @return ProxySelector；解析失败 → null（直连）
     */
    public static ProxySelector parseProxySelector(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        String p = proxy.trim();
        if (p.startsWith("http://")) {
            p = p.substring("http://".length());
        } else if (p.startsWith("https://")) {
            p = p.substring("https://".length());
        }
        int idx = p.lastIndexOf(':');
        if (idx <= 0 || idx == p.length() - 1) {
            log.warn("[ProxySelectors] proxy 格式非法（应为 host:port），回退直连: proxy='{}'", proxy);
            return null;
        }
        try {
            String host = p.substring(0, idx);
            int port = Integer.parseInt(p.substring(idx + 1));
            return ProxySelector.of(InetSocketAddress.createUnresolved(host, port));
        } catch (NumberFormatException e) {
            log.warn("[ProxySelectors] proxy 端口非法，回退直连: proxy='{}' err={}", proxy, e.getMessage());
            return null;
        }
    }
}

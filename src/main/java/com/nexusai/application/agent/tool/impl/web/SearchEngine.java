package com.nexusai.application.agent.tool.impl.web;

import java.util.List;

/**
 * 搜索后端策略接口 · 参考 javaclawbot 策略模式（决策清单 组 4-1 拍板：WebSearch anysearch）。
 *
 * <p>{@link com.nexusai.application.agent.tool.impl.WebSearchTool} facade 按配置
 * {@code nexusai.websearch.engine} 字段选择引擎（anysearch / duckduckgo）。各引擎返回对齐
 * CC {@code searchHitSchema}（{@code {title, url}}，WebSearchTool.ts:43-46）的原始命中；
 * {@code {query, results, durationSeconds}} 输出组装由 facade 完成。
 *
 * <p><b>CC 对齐锚</b>：WebSearchTool.ts inputSchema（:25-37）{@code {query, allowed_domains,
 * blocked_domains}}。allowed/blocked 域过滤 <b>不</b>在客户端做（D-TR-H1-08 删除 domainMatches
 * 后置过滤，CC 交给 API server tool 参数），本接口透传请求字段，由引擎决定是否转发后端。
 */
public interface SearchEngine {

    /** 引擎标识 · 与配置 engine 字段匹配（e.g. "anysearch" / "duckduckgo"）。 */
    String name();

    /** 执行搜索，返回命中列表（对齐 CC searchHitSchema {@code {title, url}}）。 */
    List<SearchHit> search(SearchRequest request);

    /**
     * 单条命中 · CC original: {@code searchHitSchema}（WebSearchTool.ts:43-46）
     * {@code {title: string, url: string}}。
     *
     * @param title 搜索结果标题
     * @param url   搜索结果 URL
     */
    record SearchHit(String title, String url) {
    }

    /**
     * 搜索请求 · 对齐 CC inputSchema（WebSearchTool.ts:25-37）。
     *
     * <p>{@code allowedDomains}/{@code blockedDomains} 仅透传给后端 API（CC 域过滤交给 server tool
     * 参数，D-TR-H1-08 删除客户端 domainMatches 后置过滤）；引擎不支持时忽略，不做客户端过滤。
     *
     * @param query          搜索关键词
     * @param allowedDomains 仅包含这些域的结果（可选）
     * @param blockedDomains 永不包含这些域的结果（可选）
     */
    record SearchRequest(String query, List<String> allowedDomains, List<String> blockedDomains) {

        public SearchRequest {
            allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
            blockedDomains = blockedDomains == null ? List.of() : List.copyOf(blockedDomains);
        }
    }
}

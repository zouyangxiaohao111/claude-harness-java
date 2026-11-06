package com.nexusai.kb.app.qa;

import com.nexusai.application.agent.tool.impl.WebFetchTool;
import com.nexusai.application.agent.tool.impl.WebSearchTool;
import com.nexusai.application.agent.tool.impl.web.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答链的联网端口 · 对应源侧 {@code chat_pipeline/search.go:searchWebIfEnabled}
 * + {@code chat_pipeline/web_fetch.go}。
 *
 * <h2>为什么需要这一层</h2>
 * 本仓的联网能力全部是 <b>agent 工具形态</b>（{@link WebSearchTool} / {@link WebFetchTool}），
 * 它们要 {@code ToolUseBlock} + {@code ToolUseContext} 才能跑。而问答链<b>没有工具上下文</b>
 * —— 它不是一次工具调用，是流水线里的一个阶段。伪造一个空的工具上下文去跑工具，
 * 会让「来源采集 / 缓存 / 会话状态」这些工具副作用意外触发。
 *
 * <p>⇒ 本类只做两件事，且都委托给工具的<b>新增只读入口</b>
 * （{@link WebSearchTool#searchRaw} / {@link WebFetchTool#fetchMarkdown}）：
 * <ol>
 *   <li>{@link #search} —— 拿「标题 + URL」；</li>
 *   <li>{@link #fetch} —— 把 URL 换成整页正文。</li>
 * </ol>
 * ⛔ 不新增第二套引擎配置：引擎选择仍由 {@code settings.websearchEngine} 决定，
 * 与 agent 工具路径同源。
 *
 * <h2>失败语义</h2>
 * 联网是**增强**不是正确性前提：
 * <ul>
 *   <li>搜索失败 ⇒ 空列表 + WARN（本轮只用知识库结果）</li>
 *   <li>抓正文失败 ⇒ {@code null}（调用方保留搜索给的 snippet）</li>
 * </ul>
 * 这与源侧一致：`web_fetch.go` 对单页失败只 {@code continue}，不删掉该条结果。
 *
 * <h2>⚠️ 已知边界</h2>
 * 本仓的 {@link SearchEngine} 只返回 {@code {title, url}}，<b>没有 snippet</b>
 * （源侧 {@code WebSearchResult} 带 Content）。⇒ {@link WebHit#snippet} 恒为 {@code null}，
 * 进入上下文前必须由 {@link #fetch} 补正文；抓取失败的条目正文为空，
 * 由调用方决定丢弃（见 {@code KbQaPipeline} 的 WEB_FETCH 阶段）。
 */
@Component
public class KbWebSearchPort {

    private static final Logger log = LoggerFactory.getLogger(KbWebSearchPort.class);

    /** 单页正文上限（源侧 {@code web_fetch.go:webFetchMaxRunes = 8000}）。 */
    static final int MAX_PAGE_CHARS = 8000;

    @Autowired(required = false) private WebSearchTool webSearchTool;
    @Autowired(required = false) private WebFetchTool webFetchTool;

    /**
     * 一条联网命中。
     *
     * @param title   标题（可能为空）
     * @param url     链接（知识库命中没有这一项）
     * @param snippet 摘要（本仓引擎不返回 ⇒ 恒 null）
     */
    public record WebHit(String title, String url, String snippet) {
    }

    /**
     * 联网检索。
     *
     * @param query      查询词（已改写）
     * @param maxResults 条数上限
     * @return 命中列表；未装配 / 失败 → 空列表
     */
    public List<WebHit> search(String query, int maxResults) {
        if (webSearchTool == null) {
            log.debug("[联网] WebSearchTool 未装配，跳过联网检索");
            return List.of();
        }
        List<SearchEngine.SearchHit> raw = webSearchTool.searchRaw(query, maxResults);
        if (raw.isEmpty()) {
            log.info("[联网] 检索无命中 query={}", query);
            return List.of();
        }
        List<WebHit> out = new ArrayList<>(raw.size());
        for (SearchEngine.SearchHit h : raw) {
            if (h == null || h.url() == null || h.url().isBlank()) {
                continue;
            }
            out.add(new WebHit(h.title(), h.url(), null));
        }
        log.info("[联网] 检索完成 query={} 命中={}", query, out.size());
        return out;
    }

    /**
     * 抓取网页正文（按码点截断到 {@link #MAX_PAGE_CHARS}）。
     *
     * @param url 目标 URL
     * @return Markdown 正文（截断后）；失败 / 未装配 / 空内容 → {@code null}
     */
    public String fetch(String url) {
        if (webFetchTool == null) {
            log.debug("[联网] WebFetchTool 未装配，跳过正文抓取");
            return null;
        }
        String content = webFetchTool.fetchMarkdown(url);
        if (content == null || content.isBlank()) {
            return null;
        }
        int len = content.codePointCount(0, content.length());
        if (len <= MAX_PAGE_CHARS) {
            return content;
        }
        int end = content.offsetByCodePoints(0, MAX_PAGE_CHARS);
        return content.substring(0, end) + "\n...(truncated)";
    }
}

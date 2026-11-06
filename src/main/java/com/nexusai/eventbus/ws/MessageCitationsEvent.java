package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 引用事件 · 知识库问答的引用来源（阶段 12 · 流中推引用）。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}（会话级单 topic，与其它事件同源）
 *
 * <h2>⭐ 为什么要在「生成前」推一次</h2>
 * 源侧 {@code chat_completion_stream.go} 在流开始**前**推 references。原因：
 * 引用是从<b>检索结果</b>算出来的，生成阶段还没开始就已经确定了。
 * 若等 {@code complete} 之后再推，前端在「回答流完」到「引用出现」之间会有一段空白，
 * 用户看到的引用像是「后来补上去的」。提前推，前端可以在回答逐字出现的同时
 * 就把来源列出来。
 *
 * <h2>⚠️ 与 REST 端点的关系</h2>
 * 本事件是<b>增量通知</b>；{@code GET /api/v1/messages/{id}/citations} 仍是权威查询入口
 * （刷新页面、历史回放都走它）。两者数据同源（{@code kb_citation}），⛔ 不要只保留一个。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageCitationsEvent extends StreamEvent {

    /** 一条引用的展示形状（前端直接渲染，不需要再查接口）。 */
    public record CitationItem(
        @JsonProperty("ref_index") int refIndex,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("knowledge_id") String knowledgeId,
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("knowledge_title") String knowledgeTitle,
        @JsonProperty("snippet") String snippet,
        @JsonProperty("chunk_index") Integer chunkIndex
    ) {
    }

    private final String assistantMessageId;
    private final List<CitationItem> citations;
    /** 网页来源（没有 chunk，不落 kb_citation，但仍然要告诉用户「这些是网上找的」）。 */
    private final List<WebSource> webSources;

    /** 网页来源形状。 */
    public record WebSource(
        @JsonProperty("title") String title,
        @JsonProperty("url") String url
    ) {
    }

    public MessageCitationsEvent(String sessionId, String userMessageId, String assistantMessageId,
                                 List<CitationItem> citations, List<WebSource> webSources) {
        super("citations", sessionId, userMessageId);
        this.assistantMessageId = assistantMessageId;
        this.citations = citations == null ? List.of() : List.copyOf(citations);
        this.webSources = webSources == null ? List.of() : List.copyOf(webSources);
    }

    @JsonProperty("assistant_message_id")
    public String assistantMessageId() {
        return assistantMessageId;
    }

    @JsonProperty("citations")
    public List<CitationItem> citations() {
        return citations;
    }

    @JsonProperty("web_sources")
    public List<WebSource> webSources() {
        return webSources;
    }

    /** 是否没有任何来源（此时不推事件，省一次前端无关更新）。 */
    public boolean isEmpty() {
        return citations.isEmpty() && webSources.isEmpty();
    }
}

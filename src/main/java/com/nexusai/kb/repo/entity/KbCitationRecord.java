package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 问答引用记录（{@code kb_citation}）· V265 建表。
 *
 * <p><b>源侧对照</b>：WeKnora 把引用存在 message 的 metadata JSON 里
 * （{@code chat_pipeline/references.go} + {@code citation_sources.go}）。
 * 本仓独立成表，原因见迁移脚本头部注释。
 *
 * <h2>⭐⭐ 两个字段语义容易写错</h2>
 * <ul>
 *   <li><b>{@code refIndex}</b>：与拼进提示词的编号（{@code [1]}、{@code [2]}…）<b>一一对应</b>。
 *       前端点「引用 2」就是按它回查。序号与编号错位 = 用户点开看到的是别的段落，
 *       而且<b>不会报错</b> —— 这是本表最容易出的缺陷。</li>
 *   <li><b>{@code knowledgeTitle} / {@code snippet}</b>：<b>写入时快照</b>，不是 join 出来的。
 *       文档被改名、块被重解析删掉后，历史引用仍要能显示「当时引的是什么」。
 *       引用是证据，⛔ 不该随后续编辑变化。</li>
 * </ul>
 *
 * <p><b>⚠️ 本表 append-only</b>：只在问答完成时批量插入，⛔ 不做 UPDATE。
 * 重新生成 = 新一批行（用新的 {@code assistantMessageId} 区分）。
 */
@Table(
    value = "kb_citation",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbCitationRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 会话 ID。 */
    private String sessionId;

    /** 该引用归属的 assistant 消息 ID。 */
    private String assistantMessageId;

    /** 引用序号（1 起）· 必须与提示词里的编号一致。 */
    private Integer refIndex;

    /** 被引用的块 ID。 */
    private String chunkId;

    /** 被引用的知识条目 ID。 */
    private String knowledgeId;

    /** 被引用的知识库 ID。 */
    private String knowledgeBaseId;

    /** 写入时的文档标题快照（块被删后历史引用仍可显示）。 */
    private String knowledgeTitle;

    /** 写入时的正文片段快照。 */
    private String snippet;

    /** 块在文档中的序号（前端「跳转到第 N 段」）。 */
    private Integer chunkIndex;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(String v) { this.assistantMessageId = v; }
    public Integer getRefIndex() { return refIndex; }
    public void setRefIndex(Integer v) { this.refIndex = v; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { this.chunkId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeTitle() { return knowledgeTitle; }
    public void setKnowledgeTitle(String v) { this.knowledgeTitle = v; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String v) { this.snippet = v; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer v) { this.chunkIndex = v; }
}

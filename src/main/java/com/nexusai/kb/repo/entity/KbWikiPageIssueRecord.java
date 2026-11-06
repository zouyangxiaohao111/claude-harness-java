package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * Wiki 页面 Lint 问题（{@code kb_wiki_page_issue}）· V266 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_page_issues}（000037 迁移）。
 *
 * <h2>为什么要单独一张表而不是页面上的 JSON 列</h2>
 * Lint 结果是<b>可单独处置</b>的：用户可以「忽略」某条、可以分派、可以按 status 过滤。
 * 塞进页面的 JSON 里就没法按状态查，也没法记「谁什么时候处置的」。
 *
 * <h2>⚠️ {@code status} 是<b>人</b>的处置状态，不是 Lint 的重算结果</h2>
 * 重新 Lint 时：
 * <ul>
 *   <li>已经 {@code resolved} 的问题若<b>再次出现</b> ⇒ 新建一条 pending（⛔ 不把旧的改回 pending，
 *       那会抹掉「它曾经被修好过」这个事实）；</li>
 *   <li>已 {@code ignored} 的问题 ⇒ 重算时跳过（用户明确说不用管）。</li>
 * </ul>
 */
@Table(
    value = "kb_wiki_page_issue",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbWikiPageIssueRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 出问题的页面 slug。 */
    private String slug;

    /**
     * 问题类型（对应 {@code KbWikiLinter} 的检查项），如
     * {@code dead_link} / {@code orphan_page} / {@code empty_page} / {@code broken_citation}。
     */
    private String issueType;

    /** 严重度：error / warning / info。 */
    private String severity;

    /** 人可读描述（要能直接告诉用户「哪里不对、怎么改」）。 */
    private String description;

    /** 疑似相关的文档 ID 列表（JSON 数组字符串），便于人工核查。 */
    private String suspectedKnowledgeIds;

    /** 处置状态：pending / resolved / ignored。 */
    private String status;

    /** 上报者：{@code lint}（自动）或用户 ID（手工）。 */
    private String reportedBy;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String v) { this.issueType = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getSuspectedKnowledgeIds() { return suspectedKnowledgeIds; }
    public void setSuspectedKnowledgeIds(String v) { this.suspectedKnowledgeIds = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String v) { this.reportedBy = v; }
}

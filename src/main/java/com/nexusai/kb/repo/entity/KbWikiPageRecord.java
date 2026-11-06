package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * Wiki 页面（{@code kb_wiki_page}）· V266 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_pages}（000037 迁移 + 000061 层级扩展）。
 * 差异只在类型（JSONB → TEXT），列与语义逐列对齐。
 *
 * <h2>⭐⭐ 两条不变量（写错会静默出错）</h2>
 * <ol>
 *   <li><b>{@code chunkRefs} 存证据 chunk id，正文里永远不许出现 chunk id。</b>
 *       源侧在系统提示词里写了两条规则保证这点（不许新写 + 编辑时删掉历史遗留的）。
 *       chunk id 进正文 = 用户看到噪音；删掉 {@code chunkRefs} = 引用溯源断裂。
 *       两者必须分离。</li>
 *   <li><b>{@code folderId} 是目录归属的单一真源</b>；{@code categoryPath} / {@code wikiPath} /
 *       {@code depth} 是它的<b>冗余投影</b>（写时同步，免 JOIN 读）。
 *       ⛔ 不要让投影和真源各自演化 —— 一旦不一致，目录树和面包屑会互相矛盾。</li>
 * </ol>
 *
 * <h2>为什么 {@code slug} 是寻址键</h2>
 * 页面用 {@code entity/acme-corp} 这种可读 slug 而不是 UUID 寻址 ——
 * 这样 {@code [[entity/acme-corp|Acme]]} 这种 wiki 链接天然稳定，改标题不破链。
 * ⚠️ 但「同一知识库内 slug 唯一」只在<b>未删除</b>范围内成立（部分索引）。
 */
@Table(
    value = "kb_wiki_page",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbWikiPageRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 寻址键：{@code entity/xxx} / {@code concept/xxx} / {@code summary/<knowledgeId>}。 */
    private String slug;

    /** 人类可读标题。 */
    private String title;

    /** 页面类型：summary / entity / concept / index / synthesis / comparison。 */
    private String pageType;

    /** 状态：draft / published / archived。 */
    private String status;

    /** 完整 Markdown 正文。⛔ 不含 chunk id。 */
    private String content;

    /** 一行摘要（15-40 词，索引用）。 */
    private String summary;

    /** 语义父页 slug（可空）；目录归属看 {@code folderId}。 */
    private String parentSlug;

    /** 目录归属单一真源（空串 = 根）。 */
    private String folderId;

    /** 目录面包屑（JSON 数组字符串）· {@code folderId} 的冗余投影。 */
    private String categoryPath;

    /** 物化路径（如 {@code 地点/地址}）· 冗余投影。 */
    private String wikiPath;

    /** 层级深度 · 冗余投影。 */
    private Integer depth;

    /** 同级排序。 */
    private Integer sortOrder;

    /** 来源文档 ID 列表（JSON 数组字符串）。 */
    private String sourceRefs;

    /** ⭐ 证据 chunk ID 列表（JSON 数组字符串）· 永不进正文。 */
    private String chunkRefs;

    /** 反向链接缓存（JSON 数组字符串）。 */
    private String inLinks;

    /** 正向链接缓存（JSON 数组字符串）。 */
    private String outLinks;

    /** 附加元数据（JSON 对象字符串）。 */
    private String pageMetadata;

    /** 别名列表（JSON 数组字符串）· 去重判同用。 */
    private String aliases;

    /** 页面版本号（配合 {@code kb_wiki_page_revision.version}）。 */
    private Integer version;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getPageType() { return pageType; }
    public void setPageType(String v) { this.pageType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getParentSlug() { return parentSlug; }
    public void setParentSlug(String v) { this.parentSlug = v; }
    public String getFolderId() { return folderId; }
    public void setFolderId(String v) { this.folderId = v; }
    public String getCategoryPath() { return categoryPath; }
    public void setCategoryPath(String v) { this.categoryPath = v; }
    public String getWikiPath() { return wikiPath; }
    public void setWikiPath(String v) { this.wikiPath = v; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer v) { this.depth = v; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
    public String getSourceRefs() { return sourceRefs; }
    public void setSourceRefs(String v) { this.sourceRefs = v; }
    public String getChunkRefs() { return chunkRefs; }
    public void setChunkRefs(String v) { this.chunkRefs = v; }
    public String getInLinks() { return inLinks; }
    public void setInLinks(String v) { this.inLinks = v; }
    public String getOutLinks() { return outLinks; }
    public void setOutLinks(String v) { this.outLinks = v; }
    public String getPageMetadata() { return pageMetadata; }
    public void setPageMetadata(String v) { this.pageMetadata = v; }
    public String getAliases() { return aliases; }
    public void setAliases(String v) { this.aliases = v; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer v) { this.version = v; }
}

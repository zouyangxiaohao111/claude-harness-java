package com.nexusai.kb.agent.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 自定义智能体扩展（{@code kb_agent}）· V274 建表 · 批次 W10。
 *
 * <p><b>⭐ 分工（用户 2026-11-05 裁定，本类的前提）</b>：
 * <pre>
 *   agents/&lt;id&gt;.md   ← 本体：系统提示词 / 模型 / 工具 / 权限模式 / 技能
 *   kb_agent（本表）    ← 只存 MD 装不下的扩展：知识库范围 / 检索参数 / 引用开关
 * </pre>
 *
 * <p><b>⛔ 本表不存的东西</b>：系统提示词、模型、工具清单、permissionMode。
 * 两边都存同一项就一定会分叉 —— 用户改了 MD 没生效、或改了这里被 MD 覆盖，
 * 都无从判断哪个算数。
 *
 * <p><b>主键 = MD 的 agentType</b>（不是独立 UUID）：用户原话「kb_agent 中存的是
 * agent id，直接查到是哪个专家」。同一个标识贯穿 MD 与 DB，不需要第二张映射表。
 *
 * <p><b>孤儿语义</b>：MD 被手工删掉时本行成孤儿。列表接口如实标 {@code md_missing: true}，
 * ⛔ 不静默忽略、也不自动清理（用户可能只是临时移走文件）。
 */
@Table(
    value = "kb_agent",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbAgentRecord extends KbBaseRecord {

    /** 智能体标识 = {@code agents/<id>.md} 的 agentType。 */
    @Id(keyType = KeyType.None)
    private String id;

    /**
     * 知识库检索范围：{@code all}（全部可检索库）/ {@code selected}（只用
     * {@link #knowledgeBases} 里的）/ {@code none}（不检索）。
     */
    private String kbSelectionMode;

    /** JSON 数组字符串（库 id 列表）；{@code kbSelectionMode='selected'} 时才看。 */
    private String knowledgeBases;

    /** 仅当用户 @ 明确提及时才检索知识库（对齐源侧 {@code retrieve_kb_only_when_mentioned}）。 */
    private Integer retrieveKbOnlyWhenMentioned;

    /** 向量召回条数（null = 用检索层默认）。 */
    private Integer embeddingTopK;

    /** 关键词召回阈值（null = 用检索层默认）。 */
    private Double keywordThreshold;

    /** 向量相似度阈值（null = 用检索层默认）。 */
    private Double vectorThreshold;

    /** 重排后保留条数（null = 用检索层默认）。 */
    private Integer rerankTopK;

    /** 重排阈值（null = 用检索层默认）。 */
    private Double rerankThreshold;

    /** 回答是否带引用（null = 默认开，对齐源侧 nil⇒true）。 */
    private Integer citationEnabled;

    // ⚠️ 时间字段**不在本类重声明**：MyBatis-Flex 的 applyAllClass 会逐级扫父类字段注解
    //    （{@link KbBaseRecord} 的 createTime/updateTime 已挂 KbSqliteLocalDateTimeTypeHandler）。
    //    重复声明反而会让「同名字段两份」引发映射歧义。

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKbSelectionMode() { return kbSelectionMode; }
    public void setKbSelectionMode(String v) { this.kbSelectionMode = v; }
    public String getKnowledgeBases() { return knowledgeBases; }
    public void setKnowledgeBases(String v) { this.knowledgeBases = v; }
    public Integer getRetrieveKbOnlyWhenMentioned() { return retrieveKbOnlyWhenMentioned; }
    public void setRetrieveKbOnlyWhenMentioned(Integer v) { this.retrieveKbOnlyWhenMentioned = v; }
    public Integer getEmbeddingTopK() { return embeddingTopK; }
    public void setEmbeddingTopK(Integer v) { this.embeddingTopK = v; }
    public Double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(Double v) { this.keywordThreshold = v; }
    public Double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(Double v) { this.vectorThreshold = v; }
    public Integer getRerankTopK() { return rerankTopK; }
    public void setRerankTopK(Integer v) { this.rerankTopK = v; }
    public Double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(Double v) { this.rerankThreshold = v; }
    public Integer getCitationEnabled() { return citationEnabled; }
    public void setCitationEnabled(Integer v) { this.citationEnabled = v; }
}

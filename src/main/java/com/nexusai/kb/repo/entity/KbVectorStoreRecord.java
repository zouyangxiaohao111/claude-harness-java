package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 向量库实例（{@code kb_vector_store}）· V271 建表 · 批次 W8。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.VectorStore}
 * （{@code internal/types/vectorstore.go}，表 {@code vector_stores}，迁移 000032）。
 *
 * <p><b>⭐ 与 W1 的 {@code KbRetrievalEngine} 是两层，不是重复</b>：
 * <ul>
 *   <li>W1 的 Registry 是<b>类型层</b>：知识库选「用哪种引擎」（{@code retriever_engine}），
 *       连接配置不在库里。</li>
 *   <li>本表是<b>实例层</b>：同一种引擎可以建多个实例，各自独立的 host/port/api_key。</li>
 * </ul>
 * 用户 2026-11-05 裁定「实例层覆盖类型层」⇒ 知识库绑了 {@code vector_store_id} 就按实例配置建驱动，
 * 没绑就回落 W1 的类型默认。⛔ 不推翻 W1 已真机验证的链路。
 *
 * <p><b>⚠️ 本表只做「同类型多实例」的隔离</b>。源侧明确排除 postgres / sqlite 作为可注册类型
 * （它们的物理表名写死、同一实例上再建一个没有任何隔离效果，会让用户误以为数据分开了）。
 */
@Table(
    value = "kb_vector_store",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbVectorStoreRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    /** 实例名（同库唯一，逻辑删除的行不占名额）。 */
    private String name;

    /** 引擎类型：7 种之一，见 {@code KbVectorStoreTypes.validTypes()}。 */
    private String engineType;

    /** 连接配置 JSON（host/port/api_key/...；字段 schema 见 {@code KbVectorStoreTypes}）。 */
    private String connectionConfig;

    /** 索引配置 JSON（collection_name/shards/...）。 */
    private String indexConfig;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String v) { this.engineType = v; }
    public String getConnectionConfig() { return connectionConfig; }
    public void setConnectionConfig(String v) { this.connectionConfig = v; }
    public String getIndexConfig() { return indexConfig; }
    public void setIndexConfig(String v) { this.indexConfig = v; }
}

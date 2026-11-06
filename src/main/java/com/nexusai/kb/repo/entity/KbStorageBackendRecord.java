package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 存储后端实例（{@code kb_storage_backend}）· V271 建表 · 批次 W8。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.StorageBackend}
 * （{@code internal/types/storagebackend.go}，表 {@code storage_backends}，迁移 000068）。
 *
 * <p><b>用户 2026-11-05 裁定</b>：「文件服务跟其他的一样，保留接口兼容；
 * 如果有 minio 那直接无缝接入，没有就本地」。
 * ⇒ 本表 + {@code KbFileStorage} 接口；{@code local} 恒可用，
 * {@code minio}/{@code cos} 在「配了凭据 + 本机有 SDK」时才可用，否则如实报到不了。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@code provider}：{@code local} / {@code minio} / {@code cos}</li>
 *   <li>{@code source}：{@code user} = 用户建的；{@code env} = 由配置注入（本仓的本地磁盘）</li>
 *   <li>{@code status}：{@code active} / {@code disabled}</li>
 *   <li>{@code legacyAlias}：源侧用来兼容「老部署按 provider 名挑后端」的过渡字段。
 *       本仓不用，但列保留以对齐源侧形态。</li>
 * </ul>
 */
@Table(
    value = "kb_storage_backend",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbStorageBackendRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String name;

    /** local | minio | cos */
    private String provider;

    /** provider 配置 JSON（local: root；minio/cos: endpoint/bucket/access_key/secret_key）。 */
    private String config;

    /** user | env */
    private String source;

    /** active | disabled */
    private String status;

    /** 源侧过渡字段（本仓不用，保留列）。 */
    private Integer legacyAlias;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getLegacyAlias() { return legacyAlias; }
    public void setLegacyAlias(Integer v) { this.legacyAlias = v; }
}

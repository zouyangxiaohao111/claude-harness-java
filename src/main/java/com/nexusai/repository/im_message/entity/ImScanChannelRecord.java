package com.nexusai.repository.im_message.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.im_message.ImScanChannelEntry;

/**
 * MyBatis-Flex 持久化记录：{@code im_scan_channel} 表行（V315）· 扫描渠道配置。
 *
 * <p>命名：camelCase → snake 精确映射（instanceId→instance_id、enabled→enabled、
 * lastScanAt→last_scan_at、lastChatCount→last_chat_count、lastNewCount→last_new_count、
 * lastError→last_error、createdAt→created_at、updatedAt→updated_at）。
 *
 * <p>⚠️ {@code enabled} 用 {@code Integer} 而非 {@code boolean}：SQLite 无布尔类型，
 * 与本仓其它表同口径（MyBatis-Flex 对 boolean 的 null 映射会 NPE）。
 *
 * <p>⚠️ 主键就是 {@code instance_id}（一行 = 一个要扫的实例）⇒ 不另造 id 列。
 */
@Table("im_scan_channel")
public class ImScanChannelRecord {

    @Id private String instanceId;

    private Integer enabled;
    private String lastScanAt;
    private Integer lastChatCount;
    private Integer lastNewCount;
    private String lastError;
    private String createdAt;
    private String updatedAt;

    public ImScanChannelEntry toDomain() {
        return new ImScanChannelEntry(instanceId, enabled != null && enabled != 0,
            lastScanAt, lastChatCount, lastNewCount, lastError, createdAt, updatedAt);
    }

    /** 纯映射（{@code enabled} 用 1/0 与库里的 INTEGER 对齐）。 */
    public static ImScanChannelRecord fromDomain(ImScanChannelEntry e) {
        ImScanChannelRecord r = new ImScanChannelRecord();
        r.setInstanceId(e.instanceId());
        r.setEnabled(e.enabled() ? 1 : 0);
        r.setLastScanAt(e.lastScanAt());
        r.setLastChatCount(e.lastChatCount());
        r.setLastNewCount(e.lastNewCount());
        r.setLastError(e.lastError());
        r.setCreatedAt(e.createdAt());
        r.setUpdatedAt(e.updatedAt());
        return r;
    }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer v) { this.enabled = v; }
    public String getLastScanAt() { return lastScanAt; }
    public void setLastScanAt(String v) { this.lastScanAt = v; }
    public Integer getLastChatCount() { return lastChatCount; }
    public void setLastChatCount(Integer v) { this.lastChatCount = v; }
    public Integer getLastNewCount() { return lastNewCount; }
    public void setLastNewCount(Integer v) { this.lastNewCount = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String v) { this.updatedAt = v; }
}

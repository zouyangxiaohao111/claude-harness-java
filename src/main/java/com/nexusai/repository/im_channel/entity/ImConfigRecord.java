package com.nexusai.repository.im_channel.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.im_channel.ImConfigEntry;

/**
 * {@code im_config} 表实体（单行，V307）。
 *
 * <p>布尔列用 {@link Integer} 存 0/1（SQLite 无原生 BOOLEAN），
 * 在 {@link #toDomain()} 里转成 {@code boolean}。
 */
@Table("im_config")
public class ImConfigRecord {

    @Id private String id;

    private Integer readAll;
    private Integer autoBind;
    private Integer aiDirect;
    private Integer existingOnly;
    private Integer takeoverConfirm;
    private Integer audit;
    /** V311：是否允许 AI 以用户本人名义发消息（0/1，默认 0）。 */
    private Integer allowUserIdentity;

    private String creator;
    private String createTime;
    private String updater;
    private String updateTime;
    private Integer deleted;

    public ImConfigEntry toDomain() {
        return new ImConfigEntry(
            on(readAll, true),
            on(autoBind, true),
            on(aiDirect, false),
            on(existingOnly, true),
            on(takeoverConfirm, true),
            on(audit, true),
            // 默认关闭：AI 只能以机器人身份说话，"以用户名义"必须用户显式打开
            on(allowUserIdentity, false),
            updateTime
        );
    }

    private static boolean on(Integer v, boolean fallback) {
        return v == null ? fallback : v != 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getReadAll() { return readAll; }
    public void setReadAll(Integer readAll) { this.readAll = readAll; }
    public Integer getAutoBind() { return autoBind; }
    public void setAutoBind(Integer autoBind) { this.autoBind = autoBind; }
    public Integer getAiDirect() { return aiDirect; }
    public void setAiDirect(Integer aiDirect) { this.aiDirect = aiDirect; }
    public Integer getExistingOnly() { return existingOnly; }
    public void setExistingOnly(Integer existingOnly) { this.existingOnly = existingOnly; }
    public Integer getTakeoverConfirm() { return takeoverConfirm; }
    public void setTakeoverConfirm(Integer takeoverConfirm) { this.takeoverConfirm = takeoverConfirm; }
    public Integer getAudit() { return audit; }
    public void setAudit(Integer audit) { this.audit = audit; }
    public Integer getAllowUserIdentity() { return allowUserIdentity; }
    public void setAllowUserIdentity(Integer v) { this.allowUserIdentity = v; }
    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdater() { return updater; }
    public void setUpdater(String updater) { this.updater = updater; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}

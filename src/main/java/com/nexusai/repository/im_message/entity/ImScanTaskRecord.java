package com.nexusai.repository.im_message.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * {@code im_scan_task} 表实体（单行，V318）· 「消息扫描」系统任务的任务级开关。
 *
 * <p>只有 {@code paused} 一个业务列：渠道勾选仍在 {@code im_scan_channel}，
 * 这里不碰它（暂停不得丢用户的渠道选择）。
 *
 * <p>布尔列用 {@link Integer} 存 0/1（SQLite 无原生 BOOLEAN），与 {@code ImConfigRecord} 同口径。
 */
@Table("im_scan_task")
public class ImScanTaskRecord {

    @Id private String id;

    /** 0 = 运行中，1 = 已暂停。 */
    private Integer paused;

    private String creator;
    private String createTime;
    private String updater;
    private String updateTime;
    private Integer deleted;

    /** 行缺失或列为 null 时按「未暂停」（与 DDL 默认值一致，升级后扫描不会悄悄停掉）。 */
    public boolean paused() {
        return paused != null && paused != 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getPaused() { return paused; }
    public void setPaused(Integer paused) { this.paused = paused; }
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

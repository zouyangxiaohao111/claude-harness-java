package com.nexusai.repository.project.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * MyBatis-Flex 持久化记录：{@code claude_md_include_approval} 表行（V74）。
 *
 * <p>对齐 CC project config 的两个布尔标志 —— {@code hasClaudeMdExternalIncludesApproved}
 * （config.ts:115，缺省 false :146）与 {@code hasClaudeMdExternalIncludesWarningShown}
 * （config.ts:116，缺省 false :147）：CC 把它们存 project config（跨进程存盘 ⇒ 重启不重复弹审批），
 * Java 落本表作跨重启事实源。
 *
 * <p>{@code configKey} = <b>归一化 canonical 项目键</b>
 * （{@code ProjectService.normalizePathKey(AutoMemPaths.findCanonicalGitRoot(会话项目根) ?? 会话项目根)}）
 * —— ⛔ 不是 {@code projects.path}（canonical 键是 git 仓根，子目录 / worktree 折叠到仓根），
 * 也⛔不是 sessionId（CC 的宿主是项目配置，不是会话）。
 *
 * <p>DDD 分层：这是 persistence 关注点（带 {@code @Table}），归 {@code repository} 包；
 * 应用侧只持 {@code domain.project.ClaudeMdIncludeApprovalStore}，不直接依赖本类。
 *
 * <p>⚠️ 两个 {@code INTEGER} 列在 Java 侧用 {@link Integer}（0/1）而非 {@code boolean}：
 * 与列类型 1:1，且「无行」判据 = {@code selectOneById(...) == null}（不靠列值）。
 */
@Table("claude_md_include_approval")
public class ClaudeMdIncludeApprovalRecord {

    @Id private String configKey;
    private Integer externalIncludesApproved;
    private Integer externalIncludesWarningShown;
    private String createdAt;
    private String updatedAt;

    // ============== getters/setters ==============

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public Integer getExternalIncludesApproved() { return externalIncludesApproved; }
    public void setExternalIncludesApproved(Integer externalIncludesApproved) {
        this.externalIncludesApproved = externalIncludesApproved;
    }
    public Integer getExternalIncludesWarningShown() { return externalIncludesWarningShown; }
    public void setExternalIncludesWarningShown(Integer externalIncludesWarningShown) {
        this.externalIncludesWarningShown = externalIncludesWarningShown;
    }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}

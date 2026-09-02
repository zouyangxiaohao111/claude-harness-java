package com.nexusai.application.agent.permission;

/**
 * 工作目录白名单条目 · 对齐 CC types/permissions.ts:143-146
 *
 * <p>CC 中 {@code WorkingDirectorySource} 是 {@code PermissionRuleSource} 的纯类型别名
 * （types/permissions.ts:138 {@code export type WorkingDirectorySource = PermissionRuleSource}），
 * 因此 {@code source} 字段直接使用 8 值 {@link PermissionRuleSource}，不复刻独立 enum（避免双轨）。
 *
 * @param path   目录路径
 * @param source 目录来源 · CC original: source: WorkingDirectorySource (= PermissionRuleSource 纯别名, types/permissions.ts:145)
 */
public record AdditionalWorkingDirectory(String path, PermissionRuleSource source) {
    public AdditionalWorkingDirectory {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
    }
}

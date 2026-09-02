package com.nexusai.application.agent.context;

import java.util.List;
import java.util.Objects;

/**
 * claudemd 记忆文件信息 · 对齐 CC {@code Open-ClaudeCode/src/utils/claudemd.ts:229-243}
 * {@code MemoryFileInfo}。
 *
 * <table>
 *   <tr><th>Java 字段</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #path()}</td><td>{@code path}</td><td>claudemd.ts:230</td></tr>
 *   <tr><td>{@link #type()}</td><td>{@code type}</td><td>claudemd.ts:231</td></tr>
 *   <tr><td>{@link #content()}</td><td>{@code content}</td><td>claudemd.ts:232</td></tr>
 *   <tr><td>{@link #parent()}</td><td>{@code parent?}</td><td>claudemd.ts:233</td></tr>
 *   <tr><td>{@link #globs()}</td><td>{@code globs?}</td><td>claudemd.ts:234</td></tr>
 *   <tr><td>{@link #contentDiffersFromDisk()}</td><td>{@code contentDiffersFromDisk?}</td><td>claudemd.ts:241</td></tr>
 *   <tr><td>{@link #rawContent()}</td><td>{@code rawContent?}</td><td>claudemd.ts:242</td></tr>
 * </table>
 *
 * <p>{@code contentDiffersFromDisk}：auto-injection 变换 {@code content}（strip HTML
 * 注释 / strip frontmatter / 截断 MEMORY.md）后不再匹配磁盘字节时置 true；此时
 * {@code rawContent} 保存未修改的磁盘字节（供调用方缓存 {@code isPartialView}
 * readFileState 条目 —— 缓存提供 dedup + change detection，但 Edit/Write 仍需显式
 * Read 后才能进行）。
 *
 * @param path                  文件路径
 * @param type                  记忆文件类型（文件来源）
 * @param content               处理后的内容（strip frontmatter + strip HTML 注释 +
 *                              AutoMem/TeamMem 截断）
 * @param parent                @include 了本文件的父文件路径（顶层文件 null）
 * @param globs                 frontmatter paths 条件规则 glob 模式（无 → null）
 * @param contentDiffersFromDisk 处理内容是否与磁盘字节不同
 * @param rawContent            未修改的磁盘原始内容（contentDiffersFromDisk=true 时非 null）
 */
public record MemoryFileInfo(
        String path,
        ClaudemdMemoryType type,
        String content,
        String parent,
        List<String> globs,
        boolean contentDiffersFromDisk,
        String rawContent
) {
    public MemoryFileInfo {
        Objects.requireNonNull(path, "path is null");
        Objects.requireNonNull(type, "type is null");
        Objects.requireNonNull(content, "content is null");
        globs = globs == null ? null : List.copyOf(globs);
    }

    /**
     * 便捷构造 · CC claudemd.ts:389-397 parseMemoryFileContent 返回形状。
     *
     * @param globs null 表示无条件规则（无条件加载），非空表示条件规则 globs
     */
    public static MemoryFileInfo of(String path, ClaudemdMemoryType type, String content, List<String> globs) {
        return new MemoryFileInfo(path, type, content, null, globs, false, null);
    }
}

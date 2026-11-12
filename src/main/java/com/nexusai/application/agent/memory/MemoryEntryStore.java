package com.nexusai.application.agent.memory;

import com.nexusai.model.memory.dto.MemoryEntryDetailDto;
import com.nexusai.model.memory.dto.MemoryEntrySummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆条目 CRUD 存储层 · <b>仅供 UI（/memory 编辑器）使用</b>，不参与模型对话链路。
 *
 * <p><b>与 {@link MemoryStorage} 的职责边界（重要）</b>：
 * <ul>
 *   <li>{@link MemoryStorage} 保持<b>纯读取</b>——类注释写明「CC 无程序化记忆写 API，记忆文件由模型用
 *       Write/Edit 维护」，旧 CRUD 层因「write 后自动 rebuildIndex 全量重写 MEMORY.md 会覆盖模型内容」
 *       被整层删除。那是针对<b>索引</b>的裁决，本类<b>原样继承、不推翻</b>。</li>
 *   <li>本类只做<b>单条 .md 条目</b>的读/写/删，<b>永不触碰 MEMORY.md</b>（路径校验里直接拒绝）。
 *       因此不会发生「程序写回覆盖模型内容」——索引始终只由模型维护。</li>
 * </ul>
 *
 * <p><b>安全校验（三层，缺一不可）</b>：
 * <ol>
 *   <li>{@code file} 必须是<b>单层文件名</b>：拒绝空值、路径分隔符、{@code ..}、绝对路径、非 {@code .md} 后缀；</li>
 *   <li>归一化后父目录必须<b>等于</b>记忆目录（{@code memoryDir}），防 {@code ../} 逃逸；</li>
 *   <li>拒绝 {@code MEMORY.md}（索引）与符号链接（防指向目录外）。</li>
 * </ol>
 *
 * <p><b>type 归属判定</b>：以<b>磁盘文件 frontmatter 的 {@code type:} 为准</b>（{@link MemoryType#fromString}），
 * 不信任调用方传入的 type —— 否则伪造 type 可绕过「只删某类」的预期。
 */
@Component
public class MemoryEntryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntryStore.class);

    /** 单条记忆正文读取上限（防超长文件把前端撑爆；正常条目 2~30KB，127KB 的 user_profile 也能读全）。 */
    private static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024;

    @Autowired(required = false)
    private MemoryStorage memoryStorage;

    /** 测试注入用（无 Spring 容器时直接给 storage）。 */
    public MemoryEntryStore() {
    }

    public MemoryEntryStore(MemoryStorage memoryStorage) {
        this.memoryStorage = memoryStorage;
    }

    /**
     * 列出会话所属项目的全部记忆条目（按 mtime 降序，不含正文）。
     *
     * <p>复用 {@link MemoryScanner#scan}（已排除 MEMORY.md、上限 {@code MEMORY_MAX_FILES}）。
     *
     * @param sessionId 会话 id（用于解析 per-project 记忆目录）
     * @return 条目摘要列表；无绑定项目 / 目录不存在 → 空列表
     */
    public List<MemoryEntrySummaryDto> list(String sessionId) {
        Path dir = memoryDir(sessionId);
        if (dir == null) {
            log.debug("[MemoryEntryStore] list: 会话 {} 无有效记忆目录 → 空", sessionId);
            return List.of();
        }
        List<MemoryEntry> entries = new MemoryScanner().scan(dir, null);
        List<MemoryEntrySummaryDto> out = new ArrayList<>(entries.size());
        for (MemoryEntry e : entries) {
            long size = -1L;
            try {
                size = Files.size(e.filePath());
            } catch (IOException ex) {
                log.debug("[MemoryEntryStore] 读取大小失败（忽略）: {} - {}", e.filePath(), ex.getMessage());
            }
            out.add(new MemoryEntrySummaryDto(
                e.type() == null ? null : e.type().toTypeValue(),
                nameOf(e.filePath()),
                e.description(),
                e.filename(),
                e.mtime() == null ? 0L : e.mtime().toEpochMilli(),
                size));
        }
        if (log.isDebugEnabled()) {
            log.debug("[MemoryEntryStore] list: session={} dir={} 条数={}", sessionId, dir, out.size());
        }
        return out;
    }

    /**
     * 读取单条记忆正文。
     *
     * @param sessionId 会话 id
     * @param file      条目文件名（单层 .md）
     * @return 文件正文（UTF-8）
     * @throws IllegalArgumentException 路径非法 / 文件不存在 / 超限
     */
    public String read(String sessionId, String file) {
        Path path = resolveSafe(sessionId, file);
        try {
            long size = Files.size(path);
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("memory entry too large: " + size + " bytes");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("[MemoryEntryStore] read: {} ({} chars)", path, content.length());
            }
            return content;
        } catch (IOException e) {
            throw new IllegalArgumentException("memory entry not readable: " + file, e);
        }
    }

    /**
     * 读取单条记忆详情（正文 + type + mtime + size）。
     *
     * @param sessionId 会话 id
     * @param file      条目文件名（单层 .md）
     * @return 详情
     * @throws IllegalArgumentException 路径非法 / 文件不存在 / 超限
     */
    public MemoryEntryDetailDto detail(String sessionId, String file) {
        Path path = resolveSafe(sessionId, file);
        try {
            long size = Files.size(path);
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("memory entry too large: " + size + " bytes");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            MemoryType type = MemoryType.fromString(frontmatterValue(content, "type"));
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (log.isDebugEnabled()) {
                log.debug("[MemoryEntryStore] detail: {} type={} {} chars", path, type, content.length());
            }
            return new MemoryEntryDetailDto(
                type == null ? null : type.toTypeValue(),
                path.getFileName().toString(),
                content,
                mtime,
                size);
        } catch (IOException e) {
            throw new IllegalArgumentException("memory entry not readable: " + file, e);
        }
    }

    /**
     * 覆盖写单条记忆（无 upsert：文件必须已存在）。
     *
     * <p>⛔ 只写这一个文件，<b>不改 MEMORY.md</b> —— 索引由模型维护（见类注释）。
     *
     * @param sessionId 会话 id
     * @param file      条目文件名（单层 .md）
     * @param content   新内容（可为空串 = 清空）
     * @throws IllegalArgumentException 路径非法 / 文件不存在
     */
    public void write(String sessionId, String file, String content) {
        if (content == null) {
            throw new IllegalArgumentException("memory entry content is required");
        }
        Path path = resolveSafe(sessionId, file);
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("[MemoryEntryStore] 写入记忆条目: {} ({} chars, 索引未改动)", path, content.length());
        } catch (IOException e) {
            throw new IllegalStateException("memory entry not writable: " + file, e);
        }
    }

    /**
     * 删除单条记忆文件。
     *
     * <p>⛔ <b>不删 MEMORY.md 里指向它的那一行</b> —— 索引由模型维护；残留索引行由 UI 提示用户，
     * 或后续由「AI 修剪」功能统一处理（本类不越界）。
     *
     * @param sessionId 会话 id
     * @param file      条目文件名（单层 .md）
     * @throws IllegalArgumentException 路径非法 / 文件不存在
     */
    public void delete(String sessionId, String file) {
        Path path = resolveSafe(sessionId, file);
        try {
            Files.delete(path);
            log.info("[MemoryEntryStore] 删除记忆条目: {} (MEMORY.md 索引未改动)", path);
        } catch (IOException e) {
            throw new IllegalStateException("memory entry not deletable: " + file, e);
        }
    }

    // ── 内部 ──

    private Path memoryDir(String sessionId) {
        MemoryStorage storage = memoryStorage;
        if (storage == null) {
            log.warn("[MemoryEntryStore] MemoryStorage 未注入 → 无法解析记忆目录");
            return null;
        }
        return storage.memoryDir(sessionId);
    }

    /**
     * 三层校验后返回可安全操作的绝对路径。
     *
     * @throws IllegalArgumentException 任一校验失败（调用方映射为 400）
     */
    private Path resolveSafe(String sessionId, String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("memory entry file is required");
        }
        String name = file.trim();
        // ① 单层文件名：拒绝分隔符 / 上跳 / 绝对路径 / 非 .md
        if (name.contains("/") || name.contains("\\") || name.contains("..")
            || name.contains(":") || !name.endsWith(".md")) {
            log.warn("[MemoryEntryStore] 非法条目名（拒绝）: {}", name);
            throw new IllegalArgumentException("invalid memory entry name: " + name);
        }
        // ② 索引不可经本类操作（模型维护）
        if (MemoryConstants.MEMORY_INDEX_NAME.equals(name)) {
            log.warn("[MemoryEntryStore] 拒绝操作索引文件: {}", name);
            throw new IllegalArgumentException("memory index is not editable through this API");
        }
        Path dir = memoryDir(sessionId);
        if (dir == null) {
            throw new IllegalArgumentException("no memory directory bound for this session");
        }
        Path normalizedDir = dir.toAbsolutePath().normalize();
        Path target = normalizedDir.resolve(name).toAbsolutePath().normalize();
        // ③ 父目录必须等于记忆目录（防 ../ 逃逸）
        if (!normalizedDir.equals(target.getParent())) {
            log.warn("[MemoryEntryStore] 路径逃逸（拒绝）: dir={} target={}", normalizedDir, target);
            throw new IllegalArgumentException("memory entry escapes memory directory: " + name);
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("memory entry is a symbolic link: " + name);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("memory entry not found: " + name);
        }
        return target;
    }

    /**
     * 从正文 frontmatter 取某字段值（仅扫前 30 行 + 首个 {@code ---} 块内，避免误命中正文）。
     *
     * @param content 文件全文
     * @param key     字段名（type / name / description）
     * @return 字段值；缺失 → {@code null}
     */
    private static String frontmatterValue(String content, String key) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        boolean inBlock = false;
        int limit = Math.min(lines.length, 30);
        for (int i = 0; i < limit; i++) {
            String l = lines[i].trim();
            if ("---".equals(l)) {
                if (!inBlock) {
                    inBlock = true;
                    continue;
                }
                break;
            }
            if (!inBlock) {
                continue;
            }
            if (l.startsWith(key + ":")) {
                String v = l.substring(key.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 从文件 frontmatter 取 {@code name:}（列表标题用）；缺失回落文件名。 */
    private static String nameOf(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int limit = Math.min(lines.size(), 30);
            for (int i = 0; i < limit; i++) {
                String l = lines.get(i);
                if (l.startsWith("name:")) {
                    String v = l.substring(5).trim();
                    if (!v.isEmpty()) {
                        return v;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("[MemoryEntryStore] 读 name 失败（回落文件名）: {} - {}", file, e.getMessage());
        }
        return file.getFileName().toString();
    }

    /** 供测试/诊断：条目名 → 绝对路径（不做存在性校验）。 */
    Map<String, Path> describePaths(String sessionId) {
        Path dir = memoryDir(sessionId);
        if (dir == null) {
            return Map.of();
        }
        Map<String, Path> m = new LinkedHashMap<>();
        for (MemoryEntry e : new MemoryScanner().scan(dir, null)) {
            m.put(e.filename(), e.filePath());
        }
        return m;
    }
}

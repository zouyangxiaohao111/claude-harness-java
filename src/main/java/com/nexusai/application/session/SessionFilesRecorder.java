package com.nexusai.application.session;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.StructuredPatchGenerator;
import com.nexusai.application.agent.tool.StructuredPatchHunk;
import com.nexusai.eventbus.ws.FilesChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级「agent 改动文件」记录 + 实时推送 + 回滚数据源（Phase 2 · spec
 * {@code 2026-09-07-session-file-panel-collapse-atfile} 阶段 2）。
 *
 * <p>职责（对齐 CC file-history 语义，但独立命名空间、不做 CC 全量镜像）：
 * <ul>
 *   <li>在 {@code EditFileTool}/{@code WriteFileTool} 写盘点被调用（可选注入，仿 fileHistoryService），
 *       持有本会话首次写某文件前的<b>快照</b>（= 回滚目标）；后续同会话再改同一文件不回写快照。</li>
 *   <li>维护 per-session 改动文件注册表，行变更计数按<b>快照 vs 当前</b>净计（多次编辑自然累计）。</li>
 *   <li>每次记录后把<b>全量快照</b>推到预留主题 {@code /topic/sessions/{sessionId}/files}（前端整表替换）。</li>
 *   <li>查询/真实 diff/回滚由 {@code SessionFilesController} 消费（REST GET /files · GET /files/diff · POST /files/revert）。</li>
 * </ul>
 *
 * <p>会话删除时调 {@link #evict(String)} 清理内存 + 落盘（SessionService delete 已接线，防无界增长）。
 * 快照目录：{config}/session-files/{sessionId}/{sha16}@before（与 FileHistoryService 分开命名空间，
 * 避免其全局态跨会话碰撞问题）。</p>
 *
 * <p>本服务纯确定性代码（规则五），不做任何模型判断。</p>
 */
@Component
public class SessionFilesRecorder {

    private static final Logger log = LoggerFactory.getLogger(SessionFilesRecorder.class);

    /** 每会话改动文件表：relPath -> 记录。 */
    private final Map<String, Map<String, Entry>> bySession = new ConcurrentHashMap<>();

    /** 单文件记录（包内可变，仅本类 + 本文件内部使用）。 */
    private static final class Entry {
        String relPath;        // 相对会话 cwd/项目根（工具传入）
        String absPath;        // 绝对路径（回滚/diff 读盘用）
        boolean isNew;         // 本会话首次写前文件不存在 → 回滚=删除
        String snapshotAbs;    // 本会话首次写前快照落盘路径（isNew=false 时非空）
        String baseline;       // 内存基线文本（回滚语义 = 会话首次碰它之前；净 diff 基准）
        volatile int adds;
        volatile int dels;
    }

    /** 对外 API DTO（Jackson 直出 · 前端 SessionFile 形状）。 */
    public record ChangedFileDto(String name, String path, int adds, int dels, boolean isNew) {
    }

    /** diff DTO · 与前端 DiffFile/DiffHunk/DiffLine 一致。 */
    public record DiffFileDto(String name, String path, int adds, int dels, boolean isNew, List<DiffHunkDto> hunks) {
    }

    public record DiffHunkDto(int oldStart, int newStart, List<DiffLineDto> lines) {
    }

    public record DiffLineDto(String type, String text) {
    }

    // SimpMessagingTemplate：@EnableWebSocketMessageBroker 自动注册；可选注入（POJO 测试不破）
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    /**
     * 记录一次 agent 文件写盘（Edit/Write 工具写后调用）。
     *
     * @param sessionId  会话短 id（sess-xxx；null/空 → 跳过）
     * @param relPath    相对路径（展示 + 表键）
     * @param absPath    归一化绝对路径（读盘）
     * @param beforeText 本次写盘前内容（文件此前不存在传 null → isNew 语义）；非首次写同文件忽略
     * @param afterText  本次写盘后内容（净计数基准）
     */
    public void record(String sessionId, String relPath, String absPath, String beforeText, String afterText) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (relPath == null || relPath.isBlank() || absPath == null || afterText == null) {
            return;
        }
        try {
            Map<String, Entry> map = bySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
            Entry e = map.get(relPath);
            if (e == null) {
                e = new Entry();
                e.relPath = relPath;
                e.absPath = Path.of(absPath).toAbsolutePath().normalize().toString();
                e.isNew = beforeText == null;
                if (e.isNew) {
                    // 新建文件：基线=空（回滚=删除文件）
                    e.snapshotAbs = null;
                    e.baseline = "";
                } else {
                    // 首次写已有文件：把 pre-edit 内容落盘为快照（回滚目标），内存保留基线文本
                    e.snapshotAbs = writeSnapshot(sessionId, absPath, beforeText);
                    e.baseline = beforeText;
                }
                map.put(relPath, e);
                log.info("SessionFilesRecorder: 记录会话改动文件 sessionId={} path={} isNew={}", sessionId, relPath, e.isNew);
            }
            // 净行变更 = 快照(基线) vs 当前写后内容
            int[] net = netDiff(e, afterText);
            e.adds = net[0];
            e.dels = net[1];
            publish(sessionId);
        } catch (Exception ex) {
            log.warn("SessionFilesRecorder: record 失败（不影响写盘） sessionId={} path={} cause={}",
                sessionId, relPath, ex.toString());
        }
    }

    /** 会话改动文件列表（REST GET /files）。 */
    public List<ChangedFileDto> list(String sessionId) {
        Map<String, Entry> map = bySession.get(sessionId);
        if (map == null) {
            return List.of();
        }
        List<ChangedFileDto> out = new ArrayList<>();
        for (Entry e : map.values()) {
            out.add(new ChangedFileDto(basename(e.relPath), e.relPath, e.adds, e.dels, e.isNew));
        }
        return out;
    }

    /**
     * 真实 diff（快照 vs 当前磁盘内容）· 无记录返回 null。
     *
     * @param sessionId 会话
     * @param relPath   记录的相对路径
     */
    public DiffFileDto diff(String sessionId, String relPath) {
        Entry e = entryOf(sessionId, relPath);
        if (e == null) {
            return null;
        }
        String current;
        try {
            current = Files.readString(Path.of(e.absPath), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("SessionFilesRecorder: diff 读盘失败 path={} cause={}", e.absPath, ex.toString());
            return null;
        }
        List<StructuredPatchHunk> patch = List.of();
        String baseline = e.isNew ? "" : e.baseline;
        if (!e.isNew) {
            try {
                patch = StructuredPatchGenerator.getPatch(baseline, current);
            } catch (Exception ex) {
                log.warn("SessionFilesRecorder: diff 生成失败 path={} cause={}", relPath, ex.toString());
                // 生成失败仍返回（hunks 空 · 前端显示两侧近似为空），不阻断
            }
        }
        int[] counts = countFromPatch(patch, e.isNew ? current : null);
        List<DiffHunkDto> hunks = isNewHunks(current, e.isNew, patch);
        return new DiffFileDto(basename(relPath), relPath, counts[0], counts[1], e.isNew, hunks);
    }

    /**
     * 回滚到本会话首次碰它之前的状态（恢复快照 / 删除新建文件），并移除记录。
     *
     * @return true=已回滚；false=无记录/失败
     */
    public boolean revert(String sessionId, String relPath) {
        Map<String, Entry> map = bySession.get(sessionId);
        if (map == null) {
            return false;
        }
        Entry e = map.get(relPath);
        if (e == null) {
            return false;
        }
        try {
            if (e.isNew) {
                Files.deleteIfExists(Path.of(e.absPath));
                log.info("SessionFilesRecorder: 回滚(删除新建) sessionId={} path={}", sessionId, relPath);
            } else if (e.snapshotAbs != null) {
                Files.copy(Path.of(e.snapshotAbs), Path.of(e.absPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("SessionFilesRecorder: 回滚(恢复快照) sessionId={} path={}", sessionId, relPath);
            } else {
                log.warn("SessionFilesRecorder: 回滚无快照可恢复（跳过） path={}", relPath);
                return false;
            }
            map.remove(relPath);
            if (map.isEmpty()) {
                bySession.remove(sessionId);
            }
            publish(sessionId);
            return true;
        } catch (Exception ex) {
            log.error("SessionFilesRecorder: 回滚失败 sessionId={} path={} cause={}", sessionId, relPath, ex.toString());
            return false;
        }
    }

    /** 会话删除/清空时清理（SessionService.delete 接线）。 */
    public void evict(String sessionId) {
        Map<String, Entry> removed = bySession.remove(sessionId);
        if (removed == null) {
            return;
        }
        try {
            Path dir = snapshotDir(sessionId);
            if (Files.isDirectory(dir)) {
                // best-effort：删除该会话快照目录
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignore) {
                            // 单文件删除失败不阻断
                        }
                    });
                }
            }
        } catch (Exception ex) {
            log.warn("SessionFilesRecorder: evict 快照目录清理失败 sessionId={} cause={}", sessionId, ex.toString());
        }
        log.info("SessionFilesRecorder: 会话改动文件已清理 sessionId={}", sessionId);
    }

    // ---- internal ----

    private Entry entryOf(String sessionId, String relPath) {
        Map<String, Entry> map = bySession.get(sessionId);
        return map == null ? null : map.get(relPath);
    }

    /** 净 diff 计数：有基线则 StructuredPatchGenerator，新建文件全行算新增。 */
    private int[] netDiff(Entry e, String afterText) {
        if (e.isNew) {
            return new int[]{afterText.split("\\r?\\n", -1).length, 0};
        }
        List<StructuredPatchHunk> patch;
        try {
            patch = StructuredPatchGenerator.getPatch(e.baseline, afterText);
        } catch (Exception ex) {
            log.warn("SessionFilesRecorder: netDiff 生成失败 path={} cause={}", e.relPath, ex.toString());
            return new int[]{e.adds, e.dels};
        }
        return countFromPatch(patch, null);
    }

    private int[] countFromPatch(List<StructuredPatchHunk> patch, String newFileContent) {
        if (patch.isEmpty() && newFileContent != null) {
            return new int[]{newFileContent.split("\\r?\\n", -1).length, 0};
        }
        int adds = 0;
        int dels = 0;
        for (StructuredPatchHunk h : patch) {
            for (String l : h.lines()) {
                char c = l.isEmpty() ? ' ' : l.charAt(0);
                if (c == '+') adds++;
                else if (c == '-' || c == '−') dels++;
            }
        }
        return new int[]{adds, dels};
    }

    /** StructuredPatch → 前端 DiffHunk[]（lines 去掉前缀字符；支持 '-' 与 '−' 双删除符）。 */
    private List<DiffHunkDto> hunksOf(List<StructuredPatchHunk> patch) {
        List<DiffHunkDto> out = new ArrayList<>();
        for (StructuredPatchHunk h : patch) {
            List<DiffLineDto> lines = new ArrayList<>();
            for (String l : h.lines()) {
                String type;
                String text;
                if (l.isEmpty()) {
                    type = "ctx";
                    text = "";
                } else {
                    char c = l.charAt(0);
                    if (c == '+') {
                        type = "add";
                    } else if (c == '-' || c == '−') {
                        type = "del";
                    } else {
                        type = "ctx";
                    }
                    text = l.substring(1);
                }
                lines.add(new DiffLineDto(type, text));
            }
            out.add(new DiffHunkDto(h.oldStart(), h.newStart(), lines));
        }
        return out;
    }

    /** 新建文件：构造单 hunk 全新增（DiffModal 只按 line.text/type 重建两侧）。 */
    private List<DiffHunkDto> isNewHunks(String current, boolean isNew, List<StructuredPatchHunk> patch) {
        if (!isNew) {
            return hunksOf(patch);
        }
        List<DiffLineDto> lines = new ArrayList<>();
        for (String line : current.split("\\r?\\n", -1)) {
            lines.add(new DiffLineDto("add", line));
        }
        List<DiffHunkDto> out = new ArrayList<>();
        out.add(new DiffHunkDto(0, 1, lines));
        return out;
    }

    /** 全量快照推送（前端整表替换）。 */
    private void publish(String sessionId) {
        if (wsTemplate == null) {
            return;
        }
        try {
            List<ChangedFileDto> dtos = list(sessionId);
            List<FilesChangedEvent.FileEntry> entries = new ArrayList<>();
            for (ChangedFileDto d : dtos) {
                entries.add(new FilesChangedEvent.FileEntry(d.path(), d.isNew() ? "added" : "modified", d.adds(), d.dels()));
            }
            FilesChangedEvent event = new FilesChangedEvent(sessionId, null, entries);
            wsTemplate.convertAndSend("/topic/sessions/" + sessionId + "/files", event);
            if (log.isDebugEnabled()) {
                log.debug("SessionFilesRecorder: 已推送 files.changed sessionId={} files={}", sessionId, entries.size());
            }
        } catch (Exception ex) {
            log.warn("SessionFilesRecorder: files.changed 推送失败 sessionId={} cause={}", sessionId, ex.toString());
        }
    }

    private String writeSnapshot(String sessionId, String absPath, String content) throws Exception {
        Path dir = snapshotDir(sessionId);
        Files.createDirectories(dir);
        String fileName = sha16(absPath) + "@before";
        Path target = dir.resolve(fileName);
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target.toString();
    }

    private Path snapshotDir(String sessionId) {
        return Path.of(NexusaiPaths.getAppConfigHomeDir(), "session-files", sessionId);
    }

    private String sha16(String filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(filePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(filePath.hashCode());
        }
    }

    private String basename(String path) {
        if (path == null) {
            return "";
        }
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}

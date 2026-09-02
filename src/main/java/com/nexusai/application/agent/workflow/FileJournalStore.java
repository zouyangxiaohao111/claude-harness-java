package com.nexusai.application.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件 JournalStore（jsonl，每 run 一目录）· CC original: {@code createFileJournalStore}
 * (Open-ClaudeCode/packages/workflow-engine/src/engine/journal.ts:24-50)。
 *
 * <p>路径：{@code <runsDir>/<runId>/journal.jsonl}。
 * <ul>
 *   <li><b>read</b>（journal.ts:28-40）：读文件 → 按 '\n' 分割 → 过滤空白行 → 逐行 JSON.parse →
 *       <b>按 seq 升序重排</b>（append 是完成顺序可能乱序）；读失败（文件不存在等）返回 {@code []}。</li>
 *   <li><b>append</b>（journal.ts:42-44）：mkdir(recursive) + JSON + '\n' 追加。
 *       append 顺序 = 完成顺序（可能乱序，靠 read 的 seq 重排兜底）。</li>
 *   <li><b>truncate</b>（journal.ts:46-48）：递归删除整个 run 目录。</li>
 * </ul>
 *
 * <p>{@code AgentRunResult} 三态经 {@code @JsonTypeInfo(property="kind")} 往返
 * （{@code {"kind":"ok",...}}），对齐 CC 判别字段名。
 */
public class FileJournalStore implements JournalStore {

    private static final Logger log = LoggerFactory.getLogger(FileJournalStore.class);

    private final String runsDir;
    private final ObjectMapper objectMapper;

    /**
     * @param runsDir      run 持久化根目录（{@code <projectRoot>/<WORKFLOW_RUNS_DIR>} = 动态
     *                     .{appName}/workflow-runs，appName 默认 nexusai → .nexusai/workflow-runs，对齐
     *                     {@code getRunsDir()} persistence.ts:32-34）
     * @param objectMapper Jackson 序列化（journal.jsonl 读写用）
     */
    public FileJournalStore(String runsDir, ObjectMapper objectMapper) {
        this.runsDir = runsDir;
        this.objectMapper = objectMapper;
    }

    /** {@code join(runsDir, runId, 'journal.jsonl')} · 对齐 journal.ts:25 */
    private Path pathOf(String runId) {
        return Paths.get(runsDir, runId, "journal.jsonl");
    }

    @Override
    public List<JournalEntry> read(String runId) {
        try {
            String raw = Files.readString(pathOf(runId), StandardCharsets.UTF_8);
            List<JournalEntry> entries = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line != null && !line.trim().isEmpty()) {
                    entries.add(objectMapper.readValue(line, JournalEntry.class));
                }
            }
            // 并行完成序 ≠ 调用序；按 seq 升序重排让 resume 的 key 索引稳定（journal.ts:35-37）。
            // 旧条目缺 seq → Jackson 对原始 int 补 0（等价 CC `(a.seq ?? 0)`）。
            entries.sort(Comparator.comparingInt(JournalEntry::seq));
            return entries;
        } catch (Exception e) {
            // 读失败（文件不存在等）返回 []，不抛 · 对齐 journal.ts:38-40
            if (log.isDebugEnabled()) {
                log.debug("FileJournalStore.read: 读失败返回空列表 runId={} reason={}", runId, e.toString());
            }
            return List.of();
        }
    }

    @Override
    public void append(String runId, JournalEntry entry) throws IOException {
        if (runId == null || entry == null) {
            return;
        }
        Path dir = Paths.get(runsDir, runId);
        Files.createDirectories(dir); // journal.ts:43 mkdir(recursive)
        String line = objectMapper.writeValueAsString(entry) + "\n";
        Files.writeString(pathOf(runId), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); // journal.ts:44 appendFile
        if (log.isDebugEnabled()) {
            log.debug("FileJournalStore.append: runId={} seq={} key={}", runId, entry.seq(), entry.key());
        }
    }

    @Override
    public void truncate(String runId) throws IOException {
        if (runId == null) {
            return;
        }
        Path dir = Paths.get(runsDir, runId);
        if (!Files.exists(dir)) {
            return;
        }
        // journal.ts:47-48 rm(recursive, force) — 递归删除整个 run 目录
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("FileJournalStore.truncate: 删除失败 path={} reason={}", p, e.toString());
                }
            });
        }
        if (log.isDebugEnabled()) {
            log.debug("FileJournalStore.truncate: 已删除 run 目录 runId={}", runId);
        }
    }
}

package com.nexusai.application.agent.memory;

import com.nexusai.model.memory.dto.MemoryEntryDetailDto;
import com.nexusai.model.memory.dto.MemoryEntrySummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryEntryStore} 单测 —— 纯文件系统，<b>不启动 Spring</b>。
 *
 * <p>重点覆盖三件容易做错的事：
 * <ol>
 *   <li><b>路径逃逸</b>：只能操作记忆目录下的单层 {@code .md}，否则 400；</li>
 *   <li><b>索引不可写</b>：{@code MEMORY.md} 是模型维护的索引，任何写/删都必须被拒；</li>
 *   <li><b>四类来自 frontmatter</b>：{@code type} 以文件内容为准，不是文件名前缀。</li>
 * </ol>
 */
class MemoryEntryStoreTest {

    @TempDir
    Path tempDir;

    private Path memDir;
    private MemoryEntryStore store;

    @BeforeEach
    void setUp() throws IOException {
        memDir = tempDir.resolve("memory");
        Files.createDirectories(memDir);
        store = new MemoryEntryStore(new MemoryStorage(memDir));
    }

    private void writeEntry(String name, String type, String body) throws IOException {
        String content = "---\nname: " + name + "\ntype: " + type
            + "\ndescription: 说明-" + name + "\n---\n\n" + body;
        Files.writeString(memDir.resolve(name + ".md"), content, StandardCharsets.UTF_8);
    }

    // ── ① 路径校验 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("路径逃逸与非法名一律拒绝：../、子目录、绝对路径、非 .md、空值")
    void rejectIllegalNames() {
        String[] bad = {
            "../outside.md",
            "sub/dir.md",
            "sub\\dir.md",
            "/etc/passwd.md",
            "C:\\temp\\x.md",
            "note.txt",
            "",
            "   ",
        };
        for (String name : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.read("s1", name), "应拒绝: " + name);
            assertNotNull(e.getMessage());
        }
    }

    @Test
    @DisplayName("MEMORY.md 索引不可经本类读写删")
    void rejectMemoryIndex() throws IOException {
        Path index = memDir.resolve("MEMORY.md");
        Files.writeString(index, "- [a](a.md)\n", StandardCharsets.UTF_8);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> store.write("s1", "MEMORY.md", "hacked"));
        assertTrue(e.getMessage().contains("index"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> store.read("s1", "MEMORY.md"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("s1", "MEMORY.md"));

        // 索引内容原封不动
        assertEquals("- [a](a.md)\n", Files.readString(index, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("文件不存在 → 拒绝（write 无 upsert，不会误建空文件）")
    void missingFileIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> store.write("s1", "not-exist.md", "x"));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertFalse(Files.exists(memDir.resolve("not-exist.md")));
    }

    // ── ② list ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list：排除 MEMORY.md，type 取自 frontmatter，name 缺失回落文件名")
    void listExcludesIndexAndReadsFrontmatter() throws IOException {
        writeEntry("a", "feedback", "内容 A");
        writeEntry("b", "project", "内容 B");
        Files.writeString(memDir.resolve("MEMORY.md"), "- [a](a.md)\n", StandardCharsets.UTF_8);
        // 无 frontmatter 的裸文件：type=null、name 回落文件名
        Files.writeString(memDir.resolve("bare.md"), "就是一段正文\n", StandardCharsets.UTF_8);

        List<MemoryEntrySummaryDto> list = store.list("s1");

        assertEquals(3, list.size(), "应排除 MEMORY.md，只剩 3 条");
        assertTrue(list.stream().noneMatch(x -> "MEMORY.md".equals(x.filename())));

        MemoryEntrySummaryDto a = list.stream()
            .filter(x -> "a.md".equals(x.filename())).findFirst().orElseThrow();
        assertEquals("feedback", a.type());
        assertEquals("a", a.name());
        assertTrue(a.sizeBytes() > 0);

        MemoryEntrySummaryDto bare = list.stream()
            .filter(x -> "bare.md".equals(x.filename())).findFirst().orElseThrow();
        assertNull(bare.type(), "无 frontmatter → type 为 null（前端归「未分类」）");
        assertEquals("bare.md", bare.name());
    }

    // ── ③ 读 / 写 / 删 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("detail 返回正文 + frontmatter type；write 覆盖后能读回")
    void detailAndWriteRoundTrip() throws IOException {
        writeEntry("pref", "feedback", "原标题");
        MemoryEntryDetailDto d = store.detail("s1", "pref.md");
        assertEquals("feedback", d.type());
        assertEquals("pref.md", d.filename());
        assertTrue(d.content().contains("原标题"));
        assertTrue(d.sizeBytes() > 0);

        String updated = "---\nname: pref\ntype: feedback\ndescription: 改过\n---\n\n新正文\n";
        store.write("s1", "pref.md", updated);
        assertEquals(updated, store.read("s1", "pref.md"));
        assertEquals(updated, Files.readString(memDir.resolve("pref.md"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("write 只动目标条目，MEMORY.md 逐字节不变（索引由模型维护）")
    void writeNeverTouchesIndex() throws IOException {
        writeEntry("x", "reference", "原内容");
        Path index = memDir.resolve("MEMORY.md");
        String indexBefore = "- [x](x.md) — 原索引行\n";
        Files.writeString(index, indexBefore, StandardCharsets.UTF_8);

        store.write("s1", "x.md", "---\ntype: reference\n---\n\n改过了\n");

        assertEquals(indexBefore, Files.readString(index, StandardCharsets.UTF_8),
            "⛔ 程序永不写回 MEMORY.md");
    }

    @Test
    @DisplayName("delete 删掉条目文件，但 MEMORY.md 里那一行仍在（残留索引由用户处理）")
    void deleteKeepsIndexLine() throws IOException {
        writeEntry("gone", "project", "要被删的");
        Path index = memDir.resolve("MEMORY.md");
        Files.writeString(index, "- [gone](gone.md)\n", StandardCharsets.UTF_8);

        store.delete("s1", "gone.md");

        assertFalse(Files.exists(memDir.resolve("gone.md")));
        assertTrue(Files.exists(index), "索引文件本身不能被带走");
        assertTrue(Files.readString(index, StandardCharsets.UTF_8).contains("gone.md"),
            "残留索引行必须保留 —— 删它属于模型/后续 AI 修剪的职责");
        assertTrue(store.list("s1").stream().noneMatch(x -> "gone.md".equals(x.filename())));
    }

    @Test
    @DisplayName("写空串 = 清空正文（不删除文件）")
    void writeEmptyClearsContent() throws IOException {
        writeEntry("blank", "user", "有内容");
        store.write("s1", "blank.md", "");
        assertEquals("", store.read("s1", "blank.md"));
        assertTrue(Files.exists(memDir.resolve("blank.md")));
    }

    @Test
    @DisplayName("无 SessionStorage 绑定 → list 空集、读写抛 400（不 NPE）")
    void withoutStorageFailsSafely() {
        MemoryEntryStore bare = new MemoryEntryStore();
        assertTrue(bare.list("s1").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> bare.read("s1", "a.md"));
    }
}

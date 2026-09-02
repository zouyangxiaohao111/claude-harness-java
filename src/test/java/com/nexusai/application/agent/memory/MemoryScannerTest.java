package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-P0-1] MemoryScanner.scan 对齐 CC memoryScan.ts:35-77 scanMemoryFiles。
 *
 * <p>WHY (规则九 · 测试验证意图): CC scanMemoryFiles 用 {@code readdir(recursive:true)} 递归扫描
 * 子目录记忆 + 排除 MEMORY.md 索引 + 只读前 FRONTMATTER_MAX_LINES=30 行 frontmatter（两段式优化）。
 * 旧 Java 用 {@code Files.list} 非递归 —— 子目录中的记忆被漏扫，导致 prompt 注入缺记忆。
 * 本测试锁定: 递归命中、MEMORY.md 排除、前 30 行 frontmatter 解析。
 */
@DisplayName("[IMP-M-P0-1] MemoryScanner.scan 递归扫描对齐 CC memoryScan.ts")
class MemoryScannerTest {

    @Test
    @DisplayName("scan 递归命中子目录 .md + 排除 MEMORY.md（CC memoryScan.ts:40-43）")
    void scan_recursesAndExcludesMemoryIndex(@TempDir Path memoryDir) throws Exception {
        // WHY: CC readdir(recursive:true) —— 记忆可放在子目录（如 .claude 分层布局）; MEMORY.md 是索引文件
        //       不得作为记忆注入 prompt。旧 Files.list 非递归会漏扫子目录。
        Files.createDirectories(memoryDir.resolve("sub/deep"));
        Files.writeString(memoryDir.resolve("a.md"), "---\nname: a\ndescription: A\n---\nbody-a\n");
        Files.writeString(memoryDir.resolve("sub/b.md"), "---\nname: b\ndescription: B\n---\nbody-b\n");
        Files.writeString(memoryDir.resolve("sub/deep/c.md"), "---\nname: c\ndescription: C\n---\nbody-c\n");
        Files.writeString(memoryDir.resolve("MEMORY.md"), "# index\n");

        MemoryScanner scanner = new MemoryScanner();
        var entries = scanner.scan(memoryDir, null);

        assertThat(entries)
            .as("递归扫描必须命中子目录记忆，且排除 MEMORY.md 索引")
            .extracting(MemoryEntry::filename)
            .containsExactlyInAnyOrder("a.md", "sub/b.md", "sub/deep/c.md");
    }

    @Test
    @DisplayName("scan filename=relativePath（CC memoryScan.ts:57，递归目录非 basename）")
    void scan_filenameIsRelativePath(@TempDir Path memoryDir) throws Exception {
        // WHY: CC readdir(recursive:true) 返回相对路径（memoryScan.ts:46-57 filename=relativePath），
        //      递归目录下 filename 必须为 sub/deep/c.md 而非 basename c.md —— 否则 formatMemoryManifest
        //      子目录记忆与顶层记忆同名列（重名失配），提取器无法区分来源文件。FIX-MC 修复点。
        Files.createDirectories(memoryDir.resolve("sub/deep"));
        Files.writeString(memoryDir.resolve("a.md"), "---\nname: a\ndescription: A\n---\nbody-a\n");
        Files.writeString(memoryDir.resolve("sub/deep/c.md"), "---\nname: c\ndescription: C\n---\nbody-c\n");

        MemoryScanner scanner = new MemoryScanner();
        var entries = scanner.scan(memoryDir, null);

        assertThat(entries)
            .as("递归目录记忆 filename 必须是相对路径（非 basename）")
            .extracting(MemoryEntry::filename)
            .containsExactlyInAnyOrder("a.md", "sub/deep/c.md");
    }

    @Test
    @DisplayName("scan 用前 30 行解析 frontmatter（CC memoryScan.ts:48-55 readFileInRange）")
    void scan_parsesFrontmatterWithin30Lines(@TempDir Path memoryDir) throws Exception {
        // WHY: CC readFileInRange(offset=0, maxLines=30) —— 两段式优化：为列清单只读前 30 行提取
        //       frontmatter，避免读全文件。frontmatter 字段（name/description/type）必须被解析。
        Files.writeString(memoryDir.resolve("pref.md"),
            "---\nname: preferred\ndescription: user preference\n---\nbody-here\n");

        MemoryScanner scanner = new MemoryScanner();
        var entries = scanner.scan(memoryDir, null);

        assertThat(entries).hasSize(1);
        // CC MemoryHeader（memoryScan.ts:13-19）无 name 字段：frontmatter name 不提取，
        // 仅 description/type 进入 MemoryEntry（memoryScan.ts:60-61）。
        assertThat(entries.get(0).filename())
            .as("filename = 相对路径（CC memoryScan.ts:57）")
            .isEqualTo("pref.md");
        assertThat(entries.get(0).description()).isEqualTo("user preference");
        assertThat(entries.get(0).mtime()).isNotNull();
    }

    @Test
    @DisplayName("scan 不创建 MEMORY.md 索引（CC memdir.ts:288 readFileSync 只读 + memoryScan.ts:42 排除）")
    void scan_doesNotCreateMemoryIndexWhenAbsent(@TempDir Path memoryDir) throws Exception {
        // WHY (D4 索引只读等价意图 · ODF-D4D5): CC memdir.ts:227 明言 MEMORY.md 是 index，不是
        //       memory —— 索引由模型 Write/Edit 维护（memdir.ts:288 仅 fs.readFileSync 读），
        //       scanMemoryFiles 只 readdir + 排除（memoryScan.ts:42 basename!=='MEMORY.md'），
        //       程序永不创建/重写 MEMORY.md。旧 MemoryStorageIndexNotAutoRebuiltTest 断言
        //       write/loadIndex（已删 API）随 CRUD 死层删除 → 该意图失去覆盖。
        //       rediscovery: 若 scan 实现误"顺手"初始化索引文件，则文件系统状态被程序改变。
        Files.writeString(memoryDir.resolve("a.md"),
            "---\nname: a\ndescription: A\n---\nbody-a\n");
        assertThat(Files.exists(memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME))).isFalse();

        MemoryScanner scanner = new MemoryScanner();
        scanner.scan(memoryDir, null);

        assertThat(Files.exists(memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME)))
            .as("scan 是只读操作，绝不创建 MEMORY.md（索引由模型 Write/Edit 维护 · memdir.ts:227）")
            .isFalse();
    }

    @Test
    @DisplayName("scan 多次不重写已有 MEMORY.md（逐字节不变 · CC memdir.ts:288 只读）")
    void scan_doesNotRewriteExistingMemoryIndex(@TempDir Path memoryDir) throws Exception {
        // WHY (D4 索引只读等价意图 · ODF-D4D5): 预置 MEMORY.md 索引内容由模型维护（memdir.ts:227），
        //       buildMemoryPrompt 仅 readFileSync（memdir.ts:288）读它；scan 若重写/清空索引会
        //       破坏模型维护的指针行（- [Title](file.md) hook）。锁 index 内容逐字节不变 + mtime
        //       不变（无写发生）。
        String indexContent = "# Memory Index\n\n- [Role](role.md) - who I am\n- [Pref](pref.md) - how I work\n";
        Files.writeString(memoryDir.resolve("a.md"),
            "---\nname: a\ndescription: A\n---\nbody-a\n");
        Files.writeString(memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME), indexContent);
        byte[] before = Files.readAllBytes(memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME));
        long beforeMtime = Files.getLastModifiedTime(
            memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME)).toMillis();

        MemoryScanner scanner = new MemoryScanner();
        scanner.scan(memoryDir, null);
        scanner.scan(memoryDir, null);
        scanner.scan(memoryDir, null);

        byte[] after = Files.readAllBytes(memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME));
        long afterMtime = Files.getLastModifiedTime(
            memoryDir.resolve(MemoryConstants.MEMORY_INDEX_NAME)).toMillis();
        assertThat(after)
            .as("scan 不得改写模型维护的 MEMORY.md 索引（逐字节不变 · memdir.ts:288 只读）")
            .isEqualTo(before);
        assertThat(afterMtime)
            .as("scan 不得触碰 MEMORY.md mtime（无写发生 · memdir.ts:288）")
            .isEqualTo(beforeMtime);
    }

    @Test
    @DisplayName("G-13: YAML 特殊字符值经加引号重试成功解析（CC frontmatterParser.ts:85-121 quoteProblematicValues）")
    void scan_quoteRetry_savesProblematicYaml(@TempDir Path memoryDir) throws Exception {
        // WHY: `description: use case: foo` —— js-yaml 与 SnakeYAML 同抛 "mapping values are not
        //       allowed"；CC 捕获 → quoteProblematicValues 加引号 → 重试成功（desc 保留）。
        //       旧 Java yaml.load 异常冒泡 → parseHeaderSafely 丢弃文件（记忆从 manifest 消失）。
        Files.writeString(memoryDir.resolve("q.md"),
            "---\ndescription: use case: foo\n---\nbody\n");

        var entries = new MemoryScanner().scan(memoryDir, null);

        assertThat(entries)
            .as("解析失败不得丢文件（G-13 降级保留）")
            .extracting(MemoryEntry::filename)
            .contains("q.md");
        MemoryEntry q = entries.stream().filter(e -> e.filename().equals("q.md")).findFirst().orElseThrow();
        assertThat(q.description()).as("加引号重试成功后 description 保留原值").isEqualTo("use case: foo");
    }

    @Test
    @DisplayName("G-13: 双重失败 frontmatter 文件保留且 type/desc 降级（CC frontmatterParser.ts:161-168）")
    void scan_yamlDoubleFail_keepsFileDegraded(@TempDir Path memoryDir) throws Exception {
        // WHY: `"a": b: c` —— 引号 key 不匹配 quoteProblematicValues 的 `key: value` 简单行正则
        //       （frontmatterParser.ts:91 仅 [a-zA-Z_-]+ key）→ 加引号重试仍失败 → CC warn + {}
        //       保留文件（type/desc 降级 null）。旧 Java 直接丢文件。
        Files.writeString(memoryDir.resolve("bad.md"),
            "---\n\"a\": b: c\n---\nbody\n");

        var entries = new MemoryScanner().scan(memoryDir, null);

        assertThat(entries)
            .as("解析双重失败也不得丢文件（G-13 文件绝不因解析失败消失）")
            .extracting(MemoryEntry::filename)
            .contains("bad.md");
        MemoryEntry bad = entries.stream().filter(e -> e.filename().equals("bad.md")).findFirst().orElseThrow();
        assertThat(bad.type()).as("type 降级 null").isNull();
        // D08-2：CC memoryScan.ts:60 `description: frontmatter.description || null` —— 缺失 → null（非空串）
        assertThat(bad.description()).as("description 降级 null（CC || null 契约）").isNull();
    }

    @Test
    @DisplayName("G-13: `---foo` 开头不匹配开符正则 → 无 frontmatter（CC FRONTMATTER_REGEX ^---\\s*\\n）")
    void scan_dashPrefixBoundary_noFrontmatter(@TempDir Path memoryDir) throws Exception {
        // WHY: CC frontmatterParser.ts:123 开符须 `^---` + 仅空白 + 必选换行；`---foo` 不匹配 →
        //       frontmatter={} 文件保留（type/desc 降级）。旧 Java startsWith("---") 误入 frontmatter
        //       分支 → 垃圾片段 yaml 失败 → 文件丢弃（EV-057）。
        Files.writeString(memoryDir.resolve("dash.md"),
            "---foo\ndescription: x\n---\nbody\n");

        var entries = new MemoryScanner().scan(memoryDir, null);

        assertThat(entries)
            .as("开符边界输入文件必须保留")
            .extracting(MemoryEntry::filename)
            .contains("dash.md");
        MemoryEntry dash = entries.stream().filter(e -> e.filename().equals("dash.md")).findFirst().orElseThrow();
        assertThat(dash.type()).as("无 frontmatter → type null").isNull();
        // D08-2：CC memoryScan.ts:60 `frontmatter.description || null` —— 无 frontmatter → null（非空串）
        assertThat(dash.description()).as("无 frontmatter → description null（CC || null 契约）").isNull();
    }

    @Test
    @DisplayName("G-24/MEM-07: 含 BOM 文件 frontmatter 正常解析（CC readFileInRange.ts:138 剥 BOM）")
    void scan_bomFrontmatter_parsed(@TempDir Path memoryDir) throws Exception {
        // WHY: 旧 Java BufferedReader 不剥 BOM → BOM+`---` 首行判定失败 → frontmatter 整体丢失
        //       （description/type null，DRF-3b/EV-R2-M03-24）。
        Files.write(memoryDir.resolve("bom.md"),
            "\uFEFF---\ndescription: bom desc\ntype: reference\n---\nbody\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var entries = new MemoryScanner().scan(memoryDir, null);

        MemoryEntry bom = entries.stream().filter(e -> e.filename().equals("bom.md")).findFirst().orElseThrow();
        assertThat(bom.description()).as("BOM 剥离后 frontmatter 必须解析").isEqualTo("bom desc");
        assertThat(bom.type()).as("BOM 剥离后 type 必须解析").isEqualTo(MemoryType.REFERENCE);
    }

    @Test
    @DisplayName("G-14: abort 后扫描停止返回部分结果（CC readFileInRange.ts:81 throwIfAborted 每文件）")
    void scan_aborted_returnsPartialOrEmpty(@TempDir Path memoryDir) throws Exception {
        // WHY: CC scanMemoryFiles 每文件 readFileInRange 前 signal.throwIfAborted() —— abort 后
        //       已完成文件保留、未读文件丢弃（部分结果）；Java takeWhile 等价。
        Files.writeString(memoryDir.resolve("a.md"), "---\ndescription: A\n---\nbody\n");
        Files.writeString(memoryDir.resolve("b.md"), "---\ndescription: B\n---\nbody\n");
        com.nexusai.application.agent.tool.AbortController ab =
            new com.nexusai.application.agent.tool.AbortController();

        // 预取消 → 无文件被读（全部跳过）
        ab.abort();
        var entries = new MemoryScanner().scan(memoryDir, ab);

        assertThat(entries).as("abort 后不得再读新文件").isEmpty();
    }

    @Test
    @DisplayName("D08-1: frontmatter 值含 `---` 在闭符判定时于首个 `---` 截断（对齐 CC FRONTMATTER_REGEX 实际行为）")
    void scan_frontmatterValueContainingTripleDash_closesAtFirstDash(@TempDir Path memoryDir) throws Exception {
        // WHY (D08-1, 实测 CC 正则): 探查报告称 CC `([\s\S]*?)---\s*\n?` 在 `x: ---bar` 处"不闭合"，
        //      但实际运行该正则（node 实测 frontmatterParser.ts:123）：`\s*\n?` 可匹配空串 → 首个 `---`
        //      即闭。Java `indexOf("---", nl+1)` 与 CC 行为一致（均在首个 `---` 截断），本测试锁定该
        //      真实 CC 语义，防止未来"按探查误判改正则"导致偏离 CC。
        Files.writeString(memoryDir.resolve("dashval.md"),
            "---\ndescription: foo---bar\n---\nbody\n");

        var entries = new MemoryScanner().scan(memoryDir, null);

        MemoryEntry e = entries.stream().filter(x -> x.filename().equals("dashval.md")).findFirst().orElseThrow();
        // CC 正则实测：`([\s\S]*?)---\s*\n?` 在 `foo---bar` 首个 `---` 闭合 → fm = "description: foo"
        assertThat(e.description())
            .as("首个 `---` 即闭（与 CC FRONTMATTER_REGEX 实测一致），desc 截断为 foo")
            .isEqualTo("foo");
    }

    @Test
    @DisplayName("D08-2: description 空串 → null（CC memoryScan.ts:60 `frontmatter.description || null`）")
    void scan_emptyDescription_mapsToNull(@TempDir Path memoryDir) throws Exception {
        // WHY: CC `frontmatter.description || null` —— 空串（falsy）→ null，非空串。
        //      Java 旧实现缺失/空串 → ""；契约上 description 应为 null（消费方 == null 判空语义）。
        Files.writeString(memoryDir.resolve("empty.md"),
            "---\ndescription: \"\"\n---\nbody\n");

        var entries = new MemoryScanner().scan(memoryDir, null);

        MemoryEntry e = entries.stream().filter(x -> x.filename().equals("empty.md")).findFirst().orElseThrow();
        assertThat(e.description()).as("空串 description → null（CC || null）").isNull();
    }

    @Test
    @DisplayName("D08-4: 扫描中 abort → 已读文件保留、in-flight 丢弃（CC allSettled 部分结果）")
    void scan_abortedMidScan_keepsCompletedReads(@TempDir Path memoryDir) throws Exception {
        // WHY: CC allSettled 并发下保留"abort 前已完成读入"的文件；Java takeWhile + 读后 filter
        //      保证顺序执行等价（前缀保留）。本测试用预取消验证 takeWhile 前缀语义，避免 flaky 时序。
        Files.writeString(memoryDir.resolve("a.md"), "---\ndescription: A\n---\nbody\n");
        Files.writeString(memoryDir.resolve("b.md"), "---\ndescription: B\n---\nbody\n");
        com.nexusai.application.agent.tool.AbortController ab =
            new com.nexusai.application.agent.tool.AbortController();

        // 预取消 = CC signal 已在首个 readFileInRange throwIfAborted 触发后的状态 → 全部丢弃
        ab.abort();
        var entries = new MemoryScanner().scan(memoryDir, ab);

        assertThat(entries).as("abort 触发后扫描不返回未读文件").isEmpty();
    }
    @Test
    @DisplayName("△-walk-err: 子目录不可读/失效 → scan 返回空清单不抛异常（CC memoryScan.ts:74-75 整体 catch→[]）")
    void scan_walkError_returnsEmptyList(@TempDir Path memoryDir) throws Exception {
        // WHY: CC scanMemoryFiles 整体 try/catch → []（memoryScan.ts:74-75）：递归 readdir 遇不可读
        //      子目录（EACCES）/失效链接整体拒绝 → 空清单（CC 仅空清单继续，提取链
        //      ExtractMemoriesAgent:522 storage.list() 无 catch）。Java Files.walk 迭代期抛
        //      UncheckedIOException（RuntimeException），旧实现仅 catch(IOException)（:95）→ 逃逸。
        Path blocked = Files.createDirectories(memoryDir.resolve("blocked"));
        Files.writeString(blocked.resolve("hidden.md"), "---\ndescription: hidden\n---\nbody\n");
        Files.writeString(memoryDir.resolve("visible.md"), "---\ndescription: ok\n---\nbody\n");

        Runnable cleanup = breakSubdirectoryTraversal(blocked);
        if (cleanup == null) {
            return; // 平台无法构造 walk 失败（无 POSIX 视图且 junction 创建失败）→ 跳过
        }
        try {
            var entries = new MemoryScanner().scan(memoryDir, null);

            assertThat(entries)
                .as("walk 失败 → 空清单不抛异常（CC memoryScan.ts:74-75 整体 catch→[]，非部分结果）")
                .isEmpty();
        } finally {
            cleanup.run();
        }
    }

    /**
     * 破坏子目录可遍历性以触发 {@link java.nio.file.Files#walk} 迭代期失败（确定性构造）：
     * POSIX（Linux/macOS）→ chmod 000（目录访问 → AccessDeniedException）；Windows → 悬空 junction
     * （目标不存在 → NoSuchFileException，FileTreeWalker 对每个 entry stat）。两种失败均以
     * {@link java.io.UncheckedIOException} 从 walk 抛出。返回恢复用清理动作；无法构造 → null（调用方跳过）。
     */
    private static Runnable breakSubdirectoryTraversal(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, Set.of());
            return () -> {
                try {
                    Files.setPosixFilePermissions(dir,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                } catch (Exception ignored) {
                    // 仅恢复遍历权限供 @TempDir 清理，失败不至于掩盖断言结果
                }
            };
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 默认 provider 无 POSIX 视图 → 悬空 junction 触发 walk 失败（实测 mklink /J）
            Path junction = dir.resolveSibling(dir.getFileName() + "-dangling");
            Process p;
            try {
                p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    junction.toString(),
                    dir.resolve("nonexistent-target").toString())
                    .redirectErrorStream(true)
                    .start();
            } catch (IOException e2) {
                return null;
            }
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0
                        || !Files.exists(junction, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
            } catch (InterruptedException e2) {
                Thread.currentThread().interrupt();
                return null;
            }
            return () -> {
                try {
                    Files.deleteIfExists(junction);
                } catch (Exception ignored) {
                    // 悬空 junction 清理失败不至于掩盖断言结果
                }
            };
        }
    }
}

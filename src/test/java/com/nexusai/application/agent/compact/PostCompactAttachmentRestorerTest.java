package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-04 · 压缩后附件恢复单测 · 对齐 CC compact.ts:1415-1464（INV-15）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: REQ-04 要求压缩后恢复最近读取文件，
 * INV-15 附件预算不变量——文件数上限 5（compact.ts:122）、总预算 50K（:123）、
 * 单文件 5K（:124）、skill 单技能 5K / 总预算 25K（:129-130）。本测试验证预算裁剪
 * 与 preserved 尾部去重（CC collectReadToolFilePaths，compact.ts:1610-1655）。
 *
 * <p>[FINDING-1 返工] G-73（X-23）六值 MEMORY_TYPE_VALUES 路径集合排除落在<b>生产载体</b>
 * {@code restoreFileAttachments}（CompactConversation.java:276 / PartialCompactConversation.java:311
 * 生产可达）——旧实现仅存在于死路径 PostCompactFileRestore（已删除，双轨收敛）。
 */
class PostCompactAttachmentRestorerTest {

    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        AutoMemPaths.resetCurrentProjectRoot();
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    private static Map<String, CompactConversation.ReadFileState> state(String... pathAndTs) {
        Map<String, CompactConversation.ReadFileState> m = new LinkedHashMap<>();
        for (int i = 0; i < pathAndTs.length; i += 2) {
            String path = pathAndTs[i];
            long ts = Long.parseLong(pathAndTs[i + 1]);
            m.put(path, new CompactConversation.ReadFileState("content-of-" + path, ts));
        }
        return m;
    }

    // ════════════════════════════════════════════════════════════════════
    // G-73（X-23）：MEMORY_TYPE_VALUES 六值路径集合排除（生产载体 restoreFileAttachments，
    // 对齐 CC services/compact/compact.ts:1693-1697 shouldExcludeFromPostCompactRestore）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-73：六值类型路径集合精确匹配排除（生产载体 restoreFileAttachments，compact.ts:1693-1697）")
    void memory_type_path_set_excluded() throws Exception {
        // WHY: CC 排除项 = MEMORY_TYPE_VALUES.map(type => expandPath(getMemoryPath(type))) 路径集合，
        //   normalizedFilename 精确命中才排除。六值路径必须全部命中；普通文件保留。
        //   [FINDING-1 返工] 生产载体 PostCompactAttachmentRestorer.restoreFileAttachments
        //   （CompactConversation.java:276 / PartialCompactConversation.java:311 生产可达）——
        //   旧实现无排除（G-73 仅落在死路径 PostCompactFileRestore 上）→ RED。
        Path configHome = tempDir.resolve("config");
        Path managed = tempDir.resolve("managed");
        Path sessionRoot = tempDir.resolve("proj");
        Files.createDirectories(configHome);
        Files.createDirectories(managed);
        String sep = java.io.File.separator;
        try {
            ClaudePaths.setConfigDirOverride(configHome.toString());
            ClaudePaths.setManagedFilePathOverride(managed.toString());
            // G5：memoryPathsForPostCompactRestore 亦含 nexusai 自有根 → 唯一 appName 隔离（防读真实 ~/.nexusai）
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());

            AutoMemPaths autoMemPaths = AutoMemPaths.defaultInstance();
            String autoMemEntrypoint = autoMemPaths.getAutoMemEntrypoint();
            String user = configHome + sep + "CLAUDE.md";
            String project = sessionRoot + sep + "CLAUDE.md";
            String local = sessionRoot + sep + "CLAUDE.local.md";
            String managedPath = managed + sep + "CLAUDE.md";
            String normalFile = sessionRoot + sep + "src" + sep + "App.java";

            List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
                state(user, "1", project, "1", local, "1", managedPath, "1",
                    autoMemEntrypoint, "1", normalFile, "1"),
                10, Set.of(), sessionRoot.toString());

            // 五值 memory 路径（User/Project/Local/Managed/AutoMem）全部排除；普通文件保留。
            // TeamMem 为 CC feature('TEAMMEM') 门控（memory/types.ts:3-10），Java 默认关 → N/A 不测
            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).content()).contains(normalFile);
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            ClaudePaths.setConfigDirOverride(null);
            ClaudePaths.setManagedFilePathOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    @Test
    @DisplayName("G-73：claude*.md 文件名匹配移除——非六值路径的 claude*.md 不得排除（生产载体，旧 Java △-4 误排除）")
    void non_memory_claude_md_files_not_excluded() throws Exception {
        // WHY: 旧 Java 按 basename claude*.md（不区分大小写）匹配 → docs/claude-notes.md 与
        //   notes/CLAUDE.md（basename 命中但非六值路径）被误排除。CC 路径集合精确匹配 → 保留。
        Path configHome = tempDir.resolve("config");
        Path sessionRoot = tempDir.resolve("proj");
        Files.createDirectories(configHome);
        try {
            ClaudePaths.setConfigDirOverride(configHome.toString());
            // G5：memoryPathsForPostCompactRestore 亦含 nexusai 自有根 → 唯一 appName 隔离（防读真实 ~/.nexusai）
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());
            String sep = java.io.File.separator;
            String subdirClaudeMd = sessionRoot + sep + "notes" + sep + "CLAUDE.md";
            String claudeNotes = sessionRoot + sep + "docs" + sep + "claude-notes.md";
            String claudeLocalNotes = sessionRoot + sep + "docs" + sep + "claude.local-notes.md";

            List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
                state(subdirClaudeMd, "1", claudeNotes, "1", claudeLocalNotes, "1"), 10, Set.of());

            assertThat(restored).hasSize(3);
            assertThat(restored.get(0).content()).contains(subdirClaudeMd);
            assertThat(restored.get(1).content()).contains(claudeNotes);
            assertThat(restored.get(2).content()).contains(claudeLocalNotes);
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    @Test
    @DisplayName("G-73：cwd=sessionRoot 外同形路径不排除（如其他项目的 CLAUDE.md 绝对路径，生产载体）")
    void other_cwd_claude_md_not_excluded() throws Exception {
        // WHY: 路径集合按 per-session cwd（CC getOriginalCwd → Java currentSessionProjectRoot，
        //   ODF-A1）计算；其他项目根的 CLAUDE.md 不在本会话集合内 → 保留。
        Path configHome = tempDir.resolve("config");
        Path sessionRoot = tempDir.resolve("proj");
        Path otherProject = tempDir.resolve("other-proj");
        Files.createDirectories(configHome);
        try {
            ClaudePaths.setConfigDirOverride(configHome.toString());
            // G5：memoryPathsForPostCompactRestore 亦含 nexusai 自有根 → 唯一 appName 隔离（防读真实 ~/.nexusai）
            NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
            AutoMemPaths.setCurrentProjectRoot(sessionRoot.toString());
            String otherClaudeMd = otherProject + java.io.File.separator + "CLAUDE.md";
            List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
                state(otherClaudeMd, "1"), 10, Set.of());
            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).content()).contains(otherClaudeMd);
        } finally {
            AutoMemPaths.resetCurrentProjectRoot();
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · 附件恢复集成（INV-15）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("文件恢复: 按 recency 排序取最近 5 个（POST_COMPACT_MAX_FILES_TO_RESTORE=5）")
    void restoresMostRecentFilesUpToMax() {
        Map<String, CompactConversation.ReadFileState> state = state(
            "f1.txt", "1", "f2.txt", "2", "f3.txt", "3", "f4.txt", "4",
            "f5.txt", "5", "f6.txt", "6", "f7.txt", "7");
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of());
        // 最近 5 个：f7..f3
        assertThat(restored).hasSize(5);
        assertThat(restored.get(0).content()).contains("f7.txt");
        assertThat(restored.get(4).content()).contains("f3.txt");
    }

    @Test
    @DisplayName("[R1 A-03] 单文件超限: content token 数 > 5K → compact_file_reference 引用（不再截断）")
    void perFileTokenCap() {
        // WHY: CC generateFileAttachment compact 模式内容超过 per-file 上限（POST_COMPACT_MAX_TOKENS_PER_FILE）
        //   时由 FileReadTool.call 抛 MaxFileReadTokenExceededError → readTruncatedFile() 返回
        //   {type:'compact_file_reference'}（attachments.ts:3134-3140），Java 不再截断内容注入完整载荷，
        //   而是发轻量引用（渲染层 messages.ts:3592-3598 "too large to include" 注文案）。
        String huge = "x".repeat(40_000); // rough tokens ≈ 10_000 > 5_000
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put("big.txt", new CompactConversation.ReadFileState(huge, 1));
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of());
        assertThat(restored).hasSize(1);
        // subtype = compact_file_reference（轻量引用），非 "file" 完整内容
        assertThat(restored.get(0).subtype()).isEqualTo(PostCompactAttachmentRestorer.COMPACT_FILE_REFERENCE_SUBTYPE);
        // 载荷为引用注文案（含 "too large to include"），不含截断的文件体
        assertThat(restored.get(0).content()).contains("too large to include");
        assertThat(restored.get(0).content()).doesNotContain("File: big.txt");
    }

    @Test
    @DisplayName("总预算: 超过 50K 时停止追加（POST_COMPACT_TOKEN_BUDGET=50_000）")
    void totalTokenBudget() {
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) {
            state.put("f" + i + ".txt", new CompactConversation.ReadFileState("y".repeat(10_000), 100 - i));
        }
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 25, Set.of());
        int total = restored.stream().mapToInt(m -> CompactConversation.roughTokenCountEstimation(m.content())).sum();
        // 每个文件约 2500 tokens；19 个 = 47500 < 50K，第 20 个超预算跳过
        assertThat(total).isLessThanOrEqualTo(50_000);
        assertThat(restored.size()).isEqualTo(19);
    }

    @Test
    @DisplayName("preserved 尾部去重: 已可见 Read 路径跳过重注入（CC collectReadToolFilePaths）")
    void skipsPreservedReadPaths() {
        Map<String, CompactConversation.ReadFileState> state = state("already-read.txt", "2", "fresh.txt", "1");
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of("already-read.txt"));
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("fresh.txt");
    }

    // ════════════════════════════════════════════════════════════════════
    // [FIX-C1 拍板#6] shouldExcludeFromPostCompactRestore（compact.ts:1674-1705）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("压缩排除: Project/Local memory 文件（CLAUDE.md / CLAUDE.local.md）跳过恢复（CC compact.ts:1689-1702）")
    void excludesProjectAndLocalMemoryFilesFromRestore() throws Exception {
        java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("ws-fixc1");
        try {
            String projectMd = ws.resolve("CLAUDE.md").toString();
            String localMd = ws.resolve("CLAUDE.local.md").toString();
            String normalFile = ws.resolve("src/App.java").toString();
            Map<String, CompactConversation.ReadFileState> state = state(
                projectMd, "3", localMd, "2", normalFile, "1");
            List<ChatMessageDto> restored =
                PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of(), ws.toString());
            // Project/Local memory 文件排除；普通文件保留
            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).content()).contains("App.java");
        } finally {
            // 递归删除临时目录
            deleteRecursively(ws);
        }
    }

    @Test
    @DisplayName("shouldExcludeFromPostCompactRestore: memory 路径精确匹配排除，普通路径不排除")
    void shouldExcludeMemoryPathsOnly() throws Exception {
        java.nio.file.Path ws = java.nio.file.Files.createTempDirectory("ws-fixc1b");
        try {
            Set<String> memoryPaths = PostCompactAttachmentRestorer.memoryPathsForPostCompactRestore(ws.toString());
            String projectMd = ws.resolve("CLAUDE.md").toString();
            String localMd = ws.resolve("CLAUDE.local.md").toString();
            String normal = ws.resolve("notes.txt").toString();
            assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(projectMd, memoryPaths, null)).isTrue();
            assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(localMd, memoryPaths, null)).isTrue();
            assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(normal, memoryPaths, null)).isFalse();
            assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(null, memoryPaths, null)).isFalse();
        } finally {
            deleteRecursively(ws);
        }
    }

    @Test
    @DisplayName("NEW-GAP-3: content==null/blank 的 readFileState 条目被过滤（不产出空壳附件）")
    void filtersEmptyContentAttachments() {
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put("null-content.txt", new CompactConversation.ReadFileState(null, 3));
        state.put("blank-content.txt", new CompactConversation.ReadFileState("   ", 2));
        state.put("real.txt", new CompactConversation.ReadFileState("real content", 1));
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of());
        // WHY: CC generateFileAttachment 读失败返回 null 被 results.filter(result !== null) 过滤
        // （compact.ts:1461-1464），空内容条目不得注入 "File: path\n\n" 空壳附件污染压缩后消息。
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("real.txt");
        assertThat(restored).noneMatch(m -> m.content().contains("null-content.txt"));
        assertThat(restored).noneMatch(m -> m.content().contains("blank-content.txt"));
    }

    @Test
    @DisplayName("plan 排除: shouldExcludeFromPostCompactRestore 对 plan 文件路径精确匹配排除（CC compact.ts:1680-1687）")
    void excludesPlanFilePath() {
        java.nio.file.Path plans = java.nio.file.Path.of("plans-dir");
        String planPath = plans.resolve("session-123.md").toString();
        String normal = plans.resolve("notes.txt").toString();
        assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(planPath, Set.of(), planPath)).isTrue();
        assertThat(PostCompactAttachmentRestorer.shouldExcludeFromPostCompactRestore(normal, Set.of(), planPath)).isFalse();
    }

    @Test
    @DisplayName("plan 排除: restoreFileAttachments 传入 planFilePath 时 plan 文件不恢复")
    void excludesPlanFileFromRestore() {
        java.nio.file.Path plans = java.nio.file.Path.of("plans-dir");
        String planPath = plans.resolve("session-123.md").toString();
        String normal = plans.resolve("notes.txt").toString();
        Map<String, CompactConversation.ReadFileState> state = state(
            planPath, "2", normal, "1");
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restoreFileAttachments(state, 5, Set.of(), null, planPath);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("notes.txt");
        assertThat(restored).noneMatch(m -> m.content().contains("session-123.md"));
    }

    /** 递归删除临时目录（测试清理）。 */
    private static void deleteRecursively(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException e) {
                    // 清理失败不阻断断言
                }
            });
        } catch (java.io.IOException e) {
            // 清理失败不阻断断言
        }
    }

    @Test
    @DisplayName("skill 附件: 单技能截断 5K + 总预算 25K（POST_COMPACT_MAX_TOKENS_PER_SKILL/SKILLS_TOKEN_BUDGET）")
    void skillAttachmentBudget() {
        List<PostCompactAttachmentRestorer.SkillInfo> skills = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            skills.add(new PostCompactAttachmentRestorer.SkillInfo(
                "skill-" + i, "/skills/" + i, "s".repeat(30_000), 10 - i));
        }
        ChatMessageDto attachment = PostCompactAttachmentRestorer.skillAttachment(skills);
        assertThat(attachment).isNotNull();
        // 载荷含 invoked_skills + 截断标记（JSON 转义后 \n → \\n，按文本片段判别）
        assertThat(attachment.content()).contains("invoked_skills");
        assertThat(attachment.content()).contains("skill content truncated for compaction");
    }

    @Test
    @DisplayName("collectReadToolFilePaths: 扫描 assistant tool_use Read 的 file_path")
    void collectReadToolPaths() {
        com.fasterxml.jackson.databind.node.ObjectNode toolUse =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "Read");
        toolUse.set("input", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("file_path", "/abs/source.java"));
        ChatMessageDto assistant = new ChatMessageDto("a", null, Role.assistant, "assistant",
            null, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(toolUse), List.of(), null, false, false);

        Set<String> paths = PostCompactAttachmentRestorer.collectReadToolFilePaths(List.of(assistant));
        assertThat(paths).containsExactly("/abs/source.java");
    }

    @Test
    @DisplayName("[R2 A-04] FILE_UNCHANGED_STUB: dedup stub 的 Read 不计入 preserved（压缩后附件正确恢复）")
    void collectReadToolPathsSkipsStub() {
        // WHY: CC collectReadToolFilePaths 先扫 tool_result 的 FILE_UNCHANGED_STUB
        //   （compact.ts:1613-1621 收集 stubIds）再跳过对应 tool_use（:1624-1628 stubIds.has(block.id)）。
        //   stub 的 tool_result 指向更早完整 Read —— 该完整 Read 可能已被压缩掉；计入 preserved
        //   会跳过恢复 → 压缩后模型缺真实文件内容（探查 ✗-R2 实害）。故 stub Read 排除 → 重注入。
        String stubId = "toolu_stub_read_1";
        com.fasterxml.jackson.databind.node.ObjectNode stubRead =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        stubRead.put("type", "tool_use");
        stubRead.put("name", "Read");
        stubRead.put("id", stubId);
        stubRead.set("input", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("file_path", "/abs/stub-read.java"));
        ChatMessageDto assistant = new ChatMessageDto("a", null, Role.assistant, "assistant",
            null, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(stubRead), List.of(), null, false, false);

        // 普通 Read（无 stub）应保留
        com.fasterxml.jackson.databind.node.ObjectNode normalRead =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        normalRead.put("type", "tool_use");
        normalRead.put("name", "Read");
        normalRead.put("id", "toolu_normal_read_2");
        normalRead.set("input", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("file_path", "/abs/normal-read.java"));
        ChatMessageDto assistant2 = new ChatMessageDto("b", null, Role.assistant, "assistant",
            null, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(normalRead), List.of(), null, false, false);

        // stub 命中的 tool_result 载荷（Role.tool 消息，content()=FILE_UNCHANGED_STUB，toolCallId=stubId）
        ChatMessageDto stubResult = new ChatMessageDto("c", null, Role.tool, "tool",
            PostCompactAttachmentRestorer.FILE_UNCHANGED_STUB, null, List.of(), null, null, null, "刚刚",
            OffsetDateTime.now(), stubId, null, null, List.of(), List.of(), null, false, false);

        Set<String> paths = PostCompactAttachmentRestorer.collectReadToolFilePaths(
            List.of(assistant, assistant2, stubResult));
        // stub Read 被排除；普通 Read 保留
        assertThat(paths).containsExactly("/abs/normal-read.java");
    }

    @Test
    @DisplayName("[R2 A-04] stub Read 排除 → restoreFileAttachments 重注入真实内容")
    void stubReadReinjectedAfterCompact() {
        // WHY: stub 判别目的（CC collectReadToolFilePaths 注释）—— stub 指向更早完整 Read，
        //   压缩后须由 createPostCompactFileAttachments 重注入真实内容；若计入 preserved
        //   则跳过恢复，压缩后模型缺真实文件内容。端到端验证：stub Read 路径不在
        //   preservedReadPaths → restoreFileAttachments 重注入。
        Map<String, CompactConversation.ReadFileState> state = state("/abs/stub-read.txt", "1");
        String stubId = "toolu_stub_1";
        com.fasterxml.jackson.databind.node.ObjectNode stubRead =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        stubRead.put("type", "tool_use");
        stubRead.put("name", "Read");
        stubRead.put("id", stubId);
        stubRead.set("input", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            .put("file_path", "/abs/stub-read.txt"));
        ChatMessageDto assistant = new ChatMessageDto("a", null, Role.assistant, "assistant",
            null, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(stubRead), List.of(), null, false, false);
        ChatMessageDto stubResult = new ChatMessageDto("c", null, Role.tool, "tool",
            PostCompactAttachmentRestorer.FILE_UNCHANGED_STUB, null, List.of(), null, null, null, "刚刚",
            OffsetDateTime.now(), stubId, null, null, List.of(), List.of(), null, false, false);

        Set<String> preservedReadPaths = PostCompactAttachmentRestorer.collectReadToolFilePaths(
            List.of(assistant, stubResult));
        assertThat(preservedReadPaths).isEmpty();

        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, preservedReadPaths);
        // stub Read 不计入 preserved → stub-read.txt 被重注入真实内容
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("/abs/stub-read.txt");
    }

    @Test
    @DisplayName("附件消息载体: author='attachment' + subtype=附件类型")
    void attachmentMessageShape() {
        ChatMessageDto att = PostCompactAttachmentRestorer.buildAttachmentMessage("plan_file_reference", "/plan.md", "plan content");
        assertThat(att.author()).isEqualTo("attachment");
        assertThat(att.subtype()).isEqualTo("plan_file_reference");
        assertThat(att.content()).contains("/plan.md");
    }

    // ════════════════════════════════════════════════════════════════════
    // [R1 A-03] 附件重读: 重读磁盘 + 遥测 + deny + compact_file_reference
    // （修复面 generateFileAttachment · attachments.ts:3020-3199）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[R1 A-03] 重读磁盘: contentReader 提供最新内容替代快照（数据新鲜度）")
    void reReadsDiskForFreshContent() {
        // WHY: CC generateFileAttachment 经 FileReadTool.call 重读磁盘拿最新内容（attachments.ts:3177-3178），
        //   而非用压缩前 readFileState 快照 content（文件可能已更新 → stale content）。修复面正是此处。
        Map<String, CompactConversation.ReadFileState> state = state("/abs/fresh.txt", "1");
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), null, null,
            path -> "fresh-disk-content-" + path, // 重读磁盘返回最新内容
            null, null);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("fresh-disk-content-/abs/fresh.txt");
        // 快照 content（"content-of-..."）被重读替代，不再注入
        assertThat(restored.get(0).content()).doesNotContain("content-of-");
    }

    @Test
    @DisplayName("[R1 A-03] 重读失败: contentReader 返回 null → 跳过 + 遥测 error")
    void reReadFailureSkipsAndTelemetryError() {
        // WHY: CC generateFileAttachment 读失败（文件不存在/IO 错）→ 外层 catch →
        //   logEvent(errorEventName) → 返回 null 被过滤（attachments.ts:3195-3198）。空壳不注入。
        Telemetry telemetry = new Telemetry();
        Map<String, CompactConversation.ReadFileState> state = state("/abs/missing.txt", "1");
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), null, null,
            path -> null, // 重读失败
            null, telemetry);
        assertThat(restored).isEmpty();
        assertThat(telemetry.getCounter("tengu_post_compact_file_restore_error")).isEqualTo(1);
        assertThat(telemetry.getCounter("tengu_post_compact_file_restore_success")).isZero();
    }

    @Test
    @DisplayName("[R1 A-03] 重读成功: 遥测 success 事件发射")
    void reReadSuccessEmitsTelemetry() {
        // WHY: CC generateFileAttachment 成功 → logEvent(successEventName)（attachments.ts:3179）。
        //   遥测可观测附件重读是否真实发生（数据流日志）。
        Telemetry telemetry = new Telemetry();
        Map<String, CompactConversation.ReadFileState> state = state("/abs/ok.txt", "1");
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), null, null,
            path -> "content",
            null, telemetry);
        assertThat(restored).hasSize(1);
        assertThat(telemetry.getCounter("tengu_post_compact_file_restore_success")).isEqualTo(1);
        assertThat(telemetry.getCounter("tengu_post_compact_file_restore_error")).isZero();
    }

    @Test
    @DisplayName("[R1 A-03] deny 检查: 文件读被 deny 规则拒绝 → 跳过（CC isFileReadDenied）")
    void denyRuleSkipsFile() {
        // WHY: CC generateFileAttachment 先查 isFileReadDenied(filename, toolPermissionContext)
        //   （attachments.ts:3041）→ 命中 deny 返回 null 被过滤。Java 等价：RuleQuery 查 read-deny
        //   content rule（Read 工具 + file_path）。
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put("/abs/secret.txt", new CompactConversation.ReadFileState("secret-content", 2L));
        state.put("/abs/normal.txt", new CompactConversation.ReadFileState("normal-content", 1L));
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Read", "/abs/secret.txt"));
        Map<PermissionRuleSource, Set<PermissionRule>> denyRules =
            Map.of(PermissionRuleSource.SESSION, Set.of(deny));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), denyRules, Map.of(), Map.of());
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), null, null, null, permCtx, null);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).content()).contains("normal.txt");
        assertThat(restored).noneMatch(m -> m.content().contains("secret.txt"));
    }

    @Test
    @DisplayName("[R1 A-03] compact_file_reference: 内容 token 超 per-file 上限 → 轻量引用（不截断）")
    void oversizedContentEmitsCompactFileReference() {
        // WHY: CC readTruncatedFile compact 分支（attachments.ts:3134-3140）—— FileReadTool.call
        //   内容超 maxTokens 抛 MaxFileReadTokenExceededError → 返回 {type:'compact_file_reference',
        //   filename, displayPath}（轻量引用，不带内容）。Java 不再截断注入完整载荷。
        String huge = "x".repeat(40_000); // rough tokens ≈ 10_000 > 5_000
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put("big.txt", new CompactConversation.ReadFileState(huge, 1));
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), null, null,
            path -> huge, // 重读磁盘返回同样超大内容
            null, null);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).subtype())
            .isEqualTo(PostCompactAttachmentRestorer.COMPACT_FILE_REFERENCE_SUBTYPE);
        assertThat(restored.get(0).content()).contains("too large to include");
    }

    @Test
    @DisplayName("[prompt-align CTX-09] deferred_tools_delta 人类可读渲染：added/removed 两段 + system-reminder 包裹")
    void renderDeferredToolsDeltaBuildsHumanReadableTwoSections() {
        // WHY (规则九 · 测试验证意图): CTX-09 双表示策略——持久化 content 保持 JSON（scanAnnouncedDeltaNames
        //   跨 turn 重建源），LLM 注入用 renderDeferredToolsDelta 人类可读副本（对齐 CC messages.ts:4178-4195：
        //   added 段 "The following deferred tools are now available via ToolSearch:" + removed 段
        //   "The following deferred tools are no longer available..." + parts.join('\n\n') + system-reminder 包裹）。
        //   若渲染回归 JSON 直塞，模型读到 raw JSON payload（指令污染）——测试锚定人类可读 shape。
        String jsonPayload = "{\"type\":\"deferred_tools_delta\","
            + "\"addedNames\":[\"mcp_alpha\",\"mcp_beta\"],\"addedLines\":[\"mcp_alpha\",\"mcp_beta\"],"
            + "\"removedNames\":[\"mcp_old\"]}";
        String rendered = PostCompactAttachmentRestorer.renderDeferredToolsDelta(jsonPayload);
        assertThat(rendered).isNotNull();
        assertThat(rendered).startsWith("<system-reminder>\n");
        assertThat(rendered).endsWith("\n</system-reminder>");
        // 人类可读两段（非 raw JSON）
        assertThat(rendered).contains("The following deferred tools are now available via ToolSearch:\nmcp_alpha\nmcp_beta");
        assertThat(rendered).contains("The following deferred tools are no longer available (their MCP server disconnected). Do not search for them — ToolSearch will return no match:\nmcp_old");
        assertThat(rendered).contains("\n\n"); // parts.join('\n\n')
        // 不直塞 JSON payload 键（模型不可见 raw JSON）
        assertThat(rendered).doesNotContain("\"addedNames\"");

        // 两段均空 / 解析失败 → null（防御，不注入空壳）
        assertThat(PostCompactAttachmentRestorer.renderDeferredToolsDelta("{\"addedNames\":[],\"removedNames\":[]}"))
            .isNull();
        assertThat(PostCompactAttachmentRestorer.renderDeferredToolsDelta("not-json")).isNull();
        assertThat(PostCompactAttachmentRestorer.renderDeferredToolsDelta(null)).isNull();
    }
}

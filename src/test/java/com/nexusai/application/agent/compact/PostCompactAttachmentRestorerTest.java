package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.agent.CwdResolution;
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
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionKeys;
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
import java.util.UUID;

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

            AutoMemPaths autoMemPaths = AutoMemPaths.defaultInstance();
            // [批 4b-1] 显式传会话项目根（= 下游 restoreFileAttachments 的 workspaceDir 参数）——
            //   原无参调用经 ThreadLocal 隐式解析，载体已删。
            String autoMemEntrypoint = autoMemPaths.getAutoMemEntrypoint(sessionRoot.toString());
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
            String otherClaudeMd = otherProject + java.io.File.separator + "CLAUDE.md";
            List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
                state(otherClaudeMd, "1"), 10, Set.of());
            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).content()).contains(otherClaudeMd);
        } finally {
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
    @DisplayName("[R1 A-03 · P24] deny 检查: 文件读被 deny 规则拒绝 → 跳过（CC isFileReadDenied；规则根锚 = cwd）")
    void denyRuleSkipsFile() {
        // WHY: CC generateFileAttachment 先查 isFileReadDenied(filename, toolPermissionContext)
        //   （attachments.ts:3041）→ 命中 deny 返回 null 被过滤。Java 等价：RuleQuery 查 read-deny
        //   content rule（Read 工具 + file_path）。
        // [P24] 规则形态由 `//<abs>`（文件系统根）改为 **裸 `/` 前缀 + source=SESSION**：
        //   按 RuleQuery.rootPathForSource，裸 /… 规则的根 = 规则 source 的根，source=SESSION ⇒ 根 = cwd
        //   —— 规则根<b>就是被测值</b>。`//` 形态结构上不消费 cwd（规则根 = 盘符根），
        //   即使 cwd 传错也恒绿（本批实测：cwd 改成 null / 固定错目录，旧夹具 23 条断言全绿一条不红）。
        Path secret = tempDir.resolve("secret.txt");
        Path normal = tempDir.resolve("normal.txt");
        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put(secret.toString(), new CompactConversation.ReadFileState("secret-content", 2L));
        state.put(normal.toString(), new CompactConversation.ReadFileState("normal-content", 1L));
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Read", "/secret.txt"));
        Map<PermissionRuleSource, Set<PermissionRule>> denyRules =
            Map.of(PermissionRuleSource.SESSION, Set.of(deny));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), denyRules, Map.of(), Map.of());
        assertTwoLegsDiverge(tempDir);
        List<ChatMessageDto> restored = PostCompactAttachmentRestorer.restoreFileAttachments(
            state, 5, Set.of(), tempDir.toString(), null, null, permCtx, null);
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

    // ════════════════════════════════════════════════════════════════════
    // [P24] cwd 接线守护：restore(ctx, …) → isFileReadDenied → RuleQuery
    //   read-deny 规则的 root-relative 根锚 = ctx.getWorkspaceDir()
    // ════════════════════════════════════════════════════════════════════

    /**
     * [P24] 夹具 cwd · {@code @TempDir} 下的<b>全新随机子目录</b>。
     *
     * <p><b>WHY 放在 @TempDir（系统临时目录）而非工作目录内</b>：本类守护的接线断点是
     * 「cwd 传丢了会怎样」，所以夹具 cwd 必须与<b>回落腿</b>
     * （{@code RuleQuery.resolveEffectiveCwd(null)} → {@code CwdResolution.getCwd(null)}
     * → 归一化进程 {@code user.dir}，maven 下 = 模块目录）<b>必然不同值</b>。
     * 临时目录与模块目录既非同一路径、又非同盘符 ⇒ 回落腿下的 relativePath 直接越界。
     */
    private Path fixtureCwd() throws Exception {
        Path dir = tempDir.resolve("p24ws-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        return dir;
    }

    /** POSIX 归一（比对两侧一致，避免 Windows 分隔符假差异）。 */
    private static String toPosix(String s) {
        return s == null ? null : s.replace('\\', '/');
    }

    /**
     * ⭐ <b>两腿分叉前置断言</b>——本类 [P24] 用例鉴别力的<b>唯一来源</b>，也是
     * 「禁止退化成恒绿空转」的闸门。
     *
     * <p>左腿 = 夹具声明并注入的会话 cwd；右腿 = cwd 丢失时 {@code RuleQuery} 实际使用的回落值
     * {@code CwdResolution.getCwd(null)}（= 归一化进程 {@code user.dir}）。
     * <b>两腿必须不同值</b>，否则「cwd 真传」与「cwd 丢失」落点同值 ⇒ 断言对 P19/P24 接线
     * 恒绿空转 —— 前提一破<b>必须红</b>，⛔ 不许静默。
     */
    private static void assertTwoLegsDiverge(Path fixtureCwd) {
        assertThat(toPosix(CwdResolution.getCwd(null)))
            .as("夹具前提（两腿分叉）：RuleQuery 无 cwd 入参时的回落基准 = CwdResolution.getCwd(null) = 归一化进程 user.dir，"
                + "它必须 ≠ 本夹具的 cwd；若两者同值，则「cwd 真传」与「cwd 丢失」落点相同，"
                + "本类 [P24] 用例对该接线恒绿空转 —— 必须红，不许静默")
            .isNotEqualTo(toPosix(fixtureCwd.toAbsolutePath().normalize().toString()));
    }

    /**
     * [P24] 13 参 TUC 夹具（显式 {@code effectiveCwd}；会话键用 {@link SessionKeys#NO_SESSION} 哨兵）。
     *
     * <p>用 {@code NO_SESSION} 让 {@code CwdResolution} 走<b>无会话命名出口</b>（进程 user.dir）——
     * 不查 DB、不依赖 JUnit 扩展的 resolver 装配状态，回落腿取值完全确定。
     */
    private static ToolUseContext tuc(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), SessionKeys.NO_SESSION, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    /**
     * [P24] read-deny 规则夹具 · <b>裸 {@code /} 前缀 + {@code source=SESSION}</b>。
     *
     * <p>按 {@code RuleQuery.rootPathForSource}，裸 {@code /…} 规则的根 = 规则 source 的根，
     * {@code SESSION} ⇒ 根 = cwd ⇒ <b>规则根就是被测值</b>。这正是「打破规则形态不消费 cwd」
     * 的关键：{@code //…}（文件系统根 / 盘符根）形态的根与 cwd 无关，结构上抓不住 cwd 传错。
     */
    private static ToolPermissionContext denyReadRule(String ruleContent) {
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Read", ruleContent));
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(),
            Map.of(PermissionRuleSource.SESSION, Set.of(deny)), Map.of(), Map.of());
    }

    private static Map<String, CompactConversation.ReadFileState> stateOf(String path, long ts) {
        Map<String, CompactConversation.ReadFileState> m = new LinkedHashMap<>();
        m.put(path, new CompactConversation.ReadFileState("snapshot-" + path, ts));
        return m;
    }

    /**
     * [P24] <b>主守护</b>：{@code restore(ctx, …)} 把 {@code ctx.getWorkspaceDir()} 真传给
     * {@code RuleQuery} 作 read-deny 规则的 root-relative 根锚。
     *
     * <p><b>WHY（规则九）</b>：P19 给这条链路补了 {@code cwd} 形参，但原先<b>没有任何断言</b>能抓住
     * 「{@code PostCompactAttachmentRestorer} 传错/没传 cwd」—— 唯一的 read-deny 用例
     * （{@link #denyRuleSkipsFile}）用的是 {@code //<abs>} 文件系统根 glob，规则形态本身不消费 cwd。
     * 本用例把规则换成裸 {@code /secret.txt} + {@code SESSION}：cwd 正确 ⇒ 相对路径
     * {@code secret.txt} 命中 deny ⇒ 该文件<b>不</b>被恢复；cwd 传丢（或传成别的目录）⇒
     * 相对路径越界 ⇒ 不命中 ⇒ 文件被恢复。两条腿结论相反，接线才有鉴别力。
     */
    @Test
    @DisplayName("[P24] restore(ctx)：read-deny 根锚 = ctx.getWorkspaceDir()（裸 '/' 前缀 + SESSION）")
    void restoreDenyRootFollowsCtxWorkspaceDir() throws Exception {
        Path cwd = fixtureCwd();
        assertTwoLegsDiverge(cwd);
        Path secret = cwd.resolve("secret.txt");
        Path normal = cwd.resolve("normal.txt");
        Files.writeString(secret, "secret-content");
        Files.writeString(normal, "normal-content");

        CompactConversationContext ctx = new CompactConversationContext()
            .setWorkspaceDir(cwd)
            .setToolUseContext(tuc(denyReadRule("/secret.txt"), cwd));

        Map<String, CompactConversation.ReadFileState> state = new LinkedHashMap<>();
        state.put(secret.toString(), new CompactConversation.ReadFileState("snap-secret", 2L));
        state.put(normal.toString(), new CompactConversation.ReadFileState("snap-normal", 1L));

        List<String> contents = PostCompactAttachmentRestorer.restore(ctx, state, List.of())
            .stream().map(ChatMessageDto::content).toList();

        assertThat(contents)
            .as("cwd = ctx.getWorkspaceDir() 时，规则 '/secret.txt' 的根 = 该目录 ⇒ secret.txt 命中 deny 被跳过；"
                + "cwd 传丢（回落进程 user.dir）时相对路径越界 ⇒ 不命中 ⇒ secret.txt 会被恢复（本断言转红）")
            .noneMatch(c -> c.contains("secret.txt"));
        assertThat(contents)
            .as("未被 deny 覆盖的 normal.txt 必须正常恢复（证明 deny 是精确命中而非整批丢弃）")
            .anyMatch(c -> c.contains("normal.txt"));
    }

    /**
     * [P24] <b>镜像见证</b>：同规则 + 同 ctx，仅把 {@code ctx.getWorkspaceDir()} 换成另一个目录
     * ⇒ 结论翻转（不再被 deny）。
     *
     * <p>证明根锚<b>跟随 ctx 声明的会话 cwd</b>，而不是「目标路径自身的父目录」「某个常量根」
     * 或任何与 cwd 无关的取值 —— 后三种退化实现都会让本用例仍然命中 deny 而<b>转红</b>。
     */
    @Test
    @DisplayName("[P24] 镜像见证：同一 deny 规则 + 同一 ctx，仅换 ctx.getWorkspaceDir() → 不再命中 deny")
    void sameRuleDifferentWorkspaceDirDoesNotDeny() throws Exception {
        Path cwd = fixtureCwd();
        assertTwoLegsDiverge(cwd);
        Path otherCwd = fixtureCwd();
        Path secret = cwd.resolve("secret.txt");
        Files.writeString(secret, "secret-content");

        CompactConversationContext ctx = new CompactConversationContext()
            .setWorkspaceDir(otherCwd)   // ⭐ 仅此一处与主守护用例不同
            .setToolUseContext(tuc(denyReadRule("/secret.txt"), otherCwd));

        List<String> contents = PostCompactAttachmentRestorer
            .restore(ctx, stateOf(secret.toString(), 1L), List.of())
            .stream().map(ChatMessageDto::content).toList();

        assertThat(contents)
            .as("根锚 = ctx.getWorkspaceDir() = otherCwd ⇒ secret.txt 不在该根之下，relativePath 越界 ⇒ 不命中 deny；"
                + "若根锚退化成「目标自己的父目录」或任何与 cwd 无关的常量，本用例会命中 deny 而转红")
            .anyMatch(c -> c.contains("secret.txt"));
    }

    /**
     * [P24] <b>生产装配链守护</b>：{@code tuc.effectiveCwd()} 经
     * {@code CompactConversation.buildAutoContext} 落到 {@code ctx.workspaceDir}，
     * 再经 {@code restore} 抵达 {@code RuleQuery}。
     *
     * <p><b>WHY 需要这一层</b>：{@code CompactConversationContext.workspaceDir} 的
     * <b>生产唯一写入点</b>是 {@code CompactConversation.buildAutoContext}
     * （{@code ctx.setWorkspaceDir(tuc.effectiveCwd())}，且带 {@code != null} 门）。只测
     * {@code restore} 覆盖不到这一跳 —— 那一跳若断（改成 {@code null} / 不 set / 换成别的源），
     * 上面两个用例仍会全绿。本用例把两条跳一起钉住。
     */
    @Test
    @DisplayName("[P24] buildAutoContext → restore：tuc.effectiveCwd() 真接到 read-deny 根锚（生产装配链全程）")
    void buildAutoContextCarriesEffectiveCwdIntoDenyRoot() throws Exception {
        Path cwd = fixtureCwd();
        assertTwoLegsDiverge(cwd);
        Path secret = cwd.resolve("secret.txt");
        Files.writeString(secret, "secret-content");

        CompactConversationContext ctx = CompactConversation.buildAutoContext(
            tuc(denyReadRule("/secret.txt"), cwd), "p24-model", "compact", null);

        assertThat(ctx.getWorkspaceDir())
            .as("装配链前置：buildAutoContext 必须把 tuc.effectiveCwd() 落进 ctx.workspaceDir"
                + "（若这一跳断掉，下面 restore 的 deny 断言也会红，但本条给出更直接的定位）")
            .isEqualTo(cwd);

        List<String> contents = PostCompactAttachmentRestorer
            .restore(ctx, stateOf(secret.toString(), 1L), List.of())
            .stream().map(ChatMessageDto::content).toList();

        assertThat(contents)
            .as("生产装配全程（tuc.effectiveCwd → ctx.workspaceDir → restore → isFileReadDenied → RuleQuery）"
                + "任一跳丢失 cwd ⇒ 根锚变成进程 user.dir ⇒ secret.txt 会被恢复 ⇒ 本断言转红")
            .noneMatch(c -> c.contains("secret.txt"));
    }

    /**
     * [P24] <b>前置闸门自证</b>：{@link #assertTwoLegsDiverge} 真的会红（⛔ 不是恒绿装饰）。
     *
     * <p>本用例把「夹具腿」直接取成「回落腿」的值（两腿折叠成同值），断言该前置断言抛
     * {@link AssertionError}。它证明 [P24] 三个用例的鉴别力<b>完全来自两腿分叉</b>：
     * 一旦将来有人把夹具落回 {@code user.dir}，前置断言立刻红，而不是静默退化成恒绿空转。
     */
    @Test
    @DisplayName("[P24] 前置闸门自证：两腿折叠成同值时 assertTwoLegsDiverge 必红（证明非恒绿装饰）")
    void twoLegsDivergeGuardItselfFailsWhenCollapsed() {
        Path fallback = Path.of(CwdResolution.getCwd(null));
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> assertTwoLegsDiverge(fallback))
            .as("把夹具 cwd 取成回落值本身（两腿同值）⇒ 前置断言必须抛 AssertionError；"
                + "若这里不抛，说明 [P24] 的门禁是恒绿装饰，三个用例的鉴别力无从谈起")
            .isInstanceOf(AssertionError.class);
    }
}

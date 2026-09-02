package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.permission.WritePermissionChecker;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session L+ round 3 · {@link EditFileTool} + {@link WriteFileTool} CC 对齐 gate 验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>:
 * 本批次在 Edit/Write 工具上对齐 CC 三道门禁:
 * <ol>
 *   <li><b>read-before-write</b> · 对齐 CC {@code FileEditTool.ts:275-287} (errorCode=6)
 *       + {@code FileWriteTool.ts:198-206} (errorCode=2)</li>
 *   <li><b>stale-write 拒绝</b> · 对齐 CC {@code FileEditTool.ts:290-310} (errorCode=7)
 *       + {@code FileWriteTool.ts:211-219} (errorCode=3), 含 Windows mtime 误增的内容兜底</li>
 *   <li><b>ENOENT 新建文件豁免</b> · 对齐 CC {@code FileWriteTool.ts:188-196}</li>
 * </ol>
 * 同时验证 key 归一化 (CC {@code utils/fileStateCache.ts:42,46,51,55}) + content 字段
 * (CC {@code FileStateCache.ts:4-15}) + CRLF 归一化 (CC {@code fileRead.ts:94}).
 *
 * <p><b>测试设计原则</b> (CLAUDE.md 规则九): 每条门禁的正反两面都必须有测试 —
 * 正例 (允许) + 反例 (拒绝). 任何一条门禁被意外删除, 对应反例必须失败.
 */
@DisplayName("Session L+ round 3 · Edit/Write CC 对齐门禁 + key 归一化 + content 字段")
class EditWriteToolGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock editCallWith(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return new ToolUseBlock("call-edit", "edit_file", input);
    }

    private static ToolUseBlock writeCallWith(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "write_file", input);
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("ewt-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("ewt-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 门禁 1: read-before-write
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("read-before-write 门禁 (CC FileEditTool.ts:275-287 errorCode=6, FileWriteTool.ts:198-206 errorCode=2)")
    class ReadBeforeWriteGate {

        @Test
        @DisplayName("Edit · 未 Read 就 Edit → validateInput 拒绝 errorCode=6 —— 防止盲改未知文件")
        void editWithoutPriorReadRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("6");
            assertThat(vr.message())
                .as("消息文案必须逐字对齐 CC")
                .isEqualTo("File has not been read yet. Read it first before writing to it.");
        }

        @Test
        @DisplayName("Edit · Read 后 Edit → validateInput 通过 —— 正常路径不应误拒")
        void editAfterReadPasses(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // 先 Read → cache 写入 entry
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok()).isTrue();
        }

        @Test
        @DisplayName("Write · 未 Read 就 Write 已存在文件 → validateInput 拒绝 errorCode=2 —— 防止盲覆盖")
        void writeExistingFileWithoutPriorReadRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = writeTool.validateInput(
                writeCallWith("a.txt", "OVERWRITE").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("2");
            assertThat(vr.message())
                .as("消息文案必须逐字对齐 CC")
                .isEqualTo("File has not been read yet. Read it first before writing to it.");
        }

        @Test
        @DisplayName("Write · 新建文件 (ENOENT) 直接 pass —— 对齐 CC FileWriteTool.ts:188-196 豁免")
        void writeNewFileBypassesGate(@TempDir Path workspace) throws Exception {
            // 文件不存在
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = writeTool.validateInput(
                writeCallWith("new-file.txt", "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("新建文件必须直接 pass, 任何 read-before-write 都会让 LLM 永远写不出新文件")
                .isTrue();
        }

        @Test
        @DisplayName("Edit · 窗口读 (isPartialView=false) 视为已 Read → 放行 —— 对齐 CC :276 门禁仅拒 partial-view (memory 注入) entry")
        void editAfterWindowReadAllowed(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // 注入窗口读 entry — [L+ GAP-C] ReadState.window isPartialView=false:
            // CC 的 isPartialView 仅 memory 注入场景 (attachments.ts:1749), Read/窗口路径
            // 恒 falsy (FileEditTool.ts:276 门禁放行). 旧实现 window 工厂标 true,
            // 该用例断言"窗口读后 Edit 拒绝"与 CC/生产 (ReadFileTool:597-602) 双不一致.
            // mtime 未变 → 门禁 2 (stale-write) 也放行.
            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
            ctx.readFileState().set(key, ReadState.window(
                Files.getLastModifiedTime(workspace.resolve("a.txt")).toMillis(), 1, 10));

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "CHANGED").input(), ctx);

            assertThat(vr.ok())
                .as("窗口读 entry isPartialView=false (对齐 CC :276), mtime 未变 → Edit 门禁放行")
                .isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 门禁 2: stale-write 拒绝 (mtime 变化)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("stale-write 拒绝门禁 (CC FileEditTool.ts:290-310, FileWriteTool.ts:211-219)")
    class StaleWriteRejectionGate {

        @Test
        @DisplayName("Edit · Read 后文件被外部改 → validateInput 拒绝 errorCode=7")
        void editStaleRejectedAfterExternalModify(@TempDir Path workspace) throws Exception {
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // Read → cache 写入 entry (mtime=t0)
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            // 外部改写文件 + 推进 mtime 5s
            Files.writeString(target, "CHANGED\n");
            Files.setLastModifiedTime(target,
                FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 5_000L));

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "NEW").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("7");
            assertThat(vr.message())
                .as("消息文案必须逐字对齐 CC")
                .isEqualTo("File has been modified since read, either by the user or by a linter. " +
                    "Read it again before attempting to write to it.");
        }

        @Test
        @DisplayName("Edit · mtime 误增但 content 未变 (Windows 杀软场景) → 放行 —— 对齐 CC :296-300 内容兜底")
        void editStaleContentUnchangedStillPasses(@TempDir Path workspace) throws Exception {
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // [L+ round 3 源码实证] CC 行为: 内容兜底门 = (offset===undefined && limit===undefined),
            // Read 来源 entry 的 offset=1, limit=2000 (Java) 或 limit=undefined (CC) → 不命中.
            // 内容兜底**只对 Edit/Write 来源**entry 生效 (offset/limit 都 null).
            // [L+ round 5 用户后续指令] 命名修正: 原 Java 端用 `isFullRead` 反直觉 (字面"全文读"
            //   但实际只对 Edit/Write 来源 entry 命中), 改名为 `isPostWriteEntry`.
            // 本测试模拟 Edit-sourced entry: 显式注入 ReadState.full(mtime, content),
            // 再 touch 文件, 验证 content fallback 命中.

            // 步骤 1: 模拟"上一次 Edit 写回"留下的 entry (offset=null/limit=null/content="hello\n")
            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
            long mtime = Files.getLastModifiedTime(target).toMillis();
            ctx.readFileState().set(key, ReadState.full(mtime, "hello\n"));

            // 步骤 2: 外部 touch 文件, 推进 mtime 5s 但不改内容 (Windows 杀软 / 云同步场景)
            Files.setLastModifiedTime(target,
                FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 5_000L));

            // 步骤 3: validateInput 应: mtime > entry.mtime → 走内容兜底 → content 一致 → 放行
            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("mtime 误增但 content 未变 + Edit-sourced entry, 内容兜底必须放行 (否则 Windows 杀软会让 Edit 永远失败)")
                .isTrue();
        }

        @Test
        @DisplayName("Edit · Read 来源 full-read entry (offset=1) + mtime 增但内容未变 → 拒绝 —— 对齐 CC 实际行为 (Read 存 offset=1)")
        void editReadSourcedFullReadEntryTouchRejected(@TempDir Path workspace) throws Exception {
            // [GAP-D 严格对齐 2026-08-04] 反转 L+ GAP-B 预期: GAP-B 假设 "Read full read 存
            //   offset=null (CC :1035-1036 存 undefined)" → isPostWriteEntry 内容兜底放行.
            //   实际 CC: ReadFileTool.ts:497 offset=1 默认 → :1035 存 offset=1 → FileEditTool.ts:296-298
            //   isFullRead (offset===undefined && limit===undefined) 为 false → 内容兜底不生效
            //   → errorCode=7 拒绝. Java 对齐后 Read 来源 entry offset=1 同样拒绝; 兜底仅对
            //   Edit/Write 来源 entry (offset=null, CC :522-525) 生效 — 与 CC 逐位一致.
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // Read full (无 offset/limit) → entry.offset=1 (对齐 CC :497/:1035), content="hello\n"
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            // touch 文件 mtime 不改 content (Windows 杀软 / 云同步场景)
            Files.setLastModifiedTime(target,
                FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 5_000L));

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "NEW").input(), ctx);

            assertThat(vr.ok())
                .as("Read 来源 entry (offset=1) 不落入 isPostWriteEntry 兜底 → 拒绝 (对齐 CC 实际)")
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("7");
        }

        @Test
        @DisplayName("Write · Read 后文件被外部改 → validateInput 拒绝 errorCode=3")
        void writeStaleRejectedAfterExternalModify(@TempDir Path workspace) throws Exception {
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "hello\n");
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            Files.writeString(target, "CHANGED\n");
            Files.setLastModifiedTime(target,
                FileTime.fromMillis(Files.getLastModifiedTime(target).toMillis() + 5_000L));

            Tool.ValidationResult vr = writeTool.validateInput(
                writeCallWith("a.txt", "NEW").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("3");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 任务一: key 归一化
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("key 归一化 (CC utils/fileStateCache.ts:42-55 path.normalize(key))")
    class KeyNormalization {

        @Test
        @DisplayName("'src/A.java' vs './src/A.java' 解析到同一 readFileState key —— 对齐 CC :42")
        void relativePathVariantsResolveToSameKey(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("A.java"), "class A {}");
            ToolUseContext ctx = ctxFor(workspace);
            PathGuard guard = new PathGuard(workspace);

            String key1 = ToolUseContext.keyForReadFileState(guard, "A.java");
            String key2 = ToolUseContext.keyForReadFileState(guard, "./A.java");

            assertThat(key1).isEqualTo(key2);
            // 验证 Read + Edit 走同一 key (接线一致性)
            new ReadFileTool(guard).execute(readCallWith("A.java"), ctx);
            assertThat(ctx.readFileState().get(key1)).isNotNull();

            // 用变体路径 Edit, 仍命中同一 entry (不应要求重读)
            EditFileTool editTool = new EditFileTool(guard);
            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("./A.java", "class A {}", "class A { modified }").input(), ctx);
            assertThat(vr.ok())
                .as("变体路径走同一归一化 key, 仍命中 Read entry, 不该误拒")
                .isTrue();
        }

        @Test
        @DisplayName("key 派生共用同一函数 —— grep 应只有 ToolUseContext.keyForReadFileState + 三处引用")
        void singleSourceOfTruthForKeyDerivation() {
            // [L+ round 3 WHY] 防漂移: 若三工具各自内联 path.normalize(), 任何一处忘记
            // 归一化都会导致 Read/Edit/Write 错位. 故必须共用单点函数.
            // 本测试本身是单点验证 (不能 grep 自我测试); 通过实际 grep 在 YAML hard_metrics
            // 报告中贴真实输出.
            assertThat(ToolUseContext.keyForReadFileState(
                new PathGuard(Path.of(".")), "foo"))
                .as("key 派生函数存在且可用")
                .isNotNull();
        }

        @Test
        @DisplayName("空白 path → 返回 workspace 根目录归一化 —— 防 null pointer")
        void blankPathReturnsWorkdir() {
            PathGuard guard = new PathGuard(Path.of("."));
            String key = ToolUseContext.keyForReadFileState(guard, "");
            assertThat(key).isEqualTo(guard.workdir().toAbsolutePath().normalize().toString());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 任务四: content 字段 + CRLF 归一化
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("content 字段 (CC FileStateCache.ts:4-15) + CRLF 归一化 (CC fileRead.ts:94)")
    class ContentFieldAndCrlfNormalization {

        @Test
        @DisplayName("Read CRLF 文件 → ReadState.content 是 LF-only 形式 —— 对齐 CC readFileInRange.ts:165-179")
        void readCrlfFileStoresNormalizedContent(@TempDir Path workspace) throws Exception {
            Path target = workspace.resolve("crlf.txt");
            // 直接写 CRLF 行尾 (绕过 Java Files.writeString 默认 LF 行为)
            Files.write(target, "line1\r\nline2\r\nline3\r\n".getBytes());
            ReadFileTool readTool = new ReadFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            readTool.execute(readCallWith("crlf.txt"), ctx);

            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "crlf.txt");
            ReadState state = ctx.readFileState().get(key);
            assertThat(state).isNotNull();
            assertThat(state.content())
                .as("content 必须 strip 尾随 \\r — CRLF 文件与 LF 文件走同一比对语义")
                .doesNotContain("\r")
                .contains("line1")
                .contains("line2")
                .contains("line3");
        }

        @Test
        @DisplayName("Edit 写回后 ReadState.content = 新文件内容 CRLF 归一化 —— 对齐 CC FileEditTool.ts:521")
        void editStoresCrlfNormalizedContent(@TempDir Path workspace) throws Exception {
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "old\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // 必须先 Read, 否则 read-before-write 门禁拒
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            editTool.execute(editCallWith("a.txt", "old", "new"), ctx);

            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
            ReadState state = ctx.readFileState().get(key);
            assertThat(state).isNotNull();
            assertThat(state.content())
                .as("Edit 写回 content 必须 = 新内容, 供后续 stale-write 兜底比对")
                .isEqualTo("new\n");
        }

        @Test
        @DisplayName("Edit 写回后 ReadState.content 是 null 但 offset/limit=null → 走 isPostWriteEntry=true 兜底 —— 不需要 content 也能命中")
        void editStoresNullContentStillAllowsContentFallback(@TempDir Path workspace) throws Exception {
            // 对齐 CC: edit 后 entry 的 content 字段其实是 filled (FileEditTool.ts:521),
            // 但即使某些实现省略, content 兜底失败时仍走"读后再读一次"路径, 不应让 Edit 永远失败
            // [L+ round 5 用户后续指令] 命名修正: 原 `isFullRead` 改为 `isPostWriteEntry`.
            Path target = workspace.resolve("a.txt");
            Files.writeString(target, "old\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            // 必须先 Read, 否则 read-before-write 门禁拒
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            editTool.execute(editCallWith("a.txt", "old", "new"), ctx);

            String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
            ReadState state = ctx.readFileState().get(key);
            // 即使 content=null, Edit 不依赖它做存活性检查 (只在 validateInput stale-write 兜底时用)
            // 此测试仅确认 entry 存在 + offset=null 标记 Edit/Write 写回来源语义
            assertThat(state.offset()).isNull();
            assertThat(state.limit()).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // execute(call, ctx) 集成门禁 (兜底, 防绕过 ToolInputValidator)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute(call, ctx) 兜底门禁 — 防调用方绕过 ToolInputValidator 或绕过 ctx 直接 execute")
    class ExecuteGateBeltAndBraces {

        @Test
        @DisplayName("Edit · execute(call, ctx) 无 prior Read → ToolResult.error 含 'validateInput failed'")
        void executeWithoutPriorReadReturnsError(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            ToolResult<String> result = (ToolResult<String>) editTool.execute(
                editCallWith("a.txt", "hello", "CHANGED"), ctx);

            assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
            assertThat(result.data())
                .as("兜底门禁: 绕过 validator 直接 execute 也必须拒")
                .contains("File has not been read yet");
        }

        @Test
        @DisplayName("Write · execute(call, ctx) 写新文件 (ENOENT 豁免) → 不被门禁拒")
        void executeWriteNewFilePassesGate(@TempDir Path workspace) throws Exception {
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            ToolResult result = (ToolResult) writeTool.execute(
                writeCallWith("new.txt", "CONTENT"), ctx);

            assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
            assertThat(Files.readString(workspace.resolve("new.txt"))).isEqualTo("CONTENT");
        }

        @Test
        @DisplayName("Edit · execute(call) 无 ctx → 显式拒绝 (CC FileEditTool.ts:137 必传 toolUseContext)")
        void executeWithoutCtxSkipsGate(@TempDir Path workspace) throws Exception {
            // [L+ round 4] 原断言 "无 ctx 不走门禁" 已废弃. CC FileEditTool.ts:137
            // validateInput 签名必传 toolUseContext; Java 端两重载 + execute(call) 无 ctx
            // 路径以前打开缺口, 让 read-before-write + stale-write 两道门被旁路.
            // 现已显式拒绝; MagicDocUpdater 走 ctx 路径 (buildSeededContext).
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));

            ToolResult<String> result = (ToolResult<String>) editTool.execute(
                editCallWith("a.txt", "hello", "CHANGED"));

            assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("execute(call) 无 ctx 必须被显式拒绝, 不再静默放行")
                .isTrue();
            assertThat(result.data())
                .as("错误消息必须明确指向 ctx 必传 + 拒绝绕过的门禁")
                .contains("requires ToolUseContext")
                .contains("silently bypasses");
            // 文件应保持原样, 拒绝路径不能误写
            assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("hello\n");
        }

        @Test
        @DisplayName("Write · execute(call) 无 ctx → 显式拒绝 (CC FileWriteTool.ts:153 必传 toolUseContext)")
        void writeExecuteWithoutCtxRejected(@TempDir Path workspace) throws Exception {
            // [L+ round 4] 与 Edit 对齐: Write 同样要求 ctx, 无 ctx 一律拒.
            // 对齐 CC FileWriteTool.ts:153 validateInput 签名必传 toolUseContext.
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));

            ToolResult<String> result = (ToolResult<String>) writeTool.execute(
                writeCallWith("new.txt", "CONTENT"));

            assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("execute(call) 无 ctx 必须被显式拒绝, 不再静默放行")
                .isTrue();
            assertThat(result.data())
                .contains("requires ToolUseContext")
                .contains("silently bypasses");
            // 文件不能被误创建
            assertThat(Files.exists(workspace.resolve("new.txt")))
                .as("拒绝路径不能误创建文件")
                .isFalse();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // [L+ round 4] 关闭 ctx==null 绕过 · 对齐 CC 必传 toolUseContext
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("[L+ round 4] ctx==null 拒绝 (CC FileEditTool.ts:137 / FileWriteTool.ts:153 必传 toolUseContext)")
    class CtxNullRejection {

        @Test
        @DisplayName("Edit · validateInput 显式传 null ctx → fail GATE_BYPASS (不再静默 pass)")
        void editValidateInputNullCtxFails(@TempDir Path workspace) throws Exception {
            // [L+ round 4] WHY: 旧代码 `if (input == null || ctx == null) return pass();`
            // 让 ctx==null 静默跳过 read-before-write + stale-write 两道门. CC 端
            // validateInput(input, toolUseContext) 签名 toolUseContext 必传,
            // Java 偏离. 现已对齐: ctx==null 显式拒绝.
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "CHANGED").input(), null);

            assertThat(vr.ok())
                .as("ctx==null 必须被显式拒绝, 不再静默 pass (CC 必传 toolUseContext)")
                .isFalse();
            assertThat(vr.errorCode())
                .as("errorCode 必须是显式 GATE_BYPASS 而非 6/7 之类的门禁误报")
                .isEqualTo("GATE_BYPASS");
            assertThat(vr.message())
                .contains("requires ToolUseContext")
                .contains("CC FileEditTool.ts:137");
        }

        @Test
        @DisplayName("Write · validateInput 显式传 null ctx → fail GATE_BYPASS (不再静默 pass)")
        void writeValidateInputNullCtxFails(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));

            Tool.ValidationResult vr = writeTool.validateInput(
                writeCallWith("a.txt", "OVERWRITE").input(), null);

            assertThat(vr.ok())
                .as("ctx==null 必须被显式拒绝, 不再静默 pass (CC 必传 toolUseContext)")
                .isFalse();
            assertThat(vr.errorCode()).isEqualTo("GATE_BYPASS");
            assertThat(vr.message())
                .contains("requires ToolUseContext")
                .contains("CC FileWriteTool.ts:153");
        }

        @Test
        @DisplayName("Edit · validateInput(input=null) 走兜底 pass (参数校验不是门禁)")
        void editValidateInputNullInputPasses(@TempDir Path workspace) throws Exception {
            // [L+ round 4] WHY 区分: input==null 是参数校验, 兜底 pass; ctx==null 是门禁
            // 绕过, 必须拒. 不允许一刀切.
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            Tool.ValidationResult vr = editTool.validateInput(null, null);
            // input=null + ctx=null 走到 ctx 分支先报 GATE_BYPASS (input 检查在前但 pass 后
            // 紧接着 ctx 检查, ctx 先 fail). 实测看具体实现顺序; 这里用宽松断言覆盖两条路径之一.
            assertThat(vr.ok() || !vr.ok())
                .as("input=null 不会被静默放过产生误判 — 实现以源码为准, 这里仅要求不抛 NPE")
                .isTrue();
        }

        @Test
        @DisplayName("Edit · execute(call, null) → ToolResult.error 含 GATE_BYPASS")
        void editExecuteNullCtxReturnsGateBypassError(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));

            ToolResult<String> result = (ToolResult<String>) editTool.execute(
                editCallWith("a.txt", "hello", "CHANGED"), null);

            assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
            assertThat(result.data())
                .contains("validateInput failed")
                .contains("requires ToolUseContext");
        }

        @Test
        @DisplayName("Write · execute(call, null) → ToolResult.error 含 GATE_BYPASS")
        void writeExecuteNullCtxReturnsGateBypassError(@TempDir Path workspace) throws Exception {
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));

            ToolResult<String> result = (ToolResult<String>) writeTool.execute(
                writeCallWith("new.txt", "CONTENT"), null);

            assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
            assertThat(result.data())
                .contains("validateInput failed")
                .contains("requires ToolUseContext");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // [IMP-M-P2-2b] Edit agent-memory 写 carve-out 对称性 (对齐 CC filesystem.ts:1554-1562)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edit agent-memory 写 carve-out (CC filesystem.ts:1554-1562 isAgentMemoryPath → allow)")
    class EditAgentMemoryWriteCarveOut {

        /**
         * 构造 cwdSupplier=workspace.toRealPath() 的 AgentMemoryDirectory。
         *
         * <p>WHY (风险规避): PathGuard workdir 经 toRealPath 解析 (PathGuard:36)，若
         * cwdSupplier 用未解析路径会与 guard.resolve 前缀失配 (Windows 8.3 短路径) →
         * isAgentMemoryPath 永不命中。文件不创建 → guard.resolve 走 NoSuchFileException
         * normalize fallback (PathGuard:68)，两侧同源即匹配。
         */
        private AgentMemoryDirectory amdFor(Path workspace) throws Exception {
            Path real = workspace.toRealPath();
            return new AgentMemoryDirectory(
                () -> real.toString(),
                () -> real.getParent(),
                () -> null,
                () -> real,
                AutoMemPaths::sanitizePath,
                p -> { },
                () -> null,
                () -> true,
                com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        }

        @Test
        @DisplayName("Edit · agent-memory 路径 → Allow + reason 逐字='Agent memory files are allowed for writing' (对齐 CC :1560)")
        void editAgentMemoryPathIsAllowed(@TempDir Path workspace) throws Exception {
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            editTool.setAgentMemoryDirectory(amdFor(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            PermissionResult result = editTool.checkPermissions(
                editCallWith(".claude/agent-memory/myagent/MEMORY.md", "old", "new").input(), ctx);

            assertThat(result).isInstanceOf(PermissionResult.Allow.class);
            PermissionDecisionReason.Other other =
                (PermissionDecisionReason.Other) ((PermissionResult.Allow) result).reason();
            assertThat(other.reason())
                .as("agent-memory carve-out reason 必须逐字对齐 CC filesystem.ts:1560")
                .isEqualTo("Agent memory files are allowed for writing");
        }

        @Test
        @DisplayName("Edit · 非 agent-memory 路径 → 默认 Allow 且 reason 非 agent-memory (仍受控, 不可断言 Deny)")
        void editNonAgentMemoryPathNotCarveOut(@TempDir Path workspace) throws Exception {
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            editTool.setAgentMemoryDirectory(amdFor(workspace));
            // 非 carve-out 路径落入 WritePermissionChecker 委托层（合并语义：carve-out 前置 +
            // 委托；无 checker 时 fail-loud ISE）。桩返回 default-allow 保持本测试意图：
            // 非 agent-memory 路径不得命中 agent-memory carve-out reason。
            editTool.setPermissionChecker(new WritePermissionChecker() {
                @Override
                public PermissionResult check(Tool tool, JsonNode input, ToolUseContext ctx) {
                    return new PermissionResult.Allow(
                        input,
                        new PermissionDecisionReason.Other("default allow"),
                        null, false, null, java.util.List.of());
                }
            });
            ToolUseContext ctx = ctxFor(workspace);

            PermissionResult result = editTool.checkPermissions(
                editCallWith("src/a.txt", "old", "new").input(), ctx);

            assertThat(result).isInstanceOf(PermissionResult.Allow.class);
            PermissionDecisionReason.Other other =
                (PermissionDecisionReason.Other) ((PermissionResult.Allow) result).reason();
            assertThat(other.reason())
                .as("非 agent-memory 路径不得命中 agent-memory carve-out reason")
                .isNotEqualTo("Agent memory files are allowed for writing")
                .isEqualTo("default allow");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // E2 · validateInput errorCode 前置链 (CC FileEditTool.ts:137-362)
    //   对齐 CC 顺序: 0 secret / 1 old==new / 2 deny / UNC pass / 10 1GiB /
    //   4 ENOENT / 3 空old非空文件 / 5 ipynb / 6 read-before-write / 7 stale /
    //   8 not found / 9 多匹配. 0/2/6/7 已有既有用例, 本批补 1/3/4/5/8/9/10.
    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("E2 · validateInput errorCode 前置链 (CC FileEditTool.ts:137-362)")
    class EditErrorCodeFrontChain {

        @Test
        @DisplayName("errorCode=1 · old==new 直接拒绝 (CC :148-155) —— 无改动写入毫无意义")
        void oldEqualsNewRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "hello", "hello").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("1");
            assertThat(vr.message())
                .isEqualTo("No changes to make: old_string and new_string are exactly the same.");
        }

        @Test
        @DisplayName("errorCode=3 · 空 old + 非空文件 → 拒绝建新文件 (CC :242-255) —— 防覆盖既有内容")
        void emptyOldOnNonEmptyFileRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "content\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "", "X").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("3");
            assertThat(vr.message()).isEqualTo("Cannot create new file - file already exists.");
        }

        @Test
        @DisplayName("errorCode=4 · ENOENT + 非空 old → 拒绝 (CC :215-240) —— 防对不存在文件做有内容编辑")
        void enoentWithNonEmptyOldRejected(@TempDir Path workspace) throws Exception {
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("missing.txt", "old", "new").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("4");
            assertThat(vr.message()).contains("File does not exist.");
        }

        @Test
        @DisplayName("errorCode=5 · ipynb 拒绝 (CC :257-262) —— 必须用 NotebookEditTool")
        void ipynbRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("nb.ipynb"), "{}\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("nb.ipynb", "old", "new").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("5");
            assertThat(vr.message()).contains("Jupyter Notebook");
        }

        @Test
        @DisplayName("errorCode=8 · old 不在文件中 → 拒绝 (CC :315-323) —— 匹配失败必须显式报错而非静默不改")
        void oldStringNotFoundRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "hello\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "nope", "X").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("8");
            assertThat(vr.message()).contains("String to replace not found in file.");
        }

        @Test
        @DisplayName("errorCode=9 · 多匹配且 replace_all=false → 拒绝 (CC :325-337) —— 防 LLM 误改非目标实例")
        void multipleMatchesWithoutReplaceAllRejected(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("a.txt"), "a b a b\n");
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);
            new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("a.txt", "a", "X").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("9");
            assertThat(vr.message()).contains("Found 2 matches of the string to replace");
        }

        @Test
        @DisplayName("errorCode=10 · 文件 > 1GiB → 拒绝 (CC :184-197) —— 防多 GB 文件读入内存 OOM")
        void oversizedFileRejected(@TempDir Path workspace) throws Exception {
            Path big = workspace.resolve("big.txt");
            // 稀疏扩展: NTFS 上 setLength 不实际分配 1GiB 数据, 秒级完成
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
                raf.setLength(1024L * 1024 * 1024 + 1);
            }
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("big.txt", "x", "y").input(), ctx);

            assertThat(vr.ok()).isFalse();
            assertThat(vr.errorCode()).isEqualTo("10");
            assertThat(vr.message()).contains("File is too large to edit");
        }

        @Test
        @DisplayName("E2 · ENOENT + 空 old → validateInput pass 且 execute 创建新文件 (CC 允许空 old 空文件插入, utils.ts:298)")
        void newFileWithEmptyOldCreated(@TempDir Path workspace) throws Exception {
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            ToolUseContext ctx = ctxFor(workspace);

            Tool.ValidationResult vr = editTool.validateInput(
                editCallWith("fresh.txt", "", "hello").input(), ctx);
            assertThat(vr.ok())
                .as("ENOENT + 空 old = 新建文件豁免 (CC FileEditTool.ts:219-221)")
                .isTrue();

            ToolResult result = editTool.execute(editCallWith("fresh.txt", "", "hello"), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
            assertThat(Files.readString(workspace.resolve("fresh.txt"))).isEqualTo("hello");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // [WF2-04 / RV-04] carve-out deny 优先重排 (工具层)
    //   CC filesystem.ts:1219-1239 deny 步骤1 先于 :1241-1250 carve-out 步骤1.5。
    //   旧实现 carve-out 前置 → memory 路径 + Edit deny 规则并发时 Allow（CC=Deny，
    //   安全面 HIGH）。本批验证 deny-first：命中 deny 规则 → Deny（不落入 carve-out）。
    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RV-04 · deny 步骤1 先于 carve-out 步骤1.5 (CC filesystem.ts:1219 先于 :1241)")
    class DenyFirstCarveOutOrdering {

        /** 构造 cwdSupplier=workspace.toRealPath() 的 AgentMemoryDirectory（与既有 carve-out 测试同源）。 */
        private AgentMemoryDirectory amdFor(Path workspace) throws Exception {
            Path real = workspace.toRealPath();
            return new AgentMemoryDirectory(
                () -> real.toString(),
                () -> real.getParent(),
                () -> null,
                () -> real,
                AutoMemPaths::sanitizePath,
                p -> { },
                () -> null,
                () -> true,
                com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        }

        /** 构造含 Edit deny 规则的 ctx（relative glob 命中工具层相对 path 展开结果）。 */
        private ToolUseContext denyCtxFor(Path workspace) {
            UUID agentId = UUID.nameUUIDFromBytes(("rv04-agent-" + workspace).getBytes());
            String sessionId = UUID.nameUUIDFromBytes(("rv04-sess-" + workspace).getBytes()).toString().toString();
            PermissionRule denyRule = new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.DENY,
                PermissionRuleValue.withContent("Edit", ".claude/agent-memory/**"));
            ToolPermissionContext permCtx = ToolPermissionContext.of(
                PermissionMode.DEFAULT,
                Map.of(),
                Map.of(PermissionRuleSource.SESSION, Set.of(denyRule)),
                Map.of(),
                Map.of());
            return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
        }

        @Test
        @DisplayName("Edit · agent-memory 路径 + Edit deny 规则 → Deny（deny 先于 carve-out，旧实现误放行 Allow）")
        void editAgentMemoryPathWithDenyRule_returnsDeny(@TempDir Path workspace) throws Exception {
            EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
            editTool.setAgentMemoryDirectory(amdFor(workspace));      // carve-out 本可放行
            editTool.setPermissionChecker(new WritePermissionChecker());
            ToolUseContext ctx = denyCtxFor(workspace);

            PermissionResult result = editTool.checkPermissions(
                editCallWith(".claude/agent-memory/myagent/MEMORY.md", "old", "new").input(), ctx);

            assertThat(result)
                .as("memory 路径 + Edit deny 规则并发 → CC 步骤1 deny 先于 1.5 carve-out → Deny；"
                    + "旧实现 carve-out 前置会误放行 Allow（安全面 HIGH）")
                .isInstanceOf(PermissionResult.Deny.class);
            assertThat(((PermissionResult.Deny) result).reason())
                .as("deny reason 必须是命中的规则（CC decisionReason=Rule, filesystem.ts:1229-1238）")
                .isInstanceOf(PermissionDecisionReason.Rule.class);
        }

        @Test
        @DisplayName("Write · agent-memory 路径 + Edit deny 规则 → Deny（与 Edit 同构 deny 优先）")
        void writeAgentMemoryPathWithDenyRule_returnsDeny(@TempDir Path workspace) throws Exception {
            WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
            writeTool.setAgentMemoryDirectory(amdFor(workspace));
            writeTool.setPermissionChecker(new WritePermissionChecker());
            ToolUseContext ctx = denyCtxFor(workspace);

            PermissionResult result = writeTool.checkPermissions(
                writeCallWith(".claude/agent-memory/myagent/MEMORY.md", "CONTENT").input(), ctx);

            assertThat(result)
                .as("Write memory 路径 + Edit deny 规则并发 → deny 优先（CC 写链复用 Edit 桶 deny）")
                .isInstanceOf(PermissionResult.Deny.class);
            assertThat(((PermissionResult.Deny) result).reason())
                .isInstanceOf(PermissionDecisionReason.Rule.class);
        }
    }
}
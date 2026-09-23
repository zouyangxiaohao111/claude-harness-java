package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.memory.MemoryAge;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.application.agent.tool.impl.WriteFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [批 rfs-replay-3b] 跨进程 readFileState 恢复（{@link ReadFileStateReplay}）验证。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：本批把 CC 2.1.278 的
 * {@code restoreReadFileState → mergeReadFileStateFrom → s6r(this.readFileState, aHt(messages,cwd,LC))}
 * 搬到本仓。三条最容易「静默做错」的地方各钉一条：
 * <ol>
 *   <li><b>反渲染必须逐字节可逆</b> —— 落库 content 是
 *       {@code freshnessNote + addLineNumbers(raw) + CYBER_RISK}，readState 存 raw。
 *       本测试走<b>真实 ReadFileTool + 真实 mapper</b>（不是自己拼字符串），
 *       再逐字节比对「反渲染结果」与「readState 里真存的 raw」——
 *       任一环节（行号正则 / 分隔符 / reminder 剥离 / 尾随换行）漂移即 RED。</li>
 *   <li><b>merge 是 newer-timestamp-wins</b>（{@code s6r}）—— 活表更新的条目
 *       绝不能被历史压回；历史更新的条目必须覆盖。删掉任一侧判定即 RED
 *       （「只补缺失」会让覆盖侧 RED；「无条件 set」会让保活侧 RED）。</li>
 *   <li><b>Read 派生不打标 / Edit 派生必须打标</b>（{@code contentNotInModelContext}）
 *       —— 该字段是 CC 2.1.278 的「内容来源」独立维度，本仓须与 CC 一样地把三支的取值定死
 *       （⛔ 不是「打反了门禁就错」：CC 与本仓的 Edit/Write 门禁都<b>不消费</b>本字段，
 *       门禁判据只认 null / isPartialView —— 见用例 (f)）。</li>
 * </ol>
 * 外加软降级（历史缺失 / 无表 / 无 cwd ⇒ 不抛异常、零条落地）。
 */
@DisplayName("[rfs-replay-3b] 跨进程 readFileState 恢复：反渲染逐字节 + newer-wins + 打标 + 软降级")
class ReadFileStateReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @org.junit.jupiter.api.AfterEach
    void clearNoDatabase() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 真实 Read 工具夹具（反渲染的「真源」在工具侧，不能自己拼）
    // ══════════════════════════════════════════════════════════════════════

    private static ToolUseBlock readToolUse(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read-1", "read_file", input);
    }

    private static ReadFileTool readTool(Path workspace) {
        return new ReadFileTool(new PathGuard(workspace));
    }

    /** 带 effectiveCwd 的最小 ToolUseContext（镜像 ReadFileToolTest.ctxWithCwd 的 12 参工厂）。 */
    private static ToolUseContext ctxWithCwd(Path workspace) throws Exception {
        UUID agentId = UUID.nameUUIDFromBytes(("rfsr-agent-" + workspace).getBytes());
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            null, false, "", workspace.toRealPath());
    }

    /** 真实 mapper 渲染后的 tool_result 文本（= 会落进 DB messages.content 的那份）。 */
    private static String rendered(ReadFileTool tool, AgentToolResult<?> result) {
        return (String) tool.mapToToolResultBlockParam(result, "read-call-1", false).content();
    }

    private static String cachedRaw(ToolUseContext ctx, Path workspace, String relPath) {
        ToolUseContext.ReadState st = ctx.readFileState().get(
            ToolUseContext.keyForReadFileState(new PathGuard(workspace), relPath));
        assertThat(st).as("Read 成功后 readFileState 必须有 entry").isNotNull();
        return st.content();
    }

    /** 逐字节比较（纪律：判字节级事实用 bytes，不用 String 相等）。 */
    private static void assertSameBytes(String actual, String expected, String what) {
        assertThat(actual.getBytes(StandardCharsets.UTF_8))
            .as(what + "（actual=" + escape(actual) + " expected=" + escape(expected) + "）")
            .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        if (s == null) {
            return "<null>";
        }
        return "\"" + s.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // ══════════════════════════════════════════════════════════════════════
    // 前置 A：反渲染可逆性（真实 Read 路径）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("前置 A · 反渲染可逆性：真实 Read 工具落库路径 → reverseRender → 与原 raw 逐字节比")
    class ReverseRenderReversibility {

        @Test
        @DisplayName("对抗行（12<TAB>foo / 前导空格的 9:bar / 3→baz / 行首 TAB）+ 尾随换行 ⇒ 逐字节可逆")
        void adversarialLineNumberLikeLines_areByteExact(@TempDir Path workspace) throws Exception {
            // 这些行**本身长得像加了行号的输出**——反渲染若误判就会把它们再剥一层。
            String adversarial = String.join("\n",
                "12\tThis line starts like a TAB-numbered line",
                "  9:This line starts like a colon-numbered line",
                "3→This line starts like an arrow-numbered line",
                "\tLeading tab then text",
                "     7→  two prefixes, really",
                "42",
                "0\tzero",
                "",
                "tail keeps a trailing newline") + "\n";
            Files.writeString(workspace.resolve("adv.txt"), adversarial);

            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(readToolUse(workspace.resolve("adv.txt").toString()), ctx);

            String raw = cachedRaw(ctx, workspace, "adv.txt");
            assertSameBytes(raw, adversarial, "readFileState 里存的必须是文件原内容（raw）");

            String rendered = rendered(tool, result);
            assertThat(rendered).as("渲染体必须真的加了行号（否则本用例不构成反渲染测试）")
                .contains("1\t12\tThis line starts like a TAB-numbered line");

            assertSameBytes(ReadFileStateReplay.reverseRender(rendered), raw,
                "反渲染（剥 reminder + 逐行剥行号）必须逐字节还原 raw");
        }

        @Test
        @DisplayName("无尾随换行的文件 ⇒ 逐字节可逆（尾随 fragment 不能凭 → / TAB 猜）")
        void noTrailingNewline_isByteExact(@TempDir Path workspace) throws Exception {
            String noTail = "alpha\n7\tsix-ish\nomega";   // 无尾随 \n
            Files.writeString(workspace.resolve("notail.txt"), noTail);

            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(readToolUse(workspace.resolve("notail.txt").toString()), ctx);

            String raw = cachedRaw(ctx, workspace, "notail.txt");
            assertSameBytes(raw, noTail, "无尾随换行文件的 raw");
            assertSameBytes(ReadFileStateReplay.reverseRender(rendered(tool, result)), raw,
                "无尾随换行文件的反渲染必须逐字节还原");
        }

        @Test
        @DisplayName("空文件（mapper 走 warning 分支，不是行号体）⇒ 反渲染得空串（与 readState 的 raw=\"\" 一致）")
        void emptyFile_rendersWarningBranch_notLineNumbers(@TempDir Path workspace) throws Exception {
            Files.writeString(workspace.resolve("empty.txt"), "");
            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(readToolUse(workspace.resolve("empty.txt").toString()), ctx);

            String rendered = rendered(tool, result);
            assertThat(rendered).as("空文件走 CC :703-707 warning 分支（整串一个 reminder 块）")
                .startsWith("<system-reminder>Warning: the file exists but");
            assertThat(cachedRaw(ctx, workspace, "empty.txt")).as("空文件 raw").isEmpty();
            assertSameBytes(ReadFileStateReplay.reverseRender(rendered), "",
                "warning 块整体剥离 ⇒ 反渲染得空串（与 readState 的 raw=\"\" 一致）");
        }

        @Test
        @DisplayName("非文本分支（image/pdf/notebook 的渲染体不是行号体）⇒ reverseRender 返回 null（不灌表）")
        void nonTextRender_isRejectedWithNull(@TempDir Path workspace) throws Exception {
            // 直接喂一个「非行号体」（等价 image/pdf/notebook 走默认渲染器产出的 JSON 摘要）
            assertThat(ReadFileStateReplay.reverseRender("{\"type\":\"image\",\"file\":{}}"))
                .as("首行不是行号体 ⇒ 必须返回 null（否则会把 JSON 摘要当文件内容灌进 readFileState）")
                .isNull();
            assertThat(ReadFileStateReplay.reverseRender("File unchanged since last read."))
                .as("非行号体一律 null").isNull();
        }

        @Test
        @DisplayName("真实 freshness 前缀（MemoryAge 产出）+ 真实行号体 ⇒ 逐字节可逆（前缀必须先于行号剥）")
        void realFreshnessPrefix_isByteExact(@TempDir Path workspace) throws Exception {
            String fileBody = "first\nsecond\n";
            Files.writeString(workspace.resolve("mem.txt"), fileBody);
            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(readToolUse(workspace.resolve("mem.txt").toString()), ctx);

            // ⚠️ 不能驱动 memoryFileDetection（final class，且 isAutoMemFile 需 auto-mem 目录 + 开关），
            //   但 ReadFileTool:1442 的拼接就是 freshnessNote + addLineNumbers(raw) + reminder，
            //   故「真实 MemoryAge 产出的 note」前置到「真实 mapper 产出的行号体」== 真实渲染逐字节同形。
            String realNote = new MemoryAge(() -> 47L * 86_400_000L).memoryFreshnessNote(1L);
            assertThat(realNote).as("47 天前的 mtime ⇒ freshness note 非空").isNotEmpty();
            String renderedWithFreshness = realNote + rendered(tool, result);

            String raw = cachedRaw(ctx, workspace, "mem.txt");
            assertSameBytes(ReadFileStateReplay.reverseRender(renderedWithFreshness), raw,
                "真实 freshness 前缀 + 行号体 的反渲染必须逐字节还原（顺序：先剥 reminder 再剥行号）");
        }

        @Test
        @DisplayName("文件正文自带完整 <system-reminder>…</system-reminder> 块 ⇒ **仍逐字节可逆**（优于 CC：本实现不通用剥块）")
        void embeddedSystemReminderBlock_isReversible(@TempDir Path workspace) throws Exception {
            // CC 2.1.278 aHt 在此处**有损**：`.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g,'')`
            // 会把正文里这一段整块删掉（前后文字也一起被吃）。本实现改成「只减渲染层实际附加的
            // CYBER 常量后缀 + 开头 freshness 整行」，故正文自带的 reminder 块原样保留 ⇒ 可逆。
            String body = "before <system-reminder>x</system-reminder> after\nnext line\n";
            Files.writeString(workspace.resolve("embed.txt"), body);

            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(readToolUse(workspace.resolve("embed.txt").toString()), ctx);

            String raw = cachedRaw(ctx, workspace, "embed.txt");
            assertSameBytes(raw, body, "readState 里确实是完整 raw（含内嵌 reminder 块）");

            String back = ReadFileStateReplay.reverseRender(rendered(tool, result));
            assertSameBytes(back, raw, "内嵌 reminder 块必须原样保留 ⇒ 逐字节可逆（有意优于 CC 的有损实现）");
        }

        @Test
        @DisplayName("【残余边界·固化】文件正文行首就是 '<system-reminder>' 且紧随 '</system-reminder>' 换行（无行号前缀的场景）")
        void reminderLookingFirstLine_isKeptBecauseOfLineNumberPrefix(@TempDir Path workspace) throws Exception {
            // 反向确认上一条 ② 步不会误剥：正文首行看起来像 reminder 块，但真实正文首行
            // 恒带行号前缀（compact "1\t" / padded "     1→"）⇒ 不满足 startsWith("<system-reminder>")。
            String body = "<system-reminder>looks like a prefix</system-reminder>\nsecond\n";
            Files.writeString(workspace.resolve("looklike.txt"), body);
            ReadFileTool tool = readTool(workspace);
            ToolUseContext ctx = ctxWithCwd(workspace);
            AgentToolResult<?> result = tool.execute(
                readToolUse(workspace.resolve("looklike.txt").toString()), ctx);

            String rendered = rendered(tool, result);
            assertThat(rendered).as("首行带行号前缀 ⇒ 不满足 freshness 前缀的 startsWith 条件")
                .startsWith("1\t<system-reminder>looks like a prefix</system-reminder>");
            assertSameBytes(ReadFileStateReplay.reverseRender(rendered),
                cachedRaw(ctx, workspace, "looklike.txt"),
                "行号前缀保护了正文首行的 reminder-lookalike ⇒ 逐字节可逆");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试用例 (b)：merge = newer-wins（活表更新不被压回 · 历史更新必须覆盖）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("用例 (b) · merge 语义 = s6r 的 newer-timestamp-wins（双向钉死）")
    class MergeSemantics {

        private static final String SESSION = "sess-replay-merge";

        @Test
        @DisplayName("活表更新的条目**不被压回**（历史更旧⇒跳过）；历史更新的条目**必须覆盖**（同用例两侧对照）")
        void newerWins_bothDirections(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();

            Path livePath = workspace.resolve("live.txt");
            Path histNewerPath = workspace.resolve("hist-newer.txt");
            Files.writeString(livePath, "LIVE-CONTENT\n");
            Files.writeString(histNewerPath, "HIST-NEWER-CONTENT\n");

            long now = System.currentTimeMillis();
            long liveTs = now + 10_000L;          // 活表那条「更新」
            String liveKey = keyOf(workspace, "live.txt");
            String histNewerKey = keyOf(workspace, "hist-newer.txt");
            target.set(liveKey, new ToolUseContext.ReadState(liveTs, null, null, false, "LIVE-CONTENT\n", false));
            target.set(histNewerKey, new ToolUseContext.ReadState(now - 10_000L, null, null, false, "OLD\n", false));

            // 历史里的两条 Read（时间戳：live.txt 那条更旧；hist-newer.txt 那条更新）
            OffsetDateTime olderTs = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(now - 60_000L), ZoneOffset.UTC);
            OffsetDateTime newerTs = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(now + 60_000L), ZoneOffset.UTC);
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-1", "Read", args(workspace.resolve("live.txt").toString()), workspace, olderTs),
                toolResult("t1", "tc-1", "1\tHISTORICAL-OLD\n", olderTs, workspace),
                assistantToolUse("a2", "tc-2", "Read", args(workspace.resolve("hist-newer.txt").toString()), workspace, newerTs),
                toolResult("t2", "tc-2", "1\tHIST-NEWER-CONTENT\n", newerTs, workspace));

            ReadFileStateReplay.Stats stats = ReadFileStateReplay.merge(
                SESSION, transcript, workspace.toString(), target);

            // ① 活表更新的条目必须保住（「只补缺失」以外的写法则在此 RED）
            assertThat(target.get(liveKey).content())
                .as("活表 timestamp 更大 ⇒ 历史条目不得压回（s6r 的 o.timestamp > s.timestamp 反向）")
                .isEqualTo("LIVE-CONTENT\n");
            assertThat(target.get(liveKey).mtimeMillis())
                .as("活表 mtime 必须原封不动").isEqualTo(liveTs);

            // ② 历史更新的条目必须覆盖（「只补缺失」在此 RED —— 但它不是缺失，是覆盖）
            assertThat(target.get(histNewerKey).content())
                .as("历史 timestamp 更大 ⇒ 必须覆盖活表旧条目").isEqualTo("HIST-NEWER-CONTENT\n");
            assertThat(target.get(histNewerKey).mtimeMillis()).isEqualTo(newerTs.toInstant().toEpochMilli());

            assertThat(stats.skippedOlder()).as("方向①计入 skippedOlder").isEqualTo(1);
            assertThat(stats.mergedOverwrite()).as("方向②计入 mergedOverwrite").isEqualTo(1);
            assertThat(stats.readEntries()).as("两条都从 Read 支派生").isEqualTo(2);
        }

        @Test
        @DisplayName("同刻（历史 timestamp == 活表 mtime）⇒ 保活表（s6r 是严格 >）")
        void equalTimestamp_keepsLiveEntry(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Files.writeString(workspace.resolve("eq.txt"), "DISK\n");
            long ts = System.currentTimeMillis();
            String key = keyOf(workspace, "eq.txt");
            target.set(key, new ToolUseContext.ReadState(ts, null, null, false, "LIVE\n", false));

            OffsetDateTime same = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), ZoneOffset.UTC);
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-1", "Read", args(workspace.resolve("eq.txt").toString()), workspace, same),
                toolResult("t1", "tc-1", "1\tHISTORICAL\n", same, workspace));

            ReadFileStateReplay.Stats stats = ReadFileStateReplay.merge(
                SESSION, transcript, workspace.toString(), target);

            assertThat(target.get(key).content()).as("同刻保活表（严格大于才覆盖）").isEqualTo("LIVE\n");
            assertThat(stats.skippedOlder()).isEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试用例 (c)：Read 派生不打标 / Edit 派生必须打标（两条独立断言）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("用例 (c) · contentNotInModelContext：Read 派生=false（模型看过）/ Edit 派生=true（现读盘）")
    class DerivedFlag {

        private static final String SESSION = "sess-replay-flag";

        @Test
        @DisplayName("Read 派生条目不置标记（模型确实看过这份内容）")
        void readDerivedEntry_isNotMarked(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            String renderedBody = "1\talpha\n2\tbeta";       // 落库形态（带行号，无 reminder 后缀）
            Path p = workspace.resolve("r.txt");
            Files.writeString(p, "alpha\nbeta");

            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-r", "Read", args(p.toString()), workspace, ts),
                toolResult("t1", "tc-r", renderedBody, ts, workspace));

            ReadFileStateReplay.merge(SESSION, transcript, workspace.toString(), target);

            ToolUseContext.ReadState st = target.get(keyOf(workspace, "r.txt"));
            assertThat(st).as("Read 派生必须落表").isNotNull();
            assertThat(st.content()).as("反渲染后 = raw").isEqualTo("alpha\nbeta");
            assertThat(st.contentNotInModelContext())
                .as("Read 派生**不得**打标 —— 模型确实在上下文里看过这份内容")
                .isFalse();
            assertThat(st.isPartialView()).as("Read 路径 isPartialView 恒 false（与标记是两个维度）").isFalse();
        }

        @Test
        @DisplayName("Edit 派生条目必须置标记 + content=盘上内容 + timestamp=盘上 mtime")
        void editDerivedEntry_isMarked(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Path p = workspace.resolve("e.txt");
            Files.writeString(p, "POST-EDIT-DISK-CONTENT\n");
            long diskMtime = Files.getLastModifiedTime(p).toMillis();

            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-e", "Edit", args(p.toString()), workspace, ts),
                toolResult("t1", "tc-e", "The file has been updated successfully.", ts, workspace));

            ReadFileStateReplay.merge(SESSION, transcript, workspace.toString(), target);

            ToolUseContext.ReadState st = target.get(keyOf(workspace, "e.txt"));
            assertThat(st).as("Edit 派生必须落表").isNotNull();
            assertThat(st.contentNotInModelContext())
                .as("Edit 派生**必须**打标 —— content 取自磁盘、模型没看过（对齐 aHt 的 contentNotInModelContext:!0）")
                .isTrue();
            assertThat(st.content()).as("content 必须是盘上现读内容").isEqualTo("POST-EDIT-DISK-CONTENT\n");
            assertThat(st.mtimeMillis())
                .as("Edit 支 timestamp = 盘上 mtime（对齐 aHt 的 ZZe(de)，**不是**消息时间戳）")
                .isEqualTo(diskMtime);
            assertThat(st.offset()).as("offset/limit 归 null（Edit/Write 写回形态，对齐 aHt offset:void 0）").isNull();
            assertThat(st.limit()).isNull();
        }

        @Test
        @DisplayName("Write 派生 = 入参 content（不读盘）+ 不打标（对齐 aHt：content:Lb(X.content)）")
        void writeDerivedEntry_usesInputContent_unmarked(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Path p = workspace.resolve("w.txt");
            Files.writeString(p, "OLD-ON-DISK\n");

            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-w", "Write",
                    writeArgs(p.toString(), "BRAND-NEW-CONTENT"), workspace, ts),
                toolResult("t1", "tc-w", "File created successfully at: " + p, ts, workspace));

            ReadFileStateReplay.merge(SESSION, transcript, workspace.toString(), target);

            ToolUseContext.ReadState st = target.get(keyOf(workspace, "w.txt"));
            assertThat(st).as("Write 派生必须落表").isNotNull();
            assertThat(st.content()).as("content = 入参 content（不是盘上旧内容）").isEqualTo("BRAND-NEW-CONTENT");
            assertThat(st.contentNotInModelContext()).as("Write 派生不打标（模型自己写的，内容在上下文里）").isFalse();
        }

        @Test
        @DisplayName("窗口读（带 offset/limit）不参与 replay；file_unchanged stub 与 is_error 结果都不产 entry")
        void rangedDedupStubAndError_areSkipped(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Path ranged = workspace.resolve("ranged.txt");
            Path stub = workspace.resolve("stub.txt");
            Path err = workspace.resolve("err.txt");
            Files.writeString(ranged, "r\n");
            Files.writeString(stub, "s\n");
            Files.writeString(err, "e\n");
            OffsetDateTime ts = OffsetDateTime.now();

            List<ChatMessageDto> transcript = List.of(
                // 窗口读：对齐 CC 2.1.88「Ranged reads are not added to the cache」
                assistantToolUse("a1", "tc-ranged", "Read", rangedArgs(ranged.toString(), 10, 20), workspace, ts),
                toolResult("t1", "tc-ranged", "10\tx\n", ts, workspace),
                // file_unchanged stub：内容不是文件内容
                assistantToolUse("a2", "tc-stub", "Read", args(stub.toString()), workspace, ts),
                toolResult("t2", "tc-stub", ReadFileTool.FILE_UNCHANGED_STUB, ts, workspace),
                // is_error 结果（is_error 打在 assistant 的 tool_calls 侧 —— 真源）
                assistantToolUse("a3", "tc-err", "Read", args(err.toString()), workspace, ts, true),
                toolResult("t3", "tc-err", "File does not exist.", ts, workspace));

            ReadFileStateReplay.Stats stats = ReadFileStateReplay.merge(
                SESSION, transcript, workspace.toString(), target);

            assertThat(target.size()).as("三类都必须零落地").isZero();
            assertThat(stats.readEntries()).as("零条 Read 派生").isZero();
            assertThat(stats.readSkippedRanged()).as("窗口读计数").isEqualTo(1);
            assertThat(stats.readSkippedStubOrError()).as("stub + 错误两条计入跳过").isEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试用例 (f)：真实形态 —— 历史里只有 Edit（无 Read）⇒ replay 灌 entry ⇒ 门禁放行
    // ══════════════════════════════════════════════════════════════════════

    /**
     * [批 rfs-replay-3b · 2 次修订] 把「replay 的 <b>Edit 派生</b>条目（contentNotInModelContext=true）
     * ⇒ Edit/Write 门禁<b>放行</b>」这一新行为钉在<b>真实形态</b>上（⛔ 不是合成 entry）。
     *
     * <p><b>WHY（CLAUDE.md 规则九 · 意图 = 对齐 CC）</b>：跨进程 resume 后，历史里可能只有「上一进程
     * Edit 过」而无任何 Read。replay 会为这种文件灌一条 content=盘上现读、打标 true 的 entry。
     * 对齐目标 CC <b>2.1.278</b> 的门禁条件是 {@code if(!Ee||Ee.isPartialView)}（Edit，exe off 206605650）
     * / {@code if(!he||he.isPartialView)}（Write，exe off 203520944）—— <b>不消费</b>该字段 ⇒ 放行。
     * 本用例走<b>真实 {@link ReadFileStateReplay#merge} + 真实 {@link EditFileTool}/{@link WriteFileTool}
     * 门禁</b>（不是手工 set 一条 entry），任一侧把该字段当判据即 RED。
     */
    @Nested
    @DisplayName("用例 (f) · 真实形态：历史里只有 Edit（无 Read）⇒ replay 灌 entry ⇒ Edit/Write 门禁放行（对齐 CC 2.1.278）")
    class ReplayDerivedEditEntryPassesGate {

        private static final String SESSION = "sess-replay-gate";

        @Test
        @DisplayName("Edit 派生条目（打标=true）⇒ EditFileTool 门禁放行（errorCode 非 6）")
        void replayEditDerived_passesEditGate(@TempDir Path workspace) throws Exception {
            Path p = workspace.resolve("e.txt");
            Files.writeString(p, "hello\n");
            ToolUseContext ctx = ctxWithCwd(workspace);

            // 历史里**只有一条 Edit**（没有任何 Read 派生）
            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-e", "Edit", args(p.toString()), workspace, ts),
                toolResult("t1", "tc-e", "The file has been updated successfully.", ts, workspace));

            ReadFileStateReplay.Stats stats = ReadFileStateReplay.merge(
                SESSION, transcript, workspace.toString(), ctx.readFileState());
            assertThat(stats.editEntries()).as("Edit 派生一条").isEqualTo(1);

            String key = keyOf(workspace, "e.txt");
            assertThat(ctx.readFileState().get(key))
                .as("replay 必须灌出 Edit 派生 entry（真实路径）").isNotNull();
            assertThat(ctx.readFileState().get(key).contentNotInModelContext())
                .as("该 entry 正是「内容取自磁盘、不在模型上下文」形态（打标=true）").isTrue();

            ToolUseBlock call = new ToolUseBlock("call-edit", "edit_file",
                editArgs(p.toString(), "hello", "CHANGED"));
            Tool.ValidationResult vr = new EditFileTool(new PathGuard(workspace))
                .validateInput(call.input(), ctx);

            assertThat(vr.ok())
                .as("CC 2.1.278 门禁不消费 contentNotInModelContext ⇒ 放行（errorCode=%s）", vr.errorCode())
                .isTrue();
        }

        @Test
        @DisplayName("同一真实形态 ⇒ WriteFileTool 门禁放行（errorCode 非 2）")
        void replayEditDerived_passesWriteGate(@TempDir Path workspace) throws Exception {
            Path p = workspace.resolve("w.txt");
            Files.writeString(p, "hello\n");
            ToolUseContext ctx = ctxWithCwd(workspace);

            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-e", "Edit", args(p.toString()), workspace, ts),
                toolResult("t1", "tc-e", "The file has been updated successfully.", ts, workspace));

            ReadFileStateReplay.merge(SESSION, transcript, workspace.toString(), ctx.readFileState());
            String key = keyOf(workspace, "w.txt");
            assertThat(ctx.readFileState().get(key)).isNotNull();
            assertThat(ctx.readFileState().get(key).contentNotInModelContext()).isTrue();

            ToolUseBlock call = new ToolUseBlock("call-write", "write_file",
                writeArgsNode(p.toString(), "OVERWRITE"));
            Tool.ValidationResult vr = new WriteFileTool(new PathGuard(workspace))
                .validateInput(call.input(), ctx);

            assertThat(vr.ok())
                .as("CC 2.1.278 Write 门禁不消费 contentNotInModelContext ⇒ 放行（errorCode=%s）", vr.errorCode())
                .isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 测试用例 (e)：软降级（不抛异常、零条落地）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("用例 (e) · 软降级：历史缺失 / 目标表 null / 无 cwd ⇒ 不抛异常 + 零落地")
    class SoftDegrade {

        @Test
        @DisplayName("历史为 null / 空列表 ⇒ 零落地且不抛（对齐 ResumeService.restoreSessionCwd 的软降级形态）")
        void nullOrEmptyTranscript_softDegrades(@TempDir Path workspace) {
            FileStateCache target = ToolUseContext.createFileStateCache();
            assertThatCode(() -> {
                ReadFileStateReplay.Stats s1 = ReadFileStateReplay.merge(
                    "sess-x", null, workspace.toString(), target);
                assertThat(s1.applied()).isZero();
                ReadFileStateReplay.Stats s2 = ReadFileStateReplay.merge(
                    "sess-x", List.of(), workspace.toString(), target);
                assertThat(s2.applied()).isZero();
                assertThat(s2.scannedMessages()).isZero();
            }).as("软降级不得抛异常").doesNotThrowAnyException();
            assertThat(target.size()).isZero();
        }

        @Test
        @DisplayName("目标表 null（无会话标识 ⇒ forSession 返回 null）⇒ 零落地且不抛")
        void nullTarget_softDegrades(@TempDir Path workspace) {
            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-1", "Read", args(workspace.resolve("x.txt").toString()), workspace, ts),
                toolResult("t1", "tc-1", "1\tx\n", ts, workspace));
            assertThatCode(() -> {
                ReadFileStateReplay.Stats s = ReadFileStateReplay.merge(null, transcript, workspace.toString(), null);
                assertThat(s.applied()).isZero();
            }).as("无会话标识路径不得抛异常").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("消息无 cwd 且兜底 cwd 也缺 ⇒ 该条跳过（不猜基准），其余条目不受影响")
        void missingCwd_skipsOnlyThatEntry(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Path p = workspace.resolve("has-cwd.txt");
            Files.writeString(p, "h\n");
            OffsetDateTime ts = OffsetDateTime.now();

            List<ChatMessageDto> transcript = List.of(
                // 无 cwd 的消息 + 相对路径 ⇒ 无法定 key ⇒ 跳过
                assistantToolUse("a1", "tc-no-cwd", "Read", args("relative-needs-cwd.txt"), null, ts),
                toolResult("t1", "tc-no-cwd", "1\tx\n", ts, null),
                // 有 cwd 的消息 ⇒ 正常落地
                assistantToolUse("a2", "tc-ok", "Read", args(p.toString()), workspace, ts),
                toolResult("t2", "tc-ok", "1\th", ts, workspace));

            ReadFileStateReplay.Stats stats = ReadFileStateReplay.merge(
                "sess-cwd", transcript, null, target);

            assertThat(target.size()).as("只落地有 cwd 的那条").isEqualTo(1);
            assertThat(target.get(keyOf(workspace, "has-cwd.txt"))).isNotNull();
            assertThat(stats.readEntries()).as("无 cwd 的条目被跳过，未计入派生").isEqualTo(1);
        }

        @Test
        @DisplayName("Edit 指向已删除文件 ⇒ 跳过该条（现读盘失败不抛）")
        void editDiskMissing_skipsEntry(@TempDir Path workspace) throws Exception {
            FileStateCache target = ToolUseContext.createFileStateCache();
            Path gone = workspace.resolve("gone.txt");
            OffsetDateTime ts = OffsetDateTime.now();
            List<ChatMessageDto> transcript = List.of(
                assistantToolUse("a1", "tc-e", "Edit", args(gone.toString()), workspace, ts),
                toolResult("t1", "tc-e", "ok", ts, workspace));

            assertThatCode(() -> {
                ReadFileStateReplay.Stats s = ReadFileStateReplay.merge(
                    "sess-gone", transcript, workspace.toString(), target);
                assertThat(s.editSkippedDiskUnreadable())
                    .as("盘上文件已删 ⇒ Edit 支现读盘失败计入跳过").isEqualTo(1);
                assertThat(s.editEntries()).as("读盘失败的 Edit 不计入派生").isZero();
            }).as("盘上文件已删 ⇒ 跳过且不抛").doesNotThrowAnyException();

            assertThat(target.size()).as("Edit 读盘失败 ⇒ 零落地").isZero();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 驱逐行为实测：「5000 条 × 全文」vs 25MB 字节上限（本批「存全文」决策的可测边界）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("驱逐实测 · 存全文 + 双限 LRU：25MB 字节限先于 5000 条目限生效（CC 2.1.278 LC=5000/T=26214400 同款）")
    class EvictionObservation {

        @Test
        @DisplayName("每条约 20KB 全文 → 稳定驻留 1280 条（=25MB/20KB），LRU 端被逐出；据此换算等效容量")
        void fullTextEntries_byteLimitBindsBeforeEntryLimit() {
            FileStateCache cache = ToolUseContext.createFileStateCache();   // 5000 条 + 25MB
            String body = "x".repeat(20_480);                                // 恰好 20480 B/条
            for (int i = 0; i < 2_000; i++) {
                cache.set("/wk/f" + i + ".txt",
                    new ToolUseContext.ReadState(i, null, null, false, body, false));
            }
            // 25MiB / 20KiB = 26214400 / 20480 = 1280 条整
            assertThat(cache.size())
                .as("字节上限先于条目上限生效 ⇒ 驻留 = 1280 条（不是 2000/5000）").isEqualTo(1280);
            assertThat(cache.max()).as("条目上限仍是 5000（容量本批未改）").isEqualTo(5000);
            assertThat(cache.maxSize()).isEqualTo(25L * 1024 * 1024);
            assertThat(cache.has("/wk/f0.txt")).as("LRU 最旧端（f0）已被逐出").isFalse();
            assertThat(cache.has("/wk/f1999.txt")).as("最新插入（f1999）仍在").isTrue();
            assertThat(cache.has("/wk/f719.txt")).as("逐出边界：f0..f719 出局（2000-1280=720）").isFalse();
            assertThat(cache.has("/wk/f720.txt")).as("逐出边界：f720 起留存").isTrue();
            assertThat(cache.calculatedSize())
                .as("驻留字节必须 ≤ 25MB").isLessThanOrEqualTo(25L * 1024 * 1024);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    private static String keyOf(Path workspace, String relPath) {
        return ToolUseContext.keyForReadFileState(new PathGuard(workspace), relPath);
    }

    private static String args(String filePath) {
        ObjectNode n = JSON.createObjectNode();
        n.put("file_path", filePath);
        return n.toString();
    }

    private static String rangedArgs(String filePath, int offset, int limit) {
        ObjectNode n = JSON.createObjectNode();
        n.put("file_path", filePath);
        n.put("offset", offset);
        n.put("limit", limit);
        return n.toString();
    }

    private static String writeArgs(String filePath, String content) {
        ObjectNode n = JSON.createObjectNode();
        n.put("file_path", filePath);
        n.put("content", content);
        return n.toString();
    }

    /** Edit 入参节点（供用例 (f) 驱动真实 EditFileTool 门禁）。 */
    private static ObjectNode editArgs(String filePath, String oldString, String newString) {
        ObjectNode n = JSON.createObjectNode();
        n.put("file_path", filePath);
        n.put("old_string", oldString);
        n.put("new_string", newString);
        return n;
    }

    /** Write 入参节点（供用例 (f) 驱动真实 WriteFileTool 门禁）。 */
    private static ObjectNode writeArgsNode(String filePath, String content) {
        ObjectNode n = JSON.createObjectNode();
        n.put("file_path", filePath);
        n.put("content", content);
        return n;
    }

    private static ChatMessageDto assistantToolUse(String id, String toolCallId, String toolName,
                                                   String argumentsJson, Path cwd, OffsetDateTime ts) {
        return assistantToolUse(id, toolCallId, toolName, argumentsJson, cwd, ts, false);
    }

    /**
     * assistant + 单个 tool_use。
     *
     * <p>⚠️ is_error 的真源在 <b>assistant 消息的 {@link ToolCallDto}</b> 上
     * （{@code tool_calls.is_error}）—— messages 表不持久化消息级 is_error
     * （{@code MessageService.toDto} 恒 false）⇒ 反例必须打在 tool_use 侧。
     */
    private static ChatMessageDto assistantToolUse(String id, String toolCallId, String toolName,
                                                   String argumentsJson, Path cwd, OffsetDateTime ts,
                                                   boolean toolCallIsError) {
        ChatMessageDto dto = new ChatMessageDto(id, "sess-x", Role.assistant, null, "", null,
            List.of(new ToolCallDto(toolCallId, toolName, argumentsJson, null, toolCallIsError)),
            FinishReason.stop, null, null, "刚刚", ts,
            null, null, null, List.of(), List.of(), null, false, false, null);
        return dto.withCwd(cwd == null ? null : cwd.toString());
    }

    private static ChatMessageDto toolResult(String id, String toolCallId, String content,
                                            OffsetDateTime ts, Path cwd) {
        ChatMessageDto dto = new ChatMessageDto(id, "sess-x", Role.tool, null, content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", ts,
            toolCallId, null, null, List.of(), List.of(), null, false, false, null);
        return dto.withCwd(cwd == null ? null : cwd.toString());
    }
}

package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.attachment.ChangedFilesDetector;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.context.ClaudemdMemoryType;
import com.nexusai.application.agent.context.MemoryFileInfo;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.FileReadingLimits;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>步骤 7 · 投递层</b>意图测试（对齐 CC {@code getChangedFiles}，utils/attachments.ts:2063-2161
 * + {@code REPL.tsx:3797-3818} + {@code maybe('changed_files', ...)} :871）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 验证意图而非行为）</b>：本步的对齐点不是「有消息被追加」，而是
 * 「<b>头部冻结 ≠ 什么都看不到</b>」这条取舍的<b>两侧同时对</b>：
 * <ol>
 *   <li><b>头部字节不变</b>：变更提示<b>绝不可</b>回写 {@code messages[0]}（回写 = 重新生成整段前缀
 *       ⇒ 前缀缓存修复被这一下抹平）；</li>
 *   <li><b>尾部必须真的看到</b>：文件在磁盘上变了（判据 {@code mtime > 记录时间戳}）⇒ 必须以一条
 *       <b>尾部真实消息</b>带变更行告知模型（CC {@code yield attachment; toolResults.push}，
 *       query.ts:1580-1588），否则模型永久停在会话开始时的旧 CLAUDE.md。</li>
 * </ol>
 * 另有四条 CC 状态机不变量必须钉住（任一条反了都会让通道「看起来接了、实际不生效」）：
 * ① {@code mtime} 未变 ⇒ <b>一条都不追加</b>；② {@code mtime} 变了但内容一致（touched but not
 * modified，:2112-2115）⇒ 不产出附件（但写回 readFileState）；③ <b>{@code offset/limit} 已设的
 * entry 必须跳过</b>（:2076-2078）—— 这条正是启动登记必须留空 offset/limit 才生效的原因；
 * ④ 投递后写回新时间戳 ⇒ 同一变更<b>不重复投递</b>。
 *
 * <p>纯单测（⛔ 无 Spring / 无 @SpringBootTest）：真临时文件 + 真 FileStateCache，判据全靠磁盘 mtime。
 */
@DisplayName("步骤 7 · 变更文件尾部投递（头部冻结 + 尾部告知变更行）")
class ChangedFilesTailDeliveryTest {

    /** 基线 mtime（判据基准：记录时间戳）。 */
    private static final long BASE_MTIME = 1_700_000_000_000L;

    private static String newSessionId() {
        return "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 生产同款 base TUC 最小形态（readFileState 走 compact ctor 兜底的新 FileStateCache）。 */
    private static ToolUseContext tuc(String sessionId) {
        return new ToolUseContext(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    /** 写文件并把 mtime 钉到确定值（⛔ 不靠 sleep —— 判据必须是确定值而非时序运气）。 */
    private static Path writeWithMtime(Path dir, String name, String content, long mtimeMillis)
            throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtimeMillis));
        return f;
    }

    /** 造一条「用户消息」当作 messages[0] 的近似（用于验证头部未被回写）。 */
    private static ChatMessageDto userMsg(String sessionId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.user, "user",
            content, null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false);
    }

    /** 把「上次读过」的 entry 写进 readFileState（key = 归一化绝对路径，同 keyForReadFileState 语义）。 */
    private static String seedReadEntry(FileStateCache cache, Path file, String contentAtReadTime,
                                        long recordedMtime, Integer offset, Integer limit) {
        String key = file.toAbsolutePath().normalize().toString();
        cache.set(key, new ToolUseContext.ReadState(recordedMtime, offset, limit, false, contentAtReadTime));
        return key;
    }

    // ════════════════════════════ 不变量 ① · mtime 变 ⇒ 尾部追加 ════════════════════════════

    @Test
    @DisplayName("CLAUDE.md 在 run 内被改动 ⇒ 尾部追加 isMeta 消息（含变更行），messages[0] 逐字不变")
    void fileChanged_appendsTailMessage_headUntouched(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        // 1. 启动时的 CLAUDE.md（= 进头部的字节）
        Path claudeMd = writeWithMtime(dir, "CLAUDE.md", "# 规则\n- 旧规则 A\n", BASE_MTIME);
        String headContent = Files.readString(claudeMd);

        AgentState state = new AgentState("sys", sessionId, null);
        ChatMessageDto head = userMsg(sessionId, "第一条请求");
        state.appendMessage(head);
        ToolUseContext tuc = tuc(sessionId);
        // 2. 启动登记（CC REPL.tsx:3810-3816：offset/limit = undefined ⇒ 变更检测对它生效）
        seedReadEntry(tuc.readFileState(), claudeMd, headContent, BASE_MTIME, null, null);
        int before = state.rawMessages().size();

        // 3. 磁盘上真的变了（mtime 前进 10s + 内容变化）
        Files.writeString(claudeMd, "# 规则\n- 旧规则 A\n- 新规则 B\n");
        Files.setLastModifiedTime(claudeMd, FileTime.fromMillis(BASE_MTIME + 10_000));

        AgentLoopContext.maybeEmitChangedFiles(state, tuc);

        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after).as("必须追加一条（把变更告知模型）").hasSize(before + 1);
        assertThat(after.get(0))
            .as("⛔ 头部（messages[0]）不得被回写 —— 回写即重新生成整段前缀，缓存修复被抹平")
            .isSameAs(head);

        ChatMessageDto tail = after.get(after.size() - 1);
        assertThat(tail).as("必须是尾部追加（不插队、不改写前缀）").isNotSameAs(head);
        assertThat(tail.role()).isEqualTo(Role.user);
        assertThat(tail.isMeta()).as("isMeta ⇒ 前端隐藏、不污染用户转录（CC createUserMessage isMeta:true）").isTrue();
        assertThat(tail.author()).as("attachment 通道产出（与 CC AttachmentMessage 同契约）").isEqualTo("attachment");
        assertThat(tail.subtype()).as("subtype 与 CC attachment.type 同名").isEqualTo("edited_text_file");
        assertThat(tail.content())
            .as("渲染文案对齐 CC messages.ts:3538-3543")
            .startsWith("<system-reminder>")
            .contains("Note: " + claudeMd.toAbsolutePath().normalize() + " was modified")
            .contains("Here are the relevant changes (shown with line numbers):")
            // ⭐ 变更行必须真的在里面（否则「投递了」不过是空壳）
            .contains("新规则 B");
    }

    // ════════════════════════════ 不变量 ② · mtime 未变 ⇒ 零追加 ════════════════════════════

    @Test
    @DisplayName("mtime 未变 ⇒ 一条都不追加（判据边界：mtime == 记录时间戳 时即使内容不同也必须跳过）")
    void fileUnchanged_appendsNothing(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        Path claudeMd = writeWithMtime(dir, "CLAUDE.md", "# 规则\n- A\n", BASE_MTIME);
        String content = Files.readString(claudeMd);

        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext tuc = tuc(sessionId);
        String key = seedReadEntry(tuc.readFileState(), claudeMd, content, BASE_MTIME, null, null);
        int before = state.rawMessages().size();

        // ⭐ 判据边界的**承重**用例：磁盘内容**真的变了**，但 mtime 与记录时间戳**相等**
        //   （文件系统时间戳粒度 / 同毫秒内两次写 / 工具回填 mtime 都能造成）。
        //   CC 的判据是 `mtime > fileState.timestamp`（attachments.ts:2088-2090）⇒ 必须**跳过**。
        //   WHY 这条必须有：若判据写成 `mtime >= timestamp` 或干脆不看 mtime 只比内容，
        //   本用例会红；而「内容一致 ⇒ 片段为空 ⇒ 不投递」那种弱用例<b>抓不到</b>判据写错
        //   （实测：把 `mtime <= recorded` 改成 `mtime < recorded`，弱用例仍全绿 —— 假绿）。
        Files.writeString(claudeMd, "# 规则\n- A\n- 悄悄改的 B\n");
        Files.setLastModifiedTime(claudeMd, FileTime.fromMillis(BASE_MTIME));   // mtime 不动

        // 连续多轮都不该有动作
        AgentLoopContext.maybeEmitChangedFiles(state, tuc);
        AgentLoopContext.maybeEmitChangedFiles(state, tuc);

        assertThat(state.rawMessages())
            .as("mtime == 记录时间戳 ⇒ 判据 `mtime > timestamp` 不成立 ⇒ 零投递（即便磁盘内容不同 —— 这就是 CC 的判据，不是『内容比较』）")
            .hasSize(before);
        assertThat(tuc.readFileState().get(key).content())
            .as("跳过发生在读盘之前 ⇒ entry 不得被改写（否则会把判据偷换成『内容比较』）")
            .isEqualTo(content);
    }

    // ════════════════════════════ 不变量 ③ · touched but not modified ════════════════════════════

    @Test
    @DisplayName("mtime 变了但内容一致（touch/存盘重写）⇒ 不产出附件，但 readFileState 必须落位新时间戳")
    void touchedButNotModified_noAttachment_butTimestampAdvanced(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        Path claudeMd = writeWithMtime(dir, "CLAUDE.md", "# 规则\n- A\n", BASE_MTIME);
        String content = Files.readString(claudeMd);

        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext tuc = tuc(sessionId);
        String key = seedReadEntry(tuc.readFileState(), claudeMd, content, BASE_MTIME, null, null);
        int before = state.rawMessages().size();

        // 内容不动，只把 mtime 推后（编辑器原子保存 / 格式化器重写的常见形态）
        long newMtime = BASE_MTIME + 5_000;
        Files.setLastModifiedTime(claudeMd, FileTime.fromMillis(newMtime));

        AgentLoopContext.maybeEmitChangedFiles(state, tuc);

        assertThat(state.rawMessages())
            .as("内容一致（snippet 为空）⇒ 不产出附件（CC :2112-2115 // File was touched but not modified）")
            .hasSize(before);
        assertThat(tuc.readFileState().get(key).mtimeMillis())
            .as("但时间戳必须落位（CC :2104 先写回 readFileState 再判片段）⇒ 下一轮不再重复读盘")
            .isEqualTo(newMtime);
    }

    // ════════════════════════════ 不变量 ④ · 投递后不重复投递 ════════════════════════════

    @Test
    @DisplayName("投递后写回新时间戳 ⇒ 同一变更只投一次（同轮多次迭代不重复刷屏）")
    void deliveredChange_isNotDeliveredAgain(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        Path claudeMd = writeWithMtime(dir, "CLAUDE.md", "# 规则\n- A\n", BASE_MTIME);

        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext tuc = tuc(sessionId);
        seedReadEntry(tuc.readFileState(), claudeMd, Files.readString(claudeMd), BASE_MTIME, null, null);

        Files.writeString(claudeMd, "# 规则\n- A\n- B\n");
        Files.setLastModifiedTime(claudeMd, FileTime.fromMillis(BASE_MTIME + 10_000));

        AgentLoopContext.maybeEmitChangedFiles(state, tuc);
        int afterFirst = state.rawMessages().size();
        AgentLoopContext.maybeEmitChangedFiles(state, tuc);

        assertThat(state.rawMessages())
            .as("第二轮判据 mtime > 记录时间戳 已不成立（记录已更新为文件当前 mtime）⇒ 不重复投递")
            .hasSize(afterFirst);
    }

    // ═══════════════ 不变量 ⑤ · offset/limit 已设 ⇒ 跳过（启动登记必须留空的那一格）═══════════════

    @Test
    @DisplayName("窗口 entry（offset/limit 已设）⇒ 跳过：这正是启动登记必须留空 offset/limit 的原因")
    void windowEntry_isSkipped(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        Path f = writeWithMtime(dir, "big.txt", "line1\nline2\n", BASE_MTIME);

        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext tuc = tuc(sessionId);
        // 模型用 Read(offset=1, limit=50) 读过的 entry（CC Read 存 offset/limit ⇒ getChangedFiles 跳过）
        seedReadEntry(tuc.readFileState(), f, "line1\nline2\n", BASE_MTIME, 1, 50);
        int before = state.rawMessages().size();

        Files.writeString(f, "line1\nline2\nline3\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(BASE_MTIME + 10_000));

        AgentLoopContext.maybeEmitChangedFiles(state, tuc);

        assertThat(state.rawMessages())
            .as("offset 已设 ⇒ CC :2076-2078 跳过（窗口视图 diff 会误导）")
            .hasSize(before);
    }

    // ═════════════════ 不变量 ⑥ · 启动登记必须写 offset/limit = null（承重格）═════════════════

    @Test
    @DisplayName("registerMemoryFilesBaseline：offset/limit 必须为 null（写成 1 会让根 CLAUDE.md 永远检测不到）")
    void baselineRegistration_leavesOffsetAndLimitNull() {
        FileStateCache cache = ToolUseContext.createFileStateCache();
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "nexusai-baseline-probe", "CLAUDE.md");
        MemoryFileInfo info = MemoryFileInfo.of(file.toString(), ClaudemdMemoryType.PROJECT,
            "# 项目指令", null);

        // 最小引擎夹具（注册基线只用 log + key 归一化，不触扫描/磁盘）
        String root = System.getProperty("java.io.tmpdir");
        com.nexusai.application.agent.memory.AutoMemPaths amp = new com.nexusai.application.agent.memory.AutoMemPaths(
            () -> root, () -> root, () -> root + java.io.File.separator, () -> null);
        com.nexusai.application.agent.memory.MemoryFileDetection detection =
            new com.nexusai.application.agent.memory.MemoryFileDetection(
                amp, () -> root, () -> true, () -> false, () -> false);
        ClaudemdEngine engine = new ClaudemdEngine(amp, detection,
            sessionId -> root, () -> true, () -> true, () -> true, () -> false, () -> List.of());

        int seeded = engine.registerMemoryFilesBaseline(List.of(info), cache);

        assertThat(seeded).as("必须真的登记（0 条 = 该通道对启动记忆文件完全不生效）").isEqualTo(1);
        ToolUseContext.ReadState entry = cache.get(
            file.toAbsolutePath().normalize().toString());
        assertThat(entry).as("entry 必须落在 readFileState 里").isNotNull();
        assertThat(entry.offset())
            .as("⭐ offset 必须 null —— ChangedFilesDetector 会跳过 offset 非 null 的 entry"
                + "（CC attachments.ts:2076-2078）⇒ 写成 1 等于把本步骤对根 CLAUDE.md 的能力关掉")
            .isNull();
        assertThat(entry.limit()).as("⭐ limit 同理必须 null").isNull();
        assertThat(entry.content()).as("内容基线 = 磁盘/注入内容（diff 基线）").isEqualTo("# 项目指令");
        assertThat(entry.mtimeMillis())
            .as("登记时刻（CC REPL.tsx:3812 timestamp: Date.now()）").isGreaterThan(0);
    }

    // ═════════════════ 不变量 ⑦ · 片段渲染（删行被过滤 + 行号 + 8KB 截断）═════════════════

    @Test
    @DisplayName("片段渲染：只有新增/上下文行 + 行号前缀；无变更 ⇒ 空片段（不产出附件）")
    void snippetRendering_filtersDeletedLines_andNumbersLines(@TempDir Path dir) throws Exception {
        String sessionId = newSessionId();
        Path f = writeWithMtime(dir, "note.md", "keep1\nremoved\nkeep2\n", BASE_MTIME);

        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext tuc = tuc(sessionId);
        String key = seedReadEntry(tuc.readFileState(), f, "keep1\nremoved\nkeep2\n", BASE_MTIME, null, null);

        Files.writeString(f, "keep1\nkeep2\nadded\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(BASE_MTIME + 10_000));

        // 生产者单测（不经 TUC）：直接看片段形态
        List<AttachmentMessageDto> atts = ChangedFilesDetector.detectChangedFiles(
            tuc.readFileState(), null, null, FileReadingLimits.DEFAULT_MAX_SIZE_BYTES);

        assertThat(atts).hasSize(1);
        String snippet = atts.get(0).lineSelection().snippet();
        assertThat(snippet)
            .as("删除行（'-' 前缀）被过滤掉（CC filter(!startsWith('-'))）⇒ 片段里不得出现 'removed'")
            .doesNotContain("removed");
        assertThat(snippet).as("新增行必须真的在片段里（否则投递只是空壳）").contains("added");
        assertThat(snippet)
            .as("行号前缀（compact 默认 = 行号 + Tab，与 Read 输出同格式）")
            .containsPattern("[0-9]+\\t");
        // 无变更 ⇒ 空片段（② 已在 TUC 层验证；此处钉生产者的「相同则不产出」）
        List<AttachmentMessageDto> again = ChangedFilesDetector.detectChangedFiles(
            tuc.readFileState(), null, null, FileReadingLimits.DEFAULT_MAX_SIZE_BYTES);
        assertThat(again).as("刚投递过（时间戳已写回）⇒ 再检为 0").isEmpty();
        assertThat(tuc.readFileState().get(key)).isNotNull();
    }
}

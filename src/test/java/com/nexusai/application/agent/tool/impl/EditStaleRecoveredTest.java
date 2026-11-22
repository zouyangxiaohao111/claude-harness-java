package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * call 级 pre-read guard 的 staleRecovered 分支 · 对齐 CC {@code A2o}
 * （exe 2.1.328 存在 {@code staleRecovered} 串 @167336640）。
 *
 * <p><b>语义</b>：mtime 变新（看起来“过期”）但 {@code old_string} 在最新内容里仍能干净施加时，
 * CC 放行编辑并打 staleRecovered 标记；只有内容变了且编辑不再适用时才抛 sQe
 * （{@code File content has changed since it was last read…}）。
 */
@DisplayName("Edit call 级守卫 · staleRecovered（CC A2o applies 分支）")
class EditStaleRecoveredTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void declareNoDatabase() {
        SessionProjectRootTestSupport.declareNoDatabase();
    }

    @AfterEach
    void clearNoDatabase() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    private static ToolUseContext ctxFor(Path workspace) {
        String agentId = com.nexusai.application.agent.subagent.AgentContext.createAgentId();
        String sessionId = UUID.nameUUIDFromBytes(("stale-sess-" + workspace).getBytes()).toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock readCallWith(String path) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        return new ToolUseBlock("call-read", "read_file", input);
    }

    private static ToolUseBlock editCall(String path, String oldText, String newText) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("old_string", oldText);
        input.put("new_string", newText);
        return new ToolUseBlock("call-edit", "Edit", input);
    }

    private static ToolUseContext.ReadState readState(long mtime, String content) {
        return ToolUseContext.ReadState.full(mtime, content);
    }

    // ───────────────────── 判定矩阵（纯函数） ─────────────────────

    @Test
    @DisplayName("无读记录：免门 true 放行，否则 THROW_NOT_READ")
    void noReadEntry() {
        assertThat(EditFileTool.evaluateCallLevelGuard(
            100L, null, true, "a.txt", "x", "x", "y", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.OK);
        assertThat(EditFileTool.evaluateCallLevelGuard(
            100L, null, false, "a.txt", "x", "x", "y", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.THROW_NOT_READ);
    }

    @Test
    @DisplayName("mtime 未增 / 全量内容一致 → OK")
    void upToDateOrSameContent() {
        assertThat(EditFileTool.evaluateCallLevelGuard(
            100L, readState(100L, "hello"), false, "a.txt", "hello", "hello", "bye", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.OK);
        assertThat(EditFileTool.evaluateCallLevelGuard(
            200L, readState(100L, "hello"), false, "a.txt", "hello", "hello", "bye", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.OK);
    }

    @Test
    @DisplayName("mtime 变新 + 内容变 + old_string 仍唯一 → RECOVERED")
    void changedButEditStillApplies() {
        assertThat(EditFileTool.evaluateCallLevelGuard(
            200L, readState(100L, "hello"), false,
            "a.txt", "hello\nworld\n", "hello", "bye", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.RECOVERED);
    }

    @Test
    @DisplayName("mtime 变新 + 内容变 + old_string 不存在 / 多匹配 → THROW_STALE（replace_all 例外）")
    void changedAndEditNoLongerApplies() {
        // 不存在
        assertThat(EditFileTool.evaluateCallLevelGuard(
            200L, readState(100L, "hello"), false,
            "a.txt", "world\n", "hello", "bye", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.THROW_STALE);
        // 两处匹配且 replace_all=false
        assertThat(EditFileTool.evaluateCallLevelGuard(
            200L, readState(100L, "hello"), false,
            "a.txt", "hello\nhello\n", "hello", "bye", false))
            .isEqualTo(EditFileTool.CallGuardVerdict.THROW_STALE);
        // 两处匹配且 replace_all=true → 可施加
        assertThat(EditFileTool.evaluateCallLevelGuard(
            200L, readState(100L, "hello"), false,
            "a.txt", "hello\nhello\n", "hello", "bye", true))
            .isEqualTo(EditFileTool.CallGuardVerdict.RECOVERED);
    }

    // ───────────────────── 端到端（绕过 validateInput 构造 TOCTOU） ─────────────────────

    @Test
    @DisplayName("executeInternal：stale 但可施加 → 编辑成功且结果带 staleRecovered=true")
    void executesWithStaleRecoveredMarker(@TempDir Path workspace) throws Exception {
        Path f = workspace.resolve("a.txt");
        Files.writeString(f, "hello\n");
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        // 播种 read 记录（旧内容）
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);
        long oldMtime = Files.getLastModifiedTime(f).toMillis();

        // 模拟 validateInput 之后文件被外部修改（本段之外的内容变化）
        Files.writeString(f, "hello\nworld\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(oldMtime + 5_000));

        ToolResult<?> result = tool.executeInternal(editCall("a.txt", "hello", "HI"), ctx);

        assertThat(result.data()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("staleRecovered"))
            .as("mtime 变新但 old_string 仍唯一 ⇒ 放行并打标记")
            .isEqualTo(true);
        assertThat(Files.readString(f)).isEqualTo("HI\nworld\n");
    }

    @Test
    @DisplayName("executeInternal：stale 且不再适用 → 拒绝（File content has changed），文件不动")
    void rejectsWhenEditNoLongerApplies(@TempDir Path workspace) throws Exception {
        Path f = workspace.resolve("a.txt");
        Files.writeString(f, "hello\n");
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);
        new ReadFileTool(new PathGuard(workspace)).execute(readCallWith("a.txt"), ctx);
        long oldMtime = Files.getLastModifiedTime(f).toMillis();

        Files.writeString(f, "hello\nhello\n");
        Files.setLastModifiedTime(f, FileTime.fromMillis(oldMtime + 5_000));

        ToolResult<?> result = tool.executeInternal(editCall("a.txt", "hello", "HI"), ctx);

        assertThat(String.valueOf(result.data()))
            .contains("File content has changed since it was last read");
        assertThat(Files.readString(f))
            .as("拒绝时不得写盘")
            .isEqualTo("hello\nhello\n");
    }
}

package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.PathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FIX-A backfill-observable] RED→GREEN 单测 · 锁定三个文件工具的 {@code backfillObservableInput}
 * 把 {@code ~}/相对路径展开为绝对路径，防 hook allowlist 被 ~/相对路径绕过。
 *
 * <p>WHY（规则九 · 验证意图）：CC {@code Tool.ts:475-484} 约定 hook/canUseTool 看到的 input
 * 是 backfilled 版（file_path 已绝对化），而 tool.call() 仍用原始 input（保护 prompt cache）。
 * 若 override 缺失（默认 identity），{@code ~/a.txt} 会原样传给 hook → allowlist 模式
 * {@code Read(/home/user/*)} 匹配不上 → 权限绕过。本测试在<b>工具层</b>锁定 expandPath 型
 * backfill 的行为契约（幂等 + 非抛异常 + 原引用不突变）；生产接线（hook 拿到绝对路径）
 * 由 {@code InputSanitizerBackfillWiringTest} 锁定。
 *
 * <p>键偏移登记（K-4 Java 契约）：Edit/Write 键为 {@code path}，Read 键为 {@code file_path}
 * （CC 三者均为 {@code file_path}）。本测试按 Java 实际键断言，不照抄 CC 键。
 */
@DisplayName("FIX-A · 文件工具 backfillObservableInput 展开 ~/相对路径（防 hook allowlist 绕过）")
class FileToolBackfillObservableInputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String home() {
        return System.getProperty("user.home");
    }

    private static ObjectNode editInput(String path) {
        ObjectNode in = JSON.createObjectNode();
        in.put("file_path", path);
        in.put("old_string", "old");
        in.put("new_string", "new");
        return in;
    }

    private static ObjectNode readInput(String filePath) {
        ObjectNode in = JSON.createObjectNode();
        in.put("file_path", filePath);
        return in;
    }

    private static ObjectNode writeInput(String path) {
        ObjectNode in = JSON.createObjectNode();
        in.put("file_path", path);
        in.put("content", "content");
        return in;
    }

    // ── Edit（键 path）──

    @Test
    @DisplayName("Edit: ~/a.txt → 家目录/a.txt 绝对路径")
    void editExpandsTilde(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        String expected = Paths.get(home()).resolve("a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(editInput("~/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Edit: src/a.txt → workspace/src/a.txt 绝对路径")
    void editExpandsRelative(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        String expected = guard.workdir().resolve("src/a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(editInput("src/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Edit: 已绝对路径 → 归一化后不变（返回原引用）")
    void editAbsoluteUnchanged(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        EditFileTool tool = new EditFileTool(guard);
        String abs = guard.workdir().resolve("a.txt").normalize().toString();
        ObjectNode in = editInput(abs);

        JsonNode result = tool.backfillObservableInput(in);

        assertThat(result).isSameAs(in);
        assertThat(result.path("file_path").asText()).isEqualTo(abs);
    }

    @Test
    @DisplayName("Edit: 幂等（调两次结果一致）+ 原 input 未被 in-place 改动")
    void editIdempotent(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ObjectNode in = editInput("~/a.txt");

        JsonNode first = tool.backfillObservableInput(in);
        JsonNode second = tool.backfillObservableInput(first);

        assertThat(second.path("file_path").asText()).isEqualTo(first.path("file_path").asText());
        assertThat(in.path("file_path").asText()).isEqualTo("~/a.txt");  // 原引用未突变
    }

    @Test
    @DisplayName("Edit: 缺 path 字段 → 返回原引用")
    void editMissingPathReturnsSame(@TempDir Path workspace) {
        EditFileTool tool = new EditFileTool(new PathGuard(workspace));
        ObjectNode in = JSON.createObjectNode();
        in.put("old_string", "old");
        in.put("new_string", "new");

        assertThat(tool.backfillObservableInput(in)).isSameAs(in);
    }

    // ── Read（键 file_path）──

    @Test
    @DisplayName("Read: ~/a.txt → 家目录/a.txt 绝对路径")
    void readExpandsTilde(@TempDir Path workspace) {
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));
        String expected = Paths.get(home()).resolve("a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(readInput("~/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Read: src/a.txt → workspace/src/a.txt 绝对路径")
    void readExpandsRelative(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        ReadFileTool tool = new ReadFileTool(guard);
        String expected = guard.workdir().resolve("src/a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(readInput("src/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Read: 已绝对路径 → 归一化后不变（返回原引用）")
    void readAbsoluteUnchanged(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        ReadFileTool tool = new ReadFileTool(guard);
        String abs = guard.workdir().resolve("a.txt").normalize().toString();
        ObjectNode in = readInput(abs);

        JsonNode result = tool.backfillObservableInput(in);

        assertThat(result).isSameAs(in);
        assertThat(result.path("file_path").asText()).isEqualTo(abs);
    }

    @Test
    @DisplayName("Read: 缺 file_path 字段 → 返回原引用")
    void readMissingPathReturnsSame(@TempDir Path workspace) {
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace));
        ObjectNode in = JSON.createObjectNode();
        in.put("offset", 1);

        assertThat(tool.backfillObservableInput(in)).isSameAs(in);
    }

    // ── Write（键 path）──

    @Test
    @DisplayName("Write: ~/a.txt → 家目录/a.txt 绝对路径")
    void writeExpandsTilde(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));
        String expected = Paths.get(home()).resolve("a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(writeInput("~/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Write: src/a.txt → workspace/src/a.txt 绝对路径")
    void writeExpandsRelative(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        WriteFileTool tool = new WriteFileTool(guard);
        String expected = guard.workdir().resolve("src/a.txt").normalize().toString();

        JsonNode result = tool.backfillObservableInput(writeInput("src/a.txt"));

        assertThat(result.path("file_path").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Write: 已绝对路径 → 归一化后不变（返回原引用）")
    void writeAbsoluteUnchanged(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        WriteFileTool tool = new WriteFileTool(guard);
        String abs = guard.workdir().resolve("a.txt").normalize().toString();
        ObjectNode in = writeInput(abs);

        JsonNode result = tool.backfillObservableInput(in);

        assertThat(result).isSameAs(in);
        assertThat(result.path("file_path").asText()).isEqualTo(abs);
    }

    @Test
    @DisplayName("Write: 幂等（调两次结果一致）+ 原 input 未被 in-place 改动")
    void writeIdempotent(@TempDir Path workspace) {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));
        ObjectNode in = writeInput("~/a.txt");

        JsonNode first = tool.backfillObservableInput(in);
        JsonNode second = tool.backfillObservableInput(first);

        assertThat(second.path("file_path").asText()).isEqualTo(first.path("file_path").asText());
        assertThat(in.path("file_path").asText()).isEqualTo("~/a.txt");  // 原引用未突变
    }
}

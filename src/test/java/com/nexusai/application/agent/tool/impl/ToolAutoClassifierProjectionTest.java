package com.nexusai.application.agent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.PathGuard;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [OPD-24 G1] 4 个高安全工具真实 toAutoClassifierInput 投影测试 · 对齐 CC
 *
 * <p><b>WHY (意图验证)</b>: G6 阻断残留 = BashTool/EditFileTool/ReadFileTool/WriteFileTool
 * 未 override {@code toAutoClassifierInput} → 走 {@link com.nexusai.application.agent.tool.Tool}
 * 默认 {@code ''}（CC Tool.ts:767 TOOL_DEFAULTS）→ 消费侧 yoloClassifier.ts:411/{@code :1021-1024}
 * 空串短路 ALLOW，即 auto-mode 对这些高安全工具完全不做 LLM 分类（安全缺口）。
 * 本测试断言 4 工具真实投影非空且文本对齐 CC，确保分类器真正拿到 per-tool 投影。</p>
 *
 * <p>CC 真源：BashTool.tsx:442-444 {@code return input.command}；FileReadTool.ts:379-381
 * {@code return input.file_path}；FileEditTool.ts:109-111 {@code `${file_path}: ${new_string}`}；
 * FileWriteTool.ts:119-121 {@code `${file_path}: ${content}`}。</p>
 */
class ToolAutoClassifierProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode input() {
        return MAPPER.createObjectNode();
    }

    // ── Bash ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OPD-24 G1: Bash 投影 = command 原文（CC BashTool.tsx:442-444 input.command）")
    void bashProjection_returnsCommand() {
        BashTool tool = new BashTool();
        ObjectNode input = input();
        input.put("command", "ls -la");

        assertEquals("ls -la", tool.toAutoClassifierInput(input));
    }

    @Test
    @DisplayName("OPD-24 G1: Bash 缺失 command / null 输入 → 空串（CC 空串=跳过转录）")
    void bashProjection_missingOrNullInput_returnsEmpty() {
        BashTool tool = new BashTool();
        assertEquals("", tool.toAutoClassifierInput(input()));
        assertEquals("", tool.toAutoClassifierInput(null));
    }

    // ── ReadFile ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("OPD-24 G1: ReadFile 投影 = file_path 原文（CC FileReadTool.ts:379-381 input.file_path）")
    void readFileProjection_returnsFilePath() {
        ReadFileTool tool = new ReadFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode input = input();
        input.put("file_path", "/abs/x.txt");

        assertEquals("/abs/x.txt", tool.toAutoClassifierInput(input));
    }

    @Test
    @DisplayName("OPD-24 G1: ReadFile 缺失 file_path / null 输入 → 空串")
    void readFileProjection_missingOrNullInput_returnsEmpty() {
        ReadFileTool tool = new ReadFileTool(new PathGuard(Path.of("/tmp/ws")));
        assertEquals("", tool.toAutoClassifierInput(input()));
        assertEquals("", tool.toAutoClassifierInput(null));
    }

    // ── EditFile ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("OPD-24 G1: EditFile 投影 = path: new_text（CC FileEditTool.ts:109-111 file_path:new_string；Java 键偏移 path/new_text）")
    void editFileProjection_pathColonNewText() {
        EditFileTool tool = new EditFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode input = input();
        input.put("file_path", "/abs/x.java");
        input.put("new_string", "String s;");

        assertEquals("/abs/x.java: String s;", tool.toAutoClassifierInput(input));
    }

    @Test
    @DisplayName("OPD-24 G1: EditFile 缺失 path / null 输入 → 空串")
    void editFileProjection_missingOrNullInput_returnsEmpty() {
        EditFileTool tool = new EditFileTool(new PathGuard(Path.of("/tmp/ws")));
        assertEquals("", tool.toAutoClassifierInput(input()));
        assertEquals("", tool.toAutoClassifierInput(null));
    }

    // ── WriteFile ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("OPD-24 G1: WriteFile 投影 = path: content（CC FileWriteTool.ts:119-121 file_path:content；Java 键偏移 path）")
    void writeFileProjection_pathColonContent() {
        WriteFileTool tool = new WriteFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode input = input();
        input.put("file_path", "/abs/y.txt");
        input.put("content", "hello");

        assertEquals("/abs/y.txt: hello", tool.toAutoClassifierInput(input));
    }

    @Test
    @DisplayName("OPD-24 G1: WriteFile 缺失 path / null 输入 → 空串")
    void writeFileProjection_missingOrNullInput_returnsEmpty() {
        WriteFileTool tool = new WriteFileTool(new PathGuard(Path.of("/tmp/ws")));
        assertEquals("", tool.toAutoClassifierInput(input()));
        assertEquals("", tool.toAutoClassifierInput(null));
    }

    // ── G6 安全缺口关闭：投影非空 → auto-mode 不再短路 ALLOW ────────────

    @Test
    @DisplayName("OPD-24 G1: 4 工具真实输入投影均非空 → 关闭 G6 auto-mode 空串短路 ALLOW 缺口（CC yoloClassifier.ts:1021-1024）")
    void allFourTools_RealisticInput_projectionsNonEmpty() {
        BashTool bashTool = new BashTool();
        ObjectNode bash = input();
        bash.put("command", "rm -rf /tmp/x");
        assertFalse(bashTool.toAutoClassifierInput(bash).isEmpty(),
            "Bash 投影为空 → auto-mode 对 Bash 短路 ALLOW，分类器看不到危险命令");

        ReadFileTool readTool = new ReadFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode read = input();
        read.put("file_path", "/etc/passwd");
        assertFalse(readTool.toAutoClassifierInput(read).isEmpty(),
            "ReadFile 投影为空 → 读取不被分类");

        EditFileTool editTool = new EditFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode edit = input();
        edit.put("file_path", "/etc/sudoers");
        edit.put("new_string", "evil");
        assertFalse(editTool.toAutoClassifierInput(edit).isEmpty(),
            "EditFile 投影为空 → 编辑不被分类");

        WriteFileTool writeTool = new WriteFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode write = input();
        write.put("file_path", "/etc/crontab");
        write.put("content", "evil");
        assertFalse(writeTool.toAutoClassifierInput(write).isEmpty(),
            "WriteFile 投影为空 → 写入不被分类");
    }

    /**
     * 投影必须可被 {@code YoloPromptBuilder} 序列化为转录文本，非空断言即覆盖此意图；
     * 此处补充校验投影仅由工具相关字段构成（不含 toolName 前缀噪音）。
     */
    @Test
    @DisplayName("OPD-24 G1: 投影文本对齐 CC 格式，无 toolName 前缀（Bash=command/Read=file_path/Edit=path:new_text/Write=path:content）")
    void projections_containOnlyRelevantFields_notToolNamePrefix() {
        BashTool bashTool = new BashTool();
        ObjectNode bash = input();
        bash.put("command", "git status");
        assertTrue(bashTool.toAutoClassifierInput(bash).equals("git status"));

        EditFileTool editTool = new EditFileTool(new PathGuard(Path.of("/tmp/ws")));
        ObjectNode edit = input();
        edit.put("file_path", "/abs/x");
        edit.put("new_string", "y");
        assertEquals("/abs/x: y", editTool.toAutoClassifierInput(edit));
    }
}

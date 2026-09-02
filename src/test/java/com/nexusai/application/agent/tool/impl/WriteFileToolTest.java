package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-D2 · Write 编辑语义与编码对齐 CC {@code FileWriteTool.ts}。
 *
 * <p>WHY（规则九 · 验证意图）：组 2-5 拍板「全对齐 CC」——键名 file_path（W5）、
 * utf16le 编码保留（W24/W26，数据完整性 HIGH）。测试锁定旧实现必红的 RED→GREEN 行为：
 * 旧键 path、恒 UTF-8 写覆盖 utf16le 乱码。
 * <p>BOM 语义对齐 CC：{@code writeTextContent}（utils/file.ts:84-98）<b>不强制前置 BOM</b>，
 * BOM 仅随 content 首字符 U+FEFF 随 utf16le 编码保真（PROBE-BOM DC-1 收敛 Java 旧强制 BOM）。
 */
@DisplayName("IMP-D2 · WriteFileTool 编辑语义与编码对齐")
class WriteFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseContext ctxFor(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("d2-write-agent-" + workspace).getBytes());
        String sessionId = UUID.nameUUIDFromBytes(("d2-write-sess-" + workspace).getBytes()).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    private static ToolUseBlock writeCall(String path, String content) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        input.put("content", content);
        return new ToolUseBlock("call-write", "Write", input);
    }

    private static void seedReadState(PathGuard guard, ToolUseContext ctx, String relPath,
                                      String content) throws Exception {
        Path file = guard.resolve(relPath);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        ctx.readFileState().set(
            ToolUseContext.keyForReadFileState(guard, relPath),
            ReadState.full(mtime, content));
    }

    @Test
    @DisplayName("inputSchema 键名为 file_path/content（CC FileWriteTool.ts:58-64，旧 path 消失）")
    void inputSchema_usesCcKeys(@TempDir Path workspace) {
        JsonNode schema = new WriteFileTool(new PathGuard(workspace)).inputSchema();
        JsonNode props = schema.path("properties");
        assertThat(props.has("file_path")).as("file_path 键必须存在").isTrue();
        assertThat(props.has("content")).as("content 键必须存在").isTrue();
        assertThat(props.has("path")).as("旧 path 键必须删除").isFalse();
        JsonNode required = schema.path("required");
        assertThat(required.toString()).contains("file_path", "content");
    }

    @Test
    @DisplayName("utf16le 写覆盖不强制 BOM——BOM 仅随 content U+FEFF 保真（对齐 CC writeTextContent）")
    void write_utf16le_preserved(@TempDir Path workspace) throws Exception {
        PathGuard guard = new PathGuard(workspace);
        WriteFileTool tool = new WriteFileTool(guard);
        ToolUseContext ctx = ctxFor(workspace);

        // 已有 utf16le 文件：BOM(FF FE) + "hello"
        byte[] bom = {(byte) 0xff, (byte) 0xfe};
        byte[] body = "hello".getBytes(Charset.forName("UTF-16LE"));
        byte[] raw = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, raw, 0, bom.length);
        System.arraycopy(body, 0, raw, bom.length, body.length);

        // WHY（规则九 · 验证意图 · 对齐 CC）：WriteFileTool 走
        //   writeTextContent(fullFilePath, content, enc, 'LF')（FileWriteTool.ts:305）忠实编码模型
        //   content，不强制前置 BOM——Node writeFileSync('utf16le') 无自动 BOM，BOM 仅当 content
        //   首字符为 U+FEFF 时随 utf16le 编码产出（PROBE-BOM §6.2-3 / DC-1 收敛 Java 旧强制 BOM）。
        //   情形①：模型 content 无 U+FEFF → 字节不以 FF FE 开头、解码为 "world"（旧实现强制 BOM 必红）。
        Path file = workspace.resolve("w16.txt");
        Files.write(file, raw);
        seedReadState(guard, ctx, "w16.txt", "\uFEFFhello");

        ToolResult<String> result = tool.execute(writeCall("w16.txt", "world"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("utf16le 写覆盖应成功").isFalse();
        byte[] after = Files.readAllBytes(file);
        assertThat(after.length >= 2 && (after[0] & 0xff) == 0xff && (after[1] & 0xff) == 0xfe)
            .as("模型 content 无 U+FEFF 写 utf16le 不得强制前置 BOM（对齐 CC writeTextContent）").isFalse();
        assertThat(new String(after, Charset.forName("UTF-16LE")))
            .as("模型 content 原样 UTF-16LE 编码，无 BOM 字符").isEqualTo("world");

        // 情形②：模型 content 首字符 U+FEFF → BOM 随内容保真（FF FE 落盘），解码含 BOM 字符
        Path fileBom = workspace.resolve("w16b.txt");
        Files.write(fileBom, raw);
        seedReadState(guard, ctx, "w16b.txt", "\uFEFFhello");
        ToolResult<String> resultBom = tool.execute(writeCall("w16b.txt", "\uFEFFworld"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(resultBom.data())).as("BOM 内容写 utf16le 应成功").isFalse();
        byte[] afterBom = Files.readAllBytes(fileBom);
        assertThat(afterBom.length >= 2 && (afterBom[0] & 0xff) == 0xff && (afterBom[1] & 0xff) == 0xfe)
            .as("content 含 U+FEFF 时 BOM 必须随 utf16le 编码保真（FF FE 落盘）").isTrue();
        assertThat(new String(afterBom, Charset.forName("UTF-16LE")))
            .as("解码 = BOM 字符 + world").isEqualTo("\uFEFFworld");
    }

    @Test
    @DisplayName("模型 content 行尾原样保留（W26 行尾恒 LF，不 CRLF 改写）")
    void write_contentLineEndingsAsIs(@TempDir Path workspace) throws Exception {
        WriteFileTool tool = new WriteFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxFor(workspace);

        ToolResult<String> result = tool.execute(writeCall("new.txt", "line1\nline2"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(Files.readString(workspace.resolve("new.txt")))
            .as("模型 content 行尾必须原样保留").isEqualTo("line1\nline2");
    }
}

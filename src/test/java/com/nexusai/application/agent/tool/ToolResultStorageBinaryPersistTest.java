package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RV-FOLLOWUP MCP-01] ToolResultStorage.persistBinaryContent + extensionForMimeType 单测。
 *
 * <p>WHY (规则九 · 测试验证意图): MCP audio 内容块不再把 base64 二进制直进 prompt——CC
 * persistBlobToTextBlock（mcp/client.ts:2598）经 persistBinaryContent（mcpOutputStorage.ts:148）
 * 写盘后替换为文本提示。本测试验证 Java 等价物的三个 intent:
 * <ol>
 *   <li>mime → ext 映射正确（audio 子集 + bin 默认 + charset 剥离，对齐 CC extensionForMimeType:66-116）</li>
 *   <li>原生字节按 {configHome}/projects/{slug(workspaceDir)}/{sessionId}/tool-results/{persistId}.{ext} 写盘
 *       （对齐 CC :154-155 + session-bound-dir：getToolResultsDir→SessionStorage.getProjectDir 重定向 config-home），
 *       非字符串化（字节级 fidelity）</li>
 *   <li>入参非法 → 返回 error 结果而非抛异常（CC {error} 联合语义）</li>
 * </ol>
 */
@DisplayName("[RV-FOLLOWUP MCP-01] ToolResultStorage 二进制持久化（MCP audio 对齐）")
class ToolResultStorageBinaryPersistTest {

    @TempDir
    Path workspaceDir;

    // ── extensionForMimeType ──

    @Test
    @DisplayName("audio mime → 正确 ext（mp3/wav/ogg）· 未知 → bin · charset 剥离 · null → bin")
    void extensionForMimeType_audioSubsetAndDefaults() {
        // 对齐 CC mcpOutputStorage.ts:95-100 audio 子集
        assertThat(ToolResultStorage.extensionForMimeType("audio/mpeg")).isEqualTo("mp3");
        assertThat(ToolResultStorage.extensionForMimeType("audio/wav")).isEqualTo("wav");
        assertThat(ToolResultStorage.extensionForMimeType("audio/ogg")).isEqualTo("ogg");
        // 全表抽查（CC :66-116 其余条目）
        assertThat(ToolResultStorage.extensionForMimeType("application/pdf")).isEqualTo("pdf");
        assertThat(ToolResultStorage.extensionForMimeType("application/json")).isEqualTo("json");
        assertThat(ToolResultStorage.extensionForMimeType("image/png")).isEqualTo("png");
        assertThat(ToolResultStorage.extensionForMimeType("image/jpeg")).isEqualTo("jpg");
        // charset/boundary 剥离（CC :70-72 split(';')[0] trim toLowerCase）
        assertThat(ToolResultStorage.extensionForMimeType("audio/mpeg; charset=utf-8"))
            .as("charset 参数必须剥离")
            .isEqualTo("mp3");
        // 未知 → 'bin'（CC :115-116 default）
        assertThat(ToolResultStorage.extensionForMimeType("application/octet-stream")).isEqualTo("bin");
        assertThat(ToolResultStorage.extensionForMimeType(null)).isEqualTo("bin");
        assertThat(ToolResultStorage.extensionForMimeType("  ")).isEqualTo("bin");
    }

    // ── persistBinaryContent ──

    @Test
    @DisplayName("原生字节写盘到 {configHome}/projects/{slug}/{session}/tool-results/{persistId}.{ext}（字节级 fidelity）")
    void persistBinaryContent_writesRawBytesToSessionToolResultsDir() throws Exception {
        byte[] audio = new byte[]{(byte) 0xFF, 0x4F, 0x46, 0x46, 0x00, 0x01}; // OGG 头
        ToolResultStorage.BinaryPersistResult r = ToolResultStorage.persistBinaryContent(
            workspaceDir, "sess-1", audio, "audio/ogg", "mcp-myserver-blob-123-ab12cd").join();

        // 成功路径：error==null，返回 filepath/size/ext
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.ext()).isEqualTo("ogg");
        assertThat(r.size()).isEqualTo(audio.length);
        // 目录 = {configHome}/projects/{slug(workspaceDir)}/{sessionId}/tool-results
        //（getToolResultsDir→SessionStorage.getProjectDir 重定向 config-home，对齐 CC session-bound-dir；
        //  2026-08-30 修正过时断言：transcript 迁移后 tool-results 不再在 workspaceDir 下）
        Path expected = SessionStorage.getProjectDir(workspaceDir).resolve("sess-1").resolve("tool-results")
            .resolve("mcp-myserver-blob-123-ab12cd.ogg");
        assertThat(Path.of(r.filepath())).isEqualTo(expected);
        // 字节级 fidelity（非字符串化）
        assertThat(Files.readAllBytes(expected)).containsExactly(audio);
    }

    @Test
    @DisplayName("入参非法 → 返回 error 结果（CC {error} 联合）而非抛异常")
    void persistBinaryContent_nullSession_returnsErrorResult() {
        ToolResultStorage.BinaryPersistResult r = ToolResultStorage.persistBinaryContent(
            workspaceDir, null, new byte[]{1, 2, 3}, "audio/mpeg", "persist-1").join();
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).isNotNull();
    }
}

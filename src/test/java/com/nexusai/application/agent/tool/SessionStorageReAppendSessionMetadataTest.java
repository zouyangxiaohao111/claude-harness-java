package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reAppendSessionMetadata / getTranscriptPath 测试 · 对齐 CC utils/sessionStorage.ts:202/721-829.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 压缩后消息数剧增，会把 session 元数据
 * （custom-title / tag / last-prompt …）挤出 transcript 尾窗口，导致 --resume 展示回退到
 * 自动生成标题（CC compact.ts:705-708 在压缩成功路径调用 reAppendSessionMetadata 解决）。
 * 本测试锁定:
 * <ol>
 *   <li><b>getTranscriptPath</b> 路径形态与 CC 对齐（扁平 {@code {projectDir}/{sessionId}.jsonl}，
 *       07 S8 / D14: target 旧 getSessionFile 是嵌套形态）</li>
 *   <li><b>reAppendSessionMetadata</b> 把非空元数据按 CC 顺序无条件重 append 到 transcript 尾
 *       （last-prompt → custom-title → tag → agent-name → agent-color → agent-setting → mode
 *       → worktree-state → pr-link，sessionStorage.ts:788-829）</li>
 *   <li><b>外部写者刷新</b>: append 前读尾窗口，吸收更新鲜的 custom-title/tag
 *       （sessionStorage.ts:730-761 external-writer safety）</li>
 * </ol>
 */
@DisplayName("[IMP-08] reAppendSessionMetadata / getTranscriptPath（--resume 标题/tag 保全）")
class SessionStorageReAppendSessionMetadataTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("getTranscriptPath 路径形态对齐 CC 扁平结构（S2 迁 config-home）{configHome}/projects/{slug}/{sessionId}.jsonl")
    void transcriptPathIsFlatCcAligned() {
        Path path = SessionStorage.getTranscriptPath(tempDir, "session-42");

        Path expected = SessionStorage.getProjectsDir()
            .resolve(com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(tempDir.toString()))
            .resolve("session-42.jsonl");
        assertThat(path).isEqualTo(expected);
        assertThat(path.getFileName().toString()).isEqualTo("session-42.jsonl");
        assertThat(path.getParent())
            .isEqualTo(SessionStorage.getProjectsDir()
                .resolve(com.nexusai.application.agent.memory.AutoMemPaths.sanitizePath(tempDir.toString())));
    }

    @Test
    @DisplayName("reAppendSessionMetadata 按 CC 顺序 append 非空元数据到 transcript 尾")
    void reAppendsNonNullMetadataInCcOrder() throws Exception {
        String sessionId = "session-42";
        SessionStorage.SessionMetadata metadata = new SessionStorage.SessionMetadata(
            "last user prompt text",   // lastPrompt
            "My Custom Title",          // customTitle
            "project:nexus",            // tag
            null, null, null, null, null, null, null, null);

        SessionStorage.reAppendSessionMetadata(tempDir, sessionId, metadata);

        List<JsonNode> entries = readAllEntries(sessionId);
        // CC append 顺序: last-prompt 先写, custom-title/tag 随后（sessionStorage.ts:788-829）
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).path("type").asText()).isEqualTo("last-prompt");
        assertThat(entries.get(0).path("lastPrompt").asText()).isEqualTo("last user prompt text");
        assertThat(entries.get(1).path("type").asText()).isEqualTo("custom-title");
        assertThat(entries.get(1).path("customTitle").asText()).isEqualTo("My Custom Title");
        assertThat(entries.get(2).path("type").asText()).isEqualTo("tag");
        assertThat(entries.get(2).path("tag").asText()).isEqualTo("project:nexus");
        for (JsonNode e : entries) {
            assertThat(e.path("sessionId").asText()).isEqualTo(sessionId);
        }
    }

    @Test
    @DisplayName("reAppend 无条件重复执行：已有尾条目不阻止再 append（压缩推动的保全语义）")
    void reAppendIsUnconditional() throws Exception {
        String sessionId = "session-42";
        SessionStorage.SessionMetadata metadata = new SessionStorage.SessionMetadata(
            null, "Title", null, null, null, null, null, null, null, null, null);

        SessionStorage.reAppendSessionMetadata(tempDir, sessionId, metadata);
        SessionStorage.reAppendSessionMetadata(tempDir, sessionId, metadata);

        List<JsonNode> entries = readAllEntries(sessionId);
        // 无条件重 append → 两条 custom-title（CC 语义: 跳过会随 post-compact 会话增长掉出窗口）
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e ->
            assertThat(e.path("type").asText()).isEqualTo("custom-title"));
    }

    @Test
    @DisplayName("外部写者刷新：尾窗口有更新鲜 custom-title 时吸收而非用参数陈旧值")
    void tailRefreshAbsorbsFresherTitle() throws Exception {
        String sessionId = "session-42";
        // 先写入一条更旧参数标题（模拟调用方传入的陈旧缓存）
        SessionStorage.reAppendSessionMetadata(tempDir, sessionId,
            new SessionStorage.SessionMetadata(null, "STALE", null, null, null,
                null, null, null, null, null, null));
        // 模拟外部 SDK 写入更新鲜 custom-title（readLiteMetadata 尾窗口内）
        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        String fresherLine = JSON.writeValueAsString(java.util.Map.of(
            "type", "custom-title", "customTitle", "FRESHER", "sessionId", sessionId)) + "\n";
        Files.writeString(transcript, fresherLine, java.nio.file.StandardOpenOption.APPEND);

        // 再 re-append: 应吸收 FRESHER（external-writer safety, sessionStorage.ts:730-748）
        SessionStorage.reAppendSessionMetadata(tempDir, sessionId,
            new SessionStorage.SessionMetadata(null, "STALE", null, null, null,
                null, null, null, null, null, null));

        List<JsonNode> entries = readAllEntries(sessionId);
        List<JsonNode> titles = entries.stream()
            .filter(e -> "custom-title".equals(e.path("type").asText()))
            .toList();
        // 最后一条 custom-title 必须是 FRESHER（吸收外部写者）
        assertThat(titles.get(titles.size() - 1).path("customTitle").asText())
            .isEqualTo("FRESHER");
    }

    private List<JsonNode> readAllEntries(String sessionId) throws Exception {
        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        assertThat(transcript).exists();
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(transcript)) {
            if (!line.isBlank()) result.add(JSON.readTree(line));
        }
        return result;
    }
}

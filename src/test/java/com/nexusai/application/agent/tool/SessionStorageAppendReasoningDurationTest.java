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
 * {@link SessionStorage#appendReasoningDuration} 测试 · 后端测推理耗时 transcript 双轨
 * （净新增 entry 类型 {@code 'reasoning-duration'}，非 CC 对齐——CC 无 reasoning 计时字段）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 用户拍板（2026-08-24）后端测量模型推理耗时，
 * 双轨之一为 transcript 文件。扁平 transcript（{@code {configHome}/projects/{slug}/{sessionId}.jsonl}）
 * 当前只含元数据/worktree-state entry，本方法新增独立 {@code 'reasoning-duration'} entry。
 * 变异点：
 * <ul>
 *   <li>entry 形状错（type/messageId/reasoningDurationMs 缺失）→ 未来工具无法消费 → 红</li>
 *   <li>append-only 破坏（多次调用覆盖 / 截断）→ 历史计时丢失 → 红</li>
 *   <li>null 副属（durationMs/workspaceDir/sessionId/messageId null）仍写文件 → 无 reasoning 也留痕 → 红</li>
 *   <li>文件不存在时未 CREATE → 首次调用静默失败 → 红</li>
 * </ul>
 */
@DisplayName("[reasoningDurationMs] SessionStorage.appendReasoningDuration（transcript reasoning-duration entry）")
class SessionStorageAppendReasoningDurationTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("追加正确 JSON 行 {type:'reasoning-duration', sessionId, messageId, reasoningDurationMs, timestamp}")
    void appendsReasoningDurationEntry() throws Exception {
        String sessionId = "session-42";

        SessionStorage.appendReasoningDuration(tempDir, sessionId, "msg-asst-1", 1234L);

        List<JsonNode> entries = readAllEntries(sessionId);
        assertThat(entries).hasSize(1);
        JsonNode e = entries.get(0);
        assertThat(e.path("type").asText())
            .as("entry 类型必须为 reasoning-duration（供未来工具按 type 消费）")
            .isEqualTo("reasoning-duration");
        assertThat(e.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(e.path("messageId").asText())
            .as("entry 必须携带产生该推理的 assistant 消息 id")
            .isEqualTo("msg-asst-1");
        assertThat(e.path("reasoningDurationMs").asLong())
            .as("entry 必须携带后端测推理耗时（ms）")
            .isEqualTo(1234L);
        assertThat(e.has("timestamp"))
            .as("entry 必须携带 timestamp（Instant.now 毫秒），供时序审计")
            .isTrue();
    }

    @Test
    @DisplayName("多次调用 append-only 追加多行（不覆盖、不截断）")
    void multipleCallsAppendMultipleLines() throws Exception {
        String sessionId = "session-42";

        SessionStorage.appendReasoningDuration(tempDir, sessionId, "msg-1", 100L);
        SessionStorage.appendReasoningDuration(tempDir, sessionId, "msg-2", 200L);

        List<JsonNode> entries = readAllEntries(sessionId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("messageId").asText())
            .as("第一条 reasoning-duration entry 必须保留（append-only）")
            .isEqualTo("msg-1");
        assertThat(entries.get(1).path("messageId").asText()).isEqualTo("msg-2");
        assertThat(entries.get(1).path("reasoningDurationMs").asLong()).isEqualTo(200L);
    }

    @Test
    @DisplayName("null 副属（durationMs/workspaceDir/sessionId/messageId）→ no-op 不写文件")
    void nullGuardNoFileWritten() throws Exception {
        String sessionId = "session-42";
        // 无 reasoning（durationMs null）→ 不记录
        SessionStorage.appendReasoningDuration(tempDir, sessionId, "msg-1", null);
        // workspaceDir / sessionId / messageId null → 不记录
        SessionStorage.appendReasoningDuration(null, sessionId, "msg-1", 100L);
        SessionStorage.appendReasoningDuration(tempDir, null, "msg-1", 100L);
        SessionStorage.appendReasoningDuration(tempDir, sessionId, null, 100L);

        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        assertThat(transcript)
            .as("null 副属不得写文件（无 reasoning 不记录，干净语义）")
            .doesNotExist();
    }

    @Test
    @DisplayName("文件不存在时 CREATE 新建（对齐 appendEntry CREATE+APPEND 语义）")
    void createsFileWhenMissing() throws Exception {
        String sessionId = "session-42";
        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        assertThat(transcript).doesNotExist();

        SessionStorage.appendReasoningDuration(tempDir, sessionId, "msg-1", 500L);

        assertThat(transcript)
            .as("首次调用必须 CREATE 新建 transcript 文件（对齐 appendEntry）")
            .exists();
    }

    private List<JsonNode> readAllEntries(String sessionId) throws Exception {
        Path transcript = SessionStorage.getTranscriptPath(tempDir, sessionId);
        assertThat(transcript).exists();
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(transcript)) {
            if (!line.isBlank()) {
                result.add(JSON.readTree(line));
            }
        }
        return result;
    }
}

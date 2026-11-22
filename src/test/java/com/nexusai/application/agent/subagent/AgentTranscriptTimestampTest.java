package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [transcript-timestamp] 写入侧每一行都有 timestamp · 老文件仍可读 · 叶子按时间而非文件序。
 *
 * <h2>WHY（2026-11-20 实测）</h2>
 * <p>CC 每条 JSONL 行都写 {@code timestamp}；本仓工具结果行（{@code LlmAgentLoop.toolResultMessage}
 * 的 {@code createdAt=null}）与若干 raw map 写入点（initialMessages / 外部 agent）此前不写，
 * 真实会话里出现多个 timestamp=null 的叶子。读取侧只能靠「文件序最后一条」兜底才没把
 * SendMessage 通知行截掉（374 行文件一度只回 71 条、agent_message=0）。
 * 本测试钉住：兜底只服务历史老文件，新写入必须带事件时刻的时间戳。
 *
 * <h2>RED teeth（变异）</h2>
 * <ul>
 *   <li>去掉 {@code AgentTranscript.stampWriteTimestamp}（写入漏斗补时间戳）⇒ 用例 1 红；</li>
 *   <li>把它改成「统一成同一时间 / 全部按文件序」⇒ 用例 3/4/5 分头红。</li>
 * </ul>
 */
@DisplayName("[transcript-timestamp] 每行写入时间戳 + 老文件兼容 + 按时间选叶子")
class AgentTranscriptTimestampTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "sess-ts";
    private static final String AGENT = "a0123456789abcdef";

    private Path transcriptPath() {
        return AgentTranscript.getTranscriptPath(tmpDir, SESSION, AGENT);
    }

    /** 直接写 JSONL（模拟历史文件 / 已落盘行，绕过写入漏斗）。 */
    private void writeRaw(List<ObjectNode> rows) throws Exception {
        Path path = transcriptPath();
        Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        for (ObjectNode n : rows) {
            lines.add(MAPPER.writeValueAsString(n));
        }
        Files.write(path, lines);
    }

    private List<JsonNode> readRaw() throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(transcriptPath())) {
            if (!line.isBlank()) {
                nodes.add(MAPPER.readTree(line));
            }
        }
        return nodes;
    }

    private static long millis(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    }

    private static ObjectNode oldRow(String role, String uuid, String parentUuid, String timestamp) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        n.put("content", role + "-" + uuid);
        n.put("agentId", AGENT);
        n.put("isSidechain", true);
        n.put("uuid", uuid);
        if (parentUuid != null) {
            n.put("parentUuid", parentUuid);
        }
        if (timestamp != null) {
            n.put("timestamp", timestamp);
        }
        return n;
    }

    // ────────────────────────────────────────────────────────────────────────
    // a) 新写入的每一类行（user / system / assistant-tool_use / tool / notification）都有 timestamp
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("raw map 写入（user/system/assistant tool_use/tool）经写入漏斗后每行都有时间戳，同批不倒序")
    void rawWrites_withoutTimestamp_allGetWriteTimestamp() throws Exception {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "系统上下文");

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", "用户 prompt");

        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("role", "assistant");
        toolUse.put("content", "");
        List<Map<String, Object>> tcs = new ArrayList<>();
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("id", "toolu_1");
        tc.put("name", "Bash");
        tc.put("arguments", "{}");
        tcs.add(tc);
        toolUse.put("toolCalls", tcs);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("role", "tool");
        tool.put("toolCallId", "toolu_1");
        tool.put("content", "(Bash completed with no output)");

        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION, AGENT,
            new ArrayList<>(List.of(system, user, toolUse, tool)));

        List<JsonNode> rows = readRaw();
        assertThat(rows).as("四类行都要落盘").hasSize(4);
        long prev = Long.MIN_VALUE;
        for (JsonNode n : rows) {
            assertThat(n.hasNonNull("timestamp"))
                .as("role=%s 行必须有 timestamp（CC 每行都有；缺则成为无时序叶子）",
                    n.path("role").asText())
                .isTrue();
            long t = millis(n.path("timestamp").asText());
            assertThat(t).as("同批写入顺序 = 时间顺序（允许相等，禁止倒序）").isGreaterThanOrEqualTo(prev);
            prev = t;
        }
        assertThat(rows.get(2).path("toolCalls").isArray()).as("tool_use 行不得被时间戳改动破坏").isTrue();
    }

    @Test
    @DisplayName("SendMessage 通知行落盘带 timestamp（沿用入队时刻 queuedAt，不改成落盘时刻）")
    void notificationRow_carriesQueuedTimestamp() throws Exception {
        String queuedAt = "2026-11-20T07:00:17.376242400Z";
        AgentNameRegistry.PendingAgentMessage pm = new AgentNameRegistry.PendingAgentMessage(
            "n-1", "0f1e2d3c-0000-0000-0000-000000000001", SESSION, "team-lead",
            "补查审计结论", "请补充审计结论", "coordinator", queuedAt);

        Map<String, Object> row = AgentNotificationMessages.toTranscriptMap(pm);
        assertThat(row.get("timestamp")).as("通知行自带时间戳").isEqualTo(queuedAt);

        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION, AGENT,
            new ArrayList<>(List.of(row)));

        JsonNode line = readRaw().get(0);
        assertThat(line.path("timestamp").asText())
            .as("落盘后仍是入队时刻（事件发生时刻），不被改成落盘时刻")
            .isEqualTo(queuedAt);
    }

    // ────────────────────────────────────────────────────────────────────────
    // b) 混合老文件：部分行无 timestamp，仍能选中正确叶子
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("老文件（无 timestamp 的旧分支）+ 新写入行：叶子取带时间戳的新行，不落回旧分支")
    void mixedOldFile_stillSelectsNewestTimedLeaf() throws Exception {
        writeRaw(List.of(
            oldRow("user", "u0", null, null),
            oldRow("assistant", "a1", "u0", null),
            // 旧分支的链尾（历史行，无 timestamp，且在文件里更早写入）
            oldRow("tool", "t1", "a1", null),
            // 修复后新写入的行（带时间戳），同挂在 a1 上
            oldRow("assistant", "a2", "a1", "2026-11-20T09:00:00Z")));

        AgentTranscript.AgentTranscriptResult r =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION, AGENT).orElseThrow();

        assertThat(r.messages()).extracting(AgentMessage::uuid)
            .as("无时间戳候选让位给带时间戳的候选 ⇒ 选 a2 而不是旧分支链尾 t1")
            .containsExactly("u0", "a1", "a2");
    }

    @Test
    @DisplayName("全部是老文件（无 timestamp）：仍按文件序取最后一条叶子，不抛不空")
    void legacyFileWithoutAnyTimestamp_stillReadable() throws Exception {
        writeRaw(List.of(
            oldRow("user", "u0", null, null),
            oldRow("assistant", "a1", "u0", null),
            oldRow("tool", "t1", "a1", null),
            oldRow("assistant", "a2", "t1", null)));

        AgentTranscript.AgentTranscriptResult r =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION, AGENT).orElseThrow();

        assertThat(r.messages()).extracting(AgentMessage::uuid)
            .as("兜底保留：全无时间戳时取文件序最后一条叶子")
            .containsExactly("u0", "a1", "t1", "a2");
        assertThat(r.messages()).allSatisfy(m -> assertThat(m.timestamp()).isNull());
    }

    // ────────────────────────────────────────────────────────────────────────
    // c) 两条分支都有 timestamp：按时间取最新，不按文件序
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("两个带时间戳的叶子：取时间更晚者，即使它写在文件更前面")
    void timedLeaves_takeNewestByTime_notFileOrder() throws Exception {
        writeRaw(List.of(
            // 文件序第 1 行，但时间更晚
            oldRow("assistant", "late", null, "2026-11-20T11:00:00Z"),
            // 文件序第 2 行，时间更早
            oldRow("assistant", "early", null, "2026-11-20T10:00:00Z")));

        AgentTranscript.AgentTranscriptResult r =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION, AGENT).orElseThrow();

        assertThat(r.messages()).extracting(AgentMessage::uuid)
            .as("按 timestamp 取最新 leaf（不是文件序最后一条）")
            .containsExactly("late");
    }

    @Test
    @DisplayName("时间戳可解析但相等：取先出现者（CC 严格大于语义），不因相等而换分支")
    void equalTimestamps_takeFirstOccurrence() throws Exception {
        writeRaw(List.of(
            oldRow("assistant", "first", null, "2026-11-20T10:00:00Z"),
            oldRow("assistant", "second", null, "2026-11-20T10:00:00Z")));

        AgentTranscript.AgentTranscriptResult r =
            AgentTranscript.getAgentTranscript(tmpDir, SESSION, AGENT).orElseThrow();

        assertThat(r.messages()).extracting(AgentMessage::uuid).containsExactly("first");
    }

    // ────────────────────────────────────────────────────────────────────────
    // d) 同一轮内时间戳单调：写入顺序 = 时间顺序（允许相等，不允许倒序）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同批写入：调用方给的更早时间戳被抬到上一条（禁止倒序），缺失的按落盘时刻补齐")
    void sameBatch_timestampsNeverGoBackwards() throws Exception {
        String earlier = OffsetDateTime.now().minusSeconds(60).toString();
        String later = OffsetDateTime.now().minusSeconds(10).toString();

        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("role", "user");
        m1.put("content", "第一条");
        m1.put("timestamp", earlier);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("role", "assistant");
        m2.put("content", "第二条（时间更早，必须被抬高）");
        m2.put("timestamp", earlier);   // 与 m1 相等 → 允许
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("role", "assistant");
        m3.put("content", "第三条（无时间戳）");
        m3.put("timestamp", "2000-01-01T00:00:00Z");   // 明显早于上一条 → 必须被抬高

        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION, AGENT,
            new ArrayList<>(List.of(m1, m2, m3)));

        List<JsonNode> rows = readRaw();
        assertThat(rows).hasSize(3);
        long t0 = millis(rows.get(0).path("timestamp").asText());
        long t1 = millis(rows.get(1).path("timestamp").asText());
        long t2 = millis(rows.get(2).path("timestamp").asText());
        assertThat(t0).as("首条保留调用方时间戳").isEqualTo(millis(earlier));
        assertThat(t1).as("相等允许").isEqualTo(t0);
        assertThat(t2).as("更早的时间戳被抬到上一条（不倒序）").isGreaterThanOrEqualTo(t1);

        // 缺失时间戳时取落盘时刻（晚于本次调用前的时间，且不统一成同一时间以外的假值）
        Map<String, Object> m4 = new LinkedHashMap<>();
        m4.put("role", "user");
        m4.put("content", "第四条（无时间戳）");
        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION, AGENT,
            new ArrayList<>(List.of(m4)));
        JsonNode last = readRaw().get(3);
        assertThat(last.hasNonNull("timestamp")).as("缺失时补写入时刻").isTrue();
        assertThat(millis(last.path("timestamp").asText()))
            .as("补的时间不得早于上一条（继续单调）").isGreaterThanOrEqualTo(t2);
    }
}

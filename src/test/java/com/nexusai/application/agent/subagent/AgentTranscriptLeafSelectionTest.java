package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * transcript 叶子选择 · SendMessage 通知行必须出现在读回的链里。
 *
 * <h2>WHY（意图验证 · 规则九）</h2>
 * <p>用户诉求：「主会话给子代理发 SendMessage 后，子代理视图里要看到『收到主代理通知』」。
 * 2026-11-20 实测（{@code sess-01c0afd5/agent-ab1bb3af453e9f75f.jsonl}，374 行）：
 * 通知行**已落盘**（第 69 行，role=user + subtype=agent_message + isMeta=true），但 REST
 * transcript 只回 71 条、agent_message=0 —— 因为文件里有两个 leaf 且 timestamp 均为 null
 * （tool 行不写 timestamp），旧实现取「文件序首条 leaf」= 更早写入的旧分支，通知行被截掉。
 *
 * <h2>RED teeth</h2>
 * <ul>
 *   <li>{@code findLatestLeaf} 退回「全无 timestamp 时取首条」⇒ 本用例红（只回旧分支 2 条、无通知行）；</li>
 *   <li>把「有 timestamp 优先」删掉、一律取末条 ⇒ 压缩双链头用例红（会选中压缩前旧链）。</li>
 * </ul>
 */
@DisplayName("[agent-notice] transcript 叶子选择：通知行不得被旧分支截掉")
class AgentTranscriptLeafSelectionTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "sess-1";
    private static final String AGENT = "a0f1e2d3c4b5a6978";

    private static ObjectNode row(String role, String uuid, String parentUuid) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        n.put("content", role + "-" + uuid);
        n.put("agentId", AGENT);
        n.put("isSidechain", true);
        n.put("uuid", uuid);
        if (parentUuid != null) n.put("parentUuid", parentUuid);
        return n;
    }

    private Path write(List<ObjectNode> rows) throws Exception {
        Path path = tmpDir.resolve(SESSION).resolve("subagents").resolve("agent-" + AGENT + ".jsonl");
        Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        for (ObjectNode n : rows) lines.add(MAPPER.writeValueAsString(n));
        Files.write(path, lines);
        return path;
    }

    private Optional<AgentTranscript.AgentTranscriptResult> read() {
        return AgentTranscript.getAgentTranscript(tmpDir, SESSION, AGENT);
    }

    @Test
    @DisplayName("两个无 timestamp 的 leaf：取最晚写入的链（含 SendMessage 通知行）")
    void untimedLeavesPreferLastWrittenChain() throws Exception {
        List<ObjectNode> rows = new ArrayList<>();
        // 旧分支（更早写入）：prompt → assistant → tool（tool 行无 timestamp，成为旧 leaf）
        rows.add(row("user", "u0", null));
        rows.add(row("assistant", "a1", "u0"));
        rows.add(row("tool", "t1", "a1"));
        // 分叉：通知行挂在 a1 上继续跑（真实形状：persistNotice 用旧链尾挂载）
        ObjectNode notice = row("user", "n1", "a1");
        notice.put("subtype", "agent_message");
        notice.put("isMeta", true);
        notice.put("author", "team-lead");
        notice.put("summary", "收束检索方案与文件清单");
        notice.put("timestamp", "2026-11-20T07:00:17.376242400Z");
        rows.add(notice);
        rows.add(row("assistant", "a2", "n1"));
        // 继续追加，最后一条 assistant 也是无 timestamp 的 leaf（最晚写入）
        rows.add(row("tool", "t2", "a2"));
        rows.add(row("assistant", "a3", "t2"));
        write(rows);

        AgentTranscript.AgentTranscriptResult r = read().orElseThrow();

        // 整条链都要回来（旧实现只回 u0/a1/t1 = 3 条，通知行丢失）
        assertThat(r.messages()).extracting(AgentMessage::uuid)
            .containsExactly("u0", "a1", "n1", "a2", "t2", "a3");

        AgentMessage noticeMsg = r.messages().stream()
            .filter(m -> "n1".equals(m.uuid())).findFirst().orElseThrow();
        // 前端通知条判据需要这三个字段原样透出
        assertThat(noticeMsg.role()).isEqualTo("user");
        assertThat(noticeMsg.subtype()).isEqualTo("agent_message");
        assertThat(noticeMsg.isMeta()).isTrue();
        assertThat(noticeMsg.author()).isEqualTo("team-lead");
        assertThat(noticeMsg.summary()).isEqualTo("收束检索方案与文件清单");
    }

    @Test
    @DisplayName("有 timestamp 时仍取时间最新者（压缩双链头不被旧链抢走）")
    void timedLeavesStillChooseNewest() throws Exception {
        List<ObjectNode> rows = new ArrayList<>();
        // 压缩前旧链：u0 → a1（leaf，较早）
        rows.add(row("user", "u0", null));
        ObjectNode oldLeaf = row("assistant", "a1", "u0");
        oldLeaf.put("timestamp", "2026-11-20T07:00:00Z");
        rows.add(oldLeaf);
        // 压缩边界开新链头（parentUuid=null，CC 语义）→ 压缩后新链（较晚）
        ObjectNode boundary = row("system", "b1", null);
        boundary.put("subtype", "compact_boundary");
        boundary.put("timestamp", "2026-11-20T08:00:00Z");
        rows.add(boundary);
        ObjectNode newLeaf = row("assistant", "a2", "b1");
        newLeaf.put("timestamp", "2026-11-20T09:00:00Z");
        rows.add(newLeaf);
        write(rows);

        AgentTranscript.AgentTranscriptResult r = read().orElseThrow();
        assertThat(r.messages()).extracting(AgentMessage::uuid)
            .containsExactly("b1", "a2");
    }
}

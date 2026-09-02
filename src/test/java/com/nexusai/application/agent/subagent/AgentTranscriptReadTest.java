package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.ContentReplacementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 [差异 4] AgentTranscript.getAgentTranscript + buildConversationChain + MessageFilters +
 * reconstructForSubagentResume.
 *
 * <p>规则九 (测试验证意图而非行为): 意图是 —
 * <ul>
 *   <li>resumeAgent 需读历史 transcript 重建对话链 (CC sessionStorage.ts:4190-4236)。
 *       不写 = 无法 resume: 异步 Agent 被 kill 后 transcript 无法恢复。</li>
 *   <li>filterUnresolvedToolUses (CC messages.ts:2795) 不能把残缺 tool_use 传给 LLM,
 *       但语义不同于 filterIncompleteToolCalls (runAgent.ts:866): 仅当 assistant 的
 *       <b>ALL</b> tool_use 均 unresolved 才丢弃 (保留 partial)。</li>
 *   <li>reconstructForSubagentResume (CC toolResultStorage.ts:1001) 合并 transcript
 *       contentReplacements 到 parentState, 父侧 inherited replacements gap-fill。</li>
 * </ul>
 */
@DisplayName("[S5] AgentTranscript 读取 + MessageFilters + reconstructForSubagentResume")
class AgentTranscriptReadTest {

    @TempDir
    Path tmpDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ────────────────────────────────────────────────────────────────────────
    // 夹具: 写 sidechain transcript JSONL (agentId + isSidechain + uuid/parentUuid)
    // ────────────────────────────────────────────────────────────────────────

    private static ObjectNode entry(String role, String content, String agentId,
                                    boolean isSidechain, String uuid, String parentUuid) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        n.put("agentId", agentId);
        n.put("isSidechain", isSidechain);
        n.put("uuid", uuid);
        if (parentUuid != null) n.put("parentUuid", parentUuid);
        return n;
    }

    private Path writeTranscript(String agentId, List<ObjectNode> entries) throws Exception {
        Path path = tmpDir.resolve("session-1").resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        for (ObjectNode e : entries) {
            sb.append(MAPPER.writeValueAsString(e)).append("\n");
        }
        Files.writeString(path, sb.toString());
        return path;
    }

    /**
     * CC JSONL content-replacement 条目夹具 · 对齐 sessionStorage.ts:1119-1122:
     * {@code {type:'content-replacement', sessionId, agentId, replacements:[{kind, toolUseId, replacement}]}}.
     */
    private static ObjectNode contentReplacementEntry(String agentId, String toolUseId, String replacement) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "content-replacement");
        n.put("sessionId", "session-1");
        n.put("agentId", agentId);
        ArrayNode replacements = n.putArray("replacements");
        ObjectNode rec = replacements.addObject();
        rec.put("kind", "tool-result");
        rec.put("toolUseId", toolUseId);
        rec.put("replacement", replacement);
        return n;
    }

    // ────────────────────────────────────────────────────────────────────────
    // getAgentTranscript
    // ────────────────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────────────────
    // recordSidechainTranscript 跨调用链（CC startingParentUuid）· W5-03b
    // ────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> msgMap(String role, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    void recordSidechainTranscript_chainsAcrossCalls_withStartingParentUuid() throws Exception {
        // WHY (规则九): CC 主会话后台化逐消息写 (LocalMainSessionTask.ts:411-419)
        //   recordSidechainTranscript([event], taskId, lastRecordedUuid) — 每条新消息以
        //   lastRecordedUuid 作为 startingParentUuid 链到上一条. 若 Java 工具类缺少该参数,
        //   跨 write() 调用的消息各自 parentUuid=null → getAgentTranscript leaf 查找
        //   (uuid 非任何 parentUuid, CC :4210-4214) 命中首条而非末条 → 链重建丢失后半段 → resume 残缺.
        // 断言: 两次独立写入 (pre-background 批量 + 逐消息追加) 重建出完整 first→second→third 链.
        Map<String, Object> second = msgMap("assistant", "second");
        AgentTranscript.recordSidechainTranscript(tmpDir, "session-1", "agent-x", List.of(
            msgMap("user", "first"), second));

        // 逐消息追加: startingParentUuid = 上一条已写消息的 uuid (CC lastRecordedUuid)
        String lastUuid = String.valueOf(second.get("uuid"));
        AgentTranscript.recordSidechainTranscript(tmpDir, "session-1", "agent-x",
            List.of(msgMap("user", "third")), lastUuid);

        Optional<AgentTranscript.AgentTranscriptResult> result =
            AgentTranscript.getAgentTranscript(tmpDir, "session-1", "agent-x");
        assertThat(result).isPresent();
        // 跨调用追加的消息必须链到最后写入的消息 (CC startingParentUuid → parentUuid)
        assertThat(result.get().messages()).extracting(AgentMessage::content)
            .containsExactly("first", "second", "third");
        assertThat(result.get().messages().get(2).parentUuid()).isEqualTo(lastUuid);
    }

    @Test
    void getAgentTranscript_readsJsonlAndBuildsConversationChain() throws Exception {
        // WHY: resumeAgent 需读历史 transcript 重建对话链 (CC sessionStorage.ts:4190-4236)
        // 不写 = 无法 resume: 异步 Agent 被 kill 后 transcript 无法恢复
        writeTranscript("agent-x", List.of(
            entry("user", "first", "agent-x", true, "u1", null),
            entry("assistant", "working", "agent-x", true, "u2", "u1"),
            entry("user", "more", "agent-x", true, "u3", "u2")));

        Optional<AgentTranscript.AgentTranscriptResult> result =
            AgentTranscript.getAgentTranscript(tmpDir, "session-1", "agent-x");
        assertThat(result).isPresent();
        List<AgentMessage> messages = result.get().messages();
        assertThat(messages).hasSize(3);
        // parentUuid 链重建 → 顺序为 u1 → u2 → u3
        assertThat(messages.get(0).uuid()).isEqualTo("u1");
        assertThat(messages.get(1).uuid()).isEqualTo("u2");
        assertThat(messages.get(2).uuid()).isEqualTo("u3");
        assertThat(messages.get(1).parentUuid()).isEqualTo("u1");
        assertThat(messages.get(2).parentUuid()).isEqualTo("u2");
    }

    @Test
    void getAgentTranscript_filtersByAgentId() throws Exception {
        // 断言: 只返匹配 agentId 的消息, 其他 agent 的消息排除 (CC :4201-4203 filter agentId && isSidechain)
        writeTranscript("agent-x", List.of(
            entry("user", "a", "agent-x", true, "x1", null),
            entry("assistant", "b", "other", true, "o1", null)));

        Optional<AgentTranscript.AgentTranscriptResult> result =
            AgentTranscript.getAgentTranscript(tmpDir, "session-1", "agent-x");
        assertThat(result).isPresent();
        assertThat(result.get().messages()).extracting(AgentMessage::role)
            .containsExactly("user");
    }

    @Test
    void getAgentTranscript_returnsEmpty_whenFileNotExists() {
        // 边界: 文件不存在 → 返空 Optional (不抛) (CC :4233 catch → null)
        Optional<AgentTranscript.AgentTranscriptResult> result =
            AgentTranscript.getAgentTranscript(tmpDir, "session-missing", "agent-z");
        assertThat(result).isEmpty();
    }

    @Test
    void getAgentTranscript_parsesContentReplacementEntries_ccJsonlFormat() throws Exception {
        // WHY (规则九): transcript 内 content-replacement 条目是 CC JSONL 结构
        // {type:'content-replacement', agentId?, replacements:[{kind:'tool-result', toolUseId, replacement}]}
        // (sessionStorage.ts:1119-1122 写入侧 + :3682-3693 解析侧, toolResultStorage.ts:475-479 记录字段).
        // 旧实现查顶层 toolUseId+preview → 真实写入方 SessionStorage.writeContentReplacement
        // (R28-3.7) 的条目被误判为消息节点, contentReplacements 恒空 → resume 时替换状态丢失.
        // 断言: 按 CC 格式写入的条目被解析为 ContentReplacementRecord, 且仅归入匹配 agentId 的桶
        // (CC loadTranscriptFile agentContentReplacements.get(agentId), 无 agentId 条目按 sessionId 归桶).
        writeTranscript("agent-x", List.of(
            entry("user", "go", "agent-x", true, "u1", null),
            entry("assistant", "call", "agent-x", true, "u2", "u1"),
            contentReplacementEntry("agent-x", "t1", "t1-replacement"),
            contentReplacementEntry("other-agent", "t2", "other-replacement")));

        Optional<AgentTranscript.AgentTranscriptResult> result =
            AgentTranscript.getAgentTranscript(tmpDir, "session-1", "agent-x");
        assertThat(result).isPresent();
        // 只解析出 agent-x 的记录; other-agent 条目被归桶排除
        assertThat(result.get().contentReplacements()).hasSize(1);
        ContentReplacementRecord r = result.get().contentReplacements().get(0);
        assertThat(r.kind()).isEqualTo("tool-result");
        assertThat(r.toolUseId()).isEqualTo("t1");
        assertThat(r.replacement()).isEqualTo("t1-replacement");
        // content-replacement 条目不是消息, 不影响消息解析
        assertThat(result.get().messages()).hasSize(2);
    }

    // ────────────────────────────────────────────────────────────────────────
    // readMetadata/writeMetadata 3 字段契约（[#25] 删 model 字段 · 对齐 CC sessionStorage.ts:264-272）
    // ────────────────────────────────────────────────────────────────────────

    private Path writeMeta(String agentId, ObjectNode node) throws Exception {
        Path path = tmpDir.resolve("session-1").resolve("subagents")
            .resolve("agent-" + agentId + ".meta.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, MAPPER.writeValueAsString(node));
        return path;
    }

    @Test
    void readMetadata_legacyMetaWithoutModel_parsesTypeAndOptionalFields() throws Exception {
        // WHY (#25 删字段): AgentMetadata.model 已删除（对齐 CC sessionStorage.ts:264-272 无 model 字段）；
        //   旧 meta 文件（仅 agentType）必须正常读回（type 解析 + 可选字段 null），不崩溃 —— 兼容既有 transcript。
        writeMeta("meta-x", MAPPER.createObjectNode().put("agentType", "fork"));

        Optional<AgentTranscript.AgentMetadata> meta =
            AgentTranscript.readMetadata(tmpDir, "session-1", "meta-x");

        assertThat(meta).isPresent();
        assertThat(meta.get().agentType()).isEqualTo("fork");
        assertThat(meta.get().worktreePath()).isNull();
        assertThat(meta.get().description()).isNull();
    }

    @Test
    void writeMetadata_roundtripsTypeWorktreeDescription_ignoresLegacyModelKey() throws Exception {
        // WHY (#25 删字段): AgentMetadata.model 已删除（CC sessionStorage.ts:264-272 无 model 字段）——
        //   writeMetadata/readMetadata 3 字段（agentType/worktreePath/description）读写一致；旧 meta 文件
        //   残留 "model" JSON 键被 readMetadata 忽略（对齐 CC JSON.parse 天然容错，resume 模型改现算）。
        Path p1 = tmpDir.resolve("session-1").resolve("subagents")
            .resolve("agent-roundtrip.meta.json");
        Files.createDirectories(p1.getParent());
        AgentTranscript.AgentMetadata withFields =
            new AgentTranscript.AgentMetadata("fork", "/wt/path", "desc");
        AgentTranscript.writeMetadata(tmpDir, "session-1", "roundtrip", withFields);

        Optional<AgentTranscript.AgentMetadata> readBack =
            AgentTranscript.readMetadata(tmpDir, "session-1", "roundtrip");
        assertThat(readBack).isPresent();
        assertThat(readBack.get().agentType()).isEqualTo("fork");
        assertThat(readBack.get().worktreePath()).isEqualTo("/wt/path");
        assertThat(readBack.get().description()).isEqualTo("desc");

        // 旧 meta 残留 model 键 → 忽略（3 字段读回正常）
        Path p2 = tmpDir.resolve("session-1").resolve("subagents")
            .resolve("agent-legacy.meta.json");
        Files.createDirectories(p2.getParent());
        ObjectNode legacyNode = MAPPER.createObjectNode();
        legacyNode.put("agentType", "fork");
        legacyNode.put("model", "claude-sonnet-4");
        Files.writeString(p2, MAPPER.writeValueAsString(legacyNode));

        Optional<AgentTranscript.AgentMetadata> legacyRead =
            AgentTranscript.readMetadata(tmpDir, "session-1", "legacy");
        assertThat(legacyRead).isPresent();
        assertThat(legacyRead.get().agentType()).isEqualTo("fork");
        assertThat(legacyRead.get().worktreePath()).isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // readMetadata model 缺失容错（[RES-R6-1] mainLoopModel 通道）

    // ────────────────────────────────────────────────────────────────────────
    // filterUnresolvedToolUses (CC messages.ts:2795)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void filterUnresolvedToolUses_filtersAssistantWithUnresolvedToolUse() {
        // WHY: resumeAgent 不能把残缺 tool_use 传给 LLM (messages.ts:2795)
        // 断言: assistant 消息含 tool_use 无对应 tool_result → 被过滤
        List<AgentMessage> messages = List.of(
            AgentMessage.of("user", "go"),
            new AgentMessage("assistant", "call", false, "a1", true, "m2", "m1",
                List.of(new AgentMessage.ToolCallInfo("t1", "Bash", "{}")), null));

        List<AgentMessage> filtered = MessageFilters.filterUnresolvedToolUses(messages);
        assertThat(filtered).extracting(AgentMessage::role).containsExactly("user");
    }

    @Test
    void filterUnresolvedToolUses_keepsCompleteToolCalls() {
        // 反向: tool_use 有对应 tool_result → 保留 (CC messages.ts:2795 仅 ALL unresolved 才丢)
        List<AgentMessage> messages = List.of(
            new AgentMessage("assistant", "call", false, "a1", true, "m2", "m1",
                List.of(new AgentMessage.ToolCallInfo("t1", "Bash", "{}")), null),
            new AgentMessage("tool", "result", false, "a1", true, "m3", "m2", List.of(), "t1"));

        List<AgentMessage> filtered = MessageFilters.filterUnresolvedToolUses(messages);
        assertThat(filtered).hasSize(2);
    }

    // ────────────────────────────────────────────────────────────────────────
    // reconstructForSubagentResume (CC toolResultStorage.ts:1001)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void reconstructForSubagentResume_mergesContentReplacements() {
        // 断言: transcript.contentReplacements 被合并到 contentReplacementState
        // parentState 有已替换的 "t0"; transcript 有 "t1" 的新替换; 父侧 inherited 不覆盖已存在替换
        ContentReplacementState parent = ContentReplacementState.create();
        parent.recordReplacement("t0", "parent-preview");

        List<AgentMessage> resumedMessages = List.of(
            new AgentMessage("assistant", "call", false, "a1", true, "m2", "m1",
                List.of(
                    new AgentMessage.ToolCallInfo("t0", "Bash", "{}"),
                    new AgentMessage.ToolCallInfo("t1", "Bash", "{}")), null));

        List<ContentReplacementRecord> sidechainRecords = List.of(
            new ContentReplacementRecord("tool-result", "t1", "t1-replacement"));

        ContentReplacementState resumed = ContentReplacementState.reconstructForSubagentResume(
            parent, resumedMessages, sidechainRecords);

        assertThat(resumed.getReplacement("t1")).isEqualTo("t1-replacement");
        // parent 的 inherited replacement gap-fill (t0 在 candidateIds 中)
        assertThat(resumed.getReplacement("t0")).isEqualTo("parent-preview");
        // 已 seen 的 id
        assertThat(resumed.isSeen("t0")).isTrue();
        assertThat(resumed.isSeen("t1")).isTrue();
    }
}

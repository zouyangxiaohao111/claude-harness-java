package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.cost.SessionUsageAccountingRegistry;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.tool.impl.SendMessageTool;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * lineage 的生产触发点 · 对齐 CC 恢复门（claude.exe 2.1.328 偏移 333967189：
 * {@code (meta.stoppedByUser || 伴生 found) && !userInitiated && !lineage → k9}）。
 *
 * <p>语义收窄（对齐 CC 唯一生产来源「恢复 fork 子代理时先递归恢复它的父」）：
 * lineage 只在<b>后代发起、恢复其祖先</b>时为真；父恢复子不是 lineage。
 * 本用例锁判据：
 * <ol>
 *   <li>a 后代（子代理）发起恢复被用户停掉的父 → lineage=true → 放行；</li>
 *   <li>b 父（祖先）发起恢复子代理 → lineage=false → k9 拒绝；</li>
 *   <li>b2 无关方发起 → lineage=false → k9 拒绝；</li>
 *   <li>c 用户直接发起（userInitiated）与 lineage 各自独立旁路（用户放行、无人旁路仍拒）；</li>
 *   <li>d 工具入参无法自报 lineage（schema 无该字段；发起方身份来自 ToolUseContext）。</li>
 * </ol>
 */
@DisplayName("[lineage] 恢复门的血统触发点（子代理）")
class AgentLineageProductionTriggerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    // 三个夹具 agentId 必须互不相同（账本节点键按 agentId 分节点）。
    private static final String TARGET_HEX = "a000000000001111";
    private static final String CHILD_HEX = "a000000000002222";
    private static final String OTHER_HEX = "a000000000003333";
    private static final String SESSION = "sess-lineage-1";

    @TempDir
    Path tmpDir;

    private ResumeService service;
    private SessionUsageAccountingRegistry accounting;

    @BeforeEach
    void setUp() {
        SessionProjectRootTestSupport.declareNoDatabase();
        com.nexusai.application.agent.prompt.SessionPromptCacheRegistry.resetForTest();
        service = new ResumeService();
        service.setSubagentTool(mock(SubagentTool.class));
        service.setSessionAgentStateRegistry(new SessionAgentStateRegistry());
        accounting = new SessionUsageAccountingRegistry();
        service.setUsageAccountingRegistry(accounting);
    }

    @AfterEach
    void tearDown() {
        SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a · 后代（子代理）发起恢复被用户停掉的父代理 → lineage=true，恢复放行")
    void descendantResume_lineageTrue_allowed() throws Exception {
        stopTargetAndWriteTranscript();
        SessionUsageLedger ledger = ledger();
        node(ledger, TARGET_HEX, null, 1);
        node(ledger, CHILD_HEX, TARGET_HEX, 2);   // 真实父子边：child.parentAgentId = target

        ResumeAgentResult result = service.resumeAgentBackgroundForLineage(
            TARGET_HEX, "continue", tmpDir, SESSION, null, false, CHILD_HEX);

        assertThat(result.agentId()).isEqualTo(TARGET_HEX);
        assertThat(ledger.attributionOf(CHILD_HEX).parentAgentId())
            .as("前提：账本里 child 的父就是 target").isEqualTo(TARGET_HEX);
    }

    @Test
    @DisplayName("b · 父（祖先）发起恢复子代理 → lineage=false，用户停过的子代理被 k9 拒绝")
    void ancestorResumeChild_isNotLineage_k9() throws Exception {
        stopAndWriteTranscript(CHILD_HEX);
        SessionUsageLedger ledger = ledger();
        node(ledger, TARGET_HEX, null, 1);
        node(ledger, CHILD_HEX, TARGET_HEX, 2);   // 真实父子边：child.parentAgentId = target

        assertThatThrownBy(() -> service.resumeAgentBackgroundForLineage(
            CHILD_HEX, "continue", tmpDir, SESSION, null, false, TARGET_HEX))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("was stopped and won't be resumed")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("b2 · 无关方发起恢复 → lineage=false，用户停过的 agent 被 k9 拒绝")
    void unrelatedResume_lineageFalse_k9() throws Exception {
        stopTargetAndWriteTranscript();
        SessionUsageLedger ledger = ledger();
        node(ledger, TARGET_HEX, null, 1);
        node(ledger, OTHER_HEX, null, 1);          // 与目标无父子边

        assertThatThrownBy(() -> service.resumeAgentBackgroundForLineage(
            TARGET_HEX, "continue", tmpDir, SESSION, null, false, OTHER_HEX))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .hasMessageContaining("was stopped and won't be resumed")
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("c · 用户直接发起（userInitiated）→ 独立放行；无 userInitiated/lineage 时仍拒")
    void userInitiated_independentBypass() throws Exception {
        stopTargetAndWriteTranscript();

        // userInitiated 与 lineage 各自独立旁路、无优先级：lineage=false 也不影响用户本人恢复。
        assertThatCode(() -> service.resumeAgentBackgroundForLineage(
            TARGET_HEX, "continue", tmpDir, SESSION, null, true, OTHER_HEX))
            .doesNotThrowAnyException();
        // 对照：既非用户发起也无 lineage（用户刚 resume 已清标记 ⇒ 重新打标）
        stopTargetAndWriteTranscript();
        assertThatThrownBy(() -> service.resumeAgentBackgroundForLineage(
            TARGET_HEX, "continue", tmpDir, SESSION, null, false, OTHER_HEX))
            .isInstanceOf(ResumeErrorCode.ResumeException.class)
            .satisfies(e -> assertThat(((ResumeErrorCode.ResumeException) e).errorCode())
                .isEqualTo(ResumeErrorCode.AGENT_STOPPED_BY_USER));
    }

    @Test
    @DisplayName("d · lineage 不可由工具入参自报：SendMessage schema 无 lineage 字段")
    void lineageNotToolControllable() {
        SendMessageTool tool = new SendMessageTool(null);
        assertThat(tool.inputSchema().path("properties").has("lineage"))
            .as("lineage 是编排层现算值，⛔ 不是模型可填参数").isFalse();
        assertThat(tool.inputSchema().path("properties").has("userInitiated")).isFalse();
    }

    @Test
    @DisplayName("判据纯函数：后代发起祖先 true / 父恢复子 false / 无关方 false / 自身 false")
    void linkedPredicate() {
        java.util.Map<String, String> parents = java.util.Map.of(
            CHILD_HEX, TARGET_HEX, TARGET_HEX, SESSION);
        java.util.function.Function<String, String> parentOf = parents::get;

        assertThat(AgentLineage.linked(CHILD_HEX, TARGET_HEX, parentOf))
            .as("后代发起、恢复祖先 → 唯一为真的方向").isTrue();
        assertThat(AgentLineage.linked(TARGET_HEX, CHILD_HEX, parentOf))
            .as("父恢复子不是 lineage").isFalse();
        assertThat(AgentLineage.linked(CHILD_HEX, OTHER_HEX, parentOf)).isFalse();
        assertThat(AgentLineage.linked(TARGET_HEX, TARGET_HEX, parentOf)).isFalse();
        assertThat(AgentLineage.linked(CHILD_HEX, SESSION, parentOf))
            .as("Session 根也在祖先链上").isTrue();
        assertThat(AgentLineage.linked(null, TARGET_HEX, parentOf)).isFalse();
    }

    @Test
    @DisplayName("teammate 判据：member→lead true / lead→member false / 兄弟 false / 主会话→member false")
    void teammateLinkedPredicate() {
        String lead = "team-lead@t1";
        String member = "mate@t1";
        String sibling = "mate2@t1";

        assertThat(AgentLineage.teammateLinked(member, lead, lead))
            .as("成员恢复 lead（后代发起祖先）").isTrue();
        assertThat(AgentLineage.teammateLinked(lead, member, lead))
            .as("lead 恢复成员（父恢复子）不是 lineage").isFalse();
        assertThat(AgentLineage.teammateLinked(null, member, lead))
            .as("主会话（即 lead）恢复成员不是 lineage").isFalse();
        assertThat(AgentLineage.teammateLinked(sibling, member, lead)).isFalse();
        assertThat(AgentLineage.teammateLinked(member, lead, "team-lead@t2"))
            .as("别队的 lead 不是我的祖先").isFalse();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private SessionUsageLedger ledger() {
        return accounting.forSession(SESSION);
    }

    /** 在账本里登记一个 agent 节点（生产由 spawn 点 attach 写入，此处直接走公开 record 路径）。 */
    private void node(SessionUsageLedger ledger, String agentId, String parentAgentId, int depth) {
        ledger.record(new UsageAttribution(SESSION, agentId, UsageCategory.SUBAGENT,
                parentAgentId, depth, "task"),
            null, null, 0.0, false, false, null, 0L, null, 0, 0);
    }

    /** 目标：transcript 就绪 + 磁盘 stopped-by-user 伴生文件（k9 的判据源）。 */
    private void stopTargetAndWriteTranscript() throws Exception {
        stopAndWriteTranscript(TARGET_HEX);
    }

    /** 任意 agent：transcript 就绪 + 磁盘 stopped-by-user 伴生文件（k9 的判据源）。 */
    private void stopAndWriteTranscript(String agentId) throws Exception {
        Path path = tmpDir.resolve(SESSION).resolve("subagents").resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append(JSON.writeValueAsString(entry("user", "go", "u1", null, agentId))).append('\n');
        sb.append(JSON.writeValueAsString(entry("assistant", "work", "u2", "u1", agentId)))
            .append('\n');
        Files.writeString(path, sb.toString());
        AgentTranscript.markStoppedByUser(tmpDir, SESSION, agentId);
        assertThat(AgentTranscript.isStoppedByUserOrMeta(tmpDir, SESSION, agentId))
            .as("前提：用户停过").isTrue();
    }

    private static ObjectNode entry(String role, String content, String uuid, String parentUuid,
                                    String agentId) {
        ObjectNode n = JSON.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        n.put("agentId", agentId);
        n.put("isSidechain", true);
        n.put("uuid", uuid);
        if (parentUuid != null) {
            n.put("parentUuid", parentUuid);
        }
        return n;
    }
}

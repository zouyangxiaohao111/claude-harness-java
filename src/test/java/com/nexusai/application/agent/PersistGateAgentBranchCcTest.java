package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP2-05] persist gate AGENT_ 死分支修复（DRIFT-2）· SUBAGENT/FORK content replacement 落 sidechain。
 *
 * <p>CC 真源（query.ts:376-378，Read 自验）：
 * <pre>
 * const persistReplacements =
 *   querySource.startsWith('agent:') ||
 *   querySource.startsWith('repl_main_thread')
 * </pre>
 * persist=true 时 {@code recordContentReplacement(records, toolUseContext.agentId)} ——
 * agentId 非空 → sidechain file（AgentTool resume 重建源）；agentId 空 → session.jsonl（/resume）。
 * <p>探查证据（05 DRIFT-2 / QV-22）：基线 8e1437ff:5777 {@code name().startsWith("AGENT" + "_")}
 * 为死分支（QuerySource 无该大写前缀枚举名）→ SUBAGENT/FORK replacement 永不落 sidechain →
 * resume 重建缺记录源（风险 7）。IMP2-01 canonical 归一（SUBAGENT→agent:subagent /
 * FORK→agent:builtin:fork）复活该分支；本测试锁定闭环：gate 矩阵 + sidechain 落库 +
 * main-thread 回归不变 + resume 重建（CC resumeAgent.ts:75-79 reconstructForSubagentResume）。
 *
 * <p>规则九（测试验证意图而非行为）：意图是「子 agent 的 content replacement 决策在
 * resume 时能从记录恢复（prompt cache 前缀稳定）」——不是锁定某一行代码。
 */
@DisplayName("[IMP2-05] persist gate DRIFT-2：SUBAGENT/FORK → sidechain 落库 + resume 重建（CC query.ts:376-378）")
class PersistGateAgentBranchCcTest {

    @TempDir
    Path workspaceDir;

    /** budgetAggregateGate=true（第 7 位，tengu_hawthorn_steeple）· 聚合预算路径测试用（同 ApplyPerMessageBudgetBudgetConstantTest）。 */
    private static final FeatureFlags GATE_ON =
        new FeatureFlags(false, false, false, false, false, false, true,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false);

    private static final String SESSION = "00000000-0000-0000-0000-0000000000a1";
    private static final UUID SUBAGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String SESSION_STR = SESSION.toString();

    // ────────────────────────────────────────────────────────────────────────
    // 夹具（复用 ApplyPerMessageBudgetBudgetConstantTest 形态）
    // ────────────────────────────────────────────────────────────────────────

    private AgentLoopContext buildCtx(AgentLoopContext.LoopSessionState session, FeatureFlags flags) {
        // record 32 参 compat（32 参构造器布局）：20=FeatureFlags、31=LoopSessionState，其余 null
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null,   // 1-10
            null, null, null, null, null, null, null, null, null,         // 11-19
            flags,                                                        // 20 FeatureFlags
            null, null, null, null, null, null, null, null, null, null,   // 21-30
            session, null);                                               // 31-32
    }

    private ChatMessageDto asst(String id, String asstMsgId, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, SESSION_STR, Role.assistant, "assistant",
            "assistant text " + id, null, toolCalls, FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, asstMsgId,
            null, null, List.of());
    }

    private ChatMessageDto tool(String id, String toolCallId, String asstId, String content) {
        return new ChatMessageDto(id, SESSION_STR, Role.tool, "tool",
            content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), toolCallId, asstId,
            null, null, List.of());
    }

    /** 同组 150K+80K > 200K 聚合预算 → 最大 fresh（call_t1）被持久化为 preview（CC selectFreshToReplace）。 */
    private AgentState overBudgetState(UUID agentId) {
        ChatMessageDto asst = asst("asst-1", "A", List.of(
            new ToolCallDto("call_t1", "Bash", null, null, false),
            new ToolCallDto("call_t2", "Bash", null, null, false)));
        ChatMessageDto toolBig = tool("tool-1", "call_t1", "A", "x".repeat(150_000));
        ChatMessageDto toolSmall = tool("tool-2", "call_t2", "A", "y".repeat(80_000));
        AgentState state = new AgentState("sys", SESSION, agentId);
        state.replaceMessages(List.of(asst, toolBig, toolSmall));
        return state;
    }

    private AgentLoopContext.LoopSessionState sessionWith() {
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        session.setContentReplacementState(ContentReplacementState.create());
        session.setWorkspaceDir(workspaceDir);
        return session;
    }

    private String contentOf(AgentState state, String toolCallId) {
        return state.messages().stream()
            .filter(m -> toolCallId.equals(m.toolCallId()))
            .findFirst()
            .orElseThrow()
            .content();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1) gate 矩阵 · CC query.ts:376-378
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gate 矩阵: agent:*（SUBAGENT/FORK）与 repl_main_thread（USER/REPL_MAIN_THREAD）persist=true，其余 false（CC query.ts:376-378）")
    void gateMatrix_matchesCcQuerySource376_378() {
        // DRIFT-2 复活载体：canonical 值域必须带 agent: 前缀（IMP2-01 映射侧归一）
        assertThat(QuerySource.SUBAGENT.canonical()).isEqualTo("agent:subagent");
        assertThat(QuerySource.FORK.canonical()).isEqualTo("agent:builtin:fork");
        assertThat(QuerySource.REPL_MAIN_THREAD.canonical()).isEqualTo("repl_main_thread");

        Map<QuerySource, Boolean> expected = new HashMap<>();
        expected.put(QuerySource.SUBAGENT, true);          // agent:subagent → agent: 前缀
        expected.put(QuerySource.FORK, true);              // agent:builtin:fork → agent: 前缀
        expected.put(QuerySource.USER, true);              // canonical=repl_main_thread（CC 主线程唯一值）
        expected.put(QuerySource.REPL_MAIN_THREAD, true);  // repl_main_thread 前缀
        expected.put(QuerySource.COMPACT, false);          // 'compact'
        expected.put(QuerySource.SESSION_MEMORY, false);   // 'session_memory'
        expected.put(QuerySource.SDK, false);              // 'sdk'
        expected.put(QuerySource.HOOK_AGENT, false);       // 'hook_agent'
        expected.put(QuerySource.EXTRACT_MEMORIES, false); // 'extract_memories'
        expected.put(QuerySource.AUTO_DREAM, false);       // 'auto_dream'
        expected.put(QuerySource.MARBLE_ORIGAMI, false);   // 'marble_origami'
        expected.put(QuerySource.WORKFLOW, false);         // [Fix-D4] 'workflow'（claudeCodeBackend.ts:304）：非 agent:/repl_main_thread 前缀 → 不持久化（CC query.ts:376-378 语义）

        for (QuerySource qs : QuerySource.values()) {
            assertThat(LlmAgentLoop.shouldPersistReplacements(qs))
                .as("shouldPersistReplacements(%s) canonical=%s（CC query.ts:376-378）", qs, qs.canonical())
                .isEqualTo(expected.get(qs));
        }
        assertThat(LlmAgentLoop.shouldPersistReplacements(null))
            .as("null querySource → false（CC persistReplacements undefined 语义）")
            .isFalse();
    }

    @Test
    @DisplayName("[IMP2-05] persist gate 精确值不变性: 运行时 agentType 级值（agent:builtin:<type>/agent:custom）仍以 agent: 前缀命中（CC query.ts:376-378）")
    void runtimeExactValues_keepAgentPrefix_forPersistGate() {
        // WHY（IMP2-05）: 发射侧改用 effectiveValue 后 querySource 从 'agent:subagent' 聚合占位
        // 升级为 agentType 级精确值（agent:builtin:<type>/agent:custom/agent:default，CC
        // promptCategory.ts:16-28 → AgentTool.tsx:609）。persist gate（query.ts:376-378 按字符串
        // startsWith('agent:') 判定）必须对精确值同样命中 —— 否则精确化后子 agent 的 content
        // replacement 不再落 sidechain（resume 重建记录源丢失，DRIFT-2 回退）。本断言锁
        // 「effectiveValue 产出的发射值恒保留 agent: 前缀」→ 与枚举 canonical（agent: 前缀）同命中。
        for (String exact : new String[]{
            "agent:builtin:Explore", "agent:builtin:general", "agent:custom",
            "agent:default", "agent:builtin:fork"}) {
            assertThat(exact).as("运行时精确值 %s 必须以 agent: 前缀开头（persist gate 命中前提）", exact)
                .startsWith("agent:");
            assertThat(QuerySource.effectiveValue(QuerySource.SUBAGENT, exact))
                .as("发射侧 effectiveValue(%s) 必须保留 agent: 前缀", exact)
                .startsWith("agent:");
        }
        // 对照：枚举类别 persist gate 仍 true（既有 IMP2-01 语义，不被精确化改变）
        assertThat(LlmAgentLoop.shouldPersistReplacements(QuerySource.SUBAGENT)).isTrue();
        assertThat(LlmAgentLoop.shouldPersistReplacements(QuerySource.FORK)).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2) SUBAGENT/FORK 场景：persist=true → sidechainRecords 落库
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SUBAGENT 场景: replacement 落 sidechain（{session}/subagents/agent-{agentId}.jsonl），文件含 content-replacement 条目")
    void subagentScenario_replacementPersistsToSidechain() {
        AgentState state = overBudgetState(SUBAGENT_ID);
        AgentLoopContext ctx = buildCtx(sessionWith(), GATE_ON);

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.SUBAGENT, Set.of());

        // 落库证据：内容被替换为 preview（聚合预算路径生效）
        assertThat(contentOf(state, "call_t1"))
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
        // 落库证据：sidechain 文件存在且为 content-replacement 条目（R28-3.7 §1.3 agentId 路由）
        Path sidechain = SessionStorage.getAgentTranscriptPath(workspaceDir, SESSION_STR, SUBAGENT_ID.toString());
        assertThat(sidechain).exists();
        String content = readAll(sidechain);
        assertThat(content)
            .as("SUBAGENT 场景必须写 sidechain 文件（resume 重建记录源，DRIFT-2）")
            .contains("\"type\":\"content-replacement\"")
            .contains("\"agentId\":\"" + SUBAGENT_ID + "\"")
            .contains("\"toolUseId\":\"call_t1\"");
    }

    @Test
    @DisplayName("FORK 场景: replacement 落 sidechain（fork 子 agent agentId 非空 → sidechain 文件）")
    void forkScenario_replacementPersistsToSidechain() {
        AgentState state = overBudgetState(SUBAGENT_ID);
        AgentLoopContext ctx = buildCtx(sessionWith(), GATE_ON);

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.FORK, Set.of());

        assertThat(contentOf(state, "call_t1"))
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
        Path sidechain = SessionStorage.getAgentTranscriptPath(workspaceDir, SESSION_STR, SUBAGENT_ID.toString());
        assertThat(sidechain)
            .as("FORK（agent:builtin:fork）场景必须写 sidechain（CC query.ts:376-378 agent: 前缀）")
            .exists();
        assertThat(readAll(sidechain))
            .contains("\"type\":\"content-replacement\"")
            .contains("\"toolUseId\":\"call_t1\"");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3) main-thread 行为回归不变（REPL_MAIN_THREAD 路径不受影响）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("main-thread 回归: REPL_MAIN_THREAD + agentId=null → 写 session.jsonl，不写 sidechain")
    void mainThreadRegression_replMainThread_writesSessionJsonl_notSidechain() {
        AgentState state = overBudgetState(null);   // agentId=null = 主线程
        AgentLoopContext ctx = buildCtx(sessionWith(), GATE_ON);

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.REPL_MAIN_THREAD, Set.of());

        assertThat(contentOf(state, "call_t1"))
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
        Path sessionFile = SessionStorage.getSessionFile(workspaceDir, SESSION_STR);
        assertThat(sessionFile)
            .as("REPL_MAIN_THREAD 必须写 session.jsonl（/resume 用，行为回归不变）")
            .exists();
        assertThat(readAll(sessionFile)).contains("\"type\":\"content-replacement\"");
        assertThat(workspaceDir.resolve(SESSION_STR).resolve(SessionStorage.SUBAGENTS_SUBDIR))
            .as("主线程不得产生 sidechain 子目录")
            .doesNotExist();
    }

    @Test
    @DisplayName("USER（agentId=null）同主线程语义: canonical=repl_main_thread → session.jsonl")
    void userMainThread_writesSessionJsonl() {
        AgentState state = overBudgetState(null);
        AgentLoopContext ctx = buildCtx(sessionWith(), GATE_ON);

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.USER, Set.of());

        assertThat(contentOf(state, "call_t1"))
            .startsWith(ToolResultStorage.PERSISTED_OUTPUT_TAG);
        assertThat(SessionStorage.getSessionFile(workspaceDir, SESSION_STR))
            .as("USER canonical=repl_main_thread → session.jsonl（CC 主线程唯一值 repl_main_thread）")
            .exists();
        assertThat(workspaceDir.resolve(SESSION_STR).resolve(SessionStorage.SUBAGENTS_SUBDIR))
            .doesNotExist();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4) resume 集成测试：sidechain 记录可重建 replacement
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resume 集成: sidechain content-replacement 记录经 getAgentTranscript 读回 → reconstructForSubagentResume 重建 replacement")
    void resume_rebuildsReplacementFromSidechainRecords() {
        // ── 写侧（生产路径）──
        // [R1/S2] AgentTranscript 系列方法收 config-home 项目 slug 目录（sessionDir = SessionStorage
        //   .getProjectDir(workspaceDir)），与 SessionStorage 的 config-home 派生 seam 同根 ——
        //   双根分裂消除（AgentTranscript 双根统一）。sessionDir 不能传裸 workspaceDir（否则
        //   SessionStorage 内部 config-home 派生 → 两写落不同根，读回缺 replacement）。
        Path sidechainDir = SessionStorage.getProjectDir(workspaceDir);
        // 1a. sidechain transcript 消息（带 tool_use 块；AgentTranscript.recordSidechainTranscript 补 agentId/isSidechain/uuid）
        Map<String, Object> asstMsg = new HashMap<>();
        asstMsg.put("role", "assistant");
        asstMsg.put("content", "assistant text");
        asstMsg.put("toolCalls", List.of(Map.of("id", "call_t1", "name", "Bash")));
        AgentTranscript.recordSidechainTranscript(sidechainDir, SESSION_STR, SUBAGENT_ID.toString(),
            List.of(asstMsg));
        // 1b. content replacement 记录（生产写入方 SessionStorage.writeContentReplacement）
        SessionStorage.writeContentReplacement(workspaceDir, SESSION_STR, SUBAGENT_ID.toString(),
            "call_t1", "preview-文本");

        // ── 读侧（resume 路径，CC resumeAgent.ts:63-66 getAgentTranscript）──
        Optional<AgentTranscript.AgentTranscriptResult> transcriptOpt =
            AgentTranscript.getAgentTranscript(sidechainDir, SESSION_STR, SUBAGENT_ID.toString());
        assertThat(transcriptOpt).isPresent();
        AgentTranscript.AgentTranscriptResult transcript = transcriptOpt.get();
        assertThat(transcript.messages()).hasSize(1);
        assertThat(transcript.messages().get(0).toolCalls()).hasSize(1);
        assertThat(transcript.messages().get(0).toolCalls().get(0).id()).isEqualTo("call_t1");
        assertThat(transcript.contentReplacements())
            .as("sidechain 记录必须读回（DRIFT-2 记录源闭环）")
            .hasSize(1);
        assertThat(transcript.contentReplacements().get(0).toolUseId()).isEqualTo("call_t1");
        assertThat(transcript.contentReplacements().get(0).replacement()).isEqualTo("preview-文本");

        // ── 重建（CC resumeAgent.ts:75-79 reconstructForSubagentResume）──
        ContentReplacementState parent = ContentReplacementState.create();
        parent.markSeen("other-id");
        ContentReplacementState resumed = ContentReplacementState.reconstructForSubagentResume(
            parent, transcript.messages(), transcript.contentReplacements());
        assertThat(resumed).isNotNull();
        assertThat(resumed.isSeen("call_t1"))
            .as("resume 重建后候选 tool_use id 必须 markSeen（fate 冻结，CC toolResultStorage.ts:1001-1012）")
            .isTrue();
        assertThat(resumed.getReplacement("call_t1"))
            .as("resume 重建后 replacement 必须从 sidechain 记录恢复（prompt cache 前缀稳定）")
            .isEqualTo("preview-文本");

        // parentState=null → 返 null（CC :1006 reconstructForSubagentResume feature off）
        assertThat(ContentReplacementState.reconstructForSubagentResume(
                null, transcript.messages(), transcript.contentReplacements()))
            .as("parentState null → 重建返 null（CC toolResultStorage.ts:1006）")
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5) 门控 interplay：budgetAggregateGate 关 → 无副作用（CC query.ts:369-372 no-op）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("③ gate: budgetAggregateGate=false → SUBAGENT 场景零副作用（不落 sidechain，CC feature off no-op）")
    void gateOff_noPersistRegardlessOfAgentSource() {
        AgentState state = overBudgetState(SUBAGENT_ID);
        AgentLoopContext.LoopSessionState session = sessionWith();
        AgentLoopContext ctx = buildCtx(session, FeatureFlags.ALL_DISABLED);
        ContentReplacementState crs = session.contentReplacementState();

        AgentLoopContext.applyPerMessageBudget(ctx, state, QuerySource.SUBAGENT, Set.of());

        assertThat(crs.seenIds()).as("gate 关 → contentReplacementState 无新增 seen").isEmpty();
        assertThat(crs.replacements()).as("gate 关 → contentReplacementState 无新增 replacement").isEmpty();
        assertThat(contentOf(state, "call_t1")).as("gate 关 → 内容保持完整").hasSize(150_000);
        assertThat(SessionStorage.getAgentTranscriptPath(workspaceDir, SESSION_STR, SUBAGENT_ID.toString()))
            .as("gate 关 → 不写 sidechain")
            .doesNotExist();
    }

    private static String readAll(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("读取失败: " + p, e);
        }
    }
}

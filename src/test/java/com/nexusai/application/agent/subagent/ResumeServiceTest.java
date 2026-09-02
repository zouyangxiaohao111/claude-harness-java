package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RES-④ /resume 后端核心编排测试（ResumeService，plain JUnit，mock SubagentTool）。
 *
 * <p>规则九（测试验证意图）：resumeAgentBackground 是 CC resumeAgent.ts:42-265 的 Java 等价 —
 * 若 transcript 读取/三层过滤/fork 父提示继承/worktree 回退任一断裂，resume 无法把被 kill 的
 * 异步 agent 恢复到真实上下文，前端 POST /builtins/resume/execute 得到的是空壳。
 *
 * <ul>
 *   <li>无 transcript → 抛错（CC :67-69；REST 语义 NotFound → 404）</li>
 *   <li>三层过滤（whitespace-only / orphaned-thinking-only / unresolved tool uses，CC :70-74）
 *       必须在透传给 SubagentExecutor 前完成</li>
 *   <li>fork resume 继承父 system prompt（CC :116-148），非 fork resume 不传 forkParentSystemPrompt</li>
 *   <li>worktree stat 校验：目录缺失 → null 回退父 cwd（CC :82-92）；存在 → 复用原 worktree</li>
 * </ul>
 */
@DisplayName("[RES-④] ResumeService resumeAgentBackground")
class ResumeServiceTest {

    @TempDir
    Path tmpDir;

    private ResumeService service;
    private SubagentTool subagentTool;
    private SessionAgentStateRegistry registry;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 固定 agent UUID（transcript 文件名 agent-{uuid}.jsonl 与 resumeAgentBackground 入参须一致）。 */
    private static final UUID AGENT_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String AGENT = AGENT_UUID.toString();
    /** agentId a+16hex（生产返回形制：AgentContext.unpackAgentId(AGENT_UUID) 还原 —— 对齐 CC
     *  uuid.ts:24-27 createAgentId 'a'+16hex · D18/B2 拍板；mostSigBits=0 → "a0000000000000000"）。 */
    private static final String AGENT_ID_A16HEX = "a0000000000000000";
    /** 固定 session UUID（transcript 子目录 {sessionId}/subagents 与 resumeAgentBackground 入参须一致）。 */
    private static final String SESSION_UUID = "00000000-0000-0000-0000-00000000000b";

    @BeforeEach
    void setUp() {
        service = new ResumeService();
        subagentTool = mock(SubagentTool.class);
        registry = new SessionAgentStateRegistry();
        service.setSubagentTool(subagentTool);
        service.setSessionAgentStateRegistry(registry);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 夹具: transcript JSONL + metadata（对齐 AgentTranscriptReadTest 格式）
    // ────────────────────────────────────────────────────────────────────────

    private static ObjectNode entry(String role, String content, String agentId, String uuid, String parentUuid) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        n.put("agentId", agentId);
        n.put("isSidechain", true);
        n.put("uuid", uuid);
        if (parentUuid != null) n.put("parentUuid", parentUuid);
        return n;
    }

    private static ObjectNode entryWithToolCall(String role, String content, String agentId,
                                                String uuid, String parentUuid, String toolUseId) {
        ObjectNode n = entry(role, content, agentId, uuid, parentUuid);
        ArrayNode tcs = n.putArray("toolCalls");
        ObjectNode tc = tcs.addObject();
        tc.put("id", toolUseId);
        tc.put("name", "Bash");
        tc.put("arguments", "{}");
        return n;
    }

    /** role='tool' 消息（tool_result 块）· 使前序 tool_use 从 filterUnresolvedToolUses 存活（CC messages.ts:2795）。 */
    private static ObjectNode entryWithToolResult(String content, String agentId,
                                                  String uuid, String parentUuid, String toolCallId) {
        ObjectNode n = entry("tool", content, agentId, uuid, parentUuid);
        n.put("toolCallId", toolCallId);
        return n;
    }

    private void writeTranscript(String agentId, List<ObjectNode> entries) throws Exception {
        Path path = tmpDir.resolve(SESSION_UUID.toString()).resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        for (ObjectNode e : entries) {
            sb.append(MAPPER.writeValueAsString(e)).append("\n");
        }
        Files.writeString(path, sb.toString());
    }

    private void writeMetadata(String agentId, String agentType, String worktreePath, String description) throws Exception {
        writeMetadataWithModel(agentId, agentType, worktreePath, description, null);
    }

    /** [#25] 写 meta 可含 model 字段（旧扩展；readMetadata 已不读 model —— CC AgentMetadata 无 model）。 */
    private void writeMetadataWithModel(String agentId, String agentType, String worktreePath,
                                        String description, String model) throws Exception {
        Path path = tmpDir.resolve(SESSION_UUID.toString()).resolve("subagents")
            .resolve("agent-" + agentId + ".meta.json");
        Files.createDirectories(path.getParent());
        ObjectNode n = MAPPER.createObjectNode();
        n.put("agentType", agentType);
        if (worktreePath != null) n.put("worktreePath", worktreePath);
        if (description != null) n.put("description", description);
        if (model != null) n.put("model", model);
        Files.writeString(path, MAPPER.writeValueAsString(n));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. 无 transcript 抛错（CC :67-69）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无 transcript → 抛 NotFoundException（CC :67-69 throw new Error; REST 语义 404）")
    void resume_noTranscript_throwsNotFound() {
        assertThatThrownBy(() -> service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("No transcript found for agent ID");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. 三层过滤（CC :70-74）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("三层过滤：whitespace-only / orphaned-thinking-only / unresolved tool_use 在透传前剔除")
    void resume_appliesThreeLayerFilter() throws Exception {
        // 链: u1(user) → u2(assistant 空白) → u3(assistant 空内容 + 未配对 tool_use)
        //     → u4(user) → u5(assistant "final")
        writeTranscript(AGENT, List.of(
            entry("user", "first", AGENT, "u1", null),
            entry("assistant", "  ", AGENT, "u2", "u1"),
            entryWithToolCall("assistant", "", AGENT, "u3", "u2", "tc1"),
            entry("user", "second", AGENT, "u4", "u3"),
            entry("assistant", "final", AGENT, "u5", "u4")));

        ResumeAgentResult result = service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        // [REWORK-5 R-1] 生产返回 a+16hex（unpackAgentId 还原，CC resumeAgent.ts:261 agentId 即原 string）——
        //   锁定 a+16hex 形制而非旧 UUID（前端入参同形制，二次 resume 回环成立，B-4）
        assertThat(result.agentId()).isEqualTo(AGENT_ID_A16HEX);
        ArgumentCaptor<List<AgentMessage>> resumedCaptor = ArgumentCaptor.forClass(List.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            resumedCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
        // u2(空白 assistant) / u3(未配对 tool_use) 剔除 → [user, user, assistant]
        List<AgentMessage> resumed = resumedCaptor.getValue();
        assertThat(resumed).extracting(AgentMessage::role).containsExactly("user", "user", "assistant");
        assertThat(resumed.get(0).content()).isEqualTo("first");
        assertThat(resumed.get(1).content()).isEqualTo("second");
        assertThat(resumed.get(2).content()).isEqualTo("final");
    }

    @Test
    @DisplayName("[RES-R2] executeResumeAsync 首参透传原 agentId（二次续跑续写原键前置）")
    void resume_passesOriginalAgentIdToExecuteResumeAsync() throws Exception {
        // WHY (REQ-R2-1): CC resumeAgent.ts:198-205 registerAsyncAgent 复用原 agentId + :240
        //   override.agentId —— 若 ResumeService 不把原键透传给 SubagentTool, 二次 resume 会写新键,
        //   读到 pre-resume transcript (新键空 transcript), 被 kill 的异步 agent 上下文丢失。
        // RED 依据: 本断言锁定 executeResumeAsync 首参 == 原 AGENT_UUID, 而非 any(UUID)。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "general-purpose", null, "gp");

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.eq(AGENT_UUID),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. fork 父提示继承（CC :116-148）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fork resume → 继承主会话 AgentState.systemPrompt 作为 forkParentSystemPrompt")
    void resume_fork_inheritsParentSystemPrompt() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        // worktree 指向不存在目录 → 回退 null（同时覆盖 worktree 回退路径）
        writeMetadata(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE,
            tmpDir.resolve("missing-worktree").toString(), "fork agent");

        registry.register(SESSION_UUID, new AgentState("PARENT_RENDERED_PROMPT", SESSION_UUID, null));

        ResumeAgentResult result = service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        assertThat(result.description()).isEqualTo("fork agent");
        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            agentCaptor.capture(),
            org.mockito.ArgumentMatchers.eq("fork agent"),
            promptCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
        // fork → selectedAgent 是 fork 类型 + 父提示继承（CC :102-104 / :118-119）
        assertThat(agentCaptor.getValue().agentType()).isEqualTo(ForkSubagent.FORK_SUBAGENT_TYPE);
        assertThat(promptCaptor.getValue()).isEqualTo("PARENT_RENDERED_PROMPT");
    }

    @Test
    @DisplayName("[RES-R6-1/#25] fork resume 无 rendered + 注入 ToolRegistry + currentModel → 重建 fork 父提示（CC resumeAgent.ts:120-141）")
    void resume_fork_rebuildsParentPrompt_whenRenderedUnavailable() throws Exception {
        // WHY (REQ-R6-1): CC resumeAgent.ts:118-119 renderedSystemPrompt 优先；rendered 不可得时
        //   :129-141 getSystemPrompt(tools, mainLoopModel, additionalWorkingDirectories, mcpClients)
        //   + buildEffectiveSystemPrompt 重建 fork 父提示。Java web 以 AgentState.systemPrompt() 为
        //   rendered 等价（RES-R6 决策）；[#25 删字段改现算] 模型只从 AgentState.currentModel() 读
        //   （CC resumeAgent.ts:131 options.mainLoopModel），meta model 字段已删 —— 为 null 且注入
        //   ToolRegistry + currentModel 时必须重建非空提示 —— 含 model 描述证明 currentModel 通道落位
        //   （env_info_simple）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE, null, "fork agent");
        // GIVEN: 主会话 AgentState.systemPrompt()==null（rendered 不可得 → 走重建路径）
        //   + currentModel="test-model"（[#25] 模型现算源，CC resumeAgent.ts:131）
        AgentState state = new AgentState(null, SESSION_UUID, null);
        state.setCurrentModel("test-model");
        registry.register(SESSION_UUID, state);
        // GIVEN: ToolRegistry 注入（enabledTools 源，CC resumeAgent.ts:130 options.tools）
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubTool("Echo", "echo test tool"));
        service.setToolRegistry(toolRegistry);

        ResumeAgentResult result = service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.eq("fork agent"),
            promptCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
        // THEN: 重建出非空 fork 父提示（getSystemPrompt 等价组装 + buildEffectiveSystemPrompt）
        String rebuilt = promptCaptor.getValue();
        assertThat(rebuilt)
            .as("rendered 不可得 + 注入 ToolRegistry + currentModel 时必须重建 fork 父提示（CC resumeAgent.ts:120-141），而非返 null 抛错")
            .isNotBlank()
            .contains("You are an interactive agent")                    // default 组装（SystemPromptAssembler intro）
            .contains("You are powered by the model test-model");        // currentModel 通道落位（env_info_simple）
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3.5 [RES-R6-1 AC5] 重建不可得 → fail loud 抛 "Cannot resume fork agent"（CC :143-147）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RES-R6-1 AC5] fork resume + rendered 不可得 + meta.model 缺失 → 抛 Cannot resume fork agent（不伪造字节）")
    void resume_fork_renderedUnavailableAndModelMissing_throwsCannotResume() throws Exception {
        // WHY (REQ-R6-1 AC5): CC resumeAgent.ts:143-147 两者皆空 → throw 'Cannot resume fork agent:
        //   unable to reconstruct parent system prompt' —— 重建缺 mainLoopModel（meta.model）这一关键原料
        //   时，Java 不得静默降级/伪造字节（否则 resumed fork 上下文不完整，异步 agent 恢复失真）。
        // RED 依据: 全测试套件此前无任何 "Cannot resume fork" 断言（grep src/test → 0），
        //   本测试直接锁定该抛错路径。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        // 旧 fork meta 无 model 字段（readMetadata 容错 → null）
        writeMetadata(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE, null, "fork agent");
        // rendered 不可得 + ToolRegistry 已注入（但 model 缺失 → 重建仍 null）
        registry.register(SESSION_UUID, new AgentState(null, SESSION_UUID, null));
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubTool("Echo", "echo test tool"));
        service.setToolRegistry(toolRegistry);

        assertThatThrownBy(() -> service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot resume fork agent: unable to reconstruct parent system prompt");
    }

    @Test
    @DisplayName("[RES-R6-1 AC5] fork resume + rendered 不可得 + ToolRegistry 未注入 → 抛 Cannot resume fork agent")
    void resume_fork_renderedUnavailableAndToolRegistryNotInjected_throwsCannotResume() throws Exception {
        // WHY (REQ-R6-1 AC5): 重建缺 enabledTools 源（CC resumeAgent.ts:130 options.tools；Java =
        //   ToolRegistry.all()）→ 无法按 CC 重建 → null → 抛错。plain JUnit 缺省 null fail loud 语义。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadataWithModel(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE, null, "fork agent", "test-model");
        registry.register(SESSION_UUID, new AgentState(null, SESSION_UUID, null));
        // 不注入 ToolRegistry → rebuildForkParentSystemPrompt 返 null → 调用方抛错

        assertThatThrownBy(() -> service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot resume fork agent: unable to reconstruct parent system prompt");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. worktree 回退（CC :82-97）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("worktree 存在 → 复用原 worktree 路径（不 null）")
    void resume_worktreeExists_reusesPath() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        Path worktreeDir = tmpDir.resolve("real-worktree");
        Files.createDirectories(worktreeDir);
        writeMetadata(AGENT, "general-purpose", worktreeDir.toString(), "gp");

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<String> worktreeCaptor = ArgumentCaptor.forClass(String.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            worktreeCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
        assertThat(worktreeCaptor.getValue()).isEqualTo(worktreeDir.toString());
    }

    @Test
    @DisplayName("worktree 目录已删除 → null 回退父 cwd（CC :86-90 logForDebugging）")
    void resume_worktreeMissing_fallsBackToNull() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "general-purpose", tmpDir.resolve("gone").toString(), "gp");

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
    }

    @Test
    @DisplayName("返回 {agentId, description, outputFile}（CC :260-264）")
    void resume_returnsAgentIdDescriptionOutputFile() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "general-purpose", null, "My Agent");

        ResumeAgentResult result = service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        // [REWORK-5 R-1] 生产返回 a+16hex（unpackAgentId 还原，CC resumeAgent.ts:261 agentId 即原 string）——
        //   锁定 a+16hex 形制而非旧 UUID；outputFile 走唯一根 taskOutputPath（对齐 CC getTaskOutputPath，
        //   旧平铺 /tmp/agent-{id}.out 已删）
        assertThat(result.agentId()).isEqualTo(AGENT_ID_A16HEX);
        assertThat(result.description()).isEqualTo("My Agent");
        assertThat(result.outputFile())
            .isEqualTo(com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(AGENT));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4.25 [OD-2A-4] resume 目录上下文恢复（对齐 CC sessionStorage.ts:2522/4680 projectPath: firstMessage.cwd）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[OD-2A-4] resume 用首条消息 cwd 恢复 SessionCwdHolder（CC sessionStorage.ts:2522 projectPath: firstMessage.cwd）")
    void resume_restoresSessionCwdFromFirstMessageCwd() throws Exception {
        // WHY (规则九 · 意图): CC /resume 以 firstMessage.cwd 作 projectPath 恢复目录上下文
        //   （sessionStorage.ts:2522/4680），目录在会话启动/绑定时定死，resume 后不回落到新启动
        //   目录（boundProject）。Java 端 SessionCwdHolder 是会话级可变 cwd 槽（CwdResolution.getCwd
        //   的 L2 层），resume 后若不恢复会回落 boundProject —— 本测试锁定「首条消息 cwd → 恢复
        //   SessionCwdHolder」链路（V22/G13 已落 messages 表 cwd 列，写侧 MessageService 经
        //   CwdResolution.getCwd(sessionId) 戳入）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "general-purpose", null, "gp");

        // GIVEN: 主会话首条消息（chain[0]）已落 messages 表 cwd 列（V22/G13）
        MessageRecord first = new MessageRecord();
        first.setSessionId(SESSION_UUID.toString());
        first.setCwd("/original/startup/dir");
        MessageMapper mapper = mock(MessageMapper.class);
        when(mapper.selectListByQuery(org.mockito.ArgumentMatchers.any(
            com.mybatisflex.core.query.QueryWrapper.class)))
            .thenReturn(List.of(first));
        service.setMessageMapper(mapper);

        // 先清 SessionCwdHolder 槽（隔离跨测试污染）
        com.nexusai.application.agent.agent.SessionCwdHolder.clear(SESSION_UUID.toString());
        try {
            service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

            // THEN: SessionCwdHolder 会话 cwd 槽被恢复为首条消息 cwd（realpath+NFC 归一化）
            assertThat(com.nexusai.application.agent.agent.SessionCwdHolder.get(SESSION_UUID.toString()))
                .as("resume 必须用首条消息 cwd 恢复会话 cwd 槽（CC projectPath: firstMessage.cwd）")
                .isEqualTo(com.nexusai.application.agent.agent.CwdResolution.normalizeCwd("/original/startup/dir"));
        } finally {
            com.nexusai.application.agent.agent.SessionCwdHolder.clear(SESSION_UUID.toString());
        }
    }

    @Test
    @DisplayName("[OD-2A-4] 首条消息无 cwd（旧消息 V22 列 NULL）→ resume 目录恢复软跳过（CC 旧 jsonl 容错）")
    void resume_firstMessageWithoutCwd_softSkipsRestore() throws Exception {
        // WHY: CC 旧 jsonl 无 cwd 字段（sessionStorage.ts:1059 是后续版本才戳）；V22 列可空。
        //   resume 目录恢复必须软降级跳过，不得阻断 resume 主流程（CC 旧 transcript 同容错）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "general-purpose", null, "gp");

        MessageRecord first = new MessageRecord();
        first.setSessionId(SESSION_UUID.toString());
        first.setCwd(null); // 旧消息未戳 cwd（V22 NULL）
        MessageMapper mapper = mock(MessageMapper.class);
        when(mapper.selectListByQuery(org.mockito.ArgumentMatchers.any(
            com.mybatisflex.core.query.QueryWrapper.class)))
            .thenReturn(List.of(first));
        service.setMessageMapper(mapper);

        com.nexusai.application.agent.agent.SessionCwdHolder.clear(SESSION_UUID.toString());
        try {
            ResumeAgentResult result = service.resumeAgentBackground(
                AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

            // THEN: 主流程不受阻（resume 正常返回），SessionCwdHolder 槽未被污染（保持 null）
            assertThat(result.agentId()).isEqualTo(AGENT_ID_A16HEX);
            assertThat(com.nexusai.application.agent.agent.SessionCwdHolder.get(SESSION_UUID.toString()))
                .as("首条消息无 cwd → 目录恢复软跳过，SessionCwdHolder 保持未设置")
                .isNull();
        } finally {
            com.nexusai.application.agent.agent.SessionCwdHolder.clear(SESSION_UUID.toString());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4.5 [RES-SP31-1 返工] fork resume rendered 路径: 父提示恒末尾补 append
    //     （CC renderedSystemPrompt 含 append；fork-only，非 fork resume 不追加 —— 原两个非 fork
    //      append 透传测试已删，理由见 SubagentExecutorForkPathTest 返工段）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RES-SP31-1 返工] fork resume rendered 路径: 父提示恒末尾补 append（CC renderedSystemPrompt 含 append）")
    void resume_fork_renderedPrompt_includesAppend() throws Exception {
        // WHY (REQ-SP31-1): CC 的 toolUseContext.renderedSystemPrompt 是完整渲染提示（含 append 恒末尾）；
        //   Java 以 AgentState.systemPrompt()（custom prompt 不含 append）为 rendered 等价 —— 若 fork resume
        //   rendered 路径不补 append，续跑 fork 子代理丢失主会话追加指令（与重建路径 :395 行为不一致）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE,
            tmpDir.resolve("missing-worktree").toString(), "fork agent");
        registry.register(SESSION_UUID, new AgentState("PARENT_RENDERED_PROMPT", SESSION_UUID, null,
            "APPEND_USER_INSTRUCTIONS"));

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.eq("fork agent"),
            promptCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
        assertThat(promptCaptor.getValue())
            .as("fork resume rendered 父提示必须恒末尾含 append（CC renderedSystemPrompt 含 append，systemPrompt.ts:121）")
            .isEqualTo("PARENT_RENDERED_PROMPT\n\nAPPEND_USER_INSTRUCTIONS");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. [RES-R6] ContentReplacementState 注入（CC :194 runAgentParams.contentReplacementState）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RES-R6] 父 live ContentReplacementState 可取得 → 重建结果透传 executeResumeAsync（注入 query loop）")
    void resume_parentStateAvailable_reconstructedCrsFlowsToExecuteResumeAsync() throws Exception {
        // WHY (REQ-R6-1): CC resumeAgent.ts:75-79 reconstructForSubagentResume + :194
        //   runAgentParams.contentReplacementState —— resume 重建的 ContentReplacementState 必须传入
        //   resumed 子 agent query loop，否则 resumed 会话 budget 决策从头开始、prompt cache 前缀破坏。
        //   web 端点无父 ToolUseContext → 显式传 null = CC toolResultStorage.ts:1006 feature off；本用例第 5 参直传非 null 模拟父 live state
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entryWithToolCall("assistant", "work", AGENT, "u2", "u1", "tc1"),
            entryWithToolResult("RESULT", AGENT, "u3", "u2", "tc1")));
        writeMetadata(AGENT, "general-purpose", null, "gp");

        // GIVEN: 父 live ContentReplacementState 可取得（第 5 参直传非 null → 重建非 null，
        //   对齐 CC resumeAgent.ts:76 toolUseContext.contentReplacementState 参数直传）
        ContentReplacementState parentState = ContentReplacementState.create();
        parentState.recordReplacement("tc1", "[parent-preview]");

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, parentState);

        // THEN: executeResumeAsync 第 8 参收到重建后的 CRS（含 candidate tc1 seen + 替换）
        ArgumentCaptor<ContentReplacementState> crsCaptor =
            ArgumentCaptor.forClass(ContentReplacementState.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            crsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyString());;
        ContentReplacementState reconstructed = crsCaptor.getValue();
        assertThat(reconstructed)
            .as("父 live state 可取得时必须重建出非 null CRS 注入 query loop (CC :194)")
            .isNotNull();
        assertThat(reconstructed.isSeen("tc1")).isTrue();
        assertThat(reconstructed.getReplacement("tc1")).isEqualTo("[parent-preview]");
    }

    @Test
    @DisplayName("[RES-R6] 无父 live state（web 端点）→ reconstruct 返 null → executeResumeAsync 收 null（CC :1006）")
    void resume_noParentState_reconstructedNull() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entryWithToolCall("assistant", "work", AGENT, "u2", "u1", "tc1")));
        writeMetadata(AGENT, "general-purpose", null, "gp");
        // 显式传第 5 参 null（web 端点无父 ToolUseContext）→ reconstructForSubagentResume 返 null（CC toolResultStorage.ts:1006 if (!parentState) return undefined）

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<ContentReplacementState> crsCaptor =
            ArgumentCaptor.forClass(ContentReplacementState.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            crsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyString());;
        assertThat(crsCaptor.getValue())
            .as("无父 live state 时重建返 null（CC :1006 if (!parentState) return undefined），loop 保持默认 create")
            .isNull();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. [RES-R6] agent 解析扩展到自定义 agent（CC :106-109 activeAgents.find）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("[RES-R6] 自定义 agentType（非 BuiltIn 内置）resume 命中 activeAgents 定义")
    void resume_customAgent_resolvesFromRegistry() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "my-custom-reviewer", null, "reviewer");
        // GIVEN: SubagentTool 暴露 activeAgents 注册中心（内置 + 自定义合并，CC loadAgentsDir.ts:216）
        AgentDefinition customDef = AgentDefinition.BuiltInAgentDefinition.create(
            "my-custom-reviewer", "Review the code and return findings",
            null, (ctx, dirs) -> "custom reviewer system prompt");
        when(subagentTool.agentRegistry()).thenReturn(new AgentDefinitionRegistry(
            java.util.Map.of(), List.of(customDef)));

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            agentCaptor.capture(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
        assertThat(agentCaptor.getValue().agentType())
            .as("自定义 agent resume 必须命中 activeAgents 定义 (CC resumeAgent.ts:106-109 activeAgents.find)")
            .isEqualTo("my-custom-reviewer");
    }

    @Test
    @DisplayName("[RES-R6] 未知 agentType → GENERAL_PURPOSE_AGENT（CC :109 found ?? GENERAL_PURPOSE_AGENT）")
    void resume_unknownAgentType_fallsBackToGeneralPurpose() throws Exception {
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        writeMetadata(AGENT, "ghost-type", null, "ghost");
        when(subagentTool.agentRegistry()).thenReturn(new AgentDefinitionRegistry(
            java.util.Map.of(), List.of()));

        service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            agentCaptor.capture(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());;
        assertThat(agentCaptor.getValue().agentType())
            .as("未知 agentType 必须回退 GENERAL_PURPOSE_AGENT (CC resumeAgent.ts:109)")
            .isEqualTo(BuiltInAgents.GENERAL_PURPOSE);
    }

    @Test
    @DisplayName("[RES-C7] fork resume: 当前会话模型为模型唯一源（CC resumeAgent.ts:131 options.mainLoopModel）")
    void resume_fork_currentSessionModel_overridesMetaModel() throws Exception {
        // WHY (REQ-C7-1): CC resumeAgent.ts:131 取 options.mainLoopModel（resume 时当前主循环模型）；
        //   [#25 删字段改现算] meta.model 字段已删（CC AgentMetadata 无 model，sessionStorage.ts:264-272）——
        //   当前会话模型是模型唯一源。本测试断言：state.currentModel="current-model" 时，
        //   重建的父提示含 "current-model"；旧 meta 残留 "spawn-model" 键被忽略（不出现）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        // 旧 meta 残留 model 键（readMetadata 已不读 model，应被忽略）
        writeMetadataWithModel(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE, null, "fork agent", "spawn-model");
        // GIVEN: 主会话 AgentState.systemPrompt()==null（rendered 不可得 → 走重建路径）
        //   + currentModel="current-model"（CC resumeAgent.ts:131 当前会话模型）
        AgentState state = new AgentState(null, SESSION_UUID, null);
        state.setCurrentModel("current-model");
        registry.register(SESSION_UUID, state);
        // GIVEN: ToolRegistry 注入
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubTool("Echo", "echo test tool"));
        service.setToolRegistry(toolRegistry);

        ResumeAgentResult result = service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(subagentTool).executeResumeAsync(
            org.mockito.ArgumentMatchers.any(UUID.class),
            org.mockito.ArgumentMatchers.eq("continue"),
            org.mockito.ArgumentMatchers.any(AgentDefinition.class),
            org.mockito.ArgumentMatchers.eq("fork agent"),
            promptCaptor.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
        // THEN: 重建的父提示含 current-model（当前会话模型唯一源）
        String rebuilt = promptCaptor.getValue();
        assertThat(rebuilt)
            .as("[RES-C7] 当前会话模型必须是模型唯一源 (CC resumeAgent.ts:131)")
            .isNotBlank()
            .contains("current-model")
            .doesNotContain("spawn-model");
    }

    @Test
    @DisplayName("[RES-C7/#25] fork resume: 当前会话模型不可得（meta model 字段已删）→ 抛 Cannot resume fork agent")
    void resume_fork_noCurrentModel_throwsCannotResume() throws Exception {
        // WHY (#25 删字段改现算): CC resumeAgent.ts:131 options.mainLoopModel = resume 时当前主循环模型；
        //   AgentMetadata.model 字段已删（open-decisions F2 #25，CC sessionStorage.ts:264-272 无 model），
        //   不再有 spawn 持久化兜底。当前会话模型不可得 → 无法按 CC 重建 fork 父提示 → null →
        //   抛 "Cannot resume fork agent"（fail loud，不伪造字节）。
        writeTranscript(AGENT, List.of(
            entry("user", "go", AGENT, "u1", null),
            entry("assistant", "work", AGENT, "u2", "u1")));
        // 旧 meta 即使残留 model 字段也忽略（readMetadata 已不读 model）
        writeMetadataWithModel(AGENT, ForkSubagent.FORK_SUBAGENT_TYPE, null, "fork agent", "spawn-model");
        // GIVEN: AgentState.currentModel() == null（web resume 场景 AgentState 未注入模型）
        registry.register(SESSION_UUID, new AgentState(null, SESSION_UUID, null));
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubTool("Echo", "echo test tool"));
        service.setToolRegistry(toolRegistry);

        assertThatThrownBy(() -> service.resumeAgentBackground(AGENT_UUID, "continue", tmpDir, SESSION_UUID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot resume fork agent: unable to reconstruct parent system prompt");
    }

    /** [RES-R6-1] 最小 Tool 桩（ToolRegistry.all() → enabledTools 源）。 */
    private static final class StubTool implements com.nexusai.application.agent.tool.Tool {
        private final String name;
        private final String desc;

        StubTool(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override public String name() { return name; }
        @Override public String description() { return desc; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "stub:" + call.name());
        }
    }
}

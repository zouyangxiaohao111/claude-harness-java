package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM 工具诊断 · DB 复现] 用真实 DB 会话驱动 SessionMemoryService 生产自动提取路径，捕获
 * RunForkedAgent 边界（RecordingQuery），断言发给 fork 的输入：
 * <ol>
 *   <li>systemPrompt <b>完整透传</b>（不是空 / 没给完整）</li>
 *   <li>toolUseContext.availableTools <b>含 Edit</b>（生产 supplier 携带真实工具集）</li>
 *   <li>messages 前缀 = DB 完整会话（对话真在上下文）</li>
 *   <li>canUseTool 已注入（SM 受限门禁）</li>
 * </ol>
 *
 * <p>门控：仅当系统属性 {@code -DsmDbRepro=true} 才运行（读开发机 ~/.nexusai/nexusai.db
 * 的 sess-fcdcdc68 完整会话），常规 mvn test 不跑。不发网络：fork seam 用 RecordingQuery 捕获即停。
 */
@EnabledIfSystemProperty(named = "smDbRepro", matches = "true")
class SessionMemoryDbReproTest {

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.setLastSummarizedMessageId(null, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    @Test
    @DisplayName("真实 DB 会话 → SM fork 收到 完整 systemPrompt + Edit 工具 + 全会话消息")
    void realDbSession_forksWithCompleteSystemPromptEditAndConversation() throws Exception {
        Path db = Paths.get(System.getProperty("user.home"), ".nexusai", "nexusai.db");
        String sessionId = "sess-fcdcdc68";

        // ── 读真实 DB 完整会话（仅读，不改库）──
        List<ChatMessageDto> dbMessages = loadDbMessages(db, sessionId);
        assertThat(dbMessages).as("DB 会话 %s 必须有真实消息（否则不是复现）", sessionId).isNotEmpty();

        // 保证 shouldExtract 阈值 + 最后 turn 无工具 → 提取走到 fork（内容前缀仍是 DB 真实会话）
        List<ChatMessageDto> ctxMessages = new ArrayList<>(dbMessages);
        ctxMessages.add(asst("db-tail", 12000, List.of()));   // 末条 assistant 无工具，触发提取

        // ── 捕获 fork 输入 ──
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(new com.nexusai.application.agent.tool.impl.ReadFileTool(
            PathGuard.of(baseDir.toString())));

        // 生产 supplier：systemPrompt 空占位 + toolUseContext 携带真实工具集（含 Edit）
        Tool editTool = fakeTool("Edit");
        Tool readTool = fakeTool("Read");
        ToolUseContext supplierTuc = new ToolUseContext(
            UUID.randomUUID(), "sess-supplier", PermissionMode.DEFAULT,
            Map.of(), List.of(editTool, readTool), "", AbortController.NOOP, List.of());
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierTuc, List.of()));

        // ── psContext：messages = DB 会话；systemPrompt = 完整主提示（多段）──
        List<String> fullSystemPrompt = List.of(
            "You are Claude Code, a tool-using AI agent. You may use the Edit tool to modify files.",
            "Today's date is 2026-09-09. Work only in the bound project directory.");
        ToolUseContext parentTuc = baseContext();
        PostSamplingContext ps = new PostSamplingContext(
            ctxMessages, fullSystemPrompt, Map.of(), Map.of(), parentTuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ps);

        // ── 断言 ──
        assertThat(query.captured).as("fork 必须发起（提取走到 fork 边界）").isNotNull();

        // 1. systemPrompt 完整透传（一段不丢）
        assertThat(query.captured.systemPrompt())
            .as("SM fork systemPrompt = psContext 完整主提示（非空、全段保留）")
            .isEqualTo(fullSystemPrompt);

        // 2. 工具集含 Edit（生产 supplier 真实工具集经隔离 clone 保留）
        List<String> toolNames = query.captured.toolUseContext().availableTools() == null ? List.of()
            : query.captured.toolUseContext().availableTools().stream().map(Tool::name).toList();
        assertThat(toolNames)
            .as("SM fork 隔离上下文 availableTools 必须含 Edit（模型才有得调）")
            .contains("Edit");

        // 3. messages 前缀 = DB 完整会话（对话真在上下文里）
        List<ChatMessageDto> forkMessages = query.captured.messages();
        assertThat(forkMessages.size()).as("fork messages = DB 会话 + 尾部触发消息 + 改文件指令")
            .isEqualTo(dbMessages.size() + 2);
        assertThat(forkMessages.get(0).content())
            .as("fork 首条应为 DB 会话第 1 条真实消息")
            .isEqualTo(dbMessages.get(0).content());

        // 4. canUseTool 已注入（SM 受限门禁 · createMemoryFileCanUseTool）
        assertThat(query.captured.canUseTool()).as("SM fork 必须带受限 canUseTool（仅 Edit 精确路径）").isNotNull();

        // 打印关键载荷供人工核对
        System.out.println("[SM-DbRepro] fork dbMessages=" + dbMessages.size()
            + " systemPromptSegments=" + query.captured.systemPrompt().size()
            + " availableTools=" + toolNames
            + " canUseTool=" + (query.captured.canUseTool() != null));
    }

    // ── helpers ──

    /** 只读真实 DB，取某会话全部非 meta 消息（按 created_at 序）→ 最小 ChatMessageDto。 */
    private static List<ChatMessageDto> loadDbMessages(Path db, String sessionId) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:file:" + db.toAbsolutePath().toString().replace('\\', '/')
            + "?mode=ro&immutable=0";
        List<ChatMessageDto> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id, role, COALESCE(content, '') AS content FROM messages "
                     + "WHERE session_id = '" + sessionId.replace("'", "''") + "' "
                     + "AND (is_meta IS NULL OR is_meta = 0) ORDER BY created_at")) {
            while (rs.next()) {
                out.add(minimalMessage(rs.getString("id"), rs.getString("role"), rs.getString("content")));
            }
        }
        return out;
    }

    private static ChatMessageDto minimalMessage(String id, String role, String content) {
        Role r = "user".equals(role) ? Role.user
            : "assistant".equals(role) ? Role.assistant : Role.system;
        return new ChatMessageDto(id, null, r, role, content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto asst(String id, int tokens, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, tokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    private static Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "fake " + name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
        };
    }

    /** 捕获 fork 参数的 RecordingQuery（对齐 SessionMemoryExtractionPipelineTest 模式）。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        RunForkedAgent.ForkQueryParams captured;

        @Override
        public com.nexusai.application.agent.compact.fork.ForkedAgentResult run(
                RunForkedAgent.ForkQueryParams params) {
            this.captured = params;
            return new com.nexusai.application.agent.compact.fork.ForkedAgentResult(
                List.of(), com.nexusai.application.agent.compact.fork.ForkedAgentResult.ForkUsage.empty());
        }
    }
}

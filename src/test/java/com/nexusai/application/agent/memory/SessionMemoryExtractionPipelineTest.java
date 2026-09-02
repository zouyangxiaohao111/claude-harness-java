package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.CompactSettingsResolver;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-M-P1-3 · SM 提取管线测试（token 阈值状态机 / repl_main_thread 门控 / fork 参数 /
 * lastSummarized 安全更新）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 提取管线是 session-memory 的核心链路
 * （sessionMemory.ts:134-181 shouldExtractMemory + :272-350 extractSessionMemory + :488-495
 * updateLastSummarizedMessageIdIfSafe），旧版缺失。本测试锁定：
 * <ol>
 *   <li>init 阈值（10000）与更新阈值（5000 增长）状态机（REQ-M-08）</li>
 *   <li>repl_main_thread 门控（INV-9）：子代理/非主线程跳过提取</li>
 *   <li>fork 参数：querySource=session_memory + 受限 canUseTool + 提示词含 notesPath</li>
 *   <li>lastSummarizedMessageId 安全更新：「最后 turn 无工具」才 set（避免孤儿 tool_result）</li>
 * </ol>
 */
@DisplayName("[IMP-M-P1-3] SM 提取管线（阈值状态机/门控/fork/安全更新）")
class SessionMemoryExtractionPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 阈值状态机单测固定会话 · [sm-cursor-sessionize] shouldExtractMemory 按 sessionId 键控。 */
    private static final String SESSION = "s1";

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        SessionMemoryService.setLastSummarizedMessageId(null, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
    }

    /** [G-41] 读取通道注入（ReadFileTool 权限层读取；guard 与 baseDir 同源保证 key 派生一致）。 */
    private ReadFileTool readFileTool() {
        return new ReadFileTool(PathGuard.of(baseDir.toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · shouldExtractMemory token 阈值状态机（sessionMemory.ts:134-181）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("init 阈值未达（<10000）→ false，且不标记已初始化")
    void shouldExtractMemory_belowInitThreshold_false() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);

        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a1", 5000, List.of())))).isFalse();
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION)).isFalse();
    }

    @Test
    @DisplayName("init 阈值达到（≥10000）+ 最后 turn 无工具 → true（首提取，标记已初始化）")
    void shouldExtractMemory_initMetLastTurnNoTools_true() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);

        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a1", 12000, List.of())))).isTrue();
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION)).isTrue();
    }

    @Test
    @DisplayName("更新阈值未达（增长 <5000）→ false（token 阈值 ALWAYS required，CC :164-167）")
    void shouldExtractMemory_updateThresholdNotMet_false() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // 首次提取初始化 + 记录 12000（模拟抽取时 token）
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a1", 12000, List.of())))).isTrue();
        SessionMemoryUtils.recordExtractionTokenCount(SESSION, 12000);

        // 增长仅 1000（13000-12000）< 5000 → 即使最后 turn 无工具也不提取
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a2", 13000, List.of())))).isFalse();
    }

    @Test
    @DisplayName("更新阈值达到（增长 ≥5000）→ true（token 增长自上次提取度量）")
    void shouldExtractMemory_updateThresholdMet_true() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a1", 12000, List.of())))).isTrue();
        SessionMemoryUtils.recordExtractionTokenCount(SESSION, 12000);

        // 增长 6000（18000-12000）≥ 5000 → 提取（最后 turn 无工具）
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a2", 18000, List.of())))).isTrue();
    }

    @Test
    @DisplayName("工具调用阈值（≥3）满足但 token 未达 → false（token 阈值是必要条件）")
    void shouldExtractMemory_toolCallsMetTokenNotMet_false() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // 3 个工具调用但 token 5000 < init 10000 → false
        List<ChatMessageDto> msgs = List.of(
            asst("a0", 3000, List.of(call("t1"))),
            asst("a1", 2000, List.of(call("t2"), call("t3"))));
        assertThat(svc.shouldExtractMemory(SESSION, msgs)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · repl_main_thread 门控（sessionMemory.ts:278，INV-9）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("querySource != repl_main_thread → 不 fork、不更新 lastSummarized（INV-9）")
    void extractSessionMemory_skipsNonMainThread() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.USER);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNull();
        assertThat(SessionMemoryService.getLastSummarizedMessageId(tuc.sessionId())).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · fork 参数（sessionMemory.ts:318-325）+ lastSummarized 安全更新
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("repl_main_thread + 阈值满足 → fork(querySource=session_memory) + 文件建立 + 安全更新")
    void extractSessionMemory_forksWithSessionMemoryParams() throws Exception {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();
        String sessionId = tuc.sessionId().toString();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // fork querySource=session_memory（CC :322）
        assertThat(query.captured.querySource()).isEqualTo(QuerySource.SESSION_MEMORY);
        // prompt 消息含 notesPath（buildSessionMemoryUpdatePrompt 替换 {{notesPath}}）
        Path expected = baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        String prompt = query.captured.messages().get(query.captured.messages().size() - 1).content();
        assertThat(prompt).contains(expected.toString());
        // 文件已建立（setupSessionMemoryFile）
        assertThat(Files.exists(expected)).isTrue();
        // 最后 turn 无工具 → lastSummarizedMessageId 安全更新（CC :488-495）
        assertThat(SessionMemoryService.getLastSummarizedMessageId(sessionId)).isEqualTo("a1");
    }

    @Test
    @DisplayName("OPD-CM3-28/F04: forkedQuery seam 未注入提前 return → extractionStartedAt 不滞留（finally 兜底）")
    void extractSessionMemory_forkedQueryNull_doesNotStickTimestamp() {
        // WHY (规则九): 决策 OPD-CM3-28 —— markExtractionStarted 后用 finally 保证 markCompleted。
        //   生产 seam 未接线（IMP-CM-01 A01 之前）forkedQuery 恒 null → doExtractSessionMemory
        //   在 :605 提前 return，若 finally 不兜底清除 → extractionStartedAt 滞留 →
        //   waitForSessionMemoryExtraction 阻塞满 15s（S1 附带影响）。本测试钉死：seam 未注入
        //   提前 return 路径也必须清空时间戳（finally 兜底），waitForSessionMemoryExtraction 不阻塞。
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // 不注入 forkedQuery（生产 seam 未接线场景）
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        // [sm-cursor-sessionize] extractionStartedAt 已按 sessionId 键控 → 读本会话时间戳
        assertThat(SessionMemoryUtils.getExtractionStartedAt(tuc.sessionId()))
            .as("OPD-CM3-28: forkedQuery null 提前 return 后 extractionStartedAt 不滞留（finally 兜底 markCompleted）")
            .isNull();
    }

    @Test
    @DisplayName("RES-C5 兜底: supplier 未注入时 psContext 会话 systemPrompt → fork CacheSafeParams 非空 + gate 透传")
    void extractSessionMemory_fallbackFillsSystemPromptFromSession() {
        // WHY (规则九): 主会话 cache-safe params 未注入（supplier null）时兜底
        // createMinimalCacheSafeParams 旧实现 systemPrompt 恒空 → fork 缓存 key 与主循环
        // 不一致、boundary 剥离无真实输入（RES-C5）。CC 真源 createCacheSafeParams(context)
        // 从 REPLHookContext 完整构建（forkedAgent.ts:131-141），Java PostSamplingContext
        // 等价 REPLHookContext → 兜底应注入 psContext.systemPrompt/userContext/systemContext + gate。
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        // firstParty gate 注入（GlobalCacheScope 单实现消费方；生产由接线方注入）
        svc.setUseGlobalCacheScopeSupplier(() -> true);
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())),
            List.of("CUSTOM-SYSTEM-PROMPT"),  // 会话 systemPrompt（REPLHookContext.systemPrompt）
            Map.of("uk", "uv"),               // userContext
            Map.of("sk", "sv"),               // systemContext
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // 兜底 CacheSafeParams.systemPrompt 非空（真实会话 systemPrompt，不再恒空）
        assertThat(query.captured.systemPrompt()).containsExactly("CUSTOM-SYSTEM-PROMPT");
        // userContext / systemContext 透传（forkedAgent.ts:61/63 cache key 组成部分）
        assertThat(query.captured.userContext()).containsEntry("uk", "uv");
        assertThat(query.captured.systemContext()).containsEntry("sk", "sv");
        // gate 透传（fork 与主线程同一判定 · betas.ts:227-233）
        assertThat(query.captured.useGlobalCacheScope()).isTrue();
    }

    @Test
    @DisplayName("RES-C5 rework: supplier 存在(生产 buildProductionCacheSafeParams 空 systemPrompt) → supplied 分支合并 psContext 会话原料")
    void extractSessionMemory_suppliedEmptySystemPrompt_mergesSessionMaterial() {
        // WHY (规则九): 生产 ToolRegistrationConfig 三消费方恒接
        // buildProductionCacheSafeParams（systemPrompt=List.of() 空 · ToolRegistrationConfig:1087-1098），
        // 消费方 `supplied != null` 分支恒胜 → RES-C5 兜底（supplier=null）生产不可达，生产 fork
        // systemPrompt 仍空、缓存 key 仍与主循环不一致（REQ-C5-1）。本测试锁定：supplied.systemPrompt()
        // 空时，supplied 分支必须合并 psContext 会话原料（REPLHookContext 等价 · forkedAgent.ts:131）。
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        // 生产 supplier：buildProductionCacheSafeParams 等价（systemPrompt/userContext/systemContext 空
        // 占位，toolUseContext 携带真实工具集，5 参便捷构造 gate=false）
        ToolUseContext supplierCtx = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierCtx, List.of()));
        // firstParty gate 注入（GlobalCacheScope 单实现消费方）
        svc.setUseGlobalCacheScopeSupplier(() -> true);
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())),
            List.of("CUSTOM-SYSTEM-PROMPT"),  // 会话 systemPrompt（REPLHookContext.systemPrompt）
            Map.of("uk", "uv"),               // userContext
            Map.of("sk", "sv"),               // systemContext
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // supplied 分支合并 psContext 会话原料（不再恒空）→ fork cache key 与主循环对齐（REQ-C5-1）
        assertThat(query.captured.systemPrompt()).containsExactly("CUSTOM-SYSTEM-PROMPT");
        assertThat(query.captured.userContext()).containsEntry("uk", "uv");
        assertThat(query.captured.systemContext()).containsEntry("sk", "sv");
        // 生产 supplier 真实工具集经隔离上下文（with() clone）保留 —— 非同一实例但 availableTools
        // 继承自 supplierCtx（buildProductionCacheSafeParams 唯一有效载荷；RunForkedAgent 隔离语义）
        assertThat(query.captured.toolUseContext()).isNotSameAs(supplierCtx);
        assertThat(query.captured.toolUseContext().availableTools())
            .isEqualTo(supplierCtx.availableTools());
        // gate 合并：supplied(false) || 会话级 gate(true) → true（REQ-C5-4 · betas.ts:227-233）
        assertThat(query.captured.useGlobalCacheScope()).isTrue();
    }

    @Test
    @DisplayName("RES-C5 rework: supplier 存在且 systemPrompt 非空 → 保留 supplied 原值（不覆写）")
    void extractSessionMemory_suppliedNonEmptySystemPrompt_keepsSupplied() {
        // WHY: 未来 C2/C10 接线方可能注入完整组装数组到 supplier —— supplied.systemPrompt() 非空时
        // 必须保留原值（mergeSystemPrompt 的「supplied 优先」语义），不得用 psContext 单串覆写。
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        ToolUseContext supplierCtx = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
        // supplier 已携带真实 systemPrompt + systemContext（组装链注入 · 未来状态）；
        // userContext 留空 → 应合并 psContext 会话原料
        svc.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of("REAL-ASSEMBLED-PROMPT"), Map.of(), Map.of("sk", "sv"), supplierCtx, List.of()));

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())),
            List.of("CUSTOM-SYSTEM-PROMPT"),
            Map.of("uk", "uv"), Map.of("sk2", "sv2"),
            baseContext(), QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // supplied.systemPrompt 非空 → 保留组装链数组（不被 psContext 单串覆写）
        assertThat(query.captured.systemPrompt()).containsExactly("REAL-ASSEMBLED-PROMPT");
        // supplied.systemContext 非空 → 保留原值（不被 psContext 覆写）
        assertThat(query.captured.systemContext()).containsEntry("sk", "sv");
        // supplied.userContext 空 → 合并 psContext 会话 userContext
        assertThat(query.captured.userContext()).containsEntry("uk", "uv");
    }

    @Test
    @DisplayName("最后 turn 有工具 → 提取后 lastSummarizedMessageId 不更新（避免孤儿 tool_result）")
    void extractSessionMemory_lastTurnHasTools_noSafeUpdate() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());

        // 3 个工具调用（工具阈值满足）+ 最后 turn 含工具（末条 assistant 高 usage 达 init 阈值）
        // → 提取触发但安全更新跳过（CC :488-495 hasToolCallsInLastAssistantTurn → skip）
        List<ChatMessageDto> msgs = List.of(
            asst("a0", 1000, List.of(call("t1"))),
            asst("a1", 12000, List.of(call("t2"), call("t3"))));
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            msgs, List.of(""), Map.of(), Map.of(), tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // 最后 turn 有工具 → 不 set（CC :488-495 hasToolCallsInLastAssistantTurn → skip）
        assertThat(SessionMemoryService.getLastSummarizedMessageId(tuc.sessionId())).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · 核心写入链（sessionMemory.ts:216-226 播种 + :324 fork 共享 + Edit 门禁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 隔离上下文 readFileState 含 memory 文件 full entry（setup 播种 + override 共享）")
    void forkReadFileStateOverride_containsSeededMemoryEntry() throws Exception {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();
        String sessionId = tuc.sessionId().toString();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        Path memoryPath = baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        // fork 隔离 ctx 的 readFileState == setup 播种缓存（override 共享，RunForkedAgent 透传）
        String key = ToolUseContext.keyForReadFileState(PathGuard.of(baseDir.toString()), memoryPath.toString());
        ToolUseContext.ReadState entry = query.captured.toolUseContext().readFileState().get(key);
        assertThat(entry).isNotNull();
        // full read entry：isPartialView=false + offset=1（ReadFileTool full read 默认，CC FileReadTool.ts:497）
        //   + limit=null + 内容与磁盘一致（CRLF 归一化）
        assertThat(entry.isPartialView()).isFalse();
        assertThat(entry.offset()).isEqualTo(1);
        assertThat(entry.limit()).isNull();
        assertThat(entry.content()).isEqualTo(Files.readString(memoryPath).replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("真实 EditFileTool: seed 后 validateInput 对 memory 文件通过；未 seed → fail(\"6\")")
    void editReadStateSeeded_validateInputPasses_seedAbsentFails6() throws Exception {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();
        String sessionId = tuc.sessionId().toString();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);
        svc.extractSessionMemory(ctx);

        Path memoryPath = baseDir.resolve(sessionId).resolve("session-memory").resolve("summary.md");
        EditFileTool editTool = new EditFileTool(PathGuard.of(baseDir.toString()));
        ObjectNode editInput = JSON.createObjectNode();
        editInput.put("file_path", memoryPath.toString());
        // 探针用模板真实子串（# Worklog 是 DEFAULT_SESSION_MEMORY_TEMPLATE 末节标题，summary.md 新建即含）
        // WF-E E2 匹配算法对齐后 old_string 需真实存在于文件（errorCode 8 old_string not found 拒绝）
        editInput.put("old_string", "# Worklog");
        editInput.put("new_string", "# Worklog\n- session memory alignment");

        // seed 后（fork 隔离 ctx 共享 setup 播种缓存）→ read-before-write 门禁通过（无 errorCode 6）
        Tool.ValidationResult seeded = editTool.validateInput(editInput, query.captured.toolUseContext());
        assertThat(seeded.ok()).isTrue();

        // 未 seed（全新 ctx 空 readFileState）→ read-before-write 门禁拒绝 errorCode=6（CC :275-287）
        Tool.ValidationResult noSeed = editTool.validateInput(editInput, baseContext());
        assertThat(noSeed.ok()).isFalse();
        assertThat(noSeed.errorCode()).isEqualTo("6");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · createMemoryFileCanUseTool（sessionMemory.ts:460-482）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canUseTool: 仅 Edit 精确路径允许；其他工具/其他路径拒绝")
    void createMemoryFileCanUseTool_allowsOnlyExactEdit() {
        String path = baseDir.resolve("s1").resolve("session-memory").resolve("summary.md").toString();
        HookPermissionResolver.CanUseTool gate = SessionMemoryService.createMemoryFileCanUseTool(path);

        Tool editTool = Mockito.mock(Tool.class);
        Mockito.when(editTool.name()).thenReturn("Edit");
        Tool readTool = Mockito.mock(Tool.class);
        Mockito.when(readTool.name()).thenReturn("Read");

        ObjectNode exact = JSON.createObjectNode().put("file_path", path);
        ObjectNode wrong = JSON.createObjectNode().put("file_path", baseDir.resolve("other.md").toString());

        // Edit 精确路径 → ALLOW
        assertThat(gate.canUse(editTool, exact, null, "id", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        // Edit 其他路径 → DENY
        assertThat(gate.canUse(editTool, wrong, null, "id", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        // 其他工具（Read）→ DENY
        assertThat(gate.canUse(readTool, exact, null, "id", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        // 无 input → DENY
        assertThat(gate.canUse(editTool, null, null, "id", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · SM token 口径（OPD-R2-SM-01 · DRIFT-1/2 · G-32/G-44）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("token 口径: cache 占比场景 —— cache_read 计入后达 init 阈值（tokens.ts:226-260 四通道全窗口）")
    void shouldExtractMemory_cacheHeavyUsage_hitsInitThresholdOnlyWithCache() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // input 5000 + output 0 + cache_read 6000 → getTokenCountFromUsage 全窗口 11000 ≥ 10000
        // （CC tokens.ts:46-53 四通道）。旧实现（ChatMessageDto 无 cache 字段 → 恒 0）仅 5000 < 10000
        // → 不提取（DRIFT-1 系统性低估：cache 占比高时提取时机偏移）。
        ChatMessageDto cached = asst("a1", 5000, List.of()).withUsageCache(6000, 0);

        assertThat(svc.shouldExtractMemory(SESSION, List.of(cached))).isTrue();
        assertThat(SessionMemoryUtils.isSessionMemoryInitialized(SESSION)).isTrue();
    }

    @Test
    @DisplayName("token 口径: cache_creation 计入（四通道边界：input+output+creation = 恰好 10000）")
    void shouldExtractMemory_cacheCreationCountsTowardThreshold() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // 2000 + 0 + 0 + 8000(cache_creation) = 10000 → 恰好达 init 阈值（CC getTokenCountFromUsage）
        ChatMessageDto cached = asst("a1", 2000, List.of()).withUsageCache(0, 8000);

        assertThat(svc.shouldExtractMemory(SESSION, List.of(cached))).isTrue();
    }

    @Test
    @DisplayName("token 口径: 更新阈值增长含 cache（recordExtractionTokenCount 与阈值同口径）")
    void shouldExtractMemory_updateThresholdCountsCacheGrowth() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        // 首提取：全窗口 11000（input 5000 + cache_read 6000）→ 记录
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a1", 5000, List.of()).withUsageCache(6000, 0))))
            .isTrue();
        // 下一轮：input 不变 + cache_read 12000 → 全窗口 17000，增长 6000 ≥ 5000 → 提取
        assertThat(svc.shouldExtractMemory(SESSION, List.of(asst("a2", 5000, List.of()).withUsageCache(12000, 0))))
            .isTrue();
    }

    @Test
    @DisplayName("提取遥测: cache_read/cache_creation 属性随末条 usage 发射（sessionMemory.ts:335-337 · G-44）")
    void extractSessionMemory_telemetryIncludesCacheAttrs() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
                svc.setReadFileTool(readFileTool());
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        ChatMessageDto last = asst("a1", 12000, List.of()).withUsageCache(500, 300);
        PostSamplingContext ctx = new PostSamplingContext(List.of(last), List.of(""), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        Map<String, Object> attrs = telemetry.attrsOf("tengu_session_memory_extraction");
        assertThat(attrs).isNotNull();
        assertThat(attrs.get("input_tokens")).isEqualTo(12000);
        assertThat(attrs.get("output_tokens")).isEqualTo(0);
        assertThat(attrs.get("cache_read_input_tokens")).isEqualTo(500);
        assertThat(attrs.get("cache_creation_input_tokens")).isEqualTo(300);
        assertThat(attrs.get("config_min_message_tokens_to_init")).isEqualTo(10000);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 7 · DB sm_session_memory_enabled 门控（[SM-DB-gate] 2026-08-30）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DB sm_session_memory_enabled=true（无 env/feature 注入）→ 提取门控通过（fork 执行）")
    void extractSessionMemory_dbSmSessionEnabledTrue_forks() {
        // WHY (规则九): 前端配 DB sm_session_memory_enabled=true 即获提取入口（SM 功能总开关，
        //   sessionMemory.ts:80-82 + sessionMemoryCompact.ts:412-415 读同一 tengu_session_memory）——
        //   旧实现提取门控只吃 env/feature（ToolRegistrationConfig:1408-1412），DB 配了不生效。
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        // 不 setSessionMemoryFeatureEnabled（默认 false）→ 提取全靠 DB 覆盖放行
        svc.setSettingsResolver(settingsResolverWithSmSessionMemory(true));
        svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        assertThat(query.captured.querySource()).isEqualTo(QuerySource.SESSION_MEMORY);
    }

    @Test
    @DisplayName("DB sm_session_memory_enabled=false（即便注入 feature=true）→ 提取门控阻断（无 fork）")
    void extractSessionMemory_dbSmSessionEnabledFalse_blocks() {
        // WHY (规则九): 前端配 DB sm_session_memory_enabled=false 必须阻断提取——DB 有值覆盖
        //   env/feature 注入（DB 优先 + env/feature fallback，前端关即关）。
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);      // env/feature 链开
        svc.setSettingsResolver(settingsResolverWithSmSessionMemory(false)); // DB 覆盖为关
        svc.setReadFileTool(readFileTool());
        ToolUseContext tuc = baseContext();

        PostSamplingContext ctx = new PostSamplingContext(
            List.of(asst("a1", 12000, List.of())), List.of(""), Map.of(), Map.of(),
            tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNull();
        assertThat(SessionMemoryService.getLastSummarizedMessageId(tuc.sessionId())).isNull();
    }

    /** DB settings.sm_session_memory_enabled 单列 mock 读源（[SM-DB-gate] 测试辅助）。 */
    private CompactSettingsResolver settingsResolverWithSmSessionMemory(boolean v) {
        SettingsMapper mapper = Mockito.mock(SettingsMapper.class);
        SettingsRecord record = new SettingsRecord();
        record.setSmSessionMemoryEnabled(v);
        Mockito.when(mapper.selectOneById(1)).thenReturn(record);
        CompactSettingsResolver resolver = new CompactSettingsResolver();
        resolver.setSettingsMapper(mapper);
        return resolver;
    }

    /** 记录事件属性映射的 Telemetry 假实现（extraction 遥测属性断言用）。 */
    private static final class AttrRecordingTelemetry
            extends com.nexusai.application.agent.telemetry.Telemetry {
        final Map<String, Map<String, Object>> attrsByEvent = new java.util.HashMap<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            attrsByEvent.put(eventName, attributes);
            super.recordEvent(eventName, attributes);
        }

        Map<String, Object> attrsOf(String eventName) {
            return attrsByEvent.get(eventName);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** assistant 消息 · inputTokens 参与 tokenCountWithEstimation 估算（CC usage-walk）。 */
    private static ChatMessageDto asst(String id, int tokens, List<ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, tokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ToolCallDto call(String id) {
        return new ToolCallDto(id, "Read", "{}", null, null);
    }

    /** 最小 ToolUseContext（sessionId = 第二个 UUID）。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    /** 捕获 fork 参数的 RecordingQuery（对齐 RunForkedAgentTest 模式）。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        RunForkedAgent.ForkQueryParams captured;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.captured = params;
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
        }
    }
}

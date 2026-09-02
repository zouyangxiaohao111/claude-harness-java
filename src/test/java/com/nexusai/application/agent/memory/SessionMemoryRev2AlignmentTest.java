package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.CompactSummary;
import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.config.MemoryRemoteModeConfig;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.session.SessionMemoryPrompts;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-M-R2-P1-SM · SM 其余域对齐聚焦测试（SM-02/03/05/08/13/14 + G-41/G-43/G-48 + G-105/SM-15）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 本类钉死 rev2 对齐裁决的可观测行为：
 * <ol>
 *   <li>G-48 init 事件 ant-only（sessionMemory.ts:363-367）</li>
 *   <li>SM-08 gate_disabled ant-only + 每 session 一次（sessionMemory.ts:286-288）+ deny message（:473-480）</li>
 *   <li>G-43/OPD-CM3-28 异常时 extractionStartedAt 清空（sessionMemory.ts:272-350 无 finally；
 *       决策以 finally 兜底清除滞留时间戳，防 waitForSessionMemoryExtraction 阻塞满 15s）</li>
 *   <li>SM-05 提取互斥（sequential 等价 · sequential.ts:19-56）——相邻轮次不并发双 fork</li>
 *   <li>G-41 读取经权限层：ReadFileTool 错误 → 空内容不抛（sessionMemory.ts:223-226）</li>
 *   <li>SM-14 hasTextBlocks string/array 互斥分支（sessionMemoryCompact.ts:135-150）</li>
 *   <li>SM-13 模板前导换行（prompts.ts:11）</li>
 *   <li>SM-03 preCompactDiscoveredTools 非空写入 compactMetadata（sessionMemoryCompact.ts:447-459）</li>
 *   <li>SM-02 SessionStart hooks 结果进 CompactionResult.hookResults（sessionMemoryCompact.ts:583-586）</li>
 *   <li>G-105 CombinedMemoryPrompt skipIndex 分支空行（teamMemPrompts.ts:30-41）</li>
 *   <li>SM-15 摘要续跑段字节（prompt.ts:357-371）</li>
 * </ol>
 */
@DisplayName("[IMP-M-R2-P1-SM] SM 其余域对齐（hooks/门控/互斥/字节）")
class SessionMemoryRev2AlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path baseDir;

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        SessionMemoryService.setLastSummarizedMessageId(null, null);
        SessionMemoryService.resetLastMemoryMessageUuid();
        SessionMemoryService.resetGateDisabledLogging();
        com.nexusai.application.agent.hook.PostSamplingHookRegistry.clearAll();
        CompactSummary.setProactiveContinuationGate(() -> false);
        MemoryRemoteModeConfig.reset();
    }

    // ════════════════════════════════════════════════════════════════════
    // G-48 · init 事件 ant-only（sessionMemory.ts:363-367）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-48: userTypeIsAnt=false → tengu_session_memory_init 不发射（DRIFT-15）")
    void initEvent_notEmittedForNonAnt() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> false);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        svc.initSessionMemory();

        assertThat(telemetry.attrsOf("tengu_session_memory_init"))
            .as("非 ant 用户不发射 init 事件（CC sessionMemory.ts:363-367 ant-only）")
            .isNull();
    }

    @Test
    @DisplayName("G-48: userTypeIsAnt=true → tengu_session_memory_init 发射")
    void initEvent_emittedForAnt() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> true);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        svc.initSessionMemory();

        assertThat(telemetry.attrsOf("tengu_session_memory_init")).isNotNull();
        assertThat(telemetry.attrsOf("tengu_session_memory_init").get("auto_compact_enabled"))
            .isEqualTo(true);
    }

    @Test
    @DisplayName("[IMP-CM-19] remote mode → initSessionMemory 早退，init 事件不发射（CC sessionMemory.ts:358）")
    void initEvent_notEmittedInRemoteMode() {
        // remote mode 开启（nexusai.memory.remote-mode=true 等价 STATE.isRemoteMode=true，
        //   main.tsx:3328/:3447 setIsRemoteMode(true)）；即使 ant + autoCompactEnabled=true，
        //   CC :358 getIsRemoteMode 早退在 :363 init 事件 / :374 registerPostSamplingHook 之前。
        new MemoryRemoteModeConfig(true);
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> true);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        svc.initSessionMemory();

        assertThat(telemetry.attrsOf("tengu_session_memory_init"))
            .as("remote mode 下不发射 init 事件（CC sessionMemory.ts:358 getIsRemoteMode 早退）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-08 · gate_disabled ant-only + 一次性（sessionMemory.ts:286-288）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-08: feature off + ant → gate_disabled 每 session 仅发射一次（DRIFT-5）")
    void gateDisabled_antOnlyOncePerSession() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> true);
        svc.setSessionMemoryFeatureEnabled(false);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        PostSamplingContext ctx = new PostSamplingContext(List.of(), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);
        svc.extractSessionMemory(ctx);

        assertThat(telemetry.eventCount("tengu_session_memory_gate_disabled"))
            .as("ant-only + hasLoggedGateFailure 一次性守卫：仅首次发射")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("SM-08: feature off + 非 ant → gate_disabled 不发射")
    void gateDisabled_notEmittedForNonAnt() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> false);
        svc.setSessionMemoryFeatureEnabled(false);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        PostSamplingContext ctx = new PostSamplingContext(List.of(), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(telemetry.eventCount("tengu_session_memory_gate_disabled")).isZero();
    }

    @Test
    @DisplayName("SM-08: canUseTool deny 带 message+decisionReason（DRIFT-12 · sessionMemory.ts:473-480）")
    void canUseTool_denyCarriesMessageAndReason() {
        String path = baseDir.resolve("s1").resolve("session-memory").resolve("summary.md").toString();
        var gate = SessionMemoryService.createMemoryFileCanUseTool(path);
        com.nexusai.application.agent.permission.ToolPermissionGate.DecisionResult denied =
            gate.canUse(Mockito.mock(com.nexusai.application.agent.tool.Tool.class),
                JSON.createObjectNode().put("file_path", baseDir.resolve("other.md").toString()),
                null, "id", null);

        assertThat(denied.decision()).isEqualTo(com.nexusai.application.agent.permission.ToolPermissionGate.Decision.DENY);
        assertThat(denied.result()).isInstanceOf(com.nexusai.application.agent.permission.PermissionResult.Deny.class);
        var deny = (com.nexusai.application.agent.permission.PermissionResult.Deny) denied.result();
        assertThat(deny.message())
            .as("CC sessionMemory.ts:475 `only Edit on {path} is allowed`")
            .isEqualTo("only Edit on " + path + " is allowed");
        assertThat(deny.reason()).isInstanceOf(com.nexusai.application.agent.permission.PermissionDecisionReason.Other.class);
        assertThat(((com.nexusai.application.agent.permission.PermissionDecisionReason.Other) deny.reason()).reason())
            .isEqualTo("only Edit on " + path + " is allowed");
    }

    // ════════════════════════════════════════════════════════════════════
    // G-43 · 异常时 extractionStartedAt 滞留（sessionMemory.ts:272-350 无 finally）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-43/OPD-CM3-28: 提取异常 → extractionStartedAt 清空（finally 兜底，防 15s 阻塞）")
    void extractionException_clearsExtractionStartedAtViaFinally() {
        RunForkedAgent.ForkedQuery throwingQuery = params -> {
            throw new IllegalStateException("fork boom");
        };
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(throwingQuery);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());

        ToolUseContext tuc = baseContext();
        PostSamplingContext ctx = new PostSamplingContext(List.of(asst("a1", 12000, List.of())), List.of(), Map.of(), Map.of(), tuc, QuerySource.REPL_MAIN_THREAD);

        assertThatThrownBy(() -> svc.extractSessionMemory(ctx))
            .as("异常上抛（onPostSampling 层才隔离）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fork boom");

        // [sm-cursor-sessionize] extractionStartedAt 已按 sessionId 键控 → 读本会话时间戳
        assertThat(SessionMemoryUtils.getExtractionStartedAt(tuc.sessionId()))
            .as("OPD-CM3-28: 异常后 finally 兜底清空 extractionStartedAt（CC 无 finally 会滞留，"
                + "Java 决策改 finally 清除防 waitForSessionMemoryExtraction 阻塞满 15s）")
            .isNull();
    }

    @Test
    @DisplayName("G-43: 提取成功 → extractionStartedAt 清空（成功路径 mark，CC :349）")
    void extractionSuccess_clearsExtractionStartedAt() {
        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());

        ToolUseContext tuc = baseContext();
        PostSamplingContext ctx = new PostSamplingContext(List.of(asst("a1", 12000, List.of())), List.of(), Map.of(), Map.of(), tuc, QuerySource.REPL_MAIN_THREAD);

        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        // [sm-cursor-sessionize] extractionStartedAt 已按 sessionId 键控 → 读本会话时间戳
        assertThat(SessionMemoryUtils.getExtractionStartedAt(tuc.sessionId()))
            .as("成功路径 markExtractionCompleted（CC sessionMemory.ts:349）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-05 · 提取互斥（sequential 等价 · sequential.ts:19-56）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-05: 相邻轮次并发提取 → fork 最大并发 1（双 fork 同写防护）")
    void concurrentExtraction_mutuallyExclusive() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        RunForkedAgent.ForkedQuery blockingQuery = params -> {
            int cur = active.incrementAndGet();
            maxActive.accumulateAndGet(cur, Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
        };
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(blockingQuery);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(readFileTool());
        svc.setUserTypeIsAnt(() -> false);

        // 两轮不同消息（init 12000 → 更新 18000 增长 6000 ≥ 5000），两轮都满足提取阈值
        PostSamplingContext ctx1 = new PostSamplingContext(List.of(asst("a1", 12000, List.of())), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);
        PostSamplingContext ctx2 = new PostSamplingContext(List.of(asst("a2", 18000, List.of())), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        Thread t1 = new Thread(() -> svc.extractSessionMemory(ctx1));
        Thread t2 = new Thread(() -> svc.extractSessionMemory(ctx2));
        t1.start();
        t2.start();
        // 等待两线程都进入（t1 持锁阻塞在 fork；t2 在锁上排队）
        Thread.sleep(300);
        release.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(t1.isAlive()).isFalse();
        assertThat(t2.isAlive()).isFalse();
        assertThat(maxActive.get())
            .as("SM-05 sequential 等价：相邻轮次 fork 不并发（旧实现并行 CompletableFuture 可并发）")
            .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // G-41 · 读取经权限层（sessionMemory.ts:217-226 FileReadTool.call）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-41: ReadFileTool 权限层拒绝（error 结果）→ currentMemory 空、提取不抛（CC :223-226）")
    void permissionLayerDeniedRead_returnsEmptyMemory() {
        ReadFileTool denyingReadTool = Mockito.mock(ReadFileTool.class);
        Mockito.when(denyingReadTool.name()).thenReturn("Read");
        Mockito.when(denyingReadTool.execute(Mockito.any(), Mockito.any()))
            .thenReturn(ToolResult.error("id", "Permission denied by read rules"));

        RecordingQuery query = new RecordingQuery();
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(query);
        svc.setSessionMemoryFeatureEnabled(true);
        svc.setReadFileTool(denyingReadTool);

        PostSamplingContext ctx = new PostSamplingContext(List.of(asst("a1", 12000, List.of())), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        // 不抛（CC 错误结果 → 非 text 输出 → currentMemory=''，不打断提取）
        svc.extractSessionMemory(ctx);

        assertThat(query.captured).isNotNull();
        String prompt = query.captured.messages().get(query.captured.messages().size() - 1).content();
        assertThat(prompt)
            .as("权限层拒绝 → 空 memory 内容进入提取 prompt（无文件内容泄漏）")
            .doesNotContain("# Session Title");
    }

    @Test
    @DisplayName("G-41: ReadFileTool 未注入 → fail-loud（无 Files.readString 直读降级）")
    void readFileToolMissing_failsLoud() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setForkedQuery(new RecordingQuery());
        svc.setSessionMemoryFeatureEnabled(true);
        // 不注入 readFileTool

        PostSamplingContext ctx = new PostSamplingContext(List.of(asst("a1", 12000, List.of())), List.of(), Map.of(), Map.of(), baseContext(), QuerySource.REPL_MAIN_THREAD);

        assertThatThrownBy(() -> svc.extractSessionMemory(ctx))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ReadFileTool 未注入");
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-14 · hasTextBlocks string/array 互斥分支（sessionMemoryCompact.ts:135-150）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-14: assistant contentBlocks 数组分支独占——无 text 块 → false（不回落 content）")
    void hasTextBlocks_assistantArrayBranchExclusive() {
        // contentBlocks=[tool_use]（数组分支）→ false；旧实现回落 content()='ok' → true（DRIFT-21）
        ChatMessageDto assistantWithToolUse = new ChatMessageDto(
            "a1", null, Role.assistant, "assistant", "ok", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(toolUseBlock("t1")), List.of(), null, false, false);
        assertThat(SessionMemoryService.hasTextBlocks(assistantWithToolUse)).isFalse();

        // contentBlocks=[text] → true
        ChatMessageDto assistantWithText = new ChatMessageDto(
            "a2", null, Role.assistant, "assistant", "ok", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(textBlock("hello")), List.of(), null, false, false);
        assertThat(SessionMemoryService.hasTextBlocks(assistantWithText)).isTrue();

        // contentBlocks=null（Java 合成纯文本）→ content 非空白 → true（CC 等价单 text 块）
        assertThat(SessionMemoryService.hasTextBlocks(asst("a3", 0, List.of()))).isTrue();
    }

    @Test
    @DisplayName("SM-14: user string/array 互斥分支——content() 优先，其次 contentBlocks（CC :141-147 同序）")
    void hasTextBlocks_userStringThenArray() {
        // content 非 null（含空串）→ string 分支独占（CC typeof === 'string' 优先）
        ChatMessageDto userEmpty = new ChatMessageDto(
            "u1", null, Role.user, "user", "", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(textBlock("ignored")), List.of(), null, false, false);
        assertThat(SessionMemoryService.hasTextBlocks(userEmpty))
            .as("user content=''（string 分支）→ false（CC :143 length>0）")
            .isFalse();

        ChatMessageDto userText = new ChatMessageDto(
            "u2", null, Role.user, "user", "hi", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(toolUseBlock("t1")), List.of(), null, false, false);
        assertThat(SessionMemoryService.hasTextBlocks(userText))
            .as("user content='hi'（string 分支）→ true，contentBlocks 不参与")
            .isTrue();

        // content=null → array 分支
        ChatMessageDto userArrayText = new ChatMessageDto(
            "u3", null, Role.user, "user", null, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(textBlock("hi")), List.of(), null, false, false);
        assertThat(SessionMemoryService.hasTextBlocks(userArrayText)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-13 · 模板前导换行（prompts.ts:11）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-13: DEFAULT_SESSION_MEMORY_TEMPLATE 以 \\n 开头（DRIFT-20）")
    void template_leadingNewline() {
        assertThat(SessionMemoryPrompts.DEFAULT_SESSION_MEMORY_TEMPLATE)
            .as("CC prompts.ts:11 模板字面量以换行开头")
            .startsWith("\n# Session Title\n");
        // isSessionMemoryEmpty 因 trim 不受影响（C7）
        assertThat(new SessionMemoryPrompts().isSessionMemoryEmpty(
            SessionMemoryPrompts.DEFAULT_SESSION_MEMORY_TEMPLATE)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-03 · preCompactDiscoveredTools（sessionMemoryCompact.ts:447-459）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-03: boundary compactMetadata 非空写入 preCompactDiscoveredTools（排序）")
    void boundary_writesPreCompactDiscoveredTools() throws Exception {
        Path dir = baseDir.resolve("s1").resolve("session-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), "# Learnings\nsome real learning content\n");

        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        List<ChatMessageDto> messages = new ArrayList<>(largeMessages(15));
        // 首条 user 消息携带 tool_result → tool_reference（Bash）
        messages.set(0, userMessageWithToolReference("ref-1", "Bash"));

        CompactionResult r = sm.trySessionMemoryCompaction(messages, "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.boundaryMarker().compactMetadata().preCompactDiscoveredTools())
            .as("CC sessionMemoryCompact.ts:452-457 非空写入排序列表")
            .containsExactly("Bash");
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-02 · SessionStart hooks（sessionMemoryCompact.ts:583-586）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-02: SM 压缩执行 SessionStart hooks('compact') → hookResults 进 CompactionResult（GAP-3）")
    void compaction_runsSessionStartHooks_intoHookResults() throws Exception {
        Path dir = baseDir.resolve("s1").resolve("session-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), "# Learnings\nsome real learning content\n");

        HookRegistry registry = Mockito.mock(HookRegistry.class);
        Mockito.when(registry.executeEventAll(Mockito.any())).thenReturn(List.of(
            new GenericHook.HookResult(false, null, null, null,
                "restored CLAUDE.md context", null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null,
                null, null, null, null)));

        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        sm.setSessionStartHookRegistry(registry);
        sm.setMainLoopModelSupplier(() -> "claude-sonnet-4-5");
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        CompactionResult r = sm.trySessionMemoryCompaction(
            largeMessages(15), "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.hookResults())
            .as("SessionStart hooks 输出必须进 CompactionResult.hookResults（旧实现恒空）")
            .hasSize(1);
        assertThat(r.hookResults().get(0).content()).contains("restored CLAUDE.md context");
        assertThat(r.hookResults().get(0).author()).isEqualTo("hook");
        // source='compact' + model 载荷
        Mockito.verify(registry).executeEventAll(Mockito.argThat(event -> {
            com.nexusai.application.agent.permission.hook.HookEvent e =
                (com.nexusai.application.agent.permission.hook.HookEvent) event;
            return "compact".equals(e.data().get("source"))
                && "claude-sonnet-4-5".equals(e.data().get("model"));
        }));
    }

    @Test
    @DisplayName("SM-02: hookRegistry 未注入 → hookResults 空（等价 CC 无 hook 注册）")
    void compaction_withoutHookRegistry_hookResultsEmpty() throws Exception {
        Path dir = baseDir.resolve("s1").resolve("session-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.md"), "# Learnings\nsome real learning content\n");

        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId("s1", null);

        CompactionResult r = sm.trySessionMemoryCompaction(
            largeMessages(15), "s1", "agent-1", Integer.MAX_VALUE);

        assertThat(r).isNotNull();
        assertThat(r.hookResults()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // G-105 · CombinedMemoryPrompt skipIndex 空行（teamMemPrompts.ts:30-41）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-105: skipIndex=true → FRONTMATTER 与 bullets 间有空行（DRIFT-17）")
    void combinedMemoryPrompt_skipIndex_blankLineAfterFrontmatter() {
        String prompt = CombinedMemoryPrompt.buildCombinedMemoryPrompt(
            "/auto", "/auto/team", List.of(), true, "");

        // CC teamMemPrompts.ts:30-41：...MEMORY_FRONTMATTER_EXAMPLE, '', '- Keep the name…'
        assertThat(prompt)
            .as("FRONTMATTER 示例（``` 结尾）与 '- Keep the name' 之间必须有空行")
            .contains("```\n\n- Keep the name, description, and type fields in memory files up-to-date with the content");
    }

    @Test
    @DisplayName("G-105: skipIndex=false → 两步保存分支不受影响（Step 2 仍存在）")
    void combinedMemoryPrompt_nonSkipIndex_unchanged() {
        String prompt = CombinedMemoryPrompt.buildCombinedMemoryPrompt(
            "/auto", "/auto/team", List.of(), false, "");

        assertThat(prompt).contains("**Step 2** — add a pointer to that file");
    }

    // ════════════════════════════════════════════════════════════════════
    // SM-15 · 摘要续跑段字节（prompt.ts:357-371）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM-15: suppressFollowUp 续跑段前导单 \\n（CC `${baseSummary}\\nContinue…`）+ proactive 段默认不可达")
    void continuationPrompt_singleNewline_proactiveInert() {
        String s = CompactSummary.buildUserMessage("plain", null, true, false);

        // CC prompt.ts:358 `${baseSummary}\nContinue the conversation…`（单 \n；旧实现 "\n\n"）
        assertThat(s).endsWith("\nContinue the conversation from where it left off without asking "
            + "the user any further questions. Resume directly — do not acknowledge the summary, "
            + "do not recap what was happening, do not preface with \"I'll continue\" or similar. "
            + "Pick up the last task as if the break never happened.");
        assertThat(s).doesNotContain("\n\nContinue the conversation");
        // proactive 模块不存在 → 段默认不可达（CC 基线 proactiveModule=null）
        assertThat(s).doesNotContain("autonomous/proactive mode");
    }

    @Test
    @DisplayName("SM-15: proactive 门控注入开启 → 自主续跑段追加（\\n\\n 前缀，CC prompt.ts:365-367）")
    void continuationPrompt_proactiveSegmentWhenGateOn() {
        CompactSummary.setProactiveContinuationGate(() -> true);
        try {
            String s = CompactSummary.buildUserMessage("plain", null, true, false);
            assertThat(s).endsWith("\n\nYou are running in autonomous/proactive mode. This is NOT "
                + "a first wake-up — you were already working autonomously before compaction. "
                + "Continue your work loop: pick up where you left off based on the summary above. "
                + "Do not greet the user or ask what to work on.");
        } finally {
            CompactSummary.setProactiveContinuationGate(() -> false);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // F2/DRIFT-13 · tengu_sm_compact_flag_check ant-only 发射（sessionMemoryCompact.ts:422-429）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("F2: ant → tengu_sm_compact_flag_check 三属性发射（DRIFT-13）")
    void flagCheck_emittedForAnt_withThreeAttrs() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> true);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(false);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        boolean use = svc.shouldUseSessionMemoryCompaction();

        assertThat(use).isFalse();
        Map<String, Object> attrs = telemetry.attrsOf("tengu_sm_compact_flag_check");
        assertThat(attrs)
            .as("ant-only 发射 flag_check（CC sessionMemoryCompact.ts:423-429）")
            .isNotNull();
        assertThat(attrs.get("tengu_session_memory")).isEqualTo(true);
        assertThat(attrs.get("tengu_sm_compact")).isEqualTo(false);
        assertThat(attrs.get("should_use")).isEqualTo(false);
    }

    @Test
    @DisplayName("F2: 非 ant → flag_check 不发射；AND 语义保持")
    void flagCheck_notEmittedForNonAnt() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        svc.setUserTypeIsAnt(() -> false);
        svc.setSmSessionMemoryEnabled(true);
        svc.setSmCompactEnabled(true);
        AttrRecordingTelemetry telemetry = new AttrRecordingTelemetry();
        svc.setTelemetry(telemetry);

        boolean use = svc.shouldUseSessionMemoryCompaction();

        assertThat(use).isTrue();
        assertThat(telemetry.attrsOf("tengu_sm_compact_flag_check"))
            .as("非 ant 不发射（CC :423 条件）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // G-42/DRIFT-16 · isFsInaccessible 五类（errors.ts:186-195）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-42: ENOTDIR 形态（父组件为文件）→ null 不抛（X-7 联动）")
    void getSessionMemoryContent_enotdirShape_returnsNull() throws Exception {
        Path root = baseDir.resolve("s1");
        Files.createDirectories(root);
        // session-memory 被一个普通文件占据 → 读 {root}/session-memory/summary.md
        // Unix: ENOTDIR → NoSuchFileException；Windows: ERROR_DIRECTORY →
        // FileSystemException("The directory name is invalid") → 均回落 null
        Files.writeString(root.resolve("session-memory"), "I am a file, not a dir");

        SessionMemoryService svc = new SessionMemoryService(baseDir);

        assertThat(svc.getSessionMemoryContent("s1"))
            .as("ENOTDIR 属 isFsInaccessible 五类 → null（errors.ts:186-195）")
            .isNull();
    }

    @Test
    @DisplayName("G-42: ELOOP 形态（symlink 自循环）→ null 不抛（errors.ts:186-195）")
    void getSessionMemoryContent_eloopShape_returnsNull() throws Exception {
        Path root = baseDir.resolve("s1");
        Files.createDirectories(root);
        try {
            // session-memory → 自身：解析循环 → Unix ELOOP → FileSystemException
            // ("Too many levels of symbolic links")
            Files.createSymbolicLink(root.resolve("session-memory"), Path.of("session-memory"));
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "symlink 创建不可用（Windows 无开发者模式等）→ 跳过 ELOOP 场景");
            return;
        }

        SessionMemoryService svc = new SessionMemoryService(baseDir);

        assertThat(svc.getSessionMemoryContent("s1"))
            .as("ELOOP 属 isFsInaccessible 五类 → null（errors.ts:186-195）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private ReadFileTool readFileTool() {
        return new ReadFileTool(PathGuard.of(baseDir.toString()));
    }

    private static ChatMessageDto asst(String id, int tokens, List<com.nexusai.model.session.dto.ToolCallDto> toolCalls) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "ok", null,
            toolCalls, FinishReason.stop, tokens, 0, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new ChatMessageDto("u" + i, null, Role.user, "user", "hi", null, List.of(),
                FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false));
        }
        return list;
    }

    /** user 消息 contentBlocks 携带 tool_result → tool_reference（Bash）。 */
    private static ChatMessageDto userMessageWithToolReference(String id, String toolName) {
        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put("type", "tool_result");
        ArrayNode content = toolResult.putArray("content");
        ObjectNode ref = content.addObject();
        ref.put("type", "tool_reference");
        ref.put("tool_name", toolName);
        return new ChatMessageDto(id, null, Role.user, "user", "hi", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(toolResult), List.of(), null, false, false);
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static ObjectNode toolUseBlock(String id) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", "Read");
        return block;
    }

    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
    }

    /** 捕获 fork 参数的 RecordingQuery（对齐 SessionMemoryExtractionPipelineTest 模式）。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        RunForkedAgent.ForkQueryParams captured;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.captured = params;
            return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
        }
    }

    /** 记录事件 + 计数（一次性守卫断言用）。 */
    private static final class AttrRecordingTelemetry
            extends com.nexusai.application.agent.telemetry.Telemetry {
        final Map<String, Map<String, Object>> attrsByEvent = new java.util.HashMap<>();
        final Map<String, Integer> countsByEvent = new java.util.HashMap<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            attrsByEvent.put(eventName, attributes);
            countsByEvent.merge(eventName, 1, Integer::sum);
            super.recordEvent(eventName, attributes);
        }

        Map<String, Object> attrsOf(String eventName) {
            return attrsByEvent.get(eventName);
        }

        int eventCount(String eventName) {
            return countsByEvent.getOrDefault(eventName, 0);
        }
    }
}

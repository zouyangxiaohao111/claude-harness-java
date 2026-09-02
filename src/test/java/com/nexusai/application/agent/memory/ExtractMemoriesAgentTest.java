package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkRawMaterial;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-M-P0-3 · ExtractMemoriesAgent forked-agent 重建测试 · 对齐 CC
 * services/extractMemories/extractMemories.ts。
 *
 * <p><b>WHY (规则 9 · 测试验证意图)</b>：extract 生产接线（DEL-M-43..48）把「教学版单次
 * chat + JSON 数组契约」删除，重建为 CC forked-agent（skipTranscript + maxTurns=5 + 受限
 * canUseTool）。以下测试验证这些 CC 行为为何重要：
 * <ol>
 *   <li><b>INV-6</b>：fork 必须 skipTranscript + maxTurns=5 + createAutoMemCanUseTool 受限
 *       （提取子代理不得污染主 transcript / 不得无限 turn / 只读写 auto-memory 目录）</li>
 *   <li><b>INV-4</b>：游标（lastMemoryMessageUuid）成功推进 / 失败不动 / compact 移除后
 *       回退全量计数 —— 保证每轮只处理新增消息，失败不丢消息</li>
 *   <li><b>INV-5</b>：主/背景互斥 hasMemoryWritesSince → skip fork + 推进游标 ——
 *       主 agent 已写记忆时后台提取冗余，必须跳过且不让下一轮重复处理该段</li>
 * </ol>
 */
@DisplayName("[IMP-M-P0-3] ExtractMemoriesAgent forked-agent 重建（INV-4/5/6）")
class ExtractMemoriesAgentTest {

    @TempDir
    Path tempDir;

    // ════════════════════════════════════════════════════════════════════
    // INV-6 · fork 参数契约（skipTranscript + maxTurns=5 + 受限 canUseTool）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extract 构造 fork: skipTranscript=true + maxTurns=5 + querySource=extract_memories + 受限 canUseTool (INV-6)")
    void extract_buildsForkWithSkipTranscriptMaxTurns5RestrictedCanUseTool() {
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);

        List<ChatMessageDto> messages = List.of(
            userMsg("m1", "hi"), userMsg("m2", "remember I prefer terse replies"));

        runExtractSync(agent, messages);
        ExtractMemoriesAgent.ExtractResult result = agent.lastResult();

        // fork 参数契约（INV-6 / extractMemories.ts:415-427）
        ForkedAgentParams p = agent.lastForkParams();
        assertThat(p).isNotNull();
        assertThat(p.skipTranscript()).isTrue();        // 子代理不记 transcript
        assertThat(p.maxTurns()).isEqualTo(5);          // 硬上限防验证兔子洞
        assertThat(p.querySource()).isEqualTo(QuerySource.EXTRACT_MEMORIES);
        assertThat(p.forkLabel()).isEqualTo("extract_memories");
        assertThat(p.canUseTool()).isNotNull();         // createAutoMemCanUseTool 受限

        // query seam 收到透传参数
        RunForkedAgent.ForkQueryParams q = query.lastParams();
        assertThat(q).isNotNull();
        assertThat(q.maxTurns()).isEqualTo(5);
        assertThat(q.querySource()).isEqualTo(QuerySource.EXTRACT_MEMORIES);
        assertThat(q.canUseTool()).isSameAs(p.canUseTool());
        // messages = [...forkContext, prompt user message]
        assertThat(q.messages()).hasSize(messages.size() + 1);
        // 最后一条是提取 prompt（含 opener + newMessageCount）
        ChatMessageDto promptMsg = q.messages().get(q.messages().size() - 1);
        assertThat(promptMsg.content()).contains("memory extraction subagent");
        assertThat(promptMsg.content()).contains("most recent ~2 messages");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("createAutoMemCanUseTool: 只读工具放行, auto-memory 内 Edit/Write 放行, 目录外 Write 拒绝, 写 Bash 拒绝 (INV-6)")
    void createAutoMemCanUseTool_gatesToolAccessToMemoryDir() {
        String memoryDir = tempDir.toString();
        HookPermissionResolver.CanUseTool gate = ExtractMemoriesAgent.createAutoMemCanUseTool(memoryDir);

        // Read/Grep/Glob 无条件放行（只读）
        assertThat(gate.canUse(tool("Read"), json(Map.of()), ctx(), "tu-1", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(gate.canUse(tool("Grep"), json(Map.of()), ctx(), "tu-2", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(gate.canUse(tool("Glob"), json(Map.of()), ctx(), "tu-3", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);

        // auto-memory 目录内 Edit/Write 放行
        String inDir = tempDir.resolve("user_role.md").toString();
        assertThat(gate.canUse(tool("Write"), json(Map.of("file_path", inDir)), ctx(), "tu-4", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
        assertThat(gate.canUse(tool("Edit"), json(Map.of("file_path", inDir)), ctx(), "tu-5", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);

        // 目录外 Write 拒绝（不能写主工作区）
        assertThat(gate.canUse(tool("Write"), json(Map.of("file_path", "C:/evil/x.md")), ctx(), "tu-6", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        // 无 file_path 的 Write 拒绝
        assertThat(gate.canUse(tool("Write"), json(Map.of()), ctx(), "tu-7", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
        // 其他工具（TaskCreate 等）拒绝
        assertThat(gate.canUse(tool("TaskCreate"), json(Map.of()), ctx(), "tu-8", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    @Test
    @DisplayName("[rev2 EX-04] canUseTool: REPL 无条件放行（extractMemories.ts:173-182 首分支）")
    void createAutoMemCanUseTool_replAllowed() {
        // WHY: CC createAutoMemCanUseTool 首分支放行 REPL（REPL 模式开启时基础工具隐藏、
        //   fork 改调 REPL；内层 VM 重查仍受限）——旧 Java 无该分支落"其余工具拒绝"，
        //   REPL 模式开启且 fork 调 REPL 时提取失败（OPD-R2-EX-04，EV-024）。
        HookPermissionResolver.CanUseTool gate =
            ExtractMemoriesAgent.createAutoMemCanUseTool(tempDir.toString());
        assertThat(gate.canUse(tool("REPL"), json(Map.of("command", "anything")), ctx(), "tu-1", null).decision())
            .as("REPL 必须无条件放行（内层 VM 重查仍受限）")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    @Test
    @DisplayName("[rev2 EX-04/G-101] canUseTool Bash: 缺 command 字段 → deny（CC schema 预校验 fail-closed）；空串 command 放行")
    void createAutoMemCanUseTool_bashMissingCommandDenied() {
        // WHY: CC extractMemories.ts:195-204 先 inputSchema.safeParse（command 必填 z.string()）
        //   再 isReadOnly —— 缺 command 字段的退化输入（如 {"timeout": 1000}）→ schema deny
        //   （fail-closed）；旧 Java 直接 isReadOnly → 空串放行 fail-open（权限门反转，EV-036）。
        //   空串 command：safeParse 通过 → isReadOnly("") 空串分支 allow（两侧同）。
        HookPermissionResolver.CanUseTool gate =
            ExtractMemoriesAgent.createAutoMemCanUseTool(tempDir.toString());

        // 缺 command 字段 → deny（fail-closed）
        ToolPermissionGate.DecisionResult missing =
            gate.canUse(tool("Bash"), json(Map.of("timeout", 1000)), ctx(), "tu-1", null);
        assertThat(missing.decision())
            .as("缺 command 字段必须 deny（CC schema 预校验 fail-closed）")
            .isEqualTo(ToolPermissionGate.Decision.DENY);

        // 空串 command：command 存在（isTextual）→ 交 isReadOnly 判定；只读 stub 下放行
        Tool bash = tool("Bash");
        Mockito.when(bash.isReadOnly(Mockito.any())).thenReturn(true);
        assertThat(gate.canUse(bash, json(Map.of("command", "")), ctx(), "tu-2", null).decision())
            .as("空串 command 两侧均放行（zod 接受空串 + isReadOnly 空串分支 allow）")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    @Test
    @DisplayName("[v5 E-03] canUseTool Bash: 整 schema 校验（command 必填 + timeout 类型），非法 timeout 类型 deny")
    void createAutoMemCanUseTool_bashWholeSchemaValidation() {
        // WHY: OPD-CM5-E-03 补齐整 schema 校验——CC extractMemories.ts:196-197
        //   `tool.inputSchema.safeParse(input)` 校验整 schema（command 必填 + timeout integer），
        //   {"command":"ls","timeout":"bad"} 也 deny；旧 Java 仅查 command 字段存在性（△-5
        //   IMP-MV2-40）→ 非法 timeout 类型放行，与 CC 判定分歧（探查 △-1 R2）。本测试用带
        //   真实 inputSchema 的 Bash mock 守护：非法 timeout 类型 → schema 失败 → deny（fail-closed）。
        HookPermissionResolver.CanUseTool gate =
            ExtractMemoriesAgent.createAutoMemCanUseTool(tempDir.toString());

        // Bash inputSchema：command 必填 string + timeout integer（对齐 BashTool.inputSchema）
        Tool bash = tool("Bash");
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode command = om.createObjectNode();
        command.put("type", "string");
        com.fasterxml.jackson.databind.node.ObjectNode timeout = om.createObjectNode();
        timeout.put("type", "integer");
        com.fasterxml.jackson.databind.node.ObjectNode properties = om.createObjectNode();
        properties.set("command", command);
        properties.set("timeout", timeout);
        schema.set("properties", properties);
        schema.set("required", om.createArrayNode().add("command"));
        Mockito.when(bash.inputSchema()).thenReturn(schema);
        Mockito.when(bash.isReadOnly(Mockito.any())).thenReturn(true);

        // 合法只读输入（command 存在 + schema 通过 + isReadOnly true）→ allow
        assertThat(gate.canUse(bash, json(Map.of("command", "ls")), ctx(), "tu-1", null).decision())
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);

        // 非法 timeout 类型（CC safeParse 整 schema 校验失败）→ deny（fail-closed）
        assertThat(gate.canUse(bash, json(Map.of("command", "ls", "timeout", "bad")), ctx(), "tu-2", null).decision())
            .as("非法 timeout 类型必须 deny（CC safeParse 整 schema 校验，OPD-CM5-E-03）")
            .isEqualTo(ToolPermissionGate.Decision.DENY);
    }

    @Test
    @DisplayName("[rev2 EX-07] canUseTool deny 载荷：Bash 非只读/其余工具/目录外 Write 带 CC 具体指引 + decisionReason")
    void createAutoMemCanUseTool_denyCarriesGuidePayload() {
        // WHY: CC denyAutoMemTool（extractMemories.ts:154-164）返回 {behavior:'deny',
        //   message: reason, decisionReason:{type:'other', reason}} —— Bash 非只读文案 :200-203，
        //   其余 deny 文案 :217-220（含 memoryDir）。旧 Java 恒 deny(null) → 模型收到通用
        //   "Permission denied for tool use." 无指引（OPD-R2-EX-07，EV-037）。
        String memoryDir = tempDir.toString();
        HookPermissionResolver.CanUseTool gate = ExtractMemoriesAgent.createAutoMemCanUseTool(memoryDir);
        String otherGuide =
            "only Read, Grep, Glob, read-only Bash, and Edit/Write within " + memoryDir + " are allowed";
        String bashGuide =
            "Only read-only shell commands are permitted in this context (ls, find, grep, cat, stat, wc, head, tail, and similar)";

        // Bash 非只读 → CC 只读命令指引
        ToolPermissionGate.DecisionResult writeBash =
            gate.canUse(tool("Bash"), json(Map.of("command", "rm -rf /")), ctx(), "tu-1", null);
        assertThat(writeBash.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertDenyPayload(writeBash, bashGuide);

        // 其余工具 → CC 工具集指引
        ToolPermissionGate.DecisionResult other =
            gate.canUse(tool("TaskCreate"), json(Map.of()), ctx(), "tu-2", null);
        assertThat(other.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertDenyPayload(other, otherGuide);

        // 目录外 Write → 落"其余工具拒绝"通用文案（CC Edit/Write 非目录内不单独给文案）
        ToolPermissionGate.DecisionResult outOfDir =
            gate.canUse(tool("Write"), json(Map.of("file_path", "C:/evil/x.md")), ctx(), "tu-3", null);
        assertThat(outOfDir.decision()).isEqualTo(ToolPermissionGate.Decision.DENY);
        assertDenyPayload(outOfDir, otherGuide);
    }

    /** [rev2 EX-07] deny 结果必须携带 CC 指引 message + Other(reason) decisionReason。 */
    private static void assertDenyPayload(ToolPermissionGate.DecisionResult result, String expectedGuide) {
        assertThat(result.result()).as("deny 必须携带 PermissionResult.Deny 载荷（非 null）")
            .isInstanceOf(com.nexusai.application.agent.permission.PermissionResult.Deny.class);
        com.nexusai.application.agent.permission.PermissionResult.Deny deny =
            (com.nexusai.application.agent.permission.PermissionResult.Deny) result.result();
        assertThat(deny.message()).as("deny message 必须 = CC 具体指引文本").isEqualTo(expectedGuide);
        assertThat(deny.reason()).as("decisionReason.type 必须 = other").isInstanceOf(
            com.nexusai.application.agent.permission.PermissionDecisionReason.Other.class);
        assertThat(((com.nexusai.application.agent.permission.PermissionDecisionReason.Other) deny.reason()).reason())
            .as("decisionReason.reason 必须 = 同一指引文本").isEqualTo(expectedGuide);
    }

    // ════════════════════════════════════════════════════════════════════
    // INV-4 · 游标：成功推进 / 失败不动 / compact 移除→全量计数
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("游标: fork 成功后推进到最后一条, 下一轮只计数新增消息 (INV-4)")
    void cursor_advancesAfterSuccess_onlyNewMessagesCounted() {
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        agent.setForkedQuery(new RecordingQuery());

        List<ChatMessageDto> turn1 = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        runExtractSync(agent, turn1);
        assertThat(agent.getLastMemoryMessageUuid()).isEqualTo("m2");

        // 第二轮：只有 m3 是新增 → prompt 计数 ~1
        List<ChatMessageDto> turn2 = List.of(userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c"));
        runExtractSync(agent, turn2);
        ForkedAgentParams p2 = agent.lastForkParams();
        ChatMessageDto prompt2 = p2.promptMessages().get(p2.promptMessages().size() - 1);
        assertThat(prompt2.content()).contains("most recent ~1 messages");
    }

    @Test
    @DisplayName("游标: fork 失败（异常）时游标不动, 下次重试仍计数全部未处理消息 (INV-4)")
    void cursor_staysPutOnFailure() {
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        agent.setForkedQuery(new FailingQuery());

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        runExtractSync(agent, messages);
        // 失败不推进游标（extractMemories.ts:429-435 注释：失败留原地下次重试）
        assertThat(agent.getLastMemoryMessageUuid()).isNull();
        // 结果 newCount=0（best-effort，不抛异常到调用方）
        assertThat(agent.lastResult().newCount()).isZero();
    }

    @Test
    @DisplayName("游标: sinceUuid 未找到（compact 移除）→ 回退全量计数而非返回 0 (INV-4)")
    void countModelVisibleMessagesSince_fallsBackToAllWhenCursorMissing() {
        // extractMemories.ts:106-108 —— sinceUuid 被 context compaction 移除时回退全量，
        // 否则提取会永久禁用直到会话结束。
        List<ChatMessageDto> messages = List.of(
            userMsg("m1", "a"), userMsg("m2", "b"), assistantMsg("m3", "c"));
        assertThat(ExtractMemoriesAgent.countModelVisibleMessagesSince(messages, "ghost-uuid"))
            .isEqualTo(3);
    }

    // ════════════════════════════════════════════════════════════════════
    // INV-5 · 主/背景互斥 hasMemoryWritesSince
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("主/背景互斥: 主 agent 已写 auto-memory → skip fork + 推进游标 (INV-5)")
    void extract_skipsForkWhenMainAgentWroteMemory_advancesCursor() {
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);

        String memFile = tempDir.resolve("user_role.md").toString();
        List<ChatMessageDto> messages = List.of(
            userMsg("m1", "hi"),
            assistantWriteMsg("m2", memFile));   // 主 agent 直接写记忆

        runExtractSync(agent, messages);
        // 互斥：不启动 fork（recordingQuery 不被调用）
        assertThat(query.called()).isFalse();
        // 游标推进到最后一条（extractMemories.ts:352-355）
        assertThat(agent.getLastMemoryMessageUuid()).isEqualTo("m2");
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-M-C-1 · telemetry 事件（tengu_extract_memories_* / tengu_auto_mem_tool_denied）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fork 成功 → tengu_extract_memories_extraction 事件（token/message/files/duration 属性，CC :473-485）")
    void extract_success_emitsExtractionTelemetry() {
        // WHY: CC extractMemories.ts:473-485 在 fork 成功后发 tengu_extract_memories_extraction
        //       （input/output/cache/message_count/turn_count/files_written/memories_saved/duration_ms）
        //       —— 记忆提取的用量审计数据基础。事件名漂移 = analytics 数据丢失（对齐 CC 事件表）。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);
        agent.setForkedQuery(new RecordingQuery());

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        runExtractSync(agent, messages);

        assertThat(telemetry.events)
            .as("fork 成功必须发射 tengu_extract_memories_extraction")
            .contains("tengu_extract_memories_extraction");
        assertThat(telemetry.events).doesNotContain("tengu_extract_memories_error");
    }

    @Test
    @DisplayName("主 agent 已写记忆 → tengu_extract_memories_skipped_direct_write(message_count)（CC :356-358）")
    void extract_skippedDirectWrite_emitsTelemetry() {
        // WHY: CC extractMemories.ts:356-358 在主/背景互斥跳过 fork 时发
        //       tengu_extract_memories_skipped_direct_write（message_count）—— 标记冗余跳过，
        //       否则 analytics 会把"主 agent 已写"误读为"提取失败"。Java 端互斥分支埋点。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);

        String memFile = tempDir.resolve("user_role.md").toString();
        List<ChatMessageDto> messages = List.of(
            userMsg("m1", "hi"),
            assistantWriteMsg("m2", memFile));   // 主 agent 直接写记忆 → 互斥跳过

        runExtractSync(agent, messages);

        assertThat(telemetry.events)
            .as("互斥跳过必须发射 tengu_extract_memories_skipped_direct_write")
            .contains("tengu_extract_memories_skipped_direct_write");
        assertThat(telemetry.events).doesNotContain("tengu_extract_memories_extraction");
    }

    @Test
    @DisplayName("受限 canUseTool deny → tengu_auto_mem_tool_denied(tool_name)（CC :154-164）")
    void createAutoMemCanUseTool_deny_emitsToolDeniedTelemetry() {
        // WHY: CC denyAutoMemTool（extractMemories.ts:154-164）在 Bash 非只读/Edit-Write 非
        //       auto-memory/其余工具拒绝时发 tengu_auto_mem_tool_denied(tool_name) —— 审计
        //       fork 内被拒绝的工具调用（INV-6 受限门控的可见性）。Java 端经 onToolDenied 回调注入。
        List<String> denied = new java.util.ArrayList<>();
        HookPermissionResolver.CanUseTool gate =
            ExtractMemoriesAgent.createAutoMemCanUseTool(tempDir.toString(), denied::add);

        // 写 Bash（非只读）→ deny → tengu_auto_mem_tool_denied
        gate.canUse(tool("Bash"), json(Map.of("command", "rm -rf /")), ctx(), "tu-1", null);
        assertThat(denied).contains("Bash");
        // 目录外 Write → deny
        gate.canUse(tool("Write"), json(Map.of("file_path", "C:/evil/x.md")), ctx(), "tu-2", null);
        assertThat(denied).contains("Write");
        // 只读工具不放 deny 回调
        gate.canUse(tool("Read"), json(Map.of("file_path", "x")), ctx(), "tu-3", null);
        assertThat(denied).containsExactlyInAnyOrder("Bash", "Write");
    }

    // ════════════════════════════════════════════════════════════════════
    // FIX-EX · manifest 格式 / memoryPaths basename / skipIndex 门控
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("manifest 格式: - [type] filename (ISO8601): desc，无 desc 省略冒号后缀，无 '(no description)' 伪注（CC memoryScan.ts:84-94）")
    void formatManifest_iso8601Timestamp_noFakeNote() throws Exception {
        // WHY: CC memoryScan.ts:84-94 —— 预注入 manifest 每行
        //   `- [type] filename (ts): desc` / 无 desc 时省略冒号后缀。Java 此前无时间戳 +
        //   '(no description)' 伪注 → 注入 LLM 的 prompt 偏离 CC，且缺时间戳让 agent 无法按
        //   新旧判断更新哪个文件（对齐 CC 的数据流契约）。
        Path memFile = tempDir.resolve("user_role.md");
        java.nio.file.Files.writeString(memFile,
            "---\nname: user_role\ndescription: prefers terse replies\ntype: user\n---\n\nPrefer terse replies.\n");

        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        agent.setForkedQuery(new RecordingQuery());
        runExtractSync(agent, List.of(userMsg("m1", "a"), userMsg("m2", "b")));

        ForkedAgentParams p = agent.lastForkParams();
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        // CC memoryScan.ts:88 ts=new Date(m.mtimeMs).toISOString() → ISO-8601 UTC（形如 2026-..T..Z）
        assertThat(promptMsg.content())
            .as("manifest 行必须含 [type] filename (ISO8601): desc")
            .containsPattern("- \\[user\\] user_role\\.md \\(\\d{4}-\\d{2}-\\d{2}T.*Z\\): prefers terse replies");
        assertThat(promptMsg.content())
            .as("manifest 不得再含 '(no description)' 伪注（CC memoryScan.ts:91 无 desc 时省略冒号后缀）")
            .doesNotContain("(no description)");
    }

    @Test
    @DisplayName("memoryPaths basename 等值过滤: MEMORY.md 排除、userMEMORY.md 保留（CC :465-467 basename(p) !== ENTRYPOINT_NAME）")
    void memoryPaths_basenameFilter_keepsUserMEMORY() throws Exception {
        // WHY: CC extractMemories.ts:465-467 —— writtenPaths.filter(p => basename(p) !==
        //   ENTRYPOINT_NAME)。Java 此前 endsWith("MEMORY.md") 会把 userMEMORY.md 误排
        //   （memory_saved 计数少算 + MEMORY.md 索引语义错位）；basename 等值才对齐 CC。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        String indexFile = tempDir.resolve("MEMORY.md").toString();
        String userMem = tempDir.resolve("userMEMORY.md").toString();
        String roleFile = tempDir.resolve("user_role.md").toString();
        agent.setForkedQuery(params -> new ForkedAgentResult(
            List.of(
                assistantWriteMsg("fw-1", indexFile),
                assistantWriteMsg("fw-2", userMem),
                assistantWriteMsg("fw-3", roleFile)),
            ForkedAgentResult.ForkUsage.empty()));
        runExtractSync(agent, List.of(userMsg("m1", "a"), userMsg("m2", "b")));

        // basename 等值：MEMORY.md 排除，userMEMORY.md 与 user_role.md 保留 → newCount=2
        assertThat(agent.lastResult().newCount())
            .as("MEMORY.md 排除、userMEMORY.md 保留（endsWith 会误排为 1）")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("skipIndex 门控: true → 单步 howToSave（不更新 MEMORY.md 索引），false → 两步保存（CC prompts.ts:55-82 / extractMemories.ts:366-369）")
    void skipIndexGate_switchesHowToSave() {
        // WHY: CC extractMemories.ts:366-369 getFeatureValue('tengu_moth_copse', false) → :407/:412
        //   传入 buildExtractAutoOnlyPrompt。skipIndex=true 时 prompts.ts:56-66 单步写文件（不碰
        //   MEMORY.md 索引）；false 时 prompts.ts:68-82 两步（写文件 + MEMORY.md 加索引行）。
        //   Java 此前硬编码 false → skipIndex=true 的分支永远不可达。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        agent.setForkedQuery(new RecordingQuery());

        agent.setSkipIndexGate(() -> true);
        runExtractSync(agent, List.of(userMsg("m1", "a"), userMsg("m2", "b")));
        String promptTrue = agent.lastForkParams().promptMessages()
            .get(agent.lastForkParams().promptMessages().size() - 1).content();
        assertThat(promptTrue)
            .as("skipIndex=true → 单步 howToSave（写文件，无两步）")
            .contains("Write each memory to its own file");
        assertThat(promptTrue).doesNotContain("two-step process");

        agent.setSkipIndexGate(() -> false);
        runExtractSync(agent, List.of(userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c")));
        String promptFalse = agent.lastForkParams().promptMessages()
            .get(agent.lastForkParams().promptMessages().size() - 1).content();
        assertThat(promptFalse)
            .as("skipIndex=false → 两步 howToSave（写文件 + MEMORY.md 加索引行）")
            .contains("Saving a memory is a two-step process");
        assertThat(promptFalse).contains("add a pointer to that file in `MEMORY.md`");
    }

    @Test
    @DisplayName("[rev2 EX-02] 节流 env 通道：NEXUSAI_EXTRACT_MEMORIES_INTERVAL=2 → 第 2 轮运行、第 1/3 轮跳过，改值后每 run 重读")
    void extractionInterval_envChannel_throttlesPerRun() {
        // WHY: CC extractMemories.ts:380-385 每 run 读 feature('tengu_bramble_lintel')（默认 1）；
        //   Java 旧字段默认 1 + setter 0 调用方 → 部署标志等价建模不完整（OPD-R2-EX-02，EV-011/038）。
        //   property 经 resolveEnv（property 优先）注入，验证 env 通道 + 每 run 重读语义。
        String key = "NEXUSAI_EXTRACT_MEMORIES_INTERVAL";
        System.setProperty(key, "2");
        try {
            MemoryStorage storage = new MemoryStorage(tempDir);
            ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
            RecordingQuery query = new RecordingQuery();
            agent.setForkedQuery(query);
            agent.setExtractionGate(() -> true);
            agent.setAutoMemoryEnabled(() -> true);

            List<ChatMessageDto> t1 = List.of(userMsg("m1", "a"));
            List<ChatMessageDto> t2 = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
            List<ChatMessageDto> t3 = List.of(userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c"));

            agent.executeExtractMemories(t1, null);  // turn1: turns=1 < 2 → 节流跳过
            agent.drainPendingExtraction(5000);
            assertThat(query.called()).as("interval=2：第 1 轮必须节流跳过").isFalse();

            agent.executeExtractMemories(t2, null);  // turn2: turns=2 ≥ 2 → 运行
            agent.drainPendingExtraction(5000);
            assertThat(query.called()).as("interval=2：第 2 轮必须运行").isTrue();

            agent.executeExtractMemories(t3, null);  // turn3: turns 已重置 → 1 < 2 → 跳过
            agent.drainPendingExtraction(5000);
            assertThat(query.calls()).as("interval=2：第 3 轮必须再次节流跳过").isEqualTo(1);

            // 每 run 重读：改回 1 → 下一轮立即运行
            System.setProperty(key, "1");
            List<ChatMessageDto> t4 = List.of(userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c"), userMsg("m4", "d"));
            agent.executeExtractMemories(t4, null);
            agent.drainPendingExtraction(5000);
            assertThat(query.calls()).as("改 interval=1 后每 run 重读 → 立即运行").isEqualTo(2);
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("[rev2 EX-03] prompt 字节不变量：opener 尾 2 换行、manifest 注入段 2 换行、末尾无尾换行（EV-040/042 三处）")
    void promptByteInvariants_alignCC() {
        // WHY: CC prompts.ts:29-44/:84-93 array-join 无尾换行（opener 尾 2 换行、末尾无尾换行）
        //   + memoryScan.ts:84-93 manifest join('\n') 无尾换行（注入段 2 换行）—— Java 旧实现
        //   3 处 +1 换行（OPD-R2-EX-03，EV-040/042 脚本实证）。
        String manifestLine = "- [user] user_role.md (2026-08-06T04:35:12.000Z): prefers terse replies";
        String prompt = ExtractMemoriesOpener.buildExtractAutoOnlyPrompt(10, manifestLine, false);

        // ③ manifest 注入段 2 换行：最后一行 desc 与 "Check this list…" 之间恰 2 个 \n
        int manifestEnd = prompt.indexOf("prefers terse replies") + "prefers terse replies".length();
        assertThat(prompt.substring(manifestEnd, manifestEnd + 2))
            .as("manifest 注入段必须 2 换行（旧 formatManifest 尾换行 → 3 换行，D-06 消除）")
            .isEqualTo("\n\n");
        // ① opener 尾 2 换行：manifest 与 "If the user explicitly asks…" 之间恰 2 个 \n
        assertThat(prompt.substring(manifestEnd + 2)).startsWith("Check this list before writing");
        int ifJunction = prompt.indexOf("If the user explicitly asks");
        assertThat(prompt.substring(ifJunction - 2, ifJunction))
            .as("opener 尾必须 2 换行（旧 text block 尾换行 + 2 显式 \n → 3 换行）")
            .isEqualTo("\n\n");
        // ② 末尾无尾换行（CC array-join 无尾换行；旧 howToSave 末行尾 \n）
        assertThat(prompt).as("提示词末尾不得尾随换行").doesNotEndWith("\n");

        // 空 manifest：无 "## Existing memory files" 段 + opener 尾仍 2 换行
        String promptEmpty = ExtractMemoriesOpener.buildExtractAutoOnlyPrompt(10, "", false);
        assertThat(promptEmpty).doesNotContain("## Existing memory files");
        int emptyIf = promptEmpty.indexOf("If the user explicitly asks");
        assertThat(promptEmpty.substring(emptyIf - 2, emptyIf)).isEqualTo("\n\n");
        assertThat(promptEmpty).doesNotEndWith("\n");
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-M-P0-3b · 生命周期对齐（REQ-M-18）：trailing/coalesced/drain/memory_saved/gate_disabled
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("trailing run: in-progress 时 stash 上下文（coalesced），当前轮完成后尾随一轮且跳过节流（CC :557-564/:503-522）")
    void executeExtractMemories_inProgress_stashAndTrailingRun() throws Exception {
        // WHY: CC extractMemories.ts:557-564 —— 提取进行中时新调用必须 stash（覆盖旧值，仅最新
        //   有用）+ 发 tengu_extract_memories_coalesced；:503-522 finally 取走尾随一轮
        //   （isTrailingRun=true 跳过节流 :377-385），且尾随轮相对已推进的游标只计数新增消息。
        //   旧 Java 直返空结果分支会让第二轮的已提交工作丢失 —— 本测试验证 stash+trailing 语义。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        LatchedQuery query = new LatchedQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);  // 显式开 tengu_passport_quail gate
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);

        List<ChatMessageDto> turn1 = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(turn1, null);
        assertThat(query.awaitStarted(2000)).as("第一轮 fork 应已开始").isTrue();

        // 第二轮在第一轮 in-progress 时到达 → stash + coalesced（不叠加并发 fork）
        List<ChatMessageDto> turn2 = List.of(
            userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c"), userMsg("m4", "d"));
        agent.executeExtractMemories(turn2, null);
        awaitUntil(() -> telemetry.events.contains("tengu_extract_memories_coalesced"), 2000);

        // 释放第一轮 → finally 取走 stashed 上下文尾随一轮
        query.release();

        // 尾随轮相对已推进的游标（m2）只计数 m3/m4 → prompt "most recent ~2 messages"
        awaitUntil(() -> query.calls() == 2, 5000);
        ForkedAgentParams trailing = agent.lastForkParams();
        ChatMessageDto prompt = trailing.promptMessages().get(trailing.promptMessages().size() - 1);
        assertThat(prompt.content()).contains("most recent ~2 messages");
    }

    @Test
    @DisplayName("drainPendingExtraction: in-flight 非空时阻塞等待完成，超时后不阻塞（CC :579-586）")
    void drainPendingExtraction_waitsForInFlightThenCompletes() throws Exception {
        // WHY: CC print.ts:962-969 在响应 flush 后、shutdown 前 drain —— 等待 fork agent
        //   完成以免 5s shutdown failsafe 杀到中途；in-flight 为空立即返回（无锁开销）。
        //   本测试验证 drain 会阻塞（而非直接返回）并等待 in-flight 完成。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        LatchedQuery query = new LatchedQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(messages, null);
        assertThat(query.awaitStarted(2000)).as("fork 应已开始").isTrue();

        AtomicBoolean drainDone = new AtomicBoolean(false);
        Thread drainThread = new Thread(() -> {
            agent.drainPendingExtraction(5000);
            drainDone.set(true);
        });
        drainThread.start();

        // in-flight 未完成时 drain 必须阻塞
        Thread.sleep(300);
        assertThat(drainDone.get()).as("in-flight 未完成时 drain 必须阻塞").isFalse();

        query.release();
        drainThread.join(5000);
        assertThat(drainDone.get()).as("release 后 drain 应完成").isTrue();
    }

    @Test
    @DisplayName("memory_saved: memoryPaths>0 时经 appendSystemMessage 追加 subtype=memory_saved+writtenPaths（CC :490-496/messages.ts:4460-4471）")
    void executeExtractMemories_memorySavedCarriesWrittenPaths() throws Exception {
        // WHY: CC extractMemories.ts:490-496 —— fork 写入记忆文件后必须发 type=system
        //   subtype=memory_saved writtenPaths 系统消息（前端渲染"已保存记忆"）。此前 Java
        //   extract() 只更新 ExtractResult 不通知 UI —— 缺消息契约。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        String memFile = tempDir.resolve("user_role.md").toString();
        MemoryWriteQuery query = new MemoryWriteQuery(memFile);
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);

        List<SystemMessage> received = new ArrayList<>();
        Consumer<SystemMessage> append = received::add;
        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(messages, append);

        awaitUntil(() -> !received.isEmpty(), 2000);
        SystemMessage msg = received.get(0);
        assertThat(msg.role()).as("CC type='system'").isEqualTo("system");
        assertThat(msg.subtype()).as("CC subtype='memory_saved'").isEqualTo("memory_saved");
        assertThat(msg.writtenPaths()).contains(memFile);
    }

    @Test
    @DisplayName("[sm 决策 2026-08-30] DB 主控: auto_memory_enabled=true（无 env）→ 提取触发；false → 不触发")
    void extraction_dbGateControlsWhenEnvUnset() {
        // WHY: [sm 决策 2026-08-30] 总闸由 env NEXUSAI_EXTRACT_MEMORIES（默认 false，前端 DB 配了
        //   但 env 没开 = 永不提取）移至 DB settings 列 auto_memory_enabled（默认 true）—— 直接
        //   DB 改即生效，无需 env。经 setAutoMemoryEnabled 注入 DB 主控真值（生产 =
        //   BundledSkillEnabledGates.isAutoMemoryEnabled 读 DB 列）。不调用 setExtractionGate →
        //   无 env 覆盖，交 DB 主控判定。
        System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        try {
            MemoryStorage storage = new MemoryStorage(tempDir);
            // DB auto_memory_enabled = true → 提取触发
            ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
            RecordingQuery query = new RecordingQuery();
            agent.setForkedQuery(query);
            agent.setAutoMemoryEnabled(() -> true);
            agent.executeExtractMemories(List.of(userMsg("m1", "a"), userMsg("m2", "b")), null);
            agent.drainPendingExtraction(5000);
            assertThat(query.called())
                .as("DB auto_memory_enabled=true（无 env）必须触发提取（默认开）").isTrue();

            // DB auto_memory_enabled = false → 不触发
            ExtractMemoriesAgent agentOff = new ExtractMemoriesAgent(storage);
            RecordingQuery queryOff = new RecordingQuery();
            agentOff.setForkedQuery(queryOff);
            agentOff.setAutoMemoryEnabled(() -> false);
            agentOff.executeExtractMemories(List.of(userMsg("m1", "a"), userMsg("m2", "b")), null);
            agentOff.drainPendingExtraction(5000);
            assertThat(queryOff.called())
                .as("DB auto_memory_enabled=false（无 env）不得触发提取").isFalse();
        } finally {
            System.clearProperty("NEXUSAI_EXTRACT_MEMORIES");
        }
    }

    @Test
    @DisplayName("[sm-cursor-sessionize] 多会话游标隔离: A/B 会话各自推进游标，互不串扰")
    void cursor_multiSessionIsolated() {
        // WHY: ExtractMemoriesAgent 是 Spring 单例 bean（跨会话共享）—— 旧实例游标
        //   lastMemoryMessageUuid/turnsSinceLastExtraction/inProgress 跨会话共享：A 会话推进
        //   游标后 B 会话只 count 其"新增"消息 → 漏提取（同类串扰见 sm 修复）。现游标/stash/
        //   观察点全部按 sessionId 键控（{@link ExtractMemoriesAgent#cursorKey}，null → "unknown"）。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);

        // A 会话首轮 m1/m2 → 游标 m2；B 会话游标不受影响（未推进）
        agent.executeExtractMemories(List.of(userMsg("m1", "a"), userMsg("m2", "b")), null, null, null, "sess-A");
        agent.drainPendingExtraction(5000);
        assertThat(agent.getLastMemoryMessageUuid("sess-A")).isEqualTo("m2");
        assertThat(agent.getLastMemoryMessageUuid("sess-B")).as("B 会话游标不受 A 会话影响").isNull();

        // A 会话第二轮 m3 → 游标 m3（B 首轮只 count B 的消息）
        agent.executeExtractMemories(
            List.of(userMsg("m1", "a"), userMsg("m2", "b"), userMsg("m3", "c")), null, null, null, "sess-A");
        agent.drainPendingExtraction(5000);
        assertThat(agent.getLastMemoryMessageUuid("sess-A")).isEqualTo("m3");

        // B 会话首轮 x1/x2 → 游标 x2，A 会话游标仍为 m3（B 推进不覆盖 A）
        agent.executeExtractMemories(List.of(userMsg("x1", "u"), userMsg("x2", "v")), null, null, null, "sess-B");
        agent.drainPendingExtraction(5000);
        assertThat(agent.getLastMemoryMessageUuid("sess-B")).isEqualTo("x2");
        assertThat(agent.getLastMemoryMessageUuid("sess-A"))
            .as("A 会话游标必须保持 m3（B 会话推进不得串扰）").isEqualTo("m3");

        // 观察点按会话键控（各自留档，互不覆盖）
        assertThat(agent.lastResult("sess-A")).isNotNull();
        assertThat(agent.lastResult("sess-B")).isNotNull();
        assertThat(agent.lastForkParams("sess-A")).isNotNull();
    }

    @Test
    @DisplayName("Q6 cache-safe 降级 E4: cacheSafeParamsSupplier 未注入 → createMinimalCacheSafeParams 兜底，fork 正常运行（RES-C5）")
    void cacheSafe_degradation_nullSupplier_forkStillRuns() {
        // WHY: OPD-CM3-24 Q6 —— RES-C5 降级路径 E4 运行验证：cacheSafeParamsSupplier 未注入
        //   （null）时 extract 必须降级到 RunForkedAgent.createMinimalCacheSafeParams
        //   （systemPrompt/gate 原料缺省，ExtractMemoriesAgent.java:519-526），fork 仍须正常运行
        //   不 NPE —— 降级路径真实可达（不再恒 null/静默失败）。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        // 不调用 setCacheSafeParamsSupplier —— 默认 null → createMinimalCacheSafeParams 兜底

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        runExtractSync(agent, messages);

        assertThat(query.called()).as("降级路径下 fork 必须正常运行").isTrue();
        ForkedAgentParams p = agent.lastForkParams();
        assertThat(p.cacheSafeParams())
            .as("降级路径必须产出 cacheSafeParams（createMinimalCacheSafeParams 兜底）").isNotNull();
        // createMinimalCacheSafeParams 兜底：systemPrompt 降级为空（RES-C5 验收 2）、gate 透传（缺省 false）
        assertThat(p.cacheSafeParams().systemPrompt()).isEmpty();
        assertThat(p.cacheSafeParams().useGlobalCacheScope()).isFalse();
    }

    @Test
    @DisplayName("[IMP-MV2-09 T9] fork 原料注入：extract fork 载荷三段原料非空且与主线程同值（CC createCacheSafeParams · extractMemories.ts:372 → forkedAgent.ts:131-141）")
    void forkRawMaterial_payloadMatchesMainThread() {
        // WHY: △-1（域级唯一 HIGH）—— ToolRegistrationConfig.buildProductionCacheSafeParams 空载荷
        // （三段恒空）→ extract fork 无主系统提示（提取质量降级）+ prompt-cache key 与主线程不一致
        // （cache 共享失效）。T9 修复：LlmAgentLoop:5154 捕获 ForkRawMaterial 透传，runExtraction
        // 合并注入（supplied 空 → 原料）。RecordingQuery 断言 fork 载荷与主线程同值。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        // 生产 supplier 形态：三段恒空，仅 toolUseContext 载荷（buildProductionCacheSafeParams）
        ToolUseContext supplierCtx = ctx();
        agent.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierCtx, List.of(), false));
        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        ForkRawMaterial raw = new ForkRawMaterial(
            List.of("MAIN-SYSTEM-PROMPT-1", "MAIN-SYSTEM-PROMPT-2"),
            Map.of("claudeMd", "项目指令"),
            Map.of("gitStatus", "GIT-BLOCK"),
            messages);

        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        agent.executeExtractMemories(messages, null, raw);
        agent.drainPendingExtraction(5000);

        assertThat(query.called()).isTrue();
        RunForkedAgent.ForkQueryParams q = query.lastParams();
        assertThat(q).isNotNull();
        // 三段原料非空且与主线程同值（forkedAgent.ts:131-141 createCacheSafeParams）
        assertThat(q.systemPrompt())
            .as("fork systemPrompt = 主线程 fullSystemPrompt（同值透传 · cache key 对齐）")
            .containsExactlyElementsOf(raw.systemPrompt());
        assertThat(q.userContext()).containsEntry("claudeMd", "项目指令");
        assertThat(q.systemContext()).containsEntry("gitStatus", "GIT-BLOCK");
        // forkContextMessages = 消息快照（CC context.messages · extractMemories.ts:372）
        assertThat(q.messages()).extracting(ChatMessageDto::id).contains("m1", "m2");
        // 生产 supplier toolUseContext 保留（唯一有效载荷）
        assertThat(q.toolUseContext()).isNotNull();
    }

    @Test
    @DisplayName("[IMP-MV2-09 T9] supplied 非空 → 保留 supplied 三段（merge supplied 优先语义 · 同 SessionMemoryService RES-C5）")
    void forkRawMaterial_suppliedNonEmptyKeepsSupplied() {
        // WHY: mergeSystemPrompt/mergeContext "supplied 优先"（REQ-C5-1）—— 未来接线方注入完整
        // 组装数组时不得被 ForkRawMaterial 覆写（同 SessionMemoryService RES-C5 rework 语义）。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        ToolUseContext supplierCtx = ctx();
        agent.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of("REAL-ASSEMBLED-PROMPT"),
            Map.of("userKey", "userVal"),
            Map.of("sysKey", "sysVal"),
            supplierCtx, List.of(), false));
        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        ForkRawMaterial raw = new ForkRawMaterial(
            List.of("MAIN-SYSTEM-PROMPT"), Map.of("claudeMd", "x"),
            Map.of("gitStatus", "y"), messages);

        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        agent.executeExtractMemories(messages, null, raw);
        agent.drainPendingExtraction(5000);

        RunForkedAgent.ForkQueryParams q = query.lastParams();
        assertThat(q).isNotNull();
        assertThat(q.systemPrompt()).containsExactly("REAL-ASSEMBLED-PROMPT");
        assertThat(q.userContext()).containsEntry("userKey", "userVal");
        assertThat(q.systemContext()).containsEntry("sysKey", "sysVal");
    }

    @Test
    @DisplayName("gate_disabled: tengu_passport_quail 关闭且用户为 ant → 一次性发 gate_disabled，不启动 fork（CC :536-542）")
    void executeExtractMemories_gateDisabled_emittedOnceForAnt() throws Exception {
        // WHY: CC extractMemories.ts:536-542 —— gate 关闭（tengu_passport_quail=false）时，
        //   USER_TYPE==='ant' 且未记录过 gate 失败 → 一次性发 tengu_extract_memories_gate_disabled
        //   （hasLoggedGateFailure 防重复刷事件）。Java 无 USER_TYPE env → NEXUSAI_USER_TYPE 近似。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> false);   // tengu_passport_quail 关闭
        agent.setUserTypeIsAnt(() -> true);     // USER_TYPE==='ant'
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(messages, null);
        agent.executeExtractMemories(messages, null);

        awaitUntil(() -> telemetry.events.contains("tengu_extract_memories_gate_disabled"), 2000);
        assertThat(telemetry.events.stream()
            .filter("tengu_extract_memories_gate_disabled"::equals).count())
            .as("gate_disabled 必须一次性（hasLoggedGateFailure 防重复）").isEqualTo(1);
        assertThat(query.called()).as("gate 关闭必须不启动 fork").isFalse();
    }

    @Test
    @DisplayName("[IMP-CM-19] remote mode → executeExtractMemoriesImpl 跳过，不启动 fork（CC extractMemories.ts:549-552）")
    void executeExtractMemories_skippedInRemoteMode() {
        // WHY: CC extractMemories.ts:549-552 —— gate 链中 isAutoMemoryEnabled（:545）之后、
        //   inProgress stash（:557-564）之前的 remote mode 跳过（getIsRemoteMode()，bootstrap/state.ts:1631）。
        //   Java 等价 nexusai.memory.remote-mode 配置（MemoryRemoteModeConfig，默认 false 对齐
        //   CC state.ts:390）。此处经 setRemoteMode seam 确定性开启，断言 fork 不被启动。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        agent.setRemoteMode(() -> true);    // remote mode 开启

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(messages, null);
        agent.drainPendingExtraction(5000);

        assertThat(query.called()).as("remote mode 下不启动 fork（extractMemories.ts:549-552 跳过）").isFalse();
        assertThat(agent.lastForkParams()).as("remote mode 下不构造 fork 参数").isNull();
    }

    @Test
    @DisplayName("[IMP-E-2 OPD-CM5-E-04] agentId 非空 → impl 入口防御性跳过，不启动 fork（CC extractMemories.ts:531-533）")
    void executeExtractMemories_agentIdDefense_skipsInImpl() {
        // WHY: CC extractMemories.ts:531-533 —— executeExtractMemoriesImpl 入口首个检查
        //   `if (context.toolUseContext.agentId) return`：双层防御第二层（调用点 stopHooks.ts:143
        //   `!toolUseContext.agentId` 是第一层）。若未来有非 StopHookPipeline 调用方直接调
        //   executeExtractMemories 误传子代理 agentId，子代理片段会被当主会话记忆写入 —— 本
        //   回补在 impl 内拦截（决策 E-04 / 探查 R3）。测试经 4 参入口透传非空 agentId，断言
        //   不构造 fork 参数、不推进游标、不发任何提取 telemetry。
        MemoryStorage storage = new MemoryStorage(tempDir);
        ExtractMemoriesAgent agent = new ExtractMemoriesAgent(storage);
        RecordingQuery query = new RecordingQuery();
        agent.setForkedQuery(query);
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        agent.setTelemetry(telemetry);

        List<ChatMessageDto> messages = List.of(userMsg("m1", "a"), userMsg("m2", "b"));
        agent.executeExtractMemories(messages, null, null, "subagent-1");
        agent.drainPendingExtraction(5000);

        assertThat(query.called()).as("agentId 非空 → impl 入口跳过，不启动 fork（CC :531-533）").isFalse();
        assertThat(agent.lastForkParams()).as("agentId 非空 → 不构造 fork 参数").isNull();
        assertThat(agent.getLastMemoryMessageUuid()).as("agentId 非空 → 不推进游标（return 在 runExtraction 之前）").isNull();
        assertThat(telemetry.events).as("agentId 非空 → 不发提取 telemetry（gate 链首个检查即 return）").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 轮询等待条件满足（跨线程 runAsync 事件断言用）。 */
    private static void awaitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        org.assertj.core.api.Assertions.fail("awaitUntil 超时（" + timeoutMs + "ms）");
    }

    /**
     * FIX-EX: extract(List) 同步空壳已删（Java 自有、非 CC 接口，CC extractMemories.ts 只有异步
     * executeExtractMemories）—— 测试迁生产主路径 executeExtractMemories（fire-and-forget +
     * inFlight 登记）+ drainPendingExtraction（[rev2 EX-01] 测试用显式 drain 同步等待确定性；
     * 生产每轮退出不阻塞，drain 仅 headless 类退出路径 = @PreDestroy shutdown）。显式强制开
     * extractionGate（env 覆盖 = 强制开）+ setAutoMemoryEnabled 恒 true 保证确定性（防宿主
     * settings.json/DB 列干扰）。[sm-cursor-sessionize] 2 参入口 sessionId=null → "unknown" 键，
     * 观察点 no-arg accessor 读同一键。
     */
    private static void runExtractSync(ExtractMemoriesAgent agent, List<ChatMessageDto> messages) {
        agent.setExtractionGate(() -> true);
        agent.setAutoMemoryEnabled(() -> true);
        agent.executeExtractMemories(messages, null);
        agent.drainPendingExtraction(5000);
    }

    private static ChatMessageDto userMsg(String id, String content) {
        return new ChatMessageDto(id, null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto assistantMsg(String id, String content) {
        return new ChatMessageDto(id, null, Role.assistant, "assistant", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 主 agent 的 Write tool_use 消息（写 auto-memory 文件）。 */
    private static ChatMessageDto assistantWriteMsg(String id, String filePath) {
        // Windows 路径含反斜杠 → JSON 必须转义（否则 \\U 是非法 JSON escape，getWrittenFilePath 解析失败）
        String jsonPath = filePath.replace("\\", "\\\\");
        ToolCallDto tc = new ToolCallDto("tc-" + id, "Write",
            "{\"file_path\": \"" + jsonPath + "\", \"content\": \"x\"}", null, null);
        return new ChatMessageDto(id, null, Role.assistant, "assistant", "", null,
            List.of(tc), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static Tool tool(String name) {
        Tool t = Mockito.mock(Tool.class);
        Mockito.when(t.name()).thenReturn(name);
        return t;
    }

    private static ToolUseContext ctx() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    private static com.fasterxml.jackson.databind.JsonNode json(Map<String, Object> values) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(values);
    }

    /** 记录最后一次 fork 调用参数的 fake ForkedQuery。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        private RunForkedAgent.ForkQueryParams last;
        private int calls;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.last = params;
            this.calls++;
            return new ForkedAgentResult(params.messages(), ForkedAgentResult.ForkUsage.empty());
        }

        RunForkedAgent.ForkQueryParams lastParams() {
            return last;
        }

        boolean called() {
            return calls > 0;
        }

        /** [rev2 EX-02] 精确调用次数（节流 env 通道逐轮断言用）。 */
        int calls() {
            return calls;
        }
    }

    /** 抛异常的 fake ForkedQuery（模拟 fork 失败）。 */
    static class FailingQuery implements RunForkedAgent.ForkedQuery {
        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            throw new RuntimeException("fork failed");
        }
    }

    /**
     * 可阻塞的 fake ForkedQuery（IMP-M-P0-3b trailing/drain 测试用）· 首调阻塞在 release
     * latch 上模拟慢 fork，让后续 executeExtractMemories 走 stash 路径 / drain 阻塞。
     */
    static class LatchedQuery implements RunForkedAgent.ForkedQuery {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int calls;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            calls++;
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ForkedAgentResult(params.messages(), ForkedAgentResult.ForkUsage.empty());
        }

        boolean awaitStarted(long ms) throws InterruptedException {
            return started.await(ms, TimeUnit.MILLISECONDS);
        }

        void release() {
            release.countDown();
        }

        int calls() {
            return calls;
        }
    }

    /** 返回带 Write tool_use 消息的 fake ForkedQuery（memory_saved 测试用）。 */
    static class MemoryWriteQuery implements RunForkedAgent.ForkedQuery {
        private final List<ChatMessageDto> resultMessages;

        MemoryWriteQuery(String memoryFilePath) {
            this.resultMessages = List.of(assistantWriteMsg("fw-1", memoryFilePath));
        }

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            return new ForkedAgentResult(resultMessages, ForkedAgentResult.ForkUsage.empty());
        }
    }

    /**
     * 记录发射事件名的 Telemetry 假实现（参照 SessionFileAccessHooksTest.RecordingTelemetry 模式）。
     * events 用 CopyOnWriteArrayList —— IMP-M-P0-3b 跨线程测试（executeExtractMemories 经
     * runAsync 在独立线程发射 telemetry）需要并发安全读。
     */
    static final class RecordingTelemetry extends com.nexusai.application.agent.telemetry.Telemetry {
        final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            events.add(eventName);
            super.recordEvent(eventName, attributes);
        }
    }
}

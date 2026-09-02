package com.nexusai.application.agent.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.mcp.McpServerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R32-b12 · B7 OTel/Statsig analytics 埋点 · 工具函数 + 决策归因单元测试.
 *
 * <p><b>WHY (意图验证)</b>: b12 brief 对齐 CC Open-ClaudeCode/src/services/tools/toolExecution.ts
 * 8 个埋点 + 4 个工具函数. 本测试验证 4 个工具函数的 Java 端实现严格对齐 CC 真源:
 *
 * <ul>
 *   <li><b>ToolInputTruncator</b>: 截断字符串/数组/对象深度, 对齐 CC metadata.ts:240-303 (extractToolInputForTelemetry)</li>
 *   <li><b>FileExtensionExtractor</b>: 提取文件扩展名 + bash 命令解析, 对齐 CC metadata.ts:311-360</li>
 *   <li><b>McpServerScope</b>: 解析 MCP server scope, 对齐 CC mcp/utils.ts:413-436</li>
 *   <li><b>ToolDecisionInfo</b>: 不可变 record + 决策字符串校验, 对齐 CC toolExecution.ts:1741-1743</li>
 *   <li><b>Telemetry 4 工具函数</b>: isToolDetailsLoggingEnabled / extractToolInputForTelemetry /
 *       incrementCodeEditCounter / toolResultSizeBytes — 严格对齐 CC 行为</li>
 *   <li><b>PermissionDecisionReason.decisionReasonToOTelSource</b>: 11 case switch, 对齐 CC toolExecution.ts:207-250</li>
 * </ul>
 *
 * <p>本测试不验证 OTel SDK 调用（那是 telemetry 集成的运行时行为，本测试覆盖纯逻辑函数）.
 * 端到端 OTel 事件验证见 R32B12_IntegrationTest（不在 b12 范围内，R33+ 添加）.
 */
class R32B12_TelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private Telemetry telemetry;
    private ToolTelemetryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolTelemetryProperties();
        properties.setEnabled(false); // 关闭 OTel SDK, 仅测试工具函数逻辑
        properties.setLogToolDetails(false); // 默认关闭, 验证 extractToolInputForTelemetry 开关行为
        telemetry = new Telemetry(properties);
        telemetry.init(); // 显式 init (即使 disabled 也保证状态确定)
    }

    // ═══════════════════ ToolInputTruncator (D-6 P1) ═══════════════════

    @Test
    @DisplayName("D-6 P1: ToolInputTruncator 短字符串不变 (depth=0, length<512)")
    void truncatorKeepsShortString() {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        JsonNode out = ToolInputTruncator.truncate(input);
        assertThat(out.get("command").asText()).isEqualTo("ls");
    }

    @Test
    @DisplayName("D-6 P1: ToolInputTruncator 长字符串截断 (>512 → 前128 + …[N chars])")
    void truncatorTruncatesLongString() {
        ObjectNode input = JSON.createObjectNode();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append('x');
        input.put("data", sb.toString());
        JsonNode out = ToolInputTruncator.truncate(input);
        String truncated = out.get("data").asText();
        assertThat(truncated).startsWith("x".repeat(128));
        assertThat(truncated).contains("…[600 chars]");
    }

    @Test
    @DisplayName("D-6 P1: ToolInputTruncator 数组 > 20 项截断 + 提示")
    void truncatorLimitsArraySize() {
        ObjectNode input = JSON.createObjectNode();
        var arr = JSON.createArrayNode();
        for (int i = 0; i < 25; i++) arr.add(i);
        input.set("items", arr);
        JsonNode out = ToolInputTruncator.truncate(input);
        assertThat(out.get("items").size()).isEqualTo(21); // 20 + "…[25 items]"
        assertThat(out.get("items").get(20).asText()).contains("…[25 items]");
    }

    @Test
    @DisplayName("D-6 P1: ToolInputTruncator 对象嵌套深度 > 2 → <nested>")
    void truncatorLimitsNestingDepth() {
        // MAX_DEPTH=2: depth=0 (root), depth=1 (child), depth=2 (grandchild)
        // depth=2 时整个子对象变成 "<nested>"
        ObjectNode level0 = JSON.createObjectNode();
        ObjectNode level1 = JSON.createObjectNode();
        ObjectNode level2 = JSON.createObjectNode();
        level2.put("deep", "value"); // level2 上的字段会在 truncate 时被丢弃
        level1.set("l1", level2);
        level0.set("l0", level1);
        JsonNode out = ToolInputTruncator.truncate(level0);
        // 顶层 depth=0: level0 是 object, 进入对象处理
        //   l0 字段: 递归 level1, depth=1, 进入对象处理
        //     l1 字段: 递归 level2, depth=2, 2>=MAX_DEPTH → "<nested>"
        JsonNode level1Out = out.get("l0");
        JsonNode level2Out = level1Out.get("l1");
        assertThat(level2Out.isTextual()).isTrue();
        assertThat(level2Out.asText()).isEqualTo("<nested>");
    }

    @Test
    @DisplayName("D-6 P1: ToolInputTruncator 跳过以 _ 开头的内部 marker key")
    void truncatorSkipsInternalMarkerKeys() {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        input.put("_simulatedSedEdit", "internal");
        input.put("_internal", "marker");
        JsonNode out = ToolInputTruncator.truncate(input);
        assertThat(out.has("command")).isTrue();
        assertThat(out.has("_simulatedSedEdit")).isFalse();
        assertThat(out.has("_internal")).isFalse();
    }

    @Test
    @DisplayName("D-6 P1: Telemetry.extractToolInputForTelemetry 开关=false 返回 null")
    void extractToolInputReturnsNullWhenDisabled() {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        String result = telemetry.extractToolInputForTelemetry(input);
        assertThat(result).isNull(); // OTEL_LOG_TOOL_DETAILS=false 默认
    }

    @Test
    @DisplayName("D-6 P1: Telemetry.extractToolInputForTelemetry 开关=true 返回 JSON")
    void extractToolInputReturnsJsonWhenEnabled() {
        properties.setLogToolDetails(true);
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        String result = telemetry.extractToolInputForTelemetry(input);
        assertThat(result).isNotNull();
        assertThat(result).contains("\"command\"");
        assertThat(result).contains("\"ls\"");
    }

    @Test
    @DisplayName("D-6 P1: Telemetry.extractToolInputForTelemetry null 输入 → null")
    void extractToolInputNullInput() {
        properties.setLogToolDetails(true);
        assertThat(telemetry.extractToolInputForTelemetry(null)).isNull();
    }

    // ═══════════════════ FileExtensionExtractor (D-9 P1) ═══════════════════

    @Test
    @DisplayName("D-9 P1: getFileExtensionForAnalytics 标准扩展名 (.java → java)")
    void fileExtStandard() {
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("/path/to/Foo.java"))
            .isEqualTo("java");
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionForAnalytics 无扩展名 → null")
    void fileExtNoExtension() {
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("/path/to/Makefile")).isNull();
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionForAnalytics 大写扩展名 → 小写")
    void fileExtUpperCase() {
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("README.TXT")).isEqualTo("txt");
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionForAnalytics 扩展名 > 10 字符 → 'other' (hash 防护)")
    void fileExtLongExtension() {
        // 11 字符扩展名 (e.g., abcdefghijk)
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("file.abcdefghijk"))
            .isEqualTo("other");
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionForAnalytics null/blank → null")
    void fileExtNullBlank() {
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics(null)).isNull();
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("")).isNull();
        assertThat(FileExtensionExtractor.getFileExtensionForAnalytics("   ")).isNull();
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionsFromBashCommand 白名单命令提取")
    void bashCmdWhitelisted() {
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("cat README.md"))
            .isEqualTo("md");
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("rm -rf build/output.txt"))
            .isEqualTo("txt");
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("/usr/bin/cp file.json dest/"))
            .isEqualTo("json");
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionsFromBashCommand 非白名单命令 → null")
    void bashCmdNonWhitelisted() {
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("curl https://example.com/api"))
            .isNull();
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("npm install")).isNull();
    }

    @Test
    @DisplayName("D-9 P1: getFileExtensionsFromBashCommand 优先 simulatedFilePath")
    void bashCmdSimulatedPathPreferred() {
        String result = FileExtensionExtractor.getFileExtensionsFromBashCommand(
            "sed -i 's/foo/bar/' other.txt", "/tmp/sed_file.py");
        assertThat(result).isEqualTo("py"); // simulatedFilePath 优先
    }

    // ═══════════════════ McpServerScope (D-10 P1) ═══════════════════

    @Test
    @DisplayName("D-10 P1: 非 MCP 工具 → null")
    void mcpScopeNonMcpTool() {
        // [IMP-E1 DC-2] 签名收敛 (toolName, tool)：McpServerInfo 不再承载 scope。
        assertThat(McpServerScope.getMcpServerScopeFromToolName("Read", null)).isNull();
        assertThat(McpServerScope.getMcpServerScopeFromToolName(null, null)).isNull();
    }

    @Test
    @DisplayName("D-10 P1: MCP 工具非 claude_ai_ 前缀 → null（scope 不承载于 mcpInfo）")
    void mcpScopeNoConfigSource_returnsNull() {
        // [IMP-E1 DC-2] CC scope 经配置 getMcpConfigByName 派生；McpServerInfo 收敛 2 字段后
        // 本静态层无配置源 → 非 claude_ai 前缀返回 null（与既有生产行为一致：OTel mcp_server_scope 不发射）。
        assertThat(McpServerScope.getMcpServerScopeFromToolName(
            "mcp__filesystem__read_file", null)).isNull();
    }

    @Test
    @DisplayName("D-10 P1: MCP 工具 serverName 以 claude_ai_ 开头 → 'claudeai' fallback")
    void mcpScopeClaudeAiFallback() {
        // claude_ai_* serverName → fallback "claudeai"（CC utils.ts:428-430）
        assertThat(McpServerScope.getMcpServerScopeFromToolName(
            "mcp__claude_ai_search__search", null)).isEqualTo("claudeai");
    }

    @Test
    @DisplayName("D-10 P1: MCP 工具非 claude_ai_ 前缀 → null")
    void mcpScopeNotFoundNull() {
        assertThat(McpServerScope.getMcpServerScopeFromToolName(
            "mcp__unknown__tool", null)).isNull();
    }

    // ═══════════════════ ToolDecisionInfo (D-4 P0) ═══════════════════

    @Test
    @DisplayName("D-4 P0: ToolDecisionInfo 合法 accept/reject")
    void toolDecisionInfoValid() {
        assertThat(new ToolDecisionInfo("rule", "accept")).isNotNull();
        assertThat(new ToolDecisionInfo("user_reject", "reject")).isNotNull();
    }

    @Test
    @DisplayName("D-4 P0: ToolDecisionInfo 非法 decision 抛 IllegalArgumentException")
    void toolDecisionInfoInvalidDecision() {
        assertThatThrownBy(() -> new ToolDecisionInfo("rule", "unknown"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDecisionInfo("rule", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDecisionInfo("rule", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D-4 P0: ToolDecisionInfo 非法 source 抛 IllegalArgumentException")
    void toolDecisionInfoInvalidSource() {
        assertThatThrownBy(() -> new ToolDecisionInfo(null, "accept"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDecisionInfo("", "accept"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDecisionInfo("   ", "accept"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ═══════════════════ PermissionDecisionReason.decisionReasonToOTelSource (D-3) ═══════════════════

    @Test
    @DisplayName("D-3: null reason → 'config' (CC toolExecution.ts:212-213)")
    void decisionSourceNull() {
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(null,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("config");
    }

    @Test
    @DisplayName("D-3: rule → CC 词汇映射 (CC line 228-229)")
    void decisionSourceRule() {
        var ruleValue = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test-pattern");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.PROJECT_SETTINGS,
            com.nexusai.application.agent.permission.PermissionBehavior.ALLOW,
            ruleValue);
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("config");
    }

    @Test
    @DisplayName("D-3: hook → 'hook' (CC line 231)")
    void decisionSourceHook() {
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.Hook(
            "PreToolUse:Read", "user", null);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.DENY))
            .isEqualTo("hook");
    }

    @Test
    @DisplayName("D-3: permissionPromptTool 已知 classification → 原值 (CC line 218-222)")
    void decisionSourcePromptToolClassified() {
        Map<String, Object> toolResult = Map.of("decisionClassification", "user_permanent");
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.PermissionPromptTool(
            "permission_prompt_tool", toolResult);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("user_permanent");
    }

    @Test
    @DisplayName("D-3: permissionPromptTool 未知 classification + allow → 'user_temporary' fallback")
    void decisionSourcePromptToolAllowFallback() {
        Map<String, Object> toolResult = Map.of("decisionClassification", "unknown");
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.PermissionPromptTool(
            "permission_prompt_tool", toolResult);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("user_temporary");
    }

    @Test
    @DisplayName("D-3: permissionPromptTool 未知 classification + deny → 'user_reject' fallback")
    void decisionSourcePromptToolDenyFallback() {
        Map<String, Object> toolResult = Map.of("decisionClassification", "unknown");
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.PermissionPromptTool(
            "permission_prompt_tool", toolResult);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.DENY))
            .isEqualTo("user_reject");
    }

    @Test
    @DisplayName("D-3: mode → 'config' (CC line 234 default case)")
    void decisionSourceModeFallback() {
        var reason = new com.nexusai.application.agent.permission.PermissionDecisionReason.Mode(
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(reason,
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("config");
    }

    // ═══════════════════ Telemetry 4 工具函数 (D-6/D-7/D-8/D-9/D-10) ═══════════════════

    @Test
    @DisplayName("D-7: isToolDetailsLoggingEnabled 默认 false (CC OTEL_LOG_TOOL_DETAILS 默认关闭)")
    void telemetryLogToolDetailsDefaultFalse() {
        assertThat(telemetry.isToolDetailsLoggingEnabled()).isFalse();
    }

    @Test
    @DisplayName("D-7: isToolDetailsLoggingEnabled 开启后 → true")
    void telemetryLogToolDetailsTrue() {
        properties.setLogToolDetails(true);
        Telemetry t = new Telemetry(properties);
        assertThat(t.isToolDetailsLoggingEnabled()).isTrue();
    }

    @Test
    @DisplayName("D-8: incrementCodeEditCounter 累加同 tool+decision")
    void codeEditCounterIncrement() {
        telemetry.incrementCodeEditCounter("Edit", "accept", "rule", "accept");
        telemetry.incrementCodeEditCounter("Edit", "accept", "rule", "accept");
        // 内部计数器已 +2, 此处仅验证不抛异常 (具体计数器值非公开 API).
        // 间接验证: logOTelEvent 不抛异常 (依赖 codeEditCounters 状态).
        telemetry.logOTelEvent("tool_decision",
            Map.of("decision", "accept", "source", "rule", "tool_name", "Edit"));
    }

    @Test
    @DisplayName("D-10: toolResultSizeBytes UTF-8 字节大小")
    void toolResultSizeBytesBasic() {
        assertThat(telemetry.toolResultSizeBytes("hello")).isEqualTo(5L);
        assertThat(telemetry.toolResultSizeBytes("")).isEqualTo(0L);
        assertThat(telemetry.toolResultSizeBytes(null)).isEqualTo(0L);
        // UTF-8 中文: "你好" → 6 字节 (UTF-8 编码)
        assertThat(telemetry.toolResultSizeBytes("你好")).isEqualTo(6L);
    }

    // ═══════════════════ McpServerInfo 2 字段契约 (IMP-E1 DC-2) ═══════════════════

    @Test
    @DisplayName("D-10: McpServerInfo 仅 {serverName,toolName}（CC mcpInfo 契约）")
    void mcpServerInfo_2fieldContract() {
        // [IMP-E1 DC-2] CC mcpInfo 仅 2 字段（client.ts:1780）；serverUrl/scope/instructions 扩展字段已删。
        McpServerInfo info = new McpServerInfo("filesystem", "read_file");
        assertThat(info.serverName()).isEqualTo("filesystem");
        assertThat(info.toolName()).isEqualTo("read_file");
    }

    @Test
    @DisplayName("D-10: McpServerInfo 构造器空白校验（TR-E1-DC-3 保留）")
    void mcpServerInfo_blankRejected() {
        // TR-E1-DC-3：构造器防御校验（serverName/toolName 非空白）保留（防御性基础设施）。
        assertThatThrownBy(() -> new McpServerInfo("", "tool")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpServerInfo("server", "")).isInstanceOf(IllegalArgumentException.class);
    }

    // ═══════════════════ Telemetry.recordToolDecision (D-4 静态 helper) ═══════════════════

    @Test
    @DisplayName("D-4: Telemetry.recordToolDecision 写入 decisions map")
    void recordToolDecisionWrites() {
        Map<String, ToolDecisionInfo> map = new HashMap<>();
        Telemetry.recordToolDecision(map, "call_1", new ToolDecisionInfo("rule", "accept"));
        assertThat(map).hasSize(1);
        assertThat(map.get("call_1").decision()).isEqualTo("accept");
        assertThat(map.get("call_1").source()).isEqualTo("rule");
    }

    @Test
    @DisplayName("D-4: Telemetry.recordToolDecision null 参数静默跳过")
    void recordToolDecisionNullSafe() {
        Map<String, ToolDecisionInfo> map = new HashMap<>();
        Telemetry.recordToolDecision(null, "call_1", new ToolDecisionInfo("rule", "accept"));
        assertThat(map).isEmpty();
        Telemetry.recordToolDecision(map, null, new ToolDecisionInfo("rule", "accept"));
        assertThat(map).isEmpty();
        Telemetry.recordToolDecision(map, "call_1", null);
        assertThat(map).isEmpty();
    }

    // ═══════════════════ logOTelEvent (D-6 通用入口) ═══════════════════

    @Test
    @DisplayName("D-6: Telemetry.logOTelEvent disabled 时不抛异常, 仅 in-memory counter")
    void logOTelEventDisabledSafe() {
        long before = telemetry.totalEvents();
        telemetry.logOTelEvent("test_event", Map.of("key", "value"));
        assertThat(telemetry.totalEvents()).isEqualTo(before);
    }

    @Test
    @DisplayName("D-6: Telemetry.logOTelEvent 接收 Map<String, ?> 类型")
    void logOTelEventWildcardMap() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tool", "Read");
        attrs.put("count", 42);
        attrs.put("success", true);
        long before = telemetry.totalEvents();
        telemetry.logOTelEvent("test_event", attrs);
        assertThat(telemetry.totalEvents()).isEqualTo(before);
    }

    @Test
    @DisplayName("D-6: Telemetry.logOTelEvent null eventName 静默跳过")
    void logOTelEventNullNameSafe() {
        long before = telemetry.totalEvents();
        telemetry.logOTelEvent(null, Map.of("key", "value"));
        telemetry.logOTelEvent("", Map.of("key", "value"));
        assertThat(telemetry.totalEvents()).isEqualTo(before); // 不递增
    }

    @Test
    @DisplayName("P1-5: ToolInputTruncator 五项常量严格对齐 CC 512/128/4096/20/2")
    void truncatorConstantsMatchCc() {
        assertThat(ToolInputTruncator.STRING_TRUNCATE_AT).isEqualTo(512);
        assertThat(ToolInputTruncator.STRING_TRUNCATE_TO).isEqualTo(128);
        assertThat(ToolInputTruncator.MAX_JSON_CHARS).isEqualTo(4096);
        assertThat(ToolInputTruncator.MAX_COLLECTION_ITEMS).isEqualTo(20);
        assertThat(ToolInputTruncator.MAX_DEPTH).isEqualTo(2);
    }

    @Test
    @DisplayName("P2-1: Bash 文件命令不接受 CC 未列出的 less/more")
    void bashCommandsExcludeLessAndMore() {
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("less README.md")).isNull();
        assertThat(FileExtensionExtractor.getFileExtensionsFromBashCommand("more README.md")).isNull();
    }

    @Test
    @DisplayName("P1-4: session rule 映射为 CC user_temporary 词汇")
    void decisionSourceSessionRuleUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.SESSION,
            com.nexusai.application.agent.permission.PermissionBehavior.ALLOW, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("user_temporary");
    }

    @Test
    @DisplayName("Fix E-补: SESSION + DENY → user_reject (CC toolExecution.ts:181-194)")
    void decisionSourceSessionDenyUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.SESSION,
            com.nexusai.application.agent.permission.PermissionBehavior.DENY, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.DENY))
            .isEqualTo("user_reject");
    }

    @Test
    @DisplayName("Fix E-补: USER_SETTINGS + ALLOW → user_permanent (CC toolExecution.ts:181-194)")
    void decisionSourceUserSettingsAllowUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS,
            com.nexusai.application.agent.permission.PermissionBehavior.ALLOW, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("user_permanent");
    }

    @Test
    @DisplayName("Fix E-补: USER_SETTINGS + DENY → user_reject (CC toolExecution.ts:181-194)")
    void decisionSourceUserSettingsDenyUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.USER_SETTINGS,
            com.nexusai.application.agent.permission.PermissionBehavior.DENY, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.DENY))
            .isEqualTo("user_reject");
    }

    @Test
    @DisplayName("Fix E-补: LOCAL_SETTINGS + ALLOW → user_permanent (CC toolExecution.ts:181-194)")
    void decisionSourceLocalSettingsAllowUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.LOCAL_SETTINGS,
            com.nexusai.application.agent.permission.PermissionBehavior.ALLOW, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.ALLOW))
            .isEqualTo("user_permanent");
    }

    @Test
    @DisplayName("Fix E-补: LOCAL_SETTINGS + DENY → user_reject (CC toolExecution.ts:181-194)")
    void decisionSourceLocalSettingsDenyUsesCcVocabulary() {
        var value = com.nexusai.application.agent.permission.PermissionRuleValue.withContent("Bash", "test");
        var rule = new com.nexusai.application.agent.permission.PermissionRule(
            com.nexusai.application.agent.permission.PermissionRuleSource.LOCAL_SETTINGS,
            com.nexusai.application.agent.permission.PermissionBehavior.DENY, value);
        assertThat(com.nexusai.application.agent.permission.PermissionDecisionReason
            .decisionReasonToOTelSource(
                new com.nexusai.application.agent.permission.PermissionDecisionReason.Rule(rule),
                com.nexusai.application.agent.permission.PermissionBehavior.DENY))
            .isEqualTo("user_reject");
    }

    @Test
    @DisplayName("P1-7: recordEvent 与 logOTelEvent 职责分离，组合调用只计数一次")
    void recordAndOtelCombinedCountExactlyOnce() {
        long before = telemetry.totalEvents();
        telemetry.recordEvent("combined", Map.of());
        telemetry.logOTelEvent("combined", Map.of());
        assertThat(telemetry.totalEvents()).isEqualTo(before + 1);
        assertThat(telemetry.getCounter("combined")).isEqualTo(1);
    }

    @Test
    @DisplayName("P1-6: ToolUseContext 保留 15 参数构造器且 toolDecisions 默认空")
    void toolUseContextFifteenArgConstructorCompatible() {
        var ctx = new com.nexusai.application.agent.tool.ToolUseContext(
            java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(), null,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), false, "", java.nio.file.Path.of("."), null);
        assertThat(ctx.toolDecisions()).isEmpty();
    }

    @Test
    @DisplayName("P0: 主应用显式注册 ToolTelemetryProperties")
    void applicationRegistersTelemetryProperties() {
        var annotation = com.nexusai.NexusAiApplication.class.getAnnotation(
            org.springframework.boot.context.properties.EnableConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(ToolTelemetryProperties.class);
    }

    @Test
    @DisplayName("P1-5: 512 字符边界不截断，513 字符触发截断")
    void truncatorHonorsCcStringBoundary() {
        ObjectNode input = JSON.createObjectNode();
        input.put("value", "x".repeat(512));
        assertThat(ToolInputTruncator.truncate(input).get("value").asText()).hasSize(512);
        input.put("value", "x".repeat(513));
        assertThat(ToolInputTruncator.truncate(input).get("value").asText())
            .startsWith("x".repeat(128)).contains("…[513 chars]");
    }

    @Test
    @DisplayName("P1-7: 单独 recordEvent 仍保留 in-memory 兼容计数")
    void recordEventStillCountsInMemory() {
        long before = telemetry.totalEvents();
        telemetry.recordEvent("legacy_event", Map.of("key", "value"));
        assertThat(telemetry.totalEvents()).isEqualTo(before + 1);
        assertThat(telemetry.getCounter("legacy_event")).isEqualTo(1);
    }

    @Test
    @DisplayName("P1-1: PreToolUse 入口记录纳秒时间且结束清理")
    void preToolHookDurationLifecycleIsWired() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // [R32-b12 Fix-v3 P1-2] 验证新的 hook 计时实现: hook 入口 startNs + 出口立即计算
        //   durationMs 写入 t.preToolHookDurationMs 字段. 旧实现 preToolHookStartTimes map
        //   + map.remove() 在 emit 时延迟计算的方式已废弃.
        assertThat(source).contains("long preToolHookStartNs = System.nanoTime();");
        assertThat(source).contains("TimeUnit.NANOSECONDS.toMillis");
        assertThat(source).contains("t.preToolHookDurationMs = preToolHookDurationMs;");
    }

    @Test
    @DisplayName("P1-2: success 埋点显式跳过 error ToolResult")
    void successTelemetrySkipsErrorResults() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // [IMP-C2 适配] ToolResult.isError() 已删（4 字段契约，isError 由执行器推导）——
        //   emitSuccessTelemetry 现以 t.isError 字段早返（StreamingToolExecutor.java:3862）。
        assertThat(source).contains("if (result == null || t.isError) return;");
    }

    @Test
    @DisplayName("P1-3 [A1 撤外层迁移]: Allow/Deny 决策 telemetry 已搬到 StreamingToolExecutor.injectDecisionInfo")
    void allPermissionDecisionPathsEmitTelemetry() throws Exception {
        // [A1 撤外层] 8 处 emitDecisionTelemetry 全部从 LlmAgentLoop.applyPermissionFilter
        //   搬到 StreamingToolExecutor.injectDecisionInfo (executeAsync 内 gate check 之后).
        //   LlmAgentLoop.java 不应再有 emitDecisionTelemetry 引用.
        String llmSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        assertThat(llmSource)
            .as("applyPermissionFilter 已删除,LlmAgentLoop 不应再有 emitDecisionTelemetry 引用")
            .doesNotContain("emitDecisionTelemetry");
        assertThat(llmSource).doesNotContain("applyPermissionFilter");
        assertThat(llmSource).doesNotContain("PermissionFilterResult");
        // 决策 telemetry 现在在 StreamingToolExecutor.injectDecisionInfo 内部 emit
        String execSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        assertThat(execSource).contains("injectDecisionInfo");
        assertThat(execSource).contains("tengu_tool_use_can_use_tool_allowed");
        assertThat(execSource).contains("tengu_tool_use_can_use_tool_rejected");
        assertThat(execSource).contains("logOTelEvent(\"tool_decision\"");
    }

    // ═══════════════════ [R32-b12 Fix-v3 P1] 5 项 P1 阻塞缺陷修复验证 ═══════════════════

    /**
     * [R32-b12 Fix-v3 P1-1] StreamingToolExecutor telemetry 注入修复:
     *   之前 @Autowired(required=false) 让 Spring 自动注入, 但 LlmAgentLoop 手动
     *   {@code new StreamingToolExecutor(...)} 绕过 Spring 注入 → telemetry 字段
     *   null → 所有 8 埋点被短路. 现在改为 setTelemetry setter, LlmAgentLoop manual
     *   new 后显式调 setTelemetry(this.telemetry). 验证 setTelemetry + getTelemetry.
     */
    @Test
    @DisplayName("Fix-v3 P1-1: StreamingToolExecutor.setTelemetry 注入 telemetry bean (非 null)")
    void streamingToolExecutorSetTelemetryInjectsBean() throws Exception {
        // 1. 验证 StreamingToolExecutor 有 setTelemetry + getTelemetry 方法
        java.lang.reflect.Method setTelemetryMethod = StreamingToolExecutor.class
            .getDeclaredMethod("setTelemetry", Telemetry.class);
        java.lang.reflect.Method getTelemetryMethod = StreamingToolExecutor.class
            .getDeclaredMethod("getTelemetry");
        assertThat(setTelemetryMethod).isNotNull();
        assertThat(getTelemetryMethod).isNotNull();
        // 2. [R32-b15 Stage 2 C4] telemetry 注入统一到 buildStreamingExecutor 工厂 ·
        //   [H7-arch Phase 5-2 P3-⑤] 工厂已 static 化至 AgentLoopContext；loop 经
        //   AgentLoopContext.buildStreamingExecutor(...) 调用（替代原 inline streamingExec.setTelemetry）。
        String ctxSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/loop/AgentLoopContext.java"));
        assertThat(ctxSource).contains("buildStreamingExecutor");
        // buildStreamingExecutor 内含 exec.setTelemetry(beans.telemetry()) 调用
        assertThat(ctxSource).contains("exec.setTelemetry(beans.telemetry())");
        // 3. 验证 @Autowired(required=false) 已从 StreamingToolExecutor 移除
        // (移除避免误导: 无 setter 也不会自动注入)
        String execSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        assertThat(execSource).doesNotContain("@Autowired(required = false)");
    }

    /**
     * [R32-b12 Fix-v3 P1-1] StreamingToolExecutor 不使用 @Autowired(required=false) on telemetry 字段:
     *   移除误导性 @Autowired 注解. telemetry 字段保持非 final (允许 setter 注入).
     */
    @Test
    @DisplayName("Fix-v3 P1-1: telemetry 字段移除 @Autowired 注解 (避免误导, 改用 setter)")
    void streamingToolExecutorTelemetryFieldHasNoAutowiredAnnotation() throws Exception {
        java.lang.reflect.Field telemetryField = StreamingToolExecutor.class
            .getDeclaredField("telemetry");
        org.springframework.beans.factory.annotation.Autowired autowiredAnnotation =
            telemetryField.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class);
        assertThat(autowiredAnnotation)
            .as("telemetry 字段不应有 @Autowired 注解 (改用 setter 注入)")
            .isNull();
    }

    /**
     * [R32-b12 Fix-v3 P1-2] preToolHookDurationMs 仅含 hook 时长:
     *   之前 preToolHookStartTimes map + map.remove() 在 emit 时计算 (now - startNs)
     *   包含后续 permission gate + tool.execute + emit 间隔时间. 现在 hook 入口 startNs +
     *   hook 出口立即计算 preToolHookDurationMs 写入 t.preToolHookDurationMs 字段.
     *   工具执行时长另用 t.toolDurationMs 字段 (与 CC durationMs 区分).
     */
    @Test
    @DisplayName("Fix-v3 P1-2: StreamingToolExecutor emitSuccessTelemetry 使用 t.preToolHookDurationMs 字段 (hook-only)")
    void preToolHookDurationIsHookOnly() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // 1. 验证 emitSuccessTelemetry 用 t.preToolHookDurationMs (非 map.remove)
        assertThat(source).contains("long preToolHookDurationMs = t.preToolHookDurationMs;");
        assertThat(source).contains("long toolDurationMsOnly = t.toolDurationMs;");
        // 2. 验证 preToolHookStartTimes map 字段已移除 (避免旧路径被误用)
        assertThat(source).doesNotContain("private final java.util.Map<String, Long> preToolHookStartTimes");
        // 3. 验证 hook 出口 finally 块立即计算 durationMs (而非延迟到 emit)
        assertThat(source).contains("preToolHookDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis");
        assertThat(source).contains("t.preToolHookDurationMs = preToolHookDurationMs;");
        // 4. 验证 tool.execute 也用 try/finally 单独计时 (toolDurationMs)
        assertThat(source).contains("long toolExecStartNs = System.nanoTime();");
        assertThat(source).contains("t.toolDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis");
    }

    /**
     * [R32-b12 Fix-v3 P1-3] failure analytics 不依赖 hookRegistry:
     *   之前 `if (hookRegistry != null)` 包裹整个 PostToolUse + failure 分支, hookRegistry=null
     *   时即使错误结果也不发失败事件. 现在 emitPostToolUseFailureAnalytics 仅依赖
     *   t.isError (执行器推导的 4 字段契约错误标志), hookRegistry 是辅助 (有则带 hook 数据, 无则跳过).
     */
    @Test
    @DisplayName("Fix-v3 P1-3: emitPostToolUseFailureAnalytics 仅依赖 t.isError (hookRegistry 是辅助)")
    void failureAnalyticsDependsOnIsErrorNotHookRegistry() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // 1. 验证失败 analytics 调用在 if (t.isError && !t.stoppedByHookExecuted) 分支内 (无条件)
        //    [IMP-C2 适配] ToolResult.isError() 已删 → 执行器以 t.isError 字段推导（StreamingToolExecutor.java:1973）
        assertThat(source).contains("if (t.isError && !t.stoppedByHookExecuted) {");
        assertThat(source).contains("emitPostToolUseFailureAnalytics(t, baseResult, t0);");
        // 2. 验证 hook 块是单独的 if (hookRegistry != null && ctx != null && t.tool != null) 在
        //    错误标志分支之后 (而非嵌套) —— 通过查找两段字符串的相对位置验证
        int emitFailureIndex = source.indexOf("emitPostToolUseFailureAnalytics(t, baseResult, t0);");
        //    在 emit 之后查找最近的 if (hookRegistry != null) 块 (hook 串联)
        int hookBlockAfterEmit = source.indexOf(
            "if (hookRegistry != null && ctx != null && t.tool != null) {",
            emitFailureIndex);
        assertThat(emitFailureIndex).isGreaterThan(0);
        assertThat(hookBlockAfterEmit)
            .as("hook 块应在 emit 之后 (非嵌套), 表示 failure analytics 不依赖 hookRegistry")
            .isGreaterThan(emitFailureIndex);
        // 3. 验证 hook 块内有 try/catch 不包含 emitPostToolUseFailureAnalytics (失败 analytics 已分离)
        //    截取 hook 块函数体, 验证不含 emit 调用
        int hookBlockEnd = source.indexOf("}", hookBlockAfterEmit + 100);
        String hookBlockBody = source.substring(hookBlockAfterEmit, Math.min(hookBlockEnd + 1, hookBlockAfterEmit + 2000));
        assertThat(hookBlockBody)
            .as("hook 块内不应有 emitPostToolUseFailureAnalytics 调用 (failure 已分离到外层)")
            .doesNotContain("emitPostToolUseFailureAnalytics(t, baseResult, t0);");
    }

    /**
     * [P-AL-05 D-1] mcp__server__tool sanitizer 值语义 · 对齐 CC
     * sanitizeToolNameForAnalytics (Open-ClaudeCode/src/services/analytics/metadata.ts:70-77):
     * {@code mcp__} 前缀整体遮蔽为字面量 {@code mcp_tool} —— server alias 可能暴露
     * 用户特定配置 (IP / 路径 / 凭据, PII-medium), 整体遮蔽不保留 alias 主体.
     * 旧 R32-b12 "片段脱敏"实现 (IP/路径/凭据/邮箱 pattern 局部替换) 与 CC 值语义
     * 相悖且其声称对齐的 CC sanitizeMcpServerTool.ts 在基线中不存在, 已整段删除.
     */
    @Test
    @DisplayName("P-AL-05 D-1: McpServerToolSanitizer.sanitize mcp__ 前缀整体遮蔽 → mcp_tool (含 IP)")
    void mcpServerToolSanitizerMapsMcpPrefixToMcpTool() {
        // IP 片段不再局部替换 —— 整个 mcp__ 名称遮蔽为 mcp_tool (CC metadata.ts:73-75)
        String sanitized = McpServerToolSanitizer.sanitize("mcp__192.168.1.100__tool__exec");
        assertThat(sanitized).isEqualTo("mcp_tool");
        assertThat(sanitized).doesNotContain("192.168.1.100");
    }

    /**
     * [P-AL-05 D-1] 含 Unix 路径的 MCP 工具名 → mcp_tool (CC 只看 mcp__ 前缀).
     */
    @Test
    @DisplayName("P-AL-05 D-1: 含 Unix 路径的 MCP 工具名 → mcp_tool")
    void mcpServerToolSanitizerMapsUnixPathNameToMcpTool() {
        String sanitized = McpServerToolSanitizer.sanitize("mcp__server__/home/user/secret/file__tool");
        assertThat(sanitized).isEqualTo("mcp_tool");
        assertThat(sanitized).doesNotContain("/home/user/secret");
    }

    /**
     * [P-AL-05 D-1] 含凭据的 MCP 工具名 → mcp_tool (CC 只看 mcp__ 前缀).
     */
    @Test
    @DisplayName("P-AL-05 D-1: 含凭据关键词的 MCP 工具名 → mcp_tool")
    void mcpServerToolSanitizerMapsCredentialNameToMcpTool() {
        String sanitized = McpServerToolSanitizer.sanitize("mcp__token=abc123secret__tool");
        assertThat(sanitized).isEqualTo("mcp_tool");
        assertThat(sanitized).doesNotContain("abc123secret");
    }

    /**
     * [P-AL-05 D-1] 无敏感 pattern 的 MCP 工具名同样整体遮蔽 → mcp_tool
     * (旧"保留原 alias"断言与 CC 相悖 —— CC 只判 mcp__ 前缀, metadata.ts:73).
     */
    @Test
    @DisplayName("P-AL-05 D-1: 安全 MCP 工具名同样整体遮蔽 → mcp_tool (CC 前缀判定)")
    void mcpServerToolSanitizerMapsSafeMcpNameToMcpTool() {
        assertThat(McpServerToolSanitizer.sanitize("mcp__github__create_issue"))
            .as("mcp__ 前缀 → mcp_tool (CC metadata.ts:73-75), 不保留 alias")
            .isEqualTo("mcp_tool");
    }

    /**
     * [P-AL-05 D-1] 内置工具名 (非 mcp__ 前缀) 原样保留 (CC metadata.ts:76 返回原值).
     */
    @Test
    @DisplayName("P-AL-05 D-1: 内置工具名原样保留 (Bash/Read)")
    void mcpServerToolSanitizerPreservesBuiltInToolName() {
        assertThat(McpServerToolSanitizer.sanitize("Bash")).isEqualTo("Bash");
        assertThat(McpServerToolSanitizer.sanitize("Read")).isEqualTo("Read");
    }

    /**
     * [P-AL-05 D-1] 无 mcp__ 前缀的名称原样返回 (CC 前缀判定, 与是否含 __ 无关).
     */
    @Test
    @DisplayName("P-AL-05 D-1: 无 mcp__ 前缀的名称原样返回")
    void mcpServerToolSanitizerPreservesNonMcpPrefixedName() {
        assertThat(McpServerToolSanitizer.sanitize("github__create_issue"))
            .isEqualTo("github__create_issue");
    }

    /**
     * [R32-b12 Fix-v3 P1-4] mcp__server__tool sanitizer: telemetry emit 入口调用 sanitize.
     */
    @Test
    @DisplayName("Fix-v3 P1-4: StreamingToolExecutor emit 方法在 telemetry 前调用 sanitize")
    void streamingToolExecutorEmitMethodsCallSanitize() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // emitSuccessTelemetry / emitPostToolUseFailureAnalytics / emitFailureTelemetry 都调用 sanitize
        assertThat(source.split("McpServerToolSanitizer\\.sanitize", -1).length - 1)
            .as("至少 6 次 sanitize 调用 (success + post-failure + failure 各 ≥ 2 次)")
            .isGreaterThanOrEqualTo(6);
    }

    /**
     * [A1 撤外层迁移] 5 类 fallback 决策 telemetry 已从 LlmAgentLoop.applyPermissionFilter
     *   搬到 StreamingToolExecutor.executeAsync 入口. 验证新位置覆盖相同语义.
     */
    @Test
    @DisplayName("A1 [撤外层迁移]: StreamingToolExecutor.executeAsync 入口 5 类 fallback 路径均注入 decisions")
    void allPermissionFilterFallbacksInjectDecision() throws Exception {
        String execSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/tool/StreamingToolExecutor.java"));
        // 1. cancelled: emitCancelledTelemetry 注入 user_reject/reject
        assertThat(execSource).contains("emitCancelledTelemetry");
        assertThat(execSource).contains("ToolDecisionInfo(\"user_reject\", \"reject\")");
        // 2. unknown tool: 已在 StreamingToolExecutor.add() 立即生成 ToolResult.error (No such tool)
        //    (不是新加,但保留)
        assertThat(execSource).contains("No such tool available");
        // 3. schema fail: emitToolUseErrorTelemetry(单参重载) + schema fail log
        assertThat(execSource).contains("emitToolUseErrorTelemetry");
        assertThat(execSource).contains("TOOL schema 校验");
        // 4. semantic fail: emitToolUseErrorTelemetry(errorCode, errorMsg 双参重载) + semantic fail log
        assertThat(execSource).contains("TOOL semantic 校验");
        // 5. pipeline exception: gate.check throw → 已捕获, fail loud
        assertThat(execSource).contains("TOOL permission gate threw");
        // 6. validation/config fail fallback 决策注入: 两重载内部硬编码注入
        //    ToolDecisionInfo("config", "reject") · decision="reject" (P-AL-05 D-2)
        assertThat(execSource).contains("emitToolUseErrorTelemetry(t,");
        assertThat(execSource).contains("new ToolDecisionInfo(\"config\", \"reject\")");
        // SchemaNotSentHint (仅 MCP deferred tool): emitDeferredSchemaTelemetry
        assertThat(execSource).contains("emitDeferredSchemaTelemetry");
        assertThat(execSource).contains("tengu_deferred_tool_schema_not_sent");
        // 决策 telemetry injectDecisionInfo 注入 source="other" (ALLOW 默认) / deny.reason() (DENY)
        assertThat(execSource).contains("injectDecisionInfo");
        assertThat(execSource).contains("decisionReasonToOTelSource");
    }

    /**
     * [A1 撤外层迁移] toolDecisions Map 初始化已从 LlmAgentLoop.applyPermissionFilter 入口
     *   搬到 StreamingToolExecutor.executeAsync 各 emit helpers 内 (按 callId 即时注入).
     *   验证 LlmAgentLoop.applyPermissionFilter 已整体删除 + record 已删除.
     */
    @Test
    @DisplayName("A1 [撤外层迁移]: applyPermissionFilter + PermissionFilterResult 整体删除")
    void permissionFilterInitializesDecisionsMapAtEntry() throws Exception {
        String llmSource = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        // 验证 applyPermissionFilter 函数定义已删除 (search returns -1)
        int applyFilterStart = llmSource.indexOf(
            "private PermissionFilterResult applyPermissionFilter(AgentState state, List<ToolUseBlock> calls) {");
        // 验证 PermissionFilterResult record 已删除
        int recordStart = llmSource.indexOf("private record PermissionFilterResult(");
        // 验证 applyHookDecision helper 已删除 (applyPermissionFilter 内部唯一调用)
        int helperStart = llmSource.indexOf("private void applyHookDecision(");
        // 验证 maybeExecutePermissionDeniedRetry helper 已删除
        int retryHelper = llmSource.indexOf("private boolean maybeExecutePermissionDeniedRetry(");
        // 验证 emitDecisionTelemetry 已删除
        int decisionTelemetry = llmSource.indexOf("private void emitDecisionTelemetry(");
        assertThat(applyFilterStart)
            .as("applyPermissionFilter 函数定义应已删除")
            .isLessThan(0);
        assertThat(recordStart)
            .as("PermissionFilterResult record 应已删除")
            .isLessThan(0);
        assertThat(helperStart)
            .as("applyHookDecision helper 应已删除")
            .isLessThan(0);
        assertThat(retryHelper)
            .as("maybeExecutePermissionDeniedRetry helper 应已删除")
            .isLessThan(0);
        assertThat(decisionTelemetry)
            .as("emitDecisionTelemetry helper 应已删除")
            .isLessThan(0);
        // 验证 LlmAgentLoop 不再调用 applyPermissionFilter
        assertThat(llmSource).doesNotContain("applyPermissionFilter");
        // 验证迁移日志 (loop 内调用点已有)
        assertThat(llmSource).contains("撤除外层");
    }
}
package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [canUseTool v2 对抗核验] STOMP 权限弹窗必须承载 description / suggestions / blockedPath.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC useCanUseTool.tsx:56-60 在 ask/deny 分流前
 * {@code await tool.description(input, ...)}，description 用于交互队列展示与拒绝记录；
 * CC interactiveHandler.ts:250-253 把 {@code result.suggestions} / {@code result.blockedPath}
 * 传给 bridge（CCR/claude.ai 远程弹窗），本地弹窗也必须携带。若 Java STOMP 事件丢弃这些
 * 字段 → 前端弹窗无法显示描述 / 建议规则 / 被阻断路径，行为与 CC 不符。
 *
 * @see MessagePermissionRequestEvent
 * @see WebSocketPermissionPrompter
 * @since canUseTool v2 修复
 */
class WebSocketPermissionPrompterPromptDetailsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    /** 可配置 description 的桩工具. */
    private static final class StubTool implements Tool {
        private final String name;
        private final String desc;
        StubTool(String name, String desc) { this.name = name; this.desc = desc; }
        @Override public String name() { return name; }
        @Override public String description() { return desc; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static ToolUseContext newCtx() {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    /**
     * 在独立线程调用阻塞 prompt（短超时），捕获 STOMP 推送的事件。
     * prompt 会阻塞到超时降级 Deny，但事件在阻塞前已推送。
     */
    private static MessagePermissionRequestEvent runPromptAndCapture(
            Tool tool, JsonNode input,
            PermissionDecisionReason reason, PermissionPromptDetails details)
            throws InterruptedException {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter bound = new WebSocketPermissionPrompter(ws, 100);
        return runPromptAndCapture(ws, bound, tool, input, reason, details);
    }

    /**
     * [IMP-H R1] 使用已注入 FeatureFlags 的 prompter 捕获事件（破坏性命令分支门控验证）。
     */
    private static MessagePermissionRequestEvent runPromptAndCapture(
            SimpMessagingTemplate ws, WebSocketPermissionPrompter bound, Tool tool, JsonNode input,
            PermissionDecisionReason reason, PermissionPromptDetails details)
            throws InterruptedException {
        Thread t = new Thread(() -> {
            try {
                bound.prompt(tool, input, reason, newCtx(), "req-1", details);
            } catch (Throwable th) {
                // 阻塞线程超时降级 Deny 是预期路径；不在此处断言
            }
        });
        t.start();
        t.join(3_000);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(anyString(), captor.capture());
        return (MessagePermissionRequestEvent) captor.getValue();
    }

    @Test
    @DisplayName("STOMP 事件必须携带 description（CC tool.description(input) 对齐）")
    void stompEventCarriesDescription() throws InterruptedException {
        // WHY: description 是 CC 队列展示 + recordAutoModeDenial 的文案来源；事件缺该字段
        //      前端弹窗只能显示 toolName+input，无法解释"为什么问"，行为与 CC 不符。
        MessagePermissionRequestEvent event = runPromptAndCapture(
            new StubTool("Bash", "Run a shell command"),
            JSON.createObjectNode().put("command", "git status"),
            new PermissionDecisionReason.Other("test"),
            new PermissionPromptDetails("Run a shell command", List.of(), null));

        assertThat(event.getDescription())
            .as("CC useCanUseTool.tsx:56-60 description 必须进弹窗队列展示")
            .isEqualTo("Run a shell command");
    }

    @Test
    @DisplayName("STOMP 事件必须携带 suggestions + blockedPath（CC interactiveHandler.ts:250-253）")
    void stompEventCarriesSuggestionsAndBlockedPath() throws InterruptedException {
        // WHY: CC 把 PermissionAskDecision.suggestions（"Add allow rule" 等建议）+
        //      blockedPath（被阻断路径）传给 bridge 与弹窗；Java 事件丢弃 → 用户无法
        //      一键加规则/看到被阻断的路径，suggestions/blockedPath 结构体形同虚设。
        PermissionRule rule = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Bash"));
        PermissionUpdate suggestion = new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.SESSION, List.of(rule), PermissionBehavior.ALLOW);

        MessagePermissionRequestEvent event = runPromptAndCapture(
            new StubTool("Bash", "desc"),
            JSON.createObjectNode(),
            new PermissionDecisionReason.Other("test"),
            new PermissionPromptDetails("desc", List.of(suggestion), "/blocked/path"));

        assertThat(event.getSuggestions())
            .as("CC interactiveHandler.ts:252 result.suggestions 必须进事件")
            .hasSize(1);
        assertThat(event.getBlockedPath())
            .as("CC interactiveHandler.ts:253 result.blockedPath 必须进事件")
            .isEqualTo("/blocked/path");
    }

    @Test
    @DisplayName("IMP-H R1: sed 编辑分支不门控（CC BashPermissionRequest.tsx:89 sedInfo 无 feature 门控）")
    void sedBranchUngatedByFeatureFlag() throws InterruptedException {
        // WHY: CC sedInfo 分支（BashPermissionRequest.tsx:89 parseSedEditCommand → :100 if (sedInfo)）
        //      不经过 tengu_destructive_command_warning 门控 —— sed 编辑渲染必须恒显示，
        //      与破坏性命令分支（:274 门控）语义不同。feature 关闭时 sed 渲染仍必须出现。
        MessagePermissionRequestEvent event = runPromptAndCapture(
            new StubTool("Bash", "desc"),
            JSON.createObjectNode().put("command", "sed -i s/foo/bar/g file.txt"),
            new PermissionDecisionReason.Other("test"),
            new PermissionPromptDetails("desc", List.of(), null));

        assertThat(event.getWarning())
            .as("CC BashPermissionRequest.tsx:89 sedInfo 分支不门控 —— feature 关闭时 sed 渲染仍须出现")
            .contains("sed 编辑")
            .contains("file.txt");
    }

    @Test
    @DisplayName("IMP-H R1: 破坏性命令分支门控关闭（默认 false）→ warning 恒 null（CC 外部构建语义）")
    void destructiveBranchGatedOffByDefault() throws InterruptedException {
        // WHY: CC BashPermissionRequest.tsx:274 getFeatureValue_CACHED_MAY_BE_STALE(
        //      'tengu_destructive_command_warning', false) —— 外部构建默认 false →
        //      destructiveWarning 恒 null。Java 默认（featureFlags=ALL_DISABLED）必须同样 null，
        //      否则无条件显示破坏性命令警告即偏离 CC 外部构建行为。
        MessagePermissionRequestEvent event = runPromptAndCapture(
            new StubTool("Bash", "desc"),
            JSON.createObjectNode().put("command", "rm -rf build/"),
            new PermissionDecisionReason.Other("test"),
            new PermissionPromptDetails("desc", List.of(), null));

        assertThat(event.getWarning())
            .as("CC BashPermissionRequest.tsx:274 门控默认 false → destructiveWarning 恒 null")
            .isNull();
    }

    @Test
    @DisplayName("IMP-H R1: 破坏性命令分支门控开启 → warning 出现（CC 门控 true 语义）")
    void destructiveBranchGatedOnWhenEnabled() throws InterruptedException {
        // WHY: 门控开启（nexusai.feature.destructive-command-warning=true，对应 CC
        //      getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', true)）
        //      时 getDestructiveCommandWarning(command) 才返回警告 —— 证明门控真实接线、
        //      开启后破坏性命令警告可达（非恒 null 死区）。
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter bound = new WebSocketPermissionPrompter(ws, 100);
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false,
            false, false, false, true); // 仅 destructiveCommandWarning=true
        bound.setFeatureFlagsForTesting(flags);

        MessagePermissionRequestEvent event = runPromptAndCapture(
            ws, bound,
            new StubTool("Bash", "desc"),
            JSON.createObjectNode().put("command", "rm -rf build/"),
            new PermissionDecisionReason.Other("test"),
            new PermissionPromptDetails("desc", List.of(), null));

        assertThat(event.getWarning())
            .as("CC BashPermissionRequest.tsx:274 门控开启 → getDestructiveCommandWarning 可达")
            .contains("force-remove");
    }
}

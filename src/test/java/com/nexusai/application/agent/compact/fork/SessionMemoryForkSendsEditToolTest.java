package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.RunForkedAgent.ForkQueryParams;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM 工具诊断] 复现 SessionMemory fork 形态，断言「发给模型的实际请求」里：
 * <ol>
 *   <li>工具集<b>包含 Edit</b>（availableTools 继承主线程 → buildToolsArray → 下发；模型可见）；</li>
 *   <li>真实对话（forkContextMessages）<b>确实在请求 history 里</b>（模型看得到要总结的会话）。</li>
 * </ol>
 *
 * <p>WHY 本测试存在（意图）：日志显示 SM fork「turns=1 / 0 次工具调用 / 只回 ~42 token 文本」，
 * summary.md 恒为模板 → 需先钉死是「没给 Edit 工具」（本文档第一断言 RED 即证实）还是「工具给了、
 * 模型没发起 Edit」（第一断言绿 + 真机看「无工具调用文本收尾」日志）。纯单测不发网络：stub provider
 * 捕获 stream 的 tools(第5参) 与 history(第4参) 后回一个无工具调用的文本（对齐真实模型行为）。
 */
class SessionMemoryForkSendsEditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("SM fork：工具集含 Edit + 对话 history 含真实会话消息")
    void sessionMemoryFork_sendsEditTool_andConversationHistory() {
        // ── 捕获槽：provider 收到的 tools 数组 + history ──
        ArrayNode[] capturedTools = new ArrayNode[]{null};
        List<ChatMessageDto>[] capturedHistory = new List[]{null};
        LlmProvider provider = fakeProvider(capturedTools, capturedHistory);

        // ── availableTools：主线程工具集（含 Edit / Read / Bash 等）──
        Tool edit = fakeTool("Edit");
        Tool read = fakeTool("Read");
        Tool bash = fakeTool("Bash");
        ToolUseContext tuc = new ToolUseContext(
            UUID.randomUUID(), "sess-test", PermissionMode.DEFAULT,
            Map.of(), List.of(edit, read, bash), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of());

        // ── SM 请求形态：forkContextMessages=真实会话 + promptMessages=改 notes 指令 ──
        ChatMessageDto userMsg = message("u-1", Role.user, "请帮我诊断会话为什么卡");
        ChatMessageDto asstMsg = message("a-1", Role.assistant, "我看了日志，根因是空响应守卫硬断");
        ChatMessageDto prompt = message("sm-prompt", Role.user,
            "Based on the user conversation above (EXCLUDING this instruction), update the session notes file "
                + "C:\\summary.md using the Edit tool, then stop.");
        List<ChatMessageDto> requestMessages = List.of(userMsg, asstMsg, prompt);

        ForkQueryParams params = new ForkQueryParams(
            requestMessages,
            List.of("static", "dynamic"),
            Map.of(), Map.of(),
            null, tuc,
            QuerySource.SESSION_MEMORY, null, null, false, false, null);

        ProductionForkedQuery query = new ProductionForkedQuery(
            () -> provider, () -> "test-model",
            () -> new ProviderConfig("https://api.test", "sk-test"),
            null, null);

        ForkedAgentResult result = query.run(params);

        // ── 断言 1：模型可见工具集必须含 Edit（若 RED → 用户怀疑「没给工具」成立）──
        assertThat(capturedTools[0]).as("stub provider 必须收到 tools 数组").isNotNull();
        List<String> toolNames = new ArrayList<>();
        for (JsonNode n : capturedTools[0]) {
            toolNames.add(n.path("function").path("name").asText(""));
        }
        assertThat(toolNames)
            .as("SM fork 发给模型 的可见工具必须含 Edit（否则模型无从用 Edit 更新 summary.md）")
            .contains("Edit");

        // ── 断言 2：history 必须含真实会话（首条即对话 user 消息，而非只有改文件指令）──
        assertThat(capturedHistory[0]).as("stub provider 必须收到 history").isNotNull();
        List<String> contents = capturedHistory[0].stream()
            .map(ChatMessageDto::content)
            .collect(java.util.stream.Collectors.toList());
        assertThat(contents.subList(0, 3))
            .as("history 前三条应为 真实会话 user + assistant + 改文件指令 —— 证明对话上下文真的在请求里（尾部为模型回复追加，非判定点）")
            .containsExactly(
                "请帮我诊断会话为什么卡",
                "我看了日志，根因是空响应守卫硬断",
                "Based on the user conversation above (EXCLUDING this instruction), update the session notes file "
                    + "C:\\summary.md using the Edit tool, then stop.");

        // ── 断言 3：无工具调用文本收尾 → fork 正常返回（对齐真实「模型没调 Edit」时文件不变）──
        assertThat(result).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** fake provider：捕获 stream 的 tools(第5参) 与 history(第4参)，回无工具调用文本（对齐真实模型行为）。 */
    private static LlmProvider fakeProvider(ArrayNode[] capturedTools, List<ChatMessageDto>[] capturedHistory) {
        return new LlmProvider() {
            @Override public String type() { return "test"; }
            @Override public void stream(ProviderConfig c, String m, List<SystemPromptBlock> blocks,
                                         List<ChatMessageDto> h, ArrayNode t, Integer maxTokens,
                                         com.nexusai.infra.llm.TaskBudgetParam taskBudget, String effort,
                                         String querySource, Consumer<String> onChunk,
                                         Consumer<AssistantMessage> onAssistant,
                                         Consumer<com.nexusai.application.agent.tool.ToolUseBlock> onToolCall,
                                         Consumer<String> onReasoning, Runnable onStreamingFallback,
                                         com.nexusai.application.agent.tool.AbortController abort,
                                         Consumer<Throwable> onError, Runnable onComplete) {
                capturedTools[0] = t;
                capturedHistory[0] = h;
                onAssistant.accept(new AssistantMessage("会话内容较长，暂无必要新增内容。", "stop", List.of()));
                onComplete.run();
            }
            @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
                return "会话内容较长，暂无必要新增内容。";
            }
        };
    }

    /** 简易可用 Tool（name 可控；isEnabled 默认 true → toOpenAiToolsArray 不过滤掉）。 */
    private static Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "fake " + name + " tool"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
            }
        };
    }

    private static ChatMessageDto message(String id, Role role, String content) {
        return new ChatMessageDto(
            id, null, role, role == Role.user ? "user" : "assistant", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }
}

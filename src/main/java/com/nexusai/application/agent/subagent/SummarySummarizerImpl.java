package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * SummarySummarizer 默认实现 · 对齐 CC agentSummary.ts 的三类副作用.
 *
 * <p>CC 真源 (agentSummary.ts):
 * <ul>
 *   <li>{@code readTranscript} (:68): {@code getAgentTranscript(agentId)} 读 sidechain transcript</li>
 *   <li>{@code filterIncompleteToolCalls} (:78): {@code filterIncompleteToolCalls(transcript.messages)}
 *       (CC runAgent.ts:866)</li>
 *   <li>{@code summarize} (:109-119): {@code runForkedAgent({ promptMessages: [buildSummaryPrompt],
 *       cacheSafeParams, canUseTool: deny, querySource: 'agent_summary', forkLabel: 'agent_summary',
 *       skipTranscript: true })} — fork LLM 生成 1-2 句进度摘要</li>
 * </ul>
 *
 * <p>[IMP-SUB-02 D2] 摘要带 transcript 上下文 (CC agentSummary.ts:81-84 forkContextMessages):
 * 原 Java 简化 {@code provider.chat(config, modelName, null, prompt)} 单轮无上下文 (WF6-02 T1),
 * 现改用 {@link LlmProvider#chatWithOptions} + {@code ChatRequestOptions.history} = clean 消息,
 * 使 LLM 看到 clean transcript + summary prompt (对齐 CC fork 的 initialMessages =
 * {@code [...cleanMessages, summaryPrompt]}).
 *
 * <p>[IMP-SUB-02 D2] abort in-flight (CC agentSummary.ts:91/169-171 overrides.abortController):
 * 每轮 fork 由 {@link AgentSummaryService} 创建 per-run {@link AbortController}, 经本方法透传
 * provider — stop() 时 abort 中断进行中的 LLM 调用.
 *
 * <p>Java 简化 (S5-9/S5-11 决策):
 * <ul>
 *   <li>非 @Component: sessionDir/sessionId 是 per-agent 运行时值 (SubagentExecutor 每次执行
 *       解析出会话目录), 由 SubagentExecutor 每次构造本实例注入</li>
 *   <li>不共享父 prompt cache (Java 端无 fork cache 通道, 见 concern S5-11; CC 摘要 fork 共享
 *       父 cache — WF6-02 T2 已知受控残留)</li>
 * </ul>
 */
public class SummarySummarizerImpl implements SummarySummarizer {

    private static final Logger log = LoggerFactory.getLogger(SummarySummarizerImpl.class);

    private final Path sessionDir;
    private final String sessionId;
    private final LlmProviderFactory llmProviderFactory;
    private final ProviderConfig providerConfig;
    private final String modelName;

    public SummarySummarizerImpl(Path sessionDir, String sessionId,
                                 LlmProviderFactory llmProviderFactory,
                                 ProviderConfig providerConfig,
                                 String modelName) {
        this.sessionDir = sessionDir;
        this.sessionId = sessionId;
        this.llmProviderFactory = llmProviderFactory;
        this.providerConfig = providerConfig;
        this.modelName = modelName;
    }

    @Override
    public List<AgentMessage> readTranscript(String agentId) {
        // CC agentSummary.ts:68 getAgentTranscript(agentId)
        Optional<AgentTranscript.AgentTranscriptResult> transcript =
            AgentTranscript.getAgentTranscript(sessionDir, sessionId, agentId);
        if (transcript.isEmpty()) {
            log.debug("[SummarySummarizerImpl] agent {} 无 transcript, 返空列表", agentId);
            return List.of();
        }
        List<AgentMessage> messages = transcript.get().messages();
        if (log.isDebugEnabled()) {
            log.debug("[SummarySummarizerImpl] agent {} 读取 {} 条 transcript 消息", agentId, messages.size());
        }
        return messages;
    }

    @Override
    public List<AgentMessage> filterIncompleteToolCalls(List<AgentMessage> messages) {
        // CC agentSummary.ts:78 filterIncompleteToolCalls (runAgent.ts:866) — ANY unresolved tool_use 剔除
        return MessageFilters.filterIncompleteToolCalls(messages);
    }

    @Override
    public String summarize(String agentId, String prompt) {
        // 无显式 clean 上下文时自读自滤 (CC agentSummary.ts:68-84: runSummary 读 transcript →
        // filterIncompleteToolCalls → forkContextMessages). 兼容非 SummarySummarizerImpl 的注入方.
        List<AgentMessage> clean = filterIncompleteToolCalls(readTranscript(agentId));
        return summarize(agentId, prompt, clean, null);
    }

    /**
     * CC runForkedAgent 等价实现 (agentSummary.ts:109-119) · fork 带 clean transcript 上下文.
     *
     * <p>由 {@link AgentSummaryService} 调用: 传入 runSummary 已读 + 已滤的 clean 消息
     * (CC agentSummary.ts:78-84 forkContextMessages) + 本轮的 per-run AbortController
     * (CC agentSummary.ts:91/117 overrides.abortController).
     *
     * @param agentId         fork 目标 agent id
     * @param prompt          含历史摘要 + 模板的 prompt (CC buildSummaryPrompt)
     * @param context         clean transcript 消息 (forkContextMessages 等价)
     * @param abortController 本轮 fork 的取消信号 (CC overrides.abortController; 可 null = 无中断能力)
     * @return 摘要文本 (trim 后非空); 失败 / LLM 返回空 / 被 abort → null
     */
    public String summarize(String agentId, String prompt,
                            List<AgentMessage> context, AbortController abortController) {
        // CC agentSummary.ts:109-119 runForkedAgent — 简化为 chatWithOptions 非工具调用
        try {
            LlmProvider provider = llmProviderFactory != null
                ? llmProviderFactory.getProvider(providerConfig)
                : null;
            if (provider == null) {
                log.warn("[SummarySummarizerImpl] llmProviderFactory 未注入, 跳过 summary (agent={})", agentId);
                return null;
            }
            List<ChatMessageDto> history = toChatHistory(context);
            // CC querySource: 'agent_summary' (agentSummary.ts:115) — 侧信道来源标记
            LlmProvider.ChatRequestOptions options = new LlmProvider.ChatRequestOptions(
                history, null, null, null, null,
                "agent_summary", abortController, null);
            if (log.isDebugEnabled()) {
                log.debug("[SummarySummarizerImpl] agent {} fork 摘要, {} 条 clean 上下文, abort={}",
                    agentId, history.size(), abortController != null);
            }
            String summary = provider.chatWithOptions(providerConfig, modelName, null, prompt, options);
            if (log.isDebugEnabled()) {
                log.debug("[SummarySummarizerImpl] agent {} summary: {}", agentId,
                    summary != null ? summary.substring(0, Math.min(summary.length(), 120)) : "null");
            }
            return summary;
        } catch (CancellationException e) {
            // CC agentSummary.ts:145 abort 中止 fork — stop() 已置 stopped, 静默返回 null
            //   (AnthropicSdkProvider chatWithOptions abort 预检 CancellationException 原样透传)
            if (log.isDebugEnabled()) {
                log.debug("[SummarySummarizerImpl] agent {} 摘要被中止 (abort)", agentId);
            }
            return null;
        } catch (Exception e) {
            log.warn("[SummarySummarizerImpl] 摘要生成失败 agent={}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * clean AgentMessage → provider history (ChatMessageDto) 转换 · 对齐 CC forkContextMessages
     * 的 role/content/tool_use/tool_result 语义 (SubagentExecutor.convertToChatMessageDto 同型):
     * <ul>
     *   <li>tool → Role.tool + toolCallId (tool_result 序列化); 无 toolCallId 无法配对 → 丢弃</li>
     *   <li>assistant 含 toolCalls → Role.assistant + toolCalls (tool_use 块序列化)</li>
     *   <li>其余 → Role 直映射 + content</li>
     * </ul>
     * transcript 内部字段 (agentId/isSidechain/uuid/parentUuid) 剥离 — 对齐 CC 只取消息语义.
     */
    private List<ChatMessageDto> toChatHistory(List<AgentMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (AgentMessage m : messages) {
            if (m == null) {
                continue;
            }
            String role = m.role() != null ? m.role() : "user";
            String content = m.content() != null ? m.content() : "";
            if ("tool".equals(role)) {
                if (m.toolCallId() == null || m.toolCallId().isBlank()) {
                    // SubagentExecutor.convertToChatMessageDto:4591-4631 同语义 — role=tool 无 toolCallId 丢弃
                    if (log.isDebugEnabled()) {
                        log.debug("[SummarySummarizerImpl] toChatHistory: role=tool 无 toolCallId, 丢弃该消息");
                    }
                    continue;
                }
                out.add(new ChatMessageDto(
                    UUID.randomUUID().toString(), sessionId, Role.tool, null,
                    content, null, null, null, null, null, null, OffsetDateTime.now(),
                    m.toolCallId(), null, null, List.of(), List.of(), null, false, m.isApiError()));
            } else if ("assistant".equals(role) && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<ToolCallDto> toolCalls = new ArrayList<>();
                for (AgentMessage.ToolCallInfo tc : m.toolCalls()) {
                    toolCalls.add(new ToolCallDto(
                        tc.id(), tc.name(),
                        tc.arguments() != null ? tc.arguments() : "{}", null, null));
                }
                out.add(new ChatMessageDto(
                    UUID.randomUUID().toString(), sessionId, Role.assistant, null,
                    content, null, toolCalls, FinishReason.tool_calls,
                    null, null, null, OffsetDateTime.now(), null, null,
                    null, List.of(), List.of(), null, false, false));
            } else {
                Role r;
                try {
                    // 对齐 SubagentExecutor.convertToChatMessageDto:4664 Role.valueOf(role.toLowerCase())
                    //   (Role 枚举常量为小写 user/assistant/system/tool — toUpperCase 恒抛 → 全落 user)
                    r = Role.valueOf(role.toLowerCase());
                } catch (IllegalArgumentException e) {
                    r = Role.user;
                }
                out.add(new ChatMessageDto(
                    UUID.randomUUID().toString(), sessionId, r, null,
                    content, null, null, null, null, null, null, OffsetDateTime.now(),
                    null, null, null, List.of(), List.of(), null, false, false));
            }
        }
        return out;
    }

    @Override
    public int minMessages() {
        // CC agentSummary.ts:69 (transcript.messages.length < 3 跳过)
        return 3;
    }
}

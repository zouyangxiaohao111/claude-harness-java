package com.nexusai.apis.verify;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.event.AgentLoopExitedEvent;
import com.nexusai.application.agent.event.AgentLoopStartedEvent;
import com.nexusai.application.agent.event.AgentTurnCompletedEvent;
import com.nexusai.application.agent.event.AgentTurnStartedEvent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证用 Chat Controller · Phase 6·s02 AgentLoop 验证页面的后端。
 *
 * <p>区别于 {@link com.nexusai.apis.session.ChatController}（DB 持久化 + STOMP 流），
 * 本 controller 专注于"快速验证 AgentLoop 是否能跑"：
 * <ul>
 *   <li>直接 new 一个 LlmAgentLoop，传入 user prompt</li>
 *   <li>同步等 run() 返回</li>
 *   <li>把 AgentState 的关键信息序列化为 JSON 返回</li>
 *   <li>不写 DB · 不发 STOMP · 每次调用独立</li>
 * </ul>
 *
 * <p>POST /api/v1/verify/chat
 * <pre>
 * Request:  { "prompt": "...", "model": "mock-fast" (optional), "systemPrompt": "..." (optional),
 *             "appendSystemPrompt": "..." (optional, RES-SP31),
 *             "taskBudget": 150000 (optional · API task_budget tokens · IMP2-10/OD-13,
 *             CC original: --task-budget main.tsx:982-988; null → 配置/默认值) }
 * Response: { "userText", "assistantText", "turns", "exitReason", "durationMs",
 *             "toolCalls": [...], "events": [...] }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/verify")
public class VerifyChatController {

    private static final Logger log = LoggerFactory.getLogger(VerifyChatController.class);

    @Autowired private LlmProviderFactory llmProviderFactory;
    @Autowired private ToolRegistry toolRegistry;
    // [R32-b7b-2 P1-1 修复] Spring prototype scope 注入 — 替代旧 new LlmAgentLoop(),
    //   让 setFileConfigStorage / setRuntimeModelOverride / setStartupModelFlag 在生产路径生效.
    @Autowired private org.springframework.beans.factory.ObjectProvider<LlmAgentLoop> loopProvider;
    // [IMP2-10 · MISS-2 · OD-13] taskBudget 配置源（tokens；0 = 未配置 → 回落 RunRequest.DEFAULT_TASK_BUDGET_TOTAL）。
    //   来源链 = 请求参数 VerifyChatRequest.taskBudget → 本配置 → 默认值（OD-13 裁决）。
    @Value("${nexusai.agent.task-budget.total:0}")
    private int taskBudgetTotalConfigured = 0;

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public VerifyChatResponse chat(@RequestBody VerifyChatRequest req) {
        if (req == null || req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        String model = (req.model() == null || req.model().isBlank()) ? "mock-fast" : req.model();
        // [P-26] fallback==main 内联校验（loop.run 前）· 对齐 CC main.tsx:1336-1340
        //   stderr + exit(1) 等价；ValidationException → GlobalExceptionHandler 400。
        //   主模型 = 本入口解析后的生效 model（request body / mock-fast 默认）。
        String fallbackModel = req.fallbackModel();
        if (fallbackModel != null && !fallbackModel.isBlank() && fallbackModel.equals(model)) {
            if (log.isDebugEnabled()) {
                log.debug("[VerifyChatController] fallback 模型与主模型相同被拒: model={} · CC main.tsx:1336-1340",
                    model);
            }
            throw new com.nexusai.infra.exception.ValidationException(
                "Fallback model cannot be the same as the main model. Please specify a different model for --fallback-model.");
        }
        // [R32-b7b-2 P2-2 修复] 替换 System.out.println 为 slf4j 中文 INFO · 不回显 model 原值
        // 仅记录 prompt 长度 + model 来源 (request body / 默认), 与项目 CLAUDE.md 规则 11 一致.
        log.info("[VerifyChatController] 已接收 verify 请求: prompt={}chars, model来源={}",
            req.prompt().length(),
            (req.model() == null || req.model().isBlank()) ? "default" : "request-body");
        EventRecorder recorder = new EventRecorder();
        ApplicationEventPublisher publisher = recorder::record;

        // [R32-b7b-2 P1-1 修复] Spring prototype scope 注入 — loopProvider.getObject() 返回 fresh 实例,
        //   Spring 自动注入 fileConfigStorage / runtimeModelOverride / startupModelFlag 等 @Autowired 依赖.
        LlmAgentLoop loop = loopProvider.getObject();
        loop.setStreamContext(null, null, null);  // VerifyChatController 无 STOMP 流上下文
        loop.setEventPublisher(publisher);

        long start = System.nanoTime();

        // [IMP2-10 · MISS-2 · OD-13] taskBudget 生产注入：来源链 = 请求参数 taskBudget →
        //   配置 nexusai.agent.task-budget.total → 默认值（OD-13 裁决；恒非 null）。
        com.nexusai.application.agent.TaskBudget taskBudget =
            com.nexusai.application.agent.RunRequest.resolveTaskBudget(req.taskBudget(), taskBudgetTotalConfigured);
        if (log.isDebugEnabled()) {
            log.debug("[IMP2-10 taskBudget] verify 入口注入: source=请求参数/配置/默认值 total={}", taskBudget.total());
        }
        AgentState state = loop.run(com.nexusai.application.agent.RunRequest.user(
            req.prompt(), null, model, req.systemPrompt(), req.appendSystemPrompt(),
            req.fallbackModel(),  // [DEC-RV-02 · FIX-16] per-call 降级模型（HTTP 请求体 → RunRequest.fallbackModel → QueryParams → RetryOptions）
            req.permissionMode(), // [RV-11 · REV-FIX-2] 初始权限模式（HTTP 请求体 → RunRequest.permissionModeCli → InitialPermissionModeResolver.Input）
            Boolean.TRUE.equals(req.dangerouslySkipPermissions()), // [RV-11 · REV-FIX-2] dangerouslySkip（HTTP 请求体 → RunRequest.dangerouslySkipPermissions）
            taskBudget)); // [IMP2-10 · MISS-2] taskBudget 生产注入（OD-13: 配置→默认值）
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        String userText = req.prompt();
        for (int i = state.messages().size() - 1; i >= 0; i--) {
            ChatMessageDto m = state.messages().get(i);
            if (m.role() == Role.user && m.content() != null) {
                userText = m.content();
                break;
            }
        }

        List<ToolCallRecord> toolCalls = new ArrayList<>();
        for (ChatMessageDto m : state.messages()) {
            if (m.role() == Role.assistant && m.toolCalls() != null) {
                for (ToolCallDto tc : m.toolCalls()) {
                    toolCalls.add(new ToolCallRecord(
                        tc.id(), tc.name(), tc.arguments(), null, null, null));
                }
            }
            if (m.role() == Role.tool) {
                String callId = m.toolCallId();
                String content = m.content() == null ? "" : m.content();
                boolean isError = content.startsWith("Error")
                    || content.toLowerCase().contains("dangerous")
                    || content.toLowerCase().contains("not found")
                    || content.toLowerCase().contains("no such tool");
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolCallRecord tcr = toolCalls.get(i);
                    if (callId != null && callId.equals(tcr.id())) {
                        toolCalls.set(i, new ToolCallRecord(
                            tcr.id(), tcr.name(), tcr.args(),
                            content, isError, (long) content.length()));
                    }
                }
            }
        }

        return new VerifyChatResponse(
            userText,
            state.lastAssistant(),
            state.turnCount(),
            state.exitReason() == null ? null : state.exitReason().name(),
            state.lastError(),
            durationMs,
            state.messages().size(),
            toolCalls,
            recorder.events
        );
    }

    /**
     * @param taskBudget 可选 · API task_budget（tokens）。CC original: {@code --task-budget <tokens>}
     *                   （main.tsx:982-988，正整型校验）；null = 未指定 → 回落配置
     *                   {@code nexusai.agent.task-budget.total} → 默认值（OD-13 来源链）。
     */
    public record VerifyChatRequest(String prompt, String model, String systemPrompt, String appendSystemPrompt,
                                    String fallbackModel, Integer taskBudget, String permissionMode, Boolean dangerouslySkipPermissions) {}

    public record VerifyChatResponse(
        String userText,
        String assistantText,
        int turns,
        String exitReason,
        String error,
        long durationMs,
        int messageCount,
        List<ToolCallRecord> toolCalls,
        List<CapturedEvent> events
    ) {}

    public record ToolCallRecord(
        String id,
        String name,
        String args,
        String result,
        Boolean isError,
        Long resultLength
    ) {}

    public record CapturedEvent(String type, int turn, String detail) {}

    static class EventRecorder {
        final List<CapturedEvent> events = new ArrayList<>();

        void record(Object event) {
            String type = event.getClass().getSimpleName();
            if (event instanceof AgentLoopStartedEvent) {
                events.add(new CapturedEvent(type, 0, null));
            } else if (event instanceof AgentTurnStartedEvent e) {
                events.add(new CapturedEvent(type, e.turnCount(), "model=" + e.modelName()));
            } else if (event instanceof AgentTurnCompletedEvent e) {
                events.add(new CapturedEvent(type, e.turnCount(),
                    e.textLength() + " chars, finish=" + e.finishReason()));
            } else if (event instanceof AgentLoopExitedEvent e) {
                events.add(new CapturedEvent(type, e.totalTurns(),
                    "exit=" + e.exitReason()));
            }
        }
    }
}

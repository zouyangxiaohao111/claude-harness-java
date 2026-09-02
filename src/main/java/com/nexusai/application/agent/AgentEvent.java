package com.nexusai.application.agent;

import com.nexusai.model.session.dto.ChatMessageDto;

/**
 * AgentEvent · 流式契约事件 · 对齐 CC query.ts AsyncGenerator 的 {@code Terminal | Continue} 类型.
 *
 * <p>修正文件对比统计报告 §8.1 A13：CC TS 的 queryLoop 是 AsyncGenerator, 每次 yield 一个
 * Terminal 或 Continue 类型事件. Java 端原 do-while 同步返回终态 AgentState, 无法满足
 * "带停止原因的模型事件流"消费方 (REPL/SDK/CCR Remote) 的需求.
 *
 * <p>本类提供 sealed interface 七种事件, 兼容 CC:
 * <ul>
 *   <li>{@link TurnStarted} - LLM 调用开始 (CC Continue)</li>
 *   <li>{@link Chunk} - 流式文本片段 (CC Continue, 用于 STOMP 推送)</li>
 *   <li>{@link ToolCalled} - 工具调用已完成 (CC Continue)</li>
 *   <li>{@link BoundaryMessage} - 压缩边界消息 (CC Continue, 对齐 query.ts:406-408 yield)</li>
 *   <li>{@link TurnCompleted} - 单 turn 结束 (CC Continue)</li>
 *   <li>{@link TokenWarning} - 上下文警告 / token 用量 (对齐 CC TokenWarning.tsx + compactWarningState.ts)</li>
 *   <li>{@link Terminal} - loop 终态 (CC Terminal, 含 ExitReason)</li>
 * </ul>
 *
 * <p>L2 契约 (6 Release Gate):
 * <ul>
 *   <li><b>A1</b>: sealed interface permits 7 records; record 字段顺序与 CC 一致</li>
 *   <li><b>A2 Golden Trace</b>: happy path → TurnStarted → Chunk* → ToolCalled* → TurnCompleted
 *       → ... → Terminal(NORMAL)</li>
 *   <li><b>A3 状态机</b>: sealed 不可扩展; 字段 record 不可变</li>
 *   <li><b>A4 边界</b>: null chunk 跳过; turnCount 0 防御</li>
 *   <li><b>A5</b>: 业务场景 user→TurnStarted→"Hello"→TurnCompleted→Terminal(NORMAL)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS discriminated union ('Terminal' | 'Continue') → Java sealed interface
 * with permits + pattern matching.
 */
public sealed interface AgentEvent
    permits AgentEvent.TurnStarted, AgentEvent.Chunk, AgentEvent.ToolCalled,
            AgentEvent.BoundaryMessage, AgentEvent.TurnCompleted, AgentEvent.TokenWarning,
            AgentEvent.Terminal {

    /**
     * 会话 ID（short 形态 sess-xxx）。[session-id-short] 与 STOMP topic 段 / 前端订阅对齐。
     */
    String sessionId();

    // ── CC "Continue" 事件族 (非终态) ──

    record TurnStarted(String sessionId, int turnCount, String modelName) implements AgentEvent {}

    record Chunk(String sessionId, int turnCount, String text) implements AgentEvent {}

    record ToolCalled(String sessionId, int turnCount, String toolName, String toolCallId) implements AgentEvent {}

    /**
     * 压缩边界消息事件 · 对齐 CC query.ts:406-408 {@code if (snipResult.boundaryMessage)
     * yield snipResult.boundaryMessage} —— snip 步骤产出的 boundary 消息 yield 到 SDK 流,
     * 供前端呈现（boundary 消息本身不落入 state.messages, 模型面消息链仍为
     * snipResult.messages, 双通道语义对齐 CC: yield 是流事件, messagesForQuery 继续走模型）。
     *
     * <p>载荷为完整 {@link ChatMessageDto}（Java 结构化边界转消息, 同
     * {@code CompactBoundaryMessage.createCompactBoundaryMessage("auto", 0, null, null, null)
     * .toChatMessageDto()}, [IMP2-23 D-19] 旧 of() 工厂已删）, 流消费者
     * 可直接渲染/落 transcript, 无需再查状态。
     */
    record BoundaryMessage(String sessionId, ChatMessageDto message) implements AgentEvent {}


    record TurnCompleted(String sessionId, int turnCount, int chunkCount, int assistantChars,
                         String finishReason) implements AgentEvent {}

    // ── CC "TokenWarning" 事件 (上下文警告, 非终态, STOMP 推送) ──

    /**
     * token 上下文警告事件 · 契约见 decisions-log §32「前端联动 · token_warning 事件契约」。
     *
     * <p>CC 锚点：
     * <ul>
     *   <li>{@code Open-ClaudeCode/src/components/TokenWarning.tsx:87-178} —— 前端警告组件,
     *       消费 tokenUsage/model 计算警告/错误阈值;</li>
     *   <li>{@code Open-ClaudeCode/src/services/compact/compactWarningState.ts:8-18} ——
     *       compactWarningStore 抑制态（suppressCompactWarning→true / clearCompactWarningSuppression→false）;</li>
     *   <li>{@code Open-ClaudeCode/src/services/compact/autoCompact.ts:33-49} ——
     *       getEffectiveContextWindowSize(model) → effectiveWindow（contextWindow − reserved）;</li>
     *   <li>{@code Open-ClaudeCode/src/services/compact/autoCompact.ts:93-145} ——
     *       calculateTokenWarningState(tokenUsage, model) 四态 + percentLeft。</li>
     * </ul>
     *
     * <p>STOMP 载荷（前端占位, 后端定事件类型）：
     * {@code { eventType, sessionId, suppressed, tokenUsage, contextWindow, percentLeft? }}。
     *
     * <p>触发（对齐 CC compactWarningState 生命周期）：
     * <ul>
     *   <li>压缩成功 → {@code suppressed=true}（CC suppressCompactWarning(), compactWarningState.ts:11-13）;</li>
     *   <li>新压缩开始 → {@code suppressed=false}（CC clearCompactWarningSuppression(), compactWarningState.ts:16-18）;</li>
     *   <li>上下文接近阈值 → 推 token 用量（tokenUsage/contextWindow）。</li>
     * </ul>
     *
     * @param sessionId     会话 ID
     * @param eventType     事件类型, 固定 {@code "token_warning"}（前端占位, 后端定, 见 {@link #EVENT_TYPE}）
     * @param suppressed    压缩警告抑制态（对齐 CC compactWarningStore, compactWarningState.ts:8）
     * @param tokenUsage    当前 token 用量（对齐 CC TokenWarning.tsx:10 props tokenUsage）
     * @param contextWindow 有效上下文窗口（对齐 CC autoCompact.ts:33-49 getEffectiveContextWindowSize → effectiveWindow）
     * @param percentLeft   剩余百分比（对齐 CC displayPercentLeft, TokenWarning.tsx:127/:154）; 可选,
     *                      null 表示前端自行计算 {@code Math.max(0, Math.round((effectiveWindow-tokenUsage)/effectiveWindow*100))}
     */
    record TokenWarning(String sessionId, String eventType, boolean suppressed,
                        long tokenUsage, long contextWindow, Integer percentLeft)
            implements AgentEvent {

        /** 后端定事件类型（前端占位 token_warning, 后端定字面量, decisions-log §32）。 */
        public static final String EVENT_TYPE = "token_warning";

        public TokenWarning {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("TokenWarning.eventType required");
            }
        }

        public static TokenWarning of(String sessionId, boolean suppressed, long tokenUsage,
                                      long contextWindow, Integer percentLeft) {
            return new TokenWarning(sessionId, EVENT_TYPE, suppressed, tokenUsage, contextWindow, percentLeft);
        }
    }

    // ── CC "Terminal" 事件 (终态, 必为最后一条) ──

    record Terminal(String sessionId, AgentState.ExitReason exitReason, String error) implements AgentEvent {

        public Terminal {
            if (exitReason == null) {
                throw new IllegalArgumentException("Terminal.exitReason required");
            }
        }

        public static Terminal of(String sessionId, AgentState.ExitReason reason) {
            return new Terminal(sessionId, reason, null);
        }
    }
}
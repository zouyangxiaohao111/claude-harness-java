package com.nexusai.application.agent.diff;

import java.util.Map;

/**
 * TraceEvent · 单条 trace 记录 · 对齐 skill differential-testing.md 的 trace.proto 结构.
 *
 * <p>Diff Engine 5-Gate 中的 A2 Golden Trace + A4 Tool Sequence 都基于此 record:
 * <ul>
 *   <li>{@code kind} — 事件类型 (LLM_REQUEST / LLM_CHUNK / TOOL_CALL / HOOK_FIRE / STATE_TRANSITION / EXIT)</li>
 *   <li>{@code name} — 子类型 (e.g. tool name, hook event type)</li>
 *   <li>{@code timestamp} — ms 相对开始时间</li>
 *   <li>{@code data} — 附加数据 (payload keys, exit reason 等)</li>
 * </ul>
 *
 * <p>序列化简单 (CSV-like), 不引入 protobuf 依赖. 差分测试读取 .golden trace 文件比对.
 */
public record TraceEvent(
    Kind kind,
    String name,
    long timestamp,
    Map<String, Object> data
) {

    public enum Kind {
        LLM_REQUEST,         // provider.stream() 进入
        LLM_CHUNK,           // 每个文本/推理 chunk
        LLM_ASSISTANT_MSG,   // 完整 assistant message (含 tool_calls)
        LLM_TOOL_CALL,       // 单个 tool_call 完成
        LLM_ERROR,           // provider error
        LLM_COMPLETE,        // provider complete
        TOOL_EXECUTE,        // 工具开始执行
        TOOL_RESULT,         // 工具结果
        HOOK_FIRE,           // hook 触发
        HOOK_RESULT,         // hook 处理结果
        STATE_TRANSITION,    // AgentState 字段变化 (turnCount, exitReason 等)
        COMPACTION,          // 压缩触发
        NOTIFICATION,        // NotificationQueue 入队
        EXIT                 // run() 退出
    }

    public static TraceEvent llmRequest(long ts) {
        return new TraceEvent(Kind.LLM_REQUEST, "stream", ts, Map.of());
    }

    public static TraceEvent llmChunk(long ts, String text) {
        return new TraceEvent(Kind.LLM_CHUNK, "text", ts, Map.of("len", text.length()));
    }

    public static TraceEvent toolCall(long ts, String name, String callId) {
        return new TraceEvent(Kind.LLM_TOOL_CALL, name, ts, Map.of("id", callId));
    }

    public static TraceEvent toolResult(long ts, String name, String callId, boolean isError) {
        return new TraceEvent(Kind.TOOL_RESULT, name, ts, Map.of("id", callId, "isError", isError));
    }

    public static TraceEvent hookFire(long ts, String hookType) {
        return new TraceEvent(Kind.HOOK_FIRE, hookType, ts, Map.of());
    }

    public static TraceEvent compaction(long ts, String trigger, int freed) {
        return new TraceEvent(Kind.COMPACTION, trigger, ts, Map.of("freed", freed));
    }

    public static TraceEvent stateTransition(long ts, String key, Object before, Object after) {
        return new TraceEvent(Kind.STATE_TRANSITION, key, ts,
            Map.of("before", String.valueOf(before), "after", String.valueOf(after)));
    }

    public static TraceEvent exit(long ts, String exitReason) {
        return new TraceEvent(Kind.EXIT, exitReason, ts, Map.of());
    }

    @Override
    public String toString() {
        return String.format("[%6dms] %-20s %-30s %s", timestamp, kind, name, data);
    }
}
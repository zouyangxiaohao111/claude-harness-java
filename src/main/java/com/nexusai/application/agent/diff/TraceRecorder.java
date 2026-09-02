package com.nexusai.application.agent.diff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TraceRecorder · Diff Engine 的记录端 · 对齐 skill differential-testing.md Orchestrator.
 *
 * <p>在 LlmAgentLoop 测试中, 把 LLM 请求/工具调用/Hook/State transition/Exit
 * 全部记录到线程安全的 list, 跑完后跟 golden trace 比对.
 *
 * <p>用法 (与 A2 Golden Trace 一致):
 * <pre>
 * TraceRecorder recorder = new TraceRecorder();
 * LlmAgentLoop loop = new LlmAgentLoop(...);
 * // 把 recorder 接到 provider callbacks / hookRegistry
 * AgentState state = loop.run(...);
 * List<TraceEvent> actual = recorder.events();
 * DiffEngine.compare("test-name", GOLDEN_TRACE, actual);
 * </pre>
 */
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final long startMs = System.currentTimeMillis();
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();

    public void record(TraceEvent event) {
        events.add(event);
        log.debug("[TraceRecorder] {}", event);
    }

    public void llmRequest() {
        record(TraceEvent.llmRequest(elapsed()));
    }

    public void llmChunk(String text) {
        record(TraceEvent.llmChunk(elapsed(), text));
    }

    public void toolCall(String name, String callId) {
        record(TraceEvent.toolCall(elapsed(), name, callId));
    }

    public void toolResult(String name, String callId, boolean isError) {
        record(TraceEvent.toolResult(elapsed(), name, callId, isError));
    }

    public void hookFire(String hookType) {
        record(TraceEvent.hookFire(elapsed(), hookType));
    }

    public void compaction(String trigger, int freed) {
        record(TraceEvent.compaction(elapsed(), trigger, freed));
    }

    public void stateTransition(String key, Object before, Object after) {
        record(TraceEvent.stateTransition(elapsed(), key, before, after));
    }

    public void exit(String exitReason) {
        record(TraceEvent.exit(elapsed(), exitReason));
    }

    public List<TraceEvent> events() {
        return new ArrayList<>(events);
    }

    public void clear() {
        events.clear();
    }

    private long elapsed() {
        return System.currentTimeMillis() - startMs;
    }
}
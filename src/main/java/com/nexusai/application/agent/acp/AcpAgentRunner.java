package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.ResumeService;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP 子代理执行器 · 把一次委派变成「解析启动规格 → spawn → initialize → session/new →
 * session/prompt → 收尾 → 写 transcript → 返回 {@link SubagentExecutor.SubagentResult}」。
 *
 * <p>与 {@code ExternalAgentRunner} 的关键差别：
 * <ul>
 *   <li>不是「stdout 读到 EOF 再解析」，而是<b>长连接 JSON-RPC</b>，边读边推 STOMP；</li>
 *   <li>server→client 的 {@code request_permission} 要能回写 result ⇒ 有独立请求线程；</li>
 *   <li>收尾走「关 stdin → 宽限 → 杀进程树」的阶梯（AFP {@code disposeAcpChild} 同款）。</li>
 * </ul>
 *
 * <p>对外形态与既有外部路径一致：同一个 agentId 命名、同一份 transcript 落盘、
 * 同一种三态 {@code SubagentResult} ⇒ 右栏面板与 SubagentController 零改动。
 */
public final class AcpAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AcpAgentRunner.class);

    /** 结论文本上限（与 ExternalAgentRunner.RESULT_LIMIT 一致）。 */
    private static final int RESULT_LIMIT = 100_000;
    private static final long INIT_TIMEOUT_MS = 60_000L;
    private static final long SESSION_TIMEOUT_MS = 30_000L;
    /** 关 stdin 后等子进程自行退出的宽限（DSH 默认 6s）。 */
    private static final long EOF_GRACE_MS = 6_000L;

    private AcpAgentRunner() {
    }

    /** 生产入口 · 事件出口取 Spring 注册的 {@link AcpStreamPublisher}。 */
    public static SubagentExecutor.SubagentResult run(
            AgentDefinition.ExternalAgentDefinition def, String prompt, String model, Path cwd,
            String sessionId, String agentId, String description,
            ToolUseContext parentTuc, ToolPermissionGate permissionGate) {
        return run(def, prompt, model, cwd, sessionId, agentId, description,
                parentTuc, permissionGate, AcpStreamPublisher.current());
    }

    /** 可注入事件出口（单测用记录型 sink）。 */
    static SubagentExecutor.SubagentResult run(
            AgentDefinition.ExternalAgentDefinition def, String prompt, String model, Path cwd,
            String sessionId, String agentId, String description,
            ToolUseContext parentTuc, ToolPermissionGate permissionGate, AcpEventSink sink) {

        long startMs = System.currentTimeMillis();
        AcpRuntimeSpec spec;
        try {
            AcpRuntimeSpec.Resolution resolution = AcpRuntimeSpec.tryResolve(def, model, cwd);
            if (!resolution.isAvailable()) {
                return fail(resolution.unavailableReason(), agentId, startMs);
            }
            spec = resolution.spec();
        } catch (Exception e) {
            return fail("ACP 运行时解析失败: " + e.getMessage(), agentId, startMs);
        }

        log.info("[AcpAgentRunner] 启动 ACP 子代理: bin={} argv={} cwd={} timeoutMs={} policy={} agentId={}",
                spec.bin(), spec.argv(), spec.cwd(), spec.timeoutMs(),
                spec.permissionPolicy(), agentId);

        RunState state = new RunState();
        String assistantMessageId = safeAssistantId(sink, sessionId);
        AcpProcess.Started started = null;
        AcpTransport transport = null;
        AcpClient client = null;
        boolean timedOut = false;
        boolean promptFinished = false;
        // 父轮取消（POST /sessions/{id}/cancel）→ 发 ACP session/cancel + 终止进程树
        AcpAbortBridge abortBridge = new AcpAbortBridge(
                parentTuc == null ? null : parentTuc.abortController());
        abortBridge.bind();
        try {
            started = AcpProcess.start(spec.fullArgv(), spec.cwd(), spec.env());
            transport = AcpTransport.forProcess(started);
            client = new AcpClient(transport);
            client.setPermissionBridge(new AcpPermissionBridge(
                    permissionGate, parentTuc, spec.cwd(), spec.permissionPolicy()));
            AcpClient boundClient = client;
            client.setUpdateListener(update ->
                    onUpdate(update, state, sink, sessionId, assistantMessageId));
            // 父轮若在启动前就已取消，attach 会立即收尾（下面 initialize 随即因 EOF 失败）
            abortBridge.attach(client, started);
            transport.start();

            client.initialize(INIT_TIMEOUT_MS);
            client.newSession(spec.cwd(), SESSION_TIMEOUT_MS);

            JsonNode promptResult;
            try {
                promptResult = boundClient.prompt(prompt, spec.timeoutMs());
            } catch (AcpException e) {
                if (e.getMessage() != null && e.getMessage().contains("超时")) {
                    timedOut = true;
                    boundClient.cancel();
                }
                throw e;
            }

            promptFinished = true;
            String stopReason = promptResult == null ? "" : promptResult.path("stopReason").asText("");
            String status = mapStopReason(stopReason);
            String summary = state.text.toString();
            long durationMs = System.currentTimeMillis() - startMs;

            if (summary.isBlank()) {
                String stderr = started.stderrTail(800);
                recordTranscript(sessionId, agentId, description, def, state, true,
                        "ACP 对端未返回正文（stopReason=" + stopReason + "）", spec.cwd());
                return fail("ACP 运行时未返回任何正文（stopReason=" + stopReason
                                + (stderr.isBlank() ? "" : "；stderr: " + stderr) + "）",
                        agentId, startMs);
            }

            recordTranscript(sessionId, agentId, description, def, state, false, null, spec.cwd());
            log.info("[AcpAgentRunner] ACP 子代理结束: stopReason={} toolCalls={} textLen={} durationMs={} agentId={}",
                    stopReason, state.toolCount(), summary.length(), durationMs, agentId);
            return new SubagentExecutor.SubagentResult(
                    truncate(summary), state.toolCount(), durationMs, agentId,
                    status, 0L, AgentUsage.EMPTY);

        } catch (AcpException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            String stderr = started == null ? "" : started.stderrTail(800);
            boolean aborted = abortBridge.isCancelled();
            String message = aborted
                    ? "ACP 子代理被父轮取消（已发 session/cancel 并终止进程树）: " + e.getMessage()
                    : (timedOut ? "ACP 子代理超时（" + spec.timeoutMs() + "ms），已终止进程树: "
                            : "ACP 子代理失败: ") + e.getMessage();
            message = message + (stderr.isBlank() ? "" : "；stderr: " + stderr);
            recordTranscript(sessionId, agentId, description, def, state, true, message, spec.cwd());
            // 父轮取消 ⇒ aborted；超时与协议失败 ⇒ failed（⛔ 不把没干净收尾当 completed）
            return new SubagentExecutor.SubagentResult(message, state.toolCount(), durationMs, agentId,
                    aborted ? "aborted" : "failed", 0L, AgentUsage.EMPTY);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            boolean aborted = abortBridge.isCancelled();
            String message = (aborted ? "ACP 子代理被父轮取消: " : "ACP 子代理异常: ") + e.getMessage();
            recordTranscript(sessionId, agentId, description, def, state, true, message, spec.cwd());
            return new SubagentExecutor.SubagentResult(message, state.toolCount(), durationMs, agentId,
                    aborted ? "aborted" : "failed", 0L, AgentUsage.EMPTY);
        } finally {
            abortBridge.unbind();
            if (!promptFinished && client != null && transport != null && !transport.isClosed()) {
                try {
                    // 只在中途失败/超时时尽力取消；正常收尾（end_turn 已返回）不再发 cancel
                    client.cancel();
                } catch (RuntimeException ignore) {
                    // 尽力而为
                }
            }
            // ⛔ 顺序不能反：先 quiesce（关 stdin → 宽限 → 杀树）让子进程退出，reader 自然
            //   EOF；再关传输。反序会撞「close stdout 与 readLine 互等」的死锁（Windows）。
            if (started != null) {
                AcpProcess.quiesce(started.process(), EOF_GRACE_MS);
                started.awaitStderr(1_500L);
            }
            if (transport != null) {
                try {
                    transport.close();
                } catch (RuntimeException ignore) {
                    // 已关闭
                }
            }
        }
    }

    private static String safeAssistantId(AcpEventSink sink, String sessionId) {
        if (sink == null) {
            return null;
        }
        try {
            return sink.resolveAssistantMessageId(sessionId);
        } catch (RuntimeException e) {
            log.debug("[AcpAgentRunner] 解析 assistantMessageId 失败: {}", e.getMessage());
            return null;
        }
    }

    private static void onUpdate(JsonNode update, RunState state, AcpEventSink sink,
                                 String sessionId, String assistantMessageId) {
        AcpEventMapper.MappedUpdate mapped = AcpEventMapper.map(update);
        switch (mapped.kind()) {
            case CHUNK -> {
                String text = mapped.text();
                if (text != null && !text.isEmpty()) {
                    state.text.append(text);
                    sink.chunk(sessionId, assistantMessageId, text, null);
                }
            }
            case REASONING -> {
                String text = mapped.text();
                if (text != null && !text.isEmpty()) {
                    state.reasoning.append(text);
                    sink.chunk(sessionId, assistantMessageId, null, text);
                }
            }
            case TOOL_CALL -> {
                state.startTool(mapped.toolCallId(), mapped.toolName(),
                        mapped.arguments(), mapped.status());
                sink.toolCall(sessionId, assistantMessageId, mapped.toolCallId(),
                        mapped.toolName(), mapped.arguments());
            }
            case TOOL_RESULT -> {
                long durationMs = state.finishTool(mapped.toolCallId(), mapped.text(),
                        Boolean.TRUE.equals(mapped.isError()), mapped.status());
                sink.toolResult(sessionId, assistantMessageId, mapped.toolCallId(),
                        mapped.text(), Boolean.TRUE.equals(mapped.isError()), durationMs);
            }
            default -> {
                // usage / plan / config_option_update / 未知：丢弃
            }
        }
    }

    /** ACP stopReason → 本仓 status。⛔ 未知一律 failed，绝不把没干净收尾当 completed。 */
    static String mapStopReason(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return "failed";
        }
        return switch (stopReason) {
            case "end_turn" -> "completed";
            case "cancelled" -> "aborted";
            default -> "failed";
        };
    }

    private static void recordTranscript(String sessionId, String agentId, String description,
                                         AgentDefinition.ExternalAgentDefinition def,
                                         RunState state, boolean isError, String errorText, Path cwd) {
        if (sessionId == null || agentId == null || state.transcriptWritten) {
            return;
        }
        state.transcriptWritten = true;
        try {
            Path sessionDir = ResumeService.resolveSessionDir(sessionId);
            AgentTranscript.writeMetadata(sessionDir, sessionId, agentId,
                    new AgentTranscript.AgentMetadata(def.agentType(), null, description,
                            cwd != null ? cwd.toString() : null));
            String ts = OffsetDateTime.now().toString();
            List<Map<String, Object>> messages = new ArrayList<>();
            if (state.reasoning.length() > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", "");
                m.put("reasoning", state.reasoning.toString());
                m.put("timestamp", ts);
                messages.add(m);
            }
            if (!state.tools.isEmpty()) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (Map.Entry<String, ToolEntry> entry : state.tools.entrySet()) {
                    ToolEntry tool = entry.getValue();
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", entry.getKey());
                    one.put("name", tool.name);
                    if (tool.arguments != null) {
                        one.put("arguments", tool.arguments);
                    }
                    if (tool.result != null) {
                        one.put("result", tool.result);
                    }
                    if (tool.isError) {
                        one.put("isError", true);
                    }
                    tcs.add(one);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", "");
                m.put("toolCalls", tcs);
                m.put("timestamp", ts);
                messages.add(m);
            }
            if (state.text.length() > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", state.text.toString());
                m.put("timestamp", ts);
                messages.add(m);
            }
            if (isError) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("role", "assistant");
                err.put("content", errorText == null ? "ACP 执行失败" : errorText);
                err.put("isError", true);
                err.put("timestamp", ts);
                messages.add(err);
            }
            if (!messages.isEmpty()) {
                AgentTranscript.recordSidechainTranscript(sessionDir, sessionId, agentId, messages);
            }
        } catch (Exception e) {
            // transcript 失败绝不掩盖真实结果
            log.warn("[AcpAgentRunner] transcript 落盘失败 agentId={}: {}", agentId, e.getMessage());
        }
    }

    private static SubagentExecutor.SubagentResult fail(String message, String agentId, long startMs) {
        log.warn("[AcpAgentRunner] ACP 子代理失败: {}", message);
        return new SubagentExecutor.SubagentResult(message, 0,
                System.currentTimeMillis() - startMs, agentId, "failed", 0L, AgentUsage.EMPTY);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= RESULT_LIMIT) {
            return s;
        }
        int head = RESULT_LIMIT * 2 / 3;
        int tail = RESULT_LIMIT - head;
        return s.substring(0, head) + "\n…[已截断 " + (s.length() - RESULT_LIMIT) + " 字符]…\n"
                + s.substring(s.length() - tail);
    }

    /** 一次委派的累积状态（正文 / 推理 / 工具）。 */
    static final class RunState {
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final Map<String, ToolEntry> tools = new LinkedHashMap<>();
        boolean transcriptWritten;

        int toolCount() {
            return tools.size();
        }

        void startTool(String toolCallId, String name, Map<String, Object> arguments, String status) {
            if (toolCallId == null || toolCallId.isBlank()) {
                return;
            }
            ToolEntry entry = tools.computeIfAbsent(toolCallId, id -> new ToolEntry());
            if (name != null && !name.isBlank()) {
                entry.name = name;
            }
            if (arguments != null && !arguments.isEmpty()) {
                entry.arguments = arguments;
            }
            entry.status = status;
        }

        /** 标记工具终态，返回本轮耗时（毫秒；未知开始时间返回 null）。 */
        Long finishTool(String toolCallId, String result, boolean isError, String status) {
            if (toolCallId == null || toolCallId.isBlank()) {
                return null;
            }
            ToolEntry entry = tools.computeIfAbsent(toolCallId, id -> new ToolEntry());
            entry.result = result;
            entry.isError = isError;
            entry.status = status;
            return entry.durationMs();
        }
    }

    /** 单个工具的累积记录。 */
    static final class ToolEntry {
        String name = "acp-tool";
        Map<String, Object> arguments;
        String result;
        boolean isError;
        String status;
        final long startMs = System.currentTimeMillis();
        private Long endMs;

        Long durationMs() {
            if (endMs == null) {
                endMs = System.currentTimeMillis();
            }
            return endMs - startMs;
        }
    }
}

package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@code mcp__computer-use__wait} · 等待工具（对齐 CCB 2.8.4 computer-use MCP 的 {@code wait}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:896-906}）</h2>
 * <pre>
 * name: 'wait',
 * description: 'Wait for a specified duration.',
 * inputSchema: {
 *   type: 'object',
 *   properties: { duration: { type: 'number', description: 'Duration in seconds (0–100).' } },
 *   required: ['duration'],
 * }
 * </pre>
 * <p>⚠️ 描述里的 {@code 0–100} 用的是<b>en dash（U+2013）</b>，不是连字符 ——
 * 与 CCB 逐字一致（已核 CCB 源码的码位）。
 *
 * <h2>行为（逐字对齐 CCB {@code toolCalls.ts:3636-3660} 的 {@code handleWait}）</h2>
 * <ol>
 *   <li>{@code duration} 非有限数 ⇒ {@code "duration must be a number"}；</li>
 *   <li>{@code duration < 0} ⇒ {@code "duration must be non-negative"}；</li>
 *   <li>{@code duration > 100} ⇒ {@code "duration is too long. Duration is in seconds."}；</li>
 *   <li>{@code sleep(duration * 1000)}；</li>
 *   <li>返回 {@code "Waited {duration}s."}。</li>
 * </ol>
 *
 * <p><b>为什么没有前台门控</b>：CCB 注释原文（{@code toolCalls.ts:3631-3634}）：
 * “No frontmost gate — no input, nothing to protect.” ⇒ 本仓同款<b>不做</b>门控
 * （等待不触碰任何应用）。
 */
public final class ComputerUseWaitTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseWaitTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__wait";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:897}。 */
    private static final String DESCRIPTION = "Wait for a specified duration.";

    /**
     * 输入 schema · 逐字对齐 CCB {@code tools.ts:898-905}（{@code 0–100} 的 en dash 逐字保留）。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "duration": {
                  "type": "number",
                  "description": "Duration in seconds (0–100)."
                }
              },
              "required": ["duration"]
            }
            """;

    /** 时长上限（秒）· CCB {@code toolCalls.ts:3652}。 */
    static final double MAX_DURATION_SECONDS = 100d;

    private final ComputerUseSleeper sleeper;

    /**
     * @param sleeper 休眠器（生产用 {@link ComputerUseSleeper#REAL}；单测注入假时钟）
     */
    public ComputerUseWaitTool(ComputerUseSleeper sleeper) {
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] wait inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只等不做事 ⇒ 只读、并发安全。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 等待不触碰系统状态 ⇒ <b>权限自决 Allow（免弹窗）</b> · 与其它 computer-use 工具同款。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use wait does not touch the system state"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * 执行等待。
     *
     * @param call 工具调用
     * @param ctx  工具上下文（本工具不用）
     * @return 结果
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode durationNode = call.input().path("duration");
        if (!durationNode.isNumber() || !Double.isFinite(durationNode.asDouble())) {
            return ToolResult.error(call.id(), "duration must be a number");
        }
        double duration = durationNode.asDouble();
        if (duration < 0) {
            return ToolResult.error(call.id(), "duration must be non-negative");
        }
        if (duration > MAX_DURATION_SECONDS) {
            return ToolResult.error(call.id(), "duration is too long. Duration is in seconds.");
        }
        try {
            sleeper.sleep(Math.round(duration * 1000d));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[computer-use] {} 被中断", TOOL_NAME);
            return ToolResult.error(call.id(), "等待被中断。");
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 等待 {} 秒", TOOL_NAME, formatDuration(duration));
        }
        // CCB toolCalls.ts:3659 成功文案逐字对齐："Waited Ns."
        return ToolResult.success(call.id(), "Waited " + formatDuration(duration) + "s.");
    }

    /**
     * 时长格式化 · 对齐 JS 的 {@code Number → String}（CCB 模板串 {@code `Waited ${duration}s.`}）。
     *
     * <p>JS 里 {@code 2} 打成 {@code "2"}、{@code 2.5} 打成 {@code "2.5"} ⇒
     * Java 侧不能直接用 {@code Double.toString}（会把 {@code 2.0} 打成 {@code "2.0"}）。
     *
     * @param duration 秒数
     * @return 与 JS 一致的字符串
     */
    static String formatDuration(double duration) {
        if (duration == Math.floor(duration) && !Double.isInfinite(duration)
                && Math.abs(duration) < 1e15) {
            return Long.toString((long) duration);
        }
        return Double.toString(duration);
    }
}

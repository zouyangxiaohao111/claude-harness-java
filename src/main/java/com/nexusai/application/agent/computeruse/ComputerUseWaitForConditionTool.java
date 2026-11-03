package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpStringUtils;
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
 * {@code mcp__computer-use__wait_for} · <b>按条件等待</b>辅助功能状态
 * （对齐 dsh {@code dsh-computer-use} 的 {@code computer_wait}）。
 *
 * <h2>WHY 需要它（而不只是 {@code wait} 的固定 sleep）</h2>
 * <p>固定 sleep 有两个问题：睡短了要重试（多轮往返），睡长了白等。
 * <p>条件等待让模型能说「等这个按钮出现 / 等这段文字出现」⇒ <b>一次调用</b>等到状态满足，
 * 且返回<b>届时的最新树</b>（模型不用再 observe 一次）。
 *
 * <h2>语义（逐条对齐 dsh {@code service.ts:188-193} 的 {@code matchesWait}）</h2>
 * <ul>
 *   <li>{@code text}：树文本<b>包含</b>该子串（大小写不敏感）；</li>
 *   <li>{@code element_role}：存在该<b>角色</b>的元素；</li>
 *   <li>{@code element_title}：存在该<b>标题/名字</b>的元素；</li>
 *   <li>三个条件<b>全部</b>给定时取<b>合取</b>；一个都不给 ⇒ 永不匹配（dsh 同款）。</li>
 * </ul>
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li>dsh 的 {@code computer_wait} 必须带 {@code observationId}（用于限定「在哪个应用里等」）；
 *       本仓观察对象固定为<b>前台应用</b>（与所有输入动作同一对象）⇒ 不需要 observationId。</li>
 *   <li>dsh 超时抛 {@code COMPUTER_TIMEOUT}；本仓超时时<b>返回最后一次快照 + 明确说明未满足</b>
 *       （让模型能看到「现在是什么状态」再决定下一步），不抛异常。</li>
 * </ul>
 */
public final class ComputerUseWaitForConditionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseWaitForConditionTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "wait_for");

    /** 工具描述。 */
    private static final String DESCRIPTION =
            "Wait until the frontmost app's accessibility state satisfies a condition — a piece of text "
                    + "appears, an element with a given role shows up, or an element with a given title "
                    + "appears. Returns the fresh tree when the condition is met. Use this instead of a "
                    + "fixed sleep so you do not poll blindly.";

    /** 输入 schema。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Wait until the accessibility tree contains this text (case-insensitive)."
                },
                "element_role": {
                  "type": "string",
                  "description": "Wait until an element with this role exists (e.g. \\"Button\\", \\"StaticText\\")."
                },
                "element_title": {
                  "type": "string",
                  "description": "Wait until an element with this accessible name/title exists."
                },
                "timeout_ms": {
                  "type": "integer",
                  "description": "Maximum wait in milliseconds. Defaults to 10000."
                }
              },
              "required": []
            }
            """;

    /** 默认超时（毫秒）。 */
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    /** 超时上限（毫秒）—— 防止模型请求一个几小时的等待。 */
    public static final int MAX_TIMEOUT_MS = 120_000;

    private final ComputerUseUiaService uiaService;

    /**
     * @param uiaService AX/UIA 服务
     */
    public ComputerUseWaitForConditionTool(ComputerUseUiaService uiaService) {
        this.uiaService = uiaService;
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
            throw new IllegalStateException(
                    "[computer-use] wait_for inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 只读（不改变应用状态）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use wait_for is read-only"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String text = input.path("text").isTextual() ? input.path("text").asText() : null;
        String role = input.path("element_role").isTextual() ? input.path("element_role").asText() : null;
        String title = input.path("element_title").isTextual() ? input.path("element_title").asText() : null;
        int timeoutMs = input.path("timeout_ms").asInt(DEFAULT_TIMEOUT_MS);
        timeoutMs = Math.max(0, Math.min(timeoutMs, MAX_TIMEOUT_MS));

        if ((text == null || text.isEmpty())
                && (role == null || role.isEmpty())
                && (title == null || title.isEmpty())) {
            // dsh matchesWait：三个条件都没给 ⇒ 永不匹配 ⇒ 直接报错而不是白等到超时
            return ToolResult.error(call.id(),
                    "At least one of text, element_role, or element_title is required.");
        }
        ComputerUseAccessibilitySnapshot snapshot =
                uiaService.waitForCondition(0L, text, role, title, timeoutMs);
        if (snapshot == null) {
            // 平台不支持条件等待（Windows 侧默认实现返回 null）
            return ToolResult.error(call.id(), "wait_for is not supported on this platform.");
        }
        boolean satisfied = matches(snapshot, text, role, title);
        if (!satisfied) {
            return ToolResult.error(call.id(),
                    "Timed out after " + timeoutMs + "ms waiting for the condition. Last observed state:\n"
                            + snapshot.text());
        }
        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 条件满足: text={} role={} title={} 超时上限={}ms",
                    TOOL_NAME, text, role, title, timeoutMs);
        }
        return ToolResult.success(call.id(), snapshot.text());
    }

    /**
     * 判断快照是否满足条件（与 {@link MacOsUiaService#matchesCondition} 同语义）。
     *
     * <p>抽成静态方法以便本类与实现类共用同一套判定。
     */
    private static boolean matches(ComputerUseAccessibilitySnapshot snapshot,
                                   String text,
                                   String role,
                                   String title) {
        return MacOsUiaService.matchesCondition(snapshot, text, role, title);
    }
}

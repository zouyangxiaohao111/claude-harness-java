package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * PushNotification Tool 真实现 · 对齐 CC {@code Open-ClaudeCode/src/tools/PushNotificationTool/PushNotificationTool.ts}
 * （G32① 按真源重写，G11 改名 PascalCase）。
 *
 * <p><b>WHY（G32①）</b>: CC 真源已就位（PushNotificationTool.ts，149 行）——给用户移动设备发推送，
 * 输出 {@code {sent: boolean}}。原 fail-loud 注册桩（WFI-R1）替换为真实现。
 *
 * <p><b>门控语义</b>: bean 创建由 {@link KairosOrPushNotificationEnabledCondition}（CC tools.ts:45-48
 * {@code feature('KAIROS') || feature('KAIROS_PUSH_NOTIFICATION')} 模块门控）；{@link #isEnabled()} 对齐
 * CC {@code isBridgeEnabled()}（PushNotificationTool.ts:52-54）——Java 无 BRIDGE_MODE 构建 feature /
 * claude.ai 订阅判定 / GrowthBook 门，单一配置 {@code nexusai.bridge.enabled} 承载（默认 false →
 * 工具不暴露，CC isBridgeEnabled()=false 等价）。
 *
 * <p>CC 注册点: Open-ClaudeCode/src/tools.ts:240。
 *
 * <p><b>受控残留</b>: CC bridge 投递（PushNotificationTool.ts:87-135）向
 * {@code ${bridgeBaseUrl}/v1/sessions/${sessionId}/events} POST 推送事件；Java 无 Remote Control bridge
 * 客户端（baseUrl/token/sessionId 通道未接线）→ 可到达路径恒为 CC 无 bridge 回退
 * {@code {sent:false, error:'No Remote Control bridge configured. Notification not delivered.'}}
 * （PushNotificationTool.ts:138-147）。bridge 客户端属待接线基础设施。
 */
@Component
@Conditional(KairosOrPushNotificationEnabledCondition.class)
public class PushNotificationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationTool.class);

    /** CC 工具名 · {@code PushNotificationTool.ts:9} PUSH_NOTIFICATION_TOOL_NAME='PushNotification'。 */
    public static final String NAME = ToolNameConstants.PUSH_NOTIFICATION_TOOL_NAME;

    /** CC original: maxResultSizeChars=1_000（PushNotificationTool.ts:31）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 1_000L;

    /** bridge 能力开关 · 对齐 CC {@code isBridgeEnabled()}（bridgeEnabled.ts:20-28）的 Java 单配置承载。 */
    @Value("${nexusai.bridge.enabled:false}")
    boolean bridgeEnabled;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Send a push notification to the user's mobile device";
    }

    /** 搜索提示 · 对齐 CC PushNotificationTool.ts:30 searchHint。 */
    @Override
    public String searchHint() {
        return "push notification mobile alert notify user";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** 是否启用 · 对齐 CC PushNotificationTool.ts:52-54 isEnabled() = isBridgeEnabled()。 */
    @Override
    public boolean isEnabled() {
        return bridgeEnabled;
    }

    /** 可并发 · 对齐 CC PushNotificationTool.ts:55-57 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC PushNotificationTool.ts:58-60 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 用户可见名 · 对齐 CC PushNotificationTool.ts:62-64 userFacingName() → 'Notify'。 */
    @Override
    public String userFacingName() {
        return "Notify";
    }

    /** 工具提示词 · 对齐 CC PushNotificationTool.ts:41-50 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                Send a push notification to the user's mobile device via Remote Control.

                Use this when:
                - A long-running task completes and the user may not be watching
                - A permission prompt is waiting and you need user input
                - Something urgent requires the user's attention

                Requires Remote Control to be configured. Respects user notification settings (taskCompleteNotifEnabled, inputNeededNotifEnabled, agentPushNotifEnabled).""";
    }

    /** 工具使用消息渲染 · 对齐 CC PushNotificationTool.ts:66-68（title 缺省 '...'）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        String title = input != null && input.has("title") ? input.get("title").asText() : "...";
        return "Push: " + title;
    }

    /** 输入 schema · 对齐 CC PushNotificationTool.ts:11-22 {@code z.strictObject({title, body, priority?})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode title = props.putObject("title");
        title.put("type", "string");
        title.put("description", "Title of the push notification.");

        ObjectNode body = props.putObject("body");
        body.put("type", "string");
        body.put("description", "Body text of the push notification.");

        ObjectNode priority = props.putObject("priority");
        priority.put("type", "string");
        priority.putArray("enum").add("normal").add("high");
        priority.put("description",
                "Notification priority. Use \"high\" for blockers or permission prompts.");

        schema.putArray("required").add("title").add("body");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 结果块渲染 · 对齐 CC PushNotificationTool.ts:70-81 mapToolResultToToolResultBlockParam：
     * sent → 'Notification sent.'；否则 'Failed to send notification.'。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                    ? ToolResult.renderToolResultPayloadText(tr) : "Failed to send notification.";
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        boolean sent = false;
        if (result != null && result.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                sent = node.path("sent").asBoolean(false);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[PushNotification] 结果解析失败: {}", e.toString());
                }
            }
        }
        return new ToolResultBlockParam(toolUseId, "tool_result",
                sent ? "Notification sent." : "Failed to send notification.", false);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC PushNotificationTool.ts:83-148 call — bridge 投递优先，无 bridge 回退。
     *
     * <p>Java 无 Remote Control bridge 客户端 → 恒走 CC 无 bridge 回退
     * {@code {sent:false, error:'No Remote Control bridge configured. Notification not delivered.'}}。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String title = call.input() != null && call.input().has("title")
                ? call.input().get("title").asText() : "";
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("sent", false);
        out.put("error", "No Remote Control bridge configured. Notification not delivered.");
        if (log.isInfoEnabled()) {
            log.info("[PushNotification] 无 Remote Control bridge 客户端，通知未投递: title='{}'（CC "
                    + "PushNotificationTool.ts:138-147 无 bridge 回退语义）", title);
        }
        return ToolResult.success(call.id(), out.toString());
    }
}

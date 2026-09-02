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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SubscribePR Tool 真实现 · 对齐 CC {@code Open-ClaudeCode/src/tools/SubscribePRTool/SubscribePRTool.ts}
 * （G32① 按真源重写，G11 改名 PascalCase）。
 *
 * <p><b>WHY（G32①）</b>: CC 真源已就位（SubscribePRTool.ts，83 行）——GitHub PR 事件订阅工具，
 * 输出 {@code {subscribed, subscription_id}}。原 fail-loud 注册桩（WFI-R1）替换为真实现。
 *
 * <p><b>门控语义</b>: {@code nexusai.feature.kairos-github-webhooks=true} 时 bean 创建
 * （CC tools.ts:50-51 {@code feature('KAIROS_GITHUB_WEBHOOKS')} 模块门控），{@link #isEnabled()}
 * 默认 true（CC descriptor 无 isEnabled override）。
 *
 * <p>CC 注册点: Open-ClaudeCode/src/tools.ts:241。
 *
 * <p><b>CC 真行为</b>: call 恒返回 {@code {subscribed:false, subscription_id:'',
 * error:'SubscribePR requires the KAIROS GitHub webhook subsystem.'}}（SubscribePRTool.ts:72-82）——
 * webhook 订阅由 KAIROS GitHub webhook 子系统管理，无 KAIROS 运行时即不可用。Java 无 KAIROS
 * webhook 子系统 → 同一不可用回退（非 fail-loud 桩，是 CC 真源行为）。
 */
@Component
@ConditionalOnProperty(prefix = "nexusai.feature", name = "kairos-github-webhooks",
        havingValue = "true", matchIfMissing = false)
public class SubscribePRTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SubscribePRTool.class);

    /** CC 工具名 · {@code SubscribePRTool.ts:6} SUBSCRIBE_PR_TOOL_NAME='SubscribePR'。 */
    public static final String NAME = ToolNameConstants.SUBSCRIBE_PR_TOOL_NAME;

    /** CC original: maxResultSizeChars=5_000（SubscribePRTool.ts:26）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 5_000L;

    /** CC error 文案 · SubscribePRTool.ts:79。 */
    private static final String NOT_AVAILABLE_ERROR =
            "SubscribePR requires the KAIROS GitHub webhook subsystem.";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Subscribe to pull request events via GitHub webhooks";
    }

    /** 搜索提示 · 对齐 CC SubscribePRTool.ts:25 searchHint。 */
    @Override
    public String searchHint() {
        return "subscribe pull request github webhook events watch";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** 可并发 · 对齐 CC SubscribePRTool.ts:42-44 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC SubscribePRTool.ts:45-47 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 用户可见名 · 对齐 CC SubscribePRTool.ts:49-51 userFacingName() → 'SubscribePR'。 */
    @Override
    public String userFacingName() {
        return NAME;
    }

    /** 工具提示词 · 对齐 CC SubscribePRTool.ts:36-40 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                Subscribe to events on a GitHub pull request. You'll receive notifications when selected events occur (comments, reviews, CI status changes, merge, close).

                Use this to monitor PRs you've created or are reviewing. Events are delivered as messages you can act on.""";
    }

    /** 工具使用消息渲染 · 对齐 CC SubscribePRTool.ts:53-57（repo/pr_number 缺省 '...'）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        String repo = input != null && input.has("repo") ? input.get("repo").asText() : null;
        boolean hasPr = input != null && input.has("pr_number") && input.get("pr_number").isNumber();
        String pr = repo != null && hasPr
                ? repo + "#" + input.get("pr_number").asText()
                : "...";
        return "Subscribe PR: " + pr;
    }

    /** 输入 schema · 对齐 CC SubscribePRTool.ts:8-17 {@code z.strictObject({repo, pr_number, events?})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode repo = props.putObject("repo");
        repo.put("type", "string");
        repo.put("description", "Repository in owner/repo format.");

        ObjectNode prNumber = props.putObject("pr_number");
        prNumber.put("type", "integer");
        prNumber.put("description", "Pull request number to subscribe to.");

        ObjectNode events = props.putObject("events");
        events.put("type", "array");
        ObjectNode items = JsonNodeFactory.instance.objectNode();
        items.put("type", "string");
        items.putArray("enum").add("comment").add("review").add("ci").add("merge").add("close");
        events.set("items", items);
        events.put("description", "Event types to subscribe to. Defaults to all events.");

        schema.putArray("required").add("repo").add("pr_number");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 结果块渲染 · 对齐 CC SubscribePRTool.ts:59-70 mapToolResultToToolResultBlockParam：
     * subscribed → 'Subscribed to PR events (id: X)'；否则 'Failed to subscribe to PR events.'。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                    ? ToolResult.renderToolResultPayloadText(tr) : "Failed to subscribe to PR events.";
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        boolean subscribed = false;
        String subscriptionId = "";
        if (result != null && result.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                subscribed = node.path("subscribed").asBoolean(false);
                subscriptionId = node.path("subscription_id").asText("");
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubscribePR] 结果解析失败: {}", e.toString());
                }
            }
        }
        String content = subscribed
                ? "Subscribed to PR events (id: " + subscriptionId + ")"
                : "Failed to subscribe to PR events.";
        return new ToolResultBlockParam(toolUseId, "tool_result", content, false);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC SubscribePRTool.ts:72-82 call — KAIROS webhook 子系统管理订阅，无 KAIROS 不可用。
     *
     * <p>Java 无 KAIROS GitHub webhook 子系统 → 恒返回 CC 真源不可用回退
     * {@code {subscribed:false, subscription_id:'', error:...}}。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("subscribed", false);
        out.put("subscription_id", "");
        out.put("error", NOT_AVAILABLE_ERROR);
        if (log.isInfoEnabled()) {
            log.info("[SubscribePR] 无 KAIROS GitHub webhook 子系统，订阅不可用（CC "
                    + "SubscribePRTool.ts:72-82 真源回退语义）");
        }
        return ToolResult.success(call.id(), out.toString());
    }
}

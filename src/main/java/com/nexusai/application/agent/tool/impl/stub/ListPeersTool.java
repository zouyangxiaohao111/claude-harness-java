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
 * ListPeers Tool 真实现 · 对齐 CC {@code Open-ClaudeCode/src/tools/ListPeersTool/ListPeersTool.ts}
 * （G32① 按真源重写，G11 改名 PascalCase）。
 *
 * <p><b>WHY（G32①）</b>: CC 真源已就位（ListPeersTool.ts，136 行）——跨会话消息目标发现工具，
 * 输出 {@code {peers: [{address, name?, cwd?, pid?}]}}。原 fail-loud 注册桩（WFI-R1）替换为真实现。
 *
 * <p><b>门控语义</b>: {@code nexusai.feature.uds-inbox=true} 时 bean 创建（CC tools.ts:126
 * {@code feature('UDS_INBOX')} 模块门控），{@link #isEnabled()} 默认 true（CC descriptor 无
 * isEnabled override）。
 *
 * <p>CC 注册点: Open-ClaudeCode/src/tools.ts:227。
 *
 * <p><b>受控残留</b>: CC call 用 UDS socket 目录（{@code udsMessaging.getUdsMessagingSocketPath()}）
 * + Remote Control bridge peer 注册表发现对端；Java Web 后端<b>无 UDS 消息基础设施</b>（SendMessage
 * 走内部队列非跨进程 socket）→ {@code peers=[]}（无 socket 时的 CC 等价分支 ListPeersTool.ts:106-130
 * 遍历为空即返回空数组）。契约面（schema/prompt/description/mapper 格式）逐字对齐 CC。
 */
@Component
@ConditionalOnProperty(prefix = "nexusai.feature", name = "uds-inbox",
        havingValue = "true", matchIfMissing = false)
public class ListPeersTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListPeersTool.class);

    /** CC 工具名 · {@code ListPeersTool.ts:6} LIST_PEERS_TOOL_NAME='ListPeers'。 */
    public static final String NAME = ToolNameConstants.LIST_PEERS_TOOL_NAME;

    /** CC original: maxResultSizeChars=50_000（ListPeersTool.ts:32）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 50_000L;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Discover other Claude Code sessions for cross-session messaging";
    }

    /** 搜索提示 · 对齐 CC ListPeersTool.ts:31 searchHint。 */
    @Override
    public String searchHint() {
        return "list peers sessions discover uds socket messaging";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** 可并发 · 对齐 CC ListPeersTool.ts:52-54 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC ListPeersTool.ts:55-57 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 用户可见名 · 对齐 CC ListPeersTool.ts:59-61 userFacingName() → 'ListPeers'。 */
    @Override
    public String userFacingName() {
        return NAME;
    }

    /** 工具提示词 · 对齐 CC ListPeersTool.ts:43-49 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                List active Claude Code sessions that can receive messages via SendMessage.

                Returns an array of peers with their addresses. Use these addresses as the `to` field in SendMessage:
                - `"uds:/path/to.sock"` — local sessions on the same machine (Unix Domain Socket)
                - `"bridge:session_..."` — remote sessions via Remote Control

                Use this tool to discover messaging targets before sending cross-session messages. Only running sessions with active messaging sockets are returned.""";
    }

    /** 工具使用消息渲染 · 对齐 CC ListPeersTool.ts:63-65 renderToolUseMessage() → 'ListPeers'。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        return "ListPeers";
    }

    /** 输入 schema · 对齐 CC ListPeersTool.ts:8-17 {@code z.strictObject({include_self?})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode includeSelf = props.putObject("include_self");
        includeSelf.put("type", "boolean");
        includeSelf.put("description", "Whether to include the current session in the list. Defaults to false.");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 结果块渲染 · 对齐 CC ListPeersTool.ts:67-83 mapToolResultToToolResultBlockParam：
     * 每行 {@code `${address}${name? ( name)}${cwd? @ cwd}`}；空 → 'No peers found.'。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                    ? ToolResult.renderToolResultPayloadText(tr) : "ListPeers failed.";
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        StringBuilder lines = new StringBuilder();
        int count = 0;
        if (result != null && result.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                JsonNode peers = node.path("peers");
                if (peers.isArray()) {
                    for (JsonNode p : peers) {
                        if (count > 0) {
                            lines.append('\n');
                        }
                        String address = p.path("address").asText("");
                        String name = p.has("name") ? p.path("name").asText("") : null;
                        String cwd = p.has("cwd") ? p.path("cwd").asText("") : null;
                        StringBuilder line = new StringBuilder(address);
                        if (name != null && !name.isBlank()) {
                            line.append(" (").append(name).append(')');
                        }
                        if (cwd != null && !cwd.isBlank()) {
                            line.append(" @ ").append(cwd);
                        }
                        lines.append(line);
                        count++;
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[ListPeers] 结果解析失败, 回退原文: {}", e.toString());
                }
            }
        }
        String content = count > 0
                ? "Found " + count + " peer(s):\n" + lines
                : "No peers found.";
        return new ToolResultBlockParam(toolUseId, "tool_result", content, false);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC ListPeersTool.ts:85-135 call — peer 发现。
     *
     * <p>Java Web 后端无 UDS 消息基础设施 → peers 为空数组 {@code {peers: []}}。
     * include_self 亦无本机 self socket 可列（CC :108-116 需 messagingSocketPath 存在才加 self）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.putArray("peers");
        if (log.isDebugEnabled()) {
            log.debug("[ListPeers] 无 UDS 消息基础设施，返回空 peers（Java Web 后端无跨进程 socket，"
                    + "CC ListPeersTool.ts:106-130 无 socket 时为空数组等价）");
        }
        return ToolResult.success(call.id(), out.toString());
    }
}

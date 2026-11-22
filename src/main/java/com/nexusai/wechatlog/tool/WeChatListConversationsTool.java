package com.nexusai.wechatlog.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.wechatlog.service.WeChatMessageIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 工具：列出本机微信的会话（按最近消息倒序）。
 *
 * <p>用途：模型不知道有哪些会话时，先调这个拿到 conversation_id，再用
 * {@link WeChatSearchMessagesTool} 或按会话读消息。避免盲目猜 wxid。
 */
@Component
public class WeChatListConversationsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WeChatListConversationsTool.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    @Autowired
    private WeChatMessageIndexer indexer;

    @Value("${nexusai.wechatlog.default-account:}")
    private String defaultAccount;

    @Override
    public String name() {
        return "wechat_list_conversations";
    }

    @Override
    public String description() {
        return "列出本机微信的会话列表（按最近消息时间倒序），"
                + "返回会话标识、展示名、是否群聊、最近消息时间与消息条数。"
                + "在不知道有哪些聊天对象时先用它拿到 conversation_id。";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = JSON.objectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");
        ObjectNode limit = JSON.objectNode();
        limit.put("type", "integer");
        limit.put("description", "返回条数（默认 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "）");
        props.set("limit", limit);
        return root;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        String toolUseId = call.id();
        try {
            JsonNode in = call.input();
            int limit = WeChatSearchMessagesTool.clamp(
                    WeChatSearchMessagesTool.intVal(in, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
            String account = (defaultAccount == null || defaultAccount.isBlank())
                    ? null : defaultAccount;

            List<Map<String, Object>> rows = indexer.listConversations(account, limit);
            if (rows.isEmpty()) {
                return ToolResult.success(toolUseId,
                        "没有已同步的微信会话。请先在设置中执行一次「同步微信聊天记录」。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(rows.size()).append(" 个会话：\n");
            for (Map<String, Object> r : rows) {
                Object conv = r.get("conversation_id");
                Object name = r.get("display_name");
                Object group = r.get("is_group");
                Object last = r.get("last_message_time");
                Object count = r.get("message_count");

                boolean isGroup = group != null && ((Number) group).intValue() == 1;
                sb.append("- ").append(name == null ? conv : name);
                if (isGroup) {
                    sb.append("（群）");
                }
                sb.append("  id=").append(conv);
                sb.append("  最近=").append(WeChatSearchMessagesTool.formatTime(last));
                sb.append("  条数=").append(count == null ? 0 : count);
                sb.append('\n');
            }
            return ToolResult.success(toolUseId, sb.toString().trim());
        } catch (Exception e) {
            log.warn("列出微信会话失败", e);
            return ToolResult.error(toolUseId, "列出微信会话失败: " + e.getMessage());
        }
    }
}

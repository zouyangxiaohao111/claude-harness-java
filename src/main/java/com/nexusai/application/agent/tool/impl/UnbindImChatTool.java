package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.inbound.ImInboundManager;
import com.nexusai.model.im_channel.ImChatEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code UnbindImChat} 工具 · 把 IM 聊天从**当前会话**上解下来（之后消息不再唤醒本会话）。
 *
 * <h2>为什么参数里没有 sessionId</h2>
 * 同 {@link BindImChatTool}：用户裁定「不需要指定会话 id，默认用本地的」。
 * 身份取自 {@link ToolUseContext#sessionId()}，⛔ 不出现在 schema 里。
 *
 * <h2>为什么拒绝解绑「别人接的聊天」</h2>
 * 解绑是一个跨会话可见的动作：一旦解掉，那边就收不到消息了。
 * 若允许模型顺手解掉接在**别的会话**上的聊天，等于「A 会话把 B 会话的耳朵摘了」。
 * ⇒ 判据：{@code bound_session_id} 必须等于当前会话；否则拒绝并说明它接在哪。
 * <p>本来就没接任何会话 ⇒ **幂等成功**（不是一个错误，模型重复调用不该报错）。
 */
@Component
public class UnbindImChatTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(UnbindImChatTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "UnbindImChat";

    public static final String DESCRIPTION =
        "Disconnect (unbind) an IM chat from THIS session. No session id needed — it only unbinds "
            + "chats that belong to the current session; chats bound to another session are refused.";

    public static final String PROMPT =
        "Unbind an IM chat from the **current** session.\n\n"
            + "- `chatId` — take it from `ListImChats`. Never invent one.\n\n"
            + "After unbinding, messages from that chat will no longer wake this session. "
            + "Only chats bound to THIS session can be unbound; if the chat belongs to another "
            + "session the call is refused (it is not yours to detach). "
            + "Unbinding a chat that is not bound at all succeeds without effect.";

    @Autowired private ImChatService chatService;
    @Autowired private ImInboundManager inboundManager;

    /** [测试注入] 聊天目录。 */
    public void setChatService(ImChatService chatService) {
        this.chatService = chatService;
    }

    /** [测试注入] 入站监听管理（接线变了要让监听跟着收敛）。 */
    public void setInboundManager(ImInboundManager inboundManager) {
        this.inboundManager = inboundManager;
    }

    @Override public String name() { return NAME; }

    @Override public String description() { return DESCRIPTION; }

    @Override public String prompt() { return PROMPT; }

    @Override
    public JsonNode inputSchema() {
        var schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("chatId")
            .put("type", "string")
            .put("description", "IM chat id from ListImChats, e.g. `imch-27468836`.");
        var required = schema.putArray("required");
        required.add("chatId");
        return schema;
    }

    /** 写接线 ⇒ 不是只读。 */
    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    /** 断开接线会让那个聊天不再触发本会话 ⇒ 破坏性（可恢复，但要用户知情）。 */
    @Override public boolean isDestructive(JsonNode input) { return true; }

    /**
     * 单参入口：**拿不到当前会话** ⇒ 明确失败（同 {@link BindImChatTool}）。
     *
     * <p>本工具的语义是「从**我**这儿解下来」；没有上下文就无法判定「我」是谁，
     * 静默解绑可能摘掉别人的接线。
     */
    @Override
    public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
        return ToolResult.error(call.id(), "Error: 缺少会话上下文，无法解绑（不要在不带 ctx 的路径调用本工具）");
    }

    @Override
    public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
            ToolUseBlock call, ToolUseContext ctx) {
        String chatId = call.input() == null ? null : text(call.input().path("chatId"));
        if (chatId == null || chatId.isBlank()) {
            return ToolResult.error(call.id(), "Error: chatId 不能为空（用 ListImChats 拿 id）");
        }
        String sessionId = ctx == null ? null : ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error(call.id(), "Error: 当前没有会话上下文，无法解绑");
        }
        try {
            ImChatEntry chat = chatService.findById(chatId).orElse(null);
            if (chat == null) {
                return ToolResult.error(call.id(),
                    "Error: 聊天不存在: " + chatId + "（用 ListImChats 重新取 id）");
            }

            String holder = chat.boundSessionId();
            boolean boundHere = sessionId.equals(holder);
            boolean boundElsewhere = holder != null && !holder.isBlank() && !boundHere;
            if (boundElsewhere) {
                return ToolResult.error(call.id(),
                    "Error: 该聊天接的不是当前会话（它在 " + holder + " 上），不能从这里解绑。");
            }

            boolean wasBound = boundHere;
            if (wasBound) {
                chatService.unbind(chatId);
                reconcile(chat.instanceId());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("unbound", wasBound);
            out.put("chatId", chatId);
            out.put("chatName", chat.displayName());
            out.put("alreadyUnbound", !wasBound);
            out.put("note", wasBound
                ? "已断开：该聊天的消息不再唤醒本会话"
                : "本来就未接线（重复调用无副作用）");
            log.info("[UnbindImChat] chat={} session={} wasBound={}", chatId, sessionId, wasBound);
            return ToolResult.success(call.id(), JSON.writeValueAsString(out));
        } catch (RuntimeException e) {
            log.warn("[UnbindImChat] 解绑失败 chat={} session={} err={}", chatId, sessionId, e.toString());
            return ToolResult.error(call.id(), "Error: 解绑失败 —— " + e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ToolResult.success(call.id(), "{\"unbound\":true,\"chatId\":\"" + chatId + "\"}");
        }
    }

    /** 接线变了 ⇒ 监听收敛；失败只记日志（接线已经写进库了）。 */
    private void reconcile(String instanceId) {
        try {
            inboundManager.reconcile(instanceId);
        } catch (RuntimeException e) {
            log.warn("[UnbindImChat] 监听收敛失败 instance={} err={}", instanceId, e.toString());
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}

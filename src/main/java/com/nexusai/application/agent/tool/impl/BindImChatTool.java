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
 * {@code BindImChat} 工具 · 把一个 IM 聊天**接到当前会话**（接线 = 消息能唤醒本会话）。
 *
 * <h2>为什么参数里没有 sessionId</h2>
 * 用户裁定（2026-11-19）：<b>「不需要指定会话 id，默认用本地的」</b>。
 * 语义锁死为「接给**我自己**」——{@code sessionId} 取自
 * {@link ToolUseContext#sessionId()}，⛔ 不暴露给模型。
 * <p>这样模型不可能把聊天接到一个它顺口编出来的会话 id 上（那会导致
 * 「消息进来了却唤醒一个用户看不见的会话」，产物没人看、还可能乱发消息）。
 *
 * <h2>接线与「通知目标」是两件事</h2>
 * 本工具写 {@code im_chat.bound_session_id}（决定**能不能触发**本会话）。
 * 「跑完把结果发到哪」（{@code notifyChats}）是另一个维度，⛔ 不要混。
 *
 * <h2>为什么有 {@code force}</h2>
 * 一个聊天最多接一个会话（{@code bound_session_id} 单值）。若目标聊天已接在**别的会话**，
 * 直接覆盖等于**静默抢走别人的接线** ⇒ 默认拒绝并说明占用者，只有显式
 * {@code force=true} 才顶替（与界面上「点灰项 → 弹确认框 → 顶替」同一语义）。
 */
@Component
public class BindImChatTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BindImChatTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "BindImChat";

    public static final String DESCRIPTION =
        "Connect (bind) an IM chat to THIS session, so incoming messages from that chat wake this "
            + "session up. No session id needed — it always binds to the current session. "
            + "If the chat is already bound to a different session, pass force=true to take it over.";

    public static final String PROMPT =
        "Bind an existing IM chat to the **current** session.\n\n"
            + "- `chatId` — take it from `ListImChats` (or from `SearchImContacts`). Never invent one.\n"
            + "- `force` — only when the chat is already bound to **another** session and the user "
            + "explicitly asked to move it. Without it the call fails and tells you who holds it.\n\n"
            + "Binding controls **whether messages wake this session**. It is NOT the same as where "
            + "results get delivered. After binding, messages from that chat arrive here.";

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
        props.putObject("force")
            .put("type", "boolean")
            .put("description",
                "Take the chat over from another session. Only when the user explicitly asked for it.");
        var required = schema.putArray("required");
        required.add("chatId");
        return schema;
    }

    /** 写接线 ⇒ 不是只读。 */
    @Override public boolean isReadOnly(JsonNode input) { return false; }

    /** 同一聊天的两次绑定不该并发（后一次会把前一次的结果顶掉，用户看到哪个都算错）。 */
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    /** force=true 会抢走别人的接线 ⇒ 破坏性。 */
    @Override
    public boolean isDestructive(JsonNode input) {
        return input != null && input.path("force").asBoolean(false);
    }

    /**
     * 单参入口：**拿不到当前会话** ⇒ 明确失败。
     *
     * <p>为什么不是「猜到某个会话」：本工具的语义就是「接给**我自己**」，
     * 没有上下文时任何猜测都可能把聊天接到用户看不见的会话上。
     * 生产链路（{@code ToolRegistry.dispatch → tool.execute(call, ctx)}）始终带 ctx。
     */
    @Override
    public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
        return ToolResult.error(call.id(), "Error: 缺少会话上下文，无法绑定（不要在不带 ctx 的路径调用本工具）");
    }

    /**
     * 执行绑定。
     *
     * <p>⚠️ 真正的实现挂在双参版本：只有它能拿到当前会话。
     */
    @Override
    public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
            ToolUseBlock call, ToolUseContext ctx) {
        String chatId = call.input() == null ? null : text(call.input().path("chatId"));
        if (chatId == null || chatId.isBlank()) {
            return ToolResult.error(call.id(), "Error: chatId 不能为空（用 ListImChats 拿 id）");
        }
        String sessionId = ctx == null ? null : ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // 理论上 ToolUseContext 构造就要求 sessionId 非空；这里兜底，避免静默绑到 null 上
            return ToolResult.error(call.id(), "Error: 当前没有会话上下文，无法绑定");
        }

        boolean force = call.input() != null && call.input().path("force").asBoolean(false);
        try {
            ImChatEntry chat = chatService.findById(chatId).orElse(null);
            if (chat == null) {
                return ToolResult.error(call.id(),
                    "Error: 聊天不存在: " + chatId + "（用 ListImChats 重新取 id）");
            }

            String holder = chat.boundSessionId();
            boolean alreadyHere = sessionId.equals(holder);
            if (!alreadyHere && holder != null && !holder.isBlank() && !force) {
                // 不静默抢：把占用者告诉调用方，由它去问用户
                return ToolResult.error(call.id(),
                    "Error: 该聊天已接在另一个会话（" + holder + "）上。"
                        + "要顶替请确认后带 force=true 重试。");
            }

            chatService.bind(chatId, sessionId, "manual");
            reconcile(chat.instanceId());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bound", true);
            out.put("chatId", chatId);
            out.put("chatName", chat.displayName());
            out.put("sessionId", sessionId);
            out.put("alreadyBound", alreadyHere);
            if (!alreadyHere && holder != null && !holder.isBlank()) {
                out.put("tookOverFrom", holder);
            }
            out.put("note", alreadyHere
                ? "本来就接在这个会话上（重复调用无副作用）"
                : "已接线：该聊天的新消息会唤醒本会话");
            log.info("[BindImChat] chat={} session={} force={} tookOverFrom={}",
                chatId, sessionId, force, alreadyHere ? null : holder);
            return ToolResult.success(call.id(), JSON.writeValueAsString(out));
        } catch (RuntimeException e) {
            log.warn("[BindImChat] 绑定失败 chat={} session={} err={}", chatId, sessionId, e.toString());
            return ToolResult.error(call.id(), "Error: 绑定失败 —— " + e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ToolResult.success(call.id(), "{\"bound\":true,\"chatId\":\"" + chatId + "\"}");
        }
    }

    /**
     * 接线变了 ⇒ 该渠道的监听跟着收敛（有接线就开、没有就关）。
     *
     * <p>失败只记日志：接线本身已经写进库了，监听会在下次 bind/unbind 或重启时再收敛一次，
     * 不该因为这一句失败就把「绑定成功」改报成失败。
     */
    private void reconcile(String instanceId) {
        try {
            inboundManager.reconcile(instanceId);
        } catch (RuntimeException e) {
            log.warn("[BindImChat] 监听收敛失败 instance={} err={}", instanceId, e.toString());
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}

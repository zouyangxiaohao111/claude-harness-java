package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.inbound.ImInboundManager;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code BindImChat} / {@code UnbindImChat} 的守卫测试。
 *
 * <h2>为什么这几条必须存在</h2>
 * 两个工具的整个设计前提是「**会话 id 不发生成参数**，一律取当前上下文」。
 * 一旦有人为了省事把 sessionId 加成参数，模型就能把聊天接到一个它编出来的会话上 ——
 * 编译通过、界面无异常，但消息会唤醒一个用户看不见的会话。这几条把该前提钉住。
 *
 * <p>第二条同样关键：**不静默顶替别人的接线**。占用的聊天默认拒绝，
 * 只有显式 {@code force=true} 才覆盖（与界面上「点灰项 → 确认 → 顶替」同语义）。
 */
class BindUnbindImChatToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock call(String tool, String json) {
        try {
            JsonNode input = JSON.readTree(json);
            return new ToolUseBlock("tu-1", tool, input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 只桩 sessionId：工具只读这一个字段。 */
    private static ToolUseContext ctx(String sessionId) {
        ToolUseContext c = mock(ToolUseContext.class);
        when(c.sessionId()).thenReturn(sessionId);
        return c;
    }

    private static ImChatEntry chat(String id, String boundSessionId) {
        return new ImChatEntry(id, "imc-fs", "feishu", "oc_x", "飞书单聊", "p2p", null, null, null,
                boundSessionId, boundSessionId == null ? null : "manual", null, "t", "t", null);
    }

    private static String data(AgentToolResult<?> r) {
        return String.valueOf(((ToolResult<?>) r).data());
    }

    // ───────────────────────── BindImChat ─────────────────────────

    @Test
    @DisplayName("schema 里不得出现 sessionId —— 会话只能取自上下文")
    void bindSchemaHasNoSessionId() {
        BindImChatTool t = new BindImChatTool();
        String schema = t.inputSchema().toString();
        assertThat(schema).doesNotContain("sessionId");
        assertThat(schema).contains("chatId");
        assertThat(t.prompt()).doesNotContain("sessionId");
    }

    @Test
    @DisplayName("正常绑定：写到**当前会话**，并让监听收敛")
    void bindUsesContextSession() {
        ImChatService chats = mock(ImChatService.class);
        ImInboundManager inbound = mock(ImInboundManager.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", null)));

        BindImChatTool t = new BindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(inbound);

        AgentToolResult<?> r = t.execute(call("BindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).contains("true");
        ArgumentCaptor<String> sessions = ArgumentCaptor.forClass(String.class);
        verify(chats).bind(eq("imch-1"), sessions.capture(), anyString());
        assertThat(sessions.getValue()).as("必须绑到上下文里那个会话，不能是别的").isEqualTo("sess-me");
        verify(inbound).reconcile("imc-fs");
    }

    @Test
    @DisplayName("已接在别的会话 + 没 force ⇒ 拒绝且**一次 bind 都不调**（不静默抢）")
    void bindRefusesTakeoverWithoutForce() {
        ImChatService chats = mock(ImChatService.class);
        ImInboundManager inbound = mock(ImInboundManager.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", "sess-other")));

        BindImChatTool t = new BindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(inbound);

        AgentToolResult<?> r = t.execute(call("BindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).startsWith("Error:").contains("sess-other");
        verify(chats, never()).bind(anyString(), anyString(), any());
        verify(inbound, never()).reconcile(anyString());
    }

    @Test
    @DisplayName("force=true 才顶替：越过占用者，绑到当前会话")
    void bindTakesOverWithForce() {
        ImChatService chats = mock(ImChatService.class);
        ImInboundManager inbound = mock(ImInboundManager.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", "sess-other")));

        BindImChatTool t = new BindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(inbound);

        AgentToolResult<?> r = t.execute(
            call("BindImChat", "{\"chatId\":\"imch-1\",\"force\":true}"), ctx("sess-me"));

        assertThat(data(r)).contains("tookOverFrom").contains("sess-other");
        verify(chats).bind(eq("imch-1"), eq("sess-me"), anyString());
    }

    @Test
    @DisplayName("重复绑同一会话 ⇒ 幂等，不报错")
    void bindAlreadyHereIsIdempotent() {
        ImChatService chats = mock(ImChatService.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", "sess-me")));

        BindImChatTool t = new BindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(mock(ImInboundManager.class));

        AgentToolResult<?> r = t.execute(call("BindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).contains("alreadyBound").contains("true");
        // 幂等仍要落库一次（保持 boundAt 刷新语义），但不能报错
        verify(chats).bind(eq("imch-1"), eq("sess-me"), anyString());
    }

    @Test
    @DisplayName("聊天不存在 / 缺 chatId ⇒ Error（提示去 ListImChats 取 id）")
    void bindRejectsMissingChat() {
        ImChatService chats = mock(ImChatService.class);
        when(chats.findById("nope")).thenReturn(Optional.empty());

        BindImChatTool t = new BindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(mock(ImInboundManager.class));

        assertThat(data(t.execute(call("BindImChat", "{\"chatId\":\"nope\"}"), ctx("sess-me"))))
            .startsWith("Error:").contains("ListImChats");
        assertThat(data(t.execute(call("BindImChat", "{}"), ctx("sess-me"))))
            .startsWith("Error:");
        // 单参路径拿不到上下文 ⇒ 必须失败而不是瞎绑
        assertThat(data(t.execute(call("BindImChat", "{\"chatId\":\"imch-1\"}"))))
            .startsWith("Error:");
    }

    // ───────────────────────── UnbindImChat ─────────────────────────

    @Test
    @DisplayName("解绑只对**本会话**的接线生效：接在别人那儿 ⇒ 拒绝")
    void unbindRefusesForeignBinding() {
        ImChatService chats = mock(ImChatService.class);
        ImInboundManager inbound = mock(ImInboundManager.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", "sess-other")));

        UnbindImChatTool t = new UnbindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(inbound);

        AgentToolResult<?> r = t.execute(call("UnbindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).startsWith("Error:").contains("sess-other");
        verify(chats, never()).unbind(anyString());
    }

    @Test
    @DisplayName("正常解绑本会话的接线 ⇒ unbind + 监听收敛")
    void unbindOwnBinding() {
        ImChatService chats = mock(ImChatService.class);
        ImInboundManager inbound = mock(ImInboundManager.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", "sess-me")));

        UnbindImChatTool t = new UnbindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(inbound);

        AgentToolResult<?> r = t.execute(call("UnbindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).contains("\"unbound\":true");
        verify(chats).unbind("imch-1");
        verify(inbound).reconcile("imc-fs");
    }

    @Test
    @DisplayName("本来就没接线 ⇒ 幂等成功（不是一个错误）")
    void unbindNotBoundIsIdempotent() {
        ImChatService chats = mock(ImChatService.class);
        when(chats.findById("imch-1")).thenReturn(Optional.of(chat("imch-1", null)));

        UnbindImChatTool t = new UnbindImChatTool();
        t.setChatService(chats);
        t.setInboundManager(mock(ImInboundManager.class));

        AgentToolResult<?> r = t.execute(call("UnbindImChat", "{\"chatId\":\"imch-1\"}"), ctx("sess-me"));

        assertThat(data(r)).contains("alreadyUnbound").doesNotContain("Error");
        verify(chats, never()).unbind(anyString());
    }
}

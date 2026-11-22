package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImConfigService;
import com.nexusai.domain.im_channel.ImSendService;
import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategyFactory;
import com.nexusai.domain.im_channel.catalog.UnsupportedChatListStrategy;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两个 IM 工具的契约验证：{@code SendImAsUser}（以用户名义发）与 {@code ListImChats}（列会话）。
 *
 * <h2>WHY 这些断言值得存在</h2>
 * <ul>
 *   <li><b>可见性</b>：开关关闭时 {@code SendImAsUser} 必须在 LLM schema 里消失 ——
 *       「替用户对外发言」这个能力不能靠模型自觉不用。</li>
 *   <li><b>降级必须可见</b>：平台不支持用户身份时消息仍要发出去，但结果必须报
 *       {@code identity=BOT} + 说明 ⇒ 模型不能以为「我以用户身份发出去了」。</li>
 *   <li><b>参数脏值</b>：chatId 空 / 正文与附件都空 ⇒ 明确报错而不是发空消息。</li>
 *   <li><b>单目标失败不外溢</b>：投递失败要如实报失败（否则模型以为发出去了）。</li>
 * </ul>
 */
@DisplayName("IM 工具 · SendImAsUser / ListImChats")
class ImImToolsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ═════════════════════ SendImAsUser ═════════════════════

    @Test
    @DisplayName("开关关闭 ⇒ isEnabled=false（工具不进 LLM schema）")
    void sendAsUserDisabledWhenSwitchOff() {
        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setConfigService(configService(false));

        assertThat(tool.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("开关开启 ⇒ isEnabled=true")
    void sendAsUserEnabledWhenSwitchOn() {
        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setConfigService(configService(true));

        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("配置服务未注入 ⇒ isEnabled=false（fail-closed：宁可 AI 不能冒充）")
    void sendAsUserFailClosedWithoutConfig() {
        assertThat(new SendImAsUserTool().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("以用户名义发送 ⇒ 传给 Service 的 identity 是 USER，且结果带 identity")
    void sendAsUserPassesUserIdentity() {
        ImSendService svc = mock(ImSendService.class);
        when(svc.send(eq(List.of("imch-1")), eq("你好"), anyList(), eq(SendIdentity.USER)))
            .thenReturn(List.of(ImSendResult.sent("mid-1", List.of(),
                SendIdentity.USER, null)));

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);
        tool.setChatService(chatService());

        AgentToolResult<?> r = tool.execute(call(sendInput("imch-1", "你好", null)));

        assertThat(isError(r)).isFalse();
        assertThat(dataOf(r).get("identity")).isEqualTo("USER");
        assertThat(dataOf(r).get("requestedIdentity")).isEqualTo("USER");
    }

    @Test
    @DisplayName("平台不支持用户身份（降级为 BOT）⇒ 仍是成功，但 identity=BOT 且带说明")
    void sendAsUserReportsDowngrade() {
        ImSendService svc = mock(ImSendService.class);
        when(svc.send(eq(List.of("imch-1")), eq("你好"), anyList(), eq(SendIdentity.USER)))
            .thenReturn(List.of(ImSendResult.sent("mid-1", List.of(), SendIdentity.BOT,
                "该平台不支持以用户名义发送，已降级为机器人身份")));

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);
        tool.setChatService(chatService());

        AgentToolResult<?> r = tool.execute(call(sendInput("imch-1", "你好", null)));

        assertThat(isError(r)).as("消息确实发出去了 ⇒ 不算失败").isFalse();
        assertThat(dataOf(r).get("identity")).isEqualTo("BOT");
        assertThat(String.valueOf(dataOf(r).get("identityNote"))).contains("降级");
    }

    @Test
    @DisplayName("chatId 为空 ⇒ 报错，不发请求")
    void sendAsUserRejectsBlankChatId() {
        ImSendService svc = mock(ImSendService.class);

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);

        AgentToolResult<?> r = tool.execute(call(sendInput("  ", "你好", null)));

        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("chatId");
    }

    @Test
    @DisplayName("正文与附件都空 ⇒ 报错，不发请求")
    void sendAsUserRejectsEmptyContent() {
        ImSendService svc = mock(ImSendService.class);

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);

        AgentToolResult<?> r = tool.execute(call(sendInput("imch-1", "  ", null)));

        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("内容为空");
    }

    @Test
    @DisplayName("投递失败 ⇒ 如实报失败（不能骗模型说发出去了）")
    void sendAsUserReportsFailure() {
        ImSendService svc = mock(ImSendService.class);
        when(svc.send(anyList(), any(), anyList(), any()))
            .thenReturn(List.of(ImSendResult.failed("IM_CHAT_NOT_FOUND", "目标聊天不存在：imch-x")));

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);
        tool.setChatService(chatService());

        AgentToolResult<?> r = tool.execute(call(sendInput("imch-1", "你好", null)));

        assertThat(isError(r)).isTrue();
        assertThat(textOf(r)).contains("IM_CHAT_NOT_FOUND");
    }

    @Test
    @DisplayName("SKIPPED（如缺回复令牌）⇒ 明确告诉模型「没发出去」")
    void sendAsUserReportsSkipped() {
        ImSendService svc = mock(ImSendService.class);
        when(svc.send(anyList(), any(), anyList(), any()))
            .thenReturn(List.of(ImSendResult.skipped("IM_REPLY_TOKEN_MISSING", "需要对方先给机器人发一条消息")));

        SendImAsUserTool tool = new SendImAsUserTool();
        tool.setSendService(svc);
        tool.setChatService(chatService());

        AgentToolResult<?> r = tool.execute(call(sendInput("imch-1", "你好", null)));

        assertThat(isError(r)).as("没发出去就不能报成功").isTrue();
        assertThat(textOf(r)).contains("未发送");
    }

    @Test
    @DisplayName("schema：chatId 必填，attachments 是字符串数组（不是 tuple）")
    void sendAsUserSchemaIsWellFormed() {
        JsonNode schema = new SendImAsUserTool().inputSchema();

        assertThat(schema.path("required").toString()).contains("chatId");
        JsonNode attachments = schema.path("properties").path("attachments");
        assertThat(attachments.path("type").asText()).isEqualTo("array");
        assertThat(attachments.path("items").path("type").asText())
            .as("⛔ items 必须是 string；tuple 语法（addObject）会让服务商 400")
            .isEqualTo("string");
    }

    // ═════════════════════ ListImChats ═════════════════════

    @Test
    @DisplayName("refresh=false ⇒ 只读本地目录，不调平台")
    void listChatsWithoutRefreshReadsLocalOnly() {
        ListImChatsTool tool = new ListImChatsTool();
        tool.setChatService(chatService());
        tool.setStrategyFactory(factoryThatFailsIfCalled());

        AgentToolResult<?> r = tool.execute(call(listInput(null, false)));

        assertThat(isError(r)).isFalse();
        assertThat(dataOf(r).get("count")).isEqualTo(1);
        assertThat(rowsOf(r).get(0).get("id")).isEqualTo("imch-1");
    }

    @Test
    @DisplayName("平台拉取失败 ⇒ 不让工具失败，降级为本地目录并写明原因")
    void listChatsDegradesWhenPlatformFails() {
        ListImChatsTool tool = new ListImChatsTool();
        tool.setChatService(chatService());
        tool.setChannelService(channelService());
        tool.setStrategyFactory(failingFactory());

        AgentToolResult<?> r = tool.execute(call(listInput(null, true)));

        assertThat(isError(r)).as("平台挂了不该让整个工具失败").isFalse();
        assertThat(dataOf(r).get("count")).isEqualTo(1);
        assertThat(String.valueOf(dataOf(r).get("notes"))).contains("拉取失败");
    }

    @Test
    @DisplayName("平台不支持拉取 ⇒ 提示改用本地目录")
    void listChatsNotesUnsupportedPlatform() {
        ListImChatsTool tool = new ListImChatsTool();
        tool.setChatService(chatService());
        tool.setChannelService(channelService());
        tool.setStrategyFactory(unsupportedFactory());

        AgentToolResult<?> r = tool.execute(call(listInput(null, true)));

        assertThat(isError(r)).isFalse();
        assertThat(String.valueOf(dataOf(r).get("notes"))).contains("不支持");
    }

    @Test
    @DisplayName("输出紧凑：每条带 id（供 SendImAsUser 用）/ 名称 / 类型")
    void listChatsOutputIsCompactAndUsable() {
        ListImChatsTool tool = new ListImChatsTool();
        tool.setChatService(chatService());
        tool.setStrategyFactory(factoryThatFailsIfCalled());

        AgentToolResult<?> r = tool.execute(call(listInput(null, false)));

        List<Map<String, Object>> rows = rowsOf(r);
        assertThat(rows).hasSize(1);
        Map<String, Object> c = rows.get(0);
        assertThat(c.get("id")).isEqualTo("imch-1");
        assertThat(c.get("name")).isEqualTo("张三");
        assertThat(c.get("kind")).isEqualTo("p2p");
        // ⛔ 不该把整个领域对象序列化给模型（bindMode/boundAt/createdAt 都没用）
        assertThat(c).doesNotContainKeys("bindMode", "createdAt", "boundAt");
    }

    @Test
    @DisplayName("instanceId 过滤 ⇒ 只返回该实例的会话")
    void listChatsFiltersByInstance() {
        ImChatService chats = mock(ImChatService.class);
        when(chats.list()).thenReturn(List.of(
            entry("imch-1", "inst-1", "张三"),
            entry("imch-2", "inst-2", "李四")));

        ListImChatsTool tool = new ListImChatsTool();
        tool.setChatService(chats);
        tool.setStrategyFactory(factoryThatFailsIfCalled());

        AgentToolResult<?> r = tool.execute(call(listInput("inst-2", false)));

        assertThat(dataOf(r).get("count")).isEqualTo(1);
        assertThat(rowsOf(r).get(0).get("id")).isEqualTo("imch-2");
    }

    // ═════════════════════ 工具替身 ═════════════════════

    private static ToolUseBlock call(JsonNode input) {
        return new ToolUseBlock("t-1", "SendImAsUser", input);
    }

    private static ObjectNode sendInput(String chatId, String text, List<String> attachments) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("chatId", chatId);
        in.put("text", text);
        if (attachments != null && !attachments.isEmpty()) {
            ArrayNode arr = in.putArray("attachments");
            attachments.forEach(arr::add);
        }
        return in;
    }

    private static ObjectNode listInput(String instanceId, boolean refresh) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        if (instanceId != null) {
            in.put("instanceId", instanceId);
        }
        in.put("refresh", refresh);
        return in;
    }

    private static ImConfigService configService(boolean allow) {
        ImConfigService c = mock(ImConfigService.class);
        when(c.isAllowUserIdentity()).thenReturn(allow);
        return c;
    }

    private static ImChatService chatService() {
        ImChatService c = mock(ImChatService.class);
        when(c.list()).thenReturn(List.of(entry("imch-1", "inst-1", "张三")));
        when(c.findById("imch-1")).thenReturn(java.util.Optional.of(entry("imch-1", "inst-1", "张三")));
        return c;
    }

    private static ImChatEntry entry(String id, String instanceId, String name) {
        return new ImChatEntry(id, instanceId, "wecom", "peer-" + id, name, "p2p", null,
            "最近一条", "2026-11-18T10:00:00+08:00", null, null, null, "t", "t");
    }

    /** 有渠道实例的目录服务（拉平台会话需要知道有哪些实例）。 */
    private static ImChannelService channelService() {
        ImChannelService c = mock(ImChannelService.class);
        when(c.list()).thenReturn(List.of(
            new ImInstanceEntry("inst-1", "wecom", "我的企微机器人", "connected",
                null, null, null, "t", "t")));
        return c;
    }

    /** 一个必然失败的策略（refresh=true 时会被调用 ⇒ 验证「不会因平台失败而崩」）。 */
    private static ImChatListStrategyFactory failingFactory() {
        ImChatListStrategy s = new ImChatListStrategy() {
            @Override public String platform() { return "wecom"; }
            @Override public boolean canRefresh() { return true; }
            @Override public List<ImChatCandidate> fetch(String instanceId) throws ImSendException {
                throw new ImSendException("IM_PLATFORM_ERROR", "凭证失效");
            }
        };
        return new ImChatListStrategyFactory(List.of(s));
    }

    /** 平台不支持拉取。 */
    private static ImChatListStrategyFactory unsupportedFactory() {
        return new ImChatListStrategyFactory(List.of(new UnsupportedChatListStrategy("wecom")));
    }

    /** 策略不支持拉取 ⇒ fetch 一次都不该被调（refresh=false 路径）。 */
    private static ImChatListStrategyFactory factoryThatFailsIfCalled() {
        ImChatListStrategy s = new ImChatListStrategy() {
            @Override public String platform() { return "wecom"; }
            @Override public boolean canRefresh() { return false; }
            @Override public List<ImChatCandidate> fetch(String instanceId) {
                throw new AssertionError("refresh=false 时不应调用 fetch");
            }
        };
        return new ImChatListStrategyFactory(List.of(s));
    }

    /**
     * 是否是错误结果。
     *
     * <p>⚠️ {@code AgentToolResult} 没有 {@code isError()} 字段 —— 错误是**按消息前缀**
     * 推导的（{@code LlmAgentLoop.isToolErrorData}），所以本工具的错误串<b>必须</b>以
     * {@code "Error: "} 开头，否则会被当成成功（silent success）。
     */
    private static boolean isError(AgentToolResult<?> r) {
        return LlmAgentLoop.isToolErrorData(((ToolResult<?>) r).data());
    }

    /**
     * 成功时的结构化数据。
     *
     * <p>⚠️ {@code ListImChats} 现在返回 <b>JSON 字符串</b>而不是 Map ——
     * 落库链路只对 String data 生成 content（Map 会落成空，模型拿到空结果）。
     * 所以这里两种形态都要认。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(AgentToolResult<?> r) {
        Object d = ((ToolResult<?>) r).data();
        if (d instanceof Map) {
            return (Map<String, Object>) d;
        }
        if (d instanceof String s && s.trim().startsWith("{")) {
            try {
                return JSON.readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /** 输出里的会话行。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(AgentToolResult<?> r) {
        Object rows = dataOf(r).get("chats");
        return rows instanceof List ? (List<Map<String, Object>>) rows : List.of();
    }

    /** 结果文本（成功时是 Map 的 toString，错误时是错误串）。 */
    private static String textOf(AgentToolResult<?> r) {
        Object d = ((ToolResult<?>) r).data();
        return d == null ? "" : String.valueOf(d);
    }
}

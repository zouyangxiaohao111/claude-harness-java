package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImContactCandidate;
import com.nexusai.domain.im_channel.catalog.ImContactSearchStrategy;
import com.nexusai.domain.im_channel.catalog.ImContactSearchStrategyFactory;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 IM 通讯录里按关键词找人。
 *
 * <h2>为什么与 {@code ListImChats} 分开（两个工具，不是参数）</h2>
 * {@code ListImChats} 列的是「<b>机器人参与过</b>的会话」；<b>从没和机器人说过话的同事
 * 不在其中</b>。要让 AI「以你的名义给某个同事发消息」，必须能从通讯录里找人。
 * 两者是不同数据源（会话 vs 人员），混成一个工具会让模型误判「这个人是不是我群里的」。
 *
 * <h2>返回的 {@code userId} 直接就是发消息用的 chatId</h2>
 * 企微实测：{@code contact/users/search} 返回的 userid 是加密形态（{@code woNH-…}），
 * 可直接传给 {@code SendImAsUser} 当 chatId。⚠️ 与入站帧的明文 {@code from.userid} 不是一回事。
 */
@Component
public class SearchImContactsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SearchImContactsTool.class);

    public static final String NAME = "SearchImContacts";

    public static final String DESCRIPTION =
        "在 IM 通讯录里按姓名/拼音搜索联系人，返回可用于发消息的 id。"
            + "当目标**不在** ListImChats 的会话列表里（例如一个从没和机器人说过话的同事）时用它。"
            + "返回的 userId 可以直接作为 SendImAsUser 的 chatId。";

    public static final String PROMPT =
        "Search the IM contact directory by name (Chinese, pinyin or English alias).\n\n"
            + "Use this when the person you need to message is NOT in the chat list from "
            + "`ListImChats` — i.e. someone who never talked to the bot.\n\n"
            + "Each result carries `userId`, which you can pass directly as the `chatId` "
            + "argument of `SendImAsUser`. Do not invent ids.\n\n"
            + "Not every platform supports directory search; unsupported platforms will say so.\n"
            + "`instanceId` is optional — omit it to search across all channel accounts.";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired(required = false)
    private ImChannelService channelService;

    @Autowired(required = false)
    private ImContactSearchStrategyFactory strategyFactory;

    @Autowired(required = false)
    private com.nexusai.domain.im_channel.ImChatService chatService;

    /** [测试注入] 渠道实例。 */
    public void setChannelService(ImChannelService channelService) {
        this.channelService = channelService;
    }

    /** [测试注入] 策略工厂。 */
    public void setStrategyFactory(ImContactSearchStrategyFactory factory) {
        this.strategyFactory = factory;
    }

    /** [测试注入] 聊天目录（搜到的人要落库才能被 SendImAsUser 使用）。 */
    public void setChatService(com.nexusai.domain.im_channel.ImChatService chatService) {
        this.chatService = chatService;
    }

    @Override public String name() { return NAME; }

    @Override public String description() { return DESCRIPTION; }

    @Override public String prompt() { return PROMPT; }

    /**
     * ⚠️ 不是纯只读：搜到的人会 upsert 进 {@code im_chat}（否则拿到平台 id 也发不出去）。
     * 但仍然**不对发消息**，副作用远小于发送类工具。
     */
    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("keyword")
            .put("type", "string")
            .put("description", "Name (Chinese / pinyin / English alias) to search for. "
                + "Required — never guess a user id instead of searching.");

        props.putObject("instanceId")
            .put("type", "string")
            .put("description", "Optional. Restrict the search to one channel account "
                + "(e.g. a specific WeCom bot). Omit to search across all accounts.");

        schema.putArray("required").add("keyword");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        if (channelService == null || strategyFactory == null) {
            return ToolResult.error(call.id(), "Error: IM 渠道服务未初始化");
        }
        JsonNode input = call.input();
        String keyword = input == null ? null : input.path("keyword").asText(null);
        if (keyword == null || keyword.isBlank()) {
            return ToolResult.error(call.id(),
                    "Error: keyword 必填（用姓名/拼音搜索，不要凭空编 user id）");
        }
        String instanceFilter = input.path("instanceId").asText(null);

        List<Map<String, Object>> people = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ImInstanceEntry inst : instances()) {
            if (instanceFilter != null && !instanceFilter.isBlank()
                    && !instanceFilter.equals(inst.id())) {
                continue;
            }
            ImContactSearchStrategy strategy = strategyFactory.of(inst.platform());
            if (!strategy.canSearch()) {
                continue;   // 该平台没有通讯录接口 → 不是错误，只是别列出来
            }
            try {
                List<ImContactCandidate> found = strategy.search(inst.id(), keyword);
                for (ImContactCandidate c : found) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    // 🔴 必须落进 im_chat 再返回本地 id：
                    //   SendImAsUser 的 chatId 走 ImChatService.findById，要的是 imch-xxx；
                    //   直接给平台 userId（woNH-…）会报「聊天不存在」。
                    String localId = ensureChatRow(inst, c, notes);
                    if (localId != null) {
                        m.put("id", localId);             // ← SendImAsUser 的 chatId
                    }
                    m.put("userId", c.userId());          // 平台侧 id（排障/再次定位用）
                    m.put("name", c.name());
                    m.put("platform", inst.platform());
                    m.put("instanceId", inst.id());
                    if (c.position() != null && !c.position().isBlank()) {
                        m.put("position", c.position());
                    }
                    if (!c.departments().isEmpty()) {
                        m.put("departments", c.departments());
                    }
                    people.add(m);
                }
            } catch (ImSendException e) {
                // 单实例失败不能拖垮整个搜索：其它实例的结果仍然有用
                notes.add(inst.displayName() + ": 搜索失败（" + e.getMessage() + "）");
                log.warn("[SearchImContacts] 实例 {} 搜索失败: {}", inst.id(), e.getMessage());
            } catch (RuntimeException e) {
                notes.add(inst.displayName() + ": 搜索异常（" + e.getClass().getSimpleName() + "）");
                log.warn("[SearchImContacts] 实例 {} 搜索异常", inst.id(), e);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contacts", people);
        data.put("count", people.size());
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        if (people.isEmpty()) {
            data.put("hint", "没搜到人。换个关键词（拼音/英文名）再试；或该平台不支持通讯录搜索。");
        }
        if (people.isEmpty() == false && people.stream().allMatch(m -> m.get("id") == null)) {
            data.put("warningHint", "搜到人了但都没落成本地目录（见 notes）⇒ 暂时只能用其中一个 userId 直接发");
        }
        try {
            // ⛔ 必须返回 String：落库链路只对 String data 生成 content（Map 会落成空）
            return ToolResult.success(call.id(), JSON.writeValueAsString(data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[SearchImContacts] 序列化失败: {}", e.toString());
            return ToolResult.success(call.id(), "{\"contacts\":[],\"count\":0}");
        }
    }

    /**
     * 把「联系人」落成 {@code im_chat} 的一行，返回本地 id（{@code imch-xxx}）。
     *
     * <h2>为什么必须落库</h2>
     * 发送链路（{@code ImSendService.sendOne}）第一步就是 {@code chatService.findById(chatId)}，
     * 拿不到就 {@code IM_CHAT_NOT_FOUND}。联系人来自通讯录而不是会话列表，
     * 如果不落库，AI 搜到了人也发不出去。
     *
     * <h2>为什么用 {@code SOURCE_LISTED}</h2>
     * 这一行是「从平台目录同步来的」事实（与 {@code sessions/list} 同一来源类别），
     * 不是入站产生的（{@code SOURCE_INBOUND}）、也不是本机扫描产物（{@code SOURCE_SCANNED}）。
     *
     * @return 本地 id；落库失败返回 null（并记 note，不让整个搜索失败）
     */
    private String ensureChatRow(ImInstanceEntry inst, ImContactCandidate c, List<String> notes) {
        if (chatService == null) {
            return null;
        }
        try {
            var chat = chatService.upsert(
                    inst.id(), inst.platform(), c.userId(), c.name(), "p2p",
                    null, null, null,
                    com.nexusai.domain.im_channel.ImChatService.SOURCE_LISTED);
            return chat == null ? null : chat.id();
        } catch (RuntimeException e) {
            notes.add(c.name() + ": 已搜到但落库失败（" + e.getClass().getSimpleName()
                    + "），暂时无法直接发消息");
            log.warn("[SearchImContacts] 落 im_chat 失败 peer={} err={}", c.userId(), e.toString());
            return null;
        }
    }

    private List<ImInstanceEntry> instances() {
        try {
            List<ImInstanceEntry> list = channelService.list();
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            log.warn("[SearchImContacts] 读渠道实例失败: {}", e.toString());
            return List.of();
        }
    }
}

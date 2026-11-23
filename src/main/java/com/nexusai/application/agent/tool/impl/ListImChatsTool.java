package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategyFactory;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ListImChats} 工具 · 让 agent **看到**有哪些 IM 会话，以及能对谁发消息。
 *
 * <h2>为什么必须有这个工具</h2>
 * {@code SendImAsUser} 需要一个明确的 {@code chatId}（{@code im_chat.id}）。
 * 模型不能凭空编一个 id ⇒ 必须先能「列出可选目标」。
 * <p>本工具给模型的每一条都带 {@code id}，它就是 {@code SendImAsUser} 的 {@code chatId} 参数值。
 *
 * <h2>两个数据源</h2>
 * <ul>
 *   <li><b>本地目录</b>（{@code im_chat} 表）：已经同步过 / 入站见过的会话，<b>永远可读</b>；</li>
 *   <li><b>平台实时拉取</b>（{@link ImChatListStrategy}）：用户刚建的群、刚加的联系人还不在本地目录里。
 *       {@code refresh=true} 时会去平台拉并 upsert 进本地目录。</li>
 * </ul>
 * 平台拉取失败（凭证失效 / 网络不通 / 平台不支持）**不能让整个工具失败** ⇒
 * 降级为「只用本地目录」，并在输出里写明哪个实例拉取失败、为什么。
 *
 * <h2>为什么要策略 + 工厂</h2>
 * 各平台的会话来源完全不同（企微机器人会话列表 / 飞书 chat 列表 / 微信本机扫描产物），
 * 把差异关进策略里，新增平台只加一个 {@code @Component}。
 */
@Component
public class ListImChatsTool implements Tool {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(ListImChatsTool.class);

    public static final String NAME = "ListImChats";

    public static final String DESCRIPTION =
        "列出可用的 IM 会话（人 / 群），返回每个会话的 id、名称、类型、最后消息时间。"
            + "要给别人发 IM 消息前，先用它找到目标会话的 id（chatId），再传给 SendImAsUser。";

    public static final String PROMPT =
        "List the available IM chats (people and groups) you can talk to.\n\n"
            + "Each entry carries `id` — that is the `chatId` argument for `SendImAsUser` "
            + "(and for other IM tools). Never invent a chat id; take it from this list.\n\n"
            + "`refresh` (default true) asks the platform for the newest chats (a group you just "
            + "created may not be in the local directory yet). Set it to false to read the local "
            + "directory only — faster, and works offline.\n"
            + "`instanceId` — restrict to one channel account (e.g. a specific WeCom bot).";

    @Autowired(required = false)
    private ImChatService chatService;

    @Autowired(required = false)
    private ImChannelService channelService;

    @Autowired(required = false)
    private ImChatListStrategyFactory strategyFactory;

    /** [测试注入] 聊天目录。 */
    public void setChatService(ImChatService chatService) {
        this.chatService = chatService;
    }

    /** [测试注入] 渠道实例。 */
    public void setChannelService(ImChannelService channelService) {
        this.channelService = channelService;
    }

    /** [测试注入] 策略工厂。 */
    public void setStrategyFactory(ImChatListStrategyFactory factory) {
        this.strategyFactory = factory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public String prompt() {
        return PROMPT;
    }

    /** 只读工具。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        // ⚠️ refresh=true 会写本地目录（upsert），因此不算纯只读；
        // 但它不会对外发消息，副作用远小于发送类工具。
        return false;
    }

    /** 并发安全（只读目录 + 幂等 upsert）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("instanceId")
            .put("type", "string")
            .put("description", "Optional. Restrict the listing to one channel account "
                + "(e.g. a specific WeCom bot). Omit to list chats across all accounts.");

        props.putObject("refresh")
            .put("type", "boolean")
            .put("description", "Whether to pull the newest chats from the platform first. "
                + "Default true. Set false to read the local directory only (faster, works "
                + "when the platform is unreachable).");

        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String instanceFilter = input.path("instanceId").asText("").trim();
        JsonNode refreshNode = input.path("refresh");
        boolean refresh = refreshNode.isMissingNode() || refreshNode.isNull()
                || refreshNode.asBoolean(true);

        if (chatService == null) {
            return ToolResult.error(call.id(), "Error: 聊天目录服务未初始化");
        }

        List<String> notes = new ArrayList<>();

        // ── 1. 可选：从平台拉最新会话并 upsert 进本地目录 ──
        if (refresh && strategyFactory != null) {
            for (ImInstanceEntry inst : instances()) {
                if (!instanceFilter.isEmpty() && !instanceFilter.equals(inst.id())) {
                    continue;
                }
                ImChatListStrategy strategy = strategyFactory.of(inst.platform());
                if (!strategy.canRefresh()) {
                    notes.add(inst.displayName() + "(" + inst.platform() + ")：该平台不支持拉取会话列表，"
                        + "只用本地目录");
                    continue;
                }
                try {
                    List<ImChatCandidate> candidates = strategy.fetch(inst.id());
                    if (candidates == null || candidates.isEmpty()) {
                        continue;
                    }
                    for (ImChatCandidate c : candidates) {
                        if (c == null || c.peerId() == null || c.peerId().isBlank()) {
                            continue;
                        }
                        try {
                            chatService.upsert(inst.id(), inst.platform(), c.peerId(),
                                c.peerName(), c.chatKind(), null, null, c.lastMessageAt(),
                                ImChatService.SOURCE_LISTED);
                        } catch (RuntimeException e) {
                            log.warn("[ListImChats] upsert 失败 instance={} peer={} err={}",
                                inst.id(), c.peerId(), e.toString());
                        }
                    }
                } catch (ImSendException e) {
                    // ⛔ 拉取失败不让整个工具失败：降级为「只用本地目录」并说明原因
                    notes.add(inst.displayName() + "(" + inst.id() + ")：拉取失败("
                        + (e.getMessage() == null ? e.code() : e.getMessage()) + ")，已回退本地目录");
                    log.warn("[ListImChats] 平台拉取失败 instance={} err={}", inst.id(), e.toString());
                } catch (RuntimeException e) {
                    notes.add(inst.displayName() + "(" + inst.id() + ")：拉取失败("
                        + e.toString() + ")，已回退本地目录");
                    log.warn("[ListImChats] 平台拉取异常 instance={} err={}", inst.id(), e.toString());
                }
            }
        }

        // ── 2. 读本地目录 ──
        List<ImChatEntry> chats;
        try {
            // [V335] 只给「可见」会话：机器人不在的群绑了也收不到事件（已接线的仍可见）
            chats = chatService.listVisible();
        } catch (RuntimeException e) {
            return ToolResult.error(call.id(),
                "Error: 读取聊天目录失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        if (chats == null) {
            chats = List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>(chats.size());
        Set<String> seen = new LinkedHashSet<>();
        for (ImChatEntry c : chats) {
            if (c == null) {
                continue;
            }
            if (!instanceFilter.isEmpty() && !instanceFilter.equals(c.instanceId())) {
                continue;
            }
            if (!seen.add(c.id())) {
                continue;
            }
            out.add(describe(c));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chats", out);
        data.put("count", out.size());
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        if (out.isEmpty()) {
            data.put("hint", "当前没有可用的 IM 会话。可能是还没配置渠道，或该平台拉不到会话列表。");
        }
        // 🔴 必须序列化成 **String** 再返回：落库链路（LlmAgentLoop.toolResultMessage /
        //   applyToolResultBudget）只对 String data 生成 content，Map 会落成空 content
        //   （2026-11-18 实测：DB 里 length(content)=0，模型拿到空）。BriefTool 能返回 Map
        //   是因为它走 successWithStructuredOutput 带 summary 兜底，本工具没有。
        try {
            return ToolResult.success(call.id(), JSON.writeValueAsString(data));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[ListImChats] 序列化失败: {}", e.toString());
            return ToolResult.success(call.id(), "{\"chats\":[],\"count\":0}");
        }
    }

    /** 渠道实例列表（拉取平台会话需要知道有哪些实例）。 */
    private List<ImInstanceEntry> instances() {
        if (channelService == null) {
            return List.of();
        }
        try {
            List<ImInstanceEntry> list = channelService.list();
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            log.warn("[ListImChats] 读渠道实例失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 会话 → 给模型看的紧凑描述。
     *
     * <p>⛔ 不把 {@code ImChatEntry} 整个序列化给模型：字段多且大多无用（bindMode / boundAt /
     * createdAt），会白烧 context。这里只挑模型真正要用的。
     */
    private static Map<String, Object> describe(ImChatEntry c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());                 // ← SendImAsUser 的 chatId 参数值
        // V319：昵称优先（对方改过的名字），再退 peerName（群名/显示名），最后 id。
        // 「name」保留给老调用方；「nickName」给模型一个明确的昵称字段。
        String displayName = c.nickName() != null && !c.nickName().isBlank()
            ? c.nickName()
            : (c.peerName() == null || c.peerName().isBlank() ? c.peerId() : c.peerName());
        m.put("name", displayName);
        m.put("nickName", c.nickName());
        m.put("kind", c.chatKind());         // p2p | group
        m.put("platform", c.platform());
        m.put("lastMessageAt", c.lastMessageAt());
        m.put("bound", c.bound());           // 是否已接到某个任务
        return m;
    }
}

package com.nexusai.domain.im_channel;

import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.repository.im_channel.entity.WechatConversationRecord;
import com.nexusai.repository.im_channel.mapper.WechatConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把本机微信的扫描结果同步进聊天目录 {@code im_chat}（V310 · NXS-UC-CHN-020 的前置）。
 *
 * <h2>为什么微信的聊天目录来自扫描，而不是入站</h2>
 * 微信 iLink 拿不到历史（只有 {@code getupdates} 的长轮询增量），而且需要 bot 已与对方
 * 建立过会话。但 wechatlog 域已经把**本机微信**的全部会话扫进 V301
 * （{@code wechat_conversation} / {@code wechat_message}，实测 413 个会话）。
 * 用它当「有哪些聊天」的来源，用户不用先跟 bot 说一句话就能在界面上选到群/人。
 *
 * <h2>⚠️ 两个必须知道的约束（都由发送层兜住，本类不隐藏数据）</h2>
 * <ol>
 *   <li><b>群聊目前发不出去</b>：微信 iLink 没有群发消息通道（NXS-INT-CHN-001 的 failure_mode）。
 *       本类仍然把群写进目录（界面要能看到），但微信策略会因缺 {@code context_token}
 *       返回 {@code SKIPPED}。</li>
 *   <li><b>私聊也要等对方先说话</b>：iLink 必须带 {@code context_token}，它只来自入站消息。
 *       所以「扫描到了」≠「现在能发」，要等 {@code im_chat.reply_token} 被入站写入。</li>
 * </ol>
 *
 * <h2>幂等</h2>
 * 按 {@code (instance_id, peer_id)} upsert（V305 唯一键），重复调用只更新不新增。
 * ⛔ 不写 {@code reply_token}：那是入站链路的字段，扫描不该清掉它。
 */
@Service
public class ImChatSyncService {

    private static final Logger log = LoggerFactory.getLogger(ImChatSyncService.class);

    @Autowired private ImChannelService channelService;
    @Autowired private ImChatService chatService;
    @Autowired private WechatConversationMapper wechatConversationMapper;
    @Autowired private com.nexusai.domain.im_channel.send.FeishuTokenService feishuTokenService;
    /** 飞书显示名解析（真名优先、拿不到降级尾号）；测试可 setField 替换。 */
    @Autowired(required = false) private FeishuChatNameResolver nameResolver;
    @Autowired private com.nexusai.domain.im_channel.cli.WecomCliTokenService wecomTokenService;
    @Autowired private com.nexusai.domain.im_channel.cli.WecomCliClient wecomClient;

    /** 飞书群列表端点（docs/09）。 */
    private static final String FEISHU_CHATS_PATH = "/open-apis/im/v1/chats";
    /** 飞书历史消息端点（本人身份读群消息）。 */
    private static final String FEISHU_MESSAGES_PATH = "/open-apis/im/v1/messages";
    private static final String FEISHU_DEFAULT_BASE = "https://open.feishu.cn";
    /** 群列表 page_size 上限（docs/09）。 */
    private static final int FEISHU_CHATS_PAGE_SIZE = 100;
    /** 每个群拉一页消息（只取最新一条做预览）。 */
    private static final int FEISHU_MSG_PAGE_SIZE = 50;

    /**
     * 飞书 open-apis 基址。
     *
     * <p>为什么是实例字段而不是直接引用常量：单测用本地 {@code HttpServer} 桩，
     * 需要把基址换掉（⛔ 不连真实飞书）。
     */
    private String feishuOpenBase = FEISHU_DEFAULT_BASE;
    /** 页数硬保护：服务端 has_more 恒 true 时不至于死循环。 */
    private static final int MAX_PAGES = 20;

    /** [ou_→name] 单次同步最多补多少个私聊名（缺通讯录权限时也别把接口打爆）。 */
    private static final int FEISHU_NAME_RESOLVE_LIMIT = 50;

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 同步结果（给 REST 与日志看）。 */
    public record SyncResult(
            String instanceId,
            int total,      // 扫描到的会话数
            int created,    // 新建的
            int updated,    // 已存在并更新的
            int skipped,    // 缺关键字段（无 conversationId）被跳过的
            String message  // 给人看的一句话
    ) {}

    /**
     * 同步本机微信会话到指定实例的聊天目录。
     *
     * @param instanceId 目标实例；null/空白 ⇒ 自动取第一个 {@code platform=wechat} 的实例
     * @return 同步统计；无实例时 {@code total=0} 且 message 说明原因（⛔ 不抛异常，让调用方好处理）
     */
    public SyncResult syncWechatChats(String instanceId) {
        Optional<ImInstanceEntry> instanceOpt = resolveInstance(instanceId, "wechat");
        if (instanceOpt.isEmpty()) {
            String why = (instanceId == null || instanceId.isBlank())
                    ? "还没有微信渠道实例，请先在「IM 频道」里添加微信"
                    : "找不到该实例或它不是微信渠道：" + instanceId;
            log.warn("[ImChatSync] 跳过同步：{}", why);
            return new SyncResult(instanceId, 0, 0, 0, 0, why);
        }
        ImInstanceEntry instance = instanceOpt.get();

        List<WechatConversationRecord> conversations;
        try {
            conversations = wechatConversationMapper.selectAllOrdered();
        } catch (RuntimeException e) {
            // 表可能是空的（还没同步过本机微信），也可能库结构变化 —— 都不能让上层崩
            log.error("[ImChatSync] 读 wechat_conversation 失败（可能还没同步过本机微信）", e);
            return new SyncResult(instance.id(), 0, 0, 0, 0,
                    "读取本机微信会话失败，请先在「微信同步」里完成一次同步");
        }
        if (conversations == null || conversations.isEmpty()) {
            return new SyncResult(instance.id(), 0, 0, 0, 0,
                    "本机微信还没有同步过会话（数据源为空）");
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (WechatConversationRecord c : conversations) {
            if (c == null || c.getConversationId() == null || c.getConversationId().isBlank()) {
                skipped++;
                continue;
            }
            String peerId = c.getConversationId().trim();
            String peerName = c.getDisplayName() == null || c.getDisplayName().isBlank()
                    ? peerId : c.getDisplayName().trim();
            // isGroup: 1=群。数据库里可能是 null（老数据）⇒ 按单聊处理，宁可不误判成群
            String chatKind = (c.getIsGroup() != null && c.getIsGroup() == 1) ? "group" : "p2p";
            String lastAt = toIso(c.getLastMessageTime());

            boolean existed = exists(instance.id(), peerId);
            try {
                chatService.upsert(instance.id(), "wechat", peerId, peerName, chatKind,
                        null, null, lastAt, ImChatService.SOURCE_SCANNED);
                if (existed) {
                    updated++;
                } else {
                    created++;
                }
            } catch (RuntimeException e) {
                // 单条失败不影响其余（和投递链一个原则）
                skipped++;
                log.warn("[ImChatSync] 单条同步失败 peer={} err={}", peerId, e.toString());
            }
        }

        String message = String.format("已同步 %d 个会话（新建 %d / 更新 %d / 跳过 %d）",
                created + updated, created, updated, skipped);
        log.info("[ImChatSync] 微信会话同步完成 instance={} total={} created={} updated={} skipped={}",
                instance.id(), conversations.size(), created, updated, skipped);
        return new SyncResult(instance.id(), conversations.size(), created, updated, skipped, message);
    }

    /**
     * 同步飞书「机器人所在的群」到聊天目录（source=listed）。
     *
     * <h2>为什么必须做这个</h2>
     * 飞书没有「本机扫描」这种东西 —— 机器人能看到哪些群，只能问平台
     * （{@code GET /open-apis/im/v1/chats} 返回**机器人已加入**的群）。
     * 不拉这一步，{@code im_chat} 里就一条飞书记录都没有，投递无目标可发。
     *
     * <h2>分页</h2>
     * {@code has_more=false} 时服务端**不返回** {@code page_token} ⇒ 终止条件只能用
     * {@code has_more}；另加 20 页硬保护（防服务端异常时死循环）。
     *
     * @param instanceId 目标实例；null/空白 ⇒ 取第一个飞书实例
     */
    public SyncResult syncFeishuChats(String instanceId) {
        Optional<ImInstanceEntry> instOpt = resolveInstance(instanceId, "feishu");
        if (instOpt.isEmpty()) {
            String why = (instanceId == null || instanceId.isBlank())
                    ? "还没有飞书渠道实例，请先在「IM 频道」里添加飞书"
                    : "找不到该实例或它不是飞书渠道：" + instanceId;
            log.warn("[ImChatSync] 跳过飞书同步：{}", why);
            return new SyncResult(instanceId, 0, 0, 0, 0, why);
        }
        ImInstanceEntry inst = instOpt.get();

        String appId;
        String appSecret;
        try {
            Optional<String> credOpt = channelService.credentialOf(inst.id());
            if (credOpt.isEmpty()) {
                return new SyncResult(inst.id(), 0, 0, 0, 0, "该飞书实例没有可用凭证");
            }
            var root = JSON.readTree(credOpt.get());
            appId = text(root, "appId", "app_id");
            appSecret = text(root, "appSecret", "app_secret");
        } catch (Exception e) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "飞书凭证不是合法 JSON");
        }
        if (appId == null || appSecret == null) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "飞书凭证缺少 appId/appSecret");
        }

        String token;
        try {
            token = feishuTokenService.tokenFor(inst.id(), appId, appSecret);
        } catch (com.nexusai.domain.im_channel.send.ImSendException e) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "换取飞书令牌失败：" + e.getMessage());
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int total = 0;
        int pagesOk = 0;
        // [V335] bot 身份的 im/v1/chats = 机器人所在群（CLI: 仅成员可见）⇒ 本集合可回写可达性
        java.util.Set<String> presentPeerIds = new java.util.HashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var data = fetchFeishuChatPage(token, pageToken);
            if (data == null) {
                break;
            }
            pagesOk++;
            for (var item : data.path("items")) {
                total++;
                String chatId = item.path("chat_id").asText("");
                if (chatId.isBlank()) {
                    skipped++;
                    continue;
                }
                presentPeerIds.add(chatId);
                String name = item.path("name").asText("");
                // chat_mode: "group" | "p2p" | "topic"；非 p2p 一律当群（topic 也是群的一种）
                String kind = "p2p".equals(item.path("chat_mode").asText("")) ? "p2p" : "group";
                boolean existed = exists(inst.id(), chatId);
                try {
                    chatService.upsert(inst.id(), "feishu", chatId, name, kind,
                            null, null, null, ImChatService.SOURCE_LISTED);
                    if (existed) {
                        updated++;
                    } else {
                        created++;
                    }
                } catch (RuntimeException e) {
                    skipped++;
                    log.warn("[ImChatSync] 飞书单条同步失败 chatId={} err={}", chatId, e.toString());
                }
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = data.path("page_token").asText("");
            if (pageToken.isBlank()) {
                break;
            }
        }

        // [V335] 同步成功过至少一页 ⇒ 用 bot 群集合回写「机器人是否在群」。
        //   一页都没成功（pagesOk=0）⇒ 不动，防平台抖动把整渠道的会话隐藏。
        if (pagesOk > 0) {
            try {
                chatService.markBotPresence(inst.id(), presentPeerIds);
            } catch (RuntimeException e) {
                log.warn("[ImChatSync] 回写机器人可达性失败 instance={} err={}", inst.id(), e.toString());
            }
        }

        // [ou_→name] 群同步完再补一次私聊名：飞书单聊的名字在「对方这个人」身上
        //   （contact/v3/users/{open_id}），不在 chat 上；缺通讯录权限时静默跳过。
        int resolvedNames = resolveFeishuP2pNames(inst.id(), token);

        String message = String.format("已同步 %d 个飞书群（新建 %d / 更新 %d / 跳过 %d）%s",
                created + updated, created, updated, skipped,
                resolvedNames > 0 ? "，私聊补名 " + resolvedNames : "");
        log.info("[ImChatSync] 飞书群同步完成 instance={} total={} created={} updated={} skipped={} resolvedNames={}",
                inst.id(), total, created, updated, skipped, resolvedNames);
        return new SyncResult(inst.id(), total, created, updated, skipped, message);
    }

    /**
     * 同步「本人所在」的飞书群到聊天目录（user_access_token · source=listed）。
     *
     * <h2>与 {@link #syncFeishuChats} 的区别</h2>
     * 前者用<b>机器人身份</b>（tenant_access_token）列「机器人加入的群」；本方法用
     * <b>用户身份</b>（渠道卡片「授权本人身份」拿到的 user_access_token）列「这个人加入的群」
     * —— 机器人不在群里、但用户本人所在的群，只有这一条路能看到。
     *
     * <h2>为什么只 upsert {@code im_chat}、不落消息</h2>
     * 当前目的是「让这些群出现在频道列表以便绑定」。仓里没有 IM 消息表，
     * 拉历史只为填 {@code lastMessagePreview} / {@code lastMessageAt}；
     * 拉不到消息的群照样建行（预览留空），计入 {@code skipped} 而不是判失败。
     *
     * <h2>⛔ 不假成功</h2>
     * 未授权 / 续期失败 / scope 不足 / 平台报错一律抛业务异常，绝不返回 {@code total=0}
     * 让界面看起来「同步成功但一个群都没有」。只有「确实一个群都没有」才返回 0 并说明原因。
     *
     * @param instanceId 目标实例；null/空白 ⇒ 取第一个飞书实例
     */
    public SyncResult syncFeishuUserChats(String instanceId) {
        Optional<ImInstanceEntry> instOpt = resolveInstance(instanceId, "feishu");
        if (instOpt.isEmpty()) {
            throw new ValidationException((instanceId == null || instanceId.isBlank())
                    ? "还没有飞书渠道实例，请先在「IM 频道」里添加飞书"
                    : "找不到该实例或它不是飞书渠道：" + instanceId);
        }
        ImInstanceEntry inst = instOpt.get();

        Optional<String> tokenOpt = channelService.userTokenOf(inst.id());
        if (tokenOpt.isEmpty()) {
            // userTokenOf 过期时会静默续期；仍为空 ⇒ 要么从没授权，要么续期失败。
            // 两者动作都是「重新授权」，但原因必须说清，不能含糊成一句「没有令牌」。
            if (channelService.hasUserToken(inst.id())) {
                throw new ValidationException(
                        "飞书本人身份授权已过期，且自动续期失败，请先在渠道卡片重新点「授权本人身份」");
            }
            throw new ValidationException("未授权本人身份，请先在渠道卡片点「授权本人身份」");
        }
        String userToken = tokenOpt.get();

        // ── ① 列「本人所在的全部群」（翻页取全）──
        List<String[]> chats = new ArrayList<>();   // [chatId, name, chatKind]
        int total = 0;
        String pageToken = null;
        boolean truncated = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            var data = fetchFeishuUserChatPage(userToken, pageToken);
            for (var item : data.path("items")) {
                total++;
                String chatId = item.path("chat_id").asText("");
                if (chatId.isBlank()) {
                    continue;   // 缺 id 无法 upsert
                }
                String name = item.path("name").asText("");
                String kind = "p2p".equals(item.path("chat_mode").asText("")) ? "p2p" : "group";
                chats.add(new String[]{chatId, name, kind});
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = data.path("page_token").asText("");
            if (pageToken.isBlank()) {
                break;
            }
            if (page == MAX_PAGES - 1) {
                truncated = true;   // has_more 仍为真但已到硬上限
            }
        }

        if (chats.isEmpty()) {
            String message = total == 0
                    ? "本人当前没有加入任何飞书群（或应用看不到这些群）"
                    : "本人所在的飞书群缺少 chat_id，未写入目录";
            return new SyncResult(inst.id(), total, 0, 0, total, message);
        }

        // ── ② 逐群拉最近一页消息（取最新一条做预览）──
        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (String[] row : chats) {
            String chatId = row[0];
            String name = row[1];
            String kind = row[2];

            String preview = null;
            String lastAt = null;
            MessageInfo info = fetchLatestFeishuMessage(userToken, chatId);
            if (info != null) {
                preview = info.preview();
                lastAt = info.lastAt();
            }
            boolean noMessage = preview == null && lastAt == null;
            if (noMessage) {
                skipped++;   // 拉不到 / 该群没有消息：仍建行，预览留空
            }

            boolean existed = exists(inst.id(), chatId);
            try {
                chatService.upsert(inst.id(), "feishu", chatId, name, kind,
                        null, preview, lastAt, ImChatService.SOURCE_LISTED);
                if (existed) {
                    updated++;
                } else {
                    created++;
                }
            } catch (RuntimeException e) {
                if (!noMessage) {
                    skipped++;   // 已按「无消息」计过就不再重复计
                }
                log.warn("[ImChatSync] 飞书本人群单条同步失败 chatId={} err={}", chatId, e.toString());
            }
        }

        String message = String.format(
                "已同步本人所在 %d 个飞书群（新建 %d / 更新 %d / 无消息 %d）%s",
                chats.size(), created, updated, skipped,
                truncated ? "；⚠️ 已达翻页上限，可能未拉全" : "");
        log.info("[ImChatSync] 飞书本人群同步完成 instance={} total={} created={} updated={} skipped={} truncated={}",
                inst.id(), total, created, updated, skipped, truncated);
        return new SyncResult(inst.id(), total, created, updated, skipped, message);
    }

    /**
     * 飞书私聊补名（<b>真名优先</b>，2026-11-23 用户裁决）。
     *
     * <ol>
     *   <li>{@code ou_} 行：peer_id 就是 open_id，直接查联系人名；</li>
     *   <li>{@code oc_} 行：先 {@code /im/v1/chats/{chat_id}/members} 取对方 open_id
     *       （排除机器人自己），再查联系人名；</li>
     *   <li>通讯录权限缺失：<b>整批</b>走尾号，不逐行试（本轮只探一次）；</li>
     *   <li>{@code nick_name} 非空 = 人工改名，⛔ 不覆盖。</li>
     * </ol>
     *
     * <p>拿不到真名时这里<b>不写库</b>：调用方已有/upsert 会落「飞书私聊 · 尾号 xxxx」，
     * 本方法只负责「能拿到就升级成真名」并打出原因日志。
     */
    int resolveFeishuP2pNames(String instanceId, String token) {
        java.util.List<ImChatEntry> chats;
        try {
            chats = chatService.listByInstance(instanceId);
        } catch (RuntimeException e) {
            log.warn("[ImChatSync] 读飞书聊天目录失败（跳过补名）instance={} err={}", instanceId, e.toString());
            return 0;
        }
        if (nameResolver == null) {
            log.warn("[ImChatName] 解析器未注入，跳过补名 instance={}", instanceId);
            return 0;
        }
        // 每轮只探一次权限：缺权限时整批直接走尾号，避免 N 次失败调用
        String probeOpenId = null;
        try {
            probeOpenId = channelService == null ? null
                    : channelService.userOpenIdOf(instanceId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("[ImChatName] 读授权 open_id 失败 instance={} err={}", instanceId, e.toString());
        }
        FeishuChatNameResolver.ProbeStatus probe = nameResolver.probeContacts(token, probeOpenId);
        // 与机器人的私聊（oc_）用机器人名，不依赖通讯录；ou_ 行才需要 contact
        String botName = nameResolver.fetchBotName(token);

        int attempted = 0;
        int resolved = 0;
        for (ImChatEntry c : chats == null ? java.util.List.<ImChatEntry>of() : chats) {
            if (attempted >= FEISHU_NAME_RESOLVE_LIMIT) {
                break;
            }
            if (c == null || !"feishu".equals(c.platform()) || !"p2p".equals(c.chatKind())) {
                continue;
            }
            String peerId = c.peerId();
            if (peerId == null || peerId.isBlank()) {
                continue;
            }
            // 人工改名优先：nick_name 非空即视为用户/其它链路给过的真名，不覆盖
            if (c.nickName() != null && !c.nickName().isBlank()) {
                log.info("[ImChatName] skip peer={} reason=human_nick", peerId);
                continue;
            }
            if (hasRealName(c.peerName(), peerId)) {
                continue;   // 已是真名，不重复查
            }
            attempted++;
            // ⚠️ 不在这里整批早退：oc_ 行（与机器人的私聊）只要机器人名，不需要通讯录权限；
            //    ou_ 行在 probe=DENIED 时由 resolver 直接返回 NO_PERMISSION（不发请求）。
            FeishuChatNameResolver.Resolved r =
                    nameResolver.resolveP2pName(token, peerId, probe, botName);
            if (r.hasName()) {
                try {
                    chatService.upsert(instanceId, "feishu", peerId, r.name(), "p2p",
                            c.memberCount(), c.lastMessagePreview(), c.lastMessageAt(),
                            ImChatService.SOURCE_LISTED);
                    resolved++;
                    continue;
                } catch (RuntimeException e) {
                    log.warn("[ImChatName] 写回真名失败 peer={} err={}", peerId, e.toString());
                }
            }
            // UNKNOWN 时第一次真正调用若判定无权限 ⇒ 本批后续行不再逐行试
            if (r.reason() == FeishuChatNameResolver.Reason.NO_PERMISSION
                    && probe == FeishuChatNameResolver.ProbeStatus.UNKNOWN) {
                probe = FeishuChatNameResolver.ProbeStatus.DENIED;
            }
            log.info("[ImChatName] fallback peer={} reason={}", peerId, reasonName(r.reason()));
        }
        if (attempted > 0) {
            log.info("[ImChatSync] 飞书私聊补名 instance={} attempted={} resolved={}",
                    instanceId, attempted, resolved);
        }
        return resolved;
    }

    /** 真名 = 非空、不等于 peer_id、也不是「飞书…尾号 …」合成名。 */
    private static boolean hasRealName(String peerName, String peerId) {
        return peerName != null && !peerName.isBlank()
                && !peerName.equals(peerId)
                && !ImChatService.isSyntheticFeishuName(peerName);
    }

    private static String reasonName(FeishuChatNameResolver.Reason r) {
        return r == null ? "api_error" : r.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 一条消息的预览与时间（给 {@code im_chat} 的两列）。 */
    private record MessageInfo(String preview, String lastAt) {}

    /** 拉一页「本人所在群」；失败抛业务异常（⛔ 不静默返回空页假装没群）。 */
    private com.fasterxml.jackson.databind.JsonNode fetchFeishuUserChatPage(String userToken, String pageToken) {
        String url = feishuOpenBase + FEISHU_CHATS_PATH + "?page_size=" + FEISHU_CHATS_PAGE_SIZE;
        if (pageToken != null && !pageToken.isBlank()) {
            url += "&page_token=" + java.net.URLEncoder.encode(pageToken, java.nio.charset.StandardCharsets.UTF_8);
        }
        return feishuGetUser(url, userToken, "拉飞书群列表");
    }

    /**
     * GET open-apis（Bearer = 用户身份）。
     *
     * @return 响应的 {@code data} 节点
     * @throws ValidationException 平台业务错误（scope/授权问题，需要用户处理）
     * @throws BadGatewayException 网络/HTTP 层失败（服务端问题，可重试）
     */
    private com.fasterxml.jackson.databind.JsonNode feishuGetUser(String url, String userToken, String what) {
        java.net.http.HttpResponse<String> resp;
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + userToken)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadGatewayException(what + " 被中断");
        } catch (Exception e) {
            throw new BadGatewayException(what + " 失败：" + e.getClass().getSimpleName());
        }
        String raw = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new ValidationException(what + " 被拒（HTTP " + resp.statusCode()
                    + "）：本人身份授权可能已失效，请重新授权");
        }
        if (resp.statusCode() != 200) {
            throw new BadGatewayException(what + " 返回 HTTP " + resp.statusCode());
        }
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception e) {
            throw new BadGatewayException(what + " 返回的不是合法 JSON");
        }
        int code = node.path("code").asInt(-1);
        if (code != 0) {
            throw new ValidationException(feishuRejectMessage(what, code, node.path("msg").asText("")));
        }
        return node.path("data");
    }

    /** 飞书业务错误码 → 中文提示（原始 code/msg 一并透出，便于对平台文档）。 */
    private static String feishuRejectMessage(String what, int code, String msg) {
        String hint = switch (code) {
            case 99991672 -> "缺少所需权限（scope）：请让管理员在开放平台开通 im:chat:read 等权限后，重新点「授权本人身份」";
            case 99991663, 99991677 -> "本人身份令牌无效或已过期，请重新授权";
            default -> "请检查应用权限与本人授权状态";
        };
        return what + " 被飞书拒绝：code=" + code + "（" + hint + "）"
                + (msg == null || msg.isBlank() ? "" : " msg=" + msg);
    }

    /**
     * 拉该群最近一页消息，取 {@code create_time} 最大的一条做预览。
     *
     * <p>失败/无消息一律返回 null（调用方把预览留空并计 skipped）——
     * 单个群读不到不该让整次同步失败。
     */
    private MessageInfo fetchLatestFeishuMessage(String userToken, String chatId) {
        String url = feishuOpenBase + FEISHU_MESSAGES_PATH
                + "?container_id_type=chat&container_id="
                + java.net.URLEncoder.encode(chatId, java.nio.charset.StandardCharsets.UTF_8)
                + "&page_size=" + FEISHU_MSG_PAGE_SIZE;
        com.fasterxml.jackson.databind.JsonNode data;
        try {
            data = feishuGetUser(url, userToken, "拉群消息");
        } catch (RuntimeException e) {
            log.warn("[ImChatSync] 拉飞书群消息失败 chatId={} err={}", chatId, e.getMessage());
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode best = null;
        long bestTime = Long.MIN_VALUE;
        for (var m : data.path("items")) {
            long t = parseEpochMillis(m.path("create_time").asText(""));
            if (best == null || t > bestTime) {
                best = m;
                bestTime = t;
            }
        }
        if (best == null) {
            return null;
        }
        String lastAt = bestTime == Long.MIN_VALUE ? null : toIso(bestTime);
        return new MessageInfo(previewOf(best), lastAt);
    }

    /** 消息 → 一句预览：文本取 body.content.text；其它类型用 [msg_type] 占位。 */
    private static String previewOf(com.fasterxml.jackson.databind.JsonNode item) {
        String type = item.path("msg_type").asText("");
        String content = item.path("body").path("content").asText("");
        if ("text".equals(type) && !content.isBlank()) {
            try {
                String t = JSON.readTree(content).path("text").asText("");
                if (!t.isBlank()) {
                    String oneLine = t.replaceAll("\\s+", " ").trim();
                    return oneLine.length() > 100 ? oneLine.substring(0, 100) : oneLine;
                }
            } catch (Exception ignored) {
                // content 不是 JSON ⇒ 落到下面的类型占位
            }
        }
        return type.isBlank() ? null : "[" + type + "]";
    }

    private static long parseEpochMillis(String s) {
        if (s == null || s.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /** 拉一页飞书群列表；失败返回 null（调用方据此停止，不抛异常）。 */
    private com.fasterxml.jackson.databind.JsonNode fetchFeishuChatPage(String token, String pageToken) {
        String url = FEISHU_DEFAULT_BASE + FEISHU_CHATS_PATH + "?page_size=" + FEISHU_CHATS_PAGE_SIZE;
        if (pageToken != null && !pageToken.isBlank()) {
            url += "&page_token=" + java.net.URLEncoder.encode(pageToken, java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[ImChatSync] 拉飞书群列表 HTTP {}", resp.statusCode());
                return null;
            }
            var node = JSON.readTree(resp.body());
            int code = node.path("code").asInt(-1);
            if (code != 0) {
                log.warn("[ImChatSync] 拉飞书群列表被拒 code={} msg={}",
                        code, node.path("msg").asText(""));
                return null;
            }
            return node.path("data");
        } catch (Exception e) {
            log.warn("[ImChatSync] 拉飞书群列表失败: {}", e.toString());
            return null;
        }
    }

    /** 取第一个非空文本字段（兼容蛇形/驼峰两种历史写法）。 */
    private static String text(com.fasterxml.jackson.databind.JsonNode root, String... keys) {
        if (root == null) {
            return null;
        }
        for (String k : keys) {
            var n = root.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText().trim();
            }
        }
        return null;
    }

    /**
     * 同步企微「最近有消息的群」到聊天目录（source=listed）。
     *
     * <h2>⚠️ 只覆盖最近 7 天有消息的群</h2>
     * 端点 {@code POST /cli/chat/groups/list} 的 schema 要求 {@code begin_time/end_time}，
     * 且平台只返回该窗口内**有消息**的会话（参考实现把窗口硬限制在 7 天）。
     * 所以「很久没说话的群」拉不到 —— 这是平台限制，不是漏做。
     *
     * <h2>时间格式（PIT-WC-028）</h2>
     * {@code YYYY-MM-DD HH:MM:SS} **字符串**，不是毫秒。直接塞 {@code currentTimeMillis()}
     * 必被拒。
     */
    public SyncResult syncWecomChats(String instanceId) {
        Optional<ImInstanceEntry> instOpt = resolveInstance(instanceId, "wecom");
        if (instOpt.isEmpty()) {
            String why = (instanceId == null || instanceId.isBlank())
                    ? "还没有企业微信渠道实例，请先在「IM 频道」里添加企业微信"
                    : "找不到该实例或它不是企微渠道：" + instanceId;
            log.warn("[ImChatSync] 跳过企微同步：{}", why);
            return new SyncResult(instanceId, 0, 0, 0, 0, why);
        }
        ImInstanceEntry inst = instOpt.get();

        String botId;
        String botSecret;
        try {
            Optional<String> credOpt = channelService.credentialOf(inst.id());
            if (credOpt.isEmpty()) {
                return new SyncResult(inst.id(), 0, 0, 0, 0, "该企微实例没有可用凭证");
            }
            var root = JSON.readTree(credOpt.get());
            botId = text(root, "botId", "botid");
            botSecret = text(root, "botSecret", "secret");
        } catch (Exception e) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "企微凭证不是合法 JSON");
        }
        if (botId == null || botSecret == null) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "企微凭证缺少 botId/botSecret");
        }

        String token;
        try {
            token = wecomTokenService.tokenFor(inst.id(), botId, botSecret);
        } catch (com.nexusai.domain.im_channel.send.ImSendException e) {
            return new SyncResult(inst.id(), 0, 0, 0, 0, "换取企微令牌失败：" + e.getMessage());
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.ZonedDateTime end = java.time.ZonedDateTime.now(zone);
        // 平台只返回窗口内有消息的群；参考实现硬限制 7 天，这里对齐
        java.time.ZonedDateTime begin = end.minusDays(7);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int total = 0;

        // ── ① 优先拉「机器人所在的会话」（POST /message/aibot/sessions/list）──
        // 为什么先走这个：{@code /chat/groups/list} 要求 begin/end 时间窗，**只返回窗口内
        // 有消息**的群 —— 一个很久没人说话的群会被漏掉（实测：群最后一条是 11 天前 ⇒ 拉不到）。
        // sessions/list 无入参、无时间窗，返回机器人最近参与的会话（最多 20 个）。
        boolean gotSessions = false;
        try {
            var sr = wecomClient.call(token, "/message/aibot/sessions/list", JSON.createObjectNode());
            if (sr != null && sr.ok() && sr.result() != null) {
                gotSessions = true;
                for (var node : sr.result().path("sessions")) {
                    total++;
                    String chatId = node.path("chat_id").asText("");
                    if (chatId.isBlank()) {
                        skipped++;
                        continue;
                    }
                    String name = node.path("chat_name").asText("");
                    // chat_type: single | group；未知值按群处理（企微 aibot 场景以群为主）
                    String kind = "single".equals(node.path("chat_type").asText("")) ? "p2p" : "group";
                    boolean existed = exists(inst.id(), chatId);
                    try {
                        chatService.upsert(inst.id(), "wecom", chatId, name, kind,
                                null, null, null, ImChatService.SOURCE_LISTED);
                        if (existed) {
                            updated++;
                        } else {
                            created++;
                        }
                    } catch (RuntimeException e) {
                        skipped++;
                        log.warn("[ImChatSync] 企微会话单条同步失败 chatId={} err={}", chatId, e.toString());
                    }
                }
                if (log.isInfoEnabled()) {
                    log.info("[ImChatSync] 企微会话列表拉取完成 instance={} sessions={} count={}",
                            inst.id(), total, sr.result().path("sessions_count").asInt(-1));
                }
            } else if (sr != null) {
                log.warn("[ImChatSync] aibot/sessions/list 被拒: {} {}", sr.errcode(), sr.errmsg());
            }
        } catch (Exception e) {
            log.warn("[ImChatSync] 拉企微会话列表失败（回落到按时间窗的群列表）: {}", e.toString());
        }

        // ── ② 补充群名：按时间窗拉群（7 天内**有消息**的）──
        // 为什么要跑这一步：`aibot/sessions/list` 对某些群**不返回 chat_name**
        // （实测：拉到的群 peer_name 直接等于 chat_id），而 `chat/groups/list` 有 `chat_name`。
        // 所以两个端点都跑：sessions 负责"找全会话"，groups 负责"补名字"。
        // ⛔ 不要因为 sessions 拿到数据就跳过 groups —— 那样界面上的群名就是一串 id。
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            var body = JSON.createObjectNode();
            body.put("begin_time", begin.format(fmt));
            body.put("end_time", end.format(fmt));
            if (cursor != null && !cursor.isBlank()) {
                body.put("cursor", cursor);
            }
            com.nexusai.domain.im_channel.cli.WecomCliClient.Result r;
            try {
                r = wecomClient.call(token, "/chat/groups/list", body);
            } catch (Exception e) {
                log.warn("[ImChatSync] 拉企微群列表失败: {}", e.toString());
                break;
            }
            if (r == null || !r.ok()) {
                log.warn("[ImChatSync] 拉企微群列表被拒: {}",
                        r == null ? "null" : (r.errcode() + " " + r.errmsg()));
                break;
            }
            var data = r.result();
            if (data != null) {
                for (var item : data.path("items")) {
                    total++;
                    String chatId = item.path("chat_id").asText("");
                    if (chatId.isBlank()) {
                        skipped++;
                        continue;
                    }
                    String name = item.path("chat_name").asText("");
                    boolean existed = exists(inst.id(), chatId);
                    try {
                        // 端点文档「目前仅返回群聊会话」
                        chatService.upsert(inst.id(), "wecom", chatId, name, "group",
                                null, null, null, ImChatService.SOURCE_LISTED);
                        if (existed) {
                            updated++;
                        } else {
                            created++;
                        }
                    } catch (RuntimeException e) {
                        skipped++;
                        log.warn("[ImChatSync] 企微单条同步失败 chatId={} err={}", chatId, e.toString());
                    }
                }
                String next = data.path("next_cursor").asText("");
                boolean hasMore = data.path("has_more").asBoolean(false);
                if (!hasMore || next.isBlank() || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            } else {
                break;
            }
        }

        String message = gotSessions
                ? String.format("已同步 %d 个企微会话（新建 %d / 更新 %d / 跳过 %d）",
                        created + updated, created, updated, skipped)
                : String.format("已同步 %d 个企微群（最近 7 天有消息的；新建 %d / 更新 %d / 跳过 %d）",
                        created + updated, created, updated, skipped);
        log.info("[ImChatSync] 企微群同步完成 instance={} total={} created={} updated={} skipped={}",
                inst.id(), total, created, updated, skipped);
        return new SyncResult(inst.id(), total, created, updated, skipped, message);
    }

    /** 预览：不同步，只返回会被写进目录的条目（给界面做「同步前看一眼」）。 */
    public List<Map<String, Object>> previewWechatChats(int limit) {
        List<WechatConversationRecord> all = wechatConversationMapper.selectAllOrdered();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int max = limit <= 0 ? 50 : Math.min(limit, 500);
        List<Map<String, Object>> out = new ArrayList<>(Math.min(all.size(), max));
        for (WechatConversationRecord c : all) {
            if (out.size() >= max) {
                break;
            }
            if (c == null || c.getConversationId() == null || c.getConversationId().isBlank()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("peerId", c.getConversationId());
            m.put("peerName", c.getDisplayName());
            m.put("chatKind", (c.getIsGroup() != null && c.getIsGroup() == 1) ? "group" : "p2p");
            m.put("lastMessageAt", toIso(c.getLastMessageTime()));
            m.put("messageCount", c.getMessageCount());
            out.add(m);
        }
        return out;
    }

    /**
     * 解析目标实例：显式指定优先；否则取第一个微信实例。
     *
     * <p>为什么「第一个」而不是「全部」：本机微信只有一个账号，多个实例通常是人手动建的
     * （旧 bot 没删）。全部同步会让同一批会话在多个实例下各存一份，界面上重复。
     */
    private Optional<ImInstanceEntry> resolveInstance(String instanceId, String platform) {
        List<ImInstanceEntry> all = channelService.list();
        if (all == null || all.isEmpty()) {
            return Optional.empty();
        }
        if (instanceId != null && !instanceId.isBlank()) {
            return all.stream()
                    .filter(i -> instanceId.equals(i.id()) && platform.equals(i.platform()))
                    .findFirst();
        }
        return all.stream()
                .filter(i -> platform.equals(i.platform()))
                .findFirst();
    }

    /** 精确判断「该 (instance, peer) 是否已有行」。 */
    private boolean exists(String instanceId, String peerId) {
        return chatService.findByInstanceAndPeer(instanceId, peerId).isPresent();
    }

    /** epoch 毫秒 → ISO-8601 偏移时间；null/非法 ⇒ null（不猜）。 */
    private static String toIso(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        try {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

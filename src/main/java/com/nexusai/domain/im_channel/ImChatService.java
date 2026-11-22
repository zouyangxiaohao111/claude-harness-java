package com.nexusai.domain.im_channel;

import com.mybatisflex.core.update.UpdateChain;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImChatActivation;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.repository.im_channel.entity.ImChatRecord;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IM 聊天目录业务逻辑（V305）· 对应界面「IM 频道 › 任务」tab。
 *
 * <p><b>为什么需要独立服务</b>：界面上的「任务」tab 展示的是<b>聊天</b>
 * （张三 / 产品需求群），不是渠道。渠道级绑定（V255 {@code mcp_channel_binding}）
 * 只到 wechat/feishu 这一层，拿不到"哪个聊天、最后说了什么"。
 *
 * <p><b>单一写者</b>：产品层的「聊天 → 任务」接线只写本表 {@code bound_session_id}。
 * ⛔ 不要同时写 {@code mcp_channel_binding.session_id}（那属于 MCP 门序，另一条链）。
 *
 * <p><b>接线规则</b>（用户裁定）：
 * <ul>
 *   <li>一个任务可接多个聊天；</li>
 *   <li>一个聊天只能接一个任务 ⇒ {@code bound_session_id} 单值，改接即覆盖；</li>
 *   <li>改接不静默：调用方负责给用户看"从 A 改到 B"的确认（本层只执行）。</li>
 * </ul>
 */
@Service
public class ImChatService {

    private static final Logger log = LoggerFactory.getLogger(ImChatService.class);

    @Autowired private ImChatMapper mapper;

    public List<ImChatEntry> list() {
        return mapper.selectAllOrdered().stream().map(ImChatRecord::toDomain).toList();
    }

    public List<ImChatEntry> listBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return mapper.selectListByBoundSessionId(sessionId).stream().map(ImChatRecord::toDomain).toList();
    }

    /**
     * 某渠道实例下的全部会话（消息扫描 Job 用）。
     *
     * <p>为什么需要它：扫描是<b>按实例</b>走的（{@code im_scan_channel} 一行 = 一个实例），
     * 而 {@link #list()} 是全量 ⇒ 扫描时必须在服务端按 {@code instance_id} 过滤，
     * 否则会把别的实例的会话也拿去拉消息（跨实例串号）。
     *
     * <p>返回的 {@code peerId} 是<b>平台侧会话 id</b>（飞书 {@code oc_xxx} / 企微加密 chatid /
     * 微信 wxid），正是 {@code ImMessageFetcher.fetch} 要的那个 id（⛔ 不是本地 {@code imch-xxx}）。
     */
    public List<ImChatEntry> listByInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return List.of();
        return mapper.selectListByInstanceId(instanceId).stream().map(ImChatRecord::toDomain).toList();
    }

    public Optional<ImChatEntry> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ImChatRecord r = mapper.selectOneById(id);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    /**
     * 按 {@code (instance_id, peer_id)} 查（V305 唯一键）。
     *
     * <p>用于「同步前判断这条是新还是旧」——{@code im_chat.id} 是插入时随机生成的，
     * 无法从 (instance, peer) 反推，所以必须走这个唯一键查。
     */
    public Optional<ImChatEntry> findByInstanceAndPeer(String instanceId, String peerId) {
        if (instanceId == null || instanceId.isBlank() || peerId == null || peerId.isBlank()) {
            return Optional.empty();
        }
        ImChatRecord r = mapper.selectOneByInstanceAndPeer(instanceId, peerId);
        return r == null ? Optional.empty() : Optional.of(r.toDomain());
    }

    /**
     * 适配器收到消息时 upsert 聊天行（(instance_id, peer_id) 唯一）。
     *
     * <p>不覆盖已存在的接线（{@code bound_session_id} 只在新建时为空）——
     * 否则每条新消息都会把用户手工改过的接线冲掉。
     */
    public ImChatEntry upsertOnInbound(String instanceId, String platform, String peerId,
                                       String peerName, String chatKind, Integer memberCount,
                                       String lastPreview, String lastAt) {
        return upsert(instanceId, platform, peerId, peerName, chatKind, memberCount,
                lastPreview, lastAt, SOURCE_INBOUND);
    }

    /**
     * 通用 upsert（V310 起带数据来源）。
     *
     * <p><b>为什么要有 source</b>：同一个聊天可能有多个数据源 —— 入站消息、本地微信扫描
     * （V301 {@code wechat_conversation}）、平台列表拉取（企微群列表 / 飞书群列表）。
     * 没有来源标记时，后写的一方会把先写的信息冲掉（例如扫描把入站拿到的
     * {@code reply_token} 覆盖成 null）。有了 source，调用方可决定「要不要覆盖非本源的字段」。
     *
     * <p>⚠️ 本方法**不动** {@code reply_token}：令牌只由入站链路写（{@link #saveReplyToken}），
     * 扫描/拉列表不该清掉它 —— 那会让微信从「能发」变回「不能发」。
     *
     * @param source inbound | scanned | listed（见 V310）
     */
    public ImChatEntry upsert(String instanceId, String platform, String peerId,
                              String peerName, String chatKind, Integer memberCount,
                              String lastPreview, String lastAt, String source) {
        if (instanceId == null || instanceId.isBlank()) throw new ValidationException("instanceId 不能为空");
        if (peerId == null || peerId.isBlank()) throw new ValidationException("peerId 不能为空");

        ImChatRecord existing = mapper.selectOneByInstanceAndPeer(instanceId, peerId);
        // V319：平台给的名字同时落到 nick_name。调用方传的 peerName 在拿不到名字时
        // 会退化成 peerId —— 那种情况不能当昵称，否则「昵称」就是一串 wxid。
        String nickname = (peerName == null || peerName.isBlank() || peerName.equals(peerId)
                || isSyntheticFeishuName(peerName))
                ? null : peerName;
        // 飞书拿不到真名（入站事件不带给昵称、群列表可能 name 为空）时，⛔ 不能把
        // oc_/ou_ 当展示名写进列表；给一个可读且能区分的合成名兜底。
        // 只在飞书生效：微信/企微的 peerId 本身就是人类可读的（备注/群名来自扫描）。
        String effectiveKind = chatKind == null || chatKind.isBlank() ? "p2p" : chatKind;
        boolean synthetic = false;
        String effectivePeerName = peerName;
        if ("feishu".equals(platform)
                && (effectivePeerName == null || effectivePeerName.isBlank() || effectivePeerName.equals(peerId))) {
            effectivePeerName = syntheticFeishuName(peerId, effectiveKind);
            synthetic = true;
        }
        if (existing != null) {
            if (nickname != null) existing.setNickName(nickname);
            if (effectivePeerName != null && !effectivePeerName.isBlank()) {
                // 合成名不得覆盖已有的真名（后续同步/入站拿到真名后要能生效）
                boolean existingReal = existing.getPeerName() != null && !existing.getPeerName().isBlank()
                        && !existing.getPeerName().equals(existing.getPeerId())
                        && !isSyntheticFeishuName(existing.getPeerName());
                if (!synthetic || !existingReal) {
                    existing.setPeerName(effectivePeerName);
                }
            }
            if (chatKind != null && !chatKind.isBlank()) existing.setChatKind(chatKind);
            if (memberCount != null) existing.setMemberCount(memberCount);
            if (lastPreview != null) existing.setLastMessagePreview(lastPreview);
            if (lastAt != null) existing.setLastMessageAt(lastAt);
            // 来源只在「首次建立」时定；已存在则保留本源（避免入站数据被扫描标记覆盖）
            if (existing.getSource() == null || existing.getSource().isBlank()) {
                existing.setSource(source == null ? SOURCE_INBOUND : source);
            }
            existing.setUpdatedAt(now());
            mapper.update(existing);
            return existing.toDomain();
        }

        ImChatRecord r = new ImChatRecord();
        r.setId("imch-" + UUID.randomUUID().toString().substring(0, 8));
        r.setInstanceId(instanceId);
        r.setPlatform(platform);
        r.setPeerId(peerId);
        // 与 existing 分支同一口径：feishu 的裸 id 已在上面换成合成名
        r.setPeerName(effectivePeerName == null || effectivePeerName.isBlank() ? peerId : effectivePeerName);
        r.setNickName(nickname);
        r.setChatKind(effectiveKind);
        r.setMemberCount(memberCount);
        r.setLastMessagePreview(lastPreview);
        r.setLastMessageAt(lastAt != null ? lastAt : now());
        r.setSource(source == null || source.isBlank() ? SOURCE_INBOUND : source);
        r.setCreatedAt(now());
        r.setUpdatedAt(now());
        mapper.insert(r);
        log.info("[ImChatService] 新增聊天: instance={} peer={} name={} source={}",
                instanceId, peerId, r.getPeerName(), r.getSource());
        return r.toDomain();
    }

    /** 数据来源常量（V310）。 */
    public static final String SOURCE_INBOUND = "inbound";
    public static final String SOURCE_SCANNED = "scanned";
    public static final String SOURCE_LISTED = "listed";

    /**
     * 把聊天接到某任务（覆盖式：一个聊天只接一个任务）。
     *
     * @param mode auto（ChannelSend 首次发送自动接）| manual（用户显式改接）
     */
    public ImChatEntry bind(String chatId, String sessionId, String mode) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ValidationException("sessionId 不能为空");
        }
        ImChatRecord r = mapper.selectOneById(chatId);
        if (r == null) throw new ValidationException("聊天不存在: " + chatId);

        String prev = r.getBoundSessionId();
        r.setBoundSessionId(sessionId);
        r.setBindMode(mode == null ? "manual" : mode);
        r.setBoundAt(now());
        r.setUpdatedAt(now());
        mapper.update(r);
        log.info("[ImChatService] 接线: chat={} {} → {}", chatId, prev == null ? "(未接)" : prev, sessionId);
        return r.toDomain();
    }

    /**
     * 删除渠道实例前清理该实例下所有聊天的接线。
     *
     * <p>否则删实例后 {@code im_chat.bound_session_id} 仍指向会话 ⇒ 「本会话是否还有渠道」误判为有，
     * R14 的收敛永远不触发。必须用带 set 的链式更新（update(entity) 忽略 null）。
     *
     * @param instanceId 渠道实例 id
     * @return 受影响（原本绑定了会话）的会话 id 去重列表
     */
    public List<String> unbindByInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return List.of();
        }
        List<String> sessions = mapper.selectListByInstanceId(instanceId).stream()
                .map(ImChatRecord::getBoundSessionId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        UpdateChain.of(ImChatRecord.class)
                .set(ImChatRecord::getBoundSessionId, null)
                .set(ImChatRecord::getBindMode, null)
                .set(ImChatRecord::getBoundAt, null)
                .set(ImChatRecord::getUpdatedAt, now())
                .where(ImChatRecord::getInstanceId).eq(instanceId)
                .update();
        if (log.isInfoEnabled() && !sessions.isEmpty()) {
            log.info("[ImChatService] 删除实例前解绑: instance={} 受影响会话={}", instanceId, sessions);
        }
        return sessions;
    }

    /** 断开接线（幂等）。 */
    public ImChatEntry unbind(String chatId) {
        ImChatRecord r = mapper.selectOneById(chatId);
        if (r == null) throw new ValidationException("聊天不存在: " + chatId);
        // 🔴 必须用带 set 的链式更新，⛔ 不能用 update(entity)：
        //    MyBatis-Flex 的 update(entity) 默认**忽略 null 字段**（updateIgnoreNull），
        //    把 boundSessionId 置 null 再 update ⇒ 这一列根本不进 SQL ⇒ 解绑静默失败
        //    （实测：接口返回 unbound=true，库里 bound_session_id 原样还在）。
        UpdateChain.of(ImChatRecord.class)
                .set(ImChatRecord::getBoundSessionId, null)
                .set(ImChatRecord::getBindMode, null)
                .set(ImChatRecord::getBoundAt, null)
                .set(ImChatRecord::getUpdatedAt, now())
                .where(ImChatRecord::getId).eq(chatId)
                .update();
        log.info("[ImChatService] 解绑: chat={} 原 session={}", chatId, r.getBoundSessionId());
        return findById(chatId).orElseThrow(() -> new ValidationException("聊天不存在: " + chatId));
    }

    // ═══════════════ V310 · 回复令牌与出站时间（NXS-UC-CHN-020/021） ═══════════════

    /**
     * 全部聊天的「能否主动发」状态（A 预热提示的数据源）。
     *
     * <p>目前只有微信受 {@code context_token} 约束（iLink 的会话级令牌只能从入站消息拿到），
     * 飞书/企微靠 appId+secret / botId+secret 换票，不需要对方先说话。
     *
     * <p>⛔ 返回值里**没有**令牌本身，只有布尔 + 一句提示。
     * 查询失败 ⇒ 返回空列表（前端按「不知道」处理、不提示）——
     * 宁可少提示一次，也不能因为一次 DB 抖动把所有人标成「未激活」。
     *
     * @return 与 {@link #list()} 同序的状态列表
     */
    public List<ImChatActivation> listActivations() {
        java.util.Set<String> activated = new java.util.HashSet<>();
        try {
            for (ImChatRecord r : mapper.selectListWithReplyToken()) {
                if (r == null || r.getId() == null || r.getId().isBlank()) {
                    continue;
                }
                String exp = r.getReplyTokenExpiresAt();
                // 与 replyTokenOf 同口径：过期 ⇒ 视为没有
                if (exp == null || exp.isBlank() || !expired(exp)) {
                    activated.add(r.getId());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[ImChatService] 批量查回复令牌失败（本次不给激活提示）: {}", e.toString());
            return List.of();
        }
        List<ImChatActivation> out = new java.util.ArrayList<>();
        for (ImChatEntry c : list()) {
            boolean required = "wechat".equals(c.platform());
            boolean ok = activated.contains(c.id());
            out.add(new ImChatActivation(c.id(), c.platform(), c.instanceId(),
                required, ok, required && !ok ? ImChatActivation.WECHAT_INACTIVE_HINT : null));
        }
        return out;
    }

    /**
     * 读该聊天的入站会话令牌（微信 iLink 主动发的硬前提）。
     *
     * <p>为什么单独一个方法、而不是塞进 {@link ImChatEntry}：
     * 这个值能让别人**冒充我们**给对端发消息，不能随 DTO 一路序列化到前端/日志。
     * 只有 {@code ImSendService} 在发起投递前读一次。
     *
     * <p>已过期 ⇒ 返回 empty（调用方按「没有令牌」处理 → 微信策略返回 SKIPPED），
     * 而不是返回一个用了会被平台拒的旧值。
     *
     * @return 可用令牌；聊天不存在 / 无令牌 / 已过期 都是 empty（不抛异常——投递链不能因它崩）
     */
    public Optional<String> replyTokenOf(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        ImChatRecord r = mapper.selectOneById(chatId);
        if (r == null || r.getReplyToken() == null || r.getReplyToken().isBlank()) {
            return Optional.empty();
        }
        String expiresAt = r.getReplyTokenExpiresAt();
        if (expiresAt != null && !expiresAt.isBlank() && expired(expiresAt)) {
            if (log.isDebugEnabled()) {
                log.debug("[ImChatService] 回复令牌已过期: chat={} expiresAt={}", chatId, expiresAt);
            }
            return Optional.empty();
        }
        return Optional.of(r.getReplyToken());
    }

    /**
     * 入站时写入/刷新回复令牌（V310）。
     *
     * <p>⚠️ 目前<b>没有调用方</b> —— 入站适配器尚未接入（见设计 NXS-UC-CHN-007）。
     * 先把它建好，入站接上时直接调用，不用再改表。
     *
     * @param expiresAt ISO-8601；null 表示该平台无过期概念（微信 iLink 的 token 由对端轮换）
     */
    public void saveReplyToken(String chatId, String token, String expiresAt) {
        if (chatId == null || chatId.isBlank() || token == null || token.isBlank()) {
            return;
        }
        ImChatRecord r = mapper.selectOneById(chatId);
        if (r == null) {
            return;
        }
        r.setReplyToken(token);
        r.setReplyTokenExpiresAt(expiresAt);
        r.setUpdatedAt(now());
        mapper.update(r);
    }

    /**
     * 出站成功后更新「最后一条消息」信息（列表排序用）。
     *
     * <p>为什么同时写 {@code last_message_at} 与 {@code last_outbound_at}：
     * 前者是「这个聊天最近有动静」（入站或出站都算），后者只记「我说了话」——
     * 界面要能区分「对方还没回」和「我还没说」。
     */
    public void markOutbound(String chatId, String preview, String at) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        ImChatRecord r = mapper.selectOneById(chatId);
        if (r == null) {
            return;
        }
        String previewText = preview == null ? null
                : (preview.length() > 100 ? preview.substring(0, 100) : preview);
        if (previewText != null) {
            r.setLastMessagePreview(previewText);
        }
        r.setLastMessageAt(at != null ? at : now());
        r.setLastOutboundAt(at != null ? at : now());
        r.setUpdatedAt(now());
        mapper.update(r);
    }

    /** 过期判定：解析失败按「已过期」处理（宁可少发一次，也不发一条会被拒的请求）。 */
    private static boolean expired(String iso) {
        try {
            return OffsetDateTime.parse(iso).isBefore(OffsetDateTime.now());
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * 飞书「拿不到真名」时的可读展示名。
     *
     * <p>{@code oc_xxx}（chat_id）/ {@code ou_xxx}（open_id）对用户毫无意义，
     * 直接展示等于让用户看到一串编码。用「类型 + 尾号」至少能区分是哪个会话，
     * 等同步/入站拿到真名后会被覆盖（见 {@link #upsert} 的 existing 分支）。
     */
    static String syntheticFeishuName(String peerId, String chatKind) {
        String id = peerId == null ? "" : peerId;
        String tail = id.length() >= 4 ? id.substring(id.length() - 4) : id;
        return ("group".equals(chatKind) ? "飞书群 · 尾号 " : "飞书私聊 · 尾号 ") + tail;
    }

    /** 是否是上面那种合成名（用于防止它被当成昵称、或反过来覆盖真名）。 */
    static boolean isSyntheticFeishuName(String name) {
        return name != null
                && (name.startsWith("飞书群 · 尾号 ") || name.startsWith("飞书私聊 · 尾号 "));
    }
}

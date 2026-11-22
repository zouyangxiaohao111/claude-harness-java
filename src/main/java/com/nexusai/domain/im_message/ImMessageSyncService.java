package com.nexusai.domain.im_message;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_channel.catalog.ImChatCandidate;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategy;
import com.nexusai.domain.im_channel.catalog.ImChatListStrategyFactory;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.model.im_message.ImScanChannelEntry;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.domain.im_message.fetch.ImMessageFetcherFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「消息扫描」执行体（V315 的 {@code im_scan_channel} 那一套）。
 *
 * <h2>🔴 这个服务只做「同步数据」，不碰 agent</h2>
 * 旧的「IM 扫描规则」机制是「定时筛本机微信记录 → 命中就<b>入队唤醒 agent</b>」。
 * 那套已经删干净（{@code im_scan_rule} 表保留、代码不再引用）。
 * <p>本服务是新的那一层：
 * <ul>
 *   <li>✅ 每 5 分钟把已启用渠道的<b>新消息</b>拉下来，幂等落进 {@code im_message}；</li>
 *   <li>⛔ <b>不</b>唤醒 agent、<b>不</b>发消息、<b>不</b>跑任何提示词。</li>
 * </ul>
 * ⇒ 「要不要看这些消息、看了要干什么」由 agent 任务自己决定
 * （用 {@code ReadImMessages} 工具读表判断），本层只保证<b>表里有</b>。
 *
 * <h2>增量起点怎么定</h2>
 * 每个会话的拉取起点 = {@code max(实例游标 last_scan_at, 该会话本地最大 sent_at)}：
 * <ul>
 *   <li>本地已有消息 ⇒ 从本地最大时间开始（只拉增量，不重复拉已入库的）；</li>
 *   <li>本地一条都没有 ⇒ 从实例游标开始（首次是 null ⇒ 从平台能给的 earliest 开始回填）。</li>
 * </ul>
 *
 * <h2>🔴 失败时游标怎么动（2026-11-19 改）</h2>
 * <b>旧语义</b>（错的）：任一会话失败 ⇒ 该实例 {@code last_scan_at} 不推进
 * ⇒ 一个坏会话把整个实例钉在同一起点（真机：{@code ZhengChangWei} 这种拉不了的会话，
 * 让好会话永远停在老位置，而那一轮明明已经拉到 16 条新消息）。
 * <p><b>新语义</b>：
 * <ul>
 *   <li><b>部分会话失败</b>（有的拉到、有的没拉到）⇒ 游标<b>照常推进</b>（写本轮窗口上界）。
 *       失败会话本地没有数据 ⇒ 它下轮的起点 = {@code max(实例游标, 本地最大 sent_at)}
 *       = 新游标 ⇒ 只拉增量，不会重拉全量；</li>
 *   <li><b>全部会话都失败</b>（且会话数 &gt; 0）⇒ 游标<b>不推进</b>
 *       —— 这才是「凭证坏了 / 平台整体拒绝」的信号；</li>
 *   <li>会话数为 0 ⇒ 按现状（没东西可扫）。</li>
 * </ul>
 * 失败原因仍然写进 {@code last_error}（摘要形式：{@code N/M 个会话失败: peer(原因)，
 * …(共 N 个)}）⇒ 用户看得见，但不会被一屏错误淹掉。
 *
 * <h2>为什么逐会话隔离失败</h2>
 * 一个会话拉不动（凭证过期 / 平台限流 / 会话已删）不该让整个实例的扫描停摆
 * —— 那是「一个群坏了，别的群也扫不到」的经典故障。
 */
@Service
public class ImMessageSyncService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageSyncService.class);

    /** 单会话单轮最多拉多少条（再多上下文也装不下，且拉不完说明增量太大）。 */
    public static final int MAX_MESSAGES_PER_CHAT = 200;

    /** 失败原因写进 {@code last_error} 时的长度上限（免得把整屏堆进一列）。 */
    private static final int MAX_ERROR_LEN = 500;

    /** 失败摘要里最多列几个会话（超出的只给总数）。 */
    private static final int MAX_ERROR_ITEMS = 3;

    /** 单个失败原因（摘要里）的长度上限。 */
    private static final int MAX_REASON_LEN = 80;

    @Autowired private ImScanChannelService scanChannelService;
    /** 任务级暂停开关（V318）· 暂停时整轮跳过，渠道勾选原样保留。 */
    @Autowired private ImScanTaskService scanTaskService;
    @Autowired private ImChatService chatService;
    @Autowired private ImChannelService channelService;
    @Autowired private ImMessageService messageService;
    @Autowired private ImMessageFetcherFactory fetcherFactory;

    /**
     * 平台会话列表策略（可选注入）。
     *
     * <p>null ⇒ 只用本地目录 {@code im_chat} 里已有的会话（单测 / 未装配时）。
     * 非 null ⇒ 先从平台刷新一遍目录（企微 {@code aibot/sessions/list} + {@code chat/groups/list}、
     * 飞书 {@code im/v1/chats}、微信本机库），再扫。
     */
    @Autowired(required = false)
    private ImChatListStrategyFactory chatListStrategyFactory;

    /** 重入保护：同一时刻只跑一轮。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 一轮扫描的统计。
     *
     * @param instances      参与的渠道实例数
     * @param chats          扫过的会话数（成功的）
     * @param newMessages    本轮<b>新插入</b>的消息数（幂等命中不算）
     * @param failedInstances 本轮<b>全部会话都失败</b>的实例数（⇒ 游标未推进）
     * @param partialFailedInstances 本轮<b>部分会话失败</b>的实例数（⇒ 游标照常推进，
     *                                失败摘要写在 {@code last_error} 里）
     */
    public record SyncSummary(int instances, int chats, int newMessages, int failedInstances,
                              int partialFailedInstances) {
    }

    /**
     * 跑一轮扫描。
     *
     * @return 本轮统计；没启用任何渠道时返回全 0（不抛异常）
     */
    public SyncSummary runOnce() {
        if (!running.compareAndSet(false, true)) {
            // 上一轮还没跑完（平台慢 / 会话很多）⇒ 本轮直接跳过，别叠第二轮
            log.info("[ImMessageSync] 上一轮尚未结束，本轮跳过");
            return new SyncSummary(0, 0, 0, 0, 0);
        }
        try {
            return doRun();
        } catch (RuntimeException e) {
            log.error("[ImMessageSync] 本轮扫描异常", e);
            return new SyncSummary(0, 0, 0, 0, 0);
        } finally {
            running.set(false);
        }
    }

    private SyncSummary doRun() {
        // 任务级暂停：Job 照常到点触发，但暂停期间不拉任何渠道
        // （⛔ 不动 im_scan_channel ⇒ 恢复后用户原来的渠道勾选还在）
        if (scanTaskService.isPaused()) {
            log.info("[ImMessageSync] 系统任务已暂停，本轮跳过");
            return new SyncSummary(0, 0, 0, 0, 0);
        }
        List<ImScanChannelEntry> channels = scanChannelService.listEnabled();
        if (channels == null || channels.isEmpty()) {
            // 一行 info 就够：每 5 分钟一次，不要刷屏
            log.info("[ImMessageSync] 没有启用扫描的渠道，本轮跳过");
            return new SyncSummary(0, 0, 0, 0, 0);
        }

        int chatTotal = 0;
        int newTotal = 0;
        int failed = 0;
        int partialFailed = 0;
        for (ImScanChannelEntry channel : channels) {
            if (channel == null || channel.instanceId() == null || channel.instanceId().isBlank()) {
                continue;
            }
            InstanceScanResult r = scanInstance(channel);
            chatTotal += r.chats();
            newTotal += r.newMessages();
            if (r.error() == null) {
                continue;
            }
            if (r.cursorAdvanced()) {
                // 部分失败：游标照常推进（只是把失败摘要写进 last_error）
                partialFailed++;
            } else {
                failed++;
            }
        }
        log.info("[ImMessageSync] 本轮完成: 实例 {} 个 / 会话 {} 个 / 新增 {} 条 / 失败实例 {} 个 / 部分失败实例 {} 个",
                channels.size(), chatTotal, newTotal, failed, partialFailed);
        return new SyncSummary(channels.size(), chatTotal, newTotal, failed, partialFailed);
    }

    /**
     * 扫一个渠道实例。
     *
     * @return 会话数 / 新增数 / 失败原因（null = 本实例成功）/ 游标是否推进
     */
    private InstanceScanResult scanInstance(ImScanChannelEntry channel) {
        String instanceId = channel.instanceId();

        Optional<ImInstanceEntry> instOpt = channelService.findById(instanceId);
        if (instOpt.isEmpty()) {
            // 实例被删了但扫描配置还在 ⇒ 记失败原因（游标不推进），让用户看得见
            String error = "渠道实例不存在: " + instanceId;
            markFailed(instanceId, error);
            return new InstanceScanResult(0, 0, error, false);
        }
        ImInstanceEntry inst = instOpt.get();

        // 本轮的窗口上界：所有会话共用同一个 until，游标也写成它 ⇒ 窗口严丝合缝不漏消息
        String scanUntil = ImMessageTime.canonicalNow();

        // ① 先刷新会话目录（平台实时列表）；失败不致命 ⇒ 退回本地目录
        refreshChatDirectory(inst);

        // ② 按实例取本地目录里的会话（peerId = 平台侧会话 id）
        List<ImChatEntry> chats = chatService.listByInstance(instanceId);
        if (chats == null) {
            chats = List.of();
        }

        int chatCount = 0;
        int newCount = 0;
        int attempted = 0;
        List<ChatFailure> failures = new ArrayList<>();

        for (ImChatEntry chat : chats) {
            if (chat == null || chat.peerId() == null || chat.peerId().isBlank()) {
                continue;
            }
            String peerId = chat.peerId();
            String platform = (chat.platform() != null && !chat.platform().isBlank())
                ? chat.platform() : inst.platform();
            attempted++;
            try {
                ImMessageFetcher fetcher = fetcherFactory.of(platform);
                String since = computeSince(channel.lastScanAt(),
                    messageService.maxSentAt(instanceId, peerId));
                List<ImMessageEntry> fetched = fetcher.fetch(instanceId, peerId, since, scanUntil,
                    MAX_MESSAGES_PER_CHAT);
                // 幂等键由服务层统一推导（dedupKeys=null）—— 调用方自己拼 = 迟早拼错
                int inserted = messageService.upsertBatch(fetched, null);
                chatCount++;
                newCount += inserted;
                if (log.isDebugEnabled()) {
                    log.debug("[ImMessageSync] 会话拉取完成 instance={} peer={} since={} rows={} new={}",
                        instanceId, peerId, since, fetched == null ? 0 : fetched.size(), inserted);
                }
            } catch (Exception e) {
                // ⛔ 单会话失败不影响其它会话：记下来，继续下一个
                failures.add(new ChatFailure(peerId, reasonOf(e)));
                log.warn("[ImMessageSync] 会话拉取失败 instance={} peer={} err={}",
                    instanceId, peerId, e.toString());
            }
        }

        if (failures.isEmpty()) {
            // 成功 ⇒ 游标推进到本轮窗口上界（与 until 同一个时刻 ⇒ 不漏 (T1, T2) 这一段）
            scanChannelService.markScanResult(instanceId, chatCount, newCount, null, scanUntil);
            return new InstanceScanResult(chatCount, newCount, null, true);
        }

        String error = truncate(summarizeFailures(attempted, failures));
        if (chatCount > 0) {
            // 🔴 部分会话成功 ⇒ 游标**照常推进**（否则一个坏会话把好会话钉死在同一起点）。
            //    失败会话本地没数据 ⇒ 它下轮的起点 = max(新游标, 本地最大 sent_at) = 新游标
            //    ⇒ 只拉增量，不会重拉全量。
            scanChannelService.markScanResult(instanceId, chatCount, newCount, error, scanUntil, true);
            return new InstanceScanResult(chatCount, newCount, error, true);
        }
        // 全部会话都失败（会话数 > 0）⇒ 游标**不推进**：这才是「凭证坏了 / 平台整体拒绝」的信号
        scanChannelService.markScanResult(instanceId, chatCount, newCount, error, null, false);
        return new InstanceScanResult(chatCount, newCount, error, false);
    }

    /**
     * 失败摘要：{@code N/M 个会话失败: peer1(原因), peer2(原因)}。
     *
     * <p>只列前 {@link #MAX_ERROR_ITEMS} 个 + 总数 ⇒ 一列 {@code last_error} 塞得下，
     * 用户又看得见「哪几条会话拉不了」。
     */
    static String summarizeFailures(int attempted, List<ChatFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(failures.size()).append('/').append(attempted).append(" 个会话失败: ");
        int n = Math.min(failures.size(), MAX_ERROR_ITEMS);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            ChatFailure f = failures.get(i);
            sb.append(f.peerId()).append('(').append(f.reason()).append(')');
        }
        if (failures.size() > n) {
            sb.append(" …(共 ").append(failures.size()).append(" 个)");
        }
        return sb.toString();
    }

    /**
     * 失败原因（短）：能识别的错误码给一句人话，其余用异常消息。
     *
     * <p>🔴 「会话 id 不是合法 chat_id」必须能被认出来 —— 用户得知道
     * 「这条会话拉不了，去把它清理掉或换成正确的 id」。
     */
    private static String reasonOf(Exception e) {
        if (e instanceof ImMessageFetchException fe) {
            if (ImMessageFetchException.CODE_INVALID_CHAT_ID.equals(fe.code())) {
                return "非法 chat_id";
            }
            String msg = fe.getMessage();
            return shorten(msg == null || msg.isBlank() ? fe.code() : msg);
        }
        return shorten(describe(e));
    }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) {
            return "未知错误";
        }
        return s.length() <= MAX_REASON_LEN ? s : s.substring(0, MAX_REASON_LEN) + "…";
    }

    /**
     * 从平台刷新会话目录（best-effort）。
     *
     * <p>各平台的会话来源完全不同，这一层已经封装好了：
     * 企微 = {@code aibot/sessions/list}（找全会话）+ {@code chat/groups/list}（补群名）；
     * 飞书 = {@code im/v1/chats}；微信 = 本机聊天记录库。
     *
     * <p>⛔ 拉取失败<b>不</b>让本轮失败：本地目录里已有的会话照样能扫
     * （平台列表接口挂了 ≠ 消息接口也挂了）。
     */
    private void refreshChatDirectory(ImInstanceEntry inst) {
        if (chatListStrategyFactory == null) {
            return;
        }
        ImChatListStrategy strategy = chatListStrategyFactory.of(inst.platform());
        if (!strategy.canRefresh()) {
            // 该平台没有会话列表接口（如钉钉）⇒ 只能用本地目录
            return;
        }
        List<ImChatCandidate> candidates;
        try {
            candidates = strategy.fetch(inst.id());
        } catch (Exception e) {
            log.warn("[ImMessageSync] 刷新会话目录失败（退回本地目录）instance={} err={}",
                inst.id(), e.toString());
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (ImChatCandidate c : candidates) {
            if (c == null || c.peerId() == null || c.peerId().isBlank()) {
                continue;
            }
            try {
                chatService.upsert(inst.id(), inst.platform(), c.peerId(), c.peerName(),
                    c.chatKind(), null, null, c.lastMessageAt(), ImChatService.SOURCE_LISTED);
            } catch (RuntimeException e) {
                log.warn("[ImMessageSync] 会话目录 upsert 失败 instance={} peer={} err={}",
                    inst.id(), c.peerId(), e.toString());
            }
        }
    }

    /**
     * 增量起点 = {@code max(实例游标, 该会话本地最大 sent_at)}。
     *
     * <p>两个都为空 ⇒ 返回 null（= 不限起点，由平台按自己的能力回填历史）。
     *
     * <p>⚠️ 两边都要先归一化成规范形态（{@link ImMessageTime#canonical}）：
     * {@code last_scan_at} 是用 {@code ISO_OFFSET_DATE_TIME} 写的，
     * {@code sent_at} 是定宽的 {@code yyyy-MM-dd'T'HH:mm:ss.SSS+08:00}
     * ⇒ 直接比字符串会把「同一时刻的两种写法」当成两个时刻。
     */
    static String computeSince(String lastScanAt, String localMaxSentAt) {
        String cursor = ImMessageTime.canonicalOrSelf(lastScanAt);
        String local = ImMessageTime.canonicalOrSelf(localMaxSentAt);
        if (cursor == null) {
            return local;
        }
        if (local == null) {
            return cursor;
        }
        Instant a = ImMessageTime.toInstant(cursor);
        Instant b = ImMessageTime.toInstant(local);
        if (a == null) {
            return local;
        }
        if (b == null) {
            return cursor;
        }
        return b.isAfter(a) ? local : cursor;
    }

    /** 记一次「整实例失败」（实例不存在等），失败 ⇒ 游标不推进。 */
    private void markFailed(String instanceId, String error) {
        try {
            scanChannelService.markScanResult(instanceId, 0, 0, truncate(error));
        } catch (RuntimeException e) {
            log.warn("[ImMessageSync] 写扫描结果失败 instance={} err={}", instanceId, e.toString());
        }
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank())
            ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + " " + msg;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }

    /**
     * 单实例的扫描结果。
     *
     * @param cursorAdvanced 游标是否推进（false = 全失败 ⇒ 下一轮从旧游标重来）
     */
    private record InstanceScanResult(int chats, int newMessages, String error, boolean cursorAdvanced) {
    }

    /** 单个会话的失败（peerId + 短原因）。 */
    private record ChatFailure(String peerId, String reason) {
    }
}

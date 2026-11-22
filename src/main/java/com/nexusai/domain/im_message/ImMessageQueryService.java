package com.nexusai.domain.im_message;

import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_message.fetch.ImFetchWindow;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.domain.im_message.fetch.ImMessageFetcherFactory;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「本地 ∪ 官方」并集读取（V315）· 对 agent 只暴露一个 {@code ReadImMessages} 工具。
 *
 * <h2>为什么读路径必须是「本地 + 官方」的并集</h2>
 * 单靠任何一边都不够：
 * <ul>
 *   <li><b>只读本地</b>：企微只有 7 天窗口、且读不到 bot 自己发的消息 ⇒ 历史会缺；</li>
 *   <li><b>只读官方</b>：网络/凭证一抖就读不到；而且「我们自己发出去的消息」永远不在官方流里。</li>
 * </ul>
 * ⇒ 本地表是<b>缓存 + 补缺</b>（落库后长期可读），官方接口是<b>增量来源</b>。
 *
 * <h2>⚠️ {@code origin} 的准确含义（别搞混）</h2>
 * <ul>
 *   <li>{@code origin='official'} —— 这条消息<b>来自平台</b>（无论是现在拉的，还是以前拉过落库的）；</li>
 *   <li>{@code origin='local'} —— 这条消息是<b>我们自己发送时写进去的</b>
 *       （企微读不到 bot 自己的消息 ⇒ 那条缺口只能由发送侧补写）。</li>
 * </ul>
 * ⇒ 合并时<b>本地已有的那条为准</b>（它可能带 {@code direction='out'} / {@code origin='local'}），
 * 平台拉回来的同一条不覆盖它。
 *
 * <h2>🔴 时间归一化是这一层的硬要求</h2>
 * {@code sent_at} 靠字典序比较 ⇒ 所有写入方必须走 {@link ImMessageTime}。
 * 本层做两件事：① <b>写</b>前归一化（拉取层已做）② <b>读</b>后再归一化一次
 * （库里若混进了非规范形态，排序也不会错）。
 */
@Service
public class ImMessageQueryService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageQueryService.class);

    /** 工具默认上限（与 {@code ReadImMessagesTool} 一致）。 */
    public static final int DEFAULT_LIMIT = 50;
    /** 工具硬上限（防模型一次要 10 万条把上下文撑爆）。 */
    public static final int MAX_LIMIT = 200;

    @Autowired private ImChatService chatService;
    @Autowired private ImMessageService messageService;
    @Autowired private ImMessageFetcherFactory fetcherFactory;

    /**
     * 读某个会话的消息（本地 ∪ 官方）。
     *
     * @param chatId              <b>本地目录 id</b>（{@code imch-xxx}，来自 {@code ListImChats}）
     * @param sinceIso            起（不含）；null = 不限
     * @param untilIso            止（含）；null = 不限
     * @param senderId            只看某个发送者；null/空 = 全部
     * @param limit               最多返回多少条（自动收敛到 [1, 200]）
     * @param refreshFromPlatform 是否先从平台补拉增量
     * @return 合并去重后的消息（按时间<b>正序</b>）
     */
    public List<ImMessageEntry> read(String chatId, String sinceIso, String untilIso,
                                     String senderId, int limit, boolean refreshFromPlatform) {
        return readWithNotes(chatId, sinceIso, untilIso, senderId, limit, refreshFromPlatform).messages();
    }

    /**
     * 同 {@link #read}，但额外带回「平台/会话名/说明」——
     * 工具要把「这是飞书还是别的渠道」「企微只能补 7 天」告诉模型，这些不在消息列表里。
     */
    public ImMessageReadResult readWithNotes(String chatId, String sinceIso, String untilIso,
                                             String senderId, int limit, boolean refreshFromPlatform) {
        if (chatId == null || chatId.isBlank()) {
            throw new ValidationException("chatId 不能为空");
        }
        ImChatEntry chat = chatService.findById(chatId).orElseThrow(
            () -> new ValidationException("聊天不存在: " + chatId));

        String instanceId = chat.instanceId();
        String platform = chat.platform();
        String peerId = chat.peerId();
        int effectiveLimit = clampLimit(limit);

        // ⚠️ 区间一律归一化后再进 SQL（sent_at 是字典序比较）
        String since = ImMessageTime.canonical(sinceIso);
        String until = ImMessageTime.canonical(untilIso);
        List<String> notes = new ArrayList<>();

        // ── 1. 按需从平台补拉增量（落库）──
        if (refreshFromPlatform) {
            notes.addAll(refresh(instanceId, platform, peerId, since, until, effectiveLimit));
        }

        // ── 2. 读本地（本地表此时已是并集）──
        List<ImMessageEntry> rows;
        if (senderId != null && !senderId.isBlank()) {
            // ⚠️ 按人筛走 SQL（sender_id 有索引）；queryBySender 没有 until 参数
            //    ⇒ until 在内存里补一道（少见，但语义要对）
            rows = messageService.queryBySender(instanceId, peerId, senderId, since, effectiveLimit);
        } else {
            rows = messageService.queryByChat(instanceId, peerId, since, until, effectiveLimit);
        }

        // ── 3. 合并去重（本地为准）+ 归一化 + 按真实时间排序 ──
        List<ImMessageEntry> merged = merge(rows, since, until, effectiveLimit);

        return new ImMessageReadResult(chatId, platform, chat.peerName(), instanceId, merged, notes);
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /**
     * 从平台补拉增量并落库，返回给用户看的说明。
     *
     * <h2>拉取起点怎么定</h2>
     * {@code max(请求 since, 本地该会话最大 sent_at)}：
     * <ul>
     *   <li>本地还没有消息 ⇒ 起点 = 请求 since ⇒ <b>首次能回填历史</b>；</li>
     *   <li>本地已有 ⇒ 起点 = 本地最大时间 ⇒ 只拉<b>增量</b>（不重复拉已落库的）。</li>
     * </ul>
     * ⚠️ 副作用：本地已有近期消息时，<b>早于本地最大时间、但本地没有的历史不会被回填</b>
     * （要回填得让请求区间整体早于本地最大时间）。这是「增量」语义的必然结果。
     */
    private List<String> refresh(String instanceId, String platform, String peerId,
                                 String since, String until, int limit) {
        List<String> notes = new ArrayList<>();
        ImMessageFetcher fetcher = fetcherFactory.of(platform);
        ImFetchWindow window = fetcher.window();

        String fetchSince = since;
        // ① 平台时间墙（企微 7 天）⇒ 收敛区间 + 给一句说明（⛔ 不报错）
        if (window.limited()) {
            Instant earliest = ImMessageTime.toInstant(window.earliestSince());
            Instant requested = fetchSince == null ? null : ImMessageTime.toInstant(fetchSince);
            if (requested == null || (earliest != null && requested.isBefore(earliest))) {
                fetchSince = window.earliestSince();
                if (window.note() != null && !window.note().isBlank()) {
                    notes.add(window.note());
                }
            }
        }
        // ② 增量游标：本地最大 sent_at 与请求 since 取大者
        String localMax = messageService.maxSentAt(instanceId, peerId);
        if (localMax != null && !localMax.isBlank()) {
            String localMaxCanonical = ImMessageTime.canonical(localMax);
            if (localMaxCanonical != null) {
                Instant localAt = ImMessageTime.toInstant(localMaxCanonical);
                Instant wantAt = fetchSince == null ? null : ImMessageTime.toInstant(fetchSince);
                if (localAt != null && (wantAt == null || localAt.isAfter(wantAt))) {
                    fetchSince = localMaxCanonical;
                }
            }
        }

        try {
            List<ImMessageEntry> fetched = fetcher.fetch(instanceId, peerId, fetchSince, until, limit);
            if (fetched == null || fetched.isEmpty()) {
                notes.add("平台没有可补拉的新消息");
                return notes;
            }
            // ⭐ 幂等键由服务层统一推导（dedupKey=null）—— 调用方自己拼 = 迟早拼错
            int inserted = messageService.upsertBatch(fetched, null);
            notes.add("本次从平台补拉 " + fetched.size() + " 条，新增落库 " + inserted + " 条");
        } catch (ImMessageFetchException e) {
            // ⛔ 拉取失败不让整个读取失败：降级为「只用本地记录」并说明原因
            notes.add("平台补拉失败（" + e.getMessage() + "），已回退本地记录");
            log.warn("[ImMessageQuery] 平台补拉失败 instance={} platform={} err={}",
                instanceId, platform, e.toString());
        } catch (RuntimeException e) {
            notes.add("平台补拉异常（" + e.getClass().getSimpleName() + "），已回退本地记录");
            log.warn("[ImMessageQuery] 平台补拉异常 instance={} platform={}", instanceId, platform, e);
        }
        return notes;
    }

    /**
     * 合并 + 去重 + 归一化 + 排序。
     *
     * <p>去重按 {@code dedupKey}：本地已有的那条为准（它可能带 {@code direction='out'}）。
     */
    private List<ImMessageEntry> merge(List<ImMessageEntry> rows, String since, String until, int limit) {
        Map<String, ImMessageEntry> byKey = new LinkedHashMap<>();
        if (rows != null) {
            for (ImMessageEntry e : rows) {
                if (e == null) {
                    continue;
                }
                String key = ImMessageService.dedupKey(e.messageId(), e.chatId(), e.sentAt(),
                    e.senderId(), e.content());
                byKey.putIfAbsent(key, e);
            }
        }

        Instant sinceAt = since == null ? null : ImMessageTime.toInstant(since);
        Instant untilAt = until == null ? null : ImMessageTime.toInstant(until);

        List<ImMessageEntry> out = new ArrayList<>(byKey.size());
        for (ImMessageEntry e : byKey.values()) {
            String canonical = ImMessageTime.canonical(e.sentAt());
            if (canonical == null) {
                // 时间不可解析 ⇒ 无法参与排序/过滤 ⇒ 丢弃（不写「时间不明」的行）
                log.warn("[ImMessageQuery] 丢弃 sent_at 无法解析的消息: {}", e.sentAt());
                continue;
            }
            Instant at = ImMessageTime.toInstant(canonical);
            if (at == null) {
                continue;
            }
            if (sinceAt != null && at.isBefore(sinceAt)) {
                continue;
            }
            if (untilAt != null && at.isAfter(untilAt)) {
                continue;
            }
            out.add(new ImMessageEntry(e.id(), e.instanceId(), e.platform(), e.chatId(),
                e.messageId(), e.senderId(), e.senderName(), e.msgType(), e.content(),
                e.mediaJson(), canonical, e.direction(), e.origin()));
        }
        out.sort(Comparator.comparing(e -> ImMessageTime.toInstant(e.sentAt())));
        if (out.size() > limit) {
            // 保留最近的 N 条，但仍是正序展示
            out = new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 读取结果（含工具要展示的元信息）。
     *
     * @param chatId    本地目录 id
     * @param platform  平台（wechat / feishu / wecom）
     * @param chatName  会话名（可能是 id）
     * @param instanceId 渠道实例 id
     * @param messages  消息（正序）
     * @param notes     给用户/模型看的说明（如「企微仅能补拉最近 7 天」）
     */
    public record ImMessageReadResult(
        String chatId,
        String platform,
        String chatName,
        String instanceId,
        List<ImMessageEntry> messages,
        List<String> notes
    ) {
    }
}

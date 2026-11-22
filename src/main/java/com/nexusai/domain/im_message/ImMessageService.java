package com.nexusai.domain.im_message;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.repository.im_message.entity.ImMessageRecord;
import com.nexusai.repository.im_message.mapper.ImMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 统一消息表业务逻辑（V315）· 三平台「读消息」的落库与查询。
 *
 * <p><b>本服务只做「存」和「查」</b>，不碰任何平台对接（拉取在另一层）。
 *
 * <h2>为什么幂等键必须在这里统一生成</h2>
 * 幂等键的算法散落到各处 = 迟早有一处少拼一个字段 ⇒ 那条路径上的重复拉取会插两条
 * （同一条消息在库里出现两次，agent 读到重复内容，<b>且没有任何报错</b>）。
 * 所以：<b>唯一的生成入口是 {@link #dedupKey(String, String, String, String, String)}</b>，
 * 调用方只负责把原始字段传进来。
 *
 * <h2>为什么「先查后插」而不是「插了再说」</h2>
 * 两种都能实现幂等，选<b>先查后插 + 唯一约束兜底</b>：
 * <ul>
 *   <li>先查：命中唯一索引 {@code uq_im_message_dedup}，一次索引查找即可判定 ⇒
 *       不产生异常、不刷日志，是重复拉取的常态路径；</li>
 *   <li>兜底：并发下两个线程可能同时「查不到」⇒ 后插的那个撞唯一索引 ⇒
 *       捕获该冲突并当成「已存在」（返回 false），保证「重复拉取最多一条」；</li>
 *   <li>⛔ 不用 Flex 的 {@code insert(entity, true)}（ignoreError）：该标志只在
 *       MySQL 方言下生成 {@code INSERT IGNORE}，SQLite 走的 Commons 方言<b>不生效</b>
 *       ⇒ 仍会抛异常，靠不住；</li>
 *   <li>⛔ 不用 {@code insertOrUpdate}：它只按<b>主键</b>判重，而我们的主键是随机生成的
 *       ⇒ 对 (instance_id, dedup_key) 这条真正要防重的唯一索引<b>完全无效</b>。</li>
 * </ul>
 */
@Service
public class ImMessageService {

    private static final Logger log = LoggerFactory.getLogger(ImMessageService.class);

    /** {@code direction}：别人发的。 */
    public static final String DIRECTION_IN = "in";
    /** {@code direction}：我们自己发的（补「企微读不到 bot 自己消息」的缺口）。 */
    public static final String DIRECTION_OUT = "out";
    /** {@code origin}：从平台接口拉的。 */
    public static final String ORIGIN_OFFICIAL = "official";
    /** {@code origin}：我们自己发送时写的。 */
    public static final String ORIGIN_LOCAL = "local";

    /** 去重键前缀：区分「平台 id」与「本地算出的摘要」两个命名空间。 */
    private static final String HASH_PREFIX = "sha256:";

    @Autowired private ImMessageMapper mapper;

    // ═══════════════════════ 去重键 ═══════════════════════

    /**
     * 生成幂等键。
     *
     * <p>规则：<b>有平台 id 用平台 id</b>（飞书 {@code om_xxx} / 微信自增 id）；
     * 没有（企微 ChatMessage 根本没有 messageId）就用
     * {@code (chat_id|sent_at|sender_id|content)} 的 SHA-256 前 32 位十六进制。
     *
     * <p>⚠️ <b>不要把 instanceId 拼进去</b>：表上的唯一索引是
     * {@code UNIQUE(instance_id, dedup_key)} ⇒ instance 已经进了唯一键。
     * 再拼一次的后果是「同一实例的同一平台消息算出两个不同的键」⇒ 索引形同虚设，
     * 重复拉取照样插两条。
     *
     * <p>⚠️ 摘要结果加 {@code "sha256:"} 前缀：否则「平台 id」与「32 位十六进制摘要」
     * 共用一个命名空间 —— 万一某个平台的 id 恰好长成 32 位十六进制，
     * 就会与另一条消息的摘要撞上 ⇒ <b>那条消息被静默吞掉</b>。前缀把这种碰撞彻底排除。
     *
     * <p>⚠️ 已知副作用（V315 文件头已记）：同一人<b>同一秒</b>发<b>完全相同</b>的两条，
     * 会被当成一条（企微 {@code send_time} 只到秒）。概率极低，换三平台一套幂等逻辑，值得。
     */
    public static String dedupKey(String messageId, String chatId, String sentAt,
                                  String senderId, String content) {
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        // 分隔符用 "|" 且 content 放最后：sender_id 是平台 id（不含 "|"）⇒ 拼接无歧义
        String raw = nullSafe(chatId) + "|" + nullSafe(sentAt) + "|" + nullSafe(senderId)
            + "|" + nullSafe(content);
        return HASH_PREFIX + sha256Hex32(raw);
    }

    private static String sha256Hex32(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JRE 坏了 —— 直接炸，别静默降级成弱哈希
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ═══════════════════════ 写入 ═══════════════════════

    /**
     * 幂等写入：同一 {@code (instance_id, dedup_key)} 已存在则跳过，返回是否新插入。
     *
     * @param dedupKey 幂等键；传 null/空白时按 {@link #dedupKey} 规则自动推导
     */
    public boolean upsert(ImMessageEntry e, String dedupKey) {
        if (e == null) throw new ValidationException("消息不能为空");
        if (isBlank(e.instanceId())) throw new ValidationException("instanceId 不能为空");
        if (isBlank(e.chatId())) throw new ValidationException("chatId 不能为空");
        if (isBlank(e.sentAt())) throw new ValidationException("sentAt 不能为空");

        String key = (dedupKey == null || dedupKey.isBlank())
            ? dedupKey(e.messageId(), e.chatId(), e.sentAt(), e.senderId(), e.content())
            : dedupKey;

        // ① 先查：命中唯一索引，重复拉取的常态走这里（不抛异常）
        if (mapper.countByDedupKey(e.instanceId(), key) > 0) {
            return false;
        }

        ImMessageRecord r = ImMessageRecord.fromDomain(e);
        if (isBlank(r.getId())) {
            r.setId("immsg-" + UUID.randomUUID());
        }
        r.setDedupKey(key);
        r.setDirection(isBlank(r.getDirection()) ? DIRECTION_IN : r.getDirection());
        r.setOrigin(isBlank(r.getOrigin()) ? ORIGIN_OFFICIAL : r.getOrigin());
        r.setFetchedAt(now());

        // ② 插：并发下的兜底（两个线程同时「查不到」⇒ 后者撞唯一索引）
        try {
            mapper.insert(r);
            return true;
        } catch (RuntimeException ex) {
            if (isUniqueViolation(ex)) {
                log.debug("[ImMessageService] 幂等跳过（并发撞唯一索引）: instance={} key={}",
                        e.instanceId(), key);
                return false;
            }
            throw ex;
        }
    }

    /**
     * 批量写入（拉取时一次存一批），返回实际新增条数。
     *
     * <p>⚠️ {@code dedupKeys} 与 {@code list} 必须<b>一一对应</b>（下标即配对），
     * 长度不一致直接抛错 —— 宁可报错也不要错配（错配 = 幂等键和消息对不上号 ⇒ 静默脏数据）。
     *
     * @param dedupKeys 可为 null / 空 ⇒ 逐条按 {@link #dedupKey} 规则推导
     */
    public int upsertBatch(List<ImMessageEntry> list, List<String> dedupKeys) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        if (dedupKeys != null && !dedupKeys.isEmpty() && dedupKeys.size() != list.size()) {
            throw new ValidationException("dedupKeys 数量(" + dedupKeys.size()
                + ")与消息数量(" + list.size() + ")不一致");
        }
        int inserted = 0;
        for (int i = 0; i < list.size(); i++) {
            String key = (dedupKeys == null || dedupKeys.size() <= i)
                ? null
                : dedupKeys.get(i);
            if (upsert(list.get(i), key)) {
                inserted++;
            }
        }
        return inserted;
    }

    // ═══════════════════════ 查询 ═══════════════════════

    /** 按会话 + 时间区间查（工具主路径）。since/until 可空。 */
    public List<ImMessageEntry> queryByChat(String instanceId, String chatId,
                                             String sinceIso, String untilIso, int limit) {
        if (isBlank(instanceId) || isBlank(chatId)) {
            return List.of();
        }
        return mapper.selectByChat(instanceId, chatId, sinceIso, untilIso, limit).stream()
            .map(ImMessageRecord::toDomain)
            .toList();
    }

    /** 按发送者筛（「看群里某人的消息」）。三平台统一走本地 SQL。 */
    public List<ImMessageEntry> queryBySender(String instanceId, String chatId, String senderId,
                                               String sinceIso, int limit) {
        if (isBlank(instanceId) || isBlank(chatId) || isBlank(senderId)) {
            return List.of();
        }
        return mapper.selectBySender(instanceId, chatId, senderId, sinceIso, limit).stream()
            .map(ImMessageRecord::toDomain)
            .toList();
    }

    /** 该会话已有的最大 {@code sent_at}（增量游标）；没有则 null。 */
    public String maxSentAt(String instanceId, String chatId) {
        if (isBlank(instanceId) || isBlank(chatId)) {
            return null;
        }
        return mapper.selectMaxSentAt(instanceId, chatId);
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /**
     * 是否是「唯一约束冲突」。
     *
     * <p>只认 SQLite 的 {@code UNIQUE constraint failed}：{@code NOT NULL constraint failed}
     * 是<b>另一种</b>错误（数据缺失），把它当成「重复」吞掉 = 静默丢消息 ⇒ 必须区分开。
     *
     * <p>包级可见：测试要能直接验它对<b>真实</b> SQLite 异常的判别力
     * （「前置检查」那条路永远走不到这里 ⇒ 不单独验就等于没验）。
     */
    static boolean isUniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            String m = c.getMessage();
            if (m != null && m.toUpperCase().contains("UNIQUE CONSTRAINT FAILED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}

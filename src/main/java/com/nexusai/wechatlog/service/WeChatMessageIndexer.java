package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.model.WeChatMessageType;
import com.nexusai.wechatlog.store.MessageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把解密出来的消息摘要写入 NexusAI 的索引表。
 *
 * <h2>⚠️ 只存摘要（用户 2026-11-08 裁定）</h2>
 * <ul>
 *   <li>{@code content_preview}：给人看的一小段（120 字符），<b>不参与检索</b>；</li>
 *   <li>{@code search_text}：截断到 500 字符的检索文本，关键词检索走它；</li>
 *   <li>完整正文仍留在本机解密产物目录，按需读取。</li>
 * </ul>
 * 这样既能在 NexusAI 里快速检索，又不把整份聊天记录复制进产品数据库。
 *
 * <h2>增量策略</h2>
 * 用 {@code (account_wxid, conversation_id, server_id, sort_seq)} 做唯一键 upsert：
 * <ul>
 *   <li>重复同步不会产生重复行（{@code ON CONFLICT DO UPDATE}）；</li>
 *   <li>不做「先删后插」，避免同步中途失败把所有历史清空。</li>
 * </ul>
 */
@Service
public class WeChatMessageIndexer {

    private static final Logger log = LoggerFactory.getLogger(WeChatMessageIndexer.class);

    /** {@code content_preview} 最大长度（仅展示）。 */
    private static final int PREVIEW_MAX = 120;

    /** {@code search_text} 最大长度（检索用）。 */
    private static final int SEARCH_MAX = 500;

    /** 单批写入条数。 */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    @Autowired
    public WeChatMessageIndexer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 索引一批消息（幂等）。
     *
     * @param accountWxid 账号
     * @param messages    消息（通常来自一次 {@link WeChatLogService#sync}）
     * @return 实际写入/更新的行数
     */
    public int index(String accountWxid, List<WeChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int written = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        for (WeChatMessage m : messages) {
            batch.add(new Object[]{
                    UUID.randomUUID().toString(),
                    accountWxid,
                    m.conversationId(),
                    m.serverId(),
                    m.sortSeq(),
                    m.createTime(),
                    m.sender(),
                    m.type(),
                    preview(m.content()),
                    searchText(m.content()),
                    m.isSelf() ? 1 : 0
            });
            if (batch.size() >= BATCH_SIZE) {
                written += flush(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            written += flush(batch);
        }
        return written;
    }

    private int flush(List<Object[]> batch) {
        String sql = """
                INSERT INTO wechat_message
                    (id, account_wxid, conversation_id, server_id, sort_seq,
                     msg_time, sender, msg_type, content_preview, search_text, is_self)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (account_wxid, conversation_id, server_id, sort_seq)
                DO UPDATE SET
                    msg_time        = excluded.msg_time,
                    sender          = excluded.sender,
                    msg_type        = excluded.msg_type,
                    content_preview = excluded.content_preview,
                    search_text     = excluded.search_text,
                    is_self         = excluded.is_self,
                    update_time     = datetime('now')
                """;
        try {
            int[] affected = jdbc.batchUpdate(sql, batch);
            int sum = 0;
            for (int a : affected) {
                // 某些驱动对 upsert 返回 -2（无法计数），此时按 1 计
                sum += (a > 0 ? a : 1);
            }
            return sum;
        } catch (Exception e) {
            log.error("批量写入索引失败（本批 {} 条将丢失）", batch.size(), e);
            return 0;
        }
    }

    /**
     * 刷新会话摘要表（按 SQL 聚合，逐会话 upsert）。
     *
     * <p>⚠️ 不能用「{@code INSERT ... SELECT ... GROUP BY} + {@code ON CONFLICT}」一次写完：
     * 那样 {@code display_name} 只能绑定<b>一个</b>参数，会把所有会话都写成同一个名字。
     * 所以先聚合出 (会话, 消息数, 最近时间)，再逐会话 upsert 各自的展示名。
     *
     * @param accountWxid  账号
     * @param displayNames conversationId → 展示名（来自联系人表）
     */
    public void refreshConversations(String accountWxid, Map<String, String> displayNames) {
        Map<String, String> names = displayNames == null ? Map.of() : displayNames;

        List<ConvAgg> aggs = new ArrayList<>();
        try {
            jdbc.query("""
                            SELECT conversation_id, MAX(msg_time), COUNT(*)
                            FROM wechat_message
                            WHERE account_wxid = ? AND deleted = 0
                            GROUP BY conversation_id
                            """,
                    rs -> {
                        aggs.add(new ConvAgg(rs.getString(1), rs.getLong(2), rs.getInt(3)));
                    }, accountWxid);
        } catch (Exception e) {
            log.warn("聚合会话失败", e);
            return;
        }

        String upsert = """
                INSERT INTO wechat_conversation
                    (id, account_wxid, conversation_id, display_name, is_group,
                     last_message_time, message_count, synced_at)
                VALUES (?,?,?,?,?,?,?, CAST(strftime('%s','now') AS INTEGER))
                ON CONFLICT (account_wxid, conversation_id)
                DO UPDATE SET
                    display_name      = excluded.display_name,
                    is_group          = excluded.is_group,
                    last_message_time = excluded.last_message_time,
                    message_count     = excluded.message_count,
                    synced_at         = excluded.synced_at,
                    update_time       = datetime('now')
                """;

        List<Object[]> batch = new ArrayList<>();
        for (ConvAgg a : aggs) {
            batch.add(new Object[]{
                    UUID.randomUUID().toString(),
                    accountWxid,
                    a.conversationId(),
                    names.getOrDefault(a.conversationId(), a.conversationId()),
                    a.conversationId().endsWith("@chatroom") ? 1 : 0,
                    a.lastTime(),
                    a.count()
            });
        }
        try {
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(upsert, batch);
            }
            log.debug("刷新会话摘要完成: {} 个会话", batch.size());
        } catch (Exception e) {
            log.warn("写会话摘要失败", e);
        }
    }

    /** 会话聚合中间结果。 */
    private record ConvAgg(String conversationId, long lastTime, int count) {
    }

    /**
     * 从本机解密产物里读联系人，构造 conversationId → 展示名。
     *
     * <p>找不到联系人表时返回空 map（展示名回落为 conversationId）。
     */
    public Map<String, String> loadDisplayNames(Path workDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (workDir == null || !Files.isDirectory(workDir)) {
            return out;
        }
        try (var stream = Files.walk(workDir, 3)) {
            Path contactDb = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("contact.db"))
                    .findFirst().orElse(null);
            if (contactDb == null) {
                return out;
            }
            for (MessageReader.ContactInfo c : MessageReader.readContacts(contactDb)) {
                out.put(c.username(), c.displayName());
            }
        } catch (Exception e) {
            log.debug("读取联系人用于展示名失败: {}", e.getMessage());
        }
        return out;
    }

    // ==================================================================
    // 查询（供 AI 工具使用）
    // ==================================================================

    /**
     * 关键词检索。
     *
     * @param accountWxid 账号；null 表示不限
     * @param keyword     关键词（对 search_text 做 LIKE）；null/空表示不限
     * @param conversationId 会话；null 表示不限
     * @param sinceUnix   起始时间（Unix 秒）；0 表示不限
     * @param limit       上限
     */
    public List<Map<String, Object>> search(String accountWxid, String keyword,
                                            String conversationId, long sinceUnix,
                                            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT conversation_id, sender, msg_type, msg_time,
                       content_preview, is_self
                FROM wechat_message
                WHERE deleted = 0
                """);
        List<Object> args = new ArrayList<>();

        if (accountWxid != null && !accountWxid.isBlank()) {
            sql.append(" AND account_wxid = ?");
            args.add(accountWxid);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND search_text LIKE ?");
            args.add("%" + escapeLike(keyword) + "%");
        }
        if (conversationId != null && !conversationId.isBlank()) {
            sql.append(" AND conversation_id = ?");
            args.add(conversationId);
        }
        if (sinceUnix > 0) {
            sql.append(" AND msg_time >= ?");
            args.add(sinceUnix);
        }
        sql.append(" ORDER BY msg_time DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 200)));

        try {
            return jdbc.queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.warn("检索微信索引失败", e);
            return List.of();
        }
    }

    /** 列出会话（按最近消息倒序）。 */
    public List<Map<String, Object>> listConversations(String accountWxid, int limit) {
        String sql = """
                SELECT conversation_id, display_name, is_group,
                       last_message_time, message_count
                FROM wechat_conversation
                WHERE deleted = 0
                """ + (accountWxid != null && !accountWxid.isBlank()
                ? " AND account_wxid = ?" : "")
                + " ORDER BY last_message_time DESC LIMIT ?";
        try {
            if (accountWxid != null && !accountWxid.isBlank()) {
                return jdbc.queryForList(sql, accountWxid,
                        Math.max(1, Math.min(limit, 200)));
            }
            return jdbc.queryForList(sql, Math.max(1, Math.min(limit, 200)));
        } catch (Exception e) {
            log.warn("列出微信会话失败", e);
            return List.of();
        }
    }

    // ==================================================================

    static String preview(String content) {
        if (content == null) {
            return "";
        }
        String t = content.replaceAll("\\s+", " ").trim();
        return t.length() <= PREVIEW_MAX ? t : t.substring(0, PREVIEW_MAX) + "…";
    }

    static String searchText(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SEARCH_MAX ? content : content.substring(0, SEARCH_MAX);
    }

    /** 转义 LIKE 的特殊字符，避免用户输入 {@code %} 变成通配符。 */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 类型名（供工具输出用）。 */
    public static String typeName(int msgType) {
        return WeChatMessageType.name(msgType);
    }
}

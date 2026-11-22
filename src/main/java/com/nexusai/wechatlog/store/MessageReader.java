package com.nexusai.wechatlog.store;

import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.model.WeChatMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从<b>已解密</b>的明文 SQLite 库里读消息、联系人、会话。
 *
 * <h2>微信 4.x 的表结构（与上游互证）</h2>
 * <pre>
 * 每个会话一张表：Msg_&lt;md5(会话wxid)&gt;
 *   同一会话的消息可能分散在 message_0.db / message_1.db ... 多个分片里
 *
 * Msg_xxx(
 *   local_id, server_id, local_type, sort_seq, real_sender_id,
 *   create_time, status, message_content, packed_info_data,
 *   WCDB_CT_message_content, ...
 * )
 *
 * Name2Id(rowid, user_name)          -- real_sender_id → 发送者 wxid
 * contact(username, alias, remark, nick_name, ...)
 * session(username, summary, last_timestamp, ...)
 * </pre>
 *
 * <h2>⚠️ 三个容易踩的坑（都是上游踩过的）</h2>
 * <ol>
 *   <li><b>{@code local_type} 高位</b>：必须 {@code & 0xFFFFFFFF}，否则类型判断全错。</li>
 *   <li><b>{@code message_content} 可能是 zstd</b>：活跃会话的内容几乎都是压缩的，
 *       按 UTF-8 硬读会得到乱码或异常。</li>
 *   <li><b>会话跨分片</b>：同一个 {@code Msg_} 表在多个 {@code message_N.db} 里都有，
 *       必须全部读并按 {@code sort_seq} 合并，否则会丢消息。</li>
 * </ol>
 *
 * <h2>只读约束</h2>
 * 全程 {@code SELECT}，连接以只读模式打开。产物是我们自己解密出来的副本，
 * 绝不触碰微信原始库。
 */
public final class MessageReader {

    private static final Logger log = LoggerFactory.getLogger(MessageReader.class);

    /** {@code Msg_<md5hex>} */
    private static final Pattern MSG_TABLE = Pattern.compile("^Msg_([0-9a-fA-F]{32})$");

    private MessageReader() {
    }

    /**
     * 打开明文库（只读）。
     *
     * <p>⚠️ 只读<b>必须靠 URL 参数</b>（{@code mode=ro}）实现，不能在连接建立后调
     * {@code Connection.setReadOnly(true)} —— SQLite JDBC 会抛
     * <i>"Cannot change read-only flag after establishing a connection"</i>。
     * 这个异常会被上层 catch 成「读库失败 → 0 条消息」，表现得像「解不出数据」，
     * 极难定位（我就在这里踩过一次）。
     *
     * @param plainDb 已解密的 SQLite 文件
     */
    public static Connection openReadOnly(Path plainDb) throws Exception {
        if (!Files.isRegularFile(plainDb)) {
            throw new IllegalArgumentException("明文库不存在: " + plainDb);
        }
        // mode=ro 在打开时即只读；immutable=1 让 SQLite 跳过锁检查（我们是自己的副本）
        String url = "jdbc:sqlite:file:" + plainDb.toAbsolutePath()
                + "?mode=ro&immutable=1";
        return DriverManager.getConnection(url);
    }

    /**
     * 读取某个分片库里的全部会话消息。
     *
     * @param shardDb 一个 {@code message_N.db} 的明文副本
     * @return 该分片里的全部消息（按 sort_seq 升序）
     */
    public static List<WeChatMessage> readShard(Path shardDb) {
        List<WeChatMessage> out = new ArrayList<>();
        try (Connection c = openReadOnly(shardDb)) {
            // Name2Id: rowid → user_name，用于把 real_sender_id 解析成 wxid
            Map<Long, String> nameIndex = readNameIndex(c);

            for (String table : listMessageTables(c)) {
                String conversationId = guessConversationId(table, c, nameIndex);
                if (conversationId == null) {
                    continue;
                }
                out.addAll(readMessagesFromTable(c, table, conversationId, nameIndex));
            }
        } catch (Exception e) {
            // ⚠️ 用 error 而不是 warn：这里吞掉的异常会让结果变成「0 条消息」，
            // 与「真的没有消息」无法区分。必须让它显眼（我踩过一次：setReadOnly 抛异常
            // 被吞，表现成「读不出数据」）。
            log.error("读取分片失败（该分片将被视为空）: {}", shardDb, e);
        }
        out.sort((a, b) -> Long.compare(a.sortSeq(), b.sortSeq()));
        return out;
    }

    // ==================================================================
    // 表发现
    // ==================================================================

    /** 列出库里所有 {@code Msg_<md5>} 表。 */
    static List<String> listMessageTables(Connection c) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && MSG_TABLE.matcher(name).matches()) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    /** {@code Name2Id}：rowid → user_name。 */
    static Map<Long, String> readNameIndex(Connection c) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (!tableExists(c, "Name2Id")) {
            return map;
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT rowid, user_name FROM Name2Id")) {
            while (rs.next()) {
                map.put(rs.getLong(1), rs.getString(2));
            }
        } catch (Exception e) {
            log.debug("读取 Name2Id 失败: {}", e.getMessage());
        }
        return map;
    }

    static boolean tableExists(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 反推 {@code Msg_<md5>} 对应的会话 wxid。
     *
     * <p>表名只给了 md5，反推需要候选集合。做法：从本库的 {@code Name2Id}
     * 和其它表里收集所有可能的会话标识（wxid / {@code xxx@chatroom}），
     * 逐个算 md5 比对。这是上游的做法（chatlog 用 {@code session} 表 + 联系人表）。
     *
     * <p>⚠️ 若候选集里没有匹配（例如会话对象已从联系人删除），返回 null 并跳过 ——
     * 宁可少读，也不要瞎猜一个错误的会话 id 把数据挂到别人名下。
     */
    static String guessConversationId(String msgTable, Connection c, Map<Long, String> nameIndex) {
        Matcher m = MSG_TABLE.matcher(msgTable);
        if (!m.matches()) {
            return null;
        }
        String targetMd5 = m.group(1).toLowerCase();

        for (String candidate : collectConversationCandidates(c, nameIndex)) {
            if (md5Hex(candidate).equals(targetMd5)) {
                return candidate;
            }
        }
        log.debug("无法反推会话 id（候选集里没有匹配）: {}", msgTable);
        return null;
    }

    /** 收集可能的会话标识：Name2Id 的 user_name + 本库里的 sender 值。 */
    static Set<String> collectConversationCandidates(Connection c, Map<Long, String> nameIndex) {
        Set<String> set = new HashSet<>(nameIndex.values());
        // 会话表在自己的库里，但 message 库里也可能有 Contact/Session 快照
        for (String t : new String[]{"Session", "session", "Contact", "contact"}) {
            if (!tableExists(c, t)) {
                continue;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT username FROM " + t)) {
                while (rs.next()) {
                    String u = rs.getString(1);
                    if (u != null && !u.isBlank()) {
                        set.add(u);
                    }
                }
            } catch (Exception ignored) {
                // 列名可能不同，跳过
            }
        }
        return set;
    }

    // ==================================================================
    // 消息读取
    // ==================================================================

    static List<WeChatMessage> readMessagesFromTable(Connection c, String table,
                                                     String conversationId,
                                                     Map<Long, String> nameIndex) {
        List<WeChatMessage> out = new ArrayList<>();
        // 用 * 而不是枚举列，避免不同版本缺列导致整个查询失败
        String sql = "SELECT * FROM " + quote(table) + " ORDER BY sort_seq ASC";
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            while (rs.next()) {
                try {
                    long sortSeq = getLong(rs, md, cols, "sort_seq");
                    long serverId = getLong(rs, md, cols, "server_id");
                    long createTime = getLong(rs, md, cols, "create_time");
                    long localType = getLong(rs, md, cols, "local_type");
                    long realSenderId = getLong(rs, md, cols, "real_sender_id");

                    byte[] rawContent = getBytes(rs, md, cols, "message_content");
                    String text = ZstdDecompressor.toText(rawContent);

                    int baseType = WeChatMessageType.normalize(localType);
                    String rendered = WeChatMessageType.render(baseType, text);

                    String sender = nameIndex.getOrDefault(realSenderId, "");

                    out.add(new WeChatMessage(
                            conversationId,
                            serverId,
                            sortSeq,
                            createTime,
                            sender,
                            baseType,
                            rendered,
                            text,
                            false));   // isSelf 由上层用「自己的 wxid」判定
                } catch (Exception one) {
                    // 单条坏数据不拖垮整表
                    log.debug("跳过一条无法解析的消息: {}", one.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("读取表 {} 失败: {}", table, e.getMessage());
        }
        return out;
    }

    // ==================================================================
    // 联系人 / 会话
    // ==================================================================

    /** 一条联系人记录。 */
    public record ContactInfo(String username, String alias, String remark, String nickName) {
        /** 展示名优先级：备注 > 昵称 > 别名 > wxid。 */
        public String displayName() {
            if (remark != null && !remark.isBlank()) {
                return remark;
            }
            if (nickName != null && !nickName.isBlank()) {
                return nickName;
            }
            if (alias != null && !alias.isBlank()) {
                return alias;
            }
            return username;
        }
    }

    /** 读联系人表。 */
    public static List<ContactInfo> readContacts(Path contactDb) {
        List<ContactInfo> out = new ArrayList<>();
        try (Connection c = openReadOnly(contactDb)) {
            if (!tableExists(c, "contact")) {
                // v4 里实际表名可能是 contact，也可能是别的；用 sqlite_master 找含 username 列的
                log.debug("contact.db 里没有 contact 表");
                return out;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT username, alias, remark, nick_name FROM contact")) {
                while (rs.next()) {
                    out.add(new ContactInfo(
                            rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (Exception e) {
            log.warn("读取联系人失败: {}", contactDb, e);
        }
        return out;
    }

    /**
     * 从 {@code contact.db} 读「会话 id → 展示名」。
     *
     * <h2>真实群名就存在这里</h2>
     * 本机实测（微信 4.1.13）：{@code contact} 表有 {@code nick_name} / {@code remark} 列，
     * 群的记录也在<b>同一张表</b>里（{@code username} 形如 {@code xxx@chatroom}）。
     * 例：{@code 57740621024@chatroom → nick_name='美式小家庭'}。
     *
     * <p>⚠️ 不要去 {@code chat_room} 表找群名：那张表只有
     * {@code owner} / {@code ext_buffer}（protobuf），没有直接的名称列。
     */
    public static Map<String, String> readContactDisplayNames(Path contactDb) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (contactDb == null || !Files.isRegularFile(contactDb)) {
            return out;
        }
        try (Connection c = openReadOnly(contactDb)) {
            if (!tableExists(c, "contact")) {
                log.debug("contact.db 里没有 contact 表");
                return out;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT username, nick_name, remark FROM contact")) {
                while (rs.next()) {
                    String username = rs.getString(1);
                    if (username == null || username.isBlank()) {
                        continue;
                    }
                    String remark = rs.getString(3);
                    String nick = rs.getString(2);
                    String display = (remark != null && !remark.isBlank()) ? remark
                            : (nick != null && !nick.isBlank()) ? nick : null;
                    if (display != null) {
                        out.put(username, display);
                    }
                }
            }
            log.debug("从 contact.db 读到 {} 个展示名", out.size());
        } catch (Exception e) {
            log.warn("读取联系人展示名失败: {}", contactDb, e);
        }
        return out;
    }

    /**
     * 一条最近会话记录。
     *
     * @param username        会话 id（单聊 wxid / 群 {@code xxx@chatroom}）
     * @param summary         最近一条消息的文本摘要
     * @param lastTimestamp   最近消息时间（Unix 秒）
     * @param lastSenderName  最后发言人的<b>显示名</b>（从 {@code last_sender_display_name} 读）
     * @param lastSenderId    最后发言人的 wxid
     * @param sessionTitle    会话标题（若微信写入过；通常为空）
     */
    public record SessionInfo(String username, String summary, long lastTimestamp,
                              String lastSenderName, String lastSenderId,
                              String sessionTitle) {

        /** 旧构造：兼容只关心三个字段的调用方。 */
        public SessionInfo(String username, String summary, long lastTimestamp) {
            this(username, summary, lastTimestamp, null, null, null);
        }

        /**
         * 用于「没有群名时的可识别标签」。
         *
         * <p>微信的群名存在 contact.db（我们可能没拿到密钥），但 session 表里有
         * <b>最后发言人的昵称 + 最近消息摘要</b>，两者合起来通常足以让用户认出是哪个群。
         */
        public String recognisableLabel() {
            StringBuilder sb = new StringBuilder();
            if (sessionTitle != null && !sessionTitle.isBlank()) {
                return sessionTitle;
            }
            if (lastSenderName != null && !lastSenderName.isBlank()) {
                sb.append(lastSenderName).append(": ");
            }
            String s = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
            if (s.length() > 40) {
                s = s.substring(0, 40) + "…";
            }
            sb.append(s);
            return sb.toString().trim();
        }
    }

    /**
     * 读最近会话表。
     *
     * <h2>⚠️ 表名是 {@code SessionTable}，不是 {@code session}</h2>
     * 我最初按 chatlog 的写法找 {@code session} 表，但本机实测微信 4.1.13 的
     * {@code session.db} 里实际叫 {@code SessionTable}（同时还有 {@code SessionDraft}、
     * {@code SessionUnreadStatTable_1} 等）。按错名字找会静默返回空列表 ——
     * 表现为「会话列表永远是空的」，很难发现。
     *
     * <p>所以这里同时尝试多个候选表名（不同微信版本命名不一致）。
     */
    public static List<SessionInfo> readSessions(Path sessionDb) {
        List<SessionInfo> out = new ArrayList<>();
        try (Connection c = openReadOnly(sessionDb)) {
            String table = null;
            for (String t : new String[]{"SessionTable", "session", "Session"}) {
                if (tableExists(c, t)) {
                    table = t;
                    break;
                }
            }
            if (table == null) {
                log.debug("session.db 里找不到会话表（候选: SessionTable/session/Session）");
                return out;
            }

            // 列在不同版本里可能有缺，用 SELECT * + 按名取，缺列降级为 null
            String sql = "SELECT * FROM \"" + table + "\"";
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                while (rs.next()) {
                    out.add(new SessionInfo(
                            str(rs, md, cols, "username"),
                            str(rs, md, cols, "summary"),
                            longVal(rs, md, cols, "last_timestamp"),
                            str(rs, md, cols, "last_sender_display_name"),
                            str(rs, md, cols, "last_msg_sender"),
                            str(rs, md, cols, "session_title")));
                }
            }
        } catch (Exception e) {
            log.warn("读取会话失败: {}", sessionDb, e);
        }
        return out;
    }

    private static String str(ResultSet rs, ResultSetMetaData md, int cols, String col) {
        int idx = findColumn(md, cols, col);
        if (idx < 0) {
            return null;
        }
        try {
            return rs.getString(idx);
        } catch (Exception e) {
            return null;
        }
    }

    private static long longVal(ResultSet rs, ResultSetMetaData md, int cols, String col) {
        int idx = findColumn(md, cols, col);
        if (idx < 0) {
            return 0L;
        }
        try {
            long v = rs.getLong(idx);
            // 有些版本存毫秒，统一换算成秒
            return v > 10_000_000_000L ? v / 1000 : v;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static long getLong(ResultSet rs, ResultSetMetaData md, int cols, String col) {
        int idx = findColumn(md, cols, col);
        if (idx < 0) {
            return 0L;
        }
        try {
            return rs.getLong(idx);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static byte[] getBytes(ResultSet rs, ResultSetMetaData md, int cols, String col) {
        int idx = findColumn(md, cols, col);
        if (idx < 0) {
            return null;
        }
        try {
            return rs.getBytes(idx);
        } catch (Exception e) {
            return null;
        }
    }

    private static int findColumn(ResultSetMetaData md, int cols, String name) {
        for (int i = 1; i <= cols; i++) {
            try {
                if (name.equalsIgnoreCase(md.getColumnLabel(i))) {
                    return i;
                }
            } catch (Exception ignored) {
                // 继续找
            }
        }
        return -1;
    }

    /** 表名加引号（防止保留字/特殊字符）。 */
    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}

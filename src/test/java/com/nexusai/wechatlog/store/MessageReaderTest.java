package com.nexusai.wechatlog.store;

import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.model.WeChatMessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息读取与类型解析的测试。
 *
 * <h2>为什么要造合成库</h2>
 * 真机验证需要微信密钥（要重签名 + 提权），不能作为单测前提。但读库逻辑的
 * <b>正确性</b>必须被验证，所以这里按微信 4.x 的真实表结构建一个明文 SQLite：
 * <pre>
 * Name2Id(rowid, user_name)
 * Msg_&lt;md5(会话)&gt;(sort_seq, server_id, local_type, real_sender_id,
 *                  create_time, message_content, ...)
 * </pre>
 * 然后断言「能不能正确读出来」。这能抓出：表名反推错、列名取错、类型没掩高位、
 * 发送者前缀没剥等具体缺陷。
 */
class MessageReaderTest {

    /** 建一个最小的微信 4.x 结构库。 */
    private Path buildFixture(Path dir) throws Exception {
        Path db = dir.resolve("message_0.db");
        String url = "jdbc:sqlite:" + db;
        String conversation = "wxid_friend001";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            st.execute("CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT)");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO Name2Id(rowid, user_name) VALUES(?,?)")) {
                ps.setLong(1, 1);
                ps.setString(2, "wxid_me");
                ps.addBatch();
                ps.setLong(1, 2);
                ps.setString(2, conversation);
                ps.addBatch();
                ps.executeBatch();
            }

            // 会话表名 = Msg_<md5(会话wxid)>
            String table = "Msg_" + MessageReader.md5Hex(conversation);
            st.execute("CREATE TABLE " + table + "("
                    + "local_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "server_id INTEGER, local_type INTEGER, sort_seq INTEGER,"
                    + "real_sender_id INTEGER, create_time INTEGER,"
                    + "message_content BLOB, packed_info_data BLOB)");

            insert(c, table, 1001, 1, 1700000001L, 2, "wxid_friend001:\n你好，这是一条文本");
            insert(c, table, 1002, 3, 1700000002L, 1, "wxid_me:\n[图片占位]");
            // 带高位子类型的文本：真实类型应为 1
            insert(c, table, 1003, 0x100000001L, 1700000003L, 2, "wxid_friend001:\n高位类型测试");
            // 系统消息
            insert(c, table, 1004, 10000, 1700000004L, 2,
                    "wxid_friend001:\n<sysmsg type=\"revokemsg\">你撤回了一条消息</sysmsg>");
            // appmsg（链接）
            insert(c, table, 1005, 49, 1700000005L, 2,
                    "wxid_friend001:\n<msg><appmsg><type>5</type>"
                            + "<title>一篇文章</title><des>摘要</des></appmsg></msg>");
        }
        return db;
    }

    private void insert(Connection c, String table, long serverId, long localType,
                        long createTime, long senderId, String content) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + table
                        + "(server_id, local_type, sort_seq, real_sender_id, create_time, message_content)"
                        + " VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, serverId);
            ps.setLong(2, localType);
            ps.setLong(3, createTime * 1000);
            ps.setLong(4, senderId);
            ps.setLong(5, createTime);
            ps.setBytes(6, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("按表名反推会话 id，并读出全部消息")
    void readsMessagesAndResolvesConversation(@TempDir Path dir) throws Exception {
        Path db = buildFixture(dir);
        List<WeChatMessage> msgs = MessageReader.readShard(db);

        assertEquals(5, msgs.size(), "应读出 5 条消息");
        for (WeChatMessage m : msgs) {
            assertEquals("wxid_friend001", m.conversationId(),
                    "会话 id 应从 Msg_<md5> 正确反推");
        }
    }

    @Test
    @DisplayName("local_type 高位必须掩掉，否则文本类型判断全错")
    void masksHighBitsOfLocalType(@TempDir Path dir) throws Exception {
        Path db = buildFixture(dir);
        List<WeChatMessage> msgs = MessageReader.readShard(db);

        // 第 3 条用的是 0x100000001，掩掉高位后应是 TEXT(1)
        WeChatMessage highBit = msgs.stream()
                .filter(m -> m.content().contains("高位类型测试"))
                .findFirst().orElseThrow();
        assertEquals(WeChatMessageType.TEXT, highBit.type(),
                "0x100000001 必须被归一化为 1（不掩高位会读成" + highBit.type() + "）");
        assertEquals("高位类型测试", highBit.content(), "发送者前缀应被剥掉");
    }

    @Test
    @DisplayName("文本消息剥掉 <sender>:\\n 前缀，正文里的冒号不受影响")
    void stripsSenderPrefixOnly(@TempDir Path dir) throws Exception {
        Path db = buildFixture(dir);
        List<WeChatMessage> msgs = MessageReader.readShard(db);

        WeChatMessage text = msgs.stream()
                .filter(m -> m.content().contains("这是一条文本"))
                .findFirst().orElseThrow();
        assertEquals("你好，这是一条文本", text.content());
        assertEquals("wxid_friend001", text.sender(), "发送者应从 Name2Id 解析");
    }

    @Test
    @DisplayName("系统消息与 appmsg 各自渲染，不丢内容")
    void rendersSystemAndAppMessages(@TempDir Path dir) throws Exception {
        Path db = buildFixture(dir);
        List<WeChatMessage> msgs = MessageReader.readShard(db);

        WeChatMessage sys = msgs.stream()
                .filter(m -> m.type() == WeChatMessageType.SYSTEM)
                .findFirst().orElseThrow();
        assertTrue(sys.content().startsWith("[系统]"), "系统消息应带标记: " + sys.content());
        assertTrue(sys.content().contains("撤回"), "应保留原文: " + sys.content());

        WeChatMessage app = msgs.stream()
                .filter(m -> m.type() == WeChatMessageType.APP)
                .findFirst().orElseThrow();
        assertTrue(app.content().contains("[链接]"), "appmsg type=5 应渲染为链接: " + app.content());
        assertTrue(app.content().contains("一篇文章"), "应带标题: " + app.content());
    }

    @Test
    @DisplayName("媒体类消息渲染为占位符，而不是把 XML 灌给模型")
    void rendersMediaAsPlaceholder() {
        assertEquals("[图片]", WeChatMessageType.render(WeChatMessageType.IMAGE, "anything"));
        assertEquals("[语音]", WeChatMessageType.render(WeChatMessageType.VOICE, "anything"));
        assertEquals("[视频]", WeChatMessageType.render(WeChatMessageType.VIDEO, "anything"));
    }

    @Test
    @DisplayName("无法反推会话 id 时跳过，不猜")
    void skipsUnknownConversation(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("orphan.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            // 一个 md5 对不上任何已知 wxid 的表
            st.execute("CREATE TABLE Name2Id(rowid INTEGER PRIMARY KEY, user_name TEXT)");
            st.execute("INSERT INTO Name2Id(rowid,user_name) VALUES(1,'wxid_known')");
            st.execute("CREATE TABLE Msg_" + "0".repeat(32)
                    + "(sort_seq INTEGER, local_type INTEGER, create_time INTEGER,"
                    + " real_sender_id INTEGER, message_content BLOB)");
            st.execute("INSERT INTO Msg_" + "0".repeat(32)
                    + " VALUES(1, 1, 1700000000, 1, 'hi')");
        }
        List<WeChatMessage> msgs = MessageReader.readShard(db);
        assertTrue(msgs.isEmpty(), "对不上会话的表应被跳过，而不是挂到一个猜测的 id 上");
    }

    @Test
    @DisplayName("库不存在时安全失败")
    void missingDatabaseFailsSafely(@TempDir Path dir) {
        // readShard 内部 catch，返回空列表而不是抛异常
        List<WeChatMessage> msgs = MessageReader.readShard(dir.resolve("nope.db"));
        assertTrue(msgs.isEmpty());
    }
}

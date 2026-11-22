package com.nexusai.domain.im_message;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.model.im_message.ImScanChannelEntry;
import com.nexusai.repository.im_message.entity.ImMessageRecord;
import com.nexusai.repository.im_message.mapper.ImMessageMapper;
import com.nexusai.repository.im_message.mapper.ImScanChannelMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库验证：V315 迁移生效 + {@code im_message} 的幂等写入 / 区间查询 / 发送者筛选
 * + {@code im_scan_channel} 的 {@code last_error} 置空。
 *
 * <h2>WHY 需要真库（而不是替身）</h2>
 * 这套持久化层的正确性<b>几乎全在「SQL / 映射 / 约束」上</b>，替身测试一条都证明不了：
 * <ul>
 *   <li>V315 的两张表与索引真的建出来了；</li>
 *   <li>{@code ImMessageRecord} 的字段与表列一一对应（MyBatis-Flex 靠 camelCase↔snake_case
 *       约定映射，<b>拼错不报错，只会静默丢值</b>）；</li>
 *   <li>{@code UNIQUE(instance_id, dedup_key)} 真的在拦重复（幂等不是「我们记得跳过」，
 *       而是「库不让插第二条」）；</li>
 *   <li>{@code update(entity)} 忽略 null 这个坑 —— 只有真库回查才能钉住。</li>
 * </ul>
 *
 * <p>用共享稳定 SQLite + Flyway（与全仓其他 Flex 测试同一范式），⛔ 不碰用户真库。
 */
class ImMessagePersistenceTest {

    private static SQLiteDataSource ds;
    private static ImMessageMapper messageMapper;
    private static ImScanChannelMapper scanMapper;
    private static ImMessageService messageService;
    private static ImScanChannelService scanService;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // 迁移到最新（含 V315）
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ImMessageMapper.class, ImScanChannelMapper.class);

        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(ImMessageMapper.class);
        scanMapper = MybatisFlexBootstrap.getInstance().getMapper(ImScanChannelMapper.class);

        messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        scanService = new ImScanChannelService();
        ReflectionTestUtils.setField(scanService, "mapper", scanMapper);
    }

    /**
     * 清空本测试用到的两张表。
     *
     * <p>为什么必须清：{@link MybatisFlexDbTestSupport#sharedDbPath()} 是全套件共用的稳定库，
     * 重跑（或与同 JVM 的其他类混跑）会留下上一轮的行 ⇒ 计数类断言会假失败。
     */
    @BeforeEach
    void cleanTables() {
        var messages = messageMapper.selectAll();
        if (messages != null && !messages.isEmpty()) {
            messageMapper.deleteBatchByIds(messages.stream().map(r -> r.getId()).toList());
        }
        var channels = scanMapper.selectAll();
        if (channels != null && !channels.isEmpty()) {
            scanMapper.deleteBatchByIds(channels.stream().map(r -> r.getInstanceId()).toList());
        }
    }

    // ═══════════════════════ 幂等 ═══════════════════════

    @Test
    @DisplayName("幂等：同一 dedupKey 写两次 ⇒ 库里只有一行，第二次 upsert 返回 false")
    void sameDedupKeyInsertsOnlyOnce() {
        String inst = "inst-idem-" + System.nanoTime();
        String chat = "chat-idem";
        String sentAt = "2026-11-18T10:00:00+08:00";
        ImMessageEntry e = entry(inst, "wecom", chat, null, "u1", "张三", "你好", sentAt);
        String key = ImMessageService.dedupKey(null, chat, sentAt, "u1", "你好");

        assertThat(messageService.upsert(e, key)).as("第一次必须是新插入").isTrue();
        assertThat(messageService.upsert(e, key)).as("第二次必须被幂等挡掉").isFalse();

        assertThat(countRows(inst)).as("库里只能有一行").isEqualTo(1);
        List<ImMessageEntry> rows = messageService.queryByChat(inst, chat, null, null, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content()).isEqualTo("你好");
        assertThat(rows.get(0).id()).as("主键必须真的落库（Flex 的 insert 是否带 @Id 列）").isNotBlank();
    }

    /**
     * 企微真实场景：{@code messageId} 恒为 null、{@code send_time} 只到秒。
     *
     * <p>两次写入（相同内容、相同秒）必须只留一条 —— 这正是 dedup_key 存在的理由。
     */
    @Test
    @DisplayName("企微场景：无 messageId + 同秒同内容 ⇒ 两次写入只留一条")
    void wecomSameSecondSameContentCollapses() {
        String inst = "inst-wecom-" + System.nanoTime();
        String chat = "chat-wecom";
        String sentAt = "2026-11-18T11:22:33+08:00";
        ImMessageEntry a = entry(inst, "wecom", chat, null, "u1", "张三", "收到", sentAt);
        ImMessageEntry b = entry(inst, "wecom", chat, null, "u1", "张三", "收到", sentAt);

        // dedupKey 传 null ⇒ 走服务层的统一推导（调用方不该自己拼）
        assertThat(messageService.upsert(a, null)).isTrue();
        assertThat(messageService.upsert(b, null)).isFalse();
        assertThat(countRows(inst)).isEqualTo(1);
    }

    /**
     * 企微反例：{@code sent_at} 相同但<b>发送者不同</b> ⇒ 必须留两条。
     *
     * <p>这条是「dedup_key 真的把 sender 算进去了」的证据 —— 只按 (chat_id, sent_at, content)
     * 去重会把两个人同一秒说的同一句话吞成一条。
     */
    @Test
    @DisplayName("企微反例：同秒但 sender 不同 ⇒ 必须留两条")
    void wecomSameSecondDifferentSenderKeepsBoth() {
        String inst = "inst-wecom2-" + System.nanoTime();
        String chat = "chat-wecom2";
        String sentAt = "2026-11-18T11:22:33+08:00";
        ImMessageEntry a = entry(inst, "wecom", chat, null, "u1", "张三", "收到", sentAt);
        ImMessageEntry b = entry(inst, "wecom", chat, null, "u2", "李四", "收到", sentAt);

        assertThat(messageService.upsert(a, null)).isTrue();
        assertThat(messageService.upsert(b, null)).isTrue();
        assertThat(countRows(inst)).as("sender 不同 ⇒ 不能被当成同一条").isEqualTo(2);

        assertThat(messageService.queryBySender(inst, chat, "u1", null, 100)).hasSize(1);
        assertThat(messageService.queryBySender(inst, chat, "u2", null, 100)).hasSize(1);
    }

    @Test
    @DisplayName("批量写入：重复项被幂等挡掉，返回实际新增条数")
    void upsertBatchReturnsNewCount() {
        String inst = "inst-batch-" + System.nanoTime();
        String chat = "chat-batch";
        String t = "2026-11-18T12:00:00+08:00";
        ImMessageEntry a = entry(inst, "wecom", chat, null, "u1", "张三", "一", t);
        ImMessageEntry b = entry(inst, "wecom", chat, null, "u2", "李四", "二", t);
        ImMessageEntry c = entry(inst, "wecom", chat, null, "u3", "王五", "三", t);

        List<String> keys = List.of(
                ImMessageService.dedupKey(null, chat, t, "u1", "一"),
                ImMessageService.dedupKey(null, chat, t, "u2", "二"),
                ImMessageService.dedupKey(null, chat, t, "u3", "三"));

        assertThat(messageService.upsertBatch(List.of(a, b, c), keys)).isEqualTo(3);
        assertThat(messageService.upsertBatch(List.of(a, b, c), keys)).as("全部重复 ⇒ 新增 0").isZero();
        assertThat(countRows(inst)).isEqualTo(3);
    }

    /**
     * 幂等的<b>兜底</b>路径：DB 唯一索引真的拦重复，且只把「唯一冲突」当重复。
     *
     * <h2>WHY 要单独测这条</h2>
     * 正常路径是「先查后插」⇒ 重复时<b>永远走不到</b>插入冲突那条分支 ⇒
     * 「并发时两个线程同时插」这条唯一防线<b>从来没被执行过</b>。
     * 不验 = 并发下会抛未处理异常把整个扫描任务打断（或者更糟：把异常当成重复而静默吞掉别的错）。
     *
     * <p>配对证据：① 绕开服务层直接插两条同 key ⇒ 第二条必须撞唯一索引；
     * ② {@code NOT NULL} 冲突必须<b>不</b>被误判成「重复」（否则一条消息会被静默吞掉）。
     */
    @Test
    @DisplayName("兜底：唯一索引真的拦重复，且 NOT NULL 冲突不被误判成「重复」")
    void uniqueIndexReallyBlocksDuplicates() {
        String inst = "inst-uniq-" + System.nanoTime();
        String chat = "chat-uniq";
        String sentAt = "2026-11-18T14:00:00+08:00";
        String key = ImMessageService.dedupKey(null, chat, sentAt, "u1", "撞车");

        ImMessageRecord first = ImMessageRecord.fromDomain(
                entry(inst, "wecom", chat, null, "u1", "张三", "撞车", sentAt));
        first.setId("immsg-dup-1");
        first.setDedupKey(key);
        first.setDirection("in");
        first.setOrigin("official");
        first.setFetchedAt(sentAt);
        messageMapper.insert(first);

        ImMessageRecord second = ImMessageRecord.fromDomain(
                entry(inst, "wecom", chat, null, "u1", "张三", "撞车", sentAt));
        second.setId("immsg-dup-2");
        second.setDedupKey(key);
        second.setDirection("in");
        second.setOrigin("official");
        second.setFetchedAt(sentAt);

        Throwable ex = null;
        try {
            messageMapper.insert(second);
        } catch (RuntimeException e) {
            ex = e;
        }
        assertThat(ex).as("同 (instance_id, dedup_key) 必须被 DB 唯一索引挡下").isNotNull();
        assertThat(ImMessageService.isUniqueViolation(ex))
                .as("服务层的兜底判据必须能认出真实的 SQLite 唯一冲突").isTrue();

        Throwable notNullViolation = new RuntimeException(
                "Error inserting data. Cause: org.sqlite.SQLiteException: NOT NULL constraint failed: im_message.sent_at");
        assertThat(ImMessageService.isUniqueViolation(notNullViolation))
                .as("NOT NULL 冲突不是「重复」：误判会让一条消息被静默吞掉").isFalse();
    }

    // ═══════════════════════ 查询 ═══════════════════════

    @Test
    @DisplayName("按会话查：时间区间过滤生效 + 结果按时间倒序（最新在前）")
    void selectByChatFiltersAndOrders() {
        String inst = "inst-chat-" + System.nanoTime();
        String chat = "chat-time";
        messageService.upsert(entry(inst, "wecom", chat, null, "u1", "张三",
                "九点", "2026-11-18T09:00:00+08:00"), null);
        messageService.upsert(entry(inst, "wecom", chat, null, "u1", "张三",
                "十点", "2026-11-18T10:00:00+08:00"), null);
        messageService.upsert(entry(inst, "wecom", chat, null, "u1", "张三",
                "十一点", "2026-11-18T11:00:00+08:00"), null);

        List<ImMessageEntry> ranged = messageService.queryByChat(inst, chat,
                "2026-11-18T09:30:00+08:00", "2026-11-18T10:30:00+08:00", 100);
        assertThat(ranged).as("区间过滤后只应剩 10 点那条").hasSize(1);
        assertThat(ranged.get(0).content()).isEqualTo("十点");

        List<ImMessageEntry> recent = messageService.queryByChat(inst, chat, null, null, 2);
        assertThat(recent).as("limit=2 ⇒ 取最近两条").hasSize(2);
        assertThat(recent.get(0).sentAt()).as("倒序 ⇒ 最新在前").isEqualTo("2026-11-18T11:00:00+08:00");
        assertThat(recent.get(1).sentAt()).isEqualTo("2026-11-18T10:00:00+08:00");

        assertThat(messageService.maxSentAt(inst, chat)).isEqualTo("2026-11-18T11:00:00+08:00");
    }

    @Test
    @DisplayName("按发送者筛：只返回该发送者的消息")
    void selectBySenderFilters() {
        String inst = "inst-sender-" + System.nanoTime();
        String chat = "chat-sender";
        messageService.upsert(entry(inst, "feishu", chat, "om_1", "u1", "张三",
                "一", "2026-11-18T09:00:00+08:00"), null);
        messageService.upsert(entry(inst, "feishu", chat, "om_2", "u2", "李四",
                "二", "2026-11-18T09:00:10+08:00"), null);
        messageService.upsert(entry(inst, "feishu", chat, "om_3", "u1", "张三",
                "三", "2026-11-18T09:00:20+08:00"), null);

        List<ImMessageEntry> byU1 = messageService.queryBySender(inst, chat, "u1", null, 100);
        assertThat(byU1).hasSize(2);
        assertThat(byU1).allSatisfy(m -> assertThat(m.senderId()).isEqualTo("u1"));

        List<ImMessageEntry> since = messageService.queryBySender(inst, chat, "u1",
                "2026-11-18T09:00:15+08:00", 100);
        assertThat(since).as("since 过滤").hasSize(1);
        assertThat(since.get(0).content()).isEqualTo("三");
    }

    /**
     * {@code direction='out'} 必须能写能读 —— 这是「企微读不到 bot 自己发的消息」的补丁。
     *
     * <p>若这一列写不进去或读不出来，agent 看到的会话就<b>永远只有对方说的话</b>。
     */
    @Test
    @DisplayName("direction='out'：我们自己发出去的消息能写能读")
    void outboundDirectionRoundTrips() {
        String inst = "inst-out-" + System.nanoTime();
        String chat = "chat-out";
        ImMessageEntry e = new ImMessageEntry(null, inst, "wecom", chat, null,
                "bot", "机器人", "text", "我发的", null,
                "2026-11-18T13:00:00+08:00", "out", "local");

        assertThat(messageService.upsert(e, null)).isTrue();

        List<ImMessageEntry> rows = messageService.queryByChat(inst, chat, null, null, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).direction()).as("out 方向必须能读回来").isEqualTo("out");
        assertThat(rows.get(0).outbound()).isTrue();
        assertThat(rows.get(0).origin()).isEqualTo("local");
    }

    // ═══════════════════════ 扫描渠道配置 ═══════════════════════

    @Test
    @DisplayName("markScanResult 传 null 后 last_error 必须真的变成 NULL（UpdateChain 守卫）")
    void markScanResultClearsLastError() throws Exception {
        String inst = "inst-scan-" + System.nanoTime();
        scanService.enable(inst);

        scanService.markScanResult(inst, 3, 5, "拉取超时");
        assertThat(scanService.find(inst).orElseThrow().lastError()).isEqualTo("拉取超时");
        assertThat(rawLastError(inst)).as("失败原因必须落库").isEqualTo("拉取超时");

        // 再扫一次成功 ⇒ 失败原因必须被擦掉
        scanService.markScanResult(inst, 4, 6, null);

        ImScanChannelEntry after = scanService.find(inst).orElseThrow();
        assertThat(after.lastError()).as("last_error 必须真的置空（update(entity) 会忽略 null）").isNull();
        assertThat(after.lastChatCount()).isEqualTo(4);
        assertThat(after.lastNewCount()).isEqualTo(6);
        assertThat(after.lastScanAt()).as("成功才推进游标").isNotBlank();

        // 旁证：直接读库，确认是 SQL NULL 而不是空串
        assertThat(rawLastError(inst)).as("库里的 last_error 必须是 NULL").isNull();
    }

    @Test
    @DisplayName("扫描开关：enable/disable 幂等，listEnabled 只含启用的")
    void enableDisableAreIdempotent() {
        String inst = "inst-switch-" + System.nanoTime();
        scanService.enable(inst);
        scanService.enable(inst);
        assertThat(scanService.listEnabled()).extracting(ImScanChannelEntry::instanceId).contains(inst);

        scanService.disable(inst);
        assertThat(scanService.listEnabled()).extracting(ImScanChannelEntry::instanceId).doesNotContain(inst);
        assertThat(scanService.list()).extracting(ImScanChannelEntry::instanceId).contains(inst);
        assertThat(scanService.find(inst).orElseThrow().enabled()).isFalse();
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    private static ImMessageEntry entry(String instanceId, String platform, String chatId,
                                        String messageId, String senderId, String senderName,
                                        String content, String sentAt) {
        return new ImMessageEntry(null, instanceId, platform, chatId, messageId,
                senderId, senderName, "text", content, null, sentAt, "in", "official");
    }

    private static long countRows(String instanceId) {
        return messageMapper.selectCountByQuery(
                QueryWrapper.create().where("instance_id = ?", instanceId));
    }

    /**
     * 直接读库拿 {@code last_error} 的原始值（绕开 ORM 的 null/空串歧义）。
     *
     * <p>为什么要「旁证」：ORM 读回 null 有两种可能 —— 真的是 NULL，或者读错了列。
     * 直接 JDBC 读一次才能证明「列存在且值为 NULL」。
     */
    private static String rawLastError(String instanceId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_error FROM im_scan_channel WHERE instance_id = ?")) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("last_error");
            }
        }
    }
}

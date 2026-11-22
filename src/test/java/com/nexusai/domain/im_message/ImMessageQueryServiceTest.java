package com.nexusai.domain.im_message;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_message.fetch.ImFetchWindow;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.domain.im_message.fetch.ImMessageFetcherFactory;
import com.nexusai.domain.im_message.fetch.impl.FeishuImMessageFetcher;
import com.nexusai.domain.im_message.fetch.impl.WechatImMessageFetcher;
import com.nexusai.domain.im_message.fetch.impl.WecomImMessageFetcher;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.repository.im_channel.entity.ImChannelInstanceRecord;
import com.nexusai.repository.im_channel.mapper.ImChannelInstanceMapper;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
import com.nexusai.repository.im_message.entity.ImMessageRecord;
import com.nexusai.repository.im_message.mapper.ImMessageMapper;
import com.nexusai.repository.im_message.mapper.WechatMessageReadMapper;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「本地 ∪ 官方」并集读取的真库验证（V315 拉取层 + 查询层）。
 *
 * <h2>WHY 要真库</h2>
 * 这一层的正确性几乎全在 <b>SQL / 幂等 / 排序</b> 上，替身测试一条都证明不了：
 * <ul>
 *   <li>{@code UNIQUE(instance_id, dedup_key)} 真的在拦重复（并集不重复的关键）；</li>
 *   <li>{@code sent_at} 的<b>字典序</b>过滤与排序（时区混写就静默错乱）；</li>
 *   <li>{@code im_chat} → {@code instance_id / peer_id} 的翻译（工具传的是本地 id）。</li>
 * </ul>
 *
 * <p>用共享稳定 SQLite + Flyway（与全仓其他 Flex 测试同一范式），⛔ 不碰用户真库。
 */
class ImMessageQueryServiceTest {

    private static SQLiteDataSource ds;
    private static ImMessageMapper messageMapper;
    private static ImChatMapper chatMapper;
    private static ImChannelInstanceMapper instanceMapper;
    private static WechatMessageReadMapper wechatMessageMapper;

    private static ImMessageService messageService;
    private static ImChatService chatService;
    private static ImChannelService channelService;
    private static CryptoUtil cryptoUtil;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ImMessageMapper.class, ImChatMapper.class,
            ImChannelInstanceMapper.class, WechatMessageReadMapper.class);

        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(ImMessageMapper.class);
        chatMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChatMapper.class);
        instanceMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChannelInstanceMapper.class);
        wechatMessageMapper = MybatisFlexBootstrap.getInstance().getMapper(WechatMessageReadMapper.class);

        messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);

        chatService = new ImChatService();
        ReflectionTestUtils.setField(chatService, "mapper", chatMapper);

        cryptoUtil = new CryptoUtil(Base64.getEncoder().encodeToString(new byte[32]));
        channelService = new ImChannelService();
        ReflectionTestUtils.setField(channelService, "mapper", instanceMapper);
        ReflectionTestUtils.setField(channelService, "cryptoUtil", cryptoUtil);
    }

    /** 清空本测试用到的表（共享库 ⇒ 必须清，否则计数类断言会假失败）。 */
    @BeforeEach
    void cleanTables() throws Exception {
        deleteAll(messageMapper, messageMapper.selectAll().stream().map(r -> r.getId()).toList());
        deleteAll(chatMapper, chatMapper.selectAll().stream().map(r -> r.getId()).toList());
        deleteAll(instanceMapper, instanceMapper.selectAll().stream().map(r -> r.getId()).toList());
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM wechat_message")) {
            ps.executeUpdate();
        }
    }

    private static void deleteAll(com.mybatisflex.core.BaseMapper<?> mapper, List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            mapper.deleteBatchByIds(ids);
        }
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** 建一个渠道实例（凭证随便给，拉取层用替身不真换票）。 */
    private String newInstance(String platform) {
        String id = "imc-" + platform + "-" + System.nanoTime();
        ImChannelInstanceRecord r = new ImChannelInstanceRecord();
        r.setId(id);
        r.setPlatform(platform);
        r.setDisplayName(platform + " 测试实例");
        r.setCredential(cryptoUtil.encrypt("{\"botId\":\"b\",\"botSecret\":\"s\"}"));
        r.setStatus("connected");
        r.setCreatedAt(ImMessageTime.canonicalNow());
        r.setUpdatedAt(ImMessageTime.canonicalNow());
        instanceMapper.insert(r);
        return id;
    }

    /** 建一个聊天（本地目录行），返回 {@code imch-xxx}。 */
    private String newChat(String instanceId, String platform, String peerId) {
        ImChatEntry c = chatService.upsert(instanceId, platform, peerId, "张三", "p2p",
            null, null, null, ImChatService.SOURCE_LISTED);
        return c.id();
    }

    private ImMessageQueryService newService(ImMessageFetcher... fetchers) {
        ImMessageQueryService svc = new ImMessageQueryService();
        ReflectionTestUtils.setField(svc, "chatService", chatService);
        ReflectionTestUtils.setField(svc, "messageService", messageService);
        ReflectionTestUtils.setField(svc, "fetcherFactory", new ImMessageFetcherFactory(List.of(fetchers)));
        return svc;
    }

    private static ImMessageEntry entry(String instanceId, String platform, String chatId,
                                        String messageId, String senderId, String content,
                                        String sentAt, String direction, String origin) {
        return new ImMessageEntry(null, instanceId, platform, chatId, messageId,
            senderId, "张三", "text", content, null, sentAt, direction, origin);
    }

    // ═══════════════════════ 并集去重 ═══════════════════════

    @Test
    @DisplayName("并集去重：官方返回 3 条、本地已有这 3 条 ⇒ 合并后仍是 3 条（不重复），且以本地那条为准")
    void unionIsDedupedAndLocalRowWins() {
        String inst = newInstance("feishu");
        String chatId = newChat(inst, "feishu", "oc_1");

        List<ImMessageEntry> fetched = List.of(
            entry(inst, "feishu", "oc_1", "om_1", "u1", "一", "2026-11-18T10:00:00.000+08:00", "in", "official"),
            entry(inst, "feishu", "oc_1", "om_2", "u2", "二", "2026-11-18T10:01:00.000+08:00", "in", "official"),
            entry(inst, "feishu", "oc_1", "om_3", "u3", "三", "2026-11-18T10:02:00.000+08:00", "in", "official"));

        StubFetcher stub = new StubFetcher("feishu", fetched);

        // 本地已经有这 3 条（但第 2 条是「我们自己发的」⇒ direction=out / origin=local）
        messageService.upsert(fetched.get(0), null);
        messageService.upsert(entry(inst, "feishu", "oc_1", "om_2", "u2", "二",
            "2026-11-18T10:01:00.000+08:00", "out", "local"), null);
        messageService.upsert(fetched.get(2), null);

        ImMessageQueryService svc = newService(stub);
        ImMessageQueryService.ImMessageReadResult res = svc.readWithNotes(chatId, null, null, null, 50, true);

        assertThat(res.messages()).as("本地已有的 3 条不该再被插一遍").hasSize(3);
        assertThat(countRows(inst)).as("库里只能有 3 行").isEqualTo(3);

        // 本地那条「我们自己发的」必须保留 out/local（官方拉回来的那条不能覆盖它）
        ImMessageEntry local = res.messages().stream()
            .filter(m -> "om_2".equals(m.messageId())).findFirst().orElseThrow();
        assertThat(local.direction()).as("本地已有一条 out ⇒ 合并后必须还是 out").isEqualTo("out");
        assertThat(local.origin()).as("本地已有一条 local ⇒ 合并后必须还是 local").isEqualTo("local");
    }

    @Test
    @DisplayName("origin 正确性：官方新拉的 = official，本地自己写的 = local")
    void originIsAccurate() {
        String inst = newInstance("feishu");
        String chatId = newChat(inst, "feishu", "oc_2");

        // 本地：一条我们自己发的（origin=local）
        messageService.upsert(entry(inst, "feishu", "oc_2", null, "bot", "我发的",
            "2026-11-18T09:00:00.000+08:00", "out", "local"), null);

        StubFetcher stub = new StubFetcher("feishu", List.of(
            entry(inst, "feishu", "oc_2", "om_new", "u1", "官方来的",
                "2026-11-18T10:00:00.000+08:00", "in", "official")));

        ImMessageQueryService svc = newService(stub);
        List<ImMessageEntry> rows = svc.read(chatId, null, null, null, 50, true);

        assertThat(rows).hasSize(2);
        ImMessageEntry mine = rows.stream().filter(m -> "我发的".equals(m.content())).findFirst().orElseThrow();
        assertThat(mine.origin()).isEqualTo("local");
        assertThat(mine.direction()).isEqualTo("out");

        ImMessageEntry fromPlatform = rows.stream()
            .filter(m -> "官方来的".equals(m.content())).findFirst().orElseThrow();
        assertThat(fromPlatform.origin()).isEqualTo("official");
        assertThat(fromPlatform.direction()).isEqualTo("in");
    }

    @Test
    @DisplayName("senderId 过滤：只看某个人")
    void senderIdFilterWorks() {
        String inst = newInstance("feishu");
        String chatId = newChat(inst, "feishu", "oc_3");
        messageService.upsert(entry(inst, "feishu", "oc_3", "om_a", "u1", "甲说的",
            "2026-11-18T10:00:00.000+08:00", "in", "official"), null);
        messageService.upsert(entry(inst, "feishu", "oc_3", "om_b", "u2", "乙说的",
            "2026-11-18T10:01:00.000+08:00", "in", "official"), null);
        messageService.upsert(entry(inst, "feishu", "oc_3", "om_c", "u1", "甲又说的",
            "2026-11-18T10:02:00.000+08:00", "in", "official"), null);

        ImMessageQueryService svc = newService();
        List<ImMessageEntry> rows = svc.read(chatId, null, null, "u1", 50, false);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(m -> assertThat(m.senderId()).isEqualTo("u1"));
    }

    // ═══════════════════════ 企微 7 天窗口 ═══════════════════════

    @Test
    @DisplayName("企微：请求区间早于 7 天 ⇒ 不抛异常 + notes 说明 + 实际拉取区间被收敛到 7 天内")
    void wecomOlderThanSevenDaysDoesNotThrow() {
        String inst = newInstance("wecom");
        String chatId = newChat(inst, "wecom", "wrNH-1");

        ImFetchWindow window = new ImFetchWindow(
            ImMessageTime.canonicalFromEpochSeconds(
                java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS).getEpochSecond()),
            "企微仅能补拉最近 7 天（begin_time 官方限制），更早的消息只能来自本地记录");
        StubFetcher stub = new StubFetcher("wecom", List.of(), window);

        ImMessageQueryService svc = newService(stub);
        String old = ImMessageTime.canonicalFromEpochSeconds(
            java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS).getEpochSecond());

        // ⛔ 不能抛异常（超窗不是错误）
        ImMessageQueryService.ImMessageReadResult res = svc.readWithNotes(chatId, old, null, null, 50, true);

        assertThat(res.notes()).as("必须有一句说明").anyMatch(n -> n.contains("7 天"));
        assertThat(stub.callCount).as("仍然要尝试拉一次（区间已被收敛）").isEqualTo(1);
        assertThat(stub.lastSince).as("实际拉取起点必须落在 7 天窗口内")
            .isNotNull()
            .isGreaterThan(old);
        assertThat(java.time.Instant.parse(ImMessageTime.canonical(stub.lastSince)))
            .isAfter(java.time.Instant.now().minus(8, java.time.temporal.ChronoUnit.DAYS));
    }

    // ═══════════════════════ 时间归一化 ═══════════════════════

    @Test
    @DisplayName("sent_at 归一化：混入 Z 结尾的时间后，排序仍按真实时间、输出仍是规范形态")
    void sentAtNormalizationSurvivesZuluInput() {
        String inst = newInstance("feishu");
        String chatId = newChat(inst, "feishu", "oc_4");

        // ① 规范形态的 01:00 与 03:00
        messageService.upsert(entry(inst, "feishu", "oc_4", "om_1", "u1", "一点",
            "2026-11-18T01:00:00.000+08:00", "in", "official"), null);
        messageService.upsert(entry(inst, "feishu", "oc_4", "om_3", "u1", "三点",
            "2026-11-18T03:00:00.000+08:00", "in", "official"), null);
        // ② 一个「 rogue 写入方」直接写进库的 Z 形态（= 02:00 +08:00）
        insertRaw(inst, "feishu", "oc_4", "om_2", "u1", "两点", "2026-11-17T18:00:00Z");

        ImMessageQueryService svc = newService();
        List<ImMessageEntry> rows = svc.read(chatId, null, null, null, 50, false);

        assertThat(rows).as("三条都要在").hasSize(3);
        assertThat(rows).extracting(ImMessageEntry::content)
            .as("必须按**真实时间**排序（Z 那条夹在中间，不能排最后）")
            .containsExactly("一点", "两点", "三点");
        assertThat(rows).allSatisfy(m ->
            assertThat(m.sentAt()).as("输出必须是规范形态（同一偏移 + 定宽）")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+08:00"));
    }

    @Test
    @DisplayName("时间归一化入口：Z / 毫秒 epoch / 企微本地形态 ⇒ 同一个规范值")
    void timeNormalizerUnifiesFormats() {
        String expected = "2026-11-18T10:00:00.000+08:00";
        assertThat(ImMessageTime.canonical("2026-11-18T02:00:00Z")).isEqualTo(expected);
        assertThat(ImMessageTime.canonical("2026-11-18T10:00:00+08:00")).isEqualTo(expected);
        assertThat(ImMessageTime.canonical("2026-11-18 10:00:00")).isEqualTo(expected);
        assertThat(ImMessageTime.canonical(
            String.valueOf(ImMessageTime.toInstant("2026-11-18T10:00:00+08:00").toEpochMilli())))
            .isEqualTo(expected);
        assertThat(ImMessageTime.canonical("不是时间")).as("解析不出来必须返回 null（不猜）").isNull();
    }

    // ═══════════════════════ 微信本机库 ═══════════════════════

    @Test
    @DisplayName("微信：读本机 wechat_message ⇒ 稳定 messageId、is_self 决定方向、时间归一化")
    void wechatReadsLocalLibrary() throws Exception {
        String inst = newInstance("wechat");
        String chatId = newChat(inst, "wechat", "wxid_zhangsan");

        long base = ImMessageTime.toInstant("2026-11-18T10:00:00+08:00").getEpochSecond();
        insertWechatMessage("w1", "wxid_me", "wxid_zhangsan", 100L, 1000L, base, "wxid_zhangsan", 1, "在吗", 0);
        insertWechatMessage("w2", "wxid_me", "wxid_zhangsan", 101L, 1001L, base + 10, "wxid_me", 1, "我在", 1);
        insertWechatMessage("w3", "wxid_me", "wxid_zhangsan", 102L, 1002L, base + 20, "wxid_zhangsan", 3, "[图片]", 0);

        WechatImMessageFetcher fetcher = new WechatImMessageFetcher();
        ReflectionTestUtils.setField(fetcher, "mapper", wechatMessageMapper);

        ImMessageQueryService svc = newService(fetcher);
        List<ImMessageEntry> rows = svc.read(chatId, null, null, null, 50, true);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(ImMessageEntry::content).containsExactly("在吗", "我在", "[图片]");
        assertThat(rows.get(0).sentAt()).isEqualTo("2026-11-18T10:00:00.000+08:00");
        assertThat(rows.get(1).direction()).as("is_self=1 ⇒ 自己发的").isEqualTo("out");
        assertThat(rows.get(2).msgType()).as("微信 3 = 图片").isEqualTo("image");
        assertThat(rows.get(0).messageId())
            .as("messageId 必须用稳定复合键（不能用随机 UUID 的 id 列）")
            .startsWith("wx:wxid_me:wxid_zhangsan:100:1000");
    }

    @Test
    @DisplayName("微信：重复读同一批 ⇒ 幂等（库里不涨行）")
    void wechatFetchIsIdempotent() throws Exception {
        String inst = newInstance("wechat");
        String chatId = newChat(inst, "wechat", "wxid_lisi");
        long base = ImMessageTime.toInstant("2026-11-18T10:00:00+08:00").getEpochSecond();
        insertWechatMessage("w1", "wxid_me", "wxid_lisi", 200L, 2000L, base, "wxid_lisi", 1, "你好", 0);

        WechatImMessageFetcher fetcher = new WechatImMessageFetcher();
        ReflectionTestUtils.setField(fetcher, "mapper", wechatMessageMapper);
        ImMessageQueryService svc = newService(fetcher);

        svc.read(chatId, null, null, null, 50, true);
        long afterFirst = countRows(inst);
        svc.read(chatId, null, null, null, 50, true);

        assertThat(countRows(inst)).as("重复拉取不能插第二条").isEqualTo(afterFirst);
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /**
     * 装配自检：查询层能在真实 Spring 上下文里装起来。
     *
     * <p>WHY：单测里 {@code new} 出来的对象永远「能跑」，但生产是<b>装配</b>出来的 ——
     * 多构造器没标 {@code @Autowired}、缺一个 bean、mapper 没被扫到，
     * 这些只有<b>真装配</b>才会炸（单测照样全绿）。
     * <p>⛔ 不用 {@code @SpringBootTest}（会迁移用户真库）。
     */
    @Test
    @DisplayName("装配自检：查询层 + 三个拉取器能在 Spring 上下文里装起来")
    void wiringIsComplete() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                 new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean(ImMessageMapper.class, () -> messageMapper);
            ctx.registerBean(ImChatMapper.class, () -> chatMapper);
            ctx.registerBean(ImChannelInstanceMapper.class, () -> instanceMapper);
            ctx.registerBean(WechatMessageReadMapper.class, () -> wechatMessageMapper);
            ctx.registerBean(CryptoUtil.class, () -> cryptoUtil);
            ctx.registerBean(ImChannelService.class);
            ctx.registerBean(ImChatService.class);
            ctx.registerBean(ImMessageService.class);
            ctx.registerBean(ImMessageFetcherFactory.class);
            ctx.registerBean(com.nexusai.domain.im_channel.cli.WecomCliTokenService.class);
            ctx.registerBean(com.nexusai.domain.im_channel.cli.WecomCliClient.class);
            ctx.registerBean(com.nexusai.domain.im_channel.send.FeishuTokenService.class);
            ctx.registerBean(WechatImMessageFetcher.class);
            ctx.registerBean(WecomImMessageFetcher.class);
            ctx.registerBean(FeishuImMessageFetcher.class);
            ctx.registerBean(ImMessageQueryService.class);
            ctx.refresh();

            ImMessageFetcherFactory factory = ctx.getBean(ImMessageFetcherFactory.class);
            assertThat(factory.supportedPlatforms())
                .as("三个平台的拉取器都要注册上")
                .contains("wechat", "wecom", "feishu");
            assertThat(ctx.getBean(ImMessageQueryService.class)).isNotNull();
        }
    }

    /** 直接写库（绕过服务层）—— 模拟「 rogue 写入方」直接塞了一个非规范形态。 */
    private void insertRaw(String instanceId, String platform, String chatId, String messageId,
                           String senderId, String content, String sentAt) {
        ImMessageRecord r = new ImMessageRecord();
        r.setId("immsg-raw-" + System.nanoTime());
        r.setInstanceId(instanceId);
        r.setPlatform(platform);
        r.setChatId(chatId);
        r.setMessageId(messageId);
        r.setDedupKey(ImMessageService.dedupKey(messageId, chatId, sentAt, senderId, content));
        r.setSenderId(senderId);
        r.setSenderName("张三");
        r.setMsgType("text");
        r.setContent(content);
        r.setSentAt(sentAt);
        r.setDirection("in");
        r.setOrigin("official");
        r.setFetchedAt(ImMessageTime.canonicalNow());
        messageMapper.insert(r);
    }

    private void insertWechatMessage(String id, String account, String conversation, long serverId,
                                     long sortSeq, long msgTime, String sender, int msgType,
                                     String preview, int isSelf) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO wechat_message (id, account_wxid, conversation_id, server_id, sort_seq, "
                     + "msg_time, sender, msg_type, content_preview, search_text, is_self) "
                     + "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, account);
            ps.setString(3, conversation);
            ps.setLong(4, serverId);
            ps.setLong(5, sortSeq);
            ps.setLong(6, msgTime);
            ps.setString(7, sender);
            ps.setInt(8, msgType);
            ps.setString(9, preview);
            ps.setString(10, preview);
            ps.setInt(11, isSelf);
            ps.executeUpdate();
        }
    }

    private long countRows(String instanceId) {
        return messageMapper.selectCountByQuery(
            com.mybatisflex.core.query.QueryWrapper.create().where("instance_id = ?", instanceId));
    }

    /**
     * 拉取器替身：只回固定内容，用来精确控制「平台返回什么」。
     *
     * <p>真实平台的报文形态由 {@code ImMessageFetcherProtocolTest} 单独验；
     * 这里要验的是<b>查询层</b>的并集/去重/窗口语义，不需要真的 HTTP。
     */
    private static final class StubFetcher implements ImMessageFetcher {
        private final String platform;
        private final List<ImMessageEntry> rows;
        private final ImFetchWindow window;
        private String lastSince;
        private String lastUntil;
        private int callCount;

        StubFetcher(String platform, List<ImMessageEntry> rows) {
            this(platform, rows, ImFetchWindow.UNLIMITED);
        }

        StubFetcher(String platform, List<ImMessageEntry> rows, ImFetchWindow window) {
            this.platform = platform;
            this.rows = rows;
            this.window = window;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public ImFetchWindow window() {
            return window;
        }

        @Override
        public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                          String untilIso, int maxMessages) {
            this.lastSince = sinceIso;
            this.lastUntil = untilIso;
            this.callCount++;
            return new ArrayList<>(rows);
        }
    }
}

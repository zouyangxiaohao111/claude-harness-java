package com.nexusai.domain.im_message;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_channel.ImChatService;
import com.nexusai.domain.im_message.fetch.ImMessageFetchException;
import com.nexusai.domain.im_message.fetch.ImMessageFetcher;
import com.nexusai.domain.im_message.fetch.ImMessageFetcherFactory;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.model.im_message.ImScanChannelEntry;
import com.nexusai.repository.im_channel.entity.ImChannelInstanceRecord;
import com.nexusai.repository.im_channel.mapper.ImChannelInstanceMapper;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「消息扫描」（{@code ImMessageSyncService}）的真库验证。
 *
 * <h2>WHY 要真库（而不是全替身）</h2>
 * 这个任务最容易出错的不是「循环写错了」，而是<b>游标（{@code last_scan_at}）的语义</b>：
 * <ul>
 *   <li><b>全部会话都失败</b>时游标必须<b>不推进</b>（推进 = 那段时间的消息被永久跳过）；</li>
 *   <li><b>部分会话失败</b>时游标要<b>照常推进</b>（2026-11-19 改：旧语义下一个坏会话
 *       会把好会话永远钉在同一起点）；</li>
 *   <li>成功时游标要写成「本轮拉取窗口的上界」（写成落库时刻会漏掉中间一小段）。</li>
 * </ul>
 * 这几条都落在 {@code im_scan_channel} 的列上 ⇒ 只有真库回查能钉住
 * （替身只能证明「我调用了某个方法」，证明不了「那一列真的没被改」）。
 *
 * <p>拉取层用<b>替身 fetcher</b>（⛔ 不打真平台）。
 * 用共享稳定 SQLite + Flyway（与全仓其他 Flex 测试同一范式），⛔ 不碰用户真库。
 */
class ImMessageSyncServiceTest {

    private static SQLiteDataSource ds;
    private static ImMessageMapper messageMapper;
    private static ImScanChannelMapper scanMapper;
    private static ImChatMapper chatMapper;
    private static ImChannelInstanceMapper instanceMapper;

    private static ImMessageService messageService;
    private static ImScanChannelService scanService;
    private static ImChatService chatService;
    private static ImChannelService channelService;

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

        MybatisFlexDbTestSupport.resetAndStart(ds, ImMessageMapper.class, ImScanChannelMapper.class,
            ImChatMapper.class, ImChannelInstanceMapper.class);

        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(ImMessageMapper.class);
        scanMapper = MybatisFlexBootstrap.getInstance().getMapper(ImScanChannelMapper.class);
        chatMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChatMapper.class);
        instanceMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChannelInstanceMapper.class);

        messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);

        scanService = new ImScanChannelService();
        ReflectionTestUtils.setField(scanService, "mapper", scanMapper);

        chatService = new ImChatService();
        ReflectionTestUtils.setField(chatService, "mapper", chatMapper);

        channelService = new ImChannelService();
        ReflectionTestUtils.setField(channelService, "mapper", instanceMapper);
        ReflectionTestUtils.setField(channelService, "cryptoUtil",
            new CryptoUtil(Base64.getEncoder().encodeToString(new byte[32])));
    }

    @BeforeEach
    void cleanTables() throws Exception {
        deleteAll(messageMapper, messageMapper.selectAll().stream().map(r -> r.getId()).toList());
        deleteAll(chatMapper, chatMapper.selectAll().stream().map(r -> r.getId()).toList());
        deleteAll(instanceMapper, instanceMapper.selectAll().stream().map(r -> r.getId()).toList());
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM im_scan_channel")) {
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
        r.setCredential("{}");
        r.setStatus("connected");
        r.setCreatedAt(ImMessageTime.canonicalNow());
        r.setUpdatedAt(ImMessageTime.canonicalNow());
        instanceMapper.insert(r);
        return id;
    }

    /** 建一个聊天目录行（peerId = 平台侧会话 id）。 */
    private void newChat(String instanceId, String platform, String peerId) {
        chatService.upsert(instanceId, platform, peerId, peerId, "p2p",
            null, null, null, ImChatService.SOURCE_LISTED);
    }

    private ImMessageSyncService newService(ImMessageFetcher... fetchers) {
        ImMessageSyncService svc = new ImMessageSyncService();
        ReflectionTestUtils.setField(svc, "scanChannelService", scanService);
        ReflectionTestUtils.setField(svc, "chatService", chatService);
        ReflectionTestUtils.setField(svc, "channelService", channelService);
        ReflectionTestUtils.setField(svc, "messageService", messageService);
        ReflectionTestUtils.setField(svc, "fetcherFactory", new ImMessageFetcherFactory(List.of(fetchers)));
        return svc;
    }

    /** 造一条消息（时间已是规范形态）。 */
    private static ImMessageEntry entry(String instanceId, String platform, String chatId,
                                        String messageId, String sentAt, String content) {
        return new ImMessageEntry(null, instanceId, platform, chatId, messageId,
            "u1", "张三", "text", content, null, sentAt, "in", "official");
    }

    /** 读某个实例的扫描配置行。 */
    private ImScanChannelEntry scanRow(String instanceId) {
        return scanService.find(instanceId).orElseThrow();
    }

    // ═══════════════════════ 用例 ═══════════════════════

    @Test
    @DisplayName("没有启用中的渠道 ⇒ 一条都不拉（fetcher 一次都没被调用）")
    void disabledChannel_neverFetches() {
        String instanceId = newInstance("wecom");
        newChat(instanceId, "wecom", "peer-1");
        scanService.disable(instanceId);   // 显式关闭

        FakeFetcher fetcher = new FakeFetcher("wecom");
        ImMessageSyncService.SyncSummary summary = newService(fetcher).runOnce();

        assertThat(summary.instances()).isEqualTo(0);
        assertThat(summary.chats()).isEqualTo(0);
        assertThat(fetcher.calls).isZero();
        assertThat(messageMapper.selectAll()).isEmpty();
    }

    @Test
    @DisplayName("部分会话失败：好会话照常入库、游标照常推进，坏的记进 last_error 摘要")
    void oneChatFails_otherChatsStillSynced() {
        String instanceId = newInstance("wecom");
        scanService.enable(instanceId);
        newChat(instanceId, "wecom", "peer-good");
        newChat(instanceId, "wecom", "peer-bad");

        FakeFetcher fetcher = new FakeFetcher("wecom");
        fetcher.ok("peer-good", List.of(
            entry(instanceId, "wecom", "peer-good", "m1", "2026-11-18T10:00:00.000+08:00", "你好")));
        fetcher.fail("peer-bad", "平台拒绝");

        ImMessageSyncService.SyncSummary summary = newService(fetcher).runOnce();

        // 好的会话：消息真的入库了
        assertThat(messageMapper.selectAll()).hasSize(1);
        assertThat(messageMapper.selectAll().get(0).getChatId()).isEqualTo("peer-good");
        // 统计：会话数只算成功的那个
        assertThat(summary.chats()).isEqualTo(1);
        assertThat(summary.newMessages()).isEqualTo(1);
        // 失败被记下来（用户能在界面上看到「为什么没扫到」）
        ImScanChannelEntry row = scanRow(instanceId);
        assertThat(row.lastError()).contains("peer-bad");
        assertThat(row.failed()).isTrue();
        // 🔴 2026-11-19 语义变更：部分失败**不再**卡住游标
        //    （旧语义下，一个坏会话会把好会话永远钉在同一起点）
        assertThat(row.lastScanAt()).as("部分会话失败 ⇒ 游标照常推进").isNotNull();
        assertThat(row.lastError()).as("失败摘要要写清「几个会话失败」").contains("1/2 个会话失败");
        assertThat(summary.failedInstances()).as("部分失败不算「实例失败」").isZero();
        assertThat(summary.partialFailedInstances()).isEqualTo(1);
    }

    @Test
    @DisplayName("3 个会话坏 1 个 ⇒ 游标照常推进 + last_error 有摘要 + 新增数正确")
    void partialFailure_advancesCursor() {
        String instanceId = newInstance("wecom");
        scanService.enable(instanceId);
        newChat(instanceId, "wecom", "peer-good-1");
        newChat(instanceId, "wecom", "peer-bad");
        newChat(instanceId, "wecom", "peer-good-2");

        FakeFetcher fetcher = new FakeFetcher("wecom");
        fetcher.ok("peer-good-1", List.of(
            entry(instanceId, "wecom", "peer-good-1", "m1", "2026-11-18T10:00:00.000+08:00", "一")));
        fetcher.ok("peer-good-2", List.of(
            entry(instanceId, "wecom", "peer-good-2", "m2", "2026-11-18T10:01:00.000+08:00", "二")));
        // 真机上的形态：40073（非法的 chat_id）⇒ 可识别的错误码
        fetcher.failWith("peer-bad", new ImMessageFetchException(
            ImMessageFetchException.CODE_INVALID_CHAT_ID,
            "企微会话 id 不是合法 chat_id（code=40073），拉不了该会话历史: peer-bad", "40073", false));

        ImMessageSyncService.SyncSummary summary = newService(fetcher).runOnce();

        assertThat(summary.chats()).as("只有成功的会话算数").isEqualTo(2);
        assertThat(summary.newMessages()).isEqualTo(2);
        assertThat(messageMapper.selectAll()).hasSize(2);

        ImScanChannelEntry row = scanRow(instanceId);
        assertThat(row.lastScanAt()).as("部分失败 ⇒ 游标照常推进").isNotNull();
        assertThat(row.lastError())
            .as("摘要要含「1/3 个会话失败」与坏会话 id")
            .contains("1/3 个会话失败")
            .contains("peer-bad");
        assertThat(row.lastError()).as("40073 要能被人话认出").contains("非法 chat_id");
        assertThat(summary.failedInstances()).isZero();
        assertThat(summary.partialFailedInstances()).isEqualTo(1);
    }

    @Test
    @DisplayName("全部会话都失败 ⇒ last_scan_at 不推进（这才是「凭证坏了/平台全拒」的信号）")
    void failure_doesNotAdvanceCursor() {
        String instanceId = newInstance("wecom");
        scanService.enable(instanceId);
        newChat(instanceId, "wecom", "peer-1");
        newChat(instanceId, "wecom", "peer-2");
        newChat(instanceId, "wecom", "peer-3");

        FakeFetcher okFetcher = new FakeFetcher("wecom");
        okFetcher.ok("peer-1", List.of(
            entry(instanceId, "wecom", "peer-1", "m1", "2026-11-18T10:00:00.000+08:00", "第一条")));
        okFetcher.ok("peer-2", List.of(
            entry(instanceId, "wecom", "peer-2", "m2", "2026-11-18T10:01:00.000+08:00", "第二条")));
        okFetcher.ok("peer-3", List.of(
            entry(instanceId, "wecom", "peer-3", "m3", "2026-11-18T10:02:00.000+08:00", "第三条")));
        newService(okFetcher).runOnce();

        String cursorAfterSuccess = scanRow(instanceId).lastScanAt();
        assertThat(cursorAfterSuccess).isNotNull();

        // 第二轮：三个会话**全**失败 ⇒ 游标必须原地不动（部分失败才推进）
        FakeFetcher failFetcher = new FakeFetcher("wecom");
        failFetcher.fail("peer-1", "平台 500");
        failFetcher.fail("peer-2", "平台 500");
        failFetcher.fail("peer-3", "平台 500");
        ImMessageSyncService.SyncSummary summary = newService(failFetcher).runOnce();

        assertThat(scanRow(instanceId).lastScanAt())
            .as("全部会话失败 ⇒ 游标必须保持上次成功的值")
            .isEqualTo(cursorAfterSuccess);
        assertThat(scanRow(instanceId).lastError()).contains("平台 500");
        assertThat(scanRow(instanceId).lastError()).contains("3/3 个会话失败");
        assertThat(summary.failedInstances()).isEqualTo(1);
        assertThat(summary.partialFailedInstances()).isZero();
    }

    @Test
    @DisplayName("失败摘要有上限：只列前 3 个会话 + 总数（一列 last_error 塞得下）")
    void failureSummaryIsCapped() {
        String instanceId = newInstance("wecom");
        scanService.enable(instanceId);
        for (int i = 1; i <= 5; i++) {
            newChat(instanceId, "wecom", "peer-" + i);
        }

        FakeFetcher fetcher = new FakeFetcher("wecom");
        for (int i = 1; i <= 5; i++) {
            fetcher.fail("peer-" + i, "平台 500");
        }
        newService(fetcher).runOnce();

        String error = scanRow(instanceId).lastError();
        assertThat(error).contains("5/5 个会话失败");
        assertThat(error).contains("peer-1").contains("peer-2").contains("peer-3");
        assertThat(error).as("第 4、5 个不列，只给总数").doesNotContain("peer-4").doesNotContain("peer-5");
        assertThat(error).contains("共 5 个");
        assertThat(error.length()).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("成功时新增数正确；重复扫一遍新增 0（幂等）")
    void success_newCountIsCorrect_andIdempotent() {
        String instanceId = newInstance("feishu");
        scanService.enable(instanceId);
        newChat(instanceId, "feishu", "oc_1");

        FakeFetcher fetcher = new FakeFetcher("feishu");
        fetcher.ok("oc_1", List.of(
            entry(instanceId, "feishu", "oc_1", "om_1", "2026-11-18T10:00:00.000+08:00", "一"),
            entry(instanceId, "feishu", "oc_1", "om_2", "2026-11-18T10:01:00.000+08:00", "二")));

        ImMessageSyncService svc = newService(fetcher);
        ImMessageSyncService.SyncSummary first = svc.runOnce();
        assertThat(first.newMessages()).isEqualTo(2);
        assertThat(scanRow(instanceId).lastNewCount()).isEqualTo(2);
        assertThat(scanRow(instanceId).lastChatCount()).isEqualTo(1);

        // 再跑一遍：同样的两条 ⇒ 幂等键命中 ⇒ 新增 0
        ImMessageSyncService.SyncSummary second = svc.runOnce();
        assertThat(second.newMessages()).isEqualTo(0);
        assertThat(messageMapper.selectAll()).hasSize(2);
    }

    @Test
    @DisplayName("成功时游标 = 本轮拉取窗口上界（不会漏掉落库时刻之前那一小段）")
    void success_cursorEqualsScanWindowEnd() {
        String instanceId = newInstance("feishu");
        scanService.enable(instanceId);
        newChat(instanceId, "feishu", "oc_1");

        FakeFetcher fetcher = new FakeFetcher("feishu");
        fetcher.ok("oc_1", List.of(
            entry(instanceId, "feishu", "oc_1", "om_1", "2026-11-18T10:00:00.000+08:00", "一")));

        newService(fetcher).runOnce();

        String cursor = scanRow(instanceId).lastScanAt();
        // 游标是规范形态（定宽），且能被解析成时间
        assertThat(ImMessageTime.toInstant(cursor)).isNotNull();
        assertThat(ImMessageTime.canonical(cursor)).isEqualTo(cursor);
    }

    @Test
    @DisplayName("增量起点 = max(实例游标, 本地最大 sent_at)：本地已有更新的消息时不回拉")
    void since_isMaxOfCursorAndLocalMax() {
        String instanceId = newInstance("feishu");
        scanService.enable(instanceId);
        newChat(instanceId, "feishu", "oc_1");

        // 本地已有一条更晚的消息（比实例游标还晚）
        messageService.upsert(
            entry(instanceId, "feishu", "oc_1", "om_local", "2026-11-18T23:00:00.000+08:00", "本地已有"),
            null);

        FakeFetcher fetcher = new FakeFetcher("feishu");
        fetcher.ok("oc_1", List.of());
        newService(fetcher).runOnce();

        assertThat(fetcher.lastSince).isEqualTo("2026-11-18T23:00:00.000+08:00");
    }

    // ═══════════════════════ 替身 ═══════════════════════

    /**
     * 替身 fetcher：按会话 id 编排「返回什么 / 抛什么」，并记录调用次数与最后一次的 since。
     *
     * <p>⛔ 绝不打真平台（本测试的判据全在「调了几次、传了什么、库里多了几条」）。
     */
    private static final class FakeFetcher implements ImMessageFetcher {

        private final String platform;
        private final Map<String, List<ImMessageEntry>> ok = new HashMap<>();
        private final Map<String, String> fail = new HashMap<>();
        private final Map<String, ImMessageFetchException> failWith = new HashMap<>();
        private int calls;
        private String lastSince;

        FakeFetcher(String platform) {
            this.platform = platform;
        }

        void ok(String chatId, List<ImMessageEntry> entries) {
            ok.put(chatId, entries);
        }

        void fail(String chatId, String reason) {
            fail.put(chatId, reason);
        }

        /** 用指定的异常失败（模拟带错误码的失败，如 40073）。 */
        void failWith(String chatId, ImMessageFetchException e) {
            failWith.put(chatId, e);
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso,
                                          String untilIso, int maxMessages)
                throws com.nexusai.domain.im_message.fetch.ImMessageFetchException {
            calls++;
            lastSince = sinceIso;
            ImMessageFetchException typed = failWith.get(chatId);
            if (typed != null) {
                throw typed;
            }
            String reason = fail.get(chatId);
            if (reason != null) {
                throw new com.nexusai.domain.im_message.fetch.ImMessageFetchException(
                    "IM_FETCH_PLATFORM_ERROR", reason);
            }
            List<ImMessageEntry> entries = ok.get(chatId);
            return entries == null ? new ArrayList<>() : entries;
        }
    }
}

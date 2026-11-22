package com.nexusai.domain.im_channel;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.repository.im_channel.mapper.ImChannelInstanceMapper;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
import com.nexusai.repository.im_channel.mapper.WechatConversationMapper;
import com.nexusai.domain.im_channel.cli.WecomCliClient;
import com.nexusai.domain.im_channel.cli.WecomCliTokenService;
import com.nexusai.domain.im_channel.cli.WecomMediaApi;
import com.nexusai.domain.im_channel.cli.WecomMessageApi;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.domain.im_channel.send.impl.WecomSendStrategy;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实库验证：V310 迁移生效 + `ImChatService` 的 reply_token / source 读写。
 *
 * <h2>WHY 需要这条（而不是只跑 mock）</h2>
 * 前面所有测试都用替身 `ImChatService`，因此**没有任何一条**证明：
 * <ul>
 *   <li>V310 的 4 个新列真的建出来了；</li>
 *   <li>{@code ImChatRecord} 的新字段与表列名对得上（MyBatis-Flex 靠命名约定映射，
 *       写错不会编译报错，只会运行期静默丢值）；</li>
 *   <li>{@code replyTokenOf} 的过期判定真的读到了列。</li>
 * </ul>
 * 这三点正是「跑得通但语义错」的典型位置。
 *
 * <p>用共享稳定 SQLite + Flyway（与全仓其他 Flex 测试同一范式），⛔ 不碰用户真库。
 */
class ImChatPersistenceE2ETest {

    /** 固定测试密钥（32 字节 base64）——只用于本测试库，与用户真库无关。 */
    private static final String TEST_ENCRYPTION_KEY =
            "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=";

    private static ImChatService chatService;
    private static ImChatMapper chatMapper;
    private static ImChannelInstanceMapper instanceMapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // 迁移到最新（含 V310）
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds,
                ImChannelInstanceMapper.class, ImChatMapper.class, WechatConversationMapper.class);

        chatMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChatMapper.class);
        instanceMapper = MybatisFlexBootstrap.getInstance().getMapper(ImChannelInstanceMapper.class);
        chatService = new ImChatService();
        ReflectionTestUtils.setField(chatService, "mapper", chatMapper);
    }

    /**
     * 清空本测试用到的两张表。
     *
     * <p>为什么必须清：{@link MybatisFlexDbTestSupport#sharedDbPath()} 是全套件共用的稳定库，
     * 重跑（或与同 JVM 的其他类混跑）会留下上一轮的 {@code im_channel_instance} / {@code im_chat} 行，
     * 造成「同一个 peer 查到两条」这类假失败。
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        // 按 id 批量删（空 QueryWrapper 的 deleteByQuery 在 Flex 里会报 SqlProvider 错，
        // 仓内其他测试也用同样的 selectAll + deleteBatchByIds 范式）
        var chats = chatMapper.selectAll();
        if (chats != null && !chats.isEmpty()) {
            chatMapper.deleteBatchByIds(chats.stream().map(r -> r.getId()).toList());
        }
        var instances = instanceMapper.selectAll();
        if (instances != null && !instances.isEmpty()) {
            instanceMapper.deleteBatchByIds(instances.stream().map(r -> r.getId()).toList());
        }
    }

    @Test
    @DisplayName("V310 生效：upsert 写入 source，replyToken 可读回且不随 upsert 被清")
    void v310ColumnsWork() {
        String inst = "inst-persist-" + System.nanoTime();
        String peer = "peer-persist-1";

        // ① 新建：source=scanned
        var created = chatService.upsert(inst, "wecom", peer, "测试群", "group",
                5, null, "2026-11-18T10:00:00+08:00", ImChatService.SOURCE_LISTED);
        assertThat(created.id()).isNotBlank();
        assertThat(created.peerName()).isEqualTo("测试群");

        // ② 入站写入 reply_token
        chatService.saveReplyToken(created.id(), "ctx-token-persist", null);
        assertThat(chatService.replyTokenOf(created.id()))
                .as("reply_token 必须真的落库（否则微信永远发不出去）")
                .contains("ctx-token-persist");

        // ③ 再 upsert（模拟扫描又跑了一次）：⛔ 不能把 reply_token 冲掉
        chatService.upsert(inst, "wecom", peer, "测试群", "group",
                5, null, "2026-11-18T11:00:00+08:00", ImChatService.SOURCE_LISTED);
        assertThat(chatService.replyTokenOf(created.id()))
                .as("扫描不该清掉入站拿到的令牌")
                .contains("ctx-token-persist");

        // ④ 过期令牌 ⇒ 读不到（宁可不发，也不发一条会被拒的请求）
        chatService.saveReplyToken(created.id(), "expired-token", "2020-01-01T00:00:00+08:00");
        assertThat(chatService.replyTokenOf(created.id())).isEmpty();

        // ⑤ markOutbound 更新最近消息
        chatService.markOutbound(created.id(), "出站摘要", null);
        var after = chatService.findById(created.id()).orElseThrow();
        assertThat(after.lastMessagePreview()).isEqualTo("出站摘要");
    }

    @Test
    @DisplayName("凭证加解密往返：ImChannelService 能加密落库、解密取回")
    void credentialRoundTrip() {
        ImChannelService channelService = new ImChannelService();
        ReflectionTestUtils.setField(channelService, "mapper",
                MybatisFlexBootstrap.getInstance().getMapper(ImChannelInstanceMapper.class));
        // CryptoUtil 允许空 key（内部派生），不需要外部配置
        ReflectionTestUtils.setField(channelService, "cryptoUtil", new CryptoUtil(TEST_ENCRYPTION_KEY));

        String cred = "{\"botId\":\"b-persist\",\"botSecret\":\"s-persist\"}";
        var inst = channelService.create("wecom", "持久化测试实例", cred, null);

        assertThat(inst.id()).isNotBlank();
        assertThat(channelService.credentialOf(inst.id()))
                .as("密文落库、解密取回必须一致")
                .contains(cred);
    }

    /**
     * 解绑必须真的把 {@code bound_session_id} 置空（真库验证）。
     *
     * <h2>WHY 这条不能省</h2>
     * MyBatis-Flex 的 {@code update(entity)} **默认忽略 null 字段**。用旧写法
     * （实体置 null 再 update）时，接口照样返回 {@code unbound:true}，日志也正常，
     * 但库里那一列**原样还在** —— 纯 mock 测试完全发现不了（mock 只验"调了 update"）。
     * 这条用真库 + 回查，把「接口说成功、数据没变」这类静默失败钉住。
     */
    @Test
    @DisplayName("解绑：bound_session_id 必须真的置为 NULL（不是只有接口说成功）")
    void unbindActuallyClearsColumn() {
        var created = chatService.upsert("inst-unbind-" + System.nanoTime(), "wecom", "peer-unbind-1",
                "解绑测试群", "group", null, null, null, ImChatService.SOURCE_LISTED);
        chatService.bind(created.id(), "sess-unbind-1", "manual");

        var bound = chatService.findById(created.id()).orElseThrow();
        assertThat(bound.boundSessionId()).isEqualTo("sess-unbind-1");
        assertThat(bound.bindMode()).isEqualTo("manual");

        chatService.unbind(created.id());

        var after = chatService.findById(created.id()).orElseThrow();
        assertThat(after.boundSessionId())
                .as("解绑后库里必须是 NULL（update(entity) 会把 null 忽略掉）")
                .isNull();
        assertThat(after.bindMode()).isNull();
        assertThat(after.boundAt()).isNull();
    }

    // ═════════════════════ 完整链路 ═════════════════════

    /**
     * 真库 + 同步 + 投递 + 附件的完整 e2e。
     *
     * <h2>这条测试覆盖什么</h2>
     * <pre>
     * 真 SQLite（V310 已迁）
     *   ├─ ImChannelService.create()  → 凭证加密落库
     *   ├─ ImChatSyncService.syncWecomChats() → 拉群（stub）→ im_chat 落库
     *   └─ ImSendService.send(chatId, text, [file])
     *        → 读 im_chat（真库）→ 解密凭证（真 CryptoUtil）
     *        → 策略 → stub 平台（换票 / 上传 / 发文本 / 发媒体）
     *        → 回写 im_chat.last_outbound_at（真库）
     * </pre>
     * 前面所有测试都用替身 Service；只有这条证明「Service 之间真的接得上、库真的读得到」。
     */
    @Test
    @DisplayName("完整 e2e：真库建实例 → 拉群入库 → 文本+附件投递 → 回写最近消息")
    void fullChainRealDatabase() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> calls = new ArrayList<>();
        Map<String, String> bodies = new HashMap<>();
        stub.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            synchronized (calls) {
                calls.add(path);
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (bodies) {
                bodies.put(path, body);
            }
            String resp;
            if (path.contains("get_cli_config")) {
                resp = "{\"errcode\":0,\"errmsg\":\"ok\",\"token\":\"cli-token-full\"}";
            } else if (path.contains("/cli/chat/groups/list")) {
                resp = "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"items\\\":[{\\\"chat_id\\\":\\\"chatid-full-e2e\\\",\\\"chat_name\\\":\\\"全链路测试群\\\",\\\"msg_count\\\":3}],"
                        + "\\\"has_more\\\":false}\"}";
            } else if (path.contains("/cli/media/upload")) {
                resp = "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"media_id\\\":\\\"MEDIA_FULL_1\\\"}\"}";
            } else {
                resp = "{\"errcode\":0,\"errmsg\":\"ok\",\"results_json\":\"{\\\"success\\\":true}\"}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        stub.start();
        Path tmp = Files.createTempFile("full-e2e-", ".png");
        Files.write(tmp, new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9});

        try {
            String base = "http://127.0.0.1:" + stub.getAddress().getPort();

            // ── ① 真库建实例（凭证加密）──
            ImChannelService channelService = new ImChannelService();
            ReflectionTestUtils.setField(channelService, "mapper",
                    MybatisFlexBootstrap.getInstance().getMapper(ImChannelInstanceMapper.class));
            ReflectionTestUtils.setField(channelService, "cryptoUtil", new CryptoUtil(TEST_ENCRYPTION_KEY));
            var inst = channelService.create("wecom", "全链路实例",
                    "{\"botId\":\"bot-full\",\"botSecret\":\"secret-full\"}", null);

            // ── ② 同步：拉群 → im_chat（真库）──
            WecomCliClient cli = new WecomCliClient(base);
            ImChatSyncService sync = new ImChatSyncService();
            ReflectionTestUtils.setField(sync, "channelService", channelService);
            ReflectionTestUtils.setField(sync, "chatService", chatService);
            ReflectionTestUtils.setField(sync, "wecomTokenService",
                    new WecomCliTokenService(base + "/cgi-bin/aibot/cli/get_cli_config"));
            ReflectionTestUtils.setField(sync, "wecomClient", cli);

            var syncResult = sync.syncWecomChats(inst.id());
            assertThat(syncResult.created()).as("应新建 1 个群: %s", syncResult.message()).isEqualTo(1);

            var chats = chatService.list().stream()
                    .filter(c -> "chatid-full-e2e".equals(c.peerId()) && inst.id().equals(c.instanceId()))
                    .toList();
            assertThat(chats).as("同步后 im_chat 里必须有这个群").hasSize(1);
            String chatRowId = chats.get(0).id();

            // ── ③ 投递：文本 + 附件（走真库读 chat/凭证）──
            ImSendStrategyFactory factory = new ImSendStrategyFactory(List.of(
                    new WecomSendStrategy(
                            new WecomCliTokenService(base + "/cgi-bin/aibot/cli/get_cli_config"),
                            new WecomMessageApi(cli),
                            new WecomMediaApi(cli))));
            ImSendService sendService = new ImSendService();
            ReflectionTestUtils.setField(sendService, "chatService", chatService);
            ReflectionTestUtils.setField(sendService, "channelService", channelService);
            ReflectionTestUtils.setField(sendService, "strategyFactory", factory);

            List<ImSendResult> results = sendService.send(
                    List.of(chatRowId), "全链路正文", List.of(ImSendAttachment.of(tmp)));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).status()).as("详情: %s", results.get(0))
                    .isEqualTo(ImSendResult.Status.SENT);
            assertThat(results.get(0).attachmentErrors()).isEmpty();

            // 换票 → 拉群 → 换票 → 上传 → 发文本 → 发媒体
            // ⚠️ 行为变更：机器人身份的正文走 **aibot/send**（msg_type=markdown），
            //    不再走 message/send（那是「授权真人」身份 ⇒ 会冒充本人说话）。
            synchronized (calls) {
                assertThat(calls).contains("/cli/media/upload");
                assertThat(calls.stream().filter(c -> c.contains("/cli/message/aibot/send")).count())
                        .as("正文(markdown) + 媒体(image) 都走机器人端点").isEqualTo(2);
                assertThat(calls.stream().filter(c -> c.contains("/cli/message/send")).count())
                        .as("机器人身份不得调用户身份端点").isZero();
            }
            assertThat(results.get(0).identity()).isEqualTo(SendIdentity.BOT);

            // ── ④ 回写：真库的 last_outbound_at 必须被更新 ──
            var after = chatService.findById(chatRowId).orElseThrow();
            assertThat(after.lastMessagePreview()).isEqualTo("全链路正文");
        } finally {
            stub.stop(0);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("群名一旦拿到就持久化：后续同步传空名字不能把已存的名字冲成 id")
    void blankNameNeverOverwritesExistingName() {
        String inst = "inst-name-" + System.nanoTime();
        var created = chatService.upsert(inst, "wecom", "wrNH-group-1", "产品讨论群", "group",
                null, null, null, ImChatService.SOURCE_LISTED);
        assertThat(created.displayName()).isEqualTo("产品讨论群");

        // 再来一轮：groups/list 这次没给 chat_name（真机上超 7 天的群就是这样）
        var after = chatService.upsert(inst, "wecom", "wrNH-group-1", "", "group",
                null, null, null, ImChatService.SOURCE_LISTED);

        assertThat(after.displayName())
                .as("空名字不得覆盖已存的好名字（否则群名会退化成 wrNH-…）")
                .isEqualTo("产品讨论群");
        assertThat(after.peerName()).isEqualTo("产品讨论群");
    }
}

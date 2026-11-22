package com.nexusai.domain.im_channel;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.ImMediaRef;
import com.nexusai.domain.im_channel.send.ImMediaUploader;
import com.nexusai.domain.im_channel.send.ImSendAttachment;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendException;
import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.domain.im_channel.send.MediaKind;
import com.nexusai.domain.im_channel.send.SendCapability;
import com.nexusai.domain.im_channel.send.SendIdentity;
import com.nexusai.domain.im_message.ImMessageService;
import com.nexusai.domain.im_message.ImMessageTime;
import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.model.im_message.ImMessageEntry;
import com.nexusai.repository.im_message.entity.ImMessageRecord;
import com.nexusai.repository.im_message.mapper.ImMessageMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「发出去的消息要在本地 {@code im_message} 留一行」的真库验证（V315 · {@code direction='out'}）。
 *
 * <h2>WHY 这套测试必须走真库</h2>
 * 本任务的价值<b>全部落在持久化语义</b>上，替身一条都证明不了：
 * <ul>
 *   <li>那一行真的落库了（不是「我们调用了 upsert」）；</li>
 *   <li>{@code direction='out'} / {@code origin='local'} 真的写进去了
 *       （MyBatis-Flex 靠 camelCase↔snake_case 约定映射，<b>拼错不报错、只静默丢值</b>）；</li>
 *   <li>{@code message_id} 存的是<b>平台返回的那个 id</b>（不是我们编的、也不是空的）；</li>
 *   <li>幂等是<b>库在拦</b>（{@code UNIQUE(instance_id, dedup_key)}），不是「我们记得跳过」；</li>
 *   <li>{@code sent_at} 落在 TEXT 列里就是规范形态（{@code +08:00} 定宽），否则字典序排序会静默错乱。</li>
 * </ul>
 * 平台侧全部用<b>假策略</b>（不发任何网络请求），⛔ 不打真平台。
 *
 * <h2>为什么用共享稳定 SQLite + Flyway</h2>
 * 与全仓其它 Flex 测试同一范式（见 {@code ImMessagePersistenceTest}）⇒ ⛔ 不碰用户真库。
 */
class ImSendServiceOutboundRecordTest {

    /** {@code sent_at} 的规范形态：{@code yyyy-MM-dd'T'HH:mm:ss.SSS+08:00}（定宽 29 字符）。 */
    private static final Pattern CANONICAL_SENT_AT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+08:00$");

    private static SQLiteDataSource ds;
    private static ImMessageMapper messageMapper;

    // ───────────────────────── 替身 ─────────────────────────

    /** 内存版聊天目录（不碰库）。 */
    private static final class FakeChatService extends ImChatService {
        final Map<String, ImChatEntry> chats = new HashMap<>();

        @Override public Optional<ImChatEntry> findById(String id) {
            return Optional.ofNullable(chats.get(id));
        }
        @Override public Optional<String> replyTokenOf(String chatId) {
            return Optional.empty();
        }
        @Override public void markOutbound(String chatId, String preview, String at) {
            // 无副作用：这里只关心消息表
        }
    }

    /** 内存版渠道服务：凭证 / 实例显示名 / 用户授权身份。 */
    private static final class FakeChannelService extends ImChannelService {
        final Map<String, String> creds = new HashMap<>();
        final Map<String, ImInstanceEntry> instances = new HashMap<>();
        String userOpenId;
        String userName;

        @Override public Optional<String> credentialOf(String id) {
            return Optional.ofNullable(creds.get(id));
        }
        @Override public Optional<ImInstanceEntry> findById(String id) {
            return Optional.ofNullable(instances.get(id));
        }
        @Override public Optional<String> userOpenIdOf(String id) {
            return Optional.ofNullable(userOpenId);
        }
        @Override public Optional<String> userNameOf(String id) {
            return Optional.ofNullable(userName);
        }
    }

    /** 可编排的假策略（不发网络请求）。 */
    private static class FakeStrategy extends AbstractImSendStrategy {
        final String platform;
        /** 平台返回的消息 id；null = 平台不返回 id（企微就是这样）。 */
        String messageId = "om_test_1";
        boolean fail;

        FakeStrategy(String platform) {
            this.platform = platform;
        }

        @Override public String platform() { return platform; }
        @Override public SendCapability capability() { return SendCapability.proactive(1000, true); }
        @Override public boolean canSendAsUser() { return true; }

        @Override public String doSend(ImSendContext ctx, String text) throws ImSendException {
            if (fail) {
                throw new ImSendException("IM_SEND_PLATFORM_ERROR", "模拟平台拒绝");
            }
            return messageId;
        }
    }

    /** 附件上传必失败的策略（正文照发，附件记失败）。 */
    private static final class FailingUploadStrategy extends FakeStrategy {
        FailingUploadStrategy(String platform) { super(platform); }

        @Override protected ImMediaUploader mediaUploader() {
            final String p = platform;
            return new ImMediaUploader() {
                @Override public String platform() { return p; }
                @Override public boolean supports(MediaKind kind) { return true; }
                @Override public ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind)
                        throws ImSendException {
                    throw new ImSendException("IM_MEDIA_UPLOAD_FAILED", "模拟上传失败");
                }
            };
        }
    }

    /** 附件上传成功的策略（正文 + 附件都发出去）。 */
    private static final class WorkingUploadStrategy extends FakeStrategy {
        WorkingUploadStrategy(String platform) { super(platform); }

        @Override protected ImMediaUploader mediaUploader() {
            final String p = platform;
            return new ImMediaUploader() {
                @Override public String platform() { return p; }
                @Override public boolean supports(MediaKind kind) { return true; }
                @Override public ImMediaRef upload(ImSendContext ctx, Path file, MediaKind kind) {
                    return new ImMediaRef(kind, com.fasterxml.jackson.databind.node.TextNode.valueOf("key-1"));
                }
            };
        }
    }

    /** 写库必失败的 messageService（用来验「记账失败不改判发送失败」）。 */
    private static final class ThrowingMessageService extends ImMessageService {
        @Override public boolean upsert(ImMessageEntry e, String key) {
            throw new IllegalStateException("模拟写库失败");
        }
    }

    // ───────────────────────── 装配 ─────────────────────────

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

        MybatisFlexDbTestSupport.resetAndStart(ds, ImMessageMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(ImMessageMapper.class);
    }

    /**
     * 组一套「假平台 + 真库」的发送环境。
     *
     * @param messageService 写库用的 messageService（可以是被替换成抛异常版本）
     */
    private static Env env(ImMessageService messageService, ImSendStrategy... strategies) {
        FakeChatService chats = new FakeChatService();
        FakeChannelService channels = new FakeChannelService();
        ImMessageOutboundRecorder recorder = new ImMessageOutboundRecorder();
        ReflectionTestUtils.setField(recorder, "messageService", messageService);
        ReflectionTestUtils.setField(recorder, "channelService", channels);

        ImSendService service = new ImSendService();
        ReflectionTestUtils.setField(service, "chatService", chats);
        ReflectionTestUtils.setField(service, "channelService", channels);
        ReflectionTestUtils.setField(service, "strategyFactory",
                new ImSendStrategyFactory(List.of(strategies)));
        ReflectionTestUtils.setField(service, "outboundRecorder", recorder);

        return new Env(service, chats, channels, recorder);
    }

    private record Env(ImSendService service, FakeChatService chats,
                        FakeChannelService channels, ImMessageOutboundRecorder recorder) {
    }

    /** 造一个聊天（本地目录 id 与平台侧 peerId <b>故意不同</b>，用来抓「chat_id 存错」）。 */
    private static void givenChat(Env env, String instanceId, String platform, String peerId) {
        env.chats().chats.put("imch-1",
                new ImChatEntry("imch-1", instanceId, platform, peerId, "会话名",
                        "group", 3, null, null, null, null, null, "t", "t"));
        env.channels().creds.put(instanceId, "{}");
        env.channels().instances.put(instanceId,
                new ImInstanceEntry(instanceId, platform, "我的机器人", "connected", null,
                        null, null, "t", "t"));
    }

    // ═══════════════════════ SENT ⇒ 写一行 ═══════════════════════

    @Test
    @DisplayName("SENT ⇒ 本地多一行 direction=out / origin=local / message_id=平台返回的 id")
    void sentWritesOutboundRow() {
        String inst = "imc-out-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_1");

        ImSendResult r = env.service().send(List.of("imch-1"), "这是我们的回答").get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.messageId()).isEqualTo("om_test_1");

        List<ImMessageEntry> rows = rowsOf(inst);
        assertThat(rows).as("本地必须多一行（否则对话是断的）").hasSize(1);
        ImMessageEntry row = rows.get(0);
        assertThat(row.direction()).isEqualTo("out");
        assertThat(row.origin()).isEqualTo("local");
        assertThat(row.outbound()).isTrue();
        assertThat(row.messageId()).as("必须用平台返回的 id").isEqualTo("om_test_1");
        assertThat(row.content()).isEqualTo("这是我们的回答");
        assertThat(row.platform()).isEqualTo("feishu");
        assertThat(row.chatId()).as("chat_id 必须是平台侧会话 id，不是本地目录 id").isEqualTo("oc_peer_1");
        assertThat(row.instanceId()).isEqualTo(inst);
    }

    @Test
    @DisplayName("机器人身份 ⇒ sender 是实例本身（sender_name=实例 displayName，不是 owner 名字）")
    void botIdentityUsesInstanceAsSender() {
        String inst = "imc-bot-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("wecom"));
        givenChat(env, inst, "wecom", "wr_group_1");
        env.channels().userOpenId = "ou_owner";
        env.channels().userName = "实例主人";

        env.service().send(List.of("imch-1"), "机器人消息");

        ImMessageEntry row = rowsOf(inst).get(0);
        assertThat(row.senderId()).isEqualTo(inst);
        assertThat(row.senderName()).isEqualTo("我的机器人");
        assertThat(row.senderName()).isNotEqualTo("实例主人");
    }

    @Test
    @DisplayName("用户身份 ⇒ sender 是授权真人（open_id + 昵称），不是实例主人乱猜")
    void userIdentityUsesAuthorizedUser() {
        String inst = "imc-user-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_2");
        env.channels().userOpenId = "ou_authorized";
        env.channels().userName = "张三";

        ImSendResult r = env.service().send(List.of("imch-1"), "以本人名义发的",
                null, SendIdentity.USER).get(0);

        assertThat(r.identity()).isEqualTo(SendIdentity.USER);
        ImMessageEntry row = rowsOf(inst).get(0);
        assertThat(row.senderId()).isEqualTo("ou_authorized");
        assertThat(row.senderName()).isEqualTo("张三");
    }

    // ═══════════════════════ FAILED / SKIPPED ⇒ 不写 ═══════════════════════

    @Test
    @DisplayName("FAILED（平台拒绝）⇒ 不写：没发出去就不能有历史行")
    void failedDoesNotWrite() {
        String inst = "imc-fail-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        FakeStrategy feishu = new FakeStrategy("feishu");
        feishu.fail = true;
        Env env = env(messageService, feishu);
        givenChat(env, inst, "feishu", "oc_peer_3");

        ImSendResult r = env.service().send(List.of("imch-1"), "发不出去").get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.FAILED);
        assertThat(rowsOf(inst)).as("失败的发送不能留下假历史").isEmpty();
    }

    @Test
    @DisplayName("SKIPPED（平台不支持主动发）⇒ 不写")
    void skippedDoesNotWrite() {
        String inst = "imc-skip-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("feishu"));
        givenChat(env, inst, "qq", "p_unsupported");

        ImSendResult r = env.service().send(List.of("imch-1"), "发不出去").get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SKIPPED);
        assertThat(rowsOf(inst)).isEmpty();
    }

    // ═══════════════════════ 附件失败但正文 SENT ⇒ 仍写 ═══════════════════════

    @Test
    @DisplayName("附件失败但正文 SENT ⇒ 仍要写（正文确实到了）")
    void attachmentFailureStillWrites() {
        String inst = "imc-att-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FailingUploadStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_4");

        ImSendResult r = env.service().send(List.of("imch-1"), "正文到了",
                List.of(ImSendAttachment.of(Path.of("/tmp/nexusai-not-exist.png")))).get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.attachmentErrors()).as("附件确实失败了").isNotEmpty();

        List<ImMessageEntry> rows = rowsOf(inst);
        assertThat(rows).as("正文到了就必须留痕").hasSize(1);
        assertThat(rows.get(0).content()).isEqualTo("正文到了");
        assertThat(rows.get(0).direction()).isEqualTo("out");
        assertThat(rows.get(0).mediaJson())
                .as("上传失败的附件根本没发出去，不能记成「我们发过它」")
                .isNull();
    }

    @Test
    @DisplayName("附件上传成功 ⇒ media_json 记附件引用（只存引用，不存二进制）")
    void attachmentSuccessStoresMediaRef() {
        String inst = "imc-media-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new WorkingUploadStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_8");

        ImSendResult r = env.service().send(List.of("imch-1"), "带图",
                List.of(ImSendAttachment.of(Path.of("/tmp/nexusai-pic.png")))).get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.attachmentErrors()).isEmpty();

        ImMessageEntry row = rowsOf(inst).get(0);
        assertThat(row.msgType()).isEqualTo("mixed");
        assertThat(row.mediaJson()).contains("nexusai-pic.png").contains("image");
        assertThat(row.mediaJson()).as("只存引用，不存二进制").doesNotContain("base64");
    }

    // ═══════════════════════ 记账失败不改判发送结果 ═══════════════════════

    @Test
    @DisplayName("写库抛异常 ⇒ 发送结果仍然是 SENT（记账失败不能改判发送失败）")
    void upsertFailureKeepsSentStatus() {
        String inst = "imc-boom-" + System.nanoTime();
        Env env = env(new ThrowingMessageService(), new FakeStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_5");

        ImSendResult r = env.service().send(List.of("imch-1"), "已经发出去了").get(0);

        assertThat(r.status()).as("消息已经发出去，不能因为记账失败改判成失败").isEqualTo(ImSendResult.Status.SENT);
        assertThat(r.messageId()).isEqualTo("om_test_1");
    }

    // ═══════════════════════ 幂等 ═══════════════════════

    @Test
    @DisplayName("同一 messageId 写两次 ⇒ 库里只有一行（幂等由 UNIQUE(instance_id, dedup_key) 兜底）")
    void sameMessageIdTwiceKeepsOneRow() {
        String inst = "imc-idem-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_6");

        env.service().send(List.of("imch-1"), "同一条");
        env.service().send(List.of("imch-1"), "同一条");

        assertThat(rowsOf(inst)).as("同一平台消息 id 只能有一行").hasSize(1);
        ImMessageRecord raw = rawRecords(inst).get(0);
        assertThat(raw.getDedupKey())
                .as("有平台 id 时幂等键就是它本身（不是 hash）")
                .isEqualTo("om_test_1");
    }

    @Test
    @DisplayName("平台不返回 messageId（企微）⇒ 仍写一行，且不编造 id（走 hash 幂等分支）")
    void missingMessageIdFallsBackToHashBranch() {
        String inst = "imc-noid-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        FakeStrategy wecom = new FakeStrategy("wecom");
        wecom.messageId = null;
        Env env = env(messageService, wecom);
        givenChat(env, inst, "wecom", "wr_group_2");

        ImSendResult r = env.service().send(List.of("imch-1"), "企微发的").get(0);

        assertThat(r.status()).isEqualTo(ImSendResult.Status.SENT);
        List<ImMessageEntry> rows = rowsOf(inst);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).messageId()).as("没有平台 id 就不能编一个").isNull();
        assertThat(rawRecords(inst).get(0).getDedupKey())
                .as("无平台 id ⇒ 走 hash 分支（已知重复风险：同一条被记两次会算成两行）")
                .startsWith("sha256:");
    }

    // ═══════════════════════ sent_at 形态 ═══════════════════════

    @Test
    @DisplayName("sent_at 落库形态是 canonical 的（+08:00 定宽），否则字典序排序会静默错乱")
    void sentAtIsCanonical() throws Exception {
        String inst = "imc-time-" + System.nanoTime();
        ImMessageService messageService = new ImMessageService();
        ReflectionTestUtils.setField(messageService, "mapper", messageMapper);
        Env env = env(messageService, new FakeStrategy("feishu"));
        givenChat(env, inst, "feishu", "oc_peer_7");

        env.service().send(List.of("imch-1"), "时间要对");

        String rawSentAt = rawSentAt(inst);
        assertThat(rawSentAt).matches(CANONICAL_SENT_AT);
        assertThat(ImMessageTime.canonical(rawSentAt))
                .as("落库形态必须已经是 canonical（再归一一次不能变）")
                .isEqualTo(rawSentAt);
        assertThat(rawSentAt).endsWith("+08:00");
    }

    // ═══════════════════════ 辅助 ═══════════════════════

    /** 某个实例在 {@code im_message} 里的全部行（domain 形态）。 */
    private static List<ImMessageEntry> rowsOf(String instanceId) {
        return messageMapper.selectListByQuery(
                        QueryWrapper.create().where("instance_id = ?", instanceId))
                .stream().map(ImMessageRecord::toDomain).toList();
    }

    /** 某个实例在 {@code im_message} 里的全部行（原始 record，能看 dedup_key）。 */
    private static List<ImMessageRecord> rawRecords(String instanceId) {
        return messageMapper.selectListByQuery(
                QueryWrapper.create().where("instance_id = ?", instanceId));
    }

    /**
     * 直接 JDBC 读 {@code sent_at} 原始值（绕开 ORM）。
     *
     * <p>为什么要「旁证」：ORM 读回的值可能已经被某种转换动过；
     * 这一列是 TEXT 且靠<b>字典序</b>比较 ⇒ 必须证明库里存的就是那个字符串本身。
     */
    private static String rawSentAt(String instanceId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT sent_at FROM im_message WHERE instance_id = ?")) {
            ps.setString(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("必须真的有一行").isTrue();
                return rs.getString("sent_at");
            }
        }
    }
}

package com.nexusai.domain.im_channel;

import com.nexusai.model.im_channel.ImChatEntry;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V335 · 「机器人是否在群」的可达性判定（真库）。
 *
 * <h2>WHY 要真库</h2>
 * 这里钉的是<b>列值</b>与<b>默认视图的过滤</b>：
 * <ul>
 *   <li>{@code bot_present = 0} 的群不出现在 {@code listVisible()} 里；</li>
 *   <li>p2p / 微信恒可达（飞书 bot 身份列不到单聊，不能据此判死）；</li>
 *   <li>已接线但不可达的仍要可见 —— 否则用户没地方解绑；</li>
 *   <li>状态没变时不重复写（每 5 分钟一轮，别刷爆 updated_at）。</li>
 * </ul>
 * 用 Mockito 证明不了「列真的写进去了」，所以走共享稳定 SQLite + Flyway（与全仓 Flex 测试同范式）。
 */
@DisplayName("IM 聊天 · 机器人可达性(V335)")
class ImChatBotPresenceTest {

    private static SQLiteDataSource ds;
    private static ImChatMapper chatMapper;

    /**
     * 每个测试用唯一实例前缀：共享 SQLite 会被重复跑时复用，
     * 固定 instanceId 会让「首次判定」变成「状态早已是 true」（本测试第一版就踩了）。
     */
    private static final String INST = "imc-bp-" + Long.toHexString(System.nanoTime()) + "-";

    private ImChatService chatService;

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
        MybatisFlexDbTestSupport.resetAndStart(ds, ImChatMapper.class);
        chatMapper = com.mybatisflex.core.MybatisFlexBootstrap.getInstance()
            .getMapper(ImChatMapper.class);
    }

    @BeforeEach
    void setUp() {
        chatService = new ImChatService();
        ReflectionTestUtils.setField(chatService, "mapper", chatMapper);
    }

    private ImChatEntry newChat(String instanceId, String platform, String peerId, String kind) {
        return chatService.upsert(instanceId, platform, peerId, peerId, kind,
                null, null, null, ImChatService.SOURCE_LISTED);
    }

    @Test
    @DisplayName("群不在平台列表里 ⇒ 标不可达，默认视图不显示")
    void groupNotInPlatformList_becomesInvisible() {
        String inst = INST + "1";
        ImChatEntry group = newChat(inst, "feishu", "oc_gone", "group");

        chatService.markBotPresence(inst, Set.of("oc_other"));

        ImChatEntry after = chatService.findById(group.id()).orElseThrow();
        assertThat(after.botPresent()).isFalse();
        assertThat(after.visibleInList()).isFalse();
        assertThat(chatService.listVisible()).extracting(ImChatEntry::id).doesNotContain(group.id());
        // 原行仍在库里（只是不可见）——后期「开放显示」不需要重新同步
        assertThat(chatService.list()).extracting(ImChatEntry::id).contains(group.id());
    }

    @Test
    @DisplayName("p2p 恒可达；微信群聊不显示（iLink 无群发通道）")
    void p2pReachable_wechatGroupHidden() {
        String inst = INST + "2";
        ImChatEntry p2p = newChat(inst, "feishu", "oc_p2p", "p2p");
        ImChatEntry wxGroup = newChat(INST + "2wx", "wechat", "wx-group", "group");

        // 平台列表为空也不该把 p2p 判死（bot 群列表不含单聊）
        chatService.markBotPresence(inst, Set.of());
        chatService.markBotPresence(INST + "2wx", Set.of());

        assertThat(chatService.findById(p2p.id()).orElseThrow().botPresent()).isTrue();
        assertThat(chatService.listVisible()).extracting(ImChatEntry::id).contains(p2p.id());
        // 微信群聊：可达（登录账号就在群里），但**能收不能群发** ⇒ 默认列表不显示
        assertThat(chatService.findById(wxGroup.id()).orElseThrow().botPresent()).isTrue();
        assertThat(chatService.listVisible()).extracting(ImChatEntry::id).doesNotContain(wxGroup.id());
    }

    @Test
    @DisplayName("微信单聊：未激活（无 context_token）不显示，拿到令牌后显示")
    void wechatP2p_needsActivation() {
        String inst = INST + "6";
        ImChatEntry chat = newChat(inst, "wechat", "wx-friend", "p2p");

        assertThat(chatService.listVisible()).extracting(ImChatEntry::id)
            .as("未激活 ⇒ 现在发不出去").doesNotContain(chat.id());

        chatService.saveReplyToken(chat.id(), "tok-1", null);

        assertThat(chatService.listVisible()).extracting(ImChatEntry::id)
            .as("有令牌 ⇒ 能主动发").contains(chat.id());
    }

    @Test
    @DisplayName("已接线但机器人被移出群 ⇒ 默认视图仍然显示（否则解绑不了）")
    void boundUnreachable_stillVisible() {
        String inst = INST + "3";
        ImChatEntry group = newChat(inst, "feishu", "oc_bound_gone", "group");
        chatService.bind(group.id(), "sess-1", "manual");

        chatService.markBotPresence(inst, Set.of());

        ImChatEntry after = chatService.findById(group.id()).orElseThrow();
        assertThat(after.botPresent()).isFalse();
        assertThat(after.visibleInList()).as("已接线 ⇒ 可见").isTrue();
        assertThat(chatService.listVisible()).extracting(ImChatEntry::id).contains(group.id());
    }

    @Test
    @DisplayName("状态没变时不重复写（第二轮返回 0）")
    void unchangedRows_notRewritten() {
        String inst = INST + "4";
        newChat(inst, "feishu", "oc_stable", "group");

        assertThat(chatService.markBotPresence(inst, Set.of("oc_stable"))).isEqualTo(1);
        assertThat(chatService.markBotPresence(inst, Set.of("oc_stable")))
            .as("第二次同集合：不该再写").isZero();
    }

    @Test
    @DisplayName("入站消息推翻「不在群」判定（机器人后来被拉回群）")
    void inboundMakesItReachableAgain() {
        String inst = INST + "5";
        ImChatEntry group = newChat(inst, "feishu", "oc_back", "group");
        chatService.markBotPresence(inst, Set.of());
        assertThat(chatService.findById(group.id()).orElseThrow().botPresent()).isFalse();

        // 入站 upsert = 机器人确实收到了该群的消息
        chatService.upsertOnInbound(inst, "feishu", "oc_back", "群名", "group", null, "你好", null);

        assertThat(chatService.findById(group.id()).orElseThrow().botPresent()).isTrue();
    }
}

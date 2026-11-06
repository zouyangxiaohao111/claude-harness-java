package com.nexusai.domain.mcp_channel_binding;

import com.nexusai.model.mcp_channel_binding.ChannelBindingEntry;
import com.nexusai.repository.mcp_channel_binding.entity.ChannelBindingRecord;
import com.nexusai.repository.mcp_channel_binding.mapper.ChannelBindingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [V255 外部渠道] {@link ChannelBindingService} 互斥语义测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：绑定表承载「渠道归哪个会话」——
 * MCP 连接是全局单例而消息必须投给具体会话，这张表是两者之间唯一的桥。
 * 它的**互斥键选错**（用 serverName 而非渠道 id）或**抢占语义搞反**，后果都是
 * 「消息投给了错误的会话」——而这个错误**不会报错**，只会表现为「微信发了消息，
 * 但会话里毫无反应」或「两个会话同时收到同一条消息」。这类静默错误必须靠测试钉死。
 *
 * <p>钉死的四条语义：
 * <ol>
 *   <li><b>互斥键 = 渠道 id</b>：同一渠道 id 被别的会话占着 ⇒ 冲突（不静默抢占）；</li>
 *   <li><b>同会话重复绑定 = 幂等</b>（不新建行，不报冲突）；</li>
 *   <li><b>force 才抢占</b>：抢占后旧绑定消失、新绑定生效（不是「两条并存」）；</li>
 *   <li><b>解绑校验归属</b>：非归属会话不能解绑。</li>
 * </ol>
 *
 * <p><b>用 Mockito 而非实现 Mapper</b>：{@code BaseMapper} 的抽象方法面太大
 * （含 selectObjectListByQuery 等），手写内存实现不现实；本测试只关心
 * <b>服务层的互斥判定</b>，Mapper 只作为可编排的桩。
 */
@DisplayName("[V255] 渠道↔会话绑定 · 互斥语义")
class ChannelBindingServiceTest {

    private ChannelBindingService service;
    private ChannelBindingMapper mapper;

    /** 当前「DB 里的」绑定行（模拟 UNIQUE(channel_id) 约束：同渠道至多一行）。 */
    private final List<ChannelBindingRecord> rows = new ArrayList<>();
    private int idSeq = 0;

    @BeforeEach
    void setUp() {
        mapper = mock(ChannelBindingMapper.class);
        rows.clear();
        idSeq = 0;

        // 按渠道 id 查（selectOneByChannelId）
        when(mapper.selectOneByChannelId(anyString())).thenAnswer(inv -> {
            String channelId = inv.getArgument(0);
            return rows.stream()
                .filter(r -> r.getChannelId().equals(channelId))
                .findFirst().orElse(null);
        });
        // 按会话 id 查（selectListBySessionId）
        when(mapper.selectListBySessionId(anyString())).thenAnswer(inv -> {
            String sessionId = inv.getArgument(0);
            return rows.stream().filter(r -> r.getSessionId().equals(sessionId)).toList();
        });
        // 按 server 名查（selectListByServerName）
        when(mapper.selectListByServerName(anyString())).thenAnswer(inv -> {
            String serverName = inv.getArgument(0);
            return rows.stream().filter(r -> r.getServerName().equals(serverName)).toList();
        });
        when(mapper.selectAll()).thenAnswer(inv -> new ArrayList<>(rows));
        when(mapper.insert(any())).thenAnswer(inv -> {
            ChannelBindingRecord rec = inv.getArgument(0);
            // 模拟 DB 的 UNIQUE(channel_id)
            boolean dup = rows.stream().anyMatch(r -> r.getChannelId().equals(rec.getChannelId()));
            if (dup) throw new IllegalStateException("UNIQUE constraint failed: channel_id");
            if (rec.getId() == null) rec.setId("chb-" + (idSeq++));
            rows.add(rec);
            return 1;
        });
        when(mapper.update(any())).thenAnswer(inv -> {
            ChannelBindingRecord rec = inv.getArgument(0);
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).getId().equals(rec.getId())) { rows.set(i, rec); return 1; }
            }
            return 0;
        });
        when(mapper.deleteById(any())).thenAnswer(inv -> {
            Object id = inv.getArgument(0);
            return rows.removeIf(r -> r.getId().equals(id)) ? 1 : 0;
        });

        service = new ChannelBindingService();
        ReflectionTestUtils.setField(service, "mapper", mapper);
    }

    @Test
    @DisplayName("首次绑定 → 成功，落在指定会话")
    void firstBindSucceeds() {
        ChannelBindingService.BindResult r = service.bind("wechat", "wechat-channel", "sess-a", false);

        assertThat(r.conflicted()).as("首次绑定不应冲突").isFalse();
        assertThat(r.entry().channelId()).isEqualTo("wechat");
        assertThat(r.entry().sessionId()).isEqualTo("sess-a");
        assertThat(service.findByChannelId("wechat")).isPresent();
    }

    @Test
    @DisplayName("同渠道被别的会话占用 → 冲突（不静默抢占）")
    void conflictingChannelRejected() {
        // WHY：静默抢占的后果是「A 会话刚接的微信被 B 抢走，A 那边无声失联」。
        //   必须显式返回冲突，让上层决定是拒绝还是走用户确认。
        service.bind("wechat", "wechat-channel", "sess-a", false);

        ChannelBindingService.BindResult r = service.bind("wechat", "wechat-channel", "sess-b", false);

        assertThat(r.conflicted())
            .as("同渠道被别的会话占用时必须报冲突，不能默默改绑")
            .isTrue();
        assertThat(r.conflict().sessionId())
            .as("冲突详情必须指出占用者是谁（前端要拿它弹确认）")
            .isEqualTo("sess-a");
        assertThat(service.findByChannelId("wechat").orElseThrow().sessionId())
            .as("原绑定必须原样保留")
            .isEqualTo("sess-a");
    }

    @Test
    @DisplayName("不同渠道可绑到同一会话并存（微信 + 飞书 + 企微）")
    void differentChannelsCoexistInSameSession() {
        // WHY：用户裁定「一个会话可以同时绑定一个微信一个飞书一个企微」——
        //   互斥只约束**同渠道**，不约束同会话。
        service.bind("wechat", "wechat-channel", "sess-a", false);
        service.bind("feishu", "feishu-channel", "sess-a", false);
        service.bind("wecom", "wecom-channel", "sess-a", false);

        assertThat(service.listBySession("sess-a"))
            .as("同一会话应能同时持有三个不同渠道")
            .hasSize(3);
    }

    @Test
    @DisplayName("互斥键 = 渠道 id：两个 server 同为 id=wechat 仍互斥")
    void mutualExclusionIsByChannelIdNotServerName() {
        // WHY：这是「不做 serverName 兜底」的核心价值 —— 两个不同的 MCP server
        //   承载同一个渠道（wechat）时，它们**共享同一个微信入口**，
        //   若按 serverName 判互斥就会两个都绑上 ⇒ 消息被路由到错误的会话。
        service.bind("wechat", "wechat-channel", "sess-a", false);

        ChannelBindingService.BindResult r = service.bind("wechat", "another-wechat-server", "sess-b", false);

        assertThat(r.conflicted())
            .as("同一渠道 id 即使 serverName 不同也必须互斥")
            .isTrue();
    }

    @Test
    @DisplayName("同会话重复绑定同一渠道 = 幂等（不新建行、不报冲突）")
    void rebindSameSessionIsIdempotent() {
        // WHY：用户可能重复点「连接」。若报错，体验差；若新建行，
        //   同渠道会有两条绑定 ⇒ 查表时归属不确定。
        service.bind("wechat", "wechat-channel", "sess-a", false);
        int before = rows.size();

        ChannelBindingService.BindResult r = service.bind("wechat", "wechat-channel", "sess-a", false);

        assertThat(r.conflicted()).as("同会话重复绑定不应报冲突").isFalse();
        assertThat(rows.size())
            .as("同会话重复绑定不得新建行（否则同渠道两条 ⇒ 归属不确定）")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("force=true → 抢占：旧绑定消失，新绑定生效")
    void forceTakesOver() {
        // WHY：这是「接管」语义（用户确认后把渠道从 A 转到 B）。
        //   抢占后旧行必须**消失** —— 两条并存会导致查询时归属不确定。
        service.bind("wechat", "wechat-channel", "sess-a", false);

        ChannelBindingService.BindResult r = service.bind("wechat", "wechat-channel", "sess-b", true);

        assertThat(r.conflicted()).as("force 抢占不应报冲突").isFalse();
        assertThat(r.entry().sessionId()).isEqualTo("sess-b");
        assertThat(service.listAll())
            .as("抢占后同渠道必须只剩一条绑定")
            .hasSize(1);
        assertThat(service.listBySession("sess-a"))
            .as("旧会话不应再持有该渠道")
            .isEmpty();
    }

    @Test
    @DisplayName("解绑：幂等 + 归属校验")
    void unbindRespectsOwnership() {
        service.bind("wechat", "wechat-channel", "sess-a", false);

        assertThat(service.unbind("wechat", "sess-b"))
            .as("非归属会话解绑必须被拒绝（否则 A 的渠道能被 B 抢走）")
            .isFalse();
        assertThat(service.findByChannelId("wechat")).isPresent();

        assertThat(service.unbind("wechat", "sess-a"))
            .as("归属会话解绑应成功")
            .isTrue();
        assertThat(service.findByChannelId("wechat"))
            .as("解绑后不应再有绑定")
            .isEmpty();

        assertThat(service.unbind("wechat", "sess-a"))
            .as("重复解绑应幂等（不报错）")
            .isFalse();
    }

    @Test
    @DisplayName("按 server 名查绑定 → 门序[3] 的消费入口")
    void findByServerName() {
        // WHY：门序[3] 在 MCP 连接时触发，那时手里只有 serverName
        //   ⇒ 必须能由 server 名反查归属会话。
        service.bind("wechat", "wechat-channel", "sess-a", false);

        assertThat(service.findByServerName("wechat-channel").orElseThrow().sessionId())
            .isEqualTo("sess-a");
        assertThat(service.findByServerName("not-bound-server"))
            .as("未绑定的 server 必须返回空（⇒ 门序[3] skip，不注册 handler）")
            .isEmpty();
    }
}

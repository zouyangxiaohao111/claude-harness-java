package com.nexusai.domain.im_channel;

import com.nexusai.model.im_channel.ImChatActivation;
import com.nexusai.repository.im_channel.entity.ImChatRecord;
import com.nexusai.repository.im_channel.mapper.ImChatMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [A 预热提示] 渠道激活状态（{@code GET /api/v1/im-chats/activation} 的数据来源）。
 *
 * <p><b>WHY</b>：微信主动发必须带 {@code context_token}（只能从入站消息拿到）。
 * 没有它的聊天，权限通知会被 SKIPPED —— 用户必须能在**绑定之前/之后**看明白这件事，
 * 否则会以为「绑好了就能收到手机通知」。
 *
 * <p>锁死：
 * <ol>
 *   <li>微信无令牌 ⇒ {@code activationRequired=true, activated=false}，hint 非空；</li>
 *   <li>微信有有效令牌 ⇒ activated=true，hint=null；</li>
 *   <li>令牌已过期 ⇒ 按未激活处理（与 {@code replyTokenOf} 同口径）；</li>
 *   <li>飞书/企微 ⇒ {@code activationRequired=false}（不需要对方先说话）；</li>
 *   <li>查询异常 ⇒ 空列表（宁可不提示，也不误标全员未激活）。</li>
 * </ol>
 */
@DisplayName("[A] 渠道激活状态（微信 context_token）")
class ImChatActivationTest {

    private static ImChatRecord rec(String id, String platform, String instanceId,
                                    String replyToken, String expiresAt) {
        ImChatRecord r = new ImChatRecord();
        r.setId(id);
        r.setPlatform(platform);
        r.setInstanceId(instanceId);
        r.setPeerId("peer-" + id);
        r.setChatKind("p2p");
        r.setReplyToken(replyToken);
        r.setReplyTokenExpiresAt(expiresAt);
        return r;
    }

    private static ImChatService service(ImChatMapper mapper) {
        ImChatService s = new ImChatService();
        ReflectionTestUtils.setField(s, "mapper", mapper);
        return s;
    }

    @Test
    @DisplayName("微信无令牌 ⇒ 需要激活且未激活，hint 明确（只提示，不阻止绑定）")
    void wechatWithoutTokenIsInactive() {
        ImChatMapper mapper = mock(ImChatMapper.class);
        ImChatRecord wx = rec("c-wx", "wechat", "i-wx", null, null);
        when(mapper.selectAllOrdered()).thenReturn(List.of(wx));
        when(mapper.selectListWithReplyToken()).thenReturn(List.of());

        List<ImChatActivation> acts = service(mapper).listActivations();

        assertThat(acts).hasSize(1);
        ImChatActivation a = acts.get(0);
        assertThat(a.chatId()).isEqualTo("c-wx");
        assertThat(a.activationRequired()).isTrue();
        assertThat(a.activated()).isFalse();
        assertThat(a.blockedByActivation()).isTrue();
        assertThat(a.hint()).isEqualTo(ImChatActivation.WECHAT_INACTIVE_HINT);
    }

    @Test
    @DisplayName("微信有有效令牌 ⇒ 已激活、无提示")
    void wechatWithTokenIsActive() {
        ImChatMapper mapper = mock(ImChatMapper.class);
        ImChatRecord wx = rec("c-wx", "wechat", "i-wx", "ctx-token", null);
        when(mapper.selectAllOrdered()).thenReturn(List.of(wx));
        when(mapper.selectListWithReplyToken()).thenReturn(List.of(wx));

        ImChatActivation a = service(mapper).listActivations().get(0);

        assertThat(a.activationRequired()).isTrue();
        assertThat(a.activated()).isTrue();
        assertThat(a.blockedByActivation()).isFalse();
        assertThat(a.hint()).isNull();
    }

    @Test
    @DisplayName("令牌已过期 ⇒ 按未激活处理（与 replyTokenOf 同口径）")
    void expiredTokenCountsAsInactive() {
        ImChatMapper mapper = mock(ImChatMapper.class);
        ImChatRecord wx = rec("c-wx", "wechat", "i-wx", "ctx-token", "2000-01-01T00:00:00+08:00");
        when(mapper.selectAllOrdered()).thenReturn(List.of(wx));
        when(mapper.selectListWithReplyToken()).thenReturn(List.of(wx));

        ImChatActivation a = service(mapper).listActivations().get(0);

        assertThat(a.activated()).isFalse();
        assertThat(a.hint()).isEqualTo(ImChatActivation.WECHAT_INACTIVE_HINT);
    }

    @Test
    @DisplayName("飞书/企微不需要激活（activationRequired=false，无 hint）")
    void otherPlatformsNeedNoActivation() {
        ImChatMapper mapper = mock(ImChatMapper.class);
        ImChatRecord fs = rec("c-fs", "feishu", "i-fs", null, null);
        ImChatRecord wc = rec("c-wc", "wecom", "i-wc", null, null);
        when(mapper.selectAllOrdered()).thenReturn(List.of(fs, wc));
        when(mapper.selectListWithReplyToken()).thenReturn(List.of());

        List<ImChatActivation> acts = service(mapper).listActivations();

        assertThat(acts).hasSize(2);
        for (ImChatActivation a : acts) {
            assertThat(a.activationRequired()).isFalse();
            assertThat(a.activated()).isFalse();
            assertThat(a.blockedByActivation()).isFalse();
            assertThat(a.hint()).isNull();
        }
    }

    @Test
    @DisplayName("查询异常 ⇒ 空列表（不把所有人误标为未激活）")
    void queryFailureYieldsEmpty() {
        ImChatMapper mapper = mock(ImChatMapper.class);
        when(mapper.selectAllOrdered()).thenThrow(new RuntimeException("db down"));
        when(mapper.selectListWithReplyToken()).thenThrow(new RuntimeException("db down"));

        assertThat(service(mapper).listActivations()).isEmpty();
    }
}

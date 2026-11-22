package com.nexusai.domain.im_channel;

import com.nexusai.infra.util.CryptoUtil;
import com.nexusai.repository.im_channel.entity.ImChannelInstanceRecord;
import com.nexusai.repository.im_channel.mapper.ImChannelInstanceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建实例 ⇒ 发「自动启动」事件（用户 2026-11-22：连接后默认启动）。
 *
 * <h2>WHY</h2>
 * 创建路径不止一处（手填、扫码、一键创建），但都经过 {@code create}。
 * 事件发在这里才能保证「所有渠道建好即连」，而不是只在某个 UI 分支里补一次。
 */
class ImChannelServiceAutoStartTest {

    @Test
    void 新建实例后发布自动启动事件() {
        ImChannelInstanceMapper mapper = mock(ImChannelInstanceMapper.class);
        CryptoUtil crypto = mock(CryptoUtil.class);
        when(crypto.encrypt(anyString())).thenReturn("enc");
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        ImChannelService svc = new ImChannelService();
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        ReflectionTestUtils.setField(svc, "cryptoUtil", crypto);
        ReflectionTestUtils.setField(svc, "events", events);

        var created = svc.create("feishu", "飞书", "{\"appId\":\"cli_x\"}", null);

        verify(events).publishEvent(any(ImInstanceCreatedEvent.class));
        assertThat(created.id()).startsWith("imc-");
        verify(mapper).insert(any(ImChannelInstanceRecord.class));
    }

    @Test
    void 事件发布失败不影响创建成功() {
        ImChannelInstanceMapper mapper = mock(ImChannelInstanceMapper.class);
        CryptoUtil crypto = mock(CryptoUtil.class);
        when(crypto.encrypt(anyString())).thenReturn("enc");
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("listener boom"))
                .when(events).publishEvent(any(Object.class));

        ImChannelService svc = new ImChannelService();
        ReflectionTestUtils.setField(svc, "mapper", mapper);
        ReflectionTestUtils.setField(svc, "cryptoUtil", crypto);
        ReflectionTestUtils.setField(svc, "events", events);

        // 连不上平台不能把「实例已建好」变成创建失败
        var created = svc.create("feishu", "飞书", "{\"appId\":\"cli_x\"}", null);
        assertThat(created.id()).startsWith("imc-");
    }
}

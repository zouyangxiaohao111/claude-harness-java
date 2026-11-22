package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0.1] 渠道适配器身份口令。
 *
 * <p>本仓把渠道回执面换成 STOMP：{@code fromServer} 是消息体字段，任何能发到该 topic 的客户端
 * 都能自称任意渠道。口令是唯一能证明「你确实被配置成了适配器」的东西 ——
 * 未配置时一律拒绝（fail-closed），否则等于没校验。
 */
@DisplayName("[P0.1] ChannelAdapterAuth · 口令校验")
class ChannelAdapterAuthTest {

    @Test
    @DisplayName("已配置口令：一致才通过（前后空白容忍）；不一致/空/null 拒绝")
    void configuredRequiresExactMatch() {
        ChannelAdapterAuth auth = new ChannelAdapterAuth("s3cret-token");

        assertThat(auth.configured()).isTrue();
        assertThat(auth.verify("s3cret-token")).isTrue();
        assertThat(auth.verify("  s3cret-token  ")).isTrue();
        assertThat(auth.verify("s3cret-toke")).isFalse();
        assertThat(auth.verify("")).isFalse();
        assertThat(auth.verify(null)).isFalse();
    }

    @Test
    @DisplayName("未配置口令：任何回执都拒绝（fail-closed，不能用『没配=放行』）")
    void unconfiguredRejectsEverything() {
        assertThat(new ChannelAdapterAuth("").configured()).isFalse();
        assertThat(new ChannelAdapterAuth("   ").verify("anything")).isFalse();
        assertThat(new ChannelAdapterAuth((String) null).verify(null)).isFalse();
    }
}

package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * idle 订阅：一次性、源退出静默、无默认 TTL。
 */
@DisplayName("PeerIdleSubscriptions · 一次性通知")
class PeerIdleSubscriptionsTest {

    private static final String SRC = "sess-aaaaaaa1";
    private static final String TGT = "sess-bbbbbbb2";

    private static PeerEnvelope envelope(String messageId, boolean notify) {
        return PeerEnvelope.create(messageId, "alpha [aaaaaa]", SRC, "alpha",
            PermissionMode.DEFAULT, List.of(), 1L, "s", notify);
    }

    @Test
    @DisplayName("subscribe 需要 notifyWhenIdle=true，且每个事件只触发一次")
    void subscribeAndFireOnce() {
        PeerIdleSubscriptions subs = new PeerIdleSubscriptions();
        List<PeerIdleSubscriptions.IdleNotice> sink = new ArrayList<>();
        subs.setNoticeSink(sink::add);

        assertThat(subs.subscribe(envelope("m1", false), TGT, 5L)).isFalse();
        assertThat(subs.subscribe(envelope("m1", true), TGT, 5L)).isTrue();
        assertThat(subs.size()).isEqualTo(1);

        List<PeerIdleSubscriptions.IdleNotice> first = subs.onTargetIdle(TGT, 5L);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).kind()).isEqualTo(PeerIdleSubscriptions.Kind.IDLE);
        assertThat(first.get(0).render()).startsWith("[Cross-session idle notice]")
            .contains("not a user instruction");
        assertThat(subs.onTargetIdle(TGT, 5L)).as("同一事件不得重复触发").isEmpty();
        assertThat(sink).hasSize(1);
        assertThat(subs.size()).isZero();
    }

    @Test
    @DisplayName("代次不符不触发")
    void generationMustMatch() {
        PeerIdleSubscriptions subs = new PeerIdleSubscriptions();
        subs.subscribe(envelope("m1", true), TGT, 5L);
        assertThat(subs.onTargetExit(TGT, 4L)).isEmpty();
        assertThat(subs.size()).isEqualTo(1);
        assertThat(subs.onTargetExit(TGT, 5L)).hasSize(1);
    }

    @Test
    @DisplayName("源退出静默清理，不向已退出源发通知")
    void sourceExitIsSilent() {
        PeerIdleSubscriptions subs = new PeerIdleSubscriptions();
        List<PeerIdleSubscriptions.IdleNotice> sink = new ArrayList<>();
        subs.setNoticeSink(sink::add);
        subs.subscribe(envelope("m1", true), TGT, 5L);

        assertThat(subs.onSourceExit(SRC, 1L)).isEmpty();
        assertThat(subs.size()).isZero();
        assertThat(sink).isEmpty();
    }

    @Test
    @DisplayName("sweepExpired 用调用方传入的 ttl，过期只发一次 EXPIRED")
    void sweepExpired() {
        PeerIdleSubscriptions subs = new PeerIdleSubscriptions();
        subs.subscribe(envelope("m1", true), TGT, 5L);
        Instant now = Instant.now().plus(Duration.ofHours(1));

        List<PeerIdleSubscriptions.IdleNotice> expired = subs.sweepExpired(now, Duration.ofMinutes(1));
        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).kind()).isEqualTo(PeerIdleSubscriptions.Kind.EXPIRED);
        assertThat(subs.sweepExpired(now, Duration.ofMinutes(1))).isEmpty();
    }
}

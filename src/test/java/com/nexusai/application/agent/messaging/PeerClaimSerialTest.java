package com.nexusai.application.agent.messaging;

import com.nexusai.application.agent.LlmAgentLoop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [sendmessage-cross-session · B] 起轮 claim 串行性窄测试（纯静态方法，无 Spring / 无 DB）。
 *
 * <p><b>验证意图</b>（与 CC {@code QueryGuard} 对应）：
 * <ol>
 *   <li>running 中的会话拿不到 claim（{@link LlmAgentLoop#tryClaim} 返回 null）；</li>
 *   <li>同一时刻只有一个 claim 持有者（并发 16 线程只成功 1 个）；</li>
 *   <li>{@link LlmAgentLoop#releaseClaim} 只删同代次 —— 旧 finally 不会删掉新持有者的 claim；</li>
 *   <li>claim 持有期间 {@code isSessionActive} 为 true（空闲消费者据此跳过，不并行起轮）。</li>
 * </ol>
 *
 * <p><b>变异敏感点</b>：把 {@code tryClaim} 里的 {@code isSessionRunning} 判据去掉后，
 * {@link #runningSessionRejectsConcurrentClaims()} 会由「全部拒绝」变成「至少一个成功」⇒ 红。
 */
class PeerClaimSerialTest {

    private static String freshSession() {
        return "sess-b2-claim-" + System.nanoTime();
    }

    @Test
    @DisplayName("running 中的会话：tryClaim 返回 null，releaseClaim(null) 无害")
    void runningSessionRejectsClaim() {
        String session = freshSession();
        LlmAgentLoop.markRunning(session);
        try {
            assertThat(LlmAgentLoop.tryClaim(session)).isNull();
            LlmAgentLoop.releaseClaim(null);   // no-op，不抛
        } finally {
            LlmAgentLoop.markIdle(session);
        }
        LlmAgentLoop.SessionClaim claim = LlmAgentLoop.tryClaim(session);
        try {
            assertThat(claim).isNotNull();
            assertThat(claim.sessionId()).isEqualTo(session);
        } finally {
            LlmAgentLoop.releaseClaim(claim);
        }
        assertThat(LlmAgentLoop.isSessionClaimed(session)).isFalse();
    }

    @Test
    @DisplayName("并发 16 线程抢同一会话：恰好 1 个成功，其余全 null")
    void concurrentClaimsOnlyOneWins() throws Exception {
        String session = freshSession();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        List<LlmAgentLoop.SessionClaim> claims = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    LlmAgentLoop.SessionClaim c = LlmAgentLoop.tryClaim(session);
                    if (c != null) {
                        wins.incrementAndGet();
                        claims.add(c);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "peer-claim-" + i);
            t.start();
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(claims).hasSize(1);
        assertThat(wins.get()).isEqualTo(1);
        for (LlmAgentLoop.SessionClaim c : claims) {
            LlmAgentLoop.releaseClaim(c);
        }
    }

    @Test
    @DisplayName("running 中并发抢 claim：全部失败（去掉 running 判据即变红）")
    void runningSessionRejectsConcurrentClaims() throws Exception {
        String session = freshSession();
        LlmAgentLoop.markRunning(session);   // 模拟另一入口已起轮（无 claim）
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        List<LlmAgentLoop.SessionClaim> claims = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        LlmAgentLoop.SessionClaim c = LlmAgentLoop.tryClaim(session);
                        if (c != null) {
                            wins.incrementAndGet();
                            claims.add(c);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }, "peer-claim-running-" + i);
                t.start();
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(wins.get()).as("running 中任何线程都不得取得 claim").isZero();
        } finally {
            for (LlmAgentLoop.SessionClaim c : claims) {
                LlmAgentLoop.releaseClaim(c);
            }
            LlmAgentLoop.markIdle(session);
        }
    }

    @Test
    @DisplayName("releaseClaim 只删同代次：旧凭证不能删新持有者的 claim")
    void releaseOnlyRemovesMatchingGeneration() {
        String session = freshSession();
        LlmAgentLoop.SessionClaim first = LlmAgentLoop.tryClaim(session);
        assertThat(first).isNotNull();
        LlmAgentLoop.releaseClaim(first);

        LlmAgentLoop.SessionClaim second = LlmAgentLoop.tryClaim(session);
        assertThat(second).isNotNull();
        try {
            // 旧凭证的迟到 finally（重放 first）
            LlmAgentLoop.releaseClaim(first);
            assertThat(LlmAgentLoop.isSessionClaimed(session))
                .as("旧代次释放不得清掉新持有者").isTrue();
            assertThat(second.generation()).isNotEqualTo(first.generation());
            assertThat(LlmAgentLoop.isSessionActive(session)).isTrue();
        } finally {
            LlmAgentLoop.releaseClaim(second);
        }
        assertThat(LlmAgentLoop.isSessionClaimed(session)).isFalse();
    }
}

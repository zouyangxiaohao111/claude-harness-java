package com.nexusai.application.agent.messaging;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/**
 * 发送侧 pacer · 逐行对齐 CC 2.1.328 {@code ge()}（{@code claude.exe} 偏移 324042423 /
 * 324044952，Python 按字节读出）。
 *
 * <h2>CC 原文</h2>
 * <pre>
 * function ge(e,n=Date.now){let r=new Map;
 *   function i(m,a){let{bucketCapacity:o,refillPerSecond:u,maxTrackedSenders:A}=e(),k=!1,
 *     S=G(r,m,A,()=&gt;(k=!0,{tokens:o,updatedAt:a,sentInBurst:0,burstStartedAt:a}),
 *       (h)=&gt;U(h.tokens,h.updatedAt,a,o,u)&gt;=o);
 *     if(!k){S.tokens=U(S.tokens,S.updatedAt,a,o,u),S.updatedAt=a;
 *       let h=o/Math.max(u,0.000000001)*1000;
 *       if(S.tokens&gt;=o||a-S.burstStartedAt&gt;h)S.sentInBurst=0,S.burstStartedAt=a}
 *     return S}
 *   function d(m){let a=i(m,n());if(!B(a.tokens))return{ok:!1,sentInBurst:a.sentInBurst};
 *     a.tokens-=1,a.sentInBurst+=1;let o=a,u=!1;
 *     return{ok:!0,refund:()=&gt;{if(u)return;u=!0,o.tokens=Math.min(e().bucketCapacity,o.tokens+1),
 *       o.sentInBurst=Math.max(0,o.sentInBurst-1)}}}
 *   ...return{reserve:d,credit:f,debit:g}}
 * </pre>
 *
 * <p><b>维度</b>：发送进程 × 目标 socket/endpoint（CC {@code JS().reserve(BN(e)??e)}）。
 * 本仓无 UDS socket，等价键 = {@code targetSessionId + "#" + endpointEpoch}（重注册换代次 = 新桶）。
 *
 * <p><b>与接收侧桶的关系</b>：同一组参数（30 / 0.5/s）但<b>独立</b>—— 发送侧在写 socket 前
 * 预留（失败立即拒，errorClass {@code sender_paced}），接收侧在入队前判定（失败异步 dropped）。
 */
@Component
public class PeerSendPacer {
    /** 预留结果（{@code ok=false} 时 {@code sentInBurst} 供失败文案使用）。 */
    public static final class Reservation {
        private final boolean ok;
        private final int sentInBurst;
        private final Runnable refund;

        private Reservation(boolean ok, int sentInBurst, Runnable refund) {
            this.ok = ok;
            this.sentInBurst = sentInBurst;
            this.refund = refund;
        }

        public boolean ok() {
            return ok;
        }

        public int sentInBurst() {
            return sentInBurst;
        }

        /** 归还一个令牌（发送实际失败时调用；幂等）。 */
        public void refund() {
            if (refund != null) {
                refund.run();
            }
        }
    }

    private static final class PacerState {
        double tokens;
        long updatedAt;
        int sentInBurst;
        long burstStartedAt;
    }

    private final LongSupplier clock;
    private final int bucketCapacity;
    private final double refillPerSecond;
    private final int maxTrackedTargets;
    private final LinkedHashMap<String, PacerState> targets = new LinkedHashMap<>(16, 0.75f, true);

    /** 容器用默认构造（真实时钟）。 */
    public PeerSendPacer() {
        this(System::currentTimeMillis);
    }

    public PeerSendPacer(LongSupplier clock) {
        this(clock, PeerRateLimits.BUCKET_CAPACITY, PeerRateLimits.REFILL_PER_SECOND,
            PeerRateLimits.MAX_TRACKED_SENDERS);
    }

    public PeerSendPacer(LongSupplier clock, int bucketCapacity, double refillPerSecond,
                         int maxTrackedTargets) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.bucketCapacity = bucketCapacity;
        this.refillPerSecond = refillPerSecond;
        this.maxTrackedTargets = maxTrackedTargets;
    }

    /**
     * 预留一个发送名额（CC {@code reserve}）。
     *
     * @param targetKey 目标键（{@code sessionId#epoch}）
     * @return 预留结果；失败时调用方必须拒发并回报 {@link PeerRateLimits#pacedMessage(int)}
     */
    public synchronized Reservation reserve(String targetKey) {
        if (targetKey == null) {
            return new Reservation(true, 0, null);
        }
        long now = clock.getAsLong();
        PacerState state = stateFor(targetKey, now);
        if (state.tokens < 1) {
            return new Reservation(false, state.sentInBurst, null);
        }
        state.tokens -= 1;
        state.sentInBurst += 1;
        return new Reservation(true, state.sentInBurst, () -> {
            synchronized (PeerSendPacer.this) {
                state.tokens = Math.min(bucketCapacity, state.tokens + 1);
                state.sentInBurst = Math.max(0, state.sentInBurst - 1);
            }
        });
    }

    /** 归还一个令牌（CC {@code credit}）。 */
    public synchronized void credit(String targetKey) {
        if (targetKey == null) {
            return;
        }
        PacerState state = stateFor(targetKey, clock.getAsLong());
        state.tokens = Math.min(bucketCapacity, state.tokens + 1);
        state.sentInBurst = Math.max(0, state.sentInBurst - 1);
    }

    /** 额外扣一个令牌（CC {@code debit}；用于「已发出但未预留」的补偿路径）。 */
    public synchronized void debit(String targetKey) {
        if (targetKey == null) {
            return;
        }
        PacerState state = stateFor(targetKey, clock.getAsLong());
        state.tokens = Math.max(0, state.tokens - 1);
        state.sentInBurst += 1;
    }

    private PacerState stateFor(String targetKey, long now) {
        PacerState existing = targets.get(targetKey);
        if (existing != null) {
            double refilled = refill(existing.tokens, existing.updatedAt, now);
            existing.tokens = refilled;
            existing.updatedAt = now;
            long fullRefillMs = (long) (bucketCapacity / Math.max(refillPerSecond, 0.000000001) * 1000);
            if (existing.tokens >= bucketCapacity || now - existing.burstStartedAt > fullRefillMs) {
                existing.sentInBurst = 0;
                existing.burstStartedAt = now;
            }
            return existing;
        }
        while (targets.size() >= Math.max(1, maxTrackedTargets)) {
            // 只逐出「已回满」的目标（CC 的满桶判定谓词），否则逐出最旧。
            String victim = null;
            for (var entry : targets.entrySet()) {
                PacerState state = entry.getValue();
                if (refill(state.tokens, state.updatedAt, now) >= bucketCapacity) {
                    victim = entry.getKey();
                    break;
                }
            }
            if (victim == null) {
                victim = targets.keySet().iterator().next();
            }
            targets.remove(victim);
        }
        PacerState created = new PacerState();
        created.tokens = bucketCapacity;
        created.updatedAt = now;
        created.sentInBurst = 0;
        created.burstStartedAt = now;
        targets.put(targetKey, created);
        return created;
    }

    private double refill(double tokens, long updatedAt, long now) {
        double elapsedSeconds = Math.max(0L, now - updatedAt) / 1000.0;
        return Math.min(bucketCapacity, tokens + elapsedSeconds * refillPerSecond);
    }

    /** 本轮已发条数（诊断/测试；未知目标 → 0）。 */
    public synchronized int sentInBurst(String targetKey) {
        PacerState state = targets.get(targetKey);
        return state == null ? 0 : state.sentInBurst;
    }

    /** 已跟踪目标数（诊断/测试）。 */
    public synchronized int trackedTargetCount() {
        return targets.size();
    }
}

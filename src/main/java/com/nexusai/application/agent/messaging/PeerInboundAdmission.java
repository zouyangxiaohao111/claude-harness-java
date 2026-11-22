package com.nexusai.application.agent.messaging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 接收侧准入（入队之前）· 逐行对齐 CC 2.1.328 {@code Vii(...).admit()}（{@code claude.exe}
 * 偏移 324034003 / 324034215，Python 按字节读出原文）。
 *
 * <h2>CC 原文（令牌桶 + 去重 + LRU）</h2>
 * <pre>
 * function U(e,n,r,i,d){let f=Math.max(0,r-n)/1000;return Math.min(i,e+f*d)}
 * function B(e){return e>=1}
 * function G(e,n,r,i,d=()=>!0){let f=e.get(n);
 *   if(f!==void 0)return e.delete(n),e.set(n,f),f;
 *   while(e.size>=Math.max(1,r)){let m;for(let[a,o]of e)if(d(o)){m=a;break}
 *     if(m??=e.keys().next().value,m===void 0)break;e.delete(m)}
 *   let g=i();return e.set(n,g),g}
 * function f(a,o){return G(d,a,o.maxTrackedSenders,
 *   ()=&gt;({tokens:o.bucketCapacity,lastRefill:o.now(),lastBody:void 0,lastBodyAt:0}))}
 * function g(a,o){let u=i();
 *   if(a!==void 0&amp;&amp;a.length&gt;u.maxChainLength)return{admitted:!1,reason:"hop-runaway"};
 *   if(Q(a,o)&gt;=u.maxSelfHops)return{admitted:!1,reason:"hop-loop"};return}
 * function m(a){let o=i(),u=o.now(),A=g(a.hopChain,a.ownTokens);if(A)return A;
 *   let k=f(a.senderKey,o);
 *   if(k.lastBody!==void 0&amp;&amp;k.lastBody===a.body&amp;&amp;u-k.lastBodyAt&lt;o.dedupWindowMs)
 *     return{admitted:!1,reason:"duplicate"};
 *   if(k.tokens=U(k.tokens,k.lastRefill,u,o.bucketCapacity,o.refillPerSecond),k.lastRefill=u,
 *      !B(k.tokens))return{admitted:!1,reason:"rate-limited"};
 *   return k.tokens-=1,k.lastBody=a.body,k.lastBodyAt=u,{admitted:!0}}
 * </pre>
 *
 * <h2>CC 原文（队列上限与判定顺序 {@code Es()}，偏移 325455000）</h2>
 * <pre>
 * let ya=Jo.admissionExempt==="resurrected", ol=Ps.hostInjected===!0,
 *     Rd=!ol&amp;&amp;HSr(Ps),
 *     qs=!ya&amp;&amp;!ol&amp;&amp;ds()&gt;=ede().maxQueuedPeerMessages;
 * if(qs&amp;&amp;tr==="caller-retries")return{admitted:!1,reason:"queue-full"};
 * if(!ya&amp;&amp;!Rd){let Wu=yo.checkHopChain(Ps.hopChain,M8o()); ... }
 * if(!ya&amp;&amp;Rd){let Wu=yo.admit({senderKey:Ps.from!=="unknown"?`from:${Ps.from}`:`pid:${Ps.verifiedPeerPid}`,
 *     body:Wu,hopChain:Ps.hopChain,ownTokens:M8o()}); ...}
 * if(qs){ ... return{admitted:!1,reason:"queue-full"}}
 * </pre>
 * 即默认（{@code tr="coalesced"}）的判定顺序 = <b>hop 链 → 同文去重 → 令牌桶 → 队容</b>；
 * 队容只在「已入队 peer 项 ≥ 50」时拒<b>新</b>消息，不淘汰旧项。
 *
 * <p><b>维度</b>：令牌桶与去重按「接收会话 × 发送方」—— 本类实例 = 一个接收会话的准入器
 * （CC 每进程一个 {@code Vii}，senderKey 里带 {@code from:<addr>} / {@code pid:<pid>}；
 * 本仓按 target session 各持一个实例，语义等价且不会让不同接收方互相扣令牌）。
 *
 * <p><b>时钟注入</b>：{@link LongSupplier} 毫秒时间源（测试用可变时钟，不需要 sleep）。
 */
public class PeerInboundAdmission {

    /** 准入结论（{@code admitted=false} 时 reason 非 null）。 */
    public record Decision(boolean admitted, PeerRateLimits.Reason reason) {

        static final Decision ADMITTED = new Decision(true, null);

        static Decision reject(PeerRateLimits.Reason reason) {
            return new Decision(false, reason);
        }
    }

    /** 准入输入（全部来自服务端数据；⛔ 不取模型正文自报）。 */
    public record Input(String senderKey, String body, List<String> hopChain,
                        Set<String> ownHopTokens, int queuedPeerMessages, boolean callerRetries) {

        /** 简化构造：hop 已由路由层判过（hopChain=null ⇒ 本轮不做 hop 判定）。 */
        public Input(String senderKey, String body, int queuedPeerMessages) {
            this(senderKey, body, null, Set.of(), queuedPeerMessages, false);
        }
    }

    /** 单个发送方的桶状态（CC {@code {tokens,lastRefill,lastBody,lastBodyAt}}）。 */
    private static final class SenderState {
        double tokens;
        long lastRefill;
        String lastBody;
        long lastBodyAt;
    }

    private final LongSupplier clock;
    /** LRU：{@code accessOrder=true} + 头部为最旧（CC {@code G()} 命中时 delete+set 刷新）。 */
    private final LinkedHashMap<String, SenderState> senders =
        new LinkedHashMap<>(16, 0.75f, true);

    private final int bucketCapacity;
    private final double refillPerSecond;
    private final long dedupWindowMs;
    private final int maxTrackedSenders;
    private final int maxQueuedPeerMessages;

    /** 默认参数（= CC {@code q} + {@code maxQueuedPeerMessages:50}）。 */
    public PeerInboundAdmission(LongSupplier clock) {
        this(clock, PeerRateLimits.BUCKET_CAPACITY, PeerRateLimits.REFILL_PER_SECOND,
            PeerRateLimits.DEDUP_WINDOW_MS, PeerRateLimits.MAX_TRACKED_SENDERS,
            PeerRateLimits.MAX_QUEUED_PEER_MESSAGES);
    }

    /** 全参构造（测试/变异用；生产一律默认参数）。 */
    public PeerInboundAdmission(LongSupplier clock, int bucketCapacity, double refillPerSecond,
                                long dedupWindowMs, int maxTrackedSenders, int maxQueuedPeerMessages) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.bucketCapacity = bucketCapacity;
        this.refillPerSecond = refillPerSecond;
        this.dedupWindowMs = dedupWindowMs;
        this.maxTrackedSenders = maxTrackedSenders;
        this.maxQueuedPeerMessages = maxQueuedPeerMessages;
    }

    /**
     * 准入判定（CC {@code Es()} 的 coalesced 口径）。
     *
     * @param input 请求（senderKey = {@code from:<addr>} 或 {@code pid:<pid>}）
     * @return 结论（{@code admitted=false} 时调用方负责「拒新 + 回执告知发送方 dropped」）
     */
    public synchronized Decision admit(Input input) {
        if (input == null) {
            return Decision.ADMITTED;
        }
        long now = clock.getAsLong();
        // ① 队容先算（CC 在 hop/桶之前计算 qs，但只在 caller-retries 时才提前短接）
        boolean queueFull = input.queuedPeerMessages() >= maxQueuedPeerMessages;
        if (queueFull && input.callerRetries()) {
            return Decision.reject(PeerRateLimits.Reason.QUEUE_FULL);
        }
        // ② hop 链（调用方未判时；路由层已判 ⇒ 传 null 跳过，避免双重判定）
        if (input.hopChain() != null) {
            PeerRateLimits.Reason hop = checkHopChain(input.hopChain(), input.ownHopTokens());
            if (hop != null) {
                return Decision.reject(hop);
            }
        }
        // ③ 同文去重（先于令牌桶：CC 的 duplicate 分支在 refill 之前）
        if (input.senderKey() != null) {
            SenderState state = stateFor(input.senderKey(), now);
            if (state.lastBody != null && state.lastBody.equals(input.body())
                && now - state.lastBodyAt < dedupWindowMs) {
                return Decision.reject(PeerRateLimits.Reason.DUPLICATE);
            }
            // ④ 令牌桶回填 + 扣减
            state.tokens = refill(state.tokens, state.lastRefill, now);
            state.lastRefill = now;
            if (state.tokens < 1) {
                return Decision.reject(PeerRateLimits.Reason.RATE_LIMITED);
            }
            state.tokens -= 1;
            state.lastBody = input.body();
            state.lastBodyAt = now;
        }
        // ⑤ 队容（拒新、不淘汰旧）
        if (queueFull) {
            return Decision.reject(PeerRateLimits.Reason.QUEUE_FULL);
        }
        return Decision.ADMITTED;
    }

    /**
     * hop 链判定（CC {@code g(a,o)}）：链长 &gt; maxChainLength ⇒ hop-runaway；
     * 自身 token 出现 ≥ maxSelfHops ⇒ hop-loop。
     *
     * @param hopChain 链（null/空 = 通过）
     * @param ownTokens 本会话 hop token 集合
     * @return 拒绝原因；null = 通过
     */
    public static PeerRateLimits.Reason checkHopChain(List<String> hopChain, Set<String> ownTokens) {
        if (hopChain != null && hopChain.size() > PeerEnvelope.MAX_CHAIN_LENGTH) {
            return PeerRateLimits.Reason.HOP_RUNAWAY;
        }
        if (hopChain != null && ownTokens != null && !ownTokens.isEmpty()) {
            int hits = 0;
            for (String hop : hopChain) {
                if (ownTokens.contains(hop)) {
                    hits++;
                }
            }
            if (hits >= PeerEnvelope.MAX_SELF_HOPS) {
                return PeerRateLimits.Reason.HOP_LOOP;
            }
        }
        return null;
    }

    /** CC {@code U()}：线性回填，封顶 capacity。 */
    private double refill(double tokens, long lastRefill, long now) {
        double elapsedSeconds = Math.max(0L, now - lastRefill) / 1000.0;
        return Math.min(bucketCapacity, tokens + elapsedSeconds * refillPerSecond);
    }

    /**
     * 取/建发送方状态（CC {@code G()}）：命中刷新 LRU；超上限时逐出「已满桶」的最旧项，
     * 找不到满桶则逐出最旧项。
     */
    private SenderState stateFor(String senderKey, long now) {
        SenderState existing = senders.get(senderKey);
        if (existing != null) {
            return existing;
        }
        while (senders.size() >= Math.max(1, maxTrackedSenders)) {
            String victim = null;
            long bestNow = now;
            for (Map.Entry<String, SenderState> entry : senders.entrySet()) {
                SenderState state = entry.getValue();
                if (refill(state.tokens, state.lastRefill, bestNow) >= bucketCapacity) {
                    victim = entry.getKey();
                    break;
                }
            }
            if (victim == null) {
                victim = senders.keySet().iterator().next();
            }
            senders.remove(victim);
        }
        SenderState created = new SenderState();
        created.tokens = bucketCapacity;
        created.lastRefill = now;
        senders.put(senderKey, created);
        return created;
    }

    /** 已跟踪发送方数（CC {@code trackedSenderCount()}）。 */
    public synchronized int trackedSenderCount() {
        return senders.size();
    }

    /** 清空状态（CC {@code Qo()} 重建准入器的等价物；测试用）。 */
    public synchronized void reset() {
        senders.clear();
    }

    /** 当前某发送方的令牌数（诊断/测试；未知发送方 → -1）。 */
    public synchronized double tokensOf(String senderKey) {
        SenderState state = senders.get(senderKey);
        return state == null ? -1 : state.tokens;
    }

    /** 已跟踪发送方键快照（诊断/测试）。 */
    public synchronized List<String> trackedSenders() {
        return new ArrayList<>(senders.keySet());
    }
}

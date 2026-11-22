package com.nexusai.application.agent.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动循环刹车（aVn / lVn / uea）· 逐行对齐 CC 2.1.328
 * （{@code claude.exe} 偏移 336251938 / 336253847 / 336254506 / 336254843，Python 按字节读出）。
 *
 * <h2>CC 原文（状态 + 复位 + starter 记录）</h2>
 * <pre>
 * class l{starter;localSends=0;bridgeSends=0}
 * function dMs(t){let e=t.get(l);e.localSends=0,e.bridgeSends=0,Bo().receipts.wokeForHold.clear()}
 * function pea(t,e){try{if(e.some((o)=&gt;t.options.isNonInteractiveSession?g(o):
 *     o.isMeta!==!0&amp;&amp;o.foreignWord===void 0&amp;&amp;E_e(o)))dMs(t.toolState)}catch(o){...}}
 * function mea(t,e){let o=e?.kind==="peer"?e:void 0;
 *   if(!(o?.senderTaskId!==void 0||o?.selfSent===!0&amp;&amp;o.verifiedLocal!==!0||
 *        e?.kind==="observer"||A1(e)))t.get(l).starter=o}
 * </pre>
 *
 * <h2>CC 原文（刹车本体）</h2>
 * <pre>
 * function y(t){return{kind:"paused",message:`Not delivered: this session keeps answering other
 *   sessions, which looks like an automatic loop. Sending to ${t} is paused until your user's
 *   next prompt starts a turn in this session.`}}
 * function aVn(t){let e=t.toolState.get(l);
 *   if(e.starter&amp;&amp;vXr(e.starter)){
 *     if(e.localSends++,e.localSends&gt;ede().maxLocalRelays&amp;&amp;uCn(e.starter))
 *       return y("sessions on this machine")}
 *   return{kind:"admitted",peer:T(t)}}
 * function lVn(t){let e=T(t),o=t.toolState.get(l);
 *   if(o.starter&amp;&amp;vXr(o.starter)){
 *     if(o.bridgeSends++,uCn(o.starter))
 *       return o.bridgeSends&gt;ede().maxSelfHops?y("Remote Control and cloud sessions")
 *         :{kind:"admitted",hopChain:e&amp;&amp;(e.hopChain??[])}}
 *   return{kind:"admitted",hopChain:e&amp;&amp;$Nn(e)}}
 * function vXr(e){return e.verifiedLocal===!0&amp;&amp;!ede().countLocalHops}
 * function uCn(e){let{maxChainLength:n,maxSelfHops:r}=ede(),i=$Nn(e);
 *   return i.length&gt;n||Q(i,M8o())&gt;=r}
 * </pre>
 *
 * <h2>CC 原文（Desktop 转发预算）</h2>
 * <pre>
 * class c{promptIndexSeen=-1;forwardsSincePrompt=0}
 * function uea(t){let e=FTt(t,F3e);if(!e)return{kind:"unavailable"};
 *   let o=t.toolState.get(c),r=Fte();
 *   if(r!==o.promptIndexSeen)o.promptIndexSeen=r,o.forwardsSincePrompt=0;
 *   if(o.forwardsSincePrompt++,o.forwardsSincePrompt&gt;ede().maxDesktopForwards)
 *     return{kind:"loop-paused",forwards:o.forwardsSincePrompt-1};return{tool:e}}
 * // 文案（偏移 176793848）：
 * `Not delivered: this session has already messaged Claude Desktop sessions N times since your
 *  user last typed here, which looks like sessions messaging each other automatically.
 *  Paused until your user's next message in this session.`
 * </pre>
 *
 * <p><b>触发条件（与 CC 逐条同形）</b>：
 * <ol>
 *   <li>必须有 starter（本会话收到的第一条合格来信 origin）；</li>
 *   <li>{@code verifiedLocal === true && !countLocalHops}（本仓进程内跨会话恒为 verifiedLocal）；</li>
 *   <li>本地发送：{@code localSends > maxLocalRelays} <b>且</b> starter 链已带循环信号
 *       （链 &gt; 28 段 或 自身 token ≥ 10）—— 两个条件缺一不可（正常长协作不误杀）；</li>
 *   <li>bridge 发送：{@code bridgeSends > maxSelfHops(10)} 且同一循环信号。</li>
 * </ol>
 * <b>触发后行为</b>：暂停（{@code Not delivered: ...}），不降级、不问用户。
 * <b>复位</b>：真实用户输入（{@code isMeta!==true && foreignWord===undefined && E_e(message)}）
 * 清零 localSends/bridgeSends。
 */
@Component
public class PeerLoopBrake {

    /** 刹车结论：ADMITTED（放行）/ PAUSED（暂停直到用户下次输入）。 */
    public record Outcome(Kind kind, String message, List<String> hopChain) {

        static Outcome admitted(List<String> hopChain) {
            return new Outcome(Kind.ADMITTED, null, hopChain);
        }

        static Outcome paused(String message) {
            return new Outcome(Kind.PAUSED, message, null);
        }
    }

    public enum Kind { ADMITTED, PAUSED }

    /**
     * 循环 starter（CC {@code starter} · 首个合格来信 origin）。
     *
     * @param fromSessionId 来信来源会话
     * @param verifiedLocal 已证同机（CC {@code verifiedLocal===true}）
     * @param selfSent      是否自己发给自己
     * @param hopChain      来信 hop 链（判循环信号用）
     */
    public record Starter(String fromSessionId, boolean verifiedLocal, boolean selfSent,
                          List<String> hopChain) {}

    /** Desktop 转发结论。 */
    public record DesktopOutcome(Kind kind, int forwards, String message) {}

    private static final class BrakeState {
        Starter starter;
        int localSends;
        int bridgeSends;
        int promptIndexSeen = -1;
        int forwardsSincePrompt;
    }

    /** 部署配置（{@code nexusai.peer.max-local-relays} 等）；空白 = 用缺省/回退规则。 */
    @Value("${nexusai.peer.max-local-relays:}")
    private String maxLocalRelaysRaw;

    @Value("${nexusai.peer.max-desktop-forwards:}")
    private String maxDesktopForwardsRaw;

    @Value("${nexusai.peer.count-local-hops:false}")
    private boolean countLocalHops;

    private final Map<String, BrakeState> states = new ConcurrentHashMap<>();

    // ─────────────────── 接线/测试用 setter ───────────────────

    public void setMaxLocalRelaysRaw(String raw) {
        this.maxLocalRelaysRaw = raw;
    }

    public void setMaxDesktopForwardsRaw(String raw) {
        this.maxDesktopForwardsRaw = raw;
    }

    public void setCountLocalHops(boolean countLocalHops) {
        this.countLocalHops = countLocalHops;
    }

    /** 生效的本地转发预算（缺失 100000；非法 20）。 */
    public int maxLocalRelays() {
        return PeerRateLimits.resolveForwardBudget(maxLocalRelaysRaw);
    }

    /** 生效的 Desktop 转发预算（缺失 100000；非法 20）。 */
    public int maxDesktopForwards() {
        return PeerRateLimits.resolveForwardBudget(maxDesktopForwardsRaw);
    }

    // ─────────────────── starter / 复位 ───────────────────

    /**
     * 记录 starter（CC {@code mea}）：只接受合格来信 —— 非子代理、非「自己发给自己且未证同机」、
     * 非 observer、非 session-task 通知。已存在 starter 时不覆盖（CC 首次记录即固定）。
     */
    public void onPeerOrigin(String receiverSessionId, String fromSessionId, boolean selfSent,
                            boolean verifiedLocal, boolean subagentSender, boolean sessionTask,
                            List<String> hopChain) {
        if (receiverSessionId == null || fromSessionId == null) {
            return;
        }
        // 与 CC 的排除条件逐条对应（senderTaskId / selfSent&&!verifiedLocal / observer / A1()）。
        if (subagentSender || sessionTask) {
            return;
        }
        if (selfSent && !verifiedLocal) {
            return;
        }
        BrakeState state = states.computeIfAbsent(receiverSessionId, key -> new BrakeState());
        synchronized (state) {
            if (state.starter == null) {
                state.starter = new Starter(fromSessionId, verifiedLocal, selfSent,
                    hopChain == null ? List.of() : List.copyOf(hopChain));
            }
        }
    }

    /** 真实用户输入 → 清零计数器（CC {@code dMs}）。 */
    public void onUserPrompt(String sessionId) {
        if (sessionId == null) {
            return;
        }
        BrakeState state = states.computeIfAbsent(sessionId, key -> new BrakeState());
        synchronized (state) {
            state.localSends = 0;
            state.bridgeSends = 0;
            state.promptIndexSeen = -1;
            state.forwardsSincePrompt = 0;
        }
    }

    // ─────────────────── 刹车判定 ───────────────────

    /** 本地发送前（CC {@code aVn}）：计数 +1，超预算且链带循环信号 ⇒ 暂停。 */
    public Outcome beginLocalSend(String sessionId) {
        BrakeState state = stateOf(sessionId);
        synchronized (state) {
            if (state.starter != null && brakesEnforced(state.starter)) {
                state.localSends++;
                if (state.localSends > maxLocalRelays()
                    && hasLoopSignal(state.starter, sessionId)) {
                    return Outcome.paused(pauseMessage("sessions on this machine"));
                }
            }
            return Outcome.admitted(null);
        }
    }

    /** bridge/远端发送前（CC {@code lVn}）：超 maxSelfHops 且带循环信号才暂停，否则放行并带旧链。 */
    public Outcome beginBridgeSend(String sessionId, List<String> hopChain) {
        BrakeState state = stateOf(sessionId);
        synchronized (state) {
            if (state.starter != null && brakesEnforced(state.starter)) {
                state.bridgeSends++;
                if (hasLoopSignal(state.starter, sessionId)) {
                    if (state.bridgeSends > PeerEnvelope.MAX_SELF_HOPS) {
                        return Outcome.paused(pauseMessage("Remote Control and cloud sessions"));
                    }
                    return Outcome.admitted(hopChain == null ? List.of() : List.copyOf(hopChain));
                }
            }
            return Outcome.admitted(hopChain == null ? List.of() : List.copyOf(hopChain));
        }
    }

    /**
     * Desktop 转发前（CC {@code uea}）：同 prompt 索引内累计，超预算 ⇒ loop-paused。
     *
     * @param sessionId   会话
     * @param promptIndex 当前用户输入序号（与上次不同 ⇒ 计数器归零）
     */
    public DesktopOutcome beginDesktopForward(String sessionId, int promptIndex) {
        BrakeState state = stateOf(sessionId);
        synchronized (state) {
            if (promptIndex != state.promptIndexSeen) {
                state.promptIndexSeen = promptIndex;
                state.forwardsSincePrompt = 0;
            }
            state.forwardsSincePrompt++;
            if (state.forwardsSincePrompt > maxDesktopForwards()) {
                return new DesktopOutcome(Kind.PAUSED, state.forwardsSincePrompt - 1,
                    "Not delivered: this session has already messaged Claude Desktop sessions "
                        + (state.forwardsSincePrompt - 1)
                        + " times since your user last typed here, which looks like sessions "
                        + "messaging each other automatically. Paused until your user's next "
                        + "message in this session.");
            }
            return new DesktopOutcome(Kind.ADMITTED, state.forwardsSincePrompt, null);
        }
    }

    /** 本地已发条数（诊断/测试）。 */
    public int localSends(String sessionId) {
        BrakeState state = states.get(sessionId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.localSends;
        }
    }

    /** bridge 已发条数（诊断/测试）。 */
    public int bridgeSends(String sessionId) {
        BrakeState state = states.get(sessionId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.bridgeSends;
        }
    }

    /** starter（诊断/测试；null = 未记录）。 */
    public Starter starter(String sessionId) {
        BrakeState state = states.get(sessionId);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.starter;
        }
    }

    private BrakeState stateOf(String sessionId) {
        return states.computeIfAbsent(sessionId == null ? "" : sessionId, key -> new BrakeState());
    }

    /** CC {@code vXr(starter)}：已证同机且未启用 countLocalHops。 */
    private boolean brakesEnforced(Starter starter) {
        return starter.verifiedLocal() && !countLocalHops;
    }

    /** CC {@code uCn(starter)}：链 &gt; maxChainLength 或自身 token 出现 ≥ maxSelfHops。 */
    private boolean hasLoopSignal(Starter starter, String selfSessionId) {
        List<String> chain = starter.hopChain();
        if (chain.size() > PeerEnvelope.MAX_CHAIN_LENGTH) {
            return true;
        }
        String own = PeerEnvelope.hopToken(selfSessionId);
        int hits = 0;
        for (String hop : chain) {
            if (own.equals(hop)) {
                hits++;
            }
        }
        return hits >= PeerEnvelope.MAX_SELF_HOPS;
    }

    /** CC {@code y(t)} 文案。 */
    private static String pauseMessage(String target) {
        return "Not delivered: this session keeps answering other sessions, which looks like an "
            + "automatic loop. Sending to " + target + " is paused until your user's next prompt "
            + "starts a turn in this session.";
    }
}

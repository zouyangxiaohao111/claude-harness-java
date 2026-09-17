package com.nexusai.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STOMP 投递可观测（OBS2）· 订阅登记 + 出站帧计数。
 *
 * <p><b>WHY（2026-09-17 事故复盘）</b>：用户在一个会话里遇「前端整屏停止更新」——CSS 动画在动、JS 在发
 * HTTP、运行中 F5 无变化，结束后 F5 却能看到完整内容 ⇒ 渲染链是好的，坏的是「运行中内容走 STOMP 推」
 * 的那条通道（落库走 HTTP）。事后翻日志，两个关键问题<b>一个都答不出来</b>：
 * <ol>
 *   <li>「那一刻这个会话还有没有订阅者？」—— STOMP simple broker 的 topic 非持久，帧推给零订阅者时
 *       <b>静默丢弃</b>（不抛异常、不留日志），事后无从分辨「没推」与「没人收」；</li>
 *   <li>「帧有没有真写出去？」—— 此前日志最多能证明「调用了 {@code convertAndSend}」（见
 *       {@code LlmAgentLoop} 的 usage/chunk 留痕），但那只是把消息投进出站通道，写没写出、写给谁，
 *       全无痕迹。</li>
 * </ol>
 * 本类分别用<b>订阅事件监听</b>与<b>出站通道拦截器</b>把这两问答变成日志里可查的一行。
 *
 * <p><b>两处信号</b>：
 * <ul>
 *   <li>{@link SessionSubscribeEvent}/{@link SessionUnsubscribeEvent}/{@link SessionDisconnectEvent}
 *       → 维护 {@code simpSessionId → 已订阅 destination 集合}（订阅可能密集，同一
 *       {@code sid+destination} 的重复 SUBSCRIBE <b>只在首次打一行</b>，之后折叠进计数，在周期汇总里
 *       以 {@code ×N} 呈现）；</li>
 *   <li>{@link #preSend}（挂在 {@code clientOutboundChannel}）→ 按 {@code simpSessionId} 累计出站帧数与
 *       最后放行时刻（口径见 {@link OutboundDeliveryStats}）；{@link #afterSendCompletion} 另记写出异常。</li>
 * </ul>
 *
 * <p><b>周期汇总</b>：{@link #logSummary()} 每 {@link #SUMMARY_PERIOD_MS} 打一行
 * {@code [ws-deliver]} 汇总，把两个信号并排 —— 于是「有订阅者但帧不涨」= 后端没推，
 * 「有帧但前端 framesIn 不涨」= 通道死了，两种情形一眼分开。
 *
 * <p><b>日志量取舍（本类自身绝不能变成噪声源/故障源）</b>：
 * <ul>
 *   <li>订阅/断开事件本身稀疏 → 首次订阅立即打；重复订阅折叠计数不打。</li>
 *   <li>出站帧极高（流式 chunk 每帧一条）→ <b>不逐帧打</b>，只累加，由周期汇总每 10s 出一行。</li>
 *   <li>无任何登记会话（应用空闲、无 WS 客户端）→ 周期汇总<b>不打</b>。</li>
 *   <li>所有回调体全包 {@code try/catch}：诊断是旁路，⛔ 绝不允许它影响投递路径。</li>
 * </ul>
 */
@Component
public class WebSocketDeliveryDiagnostics implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketDeliveryDiagnostics.class);

    /** 出站汇总打印周期（毫秒）。 */
    public static final long SUMMARY_PERIOD_MS = 10_000L;

    /** 出站帧计数（纯逻辑，见其类 Javadoc 的口径说明）。 */
    private final OutboundDeliveryStats outbound = new OutboundDeliveryStats();

    /**
     * 订阅登记：{@code simpSessionId → (destination → 该 sid 对该 destination 收到的 SUBSCRIBE 帧次数)}。
     *
     * <p>用计数而非布尔：STOMP 客户端重复 SUBSCRIBE 同一 destination 是合法但可疑的行为（正常订阅一次），
     * 计数能区分「正常一次」与「重连风暴里反复订」，而重复次数只在周期汇总里以 {@code ×N} 呈现，
     * 不逐条刷日志。<b>会话断开即整条移除</b> —— 否则每次重连换一个 simpSessionId，长跑应用无界增长。
     */
    private final Map<String, Map<String, Integer>> subscriptions = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 一、订阅/断开事件：答「那一刻还有没有订阅者」
    // ------------------------------------------------------------------

    /** 订阅（含重新订阅）→ 登记；首次登记打一行，重复只计数。 */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        try {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
            String sid = headers.getSessionId();
            String destination = headers.getDestination();
            if (sid == null || destination == null) {
                return; // 无会话/无 destination → 静默跳过（不是错误）
            }
            Map<String, Integer> dests = subscriptions.computeIfAbsent(sid, k -> new ConcurrentHashMap<>());
            // merge 返回「合并后的新值」→ 1 即该 sid+destination 的首次订阅
            int count = dests.merge(destination, 1, Integer::sum);
            if (count == 1) {
                log.info("[ws-deliver] 订阅 simp={} dest={} 该会话订阅数={} 全局订阅会话数={}",
                    sid, destination, dests.size(), subscriptions.size());
            }
            // count > 1：同一 sid+destination 重复订阅 → 不打日志，折叠进 dests 计数（周期汇总出 ×N）
        } catch (Exception e) {
            log.warn("[ws-deliver] 订阅事件处理异常（忽略，不影响订阅）: {}", e.toString());
        }
    }

    /** 退订 → 移除；仅当该 destination 计数归零才真正注销。 */
    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        try {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
            String sid = headers.getSessionId();
            String destination = headers.getDestination();
            if (sid == null || destination == null) {
                return;
            }
            Map<String, Integer> dests = subscriptions.get(sid);
            if (dests == null) {
                return;
            }
            dests.remove(destination);
            if (dests.isEmpty()) {
                subscriptions.remove(sid);
            }
            log.info("[ws-deliver] 退订 simp={} dest={} 该会话剩余订阅={} 全局订阅会话数={}",
                sid, destination, dests.isEmpty() ? "[]" : dests.keySet(), subscriptions.size());
        } catch (Exception e) {
            log.warn("[ws-deliver] 退订事件处理异常（忽略，不影响退订）: {}", e.toString());
        }
    }

    /**
     * 断连 → 清空该会话的订阅登记与出站计数。
     *
     * <p>这是回答「那一刻还有没有订阅者」最要紧的一条：半开/被强杀时，前端可能还显示「已连接」，
     * 而后端这一行才是真相。
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        try {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
            String sid = headers.getSessionId();
            if (sid == null) {
                return;
            }
            Map<String, Integer> dropped = subscriptions.remove(sid);
            OutboundDeliveryStats.SessionCount sent = outbound.get(sid);
            long frames = sent == null ? 0 : sent.framesSent();
            long lastSendAt = sent == null ? 0 : sent.lastSendAt();
            outbound.removeSession(sid);
            log.info("[ws-deliver] 断连 simp={} 丢失订阅={} 该连接累计出站帧={} 最后放行={} 全局订阅会话数={}",
                sid, dropped == null ? Set.of() : dropped.keySet(), frames,
                lastSendAt == 0 ? "从未" : lastSendAt + "（epoch ms）", subscriptions.size());
        } catch (Exception e) {
            log.warn("[ws-deliver] 断连事件处理异常（忽略，不影响断连）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 二、出站通道拦截：答「帧有没有真写出去」
    // ------------------------------------------------------------------

    /**
     * 帧被放行进出站通道 → 记账。
     *
     * <p>口径：这是「preSend 时点」，即帧已被投进出站通道；真正写到 socket 由传输层随后完成
     * （成不成看 {@link #afterSendCompletion}）。所以本计数涨 = <b>后端侧在推</b>，涨不动 = 问题不在前端。
     *
     * <p>无 {@code simpSessionId} 的（广播/队列类非会话帧）→ 静默跳过，⛔ 不 NPE、不计数。
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            String sid = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            if (sid != null) {
                outbound.recordSend(sid, System.currentTimeMillis());
            }
        } catch (Exception e) {
            // 诊断绝不打断投递：这一句 catch 是承重的，不是装饰
            log.warn("[ws-deliver] 出站帧计数异常（忽略，不影响投递）: {}", e.toString());
        }
        return message;
    }

    /** 写出失败（传输层报错）→ 记账 + WARN（失败稀疏，值得即时一行）。 */
    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        if (ex == null) {
            return;
        }
        try {
            String sid = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
            if (sid != null) {
                outbound.recordFailure(sid, System.currentTimeMillis());
            }
            log.warn("[ws-deliver] 出站写出失败 simp={} sent={} 异常={}", sid, sent, ex.toString());
        } catch (Exception e) {
            log.warn("[ws-deliver] 写出失败记账异常（忽略）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 三、周期汇总：把「还有没有订阅者」与「帧涨没涨」并排放在一行
    // ------------------------------------------------------------------

    /**
     * 每 {@link #SUMMARY_PERIOD_MS} 打一行出站汇总。
     *
     * <p>无任何登记会话 / 无出站活动 → 不打（应用空闲时这条通道必须彻底安静）。
     *
     * <p>用 {@code @Scheduled}（{@code AsyncConfig} 已 {@code @EnableScheduling}）而非自建线程：
     * 本类只是读几个计数拼串，不需要专属调度资源，也不该再引入一个执行器与 {@code cronExecutor} /
     * 心跳调度争用。
     */
    @Scheduled(fixedDelay = SUMMARY_PERIOD_MS)
    public void logSummary() {
        try {
            boolean hasSubscribers = !subscriptions.isEmpty();
            String outboundSummary = outbound.drainSummary(System.currentTimeMillis());
            if (!hasSubscribers && outboundSummary.isEmpty()) {
                return; // 空闲：不打
            }
            log.info("[ws-deliver] 出站汇总 订阅会话={} 明细{} | 出站帧={}",
                subscriptions.size(), describeSubscriptions(), outboundSummary.isEmpty() ? "（本窗口无出站帧）" : outboundSummary);
        } catch (Exception e) {
            log.warn("[ws-deliver] 出站汇总异常（忽略）: {}", e.toString());
        }
    }

    /** 订阅明细串：{@code sid=[dest×N, dest2]}；稳定排序便于比对两次汇总。 */
    private String describeSubscriptions() {
        if (subscriptions.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String sid : new TreeSet<>(subscriptions.keySet())) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            Map<String, Integer> dests = subscriptions.get(sid);
            sb.append(sid).append('=');
            List<String> parts = new ArrayList<>();
            for (String dest : new TreeMap<>(dests == null ? Map.of() : dests).keySet()) {
                Integer n = dests == null ? null : dests.get(dest);
                parts.add(n != null && n > 1 ? dest + "×" + n : dest);
            }
            sb.append('[').append(String.join(", ", parts)).append(']');
        }
        return sb.append('}').toString();
    }
}

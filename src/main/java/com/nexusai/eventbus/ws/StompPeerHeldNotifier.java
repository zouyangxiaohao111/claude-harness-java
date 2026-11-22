package com.nexusai.eventbus.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * STOMP 实现 · 把 {@link PeerInboxHoldEvent} 发到
 * {@code /topic/sessions/{targetSessionId}/peer-inbox}。
 *
 * <p><b>失败不伪装</b>：模板缺失 / convertAndSend 抛异常 → 日志 warn + 返回 false。
 * 调用方（{@code DbHeldPeerStore}）只记日志，不回滚已落库行；前端靠列表/轮询兜底。
 *
 * <p>simple broker 非持久：零订阅者时 convertAndSend 也「成功」——这是 STOMP 语义，
 * 不代表用户已看到；本类只承诺「已交给通道」。
 */
@Component
public class StompPeerHeldNotifier implements PeerHeldNotifier {

    private static final Logger log = LoggerFactory.getLogger(StompPeerHeldNotifier.class);

    private final ObjectProvider<SimpMessagingTemplate> templates;

    public StompPeerHeldNotifier(ObjectProvider<SimpMessagingTemplate> templates) {
        this.templates = templates;
    }

    @Override
    public boolean publish(PeerInboxHoldEvent event) {
        if (event == null || event.getTargetSessionId() == null
            || event.getTargetSessionId().isBlank() || event.getMessageId() == null) {
            return false;
        }
        SimpMessagingTemplate template = templates == null ? null : templates.getIfAvailable();
        if (template == null) {
            log.warn("PEER INBOX hold 事件未发布：STOMP 模板不可用 target={} messageId={}（DB 已落库，前端靠列表兜底）",
                event.getTargetSessionId(), event.getMessageId());
            return false;
        }
        String destination = "/topic/sessions/" + event.getTargetSessionId() + "/peer-inbox";
        try {
            template.convertAndSend(destination, event);
            if (log.isDebugEnabled()) {
                log.debug("PEER INBOX hold 事件已发布 target={} messageId={}",
                    event.getTargetSessionId(), event.getMessageId());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("PEER INBOX hold 事件发布失败（不伪装已发布）target={} messageId={} err={}",
                event.getTargetSessionId(), event.getMessageId(), e.toString());
            return false;
        }
    }
}

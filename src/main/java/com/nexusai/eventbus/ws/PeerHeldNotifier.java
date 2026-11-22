package com.nexusai.eventbus.ws;

/**
 * 待收件实时通知出口（hold 落库成功后调用）。
 *
 * <p>接口与实现解耦：持久化层（{@code DbHeldPeerStore}）只依赖本接口，
 * 由 {@link StompPeerHeldNotifier} 负责 STOMP 投递；测试可替换为记录型实现。
 *
 * <p><b>返回语义</b>：{@code true} 仅当本进程确实把事件交给了出站通道（convertAndSend 未抛异常）；
 * 无模板/抛异常一律 {@code false}。⛔ 不得在失败时返回 true 伪装已发布 ——
 * DB 已提交只代表「持久化成功」，不代表「前端已收到」。
 */
@FunctionalInterface
public interface PeerHeldNotifier {

    /**
     * 发布一条 hold 事件（best-effort；调用方不得因 false 回滚已落库的行）。
     *
     * @param event 事件（非 null）
     * @return true = 已交给出站通道；false = 无通道或投递异常（未伪装成功）
     */
    boolean publish(PeerInboxHoldEvent event);
}

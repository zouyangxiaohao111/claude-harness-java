package com.nexusai.domain.im_channel.inbound;

/**
 * 平台入站监听器（每个渠道实例一个）。
 *
 * <h2>为什么每个实现只关心「把平台帧变成 {@link InboundMessage}」</h2>
 * 长连接/长轮询的生命周期管理（重连、退避、心跳、线程）由各实现自己负责，
 * 但**消息怎么处理**（分流、落库、进 agent）一律交给上层
 * {@link ImInboundRouter}。这样加平台时不用重写一遍分流逻辑，改分流时也不用碰三个平台。
 *
 * <h2>线程模型约定</h2>
 * <ul>
 *   <li>{@link #start} **不能阻塞**（内部起线程）；</li>
 *   <li>{@link InboundHandler#onMessage} 可能被**平台线程**调用 —— 实现方要么自己切线程，
 *       要么保证处理足够快（Router 内部已做轻量处理 + 异步入队）。</li>
 *   <li>{@link #stop} 要能打断阻塞调用（长轮询/WS read），且幂等。</li>
 * </ul>
 */
public interface ImInboundListener extends AutoCloseable {

    /** 平台标识（与 {@code ImChannelService.PLATFORMS} 对齐）。 */
    String platform();

    /**
     * 启动监听。
     *
     * @param instanceId 渠道实例 id（用于取凭证、日志定位、游标归属）
     * @param handler    收到归一消息时回调
     */
    void start(String instanceId, InboundHandler handler);

    /** 停止监听（幂等；必须能打断阻塞中的长轮询/WS 读）。 */
    void stop();

    /** 当前是否在运行（供状态面板显示）。 */
    boolean isRunning();

    @Override
    default void close() {
        stop();
    }

    /** 入站回调。 */
    @FunctionalInterface
    interface InboundHandler {
        /**
         * 收到一条归一后的消息。
         *
         * <p>⚠️ 实现方**不要**在这里抛异常（会打断平台的收包循环）——
         * 有异常自己 catch + 记日志。
         */
        void onMessage(InboundMessage message);
    }
}

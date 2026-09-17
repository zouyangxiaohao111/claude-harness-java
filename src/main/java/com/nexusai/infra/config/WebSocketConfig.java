package com.nexusai.infra.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket 配置 · STOMP over WebSocket
 *
 * <p>端点：
 * <ul>
 *   <li>{@code /ws}        — 原生 WebSocket（主）</li>
 *   <li>{@code /ws-sockjs} — SockJS 回退（兼容老浏览器 / 代理）</li>
 * </ul>
 *
 * <p>消息前缀：
 * <ul>
 *   <li>客户端订阅（server → client）：{@code /topic}（simple broker）</li>
 *   <li>客户端发送（client → server）：{@code /app}</li>
 *   <li>点对点：{@code /user}（user destination，前缀已注册但 Phase 5 暂未使用）</li>
 * </ul>
 *
 * <p>v1 本地单用户：用 {@code setAllowedOriginPatterns("*")} 放行所有 Origin。
 * v2 接入鉴权后应改为允许的域名白名单。
 *
 * @see <a href="/Users/zhengwei/Desktop/开发/nexusai-ui/docs/api/websocket.md">WebSocket 协议文档</a>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP 心跳间隔（毫秒）· {@code [server→client, client→server]} 两向同为 10s。
     *
     * <p>取 10s 的理由：半开连接（TCP 存活但帧不通）要在「几十秒内」被发现，10s 的心跳缺失判定
     * 窗口足够短且远大于正常网络抖动；不必更小（每次心跳都是一帧流量，越小越吵）。
     */
    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    /**
     * 心跳定时任务用的 {@link TaskScheduler}。
     *
     * <p>取 Spring 自带的 {@code messageBrokerTaskScheduler}（{@code AbstractMessageBrokerConfiguration}
     * 注册，线程名前缀 {@code MessageBroker-}，池大小 = CPU 核数）：
     * <ul>
     *   <li>⛔ 不新建 bean —— 同包 {@code AsyncConfig} 已有 {@code @EnableScheduling} / {@code cronExecutor}，
     *       项目还引了 quartz；再加一个 {@link TaskScheduler} 型 bean 会让「按类型注入 TaskScheduler」变成
     *       多候选，波及 Spring 自己的 {@code userRegistryMessageHandler} 等按类型取调度器的装配点。</li>
     *   <li>这个 bean 由 {@code @EnableWebSocketMessageBroker} 无条件下注册，是本类可用的确定前提
     *       （Spring 自己取它时同样带 {@code @Qualifier("messageBrokerTaskScheduler")}）。</li>
     * </ul>
     * ⛔ 必须按名字限定：{@code @EnableScheduling} 存在时 Boot 还可能有 {@code taskScheduler} bean，
     * 按类型注入会 NoUniqueBeanDefinitionException。
     *
     * <p>⛔ <b>必须是 {@link ObjectProvider}（延迟解析），不能直接注入 TaskScheduler</b>：本类是被
     * {@code @EnableWebSocketMessageBroker} 引入的 {@code DelegatingWebSocketMessageBrokerConfiguration}
     * 通过 {@code setConfigurers} 收集的 configurer 之一，而 {@code messageBrokerTaskScheduler} 这个 bean
     * 的工厂方法恰恰声明在<b>那个配置类</b>身上 —— 构造函数里直接要它就成环
     * （webSocketConfig → messageBrokerTaskScheduler → DelegatingConfig → setConfigurers → webSocketConfig），
     * Spring Boot 默认 {@code allow-circular-references=false}，启动即
     * {@code BeanCurrentlyInCreationException}（本批实测踩到，非推测）。
     * 延迟到 {@link #configureMessageBroker} 里取：那时本类已构造完成，环自然断开。
     */
    private final ObjectProvider<TaskScheduler> heartbeatTaskSchedulerProvider;

    /** 出站投递观测（订阅登记 + 出站帧计数）· 挂到 clientOutboundChannel 上。 */
    private final WebSocketDeliveryDiagnostics deliveryDiagnostics;

    public WebSocketConfig(@Qualifier("messageBrokerTaskScheduler") ObjectProvider<TaskScheduler> heartbeatTaskSchedulerProvider,
                           WebSocketDeliveryDiagnostics deliveryDiagnostics) {
        this.heartbeatTaskSchedulerProvider = heartbeatTaskSchedulerProvider;
        this.deliveryDiagnostics = deliveryDiagnostics;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 取不到调度器就启动期直接抛（⛔ 不静默降级）：心跳配不上正是本批要消灭的失效模式
        // ——「看着配了、实际一帧不发」比「启动就炸」危险得多。
        TaskScheduler heartbeatTaskScheduler = heartbeatTaskSchedulerProvider.getIfAvailable();
        if (heartbeatTaskScheduler == null) {
            throw new IllegalStateException(
                "[OBS2] 找不到 messageBrokerTaskScheduler bean —— STOMP 心跳无法启用。"
                    + "该 bean 由 @EnableWebSocketMessageBroker 提供，缺失说明 Broker 装配被改写；"
                    + "⛔ 不要在此退化为「不配心跳」：半开连接将再次不可察（2026-09-17 事故根因）。");
        }
        // server → client 订阅前缀
        config.enableSimpleBroker("/topic", "/queue")
            // [OBS2 · 半开连接可察] 打开 STOMP 心跳 —— 事故根因修复。
            //   WHY（2026-09-17 事故）：用户遇「前端整屏停止更新」——CSS 动画在动、JS 在发 HTTP、
            //   运行中 F5 无变化，但那一轮结束后 F5 就能看到完整内容 ⇒ 渲染链是好的，坏的是
            //   「运行中内容走 STOMP 推」的那条通道（落库走 HTTP，故重拉可见）。而这条通道断了
            //   前端毫无察觉：本类此前只 enableSimpleBroker 而没配心跳 ⇒ CONNECTED 帧不带
            //   heart-beat 头 ⇒ @stomp/stompjs 压根不武装心跳（stomp-handler 见不到 heart-beat 头
            //   直接 return）⇒ 无 PING/PONG、无 idle 判定、状态栏仍显示绿色「已连接」。
            //   Spring 的 STOMP 心跳是 TCP 之上的应用层活性探测：server 按 [0] 周期向客户端发 EOL
            //   心跳帧，并按 [1] 周期要求客户端回帧，超时即判死并关闭会话 → 半开连接转为「断连」，
            //   前端 onWebSocketClose 才会触发（flush + 置断连标志 + 重连补偿）。
            // ⛔ 必须同时给 TaskScheduler：Spring 6.2 的 SimpleBrokerMessageHandler.startInternal() 里
            //   `heartbeatValue != null && taskScheduler == null` 会直接抛
            //   IllegalArgumentException("Heartbeat values configured but no TaskScheduler provided")（启动即炸，
            //   不是静默失效 —— 本批实测）。反向实验：只配 interval 不给调度器 → 启动失败。
            //   注意反过来那一半才是真陷阱：heartbeatValue == null（即本批改动之前的现状）不会报任何错，
            //   只是 CONNECTED 帧不带 heart-beat 头 —— 这正是 2026-09-17 事故里最难查的地方。
            .setHeartbeatValue(new long[] {HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
            .setTaskScheduler(heartbeatTaskScheduler);
        // client → server 发送前缀
        config.setApplicationDestinationPrefixes("/app");
        // 点对点前缀（@SendToUser 用到，Phase 5 暂未使用）
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 主端点 — 原生 WebSocket
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*");

        // SockJS 回退端点 — 兼容老浏览器 / 不支持原生 WS 的代理
        registry.addEndpoint("/ws-sockjs")
            .setAllowedOriginPatterns("*")
            .withSockJS();
    }

    /**
     * [打字机卡死修复 · WS 传输阈值] 覆写 WebSocketTransportRegistration。
     *
     * <p>WHY（2026-09-04 根因）：后端每条 SSE chunk 立即 convertAndSend 一条 STOMP 帧，DeepSeek
     * thinking 长 reasoning 一轮可产生数百上千帧；前端渲染慢 → TCP 接收窗口填满 → 写线程阻塞超
     * <b>Spring 默认 sendTimeLimit=10s</b> 被强杀（日志 "Terminating ... Send time 10144ms exceeded
     * the allowed limit 10000"）→ STOMP 断连 → message.complete 推给零订阅者静默丢弃 → 打字机永久卡死。
     *
     * <p>本方法把传输终止阈值放宽为护栏（不消除触发源，须配 LlmAgentLoop chunk 节流）：
     * <ul>
     *   <li>sendTimeLimit 10s → 60s（单次发送允许阻塞时长）</li>
     *   <li>sendBufferSizeLimit 512KB → 2MB（单会话 outbound 缓冲上限）</li>
     *   <li>timeToFirstMessage 默认 30s → 120s（首次消息超时，防止建立后迟迟不写被误杀）</li>
     * </ul>
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
            .setSendTimeLimit(60_000)
            .setSendBufferSizeLimit(2 * 1024 * 1024)
            .setTimeToFirstMessage(120_000);
    }

    /**
     * [打字机卡死修复 · outbound 线程池扩容] 覆写 configureClientOutboundChannel。
     *
     * <p>WHY（2026-09-04）：Spring simple broker 默认 clientOutboundChannel core=max=<b>2</b>。
     * convertAndSend 是同步写 —— 单个慢消费者（浏览器渲染不过来）阻塞写线程时，2 个线程都可能被占，
     * 造成<b>跨会话全局 stall</b>。扩容到 8/16 缓解「一个慢会话拖垮所有会话」；queueCapacity 有界
     * （默认无界 Integer.MAX_VALUE 会无界堆积内存）。仍须配 chunk 节流治本 —— 线程数不消除单线程
     * 阻塞在慢消费者上的事实，只是留出余量不拖垮他人。
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
            .corePoolSize(8)
            .maxPoolSize(16)
            .queueCapacity(8192);
        // [OBS2 · 投递可观测] 出站帧留痕：把「调用了 convertAndSend」升级为「帧确实进了这个会话的
        //   出站通道」。事故复盘里「那一刻这个会话还有没有订阅者 / 帧有没有真写出去」当时无从回答，
        //   因为 STOMP simple broker 的 topic 非持久，帧推给零订阅者是静默丢弃（无异常、无日志）。
        //   见 WebSocketDeliveryDiagnostics。
        registration.interceptors(deliveryDiagnostics);
    }
}
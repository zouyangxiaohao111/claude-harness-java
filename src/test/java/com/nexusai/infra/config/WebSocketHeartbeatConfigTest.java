package com.nexusai.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebSocketConfig} 的 STOMP 心跳与出站拦截器装配验证（OBS2）。
 *
 * <p><b>WHY（规则九：验证意图 · 这是本次事故根因的回归闸）</b>：2026-09-17「前端整屏停止更新」的根因是
 * <b>半开连接不可察</b> —— 后端此前 {@code enableSimpleBroker} 没配心跳 ⇒ CONNECTED 帧不带 {@code heart-beat}
 * 头 ⇒ {@code @stomp/stompjs} 不武装心跳 ⇒ 无 PING/PONG、无 idle 判定、前端状态栏照样绿色「已连接」，
 * 而运行中的内容走这条已死的 STOMP 通道（落库走 HTTP，所以结束后 F5 才看得到）。
 *
 * <p>本测试断言的是「那条修复确实生效」的两块承重前提，而<b>不是</b>「调用了某个方法」：
 * <ol>
 *   <li><b>心跳值非零且等于约定间隔</b>：Spring 只在 {@code heartbeatValue != null} 时才把 heart-beat 头写进
 *       CONNECTED 帧；配成 0 或不配 ⇒ 前端依旧不武装心跳，事故原样复现。故直接读 broker 实例上的生效值
 *       （不是读常量——读常量只能证明「我写了个常量」）。</li>
 *   <li><b>TaskScheduler 非空</b>：{@code SimpleBrokerMessageHandler.startInternal()} 里
 *       {@code heartbeatValue == null || taskScheduler == null} 时<b>心跳定时任务根本不排</b> ——
 *       只配 interval 不给调度器就是「看着配了、实际一帧都不发」。这一条是本类最容易被漏掉的静默失效，
 *       单独一个断言钉住它。</li>
 * </ol>
 * 外加一条装配断言：{@link WebSocketDeliveryDiagnostics} 必须真的挂在 {@code clientOutboundChannel} 上，
 * 否则「帧有没有写出去」仍然无痕（拦截器忘了注册不会报错，只会静默不生效）。
 *
 * <p><b>为什么用轻量上下文而不是全量 {@code @SpringBootTest}</b>：沿用同包
 * {@link BrowserWebSocketConfigContextTest} 的既定做法 —— 只装配 WebSocket 层（无 DB / Flyway），
 * 精确验证配置本身，且不受既有迁移状态影响。
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {
    WebSocketConfig.class,
    WebSocketDeliveryDiagnostics.class
})
@DisplayName("OBS2 · WebSocketConfig STOMP 心跳与出站拦截器装配")
class WebSocketHeartbeatConfigTest {

    @Autowired
    private ApplicationContext ctx;

    /** 取生效的 simple broker 实例（心跳值/调度器都读它，不读常量）。 */
    private SimpleBrokerMessageHandler broker() {
        Map<String, SimpleBrokerMessageHandler> beans = ctx.getBeansOfType(SimpleBrokerMessageHandler.class);
        assertThat(beans)
            .as("simple broker 必须恰好注册一个（enableSimpleBroker 的产物）")
            .hasSize(1);
        return beans.values().iterator().next();
    }

    @Test
    @DisplayName("心跳间隔已生效（非零 · 双向 10s）—— 去掉即复现『半开连接不可察』")
    void heartbeatValueConfigured() {
        long[] heartbeat = broker().getHeartbeatValue();

        assertThat(heartbeat)
            .as("heartbeatValue 为 null ⇒ 不写 heart-beat 头 ⇒ 前端不武装心跳，事故原样复现")
            .isNotNull();
        assertThat(heartbeat)
            .as("STOMP 心跳数组是 [server→client, client→server] 两向")
            .hasSize(2);
        assertThat(heartbeat)
            .as("两向都必须是 10s 且非零：置 0 等价于关闭心跳（Spring 语义 0 = 不发）")
            .containsExactly(WebSocketConfig.HEARTBEAT_INTERVAL_MS, WebSocketConfig.HEARTBEAT_INTERVAL_MS)
            .doesNotContain(0L);
        assertThat(WebSocketConfig.HEARTBEAT_INTERVAL_MS)
            .as("间隔取 10s（对『几十秒内发现半开连接』足够，且远大于正常网络抖动）")
            .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("心跳调度器已装配（缺它时 Spring 6.2 启动即抛，但本断言把『必须给』钉在配置里）")
    void heartbeatTaskSchedulerConfigured() {
        // 实测更正（别再照着「静默失效」的说法写代码）：Spring 6.2 的
        // SimpleBrokerMessageHandler.startInternal() 在 heartbeatValue != null 且 taskScheduler == null 时
        // 直接抛 IllegalArgumentException("Heartbeat values configured but no TaskScheduler provided")
        // —— 所以缺调度器是「启动即炸」而非静默不发。本断言仍保留：它把「必须显式给调度器」钉在本次配置上
        // （变异实验：去掉 setTaskScheduler → 上下文装配失败 → 本类全红）。
        assertThat(broker().getTaskScheduler())
            .as("心跳定时任务跑在这个调度器上；没有它 broker 会拒绝启动")
            .isNotNull();
    }

    @Test
    @DisplayName("出站投递观测拦截器已挂到 clientOutboundChannel（忘挂不报错，只会静默无痕）")
    void outboundInterceptorRegistered() {
        AbstractMessageChannel outbound = ctx.getBean("clientOutboundChannel", AbstractMessageChannel.class);

        assertThat(outbound.getInterceptors())
            .as("clientOutboundChannel 上必须挂着 WebSocketDeliveryDiagnostics —— 否则『帧有没有真写出去』"
                + "依旧答不出来（本次事故复盘答不出的正是这一问）")
            .anyMatch(i -> i instanceof WebSocketDeliveryDiagnostics);
    }
}

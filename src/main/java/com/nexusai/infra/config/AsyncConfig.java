package com.nexusai.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置 · 启用 {@code @Async} 注解支持
 *
 * <p>设计要点：
 * <ul>
 *   <li>ChatService.processUserMessage 标 {@code @Async("chatExecutor")}，
 *       HTTP 请求立刻返回（202），后台跑 LLM 流</li>
 *   <li>core 可配置（{@code nexusai.async.chat-core-pool-size}，默认 4，<b>最小 4</b>——联调教训
 *       2026-08-24：权限请求无限等待会占住 core 线程，并发低于 4 时新消息排队不执行）；max=16 /
 *       queue=100 —— 桌面应用同时聊天请求一般 &lt; 5，排队 100 已经远超实际峰值</li>
 *   <li>线程名前缀 "chat-async-" 便于日志/jstack 排查</li>
 *   <li>CallerRunsPolicy：队列满时让调用方线程跑（避免任务丢失，但会阻塞 HTTP）</li>
 * </ul>
 *
 * <p>v2 升级到 JDK 25 后，可考虑换 {@code Executors.newVirtualThreadPerTaskExecutor()}
 * （API 一行切换）—— 配置接口不动。
 *
 * <p>v3（CRON-D2）：加 {@code @EnableScheduling}（使能 {@code CronIdleExecutor} 的
 * {@code @Scheduled} 轮询）+ 专用 {@code cronExecutor}（core=1 串行，避免同会话并发 agent_loop）。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "chatExecutor")
    public Executor chatExecutor() {
        // [2026-08-24 虚拟线程改造 · 用户拍板] JDK 25 虚拟线程（每任务独立虚拟线程）。
        //   根治：权限弹窗等无限等待不再占池线程（原 ThreadPoolTaskExecutor core=4 被多个
        //   会话的权限弹窗 run 占满 → 新会话 processUserMessage 排队卡住，39 个未完成 run 实锤）。
        //   虚拟线程无池上限，每 run 独立，无限等待不阻塞其他会话（会话隔离真正成立）。
        //   适合 IO 密集型（LLM 调用/DB/STOMP）。ThreadLocal（MDC/sessionId）虚拟线程支持。
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * CRON-D2: cron idle 专用执行器 · core=1 串行（同会话不并发 agent_loop，RUNNING_SESSIONS 计数配合）。
     * 线程名前缀 "cron-idle-" 便于日志排查。
     */
    @Bean(name = "cronExecutor")
    public Executor cronExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cron-idle-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
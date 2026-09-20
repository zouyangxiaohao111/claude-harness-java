package com.nexusai.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

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
 * {@code @Scheduled} 轮询）+ 专用 {@code cronExecutor}（原 core=1 串行）。
 *
 * <p>v4（[M1] 2026-09-18 子代理投递修复）：{@code cronExecutor} 亦改虚拟线程 —— 原 core=1/max=1
 * 是一条可被永久占住的 OS 线程（真因 L1）；「同会话不并发」改由 {@code CronIdleExecutor} 出队点
 * 同步占位 + {@code LlmAgentLoop} per-session dispatching 保留态（CC QueryGuard）守住，不再靠池串行。
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
     * CRON-D2: cron idle 专用执行器。
     *
     * <p><b>[M1 · 2026-09-18 子代理投递修复 · 用户裁定] 由 core=1/max=1 串行改为虚拟线程</b>，
     * 与同文件 {@link #chatExecutor()}（2026-08-24 同款失效类已拍板改虚拟线程）同款。
     *
     * <p><b>WHY（失效类与 chatExecutor 逐字相同）</b>：原 {@code ThreadPoolTaskExecutor}
     * core=1/max=1/queue=100 是一条<b>可被永久占住的 OS 线程</b> —— 一个卡在权限弹窗
     * （{@code WebSocketPermissionPrompter} 的 {@code future.get()} 无超时是<b>设计</b>，对齐 CC 无限等待）
     * 或任何长阻塞的 run 会把<b>所有会话</b>的空闲代跑一起饿死。实锤：事故日志里
     * {@code cron-idle-1} 出现 7829 次而 {@code cron-idle-2} 及以后 <b>0 次</b>，该线程最后一行后
     * 64 分钟零日志（异步子代理跑完但主代理收不到结果的真因 L1）。
     *
     * <p>虚拟线程无池上限 ⇒ 每 run 独立，阻塞不传染其他会话（会话隔离真正成立，不再靠「池只有 1 条」
     * 这个假安全）。适合 IO 密集型（LLM 调用/DB/STOMP）。ThreadLocal（MDC/sessionId）虚拟线程支持。
     *
     * <p>⚠️ <b>必须与 M2（per-session dispatching 保留态）同批</b>：core=1 时代「同会话不并发」之所以
     * 看着成立，只因提交全序 ⇒ 单上本改动 = 单纯调大池（高危：制造同会话并发与静默新缺陷）。
     * 虚拟线程后该不变量由 {@code LlmAgentLoop.reserve/cancelReservation}（CC QueryGuard dispatching）
     * 在出队点显式守住。
     *
     * <p>可观测性保留：线程名前缀仍是 {@code cron-idle-}（{@code logback-spring.xml} 的
     * {@code [%thread]} 是判定「任务走哪条线程/开跑没有」的现成证据）。
     */
    @Bean(name = "cronExecutor")
    public Executor cronExecutor() {
        // [M1] 每任务独立虚拟线程 + 保留 "cron-idle-" 命名（Executors.newThreadPerTaskExecutor(factory)
        //   与 newVirtualThreadPerTaskExecutor 等价，只是允许自定义 ThreadFactory）。
        java.util.concurrent.ThreadFactory factory = Thread
            .ofVirtual()
            .name("cron-idle-", 0)
            .factory();
        return java.util.concurrent.Executors.newThreadPerTaskExecutor(factory);
    }
}
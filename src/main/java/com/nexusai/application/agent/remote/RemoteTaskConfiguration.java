package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * remote_agent 任务基础设施 Spring 装配。
 *
 * <p>创建 {@link RemoteSessionsApi}（CCR REST 传输）→ {@link RemoteAgentTaskService}
 * （状态机）Bean 链。独立配置类避免与共享 TaskConfiguration 冲突。
 *
 * <p><b>auth（R1 返工登记）</b>: Java 侧<b>无 claude.ai OAuth token 持久化/读取通道</b>
 * （OAuthService 为骨架；OAuth401Retry.java:47-48 自证"本项目当前无 OAuth token 流"），
 * 故 accessToken supplier 仍缺省返回 null —— 对齐 CC prepareApiRequest（api.ts:186-190）无 token
 * 即抛"require authentication"。OAuth token 通道（CC getClaudeAIOAuthTokens 等价：持久化
 * claude.ai OAuth accessToken + organizationUuid）为上线前提，已登记 OPD-R1-02 待接线。
 *
 * <p><b>批次Y Q3 输出根收敛 + 红线豁免（唯一根约定）</b>：
 * <ul>
 *   <li><b>输出根</b>（taskOutputDirSupplier）已从项目目录 {@code {projectRoot}/{sessionId}/tasks}
 *       迁 <b>temp 唯一根</b> {@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks} —— 与
 *       {@link BackgroundTaskRunner#taskOutputDir()} 同源（CC diskOutput.ts:50-55 唯一机制）。
 *       旧项目目录根 = CC 无对应的 Java 自创偏离，已消除。</li>
 *   <li><b>红线豁免</b>：输出落点<b>不再</b>经 {@link AutoMemPaths#currentSessionProjectRoot()}
 *       （memory/身份域红线约束的是 projectRoot 解析来源，不是 task 输出落点）—— 只拆输出落点
 *       耦合，{@link AutoMemPaths#currentSessionProjectRoot()} 回落链本身不动。</li>
 *   <li><b>元数据 sidecar 留项目目录</b>（sessionDirSupplier 不变，对齐 CC sessionStorage.ts:320-328
 *       remote-agents/*.meta.json 在项目目录）—— 只迁输出文件根，不搬 sidecar。</li>
 * </ul>
 *
 * <p>sessionDir 供 {@link RemoteAgentMetadataStore}（sidecar，项目目录）：sessionDir =
 * {projectRoot}/{sessionId}，projectRoot 取 {@link AutoMemPaths#currentSessionProjectRoot()}
 * （CC STATE.projectRoot 等价，会话线程 ThreadLocal，LlmAgentLoop.run() 入口注入），sessionId 取
 * {@link RequestContext#sessionId()}（MDC 线程上下文，ChatService 入口设置）。无会话上下文
 * （非会话线程）时回落 projectRoot（绝不含 java.io.tmpdir）。
 */
@Configuration
public class RemoteTaskConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RemoteTaskConfiguration.class);

    /** CC prod BASE_API_URL = 'https://api.anthropic.com'（constants/oauth.ts:85）· 对齐 Java 惯例 */
    public static final String DEFAULT_BASE_API_URL = "https://api.anthropic.com";

    /** 轮询调度器（daemon）· 对齐 CC setTimeout 自调度 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService remotePollScheduler() {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-agent-poll");
            t.setDaemon(true);
            return t;
        });
        log.info("RemoteTaskConfiguration: creating remotePollScheduler bean");
        return s;
    }

    /** CCR Sessions API REST 传输 · 对齐 CC api.ts + teleport.tsx */
    @Bean
    public RemoteSessionsApi remoteSessionsApi() {
        log.info("RemoteTaskConfiguration: creating RemoteSessionsApi bean (baseUrl={})", DEFAULT_BASE_API_URL);
        return new HttpRemoteSessionsApi(() -> DEFAULT_BASE_API_URL, () -> null);
    }

    /** remote_agent 状态机 · 对齐 CC RemoteAgentTask.tsx */
    @Bean
    public RemoteAgentTaskService remoteAgentTaskService(
            TaskFrameworkService taskFrameworkService,
            NotificationQueue notificationQueue,
            SdkEventQueue sdkEventQueue,
            RemoteSessionsApi remoteSessionsApi,
            ScheduledExecutorService remotePollScheduler) {
        log.info("RemoteTaskConfiguration: creating RemoteAgentTaskService bean");
        // R1 返工：tmp 占位 → 真实会话目录（{projectRoot}/{sessionId}）
        return new RemoteAgentTaskService(taskFrameworkService, notificationQueue,
            sdkEventQueue, remoteSessionsApi,
            sessionDirSupplier(),
            taskOutputDirSupplier(),
            remotePollScheduler);
    }

    /**
     * 真实会话目录 supplier（惰性求值 · 会话线程调用时解析）· <b>供元数据 sidecar</b>。
     *
     * <p>对齐 CC sessionStorage.ts:320-329（sidecar 落 {projectDir}/{sessionId}/remote-agents）：
     * 返回的 {@code sessionDir} = {projectRoot}/{sessionId}（本地会话目录），
     * {@link RemoteAgentMetadataStore} 再拼 remote-agents 子目录。
     *
     * <p><b>批次Y 红线豁免</b>：本 supplier 只承载 sidecar（元数据，留项目目录对齐 CC）；
     * <b>输出文件根</b>已解耦到 {@link #taskOutputDirSupplier()}（temp 唯一根，不再经
     * currentSessionProjectRoot）。AutoMemPaths.currentSessionProjectRoot() 回落链本身不动。
     *
     * <p>数据源（均 per-session 线程安全）：
     * <ul>
     *   <li>projectRoot = {@link AutoMemPaths#currentSessionProjectRoot()} — CC STATE.projectRoot
     *       （bootstrap/state.ts:277-279）等价，会话线程 ThreadLocal，LlmAgentLoop.run() 入口注入；</li>
     *   <li>sessionId   = {@link RequestContext#sessionId()} — MDC 线程上下文，ChatService 入口设置。</li>
     * </ul>
     * 无会话上下文（非会话线程：bean 构造期 / 启动线程）时回落 projectRoot，
     * 绝不含 java.io.tmpdir 占位。
     */
    private Supplier<Path> sessionDirSupplier() {
        return () -> {
            Path root = Path.of(AutoMemPaths.currentSessionProjectRoot());
            String sid = RequestContext.sessionId();
            return (sid != null && !sid.isBlank()) ? root.resolve(sid) : root;
        };
    }

    /**
     * 任务输出目录 supplier · 对齐 CC getTaskOutputDir（diskOutput.ts:50-55 = {projectTempDir}/
     * {sessionId}/tasks）。
     *
     * <p><b>批次Y Q3 收敛唯一根（红线豁免）</b>：输出根迁 <b>temp 唯一根</b>
     * {@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks}，与 {@link BackgroundTaskRunner#taskOutputPath}
     * 同源（Bash/PS/LOCAL_AGENT/monitor_mcp/remote_agent 全收统一根，CC 唯一 diskOutput 机制）。
     * <ul>
     *   <li><b>不再经 {@link AutoMemPaths#currentSessionProjectRoot()}</b> —— 输出落点与
     *       projectRoot 解析来源解耦（红线豁免：projectRoot 回落链本身不动，只拆输出落点耦合）。
     *       旧 {@code {projectDir}/{sessionId}/tasks} 项目目录根 = CC 无对应的 Java 自创偏离，已删。</li>
     *   <li><b>sidecar 留项目目录</b>：本 supplier 只管输出文件根；元数据 sidecar 仍由
     *       {@link #sessionDirSupplier()}（projectRoot，不动）承载，对齐 CC sessionStorage.ts:320-328。</li>
     * </ul>
     */
    private Supplier<Path> taskOutputDirSupplier() {
        return () -> Path.of(BackgroundTaskRunner.taskOutputDir());
    }
}

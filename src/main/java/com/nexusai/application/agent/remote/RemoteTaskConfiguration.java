package com.nexusai.application.agent.remote;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
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
 *       {@link BackgroundTaskRunner#taskOutputDir(String)} 同源（CC diskOutput.ts:50-55 唯一机制）。
 *       旧项目目录根 = CC 无对应的 Java 自创偏离，已消除。</li>
 *   <li><b>红线豁免</b>：输出落点<b>不再</b>经 {@code AutoMemPaths#currentSessionProjectRoot()}（批 4b-1 已删，旧三级回落版）
 *       （memory/身份域红线约束的是 projectRoot 解析来源，不是 task 输出落点）—— 只拆输出落点
 *       耦合，{@code AutoMemPaths#currentSessionProjectRoot()}（批 4b-1 已删，旧三级回落版） 回落链本身不动。</li>
 *   <li><b>元数据 sidecar 留项目目录</b>（{@link #sessionProjectRootResolver()}，对齐 CC
 *       sessionStorage.ts:320-328 remote-agents/*.meta.json 在项目目录）—— 只迁输出文件根，
 *       不搬 sidecar。</li>
 * </ul>
 *
 * <p>sessionDir 供 {@link RemoteAgentMetadataStore}（sidecar，项目目录）：sessionDir =
 * {projectRoot}/{sessionId}。<b>[TL-W2 P7]</b> projectRoot 按<b>任务 creatingSession 的 sessionId
 * 现算</b>（{@link com.nexusai.common.SessionProjectRoot#getForSession}：未绑定 → null，绝不回落 config home、不读
 * ThreadLocal；旧实现经 {@code Supplier<Path>} 在消费线程读
 * {@code AutoMemPaths#currentSessionProjectRoot()}（批 4b-1 已删，旧三级回落版），REST kill 线程必空 → 落/删错目录）。
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
        // [TL-W2 P7] sessionDir 解析由 Supplier<Path>（读 ThreadLocal）改 Function<sessionId, projectRoot>
        return new RemoteAgentTaskService(taskFrameworkService, notificationQueue,
            sdkEventQueue, remoteSessionsApi,
            sessionProjectRootResolver(),
            taskOutputDirSupplier(),
            remotePollScheduler);
    }

    /**
     * [TL-W2 P7] sidecar 会话目录解析器 · <b>按 sessionId 现算</b>（不再经 ThreadLocal）。
     *
     * <p>对齐 CC sessionStorage.ts:320-329（sidecar 落 {projectDir}/{sessionId}/remote-agents）：
     * {@link RemoteAgentTaskService} 的 {@code sessionDirFor(sessionId)} 用本解析器取 projectRoot，
     * 再拼 {@code /{sessionId}}（{@link RemoteAgentMetadataStore} 继续拼 remote-agents 子目录）。
     *
     * <p><b>WHY 不再是旧 {@code sessionDirSupplier}（Supplier&lt;Path&gt;）</b>：旧实现在<b>消费线程</b>
     * 读 {@code AutoMemPaths#currentSessionProjectRoot()}（批 4b-1 已删，旧三级回落版）（ThreadLocal）—— kill 由 REST 线程链路调用
     * （TaskController → BackgroundTaskRunner → {@link RemoteAgentTaskService#kill}）时 ThreadLocal
     * 必空 ⇒ 回落 config home ⇒ remote-agents 元数据落错根/删错目录（审计 P7）。
     * 现注入 <b>sessionId → projectRoot</b> 的纯函数（{@link com.nexusai.common.SessionProjectRoot#getForSession}：
     * 未绑定返回 null，绝不回落 config home、不读 ThreadLocal）；未绑定 → 不落 sidecar。
     *
     * <p><b>不可</b>改用 {@code SessionStorage.sessionProjectDir(sessionId)}：那是
     * {@code {configHome}/projects/{slug}} 存储目录（transcript 锚），会把 sidecar 从项目目录搬到
     * config home，破坏 sidecar 契约（sidecar 留项目目录）。
     */
    private java.util.function.Function<String, String> sessionProjectRootResolver() {
        return com.nexusai.common.SessionProjectRoot::getForSession;
    }

    /**
     * 任务输出目录 supplier · 对齐 CC getTaskOutputDir（diskOutput.ts:50-55 = {projectTempDir}/
     * {sessionId}/tasks）。
     *
     * <p><b>批次Y Q3 收敛唯一根（红线豁免）</b>：输出根迁 <b>temp 唯一根</b>
     * {@code {tmpRoot}/claude-{uid}/{sanitizedCwd}/{sessionId}/tasks}，与 {@link BackgroundTaskRunner#taskOutputPath}
     * 同源（Bash/PS/LOCAL_AGENT/monitor_mcp/remote_agent 全收统一根，CC 唯一 diskOutput 机制）。
     * <ul>
     *   <li><b>不再经 {@code AutoMemPaths#currentSessionProjectRoot()}（批 4b-1 已删，旧三级回落版）</b> —— 输出落点与
     *       projectRoot 解析来源解耦（红线豁免：projectRoot 回落链本身不动，只拆输出落点耦合）。
     *       旧 {@code {projectDir}/{sessionId}/tasks} 项目目录根 = CC 无对应的 Java 自创偏离，已删。</li>
     *   <li><b>sidecar 留项目目录</b>：本 supplier 只管输出文件根；元数据 sidecar 仍由
     *       {@link #sessionProjectRootResolver()}（按 creatingSessionId 现算 projectRoot）承载，
     *       对齐 CC sessionStorage.ts:320-328。</li>
     * </ul>
     */
    private java.util.function.Function<String, Path> taskOutputDirSupplier() {
        // [批 3b-D7] 会话态显式：sessionId → 该会话的输出根（⛔ 不再 Supplier 无参 + 下游读 MDC）
        return sessionId -> Path.of(BackgroundTaskRunner.taskOutputDir(sessionId));
    }
}

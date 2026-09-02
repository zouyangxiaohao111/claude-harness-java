package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台任务决策器 — s13 实现 CC 路径 1 (显式 run_in_background)；G1-3 增补路径 2
 * (assistant 主线程 15s 自动后台化判定)。
 *
 * <p>CC 3 条后台化决策路径 (都在 BashTool.call() 内部):
 * <ol>
 *   <li><b>显式请求</b>: run_in_background === true && !isBackgroundTasksDisabled (BashTool.tsx:989-995) — s13</li>
 *   <li><b>Kairos Assistant 模式自动后台化</b>: 15s 超时后自动后台 (BashTool.tsx:976-982) — G1-3</li>
 *   <li><b>命令超时回调</b>: onTimeout 触发后台化 (BashTool.tsx:967-971) — BashTool 侧接线 (G5)</li>
 * </ol>
 *
 * <p><b>CC 没有关键词启发式</b>: COMMON_BACKGROUND_COMMANDS (BashTool.tsx:265, 23 个命令) 仅用于
 * analytics 日志分类 (getCommandTypeForLogging)，不是后台决策依据。
 *
 * <p><b>命名不含 "Heuristic"</b> — CC 源码中不存在关键词启发式路径。
 */
public class BackgroundTaskDecider {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskDecider.class);

    /**
     * G1-3: assistant 主线程阻塞命令自动后台化阈值 · CC original: ASSISTANT_BLOCKING_BUDGET_MS
     * （BashTool.tsx:57）= 15_000 —— 主 agent 保持响应，阻塞命令跑满预算自动转后台（不杀进程）。
     */
    public static final long ASSISTANT_BLOCKING_BUDGET_MS = 15_000L;

    /** CC 对等: CLAUDE_CODE_DISABLE_BACKGROUND_TASKS 环境变量 (BashTool.tsx:224-226) */
    private final boolean backgroundTasksEnabled;

    /**
     * G1-3: CC {@code feature('KAIROS')} 编译期门（BashTool.tsx:976）· Java 等价接入点 =
     * {@code nexusai.feature.kairos} 属性（对齐 KairosOrPushNotificationEnabledCondition 的
     * {@code FEATURE_KAIROS} 键）。默认 false —— 对齐 CC 外部构建 KAIROS 默认关（auto-background 整链断链）。
     */
    private final boolean kairosEnabled;

    /**
     * G1-3: CC {@code getKairosActive()} 运行时门（BashTool.tsx:976 + bootstrap/state.ts:1085-1086
     * {@code STATE.kairosActive}，默认 false）· Java 端为会话级状态（assistant 模式激活），由
     * 调用方（BashTool G5 组）按会话注入。默认 false。
     */
    private final boolean kairosActive;

    /**
     * @param backgroundTasksEnabled 是否启用后台任务 (对等 CC isBackgroundTasksDisabled 的反向)
     */
    public BackgroundTaskDecider(boolean backgroundTasksEnabled) {
        this(backgroundTasksEnabled, false, false);
    }

    /**
     * G1-3: 全参构造 · kairosEnabled / kairosActive 默认 false（对齐 CC 外部构建 KAIROS 关）。
     *
     * @param backgroundTasksEnabled 是否启用后台任务 (对等 CC isBackgroundTasksDisabled 的反向)
     * @param kairosEnabled          CC feature('KAIROS') 编译门（nexusai.feature.kairos 属性）
     * @param kairosActive           CC getKairosActive() 运行时门（assistant 模式激活，会话级）
     */
    public BackgroundTaskDecider(boolean backgroundTasksEnabled, boolean kairosEnabled, boolean kairosActive) {
        this.backgroundTasksEnabled = backgroundTasksEnabled;
        this.kairosEnabled = kairosEnabled;
        this.kairosActive = kairosActive;
    }

    /**
     * s13 唯一的后台决策路径 — 对齐 CC BashTool.tsx:989-995
     *
     * <p>CC BashTool.tsx:989:
     * <pre>
     * if (run_in_background === true && !isBackgroundTasksDisabled) {
     *   spawnBackgroundTask();
     * }
     * </pre>
     *
     * <p>路径 2 (Kairos 15s) 和路径 3 (onTimeout) 由 {@link #isAssistantAutoBackgroundEligible}
     * 与 BashTool 侧 (G5) 承接
     *
     * @param explicitRunInBackground 用户/LLM 显式设置的 run_in_background 值
     * @return true 表示应走后台执行路径
     */
    public boolean shouldRunBackground(boolean explicitRunInBackground) {
        if (!backgroundTasksEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("BackgroundTaskDecider: 后台任务已禁用 (isBackgroundTasksDisabled)");
            }
            return false;
        }
        boolean result = explicitRunInBackground; // s13: 仅路径 1
        if (log.isDebugEnabled()) {
            log.debug("BackgroundTaskDecider: explicitRunInBackground={}, result={}", explicitRunInBackground, result);
        }
        return result;
    }

    /**
     * G1-3: assistant 主线程阻塞命令是否可自动后台化 · 对齐 CC BashTool.tsx:976 的 {@code &&} 条件。
     *
     * <p>CC 真源（BashTool.tsx:976-982）：
     * <pre>
     * if (feature('KAIROS') && getKairosActive() && isMainThread
     *     && !isBackgroundTasksDisabled && run_in_background !== true) {
     *   setTimeout(() => {
     *     if (shellCommand.status === 'running' && backgroundShellId === undefined) {
     *       assistantAutoBackgrounded = true;
     *       startBackgrounding('tengu_bash_command_assistant_auto_backgrounded');
     *     }
     *   }, ASSISTANT_BLOCKING_BUDGET_MS).unref();
     * }
     * </pre>
     *
     * <p>映射：{@code feature('KAIROS')} → {@link #kairosEnabled}；{@code getKairosActive()} →
     * {@link #kairosActive}；{@code !isBackgroundTasksDisabled} → {@link #backgroundTasksEnabled}。
     * 判定通过后由 BashTool（G5 组）启动 {@link #ASSISTANT_BLOCKING_BUDGET_MS} 定时器，进程仍运行
     * 且未后台化时调 {@code backgroundExistingForegroundTask} 就地转后台（不杀进程，进程继续跑）。
     *
     * <p>仅判定（规则九：判定方法独立可测）；15s 定时器与进程侧条件（status==='running' &&
     * backgroundShellId===undefined）由 BashTool G5 组接线。
     *
     * @param isMainThread   当前是否主 agent 线程（CC isMainThread，:976）——subagent/异步非主线程不触发
     * @param runInBackground 本次调用的 run_in_background 显式值（true 时不自动后台，走路径 1）
     * @return true 应启动 15s 自动后台化定时器
     */
    public boolean isAssistantAutoBackgroundEligible(boolean isMainThread, boolean runInBackground) {
        boolean eligible = kairosEnabled && kairosActive && isMainThread
            && backgroundTasksEnabled && !runInBackground;
        if (log.isDebugEnabled()) {
            log.debug("BackgroundTaskDecider: assistant 自动后台化判定 kairosEnabled={} kairosActive={} "
                    + "isMainThread={} backgroundTasksEnabled={} runInBackground={} → {}（对齐 CC BashTool.tsx:976）",
                kairosEnabled, kairosActive, isMainThread, backgroundTasksEnabled, runInBackground, eligible);
        }
        return eligible;
    }

    /** CC BashTool.tsx:224-226 — 后台任务是否启用 */
    public boolean isEnabled() {
        return backgroundTasksEnabled;
    }
}

package com.nexusai.application.agent.permission.hook;

/**
 * 挂起的 async hook 记录 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/AsyncHookRegistry.ts:12-25}
 * {@code PendingAsyncHook} 12 字段.
 *
 * <p><b>WHY 可变 class 而非 record (Pattern #4)</b>: CC 在交付时<b>就地 mutate</b>
 * {@code responseAttachmentSent} (AsyncHookRegistry.ts:214 {@code hook.responseAttachmentSent = true}),
 * 而所有 settled 回调共享同一 map 引用 (allSettled 语义 :236-246: 已应用的 flag 不因
 * finalizeHook 异常回滚). record 的字段不可变 → 无法表达"标记已交付"的语义, 只能
 * 换引用重写 map 值, 会破坏快照迭代 (CC :142 先快照再处理) 的一致性.
 *
 * <p><b>字段与 CC 对齐</b> (AsyncHookRegistry.ts:12-25):
 * <ul>
 *   <li>{@code processId} (:13) — 进程 ID (config async 路径 = "async_hook_" + pid)</li>
 *   <li>{@code hookId} (:14) — 事件总线关联标识 (emitHookStarted/Response 同 id)</li>
 *   <li>{@code hookName} (:15) — hook 名 (日志关联)</li>
 *   <li>{@code hookEvent} (:16) — CC 事件名 (PascalCase 字符串, 如 "PreToolUse")</li>
 *   <li>{@code toolName} (:17) / {@code pluginId} (:18) — optional</li>
 *   <li>{@code startTime} (:19) — 注册时间戳</li>
 *   <li>{@code timeout} (:20) — asyncTimeout, <b>只存不用</b> (CC 无消费点, :51/53/78)</li>
 *   <li>{@code command} (:21) — hook 命令串 (日志)</li>
 *   <li>{@code responseAttachmentSent} (:22) — 可变: 已标记交付</li>
 *   <li>{@code shellCommand} (:23) — 进程抽象 (CC ShellCommand, 此处
 *       {@link AsyncHookProcess} 接口, status: 'running'|'completed'|'killed')</li>
 *   <li>{@code stopProgressInterval} (:24) — 进度定时器停止回调 (CC 函数引用, Java Runnable)</li>
 * </ul>
 *
 * @see AsyncHookRegistry
 * @see HookEventBus
 * @since Session H10
 */
public final class PendingAsyncHook {

    /**
     * 进程抽象 · 对齐 CC {@code ShellCommand} (AsyncHookRegistry.ts:23, :160-189 使用面).
     *
     * <p>CC 用到的 ShellCommand 面: {@code status} (:'running'|'completed'|'killed'),
     * {@code taskOutput.getStdout()/getStderr()} (:146-147, :97-99), {@code cleanup()} (:100),
     * {@code kill()} (:294), {@code result.code} (:188-189, :286-290). Java 端无法承载
     * ShellCommand 全量 (TS 运行时进程封装), 提取上述 5 面 + exitCode 为接口,
     * CommandHookExecutor 提供 {@code HookProcessWrapper} 适配真实 {@link Process}.
     *
     * <p><b>exitCode() 扩展说明</b>: 接口比任务书多 1 个方法 — CC 读取退出码的两处
     * (checkForAsyncHookResponses :188-189 {@code execResult.code}, finalizePendingAsyncHooks
     * :286-290) 都需要, 不暴露则注册表拿不到退出码. 仅在 status()='completed' 后调用
     * (CC 同一顺序约束), 未退出时实现应降级返回 1.
     */
    public interface AsyncHookProcess {
        /** CC ShellCommand.status · 'running' | 'completed' | 'killed' (AsyncHookRegistry.ts:160-173). */
        String status();

        /** CC taskOutput.getStdout() (AsyncHookRegistry.ts:146) · 累积的 stdout 全量. */
        String stdout();

        /** CC taskOutput.getStderr() (AsyncHookRegistry.ts:147) · 累积的 stderr 全量. */
        String stderr();

        /** CC shellCommand.cleanup() (AsyncHookRegistry.ts:100) · 释放进程侧资源. */
        void cleanup();

        /** CC shellCommand.kill() (AsyncHookRegistry.ts:294) · 强杀进程. */
        void kill();

        /** CC execResult.code (AsyncHookRegistry.ts:188-189) · 退出码; 仅 completed 后有效. */
        int exitCode();
    }

    /** CC original: processId (AsyncHookRegistry.ts:13) */
    private final String processId;
    /** CC original: hookId (AsyncHookRegistry.ts:14) */
    private final String hookId;
    /** CC original: hookName (AsyncHookRegistry.ts:15) */
    private final String hookName;
    /** CC original: hookEvent (AsyncHookRegistry.ts:16) · CC 事件名 (PascalCase 字符串) */
    private final String hookEvent;
    /** CC original: toolName (AsyncHookRegistry.ts:17) · nullable */
    private final String toolName;
    /** CC original: pluginId (AsyncHookRegistry.ts:18) · nullable */
    private final String pluginId;
    /** CC original: startTime (AsyncHookRegistry.ts:19) */
    private final long startTime;
    /** CC original: timeout (AsyncHookRegistry.ts:20) · 只存不用 (H10-4 已证伪超时消费) */
    private final long timeout;
    /** CC original: command (AsyncHookRegistry.ts:21) */
    private final String command;
    /** CC original: responseAttachmentSent (AsyncHookRegistry.ts:22) · 可变, 见类注释 WHY */
    private volatile boolean responseAttachmentSent;
    /** CC original: shellCommand (AsyncHookRegistry.ts:23) · nullable (无进程 → 直接移除) */
    private final AsyncHookProcess shellCommand;
    /** CC original: stopProgressInterval (AsyncHookRegistry.ts:24) · 进度定时器停止回调 */
    private final Runnable stopProgressInterval;

    public PendingAsyncHook(String processId, String hookId, String hookName, String hookEvent,
                            String toolName, String pluginId, long startTime, long timeout, String command,
                            boolean responseAttachmentSent, AsyncHookProcess shellCommand,
                            Runnable stopProgressInterval) {
        this.processId = processId;
        this.hookId = hookId;
        this.hookName = hookName;
        this.hookEvent = hookEvent;
        this.toolName = toolName;
        this.pluginId = pluginId;
        this.startTime = startTime;
        this.timeout = timeout;
        this.command = command;
        this.responseAttachmentSent = responseAttachmentSent;
        this.shellCommand = shellCommand;
        this.stopProgressInterval = stopProgressInterval;
    }

    public String processId() { return processId; }
    public String hookId() { return hookId; }
    public String hookName() { return hookName; }
    public String hookEvent() { return hookEvent; }
    public String toolName() { return toolName; }
    public String pluginId() { return pluginId; }
    public long startTime() { return startTime; }
    public long timeout() { return timeout; }
    public String command() { return command; }
    public boolean responseAttachmentSent() { return responseAttachmentSent; }

    /** 标记已交付 · 对齐 CC :214 {@code hook.responseAttachmentSent = true} (唯一 mutate 点). */
    public void setResponseAttachmentSent(boolean responseAttachmentSent) {
        this.responseAttachmentSent = responseAttachmentSent;
    }

    public AsyncHookProcess shellCommand() { return shellCommand; }
    public Runnable stopProgressInterval() { return stopProgressInterval; }
}

package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.bash.ShellResolver;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command Hook 执行器 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks.ts:747-1335}
 * {@code execCommandHook} 全函数 (主 agent grep 自验行号 747).
 *
 * <p>WHY (Session H2): settings.json 配置的 {@link CommandHook} (type='command') 由
 * {@link HookRegistry#executeEvent(HookEvent)} 分发到本执行器, 等价 CC execCommandHook:
 * shell 选择 (bash/pwsh) → Windows POSIX 路径转换 → ${CLAUDE_PLUGIN_ROOT} 变量替换 →
 * {@code .sh} prepend → CLAUDE_CODE_SHELL_PREFIX → timeout → env 注入 → spawn →
 * stdin jsonInput 写入 (EPIPE 处理) → stdout/stderr 异步收集 → async 检测
 * → 错误分流. 返回 6 字段 {@link CommandHookResult}, 由调用方 (HookRegistry) 经
 * {@link #toHookResultCore} 按 CC runHook 分流序 (hooks.ts:2446-2730) 解析:
 * aborted→cancelled; validationError/json 先于 status; 纯文本 exit0→hook_success;
 * exit2→blocking; 其他非零→non_blocking_error (S4 G01/G02/G03/G17).
 *
 * <p><b>可测性设计</b> (Pattern #14 RED-GREEN + Windows 无 Git Bash CI):
 * <ul>
 *   <li>纯逻辑全 static: {@link #windowsPathToPosixPath} / {@link #buildFinalCommand} /
 *       {@link #applyShellPrefix} / {@link #buildEnv} / {@link #toHookResult} — 直接单测</li>
 *   <li>进程执行经 {@link ProcessLauncher} 抽象, 测试注入 fake {@link HookProcess},
 *       不依赖真实 Git Bash/pwsh 存在</li>
 *   <li>env 读取经 {@link Function}{@code <String,String>} resolver 注入 (默认 {@link System#getenv})</li>
 * </ul>
 *
 * <p><b>关键纠偏</b> (H2.md 决策点 1): 默认 shell 是 bash (Windows 走 Git Bash), 仅显式
 * {@code shell: "powershell"} 才走 pwsh. 不要在 Windows 上默认切 pwsh.
 *
 * <p><b>async 接线 (Session H10)</b>: 检测到 async (config 或 stdout 首行) →
 * {@link #executeInBackground(String, String, HookProcess, PendingAsyncHook.AsyncHookProcess,
 * HookJSONOutput.AsyncHookOutput, HookEventType, String, String, boolean, String, Thread, Thread)}
 * (等价 CC executeInBackground, hooks.ts:192-263):
 * <ul>
 *   <li>{@code asyncRewake=true} → <b>bypass registry</b> (CC :205-240): 完成回调读输出 →
 *       emitHookResponse → exit 2 → NotificationQueue 入队 task-notification 唤醒模型</li>
 *   <li>否则 → 注册进 {@link AsyncHookRegistry} (CC :242-263), 由轮询交付响应</li>
 * </ul>
 * 进程包装 {@link HookProcessWrapper} (status: 'running'|'completed'|'killed', 对齐 CC
 * ShellCommand.status), stdout/stderr 由 daemon 读取线程累积. <b>H10-9 修复</b>:
 * stdout 首行 async 检测在 data 流首行即完成 (stdout 读取线程内, CC hooks.ts:1117-1164),
 * 主线程 wait 循环轮询检测结果提前返回 — 长跑 async hook 不再被同步超时杀死
 * (旧实现先 waitFor 进程退出再检测).
 *
 * <p><b>hookIndex 接线</b>: {@code CLAUDE_ENV_FILE} (CC :917-926) 需要 hookIndex,
 * 由 {@link HookRegistry} 按事件匹配列表位置生成 (CC :3084-3085 {@code map} index →
 * :3293 传递 → :925 注入 env), 本执行器 {@link #buildEnv} 在
 * SessionStart/Setup/CwdChanged/FileChanged + bash + hookIndex 非 null 时注入
 * session 环境脚本路径.
 *
 * <p><b>日志</b>: slf4j + 中文, debug 用 {@code if (log.isDebugEnabled())} 包裹.
 *
 * @since Session H2
 */
@Component
public class CommandHookExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandHookExecutor.class);

    /**
     * 默认 hook 超时 10 分钟 · 对齐 CC {@code TOOL_HOOK_EXECUTION_TIMEOUT_MS}
     * (hooks.ts:166 {@code 10 * 60 * 1000 = 600000}).
     *
     * <p>WHY (Concern H2-2): 不动 {@code HookRegistry.hookTimeoutMs} (10s, 面向程序化 hook
     * 的 CompletableFuture.get), command hook 超时语义独立对齐 CC.
     */
    public static final long DEFAULT_HOOK_EXECUTION_TIMEOUT_MS = 10L * 60 * 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC execCommandHook 返回结构 (hooks.ts:760-767) · Java 端 6 字段 record. */
    public record CommandHookResult(
        String stdout,
        String stderr,
        String output,
        int status,
        boolean aborted,
        boolean backgrounded
    ) {
    }

    /**
     * 进程启动描述 · 传给 {@link ProcessLauncher#launch(ProcessSpec)}.
     *
     * @param commandArgs 完整 argv (PowerShell: [pwsh, -NoProfile, -NonInteractive, -Command, cmd];
     *                    bash: [bashPath, -c, cmd] / [/bin/sh, -c, cmd])
     * @param env         追加的 hook env (父进程 env 由 ProcessBuilder 默认继承)
     * @param cwd         工作目录 (可 null = 继承父进程)
     */
    public record ProcessSpec(List<String> commandArgs, Map<String, String> env, String cwd) {
    }

    /** 进程句柄抽象 · 供测试注入 fake 实现, 不依赖真实 {@link Process}. */
    public interface HookProcess {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

        void destroyForcibly();

        int exitValue();
    }

    /** 进程启动抽象 · 默认 {@link DefaultProcessLauncher}, 测试注入 fake. */
    @FunctionalInterface
    public interface ProcessLauncher {
        HookProcess launch(ProcessSpec spec) throws IOException;
    }

    private final ProcessLauncher processLauncher;
    private final Function<String, String> envResolver;
    private final Function<String, Boolean> pathExists;
    private final Supplier<String> projectRootResolver;
    private final Function<String, String> pluginDataDirResolver;

    /**
     * async hook 注册表 · volatile + setter, 仿 HookRegistry.setCommandHookExecutor 模式
     * (HookRegistry.java:107-111). @Autowired(required=false): 手动 new 场景为 null →
     * async 降级 (config async 占位 / stdout 首行 async 走同步路径, Fail Loud 日志).
     * 等价 CC executeInBackground 的 registry 注册面 (hooks.ts:242-263).
     */
    private volatile AsyncHookRegistry asyncHookRegistry;

    /**
     * 事件总线 · asyncRewake bypass 完成回调直接 emitHookResponse (CC hooks.ts:226);
     * null = 仅日志 (测试/未接线场景).
     */
    private volatile HookEventBus hookEventBus;

    /**
     * [S4 D-02] sync 路径进度定时器间隔 · 缺省 HookEventBus 默认 1000ms
     * (hookEvents.ts:147 {@code intervalMs ?? 1000}). 测试可覆写提速 (10ms),
     * 不触碰 HookEventBus (S3 文件).
     */
    volatile long progressIntervalMs = HookEventBus.DEFAULT_PROGRESS_INTERVAL_MS;

    /**
     * 通知队列 · asyncRewake exit 2 → enqueuePendingNotification(task-notification)
     * (CC hooks.ts:232-238); null = warn 日志 (唤醒通知丢失).
     */
    private volatile NotificationQueue notificationQueue;

    /** 注入 async hook 注册表 (Spring 可选). */
    @Autowired(required = false)
    public void setAsyncHookRegistry(AsyncHookRegistry asyncHookRegistry) {
        this.asyncHookRegistry = asyncHookRegistry;
    }

    /** 注入事件总线 (Spring 可选). */
    @Autowired(required = false)
    public void setHookEventBus(HookEventBus hookEventBus) {
        this.hookEventBus = hookEventBus;
    }

    /** 注入通知队列 (Spring 可选). */
    @Autowired(required = false)
    public void setNotificationQueue(NotificationQueue notificationQueue) {
        this.notificationQueue = notificationQueue;
    }

    /** Spring 无参构造 · 全部默认实现. */
    public CommandHookExecutor() {
        this(null, null, null, null, null);
    }

    /**
     * 全参构造 (测试注入) · 任一参数 null → 默认实现.
     *
     * @param processLauncher      进程启动器 (默认 {@link DefaultProcessLauncher})
     * @param envResolver          env 取值器 (默认 {@link System#getenv})
     * @param pathExists           路径存在判断 (默认 {@link Files#exists})
     * @param projectRootResolver  CLAUDE_PROJECT_DIR 取值器 (默认 user.dir, CC getProjectRoot)
     * @param pluginDataDirResolver CLAUDE_PLUGIN_DATA 取值器 (默认 ~/.nexusai/plugins/{id})
     */
    CommandHookExecutor(ProcessLauncher processLauncher, Function<String, String> envResolver,
                        Function<String, Boolean> pathExists, Supplier<String> projectRootResolver,
                        Function<String, String> pluginDataDirResolver) {
        this.processLauncher = processLauncher != null ? processLauncher : new DefaultProcessLauncher();
        this.envResolver = envResolver != null ? envResolver : System::getenv;
        this.pathExists = pathExists != null ? pathExists : CommandHookExecutor::defaultPathExists;
        this.projectRootResolver = projectRootResolver != null ? projectRootResolver : CommandHookExecutor::defaultProjectRoot;
        this.pluginDataDirResolver = pluginDataDirResolver != null ? pluginDataDirResolver : CommandHookExecutor::defaultPluginDataDir;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 主入口 · 等价 CC execCommandHook (hooks.ts:747-1335)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 执行 command hook · 等价 CC execCommandHook (hooks.ts:747-1335).
     *
     * <p>流程 (CC 行号):
     * <ol>
     *   <li>shell 选择 (CC :790-792): {@code hook.shell ?? 'bash'}; isPowerShell 判定</li>
     *   <li>Windows POSIX 路径转换 (CC :808-811): Windows bash 走 {@link #windowsPathToPosixPath}</li>
     *   <li>命令变换 (CC :822-875): ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA}/${user_config.X} 替换 +
     *       .sh prepend + CLAUDE_CODE_SHELL_PREFIX</li>
     *   <li>timeout (CC :877-879): {@code hook.timeout * 1000} 缺省 10min</li>
     *   <li>env (CC :882-926): CLAUDE_PROJECT_DIR + plugin/skill vars + CLAUDE_ENV_FILE</li>
     *   <li>spawn (CC :957-984): pwsh -NoProfile -NonInteractive -Command / bash -c</li>
     *   <li>async 检测 (CC :995-1030, :1117-1164) → backgrounded 占位</li>
     *   <li>stdin 写入 (CC :1006, :1189-1216) + EPIPE 处理</li>
     *   <li>stdout/stderr 异步收集 + 首行 async 检测 (CC :1068-1165) — prompt 检测已删 (DEL-01e)</li>
     *   <li>错误分流 (CC :1263-1335)</li>
     * </ol>
     *
     * @param hook                CC original: hook (hooks.ts:748); command hook 配置
     * @param hookEvent           CC original: hookEvent (hooks.ts:749); 触发事件
     * @param hookName            hook 名 (日志关联; MatchedHook 未携带, 用生成名)
     * @param jsonInput           stdin JSON payload (hook 输入); 由 {@link #buildJsonInput(HookEvent)} 构造
     * @param pluginRoot          CC original: pluginRoot (hooks.ts:756); 插件根目录, null=跳过变量替换
     * @param pluginId            CC original: pluginId (hooks.ts:757); 插件 ID
     * @param skillRoot           CC original: skillRoot (hooks.ts:758); skill 根目录
     * @param hookIndex           CC original: hookIndex (hooks.ts:754); hook 在事件列表中的索引 (CLAUDE_ENV_FILE), null=不注入
     * @param forceSyncExecution  CC original: forceSyncExecution (hooks.ts:759); true=强制同步不后台化
     * @return 6 字段 {@link CommandHookResult}
     * @throws IllegalStateException pluginRoot 非 null 但目录不存在 (CC :831-836 语义, 调用方按 non-blocking 处理)
     */
    public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                     String pluginRoot, String pluginId, String skillRoot,
                                     Integer hookIndex, boolean forceSyncExecution) {
        // [IMP-HOOKS-S9 DEL-01d] 9 参签名 · 委托 10 参 (parentAbort=null, 与 CC signal 缺省语义一致).
        //   prompt 回调参数已删除 (未接线通道, DEL-01)。
        return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
            hookIndex, forceSyncExecution, null);
    }

    /**
     * 执行 command hook · 等价 CC execCommandHook (hooks.ts:747-1335), 支持父 abort 传播.
     *
     * <p><b>parentAbort (EX_E §8.3)</b>: CC execCommandHook 接收 abortSignal 参数
     * (hooks.ts:755), spawn 后经 {@code wrapSpawn(child, signal, ...)} 注册
     * {@code addEventListener('abort', ...)} (ShellCommand.ts:264-265); abort 触发 →
     * {@code #abortHandler()} → {@code kill()} → {@code treeKill(pid, SIGKILL)}
     * (ShellCommand.ts:186-193, :345-347), 结果 {@code aborted: signal.aborted}
     * (hooks.ts:1257). Java 端: {@link AbortController#onCancel} 监听 → destroyForcibly
     * (等价 SIGKILL) + {@code aborted=true} + 'Hook cancelled' (CC ABORT_ERR 文案
     * hooks.ts:1300-1307). null / {@link AbortController#NOOP} = 无父 abort, 行为与
     * 旧 10 参签名一致.
     *
     * <p><b>幂等</b>: abort 与超时并发只终止一次 — listener 内 CAS + 超时路径共用
     * {@code destroyForcibly} (对已终止进程为 no-op), 且完成后 finally 摘除监听面.
     *
     * <p>流程 (CC 行号):
     * <ol>
     *   <li>shell 选择 (CC :790-792): {@code hook.shell ?? 'bash'}; isPowerShell 判定</li>
     *   <li>Windows POSIX 路径转换 (CC :808-811): Windows bash 走 {@link #windowsPathToPosixPath}</li>
     *   <li>命令变换 (CC :822-875): ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA}/${user_config.X} 替换 +
     *       .sh prepend + CLAUDE_CODE_SHELL_PREFIX</li>
     *   <li>timeout (CC :877-879): {@code hook.timeout * 1000} 缺省 10min</li>
     *   <li>env (CC :882-926): CLAUDE_PROJECT_DIR + plugin/skill vars + CLAUDE_ENV_FILE</li>
     *   <li>spawn (CC :957-984): pwsh -NoProfile -NonInteractive -Command / bash -c</li>
     *   <li>async 检测 (CC :995-1030, :1117-1164) → backgrounded 占位</li>
     *   <li>stdin 写入 (CC :1006, :1189-1216) + EPIPE 处理</li>
     *   <li>stdout/stderr 异步收集 + 首行 async 检测 (CC :1068-1165) — prompt 检测已删 (DEL-01e)</li>
     *   <li>错误分流 (CC :1263-1335)</li>
     * </ol>
     *
     * @param hook                CC original: hook (hooks.ts:748); command hook 配置
     * @param hookEvent           CC original: hookEvent (hooks.ts:749); 触发事件
     * @param hookName            hook 名 (日志关联; MatchedHook 未携带, 用生成名)
     * @param jsonInput           stdin JSON payload (hook 输入); 由 {@link #buildJsonInput(HookEvent)} 构造
     * @param pluginRoot          CC original: pluginRoot (hooks.ts:756); 插件根目录, null=跳过变量替换
     * @param pluginId            CC original: pluginId (hooks.ts:757); 插件 ID
     * @param skillRoot           CC original: skillRoot (hooks.ts:758); skill 根目录
     * @param hookIndex           CC original: hookIndex (hooks.ts:754); hook 在事件列表中的索引 (CLAUDE_ENV_FILE), null=不注入
     * @param forceSyncExecution  CC original: forceSyncExecution (hooks.ts:759); true=强制同步不后台化
     * @param parentAbort         CC original: signal (hooks.ts:755); 父 abort 信号, null/NOOP=不监听
     * @return 6 字段 {@link CommandHookResult}
     * @throws IllegalStateException pluginRoot 非 null 但目录不存在 (CC :831-836 语义, 调用方按 non-blocking 处理)
     */
    /**
     * [IMP-HOOKS-S5 D-01] 执行 command hook + 调用方缺省超时 · 10 参重载缺省 10min；
     * SessionEnd 传 sessionEndTimeoutMs（CC hooks.ts:3280 {@code hook.timeout ? hook.timeout*1000
     * : timeoutMs}，executeHooksOutsideREPL 的 timeoutMs 由 executeSessionEndHooks 注入 1500）。
     * [IMP-HOOKS-S9 DEL-01d] prompt 回调参数已删除 (未接线通道, DEL-01)。
     */
    public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                     String pluginRoot, String pluginId, String skillRoot,
                                     Integer hookIndex, boolean forceSyncExecution,
                                     AbortController parentAbort,
                                     long defaultTimeoutMs) {
        // [S4 G09 / G14] 委托 12 参 · hookCwd 缺省 = CwdResolution.getCwd(hookEvent.sessionId())
        //   (对齐 CC execCommandHook hookCwd=getCwd() hooks.ts:931 —— 不再读 JVM user.dir,
        //   G14 收敛统一入口消除同语义两套标准). HookRegistry 经 resolveSpawnCwd(event) 传
        //   会话 cwd 时走 12 参版本; 本便捷重载缺省经 CwdResolution 解析.
        return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
            hookIndex, forceSyncExecution, parentAbort, defaultTimeoutMs,
            CwdResolution.getCwd(hookEvent != null ? hookEvent.sessionId() : null));
    }

    /**
     * [S4 G09] 执行 command hook · 12 参版本 (含 spawn cwd 注入).
     * [IMP-RS-01 DEL-01e 补回] 12 参便捷版 · 委托 13 参 (requestPrompt=null → prompt 通道关闭,
     * 对齐 CC feature('HOOK_PROMPTS')=false 时 requestPrompt: undefined 语义, hooks.ts:2520)。
     *
     * <p><b>hookCwd (S4 G09)</b>: 对齐 CC execCommandHook cwd 解析 (hooks.ts:928-938) —
     * {@code hookCwd = getCwd()} (会话 AsyncLocalStorage cwd), {@code safeCwd =
     * pathExists(hookCwd) ? hookCwd : getOriginalCwd()}. Java 端会话 cwd 由
     * {@link HookRegistry} 经 {@link #resolveSpawnCwd(HookEvent)} 解析
     * (event.cwd() ?? CwdResolution.getCwd(sessionId) —— G14 收敛统一入口),
     * 本参数即 CC hookCwd; pathExists 回退语义不变 (false → safeCwd=null ≈ 继承 JVM cwd ≈ CC getOriginalCwd).
     *
     * @param hookCwd          CC original: hookCwd (hooks.ts:931); 会话 cwd (可 null ≈ 继承 JVM cwd)
     */
    public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                     String pluginRoot, String pluginId, String skillRoot,
                                     Integer hookIndex, boolean forceSyncExecution,
                                     AbortController parentAbort,
                                     long defaultTimeoutMs, String hookCwd) {
        return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
            hookIndex, forceSyncExecution, parentAbort, defaultTimeoutMs, hookCwd, null);
    }

    /**
     * [S4 G09 + IMP-RS-01 DEL-01e 补回] 执行 command hook · 13 参全量版本 (含 spawn cwd 注入 +
     * prompt 请求回调)。
     *
     * <p><b>requestPrompt (IMP-RS-01 DEL-01e 补回)</b>: 对齐 CC execCommandHook 的
     * {@code requestPrompt?: (request: PromptRequest) => Promise<PromptResponse>} 参数
     * (hooks.ts:759)。非 null 时:
     * <ul>
     *   <li>stdout 逐行检测 {@code {prompt, message, options}} JSON (CC :1072-1110),
     *       命中 → 串行调 {@code requestPrompt} 并把 {@link PromptResponse} JSON 写回 stdin
     *       (每行一个 + '\n'), 回调失败 → destroy stdin 防 hook 挂起 (CC :1098-1102)</li>
     *   <li>stdin 首轮写入后保持 open 供 prompt 响应 (CC :1199-1216)</li>
     *   <li>最终 stdout 过滤已处理的 prompt 行 (防泄漏, CC :1243-1249)</li>
     * </ul>
     * null (默认) → prompt 通道关闭, stdin 写后即关 (CC :1212-1214 无回调时 end 语义),
     * 与 CC 发布产物 {@code feature('HOOK_PROMPTS')=false} 时行为一致。
     *
     * <p><b>hookCwd (S4 G09)</b>: 对齐 CC execCommandHook cwd 解析 (hooks.ts:928-938) —
     * {@code hookCwd = getCwd()} (会话 AsyncLocalStorage cwd), {@code safeCwd =
     * pathExists(hookCwd) ? hookCwd : getOriginalCwd()}. Java 端会话 cwd 由
     * {@link HookRegistry} 经 {@link #resolveSpawnCwd(HookEvent)} 解析
     * (event.cwd() ?? CwdResolution.getCwd(sessionId) —— G14 收敛统一入口),
     * 本参数即 CC hookCwd; pathExists 回退语义不变 (false → safeCwd=null ≈ 继承 JVM cwd ≈ CC getOriginalCwd).
     *
     * @param hookCwd          CC original: hookCwd (hooks.ts:931); 会话 cwd (可 null ≈ 继承 JVM cwd)
     * @param requestPrompt    CC original: requestPrompt (hooks.ts:759); prompt 请求回调
     *                         (绑定版), null=禁用 prompt 检测 (stdin 写后即关)
     */
    public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                     String pluginRoot, String pluginId, String skillRoot,
                                     Integer hookIndex, boolean forceSyncExecution,
                                     AbortController parentAbort,
                                     long defaultTimeoutMs, String hookCwd,
                                     PromptRequester requestPrompt) {
        long diagStartMs = System.currentTimeMillis();
        boolean shouldEmitDiag = hookEvent != null && (hookEvent.type() == HookEventType.SESSION_START
            || hookEvent.type() == HookEventType.SETUP
            || hookEvent.type() == HookEventType.SESSION_END);

        // 1. shell 选择 · 对齐 CC :790-792
        String shellType = hook.shell() != null ? hook.shell() : CommandHook.DEFAULT_SHELL;
        boolean isPowerShell = "powershell".equals(shellType);

        // 2. toHookPath · 对齐 CC :808-811 (Windows bash 才转换; PowerShell 用原生路径)
        Function<String, String> toHookPath = (isWindows() && !isPowerShell)
            ? CommandHookExecutor::windowsPathToPosixPath
            : Function.identity();

        // 3. 命令变换 · ${CLAUDE_PLUGIN_ROOT} / ${CLAUDE_PLUGIN_DATA} / .sh prepend (CC :822-866)
        String command = buildFinalCommand(hook, isPowerShell, isWindows(), pluginRoot, pluginId,
            toHookPath, pathExists, pluginDataDirResolver);
        String finalCommand = applyShellPrefix(command, isPowerShell, envResolver);

        // 4. timeout · 对齐 CC :877-879 · [IMP-HOOKS-S5 D-01] 缺省超时由调用方注入
        long hookTimeoutMs = resolveTimeoutMs(hook.timeout(), defaultTimeoutMs);

        // 5. env · 对齐 CC :882-926
        Map<String, String> env = buildEnv(hookEvent, pluginRoot, pluginId, skillRoot,
            toHookPath, projectRootResolver, pluginDataDirResolver, isPowerShell, hookIndex);

        // 6. cwd fallback · 对齐 CC :931-938 (getCwd 可能指向已删 worktree → 回退 original cwd)
        //   [S4 G09] hookCwd 由调用方注入 (会话 cwd); pathExists 回退语义不变
        String safeCwd = hookCwd != null && Boolean.TRUE.equals(pathExists.apply(hookCwd))
            ? hookCwd : null;

        if (log.isDebugEnabled()) {
            log.debug("CommandHook '{}' 准备执行: shell={} ps={} cmd='{}' timeout={}ms cwd={}",
                hookName, shellType, isPowerShell, finalCommand, hookTimeoutMs, safeCwd);
        }

        // 7. spawn (CC :957-984) — 构造 argv, pwsh 缺失时抛错 (CC :961-966)
        ProcessSpec spec;
        try {
            spec = buildProcessSpec(finalCommand, shellType, isPowerShell, isWindows(), env, safeCwd,
                envResolver, pathExists);
        } catch (IllegalStateException e) {
            // pwsh 找不到 → 等价 CC :961-966 throw, 调用方按 non-blocking 处理
            log.warn("CommandHook '{}' spawn 前置检查失败: {}", hookName, e.getMessage());
            throw e;
        }

        // 8. 启动 + 运行 (async/EPIPE/timeout/prompt 全部在 runProcess 内; requestPrompt 通道,
        //   IMP-RS-01 DEL-01e 补回)
        try {
            HookProcess child = processLauncher.launch(spec);
            return runProcess(child, hook, hookName, jsonInput, hookTimeoutMs,
                forceSyncExecution, shouldEmitDiag, diagStartMs,
                hookEvent, pluginId, parentAbort, requestPrompt);
        } catch (IOException e) {
            // spawn 失败 (ENOENT 等) → 等价 CC childErrorPromise reject (CC :1219-1221, :1283-1318)
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String errOutput = "Error occurred while executing hook command: " + errorMsg;
            log.error("CommandHook '{}' 进程启动失败: {}", hookName, errorMsg);
            return new CommandHookResult("", errOutput, errOutput, 1, false, false);
        }
    }

    /**
     * 执行 command hook · 等价 CC execCommandHook (hooks.ts:747-1335), 支持父 abort 传播.
     *
     * <p><b>parentAbort (EX_E §8.3)</b>: CC execCommandHook 接收 abortSignal 参数
     * (hooks.ts:755), spawn 后经 {@code wrapSpawn(child, signal, ...)} 注册
     * {@code addEventListener('abort', ...)} (ShellCommand.ts:264-265); abort 触发 →
     * {@code #abortHandler()} → {@code kill()} → {@code treeKill(pid, SIGKILL)}
     * (ShellCommand.ts:186-193, :345-347), 结果 {@code aborted: signal.aborted}
     * (hooks.ts:1257). Java 端: {@link AbortController#onCancel} 监听 → destroyForcibly
     * (等价 SIGKILL) + {@code aborted=true} + 'Hook cancelled' (CC ABORT_ERR 文案
     * hooks.ts:1300-1307). null / {@link AbortController#NOOP} = 无父 abort, 行为与
     * 旧 10 参签名一致.
     *
     * <p><b>幂等</b>: abort 与超时并发只终止一次 — listener 内 CAS + 超时路径共用
     * {@code destroyForcibly} (对已终止进程为 no-op), 且完成后 finally 摘除监听面.
     *
     * <p>流程 (CC 行号):
     * <ol>
     *   <li>shell 选择 (CC :790-792): {@code hook.shell ?? 'bash'}; isPowerShell 判定</li>
     *   <li>Windows POSIX 路径转换 (CC :808-811): Windows bash 走 {@link #windowsPathToPosixPath}</li>
     *   <li>命令变换 (CC :822-875): ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA}/${user_config.X} 替换 +
     *       .sh prepend + CLAUDE_CODE_SHELL_PREFIX</li>
     *   <li>timeout (CC :877-879): {@code hook.timeout * 1000} 缺省 10min</li>
     *   <li>env (CC :882-926): CLAUDE_PROJECT_DIR + plugin/skill vars + CLAUDE_ENV_FILE</li>
     *   <li>spawn (CC :957-984): pwsh -NoProfile -NonInteractive -Command / bash -c</li>
     *   <li>async 检测 (CC :995-1030, :1117-1164) → backgrounded 占位</li>
     *   <li>stdin 写入 (CC :1006, :1189-1216) + EPIPE 处理</li>
     *   <li>stdout/stderr 异步收集 + 首行 async 检测 (CC :1068-1165) — prompt 检测已删 (DEL-01e)</li>
     *   <li>错误分流 (CC :1263-1335)</li>
     * </ol>
     *
     * @param hook                CC original: hook (hooks.ts:748); command hook 配置
     * @param hookEvent           CC original: hookEvent (hooks.ts:749); 触发事件
     * @param hookName            hook 名 (日志关联; MatchedHook 未携带, 用生成名)
     * @param jsonInput           stdin JSON payload (hook 输入); 由 {@link #buildJsonInput(HookEvent)} 构造
     * @param pluginRoot          CC original: pluginRoot (hooks.ts:756); 插件根目录, null=跳过变量替换
     * @param pluginId            CC original: pluginId (hooks.ts:757); 插件 ID
     * @param skillRoot           CC original: skillRoot (hooks.ts:758); skill 根目录
     * @param hookIndex           CC original: hookIndex (hooks.ts:754); hook 在事件列表中的索引 (CLAUDE_ENV_FILE), null=不注入
     * @param forceSyncExecution  CC original: forceSyncExecution (hooks.ts:759); true=强制同步不后台化
     * @param parentAbort         CC original: signal (hooks.ts:755); 父 abort 信号, null/NOOP=不监听
     * @return 6 字段 {@link CommandHookResult}
     * @throws IllegalStateException pluginRoot 非 null 但目录不存在 (CC :831-836 语义, 调用方按 non-blocking 处理)
     */
    public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                     String pluginRoot, String pluginId, String skillRoot,
                                     Integer hookIndex, boolean forceSyncExecution,
                                     AbortController parentAbort) {
        // [IMP-HOOKS-S5 D-01] 委托 11 参（缺省超时 = DEFAULT_HOOK_EXECUTION_TIMEOUT_MS 10min）·
        //   SessionEnd 场景走 11 参版本（1500ms 收紧）· [IMP-HOOKS-S9 DEL-01d] prompt 回调已删
        return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
            hookIndex, forceSyncExecution, parentAbort,
            DEFAULT_HOOK_EXECUTION_TIMEOUT_MS);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 运行核心 (stdin / stdout / stderr / async / timeout)
    // ════════════════════════════════════════════════════════════════════════

    private CommandHookResult runProcess(HookProcess child, CommandHook hook, String hookName, String jsonInput,
                                         long hookTimeoutMs, boolean forceSyncExecution,
                                         boolean shouldEmitDiag,
                                         long diagStartMs, HookEvent hookEvent, String pluginId,
                                         AbortController parentAbort,
                                         PromptRequester requestPrompt) {
        // ── config async 路径 · 对齐 CC :995-1030 (hook.async || hook.asyncRewake) && !forceSync ──
        boolean configAsync = (Boolean.TRUE.equals(hook.asyncFlag()) || Boolean.TRUE.equals(hook.asyncRewake()))
            && !forceSyncExecution;
        if (configAsync) {
            try {
                // CC :1006-1008: 先写 stdin + end (尾部 '\n' 保证 bash read -r line 不返回 exit 1)
                OutputStream stdin = child.stdin();
                stdin.write((jsonInput + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                stdin.close();
            } catch (IOException e) {
                return handleStdinError(hookName, e);
            }
            // H10: 真实后台托管 (H2 占位 → 注册/bγpass) · CC :1010-1029
            // 进程可能持续输出 → 必须有人读 stdout/stderr 管道 (64KB 缓冲满会阻塞进程),
            // wrapper 专属 daemon 读取线程累积到 AtomicReference
            AtomicReference<String> asyncOut = new AtomicReference<>("");
            AtomicReference<String> asyncErr = new AtomicReference<>("");
            Thread asyncOutReader = new Thread(() -> readStreamInto(child.stdout(), asyncOut),
                "nexusai-hook-async-out-" + hookName);
            Thread asyncErrReader = new Thread(() -> readStreamInto(child.stderr(), asyncErr),
                "nexusai-hook-async-err-" + hookName);
            asyncOutReader.setDaemon(true);
            asyncErrReader.setDaemon(true);
            asyncOutReader.start();
            asyncErrReader.start();
            // CC :995 processId = 'async_hook_' + child.pid — Java 无 pid, 用 identityHashCode
            String processId = "async_hook_" + System.identityHashCode(child);
            // hookId · 对齐 ExecHttpHook:145 UUID.randomUUID() 模式 (CC hooks.ts:2199)
            String hookId = UUID.randomUUID().toString();
            // [决策 2-3 / D-WF5-05] config-async 执行入口 started · 对齐 CC hooks.ts:2446
            //   emitHookStarted 先于 execCommandHook → background → response 同 id — hookId
            //   与 executeInBackground 注册的 hookId 同源 (R), 单发; 注册侧 (A2) 与
            //   asyncRewake 分支 (B2 合并) 不再补发, 消除「执行入口 S + 注册侧 R」双发孤儿
            //   (WF5-02 ⊕-1 / S4).
            HookEventBus bus = hookEventBus;
            if (bus != null) {
                bus.emitHookStarted(hookId, hookName, ccEventNameOf(hookEvent));
            }
            HookProcessWrapper wrapper = new HookProcessWrapper(child,
                () -> asyncOut.get(), () -> asyncErr.get());
            boolean backgrounded = executeInBackground(processId, hookId, child, wrapper,
                new HookJSONOutput.AsyncHookOutput(true, hookTimeoutMs),
                hookEvent != null ? hookEvent.type() : null, hookName,
                hook.command() != null ? hook.command() : "",
                Boolean.TRUE.equals(hook.asyncRewake()), pluginId,
                asyncOutReader, asyncErrReader);
            if (!backgrounded) {
                // 注册表未注入 (Spring 外手动 new) → 占位降级, Fail Loud. 不能 fall-through
                // 到同步路径: Java 无 CC 的 stdinWritten 守卫 (hooks.ts:1031), 会二次写
                // 已关闭的 stdin 触发 EPIPE (concern H10-7)
                log.warn("HOOK command hook '{}' 配置 async 但 AsyncHookRegistry 未注入 → 占位返回 (响应将丢失)", hookName);
            }
            // CC :1021-1029 {stdout:'', stderr:'', output:'', status:0, backgrounded:true}
            return new CommandHookResult("", "", "", 0, false, true);
        }

        // ── 父 abort 监听 (EX_E §8.3) · 对齐 CC wrapSpawn addEventListener('abort')
        //    (ShellCommand.ts:264-265) ──
        // 语义: CC execCommandHook 把 combined abortSignal 传给 wrapSpawn, abort →
        // #abortHandler → kill() → treeKill(pid, SIGKILL) (ShellCommand.ts:186-193,
        // :345-347); 结果 aborted: signal.aborted (hooks.ts:1257). Java 端 onCancel →
        // destroyForcibly (等价 SIGKILL) + aborted 标记. config async 路径已提前返回
        // (等价 CC background() 摘除 abort listener, ShellCommand.ts:365-366) — async
        // 进程交由 registry/watcher 托管, 不受父 abort 终止.
        // 幂等: abort 与超时并发只终止一次 — listener 内 CAS + destroyForcibly 对已死
        // 进程为 no-op; 同步路径结束后 finally 置 syncDone 摘除后续 abort 响应面.
        AtomicBoolean abortedByParent = new AtomicBoolean(false);
        AtomicBoolean syncDone = new AtomicBoolean(false);
        if (parentAbort != null) {
            parentAbort.onCancel(ac -> {
                if (syncDone.get()) {
                    return; // 已完成 → 不再终止 (超时先行终止或正常退出后的迟来 abort)
                }
                if (abortedByParent.compareAndSet(false, true)) {
                    log.warn("HOOK command hook '{}' 收到父 abort, 终止子进程 (对齐 CC wrapSpawn abort → treeKill SIGKILL)", hookName);
                    child.destroyForcibly();
                }
            });
        }
        // 输出收集器 (CC :1032-1038, :1062) · [IMP-RS-01 DEL-01e 补回] prompt 行过滤恢复
        AtomicReference<String> stdoutRef = new AtomicReference<>("");
        AtomicReference<String> stderrRef = new AtomicReference<>("");
        // 已处理的 prompt 行集合 · 供最终 stdout 过滤 (防泄漏, CC :1243-1249)
        Set<String> processedPromptLines = ConcurrentHashMap.newKeySet();
        // stdout 首行 async 检测结果 (CC :1117-1164) · stdout 读取线程在 data 流首行
        // 检测到 async 且后台化成功 → 置位; 主线程 wait 循环轮询后提前返回 (CC :1146-1150
        // asyncResolve 竞胜 childClosePromise, :1273-1277). null = 未检测到/未后台化.
        AtomicReference<CommandHookResult> backgroundedResult = new AtomicReference<>(null);

        // ── [S4 D-02] sync 路径事件接线 · 对齐 CC :2446 emitHookStarted + :1172-1177
        //    startHookProgressInterval + 各 outcome 分支 emitHookResponse ──
        // 发射点: configAsync 早退之后、线程启动之前 — config-async 的 started/progress/
        // response 由执行入口 (决策 2-3 / B2) / registry / asyncRewake watcher 承担,
        // 此处只覆盖纯 sync 路径 (决策 2-3: config-async 在 execute 入口单发, 不重复).
        // stdout 首行 async 罕见路径 (决策 2-3 / B2): 本段已发 started (hookId=S),
        // 该 hookId 透传给 checkFirstLineAsync 复用为 async 注册 hookId (R==S),
        // 消除 S 孤儿 + S≠R 不配对 (B2 修正 S4 风险登记).
        // 进度 interval: getOutput 闭包读 stdoutRef/stderrRef; liveOutput 门控让
        // readStdout 逐块更新 (CC stdout+=data 等价; bus==null 测试路径不付 O(n²) 拷贝).
        // backgrounded 返回时 interval 由 finally 停止 (CC execCommandHook finally
        // stopProgressInterval :1329 — 后台化后进度由注册侧 interval 承接).
        HookEventBus bus = hookEventBus;
        String syncHookId = null;
        Runnable stopProgress = null;
        AtomicBoolean liveOutput = new AtomicBoolean(false);
        if (bus != null) {
            syncHookId = UUID.randomUUID().toString();
            String ccEvent = ccEventNameOf(hookEvent);
            bus.emitHookStarted(syncHookId, hookName, ccEvent);
            stopProgress = bus.startHookProgressInterval(syncHookId, hookName, ccEvent,
                () -> new HookEventBus.HookProgressOutput(stdoutRef.get(), stderrRef.get(),
                    stdoutRef.get() + stderrRef.get()),
                progressIntervalMs);
            liveOutput.set(true);
        }
        final String stdoutAsyncHookId = syncHookId;

        // 异步收集 stdout (含 prompt 检测 + 首行 async 检测) / stderr · 对齐 CC :1068-1170
        //   [IMP-RS-01 DEL-01e 补回] prompt 检测分支恢复 (requestPrompt 非 null 时激活, CC :1072-1110)
        Thread stdoutThread = new Thread(
            () -> readStdout(child, stdoutRef, stderrRef, processedPromptLines,
                requestPrompt, child::stdin,
                hookName, forceSyncExecution, hookEvent, pluginId,
                hook.command() != null ? hook.command() : "", stdoutAsyncHookId,
                backgroundedResult, liveOutput),
            "nexusai-hook-stdout-" + hookName);
        Thread stderrThread = new Thread(
            () -> readStreamInto(child.stderr(), stderrRef),
            "nexusai-hook-stderr-" + hookName);
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        // 写 stdin · 对齐 CC :1189-1216 (EPIPE 处理; requestPrompt 非 null 保持 open)
        //   [IMP-RS-01 DEL-01e 补回] prompt 回调通道恢复 — requestPrompt 非 null → stdin 保持
        //   open 供 prompt 响应 (CC :1211-1214); null → 写入后立即 close (CC :1212 end 语义)
        try {
            writeStdin(child, jsonInput, requestPrompt);
        } catch (IOException e) {
            child.destroyForcibly();
            // 父 abort 与 stdin 写入并发 → 对齐 CC ABORT_ERR 分支 (hooks.ts:1298-1307):
            // 'Hook cancelled' aborted=true, 而非 EPIPE 误报
            if (abortedByParent.get()) {
                log.warn("HOOK command hook '{}' stdin 写入期间收到父 abort, 返回 cancelled 结果", hookName);
                emitSyncResponse(syncHookId, hookName, ccEventNameOf(hookEvent),
                    "", "Hook cancelled", 1, true); // [S4 D-02] CANCELLED response
                return new CommandHookResult("", "Hook cancelled", "Hook cancelled", 1, true, false);
            }
            syncDone.set(true); // stdin 错误路径提前退出 → 同样摘除 abort 响应面
            CommandHookResult stdinErr = handleStdinError(hookName, e);
            // [S4 D-02] EPIPE/其他 stdin 错误 → status 1 → ERROR response
            emitSyncResponse(syncHookId, hookName, ccEventNameOf(hookEvent),
                stdinErr.stdout(), stdinErr.stderr(), stdinErr.status(), false);
            return stdinErr;
        }

        try {
            // ── 等待进程完成 · 对齐 CC childClosePromise (:1225-1260) + 与
            //    childIsAsyncPromise 的竞速 (:1273-1277 Promise.race) ──
            //    stdout 读取线程在 data 流首行检测到 async 并成功后台化后置
            //    backgroundedResult → 主线程切片轮询立即感知并返回, 不等进程退出/超时
            //    (CC :1137-1151 asyncResolve 竞胜 childClosePromise). 修复 H10-9:
            //    长跑 async hook 不再被同步超时杀死 (旧实现先 waitFor 退出再检测首行).
            long deadline = System.currentTimeMillis() + hookTimeoutMs;
            boolean finished = false;
            while (backgroundedResult.get() == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break; // 超时预算耗尽
                }
                if (child.waitFor(Math.min(100L, remaining), TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }
            // 检测与退出的竞态窗口: 读取线程可能在 waitFor 返回后才完成首行检测 →
            // 再查一次 (CC race 语义: 检测先于 close 即 backgrounded)
            if (backgroundedResult.get() != null) {
                return backgroundedResult.get();
            }
            if (!finished) {
                // 超时 → 强杀 · 对齐 CC wrapSpawn timeout (shellCommand.cleanup)
                log.warn("HOOK command hook '{}' 超时 ({}ms), 终止进程", hookName, hookTimeoutMs);
                child.destroyForcibly();
                if (!child.waitFor(2000, TimeUnit.MILLISECONDS)) {
                    log.warn("HOOK command hook '{}' 超时强杀后未完全退出, 继续收集已输出内容", hookName);
                }
            }
            // 等 streams end (防 close 早于 data 处理竞态, CC :1237-1238).
            // 注意: async 已后台化时主线程已提前返回, 不会在此 join 存活进程.
            stdoutThread.join(2000);
            stderrThread.join(2000);
            // 读取线程已结束 (EOF) → 若其间完成检测仍按 backgrounded 返回
            // (检测发生在读取期间, join 保证可见性)
            if (backgroundedResult.get() != null) {
                return backgroundedResult.get();
            }

            // ── 父 abort 结果判定 · 对齐 CC childClosePromise aborted: signal.aborted
            //    (hooks.ts:1257) + ABORT_ERR 'Hook cancelled' (hooks.ts:1300-1307) ──
            if (abortedByParent.get()) {
                log.warn("HOOK command hook '{}' 因父 abort 终止, 返回 cancelled 结果", hookName);
                emitSyncResponse(syncHookId, hookName, ccEventNameOf(hookEvent),
                    "", "Hook cancelled", 1, true); // [S4 D-02] CANCELLED response
                return new CommandHookResult("", "Hook cancelled", "Hook cancelled", 1, true, false);
            }

            String rawStdout = stdoutRef.get();
            String stderr = stderrRef.get();
            // 过滤已处理的 prompt 行 (CC :1243-1249, 内容匹配防泄漏) ·
            //   [IMP-RS-01 DEL-01e 补回] prompt 行从最终 stdout 移除 (仅 requestPrompt 激活时
            //   processedPromptLines 非空; 空集 → 原样返回, 与删除前行为一致)
            String stdout = processedPromptLines.isEmpty()
                ? rawStdout
                : filterPromptLines(rawStdout, processedPromptLines);
            int status;
            try {
                status = child.exitValue();
            } catch (IllegalThreadStateException e) {
                status = 1;
            }
            // stdout 首行 async 检测已移入 stdout 读取线程 (data 流首行即检测,
            // CC :1117-1164) — 同步路径不再有进程退出后的检测 (被替换, 无兼容残留).

            if (shouldEmitDiag) {
                if (log.isInfoEnabled()) {
                    log.info("HOOK command hook '{}' 执行完成: exit={} stdoutBytes={} stderrBytes={} durationMs={}",
                        hookName, status, stdout.length(), stderr.length(), System.currentTimeMillis() - diagStartMs);
                }
            }
            // [S4 D-02] 正常完成 → SUCCESS(status 0)/ERROR(其他) response (CC :2618-2627/:2672-2681)
            emitSyncResponse(syncHookId, hookName, ccEventNameOf(hookEvent),
                stdout, stderr, status, false);
            return new CommandHookResult(stdout, stderr, stdout + stderr, status, false, false);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("HOOK command hook '{}' 等待被中断", hookName);
            child.destroyForcibly();
            // [S4 D-02] 中断 → aborted → CANCELLED response
            emitSyncResponse(syncHookId, hookName, ccEventNameOf(hookEvent),
                "", "Hook cancelled", 1, true);
            return new CommandHookResult("", "Hook cancelled", "Hook cancelled", 1, true, false);
        } finally {
            // 同步路径结束 → 摘除 abort 响应面 (listener 仍在, 但不再终止子进程).
            // AbortController 无 removeListener API, 用 syncDone 守卫等效 CC
            // ShellCommand #cleanupListeners (ShellCommand.ts:213-216).
            syncDone.set(true);
            // [S4 D-02] 停止进度 interval · 对齐 CC execCommandHook finally
            //   stopProgressInterval (hooks.ts:1329) — backgrounded/abort/正常完成全路径.
            if (stopProgress != null) {
                stopProgress.run();
            }
        }
    }

    /**
     * [S4 D-02] sync 路径 response 发射 · 对齐 CC 各 outcome 分支 emitHookResponse
     * (hooks.ts:2474-2483 aborted / :2618-2627 success / :2672-2681 error).
     *
     * <p>outcome 映射: aborted → CANCELLED (CC 'cancelled'); status==0 → SUCCESS;
     * 其余 → ERROR (CC {@code status === 0 ? 'success' : 'error'}, hooks.ts:2606).
     * hookId 与 sync 路径 started 同源; bus==null (未接线/测试) 静默跳过.
     */
    private void emitSyncResponse(String hookId, String hookName, String ccEvent,
                                  String stdout, String stderr, int status, boolean aborted) {
        HookEventBus bus = hookEventBus;
        if (bus == null || hookId == null) {
            return;
        }
        HookEventBus.HookOutcome outcome = aborted ? HookEventBus.HookOutcome.CANCELLED
            : (status == 0 ? HookEventBus.HookOutcome.SUCCESS : HookEventBus.HookOutcome.ERROR);
        bus.emitHookResponse(new HookEventBus.HookResponseData(
            hookId, hookName, ccEvent, stdout + stderr, stdout, stderr, status, outcome));
    }

    // ════════════════════════════════════════════════════════════════════════
    // async 后台化 · 对齐 CC executeInBackground (hooks.ts:192-263)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 后台化 async hook · 等价 CC {@code executeInBackground} (hooks.ts:192-263).
     *
     * <p>分支 (CC 行号):
     * <ul>
     *   <li>asyncRewake → <b>bypass registry</b> (:205-240): 完成回调读 stdout/stderr →
     *       emitHookResponse → exit 2 → NotificationQueue 入队 task-notification 唤醒模型
     *       (:232-238, wrapInSystemReminder 包装)</li>
     *   <li>否则 → registerPendingAsyncHook (:242-263)</li>
     * </ul>
     *
     * <p><b>Java 差异</b>: CC 先 {@code shellCommand.background(processId)} 成功才注册
     * (:247-253), 失败 return false 走同步路径; Java 进程已 spawn 无 background 失败面,
     * 仅当注册表未注入 (Spring 外) 时返回 false — 调用方按 CC 语义降级.
     *
     * @param processId     CC original: processId (:193); "async_hook_" + pid
     * @param hookId        CC original: hookId (:194)
     * @param child         底层进程 (asyncRewake watcher 等待用)
     * @param wrapper       进程包装 (status/stdout/stderr 面, 供 registry/watcher 读取)
     * @param asyncResponse CC original: asyncResponse (:195); async 声明 (asyncTimeout)
     * @param hookEvent     CC original: hookEvent (:196); 触发事件类型
     * @param hookName      CC original: hookName (:197)
     * @param command       CC original: command (:198)
     * @param asyncRewake   CC original: asyncRewake (:199); true=bypass registry
     * @param pluginId      CC original: pluginId (:200)
     * @param stdoutReader  config-async 路径的 stdout 读取线程 (watcher 完成时 join 排空);
     *                      stdout 首行 async 路径传 null (读取线程以 daemon 持续累积到 ref,
     *                      wrapper 的 supplier 直接读 ref, 无 join 需求)
     * @param stderrReader  同上 (stderr)
     * @return true=已后台化 (调用方返回 backgrounded 结果); false=降级同步路径
     */
    private boolean executeInBackground(String processId, String hookId, HookProcess child,
                                        PendingAsyncHook.AsyncHookProcess wrapper,
                                        HookJSONOutput.AsyncHookOutput asyncResponse,
                                        HookEventType hookEvent, String hookName, String command,
                                        boolean asyncRewake, String pluginId,
                                        Thread stdoutReader, Thread stderrReader) {
        if (asyncRewake) {
            // CC :205-240 asyncRewake bypass: 不注册 registry, 完成回调直接交付
            String ccEventName = ccEventNameOf(hookEvent);
            // [决策 2-3 / D-WF5-05] started 已移执行入口 (configAsync 分支, B2) — 此处不再
            //   补发, 避免 config-async+asyncRewake 双发 started (execute 入口 + 本分支).
            //   completeAsyncRewake 的 response 与执行入口 started 同 hookId 配对.
            Thread watcher = new Thread(
                () -> completeAsyncRewake(hookId, hookName, ccEventName, child, wrapper,
                    stdoutReader, stderrReader),
                "nexusai-hook-reawake-" + hookName);
            watcher.setDaemon(true);
            watcher.start();
            if (log.isInfoEnabled()) {
                log.info("HOOK command hook '{}' asyncRewake → bypass registry, 后台运行 (exit 2 唤醒模型)", hookName);
            }
            return true;
        }
        AsyncHookRegistry registry = asyncHookRegistry;
        if (registry == null) {
            log.warn("HOOK command hook '{}' async 需要 AsyncHookRegistry 但未注入 (Spring 外手动构造?)", hookName);
            return false;
        }
        // CC :242-263: registry 注册 (Java 无 background() 失败面, 直接注册)
        registry.registerPendingAsyncHook(processId, hookId, asyncResponse, hookName,
            ccEventNameOf(hookEvent), command, wrapper, null, pluginId);
        return true;
    }

    /**
     * asyncRewake 完成回调 · 等价 CC :211-240 {@code result.then}.
     *
     * <p>流程 (CC 行号): 等进程退出 (:213) → setImmediate 排空 IO (:214-216, Java 用
     * join 读取线程等价) → 读 stdout/stderr (:217-218) → cleanup (:219) →
     * emitHookResponse ({:221-228, outcome: code===0?'success':'error'}) → exit 2 →
     * enqueuePendingNotification + wrapInSystemReminder (:232-238).
     */
    private void completeAsyncRewake(String hookId, String hookName, String ccEventName,
                                     HookProcess child, PendingAsyncHook.AsyncHookProcess wrapper,
                                     Thread stdoutReader, Thread stderrReader) {
        try {
            while (!child.waitFor(1000, TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // CC :214-216 setImmediate: 让流读取线程排空最后一段输出
        joinQuietly(stdoutReader);
        joinQuietly(stderrReader);
        String stdout = wrapper.stdout() != null ? wrapper.stdout() : "";
        String stderr = wrapper.stderr() != null ? wrapper.stderr() : "";
        wrapper.cleanup();
        int exitCode = wrapper.exitCode();
        HookEventBus.HookOutcome outcome = exitCode == 0
            ? HookEventBus.HookOutcome.SUCCESS : HookEventBus.HookOutcome.ERROR;
        HookEventBus bus = hookEventBus;
        if (bus != null) {
            bus.emitHookResponse(new HookEventBus.HookResponseData(
                hookId, hookName, ccEventName, stdout + stderr, stdout, stderr, exitCode, outcome));
        } else if (log.isDebugEnabled()) {
            log.debug("HOOK command hook '{}' asyncRewake 完成 exit={} (HookEventBus 未注入, 事件未广播)",
                hookName, exitCode);
        }
        if (exitCode == 2) {
            NotificationQueue queue = notificationQueue;
            if (queue != null) {
                // CC :234 wrapInSystemReminder + :232-238 enqueuePendingNotification(task-notification)
                // JS (stderr || stdout) 真值语义 → Java 非空串优先 stderr
                String blockingText = (stderr != null && !stderr.isEmpty()) ? stderr : stdout;
                queue.enqueuePendingNotification(new NotificationQueue.QueueItem(
                    "<system-reminder>\nStop hook blocking error from command \"" + hookName + "\": "
                        + blockingText + "\n</system-reminder>",
                    "task-notification"));
                if (log.isInfoEnabled()) {
                    log.info("HOOK command hook '{}' exit=2 → 已入队 task-notification 唤醒模型", hookName);
                }
            } else {
                log.warn("HOOK command hook '{}' exit=2 但 NotificationQueue 未注入, 唤醒通知丢失", hookName);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("HOOK command hook '{}' asyncRewake 后台完成: exit={} stdoutBytes={} stderrBytes={}",
                hookName, exitCode, stdout.length(), stderr.length());
        }
    }

    /** 静默 join (≤2s) · 读取线程排空用; 中断则恢复中断位. */
    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** HookEventType → CC 事件名; null → "Unknown" (防御, HookEvent 构造器已禁止 null type). */
    private static String ccEventNameOf(HookEventType hookEvent) {
        return hookEvent != null ? hookEvent.ccName() : "Unknown";
    }

    /** 从 stdout 首行 async JSON 取 asyncTimeout · CC :1144-1145 (parsed 整体作为 asyncResponse). */
    private static Long asyncTimeoutOf(String firstLine) {
        try {
            JsonNode node = MAPPER.readTree(firstLine);
            return node.has("asyncTimeout") && node.get("asyncTimeout").isNumber()
                ? node.get("asyncTimeout").asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** HookEvent → CC 事件名 · [IMP-HOOKS-S5 并行协调] S4 的 emitSyncResponse 调用点传
     *  HookEvent（runProcess 参数），补重载使两处调用形态都编译（HookEventType 版保留给
     *  executeInBackground）。 */
    private static String ccEventNameOf(HookEvent hookEvent) {
        return hookEvent != null && hookEvent.type() != null ? hookEvent.type().ccName() : "Unknown";
    }

    /**
     * 写 jsonInput 到 stdin · 对齐 CC :1189-1216 (EPIPE 处理 + 写后 end)。
     * [IMP-RS-01 DEL-01e 补回] requestPrompt 非 null → stdin 保持 open 供 prompt 响应
     * (CC :1211-1214 {@code if (!requestPrompt) child.stdin.end()}); null → 写入后立即 end.
     */
    private void writeStdin(HookProcess child, String jsonInput, PromptRequester requestPrompt) throws IOException {
        OutputStream stdin = child.stdin();
        stdin.write((jsonInput + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        if (requestPrompt == null) {
            stdin.close();
        }
        // requestPrompt != null → stdin 保持 open, 由 readStdout 线程写 prompt 响应 (CC :1212-1214)
    }
    /**
     * 读取 stdout · 对齐 CC stdout 'data' handler (:1068-1165) + prompt 检测 (:1072-1110)
     * + 首行 async 检测 (:1117-1164).
     * [IMP-RS-01 DEL-01e 补回] prompt 检测分支恢复 — requestPrompt 非 null 时激活
     * (对齐 CC :1072-1110), null 时行为与 CC 回调 undefined 时一致 (不检测, stdin 写后即关).
     *
     * <p>逐块读取 (对齐 CC data 事件语义): 每块先做 prompt 行检测 (行缓冲, 末行不完整
     * 留待下块, CC :1076-1078 lineBuffer), 再做首行 async 检测 — 只查累计 stdout 的
     * <b>首行</b> (CC :1122 firstLineOf), 首行含 {@code '}'} 才算完整 (CC :1123-1124
     * 无则等更多数据), 命中且后台化成功 → 置 {@code backgroundedResult} 供主线程
     * wait 循环提前返回.
     *
     * <p><b>prompt 检测 (CC :1072-1110)</b>: 命中 {@code {prompt, message, options}} 行 →
     * 记录到 {@code processedPromptLines} (供最终 stdout 过滤, CC :1243-1249) →
     * 同步调 {@code requestPrompt} 并把 {@link PromptResponse} JSON 写回 stdin
     * (CC :1093-1096; Java 回调同步 → 天然串行, 等价 CC promptChain 串行语义);
     * 回调失败 → 关闭 stdin 防 hook 挂起 (CC :1098-1102).
     *
     * <p>stdoutRef 更新: 后台化成功后每块更新 (async registry / 进度定时器轮询读取
     * 累积输出, CC TaskOutput 语义); [S4 D-02] sync 路径启动进度定时器时经
     * {@code liveOutput} 门控同样逐块更新 (CC stdout+=data 等价, 进度增量可见);
     * 未后台化且无进度定时器仅 EOF 一次性写入 (bus==null 测试路径不付每块 O(n²) 拷贝).
     */
    private void readStdout(HookProcess child, AtomicReference<String> stdoutRef,
                            AtomicReference<String> stderrRef, Set<String> processedPromptLines,
                            PromptRequester requestPrompt, Supplier<OutputStream> stdinSupplier,
                            String hookName, boolean forceSyncExecution, HookEvent hookEvent,
                            String pluginId, String hookCommand, String syncHookId,
                            AtomicReference<CommandHookResult> backgroundedResult,
                            AtomicBoolean liveOutput) {
        StringBuilder sb = new StringBuilder();
        StringBuilder lineBuffer = new StringBuilder();
        boolean initialResponseChecked = false;
        boolean asyncBackgrounded = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.stdout(), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                String data = new String(buf, 0, n);
                sb.append(data);
                // prompt 检测 (CC :1072-1110) · 逐行 trim 并 safeParse
                // {prompt, message, options:[{key,label,description?}]}
                // (对齐 CC promptRequestSchema, types/hooks.ts:28-40); 命中 → 记录到
                // processedPromptLines (供最终 stdout 过滤) → 调 requestPrompt → 写回 stdin.
                if (requestPrompt != null) {
                    lineBuffer.append(data);
                    String buffered = lineBuffer.toString();
                    int lastNl = buffered.lastIndexOf('\n');
                    String complete = lastNl >= 0 ? buffered.substring(0, lastNl) : "";
                    lineBuffer.setLength(0);
                    lineBuffer.append(lastNl >= 0 ? buffered.substring(lastNl + 1) : buffered);
                    if (!complete.isEmpty()) {
                        for (String line : complete.split("\n", -1)) {
                            String trimmed = line.trim();
                            if (trimmed.isEmpty()) {
                                continue;
                            }
                            PromptRequest req = tryParsePromptRequest(trimmed);
                            if (req != null) {
                                processedPromptLines.add(trimmed);
                                if (log.isDebugEnabled()) {
                                    log.debug("HOOK command hook '{}' 检测到 prompt 请求: {}", hookName, trimmed);
                                }
                                try {
                                    PromptResponse resp = requestPrompt.request(req);
                                    OutputStream stdin = stdinSupplier.get();
                                    synchronized (stdin) {
                                        stdin.write((jsonStringify(resp) + "\n").getBytes(StandardCharsets.UTF_8));
                                        stdin.flush();
                                    }
                                } catch (Exception e) {
                                    // 用户取消/prompt 失败 → 关闭 stdin 防 hook 挂起 (CC :1098-1102)
                                    log.warn("HOOK command hook '{}' prompt 处理失败: {}", hookName, e.toString());
                                    try {
                                        stdinSupplier.get().close();
                                    } catch (IOException ignored) {
                                        // 已关闭/损坏, 忽略
                                    }
                                }
                            }
                        }
                    }
                }
                // stdout 首行 async 检测 (CC :1117-1164) · 只查首行, 首行含 '}' 才算完整
                if (!initialResponseChecked) {
                    String firstLine = firstLineOf(sb.toString()).trim();
                    if (firstLine.contains("}")) {
                        initialResponseChecked = true;
                        if (checkFirstLineAsync(child, firstLine, hookName, forceSyncExecution,
                            hookEvent, pluginId, hookCommand, syncHookId, sb, stdoutRef, stderrRef, backgroundedResult)) {
                            asyncBackgrounded = true;
                        }
                    }
                    // 首行尚未完整 (无 '}') → 等更多数据 (CC :1123-1124 return)
                }
                if (asyncBackgrounded || liveOutput.get()) {
                    // 后台化 / sync 进度定时器激活 → 持续更新 ref → registry/定时器可见累积输出
                    stdoutRef.set(sb.toString());
                }
            }
        } catch (IOException e) {
            // 进程被 destroyForcibly 后流关闭 → 正常 (CC 无此分支, Java 流语义差异)
            if (log.isDebugEnabled()) {
                log.debug("HOOK command hook '{}' stdout 读取结束: {}", hookName, e.getMessage());
            }
        }
        stdoutRef.set(sb.toString());
    }

    /**
     * stdout 首行 async 检测 · 对齐 CC :1117-1164 (data 流首行即检测, 不等进程退出).
     *
     * <p>WHY (H10-9 修复): 旧实现先 {@code waitFor} 进程退出再检测首行 — 长跑 async hook
     * 会在检测前被同步超时杀死. 本方法在 stdout 读取线程的首块数据到达时即检测:
     * 命中且后台化成功 → 置 {@code backgroundedResult} 供主线程 wait 循环提前返回
     * (等价 CC asyncResolve 竞胜 childClosePromise, :1273-1277).
     *
     * <p>分支 (CC 行号): 非 async 首行 → 继续正常同步处理 (:1155-1157); async 但
     * forceSyncExecution → 等待完成不后台化 (:1152-1154); async 且后台化成功 →
     * 部分输出快照入 result (:1146-1150); 注册表未注入 → 降级同步路径 (:1151).
     *
     * @return true = 已后台化 (读取线程应持续更新 stdoutRef 供 registry 轮询)
     */
    private boolean checkFirstLineAsync(HookProcess child, String firstLine, String hookName,
                                        boolean forceSyncExecution, HookEvent hookEvent, String pluginId,
                                        String hookCommand, String syncHookId, StringBuilder stdoutSb,
                                        AtomicReference<String> stdoutRef, AtomicReference<String> stderrRef,
                                        AtomicReference<CommandHookResult> backgroundedResult) {
        if (!isAsyncJson(firstLine)) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK command hook '{}' stdout 首行非 async, 继续正常同步处理", hookName);
            }
            return false;
        }
        if (forceSyncExecution) {
            // CC :1152-1154: async 声明但 forceSyncExecution=true → 等待完成 (不后台化)
            if (log.isDebugEnabled()) {
                log.debug("HOOK command hook '{}' 检测到 async 但 forceSyncExecution=true, 等待完成", hookName);
            }
            return false;
        }
        // H10: 真实注册进 AsyncHookRegistry (H2 占位 → 注册). 注意 CC :1137-1151 stdout
        // 路径 executeInBackground <b>不传 asyncRewake</b> → 只走 registry.
        // [决策 2-3 / D-WF5-05] sync 路径 started 已发 (hookId=S, 本方法调用前);
        // 复用 S 为 async 注册 hookId (R==S) — 消除 S 孤儿 + S≠R 不配对 (B2).
        // bus==null (未接线/测试) 时 syncHookId 为 null → 回退新 UUID (注册仍需要 hookId).
        String processId = "async_hook_" + System.identityHashCode(child);
        String hookId = syncHookId != null ? syncHookId : UUID.randomUUID().toString();
        HookJSONOutput.AsyncHookOutput asyncResponse =
            new HookJSONOutput.AsyncHookOutput(true, asyncTimeoutOf(firstLine));
        HookProcessWrapper wrapper = new HookProcessWrapper(child,
            () -> stdoutRef.get(), () -> stderrRef.get());
        boolean backgrounded = executeInBackground(processId, hookId, child, wrapper,
            asyncResponse, hookEvent != null ? hookEvent.type() : null, hookName,
            hookCommand, false, pluginId, null, null);
        if (backgrounded) {
            // CC :1146-1150 asyncResolve({stdout, stderr, output, status: 0}) — 检测时刻的部分输出
            String partialStdout = stdoutSb.toString();
            String partialStderr = stderrRef.get() != null ? stderrRef.get() : "";
            backgroundedResult.set(new CommandHookResult(
                partialStdout, partialStderr, partialStdout + partialStderr, 0, false, true));
            if (log.isInfoEnabled()) {
                log.info("HOOK command hook '{}' stdout 首行 async → 已后台化 (registry)", hookName);
            }
            return true;
        }
        // CC :1151 background()==false 语义: 注册表未注入 → 继续正常同步处理
        log.warn("HOOK command hook '{}' stdout 首行 async 但 AsyncHookRegistry 未注入, 按同步路径处理", hookName);
        return false;
    }

    /** 读 stderr 到 AtomicReference · 对齐 CC stderr 'data' handler (:1167-1170). */
    private static void readStreamInto(InputStream in, AtomicReference<String> ref) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                // 每块更新 ref → async registry / 进度定时器可见累积输出 (CC TaskOutput
                // 累积语义; 同步路径 EOF 时最终值覆盖, 行为不变)
                ref.set(sb.toString());
            }
        } catch (IOException e) {
            // 流关闭 → 正常
        }
        ref.set(sb.toString());
    }

    /** stdin 写入错误分流 · 对齐 CC :1288-1299 (EPIPE) + :1309-1317 (其他). */
    private static CommandHookResult handleStdinError(String hookName, IOException e) {
        if (isEpipeError(e)) {
            log.warn("HOOK command hook '{}' EPIPE: 命令提前关闭 stdin", hookName);
            String errMsg = "Hook command closed stdin before hook input was fully written (EPIPE)";
            return new CommandHookResult("", errMsg, errMsg, 1, false, false);
        }
        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String errOutput = "Error occurred while executing hook command: " + errorMsg;
        log.error("HOOK command hook '{}' stdin 写入失败: {}", hookName, errorMsg);
        return new CommandHookResult("", errOutput, errOutput, 1, false, false);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 纯逻辑静态工具 (可直接单测) · 对齐 CC 各辅助函数
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Windows 路径 → POSIX 路径 · 对齐 CC {@code windowsPaths.ts:128-145}
     * {@code windowsPathToPosixPath} (纯 JS 无 shell-out).
     *
     * <p>WHY: Windows Git Bash 无法解析 Windows 路径 (hooks.ts:797-799). 转换规则:
     * <ul>
     *   <li>UNC {@code \\server\share} → {@code //server/share} (仅反斜杠转正斜杠)</li>
     *   <li>盘符 {@code C:\Users\foo} → {@code /c/Users/foo} (盘符小写)</li>
     *   <li>已有 POSIX/相对 → 仅反斜杠转正斜杠</li>
     * </ul>
     *
     * @param p Windows 或 POSIX 路径
     * @return POSIX 路径
     */
    public static String windowsPathToPosixPath(String p) {
        if (p == null) return null;
        if (p.startsWith("\\\\")) {
            return p.replace("\\", "/");
        }
        if (p.length() >= 3 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':'
            && (p.charAt(2) == '\\' || p.charAt(2) == '/')) {
            char drive = Character.toLowerCase(p.charAt(0));
            return "/" + drive + p.substring(2).replace("\\", "/");
        }
        return p.replace("\\", "/");
    }

    /**
     * 当前平台是否 Windows · CC getPlatform() === 'windows' (hooks.ts:779).
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 命令变换 · 对齐 CC :822-866.
     *
     * <p>WHY: ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} 替换 (pluginRoot 非 null 时),
     * ${user_config.X} 替换 (H2 settings 来源 pluginOpts 空, 未知 key 替换为空),
     * Windows bash .sh 脚本自动前置 {@code bash }.
     *
     * @param hook               hook 配置
     * @param isPowerShell       是否 PowerShell (跳过 .sh prepend, CC :862)
     * @param pluginRoot         插件根目录; null → 跳过变量替换
     * @param pluginId           插件 ID (CLAUDE_PLUGIN_DATA)
     * @param toHookPath         Windows bash → POSIX 转换器
     * @param pathExists         路径存在判断 (pluginRoot 不存在 → throw)
     * @param pluginDataDirResolver pluginId → 数据目录
     * @return 替换后的命令串
     * @throws IllegalStateException pluginRoot 非 null 但目录不存在 (CC :831-836)
     */
    public static String buildFinalCommand(CommandHook hook, boolean isPowerShell, boolean isWindows,
                                           String pluginRoot, String pluginId,
                                           Function<String, String> toHookPath, Function<String, Boolean> pathExists,
                                           Function<String, String> pluginDataDirResolver) {
        String command = hook.command() != null ? hook.command() : "";
        if (pluginRoot != null) {
            if (!Boolean.TRUE.equals(pathExists.apply(pluginRoot))) {
                // CC :831-836: 插件目录被删 → throw (防 exit-2-missing-script 误判为 block)
                throw new IllegalStateException(
                    "Plugin directory does not exist: " + pluginRoot
                        + (pluginId != null ? " (" + pluginId + " — run /plugin to reinstall)" : ""));
            }
            // CC :844-845 内联 ROOT 替换 (String.replace 字面量替换, 防 $ 模式解释; PS 原生路径)
            command = command.replace("${CLAUDE_PLUGIN_ROOT}", toHookPath.apply(pluginRoot));
            if (pluginId != null) {
                String dataPath = toHookPath.apply(pluginDataDirResolver.apply(pluginId));
                command = command.replace("${CLAUDE_PLUGIN_DATA}", dataPath);
            }
            if (pluginId != null) {
                // CC :855 substituteUserConfigVariables — H2 settings 来源 pluginOpts 空,
                // 未知 key 替换为空串 (CC loadPluginOptions 未配置会 throw, H2 简化).
                command = substituteUserConfigVariables(command, Map.of());
            }
        }
        // Windows bash .sh prepend · 对齐 CC :862-866 (/\.sh(\s|$|")/)
        if (isWindows && !isPowerShell) {
            String trimmed = command.trim();
            if (Pattern.compile("\\.sh(\\s|$|\")").matcher(trimmed).find()
                && !trimmed.startsWith("bash ")) {
                command = "bash " + command;
            }
        }
        return command;
    }

    /**
     * ${user_config.X} 替换 · 对齐 CC substituteUserConfigVariables (hooks.ts:822-855).
     *
     * <p>H2 简化: pluginOpts 仅承载已配置的 user_config 键值; 未配置的 key 替换为空串
     * (CC 对缺失 key 会 throw, H2 不做 — settings 来源 pluginRoot=null 此方法不会触发).
     */
    public static String substituteUserConfigVariables(String command, Map<String, String> pluginOpts) {
        if (command == null) return null;
        Map<String, String> opts = pluginOpts != null ? pluginOpts : Map.of();
        // ${user_config.X} → opts.get(X) ?? ''
        Pattern p = Pattern.compile("\\$\\{user_config\\.([A-Za-z0-9_]+)\\}");
        var m = p.matcher(command);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = opts.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * CLAUDE_CODE_SHELL_PREFIX · 对齐 CC :872-875.
     *
     * <p>!isPowerShell && env CLAUDE_CODE_SHELL_PREFIX 存在 → formatShellPrefixCommand
     * (POSIX 单引号包装). PowerShell 忽略 (CC :870-871).
     *
     * @param command      原始命令
     * @param isPowerShell 是否 PowerShell
     * @param envResolver  env 取值器
     * @return 加了 prefix 的命令; 无 prefix → 原样
     */
    public static String applyShellPrefix(String command, boolean isPowerShell, Function<String, String> envResolver) {
        String prefix = envResolver != null ? envResolver.apply("CLAUDE_CODE_SHELL_PREFIX") : null;
        if (!isPowerShell && prefix != null && !prefix.isBlank()) {
            return formatShellPrefixCommand(prefix, command);
        }
        return command;
    }

    /**
     * 格式化 shell prefix · 对齐 CC {@code bash/shellPrefix.ts} {@code formatShellPrefixCommand}.
     *
     * <p>在最后一个 " -" 前切分可执行路径与参数, 分别 POSIX 单引号包装.
     * 例: prefix="bash" → {@code 'bash' 'cmd'}; prefix="C:\Program Files\Git\bin\bash.exe -c"
     * → {@code 'C:\Program Files\Git\bin\bash.exe' -c 'cmd'}.
     */
    public static String formatShellPrefixCommand(String prefix, String command) {
        int spaceBeforeDash = prefix.lastIndexOf(" -");
        if (spaceBeforeDash > 0) {
            String execPath = prefix.substring(0, spaceBeforeDash);
            String args = prefix.substring(spaceBeforeDash + 1);
            return shellQuote(execPath) + " " + args + " " + shellQuote(command);
        }
        return shellQuote(prefix) + " " + shellQuote(command);
    }

    /** POSIX 单引号包装 · shell-quote 的 quote (单引号转义 {@code '} → {@code '\''}). */
    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 构造 env · 对齐 CC :882-926.
     *
     * <p>CLAUDE_PROJECT_DIR + NEXUSAI_PROJECT_DIR 恒注入（双注入同一项目根，决策 D1/D6）;
     * pluginRoot → CLAUDE_PLUGIN_ROOT/CLAUDE_PLUGIN_DATA;
     * skillRoot → CLAUDE_PLUGIN_ROOT; 非 PS + SessionStart/Setup/CwdChanged/FileChanged +
     * hookIndex 非 null → CLAUDE_ENV_FILE.
     */
    public static Map<String, String> buildEnv(HookEvent hookEvent, String pluginRoot, String pluginId,
                                               String skillRoot, Function<String, String> toHookPath,
                                               Supplier<String> projectRootResolver,
                                               Function<String, String> pluginDataDirResolver,
                                               boolean isPowerShell, Integer hookIndex) {
        Map<String, String> env = new HashMap<>();
        String projectDir = toHookPath.apply(projectRootResolver.get());
        // 双注入：CLAUDE_PROJECT_DIR 对齐 CC hook env 协议（老 CC 脚本读取）；NEXUSAI_PROJECT_DIR
        // nexusai 命名（决策 D1/D6 自有根语义），同一路径两键，兼容两类脚本。
        env.put("CLAUDE_PROJECT_DIR", projectDir);
        env.put("NEXUSAI_PROJECT_DIR", projectDir);
        if (pluginRoot != null) {
            env.put("CLAUDE_PLUGIN_ROOT", toHookPath.apply(pluginRoot));
            if (pluginId != null) {
                env.put("CLAUDE_PLUGIN_DATA", toHookPath.apply(pluginDataDirResolver.apply(pluginId)));
            }
        }
        if (skillRoot != null) {
            // CC :907-909: skill 与 plugin 共用 CLAUDE_PLUGIN_ROOT 名
            env.put("CLAUDE_PLUGIN_ROOT", toHookPath.apply(skillRoot));
        }
        // CLAUDE_ENV_FILE · 对齐 CC :917-926 (仅 bash; 事件∈4 类; hookIndex 已定义)
        if (!isPowerShell && hookIndex != null && hookEvent != null) {
            HookEventType t = hookEvent.type();
            if (t == HookEventType.SESSION_START || t == HookEventType.SETUP
                || t == HookEventType.CWD_CHANGED || t == HookEventType.FILE_CHANGED) {
                env.put("CLAUDE_ENV_FILE", getHookEnvFilePath(hookEvent.type(), hookIndex));
            }
        }
        return env;
    }

    /**
     * CLAUDE_ENV_FILE 路径 · 对齐 CC getHookEnvFilePath (hooks.ts:917-926 +
     * sessionEnvironment.ts:25-33).
     *
     * <p>H2 简化: 放系统临时目录 {@code nexusai-hooks/<Event>-hook-<index>.sh}.
     * CC 走 getSessionEnvironmentScript 实际 session 环境脚本路径; Java 端会话脚本
     * 系统未建成, 此路径为占位 (hookIndex 接线后真实注入).
     *
     * <p><b>目录创建</b>: CC getHookEnvFilePath 先 {@code mkdir(sessionEnvDir,
     * recursive:true)} 再返回路径 — hook 直接 {@code echo >> "$CLAUDE_ENV_FILE"} 写入,
     * 目录不存在则写入失败 (sessionEnvironment.ts:29-31). Java 同语义: 返回前确保父
     * 目录存在; 创建失败仅 warn (hook 侧写入失败仍以非零退出 → non_blocking_error,
     * 与 CC 抛错降级的结果类别一致).
     */
    public static String getHookEnvFilePath(HookEventType eventType, int hookIndex) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "nexusai-hooks");
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("HOOK 无法创建 CLAUDE_ENV_FILE 目录: {} (hook 写入环境变量将失败)", dir.getAbsolutePath());
        }
        return new File(dir, toCcEventName(eventType) + "-hook-" + hookIndex + ".sh").getPath();
    }

    /**
     * 构造 spawn argv · 对齐 CC :957-984.
     *
     * <p>PowerShell: {@code [pwsh, -NoProfile, -NonInteractive, -Command, cmd]}
     * (powershellProvider.ts:11-13 buildPowerShellArgs); Windows bash: {@code [gitBash, -c, cmd]};
     * Unix bash: {@code [/bin/sh, -c, cmd]} (shell:true 语义).
     *
     * @throws IllegalStateException shell=powershell 但找不到 pwsh/powershell (CC :961-966)
     */
    public static ProcessSpec buildProcessSpec(String finalCommand, String shellType, boolean isPowerShell,
                                               boolean isWindows, Map<String, String> env, String cwd,
                                               Function<String, String> envResolver, Function<String, Boolean> pathExists) {
        if (isPowerShell) {
            String pwshPath = resolvePowerShellPath(envResolver, pathExists);
            return new ProcessSpec(List.of(pwshPath, "-NoProfile", "-NonInteractive", "-Command", finalCommand),
                env, cwd);
        }
        if (isWindows) {
            String gitBashPath = resolveGitBashPath(envResolver, pathExists);
            return new ProcessSpec(List.of(gitBashPath, "-c", finalCommand), env, cwd);
        }
        // Unix bash：探测 bash/zsh（对齐 CC hooks 默认 shell 'bash' 语义 + findSuitableShell，
        // 不再硬编码 /bin/sh）。找不到 → ShellResolver 抛 CC 同款显式错误。
        return new ProcessSpec(List.of(
            ShellResolver.resolveShell(envResolver, pathExists, false), "-c", finalCommand), env, cwd);
    }

    /**
     * 解析 pwsh 路径 · 对齐 CC getCachedPowerShellPath.
     *
     * <p>优先 {@code CLAUDE_CODE_POWERSHELL_PATH} env; 否则 PATH 找 pwsh / powershell;
     * 都找不到 → throw (CC :961-966).
     *
     * @throws IllegalStateException 无 PowerShell 可执行文件
     */
    public static String resolvePowerShellPath(Function<String, String> envResolver, Function<String, Boolean> pathExists) {
        Function<String, String> env = envResolver != null ? envResolver : System::getenv;
        String envPath = env.apply("CLAUDE_CODE_POWERSHELL_PATH");
        if (envPath != null && !envPath.isBlank() && Boolean.TRUE.equals(pathExists.apply(envPath))) {
            return envPath;
        }
        for (String exe : List.of("pwsh", "powershell")) {
            String found = findExecutableOnPath(exe, env);
            if (found != null) return found;
        }
        throw new IllegalStateException(
            "Hook has shell: 'powershell' but no PowerShell executable (pwsh or powershell) was found on PATH. "
                + "Install PowerShell, or remove \"shell\": \"powershell\" to use bash.");
    }

    /**
     * 解析 Git Bash 路径 · 对齐 CC findGitBashPath (windowsPaths.ts:98-125).
     *
     * <p>优先 {@code CLAUDE_CODE_GIT_BASH_PATH} env (且存在); 否则 PATH 找 git → 推导
     * {@code <gitDir>/bin/bash.exe}; 兜底返回 "bash" (let PATH 解析).
     */
    public static String resolveGitBashPath(Function<String, String> envResolver, Function<String, Boolean> pathExists) {
        Function<String, String> env = envResolver != null ? envResolver : System::getenv;
        Function<String, Boolean> exists = pathExists != null ? pathExists : CommandHookExecutor::defaultPathExists;
        String envPath = env.apply("CLAUDE_CODE_GIT_BASH_PATH");
        if (envPath != null && !envPath.isBlank() && Boolean.TRUE.equals(exists.apply(envPath))) {
            return envPath;
        }
        String gitPath = findExecutableOnPath("git", env);
        if (gitPath != null) {
            String bashPath = deriveBashFromGit(gitPath);
            if (bashPath != null && Boolean.TRUE.equals(exists.apply(bashPath))) {
                return bashPath;
            }
        }
        return "bash";
    }

    /**
     * exit code → HookResult 分流 · 对齐 CC runHook (hooks.ts:2616-2697).
     *
     * <p>WHY: status 0 → 解析 stdout JSON (continue/decision); status 2 → blocking stop
     * (stderr 注入 LLM); 其他 → non_blocking (proceed + 日志). backgrounded → proceed
     * (异步后台, 不阻断主流程).
     *
     * @param result      {@link CommandHookExecutor#execute} 的返回
     * @param hookCommand CC hook.command (blockingError.command 字段, types/hooks.ts:245)
     * @return 聚合进 GenericHook.HookResult 语义的结果
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, String hookCommand) {
        return toHookResult(result, hookCommand, null);
    }

    /**
     * [H3 v2 修复] Gap 3 (H3-GAP-4): 接线 expectedHookEvent · 对齐 CC hooks.ts:583-590
     * 非空 expectedHookEvent 且 hookSpecificOutput.hookEventName 不匹配 → throw (fail-loud).
     * 此前调用方传 null 跳过校验, hook 返回错误事件名时静默接受. 现由调用方传入实际事件名
     * (HookEvent.type().ccName()), mismatch 时 HookOutputParser.processHookJSONOutput 抛异常,
     * 本方法 catch 后降级 proceed (non_blocking, 对齐 CC runHook catch :2698-2729).
     *
     * @param result            {@link CommandHookExecutor#execute} 的返回
     * @param hookCommand       命令串 (blockingError.command / 日志)
     * @param expectedHookEvent CC 事件名 (如 "PreToolUse"); null/空 → 跳过校验 (无事件上下文时)
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, String hookCommand,
                                                      String expectedHookEvent) {
        return toHookResult(result, hookCommand, null, expectedHookEvent, null, null, 0L);
    }

    /**
     * [H3 v3 修复] 7 参全量版本 · 载荷透传 (hookName/toolUseID/hookEvent/durationMs) ·
     * 对齐 CC executeHooks command 分支 processHookJSONOutput 调用载荷 (hooks.ts:2544-2557).
     *
     * <p>WHY (Gap 2 / Gap 3): 旧 3 参版本无事件上下文 (hookName/toolUseID/hookEvent=null),
     * 且无法承载 durationMs → message attachment 元数据缺失、载荷薄于 CC. 本版本接收完整
     * 上下文 + 计时, 供 {@link HookRegistry#executeConfiguredCommand} 接线真实事件.
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, String hookCommand,
                                                      String hookName, String expectedHookEvent,
                                                      String toolUseID, String hookEvent,
                                                      long durationMs) {
        // [S4 G01/G02/G03/G17] 委托统一核心 (CC runHook 分流序) — 旧 exit-code 先分流实现已删
        return toHookResultCore(result, hookCommand, hookName, expectedHookEvent,
            toolUseID, hookEvent, durationMs, null);
    }

    /**
     * [H14 v3 Gap④] toHookResult (String command) + watchPaths 收集重载.
     *
     * <p>WHY: 同 {@link #toHookResult(CommandHookResult, CommandHook, String, List)} —
     * CwdChanged/FileChanged command hook 结果的 watchPaths 需要收集供 watcher 动态扩展。
     * status 0 分支把 watchPathsOut 透传给 parseStdoutJson 4 参重载。
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, String hookCommand,
                                                      String expectedHookEvent,
                                                      java.util.List<String> watchPathsOut) {
        // [S4 G01/G02/G03/G17] 委托统一核心 (watchPaths 收集语义保留)
        return toHookResultCore(result, hookCommand, null, expectedHookEvent, null, null, 0L,
            watchPathsOut);
    }

    /**
     * [S4 G01/G02/G03/G17] toHookResult 统一核心 · 对齐 CC runHook command 分支
     * (hooks.ts:2446-2730, 自验 2026-08-14) 完整分流序.
     *
     * <p><b>分流顺序 (CC 行号)</b>:
     * <ol>
     *   <li>null → proceed; backgrounded → proceed (CC :2465-2471 backgrounded → success)</li>
     *   <li>aborted → CANCELLED + hook_cancelled attachment (CC :2473-2497)</li>
     *   <li>parseHookOutput(stdout): validationError → NON_BLOCKING_ERROR +
     *       hook_non_blocking_error ({@code `JSON validation failed: ${validationError}`},
     *       stdout 原文, exitCode=1, CC :2504-2531)</li>
     *   <li>SyncHookOutput json → processHookJSONOutput 任意 status (CC :2533-2613;
     *       exit2+合法 JSON 不再无条件 blocking — 行为变化登记); AsyncHookOutput →
     *       proceed 等价 success (CC :2535-2541); processHookJSONOutput 异常 → catch
     *       {@code `Failed to run:`} attachment (CC :2698-2729)</li>
     *   <li>非 json + status==0 → SUCCESS + hook_success content=stdout.trim()
     *       (CC :2617-2645; 空 stdout 也产 hook_success, 旧 blank 早退已删)</li>
     *   <li>非 json + status==2 → BLOCKING (CC :2648-2668)</li>
     *   <li>非 json + 其他 → NON_BLOCKING_ERROR + hook_non_blocking_error
     *       ({@code `Failed with non-blocking status code: ${stderr.trim()||'No stderr output'}`},
     *       stdout 原文, exitCode=status, CC :2670-2697)</li>
     * </ol>
     *
     * <p><b>[S4 行为变化登记]</b>: 旧 Java 按 exit-code 先分流 (status2 先于 JSON 解析) —
     * 现对齐 CC validationError/json 先于 status 检查; 非 0/2 从静默 proceed 改为
     * NON_BLOCKING_ERROR + attachment; 纯文本 exit0 从 proceed 改为 hook_success。
     *
     * @param result      {@link CommandHookExecutor#execute} 的返回 (可 null)
     * @param hookCommand CC hook.command (blockingError.command / attachment.command)
     * @param hookName    CC hookName (attachment.hookName; 无上下文传 null)
     * @param expectedHookEvent CC 事件名 (如 "PreToolUse"); null/空 → 跳过校验
     * @param toolUseID   工具调用 ID (attachment.toolUseID; 无上下文传 null)
     * @param hookEvent   CC hookEvent 名 (attachment.hookEvent; 无上下文传 null)
     * @param durationMs  hook 执行耗时毫秒 (attachment.durationMs; 无计时传 0)
     * @param watchPathsOut watchPaths 收集器 (可为 null); CwdChanged/FileChanged 的
     *                      hookSpecificOutput.watchPaths 追加至此
     */
    private static GenericHook.HookResult toHookResultCore(CommandHookResult result, String hookCommand,
                                                           String hookName, String expectedHookEvent,
                                                           String toolUseID, String hookEvent,
                                                           long durationMs,
                                                           java.util.List<String> watchPathsOut) {
        if (result == null) {
            return GenericHook.HookResult.proceed();
        }
        if (result.backgrounded()) {
            // async 后台占位 → 不阻断 (CC :2465-2471 backgrounded → success, 无 message)
            return GenericHook.HookResult.proceed();
        }
        if (result.aborted()) {
            // CC :2473-2497 aborted → hook_cancelled attachment + outcome cancelled
            if (log.isWarnEnabled()) {
                log.warn("HOOK command hook '{}' 被取消 (aborted), 产出 hook_cancelled", hookCommand);
            }
            return new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookCancelled(hookName, toolUseID, hookEvent),
                null, null, null, null,
                GenericHook.HookOutcome.CANCELLED, null, null, null, null, null, null, null, null);
        }
        // 3. parseHookOutput · JSON 校验先于 status 分流 (CC :2499-2531)
        HookOutputParser.ParseResult pr = HookOutputParser.parseHookOutput(result.stdout());
        if (pr.validationError() != null) {
            // CC :2504-2531 validationError → hook_non_blocking_error
            //   (`JSON validation failed: ${validationError}`, stdout 原文, exitCode=1)
            if (log.isWarnEnabled()) {
                log.warn("HOOK command hook '{}' stdout JSON 校验失败, 视为 non_blocking_error: {}",
                    hookCommand, pr.validationError());
            }
            return new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookNonBlockingError(
                    hookName, toolUseID, hookEvent,
                    "JSON validation failed: " + pr.validationError(),
                    result.stdout() != null ? result.stdout() : "", 1,
                    hookCommand, durationMs),
                null, null, null, null,
                GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
        if (pr.json() instanceof HookJSONOutput.SyncHookOutput sync) {
            // CC :2533-2613 json → processHookJSONOutput (任意 status)
            try {
                HookOutputParser.ParsedHookJSONOutput parsed = HookOutputParser.processHookJSONOutput(
                    sync, hookCommand, hookName, expectedHookEvent, toolUseID, hookEvent,
                    result.stdout(), result.stderr(), result.status(), durationMs);
                if (parsed != null && parsed.result() != null) {
                    // [IMP-DC-01] 泛型 watchPaths 提取 · 镜像 CC executeHooksOutsideREPL:3340-3346
                    //   (`json && isSyncHookJSONOutput(json) && json.hookSpecificOutput &&
                    //    'watchPaths' in json.hookSpecificOutput` → hookSpecificOutput.watchPaths),
                    //   事件无关。取代 HookOutputParser.processHookJSONOutput 的 CwdChanged/FileChanged
                    //   case (已删, 对齐 CC switch 无此 case) — collecting 链 (CwdChanged/FileChanged)
                    //   与 executeEvent 链任意事件的 watchPaths 均在此咽喉统一提取。
                    if (watchPathsOut != null) {
                        java.util.List<String> genericWatchPaths = extractWatchPaths(sync.hookSpecificOutput());
                        if (genericWatchPaths != null) {
                            watchPathsOut.addAll(genericWatchPaths);
                        }
                    }
                    return parsed.result();
                }
            } catch (Exception e) {
                // processHookJSONOutput 对非法 decision / expectedHookEvent 不匹配抛 throw
                // (hooks.ts:538-541 / :583-590), CC runHook catch (hooks.ts:2698-2729) →
                // non_blocking_error + hook_non_blocking_error attachment. 不阻断主流程,
                // 但 fail-loud. stdout 对齐 CC runHook catch stdout:'' (hooks.ts:2726).
                if (log.isWarnEnabled()) {
                    log.warn("HOOK command hook '{}' stdout JSON 处理失败, 视为 non_blocking_error: {}",
                        hookCommand, e.getMessage());
                }
                return new GenericHook.HookResult(false, null, null, null,
                    AttachmentMessageDto.hookNonBlockingError(
                        hookName, toolUseID, hookEvent,
                        "Failed to run: " + e.getMessage(), "", 1,
                        hookCommand, durationMs),
                    null, null, null, null,
                    GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
            }
            return GenericHook.HookResult.proceed();
        }
        if (pr.json() instanceof HookJSONOutput.AsyncHookOutput) {
            // CC :2535-2541 async 声明残留 (forceSync 等) → success 无 message
            return GenericHook.HookResult.proceed();
        }
        // 4-6. 非 json 纯文本路径 (CC :2616-2697)
        String stderr = result.stderr() != null ? result.stderr() : "";
        if (result.status() == 0) {
            // CC :2617-2645 status 0 → hook_success content=stdout.trim() (空 stdout 也产)
            String content = result.stdout() != null ? result.stdout().trim() : "";
            return new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookSuccess(hookName, toolUseID, hookEvent, content,
                    result.stdout(), stderr, 0, hookCommand, durationMs),
                null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
        }
        if (result.status() == 2) {
            // CC :2648-2668 blocking (stderr.trim() || 'No stderr output')
            String blockingText = stderr.trim().isEmpty() ? "No stderr output" : stderr.trim();
            String msg = "[" + hookCommand + "]: " + blockingText;
            if (log.isInfoEnabled()) {
                log.info("HOOK command hook '{}' exit=2 阻断: {}", hookCommand, msg);
            }
            return new GenericHook.HookResult(true,
                new HookBlockingError(msg, hookCommand), null, null, null, null, null,
                null, null, GenericHook.HookOutcome.BLOCKING, msg, null, null, null, null, null, null, null);
        }
        // CC :2670-2697 其他非零 → non_blocking_error + attachment (旧静默 proceed 已删)
        if (log.isInfoEnabled()) {
            log.info("HOOK command hook '{}' exit={} 非 0/2, non_blocking_error", hookCommand, result.status());
        }
        String errText = stderr.trim().isEmpty() ? "No stderr output" : stderr.trim();
        return new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError(
                hookName, toolUseID, hookEvent,
                "Failed with non-blocking status code: " + errText,
                result.stdout() != null ? result.stdout() : "", result.status(),
                hookCommand, durationMs),
            null, null, null, null,
            GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
    }

    /**
     * [IMP-DC-01] 泛型 watchPaths 提取 · 镜像 CC executeHooksOutsideREPL:3340-3346
     * {@code 'watchPaths' in json.hookSpecificOutput}（属性存在性检查，事件无关）。
     *
     * <p>WHY: CC 对 CwdChanged/FileChanged（及任意未来 outside-REPL 事件）的 watchPaths
     * 提取不在 processHookJSONOutput switch 内（CC 无此 case），而在 executeHooksOutsideREPL
     * 泛型完成。Java 端 toHookResultCore 是 collecting 链与 executeEvent 链的共享咽喉，
     * 此处按 CC 泛型语义统一提取 — 只要 hookSpecificOutput 携带 watchPaths 即收集。
     * Java 三种携带 watchPaths 的 hookSpecificOutput subtype = SessionStart / CwdChanged /
     * FileChanged（types/hooks.ts:87-90/145-158），其余 subtype 无此字段 → null。
     *
     * @param hso 已解析的 sync hookSpecificOutput（可 null）
     * @return watchPaths 列表（无字段或为 null → null，等价 CC {@code r.watchPaths ?? []} 空贡献）
     */
    private static java.util.List<String> extractWatchPaths(HookSpecificOutput hso) {
        if (hso instanceof HookSpecificOutput.SessionStart ss) {
            return ss.watchPaths();
        }
        if (hso instanceof HookSpecificOutput.CwdChanged cc) {
            return cc.watchPaths();
        }
        if (hso instanceof HookSpecificOutput.FileChanged fc) {
            return fc.watchPaths();
        }
        return null;
    }

    /**
     * exit code → HookResult 分流 · CommandHook 重载 (hook 字段填充).
     *
     * <p>WHY (Session H3): CC HookResult.hook 字段 (hooks.ts:356) 承载触发 hook 的
     * HookCommand, 供审计/UI 追溯"这条 result 来自哪个 hook". 本重载内部先走 String 版本
     * (hook=null), 再 withHook() 补填 — 不重复实现分流逻辑.
     *
     * @param result {@link CommandHookExecutor#execute} 的返回
     * @param hook   触发本 result 的 CommandHook (null → 等价 String 版本)
     * @return 聚合进 GenericHook.HookResult 语义的结果 (含 hook 字段)
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, CommandHook hook) {
        return toHookResult(result, hook, null);
    }

    /**
     * [H3 v2 修复] Gap 3 (H3-GAP-4): CommandHook 重载 + expectedHookEvent 接线.
     *
     * <p>WHY: 与 {@link #toHookResult(CommandHookResult, String, String)} 同理由 —
     * 调用方 (HookRegistry.executeConfiguredCommand) 持有事件上下文, 传 actual ccName.
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, CommandHook hook,
                                                      String expectedHookEvent) {
        return toHookResult(result, hook, expectedHookEvent, null);
    }

    /**
     * [H14 v3 Gap④] toHookResult + watchPaths 收集重载.
     *
     * <p>WHY: CwdChanged/FileChanged command hook 的结果可携带 {@code watchPaths}
     * (hookSpecificOutput.watchPaths, hooks.ts:630-635) — 供 FileChangedWatcher 动态扩展监听。
     * 原 toHookResult 只透出 HookResult，watchPaths 在 parseStdoutJson 内解析后丢弃。
     * 本重载把解析出的 watchPaths 收集进 out 参数（传给 parseStdoutJson 的 4 参重载）。
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, CommandHook hook,
                                                      String expectedHookEvent,
                                                      java.util.List<String> watchPathsOut) {
        GenericHook.HookResult r = toHookResult(
            result, hook != null ? hook.command() : null, expectedHookEvent, watchPathsOut);
        return hook != null ? r.withHook(hook) : r;
    }

    /**
     * [H3 v3 修复] CommandHook 重载 + durationMs 透传 · 对齐 CC :2544-2557.
     *
     * <p>WHY: {@link HookRegistry#executeConfiguredCommand} 持有执行计时, 经本重载把
     * durationMs + hook 字段 (withHook) 一起灌入 message attachment。
     * <p><b>与 H14 v3 的 watchPaths 重载共存</b>（同为扩展，参数类型不同）：
     * 本重载走 durationMs 载荷；watchPaths 场景走 {@code toHookResult(..., List<String>)}。
     */
    public static GenericHook.HookResult toHookResult(CommandHookResult result, CommandHook hook,
                                                      String expectedHookEvent, long durationMs) {
        GenericHook.HookResult r = toHookResult(result,
            hook != null ? hook.command() : null, null, expectedHookEvent, null, null, durationMs);
        return hook != null ? r.withHook(hook) : r;
    }

    /**
     * 解析 hook stdout JSON · 对齐 CC parseHookOutput + processHookJSONOutput (hooks.ts:399-737)
     * 全量实现 (H3 从 H2 最小子集升级).
     *
     * <p>WHY (H2-GAP-2): H2 只识别 continue/decision/stopReason/systemMessage 4 字段,
     * 15 子类型 hookSpecificOutput (updatedInput / additionalContext / watchPaths / retry /
     * permissionRequestResult / elicitationResponse) 全部静默丢失. H3 委托
     * {@link HookOutputParser}, 完整映射.
     *
     * <p><b>outcome 语义纠偏 (CC 对齐)</b>: CC runHook 在 status 0 JSON 路径恒 yield
     * {@code outcome:'success'} (hooks.ts:2592 / :2610), 故本方法解析结果 outcome=SUCCESS;
     * 阻断语义由 preventContinuation / permissionBehavior / blockingError 承载.
     *
     * @param stdout      hook 的 stdout 全量
     * @param hookCommand hook 命令串 (日志关联 + blockingError.command)
     */
    public static GenericHook.HookResult parseStdoutJson(String stdout, String hookCommand) {
        return parseStdoutJson(stdout, hookCommand, null);
    }

    /**
     * [H3 v2 修复] Gap 3 (H3-GAP-4): expectedHookEvent 接线版本 (String 便捷重载).
     *
     * <p>WHY: 委托 {@link #parseStdoutJson(CommandHookResult, String, String, String, String, String, long)},
     * 保留既有 String 调用方 (FrontmatterHooks 等) 兼容.
     */
    public static GenericHook.HookResult parseStdoutJson(String stdout, String hookCommand,
                                                         String expectedHookEvent) {
        CommandHookResult r = new CommandHookResult(stdout, null, stdout, 0, false, false);
        return parseStdoutJson(r, hookCommand, null, expectedHookEvent, null, null, 0L, null);
    }

    /**
     * [H14 v3 Gap④] parseStdoutJson + watchPaths 收集重载（String 便捷版本）.
     *
     * <p>WHY: {@link #toHookResult(CommandHookResult, String, String, List)} 的 status 0
     * 分支调用本版本，把 CwdChanged/FileChanged hook 的 watchPaths 收集进 out 参数，
     * 供 FileChangedWatcher 动态扩展监听。委托到核心 8 参版本。
     */
    public static GenericHook.HookResult parseStdoutJson(String stdout, String hookCommand,
                                                         String expectedHookEvent,
                                                         java.util.List<String> watchPathsOut) {
        CommandHookResult r = new CommandHookResult(stdout, null, stdout, 0, false, false);
        return parseStdoutJson(r, hookCommand, null, expectedHookEvent, null, null, 0L, watchPathsOut);
    }

    /**
     * [H3 v3 修复] CommandHookResult 全量版本 · 载荷透传 + expectedHookEvent fail-loud.
     *
     * <p>WHY (Gap 2 / Gap 3): 旧 String 版本丢失 stderr/exitCode, 也无法承载
     * stdout/stderr/exitCode/durationMs 注入 message attachment (CC hooks.ts:2544-2557).
     * 本版本接收完整 {@link CommandHookResult} + 事件上下文 (hookName/toolUseID/hookEvent/
     * expectedHookEvent) + durationMs, 对齐 CC executeHooks command 分支的
     * processHookJSONOutput 调用载荷.
     *
     * <p><b>fail-loud 对齐 (CC runHook catch :2698-2729)</b>: processHookJSONOutput 对非法
     * decision / expectedHookEvent 不匹配抛 throw (hooks.ts:538-541 / :583-590), CC catch 后
     * yield {@code outcome:'non_blocking_error'} + hook_non_blocking_error attachment. 本方法
     * 等价: catch → NON_BLOCKING_ERROR HookResult + attachment (stderr=`Failed to run: ...`,
     * stdout=原 stdout, exitCode=1), 不阻断主流程但 fail-loud 可观测.
     *
     * @param result            {@link CommandHookExecutor#execute} 的返回 (stdout/stderr/status 载荷)
     * @param hookCommand       hook 命令串 (日志关联 + blockingError.command + attachment.command)
     * @param hookName          CC hookName (attachment.hookName; 无上下文传 null)
     * @param expectedHookEvent CC 事件名 (如 "PreToolUse"); null/空 → 跳过校验
     * @param toolUseID         工具调用 ID (attachment.toolUseID; 无上下文传 null)
     * @param hookEvent         CC hookEvent 名 (attachment.hookEvent; 无上下文传 null)
     * @param durationMs        hook 执行耗时毫秒 (attachment.durationMs; 无计时传 0)
     */
    public static GenericHook.HookResult parseStdoutJson(CommandHookResult result, String hookCommand,
                                                         String hookName, String expectedHookEvent,
                                                         String toolUseID, String hookEvent,
                                                         long durationMs) {
        return parseStdoutJson(result, hookCommand, hookName, expectedHookEvent, toolUseID,
            hookEvent, durationMs, null);
    }

    /**
     * [H14 v3 Gap④ + H3 v3] parseStdoutJson 核心版本 · watchPaths 收集 + durationMs 载荷.
     *
     * <p>WHY: CC executeFileChangedHooks/executeCwdChangedHooks 的返回值含
     * {@code watchPaths} (hooks.ts:4246-4264 / 4266-4285)，供 FileChangedWatcher 动态扩展监听
     * (fileChangedWatcher.ts:86-89 / 160-161)。本核心版本把解析出的 watchPaths 收集进
     * {@code watchPathsOut}（可为 null → 等价原行为），同时透传 durationMs 到 attachment。
     *
     * @param result            {@link CommandHookExecutor#execute} 的返回 (stdout/stderr/status 载荷)
     * @param hookCommand       hook 命令串 (日志关联 + blockingError.command + attachment.command)
     * @param hookName          CC hookName (attachment.hookName; 无上下文传 null)
     * @param expectedHookEvent CC 事件名 (如 "PreToolUse"); null/空 → 跳过校验
     * @param toolUseID         工具调用 ID (attachment.toolUseID; 无上下文传 null)
     * @param hookEvent         CC hookEvent 名 (attachment.hookEvent; 无上下文传 null)
     * @param durationMs        hook 执行耗时毫秒 (attachment.durationMs; 无计时传 0)
     * @param watchPathsOut     watchPaths 收集器 (可为 null); 解析出的 CwdChanged/FileChanged
     *                          hookSpecificOutput.watchPaths 追加至此
     */
    public static GenericHook.HookResult parseStdoutJson(CommandHookResult result, String hookCommand,
                                                         String hookName, String expectedHookEvent,
                                                         String toolUseID, String hookEvent,
                                                         long durationMs,
                                                         java.util.List<String> watchPathsOut) {
        // [S4 G01/G02/G03/G17] 委托统一核心 — 旧实现 (blank 早退 / json 分支 / proceed 尾)
        //   已并入 toHookResultCore, 本方法保留为公开便捷入口 (watchPaths 收集语义保留).
        return toHookResultCore(result, hookCommand, hookName, expectedHookEvent,
            toolUseID, hookEvent, durationMs, watchPathsOut);
    }

    /**
     * [fix-ts04 IMPL-01] 批级 base 字段合并 · 对齐 CC {@code createBaseHookInput}
     * （hooks.ts:301-328）恒备字段单点计算语义（OD-TS04-01 方案 B：分发层注入）。
     *
     * <p>合并优先级（单值约束 REQ-06，杜绝双轨）：
     * {@code event 顶层已有值 ＞ event.data 已有值（事件特有覆盖）＞ ctx 派生值 ＞ 省略}。
     *
     * <ul>
     *   <li>{@code session_id}：{@code event.sessionId() ?? RequestContext.sessionId()}（MDC 回退；
     *       两源皆 null → 省略，对齐 CC getSessionId 回退语义 hooks.ts:315/320）</li>
     *   <li>{@code transcript_path}：{@code event.transcriptPath() ?? SessionStorage.resolveExistingTranscript(
     *       Path.of(AutoMemPaths.currentSessionProjectRoot()), resolvedSessionId)}（D3 读兼容：经
     *       resolveExistingTranscript 读 nexusai 现有 transcript；resolvedSessionId = 合并后 session_id；
     *       workspaceDir/sessionId null → null → 省略，不产出空串
     *       REQ-03）</li>
     *   <li>{@code cwd}：{@code event.cwd() ?? CwdResolution.getCwd(sessionId)}（G14 收敛统一入口，
     *       对齐 CC BaseHookInput {@code cwd: getCwd()} hooks.ts:323 —— 消除 hook 域自建
     *       effectiveCwd ?: currentSessionProjectRoot 三级链同语义两套标准 OD-4；
     *       event.cwd() 显式覆盖优先 = Java 侧 escape hatch，CC 端恒 getCwd() 无事件级覆盖）</li>
     *   <li>{@code permission_mode}：仅工具事件（PreToolUse/PostToolUse/PostToolUseFailure/
     *       PermissionRequest/PermissionDenied）顶层 null && ctx.permissionMode() 非 null →
     *       {@code ToolPermissionGate.modeToCcString(ctx.permissionMode())}（CC toolHooks.ts 恒有
     *       toolPermissionContext.mode；事件域保持 undefined 省略）</li>
     *   <li>{@code agent_id}：{@code event.agentId() ?? ctx.agentId()}（subagent 场景）</li>
     *   <li>{@code agent_type}：仅工具事件 data 无 {@code agent_type} && ctx.agentType() 非 null →
     *       合并进 data（OD-TS04-02：ctx.agentType() subagent 非 null → 注入；主线程 null → 省略，
     *       Java 无 --agent 等价全局源）；事件特有覆盖（Stop/SessionEnd/subagentStart/subagentStop
     *       data 注入）优先保持，同 key 不双写</li>
     * </ul>
     *
     * <p>工厂保持现状不动（R33H4 锁定 record 组件语义）；signal 状态字段不触碰（OD-TS04-05）。
     * 无任何新增值（或事件为 null）→ 返回原事件（零分配）。
     *
     * @param event     原始 hook 事件（HookEvent 工厂产物）
     * @param parentTuc 当前父 ToolUseContext（工具链 ctx / Stop 段 parentTuc；null = 无 ctx，跳过 ctx 派生）
     * @return 合并后的事件副本；无新增值返回原事件；event 为 null 返回 null
     */
    public static HookEvent enrichBaseFields(HookEvent event, ToolUseContext parentTuc) {
        if (event == null) {
            return null;
        }
        // session_id 回退: event.sessionId() ?? RequestContext.sessionId()（MDC；虚拟线程 null → 省略）
        String sessionId = event.sessionId();
        if (sessionId == null) {
            sessionId = RequestContext.sessionId();
        }
        // transcript_path: event.transcriptPath() ?? SessionStorage.resolveExistingTranscript(workspaceDir, resolvedSessionId)
        // [D3 读兼容] 只读 nexusai 自有 transcript（resume 仅支持 nexusai 会话，无 claude ~/.claude/projects 回落）
        String transcriptPath = event.transcriptPath();
        if (transcriptPath == null && sessionId != null) {
            Path tp = SessionStorage.resolveExistingTranscript(
                Path.of(AutoMemPaths.currentSessionProjectRoot()), sessionId);
            if (tp != null) {
                transcriptPath = tp.toString();
            }
        }
        // cwd: event.cwd() ?? CwdResolution.getCwd(sessionId)（G14 收敛统一入口，对齐 CC
        //   BaseHookInput cwd: getCwd() hooks.ts:323 —— 消除 hook 域自建 effectiveCwd ?:
        //   currentSessionProjectRoot 三级链同语义两套标准 OD-4）。event.cwd() 显式覆盖优先
        //   （Java 侧 escape hatch）。CwdResolution.getCwd = override ?? sessionCwd ??
        //   boundProject ?? user.dir，绝不读 CLAUDE_PROJECT_DIR env / config home（身份域红线 D-1）。
        String cwd = event.cwd();
        if (cwd == null) {
            cwd = CwdResolution.getCwd(sessionId);
        }
        boolean toolEvent = isToolEvent(event.type());
        // permission_mode: 仅工具事件 ctx 注入（事件顶层已有值优先）
        String permissionMode = event.permissionMode();
        if (permissionMode == null && toolEvent && parentTuc != null && parentTuc.permissionMode() != null) {
            permissionMode = ToolPermissionGate.modeToCcString(parentTuc.permissionMode());
        }
        // agent_id: event.agentId() ?? ctx.agentId()（subagent 场景）
        String agentId = event.agentId();
        if (agentId == null && parentTuc != null && parentTuc.agentId() != null) {
            agentId = parentTuc.agentId().toString();
        }
        // agent_type: 仅工具事件 data 无 agent_type && ctx.agentType() 非 null → 合并（单值不双写）
        // [IMP-CF-01] data 改类型化 record：合并经 HookEventData.fromMap 重建类型化数据；
        //   身份比较用 dataRecord 实例（data() 派生视图每次新建，不能用于 == 判定）。
        HookEventData dataRecord = event.dataRecord();
        if (toolEvent && parentTuc != null && parentTuc.agentType() != null
                && !event.data().containsKey("agent_type")) {
            Map<String, Object> merged = new HashMap<>(event.data());
            merged.put("agent_type", parentTuc.agentType());
            dataRecord = HookEventData.fromMap(event.type(), merged);
        }
        boolean unchanged = Objects.equals(sessionId, event.sessionId())
            && Objects.equals(transcriptPath, event.transcriptPath())
            && Objects.equals(cwd, event.cwd())
            && Objects.equals(permissionMode, event.permissionMode())
            && Objects.equals(agentId, event.agentId())
            && dataRecord == event.dataRecord();
        if (unchanged) {
            return event;
        }
        return new HookEvent(event.type(), sessionId, transcriptPath, cwd, permissionMode, agentId,
            event.toolName(), event.input(), event.result(), event.toolUseId(),
            event.permissionSuggestions(), event.requestedSchema(), dataRecord, event.timestampMs());
    }

    /**
     * [S4 G09 / G14] 解析 hook spawn cwd · 对齐 CC execCommandHook {@code hookCwd = getCwd()}
     * (hooks.ts:931, 会话 AsyncLocalStorage cwd) + safeCwd pathExists 回退 getOriginalCwd
     * (hooks.ts:932-935).
     *
     * <p><b>G14 收敛</b>（OD-4 闭环）：原 hook 域自建三级链
     * {@code event.cwd() ?? parentTuc.effectiveCwd() (非 JVM user.dir) ??
     * AutoMemPaths.currentSessionProjectRoot()} 与工具域 CwdResolution 形成「同语义两套标准」。
     * 现收敛到 CwdResolution 单一入口，消除分叉：
     * <pre>
     * resolveSpawnCwd(event):
     *   1. event.cwd()（显式覆盖优先 · Java 侧 escape hatch，CC 端 BaseHookInput 恒 getCwd() 无事件级覆盖）
     *   2. CwdResolution.getCwd(sessionId)（对齐 CC pwd/getCwd 三层 + user.dir 兜底）
     *      = override(ThreadLocal) ?? sessionCwd(SessionCwdHolder, worktree 入口与 bash cd 共用)
     *        ?? boundProject(SessionProjectRoot.getForSession) ?? user.dir
     * </pre>
     * sessionId = {@code event.sessionId() ?? RequestContext.sessionId()}（MDC 回退）。
     *
     * <p><b>身份域红线 D-1</b>：CwdResolution.getCwd 不读 {@code SessionProjectRoot.resolve()}
     * （回落 CLAUDE_PROJECT_DIR env / config home 属身份域）。原 {@code AutoMemPaths.currentSessionProjectRoot()}
     * 的 env / config home 兜底属身份域泄入工作目录域，现由 CwdResolution 的 user.dir 兜底替代
     * （对齐 CC getCwd catch → getOriginalCwd → STATE.originalCwd = 启动 cwd）。
     *
     * <p>pathExists 回退语义不变（调用方 12 参 execute hookCwd + pathExists false → safeCwd=null
     * ≈ 继承 JVM cwd ≈ CC safeCwd=getOriginalCwd 兜底，hooks.ts:932-935）。
     *
     * <p>原 {@code isJvmUserDir} 守卫（识别 ToolUseContext compact ctor 把 null effectiveCwd
     * 兜底为 JVM user.dir）随 effectiveCwd 层删除而失效，已删。
     *
     * @param event hook 事件（event.cwd() 显式覆盖优先；event.sessionId() 取会话）
     * @return spawn cwd（恒非 null，CwdResolution.getCwd 兜底 user.dir）
     */
    public static String resolveSpawnCwd(HookEvent event) {
        String cwd = event != null ? event.cwd() : null;
        if (cwd == null) {
            String sessionId = event != null ? event.sessionId() : null;
            if (sessionId == null) {
                sessionId = RequestContext.sessionId();
            }
            cwd = CwdResolution.getCwd(sessionId);
        }
        if (log.isDebugEnabled()) {
            log.debug("[CommandHookExecutor] 解析 hook spawn cwd: eventCwd={} sessionId={} resolved={}",
                event != null ? event.cwd() : null,
                event != null ? event.sessionId() : null, cwd);
        }
        return cwd;
    }
    /**
     * 工具类事件判别 · enrichBaseFields 决定是否从 parentTuc 注入 permission_mode/agent_type.
     *
     * <p>含 USER_PROMPT_SUBMIT: 对齐 CC executeUserPromptSubmitHooks (hooks.ts:3826-3855)
     * 经 createBaseHookInput(permissionMode) 注入 permission_mode (hooks.ts:3842) —
     * processUserInput.ts:184 传 appState.toolPermissionContext.mode。Java 等价注入源为
     * parentTuc.permissionMode()（LlmAgentLoop:1963 UserPromptSubmit 段传
     * PermissionMode.DEFAULT 等），故 USER_PROMPT_SUBMIT 纳入本集合（OPD-WF3-TH-02）。
     */
    private static boolean isToolEvent(HookEventType type) {
        return type == HookEventType.PRE_TOOL_USE
            || type == HookEventType.POST_TOOL_USE
            || type == HookEventType.POST_TOOL_USE_FAILURE
            || type == HookEventType.PERMISSION_REQUEST
            || type == HookEventType.PERMISSION_DENIED
            || type == HookEventType.USER_PROMPT_SUBMIT;
    }

    /**
     * hookEvent → stdin jsonInput · 对齐 CC BaseHookInput 序列化 (复用 FrontmatterHooks
     * buildStdinPayload 模式, 抽为公共 static).
     *
     * <p>字段: hook_event_name (PascalCase) / session_id / agent_id / tool_name / tool_input /
     * tool_response + event.data KV.
     *
     * @param event hook 事件
     * @return JSON 字符串 (无事件 → "{}")
     */
    public static String buildJsonInput(HookEvent event) {
        if (event == null) return "{}";
        ObjectNode node = MAPPER.createObjectNode();
        node.put("hook_event_name", toCcEventName(event.type()));
        if (event.sessionId() != null) node.put("session_id", event.sessionId());
        // [fix-ts04 IMPL-01] transcript_path/cwd 恒备序列化 · 对齐 CC BaseHookInputSchema
        //   (coreSchemas.ts:389-390 必传 string) — 值由批级 enrichBaseFields 合并注入
        //   （executeConfiguredHooks 序列化前），null 省略（REQ-03 不产出空串）。
        if (event.transcriptPath() != null) node.put("transcript_path", event.transcriptPath());
        if (event.cwd() != null) node.put("cwd", event.cwd());
        if (event.agentId() != null) node.put("agent_id", event.agentId());
        if (event.toolName() != null) node.put("tool_name", event.toolName());
        // [IMPL-03 X5] tool_use_id 注入 · 对齐 CC PreToolUseHookInputSchema
        //   (coreSchemas.ts:417/444/448) tool_use_id 必传字段 — 旧实现遗漏, 配置 hook
        //   收不到工具调用 ID.
        if (event.toolUseId() != null) node.put("tool_use_id", event.toolUseId());
        if (event.input() != null) node.set("tool_input", event.input());
        if (event.result() != null) node.set("tool_response", event.result());
        // [Session S07] PermissionRequest hook 载荷补全 · 对齐 CC PermissionRequestHookInputSchema
        //   (coreSchemas.ts:425-433) + createBaseHookInput (hooks.ts:309-327):
        //   permission_mode 与 permission_suggestions 此前被丢弃 (只存 HookEvent 顶层字段),
        //   配置驱动 PermissionRequest hook 的 stdin 收不到建议列表与权限模式 → 决策信息不全.
        if (event.permissionMode() != null) node.put("permission_mode", event.permissionMode());
        if (event.permissionSuggestions() != null) {
            node.putPOJO("permission_suggestions", event.permissionSuggestions());
        }
        for (Map.Entry<String, Object> entry : event.data().entrySet()) {
            node.putPOJO(entry.getKey(), entry.getValue());
        }
        return node.toString();
    }

    /** SNAKE_CASE → CC PascalCase 事件名 (PRE_TOOL_USE → PreToolUse). */
    public static String toCcEventName(HookEventType type) {
        StringBuilder sb = new StringBuilder();
        for (String part : type.name().split("_")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** 解析 timeout · CC :877-879 {@code hook.timeout ? hook.timeout * 1000 : DEFAULT}. */
    public static long resolveTimeoutMs(Integer hookTimeout) {
        return resolveTimeoutMs(hookTimeout, DEFAULT_HOOK_EXECUTION_TIMEOUT_MS);
    }

    /**
     * [IMP-HOOKS-S5 D-01] 解析 timeout + 调用方缺省 · CC :3280 / :877-879
     * {@code hook.timeout ? hook.timeout * 1000 : timeoutMs} —— 缺省超时由调用方注入
     * （SessionEnd 传 sessionEndTimeoutMs=1500；其余场景走 10min）。
     */
    public static long resolveTimeoutMs(Integer hookTimeout, long defaultTimeoutMs) {
        return hookTimeout != null && hookTimeout > 0
            ? hookTimeout * 1000L
            : defaultTimeoutMs;
    }

    /** 取 stdout 首行 · 对齐 CC stringUtils.ts:44-48 firstLineOf. */
    public static String firstLineOf(String s) {
        int nl = s.indexOf('\n');
        return nl == -1 ? s : s.substring(0, nl);
    }

    /** JSON 是否 {@code {async:true}} · 对齐 CC types/hooks.ts:189-193 isAsyncHookJSONOutput. */
    public static boolean isAsyncJson(String firstLine) {
        if (firstLine == null || !firstLine.contains("async")) return false;
        try {
            JsonNode node = MAPPER.readTree(firstLine);
            return node.has("async") && node.get("async").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析 prompt 请求 JSON · 对齐 CC promptRequestSchema (types/hooks.ts:28-40).
     * [IMP-RS-01 DEL-01e 补回] 恢复自删除前实现 (41a486cd^). 非 prompt 结构 → null.
     */
    public static PromptRequest tryParsePromptRequest(String trimmed) {
        if (trimmed == null || trimmed.isEmpty() || !trimmed.startsWith("{")) return null;
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (!node.has("prompt") || !node.has("message") || !node.has("options")) return null;
            if (!node.path("prompt").isTextual() || !node.path("message").isTextual()) return null;
            if (!node.path("options").isArray()) return null;
            List<PromptRequest.Option> options = new ArrayList<>();
            for (JsonNode opt : node.path("options")) {
                if (!opt.has("key") || !opt.has("label")) return null;
                if (!opt.path("key").isTextual() || !opt.path("label").isTextual()) return null;
                options.add(new PromptRequest.Option(
                    opt.path("key").asText(),
                    opt.path("label").asText(),
                    opt.has("description") && !opt.path("description").isNull()
                        ? opt.path("description").asText() : null));
            }
            return new PromptRequest(node.path("prompt").asText(), node.path("message").asText(), options);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 过滤已处理的 prompt 行 (内容匹配, 防泄漏, CC :1243-1249).
     * [IMP-RS-01 DEL-01e 补回] 恢复自删除前实现 (41a486cd^).
     */
    public static String filterPromptLines(String stdout, Set<String> processed) {
        if (stdout == null) return null;
        StringBuilder sb = new StringBuilder();
        String[] lines = stdout.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!processed.contains(lines[i].trim())) {
                sb.append(lines[i]);
                if (i < lines.length - 1) sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** JSON 序列化 (PromptResponse 等) · 对齐 CC jsonStringify. */
    public static String jsonStringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** EPIPE 判定 · Java IOException 无 errno code, 按消息启发式 (CC getErrnoCode===EPIPE). */
    static boolean isEpipeError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("broken pipe") || lower.contains("pipe is being closed")
            || lower.contains("stream closed");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 默认实现 (平台路径解析 / 数据目录)
    // ════════════════════════════════════════════════════════════════════════

    private static boolean defaultPathExists(String path) {
        return path != null && !path.isBlank() && Files.exists(Paths.get(path));
    }

    /**
     * CLAUDE_PROJECT_DIR env 取值器缺省（CC getProjectRoot state.ts:511-513）·
     * <b>非 spawn cwd</b>（G14 后 spawn cwd 经 CwdResolution.getCwd 解析，不再走此方法）。
     * 此处 user.dir 兜底仅用于 CLAUDE_PROJECT_DIR env（项目根域），生产 bean 注入实际 resolver。
     */
    private static String defaultProjectRoot() {
        return System.getProperty("user.dir", "");
    }

    /**
     * CLAUDE_PLUGIN_DATA 缺省取值器（pluginDataDirResolver 兜底）·
     * <b>[T2 · 决策 D4/D1/D6]</b> nexusai 写根改 {@code {user.home}/.{appName}/plugins/{pluginId}}，
     * 经 {@link NexusaiPaths#getAppConfigHomeDir()}（用户级自有根，动态 appName）；
     * claude 已装插件仅经 {@code InstalledPluginsFileStore} 读回落（~/.claude/plugins 兼容读，不迁移文件）。
     */
    private static String defaultPluginDataDir(String pluginId) {
        return NexusaiPaths.getAppConfigHomeDir() + File.separator + "plugins" + File.separator + pluginId;
    }

    /** PATH 中找可执行文件 · where/which 语义 (Windows 加 .exe). */
    private static String findExecutableOnPath(String exe, Function<String, String> envResolver) {
        String pathValue = envResolver.apply("PATH");
        if (pathValue == null) return null;
        String sep = isWindows() ? ";" : ":";
        for (String dir : pathValue.split(Pattern.quote(sep))) {
            if (dir == null || dir.isBlank()) continue;
            File base = new File(dir);
            File direct = new File(base, exe);
            if (direct.isFile()) return direct.getAbsolutePath();
            if (isWindows()) {
                File withExe = new File(base, exe + ".exe");
                if (withExe.isFile()) return withExe.getAbsolutePath();
            }
        }
        return null;
    }

    /** 由 git 可执行路径推导 bash 路径 · {@code <gitDir>/bin/bash.exe} (windowsPaths.ts:113). */
    private static String deriveBashFromGit(String gitPath) {
        File gitFile = new File(gitPath);
        File parent = gitFile.getParentFile();
        if (parent == null) return null;
        File gitRoot = parent.getParentFile();
        if (gitRoot == null) return null;
        return new File(gitRoot, "bin" + File.separator + "bash.exe").getPath();
    }

    /**
     * 默认进程启动器 · 基于 {@link ProcessBuilder}.
     *
     * <p>env 合并: ProcessBuilder 默认继承父进程 env, 本实现把 {@link ProcessSpec#env()}
     * 追加进去 (对齐 CC subprocessEnv() spread + hook vars).
     */
    static class DefaultProcessLauncher implements ProcessLauncher {
        @Override
        public HookProcess launch(ProcessSpec spec) throws IOException {
            if (spec.commandArgs() == null || spec.commandArgs().isEmpty()) {
                throw new IOException("command args is empty");
            }
            ProcessBuilder pb = new ProcessBuilder(spec.commandArgs());
            if (spec.cwd() != null && !spec.cwd().isBlank()) {
                pb.directory(new File(spec.cwd()));
            }
            if (spec.env() != null) {
                pb.environment().putAll(spec.env());
            }
            Process process = pb.start();
            return new RealHookProcess(process);
        }
    }

    /**
     * {@link PendingAsyncHook.AsyncHookProcess} 适配 · 对齐 CC ShellCommand 的注册表面
     * (AsyncHookRegistry.ts:146-189 使用面).
     *
     * <p><b>status 探测</b>: destroyForcibly 已调 (kill) → 'killed'; 否则
     * {@code waitFor(0, MILLISECONDS)} 立即探测退出态 → 'completed'/'running'
     * (对齐 CC ShellCommand.status 语义, AsyncHookRegistry.ts:160-173).
     *
     * <p><b>stdout/stderr 累积</b>: 由 supplier 注入, 两条路径:
     * <ul>
     *   <li>config-async 路径: 本执行器在注册前启动 daemon 读取线程读 InputStream 到
     *       AtomicReference (管道必须有人读防阻塞, 64KB 缓冲)</li>
     *   <li>stdout 首行 async 路径: 同步收集线程已独占读取流, 直接用
     *       stdoutRef/stderrRef 做 supplier (双读者会争抢流内字节)</li>
     * </ul>
     */
    static class HookProcessWrapper implements PendingAsyncHook.AsyncHookProcess {
        private final HookProcess child;
        private final Supplier<String> stdoutSupplier;
        private final Supplier<String> stderrSupplier;
        private volatile boolean killed;

        HookProcessWrapper(HookProcess child, Supplier<String> stdoutSupplier, Supplier<String> stderrSupplier) {
            this.child = child;
            this.stdoutSupplier = stdoutSupplier != null ? stdoutSupplier : () -> "";
            this.stderrSupplier = stderrSupplier != null ? stderrSupplier : () -> "";
        }

        @Override
        public String status() {
            if (killed) {
                return "killed";
            }
            try {
                return child.waitFor(0, TimeUnit.MILLISECONDS) ? "completed" : "running";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "running";
            }
        }

        @Override
        public String stdout() { return stdoutSupplier.get(); }

        @Override
        public String stderr() { return stderrSupplier.get(); }

        @Override
        public void cleanup() {
            // CC shellCommand.cleanup() (AsyncHookRegistry.ts:100): Java 侧无外部资源,
            // 读取线程是 daemon 自然结束, 无需动作
        }

        @Override
        public void kill() {
            killed = true;
            child.destroyForcibly();
        }

        @Override
        public int exitCode() {
            try {
                return child.exitValue();
            } catch (IllegalThreadStateException e) {
                // 进程未退出时 exitValue 抛异常 (CC execResult.code 仅 completed 后取,
                // 防御降级 1)
                return 1;
            }
        }
    }

    /** {@link Process} 适配 {@link HookProcess}. */
    static class RealHookProcess implements HookProcess {
        private final Process process;

        RealHookProcess(Process process) {
            this.process = process;
        }

        @Override public OutputStream stdin() { return process.getOutputStream(); }
        @Override public InputStream stdout() { return process.getInputStream(); }
        @Override public InputStream stderr() { return process.getErrorStream(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
        @Override public int exitValue() { return process.exitValue(); }
    }
}

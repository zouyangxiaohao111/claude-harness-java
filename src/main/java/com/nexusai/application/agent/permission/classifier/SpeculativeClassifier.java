package com.nexusai.application.agent.permission.classifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.RequestContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SpeculativeClassifier · 对齐 CC {@code tools/BashTool/bashPermissions.ts:1483-1544}
 * {@code speculativeChecks} Map + peek / start / consume / clear 四函数。
 *
 * <p><b>投机机制语义（CC 真源）</b>：{@link #startSpeculativeClassifierCheck} 在 pre-tool
 * hooks / deny-ask classifiers / 权限弹窗前<b>提前启动</b> bash allow classifier 检查，让
 * 分类器与 hooks / 弹窗并行执行；结果后续由 {@code useCanUseTool.tsx:127-158} 经
 * {@link #peekSpeculativeClassifierCheck} → {@code Promise.race} 竞速 →
 * {@link #consumeSpeculativeClassifierCheck}（命中即删）消费。
 *
 * <p><b>可随时开启门控</b>：CC {@code utils/permissions/bashClassifier.ts:24-26}
 * {@code isClassifierPermissionsEnabled()} 恒 {@code false}（ANT-ONLY stub），故生产环境
 * {@link #startSpeculativeClassifierCheck} 首闸（bashPermissions.ts:1504）恒
 * {@code return false} → {@code speculativeChecks} 恒空 → peek / consume 恒 {@code null}
 * → gate 竞速恒回落 interactive。但首闸<b>之后的主体已实现为真实代码</b>（3 guard +
 * {@link #getBashPromptAllowDescriptions} + {@link #getCwd(String)} + {@link #classifyBashCommand}
 * + 存 Map + {@code return true}）。因 {@link #getBashPromptAllowDescriptions} 对齐 CC
 * checked-in stub 恒返 {@code []}（bashClassifier.ts:36-38），外部构建下即便翻转
 * {@link #setClassifierPermissionsEnabled(true)} 仍命中 guard 4（allowDescriptions 空）而
 * {@code return false} —— 对齐 CC 外部构建真实行为，<b>可随时开启</b>（未来提供非空描述源即可走完）。
 *
 * <p><b>不接 YoloClassifier</b>：CC 投机用 {@code classifyBashCommand} stub（bashClassifier.ts:40-51，
 * 返回 {@code matches:false, reason:'This feature is disabled'}），非 YOLO-LLM 分类器。
 * Java 对齐 stub，不复用 {@link YoloClassifierResult}（18 字段契约错位）。
 *
 * <p>Java 端容器为<b>静态工具类</b>（仿 {@link com.nexusai.application.agent.permission.ClassifierApprovals}，
 * 非 Spring bean —— 与 CC 模块级 {@code Map} + 模块级函数对齐；调用点为
 * {@code StreamingToolExecutor}（start）、{@code ToolPermissionGate}（peek/consume）、
 * {@code PostCompactCleanup}（clear）三处静态接线）。门控用 package-private
 * {@code static} 字段 + setter 注入（默认 false，保生产行为不变），测试可翻转验证。
 */
public final class SpeculativeClassifier {

    private static final Logger log = LoggerFactory.getLogger(SpeculativeClassifier.class);

    /**
     * 投机检查容器 · CC original: {@code speculativeChecks}
     * ({@code new Map<string, Promise<ClassifierResult>>()}，bashPermissions.ts:1483)。
     *
     * <p>ConcurrentHashMap —— CC 的 {@code Map} 被 toolExecution.ts:739-751（主循环 start）
     * 与 useCanUseTool.tsx:127-158（gate 竞速 peek/consume）并发访问；Java 侧对齐线程安全。
     */
    private static final Map<String, CompletableFuture<SpeculativeClassifierResult>> SPECULATIVE_CHECKS =
        new ConcurrentHashMap<>();

    /**
     * 分类器权限特性开关 · CC original: {@code isClassifierPermissionsEnabled}
     * （bashClassifier.ts:24-26）。
     *
     * <p>默认 {@code false}（ANT-ONLY stub）—— 生产环境投机分类器永不启动，对齐 CC
     * 外部构建行为。package-private setter 供测试翻转（可随时开启验证）。
     */
    private static boolean classifierPermissionsEnabled = false;

    /**
     * {@code feature('TRANSCRIPT_CLASSIFIER')} 编译期 flag 等价 · CC original:
     * {@code feature('TRANSCRIPT_CLASSIFIER')}（bashPermissions.ts:1505）。
     *
     * <p>CC 中该 flag 开启 auto mode；首闸后 guard 2 为
     * {@code transcriptClassifierEnabled && mode == AUTO → return false}。默认 {@code true}
     * （对齐 CC 外部构建下 flag 编译开启），package-private setter 供测试注入。
     */
    private static boolean transcriptClassifierEnabled = true;

    private SpeculativeClassifier() {}

    /**
     * 测试钩子（package-private）· 翻转 {@code isClassifierPermissionsEnabled} 门控。
     *
     * <p>CC 中该值由编译期 stub 决定（恒 false）；Java 静态工具类无 Spring 注入点，
     * 用 package-private setter 让测试翻转 flag 后验证 {@link #startSpeculativeClassifierCheck}
     * 走完主体并 {@code return true}。
     */
    static void setClassifierPermissionsEnabled(boolean enabled) {
        classifierPermissionsEnabled = enabled;
    }

    /**
     * 测试钩子（package-private）· 注入 {@code feature('TRANSCRIPT_CLASSIFIER')} 等价 flag。
     */
    static void setTranscriptClassifierEnabled(boolean enabled) {
        transcriptClassifierEnabled = enabled;
    }

    /**
     * ClassifierResult 等价 · CC original: {@code ClassifierResult}（bashClassifier.ts:5-9）。
     *
     * @param matches            CC original: {@code matches}（bashClassifier.ts:6）—— 是否命中放行规则
     * @param matchedDescription CC original: {@code matchedDescription}（bashClassifier.ts:7）—— 命中规则描述（可 null）
     * @param confidence         CC original: {@code confidence}（bashClassifier.ts:8）—— {@code 'high'|'medium'|'low'}
     * @param reason             CC original: {@code reason}（bashClassifier.ts:9）—— 分类结论说明
     */
    public record SpeculativeClassifierResult(
        boolean matches,
        String matchedDescription,
        String confidence,
        String reason) {}

    /**
     * 分类器权限特性开关 · CC original: {@code isClassifierPermissionsEnabled}
     * （bashClassifier.ts:24-26）。
     *
     * <p>读 static 字段（默认 {@code false}，ANT-ONLY stub）—— 生产恒 false，对齐 CC
     * 外部构建行为。方法与接线保留，未来启用时仅需翻 {@link #setClassifierPermissionsEnabled}。
     *
     * @return 当前门控值（默认 {@code false}）
     */
    public static boolean isClassifierPermissionsEnabled() {
        return classifierPermissionsEnabled;
    }

    /**
     * 投机检查 peek（不删）· CC original: {@code peekSpeculativeClassifierCheck}
     * （bashPermissions.ts:1491-1494，{@code return speculativeChecks.get(command)}）。
     *
     * @param command bash 命令（Map key）
     * @return 命中的 CompletableFuture，或缺席时 {@code null}
     */
    public static CompletableFuture<SpeculativeClassifierResult> peekSpeculativeClassifierCheck(String command) {
        if (command == null) {
            return null;
        }
        return SPECULATIVE_CHECKS.get(command);
    }

    /**
     * 启动投机检查 · CC original: {@code startSpeculativeClassifierCheck}
     * （bashPermissions.ts:1497-1526）。
     *
     * <p>CC 完整签名四参：{@code command} / {@code toolPermissionContext} /
     * {@code signal} / {@code isNonInteractiveSession}。Java 无 {@code AbortSignal}，
     * 用 {@link AbortController} 等价（可 null —— classifyBashCommand stub 忽略）。
     *
     * <p><b>主体（首闸后为真实代码，非注释死代码）</b>：
     * <ol>
     *   <li>guard 1：{@code !isClassifierPermissionsEnabled()} → false（:1504）</li>
     *   <li>guard 2：{@code feature('TRANSCRIPT_CLASSIFIER') && mode == AUTO} → false（:1505-1506）</li>
     *   <li>guard 3：{@code mode == BYPASS_PERMISSIONS} → false（:1507）</li>
     *   <li>{@code getBashPromptAllowDescriptions(...)} 空 → false（:1508-1511）</li>
     *   <li>{@code classifyBashCommand(command, cwd, allowDescriptions, 'allow', signal, isNonInteractiveSession)}
     *       → {@code promise.exceptionally(吞异常)}（防 unhandled rejection，等价
     *       {@code promise.catch(() => {})}）→ {@code SPECULATIVE_CHECKS.put(command, promise)}
     *       → {@code return true}（:1513-1526）</li>
     * </ol>
     *
     * @param command                 bash 命令（Map key）· CC original: {@code command}
     * @param toolPermissionContext   权限上下文 · CC original: {@code toolPermissionContext}
     * @param signal                  取消信号（Java 等价 AbortController，可 null）· CC original: {@code signal}（AbortSignal）
     * @param isNonInteractiveSession 是否非交互会话 · CC original: {@code isNonInteractiveSession}
     * @return {@code true} 投机已启动并存入 Map；{@code false} 任一 guard 拦截
     */
    public static boolean startSpeculativeClassifierCheck(
            String command,
            ToolPermissionContext toolPermissionContext,
            AbortController signal,
            boolean isNonInteractiveSession) {
        // guard 1: !isClassifierPermissionsEnabled() → false (bashPermissions.ts:1504)
        if (!isClassifierPermissionsEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("投机分类器检查未启动 (isClassifierPermissionsEnabled=false) · CC bashPermissions.ts:1504 command={}",
                    command);
            }
            return false;
        }
        // guard 2: feature('TRANSCRIPT_CLASSIFIER') && mode === 'auto' → false (bashPermissions.ts:1505-1506)
        if (transcriptClassifierEnabled && toolPermissionContext != null
                && toolPermissionContext.mode() == PermissionMode.AUTO) {
            if (log.isDebugEnabled()) {
                log.debug("投机分类器检查未启动 (auto mode) · CC bashPermissions.ts:1505 command={}", command);
            }
            return false;
        }
        // guard 3: mode === 'bypassPermissions' → false (bashPermissions.ts:1507)
        if (toolPermissionContext != null
                && toolPermissionContext.mode() == PermissionMode.BYPASS_PERMISSIONS) {
            if (log.isDebugEnabled()) {
                log.debug("投机分类器检查未启动 (bypassPermissions mode) · CC bashPermissions.ts:1507 command={}", command);
            }
            return false;
        }
        // guard 4: getBashPromptAllowDescriptions 空 → false (bashPermissions.ts:1508-1511)
        List<String> allowDescriptions = getBashPromptAllowDescriptions(toolPermissionContext);
        if (allowDescriptions.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("投机分类器检查未启动 (无 prompt: allow 描述) · CC bashPermissions.ts:1511 command={}", command);
            }
            return false;
        }
        // cwd 经 RequestContext.sessionId() 解析会话当前 cwd（对齐 CC bashPermissions.ts:1513 getCwd()，
        //     Java CwdResolution.getCwd 含 override ?? sessionCwd ?? boundProject ?? user.dir 兜底链）
        String cwd = getCwd(RequestContext.sessionId());
        CompletableFuture<SpeculativeClassifierResult> promise = classifyBashCommand(
            command, cwd, allowDescriptions, "allow", signal, isNonInteractiveSession);
        // 防 unhandled rejection: 等价 CC promise.catch(() => {}) (bashPermissions.ts:1522-1524)
        //   原始 promise (可能 reject) 仍存入 Map 供消费者 await; exceptionally 仅注册吞异常处理器。
        promise.exceptionally(ex -> {
            if (log.isDebugEnabled()) {
                log.debug("投机分类器 promise 异常被吞 (未消费前 signal abort) · CC bashPermissions.ts:1524 command={}",
                    command, ex);
            }
            return null;
        });
        SPECULATIVE_CHECKS.put(command, promise);
        if (log.isDebugEnabled()) {
            log.debug("投机分类器检查已启动 · CC bashPermissions.ts:1525 command={} cwd={} allowDescriptions={}",
                command, cwd, allowDescriptions.size());
        }
        return true;
    }

    /**
     * prompt: allow 描述 · CC original: {@code getBashPromptAllowDescriptions}
     * （bashClassifier.ts:36-38）。
     *
     * <p>CC checked-in stub 恒返 {@code []}（ANT-ONLY，外部构建分类器权限特性关闭），
     * Java 对齐 stub 恒返 {@link List#of()}。因此 {@link #startSpeculativeClassifierCheck}
     * 的 guard 4（allowDescriptions 空）在外部构建下恒拦截，翻转 flag 后仍 {@code return false}。
     *
     * @param context 权限上下文（可 null，stub 忽略）
     * @return 恒空列表（对齐 CC {@code return []}）
     */
    static List<String> getBashPromptAllowDescriptions(ToolPermissionContext context) {
        return List.of();
    }

    /**
     * 当前工作目录 · CC original: {@code getCwd}（utils/cwd.ts:26-32）。
     *
     * <p>CC {@code getCwd()} 返回 AsyncLocalStorage override 或 {@code getCwdState()}，
     * 失败回 {@code getOriginalCwd()}（cwd.ts:26-32）；Java 等价 =
     * {@link CwdResolution#getCwd(String)}（override ?? sessionCwd ?? boundProject ?? user.dir
     * 兜底链）。直读 {@code System.getProperty("user.dir")} 缺会话 cwd 层（worktree/绑定项目场景
     * 下 user.dir 恒为 JVM 启动目录，而 CC getCwd() 返回会话当前 cwd）。
     *
     * @param sessionId 会话 ID（null 回落 user.dir，对齐 CC 无会话兜底）
     * @return 恒非 null 的会话当前工作目录绝对路径
     */
    static String getCwd(String sessionId) {
        return CwdResolution.getCwd(sessionId);
    }

    /**
     * 投机检查 consume（命中即删）· CC original: {@code consumeSpeculativeClassifierCheck}
     * （bashPermissions.ts:1533-1540，{@code get} 后命中则 {@code delete}）。
     *
     * <p>缺席幂等：Map 无该 key 时恒 {@code null}，不抛异常。
     *
     * @param command bash 命令（Map key）
     * @return 被删除的 CompletableFuture，或缺席时 {@code null}
     */
    public static CompletableFuture<SpeculativeClassifierResult> consumeSpeculativeClassifierCheck(String command) {
        if (command == null) {
            return null;
        }
        return SPECULATIVE_CHECKS.remove(command);
    }

    /**
     * 测试钩子（public，test-only）· 直接向 {@link #SPECULATIVE_CHECKS} 种入一条已完成的投机结果。
     *
     * <p><b>WHY 需要此钩子</b>：{@link #SPECULATIVE_CHECKS} 为 {@code private}，且生产
     * {@link #startSpeculativeClassifierCheck} 受 guard 4（{@link #getBashPromptAllowDescriptions}
     * 恒返空）拦截恒不填充 Map，测试跨包（{@code CanUseToolDispatchTest} 位于父包
     * {@code com.nexusai.application.agent.permission}）无法经 start 驱动。此钩子种入
     * {@code matches=true + confidence=high} 的结果，使 {@code ToolPermissionGate} 投机竞速
     * 分支可达，用于验证 buildAllow 对齐（CC useCanUseTool.tsx:149-159）。
     *
     * <p><b>test-only 语义</b>：生产代码不得调用；与 package-private
     * {@link #setClassifierPermissionsEnabled} / {@link #setTranscriptClassifierEnabled}
     * 同款测试注入口（前者因跨包须 public，后者同包可 package-private）。
     *
     * @param command bash 命令（Map key）· CC original: {@code command}（bashPermissions.ts:1483）
     * @param result  已完成的投机结果（matches=true + confidence=high 驱动 allow 分支）
     */
    public static void seedSpeculativeClassifierCheckForTest(
            String command, SpeculativeClassifierResult result) {
        if (command == null) {
            return;
        }
        SPECULATIVE_CHECKS.put(command, CompletableFuture.completedFuture(result));
    }

    /**
     * 清空投机检查 · CC original: {@code clearSpeculativeChecks}
     * （bashPermissions.ts:1543-1545，{@code speculativeChecks.clear()}）。
     *
     * <p>空 Map 幂等：重复调用无副作用。调用点对齐 CC postCompactCleanup.ts:64
     * （压缩后释放失效的投机检查）。
     */
    public static void clearSpeculativeChecks() {
        SPECULATIVE_CHECKS.clear();
        if (log.isDebugEnabled()) {
            log.debug("投机分类器检查缓存已清空 (clearSpeculativeChecks) · CC bashPermissions.ts:1543-1545");
        }
    }

    /**
     * bash 命令分类 stub · CC original: {@code classifyBashCommand}
     * （bashClassifier.ts:40-53）。
     *
     * <p>恒返回 {@code matches:false, confidence:'high', reason:'This feature is disabled'}，
     * 对齐 CC 外部构建（ANT-ONLY stub）行为 —— 投机分类器不产生放行结果。6 参全部忽略
     * （stub），返回 {@code completedFuture} 立即完成的异步结果。
     *
     * @param command                 bash 命令 · CC original: {@code command}（bashClassifier.ts:41）
     * @param cwd                     工作目录 · CC original: {@code cwd}（bashClassifier.ts:42）
     * @param descriptions            描述列表 · CC original: {@code descriptions}（bashClassifier.ts:43）
     * @param behavior                分类行为 {@code 'deny'|'ask'|'allow'} · CC original: {@code behavior}（bashClassifier.ts:44）
     * @param signal                  取消信号 · CC original: {@code signal}（bashClassifier.ts:45）
     * @param isNonInteractiveSession 是否非交互会话 · CC original: {@code isNonInteractiveSession}（bashClassifier.ts:46）
     * @return stub 异步结果（恒 matches:false + reason='This feature is disabled'）
     */
    public static CompletableFuture<SpeculativeClassifierResult> classifyBashCommand(
            String command,
            String cwd,
            List<String> descriptions,
            String behavior,
            AbortController signal,
            boolean isNonInteractiveSession) {
        return CompletableFuture.completedFuture(
            new SpeculativeClassifierResult(false, null, "high", "This feature is disabled"));
    }
}

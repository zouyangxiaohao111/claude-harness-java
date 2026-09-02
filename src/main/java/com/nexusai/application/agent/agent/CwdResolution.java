package com.nexusai.application.agent.agent;

import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.common.SessionProjectRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.function.Supplier;

/**
 * 工作目录域统一入口 · 对齐 CC {@code utils/cwd.ts} {@code pwd()}/{@code getCwd()}
 * 与 {@code bootstrap/state.ts} 四层 STATE 语义。
 *
 * <p><b>CC 真源（自验，不信注释）</b>：
 * <ul>
 *   <li>{@code cwd.ts:19-21} {@code pwd() = cwdOverrideStorage.getStore() ?? getCwdState()}（override ?? STATE.cwd）</li>
 *   <li>{@code cwd.ts:26-32} {@code getCwd() = try pwd() catch → getOriginalCwd()}（失败回 originalCwd）</li>
 *   <li>{@code cwd.ts:12-14} {@code runWithCwdOverride(cwd, fn)} 用 AsyncLocalStorage 在异步上下文覆盖
 *       cwd，并发 agent 各自隔离</li>
 *   <li>{@code state.ts:500-502} {@code getOriginalCwd()} 返回 {@code STATE.originalCwd}
 *       （启动=realpath cwd，随 worktree/resume 重锚）</li>
 *   <li>{@code state.ts:527-533} {@code STATE.cwd} 单一可变，{@code setCwdState} 做 NFC 归一化</li>
 * </ul>
 *
 * <p><b>分层解析</b>（{@link #getCwd(String)}，对齐 CC pwd/getCwd 三层 + user.dir 兜底）：
 * <pre>
 * getCwd(sessionId):
 *   1. override = ThreadLocal CURRENT_OVERRIDE（对齐 CC cwdOverrideStorage AsyncLocalStorage；
 *      runWithCwdOverride 设置，退出 clear）→ 非空返回 normalizeCwd(override)
 *   2. sessionCwd = SessionCwdHolder.get(sessionId)（对齐 CC 单一 STATE.cwd；
 *      worktree 入口与 bash cd 共用此层，后者覆盖前者 [Fix-R1]）→ 非空返回 normalizeCwd
 *   3. boundProject = SessionProjectRoot.getForSession(sessionId)（D-1 裁决：仅绑定，可为 null，
 *      不读 resolve() 回落链——身份域红线）→ 非空返回 normalizeCwd
 *   4. return System.getProperty("user.dir")（JVM 启动目录，对齐 CC 进程启动 cwd 兜底）
 * </pre>
 *
 * <p><b>[CRON-D5 F2 返工] 双键解析</b>：第 2/3 层（sessionCwd/boundProject）的 Map 键形态不同——
 * sessionCwd 层以派生 UUID 串为键（BashTool/EnterWorktreeTool 经 {@code ctx.sessionId()}），
 * boundProject 层以原始键 {@code "sess-xxx"} 为键（bind / resolveSessionProjectRoot 经 streamSessionId）。
 * 传入 sessionId 可能是任一形态（cron 后台线程透传派生 UUID / HTTP MDC 为原始键），每层先试原键、
 * MISS 再试另一形态（{@link #alternateKeyOf}，严格超集仅补缺失路径，两形态键域不重叠无错配）。
 *
 * <p><b>[Fix-R1] 合并存储</b>：worktree 入口（{@code EnterWorktreeTool.ts:95 setCwd}）与 bash
 * {@code cd}（{@code Shell.ts:407 setCwd}）在 CC 端<b>均写同一 {@code STATE.cwd}</b>，后者覆盖前者
 * ——cd 后 getCwd() 返回 cd 子目录。Java 端二者均写 {@link SessionCwdHolder}（sessionCwd 层），
 * {@code WorktreeCwdTracker} 仅记录 worktree 基路径供退出恢复，<b>不作</b> getCwd 优先层。否则活跃
 * worktree 内 cd 后 getCwd() 会返回 worktree 基路径而非 cd 子目录，违反 INV-2。
 *
 * <p><b>getOriginalCwdLayer</b>（{@link #getOriginalCwdLayer(String)}，对齐 CC getOriginalCwd）：
 * <ol>
 *   <li>{@code SessionCwdHolder.getOriginalCwd(sessionId)}（<b>[INV-3]</b> worktree 入口重锚层，对齐 CC
 *       {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())} 写 {@code STATE.originalCwd}=worktreePath；
 *       Exit clear 回落下一层，对齐 CC {@code ExitWorktreeTool.ts:129 setOriginalCwd(originalCwd)} 恢复）→ 非空返回 normalizeCwd</li>
 *   <li>{@code SessionProjectRoot.getForSession(sessionId)}（boundProject，启动锚）→ 非空返回 normalizeCwd</li>
 *   <li>{@code user.dir} 兜底</li>
 * </ol>
 * <b>D-1 裁决：不读 resolve()</b>（resolve() 回落 CLAUDE_PROJECT_DIR env / config home 属身份域，会使 user.dir
 * 成死代码且身份域泄入工作目录域）。worktree 重锚由 {@link SessionCwdHolder#getOriginalCwd} 独立槽承裁
 * （非 cwd 槽 {@code resolve()}），与 cwd 槽（{@link SessionCwdHolder#get}）双独立，对齐 CC
 * {@code STATE.cwd}/{@code STATE.originalCwd} 双字段。
 *
 * <p><b>归一化</b>（{@link #normalizeCwd(String)}，对齐 CC setCwdState NFC + Shell.ts setCwd realpathSync）：
 * realpath 解符号链接 + NFC 归一化；realpath 失败（目录被删/不存在）回原值 + NFC（不抛，对齐 CC catch 兜底）。
 *
 * <p><b>失败语义</b>（对齐 CC getCwd catch → getOriginalCwd）：{@link #getCwd(String)} 各层 safeGet
 * （异常回 null）逐层回落，最终 user.dir 恒非 null，不抛异常。
 *
 * <p><b>OD-1 决策</b>：{@code CwdOverride}（0 生产调用）已<b>合并入本类并删除</b>，无别名/双轨。
 *
 * <p>线程安全：{@link SessionCwdHolder} / {@link SessionProjectRoot} 内部 ConcurrentHashMap；
 * override 层 ThreadLocal（同 JVM 多线程=多 agent 各自隔离，对齐 CC AsyncLocalStorage 跨 async 边界语义）。
 */
public final class CwdResolution {

    private static final Logger log = LoggerFactory.getLogger(CwdResolution.class);

    /**
     * 当前线程显式 cwd override（对齐 CC {@code cwdOverrideStorage} AsyncLocalStorage · cwd.ts:4）。
     * 由 {@link #runWithCwdOverride(String, Supplier)} / {@link #setCurrentOverride(String)} 设置，
     * 退出 {@link #clearCurrentOverride()} 清除。子代理若跨线程需入口 set/finally clear。
     */
    private static final ThreadLocal<String> CURRENT_OVERRIDE = new ThreadLocal<>();

    private CwdResolution() {}

    /**
     * 统一入口：解析 sessionId 对应的当前工作目录（对齐 CC pwd/getCwd）。
     *
     * <p>三层回落 + user.dir 兜底；各层 safeGet 异常回 null；最终恒非 null。
     *
     * <p><b>[CRON-D5 F2 返工] 双键解析</b>：sessionCwd/SessionCwdHolder 层以派生 UUID 串为键
     * （BashTool/EnterWorktreeTool 以 {@code ctx.sessionId()} 登记），boundProject/
     * SessionProjectRoot 层以原始会话键 {@code "sess-xxx"} 为键（bind / resolveSessionProjectRoot 以
     * streamSessionId 登记）。传入的 sessionId 可能是任一形态（cron 后台线程经 QueueItem 透传派生 UUID；
     * HTTP 线程 MDC 为原始键），故每层先试原键、MISS 再试另一形态（{@link #alternateKeyOf}，严格超集，
     * 仅补缺失解析路径，两形态键域不重叠无错配）。
     *
     * @param sessionId 会话 ID（null 时跳过 sessionCwd/boundProject 层，回落 override/user.dir）
     * @return 恒非 null 的归一化 cwd
     */
    public static String getCwd(String sessionId) {
        // L1: override（对齐 CC cwdOverrideStorage.getStore()）
        String override = safeGet(() -> CURRENT_OVERRIDE.get());
        if (override != null && !override.isBlank()) {
            return normalizeCwd(override);
        }
        // L2: sessionCwd（对齐 CC 单一 STATE.cwd · worktree 入口与 cd 共用 [Fix-R1] · F2 双键）
        String sessionCwd = safeGet(() -> SessionCwdHolder.get(sessionId));
        if (sessionCwd == null || sessionCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                sessionCwd = safeGet(() -> SessionCwdHolder.get(alt));
            }
        }
        if (sessionCwd != null && !sessionCwd.isBlank()) {
            return normalizeCwd(sessionCwd);
        }
        // L3: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键）
        String boundProject = safeGet(() -> SessionProjectRoot.getForSession(sessionId));
        if (boundProject == null || boundProject.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                boundProject = safeGet(() -> SessionProjectRoot.getForSession(alt));
            }
        }
        if (boundProject != null && !boundProject.isBlank()) {
            if (isValidDirectory(boundProject)) {
                return normalizeCwd(boundProject);
            }
            // [2026-08-24 cwd 污染修复] boundProject 无效（相对/不存在/非目录，如绑定「抓包流程」）→
            //   返回会污染工具 cwd 致 Bash/Glob/Read 全失败；回落下一层（user.dir）
            log.warn("[CwdResolution] boundProject 无效（需绝对路径且目录存在），回落下一层: sessionId={} "
                + "boundProject={}", sessionId, boundProject);
        }
        // L4: user.dir 兜底（JVM 启动目录，对齐 CC 进程启动 cwd）
        String userDir = System.getProperty("user.dir");
        return normalizeCwd(userDir != null ? userDir : "");
    }

    /**
     * 无参重载：从 {@link RequestContext#sessionId()} 取 sessionId（对齐 CC pwd() 无参取全局 STATE 语义）。
     */
    public static String getCwd() {
        return getCwd(RequestContext.sessionId());
    }

    /**
     * 原始工作目录层（对齐 CC {@code getOriginalCwd()} state.ts:500-502）。
     *
     * <p>分层回落（对齐 CC {@code STATE.originalCwd} 随 worktree/resume 重锚语义）：
     * <ol>
     *   <li><b>[INV-3]</b> {@link SessionCwdHolder#getOriginalCwd(String)}——worktree 入口重锚层
     *       （对齐 CC {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}=worktreePath；
     *       使 worktree 会话内 CLAUDE.md 扫描/存档锚走 worktreePath 非 boundProject）。非空返回 normalizeCwd。</li>
     *   <li>{@link SessionProjectRoot#getForSession(String)}（boundProject，启动锚，对齐 CC 启动=realpath cwd）→ 非空返回 normalizeCwd</li>
     *   <li>{@code user.dir} 兜底</li>
     * </ol>
     *
     * <p><b>D-1 裁决</b>：不读 {@code SessionProjectRoot.resolve()}（回落链涉身份域 env/config home，
     * 会使 user.dir 成死代码且身份域泄入工作目录域，违反 R4 红线）。originalCwd 槽是 {@link SessionCwdHolder}
     * 的<b>独立新槽</b>（非 cwd 槽 {@code resolve()}），D-1 红线不变。
     *
     * <p>用于 CLAUDE.md 扫描根 / 会话存档锚（CC claudemd.ts:851 getOriginalCwd）。
     *
     * @param sessionId 会话 ID
     * @return 恒非 null 的归一化原始 cwd
     */
    public static String getOriginalCwdLayer(String sessionId) {
        // [INV-3] L1: originalCwd 重锚层（worktree 入口 setOriginalCwd(worktreePath)，Exit clear 回落 ·
        //   F2 双键，见 {@link #alternateKeyOf}）
        String originalCwd = safeGet(() -> SessionCwdHolder.getOriginalCwd(sessionId));
        if (originalCwd == null || originalCwd.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                originalCwd = safeGet(() -> SessionCwdHolder.getOriginalCwd(alt));
            }
        }
        if (originalCwd != null && !originalCwd.isBlank()) {
            return normalizeCwd(originalCwd);
        }
        // L2: boundProject（对齐 CC originalCwd 启动目录 · D-1 裁决仅读 getForSession · F2 双键）
        String boundProject = safeGet(() -> SessionProjectRoot.getForSession(sessionId));
        if (boundProject == null || boundProject.isBlank()) {
            String alt = alternateKeyOf(sessionId);
            if (alt != null) {
                boundProject = safeGet(() -> SessionProjectRoot.getForSession(alt));
            }
        }
        if (boundProject != null && !boundProject.isBlank()) {
            if (isValidDirectory(boundProject)) {
                return normalizeCwd(boundProject);
            }
            // [2026-08-24 cwd 污染修复] boundProject 无效（相对/不存在/非目录）→ 回落 user.dir
            log.warn("[CwdResolution] boundProject 无效（需绝对路径且目录存在），回落 user.dir: sessionId={} "
                + "boundProject={}", sessionId, boundProject);
        }
        // L3: user.dir 兜底（JVM 启动目录，对齐 CC 进程启动 cwd）
        String userDir = System.getProperty("user.dir");
        return normalizeCwd(userDir != null ? userDir : "");
    }

    /**
     * 会话标识的"另一形态"键 · CRON-D5 F2 双键解析辅助。
     *
     * <p>[session-id-short] 双层键已统一 short（sess-xxx），本方法退化为恒等（返回入参或 null）。
     * 阶段 1 保留为 {@code @Deprecated} 兼容层：进程内旧 sessionCwd 派生 UUID 键条目仍可经
     * canonicalUuid 分支命中（:210 与新数据无关但无害）；阶段 2（DB 迁移 + transcript 改名完成）
     * 后整段删除。
     *
     * @param sessionId 原键（{@code "sess-xxx"} / 存量派生 UUID 串 / 任意串）
     * @return 另一形态键；无法派生（hash 兜底/随机 UUID/null/空白）→ null
     */
    @Deprecated
    private static String alternateKeyOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (sessionId.startsWith("sess-")) {
            // [session-id-short] 新数据短键与派生键已合一；仅存量派生 UUID 键条目需经此回退命中
            return SessionKeys.canonicalUuid(sessionId).toString();
        }
        // 存量派生 UUID 串 → 原始键（boundProject 层以 "sess-xxx" 为键）；不可逆 → null
        return SessionKeys.originalKey(sessionId);
    }

    /**
     * 无参重载：从 {@link RequestContext#sessionId()} 取 sessionId。
     */
    public static String getOriginalCwdLayer() {
        return getOriginalCwdLayer(RequestContext.sessionId());
    }

    /**
     * realpath + NFC 归一化（对齐 CC {@code setCwdState(cwd.normalize('NFC'))} state.ts:532 +
     * Shell.ts setCwd realpathSync）。
     *
     * <p>realpath 失败（目录被删/不存在/权限不足）回原值 + NFC（不抛，对齐 CC catch 兜底）。
     *
     * @param cwd 待归一化路径
     * @return 归一化路径（null/空 原样返回）
     */
    /** boundProject 有效性校验 · [2026-08-24 cwd 污染修复] 相对/不存在路径是无效绑定
     *  （如「抓包流程」），返回会污染工具 cwd 致 Bash/Glob/Read 全失败；仅绝对路径且目录存在
     *   才算有效。 */
    /** [cwd-consistency 2026-08-25] private→public：LlmAgentLoop 冻结 projectRoot 前校验（与 getCwd 一致化）。 */
    public static boolean isValidDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(path);
            return p.isAbsolute() && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalizeCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return cwd;
        }
        try {
            Path real = Path.of(cwd).toRealPath();
            return Normalizer.normalize(real.toString(), Normalizer.Form.NFC);
        } catch (Exception e) {
            // realpath 失败回原值 + NFC（目录被删/不存在场景，对齐 CC catch 不抛）
            if (log.isDebugEnabled()) {
                log.debug("[CwdResolution] realpath 失败回原值+NFC: cwd={} reason={}", cwd, e.toString());
            }
            return Normalizer.normalize(cwd, Normalizer.Form.NFC);
        }
    }

    /**
     * 在当前线程覆盖 cwd 执行 fn（对齐 CC {@code runWithCwdOverride(cwd, fn)} cwd.ts:12-14）。
     *
     * <p>对齐 CC AsyncLocalStorage.run：override 在 fn 内及同线程调用链生效，fn 返回/异常后 finally clear。
     * 子代理若跨线程需入口 set/finally clear。
     *
     * @param cwd override 工作目录
     * @param fn  待执行逻辑
     * @param <T> 返回类型
     * @return fn 返回值
     */
    public static <T> T runWithCwdOverride(String cwd, Supplier<T> fn) {
        setCurrentOverride(cwd);
        try {
            return fn.get();
        } finally {
            clearCurrentOverride();
        }
    }

    /**
     * 设置当前线程 cwd override（null 等价清除）。
     */
    public static void setCurrentOverride(String cwd) {
        if (cwd == null) {
            CURRENT_OVERRIDE.remove();
        } else {
            CURRENT_OVERRIDE.set(cwd);
        }
    }

    /** 清除当前线程 override（会话处理结束 finally 调）。 */
    public static void clearCurrentOverride() {
        CURRENT_OVERRIDE.remove();
    }

    /**
     * safeGet：异常回 null（对齐 CC getCwd catch → getOriginalCwd 兜底语义）。
     */
    private static String safeGet(Supplier<String> s) {
        if (s == null) {
            return null;
        }
        try {
            return s.get();
        } catch (Exception e) {
            return null;
        }
    }
}

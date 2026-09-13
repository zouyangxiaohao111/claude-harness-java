package com.nexusai.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级 projectRoot 载体 · 对齐 CC {@code bootstrap/state.ts} per-session projectRoot（ODF-A1）。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：
 * <ul>
 *   <li>{@code State.projectRoot} state.ts:45-50 —— 注释即语义：「Stable project root - set once at
 *       startup (including by --worktree flag), never updated by mid-session EnterWorktreeTool. Use
 *       for project identity (history, skills, sessions) not file operations.」</li>
 *   <li>{@code getInitialState()} state.ts:261-279 —— 启动时 {@code realpathSync(cwd())} 冻结为
 *       {@code projectRoot}（:271 resolvedCwd = realpathSync(rawCwd)；:279 projectRoot: resolvedCwd）</li>
 *   <li>{@code getProjectRoot()} state.ts:511-513 —— 返回稳定 projectRoot，会话中不更新
 *       （mid-session EnterWorktreeTool 不得调用 setProjectRoot，skills/history 锚定启动时 cwd）</li>
 *   <li>{@code setProjectRoot()} state.ts:523-525 —— 仅 --worktree 启动 flag 使用</li>
 * </ul>
 *
 * <p><b>为什么引入</b>：旧 Java memory 路径解析链（AutoMemPaths/AgentMemoryDirectory/LlmAgentLoop
 * workspaceDir）恒读 {@code System.getProperty("user.dir")} 单例 → 同一 JVM 内不同 cwd 会话解析到
 * 同一 memory 目录（跨项目记忆污染），违反 CC per-session per-cwd 语义。本类提供按 sessionId 登记的
 * 会话级 projectRoot，生产链经注入 supplier 消费，不再直接读 user.dir。
 *
 * <p><b>查询面（[TL-W2 P11] 收紧后唯一读法）</b>：{@link #getForSession(String)} ——
 * 按 sessionId 直查全局冻结表，<b>miss ⇒ 回源 DB 并回填</b>（{@link #setDbResolver} 注入口，
 * 用户 2026-09-14 裁定 #9）；<b>DB 也查不到 → null</b>（绝不回落 config home / env / user.dir）。
 * 调用方持 sessionId 现算（REST / fork / hook / 启动线程皆可），<b>零 ThreadLocal</b>。
 *
 * <p><b>[TL-W2 P11] 已删除</b>：旧 {@code resolve()} / {@code setCurrent()} / {@code clearCurrent()}
 * + {@code CURRENT ThreadLocal} 读路径 —— 生产 0 调用方（全仓仅测试引用），且 {@code resolve()}
 * 第 3 级回落 {@code CLAUDE_PROJECT_DIR env ?? config home} 把 configHome 当「会话绑定项目」身份返回
 * （与 cwd 身份域红线 D-1 冲突，CwdResolution:163 明写「不读 SessionProjectRoot.resolve()」）。
 * 按死代码决策规则（CC 对应物 = 全局 state.projectRoot，本类已有 sessionId 直查主链）删除，
 * 消除同构陷阱（与 AutoMemPaths 回落失败模式同构）。
 *
 * <p><b>冻结语义</b>（OPD-SPR-03 · CC stable identity）：{@link #setForSession(String, String)} 首写胜，
 * 会话已冻结（已登记 projectRoot）时 rebind 不覆盖；{@link #clearSession(String)} 解除冻结后可再绑定。
 *
 * <p><b>生产接线</b>（IMP-B 闭环 ODF-A1 §8 阻塞项）：ProjectSessionBindingService.bind() →
 * {@link #setForSession(String, String)}（session.mainProjectId → ProjectRecord.path）；
 * unbind() → {@link #clearSession(String)}。
 */
public final class SessionProjectRoot {

    private static final Logger log = LoggerFactory.getLogger(SessionProjectRoot.class);

    /** sessionId → projectRoot（会话绑定登记 · CC state.ts:269-279 启动冻结语义）。 */
    private static final ConcurrentHashMap<String, String> BY_SESSION = new ConcurrentHashMap<>();

    /**
     * 会话绑定查询结果 · <b>三态</b>（[批 4a] 用户裁定 #7/#13 的判据载体）：
     * <ul>
     *   <li>{@link #bound(String)} —— 会话有绑定项目根（且回源校验通过）</li>
     *   <li>{@link #unbound()} —— <b>会话存在</b>（DB 有行）但无绑定项目根 / 绑定失效
     *       ⇒ <b>「数据链路异常」</b>，cwd 域 fail-loud（web 会话必须绑定项目才能进行）</li>
     *   <li>{@link #unknown()} —— <b>无此会话</b>（合成 / 伪造 / 已删 id：MCP 入站调用、standalone
     *       fork / subagent、文档更新器等现造 id）⇒ 属「确无会话」，cwd 域走命名无会话出口
     *       （对齐批 4a 前 user.dir 行为，⛔ 不得对它 fail-loud：那会打死合成 id 的合法路径）</li>
     * </ul>
     */
    public record Lookup(String projectRoot, boolean sessionKnown) {
        /** 绑定命中。 */
        public static Lookup bound(String projectRoot) {
            return new Lookup(projectRoot, true);
        }

        /** 会话存在但无绑定（数据链路异常判据）。 */
        public static Lookup unbound() {
            return new Lookup(null, true);
        }

        /** 无此会话（确无会话判据）。 */
        public static Lookup unknown() {
            return new Lookup(null, false);
        }
    }

    /**
     * DB 回源解析器 · 冻结表 cache miss 时的<b>回源</b>通道（用户裁定 #9：miss ⇒ 回查 DB 并回填）。
     *
     * <p>⛔ 生产不得在 {@code common} 层内直连 mapper：本类是无依赖静态载体，DB 访问必须经
     * {@link #setDbResolver} 显式注入。
     */
    @FunctionalInterface
    public interface DbResolver {
        /** 回源查会话绑定（三态见 {@link Lookup}）；不得回落到 config home / env / user.dir。 */
        Lookup resolve(String sessionId);
    }

    /** 回源解析器（volatile：启动期注册一次，测试可注销）。 */
    private static volatile DbResolver dbResolver;

    /**
     * 注册 DB 回源解析器（生产启动期一次；{@code null} = 注销 —— 测试清理用）。
     *
     * <p><b>生产接线</b> = {@code ToolRegistrationConfig#sessionProjectRootResolver}
     * （全仓唯一 DB 查询实现：sessionId → {@code sessions.main_project_id → projects.path}）。
     * 未接线（纯 JUnit / 非 Spring 直构）→ {@link #lookup} 退化为纯内存查表 + unknown 判据，
     * 行为与改造前一致（夹具不需要 DB，也不会误触 fail-loud）。
     */
    public static void setDbResolver(DbResolver resolver) {
        dbResolver = resolver;
    }

    private SessionProjectRoot() {}

    /**
     * 会话绑定 projectRoot · 首写胜（对齐 CC stable identity · OPD-SPR-03）：会话已冻结时不覆盖，
     * rebind 尝试仅记 debug；clearSession 解除冻结后可再绑定。
     */
    public static void setForSession(String sessionId, String projectRoot) {
        if (sessionId == null || projectRoot == null || projectRoot.isEmpty()) {
            return;
        }
        // [2026-08-24 cwd 污染修复] 绑定路径校验：绝对路径 + 目录存在，无效拒绝（不绑定污染 cwd——
        //   否则 CwdResolution 返回无效路径致工具全失败）
        if (!isValidProjectRoot(projectRoot)) {
            log.warn("[SessionProjectRoot] 拒绝绑定无效项目根（需绝对路径且目录存在）: sessionId={} "
                + "projectRoot={}", sessionId, projectRoot);
            return;
        }
        String prev = BY_SESSION.putIfAbsent(sessionId, projectRoot);
        if (prev == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionProjectRoot] 会话绑定 projectRoot: sessionId={} projectRoot={}", sessionId, projectRoot);
            }
        } else if (log.isDebugEnabled() && !prev.equals(projectRoot)) {
            log.debug("[SessionProjectRoot] 会话已冻结不覆盖（CC stable identity · OPD-SPR-03）: "
                + "sessionId={} frozen={} attempt={}", sessionId, prev, projectRoot);
        }
    }

    /** 绑定项目根有效性校验 · [2026-08-24 cwd 污染修复] 绝对路径 + 目录存在；相对/不存在路径
     *  （如「抓包流程」）拒绝绑定，防 CwdResolution 返回无效 cwd 致工具全失败。 */
    private static boolean isValidProjectRoot(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return false;
        }
        try {
            Path p = Path.of(projectRoot);
            return p.isAbsolute() && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取会话绑定 projectRoot（null = 未冻结 · OPD-SPR-03 允许未冻结查询）。
     *
     * <p><b>miss ⇒ 回源 DB 并回填</b>（用户裁定 #9）：内存冻结表未命中 → {@link DbResolver} 回查
     * DB → 命中则 {@link #setForSession(String, String)} 回填后返回。DB 未给出绑定 → null
     * （既含「有会话但未绑定」也含「无此会话」，故绝不回落 config home / env / user.dir）。
     * <p>需要区分这两态（cwd 域的 fail-loud 判据）请用 {@link #lookup(String)}。
     *
     * <p>注：未绑定的会话（DB 无 {@code main_project_id}）每次读都会回源一次 DB ——
     * 不设负缓存，避免「先读 miss → 后 bind」被负缓存钉死（PK 查询，代价可忽略）。
     */
    public static String getForSession(String sessionId) {
        return lookup(sessionId).projectRoot();
    }

    /**
     * 三态查询（[批 4a]）· {@link Lookup}：绑定 / 有会话但未绑定 / 无此会话。
     *
     * <p>内存冻结表 miss ⇒ 回源 DB 并回填（用户裁定 #9 · Redis miss → 回源 → 回填）。
     * cwd 域用 {@code sessionKnown()} 区分「数据链路异常（fail-loud）」与「确无会话（无会话出口）」。
     */
    public static Lookup lookup(String sessionId) {
        if (sessionId == null) {
            return Lookup.unknown();
        }
        String cached = BY_SESSION.get(sessionId);
        if (cached != null) {
            return Lookup.bound(cached);
        }
        return refillFromDb(sessionId);
    }

    /**
     * 回源 DB + 回填（Redis miss → 回源 → 回填语义）。
     *
     * <p>回源值无效（非绝对路径 / 目录不存在 / 空白）⇒ <b>不回填</b>；若 DB 显示会话存在 ⇒
     * 「有会话但绑定失效」= 数据链路异常（{@link Lookup#unbound()}）。与
     * {@code LlmAgentLoop.tryResolveBoundProjectFromDb} 同一判据（无效绑定目录不冒充项目根，
     * 否则 memory 域会按无效路径建目录 = 跨项目污染）。
     */
    private static Lookup refillFromDb(String sessionId) {
        DbResolver resolver = dbResolver;
        if (resolver == null) {
            // 未接线（纯 JUnit / 非 Spring 直构）→ 无会话判据（行为与批 4a 前一致：不抛）
            return Lookup.unknown();
        }
        Lookup fromDb;
        try {
            fromDb = resolver.resolve(sessionId);
        } catch (Exception e) {
            log.warn("[SessionProjectRoot] 会话 projectRoot 回源 DB 失败，按无此会话处理: sessionId={} err={}",
                sessionId, e.toString());
            return Lookup.unknown();
        }
        if (fromDb == null) {
            return Lookup.unknown();
        }
        String path = fromDb.projectRoot();
        if (path == null || path.isBlank()) {
            return fromDb.sessionKnown() ? Lookup.unbound() : Lookup.unknown();
        }
        if (!isValidProjectRoot(path)) {
            log.warn("[SessionProjectRoot] 回源 DB 得到无效项目根（需绝对路径且目录存在），按「有会话但绑定"
                + "失效」处理（不回填）: sessionId={} projectRoot={}", sessionId, path);
            return Lookup.unbound();
        }
        setForSession(sessionId, path);
        // 回读缓存作为唯一来源：并发下可能已被他线程回填（首写胜），返回缓存值保证「返回 == 缓存」。
        String cached = BY_SESSION.get(sessionId);
        String resolved = cached != null ? cached : path;
        log.info("[SessionProjectRoot] 冻结表 miss → 回源 DB 并回填: sessionId={} projectRoot={}",
            sessionId, resolved);
        return Lookup.bound(resolved);
    }

    /** 清除会话绑定（会话销毁/解绑项目时调用）。 */
    public static void clearSession(String sessionId) {
        if (sessionId != null) {
            BY_SESSION.remove(sessionId);
        }
    }

    /** 测试钩子：清空全部会话登记（[TL-W2 P11] 原含 CURRENT ThreadLocal 清理，已随死代码删除）。
     *  <p>不回源解析器本身 —— 注册过 {@link #setDbResolver} 的测试须自行注销（否则跨用例污染）。 */
    public static void reset() {
        BY_SESSION.clear();
    }
}

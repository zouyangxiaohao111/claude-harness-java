package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;

/**
 * prompt 域会话态惰性三槽 · 裁定 #10 第二域（{@code agent/prompt}）的承载。
 *
 * <p><b>为什么需要它（承载的边界）</b>：CC 的 system prompt 组装读的是<b>进程级 ambient 状态</b>——
 * {@code computeSimpleEnvInfo} 内 {@code const cwd = getCwd()}（prompts.ts:640）、
 * {@code isWorktree = getCurrentWorktreeSession() !== null}（prompts.ts:641）、
 * {@code getScratchpadDir() = join(getProjectTempDir(), getSessionId(), 'scratchpad')} 中的
 * {@code getOriginalCwd()}（filesystem.ts:376-388）。CC 单进程单会话 ⇒ 这些 ambient 读恒 = 会话态；
 * 本仓 1 JVM : N 会话 ⇒ ambient 读 = 后端启动目录（错）。本类把「边界解析一次 ⇒ 槽下传」的形态
 * 落到 prompt 域：{@link SystemPromptSections} 的 section compute 在 {@code ForkJoinPool}
 * （{@link SystemPromptSectionRegistry#resolveAll} 的 {@code supplyAsync}）上跑，读不到任何
 * 隐式会话槽，只能从本对象取值。
 *
 * <p><b>惰性（本类存在的另一半理由）</b>：三个槽<b>全部惰性</b>（首次读时才解析），镜像
 * {@code PromptFnContext.originalCwd} 的 {@code Supplier} 形态（该处 javadoc 已论证：传已解析值会
 * 在「本不解析 cwd 的路径」上<b>新增抛出面</b>）。具体到本域：
 * <ul>
 *   <li>section 缓存命中短路（{@code SystemPromptSectionRegistry:85}）⇒
 *       {@code env_info_simple} / {@code scratchpad} 的 compute 在 turn2..N 根本不执行 ⇒
 *       槽不被读；</li>
 *   <li>{@code assemble} 根本不被调用的路径（{@code EffectiveSystemPromptBuilder:143-146} override
 *       早退 / {@code :214-216} custom 替换 default）⇒ 连 input 都不构造。</li>
 * </ul>
 * 若槽改成构造期已解析值，解析面会从「每会话首次组装（及每次 clear 后）」扩大到「每次组装」——
 * 在那些路径上新增抛出。⛔ <b>不得</b>把槽改成已解析 {@code String}。
 *
 * <p><b>⚠️ 两个 cwd 槽不可互换</b>（同 {@code SlashCommandContext:162-164} 的警告）：
 * <ul>
 *   <li>{@link #cwd()} —— {@code getCwd} 语义（对齐 CC {@code STATE.cwd} / {@code getCwd()}，
 *       {@code cwd.ts:26-32}）：<b>受 bash {@code cd} 影响</b>（{@code Shell.ts:407 setCwd} 写同一
 *       字段），也受 worktree 入口重锚影响（{@code EnterWorktreeTool.ts:95}）；</li>
 *   <li>{@link #originalCwd()} —— {@code getOriginalCwdLayer} 语义（对齐 CC
 *       {@code STATE.originalCwd} / {@code getOriginalCwd()}，{@code state.ts:500-502}）：
 *       <b>不受 bash {@code cd} 影响</b>，随 worktree 重锚（{@code EnterWorktreeTool.ts:96}）。
 *       scratchpad 目录派生用它（CC {@code getProjectTempDir} 用 {@code getOriginalCwd()}）。</li>
 * </ul>
 * 发生过 {@code cd} 的会话里两者<b>必然不同值</b>；用 {@link #cwd()} 顶替 {@link #originalCwd()}
 * 会让 scratchpad / CLAUDE.md 扫描锚漂到 cd 后的子目录。
 *
 * <p><b>线程安全（⚠️ 必须照做，不得照抄 r10 的裸字段写法）</b>：三个槽各自
 * <b>{@code volatile} 标记 + {@code synchronized} 双检</b>。理由：本对象的读点分散在
 * {@code ForkJoinPool} 的多个并发 section compute 上，<b>多 section 会并发读同一槽</b>；
 * 而批 r10 的 {@code SlashCommandContext} javadoc 自陈「非线程安全（解析结果非 volatile）」——
 * 那种写法在它的用途下是对的（每次 dispatch 新建一个 ctx、不跨线程发布），<b>在本域会出竞态</b>。
 * 先例（同款双检）：{@link GitStatusProvider#getGitStatus()} / {@code SystemPromptContextProvider}。
 *
 * <p><b>异常不缓存</b>（对齐 {@code SlashCommandContext} 的这条语义）：解析抛出时标记不置位 ⇒
 * 下次调用重新解析并重新抛，fail-loud 可重复暴露。
 *
 * <p><b>无会话</b>：{@code sessionId} 为 null/空白时，{@code CwdResolution} 走「确无会话」路由
 * （≥WARN + 命名出口 = 进程 {@code user.dir}），{@link #worktreeBound()} 恒 {@code false}
 * （对齐 CC「无会话级 worktree 会话 = 非 worktree」）。
 */
public final class PromptSessionSlots {

    /** 本槽所属会话（可为 null/空白 = 确无会话）。 */
    private final String sessionId;

    /** {@link #cwd()} 的 memoize 值 · 对齐 CC {@code getCwd()}。 */
    private volatile String cwd;
    /** {@link #cwd()} 的「已解析」标记（volatile ⇒ 双检可见性；异常时不置位）。 */
    private volatile boolean cwdResolved;

    /** {@link #originalCwd()} 的 memoize 值 · 对齐 CC {@code getOriginalCwd()}。 */
    private volatile String originalCwd;
    /** {@link #originalCwd()} 的「已解析」标记。 */
    private volatile boolean originalCwdResolved;

    /** {@link #worktreeBound()} 的 memoize 值（解析结果恒非 null ⇒ 用 null 即「未解析」）。 */
    private volatile Boolean worktreeBound;

    /**
     * 唯一工厂（生产边界调用点 = {@code LlmAgentLoop.buildSystemPromptAssemblyInput}）。
     *
     * <p>⛔ <b>本工厂不做任何解析</b>（惰性）：构造本身不得触碰 {@code CwdResolution} /
     * {@code SessionCwdHolder}。
     *
     * @param sessionId 会话 ID（short 形态 {@code sess-xxx}；null/空白 = 确无会话）
     * @return 全新槽实例（每次组装一个；实例内三槽各自 memoize）
     */
    public static PromptSessionSlots of(String sessionId) {
        return new PromptSessionSlots(sessionId);
    }

    private PromptSessionSlots(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 本槽所属会话（可为 null）。 */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 会话当前工作目录 · <b>{@code getCwd} 语义</b>（受 bash {@code cd} 覆盖，对齐 CC
     * {@code getCwd()} {@code cwd.ts:26-32}）。首次读时解析并 memoize；解析抛出<b>不</b>缓存。
     *
     * <p>⛔ 不得用 {@link #originalCwd()} 顶替（见类 javadoc）。
     *
     * @return 归一化会话 cwd（无会话 ⇒ 进程 {@code user.dir}）
     */
    public String cwd() {
        if (cwdResolved) {
            return cwd;
        }
        synchronized (this) {
            if (!cwdResolved) {
                // 异常路径：赋值发生在解析成功之后 ⇒ 抛出时 cwdResolved 保持 false（fail-loud 可重复）
                cwd = CwdResolution.getCwd(sessionId);
                cwdResolved = true;
            }
            return cwd;
        }
    }

    /**
     * 会话原始工作目录 · <b>{@code getOriginalCwdLayer} 语义</b>（<b>不受</b> bash {@code cd} 影响，
     * 随 worktree 重锚，对齐 CC {@code getOriginalCwd()} {@code state.ts:500-502}）。
     * 首次读时解析并 memoize；解析抛出<b>不</b>缓存。
     *
     * <p>消费点 = {@link SystemPromptSections#getScratchpadDir(String, java.util.function.Supplier)}
     * （CC {@code getProjectTempDir} 用 {@code getOriginalCwd()}，filesystem.ts:376-378）。
     *
     * @return 归一化原始 cwd（无会话 ⇒ 进程 {@code user.dir}）
     */
    public String originalCwd() {
        if (originalCwdResolved) {
            return originalCwd;
        }
        synchronized (this) {
            if (!originalCwdResolved) {
                originalCwd = CwdResolution.getOriginalCwdLayer(sessionId);
                originalCwdResolved = true;
            }
            return originalCwd;
        }
    }

    /**
     * 本会话是否经 {@code EnterWorktree} 进入 git worktree · 对齐 CC
     * {@code getCurrentWorktreeSession() !== null}（prompts.ts:675-681，仅 '!' 子弹消费）。
     *
     * <p>会话级判定（非 git 级检测）；无会话 ⇒ {@code false}。
     *
     * @return true = 该会话处于 worktree 内且未退出
     */
    public boolean worktreeBound() {
        Boolean v = worktreeBound;
        if (v != null) {
            return v;
        }
        synchronized (this) {
            if (worktreeBound == null) {
                worktreeBound = SessionCwdHolder.isWorktreeBound(sessionId);
            }
            return worktreeBound;
        }
    }
}

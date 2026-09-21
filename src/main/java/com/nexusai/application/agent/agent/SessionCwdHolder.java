package com.nexusai.application.agent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级可变工作目录载体 · 对齐 CC {@code bootstrap/state.ts} 单一可变 {@code STATE.cwd} 字段
 * （{@code getCwdState}/{@code setCwdState} state.ts:527-533）。
 *
 * <p><b>CC 真源（自验，不信注释）</b>：
 * <ul>
 *   <li>{@code state.ts:527-529} {@code getCwdState()} 返回 {@code STATE.cwd}（单一可变字段）</li>
 *   <li>{@code state.ts:531-533} {@code setCwdState(cwd)} 写 {@code STATE.cwd = cwd.normalize('NFC')}
 *       （NFC 归一化）</li>
 *   <li>{@code Shell.ts:407} bash {@code cd} 后 {@code setCwd(newCwd, cwd)} → {@code setCwdState(physicalPath)}
 *       （realpath+NFC，Shell.ts:447-464 setCwd）</li>
 *   <li>{@code EnterWorktreeTool.ts:95} 进 worktree 时 {@code setCwd(worktreeSession.worktreePath)}
 *       → 同写 {@code STATE.cwd}（与 bash cd 写同一字段，后者覆盖前者）</li>
 * </ul>
 *
 * <p><b>[Fix-R1] 合并存储语义</b>：CC 的 {@code STATE.cwd} 是<b>单一可变字段</b>，worktree 入口
 * 与 bash {@code cd} <b>均写此字段</b>。Java 端等价为 {@code SessionCwdHolder} 单一可变存储：
 * worktree 入口（{@code EnterWorktreeTool}）与 bash {@code cd}（{@code BashTool} 跑完读回）二者
 * 调 {@link #set(String, String)} 写同一 sessionId 槽，{@code cd} 覆盖 worktree 初始值——对齐 CC
 * 「cd 后 getCwd() 返回 cd 子目录」行为（INV-2）。{@code WorktreeCwdTracker} 仅记录 worktree 基路径
 * 供 {@code ExitWorktreeTool} 退出恢复，<b>不作</b> {@code CwdResolution.getCwd()} 优先层。
 *
 * <p><b>归一化</b>：{@link #set(String, String)} 入口做 realpath + NFC 归一化（对齐 CC
 * {@code setCwdState(cwd.normalize('NFC'))} + Shell.ts setCwd realpathSync）。realpath 失败（目录被删）
 * 回落原值 + NFC（不抛，对齐 CC catch 兜底语义）。
 *
 * <p><b>生命周期</b>：会话创建不预置（初始 null → 回落 boundProject/user.dir）；bash 前台命令跑完
 * cd 变化由 WF-2A 接线 {@link #set}；worktree 退出由 {@code ExitWorktreeTool} 调 {@link #clear}
 * 恢复 pre-worktree（对齐 CC 退出 worktree 后 STATE.cwd 回到 pre-worktree）；resume 不持久化（已知简化，
 * 登记 OD-6）。
 *
 * <p><b>[步骤 6 · cwd 语义对齐 CC · 读层 / 写入路径全集 / 子代理语义]</b>（全部自验 grep，不信注释）：
 * <ul>
 *   <li><b>读层</b>：CC {@code utils/cwd.ts:19-21} {@code pwd() = cwdOverrideStorage.getStore() ?? getCwdState()}
 *       —— AsyncLocalStorage override <b>只覆盖读、不覆盖写</b>（{@code setCwdState} 永远写<b>进程级</b>
 *       {@code STATE.cwd}，{@code state.ts:531-533}）。本仓无 AsyncLocalStorage（{@code [S2 F-07]} 已删
 *       override 通道，理由见 {@link CwdResolution} 类注释）⇒ 本类即「唯一写槽」，按 sessionId 分区
 *       （CC 一进程 = 一会话；本仓多租户 ⇒ 进程级单例必须降为会话级）。
 *       ⚠️ 读层的子代理隔离<b>不由本类提供</b>：本仓子代理的「有效 cwd」经
 *       {@code ToolUseContext.effectiveCwd} <b>值</b>承载（非 ThreadLocal 覆盖）。</li>
 *   <li><b>写入路径全集</b>（CC 真源穷举 + 本仓 grep 复验）：① shell 回读（<b>主线程门控</b> ——
 *       CC {@code Shell.ts:395} 的 {@code !preventCwdChanges}，本仓 {@code BashTool}/{@code PowerShellTool}
 *       回读处同款 isMainThread 门控）；② 越界重置（CC {@code BashTool.tsx:702-707}
 *       {@code resetCwdIfOutsideProject} → 本仓<b>未接</b>，登记）；③ <b>{@code EnterWorktreeTool} /
 *       {@code ExitWorktreeTool}</b>（CC {@code :95} / {@code :126} 调 {@code setCwd}）；④
 *       {@code WorktreeExitDialog} / /clear / setup 启动 / /resume worktree 恢复 / 进程入口；
 *       ⑤ 本仓 {@code ResumeService}（resume 首 cwd 回填）。</li>
 *   <li>⭐ <b>③ 这条写入路径是「子代理可达」的，而 CC 与本仓<b>同样放行</b></b>（实测 grep）：
 *       CC {@code constants/tools.ts:55-71} 把 {@code ENTER/EXIT_WORKTREE_TOOL_NAME} 放进
 *       {@code ASYNC_AGENT_ALLOWED_TOOLS}，且两工具文件对 {@code agentId}/{@code isMainThread}
 *       引用数为 <b>0</b>；本仓 {@code AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS} 同样含这两项，
 *       {@code EnterWorktreeTool}/{@code ExitWorktreeTool} 亦<b>无</b> agentId 门控。
 *       ⇒ 这是<b>有意的行为对齐</b>（子代理可经 worktree 工具改会话 cwd），⛔ <b>不得</b>读成「已隔离」：
 *       「子代理绝不动会话 cwd」作为<b>整体保证是假的</b>；本仓真正成立的保证只有一句 ——
 *       <b>子代理的 shell {@code cd} 不写主会话 cwd 槽</b>（由 shell 回读处的主线程门控提供，
 *       见 {@code BashTool} 回读注释与 {@code BashToolTest} 的断言）。</li>
 *   <li><b>子代理 shell cd 的语义</b>（以 CC 实际行为为准）：CC 子代理的
 *       {@code preventCwdChanges = !isMainThread = true}（{@code BashTool.tsx:642-643}）⇒ 回读被跳过
 *       ⇒ cd <b>不跨其自身多次 Bash 调用持久化</b>（下一条仍以 {@code pwd()} 起）。本仓门控后同义。</li>
 * </ul>
 *
 * <p>线程安全：{@link ConcurrentHashMap}，同 JVM 多会话按 sessionId 隔离。
 */
public final class SessionCwdHolder {

    private static final Logger log = LoggerFactory.getLogger(SessionCwdHolder.class);

    /** sessionId → 会话可变 cwd（对齐 CC 单一 STATE.cwd，null = 未设置回落下一层）。 */
    private static final Map<String, String> BY_SESSION = new ConcurrentHashMap<>();

    /**
     * sessionId → 会话 originalCwd 重锚层（对齐 CC {@code STATE.originalCwd} state.ts:46，
     * null = 未设置回落 boundProject/user.dir）。<b>[INV-3]</b> worktree 入口（{@code EnterWorktreeTool}
     * 对齐 CC {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}）与 worktree 退出（对齐 CC
     * {@code ExitWorktreeTool.ts:129 setOriginalCwd(originalCwd)} 恢复）写此槽。
     *
     * <p><b>独立于 {@link #BY_SESSION}</b>（cwd 槽）：对齐 CC {@code STATE.cwd} 与 {@code STATE.originalCwd}
     * 双字段（state.ts:46/48）。worktree 入口两槽同写 worktreePath（对齐 CC :95-96 setCwd+setOriginalCwd
     * 均写 worktreePath）；活跃 worktree 内 bash cd 仅写 cwd 槽（对齐 CC Shell.ts:407 setCwd 仅写 STATE.cwd），
     * originalCwd 槽不受冲——CLAUDE.md 扫描/存档锚稳定在 worktreePath（INV-3）。
     */
    private static final Map<String, String> ORIGINAL_BY_SESSION = new ConcurrentHashMap<>();

    /**
     * sessionId → 会话是否在 git worktree 内（对齐 CC 模块级 {@code currentWorktreeSession}
     * worktree.ts:156-158，EnterWorktree 写 / Exit 置 null / resume 恢复）。
     *
     * <p><b>[SP-11] worktree 判定改回 CC 会话级</b>：CC 的 isWorktree（prompts.ts:675-681）=
     * {@code getCurrentWorktreeSession() !== null}（模块级会话判定），仅「EnterWorktree 工具进入」
     * 消费 '!' 子弹（'This is a git worktree'）。Java 原 git 级检测（.git 普通文件）为超集偏差
     * （手工 git worktree add 亦命中），用户已拍板改回 CC 判定 → 本 Map 会话级隔离。
     *
     * <p><b>in-memory 已知简化</b>：resume 不持久化（对齐 CC sessionRestore.ts 恢复
     * currentWorktreeSession 有差异，登记 OD-6 同款）；手工 git worktree add（未走 EnterWorktree
     * 工具）不再命中 '!' 子弹——正是 CC 语义（超集收窄）。
     */
    private static final Map<String, Boolean> WORKTREE_BOUND_BY_SESSION = new ConcurrentHashMap<>();

    private SessionCwdHolder() {}

    /**
     * 设置会话可变 cwd（realpath + NFC 归一化，对齐 CC setCwdState + Shell.ts setCwd）。
     *
     * <p>worktree 入口与 bash cd 共用此方法写同一 sessionId 槽，后者覆盖前者（对齐 CC 单 STATE.cwd）。
     *
     * @param sessionId 会话 ID（null 静默忽略）
     * @param cwd 新工作目录（null/空 静默忽略）
     */
    public static void set(String sessionId, String cwd) {
        if (sessionId == null || cwd == null || cwd.isBlank()) {
            return;
        }
        String normalized = CwdResolution.normalizeCwd(cwd);
        BY_SESSION.put(sessionId, normalized);
        if (log.isDebugEnabled()) {
            log.debug("[SessionCwdHolder] 设置会话 cwd: sessionId={} cwd={} normalized={}",
                    sessionId, cwd, normalized);
        }
    }

    /**
     * 读取会话可变 cwd（null = 未设置，回落下一层）。
     */
    public static String get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return BY_SESSION.get(sessionId);
    }

    /**
     * 清除会话 cwd（退出 worktree / 会话销毁调用）。
     * <p>[Fix-R1] 退出 worktree 时 clear 即恢复 pre-worktree（worktree 入口写入被清，回落 boundProject/user.dir）。
     */
    public static void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String removed = BY_SESSION.remove(sessionId);
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[SessionCwdHolder] 清除会话 cwd: sessionId={} removed={}", sessionId, removed);
        }
    }

    /** 测试钩子：清空全部登记。 */
    public static void reset() {
        BY_SESSION.clear();
        ORIGINAL_BY_SESSION.clear();
        WORKTREE_BOUND_BY_SESSION.clear();
    }

    // ===== [SP-11] 会话级 worktree 绑定标志（对齐 CC currentWorktreeSession 模块级语义） =====

    /**
     * 标记会话已进入 git worktree（对齐 CC EnterWorktreeTool 写 currentWorktreeSession）。
     *
     * <p>EnterWorktreeTool 进入 worktree 成功后调用（:EnterWorktreeTool 对齐 CC EnterWorktreeTool.ts
     * 写 currentWorktreeSession）；sessionId null 静默忽略（无会话上下文不登记）。
     *
     * @param sessionId 会话 ID
     */
    public static void markWorktree(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        WORKTREE_BOUND_BY_SESSION.put(sessionId, Boolean.TRUE);
        if (log.isInfoEnabled()) {
            log.info("[SessionCwdHolder] 会话标记进入 worktree: sessionId={}", sessionId);
        }
    }

    /**
     * 清除会话 worktree 绑定（对齐 CC ExitWorktreeTool 置 null）。
     *
     * <p>ExitWorktreeTool 退出 worktree 时调用（:ExitWorktreeTool 对齐 CC ExitWorktreeTool.ts
     * currentWorktreeSession = null）；sessionId null 静默忽略。
     *
     * @param sessionId 会话 ID
     */
    public static void clearWorktree(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        WORKTREE_BOUND_BY_SESSION.remove(sessionId);
        if (log.isInfoEnabled()) {
            log.info("[SessionCwdHolder] 会话清除 worktree 绑定: sessionId={}", sessionId);
        }
    }

    /**
     * 会话是否处于 git worktree 内（对齐 CC prompts.ts:675-681 isWorktree =
     * {@code getCurrentWorktreeSession() !== null}，仅 '!' 子弹消费）。
     *
     * @param sessionId 会话 ID（null/未标记 → false，对齐 CC 无会话级 worktree 会话 = 非 worktree）
     * @return true = 该会话经 EnterWorktree 工具进入 worktree 且未退出
     */
    public static boolean isWorktreeBound(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(WORKTREE_BOUND_BY_SESSION.get(sessionId));
    }

    // ===== [INV-3] originalCwd 重锚层（对齐 CC STATE.originalCwd state.ts:46/515-517） =====

    /**
     * 设置会话 originalCwd 重锚层（realpath + NFC 归一化，对齐 CC setOriginalCwd state.ts:515-517）。
     *
     * <p><b>[INV-3]</b> worktree 入口由 {@code EnterWorktreeTool.applySessionCwd} 调用，对齐 CC
     * {@code EnterWorktreeTool.ts:96 setOriginalCwd(getCwd())}（此时 getCwd()=worktreePath，setCwd 已先写）。
     * 独立于 cwd 槽（{@link #set}），对齐 CC {@code STATE.cwd}/{@code STATE.originalCwd} 双字段。
     *
     * @param sessionId 会话 ID（null 静默忽略）
     * @param cwd 重锚的原始目录（null/空 静默忽略）
     */
    public static void setOriginalCwd(String sessionId, String cwd) {
        if (sessionId == null || cwd == null || cwd.isBlank()) {
            return;
        }
        String normalized = CwdResolution.normalizeCwd(cwd);
        ORIGINAL_BY_SESSION.put(sessionId, normalized);
        if (log.isDebugEnabled()) {
            log.debug("[SessionCwdHolder] 设置会话 originalCwd: sessionId={} cwd={} normalized={}",
                    sessionId, cwd, normalized);
        }
    }

    /**
     * 读取会话 originalCwd 重锚层（null = 未设置，回落 boundProject/user.dir）。
     *
     * <p><b>[INV-3]</b> 由 {@code CwdResolution.getOriginalCwdLayer} 读，对齐 CC {@code getOriginalCwd}
     * state.ts:500-502 返回 {@code STATE.originalCwd}。worktree 入口重锚后返回 worktreePath，
     * 否则 null（回落 boundProject）。
     */
    public static String getOriginalCwd(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return ORIGINAL_BY_SESSION.get(sessionId);
    }

    /**
     * 清除会话 originalCwd 重锚层（退出 worktree 调用）。
     * <p>[INV-3] 对齐 CC {@code ExitWorktreeTool.ts:129 setOriginalCwd(originalCwd)} 退出恢复。Java 端无
     * pre-worktree originalCwd 持久化（boundProject 是稳定身份不变），clear 即回落 boundProject
     * （= pre-worktree originalCwd 语义）。
     */
    public static void clearOriginalCwd(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String removed = ORIGINAL_BY_SESSION.remove(sessionId);
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[SessionCwdHolder] 清除会话 originalCwd: sessionId={} removed={}", sessionId, removed);
        }
    }
}

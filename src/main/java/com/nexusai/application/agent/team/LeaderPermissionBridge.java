package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Leader Permission Bridge · 对齐 CC {@code utils/swarm/leaderPermissionBridge.ts}。
 *
 * <p>模块级 setter/getter registry：允许 REPL / 生产确认表面注册其 ToolUseConfirmQueue setter 与
 * ToolPermissionContext setter，供非 React 代码（in-process runner 的 worker 权限解析 / leader inbox
 * 分发）复用 —— 这样 in-process teammate 请求权限时走 leader 的标准 ToolUseConfirm 对话框（带 worker
 * badge），而非退化为 mailbox 转发。对齐 CC 描述「makes the REPL's queue setter and permission context
 * setter accessible from non-React code」（leaderPermissionBridge.ts:7-10）。
 *
 * <p><b>REV-FIX-6 / WF 合并说明</b>：原 {@code submit/resolve} 内存 Map stub 与 CC API
 * （register/get/unregister 三组）完全不符且 CC 无对应能力（grep -rn "submit\|resolve"
 * leaderPermissionBridge.ts → 无）→ 已删除。CC leaderPermissionBridge.ts 有对应 registry 模块，
 * 故整类保留、重写为 CC registry API。
 *
 * <p><b>[T2 · 会话分桶]</b> 改前本类是<b>进程级 static 单槽</b>（{@code static volatile} 两个字段，
 * last-writer-wins、无键）—— 逐条照抄 CC <b>2.1.88</b> 的形态（已核源：
 * {@code Open-ClaudeCode/src/utils/swarm/leaderPermissionBridge.ts:25-26} 两个模块级
 * {@code let registeredSetter / registeredPermissionContextSetter}）。该形态的前提是 CC 单会话 CLI
 * （一进程 = 一会话），而本仓是<b>天然多会话</b>常驻 JVM（multi-session-vs-cc-single-session 铁律）：
 * 全局单槽在 `会话 A 注册 → 会话 B 注册 → 会话 A 查` 的时序下让 B 的 setter 冒充 A 的表面
 * （跨会话表面串台），且 A 的注册被静默覆盖、无任何注销通道 ⇒ 团队权限面断裂。
 *
 * <p>故按用户裁定「LeaderPermissionBridge 需要改成会话级的，我们本身就是多会话，按 session 分桶」
 * 改为 <b>sessionId → setter 分桶</b>（等价于 CC 2.1.278 把模块级槽改成按 SessionController 分桶的
 * 注册表：每个会话各自注册/注销自己的 setter）。本仓按既有会话级范式实现（同
 * {@link TeamHelpers#registerTeamForSessionCleanup} 的 {@code Map<sessionId, …>} +
 * {@link #clearSession(String)} 会话结束清理），⛔ 不照抄 TS 的 WeakMap 结构。
 *
 * <p><b>⛔ 不保留全局兜底桶</b>：null/blank sessionId 一律 fail-loud 拒绝（{@code log.warn} + no-op），
 * 因为它恰好就是本任务要消除的「无键全局表面」——回落它会以相反方向重现串台。
 *
 * <p>本类提供<b>两组</b>会话分桶注册（ML-3 统一：删除 Java-only
 * {@code LeaderConfirmHandler}/{@code LeaderDecision} 一次性 ask→future 模型，与 CC 单一
 * {@code registeredSetter} 队列推送形态对齐）：
 * <ul>
 *   <li><b>CC 队列 setter 注册</b>（对齐 CC {@code SetToolUseConfirmQueueFn} :16-18）：
 *       {@link #registerLeaderToolUseConfirmQueue(String, SetToolUseConfirmQueueFn)} /
 *       {@link #getLeaderToolUseConfirmQueue(String)} /
 *       {@link #unregisterLeaderToolUseConfirmQueue(String)}；
 *       消费方 = {@link SwarmLeaderPermissionDispatcher}（leader 侧 inbox 分发，按 team config
 *       {@code leadSessionId} 取本会话桶，经 setter 推 {@link ToolUseConfirmEntry} 到 leader UI 队列）
 *       + 生产注册方 {@code LeaderPermissionConfirmBridge}（经 TeamCreateTool 按 leader 会话注册）。</li>
 *   <li><b>权限上下文 setter 注册</b>（对齐 CC {@code SetToolPermissionContextFn} :20-23）：
 *       {@link #registerLeaderSetToolPermissionContext(String, SetToolPermissionContextFn)} /
 *       {@link #getLeaderSetToolPermissionContext(String)} /
 *       {@link #unregisterLeaderSetToolPermissionContext(String)}。</li>
 * </ul>
 *
 * <p>静态模块（对齐 CC module-level 函数 + {@link TeammateMailbox} 同款风格；无实例状态，
 * 故 {@code final} + 私有构造器，不注册 Spring bean）。
 */
public final class LeaderPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(LeaderPermissionBridge.class);

    private LeaderPermissionBridge() {
    }

    // ── CC 队列 setter 注册（worker 侧 Path A）────────────────────────────

    /**
     * ToolUseConfirm 队列条目 · CC components/permissions/PermissionRequest.ts ToolUseConfirm
     * 的后端投影（worker 推入 leader UI 队列的权限提示）。
     *
     * <p>CC 完整条目含 assistantMessage/tool/toolUseContext/permissionResult 等 UI 层引用，
     * Java 后端仅保留跨层必要字段 + 回调（UI 层渲染时再还原完整 Tool 上下文）。
     *
     * @param toolName                    工具名（CC entry.tool.name）
     * @param toolUseId                   tool use ID（CC entry.toolUseID）
     * @param description                 工具用途描述（CC entry.description）
     * @param input                       序列化 tool input（CC entry.input）
     * @param workerBadgeName             worker badge 名（CC entry.workerBadge.name，可选）
     * @param workerBadgeColor            worker badge 色（CC entry.workerBadge.color，可选）
     * @param permissionPromptStartTimeMs 权限提示开始时间（CC entry.permissionPromptStartTimeMs）
     * @param onAllow                     leader 批准回调（updatedInput, permissionUpdates）
     *                                    （CC entry.onAllow，inProcessRunner.ts:250-254 第二参为
     *                                    PermissionUpdate[]；RF-5 升级为带 permissionUpdates）
     * @param onReject                    leader 拒绝回调（feedback）（CC entry.onReject）
     * @param onAbort                     leader 中止回调（CC entry.onAbort）
     */
    public record ToolUseConfirmEntry(
            String toolName,
            String toolUseId,
            String description,
            Map<String, Object> input,
            String workerBadgeName,
            String workerBadgeColor,
            long permissionPromptStartTimeMs,
            java.util.function.BiConsumer<Map<String, Object>, List<PermissionUpdate>> onAllow,
            java.util.function.Consumer<String> onReject,
            Runnable onAbort) {
    }

    /** CC SetToolUseConfirmQueueFn（:16-18）：接收 prev 队列返回新队列的 setter。 */
    @FunctionalInterface
    public interface SetToolUseConfirmQueueFn {
        void apply(UnaryOperator<List<ToolUseConfirmEntry>> updater);
    }

    // ── 权限上下文 setter 注册 ─────────────────────────────────────────────

    /**
     * leader 权限上下文 setter · 对齐 CC {@code SetToolPermissionContextFn}
     * （leaderPermissionBridge.ts:20-23）{@code (context: ToolPermissionContext, options?: { preserveMode?: boolean }) => void}。
     *
     * <p>context 收紧为 {@link ToolPermissionContext}（CC {@code ToolPermissionContext}，Tool.ts:123-138；
     * ML-5 收紧自原 {@code Object} 透传占位 —— 原「待 REPL→Java 生产接线后收紧」已落地，透传类型
     * 与 CC {@code import type { ToolPermissionContext } from '../../Tool.js'}（:14）一致）。
     */
    @FunctionalInterface
    public interface SetToolPermissionContextFn {
        /**
         * 设置 leader 权限上下文。
         *
         * @param context      CC original: context（ToolPermissionContext，Tool.ts:123-138）
         * @param preserveMode CC original: options.preserveMode（leaderPermissionBridge.ts:22）
         */
        void apply(ToolPermissionContext context, boolean preserveMode);
    }

    // ── 会话分桶 registry 字段 ─────────────────────────────────────────────

    /**
     * [T2 · 会话分桶] leader ToolUseConfirmQueue setter 桶 · 键 = leader 会话 ID
     * （对齐 CC 2.1.278 按 SessionController 分桶的注册表；对齐改前 CC 2.1.88
     * {@code registeredSetter}（:25）的<b>语义</b>但按会话隔离）。
     *
     * <p>并发语义：不同 sessionId 互不覆盖（隔离）；同 sessionId 重复注册 = 覆盖
     * （保留 CC last-writer-wins 的<b>单会话内</b>语义 —— 同一会话的确认表面重挂载即替换）。
     * 桶数量 = 在领导 team 的会话数（注册/注销成对，见 {@link #clearSession(String)}）。
     */
    private static final Map<String, SetToolUseConfirmQueueFn> toolUseConfirmQueueBySession =
            new ConcurrentHashMap<>();

    /** [T2 · 会话分桶] registered ToolPermissionContext setter 桶（对齐改前 CC {@code registeredPermissionContextSetter} :26，按会话隔离）。 */
    private static final Map<String, SetToolPermissionContextFn> permissionContextSetterBySession =
            new ConcurrentHashMap<>();

    /** 会话键校验 · null/blank 一律拒绝（⛔ 无全局兜底桶：那正是本任务要消除的无键全局表面）。 */
    private static boolean invalidSession(String sessionId) {
        return sessionId == null || sessionId.isBlank();
    }

    // ── CC 队列 setter 注册方法（worker 侧 Path A）────────────────────────

    /**
     * 注册 leader ToolUseConfirm 队列 setter（按会话分桶）· 对齐 CC leaderPermissionBridge.ts:28-32
     * registerLeaderToolUseConfirmQueue（**语义**；本仓按 sessionId 分桶，见类 JavaDoc [T2]）。
     *
     * @param sessionId leader 会话 ID（桶键；team config {@code leadSessionId}，与
     *                  {@link #getLeaderToolUseConfirmQueue(String)} 读侧同键）。null/blank → 拒绝注册
     *                  （fail-loud WARN，⛔ 不回落全局槽）
     * @param setter    确认表面 setter（生产 = LeaderPermissionConfirmBridge 的 STOMP 推送）
     */
    public static void registerLeaderToolUseConfirmQueue(String sessionId, SetToolUseConfirmQueueFn setter) {
        if (invalidSession(sessionId)) {
            log.warn("[LeaderPermissionBridge] registerLeaderToolUseConfirmQueue 拒绝：无会话标识"
                    + "（sessionId={}）—— 会话态不得回落全局槽（多会话串台根因）；setter 未注册", sessionId);
            return;
        }
        if (setter == null) {
            log.warn("[LeaderPermissionBridge] registerLeaderToolUseConfirmQueue 拒绝：setter 为 null"
                    + "（session={}）", sessionId);
            return;
        }
        toolUseConfirmQueueBySession.put(sessionId, setter);
        // 数据流日志（CLAUDE.md 编码后必须添加数据流日志 · 中文）：确认表面与会话的绑定关系可见
        if (log.isInfoEnabled()) {
            log.info("[LeaderPermissionBridge] 已注册 leader ToolUseConfirm 队列 setter（会话分桶）: session={}"
                    + "（当前会话桶数={}）", sessionId, toolUseConfirmQueueBySession.size());
        }
    }

    /**
     * 取本会话的 leader ToolUseConfirm 队列 setter · 对齐 CC leaderPermissionBridge.ts:34-36。
     *
     * @param sessionId leader 会话 ID（桶键）
     * @return 本会话注册的 setter；未注册 / sessionId 空 → null（调用方据此走降级，⛔ 不跨会话借用）
     */
    public static SetToolUseConfirmQueueFn getLeaderToolUseConfirmQueue(String sessionId) {
        if (invalidSession(sessionId)) {
            return null;
        }
        return toolUseConfirmQueueBySession.get(sessionId);
    }

    /**
     * 注销本会话的 leader ToolUseConfirm 队列 setter · 对齐 CC leaderPermissionBridge.ts:38-40。
     *
     * <p>只 remove 本会话桶（⛔ 绝不清全局），与
     * {@link #registerLeaderToolUseConfirmQueue(String, SetToolUseConfirmQueueFn)} 成对。
     *
     * @param sessionId leader 会话 ID（桶键）；null/blank → no-op
     * @return true = 本会话确有注册被移除；false = 无此桶（幂等）
     */
    public static boolean unregisterLeaderToolUseConfirmQueue(String sessionId) {
        if (invalidSession(sessionId)) {
            return false;
        }
        boolean removed = toolUseConfirmQueueBySession.remove(sessionId) != null;
        if (log.isInfoEnabled()) {
            log.info("[LeaderPermissionBridge] 已注销 leader ToolUseConfirm 队列 setter: session={} removed={}"
                    + "（当前会话桶数={}）", sessionId, removed, toolUseConfirmQueueBySession.size());
        }
        return removed;
    }

    // ── 权限上下文 setter 注册方法 ─────────────────────────────────────────

    /** 注册 leader 权限上下文 setter（按会话分桶）· 对齐 CC leaderPermissionBridge.ts:42-46（语义，见类 JavaDoc [T2]）。 */
    public static void registerLeaderSetToolPermissionContext(String sessionId, SetToolPermissionContextFn setter) {
        if (invalidSession(sessionId)) {
            log.warn("[LeaderPermissionBridge] registerLeaderSetToolPermissionContext 拒绝：无会话标识"
                    + "（sessionId={}）—— 会话态不得回落全局槽；setter 未注册", sessionId);
            return;
        }
        if (setter == null) {
            log.warn("[LeaderPermissionBridge] registerLeaderSetToolPermissionContext 拒绝：setter 为 null"
                    + "（session={}）", sessionId);
            return;
        }
        permissionContextSetterBySession.put(sessionId, setter);
        if (log.isInfoEnabled()) {
            log.info("[LeaderPermissionBridge] 已注册 leader 权限上下文 setter（会话分桶）: session={}"
                    + "（当前会话桶数={}）", sessionId, permissionContextSetterBySession.size());
        }
    }

    /** 取本会话的 leader 权限上下文 setter · 对齐 CC leaderPermissionBridge.ts:48-50。未注册 / sessionId 空 → null。 */
    public static SetToolPermissionContextFn getLeaderSetToolPermissionContext(String sessionId) {
        if (invalidSession(sessionId)) {
            return null;
        }
        return permissionContextSetterBySession.get(sessionId);
    }

    /** 注销本会话的 leader 权限上下文 setter · 对齐 CC leaderPermissionBridge.ts:52-54。只清本会话桶。 */
    public static boolean unregisterLeaderSetToolPermissionContext(String sessionId) {
        if (invalidSession(sessionId)) {
            return false;
        }
        boolean removed = permissionContextSetterBySession.remove(sessionId) != null;
        if (log.isInfoEnabled()) {
            log.info("[LeaderPermissionBridge] 已注销 leader 权限上下文 setter: session={} removed={}"
                    + "（当前会话桶数={}）", sessionId, removed, permissionContextSetterBySession.size());
        }
        return removed;
    }

    // ── 会话结束清理 ──────────────────────────────────────────────────────

    /**
     * 会话结束清理 · 两个桶的本会话条目一并移除（对齐 CC 2.1.278 会话控制器销毁时注销自身 setter；
     * 本仓会话结束点 = {@code SessionService.delete}，同 {@link TeamHelpers#cleanupSessionTeams(String)}
     * 的会话级清零点）。
     *
     * <p><b>WHY 必须存在</b>：注册与注销成对才不泄漏 —— 会话删除后其 setter 桶若不摘除：
     * ① 桶是无界增长的内存残留；② 复用/回归的 sessionId 会取到<b>上一世会话的确认表面</b>
     * （跨会话串台的第二形态）。只清本会话桶，⛔ 不清全局。
     *
     * @param sessionId 结束的会话 ID；null/blank → no-op
     */
    public static void clearSession(String sessionId) {
        if (invalidSession(sessionId)) {
            return;
        }
        boolean queueRemoved = toolUseConfirmQueueBySession.remove(sessionId) != null;
        boolean ctxRemoved = permissionContextSetterBySession.remove(sessionId) != null;
        if (queueRemoved || ctxRemoved) {
            log.info("[LeaderPermissionBridge] 会话结束清理: session={}（队列 setter removed={}，权限上下文 setter removed={}；"
                    + "剩余桶数 queue={} ctx={}）", sessionId, queueRemoved, ctxRemoved,
                    toolUseConfirmQueueBySession.size(), permissionContextSetterBySession.size());
        } else if (log.isDebugEnabled()) {
            log.debug("[LeaderPermissionBridge] 会话结束清理: session={} 无本会话桶（幂等 no-op）", sessionId);
        }
    }

    /** 测试可观测：当前会话桶数（队列 setter）· 泄漏判据（注册/注销成对的旁证）+ 幻影桶守护判据。 */
    public static int toolUseConfirmQueueSessionBucketCount() {
        return toolUseConfirmQueueBySession.size();
    }
}

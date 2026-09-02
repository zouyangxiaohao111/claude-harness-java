package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Leader Permission Bridge · 对齐 CC {@code utils/swarm/leaderPermissionBridge.ts}（54 行）。
 *
 * <p>模块级 setter/getter registry（对齐 CC module-level let registry :25-26）：允许 REPL /
 * 生产确认表面注册其 ToolUseConfirmQueue setter 与 ToolPermissionContext setter，供非 React 代码
 * （in-process runner 的 worker 权限解析 / leader inbox 分发）复用 —— 这样 in-process teammate
 * 请求权限时走 leader 的标准 ToolUseConfirm 对话框（带 worker badge），而非退化为 mailbox 转发。
 * 对齐 CC 描述「makes the REPL's queue setter and permission context setter accessible from
 * non-React code」（leaderPermissionBridge.ts:7-10）。
 *
 * <p><b>REV-FIX-6 / WF 合并说明</b>：原 {@code submit/resolve} 内存 Map stub 与 CC API
 * （register/get/unregister 三组）完全不符且 CC 无对应能力（grep -rn "submit\|resolve"
 * leaderPermissionBridge.ts → 无）→ 已删除。CC leaderPermissionBridge.ts 有对应模块（:28-54
 * registry），故整类保留、重写为 CC registry API。
 *
 * <p>本类提供 <b>单一</b> CC 队列 setter 注册（ML-3 统一：删除 Java-only
 * {@code LeaderConfirmHandler}/{@code LeaderDecision} 一次性 ask→future 模型，与 CC 单一
 * {@code registeredSetter} 队列推送形态对齐）：
 * <ul>
 *   <li><b>CC 队列 setter 注册</b>（对齐 CC {@code SetToolUseConfirmQueueFn} :16-18）：
 *       {@link #registerLeaderToolUseConfirmQueue(SetToolUseConfirmQueueFn)} /
 *       {@link #getLeaderToolUseConfirmQueue()} / {@link #unregisterLeaderToolUseConfirmQueue()}；
 *       消费方 = {@link SwarmLeaderPermissionDispatcher}（leader 侧 inbox 分发，经 setter 推
 *       {@link ToolUseConfirmEntry} 到 leader UI 队列）+ 测试侧 {@code LeaderPermissionBridgeTest}。</li>
 *   <li><b>权限上下文 setter 注册</b>（对齐 CC {@code SetToolPermissionContextFn} :20-23）：
 *       {@link #registerLeaderSetToolPermissionContext(SetToolPermissionContextFn)} /
 *       {@link #getLeaderSetToolPermissionContext()} / {@link #unregisterLeaderSetToolPermissionContext()}。</li>
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

    // ── 静态 registry 字段 ─────────────────────────────────────────────────

    /** registered ToolUseConfirmQueue setter · 对齐 CC registeredSetter（:25）。 */
    private static volatile SetToolUseConfirmQueueFn registeredSetter;

    /** registered ToolPermissionContext setter · 对齐 CC registeredPermissionContextSetter（:26）。 */
    private static volatile SetToolPermissionContextFn registeredPermissionContextSetter;

    // ── CC 队列 setter 注册方法（worker 侧 Path A）────────────────────────

    /** 注册 leader ToolUseConfirm 队列 setter · 对齐 CC leaderPermissionBridge.ts:28-32。 */
    public static void registerLeaderToolUseConfirmQueue(SetToolUseConfirmQueueFn setter) {
        registeredSetter = setter;
        if (log.isDebugEnabled()) {
            log.debug("[LeaderPermissionBridge] 注册 leader ToolUseConfirm 队列 setter");
        }
    }

    /** 获取 leader ToolUseConfirm 队列 setter · 对齐 CC leaderPermissionBridge.ts:34-36。 */
    public static SetToolUseConfirmQueueFn getLeaderToolUseConfirmQueue() {
        return registeredSetter;
    }

    /** 注销 leader ToolUseConfirm 队列 setter · 对齐 CC leaderPermissionBridge.ts:38-40。 */
    public static void unregisterLeaderToolUseConfirmQueue() {
        registeredSetter = null;
        if (log.isDebugEnabled()) {
            log.debug("[LeaderPermissionBridge] 注销 leader ToolUseConfirm 队列 setter");
        }
    }

    // ── 权限上下文 setter 注册方法 ─────────────────────────────────────────

    /** 注册 leader 权限上下文 setter · 对齐 CC leaderPermissionBridge.ts:42-46。 */
    public static void registerLeaderSetToolPermissionContext(SetToolPermissionContextFn setter) {
        registeredPermissionContextSetter = setter;
        if (log.isDebugEnabled()) {
            log.debug("[LeaderPermissionBridge] 注册 leader 权限上下文 setter");
        }
    }

    /** 获取 leader 权限上下文 setter · 对齐 CC leaderPermissionBridge.ts:48-50。 */
    public static SetToolPermissionContextFn getLeaderSetToolPermissionContext() {
        return registeredPermissionContextSetter;
    }

    /** 注销 leader 权限上下文 setter · 对齐 CC leaderPermissionBridge.ts:52-54。 */
    public static void unregisterLeaderSetToolPermissionContext() {
        registeredPermissionContextSetter = null;
        if (log.isDebugEnabled()) {
            log.debug("[LeaderPermissionBridge] 注销 leader 权限上下文 setter");
        }
    }
}

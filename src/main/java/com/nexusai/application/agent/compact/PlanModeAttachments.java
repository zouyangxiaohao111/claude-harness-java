package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * tool 轮 plan_mode 附件生产器 · 对齐 CC {@code utils/attachments.ts:1186-1273}
 * {@code getPlanModeAttachments} + {@code getPlanModeExitAttachment} + 两个计数辅助函数。
 *
 * <p><b>WHY 存在（RC-2）</b>: CC 在 plan 模式的每个 tool 轮（{@code getAttachmentMessages}
 * 每轮调用）经 {@code getPlanModeAttachments} 注入 plan_mode 附件，让模型在 plan 模式持续拿到
 * {@code planFilePath} + {@code planExists}（即使文件不存在，planFilePath 仍由 getPlanFilePath
 * 生成），模型据此知道 plan 文件应写到哪、何时该用 Write 工具建 plan 文件。Java 端此前
 * EnterPlanModeTool 只置 mode=PLAN + 一次性「只读探索」指引，无任何 planFilePath/plan_mode
 * 附件注入 → 模型拿不到 planFilePath → 永不写 plan 文件 → getPlan 恒 null → plan_file_reference
 * 恒不注入（读侧依赖写侧风险）。本类闭合 tool 轮生产侧。
 *
 * <p><b>节流/周期/reentry/exit 契约（对齐 CC 真源）</b>:
 * <ul>
 *   <li><b>节流</b>：已发过 plan_mode 且距上次 &lt; TURNS_BETWEEN_ATTACHMENTS(5) 轮 → 返回空
 *       （首轮恒注入，CC attachments.ts:1199-1207）。Java 以会话 {@code lastPlanModeAttachmentTurn}
 *       计数器近似 CC 的「人类 turn 反向扫描」（Java 附件不混入 messages 流，见下）。</li>
 *   <li><b>plan_mode_reentry 一次性</b>：{@code hasExitedPlanModeInSession() && existingPlan != null}
 *       → push reentry + clear flag（CC :1216-1219）。</li>
 *   <li><b>full/sparse 每 N 次</b>：{@code attachmentCount % FULL_REMINDER_EVERY_N(5) === 1 → full}
 *       （CC :1222-1231）。</li>
 *   <li><b>plan_mode_exit 一次性</b>：{@code needsPlanModeExitAttachment() && mode !== 'plan'}
 *       → push exit + clear flag；mode==='plan' 时 clear + 返回空（CC :1248-1273）。</li>
 * </ul>
 *
 * <p><b>flag 载体</b>: {@link PlanModeFlags} 为会话级可变 holder（hasExitedPlanModeInSession /
 * needsPlanModeExitAttachment / lastPlanModeAttachmentTurn），由 loop 每轮读写；ExitPlanModeTool /
 * EnterPlanModeTool 经 appState 置 flag（CC STATE.hasExitedPlanMode / needsPlanModeExitAttachment
 * 的 Java 等价）。
 */
public final class PlanModeAttachments {

    private static final Logger log = LoggerFactory.getLogger(PlanModeAttachments.class);

    /** CC PLAN_MODE_ATTACHMENT_CONFIG.TURNS_BETWEEN_ATTACHMENTS（attachments.ts:260）。 */
    public static final int TURNS_BETWEEN_ATTACHMENTS = 5;
    /** CC PLAN_MODE_ATTACHMENT_CONFIG.FULL_REMINDER_EVERY_N_ATTACHMENTS（attachments.ts:261）。 */
    public static final int FULL_REMINDER_EVERY_N_ATTACHMENTS = 5;

    private PlanModeAttachments() { /* 静态工具类 */ }

    /** appState 中 PlanModeFlags 的键 · 对齐 CC STATE.hasExitedPlanMode/needsPlanModeExitAttachment 的会话级存储。 */
    public static final String APP_STATE_FLAGS_KEY = "planModeFlags";

    /**
     * 从 appState 读取或创建会话级 PlanModeFlags（get-or-create）· 供 loop（每轮）与
     * EnterPlanModeTool / ExitPlanModeTool（置 flag）共用，保证三方操作同一会话级对象。
     *
     * @param appState 会话 appState 快照（可空；空 → 返回全新实例，不持久化）
     * @return 已存在 / 新建并回写的 PlanModeFlags
     */
    public static PlanModeFlags getOrCreateFlags(java.util.Map<String, Object> appState) {
        if (appState != null) {
            Object existing = appState.get(APP_STATE_FLAGS_KEY);
            if (existing instanceof PlanModeFlags flags) {
                return flags;
            }
            PlanModeFlags flags = new PlanModeFlags();
            appState.put(APP_STATE_FLAGS_KEY, flags);
            return flags;
        }
        return new PlanModeFlags();
    }

    /**
     * tool 轮 plan_mode 附件 · 对齐 CC {@code getPlanModeAttachments(messages, toolUseContext)}
     * （attachments.ts:1186-1242）。
     *
     * <p>mode !== plan → 空列表。节流（非首轮且距上次 &lt; 5 轮 → 空）。plan_mode_reentry 一次性。
     * 恒 push plan_mode{reminderType full|sparse, isSubAgent, planFilePath, planExists}。
     *
     * @param messages    当前消息列表（CC messages，用于节流近似，仅判空）
     * @param attachments 会话级附件列表（CC transcript 内 AttachmentMessage 的 Java 等价，用于 full/sparse 计数）
     * @param mode        当前 permission mode（CC appState.toolPermissionContext.mode）
     * @param agentId     当前 agent ID（null = 主会话，CC context.agentId）
     * @param provider    plan 数据源（getPlanFilePath / getPlan）
     * @param flags       会话级 plan 模式 flag holder（reentry/exit/节流计数器）
     * @param turnCount   当前 turn 计数（CC 每轮 query 的 turn 近似，用于节流）
     * @return plan_mode_reentry + plan_mode 附件列表；非 plan 模式 / 节流命中 → 空
     */
    public static List<AttachmentMessageDto> getPlanModeAttachments(
            List<ChatMessageDto> messages,
            List<AttachmentMessageDto> attachments,
            PermissionMode mode,
            UUID agentId,
            PlanProvider provider,
            PlanModeFlags flags,
            int turnCount) {
        if (mode != PermissionMode.PLAN) {
            if (log.isDebugEnabled()) {
                log.debug("[PlanModeAttachments] getPlanModeAttachments: 非 plan 模式（mode={}），返回空 · CC attachments.ts:1191-1193",
                    mode);
            }
            return List.of();
        }

        // 节流：已发过 plan_mode 且距上次 < TURNS_BETWEEN_ATTACHMENTS → 空（首轮恒注入）
        // CC attachments.ts:1197-1207 getPlanModeAttachmentTurnCount 反向扫 human turn；
        // Java 附件不混入 messages 流，故以会话 lastPlanModeAttachmentTurn 计数器近似。
        if (messages != null && !messages.isEmpty()
                && flags.lastPlanModeAttachmentTurn() >= 0
                && (turnCount - flags.lastPlanModeAttachmentTurn()) < TURNS_BETWEEN_ATTACHMENTS) {
            if (log.isDebugEnabled()) {
                log.debug("[PlanModeAttachments] getPlanModeAttachments: 节流命中（turn={} last={} < {}）返回空 · CC attachments.ts:1200-1207",
                    turnCount, flags.lastPlanModeAttachmentTurn(), TURNS_BETWEEN_ATTACHMENTS);
            }
            return List.of();
        }

        String planFilePath = provider.getPlanFilePath(agentId);
        boolean existingPlan = provider.getPlan(agentId) != null;

        List<AttachmentMessageDto> out = new ArrayList<>(2);

        // reentry 一次性（CC :1215-1219）：flag 置位且 plan 文件存在 → push + clear
        if (flags.hasExitedPlanModeInSession() && existingPlan) {
            out.add(AttachmentMessageDto.planModeReentry(planFilePath));
            flags.setHasExitedPlanModeInSession(false);
            if (log.isDebugEnabled()) {
                log.debug("[PlanModeAttachments] plan_mode_reentry 一次性注入（path={}）+ clear flag · CC attachments.ts:1216-1219",
                    planFilePath);
            }
        }

        // full/sparse 每 N 次（CC :1222-1231）：countPlanModeAttachmentsSinceLastExit + 1，%5===1 → full
        int attachmentCount = countPlanModeAttachmentsSinceLastExit(attachments) + 1;
        String reminderType = (attachmentCount % FULL_REMINDER_EVERY_N_ATTACHMENTS == 1) ? "full" : "sparse";

        out.add(AttachmentMessageDto.planMode(reminderType, agentId != null, planFilePath, existingPlan));
        flags.setLastPlanModeAttachmentTurn(turnCount);
        if (log.isDebugEnabled()) {
            log.debug("[PlanModeAttachments] plan_mode 注入（reminderType={} isSubAgent={} path={} planExists={} count={}）· CC attachments.ts:1234-1241",
                reminderType, agentId != null, planFilePath, existingPlan, attachmentCount);
        }
        return out;
    }

    /**
     * tool 轮 plan_mode_exit 附件 · 对齐 CC {@code getPlanModeExitAttachment(toolUseContext)}
     * （attachments.ts:1248-1273）。
     *
     * <p>flag 未置位 → 空。mode==='plan' → clear + 空（防 plan_mode 与 plan_mode_exit 同发）。
     * 否则 push 一次性 plan_mode_exit{planFilePath, planExists} + clear。
     *
     * @param mode     当前 permission mode
     * @param agentId  当前 agent ID（null = 主会话）
     * @param provider plan 数据源
     * @param flags    会话级 plan 模式 flag holder
     * @return plan_mode_exit 附件；无退出信号 / 仍在 plan 模式 → 空
     */
    public static List<AttachmentMessageDto> getPlanModeExitAttachment(
            PermissionMode mode,
            UUID agentId,
            PlanProvider provider,
            PlanModeFlags flags) {
        if (!flags.needsPlanModeExitAttachment()) {
            return List.of();
        }
        if (mode == PermissionMode.PLAN) {
            // 仍在 plan 模式 → 清 flag 不注入（CC :1258-1261，防 plan_mode + plan_mode_exit 双发）
            flags.setNeedsPlanModeExitAttachment(false);
            return List.of();
        }
        flags.setNeedsPlanModeExitAttachment(false);
        String planFilePath = provider.getPlanFilePath(agentId);
        boolean planExists = provider.getPlan(agentId) != null;
        if (log.isDebugEnabled()) {
            log.debug("[PlanModeAttachments] plan_mode_exit 一次性注入（path={} planExists={}）+ clear flag · CC attachments.ts:1262-1273",
                planFilePath, planExists);
        }
        return List.of(AttachmentMessageDto.planModeExit(planFilePath, planExists));
    }

    /**
     * 自上次 plan_mode_exit 起的 plan_mode 附件计数 · 对齐 CC
     * {@code countPlanModeAttachmentsSinceLastExit(messages)}（attachments.ts:1169-1184）。
     *
     * <p>反向遍历，遇 plan_mode_exit 停；遇 plan_mode 计 +1。用于 full/sparse 周期重置
     * （重新进入 plan 模式后周期从头开始）。Java 附件在 state.attachments()（不混入 messages），
     * 故扫 attachments 列表。
     *
     * @param attachments 会话级附件列表
     * @return plan_mode 附件数（自上次 exit 或从头）
     */
    static int countPlanModeAttachmentsSinceLastExit(List<AttachmentMessageDto> attachments) {
        int count = 0;
        if (attachments == null) {
            return count;
        }
        for (int i = attachments.size() - 1; i >= 0; i--) {
            AttachmentMessageDto a = attachments.get(i);
            if (a == null) {
                continue;
            }
            if ("plan_mode_exit".equals(a.type())) {
                break; // CC :1177 遇 exit 停
            }
            if ("plan_mode".equals(a.type())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 会话级 plan 模式 flag holder · 对齐 CC {@code bootstrap/state.ts:1333-1361}
     * {@code hasExitedPlanModeInSession} / {@code needsPlanModeExitAttachment}（STATE 全局布尔）。
     *
     * <p>Java 无全局 STATE 单例 → 会话级可变 holder，由 loop 每轮读写（ExitPlanModeTool /
     * EnterPlanModeTool 经 appState 置位）。{@code lastPlanModeAttachmentTurn} 为 Java 节流
     * 近似计数器（CC 由 messages 反向扫 human turn 派生）。
     */
    public static final class PlanModeFlags {
        private boolean hasExitedPlanModeInSession = false;
        private boolean needsPlanModeExitAttachment = false;
        private int lastPlanModeAttachmentTurn = -1;

        public boolean hasExitedPlanModeInSession() { return hasExitedPlanModeInSession; }
        public void setHasExitedPlanModeInSession(boolean v) { this.hasExitedPlanModeInSession = v; }
        public boolean needsPlanModeExitAttachment() { return needsPlanModeExitAttachment; }
        public void setNeedsPlanModeExitAttachment(boolean v) { this.needsPlanModeExitAttachment = v; }
        public int lastPlanModeAttachmentTurn() { return lastPlanModeAttachmentTurn; }
        public void setLastPlanModeAttachmentTurn(int v) { this.lastPlanModeAttachmentTurn = v; }
    }
}

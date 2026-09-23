package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * [Batch2 C1] leader ToolUseConfirm setter 生产注册 · 对齐 CC useInboxPoller.ts:259-350
 * （REPL/React 注册 setter）+ leaderPermissionBridge.ts:16-54 registry。
 *
 * <p><b>WHY 存在</b>：{@link LeaderPermissionBridge#registerLeaderToolUseConfirmQueue} 此前仅测试
 * 注册（生产 0 调用方，grep 复验）→ {@link SwarmLeaderPermissionDispatcher} setter==null →
 * leader inbox 权限请求恒自动 deny（探查 C1 P1 断链）。Web STOMP 权限面是 Java 的
 * 「ToolUseConfirm 表面」等价物（对齐 CC REPL/React 注册 setter 语义 —— 确认表面即前端弹窗）。
 *
 * <p><b>桥接</b>：本类 {@link #registerSetter(String)}（[T2] 起按会话注册）注册 setter；dispatcher
 * 推入的 {@link LeaderPermissionBridge.ToolUseConfirmEntry}（key = toolUseId，dedup 语义经 map 承载）
 * 经 {@link #onConfirmQueueUpdate(String, UnaryOperator)} 入 map + 推 STOMP 到 leader 会话
 * {@code /topic/sessions/{leadSessionId}/permission-requests}（leaderSessionId = 注册时闭合的会话，
 * 与 team config.json {@code leadSessionId} 同源 —— TeamCreateTool 建 team 时落盘并据此注册）。
 * 前端响应经既有
 * {@link com.nexusai.apis.permission.PermissionController#handlePermissionResponse} 回灌
 * {@link #onResponse} → entry 回调（onAllow/onReject/onAbort → sendPermissionResponseViaMailbox +
 * resolvePermission，对齐 CC useInboxPoller.ts:297-331）。
 *
 * <p><b>requestId 空间</b>：主 loop 权限用 ToolUseBlock.id，leader inbox 权限用 worker toolUseId ——
 * 两流无碰撞；本桥 {@link #onResponse} 未命中（非本桥请求）返回 false 交
 * {@link WebSocketPermissionPrompter}。
 *
 * <p><b>setter null 语义保留</b>：{@code SimpMessagingTemplate} 未注入（无 WebSocket 场景）→
 * 不注册 setter（对齐 CC 无 STOMP 表面则丢弃，useInboxPoller.ts:346-350）；
 * {@link SwarmLeaderPermissionDispatcher} 仍走自动 deny（R1 免悬挂降级，Java 增强防 worker 悬挂，
 * 差异注释于 dispatcher）。
 *
 * <p><b>[T2 · 会话分桶]</b> 改前本类的 {@code @PostConstruct registerSetter()} 在<b>进程启动时</b>
 * 往 {@link LeaderPermissionBridge} 的<b>全局单槽</b>注册<b>一个</b>不区分会话的 setter ⇒ 多会话
 * 常驻 JVM 下「A 会话表面 / B 会话表面」共享同一槽（跨会话串台），且 STOMP 目标会话只能靠
 * 进程级 team 名反查（同源问题）。改后：注册/注销<b>按会话</b>
 * （{@link #registerSetter(String)} / {@link #unregisterSetter(String)}），并把这个会话 ID
 * <b>闭合进 setter 回调</b>（{@link #onConfirmQueueUpdate(String, UnaryOperator)}）—— 与 CC 2.1.278
 * 「每个 SessionController 注册自己的 setter」形态同构：setter 知道自己服务哪个会话，
 * 推送目标即注册时的会话，⛔ 不再依赖进程级 team 名反查路由。
 *
 * <p><b>注册方 = leader 会话取得 leader 角色时</b>（{@code TeamCreateTool} 建 team 成功后按
 * {@code leadSessionId} 注册；{@code TeamDeleteTool} 解绑时注销；会话删除兜底
 * {@code SessionService.delete → LeaderPermissionBridge.clearSession}）。
 */
@Component
public class LeaderPermissionConfirmBridge {

    private static final Logger log = LoggerFactory.getLogger(LeaderPermissionConfirmBridge.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** toolUseId → entry 回调 · 对齐 CC setToolUseConfirmQueue 队列的 dedup 语义（按 toolUseId）。 */
    private final ConcurrentHashMap<String, LeaderPermissionBridge.ToolUseConfirmEntry> confirmEntries =
        new ConcurrentHashMap<>();

    /** STOMP 推送模板 · required=false 容错（无 WebSocket 场景 → 不注册 setter，保留 null 语义）。 */
    @Autowired(required = false)
    private SimpMessagingTemplate ws;

    /**
     * 生产注册 setter（按会话）· 对齐 CC REPL/React 注册点（useInboxPoller.ts:259
     * getLeaderToolUseConfirmQueue 消费已注册 setter）。
     *
     * <p>[T2] 由 leader 会话（{@code TeamCreateTool} 建 team 成功）调用；{@code SimpMessagingTemplate}
     * 未注入 → 不注册（无确认表面，CC 丢弃语义）；sessionId 空 → 由
     * {@link LeaderPermissionBridge#registerLeaderToolUseConfirmQueue(String,
     * LeaderPermissionBridge.SetToolUseConfirmQueueFn)} fail-loud 拒绝。
     *
     * @param sessionId leader 会话 ID（桶键 = team config {@code leadSessionId}）
     */
    public void registerSetter(String sessionId) {
        if (ws == null) {
            log.warn("[LeaderPermissionConfirmBridge] SimpMessagingTemplate 未注入，跳过 setter 注册"
                + "（无 WebSocket 表面 → leader inbox 权限请求仍自动 deny）session={}", sessionId);
            return;
        }
        // 会话 ID 闭合进回调：本 setter 只服务注册它的那个会话（对齐 CC 2.1.278 每 SessionController
        // 注册自己的 setter；推送目标 = 注册会话，⛔ 不靠进程级 team 名反查）。
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(sessionId,
            updater -> onConfirmQueueUpdate(sessionId, updater));
        log.info("[LeaderPermissionConfirmBridge] 已注册 leader ToolUseConfirm 队列 setter"
            + "（生产确认表面 = Web STOMP 权限面 · 会话分桶）session={}", sessionId);
    }

    /**
     * 注销 setter（按会话）· 与 {@link #registerSetter(String)} 成对（team 解绑 / 会话结束）。
     *
     * @param sessionId leader 会话 ID（桶键）
     * @return true = 本会话桶确有注册被摘除
     */
    public boolean unregisterSetter(String sessionId) {
        boolean removed = LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(sessionId);
        log.info("[LeaderPermissionConfirmBridge] 已注销 leader ToolUseConfirm 队列 setter session={} removed={}",
            sessionId, removed);
        return removed;
    }

    /**
     * setter 回调 · 对齐 CC useInboxPoller.ts:340-345
     * {@code setToolUseConfirmQueue(queue => dedup ? queue : [...queue, entry])}。
     *
     * <p>updater 的 dedup lambda 已按 toolUseId 判重（dispatcher 侧构造）；本方法经
     * {@code confirmEntries.values()} 重建 prev 队列喂 updater，返回列表中的新 entry（toolUseId
     * 不在 map）→ 入 map + 推 STOMP。推送失败 → log.warn + 从 map 移除（不阻塞 dispatcher，
     * CC delivery 失败丢弃语义）。
     *
     * @param leaderSessionId [T2] 注册此 setter 的 leader 会话（STOMP 推送目标会话，见
     *                        {@link #registerSetter(String)}）
     * @param updater         队列更新函数（dedup by toolUseId）
     */
    void onConfirmQueueUpdate(String leaderSessionId,
                              UnaryOperator<List<LeaderPermissionBridge.ToolUseConfirmEntry>> updater) {
        if (updater == null) {
            return;
        }
        try {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> prev =
                new ArrayList<>(confirmEntries.values());
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = updater.apply(prev);
            for (LeaderPermissionBridge.ToolUseConfirmEntry entry : next) {
                if (entry != null && !confirmEntries.containsKey(entry.toolUseId())) {
                    confirmEntries.put(entry.toolUseId(), entry);
                    pushToStomp(leaderSessionId, entry);
                }
            }
        } catch (Exception e) {
            log.warn("[LeaderPermissionConfirmBridge] onConfirmQueueUpdate 处理失败: {}", e.toString());
        }
    }

    /**
     * 推送权限请求到 leader 会话 STOMP topic · 对齐 WebSocketPermissionPrompter.prompt 的
     * push 形状（{@code /topic/sessions/{sessionId}/permission-requests}）。
     *
     * <p>entry 字段映射（对齐 CC useInboxPoller.ts:278-336 ToolUseConfirm entry → 弹窗）：
     * workerBadgeColor → 事件 {@code workerBadgeColor} 字段（前端渲染彩色徽标）；workerBadgeName
     * 仍经 description 透传（既有 workaround 保留，本次范围只加 color，前端 #134 用 description
     * 取名字 + workerBadgeColor 渲染徽标）；requestId = entry.toolUseId()（前端响应据此回灌本桥）。
     * reason = Other("leader_inbox")。
     *
     * @param leaderSessionId [T2] 本 setter 注册时的 leader 会话（推送目标；改前经进程级 team 名反查
     *                        config {@code leadSessionId} —— 多会话下反查源是全局的，会推错会话）
     * @param entry           待推送的确认条目
     */
    private void pushToStomp(String leaderSessionId, LeaderPermissionBridge.ToolUseConfirmEntry entry) {
        if (leaderSessionId == null || leaderSessionId.isBlank()) {
            confirmEntries.remove(entry.toolUseId());
            log.warn("[LeaderPermissionConfirmBridge] leader 会话为空，丢弃权限请求 tool={} toolUseId={}",
                entry.toolName(), entry.toolUseId());
            return;
        }
        try {
            JsonNode toolInput = entry.input() != null
                ? JSON.valueToTree(entry.input()) : JSON.createObjectNode();
            MessagePermissionRequestEvent event = MessagePermissionRequestEvent.of(
                leaderSessionId,
                entry.toolUseId(),        // requestId（前端响应关联 = worker toolUseId）
                entry.toolUseId(),        // toolUseId
                entry.toolName(),
                toolInput,
                new PermissionDecisionReason.Other("leader_inbox"),
                entry.description(),
                List.of(),                // suggestions（leader inbox 无授权建议）
                null,                     // blockedPath
                null,                     // warning
                entry.workerBadgeColor(), // workerBadgeColor（前端渲染彩色徽标，WorkerBadge.tsx:8）
                false);                   // classifierFeatureEnabled
            ws.convertAndSend("/topic/sessions/" + leaderSessionId + "/permission-requests", event);
            log.info("[LeaderPermissionConfirmBridge] 已推送权限请求到 leader 会话 {}: tool={} worker={} color={}",
                leaderSessionId, entry.toolName(), entry.workerBadgeName(), entry.workerBadgeColor());
        } catch (Exception e) {
            confirmEntries.remove(entry.toolUseId());
            log.warn("[LeaderPermissionConfirmBridge] STOMP 推送失败，移除 entry tool={}: {}",
                entry.toolName(), e.toString());
        }
    }

    /**
     * 前端权限响应分流 · 命中本桥 entry → 消费并触发回调；未命中 → 返回 false
     * （交 {@link WebSocketPermissionPrompter}）。由
     * {@link com.nexusai.apis.permission.PermissionController#handlePermissionResponse}
     * 在 prompter.onResponse 之前调用。
     *
     * <p>决策映射（对齐 CC useInboxPoller.ts:297-331）：allow → onAllow(updatedInput,
     * permissionUpdates)；deny → onReject(feedback)；abort/cancel → onAbort()。
     * updatedInput = entry.input()（leader inbox 流程前端无 input 编辑，透传原始 input）。
     *
     * @return true 本桥已消费（不落 prompter 的 pending map）；false 非本桥请求
     */
    public boolean onResponse(String requestId, String decision, List<JsonNode> updatedPermissions,
                              String acceptFeedback, List<JsonNode> contentBlocks,
                              JsonNode answers, JsonNode annotations) {
        LeaderPermissionBridge.ToolUseConfirmEntry entry = confirmEntries.remove(requestId);
        if (entry == null) {
            return false;
        }
        boolean isAllow = "allow".equalsIgnoreCase(decision);
        boolean isAbort = "abort".equalsIgnoreCase(decision)
            || "cancel".equalsIgnoreCase(decision) || "interrupt".equalsIgnoreCase(decision);
        try {
            if (isAllow) {
                List<PermissionUpdate> updates =
                    WebSocketPermissionPrompter.parseUpdatedPermissions(updatedPermissions);
                entry.onAllow().accept(entry.input(), updates);
            } else if (isAbort) {
                entry.onAbort().run();
            } else {
                String feedback = (acceptFeedback != null && !acceptFeedback.isBlank())
                    ? acceptFeedback : null;
                entry.onReject().accept(feedback);
            }
            log.info("[LeaderPermissionConfirmBridge] 已响应权限请求 toolUseId={} decision={} worker={}",
                requestId, isAllow ? "allow" : (isAbort ? "abort" : "deny"), entry.workerBadgeName());
        } catch (Exception e) {
            log.warn("[LeaderPermissionConfirmBridge] 权限响应回调执行失败 toolUseId={}: {}",
                requestId, e.toString());
        }
        return true;
    }

    /**
     * [T2 删除] 原 {@code resolveLeaderSessionId()} 已移除：改前它经<b>进程级</b>
     * {@link TaskSystemConfig#getTeamName()} 反查 team config {@code leadSessionId} 决定 STOMP 目标会话
     * —— 多会话常驻 JVM 下该反查源本身就是全局单值（同一根因的另一形态）。改后目标会话由
     * {@link #registerSetter(String)} <b>在注册时闭合进 setter</b>，无需反查。
     */

    /** 测试/接线用 setter（ws · STOMP 推送模板；测试直构无 Spring 上下文时注入 mock）。 */
    public void setWs(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** 测试可观测：当前 pending 的 entry 数。 */
    int pendingCount() {
        return confirmEntries.size();
    }
}

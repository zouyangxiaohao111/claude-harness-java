package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import jakarta.annotation.PostConstruct;
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
 * <p><b>桥接</b>：本类 {@link #registerSetter()} @PostConstruct 注册 setter；dispatcher 推入的
 * {@link LeaderPermissionBridge.ToolUseConfirmEntry}（key = toolUseId，dedup 语义经 map 承载）
 * 经 {@link #onConfirmQueueUpdate} 入 map + 推 STOMP 到 leader 会话
 * {@code /topic/sessions/{leadSessionId}/permission-requests}（leaderSessionId 经 team config.json
 * {@code leadSessionId} 路由，TeamCreateTool.buildConfigJson 已落盘）。前端响应经既有
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

    /** team 配置文件读取（leadSessionId 解析）· required=false 容错（无 bean → 无法路由，丢弃）。 */
    @Autowired(required = false)
    private TeamHelpers teamHelpers;

    /**
     * 生产注册 setter · 对齐 CC REPL/React 注册点（useInboxPoller.ts:259 getLeaderToolUseConfirmQueue
     * 消费已注册 setter）。{@code SimpMessagingTemplate} 未注入 → 不注册（无确认表面，CC 丢弃语义）。
     */
    @PostConstruct
    void registerSetter() {
        if (ws == null) {
            log.warn("[LeaderPermissionConfirmBridge] SimpMessagingTemplate 未注入，跳过 setter 注册"
                + "（无 WebSocket 表面 → leader inbox 权限请求仍自动 deny）");
            return;
        }
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(this::onConfirmQueueUpdate);
        log.info("[LeaderPermissionConfirmBridge] 已注册 leader ToolUseConfirm 队列 setter"
            + "（生产确认表面 = Web STOMP 权限面）");
    }

    /**
     * setter 回调 · 对齐 CC useInboxPoller.ts:340-345
     * {@code setToolUseConfirmQueue(queue => dedup ? queue : [...queue, entry])}。
     *
     * <p>updater 的 dedup lambda 已按 toolUseId 判重（dispatcher 侧构造）；本方法经
     * {@code confirmEntries.values()} 重建 prev 队列喂 updater，返回列表中的新 entry（toolUseId
     * 不在 map）→ 入 map + 推 STOMP。推送失败 → log.warn + 从 map 移除（不阻塞 dispatcher，
     * CC delivery 失败丢弃语义）。
     */
    void onConfirmQueueUpdate(UnaryOperator<List<LeaderPermissionBridge.ToolUseConfirmEntry>> updater) {
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
                    pushToStomp(entry);
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
     */
    private void pushToStomp(LeaderPermissionBridge.ToolUseConfirmEntry entry) {
        String leaderSessionId = resolveLeaderSessionId();
        if (leaderSessionId == null) {
            confirmEntries.remove(entry.toolUseId());
            log.warn("[LeaderPermissionConfirmBridge] 无法解析 leader 会话（leadSessionId 缺失），"
                + "丢弃权限请求 tool={} toolUseId={}", entry.toolName(), entry.toolUseId());
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
     * 解析 leader 会话 ID · 读 team config.json {@code leadSessionId}
     * （TeamCreateTool.buildConfigJson 已落盘，TeamCreateTool.java:231）。找不到 / 解析失败 → null
     * （调用方丢弃该请求，fail loud log.warn 不静默）。
     *
     * <p>team 名取 {@link TaskSystemConfig#getTeamName()}（与 {@link SwarmLeaderPermissionDispatcher}
     * dispatchOnce 同源 —— 当前 in-process swarm 单 team/进程限制，会话级化归 Batch4）。
     */
    private String resolveLeaderSessionId() {
        String teamName = TaskSystemConfig.getTeamName();
        if (teamName == null || teamName.isBlank()) {
            return null;
        }
        if (teamHelpers == null) {
            return null;
        }
        String config = teamHelpers.readConfig(teamName);
        if (config == null) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(config);
            String sessionId = root.path("leadSessionId").asText(null);
            return (sessionId == null || sessionId.isBlank()) ? null : sessionId;
        } catch (Exception e) {
            log.warn("[LeaderPermissionConfirmBridge] 解析 leadSessionId 失败 team={}: {}",
                teamName, e.getMessage());
            return null;
        }
    }

    /** 测试/接线用 setter（ws · STOMP 推送模板；测试直构无 Spring 上下文时注入 mock）。 */
    public void setWs(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** 测试/接线用 setter（teamHelpers · leadSessionId 解析）。 */
    public void setTeamHelpers(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }

    /** 测试可观测：当前 pending 的 entry 数。 */
    int pendingCount() {
        return confirmEntries.size();
    }
}

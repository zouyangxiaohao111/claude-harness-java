package com.nexusai.application.agent.team;

import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Swarm Leader 权限分发器 · 对齐 CC {@code Open-ClaudeCode/src/hooks/useInboxPoller.ts:250-364}
 * （leader 侧 inbox 分发）+ {@code utils/swarm/leaderPermissionBridge.ts} registry。
 *
 * <p><b>REV-FIX-6（WF-3 缝隙4）</b>：补 leader 侧 inbox 分发，使 swarm 权限请求-响应端到端闭环。
 * 此前的唯一消费端（worker mailbox 发送）无任何 leader 侧对端 —— {@code sendPermissionResponseViaMailbox}
 * / {@code resolvePermission} 均 0 调用方，worker 请求发出后悬挂。本类作为 leader 对端：
 * <ol>
 *   <li>守卫 {@code isTeamLeader}（对齐 CC useInboxPoller :253 isTeamLead 才处理 permission_request）</li>
 *   <li>读 leader 自身邮箱（{@link SwarmPermissionSync#getLeaderName}，对齐 CC getAgentNameToPoll
 *       :81-105 的 leader 分支 leadName || 'team-lead'）</li>
 *   <li>permission_request → {@link LeaderPermissionBridge#getLeaderToolUseConfirmQueue()} 取
 *       队列 setter（对齐 CC :259 getLeaderToolUseConfirmQueue）：
 *       <ul>
 *         <li>无 setter → log + 自动 deny（<b>R1 降级</b>：mailbox 请求无 STOMP 会话；不悬挂 worker，
 *             对齐 CC :346-350 的 ToolUseConfirmQueue unavailable 丢弃语义 + 免悬挂增强）</li>
 *         <li>有 setter → 构建 {@link LeaderPermissionBridge.ToolUseConfirmEntry}（onAllow/onReject/onAbort
 *             三回调 wire {@code resolvePermission} + {@code sendPermissionResponseViaMailbox}，
 *             对齐 CC :297-331）→ setter 队列推送（dedup by toolUseId，对齐 CC :340-345）</li>
 *       </ul></li>
 *   <li>sandbox_permission_request → 同款 → {@code sendSandboxPermissionResponseViaMailbox}
 *       （对齐 CC :399-463）</li>
 *   <li>处理完 {@link TeammateMailbox#markMessageAsReadByIndex}（对齐 CC inProcessRunner.ts:403）</li>
 * </ol>
 *
 * <p>轮询间隔 1000ms（对齐 CC INBOX_POLL_INTERVAL_MS = 1000，useInboxPoller.ts:107）。
 * 单例 {@code @Component}（{@code @Scheduled} 挂载定时任务）；核心逻辑在 {@link #dispatchOnce()}
 * 供测试直接驱动（规则九：回环闭环测试不依赖 Spring 调度）。
 */
@Component
public class SwarmLeaderPermissionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SwarmLeaderPermissionDispatcher.class);

    /** leader inbox 轮询间隔 · 对齐 CC INBOX_POLL_INTERVAL_MS = 1000（useInboxPoller.ts:107）。 */
    public static final long POLL_INTERVAL_MS = 1000;

    /** 分发进行中标志 · 对齐 CC isProcessingRef 防并发轮询（useSwarmPermissionPoller.ts:269）。 */
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * 单次 leader inbox 分发 · 可测入口（Spring {@code @Scheduled} 的 {@link #poll()} 委托于此）。
     *
     * @return 本次处理的消息条数（permission_request + sandbox_permission_request）
     */
    public int dispatchOnce() {
        String teamName = TaskSystemConfig.getTeamName();
        if (teamName == null || teamName.isBlank()) {
            return 0;
        }
        // 守卫：仅 leader 处理 permission_request（对齐 CC useInboxPoller:253 isTeamLead）
        if (!SwarmPermissionSync.isTeamLeader(teamName)) {
            return 0;
        }
        if (!processing.compareAndSet(false, true)) {
            return 0;
        }
        try {
            String leaderName = SwarmPermissionSync.getLeaderName(teamName);
            if (leaderName == null || leaderName.isBlank()) {
                return 0;
            }
            List<TeammateMailbox.TeammateMessage> all = TeammateMailbox.readMailbox(leaderName, teamName);
            int handled = 0;
            for (int i = 0; i < all.size(); i++) {
                TeammateMailbox.TeammateMessage msg = all.get(i);
                if (msg == null || msg.read()) {
                    continue;
                }
                TeammateMailbox.PermissionRequestMessage permReq =
                        TeammateMailbox.isPermissionRequest(msg.text());
                if (permReq != null) {
                    // 对齐 CC :403 markMessageAsReadByIndex（处理即已读，防重复分发）
                    TeammateMailbox.markMessageAsReadByIndex(leaderName, teamName, i);
                    handlePermissionRequest(permReq, teamName);
                    handled++;
                    continue;
                }
                TeammateMailbox.SandboxPermissionRequestMessage sandboxReq =
                        TeammateMailbox.isSandboxPermissionRequest(msg.text());
                if (sandboxReq != null) {
                    TeammateMailbox.markMessageAsReadByIndex(leaderName, teamName, i);
                    handleSandboxRequest(sandboxReq, teamName);
                    handled++;
                }
            }
            if (handled > 0 && log.isDebugEnabled()) {
                log.debug("[SwarmLeader] 本次分发 {} 条权限/sandbox 请求", handled);
            }
            return handled;
        } finally {
            processing.set(false);
        }
    }

    /** 500ms/1000ms 定时轮询 · 对齐 CC useInterval(INBOX_POLL_INTERVAL_MS)（useInboxPoller.ts:954）。 */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void poll() {
        if (log.isDebugEnabled()) {
            log.debug("[SwarmLeader] 定时轮询 leader 邮箱权限请求开始");
        }
        dispatchOnce();
    }

    /**
     * 处理一条 mailbox 权限请求 · 对齐 CC useInboxPoller.ts:262-351（ToolUseConfirmQueue 路由 +
     * onAllow/onReject/onAbort → sendPermissionResponseViaMailbox + setter 队列推送 dedup by toolUseId）。
     */
    private void handlePermissionRequest(TeammateMailbox.PermissionRequestMessage request, String teamName) {
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setter =
                LeaderPermissionBridge.getLeaderToolUseConfirmQueue();
        if (setter == null) {
            // R1 降级：mailbox 请求无 STOMP 会话 → 无确认表面时 log + 自动 deny，不悬挂 worker
            // （对齐 CC :346-350 ToolUseConfirmQueue unavailable 丢弃语义 + 免悬挂增强）
            if (log.isDebugEnabled()) {
                log.debug("[SwarmLeader] 无 leader ToolUseConfirm 队列，自动 deny 请求 id={} tool={} worker={}",
                        request.requestId(), request.toolName(), request.agentId());
            }
            resolveAndRespond(request.requestId(), request.agentId(), teamName,
                    "rejected", null, null, "No leader confirm surface available");
            return;
        }
        // 构建 ToolUseConfirm 队列条目（对齐 CC useInboxPoller.ts:278-336），onAllow/onReject/onAbort
        // 三回调 wire resolvePermission + sendPermissionResponseViaMailbox（CC :297-331）
        LeaderPermissionBridge.ToolUseConfirmEntry entry = new LeaderPermissionBridge.ToolUseConfirmEntry(
                request.toolName(), request.toolUseId(), request.description(), request.input(),
                request.agentId(), "cyan", System.currentTimeMillis(),
                // onAllow（CC :305-320）：leader 批准 → approved + updatedInput + permissionUpdates
                (updatedInput, permissionUpdates) -> resolveAndRespond(request.requestId(), request.agentId(),
                        teamName, "approved", updatedInput, toObjectList(permissionUpdates), null),
                // onReject（CC :321-332）：leader 拒绝 → rejected + feedback
                feedback -> resolveAndRespond(request.requestId(), request.agentId(),
                        teamName, "rejected", null, null, feedback),
                // onAbort（CC :297-304）：leader 中止 → rejected
                () -> resolveAndRespond(request.requestId(), request.agentId(),
                        teamName, "rejected", null, null, null));
        // setter 队列推送：dedup by toolUseId（CC :340-345）
        setter.apply(queue -> {
            if (queue.stream().anyMatch(q -> Objects.equals(q.toolUseId(), request.toolUseId()))) {
                return queue;
            }
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });
        if (log.isDebugEnabled()) {
            log.debug("[SwarmLeader] 已推送权限请求 id={} tool={} 到 leader ToolUseConfirm 队列",
                    request.requestId(), request.toolName());
        }
    }

    /**
     * 处理一条 mailbox sandbox 权限请求 · 对齐 CC useInboxPoller.ts:399-463（workerSandboxPermissions
     * 队列 → 决策 → sendSandboxPermissionResponseViaMailbox）。Java 无 workerSandboxPermissions UI
     * 队列，经同一 {@link LeaderPermissionBridge} ToolUseConfirm 队列裁决（R3：sandbox 分发依赖 sandbox
     * 运行时存在，低风险不阻塞主回环；ML-3 统一队列推送形态，toolName="network"）。
     */
    private void handleSandboxRequest(TeammateMailbox.SandboxPermissionRequestMessage request, String teamName) {
        String host = request.hostPattern().host();
        LeaderPermissionBridge.SetToolUseConfirmQueueFn setter =
                LeaderPermissionBridge.getLeaderToolUseConfirmQueue();
        if (setter == null) {
            // R1 降级：无确认表面 → 自动 deny（不悬挂 sandbox 等待方）
            if (log.isDebugEnabled()) {
                log.debug("[SwarmLeader] 无 leader ToolUseConfirm 队列，自动 deny sandbox 请求 id={} host={} worker={}",
                        request.requestId(), host, request.workerName());
            }
            SwarmPermissionSync.sendSandboxPermissionResponseViaMailbox(
                    request.workerName(), request.requestId(), host, false, teamName);
            return;
        }
        // sandbox 请求转通用 ToolUseConfirm 队列条目（host 承载于 input，供确认表面展示「网络访问」）
        LeaderPermissionBridge.ToolUseConfirmEntry entry = new LeaderPermissionBridge.ToolUseConfirmEntry(
                "network", request.requestId(), "network access to " + host,
                Map.of("host", host), request.workerName(), "cyan", System.currentTimeMillis(),
                // onAllow → 放行 sandbox（CC sandbox allow）
                (updatedInput, permissionUpdates) -> SwarmPermissionSync.sendSandboxPermissionResponseViaMailbox(
                        request.workerName(), request.requestId(), host, true, teamName),
                // onReject → 拒绝 sandbox
                feedback -> SwarmPermissionSync.sendSandboxPermissionResponseViaMailbox(
                        request.workerName(), request.requestId(), host, false, teamName),
                // onAbort → 拒绝 sandbox
                () -> SwarmPermissionSync.sendSandboxPermissionResponseViaMailbox(
                        request.workerName(), request.requestId(), host, false, teamName));
        setter.apply(queue -> {
            if (queue.stream().anyMatch(q -> Objects.equals(q.toolUseId(), request.requestId()))) {
                return queue;
            }
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });
        if (log.isDebugEnabled()) {
            log.debug("[SwarmLeader] 已推送 sandbox 请求 id={} host={} 到 leader ToolUseConfirm 队列",
                    request.requestId(), host);
        }
    }

    /**
     * 写 resolved/ + 发 mailbox 响应 · 双通道闭环（对齐 CC onAllow/onReject/onAbort 全为
     * sendPermissionResponseViaMailbox，:298/309/322；Java 额外喂磁盘 poller，R2 见类 JavaDoc）。
     *
     * @param decision           CC original: decision（'approved' | 'rejected'，permissionSync.ts:97）
     * @param updatedInput       CC original: updatedInput（useInboxPoller.ts:310）— allow 时修改后 input
     * @param permissionUpdates  CC original: permissionUpdates（useInboxPoller.ts:311）— allow 时权限更新
     * @param feedback           CC original: feedback（useInboxPoller.ts:323）— reject 附言
     */
    private void resolveAndRespond(String requestId, String workerAgentId, String teamName,
                                   String decision, Map<String, Object> updatedInput,
                                   List<Object> permissionUpdates, String feedback) {
        SwarmPermissionSync.PermissionResolution resolution =
                new SwarmPermissionSync.PermissionResolution(decision, "leader",
                        feedback, updatedInput, permissionUpdates);
        // 1. resolved/ 目录（喂 Java 既有磁盘 poller；CC permissionSync.ts:356 'Called by the team leader'）
        SwarmPermissionSync.resolvePermission(requestId, resolution, teamName);
        // 2. mailbox 响应回传 worker（CC 主通道）
        SwarmPermissionSync.sendPermissionResponseViaMailbox(workerAgentId, resolution, requestId, teamName);
        if (log.isDebugEnabled()) {
            log.debug("[SwarmLeader] 已响应请求 id={} decision={} → worker {}", requestId, decision, workerAgentId);
        }
    }

    /**
     * 强类型 {@code List<PermissionUpdate>} → 弱类型 {@code List<Object>} 桥接（CC onAllow 第二参为
     * {@code PermissionUpdate[]}，经 mailbox JSON 序列化后以弱类型 List 承载）。
     */
    private static List<Object> toObjectList(List<PermissionUpdate> updates) {
        if (updates == null) {
            return null;
        }
        return new ArrayList<>(updates);
    }
}

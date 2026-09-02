package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.team.SwarmPermissionSync;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Swarm 权限轮询器 · 对齐 CC {@code Open-ClaudeCode/src/hooks/useSwarmPermissionPoller.ts}。
 *
 * <p>CC 为 React hook（UI 层 {@code useInterval} 500ms 轮询），Java 无 UI，映射为后台定时任务
 * （{@code @Scheduled(fixedDelay=500)}，{@link #poll()}）。registry 为模块级静态 Map（对齐 CC
 * :76 {@code const pendingCallbacks: Map} 模块级持久），跨 handler 注册与 poller 轮询共享。
 *
 * <p>核心语义（grep 自验 CC，不信注释）：
 * <ol>
 *   <li>{@code registerPermissionCallback}（:82-89）— 请求注册 callback，先于 mailbox 发送（防 race）</li>
 *   <li>{@code processMailboxPermissionResponse}（:124-156）— approved/rejected 分支<b>先 delete 再 invoke</b>
 *       （delete-before-call，重复事件二次返回 false）</li>
 *   <li>{@code processSandboxPermissionResponse}（:201-226）— sandbox 响应同款先删后调</li>
 *   <li>{@code poll}（:268-330）— isSwarmWorker 守卫 + isProcessing 防并发 + pendingCallbacks 空跳过</li>
 * </ol>
 *
 * <p>静态 registry + 静态方法（对齐 CC 模块函数），{@code @Component} 仅为挂载 {@code @Scheduled}
 * 定时任务；轮询触发接线点见 {@link #poll()} JavaDoc。
 */
@Component
public class SwarmPermissionPoller {

    private static final Logger log = LoggerFactory.getLogger(SwarmPermissionPoller.class);

    /** 轮询间隔 500ms · 对齐 CC POLL_INTERVAL_MS = 500（useSwarmPermissionPoller.ts:28）。 */
    public static final long POLL_INTERVAL_MS = 500;

    /** 畸形条目校验用 JSON mapper（Java wire 形状回退解析）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * pending 权限回调 registry · 对齐 CC {@code pendingCallbacks}（useSwarmPermissionPoller.ts:76）。
     */
    private static final Map<String, PermissionResponseCallback> PENDING_CALLBACKS =
            new ConcurrentHashMap<>();

    /**
     * pending sandbox 回调 registry · 对齐 CC {@code pendingSandboxCallbacks}（:172-173）。
     */
    private static final Map<String, SandboxPermissionResponseCallback> PENDING_SANDBOX_CALLBACKS =
            new ConcurrentHashMap<>();

    /** 轮询进行中标志 · 对齐 CC isProcessingRef（:269），防并发轮询。 */
    private static final AtomicBoolean IS_PROCESSING = new AtomicBoolean(false);

    /** mailbox 响应分发进行中标志 · 防并发轮询（对齐 CC isProcessingRef 同款模式）。 */
    private static final AtomicBoolean MAILBOX_PROCESSING = new AtomicBoolean(false);

    /**
     * 权限响应回调 · 对齐 CC PermissionResponseCallback（:58-67）。
     *
     * <p>[REV-FIX-6 gap3] {@code onAllow} 透传 permissionUpdates（CC :61-65 onAllow 签名含
     * permissionUpdates 参数，swarmWorkerHandler.ts:84-108）。
     *
     * @param requestId CC original: requestId (:59)
     * @param toolUseId CC original: toolUseId (:60)
     * @param onAllow   CC original: onAllow (:61-65) — approved 时触发（携带 AllowResult：updatedInput + permissionUpdates）
     * @param onReject  CC original: onReject (:66) — rejected 时触发（携带 feedback）
     */
    public record PermissionResponseCallback(String requestId, String toolUseId,
                                             Consumer<AllowResult> onAllow,
                                             Consumer<String> onReject) {
    }

    /**
     * allow 载荷 · 对齐 CC onAllow 的 {@code (allowedInput, permissionUpdates)} 二元组
     * （useSwarmPermissionPoller.ts:61-65 / swarmWorkerHandler.ts:84-88）——permissionUpdates 承载
     * 「Always allow」规则，worker 侧需透传才能让决策下游 apply。
     *
     * @param updatedInput      CC original: allowedInput / updated_input（useInboxPoller.ts:385）
     * @param permissionUpdates CC original: permissionUpdates / permission_updates（useInboxPoller.ts:386）
     */
    public record AllowResult(Map<String, Object> updatedInput, List<Object> permissionUpdates) {
    }

    /**
     * sandbox 响应回调 · 对齐 CC SandboxPermissionResponseCallback（:165-169）。
     */
    public record SandboxPermissionResponseCallback(String requestId, String host,
                                                    Consumer<Boolean> resolve) {
    }

    // ════════════════════════════════════════════════════════════════════════
    // registry · 对齐 CC useSwarmPermissionPoller.ts:82-116
    // ════════════════════════════════════════════════════════════════════════

    /** 注册权限回调 · 对齐 CC registerPermissionCallback（:82-89）。 */
    public static void registerPermissionCallback(String requestId, String toolUseId,
                                                  Consumer<AllowResult> onAllow,
                                                  Consumer<String> onReject) {
        PENDING_CALLBACKS.put(requestId, new PermissionResponseCallback(requestId, toolUseId, onAllow, onReject));
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 已注册回调 request={}", requestId);
        }
    }

    /** 注销权限回调 · 对齐 CC unregisterPermissionCallback（:94-99）。 */
    public static void unregisterPermissionCallback(String requestId) {
        PENDING_CALLBACKS.remove(requestId);
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 已注销回调 request={}", requestId);
        }
    }

    /** 是否已注册 · 对齐 CC hasPermissionCallback（:104-106）。 */
    public static boolean hasPermissionCallback(String requestId) {
        return PENDING_CALLBACKS.containsKey(requestId);
    }

    /** 注册 sandbox 回调 · 对齐 CC registerSandboxPermissionCallback（:179-186）。 */
    public static void registerSandboxPermissionCallback(String requestId, String host,
                                                         Consumer<Boolean> resolve) {
        PENDING_SANDBOX_CALLBACKS.put(requestId, new SandboxPermissionResponseCallback(requestId, host, resolve));
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 已注册 sandbox 回调 request={}", requestId);
        }
    }

    /** 是否已注册 sandbox · 对齐 CC hasSandboxPermissionCallback（:191-193）。 */
    public static boolean hasSandboxPermissionCallback(String requestId) {
        return PENDING_SANDBOX_CALLBACKS.containsKey(requestId);
    }

    /** 清空全部回调（permission + sandbox）· 对齐 CC clearAllPendingCallbacks（:113-116）。 */
    public static void clearAllPendingCallbacks() {
        PENDING_CALLBACKS.clear();
        PENDING_SANDBOX_CALLBACKS.clear();
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 已清空全部 pending 回调");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 响应处理 · 对齐 CC useSwarmPermissionPoller.ts:124-226
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 处理 mailbox 权限响应 · 对齐 CC processMailboxPermissionResponse（:124-156）：
     * 未注册 → false；命中 → <b>先 delete 再 invoke</b>，approved/rejected 分流。
     *
     * @return true = 命中并处理；false = 无回调
     */
    public static boolean processMailboxPermissionResponse(String requestId, String decision, String feedback,
                                                           Map<String, Object> updatedInput,
                                                           java.util.List<Object> permissionUpdates) {
        PermissionResponseCallback callback = PENDING_CALLBACKS.get(requestId);
        if (callback == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionPoller] 无回调可处理 mailbox 响应 request={}", requestId);
            }
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 处理 mailbox 响应 request={} decision={}", requestId, decision);
        }
        // delete-before-call（CC :145）— 重复事件二次返回 false
        PENDING_CALLBACKS.remove(requestId);
        if ("approved".equals(decision)) {
            // [REV-FIX-6 gap3] 透传 permissionUpdates（对齐 CC onAllow(allowedInput, permissionUpdates)）
            // [DEL-WF7-02-01/R3] safeParse 过滤畸形条目（CC useSwarmPermissionPoller.ts:147-150
            //   parsePermissionUpdates :35-53）——buggy/旧版 teammate 的畸形条目不透传下游。
            callback.onAllow().accept(new AllowResult(updatedInput, filterPermissionUpdates(permissionUpdates)));
        } else {
            callback.onReject().accept(feedback);
        }
        return true;
    }

    /**
     * 处理 sandbox 权限响应 · 对齐 CC processSandboxPermissionResponse（:201-226）：
     * 先 delete 再 invoke resolve(allow)。
     *
     * @return true = 命中并处理；false = 无回调
     */
    public static boolean processSandboxPermissionResponse(String requestId, String host, boolean allow) {
        SandboxPermissionResponseCallback callback = PENDING_SANDBOX_CALLBACKS.get(requestId);
        if (callback == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionPoller] 无 sandbox 回调可处理响应 request={}", requestId);
            }
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SwarmPermissionPoller] 处理 sandbox 响应 request={} allow={}", requestId, allow);
        }
        PENDING_SANDBOX_CALLBACKS.remove(requestId);
        callback.resolve().accept(allow);
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 轮询 · 对齐 CC useSwarmPermissionPoller.ts:268-330
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 500ms 定时轮询 · 对齐 CC useSwarmPermissionPoller（:268-330）+ {@code useInterval(500ms)}。
     *
     * <p>守卫链（CC :271-285）：
     * <ol>
     *   <li>非 swarm worker → 跳过（{@link SwarmPermissionSync#isSwarmWorker}）</li>
     *   <li>isProcessing 防并发（:278-280）</li>
     *   <li>无 pending callback → 跳过（:283-285）</li>
     * </ol>
     *
     * <p>命中响应 → processResponse + {@link SwarmPermissionSync#removeWorkerResponse}（CC :298-308）。
     * 轮询错误 → log 不抛（CC :311-314）。
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void poll() {
        if (!SwarmPermissionSync.isSwarmWorker()) {
            return;
        }
        if (IS_PROCESSING.get()) {
            return;
        }
        if (PENDING_CALLBACKS.isEmpty()) {
            return;
        }
        if (!IS_PROCESSING.compareAndSet(false, true)) {
            return;
        }
        try {
            String agentName = TaskSystemConfig.getAgentName();
            String teamName = TaskSystemConfig.getTeamName();
            if (agentName == null || teamName == null) {
                return;
            }
            for (String requestId : PENDING_CALLBACKS.keySet()) {
                SwarmPermissionSync.PermissionResponse response =
                        SwarmPermissionSync.pollForResponse(requestId, agentName, teamName);
                if (response != null) {
                    boolean processed = processPermissionResponse(response);
                    if (processed) {
                        SwarmPermissionSync.removeWorkerResponse(requestId, agentName, teamName);
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionPoller] 轮询异常: {}", e.toString());
            }
        } finally {
            IS_PROCESSING.set(false);
        }
    }

    /** processResponse · 对齐 CC :231-257（mailbox 响应同款分流）。 */
    private static boolean processPermissionResponse(SwarmPermissionSync.PermissionResponse response) {
        PermissionResponseCallback callback = PENDING_CALLBACKS.get(response.requestId());
        if (callback == null) {
            return false;
        }
        PENDING_CALLBACKS.remove(response.requestId());
        if ("approved".equals(response.decision())) {
            // [DEL-WF7-02-01/R3] 磁盘轮询路径同款 safeParse 过滤（CC processResponse :248-251 parsePermissionUpdates）
            callback.onAllow().accept(new AllowResult(response.updatedInput(),
                filterPermissionUpdates(response.permissionUpdates())));
        } else {
            callback.onReject().accept(response.feedback());
        }
        return true;
    }

    /**
     * [DEL-WF7-02-01 / R3] permissionUpdates 畸形条目过滤 · 对齐 CC useSwarmPermissionPoller.ts:35-53
     * {@code parsePermissionUpdates}。
     *
     * <p>CC 对 external source（mailbox IPC / 磁盘轮询）的原始 {@code permission_updates}
     * 逐条 zod safeParse，保留合法条目、丢弃畸形（buggy/旧版 teammate 进程）——防止未经校验
     * 透传到 {@code callback.onAllow()}（:31-34 防污染意图）。本方法迁移自已删除的
     * {@code team/SwarmPermissionPoller.parsePermissionUpdates}（DEL-WF7-02-01）。
     *
     * <p><b>Java wire 形状兼容</b>：CC zod schema 强制 {@code type} 判别字段，但 Java mailbox
     * 回环（SwarmPermissionLoopTest）序列化的 PermissionUpdate record 无 {@code type}（如
     * {@code {"destination":"SESSION","paths":[...]}}）。严格 safeParse 会误删合法 Java 条目，
     * 故校验链：typed {@link PermissionUpdate} 直接可信 → 严格 {@link PermissionUpdateSchema#safeParse}
     * （CC 形状）→ 宽松 {@link WebSocketPermissionPrompter#parsePermissionUpdate}（Java wire 形状）。
     * 三种均失败才视为畸形丢弃 —— 保持 CC「过滤畸形、保留合法」的可观测意图。
     *
     * @param raw 原始 permissionUpdates 列表（可为 null）
     * @return 过滤后的列表（保留原条目表示，不重解释；不可变）
     */
    static List<Object> filterPermissionUpdates(List<Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? List.of() : raw;
        }
        List<Object> valid = new java.util.ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (isValidPermissionUpdate(entry)) {
                valid.add(entry);
            } else if (log.isWarnEnabled()) {
                log.warn("[SwarmPermissionPoller] 丢弃畸形 permissionUpdate 条目（safeParse 过滤，"
                    + "对齐 CC useSwarmPermissionPoller.ts:35-53）: {}", entry);
            }
        }
        return List.copyOf(valid);
    }

    /** 单条校验 · typed 对象 / CC 形状（带 type）/ Java wire 形状 三选一通过即合法。 */
    private static boolean isValidPermissionUpdate(Object entry) {
        if (entry instanceof PermissionUpdate) {
            return true;
        }
        if (PermissionUpdateSchema.safeParse(entry).isPresent()) {
            return true;
        }
        try {
            return WebSocketPermissionPrompter.parsePermissionUpdate(JSON.valueToTree(entry)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // worker mailbox 响应分发 · 对齐 CC useInboxPoller.ts:366-397/:465-495 +
    // inProcessRunner.ts:386-433（REV-FIX-6 缝隙4：inbox 分发零调用方）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 500ms 定时轮询 worker 自身邮箱的权限响应 · 对齐 CC useInboxPoller（worker 分支 :366-397）+
     * inProcessRunner.ts:386-433 的 mailbox 轮询（markMessageAsReadByIndex → processMailboxPermissionResponse）。
     *
     * <p><b>WHY（REV-FIX-6）</b>：CC 活动回环是纯 mailbox 通道 —— leader 侧决策经
     * {@code permission_response} 消息回传 worker 邮箱，worker 轮询自身邮箱消费。此前
     * {@link #processMailboxPermissionResponse} 0 调用方（无消费端），mailbox 响应永不被读取。
     * 本方法与既有 {@link #poll()}（磁盘 resolved/ 通道）双通道并存（对齐 CC worker 双通道：
     * useSwarmPermissionPoller + useInboxPoller）。
     *
     * <p>守卫链：非 swarm worker → 跳过（对齐 CC isTeammate :367）；防并发；命中即
     * {@code markMessageAsReadByIndex}（CC :403）+ {@code processMailboxPermissionResponse}。
     * 无对应 callback 的响应不标已读不消费（对齐 CC :376 hasPermissionCallback 守卫）。
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void pollMailboxResponses() {
        if (!SwarmPermissionSync.isSwarmWorker()) {
            return;
        }
        if (MAILBOX_PROCESSING.get()) {
            return;
        }
        if (!MAILBOX_PROCESSING.compareAndSet(false, true)) {
            return;
        }
        try {
            String agentName = TaskSystemConfig.getAgentName();
            String teamName = TaskSystemConfig.getTeamName();
            if (agentName == null || teamName == null) {
                return;
            }
            List<TeammateMailbox.TeammateMessage> all = TeammateMailbox.readMailbox(agentName, teamName);
            for (int i = 0; i < all.size(); i++) {
                TeammateMailbox.TeammateMessage msg = all.get(i);
                if (msg == null || msg.read()) {
                    continue;
                }
                TeammateMailbox.PermissionResponseMessage resp =
                        TeammateMailbox.isPermissionResponse(msg.text());
                if (resp != null && hasPermissionCallback(resp.requestId())) {
                    // 对齐 CC inProcessRunner.ts:403 markMessageAsReadByIndex（消费即已读）
                    TeammateMailbox.markMessageAsReadByIndex(agentName, teamName, i);
                    boolean processed = processMailboxPermissionResponse(resp.requestId(),
                            "success".equals(resp.subtype()) ? "approved" : "rejected",
                            resp.error(),
                            resp.response() != null ? resp.response().updatedInput() : null,
                            resp.response() != null ? resp.response().permissionUpdates() : null);
                    if (log.isDebugEnabled()) {
                        log.debug("[SwarmPermissionPoller] mailbox 响应已处理 request={} processed={}",
                                resp.requestId(), processed);
                    }
                    continue;
                }
                TeammateMailbox.SandboxPermissionResponseMessage sandboxResp =
                        TeammateMailbox.isSandboxPermissionResponse(msg.text());
                if (sandboxResp != null && hasSandboxPermissionCallback(sandboxResp.requestId())) {
                    TeammateMailbox.markMessageAsReadByIndex(agentName, teamName, i);
                    processSandboxPermissionResponse(sandboxResp.requestId(), sandboxResp.host(), sandboxResp.allow());
                    if (log.isDebugEnabled()) {
                        log.debug("[SwarmPermissionPoller] mailbox sandbox 响应已处理 request={} allow={}",
                                sandboxResp.requestId(), sandboxResp.allow());
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionPoller] mailbox 响应轮询异常: {}", e.toString());
            }
        } finally {
            MAILBOX_PROCESSING.set(false);
        }
    }
}

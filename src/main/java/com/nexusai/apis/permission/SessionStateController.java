package com.nexusai.apis.permission;

import com.nexusai.application.agent.permission.PendingPermissionSnapshot;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话运行时状态 · 对齐 dsh 的会话视图快照（宿主在每次打开/切换会话时**重放**未回答的
 * pending question / approval —— 见 {@code connection/fixture.ts:1598} 的 stable rpcId replay 语义）。
 *
 * <p><b>WHY 必须存在</b>：权限/提问此前只走一次性 STOMP 推送。若请求到达时该会话**不在前端
 * 订阅集合**里（非当前会话且无活跃流），事件被静默丢弃 —— 用户既看不到侧栏黄点，切回会话也
 * 不会弹（实测：AskUserQuestion 在 sess-df8b40c2 等待 12 分钟后被 Deny）。
 *
 * <p>当前承载：{@code pendingPermissions}（待回答的权限/提问，与 STOMP 事件同形，前端可直接入队）。
 * 后续会话级运行状态可在此扩展（接口名按「会话状态」而非「权限」命名）。
 *
 * <p><b>[刷新重放 · 三通道]</b>：本仓有三条权限通道（message 主通道 / bridge 远程弹窗 /
 * channel 通道中继）把同一个待审批请求推给不同表面 ⇒ 快照必须<b>三通道齐备</b>：
 * 此前只重放 message 通道 ⇒ bridge / channel 通道的待审批请求在用户<b>刷新页面</b>后消失
 * （用户看不到、也没法批准/拒绝）。三条通道的快照统一成 {@link PendingPermissionSnapshot}
 * 形状（前端 {@code PermissionEventPayload} 逐字段可消费）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/state")
public class SessionStateController {

    private static final Logger log = LoggerFactory.getLogger(SessionStateController.class);

    /** STOMP prompter（测试/无 WebSocket 场景可为 null → pending 恒空）。 */
    @Autowired(required = false)
    private WebSocketPermissionPrompter prompter;

    /**
     * 读取会话运行时状态。
     *
     * @param sessionId 会话 id（short）
     * @return 会话状态快照（pending 列表为空 = 该会话当前无需用户回答的交互）
     */
    @GetMapping
    public SessionStateDto state(@PathVariable String sessionId) {
        List<PendingPermissionSnapshot> pending = prompter == null
            ? List.of()
            : prompter.listPendingSnapshots(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[SessionStateController] session={} pendingPermissions={}", sessionId, pending.size());
        }
        return new SessionStateDto(sessionId, pending);
    }

    /**
     * 会话运行时状态快照。
     *
     * @param sessionId          会话 id
     * @param pendingPermissions 仍在等待用户回答的权限/提问请求（<b>三通道统一形状</b> ·
     *                           message / bridge / channel 三条通道的待审批请求都在这里 ·
     *                           见 {@link PendingPermissionSnapshot}）
     */
    public record SessionStateDto(String sessionId, List<PendingPermissionSnapshot> pendingPermissions) {}
}

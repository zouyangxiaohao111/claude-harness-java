package com.nexusai.apis.session;

import com.nexusai.application.agent.messaging.PeerMessageRouter;
import com.nexusai.eventbus.ws.PeerInboxEventPublisher;
import com.zjky.pro.framework.common.pojo.CommonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨会话待收件 · <b>计数基线</b>接口 · {@code GET /api/v1/peer-inbox/counts}。
 *
 * <h2>用途（与聚合通道配对）</h2>
 * STOMP 聚合 topic {@code /topic/peer-inbox} 是<b>非持久</b>通道：断线、丢帧、进程重启都会让前端
 * 错过事件。本端点给出「此刻每个会话还有几件待处理」的权威快照，供前端在
 * <b>重连 / 检测到 seq 回退 / 检测到 seq 缺口</b>时对账。
 *
 * <h2>响应契约（CommonResult，⛔ 不改数字码语义）</h2>
 * <pre>{ "code": 0, "msg": "成功", "success": true,
 *   "data": { "seq": 42, "counts": { "sess-aaa": 2, "sess-bbb": 1 } } }</pre>
 * <ul>
 *   <li>{@code seq} = 后端实例已分配的最大事件序号（0 = 本实例还没发过事件）；
 *       前端据此判断「我记的 seq 是不是这个实例的」——进程重启后 seq 归零，
 *       前端发现值比记忆值小即触发对账（契约明示为预期行为）。</li>
 *   <li>{@code counts} = 会话 id → 待处理条数；<b>只包含有待处理项的会话</b>（无待处理 → 不出现，
 *       不是 0）。口径与聚合事件 {@code pendingCount} 完全一致：
 *       {@code pending + approved} 减去已批准待消费（列表暂隐）的行；
 *       ⛔ 不含 rejected/accepted/expired。</li>
 *   <li>存储未接线 → {@code counts: {}}（⛔ 不伪造「所有会话都是 0」）。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：只返回会话 id 与条数（无正文、无 token、无发送方信息）。本端点不要求
 * 「可信 UI 审批主体」——它不执行任何写、也不暴露消息内容；审批类端点（list/approve/reject）
 * 的准入规则不变。
 */
@RestController
@RequestMapping("/api/v1/peer-inbox")
public class PeerInboxCountsController {

    private static final Logger log = LoggerFactory.getLogger(PeerInboxCountsController.class);

    /** 待收件路由器（生产 @Component）。required=false 容错（测试直构不走 Spring）。 */
    @Autowired(required = false)
    private PeerMessageRouter router;

    /** 聚合通道发布器：只为读「本实例已分配的最大 seq」。 */
    @Autowired(required = false)
    private PeerInboxEventPublisher publisher;

    /** 测试/接线用 setter。 */
    public void setRouter(PeerMessageRouter router) {
        this.router = router;
    }

    /** 测试/接线用 setter。 */
    public void setPublisher(PeerInboxEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 计数基线。
     *
     * @param seq    本实例已分配的最大事件序号（0 = 还没发过；重启归零）
     * @param counts 会话 id → 待处理条数（只含 &gt; 0 的会话）
     */
    public record CountsView(long seq, Map<String, Long> counts,
                             Map<String, Long> oldestRowId,
                             Map<String, String> oldestCreatedAt) {}

    /** 只读快照（HTTP 恒 200；沿用 CommonResult：{@code code=0 / success=true}）。 */
    @GetMapping("/counts")
    public CommonResult<CountsView> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> oldestRowId = new LinkedHashMap<>();
        Map<String, String> oldestCreatedAt = new LinkedHashMap<>();
        PeerMessageRouter r = this.router;
        if (r == null) {
            log.warn("PEER INBOX counts: PeerMessageRouter 未接线（返回空 map，不伪造 0 计数）");
        } else {
            try {
                Map<String, Long> snapshot = r.pendingCounts();
                if (snapshot != null) {
                    counts.putAll(snapshot);
                }
                // FIFO 附加信息（可选）：读失败只是让前端退回计数排序，不改 counts 本身。
                for (PeerMessageRouter.SessionPending row : r.pendingSessionCounts()) {
                    if (row == null || row.sessionId() == null) {
                        continue;
                    }
                    if (row.oldestRowId() != null) {
                        oldestRowId.put(row.sessionId(), row.oldestRowId());
                    }
                    if (row.oldestCreatedAt() != null && !row.oldestCreatedAt().isBlank()) {
                        oldestCreatedAt.put(row.sessionId(), row.oldestCreatedAt());
                    }
                }
            } catch (RuntimeException e) {
                // 读失败 ≠ 没有待处理项：返回空 map + 记日志，前端下次事件/对账会纠正。
                log.warn("PEER INBOX counts: 读取待处理计数失败（返回空 map）err={}", e.toString());
                counts.clear();
                oldestRowId.clear();
                oldestCreatedAt.clear();
            }
        }
        PeerInboxEventPublisher p = this.publisher;
        long seq = p == null ? 0L : p.seq();
        return CommonResult.ok(new CountsView(seq, counts, oldestRowId, oldestCreatedAt));
    }
}

package com.nexusai.apis.session;

import com.nexusai.application.agent.goal.GoalService;
import com.nexusai.domain.session.SessionGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [goal V253] 会话目标（/goal）REST 端点 · 对齐 CC claude.exe v2.1.212 的 activeGoal 生命周期。
 *
 * <p><b>WHY 需要这两个端点</b>（都是前端真实需求，不是「以防万一」）：
 * <ol>
 *   <li>{@code GET /api/v1/sessions/{sessionId}/goal} —— <b>初始拉取</b>。
 *       后端推的是会话级 STOMP topic（{@code /topic/sessions/{sid}/goal}），
 *       但 STOMP 只在「订阅已建立且事件到达」时才有值 ⇒ 切会话 / F5 / 后端重启后，
 *       目标头拿不到任何初始状态（对比 compact/tokenWarning/todos 都有 REST 兜底）。
 *       ⇒ 必须补一条读通道，前端切会话时拉一次。</li>
 *   <li>{@code DELETE /api/v1/sessions/{sessionId}/goal} —— <b>真清除</b>。
 *       目标头（GoalHead）的 × 按钮若只删前端 store，后端 {@code sessions.goal} 列还在、
 *       prompt Stop hook 还挂着 ⇒ 用户以为清掉了，下一轮评估器仍会拦模型，
 *       且后端下次 push 后目标头又回来。⇒ 清除必须走后端。</li>
 * </ol>
 *
 * <p><b>真源单一</b>：本控制器不持有任何状态，读写都经 {@link GoalService}
 * （真源 = {@code sessions.goal} 列，内存只是「已挂 hook」的缓存）。
 *
 * <p>⚠️ 路由与既有 {@code /api/v1/sessions/**} 无冲突：
 * {@code SessionController}（/{id}、/{id}/usage）、{@code ChatController}（/{sessionId}/messages…）、
 * {@code TodoStatusController}（/{sessionId}/todos）、{@code SessionArtifactController}（/{sessionId}/artifacts）。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/goal")
public class SessionGoalController {

    private static final Logger log = LoggerFactory.getLogger(SessionGoalController.class);

    /**
     * 目标服务 · {@code required=false}：非 Spring 直构测试无 bean → null → 端点降级空态，
     * 不影响其他用例（同 {@code TodoStatusController} 的注入风格）。
     */
    @Autowired(required = false)
    private GoalService goalService;

    /**
     * 会话目标快照 · GET /api/v1/sessions/{sessionId}/goal。
     *
     * <p><b>200 + 空态</b>（不是 404）：「该会话没有目标」是合法状态
     * （对齐 TodoStatusController 的空列表语义 —— 前端不需要特判 404）。
     *
     * @param sessionId 会话 id
     * @return 有目标 → {@code {condition, phase, iterations, setAt, tokensAtStart, lastReason}}；
     *         无目标 / 服务未接线 → {@code {goal: null}}
     */
    @GetMapping
    public GoalResponse get(@PathVariable String sessionId) {
        SessionGoal goal = goalService == null ? null : goalService.getGoal(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("SessionGoalController.get: session={} 有目标={}", sessionId, goal != null);
        }
        return new GoalResponse(goal == null ? null : new GoalPayload(
            goal.condition(),
            goal.phase().name().toLowerCase(),
            goal.iterations(),
            goal.setAt(),
            goal.tokensAtStart(),
            goal.lastReason()));
    }

    /**
     * 清除会话目标 · DELETE /api/v1/sessions/{sessionId}/goal。
     *
     * <p><b>语义</b>：列置 null + 摘 prompt Stop hook + 推 STOMP（前端目标头立即消失）。
     * 等价于 {@code /goal clear}。
     *
     * <p>幂等：本来就没有目标 → 仍 200（清除「无目标」不是错误）。
     *
     * @param sessionId 会话 id
     * @return {@code {cleared: true|false}}（false = 本来就没有目标）
     */
    @DeleteMapping
    public ClearResponse clear(@PathVariable String sessionId) {
        if (goalService == null) {
            log.warn("SessionGoalController.clear: GoalService 未接线（非容器上下文），跳过 session={}", sessionId);
            return new ClearResponse(false);
        }
        String cleared = goalService.clearGoal(sessionId);
        if (log.isInfoEnabled()) {
            log.info("SessionGoalController.clear: session={} 已清除目标={}", sessionId, cleared != null);
        }
        return new ClearResponse(cleared != null);
    }

    /**
     * 响应载荷 · 与 {@code SessionGoal} 六字段一一对应（形状与 STOMP 推送载荷一致
     * ⇒ 前端同一套 {@code parseGoalPayload} 归一逻辑吃两处，不写第二套解析）。
     */
    public record GoalPayload(
        String condition,
        String phase,
        int iterations,
        long setAt,
        long tokensAtStart,
        String lastReason
    ) {
    }

    /** GET 响应 · {@code goal=null} = 该会话无目标。 */
    public record GoalResponse(GoalPayload goal) {
    }

    /** DELETE 响应 · {@code cleared=false} = 本来就没有目标（幂等）。 */
    public record ClearResponse(boolean cleared) {
    }
}

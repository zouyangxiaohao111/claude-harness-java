package com.nexusai.apis.session;

import com.nexusai.application.session.SessionUsageTurnService;
import com.nexusai.application.session.SessionUsageTurnService.TurnUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 历史会话「按轮用量」只读端点 · {@code GET /api/v1/sessions/{sessionId}/usage/turns}。
 *
 * <p><b>WHY</b>：重新打开历史会话时，消息级 {@code input_tokens/output_tokens} 为 NULL（旧消息
 * 落库时未回填）⇒ 轮次底部只剩「模型 · 日期时间」。本端点用 {@code llm_usage_calls} 明细按轮聚合
 * 出「本轮消耗」，供前端在消息级 token 缺失时回落。
 *
 * <p><b>响应</b>：{@code {"turns": [{"userMessageId", "inputTokens", "outputTokens",
 * "costYuan", "model"}]}}，按 {@code userMessageId} 时间升序；空会话 / 无明细 →
 * {@code {"turns": []}} + 200（⛔ 不是 404/204：前端 {@code api<>()} 统一按 JSON 解析）。
 * {@code costYuan}/{@code model} 可为 null（该轮无 cost 数据）。
 *
 * <p><b>口径与数据源</b>见 {@link SessionUsageTurnService} 类 javadoc。本类只做请求映射：
 * 聚合 owner 是 {@code application.session} 域，⛔ 不在此拼 SQL。
 *
 * <p><b>只读</b>：不写任何表，不动迁移。
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class SessionUsageController {

    private static final Logger log = LoggerFactory.getLogger(SessionUsageController.class);

    @Autowired private SessionUsageTurnService sessionUsageTurnService;

    /**
     * 该会话每轮用量（只读）· 前端在轮次底部消息级 token 缺失时回落。
     *
     * @param sessionId 会话 ID（路径变量）
     * @return 200 {@code {"turns": [...]}}；无数据 → 空数组（前端回落原文案「模型 · 时间」）
     */
    @GetMapping("/usage/turns")
    public UsageTurnsResponse turns(@PathVariable String sessionId) {
        List<TurnUsage> turns = sessionUsageTurnService.listTurnUsage(sessionId);
        if (log.isInfoEnabled()) {
            log.info("GET /api/v1/sessions/{}/usage/turns → 轮次数={}（明细表按轮聚合，只读）",
                sessionId, turns.size());
        }
        return new UsageTurnsResponse(turns);
    }

    /** 响应体 · {@code turns} 按轮起点时间升序；无数据 → 空列表。 */
    public record UsageTurnsResponse(List<TurnUsage> turns) {}
}

package com.nexusai.apis.skillimprovement;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore;
import com.nexusai.application.agent.permission.hook.SkillImprovementSuggestionStore.PendingSuggestion;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Skill Improvement 决策端点 · Java 等价 CC {@code useSkillImprovementSurvey.ts handleSelect (:53-98)}
 * 的后端决策闸门.
 *
 * <p><b>[P1-13] apply 半环接线</b> (WHY 规则五 · 仅模型用于判断与裁量): CC 中 survey 的
 * applied/dismissed 是<b>用户显式决策</b> — 前端 {@code useSkillImprovementSurvey.ts:58} {@code
 * applied = selected !== 'dismissed'} → {@code applySkillImprovement} 改写 SKILL.md
 * (useSkillImprovementSurvey.ts:73). Java 无前端, 本 controller 暴露 REST decision endpoint
 * 镜像该用户决策闸门: <b>不接受客户端 skillName</b> (只读 server 端 store, 防路径穿越,
 * 对齐 CC "从 AppState 服务端读, 不信任客户端").
 *
 * <p>决策流 (严格镜像 useSkillImprovementSurvey.ts handleSelect :53-98):
 * <ol>
 *   <li>GET /suggestion → 读 store 待定 suggestion (CC useSkillImprovementSurvey.ts:26 读 AppState)</li>
 *   <li>POST /decision → 读 store → {@code applied} → 触发
 *       {@link SkillImprovementHook#applySkillImprovement} fire-and-forget (CC :73)</li>
 *   <li>telemetry {@code tengu_skill_improvement_survey} responded=applied/dismissed (CC :60-70)</li>
 *   <li>store.remove 清空 suggestion (CC :89-95 {@code setAppState({suggestion:null})})</li>
 * </ol>
 *
 * @see SkillImprovementSuggestionStore
 * @see SkillImprovementHook
 * @since Session P1-13
 */
@RestController
@RequestMapping("/api/v1/skill-improvement")
public class SkillImprovementController {

    private static final Logger log = LoggerFactory.getLogger(SkillImprovementController.class);

    /**
     * Decision 请求体 · Java 等价 CC {@code handleSelect(selected: FeedbackSurveyResponse)}
     * 的 {@code applied = selected !== 'dismissed'} (useSkillImprovementSurvey.ts:58).
     *
     * @param sessionId CC original: 无 (Java 端 REST 会话标识) — 定位 store 中待定 suggestion；
     *                  [session-id-short] {@code "sess-xxxxxxxx"} short 直键 store（不再 parseSessionUuid）
     * @param applied   CC original: applied — true=应用改进, false=dismissed 放弃
     */
    public record SkillImprovementDecisionRequest(String sessionId, boolean applied) {}

    private final SkillImprovementSuggestionStore store;
    private final SkillImprovementHook skillImprovementHook;
    private final Telemetry telemetry;

    /**
     * Spring 构造.
     *
     * @param store               session-keyed suggestion store (suggestion 唯一可信源)
     * @param skillImprovementHook apply 执行器 (决策 applied 时触发)
     * @param telemetry           survey 遥测上报 (tengu_skill_improvement_survey)
     */
    @Autowired
    public SkillImprovementController(
            SkillImprovementSuggestionStore store,
            SkillImprovementHook skillImprovementHook,
            @Autowired(required = false) Telemetry telemetry) {
        this.store = store;
        this.skillImprovementHook = skillImprovementHook;
        this.telemetry = telemetry;
    }

    /**
     * GET /suggestion · 读取待定 suggestion.
     * CC original: {@code useAppState(s => s.skillImprovement.suggestion)}
     * (useSkillImprovementSurvey.ts:26).
     *
     * @param sessionId 会话 ID（short sess-xxx；[session-id-short] 直键 store，不再 parseSessionUuid）
     * @return 200 + {skillName, updates} (有待定 suggestion); 204 (无)
     */
    @GetMapping("/suggestion")
    public ResponseEntity<PendingSuggestion> suggestion(
            @RequestParam("sessionId") String sessionId) {
        PendingSuggestion pending = store.peek(sessionId);
        if (pending == null) {
            return ResponseEntity.noContent().build();
        }
        if (log.isDebugEnabled()) {
            log.debug("Skill improvement GET /suggestion: session={} skill={} updates={}",
                    sessionId, pending.skillName(), pending.updates().size());
        }
        return ResponseEntity.ok(pending);
    }

    /**
     * POST /decision · 用户显式决策闸门 (镜像 CC handleSelect :53-98).
     *
     * <p>只从 store 读 PendingSuggestion (不接受客户端 skillName — 防路径穿越); applied →
     * {@code applySkillImprovement} fire-and-forget (不阻塞主对话, CC :73 {@code void ...});
     * telemetry responded; store 清空 (CC :89-95).
     *
     * @param req {sessionId, applied}
     * @return 200 + {status, skillName}; 400 (sessionId 缺失); 404 (无待定 suggestion)
     */
    @PostMapping("/decision")
    public ResponseEntity<Map<String, Object>> decision(
            @RequestBody(required = false) SkillImprovementDecisionRequest req) {
        if (req == null || req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // [session-id-short] req.sessionId() 已 short 直键 store（不再 parseSessionUuid）
        // 一次性消费: 原子 remove — 避免并发决策重复 apply (CC handleSelect 同步清 suggestion, :89-95)
        PendingSuggestion pending = store.remove(req.sessionId());
        if (pending == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String response = req.applied() ? "applied" : "dismissed";
        // CC original: logEvent('tengu_skill_improvement_survey', {event_type:'responded', response: applied?'applied':'dismissed', _PROTO_skill_name}) (useSkillImprovementSurvey.ts:60-70)
        if (telemetry != null) {
            telemetry.recordEvent("tengu_skill_improvement_survey", Map.of(
                    "event_type", "responded",
                    "response", response,
                    "_PROTO_skill_name", pending.skillName()));
        }

        if (req.applied()) {
            // CC original: void applySkillImprovement(current.skillName, current.updates) (useSkillImprovementSurvey.ts:73)
            skillImprovementHook.applySkillImprovement(pending.skillName(), pending.updates());
        }

        log.info("Skill improvement 决策已受理: session={} skill={} response={}",
                req.sessionId(), pending.skillName(), response);
        return ResponseEntity.ok(Map.of("status", response, "skillName", pending.skillName()));
    }
}

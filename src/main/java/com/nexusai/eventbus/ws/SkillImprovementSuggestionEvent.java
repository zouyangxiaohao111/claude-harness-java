package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Skill Improvement 建议事件 · [IMP-WF6-DC-01]（用户拍板，2026-08-16）。
 *
 * <p><b>WHY</b>：CC 侧前端 {@code useSkillImprovementSurvey.ts:26} 响应式读
 * {@code AppState.skillImprovement.suggestion}（skillImprovement.ts:160-165），检测器一旦
 * 写入 suggestion 前端即自动弹 survey。Java web 端无响应式 AppState 通道（appStateRef 在
 * LlmAgentLoop 实例字段），故由 {@code SkillImprovementHook} 在生成 suggestion 时经
 * WebSocket/STOMP 推送本事件，前端收到即弹窗，对齐 CC 响应式效果。
 *
 * <p><b>topic</b>：{@code /topic/sessions/{sess-xxx}}（session-level topic，前端
 * {@code useChatSocket.ts:42} 已订阅，与 {@code session_backgrounded} 同通道）。
 *
 * <p><b>载荷说明</b>：suggestion 完整内容（skillName + updates）走 store + REST
 * {@code GET /api/v1/skill-improvement/suggestion} peek / {@code POST .../decision} 决策，
 * 本事件只携带轻量信号——skillName（survey 标题）+ updateCount（预览条数）+ sessionId
 * （short sess-xxx，REST 决策端点直键 store，[session-id-short] 不再 parseSessionUuid 键空间）。
 *
 * <p><b>local-only 约束核对</b>：本事件不序列化 {@code SkillImprovementSuggestionStore} bean、
 * 不进入 AgentState / EventPublisher / LLM payload；仅推送轻量建议信号（用户拍板 IMP-WF6-DC-01
 * 授权），完整建议内容仍留在 store 由 REST 读取。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillImprovementSuggestionEvent extends StreamEvent {

    /** 待改进的 skill 名 · CC original: {@code AppState.skillImprovement.suggestion.skillName}
     *  (skillImprovement.ts:163). */
    private final String skillName;

    /** 改进项条数 · CC original: {@code suggestion.updates.length}（survey 预览）. */
    private final int updateCount;

    /**
     * @param sessionId  会话 ID（short sess-xxx；REST 决策端点直键，ToolUseContext.sessionId）
     * @param skillName  待改进 skill 名
     * @param updateCount 改进项条数
     */
    public SkillImprovementSuggestionEvent(String sessionId, String skillName, int updateCount) {
        super("skill_improvement.suggestion", sessionId, null);
        this.skillName = skillName;
        this.updateCount = updateCount;
    }

    public String getSkillName() { return skillName; }
    public int getUpdateCount() { return updateCount; }
}

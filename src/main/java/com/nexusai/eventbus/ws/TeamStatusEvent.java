package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.model.team.dto.TeamDto;

/**
 * Team 状态事件 · 服务端 → 前端 STOMP 推送。
 *
 * <p>topic: {@code /topic/sessions/{leadSessionId}/team-status}（leadSessionId = team config.leadSessionId，
 * 只推给创建者会话，stomp-lead-session 方案 3；WebSocketConfig enableSimpleBroker("/topic","/queue") →
 * 无需注册）。
 *
 * <p>触发点：TeamCreateTool 建（"created"）/ TeamDeleteTool 解散（"deleted"）/ TeamController
 * 成员 join/leave（"member_joined"/"member_left"）→ {@link
 * com.nexusai.application.agent.team.TeamStatusPublisher#publish} 单点推送。
 *
 * <p>member_active / member_mode eventType 枚举预留（TeamHelpers.setMemberActive/setMemberMode
 * 接线，本期不接）。
 *
 * @param type      CC original: — 恒 "team.status"
 * @param teamName  目标 team
 * @param eventType created | deleted | member_joined | member_left
 * @param team      团队详情快照（可空，发布侧尽力填充）
 * @param timestamp ISO-8601（对齐 teammateMailbox 信封时间戳）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamStatusEvent(
        @JsonProperty("type") String type,
        @JsonProperty("teamName") String teamName,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("team") TeamDto team,
        @JsonProperty("timestamp") String timestamp) {
    public static TeamStatusEvent of(String teamName, String eventType, TeamDto team) {
        return new TeamStatusEvent("team.status", teamName, eventType, team, TeammateMailbox.isoNow());
    }
}

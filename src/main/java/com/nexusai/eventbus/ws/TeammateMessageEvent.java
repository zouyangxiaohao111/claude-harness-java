package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.application.agent.team.TeammateMailbox;

/**
 * Teammate 消息出站事件 · 服务端 → 前端 STOMP 推送（B1 消息流展示，design doc §2.2 outboundEvent
 * 8 字段精确匹配）。
 *
 * <p>topic: {@code /topic/sessions/{leadSessionId}/team-messages}（leadSessionId = team config.leadSessionId，
 * 只推给创建者会话，stomp-lead-session 方案 3）。
 *
 * <p>触发点：SendMessageTool 写 inbox 后（handleMessage 单播 / handleBroadcast 广播）经
 * {@code publishTeammateMessage} 推送；teamName 从 teamContext.teamName 得（已落 sessions 列）。
 *
 * @param type      CC original: — 恒 "teammate.message"
 * @param teamName  目标 team
 * @param from      发送方 agent 名
 * @param to        收件名（单播 = 收件 agent 名；广播 = "*"）
 * @param text      消息文本
 * @param summary   UI 预览摘要（可空）
 * @param color     发送方颜色（可空）
 * @param timestamp ISO-8601（对齐 teammateMailbox 信封时间戳）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeammateMessageEvent(
        @JsonProperty("type") String type,
        @JsonProperty("teamName") String teamName,
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("text") String text,
        @JsonProperty("summary") String summary,
        @JsonProperty("color") String color,
        @JsonProperty("timestamp") String timestamp) {
    public static TeammateMessageEvent of(String teamName, String from, String to,
                                          String text, String summary, String color) {
        return new TeammateMessageEvent("teammate.message", teamName, from, to,
            text, summary, color, TeammateMailbox.isoNow());
    }
}

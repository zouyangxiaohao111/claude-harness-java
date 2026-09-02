package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 后台落库 user 消息推送 · 对齐前端 PushedUserMessageEvent（nexusai types.ts:822-827）。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/stream}。
 *
 * <p><b>WHY（cron 消息顺序倒挂修复）</b>：cron 触发（CronIdleExecutor）的 user prompt 只落库不推
 * 前端 → 前端 messages 缺该 user 消息锚点 → 该 cron 轮 assistant 流式块（无 message.complete 收口）
 * 残留 streams，被后续用户 turn 的 complete 混收口后按 flowKey=userMessageId 找不到锚点插入末尾 →
 * 顺序倒挂。推 message.user（isMeta=true，前端 appendMetaUser 占位不显示但建立 flow 锚点）+
 * message.complete 收口，双管齐下根治。isMeta=true 对齐 CC createUserMessage isMeta 语义
 * （useScheduledTasks.ts:76 cron 入队 isMeta 语义 —— UI 隐藏但模型可见）。
 *
 * @param id       user 消息 id（= 落库后真实 id；前端 appendMetaUser 按 id 幂等去重）
 * @param content  user prompt 原文
 * @param isMeta   cron/后台落库 prompt 标记（前端占位不显示，模型上下文仍可见）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageUserEvent extends StreamEvent {

    private final String id;
    private final String content;

    @JsonProperty("isMeta")
    private final boolean isMeta;

    public MessageUserEvent(String sessionId, String userMessageId, String id, String content, boolean isMeta) {
        super("message.user", sessionId, userMessageId);
        this.id = id;
        this.content = content;
        this.isMeta = isMeta;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public boolean getIsMeta() { return isMeta; }
}

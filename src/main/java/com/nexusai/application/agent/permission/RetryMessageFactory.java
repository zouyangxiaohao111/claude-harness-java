package com.nexusai.application.agent.permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 重试元消息工厂 · 对齐 CC {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1093-1098}
 * {@code createUserMessage({ content, isMeta: true })}。
 *
 * <p>WHAT: 生成 isMeta=true 的 user message, 告诉 LLM PermissionDenied hook 已允许重试。
 * 消息内容固定为 CC 真源原文, 不做本地化（保持审计可对照）。
 *
 * <p>WHY isMeta=true: LLM UI 客户端按 isMeta 字段隐藏该消息 (用户不应看到 hook 内部信号),
 * 但消息仍在对话历史里 → LLM 看到 "可以重试" 的指令。
 */
public final class RetryMessageFactory {

    private static final String RETRY_MESSAGE =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";
    private static final Logger log = LoggerFactory.getLogger(RetryMessageFactory.class);


    /**
     * 实例化入口（公开以允许测试 + 注入 setter; 真正的消息生成走 {@link #createRetryMessage(String)}）。
     */
    public RetryMessageFactory() {
    }

    public static ChatMessageDto createRetryMessage(String sessionId) {
        if (log.isDebugEnabled()) {
            log.debug("PERMISSION retry 消息生成 (CC toolExecution.ts:1093-1098 createUserMessage isMeta=true): sessionId={}",
                sessionId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        return new ChatMessageDto(
            java.util.UUID.randomUUID().toString(),
            sessionId,
            Role.user,
            null,
            RETRY_MESSAGE,
            null,
            null,
            null,
            null,
            null,
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(now),
            now,
            null,
            null,
            null,
            null,
            null,
            null,
            true
        );
    }
}

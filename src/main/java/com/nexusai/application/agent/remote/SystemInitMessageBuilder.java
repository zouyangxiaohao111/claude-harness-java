package com.nexusai.application.agent.remote;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * System Init Message Builder · 对齐 CC utils/messages/systemInit.ts.
 *
 * <p>FIX-MSG-INIT: SDK `system/init` message builder.
 *
 * <p>L1 行为: 构造 SDK init 消息 (含 sessionId/model/tools).
 */
@Component
public class SystemInitMessageBuilder {

    public record SystemInitMessage(String type, String sessionId, String model,
                                     String cwd, java.util.List<String> tools,
                                     Map<String, Object> toolspec) {}

    public SystemInitMessage build(String sessionId, String model, String cwd,
                                   java.util.List<String> tools, Map<String, Object> toolspec) {
        return new SystemInitMessage("system/init", sessionId, model, cwd, tools, toolspec);
    }
}
package com.nexusai.application.agent.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * CoreSchemas · 对齐 CC entrypoints/sdk/coreSchemas.ts (核心 Zod schema 概念).
 *
 * <p>L1 语义: SDK 核心 schemas 的简化 Java 镜像 — 定义主要 wire type:
 * <ul>
 *   <li>{@code SDKMessage} — assistant/user/system/tool 流事件</li>
 *   <li>{@code SDKControlRequest} / {@code SDKControlResponse} — 控制协议</li>
 *   <li>{@code SDKPermissionRequest} — 权限询问</li>
 *   <li>{@code SDKHookEvent} — hook 回调</li>
 * </ul>
 * Java 端 {@code com.nexusai.model.session.dto} 提供具体 DTO record;本类
 * 提供语义分类 helper (isAssistantMessage/isPermissionRequest 等 type guard)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 静态方法 helper + 5 type-guard 函数;无副作用</li>
 *   <li><b>A2 Golden Trace</b>: type字段匹配规则;isAssistantMessage("assistant")=true,等</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null/缺失 type 字段 → 所有 type-guard 返回 false</li>
 *   <li><b>A5 业务场景</b>: SDK message stream 派单根据 type guard 路由</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC Zod schema runtime validation → Java 无 Zod;type guard 用
 * 静态方法 + Function accessor;record type 通过字符串匹配 type 字段。
 */
public final class CoreSchemas {

    private CoreSchemas() {}

    /** Type-guard: returns true iff the message's type field equals {@code "assistant"}. */
    public static boolean isAssistantMessage(Map<String, Object> msg) {
        return "assistant".equals(typeOf(msg));
    }

    /** Type-guard: returns true iff the message's type field equals {@code "user"}. */
    public static boolean isUserMessage(Map<String, Object> msg) {
        return "user".equals(typeOf(msg));
    }

    /** Type-guard: system message (init/status/etc.). */
    public static boolean isSystemMessage(Map<String, Object> msg) {
        return "system".equals(typeOf(msg));
    }

    /** Type-guard: tool result message. */
    public static boolean isToolResultMessage(Map<String, Object> msg) {
        return "tool_result".equals(typeOf(msg));
    }

    /** Type-guard: SDK control request (interrupt/permission/hook_callback). */
    public static boolean isControlRequest(Map<String, Object> req) {
        return "control_request".equals(typeOf(req));
    }

    /** Type-guard: SDK permission request subtype. */
    public static boolean isPermissionRequest(Map<String, Object> req) {
        if (!isControlRequest(req)) return false;
        Object subtype = req.get("request");
        return "permission".equals(subtype instanceof Map ? ((Map<?, ?>) subtype).get("subtype") : subtype);
    }

    /** Type-guard: SDK hook callback subtype. */
    public static boolean isHookCallback(Map<String, Object> req) {
        if (!isControlRequest(req)) return false;
        Object subtype = req.get("request");
        return "hook_callback".equals(subtype instanceof Map ? ((Map<?, ?>) subtype).get("subtype") : subtype);
    }

    private static Object typeOf(Map<String, Object> m) {
        if (m == null) return null;
        Object t = m.get("type");
        return t instanceof String ? t : null;
    }

    /**
     * Categorize a wire message and return its high-level kind.
     * Returns "unknown" if no rule matches.
     */
    public static String categorize(Map<String, Object> msg) {
        if (msg == null) return "unknown";
        if (isAssistantMessage(msg)) return "assistant";
        if (isUserMessage(msg)) return "user";
        if (isSystemMessage(msg)) return "system";
        if (isToolResultMessage(msg)) return "tool_result";
        if (isPermissionRequest(msg)) return "permission_request";
        if (isHookCallback(msg)) return "hook_callback";
        if (isControlRequest(msg)) return "control_request";
        return "unknown";
    }
}

package com.nexusai.model.session.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** POST /api/v1/sessions/{sessionId}/messages 请求 */
public record SendMessageRequest(
    @NotBlank String content,
    String modelName,                // 可选 · 覆盖 session 默认 model
    Boolean useFastModel,            // 可选 · true=用 fastModel，false=用 mainModel
    List<AttachmentRequest> attachments, // 附件列表 · 对齐 CC pastedContents（utils/config.ts:54-62，
                                     //   A1 契约升级：List<String> 占位 → List<AttachmentRequest>）
                                     //   每项 {type, contentId, filename, mediaType, base64}，
                                     //   ChatService.processUserMessage 消费（contentId→ImageAttachmentStore 读缓存）
    String appendSystemPrompt,       // [RES-SP31 · OPD-SP-31] 可选 · 用户追加指令（恒末尾追加进 system prompt）
                                     // CC original: --append-system-prompt（main.tsx:1364-1382 + systemPrompt.ts:121）
    String fallbackModel,            // [DEC-RV-02 · FIX-16] 可选 · 本次调用按调用传入的降级模型（连续 529 后切换）
                                     // CC original: --fallback-model / userSpecifiedFallbackModel（main.tsx:2020）
                                     // null = 无按调用指定 → 回落 settings.fallbackModelId 默认值（决策 10 不变，F4）
    String permissionMode,           // [RV-11 · REV-FIX-2] 可选 · 本次调用的初始权限模式（会话级，非 per-turn）
                                     // CC original: --permission-mode（main.tsx:1099 permissionMode: permissionModeCli）
                                     // null = 未指定 → 回落 settings.defaultMode
    Boolean dangerouslySkipPermissions, // [RV-11 · REV-FIX-2] 可选 · 本次调用是否跳过全部权限检查
                                     // CC original: --dangerously-skip-permissions（main.tsx:621 rawCliArgs.includes）
                                     // null/false = 不跳过
    JsonNode jsonSchema,             // [IMP-HR-08 · OPD-WF6-01-06-?-3] 可选 · 本次调用主循环结构化输出 JSON Schema
                                     // CC original: --json-schema（main.tsx:1880-1883 + QueryEngine.ts:327-333）
                                     // null = 未指定 → 主循环不注册 structured output enforcement
    Map<String, Object> requestExtras // 可选 · 请求级通用透传参数（本批仅骨架空转 · 不落库 · 不参与任何行为）
                                     // 供工具执行期读取本轮「用户 @ 的库/文件」等请求级范围；null = 无
                                     // （批 2 才由 kb/api/KbChatGatewayController.viaReact 写入）
) {
    /** 旧 9 参转发构造器（requestExtras 缺省 = null · 既有调用点零改动）。 */
    public SendMessageRequest(String content, String modelName, Boolean useFastModel,
                              List<AttachmentRequest> attachments, String appendSystemPrompt,
                              String fallbackModel, String permissionMode,
                              Boolean dangerouslySkipPermissions, JsonNode jsonSchema) {
        this(content, modelName, useFastModel, attachments, appendSystemPrompt, fallbackModel,
            permissionMode, dangerouslySkipPermissions, jsonSchema, null);
    }
}

package com.nexusai.application.agent.permission;

import java.util.List;

/**
 * 权限弹窗展示细节 · 对齐 CC {@code useCanUseTool.tsx:56-60} + {@code interactiveHandler.ts:250-253}.
 *
 * <p>CC 在 ask/deny 分流前 {@code await tool.description(input, ...)} 生成弹窗描述文案；
 * 交互队列 (interactiveHandler.ts:92-232) 与 bridge (CCR/claude.ai 远程弹窗,
 * interactiveHandler.ts:244-298) 都携带 {@code description} / {@code suggestions} /
 * {@code blockedPath}。Java 端把这三者打包为 {@link PermissionPromptDetails}，
 * 经 {@link PermissionPrompter#prompt(Tool, com.fasterxml.jackson.databind.JsonNode,
 * PermissionDecisionReason, ToolUseContext, String, PermissionPromptDetails)}
 * 透传到 STOMP 弹窗事件。
 *
 * @param description 弹窗展示的工具描述文案（CC tool.description(input) 产物，可为 null）
 * @param suggestions 建议的 {@link PermissionUpdate}（"Add allow rule" 等一键授权建议，可为 null）
 * @param blockedPath 被阻断的文件路径（safetyCheck / content-specific ask 场景，可为 null）
 * @param pendingClassifierCheck 待分类器检查结构体 · 非 null 且工具为 Bash 时，交互分支
 *                                在后台竞速 bash classifier（CC interactiveHandler.ts:433-530
 *                                {@code executeAsyncClassifierCheck}）；null = 不启动分类器竞速。
 *                                对齐 CC types/permissions.ts:190-194 PendingClassifierCheck
 * @param runHookRace true = 后台竞速 PermissionRequest hooks（CC interactiveHandler.ts:411-431
 *                    {@code !awaitAutomatedChecksBeforeDialog} 时 hooks 由 interactive 分支异步
 *                    执行并采纳决策）；false（coordinator 已消费 hooks）= 不重复竞速
 * @see WebSocketPermissionPrompter
 * @see com.nexusai.eventbus.ws.MessagePermissionRequestEvent
 * @since canUseTool v2 修复 · v3 扩展交互竞速规格
 */
public record PermissionPromptDetails(
        String description,
        List<PermissionUpdate> suggestions,
        String blockedPath,
        PermissionResult.PendingClassifierCheck pendingClassifierCheck,
        boolean runHookRace
) {
    public PermissionPromptDetails {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        // description / blockedPath 可为 null（CC 可空字段）
        // pendingClassifierCheck 可为 null（无分类器检查）
    }

    /** 3 参兼容构造器 · 无分类器竞速 + 无 hook 竞速（旧调用方/测试桩无需改动）。 */
    public PermissionPromptDetails(String description, List<PermissionUpdate> suggestions, String blockedPath) {
        this(description, suggestions, blockedPath, null, false);
    }

    /** 空细节（无 description / suggestions / blockedPath / 竞速）· 调用方不关心弹窗展示细节时用。 */
    public static PermissionPromptDetails none() {
        return new PermissionPromptDetails(null, List.of(), null, null, false);
    }
}

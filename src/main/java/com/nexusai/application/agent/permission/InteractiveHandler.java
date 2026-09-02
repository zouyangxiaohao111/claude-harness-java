package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.mcp.ChannelNotificationGate;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Interactive REPL permission handler · 对齐 CC hooks/toolPermission/handlers/interactiveHandler.ts.
 *
 * <p>L1 语义: tool 权限请求的 REPL 交互处理 — 弹窗 + 同步阻塞等用户响应。
 *
 * <p>O1 删除说明（S13）: 旧 queue 模型（3 参构造器 + 三个 handle* 入口 + 队列/分类器/
 * 超时常量等）已整体删除 — CC interactiveHandler.ts 无 queue 模型，交互决策直连
 * prompter（S07 hook 决策接线后）。保留 {@link #CHANNEL_PERMISSION_REQUEST_METHOD}
 * 常量（channel 协议名单点收敛，mcp-align impl-I-3 T5）。
 */
@Component
public final class InteractiveHandler {

    private static final Logger log = LoggerFactory.getLogger(InteractiveHandler.class);

    /**
     * 出站 channel permission 请求 method · CC original: {@code CHANNEL_PERMISSION_REQUEST_METHOD}
     * (channelNotification.ts:85-86 {@code 'notifications/claude/channel/permission_request'}).
     *
     * <p>[impl-I-3 T5] 单点收敛到 {@link ChannelNotificationGate#CHANNEL_PERMISSION_REQUEST_METHOD}
     * （MCP-05 Y2：旧裸值 {@code 'channel/permission_request'} 缺前缀，三处 method 字符串不一致）·
     * 服务端按协议名推送的 structured permission 事件与 Java 侧期望 method 错配 → 通道审批死链。
     */
    public static final String CHANNEL_PERMISSION_REQUEST_METHOD =
            ChannelNotificationGate.CHANNEL_PERMISSION_REQUEST_METHOD;
    /**
     * 生产接线弹窗器 · 可为 null（手工 new 未接线）。
     *
     * <p>interactive 分支的"弹窗"由本字段委托 {@link PermissionPrompter#prompt} 完成 —
     * 内部已是 queue+回调+四路竞速（hook/classifier/bridge/channel + createResolveOnce.claim），
     * 对齐 CC interactiveHandler.ts:92-232 的 pushToQueue + resolve callback 语义
     * （Java 同步模型中 queue push = prompter 内部 STOMP 推送，等待由竞速 racer 提前 resolve）。
     */
    private final PermissionPrompter prompter;

    /**
     * 生产接线构造器 · prompter 委托模式。
     *
     * <p>标注 @Autowired → Spring 注入 WebSocketPermissionPrompter bean；null（无 bean /
     * 非 Spring 场景）→ 退化为 NoOpPrompter（prompt 调用即显式失败，暴露接线缺失而非静默放行）。
     *
     * @param prompter 弹窗询问器（WebSocketPermissionPrompter 生产实现）
     */
    @Autowired
    public InteractiveHandler(@Autowired(required = false) PermissionPrompter prompter) {
        this.prompter = prompter != null ? prompter : new NoOpPrompter();
    }

    /** 无 prompter bean 时的占位（prompt 调用即失败 · 显式暴露接线缺失而非静默放行）。 */
    private static final class NoOpPrompter implements PermissionPrompter {
        @Override
        public com.nexusai.application.agent.permission.PermissionResult prompt(
                Tool tool, JsonNode input, PermissionDecisionReason reason,
                ToolUseContext ctx, String requestId) {
            throw new IllegalStateException(
                "InteractiveHandler.prompter 未接线 (无 WebSocketPermissionPrompter bean)");
        }
    }

    /**
     * interactive 分支主入口 · 弹窗 + 同步阻塞等用户响应。
     *
     * <p>对齐 CC {@code handleInteractivePermission}（interactiveHandler.ts:57-232）：
     * queue push + callbacks 注册 → 用户响应 → resolve。Java 同步模型中：
     * <ul>
     *   <li>queue push = {@link PermissionPrompter#prompt} 内部 STOMP 推送</li>
     *   <li>同步等待 = prompt 内 queue+回调+四路竞速 + createResolveOnce.claim 原子守卫，
     *       首个 racer claim 即返回（自动化决策 ms 级；30s 超时仅纯用户决策 floor）</li>
     * </ul>
     *
     * @param tool      工具实例
     * @param input     工具输入
     * @param reason    弹窗原因（决策归因）
     * @param ctx       工具调用上下文
     * @param requestId 请求 ID（= ToolUseBlock.id）
     * @return 用户决策（Allow / Deny）
     */
    public com.nexusai.application.agent.permission.PermissionResult awaitUserDecision(
            Tool tool, JsonNode input, PermissionDecisionReason reason,
            ToolUseContext ctx, String requestId) {
        return awaitUserDecision(tool, input, reason, ctx, requestId, PermissionPromptDetails.none());
    }

    /**
     * 带展示细节的 interactive 分支入口 · 透传 description/suggestions/blockedPath
     * 到弹窗（对齐 CC useCanUseTool.tsx:56-60 + interactiveHandler.ts:250-253）。
     */
    public com.nexusai.application.agent.permission.PermissionResult awaitUserDecision(
            Tool tool, JsonNode input, PermissionDecisionReason reason,
            ToolUseContext ctx, String requestId, PermissionPromptDetails details) {
        if (prompter == null) {
            // 生产路径（ToolPermissionGate 默认构造）恒非 null；手工 new 未接线时显式失败
            throw new IllegalStateException(
                "InteractiveHandler.prompter 未接线 (awaitUserDecision 需要 PermissionPrompter)");
        }
        // 数据流日志（S13 r1）: 交互决策入口 —— 工具/归因/请求 ID；details 非 none 时附展示摘要
        if (log.isDebugEnabled()) {
            String detailsSummary = (details == null || details.description() == null
                    && details.suggestions().isEmpty() && details.blockedPath() == null)
                ? "none"
                : "desc=" + details.description() + ",suggestions=" + details.suggestions().size()
                    + ",blockedPath=" + details.blockedPath();
            log.debug("awaitUserDecision: tool={} reason={} requestId={} details={}",
                tool.name(), reason, requestId, detailsSummary);
        }
        // 同步阻塞等用户决策（prompter 内部 queue+回调+四路竞速）
        com.nexusai.application.agent.permission.PermissionResult result =
            prompter.prompt(tool, input, reason, ctx, requestId, details);
        // 数据流日志（S13 r1）: 记录弹窗决策结果（Allow/Deny/Ask/Passthrough 四种 behavior，
        // 口径对齐 WebSocketPermissionPrompter:398 PERMISSION response 的 getSimpleName 决策日志）
        if (log.isDebugEnabled()) {
            log.debug("awaitUserDecision 决策: requestId={} decision={}",
                requestId, result.getClass().getSimpleName());
        }
        return result;
    }
}

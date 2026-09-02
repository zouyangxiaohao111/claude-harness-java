package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;

/**
 * 权限询问器 · 对齐 CC {@code Tool.ts:381-385 canUseTool} callback。
 *
 * <h2>职责</h2>
 * <p>当 {@link PermissionPipeline#check} 返回 {@link PermissionResult.Ask} 时，由
 * {@link com.nexusai.application.agent.LlmAgentLoop} 调用本接口"问用户"。调用方阻塞
 * 直到用户响应（前端弹窗 Y/N）/ 超时 / 取消，然后返回 {@link PermissionResult.Allow}
 * 或 {@link PermissionResult.Deny}。
 *
 * <h2>异步阻塞契约</h2>
 * <ul>
 *   <li><b>阻塞</b>：{@link #prompt} 在用户响应前不返回 —— 调用方在它返回前不会继续</li>
 *   <li><b>幂等</b>：同一 {@code requestId} 多次响应只生效一次（后到的忽略）</li>
 *   <li><b>超时</b>：实现方负责超时降级（默认 30s），超时返回 {@link PermissionResult.Deny}</li>
 *   <li><b>取消</b>：线程 interrupt 时返回 {@link PermissionResult.Deny}（不让 loop 卡死）</li>
 * </ul>
 *
 * <h2>Phase 1 唯一实现</h2>
 * <p>{@link WebSocketPermissionPrompter} —— 通过 STOMP 推送前端弹窗。Phase 2 起会
 * 加 CLI 实现（{@code System.console().readLine} —— 用于无浏览器场景），但 PR 4 范围
 * <b>明确不实现 CLI</b>，仅 STOMP。
 *
 * <h2>为什么是阻塞接口</h2>
 * <p>对齐 CC {@code canUseTool} 的 async/await 语义 —— 上层 {@code query.ts} 调
 * {@code canUseTool} 时 {@code await}，直到 Promise resolve 才继续。本接口通过阻塞
 * + CompletableFuture 实现等价语义，但保持 Java 同步接口风格。
 *
 * @see WebSocketPermissionPrompter
 * @see PermissionResult
 * @see PermissionDecisionReason
 */
public interface PermissionPrompter {

    /**
     * 询问用户。
     *
     * <p>调用流程：
     * <ol>
     *   <li>实现方通过 STOMP 推送弹窗事件到前端（{@code requestId} 关联）</li>
     *   <li>调用方线程阻塞</li>
     *   <li>前端用户点 Y/N → STOMP 回传 → 实现方完成对应 future → 本方法返回</li>
     *   <li>若超时 / 中断 / 错误 → 返回 {@link PermissionResult.Deny}</li>
     * </ol>
     *
     * @param tool        工具实例（用于弹窗显示工具名）
     * @param input       已解析 JSON 输入（用于弹窗显示参数）
     * @param reason      决策归因（{@link PermissionDecisionReason} —— 弹窗显示"为什么问"）
     * @param ctx         工具调用上下文（含 {@code sessionId} 用于 STOMP topic）
     * @param requestId   唯一请求 ID（关联 STOMP 响应；通常 = {@code ToolUseBlock.id}）
     * @return 用户决策（{@link PermissionResult.Allow} / {@link PermissionResult.Deny}）。
     *         永不返回 null。
     * @throws NullPointerException 若必填参数为 null
     */
    PermissionResult prompt(Tool tool,
                            JsonNode input,
                            PermissionDecisionReason reason,
                            ToolUseContext ctx,
                            String requestId);

    /**
     * [canUseTool v2] 带展示细节的弹窗询问 · 对齐 CC useCanUseTool.tsx:56-60
     * {@code await tool.description(input, ...)} + interactiveHandler.ts:250-253
     * （description / suggestions / blockedPath 进弹窗）。
     *
     * <p>默认实现委托 5 参版本（details=null）—— 旧实现 / 测试桩无需改动。
     *
     * @param tool        工具实例（用于弹窗显示工具名）
     * @param input       已解析 JSON 输入（用于弹窗显示参数）
     * @param reason      决策归因（{@link PermissionDecisionReason} —— 弹窗显示"为什么问"）
     * @param ctx         工具调用上下文（含 {@code sessionId} 用于 STOMP topic）
     * @param requestId   唯一请求 ID（关联 STOMP 响应；通常 = {@code ToolUseBlock.id}）
     * @param details     弹窗展示细节（description / suggestions / blockedPath；可为 null）
     * @return 用户决策（{@link PermissionResult.Allow} / {@link PermissionResult.Deny}）。
     *         永不返回 null。
     */
    default PermissionResult prompt(Tool tool,
                                    JsonNode input,
                                    PermissionDecisionReason reason,
                                    ToolUseContext ctx,
                                    String requestId,
                                    PermissionPromptDetails details) {
        return prompt(tool, input, reason, ctx, requestId);
    }
}

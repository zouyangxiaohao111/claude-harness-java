package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ToolResult 应用器 · 严格对齐 CC SkillTool.ts:775-860 call() 返回协议.
 *
 * <p><b>A1 退役 ExtendedToolResult 后</b>, CC 的 newMessages + contextModifier + structuredOutput
 * 已折入 {@link ToolResult} 本身 (Tool.ts:323/330/331-335). 本类不再用 instanceof
 * ExtendedToolResult 分流, 对每个 {@link ToolResult} 直接应用其载荷.
 * <p>应用协议 (对齐 CC SkillTool.ts:775-860 + toolExecution.ts:1272-1279):
 * <ol>
 *   <li>追加 {@link ToolResult#newMessages()} 到 messages (跨 turn 持久, SkillTool 用)</li>
 *   <li>结构化输出 {@link ToolResult#presentationMeta(ToolResult)} → {@link AgentState#recordStructuredOutput}
 *       (b14 本地暂存, 稍后由 LlmAgentLoop toolResultMessage 挂到 tool_result)</li>
 *   <li>[IT-6] 同上条件 → {@link AttachmentMessageDto#structuredOutput} attachment 追加到
 *       {@code state.attachments()} (对齐 CC toolExecution.ts:1272-1279
 *       createAttachmentMessage({type:'structured_output', data}); 不进 LLM)</li>
 *   <li>返回原 ToolResult (无 base() 解包, ExtendedToolResult 已退役)</li>
 * </ol>
 *
 * <p><b>[IMP-C2] toolUseId 参数透传</b>（组 2-1 拍板）: ToolResult record 已删除 toolUseId
 * 字段（对齐 CC ToolResult 4 字段契约），AgentState.recordStructuredOutput 以 toolUseId 为键
 * （AgentLoopContext 经 state.takeStructuredOutput(toolUseId) 配对），故本应用器由调用方
 * （StreamingToolExecutor 持有 t.call.id()）显式传入 toolUseId，不再从 result 读取。
 *
 * <p><b>contextModifier 不在此应用</b>: CC contextModifier 签名 {@code (ctx: ToolUseContext) => ToolUseContext}
 * (Tool.ts:330), 需要 ToolUseContext; 而 stateObj 是 AgentState. contextModifier 应用属 ctx-aware 路径
 * (StreamingToolExecutor deferred · StreamingToolExecutor.java:1500-1514 按 toolUseId 入队真实 apply)。
 * SkillTool inline 经 {@code successWithNewMessagesWithContextModifier} 传真实三件套；其余工厂不携带。
 */
public final class ToolResultApplier {

    private static final Logger log = LoggerFactory.getLogger(ToolResultApplier.class);

    private ToolResultApplier() {
        // 工具类
    }

    /**
     * 应用 ToolResult 的 newMessages + structuredOutput 载荷.
     *
     * @param result   工具执行结果 (AgentToolResult&lt;?&gt;; 退役 ExtendedToolResult 后恒为 ToolResult)
     * @param messages 当前 state.messages (会追加 newMessages)
     * @param stateObj 当前 state (AgentState; 透传 recordStructuredOutput +
     *                 appendAttachment structured_output attachment, IT-6)
     * @param toolUseId 工具调用 ID（[IMP-C2] mapper 参数透传, 由调用方从调用块推导）
     * @return 处理后的 ToolResult (调用方继续追加 tool_result 消息)
     */
    public static ToolResult<?> apply(AgentToolResult<?> result, List<ChatMessageDto> messages,
                                      Object stateObj, String toolUseId) {
        if (result == null) return null;
        if (!(result instanceof ToolResult<?> tr)) {
            // 防御: sealed permits 只剩 ToolResult, 理论不可达
            return null;
        }
        // 1. 追加 newMessages (CC SkillTool.ts:735-860 call() 返回 newMessages)
        if (tr.newMessages() != null && !tr.newMessages().isEmpty()) {
            messages.addAll(tr.newMessages());
        }
        // 2. structuredOutput → AgentState.recordStructuredOutput (b14 本地暂存, Java 偏离通道)
        //    + structured_output attachment (IT-6 · 对齐 CC toolExecution.ts:1272-1279
        //    createAttachmentMessage({type:'structured_output', data}) — 独立附件消息进 transcript,
        //    不进 LLM (CC normalizeAttachmentForAPI structured_output→[], messages.ts:4258-4261))
        //    [IMP-C2] structuredOutput 已折入 data (Map)，经 presentationMeta 读取。
        Map<String, Object> structuredOutput = ToolResult.presentationMeta(tr);
        if (structuredOutput != null && !structuredOutput.isEmpty()
                && stateObj instanceof AgentState state) {
            state.appendAttachment(AttachmentMessageDto.structuredOutput(structuredOutput));
            state.recordStructuredOutput(toolUseId, structuredOutput);
            if (log.isDebugEnabled()) {
                log.debug("结构化工具输出已暂存: toolUseId={} 字段数={}",
                    toolUseId, structuredOutput.size());
            }
        }
        // 3. contextModifier 应用属 ctx-aware 路径 (StreamingToolExecutor deferred); 当前恒 null, 不在此应用
        Function<ToolUseContext, ToolUseContext> mod = tr.contextModifier();
        if (mod != null && log.isDebugEnabled()) {
            log.debug("ToolResult.contextModifier 非空但 ToolResultApplier 不应用 (需 ToolUseContext, "
                + "应由 StreamingToolExecutor deferred 路径处理): toolUseId={}", toolUseId);
        }
        return tr;
    }
}

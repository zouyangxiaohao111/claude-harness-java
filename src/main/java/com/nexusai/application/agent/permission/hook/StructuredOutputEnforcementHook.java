package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * H7-arch Phase 3: StructuredOutput 强制 Stop hook · 对齐 CC
 * {@code registerStructuredOutputEnforcement} (hookHelpers.ts:70-83) +
 * {@code hasSuccessfulToolCall} (messages.ts:4719-4760).
 *
 * <p>WHY: CC {@code execAgentHook.ts:157-160} 注册 session Stop hook，LLM 停止时若未成功调用
 * StructuredOutput，注入强制提示重入 loop。Java 端等价：注册 GenericHook 监听 STOP 事件，
 * 按 hookAgentId 自过滤（隔离父循环），检查 {@link #hasSuccessfulToolCall}，未成功则返回
 * blockingError 触发 LlmAgentLoop stop 段重入（注入 user message + markNeedsFollowUp，
 * 当前实现 LlmAgentLoop.java ~:3490-3518），给 LLM 再次调用 StructuredOutput 的机会。
 *
 * <p><b>CC 真源（已实读，非抄注释）</b>:
 * <ul>
 *   <li>{@code addFunctionHook(setAppState, hookAgentId, 'Stop', '', callback, errorMsg, {timeout:5000})}
 *       -- 按 <b>hookAgentId</b> 注册（非 sessionId），与父循环隔离</li>
 *   <li>{@code callback = messages => hasSuccessfulToolCall(messages, SYNTHETIC_OUTPUT_TOOL_NAME)}
 *       -- 返回 true=已成功调用，false=未调用/失败</li>
 *   <li>{@code errorMsg = "You MUST call the StructuredOutput tool to complete this request. Call this tool now."}
 *       -- callback 返回 false 时注入此文案重入</li>
 * </ul>
 *
 * <p><b>Java 适配</b>:
 * <ul>
 *   <li>本 hook 在 {@link #onEvent(HookEvent)} 内按 {@code event.agentId()} 比对
 *       hookAgentId 自过滤（隔离父循环与其他并发 hook agent），注册名含 hookAgentId
 *       保证唯一 + ExecAgentHook finally 注销</li>
 *   <li>{@code hasSuccessfulToolCall} 扫 {@link AgentState#messages()}（镜像 CC messages.ts:4719
 *       反向扫）；不依赖 {@code state.structuredOutputs()}（已验证 loop 后被 takeStructuredOutput 消费）</li>
 *   <li>[IMPL-10] DEL-TH-04: 5s 生命周期上限改为 per-call 超时（CC hookHelpers.ts:81 单次
 *       callback timeout）。<b>无阻断次数上限</b>（对齐 CC hookHelpers.ts:70-83 无 attempt cap）；
 *       loop 重入兜底由 {@link ExecAgentHook#MAX_AGENT_TURNS} 计数器 + 60s 整体超时承担
 *       （execAgentHook.ts:119/:197-207）——原 Java 独有 {@code MAX_BLOCKING_ATTEMPTS} 已删除
 *       （SURPLUS-1，DEL-PROBE 裁决：CC 无此能力，外层兜底已镜像 CC）</li>
 *   <li>[对抗核验 H13-GAP] 成功判定用 {@link ChatMessageDto#isError()} —— 对齐 CC :4754 is_error !== true。
 *       isError 由 {@code ToolResult.isError} 经 {@code LlmAgentLoop.toolResultMessage} 透传
 *       （SyntheticOutputTool 成功 → ToolResult.successWithStructuredOutput → isError=false；
 *        schema 失败 → ToolResult.error → isError=true）。旧实现用 content=={@link #SUCCESS_CONTENT}
 *       文案判定，工具失败返回同文案时误判。</li>
 * </ul>
 *
 * @see GenericHook
 * @see HookEventType#STOP
 */
public class StructuredOutputEnforcementHook implements GenericHook {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputEnforcementHook.class);

    /**
     * CC hookHelpers.ts:80 强制提示文案 · callback 返回 false 时注入 LLM 重入 loop。
     *
     * <p>CC original: {@code "You MUST call the ${SYNTHETIC_OUTPUT_TOOL_NAME} tool to complete
     * this request. Call this tool now."}
     */
    static final String ENFORCEMENT_PROMPT =
        "You MUST call the " + ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME
            + " tool to complete this request. Call this tool now.";

    /**
     * CC SyntheticOutputTool.ts:62 成功调用返回的 data 文案 · 用于判定 tool_result 是否成功。
     *
     * <p>CC original: {@code data: 'Structured output provided successfully'}。
     */
    static final String SUCCESS_CONTENT = "Structured output provided successfully";

    /**
     * [IMPL-10] DEL-TH-04: per-call 超时 5s · 对齐 CC hookHelpers.ts:81 {@code {timeout: 5000}}。
     *
     * <p>CC 真源: hookHelpers.ts:81 的 5s 是 {@code addFunctionHook(..., {timeout: 5000})}
     * 的 <b>单次 callback 调用超时</b>（executeFunctionHook hooks.ts:4757 对每次调用施加
     * timeout 竞速），非注册后生命周期上限。Java 端 callback (hasSuccessfulToolCall) 是
     * 同步扫描，按单次调用耗时测量：超过 5s 视为超时返回 proceed（CC timeout → cancelled
     * 语义的同步适配）。
     */
    static final long CALL_TIMEOUT_MS = 5000L;

    private final UUID hookAgentId;
    private final AgentState state;

    /**
     * @param hookAgentId 本 hook agent 的唯一 ID（CC asAgentId('hook-agent-${UUID}')），用于 STOP 事件自过滤
     * @param state       hook agent 的 AgentState，onEvent 时读 messages 做成功判定
     */
    public StructuredOutputEnforcementHook(UUID hookAgentId, AgentState state) {
        this.hookAgentId = hookAgentId;
        this.state = state;
    }

    @Override
    public HookResult onEvent(HookEvent event) {
        // 自过滤 1: 仅处理 STOP 事件
        if (event.type() != HookEventType.STOP) {
            return HookResult.proceed();
        }
        // 自过滤 2: 仅处理本 hook agent 的 STOP（隔离父循环与其他并发 hook agent）
        if (event.agentId() == null || !event.agentId().equals(hookAgentId.toString())) {
            return HookResult.proceed();
        }
        // [IMPL-10] DEL-TH-04: per-call 超时测量（CC hookHelpers.ts:81 timeout:5000 对单次
        //   callback 调用施加；Java callback 同步执行，按调用耗时判定）。
        long callStartNs = System.nanoTime();
        boolean success = hasSuccessfulToolCall(state.messages(), ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
        long callElapsedMs = (System.nanoTime() - callStartNs) / 1_000_000L;
        if (callElapsedMs > CALL_TIMEOUT_MS) {
            if (log.isWarnEnabled()) {
                log.warn("StructuredOutputEnforcementHook: hookAgent={} 单次检查超时 {}ms (>{}ms), 按超时放行",
                    hookAgentId, callElapsedMs, CALL_TIMEOUT_MS);
            }
            return HookResult.proceed();
        }
        if (success) {
            if (log.isDebugEnabled()) {
                log.debug("StructuredOutputEnforcementHook: hookAgent={} 已成功调用 StructuredOutput, 放行 STOP", hookAgentId);
            }
            return HookResult.proceed();
        }
        // 无阻断次数上限（对齐 CC hookHelpers.ts:70-83）: 未成功调用即 blockingError 触发 loop stop 段重入
        // （注入强制提示 + markNeedsFollowUp）。loop 重入有界性由外层 ExecAgentHook.MAX_AGENT_TURNS=50 计数器
        // + 60s 整体超时兜底（execAgentHook.ts:197-207）——本 hook 自身不得早退放行。
        if (log.isInfoEnabled()) {
            log.info("StructuredOutputEnforcementHook: hookAgent={} 未调用 StructuredOutput, 注入强制提示重入 loop", hookAgentId);
        }
        return HookResult.stop(ENFORCEMENT_PROMPT, ENFORCEMENT_PROMPT);
    }

    /**
     * 检查最近一次 {@code toolName} tool_use 是否有成功的 tool_result · 镜像 CC
     * {@code messages.ts:4719-4760 hasSuccessfulToolCall}.
     *
     * <p>CC 真源: 反向扫 assistant.message.content 找 tool_use(name 匹配) -> 取 id ->
     * 反向扫 user.message.content 找 tool_result(tool_use_id 匹配) -> {@code is_error !== true} 即成功。
     *
     * <p>Java 适配: ChatMessageDto tool result 无 isError 字段 -> 用 content 判定成功
     * （= {@link #SUCCESS_CONTENT}）。
     *
     * @param messages 消息列表（AgentState.messages()）
     * @param toolName 工具名（StructuredOutput）
     * @return true = 最近一次该工具调用成功；false = 未调用 / 无 result / 失败
     */
    static boolean hasSuccessfulToolCall(List<ChatMessageDto> messages, String toolName) {
        if (messages == null || messages.isEmpty()) return false;

        // 1. 反向找最近一次 toolName tool_use（CC messages.ts:4725-4738）
        String mostRecentToolUseId = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = messages.get(i);
            if (msg == null || msg.role() != Role.assistant) continue;
            List<ToolCallDto> toolCalls = msg.toolCalls();
            if (toolCalls == null) continue;
            for (ToolCallDto tc : toolCalls) {
                if (tc != null && toolName.equals(tc.name())) {
                    mostRecentToolUseId = tc.id();
                    break;
                }
            }
            if (mostRecentToolUseId != null) break;
        }
        if (mostRecentToolUseId == null) return false; // 未调用 -> false（CC :4740）

        // 2. 反向找对应 tool_result（CC messages.ts:4743-4757）
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto msg = messages.get(i);
            if (msg == null || msg.role() != Role.tool) continue;
            if (mostRecentToolUseId.equals(msg.toolCallId())) {
                // [对抗核验 H13-GAP] 成功判定改用 isError 字段 —— 对齐 CC :4754 is_error !== true。
                // 旧实现用 content == SUCCESS_CONTENT 文案判定: 工具失败路径若返回与成功文案相同的
                // content（但 isError=true）会误判成功。isError 由 ToolResult.isError 经
                // LlmAgentLoop.toolResultMessage 透传（ToolResult.isError=false = 成功）。
                return !msg.isError();
            }
        }
        // 调用了但无 result（CC :4759 "shouldn't happen in practice"）-> 视为未完成 -> false
        return false;
    }
}

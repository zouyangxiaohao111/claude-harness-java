package com.nexusai.application.agent.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.Map;

/**
 * Post-sampling hook 上下文 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/postSamplingHooks.ts:11-18}
 * {@code REPLHookContext} (L11-18):
 * <pre>
 *   export type REPLHookContext = {
 *     messages: Message[]                    // 全量消息历史 (含 assistant 响应)
 *     systemPrompt: SystemPrompt
 *     userContext: { [k: string]: string }
 *     systemContext: { [k: string]: string }
 *     toolUseContext: ToolUseContext
 *     querySource?: QuerySource
 *   }
 * </pre>
 *
 * <p>WHY (规则三): CC post-sampling hook 收到 REPLHookContext 全量上下文 — messages /
 * systemPrompt / userContext / systemContext / toolUseContext / querySource.
 * Java 端用 record 承载等价契约, 供 {@link PostSamplingHookRegistry} 执行时透传给每个
 * PostSamplingHook, 让 hook (如 SkillImprovementHook) 能读取消息历史、门控 querySource、
 * 写回 appState.
 *
 * <p><b>字段来源对照</b>:
 * <ul>
 *   <li>{@code messages} — CC original: messages (postSamplingHooks.ts:12), 全量消息历史</li>
 *   <li>{@code systemPrompt} — CC original: systemPrompt (postSamplingHooks.ts:13),
 *       <b>[IMP-HOOKS-S7 D3]</b> {@code SystemPrompt = readonly string[]}（systemPromptType.ts:8-14）
 *       —— 段数组（含 boundary 独立元素；CC buildEffectiveSystemPrompt systemPrompt.ts:115-122
 *       + api.ts:321-435 splitSysPromptPrefix 发送时才滤 boundary）。Java 端由
 *       LlmAgentLoop s10 组装链的 {@code fullSystemPrompt}（:3179-3182 appendSystemContext 产物）
 *       透传，与 query.ts:1001-1008 传 {@code systemPrompt} 段数组同构。旧 String 单值
 *       表达（RunRequest.systemPrompt 自定义提示）为偏离，已删除。</li>
 *   <li>{@code userContext} — CC original: userContext (postSamplingHooks.ts:14), 可空</li>
 *   <li>{@code systemContext} — CC original: systemContext (postSamplingHooks.ts:15), 可空</li>
 *   <li>{@code toolUseContext} — CC original: toolUseContext (postSamplingHooks.ts:16), 可空
 *       (测试/非工具场景)</li>
 *   <li>{@code querySource} — CC original: querySource (postSamplingHooks.ts:17), 可空</li>
 * </ul>
 *
 * <p><b>[IMP-HOOKS-S7 D8 登记]</b> {@code toolUseContext}：CC 类型必填（REPLHookContext），但
 * Java 生产 loop 多处 null 守卫实证（LlmAgentLoop:2472/2516/2629 等）—— 生产可 null，
 * hook 需自判空（SkillImprovementHook 等已判）。保持 null 容忍 + 本注释登记，不强行非空。
 *
 * @param messages       CC original: messages (postSamplingHooks.ts:12)
 * @param systemPrompt   CC original: systemPrompt (postSamplingHooks.ts:13 · SystemPrompt 段数组)
 * @param userContext    CC original: userContext (postSamplingHooks.ts:14)
 * @param systemContext  CC original: systemContext (postSamplingHooks.ts:15)
 * @param toolUseContext CC original: toolUseContext (postSamplingHooks.ts:16)
 * @param querySource    CC original: querySource (postSamplingHooks.ts:17)
 * @see PostSamplingHookRegistry
 * @since Session H12
 */
public record PostSamplingContext(
        List<ChatMessageDto> messages,
        List<String> systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext,
        ToolUseContext toolUseContext,
        QuerySource querySource
) {

    public PostSamplingContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        systemPrompt = systemPrompt == null ? List.of() : List.copyOf(systemPrompt);
        userContext = userContext == null ? Map.of() : Map.copyOf(userContext);
        systemContext = systemContext == null ? Map.of() : Map.copyOf(systemContext);
    }
}

package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.Map;

/**
 * fork 缓存共享参数 · 对齐 CC {@code CacheSafeParams}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:57-68)。
 *
 * <p><b>WHY 存在</b>: Anthropic API 的 prompt cache key 由 system prompt / tools / model /
 * messages(prefix) / thinking config 组成。fork 要与主线程共享缓存，这 5 项必须与父请求
 * 完全一致 —— {@code CacheSafeParams} 承载前 5 项（forkedAgent.ts:46-56 注释）。
 *
 * <p><b>systemPrompt 数组语义（RES-R4）</b>: CC 的 {@code systemPrompt: SystemPrompt} 是
 * <b>字符串数组</b>（forkedAgent.ts:59），与主线程<b>发送前</b>数组字节一致（含 boundary 元素），
 * 到发送边界才由 {@code splitSysPromptPrefix} 剥离（api.ts:363-398）。本字段用 {@link List}
 * 表达数组（Java 对 CC branded 数组的直接表示）；<b>不再</b>在构建器里无条件
 * {@code String.join("\n\n", ...)} 扁平化（旧实现丢失 boundary 元素）。
 *
 * <p><b>useGlobalCacheScope（RES-R4 · Java 侧通信通道）</b>: 主线程发送边界用
 * {@code shouldUseGlobalCacheScope()}（betas.ts:227-233，firstParty && !DISABLE_EXPERIMENTAL_BETAS）
 * 决定 split 模式（boundary→global/默认→org）。fork 发送边界必须与主线程<b>同一 gate 判定</b>
 * （REQ-R4-3），否则 cacheScope 分配不一致 → cache key 前缀字节不同 → 缓存永不命中。
 * CC 为进程级全局函数，fork 查询直接调用；Java 由调用方（LlmAgentLoop auto /
 * CompactCommand manual）在构建时求值<b>注入</b>本字段，保证 fork 与主线程 gate 一致
 * （CC original: {@code shouldUseGlobalCacheScope()} (Open-ClaudeCode/src/utils/betas.ts:227-233)）。
 *
 * <p><b>thinking 派生（INV-7）</b>: thinking config <b>不是</b>本 record 的第 6 字段，
 * 由继承的 {@link #toolUseContext()} options.thinkingConfig 派生。若 fork 设置了
 * maxOutputTokens，claude.ts 会经 {@code Math.min(budget, maxOutputTokens-1)} 改变
 * budget_tokens → thinking config 偏移 → 破坏主线程 cache key（forkedAgent.ts:46-56，
 * compact.ts:1181-1187）。因此 fork 路径 <b>禁止设 maxOutputTokens</b>。
 *
 * @param systemPrompt        主线程 system prompt <b>发送前数组</b>（含 boundary 元素；
 *                             发送边界才剥离）· CC original:
 *                             {@code systemPrompt: SystemPrompt} (forkedAgent.ts:59)
 * @param userContext         user context（前置于 messages，影响 cache）· CC original:
 *                            {@code userContext: { [k: string]: string }} (forkedAgent.ts:61)
 * @param systemContext       system context（追加到 system prompt，影响 cache）· CC original:
 *                            {@code systemContext: { [k: string]: string }} (forkedAgent.ts:63)
 * @param toolUseContext      工具使用上下文（tools/model/其他 options + 权限继承）· CC original:
 *                            {@code toolUseContext: ToolUseContext} (forkedAgent.ts:65)
 * @param forkContextMessages 主线程消息前缀（cache prefix 复用）· CC original:
 *                            {@code forkContextMessages: Message[]} (forkedAgent.ts:67)
 * @param useGlobalCacheScope boundary/gate 判定值（fork 与主线程一致）· CC original:
 *                            {@code shouldUseGlobalCacheScope()} (utils/betas.ts:227-233)，
 *                            Java 由调用方注入
 */
public record CacheSafeParams(
        List<String> systemPrompt,
        Map<String, String> userContext,
        Map<String, String> systemContext,
        ToolUseContext toolUseContext,
        List<ChatMessageDto> forkContextMessages,
        boolean useGlobalCacheScope) {

    /**
     * 紧凑构造器 · null 兜底（对齐 CC createCacheSafeParams 从不产 null，
     * forkedAgent.ts:131-141；此处防御性兜底避免 NPE）。
     */
    public CacheSafeParams {
        if (systemPrompt == null) {
            systemPrompt = List.of();
        }
        if (userContext == null) {
            userContext = Map.of();
        }
        if (systemContext == null) {
            systemContext = Map.of();
        }
        if (forkContextMessages == null) {
            forkContextMessages = List.of();
        }
        // toolUseContext 不兜底 —— runForkedAgent 必须用它创建隔离上下文（继承权限）
        if (toolUseContext == null) {
            throw new IllegalArgumentException("CacheSafeParams.toolUseContext is null");
        }
    }

    /**
     * 5 参便捷构造器 · gate 默认 {@code false}（3P 默认场景；firstParty/boundary 场景须用
     * 6 参构造显式注入 gate，见 {@code CacheSharingParamsBuilder.build(..., boolean)}）。
     */
    public CacheSafeParams(
            List<String> systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            ToolUseContext toolUseContext,
            List<ChatMessageDto> forkContextMessages) {
        this(systemPrompt, userContext, systemContext, toolUseContext, forkContextMessages, false);
    }
}

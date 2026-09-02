package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 系统提示拆分器 · 对齐 CC {@code splitSysPromptPrefix}
 * （CC original: {@code splitSysPromptPrefix(systemPrompt, options?: {skipGlobalCacheForSystemPrompt?: boolean})}
 * (Open-ClaudeCode/src/utils/api.ts:321-435)）。
 *
 * <p>发送边界组件（IMP-SP-06）· 把 SystemPrompt 数组按 boundary 拆分为若干
 * {@link SystemPromptBlock}，每个 block 携带独立缓存作用域（I-8/I-9）。三种模式：
 * <ol>
 *   <li><b>skipGlobalCache 模式</b>（{@code useGlobalCacheFeature && skipGlobalCacheForSystemPrompt}，
 *       api.ts:327-364，MCP tools 存在时）：滤掉 boundary 本身；attribution header → cacheScope
 *       null、CLI prefix → org、其余 → org。最多 3 block。</li>
 *   <li><b>boundary 命中模式</b>（{@code useGlobalCacheFeature && boundary 存在}，api.ts:368-397）：
 *       attribution → null、CLI prefix → null、boundary 前静态 → global、boundary 后动态 → null。
 *       最多 4 block（claude.ts:3214-3216 注释：超过 4 block 触发 API 400）。</li>
 *   <li><b>默认模式</b>（api.ts:412-435，3P provider 或 boundary 缺失）：attribution → null、
 *       CLI prefix → org、其余 → org。最多 3 block。</li>
 * </ol>
 *
 * <p><b>boundary 剥离（I-8）</b>: 所有模式中 {@code __SYSTEM_PROMPT_DYNAMIC_BOUNDARY__} 本身
 * <b>不发送</b>（跳过），故输出 block 不含 boundary 字符串。
 *
 * <p>attribution/prefix 识别（OPD-SP-29 保留分支）：{@code startsWith('x-anthropic-billing-header')}
 * 判 attribution；内容等于 {@link #CLI_SYSPROMPT_PREFIXES} 判 CLI prefix。
 */
public final class SystemPromptSplitter {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSplitter.class);

    /**
     * CLI 系统提示前缀集合 · CC original: {@code CLI_SYSPROMPT_PREFIXES}
     * (Open-ClaudeCode/src/constants/system.ts:26-28)。
     *
     * <p>按内容而非位置识别 prefix block（system.ts:24-25 注释）：
     * <ul>
     *   <li>DEFAULT_PREFIX = "You are Claude Code, Anthropic's official CLI for Claude."</li>
     *   <li>AGENT_SDK_CLAUDE_CODE_PRESET_PREFIX = "...running within the Claude Agent SDK."</li>
     *   <li>AGENT_SDK_PREFIX = "You are a Claude agent, built on Anthropic's Claude Agent SDK."</li>
     * </ul>
     */
    public static final Set<String> CLI_SYSPROMPT_PREFIXES = Set.of(
        "You are Claude Code, Anthropic's official CLI for Claude.",
        "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.",
        "You are a Claude agent, built on Anthropic's Claude Agent SDK.");

    /** Attribution header 前缀 · CC original: api.ts:340/377/419 {@code block.startsWith('x-anthropic-billing-header')}。 */
    public static final String ATTRIBUTION_HEADER_PREFIX = "x-anthropic-billing-header";

    private SystemPromptSplitter() {}

    /**
     * 拆分系统提示 · 对齐 CC {@code splitSysPromptPrefix}（api.ts:321-435）。
     *
     * @param systemPrompt                系统提示数组（有序，boundary 是独立数组元素）
     * @param useGlobalCacheFeature       {@code shouldUseGlobalCacheScope()} 判定结果 · CC original:
     *                                    betas.ts:227-233（firstParty && !DISABLE_EXPERIMENTAL_BETAS）；
     *                                    Java 由调用方注入（SystemPromptAssembler.globalCacheScopeGate）
     * @param skipGlobalCacheForSystemPrompt  MCP tools 存在时置 true（global cache 不作用于 system prompt）·
     *                                        CC original: options.skipGlobalCacheForSystemPrompt (api.ts:323)
     * @return 拆分后的 block 列表（boundary 已剥离；attribution block cacheScope=null）
     */
    public static List<SystemPromptBlock> splitSysPromptPrefix(
            List<String> systemPrompt,
            boolean useGlobalCacheFeature,
            boolean skipGlobalCacheForSystemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return List.of();
        }
        // 模式 1：skipGlobalCache（api.ts:327-364）
        if (useGlobalCacheFeature && skipGlobalCacheForSystemPrompt) {
            return skipGlobalCacheMode(systemPrompt);
        }
        // 模式 2：boundary 命中（api.ts:368-397）
        if (useGlobalCacheFeature) {
            int boundaryIndex = indexOfBoundary(systemPrompt);
            if (boundaryIndex != -1) {
                return boundaryMode(systemPrompt, boundaryIndex);
            }
            if (log.isDebugEnabled()) {
                log.debug("[SystemPromptSplitter] boundary 缺失（tengu_sysprompt_missing_boundary_marker）："
                    + "{} 个 block，落默认模式 · CC api.ts:399-402", systemPrompt.size());
            }
        }
        // 模式 3：默认（api.ts:412-435）
        return defaultMode(systemPrompt);
    }

    // ── 三种模式（一一对应 CC 分支） ──

    /** 模式 1 · CC api.ts:327-364。 */
    private static List<SystemPromptBlock> skipGlobalCacheMode(List<String> systemPrompt) {
        String attributionHeader = null;
        String systemPromptPrefix = null;
        List<String> rest = new ArrayList<>();
        for (String prompt : systemPrompt) {
            if (prompt == null || prompt.isEmpty()) continue;   // SP-01 △2 · CC api.ts:337 !prompt（空串 falsy 剔除）
            if (prompt.equals(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY)) continue; // 跳过 boundary
            if (prompt.startsWith(ATTRIBUTION_HEADER_PREFIX)) {
                attributionHeader = prompt;
            } else if (CLI_SYSPROMPT_PREFIXES.contains(prompt)) {
                systemPromptPrefix = prompt;
            } else {
                rest.add(prompt);
            }
        }
        List<SystemPromptBlock> result = new ArrayList<>();
        if (attributionHeader != null) {
            result.add(new SystemPromptBlock(attributionHeader, CacheScope.NULL));
        }
        if (systemPromptPrefix != null) {
            result.add(new SystemPromptBlock(systemPromptPrefix, CacheScope.ORG));
        }
        String restJoined = String.join("\n\n", rest);
        if (!restJoined.isEmpty()) {
            result.add(new SystemPromptBlock(restJoined, CacheScope.ORG));
        }
        return result;
    }

    /** 模式 2 · CC api.ts:368-397。 */
    private static List<SystemPromptBlock> boundaryMode(List<String> systemPrompt, int boundaryIndex) {
        String attributionHeader = null;
        String systemPromptPrefix = null;
        List<String> staticBlocks = new ArrayList<>();
        List<String> dynamicBlocks = new ArrayList<>();
        for (int i = 0; i < systemPrompt.size(); i++) {
            String block = systemPrompt.get(i);
            if (block == null || block.isEmpty() || block.equals(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY)) continue;   // SP-01 △2 · CC api.ts:374 !block
            if (block.startsWith(ATTRIBUTION_HEADER_PREFIX)) {
                attributionHeader = block;
            } else if (CLI_SYSPROMPT_PREFIXES.contains(block)) {
                systemPromptPrefix = block;
            } else if (i < boundaryIndex) {
                staticBlocks.add(block);
            } else {
                dynamicBlocks.add(block);
            }
        }
        List<SystemPromptBlock> result = new ArrayList<>();
        if (attributionHeader != null) {
            result.add(new SystemPromptBlock(attributionHeader, CacheScope.NULL));
        }
        if (systemPromptPrefix != null) {
            result.add(new SystemPromptBlock(systemPromptPrefix, CacheScope.NULL));
        }
        String staticJoined = String.join("\n\n", staticBlocks);
        if (!staticJoined.isEmpty()) {
            result.add(new SystemPromptBlock(staticJoined, CacheScope.GLOBAL));
        }
        String dynamicJoined = String.join("\n\n", dynamicBlocks);
        if (!dynamicJoined.isEmpty()) {
            result.add(new SystemPromptBlock(dynamicJoined, CacheScope.NULL));
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSplitter] boundary 命中（tengu_sysprompt_boundary_found）："
                    + "{} block（静态 {} chars / 动态 {} chars）· CC api.ts:387-395",
                result.size(), staticJoined.length(), dynamicJoined.length());
        }
        return result;
    }

    /** 模式 3 · CC api.ts:412-435。 */
    private static List<SystemPromptBlock> defaultMode(List<String> systemPrompt) {
        String attributionHeader = null;
        String systemPromptPrefix = null;
        List<String> rest = new ArrayList<>();
        for (String block : systemPrompt) {
            if (block == null || block.isEmpty()) continue;     // SP-01 △2 · CC api.ts:416 !block
            if (block.startsWith(ATTRIBUTION_HEADER_PREFIX)) {
                attributionHeader = block;
            } else if (CLI_SYSPROMPT_PREFIXES.contains(block)) {
                systemPromptPrefix = block;
            } else {
                rest.add(block);
            }
        }
        List<SystemPromptBlock> result = new ArrayList<>();
        if (attributionHeader != null) {
            result.add(new SystemPromptBlock(attributionHeader, CacheScope.NULL));
        }
        if (systemPromptPrefix != null) {
            result.add(new SystemPromptBlock(systemPromptPrefix, CacheScope.ORG));
        }
        String restJoined = String.join("\n\n", rest);
        if (!restJoined.isEmpty()) {
            result.add(new SystemPromptBlock(restJoined, CacheScope.ORG));
        }
        return result;
    }

    private static int indexOfBoundary(List<String> systemPrompt) {
        for (int i = 0; i < systemPrompt.size(); i++) {
            if (SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY.equals(systemPrompt.get(i))) {
                return i;
            }
        }
        return -1;
    }
}

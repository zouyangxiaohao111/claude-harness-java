package com.nexusai.application.agent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 系统提示块构建器 · 对齐 CC {@code buildSystemPromptBlocks} + {@code getCacheControl}
 * （CC original: {@code buildSystemPromptBlocks(systemPrompt, enablePromptCaching, options?)}
 * (Open-ClaudeCode/src/services/api/claude.ts:3213-3237)；{@code getCacheControl}
 * (claude.ts:358-374)）。
 *
 * <p>发送边界组件（IMP-SP-06）· 把 {@link SystemPromptSplitter} 拆出的
 * {@link SystemPromptBlock} 列表映射为 Anthropic API {@code system} 的 text block 数组：
 * <ul>
 *   <li>每 block → {@code {type:'text', text, cache_control?}}（claude.ts:3222-3230）</li>
 *   <li>{@code cache_control} 仅当 {@code enablePromptCaching && cacheScope != null} 时附加
 *       （claude.ts:3225-3229，CacheScope.NULL 的 block 不参与缓存）</li>
 *   <li><b>≤4 block 风险红线</b>（claude.ts:3214-3216 注释）：split 产出最多 4 block，
 *       超过 4 block 会触发 API 400——不得新增 block 类型。</li>
 * </ul>
 *
 * <p><b>getCacheControl ttl 语义（RES-R7，09-open-decisions.md §六 R7）</b>: CC 在
 * {@code scope==='global'} 时输出 {@code {type:'ephemeral', scope:'global'}}，并视
 * {@code should1hCacheTTL(querySource)}（claude.ts:393-431，bedrock 1h 开关 + 用户 1h 资格 +
 * allowlist）追加 {@code ttl:'1h'}。Java 简化（用户拍板）：不做用户资格门控，默认 1h 生效，
 * 由 {@link PromptCachingTtlConfig} 配置 enable/ttl 值；{@code type:'ephemeral'} 恒在，
 * {@code scope} 仅 GLOBAL 时输出，{@code ttl} 仅配置 enable 时输出。
 */
public final class SystemPromptBlocksBuilder {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptBlocksBuilder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private SystemPromptBlocksBuilder() {}

    /**
     * 构建 system text block 数组 · 对齐 CC {@code buildSystemPromptBlocks}（claude.ts:3213-3237）。
     *
     * @param blocks              拆分后的 block 列表（{@link SystemPromptSplitter#splitSysPromptPrefix} 产物）
     * @param enablePromptCaching prompt caching 是否启用 · CC original: enablePromptCaching
     *                             (claude.ts:1375-1376，默认 getPromptCachingEnabled(model))
     * @return Anthropic API {@code system} 数组（{@code type:'text'} block；caching 门控后附 cache_control）
     */
    public static ArrayNode buildSystemPromptBlocks(List<SystemPromptBlock> blocks, boolean enablePromptCaching) {
        ArrayNode arr = JSON.createArrayNode();
        if (blocks == null) {
            return arr;
        }
        for (SystemPromptBlock block : blocks) {
            if (block == null || block.text() == null) continue;
            ObjectNode textBlock = arr.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", block.text());
            if (enablePromptCaching && block.cacheScope() != null && block.cacheScope() != CacheScope.NULL) {
                textBlock.set("cache_control", getCacheControl(block.cacheScope()));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptBlocksBuilder] 构建 system blocks: {} 个 text block，"
                    + "caching={}（≤4 block 红线，claude.ts:3214-3216）",
                arr.size(), enablePromptCaching);
        }
        return arr;
    }

    /**
     * 生成 cache_control · 对齐 CC {@code getCacheControl}（claude.ts:358-374）。
     *
     * <p>默认走当前 {@link PromptCachingTtlConfig#current()} 配置（默认 enable=true, ttl='1h'，
     * RES-R7 用户拍板）；显式 ttl 重载供测试/精确控制。
     *
     * @param scope block 缓存作用域（GLOBAL → 输出 scope:'global'；ORG 仅 type；NULL 不应传此方法）
     * @return {@code {type:'ephemeral', ttl?, scope?}} · ttl 按配置 enable/ttl 输出（默认 '1h'）
     */
    public static ObjectNode getCacheControl(CacheScope scope) {
        return getCacheControl(scope, PromptCachingTtlConfig.current().ttlOrNull());
    }

    /**
     * 生成 cache_control · 对齐 CC {@code getCacheControl({scope, querySource})}（claude.ts:358-374）
     * 的 Java 简化：ttl 作为显式参数（由 {@link PromptCachingTtlConfig#ttlOrNull()} 提供）。
     *
     * @param scope block 缓存作用域（GLOBAL → 输出 scope:'global'；ORG 仅 type；NULL 不应传此方法）
     * @param ttl   缓存 TTL 值（null → 不输出 ttl 字段；'1h'/'5m' 等）· CC original: should1hCacheTTL 真时
     *              {@code ttl:'1h'}（claude.ts:371），Java 可配值
     * @return {@code {type:'ephemeral', ttl?, scope?}}
     */
    public static ObjectNode getCacheControl(CacheScope scope, String ttl) {
        ObjectNode cc = JSON.createObjectNode();
        cc.put("type", "ephemeral");
        if (ttl != null) {
            cc.put("ttl", ttl);
        }
        if (scope == CacheScope.GLOBAL) {
            cc.put("scope", "global");
        }
        return cc;
    }
}

package com.nexusai.application.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话级 system prompt section 缓存 · 对齐 CC {@code systemPromptSectionCache}
 * （CC original: {@code systemPromptSectionCache: new Map()} 会话级 STATE 字段
 * (Open-ClaudeCode/src/bootstrap/state.ts:399)）。
 *
 * <p>每个 {@code AgentState} 持有一个本类实例（跨会话隔离），resolve 阶段按
 * section name 读写。对齐 CC {@code Map<string, string | null>}（state.ts:1641-1653）：
 * <ul>
 *   <li>compute 返回 null 也要缓存（I-3），故内部用 {@link Collections#synchronizedMap}
 *       包装 {@link HashMap} —— {@link java.util.concurrent.ConcurrentHashMap} 拒绝 null
 *       值，无法满足 null 缓存语义；</li>
 *   <li>resolve 并行 compute 多线程写同一 Map，synchronizedMap 保证并发安全。</li>
 * </ul>
 *
 * <p>local-only 约束：本类实例挂在 AgentState 且以 {@code @JsonIgnore} 标记
 * （同 budgetTracker 红线），绝不序列化到 outbound DTO / STOMP / WebSocket /
 * EventPublisher payload。
 */
public class SystemPromptSectionCache {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSectionCache.class);

    private final Map<String, String> cache = Collections.synchronizedMap(new HashMap<>());

    /**
     * 取缓存值 · 对齐 CC {@code cache.get(s.name)}
     * （CC original: {@code cache.get(s.name)} (Open-ClaudeCode/src/constants/systemPromptSections.ts:51)）。
     *
     * @param name section 唯一标识
     * @return 缓存值（含已缓存的 null，见 {@link #has(String)}），未缓存则 null
     */
    public String get(String name) {
        return cache.get(name);
    }

    /**
     * 写缓存 · 对齐 CC {@code setSystemPromptSectionCacheEntry(name, value)}
     * （CC original: {@code setSystemPromptSectionCacheEntry(name, value)} 无条件 set，
     * 不判空 (state.ts:1645-1650 / systemPromptSections.ts:54)）。
     *
     * <p>compute 后无条件写回缓存，含 null 值（I-3 写侧）。
     *
     * @param name  section 唯一标识
     * @param value 计算结果，允许 null
     */
    public void set(String name, String value) {
        cache.put(name, value);
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSectionCache] 写回缓存: name={}, value=null? {}", name, value == null);
        }
    }

    /**
     * 是否已缓存 · 对齐 CC {@code cache.has(s.name)}
     * （CC original: {@code cache.has(s.name)} (systemPromptSections.ts:50)）。
     *
     * <p>{@link #set(String, String)} 写入 null 时 has 仍返回 true
     * （Map 含 key），与 get 返回 null 不矛盾（I-3 读侧）。
     *
     * @param name section 唯一标识
     * @return name 已入 Map（含值为 null 的条目）则 true
     */
    public boolean has(String name) {
        return cache.containsKey(name);
    }

    /**
     * 清空全部缓存 · 对齐 CC {@code clearSystemPromptSectionState()}
     * （CC original: {@code STATE.systemPromptSectionCache.clear()} (state.ts:1652-1653)）。
     *
     * <p>失效接线点直调本方法：CommandController（/clear）、PostCompactCleanup
     * （/compact）与 ToolRegistrationConfig（工具注册失效）；本类只建能力。
     */
    public void clear() {
        cache.clear();
        log.info("[SystemPromptSectionCache] 会话级 system prompt section 缓存已清空（对应 CC clearSystemPromptSectionState，/clear 与 /compact 后重新求值）");
    }
}

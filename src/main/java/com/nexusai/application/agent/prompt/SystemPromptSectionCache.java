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
 * <p><b>持有者 = 会话级 store（本类不再是 {@code AgentState} 的实例字段）</b>：
 * 本类实例由 {@link SessionPromptCacheStore#sectionCache()} 持有（一个会话一份，跨 run 不销毁），
 * {@code AgentState.systemPromptSectionCache()} 只是<b>转发</b>到该会话级实例
 * ⇒ 同一会话的相邻 run 命中同一份 Map，段值跨 run 逐字节稳定（前缀缓存命中的前提）。
 * 跨会话隔离由 store 的 sessionId 分区保证（⛔ 不是全局单例）。
 *
 * <p>resolve 阶段按 section name 读写。对齐 CC {@code Map<string, string | null>}（state.ts:1641-1653）：
 * <ul>
 *   <li>compute 返回 null 也要缓存（I-3），故内部用 {@link Collections#synchronizedMap}
 *       包装 {@link HashMap} —— {@link java.util.concurrent.ConcurrentHashMap} 拒绝 null
 *       值，无法满足 null 缓存语义；</li>
 *   <li>resolve 并行 compute 多线程写同一 Map，synchronizedMap 保证并发安全。</li>
 * </ul>
 *
 * <p>local-only 约束：本类实例经 {@code SessionPromptCacheStore} 挂在 AgentState 侧且以
 * {@code @JsonIgnore} 标记（同 budgetTracker 红线），绝不序列化到 outbound DTO / STOMP /
 * WebSocket / EventPublisher payload。
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
     * 已缓存段数（含值为 null 的条目）· <b>诊断/日志用</b>（数据流日志观测「会话级缓存是否在跨 run 复用」；
     * CC 无对应 accessor，不承载任何行为语义）。
     *
     * @return 条目数
     */
    public int size() {
        return cache.size();
    }

    /**
     * 清空全部缓存 · 对齐 CC {@code clearSystemPromptSectionState()}
     * （CC original: {@code STATE.systemPromptSectionCache.clear()} (state.ts:1652-1653)）。
     *
     * <p><b>失效调用面（步骤 4 起已改准 —— 原注释称三个调用点「直调本方法」，那已不是事实）</b>：
     * 各失效点（{@code /clear} / {@code /compact} / worktree 进出 / 工具注册）一律经<b>会话级 store 的
     * 集合语义入口</b> {@link SessionPromptCacheStore#clearGroups(java.util.Set, String)}
     * （集合成员见 {@link PromptCacheGroup}）→ 本方法；⛔ <b>不再</b>由调用点直调 ——
     * 直调会绕过「清哪几个集合」的定义与逐集合日志。本类只建能力（{@code SystemPromptSectionStore}
     * 与 {@link SessionPromptCacheStore#close()} 亦会调用，后者是回收路径而非失效路径）。
     */
    public void clear() {
        cache.clear();
        log.info("[SystemPromptSectionCache] 会话级 system prompt section 缓存已清空（集合A；对应 CC clearSystemPromptSectionState，caches 各失效点后重新求值）");
    }
}

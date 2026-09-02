package com.nexusai.application.agent.workflow.agent;

import com.nexusai.application.agent.workflow.AgentRunParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 多后端 adapter 注册表（完整实现）· CC original: {@code AgentAdapterRegistry}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:94-155)。
 *
 * <p><b>职责划分（与 W-1d 端口层抽象同名接口）</b>：{@code com.nexusai.application.agent.workflow.AgentAdapterRegistry}
 * （根包，W-1d）是 {@code WorkflowPorts} 8 项依赖之一的最小契约（{@code has}/{@code resolve}）；
 * 本类（W-1e）是其完整实现：{@code register}/{@code default}/{@code route}/{@code resolve}/
 * {@code initializeAll}/{@code disposeAll} 全量链式 API + AdapterRouteRule 按插入序匹配。
 * 本类 {@code implements} 根包接口，{@code resolve} 用协变返回收敛为 {@link AgentAdapter}
 * （CC 返回类型 {@code AgentAdapter}，根包接口 P0 以 Object 承载避免 W-1d 引入本类型）。
 *
 * <p><b>resolve 语义</b>（agentAdapter.ts:147-151）：按插入序 matchRule → 首个命中返回；
 * 无命中回落 default；两者皆无 → 抛 {@link AdapterNotFoundError}（配置错误不重试，
 * hooks.agent 在 try 外 resolve，hooks.ts:183）。
 *
 * <p>生命周期：{@link #initializeAll()}/{@link #disposeAll()} 统一触发全部 adapter 的
 * initialize/dispose（CC {@code a.initialize?.()}，跳过未实现者）。
 */
public final class AgentAdapterRegistry implements com.nexusai.application.agent.workflow.AgentAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentAdapterRegistry.class);

    /** id → adapter · CC original: {@code Map<string, AgentAdapter> adapters} (agentAdapter.ts:96)。 */
    private final Map<String, AgentAdapter> adapters = new LinkedHashMap<>();

    /** 路由规则（插入序匹配）· CC original: {@code AdapterRouteRule[] rules} (agentAdapter.ts:97)。 */
    private final List<AdapterRouteRule> rules = new ArrayList<>();

    /** default adapter id · CC original: {@code defaultId: string | null} (agentAdapter.ts:98)。 */
    private String defaultId = null;

    /**
     * 注册 adapter（重复 id 覆盖）· CC original: {@code register} (agentAdapter.ts:100-103)。
     *
     * @param adapter 待注册 adapter
     * @return this（链式）
     */
    public AgentAdapterRegistry register(AgentAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("AgentAdapterRegistry.register: adapter 不能为 null");
        }
        AgentAdapter prev = adapters.put(adapter.id(), adapter);
        if (log.isDebugEnabled()) {
            log.debug("AgentAdapterRegistry.register：id={} capabilities={}，{}（CC agentAdapter.ts:100-103，重复 id 覆盖）",
                    adapter.id(), adapter.capabilities(), prev == null ? "新增" : "覆盖 " + prev.id());
        }
        return this;
    }

    /**
     * 设置 default adapter · CC original: {@code default} (agentAdapter.ts:106-109)。
     *
     * <p>Java {@code default} 是保留字，命名 {@code defaultAdapter}。
     *
     * @param adapterId default adapter id
     * @return this（链式）
     */
    public AgentAdapterRegistry defaultAdapter(String adapterId) {
        this.defaultId = adapterId;
        if (log.isDebugEnabled()) {
            log.debug("AgentAdapterRegistry.defaultAdapter：defaultId={}（CC agentAdapter.ts:106-109）", adapterId);
        }
        return this;
    }

    /**
     * 追加一条路由规则 · CC original: {@code route} (agentAdapter.ts:112-115)。
     *
     * @param rule 路由规则（agentType/model/custom）
     * @return this（链式）
     */
    public AgentAdapterRegistry route(AdapterRouteRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AgentAdapterRegistry.route: rule 不能为 null");
        }
        this.rules.add(rule);
        if (log.isDebugEnabled()) {
            log.debug("AgentAdapterRegistry.route：规则 {} 加入，当前规则数={}（CC agentAdapter.ts:112-115）",
                    rule.getClass().getSimpleName(), rules.size());
        }
        return this;
    }

    /**
     * 是否已注册指定 id · CC original: {@code has(id)} (agentAdapter.ts:117-119)。
     *
     * @param id adapter id
     * @return 是否已注册
     */
    @Override
    public boolean has(String id) {
        return adapters.containsKey(id);
    }

    /**
     * 取指定 id 的 adapter · CC original: {@code get(id)} (agentAdapter.ts:121-123)。
     *
     * @param id adapter id
     * @return adapter 或 null
     */
    public AgentAdapter get(String id) {
        return adapters.get(id);
    }

    /**
     * 按规则解析 adapter · CC original: {@code resolve} (agentAdapter.ts:126-140)。
     *
     * <p>匹配序：按插入序 matchRule → 首个命中返回；无命中回落 default；两者皆无抛
     * {@link AdapterNotFoundError}（配置错误不重试）。
     *
     * @param params agent() 入参（agentType/model 为路由依据）
     * @return 解析到的 adapter
     * @throws AdapterNotFoundError 无规则命中且无 default 时抛出
     */
    @Override
    public AgentAdapter resolve(AgentRunParams params) throws AdapterNotFoundError {
        for (AdapterRouteRule rule : rules) {
            if (matchRule(rule, params)) {
                AgentAdapter hit = adapters.get(rule.adapter());
                if (hit != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("AgentAdapterRegistry.resolve 命中规则：adapterId={} rule={}（CC agentAdapter.ts:128-130）",
                                hit.id(), rule.getClass().getSimpleName());
                    }
                    return hit;
                }
            }
        }
        if (defaultId != null) {
            AgentAdapter fallback = adapters.get(defaultId);
            if (fallback != null) {
                if (log.isDebugEnabled()) {
                    log.debug("AgentAdapterRegistry.resolve 回落 default：adapterId={}（CC agentAdapter.ts:133-135）",
                            defaultId);
                }
                return fallback;
            }
        }
        log.warn("AgentAdapterRegistry.resolve 无命中：rules={} default={}（CC agentAdapter.ts:137-139 抛 AdapterNotFoundError）",
                rules.size(), defaultId == null ? "none" : defaultId);
        throw new AdapterNotFoundError(String.format(
                "No adapter matched (rules=%d, default=%s)",
                rules.size(), defaultId == null ? "none" : defaultId));
    }

    /**
     * 触发全部 adapter 的 initialize · CC original: {@code initializeAll} (agentAdapter.ts:143-147)。
     *
     * <p>跳过未实现者（default 空实现等价 CC {@code ?.()}）。
     *
     * @return 全部 initialize 完成
     */
    public CompletableFuture<Void> initializeAll() {
        return CompletableFuture.allOf(adapters.values().stream()
                .map(a -> a.initialize())
                .toArray(CompletableFuture[]::new))
                .whenComplete((v, e) -> {
                    if (log.isDebugEnabled()) {
                        log.debug("AgentAdapterRegistry.initializeAll：{} 个 adapter 初始化完成（CC agentAdapter.ts:143-147）",
                                adapters.size());
                    }
                });
    }

    /**
     * 触发全部 adapter 的 dispose · CC original: {@code disposeAll} (agentAdapter.ts:149-153)。
     *
     * @return 全部 dispose 完成
     */
    public CompletableFuture<Void> disposeAll() {
        return CompletableFuture.allOf(adapters.values().stream()
                .map(a -> a.dispose())
                .toArray(CompletableFuture[]::new))
                .whenComplete((v, e) -> {
                    if (log.isDebugEnabled()) {
                        log.debug("AgentAdapterRegistry.disposeAll：{} 个 adapter 释放完成（CC agentAdapter.ts:149-153）",
                                adapters.size());
                    }
                });
    }

    /**
     * 单条规则匹配 · CC original: {@code matchRule} (agentAdapter.ts:157-165)。
     *
     * <pre>{@code
     * function matchRule(rule, params): boolean {
     *   if (rule.kind === 'agentType') return params.agentType === rule.agentType
     *   if (rule.kind === 'model') {
     *     return typeof params.model === 'string' && params.model.startsWith(rule.pattern)
     *   }
     *   return rule.match(params)  // custom
     * }
     * }</pre>
     *
     * @param rule   路由规则
     * @param params agent() 入参
     * @return 是否命中
     */
    private static boolean matchRule(AdapterRouteRule rule, AgentRunParams params) {
        return switch (rule) {
            case AdapterRouteRule.AgentType t ->
                    params.agentType() != null && params.agentType().equals(t.agentType());
            case AdapterRouteRule.Model m ->
                    params.model() != null && params.model().startsWith(m.pattern());
            case AdapterRouteRule.Custom c -> c.match().test(params);
        };
    }
}

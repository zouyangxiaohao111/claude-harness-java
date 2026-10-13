package com.nexusai.application.agent.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话<b>用量/费用</b>账本注册表 · 按 sessionId 取 {@link SessionUsageLedger}（同会话所有 agent 共享实例）。
 *
 * <p><b>⚠️ 命名区分（勿混淆）</b>：本类是<b>用量与计费</b>账本（token/费用聚合、每 call 落表、
 * 层级构成），位于 {@code application/agent/cost}；反之
 * {@link com.nexusai.application.agent.SessionLedgerRegistry} 是<b>会话历史「活账本」接力</b>
 * 注册表（保前缀缓存的 messages 热接力），与本类不同物、无耦合。二者原名近似易混，故本类
 * 于 2026-10-10 由 {@code SessionUsageLedgerRegistry} 更名为 {@code SessionUsageAccountingRegistry}。
 *
 * <p><b>WHY 需要共享实例</b>：子代理 / fork / cron / 旁路调用的用量都要并入**同一个会话账目**
 * （本次改造要修的核心症状），而它们的执行现场彼此隔离（子代理是独立 {@code AgentState}）⇒
 * 必须有一个按 sessionId 索引的进程内单例表，让「谁记账」与「谁发起」解耦。
 *
 * <p><b>与 {@code SessionAgentStateRegistry} 的关系</b>：后者管「会话 → 主 AgentState」，本表管
 * 「会话 → 用量账本」；两者键同为 sessionId，账本另持有各 agent 的 state 引用（{@code attach}）。
 *
 * <p><b>生命周期</b>：首次触达即建（懒加载）；会话删除时由调用方 {@link #remove(String)} 清理。
 * 无会话上下文（sessionId null/空白）的调用归入 {@link #NO_SESSION_KEY} 槽位 —— 仍统计，不丢弃。
 */
@Component
public class SessionUsageAccountingRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionUsageAccountingRegistry.class);

    /** 无会话上下文的调用归属槽（不伪造 sessionId）。 */
    public static final String NO_SESSION_KEY = "__no_session__";

    private final Map<String, SessionUsageLedger> ledgers = new ConcurrentHashMap<>();

    /** 计价纯函数（随账本注入；未装配 → 账本费用记 0，不猜价格）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelCostCalculator costCalculator;

    /** 明细表 mapper（随账本注入；未装配 → 跳过落表）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.repository.usage.mapper.LlmUsageCallMapper usageCallMapper;

    /** 取（或新建）会话账本；sessionId null/空白 → 归入无会话槽。 */
    public SessionUsageLedger forSession(String sessionId) {
        String key = (sessionId == null || sessionId.isBlank()) ? NO_SESSION_KEY : sessionId;
        return ledgers.computeIfAbsent(key, k -> {
            if (log.isDebugEnabled()) {
                log.debug("[SessionUsageAccountingRegistry] 新建会话账本: sessionId={}", k);
            }
            return new SessionUsageLedger(k, costCalculator, usageCallMapper);
        });
    }

    /** 只读查询（不存在 → null；不建）。 */
    public SessionUsageLedger peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return ledgers.get(sessionId);
    }

    /** 会话销毁时清理（避免长期驻留）。 */
    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionUsageLedger removed = ledgers.remove(sessionId);
        if (removed != null && log.isDebugEnabled()) {
            log.debug("[SessionUsageAccountingRegistry] 会话账本已清理: sessionId={} calls={} costYuan={}",
                sessionId, removed.totalCalls(), removed.totalCostYuan());
        }
    }

    /** 当前驻留账本数（诊断/测试用）。 */
    public int size() {
        return ledgers.size();
    }
}

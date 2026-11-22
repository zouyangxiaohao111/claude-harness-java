package com.nexusai.application.agent.subagent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退役 agent 表 · 对齐 CC「名字在 agent 完成后仍可寻址」的语义
 * （claude.exe 2.1.328 SendMessage 侧：{@code "names keep working after an agent completes
 * (a send resumes it from its transcript)"}，偏移 356111578）。
 *
 * <h2>WHY 需要独立的退役表（而不是在注销时把名字删掉）</h2>
 * <p>本仓 {@link AgentNameRegistry#unregister} 原先同时删除 name→agentId 与 byAgentId 两处映射；
 * agent 终态后 {@code resolveEntry} 恒 null ⇒ SendMessage 的
 * 「已停止 → 读 transcript 热启动」分支在生产<b>不可达</b>（只有单测 mock 掉 resolveEntry 才看起来更绿）。
 * CC 的 name→agentId 名册在 agent 完成后仍保留，evicted 分支再按 agentId 反查磁盘 sidecar。
 * 本类就是这块「注销后仍可解析」的承载：注销时写入终态/停止原因/transcript 路径，解析顺序
 * 活跃表 → 退役表 → not-found。
 *
 * <p>纯 Java（无 Spring、无 IO）：由 {@link AgentNameRegistry} 持有并驱动；线程安全。
 */
public final class RetiredAgentRegistry {

    /**
     * 一条退役记录。
     *
     * @param ownerSessionId 父会话 ID（按名解析的 owner 限定键）
     * @param name           归一化后的 agent 名（{@link com.nexusai.application.agent.messaging.PeerAddressing#normalizeName}）
     * @param agentId        原 agentId（taskId === agentId）
     * @param terminalStatus 注销时的任务终态（{@code completed}/{@code failed}/{@code killed}；不可知 → null）
     * @param transcriptPath transcript 文件路径（不可解析 → null）
     * @param stopReason     停止原因：{@code user}（用户显式 TaskStop）/ {@code killed}（系统终止）/ null
     * @param retiredAt      注销时刻（ISO-8601）
     */
    public record RetiredAgent(String ownerSessionId, String name, String agentId,
                               String terminalStatus, String transcriptPath,
                               String stopReason, String retiredAt) {
        public boolean stoppedByUser() {
            return "user".equals(stopReason);
        }
    }

    /** owner+name 复合键（owner=null 统一用空串）。 */
    private record Key(String owner, String name) {}

    private final Map<Key, String> nameToAgentId = new ConcurrentHashMap<>();
    private final Map<String, RetiredAgent> byAgentId = new ConcurrentHashMap<>();

    /** 记录一条退役（同 owner+name 覆盖为最新一代；旧 agentId 条目同时更新）。 */
    public void retire(RetiredAgent entry) {
        if (entry == null || entry.name() == null || entry.agentId() == null) {
            return;
        }
        Key key = new Key(entry.ownerSessionId() == null ? "" : entry.ownerSessionId(),
            normalize(entry.name()));
        String prev = nameToAgentId.put(key, entry.agentId());
        byAgentId.put(entry.agentId(), entry);
        if (prev != null && !prev.equals(entry.agentId())) {
            byAgentId.remove(prev);
        }
    }

    /**
     * 重启重建写入（{@link RetiredAgentRebuilder} 专用）。
     *
     * <p>与 {@link #retire} 的差异只有一点：<b>同一 agentId 以最新 meta 为准</b> —— 若该
     * agentId 此前以旧名字记过，先删掉指向它的旧 name 键，避免「改名后旧名仍能解析到新条目」。
     * 重复扫描同一 agentId 不会产生重复条目（两处映射都按 agentId / owner+name 键覆盖）。
     */
    public void retireScanned(RetiredAgent entry) {
        if (entry == null || entry.name() == null || entry.name().isBlank()
                || entry.agentId() == null) {
            return;
        }
        Key key = new Key(entry.ownerSessionId() == null ? "" : entry.ownerSessionId(),
            normalize(entry.name()));
        nameToAgentId.entrySet().removeIf(
            e -> entry.agentId().equals(e.getValue()) && !e.getKey().equals(key));
        nameToAgentId.put(key, entry.agentId());
        byAgentId.put(entry.agentId(), entry);
    }

    /** owner 限定按名解析（活跃表未命中时调用）。 */
    public RetiredAgent find(String ownerSessionId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String agentId = nameToAgentId.get(new Key(
            ownerSessionId == null ? "" : ownerSessionId, normalize(name)));
        return agentId == null ? null : byAgentId.get(agentId);
    }

    /** 按 agentId 反查（evicted 分支：名字可能已被新一代覆盖）。 */
    public RetiredAgent findByAgentId(String agentId) {
        return agentId == null ? null : byAgentId.get(agentId);
    }

    /** 条目数（测试/观测）。 */
    public int size() {
        return byAgentId.size();
    }

    /** 清空（测试用）。 */
    public void clear() {
        nameToAgentId.clear();
        byAgentId.clear();
    }

    /** 快照（测试/诊断；按 retiredAt 升序）。 */
    public Map<String, RetiredAgent> snapshot() {
        return new LinkedHashMap<>(byAgentId);
    }

    /** 现时戳（ISO-8601，便于排查「何时被退役」）。 */
    static String now() {
        return Instant.now().toString();
    }

    private static String normalize(String name) {
        String n = com.nexusai.application.agent.messaging.PeerAddressing.normalizeName(name);
        return n == null ? "" : n;
    }
}

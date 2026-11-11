package com.nexusai.application.agent.subagent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 会话级「主线程人格」快照 · 对齐 CC 2.1.322 的 {@code this.mainThreadAgent} 冻结语义。
 *
 * <p><b>对齐的 CC 真源</b>（exe 2.1.322 逐字节核实）：
 * <ul>
 *   <li>每轮喂给模型的 system prompt 取自会话对象里冻结的那份定义：
 *       {@code Z9({mainThreadAgentDefinition: Mt.mainThreadAgentDefinition, ...})}
 *       （offset≈329932127），getter 即
 *       {@code get mainThreadAgentDefinition(){return this.mainThreadAgent}}。</li>
 *   <li>{@code this.mainThreadAgent} 只有两个写点：
 *       <ol>
 *         <li>构造：{@code this.mainThreadAgent = h.mainThreadAgentDefinition}（offset≈328512174）；</li>
 *         <li>{@code setMainThreadAgentDefinition(h)}（offset≈328518065），
 *             唯一实际调用点只有 resume（offset≈328748703）。</li>
 *       </ol>
 *   </li>
 *   <li>工具白名单 {@code computeToolPool}（offset≈328523098）用的是<b>同一份</b>
 *       {@code this.mainThreadAgent} ⇒ MD 里的 {@code tools:} 同样不热更。</li>
 * </ul>
 *
 * <p><b>为什么不跟随 watcher 热更</b>：CC 的文件监听器只刷新 AppState 里的
 * {@code agentDefinitions} 列表，<b>不会</b>回写 {@code this.mainThreadAgent}。
 * 因此磁盘上的 agent md 文件改了，当前会话（已解析过）继续用旧定义；
 * 只有新进程 / 新会话才会读到最新。本类即该语义的 Java 侧落点。
 *
 * <p><b>失效边界</b>：
 * <ul>
 *   <li>agentType 变更（用户显式换 agent）⇒ 下一次 {@link #resolve} 直接覆盖快照，<b>立刻生效</b>；</li>
 *   <li>进程重启 ⇒ 静态表随进程消失；</li>
 *   <li>{@code /clear} <b>不清</b>（CC 的 {@code clearAgentDefinitionsCache} 也不重设
 *       {@code mainThreadAgent}，严格同构）；</li>
 *   <li>无会话键（sessionId 或 agentType 为空）⇒ 跳过缓存直接 lookup，防跨会话串味。</li>
 * </ul>
 *
 * <p><b>内存</b>：一进程内每会话一个 snapshot（{@code agentType} 字符串 + 一个对象引用），
 * 量级 = 会话数 × 一个对象引用，无需额外回收策略；{@link #evict} 供未来会话删除时调用。
 *
 * <p>非 Spring bean，纯静态工具类。
 */
public final class SessionMainThreadAgentRegistry {

    /** sessionId → 冻结的主线程 agent 快照。 */
    private static final ConcurrentHashMap<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private SessionMainThreadAgentRegistry() {
        // 静态工具类
    }

    /** 会话快照：agentType 用于判断「用户是否显式换了 agent」。 */
    private record Snapshot(String agentType, AgentDefinition definition) {}

    /**
     * 解析（并按需冻结）会话的主线程 agent 定义。
     *
     * <p>分支表：
     * <table border="1">
     *   <tr><th>条件</th><th>行为</th></tr>
     *   <tr><td>sessionId 为 null/空白，或 agentType 为 null/空白</td>
     *       <td>直接 {@code lookup.get()}，<b>不缓存</b>（无会话键不共享）</td></tr>
     *   <tr><td>缓存命中且 {@code snapshot.agentType.equals(agentType)}</td>
     *       <td>返回快照里的 definition，<b>不调用</b> lookup</td></tr>
     *   <tr><td>否则（未命中 / agentType 已变）</td>
     *       <td>调用 {@code lookup.get()}；<b>仅非 null 才 put</b>（null 不覆盖旧快照）</td></tr>
     * </table>
     *
     * <p>⚠️ 刻意不用 {@code computeIfAbsent}：lookup 可能返回 null，
     * {@code ConcurrentHashMap} 对 null 值会抛 NPE。
     *
     * @param sessionId 会话 id（缓存键）
     * @param agentType 会话绑定的主线程 agent 类型（快照失效判据）
     * @param lookup    真正的 registry 查询（通常是
     *                  {@code () -> subagentTool.agentRegistry(sessionId).findAgent(agentType)}）
     * @return 命中的 {@link AgentDefinition}；未命中返回 null
     */
    public static AgentDefinition resolve(String sessionId, String agentType,
                                          Supplier<AgentDefinition> lookup) {
        if (sessionId == null || sessionId.isBlank() || agentType == null || agentType.isBlank()) {
            // 无会话键 ⇒ 不共享缓存，直接现查（防跨会话串味）
            return lookup.get();
        }
        Snapshot cached = SNAPSHOTS.get(sessionId);
        if (cached != null && cached.agentType().equals(agentType)) {
            return cached.definition();
        }
        AgentDefinition resolved = lookup.get();
        if (resolved != null) {
            // agentType 变化即覆盖旧快照（用户显式换 agent 立刻生效）
            SNAPSHOTS.put(sessionId, new Snapshot(agentType, resolved));
        }
        return resolved;
    }

    /**
     * 丢弃某会话的快照（供未来会话删除时调用）。
     *
     * @param sessionId 会话 id；null 时无操作
     */
    public static void evict(String sessionId) {
        if (sessionId != null) {
            SNAPSHOTS.remove(sessionId);
        }
    }

    /** 清空全部快照（仅供测试）。 */
    public static void clearAll() {
        SNAPSHOTS.clear();
    }
}

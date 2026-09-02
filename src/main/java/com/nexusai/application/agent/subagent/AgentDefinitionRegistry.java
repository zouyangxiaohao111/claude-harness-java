package com.nexusai.application.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase A 任务 5: Agent 定义单一注册中心 · 对齐 CC toolUseContext.options.agentDefinitions 合并逻辑.
 *
 * <p>职责:
 * <ul>
 *   <li>合并 built-in ({@link BuiltInAgents}) + custom (来自 settings/CLAUDE.md/plugin) 两类来源</li>
 *   <li>覆盖语义: 自定义与内置 type 冲突时, <b>custom 覆盖 builtIn</b>, 对齐 CC {@code loadAgentsDir.ts:216} {@code agentMap.set}</li>
 *   <li>提供 {@link #findAgent(String)} / {@link #listAgents()} 供 {@code SubagentTool} 调度</li>
 *   <li>[R-B2] 保留全量原始列表（含 shadowed agents）并暴露 {@link #resolveAgentOverrides()},
 *       还原 CC {@code resolveAgentOverrides}（agentDisplay.ts:46-72）的 overriddenBy 覆盖标注,
 *       agents 端点可展示 "(shadowed by ...)"</li>
 * </ul>
 *
 * <p>WHY 单一注册中心: 之前 {@code SubagentTool.availableAgents} 直接吃 {@code List<AgentDefinition>},
 * 内置/自定义来源各自接入, 难以做"覆盖语义"统一. 现在: 入口收敛, Spring 装配时构建一次,
 * SubagentTool 只面对 {@code AgentDefinitionRegistry} 接口 (find/list), 不再接触 raw List.
 *
 * <p>WHY 覆盖语义 (P0-1 修复 + IMP-SUB-09 REWORK R2-WF-E): CC {@code loadAgentsDir.ts:193-221}
 * {@code getActiveAgentsFromList} 6 组按序 [builtIn, plugin, user, project, flag, managed]
 * 合并, 每组用 {@code agentMap.set} 覆盖前组同 agentType → 覆盖优先级
 * managed>flag>project>user>plugin>builtIn (managed 最高, builtIn 最低).
 *
 * <p><b>[T3 内容读兼容 · 双用户层]</b>：user 层含 nexusai 自有根（~/.{appName}/agents）与 claude
 * （~/.claude/agents）两源，均 source=userSettings。nexusai 覆盖 claude 的<b>同名 agentType</b> 由上游
 * 保证：{@code MarkdownConfigLoader} name 去重层（subdir=agents 按 frontmatter name first-wins）+
 * {@code loadAgentsDir.load} 用户源双目录 putIfAbsent 合并 —— 两处 nexusai 均在前加载，registry 消费时
 * userSettings 组内已只剩 nexusai 版本。<b>getActiveAgentsFromList 6 组优先级不动</b>（user 组内 nexusai 与
 * claude 同源同组，不参与跨组覆盖）。
 * 旧 Java 用 if-absent 语义使内置吞掉自定义, 用户 {@code .claude/agents/general-purpose.md}
 * 永远不生效; R2-WF-E 前构造器 m.put last-wins 在 custom 序 [managed,user,project] 下
 * 与 CC 在 managed 档完全反转. 本期构造器 (:79) 与 {@link #merge(List)} (:129) 均经
 * {@link loadAgentsDir#getActiveAgentsFromList} 折叠, 两条生产路径统一 6 组覆盖语义
 * (折叠细节见构造器 Javadoc :56-74).
 *
 * <p>反模式防御 (本类不做):
 * <ul>
 *   <li><b>不做 filter</b> — 那是 SubagentTool.filterDeniedAgents (委托 PermissionBubbleService) 的职责</li>
 *   <li><b>不做 ThreadLocal</b> — 纯值对象, 注入即用</li>
 *   <li><b>不改 AgentDefinition record 字段</b> — 字段对齐 CC BaseAgentDefinition 已稳定</li>
 * </ul>
 *
 * <p>BudgetTracker 红线: 本类不持有任何 BudgetTracker 引用, 不进入 AgentState → 外部通道.
 * 也不参与 telemetry / log 上传.
 */
public final class AgentDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionRegistry.class);

    /** 不可变索引 · agentType → AgentDefinition (managed/自定义覆盖 builtIn, 对齐 CC loadAgentsDir.ts:216)
     *  <p>[ODF-C3] volatile + merge() 重建, 支持运行时 flag/plugin agents 并入 (Spring 装配后
     *  SubagentTool.setJsonAgents 调用). findAgent/listAgents 读 volatile 可见最新索引. */
    private volatile Map<String, AgentDefinition> byType;

    /** [R-B2] 全量 agent 原始列表 (含被覆盖的 shadowed agents) · 对齐 CC getAgentDefinitionsWithOverrides
     *  返回的 allAgents (loadAgentsDir.ts:359-363 = [builtIn, plugin, custom], 不去重).
     *  <p>byType 只保留 winner (覆盖语义折叠), shadowed agents 被丢弃后 overriddenBy 恒 null、
     *  CC shadowed 展示不可达 (B-2 受控差异, unresolved-owner-decisions.md B-2). 本字段保留全量原始列表,
     *  {@link #resolveAgentOverrides()} 据此还原 CC shadowed 标注 (agentDisplay.ts:46-72).
     *  <p>不可变 (Collections.unmodifiableList) + volatile: merge() 重建时整体替换, 读侧无并发撕裂. */
    private volatile List<AgentDefinition> allAgents;

    /**
     * 构造器: 合并 built-in (Map) + custom (List) → 6 组覆盖优先级折叠.
     *
     * <p><b>[IMP-SUB-09 REWORK R2-WF-E]</b> 构造路径应用 CC {@code getActiveAgentsFromList}
     * （loadAgentsDir.ts:193-221）: builtIn + custom 合并为全量列表后统一经
     * {@code loadAgentsDir.getActiveAgentsFromList} 折叠, 同 agentType 跨源冲突时
     * managed(policySettings) > flag(flagSettings) > project(projectSettings) > user(userSettings)
     * > plugin > builtIn（后组覆盖前组, managed 最高优先）。
     *
     * <p>WHY（优先级反转修复）: 旧实现 {@code m.put} last-wins —— custom 列表序
     * [managed,user,project]（{@code loadAllSources} 返回序 = MarkdownConfigLoader 去重序
     * {@code [...managed,...user,...project]}, markdownConfigLoader.ts:377-378）下 project 覆盖
     * user 覆盖 managed, 与 CC 在 managed 档<b>完全反转</b>（管理策略被本地 user/project agent
     * 静默覆盖, 恰是 CC 把 managed 设最高优先所防的场景）。生产构造路径（{@code SubagentTool}
     * 无参/9 参构造器, SubagentTool.java:417-419/:476-477）均经本构造器 —— 折叠必须在此发生,
     * 与 {@link #merge(List)}（:143, 已经 getActiveAgentsFromList）统一语义。
     *
     * <p>单层 builtIn+custom 的"custom 覆盖 builtIn"旧语义是 6 组折叠的特例（user 组晚于 built-in
     * 组注册）, 本实现对其完全兼容（聚焦测试 {@code customAgent_overrides_builtIn_when_same_agentType}
     * 等用例锁定）。
     *
     * @param builtIn 内置 Agent 映射 (来自 {@link BuiltInAgents}), null 时退化为空 Map
     * @param custom  自定义 Agent 列表 (来自 loadAllSources/plugin/flag, source 已打标), null 时退化为空 List
     */
    public AgentDefinitionRegistry(Map<String, AgentDefinition> builtIn, List<AgentDefinition> custom) {
        List<AgentDefinition> all = new ArrayList<>();
        if (builtIn != null) {
            all.addAll(builtIn.values());
        }
        if (custom != null) {
            for (AgentDefinition a : custom) {
                if (a != null) all.add(a);
            }
        }
        // [R-B2] 保留全量原始列表（含 shadowed agents）供 resolveAgentOverrides 还原 CC shadowed 标注
        this.allAgents = Collections.unmodifiableList(new ArrayList<>(all));
        // [IMP-SUB-09 REWORK R2-WF-E] 6 组覆盖优先级折叠（对齐 CC getActiveAgentsFromList
        //   loadAgentsDir.ts:193-221）: managed>flag>project>user>plugin>builtIn。旧实现 m.put
        //   last-wins 在序 [managed,user,project] 下 project 胜出 → managed 最低优先（反转）。
        List<AgentDefinition> active = loadAgentsDir.getActiveAgentsFromList(all);
        Map<String, AgentDefinition> m = new HashMap<>();
        for (AgentDefinition a : active) {
            if (a != null) m.put(a.agentType(), a);
        }
        this.byType = Collections.unmodifiableMap(m);
        int overridden = all.size() - byType.size();
        if (log.isDebugEnabled()) {
            log.debug("[AgentDefinitionRegistry] 合并明细: 内置={} 自定义={} 总计={} 覆盖数={} (6 组优先级 managed>flag>project>user>plugin>builtIn)",
                builtIn != null ? builtIn.size() : 0,
                custom != null ? custom.size() : 0,
                byType.size(), overridden);
        }
        log.info("[AgentDefinitionRegistry] 合并完成: 内置={} 自定义={} 总计={} (6 组优先级 managed>flag>project>user>plugin>builtIn, 对齐 CC getActiveAgentsFromList loadAgentsDir.ts:193-221)",
            builtIn != null ? builtIn.size() : 0,
            custom != null ? custom.size() : 0,
            byType.size());
    }

    /**
     * [ODF-C3] 运行时并入 agents · 对齐 CC main.tsx:2035-2044 (--agents flag) +
     * print.ts:4381-4383 (SDK request.agents) + loadPluginAgents.ts (plugin agents)。
     *
     * <p>6 组覆盖合并对齐 CC {@code getActiveAgentsFromList}（loadAgentsDir.ts:193-221）：
     * [builtIn, plugin, user, project, flag, managed] 按序 {@code agentMap.set}，
     * 后组覆盖前组同 agentType → 优先级 managed>flag>project>user>plugin>builtIn。
     * 内部经 {@link loadAgentsDir#getActiveAgentsFromList} 重建索引，不破坏
     * findAgent/listAgents 的既有读路径（volatile 索引原子替换）。
     *
     * <p>WHY: 构造期（Spring 装配）只有 built-in + userSettings 磁盘 agents；flag/plugin
     * agents 在配置入口（setJsonAgents）与插件目录扫描（PluginLoader.loadAgents）到达，
     * 必须运行时并入而非重建 registry。
     *
     * @param additionalAgents 新增 agents（flagSettings/plugin 等来源）；null/空 → no-op
     * @return this（链式）
     */
    public synchronized AgentDefinitionRegistry merge(List<AgentDefinition> additionalAgents) {
        if (additionalAgents == null || additionalAgents.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentDefinitionRegistry] merge no-op: 空 agents 列表");
            }
            return this;
        }
        // [R-B2] 折叠基改为全量 allAgents（含 shadowed）而非 byType.values()（仅 winner）——
        //   winner 结果与旧基一致（6 组优先级折叠对 winner-only 与全量幂等），但 shadowed 记录
        //   跨 merge 保留，resolveAgentOverrides 才能还原完整 CC 覆盖关系（agentDisplay.ts:46-72）。
        List<AgentDefinition> all = new ArrayList<>(this.allAgents);
        for (AgentDefinition a : additionalAgents) {
            if (a != null) all.add(a);
        }
        this.allAgents = Collections.unmodifiableList(all);
        // 6 组覆盖优先级：managed>flag>project>user>plugin>builtIn（getActiveAgentsFromList 后组覆盖前组）
        List<AgentDefinition> active = loadAgentsDir.getActiveAgentsFromList(all);
        Map<String, AgentDefinition> m = new HashMap<>();
        for (AgentDefinition a : active) {
            if (a != null) m.put(a.agentType(), a);
        }
        int added = additionalAgents.size();
        int overridden = byType.size() + added - m.size();
        this.byType = Collections.unmodifiableMap(m);
        if (log.isDebugEnabled()) {
            log.debug("[AgentDefinitionRegistry] merge 完成: 新增={} 覆盖={} 总计={}",
                added, overridden, m.size());
        }
        log.info("[AgentDefinitionRegistry] merge 并入 {} 个 agent: 覆盖 {} 个同 type (6 组优先级 managed>flag>project>user>plugin>builtIn)",
            added, overridden);
        return this;
    }

    /**
     * 按 agentType 查找 · 对齐 CC builtInAgents[type] / customAgents[type].
     *
     * @param agentType agent type 名称 (可 null, null 返回 null)
     * @return 命中的 AgentDefinition; 未知返回 null
     */
    public AgentDefinition findAgent(String agentType) {
        if (agentType == null) return null;
        return byType.get(agentType);
    }

    /**
     * 列出所有可用 Agent · 对齐 CC activeAgents 列表.
     *
     * <p>返回可变副本 (防御性 copy), 调用方可自由排序/过滤而不影响内部不可变索引.
     *
     * @return 所有注册 Agent 的可变 List 副本
     */
    public List<AgentDefinition> listAgents() {
        return new ArrayList<>(byType.values());
    }

    /**
     * [R-B2] 带覆盖标注的 Agent 记录 · 对齐 CC {@code ResolvedAgent}
     * （agentDisplay.ts:34-36, {@code AgentDefinition & { overriddenBy?: AgentSource }}）。
     *
     * @param agent       原始 Agent 定义（全量列表成员，可能非 winner）
     * @param overriddenBy 覆盖者来源（CC original: overriddenBy, agentDisplay.ts:35）——
     *                     当 {@code agent} 的 agentType 有更高优先级 source 的 winner 时记录该 winner 的
     *                     source；agent 本身是 winner 或同 source 时 null（对齐 CC undefined）
     */
    public record ResolvedAgentDefinition(AgentDefinition agent, String overriddenBy) {}

    /**
     * [R-B2] 解析覆盖关系 · 对齐 CC {@code resolveAgentOverrides}
     * （agentDisplay.ts:46-72, cli/handlers/agents.ts:36 消费）。
     *
     * <p>CC 行为（唯一真源，agentDisplay.ts:46-72）：
     * <pre>
     *   const activeMap = new Map(); for (a of activeAgents) activeMap.set(a.agentType, a)
     *   const seen = new Set()
     *   for (a of allAgents) {
     *     const key = `${a.agentType}:${a.source}`; if (seen.has(key)) continue; seen.add(key)
     *     const active = activeMap.get(a.agentType)
     *     const overriddenBy = active && active.source !== a.source ? active.source : undefined
     *     resolved.push({ ...a, overriddenBy })
     *   }
     * </pre>
     * 逐条对齐：
     * <ul>
     *   <li><b>(agentType, source) 去重·首现保留</b> — seen Set 首现跳过后续同键（worktree 双副本同源
     *       只记一次，:61-63）</li>
     *   <li><b>overriddenBy = winner.source（source 不同时）</b> — activeMap 取该 agentType 的
     *       winner（byType 折叠结果），同 source 不标（:65-67）</li>
     *   <li><b>返回全量含 winner + shadowed</b> — 不折叠，shadowed agents 也出现在结果里供
     *       agents 端点展示 "(shadowed by ...)"（cli/handlers/agents.ts:50-52）</li>
     * </ul>
     *
     * <p>WHY（B-2 受控差异修复）: 旧 registry 6 组折叠只留 winner、shadowed agents 被静默丢弃
     * （overriddenBy 恒 null、CC shadowed 不可达, unresolved-owner-decisions.md B-2）。本方法以
     * {@link #allAgents}（全量原始列表）为输入还原 CC 完整覆盖关系。
     *
     * @return 全量去重后 agent 列表，每个带 overriddenBy 标注（winner/同 source → null）
     */
    public List<ResolvedAgentDefinition> resolveAgentOverrides() {
        Map<String, AgentDefinition> activeMap = new HashMap<>(byType);
        Set<String> seen = new HashSet<>();
        List<ResolvedAgentDefinition> resolved = new ArrayList<>();
        int shadowed = 0;
        for (AgentDefinition agent : allAgents) {
            String key = agent.agentType() + ":" + agent.source();
            if (!seen.add(key)) {
                continue; // (agentType, source) 去重·首现保留, 对齐 CC agentDisplay.ts:61-63
            }
            AgentDefinition active = activeMap.get(agent.agentType());
            String overriddenBy = (active != null && !active.source().equals(agent.source()))
                ? active.source()
                : null;
            if (overriddenBy != null) {
                shadowed++;
            }
            resolved.add(new ResolvedAgentDefinition(agent, overriddenBy));
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentDefinitionRegistry] resolveAgentOverrides: 全量={} 去重后={} shadowed={}",
                allAgents.size(), resolved.size(), shadowed);
        }
        log.info("[AgentDefinitionRegistry] 解析覆盖关系: 全量={} 去重后={} shadowed={} (对齐 CC resolveAgentOverrides agentDisplay.ts:46-72)",
            allAgents.size(), resolved.size(), shadowed);
        return resolved;
    }

    /**
     * [ODF-C3 返工#2] 全量 map 访问器 · 供 SubagentTool 装配点注入 executor
     * {@code setAdditionalAgentDefinitions}（来源=registry 全量 map，对齐 print.ts:4381
     * SDK request.agents 语义）。
     *
     * <p>返回内部不可变索引引用（volatile 读最新；不可变 Map 可直接共享，无需防御性拷贝）。
     *
     * @return agentType → AgentDefinition 不可变索引
     */
    public Map<String, AgentDefinition> asMap() {
        return byType;
    }

    /**
     * 当前注册总数 · 调试 / 监控用.
     */
    public int size() {
        return byType.size();
    }
}

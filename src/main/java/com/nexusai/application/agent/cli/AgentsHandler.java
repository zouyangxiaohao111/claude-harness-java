package com.nexusai.application.agent.cli;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `claude agents` subcommand 格式化器 · 对齐 CC cli/handlers/agents.ts.
 *
 * <p>L1 语义: 打印配置的 agent 列表. 按 AGENT_SOURCE_GROUPS 顺序分组;
 *            每组按 agent name 排序;overridden 的 agent 显示 "(shadowed by ...)" 前缀;
 *            无 agent → "No agents found.";否则 "{count} active agents\n\n{lines}".
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: format(List&lt;ResolvedAgent&gt;) → String;
 *       ResolvedAgent 4 字段 (agentType/model/memory/source/overriddenBy);
 *       formatAgent 单 agent → "{type} · {model?} · {memory?} memory";
 *       AGENT_SOURCE_GROUPS 顺序保持 CC 一致.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 按 source 分组 → 排序 → 拼接行 →
 *       末尾加 "{N} active agents" + 行块;无 agent → "No agents found.".</li>
 *   <li><b>A3</b>: 状态: per-source (按组聚合);空输入 → "No agents found." (不抛).</li>
 *   <li><b>A4</b>: 空 list → "No agents found.";overridden agent → "(shadowed by {source}) " 前缀;
 *       model=null/memory=null → 不显示该字段 (避免 "null memory").</li>
 *   <li><b>A5</b>: 真实场景 — 3 组 (project/user/local) 每组 1 个 + 1 个 overridden → 完整格式化输出.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/await → 同步 Java (上层 service 已 resolve 完毕,handler 只 format);
 *                    TS array.join('\n') → Java String.join;
 *                    TS `console.log` → 返回 String (上层负责输出,testable).
 *
 * <p><b>D4/去重② 接线（探查 GAP-1/DC-2）</b>：本类此前 0 生产调用方（孤儿死代码），
 * `claude agents` 等价列表端点后端不可达。现注入 {@link SubagentTool}（agentRegistry）并暴露
 * {@code GET /api/agents} 端点 → 返回对齐 CC agents.ts 的 agent 列表文本，使 AgentsHandler
 * 成为生产可达的 agents 端点。
 * 【IMP-SUB-04 REWORK】不再声称"注入 getAgentColor 读侧 D11"——本端点实际只用
 * {@link SubagentTool#listAgents()}；getAgentColor 读侧显式登记为待前端消费（见 SubagentTool）。
 */
@RestController
@RequestMapping("/api/agents")
public final class AgentsHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentsHandler.class);
    private static final String SEP = " · ";

    private final SourceLabelResolver labelResolver;
    private final Function<ResolvedAgent, String> modelDisplayResolver;

    /**
     * 生产数据源（agentRegistry）· 对齐 CC agents.ts {@code getAgentDefinitionsWithOverrides}
     * （registry 已 6 组覆盖合并 = activeAgents）。
     * {@code @Autowired(required=false)}：plain JUnit（无 Spring 容器）缺省 null → 端点
     * 返回 "No agents found."（不 NPE，保测试兼容）。
     */
    @Autowired(required = false)
    private SubagentTool subagentTool;

    /**
     * 无参构造器 · Spring @RestController 装配（web 端点可达，D4/去重②）。
     *
     * <p>默认 labelResolver / modelDisplayResolver 对齐 CC agentDisplay.ts
     * {@code getOverrideSourceLabel}（getSourceDisplayName(...).toLowerCase()）与
     * {@code resolveAgentModelDisplay}（model || getDefaultSubagentModel()）。原 2 参构造器
     * 保留供测试注入自定义 resolver（格式器逻辑不变）。
     */
    public AgentsHandler() {
        this(AgentsHandler::defaultOverrideSourceLabel, AgentsHandler::defaultModelDisplay);
    }

    public AgentsHandler(SourceLabelResolver labelResolver,
                          Function<ResolvedAgent, String> modelDisplayResolver) {
        this.labelResolver = Objects.requireNonNull(labelResolver);
        this.modelDisplayResolver = Objects.requireNonNull(modelDisplayResolver);
    }

    /** CC agentDisplay.ts:90-92 getOverrideSourceLabel — source → 小写展示名。 */
    private static String defaultOverrideSourceLabel(String sourceKey) {
        if (sourceKey == null) {
            return "unknown";
        }
        return switch (sourceKey) {
            case "userSettings" -> "user";
            case "projectSettings" -> "project";
            case "localSettings" -> "local";
            case "policySettings" -> "managed";
            case "plugin" -> "plugin";
            case "flagSettings" -> "flag";
            case "built-in" -> "built-in";
            default -> sourceKey.toLowerCase();
        };
    }

    /**
     * CC agentDisplay.ts:78-84 resolveAgentModelDisplay — model 别名或 'inherit' 兜底。
     * 对齐 CC {@code const model = agent.model || getDefaultSubagentModel()}；
     * getDefaultSubagentModel() 恒返回 'inherit'（CC utils/model/agent.ts:25-27）→
     * model 缺省时必显示 'inherit'（agents.ts:20-30 formatAgent 经 resolveAgentModelDisplay 取 model）。
     * 【R1-WF-C REWORK-1 方案 A】原实现 null/空 model 返回 null → model 段整体省略，与 CC 行为不等价；已修正。
     */
    private static String defaultModelDisplay(ResolvedAgent agent) {
        return agent.model() != null && !agent.model().isEmpty() ? agent.model() : "inherit";
    }

    /**
     * agents 列表端点 · 对齐 CC {@code agentsHandler()}（agents.ts:32-70）。
     *
     * <p>CC 编排：{@code getAgentDefinitionsWithOverrides(cwd) → getActiveAgentsFromList →
     * resolveAgentOverrides → format}。Java 等价：{@link SubagentTool#listAgents()}（registry 已
     * 6 组覆盖合并 = activeAgents）+ 本类 format。
     *
     * <p>WHY（探查 GAP-1/M-1）：Java 侧各组件上游存在但 agentsHandler 入口 0 生产调用方 → 端点
     * 缺失使 `claude agents` 等价命令后端不可达。本端点补上生产入口。
     *
     * @return 对齐 CC agents.ts 文本（"{count} active agents\n\n{lines}" / "No agents found."）
     */
    @GetMapping
    public String agents() {
        if (subagentTool == null) {
            log.warn("[AgentsHandler] SubagentTool 未注入，agents 端点返回空（plain JUnit 无 Spring 容器）");
            return "No agents found.";
        }
        List<AgentDefinition> allAgents = subagentTool.listAgents();
        if (allAgents == null || allAgents.isEmpty()) {
            return "No agents found.";
        }
        List<ResolvedAgent> resolved = allAgents.stream()
            .map(a -> new ResolvedAgent(
                a.agentType(),
                a.model().orElse(null),
                a.memory().orElse(null),
                a.source(),
                null))   // 【IMP-SUB-04 REWORK】显式登记差异（P1-2）：Java registry 已按 6 组覆盖
                         // 合并只保留 winner（active），无"被遮蔽"agent 全集 → overriddenBy 生产路径
                         // 恒 null；CC agents.ts:50-56 的 shadowed 展示在 Java 生产端点不可达
                         // （format() 支持该分支，仅单测覆盖）。
            .toList();
        // AGENT_SOURCE_GROUPS 顺序对齐 CC agentDisplay.ts:24-32（web 后端若磁盘加载缺某 source
        // 该组为空则 format 自动跳过该组；已登记的 source key 全覆盖 CC 清单）
        List<SourceGroup> groups = List.of(
            new SourceGroup("userSettings", "User agents"),
            new SourceGroup("projectSettings", "Project agents"),
            new SourceGroup("localSettings", "Local agents"),
            new SourceGroup("policySettings", "Managed agents"),
            new SourceGroup("plugin", "Plugin agents"),
            new SourceGroup("flagSettings", "CLI arg agents"),
            new SourceGroup("built-in", "Built-in agents"));
        String out = format(resolved, groups);
        if (log.isDebugEnabled()) {
            log.debug("[AgentsHandler] agents 端点输出 {} 个 agent", resolved.size());
        }
        return out;
    }

    /** CC ResolvedAgent 最小子集 — 5 字段 (含 overriddenBy). */
    public record ResolvedAgent(
        String agentType,
        String model,
        String memory,
        String source,
        String overriddenBy   // null 表示 active
    ) {}

    /** CC AGENT_SOURCE_GROUPS — source key + display label. */
    public record SourceGroup(String key, String label) {}

    /** Source label 解析器 (CC getOverrideSourceLabel). */
    @FunctionalInterface
    public interface SourceLabelResolver {
        String resolve(String sourceKey);
    }

    /** CC formatAgent — 单 agent 格式 "{type} · {model?} · {memory?} memory". */
    public String formatAgent(ResolvedAgent agent) {
        List<String> parts = new ArrayList<>(3);
        parts.add(agent.agentType());
        String modelDisplay = modelDisplayResolver.apply(agent);
        if (modelDisplay != null && !modelDisplay.isEmpty()) {
            parts.add(modelDisplay);
        }
        if (agent.memory() != null && !agent.memory().isEmpty()) {
            parts.add(agent.memory() + " memory");
        }
        return String.join(SEP, parts);
    }

    /**
     * CC agentsHandler 输出 — 格式化 agent 列表.
     * 返回 null 表示 "No agents found.";否则返回 "{count} active agents\n\n{lines}".
     */
    public String format(List<ResolvedAgent> resolvedAgents, List<SourceGroup> groups) {
        Objects.requireNonNull(resolvedAgents);
        Objects.requireNonNull(groups);

        // 按 source 分组
        Map<String, List<ResolvedAgent>> bySource = new LinkedHashMap<>();
        for (SourceGroup g : groups) {
            bySource.put(g.key(), new ArrayList<>());
        }
        for (ResolvedAgent a : resolvedAgents) {
            List<ResolvedAgent> bucket = bySource.get(a.source());
            if (bucket != null) bucket.add(a);
        }

        List<String> lines = new ArrayList<>();
        int totalActive = 0;

        for (SourceGroup group : groups) {
            List<ResolvedAgent> groupAgents = bySource.get(group.key());
            if (groupAgents == null || groupAgents.isEmpty()) continue;

            // 排序 (CC compareAgentsByName — 按 agentType 字典序)
            List<ResolvedAgent> sorted = new ArrayList<>(groupAgents);
            sorted.sort(Comparator.comparing(ResolvedAgent::agentType,
                String.CASE_INSENSITIVE_ORDER));

            lines.add(group.label() + ":");
            for (ResolvedAgent agent : sorted) {
                if (agent.overriddenBy() != null) {
                    String winnerLabel = labelResolver.resolve(agent.overriddenBy());
                    lines.add("  (shadowed by " + winnerLabel + ") " + formatAgent(agent));
                } else {
                    lines.add("  " + formatAgent(agent));
                    totalActive++;
                }
            }
            lines.add("");
        }

        if (lines.isEmpty()) {
            return "No agents found.";
        }
        return totalActive + " active agents\n\n" + String.join("\n", lines).replaceAll("\\s+$", "");
    }
}

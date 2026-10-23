package com.nexusai.application.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentDefinition 工具白名单策略 · 主线程与子代理共用的<b>单一判定点</b>。
 *
 * <p><b>WHY（用户 2026-10-23 裁定）</b>：{@code agents/*.md} 的 {@code tools:} 此前只在子代理
 * 路径被消费（{@code SubagentExecutor.resolveAgentTools}），主线程路径完全忽略 —— 专家模式
 * 只换人格提示词、工具不受限。CC 在主线程也应用白名单：
 * {@code REPL.tsx:1089/:2919 → resolveAgentTools(mainThreadAgentDefinition, merged, false, true)}
 * （{@code isMainThread=true} 仅跳过子代理专属的 {@code filterToolsForAgent}，
 * {@code tools} 白名单与 {@code disallowedTools} 照常生效）。
 *
 * <h2>语义（对齐 CC agentToolUtils.ts:122-225）</h2>
 * <ol>
 *   <li><b>通配</b>（{@code tools} 缺省 = CC {@code undefined}，或 {@code ['*']}）→
 *       返回<b>默认池</b>：剔除 {@link ToolScope#EXCLUSIVE} 专属工具 + {@code disallowedTools}。</li>
 *   <li><b>显式列表</b> → 按名单顺序逐个在当轮工具池里精确解析；名单外的工具一律不可见
 *       （白名单是<b>替换</b>，不是叠加）。解析不到的名字记 WARN 后忽略
 *       （CC 记 {@code invalidTools}；常见原因：拼写/改名/被 deny 或会话禁用/feature 未启用）。</li>
 *   <li><b>显式空列表</b>（{@code Optional.of(List.of())}）→ 空工具集（CC：{@code []} ≠ 通配）。</li>
 *   <li>{@code disallowedTools} 无论通配与否都剔除（CC 先在 available 上剔除再解析，语义等价）。</li>
 * </ol>
 *
 * <h2>边界（刻意不做的事）</h2>
 * <ul>
 *   <li><b>只读过滤，不构造/发现工具</b>：名单里的名字必须在<b>当轮已过滤池</b>里存在。
 *       从全局 Spring registry 回补被 deny / 会话禁用 / bare 裁掉的工具会绕过全部权限门
 *       ⇒ ⛔ 永不回补。</li>
 *   <li><b>不处理 MCP 工具</b>：MCP 工具由 {@code assembleToolPool} 每轮刷新进池后再参与过滤；
 *       主线程 agent 的 {@code mcpServers} 不在本类连接（CC 主线程同样不连接）。</li>
 *   <li><b>不解析 {@code Agent(type,...)} 规格</b>：CC 用该形态携带 {@code allowedAgentTypes}
 *       （agentToolUtils.ts:190-204）。本批只做纯工具名，带参规格按工具名精确匹配自然落空
 *       （记 WARN），后续单独接入。</li>
 * </ul>
 */
public final class AgentToolPolicy {

    private static final Logger log = LoggerFactory.getLogger(AgentToolPolicy.class);

    private AgentToolPolicy() {
    }

    /**
     * 应用一次 AgentDefinition 工具策略。
     *
     * @param visibleTools    当轮已过 bare/deny/coordinator/会话禁用的工具池（null → 空列表）
     * @param agentTools      {@code AgentDefinition.tools()}（{@code Optional.empty()} = 通配）
     * @param disallowedTools {@code AgentDefinition.disallowedTools()}（null → 无）
     * @return 最终可见工具列表（保持显式名单顺序；通配时保持池原顺序）
     */
    public static List<Tool> apply(List<Tool> visibleTools,
                                   Optional<List<String>> agentTools,
                                   Optional<List<String>> disallowedTools) {
        if (visibleTools == null || visibleTools.isEmpty()) {
            return List.of();
        }

        Set<String> denied = toNameSet(disallowedTools);
        List<String> requested = agentTools != null ? agentTools.orElse(null) : null;

        if (isWildcard(requested)) {
            List<Tool> out = new ArrayList<>(visibleTools.size());
            for (Tool tool : visibleTools) {
                if (tool == null || tool.name() == null) {
                    continue;
                }
                if (ToolScope.of(tool) == ToolScope.EXCLUSIVE) {
                    continue; // 专属工具不进默认池/通配池
                }
                if (denied.contains(tool.name())) {
                    continue;
                }
                out.add(tool);
            }
            if (log.isDebugEnabled()) {
                long exclusiveHidden = visibleTools.stream()
                        .filter(t -> t != null && ToolScope.of(t) == ToolScope.EXCLUSIVE)
                        .count();
                log.debug("[AgentToolPolicy] 通配分支：池 {} → {}（隐藏专属 {}，disallowed {}）",
                        visibleTools.size(), out.size(), exclusiveHidden, denied.size());
            }
            return out;
        }

        // 显式列表：按名单顺序精确解析（CC agentToolUtils.ts:186-216 by-name 解析）
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : visibleTools) {
            if (tool != null && tool.name() != null) {
                byName.putIfAbsent(tool.name(), tool);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Tool> out = new ArrayList<>(requested.size());
        List<String> missing = new ArrayList<>();
        for (String raw : requested) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            if (denied.contains(name)) {
                if (log.isDebugEnabled()) {
                    log.debug("[AgentToolPolicy] 白名单点名 '{}' 但被 disallowedTools 剔除", name);
                }
                continue;
            }
            Tool tool = byName.get(name);
            if (tool == null) {
                missing.add(name);
                continue;
            }
            if (seen.add(name)) {
                out.add(tool);
            }
        }
        if (!missing.isEmpty()) {
            // 规则十二 fail loud：白名单解析不到是配置错误，必须可见（每轮重复打，直到修正）。
            log.warn("[AgentToolPolicy] AgentDefinition.tools 名单有 {} 个名字在当轮工具池中不存在，已忽略: {} "
                    + "（常见原因：拼写/改名只改一侧/被 deny 或会话禁用/feature 未启用）",
                    missing.size(), missing);
        }
        if (log.isDebugEnabled()) {
            log.debug("[AgentToolPolicy] 显式白名单：请求 {} 项 → 解析 {} 项（缺失 {}，disallowed {}）",
                    requested.size(), out.size(), missing.size(), denied.size());
        }
        return out;
    }

    /**
     * 通配判定 · 对齐 CC agentToolUtils.ts:163-165：{@code tools === undefined || ['*']}。
     *
     * @param requested 显式名单（null = CC undefined）
     * @return true = 通配（走默认池）
     */
    static boolean isWildcard(List<String> requested) {
        if (requested == null) {
            return true;
        }
        return requested.size() == 1 && "*".equals(requested.get(0) == null ? null : requested.get(0).trim());
    }

    private static Set<String> toNameSet(Optional<List<String>> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : names.get()) {
            if (raw != null && !raw.isBlank()) {
                out.add(raw.trim());
            }
        }
        return out;
    }
}

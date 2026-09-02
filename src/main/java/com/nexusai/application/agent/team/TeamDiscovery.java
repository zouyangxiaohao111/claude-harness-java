package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TeamDiscovery · 对齐 CC utils/teamDiscovery.ts.
 *
 * <p>L1 语义: 读取 ~/.claude/teams/{name}.json + 转换为 UI-ready 的 TeammateStatus 列表,
 * 排除 team-lead 自身;hiddenPaneIds 标记不可见队友。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: TeamFile record + Member record + TeammateStatus record + getTeammateStatuses(TeamFile)→List&lt;TeammateStatus&gt;</li>
 *   <li><b>A2 Golden Trace</b>: 缺 teamFile.members → [];skip team-lead;isActive=undefined → true (=running);hiddenPaneIds 标记 isHidden</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: null teamFile → [];null members → []</li>
 *   <li><b>A5 业务场景</b>: Teams footer UI 列出队友,可见性由 hiddenPaneIds 控制</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS array.filter+map → Java ArrayList + streams;
 * TS Set.has() → Java HashSet;TS nullish coalescing → Java Objects.requireNonNullElse。
 *
 * <p><b>激活</b>（team-frontend-channel）：供 {@link TeamStatusPublisher#toDto} 装配
 * {@code teammateStatuses}（TeamController GET detail / 状态 STOMP 事件 team 字段）；
 * {@link #fromConfigJson} 为 config.json → TeamFile 的 null-safe 解析补充。
 */
public final class TeamDiscovery {

    private TeamDiscovery() {}

    public record BackendTypeField(String value) {} // Stub for PaneBackendType (real subclassing beyond scope)
    public record TeammateStatus(
        String name,
        String agentId,
        String agentType,
        String model,
        String prompt,
        String status,            // 'running' | 'idle' | 'unknown'
        String color,
        String tmuxPaneId,
        String cwd,
        String worktreePath,
        Boolean isHidden,
        BackendTypeField backendType,
        String mode) {}

    public record Member(
        String name,
        String agentId,
        String agentType,
        String model,
        String prompt,
        Boolean isActive,
        String color,
        String tmuxPaneId,
        String cwd,
        String worktreePath,
        String backendType,
        String mode) {}

    public record TeamFile(List<Member> members, List<String> hiddenPaneIds) {}

    /** STATUS constant for active (running) members. */
    public static final String STATUS_RUNNING = "running";
    /** STATUS constant for inactive (idle) members. */
    public static final String STATUS_IDLE = "idle";

    /**
     * Compute teammate statuses from a team file. Pure function — does not perform I/O.
     *
     * @param teamFile team record (caller-wired; in CC this is loaded from disk)
     * @return filtered list of TeammateStatus records (team-lead excluded)
     */
    public static List<TeammateStatus> getTeammateStatuses(TeamFile teamFile) {
        if (teamFile == null || teamFile.members() == null) {
            return new ArrayList<>();
        }
        Set<String> hiddenPaneIds = new HashSet<>();
        if (teamFile.hiddenPaneIds() != null) {
            hiddenPaneIds.addAll(teamFile.hiddenPaneIds());
        }
        List<TeammateStatus> statuses = new ArrayList<>();
        for (Member member : teamFile.members()) {
            if ("team-lead".equals(member.name())) continue;
            boolean isActive = member.isActive() == null || member.isActive();
            String status = isActive ? STATUS_RUNNING : STATUS_IDLE;
            statuses.add(new TeammateStatus(
                member.name(),
                member.agentId(),
                member.agentType(),
                member.model(),
                member.prompt(),
                status,
                member.color(),
                member.tmuxPaneId(),
                member.cwd(),
                member.worktreePath(),
                hiddenPaneIds.contains(member.tmuxPaneId()) ? Boolean.TRUE : Boolean.FALSE,
                member.backendType() != null ? new BackendTypeField(member.backendType()) : null,
                member.mode()));
        }
        return statuses;
    }

    /**
     * config.json 文本 → TeamFile（members + hiddenPaneIds）· null-safe 解析（缺失/解析失败 → null）。
     *
     * <p>供 {@link com.nexusai.application.agent.team.TeamStatusPublisher#toDto} 装配 teammateStatuses。
     * 忽略 config 顶层 name/leadAgentId 等非 TeamFile 键（Jackson 不严格，成员缺省字段留 null）。
     *
     * @param configJson config.json 全文
     * @return TeamFile；缺失/非对象/解析失败 → null
     */
    public static TeamFile fromConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(configJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            List<Member> members = new ArrayList<>();
            JsonNode membersArr = root.get("members");
            if (membersArr != null && membersArr.isArray()) {
                for (JsonNode m : membersArr) {
                    if (m == null || !m.isObject()) {
                        continue;
                    }
                    JsonNode isActive = m.get("isActive");
                    members.add(new Member(
                        m.path("name").asText(null),
                        m.path("agentId").asText(null),
                        m.path("agentType").asText(null),
                        m.path("model").asText(null),
                        m.path("prompt").asText(null),
                        isActive == null || isActive.isMissingNode() || isActive.isNull()
                            ? null : isActive.asBoolean(),
                        m.path("color").asText(null),
                        m.path("tmuxPaneId").asText(null),
                        m.path("cwd").asText(null),
                        m.path("worktreePath").asText(null),
                        m.path("backendType").asText(null),
                        m.path("mode").asText(null)));
                }
            }
            List<String> hidden = new ArrayList<>();
            JsonNode hiddenArr = root.get("hiddenPaneIds");
            if (hiddenArr != null && hiddenArr.isArray()) {
                hiddenArr.forEach(n -> hidden.add(n.asText()));
            }
            return new TeamFile(members, hidden);
        } catch (Exception e) {
            return null;
        }
    }
}

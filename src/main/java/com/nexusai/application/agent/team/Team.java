package com.nexusai.application.agent.team;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * s15 Agent Teams 数据模型 — 对齐 CC teamHelpers.ts:684 TeamConfig.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>Team 包含 lead + members (含 agentId + name + agentType + color)</li>
 *   <li>每个 member 有 inbox (jsonl 文件存储)</li>
 *   <li>team 注册到 {@link AgentMessageBus} 全局单例</li>
 * </ul>
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
public final class Team {

    /** team 唯一名 (例如 "research-team") — 对齐 CC teamName */
    private final String name;

    /** Lead agent ID (例如 "lead@research-team") */
    private final String leadAgentId;

    /** 团队成员列表 (含 Lead) */
    private final Map<String, Member> members = new LinkedHashMap<>();

    /** team 创建时间戳 */
    private final Instant createdAt;

    public Team(String name, String leadAgentId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("team name must not be blank");
        }
        if (leadAgentId == null || leadAgentId.isBlank()) {
            throw new IllegalArgumentException("leadAgentId must not be blank");
        }
        this.name = name;
        this.leadAgentId = leadAgentId;
        this.createdAt = Instant.now();
        members.put(leadAgentId, new Member(leadAgentId, "lead", "lead", "blue", Instant.now()));
    }

    public String name() {
        return name;
    }

    public String leadAgentId() {
        return leadAgentId;
    }

    public Map<String, Member> members() {
        return Map.copyOf(members);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void addMember(String agentId, String agentName, String agentType, String color) {
        if (members.containsKey(agentId)) {
            throw new IllegalArgumentException("agent already in team: " + agentId);
        }
        members.put(agentId, new Member(agentId, agentName, agentType, color, Instant.now()));
    }

    public void removeMember(String agentId) {
        if (Objects.equals(agentId, leadAgentId)) {
            throw new IllegalArgumentException("cannot remove lead from team");
        }
        members.remove(agentId);
    }

    public boolean hasMember(String agentId) {
        return members.containsKey(agentId);
    }

    public int size() {
        return members.size();
    }

    public List<String> memberAgentIds() {
        return List.copyOf(members.keySet());
    }

    /**
     * 团队成员 — 对齐 CC teamHelpers.ts:684 Member.
     */
    public record Member(
            String agentId,
            String agentName,
            String agentType,
            String color,
            Instant joinedAt
    ) {
        public Member {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId is required");
            }
            if (agentName == null || agentName.isBlank()) {
                throw new IllegalArgumentException("agentName is required");
            }
            if (agentType == null || agentType.isBlank()) {
                throw new IllegalArgumentException("agentType is required");
            }
            color = (color == null || color.isBlank()) ? "blue" : color;
            joinedAt = (joinedAt == null) ? Instant.now() : joinedAt;
        }
    }

    /** 工厂: 用 UUID 生成 agentId — 对齐 CC agentId 格式 "{name}@{teamName}". */
    public static String generateAgentId(String teamName, String agentName) {
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        return agentName + "-" + shortId + "@" + teamName;
    }
}
package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.eventbus.ws.TeamStatusEvent;
import com.nexusai.model.team.dto.TeamDto;
import com.nexusai.model.team.dto.TeamMemberDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Team 状态推送单点 · 对齐 LeaderPermissionConfirmBridge 注入模式（{@code @Autowired(required=false)}
 * 字段 + setter）。
 *
 * <p><b>WHY 单点（plan D9）</b>：TeamCreateTool / TeamDeleteTool（LLM 工具路径也发，面板与工具双源
 * 同步）+ TeamController（成员端点）共用本类，避免每个调用方各自 convertAndSend。
 *
 * <p>{@link #toDto}：config.json → TeamDto（无 team / 解析失败 → null），控制器 / 状态事件 team 字段共用；
 * {@link #publish}：推 {@code /topic/sessions/{leadSessionId}/team-status}（leadSessionId 从 config 反查；
 * 反查失败 → warn 跳过，不回退全局 topic 防跨会话泄漏；ws / teamHelpers 缺失 → debug 跳过，不抛）。
 */
@Component
public class TeamStatusPublisher {

    private static final Logger log = LoggerFactory.getLogger(TeamStatusPublisher.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** STOMP 推送模板 · required=false 容错（无 WebSocket 场景 → 跳过推送，保留 null 语义）。 */
    @Autowired(required = false)
    private SimpMessagingTemplate ws;

    /** team 配置文件读取 · required=false 容错（无 bean → 无法装配 TeamDto，跳过推送）。 */
    @Autowired(required = false)
    private TeamHelpers teamHelpers;

    /**
     * config.json → TeamDto · 无 team / 读取失败 / 解析失败 → null（fail-soft，不抛）。
     *
     * <p>字段装配（对齐 TeamCreateTool.buildConfigJson 落盘 + TeamDiscovery.getTeammateStatuses）：
     * 顶层 name/description/createdAt/leadAgentId/leadSessionId + members 数组映射 TeamMemberDto
     * （缺省字段留 null）+ teammateStatuses（TeamDiscovery 纯函数，排除 team-lead）。
     */
    public TeamDto toDto(String teamName) {
        if (teamHelpers == null || teamName == null || teamName.isBlank()) {
            return null;
        }
        String config = teamHelpers.readConfig(teamName);
        if (config == null) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(config);
            if (root == null || !root.isObject()) {
                return null;
            }
            List<TeamMemberDto> members = new ArrayList<>();
            JsonNode arr = root.path("members");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    if (m == null || !m.isObject()) {
                        continue;
                    }
                    JsonNode isActive = m.get("isActive");
                    members.add(new TeamMemberDto(
                        m.path("agentId").asText(null),
                        m.path("name").asText(null),
                        m.path("agentType").asText(null),
                        m.path("model").asText(null),
                        m.path("color").asText(null),
                        m.path("mode").asText(null),
                        isActive == null || isActive.isMissingNode() || isActive.isNull()
                            ? null : isActive.asBoolean(),
                        m.hasNonNull("joinedAt") ? m.path("joinedAt").asLong() : null,
                        m.path("tmuxPaneId").asText(null),
                        m.path("cwd").asText(null),
                        m.path("backendType").asText(null)));
                }
            }
            TeamDiscovery.TeamFile tf = TeamDiscovery.fromConfigJson(config);
            List<TeamDiscovery.TeammateStatus> statuses = TeamDiscovery.getTeammateStatuses(tf);
            return new TeamDto(
                root.path("name").asText(null),
                root.path("description").asText(null),
                root.hasNonNull("createdAt") ? root.path("createdAt").asLong() : null,
                root.path("leadAgentId").asText(null),
                root.path("leadSessionId").asText(null),
                members, statuses);
        } catch (Exception e) {
            log.warn("[TeamStatusPublisher] toDto 解析 config.json 失败 team={}: {}", teamName, e.toString());
            return null;
        }
    }

    /**
     * 推 /topic/sessions/{leadSessionId}/team-status（leadSessionId 内部从 config 反查；
     * 反查失败 → warn 跳过，不回退全局 topic 防跨会话泄漏）。
     * 供 config 仍在的场景（created / member_joined / member_left）调用。
     */
    public void publish(String teamName, String eventType) {
        publish(teamName, (teamHelpers != null ? teamHelpers.leadSessionId(teamName) : null), eventType);
    }

    /**
     * 推 /topic/sessions/{leadSessionId}/team-status（显式 leadSessionId · 供 config 已删的
     * deleted 场景调用，见 TeamDeleteTool 在 cleanup 前预解析）。
     *
     * <p>ws / teamHelpers 缺失 / teamName 空 → debug 跳过；leadSessionId 空 → warn 跳过
     * （不回退全局 team topic，防多会话互收泄漏）。REST 与 LLM 工具双路径共用。
     *
     * @param teamName      目标 team
     * @param leadSessionId 创建者（lead）会话 ID；config 反查失败 → null（跳过推送）
     * @param eventType     created | deleted | member_joined | member_left
     */
    public void publish(String teamName, String leadSessionId, String eventType) {
        if (ws == null || teamHelpers == null || teamName == null || teamName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamStatusPublisher] STOMP 未注入/无 team，跳过状态推送 event={} team={}", eventType, teamName);
            }
            return;
        }
        if (leadSessionId == null || leadSessionId.isBlank()) {
            log.warn("[TeamStatusPublisher] 反查 leadSessionId 为空，跳过状态推送 event={} team={}"
                    + "（不回退全局 topic，防跨会话泄漏）", eventType, teamName);
            return;
        }
        try {
            ws.convertAndSend("/topic/sessions/" + leadSessionId + "/team-status",
                TeamStatusEvent.of(teamName, eventType, toDto(teamName)));
            log.info("[TeamStatusPublisher] 已推送 team 状态 topic=/topic/sessions/{}/team-status event={} team={}",
                leadSessionId, eventType, teamName);
        } catch (Exception e) {
            log.warn("[TeamStatusPublisher] 推送失败（不阻断）: {}", e.toString());
        }
    }

    /** 测试/接线用 setter（ws · STOMP 推送模板；测试直构无 Spring 上下文时注入 mock）。 */
    public void setWs(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    /** 测试/接线用 setter（teamHelpers · config.json 读取）。 */
    public void setTeamHelpers(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }
}

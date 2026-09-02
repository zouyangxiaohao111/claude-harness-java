package com.nexusai.apis.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeamStatusPublisher;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.TeamCreateTool;
import com.nexusai.application.agent.tool.impl.TeamDeleteTool;
import com.nexusai.common.RequestContext;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.infra.util.SwarmConstants;
import com.nexusai.model.team.dto.SpawnMemberRequest;
import com.nexusai.model.team.dto.TeamCreateRequest;
import com.nexusai.model.team.dto.TeamDto;
import com.nexusai.model.team.dto.TeamMemberDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Team REST 族 · 对齐 nexusai/docs/team-panel-design.md §3.3（前端设计契约权威）。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/v1/teams —— 枚举 {configHome}/teams 下含 config.json 的子目录 → List&lt;TeamDto&gt;；</li>
 *   <li>GET /api/v1/teams/{teamName} —— 详情（config.json 的 team + members + teammateStatuses）；</li>
 *   <li>POST /api/v1/teams —— 创建（复用 TeamCreateTool.execute，每 leader 一 team 守卫/自动换名/落库）；</li>
 *   <li>DELETE /api/v1/teams/{teamName} —— 解散（复用 TeamDeleteTool.deleteTeamByName，活跃成员 → 409）；</li>
 *   <li>POST/DELETE /api/v1/teams/{teamName}/members[/{agentId}] —— 成员 join/leave（可选，发布状态）；</li>
 *   <li>GET /api/v1/teams/{teamName}/inbox + POST /inbox/read —— B1 轮询兜底 + 已读回执。</li>
 * </ul>
 *
 * <p>状态变化 STOMP：创建/解散/成员加入退出 → TeamStatusPublisher 推
 * {@code /topic/teams/{teamName}/status}（design doc §3.3）。REST 风格对齐 McpServerController
 * （@RestController + @RequestMapping + @Autowired 字段注入）；sessionId 解析对齐 MemoryController
 * （query ?sessionId= → MDC 兜底）。错误契约：ValidationException→400 / ConflictException→409 /
 * NotFoundException→404（GlobalExceptionHandler 转 RFC 7807）。
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private static final Logger log = LoggerFactory.getLogger(TeamController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TeamHelpers teamHelpers;
    @Autowired private TeamStatusPublisher teamStatusPublisher;
    // 无 bean（测试直构）→ 端点 404/500 语义外容错（@Autowired(required=false)）
    @Autowired(required = false) private TeamCreateTool teamCreateTool;
    @Autowired(required = false) private TeamDeleteTool teamDeleteTool;
    // [perm-timeout #136] in-process teammate 注册表访问入口（registry() 惰性创建）· 无 bean（测试直构）→ kill 端点 404
    @Autowired(required = false) private SpawnInProcess spawnInProcess;

    /**
     * sessionId 解析：query ?sessionId= → MDC 兜底；写 MDC（MemoryController:121-139 同款）。
     * 返回 null 表示无会话上下文（create 建 team 不落会话列，fail-soft）。
     */
    private String resolveSessionId(String sessionIdParam) {
        String sid = (sessionIdParam != null && !sessionIdParam.isBlank())
                ? sessionIdParam : RequestContext.sessionId();
        if (sid != null) {
            RequestContext.setSession(sid);
        }
        return sid;
    }

    /**
     * [team-panel-backend-bugfix] 从 team config.json 读 leadSessionId（public
     * {@link TeamHelpers#readConfig} 返回 JSON 字符串，ENOENT → null；config 由创建时
     * {@code TeamCreateTool.buildConfigJson} 写入 leadSessionId）。REST 解散前端默认不带
     * sessionId → 反查该列兜底清 sessions.team_context，防残留。
     *
     * @param teamName 团队名
     * @return config.leadSessionId（字符串）；config 缺失 / 解析失败 → null
     */
    private String leadSessionIdFromConfig(String teamName) {
        try {
            String cfg = teamHelpers.readConfig(teamName);
            if (cfg == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(cfg);
            return root.path("leadSessionId").asText(null);
        } catch (Exception e) {
            log.warn("[TeamController] 反查 team={} leadSessionId 失败: {}", teamName, e.getMessage());
            return null;
        }
    }

    /**
     * GET /api/v1/teams → 枚举 {configHome}/teams 下含 config.json 的子目录 → List&lt;TeamDto&gt;。
     *
     * <p>[team-panel-backend-bugfix2] 会话隔离（用户拍板「不同会话看到不同 team」）：带 sessionId →
     * 仅返回 {@code config.leadSessionId == sessionId} 的 team（对齐 multi-session-vs-cc-single-session
     * 铁律 + STOMP 事件按 leadSessionId 推会话级 topic）；sessionId 缺失 → fail-loud 空列表
     * （不回退全局，防跨会话名册泄漏）。
     */
    @GetMapping
    public List<TeamDto> list(@RequestParam(required = false) String sessionId) {
        String sid = resolveSessionId(sessionId);
        Path teamsDir = TeammateMailbox.getTeamsDir();
        List<TeamDto> out = new ArrayList<>();
        if (Files.isDirectory(teamsDir)) {
            try (Stream<Path> s = Files.list(teamsDir)) {
                s.filter(Files::isDirectory)
                        .filter(dir -> Files.exists(dir.resolve("config.json")))
                        .forEach(dir -> {
                            String teamName = dir.getFileName().toString();
                            if (sid != null && !sid.isBlank()) {
                                String lead = leadSessionIdFromConfig(teamName);
                                if (!sid.equals(lead)) {
                                    return;
                                }
                            }
                            TeamDto dto = teamStatusPublisher.toDto(teamName);
                            if (dto != null) {
                                out.add(dto);
                            }
                        });
            } catch (IOException e) {
                log.warn("[TeamController] list 枚举失败: {}", e.toString());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[TeamController] GET /api/v1/teams → {} teams (session={})", out.size(), sid);
        }
        return out;
    }

    /**
     * GET /api/v1/teams/{teamName} → TeamDto（name/members/teammateStatuses）；config 缺失 → 404。
     *
     * <p>[team-panel-backend-bugfix2] 会话隔离：带 sessionId → config.leadSessionId 不匹配当前会话
     * → 404（防跨会话名册泄漏，对齐 list() 会话过滤）；无 sessionId → 不拦截（兼容无会话上下文调用）。
     */
    @GetMapping("/{teamName}")
    public TeamDto get(@PathVariable String teamName, @RequestParam(required = false) String sessionId) {
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            String lead = leadSessionIdFromConfig(teamName);
            if (lead == null || !sessionId.equals(lead)) {
                throw new NotFoundException("Team " + teamName + " not found");
            }
        }
        TeamDto dto = teamStatusPublisher.toDto(teamName);
        if (dto == null) {
            throw new NotFoundException("Team " + teamName + " config not readable");
        }
        if (log.isDebugEnabled()) {
            log.debug("[TeamController] GET /api/v1/teams/{} → name={} members={}",
                teamName, dto.name(), dto.members() == null ? 0 : dto.members().size());
        }
        return dto;
    }

    /**
     * POST /api/v1/teams → 复用 TeamCreateTool.execute（每 leader 一 team 守卫 / 自动换名 / 落库 /
     * 会话列 teamContext）。"created" 状态由 TeamCreateTool 内部发布（REST 与 LLM 工具双路径同步）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDto create(@RequestBody TeamCreateRequest req) {
        if (teamCreateTool == null) {
            throw new NotFoundException("TeamCreateTool bean unavailable");
        }
        if (req == null || req.teamName() == null || req.teamName().isBlank()) {
            throw new ValidationException("teamName is required");
        }
        String sessionId = resolveSessionId(req.sessionId());
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("team_name", req.teamName());
        if (req.description() != null) {
            input.put("description", req.description());
        }
        if (req.agentType() != null) {
            input.put("agent_type", req.agentType());
        }
        ToolUseBlock block = new ToolUseBlock(UUID.randomUUID().toString(), TeamCreateTool.NAME, input);
        // [session-id-short] sessionId 已 short，直传（不再 canonicalUuid 派生 UUID）
        ToolUseContext ctx = (sessionId != null)
                ? ToolUseContext.of(UUID.randomUUID(), sessionId)
                : null;
        AgentToolResult<?> result = teamCreateTool.execute(block, ctx);
        String raw = String.valueOf(result.data());
        JsonNode dataNode = parseToolResult(result.data());
        String teamName = dataNode != null ? dataNode.path("team_name").asText(null) : null;
        if (teamName == null || teamName.isBlank()) {
            // 工具错误（validation / 每 leader 一 team 守卫）→ 4xx 语义映射
            if (raw.contains("Already leading")) {
                throw new ConflictException(raw);
            }
            throw new ValidationException(raw);
        }
        if (log.isInfoEnabled()) {
            log.info("[TeamController] POST /api/v1/teams → created team={} sessionId={}", teamName, sessionId);
        }
        return teamStatusPublisher.toDto(teamName);
    }

    /**
     * DELETE /api/v1/teams/{teamName} → TeamDeleteTool.deleteTeamByName（活跃成员守卫拒删 → 409）。
     * 成功：cleanup 目录 + 清会话 teamContext 列 + 发布 "deleted" 状态。
     */
    @DeleteMapping("/{teamName}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String teamName,
                                                      @RequestParam(required = false) String sessionId) {
        if (teamDeleteTool == null) {
            throw new NotFoundException("TeamDeleteTool bean unavailable");
        }
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        String sid = resolveSessionId(sessionId);
        if (sid == null) {
            // [team-panel-backend-bugfix] 前端解散默认不带 sessionId → 从 team config 的
            //   leadSessionId 反查（创建时 buildConfigJson 已写该列），否则 sessions.team_context
            //   残留 → 前端 TeamPanel 门控（teamContext 非 null）永不消失。
            sid = leadSessionIdFromConfig(teamName);
        }
        AgentToolResult<?> result = teamDeleteTool.deleteTeamByName(teamName, sid, 0, "rest-" + UUID.randomUUID());
        JsonNode dataNode = parseToolResult(result.data());
        boolean ok = dataNode != null && dataNode.path("success").asBoolean(false);
        if (ok) {
            if (log.isInfoEnabled()) {
                log.info("[TeamController] DELETE /api/v1/teams/{} → 已解散 sessionId={}", teamName, sid);
            }
            return ResponseEntity.ok(Map.of("success", true));
        }
        String message = dataNode != null ? dataNode.path("message").asText("")
                : String.valueOf(result.data());
        log.warn("[TeamController] DELETE /api/v1/teams/{} → 拒删: {}", teamName, message);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new java.util.HashMap<>(Map.of("success", false, "message", message)));
    }

    /** (可选) POST /api/v1/teams/{teamName}/members → TeamHelpers.appendTeamMember + 发布 member_joined。 */
    @PostMapping("/{teamName}/members")
    public TeamDto addMember(@PathVariable String teamName, @RequestBody TeamMemberDto member) {
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        if (member == null || member.agentId() == null || member.agentId().isBlank()
                || member.name() == null || member.name().isBlank()) {
            throw new ValidationException("agentId and name are required");
        }
        boolean added = teamHelpers.appendTeamMember(teamName, new TeamHelpers.TeamMemberRef(
            member.agentId(), member.name(), member.agentType(), member.model(), null,
            member.color(), false, member.tmuxPaneId(), member.cwd(), member.backendType()));
        if (!added) {
            throw new ValidationException("Team " + teamName + " has no members array");
        }
        teamStatusPublisher.publish(teamName, "member_joined");
        if (log.isInfoEnabled()) {
            log.info("[TeamController] POST /api/v1/teams/{}/members → 成员加入 agentId={} name={}",
                teamName, member.agentId(), member.name());
        }
        return teamStatusPublisher.toDto(teamName);
    }

    /**
     * [team-panel-backend] POST /api/v1/teams/{teamName}/members/spawn → 前端「启动成员」→ spawn
     * 真实 in-process 子代理（CC 无此 REST，造端点包装 SpawnInProcess.spawnInProcessTeammate，
     * spawnInProcess.ts:104-216）。name 必填（agentId = formatAgentId(name, teamName) = name@team，
     * spawnInProcess.ts:112）；缺 name 后端只 fork 不入队，前端已校验。
     *
     * <p>流程：校验 bean/team/name → 构造 InProcessSpawnConfig（subagentType 空 → 回落
     * "general-purpose"；color=null / planModeRequired=false / model=null；cwd =
     * CwdResolution.getCwd(null)，兜底 user.dir）→ SpawnContext(null, "rest-"+UUID) →
     * spawnInProcessTeammate → 失败 → 409；成功 → 显式 publish member_joined（对齐 addMember:286，
     * spawnInProcessTeammate 内部仅 appendTeamMember 成功时推，端点兜底）+ toDto 返回含新成员。
     * 失败契约：spawnInProcess 未接线 / team 不存在 → 404；name 缺失 → 400（ValidationException）；
     * spawn 失败 → 409（ConflictException）。
     */
    @PostMapping("/{teamName}/members/spawn")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDto spawnMember(@PathVariable String teamName, @RequestBody SpawnMemberRequest req) {
        if (spawnInProcess == null) {
            throw new NotFoundException("SpawnInProcess bean unavailable");
        }
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ValidationException("name is required (agentId = name@team)");
        }
        // cwd 解析：CwdResolution.getCwd 恒非 null（L4 user.dir 兜底），再补一层硬兜底防回归
        String cwd = CwdResolution.getCwd(null);
        if (cwd == null || cwd.isBlank()) {
            cwd = System.getProperty("user.dir", ".");
        }
        SpawnInProcess.InProcessSpawnConfig config = new SpawnInProcess.InProcessSpawnConfig(
            req.name(), teamName, req.prompt(), null /*color*/, false /*planModeRequired*/,
            null /*model*/, req.subagentType() != null ? req.subagentType() : "general-purpose", cwd);
        // [team-cc-align fixPlan5] parentSessionId = team config.leadSessionId（short 直键，
        //   对齐 session-id-short）；原 null → SpawnInProcess:227-228 兜底 TaskService.getTaskListId()
        //   （rest 线程 = 进程级 UUID）→ 成员 parentSessionId/attachment/outboundSink 错位。
        String leadSessionId = leadSessionIdFromConfig(teamName);
        SpawnInProcess.SpawnContext ctx = new SpawnInProcess.SpawnContext(leadSessionId, "rest-" + UUID.randomUUID());
        SpawnInProcess.InProcessSpawnOutput out = spawnInProcess.spawnInProcessTeammate(config, ctx);
        if (!out.success()) {
            throw new ConflictException(out.error() != null ? out.error() : "spawn failed");
        }
        // [team-panel-backend-bugfix2] member_joined 已由 SpawnInProcess.spawnInProcessTeammate 内部
        //   appendTeamMember 成功时统一发布（SpawnInProcess.java:340-342，Agent 工具 + REST 双路径
        //   一致）；端点不再重复 publish，防双发（与 killMember 去重同理）。
        if (log.isInfoEnabled()) {
            log.info("[TeamController] POST /api/v1/teams/{}/members/spawn → 已启动成员 name={} "
                + "agentId={} subagentType={}（member_joined 由 SpawnInProcess 统一发布）", teamName,
                req.name(), SpawnInProcess.formatAgentId(req.name(), teamName),
                req.subagentType() != null ? req.subagentType() : "general-purpose");
        }
        return teamStatusPublisher.toDto(teamName);
    }

    /** (可选) DELETE /api/v1/teams/{teamName}/members/{agentId} → removeMemberByAgentId + 发布 member_left。 */
    @DeleteMapping("/{teamName}/members/{agentId}")
    public TeamDto removeMember(@PathVariable String teamName, @PathVariable String agentId) {
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        if (!teamHelpers.removeMemberByAgentId(teamName, agentId)) {
            throw new NotFoundException("Member " + agentId + " not in team " + teamName);
        }
        teamStatusPublisher.publish(teamName, "member_left");
        if (log.isInfoEnabled()) {
            log.info("[TeamController] DELETE /api/v1/teams/{}/members/{} → 成员退出", teamName, agentId);
        }
        return teamStatusPublisher.toDto(teamName);
    }

    /**
     * [perm-timeout #136] POST /api/v1/teams/{teamName}/members/{agentId}/kill → 停止运行中
     * in-process teammate。对齐 CC InProcessTeammateTask.tsx:27-30 kill →
     * killInProcessTeammate（spawnInProcess.ts:227-328）。
     *
     * <p>agentId = name@team（registry.findByAgentId 匹配 {@code identity.agentId} 全形；
     * AutonomousAgentLoop.kill() 内部守卫 running + abort + removeMemberByAgentId + 3s evict）；
     * 成功后发布 {@code member_left} 状态（对齐 removeMember 端点）。失败契约：
     * spawnInProcess 未接线 / team 不存在 / agentId 未找到 → 404；kill 非 running → 409（fail loud）。
     */
    @PostMapping("/{teamName}/members/{agentId}/kill")
    public ResponseEntity<Map<String, Object>> killMember(@PathVariable String teamName,
                                                          @PathVariable String agentId) {
        if (spawnInProcess == null) {
            throw new NotFoundException("SpawnInProcess bean unavailable");
        }
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        Optional<AutonomousAgentLoop> loop = spawnInProcess.registry().findByAgentId(agentId);
        if (loop.isEmpty()) {
            throw new NotFoundException("Teammate " + agentId + " not running in team " + teamName);
        }
        boolean killed = loop.get().kill();
        if (!killed) {
            if (log.isWarnEnabled()) {
                log.warn("[TeamController] killMember: teammate {} 非 running（已终态/任务缺失）, 拒绝 kill",
                    agentId);
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new java.util.HashMap<>(Map.of("success", false, "message", "teammate is not running")));
        }
        // [team-panel-backend-bugfix2] member_left 已由 AutonomousAgentLoop.kill() 内部统一发布
        //   （所有 kill 路径一致：REST + BackgroundTaskRunner 旁路）；此处不再重复 publish，防双发。
        if (log.isInfoEnabled()) {
            log.info("[TeamController] POST /api/v1/teams/{}/members/{}/kill → 已停止 teammate "
                + "（member_left 由 AutonomousAgentLoop.kill 统一发布）", teamName, agentId);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** GET /api/v1/teams/{teamName}/inbox?recipient=team-lead → List&lt;TeammateMessage&gt;（B1 轮询兜底/初始未读态）。 */
    @GetMapping("/{teamName}/inbox")
    public List<TeammateMailbox.TeammateMessage> inbox(
            @PathVariable String teamName,
            @RequestParam(defaultValue = SwarmConstants.TEAM_LEAD_NAME) String recipient) {
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        List<TeammateMailbox.TeammateMessage> messages = TeammateMailbox.readMailbox(recipient, teamName);
        if (log.isDebugEnabled()) {
            log.debug("[TeamController] GET /api/v1/teams/{}/inbox recipient={} → {} messages",
                teamName, recipient, messages.size());
        }
        return messages;
    }

    /** POST /api/v1/teams/{teamName}/inbox/read?recipient=team-lead → 标 read=true（B1 已读回执）。 */
    @PostMapping("/{teamName}/inbox/read")
    public Map<String, Object> markRead(
            @PathVariable String teamName,
            @RequestParam(defaultValue = SwarmConstants.TEAM_LEAD_NAME) String recipient) {
        if (!teamHelpers.teamExists(teamName)) {
            throw new NotFoundException("Team " + teamName + " not found");
        }
        TeammateMailbox.markMessagesAsRead(recipient, teamName);
        if (log.isInfoEnabled()) {
            log.info("[TeamController] POST /api/v1/teams/{}/inbox/read recipient={} → 已标已读", teamName, recipient);
        }
        return Map.of("success", true);
    }

    /** 解析 AgentToolResult.data 为 JSON（工具输出 JSON 串）· 解析失败 → null。 */
    private static JsonNode parseToolResult(Object data) {
        if (data == null) {
            return null;
        }
        try {
            return JSON.readTree(String.valueOf(data));
        } catch (Exception e) {
            return null;
        }
    }
}

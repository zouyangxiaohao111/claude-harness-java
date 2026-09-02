package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.util.SwarmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TeamDeleteTool · 对齐 CC TeamDeleteTool.ts（空 schema + 从 context 取 team 名 + 活跃成员守卫 + 输出对齐）。
 *
 * <p>IMP-G1 对齐要点（CC 唯一事实来源，TeamDeleteTool.ts + teamHelpers.ts）：
 * <ul>
 *   <li><b>空输入 schema</b>（⊕-05）：{@code inputSchema = z.strictObject({})}（TeamDeleteTool.ts:21），
 *       team 名从 {@code appState.teamContext?.teamName} 取（:74），删除 Java-only 必填 team_name 输入；</li>
 *   <li><b>活跃成员守卫</b>（:76-99）：读 teamFile，过滤非 lead 成员（name !== 'team-lead'），
 *       再过滤 {@code isActive !== false}；有活跃成员 → 返回 {@code {success:false, message, team_name}}
 *       （提示先 requestShutdown），不删除；</li>
 *   <li><b>输出对齐</b>（⊕-06）：输出仅 {@code {success, message, team_name}}（TeamDeleteTool.ts:24-28
 *       Output 类型），删除 Java-only existed 字段；</li>
 *   <li><b>清理语义</b>：cleanupTeamDirectories（team + tasks 目录，:101）+ unregisterTeamForSessionCleanup
 *       （:103）+ clearLeaderTeamName（:109）+ setAppState 清 teamContext/inbox（:118-124）。</li>
 * </ul>
 */
@Component
public class TeamDeleteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TeamDeleteTool.class);

    public static final String NAME = "TeamDelete";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TeamHelpers teamHelpers;

    /**
     * [A4] 会话级 teamContext 读写 · 对齐 CC appState.teamContext（TeamCreateTool.ts:201-216）的
     * 会话列承载（sessions.team_context）。可选注入（规则 8，构造器不动）：未注入（测试/手动直构）
     * → 回退 ctx.getAppState()（同轮内存态，不破坏既有测试构造）。
     */
    @Autowired(required = false)
    private SessionService sessionService;

    /**
     * [Batch2 T1] in-process teammate 终止通道 · 对齐 CC TeamDeleteTool.ts:119-125
     * getInProcessBackend().terminate。{@code @Autowired(required=false)} 容错（无 bean / 测试直构 →
     * null → requestShutdown 返回 false，活跃成员走「无法终止 → Cannot cleanup」守卫）。
     */
    @Autowired(required = false)
    private SpawnInProcess spawnInProcess;

    /**
     * [team-frontend-channel] Team 状态推送单点 · 解散成功后发布 "deleted" 状态
     * （/topic/sessions/{leadSessionId}/team-status）· REST（TeamController DELETE）与 LLM 工具
     * 双路径都发；leadSessionId 在 cleanup 前预解析（stomp-lead-session 方案 3，config 删后反查恒失败）。
     * 可选注入（规则 8，构造器不动）：未注入（测试/手动直构）→ 跳过推送，不破坏既有构造。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher;

    @Autowired
    public TeamDeleteTool(TeamHelpers teamHelpers) {
        this.teamHelpers = teamHelpers;
    }

    /** 测试/接线用 setter（spawnInProcess · terminate 通道；镜像 SpawnInProcess.setTeamHelpers 模式）。 */
    public void setSpawnInProcess(SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
    }

    /** 测试/接线用 setter（teamStatusPublisher · deleted 状态推送）· 镜像 setSpawnInProcess 模式。 */
    public void setTeamStatusPublisher(com.nexusai.application.agent.team.TeamStatusPublisher p) {
        this.teamStatusPublisher = p;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 搜索提示 · 对齐 CC TeamDeleteTool.ts:34 searchHint。 */
    @Override
    public String searchHint() {
        return "disband a swarm team and clean up";
    }

    /** 用户可见名 · 对齐 CC TeamDeleteTool.ts:38-40 userFacingName() → ''（UI 不显示）。 */
    @Override
    public String userFacingName() {
        return "";
    }

    @Override
    public String description() {
        // 对齐 CC TeamDeleteTool.ts:50-52 description()
        return "Clean up team and task directories when the swarm is complete";
    }

    /** 是否延迟执行 · 对齐 CC TeamDeleteTool.ts:36 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        // 对齐 CC TeamDeleteTool.ts:29-40 z.strictObject({wait_ms: z.number().min(0).max(30000).optional()})
        // （Batch2 T1）：wait_ms 允许 leader 等待活跃 teammate 确认 shutdown 后再清理。
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode waitMs = props.putObject("wait_ms");
        waitMs.put("type", "integer");
        waitMs.put("minimum", 0);
        waitMs.put("maximum", 30000);
        waitMs.put("description",
            "Optional time to wait for active teammates to acknowledge shutdown before cleanup.");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        // CC TeamDeleteTool.ts:46-47 isEnabled() { return isAgentSwarmsEnabled() } ·
        // 与 TeamCreateTool 同门（tools.ts:228-230），未开启 agent-swarms 时也不进 LLM schema。
        boolean enabled = TaskSystemConfig.isAgentSwarmsEnabled();
        if (log.isDebugEnabled()) {
            log.debug("[TeamDeleteTool] isEnabled() = {}（isAgentSwarmsEnabled 门控，CC agentSwarmsEnabled.ts:24-44）", enabled);
        }
        return enabled;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // CC TeamDeleteTool.ts:74 teamName = appState.teamContext?.teamName
        String teamName = teamNameFromContext(ctx);
        int waitMs = readWaitMs(call);

        if (teamName != null) {
            // [team-frontend-channel] REST（TeamController DELETE）与 LLM 工具共用删除主流程
            String sessionIdStr = (ctx != null && ctx.sessionId() != null)
                    ? ctx.sessionId() : null;
            AgentToolResult<?> result = deleteTeamByName(teamName, sessionIdStr, waitMs, call.id());
            // CC :215-222 清 appState teamContext + inbox（会话列清已在 deleteTeamByName 内按 sessionId 承担）
            clearTeamContext(ctx);
            return result;
        }

        // CC :215-222 清 teamContext + inbox
        clearTeamContext(ctx);

        return ToolResult.success(call.id(),
                buildOutput(true, "No team name found, nothing to clean up", null));
    }

    /**
     * 显式 team 名删除 · REST（TeamController DELETE）与 LLM 工具 {@link #execute} 共用（plan D4：
     * 避免双份逻辑，工具 = 单一事实源）。
     *
     * <p>活跃成员守卫语义对齐 CC TeamDeleteTool.ts:104-195：活跃非 lead 成员 → 逐成员 terminate
     * （mailbox shutdown_request）→ {@code waitMs &gt; 0 && requested 非空} → 轮询等待 inactive →
     * latest re-check 仍活跃 → {@code success:false} 拒删。REST 传 {@code waitMs=0} → 立即 re-check，
     * 仍活跃 → 拒删（"wait_ms=0 语义"）。
     *
     * <p>删除成功：cleanupTeamDirectories（team + tasks 目录，CC :199）+ unregisterTeamForSessionCleanup
     * + clearLeaderTeamName + 清会话 teamContext 列（sessionId 非空时，REST 路径无 ctx 由此承担）+
     * 发布 "deleted" 状态（前端面板双源同步）。
     *
     * @param teamName  目标 team 名
     * @param sessionId 会话标识（REST 传 MDC/query sessionId；工具传 ctx.sessionId() 串），可空 → 不清会话列
     * @param waitMs    shutdown 等待毫秒（0-30000，REST 传 0）
     * @param requestId 工具调用 ID（REST 传 "rest-{uuid}"；工具传 call.id()）
     */
    public AgentToolResult<?> deleteTeamByName(String teamName, String sessionId, int waitMs, String requestId) {
        // CC :104-196 读 team 配置检查活跃成员 → terminate + wait_ms 轮询
        List<String> activeMembers = activeNonLeadMembers(teamName);
        if (!activeMembers.isEmpty()) {
            // 1) 逐活跃成员 terminate（CC :115-143）——in-process → mailbox shutdown_request
            //   （对齐 InProcessBackend.ts:229-258），由 teammate loop 读 mailbox 决策批准/拒绝
            List<String> requested = new ArrayList<>();
            for (String member : activeMembers) {
                if (requestShutdown(member, teamName)) {
                    requested.add(member);
                }
            }
            // 2) waitMs > 0 && requested 非空 → 轮询等待 inactive（CC :144-157）
            if (waitMs > 0 && !requested.isEmpty()) {
                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline) {
                    sleepQuietly(Math.min(250, Math.max(0, deadline - System.currentTimeMillis())));
                    if (activeNonLeadMembers(teamName).isEmpty()) {
                        break;
                    }
                }
                List<String> stillActive = activeNonLeadMembers(teamName);
                if (!stillActive.isEmpty()) {
                    String names = String.join(", ", stillActive);
                    log.warn("[TeamDeleteTool] team={} 轮询 {}ms 后仍有 {} 个活跃成员（{}），拒绝清理",
                            teamName, waitMs, stillActive.size(), names);
                    return ToolResult.success(requestId,
                            buildOutput(false,
                                    "Shutdown requested for active teammate(s): " + String.join(", ", requested)
                                            + ". Cleanup is still blocked after waiting " + waitMs + "ms: "
                                            + names + ".",
                                    teamName));
                }
            }
            // 3) latest re-check（CC :176-195）：waitMs==0 或轮询超时后仍活跃 → success:false
            List<String> latestActive = activeNonLeadMembers(teamName);
            if (!latestActive.isEmpty()) {
                String names = String.join(", ", latestActive);
                String message = requested.isEmpty()
                        ? "Cannot cleanup team with " + latestActive.size()
                                + " active member(s): " + names
                                + ". Use requestShutdown to gracefully terminate teammates first."
                        : "Shutdown requested for active teammate(s): " + String.join(", ", requested)
                                + ". Cleanup is blocked until they exit: " + names + ".";
                log.warn("[TeamDeleteTool] team={} 存在 {} 个活跃成员（{}），拒绝清理（CC TeamDeleteTool.ts:185-193）",
                        teamName, latestActive.size(), names);
                return ToolResult.success(requestId,
                        buildOutput(false, message, teamName));
            }
        }

        // [stomp-lead-session 方案 3] cleanup 前预解析 leadSessionId（deleted 事件时序坑：
        //   cleanupTeamDirectories 删 config.json → 之后反查恒失败 → "deleted" 事件丢失 → 前端面板
        //   不消失；须在 cleanup 之前预解析，供 clearTeamContext 与 deleted 推送共用）。显式传入的
        //   sessionId（REST 已由 TeamController 反查/config 兜底）优先，否则私有反查。
        String effectiveLeadSessionId = (sessionId != null && !sessionId.isBlank())
                ? sessionId : leadSessionIdFromConfig(teamName);

        // CC :199-207 cleanupTeamDirectories + unregisterTeamForSessionCleanup + clearLeaderTeamName
        teamHelpers.cleanupTeamDirectories(teamName);
        teamHelpers.unregisterTeamForSessionCleanup(teamName);
        TaskService.clearLeaderTeamName();
        // [team-frontend-channel] REST 路径无 ctx —— 会话列 teamContext 清理由 sessionId 显式承担
        // [team-panel-backend-bugfix 加固] sessionId 为空（REST 解散未传 / 旧数据无 ctx）→
        //   从 team config.leadSessionId 反查兜底清列（复用 effectiveLeadSessionId，防残留）。
        if (sessionService != null) {
            if (effectiveLeadSessionId != null && !effectiveLeadSessionId.isBlank()) {
                sessionService.clearTeamContext(effectiveLeadSessionId);
            }
        }
        // [stomp-lead-session 方案 3] 解散成功 → 发布 "deleted" 状态（REST 与 LLM 工具双路径同步）·
        //   显式传入预解析 leadSessionId（config 已删 → 走 3 参重载，防反查失败丢事件）
        if (teamStatusPublisher != null) {
            teamStatusPublisher.publish(teamName, effectiveLeadSessionId, "deleted");
        }
        if (log.isDebugEnabled()) {
            log.debug("[TeamDeleteTool] tengu_team_deleted 事件（CC TeamDeleteTool.ts:209-212）team={}", teamName);
        }
        log.info("[TeamDeleteTool] deleted team={}（cleanupTeamDirectories 含 team + tasks 目录，CC TeamDeleteTool.ts:199）", teamName);
        return ToolResult.success(requestId,
                buildOutput(true, "Cleaned up directories and worktrees for team \"" + teamName + "\"", teamName));
    }

    /**
     * in-process teammate 优雅终止 · 对齐 CC TeamDeleteTool.ts:119-125
     * {@code getInProcessBackend().terminate(member.agentId, 'Team cleanup requested by team lead')}
     * → InProcessBackend.ts:229-258 createShutdownRequestMessage → writeToMailbox。
     *
     * <p>经 mailbox shutdown_request 交模型决策（对齐 InProcessBackend.ts:189-197 注释优雅关闭，
     * 非 registry.kill 强制终止）；teammate 侧由
     * {@link AutonomousAgentLoop#waitForNextPromptOrShutdown} 读 mailbox 决策批准/拒绝。
     *
     * @return true 已发出 shutdown 请求；spawnInProcess 未注入 / 找不到存活 loop → false
     */
    private boolean requestShutdown(String memberName, String teamName) {
        if (spawnInProcess == null) {
            return false;
        }
        Optional<AutonomousAgentLoop> loop = spawnInProcess.registry().findByAgentName(memberName);
        if (loop.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamDeleteTool] requestShutdown: 未找到存活 loop agent={}", memberName);
            }
            return false;
        }
        String requestId = "shutdown-" + memberName + "-" + System.currentTimeMillis();
        TeammateMailbox.ShutdownRequestMessage shutdown = TeammateMailbox.createShutdownRequestMessage(
                requestId, SwarmConstants.TEAM_LEAD_NAME, "Team cleanup requested by team lead");
        TeammateMailbox.writeToMailbox(memberName,
                TeammateMailbox.TeammateMessage.of(SwarmConstants.TEAM_LEAD_NAME,
                        TeammateMailbox.toCompactJson(shutdown), TeammateMailbox.isoNow(), null),
                teamName);
        log.info("[TeamDeleteTool] 已发送 shutdown_request agent={} team={} requestId={}",
                memberName, teamName, requestId);
        return true;
    }

    /**
     * 读 wait_ms · 对齐 CC TeamDeleteTool.ts:144 {@code input.wait_ms ?? 0}；
     * 缺失 / 非整数 / 越界（0-30000）→ log.warn + 用 0（不抛）。
     */
    private int readWaitMs(ToolUseBlock call) {
        JsonNode input = call != null ? call.input() : null;
        if (input == null || !input.has("wait_ms") || input.get("wait_ms").isNull()) {
            return 0;
        }
        JsonNode w = input.get("wait_ms");
        if (!w.canConvertToInt()) {
            log.warn("[TeamDeleteTool] wait_ms 非法（非整数），用 0: {}", w.asText());
            return 0;
        }
        int value = w.asInt();
        if (value < 0 || value > 30000) {
            log.warn("[TeamDeleteTool] wait_ms 越界（0-30000），用 0: {}", value);
            return 0;
        }
        return value;
    }

    /** 轮询 sleep · 对齐 CC sleep(min(250, deadline - now))；中断恢复 interrupt 标志。 */
    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 非 lead 且活跃的成员列表 · 对齐 CC TeamDeleteTool.ts:81-88：
     * {@code nonLeadMembers = members.filter(m => m.name !== TEAM_LEAD_NAME)}；
     * {@code activeMembers = nonLeadMembers.filter(m => m.isActive !== false)}。
     *
     * @return 活跃非 lead 成员 name 列表；team 不存在 / 解析失败 → 空（无守卫拦截）
     */
    private List<String> activeNonLeadMembers(String teamName) {
        String config = teamHelpers.readConfig(teamName);
        if (config == null) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(config);
            if (root == null || !root.isObject() || !root.has("members") || !root.get("members").isArray()) {
                return List.of();
            }
            List<String> active = new ArrayList<>();
            for (JsonNode member : root.get("members")) {
                if (member == null || !member.isObject()) {
                    continue;
                }
                // CC :82 排除 team lead 自身（name === TEAM_LEAD_NAME）
                if (TeamCreateTool.TEAM_LEAD_NAME.equals(member.path("name").asText())) {
                    continue;
                }
                // CC :87 isActive !== false —— 未标 inactive（idle/dead）即视为活跃
                JsonNode isActive = member.get("isActive");
                if (isActive == null || isActive.isMissingNode() || isActive.isNull() || isActive.asBoolean()) {
                    active.add(member.path("name").asText());
                }
            }
            return active;
        } catch (Exception e) {
            log.warn("[TeamDeleteTool] 解析 config.json 活跃成员失败 team={}: {}", teamName, e.getMessage());
            return List.of();
        }
    }

    /**
     * [team-panel-backend-bugfix 加固] 从 team config.json 读 leadSessionId（public
     * {@link TeamHelpers#readConfig} 返回 JSON 字符串，ENOENT → null）。sessionId 缺失
     * （REST 解散未传 / 旧数据无 ctx）时兜底清 sessions.team_context，防残留。
     *
     * @param teamName 团队名
     * @return config.leadSessionId（字符串）；config 缺失 / 解析失败 → null
     */
    private String leadSessionIdFromConfig(String teamName) {
        try {
            String config = teamHelpers.readConfig(teamName);
            if (config == null) {
                return null;
            }
            JsonNode root = MAPPER.readTree(config);
            return root != null && root.isObject()
                    ? root.path("leadSessionId").asText(null) : null;
        } catch (Exception e) {
            log.warn("[TeamDeleteTool] 反查 team={} leadSessionId 失败: {}", teamName, e.getMessage());
            return null;
        }
    }

    /** 读取 teamContext.teamName · 对齐 CC TeamDeleteTool.ts:74。
     *  [A4] store 优先（sessions.team_context 列，跨工具/回合持久），appState 回退（同轮内存态）。 */
    private String teamNameFromContext(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (sessionService != null && ctx.sessionId() != null) {
            Map<String, Object> teamContext = sessionService.getTeamContext(ctx.sessionId());
            if (teamContext != null) {
                Object name = teamContext.get(TeamCreateTool.TEAM_CONTEXT_NAME);
                if (name instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        if (ctx.getAppState() == null) {
            return null;
        }
        Map<String, Object> appState = ctx.getAppState().apply(null);
        if (appState == null) {
            return null;
        }
        Object tc = appState.get(TeamCreateTool.APPSTATE_TEAM_CONTEXT);
        if (!(tc instanceof Map<?, ?> teamContext)) {
            return null;
        }
        Object name = teamContext.get(TeamCreateTool.TEAM_CONTEXT_NAME);
        return (name instanceof String s && !s.isBlank()) ? s : null;
    }

    /** 清 teamContext（会话列 + appState）+ inbox · 对齐 CC TeamDeleteTool.ts:118-124 setAppState。 */
    private void clearTeamContext(ToolUseContext ctx) {
        // [A4] 会话级清除（sessions.team_context 列）· 跨工具/回合生效
        if (sessionService != null && ctx != null && ctx.sessionId() != null) {
            sessionService.clearTeamContext(ctx.sessionId());
            if (log.isDebugEnabled()) {
                log.debug("[TeamDeleteTool] clearTeamContext: 已清会话列 team_context session={}（A4 会话级化）",
                        ctx.sessionId());
            }
        }
        if (ctx == null || ctx.setAppState() == null) {
            return;
        }
        ctx.setAppState().accept(prev -> {
            Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
            next.remove(TeamCreateTool.APPSTATE_TEAM_CONTEXT);
            // CC :121-123 inbox: { messages: [] }
            next.put("inbox", Map.of("messages", List.of()));
            if (log.isDebugEnabled()) {
                log.debug("[TeamDeleteTool] setAppState 清空 teamContext + inbox（CC TeamDeleteTool.ts:118-124）");
            }
            return next;
        });
    }

    /** 输出 · 对齐 CC TeamDeleteTool.ts:24-28 Output {success, message, team_name?}。 */
    private String buildOutput(boolean success, String message, String teamName) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("message", message);
        if (teamName != null) {
            map.put("team_name", teamName);
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        map.forEach((k, v) -> {
            if (v instanceof Boolean b) {
                node.put(k, b);
            } else {
                node.put(k, String.valueOf(v));
            }
        });
        return node.toString();
    }
}

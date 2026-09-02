package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TeamCreateTool · 对齐 CC TeamCreateTool.ts（工具输入/输出契约 + team 文件持久化）。
 *
 * <p>IMP-G1 对齐要点（CC 唯一事实来源，TeamCreateTool.ts + teamHelpers.ts + agentId.ts）：
 * <ul>
 *   <li><b>team-lead@ 前缀</b>（⊕-01）：{@code leadAgentId = formatAgentId(TEAM_LEAD_NAME, finalTeamName)}
 *       = {@code team-lead@{team}}（TeamCreateTool.ts:146 + agentId.ts:38-40 + swarm/constants.ts:1
 *       {@code TEAM_LEAD_NAME='team-lead'}）。删除 Java-only {@code lead_agent_id} 输入（CC schema 无此键），
 *       补 CC {@code agent_type} 可选输入（TeamCreateTool.ts:41-48）；</li>
 *   <li><b>重名自动换名</b>（⊕-02）：{@code generateUniqueTeamName} —— team 已存在时返回
 *       {@code generateWordSlug()} 新名，不失败（TeamCreateTool.ts:64-72,143）；</li>
 *   <li><b>去字符集校验</b>（⊕-03）：删除 {@code TeamHelpers.isValidTeamName} 拦截（CC 无字符集校验，
 *       TeamCreateTool.ts:96-105 仅校验 team_name 非空）；</li>
 *   <li><b>输出对齐</b>（⊕-04）：输出仅 {@code {team_name, team_file_path, lead_agent_id}}（TeamCreateTool.ts:52-56
 *       Output 类型），删除 member_count/created_at/message；</li>
 *   <li><b>每 leader 一 team 守卫</b>（CC :132-140）：从 {@code appState.teamContext?.teamName} 读现有 team，
 *       已领导则拒绝（对齐 CC 文案）；</li>
 *   <li><b>TeamFile 结构补齐</b>：config.json 落盘含 leadSessionId（:162）+ members[0].model/agentType
 *       （:163-174），并调用 registerTeamForSessionCleanup（:180）+ resetTaskList/ensureTasksDir/
 *       setLeaderTeamName（:184-191）+ setAppState(teamContext)（:194-212）。</li>
 * </ul>
 *
 * <p>word slug（generateWordSlug）：CC {@code words.ts:783-791} {@code adjective-verb-noun} 格式，
 * 本类内嵌 CC words.ts 词库的紧凑子集（数据面，非行为面；随机名称无需与 CC 逐词一致）。
 */
@Component
public class TeamCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TeamCreateTool.class);

    public static final String NAME = "TeamCreate";

    /** CC swarm/constants.ts:1 TEAM_LEAD_NAME · lead agent 确定性名字段。 */
    public static final String TEAM_LEAD_NAME = "team-lead";

    /** appState teamContext 键 · 对齐 CC {@code appState.teamContext}（TeamCreateTool.ts:134）。 */
    static final String APPSTATE_TEAM_CONTEXT = "teamContext";
    static final String TEAM_CONTEXT_NAME = "teamName";
    static final String TEAM_CONTEXT_FILE_PATH = "teamFilePath";
    static final String TEAM_CONTEXT_LEAD = "leadAgentId";

    private final TeamHelpers teamHelpers;
    private final TaskService taskService;

    /**
     * [A4] 会话级 teamContext 读写 · 对齐 CC appState.teamContext（TeamCreateTool.ts:201-216
     * setAppState）的会话列承载（sessions.team_context）。可选注入（规则 8，构造器不动）：
     * 未注入（测试/手动直构）→ 回退 ctx.getAppState()（同轮内存态，不破坏既有测试构造）。
     */
    @Autowired(required = false)
    private SessionService sessionService;

    /**
     * [team-frontend-channel] Team 状态推送单点 · 建 team 成功后发布 "created" 状态
     * （/topic/teams/{team}/status）· REST 与 LLM 工具双路径都发（前端面板感知 LLM 建 team）。
     * 可选注入（规则 8，构造器不动）：未注入（测试/手动直构）→ 跳过推送，不破坏既有构造。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.team.TeamStatusPublisher teamStatusPublisher;

    @Autowired
    public TeamCreateTool(TeamHelpers teamHelpers, TaskService taskService) {
        this.teamHelpers = teamHelpers;
        this.taskService = taskService;
    }

    /** 测试/接线用 setter（teamStatusPublisher · created 状态推送）· 对齐 SessionService setter 模式。 */
    public void setTeamStatusPublisher(com.nexusai.application.agent.team.TeamStatusPublisher p) {
        this.teamStatusPublisher = p;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 搜索提示 · 对齐 CC TeamCreateTool.ts:76 searchHint。 */
    @Override
    public String searchHint() {
        return "create a multi-agent swarm team";
    }

    /** 用户可见名 · 对齐 CC TeamCreateTool.ts:80-82 userFacingName() → ''（UI 不显示）。 */
    @Override
    public String userFacingName() {
        return "";
    }

    @Override
    public String description() {
        // 对齐 CC TeamCreateTool.ts:107-109 description()
        return "Create a new team for coordinating multiple agents";
    }

    /** 是否延迟执行 · 对齐 CC TeamCreateTool.ts:78 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        // 对齐 CC TeamCreateTool.ts:37-49 z.strictObject({team_name, description?, agent_type?})
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode teamName = props.putObject("team_name");
        teamName.put("type", "string");
        teamName.put("description", "Name for the new team to create.");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "Team description/purpose.");

        ObjectNode agentType = props.putObject("agent_type");
        agentType.put("type", "string");
        agentType.put("description",
                "Type/role of the team lead (e.g., \"researcher\", \"test-runner\"). "
                        + "Used for team file and inter-agent coordination.");

        schema.putArray("required").add("team_name");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isEnabled() {
        // CC TeamCreateTool.ts:88-89 isEnabled() { return isAgentSwarmsEnabled() } ·
        // 未开启 agent-swarms 时 TeamCreateTool 不进 LLM schema（tools.ts:228 门控起始点）。
        boolean enabled = TaskSystemConfig.isAgentSwarmsEnabled();
        if (log.isDebugEnabled()) {
            log.debug("[TeamCreateTool] isEnabled() = {}（isAgentSwarmsEnabled 门控，CC agentSwarmsEnabled.ts:24-44）", enabled);
        }
        return enabled;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String teamName = readString(input, "team_name");
        if (teamName == null || teamName.isBlank()) {
            // 对齐 CC TeamCreateTool.ts:96-105 validateInput：team_name 缺失/空白 → errorCode 9
            return ToolResult.error(call.id(), "team_name is required for TeamCreate");
        }
        String description = readString(input, "description");
        String agentType = readString(input, "agent_type");

        try {
            // CC :132-140 每 leader 一 team 守卫：appState.teamContext?.teamName 已存在则拒绝
            String existingTeam = teamNameFromContext(ctx);
            if (existingTeam != null) {
                log.warn("[TeamCreateTool] 已领导 team={}，拒绝再建（CC TeamCreateTool.ts:136-140 每 leader 一 team 守卫）", existingTeam);
                return ToolResult.error(call.id(),
                        "Already leading team \"" + existingTeam
                                + "\". A leader can only manage one team at a time. "
                                + "Use TeamDelete to end the current team before creating a new one.");
            }

            // CC :142-143 generateUniqueTeamName —— 重名自动换名，不失败
            String finalTeamName = generateUniqueTeamName(teamName);
            // CC :146-147 + agentId.ts:38-40 formatAgentId(TEAM_LEAD_NAME, teamName) = team-lead@{team}
            String leadAgentId = TEAM_LEAD_NAME + "@" + finalTeamName;
            // CC :147 leadAgentType = agent_type || TEAM_LEAD_NAME
            String leadAgentType = (agentType != null && !agentType.isBlank()) ? agentType : TEAM_LEAD_NAME;
            // [A1-FIX] 会话级化归因键：[session-id-short] ctx.sessionId() 已 short 直键，
            //   SessionService.delete 侧传 raw 'sess-xxx' 同键，cleanupSessionTeams(sessionId)
            //   只清本会话 teams（防跨会话误删，探查 A1）。
            //   ctx.sessionId() 为 null（无法归因）时回退 TaskService.getTaskListId() —— 工具执行线程
            //   RequestContext MDC 有 sessionId（getTaskListId() 优先级 6），仍可归因到真实会话；
            //   teammate 线程回退 teamName。保证归因键确定性，不落随机 UUID（否则 cleanupSessionTeams
            //   永无法匹配，孤儿 config.json/inboxes/tasks 泄漏延续，finding R2-3 行为回归）。
            //   真正无会话（MDC 空 → 进程级 UUID 兜底）时亦确定性登记，孤儿清理交由 Batch4 A4/A5。
            String cleanupKey = (ctx != null && ctx.sessionId() != null)
                    ? ctx.sessionId() : TaskService.getTaskListId();
            // CC :162 leadSessionId = getSessionId()（team discovery 用实际 session id）——
            //   与清理归因键同源，避免 config.json 落随机 UUID 与清理侧键不一致。
            //   [merge-align F1/F2 修正] 原回退 UUID.randomUUID() 违反注释「不落随机 UUID」：
            //   cleanupSessionTeams 无法匹配随机键 → 孤儿 config 泄漏。cleanupKey 经
            //   TaskService.getTaskListId() 有兜底（MDC/进程级 UUID），恒非 null/blank，
            //   此处回退仅防御性，用确定性占位（空串）而非随机 UUID，保证可清理性。
            String leadSessionId = (cleanupKey != null && !cleanupKey.isBlank())
                    ? cleanupKey : "";

            String teamFilePath = teamHelpers.configPath(finalTeamName).toString();

            // 写 team 配置文件（对齐 CC writeTeamFileAsync → {configHome}/teams/{team}/config.json）
            teamHelpers.writeConfig(finalTeamName,
                    buildConfigJson(finalTeamName, leadAgentId, description, leadAgentType, leadSessionId));
            // CC :178-180 registerTeamForSessionCleanup —— 未显式 TeamDelete 时 session 结束清理（gh-32730）
            // [A3] 会话级化：以 ctx.sessionId() 归一入桶（SessionService.delete → cleanupSessionTeams(id) 只清本会话）
            String sessionIdStr = (ctx != null && ctx.sessionId() != null)
                    ? ctx.sessionId() : null;
            teamHelpers.registerTeamForSessionCleanup(sessionIdStr, finalTeamName);

            // CC :184-191 resetTaskList + ensureTasksDir + setLeaderTeamName（Team = Project = TaskList）
            String taskListId = TeamHelpers.sanitizeName(finalTeamName);
            taskService.resetTaskList(taskListId);
            try {
                taskService.ensureTasksDir(taskListId);
            } catch (IOException e) {
                log.warn("[TeamCreateTool] ensureTasksDir 失败 team={} taskListId={}: {}",
                        finalTeamName, taskListId, e.getMessage());
            }
            TaskService.setLeaderTeamName(taskListId);

            // CC :194-212 setAppState(teamContext) —— 供 TeamDelete/SendMessage 读当前 team 上下文；
            //   leadSessionId 一并落 teamContext（stomp-lead-session 方案 3：前端需经
            //   SessionDto.teamContext.leadSessionId 订阅 /topic/sessions/{leadSessionId}/team-...）
            setTeamContext(ctx, finalTeamName, teamFilePath, leadAgentId, leadAgentType, leadSessionId);

            if (log.isDebugEnabled()) {
                log.debug("[TeamCreateTool] tengu_team_created 事件（CC TeamCreateTool.ts:214-222）team={} lead={} leadAgentType={}",
                        finalTeamName, leadAgentId, leadAgentType);
            }
            log.info("[TeamCreateTool] created team={} lead={}（team-lead@ 前缀，CC TeamCreateTool.ts:146）",
                    finalTeamName, leadAgentId);
            // [team-frontend-channel] 建 team 成功 → 发布 "created" 状态（REST 与 LLM 工具双路径同步）
            if (teamStatusPublisher != null) {
                teamStatusPublisher.publish(finalTeamName, "created");
            }
            return ToolResult.success(call.id(), buildOutput(finalTeamName, teamFilePath, leadAgentId));
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            log.warn("[TeamCreateTool] failed: {}", e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    private String buildConfigJson(String teamName, String leadAgentId, String description,
                                   String leadAgentType, String leadSessionId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        // config.json 落盘结构对齐 CC TeamFile（teamHelpers.ts:64-90）+ TeamCreateTool.ts:157-175：
        // name/description/createdAt/leadAgentId/leadSessionId/members[]（lead 成员带 model/agentType）
        node.put("name", teamName);
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        }
        node.put("createdAt", System.currentTimeMillis());
        // CC TeamCreateTool.ts:161 leadAgentId（camelCase，config.json 落盘字段名）
        node.put("leadAgentId", leadAgentId);
        // CC TeamCreateTool.ts:162 leadSessionId = getSessionId()
        node.put("leadSessionId", leadSessionId);
        // members[0] = lead（对齐 CC TeamCreateTool.ts:163-174，TEAM_LEAD_NAME = 'team-lead'）
        ArrayNode members = node.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", leadAgentId);
        lead.put("name", TEAM_LEAD_NAME);
        lead.put("agentType", leadAgentType);
        // CC :149-153 leadModel = parseUserSpecifiedModel(mainLoopModelForSession ?? mainLoopModel ?? default)
        // Java 侧 model 解析不在本工具职责（LLM provider 层），落盘不写该字段（消费方 TeamDiscovery 不读 model）
        lead.put("joinedAt", System.currentTimeMillis());
        lead.put("tmuxPaneId", "");
        // cwd-align-ext：team lead cwd = 会话 cwd（CC TeamCreateTool.ts:171 cwd: getCwd()）；
        //   无 sessionId 回落 user.dir（方案 1，零行为变化）。
        lead.put("cwd", leadCwd());
        lead.putArray("subscriptions");
        if (log.isDebugEnabled()) {
            log.debug("[TeamCreateTool] buildConfigJson name={} leadAgentId={} leadSessionId={} members=1 createdAt={}",
                    teamName, leadAgentId, leadSessionId, node.get("createdAt").asLong());
        }
        return node.toString();
    }

    /**
     * team lead cwd · 对齐 CC TeamCreateTool.ts:171/:207 {@code cwd: getCwd()}。
     *
     * <p>buildConfigJson / setTeamContext 均在工具执行线程（RequestContext MDC 有 sessionId）；
     * 无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private static String leadCwd() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }

    private String readString(JsonNode input, String key) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return null;
        }
        return input.get(key).asText();
    }

    /**
     * 生成唯一 team 名 · 对齐 CC TeamCreateTool.ts:64-72 generateUniqueTeamName：
     * team 不存在 → 使用传入名；已存在 → generateWordSlug() 新名（不失败）。
     */
    private String generateUniqueTeamName(String providedName) {
        if (!teamHelpers.teamExists(providedName)) {
            return providedName;
        }
        String generated = generateWordSlug();
        if (log.isDebugEnabled()) {
            log.debug("[TeamCreateTool] team {} 已存在，自动换名 → {}（CC TeamCreateTool.ts:64-72 generateUniqueTeamName）",
                    providedName, generated);
        }
        return generated;
    }

    /**
     * 随机 word slug · 对齐 CC {@code words.ts:783-791 generateWordSlug}：
     * {@code `${adjective}-${verb}-${noun}`}（如 "gleaming-brewing-phoenix"）。
     * 词库为 CC words.ts 词条的紧凑子集（数据面非行为面；随机名无需逐词一致）。
     */
    private static String generateWordSlug() {
        String adjective = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String verb = VERBS[RANDOM.nextInt(VERBS.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        return adjective + "-" + verb + "-" + noun;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── CC words.ts 词库子集（ADJECTIVES 219 取 44 / NOUNS 409 取 46 / VERBS 109 取 55，等距抽样）──
    private static final String[] ADJECTIVES = {
        "abundant", "clever", "deep", "fancy", "graceful", "joyful", "lucky", "merry",
        "playful", "quirky", "silly", "snuggly", "sprightly", "tender", "valiant",
        "whimsical", "zany", "buzzing", "crystalline", "ethereal", "fluttering",
        "glimmering", "groovy", "jaunty", "mossy", "purring", "shimmying", "ticklish",
        "wobbly", "agile", "compiled", "curried", "eager", "expressive", "hashed",
        "inherited", "linked", "nested", "piped", "refactored", "scalable", "staged",
        "synchronous", "unified",
    };

    private static final String[] VERBS = {
        "baking", "booping", "brewing", "chasing", "coalescing", "cooking", "crunching",
        "dancing", "discovering", "dreaming", "enchanting", "finding", "fluttering",
        "forging", "gathering", "gliding", "growing", "herding", "hopping", "humming",
        "inventing", "juggling", "kindling", "launching", "mapping", "meandering",
        "moseying", "napping", "noodling", "painting", "petting", "pondering", "prancing",
        "puzzling", "riding", "rolling", "scribbling", "shimmying", "skipping", "snacking",
        "snuggling", "sparking", "splashing", "squishing", "stirring", "swimming",
        "tickling", "toasting", "twirling", "wandering", "weaving", "wibbling", "wishing",
        "wondering", "zooming",
    };

    private static final String[] NOUNS = {
        "aurora", "clover", "dusk", "forest", "island", "moonbeam", "planet", "shore",
        "stream", "valley", "bear", "crane", "falcon", "hedgehog", "llama", "otter",
        "platypus", "raven", "squid", "whale", "beacon", "castle", "dream", "globe",
        "kettle", "map", "noodle", "pillow", "pumpkin", "scroll", "swing", "treasure",
        "whistle", "bachman", "cerf", "cray", "feigenbaum", "hellman", "kahan", "lecun",
        "minsky", "pascal", "rivest", "stallman", "thompson", "wilkinson",
    };

    /** 读取 teamContext.teamName · 对齐 CC {@code appState.teamContext?.teamName}（TeamCreateTool.ts:134）。
     *  [A4] store 优先（sessions.team_context 列，跨工具/回合持久），appState 回退（同轮内存态）。 */
    private String teamNameFromContext(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (sessionService != null && ctx.sessionId() != null) {
            Map<String, Object> teamContext = sessionService.getTeamContext(ctx.sessionId());
            if (teamContext != null) {
                Object name = teamContext.get(TEAM_CONTEXT_NAME);
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
        Object tc = appState.get(APPSTATE_TEAM_CONTEXT);
        if (!(tc instanceof Map<?, ?> teamContext)) {
            return null;
        }
        Object name = teamContext.get(TEAM_CONTEXT_NAME);
        return (name instanceof String s && !s.isBlank()) ? s : null;
    }

    /** 写 teamContext（会话列 + appState）· 对齐 CC TeamCreateTool.ts:194-212 setAppState(teamContext)。
     *  @param leadSessionId 创建者（lead）会话 ID · 落 teamContext 供前端订阅
     *         /topic/sessions/{leadSessionId}/team-...（stomp-lead-session 方案 3） */
    private void setTeamContext(ToolUseContext ctx, String teamName, String teamFilePath,
                                String leadAgentId, String leadAgentType, String leadSessionId) {
        Map<String, Object> teamContext = new LinkedHashMap<>();
        teamContext.put(TEAM_CONTEXT_NAME, teamName);
        teamContext.put(TEAM_CONTEXT_FILE_PATH, teamFilePath);
        teamContext.put(TEAM_CONTEXT_LEAD, leadAgentId);
        // [stomp-lead-session 方案 3] 前端经 SessionDto.teamContext.leadSessionId 订阅会话级 team topic
        teamContext.put("leadSessionId", leadSessionId);
        Map<String, Object> teammates = new LinkedHashMap<>();
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("name", TEAM_LEAD_NAME);
        lead.put("agentType", leadAgentType);
        // cwd-align-ext：team lead cwd = 会话 cwd（CC TeamCreateTool.ts:207 cwd: getCwd()）；
        //   无 sessionId 回落 user.dir（方案 1，零行为变化）。
        lead.put("cwd", leadCwd());
        teammates.put(leadAgentId, lead);
        teamContext.put("teammates", teammates);

        // [A4] 会话级持久化（sessions.team_context 列）· 跨工具/回合存活（CC appState.teamContext 稳定态）。
        //   sessionId null 守卫（TUC compact ctor 强校验非 null，但保底）→ 只写 appState。
        if (sessionService != null && ctx != null && ctx.sessionId() != null) {
            sessionService.setTeamContext(ctx.sessionId(), teamContext);
            if (log.isDebugEnabled()) {
                log.debug("[TeamCreateTool] setTeamContext: 已写会话列 team_context session={} team={}（A4 会话级化）",
                        ctx.sessionId(), teamName);
            }
        }

        if (ctx == null || ctx.setAppState() == null) {
            return;
        }
        ctx.setAppState().accept(prev -> {
            Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
            next.put(APPSTATE_TEAM_CONTEXT, teamContext);
            if (log.isDebugEnabled()) {
                log.debug("[TeamCreateTool] setAppState(teamContext) team={} lead={}（CC TeamCreateTool.ts:194-212）",
                        teamName, leadAgentId);
            }
            return next;
        });
    }

    /** 输出 · 对齐 CC TeamCreateTool.ts:52-56 Output {team_name, team_file_path, lead_agent_id}。 */
    private String buildOutput(String teamName, String teamFilePath, String leadAgentId) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("team_name", teamName);
        map.put("team_file_path", teamFilePath);
        map.put("lead_agent_id", leadAgentId);
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        map.forEach(node::put);
        return node.toString();
    }
}

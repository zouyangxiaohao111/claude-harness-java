package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.messaging.ListAgentsCursorStore;
import com.nexusai.application.agent.messaging.ListAgentsFormatter;
import com.nexusai.application.agent.messaging.PeerAddressing;
import com.nexusai.application.agent.messaging.PeerRefPins;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.RetiredAgentRegistry;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.repository.session.entity.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ListAgents（canonical）· alias {@code ListPeers}。
 *
 * <p>只列<b>运行期</b>目标（不用 DB 历史）：
 * <ul>
 *   <li><b>Subagents</b>：本会话注册中的 async 子代理（{@link AgentNameRegistry#entriesForOwner}）。</li>
 *   <li><b>Teammates</b>：team 配置里的成员（排除自身身份）。</li>
 *   <li><b>Peer sessions</b>：DB 最近 {@value com.nexusai.domain.session.SessionService#PEER_SESSION_SUMMARY_LIMIT}
 *       个会话 ∪ {@link PeerSessionRegistry#snapshot()}（按 sessionId
 *       去重），排除自身；状态由注册表判定（只有 DB 记录的标 {@code idle}）。</li>
 * </ul>
 *
 * <p>输出契约：工具结果 content = listing；sections/notes 留在 data 供测试/诊断。
 * 他人会话标题**不进指令区**：由 {@link ListAgentsFormatter} 包进 other-sessions nonce 围栏并转义。
 *
 * <p>本工具是发现面，不放宽发送权限：deny/白名单仍优先；能力门 = 跨会话门 OR agent-swarms。
 *
 * <p><b>ref 与 pin</b>（CC 2.1.328）：整页一次性算 ref（{@link PeerAddressing#assignRefs}，
 * 最短唯一前缀 6..12），并把<b>真正打印出来的行</b>登记进本会话 pin 集合
 * （{@link PeerRefPins}，CC exe 364751847 {@code l?.set(u.id,u.name)}）。SendMessage 对
 * {@code name [session_…]} 形态要求该 ref 在本会话列过（exe 356149269），未列过即拒。
 */
@Component
public class ListAgentsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListAgentsTool.class);

    public static final String NAME = ToolNameConstants.LIST_AGENTS_TOOL_NAME;

    /**
     * 非法 / 过期 / 别会话签发的游标 → 统一错误（CC 文案）。
     *
     * <p>判据在<b>任何枚举之前</b>：错误路径不查任何 agent（{@code no agents were looked up}）。
     */
    public static final String CURSOR_NOT_VALID_MESSAGE =
        "cursor_not_valid: that cursor is not valid: this session did not issue it, "
            + "or it has expired \u2014 no agents were looked up; "
            + "call again with no cursor to list them from the start.";

    /** 游标 token 长度（{@link ListAgentsCursorStore} 用去横线 UUID = 32 hex）。 */
    private static final int CURSOR_TOKEN_CHARS = 32;

    /** 下一页提示的固定字符数（页体预算 = 10000 − 本值，保证含提示仍 ≤ 10000）。 */
    private static final int CURSOR_HINT_CHARS =
        cursorHint("0".repeat(CURSOR_TOKEN_CHARS)).length();

    /** 一行计划（ref 在整页范围内统一分配后再渲染）。 */
    private record PlannedRow(PeerAddressing.RefIdentity identity, String name, String id,
                              String type, String status, String title, boolean titleUntrusted) {}

    @Autowired(required = false)
    private AgentNameRegistry agentNameRegistry;

    @Autowired(required = false)
    private PeerSessionRegistry peerSessionRegistry;

    @Autowired(required = false)
    private TeamHelpers teamHelpers;

    @Autowired(required = false)
    private SessionService sessionService;

    @Autowired(required = false)
    private PeerRefPins refPins;

    /** 分页游标存储（可选；单测直构时按需创建）。 */
    @Autowired(required = false)
    private ListAgentsCursorStore cursorStore;

    /**
     * teammate 运行态来源（可选）· 用于把 teammate 行标成 running/idle。
     * 未注入（单测直构）→ 注册中的 teammate 一律 {@code running}（活跃注册即存活）。
     */
    @Autowired(required = false)
    private SpawnInProcess spawnInProcess;

    /** 测试/接线用 setter。 */
    public void setAgentNameRegistry(AgentNameRegistry registry) {
        this.agentNameRegistry = registry;
    }

    /** 测试/接线用 setter。 */
    public void setPeerSessionRegistry(PeerSessionRegistry registry) {
        this.peerSessionRegistry = registry;
    }

    /** 测试/接线用 setter。 */
    public void setTeamHelpers(TeamHelpers helpers) {
        this.teamHelpers = helpers;
    }

    /** 测试/接线用 setter。 */
    public void setSessionService(SessionService service) {
        this.sessionService = service;
    }

    /** 测试/接线用 setter：本会话 pin 集合（与 SendMessageTool 共用一个实例）。 */
    public void setRefPins(PeerRefPins pins) {
        this.refPins = pins;
    }

    /** 测试/接线用 setter：teammate 运行态来源（running/idle 判定）。 */
    public void setSpawnInProcess(SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
    }

    /** 测试/接线用 setter：分页游标存储。 */
    public void setCursorStore(ListAgentsCursorStore cursorStore) {
        this.cursorStore = cursorStore;
    }

    /** 分页游标存储（未注入时按需创建：仍按会话/上限 32/过期签发，行为与生产一致）。 */
    private ListAgentsCursorStore cursorStore() {
        if (cursorStore == null) {
            cursorStore = new ListAgentsCursorStore();
        }
        return cursorStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** exe 别名表 {@code ListPeers:"ListAgents"} —— 历史 transcript 仍可路由。 */
    @Override
    public List<String> aliases() {
        return List.of(ToolNameConstants.LIST_PEERS_TOOL_NAME);
    }

    @Override
    public String description() {
        return "List the agents you can send messages to: subagents in this session, "
            + "teammates, and other sessions on this machine (sessions with a stored record "
            + "count even when not currently running). Large lists are paginated: when the "
            + "result says there are more agents, call again with the returned cursor.";
    }

    @Override
    public String prompt() {
        return description();
    }

    @Override
    public String searchHint() {
        return "list agents you can SendMessage to";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode cursor = properties.putObject("cursor");
        cursor.put("type", "string");
        cursor.put("description",
            "Opaque pagination cursor returned by a previous ListAgents call. Omit to list "
                + "from the start. Cursors are issued by the server and are only valid for the "
                + "session that received them; an invalid or expired cursor returns an error "
                + "without looking up any agents.");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 能力门：跨会话门 OR agent-swarms。发现工具可见不放宽发送权限。
     */
    @Override
    public boolean isEnabled() {
        return TaskSystemConfig.isMessagingBeyondOwnAgentsEnabled()
            || TaskSystemConfig.isAgentSwarmsEnabled();
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sourceSessionId = ctx != null ? ctx.sessionId() : null;
        ListAgentsCursorStore store = cursorStore();

        // 游标先校验再枚举：非法/过期/别会话签发 ⇒ 不查任何 agent 直接报错。
        String cursor;
        try {
            cursor = readCursor(call.input());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), CURSOR_NOT_VALID_MESSAGE);
        }
        if (cursor != null) {
            ListAgentsCursorStore.Resolved resolved = store.resolve(sourceSessionId, cursor);
            if (resolved == null) {
                return ToolResult.error(call.id(), CURSOR_NOT_VALID_MESSAGE);
            }
            return renderPage(call, sourceSessionId, resolved.snapshot(), resolved.offset(), store);
        }
        return renderPage(call, sourceSessionId, buildSnapshot(ctx, sourceSessionId), 0, store);
    }

    /**
     * 读可选 {@code cursor} 参数。
     *
     * @param input 工具输入
     * @return 游标；未提供 / null → null
     * @throws IllegalArgumentException 提供了但形态非法（非字符串或空白串）
     */
    private static String readCursor(JsonNode input) {
        if (input == null) {
            return null;
        }
        JsonNode node = input.get("cursor");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("cursor must be a non-empty string");
        }
        return node.asText().trim();
    }

    /**
     * 枚举 + 去重 + 整页一次性分配 ref，产出<b>全量快照</b>（分页只切窗口，不重新枚举）。
     */
    private ListAgentsFormatter.Snapshot buildSnapshot(ToolUseContext ctx, String sourceSessionId) {
        List<ListAgentsFormatter.Note> notes = new ArrayList<>();

        // Teammates 先枚举：Subagents 要据此剔除「已注册成 teammate 的同一实体」（B1 之后
        // teammate 与子代理共用 (leadSession,name)，不剔除就会被列两遍）。
        List<PlannedRow> teammateRows = List.of();
        try {
            teammateRows = planTeammates(ctx, sourceSessionId);
        } catch (RuntimeException e) {
            notes.add(new ListAgentsFormatter.Note("teammate_list_failed",
                "could not enumerate teammates: " + e.getClass().getSimpleName()));
        }

        List<PlannedRow> subagentRows = List.of();
        try {
            subagentRows = planSubagents(sourceSessionId, teammateRows);
        } catch (RuntimeException e) {
            notes.add(new ListAgentsFormatter.Note("subagent_list_failed",
                "could not enumerate subagents: " + e.getClass().getSimpleName()));
        }

        List<PlannedRow> peerRows = List.of();
        try {
            peerRows = planPeerSessions(sourceSessionId, notes);
        } catch (RuntimeException e) {
            notes.add(new ListAgentsFormatter.Note("local_list_failed",
                "could not enumerate live sessions: " + e.getClass().getSimpleName()));
        }

        // 同一实体只出现一次（优先级 Teams > Subagents > Peer sessions，按身份键去重）。
        Set<String> seen = new HashSet<>();
        teammateRows = dedupeRows(teammateRows, seen);
        subagentRows = dedupeRows(subagentRows, seen);
        peerRows = dedupeRows(peerRows, seen);

        // 整页一次性分配 ref（CC QX）：含本会话自身哈希参与唯一性，保证「别人看到的我」与
        // 「我看到的别人」用同一套最短唯一前缀。teammate 与 session / agent 同一套派生规则
        // （kind:stableId 哈希 + 最短唯一前缀 6..12），所以同名不同实体必然拿到不同 ref。
        List<PeerAddressing.RefIdentity> identities = new ArrayList<>();
        for (PlannedRow row : teammateRows) {
            if (row.identity() != null) {
                identities.add(row.identity());
            }
        }
        for (PlannedRow row : subagentRows) {
            if (row.identity() != null) {
                identities.add(row.identity());
            }
        }
        for (PlannedRow row : peerRows) {
            if (row.identity() != null) {
                identities.add(row.identity());
            }
        }
        List<PeerAddressing.RefIdentity> extra = sourceSessionId == null || sourceSessionId.isBlank()
            ? List.of()
            : List.of(new PeerAddressing.RefIdentity("session", sourceSessionId));
        List<String> refs = PeerAddressing.assignRefs(identities, extra);
        Map<String, String> refByKey = new HashMap<>();
        for (int i = 0; i < identities.size() && i < refs.size(); i++) {
            refByKey.put(refKey(identities.get(i)), refs.get(i));
        }

        List<ListAgentsFormatter.Section> sections = new ArrayList<>();
        addSection(sections, ListAgentsFormatter.SectionKind.SUBAGENTS, subagentRows, refByKey);
        addSection(sections, ListAgentsFormatter.SectionKind.TEAMMATES, teammateRows, refByKey);
        addSection(sections, ListAgentsFormatter.SectionKind.PEER_SESSIONS, peerRows, refByKey);
        return new ListAgentsFormatter.Snapshot(sections, notes);
    }

    /**
     * 渲染快照的某一页；有下一页时签发游标并把提示追加到 listing（仅对签发会话有效）。
     */
    private AgentToolResult<?> renderPage(ToolUseBlock call, String sourceSessionId,
                                          ListAgentsFormatter.Snapshot snapshot, int offset,
                                          ListAgentsCursorStore store) {
        ListAgentsFormatter.Page page = ListAgentsFormatter.paginate(
            snapshot.sections(), snapshot.notes(), offset,
            ListAgentsFormatter.MAX_LISTING_CHARS - CURSOR_HINT_CHARS);
        ListAgentsFormatter.Output output = page.output();
        String listing = output.listing();
        String nextCursor = null;
        if (page.hasMore() && sourceSessionId != null && !sourceSessionId.isBlank()) {
            nextCursor = store.issue(sourceSessionId, snapshot, page.nextOffset());
            if (nextCursor != null) {
                listing = listing + cursorHint(nextCursor);
            }
        }
        ListAgentsFormatter.Output rendered = new ListAgentsFormatter.Output(
            listing, output.sections(), output.notes(), output.pinnedRows());
        recordListedPins(sourceSessionId, rendered);
        if (log.isDebugEnabled()) {
            log.debug("[ListAgents] session={} offset={} sections={} notes={} listingLength={} "
                    + "hasMore={} pinned={}",
                sourceSessionId, offset, rendered.sections().size(), rendered.notes().size(),
                listing.length(), page.hasMore(),
                refPins == null ? 0 : refPins.size(sourceSessionId));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", listing);
        data.put("listing", listing);
        data.put("sections", ListAgentsFormatter.sectionsToMaps(rendered.sections()));
        data.put("notes", ListAgentsFormatter.notesToMaps(rendered.notes()));
        data.put("hasMore", page.hasMore());
        data.put("total", page.totalRows());
        if (nextCursor != null) {
            data.put("nextCursor", nextCursor);
        }
        return ToolResult.success(call.id(), data);
    }

    /** 下一页提示（模型可读；token 长度固定 32，故提示长度是常量）。 */
    private static String cursorHint(String token) {
        return "\n  (more agents; pass cursor \"" + token + "\")\n";
    }

    /** 把计划行按节渲染；空节不输出（与原行为一致）。 */
    private void addSection(List<ListAgentsFormatter.Section> sections,
                            ListAgentsFormatter.SectionKind kind,
                            List<PlannedRow> planned,
                            Map<String, String> refByKey) {
        if (planned.isEmpty()) {
            return;
        }
        List<ListAgentsFormatter.Row> rows = new ArrayList<>(planned.size());
        for (PlannedRow row : planned) {
            String ref = row.identity() == null ? "-"
                : refByKey.getOrDefault(refKey(row.identity()), "-");
            rows.add(new ListAgentsFormatter.Row(row.name(), ref, row.id(), row.type(),
                row.status(), row.title(), row.titleUntrusted()));
        }
        sections.add(new ListAgentsFormatter.Section(kind, rows.size(), rows));
    }

    /**
     * 只登记<b>真正打印出来</b>的 {@code name [ref]} 行（对齐 CC：`v.blocks` 里渲染过的行才 set）。
     * 名单由 formatter 精确给出（含转义后的打印名）；被行数/字符截断掉的行不登记
     * ⇒ 未在列表里看到的 ref 发消息会被 pin 拒。
     */
    private void recordListedPins(String sourceSessionId, ListAgentsFormatter.Output output) {
        if (refPins == null || sourceSessionId == null || sourceSessionId.isBlank()) {
            return;
        }
        for (ListAgentsFormatter.PinnedRow row : output.pinnedRows()) {
            String ref = row.ref();
            String name = row.name();
            if (ref == null || ref.isBlank() || "-".equals(ref) || name == null || name.isBlank()) {
                continue;
            }
            refPins.recordListed(sourceSessionId, ref, name);
        }
    }

    private static String refKey(PeerAddressing.RefIdentity identity) {
        return identity.kind() + ":" + identity.stableId();
    }

    /** 身份键去重（同一实体只出现一次）；无身份的行走 name 兜底键。 */
    private static List<PlannedRow> dedupeRows(List<PlannedRow> rows, Set<String> seen) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<PlannedRow> out = new ArrayList<>(rows.size());
        for (PlannedRow row : rows) {
            String key = row.identity() != null
                ? refKey(row.identity())
                : "name:" + (row.name() == null ? ""
                    : row.name().toLowerCase(java.util.Locale.ROOT));
            if (seen.add(key)) {
                out.add(row);
            }
        }
        return out;
    }

    /** teammate 的 agentId 形态 = {@code name@team}（子代理 agentId 是 {@code a…hex}）。 */
    private static boolean isTeammateAgentId(String agentId) {
        return agentId != null && agentId.indexOf('@') >= 0;
    }

    private List<PlannedRow> planSubagents(String sourceSessionId, List<PlannedRow> teammateRows) {
        List<AgentNameRegistry.AgentNameEntry> entries = agentNameRegistry != null
            ? agentNameRegistry.entriesForOwner(sourceSessionId) : List.of();
        Set<String> teammateIds = new HashSet<>();
        for (PlannedRow row : teammateRows) {
            if (row.identity() != null) {
                teammateIds.add(refKey(row.identity()));
            }
        }
        List<PlannedRow> rows = new ArrayList<>(entries.size());
        for (AgentNameRegistry.AgentNameEntry e : entries) {
            // 已作为 teammate 列出的实体不得再出现在 Subagents（B1 起两者共用注册表）。
            if (isTeammateAgentId(e.agentId())
                    || teammateIds.contains(refKey(
                        new PeerAddressing.RefIdentity("agent", e.agentId())))) {
                continue;
            }
            rows.add(new PlannedRow(new PeerAddressing.RefIdentity("agent", e.agentId()),
                e.name(), e.agentId(), "subagent", "running", null, false));
        }
        return rows;
    }

    /**
     * Teammates 分节：名册 ∪ 本会话活跃 teammate 注册（排除自身）。
     *
     * <p>status 口径：活跃且本轮空闲 → {@code idle}；活跃（或无 loop 信息）→ {@code running}；
     * 已退役 → {@code stopped}；只在名册、从未 spawn → {@code member}。
     * ref 与 session 同一套派生（kind:stableId，{@code agent} 维度 + {@code name@team} 稳定 id）。
     */
    private List<PlannedRow> planTeammates(ToolUseContext ctx, String leadSessionId) {
        String teamName = resolveTeamName(ctx);
        List<String> members = teamHelpers != null && teamName != null
            ? teamHelpers.listMemberNames(teamName) : List.of();
        TeammateIdentity identity = ctx != null ? ctx.teammateIdentity() : null;
        String selfName = Teammate.getAgentName(identity);
        String selfAgentId = Teammate.getAgentId(identity);
        String normalizedSelf = PeerAddressing.normalizeName(selfName);

        Map<String, AgentNameRegistry.AgentNameEntry> active = new LinkedHashMap<>();
        if (agentNameRegistry != null && leadSessionId != null && !leadSessionId.isBlank()) {
            for (AgentNameRegistry.AgentNameEntry entry
                    : agentNameRegistry.entriesForOwner(leadSessionId)) {
                if (isTeammateAgentId(entry.agentId())) {
                    active.putIfAbsent(PeerAddressing.normalizeName(entry.name()), entry);
                }
            }
        }

        // 归一化名 → 展示名（名册原始写法优先，保持模型看到的拼写）。
        Map<String, String> display = new LinkedHashMap<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                continue;
            }
            String key = PeerAddressing.normalizeName(member);
            if (key.isEmpty() || key.equals(normalizedSelf)) {
                continue;
            }
            // lead 自己：名册里的 team-lead 不列（它不是可发送的其他人）。
            if (selfName == null && "team-lead".equals(key)) {
                continue;
            }
            display.putIfAbsent(key, member.trim());
        }
        for (Map.Entry<String, AgentNameRegistry.AgentNameEntry> e : active.entrySet()) {
            if (e.getKey().isEmpty() || e.getKey().equals(normalizedSelf)) {
                continue;
            }
            display.putIfAbsent(e.getKey(), e.getValue().name());
        }

        List<PlannedRow> rows = new ArrayList<>(display.size());
        for (Map.Entry<String, String> e : display.entrySet()) {
            String key = e.getKey();
            AgentNameRegistry.AgentNameEntry entry = active.get(key);
            String agentId = entry != null
                ? entry.agentId()
                : (teamName == null || teamName.isBlank()
                    ? e.getValue() : e.getValue() + "@" + teamName);
            if (selfAgentId != null && selfAgentId.equalsIgnoreCase(agentId)) {
                continue;
            }
            rows.add(new PlannedRow(new PeerAddressing.RefIdentity("agent", agentId),
                e.getValue(), agentId, "teammate",
                teammateStatus(leadSessionId, key, entry), null, false));
        }
        rows.sort(java.util.Comparator.comparing(PlannedRow::name));
        return rows;
    }

    /** teammate 状态：running / idle / stopped / member（口径见 {@link #planTeammates}）。 */
    private String teammateStatus(String leadSessionId, String normalizedName,
                                  AgentNameRegistry.AgentNameEntry entry) {
        if (entry == null) {
            RetiredAgentRegistry.RetiredAgent retired = (leadSessionId == null || agentNameRegistry == null)
                ? null : agentNameRegistry.resolveRetiredEntry(leadSessionId, normalizedName);
            return retired != null ? "stopped" : "member";
        }
        InProcessTeammateTaskRegistry registry = spawnInProcess == null
            ? null : spawnInProcess.registry();
        if (registry == null) {
            return "running";
        }
        return registry.findByAgentId(entry.agentId())
            .map(loop -> {
                if (loop.isAborted()) {
                    return "stopped";
                }
                com.nexusai.application.agent.team.InProcessTeammateTaskState state = loop.taskState();
                return state != null && state.isIdle() ? "idle" : "running";
            })
            .orElse("running");
    }

    /**
     * Peer sessions 分节：<b>DB 会话全集 ∪ 内存注册表</b>，按 sessionId 去重（排除自身）。
     *
     * <p><b>与收件人解析同口径</b>（{@code MessageRecipientResolver}）：<b>DB 判存在</b>（记录在且
     * 未删除，删除即消失 ⇒ 只列 DB 里还有的行），<b>内存注册表判运行状态 / endpoint 代次</b>。
     * 这张表就是「能发给谁」的镜像 —— 列表里没有的会话，SendMessage 也会按 DB 判成不存在；
     * 列表里 {status=idle} 的会话仍可投递（发送时按需登记 endpoint 并唤醒）。
     *
     * <p><b>WHY 并上 DB</b>：重启后收件人解析按 DB 记录补登记 endpoint（{@code PeerEndpointActivator}）。
     * 若列表只读内存注册表，重启后「能发但列表看不到」，两边不一致。
     *
     * <p><b>状态不伪造</b>：注册表里有 endpoint 的行按现有语义（closing / running / idle）；
     * 只有 DB 记录的行标 {@code idle} —— 记录存在 ≠ 正在跑。
     *
     * <p><b>地址名</b>：注册表行用其 {@code addressName}（含同名冲突后缀）；仅 DB 行用标题
     * 归一化名，空白回落 sessionId（与注册表 {@code baseName} 同口径）。
     *
     * <p><b>顺序</b>：先注册表行（状态分组：running → idle → closing → unknown；<b>状态已知</b>时
     * 同状态按 {@code lastActivityAt} 倒序，即最近跑过的会话排前；unknown = 运行态来源未接线，
     * 无可靠活动序 ⇒ 保持 snapshot 的地址名序），再 DB-only 行（查询按
     * updated_at DESC / id ASC，只取最近 {@value com.nexusai.domain.session.SessionService#PEER_SESSION_SUMMARY_LIMIT} 行）。
     */
    private List<PlannedRow> planPeerSessions(String sourceSessionId,
                                              List<ListAgentsFormatter.Note> notes) {
        List<PeerSessionRegistry.LiveSession> live = peerSessionRegistry != null
            ? new ArrayList<>(peerSessionRegistry.snapshot()) : new ArrayList<>();
        // [peer-activity] 状态分组内按「最近活动」倒序（{@code lastActivityAt} 由每轮 heartbeat 刷新）：
        //   running 先于 idle/closing；同状态时刚跑过的会话排前面。unknown（运行态来源未接线）
        //   没有可靠活动序 ⇒ 活动键置 null，回落 snapshot 的地址名序（不改变既有分页/展示顺序）。
        //   DB-only 行没有运行期活动，保持在各注册表行之后（既有「先注册表后 DB-only」顺序不变）。
        live.sort(java.util.Comparator
            .comparingInt((PeerSessionRegistry.LiveSession s) -> statusRank(statusOf(s)))
            .thenComparing(this::activityOrderKey,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
            .thenComparing(PeerSessionRegistry.LiveSession::addressName,
                java.util.Comparator.nullsLast(String::compareTo))
            .thenComparing(PeerSessionRegistry.LiveSession::sessionId));
        Map<String, PeerSessionRegistry.LiveSession> liveById = new HashMap<>();
        List<PlannedRow> rows = new ArrayList<>(live.size());
        for (PeerSessionRegistry.LiveSession session : live) {
            liveById.put(session.sessionId(), session);
            if (session.sessionId().equals(sourceSessionId)) {
                continue;
            }
            rows.add(new PlannedRow(new PeerAddressing.RefIdentity("session", session.sessionId()),
                session.addressName(), session.sessionId(), "session", statusOf(session),
                session.title(), true));
        }
        for (SessionRecord record : dbPeerSessions(notes)) {
            String id = record.getId();
            if (id == null || id.isBlank() || id.equals(sourceSessionId)
                    || liveById.containsKey(id)) {
                continue;
            }
            rows.add(new PlannedRow(new PeerAddressing.RefIdentity("session", id),
                dbPeerName(record), id, "session", "idle", record.getTitle(), true));
        }
        return rows;
    }

    /**
     * DB 会话摘要（只取 id/title/updated_at），<b>最多最近
     * {@value com.nexusai.domain.session.SessionService#PEER_SESSION_SUMMARY_LIMIT} 行</b>。
     *
     * <p>达到上限时写 {@code peer_sessions_capped} note：列表里只有最近的一批，更旧的会话
     * 未读入（展示层的 100 行/1 万字符截断由 {@link ListAgentsFormatter} 另行处理）。
     *
     * <p>读失败降级为空 + note：内存注册表里的行仍要列出来，⛔ 不因 DB 抖动整节消失。
     */
    private List<SessionRecord> dbPeerSessions(List<ListAgentsFormatter.Note> notes) {
        if (sessionService == null) {
            return List.of();
        }
        try {
            List<SessionRecord> rows = sessionService.listPeerSessionSummaries();
            if (rows == null) {
                return List.of();
            }
            if (rows.size() >= SessionService.PEER_SESSION_SUMMARY_LIMIT) {
                notes.add(new ListAgentsFormatter.Note("peer_sessions_capped",
                    "only the " + SessionService.PEER_SESSION_SUMMARY_LIMIT
                        + " most recently updated sessions were read; older sessions are not listed"));
            }
            return rows;
        } catch (RuntimeException e) {
            notes.add(new ListAgentsFormatter.Note("peer_db_list_failed",
                "could not read session records: " + e.getClass().getSimpleName()));
            return List.of();
        }
    }

    /** DB-only 行的地址名：标题归一化；空白（空标题/null）→ sessionId。 */
    private static String dbPeerName(SessionRecord record) {
        String name = PeerAddressing.normalizeName(record.getTitle());
        return name.isBlank() ? record.getId() : name;
    }

    private String statusOf(PeerSessionRegistry.LiveSession session) {
        if (session.closing()) {
            return "closing";
        }
        if (peerSessionRegistry == null) {
            return "unknown";
        }
        return switch (peerSessionRegistry.status(session.sessionId())) {
            case RUNNING -> "running";
            case IDLE -> "idle";
            case UNKNOWN -> "unknown";
        };
    }

    /** 状态展示优先级（同状态再按最近活动倒序）：running(0) → idle(1) → closing(2) → 其他(3)。 */
    private static int statusRank(String status) {
        if (status == null) {
            return 3;
        }
        return switch (status) {
            case "running" -> 0;
            case "idle" -> 1;
            case "closing" -> 2;
            default -> 3;
        };
    }

    /**
     * 活动排序键：只有状态已知（running/idle/closing）才用 {@code lastActivityAt}；
     * {@code unknown} = 运行态来源未接线（无可靠活动序）⇒ 返回 null，回落地址名序。
     */
    private java.time.Instant activityOrderKey(PeerSessionRegistry.LiveSession session) {
        return "unknown".equals(statusOf(session)) ? null : session.lastActivityAt();
    }

    private String resolveTeamName(ToolUseContext ctx) {
        TeammateIdentity identity = ctx != null ? ctx.teammateIdentity() : null;
        String teamName = Teammate.getTeamName(identity, null);
        if (teamName != null) {
            return teamName;
        }
        if (sessionService != null && ctx != null && ctx.sessionId() != null) {
            Map<String, Object> teamContext = sessionService.getTeamContext(ctx.sessionId());
            if (teamContext != null) {
                Object name = teamContext.get(TeamCreateTool.TEAM_CONTEXT_NAME);
                if (name instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
    }
}

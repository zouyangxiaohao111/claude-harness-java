package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.common.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Team Helpers · 对齐 CC utils/swarm/teamHelpers.ts (683 行) 的 team 文件持久化载体.
 *
 * <p>W8-03 重构（DEL-31/32）：TeamCreateTool/TeamDeleteTool 从内存 TeamMessageBus 改投本类
 * （文件型 team 配置），team 目录路径对齐 CC：
 * <pre>{@code getTeamDir(teamName) = join(getTeamsDir(), sanitizeName(teamName))}（teamHelpers.ts:58-60）</pre>
 * {@code config.json} 位于 {@code {configHome}/teams/{sanitizedTeam}/config.json}（:66-68 getTeamFilePath），
 * 与 {@link TeammateMailbox} 的 inbox 目录（同 base 的 {@code {team}/inboxes/}）同根 ——
 * CC 中 team 配置文件与 mailbox 收件箱天然同目录（swarm 跨进程互认），旧版
 * {@code .nexusai/teams} 相对路径为教学版偏离，已删。
 *
 * <p>实现要点（grep 自验 CC teamHelpers.ts，不信注释）：
 * <ul>
 *   <li>{@link #readConfig} 文件缺失返回 {@code null}（对齐 CC readTeamFile :73-80 ENOENT → null）；</li>
 *   <li>{@link #deleteTeam} 递归删除 team 目录（对齐 CC cleanupTeamDirectories，含 config.json + inboxes/）；</li>
 *   <li>成员枚举经 {@code config.json members} 数组（{@link #listMemberNames}，对齐 CC handleBroadcast
 *       teamFile.members）；旧 {@code member_*} 文件枚举已随 IMP-G2 TR-G3-⊕-12 删除（无写入方死代码）。</li>
 * </ul>
 *
 * <p>静态工具类风格同 {@link TeammateMailbox}（CC teamHelpers.ts 为模块级函数、无实例状态）；
 * 保留 {@link Component} 注册供工具注入。
 */
@Component
public class TeamHelpers {

    private static final Logger log = LoggerFactory.getLogger(TeamHelpers.class);

    private static final String CONFIG_FILE = "config.json";

    /**
     * [A3] 会话内创建的 team 集合（会话级桶）· 对齐 CC teamHelpers.ts:560-562
     * registerTeamForSessionCleanup 背后的 bootstrap/state.ts:149 {@code STATE.sessionCreatedTeams} Set。
     *
     * <p><b>[A3] 会话级化（Batch1 finding R2-2 警示）</b>：CC 为单会话（每会话独立进程、进程退出清自身
     * teams），Java 常驻 JVM 多会话必须按 sessionId 分桶——进程级 Set 会让会话 A 创建的 team 被会话 B
     * 误删/误防删（multi-session-vs-cc-single-session 铁律）。[session-id-short] 键 = short 直键
     * （写入侧 TeamCreateTool 与清理侧 SessionService.delete 同键）。<b>勿按旧进程级 Set 返工</b>：
     * cleanupSessionTeams(sessionId) 只 remove 本会话桶，不得清全局。
     */
    private static final Map<String, Set<String>> sessionCreatedTeams = new ConcurrentHashMap<>();

    /**
     * [team-panel-backend-bugfix2] 会话级 team_context 同步 · config.members（单权限源）→
     * sessions.team_context.teammates（对齐 CC spawnMultiAgent.ts:974-982 spawn 时并入 teammates）。
     * 可选注入（required=false）：未注入（测试/手动直构）→ {@link #syncTeamContextTeammates} 静默跳过。
     */
    // [fix-circular-team-deps] @Lazy 打破 TeamHelpers↔SessionService 循环依赖
    //   （SessionService.teamHelpers:65 删会话清理 team ↔ 本字段 teamContext 同步）——对齐
    //   ToolRegistrationConfig:592 @Lazy 先例；bean 装配期不解析，首次 syncTeamContextTeammates
    //   才经代理取真实 bean，无语义改变（Spring Boot 默认禁用循环依赖 → 不加则启动失败）。
    @Lazy
    @Autowired(required = false)
    private com.nexusai.domain.session.SessionService sessionService;

    /** 测试/接线用 setter（sessionService · team_context.teammates 同步）· 对齐 setTeamHelpers 模式。 */
    public void setSessionService(com.nexusai.domain.session.SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** team 目录 · 对齐 CC teamHelpers.ts:58-60 getTeamDir（[IMP-G] G26① XT-01：改用 sanitizeName
     * 对齐 CC teamHelpers.ts:115-117 {@code join(getTeamsDir(), sanitizeName(teamName))}）。
     * 旧 TaskService.sanitizePathComponent 保留 `_`/`-` 且不转小写 → 跨进程互认断链。 */
    public Path teamDir(String teamName) {
        String safeTeam = sanitizeName(teamName);
        return TeammateMailbox.getTeamsDir().resolve(safeTeam);
    }

    /** team 配置文件路径 · 对齐 CC teamHelpers.ts:66-68 getTeamFilePath。 */
    public Path configPath(String teamName) {
        return teamDir(teamName).resolve(CONFIG_FILE);
    }

    /** team 是否存在（config.json 存在）。 */
    public boolean teamExists(String teamName) {
        return Files.exists(configPath(teamName));
    }

    /**
     * 读取 team 配置文件 · 对齐 CC teamHelpers.ts:73-80 readTeamFile：
     * ENOENT → null（不抛）。
     */
    public String readConfig(String teamName) {
        Path config = configPath(teamName);
        try {
            return Files.readString(config);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 team 配置文件失败: " + config, e);
        }
    }

    /**
     * 读取 team 配置中的 lead agent ID · 对齐 CC {@code TeamFile.leadAgentId}（teamHelpers.ts:68，
     * camelCase）。CC TeamCreateTool.ts:161 以 {@code leadAgentId} 写入 config.json；工具输出
     * {@code lead_agent_id}（snake_case，TeamCreateTool.ts:55 Output 类型）是 LLM 返回契约，非
     * config.json 落盘字段。
     *
     * <p><b>WHY (GAP-R2)</b>：SendMessageTool 的 isTeamLead 守卫（CC teammate.ts:171-198）依赖
     * {@code teamContext.leadAgentId}。config 缺失 / 无该键 / 解析失败 → {@code null}（不抛，
     * 对齐 {@link #readConfig} ENOENT → null 容错）。
     *
     * @param teamName 目标 team
     * @return lead agent ID（e.g. {@code "lead@my-team"}），无则 null
     */
    public String leadAgentId(String teamName) {
        String config = readConfig(teamName);
        if (config == null) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(config);
            if (root == null || !root.isObject()) {
                return null;
            }
            String lead = root.path("leadAgentId").asText(null);
            return lead == null || lead.isBlank() ? null : lead;
        } catch (Exception e) {
            log.warn("[TeamHelpers] leadAgentId 解析 config.json 失败 team={}: {}", teamName, e.getMessage());
            return null;
        }
    }

    /**
     * 读取 team 配置中的 lead session ID · 对齐 CC {@code TeamFile.leadSessionId}（teamHelpers.ts:68，
     * camelCase）。CC TeamCreateTool.ts:162 以 {@code getSessionId()}（leadSessionId）写入 config.json；
     * STOMP 按 lead 会话推送（方案 3）经本方法反查 topic 锚点。
     *
     * <p><b>WHY (stomp-lead-session)</b>：SendMessageTool / TeamStatusPublisher 推送目标由全局
     * {@code /topic/teams/{name}/} 改为会话级 {@code /topic/sessions/{leadSessionId}/team-...}，
     * 前端只订阅创建者会话。config 缺失 / 无该键 / 解析失败 → {@code null}（不抛，对齐
     * {@link #readConfig} ENOENT → null 容错）。
     *
     * @param teamName 目标 team
     * @return config.leadSessionId（字符串），无则 null
     */
    public String leadSessionId(String teamName) {
        String config = readConfig(teamName);
        if (config == null) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(config);
            if (root == null || !root.isObject()) {
                return null;
            }
            String lead = root.path("leadSessionId").asText(null);
            return lead == null || lead.isBlank() ? null : lead;
        } catch (Exception e) {
            log.warn("[TeamHelpers] leadSessionId 解析 config.json 失败 team={}: {}", teamName, e.getMessage());
            return null;
        }
    }

    /** 写入 team 配置文件 · 对齐 CC writeTeamFileAsync（mkdirs + 写 config.json）。 */
    public void writeConfig(String teamName, String json) {
        Path config = configPath(teamName);
        try {
            Files.createDirectories(config.getParent());
            Files.writeString(config, json);
        } catch (IOException e) {
            throw new UncheckedIOException("写入 team 配置文件失败: " + config, e);
        }
    }

    /** 删除 team 目录（递归）· 对齐 CC cleanupTeamDirectories：含 config.json + inboxes/。 */
    public void deleteTeam(String teamName) {
        Path dir = teamDir(teamName);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("删除 team 目录项失败: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删除 team 目录失败: " + dir, e);
        }
    }

    /**
     * 列出 team 成员名 · 对齐 CC {@code handleBroadcast}（SendMessageTool.ts:220-226）经
     * {@code teamFile.members} 枚举：读 config.json {@code members} 数组 → 返回 {@code member.name}
     * 列表；team 不存在 / 无 members / 解析失败 → 空（对齐 CC readTeamFile ENOENT → null 容错）。
     *
     * <p><b>IMP-G2（组 6-2，TR-G3-⊕-12）</b>：替代旧 {@code member_*} 文件枚举（全仓无写入方，
     * 广播恒空死代码）。CC 广播收件人 = teamFile.members 中 name（大小写不敏感排除 sender），
     * 非 {@code member_*} 文件。旧实现已删除，不保留双轨。
     *
     * @param teamName 目标 team
     * @return 成员 name 列表（含 team-lead），team 不存在返回空
     */
    public List<String> listMemberNames(String teamName) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamHelpers] listMemberNames: team={} 不存在或无 members 数组, 返回空", teamName);
            }
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode member : root.get("members")) {
            if (member != null && member.isObject()) {
                String name = member.path("name").asText();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[TeamHelpers] listMemberNames: team={} 枚举 {} 个成员: {}", teamName, names.size(), names);
        }
        return names;
    }

    /**
     * 移除 team 成员 · 对齐 CC teamHelpers.ts:326-345 removeMemberByAgentId：
     * 读 teamFile，按 {@code agentId}（name@team 全量）从 members 数组移除，写回。
     *
     * <p>Java 侧成员移除只作用 {@code config.json members} 数组（CC 对齐，{@link TeamDiscovery}
     * 读取）——按 {@code member.agentId === agentId} 过滤（CC :333-338）。
     * 旧 {@code member_}* 文件（全仓无写入方）已随 IMP-G2 TR-G3-⊕-12 删除。
     *
     * @return true 找到并移除；false 无该成员
     */
    public boolean removeMemberByAgentId(String teamName, String agentId) {
        if (teamName == null || agentId == null) {
            return false;
        }
        boolean removed = false;
        // 1) config.json members 数组移除（对齐 CC teamHelpers.ts:326-345）
        String config = readConfig(teamName);
        if (config != null) {
            try {
                JsonNode root = new ObjectMapper().readTree(config);
                if (root != null && root.isObject() && root.has("members") && root.get("members").isArray()) {
                    ObjectNode obj = (ObjectNode) root;
                    ArrayNode members = (ArrayNode) obj.get("members");
                    for (int i = members.size() - 1; i >= 0; i--) {
                        JsonNode m = members.get(i);
                        if (m != null && m.isObject() && agentId.equals(m.path("agentId").asText())) {
                            members.remove(i);
                            removed = true;
                        }
                    }
                    if (removed) {
                        writeConfig(teamName, new ObjectMapper().writeValueAsString(obj));
                        if (log.isInfoEnabled()) {
                            log.info("[TeamHelpers] 已从 team={} 移除成员 agentId={}", teamName, agentId);
                        }
                    }
                }
            } catch (Exception e) {
                // 非 JSON 配置/解析失败不处理（不抛，对齐 CC readTeamFile ENOENT→null 容错）
                log.warn("[TeamHelpers] removeMemberByAgentId 解析 config.json 失败 team={}: {}",
                    teamName, e.getMessage());
            }
        }
        return removed;
    }

    /** 验证 team name (合法字符: a-zA-Z0-9_-). */
    public static boolean isValidTeamName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // CC teamHelpers.ts 补齐（2026-08-14 T-C）· 以下为原 TeamHelpers 缺失的 CC 导出函数
    // ════════════════════════════════════════════════════════════════════

    /**
     * 清洗 team 名（tmux window / worktree / 文件路径用）· 对齐 CC teamHelpers.ts:100-102 sanitizeName：
     * {@code name.replace(/[^a-zA-Z0-9]/g, '-').toLowerCase()}。
     */
    public static String sanitizeName(String name) {
        return name == null ? "" : name.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
    }

    /**
     * 清洗 agent 名（确定性 agent ID 用）· 对齐 CC teamHelpers.ts:108-110 sanitizeAgentName：
     * {@code name.replace(/@/g, '-')} —— 防止 {@code agentName@teamName} 格式歧义。
     */
    public static String sanitizeAgentName(String name) {
        return name == null ? "" : name.replace("@", "-");
    }

    /**
     * 按 agentId 或 name 从 team 文件移除成员 · 对齐 CC teamHelpers.ts:188-227 removeTeammateFromTeamFile。
     *
     * <p>两字段至少其一非空；读取 teamFile 失败 → false；按 {@code agentId}（且匹配）或
     * {@code name}（且匹配）过滤 members 数组；长度未变 → false（成员未找到）；否则写回 + true。
     *
     * @param agentId 目标 agentId（可空，按 agentId 过滤）
     * @param name    目标 name（可空，按 name 过滤）
     * @return true 实际移除；false 无 identifier / 读失败 / 成员未找到
     */
    public boolean removeTeammateFromTeamFile(String teamName, String agentId, String name) {
        String identifier = (agentId != null && !agentId.isBlank()) ? agentId : name;
        if (identifier == null || identifier.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamHelpers] removeTeammateFromTeamFile 无 identifier, 返回 false");
            }
            return false;
        }
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamHelpers] removeTeammateFromTeamFile 读 team 文件失败或无 members: {}", teamName);
            }
            return false;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        int originalLength = members.size();
        ArrayNode kept = new ObjectMapper().createArrayNode();
        for (JsonNode m : members) {
            if (m == null || !m.isObject()) {
                kept.add(m);
                continue;
            }
            boolean drop = (agentId != null && !agentId.isBlank()
                    && agentId.equals(m.path("agentId").asText()))
                || (name != null && !name.isBlank() && name.equals(m.path("name").asText()));
            if (!drop) {
                kept.add(m);
            }
        }
        if (kept.size() == originalLength) {
            if (log.isDebugEnabled()) {
                log.debug("[TeamHelpers] 成员 {} 不在 team {} 中, 返回 false", identifier, teamName);
            }
            return false;
        }
        root.set("members", kept);
        writeConfig(teamName, root.toString());
        log.info("[TeamHelpers] 已从 team={} 移除成员 identifier={}", teamName, identifier);
        return true;
    }

    /**
     * 按 tmuxPaneId 从 team 文件移除成员（含 hiddenPaneIds 同步移除）· 对齐 CC
     * teamHelpers.ts:285-317 removeMemberFromTeam。
     *
     * <p>Java in-process teammate 共用同一 pane（CC :322 注释），本函数按 {@code tmuxPaneId}
     * 匹配（CC :295 findIndex m.tmuxPaneId === tmuxPaneId），命中则从 members 移除 + 从
     * hiddenPaneIds 移除同 paneId（CC :305-310），写回。
     *
     * @return true 实际移除；false team/member 不存在
     */
    public boolean removeMemberFromTeam(String teamName, String tmuxPaneId) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            return false;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        int memberIndex = -1;
        for (int i = 0; i < members.size(); i++) {
            JsonNode m = members.get(i);
            if (m != null && m.isObject() && tmuxPaneId != null
                    && tmuxPaneId.equals(m.path("tmuxPaneId").asText())) {
                memberIndex = i;
                break;
            }
        }
        if (memberIndex == -1) {
            return false;
        }
        members.remove(memberIndex);
        if (root.has("hiddenPaneIds") && root.get("hiddenPaneIds").isArray()) {
            ArrayNode hidden = (ArrayNode) root.get("hiddenPaneIds");
            for (int i = hidden.size() - 1; i >= 0; i--) {
                if (tmuxPaneId.equals(hidden.get(i).asText())) {
                    hidden.remove(i);
                }
            }
        }
        writeConfig(teamName, root.toString());
        log.info("[TeamHelpers] 已按 pane={} 从 team={} 移除成员", tmuxPaneId, teamName);
        return true;
    }

    /**
     * 设置成员 permission mode · 对齐 CC teamHelpers.ts:357-389 setMemberMode。
     *
     * <p>读 teamFile → 找 member.name === memberName → 缺失 false；mode 未变 → true（不写）；
     * 否则 immutably map 更新 members 数组写回。
     *
     * @return true 已设置（含无需变更）；false team/member 不存在
     */
    public boolean setMemberMode(String teamName, String memberName, String mode) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            return false;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        JsonNode member = null;
        for (JsonNode m : members) {
            if (m != null && m.isObject() && memberName != null && memberName.equals(m.path("name").asText())) {
                member = m;
                break;
            }
        }
        if (member == null) {
            log.warn("[TeamHelpers] 设置成员 mode 失败: 成员 {} 不在 team {}", memberName, teamName);
            return false;
        }
        if (mode != null && mode.equals(member.path("mode").asText(null))) {
            return true;
        }
        for (JsonNode m : members) {
            if (m.isObject() && memberName.equals(m.path("name").asText())) {
                if (mode == null) {
                    ((ObjectNode) m).remove("mode");
                } else {
                    ((ObjectNode) m).put("mode", mode);
                }
            }
        }
        writeConfig(teamName, root.toString());
        log.info("[TeamHelpers] 已设置成员 {} 的 mode 为 {} (team={})", memberName, mode, teamName);
        return true;
    }

    /** 批量 mode 更新条目 · 对齐 CC teamHelpers.ts:415 {@code {memberName, mode}}。 */
    public record MemberModeUpdate(String memberName, String mode) {}

    /**
     * 原子批量设置成员 permission mode · 对齐 CC teamHelpers.ts:415-445 setMultipleMemberModes。
     *
     * <p>updateMap 查找 + anyChanged 才写回（避免无谓写）；返回 true（CC 恒 true，team 缺失 false）。
     */
    public boolean setMultipleMemberModes(String teamName, List<MemberModeUpdate> modeUpdates) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            return false;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        java.util.Map<String, String> updateMap = new java.util.HashMap<>();
        if (modeUpdates != null) {
            for (MemberModeUpdate u : modeUpdates) {
                if (u != null && u.memberName() != null) {
                    updateMap.put(u.memberName(), u.mode());
                }
            }
        }
        boolean anyChanged = false;
        for (JsonNode m : members) {
            if (!m.isObject()) {
                continue;
            }
            String name = m.path("name").asText();
            if (updateMap.containsKey(name)) {
                String newMode = updateMap.get(name);
                String current = m.path("mode").asText(null);
                if (!java.util.Objects.equals(current, newMode)) {
                    if (newMode == null) {
                        ((ObjectNode) m).remove("mode");
                    } else {
                        ((ObjectNode) m).put("mode", newMode);
                    }
                    anyChanged = true;
                }
            }
        }
        if (anyChanged) {
            writeConfig(teamName, root.toString());
            log.info("[TeamHelpers] 已批量设置 {} 个成员 mode (team={})", modeUpdates == null ? 0 : modeUpdates.size(), teamName);
        }
        return true;
    }

    /**
     * 追加 team 成员 · 对齐 CC spawnMultiAgent.ts:495-509 appendTeamMember
     * {@code teamFile.members.push({agentId, name, agentType, model, prompt, color,
     * planModeRequired, joinedAt, tmuxPaneId, cwd, subscriptions, backendType})}。
     *
     * <p>teammate spawn 成功后由 {@link SpawnInProcess} 调用，使 teammate 对 TeamDiscovery
     * （读 members）/ 广播（SendMessageTool listMemberNames）/ TeamDelete（活跃成员守卫）可见。
     * CC 写失败抛错（team 消失，spawnMultiAgent.ts:490-493）；Java 放宽为 log.warn 返回 false
     * （append 失败不阻断 spawn，Batch2 S1 设计决策记录差异）。
     *
     * @param teamName 目标 team
     * @param member   追加成员引用（agentType/model/prompt/color/cwd/backendType 可空，省略键）
     * @return true 追加成功；team 不存在 / 无 members 数组 → false（不抛）
     */
    public boolean appendTeamMember(String teamName, TeamMemberRef member) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            log.warn("[TeamHelpers] appendTeamMember 失败: team={} 不存在或无 members 数组", teamName);
            return false;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        ObjectNode m = members.addObject();
        m.put("agentId", member.agentId());
        m.put("name", member.name());
        if (member.agentType() != null) {
            m.put("agentType", member.agentType());
        }
        if (member.model() != null) {
            m.put("model", member.model());
        }
        if (member.prompt() != null) {
            m.put("prompt", member.prompt());
        }
        if (member.color() != null) {
            m.put("color", member.color());
        }
        m.put("planModeRequired", member.planModeRequired());
        m.put("joinedAt", System.currentTimeMillis());
        m.put("tmuxPaneId", member.tmuxPaneId() != null ? member.tmuxPaneId() : "");
        if (member.cwd() != null) {
            m.put("cwd", member.cwd());
        }
        m.putArray("subscriptions");
        if (member.backendType() != null) {
            m.put("backendType", member.backendType());
        }
        writeConfig(teamName, root.toString());
        log.info("[TeamHelpers] 已追加成员 agentId={} name={} 到 team={} members（CC spawnMultiAgent.ts:495-509）",
            member.agentId(), member.name(), teamName);
        return true;
    }

    /**
     * [team-panel-backend-bugfix2] config.members → sessions.team_context.teammates 同步 · 单权限源
     * 为 config.json members（对齐 CC teamDiscovery.ts:39-55 前端面板数据源），重建会话级
     * {@code team_context.teammates}（键 = agentId，值含 name/agentType/color/tmuxPaneId/cwd/spawnedAt，
     * 对齐 CC spawnMultiAgent.ts:974-982 teammates 形状）后写回。
     *
     * <p><b>WHY</b>：Java 双轨（config.json 全局实体 vs sessions.team_context 会话绑定）此前互不回写——
     * appendTeamMember 只写 config.members，team_context.teammates 恒为 TeamCreateTool 建队时的
     * 仅 lead（TeamCreateTool.java:420-428）；前端读 SessionDto.teamContext.teammates 与 GET /teams
     * 看到两套不同成员。CC 在 spawn（spawnMultiAgent.ts:974-982）/ kill（spawnInProcess.ts:267-275）
     * 均同步 teamContext.teammates。本方法供 spawn/kill/remove/shutdown 各离开/加入点统一调用。
     *
     * <p>fail-soft：sessionService 未注入 / team 无 config / 无 leadSessionId / teamContext 不存在
     * → 静默跳过（不抛，对齐 readConfig ENOENT→null 容错）。
     *
     * @param teamName 目标 team（config.json 路径名）
     */
    public void syncTeamContextTeammates(String teamName) {
        if (sessionService == null || teamName == null || teamName.isBlank()) {
            return;
        }
        try {
            String config = readConfig(teamName);
            if (config == null) {
                return;
            }
            JsonNode root = new ObjectMapper().readTree(config);
            if (root == null || !root.isObject()) {
                return;
            }
            String leadSessionId = root.path("leadSessionId").asText(null);
            if (leadSessionId == null || leadSessionId.isBlank()) {
                return;
            }
            Map<String, Object> teamContext = sessionService.getTeamContext(leadSessionId);
            if (teamContext == null) {
                return;
            }
            Map<String, Object> teammates = new LinkedHashMap<>();
            JsonNode arr = root.path("members");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    if (m == null || !m.isObject()) {
                        continue;
                    }
                    String name = m.path("name").asText(null);
                    if (name == null || "team-lead".equals(name)) {
                        continue;
                    }
                    String agentId = m.path("agentId").asText(null);
                    if (agentId == null || agentId.isBlank()) {
                        continue;
                    }
                    Map<String, Object> tm = new LinkedHashMap<>();
                    tm.put("name", name);
                    String agentType = m.path("agentType").asText(null);
                    if (agentType != null) {
                        tm.put("agentType", agentType);
                    }
                    String color = m.path("color").asText(null);
                    if (color != null) {
                        tm.put("color", color);
                    }
                    String tmuxPaneId = m.path("tmuxPaneId").asText(null);
                    if (tmuxPaneId != null) {
                        tm.put("tmuxPaneId", tmuxPaneId);
                    }
                    String cwd = m.path("cwd").asText(null);
                    if (cwd != null) {
                        tm.put("cwd", cwd);
                    }
                    long joinedAt = m.path("joinedAt").asLong(0);
                    if (joinedAt > 0) {
                        tm.put("spawnedAt", joinedAt);
                    }
                    teammates.put(agentId, tm);
                }
            }
            teamContext.put("teammates", teammates);
            sessionService.setTeamContext(leadSessionId, teamContext);
            if (log.isDebugEnabled()) {
                log.debug("[TeamHelpers] syncTeamContextTeammates: team={} teammates={}（config.members 为单权限源，对齐 CC spawnMultiAgent.ts:974-982）",
                        teamName, teammates.size());
            }
        } catch (Exception e) {
            log.warn("[TeamHelpers] syncTeamContextTeammates 失败 team={}: {}", teamName, e.getMessage());
        }
    }

    /**
     * 追加成员引用 · 对齐 CC spawnMultiAgent.ts:495-509 {@code members.push} 元素
     * （agentType/model/prompt/color/planModeRequired/tmuxPaneId/cwd/backendType）。
     *
     * @param agentId         CC original: agentId（formatAgentId(name, team)）
     * @param name            CC original: name（sanitizedName）
     * @param agentType       CC original: agentType（input.agent_type，可空）
     * @param model           CC original: model（可空）
     * @param prompt          CC original: prompt（input.prompt，可空）
     * @param color           CC original: color（teammateColor，可空）
     * @param planModeRequired CC original: planModeRequired
     * @param tmuxPaneId      CC original: tmuxPaneId（in-process → 'in-process'）
     * @param cwd             CC original: cwd（spawn.workingDir，可空）
     * @param backendType     CC original: backendType（in-process → 'in-process'）
     */
    public record TeamMemberRef(String agentId, String name, String agentType, String model,
                                String prompt, String color, boolean planModeRequired,
                                String tmuxPaneId, String cwd, String backendType) {
    }

    /**
     * 设置成员 active 状态 · 对齐 CC teamHelpers.ts:454-485 setMemberActive。
     *
     * <p>member 缺失 / team 缺失 → 返回（不写）；isActive 未变 → 返回；否则写回。
     */
    public void setMemberActive(String teamName, String memberName, boolean isActive) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null || !root.has("members") || !root.get("members").isArray()) {
            log.warn("[TeamHelpers] 设置成员 active 失败: team {} 不存在或无 members", teamName);
            return;
        }
        ArrayNode members = (ArrayNode) root.get("members");
        JsonNode member = null;
        for (JsonNode m : members) {
            if (m != null && m.isObject() && memberName != null && memberName.equals(m.path("name").asText())) {
                member = m;
                break;
            }
        }
        if (member == null) {
            log.warn("[TeamHelpers] 设置成员 active 失败: 成员 {} 不在 team {}", memberName, teamName);
            return;
        }
        Boolean current = member.path("isActive").isMissingNode()
            || member.path("isActive").isNull() ? null : member.path("isActive").asBoolean();
        if (current != null && current == isActive) {
            return;
        }
        ((ObjectNode) member).put("isActive", isActive);
        writeConfig(teamName, root.toString());
        log.info("[TeamHelpers] 已设置成员 {} active={} (team={})", memberName, isActive, teamName);
    }

    /**
     * 同步当前 teammate 的 mode 到 config.json · 对齐 CC teamHelpers.ts:397-407 syncTeammateMode。
     *
     * <p>非 teammate 直接返回（CC :401 isTeammate()）；否则 setMemberMode(teamName, agentName, mode)。
     */
    public void syncTeammateMode(String mode, String teamNameOverride) {
        if (!Teammate.isTeammate()) {
            return;
        }
        String teamName = teamNameOverride != null && !teamNameOverride.isBlank()
            ? teamNameOverride : Teammate.getTeamName();
        String agentName = Teammate.getAgentName();
        if (teamName != null && !teamName.isBlank() && agentName != null && !agentName.isBlank()) {
            setMemberMode(teamName, agentName, mode);
        }
    }

    /**
     * 添加 pane ID 到 hiddenPaneIds · 对齐 CC teamHelpers.ts:235-251 addHiddenPaneId。
     *
     * @return true pane 已入隐藏列表（已存在则不重复写，仍 true）；team 不存在 false
     */
    public boolean addHiddenPaneId(String teamName, String paneId) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null) {
            return false;
        }
        ArrayNode hidden = root.has("hiddenPaneIds") && root.get("hiddenPaneIds").isArray()
            ? (ArrayNode) root.get("hiddenPaneIds") : root.putArray("hiddenPaneIds");
        boolean present = false;
        for (JsonNode n : hidden) {
            if (paneId != null && paneId.equals(n.asText())) {
                present = true;
                break;
            }
        }
        if (!present) {
            hidden.add(paneId);
            writeConfig(teamName, root.toString());
            log.info("[TeamHelpers] 已添加 {} 到 team {} hiddenPaneIds", paneId, teamName);
        }
        return true;
    }

    /**
     * 从 hiddenPaneIds 移除 pane ID · 对齐 CC teamHelpers.ts:259-276 removeHiddenPaneId。
     *
     * @return true pane 已从隐藏列表移除；team 不存在 false
     */
    public boolean removeHiddenPaneId(String teamName, String paneId) {
        ObjectNode root = readConfigNode(teamName);
        if (root == null) {
            return false;
        }
        if (!root.has("hiddenPaneIds") || !root.get("hiddenPaneIds").isArray()) {
            return true;
        }
        ArrayNode hidden = (ArrayNode) root.get("hiddenPaneIds");
        for (int i = hidden.size() - 1; i >= 0; i--) {
            if (paneId != null && paneId.equals(hidden.get(i).asText())) {
                hidden.remove(i);
                writeConfig(teamName, root.toString());
                log.info("[TeamHelpers] 已从 team {} hiddenPaneIds 移除 {}", teamName, paneId);
                break;
            }
        }
        return true;
    }

    /**
     * 标记 team 为本次 session 创建（退出时清理）· 对齐 CC teamHelpers.ts:560-562
     * registerTeamForSessionCleanup（getSessionCreatedTeams().add）。
     *
     * <p>[A3] 会话级化：sessionId/teamName 任一空 → no-op；[session-id-short] 桶键 = short 直键
     * （写入侧 TeamCreateTool 传 short、清理侧 SessionService.delete 传 short，同键）。
     */
    public void registerTeamForSessionCleanup(String sessionId, String teamName) {
        if (sessionId == null || sessionId.isBlank() || teamName == null || teamName.isBlank()) {
            return;
        }
        String key = sessionId;
        sessionCreatedTeams.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(teamName);
        if (log.isDebugEnabled()) {
            log.debug("[TeamHelpers] registerTeamForSessionCleanup: session={} team={}（桶键 {}）",
                sessionId, teamName, key);
        }
    }

    /**
     * 移除 session 清理追踪 · 对齐 CC teamHelpers.ts:568-570 unregisterTeamForSessionCleanup
     * （getSessionCreatedTeams().delete）。
     *
     * <p>保持单参：team 名唯一（每 leader 一 team），遍历所有会话桶 remove + 空桶淘汰——
     * TeamDeleteTool ctx 可能为 null 拿不到 sessionId，故不依赖调用方持有 sessionId。
     */
    public void unregisterTeamForSessionCleanup(String teamName) {
        if (teamName == null || teamName.isBlank()) {
            return;
        }
        for (Set<String> set : sessionCreatedTeams.values()) {
            set.remove(teamName);
        }
        sessionCreatedTeams.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (log.isDebugEnabled()) {
            log.debug("[TeamHelpers] unregisterTeamForSessionCleanup: team={}（跨全部会话桶移除）", teamName);
        }
    }

    /**
     * 清理指定 session 创建且未显式删除的 team · 对齐 CC teamHelpers.ts:576-590 cleanupSessionTeams。
     *
     * <p>[A3] 会话级化：remove(sessionId 归一桶键) 只清本会话 teams，杜绝跨会话误删（会话 A 创建的
     * team 不被会话 B 的清理钩子删除）。空/未知会话 → no-op。逐 team cleanupTeamDirectories；末只移除
     * 本会话桶，不清全局。
     * Java in-process 无 tmux pane，killOrphanedTeammatePanes（CC :587）N/A（见 concerns）。
     */
    public void cleanupSessionTeams(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // [session-id-short] 桶键 short 直键（与 registerTeamForSessionCleanup 同键）
        String key = sessionId;
        Set<String> teams = sessionCreatedTeams.remove(key);
        if (teams == null || teams.isEmpty()) {
            return;
        }
        log.info("[TeamHelpers] cleanupSessionTeams: 清理会话 {} 创建的 {} 个孤儿 team 目录: {}",
            sessionId, teams.size(), String.join(", ", teams));
        for (String name : teams) {
            try {
                cleanupTeamDirectories(name);
            } catch (Exception e) {
                log.warn("[TeamHelpers] cleanupSessionTeams 清理 team {} 失败: {}", name, e.getMessage());
            }
        }
    }

    /**
     * 清理 team + 任务目录 · 对齐 CC teamHelpers.ts:641-683 cleanupTeamDirectories。
     *
     * <p>CC 顺序：destroyWorktree（:657-658，Java in-process 无 worktree，N/A）→ rm teamDir
     * （:661-664）→ rm tasksDir（:673-676）。Java 侧删除 teamDir + tasksDir。
     */
    public void cleanupTeamDirectories(String teamName) {
        // team 目录（含 config.json + inboxes/）
        deleteTeam(teamName);
        // 任务目录（{configHome}/tasks/{sanitizedName}）· [IMP-G] G26① XT-01：对齐 CC
        //   cleanupTeamDirectories（teamHelpers.ts:641-683）{@code sanitizedName = sanitizeName(teamName)}
        //   + getTasksDir(sanitizedName)（:673）；旧 sanitizePathComponent 不转小写断链。
        Path tasksDir = com.nexusai.application.agent.tasks.TaskSystemConfig.getClaudeConfigHomeDir()
            .resolve("tasks").resolve(sanitizeName(teamName));
        if (Files.isDirectory(tasksDir)) {
            try (Stream<Path> walk = Files.walk(tasksDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("删除任务目录项失败: " + p, e);
                    }
                });
                log.info("[TeamHelpers] 已清理任务目录: {}", tasksDir);
            } catch (IOException e) {
                log.warn("[TeamHelpers] 清理任务目录 {} 失败: {}", tasksDir, e.getMessage());
            }
        }
    }

    /** 读取 config.json 为 ObjectNode（缺失/非对象/解析失败 → null）· 供成员函数复用。 */
    private ObjectNode readConfigNode(String teamName) {
        String config = readConfig(teamName);
        if (config == null) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(config);
            if (root == null || !root.isObject()) {
                return null;
            }
            return (ObjectNode) root;
        } catch (Exception e) {
            log.warn("[TeamHelpers] 解析 config.json 失败 team={}: {}", teamName, e.getMessage());
            return null;
        }
    }
}

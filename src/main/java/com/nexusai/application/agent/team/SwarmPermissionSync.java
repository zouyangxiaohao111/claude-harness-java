package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskLock;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Swarm Permission Sync · 对齐 CC {@code Open-ClaudeCode/src/utils/swarm/permissionSync.ts}（928 行）。
 *
 * <p>原教学版内存 stub（{@code submit/resolve/getDecision/getPending} 内存 Map）已整类删除，
 * 全量重写为 CC 真源的双通道权限同步：
 * <ol>
 *   <li><b>文件系统 pending/resolved</b>：{@code ~/.claude/teams/{team}/permissions/{pending|resolved}/{id}.json}
 *       + {@code .lock} 目录级锁（CC permissionSync.ts:112-517 全链）</li>
 *   <li><b>mailbox 转发</b>：worker → leader {@code permission_request}、leader → worker
 *       {@code permission_response}，经 {@link TeammateMailbox#writeToMailbox}（CC :676-783 + sandbox :805-928）</li>
 * </ol>
 *
 * <p>静态工具类（对齐 CC module-level 函数 + {@link TeammateMailbox} 同款风格）；CC 是
 * module 函数无实例状态，Java 静态方法即忠实表达。不注册 Spring Bean（0 消费方注入，
 * 由 {@code permission.SwarmWorkerPermissionHandler} / {@code permission.SwarmPermissionPoller}
 * 静态引用）。
 */
public final class SwarmPermissionSync {

    private static final Logger log = LoggerFactory.getLogger(SwarmPermissionSync.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** leader 缺省 inbox 名 · 对齐 CC getLeaderName 缺省 {@code 'team-lead'}（permissionSync.ts:666）。 */
    public static final String TEAM_LEAD = "team-lead";

    private SwarmPermissionSync() {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 数据契约 · 对齐 CC permissionSync.ts:49-106
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 完整 swarm 权限请求 · 对齐 CC {@code SwarmPermissionRequestSchema}
     * （permissionSync.ts:49-86）全部字段。
     *
     * @param id                   CC original: id (:52) — 唯一请求 ID
     * @param workerId             CC original: workerId (:54) — worker CLAUDE_CODE_AGENT_ID
     * @param workerName           CC original: workerName (:56) — worker CLAUDE_CODE_AGENT_NAME
     * @param workerColor          CC original: workerColor (:58) — 可选颜色
     * @param teamName             CC original: teamName (:60) — 路由 team
     * @param toolName             CC original: toolName (:62) — 工具名
     * @param toolUseId            CC original: toolUseId (:64) — worker 上下文 toolUseID
     * @param description          CC original: description (:66) — 人类可读描述
     * @param input                CC original: input (:68) — 序列化工具输入
     * @param permissionSuggestions CC original: permissionSuggestions (:70) — 建议权限规则
     * @param status               CC original: status (:72) — pending/approved/rejected
     * @param resolvedBy           CC original: resolvedBy (:74) — worker/leader
     * @param resolvedAt           CC original: resolvedAt (:76) — 解析时间戳
     * @param feedback             CC original: feedback (:78) — 拒绝附言
     * @param updatedInput         CC original: updatedInput (:80) — resolver 修改后的 input
     * @param permissionUpdates    CC original: permissionUpdates (:82) — "Always allow" 规则
     * @param createdAt            CC original: createdAt (:84) — 创建时间戳
     */
    public record SwarmPermissionRequest(
            String id, String workerId, String workerName, String workerColor,
            String teamName, String toolName, String toolUseId, String description,
            Map<String, Object> input, List<Object> permissionSuggestions,
            String status, String resolvedBy, Long resolvedAt, String feedback,
            Map<String, Object> updatedInput, List<Object> permissionUpdates, long createdAt) {
    }

    /**
     * 解析数据 · 对齐 CC {@code PermissionResolution}（permissionSync.ts:95-106）。
     *
     * @param decision           CC original: decision (:97) — approved/rejected
     * @param resolvedBy         CC original: resolvedBy (:99) — worker/leader
     * @param feedback           CC original: feedback (:101)
     * @param updatedInput       CC original: updatedInput (:103)
     * @param permissionUpdates  CC original: permissionUpdates (:105)
     */
    public record PermissionResolution(String decision, String resolvedBy, String feedback,
                                       Map<String, Object> updatedInput,
                                       List<Object> permissionUpdates) {
    }

    /**
     * worker 轮询响应 · 对齐 CC {@code PermissionResponse}（permissionSync.ts:523-536）。
     */
    public record PermissionResponse(String requestId, String decision, String timestamp,
                                     String feedback, Map<String, Object> updatedInput,
                                     List<Object> permissionUpdates) {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 目录与路径 · 对齐 CC permissionSync.ts:112-162
    // ════════════════════════════════════════════════════════════════════════

    /** team 根目录 · 对齐 CC getTeamDir（teamHelpers.ts:58-60，[IMP-G] G26① XT-01：改用 sanitizeName
     * 与 TeamHelpers.teamDir 一致——CC getTeamDir :115-117 用 sanitizeName，旧 sanitizePathComponent
     * 不转小写致跨进程互认断链）。 */
    public static Path teamDir(String teamName) {
        return TeammateMailbox.getTeamsDir().resolve(TeamHelpers.sanitizeName(teamName));
    }

    /** 权限根目录 · 对齐 CC getPermissionDir = join(getTeamDir(teamName), 'permissions')（:112-114）。 */
    public static Path getPermissionDir(String teamName) {
        return teamDir(teamName).resolve("permissions");
    }

    /** pending 目录 · 对齐 CC getPendingDir（:119-121）。 */
    public static Path getPendingDir(String teamName) {
        return getPermissionDir(teamName).resolve("pending");
    }

    /** resolved 目录 · 对齐 CC getResolvedDir（:126-128）。 */
    public static Path getResolvedDir(String teamName) {
        return getPermissionDir(teamName).resolve("resolved");
    }

    /** 确保权限目录结构存在 · 对齐 CC ensurePermissionDirsAsync（:133-141）。 */
    private static void ensurePermissionDirs(String teamName) {
        for (Path dir : List.of(getPermissionDir(teamName), getPendingDir(teamName), getResolvedDir(teamName))) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("创建权限目录失败: " + dir, e);
            }
        }
    }

    private static Path getPendingRequestPath(String teamName, String requestId) {
        return getPendingDir(teamName).resolve(requestId + ".json");
    }

    private static Path getResolvedRequestPath(String teamName, String requestId) {
        return getResolvedDir(teamName).resolve(requestId + ".json");
    }

    /** 生成唯一请求 ID · 对齐 CC generateRequestId = {@code perm-{ts}-{rand}}（:160-162）。 */
    public static String generateRequestId() {
        return "perm-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 身份判定 · 对齐 CC permissionSync.ts:581-601 + teammate.ts:88-118
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否 leader · 对齐 CC {@code isTeamLeader}（permissionSync.ts:581-591）：
     * 无 agentId 或 agentId === 'team-lead' → leader。
     *
     * <p>Java 身份经 sysprop 代理（{@link TaskSystemConfig#getAgentName} 为 {@code nexusai.agent.name}，
     * 无独立 agentId 属性），故 leader 判定用 agentName == null/blank/team-lead 等价。
     */
    public static boolean isTeamLeader(String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        String agentName = TaskSystemConfig.getAgentName();
        return agentName == null || agentName.isBlank() || TEAM_LEAD.equals(agentName);
    }

    /**
     * 是否 swarm worker · 对齐 CC {@code isSwarmWorker}（permissionSync.ts:596-601）：
     * {@code !!teamName && !!agentId && !isTeamLeader()}。
     */
    public static boolean isSwarmWorker() {
        String teamName = TaskSystemConfig.getTeamName();
        String agentName = TaskSystemConfig.getAgentName();
        return teamName != null && !teamName.isBlank()
                && agentName != null && !agentName.isBlank()
                && !isTeamLeader(teamName);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 请求创建 · 对齐 CC createPermissionRequest（:167-207）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 创建权限请求 · 对齐 CC createPermissionRequest（:167-207）：teamName/workerId/workerName/workerColor
     * 由运行时上下文派生，缺 team/workerId/workerName 抛错。
     */
    public static SwarmPermissionRequest createPermissionRequest(String toolName, String toolUseId,
                                                                 Map<String, Object> input,
                                                                 String description,
                                                                 List<Object> permissionSuggestions) {
        return createPermissionRequest(generateRequestId(), toolName, toolUseId, input,
                description, permissionSuggestions);
    }

    /**
     * 创建权限请求（显式 id）· 同上，但 id 由调用方提供（worker 侧已生成 id 用于 callback 注册）。
     */
    public static SwarmPermissionRequest createPermissionRequest(String id, String toolName, String toolUseId,
                                                                 Map<String, Object> input,
                                                                 String description,
                                                                 List<Object> permissionSuggestions) {
        String teamName = TaskSystemConfig.getTeamName();
        String workerName = TaskSystemConfig.getAgentName();
        String workerColor = TaskSystemConfig.getTeammateColor();

        if (teamName == null || teamName.isBlank()) {
            throw new IllegalStateException("Team name is required for permission requests");
        }
        if (workerName == null || workerName.isBlank()) {
            throw new IllegalStateException("Worker name is required for permission requests");
        }
        // CC workerId = getAgentId()（"name@team"）；Java 无独立 agentId，用 name@team 合成代理
        String workerId = workerName + "@" + teamName;

        return new SwarmPermissionRequest(
                id, workerId, workerName, workerColor, teamName,
                toolName, toolUseId, description, input,
                permissionSuggestions != null ? permissionSuggestions : List.of(),
                "pending", null, null, null, null, null, System.currentTimeMillis());
    }

    /**
     * 创建权限请求（显式 worker 身份）· 对齐 CC inProcessRunner.ts:338-348 的显式身份派生
     * （in-process swarm 多 worker 共进程，身份不可取 TaskSystemConfig 当前进程身份）。
     * id 由 {@link #generateRequestId} 生成（对齐 CC permissionSync.ts:160-162）。
     */
    public static SwarmPermissionRequest createPermissionRequest(String toolName, String toolUseId,
                                                                 Map<String, Object> input,
                                                                 String description,
                                                                 List<Object> permissionSuggestions,
                                                                 String teamName, String workerId,
                                                                 String workerName, String workerColor) {
        String resolvedTeam = teamName != null && !teamName.isBlank() ? teamName : TaskSystemConfig.getTeamName();
        String resolvedWorkerName = workerName != null && !workerName.isBlank() ? workerName : TaskSystemConfig.getAgentName();
        String resolvedWorkerColor = workerColor != null ? workerColor : TaskSystemConfig.getTeammateColor();

        if (resolvedTeam == null || resolvedTeam.isBlank()) {
            throw new IllegalStateException("Team name is required for permission requests");
        }
        if (resolvedWorkerName == null || resolvedWorkerName.isBlank()) {
            throw new IllegalStateException("Worker name is required for permission requests");
        }
        // CC workerId = getAgentId()（"name@team"）；显式传入优先，缺省 name@team 合成代理
        String resolvedWorkerId = workerId != null && !workerId.isBlank()
                ? workerId : resolvedWorkerName + "@" + resolvedTeam;

        return new SwarmPermissionRequest(
                generateRequestId(), resolvedWorkerId, resolvedWorkerName, resolvedWorkerColor, resolvedTeam,
                toolName, toolUseId, description, input,
                permissionSuggestions != null ? permissionSuggestions : List.of(),
                "pending", null, null, null, null, null, System.currentTimeMillis());
    }

    // ════════════════════════════════════════════════════════════════════════
    // 文件系统 pending/resolved · 对齐 CC permissionSync.ts:215-517
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 写 pending 请求（目录级锁）· 对齐 CC writePermissionRequest（:215-250）。
     */
    public static SwarmPermissionRequest writePermissionRequest(SwarmPermissionRequest request) {
        ensurePermissionDirs(request.teamName());
        Path pendingPath = getPendingRequestPath(request.teamName(), request.id());
        Path pendingDir = getPendingDir(request.teamName());
        try {
            // 对齐 CC :229 lockfile.lock(join(lockDir, '.lock')) — TaskLock.withLockAndReturn
            // 列表级锁目标 = pendingDir/.lock，与 CC join(lockDir,'.lock') 一致
            TaskLock.withLockAndReturn(pendingDir, () -> {
                try {
                    Files.writeString(pendingPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(request),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException("写 pending 权限请求失败: " + pendingPath, e);
                }
                return null;
            });
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionSync] 已写 pending 请求 id={} worker={} tool={}",
                        request.id(), request.workerName(), request.toolName());
            }
            return request;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 写 pending 请求失败 id={}: {}", request.id(), e.toString());
            throw e;
        }
    }

    /** 读全部 pending 请求（按 createdAt 升序）· 对齐 CC readPendingPermissions（:256-312）。 */
    public static List<SwarmPermissionRequest> readPendingPermissions(String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return List.of();
        }
        Path pendingDir = getPendingDir(team);
        List<SwarmPermissionRequest> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(pendingDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().equals(".lock"))
                    .toList();
            for (Path file : jsonFiles) {
                try {
                    SwarmPermissionRequest req = JSON.readValue(
                            Files.readString(file), SwarmPermissionRequest.class);
                    results.add(req);
                } catch (Exception err) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SwarmPermissionSync] 无效 pending 请求文件 {}: {}", file.getFileName(), err.toString());
                    }
                }
            }
        } catch (NoSuchFileException e) {
            // ENOENT → []（CC :272-274）
            return List.of();
        } catch (IOException e) {
            log.error("[SwarmPermissionSync] 读 pending 失败 team={}: {}", team, e.toString());
            return List.of();
        }
        results.sort(Comparator.comparingLong(SwarmPermissionRequest::createdAt));
        return results;
    }

    /** 读 resolved 请求 · 对齐 CC readResolvedPermission（:320-352）。 */
    public static SwarmPermissionRequest readResolvedPermission(String requestId, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return null;
        }
        Path resolvedPath = getResolvedRequestPath(team, requestId);
        try {
            return JSON.readValue(Files.readString(resolvedPath), SwarmPermissionRequest.class);
        } catch (NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 读 resolved 失败 id={}: {}", requestId, e.toString());
            return null;
        }
    }

    /**
     * 解析请求：写 resolved/ + 移除 pending/ · 对齐 CC resolvePermission（:360-443）。
     */
    public static boolean resolvePermission(String requestId, PermissionResolution resolution, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        ensurePermissionDirs(team);
        Path pendingPath = getPendingRequestPath(team, requestId);
        Path resolvedPath = getResolvedRequestPath(team, requestId);
        Path pendingDir = getPendingDir(team);
        try {
            return TaskLock.withLockAndReturn(pendingDir, () -> {
                SwarmPermissionRequest request;
                try {
                    request = JSON.readValue(Files.readString(pendingPath), SwarmPermissionRequest.class);
                } catch (NoSuchFileException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SwarmPermissionSync] pending 请求不存在 id={}", requestId);
                    }
                    return false;
                } catch (IOException e) {
                    throw new UncheckedIOException("读 pending 失败: " + pendingPath, e);
                }
                SwarmPermissionRequest resolved = new SwarmPermissionRequest(
                        request.id(), request.workerId(), request.workerName(), request.workerColor(),
                        request.teamName(), request.toolName(), request.toolUseId(), request.description(),
                        request.input(), request.permissionSuggestions(),
                        "approved".equals(resolution.decision()) ? "approved" : "rejected",
                        resolution.resolvedBy(), System.currentTimeMillis(), resolution.feedback(),
                        resolution.updatedInput(), resolution.permissionUpdates(), request.createdAt());
                try {
                    Files.writeString(resolvedPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(resolved),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.deleteIfExists(pendingPath);
                } catch (IOException e) {
                    throw new UncheckedIOException("写 resolved/删 pending 失败: " + resolvedPath, e);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SwarmPermissionSync] 已解析请求 id={} decision={}", requestId, resolution.decision());
                }
                return true;
            });
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 解析请求失败 id={}: {}", requestId, e.toString());
            return false;
        }
    }

    /** 轮询响应 · 对齐 CC pollForResponse（:544-564）。 */
    public static PermissionResponse pollForResponse(String requestId, String agentName, String teamName) {
        SwarmPermissionRequest resolved = readResolvedPermission(requestId, teamName);
        if (resolved == null) {
            return null;
        }
        long ts = resolved.resolvedAt() != null ? resolved.resolvedAt() : resolved.createdAt();
        return new PermissionResponse(
                resolved.id(), "approved".equals(resolved.status()) ? "approved" : "denied",
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ts).truncatedTo(ChronoUnit.MILLIS)),
                resolved.feedback(), resolved.updatedInput(), resolved.permissionUpdates());
    }

    /** 删除 resolved · 对齐 CC deleteResolvedPermission（:607-635）。 */
    public static boolean deleteResolvedPermission(String requestId, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        Path resolvedPath = getResolvedRequestPath(team, requestId);
        try {
            return Files.deleteIfExists(resolvedPath);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            log.error("[SwarmPermissionSync] 删除 resolved 失败 id={}: {}", requestId, e.toString());
            return false;
        }
    }

    /** 删除 worker 响应（poll 处理后的清理）· 对齐 CC removeWorkerResponse（:570-576）。 */
    public static void removeWorkerResponse(String requestId, String agentName, String teamName) {
        deleteResolvedPermission(requestId, teamName);
    }

    /** 清理旧 resolved · 对齐 CC cleanupOldResolutions（:452-517）。 */
    public static int cleanupOldResolutions(String teamName, long maxAgeMs) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return 0;
        }
        Path resolvedDir = getResolvedDir(team);
        int cleaned = 0;
        try (Stream<Path> files = Files.list(resolvedDir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();
            long now = System.currentTimeMillis();
            for (Path file : jsonFiles) {
                try {
                    SwarmPermissionRequest request = JSON.readValue(
                            Files.readString(file), SwarmPermissionRequest.class);
                    long resolvedAt = request.resolvedAt() != null ? request.resolvedAt() : request.createdAt();
                    if (now - resolvedAt >= maxAgeMs) {
                        Files.deleteIfExists(file);
                        cleaned++;
                    }
                } catch (Exception e) {
                    // 解析失败也清理（CC :496-503）
                    try {
                        if (Files.deleteIfExists(file)) {
                            cleaned++;
                        }
                    } catch (IOException ignored) {
                        // 忽略删除错误
                    }
                }
            }
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            log.error("[SwarmPermissionSync] 清理 resolved 失败 team={}: {}", team, e.toString());
            return 0;
        }
        if (cleaned > 0 && log.isDebugEnabled()) {
            log.debug("[SwarmPermissionSync] 清理 {} 条旧 resolved", cleaned);
        }
        return cleaned;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Mailbox 转发 · 对齐 CC permissionSync.ts:651-783
    // ════════════════════════════════════════════════════════════════════════

    /** leader 名 · 对齐 CC getLeaderName（permissionSync.ts:651-667）：读 team 文件
     *  {@code members.find(m => m.agentId === leadAgentId)?.name}；team 文件缺失 / 解析失败 → {@code null}
     *  （CC :657-661 readTeamFileAsync ENOENT / jsonParse 异常 → null）；无匹配 / 无 leadAgentId
     *  回退 {@code 'team-lead'}（CC :666）。 */
    public static String getLeaderName(String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return null;
        }
        TeamHelpers helpers = new TeamHelpers();
        String config = helpers.readConfig(team);
        if (config == null) {
            // CC readTeamFileAsync ENOENT → null（permissionSync.ts:657-661），
            // 调用方 sendPermissionRequestViaMailbox / dispatchOnce 据此 return false / return 0，
            // 不向 phantom "team-lead" 邮箱发送（未上线全量对齐 CC 不留兼容壳）。
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionSync] team 文件不存在 team={}：leader 名未找到，返回 null", team);
            }
            return null;
        }
        try {
            JsonNode root = JSON.readTree(config);
            String leadAgentId = root.path("leadAgentId").asText(null);
            JsonNode members = root.path("members");
            if (leadAgentId != null && !leadAgentId.isBlank() && members.isArray()) {
                for (JsonNode m : members) {
                    if (m.isObject() && leadAgentId.equals(m.path("agentId").asText())) {
                        String name = m.path("name").asText(null);
                        return name == null || name.isBlank() ? TEAM_LEAD : name;
                    }
                }
            }
        } catch (Exception e) {
            // CC readTeamFileAsync jsonParse 异常 → null（teamHelpers.ts:139-145 catch→null），
            // getLeaderName 因此返回 null，不静默回退 "team-lead"。
            log.warn("[SwarmPermissionSync] 解析 team 配置失败 team={}：返回 null，详情={}", team, e.getMessage());
            return null;
        }
        return TEAM_LEAD;
    }

    /**
     * worker → leader 权限请求（mailbox）· 对齐 CC sendPermissionRequestViaMailbox（:676-722）。
     */
    public static boolean sendPermissionRequestViaMailbox(SwarmPermissionRequest request) {
        String leaderName = getLeaderName(request.teamName());
        if (leaderName == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionSync] 无法发送权限请求：leader 名未找到");
            }
            return false;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "permission_request");
            message.put("request_id", request.id());
            message.put("agent_id", request.workerName());
            message.put("tool_name", request.toolName());
            message.put("tool_use_id", request.toolUseId());
            message.put("description", request.description());
            message.put("input", request.input() != null ? request.input() : Map.of());
            message.put("permission_suggestions",
                    request.permissionSuggestions() != null ? request.permissionSuggestions() : List.of());
            TeammateMailbox.writeToMailbox(leaderName,
                    TeammateMailbox.TeammateMessage.of(request.workerName(),
                            JSON.writeValueAsString(message), TeammateMailbox.isoNow(), request.workerColor()),
                    request.teamName());
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionSync] 已发权限请求 {} 到 leader {} via mailbox",
                        request.id(), leaderName);
            }
            return true;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 发权限请求失败 id={}: {}", request.id(), e.toString());
            return false;
        }
    }

    /**
     * leader → worker 权限响应（mailbox）· 对齐 CC sendPermissionResponseViaMailbox（:734-783）。
     */
    public static boolean sendPermissionResponseViaMailbox(String workerName, PermissionResolution resolution,
                                                           String requestId, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "permission_response");
            message.put("request_id", requestId);
            if ("approved".equals(resolution.decision())) {
                message.put("subtype", "success");
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("updated_input", resolution.updatedInput());
                response.put("permission_updates", resolution.permissionUpdates());
                message.put("response", response);
            } else {
                message.put("subtype", "error");
                message.put("error", resolution.feedback() != null ? resolution.feedback() : "Permission denied");
            }
            String senderName = TaskSystemConfig.getAgentName() != null
                    && !TaskSystemConfig.getAgentName().isBlank()
                    ? TaskSystemConfig.getAgentName() : TEAM_LEAD;
            TeammateMailbox.writeToMailbox(workerName,
                    TeammateMailbox.TeammateMessage.of(senderName,
                            JSON.writeValueAsString(message), TeammateMailbox.isoNow(), null),
                    team);
            if (log.isDebugEnabled()) {
                log.debug("[SwarmPermissionSync] 已发权限响应 {} 到 worker {} via mailbox", requestId, workerName);
            }
            return true;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 发权限响应失败 id={}: {}", requestId, e.toString());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sandbox mailbox · 对齐 CC permissionSync.ts:789-928
    // ════════════════════════════════════════════════════════════════════════

    /** 生成 sandbox 请求 ID · 对齐 CC generateSandboxRequestId（:792-794）。 */
    public static String generateSandboxRequestId() {
        return "sandbox-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
    }

    /** worker → leader sandbox 请求 · 对齐 CC sendSandboxPermissionRequestViaMailbox（:805-869）。 */
    public static boolean sendSandboxPermissionRequestViaMailbox(String host, String requestId, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        String leaderName = getLeaderName(team);
        if (leaderName == null) {
            return false;
        }
        String workerName = TaskSystemConfig.getAgentName();
        String workerColor = TaskSystemConfig.getTeammateColor();
        if (workerName == null || workerName.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "sandbox_permission_request");
            message.put("requestId", requestId);
            message.put("workerId", workerName + "@" + team);
            message.put("workerName", workerName);
            if (workerColor != null) {
                message.put("workerColor", workerColor);
            }
            message.put("hostPattern", Map.of("host", host));
            message.put("createdAt", System.currentTimeMillis());
            TeammateMailbox.writeToMailbox(leaderName,
                    TeammateMailbox.TeammateMessage.of(workerName,
                            JSON.writeValueAsString(message), TeammateMailbox.isoNow(), workerColor),
                    team);
            return true;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 发 sandbox 权限请求失败 id={}: {}", requestId, e.toString());
            return false;
        }
    }

    /** leader → worker sandbox 响应 · 对齐 CC sendSandboxPermissionResponseViaMailbox（:882-928）。 */
    public static boolean sendSandboxPermissionResponseViaMailbox(String workerName, String requestId,
                                                                  String host, boolean allow, String teamName) {
        String team = teamName != null ? teamName : TaskSystemConfig.getTeamName();
        if (team == null || team.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "sandbox_permission_response");
            message.put("requestId", requestId);
            message.put("host", host);
            message.put("allow", allow);
            message.put("timestamp", TeammateMailbox.isoNow());
            String senderName = TaskSystemConfig.getAgentName() != null
                    && !TaskSystemConfig.getAgentName().isBlank()
                    ? TaskSystemConfig.getAgentName() : TEAM_LEAD;
            TeammateMailbox.writeToMailbox(workerName,
                    TeammateMailbox.TeammateMessage.of(senderName,
                            JSON.writeValueAsString(message), TeammateMailbox.isoNow(), null),
                    team);
            return true;
        } catch (Exception e) {
            log.error("[SwarmPermissionSync] 发 sandbox 权限响应失败 id={}: {}", requestId, e.toString());
            return false;
        }
    }
}

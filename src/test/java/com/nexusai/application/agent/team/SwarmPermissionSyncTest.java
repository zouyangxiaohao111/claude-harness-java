package com.nexusai.application.agent.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SwarmPermissionSync 文件系统 + mailbox 转发测试 · 对齐 CC permissionSync.ts。
 *
 * <p>验证意图（规则九）：RV-07 整链对齐后，worker→leader 权限请求经真实文件系统 pending/
 * resolved（+ lockfile）与 mailbox 双通道同步，而非旧内存 Map stub。若实现退回内存版
 * （进程重启即丢），本测试的「写盘 round-trip」断言必红。
 *
 * <p>合并两侧测试（AA 冲突）：保留 master 侧 7 个 @Test + 本侧 10 个独有方法，覆盖
 * 17 字段契约 / 身份缺失抛错 / 请求 ID 前缀 / leader 判定 / leader 名解析 / sandbox 双通道 /
 * 文件型 pending-resolved / cleanupOldResolutions 三态，防止回归覆盖丢失。
 */
@DisplayName("SwarmPermissionSync 文件系统 + mailbox 对齐 CC permissionSync.ts")
class SwarmPermissionSyncTest {

    /** 本侧（batch）测试的 team 名，与 master 侧 sysprop 派生的 "my-team" 隔离。 */
    private static final String TEAM = "research-team";

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.agent.name", "worker-a");
        System.setProperty("nexusai.team.name", "my-team");
        writeTeamConfig();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.task.config-dir");
        System.clearProperty("nexusai.agent.name");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.agent.color");
    }

    /** 写入 team config：leadAgentId=lead@team，members 含 team-lead（leader）与 alice（worker）。
     *  同时为 master 侧 sysprop 派生的 "my-team" 写同构 config，使 getLeaderName 能经
     *  {@code members.find(agentId === leadAgentId)} 精确解析 leader 名（对齐 CC），而非依赖
     *  旧 code path 的 "team-lead" 兜底（ML-2 已删除该兜底）。 */
    private void writeTeamConfig() throws Exception {
        Path teamDir = tempDir.resolve("teams").resolve(TEAM);
        Files.createDirectories(teamDir);
        String cfg = "{\"leadAgentId\":\"lead@team\",\"members\":["
            + "{\"agentId\":\"lead@team\",\"name\":\"team-lead\"},"
            + "{\"agentId\":\"alice@team\",\"name\":\"alice\"}]}";
        Files.writeString(teamDir.resolve("config.json"), cfg);

        // master 侧测试经 nexusai.team.name=my-team 路由；写同构 config 使
        // sendPermissionRequestViaMailbox → getLeaderName("my-team") 精确解析 "team-lead"。
        Path masterTeamDir = tempDir.resolve("teams").resolve("my-team");
        Files.createDirectories(masterTeamDir);
        String masterCfg = "{\"leadAgentId\":\"lead@my-team\",\"members\":["
            + "{\"agentId\":\"lead@my-team\",\"name\":\"team-lead\"},"
            + "{\"agentId\":\"worker-a@my-team\",\"name\":\"worker-a\"}]}";
        Files.writeString(masterTeamDir.resolve("config.json"), masterCfg);
    }

    /** 构建显式 worker 身份的权限请求（9 参 createPermissionRequest，对齐 CC inProcessRunner 显式身份）。 */
    private SwarmPermissionSync.SwarmPermissionRequest newRequest() {
        return SwarmPermissionSync.createPermissionRequest(
            "Bash", "toolu-1", Map.of("command", "ls"), "run ls", List.of(),
            TEAM, "alice@team", "alice", "red");
    }

    // ════════════════════════════════════════════════════════════════════════
    // master 侧 7 个测试（保留原样）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("createPermissionRequest 由 team 上下文派生 workerId/workerName（CC :167-207）")
    void createPermissionRequest_derivesIdentity() {
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "Bash", "tooluse-1", Map.of("command", "ls"), "run ls", List.of());
        assertThat(req.teamName()).isEqualTo("my-team");
        assertThat(req.workerName()).isEqualTo("worker-a");
        assertThat(req.workerId()).isEqualTo("worker-a@my-team");
        assertThat(req.status()).isEqualTo("pending");
        assertThat(req.toolName()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("writePermissionRequest + readPendingPermissions 写盘 round-trip（CC :215-312）")
    void writeAndReadPending_roundTrip() {
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "Edit", "tooluse-2", Map.of("path", "a.txt"), "edit", List.of());
        SwarmPermissionSync.writePermissionRequest(req);

        List<SwarmPermissionSync.SwarmPermissionRequest> pending =
            SwarmPermissionSync.readPendingPermissions("my-team");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(req.id());
        assertThat(pending.get(0).toolName()).isEqualTo("Edit");
    }

    @Test
    @DisplayName("resolvePermission 写 resolved/ + 移除 pending/（CC :360-443）")
    void resolvePermission_movesToResolved() {
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "Bash", "tooluse-3", Map.of("command", "x"), "run x", List.of());
        SwarmPermissionSync.writePermissionRequest(req);

        boolean resolved = SwarmPermissionSync.resolvePermission(req.id(),
            new SwarmPermissionSync.PermissionResolution("approved", "leader", null,
                null, List.of()), "my-team");
        assertThat(resolved).isTrue();
        assertThat(SwarmPermissionSync.readPendingPermissions("my-team")).isEmpty();
        assertThat(SwarmPermissionSync.readResolvedPermission(req.id(), "my-team")).isNotNull();
        assertThat(SwarmPermissionSync.readResolvedPermission(req.id(), "my-team").status())
            .isEqualTo("approved");
    }

    @Test
    @DisplayName("pollForResponse 把 resolved 转成 PermissionResponse（CC :544-564）")
    void pollForResponse_afterResolve() {
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "Bash", "tooluse-4", Map.of("command", "y"), "run y", List.of());
        SwarmPermissionSync.writePermissionRequest(req);
        SwarmPermissionSync.resolvePermission(req.id(),
            new SwarmPermissionSync.PermissionResolution("rejected", "leader", "nope", null, List.of()),
            "my-team");

        SwarmPermissionSync.PermissionResponse resp =
            SwarmPermissionSync.pollForResponse(req.id(), "worker-a", "my-team");
        assertThat(resp).isNotNull();
        assertThat(resp.decision()).isEqualTo("denied");
        assertThat(resp.feedback()).isEqualTo("nope");
    }

    @Test
    @DisplayName("isSwarmWorker 由 team 上下文派生（CC :596-601）：teamName && agentId && !leader")
    void isSwarmWorker_derivedFromTeamContext() {
        assertThat(SwarmPermissionSync.isSwarmWorker()).isTrue();
        assertThat(SwarmPermissionSync.isTeamLeader("my-team")).isFalse();
    }

    @Test
    @DisplayName("sendPermissionRequestViaMailbox 写入 leader inbox（CC :676-722）")
    void sendPermissionRequestViaMailbox_writesToLeaderInbox() {
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "Bash", "tooluse-5", Map.of("command", "z"), "run z", List.of());
        boolean sent = SwarmPermissionSync.sendPermissionRequestViaMailbox(req);
        assertThat(sent).isTrue();

        List<TeammateMailbox.TeammateMessage> leaderInbox =
            TeammateMailbox.readMailbox("team-lead", "my-team");
        assertThat(leaderInbox).hasSize(1);
        assertThat(leaderInbox.get(0).text()).contains("permission_request");
        assertThat(leaderInbox.get(0).text()).contains(req.id());
    }

    @Test
    @DisplayName("sendPermissionResponseViaMailbox 写入 worker inbox（CC :734-783）")
    void sendPermissionResponseViaMailbox_writesToWorkerInbox() {
        boolean sent = SwarmPermissionSync.sendPermissionResponseViaMailbox("worker-a",
            new SwarmPermissionSync.PermissionResolution("approved", "leader", null,
                Map.of("command", "z"), List.of()),
            "perm-req-1", "my-team");
        assertThat(sent).isTrue();

        List<TeammateMailbox.TeammateMessage> workerInbox =
            TeammateMailbox.readMailbox("worker-a", "my-team");
        assertThat(workerInbox).hasSize(1);
        assertThat(workerInbox.get(0).text()).contains("permission_response");
        assertThat(workerInbox.get(0).text()).contains("perm-req-1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 本侧独有 10 个测试（恢复回归覆盖，适配静态 API）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void createPermissionRequest_produces17FieldContract() {
        // WHY: CC SwarmPermissionRequestSchema (:49-86) 为 17 字段。缺字段 → leader 侧
        //      SwarmPermissionRequestSchema.safeParse 失败被过滤（CC :287-295）。
        SwarmPermissionSync.SwarmPermissionRequest r = newRequest();
        assertTrue(r.id().startsWith("perm-"), "id 前缀 perm-");
        assertEquals("alice@team", r.workerId());
        assertEquals("alice", r.workerName());
        assertEquals("red", r.workerColor());
        assertEquals(TEAM, r.teamName());
        assertEquals("Bash", r.toolName());
        assertEquals("toolu-1", r.toolUseId());
        assertEquals("run ls", r.description());
        assertEquals("ls", r.input().get("command"));
        assertTrue(r.permissionSuggestions().isEmpty(), "缺省 permissionSuggestions 应为空数组");
        assertEquals("pending", r.status());
        assertTrue(r.createdAt() > 0, "createdAt 应为毫秒时间戳");
        // 未解析字段全部 null（CC :74-82 可选字段缺省 undefined）
        assertEquals(null, r.resolvedBy());
        assertEquals(null, r.resolvedAt());
        assertEquals(null, r.feedback());
        assertEquals(null, r.updatedInput());
        assertEquals(null, r.permissionUpdates());
    }

    @Test
    void createPermissionRequest_throwsWhenIdentityMissing() {
        // WHY: CC createPermissionRequest (:183-191) 对 teamName/workerId/workerName 缺省时抛异常，
        //      防止无上下文时静默生成残缺请求。Java 无上下文时 getTeamName 返回 null → 必抛。
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.agent.name");
        assertThrows(IllegalStateException.class,
            () -> SwarmPermissionSync.createPermissionRequest(
                "Bash", "toolu-1", Map.of(), "d", List.of(), null, null, null, null),
            "缺 teamName/workerId/workerName 必须抛异常");
    }

    @Test
    void generateRequestId_hasPermPrefix() {
        assertTrue(SwarmPermissionSync.generateRequestId().startsWith("perm-"));
        assertTrue(SwarmPermissionSync.generateSandboxRequestId().startsWith("sandbox-"));
    }

    @Test
    void isTeamLeader_trueWhenNoAgentId() {
        // WHY: CC isTeamLeader (:581-591) 无 agentId（原始创建 team 的 session）即 leader。
        System.clearProperty("nexusai.agent.name");
        assertTrue(SwarmPermissionSync.isTeamLeader(TEAM));
    }

    @Test
    void getLeaderName_resolvesLeadAgentIdField() throws Exception {
        // WHY: CC getLeaderName (permissionSync.ts:651-667) 读 TeamFile.leadAgentId，再
        //      members.find(agentId === leadAgentId).name。leader 名用 "captain" 以区分
        //      默认回退 "team-lead"——若误读成 leadAgentId 前缀截取（"lead"），本测试即红。
        Path teamDir = tempDir.resolve("teams").resolve(TEAM);
        String cfg = "{\"leadAgentId\":\"lead@team\",\"members\":["
            + "{\"agentId\":\"lead@team\",\"name\":\"captain\"},"
            + "{\"agentId\":\"alice@team\",\"name\":\"alice\"}]}";
        Files.writeString(teamDir.resolve("config.json"), cfg);

        assertEquals("captain", SwarmPermissionSync.getLeaderName(TEAM),
            "leader 名必须从 leadAgentId 匹配成员解析（非 leadAgentId 前缀截取）");
    }

    @Test
    void getLeaderName_returnsNullWhenTeamFileMissing() {
        // WHY: CC getLeaderName (permissionSync.ts:657-661) 读 team 文件失败（ENOENT）→ 返回 null，
        //      调用方 sendPermissionRequestViaMailbox 据此 return false，而非向 phantom "team-lead"
        //      邮箱发送。若 Java 误回退 "team-lead"，本测试即红（未上线全量对齐 CC 不留兼容壳）。
        assertEquals(null, SwarmPermissionSync.getLeaderName("no-such-team"),
            "team 文件不存在时必须返回 null（对齐 CC readTeamFileAsync ENOENT→null）");
    }

    @Test
    void getLeaderName_returnsNullWhenTeamConfigUnparseable() throws Exception {
        // WHY: CC readTeamFileAsync jsonParse 异常 → 返回 null（teamHelpers.ts:139-145 catch→null），
        //      getLeaderName 因此返回 null。损坏配置不能静默回退 "team-lead" 导致向错误邮箱发送。
        Path teamDir = tempDir.resolve("teams").resolve("corrupt-team");
        Files.createDirectories(teamDir);
        Files.writeString(teamDir.resolve("config.json"), "{not valid json");

        assertEquals(null, SwarmPermissionSync.getLeaderName("corrupt-team"),
            "team 配置解析失败时必须返回 null（对齐 CC jsonParse 异常→null）");
    }

    @Test
    void sendSandboxPermissionViaMailbox_roundTrip() {
        // WHY: sandbox 网络访问权限请求/响应（permissionSync.ts:805-928）独立于 tool 权限，
        //      独立 host/allow 字段，独立双通道。Java worker 身份经 TaskSystemConfig sysprop 代理。
        assertTrue(SwarmPermissionSync.sendSandboxPermissionRequestViaMailbox("example.com", "sb-1", TEAM));
        List<TeammateMailbox.TeammateMessage> leadInbox = TeammateMailbox.readMailbox("team-lead", TEAM);
        TeammateMailbox.SandboxPermissionRequestMessage req =
            TeammateMailbox.isSandboxPermissionRequest(leadInbox.get(0).text());
        assertNotNull(req);
        assertEquals("sb-1", req.requestId());
        assertEquals("example.com", req.hostPattern().host());

        assertTrue(SwarmPermissionSync.sendSandboxPermissionResponseViaMailbox("alice", "sb-1", "example.com", true, TEAM));
        List<TeammateMailbox.TeammateMessage> aliceInbox = TeammateMailbox.readMailbox("alice", TEAM);
        TeammateMailbox.SandboxPermissionResponseMessage resp =
            TeammateMailbox.isSandboxPermissionResponse(aliceInbox.get(0).text());
        assertNotNull(resp);
        assertTrue(resp.allow());
    }

    @Test
    void writeAndResolvePermission_persistsPendingThenResolved() {
        // WHY: 文件型 pending/resolved 目录是 worker 轮询 pollForResponse 的存储层；
        //      resolvePermission 写 resolved/ 删 pending/（CC :360-443）。
        SwarmPermissionSync.SwarmPermissionRequest r = newRequest();
        SwarmPermissionSync.writePermissionRequest(r);
        assertEquals(1, SwarmPermissionSync.readPendingPermissions(TEAM).size());

        assertTrue(SwarmPermissionSync.resolvePermission(r.id(),
            new SwarmPermissionSync.PermissionResolution("approved", "leader", null, null, List.of()), TEAM));
        assertTrue(SwarmPermissionSync.readPendingPermissions(TEAM).isEmpty(), "resolve 后 pending 应删除");
        SwarmPermissionSync.PermissionResponse resp = SwarmPermissionSync.pollForResponse(r.id(), "alice", TEAM);
        assertNotNull(resp);
        assertEquals("approved", resp.decision());
        assertEquals(r.id(), resp.requestId());
    }

    @Test
    void cleanupOldResolutions_deletesOldResolvedFiles() throws Exception {
        // WHY: CC cleanupOldResolutions (:452-517) 周期性清理 resolved/ 旧文件防止累积；
        //      resolvedAt 超 maxAgeMs → unlink（:486-494）。若不清理，resolved/ 无限增长。
        Path resolvedDir = SwarmPermissionSync.getResolvedDir(TEAM);
        long old = System.currentTimeMillis() - 2 * 3_600_000L;
        SwarmPermissionSync.SwarmPermissionRequest oldReq = new SwarmPermissionSync.SwarmPermissionRequest(
            "perm-old", "alice@team", "alice", "red", TEAM, "Bash", "toolu-1", "old",
            Map.of(), List.of(), "approved", "leader", old, null, null, null, old);
        Files.createDirectories(resolvedDir);
        Files.writeString(resolvedDir.resolve("perm-old.json"), new ObjectMapper().writeValueAsString(oldReq));

        assertEquals(1, SwarmPermissionSync.cleanupOldResolutions(TEAM, 3_600_000L), "超龄 resolved 文件应被清理");
        assertEquals(null, SwarmPermissionSync.readResolvedPermission("perm-old", TEAM), "清理后 resolved 文件应不存在");
    }

    @Test
    void cleanupOldResolutions_keepsFreshResolvedFiles() {
        // WHY: resolvedAt 未超 maxAgeMs → 保留（CC :493 返回 0）。清理不能误删刚 resolve 的结果。
        SwarmPermissionSync.SwarmPermissionRequest r = newRequest();
        SwarmPermissionSync.writePermissionRequest(r);
        assertTrue(SwarmPermissionSync.resolvePermission(r.id(),
            new SwarmPermissionSync.PermissionResolution("approved", "leader", null, null, List.of()), TEAM));

        assertEquals(0, SwarmPermissionSync.cleanupOldResolutions(TEAM, 3_600_000L), "新鲜 resolved 文件不应被清理");
        assertNotNull(SwarmPermissionSync.readResolvedPermission(r.id(), TEAM), "新鲜 resolved 文件应保留");
    }

    @Test
    void cleanupOldResolutions_deletesUnparseableFiles() throws Exception {
        // WHY: CC :495-504 无法解析的文件（损坏 JSON）一并清理（catch → unlink），
        //      不能因为一个坏文件阻塞整轮清理。
        Path resolvedDir = SwarmPermissionSync.getResolvedDir(TEAM);
        Files.createDirectories(resolvedDir);
        Files.writeString(resolvedDir.resolve("corrupt.json"), "{not valid json");

        assertTrue(SwarmPermissionSync.cleanupOldResolutions(TEAM, 3_600_000L) >= 1, "无法解析的文件应被清理");
    }
}

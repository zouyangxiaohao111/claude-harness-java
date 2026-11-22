package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SendMessageTool;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重启后按磁盘 sidecar 重建退役表 · 「后端重启后子代理仍可被 SendMessage 恢复」。
 *
 * <h2>WHY</h2>
 * <p>退役表与活跃名册都是内存态，重启即空；旧行为下按名给已完成的子代理发消息会 not-found，
 * transcript 恢复分支不可达。本组用例锁死：扫描位置/字段、幂等、坏 meta 容错、已删除会话不复活、
 * 用户 Stop 标记在重建后仍生效。
 */
@DisplayName("[restart-rebuild] 重启后从 sidecar 重建退役表")
class RetiredAgentRebuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEX = "a0123456789abcdef";
    private static final String HEX2 = "a111111111111111";
    private static final String SESS = "sess-rhy";

    @AfterEach
    void clearCwd() {
        com.nexusai.application.agent.agent.SessionCwdHolder.reset();
    }

    // ───────────────────────── 夹具 ─────────────────────────

    private static void seedCwd(String sessionId, Path dir) {
        com.nexusai.application.agent.agent.SessionCwdHolder
            .setOriginalCwd(sessionId, dir.toAbsolutePath().toString());
    }

    private static void writeTranscript(Path dir, String sessionId, String hex) {
        writeTranscriptRows(dir, sessionId, hex, List.of(row("assistant", "finished earlier")));
    }

    private static Map<String, Object> row(String role, String content) {
        Map<String, Object> row = new HashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private static void writeTranscriptRows(Path dir, String sessionId, String hex,
                                            List<Map<String, Object>> rows) {
        AgentTranscript.recordSidechainTranscript(dir, sessionId, hex,
            new ArrayList<>(rows), null);
    }

    private static void writeMetaWithName(Path dir, String sessionId, String hex, String name)
            throws Exception {
        AgentTranscript.writeMetadata(dir, sessionId, hex,
            new AgentTranscript.AgentMetadata("general-purpose", null, "work", null));
        AgentTranscript.writeMetaName(dir, sessionId, hex, name);
    }

    /** 新实例 = 模拟进程重启（内存注册表/退役表全空）。 */
    private static AgentNameRegistry freshRegistry(Path dir) {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> dir);
        return registry;
    }

    private static BackgroundTaskRunner emptyRunner() {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(any(String.class))).thenReturn(Optional.empty());
        return runner;
    }

    private static ToolUseContext ctx(String sessionId) {
        ToolUseContext ctx = mock(ToolUseContext.class);
        when(ctx.sessionId()).thenReturn(sessionId);
        return ctx;
    }

    private static ToolResult<?> send(SendMessageTool tool, String to, String message,
                                      ToolUseContext ctx) throws Exception {
        return (ToolResult<?>) tool.execute(new ToolUseBlock("c1", "SendMessage",
            JSON.readTree("{\"to\":\"" + to + "\",\"message\":\"" + message + "\"}")), ctx);
    }

    private static String data(ToolResult<?> r) {
        return String.valueOf(r.data());
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ───────────────────────── a. 重启后按名 resume ─────────────────────────

    @Test
    @DisplayName("a) 重启（新实例）后按名发送 → 扫描 meta+transcript 重建退役表 → 走 resume")
    void restartRebuildsAndResumesByName(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");
        seedCwd(SESS, dir);

        AgentNameRegistry after = freshRegistry(dir);
        assertThat(after.retiredSize()).as("新实例内存为空").isZero();

        SubagentTool subagentTool = mock(SubagentTool.class);
        ResumeService resume = new ResumeService();
        resume.setSubagentTool(subagentTool);
        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(after);
        tool.setResumeServiceProvider(provider(resume));
        tool.setBackgroundTaskRunner(emptyRunner());
        tool.setSessionDirResolver(s -> dir);

        ToolResult<?> r = send(tool, "researcher", "keep going", ctx(SESS));

        verify(subagentTool).executeResumeAsync(eq(HEX),
            eq("keep going"), any(), any(), any(), any(), any(), any(), eq(SESS));
        assertThat(data(r)).contains("Resuming agent researcher");
        assertThat(after.retiredSize()).as("重建只写退役表").isEqualTo(1);
        assertThat(after.size()).as("⛔ 不注册为活跃").isZero();
        RetiredAgentRegistry.RetiredAgent rebuilt = after.retiredEntryById(HEX);
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.name()).isEqualTo("researcher");
        assertThat(rebuilt.ownerSessionId()).isEqualTo(SESS);
        assertThat(rebuilt.terminalStatus()).isEqualTo("completed");
        assertThat(rebuilt.transcriptPath()).isEqualTo(
            AgentTranscript.getTranscriptPath(dir, SESS, HEX).toString());
        assertThat(rebuilt.stopReason()).isNull();
        assertThat(rebuilt.retiredAt()).isNotBlank();
    }

    // ───────────────────────── b. 坏 meta 跳过 ─────────────────────────

    @Test
    @DisplayName("b) meta 损坏/缺 name 跳过并计数，不影响其它条目")
    void corruptMetaIsSkipped(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "good");

        // 坏 JSON（transcript 存在，证明跳过原因是 meta 读不了而不是缺 transcript）
        writeTranscript(dir, SESS, HEX2);
        Path bad = AgentTranscript.getMetadataPath(dir, SESS, HEX2);
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "{not-json");

        // 旧 subagent meta：只有 agentType，没有 name ⇒ 无法按名寻址
        String hex3 = "a222222222222222";
        writeTranscript(dir, SESS, hex3);
        AgentTranscript.writeMetadata(dir, SESS, hex3,
            new AgentTranscript.AgentMetadata("general-purpose", null, "work", null));

        AgentNameRegistry registry = freshRegistry(dir);
        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(SESS);

        assertThat(result.metaFiles()).isEqualTo(3);
        assertThat(result.rebuilt()).isEqualTo(1);
        assertThat(result.skippedCorrupt()).isEqualTo(1);
        assertThat(result.skippedNoName()).isEqualTo(1);
        assertThat(result.skippedNoTranscript()).isZero();
        assertThat(registry.retiredSize()).isEqualTo(1);
        assertThat(registry.resolveEntry(SESS, "good")).isNotNull();
        assertThat(registry.resolveEntry(SESS, HEX2)).isNull();
    }

    // ───────────────────────── c. 已删除会话不重建 ─────────────────────────

    @Test
    @DisplayName("c) 会话已删除（<sessionId>/ 目录不存在）→ 不重建、不复活")
    void deletedSessionIsNotRebuilt(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");
        deleteRecursively(dir.resolve(SESS));

        AgentNameRegistry registry = freshRegistry(dir);
        RetiredAgentRebuilder.ScanResult result = registry.rebuildRetiredFromTranscripts(SESS);

        assertThat(result).isEqualTo(RetiredAgentRebuilder.ScanResult.EMPTY);
        assertThat(registry.retiredSize()).isZero();
        assertThat(registry.resolveEntry(SESS, "researcher")).isNull();
    }

    // ───────────────────────── d. 幂等 + 最新 meta 为准 ─────────────────────────

    @Test
    @DisplayName("d) 重复扫描幂等；同一 agentId 以最新 meta 为准（改名后旧名失效）")
    void rescanIsIdempotentAndLatestMetaWins(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");

        AgentNameRegistry registry = freshRegistry(dir);
        assertThat(registry.rebuildRetiredFromTranscripts(SESS).rebuilt()).isEqualTo(1);
        assertThat(registry.rebuildRetiredFromTranscripts(SESS).rebuilt()).isEqualTo(1);
        assertThat(registry.retiredSize()).as("不重复插入").isEqualTo(1);

        // 同一 agentId 的 meta 改名 → 最新为准，旧 name 键被清掉
        writeMetaWithName(dir, SESS, HEX, "renamed");
        assertThat(registry.rebuildRetiredFromTranscripts(SESS).rebuilt()).isEqualTo(1);
        assertThat(registry.retiredSize()).isEqualTo(1);
        assertThat(registry.retiredEntryById(HEX).name()).isEqualTo("renamed");
        assertThat(registry.resolveEntry(SESS, "renamed")).isNotNull();
        assertThat(registry.resolveEntry(SESS, "researcher")).isNull();
    }

    // ───────────────────────── f. 终态字段 finished ─────────────────────────

    @Test
    @DisplayName("f) 终态写 finished:true → 重建状态 completed")
    void finishedMetaRebuildsCompleted(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");
        assertThat(AgentTranscript.markFinished(dir, SESS, HEX)).isTrue();
        assertThat(AgentTranscript.readFinishedFlag(dir, SESS, HEX)).contains(true);

        AgentNameRegistry registry = freshRegistry(dir);
        registry.rebuildRetiredFromTranscripts(SESS);

        assertThat(registry.retiredEntryById(HEX).terminalStatus()).isEqualTo("completed");
        assertThat(registry.retiredEntryById(HEX).name())
            .as("合并写入 finished 不得冲掉 name").isEqualTo("researcher");
    }

    @Test
    @DisplayName("g) 末条是 tool 调用但 meta 有 finished → 判 completed（不再误判 incomplete）")
    void finishedMetaOverridesIncompleteInference(@TempDir Path dir) throws Exception {
        writeTranscriptRows(dir, SESS, HEX,
            List.of(row("assistant", "let me check"), row("tool", "tool output")));
        writeMetaWithName(dir, SESS, HEX, "researcher");
        AgentTranscript.markFinished(dir, SESS, HEX);

        AgentNameRegistry registry = freshRegistry(dir);
        registry.rebuildRetiredFromTranscripts(SESS);

        assertThat(AgentTranscript.inferStatus(dir, SESS, HEX))
            .as("对照组：没有 finished 时末条 tool ⇒ incomplete").isEqualTo("incomplete");
        assertThat(registry.retiredEntryById(HEX).terminalStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("h) 旧数据无 finished → 仍按 transcript 末行推断兜底")
    void missingFinishedFallsBackToInference(@TempDir Path dir) throws Exception {
        writeTranscriptRows(dir, SESS, HEX,
            List.of(row("assistant", "let me check"), row("tool", "tool output")));
        writeMetaWithName(dir, SESS, HEX, "researcher");

        assertThat(AgentTranscript.readFinishedFlag(dir, SESS, HEX)).isEmpty();
        AgentNameRegistry registry = freshRegistry(dir);
        registry.rebuildRetiredFromTranscripts(SESS);

        assertThat(registry.retiredEntryById(HEX).terminalStatus()).isEqualTo("incomplete");
    }

    @Test
    @DisplayName("i) 每次重算（不靠目录 mtime）：同 mtime 下 meta 改名仍立刻生效，两次结果一致")
    void rebuildIgnoresDirMtimeAndIsIdempotent(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");

        Path subDir = dir.resolve(SESS).resolve("subagents");
        java.nio.file.attribute.FileTime fixed =
            java.nio.file.attribute.FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(subDir, fixed);

        AgentNameRegistry registry = freshRegistry(dir);
        assertThat(registry.resolveEntry(SESS, "researcher")).isNotNull();

        // 文件内容改名（目录 mtime 不变）→ 必须重算才能看到新名
        writeMetaWithName(dir, SESS, HEX, "renamed");
        Files.setLastModifiedTime(subDir, fixed);

        assertThat(registry.resolveEntry(SESS, "renamed"))
            .as("不按 mtime 跳过 ⇒ 同 mtime 下改名也生效").isNotNull();
        assertThat(registry.resolveEntry(SESS, "researcher")).isNull();

        RetiredAgentRebuilder.ScanResult r1 = registry.rebuildRetiredFromTranscripts(SESS);
        RetiredAgentRebuilder.ScanResult r2 = registry.rebuildRetiredFromTranscripts(SESS);
        assertThat(r1).as("重复重建结果一致（幂等）").isEqualTo(r2);
        assertThat(registry.retiredSize()).isEqualTo(1);
        assertThat(registry.retiredEntryById(HEX).name()).isEqualTo("renamed");
    }

    @Test
    @DisplayName("j) 复活：写 revivedAt 并清 finished（保留 name），重建回落推断")
    void reviveClearsFinishedAndWritesRevivedAt(@TempDir Path dir) throws Exception {
        writeTranscriptRows(dir, SESS, HEX,
            List.of(row("assistant", "let me check"), row("tool", "tool output")));
        writeMetaWithName(dir, SESS, HEX, "researcher");
        AgentTranscript.markFinished(dir, SESS, HEX);

        assertThat(AgentTranscript.markRevived(dir, SESS, HEX)).isTrue();
        assertThat(AgentTranscript.readFinishedFlag(dir, SESS, HEX)).isEmpty();

        com.fasterxml.jackson.databind.JsonNode meta = JSON.readTree(
            Files.readString(AgentTranscript.getMetadataPath(dir, SESS, HEX)));
        assertThat(meta.hasNonNull("revivedAt")).isTrue();
        assertThat(meta.path("name").asText()).as("合并写入不得冲掉 name").isEqualTo("researcher");

        AgentNameRegistry registry = freshRegistry(dir);
        registry.rebuildRetiredFromTranscripts(SESS);
        assertThat(registry.retiredEntryById(HEX).terminalStatus())
            .as("finished 已清 ⇒ 回落末行推断").isEqualTo("incomplete");
    }

    // ───────────────────────── e. 用户 Stop 标记仍生效 ─────────────────────────

    @Test
    @DisplayName("e) 重建后的退役条目带 stopReason=user，SendMessage 走 k9 拒绝（不绕过）")
    void stopMarkerSurvivesRebuild(@TempDir Path dir) throws Exception {
        writeTranscript(dir, SESS, HEX);
        writeMetaWithName(dir, SESS, HEX, "researcher");
        AgentTranscript.markStoppedByUser(dir, SESS, HEX);

        AgentNameRegistry after = freshRegistry(dir);
        SendMessageTool tool = new SendMessageTool(null);
        tool.setAgentNameRegistry(after);
        // ⛔ 刻意不接 SendMessageTool 的目录解析缝：k9 只能来自「重建后的退役条目」，
        //   否则磁盘伴生文件的二次检查会掩盖重建漏读 stopReason 的缺陷（变异判别力）。
        tool.setBackgroundTaskRunner(emptyRunner());

        ToolResult<?> r = send(tool, "researcher", "again", ctx(SESS));

        assertThat(after.retiredEntryById(HEX).stopReason()).as("重建读 stopped-by-user sidecar")
            .isEqualTo("user");
        assertThat(data(r)).contains("was stopped by the user");
        assertThat(data(r)).contains("\"degradedClass\":\"not_reachable\"");
    }
}

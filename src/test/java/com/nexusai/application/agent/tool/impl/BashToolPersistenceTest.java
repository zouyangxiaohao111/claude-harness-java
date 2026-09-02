package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolResultStorage.PersistedToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bash 输出持久化（E4 / OPD-32）· 对齐 CC {@code BashTool.call} 持久化块
 * （Open-ClaudeCode/src/tools/BashTool/BashTool.tsx:731-752）。
 *
 * <p>验证契约：
 * <ul>
 *   <li>完整输出超 {@code SPILL_THRESHOLD}(30k) → 落盘 tool-results + 输出携带
 *       persistedOutputPath/persistedOutputSize（CC schema :292-293）+ 模型见 &lt;persisted-output&gt; 预览（:591-600）</li>
 *   <li>&gt;MAX_PERSISTED_SIZE(64MB) 先 truncate 源再 link/copy（CC :741-748）；持久化失败降级不抛错（:736/:750）</li>
 *   <li>小输出（&lt;30k）不落盘，行为与现状一致；Bash 无双持久化（预览 &lt; 阈值 → 组装层跳过）</li>
 * </ul>
 */
@DisplayName("BashTool 输出持久化（E4 / OPD-32）")
class BashToolPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final BashTool bashTool = new BashTool();

    @TempDir
    Path tempDir;

    // ── helpers ──

    private ToolUseContext ctx(Path workspaceDir, String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, null, Map.of(),
            false, "", workspaceDir);
    }

    private ToolUseBlock call(String callId, String command) {
        JsonNode input = JSON.createObjectNode().put("command", command);
        return new ToolUseBlock(callId, "Bash", input);
    }

    /**
     * 批次4 #11：persist workspaceDir 锚 = originalCwd 层（对齐 CC toolResultStorage.ts:97-104
     * getProjectDir(getOriginalCwd())，非 effectiveCwd）。测试用 SessionCwdHolder.setOriginalCwd 把
     * 会话 originalCwd 锚到 @TempDir，使落盘位置可断言。WHY：cd 后落盘不再漂移（CC 稳定 originalCwd 锚）。
     */
    private void anchorOriginalCwd(String sessionId, Path originalCwd) {
        SessionCwdHolder.setOriginalCwd(sessionId.toString(), originalCwd.toString());
    }

    private void clearOriginalCwd(String sessionId) {
        SessionCwdHolder.clearOriginalCwd(sessionId.toString());
    }

    /** 生成 &gt;30k 的 stdout（统一 bash 语法：Windows 走 Git Bash，seq 可用，对齐 CC）。 */
    private static String largeOutputCommand() {
        return "for i in $(seq 1 1500); do echo BBBBBBBBBBBBBBBBBBBBBBBBBBB$i; done";
    }

    // ── t1 小输出无落盘（行为与现状一致）──

    @Test
    @DisplayName("t1 小输出（<30k）不落盘：data=截断 stdout，无 tool-results 目录")
    void t1_smallOutput_noPersist() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolResult<String> r = bashTool.execute(call("e4-t1", "echo hello-e4-small"), ctx(tempDir, sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("小输出成功结果 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        assertThat(r.data()).contains("hello-e4-small");
        // 小输出走原截断 stdout 路径：无 <persisted-output> 包装，无落盘目录
        assertThat(r.data()).doesNotContain("<persisted-output>");
        assertThat(ToolResultStorage.getToolResultsDir(tempDir, sessionId.toString()))
            .doesNotExist();
    }

    // ── t2 >30k 落盘 + <persisted-output> 预览 ──

    @Test
    @DisplayName("t2 完整输出>30k → 落盘 tool-results + data=预览消息 + structuredOutput 两字段")
    void t2_largeOutput_persistsWithPreview() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String callId = "e4-t2";
        // 批次4 #11：persist 锚 = originalCwd 层（对齐 CC getOriginalCwd），非 effectiveCwd（cd 漂移 bug）。
        anchorOriginalCwd(sessionId, tempDir);
        try {
        ToolResult<Map<String, Object>> r = bashTool.execute(call(callId, largeOutputCommand()), ctx(tempDir, sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("落盘成功预览 data 非错误消息（IMP-C2 后 isError 由执行路径推导）")
            .isFalse();
        // 模型侧 data = buildLargeToolResultMessage 预览（CC :591-600 等价）——successWithStructuredOutput
        //   折入 data(Map)，预览消息在 "summary" 键（IMP-C2 后 structuredOutput 通道改道）
        String preview = (String) r.data().get("summary");
        assertThat(preview).contains("<persisted-output>");
        assertThat(preview).contains("Output too large");
        assertThat(preview).contains("tool-results");
        assertThat(preview).contains("Preview (first");
        // 结构化输出携带 CC 两字段（schema :292-293）——successWithStructuredOutput 折入 data(Map)，经 presentationMeta 读取
        Map<String, Object> persistedMeta = ToolResult.presentationMeta(r);
        assertThat(persistedMeta).containsKey("persistedOutputPath");
        assertThat(persistedMeta).containsKey("persistedOutputSize");

        // 落盘文件存在且为完整输出（>30k），路径与 structuredOutput 一致
        Path dest = ToolResultStorage.getToolResultPath(tempDir, sessionId.toString(), callId, false);
        assertThat(dest).exists();
        assertThat(Files.size(dest)).isGreaterThan(30_000L);
        assertThat(persistedMeta.get("persistedOutputPath")).isEqualTo(dest.toString());
        assertThat(((Number) persistedMeta.get("persistedOutputSize")).intValue())
            .isEqualTo((int) Files.size(dest));
        } finally {
            clearOriginalCwd(sessionId);
        }
    }

    // ── t3 truncate 顺序（小 max 注入，persistOutputFile 单测）──

    @Test
    @DisplayName("t3 >maxPersistedSize 先 truncate 源再 link：dest≤max，persistedOutputSize=截断前原大小")
    void t3_persistOutputFile_truncatesSourceBeforeLink() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path source = Files.createTempFile(tempDir, "src-", ".txt");
        Files.writeString(source, "A".repeat(100_000));
        long max = 10_000; // 注入小上限验证 truncate 顺序

        PersistedToolResult persisted = ToolResultStorage.persistOutputFile(
            tempDir, sessionId.toString(), "e4-t3", source, max);

        assertThat(persisted).isNotNull();
        // 截断前原大小（CC :738 stat 在前，:742 truncate 在后）
        assertThat(persisted.originalSize()).isEqualTo(100_000);
        Path dest = ToolResultStorage.getToolResultPath(tempDir, sessionId.toString(), "e4-t3", false);
        assertThat(dest).exists();
        // truncate 先于 link → dest 内容即截断版，长度 ≤ max
        assertThat(Files.size(dest)).isLessThanOrEqualTo(max);
        assertThat(Files.size(source)).isLessThanOrEqualTo(max);
    }

    // ── t4 link→copy 回退（预建 dest 触发 FileAlreadyExists）──

    @Test
    @DisplayName("t4 link 失败（dest 已存在）→ copyFile 覆盖回退成功")
    void t4_persistOutputFile_linkFails_fallsBackToCopy() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path dest = ToolResultStorage.getToolResultPath(tempDir, sessionId.toString(), "e4-t4", false);
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, "OLD"); // 预建 dest → Files.createLink 抛 FileAlreadyExists

        Path source = Files.createTempFile(tempDir, "src-", ".txt");
        Files.writeString(source, "NEW-CONTENT-E4");

        PersistedToolResult persisted = ToolResultStorage.persistOutputFile(
            tempDir, sessionId.toString(), "e4-t4", source, ToolResultStorage.MAX_PERSISTED_SIZE);

        assertThat(persisted).isNotNull();
        assertThat(persisted.filepath()).isEqualTo(dest.toString());
        // 回退 copy REPLACE_EXISTING → 覆盖为源内容
        assertThat(Files.readString(dest)).isEqualTo("NEW-CONTENT-E4");
    }

    // ── t5 持久化整体 catch 降级不抛错（CC :736/:750）──

    @Test
    @DisplayName("t5 persistOutputFile 失败（源缺失）→ 返回 null 不抛错（调用方降级保留 stdout preview）")
    void t5_persistOutputFile_failure_returnsNull_noThrow() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path missing = tempDir.resolve("missing.txt");
        PersistedToolResult persisted = ToolResultStorage.persistOutputFile(
            tempDir, sessionId.toString(), "e4-t5", missing, ToolResultStorage.MAX_PERSISTED_SIZE);
        assertThat(persisted).isNull();
        // 无残留 dest
        assertThat(ToolResultStorage.getToolResultPath(tempDir, sessionId.toString(), "e4-t5", false))
            .doesNotExist();
    }

    // ── t6 无双持久化：预览过组装层阈值跳过（applyToolResultBudget 不再二次落盘）──

    @Test
    @DisplayName("t6 BashTool 落盘后 data=预览（<阈值）→ 组装层不二次落盘，tool-results 仅 1 个文件")
    void t6_persistedPreview_doesNotDoublePersist() throws Exception {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 批次4 #11：persist 锚 = originalCwd 层（对齐 CC getOriginalCwd）
        anchorOriginalCwd(sessionId, tempDir);
        try {
        ToolResult<Map<String, Object>> r = bashTool.execute(call("e4-t6", largeOutputCommand()), ctx(tempDir, sessionId));

        assertThat(LlmAgentLoop.isToolErrorData(r.data()))
            .as("落盘预览 data 非错误消息")
            .isFalse();
        // 预览消息长度 < 组装层持久化阈值（getPersistenceThreshold("Bash",30000)=30000）
        // WHY: applyToolResultBudget（AgentLoopContext:1874）对 String data 长度 ≤ 阈值直接返回原结果，
        //      不再 persistToolResult → 不会产生第二个 tool-results 文件（双持久化规避）。
        //      [IMP-C2] 落盘结果 data 为 Map（successWithStructuredOutput 折入），预览在 "summary" 键。
        int threshold = ToolResultStorage.getPersistenceThreshold("Bash", 30_000);
        String preview = (String) r.data().get("summary");
        assertThat(preview.length()).isLessThanOrEqualTo(threshold);
        // BashTool.call 自身落盘仅 1 个文件
        try (Stream<Path> files = Files.list(
                ToolResultStorage.getToolResultsDir(tempDir, sessionId.toString()))) {
            assertThat(files.count()).isEqualTo(1L);
        }
        } finally {
            clearOriginalCwd(sessionId);
        }
    }
}

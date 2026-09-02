package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD-WF5-02-02 · ReadPermissionChecker 内部可读路径白名单接入核心测试。
 *
 * <p><b>RED→GREEN 语义</b>：改动前 Java 仅有 agent-memory/auto-mem/bundled-skills 3 读 carve-out，
 * session-memory / plan / tool-results 等路径落到兜底 ask（探查 EV-FS-039h）；改动后经
 * {@link PathValidation#checkReadableInternalPath} 静默 allow（对齐 CC filesystem.ts:1611-1777）。
 *
 * <p>session-memory 路径为 {@code {claudeConfigHomeDir}/session-memory/}（Java 真实介质，
 * MemoryFileDetection 自证）；测试经 {@link ClaudePaths#setConfigDirOverride} 隔离到 target/ 下，
 * 避免命中真实用户目录。
 */
@DisplayName("OPD-WF5-02-02 · ReadPermissionChecker 内部可读路径白名单接入核心")
class ReadPermissionCheckerInternalPathTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG_DIR = "target/wf6-internal-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ClaudePaths.setConfigDirOverride(Paths.get(CONFIG_DIR).toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
    }

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext emptyRulesCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.of());
    }

    private static Path cwdDir() {
        return Paths.get("target", "wf6-internal-cwd-" + UUID.randomUUID()).toAbsolutePath();
    }

    private static ReadPermissionChecker checker() {
        return new ReadPermissionChecker(new WritePermissionChecker());
    }

    @Test
    @DisplayName("session-memory 文件读 → 静默 Allow（CC filesystem.ts:1620-1629）")
    void sessionMemory_read_allow() {
        Path sessionMemoryFile = Paths.get(CONFIG_DIR, "session-memory", "summary.md").toAbsolutePath();

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(sessionMemoryFile.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(result)
            .as("CC checkReadableInternalPath session-memory 分支（filesystem.ts:1620-1629）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isEqualTo(new PermissionDecisionReason.Other("Session memory files are allowed for reading"));
    }

    @Test
    @DisplayName("plan 文件读 → 静默 Allow（CC filesystem.ts:1645-1654）")
    void planFile_read_allow() {
        // env.plansPrefix() = {configHome}/plans/{sessionId}；读分支 auto-allow。
        Path configHome = Paths.get(CONFIG_DIR).toAbsolutePath();
        // 用任意 sessionId 构造 plan 路径；checker 的 env 用 ctx.sessionId()，故此处须用同一 sessionId。
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), emptyRulesCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", cwdDir());
        Path planFile = configHome.resolve("plans").resolve(tuc.sessionId().toString() + ".md");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(planFile.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath plan 分支（filesystem.ts:1645-1654）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isEqualTo(new PermissionDecisionReason.Other("Plan files for current session are allowed for reading"));
    }

    @Test
    @DisplayName("tool-results 文件读 → 静默 Allow（CC filesystem.ts:1656-1674）")
    void toolResults_read_allow() {
        Path cwd = cwdDir();
        ToolUseContext tuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), emptyRulesCtx(), PermissionMode.DEFAULT,
            Map.of(), false, "", cwd);
        Path toolResults = cwd.resolve(tuc.sessionId().toString()).resolve("tool-results").resolve("o.txt");

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwd)),
            input(toolResults.toString()), tuc);

        assertThat(result)
            .as("CC checkReadableInternalPath tool-results 分支（filesystem.ts:1656-1674）应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("普通目录外文件读 → 仍兜底 Ask（内部白名单不误放行）")
    void ordinaryOutsideFile_stillAsk() {
        Path outside = Paths.get("target", "wf6-outside-" + UUID.randomUUID(), "a.txt").toAbsolutePath();

        PermissionResult result = checker().check(new ReadFileTool(new PathGuard(cwdDir())),
            input(outside.toString()), ctx(emptyRulesCtx(), cwdDir()));

        assertThat(result)
            .as("非内部路径 + 工作目录外 → 兜底 ask（fail-closed 不因新白名单误放行）")
            .isInstanceOf(PermissionResult.Ask.class);
    }
}

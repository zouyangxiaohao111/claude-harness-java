package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.config.GitInstructionConfig;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.permission.PermissionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BashTool 工具系统提示词聚焦测试（IMP-B1 · P0-1 getSimplePrompt 369 行 override）。
 *
 * <p>WHY（测试验证意图而非行为）：P0-1 风险 = LLM 无 Bash 使用规范 / git 安全协议 / sandbox 语义
 * 引导（EV-B1-006）。测试断言 prompt() 返回 CC getSimplePrompt 全文的关键段与插值，验证
 * {@code BashTool.prompt()} → {@code ToolRegistry.toOpenAiToolsArray}（prompt() 非 null 优先）
 * 链路，使 LLM 在工具描述中收到 CC 对齐引导。
 */
class BashToolTest {

    private final BashTool bashTool = new BashTool();

    @BeforeEach
    @AfterEach
    void resetGitInstructionConfig() {
        // 防跨测试静态污染（GitInstructionConfig 静态桥接）
        GitInstructionConfig.reset();
        // [WF-2A] 防止 SessionCwdHolder 跨测试污染（cd 持久化测试会写入会话 cwd）
        SessionCwdHolder.reset();
    }

    @Test
    @DisplayName("prompt() 返回 CC getSimplePrompt 全文关键段（P0-1）")
    void prompt_returnsCcAlignedSimplePrompt() {
        String p = bashTool.prompt();

        // 首段（CC prompt.ts:355）
        assertThat(p).startsWith("Executes a given bash command and returns its output.");
        assertThat(p).contains("The working directory persists between commands, but shell state does not.");

        // avoidCommands 引导（prompt.ts:359，非 embedded 变体含 find/grep）
        assertThat(p).contains("IMPORTANT: Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, "
            + "`sed`, `awk`, or `echo` commands, unless explicitly instructed");

        // 工具偏好子弹（prompt.ts:284-290）
        assertThat(p).contains(" - File search: Use Glob (NOT find or ls)");
        assertThat(p).contains(" - Content search: Use Grep (NOT grep or rg)");
        assertThat(p).contains(" - Read files: Use Read (NOT cat/head/tail)");
        assertThat(p).contains(" - Edit files: Use Edit (NOT sed/awk)");
        assertThat(p).contains(" - Write files: Use Write (NOT echo >/cat <<EOF)");
        assertThat(p).contains(" - Communication: Output text directly (NOT echo/printf)");
        assertThat(p).contains("While the Bash tool can do similar things, it’s better to use the built-in tools");

        // 指令段（prompt.ts:364）+ 超时行（prompt.ts:335，Java 默认 120s / CC max 600s）
        assertThat(p).contains("# Instructions");
        assertThat(p).contains("You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). "
            + "By default, your command will timeout after 120000ms (2 minutes).");

        // backgroundNote（prompt.ts:39，backgroundTasksDisabled=false 默认）
        assertThat(p).contains("You can use the `run_in_background` parameter to run the command in the background.");

        // 多命令/git/sleep 子项
        assertThat(p).contains("DO NOT use newlines to separate commands (newlines are ok in quoted strings).");
        assertThat(p).contains("Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign, -c commit.gpgsign=false)");
        assertThat(p).contains("If you must poll an external process, use a check command (e.g. `gh run view`) rather than sleeping first.");

        // git 段（prompt.ts:81-160，gitInstructions 默认开启）
        assertThat(p).contains("# Committing changes with git");
        assertThat(p).contains("Git Safety Protocol:");
        assertThat(p).contains("# Creating pull requests");
        assertThat(p).contains("# Other common operations");

        // 工具名插值（Bash/TodoWrite/Agent）
        assertThat(p).contains("each using the Bash tool:");
        assertThat(p).contains("NEVER use the TodoWrite or Agent tools");
        assertThat(p).contains("- View comments on a Github PR: gh api repos/foo/bar/pulls/123/comments");
    }

    @Test
    @DisplayName("默认配置（sandbox 未启用）不渲染 sandbox 段（isSandboxingEnabled=false）")
    void prompt_sandboxDisabled_noSandboxSection() {
        assertThat(bashTool.prompt()).doesNotContain("## Command sandbox");
        assertThat(bashTool.prompt()).doesNotContain("dangerouslyDisableSandbox");
    }

    @Test
    @DisplayName("git 指令禁用时不含 git 段（gitSettings.ts:13-18 shouldIncludeGitInstructions=false）")
    void prompt_gitInstructionsDisabled_noGitSection() {
        GitInstructionConfig.setConfiguredForTest(false);
        String p = bashTool.prompt();
        assertThat(p).doesNotContain("# Committing changes with git");
        assertThat(p).doesNotContain("Git Safety Protocol");
        // 指令段不受 git 门控影响（instructions 仍在）
        assertThat(p).contains("# Instructions");
    }

    @Test
    @DisplayName("默认归因注入 git 段 + given direct instructions 行尾空格保真（attribution.ts / prompt.ts:89）")
    void prompt_defaultAttributionAndTrailingSpacePreserved() {
        String p = bashTool.prompt();
        assertThat(p).contains("Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>");
        assertThat(p).contains("🤖 Generated with [Claude Code](https://claude.com/claude-code)");
        // CC prompt.ts:89 行尾空格（text block 会剥，{{TRAIL}} 运行时补）保真
        assertThat(p).contains("given direct instructions \n");
    }

    @Test
    @DisplayName("注入 supplier 覆盖默认归因（AttributionSupplier 语义）")
    void prompt_customAttributionSupplierUsed() {
        bashTool.setBashAttributionSupplier(() ->
            new com.nexusai.application.agent.bash.BashToolPrompt.Attribution(
                "Co-Authored-By: Test Model <noreply@test.com>", null));
        String p = bashTool.prompt();
        assertThat(p).contains("Co-Authored-By: Test Model <noreply@test.com>");
        assertThat(p).doesNotContain("Co-Authored-By: Claude Opus 4.6");
    }

    @Test
    @DisplayName("monitorTool=true 时 sleep 子项用 Monitor 工具引导（feature('MONITOR_TOOL')）")
    void prompt_monitorToolEnabled_includesMonitorSubitems() {
        // [IMP-CM-08/09] FeatureFlags 21 字段（memoryShapeTelemetry 已由 memory_v2 DCE 移除——
        //   CC memoryShapeTelemetry.js 缺失 OD-01，findRelevantMemories.ts:66 门 goto 悬空）；
        //   monitorTool 仍是第 16 位（feature('MONITOR_TOOL')），其余全关。
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, true, false, false, false, false, false);
        bashTool.setFeatureFlags(flags);
        String p = bashTool.prompt();
        assertThat(p).contains("Use the Monitor tool to stream events from a background process");
        assertThat(p).contains("`sleep N` as the first command with N ≥ 2 is blocked.");
        // 非 Monitor 变体子项应消失（feature('MONITOR_TOOL') 分叉）
        assertThat(p).doesNotContain("If you must poll an external process, use a check command (e.g. `gh run view`)");
    }

    @Test
    @DisplayName("ToolRegistry 工具描述走 prompt()（api.ts:171，prompt() 非 null 优先）")
    void toolRegistryDescriptionUsesPrompt() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(bashTool);
        JsonNode arr = registry.toOpenAiToolsArray();
        JsonNode bashEntry = null;
        for (JsonNode wrapper : arr) {
            if (bashTool.name().equals(wrapper.path("function").path("name").asText())) {
                bashEntry = wrapper;
                break;
            }
        }
        assertThat(bashEntry).as("Bash 工具应出现在注册表 LLM schema 中").isNotNull();
        assertThat(bashEntry.path("function").path("description").asText())
            .isEqualTo(bashTool.prompt())
            .startsWith("Executes a given bash command and returns its output.");
    }

    // ─────────────────────────────────────────────────────────────
    // [WF-2A] cd 持久化 · 对齐 CC Shell.ts:380-470 cd tracking 全机制
    // WHY（意图验证 · CLAUDE.md 规则九）：CC bash 命令跑完读 pwd → setCwdState，
    // 下一条命令/文件工具/权限用新 cwd（INV-2）。Java 等价：前台命令尾部追加 pwd/cd
    // 重定向到临时文件 → 跑完读回 → SessionCwdHolder.set（realpath+NFC）。这些测试
    // 验证「cd 后 cwd 真的持久化并被下一条命令消费」这一 WHY，而非仅断言 set 被调用。
    // ─────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON_CWD = new ObjectMapper();

    private static ToolUseBlock bashCall(String id, String command) {
        return new ToolUseBlock(id, "Bash", JSON_CWD.createObjectNode().put("command", command));
    }

    private static ToolUseContext ctxForSession(String sessionId) {
        // 4 参构造器：effectiveCwd=null → 经 WF-1A 走 CwdResolution.getCwd(sessionId) 兜底。
        return new ToolUseContext(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            java.util.Map.of());
    }

    private String cdCommandFor(Path target) {
        // 统一 bash 语义（对齐 CC：Windows 走 Git Bash，非 cmd.exe）：
        // 反斜杠 → 正斜杠（Git Bash 接受 C:/... 自动转 POSIX；POSIX 路径原样）。两端引号包裹空格路径。
        String quoted = "\"" + target.toString().replace('\\', '/') + "\"";
        return "cd " + quoted;
    }

    @Test
    @DisplayName("AC-1 前台 cd 后 cwd 持久化到 SessionCwdHolder（CC Shell.ts setCwd）")
    void cd_foreground_persistsToSessionCwdHolder() throws Exception {
        // WHY: CC Shell.ts:407 setCwd(newCwd,cwd) → setCwdState，STATE.cwd 随 cd 变。
        //      Java 旧实现 pb.directory(user.dir) 每次固定 → cd 不持久化（违反 INV-2）。
        //      改造后跑完读 pwd 写回 SessionCwdHolder，下一条命令取新 cwd。
        Path sub = Files.createTempDirectory("wf2a-cd-sub");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ctxForSession(sessionId);
        try {
            ToolResult<String> r = bashTool.execute(bashCall("wf2a-cd-1", cdCommandFor(sub)), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("cd 命令应成功").isFalse();

            String persisted = SessionCwdHolder.get(sessionId.toString());
            assertThat(persisted).as("cd 后 SessionCwdHolder 必须写入新 cwd").isNotNull();
            // 持久化值经 realpath+NFC 归一化（对齐 CC setCwdState + setCwd realpathSync）。
            assertThat(persisted).isEqualTo(CwdResolution.normalizeCwd(sub.toString()));
        } finally {
            deleteRecursively(sub);
        }
    }

    @Test
    @DisplayName("AC-1b cd 后下一条命令工作目录=新 cwd（INV-2：cd 持久化被消费）")
    void cd_thenNextCommandUsesNewCwd() throws Exception {
        // WHY: 持久化的目的不是「写入 holder」本身，而是「下一条命令真的在新 cwd 执行」。
        //      cd sub 后执行 `echo marker > marker.txt`（相对路径），marker 必须落在 sub 内。
        //      用相对路径而非绝对路径，否则即便 pb.directory 未取新 cwd 也会写到 sub（测试失效）。
        Path sub = Files.createTempDirectory("wf2a-cd-sub2");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ctxForSession(sessionId);
        Path marker = sub.resolve("marker.txt");
        try {
            bashTool.execute(bashCall("wf2a-cd-2a", cdCommandFor(sub)), ctx);
            // 第二条命令：相对路径写 marker。pb.directory 必须取 SessionCwdHolder 的 sub，
            // 否则 marker 落到旧 cwd（user.dir）而非 sub。
            bashTool.execute(bashCall("wf2a-cd-2b", "echo marker > marker.txt"), ctx);
            assertThat(marker).as("marker 必须落在 cd 后的 sub 目录（证明下条命令 pb.directory 用了新 cwd）").exists();
            // 旧 cwd（user.dir）下不应出现 marker（排除偶发）
            java.nio.file.Path userDirMarker = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("marker.txt");
            assertThat(userDirMarker).as("旧 cwd 不应残留 marker（否则 pb.directory 未真正切到新 cwd）").doesNotExist();
        } finally {
            deleteRecursively(sub);
            java.nio.file.Path userDirMarker = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("marker.txt");
            deleteRecursively(userDirMarker);
        }
    }

    @Test
    @DisplayName("AC-2 后台任务不更新 cwd（CC Shell.ts:395 !backgroundTaskId）")
    void cd_background_doesNotUpdateCwd() throws Exception {
        // WHY: CC Shell.ts:395 仅前台任务（!result.backgroundTaskId）更新 cwd；后台任务不改。
        //      后台命令在独立 runner 跑，cwd 变化不回写会话 STATE.cwd。Java 后台路径
        //      （executeBackground → BackgroundTaskRunner.spawn）不经前台 pwd 读回，故不改 holder。
        Path sub = Files.createTempDirectory("wf2a-cd-bg");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ctxForSession(sessionId);
        // stub runner：spawn 全 no-op，不跑命令、不触 SessionCwdHolder（隔离后台执行细节）。
        BackgroundTaskRunner stub = new BackgroundTaskRunner(null, null) {
            @Override
            public void spawn(BackgroundTask task, String bashCommand, String createSessionId) {
                // no-op：证明后台路径本身不写 holder（即便命令真跑也不经前台 pwd 读回）
            }
        };
        bashTool.setBackgroundTaskRunner(stub);
        try {
            com.fasterxml.jackson.databind.node.ObjectNode bgInput = JSON_CWD.createObjectNode()
                .put("command", cdCommandFor(sub))
                .put("run_in_background", true);
            ToolUseBlock bgCall = new ToolUseBlock("wf2a-bg-1", "Bash", bgInput);
            ToolResult<String> r = bashTool.execute(bgCall, ctx);
            // 后台路径应返回 "Background task started" 而非执行命令
            assertThat(r.data()).contains("Background task started");
            // 关键断言：后台任务不得更新会话 cwd
            assertThat(SessionCwdHolder.get(sessionId.toString()))
                .as("后台任务不得更新 SessionCwdHolder（对齐 CC !backgroundTaskId）").isNull();
        } finally {
            bashTool.setBackgroundTaskRunner(null);
            deleteRecursively(sub);
        }
    }

    @Test
    @DisplayName("AC-3 持久化 cwd 经 realpath+NFC 归一化（CC setCwdState + setCwd realpathSync）")
    void cd_persistedValue_normalizedRealpathNfc() throws Exception {
        // WHY: CC Shell.ts:406 newCwd.normalize('NFC') !== cwd 比对，setCwd 内 realpathSync。
        //      防止符号链接/Unicode 路径假阳性。Java SessionCwdHolder.set 经 CwdResolution.normalizeCwd
        //      做 Path.toRealPath + NFC，与本测试 normalizeCwd 期望一致。
        Path sub = Files.createTempDirectory("wf2a-cd-norm");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = ctxForSession(sessionId);
        try {
            bashTool.execute(bashCall("wf2a-norm-1", cdCommandFor(sub)), ctx);
            String persisted = SessionCwdHolder.get(sessionId.toString());
            assertThat(persisted).isEqualTo(CwdResolution.normalizeCwd(sub.toString()));
            // realpath 已解符号链接 + NFC，路径不应含冗余 . 或 ..
            assertThat(persisted).doesNotContain("\\.").doesNotContain("/./");
        } finally {
            deleteRecursively(sub);
        }
    }

    private static void deleteRecursively(Path p) {
        if (p == null) return;
        try {
            if (Files.isDirectory(p)) {
                try (var stream = Files.list(p)) {
                    stream.forEach(BashToolTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 测试清理容忍失败
        }
    }
}

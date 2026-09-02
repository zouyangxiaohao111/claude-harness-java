package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.powershell.PowerShellAstService;
import com.nexusai.application.agent.tool.powershell.PowerShellPermissionChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellTool 工具级契约测试（A5 聚焦测试类）。
 *
 * <p><b>WHY (意图验证)</b>:
 * <ul>
 *   <li>工具标识/描述稳定（供 ToolRegistry 分发与 LLM schema 消费）</li>
 *   <li>outputSchema 对齐 CC {@code PowerShellTool.tsx:245-256} 10 字段 —— 不随旧
 *       {exit_code, output} 契约漂移</li>
 *   <li>inputSchema required 含 command（CC :229 z.strictObject）</li>
 * </ul>
 */
class PowerShellToolTest {

    private static final PowerShellTool TOOL =
        new PowerShellTool(new PowerShellPermissionChain(new PowerShellAstService()));

    @Test
    @DisplayName("工具标识：name=PowerShell · description 提示 Windows")
    void toolIdentity() {
        assertEquals("PowerShell", TOOL.name(), "工具名对齐 CC POWERSHELL_TOOL_NAME");
        assertTrue(TOOL.description().contains("Windows"),
            "description 必须提示 Windows 平台（CC PowerShellTool.tsx 平台 shim 语义）");
    }

    @Test
    @DisplayName("inputSchema required 含 command（CC :229 z.strictObject）")
    void inputSchemaRequiresCommand() {
        JsonNode schema = TOOL.inputSchema();
        JsonNode required = schema.path("required");
        assertTrue(required.isArray() && required.toString().contains("command"),
            "command 必填（CC PowerShellTool.tsx:229）");
    }

    @Test
    @DisplayName("outputSchema 10 字段对齐 CC PowerShellTool.tsx:245-256")
    void outputSchemaTenFields() {
        JsonNode props = TOOL.outputSchema().path("properties");
        for (String field : new String[]{"stdout", "stderr", "interrupted",
            "returnCodeInterpretation", "isImage", "persistedOutputPath", "persistedOutputSize",
            "backgroundTaskId", "backgroundedByUser", "assistantAutoBackgrounded"}) {
            assertTrue(props.has(field), "outputSchema 必须含字段 " + field
                + "（CC PowerShellTool.tsx:245-256）");
        }
        // 旧 2 字段契约（exit_code/output）不得残留
        assertTrue(!props.has("exit_code"), "不得再声明旧 exit_code 字段");
        assertTrue(!props.has("output"), "不得再声明旧 output 字段");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // [OD-2A-2] PowerShell cd 读回持久化 · 对齐 CC powershellProvider.buildExecCommand
    //   （powershellProvider.ts:35-97 产 cwdFilePath）+ Shell.ts:385-421 cd tracking 读回
    // WHY (规则九 · 验证意图): CC PowerShell 前台命令跑完读 (Get-Location).Path 写 cwdFilePath
    //   → Shell.ts readFileSync 读回 → setCwdState（INV-2 前台 cd 持久化）。Java 等价：
    //   PowerShellTool 命令尾部追加 cwdTracking（$_ec 退出码保持 + Get-Location | Out-File）→
    //   跑完读回 → SessionCwdHolder.set。若读回/持久化断裂，PS cd 后下一条命令仍跑在旧 cwd。
    //   PS 5.1（Java resolvePwshPath 兜底 powershell）Out-File -Encoding utf8 写 UTF-8 BOM，
    //   读回必须剥离（[OD-2A-2 返工 R1]），否则 Path.of 抛 InvalidPathException。
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    void resetSessionCwdHolder() {
        SessionCwdHolder.reset();
    }

    private static final ObjectMapper PS_JSON = new ObjectMapper();

    private static ToolUseBlock psCall(String id, String command) {
        ObjectNode input = PS_JSON.createObjectNode().put("command", command);
        return new ToolUseBlock(id, PowerShellTool.NAME, input);
    }

    private static ToolUseContext psCtx(String sessionId) {
        return new ToolUseContext(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            java.util.Map.of());
    }

    private String psCdCommandFor(Path target) {
        // PS 单引号包裹路径（Windows 路径含空格可经单引号直接传递）
        String quoted = "'" + target + "'";
        return "Set-Location " + quoted;
    }

    @Test
    @DisplayName("[OD-2A-2] PS 前台 cd 后 cwd 持久化到 SessionCwdHolder（CC powershellProvider cwdTracking）")
    void cd_foreground_persistsToSessionCwdHolder() throws Exception {
        // WHY: CC powershellProvider.ts:65 cwdTracking `(Get-Location).Path | Out-File` +
        //   Shell.ts:385-421 readFileSync 读回 → setCwdState，STATE.cwd 随 PS cd 变。
        //   Java 旧实现缺 cd 读回 → PS cd 不持久化（违反 INV-2）。改造后跑完读回写回
        //   SessionCwdHolder，下一条命令取新 cwd。PS 5.1 BOM 必须剥离（否则持久化值带 前缀）。
        Path sub = Files.createTempDirectory("od2a2-ps-cd");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = psCtx(sessionId);
        try {
            ToolResult<?> r = (ToolResult<?>) TOOL.execute(psCall("od2a2-ps-1", psCdCommandFor(sub)), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(String.valueOf(r.data()))).as("PS cd 命令应成功").isFalse();

            String persisted = SessionCwdHolder.get(sessionId.toString());
            assertThat(persisted).as("PS cd 后 SessionCwdHolder 必须写入新 cwd").isNotNull();
            // 持久化值经 realpath+NFC 归一化（对齐 CC setCwdState + setCwd realpathSync）；
            // 关键：不得含 UTF-8 BOM（PS 5.1 Out-File -Encoding utf8 写 BOM，读回必须剥离）
            assertThat(persisted)
                .isEqualTo(CwdResolution.normalizeCwd(sub.toString()))
                .doesNotStartWith("﻿");
        } finally {
            deleteRecursively(sub);
        }
    }

    @Test
    @DisplayName("[OD-2A-2] PS cd 后下一条命令工作目录=新 cwd（INV-2：PS cd 持久化被消费）")
    void cd_thenNextCommandUsesNewCwd() throws Exception {
        // WHY: 持久化的目的不是「写入 holder」本身，而是「下一条命令真的在新 cwd 执行」。
        //   PS cd sub 后执行 `New-Item -ItemType File marker.txt`（相对路径），marker 必须落在
        //   sub 内（用相对路径，否则即便 pb.directory 未取新 cwd 也会写到 sub，测试失效）。
        Path sub = Files.createTempDirectory("od2a2-ps-cd2");
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ToolUseContext ctx = psCtx(sessionId);
        Path marker = sub.resolve("marker.txt");
        try {
            TOOL.execute(psCall("od2a2-ps-2a", psCdCommandFor(sub)), ctx);
            // 第二条命令复用同一 ctx：spawn cwd = 实时 sessionCwd（CwdResolution.getCwd(sessionId)
            //   每次 execute 内重新解析），cd 后 SessionCwdHolder 已持久化 sub → pb.directory
            //   必须取 sub（对齐 CC Shell.ts:218 pwd() 每命令取全局 STATE.cwd；对齐 BashTool
            //   pb.directory 走实时 sessionCwd）。若 pb.directory 误用冻结 effectiveCwd 快照，
            //   marker 会落到旧 cwd（user.dir）而非 sub —— 本测试锁定该回归。
            TOOL.execute(psCall("od2a2-ps-2b",
                "New-Item -ItemType File marker.txt -Force | Out-Null"), ctx);
            assertThat(marker).as("marker 必须落在 cd 后的 sub 目录（证明下条命令 pb.directory 用了新 cwd）").exists();
            // 旧 cwd（user.dir）下不应出现 marker（排除偶发）
            Path userDirMarker = Path.of(System.getProperty("user.dir")).resolve("marker.txt");
            assertThat(userDirMarker).as("旧 cwd 不应残留 marker").doesNotExist();
        } finally {
            deleteRecursively(sub);
            deleteRecursively(Path.of(System.getProperty("user.dir")).resolve("marker.txt"));
        }
    }

    private static void deleteRecursively(Path p) {
        if (p == null) return;
        try {
            if (Files.isDirectory(p)) {
                try (var stream = Files.list(p)) {
                    stream.forEach(PowerShellToolTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 测试清理容忍失败
        }
    }
}

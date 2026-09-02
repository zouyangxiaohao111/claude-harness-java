package com.nexusai.application.agent.mcp.config;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link McpConfigFileWriter} 路径描述器（DB 唯一源改造后角色 · describeMcpConfigFilePath
 * 对齐 CC utils.ts:254-271）验证。
 *
 * <p><b>WHY (意图验证)</b>: 用户拍板（2026-08-30）MCP 写只写 DB、读只读 DB，双写已删——
 * 本类不再有写/读方法（addServer/removeServer/writeMcpJson/readProjectServersBestEffort 等
 * 已删），唯一职责 = create/update 响应 DTO 的 {@code filePath} 展示（前端 G5 展示每个 server
 * 的配置文件路径）。本测试锁定路径描述语义：
 * <ul>
 *   <li><b>describeMcpConfigFilePath 各 scope</b>：project → {@code <cwd>/.mcp.json}；
 *       user → {@link #globalConfigFilePath()}；local → {@code globalConfigFilePath() +
 *       " [project: <cwd>]"}；dynamic → "Dynamically configured"；enterprise →
 *       {@link McpEnterpriseConfig#getEnterpriseMcpFilePath()}（缺省 managed-mcp.json）；
 *       claudeai → "claude.ai"；default → 原样返回 scope（CC utils.ts:254-271）。</li>
 *   <li><b>globalConfigFilePath 兜底</b>：FileConfigStorage.globalFilePath() 委托（配置覆盖时
 *       报告真实写入路径），null / 未装配 → user.home 默认 {@code <home>/.nexusai.json}，不 NPE。</li>
 * </ul>
 */
class McpConfigFileWriterTest {

    @TempDir
    Path tempDir;

    private FileConfigStorage storage;
    private McpConfigFileWriter writer;

    @BeforeEach
    void setUp() {
        // projectMcpJsonPath 走 CwdResolution override（避免读到真实 user.dir）
        CwdResolution.setCurrentOverride(tempDir.toString());
        storage = Mockito.mock(FileConfigStorage.class);
        writer = new McpConfigFileWriter(storage, null);
    }

    @AfterEach
    void tearDown() {
        CwdResolution.clearCurrentOverride();
    }

    // ── describeMcpConfigFilePath（utils.ts:254-271） ──

    @Test
    @DisplayName("describeMcpConfigFilePath(project, cwd) → <cwd>/.mcp.json")
    void describe_project_isCwdMcpJson() {
        String cwd = tempDir.toString();
        assertThat(writer.describeMcpConfigFilePath("project", cwd))
            .isEqualTo(Path.of(cwd, ".mcp.json").toString());
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(user, cwd) → globalConfigFilePath()（FileConfigStorage 委托）")
    void describe_user_delegatesGlobalConfigFilePath() {
        when(storage.globalFilePath()).thenReturn(Path.of("/custom/global", ".nexusai.json"));
        String cwd = tempDir.toString();
        assertThat(writer.describeMcpConfigFilePath("user", cwd))
            .isEqualTo(writer.globalConfigFilePath())
            .isEqualTo(Path.of("/custom/global", ".nexusai.json").toString());
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(local, cwd) → globalConfigFilePath() + [project: <cwd>]（CC local 显式标注）")
    void describe_local_annotatesProjectCwd() {
        when(storage.globalFilePath()).thenReturn(Path.of("/custom/global", ".nexusai.json"));
        String cwd = tempDir.toString();
        assertThat(writer.describeMcpConfigFilePath("local", cwd))
            .isEqualTo(Path.of("/custom/global", ".nexusai.json").toString()
                + " [project: " + cwd + "]");
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(dynamic, cwd) → Dynamically configured")
    void describe_dynamic_fixedString() {
        assertThat(writer.describeMcpConfigFilePath("dynamic", tempDir.toString()))
            .isEqualTo("Dynamically configured");
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(enterprise) 无 enterprise 配置 → 缺省 managed-mcp.json")
    void describe_enterprise_nullConfig_defaultManaged() {
        // enterpriseConfig 注入 null → 缺省 managed-mcp.json（config.ts:62-64 平台路径的兜底）
        assertThat(writer.describeMcpConfigFilePath("enterprise", tempDir.toString()))
            .isEqualTo("managed-mcp.json");
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(enterprise) 注入 McpEnterpriseConfig → 报告其配置路径")
    void describe_enterprise_withConfig_reportsManagedPath() {
        McpEnterpriseConfig enterprise = new McpEnterpriseConfig("C:/tmp/managed-mcp.json");
        McpConfigFileWriter w = new McpConfigFileWriter(null, enterprise);
        assertThat(w.describeMcpConfigFilePath("enterprise", tempDir.toString()))
            .isEqualTo("C:/tmp/managed-mcp.json");
    }

    @Test
    @DisplayName("describeMcpConfigFilePath(claudeai, cwd) → claude.ai")
    void describe_claudeai_fixedString() {
        assertThat(writer.describeMcpConfigFilePath("claudeai", tempDir.toString()))
            .isEqualTo("claude.ai");
    }

    @Test
    @DisplayName("describeMcpConfigFilePath 未知 scope → 原样返回（CC default 分支透传）")
    void describe_unknownScope_passthrough() {
        assertThat(writer.describeMcpConfigFilePath("managed", tempDir.toString()))
            .isEqualTo("managed");
    }

    // ── globalConfigFilePath（FileConfigStorage 委托 + user.home 兜底） ──

    @Test
    @DisplayName("globalConfigFilePath → 委托 FileConfigStorage.globalFilePath()（配置覆盖时报告真实路径）")
    void globalConfigFilePath_delegatesToStorage() {
        when(storage.globalFilePath()).thenReturn(Path.of("/covered", "global.json"));
        assertThat(writer.globalConfigFilePath()).isEqualTo(Path.of("/covered", "global.json").toString());
    }

    @Test
    @DisplayName("globalConfigFilePath 存储返回 null → 回退 user.home 默认（不 NPE）")
    void globalConfigFilePath_nullStoragePath_fallsBackNexusaiHome() {
        when(storage.globalFilePath()).thenReturn(null);
        assertThat(writer.globalConfigFilePath())
            .as("storage.globalFilePath() 为 null 必须回退 user.home/.nexusai.json（G3：废弃 nexusai.home 固定 user.home，非相对路径）")
            .isEqualTo(Path.of(System.getProperty("user.home", "."), ".nexusai.json").toString());
    }

    @Test
    @DisplayName("globalConfigFilePath 未装配存储（null）→ 兜底 {user.home}/.nexusai.json（G3：废弃 nexusai.home，固定 user.home）")
    void globalConfigFilePath_noStorage_fallsBackNexusaiHome() {
        McpConfigFileWriter w = new McpConfigFileWriter(null, null);
        assertThat(w.globalConfigFilePath())
            .isEqualTo(Path.of(System.getProperty("user.home", "."), ".nexusai.json").toString());
    }

    // ── projectMcpJsonPath（cwd 域入口） ──

    @Test
    @DisplayName("projectMcpJsonPath → CwdResolution.getCwd()/.mcp.json")
    void projectMcpJsonPath_usesCurrentCwd() {
        assertThat(writer.projectMcpJsonPath())
            .isEqualTo(Path.of(CwdResolution.getCwd(), ".mcp.json"));
    }
}

package com.nexusai.application.agent.mcp.config;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP 配置文件路径描述器（DB 唯一源改造后角色）· 保留 {@link #describeMcpConfigFilePath}
 * （对齐 CC describeMcpConfigFilePath，utils.ts:254-271）+ 依赖的路径解析
 * {@link #projectMcpJsonPath()} / {@link #globalConfigFilePath()} + 构造器委托
 * {@link FileConfigStorage}。
 *
 * <p><b>架构简化（用户拍板 2026-08-30）：MCP 写只写 DB，读只读 DB。</b>原 AC-1 方案 A
 * 「REST add = 写配置源文件（.mcp.json 原子写对齐 writeMcpjsonFile config.ts:88-131 /
 * user → .nexusai.json）+ 同步 upsert DB」的<b>双写已删除</b>——配置持久化语义改由
 * {@code mcp_servers} 表 scope 列（V59）唯一承载。本类不再做任何配置源文件写回
 * （addServer/putServer/removeServer/removeServerBestEffort/removeServerExcept 及其私有实现、
 * writeMcpjsonFile 原子写链均已删除），也不再提供读取（readScopeServers /
 * readProjectServersBestEffort / readUserServers 已删——读侧统一走 DB）。
 *
 * <p>.mcp.json 仅保留「手动 import 入口」：{@code McpServerService.importFromMcpJson}
 * 经 {@link McpConfigLoader} + {@link McpJsonConfigParser} 直接读文件一次性导入 DB，
 * 不经过本类。
 *
 * <p>{@link #describeMcpConfigFilePath} 仍供 create/update 响应 DTO 的 {@code filePath}
 * 展示（前端 G5 展示每个 server 的配置文件路径），scope 从 DB 记录取（DB 唯一源）。
 */
@Component
public class McpConfigFileWriter {

    private static final Logger log = LoggerFactory.getLogger(McpConfigFileWriter.class);

    private final FileConfigStorage fileConfigStorage;
    private final McpEnterpriseConfig enterpriseConfig;

    /**
     * 决策 G3（2026-08-30）：nexusai.home 已废弃，不再注入。路径兜底直接固定 user.home——
     * 与 FileConfigStorage.globalFilePath() 的 {user.home}/.nexusai.json 一致（fileConfigStorage
     * 恒在场委托，此兜底仅测试 mock 触发）。
     */
    public McpConfigFileWriter(
            @Autowired(required = false) FileConfigStorage fileConfigStorage,
            @Autowired(required = false) McpEnterpriseConfig enterpriseConfig) {
        this.fileConfigStorage = fileConfigStorage;
        this.enterpriseConfig = enterpriseConfig;
    }

    // ── 路径解析 ──

    /** project scope 目标：CwdResolution.getCwd(sessionId)/.mcp.json（cwd 域既有入口，仅供路径描述）。 */
    public Path projectMcpJsonPath() {
        return Path.of(CwdResolution.getCwd(), ".mcp.json");
    }

    /** user scope 目标：{@code <user.home>/.nexusai.json}（对齐 CC getGlobalClaudeFile）。 */
    public String globalConfigFilePath() {
        if (fileConfigStorage != null) {
            // 委托 FileConfigStorage.globalFilePath()：ConfigStorageProperties.getGlobalFile()
            // 覆盖时报告真实写入路径（describeMcpConfigFilePath 必须与实际文件一致）。
            // null 兜底（测试 mock / 未装配）：回退 user.home 默认路径，不 NPE。
            Path p = fileConfigStorage.globalFilePath();
            if (p != null) {
                return p.toString();
            }
            if (log.isDebugEnabled()) {
                log.debug("[McpConfigFileWriter] FileConfigStorage.globalFilePath() 为 null，回退 user.home 默认路径");
            }
        }
        return Path.of(System.getProperty("user.home", "."), ".nexusai.json").toString();
    }

    // ── describeMcpConfigFilePath（utils.ts:254-271，供 create/update 响应 filePath 展示） ──

    /** 描述 scope 对应配置文件路径 · 对齐 CC describeMcpConfigFilePath。 */
    public String describeMcpConfigFilePath(String scope, String cwd) {
        switch (scope) {
            case "user":
                return globalConfigFilePath();
            case "project":
                return Path.of(cwd, ".mcp.json").toString();
            case "local":
                return globalConfigFilePath() + " [project: " + cwd + "]";
            case "dynamic":
                return "Dynamically configured";
            case "enterprise":
                return enterpriseConfig != null ? enterpriseConfig.getEnterpriseMcpFilePath() : "managed-mcp.json";
            case "claudeai":
                return "claude.ai";
            default:
                return scope;
        }
    }
}
